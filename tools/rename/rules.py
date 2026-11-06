"""Name mapping for the internal rename org.jkiss.dbeaver -> com.seizia.chromite (docs/chromite/internal-rename.md).

Why this module is only data
----------------------------
rename.py (moves, content rewrite, headers, upstream patch conversion) and gate.py (checks) must apply exactly the
same mapping, otherwise the gate cannot prove that a rewritten tree is the mapped image of the old one. Keeping every
rule here, ordered and with its reason, makes the mapping reviewable in one place and deterministic: the same input
always gives the same output, and applying it to an already renamed tree changes nothing (idempotent), because no
replacement contains a pattern that a rule matches again.

How a text is transformed
-------------------------
1. GLOBAL_KEEP spans are cut out first and never touched (external URLs, mail and domain names, the one class that
   stays in deps/: org.jkiss.dbeaver.DBException).
2. RULES are applied in order to the remaining text. A rule may carry its own KEEP list: matches that fall inside a
   KEEP match of the current text are left alone (used for external contract values such as the OpenAI originator).
3. Rules marked "code" run only in code mode; DOC-mode files (Markdown, agent instructions, IDE settings) get only
   the structural rules so that prose and history keep their wording (manual review in commit C5).

Rule ids R1..R7 follow docs/chromite/internal-rename.md section 2.
"""
import re

OLD_DOT = 'org.jkiss.dbeaver'
NEW_DOT = 'com.seizia.chromite'

# Files that are never read or rewritten: copied upstream code, legal texts, and this tool's own data.
EXCLUDE_PREFIXES = (
    'deps/',
    'tools/rename/',
    'features/com.seizia.chromite.drivers.public.feature/drivers-root/',
)
EXCLUDE_FILES = {
    'NOTICE',
    'LICENSE.md',
    'docs/chromite/internal-rename.md',
}

# DOC mode: only structural names (packages, bundle paths, type names, file names) are mapped, see module docstring.
DOC_SUFFIXES = ('.md',)
DOC_PREFIXES = ('.agents/', '.github/skills/', '.ide/')
DOC_FILES = {'osgi-app.properties', '.github/copilot-instructions.md', '.github/copilot-code-review-instructions.md'}

BINARY_SUFFIXES = (
    '.png', '.gif', '.jpg', '.jpeg', '.ico', '.icns', '.bmp', '.jar', '.zip', '.gz', '.ttf', '.otf', '.woff',
    '.woff2', '.db', '.sqlite', '.dll', '.so', '.dylib', '.exe', '.class', '.pdf', '.xlsx', '.xls', '.docx',
    '.mp3', '.wav', '.tiff', '.cur', '.jnilib', '.keystore', '.jks', '.p12', '.der',
)

# ---------------------------------------------------------------------------------------------------------------------
# Spans that no rule may touch.
GLOBAL_KEEP = [
    # External web addresses keep pointing to upstream; Chromite's own GitHub URLs are mapped (negative lookahead).
    r'(?:https?|ftp)://(?![^\s"\'<>()\]]*froiibn/chromite)[^\s"\'<>()\]`]+',
    # Mail addresses and host names of DBeaver Corp services (external).
    r'[\w.+-]+@[\w-]*dbeaver\.(?:com|io|net|org)\b',
    r'(?<![\w-])[\w-]*(?:\.[\w-]+)*\.?dbeaver\.(?:com|io|net|org)(?![\w-]|\.[A-Za-z0-9])',
    # The only class of the old package that stays: it lives in deps/ (org.jkiss.utils), which is not renamed.
    r'org[./\\]jkiss[./\\]dbeaver[./\\]DBException(?![A-Za-z0-9_])',
    # JDBC URLs and Maven coordinates name external artifacts and drivers.
    r'jdbc:[A-Za-z0-9]+:[^\s"\'<>]*',
    r'maven:/[^\s"\'<>]+',
]

