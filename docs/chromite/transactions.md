# 트랜잭션 명령, 트랜잭션 로그, 열린 트랜잭션 목록

## 목적

수동 커밋 연결을 Swing에서 안전하게 쓰려면 SQL을 직접 치지 않고 커밋·롤백하고, 지금 트랜잭션에 무엇이 들어 있는지,
어느 연결이 트랜잭션을 쥐고 있는지 볼 수 있어야 한다. SWT 제품에는 있었지만 Swing에는 없던 세 가지를 옮긴다(SWT 대조 3순위,
[swt-parity-audit.md](swt-parity-audit.md), 영역 A-C1·A-C3·A-C4, 영역 B-S17·B-S20·B-S21).

- Commit / Roll Back 명령: Query 메뉴, SQL Editor 도구 막대 단추, 단축키.
- 트랜잭션 로그: 현재 트랜잭션의 문장(시각, 종류, SQL, 걸린 시간, 행, 결과).
- 열린 트랜잭션 목록: 모든 연결에서 열린 트랜잭션이 있는 세션, 거기서 커밋·롤백.

열린 트랜잭션 표시(쿼리 창 상태 줄)와 유휴 트랜잭션 처리는 [idle-transactions.md](idle-transactions.md)에 있다. 이 문서의 기능은 그 위에 얹었다.

## 구조

| 부분 | 번들 | 클래스 |
|---|---|---|
| 트랜잭션 상태·문장 읽기 | `com.seizia.chromite.model` | `QMUtils.getTransactionState`, `QMUtils.getTransactionStatements`(이번에 더함) |
| 커밋·롤백 공용 경로 | `com.seizia.chromite.model`, `ui.swing` | `IdleTransactionMonitor.endTransaction`, `SwingIdleTransactionService.endTransaction` |
| Query 메뉴·단축키 | `com.seizia.chromite.ui.swing` | `MainFrame`(`commitAction`, `rollbackAction`, `transactionLogAction`, `updateQueryActions`) |
| 도구 막대 단추 | `com.seizia.chromite.ui.swing` | `SqlEditorToolBar` |
| 쿼리 창 쪽 | `com.seizia.chromite.ui.swing` | `SqlEditorPanel`(`getOpenTransaction`, `endOpenTransaction(boolean)`, `showTransactionLog`) |
| 상태 줄 메뉴 | `com.seizia.chromite.ui.swing` | `QueryStatusLine.OpenTransactionActions`(`end`, `showLog`) |
| 트랜잭션 로그 | `com.seizia.chromite.ui.swing` | `TransactionLogDialog` |
| 열린 트랜잭션 목록 | `com.seizia.chromite.ui.swing` | `OpenTransactionsDialog`(Tools > Open Transactions…) |
| 문구 | `com.seizia.chromite.ui.swing` | `CoreMessages`(`main_commit` 등), `EditorMessages`(`editor_txn_log_*`, `editor_txn_list_*`, `editor_sql_commit_tip` 등), 영어·한국어 |

## Commit / Roll Back 명령

- 자리:
  - Query 메뉴: 실행 계획 항목 아래 묶음에 Commit, Roll Back, Transaction Log….
  - SQL Editor 도구 막대(메인 창 둘째 줄): 실제 계획 단추 뒤에 Commit, Roll Back 아이콘 단추(아이콘 팩의 `commit`, `rollback`). Query 메뉴와 같은 `Action`이라 켜짐과 단축키가 같다.
  - SWT는 메인 도구 막대와 Database 메뉴에 두었다. Swing에는 Database 메뉴가 없고 쿼리 창 명령은 Query 메뉴와 SQL Editor 도구 막대에 있으므로 그 자리에 두었다.
- 단축키(SWT `core` `plugin.xml`, 기준 커밋 `263b8ddb45`의 key 바인딩과 같음):

  | 명령 | Windows | macOS·Linux |
  |---|---|---|
  | Commit | Ctrl+Alt+Shift+K | Ctrl+4 |
  | Roll Back | Ctrl+Alt+Shift+R | Ctrl+8 |

  도구 막대 단추의 도구 설명은 동작의 단축키를 읽어 적는다(`WelcomePanel.getKeyText`). 플랫폼마다 단축키가 달라서다.
