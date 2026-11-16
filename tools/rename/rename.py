#!/usr/bin/env python3
"""Internal rename tool: org.jkiss.dbeaver -> com.seizia.chromite (docs/chromite/internal-rename.md).

Usage (from the repository root, Python 3 standard library only):
  python tools/rename/rename.py plan    [--out DIR]   report moves, rewrites and collisions; changes nothing
  python tools/rename/rename.py move                  move files to their new paths (index + working tree)
  python tools/rename/rename.py rewrite               rewrite file contents with the rules in rules.py
  python tools/rename/rename.py headers --base REV    add the Chromite modification line to Java files changed since REV
  python tools/rename/rename.py patch IN OUT          map an upstream patch (git format-patch / diff) to the new names
  python <renamed tree>/tools/rename/rename.py convert --base REV --converted REV
                                                      convert a branch started before the rename (see the doc)

Why the tool works on git blobs, not on the working tree
--------------------------------------------------------
The rename must not change a single byte other than the names: no newline conversion, no BOM loss, no encoding
change. The working tree on Windows is shaped by core.autocrlf (CRLF on disk, LF in the index) and a few files have
mixed newlines in the index. Reading the index blobs, transforming them as bytes (latin-1 round trip, every pattern is
ASCII) and writing blobs back with --no-filters keeps the stored bytes exact whatever the local git settings are.
Moves re-point the index entry to the same blob, so git records every move as a 100% rename. The working tree is
re-checked out from the index afterwards.

The tool is deterministic (sorted inputs, no clocks) and idempotent: running a step again on its own output finds
nothing to do. Any collision (two old paths mapping to one new path, or a new path that already exists, also
case-insensitively) stops the run before anything is written.
"""
import argparse
import collections
import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rules  # noqa: E402

MOD_LINE = ' * Modifications Copyright (C) 2026 Chromite contributors'


def git(*args, inp=None, check=True):
    r = subprocess.run(['git', *args], input=inp, capture_output=True)
    if check and r.returncode != 0:
        sys.exit(f'git {" ".join(args)} failed: {r.stderr.decode("utf-8", "replace")}')
    return r.stdout


def index_entries():
    """[(mode, sha, path)] of stage-0 index entries, sorted by path."""
    out = []
    for rec in git('ls-files', '-s', '-z').split(b'\0'):
        if not rec:
            continue
        meta, path = rec.split(b'\t', 1)
        mode, sha, stage = meta.split()
        if stage != b'0':
            sys.exit(f'unmerged entry {path!r}')
        out.append((mode.decode(), sha.decode(), path.decode('utf-8')))
    return sorted(out, key=lambda e: e[2])


class BlobReader:
    def __init__(self):
        self.p = subprocess.Popen(['git', 'cat-file', '--batch'], stdin=subprocess.PIPE, stdout=subprocess.PIPE)

    def read(self, sha):
        self.p.stdin.write(sha.encode() + b'\n')
        self.p.stdin.flush()
        header = self.p.stdout.readline().split()
        size = int(header[2])
        data = self.p.stdout.read(size)
        self.p.stdout.read(1)
        return data

    def close(self):
        self.p.stdin.close()
        self.p.wait()


def write_blobs(contents):
    """Store byte strings as blobs without any filter; returns their ids in order."""
    if not contents:
        return []
    with tempfile.TemporaryDirectory() as tmp:
        paths = []
        for i, data in enumerate(contents):
            fp = os.path.join(tmp, f'{i}.bin')
            with open(fp, 'wb') as f:
                f.write(data)
            paths.append(fp)
        out = git('hash-object', '-w', '--no-filters', '--stdin-paths', inp='\n'.join(paths).encode() + b'\n')
    return out.decode().split()


def is_text(path, data):
    return rules.mode_of(path) not in ('skip', 'binary') and b'\0' not in data


def checkout(paths):
    if paths:
        git('checkout-index', '-f', '-z', '--stdin', inp='\0'.join(paths).encode('utf-8') + b'\0')


# ------------------------------------------------------------------------------------------------------------- plan --
def compute_moves(entries):
    moves, collisions = {}, []
    all_old = {p for _, _, p in entries}
    targets = collections.defaultdict(list)
    for _, _, p in entries:
        n = rules.transform_path(p)
        targets[n].append(p)
        if n != p:
            moves[p] = n
    for n, olds in targets.items():
        if len(olds) > 1:
            collisions.append(f'{n} <- {olds}')
        elif n != olds[0] and n in all_old:
            collisions.append(f'{n} already exists (from {olds[0]})')
    folded = collections.defaultdict(set)
    for n in targets:
        folded[n.lower()].add(n)
    for k, v in folded.items():
        if len(v) > 1:
            collisions.append(f'case-insensitive clash: {sorted(v)}')
    return moves, sorted(collisions)


