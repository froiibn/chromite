# Swing 프런트엔드 디자인 사양

## 목적

Swing 프런트엔드(`com.seizia.chromite.ui.swing`)의 화면을 **배치와 동작은 SSMS**, **모양은 DataGrip New UI**(다크·라이트 모두)로
맞추기 위한 목표 사양이다. 요소마다 SSMS의 배치·동작, DataGrip New UI의 실제 값, 현재 Chromite의 차이와 근거, 목표 값을 적고,
그 값을 디자인 토큰과 FlatLaf 키로 옮기는 방법과 아이콘 요구 사항을 정한다.

이 문서는 목표 사양과 그 적용 현황(아래 "적용 현황")을 다룬다. 단계별 구현 내용은 [swing-frontend.md](swing-frontend.md)를 본다.

## 배경과 결정 (2026-11-06)

- 사용자 의견: "아직 DBeaver처럼 보인다. DataGrip과 SSMS 화면을 제대로 참고해 요소마다 정확히 비교하라. 폴더·테이블 아이콘은 더 다듬어진 모양이 필요하다."
- 결정:
  - **배치·동작 = SSMS**: 창 구성(왼쪽 Object Explorer, 가운데 쿼리 창, 아래 Results/Messages, 오른쪽 Properties), 메뉴와 단축키,
    쿼리 창 단위 동작(Execute, 상태 줄), Connect to Server 흐름.
  - **모양 = DataGrip New UI**: 색, 글꼴, 간격, 행 높이, 모서리, 선택 표시, 탭, 아이콘 스타일. 다크와 라이트 모두.
    이전 결정("다크는 DataGrip풍, 라이트는 SSMS풍")을 바꾼다. 라이트도 DataGrip New UI Light(밝은 머리말, "Light with Light Header")를 따른다.
  - **아이콘**: 오픈 라이선스 세트(Fluent UI System Icons, Lucide, Tabler 중 하나, 별도로 고른다)를 쓴다.
    이 문서는 세트를 고르지 않고, 어느 세트에도 적용할 수 있는 요구 사항(크기, 선 굵기, 색 규칙, 필요한 개념 목록)만 정한다.
- DataGrip 2025.3부터 기본 테마는 "Islands"(New UI의 변형, 둥근 패널)다. 처음 사양은 New UI 기본 팔레트(Dark, Light with Light Header)를 기준으로 하고
  Islands를 P2로 두었으나, 2026-11-06 사용자 의견("아직 딱딱하고 DBeaver 같다, DataGrip은 둥글다")에 따라 **Islands를 기본 모양으로 바꿨다**
  (아래 "적용 현황 — Islands 적용"). 아래 요소별 표와 토큰 표의 New UI 값 중 Islands가 바꾼 값은 그 절의 표가 우선한다.

## 근거 자료와 표기

값마다 출처를 괄호 기호로 적는다. 화면을 눈으로 재는 것보다 문서와 소스의 값을 우선했다.

| 기호 | 출처 | 비고 |
|---|---|---|
| [T] | IntelliJ Platform New UI 테마 파일 `expUI_dark.theme.json`, `expUI_light.theme.json`, `expUI_light_with_light_header.theme.json` | intellij-community 저장소(Apache 2.0). 팔레트 이름 `Gray1`~`Gray14`, `Blue1`~ 등과 UI 키 |
| [E] | New UI 편집기 색 구성 `expUI_darkScheme.xml`(부모 Darcula), `expUI_lightScheme.xml`(부모 Default) | 같은 저장소 |
| [J] | `JBUI.java`(IntelliJ UI 상수), `SpacingConfiguration.kt`(Kotlin UI DSL 간격) | 같은 저장소 |
| [I] | DataGrip 도움말에 실린 New UI 아이콘 SVG(`database-plugin.icons.expui.*.svg`), 플랫폼 `expui` 아이콘 SVG | 색과 선 굵기를 SVG에서 직접 읽음 |
| [S] | 화면 캡처 측정 | DataGrip 도움말 캡처는 macOS 2배 화면이라 측정값을 2로 나눴다. Chromite 캡처는 Windows 150%(1280×800 창 → 1920×1200)라 1.5로 나눴다. `≈`는 측정값 |
| [F] | FlatLaf 3.8 jar의 `FlatLaf.properties`, `FlatLightLaf.properties`, `FlatDarkLaf.properties`, `FlatDarculaLaf.properties` | Chromite가 쓰는 버전에서 키가 있는지 확인함 |
| [M] | Microsoft Learn의 SSMS 문서와 SSMS 22 릴리스 노트 | SSMS의 배치·동작 근거 |
| [C] | 현재 Chromite 코드 | 파일:줄 |

참고 화면은 공부용으로만 `D:\Tools\_scratch\design-spec\ref\`에 받아 보았고, 저장소에는 넣지 않는다.

## 현재 Chromite가 DBeaver처럼 보이는 원인 (요약)

| 원인 | 근거 |
|---|---|
| 다크 테마가 `FlatDarculaLaf`를 상속한다. Darcula는 트리 화살표를 삼각형으로 그리고(`Component.arrowType = triangle`), 트리·표 바탕을 창 바탕보다 밝게(`@componentBackground = tint(@background,5%)` → `#36383A`) 만든다. DataGrip은 셰브런 화살표에 트리 = 창 바탕(`#2B2D30`), 편집기·결과 = 더 어두운 `#1E1F22`다 | [F] `FlatDarculaLaf.properties:58`, `FlatDarkLaf.properties:43`; [C] `ChromiteDarkLaf.java`; [S] `swing-b/dark.png` 트리 바탕 `#36383A` |
| 테마에서 바꾼 값이 색 변수 2~3개뿐이다. 행 높이, 들여쓰기, 모서리, 탭 높이, 표 행 높이, 분할선은 모두 FlatLaf 기본값이다 | [C] `ChromiteLightLaf.properties`(2개), `ChromiteDarkLaf.properties`(3개) |
| 선택 행이 진한 강조색(`#0078D4`, 다크 `#3574F0`)으로 가로 전체를 채우고 글자를 흰색으로 바꾼다. DataGrip은 옅은 선택색(`#D4E2FF`/`#2E436E`)에 글자색 그대로, 좌우 12px 안쪽에 둥근 사각형이다 | [F] `FlatLightLaf.properties:47-48`; [S] `swing-b/light.png` 선택 `#0078D4` |
| 트리 아이콘이 DBeaver 아이콘(주황 채움 폴더, 파란 표)과 PNG 겹침 아이콘(초록 체크)이다 | [C] `NodeIcons.java`(DBeaver 번들 아이콘을 그대로 읽음); [S] `swing-b/light.png` |
| 도구 창 머리말과 도구 막대가 글자 단추(`Refresh`, `New Query`, `Execute`, `Cancel`)와 `×` 글자다. DataGrip·SSMS 모두 16px 아이콘 단추다 | [C] `ToolWindow.java:123`(`"×"`), `MainFrame.java:335-344`, `SqlEditorPanel.java:380-440` |
| 연결 대화상자가 DBeaver식 마법사(`< Back`, `Next >`, Driver/Provider Properties 탭)다 | [S] `swing-d/e8-pg-form-0.png` |
| 글꼴이 운영체제 기본(Windows: Segoe UI 12px, 편집기 Consolas)이다. DataGrip은 Inter 13px, JetBrains Mono 13px다 | [C] `defaultFont` 지정 없음; RSyntaxTextArea `FontUtil.getDefaultMonospacedFont`(Consolas); [T]·JetBrains New UI 도움말 |
| 라이트 SQL 색이 SSMS풍(파랑·자홍·빨강·초록)이다 | [C] `SqlEditorLight.xml` |
| 분할선에 손잡이 점(`⋮`, `•••`)이 보인다 | [F] `SplitPaneDivider.style = grip`; [S] 모든 캡처 |

## 요소별 비교

열 설명: **SSMS** = 따를 배치·동작, **DataGrip New UI** = 따를 모양(값), **현재 Chromite** = 차이와 근거, **목표** = 구현할 값(토큰 이름은 아래 "디자인 토큰"),
**우선**: P0 = "DBeaver처럼 보인다"를 없애는 데 꼭 필요, P1 = 다음 단계, P2 = 나중.

### 1. 창, 제목 표시줄, 주 메뉴

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 제목 표시줄 | SSMS 21/22(Visual Studio 2022 셸): 메뉴가 제목 표시줄 안에 있음 | 주 도구 막대 높이 40, 바탕 `MainToolbar.background` L `#F7F8FA`(Light with Light Header) / D `#2B2D30` [T] | FlatLaf 통합 제목 표시줄, 메뉴 내장(맞음). 높이 ≈35, 창 단추 44×30(FlatLaf 기본) [F][S] | 메뉴 내장 유지. 높이 40, 창 단추 46×40, 바탕 `bg.chrome`, 제목 글자 `text.secondary`, 가운데 정렬 유지 | P1 |
| 주 메뉴 항목 | File, Edit, View, Query, Project, Tools, Window, Help | (주 메뉴는 햄버거나 도구 막대에 합침) | File, View, Query, Help만 있음 [S] | SSMS 순서로 File, Edit, View, Query, Tools, Window, Help. Edit(실행 취소·다시 실행·잘라내기·복사·붙여넣기·찾기), Tools(Options), Window(탭 목록) 추가 | P1 |
| 메뉴 팝업 | 표준 메뉴, 단축키 오른쪽 | 바탕 L `#FFFFFF` / D `#2B2D30`, 테두리 L `#B9BDC9` / D `#43454A`, 선택 둥근 사각형 arc 8, 좌우 안쪽 여백 8 [T][J] | FlatLaf 기본(가로 전체 선택, 진한 강조색) | 항목 높이 24, 선택 `selection.bg` + arc 8 + 좌우 inset 4, 모서리 반지름 8(Windows 11), 단축키 글자 `text.secondary`, 아이콘 16 | P1 |

### 2. 주 도구 막대

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 구성 | 표준 도구 막대: **New Query**(아이콘 + 글자), 파일 열기·저장 아이콘 등. 아래에 SQL Editor 도구 막대 | 주 도구 막대(프로젝트·실행 위젯) | 글자 단추 4개 `New Connection…`, `New Query`, `Open File…`, `Save` [C] `MainFrame.java:335-344` | SSMS 구성: [새 연결 아이콘] [New Query(아이콘 + 글자)] │ [열기] [저장] [모두 저장]. 글자는 New Query만 보이고 나머지는 아이콘 + 도구 설명(단축키 포함) | P0 |
| 단추 모양 | — | 아이콘 16, 누를 수 있는 영역 ≈26×26, 마우스를 올리면 L `#00000012` / D `#FFFFFF16` 바탕, 누르면 L `#0000001D` / D `#FFFFFF26`, 모서리 arc ≈6 [T `ActionButton.*`][S] | FlatLaf 도구 막대 단추(글자, 여백 3) | `size.actionButton` 26, 아이콘 `size.icon` 16, 바탕 `bg.actionHover`/`bg.actionPressed`, arc 6, 단추 사이 2, 묶음 사이 구분선(높이 16, `border.subtle`) | P0 |
| 높이 | — | 40 [J] | ≈30 [S] | 36(제목 표시줄 40과 합쳐 SSMS의 두 줄 구성을 유지하되 높이를 줄임) | P1 |

### 3. 도구 창 머리말 (Object Explorer, Properties)

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 높이·제목 | 제목 + 오른쪽 ▾(창 위치), 핀, × | 높이 40(+아래 구분선 1 = `JBUI` 41), 제목 Inter 13 semibold, 왼쪽 여백 12 [J `ToolWindow.Header.height`=41, `labelLeftRightInsets` 0,12,0,16] | 높이 ≈28, 제목 semibold, 여백 2,8 [C] `ToolWindow.java:205-232` | 높이 40 + 아래 1px `border.subtle`, 제목 `font.ui.semibold`, 왼쪽 12 | P0 |
| 머리말 동작 단추 | Object Explorer 도구 막대: Connect ▾, Disconnect, Stop, Filter, Refresh, (22.x) Group by schema [M] | 머리말 오른쪽 아이콘(⋮ 옵션, — 숨기기), 아래 줄에 도구 막대(+, 새로 고침, 끊기 …), 아이콘 16, 단추 ≈26 [S] | 글자 `Refresh` 단추와 글자 `×` [C] `ObjectExplorerPanel.java:461-463`, `ToolWindow.java:123` | 머리말 오른쪽: [⋮ 옵션] [— 숨기기]. Object Explorer는 머리말 아래 높이 32의 도구 막대에 SSMS 순서로 [연결 ▾] [끊기] [중지] │ [필터] [새로 고침] │ [모두 접기]. 모두 아이콘 16 + 도구 설명 | P0 |
| 바탕 | — | 머리말 = 도구 창 바탕(L `#F7F8FA` / D `#2B2D30`) [T] | 머리말과 내용 바탕이 다름(다크: 머리말 `#2B2D30`, 트리 `#36383A`) [S] | 머리말·도구 막대·트리 모두 `bg.panel` | P0 |

