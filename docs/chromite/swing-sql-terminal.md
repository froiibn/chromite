# Swing SQL 터미널 (Query > SQL Terminal)

## 목적

SWT에는 SQL 편집기 결과 영역에 "SQL Terminal" 탭(`ui.editors.sql.terminal` 번들)이 있었다. 켜면 실행한 문장과 결과를
명령줄 클라이언트처럼 글자로 쌓아 보였다. SWT를 지울 때 이 번들도 지워져 Swing 쿼리 창에는 없었다
([swt-removal.md](swt-removal.md) "SQL 터미널", 대조 감사 B-S43, A 설정 표의 SQL Terminal 행).

Swing 쿼리 창에 같은 기능을 쿼리 창마다 켜고 끄는 **Query > SQL Terminal**(Ctrl+T)로 옮긴다.

## 동작

- Query > SQL Terminal(체크 메뉴, Ctrl+T)은 활성 쿼리 창에만 적용된다. 켜면 그 창의 결과 탭 줄 끝(Execution plan 뒤)에 **Terminal** 탭이 붙고 앞으로 온다.
  끄면 탭이 빠지고, 터미널이 앞이었으면 결과 탭(없으면 Messages)이 앞으로 온다(2026-11-08 결과 탭, [swing-frontend.md](swing-frontend.md) "결과 탭").
- 켠 채로 실행하면(F5) 결과 표와 Messages 탭은 지금과 똑같이 채워지고, Terminal 탭에도 문장마다 다음을 쓴다.

  ```
  SQL> SELECT id, name
       FROM t
  +----+------+
  | id | name |
  +----+------+
  |  1 | 가나 |
  |  2 | NULL |
  +----+------+
  (2 rows affected)
  Execution time: 3 ms

  ```

  순서: `SQL> 문장`(둘째 줄부터 들여씀) → 값을 바인딩한 실행(Query Library 실행, 기록에서 다시 연 바인딩 실행)은 값 줄(`-- :name = 값`,
  빈 값은 `NULL`, Query History 미리 보기와 같은 형식) → 결과 집합마다 글자 표 → Messages 탭과 같은 메시지(PRINT·NOTICE 경고, 서버 출력, 행 수,
  `Commands completed`, 실행 시간) → 오류(빨간색) → 빈 줄. 스크립트 명령(`@echo`) 출력, 문장 밖 오류(연결 실패 등), 취소 문구도 쓴다.
- 실행 중과 끝난 뒤에는 Results 대신 Terminal 탭이 앞으로 온다(오류가 나도 Terminal 탭). 오류는 Terminal에도 빨간색으로 나온다.
- 내용은 실행마다 지우지 않고 쌓인다. 오른쪽 클릭 메뉴: Copy, Select All, Clear.
- 창마다 따로다. 쿼리 창을 바꾸면 그 창이 켰는지에 따라 Terminal 탭이 붙거나 빠진다.

## 구조

| 위치 | 역할 |
|---|---|
| `ui.swing` `SqlTerminalView` (새로 만듦) | 창 하나의 터미널(고정폭 `JTextPane`, 오른쪽 클릭 메뉴, 200만 자 상한). 글자 표·문장 서식(`formatTable`, `formatStatement`, 어느 스레드에서나) |
| `ui.swing` `SqlEditorPanel` | 창마다 켜짐 상태와 Terminal 탭 선택 기억. 실행(`Execution.terminal`)이 켜고 시작했으면 결과 수신기(작업 스레드)가 글자 표를 만들어 두고, 문장 끝(`reportQueryResult`)에 메시지와 함께 한 번에 EDT로 넘김. `showRunOutput`이 Terminal 또는 Results/Messages를 앞으로 |
| `ui.swing` `MainFrame` | Query > SQL Terminal 동작(`terminalAction`, Ctrl+T, 선택 상태), 활성 창이 켰을 때만 붙는 넷째 결과 탭(`updateTerminalTab`, `selectResultTab`. 2026-11-08부터는 창의 탭 줄 `QueryResultsView.setTerminalComponent`), `Host.showTerminal` |
| `ui.swing` `internal.TerminalMessages` (새로 만듦) | 화면 문구(영어, 한국어) |
| `tools/branding/gen_ui_icons.py`, `icons/ui/*/*/terminal.svg` | Terminal 탭 아이콘(Tabler `terminal-2`를 16px 격자로 다시 그림, 두 팩·밝은/어두운 테마) |

## 설계 결정과 이유

