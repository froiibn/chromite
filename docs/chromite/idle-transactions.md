# 열린 트랜잭션 표시와 유휴 트랜잭션 처리

## 목적

사용자는 트랜잭션을 끝냈다고 생각하고 둔 세션이 트랜잭션을 쥔 채 다른 세션을 막은 잠금 사고를 겪었다(2026-11-07 사용자 요청).
그래서 두 가지를 한다.

- 지금 쿼리 창 세션에 열린 트랜잭션이 있으면 보여 준다.
- 열린 트랜잭션이 일정 시간 유휴 상태이면 알린다. 사용자가 연결 설정에서 켠 경우에만 묻지 않고 롤백한다.

사용자 결정 "설정 시 자동 롤백":

- 기본은 알리고 묻는다(커밋 / 롤백 / 열어 두기).
- 사용자가 그 연결에서 자동 롤백을 직접 켰을 때만 묻지 않고 롤백하고, 그 뒤 보이는 알림을 남긴다.
- 자동 커밋은 하지 않는다.

이것은 "트랜잭션 안전" 규칙(세션의 트랜잭션을 조용히 끝내지 않는다)의 유일한 예외다.

## 구조

| 부분 | 번들 | 클래스 |
|---|---|---|
| 트랜잭션 상태 읽기 | `com.seizia.chromite.model` | `QMUtils.getTransactionState`, `QMTransactionState` |
| 유휴 감시, 커밋·롤백 공용 경로 | `com.seizia.chromite.model` | `runtime.jobs.IdleTransactionMonitor` (`DataSourceMonitorJob`이 3초마다 부름) |
| 자동 롤백 | `com.seizia.chromite.model` | `runtime.jobs.EndIdleTransactionsJob` |
| UI 서비스 인터페이스 | `com.seizia.chromite.model` | `runtime.ui.UIServiceIdleTransactions` |
| UI 서비스 구현(알림, 롤백 알림) | `com.seizia.chromite.ui.swing` | `SwingIdleTransactionService` (`plugin.xml`의 `com.seizia.chromite.service` 확장으로 등록) |
| 상태 줄 표시 | `com.seizia.chromite.ui.swing` | `QueryStatusLine`(칸), `SqlEditorPanel`(1초 타이머, 커밋·롤백) |
| 남는 알림 | `com.seizia.chromite.ui.swing` | `NotificationToast.showWithActions` |
| 설정 | `com.seizia.chromite.model`, `registry`, `ui.swing` | `DBPConnectionConfiguration`, `DataSourceSerializerModern`, `ConnectionSettingsPanel` |
| 유휴 연결 닫기 | `com.seizia.chromite.model` | `runtime.jobs.DataSourceMonitorJob`(`isIdleConnection`), `QMUtils.getLastUseTime` |
| 쿼리 관리자 밖 세션(Chromite Agent) | `com.seizia.chromite.model`, `model.ai.tools` | 서비스 `runtime.DBServiceUnrecordedSessions`, 구현 `AgentSessionsService`(`AgentSql.isInUse`, `getLastUseTime`, ai.tools `plugin.xml`) |
| 모델 알림 표시 | `com.seizia.chromite.ui.swing` | `SwingModelNotifications`(`ChromiteSwingApplication.start`에서 `ChromiteNotifications.setHandler`), `NotificationToast.showUntilClosed` |

### 트랜잭션 상태(쿼리 관리자)

`QMUtils.getTransactionState(context)`는 쿼리 관리자(QM)가 메모리에 기록한 실행 문맥의 현재 트랜잭션을 읽는다. 드라이버를 부르지 않아 EDT에서 1초마다 읽어도 된다.

- 열림(`isOpen`): 수동 커밋 모드이고 마지막 커밋·롤백 뒤 사용자 문장이 하나 이상 실행됨.
  SELECT만 실행해도 열림이다. PostgreSQL·MySQL·SQL Server(수동 커밋은 암시적 트랜잭션)는 SELECT로도 트랜잭션이 시작되고 잠금이나 스냅숏을 쥘 수 있기 때문이다.