- 켜짐: 활성 쿼리 창이 열린 트랜잭션을 보이고(상태 줄의 "열린 트랜잭션" 칸과 같은 조건: 수동 커밋, 마지막 커밋·롤백 뒤 사용자 문장 있음),
  그 문맥에서 문장이 실행 중이 아니고, 그 쿼리 창이 실행 중이 아닐 때. 자동 커밋이거나 트랜잭션이 없으면 꺼진다.
  쿼리 창이 1초마다 상태를 읽고, 트랜잭션이 열리거나 끝나거나 바뀌거나 실행 중 여부가 바뀔 때만 메인 창에 알린다(`Host.executionStateChanged`).
- 동작: 지금 보이는 트랜잭션(시작 시각)을 상태 줄 메뉴와 같은 경로로 끝낸다. 새 커밋 경로를 만들지 않았다.
  - `SwingIdleTransactionService.endTransaction` → 작업 스레드에서 `IdleTransactionMonitor.endTransaction` → `DBCTransactionManager.commit`/`rollback`(UTIL 세션).
  - 그 사이 다른 트랜잭션이 시작되었거나 문장·Job이 실행 중이면 아무것도 하지 않고 "아무것도 하지 않았습니다"를 알린다. 실패하면 오류 대화상자.
- 확인 질문은 없다. 사용자가 명령을 고른 것이 곧 답이다(상태 줄 메뉴, SWT 명령과 같음). 묻지 않고 끝내는 경로는 생기지 않았다.

## 트랜잭션 로그

- 여는 곳: Query > Transaction Log…, 쿼리 창 상태 줄의 열린 트랜잭션 칸 메뉴(Commit / Roll Back 아래), 열린 트랜잭션 목록의 Transaction Log… 단추.
  쿼리 창이 연결되어 있지 않으면 그렇다고 알린다(Query 메뉴는 연결될 때 다시 그려지지 않아 명령을 끄지 않았다).
- 내용: 그 실행 문맥의 현재 트랜잭션 문장, 오래된 것부터(`QMUtils.getTransactionStatements`). 상태 줄의 "문장 N개"와 같은 기준이다
  (메타데이터 읽기·내부 작업 제외, SELECT 포함). 위에는 세션(그 문맥을 쓰는 쿼리 창 이름, 아니면 문맥 이름)과 요약
  ("…부터 열린 트랜잭션: 문장 N개, 데이터 변경 M개", 트랜잭션이 없으면 그 이유: 자동 커밋 또는 마지막 커밋·롤백 뒤 문장 없음).
- 열: 시각(문장 시작, 밀리초까지), 종류(SQL의 첫 키워드, `SQLUtils.getFirstKeyword`), SQL(한 줄로 줄임, 고르면 아래 미리 보기에 전체를 연결 방언으로 강조),
  걸린 시간, 행(바꾼 행 수 또는 읽은 행 수), 결과(성공, 오류 메시지, 실행 중).
- 모달이 아니고 문맥마다 하나다. 1초마다 다시 읽는다(쿼리 관리자 메모리 기록만 읽음). 다른 쿼리 창에서 실행하거나 커밋하면 따라온다.
- SWT와 다른 점:
  - SWT는 기본으로 데이터를 바꾸는 문장만 보이고 "모든 문장 보이기"를 두었다. Chromite는 SELECT도 트랜잭션을 연 것으로 보므로(잠금·스냅숏, [idle-transactions.md](idle-transactions.md)) 모두 보인다.
  - "지난 트랜잭션 보이기"는 옮기지 않았다(남은 일).
  - SWT는 쿼리 관리자 보기(`QueryLogViewer`)를 그대로 썼다. Swing에는 그 보기가 없어(A-C6) 표를 새로 만들었다.

## 열린 트랜잭션 목록

- 여는 곳: Tools > Open Transactions…. 하나만 여는 모달 아닌 대화상자다.
- 도구 창이 아니라 대화상자로 한 이유: 가끔 여는 점검 화면이고, 도구 창으로 하면 도킹 배치 저장(`ToolWindowDocking`)과 View 메뉴가 늘어난다. SWT도 대화상자였다.
- 대상: 작업 공간 모든 프로젝트의 연결된 연결에서, 모든 인스턴스의 모든 실행 문맥 중 열린 트랜잭션(`QMTransactionState.isOpen`)이 있는 것.
  상태 줄, 유휴 트랜잭션 감시, Commit 명령과 같은 기준이라 세 곳이 같은 트랜잭션을 보인다.
