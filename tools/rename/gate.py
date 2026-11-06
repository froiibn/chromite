#!/usr/bin/env python3
"""Static gates for the internal rename (docs/chromite/internal-rename.md section "검증").

Usage (repository root, Python 3 standard library only):
  python tools/rename/gate.py                 G1-G5, G8 on the checked-out tree (CI runs this on every build)
  python tools/rename/gate.py --base REV      also G6 (ID sets) and G7 (newlines/BOM) against the tree of REV

Why a gate
----------
The rename touched about ten thousand files; the build alone does not prove it complete. A string extension id that
still has the old prefix only makes a feature disappear at run time, and a later change (an upstream cherry-pick, a
copy-pasted snippet) can bring the old names back. The gates read every tracked file outside deps/ and fail on:

  G1  org.jkiss.dbeaver anywhere except org.jkiss.dbeaver.DBException (the one class that stays in deps/)
  G2  other old forms: org/jkiss/dbeaver, org-jkiss-dbeaver, the org.jkisss typo, org.jkiss.junit, org.jkiss.util,
      com.dbeaver.ai/secretController/preferences/ssh.tunnels/SecureStorage, Maven profile product-dbeaver-ce
  G3  type names DBeaver<Name> and UIFonts.DBeaver
  G4  every bundle and feature: folder name == Bundle-SymbolicName / feature id == Maven artifactId; every <module>
      of a pom.xml exists
  G5  references resolve: extension points (point=, schema=), platform:/plugin/, bundleclass://, class= in
      plugin.xml, NLS BUNDLE_NAME resource files
  G6  (--base) the IDs declared and referenced in OSGi descriptors and the Java type names of REV, mapped with
      rules.py, equal the ones of the current tree
  G7  (--base) per file: BOM, newline style (CRLF/LF/mixed) and final newline are unchanged; totals are printed
  G8  paths in .github/workflows that point into the repository exist, and target/products/<id> matches a product uid

Allowed places for old names: http(s) URLs to upstream, this tool, docs/chromite/internal-rename.md (the mapping),
CLAUDE.md (the rule), NOTICE and LICENSE.md, deps/.
"""
import argparse
import collections
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rules  # noqa: E402

# CLAUDE.md states the rule itself and has to name the old prefix.
ALLOW_FILES = {'docs/chromite/internal-rename.md', 'CLAUDE.md', 'NOTICE', 'LICENSE.md'}
ALLOW_PREFIXES = ('deps/', 'tools/rename/')
URL = re.compile(r'(?:https?|ftp)://[^\s"\'<>()\]`]+')

G1 = re.compile(r'org\.jkiss\.dbeaver(?!\.DBException(?![A-Za-z0-9_]))')
G2 = [
    re.compile(r'org[/\\-]jkiss[/\\-]dbeaver(?![/\\]DBException(?![A-Za-z0-9_]))'),
    re.compile(r'org\.jkisss\.'),
    re.compile(r'org[./]jkiss[./]junit\b'),
    re.compile(r'org[./]jkiss[./]util(?![A-Za-z0-9_])'),
    re.compile(r'com[./]dbeaver[./](?:ai|secretController|preferences|ssh[./]tunnels|SecureStorage)(?![A-Za-z0-9_])'),
    re.compile(r'product-dbeaver-ce(?![\w-])'),
    re.compile(r'org\.jkiss\.(?!code\b|utils\b|api\b|bundle\b|wmi\b|awt\b|jdbc\b|dbeaver\.DBException\b)[a-z]'),
]
# References that were already broken upstream before the rename (same 13 on the pre-rename tree with the old prefix;
# 11 of them were in the SWT bundles removed on 2026-11-07, docs/chromite/swt-removal.md).
# They are reported but do not fail the gate; fixing them is outside the rename (CLAUDE.md principle 1).
KNOWN_BROKEN = {
    'plugins/com.seizia.chromite.ext.ocient/plugin.xml: platform:/plugin/com.seizia.chromite.ext.generic/icons/ocient_icon.png missing',
    'plugins/com.seizia.chromite.ext.oracle/plugin.xml: class com.seizia.chromite.ext.oracle.model.resolvers.OracleUserResolver missing',
}
G3 = [re.compile(r'(?<![A-Za-z0-9_])DBeaver[A-Z][A-Za-z0-9_]*'), re.compile(r'\bUIFonts\.DBeaver\b')]


