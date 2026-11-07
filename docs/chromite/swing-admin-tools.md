# Swing 관리 도구 (Activity Monitor, Disk Usage, Server Logs, Maintenance, Replication, Find Objects)

## 목적

DBeaver(SWT)의 서버 관리 도구를 Swing 프런트엔드로 옮긴다. 배치는 SSMS를 따른다.

- **Activity Monitor**: 서버 세션 목록, 세션의 현재 SQL과 속성, 세션·쿼리 끝내기, 자동 새로 고침, 필터.
  같은 문서 탭의 **Locks** 탭은 잠금을 기다리는 세션과 막는 세션(blocking chain), 각 세션의 잠금을 보인다.
- **Find Objects**: 여러 연결에서 이름으로 테이블·뷰·프로시저·열을 찾고, 결과를 Object Explorer에서 고른다.
- **서버 대시보드**: 처음에는 미뤘고(아래 "미룬 것"), 뒤에 Activity Monitor의 첫 탭(개요)으로 옮겼다([swing-dashboard.md](swing-dashboard.md)).
- **Disk Usage**(공간 사용량, DB 관리 축 3): 데이터베이스·파일·테이블스페이스 크기와 큰 테이블. Activity Monitor의 탭이다(아래 "공간 사용량").
- **Server Logs**(서버 로그, DB 관리 축 5): 오류 로그·경고 로그·느린 쿼리 로그를 최신 항목부터 쪽 단위로. Activity Monitor의 탭이다(아래 "서버 로그").
- **Maintenance**(유지 관리, DB 관리 축 4): VACUUM·ANALYZE·REINDEX, ANALYZE/OPTIMIZE/CHECK/REPAIR TABLE, DBMS_STATS·다시 컴파일, 인덱스 다시 작성·통계·CHECKDB를
  미리 보기와 확인을 거쳐 실행하는 대화상자와, 후보·상태 목록을 보이는 Activity Monitor의 탭(아래 "유지 관리").
- **Replication**(복제 상태, DB 관리 축 6): 대기 서버·슬롯·복제본·Data Guard·가용성 그룹 상태, 읽기 전용. Activity Monitor의 탭이다(아래 "복제 상태").

## 여는 곳

| 명령 | 위치 | 동작 |
|---|---|---|
| Activity Monitor | Tools 메뉴, Object Explorer 오른쪽 클릭 메뉴 | 선택한 노드의 연결(Tools 메뉴는 없으면 활성 편집기의 연결)의 Activity Monitor를 Processes 탭으로 연다. 이미 열려 있으면 그 탭을 보인다. |
| Locks | 위와 같음 | 같은 Activity Monitor를 Locks 탭으로 연다. |
| Disk Usage | 위와 같음 | 같은 Activity Monitor를 Disk Usage 탭으로 연다(`AdminViewCommands`). |
| Server Logs | 위와 같음 | 같은 Activity Monitor를 Server Logs 탭으로 연다(`AdminViewCommands`). |
| Maintenance | 위와 같음 | 같은 Activity Monitor를 Maintenance 탭으로 연다(`AdminViewCommands`). |
| Replication | 위와 같음 | 같은 Activity Monitor를 Replication 탭으로 연다(`AdminViewCommands`). |
| Maintenance… | Object Explorer 오른쪽 클릭 메뉴(테이블·인덱스·스키마·데이터베이스) | 그 노드의 유지 관리 대화상자를 연다(`MaintenanceCommands`). |
| Find Objects… | 위와 같음 | Find Objects 탭을 연다. 선택한 노드의 연결에 체크하고, 노드가 스키마·데이터베이스 아래면 그 컨테이너를 범위로 제안한다. |

화면에 보이는 글자에는 다른 제품 이름을 쓰지 않는다(2026-11-06 사용자 규칙). "Activity Monitor"는 일반 명칭으로 쓴다.

## 구조

```
com.seizia.chromite.ui.swing (Swing, SWT 없음)
  MainFrame ── Tools 메뉴, 도구 문서 탭(ToolDocument) 열기·닫기
  ObjectExplorerPanel ── 오른쪽 클릭 메뉴, revealNode(노드까지 펼쳐 고르기)
  ActivityMonitorPanel (ToolDocument)
    ├ SessionManagerPanel ── Processes 탭
    ├ LockManagerPanel ───── Locks 탭
    ├ DiskUsagePanel ─────── Disk Usage 탭 (표: AdminGridTable)
    ├ ServerLogPanel ─────── Server Logs 탭 (표: AdminGridTable)
    ├ MaintenancePanel ───── Maintenance 탭 (하위 탭: AdminGridTabs) ── MaintenanceDialog 열기
    ├ ReplicationPanel ───── Replication 탭 (하위 탭: AdminGridTabs, 자동 새로 고침)
    └ AdminConnection ────── 탭들이 함께 쓰는 서버 연결(격리된 실행 컨텍스트 하나)
  AdminViewCommands ── Disk Usage·Server Logs·Maintenance·Replication 메뉴 항목(Tools, Object Explorer)
  MaintenanceDialog ── 유지 관리 대화상자(작업·옵션, 상태, 권한 안내, 경고, 미리 보기, 확인, 진행·취소, 결과)
  MaintenanceCommands ─ Object Explorer의 Maintenance… 명령
  AdminGridTabs ────── AdminGrid마다 하위 탭(설명, 표 또는 빈 표·읽을 수 없음 문구, 행 상세)
  AdminGridTable ───── 열 종류(크기·개수·백분율·글자)로 그리고 정렬·복사하는 표
  ObjectSearchPanel (ToolDocument) ── Find Objects 탭
  ObjectListTable ── @Property 열로 모델 객체 목록을 보이는 표
  AutoRefreshBox ─── 자동 새로 고침 간격 콤보
  AccessNoticeBar ── Processes·Locks·Disk Usage·Server Logs·Maintenance·Replication 탭 위의 권한 안내 줄(유지 관리 대화상자의 거부·경고 줄)
        │
        ▼
com.seizia.chromite.model.admin (새 모델 번들, UI 없음)
  ServerAdminProfile ── DB별 세션 관리자·필터·명령·잠금 관리자
  ServerAdminMessages ─ 필터·명령 이름(NLS)
  ServerAdminAccess, ActivityAccessMessages ─ 로그인 권한 확인 결과와 안내 문구(NLS)
  AbstractBlockingLockManager, BlockingSessionLock, SessionLockItem
  mssql/SQLServerLockManager, mysql/MySQLLockManager
  postgresql/PostgreActivitySessionManager(+PostgreActivitySession), postgresql/PostgreBlockingLockManager
  altibase/AltibaseBlockingLockManager
  oracle/OracleActivitySessionManager(+OracleActivitySession), oracle/OracleBlockingLockManager(+OracleBlockingLock)
  AdminSql ── 시스템 뷰 읽기 도우미(공간 사용량·서버 로그)
  space/ServerSpaceDialect(+SpaceGrid, SpaceColumn, SpaceReport, SpaceFormat, SpaceMessages)
    └ mssql/SQLServerSpaceDialect, postgresql/PostgreSpaceDialect, mysql/MySQLSpaceDialect, oracle/OracleSpaceDialect
  logs/ServerLogDialect(+ServerLogSource, ServerLogQuery, ServerLogEntry, ServerLogPage, LogMessages)
    └ mssql/SQLServerLogDialect, postgresql/PostgreLogDialect, mysql/MySQLLogDialect, oracle/OracleLogDialect
  AdminGrid ── 유지 관리·복제 표(문자열 ID, 행 값·상세, 빈 표·읽을 수 없음 문구, 설명; 열은 SpaceColumn)
  maintenance/MaintenanceDialect(+Target, Operation, Option, Plan, Command, Result, Report, Action, Runner, MaintenanceMessages)
    └ mssql/SQLServerMaintenanceDialect, postgresql/PostgreMaintenanceDialect, mysql/MySQLMaintenanceDialect, oracle/OracleMaintenanceDialect
  replication/ReplicationDialect(+ReplicationReport, ReplicationMessages)
    └ mssql/SQLServerReplicationDialect, postgresql/PostgreReplicationDialect, mysql/MySQLReplicationDialect, oracle/OracleReplicationDialect
        │
        ▼
DBeaver 모델 (그대로 재사용)
  DBAServerSessionManager(+SQL, +DetailsProvider), DBAServerLockManager, LockGraphManager,
  PostgreSessionManager, SQLServerSessionManager, MySQLSessionManager, OracleServerSessionManager,
  PostgreLockManager, OracleLockManager(PostgreSQL 계열 다른 서버·9.6 이전, Oracle 10g 이전만), DBSStructureAssistant, DBNModel
```

### 새 모델 번들을 만든 이유 (`com.seizia.chromite.model.admin`)

- SWT에서는 DB별 차이(필터 단추, 끝내기 명령과 옵션 키, 잠금 상세의 세션 키)가 DB별 **UI** 플러그인
  (`ext.postgresql.ui`의 `PostgreSessionEditor` 등)에 있다. Swing 번들은 SWT UI 플러그인을 쓸 수 없다.
- 옵션 키는 모델 클래스의 상수(`PostgreSessionManager.OPTION_SHOW_IDLE` 등)라서 그 차이를 모으는 코드는 `ext.*` 모델 번들에 의존한다.
  Swing UI 번들이 `ext.*`에 직접 의존하지 않도록 차이를 이 모델 번들(`ServerAdminProfile`)에 모았다.
- DBeaver에는 SQL Server와 MySQL/MariaDB 잠금 관리자가 없다. 새로 만든 관리자를 DBeaver 번들(`ext.mssql`, `ext.mysql`)에 넣지 않고
  이 번들에 두어 upstream 파일을 고치지 않는다(패키지 접두어 `com.seizia.chromite`).
- 번들은 `plugins/pom.xml`의 desktop 프로필(Swing 번들 옆)과 `com.seizia.chromite.swing.feature`에만 들어간다. SWT 제품에는 들어가지 않는다.

## Activity Monitor — Processes (세션)

SWT `SessionManagerViewer`(`plugins/com.seizia.chromite.ui.editors.session/.../SessionManagerViewer.java:111-239` 화면 구성,
`:376-383` 새로 고침과 끝내기, `:512-568` 검색)와 `SessionTable`(`.../SessionTable.java:131-209` 읽기·끝내기 서비스)을 옮겼다.

| 항목 | 동작 | SWT 대응 |
|---|---|---|
| 세션 표 | `DBAServerSessionManager#getSessions` 결과. 열은 세션 클래스의 `@Property(viewable)` | `SessionTable`(`ObjectListControl`) |
| 새로 고침 | Refresh 단추, 자동 새로 고침 간격(꺼짐, 2·5·10·30·60초, 기본 10초) | `AutoRefreshControl` |
| 필터 | DB별 체크 상자(아래 표). 바꾸면 서버에서 다시 읽는다 | DB별 편집기의 체크 단추 |
| Only my sessions | 세션의 사용자 = 연결 설정의 사용자인 행만(대소문자 무시) | 없음(Chromite 추가) |
| 찾기 칸 | 어느 열에든 글자가 있는 행만 | `SessionSearcher` |
| 끝내기 명령 | DB별 단추(아래 표), 확인 후 `alterSession`, 끝나면 다시 읽기 | DB별 `KillSessionAction` 등 |
| Open Session Query | 세션 읽기 SQL을 새 쿼리 창에서 실행 | "Open SQL editor" (`DBAServerSessionManagerSQL`) |
| Current SQL 탭 | 고른 세션의 `getActiveQuery()`(읽기 전용 SQL 편집기) | SQL 미리 보기 |
| Details 탭 | 세션의 모든 속성(`PropertyCollector`) | `PropertyTreeViewer` |
| 추가 세부 정보 탭 | `DBAServerSessionDetailsProvider`의 항목(예: Oracle long operations, 실행 계획 글) | 같은 탭 |

자동 새로 고침 중에도 고른 세션(세션 ID로 비교)과 열 너비·정렬을 유지한다(SWT `SessionLoadVisualizer`와 같음).
타이머는 탭이 보일 때만 읽고, 앞선 읽기가 끝나지 않았거나 연결이 끊겨 있으면 그 차례를 건너뛴다
(연결에 실패한 뒤 간격마다 비밀번호를 다시 묻지 않도록. 다시 연결하려면 Refresh를 누른다).
필터 체크 상자는 명령 단추 줄이 넘치지 않도록 도구 막대 아래 둘째 줄에 둔다.
찾기 칸과 Only my sessions는 패널과 함께 만들지만 DB에 맞춘 도구가 생기는 첫 읽기 뒤에야 붙인다. 테마 전환(`FlatLaf.updateUI`)은 창에 붙은
컴포넌트만 바꾸므로, Locks 탭으로 열고 테마를 바꾼 뒤 Processes 탭을 열면 이 둘이 이전 테마 색으로 남았다. 붙일 때 현재 Look and Feel로
다시 설치한다(`SwingUtilities.updateComponentTreeUI`). 다른 관리 도구 문서(Locks, Find Objects)와 테이블 디자이너에는 나중에 붙이는
미리 만든 컴포넌트가 없다.