- 열: 연결, 데이터베이스(문맥의 기본 카탈로그·스키마, 없으면 연결 설정의 DB), 세션(쿼리 창 이름 또는 문맥 이름), 시작 시각, 문장 수(데이터 변경 수),
  유휴(마지막 문장 끝부터, 실행 중이면 "실행 중").
- Commit / Roll Back: 고른 행이 보여 준 트랜잭션만 끝낸다. 경로와 확인 규칙은 위 명령과 같다. 실행 중인 행은 단추가 꺼진다. 끝나면 목록을 다시 읽는다.
- 1초마다 다시 채우고 고른 행은 같은 문맥이면 유지한다.
- 보이지 않는 것: Chromite Agent·MCP 도구의 격리 세션(쿼리 관리자를 거치지 않음). 설명 문구에 적었다. 끝낼 때와 끊을 때는 `ExitTransactionCheck`가 이들도 묻는다.
- SWT와 다른 점: SWT의 "Show all connections"(트랜잭션 없는 문맥도 보이기)와 아래 붙은 로그는 두지 않았다. 끝낼 것이 없는 문맥이고, 로그는 단추로 연다.

## 쿼리 창마다 별도 연결

### 문제

Swing 쿼리 창은 모두 연결의 주 실행 문맥(서버 세션 하나)을 썼다. 그래서 수동 커밋 연결에서 창 A의 Commit이 창 B의 변경까지 커밋하고,
A의 Roll Back이 B의 작업도 버렸다. 한 창에서 바꾼 기본 DB/스키마도 같은 연결의 모든 창에 적용되었다. SWT는 SQL 편집기마다 격리된 실행 문맥을 열었다
(SWT 대조 B-S22, [swt-parity-audit.md](swt-parity-audit.md) 3순위).

### 구조

| 부분 | 클래스 |
|---|---|
| 창의 세션(열기, 읽기, 닫기, 설정 판단, 세션 수) | `QueryWindowContext`(새 파일) |
| 창이 쓰는 문맥 | `SqlEditorPanel.getExecutionContext`, `confirmEndSession` |
| 세션을 끝내기 전 질문 | `ExitTransactionCheck.confirmEndSession`(끝낼 때·끊을 때와 같은 커밋·롤백 코드) |
| 창 이름 붙이기 | `SwingIdleTransactionService.describeQueryWindows`(열린 트랜잭션 목록, 트랜잭션 로그, 유휴 알림, 끊기·끝내기 질문), `AutoCommitApplier` |
| 상태 줄 세션 수 | `QueryStatusLine.setSessions` |
| 자동완성 | `SqlCompletionPopup`, `SqlCompletionAnalysis.Request.executionContext` |
| Chromite Agent | `AiAssistantPanel.getCurrentDatabase(SqlEditorPanel)` |
| 설정 | `SqlExecutionOptionsPage`(Options > Query Execution > Sessions), `ConnectionSettingsPanel`(Connection Properties > Query window sessions), `ConnectionDialog` |
| 문구 | `SettingsMessages`(`exec_separate_connection*`), `ConnectionMessages`(`settings_separate_connection*`, `window_txn_*`), `EditorMessages`(`editor_status_session_*`), 영어·한국어 |

### 설정

- 키는 SWT와 같은 `database.editor.separate.connection`, 값은 `ModelPreferences.SeparateConnectionBehavior`(`DEFAULT` / `ALWAYS` / `NEVER`)다.
  SWT 사용자가 저장해 둔 값(전역, 연결별)이 그대로 적용된다.
- 기본값은 SWT와 같다: `DEFAULT` = 창마다 세션, 단 연결이 "단일 연결"(`isForceUseSingleConnection`)이거나 드라이버가 내장 DB(`isEmbedded`, SQLite·H2 등)이면 함께 씀
  (SWT `SQLEditorUtils.isOpenSeparateConnection`). Swing에서도 그대로 켰다. 끌 이유를 찾지 못했다: 세션이 창 수만큼 늘지만 SSMS도 쿼리 창마다 연결한다.