### 4. 트리 (Object Explorer)

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 구조 | 서버 → Databases(System Databases …) → DB → Tables(System Tables …) → 테이블 → Columns, Keys, Constraints, Triggers, Indexes, Statistics. Views, Synonyms, Programmability(Stored Procedures, Functions …), Security [M] | 데이터 소스 → DB → 스키마 → tables, views, routines … [S] | DBeaver 탐색기 모델 구조(테이블, 뷰, 인덱스, 시퀀스, 테이블 트리거, 데이터 타입) [S] | 탐색기 모델 구조 유지(드라이버마다 다름). 폴더 이름 언어는 범위 밖(아래 "범위 밖 관찰") | — |
| 행 높이 | 촘촘함(≈18) | 24 [T `Tree.rowHeight`][J] (compact 22) | ≈19(FlatLaf `Tree.rowHeight = 0`, 글꼴에서 계산) [F][S] | `size.row.tree` 24 | P0 |
| 들여쓰기 | ≈18 | ≈19 [S] (화살표 사이 간격 38÷2) | ≈18(FlatLaf 7 + 11) [F] | `Tree.leftChildIndent` 7, `Tree.rightChildIndent` 12 (계 19) | P0 |
| 안쪽 여백 | — | 위아래 4, 좌우 12 [T `Tree.border` 4,12,4,12] | 1,1,1,1 [F] | `Tree.border` 4,12,4,12 | P0 |
| 펼침 화살표 | ⊞/⊟ 또는 ▷ | 셰브런(›, ⌄) 1px 선, 둥근 끝, L `#818594` / D `#B4B8BF` [I `expui/general/chevronRight`] | 라이트 셰브런, 다크 채운 삼각형(Darcula) [F][S] | 두 테마 모두 셰브런, 색 `icon.chevron`. FlatLaf `Component.arrowType = chevron` + `Tree.icon.expandedColor`/`collapsedColor` | P0 |
| 선택 | 가로 전체, 진한 파랑(포커스 없을 때 회색) | 둥근 사각형(arc 8 = 반지름 4) [J `Tree.Selection.arc`], 트리 여백 안쪽(좌우 12)만 칠함, 포커스 L `#D4E2FF` / D `#2E436E`, 포커스 없음 L `#DFE1E5` / D `#43454A`, 글자색 그대로 [T `*.selection*`, `Tree.forceFocusedSelectionForeground=false`][S] | 가로 전체, L `#0078D4` / D `#3574F0` 진한 색, 글자 흰색. 포커스 없을 때 다크 `#073351`(남색) [S][F] | `selection.bg`, `selection.inactiveBg`, 글자 `text.primary` 유지, `Tree.selectionArc` 8, 선택 사각형이 트리 여백(12) 안에 들어가게 | P0 |
| 마우스 올림 | 없음 | 행 바탕 L `#EDF3FF` / D `#393B40` [T `*.hoverBackground`] | 없음 | `bg.hover` 둥근 사각형(선택과 같은 모양). FlatLaf에 트리 hover 키가 없으므로 렌더러에서 그림 | P1 |
| 아이콘 | 16, 노란 폴더, 서버 상태 겹침 | 16, 1px 선 아이콘, 중립 회색 + 의미 강조색(아래 "아이콘 요구 사항") [I] | DBeaver 아이콘(주황 채움 폴더, 파란 표), 16 [C][S] | "아이콘 요구 사항"의 트리 아이콘으로 바꿈. 아이콘·글자 간격 6 | P0 |
| 연결 상태 표시 | 서버 아이콘에 상태 겹침(실행 중·중지·연결 안 됨) | 연결되면 데이터 소스 아이콘 오른쪽 아래에 초록 점 [S] | PNG 겹침 아이콘(초록 체크, `over/success_ovr.png`)이 150%에서 흐림 [S][C] `NodeIcons.CompositeIcon` | 지름 6 점, 오른쪽 아래, 행 바탕색 1px 테두리(아이콘과 떨어져 보이게). 연결됨 `status.success`, 연결 중 `status.warning`(깜빡이지 않음), 오류 `status.error`. 읽기 전용 자물쇠·SSH 터널 표시는 8px 배지(왼쪽 아래·오른쪽 위) | P0 |
| 보조 글자 | 열: `CustomerId (PK, int, not null)`, 서버: `(SQL Server 16.0 - 사용자)` [M] | 이름 뒤 회색 보조 글자(주석, "refreshed …"), 개수 배지 `1 of 5`(둥근 테두리) [S] L `#818594` / D `#6F737A` | 보조 글자 없음. 연결 이름에 드라이버를 괄호로 붙인 일반 글자 [S] | 이름 + 6px + 보조 글자(`text.secondary`): 연결은 `호스트:포트` 또는 연결 뒤 `제품 버전 - 사용자`(SSMS), 열은 `int, PK, not null`(SSMS 내용, DataGrip 모양), 폴더는 개수(읽은 뒤) | P1 |
| 글꼴 | Segoe UI 9pt | Inter 13 [JetBrains 도움말] | Segoe UI 12px(FlatLaf 기본) | `font.ui` | P0 |
| 들여쓰기 안내선 | 없음 | 선택 사항(`Tree.hash` L `#DFE1E5` / D `#4E5157`) [T] | 없음 | 끔(기본). 설정으로 켤 수 있게는 P2 | P2 |
| 새로 고침 키 | Object Explorer에 포커스가 있으면 **F5 = 새로 고침** | Ctrl+F5 | F5를 쿼리 실행에만 씀. 새로 고침은 단추와 오른쪽 클릭 [swing-frontend.md B단계 7] | SSMS처럼 F5를 포커스에 따라 나눔: Object Explorer = 새로 고침, 쿼리 창 = 실행 | P1 |

### 5. 편집기(문서) 탭

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 높이·글꼴 | 작은 탭, 제목 `SQLQuery1.sql - 서버.DB (사용자 (55))*` | 높이 40 [T `TabbedPane.tabHeight`], 글꼴 13, 탭 줄 바탕 = 편집기 바탕(L `#FFFFFF` / D `#1E1F22`) [T `EditorTabs.background`] | 32(FlatLaf), 탭 줄 바탕 = 창 바탕 [F][S] | 높이 `size.tab.editor` 40, 탭 줄 바탕 `bg.editor`, 아래 1px `border.subtle` [T `EditorTabs.underTabsBorderColor`] | P0 |
| 활성 표시 | 활성 탭 바탕 강조 | 아래 밑줄 4px, 끝 arc 4 [T `EditorTabs.underlineHeight`/`underlineArc`], 포커스 있으면 `#3574F0`, 없으면 L `#A8ADBD` / D `#5A5D63` [T `*.underlineColor`/`*.inactiveUnderlineColor`]. 비활성 탭 글자는 흐리게(`unselectedAlpha` 0.75) | 밑줄 3px(FlatLaf), 색 강조색 [F] | `TabbedPane.tabSelectionHeight` 4, `tabSelectionArc` 4, `tab.underline`/`tab.underlineInactive`, 비활성 탭 글자 `text.secondary` | P0 |
| 아이콘 | 문서 종류 아이콘 없음 | 탭 앞에 16 아이콘(콘솔은 데이터 소스 공급자 아이콘) [S] | 없음 | SQL 파일 아이콘(파일로 저장된 것) 또는 연결의 공급자 아이콘(새 쿼리) 16, 아이콘·글자 간격 6 | P0 |
| 닫기 단추 | × (마우스 올리면) + 고정 핀 | × 16, 마우스를 올린 탭과 활성 탭에만, 올리면 둥근 바탕 [S] | 모든 탭에 가는 `X`(FlatLaf) [S] | 닫기 아이콘 16(`icon.close`), 누를 영역 16×16 arc 4(`TabbedPane.closeArc`), 활성·마우스 올린 탭에만 보임 | P1 |
| 고침 표시 | 제목 끝 `*` | (설정에 따라 `*`) | 제목 끝 `*` [C] | 유지(SSMS) | — |
| 넘침 | 탭 목록 ▾ | 스크롤 또는 여러 줄 | 좌우 화살표 + ▾ [S] | ▾ 목록만(SSMS), 화살표 셰브런 `icon.chevron` | P2 |
| 제목 형식 | `파일 - 연결` | `console [PostgreSQL]` | `SQLQuery1.sql - 연결 이름`(SSMS와 같음) | 유지. 연결 이름은 탭 도구 설명과 상태 줄에도 | — |

### 6. SQL 편집기

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 글꼴 | 고정폭 | JetBrains Mono 13, 줄 간격 1.2 | Consolas 13(RSyntaxTextArea 기본) [C] `FontUtil.getDefaultMonospacedFont` | `font.code` | P0 |
| 바탕 | 흰색 / 다크 | L `#FFFFFF` / D `#1E1F22` [E `TEXT`] | 같음 [C] | `bg.editor` | — |
| 현재 줄 | 옅은 테두리 | L `#F5F8FE` / D `#26282E` [E `CARET_ROW_COLOR`] | L `#F2F6FC` / D `#26282E` [C] | `editor.caretRow` | P0(라이트) |
| 줄 번호 | 청록 `#2B91AF` | L `#AEB3C2`, 현재 줄 `#767A8A` / D `#4B5059`, 현재 줄 `#A1A3AB` [E] | L `#2B91AF`(SSMS) / D DataGrip 값 [C] | `editor.lineNumber`, `editor.lineNumberActive` | P0(라이트) |
| 여백(거터) | 노란 변경 막대 | 바탕 = 편집기 바탕, 구분선 없음 | 1px 구분선 `#E0E0E0`/`#393B40` [C] | 바탕 `bg.editor`, 구분선 없음(`gutterBorder` = 바탕색), 줄 번호 오른쪽 여백 12 | P1 |
| 선택 | 파랑 | L `#A6D2FF` / D `#214283` [E `SELECTION_BACKGROUND`, 다크는 부모 Darcula] | L `#ADD6FF` / D `#214283` [C] | `editor.selection` | P0(라이트) |
| 짝 괄호 | — | L 바탕 `#93D9D9` / D 바탕 `#43454A` 굵게 [E] | L `#DBE0CC` / D `#43454A` [C] | `editor.matchedBrace` | P1 |
| 같은 이름 강조 | — | L `#EDEBFC` / D `#373B39` [E `IDENTIFIER_UNDER_CARET_ATTRIBUTES`] | L `#D4D4D4` / D `#3B514D` [C] | `editor.identifierUnderCaret` | P1 |
| 구문 색 | 키워드 파랑, 함수 자홍, 문자열 빨강, 주석 초록 | 아래 "SQL 구문 색" 표 [E] | 다크는 DataGrip 값, 라이트는 SSMS 값 [C] `SqlEditorLight.xml` | 아래 표 | P0(라이트) |

### 7. 쿼리 창 도구 막대

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 위치 | 주 창의 SQL Editor 도구 막대(활성 쿼리 창에 적용) | 콘솔마다 편집기 위 도구 막대 | 편집기마다 위 도구 막대 [C] | 편집기마다 유지(연결·DB가 창마다 다르므로 보이는 곳에 둠. "결정" 참고) | — |
| 순서 | [DB 목록 ▾] [▶ Execute] [■] [✓ Parse] … [M] | [▶] [기록] [설정] … [Tx: Auto ▾] [■] … [스키마 ▾](오른쪽) [S] | [Execute] [Cancel] │ [연결 ▾] │ [스키마 ▾] [C] `SqlEditorPanel.java:380-426` | [▶ Execute] [■] │ [연결 ▾] [DB/스키마 ▾]. SSMS처럼 Execute는 글자를 보이고, 중지는 아이콘만 | P0 |
| Execute 단추 | 초록 삼각형 + "Execute"(F5) | 실행 아이콘: L 선 `#208A3C` 채움 `#F2FCF3` / D 선 `#57965C` 채움 `#253627` [I `expui/run/run`] | 글자만 `Execute` [C] | 아이콘 `action.run`(초록) + 글자 `Execute`, 도구 설명 "Execute (F5)" | P0 |
| 중지 단추 | 빨간 사각형(실행 중에만 켜짐) | L 선 `#DB3B4B` 채움 `#FFF7F7` / D 선 `#DB5C5C` 채움 `#5E3838` [I `expui/run/stop`] | 글자 `Cancel` [C] | 아이콘 `action.stop`만, 실행 중에만 켜짐(비활성은 `text.disabled`색) | P0 |
| 콤보 | Available Databases ▾ | 높이 28, arc 8, 테두리 L `#C9CCD6` / D `#4E5157`, 안쪽 여백 1,9,1,6, 화살표 영역 28 [T `ComboBox.*`, `Component.arrowAreaWidth`] | FlatLaf 기본(arc 5) [F] | `size.control` 28, arc 8, 연결 콤보에 공급자 아이콘 16, DB/스키마 콤보에 스키마 아이콘 16 | P0 |
| 높이 | — | ≈36 [S] | ≈32 [S] | `size.toolbar.editor` 36, 아래 1px `border.subtle` | P1 |

