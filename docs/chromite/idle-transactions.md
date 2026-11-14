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
| 트랜잭션 상태 읽기 | `com.seizia.chromite.model` | `QMUtils.getTransactionState`, `QMTransactionState`, 문장 분류 `QMStatementClassifier` |
| DB에 묻기(2026-11-08) | `com.seizia.chromite.model` | 답 `exec.DBCServerTransaction`, 문맥 인터페이스 `exec.DBCTransactionProbe`, 묻는 때·답 보관 `runtime.qm.QMTransactionTracker`(`QMRegistryImpl`이 등록) |
| DB별 질의 | `model.jdbc`, `ext.mssql`, `ext.postgresql`, `ext.mysql`, `ext.oracle` | 방언 인터페이스 `exec.jdbc.JDBCTransactionProbe`, `JDBCExecutionContext.probeTransaction`, `SQLServerDialectMssql`, `PostgreDialect`, `MySQLDialect`(MariaDB 포함), `OracleSQLDialect` |
| 유휴 감시, 커밋·롤백 공용 경로 | `com.seizia.chromite.model` | `runtime.jobs.IdleTransactionMonitor` (`DataSourceMonitorJob`이 3초마다 부름) |
| 자동 롤백 | `com.seizia.chromite.model` | `runtime.jobs.EndIdleTransactionsJob` |
| UI 서비스 인터페이스 | `com.seizia.chromite.model` | `runtime.ui.UIServiceIdleTransactions` |
| UI 서비스 구현(알림, 롤백 알림) | `com.seizia.chromite.ui.swing` | `SwingIdleTransactionService` (`plugin.xml`의 `com.seizia.chromite.service` 확장으로 등록) |
| 상태 줄 표시 | `com.seizia.chromite.ui.swing` | `QueryStatusLine`(칸), `SqlEditorPanel`(1초 타이머, 커밋·롤백) |
| 조회만 한 트랜잭션 끝내기(2026-11-09) | `model`, `ext.postgresql`, `ext.mysql`, `registry`, `ui.swing` | `DBCServerTransaction.readsOnly`, `QMTransactionState.isReadOnly`, `IdleTransactionMonitor.endReadOnlyTransaction`·`endReadOnlyAfterWork`, `QMTransactionTracker`(자동으로 끝내기 호출, 끝낸 시각), `DBPConnectionType.autoEndReadOnlyTransactions`, `SwingIdleTransactionService.endReadOnlyTransaction`, `ConnectionTypesPage` |
| 남는 알림 | `com.seizia.chromite.ui.swing` | `NotificationToast.showWithActions` |
| 설정 | `com.seizia.chromite.model`, `registry`, `ui.swing` | `DBPConnectionConfiguration`, `DataSourceSerializerModern`, `ConnectionSettingsPanel` |
| 유휴 연결 닫기 | `com.seizia.chromite.model` | `runtime.jobs.DataSourceMonitorJob`(`isIdleConnection`), `QMUtils.getLastUseTime` |
| 쿼리 관리자 밖 세션(Chromite Agent) | `com.seizia.chromite.model`, `model.ai.tools` | 서비스 `runtime.DBServiceUnrecordedSessions`, 구현 `AgentSessionsService`(`AgentSql.isInUse`, `getLastUseTime`, ai.tools `plugin.xml`) |
| 모델 알림 표시 | `com.seizia.chromite.ui.swing` | `SwingModelNotifications`(`ChromiteSwingApplication.start`에서 `ChromiteNotifications.setHandler`), `NotificationToast.showUntilClosed` |

### 트랜잭션 상태