def git(*args):
    return subprocess.run(['git', *args], capture_output=True, check=True).stdout


def tracked(rev=None):
    if rev:
        out = git('ls-tree', '-r', '-z', '--name-only', rev)
    else:
        out = git('ls-files', '-z')
    return sorted(p.decode('utf-8') for p in out.split(b'\0') if p)


class Tree:
    """Text files of the working tree (rev None) or of a commit, outside deps/."""

    def __init__(self, rev=None):
        self.rev = rev
        self.paths = tracked(rev)
        self._cache = {}
        self._proc = None

    def read(self, p):
        if p in self._cache:
            return self._cache[p]
        if self.rev is None:
            try:
                with open(p, 'rb') as f:
                    data = f.read()
            except OSError:
                data = b''
        else:
            if self._proc is None:
                self._proc = subprocess.Popen(['git', 'cat-file', '--batch'], stdin=subprocess.PIPE,
                                              stdout=subprocess.PIPE)
            self._proc.stdin.write(f'{self.rev}:{p}\n'.encode('utf-8'))
            self._proc.stdin.flush()
            hdr = self._proc.stdout.readline().split()
            data = self._proc.stdout.read(int(hdr[2]))
            self._proc.stdout.read(1)
        self._cache[p] = data
        return data

    def text(self, p):
        return self.read(p).decode('latin-1')

    def texts(self):
        for p in self.paths:
            if p.startswith(ALLOW_PREFIXES) or rules.mode_of(p) in ('skip', 'binary'):
                continue
            data = self.read(p)
            if b'\0' in data:
                continue
            yield p, data.decode('latin-1')

    def exists(self, p):
        if self.rev is None:
            return os.path.exists(p)
        return p in self._set() or any(x.startswith(p.rstrip('/') + '/') for x in self._set())

    def _set(self):
        if not hasattr(self, '_s'):
            self._s = set(self.paths)
        return self._s


def scan(tree, patterns, name, failures):
    hits = []
    for p, t in tree.texts():
        if p in ALLOW_FILES:
            continue
        t2 = URL.sub(' ', t)
        for rx in patterns:
            for m in rx.finditer(t2):
                line = t2.count('\n', 0, m.start()) + 1
                hits.append(f'{p}:{line}: {m.group(0)}')
    report(name, hits, failures)


def report(name, hits, failures, limit=30):
    print(f'{name}: {len(hits)}')
    for h in hits[:limit]:
        print('   ' + h)
    if len(hits) > limit:
        print(f'   ... {len(hits) - limit} more')
    if hits:
        failures.append(name)


# ---------------------------------------------------------------------------------------------------------- G4 --
def manifest_bsn(text):
    m = re.search(r'^Bundle-SymbolicName:\s*([^;\s]+)', text, re.M)
    return m.group(1) if m else None


def pom_artifact(text):
    t = re.sub(r'<parent>.*?</parent>', '', text, flags=re.S)
    t = re.sub(r'<(dependencies|build|profiles|dependencyManagement)>.*?</\1>', '', t, flags=re.S)
    m = re.search(r'<artifactId>([^<]+)</artifactId>', t)
    return m.group(1).strip() if m else None