### DB별 필터와 명령 (`ServerAdminProfile`)

| DB | 세션 관리자 | 필터(기본값) | 명령 | 사용자 속성 |
|---|---|---|---|---|
| PostgreSQL | `PostgreActivitySessionManager`(9.6+, 서버 종류 PostgreSQL), 그 밖은 데이터 소스 어댑터(`PostgreSessionManager`) | Show idle connections(켜짐) | Terminate Session(`pg_terminate_backend`), Cancel Query(`OPTION_QUERY_CANCEL`, `pg_cancel_backend`) | `usename` |
| SQL Server | `SQLServerSessionManager` | Only connections(켜짐) | Terminate Session(`KILL`) | `login_name` |
| MySQL·MariaDB | `MySQLSessionManager` | Hide sleeping(꺼짐), Show performance info(꺼짐, sys 스키마가 있을 때) | Kill Session(`KILL CONNECTION`), Kill Query(`KILL QUERY`, 쿼리가 있는 세션만) | `User` |
| Oracle | `OracleActivitySessionManager`(10g+), 그 전은 `OracleServerSessionManager` | Show background, Show inactive(꺼짐) | Kill Session, Disconnect Session(확인 창의 Immediate 체크) | `USERNAME` |
| 그 밖 | 데이터 소스 어댑터 | 없음 | Terminate Session(관리자의 `getTerminateOptions()`) | 없음 |

필터 값은 드라이버마다 이 번들의 설정 저장소(`activityMonitor.sessions.filter.<드라이버>.<옵션>`)에, 자동 새로 고침 간격은
`activityMonitor.sessions.refreshSeconds` / `activityMonitor.locks.refreshSeconds`에 둔다.

### 서버 연결 (`AdminConnection`)

SWT는 읽을 때마다 격리된 실행 컨텍스트를 열고 닫는다(`SessionTable.java:147`). 자동 새로 고침마다 서버 연결을 새로 만들면 비싸고
세션 목록에 매번 새 세션이 보이므로, Activity Monitor 탭 하나가 격리된 컨텍스트 하나를 열어 두고 탭을 닫을 때 닫는다.
연결 설정이 "단일 연결"이면 SWT처럼 기본 컨텍스트를 쓴다. 작업은 차례로 실행하고(같은 JDBC 연결), 오류가 나면 컨텍스트를 닫아
다음 작업에서 다시 연다(자기 세션을 끝낸 경우 등). 연결되지 않은 연결은 첫 작업에서 연결한다(SQL 편집기와 같은 `DBUtils.initDataSource`).

## Activity Monitor — Locks (잠금)

SWT `LockManagerViewer`(`plugins/com.seizia.chromite.ui.editors.locks/.../LockManagerViewer.java`: 잠금 표, 그래프, 대기·보유 상세 표,
"Kill waiting session")와 `PostgreLockEditor`/`OracleLockEditor`(상세 표 옵션)를 옮겼다.

- **Blocking chains(나무)**: GEF 그래프 대신 나무다. 뿌리는 남을 막기만 하는 세션, 자식은 그 세션을 기다리는 세션이다.
  관계는 잠금 관리자가 `LockGraphManager#buildGraphs`로 채운 `getHoldBy()`/`waitThis()`를 그대로 쓴다.
- **잠금 표**: `DBAServerLockManager#getLocks`의 행(열은 잠금 클래스의 `@Property`). 나무와 표의 선택은 서로 따라간다.
- **상세 표 둘**: 고른 세션과 그 세션을 막는 세션의 잠금(`getLockItems`, 옵션은 `ServerAdminProfile#getLockItemOptions`).
- **Kill Session**: 고른 행의 세션을 확인 후 끝낸다(`alterSession`).

| DB | 잠금 관리자 | 대기 목록 | 세션의 잠금 |
|---|---|---|---|
| PostgreSQL | `PostgreBlockingLockManager`(새로 만듦, 9.6+), 그 밖은 `PostgreLockManager`(DBeaver) | `pg_blocking_pids` | `pg_locks`(relation은 `regclass`로 이름) |
| Oracle | `OracleBlockingLockManager`(새로 만듦, 10g+), 그 전은 `OracleLockManager`(DBeaver) | `V$SESSION.BLOCKING_SESSION` + 요청 중인 `V$LOCK` | `V$LOCK`(TM은 ID1, 대기 중인 TX는 `ROW_WAIT_OBJ#`을 `DBA_OBJECTS`로 이름) |
| SQL Server | `SQLServerLockManager`(새로 만듦) | `sys.dm_exec_requests.blocking_session_id` | `sys.dm_tran_locks`(OBJECT 잠금은 객체 이름) |
| MySQL·MariaDB | `MySQLLockManager`(새로 만듦) | `sys.innodb_lock_waits`(sys 스키마 없으면 `INNODB_LOCK_WAITS`) | MariaDB·MySQL 5.7: `INNODB_LOCKS`, MySQL 8: `performance_schema.data_locks` |
| Altibase | `AltibaseBlockingLockManager`(새로 만듦, 2026-11-08, 아래 "Altibase 잠금") | `V$LOCK_WAIT` + `V$SESSION.TRANS_ID` | `V$LOCK`(테이블은 `SYSTEM_.SYS_TABLES_`, 분할은 `SYS_TABLE_PARTITIONS_`로 이름) |

새 관리자는 `AbstractBlockingLockManager`가 공통 부분(뿌리 행 더하기, 그래프 만들기, 상세 옵션)을 맡고 하위 클래스는 질의만 한다.
행 모양(`BlockingSessionLock`: 세션, 사용자, Blocked By, 대기 자원·모드·시간, 문장)은 `PostgreLock`과 같은 방식이고,
열 이름은 `OSGI-INF/l10n/bundle.properties`의 `meta.*` 키다(DBeaver 모델 규칙).
MySQL/MariaDB는 InnoDB 행 잠금만 보인다. 메타데이터 잠금(ALTER TABLE 대기 등)은 세션 목록의 State에서 본다.

## PostgreSQL·Oracle 세션과 잠금 (2026-11-07)

DB 관리 기능 축 2(세션·잠금): PostgreSQL과 Oracle을 SQL Server 수준으로 맞췄다. 메뉴(Tools 메뉴, Object Explorer 오른쪽 클릭의
Activity Monitor·Locks)는 원래부터 연결된 모든 DB에서 켜져 있었다(`ObjectExplorerPanel#updateActions`의 `activityMonitorAction.setEnabled(dataSource != null)`,
`MainFrame#updateActivityMonitorAction`). 그래서 메뉴 코드는 고치지 않았다.

### DB별 지원 (전 → 후)

| 항목 | SQL Server | MySQL·MariaDB | PostgreSQL 전 | PostgreSQL 후 | Oracle 전 | Oracle 후 |
|---|---|---|---|---|---|---|
| 세션 목록: SQL, 상태 | 됨 | 됨 | 됨 | 됨 | 됨 | 됨 |
| 대기 | 세션 목록 Status, 잠금 탭 Wait Mode(wait type) | State | 없음(열 없음) | Wait(`wait_event_type: wait_event`) | Event, Seconds In Wait | 그대로 |
| 막는 세션 | 잠금 탭 Blocked By | 잠금 탭 Blocked By | 없음 | Blocked By(`pg_blocking_pids`) | 없음 | Blocked By(`BLOCKING_SESSION`) |
| 트랜잭션 경과 | — | Trx Latency(Show performance info) | 없음(Xact Start는 숨은 속성) | Transaction Age (s)(`now() - xact_start`) | 없음 | Transaction Age (s)(`GV$TRANSACTION.START_DATE`) |
| 클라이언트·호스트·프로그램 | 됨 | 됨 | App Name만(Client Host는 숨은 속성) | Client Host, App Name | Remote Host, Remote Program | 그대로 |
| 쿼리 취소 / 세션 끝내기 | — / Terminate(KILL) | KILL QUERY / KILL CONNECTION | 있음(결과 확인 안 함) | 있음, 결과가 false면 오류로 알림 | Kill / Disconnect | 그대로(권한 없으면 끔) |
| 확인 창 | 됨 | 됨 | 됨 | 됨 | 됨 | 됨 |
| 잠금: 머리 차단 세션(뿌리) | 됨 | 됨 | 충돌 표로 추정, 행 잠금 대상 객체 없음 | `pg_blocking_pids`, 대상 = 테이블(행 page,tuple) | 뿌리의 "막는 세션"이 0, TM 대기 객체 없음 | `BLOCKING_SESSION`, 대상 = 객체(파일·블록·행) |
| 잠금 상세 | `sys.dm_tran_locks` | INNODB_LOCKS / data_locks | `pg_locks` | `pg_locks`(이름, page·row·xid) | `DBA_LOCK` | `V$LOCK`(모드 이름, 객체, GRANTED/WAITING/BLOCKING) |
| 권한 확인 | — | — | 없음 | pg_monitor·superuser 아니면 자기 세션만 + 안내 | 없음(ORA-00942) | 뷰·ALTER SYSTEM 확인 + 안내, 명령 끔 |

### 설계 결정

- **DBeaver 파일은 고치지 않는다**: `PostgreSession`, `OracleServerSession`, 두 세션 관리자를 이 번들에서 상속했다(SQL Server·MySQL 잠금 관리자와 같은 이유).
  잠금 관리자는 `AbstractBlockingLockManager`를 따라 새로 만들어 네 DB가 같은 행 모양(`BlockingSessionLock`, `SessionLockItem`)을 쓴다.
- **서버가 판단한 대기 관계를 쓴다**: DBeaver `PostgreLockManager`는 `pg_locks`끼리 모드 충돌 표로 짝을 지어, 행 잠금 대기(transactionid)에서 객체가 보이지 않고
  표에 없는 잠금(advisory 등)을 놓친다. `pg_blocking_pids`와 `V$SESSION.BLOCKING_SESSION`은 서버의 잠금 관리자가 내린 판단이다.
- **잠금 대상 → 객체**: PostgreSQL은 기다리는 잠금의 relation, 없으면(transactionid 대기) 기다리는 세션이 잡은 tuple 잠금의 테이블과 행이다.
  Oracle은 요청 중인 잠금이 TM이면 ID1, 아니면 `ROW_WAIT_OBJ#`과 `ROW_WAIT_FILE#/BLOCK#/ROW#`이다.
- **Oracle 세션 끝내기는 'SID,SERIAL#'**: 목록을 읽을 때의 SERIAL#을 잠금 행(`OracleBlockingLock`)에 담아 둔다. 확인 창을 보는 사이 SID가 다른 세션에 다시 쓰여도
  엉뚱한 세션을 끝내지 않는다. 그래서 `AbstractBlockingLockManager#killSession`이 세션 번호 대신 행을 받고, 뿌리 행 만들기(`createRootLock`)를 하위 클래스가 바꿀 수 있게 했다.
  잠금 탭의 Kill Session은 기다리던 세션이 바로 이어 가도록 `IMMEDIATE`다(SWT 잠금 편집기와 같음).
- **적용 범위**: PostgreSQL은 서버 종류가 PostgreSQL(`PostgreServerPostgreSQL` 계열: PostgreSQL, Google Cloud, Timescale)이고 9.6 이상일 때만,
  Oracle은 10g 이상일 때만 새 관리자를 쓴다. Greenplum·Redshift 같은 PostgreSQL 계열 서버와 오래된 버전은 함수·열이 없어 DBeaver 관리자를 그대로 쓴다.
- **ORACLE LICENSE GUARD**: Diagnostics/Tuning Pack이 필요한 ASH·AWR(`V$ACTIVE_SESSION_HISTORY`, `DBA_HIST_*`), SQL Monitor, SQL Tuning Advisor 뷰는 쓰지 않는다.
  쓰는 뷰는 `GV$SESSION`, `GV$SQL`, `GV$SESS_IO`, `GV$TRANSACTION`, `V$SESSION`, `V$LOCK`, `V$SQL`, `DBA_OBJECTS`/`ALL_OBJECTS`, `SESSION_PRIVS`와
  DBeaver 세부 정보 탭의 `GV$SESSION_LONGOPS`, `DBMS_XPLAN.DISPLAY_CURSOR`(팩 라이선스가 필요 없음)뿐이다. 그래서 라이선스 설정은 두지 않았다.
