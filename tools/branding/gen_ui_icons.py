#!/usr/bin/env python3
"""Generate the Chromite Swing UI icon packs (plugins/com.seizia.chromite.ui.swing/icons/ui).

Why this script exists
----------------------
The Swing frontend ships two selectable icon packs (View > Theme > Icon Style):

  duotone  "DataGrip-style duo-tone" (default): 1px outline + light fill, neutral grey
           objects, blue containers/routines, orange primary keys.
  color    "SSMS-style color": Visual Studio image-library-like saturated colours
           (tan folders, blue cylinder, gold/silver keys).

Each pack has a light and a dark variant with the same geometry; only the colours
differ (palettes in PAL below, documented in docs/chromite/design-spec.md
"아이콘 팩"). The SVG files are generated, never edited by hand: change this script
and run it again.

All icons are original 16x16 drawings. Shapes follow Tabler Icons (MIT License,
Copyright (c) 2020-2026 Pawel Kuna, https://github.com/tabler/tabler-icons) geometry,
re-drawn on a 16px grid (1px strokes on .5 coordinates so they rasterise crisply),
with fills, colours and small badges added. No JetBrains or Microsoft artwork is used.
The Tabler license is shipped next to the icons (icons/ui/LICENSE-tabler.txt) and
listed in NOTICE.

Badges (view, mview, column-pk, column-fk, new-query, new-connection, export) cut a
1px gap out of the base glyph with an SVG <mask>. JSVG 2.1 (the renderer of the Swing
frontend) renders these masks correctly (checked 2026-11-06), so they are kept.

How to regenerate
-----------------
    python tools/branding/gen_ui_icons.py

Python 3 standard library only. Writes
plugins/com.seizia.chromite.ui.swing/icons/ui/{duotone,color}/{light,dark}/<name>.svg
(the file names are the icon ids used by UiIcons.java) and prints "ok".
"""
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), os.pardir, os.pardir,
                    "plugins", "com.seizia.chromite.ui.swing", "icons", "ui")
# Pack id -> output folder (UiIconStyle in the Swing bundle uses the same folder names)
PACK_DIRS = {"A": "duotone", "B": "color"}

# --------------------------------------------------------------------------- palettes
# role keys:  n=neutral, b=blue, o=orange, g=green, r=red, y=yellow, p=purple
#             *s = stroke / strong colour, *f = light fill
PAL = {
    ("A", "light"): dict(
        ns="#6C707E", nf="#EBECF0", bs="#3574F0", bf="#E7EFFD", os="#E66D17", of="#FFF4EB",
        gs="#208A3C", gf="#F2FCF3", rs="#DB3B4B", rf="#FFF7F7", ys="#FFAF0F", yf="#FFF6DE",
        ps="#834DF0", pf="#FAF5FF", ts="#1E8C8C", tf="#E6F7F7", w="#FFFFFF", ink="#FFFFFF"),
    ("A", "dark"): dict(
        ns="#CED0D6", nf="#43454A", bs="#548AF7", bf="#25324D", os="#E08855", of="#45322B",
        gs="#5FB865", gf="#253627", rs="#DB5C5C", rf="#402929", ys="#F2C55C", yf="#3D3223",
        ps="#A571E6", pf="#2F2936", ts="#3FB5B5", tf="#1F3536", w="#FFFFFF", ink="#1E1F22"),
    # Set B: Visual Studio image-library style colours (outline + saturated fills)
    ("B", "light"): dict(
        ns="#424242", nf="#F6F6F6", line="#8A8A8A",
        bs="#1E6EC2", bf="#62A6E8", bl="#C9E1F7",
        fold="#DCB67A", fold_s="#B08A48", fold_l="#F0D7A6",
        gold="#F2C811", gold_s="#9C7A15", silver="#A9B1BC", silver_s="#4F5660",
        gs="#388A34", gl="#7FC47A", rs="#D7261B", rl="#F4A39C", ys="#F2C811", ys_s="#9C7A15",
        ps="#7B3FC4", pl="#C9A8EC", os="#C27D1A", ol="#F5C98A", ts="#1F8F86", tl="#9ED9D2",
        skin="#F0C27B", w="#FFFFFF", ink="#FFFFFF", head="#2F7BD0", vhead="#2E9C8F"),
    ("B", "dark"): dict(
        ns="#C8C8C8", nf="#3A3A3D", line="#8A8A8A",
        bs="#59A8F5", bf="#3B7FC4", bl="#24415F",
        fold="#DCB67A", fold_s="#9C7A40", fold_l="#EACB8E",
        gold="#F2C811", gold_s="#C9A20E", silver="#AEB4BC", silver_s="#E0E3E8",
        gs="#5FBF5A", gl="#2F5A2D", rs="#F0584E", rl="#5C2B28", ys="#F2C811", ys_s="#B8940C",
        ps="#B180F0", pl="#45305F", os="#E09A3A", ol="#5A4126", ts="#3FC1B5", tl="#1F4A46",
        skin="#E8B872", w="#FFFFFF", ink="#1E1E1E", head="#3B86D6", vhead="#2FA596"),
}


# --------------------------------------------------------------------------- svg helpers
def _a(fill=None, stroke=None, sw=1, extra=""):
    s = f' fill="{fill or "none"}"'
    if stroke:
        s += f' stroke="{stroke}"'
        if sw != 1:
            s += f' stroke-width="{sw}"'
    return s + extra


def rect(x, y, w, h, rx=0, fill=None, stroke=None, sw=1):
    r = f' rx="{rx}"' if rx else ""
    return f'<rect x="{x}" y="{y}" width="{w}" height="{h}"{r}{_a(fill, stroke, sw)}/>'


def path(d, fill=None, stroke=None, sw=1, cap="round", join="round"):
    ex = ""
    if stroke:
        ex = f' stroke-linecap="{cap}" stroke-linejoin="{join}"'
    return f'<path d="{d}"{_a(fill, stroke, sw, ex)}/>'


def circle(cx, cy, r, fill=None, stroke=None, sw=1):
    return f'<circle cx="{cx}" cy="{cy}" r="{r}"{_a(fill, stroke, sw)}/>'


def ellipse(cx, cy, rx, ry, fill=None, stroke=None, sw=1):
    return f'<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}"{_a(fill, stroke, sw)}/>'