- **결과 표는 그대로 만든다(SWT와 같음).** SWT 터미널도 결과 탭을 만들고 자동 포커스만 껐다(`setResultSetAutoFocusEnabled(!enabled)`).
  그래서 터미널을 켜도 결과 편집·필터·내보내기는 Results 탭에서 그대로 쓸 수 있다. SSMS의 "Results to Text"(결과를 표 대신 글자로)처럼
  표를 없애면 이 기능들을 잃는다.
- **창마다, 저장하지 않음.** 과제가 "창마다 토글"이다. SWT는 스크립트 파일 속성과 기본값 설정(`SQLEditor.ConsoleView.showConsoleViewByDefault`)에
  기억했지만, Swing은 새 창이 늘 꺼진 상태로 열린다. 기본값 설정과 SWT의 다른 두 설정(`SQLEditor.SQLTerminal.showQueryText`,
  `SQLEditor.SQLTerminal.showServerOutput`)은 옮기지 않았다(문장과 서버 출력은 늘 쓴다). 필요하면 Options에 더한다.
- **Ctrl+T.** SSMS의 Results to Text 단축키다. Swing에는 Ctrl+T 바인딩이 없었다(`KeyEvent.VK_T`는 Query Library 도구 창 Ctrl+Alt+T와 메뉴
  니모닉뿐, `ui.swing` 검색). 편집기(RSyntaxTextArea)에 초점이 있을 때도 메뉴 단축키로 동작하는지 앱에서 확인했다(아래 "확인").
- **메시지를 다시 만들지 않는다.** Messages 탭에 쓰는 같은 목록(경고, 서버 출력, 행 수, 시간)을 터미널에도 쓴다. 행 수 문구가
  Messages 탭과 같아(`(N rows affected)`, 최대 행 수에 걸리면 그 표시도) SSMS 결과와 맞는다.
- **글자 표는 작업 스레드에서 만든다.** 큰 결과를 EDT에서 서식하면 화면이 멈춘다. 결과 수신기 콜백은 작업 스레드이고, 문장 끝 이벤트도
  같은 스레드라 쿼리별 목록(`Execution.terminalTables`)을 잠금 없이 쓴다. 표와 메시지를 한 번에 EDT로 넘겨 순서가 섞이지 않는다.
- **칸 너비 255.** SWT 글자 결과(`PlainTextFormatter`)의 기본 최대 칸 크기(`RESULT_TEXT_MAX_COLUMN_SIZE` 기본값 255,
  `263b8ddb45:plugins/com.seizia.chromite.ui.editors.data/.../DataEditorsPreferencesInitializer.java:104`)를 따른다. 넘는 값은 잘라 `…`을 붙이고,
  줄바꿈·탭은 공백으로 바꿔 한 행이 한 줄이 되게 한다.