- **잠금은 현재 인스턴스(`V$`)만**: RAC에서 다른 인스턴스의 세션이 막으면 막는 세션 행이 비어 있다. 세션 목록은 `GV$`라 모든 인스턴스가 보인다.

### 권한 확인

연결마다 첫 작업에서 한 번 확인한다(`AdminConnection` → `ServerAdminProfile#checkAccess`). 결과(`ServerAdminAccess`)는 안내 문구,
세션 읽기·잠금 읽기·세션 끝내기 가능 여부다. 안내는 Processes·Locks 탭의 도구 막대 아래 안내 줄(`AccessNoticeBar`, 테마의 경고 색을 옅게 섞은 배경)에 계속 보인다.

| DB | 확인 | 모자랄 때 |
|---|---|---|
| PostgreSQL | `rolsuper OR pg_has_role(.., 'pg_read_all_stats', 'USAGE')`(10+; pg_monitor가 포함), 9.6은 `rolsuper` | 자기 세션만 읽고(`sa.usename = current_user`) 안내한다. 남의 세션은 SQL·상태가 "insufficient privilege"로만 보이기 때문이다. 잠금은 그대로 읽는다. 끝내기는 서버가 판단한다(자기 세션은 됨, 남의 세션은 `pg_signal_backend` 필요) |
| Oracle | 뷰마다 `SELECT 1 FROM <뷰> WHERE 1 = 0`(SELECT_CATALOG_ROLE, SELECT ANY DICTIONARY, 개별 GRANT 어느 쪽이든 실제로 읽히는지), `SESSION_PRIVS`의 ALTER SYSTEM | 못 읽는 뷰 이름과 "SELECT_CATALOG_ROLE을 부여하라"를 안내한다. 세션 뷰를 못 읽으면 세션 목록, 잠금 뷰를 못 읽으면 잠금을 읽지 않고 상태 줄에도 같은 안내를 보인다. `DBA_OBJECTS`만 없으면 `ALL_OBJECTS`로 객체 이름을 찾는다. ALTER SYSTEM이 없으면 Kill/Disconnect 단추를 끈다 |

확인 질의가 실패하면(권한과 상관없는 오류) 모두 허용으로 두고 서버가 거부하는 오류를 그대로 보인다(지금까지와 같음).

## 공간 사용량 (Disk Usage, 2026-11-07)

DB 관리 기능 축 3. 연결마다 데이터베이스 크기, 파일·테이블스페이스·데이터 파일의 크기와 빈 공간, 크기가 큰 테이블(데이터, 인덱스, 합계, 행 수)을 보인다.
DBeaver에는 같은 화면이 없다(SWT의 테이블 속성 "Disk space"가 테이블 하나씩만 보인다). 모델은 `com.seizia.chromite.model.admin.space`
(`ServerSpaceDialect`, `SpaceGrid`, `SpaceColumn`, `SpaceReport`, `SpaceFormat`, `SpaceMessages`)와 DB별 방언(`mssql/SQLServerSpaceDialect`,
`postgresql/PostgreSpaceDialect`, `mysql/MySQLSpaceDialect`, `oracle/OracleSpaceDialect`), 화면은 `DiskUsagePanel`과 `AdminGridTable`이다.

### 둘 곳: Activity Monitor의 탭

별도 문서가 아니라 Activity Monitor 문서의 탭(4번째 Disk Usage, 5번째 Server Logs)으로 둔다.

- 연결마다 하나인 관리 문서가 이미 서버 연결(`AdminConnection`, 격리된 실행 컨텍스트 하나), 첫 작업의 연결·권한 확인, "이 탭으로 열기" 길
  (`MainFrame#openActivityMonitor(container, tab)`, `ObjectExplorerPanel.QueryWindows`)을 가지고 있다. 새 문서 종류를 만들면 MainFrame에
  열기·찾기 코드가 더 들어간다(다른 작업과 충돌하는 파일).
- 메뉴는 `AdminViewCommands`가 만들고, MainFrame(Tools 메뉴)과 ObjectExplorerPanel(오른쪽 클릭 메뉴)에는 그 클래스를 부르는 줄만 둔다
  (MainFrame 1줄, ObjectExplorerPanel 4줄, 각 파일의 변경 이력 1줄).
- SSMS는 Disk Usage를 보고서로 따로 열지만, 같은 서버의 관리 정보를 한 문서에 모으는 쪽을 골랐다. 되돌리기: 두 패널을 `ToolDocument`로 감싸
  MainFrame에서 따로 열면 된다(패널은 `AdminConnection`만 받는다).

### DB별 지원

| 항목 | SQL Server | PostgreSQL | MySQL·MariaDB | Oracle |
|---|---|---|---|---|
| 데이터베이스 크기 | 데이터베이스마다 데이터 크기·사용·빈 공간, 로그 크기·사용·사용 %, 합계, 상태, 복구 모델, 로그 재사용 대기 | `pg_database_size`(소유자, 인코딩, 기본 테이블스페이스) | 스키마마다 테이블 수, 행 수(추정), 데이터, 인덱스, 빈 공간(DATA_FREE), 합계 | 연결한 PDB(CON_NAME) 하나: 데이터 파일 크기·사용·빈 공간, 임시 파일, 합계 |
| 파일·테이블스페이스 | Files: 데이터베이스의 데이터·로그 파일마다 크기, 사용, 빈 공간, 빈 공간 %, 자동 증가(64 MB씩 / 10%씩 / 없음), 최대 크기(무제한), 파일 그룹, 경로 | Tablespaces: 크기, 소유자, 위치 | — (InnoDB 빈 공간은 테이블의 DATA_FREE) | Tablespaces: 내용, 상태, 크기, 사용, 빈 공간, 사용 %, 최대 크기, 최대 대비 사용 %, 자동 확장. Data Files: 파일(임시 파일 포함)마다 크기, 사용, 빈 공간, 자동 확장, 증가 단위, 최대 크기, 상태 |
| 큰 테이블 | 고른 데이터베이스의 행 수, 데이터, 인덱스, 미사용, 합계(예약) | 연결한 데이터베이스의 종류(테이블/구체화된 뷰), 행 수(추정), 죽은 행, 데이터, TOAST, 인덱스, 합계 | 엔진, 행 형식, 행 수(추정), 데이터, 인덱스, 빈 공간, 합계 | 소유자, 행 수(통계), 데이터, 인덱스, LOB, 합계 |
| 큰 테이블 범위 | 데이터베이스 하나(기본: 연결의 데이터베이스) | 스키마 또는 전체 | 스키마 또는 전체 | 소유자 또는 전체(DBA 뷰가 없으면 자기 스키마) |
| 쓰는 뷰·함수 | `sys.databases`, `sys.master_files`, 데이터베이스마다 `[db].sys.sp_executesql`로 `sys.database_files`·`FILEPROPERTY`, `sys.partitions`·`sys.allocation_units` | `pg_database_size`, `pg_tablespace_size`, `pg_table_size`, `pg_total_relation_size`, `pg_indexes_size`, `pg_stat_all_tables` | `information_schema.SCHEMATA`, `TABLES`, `USER_PRIVILEGES` | `DBA_TABLESPACES`, `DBA_DATA_FILES`, `DBA_FREE_SPACE`, `DBA_TEMP_FILES`, `DBA_TEMP_FREE_SPACE`, `DBA_SEGMENTS`, `DBA_INDEXES`, `DBA_LOBS`, `DBA_TABLES`(없으면 `USER_*`) |

### 화면

- 도구 막대: Refresh, 범위 콤보(데이터베이스·스키마·소유자, PostgreSQL·MySQL·Oracle은 "(모두)" 포함), 큰 테이블 수(25, 50, 100, 250, 500, 기본 50).
  범위나 개수를 바꾸면 다시 읽는다. 크기는 자주 바뀌지 않아 자동 새로 고침은 없다(탭을 처음 볼 때와 Refresh).
- 표마다 하위 탭 하나(`AdminGridTable`). 다시 읽어도 열이 같으면 정렬과 열 너비, 고른 하위 탭을 그대로 둔다.
- 크기는 1024 단위의 B·KB·MB·GB·TB(`SpaceFormat`), 도구 설명에 정확한 바이트 수("1,610,612,736바이트"). 정렬은 원래 숫자로 하고, 빈 값(읽을 수 없음)은
  오름·내림 모두 아래로, 최대 크기의 "무제한"은 가장 큰 값으로 정렬한다.
- 복사: Ctrl+C·"복사"는 보이는 글자, "머리글과 함께 복사", "정확한 값 복사"(머리글과 함께, 크기를 바이트 수로; 스프레드시트에서 계산용). 고른 행이 없으면 모든 행.
- 읽을 수 없는 표는 하위 탭 안에 이유를 보이고, 무엇을 받아야 하는지는 위의 안내 줄(`AccessNoticeBar`)에 보인다. 지원하지 않는 DB는 안내 줄에
  "{드라이버}에서는 공간 사용량을 볼 수 없습니다"를 보이고 명령을 끈다.

### 설계 결정

- **SQL Server 사용량은 데이터베이스 안에서 읽는다**: 파일의 사용량은 `FILEPROPERTY(name, 'SpaceUsed')`만 알려 주고 현재 데이터베이스에서만 된다.
  세션의 데이터베이스를 `USE`로 바꾸지 않도록(트랜잭션 안전 규칙: 연결·데이터베이스를 바꾸지 않는다) 데이터베이스마다 `EXEC [db].sys.sp_executesql`로 실행한다.
  로그 파일도 같은 함수로 읽힌다(2022에서 확인: 로그 200 MB 중 87 MB). 열 수 없는 데이터베이스(권한 없음, 오프라인)는 `sys.master_files`의 크기만 보인다.
- **SQL Server 테이블 크기는 `sp_spaceused`와 같은 계산**이지만 VIEW DATABASE STATE가 필요한 `sys.dm_db_partition_stats` 대신 메타데이터 권한만 필요한
  `sys.partitions`·`sys.allocation_units`를 쓴다. 데이터 = 힙·클러스터형 인덱스의 in-row data_pages + LOB·행 오버플로 used_pages, 인덱스 = 사용 − 데이터,
  미사용 = 예약 − 사용, 합계 = 예약.
- **PostgreSQL 크기 함수의 권한 규칙을 미리 따른다**: `pg_database_size`(CONNECT), `pg_tablespace_size`(CREATE, 현재 데이터베이스의 기본 테이블스페이스는 누구나),
  pg_read_all_stats(10+)는 둘 다 허용. 권한이 없으면 함수가 오류를 내고 수동 커밋 연결의 트랜잭션을 깨므로, `has_database_privilege`·`has_tablespace_privilege`로
  확인해 크기를 비우고 안내한다. 테이블은 연결한 데이터베이스의 것만 읽을 수 있어 표 제목에 데이터베이스 이름을 넣는다("큰 테이블(chr_space_logs)").
  PostgreSQL은 디스크의 남은 공간을 SQL로 알려 주지 않아 빈 공간 열이 없고, 대신 죽은 행(`n_dead_tup`)을 보인다.
- **MySQL 크기는 information_schema 값 그대로**: MySQL 8은 `information_schema_stats_expiry`(기본 하루) 캐시 값을 준다. 세션 변수를 바꾸지 않으려고(읽기 전용)
  그대로 보인다. 전역 SELECT가 없으면 권한이 있는 객체만 보인다고 안내한다(`USER_PRIVILEGES`의 현재 계정).
- **Oracle은 DBA 뷰를 하나씩 읽어 보고** 읽을 수 있는 것만 질의한다. 파일 뷰가 없으면 데이터베이스·테이블스페이스·데이터 파일 표는 "읽을 수 없음",
  세그먼트 뷰가 없으면 큰 테이블은 `USER_*` 뷰로 로그인 자신의 스키마만 보인다. 테이블 크기는 세그먼트를 테이블로 모은다(테이블·파티션, 그 테이블 인덱스,
  LOB 세그먼트와 LOB 인덱스). 휴지통(`BIN$`)은 뺀다. 임시 테이블스페이스는 `DBA_TEMP_FREE_SPACE`로 크기·빈 공간을 읽는다.
- **ORACLE LICENSE GUARD**: 공간 기록을 주는 AWR 뷰(`DBA_HIST_TBSPC_SPACE_USAGE`, `DBA_HIST_SEG_STAT`)와 `DBA_TABLESPACE_USAGE_METRICS`는 쓰지 않는다. 위 뷰는 팩이 필요 없다.
- **적용 범위**: SQL Server는 Azure SQL Database·Synapse(엔진 에디션 5, 6, 11)를 빼고, PostgreSQL은 서버 종류가 PostgreSQL이고 9.2 이상일 때만
  (Greenplum·Redshift 같은 계열 서버는 크기 함수가 다르다), MySQL은 MySQL·MariaDB 데이터 소스 모두, Oracle은 모든 버전(임시 빈 공간은 11g+).