- 문장 수는 사용자 문장만 센다. 메타데이터 읽기(META)와 내부 작업(UTIL)은 세지 않는다. 탐색기가 같은 문맥에서 메타데이터를 읽을 때마다 유휴 시간이 0으로 돌아가면 안 되기 때문이다.
- 데이터 변경 문장 수(`getUpdateCount`)는 QM의 "트랜잭션에 영향을 주는 문장"(DML·DDL, 갱신 행 수가 있는 문장, 실패한 문장)이다.
- 시작 시각 = 첫 사용자 문장의 시작 시각. 트랜잭션을 구별하는 값으로도 쓴다.
- 마지막 문장 시각 = 마지막 사용자 문장이 끝난 시각(실행 중이면 시작 시각).
- 실행 중(`isExecuting`) = 목적과 관계없이 그 문맥에서 끝나지 않은 문장이 있음.

에디터에 `COMMIT`/`ROLLBACK`을 입력해 실행해도 실행 엔진이 트랜잭션 관리자로 끝내므로(`SQLQueryExecuteJob.handleTransactionStatements`) QM 상태가 바로 닫힌다.

### 열린 트랜잭션 표시(쿼리 창 상태 줄)

- 자리: 커밋 방식 칸(자동 커밋/수동 커밋 점) 바로 뒤.
- 보이는 때: 쿼리 창 실행 문맥(연결의 주 문맥, 같은 연결의 쿼리 창이 함께 씀)에 열린 트랜잭션이 있을 때만. 자동 커밋이거나 실행한 문장이 없으면 숨긴다.
- 모양: `status.warning` 점(커밋 방식 점과 같은 크기, 주의이지 오류가 아님) + `열린 트랜잭션 00:03:15 (문장 3개)`. 경과 시간은 트랜잭션 시작부터이고 1초마다 갱신한다.
- 도구 설명: 시작 시각, 문장 수와 데이터 변경 문장 수, 마지막 문장 끝 시각, "클릭하면 커밋하거나 롤백합니다".
- 클릭: 칸 위에 커밋 / 롤백 메뉴. 고르면 메뉴를 연 때의 트랜잭션만 끝낸다(아래 "커밋·롤백 공용 경로"). 사용자가 직접 고르는 동작이므로 허용된다.
  같은 메뉴의 트랜잭션 로그…는 그 트랜잭션의 문장을 보인다([transactions.md](transactions.md)).

### 유휴 감시

`IdleTransactionMonitor`는 `DataSourceMonitorJob`(3초 주기)이 연결된 연결마다 부른다.

- 유휴 = 그 트랜잭션의 실행 문맥에서 마지막 사용자 문장이 끝난 뒤 지난 시간. 전역 마우스·키보드 활동은 쓰지 않는다. 사고는 다른 창에서 일하는 동안 한 트랜잭션이 놓여 있던 것이었다.
- 기간을 넘으면:
  - 자동 롤백 꺼짐(기본): UI 서비스에 알림을 한 번 요청한다. 답(열어 두기, ×)이 오기 전에는 다시 요청하지 않는다.
    열어 두기는 그때부터 한 기간 뒤로 미룬다. 그 문맥에서 문장을 다시 실행하면 유휴 시간과 미루기가 처음부터 시작하고 떠 있던 알림은 닫힌다.
    답하지 않으면 아무것도 하지 않는다(자리에 아무도 없으면 아무 일도 없다).
  - 자동 롤백 켜짐: `EndIdleTransactionsJob`이 롤백 직전에 같은 트랜잭션인지, 아직 기간 넘게 유휴인지, 설정이 아직 켜져 있는지 다시 보고 롤백한다.
    그 뒤 닫을 때까지 남는 알림을 띄운다. 실패하면 오류 알림을 띄우고 한 기간 뒤에 다시 한다.
- 실행 중에는 아무것도 하지 않는다: 그 문맥에 끝나지 않은 문장이 있거나 그 문맥의 `DataSourceJob`(SQL 편집기 실행 등, 문장 사이 포함)이 있으면 유휴가 아니다.
- UI 서비스가 없으면(헤드리스) 알리지도 롤백하지도 않는다. 보이는 알림 없는 롤백은 허용하지 않는다.
- 한 바퀴가 끝나면 이번에 보지 못한 문맥(연결 끊김, 문맥 닫힘)의 상태를 지우고 떠 있던 알림을 닫는다.

### 알림(SwingIdleTransactionService)