- 전역: Tools > Options > Query Execution > Sessions의 "Query window sessions"(창마다(기본) / 항상 창마다 / 함께 씀). 기본을 고르면 값을 지운다.
- 연결별: 연결 편집 > Connection Properties의 "Query window sessions"(Options 설정을 따름 / 창마다 / 함께 씀). 연결의 설정 저장소(`data-sources.json`의
  `custom-properties`)에 쓴다. 이것만 바꾸면 다시 연결을 묻지 않는다(`ConnectionDialog`, 자동 커밋과 같은 방식).
- 적용 시점: 창이 한 연결(데이터 소스)에서 처음 문맥을 얻을 때 정하고 그 연결 동안 유지한다. 바꾼 뒤 연결하는 창(새 창, 다시 연결, 연결 바꾸기)부터 적용된다.
  이미 정한 창을 바꾸지 않는 이유: 그 창의 열린 트랜잭션이 갑자기 다른 세션의 것처럼 보이게 된다.

### 세션의 수명

- 열기: 창이 연결된 연결을 가질 때(창을 열 때, 연결될 때, 연결을 바꿀 때, 상태 줄의 커밋 방식 읽기와 기본 DB 읽기 작업) 또는 처음 실행할 때 작업 스레드에서
  `DBSInstance.openIsolatedContext(monitor, "SQLEditor <파일 이름>", 주 문맥)`으로 연다. SWT와 같은 이름이라 서버의 세션 목록(클라이언트 이름)에서 창을 구별할 수 있다.
  주 문맥을 `initFrom`으로 넘겨 기본 카탈로그·스키마를 복사한다(PostgreSQL search_path, SQL Server·MySQL 현재 DB). 자동 커밋·격리 수준은 연결 설정을 따른다.
  Modify·New 스크립트 창도 스크립트를 만들 때 주 문맥에 맞춘 DB/스키마를 이렇게 이어받는다(`SourceScripts`).
- 닫기: 창을 닫을 때, 창의 연결을 바꿀 때 작업 스레드에서 닫는다.
- 연결 끊기: 인스턴스가 모든 문맥을 닫는다(`JDBCRemoteInstance.shutdown`). 끊기 전 질문(`ExitTransactionCheck.confirmDisconnect`)은 연결의 모든 문맥을 보므로
  창마다의 세션이 모두 목록에 나오고, 목록은 문맥 이름 대신 창 이름("연결 (SQLQuery1.sql)")이다. 다시 연결하면 창마다 새 세션을 연다.
- 다시 연결(연결 유지 실패 등 무효화): 같은 문맥 객체가 다시 연결되므로 창은 같은 세션을 계속 쓴다.

### 트랜잭션 안전

창의 자기 세션을 끝내는 동작은 커밋하지 않은 트랜잭션(`QMUtils.isTransactionActive`, 끊기·끝내기 질문과 같은 기준)이 있으면 먼저 Commit / Roll back / Cancel을 묻는다.