def g4(tree, failures):
    hits = []
    paths = set(tree.paths)
    for p in tree.paths:
        parts = p.split('/')
        if len(parts) == 4 and parts[0] in ('plugins', 'test') and parts[2:] == ['META-INF', 'MANIFEST.MF']:
            d = parts[1]
            bsn = manifest_bsn(tree.text(p))
            pom = f'{parts[0]}/{d}/pom.xml'
            art = pom_artifact(tree.text(pom)) if pom in paths else None
            if bsn != d or art != d:
                hits.append(f'{parts[0]}/{d}: Bundle-SymbolicName={bsn} artifactId={art}')
        if len(parts) == 3 and parts[0] == 'features' and parts[2] == 'feature.xml':
            d = parts[1]
            m = re.search(r'<feature[^>]*?\bid="([^"]+)"', tree.text(p), re.S)
            fid = m.group(1) if m else None
            pom = f'features/{d}/pom.xml'
            art = pom_artifact(tree.text(pom)) if pom in paths else None
            if fid != d or art != d:
                hits.append(f'features/{d}: id={fid} artifactId={art}')
        if p.endswith('pom.xml') and not p.startswith('deps/'):
            base = os.path.dirname(p)
            for mod in re.findall(r'<module>([^<]+)</module>', tree.text(p)):
                target = os.path.normpath(os.path.join(base, mod.strip())).replace('\\', '/')
                if not tree.exists(target + '/pom.xml') and not tree.exists(target):
                    hits.append(f'{p}: module {mod} missing')
    report('G4 bundle/feature names and modules', hits, failures)


# ---------------------------------------------------------------------------------------------------------- G5 --
def bundles(tree):
    """{symbolic name: bundle folder}"""
    out = {}
    for p in tree.paths:
        parts = p.split('/')
        if p.endswith('META-INF/MANIFEST.MF') and len(parts) >= 3:
            out[manifest_bsn(tree.text(p))] = '/'.join(parts[:-2])
    return out


def java_types(tree):
    """{fully qualified top-level type name}"""
    types = set()
    for p in tree.paths:
        if not p.endswith('.java') or p.startswith('deps/'):
            continue
        m = re.search(r'/src/(?:main/java/|test/java/)?(.+)\.java$', p)
        if m:
            types.add(m.group(1).replace('/', '.'))
    return types


def g5(tree, failures, prefix=rules.NEW_DOT):
    hits = []
    bmap = bundles(tree)
    types = java_types(tree)
    points = set()
    xmls = [p for p in tree.paths if p.endswith('/plugin.xml') and not p.startswith('deps/')]
    for p in xmls:
        bfolder = os.path.dirname(p)
        bsn = next((b for b, f in bmap.items() if f == bfolder), None)
        t = tree.text(p)
        for m in re.finditer(r'<extension-point\b([^>]*)>', t):
            attrs = m.group(1)
            ida = re.search(r'\bid="([^"]+)"', attrs)
            if ida:
                pid = ida.group(1)
                points.add(pid if '.' in pid or not bsn else f'{bsn}.{pid}')
            sch = re.search(r'\bschema="([^"]+)"', attrs)
            if sch and not tree.exists(f'{bfolder}/{sch.group(1)}'):
                hits.append(f'{p}: schema {sch.group(1)} missing')

    def type_exists(fqcn):
        fqcn = fqcn.split(':')[0].split('$')[0].strip()
        while fqcn:
            if fqcn in types:
                return True
            if '.' not in fqcn:
                return False
            fqcn = fqcn.rsplit('.', 1)[0]  # nested class written with a dot
            if fqcn.split('.')[-1][:1].islower():
                return False
        return False

    for p in xmls + [x for x in tree.paths if x.endswith(('.e4xmi', '.product', '.ini', '.xml', '.properties', '.css'))
                     and not x.startswith('deps/') and x not in xmls]:
        if rules.mode_of(p) != 'code':
            continue
        t = tree.text(p)
        if p in xmls:
            for pt in re.findall(r'\bpoint="([^"]+)"', t):
                if pt.startswith(prefix) and pt not in points:
                    hits.append(f'{p}: point {pt} not declared')
            for cls in re.findall(r'\bclass="([^"]+)"', t):
                if cls.startswith(prefix + '.') and not type_exists(cls):
                    hits.append(f'{p}: class {cls} missing')
        for b, path in re.findall(r'platform:/plugin/([\w.]+)/([^"\'\s,;)]*)', t):
            if b.startswith(prefix):
                if b not in bmap:
                    hits.append(f'{p}: platform:/plugin/{b} bundle missing')
                elif path and '*' not in path and not tree.exists(f'{bmap[b]}/{path.rstrip("/")}'):
                    hits.append(f'{p}: platform:/plugin/{b}/{path} missing')
        for b, cls in re.findall(r'bundleclass://([\w.]+)/([\w.$]+)', t):
            if b.startswith(prefix):
                if b not in bmap:
                    hits.append(f'{p}: bundleclass://{b} bundle missing')
                elif not type_exists(cls):
                    hits.append(f'{p}: bundleclass://{b}/{cls} class missing')
    for p in tree.paths:
        if p.endswith('.java') and not p.startswith('deps/'):
            t = tree.text(p)
            for res in re.findall(r'BUNDLE_NAME\s*=\s*"([\w.]+)"', t):
                if not res.startswith(prefix):
                    continue
                folder = p.split('/src/')[0]
                rel = res.replace('.', '/') + '.properties'
                if not tree.exists(f'{folder}/src/{rel}'):
                    hits.append(f'{p}: BUNDLE_NAME {res} has no {rel}')
    known = [h for h in hits if h in KNOWN_BROKEN]
    print(f'G5 known upstream defects (not failing): {len(known)}')
    report('G5 references', [h for h in hits if h not in KNOWN_BROKEN], failures)