### 8. 결과(Results) / 메시지(Messages)

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 배치 | 결과 창은 **쿼리 창 안**(편집기 아래, 분할), 탭 Results / Messages, 상태 줄은 쿼리 창 맨 아래 [M] | Services 도구 창의 결과 탭 또는 편집기 안 결과 | 메인 창 아래의 공용 도구 창에 활성 편집기의 결과를 끼움 [C] `MainFrame.java:150-185` | 화면 결과는 지금처럼 편집기마다 따로이므로 동작은 같음. 배치를 SSMS처럼 쿼리 창 안으로 옮길지는 P1 검토(문서 탭과 함께 나뉘고, Ctrl+R로 숨김) | P1 |
| 탭 | 작은 탭, 아이콘(표, 메시지) + 글자 | 머리말 탭: 밑줄 3 [J `ToolWindow.HeaderTab.underlineHeight` 기본 3], 마우스 올림 L `#EBECF0` / D `#393B40` | FlatLaf 탭 32, 아이콘 없음 [S] | 높이 `size.tab.tool` 32, 아이콘 16(`results.grid`, `results.messages`) + 글자, 밑줄 3 `tab.underline` | P1 |
| 그리드 바탕 | 흰색(다크: 어두움) | 편집기 바탕(L `#FFFFFF` / D `#1E1F22`) [S][E] | L `#FFFFFF` / D `#36383A` [S] | `bg.editor` | P0 |
| 행 높이 | ≈20 | ≈27 [S](편집기 글꼴 13 + 위아래 여백) | 20(FlatLaf `Table.rowHeight`) [F] | `size.row.grid` 27 | P0 |
| 글꼴 | 비례 글꼴(설정 가능) | 편집기 글꼴(JetBrains Mono 13) [S] | `font.ui`(Segoe UI 12) | `font.code`(설정으로 바꿀 수 있게는 P2) | P0 |
| 머리글 | 이름 왼쪽 정렬 [M 결과 캡처] | 높이 ≈28, 바탕 = 그리드 바탕, 열 종류 아이콘 16(키 표시 포함) + 이름(왼쪽 정렬), 마우스 올리면 필터·정렬 아이콘 [S] | 높이 25, 이름 가운데 정렬, 아이콘 없음 [F][S] | `size.header.grid` 28, 왼쪽 정렬(숫자 열도 머리글은 왼쪽), 열 아이콘(PK/FK 배지 포함), 아래 1px `grid.line` | P0 |
| 격자선 | 세로·가로 옅은 선 | 세로선이 가로선보다 조금 진함 [S] | FlatLaf 기본(표시 안 함) | 세로선 `grid.line`, 가로선 `grid.lineSubtle` 둘 다 표시 | P0 |
| 줄무늬 | 없음 | 없음(기본) [S] | 없음 | 없음 | — |
| 행 번호 열 | 맨 왼쪽 열, 왼쪽 정렬 [M] | 바탕 = 그리드 바탕, 글자 `text.secondary`, 폭 ≈40 [S] | 머리글 바탕, 오른쪽 정렬 [C] `ResultSetGrid.java:194-205` | 바탕 `bg.editor`, 글자 `text.secondary`, 왼쪽 여백 8, 오른쪽 1px `grid.line` | P1 |
| NULL | `NULL`(옅은 노란 바탕, SSMS 21부터 색 설정 가능) [M] | `<null>` 회색 | `NULL` 회색 기울임꼴 [C] | 글자 `NULL`(SSMS) + `text.secondary`, 기울임 없음 | P2 |
| 숫자 정렬 | 오른쪽 | 오른쪽 | 오른쪽(맞음) [C] | 유지 | — |
| 선택 | 진한 파랑 | 셀 선택 `selection.bg`, 글자색 유지 | FlatLaf 강조색 + 흰 글자 | `selection.bg`/`selection.inactiveBg`, 현재 셀 1px `focus.ring` 테두리, 선택 행의 행 번호도 `selection.bg` | P0 |
| 메시지 글 | 고정폭, 오류 빨강 | 콘솔 출력: 일반 L `#080808` / D `#BCBEC4`, 오류 D `#F75464` [E `CONSOLE_*`] | 오류 `Actions.Red` [C] `QueryResultsView.java:117` | 글꼴 `font.code`, 오류 `status.error` | P1 |

### 9. 쿼리 상태 줄과 주 상태 표시줄

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 쿼리 상태 줄 | 쿼리 창 맨 아래: 상태 아이콘 + "Query executed successfully." │ 서버(버전) │ 사용자(spid) │ DB │ 00:00:00 │ N rows. 연결별 색 지정 가능 [M] | — | 편집기와 결과 사이: 상태, 경과 시간, 행 수 [C] `SqlEditorPanel.java:447-463` | SSMS 내용(서버, 사용자, DB 추가) + 상태 아이콘 16(`msg.*`), 위치는 결과 아래(쿼리 창 맨 아래). 높이 28, 바탕 `bg.chrome`, 위 1px `border.subtle`. 연결 색은 P2 | P1 |
| 주 상태 표시줄 | 왼쪽 "Ready", 오른쪽 Ln/Col/Ch/INS [M] | 위젯 글자 L `#6C707E` / D `#9DA0A8`, 마우스 올림 바탕 [T `StatusBar.Widget.*`] | 왼쪽 `N connections in workspace`만 [C] | 왼쪽 상태 문구, 오른쪽 `Ln, Col` │ 인코딩 │ 줄바꿈. 글자 `text.secondary`, 높이 28 | P1 |

### 10. Properties 창

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 구성 | 속성 그리드: 분류별/이름순 단추, 분류 줄 접기, 열 머리글 없음, 아래 설명 칸 | (대응 화면 없음, 모양만 따름) | 이름·종류 머리말 + `Property | Value` 가운데 정렬 열 머리글, 가로·세로 선 [C] `PropertiesPanel.java:80-100` | SSMS 구성: 머리말 아래 도구 막대(분류별·이름순 아이콘), 열 머리글 없음, 분류 줄 = 셰브런 + `font.ui.semibold`, 행 24, 가로선 `grid.lineSubtle`, 두 열 사이 1px `border.subtle`, 아래 설명 칸(선택 속성의 설명) | P1 |

### 11. 대화상자 (연결)

| 요소 | SSMS Connect to Server(modern) | DataGrip Data Sources | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 배치 | 위: History(즐겨찾기, 최근 연결), 아래: 접을 수 있는 Connection Properties(Server Name, Authentication, User Name, Password, Remember Password, Database Name, Encrypt, Trust Server Certificate, Color), `Advanced…` 링크, 단추 Connect / Cancel / Help [M] | 왼쪽 데이터 소스 목록, 오른쪽 이름·탭(General, Options, SSH/SSL, Schemas, Advanced), 아래 Test Connection 링크, OK/Cancel/Apply | DBeaver식 마법사: 드라이버 고르기 → 설정(Main / Driver Properties / Provider Properties 탭), `< Back`, `Next >`, Save, Cancel, Test Connection [S] | SSMS 배치: 첫 줄 `Database type` 콤보(공급자 아이콘, 지금의 드라이버 고르기 화면 대신. 고급 드라이버 목록은 콤보의 "More…"), Host·Port 한 줄, Authentication, User, Password, Remember password, Database, Color, `Advanced…`(드라이버·공급자 속성). 단추: 왼쪽 Test Connection, 오른쪽 **Connect**(기본 단추) / Cancel. 편집 모드에서는 Connect 대신 OK | P1 |
| 모양 | — | 바탕 `bg.chrome`, 입력 높이 28, arc 8, 테두리 L `#C9CCD6` / D `#4E5157`, 기본 단추 `#3574F0` 흰 글자, 일반 단추 테두리 L `#C9CCD6` / D `#4E5157`, 최소 폭 72 [T `Button.*`, `TextField.*`] | 입력 높이 ≈33, arc 5, 포커스 테두리 진한 파랑 2px [S] | `size.control` 28, `radius.control` 8, `button.*` 토큰, 포커스 `focus.ring` 2px(테두리 1 + 안쪽 1) | P0 |
| 간격 | 라벨 열 왼쪽 정렬 | 라벨–입력 6, 줄 사이 6, 묶음 사이 20, 대화상자 안쪽 여백 10,12,10,12 [J `SpacingConfiguration`] | 줄 사이 ≈16, 여백 14 [C] `ConnectionDialog.java:124-150` | `space.6`(라벨–입력, 줄 사이), `space.20`(묶음 사이), 대화상자 여백 10/12, 라벨 오른쪽 끝 `:` 없음(SSMS) | P1 |
| 섹션 제목 | 굵은 글자 + 선 | 제목 + 가로선 | `Authentication` 굵게 + 선 [S] | `font.ui.semibold` + 1px `border.subtle`, 위 20 아래 6 | P2 |

### 12. 팝업 (자동완성, 도구 설명, 알림)

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 자동완성 목록 | IntelliSense 목록 | 행 24 [T `List.rowHeight`], 아이콘 16, 맞는 글자 L `#3574F0` / D `#548AF7` [T `CompletionPopup.matchForeground`], 종류 오른쪽 회색, 바탕 L `#FFFFFF` / D `#2B2D30`, 테두리 L `#B9BDC9` / D `#43454A`, 선택 arc 8 [T][J] | 행 ≈19, 가로 전체 진한 선택, 맞는 글자 표시 없음 [S] `swing-f/b1-composite.png` | 행 `size.row.list` 24, 맞는 글자 `completion.match` 굵게, 오른쪽 형식 `text.secondary`, 선택 `selection.bg` arc 8, 좌우 inset 4, 모서리 반지름 8, 아래 안내 줄(Advertiser) 글꼴 12 `text.secondary` | P1 |
| 도구 설명 | 노란 바탕(고전) | L 바탕 `#27282E` 글자 `#FFFFFF` / D 바탕 `#393B40` 글자 `#F0F1F2`, 테두리 D `#43454A` [T `ToolTip.*`] | FlatLaf 기본 | `tooltip.bg`, `tooltip.fg`, 단축키 글자 `text.secondary`(다크 `#9DA0A8`) | P1 |
| 알림 | — | 바탕 D `#393B40`, 테두리 D `#43454A` [T `Notification.*`] | `NotificationToast` FlatLaf 색 | 팝업 토큰 사용, 모서리 반지름 8 | P2 |

### 13. 스크롤바, 분할선, 포커스, 기타 컨트롤

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 분할선 | 얇은 선 | 1px 선 L `#EBECF0` / D `#393B40` [T `OnePixelDivider.background`], 손잡이 없음 | 5px + 손잡이 점(`⋮`, `•••`) [F `SplitPaneDivider.style = grip`][S] | 보이는 선 1px `border.subtle`, 끄는 영역 5px, 손잡이 없음(`SplitPaneDivider.style = plain` + 가운데 1px 선을 그리는 구분선) | P0 |
| 스크롤바 | 표준 | 얇은 둥근 막대, 막대 폭 ≈7, 트랙 투명 [S] | 폭 10, 막대 6, 트랙 색 있음 [F] | `ScrollBar.width` 11, `thumbInsets` 2(막대 7), `thumbArc` 999, 트랙 = 바탕, 막대 L `#00000033`→마우스 `#00000059` / D `#FFFFFF33`→`#FFFFFF4D`(≈) | P1 |
| 포커스 표시 | 점선 사각형 | 입력: 테두리가 2px `#3574F0` [T `*.focusColor`, `Component.focusColor`], 단추: 테두리 강조 | 2px 진한 파랑(FlatLaf `@accentFocusColor`) [S] | `focus.ring` `#3574F0` 2px(`Component.borderWidth` 1 + `Component.innerFocusWidth` 1), 바깥 링 없음(`Component.focusWidth` 0) | P0 |
| 모서리 | 사각 | 입력·단추 arc 8 [T `Component.arc`, `Button.arc`] | 입력 5, 단추 6 [F] | `radius.control` 8 | P0 |
| 확인란·라디오 | 표준 | 선택: 바탕 `#3574F0` 흰 체크, 기본: 테두리 L `#A8ADBD` [T `Checkbox.*` 아이콘 팔레트] | FlatLaf 기본 | `CheckBox.icon.selectedBackground` = `accent`, arc 4 | P2 |
| 강조색 | Windows 강조색 | `#3574F0` 두 테마 같음 [T Light `Blue4`, Dark `Blue6`] | L `#0078D4`, D `#3574F0` [C] | `accent` `#3574F0` | P0 |

### 14. 간격, 글꼴, 기타

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| UI 글꼴 | Segoe UI | Inter 13(모든 OS) [JetBrains New UI 도움말], 한글은 운영체제 글꼴로 대체 | Segoe UI 12 | `font.ui` Inter 13, 한글 대체 Malgun Gothic(Windows)·Apple SD Gothic Neo(macOS). FlatLaf 합성 글꼴(`defaultFont`가 대체 글꼴을 갖는 composite font)이어야 한글이 □로 나오지 않음 | P0 |
| 간격 단위 | — | 2·4·6·8·12·16·20 [J `SpacingConfiguration`, `Tree.border`, `List.border`] | 화면마다 다름(2, 3, 8, 14 등) [C] | "디자인 토큰"의 `space.*`만 씀 | P1 |
| 시작 탭(Welcome) | 시작 화면 | Welcome 화면(로고, 큰 단추) | 큰 로고 + 안내 글 [S] | 로고 64, 제목 `font.title`, "New Connection", "New Query", "Open File" 링크 단추, 최근 파일 목록 | P2 |
| Islands | — | 2025.3부터 기본: 패널 사이 6px 틈, 패널 모서리 arc 20 [T `ManyIslands*`] | — | 기본 모양으로 적용("적용 현황 — Islands 적용") | 완료 |
| 촘촘한 모드 | — | Compact: 트리 22, 머리말 낮춤, 트리 여백 2,8 [T `*.compact`] | — | 설정으로 제공(SSMS 사용자 밀도 요구) | P2 |

## P0 상위 15개

1. **바탕 계층**: 다크 기반을 `FlatDarculaLaf`에서 `FlatDarkLaf`로 바꾸고 `bg.chrome`/`bg.panel` `#2B2D30`, `bg.editor` `#1E1F22`로 맞춘다(지금 트리·그리드 `#36383A`).
2. **라이트 팔레트**: `bg.chrome`/`bg.panel` `#F7F8FA`, `bg.editor` `#FFFFFF`, `accent` `#3574F0`(지금 `#F3F3F3`, 트리 흰색, `#0078D4`).
3. **선택 표시**: 옅은 선택색(L `#D4E2FF` / D `#2E436E`, 비활성 L `#DFE1E5` / D `#43454A`), 글자색 유지, arc 8, 트리 여백 안쪽(지금 진한 가로 전체 + 흰 글자, 다크 비활성 남색).
4. **트리 치수**: 행 24, 들여쓰기 19, 여백 4,12,4,12, 두 테마 셰브런(지금 19, 18, 다크 삼각형).
5. **트리 아이콘 교체**: DBeaver 아이콘 → 1px 선 16px 아이콘, 중립 회색 + 의미 강조색 규칙(지금 주황 채움 폴더·파란 표).
6. **연결 상태 표시**: PNG 체크 겹침 → 지름 6 상태 점(연결됨 초록, 연결 중 노랑, 오류 빨강).
7. **글꼴**: UI Inter 13, 편집기·그리드 JetBrains Mono 13(지금 Segoe UI 12, Consolas).
8. **도구 창 머리말**: 높이 40, semibold 제목, 아이콘 단추(⋮, —)와 Object Explorer 아이콘 도구 막대(지금 28, 글자 `Refresh`, `×`).
9. **주 도구 막대**: 아이콘 단추 16/26 + hover 바탕, New Query만 글자(지금 글자 단추 4개).
10. **편집기 탭**: 높이 40, 탭 줄 바탕 = 편집기 바탕, 밑줄 4 arc 4, 아이콘 16(지금 32, 아이콘 없음).
11. **쿼리 도구 막대**: `▶ Execute`(초록 아이콘 + 글자), `■` 중지 아이콘, 콤보 28·arc 8·공급자 아이콘(지금 글자 `Execute`/`Cancel`).
12. **라이트 SQL 색**: SSMS 색 → DataGrip Light 색, 줄 번호·현재 줄·선택색 포함.
13. **결과 그리드**: 바탕 `bg.editor`, 행 27, 머리글 28 왼쪽 정렬 + 열 아이콘, 세로·가로 격자선, 옅은 선택(지금 행 20, 가운데 머리글, 다크 `#36383A`).
14. **분할선**: 손잡이 점 제거, 1px 선(지금 `⋮`/`•••`).
15. **컨트롤 모양**: arc 8, 높이 28, 포커스 2px `#3574F0`(대화상자 전체에 영향).