def svg(body, defs=""):
    d = f"<defs>{defs}</defs>" if defs else ""
    return ('<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">'
            f"{d}{body}</svg>\n")


def with_badge(base, badge, halo):
    """Knock a 1px transparent gap around the badge out of the base glyph (works on any background)."""
    defs = (f'<mask id="cut" maskUnits="userSpaceOnUse" x="0" y="0" width="16" height="16">'
            f'<rect width="16" height="16" fill="#fff"/>{halo}</mask>')
    return f'<g mask="url(#cut)">{base}</g>{badge}', defs


# --------------------------------------------------------------------------- shared shapes
def folder_closed(f, s, front=None):
    # Tabler "folder" redrawn on 16px grid
    out = path("M1.5 4A1.5 1.5 0 0 1 3 2.5H6l1.5 1.5H13A1.5 1.5 0 0 1 14.5 5.5v6.5A1.5 1.5 0 0 1 13 13.5H3"
               "A1.5 1.5 0 0 1 1.5 12Z", f, s)
    if front:
        out += path("M1.5 6.5H14.5", None, s)
    return out


def folder_open(back, front, s):
    # Tabler "folder-open" redrawn: back sheet + tilted front flap
    return (path("M1.5 12V4A1.5 1.5 0 0 1 3 2.5H6l1.5 1.5H12A1.5 1.5 0 0 1 13.5 5.5V7", back, s)
            + path("M1.5 13.5L4 7.5H15L12.6 12.6A1.5 1.5 0 0 1 11.2 13.5Z", front, s))


def key_shape(fill, stroke, hole, shaft):
    """Horizontal key (Tabler "key" idea, laid flat so it rasterises crisply)."""
    return (rect(8, 7, 7, 2, 0, shaft) + rect(13, 9, 2, 2.5, 0, shaft) + rect(10, 9, 1.5, 1.5, 0, shaft)
            + circle(5, 8, 3.5, fill, stroke) + circle(5, 8, 1.25, hole))


def key_badge(c, hole, halo=False, outline=None):
    """Solid key for the bottom-right badge slot (x 7..16, y 8.5..16)."""
    if halo:
        return circle(10.5, 12, 4.25, "#000") + rect(12, 9.75, 4, 6.25, 0, "#000")
    o = outline
    return (rect(13, 11, 3, 2, 0, c) + rect(14.25, 13, 1.75, 2, 0, c)
            + circle(10.5, 12, 3 if not o else 2.75, c, o, 0.75 if o else 1) + circle(10.5, 12, 1.1, hole))


def lens_badge(ring, glass, halo=False):
    """Magnifier (Tabler "search") badge, bottom-right."""
    if halo:
        return circle(11, 11, 4.75, "#000") + path("M13 13L15.5 15.5", None, "#000", 4.5)
    return (circle(11, 11, 2.75, glass, ring, 1.5) + path("M13.1 13.1L15 15", None, ring, 1.75))


def plus_badge(c, halo=False):
    if halo:
        return rect(8, 8, 8, 8, 2, "#000")
    return path("M12 9.5V15.5M9 12.5H15", None, c, 2, cap="butt")


def table_shape(f, s, header=None, line=None, head_fill=None, x=1.5, y=2.5, w=13, h=11):
    line = line or s
    out = rect(x, y, w, h, 1.5, f, s)
    hy = y + 3.5
    if head_fill:
        out += path(f"M{x} {hy}V{y+1.5}A1.5 1.5 0 0 1 {x+1.5} {y}H{x+w-1.5}A1.5 1.5 0 0 1 {x+w} {y+1.5}V{hy}Z",
                    head_fill, s)
    out += path(f"M{x+0.5} {hy}H{x+w-0.5}", None, s, cap="butt")
    cx = x + 5
    out += path(f"M{cx} {hy + 0.5}V{y+h-0.5}", None, line, cap="butt")
    if w > 9 and h > 8:
        out += path(f"M{x+0.5} {hy+4}H{x+w-0.5}", None, line, cap="butt")
    return out


def column_shape(f, s, head):
    # one tall column of cells, header cell filled (Tabler "table-column" idea, single column)
    return (rect(2.5, 1.5, 7, 13, 1.5, f, s)
            + path("M2.5 5.5V3A1.5 1.5 0 0 1 4 1.5H8A1.5 1.5 0 0 1 9.5 3V5.5Z", head, s)
            + path("M3 9.5H9", None, s, cap="butt"))


def doc_shape(f, s):
    # Tabler "file": page with folded corner
    return (path("M3.5 2.5A1 1 0 0 1 4.5 1.5H9.5L12.5 4.5V13.5A1 1 0 0 1 11.5 14.5H4.5A1 1 0 0 1 3.5 13.5Z", f, s)
            + path("M9.5 1.5V4.5H12.5", None, s))


# save-all: the back floppy (its visible top and right edges) and a smaller front floppy (Tabler device-floppy)
SAVE_ALL_BACK = "M4.5 4.5V2A.5 .5 0 0 1 5 1.5H12L14.5 4V11A.5 .5 0 0 1 14 11.5H11.5"
SAVE_ALL_FRONT = "M1.5 5A.5 .5 0 0 1 2 4.5H9L11.5 7V14A.5 .5 0 0 1 11 14.5H2A.5 .5 0 0 1 1.5 14Z"


def tile(f, s):
    return rect(1.5, 1.5, 13, 13, 2.5, f, s)


def cylinder(f, s, top=None):
    top = top or f
    return (path("M2.5 4V12C2.5 13.1 4.96 14.5 8 14.5S13.5 13.1 13.5 12V4", f, s)
            + ellipse(8, 4, 5.5, 2.5, top, s)
            + path("M2.5 8C2.5 9.1 4.96 10.5 8 10.5S13.5 9.1 13.5 8", None, s))


