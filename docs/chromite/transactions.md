# 트랜잭션 명령, 트랜잭션 로그, 열린 트랜잭션 목록

## 목적

수동 커밋 연결을 Swing에서 안전하게 쓰려면 SQL을 직접 치지 않고 커밋·롤백하고, 지금 트랜잭션에 무엇이 들어 있는지,
어느 연결이 트랜잭션을 쥐고 있는지 볼 수 있어야 한다. SWT 제품에는 있었지만 Swing에는 없던 세 가지를 옮긴다(SWT 대조 3순위,
[swt-parity-audit.md](swt-parity-audit.md), 영역 A-C1·A-C3·A-C4, 영역 B-S17·B-S20·B-S21).

- Commit / Roll Back 명령: Query 메뉴, SQL Editor 도구 막대 단추, 단축키.
- 트랜잭션 로그: 현재 트랜잭션의 문장(시각, 종류, SQL, 걸린 시간, 행, 결과).
- 열린 트랜잭션 목록: 모든 연결에서 열린 트랜잭션이 있는 세션, 거기서 커밋·롤백.
- 커밋 방식 전환(2026-11-08, B-S18): Query > Auto-Commit과 SQL Editor 도구 막대 단추로 활성 쿼리 창 세션의 자동/수동 커밋을 바꿈.

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
| 오류 복구의 트랜잭션 보호 | `com.seizia.chromite.model` | `runtime.jobs.TransactionRecoveryGuard`(판단, 사용자가 고른 다시 연결), `DBExecUtils.tryExecuteRecover`, `KeepAlivePingJob`, `UIServiceIdleTransactions` |
| 오류 복구 알림 | `com.seizia.chromite.ui.swing` | `SwingIdleTransactionService`(`showTransactionAborted`, `showReconnectHeld`, `showSessionReopened`), `ConnectionMessages`(`recovery_*`) |
| 끊긴 세션(확인, 그 세션만 다시 열기, 버리기) | `com.seizia.chromite.model`, `ui.swing` | `TransactionRecoveryGuard`(`isSessionLost`, `reopenLostSession`, `discardLostTransaction`), `ExitTransactionCheck`(`findLost`, `confirmDiscard`), `ConnectionMessages`(`lost_txn_*`) |
| 커밋 방식 전환(B-S18) | `com.seizia.chromite.ui.swing` | `CommitModeSwitch`(질문과 전환), `SqlEditorPanel`(`getAutoCommit`, `canSwitchCommitMode`, `switchCommitMode`, `isSharedSession`), `MainFrame`(`commitModeAction`), `SqlEditorToolBar`(`setCommitMode`), `LifecycleMessages`(`commit_mode_*`) |

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
  - "지난 트랜잭션 보이기"는 2026-11-08에 더했다([connection-lifecycle.md](connection-lifecycle.md) "트랜잭션 로그의 지난 트랜잭션").
  - SWT는 쿼리 관리자 보기(`QueryLogViewer`)를 그대로 썼다. 만들 때 Swing에는 그 보기가 없어(A-C6) 표를 새로 만들었다.
    2026-11-08부터 View > Query Manager가 있다([swing-query-manager.md](swing-query-manager.md)). 그 창의 트랜잭션 보기는 지난 트랜잭션도 고를 수 있고 메타데이터·내부 쿼리도 보일 수 있다.
    이 창은 한 문맥의 지금 트랜잭션을 늘 보는 용도라 그대로 둔다.

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
- 다시 연결(연결 유지 실패 등 무효화): 같은 문맥 객체가 다시 연결되므로 창은 같은 세션을 계속 쓴다. 창의 세션 하나만 끊겼으면 그 세션만 다시 연다
  (아래 "끊긴 세션의 쿼리 창").

### 트랜잭션 안전

창의 자기 세션을 끝내는 동작은 커밋하지 않은 트랜잭션(`QMUtils.isTransactionActive`, 끊기·끝내기 질문과 같은 기준)이 있으면 먼저 Commit / Roll back / Cancel을 묻는다.