## 디자인 토큰

토큰 이름은 Chromite 내부 이름이다. FlatLaf 키는 `ChromiteLightLaf.properties`/`ChromiteDarkLaf.properties`에 쓸 키다.
FlatLaf에 없는 값(트리 hover, 보조 글자, 그리드 가로선 등)은 같은 파일에 `Chromite.` 접두사 키로 넣고 코드에서 `UIManager.getColor("Chromite.…")`로 읽는다
(FlatLaf는 properties 파일의 모든 키를 UI 기본값에 넣는다).

### 기반 Look and Feel

| 테마 | 지금 | 목표 | 이유 |
|---|---|---|---|
| Light | `FlatLightLaf` | `FlatLightLaf` 유지 | 셰브런, 평면 기본값이 New UI와 가깝다 |
| Dark | `FlatDarculaLaf` | `FlatDarkLaf` | Darcula는 삼각 화살표(`Component.arrowType = triangle`)와 Darcula 고유 색을 덧씌운다 [F `FlatDarculaLaf.properties`] |

JetBrains 테마 JSON을 FlatLaf `IntelliJTheme`로 그대로 읽는 방법은 쓰지 않는다. 토큰을 Chromite가 소유하고(값만 New UI 팔레트에서 가져옴),
IntelliJ 전용 키와 FlatLaf 키의 차이를 줄이기 위해서다(설계 결정 "토큰 소유" 참고).

### 색 토큰

| 토큰 | 용도 | Light | Dark | New UI 근거 [T][E][I] | FlatLaf 키 |
|---|---|---|---|---|---|
| `bg.chrome` | 제목 표시줄, 메뉴 막대, 주 도구 막대, 상태 표시줄, 대화상자 | `#F7F8FA` | `#2B2D30` | `*.background`(L Gray13, D Gray2), `MainToolbar.background`(Light with Light Header Gray13) | `@background` (→ `Panel.background`, `MenuBar.background`, `TitlePane.background`, `ToolBar.background`) |
| `bg.panel` | 도구 창 내용(트리, Properties) | `#F7F8FA` | `#2B2D30` | `ToolWindow.background`(L Gray13), `*.background` | `Tree.background`, `Chromite.panelBackground` |
| `bg.editor` | SQL 편집기, 결과 그리드, 편집기 탭 줄 | `#FFFFFF` | `#1E1F22` | `TEXT` 바탕, `EditorTabs.background`(L Gray14, D Gray1) | `Table.background`, `TableHeader.background`, `Chromite.editorBackground`(문서 탭 `JTabbedPane` 스타일), RSTA `<background>`·`<gutterBackground>` |
| `bg.input` | 텍스트 필드, 콤보 | `#FFFFFF` | `#2B2D30` | `TextField.background`(L Gray14, D Gray2), `ComboBox.background`(L Gray14) | `@componentBackground`, `TextField.background`, `ComboBox.background` |
| `bg.popup` | 메뉴 팝업, 자동완성, 콤보 목록 | `#FFFFFF` | `#2B2D30` | `Popup.background`(L Gray14), `PopupMenu.background` | `PopupMenu.background`, `List.background`(팝업 안), `@menuBackground` |
| `bg.hover` | 트리·목록 행 마우스 올림 | `#EDF3FF` | `#393B40` | `*.hoverBackground`(L Blue12, D Gray3) | `Chromite.Tree.hoverBackground`, `@menuHoverBackground` |
| `bg.actionHover` | 아이콘 단추 마우스 올림 | `#00000012` | `#FFFFFF16` | `ActionButton.hoverBackground` | `Button.toolbar.hoverBackground`, `TabbedPane.closeHoverBackground` |
| `bg.actionPressed` | 아이콘 단추 누름 | `#0000001D` | `#FFFFFF26` | `ActionButton.pressedBackground` | `Button.toolbar.pressedBackground`, `TabbedPane.closePressedBackground` |
| `border.default` | 입력·단추 테두리 | `#C9CCD6` | `#4E5157` | `Component.borderColor`, `Button.startBorderColor`(L Gray9, D Gray5) | `Component.borderColor`, `Button.borderColor` |
| `border.subtle` | 구분선, 분할선, 머리말 아래 선, 탭 줄 아래 선 | `#EBECF0` | `#393B40` | `OnePixelDivider.background`, `*.separatorColor`(L Gray12, D Gray3), `EditorTabs.underTabsBorderColor`(D Gray3) | `Separator.foreground`, `ToolBar.separatorColor`, `TabbedPane.contentAreaColor`, `Chromite.dividerColor` |
| `border.popup` | 팝업 테두리 | `#B9BDC9` | `#43454A` | `Popup.borderColor`(L windowsPopupBorder, D Gray4) | `PopupMenu.borderColor`, `Popup.borderColor` |
| `text.primary` | 기본 글자 | `#000000` | `#DFE1E5` | `*.foreground`(D Gray12, L Gray1) | `@foreground` |
| `text.secondary` | 보조 글자, 단축키, 트리 주석, 행 번호, 상태 표시줄 | `#818594` | `#6F737A` | `*.infoForeground`(Gray7) | `Chromite.secondaryForeground`, `MenuItem.acceleratorForeground`, `@menuAcceleratorForeground` |
| `text.disabled` | 비활성 | `#A8ADBD` | `#5A5D63` | `*.disabledForeground`(L Gray8, D Gray6) | `@disabledForeground`, `Label.disabledForeground` |
| `text.onAccent` | 기본 단추 글자 | `#FFFFFF` | `#FFFFFF` | `Button.default.foreground`(Gray14) | `Button.default.foreground` |
| `text.link` | 링크 | `#315FBD` | `#6B9BFA` | `Link.activeForeground`(L Blue2, D Blue9) | `Component.linkColor`(`@accentLinkColor`) |
| `accent` | 강조, 기본 단추 바탕, 확인란 | `#3574F0` | `#3574F0` | `Button.default.startBackground`(L Blue4, D Blue6) | `@accentColor`(운영체제 강조색 대신 고정), `Button.default.background` |
| `focus.ring` | 포커스 테두리 | `#3574F0` | `#3574F0` | `*.focusColor` | `Component.focusColor`, `Component.focusedBorderColor`, `Button.focusedBorderColor` |
| `selection.bg` | 포커스 있는 선택(트리, 목록, 그리드, 메뉴) | `#D4E2FF` | `#2E436E` | `*.selectionBackground`(L Blue11, D Blue2) | `@selectionBackground`, `@menuSelectionBackground` |
| `selection.fg` | 선택 글자 | `#000000` | `#DFE1E5` | `*.selectionForeground` | `@selectionForeground` |
| `selection.inactiveBg` | 포커스 없는 선택 | `#DFE1E5` | `#43454A` | `*.selectionInactiveBackground`(L Gray11, D Gray4) | `@selectionInactiveBackground` |
| `tab.underline` | 활성 탭 밑줄(포커스 있음) | `#3574F0` | `#3574F0` | `*.underlineColor` | `TabbedPane.underlineColor` |
| `tab.underlineInactive` | 활성 탭 밑줄(포커스 없음) | `#A8ADBD` | `#5A5D63` | `*.inactiveUnderlineColor`(L Gray8, D Gray6) | `TabbedPane.inactiveUnderlineColor` |
| `tab.hover` | 탭 마우스 올림 | `#EBECF0` | `#43454A` | `TabbedPane.hoverColor`(L Gray12, D Gray4) | `TabbedPane.hoverColor` |
| `grid.line` | 그리드 세로선, 머리글 아래 선 | `#EBECF0` | `#393B40` | `Table.gridColor`(L Gray12), 다크는 [S] 비율로 Gray3 | `Table.gridColor`, `TableHeader.separatorColor`, `TableHeader.bottomSeparatorColor` |
| `grid.lineSubtle` | 그리드 가로선 | `#F7F8FA` | `#2B2D30` | [S] 가로선이 세로선보다 옅음 | `Chromite.Table.horizontalLineColor` |
| `tooltip.bg` / `tooltip.fg` | 도구 설명 | `#27282E` / `#FFFFFF` | `#393B40` / `#F0F1F2` | `ToolTip.background`/`foreground` | `ToolTip.background`, `ToolTip.foreground` |
| `status.success` | 연결됨 점, 성공 | `#208A3C` | `#5FAD65` | 팔레트 L Green4, D Green7, 실행 아이콘 [I] | `Chromite.status.success` |
| `status.warning` | 경고, 연결 중 | `#A46704`(글자) / `#FFAF0F`(아이콘) | `#BA9752`(글자) / `#F2C55C`(아이콘) | `Label.warningForeground`(L Yellow1, D Yellow5), 팔레트 L Yellow4, D Yellow7 | `Chromite.status.warning`, `Component.warning.focusedBorderColor` |
| `status.error` | 오류 | `#DB3B4B` | `#DB5C5C` | `Label.errorForeground`(L Red4, D Red7) | `Chromite.status.error`, `Component.error.focusedBorderColor`, `Actions.Red` 대신 |
| `completion.match` | 자동완성 맞는 글자 | `#3574F0` | `#548AF7` | `CompletionPopup.matchForeground`(L Blue4, D Blue8) | `Chromite.completion.matchForeground` |

### SQL 구문 색 (RSyntaxTextArea 토큰)

DataGrip의 SQL 전용 색은 공개되지 않은 데이터베이스 플러그인에 정의되어 있어, IntelliJ 공통 기본 속성(`DEFAULT_*`)으로 맞춘다.
함수 색은 `DEFAULT_FUNCTION_DECLARATION` 값을 쓰는 근사값이다.

| RSTA 토큰 | New UI 속성 [E] | Light | Dark |
|---|---|---|---|
| `IDENTIFIER`, `OPERATOR`, `SEPARATOR`, `LITERAL_BACKQUOTE`, 기타 | `TEXT`, `DEFAULT_IDENTIFIER`, `DEFAULT_OPERATION_SIGN` | `#080808` | `#BCBEC4` |
| `RESERVED_WORD`, `RESERVED_WORD_2`, `DATA_TYPE`, `LITERAL_BOOLEAN` | `DEFAULT_KEYWORD` | `#0033B3` | `#CF8E6D` |
| `FUNCTION` | `DEFAULT_FUNCTION_DECLARATION`(근사) | `#00627A` | `#56A8F5` |
| `LITERAL_STRING_DOUBLE_QUOTE`, `LITERAL_CHAR` | `DEFAULT_STRING` | `#067D17` | `#6AAB73` |
| `LITERAL_NUMBER_*` | `DEFAULT_NUMBER` | `#1750EB` | `#2AACB8` |
| `COMMENT_*` | `DEFAULT_LINE_COMMENT`, `DEFAULT_BLOCK_COMMENT` | `#8C8C8C` 기울임 | `#7A7E85` |
| `VARIABLE` | `DEFAULT_INSTANCE_FIELD` | `#871094` | `#C77DBB` |
| `ERROR_*` | `ERRORS_ATTRIBUTES`(물결 밑줄) | 밑줄 `#DB3B4B`(근사, 바탕 없음) | 밑줄 `#FA6675` |
| 바탕 / 커서 | `TEXT`, `CARET_COLOR` | `#FFFFFF` / `#000000` | `#1E1F22` / `#CED0D6` |
| 현재 줄 | `CARET_ROW_COLOR` | `#F5F8FE` | `#26282E` |
| 선택 | `SELECTION_BACKGROUND` | `#A6D2FF` | `#214283` |
| 줄 번호 / 현재 줄 번호 | `LINE_NUMBERS_COLOR`, `LINE_NUMBER_ON_CARET_ROW_COLOR` | `#AEB3C2` / `#767A8A` | `#4B5059` / `#A1A3AB` |
| 짝 괄호 | `MATCHED_BRACE_ATTRIBUTES` | 바탕 `#93D9D9` | 바탕 `#43454A` |
| 같은 이름 | `IDENTIFIER_UNDER_CARET_ATTRIBUTES` | `#EDEBFC` | `#373B39` |
| 오른쪽 여백 선 | `RIGHT_MARGIN_COLOR` | `#EBECF0` | `#393B40` |

다크 `SqlEditorDark.xml`은 이미 이 값과 거의 같다(차이: `ERROR_*` 바탕 칠 → 밑줄). 라이트 `SqlEditorLight.xml`은 전부 바꾼다.

### 치수 토큰