| 동작 | 질문 | 취소하면 |
|---|---|---|
| 쿼리 창 닫기 | 저장 질문 뒤에 "쿼리 창 X의 세션에 커밋하지 않은 트랜잭션이 있습니다" | 창이 열린 채 남음 |
| 연결 콤보로 연결 바꾸기 | 같음 | 콤보가 이전 연결로 돌아감 |
| 기본 DB 콤보로 다른 인스턴스의 DB 고르기(PostgreSQL) | 같음. 격리 문맥은 다른 DB로 다시 연결한다(`PostgreExecutionContext.setDefaultCatalog`) | 콤보가 이전 DB로 돌아감 |
| 연결 끊기, 다시 연결, 삭제, Chromite 끝내기 | 기존 질문(#97)이 창마다의 세션을 창 이름으로 보임 | 기존과 같음 |

창들이 주 문맥을 함께 쓰는 설정에서는 창을 닫아도 세션이 끝나지 않으므로 묻지 않는다(예전과 같음).

### 창의 문맥을 따르는 것

| 기능 | 어떻게 |
|---|---|
| 실행(F5), 실제 계획 켜기·끄기, 쿼리 기록의 DB/스키마 | 준비 작업이 `QueryWindowContext.open`으로 얻은 문맥에서 엔진을 돌린다 |
| 예상 계획(Ctrl+L), 실제 계획 다시 계획 | 같은 문맥 |
| 기본 DB/스키마 콤보 | 그 문맥의 기본값을 읽고 바꾼다(이 창에만 적용) |
| 상태 줄 커밋 방식·열린 트랜잭션 칸, Query > Commit / Roll Back, 도구 막대 단추, 단축키, Transaction Log | `SqlEditorPanel.getExecutionContext`(1초마다 읽음, 서버를 부르지 않음) |
| 열린 트랜잭션 목록 | 모든 문맥을 보므로 창마다 한 줄. 세션 열은 그 문맥을 쓰는 창 이름 |
| 유휴 알림·자동 롤백, 유휴 연결 닫기 | 모델 감시가 모든 문맥을 본다. 알림의 창 이름은 그 문맥을 쓰는 창 |
| 결과 표 편집·저장, `@export` | 결과를 읽은 문맥을 넘겨받는다(`SQLQueryDataContainer`, `ResultSetEditModel`). 저장이 자동 커밋을 잠시 끄는 문맥도 이 창의 것이다 |
| 자동완성 | 요청에 창의 문맥을 넣는다(기본 스키마). 아직 세션이 없으면 주 문맥 |
| Chromite Agent | 새 대화와 "이 쿼리에 대해 묻기"의 현재 DB는 창의 문맥. `openInEditor`는 새 창을 열고 새 창은 자기 세션을 연다 |
| 연결 설정의 자동 커밋 적용(`AutoCommitApplier`) | 이미 연결의 모든 문맥에 적용한다. 질문의 이름이 창 이름이다 |

### 세션 수

쿼리 상태 줄의 커밋 방식 칸 도구 설명 맨 아래에 "세션: 이 창만 쓰는 세션(이 연결에 열린 세션 N개)" 또는 "…다른 쿼리 창과 함께 쓰는 세션(…)"을 보인다.
N은 연결의 모든 인스턴스의 실행 문맥 수다(주, 메타데이터, 쿼리 창, Activity Monitor, Chromite Agent 등 Chromite가 서버에 연 세션 모두). 1초마다 다시 센다(메모리 목록만).

### 확인 (2026-11-07)

이 브랜치의 제품 빌드를 복사해 자기 작업 공간으로 실행하고, 앱 안에서 Swing API로 누르는 스크립트(OS 입력 없음)로 확인했다.
연결은 수동 커밋, 이 작업 전용 DB `chr_p3_sepconn`의 `sep_test`(행 1~10). 증거: `D:\Tools\_scratch\p3-separate-connections\`
(스크립트 `p*.txt`, 결과 `*.txt.log`, 창 그림 `shots\`). 행 값과 세션은 다른 세션(`docker exec … psql` / `sqlcmd`)에서 읽었다.

| 확인 | PostgreSQL 17 (55432) | SQL Server 2022 (51433) |
|---|---|---|
| 두 창의 세션 | `pg_backend_pid` 34029 / 34032, 서버 `application_name` `Chromite 0.1.0 - SQLEditor <SQLQuery1.sql>` 등 | `@@SPID` 54 / 55, `program_name` `… SQLEditor <SQLQuery4.sql>` 등 |
| A의 UPDATE(행 1)가 커밋 전 B에 안 보임(B에서 확인) | B의 SELECT: 행 1 = `orig`(A 안에서는 `A-pending`) | B의 `WITH (READPAST)` SELECT: 행 1이 A의 잠금으로 빠지고 행 2만 |
| 상태 줄 세션 줄 | "세션: 이 창만 쓰는 세션(이 연결에 열린 세션 4개)" | 같음 |
| 열린 트랜잭션 목록 | `SQLQuery1.sql`, `SQLQuery2.sql` 두 줄 | `SQLQuery4.sql`, `SQLQuery5.sql` 두 줄 |
| A에서 Commit | 행 1 = `A-pending`, B의 행 2 = `orig`(B의 상태 줄은 여전히 열린 트랜잭션) | 행 1 커밋, B의 변경은 뒤에 끊기 질문에서 롤백되어 행 2 = `orig`(A의 커밋에 들어가지 않음) |
| 열린 트랜잭션이 있는 A 닫기 | 저장 질문 뒤 "쿼리 창 SQLQuery1.sql의 세션에 커밋하지 않은 트랜잭션…"; 취소 → 창·트랜잭션 남음; 롤백 → 창 닫힘, 행 3 = `orig`, 서버에서 A 세션 사라짐 | 같은 질문, 롤백 → 행 3 = `orig` |
| 끊기 | 질문 목록 "PostgreSQL 테스트 (SQLQuery2.sql)", "(SQLQuery3.sql)"; 취소 → 그대로; 롤백 → 끊김, 행 2·4 = `orig`, 서버 세션 0 | "SQL Server 테스트 (SQLQuery5.sql)", "(SQLQuery6.sql)"; 롤백 → 끊김, 행 4 = `orig` |
| 설정 끄기 | Options > Query Execution "한 연결의 쿼리 창들이 한 세션을 함께 씀": 두 창 pid 같음(34131 = Main), B의 UPDATE를 C가 봄, 목록 한 줄 "SQLQuery2.sql, SQLQuery3.sql", C의 Commit이 B의 변경을 커밋(행 5 = `B-shared`) | 연결 대화상자 "쿼리 창들이 한 세션을 함께 씀"(`custom-properties`에 `NEVER` 저장): 두 창 SPID 52 = Main, B의 Commit이 A의 변경을 커밋(행 5 = `A-shared`) |

확인한 뒤 행을 `orig`로 되돌리고 확인용 DB를 지웠다. 연결 콤보로 연결 바꾸기와 PostgreSQL 다른 DB 고르기의 질문은 실행해 보지 않았다(창 닫기와 같은 `confirmEndSession` 경로).

### SWT와 다른 점

- SWT 편집기의 "별도 연결 전환" 명령(`SQLEditorHandlerSeparateConnectionSwitch`)은 옮기지 않았다. 연결 대화상자의 연결별 값으로 대신한다.
- SWT는 설정이 바뀌면 열린 편집기의 문맥을 바로 바꿨다(`EDITOR_SEPARATE_CONNECTION` 속성 변경 이벤트). Chromite는 열린 트랜잭션이 다른 세션으로
  옮겨진 것처럼 보이지 않도록 바꾼 뒤 연결하는 창부터 적용한다.
- SWT `OpenContextJob`은 새 문맥의 기본값을 정한 뒤 그 문맥을 커밋했다. Chromite는 기본값을 따로 정하지 않고 `initFrom` 복사만 쓴다(모델의 문맥 초기화가 이미 커밋한다).
- SWT 스크립트 파일에 저장해 둔 기본 스키마 복원(`SQLEditor.autoSaveActiveSchema`)은 Swing에 그 기능이 없어 없다.

## 설계 결정과 이유

- **한 커밋 경로**: Query 메뉴·도구 막대·단축키·목록이 모두 #97의 `SwingIdleTransactionService.endTransaction`을 쓴다. 오래된 화면이 새 트랜잭션을 끝내지 않는 보호(시작 시각 비교, 실행 중이면 하지 않음)를 그대로 얻는다.
- **같은 기준**: 상태 줄, 로그, 목록, 유휴 감시가 모두 `QMUtils`의 같은 문장 기준(`isUserStatement`)을 쓴다. 로그의 행 수와 상태 줄의 문장 수가 다르지 않게 하려고 기준을 한 함수로 뺐다.
- **확인 없음**: 명령을 고르는 것이 사용자의 선택이다(트랜잭션 안전 규칙은 "조용히" 끝내는 것을 막는다).

## upstream 대비 변경점

- SWT `DataSourceCommitHandler`/`DataSourceRollbackHandler`/`DataSourceTransactionLogHandler`/`DataSourcePendingTransactionsHandler`,
  `TransactionLogDialog`/`PendingTransactionsDialog`는 SWT 제거 때 지워졌다. Swing 클래스는 새로 썼고 SWT 코드를 옮기지 않았다.
- SWT 커밋 명령은 끝낸 뒤 설정(`transaction.show.notifications`)에 따라 알림을 띄웠다. Swing은 설정 화면이 없어 알림을 두지 않았다. 상태 줄 칸이 사라지는 것으로 보인다.
- 모델: `QMUtils.getTransactionStatements` 추가, 문장 기준을 `isUserStatement`로 뺌(`getTransactionState`의 결과는 같음).

## 확인 (2026-11-07)

제품 빌드(`-Pproduct-chromite-swing`)를 복사해 자기 작업 공간으로 실행하고, 프로세스 안에서 Swing API로 누르는 스크립트(OS 입력 없음)로 확인했다.
연결은 수동 커밋(`bootstrap.autocommit = false`), 이 작업 전용 DB `chr_p3_txn_export`의 `txn_test`(행 1~10). 증거: `D:\Tools\_scratch\p3-transactions-export\`
(스크립트 `*.txt`, 결과 `*.txt.log`, 창 그림 `shots\`).

| 확인 | PostgreSQL 17 (55432) | SQL Server 2022 (51433) |
|---|---|---|
| 문장 실행 전 Query 메뉴의 커밋·롤백 꺼짐 | 예(`커밋(disabled), 롤백(disabled)`) | - |
| UPDATE 뒤 켜짐, 상태 줄 "열린 트랜잭션 … (문장 1개)" | 예 | 예 |
| Query 메뉴 커밋(행 1) / 롤백(행 2) | 1 바뀜 / 2 그대로 | 1 바뀜 / 2 그대로 |
| 도구 막대 커밋(행 3) / 롤백(행 4) | 3 바뀜 / 4 그대로 | 3 바뀜 / 4 그대로 |
| Ctrl+Alt+Shift+K(행 5) / Ctrl+Alt+Shift+R(행 6) | 5 바뀜 / 6 그대로 | 5 바뀜 / 6 그대로 |
| 트랜잭션 로그(상태 줄 메뉴·Query 메뉴·목록 단추) | UPDATE·SELECT·UPDATE 3행, 행 수(1행 변경, 2행 읽음), 시간, 성공 | 1행(SQL Server는 세 문장이 한 배치로 실행됨, `SQLScriptBatches`) |
| 열린 트랜잭션 목록 | `PostgreSQL 테스트 / chr_p3_txn_export.public / SQLQuery1.sql / 3개(데이터 변경 2개) / 11초` | `SQL Server 테스트 / chr_p3_txn_export.dbo / SQLQuery2.sql / 1개(데이터 변경 1개)` |
| 목록에서 롤백(행 7, 9) / 커밋(행 8) | 7·9 그대로 / 8 바뀜, 목록 비고 "열린 트랜잭션이 없습니다." | 같음 |

행 값은 다른 세션(`docker exec … psql` / `sqlcmd`)에서 읽었다. 확인용 DB는 확인 뒤 지웠다.

## 남은 일

- 트랜잭션 로그의 "지난 트랜잭션 보이기"와 "모든 문장(메타데이터 포함) 보이기".
- 커밋·롤백 뒤 알림과 그 설정(SWT `transaction.show.notifications`). 트랜잭션 설정 화면(A-P1 "Transactions")과 함께 한다.
- 스마트 커밋(B-S16), 실행 중 자동/수동 커밋 전환(B-S18)은 이 작업에 들어 있지 않다. 쿼리 창마다 별도 연결(B-S22)은 2026-11-07에 더했다(위 절).

## 변경 이력

- 2026-11-07 최초 작성: Query > Commit / Roll Back(SWT 단축키, SQL Editor 도구 막대 단추), 트랜잭션 로그, 열린 트랜잭션 목록.
  PostgreSQL·SQL Server에서 실행 확인(위 "확인")
- 2026-11-07 쿼리 창마다 별도 연결(SWT 대조 B-S22): 창마다 격리된 세션, 창 닫기·연결 바꾸기·DB 인스턴스 바꾸기 전 트랜잭션 질문,
  상태 줄·명령·로그·목록·유휴 알림·결과 저장·자동완성·Chromite Agent가 창의 세션을 따름, Options와 연결 대화상자의 설정, 세션 수 표시.
  PostgreSQL·SQL Server에서 실행 확인("쿼리 창마다 별도 연결 > 확인")