사용자 결정(2026-11-08, 앱 변경 #1): "SELECT만 했을 뿐인데 FETCH ALL ROWS를 하면 활성 트랜잭션 시간이 흘러간다"는 지적에서 시작해, 열린 트랜잭션은
**DB가 실제로 무언가를 쥐고 있을 때만** 보인다. 원칙(사용자): "실제로 쥐고 있을 때만 하라. 이 기능의 목적은 사고의 방지이다. 트랜잭션 활성에 안 잡혔는데
실제로 잡혀 있다면 제품 신뢰성의 큰 문제가 된다. 반대의 경우도 마찬가지이다." 그래서 놓침(쥐고 있는데 안 보임)과 거짓 표시(쥔 것이 없는데 보임) 둘 다 결함이다.

전에는 수동 커밋 모드에서 사용자 문장이 하나라도 실행되면(SELECT 포함) 열림이었다. SQL Server의 일반 SELECT(쥔 것 없음)도 열림이었고, 자동 커밋 모드에서
`BEGIN TRAN`으로 연 트랜잭션은 표시도 질문도 없었다.

**열림의 기준** — 트랜잭션이 다음 중 하나를 끝날 때까지 쥐고 있음:

- 커밋하지 않은 데이터 변경(로그 레코드, 할당된 트랜잭션 ID);
- 트랜잭션 끝까지 쥐는 잠금(행·테이블·메타데이터 잠금, 직렬화 술어 잠금);
- 스냅숏(SQL Server SNAPSHOT의 tempdb 행 버전, PostgreSQL·Oracle의 REPEATABLE READ·SERIALIZABLE·READ ONLY 스냅숏);
- 사용자가 명시적으로 시작함(`BEGIN TRAN`, `BEGIN`, `START TRANSACTION`, `SET TRANSACTION`). 자동 커밋 모드에서도;
- 오류로 중단되어 롤백해야 끝남(PostgreSQL aborted, SQL Server 커밋할 수 없는 트랜잭션).

쥔 것이 없는 SELECT(`WITH (NOLOCK)`, 기본 READ COMMITTED에서 읽은 뒤 잠금을 푼 SELECT, 테이블 없는 `SELECT 1`)는 열림이 아니다.

**DB에 묻기** (`QMTransactionTracker`, `DBCTransactionProbe`):

- 사용자 문장(META·UTIL이 아닌 문장)이 실행된 문맥은 "다시 물을 것"으로 표시하고, 사용자 문장을 실행한 세션이 모두 닫힐 때 그 문맥의 JDBC 연결에 직접 묻는다.
  SQL 실행 작업, 결과 더 읽기(Fetch All 포함), 필터·정렬 다시 읽기, 표 저장이 모두 세션을 열고 닫으므로 작업 하나가 끝날 때 한 번이다. 스크립트의 문장
  사이에는 묻지 않는다. 묻는 스레드는 실행 작업 스레드다(EDT가 아니고, 주기적으로 묻지 않는다).
- 커밋·롤백·커밋 방식 바꾸기 뒤에도 묻는다. 세션 밖에서 일어나면 작은 작업으로 묻는다. 커밋·롤백 직후에는 묻기 전에 "없음"으로 둔다(스마트 커밋이
  커밋 직후 상태를 읽어 자동 커밋으로 돌아가기 때문). 중첩 `BEGIN TRAN`처럼 남은 트랜잭션은 곧 이어 묻는 답이 고친다.
- 답은 문맥마다 메모리에 둔다. 상태 줄·유휴 감시·질문은 그것을 읽기만 한다(`QMUtils.getTransactionState`는 지금처럼 드라이버를 부르지 않는다).
- 질의는 JDBC 연결에 직접 보내므로 쿼리 관리자에 기록되지 않는다. 시간 제한 5초.
- 질의가 지켜야 하는 것: 트랜잭션을 새로 시작하지 않을 것(수동 커밋 SQL Server는 DMV를 읽는 SELECT로도, pgjdbc는 트랜잭션 밖의 아무 문장 앞에서도 BEGIN을
  보내고, 직렬화 격리 Oracle은 SELECT로도 트랜잭션을 시작한다). 실패해도 사용자 트랜잭션을 바꾸지 않을 것(PostgreSQL은 오류가 트랜잭션을 중단시키고,
  SQL Server `XACT_ABORT ON`은 롤백한다).

**합치기** (`QMUtils.getTransactionState` → `QMTransactionState.getStatus()`):

| DB의 답 | 상태 |
|---|---|
| 트랜잭션 없음 | 닫힘 |
| 쥔 것 있음(변경·잠금·스냅숏·명시적·중단) | 열림. 도구 설명에 "DB가 쥐고 있는 것" |
| 트랜잭션은 있으나 쥔 것 없음 | 문장에 명시적 BEGIN이 보이면 열림(수동 커밋 PostgreSQL의 빈 `BEGIN`), 아니면 닫힘 |
| 트랜잭션은 있으나 무엇을 쥐었는지 읽을 권한 없음 | 문장 분류가 데이터 변경·잠그는 읽기·명시적 BEGIN을 찾으면 열림, 아니면 **확인할 수 없음** |
| 질의 실패, 또는 질의가 없는 DB | 수동 커밋: 사용자 문장이 있으면 문장 분류(위와 같음), 못 찾으면 확인할 수 없음. 자동 커밋: 명시적 BEGIN 뒤 COMMIT·ROLLBACK이 없으면 열림, 아니면 닫힘 |

- 확인할 수 없음(`Status.UNKNOWN`)은 열림으로 꾸미지 않는다. 상태 줄에 "트랜잭션 확인할 수 없음"으로 따로 보이고, 도구 설명이 이유를 말한다.
  안전 동작(유휴 알림·자동 롤백, 끝낼 때·끊을 때·창을 닫을 때 질문, 커밋 방식 전환 질문, 오류 복구 멈춤, 유휴 연결 닫기에서 빼기)은 확인할 수 없음을 열림처럼
  다룬다(`QMTransactionState.isOpen()` = 열림 또는 확인할 수 없음). 끝낼 때·끊을 때 질문의 목록은 그 세션에 "쥔 것이 있는지 DB에서 확인할 수 없음"을 붙인다.
- 문장 수는 트랜잭션이 없다고 확인된 뒤의 사용자 문장이다(서버의 트랜잭션은 그 첫 문장에서 시작했다. 쥔 것 없던 SELECT라도). 자동 커밋 모드의 명시적 트랜잭션도 센다.
  메타데이터 읽기(META)와 내부 작업(UTIL)은 세지 않는다. 탐색기가 같은 문맥에서 메타데이터를 읽을 때마다 유휴 시간이 0으로 돌아가면 안 되기 때문이다.
- 데이터 변경 문장 수(`getUpdateCount`)는 QM의 "트랜잭션에 영향을 주는 문장"(DML·DDL, 갱신 행 수가 있는 문장, 실패한 문장)에서 세션만 바꾸는 문장
  (`SET`, `USE`, `ALTER SESSION`, 저장점, `BEGIN`·`COMMIT`·`ROLLBACK`)을 뺀 것이다.
- 시작 시각 = 그 첫 사용자 문장의 시작 시각(커밋 뒤에도 남은 중첩 트랜잭션은 DB가 열림이라고 처음 답한 시각). 트랜잭션을 구별하는 값으로도 쓴다.
- 마지막 문장 시각 = 마지막 사용자 문장이 끝난 시각(실행 중이면 시작 시각).
- 실행 중(`isExecuting`) = 목적과 관계없이 그 문맥에서 끝나지 않은 문장이 있음.

에디터에 `COMMIT`/`ROLLBACK`을 입력해 실행하면 수동 커밋에서는 실행 엔진이 트랜잭션 관리자로 끝내고(`SQLQueryExecuteJob.handleTransactionStatements`),
자동 커밋에서는 문장이 서버에서 실행된다. 어느 쪽이든 작업이 끝날 때 DB에 다시 묻는다.

#### 문장 분류 (`QMStatementClassifier`)

DB가 알려 주지 않는 것에만 쓴다. 잠금·변경은 DB에 물을 수 있으면 DB의 답으로 정하고 문장 글자의 힌트를 해석하지 않는다.

- 명시적 시작: `BEGIN`(단독), `BEGIN TRAN[SACTION]`/`WORK`/PostgreSQL·SQLite 옵션, `START TRANSACTION`. PL/SQL·T-SQL 블록의 `BEGIN … END`, `BEGIN TRY`는 아니다.
  새것부터 거슬러 올라가 처음 만나는 트랜잭션 제어 문장이 시작이면 열린 것이고, `COMMIT`·`ROLLBACK`(저장점으로 롤백은 제외)·`END`면 끝난 것이다.
- 잠그는 읽기: `FOR UPDATE`/`FOR SHARE` 등, `LOCK IN SHARE MODE`, `LOCK [TABLE]`, SQL Server 잠금 테이블 힌트(`UPDLOCK`, `HOLDLOCK`, `XLOCK`, `TABLOCK[X]`, `PAGLOCK`,
  `SERIALIZABLE`, `REPEATABLEREAD`).
- 데이터 변경: 위 데이터 변경 문장 수가 1 이상.

#### SQL Server

한 배치로 묻는다(트랜잭션 수를 먼저 읽어야 한다: 수동 커밋(`IMPLICIT_TRANSACTIONS ON`)에서는 DMV를 읽는 SELECT도 트랜잭션을 시작한다, 2026-11-08 확인).

- `@@TRANCOUNT` = 0 → 없음.
- `XACT_STATE()` = -1 → 중단(커밋할 수 없음).
- 명시적: 자동 커밋(`@@OPTIONS & 2` = 0)에서 트랜잭션 수 > 0, 또는 수동 커밋에서 트랜잭션 수 ≥ 2(암시적 트랜잭션은 1이고 `BEGIN TRAN`이 하나를 더한다, 확인함).
- 권한이 있으면(`VIEW SERVER STATE`, 2022는 `VIEW SERVER PERFORMANCE STATE`, `HAS_PERMS_BY_NAME`으로 먼저 확인하고 없으면 DMV를 건드리지 않는다):
  변경 = `sys.dm_tran_database_transactions.database_transaction_log_record_count` > 0, 잠금 = `sys.dm_tran_locks`에서 그 세션의 TRANSACTION 소유 잠금(모든 세션이
  쥐는 DATABASE 공유 잠금과 메타데이터 `Sch-S` 제외), 스냅숏 = `sys.dm_tran_current_transaction.transaction_is_snapshot`.
- 메타데이터 `Sch-S`를 빼는 이유(2026-11-09 확인): 계획 캐시에 없는 쿼리를 암시적 트랜잭션 안에서 컴파일하면 스키마 소유 주체에 `METADATA`
  `DATABASE_PRINCIPAL` `Sch-S`가 트랜잭션 끝까지 남는다(`DBCC FREEPROCCACHE` 뒤 같은 SELECT를 두 번: 처음만 남음). 같은 SELECT가 계획 캐시에 따라 열림·닫힘이
  바뀌면 안 되고, 이 잠금은 그 주체·스키마 자체를 바꾸는 것만 막는다. 테이블의 `Sch-S`(OBJECT)는 그대로 센다.
- 권한이 없으면: DMV가 모두 "VIEW SERVER PERFORMANCE STATE permission was denied"(오류 300)다(2026-11-08 낮은 권한 로그인으로 확인). 자기 세션의
  `sys.dm_exec_sessions` 행은 보이지만(트랜잭션 수, 격리 수준) 잠금 힌트를 알 수 없다. 그래서 트랜잭션 수·명시적·중단만 DB가 답하고, 수동 커밋의 암시적
  트랜잭션(수 1)은 "권한 없음"으로 답해 문장 분류로 간다(DML·잠금 힌트 → 열림, 일반 SELECT → 확인할 수 없음). 확인할 수 없음을 없애려면 로그인에
  `GRANT VIEW SERVER STATE`(2022: `VIEW SERVER PERFORMANCE STATE`)를 준다.
- Sybase·Babelfish·Fabric(같은 방언 계열)은 이 함수·뷰가 없어 묻지 않는다(문장 분류).

#### PostgreSQL

- pgjdbc의 프로토콜 상태(`getTransactionState`, 서버의 ReadyForQuery 상태)로 왕복 없이 트랜잭션 유무를 본다: IDLE → 없음, FAILED → 중단, OPEN → 아래 질의.
  수동 커밋에서 드라이버는 트랜잭션 밖의 아무 문장 앞에 BEGIN을 보내므로 질의만으로는 트랜잭션을 만들어 버린다. 드라이버가 pgjdbc가 아니면 묻지 않는다.
- 질의는 저장점 안에서 한다(시간 제한 등으로 실패해도 사용자 트랜잭션이 중단되지 않게): 그 세션의 `pg_locks`에서
  변경 = 트랜잭션 ID 잠금 + 쓰기 모드 관계 잠금(RowExclusiveLock 이상), 잠금 = 가상 트랜잭션 ID·트랜잭션 ID 외의 잡힌 잠금(시스템 카탈로그·뷰, 곧 OID 16384
  미만 관계 제외: 질의 자신이 `pg_locks`에 AccessShareLock을 잡는다), 스냅숏 = 격리 수준 REPEATABLE READ·SERIALIZABLE. 자동 커밋에서 트랜잭션이 있으면 명시적이다.
- **일반 SELECT는 열림이다.** PostgreSQL은 SELECT가 읽은 테이블과 인덱스에 AccessShareLock을 트랜잭션 끝까지 쥐고, 이 잠금은 다른 세션의 `ALTER TABLE`·`DROP`·
  `TRUNCATE`·`VACUUM FULL`을 막는다(2026-11-08 확인: 수동 커밋에서 SELECT 뒤 다른 세션의 `ALTER TABLE t ADD COLUMN`이 `lock_timeout` 500ms로 "canceling statement due to
  lock timeout"). 막힌 DDL 뒤로 모든 쿼리가 줄을 서는 사고가 이 기능이 막으려는 것이다. `SELECT 1`(테이블 없음)은 잠금이 없어 닫힘이다.
- 서버 종류가 PostgreSQL(Google Cloud, Timescale 포함)일 때만 묻는다. CockroachDB·Redshift·YugabyteDB·EDB 등은 문장 분류.

#### MySQL, MariaDB

- 트랜잭션 유무: `DO 0`으로 서버 상태 플래그를 새로 받고(오류 패킷에는 플래그가 없다) 드라이버가 들고 있는 SERVER_STATUS_IN_TRANS를 읽는다
  (Connector/J `getSession().getServerSession().inTransactionOnServer()`, MariaDB Connector/J `getContext().getServerStatus()`). 둘 다 아니면 MariaDB의 `@@in_transaction`.
  권한이 필요 없다. `INNODB_TRX`는 PROCESS 권한이, `performance_schema`는 SELECT 권한이 필요하다(2026-11-08 확인: 일반 로그인은 둘 다 거절).
- **트랜잭션이 있으면 열림이다.** 수동 커밋에서 트랜잭션은 테이블을 읽거나 명시적으로 시작해야만 생기고(`SELECT 1`·오류만 난 문장은 생기지 않음, 확인함),
  테이블을 읽은 트랜잭션은 그 테이블의 메타데이터 잠금(MDL `SHARED_READ`, `LOCK_DURATION = TRANSACTION`)을 끝까지 쥔다. 다른 세션의 `ALTER TABLE`이 막힌다
  (2026-11-08 확인: MySQL 8.4·MariaDB 11.8 모두 `lock_wait_timeout` 1초로 1205 "Lock wait timeout exceeded"). `INNODB_TRX`에 일반 읽기도 행이 있는 것은 이 때문에
  맞는 답이다. 자동 커밋에서 트랜잭션이 있으면 명시적이다.
- 쥔 것의 설명: 로그인이 `INNODB_TRX`를 읽을 수 있으면 변경 = `trx_rows_modified` > 0, 잠금 = 그 행이 있음(행 잠금이 없어도 읽기 뷰와 MDL). 읽을 수 없으면(처음 거절 뒤
  다시 묻지 않음) 수동 커밋은 잠금(MDL). 이 뷰는 0.1초마다만 새로 고쳐지므로 설명일 뿐 열림·닫힘은 정하지 않는다.
- 보이지 않는 것: 수동 커밋에서 MyISAM(트랜잭션이 없는 엔진) 테이블만 읽은 경우. MySQL은 그 MDL을 트랜잭션 끝까지 쥐지만 트랜잭션으로 알리지 않는다
  (2026-11-08 확인: `metadata_locks`에 `SHARED_READ`/`TRANSACTION`, 플래그·`events_transactions_current`는 없음). 아래 "남은 일".

#### Oracle

- `BEGIN ? := DBMS_TRANSACTION.LOCAL_TRANSACTION_ID; END;`(PL/SQL 호출). 같은 함수를 SELECT로 부르면 직렬화 세션에서 그 SELECT가 트랜잭션을 시작한다
  (2026-11-08 확인: `ALTER SESSION SET ISOLATION_LEVEL = SERIALIZABLE`과 COMMIT 뒤 SELECT로 부르면 값이 생기고, PL/SQL로는 NULL). PUBLIC에 실행 권한이 있다.
- 값이 있으면 열림이다. Oracle은 끝낼 것이 있을 때만 트랜잭션을 시작한다: 변경, 행·테이블 잠금(`SELECT FOR UPDATE`, `LOCK TABLE`, 바꾼 행이 없는 DML도 TM 잠금),
  `SET TRANSACTION`, SERIALIZABLE·READ ONLY 트랜잭션의 스냅숏. 일반 READ COMMITTED SELECT는 시작하지 않는다.
- 무엇을 쥐었는지는 `V$TRANSACTION`·`V$LOCK`에 있지만 대부분 로그인이 읽을 수 없어 묻지 않는다. 도구 설명은 문장 분류(변경·잠그는 읽기·명시적)로 적는다.
- 자동 커밋에서도 트랜잭션이 남을 수 있다: `ALTER SESSION SET ISOLATION_LEVEL = SERIALIZABLE` 세션에서 SELECT(드라이버는 SELECT 뒤 커밋하지 않는다). 열림으로 보인다.

#### Chromite Agent

에이전트 문맥(쿼리 관리자 밖의 격리 문맥)도 같은 방언 질의를 쓴다(`AgentSql.isTransactionOpen`). 쓰기 도구 뒤에 수동·자동 커밋 모두 묻고, 쥔 것이 있거나
확인할 수 없으면 `pending`이다([ai-agent.md](ai-agent.md) "트랜잭션 안전"). 예전 질의는 SQL Server `@@TRANCOUNT > 0`(수동 커밋의 SELECT도 1), MySQL
`INNODB_TRX` 행 수, PostgreSQL `txid_current_if_assigned()`(테이블 잠금·빈 BEGIN·중단을 모름), Oracle `SELECT … FROM dual`(직렬화 세션에서 트랜잭션을 시작함)이었다.

### 확인: DB별 경우표 (2026-11-09)

빌드한 Swing 제품의 복사본(한국어 OS, 이 브랜치)에서 앱 안 시험 에이전트(Attach API, Swing 객체를 EDT에서 직접 다룸, 화면 입력 없음)가 쿼리 창에 각 경우의 SQL을
넣어 실행하고(F5, 창 전체), 1.5초 뒤 상태 줄의 열린 트랜잭션 칸 글자·도구 설명과, **별도 관찰 연결**로 읽은 DB 자신의 뷰를 기록했다. 각 경우 뒤에는
`ROLLBACK`(SQL Server `IF @@TRANCOUNT > 0 ROLLBACK`)을 실행해 닫힘으로 돌아가는지도 보았다. 연결마다 수동 커밋·자동 커밋 두 개를 두었다.
시험 DB: 공유 컨테이너 SQL Server 2022(`chr_txn_rs`), PostgreSQL 17, MariaDB 11.8, Oracle Free 23ai(FREEPDB1), 이 브랜치가 띄운 MySQL 8.4(끝난 뒤 지움).
MySQL 서버는 내장된 MariaDB Connector/J로 연결했다(Connector/J는 내장하지 않음, [drivers.md](drivers.md)). Connector/J의 플래그 경로는 JDBC 시험 도구로 확인했다.
증거: `D:\Tools\_scratch\chromite-txn-real-state\logs\*.log`(경우마다 DB 뷰, 상태 줄 글자·도구 설명, 상태), 상태 줄 화면 `shots\`, JDBC 시험 `probe\`.

모든 경우에서 Chromite 표시(열림·닫힘·확인할 수 없음)가 DB가 쥔 것과 일치했다. 아래 "→"의 왼쪽이 DB 뷰, 오른쪽이 상태 줄(괄호 안은 도구 설명의 "DB가 쥐고 있는 것").
"끝낸 뒤"는 모든 경우에 DB가 트랜잭션 없음, 상태 줄 숨김이었다.

**SQL Server 2022** (로그인 `VIEW SERVER STATE` 있음. DB 뷰: `@@TRANCOUNT`/`open_transaction_count`, 로그 레코드, 그 세션의 TRANSACTION 소유 잠금, SNAPSHOT 행)

| 경우 | 수동 커밋 | 자동 커밋 |
|---|---|---|
| 일반 SELECT(READ COMMITTED) | 트랜잭션 1, 로그 0, 잠금 없음 → 숨김 | 트랜잭션 0 → 숨김 |
| SELECT WITH (NOLOCK) | 트랜잭션 1, 잠금 없음 → 숨김 | 0 → 숨김 |
| SELECT WITH (HOLDLOCK) | KEY S, PAGE IS, OBJECT IS → 열림(잠금) | 0 → 숨김 |
| SELECT WITH (UPDLOCK) | KEY U, PAGE IU, OBJECT IX → 열림(잠금) | 0 → 숨김 |
| REPEATABLE READ SELECT | KEY S, PAGE IS, OBJECT IS → 열림(잠금) | 0 → 숨김 |
| SERIALIZABLE SELECT | KEY S, PAGE IS, OBJECT IS → 열림(잠금) | 0 → 숨김 |
| SNAPSHOT SELECT | 잠금 없음, 스냅숏 트랜잭션 1 → 열림(스냅숏) | 0 → 숨김 |
| DML | 로그 2, KEY X, PAGE IX, OBJECT IX → 열림(변경, 잠금) | 0 → 숨김 |
| 빈 `BEGIN TRAN` | 트랜잭션 2, 잠금 없음 → 열림(명시적) | 트랜잭션 1 → 열림(명시적) |
| DML 뒤 오류(`1/0`, 트랜잭션은 남음) | 로그 2, 잠금 → 열림(변경, 잠금) | 0 → 숨김 |
| `XACT_ABORT ON` + TRY 안 오류(커밋할 수 없게 되어 배치 끝에 서버가 롤백, 오류 3998) | 0 → 숨김 | 0 → 숨김 |
| DML 뒤 `COMMIT` / `ROLLBACK` | 0 → 숨김 | 0 → 숨김 |
| `BEGIN TRAN` ×2 + DML, 상태 줄 메뉴 Commit | 트랜잭션 3, 로그 2 → 열림(변경, 잠금, 명시적) → Commit 뒤 0, 숨김 | 트랜잭션 2 → 열림 → Commit 뒤 0, 숨김 |

권한 없는 로그인(`chr_txn_low`, db_datareader·db_datawriter만):

| 경우 | 수동 커밋 | 자동 커밋 |
|---|---|---|
| 일반 SELECT | 트랜잭션 1, 잠금 없음 → **트랜잭션 확인할 수 없음** | 0 → 숨김 |
| SELECT WITH (HOLDLOCK) | KEY S 등 → 열림(실행한 문장으로 판단: 잠금) | 0 → 숨김 |
| DML | 로그 2, 잠금 → 열림(실행한 문장으로 판단: 변경) | 0 → 숨김 |
| 빈 `BEGIN TRAN` | 트랜잭션 2 → 열림(DB: 명시적) | 트랜잭션 1 → 열림(DB: 명시적) |
| DML 뒤 `COMMIT` | 0 → 숨김 | 0 → 숨김 |

고치기 전 첫 실행(2026-11-08 23:44, 계획 캐시에 없던 문장들)에서 일반 SELECT·NOLOCK·DML이 `METADATA` `Sch-S`(DATABASE_PRINCIPAL) 하나 때문에
"열린 트랜잭션(잠금)"으로 보였고 같은 문장을 다시 실행하면 숨김이었다. JDBC 시험으로 원인(계획 컴파일)을 확인하고 메타데이터 `Sch-S`를 세지 않게 고쳤다(위 "SQL Server").

**PostgreSQL 17** (DB 뷰: `pg_stat_activity.state`·`backend_xid`·`backend_xmin`, 그 세션의 `pg_locks`(시스템 카탈로그 제외))

| 경우 | 수동 커밋 | 자동 커밋 |
|---|---|---|
| 일반 SELECT | idle in transaction, `t`·`t_pkey` AccessShareLock → 열림(잠금) | idle → 숨김 |
| `SELECT 1`(테이블 없음) | idle in transaction, 잠금 없음 → 숨김 | idle → 숨김 |
| SELECT FOR UPDATE | xid 있음, RowShareLock → 열림(잠금) | idle → 숨김 |
| REPEATABLE READ(`SELECT 1`) | backend_xmin 있음(스냅숏), 잠금 없음 → 열림(스냅숏) | idle → 숨김 |
| SERIALIZABLE SELECT | xmin, AccessShareLock, SIReadLock → 열림(잠금, 스냅숏) | idle → 숨김 |
| DML | xid, RowExclusiveLock → 열림(변경, 잠금) | idle → 숨김 |
| 빈 `BEGIN` | idle in transaction, 잠금 없음 → 열림(명시적) | idle in transaction → 열림(명시적) |
| DML 뒤 오류 | idle in transaction (aborted) → 열림(오류로 중단됨) | idle → 숨김 |
| `BEGIN`, DML 뒤 오류 | aborted → 열림(명시적, 중단) | aborted → 열림(명시적, 중단) |
| DML 뒤 `COMMIT` / `ROLLBACK` | idle → 숨김 | idle → 숨김 |
| `BEGIN` + DML, 상태 줄 메뉴 Commit | xid, RowExclusiveLock → 열림 → Commit 뒤 idle, 숨김 | 같음(자동 커밋은 `COMMIT` 문장으로 끝냄) |

수동 커밋 일반 SELECT의 AccessShareLock이 실제로 다른 세션을 막는지는 JDBC 시험으로 확인했다: 관리자 세션의 `ALTER TABLE t ADD COLUMN`이 `lock_timeout` 500ms로
"canceling statement due to lock timeout"(55P03).

**MySQL 8.4** (사용자 로그인은 PROCESS 권한 없음. DB 뷰: 관찰 로그인의 `INNODB_TRX`, `performance_schema.metadata_locks`, `events_transactions_current`)

| 경우 | 수동 커밋 | 자동 커밋 |
|---|---|---|
| 일반 SELECT(REPEATABLE READ) | INNODB_TRX 행(변경 0, 잠금 구조 0), MDL `t` SHARED_READ/TRANSACTION, ACTIVE → 열림(잠금) | COMMITTED → 숨김 |
| `SELECT 1` | 행 없음, ROLLED BACK → 숨김 | 숨김 |
| SELECT FOR UPDATE / LOCK IN SHARE MODE | 잠금 구조 2, MDL → 열림(잠금) | COMMITTED → 숨김 |
| SERIALIZABLE SELECT | 잠금 구조 2, MDL → 열림(잠금) | COMMITTED → 숨김 |
| DML | 변경 1, 잠금 구조 2, MDL SHARED_WRITE → 열림(잠금) | COMMITTED → 숨김 |
| 빈 `START TRANSACTION` | INNODB_TRX 행 없음, MDL 없음, ACTIVE → 열림(잠금, 명시적) | ACTIVE → 열림(명시적) |
| DML 뒤 오류(없는 테이블) | 변경 1, MDL → 열림(잠금) | COMMITTED → 숨김 |
| DML 뒤 `COMMIT` / `ROLLBACK` | COMMITTED / ROLLED BACK → 숨김 | 숨김 |
| `START TRANSACTION` + DML, 상태 줄 메뉴 Commit | 잠금 구조 2, MDL → 열림 → Commit 뒤 COMMITTED, 숨김 | 같음 |

사용자 로그인에 PROCESS가 없으면 쥔 것의 설명은 "잠금"(MDL)뿐이다(DML의 변경은 도구 설명의 "데이터 변경 N개"에 있다). 열림·닫힘은 같다.
수동 커밋 일반 SELECT의 MDL이 실제로 다른 세션을 막는지는 JDBC 시험으로 확인했다: 다른 세션의 `ALTER TABLE t ADD COLUMN`이 `lock_wait_timeout` 1초로 1205.
PROCESS가 있는 로그인으로도 같은 표를 돌렸다(`my-p-m`): 열림·닫힘은 같고, DML은 "커밋하지 않은 변경", 빈 `START TRANSACTION`은 "명시적으로 시작함"만 보인다.
`INNODB_TRX`가 0.1초마다만 새로 고쳐져 막 실행한 문장의 행이 아직 없을 때가 있어(SERIALIZABLE SELECT는 쥔 것 설명 없이 "열린 트랜잭션"만) 설명은 덜할 수 있다.

**MariaDB 11.8** (DB 뷰: 관찰 로그인의 `INNODB_TRX`. MariaDB는 `performance_schema`가 꺼져 있어 MDL은 보이지 않는다)

| 경우 | 수동 커밋 | 자동 커밋 |
|---|---|---|
| 일반 SELECT(REPEATABLE READ) | INNODB_TRX 행(변경 0, 잠금 구조 0) → 열림(잠금) | 행 없음 → 숨김 |
| `SELECT 1` | 행 없음 → 숨김 | 숨김 |
| SELECT FOR UPDATE / LOCK IN SHARE MODE | 잠금 구조 2 → 열림(잠금) | 숨김 |
| SERIALIZABLE SELECT | 잠금 구조 2 → 열림(잠금) | 숨김 |
| DML | 변경 1, 잠금 구조 2 → 열림(잠금) | 숨김 |
| 빈 `START TRANSACTION` | 행 없음(`@@in_transaction` 1) → 열림(잠금, 명시적) | 열림(명시적) |
| DML 뒤 오류 | 변경 1 → 열림(잠금) | 숨김 |
| DML 뒤 `COMMIT` / `ROLLBACK` | 행 없음 → 숨김 | 숨김 |
| `START TRANSACTION` + DML, 상태 줄 메뉴 Commit | 잠금 구조 2 → 열림 → Commit 뒤 숨김 | 같음 |

PROCESS가 있는 로그인(`ma-p-m`)은 MySQL과 같다(DML "커밋하지 않은 변경", 빈 START TRANSACTION "명시적으로 시작함"). 수동 커밋 일반 SELECT의 MDL이 다른 세션의
`ALTER TABLE`을 막는 것은 JDBC 시험으로 확인했다(1205).

**Oracle Free 23ai** (DB 뷰: 관찰 로그인의 `V$TRANSACTION`·`V$LOCK`, 사용자 세션의 `DBMS_TRANSACTION.LOCAL_TRANSACTION_ID`(PL/SQL 호출))

| 경우 | 수동 커밋 | 자동 커밋 |
|---|---|---|
| 일반 SELECT | 트랜잭션 없음 → 숨김 | 없음 → 숨김 |
| SELECT FOR UPDATE | TX 6, TM 3, 트랜잭션 ID → 열림(잠금) | 없음 → 숨김 |
| `SET TRANSACTION ISOLATION LEVEL SERIALIZABLE` + SELECT | TX 6, 트랜잭션 ID → 열림 | 없음 → 숨김 |
| `SET TRANSACTION READ ONLY` + SELECT | TX 6, 트랜잭션 ID → 열림 | 없음 → 숨김 |
| `ALTER SESSION SET ISOLATION_LEVEL = SERIALIZABLE` + SELECT | TX 6, 트랜잭션 ID → 열림 | 없음 → 숨김 |
| DML / 행이 없는 DML | TX 6, TM 3 → 열림(변경) | 없음 → 숨김 |
| 빈 `SET TRANSACTION NAME` | TX 6, 트랜잭션 ID → 열림 | 없음 → 숨김 |
| DML 뒤 오류(`1/0`, 문장 단위 롤백이라 트랜잭션은 남음) | TX 6, TM 3 → 열림(변경) | 없음 → 숨김 |
| DML 뒤 `COMMIT` / `ROLLBACK` | 없음 → 숨김 | 숨김 |
| `LOCK TABLE … IN SHARE MODE` | TM 4, TX 6 → 열림(변경, 잠금) | 없음 → 숨김 |

Chromite 세션에서는 `SET TRANSACTION`·직렬화 트랜잭션에도 `V$TRANSACTION` 행(되돌림 레코드 1)과 TX 잠금이 보였다(JDBC 시험의 맨 연결에서는 없었다).
Oracle은 쥔 것을 알려 주지 않으므로 도구 설명의 쥔 것은 문장 분류다(직렬화·READ ONLY·빈 SET TRANSACTION은 설명 없이 "열린 트랜잭션"). "변경"은 쿼리 관리자의
데이터 변경 문장(LOCK TABLE 포함)에서 온다.

**같은 규칙: 끝낼 때·끊을 때 질문** — SQL Server 수동·자동 커밋, Oracle 수동 커밋 표를 다시 돌리며 경우마다 `ExitTransactionCheck.findActive`(끝낼 때·끊을 때 질문의 목록)를
불렀다. 모든 경우에 상태 줄이 열림이면 목록에 있고 숨김이면 없었다(일반 SELECT·NOLOCK 없음, HOLDLOCK·SNAPSHOT·DML·빈 BEGIN TRAN 있음, 자동 커밋은 BEGIN TRAN만 있음).

**Chromite Agent** (`AgentSql.run`, 쓰기 도구와 같은 NATIVE 모드로 실행한 뒤 기록한 `pending`과 DB 뷰, 그다음 `endTransaction`(commitTransaction))

| 연결 | 실행 | pending / DB | commitTransaction 뒤 |
|---|---|---|---|
| SQL Server 자동 커밋 | `BEGIN TRAN; UPDATE …` | 열림 / 트랜잭션 1, 로그 2, 잠금 | 끝낸 1개, pending 없음 / 트랜잭션 0 |
| SQL Server 자동 커밋 | `SELECT …` | 없음 / 트랜잭션 0 | — |
| SQL Server 자동 커밋 | `BEGIN TRAN; BEGIN TRAN; UPDATE …` | 열림 / 트랜잭션 2 | `COMMIT` 두 번, pending 없음 / 트랜잭션 0 |
| SQL Server 수동 커밋 | `SELECT …` | 없음 / 트랜잭션 1, 쥔 것 없음 (예전 질의는 `@@TRANCOUNT` 1이라 열림) | — |
| SQL Server 수동 커밋 | `UPDATE …` | 열림 / 로그 2, 잠금 | pending 없음 / 트랜잭션 0 |

### 열린 트랜잭션 표시(쿼리 창 상태 줄)

- 자리: 커밋 방식 칸(자동 커밋/수동 커밋 점) 바로 뒤.
- 보이는 때: 쿼리 창 실행 문맥(기본은 창마다 자기 세션, 설정에 따라 같은 연결의 쿼리 창이 함께 쓰는 주 문맥, [transactions.md](transactions.md) "쿼리 창마다 별도 연결")에 열린 트랜잭션(위 "트랜잭션 상태")이 있을 때만. 자동 커밋 모드의 명시적 트랜잭션도 보인다. 쥔 것이 없으면 숨긴다.
- 모양: `status.warning` 점(커밋 방식 점과 같은 크기, 주의이지 오류가 아님) + `열린 트랜잭션 00:03:15 (문장 3개)`. 경과 시간은 트랜잭션 시작부터이고 1초마다 갱신한다.
  DB에 확인할 수 없으면 `트랜잭션 확인할 수 없음 00:03:15 (문장 3개)`(영어 `Transaction unverified …`).
  2026-11-09부터 조회 트랜잭션과 변경 있는 트랜잭션은 글자와 점이 다르다(아래 "조회만 한 트랜잭션 끝내기"의 "두 종류를 구별해 보이기").
- 도구 설명: DB가 쥐고 있는 것(커밋하지 않은 변경, 잠금, 스냅숏, 명시적으로 시작함, 오류로 중단됨. DB에 물을 수 없어 문장으로 판단했으면 "실행한 문장으로 판단"),
  확인할 수 없으면 그 이유, 시작 시각, 문장 수와 데이터 변경 문장 수, 마지막 문장 끝 시각, "클릭하면 커밋하거나 롤백합니다".
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
- 내용: 연결, 쿼리 창(그 문맥을 쓰는 쿼리 창의 파일 이름, 없으면 문맥 이름), 유휴 시간, 시작 시각과 문장 수.
- 자동 롤백 알림: 연결, 쿼리 창, 유휴 시간, 취소된 문장 수와 데이터 변경 문장 수. 닫을 때까지 남는다.
- 모델 알림(`ChromiteNotifications`)은 쓰지 않는다. 알림은 이 서비스가 직접 띄운다. 그래서 아래 "모델 알림"을 연결한 뒤에도 같은 일이 두 번 보이지 않는다.

### 커밋·롤백 공용 경로

`IdleTransactionMonitor.endTransaction(monitor, context, transactionStart, commit)`을 알림 단추, 상태 줄 메뉴, 자동 롤백이 함께 쓴다.
2026-11-07부터 Query 메뉴·SQL Editor 도구 막대·단축키의 Commit / Roll Back과 열린 트랜잭션 목록도 이 경로를 쓴다([transactions.md](transactions.md)).

- 그 문맥의 트랜잭션이 보여 준 트랜잭션(시작 시각)과 같고 실행 중이 아닐 때만 한다. 그 사이 다른 트랜잭션이 시작되었으면 건드리지 않고 "아무것도 하지 않았습니다"라고 알린다.
- 커밋·롤백 자체는 `IdleTransactionMonitor.commitOrRollback`(UTIL 세션)이다. 끝낼 때·끊을 때 질문과 Chromite Agent의 `disconnect` 답도 이것을 쓴다(2026-11-08).
  - 수동 커밋: 다른 기능과 같은 트랜잭션 관리자 호출(`DBCTransactionManager.commit` / `rollback`).
  - 자동 커밋(사용자가 `BEGIN TRAN`·`BEGIN`·`START TRANSACTION`으로 연 트랜잭션): `COMMIT` / `ROLLBACK` 문장. 드라이버는 자동 커밋 모드에서 `commit()`을 거절한다
    (mssql-jdbc "AutoCommit 모드가 true로 설정된 경우 롤백 작업을 호출할 수 없습니다", 2026-11-08 확인).
  - 커밋은 DB가 아직 트랜잭션이 있다고 답하는 동안 되풀이한다(최대 32번). SQL Server의 `COMMIT`은 중첩 `BEGIN TRAN`의 안쪽 하나만 끝내므로, 한 번으로 그치면
    사용자가 커밋을 골랐는데 나중에 세션을 닫을 때 나머지가 롤백된다. 롤백은 한 번으로 모두 끝난다.
- Swing 쪽은 작업 스레드에서 하고(`SwingIdleTransactionService.endTransaction`), 실패하면 오류 대화상자를 띄운다.

### 조회만 한 트랜잭션 끝내기 (2026-11-09, 앱 변경 #31)

#210 뒤로 PostgreSQL·MySQL·MariaDB 수동 커밋의 일반 SELECT는 열린 트랜잭션으로 보인다(위 경우표: 테이블 잠금을 끝까지 쥐어 다른 세션의 `ALTER TABLE`을 막는다).
사용자(2026-11-08): "보여주는게 맞을거 같다. 사용자가 인지하고 활성 세션을 꺼야 하니까.. 아니면 트랜잭션을 종료시켜줄 수 있거나".
결정: 표시는 그대로 두고(2026-11-08), 끝내기를 쉽게 한다. 사용자 결정(2026-11-09) "한 번에 끝내기 + 자동 설정".

**조회만 한 트랜잭션** (`QMTransactionState.isReadOnly()`): 끝내도 버리는 것이 없는 열린 트랜잭션. 아래 둘이 모두 참일 때만이다.

- DB가 확인함(`DBCServerTransaction.readsOnly()`): 읽기 잠금과 스냅숏만 쥐고 데이터 변경·행 잠금·다른 잠금이 없다. 방언이 권한 없이 확실히 읽을 수 있을 때만 참이다.
  - PostgreSQL: 수동 커밋, 트랜잭션 ID 없음(모든 데이터 변경과 행 잠금은 먼저 트랜잭션 ID를 받는다. SELECT가 부른 함수의 변경도), 그 세션의 잡힌 잠금이
    관계의 `AccessShareLock`과 SERIALIZABLE의 `SIReadLock`뿐(시스템 카탈로그 제외). `LOCK TABLE`, 시퀀스의 `nextval`(RowExclusiveLock), 권고 잠금이 있으면 아니다.
  - MySQL·MariaDB: 수동 커밋, 로그인이 `INNODB_TRX`를 읽을 수 있고 그 트랜잭션 행이 있으며 `trx_rows_modified` 0, `trx_lock_structs` 0(행 잠금 없음: 읽기 뷰와
    공유 메타데이터 잠금뿐). PROCESS 권한이 없으면 모른다. 문장만으로는 정하지 않는다: SELECT가 데이터를 바꾸는 함수를 부를 수 있다.
    `INNODB_TRX`는 읽을 때 0.1초보다 오래된 경우에만 새로 채우는 사본이라(다른 세션이 막 읽었으면 마지막 문장 전의 사본일 수 있다), "조회만"이라고 답하기 전과
    행이 없을 때는 0.11초 뒤에 한 번 더 읽어 그때의 값으로 정한다. 그 경우에만 묻는 시간이 0.11초 늘어난다(PROCESS가 있는 로그인의 수동 커밋).
  - SQL Server·Oracle·그 밖의 DB: 늘 거짓. SQL Server 일반 SELECT는 쥔 것이 없어 이미 숨김이고 Oracle 일반 SELECT는 트랜잭션을 만들지 않는다. 남는 경우(잠금 힌트,
    REPEATABLE READ·SERIALIZABLE·SNAPSHOT, `SET TRANSACTION`)는 사용자가 고른 것이고, 읽기 잠금인지 쓰기 잠금인지를 권한 없이 읽을 수 없다(위 "SQL Server").
- 쿼리 관리자가 본 문장도 그렇다: 사용자 문장이 1개 이상, 데이터 변경 문장 0개, 잠그는 읽기(`FOR UPDATE`/`FOR SHARE`, `LOCK IN SHARE MODE`, `LOCK TABLE`, SQL Server 잠금 힌트) 없음,
  명시적 BEGIN 없음(자동 커밋 모드의 트랜잭션은 모두 명시적이라 늘 아니다).

어느 하나라도 모르면(DB에 확인할 수 없음, 권한 없음, 답이 그 뒤의 작업보다 오래됨) 조회만 한 트랜잭션이 아니고, 아래 두 동작은 하지 않는다.

**두 종류를 구별해 보이기** (사용자 2026-11-09 "활성 트랜잭션과 lock은 구분하는게 좋지 않나?"):

| 종류 | 상태 줄 칸 | 점 |
|---|---|---|
| 조회 트랜잭션(위 기준), 잠금을 쥠 | `조회 트랜잭션 00:03:15 · 잠금 유지` (`Read-only transaction … · locks held`) | 보조 글자 색(`Chromite.secondaryForeground`, `UiIcons.Status.QUIET`): 주의가 아님 |
| 조회 트랜잭션, 스냅숏만(잠금 없음) | `조회 트랜잭션 00:03:15 · 스냅숏 유지` (`… · snapshot held`) | 보조 글자 색 |
| 데이터 변경이 있음 | `열린 트랜잭션 00:03:15 (변경 있음)` (`Open transaction … (changes)`) | `status.warning`(지금과 같음) |
| 그 밖의 열린 트랜잭션(잠그는 읽기, 명시적 BEGIN, 조회만 했는지 확인할 수 없는 잠금) | `열린 트랜잭션 00:03:15 (문장 3개)`(지금과 같음) | `status.warning` |
| 확인할 수 없음 | 지금과 같음 | 지금과 같음 |

- 도구 설명은 그대로 자세한 것(DB가 쥐고 있는 것, 시작, 문장 수, 마지막 문장)을 적는다. 잠긴 테이블 이름은 지금 묻는 질의가 읽지 않아 적지 않는다.
- 끝낼 때·끊을 때·창을 닫을 때 질문(`ExitTransactionCheck`)은 두 종류 모두 계속 묻는다(둘 다 무언가를 쥔다). 목록의 줄마다 종류를 붙인다:
  "조회 트랜잭션: 읽기 잠금·스냅숏만 유지, 데이터 변경 없음", "열린 트랜잭션: 데이터 변경 있음", "열린 트랜잭션: 잠금 유지 또는 명시적으로 시작함",
  확인할 수 없음은 지금처럼.
- 유휴 알림도 두 종류 모두 알리고, 조회 트랜잭션이면 "조회 트랜잭션이 … 유휴 상태입니다(…, 데이터 변경 없음). 다른 세션의 테이블 변경을 막을 수 있는 읽기 잠금이나
  스냅숏을 쥐고 있습니다"라고 적는다.

**(A) 한 번에 끝내기** — 상태 줄의 열린 트랜잭션 칸:

- 조회만 한 트랜잭션이면 도구 설명 끝 줄이 "조회만 한 트랜잭션입니다(데이터 변경 없음). 클릭하면 트랜잭션을 끝내 잠금을 풉니다. 버리는 데이터 변경은 없습니다"이고,
  누르면 메뉴가 **트랜잭션 끝내기(잠금 해제)**(영어 `End Transaction (Release Locks)`)와 트랜잭션 로그다. 커밋과 롤백은 이때 같은 일이라 둘 대신 하나를 보인다.
  그 밖의 트랜잭션은 지금처럼 커밋 / 롤백이다. Query 메뉴의 Commit / Roll Back은 그대로다.
- 고르면 작업 스레드에서 `IdleTransactionMonitor.endReadOnlyTransaction`: 보여 준 트랜잭션(시작 시각)이고 실행 중이 아닌지 보고, **DB에 다시 묻고**
  (`QMUtils.refreshTransactionState`), 아직 조회만 했을 때만 공용 경로(`commitOrRollback`)로 롤백한다.
- 그 사이 변경이나 다른 잠금이 생겼거나 DB에 확인할 수 없으면 끝내지 않고 "더 이상 조회만 한 트랜잭션이 아닙니다 … 커밋하거나 롤백하시겠습니까?"를 묻는다
  (커밋 / 롤백 / 취소, 취소가 기본). 답은 공용 경로(`SwingIdleTransactionService.endTransaction`)로 같은 트랜잭션만 끝낸다.
- 트랜잭션이 이미 끝났거나 다른 트랜잭션이면 지금처럼 "아무것도 하지 않았습니다"를 알린다.

**(B) 자동으로 끝내기** — 연결 유형 설정 "조회만 한 트랜잭션은 문장이 끝나면 자동으로 끝내기"(Options > Connection Types, 기본 꺼짐, 아래 "설정"):

- 켠 유형의 연결에서 사용자 작업(SQL 실행, 결과 더 읽기, 필터·정렬 다시 읽기)이 끝나 추적기가 DB에 물은 직후, 같은 실행 작업 스레드에서
  `IdleTransactionMonitor.endReadOnlyAfterWork`가 롤백으로 끝낸다. 조건: 그 답이 그 사이 새 작업 없이 그대로(`QMTransactionTracker.probe`의 "current"),
  조회만 한 트랜잭션, 그 문맥에 실행 중인 문장과 다른 작업이 없음, UI 서비스가 있음(보이지 않게 끝내지 않는다).
- 끝내면 로그(INFO)를 남기고 상태 줄의 열린 트랜잭션 칸이 15초 동안 "조회 트랜잭션을 끝냈습니다(잠금 해제)"를 보인다(도구 설명: 끝낸 시각, 설정 이름, 버린 것이
  없음). 그 문맥에서 사용자 문장을 다시 실행하면 지운다.
- 별도 작업으로 미루지 않는 이유: 끝나 가는 실행 작업 자신이 그 문맥의 작업이라 늘 "실행 중"으로 보이고, 미루면 다음 문장이 끼어들 틈이 생긴다.
- 데이터 변경, 명시적 BEGIN, 잠그는 읽기, DB에 확인할 수 없는 상태에서는 하지 않는다(위 기준). 그때는 지금처럼 표시하고 유휴 알림이 맡는다.
- 사용자 규칙 "트랜잭션 안전"과의 관계: 자동 롤백처럼 사용자가 유형에서 직접 켠 경우에만 하고, 보이게 알린다. 버리는 데이터 변경이 없는 롤백이다.
- 알아 둘 것: 켜면 문장마다 트랜잭션이 끝나므로 여러 문장이 한 스냅숏(REPEATABLE READ의 같은 읽기)을 함께 쓰지 않는다. 그렇게 읽으려면 `BEGIN`으로 시작한다
  (명시적 트랜잭션은 끝내지 않는다).

**롤백으로 끝내는 이유**: 조회만 한 트랜잭션은 커밋과 롤백이 같은 일(잠금·스냅숏 해제)이지만, 혹시 놓친 변경이 있어도 롤백은 남기지 않는다.
문구는 "끝내기"이고 버리는 변경이 없다는 것을 함께 적는다.

**확인 (2026-11-09)**: 이 브랜치의 Swing 제품 복사본(한국어 OS)에서 앱 안 시험 에이전트(Attach API, Swing 객체를 EDT에서 직접 다룸, 화면 입력 없음)가
쿼리 창에 경우마다 SQL을 실행하고 1.5초 뒤 상태 줄 칸(글자, 점, 도구 설명), 메뉴 항목, 끝낼 때·끊을 때 질문의 줄, 별도 관찰 연결로 읽은 DB 뷰,
**다른 세션의 `ALTER TABLE … ADD COLUMN`**(PostgreSQL `lock_timeout` 1초, MySQL·MariaDB `lock_wait_timeout` 1초)을 기록했다. 시험 DB: 공유 컨테이너 PostgreSQL 17,
MariaDB 11, SQL Server 2022, Oracle Free 23ai, 이 브랜치가 띄운 MySQL 8.4(끝난 뒤 지움, MariaDB Connector/J로 연결). 연결은 모두 수동 커밋.
증거: `D:\Tools\_scratch\chromite-readonly-txn-end\logs\*.log`, 화면 `shots\`.

설정 꺼짐(A):

| 경우 | PostgreSQL | MySQL·MariaDB(PROCESS 있음) | MySQL·MariaDB(PROCESS 없음) |
|---|---|---|---|
| 일반 SELECT | 조회 트랜잭션 · 잠금 유지(보조 색 점), 메뉴 [트랜잭션 끝내기(잠금 해제), 트랜잭션 로그]. 다른 세션 ALTER 막힘(55P03) → 끝내기 → 트랜잭션 없음, ALTER 통과 | 같음(INNODB_TRX 변경 0·잠금 구조 0, ALTER 1205 → 끝내기 → 통과) | 열린 트랜잭션(주의 점), 메뉴 커밋/롤백(DB가 확인해 주지 않음) |
| 일반 SELECT 뒤 같은 세션에 UPDATE(쿼리 관리자 밖), 그다음 끝내기 | 다시 물어 변경 발견 → 끝내지 않고 "더 이상 조회만 한 트랜잭션이 아닙니다 …" 커밋/롤백/취소(취소 기본) → 취소 → 트랜잭션 그대로(xid, RowExclusiveLock), 칸 "(변경 있음)" | 같음(INNODB_TRX 변경 1) | — |
| SELECT FOR UPDATE / LOCK IN SHARE MODE | 열린 트랜잭션, 커밋/롤백 | 같음 | 같음 |
| DML | 열린 트랜잭션 (변경 있음), 커밋/롤백 | 같음 | 열린 트랜잭션(문장 수), 커밋/롤백 |
| 명시적 BEGIN / START TRANSACTION + SELECT | 열린 트랜잭션, 커밋/롤백 | 같음 | 같음 |
| SELECT bump()(UPDATE 하는 함수) | xid 있음 → 열린 트랜잭션 (변경 있음) | — | — |
| REPEATABLE READ / SERIALIZABLE SELECT | 조회 트랜잭션 · 잠금 유지(잠금, 스냅숏; SIReadLock 포함) | SERIALIZABLE은 잠금 구조 2 → 열린 트랜잭션 | 열린 트랜잭션 |
| LOCK TABLE … IN ACCESS SHARE MODE | 잠그는 읽기 → 열린 트랜잭션 | — | — |

끝낼 때·끊을 때 질문의 줄: 조회 트랜잭션은 "… - 조회 트랜잭션: 읽기 잠금·스냅숏만 유지, 데이터 변경 없음", DML·UPDATE 함수는 "… - 열린 트랜잭션: 데이터 변경 있음",
FOR UPDATE·BEGIN은 "… - 열린 트랜잭션: 잠금 유지 또는 명시적으로 시작함".

설정 켬(B, Options > Connection Types > 개발에서 켜고 확인 → `connection-types.xml`에 `autoEndReadOnlyTransactions="true"`):

- PostgreSQL 일반 SELECT·REPEATABLE READ·SERIALIZABLE SELECT: 실행이 끝나면 트랜잭션 없음, 칸 "조회 트랜잭션을 끝냈습니다(잠금 해제)", 다른 세션 ALTER 바로 통과.
  FOR UPDATE·DML·BEGIN + SELECT·`SELECT bump()`·LOCK TABLE: 끝내지 않음(위와 같은 표시, ALTER 막힘).
- MySQL·MariaDB(PROCESS 있음) 일반 SELECT: 끝냄, ALTER 통과. FOR UPDATE·LOCK IN SHARE MODE·DML·START TRANSACTION·SERIALIZABLE: 끝내지 않음.
  처음 시험에서 앞 경우 바로 뒤의 일반 SELECT가 끝나지 않았다(MySQL·MariaDB 모두 A2: INNODB_TRX 사본에 아직 행이 없음, 안전 쪽). 0.11초 뒤 다시 읽기를 넣고
  다시 돌리자 두 DB 모두 일반 SELECT가 매번 끝났다(`my-p-on-2.log`, `ma-p-on-2.log`).
- MySQL·MariaDB(PROCESS 없음): 어느 경우도 끝내지 않음.
- SQL Server(일반 SELECT 숨김, HOLDLOCK·REPEATABLE READ·DML 열린 트랜잭션), Oracle(일반 SELECT 숨김, FOR UPDATE·SET TRANSACTION READ ONLY·DML 열린 트랜잭션):
  설정을 켜도 끄도 같다. 메뉴는 커밋/롤백.

### 앱이 사용자 세션에 연 트랜잭션 (2026-11-15, 앱 변경 #111)

**보고(#109 작업, 2026-11-13):** PostgreSQL 수동 커밋 쿼리 창에서 커밋한 뒤 서버 세션이 `idle in transaction`(잠금 있음)인데 상태 줄에는 열린 트랜잭션이 없다.

**두 경우를 나눠 확인했다** (PostgreSQL 17, 앱 안 시험 에이전트, 쿼리 관리자 처리기로 창 세션에 간 문장과 보낸 곳을 기록, 따로 연 관찰 연결의
`pg_stat_activity`·`pg_locks`, 증거 `D:\Tools\_scratch\refresh-followups\run\c-pg*.txt.log`):

1. **사용자의 SELECT가 연 트랜잭션:** 위 "트랜잭션 상태"의 결정대로다. 표를 읽은 SELECT는 그 표의 AccessShareLock을 쥐어 "조회 트랜잭션 · 잠금 유지"로
   보이고(숨기지 않음), `SELECT 1`(쥔 것 없음)은 숨긴다. 끊기 질문 목록에 나오고, 자동 커밋으로 바꾸면 "커밋 방식" 질문(커밋하고 전환 / 롤백하고 전환 /
   취소)이 뜬다. 버릴 변경이 없는 트랜잭션이라 조용한 커밋 문제는 없다. 보고의 "읽기만 한 트랜잭션은 열린 것으로 세지 않음"은 맞지 않다(쥔 것이 있으면 센다).
2. **앱이 스스로 보낸 읽기가 연 트랜잭션 (결함):** 창 세션의 기본 스키마 읽기(`PostgreExecutionContext.refreshDefaults`:
   `SELECT reset_val FROM pg_settings …`, `SELECT current_schema(),session_user`)를 다음이 부른다.
   - 상태 줄·Query 메뉴·유휴 알림으로 커밋/롤백한 뒤 `SqlEditorPanel.refreshOpenTransaction` → `refreshSchemas`("Read default schema" 작업);
   - DDL 뒤 새로 고침이 트랜잭션이 끝난 뒤 하는 기본값 다시 맞추기(`SqlMetadataRefresh.refresh`, 204행);
   - 실행 중·실행 끝(`SqlEditorPanel.readSessionDefaults`, 대상 계산 `SQLMetadataRefreshTargetResolver`)은 사용자 트랜잭션 안이거나 자동 커밋이라 남는 것이 없다.
   pgjdbc는 수동 커밋에서 트랜잭션 밖의 아무 문장 앞에 BEGIN을 보내므로, 커밋 직후의 이 읽기가 새 트랜잭션을 열고 그대로 두었다. 상태 줄은 사용자 문장이
   아니라 DB에 다시 묻지 않아 아무것도 보이지 않았다.

고치기 전 (dd2f79e9b1) 수동 커밋 창 B의 서버 상태:

| 사용자 동작 | 앱이 B에 보낸 것 | `pg_stat_activity` / `pg_locks` | 상태 줄 |
|---|---|---|---|
| INSERT → Query > Commit | Read default schema 두 문장 | `idle in transaction`, `xact_start` = 그 읽기 시각, `backend_xmin` 7712, pg_settings AccessShareLock | 없음 |
| CREATE TABLE → Commit | 위 + Refresh metadata after DDL 두 문장 | `idle in transaction`, xmin 7714 | 없음 |
| SET search_path + INSERT → Roll Back | Read default schema | `idle in transaction`, xmin 7715 | 없음 |
| 그 상태로 1분 넘게 둠 | — | 다른 세션이 지운 1000행이 `VACUUM` 뒤에도 `n_dead_tup` 1000(지우지 못함) | 없음 |
| `INSERT …; COMMIT` 한 스크립트 | 실행 중 읽기 | `idle` | 없음 |

그 밖에 남는 것: 다음 사용자 트랜잭션의 `xact_start`가 사용자가 시작한 때가 아니라 앱의 읽기 시각이 되고, 서버에 `idle_in_transaction_session_timeout`이
있으면 사용자가 아무것도 하지 않았는데 세션(임시 테이블, search_path)이 끊긴다(시험 서버는 0).

**고침:** 기본 스키마 읽기를 메타데이터 세션으로 옮길 수는 없다(읽는 것이 그 세션 자신의 search_path·현재 스키마·세션 사용자). 그래서 트랜잭션을 열지 않고
읽는다: `PostgreExecutionContext.refreshDefaults`는 세션이 수동 커밋이고 pgjdbc의 프로토콜 상태가 IDLE(트랜잭션 없음, 왕복 없이 앎, 트랜잭션 확인과 같은
방법)이면 pgjdbc `BaseConnection.execSQLQuery`(드라이버가 자기 내부 질의에 쓰는 길, BEGIN을 보내지 않음)로 같은 두 문장을 읽는다. 트랜잭션이 열려 있거나
자동 커밋이면 전처럼 읽는다(사용자 트랜잭션 안에서 읽거나, BEGIN이 없음). COMMIT·ROLLBACK을 하나도 더하지 않았다. 이 두 문장은 그때 쿼리 관리자에 기록되지
않는다(트랜잭션 확인 질의와 같음). 드라이버가 pgjdbc가 아니면 전처럼 읽는다.

고른 이유: 다른 방법은 모두 사용자 세션에 손을 댄다. 읽은 뒤 롤백·커밋으로 끝내기는 "트랜잭션 안전" 규칙이 막는다(비어 있다고 믿는 트랜잭션도).
읽는 동안 자동 커밋으로 바꾸기는 그 사이 사용자 실행이 끼면 그 문장이 바로 커밋될 수 있다. 커밋 뒤 읽기를 건너뛰면 롤백한 `SET search_path`를
DB 선택 상자가 따라가지 못한다.

고친 뒤 (같은 경우, 증거 `after-pg.txt.log`): 모든 경우에 B는 `idle`(xid·xmin·잠금 없음), 같은 시점의 `VACUUM`이 1000행을 모두 지움(`n_dead_tup` 0),
`SET search_path TO chr_rf_ps` 뒤 선택 상자 `chr_rf_ps` → 롤백 뒤 `public`(롤백을 따라감). 사용자 SELECT는 전처럼 "조회 트랜잭션 · 잠금 유지", 끝내면 `idle`.

**엔진별: 앱 자신의 읽기가 수동 커밋 창 세션에 남기는 것** (고치기 전, 같은 경우: INSERT 뒤 Commit, DDL 뒤 새로 고침, `INSERT; COMMIT`)

| 엔진 | 앱이 창 세션에 보내는 읽기 | 서버 뷰 | 남는 것 |
|---|---|---|---|
| PostgreSQL 17 | `pg_settings` reset_val, `current_schema(),session_user` | `pg_stat_activity`, `pg_locks` | 트랜잭션, xmin, pg_settings 잠금 (**고침**) |
| SQL Server 2022 | `SELECT db_name(), schema_name(), original_login()` | `sys.dm_exec_sessions.open_transaction_count`, `sys.dm_tran_locks` | 없음(0, 잠금 0; 표 없는 SELECT는 암시적 트랜잭션을 시작하지 않음) |
| MySQL 8.4 | `SELECT DATABASE()` | `performance_schema.events_transactions_current`, `INNODB_TRX` | 없음(COMMITTED, 행 없음; 표를 읽지 않아 읽기 뷰·MDL 없음) |
| MariaDB 11 | `SELECT DATABASE()` | `INNODB_TRX` | 없음 |
| Oracle 23ai | `SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL` | `V$TRANSACTION` | 없음(READ COMMITTED 읽기는 트랜잭션을 시작하지 않음) |
| Altibase 7.3 | `SELECT db_name FROM V$DATABASE` | `V$SESSION.TRANS_ID`, `V$TRANSACTION` | 수동 커밋 세션은 읽기 전후 모두 트랜잭션 슬롯이 있다(커밋하면 새 TRANS_ID). 읽기로 달라지는 것 없음(UPDATE_STATUS 0). 확인 질의가 없어 사용자 SELECT는 "확인할 수 없음" |

확인하지 못한 것: SQL Server SNAPSHOT 격리 세션, Oracle 직렬화 세션에서의 같은 읽기(표 없는 읽기라 시작하지 않는다고 보지만 실행하지 않음), PostgreSQL을 잇는 엔진.

## 설정

연결 편집 > 연결 속성 탭, 유휴 연결 닫기 아래.

| 설정 | 저장 위치 (`data-sources.json`의 `configuration`) | 기본값 |
|---|---|---|
| 유휴 상태로 열려 있는 트랜잭션 알림(분) | `idleTransactionReminder`(켜기/끄기), `idleTransactionPeriod`(초) | 연결 유형을 따름: 켜짐, 개발 30분 · 테스트 15분 · 운영 10분 |
| 묻지 않고 자동으로 롤백 | `idleTransactionRollback` | 꺼짐(모든 유형, 기존 연결 포함) |

연결 유형(Options > Connection Types, 트랜잭션 묶음의 알림 시간 아래, 2026-11-09):

| 설정 | 저장 위치 | 기본값 |
|---|---|---|
| 조회만 한 트랜잭션은 문장이 끝나면 자동으로 끝내기 | `connection-types.xml`의 `autoEndReadOnlyTransactions`, `data-sources.json` 유형 사본의 `auto-end-read-only-transactions` | 꺼짐(모든 유형, 기존 유형 포함: 값이 없으면 꺼짐) |

- 알림과 기간은 연결 유형의 기존 값(`DBPConnectionType.isAutoCloseTransactions`, `getCloseIdleTransactionPeriod`)을 기본으로 쓴다.
  연결 유형과 같은 값이면 저장하지 않아 연결이 계속 유형을 따른다. 연결 유형을 바꾸면 알림 값만 그 유형의 기본값으로 바뀐다.
- 자동 롤백은 별도 설정이고 사용자가 그 연결에서 직접 켰을 때만 켜진다. 업스트림의 `autoCloseTransactions=true`(유형 기본값)는 "알림"으로만 읽고 롤백 동의로 읽지 않는다.
  켜면 경고 문구("유휴 트랜잭션의 커밋하지 않은 변경을 묻지 않고 버립니다. 버린 뒤 알림이 뜹니다.")가 보인다. 알림을 끄면 자동 롤백도 꺼진다.
- 설정은 감시가 매번 읽으므로 저장하면 다시 연결하지 않아도 적용된다.
- 연결 유형의 알림과 기간은 Options > Connection Types에서 바꾼다(2026-11-07, [swing-options.md](swing-options.md)). 자동 롤백의 유형별 기본값은 두지 않았다
  (모든 유형 꺼짐). 사용자가 연결마다 켜는 것이 결정이고, 유형에 두려면 모델에 새 필드가 필요하다.

## 설계 결정과 이유

- **감지는 모델, 묻기는 UI 서비스**: AGENTS-Architecture.md의 모델/UI 분리. 모델은 `UIServiceIdleTransactions`만 알고 Swing 번들이 구현을 등록한다.
- **DB에 묻고 메모리에 둔다**(2026-11-08): 열림은 DB의 답으로 정한다(문장 종류로 짐작하면 SQL Server SELECT처럼 쥔 것 없는 트랜잭션을 보이거나 자동 커밋의
  BEGIN TRAN을 놓친다). 묻는 것은 사용자 작업이 끝날 때 실행 작업 스레드에서 한 번이고, 표시·감시는 그 답을 메모리에서 읽는다. 그래서 1초마다 표시해도 DB에
  부담이 없고, 연결이 막혀 있어도 감시가 멈추지 않는다. 주기적으로 묻지 않는 것은 다른 스레드가 같은 연결을 쓰는 중에 끼어들지 않기 위해서다.
- **확인할 수 없음을 따로**: 권한이 없거나 묻지 못하면 열림·닫힘 어느 쪽으로 꾸며도 원칙을 어긴다. 표시는 따로 하고 안전 동작은 열림처럼 다룬다.
- **한 규칙**: 상태 줄, 유휴 감시, 끝낼 때·끊을 때·창을 닫을 때 질문, 자동 커밋 적용 질문, 오류 복구 멈춤, 유휴 연결 닫기, Chromite Agent가 같은 방언 질의와
  같은 합치기를 쓴다(`QMTransactionState.isOpen()`, 에이전트는 `JDBCTransactionProbe`).
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
- 열린 트랜잭션의 기준(2026-11-08): 업스트림은 쿼리 관리자의 문장 기록만 보았다(`QMUtils.isTransactionActive`: 수동 커밋에서 "트랜잭션에 영향을 주는 문장"이
  있음). Chromite는 DB에 묻는다(위 "트랜잭션 상태"). 바꾼 업스트림 파일: `QMUtils.getTransactionState`(합치기), `QMRegistryImpl`(추적기 등록),
  `JDBCExecutionContext`(`DBCTransactionProbe` 구현), 방언 4개(질의). `QMUtils.isTransactionActive` 자체는 그대로 두었다(업스트림 작업 `SQLScriptExecuteHandler`가 씀).
- 조회만 한 트랜잭션 끝내기(2026-11-09): 업스트림 `DBPConnectionType`에 속성 `autoEndReadOnlyTransactions`를 더하고(생성자는 그대로) `connection-types.xml`·
  `data-sources.json`의 유형 사본에 저장한다(`DataSourceProviderRegistry`, `DataSourceSerializerModern`, `RegistryConstants`). 업스트림에는 이런 동작이 없다.
- 앱이 사용자 세션에 연 트랜잭션(2026-11-15): 업스트림 `PostgreExecutionContext.refreshDefaults`는 늘 쿼리 관리자 세션으로 읽어, 트랜잭션 없는 수동 커밋
  세션에 BEGIN이 갔다. Chromite는 그때 pgjdbc의 BEGIN 없는 길로 읽는다(위 "앱이 사용자 세션에 연 트랜잭션").

## 연결 끊기 전 질문

연결을 끊으면 JDBC 연결을 닫으며 열린 트랜잭션이 롤백된다(`JDBCDataSource.closeConnection` → `JDBCExecutionContext.close`).
Swing의 끊기, 다시 연결, 삭제, 연결 편집 뒤 다시 연결은 이것을 묻지 않았다(앱 종료만 물었다). SWT는 `DataSourceHandler.checkAndCloseActiveTransaction`으로 물었다.

- 네 동작이 모두 지나는 `ConnectionCommands`의 끊기 경로에서, `DisconnectJob`을 예약하기 전에 `ExitTransactionCheck.confirmDisconnect`를 부른다.
- 찾기·질문·끝내기는 앱 종료 질문과 같은 코드다: 그 연결의 모든 실행 문맥에서 `QMTransactionState.isOpen()`(2026-11-08부터, 상태 줄과 같은 규칙. 전에는
  `QMUtils.isTransactionActive`: 수동 커밋의 변경 문장만 보아 자동 커밋의 BEGIN TRAN과 SELECT FOR UPDATE의 잠금을 놓쳤다), Chromite Agent·MCP 도구 격리 문맥은
  `AgentSql.hasPendingTransaction`. 있으면 목록과 함께 커밋 / 롤백 / 취소를 묻는다. 끝내기는 공용 경로(`IdleTransactionMonitor.commitOrRollback`)다.
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
  - 어느 문맥이든 열린 트랜잭션이 있다: `QMTransactionState.isOpen()`(위 "트랜잭션 상태": DB가 무언가를 쥐었다고 답했거나 명시적으로 시작했거나 확인할 수 없음)
    또는 `QMUtils.isTransactionActive`(다른 목적의 변경 문장). 연결을 닫으면 JDBC 연결이 닫히며 묻지 않고 롤백되기 때문이다(사용자 규칙 "트랜잭션 안전").
  - Chromite Agent가 그 연결에 열린 트랜잭션을 남겼거나 지금 쓰고 있다(`DBServiceUnrecordedSessions.isConnectionInUse` → `AgentSql.isInUse`).
  - 문장이 실행 중이거나, 그 문맥의 Job(SQL 편집기 실행 등)이나 연결의 작업(`DBPDataSourceTask.isActiveTask`)이 있다. 끊기는 실행 중인 Job을 취소하기 때문이다.
  - 그 연결의 연결·끊기 Job이 예약되었거나 실행 중이다.
- 열린 트랜잭션 정책(#97과 맞춤): 연결을 열어 둔다. 그 트랜잭션은 이 문서의 유휴 트랜잭션 감시가 맡는다(기본: 알리고 묻기, 연결에서 켰을 때만 자동 롤백 뒤 알림).
  커밋·롤백으로 트랜잭션이 끝나면 그때부터 다시 한 기간을 재고 닫는다. 유휴 트랜잭션 알림을 끈 연결은 트랜잭션이 끝날 때까지 열려 있다(조용히 닫지 않는다).
  2026-11-08부터 쥔 것 없는 트랜잭션(SQL Server 수동 커밋의 일반 SELECT 등)만 남은 연결은 닫을 수 있다(닫아도 잃는 것이 없다). PostgreSQL·MySQL의 SELECT처럼
  테이블 잠금을 쥔 트랜잭션과 DB에 확인할 수 없는 트랜잭션은 커밋·롤백 전에는 닫히지 않는다.
- 닫기: `DisconnectJob`을 예약하고, Job이 끊기 직전에 같은 조건을 다시 본다(그 사이 쿼리를 실행했거나 트랜잭션이 열렸으면 아무것도 하지 않는다).
  끊은 뒤 경고 알림을 보낸다: 제목은 연결 이름, 내용 "20초 동안 쓰지 않아 연결을 닫았습니다. 다시 쓰면 다시 연결합니다."(`ModelMessages.model_connection_idle_closed`,
  기간은 `DurationFormatter`). 끊긴 뒤라 데이터 소스가 없으므로 제목을 받는 `ChromiteNotifications.showNotification`을 쓴다.
- 쿼리 창: 닫힌 연결의 쿼리 창은 그대로 쓸 수 있다. 다음 실행이 다시 연결한다(`SqlEditorPanel.prepareAndSchedule` → `DBUtils.initDataSource`).
  연결에 실패하면 기존 연결 오류 표시가 그대로 나온다.
- 설정 도구 설명(`settings_close_idle_tip`): 유휴 시간을 무엇부터 재는지, 열린 트랜잭션이 있으면 닫지 않는다는 것, 다시 쓰면 다시 연결한다는 것.
- 기본값(2026-11-11부터, 앱 변경 #48): **꺼짐.** 기본 연결 유형(개발·테스트·운영)의 `autoCloseConnections`를 끄고, 기간(개발 4시간, 테스트 2시간,
  운영 1시간)은 사용자가 켤 때의 기본값으로 남겼다. 사용자 요청: "연결된 세션은 계속 유지가 되어야 한다". 전에는 업스트림 기본값(켜짐)이라 2026-11-07에
  닫기가 동작하게 된 뒤 쓰지 않은 연결이 일정 시간 뒤 끊겼다(재현: 연결에 30초를 주면 30~35초 사이에 끊김, 증거 `D:\Tools\_scratch\chromite-servers-tree\logs\b-idle.log`).
  - 켜는 곳: Options > Connection Types(그 유형의 모든 연결), 또는 연결 편집 > 연결 속성의 "유휴 연결 닫기(초)"(그 연결만).
  - 이미 저장된 연결 유형 설정(`connection-types.xml`)은 그대로 읽는다. 연결 유형을 한 번이라도 저장한 작업 공간은 모든 유형의 값이 파일에 있으므로
    켜진 채로 남는다. 기본값만 바꾸고 사용자 설정은 건드리지 않는다는 결정이다.
  - 연결 편집기의 칸은 실제로 닫을 때만 켜져 보인다(연결의 `closeIdleConnection`은 모든 연결에서 기본 참이고 "유형을 따름"이라는 뜻이라, 닫지 않는 유형이면 꺼져 보여야 한다).
    닫지 않는 유형의 연결에서 켜면 기간이 유형의 기간과 같아도 연결에 저장한다(저장하지 않으면 유형을 따라 닫히지 않는다). 닫지 않는 유형에서 꺼 둔 채 저장하면
    "유형을 따름"으로 남겨, 나중에 유형에서 켜면 이 연결도 따른다(전에는 끈 채 저장하면 그 연결은 늘 닫지 않음으로 고정됐다).
  - 회사망 방화벽이나 서버가 유휴 세션을 끊는 경우를 위한 연결 유지(keep-alive, 초)는 연결 편집 > 연결 속성의 "연결 유지(초)"에 이미 있다(기본 0 = 끔).

### 절전 때 연결 닫기(A-S8)

2026-11-08부터 Swing이 JDK 절전 알림(macOS만)을 연결 감시에 넘기고, 절전 닫기(`closeAllConnections`)와 깨어난 뒤 다시 초기화(`invalidateSleptConnections`)는
위 "닫지 않는 경우"와 같은 조건(`DataSourceMonitorJob.isInUse`)의 연결을 건너뛴다. 자세한 것은 [connection-lifecycle.md](connection-lifecycle.md) "절전".

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
- 트랜잭션 상태(2026-11-08)에서 남은 것:
  - MySQL·MariaDB 수동 커밋에서 MyISAM 등 트랜잭션이 없는 엔진의 테이블만 읽은 경우는 MDL을 쥐어도 서버가 트랜잭션으로 알리지 않아 닫힘으로 보인다
    (`performance_schema.metadata_locks`를 읽을 수 있는 로그인이면 찾을 수 있지만 권한이 필요하고 드문 조합이라 넣지 않았다).
  - SQL Server에 `VIEW SERVER STATE` 권한이 없는 로그인의 수동 커밋 SELECT는 "확인할 수 없음"이다(위 "SQL Server"). Azure SQL Database는 서버 수준 권한이 없어
    늘 이 경우다(데이터베이스 수준 `VIEW DATABASE STATE`로 읽는 방법은 확인하지 않았다, 시험 서버 없음).
  - 질의가 없는 DB(DB2, SQLite, H2, Altibase 등)는 문장 분류만 한다. 수동 커밋의 SELECT는 "확인할 수 없음"이다.
  - 트랜잭션 로그(`TransactionLogDialog`)는 자동 커밋 모드면 "자동 커밋"이라고만 하고, 자동 커밋의 명시적 트랜잭션 문장은 보이지 않는다(쿼리 관리자는
    자동 커밋에서 트랜잭션 기록을 만들지 않는다). 열린 트랜잭션 목록(`OpenTransactionsDialog`)은 확인할 수 없는 세션도 보이지만 따로 표시하지 않는다.
  - 커밋 방식 전환(`CommitModeSwitch`)·스마트 커밋·오류 복구·유휴 연결 닫기는 새 규칙에 쿼리 관리자의 `isTransactionActive`를 함께 본다(더 많이 묻는 쪽).
    서버가 스스로 롤백한 뒤(SQL Server `XACT_ABORT`, 교착 상태 희생자)에도 그 기록이 남아 한 번 더 물을 수 있다.
  - 상태 줄은 DB의 답을 실행 작업·커밋·롤백이 끝날 때만 새로 받는다(주기적으로 묻지 않음). 그래서 서버가 세션을 끊은 경우(PostgreSQL
    `idle_in_transaction_session_timeout` 등)와 기록을 끈 세션(`enableLogging(false)`, 비밀번호 바꾸기 등)의 커밋·롤백 뒤에는 다음 작업까지 이전 답이 남는다
    (끊긴 세션은 [transactions.md](transactions.md) "끊긴 세션의 쿼리 창"이 다음 실행에서 다룬다).
- 연결 유형별 자동 롤백 기본값(연결 유형 편집기는 2026-11-07에 생겼고 알림·기간은 그 화면에 있다). 넣는다면 사용자 결정을 다시 받는다.
- 조회만 한 트랜잭션 끝내기(2026-11-09)에서 남은 것:
  - MySQL·MariaDB에서 `INNODB_TRX`를 읽을 수 없는 로그인(PROCESS 권한 없음, 흔한 경우)은 조회만 했는지 DB가 확인해 주지 않아 한 번에 끝내기·자동으로 끝내기가
    없다(보통의 커밋 / 롤백). 권한 없이 읽는 방법(서버의 `session_track_transaction_info` 상태 추적)은 드라이버가 값을 내주지 않아 넣지 않았다.
  - MySQL·MariaDB에서 PROCESS가 없는 로그인의 데이터 변경 문장은 칸이 "(변경 있음)"이 아니라 문장 수로 보인다(DB의 답이 "잠금"뿐이라 쥔 것은 DB의 답을 쓴다,
    위 "합치기"). 점은 같은 주의 색이다.
  - 유휴 트랜잭션 알림은 조회만 한 트랜잭션에도 지금처럼 커밋 / 롤백 / 열어 두기다(끝내기 단추 없음). 유휴 연결 닫기도 조회만 한 트랜잭션이 있으면 닫지 않는다(그대로).
- 다른 세션의 열린 트랜잭션(Activity Monitor 탭)은 다음 작업이다.

## 변경 이력

- 2026-11-07 최초 작성: 쿼리 창 상태 줄의 열린 트랜잭션 표시, 유휴 트랜잭션 알림(기본)과 자동 롤백(연결에서 켰을 때만), 업스트림 유휴 롤백 경로 교체
- 2026-11-07 연결 끊기 전 질문: 끊기·다시 연결·삭제·편집 뒤 다시 연결이 열린 트랜잭션을 묻지 않고 롤백하던 것을 막음
- 2026-11-07 유휴 연결 닫기가 동작하게 함(연결별 마지막 사용 기준, 열린 트랜잭션·실행 중이면 닫지 않음, AI 도우미 세션 포함, 닫은 뒤 경고 알림),
  모델 알림을 메인 창 알림으로 보임. PostgreSQL·SQL Server에서 실행 확인(증거: `D:\Tools\_scratch\idle-close-notifications\`)
- 2026-11-07 에이전트 기능의 공식 이름을 Chromite Agent로 정함([ai-agent.md](ai-agent.md) "명칭"): 이 문서의 설명에서 "AI 도우미"를 바꿈.
- 2026-11-07 상태 줄 메뉴에 트랜잭션 로그, 공용 커밋 경로를 Query 메뉴·도구 막대·단축키·열린 트랜잭션 목록도 씀([transactions.md](transactions.md))
- 2026-11-07 연결 유형 편집기(Options > Connection Types)가 생겨 유형의 알림·기간을 바꿀 수 있음을 반영([swing-options.md](swing-options.md))
- 2026-11-07 쿼리 창마다 별도 연결: 상태 줄과 알림의 쿼리 창이 창의 실제 실행 문맥을 따름([transactions.md](transactions.md) "쿼리 창마다 별도 연결")
- 2026-11-07 같은 UI 서비스(`UIServiceIdleTransactions`)에 오류 복구가 멈춘 알림 두 가지를 더함. 다시 연결 알림이 언제 뜨는지 고침
  ([transactions.md](transactions.md) "오류 복구와 열린 트랜잭션")
- 2026-11-08 절전 때 연결 닫기·깨어난 뒤 다시 초기화가 쓰는 중인 연결을 건너뜀(유휴 연결 닫기의 조건을 `isInUse`로 뺌)([connection-lifecycle.md](connection-lifecycle.md) "절전")
- 2026-11-09 트랜잭션 상태를 DB의 답으로(앱 변경 #1, `chromite/txn-real-state`): 실제로 쥐고 있을 때만 열림(SQL Server 수동 커밋의 일반 SELECT는 숨김,
  자동 커밋의 BEGIN TRAN은 보임), 확인할 수 없음을 따로 보임, DB별 질의를 모델 계층 방언으로(`JDBCTransactionProbe`, Chromite Agent 질의를 옮김),
  상태 줄·유휴 감시·끝낼 때·끊을 때 질문·자동 커밋 적용·Agent가 한 규칙, 자동 커밋 트랜잭션은 COMMIT/ROLLBACK 문장으로 끝내고 중첩 BEGIN TRAN은 끝까지 커밋.
  SQL Server·PostgreSQL·MySQL·MariaDB·Oracle 컨테이너에서 수동·자동 커밋 경우표("확인: DB별 경우표")
- 2026-11-09 조회만 한 트랜잭션 끝내기(앱 변경 #31, `chromite/readonly-txn-end`, 사용자 결정 "한 번에 끝내기 + 자동 설정"): DB가 읽기 잠금·스냅숏만 쥐었다고
  확인한 트랜잭션(PostgreSQL, `INNODB_TRX`를 읽을 수 있는 MySQL·MariaDB)은 상태 줄 메뉴의 트랜잭션 끝내기(잠금 해제) 한 번으로 끝냄(직전에 다시 묻고 변경이 생겼으면
  커밋 / 롤백 질문), 연결 유형 설정(기본 꺼짐)으로 문장이 끝나면 자동으로 끝내고 상태 줄에 알림. SQL Server·Oracle은 바뀌지 않음.
  상태 줄 칸이 조회 트랜잭션(보조 색 점, "· 잠금 유지"/"· 스냅숏 유지")과 변경 있는 트랜잭션("(변경 있음)")을 구별하고, 끝낼 때·끊을 때 질문과
  유휴 알림이 종류를 적음(사용자 "활성 트랜잭션과 lock은 구분하는게 좋지 않나?")
- 2026-11-11 유휴 연결 닫기 기본값을 모든 기본 연결 유형에서 끔(앱 변경 #48, `chromite/servers-tree`). 저장된 연결 유형 설정은 그대로, 연결 편집기의 칸은 실제로 닫을 때만 켜져 보임
- 2026-11-15 "앱이 사용자 세션에 연 트랜잭션"(앱 변경 #111 (c), `chromite/refresh-followups`): PostgreSQL 수동 커밋 창에서 커밋·롤백 뒤와 DDL 뒤 새로 고침 뒤의
  기본 스키마 읽기가 BEGIN을 보내 세션을 `idle in transaction`(xmin, pg_settings 잠금)으로 두던 것을 고침(BEGIN 없이 읽음). 사용자 SELECT의 조회 트랜잭션 표시는
  결정대로임을 확인, 엔진별 표. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: PostgreSQL 쿼리 창의 DB/스키마 선택 상자, DDL 뒤 기본값 다시 맞추기, Agent의 기본 스키마