| 토큰 | 값 | 근거 | FlatLaf 키 / 구현 |
|---|---|---|---|
| `size.row.tree` | 24 | [T `Tree.rowHeight`][J] | `Tree.rowHeight = 24` |
| `size.row.list` | 24 | [T `List.rowHeight`] | 목록 `setFixedCellHeight(24)` 또는 `List.cellMargins` |
| `size.row.menu` | 24 | ≈ New UI 메뉴 | `MenuItem.margin = 4,8,4,8` |
| `size.row.grid` | 27 | [S] DataGrip 그리드 | `Table.rowHeight = 27`, `Table.cellMargins = 2,8,2,8` |
| `size.header.grid` | 28 | [S] | `TableHeader.height = 28`, `TableHeader.cellMargins = 2,8,2,8` |
| `size.header.toolWindow` | 40 (+ 아래 선 1) | [J] 41 | `ToolWindow` 머리말 패널 높이 |
| `size.tab.editor` | 40 | [T `TabbedPane.tabHeight`] | 문서 탭: `JTabbedPane` 스타일 `tabHeight: 40` |
| `size.tab.tool` | 32 | Chromite 결정(쿼리 창 안 결과 탭은 낮게) | 결과 탭: 스타일 `tabHeight: 32` |
| `size.tabUnderline` | 4 (탭) / 3 (결과 탭) | [T `EditorTabs.underlineHeight`][J] | `TabbedPane.tabSelectionHeight`, `TabbedPane.tabSelectionArc = 4` |
| `size.titleBar` | 40 | [J] 주 도구 막대 40 | `TitlePane.buttonSize = 46,40` |
| `size.toolbar.main` | 36 | Chromite 결정(SSMS 두 줄 유지) | `ToolBar.borderMargins = 5,8,5,8` |
| `size.toolbar.editor` | 36 | [S] ≈36 | 편집기 도구 막대 여백 |
| `size.statusBar` | 28 | ≈ | 상태 표시줄 여백 |
| `size.control` | 28 | [T `Button.minimumSize` 72,28, `TextField.minimumSize`, `ComboBox.minimumSize`] | `@componentMargin`, `Button.margin`, `ComboBox.padding = 1,9,1,6`로 28이 되게(또는 `JComponent.minimumHeight` 클라이언트 속성) |
| `size.button.minWidth` | 72 | [T] | `Button.minimumWidth = 72` |
| `size.actionButton` | 26 | [S] | `Button.toolbar.margin = 5,5,5,5` (16 + 10) |
| `size.arrowArea` | 28 | [T `Component.arrowAreaWidth`] | 콤보 화살표 단추 폭 |
| `size.icon` | 16 | [I] | — |
| `size.icon.large` | 20 | [J] New UI 주 도구 막대·띠 아이콘 20 | 나중(주 도구 막대 큰 아이콘) |
| `size.statusDot` | 6 | [S] | 상태 점 지름 |
| `size.badge` | 8 | 아이콘 지침 "6~9px" | 겹침 배지 |
| `size.indent.tree` | 19 | [S] | `Tree.leftChildIndent = 7`, `Tree.rightChildIndent = 12` |
| `size.scrollbar` | 11 (막대 7) | [S] | `ScrollBar.width = 11`, `ScrollBar.thumbInsets = 2,2,2,2`, `ScrollBar.thumbArc = 999` |
| `size.divider` | 끄는 영역 5, 선 1 | [T `OnePixelDivider`] | `SplitPane.dividerSize = 5`, `SplitPaneDivider.style = plain` + 1px 선 |
| `size.focusWidth` | 2 (테두리 1 + 안쪽 1) | [T] | `Component.focusWidth = 0`, `Component.innerFocusWidth = 1`, `Component.borderWidth = 1` |

### 모서리 토큰 (FlatLaf·IntelliJ의 arc는 지름이다. arc 8 = 반지름 4)

| 토큰 | arc | 쓰는 곳 | 근거 | FlatLaf 키 |
|---|---|---|---|---|
| `radius.small` | 4 | 탭 밑줄 끝, 탭 닫기 단추, 확인란 | [T `EditorTabs.underlineArc`] | `TabbedPane.tabSelectionArc`, `TabbedPane.closeArc`, `CheckBox.arc` |
| `radius.action` | 6 | 아이콘 단추 hover | ≈[S] | 도구 막대 단추 스타일 `arc: 6`(`FlatClientProperties.STYLE`), `ToolBar.hoverButtonGroupArc = 6` |
| `radius.control` | 8 | 입력, 단추, 콤보, 트리·목록·메뉴 선택 | [T `Component.arc`, `Button.arc`][J `Tree.Selection.arc`, `Popup.Selection.arc`] | `Component.arc`, `Button.arc`, `TextComponent.arc`, `Tree.selectionArc`, `List.selectionArc`, `MenuItem.selectionArc`, `ComboBox.selectionArc` |
| `radius.popup` | 반지름 8 | 팝업, 메뉴 (Windows 11 둥근 창) | Windows 11 | `Popup.borderCornerRadius = 8`, `PopupMenu.borderCornerRadius = 8` |
| `radius.island` | 20 | Islands 카드 | [T `Island.arc`] | `Chromite.island.arc` |

### 간격 토큰

| 토큰 | 값 | 쓰는 곳 | 근거 |
|---|---|---|---|
| `space.2` | 2 | 도구 막대 단추 사이, 주석 아래 | [J `verticalCommentBottomGap`] |
| `space.4` | 4 | 트리·목록 위아래 여백, 선택 inset(팝업) | [T `Tree.border`, `List.border`] |
| `space.6` | 6 | 라벨–입력, 폼 줄 사이, 아이콘–글자 | [J `horizontalSmallGap`, `verticalComponentGap`][J `ActionsList.icon.gap`] |
| `space.8` | 8 | 작은 묶음 사이, 그리드 셀 좌우 여백, 팝업 선택 좌우 | [J `verticalSmallGap`, `Popup.Selection.leftRightInset`] |
| `space.12` | 12 | 트리 좌우 여백, 머리말 왼쪽, 대화상자 좌우 | [T `Tree.border`][J `labelLeftRightInsets`, `dialogUnscaledGaps` 10,12,10,12] |
| `space.16` | 16 | 가로 묶음 사이 | [J `horizontalDefaultGap`] |
| `space.20` | 20 | 세로 묶음 사이, 종속 컨트롤 들여쓰기 | [J `verticalMediumGap`] |

### 글꼴 토큰

| 토큰 | 값 | 쓰는 곳 | FlatLaf |
|---|---|---|---|
| `font.ui` | Inter 13 보통 | 모든 UI | `defaultFont = 13 Inter` (+ 한글 대체를 위해 합성 글꼴) |
| `font.ui.semibold` | Inter 13 semibold | 도구 창 제목, 섹션 제목, Properties 분류 | 스타일 클래스 `semibold` |
| `font.ui.small` | Inter 12 | 팝업 안내 줄, 배지 | 스타일 클래스 `small`(FlatLaf `@smallScale`이 아니라 −1px로 맞춤) |
| `font.title` | Inter 15 semibold | 대화상자 머리말, 시작 탭 제목 | 스타일 클래스 `h3` 크기 조정 |
| `font.code` | JetBrains Mono 13, 줄 간격 1.2 | SQL 편집기, 결과 그리드, 메시지 | RSTA `<baseFont>` 또는 코드에서 지정 |

Inter와 JetBrains Mono는 SIL Open Font License 1.1이다. FlatLaf가 두 글꼴을 별도 jar(`flatlaf-fonts-inter`, `flatlaf-fonts-jetbrains-mono`)로 낸다.
넣으려면 의존성 추가와 `NOTICE` 갱신이 필요하므로 구현 단계에서 따로 결정한다("미룬 결정").
넣지 않으면 대체 값은 Segoe UI 13(Windows), 시스템 글꼴 13(macOS)이고 고정폭은 Cascadia Mono/Consolas, Menlo다.

## 아이콘 요구 사항

### 스타일 (DataGrip New UI 아이콘 분석 [I])

DataGrip·IntelliJ New UI 아이콘 SVG를 직접 읽어 확인한 규칙이다.

- **격자**: 16×16. 그림 영역 14×14(바깥 1px 여백) [IntelliJ 아이콘 지침]. 선은 0.5 좌표에 놓여 100%·200%에서 선명하다.
- **선 굵기**: 1px(SVG에 `stroke-width` 없음 = 1), 끝은 둥글게(`stroke-linecap="round"`), 모서리 반지름 ≈1.5.
- **채움**: 윤곽선 안을 옅은 색으로 채운다(중립: L `#EBECF0` / D `#43454A`, 파랑: L `#E7EFFD`·`#EDF3FF` / D `#25324D`).
- **색**: 기본은 중립 회색 한 가지. 의미가 있는 부분만 강조색(아래 "색 규칙").
- **선택 행**: 선택색이 옅어서(`selection.bg`) 아이콘 색을 바꾸지 않는다(DBeaver처럼 선택용 아이콘이 필요 없다).

어느 세트에도 적용할 수 있는 요구 사항:

| 항목 | 요구 |
|---|---|
| 렌더 크기 | 16(트리, 목록, 메뉴, 탭, 도구 창 머리말, 편집기·주 도구 막대, 상태 줄), 20(나중: 큰 도구 막대), 12(상태 표시줄 위젯, 작은 배지 안 그림), 32(드라이버 고르기 목록) |
| 선 굵기 | 16px로 그렸을 때 1px(허용 1.0~1.25). 24 격자 세트(Lucide, Tabler)는 `stroke-width` 1.5(= 16px에서 1px)로 쓰고, 크기별 원본이 있는 세트(Fluent UI System Icons)는 16·20 원본 그림을 그대로 쓴다(20을 16으로 줄이지 않음) |
| 형식 | SVG, JSVG로 벡터 렌더링(HiDPI 대응은 지금 `NodeIcons.SvgIcon`과 같음) |
| 색 바꾸기 | SVG 안에 색을 고정하지 않는다. 선은 `currentColor`(또는 정한 자리표시 색)로 두고 렌더링 때 토큰 색으로 바꾼다. 테마마다 따로 파일(`_dark`)을 두지 않는다 |
| 2색 표현 | 한 색 선 세트(Lucide, Tabler)는 "기본 그림(중립) + 강조 배지(강조색)" 합성으로 2색을 만든다. 배지는 오른쪽 아래 6~9px, 기본 그림과 1~2px 떨어지게(배지 자리의 기본 그림을 지움) [IntelliJ 아이콘 지침] |
| 비활성 | `text.disabled` 색으로 다시 칠함(투명도 낮추기 대신) |
| 없는 개념 | 세트에 없는 개념은 가장 가까운 기본 그림 + 배지로 만든다(예: 시스템 테이블 = 표 + 톱니 배지) |

### 색 규칙

| 토큰 | Light 선 / 채움 | Dark 선 / 채움 | 쓰는 곳 [I] |
|---|---|---|---|
| `icon.neutral` | `#6C707E` / `#EBECF0` | `#CED0D6` / `#43454A` | 기본(표, 열, 스키마, 사용자, 시퀀스, 제약, 작업 아이콘 대부분) |
| `icon.blue` | `#3574F0` / `#E7EFFD` | `#548AF7` / `#25324D` | 데이터베이스·카탈로그, 함수·프로시저, 인덱스, 외래 키, 객체 묶음 폴더, 뷰의 돋보기 배지 |
| `icon.orange` | `#E66D17` | `#F2C55C` | 기본 키(DataGrip "gold key") |
| `icon.green` | `#208A3C` / `#F2FCF3` | `#57965C` / `#253627` | 실행, 연결 |
| `icon.red` | `#DB3B4B` / `#FFF7F7` | `#DB5C5C` / `#5E3838` | 중지, 끊기, 삭제, 오류 |
| `icon.yellow` | `#FFAF0F` | `#F2C55C` | 경고 |
| `icon.chevron` | `#818594` | `#B4B8BF` | 트리 화살표, 콤보 화살표, 넘침 |

규칙: 트리 아이콘은 종류마다 색을 칠하지 않는다(DBeaver와의 가장 큰 차이). 회색 윤곽이 기본이고, 아래 표의 "강조" 열에 적은 부분만 강조색을 쓴다.

### 필요한 아이콘 개념 (모두 100개)

`DBIcon` 열은 지금 탐색기 모델이 주는 아이콘(`plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/model/DBIcon.java`)이다.
구현할 때 `NodeIcons`가 `DBIcon` 위치를 Chromite 아이콘 ID로 바꾸는 표를 갖는다. 드라이버 플러그인이 자체 아이콘을 쓰는 노드(예: PostgreSQL 전용 객체)는
노드 종류(`DBXTreeNode`)로 대응시키고, 대응이 없으면 `object.unknown`을 쓴다.

**A. 연결·구조 (8)**

| ID | 개념 | DBIcon | 강조 |
|---|---|---|---|
| `ds.generic` | 데이터 소스(공급자 로고가 없을 때) | `DATABASE_DEFAULT` | 중립 |
| `ds.folder` | 사용자 연결 폴더 | `TREE_FOLDER`(`DBNLocalFolder`) | 중립 |
| `server` | 서버·인스턴스 | `TREE_SERVER`, `TREE_SERVERS` | 중립 |
| `database` | 데이터베이스·카탈로그 | `TREE_DATABASE`, `TREE_DATABASE_CATEGORY` | 파랑(DataGrip: 겹친 둥근 사각형 2개) |
| `schema` | 스키마 | `TREE_SCHEMA` | 중립 |
| `schema.system` | 시스템 스키마 | `TREE_SCHEMA_SYSTEM`, `TREE_SCHEMA_UTIL` | 중립 + 톱니 배지 |
| `folder.objects` | 객체 묶음 폴더(Tables, Views, Procedures, Functions, Sequences, Triggers, Indexes, Users·Roles, Data Types, Constraints, Keys …) | `TREE_FOLDER_TABLE`, `_VIEW`, `_SCHEMA`, `_DATABASE`, `_USER`, `_CONSTRAINT`, `_KEY`, `_INFO`, `TREE_FOLDER` | 파랑 윤곽(DataGrip은 모든 객체 묶음에 같은 파란 폴더) |
| `folder.system` | 서버 객체·관리 묶음 | `TREE_FOLDER_ADMIN`, `TREE_ADMIN` | 중립 폴더 + 파란 배지 |

**B. 객체 (30)**

