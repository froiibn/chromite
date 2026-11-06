# Swing 관리 도구 (Activity Monitor, Find Objects)

## 목적

DBeaver(SWT)의 서버 관리 도구를 Swing 프런트엔드로 옮긴다. 배치는 SSMS를 따른다.

- **Activity Monitor**: 서버 세션 목록, 세션의 현재 SQL과 속성, 세션·쿼리 끝내기, 자동 새로 고침, 필터.
  같은 문서 탭의 **Locks** 탭은 잠금을 기다리는 세션과 막는 세션(blocking chain), 각 세션의 잠금을 보인다.
- **Find Objects**: 여러 연결에서 이름으로 테이블·뷰·프로시저·열을 찾고, 결과를 Object Explorer에서 고른다.
- **서버 대시보드**: 이번에는 옮기지 않았다(아래 "미룬 것").

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
        │
        ▼
com.seizia.chromite.model.admin (새 모델 번들, UI 없음)
  ServerAdminProfile ── DB별 세션 관리자·필터·명령·잠금 관리자
  ServerAdminMessages ─ 필터·명령 이름(NLS)
  AbstractBlockingLockManager, BlockingSessionLock, SessionLockItem
  mssql/SQLServerLockManager, mysql/MySQLLockManager
        │
        ▼
DBeaver 모델 (그대로 재사용)
  DBAServerSessionManager(+SQL, +DetailsProvider), DBAServerLockManager, LockGraphManager,
  PostgreSessionManager, SQLServerSessionManager, MySQLSessionManager, OracleServerSessionManager,
  PostgreLockManager, OracleLockManager, DBSStructureAssistant, DBNModel
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