# Values defined by deps/ or by external services. Generic lower/upper case rules leave them alone.
LOWER_KEEP = [
    # com.dbeaver.* left after R3 is defined by deps/ (common, datadam, jdbc, rest, messaging) or by DBeaver products.
    r'(?<![A-Za-z0-9_])com[./]dbeaver(?![A-Za-z0-9_])',
    r'jdbc:dbeaver',
    r'dbeaver-common', r'dbeaver-deps-ce', r'dbeaver-ce\b', r'dbeaver-version(?![\w-])', r'repo\.p2\.dbeaver[\w.-]*',
    r'repo\.maven\.dbeaver[\w.-]*', r'dbeaver\.common\.version',
    r'(?<="originator", ")dbeaver',  # OpenAI account login: registered client originator
    r'dbeaver_tidb_plugin',  # TiDB server-side application name
    r'dbeaver-storage', r'dbeaver-desktop', r'dbeaver-com\b', r'dbeaver-project-(?:file|revision)',  # DataDam
    r'dbeaver\.rpc\.', r'dbeaver\.mcp\.', r'dbeaver\.disable\.secure\.masking',
    r'dbeaver\.ssl\.disableCertificateValidation',  # system properties read by deps/ code
    r'(?<![\w.-])dbeaver/(?:dbeaver|pro|github-actions|checkstyle-nullability-annotations|action-java-checkstyle|'
    r'dbeaver-common|cloudbeaver|repo)\b',  # upstream GitHub repositories and issue references
    r'(?<![\w.-])dbeaver/(?:issues|pull|wiki)\b',  # tail of an upstream "dbeaver/dbeaver/issues/N" reference
    r'(?<![\w.-])dbeaver#\d',  # upstream issue number reference
    r'(?<=<!-- )dbeaver (?:common|ce)\b',  # product/aggregate/pom.xml comments naming deps/dbeaver-common and upstream CE
    r'dbeaver-community-license',  # upstream license file name (attribution)
    r'dbeaver-code-review',  # upstream agent skill
    r'dbeaver-packaging',  # upstream developer folder in a devtools path
]
UPPER_KEEP = [
    r'SCOPE_DBEAVER_STORAGE_FULL_ACCESS',  # defined in deps/ (datadam sso api)
    r'DBEAVER_COMMON_DIR',  # tools/build.sh: path of deps/dbeaver-common
    r'DBEAVER_OAUTH_APP',  # GitHub OAuth app registered by DBeaver Corp (Copilot login), the value is theirs
    r'PRODUCT_PROPRIETARY_DBEAVER_DESKTOP_PREFIX',  # value "com.dbeaver": detects DBeaver Corp products
]

# Separators in which the old package prefix is written: dotted, path, Windows path, CSS id, regex-escaped forms.
_SEPS = [r'\\\\\.', r'\\\.', r'\.', r'/', r'\\\\', r'\\', r'-']


def _pkg_rules():
    rules = []
    for sep in _SEPS:
        new_sep = {r'\\\\\.': r'\\\\.', r'\\\.': r'\\.', r'\.': '.', r'/': '/', r'\\\\': r'\\\\', r'\\': r'\\',
                   r'-': '-'}[sep]
        rules.append(('R1', rf'(?<![A-Za-z0-9_])org{sep}jkiss{sep}dbeaver(?![A-Za-z0-9_])',
                      f'com{new_sep}seizia{new_sep}chromite', 'all', None))
    return rules


