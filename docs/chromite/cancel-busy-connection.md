# 바쁜 메타데이터 연결에서의 취소 (2026-11-22, 앱 변경 #161)

## 목적

진행 창(`ProgressDialog`)의 취소가 늘 사용자를 바로 풀어 주게 한다. 메타데이터 연결이 다른 작업의 문장으로 바쁠 때 그 연결이 필요한 요청(DDL 보기,
디자인, 스크립팅 등 메타데이터 세션을 여는 진행 창 작업)은 JDBC 드라이버의 연결 잠금에서 기다린다. 이때 취소는 기다리는 요청을 멈추지 못하고, 어떤 DB에서는
**다른 작업의 문장**을 멈췄다. 다른 문장이 끝나지 않으면(큰 사전의 느린 조회, 방화벽이 조용히 끊은 연결) 진행 창은 끝나지도 취소되지도 않았다(실사용자 보고,
앱 변경 #159, [swing-routines.md](swing-routines.md) "DDL 권한과 취소").

## 고치기 전 측정 (2026-11-22, 시험 VM, 스크래치 작업 공간)

방법: 앱 안 시험 드라이버의 스레드가 `DBUtils.openMetaSession`(DDL 경로가 부르는 것과 같은 호출)으로 연결의 메타데이터 문맥에 세션을 열고 서버 쪽 45초
대기(Oracle `DBMS_SESSION.SLEEP`, SQL Server `WAITFOR DELAY`, PostgreSQL `pg_sleep`, MariaDB `SLEEP`, Altibase `DBMS_LOCK.SLEEP`)를 실행한다. 그동안
Object Explorer의 테이블에서 "DDL 보기"와 "디자인"을 누르고 3초 뒤 진행 창의 취소를 누른다(시험 개체 `CHR_CXL_*`).

| DB (드라이버) | 두 번째 요청이 기다리는 곳 (스레드 덤프) | 그때 가장 안쪽 블록 | 취소가 한 일 | 진행 창 | 먼저 돌던 문장 |
|---|---|---|---|---|---|
| Oracle 26ai (ojdbc11) | `PhysicalConnection.prepareStatement` → `Monitor.acquireLock` (문장을 만들기 전) | 세션 | `connection.cancel()` | 2.6–2.7초 뒤 닫힘 | **ORA-01013으로 멈춤** |
| SQL Server 2022 (mssql-jdbc 13.4) | `SQLServerConnection.executeCommand`의 `ReentrantLock` (자기 문장의 execute 안) | 문장 | `Statement.cancel()`: 아무 일 없음 | 30초 넘게 남음(다른 문장이 끝날 때 닫힘) | 끝까지 돎 |
| PostgreSQL 17 (pgjdbc 42.7) | `QueryExecutorImpl.execute` → `ResourceLock.obtain` (자기 문장의 execute 안) | 문장 | `Statement.cancel()` = 백엔드 취소 요청 | 0.2초 뒤 닫힘 | **"canceling statement due to user request"로 멈춤** |
| MariaDB 11.8 (Connector/J 3.5) DDL 보기 | `DatabaseMetaData.getImportedKeys`의 드라이버 내부 문장 잠금 | 세션 | 기본 `cancelCurrentExecution`: 아무 일 없음 | 30초 넘게 남음 | 끝까지 돎 |
| MariaDB 디자인 | `ClientPreparedStatement.executeInternal`의 잠금 (자기 문장) | 문장 | `Statement.cancel()` = `KILL QUERY` | 0.3초 뒤 닫힘 | **"Query execution was interrupted"로 멈춤** |
| Altibase 7.3 (Altibase.jar) | `CmProtocol.directExecuteAndFetch`의 `synchronized(CmChannel)` (`DatabaseMetaData` 안) | 세션 | 아무 일 없음 | 30초 넘게 남음 | 끝까지 돎 |

- 기다림은 모든 드라이버에서 인터럽트할 수 없다(`ReentrantLock.lock()`, `synchronized`). 그래서 기다리는 스레드를 깨우는 길은 없다.
- 다른 문장이 끝난 뒤: 취소한 "디자인"이 네 DB(Oracle, SQL Server, PostgreSQL, MariaDB)와 Altibase 모두에서 **디자이너 탭을 열었다.** Altibase "DDL 보기"는
  취소한 뒤 모델의 NPE(`AltibaseMetaModel.getTableDDL`, 취소된 모니터에서 인덱스 목록이 null)가 **늦게 오류 창**("스크립트를 생성할 수 없습니다.")으로 떴다.
- 세션은 다른 문장이 끝난 뒤 모두 다시 쓸 수 있었다(다음 DDL 보기 정상).
- **쿼리 창과 세션을 함께 쓰는 설정**("단일 연결", 쿼리 창과 메타데이터가 주 문맥 하나를 씀): PostgreSQL에서 쿼리 창이 수동 커밋으로 INSERT한 뒤 `pg_sleep(40)`을
  실행하는 동안 DDL 보기를 취소하자 **쿼리 창의 문장이 7.2초에 멈추고 서버의 트랜잭션이 `idle in transaction (aborted)`**가 되었다(커밋할 수 없음, INSERT를 잃음).
  Oracle에서는 쿼리 창의 40초 SELECT가 ORA-01013으로 멈췄다(트랜잭션은 열린 채 남음). 기본 설정(쿼리 창마다 별도 연결, 메타데이터 별도 연결)에서는 쿼리 창 세션이
  닿지 않았다.
- 쿼리 창 중지(기준): Oracle 5.8초, SQL Server·PostgreSQL·MariaDB 0.25초, Altibase 0.76초 뒤 멈추고 "사용자가 쿼리를 취소했습니다.".

## 설계

두 가지를 바꾼다. 둘 다 취소·기다림 경로의 공용 클래스 안이다.

1. **진행 창이 사용자를 풀어 준다(`ProgressDialog.runTask`).** 취소를 누르고 1.5초가 지나도 작업이 끝나지 않았고, 작업이 **메타데이터 세션의 DB 호출 안**
   (모니터의 활성 블록 중 목적이 `META`인 세션이나 그 세션의 문장)에 있으면 창을 닫고 호출한 쪽에는 `DBInterruptedException`(취소)을 돌려준다. 작업 스레드는
   죽이지 않는다. 작업은 취소된 모니터로 계속 돌다 연결을 얻으면 끝나고, 그 결과와 오류는 버린다(디버그 로그만). 그래서 늦은 오류 창이나 늦은 탭이 생기지 않는다.
   취소하는 동안 창의 글은 기존 "취소하는 중"이다(새 문구 없음).
2. **기다리는 요청의 취소가 다른 문장에 닿지 않는다(`JDBCExecutionContext`, `JDBCStatementImpl`, `JDBCConnectionImpl`).** 문맥이 드라이버 execute 호출 안에
   있는 문장을 시작한 순서로 기억한다. 연결은 한 번에 한 호출만 처리하므로 가장 먼저 시작한 문장이 서버가 처리 중인 문장이다. 그 문장이 **다른 세션**의 것이면
   취소하려는 세션의 호출은 연결을 기다리는 중이므로, 문장 블록 취소(`Statement.cancel`)와 세션 블록 취소(`cancelCurrentExecution`)를 드라이버에 보내지 않는다.
   그 밖의 경우(자기 문장이 가장 먼저 시작함, 실행 중인 문장 없음: 자기 `DatabaseMetaData` 호출이나 결과 읽기)는 전과 같이 보낸다.

### 왜 이것이 가장 작은가 (대안과 크기)

| 대안 | 크기 | 버린 이유 |
|---|---|---|
| 기다림을 인터럽트할 수 있게 (모니터를 보며 기다리는 앱 수준 잠금을 모든 문장 앞에) | 모든 DB의 모든 문장 앞(문장 만들기, execute, `DatabaseMetaData`, 커밋)에 잠금, 수백 줄 | 드라이버 잠금은 그대로라 Oracle처럼 prepare에서 기다리는 것과 `DatabaseMetaData` 안의 기다림을 막지 못하고, 잠금 순서가 바뀌어 교착 위험 |
| 취소 뒤 남은 작업이 연결을 얻으면 문장을 실행하지 않게 (`beforeExecute`에서 취소된 모니터 거절) | `JDBCStatementImpl` 몇 줄 | 취소된 모니터로 일부러 문장을 실행하는 경로(쿼리 창 중지 뒤 서버 출력 읽기 등)의 동작이 바뀜. PostgreSQL·SQL Server·MariaDB는 기다림이 execute 안이라 첫 문장은 막지도 못함 |
| 모든 진행 창 작업을 취소 뒤 풀어 줌 | `ProgressDialog`만 | 드라이버 내려받기, 설정 가져오기, 백업·복원 같은 DB 밖 작업과 커밋·저장 같은 쓰기가 사용자가 취소했다고 본 뒤에 끝남. 메타데이터 읽기로 한정 |
| 스레드 인터럽트·연결 닫기 | — | 규칙상 금지(스레드를 죽이지 않음, 사용자 세션을 닫지 않음, 트랜잭션 안전) |

고른 설계는 클래스 4개(Swing 번들 1, `model.jdbc` 번들 3), 쿼리 창 중지 코드는 바꾸지 않는다.

### 동작이 바뀌는 곳

- `ProgressDialog.runTask`를 부르는 곳 중 메타데이터를 읽는 작업: 연결이 바쁠 때 취소하면 1.5초 뒤 창이 닫히고 `DBInterruptedException`을 받는다.
  `DBInterruptedException`을 따로 잡는 곳(`ObjectExplorerPanel.generate`, `ExplorerObjectCommands.generateSql`, `TableDesignerPanel.create`, `ObjectScripts`
  경로 등)은 아무것도 보이지 않는다. 따로 잡지 않는 곳은 기존 오류 창에 취소를 보일 수 있다(아래 "남은 것").
- `ProgressDialog.waitForJob`(그리드 저장·미리 보기, `executeWithProgressBlocking`)과 메타데이터가 아닌 세션(`UTIL` 커밋·롤백, `META_DDL`, 사용자 문장)의 작업은 바뀌지 않는다.
- 모든 Job의 블록 취소(`AbstractJob` → `JDBCStatementImpl.cancelBlock` / `JDBCConnectionImpl.cancelBlock`): 다른 세션의 문장 뒤에서 기다리는 중이면 드라이버에
  취소를 보내지 않는다. 쿼리 창 중지는 쿼리 창의 자기 문맥에서 자기 문장이 가장 먼저 시작했으므로 전과 같다. 쿼리 창들이 한 세션을 함께 쓰고 다른 창의 문장
  뒤에서 기다리는 중에 누른 중지는 이제 그 다른 창의 문장을 멈추지 않는다(전에는 Oracle·PostgreSQL·MariaDB에서 멈췄다).

### 다른 문장과 사용자 세션

- 먼저 돌던 다른 작업의 문장은 끝까지 돈다(어느 DB에서도 취소를 받지 않음). 기다리던 요청은 그 뒤에 연결을 얻어 문장을 하나 실행하고 취소된 모니터를 보고 끝난다.
- 쿼리 창의 세션·트랜잭션은 건드리지 않는다. 세션을 닫거나 커밋·롤백하지 않는다.

### 응답하지 않는 연결

다른 문장이 끝나지 않으면 남은 작업도 끝나지 않고 그 스레드는 연결이 풀릴 때까지 기다린다. 진행 창은 1.5초 뒤 닫히고 앱은 쓸 수 있다. 같은 연결이 필요한 다음 요청도
다시 기다리고, 다시 취소하면 다시 풀려난다. 연결을 되살리는 것은 기존 무효화·다시 연결이다(이 변경은 새 다시 연결 기능을 만들지 않는다).

## 구조

| 클래스 | 번들 | 바꾼 것 |
|---|---|---|
| `ProgressDialog` | `com.seizia.chromite.ui.swing` | 취소 뒤 1.5초부터 0.25초마다 보고 메타데이터 호출 안이면(`isInMetadataCall`) 창을 닫으며, `runTask`가 `DBInterruptedException`을 던짐. 남은 작업의 결과·오류는 버림 |
| `JDBCExecutionContext` | `com.seizia.chromite.model.jdbc` | execute 중인 문장 목록(시작 순서)과 `isServingOtherSession` |
| `JDBCStatementImpl` | 같음 | `beforeExecute`/`afterExecute`에서 목록에 넣고 뺌, `cancelBlock`이 다른 세션 뒤에서 기다리면 드라이버 취소를 보내지 않음 |
| `JDBCConnectionImpl` | 같음 | `cancelBlock`이 다른 세션 뒤에서 기다리면 `cancelCurrentExecution`을 부르지 않음 |

## 확인 (2026-11-22, 고친 빌드, 같은 방법)

| DB | DDL 보기: 취소 뒤 창이 닫힘 | 디자인: 취소 뒤 창이 닫힘 | 먼저 돌던 문장 | 늦은 탭·오류 창 | 연결이 풀린 뒤 DDL 보기·디자인 |
|---|---|---|---|---|---|
| Oracle | 1.61초 | 1.61초 | 45초 끝까지(OK) | 없음 | 정상(DDL 글 고치기 전과 바이트 같음) |
| SQL Server | 1.61초 | 1.61초 | 끝까지 | 없음 | 정상(같음) |
| PostgreSQL | 1.62초 | 1.61초 | 끝까지 | 없음 | 정상(같음) |
| MariaDB | 1.61초 | 1.61초 | 끝까지 | 없음 | 정상(같음) |
| Altibase | 1.62초 | 1.61초 | 끝까지 | 없음(모델의 NPE는 디버그 로그에만) | 정상(같음) |

- 남은 작업은 다른 문장이 끝난 뒤 조용히 끝났다(디버그 로그 "Canceled task '…' ended after its dialog was closed"). 기다리는 동안의 취소는 디버그 로그
  "… waits for the connection behind another session; cancel not sent"로 남는다.
- **쿼리 창과 세션을 함께 쓰는 설정**(다섯 DB 모두, 수동 커밋): 쿼리 창이 INSERT한 뒤 40초 문장(Oracle 대기 함수 SELECT, `pg_sleep`, `SLEEP`, `WAITFOR`,
  `DBMS_LOCK.SLEEP`)을 실행하는 동안 DDL 보기를 취소 → 창 1.61초 뒤 닫힘, 쿼리 창의 문장은 40초 끝까지 돌고 결과를 보임, 트랜잭션은 열린 채(창 안에서 1행,
  다른 세션에서 0행, Oracle `v$transaction` 1, PostgreSQL `idle in transaction`과 xid, MariaDB `innodb_trx` RUNNING 1행, SQL Server `open_transaction_count` 1).
  시험 행은 쿼리 창에서 직접 ROLLBACK했다.
- **응답하지 않는 연결**: 이 PC의 시험용 TCP 프록시(바이트를 넘기지 않고 멈춤)를 거친 PostgreSQL·Oracle 연결에서 DDL 보기와 디자인을 차례로 취소 → 둘 다
  1.61초 뒤 닫힘, 그동안 다른 연결의 DDL 보기는 정상. 두 번째 요청은 첫 요청 뒤에서 다시 기다렸다(취소하면 다시 풀려남). 기존 "연결 끊김" 처리는 오류가 나지
  않으므로 뜨지 않는다. 프록시를 풀자 남은 작업은 조용히 끝나고 다음 DDL 보기는 정상.
- **자기 문장을 실행 중인 요청의 취소(#159의 경우)**: PostgreSQL 뷰를 다른 세션이 `ACCESS EXCLUSIVE`로 잠근 채 DDL 보기를 취소 → 0.2초 뒤 닫힘(문장 취소가
  전처럼 감), 탭·오류 창 없음, 잠근 세션의 문장은 끝까지.
- **쿼리 창 중지**: 고치기 전과 같다. Oracle 5.78초(전 5.78초), SQL Server·PostgreSQL·MariaDB 0.25초(전 0.25초), Altibase 0.75초(전 0.76초), 모두
  "사용자가 쿼리를 취소했습니다.".
- 취소하지 않는 경우: DDL 보기, 디자인, 속성 창(Oracle·PostgreSQL 테이블), 트리 펼치기가 전과 같다.

## 남은 것

- ~~`DBInterruptedException`을 따로 잡지 않는 `runTask` 호출의 오류 창~~: 2026-11-22 고침(앱 변경 #167, 아래 "취소는 오류가 아님"). 오류 창을 거치지 않고
  취소를 글로 보이는 10곳은 그 절의 "남은 곳".
- 풀려난 작업의 스레드는 연결이 풀릴 때까지 남는다(죽이지 않음). 연결이 끝내 응답하지 않으면 사용자가 무효화·다시 연결을 해야 한다. 바쁜 연결을 알아채
  다시 연결을 권하는 기능은 만들지 않았다: 만들려면 연결이 응답하지 않는 것을 알아낼 기준(예: 메타데이터 호출이 N초 넘게 다른 세션 뒤에 있음)과 그때의
  안내(기존 연결 끊김 대화상자 재사용), 열린 트랜잭션 질문이 필요하다.
- `waitForJob`(그리드 저장·미리 보기)과 메타데이터가 아닌 세션의 작업(커밋·롤백, DDL 실행)은 바쁜 연결 뒤에서 기다리면 전처럼 끝날 때까지 창이 남는다.
  다만 그 취소도 이제 다른 세션의 문장을 멈추지 않는다.
- Altibase: 취소된 모니터에서 `AltibaseMetaModel.getTableDDL`이 null 인덱스 목록으로 NPE를 낸다(이제는 버려져 보이지 않음, 모델은 고치지 않음).
- PostgreSQL의 세션을 다시 열 때의 교착(앱 변경 #165에서 재현, 고치지 않음): `PostgreExecutionContext`는 기본 스키마를 스키마 OID로 기억하고 OID는 목록에서만
  얻는다. 고치려면 목록이 없을 때 이름을 두었다가 목록을 읽은 뒤 OID를 찾게 하고(`setActiveSchema`, 검색 경로의 첫 스키마, 연결 설정의 기본 스키마
  `setSearchPath`, `getDefaultSchema`·`computeSearchPath`·`getCachedDefault`), 연결할 때 목록을 미리 읽던 것을 어떻게 할지 정해야 한다(약 50-70줄, PostgreSQL
  전용 확인 필요). 같은 구조의 DB2·Exasol(`initializeContextState`가 `initFrom.getDefaultSchema()`)은 시험 서버가 없어 보지 않았다(각 몇 줄).
- 다른 길: 목록을 읽는 쪽(A)이 무효화 중인 문맥을 스스로 다시 열지 않게 하면(`getConnection`의 `connect`) 모든 DB의 이 교착이 한 곳에서 풀리지만, 무효화
  중의 요청이 기다리는 대신 오류가 된다. 그 다시 열기는 무효화가 곧 다시 여는 문맥을 한 번 더 열기도 한다(공용 코드, 바꾸지 않음).

## 세션을 다시 열 때의 교착 (2026-11-22, 앱 변경 #165)

응답하지 않는 연결을 되살리는 Invalidate/Reconnect가 배경 작업과 서로 기다려 그 연결이 앱을 다시 시작할 때까지 멈췄다. 창은 움직이지만 그 연결의 모든
요청이 끝나지 않는다. 앱 변경 #164([swing-explorer-productivity.md](swing-explorer-productivity.md) "기본값 굵게 표시")가 이벤트 스레드 쪽을 고친 뒤
남은 배경 스레드 둘 사이의 경우다.

### 원인

| 스레드 | 쥔 것 | 기다리는 것 |
|---|---|---|
| A: 스키마 목록을 읽는 배경 작업(쿼리 창 자동 완성 `SQLQueryCompletionContext.obtainDefaultContext` → `getDefaultSchema` → 목록 캐시) | 목록 캐시의 모니터(`JDBCObjectCache.loadObjects`는 `synchronized`) | 닫힌 메타데이터 세션을 다시 열려고 `JDBCExecutionContext.connect` → 인스턴스의 배타 잠금 |
| B: Invalidate/Reconnect(`InvalidateJob.invalidateInstances`가 문맥마다 배타 잠금을 쥐고 `invalidateContext`) | 인스턴스의 배타 잠금 | `initializeContextState`가 원본 세션의 기본 스키마 **개체**를 찾느라 같은 목록 캐시 |

A는 B가 세션을 닫은 뒤(BEFORE_INVALIDATE) 다시 열기 전에 목록을 읽기 시작하면 이 상태가 된다. 목록이 비어 있어야 하므로(연결 직후 탐색기에서 스키마
폴더를 펼치지 않았거나, Refresh가 비운 뒤) 흔하지는 않지만, 서버가 응답하지 않아 사용자가 Invalidate/Reconnect를 누르고 그동안 쿼리 창에서 자동 완성을
쓰면 생긴다. `JDBCExecutionContext.connect`의 주석("Do it outside of exclusive lock")은 `connect` 자신의 잠금만 말한다. 무효화(`InvalidateJob`)와 끊긴 세션
다시 열기(`TransactionRecoveryGuard.reopenLostSession`)는 바깥에서 같은 잠금을 쥐고 부른다.

**고치기 전 재현**(시험 VM, 이 PC의 지연 프록시를 거친 연결, 앱 안 시험 드라이버): 연결(목록을 읽지 않은 상태) → 프록시 멈춤 → Invalidate/Reconnect → 8초 뒤
자동 완성 계산(`SqlCompletionAnalysis.compute`, Ctrl+Space가 작업에서 부르는 것을 시험 스레드에서) → 프록시 풀기. Oracle·SQL Server·PostgreSQL 세 DB 모두
영구 교착(풀고 60초 뒤에도 그대로): 무효화 스레드는 `BLOCKED on …SchemaCache owned by "bg-complete-test"`, 자동 완성 스레드는
`SimpleExclusiveLock.acquireExclusiveLock` ← `JDBCExecutionContext.connect` ← `JDBCObjectCache.loadObjects`. 무효화 쪽 자리는 Oracle
`OracleDataSource.initializeContextState`(`initFrom.getDefaultSchema()`), SQL Server `SQLServerDataSource.initializeContextState`(`ssContext.getDefaultSchema()`),
PostgreSQL `PostgreExecutionContext.setActiveSchema`(`getSchemas`).

### 고친 것

세션을 여는 길은 목록을 읽지 않는다. 원본 세션의 기본 스키마를 **이름**으로 받는다.

| 자리 | 바꾼 것 |
|---|---|
| `OracleDataSource.initializeContextState`, `YashanDBDataSource.initializeContextState` | `OracleExecutionContext.setCurrentSchemaFrom(원본)`: 원본이 가진 이름(`getActiveSchemaName`)으로 `ALTER SESSION SET CURRENT_SCHEMA`. 이름이 없으면 전처럼 아무것도 안 함. 설정이 실패하면(그새 지운 스키마) 경고를 남기고 세션의 실제 스키마를 읽는다(`refreshDefaults`) |
| `OracleExecutionContext.setCurrentSchema(String)` | 바뀜 알림의 옛·새 개체를 메모리의 목록에서만 찾음(`getLoadedSchema`). 연결 설정의 기본 스키마(`refreshDefaults`)도 이 길을 지나므로 연결할 때 목록을 읽지 않는다 |
| `SQLServerDataSource.initializeContextState` | `SQLServerExecutionContext.setDefaultSchemaFrom(원본)`: 이름을 받고 알림은 메모리의 목록에서(`getLoadedDefaultSchema`, 없으면 `dbo`) |

**알림을 받는 쪽.** Swing에서 `OBJECT_SELECT` 알림을 받는 것은 탐색기 모델(`DBNProjectDatabases`, 그 노드를 다시 그림)뿐이다. 쿼리 창의 스키마 선택과
상태 줄은 알림이 아니라 세션이 가진 이름과 목록을 작업 스레드에서 읽는다. 목록이 메모리에 있으면 알림은 전과 같은 개체를 싣는다. 목록이 없으면 알림을
보내지 않는다. 그때 탐색기에는 스키마 노드가 없거나(목록을 읽어야 노드가 생김) #164에 따라 목록을 읽기 전까지 보통 글꼴이고, 목록을 읽으면 그리기가
`getDefaultSchema`로 맞는 줄을 굵게 한다.

**바뀌는 것.** 원본 세션의 스키마가 목록에 없을 때(탐색기 필터로 숨김, "빈 스키마 숨기기") 전에는 새 세션의 스키마를 바꾸지 않았고 이제는 그 이름으로
바꾼다. 다시 연 세션이 사용자가 쓰던 스키마를 잃지 않는다. 연결 설정에 기본 스키마가 있으면 연결할 때 목록 읽기(ALL_USERS 한 문장)가 없어졌다.

### 다른 DB

| DB | 세션을 여는 길에서 잠금 아래 기다리는 캐시 | 결과 |
|---|---|---|
| Oracle(+ YashanDB) | 스키마 목록 | 고침, 재현·확인 |
| SQL Server | 기본 DB의 스키마 목록 | 고침, 재현·확인 |
| PostgreSQL | 데이터베이스의 스키마 목록(`refreshDefaults` → `setActiveSchema` → `getSchemas`, `setSearchPath` → `getSchema`) | **재현, 고치지 않음**(위 "남은 것") |
| MySQL/MariaDB | 읽기 전용 연결의 `refreshDefaults` → `getObjectReadingNew`: 목록이 다 읽혔고 현재 DB가 없을 때만 모니터를 기다림 | 고치지 않음(두 스레드가 동시에 "없는 이름"으로 다시 읽을 때만, 재현하지 않음) |
| Altibase 계열, Generic, SQLite | 없음(카탈로그·스키마는 연결할 때 읽어 둔 메모리 목록, DBMS_OUTPUT은 SQL만) | 해당 없음 |
| Snowflake(코드만) | 데이터베이스의 스키마 목록을 읽지만 `GenericCatalog.getSchemas`는 모니터를 쓰지 않음 | 교착 없음(같은 목록을 두 번 읽을 수 있음), 바꾸지 않음 |
| SQL Server·MySQL `refreshDefaults`의 `getObjectReadingNew` | MySQL과 같음 | 고치지 않음 |

### 확인 (2026-11-22, 고친 빌드, 같은 방법)

- 재현 방법 그대로 Oracle 1회 + 20회 반복(매번 목록을 비우고 프록시 멈춤 → Invalidate/Reconnect → 자동 완성 → 풀기): 20/20 교착 없음. 자동 완성은 풀린 뒤
  약 2초에 끝났고 무효화 작업·스레드가 남지 않았으며, 매번 두 세션 모두 쓸 수 있었다(`CURRENT_SCHEMA=SYSTEM`). 고치기 전과 같은 순간(자동 완성이 닫히는 메타데이터
  연결을 기다림)에 들어간 것을 스레드 덤프로 확인. SQL Server도 같은 방법으로 교착 없음(자동 완성 3.5초에 끝남). PostgreSQL은 그대로 교착(고치지 않음).
- 현재 스키마(Oracle 1521·1522, 연결 설정의 기본 스키마 없음·`PDBADMIN`·따옴표 소문자 `"chr_cil_lower"`, 시험 스키마 `CHR_CIL_*`): 연결, 쿼리 창의 새 세션,
  Invalidate/Reconnect, Reconnect, "기본값으로 설정" 뒤 새 쿼리 창, 쿼리 창의 `ALTER SESSION SET CURRENT_SCHEMA` 뒤 Invalidate/Reconnect에서 모든 세션의
  `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')`, 쿼리 창 스키마 선택·상태 줄, 탐색기의 굵은 기본 스키마가 고치기 전과 같다.
- 문장 수(쿼리 관리자): 기본 스키마 설정이 있는 연결은 연결할 때 10 → 9(ALL_USERS 읽기가 첫 쿼리 창을 열 때로 옮겨 감, 새 세션 2 → 3, 합은 같음), 그 밖의
  연결·새 세션·Invalidate/Reconnect·Reconnect는 같다. 연결할 때 `SYS_CONTEXT` 읽기가 두 세션 모두에서 도는 것은 서버의 `V$SQL` 실행 수(+2)로도 확인했다.
- SQL Server: 쿼리 창이 `USE tempdb` 한 뒤 Invalidate/Reconnect(목록 있음·없음 두 번) → 창 세션 tempdb·dbo, 주·메타데이터 세션 master·dbo(전과 같음).

## 취소는 오류가 아님 (2026-11-22, 앱 변경 #167)

### 재현 (고치기 전 빌드, 시험 VM, 스크래치 작업 공간, 시험 개체 `CHR_CNE_*`)

위 "고치기 전 측정"과 같은 방법(메타데이터 문맥을 서버 쪽 대기로 붙잡고 진행 창의 취소를 3초 뒤 누름)으로 취소를 따로 잡지 않는 호출을 눌렀다.

| 호출 | 위치 | DB | 고치기 전 | 고친 뒤 |
|---|---|---|---|---|
| 테이블의 "사용 권한" | `SecurityCommands.openObjectPermissions` (`SecurityCommands.java:178`) | Oracle, PostgreSQL, MariaDB (SQL Server는 고친 뒤만 재현) | 1.6초 뒤 오류 창 "사용 권한": "chr_cne_t1을(를) 읽지 못했습니다." + 영어 "Canceled while the database call was still running" | 1.6초 뒤 창이 닫히고 아무 창 없음, 다시 누르면 정상으로 열림 |
| 권한 대상 고르기("사용자 또는 역할 추가") | `ObjectPermissionsDialog.addGrantees` (`ObjectPermissionsDialog.java:126`) | Oracle, SQL Server, PostgreSQL | 오류 창 "사용자 또는 역할 추가": 영어 문구 한 줄뿐 | 아무 창 없음, 권한 대화상자는 그대로 쓸 수 있음 |
| 로그인·사용자 "속성" | `SecurityCommands.openProperties` (`SecurityCommands.java:121`) | Oracle, SQL Server, MariaDB | 오류 창 "속성": "SYSTEM을(를) 읽지 못했습니다." + 영어 문구 | 아무 창 없음 |
| 바쁘지 않은 연결에서 자기 문장이 잠금에 막힌 "사용 권한"(다른 세션이 `ALTER TABLE`로 Sch-M 잠금을 쥠) | 같음 | SQL Server | 0.1초 뒤 오류 창: "CHR_CNE_T1을(를) 읽지 못했습니다. SQL 오류 [HY008]: 쿼리가 취소되었습니다." | 아무 창 없음(드라이버의 취소를 취소로 받음) |
| "DDL 보기"(이미 취소를 잡는 호출, 견본) | `ObjectExplorerPanel.generate` | PostgreSQL(바쁜 연결, 뷰 잠금) | 아무 창 없음 | 같음 |

`SchemaComparePanel`은 자기 `showError`(`SchemaComparePanel.java:716`)가 이미 `DBInterruptedException`을 건너뛴다(실행해 보지 않음, 코드로 확인).

### 설계

- 판단 하나: `ErrorText.isUserCancel`(감싸개를 벗긴 예외가 `DBInterruptedException`, `InterruptedException`, `ClosedByInterruptException`,
  `OperationCanceledException`, `CancellationException`). 이것을 `SwingPlatformUI.showError`(예외·상태)와 `ErrorDialog.show`가 쓴다. 규칙과 받지 않는 것은
  [swing-frontend.md](swing-frontend.md) "사용자의 취소는 오류가 아님".
- 드라이버의 취소 오류는 예외만으로는 문장 시간 초과·다른 세션의 취소와 구별되지 않는다(PostgreSQL 57014, SQL Server HY008). 그래서 취소를 누른 것을 아는
  `ProgressDialog.runTask`만 그것을 `DBInterruptedException`으로 바꾼다(연결의 오류 도우미가 `EXECUTION_CANCELED`로 볼 때만, 문구를 맞춰 보지 않음).
- 제 종류가 있는 감싸개(`TableDesignSession.PartialSaveException`, `SecurityDialect.ExecutionException`, `ObjectMaintenanceActions.ExecutionException`)는 바꾸지도
  숨기지도 않는다: 쓰기 중 취소에서 무엇이 적용됐는지 알린다. 쓰기의 취소는 #161대로 일찍 풀려나지 않는다.
- 고른 것과 대안: 호출 129곳을 하나씩 고치는 대신 오류 창 두 입구와 `runTask` 하나에서 정했다(호출마다 고치면 새 호출이 또 빠뜨림). 대안이었던 "`DBInterruptedException`만
  건너뛰기"는 드라이버의 취소(위 SQL Server 경우)를 남기고, "드라이버 취소 코드도 오류 창에서 건너뛰기"는 문장 시간 초과를 숨긴다.

### `ProgressDialog.runTask` 호출 129곳의 분류 (2026-11-22 코드 검색, `com.seizia.chromite.ui.swing`)

| 분류 | 수 | 취소 뒤 |
|---|---|---|
| 이미 취소를 따로 잡음(`catch (DBInterruptedException)`, `instanceof`, `SchemaComparePanel`의 helper) | 67 | 아무것도 보이지 않음. 이제 드라이버의 취소도 이리로 옴 |
| `DBWorkbench.getPlatformUI().showError`(또는 그것을 부르는 helper)로 보임 | 28 | 고친 공용 판단으로 창 없음. 이 중 권한(`ObjectPermissionsDialog` 1, `SecurityCommands` 5, `PrincipalPropertiesDialog` 3), 연결 시험·암호 바꾸기, 설정·연결 옮기기, LOB 읽기·저장, 드라이버 내려받기, SQL Server 백업·복원 읽기, 디자이너 버리기 |
| `ErrorDialog.show`로 직접 보임 | 12 | 고친 `ErrorDialog.show`로 창 없음(`CommitModeSwitch`, `DataTransferWizard` 작업 저장, `ExitTransactionCheck` 2, `NativeBackupRestoreDialog` 2, `ProcedureDebuggerPanel` 3, `SqlServerRestoreDialog` 3). 오류 뒤 상태 정리(디버거 상태, 종료 취소)는 창과 상관없이 그대로 |
| `JOptionPane`에 `e.getMessage()`·`ErrorText.describe`를 직접 넣음 | 6 | **남은 곳**(아래) |
| 화면 안의 글(미리 보기 영역, 문제 글)로 보임 | 4 | **남은 곳**(아래) |
| 디버그 로그만, 또는 조용히 넘어감 | 11 | 바뀌지 않음 |
| 모델 쪽으로 던짐(`SwingPlatformUI.runWithProgress`·`runWithMonitor`) | 1 | 모델 호출의 오류가 `showError`로 오면 공용 판단을 거침 |

`runTask` 밖에서 오류를 화면 안 상태 줄에 보이는 helper(`SchedulerTablePanel.showError` :257, `ServerLogPanel.showError` :496, `ConnectionDatabaseList.showError` :289)는
진행 창이 없어 사용자가 취소를 누를 곳이 없다. `ConnectionDatabaseList`는 암호 입력 취소를 이미 따로 처리한다(:233). `ServerLogPanel`의 암호 입력 취소는 아래
"남은 곳"의 결과를 본다.

### 남은 곳 (2026-11-22 앱 변경 #168에서 다시 보고 고침)

앞 작업이 코드 검색으로만 찾은 12곳을 다시 읽고, 예외가 사용자의 취소일 수 있는지 모델 코드까지 따라갔다. 취소가 실제로 오는 곳만 `ErrorText.isUserCancel`
하나로 고쳤다: 상자는 띄우지 않고, 화면 안의 글은 기존 중립 문구 "작업을 취소했습니다." / "The operation was canceled."다. 이 문구의 키는 백업·복원 묶음
(`BackupRestoreMessages.task_message_canceled`)에서 오류 문구 묶음(`ErrorMessages.error_canceled`)으로 옮겼다(DDL 미리 보기도 쓰는 일반 문구, 쓰던 곳
`DatabaseTaskDialog` 한 곳도 함께). 새 문구·새 판단·새 helper는 없다.

| 곳 | 고치기 전 | 고친 뒤 | 확인 |
|---|---|---|---|
| `NativeBackupRestoreDialog.loadObjects` (:506 PostgreSQL 스키마, :516 MySQL·MariaDB 테이블; 상자 :527) | 경고 상자 "개체 목록을 읽지 못했습니다: Canceled while the database call was still running" | 상자 없음, 백업 대화상자가 바로 열리고 개체 목록은 비어 전체 백업(실제 오류 때와 같음) | 실행(MariaDB, 바쁜 메타데이터 연결, 3초 뒤 취소, 1.6초 뒤 진행 창 닫힘) |
| `DataImportWizard` DDL 미리 보기 (:781, 글 :786) | "DDL을 만들 수 없습니다: Canceled …" | 중립 문구가 이전 DDL을 대신함, 탭을 다시 고르면 다시 만듦 | 코드만: 아래 복사와 같은 `DatabaseCopyMappings.generateTableDdl` |
| `DatabaseCopyPages` DDL 미리 보기 (:1249, 글 :1254) | 같음 | 같음 | 코드만(MariaDB→MariaDB 복사에서 바쁜 연결·새로 고침 뒤에도 진행 창이 뜨지 않음, 취소할 수 없음) |

제외한 곳(취소 예외가 오지 않음, 고치지 않음):

| 곳 | 이유 (근거) |
|---|---|
| `DriverBundleCreateDialog` :260, `DriverBundleInstallDialog` :170·:193 | 모델이 취소를 `InterruptedException`으로만 알리고(`DriverOfflineBundleWriter.checkCanceled`, `DriverOfflineBundleInstaller.checkCanceled`, `WebUtils.downloadRemoteFile`) 작업이 그것을 잡아 `null`을 돌려준다. `ProgressDialog.runTask`가 취소로 바꾸는 두 경우(메타데이터 세션 안, 드라이버의 `SQLException`)는 이 작업에 없다. 작업 스레드는 인터럽트되지 않아(`AbstractJob.canceling`) `ClosedByInterruptException`도 없다 |
| `DriverLibrariesPanel.findClasses` :405 | `DriverClassFindJob`은 취소면 멈추기만 하고 던지지 않는다(:86, :106) |
| `DriverLibraryDetailsDialog.resolve` :164 | 의존성 해석(`DriverDependencies.resolveDependencies`, Maven POM 읽기)은 모니터의 취소를 보지 않고 `IOException`만 감싼다 |
| `ConnectionSettingsPanel.loadDriverProperties` :1655 | 드라이버 속성 읽기는 DB 세션을 열지 않고(`JDBCDataSourceProvider.getConnectionProperties`), 드라이버 파일 내려받기의 취소는 내려받기 쪽(`SwingDriversService`)에서 끝난다 |
| `ServerLogPanel` :285·:449 | 진행 창이 없다. 연결하며 뜬 암호 입력을 닫으면 `AdminConnection.execute`가 "'X'에 연결되어 있지 않습니다."(`activity_not_connected`)를 던진다. 취소 예외가 아니라서 이 판단으로는 바뀌지 않는다(실행: 고치기 전·후 모두 상태 줄 "오류: 'MA'에 연결되어 있지 않습니다.") |

다시 찾은 결과(이 번들에서 예외 글을 오류 창 밖에 보이는 130곳): 위 목록 밖에서 취소가 올 수 있는 곳은 이미 취소를 따로 처리한다(`OracleJobDialog` :250,
`TaskScheduleDialog` :164, `ExecutionTracePanel` :315, `DataSearchPanel` :365, `PropertiesPanel` :290, `ObjectDetailsPanel`·`SchemaComparePanel` DDL의 일련번호,
`MaintenanceDialect`의 `CANCELED`). `ConnectionTransferCommands`·`SettingsTransferCommands`의 `showReadProblem`은 파일 내용 오류만 상자로, 나머지는 `showError`로 보낸다.

`#345`로 쓸모없어진 취소 검사 7개를 지웠다. 오류가 그대로 `showError`·`ErrorDialog.show`로 가고 그 분기의 다른 일(`ExecutionTracePanel`의 열린 추적 세션 정리,
`ProcedureDebuggerPanel`의 컨트롤러 정리, `ExplorerObjectCommands`의 남은 변경 버리기)은 검사 밖에서 늘 했으므로 동작이 같다: `SchemaComparePanel.showError`,
`TableDesignerPanel.addKey`·`addIndex`·`addForeignKey`, `ExecutionTracePanel.run`, `ProcedureDebuggerPanel.start`, `ExplorerObjectCommands.runChange`.
`catch (DBInterruptedException)`로 따로 잡고 다른 일을 하는 곳(`TableDesignerPanel.run`의 다시 읽기, `generateScript`의 `null` 등)은 그대로 두었다.

바뀌지 않음(확인): 백업 개체 읽기의 실제 오류(바쁜 연결 뒤에서 기다리는 동안 그 메타데이터 세션을 `KILL`) → 전처럼 경고 상자 "개체 목록을 읽지 못했습니다:
SQL 오류 [1220] [08000]: … Connection is closed". DDL 미리 보기의 정상 DDL은 같다. 드라이버 라이브러리·서버 로그 화면은 코드를 바꾸지 않았다.

- 남은 것: 연결하며 뜬 암호 입력을 닫은 뒤의 "연결되어 있지 않습니다" 글은 관리 도구 탭(`AdminConnection`을 쓰는 10개 화면)과 개체 찾기·스키마 비교·실행 계획
  등이 같은 방식으로 보인다. 고치려면 연결 서비스가 "취소됨"을 호출한 쪽에 알려야 한다(`DBUtils.initDataSource`는 참·거짓만 돌려줌). 이번 범위 밖.
- 남은 것: 드라이버 파일 내려받기·Maven 의존성 읽기는 취소를 누른 뒤에도 네트워크 읽기 시간 초과(10초)까지 기다리고, 그동안 실패하면 오류를 보인다(취소 예외가 아님).
- 드라이버의 취소 오류 중 분류(`EXECUTION_CANCELED`)가 없는 것: Oracle ORA-01013(`OracleDataSource`), MariaDB·MySQL 1317(`MySQLDataSource`), Altibase.
  이 DB에서 바쁘지 않은 연결의 자기 문장을 취소하면 전처럼 드라이버 오류가 오류 창에 보인다(재현 못 함: Oracle 사전 읽기는 잠금에 막히지 않음).

### 바뀌지 않음 (확인)

| 경우 | 고치기 전 | 고친 뒤 |
|---|---|---|
| 문장 시간 초과(연결의 `options=-c statement_timeout=3000`, 잠긴 뷰의 DDL 보기, 취소 누르지 않음) | 오류 창 "스크립트를 생성할 수 없습니다. … canceling statement due to statement timeout 상태 57014" | 같은 글 |
| 다른 세션이 끊은 세션(`pg_terminate_backend`로 DDL 보기의 메타데이터 백엔드를 끊음) | 오류 창 "… FATAL: terminating connection due to administrator command 백엔드로 전송하는 동안 I/O 오류가 발생했습니다. 상태 57P01" | 같은 글 |
| 권한 없음(`SET ROLE chr_cne_r`인 연결에서 테이블 삭제 실행) | 오류 창 "chr_cne.chr_cne_t1을(를) 삭제하지 못했습니다. SQL 오류 [42501]: ERROR: must be owner of table chr_cne_t1" | 같은 글(테이블 그대로) |
| 쿼리 창 문법 오류(PostgreSQL `SELEC 1`, Oracle 없는 함수) | 메시지 탭 `42601` / `ORA-00904`, 창 없음 | 같음 |
| 쿼리 창 중지(Oracle, SQL Server, PostgreSQL, MariaDB, Altibase 7.3) | 4.9초 / 0.25초 / 0.25초 / 0.25초 / 0.76초 뒤 "사용자가 쿼리를 취소했습니다.", 창 없음 | 4.8초 / 0.13초 / 0.26초 / 0.26초 / 0.76초, 같은 글, 창 없음 |
| 연결 새로 고침 뒤, 다시 연결 뒤의 "사용 권한" 취소(PostgreSQL) | (고치기 전 측정 안 함) | 창 없음 |

## upstream 대비 변경점

- upstream `ProgressMonitorDialog`(SWT)와 `AbstractJob`은 취소가 듣지 않으면 일정 시간 뒤 바깥 블록(세션)까지 취소했다. Chromite는 2026-11-08에 이를 지웠고
  ([swing-options.md](swing-options.md) "Errors and Timeouts"), 이번에는 기다리는 세션의 취소가 다른 문장에 닿지 않게 하고 진행 창이 메타데이터 읽기를 놓아 준다.

## 변경 이력

- 2026-11-22 최초 작성(앱 변경 #161): 고치기 전 측정, 설계와 대안, 고친 뒤 확인(다섯 DB, 함께 쓰는 세션, 응답하지 않는 연결, 쿼리 창 중지). 회귀 테스트는
  실행하지 않았다(영향받을 수 있는 기능: 모든 진행 창 작업의 취소, 모든 JDBC 문장·세션의 취소, 쿼리 창 중지).
- 2026-11-22 "취소는 오류가 아님"(앱 변경 #167, `chromite/cancel-not-an-error`): 사용자의 취소는 오류 창을 열지 않음, 취소를 누른 뒤의 드라이버 취소 오류는
  `runTask`가 취소로 돌려줌, 호출 129곳의 분류와 남은 곳. "남은 것"의 첫 항목을 고침. 회귀 테스트는 실행하지 않았다(영향받을 수 있는 기능: 모든 오류 창,
  진행 창 작업의 취소).
- 2026-11-22 "세션을 다시 열 때의 교착"(앱 변경 #165): Oracle·YashanDB·SQL Server가 다시 열거나 새로 여는 세션의 기본 스키마를 이름으로 받고 알림은
  메모리의 목록에서만. 세 DB 재현, PostgreSQL은 남김. 회귀 테스트는 실행하지 않았다(영향받을 수 있는 기능: 연결·Invalidate/Reconnect·쿼리 창의 새
  세션의 현재 스키마, 탐색기의 기본값 굵게 표시, 쿼리 창의 스키마 선택).