SWT `SessionManagerViewer`(`plugins/org.jkiss.dbeaver.ui.editors.session/.../SessionManagerViewer.java:111-239` 화면 구성,
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

### DB별 필터와 명령 (`ServerAdminProfile`)

| DB | 세션 관리자 | 필터(기본값) | 명령 | 사용자 속성 |
|---|---|---|---|---|
| PostgreSQL | 데이터 소스 어댑터(`PostgreSessionManager`) | Show idle connections(켜짐) | Terminate Session(`pg_terminate_backend`), Cancel Query(`OPTION_QUERY_CANCEL`, `pg_cancel_backend`) | `usename` |
| SQL Server | `SQLServerSessionManager` | Only connections(켜짐) | Terminate Session(`KILL`) | `login_name` |
| MySQL·MariaDB | `MySQLSessionManager` | Hide sleeping(꺼짐), Show performance info(꺼짐, sys 스키마가 있을 때) | Kill Session(`KILL CONNECTION`), Kill Query(`KILL QUERY`, 쿼리가 있는 세션만) | `User` |
| Oracle | `OracleServerSessionManager` | Show background, Show inactive(꺼짐) | Kill Session, Disconnect Session(확인 창의 Immediate 체크) | `USERNAME` |
| 그 밖 | 데이터 소스 어댑터 | 없음 | Terminate Session(관리자의 `getTerminateOptions()`) | 없음 |

필터 값은 드라이버마다 이 번들의 설정 저장소(`activityMonitor.sessions.filter.<드라이버>.<옵션>`)에, 자동 새로 고침 간격은
`activityMonitor.sessions.refreshSeconds` / `activityMonitor.locks.refreshSeconds`에 둔다.

### 서버 연결 (`AdminConnection`)

SWT는 읽을 때마다 격리된 실행 컨텍스트를 열고 닫는다(`SessionTable.java:147`). 자동 새로 고침마다 서버 연결을 새로 만들면 비싸고
세션 목록에 매번 새 세션이 보이므로, Activity Monitor 탭 하나가 격리된 컨텍스트 하나를 열어 두고 탭을 닫을 때 닫는다.
연결 설정이 "단일 연결"이면 SWT처럼 기본 컨텍스트를 쓴다. 작업은 차례로 실행하고(같은 JDBC 연결), 오류가 나면 컨텍스트를 닫아
다음 작업에서 다시 연다(자기 세션을 끝낸 경우 등). 연결되지 않은 연결은 첫 작업에서 연결한다(SQL 편집기와 같은 `DBUtils.initDataSource`).

## Activity Monitor — Locks (잠금)

SWT `LockManagerViewer`(`plugins/org.jkiss.dbeaver.ui.editors.locks/.../LockManagerViewer.java`: 잠금 표, 그래프, 대기·보유 상세 표,
"Kill waiting session")와 `PostgreLockEditor`/`OracleLockEditor`(상세 표 옵션)를 옮겼다.

- **Blocking chains(나무)**: GEF 그래프 대신 나무다. 뿌리는 남을 막기만 하는 세션, 자식은 그 세션을 기다리는 세션이다.
  관계는 잠금 관리자가 `LockGraphManager#buildGraphs`로 채운 `getHoldBy()`/`waitThis()`를 그대로 쓴다.
- **잠금 표**: `DBAServerLockManager#getLocks`의 행(열은 잠금 클래스의 `@Property`). 나무와 표의 선택은 서로 따라간다.
- **상세 표 둘**: 고른 세션과 그 세션을 막는 세션의 잠금(`getLockItems`, 옵션은 `ServerAdminProfile#getLockItemOptions`).
- **Kill Session**: 고른 행의 세션을 확인 후 끝낸다(`alterSession`).

| DB | 잠금 관리자 | 대기 목록 | 세션의 잠금 |
|---|---|---|---|
| PostgreSQL | `PostgreLockManager`(DBeaver) | `pg_locks` 충돌 표 | `pg_locks` |
| Oracle | `OracleLockManager`(DBeaver) | DBeaver 질의 | DBeaver 질의 |
| SQL Server | `SQLServerLockManager`(새로 만듦) | `sys.dm_exec_requests.blocking_session_id` | `sys.dm_tran_locks`(OBJECT 잠금은 객체 이름) |
| MySQL·MariaDB | `MySQLLockManager`(새로 만듦) | `sys.innodb_lock_waits`(sys 스키마 없으면 `INNODB_LOCK_WAITS`) | MariaDB·MySQL 5.7: `INNODB_LOCKS`, MySQL 8: `performance_schema.data_locks` |

새 관리자는 `AbstractBlockingLockManager`가 공통 부분(뿌리 행 더하기, 그래프 만들기, 상세 옵션)을 맡고 하위 클래스는 질의만 한다.
행 모양(`BlockingSessionLock`: 세션, 사용자, Blocked By, 대기 자원·모드·시간, 문장)은 `PostgreLock`과 같은 방식이고,
열 이름은 `OSGI-INF/l10n/bundle.properties`의 `meta.*` 키다(DBeaver 모델 규칙).
MySQL/MariaDB는 InnoDB 행 잠금만 보인다. 메타데이터 잠금(ALTER TABLE 대기 등)은 세션 목록의 State에서 본다.

## Find Objects

SWT 메타데이터 검색(`plugins/org.jkiss.dbeaver.ui.search/.../SearchMetadataPage.java:427-485` 조건, `SearchMetadataQuery.java` 실행)과
같은 모델 API를 쓴다: 연결마다 `DBSStructureAssistant#findObjectsByMask` → `DBSObjectReference#resolveObject` → `DBNModel#getNodeByObject`.

- 이름 조건: Contains(`%이름%`), Starts with(`이름%`), Like pattern(그대로). Case sensitive. SWT와 같다.
- 객체 종류: SWT는 연결 하나의 지원 종류를 그대로 보이지만, 여러 연결을 한 번에 찾으므로 묶음(Tables, Views, Procedures and functions,
  Columns, Other objects)으로 보이고 연결마다 지원 종류(`getSupportedObjectTypes`)를 종류 클래스로 나눈다
  (`DBSView` → Views, `DBSTable`/`DBSEntity` → Tables, `DBSProcedure`, `DBSEntityAttribute` → Columns).
- 연결: 활성 프로젝트의 연결 목록(체크 상자). 연결되지 않은 연결은 찾을 때 연결한다. 연결마다 최대 500개.
- 범위: 탭을 열 때 고른 노드의 스키마·데이터베이스(`DBSStructContainer`)를 `setParentObject`로 넘긴다(SWT와 같음). 그 연결에만 적용한다.
- 결과: 이름(탐색기 아이콘), 종류, 전체 이름, 연결. 두 번 누르거나 Enter를 누르면 Object Explorer를 보이고 그 노드까지 펼쳐 고른다
  (`ObjectExplorerPanel#revealNode`: 루트에서부터 대상의 조상 노드를 따라 내려가며, 읽지 않은 노드는 펼쳐 읽은 뒤 이어 간다).

## 미룬 것

- **서버 대시보드**: 모델(`org.jkiss.dbeaver.model.dashboard`)에는 대시보드 정의와 질의 등록만 있고, 값을 주기적으로 읽어 누적·차분하는
  부분(`DashboardUpdater`, `DashboardUpdateJob`)과 차트가 SWT 플러그인(`org.jkiss.dbeaver.ui.dashboard`, SWT 차트)에 있다.
  옮기려면 갱신 로직을 모델로 옮기고 Swing 차트 라이브러리를 새로 들여야 해서 이번 범위에서 뺐다.
- 세션의 실행 계획 탭(SWT `ExplainPlanViewer`): Swing 실행 계획 보기가 아직 없다.
- 잠금 그래프 그림(GEF): 나무로 대신했다.
- Find Objects의 "주석·정의에서도 찾기"(`setSearchInComments`, `setSearchInDefinitions`)와 검색 기록.
- Oracle은 시험 DB가 없어 실제로 확인하지 못했다(코드 경로는 SWT와 같은 모델 API).

## 확인 (2026-11-06)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce,product-chromite-swing` → BUILD SUCCESS.
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

## 변경 이력

- 2026-11-06 최초 작성: Activity Monitor(Processes, Locks), Find Objects, `com.seizia.chromite.model.admin` 번들(SQL Server·MySQL 잠금 관리자).
