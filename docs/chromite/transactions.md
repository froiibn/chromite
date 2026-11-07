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
- 보이지 않는 것: AI 도우미·MCP 도구의 격리 세션(쿼리 관리자를 거치지 않음). 설명 문구에 적었다. 끝낼 때와 끊을 때는 `ExitTransactionCheck`가 이들도 묻는다.
- SWT와 다른 점: SWT의 "Show all connections"(트랜잭션 없는 문맥도 보이기)와 아래 붙은 로그는 두지 않았다. 끝낼 것이 없는 문맥이고, 로그는 단추로 연다.

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
- 스마트 커밋(B-S16), 실행 중 자동/수동 커밋 전환(B-S18), 쿼리 창마다 별도 연결(B-S22)은 이 작업에 들어 있지 않다.

## 변경 이력

- 2026-11-07 최초 작성: Query > Commit / Roll Back(SWT 단축키, SQL Editor 도구 막대 단추), 트랜잭션 로그, 열린 트랜잭션 목록.
  PostgreSQL·SQL Server에서 실행 확인(위 "확인")
