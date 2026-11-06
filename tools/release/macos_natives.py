#!/usr/bin/env python3
"""Find, sign and check every Mach-O binary of a Chromite macOS app, including the ones inside jars.

Why this script exists
----------------------
Apple's notary service opens every archive it finds in a submission (zip, jar) and requires
every Mach-O binary in it to carry a Developer ID signature with a secure timestamp and the
hardened runtime. A Chromite .app is an Eclipse product: besides the launcher in
Contents/MacOS it holds native libraries as loose files of unpacked bundles (Equinox launcher
library, JNA) and as entries of jars (SWT, JDBC drivers, ...). `codesign --deep` signs neither
kind: the loose files lie outside the bundle's code locations and jar entries are not files.
So this script finds them by their magic number (not by file name: the Equinox launcher
library is a Mach-O named *.so), signs each one, and writes the signed bytes back into its jar.

Rewriting a jar:
  * entries keep their order, names, timestamps, attributes and compression method
    (MANIFEST.MF stays the first entry);
  * nested archives (a jar inside a jar or a bundle folder) are handled inside-out;
  * a jar that is jar-signed (META-INF/*.SF) loses that signature when one of its entries is
    replaced: the replaced entry no longer matches its digest, and a JarFile opened with
    verification (URLClassLoader, e.g. JDBC driver loading) would throw SecurityException.
    The signature files (META-INF/*.SF, *.RSA, *.DSA, *.EC, SIG-*) of such a jar are removed,
    which makes it an unsigned jar. Jars without Mach-O are not touched and keep their signature.

Commands:
  scan <app|folder>       list every Mach-O (loose files and archive entries) with its current
                          signature; nothing is changed. Runs on any OS (the dry run on Windows).
  sign <app>              (macOS) sign every Mach-O except the bundle's main executable, which
                          release-macos.sh signs with the app; needs MAC_SIGN_IDENTITY and
                          MAC_SIGN_KEYCHAIN in the environment.
  check <app> <team-id>   (macOS) every Mach-O found by `scan` is signed by the team's
                          Developer ID with a secure timestamp and the hardened runtime.

Design, file lists and decisions: docs/chromite/signing.md.

Change history:
- 2026-11-06 Created for the signed release workflow (.github/workflows/release.yml).
"""

import fnmatch
import io
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

# Mach-O magic numbers as the first 4 bytes read big-endian (thin 32/64-bit in both byte orders).
THIN_MAGICS = {0xFEEDFACE, 0xFEEDFACF, 0xCEFAEDFE, 0xCFFAEDFE}
# Universal (fat) binaries. 0xCAFEBABE is also the magic of a Java .class file; a fat header is
# followed by a small architecture count, a class file by minor/major version (major >= 45).
FAT_MAGICS = {0xCAFEBABE, 0xCAFEBABF}
MAX_FAT_ARCHS = 20
ZIP_MAGIC = b"PK\x03\x04"
# Jar signature files (JAR File Specification, "Signed JAR File"): directly under META-INF/.
JAR_SIGNATURE_PATTERNS = ("*.SF", "*.RSA", "*.DSA", "*.EC", "SIG-*")
LC_CODE_SIGNATURE = 0x1D
CSMAGIC_EMBEDDED_SIGNATURE = 0xFADE0CC0
CSMAGIC_BLOBWRAPPER = 0xFADE0B01


def is_macho(head):
    if len(head) < 8:
        return False
    magic, count = struct.unpack(">II", head[:8])
    if magic in THIN_MAGICS:
        return True
    return magic in FAT_MAGICS and 0 < count <= MAX_FAT_ARCHS


def is_zip(head):
    return head[:4] == ZIP_MAGIC


def _slice_signature(data, base):
    """Signature of one thin Mach-O slice starting at `base`: none, adhoc or the signer's name."""
    magic = struct.unpack_from(">I", data, base)[0]
    if magic in (0xCEFAEDFE, 0xCFFAEDFE):
        order = "<"
    elif magic in (0xFEEDFACE, 0xFEEDFACF):
        order = ">"
    else:
        return "unknown"
    header_size = 32 if magic in (0xCFFAEDFE, 0xFEEDFACF) else 28
    ncmds = struct.unpack_from(order + "I", data, base + 16)[0]
    off = base + header_size
    for _ in range(ncmds):
        cmd, size = struct.unpack_from(order + "II", data, off)
        if cmd == LC_CODE_SIGNATURE:
            dataoff, datasize = struct.unpack_from(order + "II", data, off + 8)
            blob = data[base + dataoff:base + dataoff + datasize]
            return _superblob_signer(blob)
        if size == 0:
            break
        off += size
    return "none"