# (rule id, regex, replacement, mode 'all'|'code', keep list or None). Order matters: specific before generic.
RULES = [
    ('R1b', r'(?<![A-Za-z0-9_])org\.jkisss\.dbeaver(?![A-Za-z0-9_])', 'com.seizia.chromite', 'all', None),
    *_pkg_rules(),
    # R7: test helper packages that sat directly under org.jkiss (not deps/ org.jkiss.utils).
    ('R7', r'(?<![A-Za-z0-9_])org\.jkiss\.junit(?![A-Za-z0-9_])', 'com.seizia.chromite.junit', 'all', None),
    ('R7', r'(?<![A-Za-z0-9_])org/jkiss/junit(?![A-Za-z0-9_])', 'com/seizia/chromite/junit', 'all', None),
    ('R7', r'(?<![A-Za-z0-9_])org\.jkiss\.util(?![A-Za-z0-9_])', 'com.seizia.chromite.test.util', 'all', None),
    ('R7', r'(?<![A-Za-z0-9_])org/jkiss/util(?![A-Za-z0-9_])', 'com/seizia/chromite/test/util', 'all', None),
    # R3: IDs that upstream DBeaver code declared under com.dbeaver (not defined by deps/).
    ('R3', r'(?<![A-Za-z0-9_])com([./])dbeaver\1(ai|secretController|preferences|ssh\1tunnels|SecureStorage)'
           r'(?![A-Za-z0-9_])', r'com\1seizia\1chromite\1\2', 'all', None),
    # R6: Maven build names.
    ('R6', r'product-dbeaver-ce(?![A-Za-z0-9_-])', 'product-chromite-swt', 'all', None),
    ('R6', r'<groupId>com\.dbeaver\.build</groupId>', '<groupId>com.seizia.chromite.build</groupId>', 'code', None),
    ('R6', r'<artifactId>ce\.aggregate</artifactId>', '<artifactId>aggregate</artifactId>', 'code', None),
    ('R6', r'<name>DBeaver CE Aggregate</name>', '<name>Chromite Aggregate</name>', 'code', None),
    ('R6', r'<name>DBeaver P2 Repositories</name>', '<name>Chromite P2 Repositories</name>', 'code', None),
    # R2: types. DBeaverIcons would clash in meaning with the Swing ChromiteIcons, so it becomes SwtIcons.
    ('R2', r'(?<![A-Za-z0-9_])DBeaverIcons(?![A-Za-z0-9_])', 'SwtIcons', 'all', None),
    ('R2', r'(?<![A-Za-z0-9_])DBeaver(?=[A-Z])', 'Chromite', 'all', None),
    ('R2', r'(?<![A-Za-z0-9_])UIFonts\.DBeaver(?![A-Za-z0-9_])', 'UIFonts.Chromite', 'all', None),
    ('R2', r'(?<=public static class )DBeaver(?= \{)', 'Chromite', 'code', None),
    # R5: product definition files (Tycho pairs <name>.product with <name>.p2.inf).
    ('R5', r'(?<![A-Za-z0-9_])DBeaver(?=\.(?:product|p2\.inf)(?![A-Za-z0-9_]))', 'Chromite', 'all', None),
    # R5: image, workbench model and CSS file names referenced as paths (prose keeps the word, see DOC mode).
    ('R5', r'(?<![A-Za-z])dbeaver(?=[\w{},-]*\.(?:png|ico|icns|bmp|xpm|e4xmi|css|svg|gif)(?![A-Za-z0-9_]))',
     'chromite', 'all', LOWER_KEEP),
    # Vendor of every bundle and feature (plan decision 12).
    ('VENDOR', r'Bundle-Vendor: DBeaver Corp', 'Bundle-Vendor: Chromite contributors', 'code', None),
    ('VENDOR', r'provider-name="DBeaver Corp"', 'provider-name="Chromite contributors"', 'code', None),
    # R4: Windows administrator policy registry key (plan decision 17).
    ('R4', r'Software\\\\DBeaver Corp\\\\DBeaver\\\\policy', r'Software\\\\Chromite\\\\policy', 'code', None),
    # R4: run-time identifiers: environment variables, constants (DBEAVER_*), system properties (dbeaver.*),
    # file and folder names (.dbeaver, dbeaver-debug.log), plugin.xml ids, Maven parent artifactId and properties.
    ('R4', r'(?<![A-Za-z])DBEAVER(?![a-z])', 'CHROMITE', 'code', UPPER_KEEP),
    ('R4', r'(?<![A-Za-z])dbeaver', 'chromite', 'code', LOWER_KEEP),
]

_COMPILED = None


def compiled():
    global _COMPILED
    if _COMPILED is None:
        keep = re.compile('|'.join(f'(?:{k})' for k in GLOBAL_KEEP))
        rules = []
        for rid, pat, repl, mode, keeps in RULES:
            k = re.compile('|'.join(f'(?:{x})' for x in keeps)) if keeps else None
            rules.append((rid, re.compile(pat), repl, mode, k))
        _COMPILED = (keep, rules)
    return _COMPILED


def mode_of(path):
    """'skip', 'doc' or 'code' for a repository-relative path (forward slashes)."""
    if path in EXCLUDE_FILES or path.startswith(EXCLUDE_PREFIXES):
        return 'skip'
    if path.lower().endswith(BINARY_SUFFIXES):
        return 'binary'
    if path in DOC_FILES or path.endswith(DOC_SUFFIXES) or path.startswith(DOC_PREFIXES):
        return 'doc'
    return 'code'


def _apply_rules(text, mode, counter):
    _, rules = compiled()
    for rid, rx, repl, rmode, keep in rules:
        if rmode == 'code' and mode != 'code':
            continue
        if keep is None:
            text, n = rx.subn(repl, text)
        else:
            spans = [m.span() for m in keep.finditer(text)]
            n = 0

            def sub(m, spans=spans):
                nonlocal n
                s = m.start()
                for a, b in spans:
                    if a <= s < b:
                        return m.group(0)
                n += 1
                return m.expand(repl)

            text = rx.sub(sub, text)
        if n and counter is not None:
            counter[rid] = counter.get(rid, 0) + n
    return text


def transform(text, mode='code', counter=None):
    """Map a text (str, any newline style; only ASCII patterns are matched) to the new names."""
    if mode in ('skip', 'binary'):
        return text
    keep, _ = compiled()
    out, pos = [], 0
    for m in keep.finditer(text):
        out.append(_apply_rules(text[pos:m.start()], mode, counter))
        out.append(m.group(0))
        pos = m.end()
    out.append(_apply_rules(text[pos:], mode, counter))
    return ''.join(out)


def transform_path(path):
    """New repository path for a file. Paths always use the code-mode rules, so file names follow the references."""
    if path.startswith(EXCLUDE_PREFIXES) or path in EXCLUDE_FILES:
        return path
    return transform(path, 'code')