def gear(f, s, hole):
    # Tabler "settings" path, scaled 24 -> 16
    g = ("M10.325 4.317c.426 -1.756 2.924 -1.756 3.35 0a1.724 1.724 0 0 0 2.573 1.066c1.543 -.94 3.31 .826 2.37 2.37"
         "a1.724 1.724 0 0 0 1.065 2.572c1.756 .426 1.756 2.924 0 3.35a1.724 1.724 0 0 0 -1.066 2.573c.94 1.543 -.826 3.31 -2.37 2.37"
         "a1.724 1.724 0 0 0 -2.572 1.065c-.426 1.756 -2.924 1.756 -3.35 0a1.724 1.724 0 0 0 -2.573 -1.066c-1.543 .94 -3.31 -.826 -2.37 -2.37"
         "a1.724 1.724 0 0 0 -1.065 -2.572c-1.756 -.426 -1.756 -2.924 0 -3.35a1.724 1.724 0 0 0 1.066 -2.573c-.94 -1.543 .826 -3.31 2.37 -2.37"
         "c1 .608 2.296 .07 2.572 -1.065Z")
    return (f'<g transform="scale(0.6667)">'
            f'<path d="{g}" fill="{f}" stroke="{s}" stroke-width="1.5" stroke-linejoin="round"/>'
            f'<circle cx="12" cy="12" r="3" fill="{hole}" stroke="{s}" stroke-width="1.5"/></g>')


def refresh_arrows(s, sw=1):
    # Tabler "refresh", redrawn on 16px grid
    return (path("M13.5 7.5A5.6 5.6 0 0 0 3 6", None, s, sw) + path("M2.5 2.5V6H6", None, s, sw)
            + path("M2.5 8.5A5.6 5.6 0 0 0 13 10", None, s, sw) + path("M13.5 13.5V10H10", None, s, sw))


# --------------------------------------------------------------------------- icon catalogue
# (key, Korean, English, Tabler base)
CONCEPTS = [
    ("connection", "연결", "Connection", "server-2"),
    ("database", "데이터베이스", "Database", "database"),
    ("schema", "스키마", "Schema", "schema"),
    ("folder", "폴더", "Folder", "folder"),
    ("folder-open", "폴더 (열림)", "Folder open", "folder-open"),
    ("table", "테이블", "Table", "table"),
    ("view", "뷰", "View", "table + search"),
    ("mview", "구체화된 뷰", "Materialized view", "table + search"),
    ("column", "컬럼", "Column", "table-column"),
    ("column-pk", "컬럼 (PK)", "Column PK", "table-column + key"),
    ("column-fk", "컬럼 (FK)", "Column FK", "table-column + key"),
    ("primary-key", "기본 키", "Primary key", "key"),
    ("foreign-key", "외래 키", "Foreign key", "key + link"),
    ("index", "인덱스", "Index", "sort-descending"),
    ("unique", "고유 제약", "Unique constraint", "square-rounded-letter-u"),
    ("check", "검사 제약", "Check constraint", "square-check"),
    ("function", "함수", "Function", "math-function"),
    ("procedure", "프로시저", "Procedure", "A: letter-p · B: original cube"),
    ("trigger", "트리거", "Trigger", "bolt"),
    ("sequence", "시퀀스", "Sequence", "stairs-up"),
    ("user", "사용자", "User", "user"),
    ("role", "역할", "Role", "users"),
    ("datatype", "데이터 타입", "Data type", "letter-t"),
    ("run", "실행", "Run", "player-play"),
    ("stop", "중지", "Stop", "player-stop"),
    ("refresh", "새로 고침", "Refresh", "refresh"),
    ("new-query", "새 쿼리", "New query", "file-plus"),
    ("open", "열기", "Open", "folder-open"),
    ("save", "저장", "Save", "device-floppy"),
    ("export", "내보내기", "Export", "file-export"),
    ("filter", "필터", "Filter", "filter"),
    ("search", "검색", "Search", "search"),
    ("settings", "설정", "Settings", "settings"),
    ("commit", "커밋", "Commit", "check"),
    ("rollback", "롤백", "Rollback", "arrow-back-up"),
    ("error", "오류", "Error", "alert-circle"),
    ("warning", "경고", "Warning", "alert-triangle"),
    ("info", "정보", "Info", "info-circle"),
    # Tool window and editor actions (added for the Swing frontend)
    ("new-connection", "새 연결", "New connection", "server-2 + plus"),
    ("connect", "연결하기", "Connect", "plug"),
    ("disconnect", "연결 끊기", "Disconnect", "plug"),
    ("collapse-all", "모두 접기", "Collapse all", "fold"),
    ("hide", "숨기기", "Hide tool window", "minus"),
    ("close", "닫기", "Close", "x"),
    ("results", "결과", "Results", "table"),
    ("messages", "메시지", "Messages", "message"),
    ("file-sql", "SQL 파일", "SQL file", "file-text"),
    # Properties tool window tool bar (SSMS property grid)
    ("categorized", "분류별", "Categorized", "list-tree"),
    ("sort-alpha", "이름순", "Alphabetical", "sort-ascending-letters"),
    # Registered Servers tool bar
    ("new-group", "새 서버 그룹", "New server group", "folder-plus"),
    # SQL editor: execution plan tab and query history window (also the Query Library run history)
    ("plan", "실행 계획", "Execution plan", "hierarchy-2"),
    ("history", "쿼리 기록", "Query history", "history"),
    # Query Library tool window
    ("library", "쿼리 라이브러리", "Query library", "books"),
    ("library-query", "라이브러리 쿼리", "Library query", "file-text + player-play"),
    ("new-folder", "새 폴더", "New folder", "folder-plus"),
    ("schedule", "예약 실행", "Schedule", "clock"),
    # ER diagram tab and tool bar
    ("diagram", "ER 다이어그램", "ER diagram", "schema (two linked tables)"),
    ("zoom-in", "확대", "Zoom in", "zoom-in"),
    ("zoom-out", "축소", "Zoom out", "zoom-out"),
    ("zoom-fit", "창에 맞추기", "Fit to window", "arrows-maximize"),
    ("layout", "자동 레이아웃", "Auto layout", "hierarchy-3"),
    # Main tool bar (SSMS Standard arrangement) and SQL editor tool bar commands
    ("save-all", "모두 저장", "Save all", "device-floppy (two)"),
    ("undo", "실행 취소", "Undo", "arrow-back-up"),
    ("redo", "다시 실행", "Redo", "arrow-forward-up"),
    ("activity-monitor", "작업 모니터", "Activity monitor", "device-heart-monitor"),
    ("registered-servers", "등록된 서버", "Registered servers", "server-2 + bookmark"),
    ("object-explorer", "개체 탐색기", "Object explorer", "database + binary-tree"),
    ("properties", "속성", "Properties", "adjustments-horizontal"),
    ("comment", "주석 처리", "Comment", "align-left + dashes"),
    ("uncomment", "주석 처리 제거", "Uncomment", "align-left + dashes + slash"),
    ("indent", "들여쓰기", "Increase indent", "indent-increase"),
    ("outdent", "내어쓰기", "Decrease indent", "indent-decrease"),
    ("format", "SQL 서식 지정", "Format SQL", "align-left (indented lines)"),
    ("overflow", "더 보기", "More (tool bar overflow)", "chevrons-right"),
]