def _superblob_signer(blob):
    if len(blob) < 12 or struct.unpack_from(">I", blob, 0)[0] != CSMAGIC_EMBEDDED_SIGNATURE:
        return "unknown"
    count = struct.unpack_from(">I", blob, 8)[0]
    for i in range(count):
        _, offset = struct.unpack_from(">II", blob, 12 + 8 * i)
        magic, length = struct.unpack_from(">II", blob, offset)
        if magic == CSMAGIC_BLOBWRAPPER:
            if length <= 8:
                return "adhoc"
            cms = blob[offset + 8:offset + length]
            # The signer's certificate subject is DER text inside the CMS blob.
            for prefix in (b"Developer ID Application: ", b"Apple Development: ", b"Apple Distribution: "):
                at = cms.find(prefix)
                if at >= 0:
                    # The common name ends with "(<team id>)"; the DER byte after it may be printable.
                    end = cms.find(b")", at)
                    end = end + 1 if end >= 0 else at + len(prefix)
                    return cms[at:end].decode("ascii", "replace")
            return "cms"
    return "adhoc"


def signature_of(data):
    """Signature state of a Mach-O (every slice of a universal binary, joined by ' | ')."""
    try:
        magic, count = struct.unpack_from(">II", data, 0)
        if magic in FAT_MAGICS:
            is64 = magic == 0xCAFEBABF
            states = []
            for i in range(count):
                if is64:
                    offset = struct.unpack_from(">Q", data, 8 + 32 * i + 8)[0]
                else:
                    offset = struct.unpack_from(">I", data, 8 + 20 * i + 8)[0]
                states.append(_slice_signature(data, offset))
            return " | ".join(sorted(set(states)))
        return _slice_signature(data, 0)
    except struct.error:
        return "unreadable"


def is_jar_signature_file(name):
    if not name.startswith("META-INF/") or name.count("/") != 1:
        return False
    base = name[len("META-INF/"):].upper()
    return any(fnmatch.fnmatch(base, pattern) for pattern in JAR_SIGNATURE_PATTERNS)


class Walker:
    """Walks a folder; for each Mach-O calls `on_macho(label, data) -> data` (new bytes or the same)."""

    def __init__(self, on_macho, rewrite):
        self.on_macho = on_macho
        self.rewrite = rewrite
        self.archives = {}  # archive label -> {"machos": n, "jar_signed": bool}

    def walk_folder(self, root, skip=()):
        skip = {os.path.normpath(p) for p in skip}
        for folder, dirs, files in os.walk(root):
            dirs.sort()
            for name in sorted(files):
                path = os.path.join(folder, name)
                if os.path.islink(path) or os.path.normpath(path) in skip:
                    continue
                label = os.path.relpath(path, root).replace(os.sep, "/")
                with open(path, "rb") as f:
                    head = f.read(8)
                if is_macho(head):
                    with open(path, "rb") as f:
                        data = f.read()
                    self.on_macho(label, data, path)
                elif is_zip(head):
                    with open(path, "rb") as f:
                        data = f.read()
                    new = self.walk_archive(label, data)
                    if new is not None and self.rewrite:
                        mode = os.stat(path).st_mode
                        tmp = path + ".chromite-signing.tmp"
                        with open(tmp, "wb") as f:
                            f.write(new)
                        os.chmod(tmp, mode)
                        os.replace(tmp, path)

    def walk_archive(self, label, data):
        """Returns the rewritten archive bytes, or None when nothing in it changed."""
        try:
            zin = zipfile.ZipFile(io.BytesIO(data))
        except zipfile.BadZipFile:
            return None
        replacements = {}
        machos = 0
        with zin:
            infos = zin.infolist()
            jar_signed = any(is_jar_signature_file(i.filename) for i in infos)
            for info in infos:
                if info.is_dir():
                    continue
                entry = zin.read(info)
                inner = label + "!/" + info.filename
                if is_macho(entry[:8]):
                    machos += 1
                    new = self.on_macho(inner, entry, None)
                    if new is not entry:
                        replacements[info.filename] = new
                elif is_zip(entry[:4]):
                    new = self.walk_archive(inner, entry)
                    if new is not None:
                        replacements[info.filename] = new
            if machos:
                self.archives[label] = {"machos": machos, "jar_signed": jar_signed}
            if not replacements or not self.rewrite:
                return None
            return rewrite_zip(zin, infos, replacements, jar_signed, label)


def rewrite_zip(zin, infos, replacements, jar_signed, label):
    out = io.BytesIO()
    dropped = []
    with zipfile.ZipFile(out, "w") as zout:
        zout.comment = zin.comment
        for info in infos:
            if jar_signed and is_jar_signature_file(info.filename):
                dropped.append(info.filename)
                continue
            data = replacements.get(info.filename)
            if data is None:
                data = zin.read(info)
            copy = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            copy.compress_type = info.compress_type
            copy.external_attr = info.external_attr
            copy.create_system = info.create_system
            copy.comment = info.comment
            zout.writestr(copy, data, compress_type=info.compress_type)
    if dropped:
        print("  %s: jar signature removed (%s)" % (label, ", ".join(dropped)))
    return out.getvalue()