- 메인 창 오른쪽 아래 알림(`NotificationToast`)이다. 모달 대화상자로 막지 않는다.
- 답 단추(커밋 / 롤백 / 열어 두기)가 있는 알림은 시간이 지나도 사라지지 않는다. 자리를 비운 사이 사라지면 돌아온 사용자가 볼 수 없기 때문이다.
- 내용: 연결, 쿼리 창(주 문맥이면 그 연결의 쿼리 창 파일 이름, 아니면 문맥 이름), 유휴 시간, 시작 시각과 문장 수.
- 자동 롤백 알림: 연결, 쿼리 창, 유휴 시간, 취소된 문장 수와 데이터 변경 문장 수. 닫을 때까지 남는다.
- 모델 알림(`ChromiteNotifications`)은 쓰지 않는다. 알림은 이 서비스가 직접 띄운다. 그래서 아래 "모델 알림"을 연결한 뒤에도 같은 일이 두 번 보이지 않는다.

### 커밋·롤백 공용 경로

`IdleTransactionMonitor.endTransaction(monitor, context, transactionStart, commit)`을 알림 단추, 상태 줄 메뉴, 자동 롤백이 함께 쓴다.
2026-11-07부터 Query 메뉴·SQL Editor 도구 막대·단축키의 Commit / Roll Back과 열린 트랜잭션 목록도 이 경로를 쓴다([transactions.md](transactions.md)).

- 그 문맥의 트랜잭션이 보여 준 트랜잭션(시작 시각)과 같고 실행 중이 아닐 때만 한다. 그 사이 다른 트랜잭션이 시작되었으면 건드리지 않고 "아무것도 하지 않았습니다"라고 알린다.
- 커밋·롤백 자체는 다른 기능과 같은 트랜잭션 관리자 호출(`DBCTransactionManager.commit` / `rollback`, UTIL 세션)이다. 새 JDBC 호출을 만들지 않았다.
- Swing 쪽은 작업 스레드에서 하고(`SwingIdleTransactionService.endTransaction`), 실패하면 오류 대화상자를 띄운다.

## 설정

연결 편집 > 연결 속성 탭, 유휴 연결 닫기 아래.

| 설정 | 저장 위치 (`data-sources.json`의 `configuration`) | 기본값 |
|---|---|---|
| 유휴 상태로 열려 있는 트랜잭션 알림(분) | `idleTransactionReminder`(켜기/끄기), `idleTransactionPeriod`(초) | 연결 유형을 따름: 켜짐, 개발 30분 · 테스트 15분 · 운영 10분 |
| 묻지 않고 자동으로 롤백 | `idleTransactionRollback` | 꺼짐(모든 유형, 기존 연결 포함) |

- 알림과 기간은 연결 유형의 기존 값(`DBPConnectionType.isAutoCloseTransactions`, `getCloseIdleTransactionPeriod`)을 기본으로 쓴다.
  연결 유형과 같은 값이면 저장하지 않아 연결이 계속 유형을 따른다. 연결 유형을 바꾸면 알림 값만 그 유형의 기본값으로 바뀐다.
- 자동 롤백은 별도 설정이고 사용자가 그 연결에서 직접 켰을 때만 켜진다. 업스트림의 `autoCloseTransactions=true`(유형 기본값)는 "알림"으로만 읽고 롤백 동의로 읽지 않는다.
  켜면 경고 문구("유휴 트랜잭션의 커밋하지 않은 변경을 묻지 않고 버립니다. 버린 뒤 알림이 뜹니다.")가 보인다. 알림을 끄면 자동 롤백도 꺼진다.
- 설정은 감시가 매번 읽으므로 저장하면 다시 연결하지 않아도 적용된다.
- 연결 유형의 알림과 기간은 Options > Connection Types에서 바꾼다(2026-11-07, [swing-options.md](swing-options.md)). 자동 롤백의 유형별 기본값은 두지 않았다
  (모든 유형 꺼짐). 사용자가 연결마다 켜는 것이 결정이고, 유형에 두려면 모델에 새 필드가 필요하다.

## 설계 결정과 이유