- **NULL은 `NULL`.** SWT 글자 결과의 기본은 빈칸(`RESULT_TEXT_SHOW_NULLS` 기본 false)이지만, Swing 결과 표가 `NULL`로 보이므로(SSMS와 같음) 맞췄다.
- **전각 문자는 두 칸.** 한글·한자·전각 기호를 두 칸으로 세어 고정폭 글꼴에서 세로줄이 맞게 한다(한국어 데이터가 흔하다).
- **쌓되 상한.** 명령줄처럼 앞 실행과 비교할 수 있게 실행마다 지우지 않는다. 200만 자를 넘으면 앞의 오래된 줄부터 줄 단위로 지운다(메모리).
- **탭은 켰을 때만.** 결과 탭은 활성 쿼리 창의 내용을 끼우는 공용 탭이다. 터미널은 창마다 켜므로 탭이 늘 있으면 꺼진 창에서 빈 탭이 된다.
- **실행 중 끄기.** 실행은 시작할 때의 선택을 따른다(`Execution.terminal`). 실행 중에 끄면 남은 출력은 숨은 터미널에 쓰이고, 결과는 Results 탭이 보인다.
- **바인딩한 값은 주석 줄로(2026-11-08, evening #28).** 바인딩한 실행의 문장은 `:name`이 남은 글자(`getHistoryText`)라, 전에는 터미널에 값이 보이지 않았다.
  값을 문장에 끼워 넣지 않고 Query History 미리 보기와 같은 `-- :name = 값` 줄로 쓴다(`QueryHistory.Parameter.toCommentLines`, 두 곳이 같은 코드).
  끼워 넣으면 실제로 보낸 문장(`?`로 바인딩)과 달라 보이고, 리터럴로 바꾸는 규칙이 서버마다 다르다(`QueryParameterBinder` 설계).

## upstream 대비 변경점

- SWT `SQLTerminalEditorAddIn`/`SQLTerminalView`(Eclipse 콘솔 `MessageConsole`)를 Swing 클래스(`SqlTerminalView`)로 새로 썼다. 코드는 옮기지 않았다.
- SWT는 결과 집합 이름(쿼리 글)을 표 위에 쓰고 `N row(s) fetched.`를 붙였다. Swing은 `SQL> 문장`을 먼저 쓰고 Messages 탭과 같은 문구를 쓴다.
- SWT의 설정 페이지(SQL Terminal: 기본으로 켜기, 문장 보이기, 서버 출력 보이기)는 옮기지 않았다(위 결정).

## 확인

회귀 테스트는 만들지 않았다(요청 없음). 앱 사본에서 확인한 내용은 아래 "확인 기록"에 있다.

### 확인 기록 (2026-11-08, Windows, 이 브랜치 빌드, 작업 공간 사본, Docker 테스트 DB, 프로세스 안 드라이버로 조작)

| 확인 | 결과 |
|---|---|
| PostgreSQL, Query > SQL Terminal 켜고 두 문장 실행(한글 값, NULL, 숫자, 줄바꿈 든 값) | Terminal 탭이 넷째로 붙고 앞으로 옴. `SQL> 문장`, 글자 표(숫자 오른쪽 맞춤, `NULL`, 한글 두 칸 정렬, 줄바꿈은 공백), `(2 rows affected)`, `Execution time` |
| 같은 창에서 없는 테이블 SELECT | 앞 출력 뒤에 쌓이고 `Msg 0, State 42P01, Line 1, Column 15`와 오류가 빨간색. Terminal 탭이 앞에 남음 |
| SQL Server 새 창, 편집기에서 Ctrl+T | Terminal 탭이 붙음(RSyntaxTextArea가 Ctrl+T를 먹지 않음) |
| SQL Server `PRINT` + SELECT, `GO`, `SELECT 1/0` | 첫 배치: 표, `hello print`, 행 수. 둘째 배치: 빈 표 머리, `Msg 8134` 오류. Messages 탭에도 같은 메시지 |
| 창 바꾸기 | 터미널을 켠 창에서만 Terminal 탭이 보이고, 각 창이 마지막으로 본 탭으로 돌아감. 끄면 탭이 빠지고 Results로 |
| 한국어(`-nl ko`) | 메뉴 `SQL 터미널`, 탭 `터미널`, `(1개 행 적용됨)`, 한글 열 이름 정렬 |
| 다크 테마 | 탭·글자 색이 테마를 따름 |

### 확인 기록 (2026-11-08, evening #28, `chromite/followups-3` 빌드)

Query Library 쿼리 `SELECT id, name, price FROM dbo.fu3_items WHERE price > :min_price AND name LIKE :prefix + '%' ORDER BY id`(SQL Server, `min_price` 숫자,
`prefix` 글자)를 한 번 실행해 창을 만들고 Query > SQL Terminal을 켠 뒤 라이브러리에서 다시 실행했다.

| 언어 | 터미널 |
|---|---|
| 한국어(745, `Item 49`) | `SQL> SELECT … :min_price … :prefix …` 아래 `-- :min_price = 745`, `-- :prefix = Item 49`, 3행 표, `(3개 행 적용됨)` |
| 영어(0, `Item 5`) | `-- :min_price = 0`, `-- :prefix = Item 5`, 1행 표, `(1 row affected)` |

캡처: `D:\Tools\_scratch\chromite-followups-3\shots\ql-28-terminal-ko.png`, `ql-28-terminal-en.png`. 기록 미리 보기는 같은 메서드로 바꾸었고 글자는 전과 같다(코드로 확인).

## 변경 이력

- 2026-11-08 최초 작성: Query > SQL Terminal(Ctrl+T), 창마다 켜는 Terminal 탭, 글자 표, 아이콘
- 2026-11-08 바인딩한 실행의 값을 문장 아래 `-- :name = 값` 줄로 씀(evening #28, `chromite/followups-3`)
- 2026-11-08 결과 탭(앱 변경 #7): Terminal 탭은 쿼리 창마다의 탭 줄 끝에 붙고, 끄면 결과 탭(없으면 Messages)이 앞으로 옴