def cmd_plan(args):
    entries = index_entries()
    moves, collisions = compute_moves(entries)
    reader = BlobReader()
    counter = {}
    changed, review = [], collections.Counter()
    tok = re.compile(r'[\w.@${}/\\-]*(?:dbeaver|DBEAVER|DBeaver)[\w.${}/\\-]*')
    for _, sha, p in entries:
        mode = rules.mode_of(p)
        data = reader.read(sha)
        if not is_text(p, data):
            continue
        text = data.decode('latin-1')
        new = rules.transform(text, mode, counter)
        if new != text:
            changed.append(p)
        # Old-name tokens that survive the transform: listed for review (kept on purpose or missed).
        for m in tok.findall(new):
            review[(mode, m)] += 1
    reader.close()
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, 'moves.tsv'), 'w', encoding='utf-8', newline='\n') as f:
        for o in sorted(moves):
            f.write(f'{o}\t{moves[o]}\n')
    with open(os.path.join(args.out, 'rewrites.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(changed) + '\n')
    with open(os.path.join(args.out, 'survivors.tsv'), 'w', encoding='utf-8', newline='\n') as f:
        for (mode, t), c in sorted(review.items(), key=lambda kv: (-kv[1], kv[0])):
            f.write(f'{c}\t{mode}\t{t}\n')
    with open(os.path.join(args.out, 'collisions.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(collisions) + ('\n' if collisions else ''))
    print(f'files in index: {len(entries)}')
    print(f'moves: {len(moves)}')
    print(f'content rewrites: {len(changed)}')
    print('replacements by rule: ' + ', '.join(f'{k}={v}' for k, v in sorted(counter.items())))
    print(f'collisions: {len(collisions)}')
    for c in collisions[:20]:
        print('  ' + c)
    return 1 if collisions else 0


# ------------------------------------------------------------------------------------------------------------- move --
def cmd_move(args):
    entries = index_entries()
    moves, collisions = compute_moves(entries)
    if collisions:
        sys.exit('collisions, nothing moved:\n' + '\n'.join(collisions))
    if not moves:
        print('nothing to move')
        return 0
    info = []
    for mode, sha, p in entries:
        if p in moves:
            info.append(f'0 {"0" * 40}\t{p}')
            info.append(f'{mode} {sha}\t{moves[p]}')
    git('update-index', '--index-info', inp=('\n'.join(info) + '\n').encode('utf-8'))
    checkout(sorted(moves.values()))
    for old in sorted(moves):
        if os.path.lexists(old):
            os.remove(old)
    for old in sorted(moves, reverse=True):
        d = os.path.dirname(old)
        while d and os.path.isdir(d) and not os.listdir(d):
            os.rmdir(d)
            d = os.path.dirname(d)
    print(f'moved {len(moves)} files')
    return 0


# ---------------------------------------------------------------------------------------------------------- rewrite --
_EXPORT_HDR = re.compile(r'^Export-Package:', re.M)


def dedupe_export_package(text):
    """Two old packages can map to one (org.jkiss.dbeaver.X next to an existing com.seizia.chromite.X).
    OSGi rejects a duplicate Export-Package entry, so the later duplicate line is dropped."""
    m = _EXPORT_HDR.search(text)
    if not m:
        return text
    nl = '\r\n' if '\r\n' in text else '\n'
    start = m.start()
    lines = text[start:].split(nl)
    header = [lines[0]]
    i = 1
    while i < len(lines) and lines[i].startswith(' '):
        header.append(lines[i])
        i += 1
    if len(header) == 1:
        return text
    first = header[0][len('Export-Package:'):].strip().rstrip(',').split(';')[0].strip()
    seen, kept = {first} if first else set(), [header[0]]
    for ln in header[1:]:
        name = ln.strip().rstrip(',').split(';')[0].strip()
        if name in seen:
            continue
        seen.add(name)
        kept.append(ln)
    if len(kept) == len(header):
        return text
    last = kept[-1].rstrip()
    kept[-1] = last[:-1] if last.endswith(',') else last
    return text[:start] + nl.join(kept + lines[i:])


_PKG = re.compile(r'^package\s+([\w.]+)\s*;', re.M)
_IMPORT = re.compile(r'^import\s+(static\s+)?([\w.*]+)\s*;[^\n]*\n', re.M)


def fix_dbexception_import(text):
    """Classes of the old root package used org.jkiss.dbeaver.DBException without an import (same package), and
    'import org.jkiss.dbeaver.*' covered it. DBException stays in deps/, so such files get an explicit import."""
    pm = _PKG.search(text)
    if not pm:
        return text
    in_root = pm.group(1) == rules.NEW_DOT
    wildcard = re.search(r'^import\s+com\.seizia\.chromite\.\*\s*;', text, re.M)
    if not (in_root or wildcard):
        return text
    if re.search(r'^import\s+org\.jkiss\.dbeaver\.DBException\s*;', text, re.M):
        return text
    body = text[pm.end():]
    body_no_comments = re.sub(r'/\*.*?\*/|//[^\n]*', '', body, flags=re.S)
    if not re.search(r'(?<![\w.])DBException(?![\w])', body_no_comments):
        return text
    nl = '\r\n' if '\r\n' in text[:pm.end() + 200] else '\n'
    line = 'import org.jkiss.dbeaver.DBException;' + nl
    imports = [m for m in _IMPORT.finditer(text) if not m.group(1)]
    for m in imports:
        if m.group(2) > 'org.jkiss.dbeaver.DBException' and not m.group(2).startswith('java'):
            return text[:m.start()] + line + text[m.start():]
    non_java = [m for m in imports if not m.group(2).startswith('java')]
    if non_java:
        at = non_java[-1].end()
        return text[:at] + line + text[at:]
    at = pm.end()
    eol = text.index('\n', at) + 1
    return text[:eol] + nl + line + text[eol:]


def rewrite_one(path, data, counter=None):
    text = data.decode('latin-1')
    new = rules.transform(text, rules.mode_of(path), counter)
    if path.endswith('MANIFEST.MF'):
        new = dedupe_export_package(new)
    if path.endswith('.java'):
        new = fix_dbexception_import(new)
    return new.encode('latin-1')


def cmd_rewrite(args):
    entries = index_entries()
    reader = BlobReader()
    counter = {}
    todo = []
    for mode, sha, p in entries:
        data = reader.read(sha)
        if not is_text(p, data):
            continue
        new = rewrite_one(p, data, counter)
        if new != data:
            todo.append((mode, p, new))
    reader.close()
    shas = write_blobs([d for _, _, d in todo])
    info = [f'{mode} {sha}\t{p}' for (mode, p, _), sha in zip(todo, shas)]
    if info:
        git('update-index', '--index-info', inp=('\n'.join(info) + '\n').encode('utf-8'))
        checkout([p for _, p, _ in todo])
    print(f'rewrote {len(todo)} files')
    print('replacements by rule: ' + ', '.join(f'{k}={v}' for k, v in sorted(counter.items())))
    return 0


# ---------------------------------------------------------------------------------------------------------- headers --
def add_mod_line(text):
    head = text[:2000]
    if 'Modifications Copyright' in head or 'DBeaver Corp' not in head or not head.lstrip().startswith('/*'):
        return text
    m = re.search(r'^[ \t]*\*[^\n]*Copyright[^\n]*DBeaver Corp[^\n]*?(\r?\n)', text[:2000], re.M)
    if not m:
        return text
    return text[:m.end()] + MOD_LINE + m.group(1) + text[m.end():]


def cmd_headers(args):
    changed = set()
    for rec in git('diff', '--name-only', '-z', '--cached', args.base).split(b'\0'):
        if rec:
            changed.add(rec.decode('utf-8'))
    entries = index_entries()
    reader = BlobReader()
    todo = []
    for mode, sha, p in entries:
        if p not in changed or not p.endswith('.java') or rules.mode_of(p) != 'code':
            continue
        data = reader.read(sha)
        text = data.decode('latin-1')
        new = add_mod_line(text)
        if new != text:
            todo.append((mode, p, new.encode('latin-1')))
    reader.close()
    shas = write_blobs([d for _, _, d in todo])
    info = [f'{mode} {sha}\t{p}' for (mode, p, _), sha in zip(todo, shas)]
    if info:
        git('update-index', '--index-info', inp=('\n'.join(info) + '\n').encode('utf-8'))
        checkout([p for _, p, _ in todo])
    print(f'added the modification line to {len(todo)} Java files')
    return 0


# ---------------------------------------------------------------------------------------------------------- convert --
def cmd_convert(args):
    """Convert a branch that was started before the rename (docs/chromite/internal-rename.md "진행 중이던 브랜치 옮기기").

    The same move + rewrite + headers steps that produced devel are applied to the whole branch tree. The result is
    committed as a merge whose second parent is the devel commit holding the converted pre-rename tree (the commit
    "Mark renamed DBeaver Java files as modified by Chromite"). That commit then becomes the merge base with devel:
    a later 'git merge devel' compares new names with new names, so it needs no rename detection, and files that both
    sides only renamed cannot conflict. Run it with the rename.py of the renamed devel (same rules version)."""
    if git('status', '--porcelain', '--untracked-files=no').strip():
        sys.exit('working tree or index has changes; commit or stash them first')
    r = subprocess.run(['git', 'merge-base', '--is-ancestor', args.base, 'HEAD'])
    if r.returncode != 0:
        sys.exit(f'{args.base} is not an ancestor of HEAD: merge it into the branch first')
    converted = git('rev-parse', '--verify', args.converted + '^{commit}').decode().strip()
    r = subprocess.run(['git', 'merge-base', '--is-ancestor', args.base, converted])
    if r.returncode != 0:
        sys.exit(f'{args.converted} does not descend from {args.base}')
    head = git('rev-parse', 'HEAD').decode().strip()
    for step in (cmd_move, cmd_rewrite, cmd_headers):
        rc = step(args)
        if rc:
            return rc
    tree = git('write-tree').decode().strip()
    msg = '\n'.join([
        'Convert to Chromite names',
        '',
        f'Generated by: python tools/rename/rename.py convert --base {args.base} --converted {converted}',
        '',
        'The second parent is the converted pre-rename tree on devel, so merging devel next compares new names',
        'with new names (docs/chromite/internal-rename.md).',
        ''])
    commit = git('commit-tree', tree, '-p', head, '-p', converted, inp=msg.encode('utf-8')).decode().strip()
    git('update-ref', '-m', 'rename.py convert', 'HEAD', commit, head)
    print(f'committed {commit[:10]} (parents {head[:10]}, {converted[:10]})')
    print('next: git merge <renamed devel>, resolve conflicts if any, then python tools/rename/gate.py and a full build')
    return 0


# ------------------------------------------------------------------------------------------------------------ patch --
def cmd_patch(args):
    """Upstream commits use the old names. Paths in diff headers are mapped like moved files, every other line like
    file content (mode chosen by the file the hunk belongs to). Apply the result with 'git apply -3' and review."""
    with open(args.inp, 'rb') as f:
        data = f.read().decode('latin-1')
    out, mode = [], 'code'
    path_line = re.compile(r'^(diff --git a/|--- a/|\+\+\+ b/|rename from |rename to |copy from |copy to )(\S.*)$')
    for line in data.splitlines(keepends=True):
        body = line.rstrip('\r\n')
        eol = line[len(body):]
        m = path_line.match(body)
        if m:
            if body.startswith('diff --git a/'):
                a, _, b = m.group(2).partition(' b/')
                mode = rules.mode_of(a)
                out.append(f'diff --git a/{rules.transform_path(a)} b/{rules.transform_path(b)}{eol}')
            else:
                out.append(m.group(1) + rules.transform_path(m.group(2)) + eol)
        else:
            out.append(rules.transform(body, mode) + eol)
    with open(args.out, 'wb') as f:
        f.write(''.join(out).encode('latin-1'))
    print(f'wrote {args.out}')
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('plan')
    p.add_argument('--out', default=os.path.join(tempfile.gettempdir(), 'chromite-rename-plan'))
    sub.add_parser('move')
    sub.add_parser('rewrite')
    p = sub.add_parser('headers')
    p.add_argument('--base', required=True)
    p = sub.add_parser('convert')
    p.add_argument('--base', required=True, help='devel commit just before the rename (first parent of its merge)')
    p.add_argument('--converted', required=True,
                   help='devel commit whose tree is the converted --base ("Mark renamed DBeaver Java files ...")')
    p = sub.add_parser('patch')
    p.add_argument('inp')
    p.add_argument('out')
    args = ap.parse_args()
    return {'plan': cmd_plan, 'move': cmd_move, 'rewrite': cmd_rewrite, 'headers': cmd_headers,
            'convert': cmd_convert, 'patch': cmd_patch}[args.cmd](args)


if __name__ == '__main__':
    sys.exit(main())
