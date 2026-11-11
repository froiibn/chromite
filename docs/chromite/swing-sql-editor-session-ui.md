# 쿼리 창 세션 표시와 실행 중 표시 (앱 변경 #33, #44, #40, #50, #57)

기업 환경 실테스트(2026-11-09, 2026-11-11)에서 나온 쿼리 창 문제 다섯 가지를 고친 기록이다.
편집기 전체 구조는 [swing-frontend.md](swing-frontend.md), 실행 엔진 연결은 [swing-query-commands.md](swing-query-commands.md),
자동완성은 [swing-sql-completion.md](swing-sql-completion.md), 단축키 표는 [shortcuts.md](shortcuts.md)에 있다.

## 목적

- **현재 DB 따라가기(#33):** 쿼리 창에서 `USE`, `SET search_path`, `ALTER SESSION SET CURRENT_SCHEMA`를 실행하면 SQL Editor 도구 막대의
  DB/스키마 선택, 상태 표시줄의 DB 칸, 자동완성, Chromite Agent 문맥 기본값이 세션의 실제 값(`DB_NAME()` 등)을 따른다.
- **실행 중 표시(#44):** 쿼리가 실행 중인지 분명히 보인다. 상태 표시줄 상태 칸의 회전 스피너와 "실행 중 2/3 · 00:00:12", 쿼리 창 탭의 스피너,
  편집기에서 지금 실행 중인 문장의 바탕색과 줄 번호 옆 아이콘(끝나면 지움, 오류 난 문장은 오류 색으로 남김).
- **자동완성 창 닫기(#40):** 자동완성 목록이 Esc뿐 아니라 목록 밖을 누르거나 휠을 돌리거나 초점을 잃으면 닫힌다.
- **단축키를 도구 설명에(#50):** 결과 창 숨기기(Ctrl+R, SSMS와 같음)는 이미 있었지만 사용자가 몰랐다. 닫기 단추와 단축키가 있는 아이콘 단추의
  도구 설명이 단축키를 함께 보인다("닫기 (Ctrl+R)", macOS "⌘R").
- **닫기 단추 모양(#57):** 도구 창 머리글과 결과 영역의 숨기기 단추가 "−"라서 Windows 사용자가 최소화로 읽었다. "×"(아이콘 팩의 close)로 바꿨다.

## 구조

모두 `com.seizia.chromite.ui.swing` 번들이고, #33의 PostgreSQL 수정만 `com.seizia.chromite.ext.postgresql`이다.

| 클래스 | 역할 |
|---|---|
| `SqlEditorPanel` | `QueryListener`가 문장 뒤(조회·DML이 아닌 문장)와 실행 끝에 세션의 현재 DB/스키마를 서버에서 다시 읽음(`readSessionDefaults`). 실행 밖에서 트랜잭션이 끝나면 다시 읽음(`sessionReadPending`). 실행 중 상태 글자(`showRunState`), 탭 스피너(`getTabIcon`), 실행 중·실패 문장 표시(`showRunningStatement`, `markFailedStatement`, `clearStatementMarks`, 실행 중 편집 따라가기 `mapOffset`), 편집 영역의 아이콘 열 |
| `PostgreExecutionContext` | `refreshDefaults`: 읽은 뒤에 값을 바꿈, `SET ROLE`은 세션을 시작할 때만 |
| `SpinnerIcon` (새) | 회전 도넛 스피너 아이콘(Java2D, 테마 강조 색), 운영체제 움직임 줄이기 설정이면 돌지 않음 |
| `QueryStatusLine` | 실행 중 상태 아이콘을 스피너로 |
| `SqlCompletionPopup` | 목록이 보이거나 계산하는 동안 앱의 마우스 누름·휠을 듣고(`listenOutside`) 목록 밖이면 닫음 |
| `UiIcons` | 아이콘 단추(`createActionButton`, `createToolButton`)의 도구 설명이 동작의 단축키를 함께 보임(`ShortcutTipButton`, `withShortcut`) |
| `ToolWindow` | 숨기기 단추: 아이콘 `close`(×), 이름 "닫기", 도구 창 토글의 단축키를 도구 설명에 |
| `EditorMessages`, `CoreMessages` | `editor_sql_executing_statement`, `editor_sql_executing_elapsed`, `editor_sql_state_elapsed`, `common_tip_shortcut`, `toolwindow_close`(새). 쓰지 않게 된 `editor_sql_executing`, `toolwindow_hide` 삭제 |
| `tools/branding/gen_ui_icons.py` | 쓰지 않게 된 `hide`("−") 아이콘 삭제(팩 두 개 × 밝은·어두운 SVG 네 개도) |

## 현재 DB 따라가기 (#33)

**근본 원인:** 쿼리 창의 DB/스키마 선택·상태 표시줄 DB 칸·자동완성·Agent 문맥은 모두 실행 문맥(`DBCExecutionContextDefaults`)이 기억한 값을 읽는다.
그 값은 세션을 열 때(`refreshDefaults(…, true)`)와 선택 상자로 바꿀 때만 정해지고, 사용자의 문장이 세션을 바꾼 뒤에는 아무도 서버에서 다시 읽지 않았다.
실행 뒤의 `refreshSchemas`는 기억한 값을 다시 그릴 뿐이었다. SWT 편집기는 실행이 끝나면 `DBUtils.refreshContextDefaultsAndReflect`를 불렀지만
(`SQLEditor.refreshContextDefaults`, "Refresh active schema after SQL execution", SWT 기본값은 꺼짐) Swing으로 옮길 때 빠졌다.
그래서 SQL Server `USE`만이 아니라 같은 원인으로 MySQL·MariaDB `USE`, PostgreSQL `SET search_path`, Oracle `ALTER SESSION SET CURRENT_SCHEMA`도
표시가 바뀌지 않았다.

**고침:** 추측(문장 글자로 `USE`를 찾기)이 아니라 세션의 실제 값을 서버에서 읽는다.

- 문장 하나가 끝나면, 조회(`SELECT`)나 DML(`INSERT`/`UPDATE`/`DELETE`/`MERGE`)이 아니고 오류가 없을 때 그 세션에서 다시 읽는다. 다음 문장의 기록(쿼리 기록의 DB)도 맞게 된다.
  조회·DML은 세션의 DB를 바꾸지 않으므로 긴 스크립트에 왕복을 더하지 않는다.
- 실행이 끝나면(`onEndSqlJob`), 마지막 문장 뒤에 읽지 않았으면 한 번 더 읽는다. 일부가 실패한 배치(`USE x` 뒤 오류)와 `SELECT set_config('search_path', …)` 같은 경우다.
- 실행 밖에서 트랜잭션이 끝나면(상태 줄, Query 메뉴, 유휴 트랜잭션 알림) 다시 읽는다. PostgreSQL은 롤백한 트랜잭션의 `SET search_path`를 되돌린다.
- 읽기는 실행 작업 스레드에서 그 세션의 연결로 한다(`DBCExecutionPurpose.META`). 실패하면 기록만 하고 알던 값을 둔다.
- 저장 프로시저 안의 `USE`는 프로시저가 끝나면 세션에 남지 않는다. 서버 값을 읽으므로 표시도 바뀌지 않는다.
- 각 DB의 읽기: SQL Server `SELECT db_name(), schema_name(), original_login()`(테이블이 없는 SELECT라 암시적 트랜잭션을 시작하지 않음),
  MySQL·MariaDB `SELECT DATABASE()`, PostgreSQL `search_path`와 `current_schema()`, Oracle `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')`.

**PostgreSQL 수정(같은 원인의 다른 경로):** `PostgreExecutionContext.refreshDefaults`는 값을 먼저 지우고 읽었다. 오류로 중단된 트랜잭션에서는 읽기가 실패해 활성 스키마가 사라졌다.
이제 읽은 뒤에 바꾼다. 또 연결 설정의 역할(`SET ROLE`)을 읽을 때마다 다시 실행해 사용자의 `SET ROLE`을 되돌렸으므로, 세션을 시작할 때(`useBootstrapSettings`)만 한다.

**알려진 한계:** PostgreSQL 수동 커밋에서 스크립트가 `COMMIT`으로 끝나면 다시 읽는 SELECT가 드라이버의 `BEGIN`으로 새 트랜잭션을 연다.
쥔 것(트랜잭션 ID, 잠금)이 없어 열린 트랜잭션으로 보이지 않는 것이 [idle-transactions.md](idle-transactions.md)의 기준과 맞지만, 서버의
`idle_in_transaction_session_timeout`이 짧으면 그 세션을 끊을 수 있다.

## 실행 중 표시 (#44)

- **상태 칸:** 실행 중 아이콘은 회전 도넛 스피너(`SpinnerIcon`, 16, 1초에 한 바퀴, 테마 강조 색 호와 옅은 고리)다. 글자는 문장이 둘 이상이면 "실행 중 2/3 · 00:00:12",
  하나면 "실행 중 · 00:00:03", 연결 중·취소 중이면 "연결하는 중 · 00:00:01"이다. 실행 중에는 경과 시간 칸을 숨기고(같은 시간을 두 번 보이지 않음) 끝나면 전체 시간을 보인다.
- **번호:** 몇 번째 문장인지는 실행할 문장 목록에서 그 문장의 위치다. `GO 2`로 되풀이하는 문장은 번호가 같고, `@include` 파일의 문장은 include 명령의 번호에 머문다.
- **탭:** 실행 중인 쿼리 창의 탭 아이콘이 스피너다. 다른 탭을 보고 있어도 보인다. 탭에는 아이콘만 바꾼다(탭 메뉴·고정은 #42).
- **편집기:** 지금 실행 중인 문장의 줄 바탕을 강조 색(투명도 40)으로 칠하고 첫 줄의 줄 번호 옆 아이콘 열에 실행 아이콘을 둔다. 다음 문장으로 옮기고 실행이 끝나면 지운다.
  오류 난 문장은 오류 색 바탕과 오류 아이콘으로 다음 실행까지 남긴다(Messages의 오류를 누르면 같은 곳으로 간다).
  편집기는 그 문장으로 스크롤하지 않는다(사용자가 다른 곳을 편집 중일 수 있음, 정한 기본값).
- **실행 중 편집:** 편집은 지금처럼 막지 않는다. 실행이 시작된 뒤의 편집을 기록해 두고(`Execution.edits`) 문장 위치를 지금 글자 위치로 옮겨 표시한다.
  표시한 뒤의 편집은 줄 강조와 아이콘이 스스로 따라간다(문서 위치).
- **아이콘 열:** 편집 영역 줄 번호 옆에 아이콘 열을 늘 켠다(IDE의 중단점 열과 같은 자리). 실행 중에만 켜면 글자가 좌우로 움직이기 때문이다.
- **움직임 줄이기:** Windows "애니메이션 효과" 끔(`SPI_GETCLIENTAREAANIMATION`), macOS "동작 줄이기"(`NSWorkspace.accessibilityDisplayShouldReduceMotion`)면 같은 모양으로 멈춰 있다.
- Windows 작업 표시줄 진행 표시는 하지 않는다(사용자 "windows까지는 할 필요 없다").

## 자동완성 창 닫기 (#40)

**근본 원인:** 목록은 초점을 받지 않는 창이라 편집기가 초점을 잃을 때와 메인 창이 움직일 때만 닫혔다. 편집기 안, 빈 영역, 도구 막대, 도구 창 머리글, 상태 표시줄처럼
누르는 곳이 초점을 가져가지 않으면 편집기 초점이 그대로라 닫히지 않았다.

**고침:** 목록이 보이거나 계산 중인 동안만 앱 전체의 마우스 누름과 휠 이벤트를 듣는다(`Toolkit.addAWTEventListener`). 목록·설명 창 밖이면 닫는다(계산 중이면 취소).
이벤트를 소비하지 않으므로 편집기 안을 누르면 닫히고 커서도 그 자리로 간다. 목록 안의 누름·휠은 그대로 고르고 넘긴다.
다른 앱으로 가면 편집기가 초점을 잃어 닫힌다(그대로). 입력 중 자동으로 여는 동작은 바꾸지 않았다.

**다른 편집기 팝업:** 마우스 올림 설명은 Swing 도구 설명(`ToolTipManager`)이라 밖을 누르면 이미 닫힌다. 매개변수 힌트 창은 없다. 템플릿 메뉴는 `JPopupMenu`라 이미 닫힌다.

## 단축키를 도구 설명에 (#50)

**확인:** Ctrl+R(macOS ⌘R)은 View 메뉴의 Results 토글(`ToolWindow`의 단축키, 메뉴 가속키)이다. 편집기(RSyntaxTextArea 4.0.1의 기본 키 지도에 Ctrl+R 없음), 결과 그리드,
Messages, Object Explorer, 결과 탭 줄에 키가 갈 때 모두 보이기·숨기기가 바뀌는지 앱에서 확인했다(아래 확인 기록). 다른 명령이 Ctrl+R을 쓰지 않는다.
사용자가 몰랐던 것이 원인이라 찾기 쉽게 했다.

- `UiIcons`의 아이콘 단추는 동작에 단축키(`Action.ACCELERATOR_KEY`)가 있고 도구 설명에 그 글자가 없으면 "도구 설명 (단축키)"로 보인다. 도구 설명을 보일 때 만들므로
  동작이 설명을 나중에 바꿔도 단축키가 남는다. 단축키 글자는 `UiKeys.getText`(Windows "Ctrl+R", macOS "⌘R")다.
- 숨기기(닫기) 단추는 도구 창 토글의 단축키를 단추 동작에도 둔다(단추는 가속키를 묶지 않으므로 키는 그대로 View 메뉴로만 간다).
- 메뉴: View &gt; Results에 단축키 글자가 이미 보인다(메뉴 가속키).

## 닫기 단추 모양 (#57)

도구 창 머리글(Object Explorer, Registered Servers, Query Library, Properties, Chromite Agent 등 도킹 도구 창)과 결과 탭 줄 오른쪽의 숨기기 단추 아이콘을
`hide`("−")에서 `close`("×", 아이콘 팩에 이미 있던 Tabler x 모양, 밝은·어두운 두 팩)로 바꿨다. 동작은 그대로 숨기기다(View 메뉴로 다시 보임).
이름과 도구 설명은 "닫기 (단축키)" / "Close (shortcut)"다. 마우스 올림 상태는 다른 아이콘 단추와 같은 FlatLaf 도구 막대 단추 바탕이다.
`hide` 아이콘은 다른 곳에서 쓰지 않아 생성기와 리소스에서 지웠다.

## upstream 대비 변경점

- SWT 편집기는 실행 뒤 기본 DB/스키마 다시 읽기가 설정(기본 꺼짐)이었다. Swing은 설정 없이 늘 읽는다(사용자 요구 "실제 세션 값과 콤보가 늘 같아야 함").
- `PostgreExecutionContext.refreshDefaults`: 읽은 뒤 바꾸기, `SET ROLE`은 시작할 때만.
- 나머지(스피너, 문장 표시, 바깥 누름으로 닫기, 도구 설명 단축키, 닫기 아이콘)는 Swing 프런트엔드에만 있다.

## 확인 기록

2026-11-11, Windows 11, 150%(일부 100%), JBR 25, 빈 작업 공간과 `CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트(OS 입력 없음).
Docker가 멈춰 있어 SQL Server·Oracle 컨테이너 대신 D:의 MariaDB 11.8과 PostgreSQL 17을 임시 서버로 띄워 확인했다.

- #33 MariaDB: `USE db2` 뒤 선택·상태 줄·문맥 기본값 모두 db2. `USE db1; SELECT 1; USE db2; SELECT 잘못된 열`(마지막 문장 오류) 뒤에도 db2.
- #33 PostgreSQL: `SET search_path TO s2` → s2. `BEGIN; SET search_path TO s1; ROLLBACK` → s2 그대로.
  `SELECT set_config('search_path','s1',false)` → s1(실행 끝 읽기). `DO` 블록 안의 `set_config(…, true)`(트랜잭션 안에서만, 프로시저 안 USE와 같은 경우) → 표시 그대로.
  `BEGIN; SET search_path TO s2; SELECT 잘못된 열`(중단된 트랜잭션, 실행 끝 읽기 실패) → 알던 s2 유지, 다음 `ROLLBACK` → s1.
- #33 SQL Server·Oracle: Docker가 멈춰 확인하지 못함(보류).
- #44: 5문장 스크립트 실행 중 상태 칸 "실행 중 1/5 · 00:00:00" → "실행 중 3/5 · 00:00:02", 아이콘·탭 아이콘 `SpinnerIcon`, 실행 중 줄 1 → 3, 경과 시간 칸 숨김.
  끝난 뒤 상태 아이콘·탭 아이콘 원래대로, 실행 중 표시 없음, 오류 난 4번 줄에 오류 바탕과 아이콘, 경과 시간 칸 "00:00:03.051".
- #40: 목록을 띄운 뒤 편집기 안 누름(닫힘, 커서 14 → 22), 상태 표시줄·도구 막대·Object Explorer 누름, 편집기 휠(모두 닫힘), 목록 안 누름(열린 채).
- #50: 편집기, 결과 그리드, Messages, Object Explorer, 결과 탭 줄에서 Ctrl+R이 결과를 숨기고 다시 보임. View &gt; 결과에 Ctrl+R.
  아이콘 단추 도구 설명 조사: 닫기 단추 "닫기 (Ctrl+R)", "닫기 (F8)", "닫기 (Ctrl+Alt+G)", "닫기 (Ctrl+Alt+T)", "닫기 (Ctrl+Alt+I)"와
  쿼리 실행 취소 "… (Alt+Pause)"가 새로 단축키를 보인다. 나머지 단추는 이미 단축키를 보이고 있었다.
- #57: 도구 창 머리글과 결과 탭 줄의 단추가 ×.

**확인하지 못한 것:** 떠 있는(도킹을 뗀) 도구 창에서는 메뉴 막대가 없어 메뉴 가속키(Ctrl+R 포함)가 가지 않을 수 있다(이번 범위 밖, 보고만).

## 변경 이력

- 2026-11-11 최초 작성: #33(현재 DB 따라가기), #44(실행 중 표시), #40(자동완성 창 닫기), #50(단축키를 도구 설명에), #57(닫기 단추 모양)