| ID | 개념 | DBIcon | 강조 |
|---|---|---|---|
| `table` | 테이블 | `TREE_TABLE` | 중립(격자) |
| `table.system` | 시스템 테이블 | `TREE_TABLE_SYSTEM` | 중립 + 톱니 배지 |
| `table.external` | 외부·외래 테이블 | `TREE_TABLE_EXTERNAL`, `TREE_TABLE_LINK` | 중립 + 화살표 배지 |
| `table.partition` | 파티션 | `TREE_PARTITION` | 중립 |
| `view` | 뷰 | `TREE_VIEW` | 중립 표 + 파란 돋보기 배지 |
| `view.materialized` | 구체화된 뷰 | (드라이버별) | 중립 + 파란 배지 |
| `column` | 열 | `TREE_COLUMN`, `TREE_COLUMNS` | 중립(세로 칸 강조) |
| `column.derived` | 계산 열 | `TREE_DERIVED_COLUMN` | 중립 + 배지 |
| `key.primary` | 기본 키 | `TREE_UNIQUE_KEY`(PK 제약) | 주황 |
| `key.unique` | 고유 키 | `TREE_UNIQUE_KEY` | 중립 |
| `key.foreign` | 외래 키 | `TREE_FOREIGN_KEY`, `TREE_FOREIGN_KEY_COLUMN`, `TREE_REFERENCE` | 파랑 |
| `key.generic` | 키 | `TREE_KEY` | 중립 |
| `constraint` | 검사·기타 제약 | `TREE_CONSTRAINT` | 중립 |
| `index` | 인덱스 | `TREE_INDEX`, `TREE_TABLE_INDEX` | 파랑 |
| `trigger` | 트리거 | `TREE_TRIGGER` | 중립 + 파란 번개 |
| `function` | 함수 | `TREE_FUNCTION` | 파랑 |
| `procedure` | 프로시저 | `TREE_PROCEDURE` | 파랑(함수와 모양 구분) |
| `package` | 패키지 | `TREE_PACKAGE` | 중립 |
| `argument` | 인자·매개변수 | `TREE_ARGUMENT` | 중립 |
| `sequence` | 시퀀스 | `TREE_SEQUENCE` | 중립 |
| `synonym` | 동의어·별칭 | `TREE_SYNONYM`, `TREE_TABLE_ALIAS` | 중립 + 화살표 배지 |
| `datatype` | 데이터 타입 | `TREE_DATA_TYPE` | 중립 |
| `attribute` | 타입 속성 | `TREE_ATTRIBUTE` | 중립 |
| `user` | 사용자 | `TREE_USER` | 중립 |
| `role` | 역할·그룹 | `TREE_USER_GROUP` | 중립 + 파란 배지 |
| `privilege` | 권한 | `TREE_PERMISSIONS` | 중립 |
| `tablespace` | 테이블스페이스·저장소 | `TREE_TABLESPACE`, `TREE_FILE` | 중립 |
| `job` | 이벤트·스케줄·작업 | `TREE_EVENT`, `TREE_SCHEDULER`, `TREE_JOB`, `TREE_JOB_SCHEDULE` | 중립 |
| `session` | 세션 | `TREE_SESSIONS` | 중립 |
| `object.unknown` | 알 수 없음(대체) | `TYPE_UNKNOWN` | 중립 |

**C. 열 배지와 상태 겹침 (12)**

| ID | 개념 | 위치·크기 | 색 |
|---|---|---|---|
| `badge.pk` | 기본 키 열 | 오른쪽 아래 8 | 주황 |
| `badge.fk` | 외래 키 열 | 오른쪽 아래 8(PK와 같이면 PK 왼쪽) | 파랑 |
| `badge.index` | 인덱스 열 | 오른쪽 아래 8 | 파랑 |
| `badge.notNull` | NOT NULL 열 | 왼쪽 아래 점 4 | 중립 |
| `badge.search` | 뷰(돋보기) | 오른쪽 아래 8 | 파랑 |
| `badge.system` | 시스템 객체(톱니) | 오른쪽 아래 8 | 중립 |
| `state.connected` | 연결됨 | 오른쪽 아래 점 6, 1px 끊김 | `status.success` |
| `state.connecting` | 연결 중 | 같음 | `status.warning` |
| `state.error` | 연결 실패 | 같음 | `status.error` |
| `overlay.readOnly` | 읽기 전용 연결 | 왼쪽 아래 자물쇠 8 | 중립 |
| `overlay.network` | SSH·프록시 터널 | 오른쪽 위 8 | 중립 |
| `overlay.invalid` | 잘못된 객체(컴파일 오류 등) | 왼쪽 아래 8 | `status.error` |

**D. 작업 (39)** — 도구 막대·메뉴·오른쪽 클릭 메뉴

| 분류 | ID (색) |
|---|---|
| 연결 | `action.newConnection`(중립 + 초록 `+`), `action.connect`(초록), `action.disconnect`(빨강), `action.editConnection`(중립), `action.refresh`, `action.filter`, `action.collapseAll`, `action.stopLoading`(빨강) |
| 파일 | `action.newQuery`(중립 + 초록 `+`), `action.openFile`, `action.save`, `action.saveAll` |
| 편집 | `action.undo`, `action.redo`, `action.cut`, `action.copy`, `action.paste`, `action.find`, `action.comment`, `action.uncomment` |
| 실행 | `action.run`(초록, Execute), `action.stop`(빨강, 실행 취소), `action.parse`(중립 체크), `action.explainPlan` |
| 결과 | `action.resultsToGrid`, `action.resultsToText`, `action.exportCsv`·`action.exportXlsx`(같은 내보내기 그림), `action.copyWithHeaders`, `action.sortAsc`, `action.sortDesc` |
| 객체 | `action.scriptAs`(DDL), `action.selectTopRows`, `action.viewDdl`, `action.properties` |
| 창·일반 | `action.more`(⋮), `action.hideToolWindow`(—), `action.close`(×), `action.settings`, `action.themeLight`·`action.themeDark` |

(`action.exportCsv`·`action.exportXlsx`, `action.themeLight`·`action.themeDark`는 각각 1개로 셌다. `action.close`는 `icon.neutral`, 탭 닫기에도 쓴다.)

**E. 상태·메시지·화면 (11)**

`msg.info`, `msg.warning`(노랑), `msg.error`(빨강), `msg.success`(초록 체크), `msg.running`(진행 표시, 회전), `results.grid`(Results 탭), `results.messages`(Messages 탭),
`chevron.right`, `chevron.down`(트리·콤보, `icon.chevron`), `file.sql`(SQL 파일 탭), `console.sql`(파일 없는 새 쿼리 탭).

개수: A 8 + B 30 + C 12 + D 39 + E 11 = **100개**. 이 밖에 결과 그리드 머리글과 자동완성의 데이터 형식 아이콘(`DBIcon.TYPE_*`: 숫자, 문자열, 날짜·시간, 불리언, 이진·LOB, JSON, XML, 배열, 구조체·객체, UUID 등 약 10개)이 필요하면 P2로 더한다(DataGrip은 그리드 머리글에 형식 대신 열 아이콘을 쓴다).

### 공급자(DB 제품) 아이콘 정책

- DataGrip은 데이터 소스에 공급자 로고(PostgreSQL 코끼리, MySQL 돌고래 등)를 보인다. SSMS는 SQL Server만 다루므로 해당 없음.
- **지금 결정**: 데이터 소스 노드, 드라이버 고르기, 연결 콤보, 콘솔 탭에는 지금처럼 DBeaver 드라이버 플러그인이 가진 공급자 아이콘을 그대로 쓴다
  (파일의 저작권 라이선스는 Apache 2.0. 로고 자체의 상표권은 각 회사에 있고, 어느 DB에 연결하는지 알리는 지명적 사용이다).
- 로고는 다시 칠하거나 모양을 바꾸지 않는다(상표 지침이 대개 변형을 금지). 그래서 공급자 로고는 "색 바꾸기" 규칙의 예외다.
  연결 상태 점은 로고 위가 아니라 로고 오른쪽 아래 바깥 테두리에 겹친다.
- 제품명·제품 아이콘 자리(제목 표시줄, About, 시작 탭)에는 공급자 로고를 쓰지 않는다.
- 공급자 아이콘이 없는 드라이버와, 상표 검토 결과 쓰지 않기로 한 공급자는 `ds.generic`(중립) + 공급자 이름 첫 글자 배지로 대신한다.
- 공개 배포 전에 주요 공급자(Oracle/MySQL, Microsoft SQL Server, IBM Db2 등)의 상표 사용 지침을 확인한다("미룬 결정").

## 적용 현황 (2026-11-06)

P0 전체와 P1 일부를 `com.seizia.chromite.ui.swing`에 적용했다. 동작(메뉴, 단축키, 실행 흐름)은 바꾸지 않았고, Object Explorer에 Filter와 Collapse All 명령만 더했다.

측정 방법: Windows 11, 화면 배율 150%, JBR 25, 창 1280×900(물리 1920×1350). 창만 캡처한 화면(`shot.ps1`)에서 픽셀을 읽고 1.5로 나눴다.
캡처는 `D:\Tools\_scratch\swing-design\`에 있다(`m-<light|dark>-<A|B>-1`: 트리에서 Album 선택, `-2`: 결과 그리드 셀 선택. A = duo-tone, B = SSMS color).

### Islands 적용 (2026-11-06)

근거: IntelliJ Platform의 `ManyIslandsLight.theme.json`, `ManyIslandsDark.theme.json`(Apache 2.0, 공부용 사본 `D:\Tools\_scratch\design-spec\ref\`, 저장소에는 넣지 않음).
테마 파일의 이름 색(`main-window-bg` 등)을 같은 파일의 팔레트(`gray-20` 등)까지 풀어서 값만 옮겼다. 화면 이미지는 받지 않았다(테마 파일의 값으로 충분했고, 공급사 이미지는 저장소에 넣지 않는다).

구조: 메인 창 바탕은 **캔버스**이고, Object Explorer, 문서 탭과 편집기, Results/Messages, Properties가 각각 **둥근 카드(island)**다.
제목 표시줄·메뉴 막대·주 도구 막대·상태 표시줄은 캔버스 위에 있고 선이 없다. 카드 사이 틈은 분할선(끌 수 있음, 선 없음)이고 바깥 왼쪽·오른쪽에도 같은 틈을 둔다.

| 토큰 (`Chromite.island.*` 또는 FlatLaf 키) | Light | Dark | Islands 근거 |
|---|---|---|---|
| `Chromite.island.canvas`(`MenuBar.background`, `TitlePane.background`) | `#E9EAEE` | `#26282C` | `MainWindow.background`, `MainToolbar.background`, `StatusBar.background` = `main-window-bg` |
| `Chromite.island.background` = `@background`(트리, 패널, 편집기, 그리드) | `#FFFFFF` | `#191A1C` | `ToolWindow.background` = `tool-window-bg`, `EditorTabs.background` = `editor-bg` |
| `Chromite.island.arc` | 20(반지름 10) | 20 | `Island.arc` |
| `Chromite.island.gap`, `SplitPane.dividerSize` | 6 | 6 | `Island.borderWidth`(카드 테두리 색 `Island.borderColor` = 카드 바탕이라 테두리는 보이지 않음) |
| 탭 알약 선택(포커스) `tabSelectedBackground` / `tabSelectedBorderColor` | `#E3EBFE` / `#A7C5FF` | `#233558` / `#2E4D89` | `EditorTabs.underlinedTabBackground` / `underlinedBorderColor` |
| 탭 알약 선택(포커스 없음) `tabInactiveBackground` / `tabInactiveBorderColor` | `#E9EAEE` / `#D1D3D9` | `#26282C` / `#40434A` | `EditorTabs.inactiveUnderlinedTabBackground` / `inactiveUnderlinedTabBorderColor` |
| 탭 마우스 올림 `tabHoverBackground` | `#00000012` | `#FFFFFF17` | `EditorTabs.hoverBackground` = `tab-bg-hovered` |
| `tabArc`, 알약 위아래 여백 | 12, 6 | 12, 6 | arc는 Chromite 결정(테마 파일에 탭 arc 없음), 여백은 `EditorTabs.tabInsets` -6 |
| `@foreground` | `#000000` | `#D1D3D9` | `text-default` |
| `Chromite.secondaryForeground`, 메뉴 단축키 | `#73767C` | `#73767C` | `text-secondary` |
| `@selectionBackground` / `@selectionInactiveBackground` | `#D0DFFE` / `#E9EAEE` | `#2A4371` / `#33353B` | `selectionBackground` = `selection-bg-active`, `selection-bg-inactive` |
| `Chromite.Tree.hoverBackground` | `#00000008` | `#FFFFFF10` | `Tree.hoverBackground` = `selection-bg-hovered` |
| `@accentColor`, 포커스, 기본 단추 | `#3871E1` | `#3871E1` | `control-brand-border`, `accent-brand-bg` |
| `Component.borderColor` | `#D1D3D9` | `#40434A` | `control-border` |
| `Separator.foreground`, `Chromite.dividerColor`, 탭 줄 아래 선, 그리드 세로선 | `#E9EAEE` | `#26282C` | `dialog-border`, `editor-border`, `Table.gridColor` |
| `Chromite.Table.horizontalLineColor` | `#F7F8F9` | `#212326` | `Table.stripeColor` = `dialog-bg-inline` |
| `PopupMenu.borderColor`, `Popup.borderColor` | `#D1D3D9` | `#33353B` | `Popup.borderColor` |
| `@menuBackground`(팝업) | `#FFFFFF` | `#26282C` | `popup-bg` |
| `ComboBox.background`(다크) | — | `#26282C` | `ComboBox.background` |
| `Button.toolbar.hoverBackground` / `pressedBackground` | `#00000012` / `#00000020` | `#FFFFFF17` / `#FFFFFF29` | `ActionButton.hoverBackground` / `pressedBackground` |
| 편집기 바탕·거터 / 현재 줄(다크, `SqlEditorDark.xml`) | (흰색 그대로) | `#191A1C` / `#212326` | `editor-bg`, `layer-0-bg-inline` |

그대로 둔 것: 컨트롤 arc 8(`Component.arc`, `Button.arc` 같은 값), 트리·목록·메뉴 선택 arc 8과 안쪽 여백, 행 높이 24, 트리 여백 4,12,4,12(모두 Islands와 같은 값), 결과 탭의 밑줄 모양.

구현:
- `IslandPanel`: 카드. 자식을 그린 뒤 네 모서리 바깥을 캔버스 색으로 앤티에일리어싱해 덮는다(불투명한 사각형 자식도 모서리가 매끈하다). 모양 클립은 앤티에일리어싱이 없어 쓰지 않았다.
  자식 하나만 다시 그릴 때도 모서리를 덮도록 카드를 그리기 시작점으로 둔다(`isPaintingOrigin`).