def main_executable(app):
    """Contents/MacOS/<CFBundleExecutable> of the app (signed with the app, not here)."""
    import plistlib

    with open(os.path.join(app, "Contents", "Info.plist"), "rb") as f:
        info = plistlib.load(f)
    return os.path.join(app, "Contents", "MacOS", info["CFBundleExecutable"])


def cmd_scan(root):
    found = []

    def record(label, data, _path):
        found.append((label, len(data), signature_of(data)))
        return data

    walker = Walker(record, rewrite=False)
    walker.walk_folder(root)
    loose = [f for f in found if "!/" not in f[0]]
    in_archives = [f for f in found if "!/" in f[0]]
    print("Mach-O files in %s: %d (loose %d, inside archives %d)" % (root, len(found), len(loose), len(in_archives)))
    print()
    print("Loose files:")
    for label, size, sig in loose:
        print("  %s  (%d bytes; signature: %s)" % (label, size, sig))
    print()
    print("Archives holding Mach-O (count; jar-signed archives lose that signature when re-signed):")
    for label in sorted(walker.archives):
        a = walker.archives[label]
        print("  %3d  %s%s" % (a["machos"], label, "  [jar-signed]" if a["jar_signed"] else ""))
    print()
    print("Archive entries:")
    for label, size, sig in in_archives:
        print("  %s  (%d bytes; signature: %s)" % (label, size, sig))
    return 0


def codesign_base():
    identity = os.environ.get("MAC_SIGN_IDENTITY")
    keychain = os.environ.get("MAC_SIGN_KEYCHAIN")
    if not identity or not keychain:
        sys.exit("macos_natives.py sign: MAC_SIGN_IDENTITY and MAC_SIGN_KEYCHAIN must be set")
    # Hardened runtime and a secure timestamp on every binary (notarization requirements).
    # No entitlements: they belong to the main executable (release-macos.sh signs it with the app).
    return ["codesign", "--force", "--sign", identity, "--keychain", keychain,
            "--options", "runtime", "--timestamp"]


def cmd_sign(app):
    base = codesign_base()
    work = tempfile.mkdtemp(prefix="chromite-sign-")
    counter = [0]

    def sign(label, data, path):
        if path is not None:
            # A loose file: signed in place.
            subprocess.run(base + [path], check=True)
            print("signed %s" % label)
            return data
        counter[0] += 1
        tmp = os.path.join(work, "%05d-%s" % (counter[0], os.path.basename(label)))
        with open(tmp, "wb") as f:
            f.write(data)
        subprocess.run(base + [tmp], check=True)
        with open(tmp, "rb") as f:
            signed = f.read()
        os.remove(tmp)
        print("signed %s" % label)
        return signed

    try:
        walker = Walker(sign, rewrite=True)
        walker.walk_folder(app, skip={main_executable(app)})
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


def cmd_check(app, team):
    work = tempfile.mkdtemp(prefix="chromite-check-")
    failures = []
    count = [0]

    def check(label, data, path):
        count[0] += 1
        target = path
        if target is None:
            target = os.path.join(work, "%05d-%s" % (count[0], os.path.basename(label)))
            with open(target, "wb") as f:
                f.write(data)
        result = subprocess.run(["codesign", "-dvv", target], capture_output=True, text=True)
        info = result.stdout + result.stderr
        lines = info.splitlines()
        problems = []
        if result.returncode != 0:
            problems.append("not signed")
        if not any(l.startswith("Authority=Developer ID Application: ") for l in lines):
            problems.append("no Developer ID Application authority")
        if ("TeamIdentifier=%s" % team) not in lines:
            problems.append("not the team's signature")
        if not any(l.startswith("Timestamp=") for l in lines):
            problems.append("no secure timestamp")
        if not any(l.startswith("CodeDirectory ") and "runtime" in l for l in lines):
            problems.append("no hardened runtime")
        if problems:
            failures.append("%s: %s" % (label, ", ".join(problems)))
        if path is None:
            os.remove(target)
        return data

    try:
        Walker(check, rewrite=False).walk_folder(app)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    if failures:
        print("\n".join(failures), file=sys.stderr)
        sys.exit("%d of %d Mach-O files are not signed as required" % (len(failures), count[0]))
    print("%s: all %d Mach-O files (loose and inside jars) carry the team's Developer ID, "
          "a secure timestamp and the hardened runtime" % (app, count[0]))
    return 0


def main(argv):
    if len(argv) == 3 and argv[1] == "scan":
        return cmd_scan(argv[2])
    if len(argv) == 3 and argv[1] == "sign":
        return cmd_sign(argv[2])
    if len(argv) == 4 and argv[1] == "check":
        return cmd_check(argv[2], argv[3])
    sys.exit("usage: macos_natives.py scan <app|folder> | sign <app> | check <app> <team-id>")


if __name__ == "__main__":
    sys.exit(main(sys.argv))