# ---------------------------------------------------------------------------------------------------------- G6 --
ID_ATTRS = re.compile(r'\b(?:id|point|class|commandId|categoryId|type|definitionId|contributorClass|helpContextId|'
                      r'locationURI|plugin|schema|application|productId|splashId|editorId|parentId|targetID|'
                      r'objectClass|adaptableType|activityId|pattern|contextId|schemeId|menubarPath|toolbarPath|'
                      r'icon|image|uid|launcher)="([^"]*)"')
OLD_TOKEN = re.compile(r'[\w.$/\\:-]*(?:jkiss|dbeaver|DBeaver|DBEAVER|chromite|Chromite|CHROMITE|SwtIcons)'
                       r'[\w.$/\\:-]*')


def descriptor_ids(tree, mapper=None):
    ids = collections.Counter()
    for p in tree.paths:
        if p.startswith(ALLOW_PREFIXES) or not p.endswith(
                ('plugin.xml', 'feature.xml', '.product', 'MANIFEST.MF', '.e4xmi', 'fragment.xml')):
            continue
        t = tree.text(p)
        vals = ID_ATTRS.findall(t) if not p.endswith('MANIFEST.MF') else re.findall(
            r'^(?:Bundle-SymbolicName|Require-Bundle|Export-Package|Import-Package|Fragment-Host)?[: ]+([\w.]+)',
            t, re.M)
        found = [mapper(tok) if mapper else tok for v in vals for tok in OLD_TOKEN.findall(v)]
        if p.endswith('MANIFEST.MF'):
            # rename.py drops an Export-Package entry that became a duplicate; a manifest lists each name once
            found = sorted(set(found))
        for tok in found:
            ids[tok] += 1
    for p in tree.paths:
        if p.endswith('.java') and not p.startswith(ALLOW_PREFIXES):
            m = re.search(r'/src/(?:main/java/|test/java/)?(.+)\.java$', p)
            if m:
                name = m.group(1).replace('/', '.')
                ids[mapper(name) if mapper else name] += 1
    return ids


def g6(base, cur, failures):
    before = descriptor_ids(base, lambda s: rules.transform(s, 'code'))
    after = descriptor_ids(cur)
    hits = []
    for k in sorted(set(before) | set(after)):
        if before[k] != after[k]:
            hits.append(f'{k}: mapped base {before[k]} != current {after[k]}')
    print(f'G6 ids compared: {sum(before.values())} mapped base / {sum(after.values())} current, '
          f'{len(before)} distinct')
    report('G6 mapped id sets differ', hits, failures)


# ---------------------------------------------------------------------------------------------------------- G7 --
def eol_stats(data):
    crlf = data.count(b'\r\n')
    lf = data.count(b'\n') - crlf
    cls = 'none' if not crlf and not lf else 'crlf' if not lf else 'lf' if not crlf else 'mixed'
    return data.startswith(b'\xef\xbb\xbf'), crlf, lf, cls, data.endswith(b'\n')