- `ToolWindow`가 도구 창(머리말 + 내용)을 카드로 감싸고, `MainFrame`이 문서 탭을 카드로 감싼다. 캔버스 색은 내용 패널·분할 창·상태 표시줄이 테마가 바뀔 때마다 다시 읽는다.
- 문서 탭 `IslandTabbedPaneUI`: 선택 탭은 알약(채움 + 1px 테두리, 포커스가 없으면 회색 알약), 마우스 올린 탭은 옅은 알약, 밑줄 없음. FlatLaf가 닫기 단추와 제목보다 먼저 그리는 탭 테두리 자리에서 그린다.
- 제목 표시줄: FlatLaf `TitlePane.unifiedBackground`를 끄고 `TitlePane.background`를 캔버스로 둔다(켜 두면 루트 패널 색 = 카드 색이 된다).

확인(Windows 11, 150%, 아이콘 팩 A): `D:\Tools\_scratch\swing-p1\islands-light.png`, `islands-dark.png`. 픽셀: 캔버스 `#E9EAEE`/`#26282C`(제목 표시줄·도구 막대·틈),
카드 `#FFFFFF`/`#191A1C`, 카드 사이 틈 6(9px), 모서리 반지름 10(15px)으로 매끈함, 선택 탭 알약 파랑 채움·테두리. 실행 중 라이트↔다크 전환 뒤에도 카드·캔버스·탭 색이 바뀌었다.

### P0 결과

| # | 항목 | 목표 | 측정 (라이트 / 다크) | 상태 |
|---|---|---|---|---|
| 1 | 바탕 계층 | chrome·panel `#F7F8FA`/`#2B2D30`, editor `#FFFFFF`/`#1E1F22` | 트리·도구 창·상태 표시줄 `#F7F8FA` / `#2B2D30`, 편집기·그리드·문서 탭 줄 `#FFFFFF` / `#1E1F22` | 완료. 다크 기반을 `FlatDarkLaf`로 바꿈 |
| 2 | 라이트 팔레트, 강조색 | accent `#3574F0` | 탭 밑줄·포커스 테두리·기본 단추 `#3574F0` | 완료 |
| 3 | 선택 | L `#D4E2FF`·비활성 `#DFE1E5`, D `#2E436E`·`#43454A`, 글자색 유지, arc 8, 여백 안쪽 | 트리 `#D4E2FF`·`#DFE1E5` / `#2E436E`·`#43454A`, 좌우 8 안쪽, 글자색 그대로 | 완료. 좌우 안쪽 여백은 12 대신 8(아래 "못 한 것") |
| 4 | 트리 치수 | 행 24, 들여쓰기 19, 여백 4,12,4,12, 두 테마 셰브런 | 행 24.0(36px), 셰브런 두 테마, 화살표 색 `#818594` / `#B4B8BF` | 완료 |
| 5 | 트리 아이콘 | 1px 선 16px, 중립 + 의미 강조색 | 아이콘 팩 2종(아래 "아이콘 팩"), 폴더는 펼치면 열린 폴더 | 완료 |
| 6 | 연결 상태 점 | 지름 6, 초록·노랑·빨강 | 지름 6 + 행 바탕색 1px 테두리. 연결됨 초록, 연결 실패 빨강(없는 폴더의 SQLite 파일, `x-broken2`) 확인 | 완료. 연결 중(노랑)은 잠깐이라 화면 확인은 못 함 |
| 7 | 글꼴 | Inter 13, JetBrains Mono 13, 한글 대체 | Inter·JetBrains Mono(번들). 트리·메뉴·Properties·대화상자·편집기·그리드의 한글이 맑은 고딕으로 대체되어 보임(□ 없음) | 완료. 편집기 줄 간격 1.2는 못 함 |
| 8 | 도구 창 머리말 | 40 + 1px 선, semibold, 아이콘 단추 | 40(+1, 61px), semibold 제목 왼쪽 12, 숨기기(—) 아이콘. Object Explorer는 머리말 아래 32 도구 막대(New Connection, Connect, Disconnect │ Filter, Refresh │ Collapse All) | 완료 |
| 9 | 주 도구 막대 | 아이콘 16/26, hover 바탕, New Query만 글자 | 단추 26, 막대 36, hover `#00000012` / `#FFFFFF16` | 완료 |
| 10 | 편집기 탭 | 40, 탭 줄 = 편집기 바탕, 밑줄 4 arc 4, 아이콘 16 | 40(60px), 탭 줄 `#FFFFFF` / `#1E1F22`, 밑줄 4(포커스 `#3574F0`, 아니면 `#A8ADBD`/`#5A5D63`), 아이콘: 저장한 파일은 SQL 파일, 새 쿼리는 공급자 로고 | 완료 |
| 11 | 쿼리 도구 막대 | ▶ Execute(초록 + 글자), ■ 아이콘, 콤보 28 arc 8 + 공급자 아이콘 | 콤보 28(42px), 막대 36 | 완료 |
| 12 | 라이트 SQL 색 | DataGrip Light | "SQL 구문 색" 표 그대로, 줄 번호 `#AEB3C2`/`#767A8A`, 현재 줄 `#F5F8FE`, 선택 `#A6D2FF` | 완료. 오류 표시는 글자색 밑줄(아래 "못 한 것") |
| 13 | 결과 그리드 | 편집기 바탕, 행 27, 머리글 28 왼쪽 정렬, 세로·가로 격자선, 옅은 선택 | 행 27(40.5px), 머리글 28(41px), 세로선 `#EBECF0`/`#393B40`, 가로선 `#F7F8FA`/`#2B2D30`, 선택 `#D4E2FF`/`#2E436E`, 편집기 글꼴 | 완료. 머리글 열 아이콘은 못 함 |
| 14 | 분할선 | 1px, 손잡이 없음 | 1px `#EBECF0`/`#393B40`, 끄는 영역 5 | 완료 |
| 15 | 컨트롤 모양 | arc 8, 높이 28, 포커스 2px | 입력·콤보·단추 28(42px), arc 8, 포커스 테두리 2px `#3574F0` | 완료 |

그 밖의 측정: 결과 탭 32(≈49px), 상태 표시줄 28(+1px 선), 아이콘 단추 26.

### P1 결과 (2026-11-06)

측정은 Islands 적용 뒤, 같은 방법(Windows 11, 150%, 창만 캡처, 1.5로 나눔). 캡처는 `D:\Tools\_scratch\swing-p1\`.

| 항목 | 목표 | 측정·확인 | 상태 |
|---|---|---|---|
| 그리드 머리글 잘림(버그) | 머리글 전체 | 원인은 가로 스크롤(행 번호 열은 스크롤하지 않음). 행 번호 클릭의 불필요한 가로 스크롤을 없애고 열 여백을 렌더러에서 계산. `AlbumId` 머리글 전체 보임(라이트·다크) | 완료 |
| 제목 표시줄 | 40, 창 단추 46×40, 제목 `text.secondary` | 60px = 40, 창 단추 46×40 | 완료 |
| 주 메뉴 | File, Edit, View, Query, Tools, Window, Help | 그대로. Edit(실행 취소·다시 실행·잘라내기·복사·붙여넣기·모두 선택·찾기·바꾸기), Tools(Options), Window(Reset Window Layout, 탭 목록) | 완료 |
| 메뉴 팝업 | 항목 24, 선택 arc 8·좌우 4, 모서리 8 | 항목 24(`@menuItemMargin` 3,8,4,8; Inter 13 한 줄이 17이라 4,8,4,8이면 25), 나머지는 디자인 적용 때 값 | 완료 |
| 탭 닫기 단추 | 활성·마우스 올린 탭에만 | 그대로, 가운데 단추로 닫기, 도구 설명에 경로·연결 | 완료 |
| F5 | Object Explorer = 새로 고침, 쿼리 창 = 실행 | 트리 초점에서 F5로 폴더를 다시 읽음, 쿼리는 실행되지 않음 | 완료 |
| 쿼리 상태 줄 | SSMS 내용 + 상태 아이콘, 결과 아래, 높이 28 | 상태 아이콘·서버·사용자·DB·시간·행, Results 카드 아래쪽(숨기면 편집기 아래), 28 + 1px 선. 바탕은 카드 바탕(Islands에서는 쿼리 창이 카드라 `bg.chrome` 대신) | 완료 |
| Properties | SSMS 속성 그리드 | 분류별·이름순, 분류 접기(셰브런 + semibold), 열 머리글 없음, 행 24, 가로선, 열 사이 1px, 설명 칸 | 완료 |
| 자동완성 목록 | 행 24, 오른쪽 형식 `text.secondary`, 선택 arc 8·좌우 4, 최대 12행 | 그대로(행 36px = 24) | 완료(맞는 글자 강조는 남김) |
| 스크롤바 | 폭 11, 막대 7, 트랙 = 바탕 | 그대로, 마우스 올리면 막대 9. 막대 색은 Islands `#80808059`/`#8080808C` | 완료 |

### P1·P2 중 함께 적용한 것

- 트리: 이름 뒤 보조 글자(`text.secondary`: 열 형식, 읽은 폴더의 객체 수, 연결의 `호스트:포트`), 마우스 올림 바탕 `#EDF3FF`/`#393B40`(FlatLaf에 키가 없어 트리가 직접 그림).
- 결과: 행 번호 열(그리드 바탕, 보조 글자, 왼쪽 정렬, 선택한 행의 번호도 선택색), NULL은 기울임 없이 보조 글자색, 편집 색(추가·삭제·변경)을 테마 키 `Chromite.Table.*Background`로 옮김, Messages 글꼴 `font.code`·오류 `status.error`.
- 편집기: 여백(거터) 구분선 없음, 짝 괄호·같은 이름 강조 색.
- 메뉴·목록·콤보 목록 선택 arc 8 + 좌우 4 안쪽, 팝업 모서리 8, 도구 설명 색, 메뉴 단축키 글자 `text.secondary`.
- Object Explorer Filter(SSMS): 객체 폴더에 이름 마스크(`%`가 없으면 "포함")를 건다. DBeaver 탐색기 필터(`DBSObjectFilter`)라 연결 설정에 저장된다. 빈 글자는 필터 해제.

### 아이콘 팩

- 두 팩을 넣고 View > Theme > Icon Style에서 고른다(실행 중 바로 바뀌고 저장됨, 설정 키 `ui.iconStyle`).
  - **A "DataGrip-style duo-tone"(기본)**: 1px 윤곽 + 옅은 채움, 중립 회색, 폴더·DB·루틴·외래 키는 파랑, 기본 키는 주황.
  - **B "SSMS-style color"**: Visual Studio 이미지 라이브러리풍 진한 색(갈색 폴더, 파란 원통, 금·은 키).
- 파일: `plugins/com.seizia.chromite.ui.swing/icons/ui/{duotone,color}/{light,dark}/<id>.svg` 49개씩(P1에서 Properties 도구 막대의 `categorized`, `sort-alpha` 추가). 생성기 `tools/branding/gen_ui_icons.py`(Python 표준 라이브러리만, 다시 만드는 방법은 파일 머리말).
  사용자가 고른 38개(색 토큰은 생성기의 `PAL`)에 도구 창·편집기용 9개(`new-connection`, `connect`, `disconnect`, `collapse-all`, `hide`, `close`, `results`, `messages`, `file-sql`)를 같은 규칙으로 더했다.
  원래 38개는 후보 파일과 바이트 단위로 같다.
- 모양은 Tabler Icons(MIT)를 16px 격자에 다시 그린 Chromite 그림이다. JetBrains·Microsoft 그림은 쓰지 않았다. Tabler 라이선스는 번들의 `icons/ui/LICENSE-tabler.txt`와 `NOTICE`에 있다.
- 배지 자리를 비우는 `<mask>`(view, mview, column-pk, column-fk, new-query, new-connection, export)는 JSVG 2.1이 그대로 그린다(JSVG로 따로 렌더링해 확인, 앱 화면의 기본 키 열 아이콘도 같음). 그래서 평평한 경로로 바꾸지 않았다.
- 사양과 다른 점: "색 바꾸기" 규칙(`currentColor` 한 파일, 테마별 파일 없음) 대신 팩×테마마다 색을 넣은 파일을 쓴다. 고른 두 팩이 듀오톤(선 + 채움 두 색)과 여러 색이라 한 색 `currentColor`로 표현할 수 없고,
  파일은 생성기 출력이라 색은 `PAL` 한곳에서 바뀐다. 비활성 아이콘은 `text.disabled`로 다시 칠하는 대신 투명도 0.4로 그린다(`FlatLaf.DisabledIconProvider`).
- 모델 아이콘 대응(`NodeIcons.UI_ICON_IDS`, 아이콘 위치로 대응): `DATABASE_DEFAULT`·`TREE_SERVER(S)` → `connection`, `TREE_DATABASE*` → `database`, `TREE_SCHEMA*` → `schema`, `TREE_FOLDER*` → `folder`,
  `TREE_TABLE*`·`TREE_PARTITION` → `table`, `TREE_VIEW*` → `view`, `TREE_COLUMN(S)`·`TREE_ATTRIBUTE`·`TREE_DERIVED_COLUMN`·`TYPE_*` → `column`(키 겹침 `OVER_KEY` → `column-pk`, `OVER_REFERENCE` → `column-fk`),
  `TREE_FOREIGN_KEY_COLUMN` → `column-fk`, `TREE_UNIQUE_KEY`·`TREE_KEY` → `unique`, `TREE_FOREIGN_KEY`·`TREE_REFERENCE`·`TREE_ASSOCIATION` → `foreign-key`, `TREE_CONSTRAINT` → `check`, `TREE_INDEX` → `index`,
  `TREE_FUNCTION` → `function`, `TREE_PROCEDURE` → `procedure`, `TREE_TRIGGER` → `trigger`, `TREE_SEQUENCE` → `sequence`, `TREE_DATA_TYPE` → `datatype`, `TREE_USER` → `user`, `TREE_USER_GROUP` → `role`, `STATUS_*` → `error`·`warning`·`info`.
  Object Explorer는 객체 종류로 더 나눈다(`NodeIcons.getNodeIcon`): 기본 키 제약 → `primary-key`, 고유 키 → `unique`, 외래 키 → `foreign-key`, 검사 제약 → `check`, 인덱스 → `index`,
  함수·프로시저(`DBSProcedureType`), 트리거, 시퀀스, 객체 폴더 → `folder`/`folder-open`(펼침).
  연결은 드라이버 로고(위 "공급자(DB 제품) 아이콘 정책" 그대로, 로고가 없으면 `connection`)에 상태 점을 붙인다. 대응이 없는 아이콘은 지금처럼 DBeaver 아이콘을 쓴다. 자동완성 목록·콤보도 같은 대응을 쓴다.

