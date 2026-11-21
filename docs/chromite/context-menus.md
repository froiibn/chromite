# 오른쪽 클릭 메뉴: 해당하는 명령만 보이고, 지금 할 수 없으면 꺼짐

## 목적

사용자 요청(2026-11-16, 앱 변경 #137):

- "개체 탐색기에서 노드를 우클릭 하면 나타나는 기능들이 실제 그 노드에서 수행 가능한 작업만 나타나는건가?"
  (대부분 늘 보이고 못 하면 흐리게 보인다고 답하자) "진행한다. 노드에서 수행 가능한 목록만 나타나게 하고 작업은 가능하지만 현재 조건에 따라
  불가능한건 비활성화로 처리한다"
- "개체 탐색기를 언급했는데 다른 부분도 전반적으로 점검해본다" (다른 오른쪽 클릭 메뉴도 같은 규칙으로 점검)
- 테이블 메뉴에 Connect(꺼짐), Disconnect, Disconnect Others, Disconnect All, Reconnect, Invalidate/Reconnect, Activity Monitor, Locks, Server Dashboard,
  Disk Usage, Server Logs, Maintenance, Replication, New Connection, Find Objects, Compare Schemas가 남아 있고, 규칙대로라면 테이블이 아니라 연결·서버의
  명령이니 연결 노드에서만 보일지 묻자: "연결 노드에서만"

전에는 개체 탐색기의 오른쪽 클릭 메뉴가 약 50개 항목의 고정 목록이었다. 대부분 늘 보이고, 그 노드에서 할 수 없으면 꺼질 뿐이었다
(예: SQL Server "파일 그룹" 폴더에서 쓸 수 있는 명령은 "새 파일 그룹…" 하나인데 New View, New Procedure, New Login, Design, Modify 등이 흐리게 함께 보임).

## 규칙

명령마다 두 답을 한곳에서 정한다.

1. **이 종류에 해당하는가 → 보임.** 노드가 무엇인지로만 정해진다: 노드 형식(빈 곳, 연결, 데이터베이스, 스키마, 객체 폴더와 그 폴더가 담는 것, 테이블,
   뷰, 열, 인덱스, 프로시저, "더 읽기" 행 …), 객체 클래스, DB의 종류·버전·에디션·드라이버 기능(DB에 아예 없는 명령은 숨김).
2. **지금 할 수 있는가 → 켜짐.** 노드가 다른 종류가 되지 않고도 바뀔 수 있는 것: 연결 여부, 읽기 전용 연결, 객체의 지금 상태(이미 사용, 이미 읽기 전용,
   실행 중), 형제는 지울 수 있는데 지울 수 없는 시스템 객체, 종류가 섞인 여러 개 선택, 아직 읽는 중.

따라서:

- 같은 DB의 같은 종류 노드 둘은 같은 항목을 보이고, 꺼진 것만 다르다.
- **연결·서버 명령은 연결 노드에서만 보인다**(사용자 결정 "연결 노드에서만"): 연결 상태 명령(Connect, Disconnect, Disconnect Others, Reconnect,
  Invalidate/Reconnect)과 서버 보기(Activity Monitor, Locks, Server Dashboard, Disk Usage, Server Logs, Maintenance, Replication). 프로젝트 명령
  (New Connection, Disconnect All)은 빈 곳과 연결 노드(여러 개를 골라도 모두 연결이면)에서, Find Objects와 Compare Schemas는 그곳과 데이터베이스·스키마
  노드에서 보인다(고른 데이터베이스·스키마에서 시작하므로). 이 트리에는 연결 위의 노드(연결 폴더)가 없다: 최상위는 이 창에 연 서버의 평평한 목록이다.
  이 항목들은 메뉴에만 숨고 켜짐은 그대로라, 도구 막대의 Connect·Disconnect 단추와 키는 연결 아래 어느 노드에서나 전처럼 그 연결에 작동한다
  (`ContextMenus.setApplies`). 그래서 이 항목들에는 "전에 쓸 수 있던 것은 사라지지 않는다"는 처음 조건이 이 결정으로 풀린다: 테이블·폴더·데이터베이스
  등 연결 아래 노드에서 위 항목이 메뉴에서 빠진다(Find Objects·Compare Schemas는 데이터베이스·스키마에는 남는다).
- 상태 짝은 짝으로 남는다: 연결 노드의 Connect / Disconnect(하나는 꺼짐), 사용 / 사용 안 함, 읽기 전용 / 읽기 쓰기, 온라인 / 오프라인.
- 하위 메뉴(Tasks, Script as, Generate SQL, Recent Scripts, New …)는 안의 항목이 하나도 해당하지 않으면 숨고, 해당하지만 지금 할 수 있는 것이 없으면 꺼진다.
- 구분선은 맨 앞·맨 뒤·연달아 둘이 되지 않는다. 묶음이 모두 숨으면 그 구분선도 사라진다.
- 남은 항목의 순서는 그대로다.
- 계정의 권한은 "지금 할 수 있는가"에 넣지 않는다(앱 변경 #159). 메뉴를 열 때마다 서버에 묻지 않고, 연결 때 읽어 둔 것만으로는 객체마다 읽을 수 있는지
  확실히 알 수 없기 때문이다(Oracle DDL은 소유자, 세션의 역할, SELECT_CATALOG_ROLE이 함께 정함). 그런 명령은 켜 둔 채로 실행하고, 서버가 거절하면
  오류 창의 첫 줄로 알린다([swing-routines.md](swing-routines.md) "DDL 권한과 취소").
- 도구 막대 단추와 두 번 클릭은 전처럼 켜짐만 본다. 숨은 항목은 늘 꺼져 있다(해당하지 않으면 켜지지 않음). 예외는 아래 연결·서버·프로젝트 명령으로,
  메뉴에서만 숨고 켜짐은 전과 같다. 트리의 키(F2, Delete, F7, F5,
  Ctrl+Shift+C …)는 전처럼 명령을 부르고 명령이 스스로 조건을 확인하므로, 해당하지 않는 노드에서는 전처럼 아무 일도 하지 않는다(코드가 바뀌지 않음).
- **메뉴 막대(File, Edit, Query, …)는 항목을 숨기지 않는다.** 메뉴 막대는 늘 같은 자리에 같은 항목이 있어야 찾을 수 있으므로 지금처럼 끄기만 한다.
  이 규칙은 오른쪽 클릭 메뉴와 선택에 따라 열리는 팝업에만 쓴다.

## 구조

- `plugins/com.seizia.chromite.ui.swing/.../ContextMenus.java`(새 파일): 규칙의 도우미.
  - `set(Action, applies, possible)` / `set(JMenuItem, applies, possible)`: 동작 값(하위 메뉴·동작 없는 항목은 클라이언트 속성) `chromite.applies`에
    "해당"을 두고, 켜짐은 `applies && possible`로 둔다. 같은 동작을 쓰는 메뉴·도구 막대·단축키가 한 답을 본다.
  - `tidy(JPopupMenu)`: 팝업이 열릴 때 부른다. `chromite.applies`가 있는 항목은 그 값으로 보이고, 값이 없는 하위 메뉴는 자식에서 정하며(보이는 자식이 있으면
    보임, 켜진 자식이 있으면 켜짐), 값이 없는 다른 항목(스스로 보임을 정하는 SQL Server 에이전트·스케줄러·유지 관리 항목, 늘 해당하는 Refresh)은 그대로 둔다.
    끝으로 하위 메뉴까지 구분선을 정리한다.
  - Swing `Action`에는 표준 "보임" 값이 없고, 도구 막대·메뉴 막대는 항목을 숨기지 않으므로, 보임은 팝업이 열릴 때만 옮긴다.
- `ObjectExplorerPanel.updateActions()`: 명령마다 두 답을 `ContextMenus.set`으로 둔다(선택이 바뀔 때와 팝업이 열릴 때).
  팝업 리스너가 `updateActions()` 뒤에 `ContextMenus.tidy(popupMenu)`를 부른다. "더 읽기" 행의 설정 항목도 같은 방식이며, 그 뒤 구분선을 따로 숨기던 코드는 지웠다.
- 조건을 둘로 나눈 원천(한 `canX`가 둘을 섞던 것):

  | 원천 | 해당(보임) | 지금(켜짐) |
  |---|---|---|
  | `SourceScripts.appliesModify` / `canModify` | 프로시저·함수·뷰·트리거·패키지이고 정의 텍스트와 객체 편집기가 있음 | 저장된 객체, 편집기가 허락함(`canEditObject`) |
  | `ExplorerObjectCommands.appliesRename` / `canRename` | 폴더·컨테이너 아래 DB 객체이고 그 종류에 이름 바꾸기 관리자가 있음 | 저장됨, 관리자가 허락함(시스템 객체 등) |
  | `ExplorerObjectCommands.appliesDelete` / `canDelete` | 모든 노드가 폴더·컨테이너 아래 DB 객체이고 그 종류에 관리자가 있음 | 저장됨, 관리자가 허락함, 한 연결 |
  | `DefaultObjectCommands.appliesSetDefault` / `canSetDefault` | 데이터베이스·스키마이고 주 세션이 그 종류를 바꿀 수 있음(연결 전이면 알 수 없어 보임) | 연결됨, 지금 기본값이 아님 |

## 개체 탐색기 항목별 분류

"전 조건"은 devel 1ef4f577cc의 `ObjectExplorerPanel.java` 줄 번호다.

| 항목 | 전 조건 | 해당(보임) | 지금(켜짐) |
|---|---|---|---|
| Options의 표시 개수 | 1:950 `MoreItem` 행 | "더 읽기" 행 | – |
| New Connection… | 1256 `projectNode != null` | 빈 곳, 연결 노드(여러 개면 모두 연결) | – (켜짐은 전처럼 프로젝트가 있으면) |
| New Query, Recent Scripts | 1258–1259 `dataSource != null` | 연결 아래 노드 | – |
| Set as Default | 1328 `canSetDefault` | `appliesSetDefault` | 연결됨, 기본값 아님 |
| New Table… | 1281 `canCreateTable(folder)` | 같음(폴더의 자식 종류, 드라이버) | – |
| New View/Procedure/Function… | `SourceEditCommands.update` `getNewKinds` | 같음(폴더 종류, 방언) | – |
| New &lt;종류&gt;… | `NewObjectCommands.update`(이미 숨김) | 같음. 서버 확장 폴더(ShardSphere 스트림 폴더)는 틀마다 한 항목(2026-11-22) | – |
| New Login/Role/User… | 1320 `getNewPrincipalKind` | 같음(보안 폴더) | – |
| SQL Server 에이전트 항목 | `AgentCommands.update`(이미 숨김) | 에이전트 노드·폴더·객체; **에이전트가 없는 에디션이면 바꾸는 명령 숨김(전에는 꺼짐)** | 작업 상태(사용/사용 안 함), 에이전트 실행 중 |
| 작업 스케줄러 항목 | `SchedulerCommands.update`(이미 숨김) | 그대로 | 권한, pg_cron 기능·설치, 읽기 전용 연결(그대로) |
| Design | 1280 `canDesign` | 같음(테이블, 구조 편집기) | – |
| Modify, Script as ▸ ALTER To | `SourceEditCommands.update` `canModify` | `appliesModify` | `canModify` |
| Execute Procedure… | `RoutineCommands.update` `canExecute` | 같음 | – |
| Go to Source Code | `RoutineCommands.update`(패키지 루틴이면 보임) | 패키지 루틴이고 패키지가 `appliesModify` | `canModify(패키지)` |
| Debug…, Trace Execution… | 1285–1286 | 같음(루틴, 디버거·SQL Server) | – |
| Permissions… | 1321 `canEditPermissions` | 같음 | – |
| Select Top 1000 Rows, Script as ▸ SELECT To | 1263–1265 `canSelect` | 같음 | – |
| Script as ▸ CREATE To, View DDL | 1264, 1267 `canScriptCreate` | 같음 | – |
| Script as | 1266 | 하위 항목에서 | 하위 항목에서 |
| Generate SQL | `ExplorerObjectCommands.update` 생성기 수 | 생성기가 있음. 행을 쓰는 생성기(INSERT·UPDATE·DELETE·MERGE)는 객체의 데이터 기능이 그 행을 받을 때만(2026-11-22, [swing-explorer-object-actions.md](swing-explorer-object-actions.md) "행을 쓰는 생성기") | – |
| View Diagram | 1268 `canShow` | 같음 | – |
| Object Details | 1326 | DB 노드이고 객체이거나 자식이 있는 폴더 | 연결 노드는 연결됨 |
| Tasks ▸ Back Up…/Restore… | 1272–1273 `canBackUp` | 같음 | – |
| Tasks ▸ Run Script with Native Client… | 1274 `canRunScript` | 같음, 연결 노드는 `BackupRestoreCommands.appliesRunScript`(PostgreSQL·MySQL/MariaDB의 네이티브 클라이언트, Oracle; 닫혀 있으면 알 수 없어 보임) | `canRunScript`(연결 노드는 DB 하나를 읽었음) |
| Tasks ▸ Import/Export/Copy Tables… | 1270–1275 | 그 객체에 해당(Import Data…는 행을 받는 객체, `DataTransferObjects.acceptsRows`, 2026-11-22), 연결 노드는 `DataTransferObjects.appliesToConnection`(맨 위가 스키마나 데이터베이스; 닫혀 있으면 보임) | 연결 노드는 연결했고 읽은 DB가 하나 |
| Tasks | 1277 | 하위 항목에서 | 하위 항목에서 |
| Maintenance… | 1279 `canMaintain` | 같음 | – |
| 유지 관리 명령(TRUNCATE, TTL, 트리거·테이블스페이스·파일 그룹, Altibase 계열 작업 사용·사용 안 함·설정과 큐 압축, 서버 확장 객체의 사용·사용 안 함·실행 설정 …) | `ObjectMaintenanceCommands.update`(이미 숨김) | 그대로(`supports`) | 그대로(`changesState`, 작업·확장 객체는 모델이 읽은 사용 여부) |
| Connect | 1287 | 연결 노드 | 연결 안 됨(켜짐은 전처럼 연결 아래 노드 기준, 도구 막대) |
| Disconnect | 1289 | 연결 노드 | –(켜짐은 전처럼 연결 아래 노드, 도구 막대) |
| Disconnect Others | 1291 | 연결 노드 | 다른 연결된 서버가 있음 |
| Disconnect All | 1292 | 빈 곳, 연결 노드(여러 개면 모두 연결) | 연결된 서버가 있음 |
| Reconnect, Invalidate/Reconnect | 1293–1294 | 연결 노드 | 연결됨 |
| Activity Monitor, Locks, Server Dashboard, Disk Usage, Server Logs, Maintenance, Replication | 1295–1298 | 연결 노드 | – |
| Find Objects…, Compare Schemas… | 1299, 1301 | 빈 곳, 연결 노드, 데이터베이스·스키마(`ObjectSearchPanel.findScope`, `SchemaComparePanel.findContainer`가 고른 노드에서 시작) | – |
| Search Data… | 1300 `findScope` | 같음(스키마·카탈로그·테이블 폴더, 같은 부모의 테이블) | – |
| Edit Connection…, Duplicate Connection | 1302, 1330 | 연결 노드 | – |
| Change Password… | 1304 | 연결 노드이고 연결된 DB가 지원함(닫힌 연결은 알 수 없어 보임) | 연결됨 |
| Copy Name | `ExplorerObjectCommands.update` | 노드가 있음 | – |
| Copy Qualified Name | 같은 곳 | 모두 DB 객체 | – |
| Copy Special… | 1329 `canCopy` | 같음 | – |
| Rename | 1305 | 연결 노드, `appliesRename` | 연결 노드, `canRename` |
| Delete | 1309 | 연결·보안 주체·에이전트·스케줄러 객체, `appliesDelete` | 그들, `canDelete` |
| Filter… | 1322 | 같음(객체 폴더) | – |
| Refresh | (늘 켜짐) | 늘 | – |
| Properties | 1313 | 같음(보안 주체, 에이전트·스케줄러 객체) | – |

### 경계에서 고른 것

- **연결·서버 명령과 프로젝트 명령**: 처음(1차)에는 연결 아래 모든 노드·어디서나 보였다. 사용자 결정("연결 노드에서만")으로 위 "규칙"처럼 바꿨다.
  Find Objects·Compare Schemas는 고른 노드에서 범위를 정하므로(`ObjectSearchPanel.java:187–188, 306`, `SchemaComparePanel.java:138–139, 232`) 데이터베이스·
  스키마 노드에도 보인다. 그 아래 노드(테이블 등)에서도 위 스키마를 찾아 쓰지만, 그 노드 자체가 범위가 아니므로 보이지 않게 했다.
- **연결 노드 여러 개를 고르면** New Connection·Disconnect All·Find Objects·Compare Schemas가 보인다. Disconnect 등 한 연결 명령은 전처럼 하나만 고를 때
  작동하므로(`runOnSelectedDataSource`) 보이지 않는다.
- **닫힌 연결 노드에서 데이터 원본이 있어야 아는 것**(Change Password, Run Script with Native Client, Import/Export/Copy Tables)은 꺼진 채 보인다.
  연결하면 지원하지 않는 DB(SQLite 등)에서는 숨는다. 그래서 같은 연결이 닫혔을 때와 열렸을 때 항목 수가 다를 수 있다.
- **연결 노드의 Import/Export/Copy Tables**는 맨 위가 스키마나 데이터베이스인 연결에 해당하고, DB가 여러 개이거나 아직 읽지 않았으면 꺼진다
  (DB 수와 읽었는지는 바뀔 수 있는 "지금").
- **SQL Server 에이전트가 없는 에디션**(Express)은 서버의 종류라 바꾸는 명령을 숨긴다(전에는 꺼짐과 도구 설명). 보기 명령은 남는다.
- **작업 스케줄러의 꺼짐**(Oracle 권한, pg_cron 미설치·다른 DB·버전 기능, 읽기 전용)은 그대로 둔다. 확장 설치·버전은 노드가 그대로인 채 바뀔 수 있어 "지금"으로 보았다.
- **읽기 전용 연결**에서 전에도 켜져 있던 Delete·Rename 등은 실행할 때 알리고 멈춘다(`checkModifiable`). 켜짐을 바꾸지 않았다(새로 끄면 단축키 동작이 바뀜).
- **여러 개 선택이 연결을 넘으면** 유지 관리 명령은 전처럼 숨는다(`ObjectMaintenanceCommands`의 한 연결 조건).
- **Altibase 계열 큐의 열·키·인덱스**(2026-11-21, 앱 변경 #154): 큐의 내부 구조라 바꿀 수 없다. 종류(열·키·인덱스)에는 해당하므로 Rename·Delete는 꺼진 채 보이고,
  New Column·New Key·New Index와 큐의 Design은 디자이너가 큐에 해당하지 않아 숨는다. 작업 명령의 권한(CREATE / ALTER / DROP ANY JOB)은 메뉴에서 보지 않고
  서버 오류로 알린다([altibase.md](altibase.md) "작업 만들기·바꾸기·삭제", "큐").

## 실행 덤프로 만든 전·후 행렬

만든 방법: 앱 안 테스트 에이전트(Swing 객체를 EDT에서 다룸, OS 입력 없음)가 노드를 고르고 트리의 팝업을 열어(`popupMenuWillBecomeVisible`이 돈 뒤)
항목마다 보임·켜짐을 적었다. 전은 devel 1ef4f577cc 빌드, 후는 이 변경의 빌드다. 같은 사설 작업 공간, 같은 테스트 DB(터널 127.0.0.1의 SQL Server,
Oracle, PostgreSQL, MariaDB, MySQL, Altibase, SQLite 파일), 이 작업이 만든 `CHR_EM_*`·`chr_em_*` 개체만 썼다. 노드 95종(DB별 연결·데이터베이스·스키마·
폴더·테이블·열·키·인덱스·트리거·뷰·프로시저·함수·패키지·시퀀스·사용자·로그인·역할·파일 그룹·테이블스페이스·에이전트·스케줄러·"더 읽기" 행·여러 개 선택·
섞인 선택·읽기 전용 연결·닫힌 연결·빈 곳). 행렬 전체(행 = 항목, 열 = 노드 종류, 칸 = 전/후, E 켜짐 · d 꺼짐 · - 숨김)는 덤프와 함께
`D:\Tools\_scratch\explorer-menu-applicable\dumps\matrix.md`에 있다. 아래는 요약이다.

### 요약 (2026-11-16, 영어·150 %, 같은 사설 작업 공간)

- 보이는 항목 수: 전 50–56개(모든 노드), 후 5–36개. 가장 작은 메뉴는 빈 곳(New Connection, Disconnect All, Find Objects, Compare Schemas, Refresh 5개)이고,
  "더 읽기" 행은 그 앞에 Options의 표시 개수 항목이 붙는다. 빈 메뉴는 없다.
- 사용자의 예(SQL Server "파일 그룹" 폴더): 전 51개(꺼진 것 29개) → 후 23개(꺼진 것은 Connect 하나, 연결이 이미 열려 있어서).
- 구분선: 95종 모두 맨 앞·맨 뒤·연달아 둘인 구분선 없음(전에도 없음).
- 전에 켜져 있던 항목이 후에 숨거나 꺼진 (노드, 항목): 실행 상태가 같을 때 0건. 비교에서 나온 다섯 건은 같은 상태에서 devel의 켜짐 식을 다시 계산해 모두 false였다
  (전 덤프의 노드·읽은 상태가 달랐음): SQL Server 트리거의 Rename(`SQLServerTableTriggerManager`는 `DBEObjectRenamer`가 아님, `canRename` = false),
  Oracle Sequences 폴더의 Search Data·Tasks ▸ Export/Copy Tables(`findScope`·`canExport` = false, 시퀀스는 데이터 컨테이너가 아님).
  PostgreSQL 연결의 Tasks는 데이터베이스를 읽기 전에는 꺼지고(지금), 읽은 뒤 전처럼 켜진다.
- 후에 보이고 전에 꺼져 있던 항목의 "지금" 이유: Connect(이미 연결됨), Reconnect·Invalidate(연결 안 됨), Object Details·Tasks·Change Password(닫힌 연결),
  Enable/Disable Trigger 등 상태 짝, Rename·Delete(관리자가 거절하는 시스템 객체, 예: Oracle USERS 테이블스페이스), Tasks(DB를 아직 읽지 않음).
- 읽기 전용 연결의 테이블: devel에도 개체 탐색기 항목 가운데 읽기 전용이라 꺼지는 것이 없었다(Delete·Rename·Truncate는 실행할 때 알리고 멈춤). 그대로다.
- 키: Tables 폴더에서 F2·Delete는 전처럼 아무 일도 하지 않고(항목 숨김), 테이블에서 F2는 이름 바꾸기, Delete는 삭제 확인을 연다(취소함). SQLite Tables 폴더의 F7은
  Object Details를 연다.
- 다른 메뉴(덤프 전·후): Registered Servers 서버 메뉴(SQLite 연결됨: Change Password 없음, PostgreSQL 연결됨: 켜짐, 연결 안 됨: 꺼짐), 문서 탭(Activity Monitor·
  ER 다이어그램 탭: 닫기·다시 열기·고정·배치만, 쿼리 창: 전과 같음, 저장 전 창은 경로 항목 꺼짐), Messages(오류 글자: 오류 위치로 가기, 다른 글자: 복사만),
  ER 다이어그램(뷰: Design 없음).

위는 1차(연결 명령이 연결 아래 모든 노드에 보이던 때)의 요약이다. 1차 덤프와 행렬은 `*-round1.*`로 남겼다.

### 연결 노드에서만 (2차, 2026-11-16, 영어·150 %)

- 전(devel)에 켜져 있다가 지금 빠진 (노드, 항목)은 결정한 항목뿐이다(연결 노드·빈 곳이 아닌 노드에서): New Connection·Disconnect All 85,
  Disconnect·Disconnect Others·Reconnect·Invalidate/Reconnect·Activity Monitor·Locks·Server Dashboard·Disk Usage·Server Logs·Maintenance·Replication 각 81,
  Find Objects·Compare Schemas 각 77. 그 밖에는 1차와 같은 다섯 건(위 설명, 같은 상태에서 devel 식도 false)뿐이다.
- 구분선 문제 0, 빈 메뉴 0(가장 작은 메뉴: 연결과 테이블을 섞어 고름 = Copy Name·Refresh, "더 읽기" 행 = 표시 개수·Refresh).
- 보이는 항목 수(devel / 1차 / 지금): 빈 곳 50/5/5, 연결(열림) 50/27/27, 연결(닫힘) 50/27/27, 데이터베이스 50/28/14, 스키마 50/29/15,
  Tables 폴더 50/25/9, 테이블 51/36/20, 열 50/25/9, 뷰 50/34/18, 프로시저 50/32/16, SQL Server 파일 그룹 폴더 51/23/7, Oracle 테이블스페이스 50/28/12,
  로그인 50/26/10, 테이블 여러 개 51/12/8, 테이블+뷰 50/11/7, 연결 여러 개 –/6/6(New Connection, Disconnect All, Find Objects, Compare Schemas, Copy Name, Refresh).
- 켜짐은 그대로: 테이블과 연결 노드를 골랐을 때 탐색기 도구 막대(Connect, Disconnect 등)와 메뉴 막대(File, Tools의 Activity Monitor·Locks·Find Objects 등)의
  켜짐이 1차와 같다. 달라진 것은 쿼리 창이 열려 있느냐에 따르는 편집기 항목뿐이다(1차 실행에는 쿼리 창이 있었음).
- 키와 두 번 클릭: 트리의 키(Enter, F2, Delete, F5, F7, Ctrl+Shift+C, Ctrl+F) 가운데 이 명령들을 부르는 것은 없다. Enter는 연결 노드에서 Edit Connection,
  다른 노드에서 펼치기/접기, 두 번 클릭은 설정대로(기본 펼치기)이며 1차와 같게 동작했다.

덤프·캡처: `D:\Tools\_scratch\explorer-menu-applicable\dumps\`(before.tsv, after.tsv, matrix.md, checks.txt, `*-round1.*`, bar-round1.tsv, bar-now.tsv,
p2-*.tsv), `D:\Tools\_scratch\explorer-menu-applicable\sheets\`(menus-v2-before-after-*.png는 devel과 지금).

## 다른 오른쪽 클릭 메뉴

코드에서 `JPopupMenu`, `setComponentPopupMenu`, `isPopupTrigger`, 팝업 `show(`로 찾은 메뉴 전체를 점검했다(2026-11-16). 고친 것은 "바꿈", 이미 규칙대로인 것은 "맞음",
고칠 것이 있지만 이번에 하지 않은 것은 "남김"이다. 줄 번호는 이 변경 전 devel 기준이다.

| 곳 | 파일:줄 | 항목과 판단 | 결과 |
|---|---|---|---|
| Registered Servers 트리 | `RegisteredServersPanel.java:369` | 서버·그룹·빈 곳마다 따로 만든다(종류는 이미 숨김). 서버의 Change Password…만 연결된 서버의 DB가 지원하지 않아도 꺼진 채 보였다(:382) | 바꿈: 연결된 서버가 지원하지 않으면 숨김, 연결 안 됨이면 꺼짐(개체 탐색기와 같음) |
| 문서 탭 | `DocumentTabMenu.java:235` | 저장·이름 바꾸기·연결 변경·경로 복사·상위 폴더 열기가 쿼리 창이 아닌 탭(ER 다이어그램, Activity Monitor, 디자이너)에서 꺼짐(:242–260) | 바꿈: 쿼리 창 탭에서만 보임, 저장 전 창의 경로 항목·실행 중 창의 연결 변경은 꺼짐, `ContextMenus.tidy` |
| Messages 탭 | `QueryResultsView.java:654` | "오류 위치로 가기"가 위치 없는 글자에서 꺼짐(:688) | 바꿈: 위치를 아는 오류 글자에서만 보임 |
| ER 다이어그램 개체 | `ErdDiagramPanel.java:489` | Design(뷰에서 꺼짐 :495), Select Top(:499) | 바꿈: 해당하는 개체에서만 보임, `tidy` |
| SQL 편집기 글 영역 | `SqlEditorPanel.java:3434`(RSyntaxTextArea 기본 메뉴) | 읽기 전용 글 영역의 실행 취소·잘라내기·붙여넣기·삭제, 접기 미지원의 Folding이 꺼짐(라이브러리가 만든 항목) | 바꿈(2026-11-17, 아래 "작은 메뉴 5곳"): 한 도우미(`ContextMenus.tidyTextArea`)가 동작으로 항목을 골라, 편집할 수 없는 보기의 편집 항목과 접기가 없는 곳의 Folding을 숨김 |
| 결과 그리드 셀 | `ResultSetGrid.java:1219`, `ResultSetViewerPanel.java:1084` | 행 삽입·복제·삭제(지원 안 함), Export Data(SELECT 아님), Generate SQL 항목(원본 테이블·키 없음), 파일 불러오기·저장·16진수·외부 편집기(값 종류), 가상 열의 Filter by Value, Saved Filters·Virtual Keys·Virtual Columns(원본 테이블 없음), Count Rows가 종류 이유로 꺼짐. Save and Commit은 커밋 방식(지금)으로 숨음(:1307) | 바꿈(2026-11-16, 아래 "결과 그리드"): 두 이유를 원천에서 나누고 `ContextMenus.tidy` |
| 결과 열 머리글, Ctrl+B·Ctrl+Shift+1 팝업 | `ResultSetViewerPanel.java:924`, `:2584`, `:2604` | 누른 곳에 따라 추가, 꺼짐은 지금 상태 | 맞음 |
| 열린 트랜잭션 칸 | `QueryStatusLine.java:448` | 읽기 전용 트랜잭션은 다른 항목 | 맞음 |
| 집계 함수, 차트 값 | `ResultSetAggregatePanel.java:188`, `ResultSetChartView.java:443` | 숫자 열만 추가, 8개 제한은 꺼짐 | 맞음 |
| 관리 그리드(유지 관리, 복제, 서버 로그, 공간 사용량) | `AdminGridTable.java:94` | 복사 항목만, 늘 켜짐 | 맞음 |
| SQL 터미널 | `SqlTerminalView.java:74` | 복사는 선택(지금) | 맞음 |
| Scripts 트리 | `ScriptsPanel.java:434` | 루트·빈 곳의 이름 바꾸기·삭제가 꺼짐(:428) | 바꿈(2026-11-17): 루트·빈 곳에서 숨김 |
| Query Library 트리 | `QueryLibraryPanel.java:655` | 읽기 전용 루트의 새 쿼리·새 폴더·가져오기·복제·이름 바꾸기·삭제·일정·Commit & Push가 꺼짐, Desktop 미지원의 폴더 열기 | 바꿈(2026-11-17): 루트·빈 곳의 이름 바꾸기·삭제, 빈 곳의 내보내기·폴더 열기, Desktop 미지원의 폴더 열기를 숨김. 읽기 전용 루트는 Library Roots에서 바꿀 수 있어 "지금"(꺼짐 그대로) |
| Query Manager(기록) | `QueryManagerPanel.java:731` | 세션·트랜잭션 행만 고르면 열기·복사가 꺼짐, 트랜잭션 없는 행의 Show Transaction | 바꿈(2026-11-17): 행 종류로 숨김, 이미 그 트랜잭션을 보는 중이면 꺼짐 |
| Database Tasks | `DatabaseTasksPanel.java:354` | 편집할 수 없는 작업 종류의 Edit가 꺼짐(도구 설명) | 바꿈(2026-11-17): 숨김, 도구 설명과 그 메시지 키를 지움 |
| 작업 대화상자 Script, Data Search 결과, ERD 배경·내보내기, 대시보드, 도구 창 ⋮, 도구 막대 넘침, 업데이트 단추, Agent 플레이북·모델·기록, SQL 템플릿 고르기 | 각 파일 | 늘 해당하거나 지금 상태로만 꺼짐 | 맞음 |
| Text·Server Output·실행 계획 탭, 테이블 디자이너, Properties, Object Details, Find Objects, Activity Monitor, 결과 행 머리글, 레코드 보기 | – | 오른쪽 클릭 메뉴 없음 | – |

메뉴 막대(`MainFrame`)의 Query 메뉴(Debug, Trace, Native Script는 활성 편집기의 개체로 켬), Window 메뉴(열린 탭), File ▸ Recent는 선택에 따라 바뀌지만
메뉴 막대이므로 숨기지 않는다(규칙 참고).

## 결과 그리드

사용자 결정(2026-11-16, 앱 변경 #137 3부): 결과 그리드 셀 메뉴를 같은 규칙으로 바꾸자는 설명에 "결과 그리드도 고친다."

결과의 종류(편집할 수 있는가, 테이블 하나에서 왔는가, SELECT 하나인가, 다시 읽을 수 있는 쿼리인가)와 칸의 종류(LOB·이진 열인가, 가상 열인가,
외래 키 열인가), DB의 기능(테이블이 INSERT·DELETE를 받는가, 컨테이너가 행을 세는가)은 보임을 정한다. 결과가 다른 결과가 되지 않고도 바뀌는 것
(바뀐 것이 있음, 선택, 연결 상태, 읽기 전용 연결, 읽는 중, 저장 중, 셀 값이 NULL)은 켜짐을 정한다.

### 구조

- 셀 메뉴는 `ResultSetGrid.installContextMenu`(편집·복사·내보내기)가 만들고 `ResultSetViewerPanel.installMenus`가 항목을 덧붙인다. 둘 다 팝업이 열릴 때
  `ContextMenus.tidy`를 부른다(두 리스너 중 어느 것이 나중에 돌아도 같은 결과). 도우미는 바꾸지 않았다.
- 같은 그리드를 쓰는 곳: 쿼리 창의 결과(`QueryResultsView` → `ResultSetViewerPanel`; 개체 탐색기의 Select Top 1000 Rows도 쿼리 창을 연다)와
  참조 패널(`ResultSetReferencesPanel`, 읽기 전용 뷰어). 따로 표를 만드는 곳은 없다. 모두 같은 코드로 바뀐다.
- 조건을 둘로 나눈 원천:

  | 원천 | 해당(보임) | 지금(켜짐) |
  |---|---|---|
  | `ResultSetEditModel.appliesEditing` / `getReadOnlyReason` | 테이블 하나, 완전한 고유 키, 편집할 수 있는 열, 데이터를 바꿀 수 있는 DB | 연결됨, 읽기 전용 연결 아님, 데이터 편집 권한 |
  | `ResultSetGrid.Fetched.editingApplies` (수신기가 채움) | 위의 "해당" | 편집 모델은 둘 다일 때만 |
  | `DataExportWizard.appliesExportQuery` / `canExportQuery` | 결과의 쿼리가 바꾸지 않는 SELECT 하나 | 쿼리의 데이터 원본이 있음(문장 세기) |
  | `ResultSetSource.appliesRequery` / `canRequery` | 다시 읽을 수 있는 쿼리(필터 기능, SELECT 하나, 배치 문장을 앎, 다 읽음) | 읽을 때 연결되어 있었음 |
  | `ResultSetSource.lobColumns` + `ResultSetEditModel.isWholeValueColumn` | LOB·이진 열(읽은 값이 LOB 값 객체, 또는 형식) | – |
  | `ResultSetGenerateSql.appliesRows` | 테이블 하나 + 키가 필요한 문장은 고유 키 | 행을 고름 |

### 항목별 분류

"전 조건"은 `origin/chromite/explorer-menu-applicable` 65a95570c4의 줄 번호다(G = `ResultSetGrid.java`, V = `ResultSetViewerPanel.java`, S = `ResultSetGenerateSql.java`).

| 항목 | 전 조건 | 해당(보임) | 지금(켜짐) |
|---|---|---|---|
| Edit Cell | G:1220 편집 모델, G:529 `!saving` | 편집할 수 있는 결과(`editingApplies`) | 편집 모델 있음, 저장 중 아님 |
| Edit Value…, Set to Default, Generate UUID | G:530·532·533 `!saving && hasRows` | 같음 | 편집 모델, 저장 중 아님, 행 있음 |
| Set NULL, Paste Special… | G:531·543 `!saving` | 같음 | 편집 모델, 저장 중 아님 |
| Reset Cell | G:534 `!saving && dirty` | 같음 | 바뀐 것 있음 |
| Insert Row | G:535 `!saving && isInsertSupported` | 편집할 수 있는 결과 + 테이블이 INSERT를 받음 | 저장 중 아님 |
| Duplicate Row | G:536 | 같음(INSERT) | 저장 중 아님, 행 있음 |
| Copy from Row Above / Below, Paste | G:537·538·542 `!saving && hasRows` | 편집할 수 있는 결과 | 행 있음 |
| Delete Row | G:539 `isDeleteSupported` | 편집할 수 있는 결과 + 테이블이 DELETE를 받음 | 저장 중 아님, 행 있음 |
| Undo / Redo | G:540·541 `canUndo`/`canRedo` | 편집할 수 있는 결과 | 되돌릴 것 있음 |
| Save Changes, Preview Changes…, Cancel Changes | G:524·527·528 `dirty && !saving` | 편집할 수 있는 결과 | 바뀐 것 있음 |
| Save and Commit | G:525 켜짐 `dirty && !saving && manual`, G:1307 보임 `manual` | 편집할 수 있는 결과 + **수동 커밋 모드(예외)** | 바뀐 것 있음 |
| Copy, Copy with Headers | G:516·517 `hasRows` | 늘 | 행 있음 |
| Copy As ▸ | V:1090 | 늘 | – |
| Generate SQL ▸ | S:178 연결 | 열이 있음(CREATE TABLE) | 연결됨(도구 설명 그대로) |
| Generate SQL ▸ 행 문장(SELECT…, INSERT, UPDATE, DELETE, MERGE, 중복 키 INSERT ▸) | S:188 `rowsBlocker`, S:258 `getBlocker` | 테이블 하나 + 키가 필요한 문장은 고유 키 + 행을 쓰는 문장은 그 테이블이 그 행을 받음(`acceptsWrite`, 2026-11-22) | 행을 고름(도구 설명 그대로) |
| Generate SQL ▸ CREATE TABLE | S:227 `bindings.length` | 열이 있음 | – |
| Export Data… | G:1208 `canExportQuery` | SELECT 하나(`appliesExportQuery`) | 쿼리의 데이터 원본 있음(도구 설명: 연결 안 됨) |
| Export to CSV… / Excel (XLSX)… | 늘 켜짐 | 늘 | – |
| Load from File… | V:1337·1341 | 편집할 수 있는 결과의 LOB·이진 열 | 칸을 고칠 수 있음(저장 중·지운 행 아님), 읽는 중 아님 |
| Save to File… | V:1339·1342 | LOB·이진 열 | 값이 NULL 아님 |
| Edit as Hex… | V:1344·1346 | 이진 열(글자 LOB 아님) | 이진 값이나 편집할 수 있는 이진 LOB 칸, 읽는 중 아님 |
| Open in External Editor | V:1348 | **모든 칸**(글자 값도 연다) | 값이나 편집할 칸, 읽는 중 아님 |
| Filter by Value ▸ | V:1190·1199 | 다시 읽을 수 있는 쿼리, 가상 열 아님 | 지금 다시 읽을 수 있음, 읽는 중 아님, 칸을 고름(도구 설명 그대로) |
| Clear Filter | V:1154 | 다시 읽을 수 있는 쿼리 | 조건 있음, 읽는 중 아님 |
| Saved Filters ▸ | V:3049 안내 항목만 꺼짐 | 테이블 하나 | 안 항목은 그대로(저장할 조건 없음, 다시 읽을 수 없음) |
| Navigate ▸ 참조되는 행 | V:2484 안내 항목 "외래 키 없음" | 외래 키 열 | 읽기 가능 |
| Navigate ▸ Referenced / Referencing Tables | V:2499 | 늘(관계는 메뉴가 열린 뒤 읽음) | – |
| Navigate ▸ Back / Forward, Go to Row / Column | V:2505–2510 | 늘 | 기록 위치 |
| Navigate ▸ Count Rows | V:2512 상태 줄 링크가 보임 | 다시 읽을 수 있는 쿼리 + 컨테이너가 행을 셈 | 남은 행이 있음, 센 수 없음, 세는 중 아님 |
| Fit All Column Widths, Find… | 늘 | 늘 | – |
| Columns ▸ | V:2837 | 늘(안 항목은 그대로) | 숨긴 열, 확대 |
| Virtual Keys ▸ | V:2978 안내 항목만 꺼짐 | 테이블 하나 | – |
| Virtual Keys ▸ Define Virtual Unique Key… | V:2985 `usesVirtualKey`, 도구 설명 "이미 키" | 실제 키 없는 테이블 | 읽는 중 아님 |
| Virtual Keys ▸ Add / Remove Virtual Foreign Key | V:2992·3004 | 늘 | 읽는 중 아님 |
| Virtual Columns ▸ | V:3176 안내 항목만 꺼짐 | 테이블 하나 | 안 항목은 그대로 |
| Value Panel, Record, Text, Chart, References, Aggregates | 늘 | 늘 | – |
| Spatial | V:1159 기하 열이 있을 때만 보임 | 그대로 | – |

### 경계에서 고른 것

- **저장하고 커밋은 자동 커밋 모드에서 숨긴다(예외).** 커밋 방식은 결과가 그대로인 채 바뀔 수 있는 "지금"이지만, 자동 커밋 모드에서는 Save가 커밋하므로
  이 항목은 쓸모가 없다. 표 위의 단추도 같은 조건으로 숨는다(바뀌지 않음). 사용자가 설명과 함께 받아들였다.
- **읽기 전용 연결에서 읽은 결과의 편집 명령은 꺼진 채 보인다.** 전에는 편집 모델이 없어 메뉴에 편집 명령이 아예 없었다. 읽기 전용 연결은 규칙의 "지금"이므로
  결과가 종류로 편집할 수 있으면(`appliesEditing`) 명령을 꺼진 채 보인다. 키는 전처럼 묶지 않는다(표의 기본 키와 창의 단축키로 감, 예: Ctrl+0은 데이터베이스/스키마 선택).
  편집할 수 있는지는 읽을 때 정하므로 연결을 읽기 쓰기로 바꾼 뒤 다시 실행해야 켜진다. 연결이 닫힌 결과도 같다(읽을 때 닫혀 있었을 때만; 읽은 뒤 닫히면 전처럼 켜짐).
  "데이터를 바꿀 수 없는 DB"(드라이버의 `isReadOnlyData`)는 종류로 보아 숨긴다.
- **키 없는 테이블의 결과**는 편집할 수 없는 결과로 본다(종류). 가상 키를 정하면 결과를 다시 읽어 다른 결과가 된다. 그래서 편집 명령, 키가 필요한 Generate SQL
  문장은 숨고, 표 위 줄의 "가상 키 정하기" 링크는 그대로다.
- **외부 편집기는 모든 칸에 해당한다.** 설명에서는 LOB가 아닌 칸에서 숨기기로 했지만, 코드상 글자 값이면 어느 칸이나 연다(전에 켜져 있었음). 숨기면 쓰던 항목이 사라진다.
- **Export Data…가 연결이 닫혀 꺼지는 경우는 코드에 없었다.** 켜짐은 쿼리만 보았고(`canExportQuery`), 연결이 닫힌 뒤에도 켜져 있었다(마법사가 연결한다). 그대로 둔다.
  쿼리의 데이터 원본이 없어 문장을 셀 수 없는 경우만 "지금"으로 나눠 꺼진 채 "연결 안 됨"을 보인다. "SELECT 하나인 쿼리만" 도구 설명은 지웠다.
- **다시 읽을 수 없는 쿼리**(프로시저 호출, SHOW, 여러 문장 배치, 다 읽지 못한 결과)는 종류로 보아 Filter by Value, Clear Filter, Count Rows를 숨긴다(설명에는 Count Rows만).
  읽을 때 연결이 끊겨 있던 결과만 "지금"으로 남긴다(`appliesRequery`).
- **"외래 키 없음"·"테이블 하나에서 온 결과가 아닙니다"·"이미 키가 있음" 안내 항목**은 종류 이유로 꺼진 항목이라 지웠다(그 메뉴·항목이 숨음).
  "외래 키 없음" 문구는 Ctrl+B 상태 줄에 그대로 쓴다.
- **값이 NULL인 LOB 칸**의 Save to File은 꺼진 채 보인다(값은 바뀔 수 있음). 고른 칸이 없으면 결과에 그런 열이 있는지로 보인다.
- **참조되는·참조하는 테이블 하위 메뉴**는 관계를 메뉴가 열린 뒤 읽으므로 늘 보인다(없으면 안의 "없음" 항목). Ctrl+Shift+1 팝업과 같다.
- **값 패널의 메뉴**는 RSyntaxTextArea가 만든 기본 메뉴(Undo, Cut, Paste, Folding …)다. 이때는 SQL 편집기 글 영역과 같은 까닭으로 남겼고,
  2026-11-17 같은 도우미로 옮겼다(아래 "작은 메뉴 5곳": 칸마다 바뀌므로 항목은 꺼진 채 남음).
- **열 머리글 메뉴, Ctrl+B·Ctrl+Shift+1 팝업**은 다시 보아도 규칙대로라 그대로다.

### 실행 덤프로 만든 전·후 (2026-11-16, 영어·150 %, 같은 사설 작업 공간)

앱 안 테스트 에이전트가 셀을 고르고 표의 팝업을 열어(두 리스너가 돈 뒤) 항목마다 보임·켜짐·도구 설명을 적었다. 전은 65a95570c4 빌드, 후는 이 변경의 빌드다.
터널 127.0.0.1의 PostgreSQL·SQL Server(모든 경우), Oracle·MariaDB(다른 것만), 이 작업이 만든 `chr_rg_*`·`CHR_RG_*` 개체만 썼다(끝나고 지움).
경우: 편집할 수 있는 결과의 보통·NULL·LOB·이진·이진 NULL·키·외래 키 칸, 한 행·칸 묶음·여러 행 선택, 바뀐 것 있음, 가상 열, 식 열, 키 없는 테이블, 조인, 집계,
SELECT 아닌 결과(SHOW, EXEC 프로시저, `sp_who`), 두 문장의 두 결과, 읽은 뒤 연결 닫음, 읽기 전용 연결, 수동 커밋, 실행 중인 다음 문장, Select Top 문장, 값 패널.

- 보이는 맨 위 항목 수(꺼진 수): 편집할 수 있는 보통 칸 44(10) → 41(7), 이진 칸 44(7) → 44(7), 바뀐 것 있음 44(5) → 41(2), 키 없는 테이블 26(4) → 23(1),
  조인 26(4) → 20(1), SELECT 아닌 결과 26(6) → 17(0), 읽기 전용 연결 26(4) → 41(19, 편집 명령이 꺼진 채 보임), 수동 커밋 45(11) → 42(8). 빈 메뉴 없음.
- 구분선: 모든 경우에 맨 앞·맨 뒤·연달아 둘인 구분선 없음.
- 전에 켜져 있던 항목이 후에 숨거나 꺼진 것: 0건. 전에 켜져 있던 하위 메뉴 가운데 안에 켜진 항목이 없던 것(Saved Filters ▸ Recent Filters의 "없음",
  테이블 하나가 아닌 결과의 Saved Filters·Virtual Keys·Virtual Columns, 행 문장이 숨은 INSERT on Duplicate Key)은 꺼지거나 숨는다.
- 키·단추·메뉴 막대: 표·그리드·결과 뷰어의 모든 키 바인딩과 그 동작의 켜짐, 표 위 Save·Save and Commit·Cancel 단추, 상태 줄 단추가 모든 경우에 같다.
  메뉴 막대는 Edit ▸ Copy·Paste가 두 경우에 다르다: `EditMenu`는 포커스를 가진 컴포넌트를 따르므로(쿼리 창이 막 열린 뒤의 포커스 시점), 같은 결과의 다음 경우에서는 같다.
  Window 목록과 Query ▸ Compare…는 열린 쿼리 창 수가 달라 비교에서 뺐다.
- 키 동작(PostgreSQL·SQL Server, 전·후 같음): 편집 결과에서 F2 편집 시작, Ctrl+0 NULL, Ctrl+Z/Y, Alt+Insert 행 추가, Ctrl+D, Delete, Ctrl+Backspace;
  읽기 전용 연결 결과에서 F2·Alt+Insert·Delete는 아무 일 없음, Ctrl+0은 창의 데이터베이스/스키마 선택 대화상자, Shift+Enter는 위 행으로.
- 편집 왕복(PostgreSQL, 후): 메뉴로 Insert Row → 값 입력 → Save Changes → DB에 `99:chr_rg_rt`, 칸 고침 → Save → `99:chr_rg_rt2`, Delete Row → Save → 행 없음.

덤프·캡처: `D:\Tools\_scratch\result-grid-menu\dumps\`(before-*.tsv, after-*.tsv, *-keys.tsv, checks.txt, matrix.md),
`D:\Tools\_scratch\result-grid-menu\sheets\`.

## 작은 메뉴 5곳 (2026-11-17, 앱 변경 #141)

사용자 결정(2026-11-16): 점검표에서 "남김"이던 다섯 메뉴(SQL 편집기 글 영역, Scripts 트리, Query Library 트리, Query Manager, Database Tasks)에도 같은 규칙을
적용할지, 숫자 칸 9곳을 고칠지 묻자 "둘 다 고친다". 이 절은 메뉴다(숫자 칸은 다른 작업).

### 구조

- **글 영역 메뉴는 한 곳**: `ContextMenus.tidyTextArea(RSyntaxTextArea, JPopupMenu)`. RSyntaxTextArea가 만든 기본 메뉴(Undo, Redo | Cut, Copy, Paste, Delete |
  Select All | Folding ▸)는 항목 필드가 라이브러리 안에 숨어 있어, 모든 글 영역이 함께 쓰는 동작 객체(`RTextArea.getAction`)와 Folding 하위 메뉴의 첫 동작
  (`ToggleCurrentFoldAction`)으로 고른다. 라이브러리가 `configurePopupMenu`에서 켜짐을 정한 뒤 부르므로 켜짐은 라이브러리 그대로다.
  이 메뉴를 쓰는 글 영역 클래스는 셋이고 모두 이 도우미를 부른다:
  - `SqlEditorPanel.SqlTextArea`: 쿼리 창 편집기, DDL 보기(`DdlViewerPanel`), 원본·디버거·실행 추적(`ProcedureDebuggerPanel`, `ExecutionTracePanel`),
    Query Manager·Query History·Transaction Log의 미리 보기, 스크립트·SQL 확인 대화상자(`ScriptPreviewDialog`, `SqlConfirmDialog`, `AiConfirmationDialog`,
    `MaintenanceDialog`, `QueryParameterDialog`, 세션 SQL), Chromite Agent의 코드 블록(`AiMarkdownView`), 텍스트 비교(`TextDiffView`), 작업 대화상자의 명령 칸
    (`AgentJobStepDialog`, `PgCronJobDialog`, `OracleJobDialog`, `MySQLEventDialog`), Options의 미리 보기와 템플릿 내용(`SqlTemplatesPage`).
  - `ResultSetValuePanel.ValueTextArea`: 결과의 값 패널.
  - `PlanRawView.PlanTextArea`: 실행 계획의 원본 보기.
- **"읽기 전용"이 종류인가**: 글 영역이 한 번 읽기 전용이 되면 다시 편집할 수 없게 되지 않는 보기(DDL, 원본, 미리 보기, 로그, 계획, Agent 코드 블록, 작업 대화상자에서
  권한·작업 종류로 정해진 칸)는 종류다. 편집 항목(Undo, Redo, Cut, Paste, Delete)을 숨긴다. 열려 있는 동안 내용이 바뀌는 글 영역은 클라이언트 속성
  `ContextMenus.VARYING_TEXT`로 표시해 "지금"으로 둔다: 값 패널(칸마다 읽기 전용·언어가 바뀜), 템플릿 페이지의 내용(템플릿을 고르지 않았을 때만 읽기 전용).
  코드에서 `setEditable`을 찾아 확인했다: 쿼리 창 편집기는 실행 중에도, 읽기 전용 연결에서도 편집할 수 있다(편집기 자체를 읽기 전용으로 바꾸는 코드가 없음).
  `OracleJobDialog`·`PgCronJobDialog`의 칸은 대화상자를 만들 때 한 번 정하고 바꾸지 않는다.
- **Folding**: 접기를 끈 글 영역(계획 원본, 텍스트 비교)이나 언어에 접기 파서가 없는 글 영역(SQL이 아닌 Agent 코드 블록)에서는 숨긴다. 값 패널은 칸마다 언어가
  바뀌므로 전처럼 꺼진 채 남는다(접기를 켠 글 영역).
- 실행 취소할 것 없음, 고른 글 없음, 붙여넣을 것 없음은 "지금"이다(라이브러리의 켜짐 그대로).
- **트리·목록 메뉴**는 메뉴를 열 때마다 새로 만들고 `ContextMenus.tidy`를 부른다. 두 답은 `updateActions`에서 `ContextMenus.set`으로 둔다.
  켜짐은 전과 같은 식이라(해당 && 가능 = 전의 식) 도구 막대와 키는 그대로다.

### 항목별 분류

"전 조건"은 devel 20e976013f의 줄 번호다.

| 메뉴 | 항목 | 전 조건 | 해당(보임) | 지금(켜짐) |
|---|---|---|---|---|
| 글 영역 | Undo, Redo | RTextArea `configurePopupMenu`(동작 켜짐 && 편집 가능) | 편집할 수 있거나 `VARYING_TEXT` | 되돌릴 것 있음, 편집 가능 |
| 글 영역 | Cut, Paste, Delete | 같음 | 같음 | 고른 글·붙여넣을 것, 편집 가능 |
| 글 영역 | Copy, Select All | 동작 켜짐 | 늘 | 고른 글(Copy) |
| 글 영역 | Folding ▸ | RSyntaxTextArea `isCodeFoldingSupportedAndEnabled` | 접기를 지원함, `VARYING_TEXT`면 접기를 켬 | 지금 언어가 접기를 지원 |
| 글 영역 | Ask Chromite Agent About This Query(쿼리 창) | `AiAssistantPanel.java:1806` | 늘(쿼리 창에만 붙음) | – |
| Scripts | Open, Link to Connection…, Remove Connection Link | `ScriptsPanel.java:426·430·431`(스크립트에만 붙음) | 그대로 | 묶기 있음(Remove) |
| Scripts | New Folder | :427 프로젝트 있음 | 늘 | 프로젝트 있음 |
| Scripts | Rename, Delete | :428·429 루트 아님 | 루트가 아닌 폴더·스크립트 | – |
| Scripts | Refresh | 늘 | 늘 | – |
| Query Library | Run, Run on…, Edit, History…, Save As .sql… | `QueryLibraryPanel.java:618–629`(쿼리에만 붙음) | 그대로 | – |
| Query Library | Schedule…, Duplicate | :621·625 | 쿼리(붙는 곳 그대로) | 쓸 수 있는 루트(Schedule은 일정이 있으면 켜짐) |
| Query Library | New Query…, New Folder…, Import… | :623·624·631 | 늘 | 대상 폴더가 쓸 수 있는 루트 |
| Query Library | Rename…, Delete | :626·627 | 루트가 아닌 폴더·쿼리 | 쓸 수 있는 루트 |
| Query Library | Export… | :630 | 폴더(루트 포함, 쿼리에는 전처럼 붙지 않음) | 폴더가 있음(빠진 공유 폴더 아님) |
| Query Library | Pull, Commit & Push… | :635·636 | Git 작업 사본인 루트(그대로) | git 있음, 실행 중 아님, Commit은 쓸 수 있는 루트(도구 설명 그대로) |
| Query Library | Open Folder | :628 | 경로가 있음(빈 곳 아님) + Desktop이 폴더를 열 수 있음 | – |
| Query Library | Scheduled Queries…, Refresh, Library Roots… | 늘 | 늘 | – |
| Query Manager | Open in Query Window, Copy Statement | `QueryManagerPanel.java:670·671` 문장 있음 | 고른 행에 문장이 하나라도 있음(섞인 선택은 문장만 씀) | – |
| Query Manager | Show Transaction | :673 한 행 + 트랜잭션 + 지금 보는 것 아님 | 한 행이고 트랜잭션이 있음 | 지금 그 트랜잭션만 보고 있지 않음 |
| Query Manager | Clear | :675 행 있음 | 늘 | 행 있음 |
| Database Tasks | Run, Show Last Log, Schedule…, Delete… | `DatabaseTasksPanel.java:336–344`(작업에만 붙음) | 그대로 | 실행 중 아님, 실행 기록 있음 |
| Database Tasks | Edit… | :338 편집기가 있는 종류, 꺼지면 도구 설명 :340 | 편집기가 있는 작업 종류 | – |
| Database Tasks | New SQL Script Task…, Refresh | 늘 | 늘 | – |

### 경계에서 고른 것

- **Query Library의 읽기 전용 루트는 "지금"**: 지시서는 종류(숨김)로 보았지만, 코드상 같은 루트(같은 이름·경로)를 Library Roots… 대화상자의 Read-only 체크로
  바꿀 수 있다(`QueryLibraryRootsDialog.java:209`, `QueryLibrary.setRoots` 뒤 트리를 다시 읽음). 개체 탐색기·결과 그리드에서 읽기 전용 연결(연결 편집에서 바꿈)을
  "지금"으로 본 것과 같게, 쓰기 명령은 전처럼 꺼진 채 보인다. 루트 자체의 Rename·Delete는 Library Roots…에서만 하므로 루트에서 숨긴다.
- **여러 행 고르기**: Query Manager만 여러 행을 고를 수 있다(Scripts·Query Library 트리, Database Tasks 표는 하나만). 개체 탐색기 1부처럼 "명령이 지금 그 선택을
  다루는 대로" 정했다: 열기·복사는 고른 행 가운데 문장만 쓰므로 문장이 하나라도 있으면 보이고, Show Transaction은 한 행에만 작동하므로 여러 행이면 숨는다
  (개체 탐색기의 연결 여러 개에서 한 연결 명령을 숨긴 것과 같음).
- **자동 커밋으로 실행한 문장의 Show Transaction은 숨김**: 실행할 때 정해지는 문장의 성질이고(`Row.getTransaction`, 저장점 없음) 나중에 바뀌지 않는다.
- **값 패널**은 `VARYING_TEXT`라 메뉴가 전과 같다(편집 항목·Folding 꺼짐). 읽기 전용 결과의 칸이라도 같은 글 영역이 다음 칸에서 편집 가능해지기 때문이다.
- **Database Tasks의 도구 막대 Edit 단추**는 숨지 않고(도구 막대 규칙) 전처럼 꺼지지만, 꺼진 이유 도구 설명은 메뉴 항목과 함께 지웠다(지시서, 종류 이유).
  도구 설명은 이제 이름("Edit")이다. 표는 폴더를 보이지 않는다(작업 폴더가 있어도 모든 작업이 평평한 목록).
- **빈 곳을 누를 때**: Query Manager·Database Tasks 표는 빈 곳을 눌러도 선택을 지우지 않는다(전과 같음). 선택이 없을 때의 메뉴가 "빈 곳"이다.

### 실행 덤프로 만든 전·후 (2026-11-17, 영어·150 %, 같은 절차의 새 사설 작업 공간)

앱 안 테스트 에이전트(Swing 객체, OS 입력 없음)가 각 경우에 팝업 트리거를 보내고 열린 메뉴의 항목마다 보임·켜짐·도구 설명을 적었다. 전은 devel 20e976013f 빌드,
후는 이 변경의 빌드다. 터널 127.0.0.1의 PostgreSQL에 연결 셋(자동 커밋, 읽기 전용, 수동 커밋), 이 작업이 만든 `chr_sm_*` 개체만 썼다
(수동 커밋 연결의 `create table chr_sm_t`는 Query > Commit으로 명시적으로 커밋해 트랜잭션 행을 만들고, 끝에 자동 커밋 연결에서 지움: `pg_tables` 0행).

보이는 맨 위 항목 수(꺼진 수), 전 → 후:

| 경우 | 전 | 후 |
|---|---|---|
| 쿼리 창 편집기(고른 글 없음·있음, 읽기 전용 연결, 실행 중) | 9(4) | 9(4) |
| 값 패널 | 8(7) | 8(7) |
| 실행 계획 원본(늘 읽기 전용, 접기 없음) | 8(7) | 2(1) |
| Query Manager 미리 보기(늘 읽기 전용, SQL) | 8(6) | 3(1) |
| Scripts 루트 / 빈 곳 | 4(2) | 2(0) |
| Scripts 폴더 / 스크립트 | 4(0) / 7(1) | 4(0) / 7(1) |
| Query Library 쓸 수 있는 루트 | 10(2) | 8(0) |
| Query Library 읽기 전용 루트 | 10(5) | 8(3) |
| Query Library 폴더 / 쿼리 | 10(0) / 16(0) | 10(0) / 16(0) |
| Query Library 읽기 전용 폴더 / 쿼리 | 10(5) / 16(7) | 10(5) / 16(7) |
| Query Library 빈 곳 | 10(4) | 6(0) |
| Query Manager 자동 커밋 문장 | 4(1) | 3(0) |
| Query Manager 트랜잭션 안 문장 | 4(0) | 4(0) |
| Query Manager 연결 행 | 4(3) | 1(0) |
| Query Manager 트랜잭션(커밋) 행 | 4(2) | 2(0) |
| Query Manager 문장 + 연결 행 | 4(1) | 3(0) |
| Query Manager 연결 + 트랜잭션 행 | 4(3) | 1(0) |
| Database Tasks 편집할 수 있는 작업 / 없는 작업 | 7(1) / 7(2) | 7(1) / 6(1) |
| Database Tasks 빈 곳 | 2(0) | 2(0) |

- 전에 켜져 있던 (경우, 항목)이 후에 숨거나 꺼진 것: 0건. 구분선(맨 앞·맨 뒤·연달아 둘): 0건. 빈 메뉴: 0건(가장 작은 메뉴는 Query Manager 연결 행의 Clear 하나).
- 메뉴 막대 Edit(쿼리 창에 초점): 11항목 모두 전·후 같음. 도구 막대: Query Manager·Database Tasks 단추의 켜짐이 모든 경우에 같고, 달라진 것은 편집할 수 없는 작업에서
  Edit 단추의 도구 설명(이유 → 이름)뿐이다.
- 키(전·후 같음): 쿼리 창 Ctrl+Z/Ctrl+Y, 계획 원본과 Query Manager 미리 보기의 Delete·Ctrl+Z(글 그대로), Scripts 루트·빈 곳의 F2·Delete(아무 일 없음), 스크립트의
  F2(이름 바꾸기 대화상자)·Delete(삭제 확인, 취소), Query Library 쓸 수 있는 루트의 F2(아무 일 없음), 쿼리의 F2(이름 바꾸기, 취소), 읽기 전용 쿼리의 Delete(아무 일 없음),
  Database Tasks의 Delete(삭제 확인, 취소). Ctrl+C·Ctrl+X·Ctrl+V는 시스템 클립보드를 건드리므로 누르지 않았고, 이 변경은 키 바인딩·동작을 바꾸지 않는다(코드로 확인).

덤프·캡처: `D:\Tools\_scratch\small-menus-applicable\dumps\`(before.tsv, after.tsv, *-edit.tsv, *-bar.tsv, checks.txt, matrix.md),
`D:\Tools\_scratch\small-menus-applicable\sheets\`.

## upstream 대비 변경점

DBeaver(SWT)의 탐색기 메뉴는 Eclipse 명령의 `visibleWhen`/`enabledWhen`으로 정했다. Swing 프런트엔드는 그것이 없으므로 이 도우미가 같은 두 답을 한곳에 둔다.

## 변경 이력

- 2026-11-16 최초 작성: 규칙, `ContextMenus` 도우미, 개체 탐색기 적용(앱 변경 #137)
- 2026-11-16 Registered Servers: 서버 메뉴의 Change Password…를 지원하지 않는 연결된 서버에서 숨김(앱 변경 #137 2부)
- 2026-11-16 문서 탭·Messages: 쿼리 창 항목은 쿼리 창 탭에서만, 오류 위치로 가기는 오류 글자에서만 보임(앱 변경 #137 2부)
- 2026-11-16 ER 다이어그램: 개체 메뉴의 Design·Select Top은 해당하는 개체에서만 보임(앱 변경 #137 2부)
- 2026-11-16 연결·서버 명령은 연결 노드에서만, 프로젝트 명령은 빈 곳과 연결 노드에서만, Find Objects·Compare Schemas는 그곳과 데이터베이스·스키마에서만 보임
  (사용자 결정 "연결 노드에서만", `ContextMenus.setApplies`, 켜짐은 그대로)
- 2026-11-16 결과 그리드 셀 메뉴: 결과·칸의 종류에 해당하는 항목만 보임, 저장하고 커밋의 예외, 읽기 전용 연결 결과의 편집 명령(앱 변경 #137 3부, `chromite/result-grid-menu`)
- 2026-11-17 남은 작은 메뉴 5곳(SQL 편집기 글 영역과 같은 메뉴를 쓰는 모든 글 영역, Scripts, Query Library, Query Manager, Database Tasks)에 같은 규칙
  (사용자 "둘 다 고친다", 앱 변경 #141, `chromite/small-menus-applicable`, `ContextMenus.tidyTextArea`·`VARYING_TEXT`)
- 2026-11-17 유지 관리 명령 목록에 TTL(서버 확장이 그 표에 줄 때만 보임, 앱 변경 #152, [shardsphere.md](shardsphere.md) "TTL 관리")
- 2026-11-21 유지 관리 명령 목록에 Altibase 계열 작업·큐 명령, 큐의 열·키·인덱스는 꺼짐·디자이너 항목 숨김(앱 변경 #154, [altibase.md](altibase.md))
- 2026-11-22 ShardSphere 스트림 객체의 사용·사용 안 함·실행 설정·삭제와 스트림 폴더의 "New &lt;종류&gt;…" 틀 항목(앱 변경 #156, [shardsphere.md](shardsphere.md) "스트림 명령",
  [altibase.md](altibase.md) "확장 객체의 명령 자리")
- 2026-11-22 권한은 켜짐 조건에 넣지 않음: DDL 명령은 켜 둔 채 서버의 거절을 오류 창 첫 줄로 알림(앱 변경 #159, [swing-routines.md](swing-routines.md) "DDL 권한과 취소")
- 2026-11-22 Import Data…, Generate SQL의 행을 쓰는 항목(탐색기·결과 그리드)이 객체의 데이터 기능을 물음: 큐에는 숨음(앱 변경 #157, [altibase.md](altibase.md)
  "큐에 맞지 않는 데이터 명령")