### 권한 확인

| DB | 확인 | 모자랄 때 |
|---|---|---|
| SQL Server | `HAS_DBACCESS`, `HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW ANY DEFINITION')`, `HAS_PERMS_BY_NAME(db, 'DATABASE', 'VIEW DEFINITION')` | 열 수 없는 데이터베이스는 크기 없음 + "VIEW ANY DEFINITION을 부여하십시오", 큰 테이블은 권한 있는 테이블만 + "VIEW DEFINITION을 부여하십시오" |
| PostgreSQL | `has_database_privilege(.., 'CONNECT')`, `has_tablespace_privilege(.., 'CREATE')`, `pg_has_role('pg_read_all_stats', 'USAGE')` | 그 데이터베이스·테이블스페이스의 크기를 비우고 안내 |
| MySQL·MariaDB | `information_schema.USER_PRIVILEGES`의 전역 SELECT | "권한을 가진 스키마와 테이블만 표시합니다" |
| Oracle | 뷰마다 `SELECT 1 FROM <뷰> WHERE 1 = 0` | 못 읽는 뷰 이름과 "SELECT_CATALOG_ROLE을 부여하십시오", 해당 표는 "사용할 수 없음", 큰 테이블은 자기 스키마 |

## 서버 로그 (Server Logs, 2026-11-07)

DB 관리 기능 축 5. 연결마다 서버의 로그를 최신 항목부터 한 쪽씩 보인다. 모델은 `com.seizia.chromite.model.admin.logs`(`ServerLogDialect`, `ServerLogSource`,
`ServerLogQuery`, `ServerLogEntry`, `ServerLogPage`, `LogMessages`)와 DB별 방언(`mssql/SQLServerLogDialect`, `postgresql/PostgreLogDialect`,
`mysql/MySQLLogDialect`, `oracle/OracleLogDialect`), 화면은 `ServerLogPanel`이다. DBeaver에는 이 화면이 없다.

### DB별 지원