- **감지는 모델, 묻기는 UI 서비스**: AGENTS-Architecture.md의 모델/UI 분리. 모델은 `UIServiceIdleTransactions`만 알고 Swing 번들이 구현을 등록한다.
- **QM 기반**: 드라이버에 묻지 않으므로 1초마다 표시해도 부담이 없고, 연결이 막혀 있어도 감시가 멈추지 않는다.
- **한 메커니즘**: 업스트림의 유휴 트랜잭션 롤백을 이 감시로 바꾸었다(아래).
- **열어 두기 = 한 기간 미루기**: 3초마다 다시 묻지 않는다.
- **오래된 알림 보호**: 알림 단추와 상태 줄 메뉴는 보여 준 트랜잭션만 끝낸다.

## upstream 대비 변경점

업스트림에도 유휴 트랜잭션 롤백이 있었지만 Swing 앱에서는 동작하지 않았고, 동작했다면 묻지 않고 롤백했다.

- 업스트림: `DataSourceMonitorJob.endIdleTransactionOrConnection`이 전역 사용자 활동 시각(`DBPApplication.getLastUserActivityTime`) 기준으로 `EndIdleTransactionsJob`을 예약하고,
  이 Job은 `UIServiceConnections.confirmTransactionsClose`로 묻되 서비스가 없으면 묻지 않고 롤백했다. Swing에는 서비스 구현도 활동 시각도 없어서
  유휴 시간이 늘 3초(이전 감시 주기)였고 그래서 동작하지 않았다.
- 바꾼 것:
  - `DataSourceMonitorJob`의 트랜잭션 분기와 `getTransactionTimeoutSeconds`를 지우고 `IdleTransactionMonitor`를 부른다.
  - `EndIdleTransactionsJob`은 사용자가 켠 자동 롤백 하나만, 롤백 직전 다시 확인하고, 끝나면 UI 알림을 띄우도록 다시 썼다.
  - `QMTransactionState`·`QMUtils.getTransactionState`에 마지막 문장 시각, 실행 중 여부, 열림 여부를 더했다(다른 사용처 없음).
- 지운 것(이 변경으로 쓰지 않게 됨): `UIServiceConnections.confirmTransactionsClose`, `DBExecUtils.isExecutionInProgress`,
  `ChromiteNotifications.NT_ROLLBACK_IDLE`, 데이터 소스 설정 `ModelPreferences.TRANSACTIONS_AUTO_CLOSE_ENABLED`/`_TTL`(`transaction.auto.close.*`, 설정 화면 없음).
- `DBPConnectionType`의 TEST 기간 주석(`900, //30 minutes`)이 틀려 15분으로 고쳤다.
- 유휴 연결 닫기(아래 "유휴 연결 닫기"): 업스트림은 전역 사용자 활동 시각 기준으로 열린 트랜잭션과 관계없이 끊었다(끊기가 롤백한다). Chromite는 연결별 마지막
  사용 기준이고 열린 트랜잭션·실행 중이면 닫지 않으며, 끊기 직전 다시 확인한다. 알림 종류는 오류에서 경고로 바꿨다(설정대로 한 일이지 실패가 아니다,
  유휴 트랜잭션 롤백 알림과 같은 종류). `DBPApplication.getLastUserActivityTime`을 지웠다.
- 모델 알림 처리기(아래 "모델 알림")를 Swing에 등록했다. SWT의 알림 설정(알림 끄기, 소리)은 없다.

## 연결 끊기 전 질문

연결을 끊으면 JDBC 연결을 닫으며 열린 트랜잭션이 롤백된다(`JDBCDataSource.closeConnection` → `JDBCExecutionContext.close`).
Swing의 끊기, 다시 연결, 삭제, 연결 편집 뒤 다시 연결은 이것을 묻지 않았다(앱 종료만 물었다). SWT는 `DataSourceHandler.checkAndCloseActiveTransaction`으로 물었다.

- 네 동작이 모두 지나는 `ConnectionCommands`의 끊기 경로에서, `DisconnectJob`을 예약하기 전에 `ExitTransactionCheck.confirmDisconnect`를 부른다.
- 찾기·질문·끝내기는 앱 종료 질문과 같은 코드다: 그 연결의 모든 실행 문맥에서 `QMUtils.isTransactionActive`, Chromite Agent·MCP 도구 격리 문맥은
  `AgentSql.hasPendingTransaction`. 있으면 목록과 함께 커밋 / 롤백 / 취소를 묻는다.