def plug(f, s):
    # Tabler "plug" redrawn upright on the 16px grid: two prongs, rounded body, cable
    return (path("M6 1.5V4.5M10 1.5V4.5", None, s)
            + path("M3.5 4.5H12.5V7A4.5 4.5 0 0 1 3.5 7Z", f, s)
            + path("M8 11.5V14.5", None, s))


def collapse_arrows(s, sw=1):
    # Two chevrons pointing at a centre line (Tabler "fold")
    return (path("M5 2.5L8 5.5L11 2.5", None, s, sw) + path("M5 13.5L8 10.5L11 13.5", None, s, sw)
            + path("M3.5 8H12.5", None, s, sw))


def bubble(f, s):
    # Tabler "message": speech bubble with two text lines
    return (path("M2.5 3.5A1 1 0 0 1 3.5 2.5H12.5A1 1 0 0 1 13.5 3.5V10.5A1 1 0 0 1 12.5 11.5H7L4.5 13.5V11.5H3.5"
                 "A1 1 0 0 1 2.5 10.5Z", f, s)
            + path("M5 5.5H11M5 8.5H9", None, s, cap="butt"))


def list_tree(s, dot, sw=1):
    # Tabler "list-tree": three lines indented one step each, a dot before each
    return (circle(3, 3.5, 1, dot) + path("M5.5 3.5H13.5", None, s, sw, cap="butt")
            + circle(5, 8, 1, dot) + path("M7.5 8H13.5", None, s, sw, cap="butt")
            + circle(7, 12.5, 1, dot) + path("M9.5 12.5H13.5", None, s, sw, cap="butt"))


def sort_letters(s, arrow, sw=1):
    # Tabler "sort-ascending-letters": a down arrow, "A" above "Z"
    return (path("M4.5 2.5V13.5M2.5 11.5L4.5 13.5L6.5 11.5", None, arrow, sw)
            + path("M9.5 7.5V4A1.5 1.5 0 0 1 12.5 4V7.5M9.5 5.5H12.5", None, s, sw)
            + path("M9.5 9.5H12.5L9.5 13.5H12.5", None, s, sw))


def plan_tree(f, s, link, sw=1):
    # Tabler "hierarchy-2" turned sideways like a plan: a root box feeding two operator boxes
    return (rect(1.5, 5.5, 5, 5, 1, f, s, sw) + rect(9.5, 1.5, 5, 5, 1, f, s, sw) + rect(9.5, 9.5, 5, 5, 1, f, s, sw)
            + path("M6.5 8H8M8 4V12M8 4H9.5M8 12H9.5", None, link, sw, cap="butt"))


def clock_back(f, s, hand, sw=1):
    # Tabler "history": a clock face whose rim ends in a back arrow
    return (path("M3.2 4.5A6 6 0 1 1 2 8", f, s, sw) + path("M2 2.5V5H4.5", None, s, sw)
            + path("M8 5V8L10 10", None, hand, sw))
def books(n, nf, blue, blue_fill):
    # Tabler "books": two upright books and a leaning one
    return (rect(1.5, 2.5, 3.5, 11, 0.75, nf, n) + path("M1.5 5.5H5M1.5 10.5H5", None, n, cap="butt")
            + rect(5, 2.5, 3.5, 11, 0.75, blue_fill, blue) + path("M5 5.5H8.5M5 10.5H8.5", None, blue, cap="butt")
            + path("M9.4 4.4L12.3 3.6L14.6 12.7L11.7 13.5Z", nf, n))


def clock(f, s, hand, sw=1):
    # Tabler "clock": a circle with two hands
    return circle(8, 8, 6.5, f, s, sw) + path("M8 4.5V8L10.5 9.5", None, hand, sw)


def play_badge(c, halo=False):
    """Run triangle for the bottom-right badge slot."""
    if halo:
        return rect(8, 8, 8, 8, 2, "#000")
    return path("M10.5 9.5V15.5L15.5 12.5Z", c, c)


