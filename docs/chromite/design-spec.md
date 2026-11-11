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
| 구성 | 표준 도구 막대: **New Query**(아이콘 + 글자), 파일 열기·저장 아이콘 등. 아래에 SQL Editor 도구 막대 | 주 도구 막대(프로젝트·실행 위젯) | 글자 단추 4개 `New Connection…`, `New Query`, `Open File…`, `Save` [C] `MainFrame.java:335-344` | SSMS Standard 구성(2026-11-06 사용자 결정으로 확장): [New Query(아이콘 + 글자)] [새 연결] │ [열기] [저장] [모두 저장] │ [실행 취소] [다시 실행] │ [작업 모니터] │ [등록된 서버] [개체 탐색기] [쿼리 라이브러리] [속성](도구 창 켜고 끄기, 보이면 눌림) │ [찾기]. 글자는 New Query만 보이고 나머지는 아이콘 + 도구 설명(메뉴와 같은 단축키). 좁은 창에서는 넘치는 단추를 오른쪽 끝 » 메뉴로. 그 아래 둘째 줄에 SQL Editor 도구 막대(7절, 2026-11-07 사용자 결정) | P0 |
| 단추 모양 | — | 아이콘 16, 누를 수 있는 영역 ≈26×26, 마우스를 올리면 L `#00000012` / D `#FFFFFF16` 바탕, 누르면 L `#0000001D` / D `#FFFFFF26`, 모서리 arc ≈6 [T `ActionButton.*`][S] | FlatLaf 도구 막대 단추(글자, 여백 3) | `size.actionButton` 26, 아이콘 `size.icon` 16, 바탕 `bg.actionHover`/`bg.actionPressed`, arc 6, 단추 사이 2, 묶음 사이 구분선(높이 16, `border.subtle`) | P0 |
| 높이 | — | 40 [J] | ≈30 [S] | 36(제목 표시줄 40과 합쳐 SSMS의 두 줄 구성을 유지하되 높이를 줄임). 2026-11-07부터 도구 막대도 두 줄(Standard, SQL Editor)이고 둘 다 밀도 B 32 | P1 |

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
| 글꼴 | 고정폭 | JetBrains Mono 13, 줄 간격 1.2 | Consolas 13(RSyntaxTextArea 기본) [C] `FontUtil.getDefaultMonospacedFont` | `font.editor` Cascadia Mono 13(2026-11-06 사용자 결정, 아래 "편집기 글꼴과 SQL 색") | P0 |
| 바탕 | 흰색 / 다크 | L `#FFFFFF` / D `#1E1F22` [E `TEXT`] | 같음 [C] | `bg.editor` | — |
| 현재 줄 | 옅은 테두리 | L `#F5F8FE` / D `#26282E` [E `CARET_ROW_COLOR`] | L `#F2F6FC` / D `#26282E` [C] | `editor.caretRow` | P0(라이트) |
| 줄 번호 | 청록 `#2B91AF` | L `#AEB3C2`, 현재 줄 `#767A8A` / D `#4B5059`, 현재 줄 `#A1A3AB` [E] | L `#2B91AF`(SSMS) / D DataGrip 값 [C] | `editor.lineNumber`, `editor.lineNumberActive` | P0(라이트) |
| 여백(거터) | 노란 변경 막대 | 바탕 = 편집기 바탕, 구분선 없음 | 1px 구분선 `#E0E0E0`/`#393B40` [C] | 바탕 `bg.editor`, 구분선 없음(`gutterBorder` = 바탕색), 줄 번호 오른쪽 여백 12 | P1 |
| 선택 | 파랑 | L `#A6D2FF` / D `#214283` [E `SELECTION_BACKGROUND`, 다크는 부모 Darcula] | L `#ADD6FF` / D `#214283` [C] | `editor.selection` | P0(라이트) |
| 짝 괄호 | — | L 바탕 `#93D9D9` / D 바탕 `#43454A` 굵게 [E] | L `#DBE0CC` / D `#43454A` [C] | `editor.matchedBrace` | P1 |
| 같은 이름 강조 | — | L `#EDEBFC` / D `#373B39` [E `IDENTIFIER_UNDER_CARET_ATTRIBUTES`] | L `#D4D4D4` / D `#3B514D` [C] | `editor.identifierUnderCaret` | P1 |
| 구문 색 | 키워드 파랑, 함수 자홍, 문자열 빨강, 주석 초록 | 아래 "SQL 구문 색" 표 [E] | 다크는 DataGrip 값, 라이트는 SSMS 값 [C] `SqlEditorLight.xml` | SSMS 색(2026-11-06 사용자 결정), 아래 표 | P0(라이트) |

### 7. SQL Editor 도구 막대 (쿼리 창 도구 막대)

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 위치 | 주 창의 SQL Editor 도구 막대(활성 쿼리 창에 적용) | 콘솔마다 편집기 위 도구 막대 | 메인 창 둘째 줄의 SQL Editor 도구 막대(2026-11-07) [C] `SqlEditorToolBar` | **SSMS처럼 메인 창 둘째 줄 하나**(2026-11-07 사용자 결정, "결정" 참고). 활성 쿼리 창을 따르고(탭을 바꾸면 연결·DB 콤보와 단추 상태가 바뀜), 쿼리 창이 아니거나 문서가 없으면 보인 채 모두 꺼짐 | 완료 |
| 순서 | [DB 목록 ▾] [▶ Execute] [■] [✓ Parse] … [M] | [▶] [기록] [설정] … [Tx: Auto ▾] [■] … [스키마 ▾](오른쪽) [S] | [Execute] [Cancel] │ [연결 ▾] │ [스키마 ▾] [C] `SqlEditorPanel.java:380-426` | [▶ Execute] [■] │ [예상 계획] [실제 계획] │ [연결 ▾] │ [DB/스키마 ▾] │ [주석 처리] [주석 처리 제거] │ [내어쓰기] [들여쓰기] │ [SQL 서식 지정] │ [쿼리 기록]. SSMS처럼 Execute는 글자를 보이고, 중지는 아이콘만. 좁으면 콤보가 먼저 줄고(최소 100·100) 넘치는 단추는 » 메뉴로 | P0 |
| Execute 단추 | 초록 삼각형 + "Execute"(F5) | 실행 아이콘: L 선 `#208A3C` 채움 `#F2FCF3` / D 선 `#57965C` 채움 `#253627` [I `expui/run/run`] | 글자만 `Execute` [C] | 아이콘 `action.run`(초록) + 글자 `Execute`, 도구 설명 "Execute (F5)" | P0 |
| 중지 단추 | 빨간 사각형(실행 중에만 켜짐) | L 선 `#DB3B4B` 채움 `#FFF7F7` / D 선 `#DB5C5C` 채움 `#5E3838` [I `expui/run/stop`] | 글자 `Cancel` [C] | 아이콘 `action.stop`만, 실행 중에만 켜짐(비활성은 `text.disabled`색) | P0 |
| 콤보 | Available Databases ▾ | 높이 28, arc 8, 테두리 L `#C9CCD6` / D `#4E5157`, 안쪽 여백 1,9,1,6, 화살표 영역 28 [T `ComboBox.*`, `Component.arrowAreaWidth`] | FlatLaf 기본(arc 5) [F] | `size.control` 28, arc 8, 연결 콤보에 공급자 아이콘 16, DB/스키마 콤보에 스키마 아이콘 16 | P0 |
| 높이 | — | ≈36 [S] | 32(밀도 B) [S] | `size.toolbar.editor` 32(콤보 28 + 위아래 2). 메인 창 캔버스 위에 주 도구 막대와 같은 모양(투명, 구분선 없음) | P1 |

### 8. 결과(Results) / 메시지(Messages)

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 배치 | 결과 창은 **쿼리 창 안**(편집기 아래, 분할), 탭 Results / Messages, 상태 줄은 쿼리 창 맨 아래 [M] | Services 도구 창의 결과 탭 또는 편집기 안 결과 | 메인 창 아래의 공용 도구 창에 활성 편집기의 결과를 끼움 [C] `MainFrame.java:150-185` | 화면 결과는 지금처럼 편집기마다 따로이므로 동작은 같음. 배치를 SSMS처럼 쿼리 창 안으로 옮길지는 P1 검토(문서 탭과 함께 나뉘고, Ctrl+R로 숨김) | P1 |
| 탭 | 작은 탭, 아이콘(표, 메시지) + 글자 | 머리말 탭: 밑줄 3 [J `ToolWindow.HeaderTab.underlineHeight` 기본 3], 마우스 올림 L `#EBECF0` / D `#393B40` | FlatLaf 탭 32, 아이콘 없음 [S] | 높이 `size.tab.tool` 32, 아이콘 16(`results.grid`, `results.messages`) + 글자, 밑줄 3 `tab.underline` | P1 |
| 그리드 바탕 | 흰색(다크: 어두움) | 편집기 바탕(L `#FFFFFF` / D `#1E1F22`) [S][E] | L `#FFFFFF` / D `#36383A` [S] | `bg.editor` | P0 |
| 행 높이 | ≈20 | ≈27 [S](편집기 글꼴 13 + 위아래 여백) | 20(FlatLaf `Table.rowHeight`) [F] | `size.row.grid` 27 | P0 |
| 글꼴 | 비례 글꼴(설정 가능) | 편집기 글꼴(JetBrains Mono 13) [S] | `font.ui`(Segoe UI 12) | `font.grid` = `font.ui` Pretendard 12(2026-11-07 사용자 요청, "적용 현황 — 그리드 글꼴 Pretendard". 그전 목표는 `font.code`; 2026-11-12부터 `font.ui`는 운영체제 글꼴 13). 설정으로 바꿀 수 있게는 P2 | P0 |
| 머리글 | 이름 왼쪽 정렬 [M 결과 캡처] | 높이 ≈28, 바탕 = 그리드 바탕, 열 종류 아이콘 16(키 표시 포함) + 이름(왼쪽 정렬), 마우스 올리면 필터·정렬 아이콘 [S] | 높이 25, 이름 가운데 정렬, 아이콘 없음 [F][S] | `size.header.grid` 28, 왼쪽 정렬(숫자 열도 머리글은 왼쪽), 열 아이콘(PK/FK 배지 포함), 아래 1px `grid.line`. 2026-11-07 사용자 요청 10과 사용자 캡처(DataGrip, 아이콘이 늘 보임)로 바꿈: 열 아이콘 16 + 이름 + 오른쪽 끝에 필터(깔때기)·정렬 아이콘(각 `size.gridHeader.action` 16, 늘 보이는 흐린 `text.secondary`, 필터·정렬이 있으면 `accent`), 결과 위 WHERE 필터 줄은 없앰("적용 현황 — 열 필터", [swing-grid-column-filters.md](swing-grid-column-filters.md)) | P0 |
| 격자선 | 세로·가로 옅은 선 | 세로선이 가로선보다 조금 진함 [S] | FlatLaf 기본(표시 안 함) | 세로선 `grid.line`, 가로선 `grid.lineSubtle` 둘 다 표시 | P0 |
| 줄무늬 | 없음 | 없음(기본) [S] | 없음 | 없음 | — |
| 행 번호 열 | 맨 왼쪽 열, 왼쪽 정렬 [M] | 바탕 = 그리드 바탕, 글자 `text.secondary`, 폭 ≈40 [S] | 머리글 바탕, 오른쪽 정렬 [C] `ResultSetGrid.java:194-205` | 바탕 `bg.editor`, 글자 `text.secondary`, 왼쪽 여백 8, 오른쪽 1px `grid.line` | P1 |
| NULL | `NULL`(옅은 노란 바탕, SSMS 21부터 색 설정 가능) [M] | `<null>` 회색 | `NULL` 회색 기울임꼴 [C] | 글자 `NULL`(SSMS) + `text.secondary`, 기울임 없음 | P2 |
| 숫자 정렬 | 오른쪽 | 오른쪽 | 오른쪽(맞음) [C] | 유지 | — |
| 선택 | 진한 파랑 | 셀 선택 `selection.bg`, 글자색 유지 | FlatLaf 강조색 + 흰 글자 | `selection.bg`/`selection.inactiveBg`, 현재 셀 1px `focus.ring` 테두리. 행 번호는 선택해도 그리드 바탕(2026-11-08, 선택이 번호 열에서 끊겨 보이지 않게) | P0 |
| 메시지 글 | 고정폭, 오류 빨강 | 콘솔 출력: 일반 L `#080808` / D `#BCBEC4`, 오류 D `#F75464` [E `CONSOLE_*`] | 오류 `Actions.Red` [C] `QueryResultsView.java:117` | 글꼴 `font.code`, 오류 `status.error` | P1 |

### 9. 쿼리 상태 줄과 주 상태 표시줄