def g7(base, cur, failures):
    old_paths = set(base.paths)
    totals = collections.Counter()
    hits = []
    for newp in cur.paths:
        if newp.startswith('deps/'):
            continue
        oldp = None
        if newp in old_paths:
            oldp = newp
        else:
            # find the old path whose mapping is this path
            oldp = _reverse(base).get(newp)
        if oldp is None:
            continue
        a, b = base.read(oldp), _blob(newp)
        if b'\0' in a:
            continue
        sa, sb = eol_stats(a), eol_stats(b)
        totals['files'] += 1
        totals['bom_before'] += sa[0]
        totals['bom_after'] += sb[0]
        totals['crlf_before'] += sa[1]
        totals['crlf_after'] += sb[1]
        totals['lf_before'] += sa[2]
        totals['lf_after'] += sb[2]
        totals[f'class_{sa[3]}_before'] += 1
        totals[f'class_{sb[3]}_after'] += 1
        if sa[0] != sb[0] or sa[3] != sb[3] or sa[4] != sb[4]:
            hits.append(f'{oldp} -> {newp}: bom {sa[0]}->{sb[0]} eol {sa[3]}->{sb[3]} final-newline {sa[4]}->{sb[4]}')
    print('G7 totals (index blobs): ' + ', '.join(f'{k}={v}' for k, v in sorted(totals.items())))
    report('G7 newline/BOM changes', hits, failures)


_REV = {}


def _reverse(base):
    if not _REV:
        for p in base.paths:
            _REV[rules.transform_path(p)] = p
    return _REV


_BLOBP = None


def _blob(path):
    """Index blob of the current tree (newline bytes as stored, independent of core.autocrlf)."""
    global _BLOBP
    if _BLOBP is None:
        _BLOBP = subprocess.Popen(['git', 'cat-file', '--batch'], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    _BLOBP.stdin.write(f':{path}\n'.encode('utf-8'))
    _BLOBP.stdin.flush()
    hdr = _BLOBP.stdout.readline().split()
    if len(hdr) < 3:
        return b''
    data = _BLOBP.stdout.read(int(hdr[2]))
    _BLOBP.stdout.read(1)
    return data


# ---------------------------------------------------------------------------------------------------------- G8 --
def g8(tree, failures):
    hits = []
    uids = set()
    for p in tree.paths:
        if p.endswith('.product') and not p.startswith('deps/'):
            m = re.search(r'\buid="([^"]+)"', tree.text(p))
            if m:
                uids.add(m.group(1))
    for p in tree.paths:
        if not p.startswith('.github/workflows/'):
            continue
        # YAML comments may cite paths of other projects (setup-java docs/...); only commands and values count
        t = re.sub(r'(?m)^\s*#.*$', '', tree.text(p))
        for uid in re.findall(r'target[/\\]products[/\\]([\w.]+)', t):
            if uid not in uids:
                hits.append(f'{p}: target/products/{uid} is not a product uid')
        for m in re.finditer(r'(?<![\w$./-])((?:product|plugins|features|tools|test|docs)[/\\][\w./\\-]+)', t):
            path = m.group(1).replace('\\', '/').rstrip('./')
            if '/target/' in path + '/' or '*' in path:
                continue
            if not tree.exists(path):
                hits.append(f'{p}: path {path} missing')
    report('G8 workflow paths', hits, failures)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--base', help='commit before the rename, enables G6 and G7')
    args = ap.parse_args()
    failures = []
    cur = Tree()
    scan(cur, [G1], 'G1 org.jkiss.dbeaver outside DBException', failures)
    scan(cur, G2, 'G2 other old forms', failures)
    scan(cur, G3, 'G3 DBeaver type names', failures)
    g4(cur, failures)
    g5(cur, failures)
    g8(cur, failures)
    if args.base:
        base = Tree(args.base)
        g6(base, cur, failures)
        g7(base, cur, failures)
    print('FAILED: ' + ', '.join(failures) if failures else 'ALL GATES PASSED')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