def extra_icons(I, P, n, nf, blue, blue_fill, green, green_fill, red, red_fill, sw, server_fill, doc_fill, table):
    """Icons shared by both packs; colours come from the pack."""
    server = (rect(1.5, 1.5, 13, 6, 1.5, server_fill, n) + rect(1.5, 8.5, 13, 6, 1.5, server_fill, n)
              + circle(4.5, 4.5, 1, green) + circle(4.5, 11.5, 1, green)
              + path("M7.5 4.5H11.5M7.5 11.5H11.5", None, n))
    body, defs = with_badge(server, plus_badge(green), plus_badge(0, True))
    I["new-connection"] = svg(body, defs)
    I["connect"] = svg(plug(green_fill, green))
    I["disconnect"] = svg(plug(red_fill, red))
    I["collapse-all"] = svg(collapse_arrows(n, sw))
    I["hide"] = svg(path("M3.5 8H12.5", None, n, sw))
    I["close"] = svg(path("M4.5 4.5L11.5 11.5M11.5 4.5L4.5 11.5", None, n, sw))
    I["results"] = svg(table)
    I["messages"] = svg(bubble(blue_fill, blue))
    I["file-sql"] = svg(doc_shape(doc_fill, n) + path("M5.5 8H10.5M5.5 10.5H9", None, blue, sw, cap="butt"))
    I["categorized"] = svg(list_tree(n, blue, sw))
    I["sort-alpha"] = svg(sort_letters(n, blue, sw))
    I["plan"] = svg(plan_tree(nf, n, blue, sw))
    I["history"] = svg(clock_back(nf, n, blue, sw))
    I["library"] = svg(books(n, doc_fill, blue, blue_fill))
    body, defs = with_badge(doc_shape(doc_fill, n) + path("M5.5 8H10.5M5.5 10.5H8", None, blue, sw, cap="butt"),
                            play_badge(green), play_badge(0, True))
    I["library-query"] = svg(body, defs)
    I["schedule"] = svg(clock(blue_fill, blue, blue, sw))
    # ER diagram: two small tables with a relationship line
    I["diagram"] = svg(path("M4.5 7.5V11.5H7.5", None, n, sw)
                       + rect(1.5, 1.5, 7, 6, 1, doc_fill, n) + path("M1.5 3.5H8.5", None, n, sw, cap="butt")
                       + rect(7.5, 8.5, 7, 6, 1, blue_fill, blue) + path("M7.5 10.5H14.5", None, blue, sw, cap="butt"))
    lens = circle(6.5, 6.5, 4.5, doc_fill, n, sw) + path("M10 10L13.5 13.5", None, n, 1.5)
    I["zoom-in"] = svg(lens + path("M4.5 6.5H8.5M6.5 4.5V8.5", None, blue, sw))
    I["zoom-out"] = svg(lens + path("M4.5 6.5H8.5", None, blue, sw))
    I["zoom-fit"] = svg(path("M2.5 5.5V2.5H5.5M10.5 2.5H13.5V5.5M13.5 10.5V13.5H10.5M5.5 13.5H2.5V10.5", None, n, sw)
                        + rect(5.5, 5.5, 5, 5, 1, blue_fill, blue))
    I["layout"] = svg(path("M5.5 8H8M8 4.5V11.5M8 4.5H10.5M8 11.5H10.5", None, n, sw)
                      + rect(1.5, 6, 4, 4, 1, doc_fill, n) + rect(10.5, 2.5, 4, 4, 1, blue_fill, blue)
                      + rect(10.5, 9.5, 4, 4, 1, blue_fill, blue))
    # Main tool bar: undo / redo (Tabler arrow-back-up / arrow-forward-up, neutral unlike the orange rollback)
    I["undo"] = svg(path("M6 9.5L2.5 6L6 2.5", None, n, sw) + path("M3 6H10.5A3 3 0 0 1 10.5 12H9", None, n, sw))
    I["redo"] = svg(path("M10 9.5L13.5 6L10 2.5", None, n, sw) + path("M13 6H5.5A3 3 0 0 0 5.5 12H7", None, n, sw))
    # Activity monitor: Tabler device-heart-monitor, a screen with a green pulse line
    I["activity-monitor"] = svg(rect(1.5, 2.5, 13, 11, 1.5, doc_fill, n)
                                + path("M3.5 8.5H5.5L7 5.5L9 11L10.5 8.5H12.5", None, green, sw))
    # Registered servers: the server of "connection" with a bookmark badge (a saved server)
    body, defs = with_badge(server, path("M10.5 8.5H15.5V15.5L13 13.5L10.5 15.5Z", blue, blue),
                            rect(9.5, 7.5, 6.5, 8.5, 0, "#000"))
    I["registered-servers"] = svg(body, defs)
    # Object explorer: a database with a tree of two child nodes (Tabler binary-tree idea)
    I["object-explorer"] = svg(path("M4.5 7V13.5H8M4.5 9.5H8", None, n, sw)
                               + path("M1.5 3V5.5C1.5 6.1 2.8 6.5 4.5 6.5S7.5 6.1 7.5 5.5V3", blue_fill, blue)
                               + ellipse(4.5, 3, 3, 1.5, blue_fill, blue)
                               + rect(8.5, 8, 6, 3, 0.75, doc_fill, n) + rect(8.5, 12, 6, 3, 0.75, doc_fill, n))
    # Properties: Tabler adjustments-horizontal, three sliders
    I["properties"] = svg(path("M2 4H14M2 8H14M2 12H14", None, n, sw, cap="butt")
                          + circle(10, 4, 1.75, blue_fill, blue) + circle(5.5, 8, 1.75, blue_fill, blue)
                          + circle(11, 12, 1.75, blue_fill, blue))
    # Comment / uncomment: text lines behind green line-comment dashes; uncomment strikes the dashes out
    text_lines = path("M6 4.5H14.5M6 8H12.5M6 11.5H13.5", None, n, sw, cap="butt")
    dashes = path("M1.5 4.5H4M1.5 8H4M1.5 11.5H4", None, green, 1.5, cap="butt")
    I["comment"] = svg(text_lines + dashes)
    I["uncomment"] = svg(text_lines + dashes + path("M1.5 13.5L4.5 2.5", None, red, sw))
    # Increase / decrease indent: Tabler indent-increase / indent-decrease
    indent_lines = path("M7.5 3.5H14.5M9.5 8H14.5M7.5 12.5H14.5", None, n, sw, cap="butt")
    I["indent"] = svg(indent_lines + path("M2 5.5L4.5 8L2 10.5", None, blue, sw))
    I["outdent"] = svg(indent_lines + path("M4.5 5.5L2 8L4.5 10.5", None, blue, sw))
    # Format SQL: keyword lines (blue) around indented lines, as formatted SQL looks
    I["format"] = svg(path("M2 3.5H9M2 12.5H8", None, blue, sw, cap="butt")
                      + path("M4.5 6.5H13M4.5 9.5H11", None, n, sw, cap="butt"))
    # Tool bar overflow button: Tabler chevrons-right
    I["overflow"] = svg(path("M3.5 4.5L7 8L3.5 11.5M8.5 4.5L12 8L8.5 11.5", None, n, sw))