| 요소 | SSMS | DataGrip New UI | 현재 Chromite | 목표 | 우선 |
|---|---|---|---|---|---|
| 쿼리 상태 줄 | 쿼리 창 맨 아래: 상태 아이콘 + "Query executed successfully." │ 서버(버전) │ 사용자(spid) │ DB │ 00:00:00 │ N rows. 연결별 색 지정 가능 [M] | — | 편집기와 결과 사이: 상태, 경과 시간, 행 수 [C] `SqlEditorPanel.java:447-463` | SSMS 내용(서버, 사용자, DB 추가) + 상태 아이콘 16(`msg.*`), 위치는 결과 아래(쿼리 창 맨 아래). 높이 28, 바탕 `bg.chrome`, 위 1px `border.subtle`. 연결 색은 P2. 2026-11-07 커밋 방식 칸 추가(사용자 요청): DB 뒤에 점(`size.statusDot`, 자동 `status.success` / 수동 `status.error`) + 글자, 누르면 연결 설정의 Auto-commit으로 안내만 함([swing-frontend.md](swing-frontend.md) "자동 커밋 상태"). 2026-11-07 열린 트랜잭션 칸 추가: 커밋 방식 뒤에 `status.warning` 점 + 경과 시간·문장 수, 열린 트랜잭션이 있을 때만, 누르면 커밋/롤백 메뉴([idle-transactions.md](idle-transactions.md)). 2026-11-08 사용자 요청: 칸을 결과 아래에서 주 상태 표시줄로 옮김(아래 줄) | P1 |
| 주 상태 표시줄 | 왼쪽 "Ready", 오른쪽 Ln/Col/Ch/INS [M] | 위젯 글자 L `#6C707E` / D `#9DA0A8`, 마우스 올림 바탕 [T `StatusBar.Widget.*`] | 왼쪽 `N connections in workspace`만 [C] | 왼쪽 상태 문구, 오른쪽 `Ln, Col` │ 인코딩 │ 줄바꿈. 글자 `text.secondary`(2026-11-12부터 `text.primary`: 다크 테마 캔버스에서 3.2:1이라 잘 보이지 않음, 앱 변경 #55), 높이 28. 2026-11-08(사용자 결정): 쿼리 창이 활성이면 그 창의 쿼리 상태 칸(상태 문구 … `Ln, Col`)을 보이고 표시줄 전체를 그 연결 색으로 칠함(글자는 4.5:1이 안 되면 대비 색, 업데이트 진행 글자 포함). 쿼리 창이 아니면 작업 공간 연결 수. 업데이트 진행은 오른쪽 끝([swing-frontend.md](swing-frontend.md) "상태 표시줄의 쿼리 창 칸") | P1 |

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
| UI 글꼴 | Segoe UI | Inter 13(모든 OS) [JetBrains New UI 도움말], 한글은 운영체제 글꼴로 대체 | Segoe UI 12 | `font.ui` Pretendard 12(2026-11-07 사용자 결정: 한글·라틴을 한 글꼴로 Pretendard 13, "적용 현황 — Pretendard와 밀도 B". 같은 날 "한단계 줄인다"로 12, "적용 현황 — UI 글꼴 12"). 처음 목표는 Inter 13 + 한글 대체 Malgun Gothic이었다. FlatLaf 합성 글꼴(`defaultFont`가 대체 글꼴을 갖는 composite font)이어야 Pretendard에 없는 글자(한자·가나)가 □로 나오지 않음. **2026-11-12 운영체제 UI 글꼴 13**(Windows Segoe UI + 한글 맑은 고딕, 사용자 결정 "vscode와 같이", "적용 현황 — 운영체제 UI 글꼴 13") | P0 |
| 간격 단위 | — | 2·4·6·8·12·16·20 [J `SpacingConfiguration`, `Tree.border`, `List.border`] | 화면마다 다름(2, 3, 8, 14 등) [C] | "디자인 토큰"의 `space.*`만 씀 | P1 |
| 시작 탭(Welcome) | 시작 화면 | Welcome 화면(로고, 큰 단추) | 큰 로고 + 안내 글 [S] | 로고 64, 제목 `font.title`, "New Connection", "New Query", "Open File" 링크 단추, 최근 파일 목록 | P2 (2026-11-06 탭 대신 빈 문서 영역: "적용 현황 — 빈 문서 영역") |
| Islands | — | 2025.3부터 기본: 패널 사이 6px 틈, 패널 모서리 arc 20 [T `ManyIslands*`] | — | 기본 모양으로 적용("적용 현황 — Islands 적용") | 완료 |
| 촘촘한 모드 | — | Compact: 트리 22, 머리말 낮춤, 트리 여백 2,8 [T `*.compact`] | — | 기본 밀도를 B("조금 촘촘")로 바꿈(2026-11-07, "치수 토큰"). 더 촘촘한 C는 View > Compact Mode 전환(2026-11-08, "적용 현황 — 촘촘한 모드"): 치수가 테마 키에 모여 있어 값만 바꿈 | B·C 완료 |

## P0 상위 15개

1. **바탕 계층**: 다크 기반을 `FlatDarculaLaf`에서 `FlatDarkLaf`로 바꾸고 `bg.chrome`/`bg.panel` `#2B2D30`, `bg.editor` `#1E1F22`로 맞춘다(지금 트리·그리드 `#36383A`).
2. **라이트 팔레트**: `bg.chrome`/`bg.panel` `#F7F8FA`, `bg.editor` `#FFFFFF`, `accent` `#3574F0`(지금 `#F3F3F3`, 트리 흰색, `#0078D4`).
3. **선택 표시**: 옅은 선택색(L `#D4E2FF` / D `#2E436E`, 비활성 L `#DFE1E5` / D `#43454A`), 글자색 유지, arc 8, 트리 여백 안쪽(지금 진한 가로 전체 + 흰 글자, 다크 비활성 남색).
4. **트리 치수**: 행 24, 들여쓰기 19, 여백 4,12,4,12, 두 테마 셰브런(지금 19, 18, 다크 삼각형).
5. **트리 아이콘 교체**: DBeaver 아이콘 → 1px 선 16px 아이콘, 중립 회색 + 의미 강조색 규칙(지금 주황 채움 폴더·파란 표).
6. **연결 상태 표시**: PNG 체크 겹침 → 지름 6 상태 점(연결됨 초록, 연결 중 노랑, 오류 빨강).
7. **글꼴**: UI Inter 13, 편집기·그리드 JetBrains Mono 13(지금 Segoe UI 12, Consolas).
8. **도구 창 머리말**: 높이 40, semibold 제목, 아이콘 단추(⋮, 닫기 ×. 2026-11-11 전에는 숨기기 —, 앱 변경 #57)와 Object Explorer 아이콘 도구 막대(지금 28, 글자 `Refresh`, `×`).
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
| `grid.headerIcon` / `grid.headerIconActive` | 그리드 머리글의 필터·정렬 아이콘(평소 / 필터·정렬 있음) | = `text.secondary` / = `accent` | = `text.secondary` / = `accent` | [S] 사용자 캡처: 늘 보이는 흐린 깔때기·정렬 아이콘(2026-11-07) | `Chromite.Table.headerIconForeground`(`$Chromite.secondaryForeground`), `Chromite.Table.headerIconActiveForeground`(`@accentColor`) |
| `tooltip.bg` / `tooltip.fg` | 도구 설명 | `#27282E` / `#FFFFFF` | `#393B40` / `#F0F1F2` | `ToolTip.background`/`foreground` | `ToolTip.background`, `ToolTip.foreground` |
| `status.success` | 연결됨 점, 성공 | `#208A3C` | `#5FAD65` | 팔레트 L Green4, D Green7, 실행 아이콘 [I] | `Chromite.status.success` |
| `status.warning` | 경고, 연결 중 | `#A46704`(글자) / `#FFAF0F`(아이콘) | `#BA9752`(글자) / `#F2C55C`(아이콘) | `Label.warningForeground`(L Yellow1, D Yellow5), 팔레트 L Yellow4, D Yellow7 | `Chromite.status.warning`, `Component.warning.focusedBorderColor` |
| `status.error` | 오류 | `#DB3B4B` | `#DB5C5C` | `Label.errorForeground`(L Red4, D Red7) | `Chromite.status.error`, `Component.error.focusedBorderColor`, `Actions.Red` 대신 |
| `completion.match` | 자동완성 맞는 글자 | `#3574F0` | `#548AF7` | `CompletionPopup.matchForeground`(L Blue4, D Blue8) | `Chromite.completion.matchForeground` |

이 표는 처음(New UI) 설계 값이다. 적용 값은 "Islands 적용" 표이고, 글자 색(`text.secondary`, `text.disabled`, `status.*` 글자, `completion.match`)은
2026-11-12 대비를 올린 값이다("적용 현황 — 글자 선명도와 대비").

### SQL 구문 색 (RSyntaxTextArea 토큰)

2026-11-06 사용자 결정: 토큰 색은 **SSMS 기본 색**이다(처음 판의 DataGrip 값에서 바꿈). 숫자만 사용자가 연두색을 골랐다.
편집기 바탕·현재 줄·선택·줄 번호는 Islands/DataGrip 값 그대로다. 아래 값이 테마 XML(`SqlEditorLight.xml`, `SqlEditorDark.xml`)의 기본값이고,
사용자는 Tools > Options > Editor Colors에서 테마마다 바꿀 수 있다("편집기 글꼴과 SQL 색").

근거:
- [M] Microsoft Learn "Color coding in query editors"(SSMS, 2025-12-17판): 키워드 Blue, 시스템 함수 Magenta, Transact-SQL 문자열 Red, 주석 Dark green,
  연산자 Dark gray, 줄 번호 Teal. 이 문서는 색 이름만 준다. 16진 값은 Visual Studio·SSMS 글꼴 및 색 대화상자의 이름 붙은 색(Blue `#0000FF`,
  Magenta `#FF00FF`, Red `#FF0000`, Green `#008000`, Gray `#808080`)이다. 숫자는 SSMS 기본에서 일반 글자(검정)다.
- [P] 사용자의 SSMS 다크 테마 화면 사진(`D:\Tools\_scratch\editor-font\ref\ssms-dark-user-photo.jpg`, 모니터를 찍은 사진이라 색은 근사): 키워드 파랑,
  `AND`·`OR`·`IS`·`NULL`·비교 연산자·괄호·쉼표 회색, 문자열 빨강(연어색), `DATEDIFF`와 날짜 부분 `SECOND` 자홍-보라, 숫자 옅은 초록, 이름 흰색에 가까움.
  SSMS 다크의 T-SQL 기본 색은 공개된 값을 찾지 못했다(Microsoft Learn, SSMS 21 다크 모드 소개 글 확인). 그래서 다크 값은 사진의 색상과 Visual Studio Dark의
  일반 색(키워드 `#569CD6`, 주석 `#57A64A`, 연산자 `#B4B4B4`, 글자 `#DCDCDC`)에 맞추고 `#191A1C` 바탕에서 대비를 확인한 값이다.

| 항목 (Options 이름) | RSTA 토큰 | SSMS 분류 | Light | Dark | 대비(L/D) |
|---|---|---|---|---|---|
| Text (identifiers) | `IDENTIFIER`, `LITERAL_BACKQUOTE`, `PREPROCESSOR`(`[이름]`), `ANNOTATION`, `ERROR_*`(밑줄) | Plain Text | `#000000` | `#DCDCDC` | 21.0 / 12.7 |
| Keyword | `RESERVED_WORD`, `RESERVED_WORD_2`, `LITERAL_BOOLEAN` | SQL Keyword (Blue) | `#0000FF` | `#569CD6` | 8.6 / 5.9 |
| Operator | `OPERATOR`, `SEPARATOR`, 그리고 `ALL AND ANY BETWEEN EXISTS IN LIKE NOT OR SOME IS NULL`, 문장 부호 `, ; . ( )` | SQL Operator (Dark gray) | `#808080` | `#B4B4B4` | 4.0 / 8.4 |
| Function | `FUNCTION`: 방언의 함수, 날짜 함수의 날짜 부분(`SECOND` 등), `@@ROWCOUNT` 같은 시스템 변수 | SQL System Function (Magenta) | `#FF00FF` | `#D48CF5` | 3.1 / 7.4 |
| String | `LITERAL_CHAR`(`'...'`, `N'...'`의 `N` 포함), `LITERAL_STRING_DOUBLE_QUOTE` | SQL String (Red) | `#FF0000` | `#F07068` | 4.0 / 6.0 |
| Number | `LITERAL_NUMBER_*` | (SSMS 기본은 Plain Text) 사용자 결정 연두색 | `#669900` | `#B5E853` | 3.4 / 12.1 |
| Comment | `COMMENT_*` | Comment (Dark green) | `#008000` 기울임 | `#57A64A` 기울임 | 5.1 / 5.8 |
| Data type | `DATA_TYPE` | SQL Keyword | `#0000FF` | `#569CD6` | 8.6 / 5.9 |
| Variable / parameter | `VARIABLE`: `@이름`, `:이름`, `?` | Plain Text | `#000000` | `#DCDCDC` | 21.0 / 12.7 |
| Background / 커서 | `TEXT`, `CARET_COLOR` [E] | | `#FFFFFF` / `#000000` | `#191A1C` / `#CED0D6` | |
| Current line | `CARET_ROW_COLOR` [E] | | `#F5F8FE` | `#212326` | |
| Selection | `SELECTION_BACKGROUND` [E] | | `#A6D2FF` | `#214283` | |
| Line numbers / 현재 줄 번호 | `LINE_NUMBERS_COLOR`, `LINE_NUMBER_ON_CARET_ROW_COLOR` [E](2026-11-12 대비를 올림: "적용 현황 — 글자 선명도와 대비") | | `#6E727D` / `#3B3E45` (전 `#AEB3C2` / `#767A8A`) | `#808389` / `#D1D3D9` (전 `#4B5059` / `#A1A3AB`) | 4.8 / 10.7, 4.6 / 11.6 |
| 짝 괄호 | `MATCHED_BRACE_ATTRIBUTES` [E] | | 바탕 `#93D9D9` | 바탕 `#43454A` | |
| 같은 이름 | `IDENTIFIER_UNDER_CARET_ATTRIBUTES` [E] | | `#EDEBFC` | `#373B39` | |
| 오른쪽 여백 선 | `RIGHT_MARGIN_COLOR` [E] | | `#EBECF0` | `#393B40` | |

- 대비는 WCAG 대비율(바탕 `#FFFFFF` / `#191A1C`)이다. 라이트의 자홍(3.1)·빨강(4.0)·회색(4.0)은 SSMS 기본 그대로라 4.5보다 낮다(사용자 결정: SSMS 값을 그대로 씀).
- 숫자 연두색과 주석 초록의 구분: 라이트는 색상 80°(`#669900`)와 120°(`#008000`), 다크는 81°(`#B5E853`, 밝음)와 112°(`#57A64A`, 어두움)이고 주석은 기울임이다.
  `#ADFF2F` 같은 옅은 연두는 흰 바탕에서 대비가 1.2라 쓰지 않았다.
- 오류 표시(`ERROR_*`)는 글자색 밑줄이다(RSTA 밑줄은 직선뿐, "Swing/FlatLaf로 못 한 것").
- SSMS가 따로 칠하는 시스템 테이블·뷰(초록), 시스템 저장 프로시저(적갈색)와 표·열·스키마 이름 색(SWT 편집기의 semantic 색)은 의미 분석이 필요해서 아직 없다.

### 치수 토큰

기본 밀도는 **B "조금 촘촘"**이다(사용자 결정 2026-11-07, 비교 시안 `D:\Tools\_scratch\density-mock\`). 아래 값은 B이고, 괄호 안은 처음 값(A, DataGrip 기본)이다.
밀도를 정하는 값은 모두 테마 properties(`ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties`, 두 파일 같은 값)의 "Sizes" 절에 있다:
FlatLaf 키와, Chromite 컴포넌트가 `UiSizes`로 읽는 `Chromite.size.*` 키. 컴포넌트 코드에는 밀도 값을 두지 않는다.

**촘촘한 모드(C)**: View > Compact Mode(2026-11-08). 이 키들 중 바뀌는 것만 `ChromiteCompact.properties`에 두고 `FlatLaf.setExtraDefaults`로 덮어쓴다.
C 값(시안 C를 12px 글꼴에 맞춤): 트리 행 20(여백 2,8, 들여쓰기 5 + 10, 선택 안쪽 4), 그리드 행 22·머리글 24(셀 여백 0,6,2,6), 도구 창 머리말 30(여백 0,8,0,8),
주 도구 막대 28(단추 22 = 여백 3, 막대 여백 3,6,3,6), 도구 창 도구 막대 26(단추 22), 편집기 도구 막대 28(선택 상자 24 = 아이콘 16 + 여백 3,8,3,8), 문서 탭 32(알약 4), 도구 탭 26,
메뉴 항목 20(`@menuItemMargin` 2,8,2,8), 상태 표시줄·쿼리 상태 줄 24, Properties 행 20, 트리 보조 글자 앞 5, 결과 상태 막대 여백 0,6,0,6.
`Chromite.size.*` 값은 쓸 때 읽는다(크기 계산·그리기, 여백 테두리, 스타일의 `$키`). 만들 때 한 번 정하는 값(표 행 높이, 선택 상자 크기, 글자 단추 여백)은
UI가 바뀔 때 다시 정한다. 그래서 테마 전환처럼 실행 중에 바로 바뀐다.

| 토큰 | 값 | 근거 | FlatLaf 키 / 구현 |
|---|---|---|---|
| `size.row.tree` | 22 (24) | [T `Tree.rowHeight`][J] compact 22 | `Tree.rowHeight = 22`, `Tree.border = empty(3,10,3,10)`, `Tree.selectionInsets = 0,6,0,6` |
| `size.row.list` | 24 | [T `List.rowHeight`] | 목록 `setFixedCellHeight(24)` 또는 `List.cellMargins` |
| `size.row.menu` | 22 (24) | ≈ New UI 메뉴 | `@menuItemMargin = 3,8,3,8` (아이콘 칸 16 + 6. 12px 글자 줄 15가 아이콘 칸보다 낮다. 13px일 때는 `2,8,3,8` = 글자 줄 17 + 5) |
| `size.row.grid` | 24 (27) | [S] DataGrip 그리드 | `Table.rowHeight = 24`, `Table.cellMargins = 0,8,2,8`(글자를 1px 위로: 한글·내림 글자가 가운데로 보임) |
| `size.header.grid` | 26 (28) | [S] | `TableHeader.height = 26`, `TableHeader.cellMargins = 0,8,2,8` |
| `size.row.properties` | 22 (24) | Chromite 결정(트리 행과 같음) | `Chromite.size.propertyRow.height` |
| `size.header.toolWindow` | 34 (40) (+ 아래 선 1) | [J] 41 | `Chromite.size.toolWindowHeader.height`, 여백 `Chromite.size.toolWindowHeader.padding = 0,10,0,8` |
| `size.toolbar.toolWindow` | 28 (32) | 단추 24 + 위아래 2 | `Chromite.size.toolWindowToolBar.padding = 2,8,2,8`(도구 창, 작업 모니터·잠금·ERD 도구 막대) |
| `size.tab.editor` | 36 (40) | [T `TabbedPane.tabHeight`] | 문서 탭 스타일 `tabHeight: $Chromite.size.documentTab.height`, 선택 알약 위아래 `Chromite.size.documentTab.pillInset` 5 (6) |
| `size.tab.tool` | 28 (32) | Chromite 결정(쿼리 창 안 결과 탭은 낮게) | 결과·도구 창·세부 탭 스타일 `tabHeight: $Chromite.size.toolTab.height`(`UiSizes.TOOL_TABS_STYLE`) |
| `size.tabUnderline` | 4 (탭) / 3 (결과 탭) | [T `EditorTabs.underlineHeight`][J] | `TabbedPane.tabSelectionHeight`, `TabbedPane.tabSelectionArc = 4` |
| `size.titleBar` | 40 (메인 창), 대화상자 30 (2026-11-11 앱 변경 #56) | [J] 주 도구 막대 40 | 메인 창: `Chromite.size.mainTitleBar.height = 40`(루트 창 `JRootPane.titleBarHeight`), 그 밖의 창: `TitlePane.buttonSize = 46,30` |
| `size.toolbar.main` | 32 (36) | Chromite 결정(SSMS 두 줄 유지) | `Chromite.size.mainToolBar.padding = 4,8,4,8` |
| `size.toolbar.editor` | 32 (36) | [S] ≈36 | `Chromite.size.editorToolBar.padding = 2,8,2,8`(콤보 28 + 4; 메인 창 둘째 줄 SQL Editor 도구 막대, 테이블 디자이너). 연결·DB 선택 상자 여백 `Chromite.size.editorToolBar.comboBox.padding = @componentMargin`(촘촘한 모드에서 24로 낮춤) |
| `size.statusBar` | 26 (28) | ≈ | `Chromite.size.statusBar.height`. 활성 쿼리 창의 상태 칸도 이 높이다(2026-11-08 `size.statusLine.query` 삭제). 칸의 위아래 여백은 글자 줄 높이로 계산한다(칸이 줄보다 높아지지 않게) |
| `size.control` | 28 | [T `Button.minimumSize` 72,28, `TextField.minimumSize`, `ComboBox.minimumSize`] | 글자 줄 15(12px) + 위 6·아래 5 + 테두리 2: `@componentMargin = 6,8,5,8`(입력·콤보·스피너), `Button.margin = 6,14,5,14`. 13px일 때는 글자 줄 17 + 위 5·아래 4 |
| `size.button.minWidth` | 72 | [T] | `Button.minimumWidth = 72` |
| `size.actionButton` | 24 (26) | [S] | `Button.toolbar.margin = 4,4,4,4` (16 + 8). 글자 단추(새 쿼리, 실행)는 글자 줄이 아이콘(16)보다 높으면 그만큼 위 여백을 줄인다(`UiIcons.createToolButton`). 12px 글자 줄(15)은 아이콘보다 낮아 줄이지 않는다(13px일 때 17이라 1 줄였다) |
| `size.arrowArea` | 28 | [T `Component.arrowAreaWidth`] | 콤보 화살표 단추 폭 |
| `size.icon` | 16 | [I] | — |
| `size.icon.large` | 20 | [J] New UI 주 도구 막대·띠 아이콘 20 | 나중(주 도구 막대 큰 아이콘) |
| `size.statusDot` | 6 | [S] | 상태 점 지름 |
| `size.badge` | 8 | 아이콘 지침 "6~9px" | 겹침 배지 |
| `size.indent.tree` | 17 (19) | [S] | `Tree.leftChildIndent = 6`, `Tree.rightChildIndent = 11` |
| `size.gap.treeIcon` | 4 (6) | 시안 B | 트리 아이콘–글자 간격 `Chromite.size.tree.iconTextGap`. 보조 글자 앞과 연결 색 알약 앞뒤는 `Chromite.size.tree.secondaryGap` 6(알약 사양 그대로) |
| `size.padding.viewerStatus` | 1,6,1,6 | 시안 B(그대로, C에서 줄임) | `Chromite.size.viewerStatus.padding`. 필터 막대 여백 `size.padding.filterBar`(2,4,2,4)는 2026-11-07 필터 막대와 함께 지움 |
| `size.gridHeader.action`, `size.gridHeader.gap` | 16 / 4 | 아이콘 16, 아이콘–글자 간격(`size.gap.treeIcon`과 같은 4) | 결과 그리드 머리글의 필터·정렬 아이콘 칸(각 16×16), 열 아이콘–이름·이름–필터 아이콘 간격. `Chromite.size.gridHeader.action`, `Chromite.size.gridHeader.gap` |
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
| `font.ui` | 운영체제 UI 글꼴 13 보통(2026-11-12 사용자 결정 "vscode와 같이", 앱 변경 #67): Windows **Segoe UI** 13(한글은 맑은 고딕으로 대체), macOS 시스템 글꼴(San Francisco, 한글은 Apple SD Gothic Neo), Linux 데스크톱 글꼴. 이력: 2026-11-07 전 Inter 13, 2026-11-07 Pretendard 13 → 12, 2026-11-12 오전 UI 글꼴 선택(#43) | 모든 UI | `defaultFont = 13`, `[win]defaultFont = 13 "Segoe UI"`(FlatLaf가 합성 글꼴로 만듦, "적용 현황 — 운영체제 UI 글꼴 13") |
| `font.ui.semibold` | 영어 화면: Segoe UI Semibold 13(Windows). 한국어 화면: Segoe UI Bold 13 + 맑은 고딕 Bold(semibold 글꼴에 한글이 없어 BOLD로 바꿈) | 도구 창 제목, 섹션 제목, Properties 분류 | FlatLaf 기본 `semibold.font`(`[win]"Segoe UI Semibold"`, `[mac]"HelveticaNeue-Medium"`), 화면 언어의 글자가 없으면 `ChromiteTheme`이 `bold`로. 스타일 클래스 `semibold` |
| `font.ui.small` | 11 | 팝업 안내 줄, 배지 | 스타일 클래스 `small`: FlatLaf 기본 `small.font = -2`(`font.ui` − 2px; 13일 때 11로 측정. 이전에 적은 "−1px로 맞춤"은 구현과 달랐다) |
| `font.title` | 16 semibold(한국어 화면은 굵게) | 대화상자 머리말, 시작 탭 제목 | 스타일 클래스 `h3`: FlatLaf 기본 `h3.font = +3 $semibold.font`(`font.ui` + 3px; 13일 때 16) |
| `font.grid` | `font.ui`(Segoe UI 13) | 결과 그리드 칸·행 번호, 레코드 보기, 테이블 디자이너 표 | `Table.font`(FlatLaf 기본 = `defaultFont`). 2026-11-07 사용자 요청으로 `font.code`에서 바꿈("적용 현황 — 그리드 글꼴 Pretendard") |
| `font.code` | JetBrains Mono 13, 줄 간격 1.2 | 메시지, 값 패널, 오류 상세, DDL·스크립트 미리 보기(필터 막대는 2026-11-07 없앰) | `monospaced.font`(크기를 적지 않아 `defaultFont` 크기를 따른다: 2026-11-12 13), 스타일 클래스 `monospaced` |
| `font.editor` | Cascadia Mono 13(Options에서 바꿀 수 있음) | SQL 편집기, 줄 번호, DDL 탭 | `EditorFont`(2026-11-06, "편집기 글꼴과 SQL 색"). UI 글꼴과 따로라 2026-11-12에 바뀌지 않음 |

UI 글꼴은 설치된 운영체제 글꼴이라 번들하지 않는다(2026-11-12 번들 Pretendard 삭제, 사이트의 Pretendard 웹 글꼴은 그대로). JetBrains Mono는 SIL Open Font License 1.1이고
FlatLaf가 별도 jar(`flatlaf-fonts-jetbrains-mono`)로 낸다. 같은 방식의 Inter jar(`flatlaf-fonts-inter`)는 아직 번들에 들어 있지만 UI 글꼴로 쓰지 않는다.

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

`DBIcon` 열은 지금 탐색기 모델이 주는 아이콘(`plugins/com.seizia.chromite.model/src/com/seizia/chromite/model/DBIcon.java`)이다.
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
| 창·일반 | `action.more`(⋮), `action.close`(×, 도구 창 닫기(숨기기)도 이것. 2026-11-11 `action.hideToolWindow`(—) 삭제, 앱 변경 #57), `action.settings`, `action.themeLight`·`action.themeDark` |

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
| `Chromite.secondaryForeground`, 메뉴 단축키, `TitlePane.foreground` | `#5E6168`(2026-11-12 전 `#73767C`) | `#9A9DA4`(2026-11-12 전 `#73767C`) | `text-secondary`. 2026-11-12 Chromite 결정: 카드·캔버스에서 4.5:1 이상("적용 현황 — 글자 선명도와 대비") |
| `@disabledForeground`, `Label.disabledForeground`(비활성, 자리 표시 글, 안내 글) | `#7F8390`(2026-11-12 전 `#A8ADBD`) | `#777A81`(2026-11-12 전 `#5A5D63`) | Chromite 결정: 카드·캔버스에서 3:1 이상, text.secondary보다 흐림 |
| `Chromite.status.warningForeground`(경고 글자) | `#8A5A00` | = `Chromite.status.warning` `#F2C55C` | 2026-11-12: 밝은 테마의 아이콘 호박색 `#FFAF0F`는 흰 바탕에서 1.8:1이라 글자용을 따로 둠(위 색 토큰 표 `status.warning`의 "글자/아이콘" 구분) |
| `@selectionBackground` / `@selectionInactiveBackground` | `#D0DFFE` / `#E9EAEE` | `#2A4371` / `#33353B` | `selectionBackground` = `selection-bg-active`, `selection-bg-inactive` |
| `Chromite.Tree.hoverBackground` | `#00000008` | `#FFFFFF10` | `Tree.hoverBackground` = `selection-bg-hovered` |
| `Chromite.Tree.storageMemoryForeground` / `storageDiskForeground` / `storageVolatileForeground` | `#B3261E` / `#1A4FB0` / `#8F4F00` | `#FF9999` / `#94B8FF` / `#F0A858` | Chromite 결정: Object Explorer 테이블 저장 방식 글자색, 바탕·선택 바탕에서 4.5:1 이상([altibase.md](altibase.md)) |
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
- `ToolWindow`가 도구 창(머리말 + 내용)을 카드로 감싸고, `MainFrame`이 문서 탭을 카드로 감싼다(2026-11-06 도킹 뒤 도구 창 카드는 도킹 루트가 그린다: "적용 현황 — 도구 창 도킹"). 캔버스 색은 내용 패널·분할 창·상태 표시줄이 테마가 바뀔 때마다 다시 읽는다.
- 문서 탭 `IslandTabbedPaneUI`: 선택 탭은 알약(채움 + 1px 테두리, 포커스가 없으면 회색 알약), 마우스 올린 탭은 옅은 알약, 밑줄 없음. FlatLaf가 닫기 단추와 제목보다 먼저 그리는 탭 테두리 자리에서 그린다.
- 제목 표시줄: FlatLaf `TitlePane.unifiedBackground`를 끄고 `TitlePane.background`를 캔버스로 둔다(켜 두면 루트 패널 색 = 카드 색이 된다).

확인(Windows 11, 150%, 아이콘 팩 A): `D:\Tools\_scratch\swing-p1\islands-light.png`, `islands-dark.png`. 픽셀: 캔버스 `#E9EAEE`/`#26282C`(제목 표시줄·도구 막대·틈),
카드 `#FFFFFF`/`#191A1C`, 카드 사이 틈 6(9px), 모서리 반지름 10(15px)으로 매끈함, 선택 탭 알약 파랑 채움·테두리. 실행 중 라이트↔다크 전환 뒤에도 카드·캔버스·탭 색이 바뀌었다.

### 적용 현황 — 도구 창 도킹 (2026-11-06)

도구 창을 도킹 라이브러리(ModernDocking, [swing-frontend.md](swing-frontend.md) "도구 창 도킹")에 올린 뒤의 모양이다. Islands 값은 그대로 쓴다.

| 요소 | 값 |
|---|---|
| 카드 | 도구 창 하나 또는 탭 묶음 하나가 카드(`Chromite.island.background`, arc 20). 도킹 루트가 카드 바탕을 칠하고 모서리를 캔버스로 덮는다(`IslandPanel.paintCorners`) |
| 카드 사이 틈 | 라이브러리 분할 창의 분할선 = `SplitPane.dividerSize` 6, 선·손잡이 없음. 바깥 왼쪽·오른쪽 틈 `Chromite.island.gap` 6. 라이브러리 패널의 1px 테두리(`Component.borderColor`)와 마우스 올림 강조 테두리는 지움 |
| 머리말 | 높이 40 + 아래 1px `Separator.foreground`, semibold 제목 왼쪽 12, 오른쪽 [⋮ 옵션 `more`] [× 닫기 `close`: 동작은 숨기기. 2026-11-11 전에는 — `hide`, 앱 변경 #57] 아이콘 단추(26). 카드가 낮아도 높이 유지 |
| 옵션 메뉴 | Float, Dock, Auto Hide(체크), ─, Hide. 테마 팝업 메뉴 그대로 |
| 탭 묶음 | 아래쪽 탭(라이브러리 기본), `tabHeight 32`, 밑줄 3(Results 탭과 같음), 선택 탭의 머리말이 위에 보임 |
| 놓을 자리 표시 | `ModernDocking.overlayBackground` = 강조색 `#3871E1` 30%(`#3871E14D`), 라이트·다크 같음 |
| 도킹 손잡이 | 바탕 `ModernDocking.handleBackground` = 카드 바탕, 선·마우스 올림 `ModernDocking.handleForeground` = `@accentColor` |
| 자동 숨기기 막대 | 창 가장자리 캔버스 위 세로·가로 막대(라이브러리 단추, 테마 단추 모양) |
| 떠 있는 창 | 테마 제목 표시줄(제목 = 들어 있는 도구 창 이름), 바탕 캔버스. 카드 모서리는 둥글게 하지 않음 |
| 문서 영역 | 그대로(문서 탭 카드 + Results 카드, `IslandPanel`) |

확인: [swing-frontend.md](swing-frontend.md) "도구 창 도킹 — 확인"의 캡처(라이트·다크, 한국어·영어).

### 적용 현황 — 빈 문서 영역 (2026-11-06)

문서가 없을 때 문서 카드에 보이는 빈 화면(Welcome 탭 대체, [swing-frontend.md](swing-frontend.md) "빈 문서 영역").

| 요소 | 값 |
|---|---|
| 바탕 | 편집기 바탕 `Chromite.editorBackground`(L `#FFFFFF` / D `#191A1C`), 로고 바탕 상자 없음 |
| 로고 | 가로 잠금 로고 SVG, 라이트 = 남색 `#18253B` 글자, 다크 = 흰 글자·회색 부제 `#B9BCC6`. 가운데, 최대 너비 420, 좁으면 양쪽 32 여백을 두고 줄임 |
| 안내 글 | 로고 아래 24, 줄 사이 4, `text.secondary` `#73767C`, FlatLaf `small` |
| 단축키 | 안내 아래 20, 이름(보조 글자, 오른쪽 맞춤) │ 단축키(글자색, 왼쪽 맞춤), 사이 16 |

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
| 8 | 도구 창 머리말 | 40 + 1px 선, semibold, 아이콘 단추 | 40(+1, 61px), semibold 제목 왼쪽 12, 닫기(×) 아이콘(2026-11-11 전에는 숨기기 —, 앱 변경 #57). Object Explorer는 머리말 아래 32 도구 막대(New Connection, Connect, Disconnect │ Filter, Refresh │ Collapse All) | 완료 |
| 9 | 주 도구 막대 | 아이콘 16/26, hover 바탕, New Query만 글자 | 단추 26, 막대 36, hover `#00000012` / `#FFFFFF16`. 2026-11-06 SSMS Standard 배치로 확장(단추 15개, 넘침 메뉴, [swing-frontend.md](swing-frontend.md) "도구 막대") | 완료 |
| 10 | 편집기 탭 | 40, 탭 줄 = 편집기 바탕, 밑줄 4 arc 4, 아이콘 16 | 40(60px), 탭 줄 `#FFFFFF` / `#1E1F22`, 밑줄 4(포커스 `#3574F0`, 아니면 `#A8ADBD`/`#5A5D63`), 아이콘: 저장한 파일은 SQL 파일, 새 쿼리는 공급자 로고 | 완료 |
| 11 | 쿼리 도구 막대 | ▶ Execute(초록 + 글자), ■ 아이콘, 콤보 28 arc 8 + 공급자 아이콘 | 콤보 28(42px), 막대 36. 2026-11-06 주석·들여쓰기·서식·쿼리 기록 단추 추가(높이 그대로). 2026-11-07 메인 창 둘째 줄의 SQL Editor 도구 막대로 옮김(사용자 결정) | 완료 |
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
- 결과: 행 번호 열(그리드 바탕, 보조 글자, 왼쪽 정렬, 선택한 행의 번호도 선택색 — 2026-11-08부터 선택색 없음, swing-result-viewer.md "표 편의 기능"), NULL은 기울임 없이 보조 글자색, 편집 색(추가·삭제·변경)을 테마 키 `Chromite.Table.*Background`로 옮김, Messages 글꼴 `font.code`·오류 `status.error`.
- 편집기: 여백(거터) 구분선 없음, 짝 괄호·같은 이름 강조 색.
- 메뉴·목록·콤보 목록 선택 arc 8 + 좌우 4 안쪽, 팝업 모서리 8, 도구 설명 색, 메뉴 단축키 글자 `text.secondary`.
- Object Explorer Filter(SSMS): 객체 폴더에 이름 마스크(`%`가 없으면 "포함")를 건다. DBeaver 탐색기 필터(`DBSObjectFilter`)라 연결 설정에 저장된다. 빈 글자는 필터 해제.

### 아이콘 팩

- 두 팩을 넣고 View > Theme > Icon Style에서 고른다(실행 중 바로 바뀌고 저장됨, 설정 키 `ui.iconStyle`).
  - **A "Duo-tone"(기본, DataGrip풍)**: 1px 윤곽 + 옅은 채움, 중립 회색, 폴더·DB·루틴·외래 키는 파랑, 기본 키는 주황.
  - **B "Full color"(SSMS풍)**: Visual Studio 이미지 라이브러리풍 진한 색(갈색 폴더, 파란 원통, 금·은 키).
- 파일: `plugins/com.seizia.chromite.ui.swing/icons/ui/{duotone,color}/{light,dark}/<id>.svg` 97개씩(2026-11-11 두 트리 도구 막대의 펼치기 `expand-all` 추가, [swing-registered-servers.md](swing-registered-servers.md) "펼치기 단추". 2026-11-08 Object Explorer Link with Editor의 `link` 추가, [swing-explorer-productivity.md](swing-explorer-productivity.md). P1에서 Properties 도구 막대의 `categorized`, `sort-alpha`, Registered Servers 도구 막대의 `new-group`, SQL 편집기 확장에서 실행 계획 `plan`·쿼리 기록 `history`, Query Library에서 `library`·`library-query`·`new-folder`·`schedule`, ER 다이어그램에서 `diagram`·`zoom-in`·`zoom-out`·`zoom-fit`·`layout`, 도구 막대 확장에서 `save-all`·`undo`·`redo`·`activity-monitor`·`registered-servers`·`object-explorer`·`properties`·`comment`·`uncomment`·`indent`·`outdent`·`format`·`overflow`, 실행 계획 세 보기에서 연산자 `plan-*` 17개와 `copy` 추가(찾기·쿼리 라이브러리·쿼리 기록은 기존 `search`·`library`·`history`를 씀), [swing-sql-editor-plus.md](swing-sql-editor-plus.md), [swing-query-library.md](swing-query-library.md), [swing-erd.md](swing-erd.md), [swing-frontend.md](swing-frontend.md) "도구 막대"). 생성기 `tools/branding/gen_ui_icons.py`(Python 표준 라이브러리만, 다시 만드는 방법은 파일 머리말).
  사용자가 고른 38개(색 토큰은 생성기의 `PAL`)에 도구 창·편집기용 9개(`new-connection`, `connect`, `disconnect`, `collapse-all`, `hide`(2026-11-11 삭제, 앱 변경 #57), `close`, `results`, `messages`, `file-sql`)를 같은 규칙으로 더했다.
  원래 38개는 후보 파일과 바이트 단위로 같다.
  2026-11-12 Options 화면용 4개 추가: `palette`(편집기 색), 테마 단추 `theme-system`(화면), `theme-light`(해), `theme-dark`(달)
  ([swing-options.md](swing-options.md) "옵션 화면 모양과 저장 (#59)").
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
- 테마 properties: `defaultFont = 13 Inter`, `semibold.font = "Inter SemiBold"`, `monospaced.font = "JetBrains Mono"`. (2026-11-07 UI 글꼴은 Pretendard로 바뀜: "적용 현황 — Pretendard와 밀도 B") `ChromiteTheme`가 Look and Feel을 설치하기 전에 `installLazy()`로 등록한다.
  FlatLaf는 글꼴 이름을 `StyleContext.getFont`(합성 글꼴)로 만들기 때문에, Inter·JetBrains Mono에 없는 한글은 운영체제 글꼴(Windows: 맑은 고딕)로 대체된다.
- 그리드·Messages는 `monospaced.font`(JetBrains Mono 합성 글꼴)를 쓴다(2026-11-07부터 그리드는 UI 글꼴: "적용 현황 — 그리드 글꼴 Pretendard"). 편집기(RSyntaxTextArea)는 2026-11-06부터 `font.editor`(Cascadia Mono, "편집기 글꼴과 SQL 색")다.
  테마 XML에 `baseFont`를 쓰지 않은 이유: XML의 글꼴 이름은 합성 글꼴이 아니어서 한글이 □로 나온다.
- 확인: 트리 폴더 이름(`테이블`, `컬럼`, `인덱스`), 한글 테이블(`앨범_한글`), Properties 속성 이름, 메뉴, 대화상자, 편집기 글(`"앨범_한글" -- 한글 주석`), 그리드 머리글·값에 □ 없음(`x-korean-*.png`).
  macOS(Apple SD Gothic Neo)는 확인하지 못했다.

### 편집기 글꼴과 SQL 색 (2026-11-06)

사용자 결정(2026-11-06):
- **편집기 글꼴은 Cascadia Mono 13**이고 Chromite에 넣는다. 처음에는 Lucida Sans Typewriter를 골랐다가 견본을 보고 바꿨다.
  Lucida Sans Typewriter는 Microsoft Office 등과 함께 설치되는 상용 글꼴이라(Windows·macOS 기본 글꼴이 아님) 넣지 않고, 설치된 컴퓨터에서는 Options에서 고를 수 있다.
- **구문 색은 SSMS 기본 색**(키워드 파랑, 시스템 함수 자홍, 문자열 빨강, 주석 초록, 연산자 회색), 숫자는 연두색. 다크는 사용자의 SSMS 다크 화면 사진에 맞춘다(위 "SQL 구문 색").
- 사용자가 색을 바꿀 수 있어야 한다(SWT 제품의 설정 > Colors and Fonts > SQL Editor에 해당, `plugins/com.seizia.chromite.ui.editors.sql/plugin.xml`의 `org.eclipse.ui.themes` 색 16개).

#### 글꼴

| 항목 | 내용 |
|---|---|
| 기본 글꼴 | Cascadia Mono 2407.24, 크기 13(Look and Feel 글꼴처럼 `UIScale`로 배율 적용) |
| 넣은 파일 | `plugins/com.seizia.chromite.ui.swing/fonts/cascadia-mono/`: `CascadiaMono-Regular.ttf`, `-Bold`, `-Italic`, `-BoldItalic`(정적 TTF), `OFL.txt` |
| 출처 | Microsoft 공식 릴리스 `CascadiaCode-2407.24.zip`(https://github.com/microsoft/cascadia-code/releases/tag/v2407.24, SHA-256 `e67a68ee3386db63f48b9054bd196ea752bc6a4ebb4df35adce6733da50c8474`)의 `ttf/static`. 바꾸지 않은 파일이다 |
| 파일 SHA-256 | Regular `06520d032ec274fa5040b22c6f4a1d829081b24ba40b2da56dae89bf10c7b481`, Bold `b22cb603ed23cac36e8444846e1841caca21719a5e852780a8d37ae7a49b0a36`, Italic `8d98a33e7617c14dd1d200b4900bc6c2d5d7165abb5c09e9d2fd51c38ce9c9ff`, BoldItalic `ecaeab55e6334408f8ff1fde78a99ddaeb3bb7fad4fcd177df8475c88a7d240f` |
| 라이선스 | SIL Open Font License 1.1(Reserved Font Name "Cascadia Code"). 재배포 허용, 파일을 바꾸지 않았으므로 이름도 그대로다. `NOTICE`와 번들의 `fonts/cascadia-mono/OFL.txt`(저장소 `LICENSE`, 태그 v2407.24) |
| 저장소에 넣은 이유 | Inter·JetBrains Mono처럼 빌드 때 받으려 했으나 Cascadia는 Maven Central에 TTF 산출물이 없고, GitHub 릴리스는 150 MB zip 하나뿐이다. 빌드마다 150 MB를 받는 대신 필요한 TTF 4개(약 2 MB)를 넣었다 |
| 가변 글꼴을 쓰지 않은 이유 | Java는 가변 글꼴의 축(굵기)을 고르지 못하고 기본 인스턴스만 그린다. 굵게·기울임이 진짜 글꼴로 나오도록 정적 TTF를 쓴다 |
| 등록 | `EditorFont.installLazy()`(`ChromiteTheme.install`에서 Inter·JetBrains Mono와 함께): FlatLaf `FontUtils.registerFontFamilyLoader`로 처음 쓸 때 `FontUtils.installFont`(=`Font.createFont` + `GraphicsEnvironment.registerFont`) |
| 대체 순서 | 저장한 글꼴(이 컴퓨터에 있으면) → Cascadia Mono(번들) → JetBrains Mono(번들). 운영체제에 같은 이름의 글꼴이 있으면 Java가 등록을 거절하고 운영체제 글꼴을 쓴다(Windows 11은 가변 글꼴 `C:\Windows\Fonts\CascadiaMono.ttf`를 가지고 있어 그 글꼴의 기본 인스턴스를 쓰고, 기울임은 합성 기울임이다) |
| 한글 | `FontUtils.getCompositeFont`(= `StyleContext.getFont`, 합성 글꼴). 이 PC(JBR 25, 한국어 Windows 11)에서 합성 글꼴의 대체 칸은 Arial, **Malgun Gothic**, Wingdings, Symbol, … 순이다(`sun.font.CompositeFont` 칸 확인). macOS는 JDK의 대체 목록(Apple SD Gothic Neo)이며 확인하지 못했다 |
| 행 높이 | RSyntaxTextArea는 행 높이를 글꼴에서 한 번 정하고 모든 줄에 같게 쓴다. 한글이 있는 줄도 높이가 같다(확인 아래) |
| 쓰는 곳 | SQL 편집기, 줄 번호, DDL 탭, Options 미리 보기. 결과 그리드·Messages는 `font.code`(JetBrains Mono) 그대로(2026-11-07부터 결과 그리드는 `font.grid` = UI 글꼴) |
| 설정 | Tools > Options > Editor: Family(설치된 고정폭 글꼴 + 번들 글꼴, 기본 Cascadia Mono), Size(6–72, 기본 13), 한글이 든 미리 보기. 키 `sqlEditor.font.family`, `sqlEditor.font.size` |

#### 단어 분류 (`SqlTokenClassifier`)

RSyntaxTextArea의 SQL 토크나이저(`SQLTokenMaker`, 4.0.1)를 그대로 쓰면 색이 틀린다. 확인한 토큰(예문을 토크나이저에 넣은 결과):
`COUNT`·`SUM`·`VARCHAR`는 예약어(`RESERVED_WORD`), `UPPER`·`AVG`·`LOWER`만 함수, `COALESCE`·`GETDATE`·`ISNULL`·`OVER`·`TRUE`는 일반 이름,
`,`·`;`는 이름, `a.Title`의 `.`은 실수(`LITERAL_NUMBER_FLOAT`), `@var`는 `@` + 예약어 `var`, `:p`는 이름 하나.
그래서 토크나이저는 문자열·주석·숫자 경계에만 쓰고, 줄마다 받은 토큰의 종류를 고친다.

- 방언: 편집기 연결이 연결되어 있으면 데이터 소스의 방언, 아니면 드라이버의 스크립트 방언, 연결이 없으면 표준 SQL(`BasicSQLDialect`).
  SWT 편집기(`SQLRuleManager`, `SQLWordRule`)와 같은 규칙: 예약어 중 FUNCTION·OTHER는 함수, KEYWORD는 키워드, TYPE은 자료형, 연결되어 있으면 자료형·함수 목록을 더함.
  함수 이름은 뒤에 `(`가 오거나 키워드가 아니면 함수(`LEFT JOIN`의 LEFT는 키워드). 방언이 모르는 단어는 토크나이저의 분류를 둔다.
  편집기가 연결을 바꾸거나 연결·실행한 뒤 문서를 다시 칠한다. DDL 탭은 객체의 연결 방언을 쓴다.
- SSMS 규칙: T-SQL 논리 연산자(Microsoft Learn "Logical operators (Transact-SQL)": `ALL AND ANY BETWEEN EXISTS IN LIKE NOT OR SOME`)와 `IS`, `NULL`은 연산자 색.
  날짜 함수(`DATEADD DATEDIFF DATEDIFF_BIG DATENAME DATEPART DATETRUNC DATE_BUCKET`)와 그 첫 인수의 날짜 부분(`year`…`isoww`)은 함수 색(사진의 `DATEDIFF(SECOND, …)`).
  `N'…'`의 `N`은 문자열, `@이름`·`:이름`·`?`는 변수, `@@ROWCOUNT` 같은 시스템 변수는 함수 색, 문장 부호는 연산자 색, `a.Count`처럼 한정자 뒤 단어는 이름.
- 이 규칙은 연결의 DBMS와 관계없이 적용한다(편집기 전체가 SSMS 방식이다).

#### Editor Colors 탭

Tools > Options > Editor Colors: 테마(Light/Dark) 선택, 항목 목록(색 견본), Color…(`JColorChooser`), Bold, Italic, Reset to Defaults(SSMS 기본 색으로 되돌림), 미리 보기(한글·변수·날짜 부분 포함, 선택·현재 줄 보임).
항목은 토크나이저가 실제로 내는 것만 둔다: Text, Keyword, Operator, Function, String, Number, Comment, Data type, Variable/parameter, Background, Current line, Selection,
Line numbers, Current line number. 기본값은 테마 XML이고 바꾼 항목만 `sqlEditor.color.<light|dark>.<항목>` = `rrggbb[,bold][,italic]`로 저장한다.
OK를 누르면 열린 모든 편집기·DDL 탭에 바로 적용한다(`SqlTextArea.applyThemeToAll`). 배경을 바꾸면 거터 바탕·테두리도 같은 색이다.

SWT 색(`plugin.xml` 1360–1493행)과의 대응: keyword → Keyword, datatype → Data type, function → Function, string → String, number → Number, comment → Comment,
delimiter → Operator(SSMS처럼 `;`도 연산자 색), sqlVariable·parameter → Variable/parameter, text.foreground·text.background → Text·Background.
table, table.alias, column, column.derived, schema, composite.field, semanticError(의미 분석 색)와 command(`@set` 같은 클라이언트 명령)는
Swing 편집기에 그 분류가 없어 넣지 않았다(미룸). disabled.background는 Swing 편집기에 비활성 상태가 없어 넣지 않았다.

#### 확인

Windows 11, 화면 배율 150%, JBR 25, 작업 공간 사본 `D:\Tools\_smoke\swing-font`(연결 `Chinook Copy (SQLite)`), 창만 캡처(`shot.ps1`). 캡처는 `D:\Tools\_scratch\editor-font\`.

- 시작 로그: `SQL editor font: Cascadia Mono 13 (font Cascadia Mono Regular, requested Cascadia Mono)`.
- 예문(`SELECT a.AlbumId, COUNT(*) AS cnt, UPPER(a.Title) -- 한글 주석` / `FROM Album a` / `WHERE a.Title LIKE N'%한글%' AND a.AlbumId > 42.5` / `GROUP BY a.AlbumId;`)을
  붙여 넣고 글자 픽셀의 가장 많은 색을 읽었다(`light-editor2-0.png`, `dark-editor-0.png`, 확대 `light-editor-zoom.png`, `dark-editor-zoom.png`):

  | 토큰 | Light | Dark |
  |---|---|---|
  | `SELECT` | `#0000FF` | `#569CD6` |
  | `COUNT`, `UPPER` | `#FF00FF` | `#D48CF5` |
  | `N'%한글%'` | `#FF0000` | `#F07068` |
  | `42.5` | `#669900` | `#B5E853` |
  | `-- 한글 주석`(기울임) | `#008000` | `#57A64A` |
  | `LIKE`, `AND`, `,` | `#808080` | `#B4B4B4` |
  | `AlbumId` | `#000000` | `#DCDCDC` |

  한글은 □ 없이 맑은 고딕으로 보이고, 한글이 있는 1·3행과 없는 2·4행의 줄 간격이 같다(27px, 150%).
- 사용자 SSMS 사진의 문장을 다크 편집기에 넣어 나란히 비교(`compare-ssms-dark.png`): 키워드·연산자 단어·문자열·`DATEDIFF(SECOND, …)`·숫자 색이 사진과 같은 분류로 칠해진다.
  다른 점: 사진에서 파란 `NOLOCK`(테이블 힌트)은 SQLite 연결의 방언에 없는 단어라 글자색이다.
- Options(`opt-dark-editor-0.png`, `opt-dark-colors-0.png`, `opt-light-editor-0.png`, `opt-light-colors-0.png`): Editor 탭의 글꼴 목록에서 Lucida Sans Typewriter를
  고르면 미리 보기가 바로 바뀐다(`opt-light-editor-lucida-0.png`, 취소함). Editor Colors에서 Keyword를 Bold로 하고 OK → 열린 편집기의 키워드가 바로 굵게 바뀌고(`dark-editor-keyword-bold-crop.png`)
  설정 파일에 `sqlEditor.color.dark.keyword=569cd6,bold`만 저장됨. Reset to Defaults + OK → 그 키가 지워짐. Options에서 테마를 Light로 바꾸면 편집기 색도 라이트 값으로 바뀐다.
- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS, Swing 제품 `plugins/`에 `swt`·`jface`·`org.eclipse.ui`·`e4.ui` 번들 0개, 번들 jar에 `fonts/cascadia-mono/*` 포함.
- 확인하지 못한 것: macOS(Cascadia Mono 등록, Apple SD Gothic Neo 대체), Windows 10(운영체제 Cascadia Mono 없음 → 번들 글꼴 등록 경로), DDL 탭의 방언 강조(화면 확인 안 함), 연결된 SQL Server 방언.

### 적용 현황 — Pretendard와 밀도 B (2026-11-07)

사용자 결정(2026-11-07): UI 글꼴을 **Pretendard 13**으로(시안 `D:\Tools\_scratch\ui-font-mock\`), 기본 밀도를 **B "조금 촘촘"**으로
(시안 `D:\Tools\_scratch\density-mock\`). 결과 창 기본 높이(220)는 그때 결정 대기라 바꾸지 않았고, 2026-11-07 사용자 결정으로 **280**이 되었다(아래 "결과 창 기본 높이").

| 파일 | 변경 |
|---|---|
| `fonts/pretendard/`(새) | Pretendard 1.3.9 정적 OTF Regular·SemiBold와 `OFL.txt`(릴리스 `Pretendard-1.3.9.zip`의 `public/static`, 고치지 않음). `build.properties`는 이미 `fonts/`를 넣는다 |
| `UiFont`(새) | 패밀리 `Pretendard`, `Pretendard SemiBold`마다 지연 등록(`FontUtils.registerFontFamilyLoader`, `EditorFont`와 같은 방식) |
| `UiSizes`(새) | `Chromite.size.*` 키 이름과 배율 적용 읽기, 탭 스타일 문자열(`$키` 참조) |
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | `defaultFont = 13 Pretendard`, `semibold.font = "Pretendard SemiBold"`, Sizes 절의 B 값과 `Chromite.size.*` 키 |
| `ChromiteTheme` | Inter 등록 대신 `UiFont`. semibold 확인은 설치한 테마의 `semibold.font`로(swing-l10n.md "한글 굵은 글꼴") |
| `ToolWindowDocking`, `MainFrame`, `ObjectExplorerPanel`, `RegisteredServersPanel`, `QueryLibraryPanel`, `PropertiesPanel`, `QueryStatusLine`, `SqlEditorPanel`, `TableDesignerPanel`, `SessionManagerPanel`, `LockManagerPanel`, `ActivityMonitorPanel`, `ErdDiagramPanel`, `ResultSetFilterBar`, `ResultSetViewerPanel` | 하드코딩한 높이·여백·간격(머리말 40, 도구 막대 여백 3/4/5, 탭 32/40, 알약 6, 상태 줄 28, Properties 행 24, 트리 간격 6, 필터·결과 상태 막대 여백)을 `UiSizes`로 |
| `UiIcons.createToolButton` | 글자 단추의 위 여백을 (글자 줄 − 아이콘) 만큼 줄임 |
| `NOTICE` | Pretendard(OFL 1.1, Reserved Font Name "Pretendard") |

측정(Windows 11, 150%, 창 1280×800 논리, 사설 작업 공간, EDT 에이전트로 컴포넌트 높이를 읽음; 한국어·`-nl en`, 라이트·다크에서 같음):

| 항목 | 전(devel, Inter) | 후(Pretendard, B) | 토큰 |
|---|---|---|---|
| UI 글꼴 한 줄(`FontMetrics.getHeight`) | 17(Inter, 위 13 아래 4) | 17(Pretendard, 위 13 아래 4) | — |
| semibold 글꼴(한국어 화면) | Inter BOLD(맑은 고딕 Bold 대체) | Pretendard SemiBold(PLAIN, 대체 없음) | `font.ui.semibold` |
| 주 도구 막대 | 37(글자 단추 27) | 32(글자·아이콘 단추 24) | 32 |
| 도구 창 머리말 | 41 | 35 | 34 + 1 |
| 도구 창 도구 막대 | 32 | 28 | 28 |
| 트리 행 / 여백 | 24 / 4,12,4,12 | 22 / 3,10,3,10 | 22 |
| 문서 탭 | 40 | 36 | 36 |
| 편집기 도구 막대(콤보) | 37(29) | 32(28) | 32 |
| 결과 탭 | 32 | 28 | 28 |
| 그리드 행 / 머리글 | 27 / 28 | 24 / 26 | 24 / 26 |
| 쿼리 상태 줄(칸) | 29(29) | 27(26) | 26 + 1 |
| Properties 행 | 24 | 22 | 22 |
| 상태 표시줄 | 28 | 26 | 26 |
| 메뉴 항목 | 24 | 22(팝업 캡처 33px ÷ 1.5) | 22 |
| 입력·콤보·단추(연결 대화상자) | 29 | 28 | 28 |
| 필터 막대(고정폭 콤보) | 34(30) | 33(29) | 시안 32 |

- 전에도 Inter(JBR) 한 줄이 17이라 컨트롤이 토큰보다 1 높았다(29, 도구 막대 37). `@componentMargin`·`Button.margin`의 아래 여백을 4로,
  글자 도구 막대 단추의 위 여백을 1 줄여 28·24에 맞췄다. 쿼리 상태 줄의 칸은 여백을 아이콘 크기(16)가 아니라 글자 줄 높이로 계산해 줄보다 높아지지 않게 했다.
- 필터 막대의 콤보는 고정폭 글꼴(JetBrains Mono, 한 줄 18)이라 29다(시안은 UI 글꼴로 그려 28). 잘리지 않으므로 그대로 두었다.
- 쿼리 창의 "실제 계획" 글자 토글 단추(`SqlEditorPanel`)는 `createToolButton`을 쓰지 않아 25다. 도구 막대 높이(콤보 28)에는 영향이 없다.
- 연결 색 알약의 아이콘–알약 간격은 알약 사양(6)을 지키려고 `Chromite.size.tree.secondaryGap`을 쓴다. 알약이 없는 행은 아이콘–글자 4.
- 굵기: Regular·SemiBold만 넣었다. `Font.BOLD`(실행 계획 항목, `h4`, 값 패널 제목 등)는 Java가 Regular를 굵게 만든다.
- `flatlaf-fonts-inter`는 남겼다: UI는 더 쓰지 않지만 `tools/branding/gen_app_icons.py`(가로 로고의 `DATABASE WORKBENCH`)가 Swing 번들 빌드가 복사한
  `lib/flatlaf-fonts-inter.jar`에서 Inter SemiBold를 읽는다(`find_inter`). 빼려면 그 스크립트의 글꼴 출처를 먼저 바꿔야 한다.
- 캡처: `D:\Tools\_scratch\ui-pretendard-density\run\shots\`(before-·after- × ko·en × light·dark: 주 창, 파일 메뉴, 트리 메뉴, 연결 대화상자),
  전후 비교 `D:\Tools\_scratch\ui-pretendard-density\pair-*.png`.

### 적용 현황 — UI 글꼴 12 (2026-11-07)

사용자 요청(2026-11-07): "앱 기본 폰트 사이즈를 한단계 줄인다". UI 글꼴을 Pretendard 13에서 **12**로 줄였다. 밀도 B의 목표 높이는 그대로다.
SQL 편집기 글꼴(`font.editor`, `EditorFont` 기본 13)은 따로 정해진 크기라 바뀌지 않는다.

| 파일 | 변경 |
|---|---|
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | `defaultFont = 12 Pretendard`. 글자 줄이 17 → 15로 낮아져 `@componentMargin = 6,8,5,8`, `Button.margin = 6,14,5,14`(컨트롤 28), `@menuItemMargin = 3,8,3,8`(메뉴 항목 22) |
| `UiIcons` | 글자 도구 막대 단추의 여백 주석(12px 글자 줄 15는 아이콘 16보다 낮아 위 여백을 줄이지 않는다). 코드는 글자 줄 높이로 계산하므로 그대로 |
| `UiFont` | Javadoc의 크기 |

글꼴 크기에서 따라 바뀌는 것(FlatLaf 상대 크기, 측정):
`semibold.font`(Pretendard SemiBold 12), `small` 10(−2), `medium` 11, `h3` 15(+3), `h4` 12 굵게,
`monospaced.font` = JetBrains Mono **12**(크기를 적지 않아 `defaultFont` 크기를 따른다. 결과 그리드·Messages·필터 막대·값 패널·오류 상세 등 `monospaced` 스타일 클래스를 쓰는 곳 모두).
글자 줄 높이로 크기를 계산하는 코드(`QueryStatusLine` 칸 여백, `UiIcons.createToolButton`, 트리·목록의 보조 글자, `DashboardChart`)는 값을 고정하지 않아 그대로 맞는다.

측정(Windows 11, 150%, 창 1280×800 논리, 사설 작업 공간, EDT 에이전트로 컴포넌트 높이를 읽음; 한국어·`-nl en`, 라이트·다크에서 같음):

| 항목 | 전(Pretendard 13) | 후(Pretendard 12) | 토큰 |
|---|---|---|---|
| UI 글꼴 한 줄(`FontMetrics.getHeight`) | 17(위 13 아래 4) | 15(위 12 아래 3) | — |
| `font.code` 한 줄 | 18(JetBrains Mono 13) | 17(JetBrains Mono 12) | — |
| 주 도구 막대 / 단추(아이콘·글자) | 32 / 24·24 | 32 / 24·24 | 32 / 24 |
| 도구 창 머리말 / 도구 막대 | 35 / 28 | 35 / 28 | 34 + 1 / 28 |
| 트리 행 | 22 | 22 | 22 |
| 문서 탭 / 결과 탭 | 36 / 28 | 36 / 28 | 36 / 28 |
| 편집기 도구 막대(콤보), 테이블 디자이너 도구 막대 | 32(28), 32 | 32(28), 32 | 32 |
| "실제 계획" 글자 토글 | 25 | 24 | (24) |
| 그리드 행 / 머리글, 테이블 디자이너 행 / 머리글 | 24 / 26 | 24 / 26 | 24 / 26 |
| 쿼리 상태 줄(칸) | 27(26) | 27(26) | 26 + 1 |
| Properties 행 | 22 | 22 | 22 |
| 상태 표시줄 | 26 | 26 | 26 |
| 메뉴 항목(파일 메뉴, 트리 메뉴) | 22 | 22 | 22 |
| 입력·콤보·단추·스피너(연결 대화상자, 옵션) | 28 | 28 | 28 |
| 아이콘이 있는 콤보(연결 대화상자 "데이터베이스 유형")와 같은 줄의 "드라이버 편집…" | 28 | **29** | 28 |
| 필터 막대(고정폭 콤보) | 33(29) | 34(30) | 시안 32 |
| 확인란 | 21 | 19 | — |

- 글꼴만 줄이면(여백 그대로) 컨트롤 26, 메뉴 항목 21, 편집기 도구 막대 30이 되어 목표보다 낮았다(실행 중 `FlatLaf.setGlobalExtraDefaults`로 미리 측정).
  컨트롤은 위아래 여백을 1씩 늘려 28. 메뉴 항목은 글자 줄(15)보다 아이콘 칸(16)이 높이를 정하므로 위아래 3으로 22다(글자가 행 가운데 온다).
- **1px 차이(그대로 둠)**: 항목에 16px 아이콘을 그리는 콤보는 내용 높이가 글자 줄(15)이 아니라 아이콘(16)이라 29다(13px일 때는 글자 줄 17이 더 높아 28).
  연결 대화상자의 "데이터베이스 유형" 한 줄이 그렇다(같은 줄 단추도 29로 늘어남). 여백을 줄이면 글자만 있는 콤보·입력이 27이 되므로 공통 여백은 글자 기준으로 두었다.
  쿼리 창 도구 막대의 연결·데이터베이스 콤보는 만들 때 높이를 고정해(`SqlEditorPanel.setFixedWidth`) 28이다.
- 필터 막대는 고정폭 글꼴(한 줄 17)이라 콤보가 30, 막대가 34다(전 33). 목표보다 낮지 않아 그대로 두었다. 필터 막대는 열 머리글 필터로 바꿀 예정이다(사용자 요청, 2026-11-07).
- `small`(10)은 About 저작권 줄, 시작 화면 단축키, Properties 유형, 권한 대화상자 안내에 쓴다. 150%에서 15px로 읽을 수 있어 따로 키우지 않았다.
- 잘림: 주 창(트리, 쿼리, 결과), 파일·트리 메뉴, 연결 대화상자, 옵션, 테이블 디자이너를 한국어·영어, 라이트·다크로 캡처해 잘린 글자가 없음을 확인했다.
- 캡처: `D:\Tools\_scratch\ui-font-12\run\shots\`(before-·after- × ko·en × light·dark: main, menu-file, menu-tree, connect, options, designer),
  전후 비교 `D:\Tools\_scratch\ui-font-12\pairs\`.

### 적용 현황 — 그리드 글꼴 Pretendard (2026-11-07)

사용자 요청(2026-11-07): "쿼리결과 그리드의 폰트와 사이즈도 pretendard 12로 맞춘다". 결과 그리드의 칸과 행 번호를 고정폭 `font.code`(JetBrains Mono 12)에서
UI 글꼴 `font.grid` = `font.ui`(Pretendard 12)로 바꿨다. 열 머리글은 전부터 UI 글꼴(`TableHeader.font`)이었다.

| 파일 | 변경 |
|---|---|
| `ResultSetGrid` | 표와 행 번호 열에서 스타일 클래스 `monospaced`를 뺐다. 글꼴은 Look and Feel의 `Table.font`(= `defaultFont`)를 그대로 쓴다(글꼴을 코드에 적지 않음). 행 번호 열 폭은 자릿수 × 가장 넓은 숫자 폭 |
| `ResultSetRecordView` | 레코드 보기 표도 그리드와 같이 `monospaced`를 뺐다(같은 값을 다른 모양으로 보는 화면이라 글꼴을 맞춤) |
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | 그리드 절에 `font.grid` 주석 |
| `EditorFont` | 설계 설명(결과 그리드는 UI 글꼴) |

같은 글꼴을 따를지 정한 것:
- **필터 막대 입력: 고정폭 그대로.** SQL `WHERE` 조건을 쓰는 칸이라 편집기·Messages와 같이 코드 글꼴이 맞다. 필터 막대는 열 머리글 필터로 바뀔 예정이다(사용자 요청 10).
- **값 패널 글자 보기: 고정폭 그대로.** 여러 줄 글, JSON·XML을 구문 색과 들여쓰기로 보여 주는 편집기(RSyntaxTextArea)라 칸 정렬이 고정폭에 기대고 있다.
- **테이블 디자이너 표: 바꿀 것 없음.** 전부터 UI 글꼴이었다(측정 `DesignerTable` Pretendard).
- **레코드 보기: UI 글꼴로.** 그리드의 한 행을 세로로 보여 주는 화면이라 그리드와 같게 했다.

숫자 정렬:
- 숫자 열은 그대로 오른쪽 정렬이다(`CellRenderer`, 바꾸지 않음). 화면에서 끝이 한 세로줄에 맞는다(확대 `D:\Tools\_scratch\ui-font-12\pairs\zoom-gridp-ko-light-grid.png`).
- **Pretendard의 기본 숫자는 비례 폭**이다. `Pretendard-Regular.otf`의 advance(2048 단위): 0 1220, 1 898, 2 1202, 3 1264, 4 1278, 5 1222, 6 1256, 7 1130, 8 1242, 9 1256.
  글꼴에 고정폭 숫자 기능 `tnum`이 있지만, 표준 Java 2D에는 OpenType 기능을 켜는 API가 없다. JBR에는 `java.awt.Font`의 비공개 `deriveFont(Font, Font.Features)`가 있고
  JetBrains Runtime API(`jbr-api`의 `FontExtensions`)로만 쓸 수 있다. 새 의존성이고 JBR 전용이라 넣지 않았다(확인하지 않음, "미룬 결정").
- 그래서 같은 자릿수의 숫자도 폭이 조금 다르다. 캡처(150%)에서 `1,111`은 34px, `8,888`은 46px(논리 약 8px 차이), `10,231`과 `10,232`의 첫 숫자는 3px(논리 2px) 어긋난다.
  고정폭(JetBrains Mono 12)에서는 둘 다 같았다. 오른쪽 끝이 맞아 자릿수(크기)는 바로 읽히지만, 세로로 같은 자리 숫자를 견주는 데는 고정폭보다 덜 가지런하다.
  사용자 요청대로 두고, 불편하면 `tnum`(JBR API)이나 숫자 열만 고정폭으로 하는 방법이 있다.
- 행 번호 열 폭은 전에는 가장 큰 번호 문자열의 폭이었는데, 비례 숫자에서는 `111`이 `108`보다 좁아 잘릴 수 있다. 자릿수 × 가장 넓은 숫자 폭으로 바꿨다(120행 결과에서 100–120 잘림 없음, `rows120-en-dark.png`).

측정(Windows 11, 150%, EDT 에이전트; 한국어·`-nl en`, 라이트·다크에서 같음):

| 항목 | 전(JetBrains Mono 12) | 후(Pretendard 12) | 토큰 |
|---|---|---|---|
| 그리드 칸 글꼴 한 줄 | 17 | 15 | — |
| 그리드 행 / 머리글 | 24 / 26 | 24 / 26 | 24 / 26 |
| 레코드 보기 행 / 머리글 | (고정폭) | 24 / 26 | 24 / 26 |
| 필터 막대(고정폭, 바꾸지 않음) | 34(30) | 34(30) | 시안 32 |

- 같은 내용에서 그리드 폭이 1176px → 1023px(150% 캡처)로 13% 좁아졌다(한 화면에 보이는 열이 늘어남).
- 확인한 값: 숫자(정수·소수·음수·0), 날짜·날짜 시간, NULL(보조 글자색), 한글·영문 섞인 글. □ 없음, 잘림 없음.
- 캡처: `D:\Tools\_scratch\ui-font-12\run\shots\`의 `after-{ko,en}-{light,dark}-grid.png`(전, UI 글꼴 12 브랜치)와 `gridp-{ko,en}-{light,dark}-{grid,main,value,record}.png`(후),
  전후 비교 `D:\Tools\_scratch\ui-font-12\pairs\grid-*.png`.
### 결과 창 기본 높이 (2026-11-07 결정)

사용자 결정(2026-11-07): Results 카드의 기본 높이를 220에서 **280**으로 바꾼다(밀도 B 적용 때 결정 대기였던 것).

- 위치: `MainFrame.DEFAULT_RESULTS_HEIGHT`. 창 기본 크기(`DEFAULT_SIZE` 1280×800)·도구 창 열 비율과 같은 **배치 기본값**이라 밀도 토큰(`Chromite.size.*`, `UiSizes`)에 넣지 않았다.
  밀도 토큰은 컨트롤 치수이고 촘촘한 모드(C)가 덮어쓰는 값인데, 결과 창 높이는 사용자가 끌어 바꾸고 저장되는 값이다.
- 배율: 창 기본 크기(`setSize(DEFAULT_SIZE)`)처럼 `UIScale`을 곱하지 않는다(바꾸기 전 220도 곱하지 않았다). Windows 화면 배율(150% 등)은 Java 런타임이 처리한다.
- 저장된 높이: 사용자가 높이를 바꿨으면 저장된 값(`results.size`)이 이긴다. 설정 저장소는 기본값과 같은 값을 저장하지 않으므로(`ToolWindow.SplitHost.load`)
  높이를 바꾼 적 없는 사용자는 새 기본값 280을 받는다. Window > Reset Window Layout도 280으로 돌린다.
### 적용 현황 — 실행 계획 그림 (2026-11-07)

사용자 결정: 실행 계획 탭에 그래픽(SSMS식 연산자 그림) · 트리 · 원본 세 보기. 동작과 DB별 범위는 [swing-sql-editor-plus.md](swing-sql-editor-plus.md) "실행 계획 세 보기".
여기는 모양 토큰만 적는다(모든 값은 UIScale을 곱하기 전의 px).

| 요소 | 값 |
|---|---|
| 보기 전환 | 계획 탭 도구 막대(`toolWindowToolBar.padding`) 맨 앞, 글자 토글 단추 셋(그래픽 / 트리 / 원본, `arc: 6` 도구 막대 단추)을 1px `Component.borderColor` 둥근 테두리(arc 8)로 묶음 |
| 캔버스 | `Chromite.editorBackground`, 바깥 여백 16, 문장 사이 28, 머리말 아래 12 |
| 문장 머리말 | 1줄 굵게 `쿼리 N: 쿼리 비용(일괄 처리 기준): X%`, 2줄 문장 첫 줄 `text.secondary`, 누락 인덱스 줄 `status.success`, 오류 줄 `status.error`(글자색과 35% 섞음). 최대 폭 1200 |
| 연산자 상자 | 폭 168, 높이 = 여백 6 × 3 + 아이콘 32 + 글자 줄 4개, arc 10, 바탕 = 캔버스에 글자색 3.5%, 테두리 1px `Component.borderColor`. 열 간격 60, 줄 간격 22 |
| 상자 글자 | 연산 이름 UI 글꼴 굵게, 둘째 줄(개체·논리 연산) UI 글꼴 −1 `text.secondary`, `비용: N%` UI 글꼴, 행 줄 UI 글꼴 −1 `text.secondary`. 넘치면 … |
| 아이콘 | 연산자 아이콘 16px를 두 배(32)로 그림, 아이콘 팩·테마를 따름. 경고 배지는 `warning` 16을 아이콘 오른쪽 아래에 겹침 |
| 비싼 연산자 | 최대 비용: 2px `status.error` 테두리 + 바탕 `status.error` 8%, 비용 글자 굵게 오류 색. 25% 이상: 1.5px `status.warning` 테두리, 비용 글자 경고 색 |
| 예상 어긋남 | 행 줄을 굵게, `status.warning`과 글자색 35% 섞은 색 |
| 선택 | 바탕 `Table.selectionBackground`, 2px `Component.focusedBorderColor` |
| 화살표 | 캔버스와 글자색 45% 섞은 색, 굵기 1~10(행 수 로그 비율), 머리 길이 굵기+6 · 반폭 굵기/2+3, 자식마다 들어가는 높이 7씩 |
| 원본 보기 | SQL 편집기 테마 파일(`SqlEditorLight/Dark.xml`), `monospaced.font`, 줄 번호, 현재 줄 강조 없음 |

새 아이콘 18개(연산자 `plan-*` 17개, `copy`)는 생성기 규칙 그대로다: 데이터 객체 중립색, 동작 파랑, 데이터 변경은 표 위 배지(삽입 초록, 수정 파랑, 삭제 빨강).

### 적용 현황 — 열 필터 (2026-11-07)

사용자 요청 10(2026-11-07): "쿼리결과의 필터 입력 항목을 제거하고 그리드의 각 컬럼에 필터 아이콘을 배치하여 컬럼별 필터를 설정할 수 있도록 수정한다."
참고는 사용자의 DataGrip 결과 그리드 캡처(머리글마다 늘 보이는 흐린 깔때기·정렬 아이콘, WHERE 줄 없음). 기능 설계는 [swing-grid-column-filters.md](swing-grid-column-filters.md).

| 요소 | 값 | 토큰 |
|---|---|---|
| 머리글 배치 | 열 아이콘 16(`column`, 기본 키 `column-pk`) – 4 – 이름 … 4 – 깔때기 16 – 정렬 16 – 셀 여백 8 | `size.gridHeader.gap`, `size.gridHeader.action`, `TableHeader.cellMargins` |
| 머리글 높이 | 26(바뀌지 않음) | `size.header.grid` |
| 아이콘 색 | 평소 `text.secondary`, 필터·정렬 있음 `accent`(깔때기는 채움), 다시 읽을 수 없으면 40% | `grid.headerIcon`, `grid.headerIconActive` |
| 아이콘 마우스 올림 | 16×16 둥근 바탕(arc 4) `bg.actionHover` | `Button.toolbar.hoverBackground` |
| 필터 팝업 | 폭 320, 안쪽 여백 8, 간격 6, 테두리 `border.popup`, 바탕 `bg.popup`, 값 목록 8행, 기본 단추 "적용" | `PopupMenu.border`, `PopupMenu.background` |
| 필터 요약 | 결과 상태 줄(행 수 옆)에 강조색 깔때기 16 + "필터: 열1, 열2" + ✕(여백 1, 높이 18). 필터가 있을 때만 | `size.padding.viewerStatus` |
| 값 패널·레코드 단추 | 필터 막대 오른쪽 → 결과 상태 줄 오른쪽 | — |

- 측정(EDT 에이전트, Windows 11, 150%): 머리글 26, 결과 상태 줄 25(필터 요약이 보여도 25, ✕ 단추 18), 팝업 320×318(조건 값 칸이 보이면 352, 범위 397).
- 필터 막대(34)가 없어진 만큼 결과 영역이 넓어졌다. 흐린 아이콘이라 머리글이 무거워 보이지 않고, 필터가 걸린 열은 강조색으로 바로 보인다.
- 캡처: `D:\Tools\_scratch\grid-column-filters\run\shots\`(한국어·영어, 라이트·다크).

### 적용 현황 — 촘촘한 모드 (2026-11-08)

사용자 결정(2026-11-07, 후속 #46): 기본 밀도는 B, 촘촘한 모드(C)는 나중에 보기 메뉴 전환으로. 그 전환을 넣었다.

- **메뉴:** View > Compact Mode(한국어 "촘촘한 모드"), Theme 아래의 확인 항목. 누르면 바로 바뀌고(다시 시작하지 않음) 다음 시작에도 쓴다.
- **값:** 위 "치수 토큰"의 C 값. 바뀌는 키만 `ChromiteCompact.properties`(테마 properties와 같은 폴더)에 있고, 없는 키는 B 값 그대로다.
  두 테마가 같은 파일을 쓴다. 새 키는 `Chromite.size.editorToolBar.comboBox.padding` 하나다(B는 `@componentMargin`, SQL Editor 도구 막대의 연결·DB 선택 상자).
- **저장:** 이 번들의 설정 `ui.compactMode`(테마 `ui.theme`과 같은 저장소, 기본 false).

| 구조 | 내용 |
|---|---|
| `UiDensity`(새 파일) | 저장된 밀도, 지금 밀도, C 값 읽기(`ChromiteCompact.properties` → FlatLaf extra defaults), `apply` = 지금 테마 다시 설치 + 저장 |
| `ChromiteTheme.install` | 테마를 설치할 때마다 밀도의 extra defaults를 넘김(semibold 글꼴 대체와 합침) |
| `ChromiteSwingApplication.openMainWindow` | 저장된 테마나 밀도가 처음 설치(작업 공간을 잠그기 전, 라이트·B)와 다르면 주 창을 열기 전에 다시 설치 |
| `MainFrame` | View 메뉴 항목 |
| `UiSizes.createPadding` | 여백 테두리가 토큰을 그때마다 읽음(도구 막대·도구 창 머리말·결과 상태 막대 여백이 실행 중에 바뀜) |
| `UiIcons.createToolButton` | 글자 단추(새 쿼리, 실행)의 명시한 여백을 UI가 바뀔 때 다시 계산(아니면 단추 24가 남아 주 도구 막대가 30) |
| `SqlEditorPanel.sizePicker` | 연결·DB 선택 상자의 여백을 토큰으로, 고정한 높이를 UI가 바뀔 때 다시 계산 |
| `PropertiesPanel` | 행 높이를 `updateUI`에서 다시 정함 |
| `ResultSetGrid` | 행 번호 표의 행 높이가 그리드 행 높이를 따라가고, 열·행 번호 폭이 칸 여백 차이만큼 바뀜(맞춘 폭과 사용자가 바꾼 폭 모두) |
| `QueryStatusLine` | 칸 테두리(구분선 위치, 위아래 여백)가 줄 높이와 글자 줄을 그때마다 읽음 |

측정(이 브랜치 빌드, Windows 11 150%, 1280×800 창, SQL Server 쿼리 60행, 한국어·영어 같은 값, 라이트·다크 같은 값):

| 요소 | B | C |
|---|---|---|
| 트리 행 / 트리 여백 | 22 / 3,10 | 20 / 2,8 |
| 그리드 행 / 머리글 / 행 번호 행 | 24 / 26 / 24 | 22 / 24 / 22 |
| 주 도구 막대(단추) | 32 (24) | 28 (22) |
| SQL Editor 도구 막대(선택 상자) | 33 (29) | 28 (24) |
| 도구 창 머리말(+선) / 도구 창 도구 막대 | 35 / 28 | 31 / 26 |
| 문서 탭 / 결과 탭 | 36 / 28 | 32 / 26 |
| 쿼리 상태 줄(+선) / 상태 표시줄 | 27 / 26 | 25 / 24 |
| Properties 행 | 22 | 20 |
| 메뉴 항목 | 22 | 20 |
| 제목·메뉴 막대, 대화상자 컨트롤, 글꼴, 편집기 줄 | 그대로 | 그대로 |

- 실행 중 전환: B → C → (다크) → B → C, 저장한 C로 다시 시작(영어)에서 위 값이 모두 맞았다. View 메뉴의 확인 표시도 따라간다.
- SQL Editor 도구 막대의 B 값 33은 이번 변경 전과 같다: 연결·DB 선택 상자는 아이콘(16)이 12px 글자 줄(15)보다 높아 29다(빈 선택 상자는 28).
  C 여백은 아이콘 줄에 맞춰 24로 했다(빈 선택 상자는 23).
- 캡처: `D:\Tools\_scratch\chromite-compact-mode\run\shots\`(ko·en × light·dark × B·C, View 메뉴).

### 마우스 올림 다시 그리기 (2026-11-08)

사용자 보고(앱 1차 #10): 다크 테마 150%에서 개체 탐색기의 마우스를 위로 움직이면 희미한 잔상이 남고, 아래로 움직이면 남지 않는다.

- **원인(재현으로 확인):** 마우스 올림 둥근 사각형은 `FlatUIUtils.setRenderingHints`(안티앨리어싱 + `STROKE_NORMALIZE`)로 그린다.
  화면 배율이 1보다 크면 정규화된 안티앨리어싱 가장자리가 행 밖의 장치 픽셀 한 줄을 반쯤 칠한다. 그런데 `setHoverRow`는 옛 행과 새 행의
  `getRowBounds`만 다시 그려, 그 줄이 다시 그리는 영역 밖에 남았다.
  - 150%: 행 경계가 정수 장치 픽셀에 있을 때 사각형 아래 가장자리가 다음 행의 첫 줄을 칠한다(다크 `#202123`, 바탕 `#191A1C`.
    라이트 `#FBFBFB`, 바탕 `#FFFFFF`라 거의 안 보임). 위로 움직이면 남고, 아래로 움직이면 그 줄이 새 강조 행 안이라 지워진다(사용자 보고와 같음).
    행 경계가 반 픽셀(.5)에 걸리는 위치(스크롤·도구 창 위치에 따라 다름)에서는 남지 않았다.
  - 125%: 행 경계가 .0·.25·.5·.75로 바뀌어, 위·아래 어느 쪽으로 움직여도 줄이 남는 위치가 있다.
  - 100%: 남지 않는다.
- **수정:** 세 트리(`ObjectExplorerPanel.ExplorerTree`, `RegisteredServersPanel.ServersTree`, `QueryLibraryPanel.LibraryTree`.
  Scripts 도구 창도 `LibraryTree`를 씀)의 `repaintRow`가 행 위아래로 `HOVER_REPAINT_MARGIN`(1 논리 픽셀, 배율 1 이상에서 1 장치 픽셀 이상)만큼
  넓혀 다시 그린다. 강조 모양과 그리기 힌트는 그대로라 선택 사각형과 같은 모양을 유지한다.
- **공통 도우미로 묶지 않은 이유:** 세 클래스는 원래부터 각자 마우스 올림 코드를 가진다(swing-registered-servers.md 설계 결정).
  이번에는 같은 한 줄과 상수만 바꿔 최소 변경으로 두었다.
- **확인 방법:** 시험 에이전트가 트리에 `MOUSE_MOVED`를 직접 보내 한 행씩 위로(또는 아래로) 옮기고, 창을 `PrintWindow`로 찍은 그림과
  같은 상태를 전체 다시 그린 그림의 픽셀을 비교한다(화면 입력 없음).

측정(Windows 11, 1280×800 창, 트리 행 22, 125%·100%는 `-Dsun.java2d.uiScale`. 세 트리 × 라이트·다크 × 스크롤 0·1px × 위·아래, 배율마다 24회).
값은 한 행씩 옮긴 뒤 전체 다시 그린 그림과 다른 픽셀 수(트리 영역):

| 배율 | 수정 전 | 수정 후 |
|---|---|---|
| 150% | 위로 12회 중 행 경계가 정수 장치 픽셀인 6회 모두 잔상(지나간 행마다 한 줄, 트리 폭). 아래로는 잔상 없음(마지막 행 아래 가장자리가 덜 그려진 한 줄만 다름) | 24회 모두 0 |
| 125% | 24회 중 20회 다름. 위로 12회 모두 잔상, 아래로도 잔상이 남는 위치가 있음(개체 탐색기 스크롤 0에서 4줄) | 24회 모두 0 |
| 100% | 24회 모두 0 | 24회 모두 0 |

- 수정 후 두 번(150% 다크 Query Library, 125% 라이트 Registered Servers)은 0이 아니었는데, 다른 픽셀이 모두 창 제목 표시줄 아이콘(y 20–39)에 있고
  트리 영역은 0이었다.
- 캡처: `D:\Tools\_scratch\chromite-hover-repaint\shots\`(`evidence-150-dark-explorer.png`: 수정 전 위로 움직인 뒤 / 전체 다시 그림 / 수정 후).

### 포커스 표시 — 내용 영역 테두리 없음 (2026-11-11, 앱 변경 #37)

사용자 요청(기업 환경 2026-11-09): "출력결과 grid를 선택하면 주변에 테두리가 포커싱된다. 이 효과를 제거하고, 다른 화면에서도 이와 같은 효과를 제거한다".
사용자 결정: "내용 영역만 제거"(입력란·콤보·단추의 포커스 표시는 유지).

- **원인(그린 테두리를 읽어 확인):** FlatLaf의 스크롤 창 테두리(`FlatScrollPaneBorder`, 테마 키 `ScrollPane.border`와 표를 스크롤 창에 넣을 때
  Swing이 붙이는 `Table.scrollPaneBorder`)는 `FlatBorder.isFocused`가 스크롤 창이면 그 안 보기(표·트리·목록·글 영역)의 포커스를 보고,
  포커스가 있으면 테두리 색을 `Component.focusedBorderColor`(`#3871E1`)로 바꾼다. 결과 그리드의 스크롤 창(`ResultSetGrid`)은 테두리를 따로 정하지 않아
  이 테두리를 쓴다. 측정(테스트 에이전트, 스크롤 창 왼쪽 테두리 픽셀): 포커스 있음 `#3871E1`, 없음 다크 `#40434A` / 라이트 `#D1D3D9`.
- **고친 곳(한 곳):** 두 테마 properties가 `ScrollPane.border`, `Table.scrollPaneBorder`를 `ContentScrollPaneBorder`로 바꾼다.
  이 클래스는 `FlatScrollPaneBorder`와 같고 `isFocused`만 다르다: 보기가 **편집할 수 있는 글 영역**(여러 줄 입력란: Agent 질문 칸, 대화상자의 여러 줄 입력)이면
  FlatLaf 그대로 포커스 색, 그 밖(표·트리·목록·읽기 전용 글)은 늘 보통 테두리색. 입력란·콤보·단추는 다른 테두리(`FlatTextBorder`, `FlatRoundBorder`)라 그대로다.
- **이미 테두리가 없던 곳:** SQL 편집기, Messages, 개체 탐색기·등록된 서버·쿼리 라이브러리 트리, 값 보기, 실행 계획은 스크롤 창에 빈 테두리를 주어 원래 포커스 테두리가 없었다.
  이번 변경이 실제로 바꾸는 곳은 기본 테두리를 쓰는 스크롤 창: 결과 그리드, Options 페이지 목록, 대화상자의 표·목록·트리 등.
- **포커스 위치:** 선택 바탕색으로 구분된다(FlatLaf가 포커스에 따라 바꿈). 결과 그리드: 다크 활성 `#2A4371` / 비활성 `#33353B`, 라이트 `#D0DFFE` / `#E9EAEE`.
- **확인:** Attach API 테스트 에이전트, 실제 결과 그리드(SQLite), 포커스는 대체 키보드 포커스 관리자로 흉내 내고 화면 밖에 그림(OS 입력 없음), 100%·150%, 밝은·어두운 테마.
  전(devel의 `FlatScrollPaneBorder`) 포커스 있음 `#3871E1` → 후 포커스 있음 `#40434A`(다크) / `#D1D3D9`(라이트), Options 목록도 같음. Agent 질문 칸(편집 가능)은 후에도 `#3871E1`.
  입력란·콤보·단추의 포커스 테두리는 그대로. 캡처: `D:\Tools\_scratch\chromite-theme-look\shots\`(`grid-*.png`, `options-list-*.png`, `input-*.png`, `sel-grid-*.png`, `controls-*.png`).

### 대화상자 제목 줄 (2026-11-11, 앱 변경 #56)

사용자 요청(기업 환경 2026-11-11): 팝업·메시지 상자의 제목 영역이 너무 두꺼워 어색하다, 조금 더 얇게.

- **원인:** FlatLaf 창 장식의 제목 줄 높이는 창 단추 높이(`TitlePane.buttonSize`, 단추가 세로로 채움)로 정해지고, 이 값이 모든 창에 같다.
  메인 창(메뉴 막대를 품음)에 맞춘 46×40이 대화상자·메시지 상자에도 쓰였다. 측정: 메시지 상자 제목 줄 40(150%에서 60px), 본문 한 줄보다 훨씬 높다.
- **고친 곳:** 테마 `TitlePane.buttonSize`를 46×30(Windows 기본 대화상자 제목 줄에 가까움)으로 낮추고, 메인 창만 루트 창 속성
  `JRootPane.titleBarHeight`(`FlatClientProperties.TITLE_BAR_HEIGHT`)에 테마 키 `Chromite.size.mainTitleBar.height`(40)를 준다(`MainFrame` 생성자).
  대화상자·메시지 상자·파일 선택 등 다른 장식 창은 따로 손대지 않아도 30이다. 테두리 없는 창(시작 화면, 업데이트 창)과 macOS(OS 제목 줄)는 대상이 아니다.
- **확인(테스트 에이전트, 밝은·어두운 테마):** "지난 세션의 쿼리 창 1개를 복원할까요?" 메시지 상자 제목 줄 40 → 30(150%: 60 → 45px), 닫기 단추 46×40 → 46×30,
  제목 글자 칸이 제목 줄 높이 전체(세로 가운데). 메인 창 제목 줄은 41 그대로(단추 46×40). 캡처: `title-before-*.png`, `title-after-*.png`(100%·150%).

### 적용 현황 — 글자 선명도와 대비 (2026-11-12, 앱 변경 #43·#55)

사용자 보고(기업 환경): 회사 PC(클라우드 VDI, 1920×1080, 100% 배율, ClearType 켜짐)에서 Chromite 글자만 흐리다. 같은 모니터의 SSMS·Orange는 선명하고,
같은 VDI를 화소가 촘촘한 노트북으로 보면 견딜 만하다(#43). 다크 테마에서 일부 글자, 특히 오른쪽 아래 상태 표시줄이 잘 보이지 않는다(#55).
측정 도구와 원본: `D:\Tools\_scratch\chromite-text-clarity\`(`probe\`: 독립 측정 프로그램, `agent\`: 앱 안 테스트 에이전트, `shots\`: 캡처).

#### #43 글자 선명도: 측정

**1. 실제로 쓰는 글자 다듬기 방식.** 이 PC(Windows 11, ClearType 켜짐, 로컬 세션)에서 JBR 25.0.4, `-Dsun.java2d.uiScale=1`:

| 항목 | 값 |
|---|---|
| Windows 설정(SPI) | `win.text.fontSmoothingOn = true`, `fontSmoothingType = 2`(ClearType), 대비 1200, 방향 RGB |
| `awt.font.desktophints` | `TEXT_ANTIALIAS_LCD_HRGB`, LCD 대비 120 |
| 앱 안 `UIManager`의 `KEY_TEXT_ANTIALIASING` | `LCD_HRGB`(FlatLaf가 desktophints를 그대로 넣음) |
| 앱 화면(오프스크린으로 그린 메인 창)의 색 번짐 평균 | 31.9(다크)·37.0(라이트): LCD 서브픽셀 글자(회색조라면 10 아래) |

즉 앱은 ClearType(LCD) 다듬기를 이미 쓴다. **"회색조 안티앨리어싱이라 흐리다"는 원인이 아니다.**

**2. 원격 세션에서 고르는 방식(JDK 소스로 판단, 재현하지 않음).** JDK의 Windows 경로는 원격 세션을 따로 다루지 않는다:
`WDesktopProperties.getDesktopAAHints()`는 그 세션의 SPI 값만 보고(켜짐+ClearType → LCD, 켜짐+표준 → GASP, 꺼짐 → 기본(끔)),
`SunToolkit.useSystemAAFontSettings()`의 추가 조건은 Windows에서 쓰이지 않으며, `FileFontStrike`의 GDI 글리프 경로에도 원격 검사가 없다.
그래서 VDI에서 ClearType이 켜져 있으면(사용자 확인) 회사 PC에서도 LCD다. 이 PC에 RDP로 붙어 재현하면 Windows Pro는 콘솔 세션을 끊어 사용자 화면을
방해하므로 하지 않았다(**미확인**). VDI 영상 압축(색 정보 줄임)은 모든 앱의 ClearType 색 번짐을 뭉개지만, 아래 3의 차이(획이 한 화소에 맞는지)는 그대로 남는다.

**3. 글꼴마다 다른 그리는 길.** JDK `FileFontStrike`는 LCD 다듬기에서 **설치된** TrueType 글꼴이면 Windows GDI에 글리프를 맡기고(native 경로,
Windows 기본 앱과 같은 ClearType+힌팅), **파일에서 등록한** 글꼴(`Font.createFont`, `useJavaRasterizer`)은 FreeType으로 그린다.
번들 Pretendard는 파일 글꼴이고 CFF(PostScript) 윤곽이라 FreeType의 약한 CFF 힌팅을 받는다.
측정(`probe\TextProbe`, `SizeProbe`, `GdiStats`): 같은 글줄("Connections 연결 탐색기 SELECT …")을 불투명 RGB 그림에 그려, 글자 화소 중 덮임 20–80%인
화소(번짐)와 80% 넘는 화소(꽉 찬 획)의 비율을 셌다.

| 글꼴, 12px(100%) | 그리는 길 | 다크 번짐 / 꽉 참 | 라이트 번짐 / 꽉 참 |
|---|---|---|---|
| Pretendard(번들), LCD | FreeType | 60.0% / 12.3% | 64.5% / 7.9% |
| 맑은 고딕(설치됨), LCD | GDI | 52.6% / 18.6% | 52.6% / 18.6% |
| 맑은 고딕, Windows 앱(`TextRenderer`, GDI ClearType) | — | 52.6% / 18.6% | 58.9% / 20.8% |
| Segoe UI(설치됨), LCD | GDI | 40.3% / 29.5% | 40.2% / 29.5% |

다크 테마에서 Java의 맑은 고딕 LCD 글자는 Windows 앱의 GDI 글자와 측정값이 같다(번짐·꽉 참 같음, 색 번짐 69.9 / 69.7; 라이트의 차이는 Windows의
감마와 Java LCD 대비 값의 차이). Pretendard는 12px에서 꽉 찬 획이 맑은 고딕의 절반 정도라 획이 두 화소에 걸쳐 흐리다. 크기별(다크, LCD, 꽉 찬 획 비율):

| 크기 | 12px(100%) | 13px | 15px(125%) | 18px(150%) |
|---|---|---|---|---|
| Pretendard(번들) | 12.3% | 24.1% | 23.3% | 27.2% |
| 맑은 고딕 | 22.5% | 22.8% | 28.6% | 31.4% |
| Cascadia Mono(번들, 편집기) | 35.1% | 33.2% | 34.0% | 42.6% |
| Consolas(설치됨) | 23.9% | 28.6% | 25.5% | 35.2% |

- 150%(개발 PC)에서는 차이가 작아 흐림이 가려졌다. 100%에서 차이가 가장 크다.
- 편집기 글꼴 Cascadia Mono(번들 TTF, TrueType 힌팅)는 FreeType으로도 선명해 바꿀 필요가 없다. 고정폭 JetBrains Mono도 TrueType이다.
- 색 대비(3)는 #55에서 따로 다뤘다.

#### #43 고친 것

(같은 날 앱 변경 #67에서 UI 글꼴 선택과 번들 Pretendard를 지우고 운영체제 UI 글꼴 13 하나로 바꿨다: 아래 "적용 현황 — 운영체제 UI 글꼴 13".)

- **UI 글꼴 선택**(`UiFont.Choice`, Options > General > UI 글꼴, [swing-options.md](swing-options.md) "UI 글꼴"): 자동 / Pretendard / 시스템 글꼴.
- **기본값은 자동**: 기본 화면 배율이 150% 미만이면 시스템 글꼴(한국어 Windows는 맑은 고딕 12, 영어 Windows는 Segoe UI 12), 150% 이상이면 Pretendard 12.
  이유: 측정에서 100%·125%의 Pretendard만 흐리고, 시스템 글꼴은 Windows 기본 앱과 같은 GDI ClearType 글자다. 사용자가 Pretendard를 고른 화면은 150%였고,
  그 화면에서는 그대로 Pretendard다. Pretendard는 선택지로 남는다.
- semibold(도구 창 제목 등)는 시스템 글꼴의 BOLD(맑은 고딕 Bold)다. 시스템 글꼴에는 semibold 패밀리가 없고, 한글 대체 글꼴도 굵게 되도록
  기존 `semibold.font = bold` 방식을 쓴다.
- LCD 다듬기를 강제하는 설정은 넣지 않았다: 이미 운영체제 설정(ClearType)을 따르고, 원격 세션에서 ClearType이 꺼져 있으면 Windows 앱도 다듬지 않은 글자라
  그것이 그 환경에서 가장 또렷하다.

#### #55 글자 대비: 측정과 고친 것

WCAG 2 대비(`ConnectionColors.contrast`와 같은 식). 바탕: 카드(트리·그리드·편집기·패널), 캔버스(제목 표시줄·메뉴 막대·도구 막대·**상태 표시줄**·다크 메뉴 팝업),
띠(Properties 분류 행, 다크 편집기 현재 줄), 선택(포커스 있음/없음), 문서 탭 알약. 목표(어두운 모니터·VDI 압축을 감안해 기준보다 여유):
본문·상태 표시줄 7:1 이상, text.secondary 카드·캔버스·포커스 없는 선택에서 4.5:1 이상, text.disabled(안내 글·자리 표시 글로도 쓰임) 카드·캔버스에서 3:1 이상이면서
text.secondary보다 흐림, 편집기 줄 번호 카드에서 4.5:1 이상. 굵게 표시한 값이 바뀐 뒤 값이다.

다크:

| 글자 | 전 → 후 | `#191A1C` 카드 | `#26282C` 캔버스 | `#212326` 띠 | `#2A4371` 선택 | `#33353B` 선택(포커스 없음) | `#233558` 탭 알약 |
|---|---|---|---|---|---|---|---|
| text.primary (`@foreground`) | `#D1D3D9` | 11.64 | 9.86 | 10.53 | 6.56 | 8.19 | 8.15 |
| 상태 표시줄 칸·주 상태 문구·업데이트 진행 | `#73767C` → `#D1D3D9` | 3.82 → **11.64** | 3.24 → **9.86** | 3.46 → **10.53** | 2.16 → **6.56** | 2.69 → **8.19** | 2.68 → **8.15** |
| text.secondary (그리드 NULL·행 번호, 트리 보조 글자, 메뉴 단축키, 창 제목, Options 안내) | `#73767C` → `#9A9DA4` | 3.82 → **6.41** | 3.24 → **5.44** | 3.46 → **5.80** | 2.16 → **3.61** | 2.69 → **4.51** | 2.68 → **4.49** |
| text.disabled (비활성, 자리 표시 글, 비활성 창 제목, 연결 대화상자 등의 안내 글) | `#5A5D63` → `#777A81` | 2.64 → **4.05** | 2.24 → **3.43** | 2.39 → **3.66** | 1.49 → **2.28** | 1.86 → **2.85** | 1.85 → **2.84** |
| 편집기 줄 번호 | `#4B5059` → `#808389` | 2.15 → **4.58** | 1.82 → **3.88** | 1.94 → **4.15** | 1.21 → **2.58** | 1.51 → **3.23** | 1.50 → **3.21** |
| 편집기 현재 줄 번호 | `#A1A3AB` → `#D1D3D9` | 6.92 → **11.64** | 5.87 → **9.86** | 6.26 → **10.53** | 3.90 → **6.56** | 4.87 → **8.19** | 4.85 → **8.15** |
| text.link | `#6B9BFA` | 6.38 | 5.41 | 5.77 | 3.60 | 4.49 | 4.47 |
| status.error 글자 | `#DB5C5C` → `#E66A6A` | 4.72 → **5.50** | 4.01 → **4.66** | 4.27 → **4.98** | 2.66 → **3.10** | 3.33 → **3.87** | 3.31 → **3.85** |
| status.warning 글자(`Chromite.status.warningForeground`) | `#F2C55C` | 10.71 | 9.08 | 9.69 | 6.04 | 7.54 | 7.50 |
| status.success 글자 | `#5FAD65` | 6.35 | 5.38 | 5.74 | 3.58 | 4.47 | 4.44 |
| completion.match | `#548AF7` → `#5C93FA` | 5.27 → **5.81** | 4.47 → **4.93** | 4.77 → **5.26** | 2.97 → **3.28** | 3.71 → **4.09** | 3.69 → **4.07** |

라이트:

| 글자 | 전 → 후 | `#FFFFFF` 카드 | `#E9EAEE` 캔버스 | `#F7F8F9` 띠 | `#D0DFFE` 선택 | `#E9EAEE` 선택(포커스 없음) | `#E3EBFE` 탭 알약 |
|---|---|---|---|---|---|---|---|
| text.primary (`@foreground`) | `#000000` | 21.00 | 17.47 | 19.75 | 15.67 | 17.47 | 17.58 |
| 상태 표시줄 칸·주 상태 문구·업데이트 진행 | `#73767C` → `#000000` | 4.55 → **21.00** | 3.79 → **17.47** | 4.28 → **19.75** | 3.40 → **15.67** | 3.79 → **17.47** | 3.81 → **17.58** |
| text.secondary | `#73767C` → `#5E6168` | 4.55 → **6.20** | 3.79 → **5.16** | 4.28 → **5.83** | 3.40 → **4.63** | 3.79 → **5.16** | 3.81 → **5.19** |
| text.disabled | `#A8ADBD` → `#7F8390` | 2.24 → **3.78** | 1.86 → **3.15** | 2.11 → **3.56** | 1.67 → **2.82** | 1.86 → **3.15** | 1.87 → **3.17** |
| 편집기 줄 번호 | `#AEB3C2` → `#6E727D` | 2.09 → **4.81** | 1.74 → **4.00** | 1.97 → **4.52** | 1.56 → **3.59** | 1.74 → **4.00** | 1.75 → **4.03** |
| 편집기 현재 줄 번호 | `#767A8A` → `#3B3E45` | 4.27 → **10.71** | 3.55 → **8.91** | 4.01 → **10.07** | 3.18 → **7.99** | 3.55 → **8.91** | 3.57 → **8.97** |
| text.link | `#315FBD` | 6.00 | 4.99 | 5.64 | 4.47 | 4.99 | 5.02 |
| status.error 글자 | `#DB3B4B` → `#C9303F` | 4.43 → **5.28** | 3.69 → **4.39** | 4.17 → **4.96** | 3.31 → **3.94** | 3.69 → **4.39** | 3.71 → **4.42** |
| status.warning 글자(`Chromite.status.warningForeground`) | `#FFAF0F` → `#8A5A00` | 1.84 → **5.93** | 1.53 → **4.93** | 1.73 → **5.57** | 1.38 → **4.42** | 1.53 → **4.93** | 1.54 → **4.96** |
| status.success 글자 | `#208A3C` → `#1B7A34` | 4.41 → **5.41** | 3.67 → **4.50** | 4.15 → **5.09** | 3.29 → **4.04** | 3.67 → **4.50** | 3.69 → **4.53** |
| completion.match | `#3871E1` → `#2F62C9` | 4.55 → **5.66** | 3.78 → **4.71** | 4.28 → **5.32** | 3.39 → **4.22** | 3.78 → **4.71** | 3.81 → **4.74** |

- **상태 표시줄**: 쿼리 창 칸(`QueryStatusLine`), 작업 공간 문구(`MainFrame.StatusBar`), 업데이트 진행(`UpdateUi`)을 text.secondary에서 text.primary로.
  상태 표시줄은 캔버스(다크 `#26282C`) 위라 카드보다 대비가 더 낮았다. 또 연결 색 띠의 대비 확인(`ConnectionColors.getTextColor`)은 text.primary로 하면서
  칸은 더 흐린 text.secondary로 그려, 띠 위에서는 확인을 통과한 칸도 읽기 어려울 수 있었다. 이제 확인하는 색과 그리는 색이 같다.
- **text.secondary·text.disabled 토큰 자체를 밝힘**(다크)·진하게(라이트): 그리드 NULL 표시·행 번호, 트리 보조 글자, 메뉴 단축키, 창 제목, 자리 표시 글,
  비활성 글자가 함께 바뀐다. 코드의 안내 글 여러 곳이 `Label.disabledForeground`를 쓰므로 text.disabled도 3:1 이상으로 올렸다.
  text.disabled는 여전히 text.secondary보다 흐려 비활성인 것이 구별된다. 비활성 창 제목(`TitlePane.inactiveForeground`)은 FlatLaf 기본대로 text.disabled다.
- **경고 글자**: 밝은 테마의 `Chromite.status.warning`(`#FFAF0F`)은 아이콘용 호박색이라 흰 바탕에서 1.8:1이었다. 글자용 `Chromite.status.warningForeground`를 두고
  글자로 쓰는 곳(Agent 패널 안내·모델 설정 항목, Agent 설정의 경고 글·도구 권한, 연결 설정의 유휴 롤백 경고, 도구 카드 상태·쓰기 배지, Query Library 실행 기록의 건너뜀)을
  그 키로 바꿨다. 아이콘·테두리·바탕 섞기(연결 중 점, 실행 계획 테두리, 접근 알림 띠)는 그대로 `Chromite.status.warning`이다.
- 편집기 줄 번호는 테마 XML의 기본값이다. Options > Editor Colors에서 줄 번호 색을 저장한 사용자는 그 색이 그대로다.
- 선택 바탕(포커스 있음) 위의 보조 글자(3.61)는 여러 렌더러가 선택된 행에서 선택 글자색으로 바꾸므로 그대로 두었다.
- 바꾸지 않은 것: 비선택 탭 글자(`TabbedPane.foreground` = text.primary, 9.86 이상), 도구 설명(15.4 / 14.7), 링크, 탭 밑줄 등 글자가 아닌 표시.

#### 확인

Attach API 테스트 에이전트(EDT에서 Swing 객체를 직접 다룸, OS 입력 없음), 빈 작업 공간, SQLite 연결 하나에서 쿼리 실행, 메인 창 1280×800을 오프스크린으로 그림.
캡처: `D:\Tools\_scratch\chromite-text-clarity\shots\`(`before-*`, `after-*`, 3배 확대 비교 `crop-*.png`).

- 100%(`-Dsun.java2d.uiScale=1`), 기본값(자동): 모든 UI 글꼴(`defaultFont`, `Label`·`Tree`·`Table`·`MenuBar`·`Menu`·`TitlePane`)이 맑은 고딕 12, 다듬기는 LCD 그대로.
  상태 표시줄·그리드·메뉴·도구 막대 글자의 획이 한 화소에 맞는다(`crop-statusbar-dark.png`, `crop-grid-dark.png`, `crop-menu-dark.png`, 라이트 `crop-*-light.png`).
  1280 창에서 잘리는 칸이나 겹치는 글자는 없다(맑은 고딕이 Pretendard보다 조금 넓다).
- 실행 중 Options의 UI 글꼴 Pretendard 적용 → 테마를 다시 설치해 모든 글꼴이 Pretendard 12, 다시 자동 → 맑은 고딕(`after-pretendard-dark-100.png`).
  Options > General에 "UI 글꼴" 행과 안내 글(`after-options-dark-100.png`).
- 150%(`uiScale=1.5`), 자동: Pretendard 12 그대로(`after-dark-150.png`, `crop-statusbar-dark-150.png`).
- 테마 값: `Chromite.secondaryForeground` `#9A9DA4`, `Label.disabledForeground`·자리 표시 글·비활성 창 제목 `#777A81`(다크)를 앱에서 읽음.
- **확인하지 못함:** 회사 VDI 화면(원격 세션)에서의 모습, 실제 RDP 재현, macOS·영어 Windows(Segoe UI)의 시스템 글꼴 모습.

### 적용 현황 — 운영체제 UI 글꼴 13 (2026-11-12, 앱 변경 #67)

사용자 결정(2026-11-12, 회사 100%·집 150%에서 써 본 뒤): "아무래도 폰트를 변경해야 할것 같다. vscode와 같이 변경한다. windows는 Segoe UI를 쓰고 있고
mac은 San Francisco를 쓰고 있는걸로 알고 있다. 폰트 사이즈도 한단계만 올린다". UI 글꼴을 운영체제 글꼴 하나로, 크기를 12에서 13으로 바꿨다.
#43의 UI 글꼴 선택(자동·Pretendard·시스템 글꼴)과 번들 Pretendard는 지웠다(메인 세션 결정, 저장된 `ui.font`는 무시).

#### 정한 것

| 운영체제 | UI 글꼴 | 한글 | 어떻게 |
|---|---|---|---|
| Windows | **Segoe UI** 13 | 맑은 고딕(JDK 대체 글꼴) | `[win]defaultFont = 13 "Segoe UI"`. FlatLaf의 Windows 시스템 글꼴은 `win.messagebox.font`라 한국어 Windows에서는 맑은 고딕이 된다. 사용자가 Segoe UI를 골랐으므로 이름을 적었다. 설치되지 않았으면(오래되거나 줄인 Windows) FlatLaf가 그 이름을 건너뛰고 시스템 글꼴을 쓴다(`ActiveFont.derive`: 대체 글꼴 Dialog가 나오면 다음 후보, 없으면 시스템 글꼴의 크기만 바꿈) |
| macOS | 시스템 글꼴 13 | Apple SD Gothic Neo(JDK 대체) | `defaultFont = 13`(크기만): FlatLaf 3.8은 macOS 10.15 이상 + JetBrains Runtime 11 이상에서 `.AppleSystemUIFont`(San Francisco)를 쓴다(`FlatLaf.initDefaultFont`, JBR-1915). Chromite는 JBR 25로 돈다 |
| Linux | 데스크톱 글꼴 13 | 대체 글꼴 | `defaultFont = 13`, FlatLaf `LinuxFontPolicy` |

- 크기를 테마 properties에 적은 이유: 밀도 B의 치수가 글자 줄 높이에 맞춰져 있어, 운영체제마다 다른 기본 크기(Windows 메시지 상자 글꼴 12, macOS 13)가 아니라
  한 크기를 쓴다. VS Code UI도 13px다.
- semibold: 테마의 `semibold.font`를 지우고 FlatLaf 기본값을 쓴다(Windows `"Segoe UI Semibold"`, macOS `"HelveticaNeue-Medium"`, Linux Lato/Ubuntu Medium 등).
  **Segoe UI Semibold에는 한글이 없고**, 합성 글꼴의 대체 글꼴은 PLAIN 스타일(semibold 패밀리)을 따라 보통 굵기 맑은 고딕이 된다(측정 아래).
  그래서 한국어 화면에서는 기존 판단(`UiSemibold`, 전 `ChromiteTheme.isInSemiboldFont`)이 semibold를 기본 글꼴의 BOLD로 바꾼다:
  라틴 Segoe UI Bold + 한글 맑은 고딕 Bold. Segoe UI Semibold 옆 보통 굵기 한글은 머리말에서 굵기가 어긋나 보여 BOLD를 택했다(semibold와 bold 사이에
  Hangul semibold가 없는 맑은 고딕에서는 이것이 가장 가까운 짝이다). 영어 화면은 Segoe UI Semibold 그대로다.
- 업데이트 진행 창(OSGi 없이 도는 `UpdateWindowApp`)도 같은 테마 properties를 써서 같은 글꼴이다. semibold 판단도 하도록 그 판단을 `Log`를 쓰지 않는
  `UiSemibold`로 옮겼다(그 창의 이름·머리말·현재 단계가 semibold다).
- 시작 화면 글자는 고정 크기 12(`StartupSplash.TEXT_SIZE`)였다. UI 글꼴을 그대로 쓰게 바꿔 13이 됐다(고정값이 UI 글꼴 크기 변경을 따르지 않은 것이 원인).

#### 크기가 바뀐 글꼴

모두 `defaultFont`에서 FlatLaf가 파생하므로 함께 바뀐다(측정, Windows 한국어; 영어는 semibold만 다름):

| 글꼴 | 전(100% 자동 / 150% 자동) | 후 |
|---|---|---|
| `defaultFont`와 `Label`·`Tree`·`Table`(= `font.grid`)·`TableHeader`·`MenuItem`·`TitlePane` 등 모든 `*.font` | 맑은 고딕 12 / Pretendard 12 | Segoe UI 13 |
| `semibold.font` | 맑은 고딕 Bold 12 / Pretendard SemiBold 12 | 한국어: Segoe UI Bold 13, 영어: Segoe UI Semibold 13 |
| `monospaced.font`(`font.code`: Messages·값 패널·오류 상세·DDL 미리 보기 등 `monospaced` 스타일 클래스) | JetBrains Mono 12 | JetBrains Mono 13 |
| `small` / `medium` / `large` / `mini` | 10 / 11 / 14 / 9 | 11 / 12 / 15 / 10 |
| `h1` / `h2` / `h3`(`font.title`) / `h4` | 24 / 18 / 15 / 12 | 25 / 19 / 16 / 13 |
| 시작 화면 글자 | 12(고정) | 13(UI 글꼴) |
| 코드가 UI 글꼴에서 만든 글꼴(ERD 그림 `ErdRenderer`, 실행 계획 그림·차트·공간 보기의 −1 글자, Options 목록 묶음 이름 −1, 굵게 만든 행 등) | 12 기준 | 13 기준(상대값이라 따라감) |

바뀌지 않는 것: SQL 편집기 글꼴(`EditorFont`, Cascadia Mono 13, 사용자 설정). 코드에서 고정 크기로 만든 UI 글자는 시작 화면 하나였다
(`deriveFont(`·`new Font(` 검색: 나머지는 모두 UI 글꼴에서 상대 크기이거나 고정폭 글꼴의 크기를 그 컴포넌트 글꼴에서 가져옴).

#### 실제로 그리는 글꼴(측정)

측정 방법: 앱 안의 테스트 에이전트(Attach API)가 `sun.font` 패키지를 열고, 각 `UIManager` 글꼴의 `Font2D`(합성 글꼴)에서 글자의 글리프 코드를 얻어
`CompositeStrike.getStrikeForGlyph`로 그 글자를 그리는 물리 글꼴과 파일을 찾고, 화면 배율의 LCD 다듬기 strike가 Windows GDI 래스터라이저를 쓰는지
(`FileFontStrike.useNatives`)를 읽었다. 100%(`-Dsun.java2d.uiScale=1`)와 150%에서 같다. 다듬기는 운영체제 설정 그대로 LCD HRGB, 대비 120.

| 글꼴 | 'A' | '가' |
|---|---|---|
| 후 `defaultFont` Segoe UI 13 | Segoe UI (`segoeui.ttf`, GDI) | 맑은 고딕 (`malgun.ttf`, GDI) |
| 후 semibold, 한국어 화면 | Segoe UI Bold (`segoeuib.ttf`, GDI) | 맑은 고딕 Bold (`malgunbd.ttf`, GDI) |
| 후 semibold, 영어 화면 | Segoe UI Semibold (`seguisb.ttf`, GDI) | 맑은 고딕 (`malgun.ttf`, GDI): 보통 굵기 |
| 전 150% `defaultFont` Pretendard 12 | Pretendard (등록한 임시 파일, **FreeType**) | Pretendard (**FreeType**) |
| 전 100% `defaultFont` 맑은 고딕 12 | 맑은 고딕 (GDI) | 맑은 고딕 (GDI) |
| `monospaced.font` JetBrains Mono(전후 같음) | JetBrains Mono (JBR에 든 파일, FreeType) | 맑은 고딕 (GDI) |

선명도(같은 글줄을 다크 바탕에 LCD로 그려 글자 화소 중 덮임 20–80% 번짐과 80% 넘는 꽉 찬 획의 비율, "적용 현황 — 글자 선명도와 대비"와 같은 방법, `probe\SharpProbe`):

| 글꼴 | 100% 라틴 번짐/꽉 참 | 100% 한글 | 150% 라틴 | 150% 한글 |
|---|---|---|---|---|
| 전 Pretendard 12(번들, FreeType) | 59.9 / 15.2 | 64.8 / 4.2 | 55.7 / 27.8 | 51.4 / 26.4 |
| 전 맑은 고딕 12(100% 자동) | 54.5 / 18.4 | 56.8 / 22.3 | 50.3 / 27.3 | 45.8 / 32.1 |
| 후 Segoe UI 13 + 맑은 고딕 | **40.9 / 31.0** | 50.0 / 21.8 | 46.5 / 36.8 | 44.7 / 34.3 |

글줄 높이(`FontMetrics.getHeight`)는 합성 글꼴의 첫 글꼴(Segoe UI) 값이다: 13px 18(위 14, 아래 4). 한글과 라틴이 섞인 줄은 같은 기준선에 놓인다(캡처).

#### 크기 13에서 맞춘 것

글자 줄이 15(Pretendard 12)·17(맑은 고딕 12)에서 18이 되어, 글자 줄로 높이가 정해지는 치수가 밀도 B·C의 목표보다 커지지 않게 여백을 줄였다.
고정 높이(트리 22, 그리드 24·머리글 26, 탭, 상태 표시줄 26, 제목 줄 30·40)는 18이 들어가므로 그대로다.

| 파일 | 변경 |
|---|---|
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | `defaultFont = 13`, `[win]defaultFont = 13 "Segoe UI"`, `semibold.font` 줄 삭제. `@componentMargin = 4,8,4,8`, `Button.margin = 4,14,4,14`(컨트롤 28 = 18 + 4 + 4 + 테두리 2), `@menuItemMargin = 2,8,2,8`(메뉴 항목 22) |
| `ChromiteCompact.properties` | `@menuItemMargin = 1,8,1,8`(메뉴 항목 20), `Chromite.size.editorToolBar.comboBox.padding = 2,8,2,8`(편집기 선택 상자 24) |
| `SqlEditorToolBar` | 글자 단추 "실제 계획"·커밋 방식에 `UiIcons.useToolButtonMargin`: 18 + 4 + 4 = 26이 되던 것을 아이콘 단추와 같은 24로(주 도구 막대의 Chromite Agent 단추와 같은 방식) |
| `StartupSplash` | 고정 크기 12 대신 UI 글꼴 |
| `UiIcons` | 주석(13px 글자 줄 18은 위 여백에서 2를 뺌) |

측정(Windows 11, 창 1280×800, 빈 작업 공간, PostgreSQL 연결 하나에 한글·라틴 섞인 스키마·표, 100%와 150%, 한국어·영어, 라이트·다크; 전은 #67 직전 devel):

| 항목 | 전 100%(맑은 고딕 12) | 전 150%(Pretendard 12) | 후(Segoe UI 13) | 토큰 |
|---|---|---|---|---|
| UI 글꼴 한 줄 | 17 | 15 | 18 | — |
| `font.code` 한 줄 | 17 | 17 | 18 | — |
| 입력·콤보·단추·스피너(주 창, 옵션, 연결 대화상자) | **30** | 28 | 28 | 28 |
| 주 도구 막대 / 편집기 도구 막대 | 32 / **34** | 32 / **33** | 32 / 32 | 32 |
| "실제 계획"·"자동 커밋" 글자 단추 | 25 | 23·24 | 24 | 24 |
| 트리 행 / 그리드 행 / 머리글 | 22 / 24 / 26 | 22 / 24 / 26 | 22 / 24 / 26 | 같음 |
| 도구 창 머리말 / 상태 표시줄 / 쿼리 상태 칸 | 35 / 26 / 26 | 35 / 26 / 26 | 35 / 26 / 26 | 같음 |
| 메뉴 항목 | — | — | 22 | 22 |
| 확인란 | — | — | 22 | — |
| 결과 보기 아래 줄의 글자 토글(값 패널·레코드…)과 링크 단추(저장·취소) | 25 | 23 | 26 | (토큰 없음) |
| 촘촘한 모드: 트리 / 그리드·머리글 / 편집기 선택 상자 / 도구 막대 / 상태 표시줄 / 메뉴 항목 | | | 20 / 22·24 / 24 / 26·28 / 24 / 20 | 같음 |

- 전 100%(자동 = 맑은 고딕 12)는 여백이 Pretendard 12의 15에 맞춰져 있어 컨트롤 30, 편집기 도구 막대 34로 목표보다 컸다. 이번에 28·32로 돌아왔다.
- 결과 보기 아래 줄의 글자 토글과 링크 단추는 아이콘이 없는 글자 단추라 글자 줄을 따라 26(전 23–25)이 되고 그 줄이 3 높아졌다. 잘림이 없고 같은 줄의 단추가
  모두 같은 높이라 그대로 두었다.
- 잘림 검사: 보이는 모든 `JLabel`·`AbstractButton`의 글자를 `SwingUtilities.layoutCompoundLabel`로 다시 배치해 줄임표로 잘리거나 글자 줄이 안쪽 높이보다 큰 것을
  셌다. 주 창(개체 탐색기·등록된 서버 트리, 쿼리 창, 결과 그리드, Query Library, Chromite Agent 패널, 도구 막대, 상태 표시줄), 옵션(General), 연결 대화상자,
  메시지 상자(제목 줄 30), 업데이트 진행 창, 촘촘한 모드에서 100%·150% × 한국어·영어 × 라이트·다크 모두 **0**.
- 업데이트 진행 창: 앱 안 경로(`UpdateProgress`와 같은 `UpdateWindow`)를 캡처했고, 따로 도는 경로(`UpdateWindowApp.installLookAndFeel`)는 같은 jar로 Look and Feel만
  설치해 `defaultFont` Segoe UI 13, 영어 `semibold.font` Segoe UI Semibold 13, 한국어 `semibold.font` Segoe UI Bold 13(`h2` Segoe UI Bold 19)을 읽었다.
- 캡처: `D:\Tools\_scratch\system-ui-font\shots\`(`before-ko-{100,150}-main-dark`, `after-{ko,en}-{100,150}-{main,options}-{light,dark}`, `-connection-`, `-msgbox-`,
  `-update-`, `-splash`, `-compact-dark`, 확대 비교 `crop-{tree,grid}-dark-{100,150}.png`).
- **확인하지 못함:** macOS(실행할 수 없음. FlatLaf 3.8 소스로만 판단: `.AppleSystemUIFont` 13, semibold는 FlatLaf 기본 `HelveticaNeue-Medium`이고 한글이 없어 한국어 화면에서는
  시스템 글꼴의 BOLD), Linux, Segoe UI가 없는 Windows(코드 경로만), 회사 VDI 화면.

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
- **쿼리 창 도구 막대 위치**: 처음(2026-11-06)에는 연결·DB가 창마다 달라 편집기마다 위에 두었다.
  **2026-11-07 사용자 결정으로 SSMS처럼 바꿨다**: 메인 창 위에 도구 막대 두 줄(1줄 Standard, 2줄 SQL Editor)을 두고, 2줄은 활성 쿼리 창에 적용하며
  쿼리 창마다의 도구 막대는 없앴다. 편집기가 한 줄 높아지고 단추가 늘 같은 자리에 있다. 연결·DB 콤보는 2줄에서 활성 창의 것으로 바뀌므로 창마다 다른 연결이 그대로 보인다.
  쿼리 창이 아닌 문서가 활성이거나 문서가 없으면 2줄은 보인 채 컨트롤이 모두 꺼진다(SSMS). 구현은 [swing-frontend.md](swing-frontend.md) "SQL Editor 도구 막대".
- **라이트 머리말**: New UI Light의 기본은 어두운 주 도구 막대지만, SSMS 라이트와 이어지고 밝은 화면에서 덜 튀도록 "Light with Light Header" 값(`MainToolbar.background` `#F7F8FA`)을 쓴다.
- **결과 탭 높이 32**: 결과가 쿼리 창 아래 붙는 SSMS 배치에서 40은 세로 공간을 많이 쓴다. 도구 창 머리말(40)과 구분되는 낮은 탭으로 둔다. (2026-11-07 밀도 B로 28)
- **그리드 행 27**: DataGrip 측정값을 따른다. SSMS(≈20)보다 성기지만 모양 기준이 DataGrip이다. 촘촘한 모드에서 낮춘다. (2026-11-07 밀도 B로 24, 2026-11-08 촘촘한 모드 22)
- **기본 밀도 B**(2026-11-07 사용자 결정): DataGrip 기본(A)은 SSMS 사용자에게 성기다. 같은 창 높이에서 트리 행이 약 10%, 그리드 행이 약 20% 더 보이는
  B를 기본으로 하고, DataGrip Compact 수준(C)은 선택으로 남긴다. 치수를 테마 키 한곳에 모아 C가 값 교체로 끝나게 했다(2026-11-08 View > Compact Mode).
- **촘촘한 모드는 도구 창·도구 막대·행만**(2026-11-08): 대화상자의 입력·선택 상자(size.control 28), 단추, 제목 표시줄(40), 글꼴은 바꾸지 않는다.
  시안 C가 이 범위였고, 입력 칸까지 낮추면 12px 글자 위아래 여백이 4·3보다 줄어 한글이 답답해진다. 실행 중 전환은 테마 전환과 같은 길(Look and Feel 다시 설치)을 쓴다.
- **UI 글꼴 Pretendard**(2026-11-07 사용자 결정): Inter + 맑은 고딕 대체는 한글과 라틴 글자의 굵기·크기·기준선이 달랐다. Pretendard는
  한글과 라틴 글자를 한 글꼴로 맞춰 그린다(라틴 글자는 Inter 기반). 13px 한 줄 높이는 17로 Inter(JBR)와 같다.
- **UI 글꼴 12**(2026-11-07 사용자 요청 "앱 기본 폰트 사이즈를 한단계 줄인다"): 밀도 B의 행·막대 높이는 그대로 두고 글자만 줄인다.
  높이가 고정된 곳(트리·그리드·Properties 행, 탭, 머리말, 상태 표시줄)은 값이 그대로이고, 글자 줄에서 높이가 정해지는 컨트롤·메뉴 항목만 여백을 늘려 28·22에 맞췄다.

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
- 그리드 숫자의 고정폭 숫자(`tnum`): JBR API(`jbr-api` `FontExtensions`) 의존성을 넣을지, 숫자 열만 고정폭으로 할지(2026-11-07 "적용 현황 — 그리드 글꼴 Pretendard").
- ~~촘촘한 모드 C(보기 메뉴 전환, P2)~~: 2026-11-08 View > Compact Mode("적용 현황 — 촘촘한 모드").
- 연결별 색(SSMS Color, DataGrip 데이터 소스 색). (Islands는 2026-11-06, 기본 밀도 B는 2026-11-07 적용)

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
- SSMS 기본 코드 색(Color coding in query editors): https://learn.microsoft.com/en-us/ssms/scripting/color-coding-in-query-editors
- T-SQL 논리 연산자: https://learn.microsoft.com/en-us/sql/t-sql/language-elements/logical-operators-transact-sql
- Cascadia Code/Mono 릴리스 2407.24: https://github.com/microsoft/cascadia-code/releases/tag/v2407.24

## 변경 이력

- 2026-11-06 최초 작성. 사용자 결정(배치·동작 = SSMS, 모양 = DataGrip New UI 다크·라이트)에 따라 요소별 비교, P0 목록, 디자인 토큰과 FlatLaf 키 대응,
  SQL 구문 색, 아이콘 요구 사항(100개 개념, 색 규칙, 공급자 로고 정책)을 정리.
- 2026-11-06 적용: P0 전체와 P1 일부를 Swing 프런트엔드에 적용하고 "적용 현황"(측정값, 아이콘 팩 2종과 모델 아이콘 대응, 글꼴과 한글 대체,
  Swing/FlatLaf로 못 한 것)을 추가. 결정한 아이콘 세트·글꼴을 "미룬 결정"에서 뺌.
- 2026-11-06 Islands 적용: 사용자 의견에 따라 기본 모양을 DataGrip Islands로 바꿈(캔버스 위 둥근 카드, 알약 탭, Islands 팔레트).
  "적용 현황 — Islands 적용" 절 추가, 배경과 결정·Islands 항목·`radius.island`·미룬 결정 갱신.
- 2026-11-06 P1 적용: "적용 현황 — P1 결과" 절 추가(그리드 머리글 원인, 제목 표시줄·메뉴, 탭, F5, 쿼리 상태 줄, Properties, 자동완성, 스크롤바), 남은 일 갱신,
  아이콘 팩 파일 수 갱신.
- 2026-11-06 편집기 글꼴과 SQL 색: 사용자 결정에 따라 편집기 글꼴을 Cascadia Mono 13(번들, OFL)으로, 구문 색을 SSMS 기본 색(숫자는 연두색)으로 바꿈.
  "SQL 구문 색" 표를 SSMS 근거로 다시 쓰고 `font.editor` 토큰, "적용 현황 — 편집기 글꼴과 SQL 색" 절(글꼴 결정·라이선스·대체 순서, 방언 기반 단어 분류,
  Options의 Editor·Editor Colors 탭, 확인) 추가.
- 2026-11-06 화면 문구 정리(사용자 요청): 사용자에게 보이는 문구에 다른 제품 이름(SSMS, DataGrip, Visual Studio)을 쓰지 않는다. 아이콘 팩 이름을 "Duo-tone", "Full color"로, Editor Colors 단추를 "Reset to Defaults"로 바꿈. 설계 근거로서의 제품 이름은 이 문서와 코드 주석에 남긴다.
- 2026-11-06 도구 막대 확장(사용자 결정 "가"): 주 도구 막대(2절)와 쿼리 창 도구 막대(7절)의 목표 구성을 SSMS Standard·SQL Editor 배치로 바꾸고 적용 현황 9·11행, 아이콘 팩 파일 수(61 → 74) 갱신.
- 2026-11-07 UI 글꼴 Pretendard 13과 기본 밀도 B(사용자 결정): 글꼴 토큰, 치수 토큰(B 값, 처음 값, 촘촘한 모드 C 값과 바꾸는 방법),
  14절 표, 설계 결정, 미룬 결정 갱신. "적용 현황 — Pretendard와 밀도 B" 절(바뀐 파일, 전후 측정) 추가.
- 2026-11-07 UI 글꼴 12(사용자 요청 "앱 기본 폰트 사이즈를 한단계 줄인다"): `font.ui` 13 → 12, 컨트롤·메뉴 여백을 다시 맞춤(밀도 B 높이 유지).
  글꼴 토큰(`font.ui.small`·`font.title`을 FlatLaf 상대 크기 실측으로 고침, `font.code`가 `defaultFont` 크기를 따름), 치수 토큰, 14절 표, 설계 결정 갱신.
  "적용 현황 — UI 글꼴 12" 절(전후 측정, 1px 차이 항목) 추가.
- 2026-11-07 그리드 글꼴 Pretendard(사용자 요청 "쿼리결과 그리드의 폰트와 사이즈도 pretendard 12로 맞춘다"): `font.grid` 토큰 추가, `font.code`의 쓰는 곳 갱신,
  8절 표 갱신, "적용 현황 — 그리드 글꼴 Pretendard" 절(따를지 정한 것, 비례 숫자와 `tnum`, 측정) 추가.
- 2026-11-07 결과 창 기본 높이 280(사용자 결정): "결과 창 기본 높이" 절 추가, 밀도 B 절의 결정 대기 문장 갱신.
- 2026-11-07 Islands 색 표에 테이블 저장 방식 글자색 토큰(`Chromite.Tree.storage*Foreground`) 추가([altibase.md](altibase.md)).
- 2026-11-07 도구 막대 두 줄(사용자 결정): 쿼리 창 도구 막대를 메인 창 둘째 줄의 SQL Editor 도구 막대로 옮김. 2절 구성·높이, 7절 제목·위치·높이,
  "설계 결정"의 쿼리 창 도구 막대 위치, 적용 현황 11행, `size.toolbar.editor` 설명 갱신.
- 2026-11-07 쿼리 상태 줄의 커밋 방식 칸(사용자 요청): 9절 쿼리 상태 줄 행 갱신.
- 2026-11-07 실행 계획 세 보기(사용자 결정): "적용 현황 — 실행 계획 그림" 절(보기 전환, 연산자 상자·화살표·비싼 연산자·경고 배지·선택 색과 치수) 추가, 아이콘 팩 파일 수(74 → 92) 갱신.
- 2026-11-07 열 필터(사용자 요청 10): 8절 머리글 목표(늘 보이는 흐린 깔때기·정렬 아이콘), 색 토큰 `grid.headerIcon`·`grid.headerIconActive`,
  치수 토큰 `size.gridHeader.*`(필터 막대 여백 삭제), `font.code` 쓰는 곳 갱신, "적용 현황 — 열 필터" 절 추가.
- 2026-11-08 촘촘한 모드(사용자 결정 2026-11-07 후속 #46): View > Compact Mode. 14절 표, "치수 토큰"의 C 값과 실행 중 전환 설명,
  `size.toolbar.editor` 행(선택 상자 여백 키), 설계 결정(촘촘한 모드 범위), 미룬 결정 갱신. "적용 현황 — 촘촘한 모드" 절(구조, B·C 측정) 추가.
- 2026-11-08 9절: 쿼리 상태 칸을 주 상태 표시줄로 옮기고 표시줄 전체를 연결 색으로(앱 변경 #2). 치수 토큰 `size.statusLine.query` 삭제(`size.statusBar`를 씀).
- 2026-11-08 마우스 올림 잔상(앱 1차 #10): "마우스 올림 다시 그리기" 절 추가(원인, 세 트리의 다시 그리는 영역을 위아래 1px 넓힘, 측정).
- 2026-11-11 내용 영역의 포커스 테두리 제거(앱 변경 #37): "포커스 표시 — 내용 영역 테두리 없음" 절 추가.
- 2026-11-11 대화상자 제목 줄 30, 메인 창 40(앱 변경 #56): "대화상자 제목 줄" 절 추가.
- 2026-11-12 글자 선명도와 대비(앱 변경 #43·#55): "적용 현황 — 글자 선명도와 대비" 절(다듬기 방식·글꼴별 그리는 길 측정, UI 글꼴 선택과 기본값 자동,
  두 테마의 글자 대비 측정표) 추가. Islands 표의 글자 색, `font.ui` 토큰, SQL 구문 색 표의 줄 번호, 9절 주 상태 표시줄 글자 갱신.
- 2026-11-12 운영체제 UI 글꼴 13(앱 변경 #67, 사용자 결정 "vscode와 같이"): 글꼴 토큰(Windows Segoe UI + 한글 맑은 고딕, macOS 시스템 글꼴, 크기 13),
  8·14절 표, "적용 현황 — 운영체제 UI 글꼴 13" 절(정한 것, 크기가 바뀐 글꼴, 물리 글꼴과 GDI 측정, 선명도, 여백과 높이 측정) 추가. 번들 Pretendard 삭제.