| DB | 로그 | 읽는 방법 | 보관본 | 수준 | 서버가 거르는 것 |
|---|---|---|---|---|---|
| SQL Server | 오류 로그, 에이전트 로그 | `xp_readerrorlog <보관>, <1 오류 로그 / 2 에이전트>, N'<찾을 글자>', N'', <시작>, <끝>, N'desc'` | `sp_enumerrorlogs`의 보관 번호와 날짜(현재, 보관 #1…) | 에이전트 로그만(1 오류, 2 경고, 3 정보) | 찾을 글자, 시간, 정렬 |
| PostgreSQL | 서버 로그(log_destination의 stderr 텍스트, csvlog, jsonlog 형식마다 하나) | `pg_ls_logdir()`, `pg_current_logfile(형식)`, `pg_read_binary_file(경로, 위치, 길이, true)` | 로그 디렉터리의 그 형식 파일(현재 파일이 맨 위) | ERROR·FATAL·PANIC 묶음, 각 수준 | 없음(끝에서부터 읽으면서 거름) |
| MySQL·MariaDB | 오류 로그(MySQL 8.0.22+), 느린 쿼리 로그(log_output=TABLE) | `performance_schema.error_log`, `mysql.slow_log` | — | 오류 로그의 PRIO(System, Error, Warning, Note) | 시간, 찾을 글자, 수준, 정렬, LIMIT/OFFSET |
| Oracle | 경고 로그(11g+) | `V$DIAG_ALERT_EXT` | — | MESSAGE_LEVEL(위험 1, 심각 2, 중요 8, 보통 16), "ORA- 오류"(메시지에 ORA-) | 시간, 찾을 글자, 수준, 정렬, ROWNUM |

### 화면

- 도구 막대 첫 줄: Refresh(로그 목록과 최신 항목을 다시 읽음), 로그, 보관본(SQL Server 보관 번호, PostgreSQL 파일; 보관본이 없는 로그는 숨김), 시간(최근 1시간·24시간(기본)·7일·30일,
  전체, 직접 지정…). 둘째 줄: 수준, 메시지 검색(Enter로 적용: 글자마다 서버를 읽지 않게). 직접 지정을 고르면 셋째 줄에 시작·끝 칸과 적용 단추가 나온다
  (로그에 적힌 시각, `yyyy-MM-dd HH:mm[:ss]`, 비우면 제한 없음). 한 줄로는 좁은 문서 탭에서 검색 칸이 잘려 나눴다.
- 표: 시간, 수준(로그에 수준이 있을 때), 출처(프로세스·하위 시스템·사용자@호스트·구성 요소; 로그에 있을 때), 메시지(한 줄). 아래 칸에 고른 항목의 전체 메시지와
  상세(SQLSTATE, DETAIL, HINT, STATEMENT, ERROR_CODE, MESSAGE_TYPE, 느린 쿼리의 쿼리 시간·검사한 행 등)를 보인다(선택해 복사할 수 있다). 표의 복사는 공간 사용량과 같다.
- 표 너비: 마지막 열(메시지)이 남은 너비를 쓰고 옆으로 스크롤하지 않는다(`AdminGridTable(fillWidth)`).
- 쪽 나누기: 한 쪽 500개, 오른쪽 아래 "더 읽기"로 다음 이전 항목을 붙인다. 화면에 쌓는 항목은 10,000개까지이고, 그 뒤로는 시간 범위를 좁히거나 검색하라고 알린다.
- 읽을 수 없는 로그도 목록에 있다. 고르면 읽지 않고 안내 줄에 이유(권한이나 서버 설정과 무엇을 하면 되는지)를 보이고 시간·수준·검색을 끈다.
  처음에는 읽을 수 있는 첫 로그를 고른다.

### 설계 결정

- **큰 로그를 통째로 읽지 않는다**: MySQL·Oracle은 서버가 거르고 자른다(쪽마다 500 + 1행으로 다음 쪽이 있는지 안다). SQL Server는 `xp_readerrorlog`가 쪽 나누기를
  하지 않아 최신 것부터 받으면서 앞 쪽들의 행을 건너뛰고 쪽 크기만큼 읽은 뒤 결과를 닫는다(커서 = 건너뛸 행 수). PostgreSQL은 파일을 끝에서부터 256 KB 조각으로
  거꾸로 읽고(한 쪽에 최대 8 MB), 커서는 다음 쪽이 끝날 바이트 위치(항목의 시작)다. 조각의 첫 항목은 앞 조각에 걸쳐 있을 수 있어 버리고 다음 조각에서 읽는다.
- **PostgreSQL 항목의 경계**: 텍스트 형식은 공백·탭으로 시작하지 않는 줄이 새 항목이고 DETAIL·HINT·CONTEXT·STATEMENT 줄은 앞 항목의 상세로 붙인다.
  CSV는 시각으로 시작하는 줄에서 RFC 4180으로 나누고(따옴표 안 줄바꿈), JSON은 줄 하나가 항목이다(Gson). 수준과 프로세스는 기본 log_line_prefix
  (`%m [%p] `)의 모양으로 찾는다. 바이트로 읽고 UTF-8로 풀어 커서가 문자 경계와 상관없다.
- **시각은 로그에 적힌 그대로** 보인다(서버의 로그 시간대). 클라이언트 시간대로 바꾸면 서버 파일이나 다른 도구와 맞춰 보기 어렵다. "최근 N"은 서버의 지금 시각으로 계산한다
  (SQL Server `GETDATE()`, MySQL `NOW(6)`, Oracle `SYSTIMESTAMP`, PostgreSQL `now()`를 log_timezone으로 바꾼 시각). 직접 지정 범위는 로그 시각 그대로 비교한다.
  MySQL은 드라이버가 TIMESTAMP를 클라이언트 시간대로 바꾸지 않도록 `DATE_FORMAT` 글자로 읽는다.
- **권한을 먼저 본다**: SQL Server는 버전마다 권한 이름이 달라(2022 VIEW ANY ERROR LOG, 그 전 securityadmin) `sp_enumerrorlogs`를 실제로 읽어 보아 판단한다.
  PostgreSQL은 `has_function_privilege`로 세 함수의 EXECUTE를 확인한다(실패하는 문장을 보내지 않는다). MySQL·Oracle은 표·뷰를 `WHERE 1 = 0`으로 읽어 본다.
- **Oracle 수준은 MESSAGE_LEVEL**이다. MESSAGE_TYPE은 23ai에서 "Resize operation completed"가 3(ERROR)으로 나오는 등 이름과 맞지 않아(2026-11-07 Oracle Free 23ai에서 확인)
  상세에만 보인다. SELECT_CATALOG_ROLE에는 `V$DIAG_ALERT_EXT`가 없다(같은 날 ORA-00942로 확인). 안내는 SELECT ANY DICTIONARY 또는 SYS가 주는
  `V_$DIAG_ALERT_EXT`의 SELECT를 권한다. `V$DIAG_ALERT_EXT`는 Diagnostics/Tuning Pack 뷰가 아니다(ORACLE LICENSE GUARD).
- **SQL Server의 찾을 글자는 N'' 문자열로** 넣는다. 확장 프로시저는 nvarchar만 받으므로 드라이버의 "문자열을 유니코드로 보내기" 설정과 상관없게 한다
  (작은따옴표는 두 번 써서 이스케이프). 시간은 이 코드가 만든 형식의 글자다.
- **읽기 전용**: 로그를 돌리거나(`sp_cycle_errorlog`, `pg_rotate_logfile`) 설정을 바꾸지 않는다.

### 읽을 수 없을 때의 안내

| DB | 경우 | 안내 |
|---|---|---|
| SQL Server | 권한 없음(오류 27219, 229) | VIEW ANY ERROR LOG(2022+) 또는 securityadmin, 서버 메시지 |
| SQL Server | 에이전트 로그 파일 없음(에이전트를 쓰지 않는 서버) | 읽을 때 서버 오류를 상태 줄에 보인다(Linux에서는 보관 목록이 비어 미리 알 수 없다) |
| PostgreSQL | 10 이전 | PostgreSQL 10 이상 필요 |
| PostgreSQL | logging_collector off | 로그가 서버 프로세스의 표준 오류로 나가 SQL로 읽을 수 없음 |
| PostgreSQL | 함수 권한 없음 | superuser, 또는 pg_monitor + `GRANT EXECUTE ON FUNCTION pg_read_binary_file(text, bigint, bigint, boolean)`(log_directory가 데이터 디렉터리 밖이면 pg_read_server_files도). pg_read_server_files는 EXECUTE를 주지 않는다(PostgreSQL 17의 `pg_proc.proacl`로 확인) |
| PostgreSQL | log_destination에 파일 형식 없음(syslog, eventlog) | stderr·csvlog·jsonlog를 쓰라고 안내 |
| MySQL·MariaDB | MariaDB | 오류 로그를 SQL로 제공하지 않음, log_error(비면 "서버의 표준 오류")에 기록됨 |
| MySQL | 8.0.22 전 | performance_schema.error_log가 없음 |
| MySQL·MariaDB | 느린 쿼리 로그 off / log_output에 TABLE 없음 / SELECT 권한 없음 | 각각 설정 값·파일 이름 또는 서버 메시지와 함께 |
| Oracle | 10g 이하 / 뷰 권한 없음 | 11g 이상 필요 / SELECT ANY DICTIONARY 또는 SYS가 주는 `V_$DIAG_ALERT_EXT`의 SELECT(SELECT_CATALOG_ROLE에는 없음), 서버 메시지 |

## 유지 관리 (Maintenance, 2026-11-07)

DB 관리 축 4. 테이블·인덱스·스키마·데이터베이스에 VACUUM/ANALYZE/REINDEX/CLUSTER, ANALYZE/OPTIMIZE/CHECK/REPAIR TABLE, DBMS_STATS·다시 컴파일·인덱스 다시 작성,
인덱스 다시 작성·다시 구성·통계 업데이트·DBCC CHECKDB를 미리 보기와 확인을 거쳐 실행하고, Activity Monitor의 Maintenance 탭에 후보·상태 목록을 보인다.
모델은 `com.seizia.chromite.model.admin.maintenance`(`MaintenanceDialect`, `MaintenanceTarget`, `MaintenanceOperation`, `MaintenanceOption`, `MaintenancePlan`,
`MaintenanceCommand`, `MaintenanceResult`, `MaintenanceReport`, `MaintenanceAction`, `MaintenanceRunner`, `MaintenanceMessages`)와 DB별 방언
(`postgresql/PostgreMaintenanceDialect`, `mysql/MySQLMaintenanceDialect`, `oracle/OracleMaintenanceDialect`, `mssql/SQLServerMaintenanceDialect`),
화면은 `MaintenancePanel`(탭), `MaintenanceDialog`(대화상자), `MaintenanceCommands`(Object Explorer 메뉴), `AdminGridTabs`(하위 탭)다.

### 여는 곳

| 명령 | 위치 | 동작 |
|---|---|---|
| Maintenance(유지 관리) | Tools 메뉴, Object Explorer 오른쪽 클릭 메뉴 | Activity Monitor를 Maintenance 탭(6번째, `ActivityMonitorPanel.MAINTENANCE_TAB`)으로 연다(`AdminViewCommands`) |
| Maintenance…(유지 관리…) | Object Explorer 오른쪽 클릭 메뉴(테이블, 인덱스, 스키마, 데이터베이스 노드) | 그 노드를 대상으로 유지 관리 대화상자를 연다(`MaintenanceCommands`). 할 수 없는 노드에서는 꺼져 있다 |
| 작업 실행…(행 두 번 클릭, Enter) | Maintenance 탭 도구 막대 | 고른 행의 권장 작업으로 대화상자를 연다. 실행이 끝나면 탭을 다시 읽어 전후 숫자를 보인다 |

MainFrame은 고치지 않았다(Tools 메뉴는 `AdminViewCommands`가 만든다). ObjectExplorerPanel에는 `MaintenanceCommands`를 부르는 줄 4개와 변경 이력 1줄만 더했다.

### DB별 지원

| 항목 | PostgreSQL(9.4+, 서버 종류 PostgreSQL) | MySQL·MariaDB | Oracle | SQL Server |
|---|---|---|---|---|
| 테이블 | VACUUM(FULL, FREEZE, ANALYZE, VERBOSE), ANALYZE(VERBOSE), REINDEX TABLE(CONCURRENTLY 12+), CLUSTER(인덱스 고르기, 지난번 인덱스) | ANALYZE(NO_WRITE_TO_BINLOG), OPTIMIZE, CHECK(QUICK/FAST/MEDIUM/EXTENDED/CHANGED/FOR UPGRADE), REPAIR(QUICK/EXTENDED) | DBMS_STATS.GATHER_TABLE_STATS(표본 자동/1/10/30/100%, 인덱스 포함) | 모든 인덱스 다시 작성(ONLINE, SORT_IN_TEMPDB)·다시 구성(LOB_COMPACTION), UPDATE STATISTICS(기본/FULLSCAN/SAMPLE n PERCENT/RESAMPLE) |
| 인덱스 | REINDEX INDEX(CONCURRENTLY), CLUSTER … USING | — | ALTER INDEX … REBUILD(ONLINE), GATHER_INDEX_STATS | 다시 작성·다시 구성·UPDATE STATISTICS(그 인덱스의 통계) |
| 스키마 | 테이블마다 VACUUM/ANALYZE, REINDEX SCHEMA(9.5+) | (데이터베이스 = 스키마) 기본 테이블 50개씩 한 문장 | GATHER_SCHEMA_STATS(GATHER/STALE/EMPTY), 무효 개체 컴파일 | — |
| 데이터베이스 | VACUUM/ANALYZE 전체, REINDEX DATABASE, CLUSTER(전에 정렬한 테이블) | — | — | DBCC CHECKDB(NO_INFOMSGS, PHYSICAL_ONLY), sp_updatestats |
| 그 밖의 개체 | — | — | 무효 개체 하나 `ALTER … COMPILE`(패키지·형식 본문은 COMPILE BODY), 스키마 컴파일(COMPILE_SCHEMA 무효만/전체, 또는 개체마다 ALTER … COMPILE) | — |
| 결과 | VERBOSE의 INFO 메시지 | Table/Op/Msg_type/Msg_text 표 | `ALL_ERRORS`의 줄·위치·메시지 표 | 정보 메시지(NO_INFOMSGS를 끄면 DBCC 출력) |
| 유지 관리 탭 | VACUUM·ANALYZE(죽은 행 순, 마지막 (자동) VACUUM·ANALYZE, n_mod_since_analyze, 자동 VACUUM 대상), 부풀림(추정) | 빈 공간(DATA_FREE, 빈 공간 %, 마지막 변경·검사), InnoDB 통계(`mysql.innodb_table_stats.last_update`) | 무효 개체(오류 수, 고르면 컴파일 오류), 오래된 통계, 사용할 수 없는 인덱스 | 인덱스 조각화(LIMITED, 5% 이상, 권장 REORGANIZE/REBUILD), 통계(수정 수 순), 데이터베이스 무결성(마지막 정상 CHECKDB) |
| 권한 확인 | 소유자(소유 역할 구성원)·superuser·데이터베이스 소유자(VACUUM·ANALYZE, 16까지)·MAINTAIN/pg_maintain(17+) | 테이블마다 SELECT+INSERT(ANALYZE·OPTIMIZE·REPAIR), CHECK는 아무 권한 | 남의 스키마: ANALYZE ANY, ALTER ANY INDEX, 종류별 ALTER ANY …, 목록은 DBA 뷰 없으면 ALL 뷰 + 안내 | 테이블 ALTER, CHECKDB·sp_updatestats는 sysadmin/db_owner, 목록은 VIEW DATABASE STATE |

### 대화상자

- 위: 서버, 대상, 작업 콤보(대상에 할 수 있는 작업, 권장 작업을 먼저 고름), 설명, 옵션(체크 상자·콤보, 서버가 허용하지 않는 옵션은 꺼진 채 이유를 도구 설명에),
  현재 상태(죽은 행·마지막 VACUUM, 조각화와 권장, 마지막 분석, 무효 상태와 마지막 DDL, 마지막 정상 CHECKDB 등).
- 권한이 없으면 맨 위 안내 줄에 이유와 무엇을 받아야 하는지를 보이고 실행을 끈다. 경고(VACUUM FULL·CLUSTER의 ACCESS EXCLUSIVE 잠금, CONCURRENTLY 없는 REINDEX,
  OPTIMIZE의 재생성, REPAIR의 엔진 제한, 오프라인 다시 작성, FULLSCAN, CHECKDB 부하, ONLINE 없는 Oracle 다시 작성, 컴파일의 영향)는 미리 보기 위 경고 줄에 보인다.
- 명령 미리 보기: 실행할 문장 그대로(PL/SQL 블록은 `/`로 끝냄). "명령 복사"로 복사한다.
- 실행: 확인 창(서버 이름, 문장 최대 8개, 경고, "별도 연결에서 자동 커밋으로 실행") → 백그라운드 작업, 진행 막대, 취소 단추. 창을 닫으려 하면 실행 중일 때 취소할지 묻는다.
- 결과: 메시지 탭(문장, 서버 메시지, 문장마다 걸린 시간, 오류)과 서버가 돌려준 표마다 탭 하나. 행이 있는 첫 표를 먼저 보인다.
- 모달이 아니다: 오래 걸리는 CHECKDB 동안에도 앱을 쓸 수 있다.

### 설계 결정

- **사용자의 트랜잭션에 넣지 않는다**: 작업(상태 읽기, 실행)마다 대상의 인스턴스에서 격리된 실행 컨텍스트를 새로 열고 닫는다(`MaintenanceRunner`).
  새 컨텍스트가 수동 커밋이면 자동 커밋으로 바꾼다: 방금 연 연결이라 열린 트랜잭션이 없으므로 트랜잭션 안전 규칙(커밋/롤백을 묻기)의 대상이 아니다.
  VACUUM·REINDEX CONCURRENTLY·REINDEX DATABASE는 트랜잭션 블록 안에서 실행할 수 없고(DBeaver `PostgreToolBaseVacuum#isRunInAutoCommit`과 같은 이유),
  Oracle DDL(ALTER INDEX REBUILD, ALTER … COMPILE)은 열린 트랜잭션을 커밋한다. 연결 설정이 "단일 연결"이어도 따로 연다(같은 이유). Activity Monitor가 열어 둔 연결도 쓰지 않는다.
- **PostgreSQL은 대상의 데이터베이스에서**: 노드에서 열면 `DBUtils.getObjectOwnerInstance`(그 `PostgreDatabase`), 탭에서 열면 연결의 기본 인스턴스(탭이 읽은 데이터베이스)다.
  대상의 데이터베이스가 연결된 데이터베이스와 다르면 상태 읽기에서 이유를 보인다.
- **미리 보기 = 실행**: `buildCommand`는 서버를 읽지 않고 대화상자를 열 때 읽은 계획(`MaintenancePlan`: 작업, 거부 이유, 상태, 스키마의 테이블·무효 개체 목록, 에디션)만으로 문장을 만든다.
  옵션을 바꿀 때마다 바로 바뀌고, 실행하는 문장은 미리 보기에 보인 목록 그대로다.
- **취소**: 작업의 모니터가 실행 중인 JDBC 문장을 막는 객체로 가지고 있어(`JDBCStatementImpl#startBlock`), 작업 취소가 `Statement.cancel`(PostgreSQL 취소 요청, SQL Server 주의 신호)로 이어진다.
  문장 사이에서도 취소를 본다(나머지는 실행하지 않음). 문장을 하나씩 실행해 스키마 대상도 테이블 사이에서 멈출 수 있다.
- **권한을 먼저 본다**: PostgreSQL은 권한이 없는 테이블에 VACUUM을 보내도 오류 없이 "skipping" 경고만 하므로 미리 소유권·MAINTAIN을 보고, 스키마 대상은 할 수 있는 테이블만 문장에 넣는다(몇 개 중 몇 개인지 상태에 보임).
  MySQL은 `information_schema`의 USER/SCHEMA/TABLE_PRIVILEGES에서 현재 계정의 SELECT·INSERT를 모은다. 역할이 켜져 있으면 역할의 권한까지 계산하지 않고 서버에 맡긴다.
  MariaDB의 `ENABLED_ROLES`는 역할이 없을 때 ROLE_NAME이 NULL인 행 하나를 돌려주므로(11.8에서 확인) `COUNT(ROLE_NAME)`으로 센다.
- **부풀림은 추정치**: 플래너 통계(`pg_stats`의 열 평균 너비·NULL 비율, `reltuples`, fillfactor)로 행 크기와 필요한 페이지 수를 계산해 실제 페이지 수와 비교한다
  (check_postgres, pgsql-bloat-estimation에 널리 쓰이는 방법을 이 코드에서 다시 썼다). 표 제목과 설명에 "추정"을 쓰고, 통계가 낡으면 틀린다는 것(ANALYZE 먼저)을 적었다.
  모든 열의 통계를 읽을 수 있는 테이블만 계산한다. 정확한 값은 pgstattuple 확장이 필요해 쓰지 않았다(확장 설치는 범위 밖).
- **SQL Server 조각화는 LIMITED, 권장은 5–30% REORGANIZE, 30% 초과 REBUILD**(SSMS 문서의 권고). 다른 데이터베이스의 개체도 세션의 데이터베이스를 바꾸지 않는다:
  문장은 세 부분 이름, 목록·권한은 `EXEC [db].sys.sp_executesql`. 목록·상태 읽기는 동적 배치 안에서 `SET LOCK_TIMEOUT 5000`을 써서, 긴 트랜잭션이나 오프라인 다시 작성이 테이블을 잠그고 있으면
  기다리지 않고 "지금은 읽을 수 없음"을 보인다(확인 중 대화상자가 "읽는 중"에 멈춘 것을 고침). SET은 그 배치에만 적용된다.
- **ONLINE**: SQL Server는 엔진 에디션 3/5/8에서만 켤 수 있고, 다른 에디션은 꺼진 옵션과 이유를 보인다. Oracle ONLINE 다시 작성은 Enterprise Edition 기능이라고 도구 설명에 적는다(Free 23ai에서 됨).
- **Oracle 이름은 따옴표로**: DBMS_STATS는 따옴표 없는 이름을 대문자로 바꾸므로 `'"OWNER"'` 모양으로 넘기고, ALTER 문은 `"OWNER"."NAME"`이다.
  컴파일 오류는 예외가 아니라 경고(ORA-17110)로 오므로 실행 뒤 `ALL_ERRORS`를 읽는 SELECT를 덧붙인다(미리 보기에도 보인다).
- **ORACLE LICENSE GUARD**: 쓰는 뷰는 `DBA/ALL_OBJECTS`, `DBA/ALL_ERRORS`, `DBA/ALL_TAB_STATISTICS`, `DBA/ALL_INDEXES`, `ALL_USERS`, `SESSION_PRIVS`와 DBMS_STATS·DBMS_UTILITY 패키지다.
  AWR(`DBA_HIST_*`), ASH, SQL Tuning/Access Advisor(인덱스 권고)는 쓰지 않는다.
- **표 모양 공유**: 유지 관리·복제 표는 `AdminGrid`(문자열 ID, 행 값·상세 글, 빈 표·읽을 수 없음 문구, 설명)다. 공간 사용량의 `SpaceGrid`는 종류가 열거형이라 고치지 않고 두었다.
  열은 같은 `SpaceColumn`이라 `AdminGridTable`이 그대로 그린다.

## 복제 상태 (Replication, 2026-11-07)

DB 관리 축 6, 읽기 전용. Activity Monitor의 Replication 탭(7번째, `ActivityMonitorPanel.REPLICATION_TAB`)에 서버의 복제 역할과 상태를 보인다.
모델은 `com.seizia.chromite.model.admin.replication`(`ReplicationDialect`, `ReplicationReport`, `ReplicationMessages`)과 DB별 방언(`postgresql/PostgreReplicationDialect`,
`mysql/MySQLReplicationDialect`, `oracle/OracleReplicationDialect`, `mssql/SQLServerReplicationDialect`), 화면은 `ReplicationPanel`이다. Tools·Object Explorer 메뉴의 "Replication(복제)"로 연다.

### DB별 지원

| DB | 요약 | 표 | 구성되지 않았을 때 |
|---|---|---|---|
| PostgreSQL 10+ | 역할(주/대기), 스트리밍·논리 복제 구성 여부, wal_level 등 설정, 현재(대기 서버는 받은·재생한) WAL 위치, 마지막 재생 뒤 경과 | 대기 서버(`pg_stat_replication`: 상태, 동기화, 보낸·재생한 LSN, 지연 바이트, write/flush/replay 지연 초), 복제 슬롯(활성, 재시작 LSN, 보존 중인 WAL 바이트, wal_status 13+), WAL 수신기(`pg_stat_wal_receiver`), 구독(`pg_stat_subscription` 적용 작업자), 게시(테이블 수, 작업) | "연결된 대기 서버가 없습니다(스트리밍 복제가 구성되지 않음)", "복제 슬롯이 없습니다" 등 |
| MySQL·MariaDB | 역할(복제본/아님), 바이너리 로그와 등록된 복제본, server_id·log_bin·binlog_format·GTID 변수, 바이너리 로그 위치 | 복제본 연결(`SHOW REPLICA STATUS`/`SHOW SLAVE STATUS`, MariaDB `SHOW ALL SLAVES STATUS`: IO/SQL 스레드, Seconds_Behind, 원본 로그, 실행 위치, GTID, 마지막 IO/SQL 오류), 연결된 복제본(`SHOW REPLICAS`/`SHOW SLAVE HOSTS`) | "이 서버는 복제본이 아닙니다", "등록된 복제본이 없습니다" |
| Oracle | Data Guard 구성 여부, `V$DATABASE`의 DATABASE_ROLE·PROTECTION_MODE/LEVEL·SWITCHOVER_STATUS·DATAGUARD_BROKER·FORCE_LOGGING·LOG_MODE·OPEN_MODE | 보관 대상(`V$ARCHIVE_DEST_STATUS`, INACTIVE 제외), Data Guard 통계(`V$DATAGUARD_STATS`, 읽을 수 있을 때) | "구성되지 않음(주 역할, 대기 대상 없음, Broker 꺼짐)", "Data Guard 통계가 없습니다" |
| SQL Server | Always On(IsHadrEnabled, 그룹 수), 복제(게시·배포 데이터베이스 수), 배포 데이터베이스 | 가용성 복제본(역할, 연결, 동기화 상태, 가용성·장애 조치 모드), 가용성 데이터베이스(동기화 상태, 일시 중단 이유, 로그 전송·다시 실행 큐 크기, 마지막 커밋), 복제 데이터베이스(`sys.databases`의 is_published·is_merge_published·is_distributor) | "Always On 가용성 그룹을 사용하지 않습니다(구성되지 않음)", "게시되었거나 배포 데이터베이스로 쓰이는 데이터베이스가 없습니다" |

### 설계 결정

- **구성되지 않음은 오류가 아니다**: 표마다 빈 표 문구(`AdminGrid#getEmptyText`)를 두고 요약 행도 "구성되지 않음"이라고 한다.
- **버전 차이는 시도해 본다(MySQL)**: 8.0.22의 SLAVE→REPLICA, 8.2의 SHOW MASTER STATUS→SHOW BINARY LOG STATUS는 패치 번호까지 맞춰야 해서, 새 문장을 먼저 실행하고 문법 오류(1064)면 옛 문장을 쓴다.
  열은 새 이름과 옛 이름을 차례로 찾는다. MySQL·MariaDB는 실패한 문장이 트랜잭션을 깨지 않는다.
- **대기 서버에서는 `pg_last_wal_receive_lsn()`**: 복구 중에는 `pg_current_wal_lsn()`이 오류를 낸다. 지연 시간은 interval 글자(00:00:00.075823)가 좁은 열에서 읽기 어려워 초("0.076 s")로 보인다.
- **비밀이 있을 수 있는 값은 보이지 않는다**: `primary_conninfo`(비밀번호가 들어 있을 수 있음)와 `pg_subscription.subconninfo`는 읽지 않는다.
- **자동 새로 고침**: 지연은 계속 바뀌므로 Processes·Locks와 같은 간격 상자(기본 10초, `activityMonitor.replication.refreshSeconds`)를 둔다. 탭이 보일 때만 읽는다.
- **SQL Server 복제는 기본 정보만**: 구독자 상태와 에이전트 기록은 배포자의 복제 모니터 프로시저가 필요해 넣지 않았다. `sys.databases.is_subscribed`는 쓰이지 않는 열이라 보지 않는다.
- **ORACLE LICENSE GUARD**: `V$DATABASE`, `V$ARCHIVE_DEST_STATUS`, `V$DATAGUARD_STATS`는 팩이 필요 없다. Active Data Guard가 필요한 기능을 쓰지 않는다.

### 권한 확인

| DB | 확인 | 모자랄 때 |
|---|---|---|
| PostgreSQL | superuser 또는 `pg_read_all_stats`(pg_monitor) | 행은 보이지만 위치·지연·상태가 비어 있다고 안내 + pg_monitor 부여 |
| MySQL·MariaDB | 서버가 거부하면(1227) | REPLICATION CLIENT / REPLICATION SLAVE(MySQL), REPLICA MONITOR·BINLOG MONITOR / REPLICATION MASTER ADMIN(MariaDB 10.5.2+) 안내, 그 표는 "읽을 수 없음" |
| Oracle | 뷰마다 `SELECT 1 FROM <뷰> WHERE 1 = 0` | 못 읽는 뷰 이름 + SELECT_CATALOG_ROLE |
| SQL Server | `HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW SERVER STATE')`(또는 2022의 VIEW SERVER PERFORMANCE STATE) | Always On이 켜져 있을 때만 필요. 가용성 표 "읽을 수 없음" + 안내 |

## Find Objects

SWT 메타데이터 검색(`plugins/com.seizia.chromite.ui.search/.../SearchMetadataPage.java:427-485` 조건, `SearchMetadataQuery.java` 실행)과
같은 모델 API를 쓴다: 연결마다 `DBSStructureAssistant#findObjectsByMask` → `DBSObjectReference#resolveObject` → `DBNModel#getNodeByObject`.

- 이름 조건: Contains(`%이름%`), Starts with(`이름%`), Like pattern(그대로). Case sensitive. SWT와 같다.
- 객체 종류: SWT는 연결 하나의 지원 종류를 그대로 보이지만, 여러 연결을 한 번에 찾으므로 묶음(Tables, Views, Procedures and functions,
  Columns, Other objects)으로 보이고 연결마다 지원 종류(`getSupportedObjectTypes`)를 종류 클래스로 나눈다
  (`DBSView` → Views, `DBSTable`/`DBSEntity` → Tables, `DBSProcedure`, `DBSEntityAttribute` → Columns).
- 연결: 활성 프로젝트의 연결 목록(체크 상자). 연결되지 않은 연결은 찾을 때 연결한다. 연결마다 최대 500개.
- 범위: 탭을 열 때 고른 노드의 스키마·데이터베이스(`DBSStructContainer`)를 `setParentObject`로 넘긴다(SWT와 같음). 그 연결에만 적용한다.
- 결과: 이름(탐색기 아이콘), 종류, 전체 이름, 연결. 두 번 누르거나 Enter를 누르면 Object Explorer를 보이고 그 노드까지 펼쳐 고른다
  (`ObjectExplorerPanel#revealNode`: 루트에서부터 대상의 조상 노드를 따라 내려가며, 읽지 않은 노드는 펼쳐 읽은 뒤 이어 간다).
  Object Explorer는 연 서버만 보이므로([swing-registered-servers.md](swing-registered-servers.md)), 결과의 서버가 아직 없으면 Registered Servers의
  "Object Explorer" 명령처럼 그 서버를 먼저 Object Explorer에 넣고(맨 아래) 서버 목록을 다시 읽은 뒤 이어서 펼친다.
  없는 서버의 결과에서 조용히 멈추지 않게 하려는 것이다. 되돌리기: `revealNode`의 서버 넣기 분기를 지우면 Object Explorer에 있는 서버의 결과만 펼친다.

## Altibase 잠금 (2026-11-08)

SWT 감사 3순위 "Altibase 잠금 탭"(D-db-specific A2). 세션 목록(Processes)은 원래부터 데이터 소스 어댑터(`AltibaseServerSessionManager`)로 나왔지만
`ServerAdminProfile`의 그 밖 분기가 잠금 관리자를 넣지 않아 Locks 탭이 비어 있었다. Altibase 분기를 더하고 잠금 관리자를 새로 만들었다.

- **DBeaver `AltibaseLockManager`를 쓰지 않은 이유**: 행 모양(`w_sid`·`h_sid` 열)과 상세 옵션 키(`wsid`/`hsid`)가 다른 네 DB와 달라 공통 화면 모양
  (`BlockingSessionLock`, `SessionLockItem`)과 맞지 않고, 대기 객체를 기다리는 트랜잭션의 모든 `V$LOCK` 행과 곱해 한 대기가 여러 줄이 된다.
  다른 DB처럼 `AbstractBlockingLockManager`를 따라 질의만 새로 썼다(DBeaver 파일은 고치지 않음).
- **대기**: `V$LOCK_WAIT`(트랜잭션 → 기다리는 트랜잭션)를 `V$SESSION.TRANS_ID`로 세션에 잇는다. 대기 대상·모드는 기다리는 트랜잭션의 승인되지 않은
  `V$LOCK` 행(`IS_GRANT = 0`)의 테이블과 `LOCK_DESC`, 없으면 `TX <번호>`. 문장은 `V$SESSION.CURRENT_STMT_ID`의 `V$STATEMENT.QUERY`.
  대기 시간은 `UNIX_DATE`(UTC)와 `V$STATEMENT.QUERY_START_TIME`(Unix 초)의 차이다(서버 시간대와 무관).
- **세션의 잠금**: 그 세션 트랜잭션의 `V$LOCK`(종류, 모드, 승인/대기, 트랜잭션·개수·테이블스페이스).
- **Kill Session**: DBeaver 세션·잠금 관리자와 같은 `ALTER DATABASE <db> SESSION CLOSE <id>`(SYS 권한).
- MANIFEST: `model.admin`이 `ext.altibase`와 그 부모 `ext.generic`을 요구한다(Swing 번들은 그대로).

### 확인 (2026-11-08, Docker Altibase 7.3.0.0.7 `chromite-test-altibase`, mydb)

열은 먼저 `isql`의 `DESC`로 확인했다(`V$LOCK`, `V$LOCK_WAIT`, `V$LOCK_STATEMENT`, `V$SESSION`, `V$STATEMENT`, `V$TRANSACTION`). 컨테이너 안 `/tmp/chromite-p3-oracle-altibase-admin/`의
두 `isql` 세션(CHR_ALTI)이 같은 테이블에 `LOCK TABLE … IN EXCLUSIVE MODE`(자료를 바꾸지 않음)를 걸어 하나가 기다리게 하고, 60초 뒤 둘 다 롤백했다.
Locks 탭(자동 새로 고침 10초): 나무 "Session 4 · blocking 1" → "Session 5 · waits for 4", 표 `5 | CHR_ALTI | 4 | CHR_ALTI | CHR_ALTI.CHR_ALTI_DEFAULT | X_LOCK | 11 | LOCK TABLE …`,
상세 "Locks of session 5 (waiting)" `CHR_ALTI.CHR_ALTI_DEFAULT TBL X_LOCK WAITING`, "Locks of blocking session 4" `… X_LOCK GRANTED`(`D:\Tools\_scratch\p3-oracle-altibase-admin\shots\` `alti-en-locks.png`).
Kill Session은 공용 컨테이너에서 실행하지 않았다(다른 작업의 세션을 끊지 않도록).

## 미룬 것

- **서버 대시보드**: 모델(`com.seizia.chromite.model.dashboard`)에는 대시보드 정의와 질의 등록만 있고, 값을 주기적으로 읽어 누적·차분하는
  부분(`DashboardUpdater`, `DashboardUpdateJob`)과 차트가 SWT 플러그인(`com.seizia.chromite.ui.dashboard`, SWT 차트)에 있다.
  옮기려면 갱신 로직을 모델로 옮기고 Swing 차트 라이브러리를 새로 들여야 해서 이번 범위에서 뺐다.
  2026-11-07 개요 탭으로 옮김: 갱신 로직은 이 번들의 `dashboard` 패키지, 차트는 Java2D로 직접 그림([swing-dashboard.md](swing-dashboard.md)).
- 세션의 실행 계획 탭(SWT `ExplainPlanViewer`): Swing 실행 계획 보기가 아직 없다.
- 잠금 그래프 그림(GEF): 나무로 대신했다.
- Find Objects의 "주석·정의에서도 찾기"(`setSearchInComments`, `setSearchInDefinitions`)와 검색 기록.
- Oracle은 시험 DB가 없어 실제로 확인하지 못했다(코드 경로는 SWT와 같은 모델 API). 2026-11-07 Oracle Free 23ai로 확인함(아래 "확인 (2026-11-07, PostgreSQL·Oracle 세션과 잠금)").
- Oracle 쿼리 취소(`ALTER SYSTEM CANCEL SQL`, 18c+): 요청 범위(Kill Session)에 없어 넣지 않았다.
- 권한이 없을 때 메뉴 자체를 끄는 것: 권한은 연결한 뒤에야 알 수 있어, 메뉴는 그대로 두고 탭 안의 명령 단추를 끈다.

## 확인 (2026-11-06)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swt,product-chromite-swing` → BUILD SUCCESS.
Swing 제품을 별도 작업 공간으로 띄우고 로컬 Docker DB(PostgreSQL 17, SQL Server 2022, MariaDB 11.8)의 `chr_admin_tools`에서 확인했다.
잠금 대기는 `docker exec`로 한 세션이 행을 갱신한 채 트랜잭션을 열어 두고(`pg_sleep`, `WAITFOR DELAY`, `SLEEP`), 다른 세션이 같은 행을 갱신·조회해 만들었다.

| 항목 | PostgreSQL | SQL Server | MariaDB |
|---|---|---|---|
| Processes: 세션 목록, 필터 체크 상자, 자동 새로 고침(10초) | 됨 | 됨 | 됨 |
| Processes: 찾기 칸, Current SQL | 됨 | — | 됨 |
| Locks: Blocking chains 나무(막는 세션 → 기다리는 세션), 잠금 표, 두 세션의 잠금 상세 | 됨 | 됨(새 관리자) | 됨(새 관리자) |
| Locks: Kill Session → 서버에서 막던 세션이 끝나고 기다리던 문장이 끝남 | 됨(`pg_stat_activity`로 확인) | 됨(`sys.dm_exec_requests`로 확인) | — |
| Find Objects: 이름 찾기, 결과 두 번 눌러 Object Explorer에서 고르기 | 됨(`orders` → Tables 아래 노드까지 펼침) | — | — |
| 연결 전 열기: 인증 창 → 연결 → 목록 | 됨 | 됨 | 됨 |

확인하지 못한 것: 다크 테마 화면, Processes 탭의 끝내기 단추(MariaDB Kill Query 등)와 여러 연결 Find Objects.
확인 도중 다른 프로그램의 Windows 보안(방화벽) 창이 화면 맨 앞을 차지해 앱에 키·마우스 입력을 보낼 수 없었다(그 창은 건드리지 않았다).
끝내기 단추는 잠금 탭의 Kill Session과 같은 `alterSession` 경로이고, 테마 전환은 Look and Feel 갱신에 맡겼다(표·나무의 렌더러는 `updateUI`에서 다시 만든다).

확인 중 고친 것: 연결에 실패한 뒤 자동 새로 고침이 간격마다 다시 연결(비밀번호 창)하던 것, 필터와 명령이 한 줄에 넘치던 도구 막대,
세션 표가 너무 낮던 분할, 잠금 상세 표를 넓게 쓰도록 바꾼 배치, PostgreSQL 뷰가 검색 종류("Table")로 보이던 결과 종류(노드 종류로 바꿈).

## 확인 (2026-11-07, PostgreSQL·Oracle 세션과 잠금)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → BUILD SUCCESS, 이어서 `java tools/jre/BundleJre.java windows-x64`.
Swing 제품을 별도 작업 공간으로 띄워(한국어 기본, `-nl en`, 라이트·다크) EDT 테스트 에이전트로 조작했다. 시험 DB는 PostgreSQL 17(`chr_sessions_locks`)과
Oracle Free 23ai(FREEPDB1, `CHR_LOCKS_APP.CHR_LOCKS_ITEMS`)다. 잠금 대기는 `docker exec`의 두(세) 세션으로 만들었다: 한 세션이 행을 갱신한 채
`pg_sleep`/`DBMS_SESSION.SLEEP`로 트랜잭션을 열어 두고, 다른 세션이 같은 행을 갱신한다.

| 항목 | PostgreSQL | Oracle |
|---|---|---|
| Locks: 나무에 머리 차단 세션 → 대기 세션(PostgreSQL은 3단 체인 holder → waiter → waiter2) | 됨 | 됨 |
| Locks: 대기 리소스가 객체로 풀림 | `chr_locks_items (row 0,15)`, `ShareLock (transactionid)` | `CHR_LOCKS_APP.CHR_LOCKS_ITEMS (file 24, block 1901, row 0)`, `enq: TX - row lock contention (TX Exclusive (X))` |
| Locks: 두 세션의 잠금 상세 | tuple WAITING/GRANTED, relation, transactionid | TX WAITING / TX BLOCKING, TM Row-X (SX) + 객체 이름 |
| Locks: 머리 차단 세션 Kill Session(확인 창) → 기다리던 문장이 끝남 | 됨(holder "terminating connection due to administrator command", waiter `UPDATE 1`) | 됨(holder ORA-03113, waiter `1 row updated`) |
| Processes: Wait, Blocked By, Transaction Age, Client Host | 됨 | Blocked By, Transaction Age 됨(Event, Remote Host·Program은 그대로) |
| Processes: Cancel Query(대기 문장 취소) → Terminate Session(차단 세션) | 됨(waiter "canceling statement due to user request", holder 끝남) | — |
| 권한 안내: 일반 로그인 | `chr_locks_viewer`: 자기 세션만 + 안내 | `chr_locks_nopriv`(CREATE SESSION만): 못 읽는 뷰 8개와 ALTER SYSTEM 없음 안내, 세션·잠금을 읽지 않고 Kill/Disconnect 꺼짐 |

화면: `D:\Tools\_scratch\sessions-locks\run\shots\`(작업 공간 밖의 확인용 파일).

## 확인 (2026-11-07, 공간 사용량·서버 로그)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → BUILD SUCCESS, 이어서 `java tools/jre/BundleJre.java windows-x64`.
Swing 제품을 별도 작업 공간으로 띄워(한국어 기본, `-nl en`, 라이트·다크) EDT 테스트 에이전트로 조작했다. 시험 객체는 `chr_space_` 접두어다:
SQL Server·PostgreSQL·MariaDB의 `chr_space_logs` 데이터베이스(`chr_space_big`, `chr_space_lob`/`chr_space_toast`/`chr_space_aria`, `chr_space_small`),
Oracle FREEPDB1의 `CHR_SPACE_APP.CHR_SPACE_BIG`·`CHR_SPACE_LOB`(SecureFile CLOB). 낮은 권한 로그인은 `chr_space_viewer`(SQL Server: 데이터베이스 사용자만,
PostgreSQL: CONNECT만, MariaDB: 테이블 하나의 SELECT만, Oracle: CREATE SESSION만)와 Oracle `CHR_SPACE_APP`(자기 스키마 소유자)이다.
공유 시험 서버의 설정은 바꾸지 않았다. 로그 파일이 있는 경우를 보려고 로컬 이미지로 버리는 컨테이너 둘을 따로 띄웠다:
PostgreSQL 17(`logging_collector=on`, `log_destination=stderr,csvlog,jsonlog`)과 MariaDB 11(`slow_query_log=1`, `log_output=TABLE`).

| 항목 | SQL Server 2022 | PostgreSQL 17 | MariaDB 11.8 | Oracle Free 23ai |
|---|---|---|---|---|
| 데이터베이스 크기 | 47개 데이터베이스, 데이터·로그 사용량(`chr_space_logs` 데이터 200 MB 중 161 MB, 로그 재사용 대기 OLDEST_PAGE) | 46개, 크기·인코딩·테이블스페이스 | 39개 스키마, 데이터·인덱스·빈 공간 | FREEPDB1 한 행(데이터 파일·사용·빈 공간·임시) |
| 파일·테이블스페이스 | 파일마다 사용·빈 공간·자동 증가(64 MB씩, 10%씩)·최대 크기(무제한, 2 TB) | pg_default, pg_global 크기 | — | 테이블스페이스 5개(TEMP 포함)·데이터 파일 5개(증가 단위 10 MB, 최대 32 TB) |
| 큰 테이블 | `chr_space_big` 행 100,000, 데이터 78 MB, 인덱스 3.8 MB / `chr_space_lob` LOB가 데이터로 | 스키마 public: 데이터·TOAST(26 MB)·인덱스·추정 행 | 범위 `chr_space_logs`: InnoDB·Aria, DATA_FREE 6 MB | 소유자 CHR_SPACE_APP: 데이터 37 MB·인덱스 5 MB, LOB 24 MB |
| 낮은 권한 | 다른 데이터베이스는 열 수 있는 것만 사용량, 큰 테이블 0개 + 두 안내 | pg_global 크기 비움 + 안내 | 스키마 2개·테이블 1개 + 안내 | 데이터베이스·테이블스페이스·데이터 파일 "사용할 수 없음", 자기 스키마 테이블만 + 안내 |
| 로그 | 오류 로그 500개 쪽 → 더 읽기 548개, 검색 `chr_space`, 보관 #1, 에이전트 로그 + 수준 "경고" 4개, 검색 결과 없음 | 수집기 꺼진 시험 서버: 안내. 수집기 서버: CSV·JSON·텍스트 각 형식, 500개 쪽·더 읽기, ERROR·FATAL·PANIC 12개, 2.4 MB 파일 처음의 항목까지 검색, 직접 지정 범위(20:52:00–20:52:05) | MariaDB: 오류 로그 안내, 느린 쿼리 꺼짐 안내. TABLE 서버: 느린 쿼리 6개(쿼리 시간·검사한 행 상세) | 경고 로그 31개(전체), 수준 "보통" 18개, "ORA- 오류" 2개, 검색 `resize` 11개 |
| 낮은 권한(로그) | 권한 안내 + 서버 메시지(27219) | `chr_space_viewer`: 함수 권한 안내. pg_monitor + `pg_read_binary_file` EXECUTE: 읽힘 | (MariaDB 안내는 권한과 무관) | ORA-00942 + SELECT ANY DICTIONARY 안내 |
| 복사 | "정확한 값 복사" → 머리글과 바이트 수(312475648 등) 탭 구분 | | | |

화면: `D:\Tools\_scratch\admin-space-logs\run\shots\`(작업 공간 밖의 확인용 파일; `space-*`, `logs-*`의 `ko`/`en`, `light`/`dark`).

확인 중 고친 것: 에이전트 로그에서 검색 결과가 없으면 `xp_readerrorlog`가 결과 집합을 돌려주지 않아 "결과 집합 없음" 오류가 나던 것(`execute()`로 바꿈),
PostgreSQL 파일 목록의 수정 시각이 클라이언트 시간대로 보이던 것(log_timezone으로), 좁은 문서에서 로그 도구 막대의 검색 칸이 잘리던 것(두 줄로),
로그 표의 시간·수준 열이 잘리던 것(`AdminGridTable#doLayout`), PostgreSQL·Oracle 권한 안내 문구(pg_read_server_files는 EXECUTE를 주지 않음,
SELECT_CATALOG_ROLE에는 `V$DIAG_ALERT_EXT`가 없음: 둘 다 실제 서버에서 확인).

확인하지 못한 것: MySQL 8.0.22+의 `performance_schema.error_log`(시험 서버가 MariaDB뿐이고 MySQL 이미지를 내려받지 않았다), Azure SQL Database 엔진 에디션 분기,
PostgreSQL 10 이전·Oracle 11g 이전 분기, 열 머리글을 눌러 정렬하는 것(표준 `TableRowSorter`; 에이전트에 머리글 누르기가 없다), 크기 셀의 도구 설명(정확한 바이트).

### 미룬 것 (공간 사용량·서버 로그)

- MySQL의 InnoDB 공유 테이블스페이스·undo·임시 파일(`information_schema.FILES`)과 일반 쿼리 로그(general_log 표).
- SQL Server의 파일 그룹별 합계, 인덱스별 크기, 데이터베이스 사이 큰 테이블 비교(지금은 데이터베이스 하나씩).
- PostgreSQL 다른 데이터베이스의 큰 테이블(그 데이터베이스에 연결해야 한다), 인덱스별 크기, 부풀림 추정(축 4 유지 보수).
- 로그 내보내기(파일로 저장), 자동 새로 고침(tail), 로그 돌리기(쓰기 작업이라 이번 범위 밖).
- AI DBA 도구로 내보내기(`ai-agent.md`): 모델 방언은 화면과 무관하게 만들어 두어 그대로 쓸 수 있다.

## 확인 (2026-11-07, 유지 관리·복제 상태)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → BUILD SUCCESS, 이어서 `java tools/jre/BundleJre.java windows-x64`.
Swing 제품을 별도 작업 공간으로 띄워(한국어 기본, `-nl en`, 라이트·다크) EDT 테스트 에이전트로 조작했다. 시험 객체는 `chr_maint_` 접두어다:
PostgreSQL·SQL Server·MariaDB의 `chr_maint_repl` 데이터베이스(`chr_maint_items`(자동 VACUUM 끔, 60% 삭제), `chr_maint_frag`(NEWID 키로 조각화 / 90% 삭제한 InnoDB),
`chr_maint_myisam`, `chr_maint_small`), Oracle FREEPDB1의 `CHR_MAINT_APP`(통계가 낡은 `CHR_MAINT_ORDERS`, UNUSABLE 인덱스, 무효 뷰·프로시저 3개).
낮은 권한 로그인은 `chr_maint_viewer`(PostgreSQL: CONNECT와 작은 테이블 SELECT, SQL Server: 데이터베이스 사용자만, MariaDB: 테이블 하나의 SELECT, Oracle: CREATE SESSION만)다.
복제는 공유 시험 서버에 구성되어 있지 않아 "구성되지 않음" 길을 확인했고, 로컬 이미지(postgres:17)로 버리는 주·대기 서버 한 쌍(포트 55491/55492, 물리 슬롯 둘, 게시 하나)을
띄워 확인한 뒤 지웠다(컨테이너·볼륨·네트워크).

| 항목 | 전 | 작업 | 후 |
|---|---|---|---|
| PostgreSQL 죽은 행 | `chr_maint_items` 죽은 행 160,000(66.7%), 분석 뒤 변경 160,000 | 탭의 행에서 ANALYZE → VACUUM(VERBOSE, ANALYZE) | 분석 뒤 변경 0, 죽은 행 0, 마지막 VACUUM·ANALYZE 시각 |
| PostgreSQL 부풀림(추정) | 352 KB(1%, ANALYZE 전 통계) → ANALYZE 뒤 20.1 MB(60%) | 노드 메뉴에서 VACUUM FULL(경고 줄 확인) | 크기 33.2 MB → 13.3 MB, 부풀림 144 KB(1%) |
| PostgreSQL 인덱스 | — | 인덱스 노드에서 REINDEX (VERBOSE) INDEX CONCURRENTLY, CLUSTER … USING | "was reindexed", "clustering … 80000 row versions", 상태에 "CLUSTER 인덱스" |
| PostgreSQL 취소 | 다른 세션이 테이블을 읽는 트랜잭션을 열어 둠 | VACUUM FULL 실행(잠금 대기) → 취소 | 4.7초 뒤 취소, `pg_stat_activity`에 VACUUM 없음 |
| SQL Server 조각화 | PK 99.1%(3,223페이지), 비클러스터형 98.5% | PK REBUILD WITH (ONLINE = ON), 비클러스터형 REORGANIZE, UPDATE STATISTICS … WITH FULLSCAN | 조각화 목록이 빔(5% 미만), 테이블 상태 "1,575페이지 중 1.7%, 권장: 필요 없음" |
| SQL Server CHECKDB | 마지막 정상 CHECKDB "없음" | 데이터베이스 무결성 행에서 DBCC CHECKDB (N'chr_maint_repl') WITH NO_INFOMSGS | "2026-11-06 22:29:17"(서버 시각) |
| SQL Server 취소·잠금 | 다른 세션이 TABLOCKX로 테이블을 잠금 | 대화상자를 엶(상태: "지금은 읽을 수 없음 … 잠겨 있습니다") → 모든 인덱스 다시 작성 → 취소 | 4.7초 뒤 취소, `sys.dm_exec_requests`에 ALTER INDEX 없음 |
| MariaDB 빈 공간 | `chr_maint_frag` 데이터 59.1 MB, DATA_FREE 5 MB(7.8%) | OPTIMIZE TABLE | 결과 표 "note: Table does not support optimize, doing recreate + analyze instead", "status OK"; 데이터 7.5 MB, DATA_FREE 0 |
| MariaDB 데이터베이스 | — | 데이터베이스 노드에서 CHECK TABLE … MEDIUM, REPAIR TABLE(테이블 3개 한 문장) | 테이블마다 Msg_type/Msg_text 행 |
| Oracle 무효 개체 | 무효 3개(고르면 `4/10 ERROR: PL/SQL: ORA-00904` 상세) | 고칠 수 있는 프로시저 ALTER … COMPILE, 깨진 프로시저 컴파일(오류 표), 스키마 COMPILE_SCHEMA | 3 → 2(남은 두 개의 오류 표) |
| Oracle 통계·인덱스 | `CHR_MAINT_ORDERS` 오래됨: 예, 인덱스 UNUSABLE | GATHER_TABLE_STATS(자동 표본, 인덱스 포함), ALTER INDEX … REBUILD ONLINE | 오래된 통계 목록에서 빠짐, 사용할 수 없는 인덱스 목록이 빔 |
| 복제 PostgreSQL | 시험 서버: 주 서버, "구성되지 않음", 대기 서버 표 문구 | 버리는 주·대기 쌍, 대기 서버에서 재생을 멈추고 주 서버에서 갱신 | 주: 대기 서버 1(streaming, async, 지연 16.4 MB, replay 65 s), 슬롯 2(쉬는 슬롯이 WAL 24.1 MB 보존), 게시 1. 대기: WAL 수신기 streaming, 받은·재생한 위치 |
| 복제 그 밖 | — | — | SQL Server: Always On·복제 "구성되지 않음". MariaDB: "복제본 아님", 바이너리 로그 꺼짐. Oracle: PRIMARY, Data Guard "구성되지 않음", 로컬 보관 대상 하나, 통계 문구 |
| 권한 안내 | — | 낮은 권한 로그인 | PostgreSQL: 소유자 아님 → 실행 꺼짐 + MAINTAIN/pg_maintain 안내, 복제 pg_monitor 안내. SQL Server: VIEW DATABASE STATE 안내(목록), CHECKDB "sysadmin 또는 db_owner"(실행 꺼짐). MariaDB: ANALYZE "SELECT와 INSERT" 거부, CHECK는 됨, 복제 REPLICA MONITOR 안내. Oracle: DBA 뷰 4개·V$ 3개 안내 |

화면: `D:\Tools\_scratch\admin-maintenance-replication\shots\`(작업 공간 밖의 확인용 파일; `maint-*`, `dialog-*`, `repl-*`의 `ko`/`en`, `light`/`dark`).

확인 중 고친 것: 부풀림 질의의 열 이름 오류(`stat_cols`), SQL Server 표가 잠겨 있으면 대화상자의 상태 읽기가 끝나지 않던 것(`SET LOCK_TIMEOUT`), MariaDB `ENABLED_ROLES`가
역할이 없어도 행 하나를 돌려 권한 확인을 건너뛰던 것(`COUNT(ROLE_NAME)`), REPAIR 경고 문구(InnoDB도 OK라고 답함), 빈 결과 표로 탭을 넘기던 것, Oracle 통계가 없는 테이블의 "오래됨" 값, PostgreSQL 지연 표시(초).

확인하지 못한 것: MySQL(MariaDB만 시험; `SHOW REPLICA STATUS`·`SHOW BINARY LOG STATUS` 분기), MariaDB·MySQL 실제 복제본, Oracle Data Guard 대기 DB, SQL Server Always On·트랜잭션 복제(시험 서버에 없음, 이미지를 내려받지 않음),
PostgreSQL 17 이전 권한 분기(데이터베이스 소유자)와 12 이전 CONCURRENTLY 끔, PostgreSQL 논리 복제 구독, 다른 PostgreSQL 데이터베이스 노드에서 연 대화상자, 오래 걸리는 DBCC CHECKDB 자체의 취소(작은 시험 데이터베이스는 1초 안에 끝나 잠금 대기로 SQL Server 취소 경로를 확인).

### 미룬 것 (유지 관리·복제)

- PostgreSQL 인덱스 부풀림 추정, pgstattuple을 쓰는 정확한 부풀림, VACUUM의 다른 옵션(DISABLE_PAGE_SKIPPING, SKIP_LOCKED, INDEX_CLEANUP, PARALLEL).
- Oracle 분할 인덱스 파티션의 다시 작성(`REBUILD PARTITION`), 테이블 구조 검증(VALIDATE STRUCTURE), 세그먼트 축소(SHRINK SPACE).
- SQL Server 파티션 단위 다시 작성, 인덱스 옵션(FILLFACTOR, MAXDOP, RESUMABLE), CHECKDB 진행률(`sys.dm_exec_requests.percent_complete`) 표시.
- 복제: SQL Server 구독자·에이전트 상태, MySQL 그룹 복제·Galera, PostgreSQL 슬롯 지우기 같은 쓰기 명령(읽기 전용 범위 밖).
- AI DBA 도구로 내보내기(`ai-agent.md`): 모델 방언은 화면과 무관하게 만들어 두어 그대로 쓸 수 있다.
- 회귀 테스트는 요청이 없어 작성·실행하지 않았다.

## 변경 이력

- 2026-11-06 최초 작성: Activity Monitor(Processes, Locks), Find Objects, `com.seizia.chromite.model.admin` 번들(SQL Server·MySQL 잠금 관리자).
- 2026-11-06 Registered Servers와 합침: Find Objects 결과의 서버가 Object Explorer에 없으면 먼저 넣고 펼친다(연 서버만 보이는 Object Explorer).
- 2026-11-06 테마 전환 뒤 Processes의 찾기 칸이 흰색, Only my sessions 글자가 어두운 색으로 남던 문제: 첫 읽기 뒤에 붙이는 두 컴포넌트를
  붙일 때 현재 Look and Feel로 다시 설치함.
- 2026-11-07 서버 대시보드를 Activity Monitor의 첫 탭(개요)으로 더함, 탭 번호가 바뀜(개요 0, Processes 1, Locks 2) ([swing-dashboard.md](swing-dashboard.md)).
- 2026-11-07 PostgreSQL·Oracle 세션·잠금(DB 관리 축 2): 세션에 대기·막는 세션·트랜잭션 경과, 잠금에 `pg_blocking_pids`/`BLOCKING_SESSION` 기반 관리자와 객체로 풀린 대상,
  로그인 권한 확인과 안내 줄, Oracle 끝내기의 SERIAL# 보존, PostgreSQL 끝내기 결과 확인.
- 2026-11-07 Disk Usage·Server Logs(DB 관리 축 3·5): Activity Monitor의 4·5번째 탭, `model.admin`의 `space`·`logs` 패키지와 DB별 방언 (SQL Server·PostgreSQL·MySQL/MariaDB·Oracle), 권한 확인과 안내, 쪽 단위 로그 읽기, `AdminGridTable`(단위 표시·정확한 바이트·복사).
- 2026-11-07 Maintenance·Replication(DB 관리 축 4·6): Activity Monitor의 6·7번째 탭, 유지 관리 대화상자(권한 확인, 미리 보기, 확인, 별도 자동 커밋 연결, 진행·취소)와
  Object Explorer의 Maintenance… 명령, `model.admin`의 `maintenance`·`replication` 패키지와 DB별 방언(SQL Server·PostgreSQL·MySQL/MariaDB·Oracle), `AdminGrid`·`AdminGridTabs`.
- 2026-11-08 Altibase 잠금 탭(`AltibaseBlockingLockManager`, `ServerAdminProfile`의 Altibase 분기), Docker Altibase 7.3에서 확인.