# ----------------------------------------------------------------- SET A: duo-tone
def set_a(P):
    n, nf, b, bf = P["ns"], P["nf"], P["bs"], P["bf"]
    I = {}
    # connection: server-2, neutral duo-tone, green status LEDs
    I["connection"] = svg(
        rect(1.5, 1.5, 13, 6, 1.5, nf, n) + rect(1.5, 8.5, 13, 6, 1.5, nf, n)
        + circle(4.5, 4.5, 1, P["gs"]) + circle(4.5, 11.5, 1, P["gs"])
        + path("M7.5 4.5H11.5M7.5 11.5H11.5", None, n))
    I["database"] = svg(cylinder(bf, b))
    # schema: Tabler schema boxes, re-arranged for 16px
    I["schema"] = svg(
        path("M4.5 6V11.5H8.5", None, n) + rect(1.5, 1.5, 6, 4.5, 1, nf, n)
        + rect(8.5, 9.5, 6, 4.5, 1, bf, b) + path("M7.5 3.5H11.5V9", None, n)
        + rect(1.5, 1.5, 6, 4.5, 1, nf, n))
    I["folder"] = svg(folder_closed(bf, b))
    I["folder-open"] = svg(folder_open(bf, bf, b))
    body, defs = with_badge(folder_closed(bf, b), plus_badge(P["gs"]), plus_badge(0, True))
    I["new-group"] = svg(body, defs)
    I["new-folder"] = svg(body, defs)
    I["table"] = svg(table_shape(nf, n))
    body, defs = with_badge(table_shape(nf, n), lens_badge(b, bf), lens_badge(0, 0, True))
    I["view"] = svg(body, defs)
    mv = rect(4.5, 1.5, 10, 9, 1.5, nf, n) + table_shape(nf, n, x=1.5, y=4.5, w=10, h=10)
    body, defs = with_badge(mv, lens_badge(b, b), lens_badge(0, 0, True))
    I["mview"] = svg(body, defs)
    I["column"] = svg(column_shape(nf, n, n))
    body, defs = with_badge(column_shape(nf, n, n), key_badge(P["os"], P["of"]), key_badge(0, 0, True))
    I["column-pk"] = svg(body, defs)
    body, defs = with_badge(column_shape(nf, n, n), key_badge(b, bf), key_badge(0, 0, True))
    I["column-fk"] = svg(body, defs)
    I["primary-key"] = svg(key_shape(P["of"], P["os"], P["os"], P["os"]))
    I["foreign-key"] = svg(key_shape(bf, b, b, b))
    # index: Tabler sort-descending inside a tile
    I["index"] = svg(tile(nf, n) + path("M4.5 5H8.5M4.5 8H7.5M4.5 11H6.5", None, n, cap="butt")
                     + path("M11 4.5V11.5M9 9.5L11 11.5L13 9.5", None, b))
    I["unique"] = svg(tile(nf, n) + path("M5.5 4.5V9A2.5 2.5 0 0 0 10.5 9V4.5", None, b, 1.5))
    I["check"] = svg(tile(nf, n) + path("M4.75 8.25L7 10.5L11.25 5.75", None, P["gs"], 1.5))
    # function: Tabler math-function "f" curve in a blue tile
    I["function"] = svg(tile(bf, b) + path("M10.5 4.6A1.6 1.6 0 0 0 8.9 3.6C7.6 3.6 7.5 4.8 7.3 7S6.9 12.4 5.6 12.4A1.6 1.6 0 0 1 4.2 11.6", None, b, 1.25)
                        + path("M5.25 7.5H9.75", None, b, 1.25))
    I["procedure"] = svg(tile(bf, b) + path("M6 12V4.5H8.75A2 2 0 0 1 8.75 8.5H6", None, b, 1.5))
    # trigger: Tabler bolt
    I["trigger"] = svg(path("M9.5 1.5V6.5H13L6.5 14.5V9.5H3L9.5 1.5Z", P["yf"], P["os"]))
    # sequence: Tabler stairs-up as a filled stair block
    I["sequence"] = svg(path("M1.5 14.5V11.5H5.5V8H9.5V4.5H14.5V14.5Z", nf, n)
                        + path("M3.5 8.5V2M1.5 4L3.5 2L5.5 4", None, b))
    # user / role: Tabler user / users
    I["user"] = svg(circle(8, 5, 3, nf, n) + path("M2.5 14.5V13.5A3.5 3.5 0 0 1 6 10H10A3.5 3.5 0 0 1 13.5 13.5V14.5Z", nf, n))
    I["role"] = svg(circle(10.5, 4.5, 2.5, bf, b) + path("M9.5 9.5H12A3 3 0 0 1 15 12.5V14.5H11", bf, b)
                    + circle(5.5, 6, 2.5, nf, n) + path("M1.5 14.5V13.5A3 3 0 0 1 4.5 10.5H6.5A3 3 0 0 1 9.5 13.5V14.5Z", nf, n))
    I["datatype"] = svg(tile(P["pf"], P["ps"]) + path("M4.75 4.75H11.25M8 4.75V11.5", None, P["ps"], 1.5, cap="butt"))
    # toolbar
    I["run"] = svg(path("M4.5 2.8V13.2A.6 .6 0 0 0 5.4 13.7L13.4 8.5A.6 .6 0 0 0 13.4 7.5L5.4 2.3A.6 .6 0 0 0 4.5 2.8Z", P["gf"], P["gs"]))
    I["stop"] = svg(rect(3.5, 3.5, 9, 9, 1.5, P["rf"], P["rs"]))
    I["refresh"] = svg(refresh_arrows(n))
    body, defs = with_badge(doc_shape(nf, n), plus_badge(P["gs"]), plus_badge(0, True))
    I["new-query"] = svg(body, defs)
    I["open"] = svg(folder_open(nf, nf, n))
    I["save"] = svg(path("M2.5 3A.5 .5 0 0 1 3 2.5H11L13.5 5V13A.5 .5 0 0 1 13 13.5H3A.5 .5 0 0 1 2.5 13Z", nf, n)
                    + path("M5.5 2.5V5.5H10.5V2.5", None, n) + rect(5.5, 8.5, 5, 5, 0, None, b))
    # save-all: a second floppy behind a smaller "save"
    I["save-all"] = svg(path(SAVE_ALL_BACK, None, n) + path(SAVE_ALL_FRONT, nf, n)
                        + path("M4 4.5V7H8.5V4.5", None, n) + rect(4, 10.5, 5, 4, 0, None, b))
    body, defs = with_badge(doc_shape(nf, n),
                            path("M9 11.5H15M12.5 9L15 11.5L12.5 14", None, b, 1.5),
                            rect(8, 7.5, 8, 8, 0, "#000"))
    I["export"] = svg(body, defs)
    I["filter"] = svg(path("M2.5 2.5H13.5V4L9.5 8.5V13L6.5 14V8.5L2.5 4Z", nf, n))
    I["search"] = svg(circle(6.5, 6.5, 4, nf, n) + path("M9.5 9.5L13.5 13.5", None, n, 1.5))
    I["settings"] = svg(gear(nf, n, P["w"] if P["ink"] == "#FFFFFF" else nf))
    I["commit"] = svg(path("M2.5 8.5L6 12L13.5 4.5", None, P["gs"], 1.5))
    I["rollback"] = svg(path("M6 9.5L2.5 6L6 2.5", None, P["rs"], 1.5) + path("M3 6H10.5A3 3 0 0 1 10.5 12H9", None, P["rs"], 1.5))
    I["error"] = svg(circle(8, 8, 6.5, P["rs"]) + path("M8 4.5V8.75", None, P["w"], 1.5) + circle(8, 11.25, 0.9, P["w"]))
    I["warning"] = svg(path("M7.1 2.1L1.4 12.4A1 1 0 0 0 2.3 13.9H13.7A1 1 0 0 0 14.6 12.4L8.9 2.1A1 1 0 0 0 7.1 2.1Z", P["ys"])
                       + path("M8 5.5V9", None, P["ink"] if P["ink"] != "#FFFFFF" else "#FFFFFF", 1.5) + circle(8, 11.4, 0.9, P["ink"] if P["ink"] != "#FFFFFF" else "#FFFFFF"))
    I["info"] = svg(circle(8, 8, 6.5, b) + circle(8, 4.9, 0.9, P["w"]) + path("M8 7V11.5", None, P["w"], 1.5, cap="butt"))
    extra_icons(I, P, n, nf, b, bf, P["gs"], P["gf"], P["rs"], P["rf"], 1, nf, nf, table_shape(nf, n))
    return I