- 취소하면 끊지 않고 트랜잭션도 그대로 둔다. 다시 연결·삭제도 하지 않고, Object Explorer의 Disconnect는 서버를 창에서 빼지 않는다.
- 커밋·롤백이 실패하면 오류를 보이고 끊지 않는다.
- 클래스 이름(`ExitTransactionCheck`)은 바꾸지 않았다. 바꾸면 기존 설계 문서(auto-update.md, ai-agent.md)의 참조까지 고쳐야 해서 변경이 커진다.

## 유휴 연결 닫기

연결 편집 > 연결 속성의 "유휴 연결 닫기(초)"는 저장되었지만 아무 일도 하지 않았다(SWT 대조 A-S2).

- 원인: `DataSourceMonitorJob`이 유휴 시간을 `now - getLastUserActivityTime(lastPingTime)`으로 쟀다. 업스트림 SWT 앱은 키·마우스 입력 필터로
  `DBPApplication.getLastUserActivityTime`을 갱신했지만 Swing 앱은 재정의하지 않아 -1이었고, 그러면 직전 점검 시각(3초 전)을 썼다.
  유휴 시간이 늘 약 3초라 닫기 조건이 참이 되지 않았다. 유휴 트랜잭션 롤백(A-S1)이 동작하지 않던 것과 같은 원인이다.
- 고친 것: 유휴 시간을 **연결마다 마지막으로 쓴 때**부터 잰다(`DataSourceMonitorJob.isIdleConnection`). 전역 마우스·키보드 활동은 쓰지 않는다.
  유휴 트랜잭션 감시와 같은 이유다: 다른 연결로 일하는 동안 놓인 연결도 닫혀야 하고, 반대로 사용자가 자리에 없어도 쓰이는 연결은 닫히면 안 된다.
  이로써 `DBPApplication.getLastUserActivityTime`과 `DataSourceMonitorJob.getLastUserActivityTime`은 쓰는 곳이 없어 지웠다.
- 마지막 사용 = 다음 중 가장 늦은 것:
  - 연결한 시각(`getConnectTime`);
  - 그 연결의 각 실행 문맥에서 쿼리 관리자가 본 마지막 사용(`QMUtils.getLastUseTime`): 목적과 관계없는 마지막 문장의 끝(사용자 쿼리, 탐색기 메타데이터 읽기,
    내부 작업)과 가져오기 끝, 마지막 커밋·롤백·커밋 방식 변경(새 트랜잭션 기록의 시작), 문맥이 다시 열린 시각. 실행·가져오기 중이면 지금;
  - Chromite Agent·MCP 도구 세션의 마지막 작업 끝(`DBServiceUnrecordedSessions.getLastUseTime`). 이 세션은 JDBC를 직접 써서 쿼리 관리자에 기록이 없다.
  - 연결 유지(keep-alive) 핑은 사용이 아니다. 핑은 JDBC 연결에 직접 보내 쿼리 관리자에 남지 않는다(`JDBCUtils.isConnectionAlive`).
- 닫지 않는 경우(하나라도 맞으면 그 연결은 유휴가 아니다):
  - 어느 문맥이든 열린 트랜잭션이 있다: `QMTransactionState.isOpen()`(수동 커밋에서 사용자 문장 실행, SELECT 포함) 또는 `QMUtils.isTransactionActive`
    (다른 목적의 변경 문장). 연결을 닫으면 JDBC 연결이 닫히며 묻지 않고 롤백되기 때문이다(사용자 규칙 "트랜잭션 안전").
  - Chromite Agent가 그 연결에 열린 트랜잭션을 남겼거나 지금 쓰고 있다(`DBServiceUnrecordedSessions.isConnectionInUse` → `AgentSql.isInUse`).
  - 문장이 실행 중이거나, 그 문맥의 Job(SQL 편집기 실행 등)이나 연결의 작업(`DBPDataSourceTask.isActiveTask`)이 있다. 끊기는 실행 중인 Job을 취소하기 때문이다.
  - 그 연결의 연결·끊기 Job이 예약되었거나 실행 중이다.
