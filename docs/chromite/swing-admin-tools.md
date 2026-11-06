# Swing 관리 도구 (Activity Monitor, Find Objects)

## 목적

DBeaver(SWT)의 서버 관리 도구를 Swing 프런트엔드로 옮긴다. 배치는 SSMS를 따른다.

- **Activity Monitor**: 서버 세션 목록, 세션의 현재 SQL과 속성, 세션·쿼리 끝내기, 자동 새로 고침, 필터.
  같은 문서 탭의 **Locks** 탭은 잠금을 기다리는 세션과 막는 세션(blocking chain), 각 세션의 잠금을 보인다.
- **Find Objects**: 여러 연결에서 이름으로 테이블·뷰·프로시저·열을 찾고, 결과를 Object Explorer에서 고른다.
- **서버 대시보드**: 처음에는 미뤘고(아래 "미룬 것"), 뒤에 Activity Monitor의 첫 탭(개요)으로 옮겼다([swing-dashboard.md](swing-dashboard.md)).

## 여는 곳

| 명령 | 위치 | 동작 |
|---|---|---|
| Activity Monitor | Tools 메뉴, Object Explorer 오른쪽 클릭 메뉴 | 선택한 노드의 연결(Tools 메뉴는 없으면 활성 편집기의 연결)의 Activity Monitor를 Processes 탭으로 연다. 이미 열려 있으면 그 탭을 보인다. |
| Locks | 위와 같음 | 같은 Activity Monitor를 Locks 탭으로 연다. |
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
    └ AdminConnection ────── 두 탭이 함께 쓰는 서버 연결(격리된 실행 컨텍스트 하나)
  ObjectSearchPanel (ToolDocument) ── Find Objects 탭
  ObjectListTable ── @Property 열로 모델 객체 목록을 보이는 표
  AutoRefreshBox ─── 자동 새로 고침 간격 콤보
  AccessNoticeBar ── Processes·Locks 탭 위의 권한 안내 줄
        │
        ▼
com.seizia.chromite.model.admin (새 모델 번들, UI 없음)
  ServerAdminProfile ── DB별 세션 관리자·필터·명령·잠금 관리자
  ServerAdminMessages ─ 필터·명령 이름(NLS)
  ServerAdminAccess, ActivityAccessMessages ─ 로그인 권한 확인 결과와 안내 문구(NLS)
  AbstractBlockingLockManager, BlockingSessionLock, SessionLockItem
  mssql/SQLServerLockManager, mysql/MySQLLockManager
  postgresql/PostgreActivitySessionManager(+PostgreActivitySession), postgresql/PostgreBlockingLockManager
  oracle/OracleActivitySessionManager(+OracleActivitySession), oracle/OracleBlockingLockManager(+OracleBlockingLock)
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

## 변경 이력

- 2026-11-06 최초 작성: Activity Monitor(Processes, Locks), Find Objects, `com.seizia.chromite.model.admin` 번들(SQL Server·MySQL 잠금 관리자).
- 2026-11-06 Registered Servers와 합침: Find Objects 결과의 서버가 Object Explorer에 없으면 먼저 넣고 펼친다(연 서버만 보이는 Object Explorer).
- 2026-11-06 테마 전환 뒤 Processes의 찾기 칸이 흰색, Only my sessions 글자가 어두운 색으로 남던 문제: 첫 읽기 뒤에 붙이는 두 컴포넌트를
  붙일 때 현재 Look and Feel로 다시 설치함.
- 2026-11-07 서버 대시보드를 Activity Monitor의 첫 탭(개요)으로 더함, 탭 번호가 바뀜(개요 0, Processes 1, Locks 2) ([swing-dashboard.md](swing-dashboard.md)).
- 2026-11-07 PostgreSQL·Oracle 세션·잠금(DB 관리 축 2): 세션에 대기·막는 세션·트랜잭션 경과, 잠금에 `pg_blocking_pids`/`BLOCKING_SESSION` 기반 관리자와 객체로 풀린 대상,
  로그인 권한 확인과 안내 줄, Oracle 끝내기의 SERIAL# 보존, PostgreSQL 끝내기 결과 확인.