| 동작 | 질문 | 취소하면 |
|---|---|---|
| 쿼리 창 닫기 | 저장 질문 뒤에 "쿼리 창 X의 세션에 커밋하지 않은 트랜잭션이 있습니다" | 창이 열린 채 남음 |
| 연결 콤보로 연결 바꾸기 | 같음 | 콤보가 이전 연결로 돌아감 |
| 기본 DB 콤보로 다른 인스턴스의 DB 고르기(PostgreSQL) | 같음. 격리 문맥은 다른 DB로 다시 연결한다(`PostgreExecutionContext.setDefaultCatalog`) | 콤보가 이전 DB로 돌아감 |
| 연결 끊기, 다시 연결, 삭제, Chromite 끝내기 | 기존 질문(#97)이 창마다의 세션을 창 이름으로 보임 | 기존과 같음 |

창들이 주 문맥을 함께 쓰는 설정에서는 창을 닫아도 세션이 끝나지 않으므로 묻지 않는다(예전과 같음).

오류 복구(아래 "오류 복구와 열린 트랜잭션", `TransactionRecoveryGuard`)와 함께:
- 창의 세션은 인스턴스의 문맥 목록(`getAllContexts`)에 들어가므로 복구가 끝낼 문맥을 셀 때 창마다의 세션이 모두 들어간다. 코드를 바꿀 필요가 없었다.
- 중단된 트랜잭션(PostgreSQL 25P02)의 롤백은 실패한 창의 문맥 하나만 대상이다. 알림은 그 창 이름으로 뜨고 다른 창의 트랜잭션은 그대로다.
- 한 창의 세션이 끊기면 복구는 그 창의 세션만 다시 연다(2026-11-08, 아래 "끊긴 세션의 쿼리 창"). 주 문맥(창들이 함께 쓰는 설정)이나 메타데이터 문맥이
  끊겼을 때만 데이터 소스 전체를 다시 연결하려 하고, 다른 창에 열린 트랜잭션이 있으면 다시 연결하지 않고 "연결 끊김" 알림이 그 창들을 이름으로 보인다
  (`SwingIdleTransactionService.describeQueryWindows`가 창의 실제 문맥을 비교). 알림의 다시 연결을 고르면 모든 창의 세션이 다시 연결된다.

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

오류 복구(#120)와 함께(2026-11-08, PostgreSQL, 수동 커밋, 증거 `m*-pg.txt.log`, `shots\m1-…`, `shots\m3-…`):
- B에 열린 트랜잭션(UPDATE), A에서 UPDATE → `SELECT 1/0` → `SELECT 1`: 알림 "서버가 트랜잭션을 중단함 … 쿼리 창 SQLQuery1.sql …"은 A에만 뜨고,
  B는 같은 세션(pid 34716)에서 `idle in transaction` 그대로. A는 알림의 롤백으로 끝냄.
- A의 세션을 밖에서 `pg_terminate_backend` → A에서 `SELECT 4`: 알림 "연결 끊김 … 57P01 … 쿼리 창 SQLQuery2.sql: 00:11:16부터 문장 2개", 다시 연결하지 않음.
  B는 같은 pid로 `idle in transaction`, 행 2는 밖에서 `orig`(커밋도 롤백도 되지 않음).

확인한 뒤 행을 `orig`로 되돌리고 확인용 DB를 지웠다. 연결 콤보로 연결 바꾸기와 PostgreSQL 다른 DB 고르기의 질문은 실행해 보지 않았다(창 닫기와 같은 `confirmEndSession` 경로).

### SWT와 다른 점

- SWT 편집기의 "별도 연결 전환" 명령(`SQLEditorHandlerSeparateConnectionSwitch`)은 옮기지 않았다. 연결 대화상자의 연결별 값으로 대신한다.
- SWT는 설정이 바뀌면 열린 편집기의 문맥을 바로 바꿨다(`EDITOR_SEPARATE_CONNECTION` 속성 변경 이벤트). Chromite는 열린 트랜잭션이 다른 세션으로
  옮겨진 것처럼 보이지 않도록 바꾼 뒤 연결하는 창부터 적용한다.
- SWT `OpenContextJob`은 새 문맥의 기본값을 정한 뒤 그 문맥을 커밋했다. Chromite는 기본값을 따로 정하지 않고 `initFrom` 복사만 쓴다(모델의 문맥 초기화가 이미 커밋한다).
- SWT 스크립트 파일에 저장해 둔 기본 스키마 복원(`SQLEditor.autoSaveActiveSchema`)은 Swing에 그 기능이 없어 없다.

## 오류 복구와 열린 트랜잭션

문장이 실패하면 모델의 실행 복구(`DBExecUtils.tryExecuteRecover`)가 원인에 따라 복구하고 다시 실행한다. 업스트림 복구는 묻지 않고 트랜잭션을 끝냈다
(설정 작업 에이전트가 2026-11-07에 발견).

| 경로 | 언제 | 업스트림이 한 일 | 잃는 것 |
|---|---|---|---|
| 중단된 트랜잭션(`TRANSACTION_ABORTED`) | PostgreSQL(과 그 계열, Trino): 트랜잭션 안에서 문장이 실패한 뒤의 문장이 "current transaction is aborted"(25P02) | 그 문맥(오류에 문맥이 없으면 데이터 소스의 모든 문맥)을 롤백하고 다시 실행. 롤백은 쿼리 관리자에 기록하지 않음(`InvalidateJob.invalidateTransaction`, `enableLogging(false)`) | 서버가 이미 중단한 트랜잭션이라 커밋할 수 있는 데이터는 없다. 그러나 사용자는 앞 문장이 아직 있다고 믿고, 상태 줄도 계속 "열린 트랜잭션"을 보였다 |
| 끊긴 연결(`CONNECTION_LOST`) | 모든 DB: SQLState 08xxx, 소켓 오류(SQL Server "Connection reset", MariaDB·PostgreSQL에서 세션이 끊김 등) | 데이터 소스와 그 네트워크 처리기를 쓰는 데이터 소스의 **모든** 문맥을 닫고(수동 커밋이면 닫기 전에 롤백, `JDBCDataSource.closeConnection`) 다시 연결한 뒤 다시 실행. 알림은 "다시 연결함" 정보뿐 | 끊긴 문맥의 트랜잭션(서버가 이미 버림)과 **끊기지 않은 다른 문맥**(Chromite Agent 세션 등)의 살아 있는 트랜잭션 |
| 연결 유지 핑(`KeepAlivePingJob`) | 연결 설정의 연결 유지 간격이 있을 때, 문맥 하나라도 죽었으면 | 위와 같은 다시 연결 | 위와 같음 |

복구를 거치는 호출: 쿼리 창의 SELECT·DDL 등(INSERT·UPDATE·DELETE는 `SQLQueryExecuteJob.shouldRecoverQuery`가 복구하지 않음), 메타데이터 읽기,
실행 계획, 자동 완성 분석, 데이터 전송, 결과 표 저장 등(`grep tryExecuteRecover`).

### 바꾼 것: 멈추고 묻는다

- **판단.** 복구가 끝낼 문맥 중 열린 트랜잭션이 있으면(`QMTransactionState.isOpen()`: 수동 커밋에서 사용자 문장이 있음, 상태 줄·목록과 같은 기준) 복구하지 않는다.
  다시 연결은 Chromite Agent 세션(`DBServiceUnrecordedSessions.isConnectionInUse`)도 본다. 판단은 `TransactionRecoveryGuard`(모델)가 한다.
  - 실패한 시도 자신의 문장은 셈에서 뺀다: 세션으로 부른 복구는 첫 시도 전의 트랜잭션 상태를 기억하고(`TransactionRecoveryGuard.Attempt`), 그 문맥은 그때 열려
    있었을 때만 연 것으로 본다. 커밋 직후 첫 SELECT에서 연결이 끊긴 경우처럼 잃을 것이 없으면 전처럼 다시 연결한다.
  - 자동 커밋 문맥과 메타데이터 읽기만 한 문맥은 잃을 것이 없으므로 전처럼 복구한다.
- **멈춤.** 복구하지 않고 원래 오류를 호출자에게 그대로 돌려준다(쿼리 창의 Messages 탭에 서버 오류). 핑은 다시 연결하지 않고 연결을 그대로 둔다.
- **알림(닫을 때까지 남음, `UIServiceIdleTransactions` → `SwingIdleTransactionService`).**
  - 중단된 트랜잭션: "서버가 트랜잭션을 중단함 — 오류가 나서 서버가 {쿼리 창}의 열린 트랜잭션을 중단했습니다. {시각}부터 실행한 문장 N개는 사라졌고 커밋해도
    저장되지 않습니다. 계속하려면 롤백하십시오." 단추 **롤백**은 다른 커밋·롤백과 같은 경로(`IdleTransactionMonitor.endTransaction`, 보여 준 트랜잭션만)다.
    같은 트랜잭션의 알림이 떠 있으면 다시 띄우지 않는다(멈춘 뒤의 문장은 모두 같은 오류).
  - 끊긴 연결: "연결 끊김 — 연결 X이(가) 끊겼습니다: {오류 첫 줄}. 다시 연결하면 아래의 열린 트랜잭션이 끝나므로 자동으로 다시 연결하지 않았습니다: {세션: 문장 N개}…
    아직 동작하는 세션의 변경을 남기려면 먼저 커밋하십시오(도구 > 열린 트랜잭션). 다시 연결하면 나머지는 버려집니다." 단추 **다시 연결**은 업스트림 복구와 같은
    `InvalidateJob.invalidateDataSource`를 사용자가 고른 뒤에 하고(`TransactionRecoveryGuard.reconnect`), 끝낸 트랜잭션을 쿼리 관리자에 롤백으로 기록한다
    (다시 연결은 문맥 객체를 그대로 두고 옛 연결을 기록 없이 닫아 상태 줄이 사라진 문장을 계속 보였기 때문이다). 핑은 같은 연결 객체에 한 번만 알린다.
- **왜 "알림만 하고 복구"가 아니라 "멈추고 묻기"인가.** 중단된 트랜잭션은 롤백 말고 할 일이 없지만, 사용자가 무엇이 사라졌는지 보고 직접 끝내야 다음 문장이 새
  트랜잭션이라는 것을 안다. 끊긴 연결은 살아 있는 다른 문맥의 트랜잭션까지 끝내므로 먼저 커밋할 기회를 주어야 한다.
- **PostgreSQL의 COMMIT.** 중단된 트랜잭션에 COMMIT을 보내면 서버가 ROLLBACK으로 끝낸다(pgjdbc 기본 설정에서 오류 없음). 그래서 알림에 "커밋해도 저장되지 않습니다"를 적었다.
- 절전 뒤 다시 검증(`DataSourceMonitorJob.invalidateSleptConnections`)과 절전 때 연결 닫기는 2026-11-08부터 쓰는 중인 연결(열린 트랜잭션 등)을 건너뛴다
  ([connection-lifecycle.md](connection-lifecycle.md) "절전"). 쿼리 창의 "실행 전 연결 다시 검증"(`isInvalidateBeforeExecute`)은 Swing이 켜지 않는다(기본 `false`, 재정의 없음).
  오류로 멈춘 스크립트의 자동 롤백(`STOP_ROLLBACK`)은 #117이 고쳤다([swing-options.md](swing-options.md)).

### 확인 (2026-11-07)

이 브랜치의 제품 빌드를 복사해 자기 작업 공간으로 실행하고 프로세스 안에서 Swing API로 누르는 스크립트(OS 입력 없음)로 확인했다. 연결은 수동 커밋,
전용 DB `chr_srd`의 `t`. 세션을 끊는 것은 다른 세션(`docker exec`)에서 했다(PostgreSQL `pg_terminate_backend`, SQL Server `KILL`, MariaDB `KILL`).
증거: `D:\Tools\_scratch\safety-recovery-drop\run\`(스크립트 `*.txt`, 결과 `*.txt.log`, 서버 상태 `server-state.txt`, 앱 로그 `app-workspace.log`, 창 그림 `shots\`).

| 확인 | 결과 |
|---|---|
| PostgreSQL: INSERT → `SELECT 1/0`(오류) → `SELECT 1` | `SELECT 1`은 25P02 오류로 멈춤, 알림 "서버가 트랜잭션을 중단함 … 23:31:51부터 실행한 문장 3개는 사라졌고 커밋해도 저장되지 않습니다", 상태 줄 "열린 트랜잭션 (문장 3개)" 그대로, 서버 `idle in transaction (aborted)`(롤백되지 않음). 다음 문장도 같은 오류지만 알림은 하나 (`pg-a1-aborted-notice.png`) |
| 알림의 롤백 | 서버 `idle`·`ROLLBACK`, 상태 줄 칸 사라짐, 다음 문장 정상 (`pg-a2-after-rollback.png`) |
| PostgreSQL: SELECT·INSERT(열린 트랜잭션) 뒤 세션 끊김 → `SELECT 4` | 다시 연결하지 않음(서버에 주 세션 없음), 알림 "연결 끊김 … 57P01 … 쿼리 창 SQLQuery1.sql: 문장 3개", 상태 줄 그대로 (`pg-b2-reconnect-held.png`) |
| 알림의 다시 연결 | 다시 연결("활성 연결 수 2/2"), 상태 줄 칸 사라짐(쿼리 관리자에 롤백 기록), 다음 문장 정상 (`pg-b3-after-reconnect.png`) |
| PostgreSQL: 롤백 뒤(열린 트랜잭션 없음) 세션 끊김 → `SELECT 6` | 전처럼 묻지 않고 다시 연결해 실행(로그 "Invalidate datasource"), 알림 없음 (`pg-c1-harmless-recovery.png`) |
| SQL Server: INSERT → `SELECT 1/0`(오류) → `SELECT 7` | 알림 없음, 서버 `open_tran = 1`(INSERT가 그대로 남음, SQL Server는 문장 오류로 트랜잭션을 끝내지 않음) (`mssql-d1-error-then-select.png`) |
| SQL Server: 세션 `KILL` → `SELECT 8` → 다시 연결 | 다시 연결하지 않고 알림("08S01 연결이 닫혔습니다", 문장 3개) → 다시 연결 뒤 칸 사라짐, 정상 (`mssql-d2-…`, `mssql-d3-…`) |
| MariaDB: INSERT → 없는 표 SELECT(오류) → `SELECT 7` | 알림 없음, 서버 `trx_state RUNNING, rows_modified 1`(INSERT가 그대로) (`maria-d1-error-then-select.png`) |
| MariaDB: 세션 `KILL` → `SELECT 8` → 다시 연결 | 다시 연결하지 않고 알림("08000 Socket error", 문장 4개) → 다시 연결 뒤 칸 사라짐, 정상 (`maria-d2-…`, `maria-d3-…`) |

연결 유지 핑 경로(`KeepAlivePingJob`)는 같은 판단을 쓰지만 연결 유지 간격을 켜고 실행해 보지는 않았다.

## 끊긴 세션의 쿼리 창

### 문제 (#124를 만들며 발견, 2026-11-07 저녁 후속 10·11번)

1. 쿼리 창의 자기 세션이 끊기면(서버가 세션을 끊음, 네트워크 끊김) 창을 닫을 수 없었다. 닫기 질문이 커밋 / 롤백 / 취소를 물었지만 커밋과 롤백이 모두
   끊긴 연결에서 실패해 창이 남았다. 끊긴 연결을 끊을 때(연결 끊기, 다시 연결, 삭제)와 Chromite를 끝낼 때도 같았다(`ExitTransactionCheck`의 한 경로).
2. 세션 하나가 끊기면 복구가 연결 전체를 다시 연결하려 했고, #120의 멈춤이 다른 창의 열린 트랜잭션 때문에 그 다시 연결을 막았다. 그래서 끊긴 창은
   다른 창의 트랜잭션이 끝날 때까지 쓸 수 없었고, 알림의 다시 연결을 고르면 다른 창의 트랜잭션이 버려졌다.

### 바꾼 것

- **끊긴 세션 알아보기**(`TransactionRecoveryGuard.isSessionLost`, 모델). 문맥에 연결이 없거나, 연결 확인(`DBCExecutionContext.checkContextAlive`, 연결 유지 핑과 같은
  확인. PostgreSQL·SQL Server는 `Connection.isValid`, 테스트 SQL이 있는 DB는 그 SQL)이 3초 안에 실패하면 끊긴 것이다. 3초 안에 답하지 않거나 문맥에서 문장이
  실행 중이면 살아 있다고 본다. 살아 있는 세션을 끊긴 것으로 보면 트랜잭션을 묻지 않고 버리게 되므로 모를 때는 예전처럼 묻는다.
  확인은 서버에 왕복 하나를 보내지만 트랜잭션을 끝내지 않고 쿼리 관리자에도 기록되지 않는다.
- **끝내기 전 질문**(`ExitTransactionCheck`: 창 닫기, 연결 바꾸기, 연결 끊기·다시 연결·삭제, Chromite 끝내기, 탐색기 개체 변경). 열린 트랜잭션이 있는 세션을
  찾은 뒤 위 확인을 진행 대화상자에서 한다(빨리 끝나면 대화상자는 보이지 않는다).
  - 모두 끊겼으면: "다음 세션의 연결이 끊겼습니다 … 커밋하지 않은 변경은 이미 사라졌습니다. 끊긴 세션의 트랜잭션은 서버가 롤백하므로 커밋도 롤백도 할 수 없습니다.
    버리고 계속하시겠습니까?" 단추 **버리기** / **취소**. 버리기는 쿼리 관리자에 롤백으로 적고(`discardLostTransaction`, 상태 줄·목록에서 사라짐) 하던 일(창 닫기,
    끊기)을 계속한다. 취소하면 아무것도 바꾸지 않는다.
  - 살아 있는 세션과 섞였으면: 기존 커밋 / 롤백 / 취소 질문이 살아 있는 세션만 나열하고, 아래에 "다음 세션은 연결이 끊겨 서버가 … 이미 롤백했습니다. 이 세션들은
    버립니다"를 덧붙인다. 커밋·롤백은 살아 있는 세션에만 한다.
  - 확인이 답하지 않아 살아 있다고 본 세션의 커밋·롤백이 연결 끊김 오류(`DBExecUtils.discoverErrorType` = `CONNECTION_LOST`)로 실패하면 그 세션에 대해 위 버리기
    질문을 한다. 전에는 여기서 오류를 보이고 멈췄다.
- **끊긴 세션만 다시 열기**(`TransactionRecoveryGuard.reopenLostSession`). 실행 복구(`DBExecUtils.tryExecuteRecover`의 끊긴 연결)와 연결 유지 핑에서, 실패한 문맥이
  인스턴스의 주 문맥도 메타데이터 문맥도 아니고(격리 문맥: 쿼리 창의 자기 세션, Activity Monitor 등 도구의 세션) 위 확인으로 정말 끊겼으면 그 문맥만 다시 연다
  (`DBCExecutionContext.invalidateContext`, 데이터 소스 다시 연결이 문맥마다 하는 것과 같은 단계, 인스턴스 잠금 안에서). 주·메타데이터 문맥과 다른 창의 세션,
  그 트랜잭션은 그대로다. 그 뒤 실행 복구는 같은 문장을 새 세션에서 다시 실행한다(업스트림 다시 연결 뒤와 같음).
  - 옛 연결은 닫혔으므로 쿼리 관리자에 롤백으로 적는다(상태 줄의 "열린 트랜잭션"이 사라진 문장을 계속 보이지 않게).
  - 알림(`showSessionReopened`): 실패한 시도 전에 사용자 문장이 있었으면 "세션 끊김 — {연결}의 쿼리 창 {창}의 세션이 끊겼습니다: {오류 첫 줄}. 이 세션만 새로
    열었고 다른 세션과 그 트랜잭션은 건드리지 않았습니다. 커밋하지 않은 트랜잭션({시각}부터 문장 N개)은 사라졌습니다 …"(닫을 때까지 남음). 문장 수는 쿼리
    관리자의 그 트랜잭션 문장 수(상태 줄과 같은 기준, 실패한 시도 자신은 빼고)다. 잃은 것이 없으면 잠시 보이는 정보 알림 "세션 다시 열림"이다.
  - 다시 열지 못하면(서버가 아예 내려감, SSH 터널이 죽음 등) 예전 경로로 간다: 데이터 소스 전체 다시 연결과 #120의 멈춤.
  - 주 문맥(창들이 함께 쓰는 설정)이나 메타데이터 문맥이 끊긴 경우는 바꾸지 않았다. 여러 창과 탐색기가 함께 쓰므로 #120의 멈춤과 사용자가 고르는 다시 연결을 그대로 쓴다.
- **살아 있는 세션은 묻지 않고 끝내지 않는다.** 다시 열기와 버리기는 확인으로 끊긴 것이 확인된 세션에만 한다. 끊긴 세션의 트랜잭션은 서버가 세션과 함께
  롤백하므로(PostgreSQL `pg_terminate_backend`, SQL Server `KILL`, 네트워크가 끊기면 서버가 끊김을 알아챌 때) 버려도 잃을 것이 없다.

### 확인 (2026-11-08)

이 브랜치의 제품 빌드를 복사해 자기 작업 공간으로 실행하고 프로세스 안에서 Swing API로 누르는 스크립트(OS 입력 없음)로 확인했다. 연결은 수동 커밋,
전용 DB `chr_dead_session`의 `ds_test`(행 1~10, `orig`). 세션은 밖에서(`docker exec`) PostgreSQL `pg_terminate_backend`, SQL Server `KILL`로 끊었다.
증거: `D:\Tools\_scratch\dead-session-windows\`(스크립트 `pg*.txt`, `ms*.txt`, 결과 `*.txt.log`, 서버 상태 `server-state.txt`, 앱 로그 `app-workspace.log`, 그림 `shots\`).

| 확인 | PostgreSQL 17 (55432) | SQL Server 2022 (51433) |
|---|---|---|
| A·B에 열린 UPDATE, A의 세션을 끊고 A 닫기 | 저장 질문 뒤 "다음 세션의 연결이 끊겼습니다: PG test (SQLQuery1.sql) …" 버리기 / 취소 → 버리기로 창 닫힘. B는 같은 pid 35358로 `idle in transaction`(xid 있음), 상태 줄 그대로, 행 1·2 `orig` (`pg-02-…`) | 같은 질문 → 닫힘. B는 같은 SPID 55, `open_transaction_count` 1, `READPAST`로 행 2가 B의 잠금으로 빠짐 (`ms-02-…`) |
| 새 창 C에 열린 UPDATE, C의 세션을 끊고 C에서 `SELECT 4` | C만 새 세션(pid 35405 → 35420)으로 실행됨. 알림 "세션 끊김 … 57P01 … 커밋하지 않은 트랜잭션(00:32:52부터 문장 2개)은 사라졌습니다". 주 35356·메타데이터 35357·B 35358 그대로 (`pg-06-…`) | C만 새 세션(08S01 "연결이 닫혔습니다", 문장 1개), 주 52·메타데이터 53·B 55(`open_transaction_count` 1) 그대로 (`ms-06-…`) |
| C에 살아 있는 열린 트랜잭션, B의 세션을 끊고 연결 끊기 | "PG test (SQLQuery3.sql)" 커밋 / 롤백 / 취소 + "다음 세션은 연결이 끊겨 … 이 세션들은 버립니다: PG test (SQLQuery2.sql)" → 취소로 그대로 (`pg-07-…`) | 같음 (`ms-07-…`) |
| C를 롤백한 뒤 다시 연결 끊기 | "다음 세션의 연결이 끊겼습니다: PG test (SQLQuery2.sql)" 버리기 / 취소 → 버리기로 끊김, 서버 세션 0, 행 1~5 `orig` (`pg-08-…`) | 같음, 서버 세션 0 (`ms-08-…`) |

연결 유지 핑 경로와 네트워크가 끊긴 경우(확인이 3초 안에 답하지 않음)는 실행해 보지 않았다. 확인한 뒤 확인용 DB를 지웠다.

## 커밋 방식 전환

SWT 대조 B-S18(영역 A-C5의 자동 커밋 부분): 열린 세션의 자동 커밋 / 수동 커밋을 연결을 고치지 않고 바꾼다.

### 사용자 결정 (2026-11-08)

- Query 메뉴와 SQL Editor 도구 막대에 둔다. 상태 줄에는 두지 않는다(B-S18a, swing-frontend.md "자동 커밋 상태"의 2026-11-07 결정). 상태 줄은 방식을 보이기만 하고, 누르면 예전처럼 연결 설정의 Auto-commit으로 안내한다.
- 수동 → 자동에서 열린 트랜잭션이 있으면 커밋 / 롤백 / 취소를 묻고 취소가 기본이다. 자동 → 수동은 묻지 않는다. 실행 중에는 바꾸지 않는다.

### 자리와 모양

- Query 메뉴: Commit, Roll Back, Transaction Log… 아래의 확인 표시 항목 **Auto-Commit**(한국어 "자동 커밋"). 자동 커밋이면 표시가 켜져 있다. 글자가 바뀌지 않는 확인 항목이라 "켜짐 = 자동 커밋"이 헷갈리지 않는다.
- SQL Editor 도구 막대: Roll Back 단추 뒤에 상태 줄과 같은 점(자동 커밋 초록, 수동 커밋 빨강)과 지금 방식의 글자("자동 커밋"/"수동 커밋") 단추. 누르면 다른 방식으로 바꾼다. Swing 아이콘 팩에 SWT의 트랜잭션 방식 아이콘(`TXN_COMMIT_AUTO`/`MANUAL`)에 해당하는 것이 없어 상태 줄의 점을 썼다. 글자가 지금 방식을 말하므로 눌림 상태(토글 단추)는 쓰지 않는다. 쿼리 창이 없거나 연결되지 않았으면 "커밋 방식"으로 꺼진다.
- 둘은 같은 `Action`(`MainFrame.commitModeAction`)이고, 도구 설명은 지금 방식과 누르면 무엇이 되는지, 세션을 함께 쓰면 그 창들에도 적용된다는 것을 말한다.
- 켜짐(`SqlEditorPanel.canSwitchCommitMode`): 상태 줄이 방식을 보이고(연결됨), 그 창이 실행·예상 계획·결과 저장 중이 아니고, 열린 트랜잭션에서 문장이 실행 중이 아니고, 전환 중이 아닐 때. 상태 줄이 보이는 방식이 바뀌면(`showAutoCommit`) 메인 창에 알려 메뉴와 단추가 따라온다(스마트 커밋이 되돌린 경우, 연결 설정의 Auto-commit을 열린 세션에 적용한 경우도).

### 동작 (`CommitModeSwitch.switchMode`)

1. 대상은 그 창의 실행 문맥이다(`getExecutionContext`: 창의 자기 세션, 또는 주 문맥). 연결 설정의 기본값(`bootstrap.autocommit`)은 바꾸지 않는다. 그래서 다시 연결하면 설정의 방식으로 돌아가고, 그동안 상태 줄 도구 설명에 "연결 설정은 지금 …입니다. 다시 연결하면 적용됩니다"가 나온다. 무효화·다시 연결은 문맥의 지금 방식을 이어받는다(`JDBCExecutionContext.invalidateContext`가 `autoCommit`을 넘김).
2. 수동 → 자동이고 세션에 커밋하지 않은 문장이 있으면(상태 줄의 열린 트랜잭션 `QMTransactionState.isOpen`, 또는 데이터를 바꾼 실행 `QMUtils.isTransactionActive`) **커밋하고 바꾸기 / 롤백하고 바꾸기 / 취소**(취소 기본)를 묻는다. JDBC `setAutoCommit(true)`가 열린 트랜잭션을 묻지 않고 커밋하기 때문이다. SELECT만 있어도 묻는다(상태 줄이 열린 트랜잭션을 보이는 기준과 같게, SELECT … FOR UPDATE의 잠금).
3. 창이 세션을 함께 쓰면(쿼리 창마다 별도 연결이 꺼졌거나 "단일 연결") "PG test의 쿼리 창들이 한 세션을 함께 씁니다(SQLQuery1.sql, SQLQuery2.sql). 커밋 방식이 그 창 모두에서 바뀝니다"를 알린다. 열린 트랜잭션 질문이 있으면 그 질문에 넣고, 없으면 **…으로 바꾸기 / 취소**(취소 기본)로 묻는다. 자기 세션이면 자동 → 수동은 묻지 않는다.
4. 진행 대화상자(`ProgressDialog.runTask`)의 작업 스레드에서 다시 확인한다: 그 세션에서 문장이나 `DataSourceJob`이 돌고 있으면 아무것도 하지 않고 "세션에서 문장이 실행 중입니다…"를 알린다. 질문 뒤 트랜잭션이 바뀌었거나(시작 시각) 묻지 않았는데 트랜잭션이 생겼으면(함께 쓰는 다른 창) "질문한 뒤 세션의 트랜잭션이 바뀌었습니다… 커밋, 롤백, 전환 모두 하지 않았습니다"를 알린다.
5. 답에 따라 UTIL 세션으로 `commit`/`rollback`을 먼저 하고 `DBCTransactionManager.setAutoCommit`을 부른다. 실패하면 오류 대화상자(창과 연결 이름, 원인). 끝나면 창이 실제 방식을 다시 읽는다. 함께 쓰는 다른 창은 1초마다의 쿼리 관리자 상태 읽기가 방식 변화를 보고 다시 읽는다(`refreshOpenTransaction`).

### 스마트 커밋, 상태 줄과의 관계

- 스마트 커밋이 수동 커밋으로 바꾼 세션을 여기서 자동 커밋으로 바꾸면(같은 질문을 거침), 모델의 `handleTransactionAutocommit`으로 스마트 커밋의 되돌릴 목록에서 빠진다. 여기서 수동 커밋으로 바꾼 세션은 스마트 커밋이 바꾼 것이 아니어서 커밋·롤백 뒤에도 수동 커밋으로 남는다(사용자가 고른 방식).
- 연결 편집의 Auto-commit 저장(`AutoCommitApplier`)은 지금처럼 열린 세션을 설정 값으로 맞춘다. 여기서 바꾼 세션도 그때 설정 값으로 돌아가고, 열린 트랜잭션이 있으면 그쪽 질문이 묻는다.

### SWT와 다른 점

- SWT `DataSourceAutoCommitHandler`(도구 막대 토글)와 `DataSourceTransactionModeContributor`(Database > Transaction Mode의 Auto-Commit / Manual Commit 라디오)는 활성 편집기의 문맥에 바로 `setAutoCommit`을 불렀다. 열린 트랜잭션을 묻지 않아 수동 → 자동에서 JDBC가 커밋했다. Chromite는 묻는다.
- SWT는 실행 중에도 단추가 켜져 있었다(작업으로 바꿈). Chromite는 끄고, 작업에서도 다시 확인한다.
- SWT는 권한(`PERMISSION_PROJECT_ADMIN`/`DATABASE_DEVELOPER`)을 확인했다. CE 데스크톱에서는 늘 허용이라 옮기지 않았다.
- SWT 메뉴는 수동 커밋 항목에 격리 수준 이름을 붙이고 격리 수준과 스마트 커밋 항목을 함께 두었다. 격리 수준 바꾸기(B-S19)는 이 작업에 없고, 스마트 커밋은 Options > Connection Types에 있다.

### 확인 (2026-11-08)

이 브랜치의 제품 빌드를 복사해 자기 작업 공간·데이터 폴더(`CHROMITE_DATA`)로 실행하고 프로세스 안에서 Swing API로 누르는 스크립트(OS 입력 없음)로 확인했다.
연결은 수동 커밋(`bootstrap.autocommit = false`), 전용 DB `chr_commit_toggle`의 `ct_test`(PostgreSQL·SQL Server), Oracle은 `SYSTEM.CHR_CT_TEST`(행 1~10, `orig`).
행 값과 열린 트랜잭션은 다른 세션(`docker exec … psql` / `sqlcmd` / `sqlplus`)에서 읽었다. 증거: `D:\Tools\_scratch\commit-mode-toggle\`(스크립트 `*.txt`, 결과 `*.txt.log`, 그림 `shots\`, 앱 로그 `app-switch-log.txt`).

| 확인 (한국어, 창마다 세션) | PostgreSQL 17 | SQL Server 2022 | Oracle 23 Free |
|---|---|---|---|
| 처음: 메뉴 "자동 커밋" 표시 꺼짐, 단추 "● 수동 커밋", 연결 전에는 "커밋 방식" 꺼짐 | 예 | 예 | 예 |
| A: UPDATE id 1 → 전환 → 질문 → 취소 | 수동 그대로, 트랜잭션 그대로(`idle in transaction`, xid 있음), id 1 `orig` | 그대로(SPID 54 `open_transaction_count` 1, `READPAST`로 id 1 빠짐) | 그대로(`v$transaction`에 SID 61), id 1 `orig` |
| 다시 전환 → 롤백하고 바꾸기 | 자동, id 1 `orig`, 트랜잭션 없음 | 같음 | 같음 |
| 같은 연결의 창 B(자기 세션) | 수동 그대로 | 수동 그대로 | 수동 그대로 |
| 도구 막대 단추로 자동 → 수동 | 질문 없음, 수동 | 같음 | 같음 |
| UPDATE id 2 → 단추 → 커밋하고 바꾸기 | 자동, id 2 바뀜 | 같음 | 같음 |
| 자동 커밋에서 UPDATE id 3 | 바로 커밋(열린 트랜잭션 칸 없음, 밖에서 바뀜) | 같음 | 같음 |
| 실행 중(`pg_sleep(8)`, `WAITFOR`, `DBMS_SESSION.SLEEP`) | 메뉴·단추 꺼짐, 메뉴를 눌러도 바뀌지 않음, 끝나면 켜짐 | 같음 | 같음 |

| 확인 (영어, `-nl en`) | 결과 |
|---|---|
| PostgreSQL, 세션을 함께 쓰는 설정(`database.editor.separate.connection = NEVER`): UPDATE 뒤 전환 | 질문에 "The query windows of PG test share one session (SQLQuery1.sql, SQLQuery2.sql)…"가 들어감. Cancel → 그대로(`idle in transaction`), Roll Back and Switch → 두 창 모두 자동, id 1 `orig` |
| 함께 쓰는 세션의 자동 → 수동 | "Switch to Manual Commit / Cancel"(Cancel 기본). Cancel → 자동 그대로, Switch → 두 창 모두 수동 |
| SQL Server: UPDATE id 4 → Commit and Switch, 다시 수동 | id 4 `EN-committed`, 열린 트랜잭션 0, 수동으로 바꿀 때 질문 없음 |
| Oracle: UPDATE id 4 → Roll Back and Switch, 메뉴로 다시 수동 | id 4 `orig`, `v$transaction` 0 |

전환 뒤에도 작업 공간의 `data-sources.json`의 세 연결은 `"autocommit": false` 그대로였다(연결의 기본값을 바꾸지 않음). 확인한 뒤 확인용 DB와 표를 지웠다.

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

- 트랜잭션 로그의 "모든 문장(메타데이터 포함) 보이기". "지난 트랜잭션 보이기"는 2026-11-08에 더했다.
- 커밋·롤백 뒤 알림과 그 설정(SWT `transaction.show.notifications`). 트랜잭션 설정 화면(A-P1 "Transactions")과 함께 한다.
- 실행 중 자동/수동 커밋 전환(B-S18)은 2026-11-08에 더했다(위 "커밋 방식 전환"). 실행 중 세션의 격리 수준 바꾸기(B-S19)는 아직 없다. 스마트 커밋(B-S16)은 2026-11-08에 더했다([connection-lifecycle.md](connection-lifecycle.md) "스마트 커밋"). 쿼리 창마다 별도 연결(B-S22)은 2026-11-07에 더했다(위 절).
- 서버가 스스로 트랜잭션 전체를 롤백하는 경우(SQL Server 교착 상태 희생자 1205·`XACT_ABORT ON`, MariaDB 교착 상태 1213)는 Chromite의 복구가 아니라서
  알리지 않고, 상태 줄도 계속 열린 트랜잭션을 보인다. 오류 코드로 알아보거나 서버에 트랜잭션 수(`@@TRANCOUNT` 등)를 묻는 것이 필요하다.
- PostgreSQL의 중단된 트랜잭션에 Query > Commit을 누르면 서버가 롤백하고 오류가 없다(pgjdbc `raiseExceptionOnSilentRollback` 기본 꺼짐). 알림이 그것을 말하지만
  커밋 명령 자체는 막지 않는다.
- 사용자가 고른 다시 연결 뒤 Chromite Agent의 열린 트랜잭션 기록(`AgentSql`)은 지우지 않는다(모델에서 위 번들을 알 수 없다). 에이전트가 끝내거나 끊을 때 정리된다.
- 주 문맥(창들이 세션을 함께 쓰는 설정)이나 메타데이터 문맥이 끊기면 여전히 데이터 소스 전체를 다시 연결하고, 다른 창에 열린 트랜잭션이 있으면 #120의 멈춤이 막는다.
  끊긴 격리 세션은 2026-11-08부터 그 세션만 다시 연다("끊긴 세션의 쿼리 창").
- "연결 끊김" 알림(#120)의 목록은 끊긴 세션의 트랜잭션도 "다시 연결하면 끝나는 트랜잭션"으로 센다. 끊긴 세션은 이미 끝났으므로 그 목록에서 빼는 것이 더 정확하다.
- Chromite Agent 세션(`AgentSql`)은 쿼리 관리자 밖이라 끝내기 전 질문에서 끊긴 세션인지 확인하지 않는다. 그 세션이 끊겼으면 커밋·롤백이 실패해 예전처럼 멈춘다.

## 변경 이력

- 2026-11-07 최초 작성: Query > Commit / Roll Back(SWT 단축키, SQL Editor 도구 막대 단추), 트랜잭션 로그, 열린 트랜잭션 목록.
  PostgreSQL·SQL Server에서 실행 확인(위 "확인")
- 2026-11-07 쿼리 창마다 별도 연결(SWT 대조 B-S22): 창마다 격리된 세션, 창 닫기·연결 바꾸기·DB 인스턴스 바꾸기 전 트랜잭션 질문,
  상태 줄·명령·로그·목록·유휴 알림·결과 저장·자동완성·Chromite Agent가 창의 세션을 따름, Options와 연결 대화상자의 설정, 세션 수 표시.
  PostgreSQL·SQL Server에서 실행 확인("쿼리 창마다 별도 연결 > 확인")
- 2026-11-07 오류 복구와 열린 트랜잭션: 실행 복구와 연결 유지 핑이 열린 트랜잭션을 묻지 않고 롤백(PostgreSQL 중단된 트랜잭션)하거나
  다시 연결로 끝내던 것을 막음. 멈추고 롤백·다시 연결을 알림으로 물음(`TransactionRecoveryGuard`). PostgreSQL·SQL Server·MariaDB에서 확인
- 2026-11-08 origin/devel(#120 오류 복구, #121, #122) 병합: 오류 복구가 창마다의 세션을 보는지 확인하고 "쿼리 창마다 별도 연결 > 트랜잭션 안전"과 남은 일에 적음
- 2026-11-08 끊긴 세션의 쿼리 창: 끝내기 전 질문이 끊긴 세션에 버리기 / 취소를 물음(커밋·롤백이 실패해 창을 닫거나 끊을 수 없던 것), 실행 복구와 연결 유지 핑이
  끊긴 격리 세션만 다시 열고 잃은 문장 수를 알림(다른 창의 세션과 트랜잭션은 그대로). PostgreSQL·SQL Server에서 확인("끊긴 세션의 쿼리 창 > 확인")
- 2026-11-08 트랜잭션 로그의 지난 트랜잭션, 스마트 커밋, 절전 경로의 트랜잭션 안전은 [connection-lifecycle.md](connection-lifecycle.md)에 적고 이 문서의 해당 줄을 고침
- 2026-11-08 커밋 방식 전환(SWT 대조 B-S18, 사용자 결정: Query 메뉴와 SQL Editor 도구 막대): Query &gt; Auto-Commit과 도구 막대 단추가 활성 창 세션만 바꿈,
  수동 → 자동은 열린 트랜잭션이 있으면 커밋하고 바꾸기 / 롤백하고 바꾸기 / 취소(취소 기본), 세션을 함께 쓰면 그 창들을 알리고 확인, 실행 중 꺼짐,
  연결의 기본값은 그대로(`chromite/commit-mode-toggle`). PostgreSQL·SQL Server·Oracle, 한국어·영어에서 확인("커밋 방식 전환 > 확인")