- 열린 트랜잭션 정책(#97과 맞춤): 연결을 열어 둔다. 그 트랜잭션은 이 문서의 유휴 트랜잭션 감시가 맡는다(기본: 알리고 묻기, 연결에서 켰을 때만 자동 롤백 뒤 알림).
  커밋·롤백으로 트랜잭션이 끝나면 그때부터 다시 한 기간을 재고 닫는다. 유휴 트랜잭션 알림을 끈 연결은 트랜잭션이 끝날 때까지 열려 있다(조용히 닫지 않는다).
  수동 커밋 연결은 SELECT만 실행해도 트랜잭션이 열린 것으로 보므로(위 "트랜잭션 상태") 커밋·롤백 전에는 닫히지 않는다.
- 닫기: `DisconnectJob`을 예약하고, Job이 끊기 직전에 같은 조건을 다시 본다(그 사이 쿼리를 실행했거나 트랜잭션이 열렸으면 아무것도 하지 않는다).
  끊은 뒤 경고 알림을 보낸다: 제목은 연결 이름, 내용 "20초 동안 쓰지 않아 연결을 닫았습니다. 다시 쓰면 다시 연결합니다."(`ModelMessages.model_connection_idle_closed`,
  기간은 `DurationFormatter`). 끊긴 뒤라 데이터 소스가 없으므로 제목을 받는 `ChromiteNotifications.showNotification`을 쓴다.
- 쿼리 창: 닫힌 연결의 쿼리 창은 그대로 쓸 수 있다. 다음 실행이 다시 연결한다(`SqlEditorPanel.prepareAndSchedule` → `DBUtils.initDataSource`).
  연결에 실패하면 기존 연결 오류 표시가 그대로 나온다.
- 설정 도구 설명(`settings_close_idle_tip`): 유휴 시간을 무엇부터 재는지, 열린 트랜잭션이 있으면 닫지 않는다는 것, 다시 쓰면 다시 연결한다는 것.
- 기본값은 업스트림 그대로다: 켜짐(`CLOSE_IDLE_CONNECTION_DEFAULT`), 연결 유형별 기간(개발 4시간 등). 지금까지는 효과가 없었으므로, 이 변경 뒤에는
  오래 쓰지 않은 연결이 실제로 닫히고 알림이 뜬다.

### 절전 때 연결 닫기(A-S8)는 바뀌지 않았다

`DataSourceMonitorJob`의 "절전 진입 → 모든 연결 닫기"(`closeAllConnections`)는 `OperationSystemState.isInSleepMode()`가 참일 때만 돈다.
`toggleSleepMode`를 부르는 곳이 없어 Swing에서는 여전히 실행되지 않는다. 이 변경은 그 경로를 열지 않았고 절전 감지도 더하지 않았다.
절전 감지를 더하면 `closeAllConnections`도 위 조건(열린 트랜잭션이면 닫지 않음)을 거쳐야 한다. 지금은 묻지 않고 끊는다.

## 모델 알림

모델 계층의 알림(`ChromiteNotifications.showNotification`)은 SWT 앱에서 `CoreApplicationActivator`가 등록한 처리기가 알림 팝업으로 보였다.
Swing은 처리기를 등록하지 않아 기본 처리기가 `System.out`에만 썼다(A-S7).

- `ChromiteSwingApplication.start`가 연결 감시가 시작되기 전(`platform.postInitialize()` 앞)에 `SwingModelNotifications`를 등록한다.
- 메인 창 알림(`NotificationToast`)으로 보인다. 제목은 연결 이름(데이터 소스가 있는 알림) 또는 보낸 쪽의 제목이다(SWT `NotificationUtils`와 같음).
- 종류는 그대로 옮긴다: 정보 → 정보, 경고 → 경고, 오류 → 오류. 로그에도 같은 수준으로 남긴다.
- 경고·오류는 닫을 때까지 남는다(`NotificationToast.showUntilClosed`). 유휴 연결 닫힘처럼 자리를 비운 사이 생기는 일이라 시간이 지나 사라지면 볼 수 없다.
  정보(다시 연결 성공)는 잠시 뒤 사라진다. 누르면 보낸 쪽의 동작을 실행한다(다시 연결 실패: 연결 편집).
- 메인 창이 없으면(시작·종료 중) 로그에만 남는다.

보내는 곳(2026-11-07 검색, `ChromiteNotifications.showNotification` 호출 전부):

| 보내는 곳 | 알림 | 종류 | 언제 |
|---|---|---|---|
| `DataSourceMonitorJob.showNotification` | `NT_DISCONNECT_IDLE` 유휴 연결 닫힘 | 경고 | 위 "유휴 연결 닫기" |
| `DefaultInvalidationFeedbackHandler.onInvalidateSuccess` | `NT_RECONNECT_SUCCESS` "Datasource was invalidated, Live connection count: n/m" | 정보 | 끊긴 연결을 다시 이었을 때: 연결 유지 핑 실패(`KeepAlivePingJob`), 실행 중 연결 오류 복구(`DBExecUtils`), "연결 끊김" 알림의 다시 연결. 열린 트랜잭션이 있으면 자동으로 다시 잇지 않는다([transactions.md](transactions.md) "오류 복구와 열린 트랜잭션") |
| `DefaultInvalidationFeedbackHandler.onInvalidateFailure` | `NT_RECONNECT_FAILURE` "Datasource invalidate failed" | 오류 | 실행 중 연결 오류 복구가 실패했을 때(핑 실패 쪽은 오류를 보이지 않게 부른다) |

- 유휴 트랜잭션 롤백은 #97부터 모델 알림을 쓰지 않는다(`EndIdleTransactionsJob` → `UIServiceIdleTransactions.showIdleTransactionRolledBack`, `NT_ROLLBACK_IDLE`은 지웠다).
  그래서 이 처리기를 등록해도 롤백 알림이 두 번 뜨지 않는다.
- 절전 뒤 다시 검증(`DataSourceMonitorJob.invalidateSleptConnections`)은 업스트림부터 알림 처리기 없이(`null`) 부르므로 알림이 없다. 감사 문서는 이것도
  모델 알림으로 적었지만 코드로 보면 아니다. 바꾸지 않았다.
- 다시 연결 알림 문구는 업스트림 영어 그대로다(지역화하지 않았다).

## 남은 일

- Chromite Agent·MCP 도구의 격리 문맥은 QM을 거치지 않아 표시와 유휴 감시에 잡히지 않는다(`AgentSql`이 열린 트랜잭션만 따로 기록). 끝낼 때 질문(`ExitTransactionCheck`)은 이들도 묻는다. 유휴 연결 닫기는 `DBServiceUnrecordedSessions`로 이들을 본다.
- 절전 감지(A-S8)를 더하면 `closeAllConnections`가 유휴 연결 닫기와 같은 트랜잭션 확인을 거치게 해야 한다.
- 연결 유형별 자동 롤백 기본값(연결 유형 편집기는 2026-11-07에 생겼고 알림·기간은 그 화면에 있다). 넣는다면 사용자 결정을 다시 받는다.
- 다른 세션의 열린 트랜잭션(Activity Monitor 탭)은 다음 작업이다.

## 변경 이력

- 2026-11-07 최초 작성: 쿼리 창 상태 줄의 열린 트랜잭션 표시, 유휴 트랜잭션 알림(기본)과 자동 롤백(연결에서 켰을 때만), 업스트림 유휴 롤백 경로 교체
- 2026-11-07 연결 끊기 전 질문: 끊기·다시 연결·삭제·편집 뒤 다시 연결이 열린 트랜잭션을 묻지 않고 롤백하던 것을 막음
- 2026-11-07 유휴 연결 닫기가 동작하게 함(연결별 마지막 사용 기준, 열린 트랜잭션·실행 중이면 닫지 않음, AI 도우미 세션 포함, 닫은 뒤 경고 알림),
  모델 알림을 메인 창 알림으로 보임. PostgreSQL·SQL Server에서 실행 확인(증거: `D:\Tools\_scratch\idle-close-notifications\`)
- 2026-11-07 에이전트 기능의 공식 이름을 Chromite Agent로 정함([ai-agent.md](ai-agent.md) "명칭"): 이 문서의 설명에서 "AI 도우미"를 바꿈.
- 2026-11-07 상태 줄 메뉴에 트랜잭션 로그, 공용 커밋 경로를 Query 메뉴·도구 막대·단축키·열린 트랜잭션 목록도 씀([transactions.md](transactions.md))
- 2026-11-07 연결 유형 편집기(Options > Connection Types)가 생겨 유형의 알림·기간을 바꿀 수 있음을 반영([swing-options.md](swing-options.md))
- 2026-11-07 같은 UI 서비스(`UIServiceIdleTransactions`)에 오류 복구가 멈춘 알림 두 가지를 더함. 다시 연결 알림이 언제 뜨는지 고침
  ([transactions.md](transactions.md) "오류 복구와 열린 트랜잭션")