### 글꼴

- FlatLaf가 낸 글꼴 jar `com.formdev:flatlaf-fonts-inter` 4.1(Inter 4.1)과 `flatlaf-fonts-jetbrains-mono` 2.304(JetBrains Mono 2.304)를 FlatLaf와 같은 방식으로 넣었다:
  빌드할 때 Maven Central에서 받아 `lib/`에 복사(`pom.xml`), `Bundle-ClassPath`, 저장소에는 넣지 않음. 글꼴은 SIL OFL 1.1, jar 포장은 Apache 2.0. `NOTICE`에 적었다.
  - `flatlaf-fonts-inter-4.1.jar`: SHA-1 `5b22a28695d69d6865bceb206db1debb729e26ac`(Central `.sha1`과 같음), SHA-256 `053f7274d6489900e53d4ad397323440428733a41b7fe2f642daf3feb7c730e8`
  - `flatlaf-fonts-jetbrains-mono-2.304.jar`: SHA-1 `68c779e849aec16c69f69d4405fb1edea04e9b8f`(Central `.sha1`과 같음), SHA-256 `bc8e93479960b2a5e3b45fe724e16439a69f28cc43cac3a2b87b7f7f325fd3a5`
- 테마 properties: `defaultFont = 13 Inter`, `semibold.font = "Inter SemiBold"`, `monospaced.font = "JetBrains Mono"`. `ChromiteTheme`가 Look and Feel을 설치하기 전에 `installLazy()`로 등록한다.
  FlatLaf는 글꼴 이름을 `StyleContext.getFont`(합성 글꼴)로 만들기 때문에, Inter·JetBrains Mono에 없는 한글은 운영체제 글꼴(Windows: 맑은 고딕)로 대체된다.
- 편집기(RSyntaxTextArea)·그리드·Messages는 `monospaced.font`(JetBrains Mono 합성 글꼴)를 쓴다. 테마 XML에 `baseFont`를 쓰지 않은 이유: XML의 글꼴 이름은 합성 글꼴이 아니어서 한글이 □로 나온다.
- 확인: 트리 폴더 이름(`테이블`, `컬럼`, `인덱스`), 한글 테이블(`앨범_한글`), Properties 속성 이름, 메뉴, 대화상자, 편집기 글(`"앨범_한글" -- 한글 주석`), 그리드 머리글·값에 □ 없음(`x-korean-*.png`).
  macOS(Apple SD Gothic Neo)는 확인하지 못했다.

### Swing/FlatLaf로 못 한 것과 이유

| 항목 | 이유 | 대신 한 것 |
|---|---|---|
| 편집기 줄 간격 1.2 | RSyntaxTextArea는 행 높이를 글꼴 크기로만 정하고 줄 간격 설정이 없다 | 기본 행 높이 |
| 오류 빨간 물결 밑줄 | RSTA 토큰 스타일의 밑줄은 글자색 직선뿐이다 | 글자색 밑줄(바탕 칠 없음) |
| 트리 선택 좌우 안쪽 12 | FlatLaf 넓은 선택은 트리 왼쪽 끝부터 재고, 1단계 노드의 셰브런이 왼쪽 12 근처라 12면 셰브런이 선택 사각형 경계에 걸린다 | 안쪽 8(DataGrip 캡처의 선택 왼쪽 끝 ≈12와 4 차이) |
| 그리드 머리글 열 아이콘(PK/FK) | 결과 열이 키에 속하는지 알려면 원본 테이블 메타데이터를 읽어야 한다(결과 수신기에서 가능하지만 이번 범위가 아님) | 미룸 |
| 세로·가로 격자선 색 구분 | `JTable` 격자선은 한 색이다 | 세로선은 `Table.gridColor`, 가로선은 표가 직접 그림(`Chromite.Table.horizontalLineColor`) |
| 트리 hover | FlatLaf에 트리 hover 키가 없다 | 트리가 선택과 같은 둥근 사각형을 직접 그림 |
| 문서 탭 줄 바탕 | FlatLaf 탭 패널은 불투명하지 않고, 실행 중 테마를 바꾸면 스타일 `background`가 스크롤 탭 줄에 전달되지 않았다(다크 전환 뒤 탭만 `#2B2D30`) | 탭 패널을 불투명하게 하고, Look and Feel이 바뀔 때마다 배경을 다시 설정 |

### 남은 일 (P1·P2)

- ~~제목 표시줄 40·창 단추 46×40, 메뉴 항목 높이 24, 주 메뉴 Edit·Tools·Window, 탭 닫기 단추를 활성·마우스 올린 탭에만, 스크롤바 모양~~: 2026-11-06 완료("P1 결과").
- ~~쿼리 상태 줄의 SSMS 내용(서버, 사용자, DB)과 위치, Properties의 SSMS식 속성 그리드, 자동완성 목록 행 24, F5를 포커스에 따라 나누기~~: 2026-11-06 완료("P1 결과").
- 주 상태 표시줄 오른쪽 위젯(Ln/Col, 인코딩, 줄바꿈), 자동완성 맞는 글자 강조(`completion.match`), 결과를 쿼리 창 안에 배치.
- 연결 대화상자의 남은 SSMS 항목은 [swing-frontend.md](swing-frontend.md) "Connect 대화상자 (SSMS식) — 남긴 것".
- 그리드 머리글 열 아이콘, 연결 중 상태 점의 화면 확인, macOS 한글 대체 확인.
- Filter를 건 폴더는 새로 고침이 연결 노드로 올라가 다시 읽히므로 접힌다(기존 새로 고침과 같은 동작). 필터가 걸렸다는 표시("1 of 13")도 아직 없다.

## 설계 결정과 이유

- **배치는 SSMS, 모양은 DataGrip**: 사용자 결정(2026-11-06). SSMS 사용자가 익숙한 흐름을 유지하면서, DBeaver/Eclipse 느낌의 주원인인 색·밀도·아이콘을 DataGrip New UI 값으로 바꾼다.
- **토큰 소유**: JetBrains 테마 JSON을 FlatLaf `IntelliJTheme`로 직접 읽지 않고, Chromite 토큰(이 문서)에 New UI 팔레트 값을 옮겨 FlatLaf 키에 연결한다.
  IntelliJ 전용 키 다수(`EditorTabs.*`, `ToolWindow.*`)는 FlatLaf가 읽지 않으므로 직접 읽어도 대응이 필요하고, 토큰을 소유해야 나중에 값을 바꿀 때 한곳만 고친다.
  값의 출처는 Apache 2.0 저장소(intellij-community)이지만 색값만 가져오고 파일은 복사하지 않는다.
- **쿼리 창 도구 막대 위치**: SSMS는 주 창의 SQL Editor 도구 막대 하나를 쓰지만, Chromite는 편집기마다 연결·DB가 달라 보이는 자리에 두는 편이 낫고 이미 구현되어 있다.
  순서와 아이콘만 SSMS에 맞춘다. 되돌리기 쉬운 결정이다.
- **라이트 머리말**: New UI Light의 기본은 어두운 주 도구 막대지만, SSMS 라이트와 이어지고 밝은 화면에서 덜 튀도록 "Light with Light Header" 값(`MainToolbar.background` `#F7F8FA`)을 쓴다.
- **결과 탭 높이 32**: 결과가 쿼리 창 아래 붙는 SSMS 배치에서 40은 세로 공간을 많이 쓴다. 도구 창 머리말(40)과 구분되는 낮은 탭으로 둔다.
- **그리드 행 27**: DataGrip 측정값을 따른다. SSMS(≈20)보다 성기지만 모양 기준이 DataGrip이다. 촘촘한 모드(P2)에서 낮춘다.

## 적용 순서 (제안)

1. 테마 기반(P0 1·2·3·14·15): 다크 기반 LAF 교체, 색 토큰, 선택, 분할선, 컨트롤 모양. properties 파일과 `ChromiteDarkLaf` 상위 클래스만 바뀐다.
2. 글꼴(P0 7): 글꼴 jar 결정 뒤 `defaultFont`와 RSTA 글꼴.
3. 트리(P0 4·5·6): 치수, 아이콘 교체 표(`NodeIcons`), 상태 점. 아이콘 세트 선정이 먼저 필요하다.
4. 머리말·도구 막대·탭(P0 8·9·10·11): `ToolWindow`, `MainFrame`, `SqlEditorPanel`.
5. 편집기·그리드(P0 12·13): `SqlEditorLight.xml`, `ResultSetGrid`.
6. P1(상태 줄, Properties 그리드, 연결 대화상자 SSMS 배치, 자동완성 팝업, 메뉴 추가, F5 포커스 분기), 그다음 P2.

각 단계 뒤 라이트·다크 캡처를 이 문서의 표와 대조한다.

## 범위 밖 관찰 (고치지 않음)

- 탐색기 폴더 이름이 한국어(`테이블`, `뷰`, `테이블 트리거`)이고 메뉴·Properties 일부는 영어다(DBeaver 한국어 메시지 번들을 그대로 씀). 언어를 섞지 않는 정책이 필요하다.
- Properties의 일부 속성 이름도 한국어·영어가 섞여 있다(`테이블명`, `Catalog Name`). 같은 원인이다.

## 미룬 결정

- ~~아이콘 세트 선정~~: 2026-11-06 Tabler Icons(MIT) 모양을 바탕으로 한 Chromite 그림 2팩(duo-tone 기본, SSMS color 선택)으로 결정·적용("적용 현황 — 아이콘 팩").
- ~~Inter·JetBrains Mono 글꼴 jar~~: 2026-11-06 넣음("적용 현황 — 글꼴").
- 공급자 로고의 상표 사용 검토(공개 배포 전).
- 촘촘한 모드, 연결별 색(SSMS Color, DataGrip 데이터 소스 색). (Islands는 2026-11-06 적용)

## 참고 자료

- IntelliJ Platform UI Guidelines: https://plugins.jetbrains.com/docs/intellij/ui-guidelines-welcome.html
- IntelliJ 아이콘 지침: https://plugins.jetbrains.com/docs/intellij/icons-style.html
- IntelliJ 레이아웃 지침: https://plugins.jetbrains.com/docs/intellij/layout.html
- New UI 테마 파일: https://github.com/JetBrains/intellij-community/tree/master/platform/platform-resources/src/themes/expUI
- Islands 테마 파일: https://github.com/JetBrains/intellij-community/tree/master/platform/platform-resources/src/themes/islands
- New UI 아이콘: https://github.com/JetBrains/intellij-community/tree/master/platform/icons/src/expui
- `JBUI.java`: https://github.com/JetBrains/intellij-community/blob/master/platform/util/ui/src/com/intellij/util/ui/JBUI.java
- `SpacingConfiguration.kt`: https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/ui/dsl/builder/SpacingConfiguration.kt
- JetBrains New UI 도움말: https://www.jetbrains.com/help/idea/new-ui.html
- Islands 테마 기본 적용 발표(2025.3): https://blog.jetbrains.com/platform/2025/12/meet-the-islands-theme-the-new-default-look-for-jetbrains-ides/
- DataGrip Database Explorer: https://www.jetbrains.com/help/datagrip/database-explorer.html
- DataGrip 데이터 편집기: https://www.jetbrains.com/help/datagrip/data-editor-and-viewer.html
- SSMS 연결과 쿼리 빠른 시작: https://learn.microsoft.com/en-us/ssms/quickstarts/ssms-connect-query-sql-server
- SSMS 22 릴리스 노트: https://learn.microsoft.com/ssms/release-notes-22
- SSMS 21 발표: https://techcommunity.microsoft.com/t5/sql-server-blog/announcing-the-release-of-sql-server-management-studio-21/ba-p/4293314
- FlatLaf 속성 파일: https://github.com/JFormDesigner/FlatLaf/tree/main/flatlaf-core/src/main/resources/com/formdev/flatlaf

## 변경 이력

- 2026-11-06 최초 작성. 사용자 결정(배치·동작 = SSMS, 모양 = DataGrip New UI 다크·라이트)에 따라 요소별 비교, P0 목록, 디자인 토큰과 FlatLaf 키 대응,
  SQL 구문 색, 아이콘 요구 사항(100개 개념, 색 규칙, 공급자 로고 정책)을 정리.
- 2026-11-06 적용: P0 전체와 P1 일부를 Swing 프런트엔드에 적용하고 "적용 현황"(측정값, 아이콘 팩 2종과 모델 아이콘 대응, 글꼴과 한글 대체,
  Swing/FlatLaf로 못 한 것)을 추가. 결정한 아이콘 세트·글꼴을 "미룬 결정"에서 뺌.
- 2026-11-06 Islands 적용: 사용자 의견에 따라 기본 모양을 DataGrip Islands로 바꿈(캔버스 위 둥근 카드, 알약 탭, Islands 팔레트).
  "적용 현황 — Islands 적용" 절 추가, 배경과 결정·Islands 항목·`radius.island`·미룬 결정 갱신.
- 2026-11-06 P1 적용: "적용 현황 — P1 결과" 절 추가(그리드 머리글 원인, 제목 표시줄·메뉴, 탭, F5, 쿼리 상태 줄, Properties, 자동완성, 스크롤바), 남은 일 갱신,
  아이콘 팩 파일 수 갱신.