# ----------------------------------------------------------------- SET B: VS / SSMS colour
def set_b(P):
    n, nf, ln = P["ns"], P["nf"], P["line"]
    I = {}
    I["connection"] = svg(
        rect(1.5, 1.5, 13, 6, 1, "#D6D9DE" if nf != "#3A3A3D" else "#55585E", n)
        + rect(1.5, 8.5, 13, 6, 1, "#D6D9DE" if nf != "#3A3A3D" else "#55585E", n)
        + circle(4.5, 4.5, 1.25, P["gs"]) + circle(4.5, 11.5, 1.25, P["gs"])
        + path("M7.5 4.5H11.5M7.5 11.5H11.5", None, n))
    I["database"] = svg(cylinder(P["bf"], P["bs"], P["bl"]))
    I["schema"] = svg(
        path("M4.5 6V11.5H8.5", None, n) + path("M7.5 3.5H11.5V9", None, n)
        + rect(1.5, 1.5, 6, 4.5, 0.5, P["fold"], P["fold_s"])
        + rect(8.5, 9.5, 6, 4.5, 0.5, P["bf"], P["bs"]))
    folder_b = (path("M1.5 4A1.5 1.5 0 0 1 3 2.5H6l1.5 1.5H13A1.5 1.5 0 0 1 14.5 5.5v6.5A1.5 1.5 0 0 1 13 13.5H3"
                     "A1.5 1.5 0 0 1 1.5 12Z", P["fold"], P["fold_s"])
                + path("M2 6.5H14", None, P["fold_l"], 1, cap="butt"))
    I["folder"] = svg(folder_b)
    body, defs = with_badge(folder_b, plus_badge(P["gs"]), plus_badge(0, True))
    I["new-group"] = svg(body, defs)
    I["folder-open"] = svg(folder_open(P["fold"], P["fold_l"], P["fold_s"]))
    body, defs = with_badge(folder_b, plus_badge(P["gs"]), plus_badge(0, True))
    I["new-folder"] = svg(body, defs)
    tb = lambda hf, **k: table_shape(nf if nf != "#3A3A3D" else "#2D2D30", n, line=ln, head_fill=hf, **k)
    I["table"] = svg(tb(P["head"]))
    body, defs = with_badge(tb(P["vhead"]), lens_badge(n, P["bl"]), lens_badge(0, 0, True))
    I["view"] = svg(body, defs)
    mv = rect(4.5, 1.5, 10, 9, 1.5, P["vhead"], n) + tb(P["vhead"], x=1.5, y=4.5, w=10, h=10)
    body, defs = with_badge(mv, lens_badge(n, P["bf"]), lens_badge(0, 0, True))
    I["mview"] = svg(body, defs)
    col = column_shape(nf if nf != "#3A3A3D" else "#2D2D30", n, P["head"])
    I["column"] = svg(col)
    gold_badge = key_badge(P["gold"], P["gold_s"], outline=P["gold_s"])
    silver_badge = key_badge(P["silver"], P["silver_s"], outline=P["silver_s"])
    halo = key_badge(0, 0, True)
    body, defs = with_badge(col, gold_badge, halo)
    I["column-pk"] = svg(body, defs)
    body, defs = with_badge(col, silver_badge, halo)
    I["column-fk"] = svg(body, defs)
    I["primary-key"] = svg(key_shape(P["gold"], P["gold_s"], P["gold_s"], P["gold_s"]))
    I["foreign-key"] = svg(key_shape(P["silver"], P["silver_s"], P["silver_s"], P["silver_s"]))
    pg = nf if nf != "#3A3A3D" else "#2D2D30"
    I["index"] = svg(rect(1.5, 1.5, 13, 13, 1, pg, n)
                     + path("M4 5H8.5M4 8H7.5M4 11H6.5", None, P["bs"], 1.5, cap="butt")
                     + path("M11 4V11.5M9 9.5L11 11.5L13 9.5", None, P["gs"], 1.5))
    I["unique"] = svg(rect(1.5, 1.5, 13, 13, 1, pg, n) + path("M5.5 4.5V9A2.5 2.5 0 0 0 10.5 9V4.5", None, P["bs"], 2, cap="butt"))
    I["check"] = svg(rect(1.5, 1.5, 13, 13, 1, pg, n) + path("M4.5 8.25L7 10.75L11.5 5.75", None, P["gs"], 2))
    I["function"] = svg(rect(1.5, 1.5, 13, 13, 2, P["ps"], None)
                        + path("M10.6 4.4A1.6 1.6 0 0 0 9 3.5C7.6 3.5 7.5 4.8 7.3 7S6.9 12.5 5.5 12.5A1.6 1.6 0 0 1 4 11.6", None, P["w"], 1.5)
                        + path("M5 7.5H10", None, P["w"], 1.5))
    # procedure: isometric box (Visual-Studio "method" idea), original drawing
    I["procedure"] = svg(path("M8 1.5L14 4.5V11.5L8 14.5L2 11.5V4.5Z", P["pl"], P["ps"])
                         + path("M2 4.5L8 7.5L14 4.5", None, P["ps"]) + path("M8 7.5V14.5", None, P["ps"])
                         + path("M8 7.5L14 4.5V11.5L8 14.5Z", P["ps"], None))
    I["trigger"] = svg(path("M9.5 1.5V6.5H13L6.5 14.5V9.5H3L9.5 1.5Z", P["ys"], P["ys_s"]))
    I["sequence"] = svg(path("M1.5 14.5V11.5H5.5V8H9.5V4.5H14.5V14.5Z", P["bf"], P["bs"])
                        + path("M3.5 8.5V2M1.5 4L3.5 2L5.5 4", None, P["gs"], 1.25))
    I["user"] = svg(path("M2.5 14.5V13.5A3.5 3.5 0 0 1 6 10H10A3.5 3.5 0 0 1 13.5 13.5V14.5Z", P["bf"], P["bs"])
                    + circle(8, 5, 3, P["skin"], P["os"]))
    I["role"] = svg(path("M9.5 9.5H12A3 3 0 0 1 15 12.5V14.5H11", P["gl"], P["gs"]) + circle(10.5, 4.5, 2.5, P["skin"], P["os"])
                    + path("M1.5 14.5V13.5A3 3 0 0 1 4.5 10.5H6.5A3 3 0 0 1 9.5 13.5V14.5Z", P["bf"], P["bs"])
                    + circle(5.5, 6, 2.5, P["skin"], P["os"]))
    I["datatype"] = svg(rect(1.5, 1.5, 13, 13, 2, P["os"], None) + path("M4.5 4.75H11.5M8 4.75V12", None, P["w"], 2, cap="butt"))
    I["run"] = svg(path("M4 2.6V13.4A.7 .7 0 0 0 5.05 14L14 8.6A.7 .7 0 0 0 14 7.4L5.05 2A.7 .7 0 0 0 4 2.6Z", P["gs"]))
    I["stop"] = svg(rect(3, 3, 10, 10, 1, P["rs"]))
    I["refresh"] = svg(refresh_arrows(P["bs"], 1.5))
    body, defs = with_badge(doc_shape(pg, n), plus_badge(P["gs"]), plus_badge(0, True))
    I["new-query"] = svg(body, defs)
    I["open"] = svg(folder_open(P["fold"], P["fold_l"], P["fold_s"]))
    I["save"] = svg(path("M2.5 3A.5 .5 0 0 1 3 2.5H11L13.5 5V13A.5 .5 0 0 1 13 13.5H3A.5 .5 0 0 1 2.5 13Z", P["bf"], P["bs"])
                    + rect(5, 2.5, 5.5, 3.5, 0, P["w"], P["bs"]) + rect(4.5, 8.5, 7, 5, 0, P["w"], P["bs"]))
    I["save-all"] = svg(path(SAVE_ALL_BACK, P["bl"], P["bs"]) + path(SAVE_ALL_FRONT, P["bf"], P["bs"])
                        + rect(3.5, 4.5, 5, 2.5, 0, P["w"], P["bs"]) + rect(3.5, 10, 6, 4.5, 0, P["w"], P["bs"]))
    body, defs = with_badge(doc_shape(pg, n), path("M9 11.5H15M12.5 9L15 11.5L12.5 14", None, P["gs"], 1.75),
                            rect(8, 7.5, 8, 8, 0, "#000"))
    I["export"] = svg(body, defs)
    I["filter"] = svg(path("M2.5 2.5H13.5V4L9.5 8.5V13L6.5 14V8.5L2.5 4Z", P["bf"], P["bs"]))
    I["search"] = svg(path("M9.5 9.5L13.75 13.75", None, n, 2.25) + circle(6.5, 6.5, 4, P["bl"], n, 1.25))
    I["settings"] = svg(gear("#9AA0A8" if nf != "#3A3A3D" else "#7A8088", n, pg))
    I["commit"] = svg(path("M2.5 8.5L6 12L13.5 4.5", None, P["gs"], 2))
    I["rollback"] = svg(path("M6 9.5L2.5 6L6 2.5", None, P["os"], 2) + path("M3 6H10.5A3 3 0 0 1 10.5 12H9", None, P["os"], 2))
    I["error"] = svg(circle(8, 8, 6.5, P["rs"]) + path("M5.75 5.75L10.25 10.25M10.25 5.75L5.75 10.25", None, P["w"], 1.75))
    I["warning"] = svg(path("M7.1 2.1L1.4 12.4A1 1 0 0 0 2.3 13.9H13.7A1 1 0 0 0 14.6 12.4L8.9 2.1A1 1 0 0 0 7.1 2.1Z", P["ys"], P["ys_s"], 0.75)
                       + path("M8 5.5V9", None, "#1E1E1E", 1.75) + circle(8, 11.4, 1, "#1E1E1E"))
    I["info"] = svg(circle(8, 8, 6.5, P["bs"]) + circle(8, 4.9, 1, P["w"]) + path("M8 7V11.75", None, P["w"], 1.75, cap="butt"))
    extra_icons(I, P, n, nf, P["bs"], P["bl"], P["gs"], P["gl"], P["rs"], P["rl"], 1.5,
                "#D6D9DE" if nf != "#3A3A3D" else "#55585E", pg, tb(P["head"]))
    return I


BUILDERS = {"A": set_a, "B": set_b}


def build(set_id, theme):
    return BUILDERS[set_id](PAL[(set_id, theme)])


def write_all():
    for s in ("A", "B"):
        for th in ("light", "dark"):
            d = os.path.normpath(os.path.join(ROOT, PACK_DIRS[s], th))
            os.makedirs(d, exist_ok=True)
            icons = build(s, th)
            missing = [c[0] for c in CONCEPTS if c[0] not in icons]
            assert not missing, missing
            for k, v in sorted(icons.items()):
                with open(os.path.join(d, k + ".svg"), "w", encoding="utf-8", newline="\n") as f:
                    f.write(v)


if __name__ == "__main__":
    write_all()
    print("ok")
