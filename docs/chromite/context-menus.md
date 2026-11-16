# 오른쪽 클릭 메뉴: 해당하는 명령만 보이고, 지금 할 수 없으면 꺼짐

## 목적

사용자 요청(2026-11-16, 앱 변경 #137):

- "개체 탐색기에서 노드를 우클릭 하면 나타나는 기능들이 실제 그 노드에서 수행 가능한 작업만 나타나는건가?"
  (대부분 늘 보이고 못 하면 흐리게 보인다고 답하자) "진행한다. 노드에서 수행 가능한 목록만 나타나게 하고 작업은 가능하지만 현재 조건에 따라
  불가능한건 비활성화로 처리한다"
- "개체 탐색기를 언급했는데 다른 부분도 전반적으로 점검해본다" (다른 오른쪽 클릭 메뉴도 같은 규칙으로 점검)

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
- 상태 짝은 짝으로 남는다: 연결 노드의 Connect / Disconnect(하나는 꺼짐), 사용 / 사용 안 함, 읽기 전용 / 읽기 쓰기, 온라인 / 오프라인.
- 하위 메뉴(Tasks, Script as, Generate SQL, Recent Scripts, New …)는 안의 항목이 하나도 해당하지 않으면 숨고, 해당하지만 지금 할 수 있는 것이 없으면 꺼진다.
- 구분선은 맨 앞·맨 뒤·연달아 둘이 되지 않는다. 묶음이 모두 숨으면 그 구분선도 사라진다.
- 남은 항목의 순서는 그대로다.
- 도구 막대 단추와 두 번 클릭은 전처럼 켜짐만 본다. 숨은 항목은 늘 꺼져 있다(해당하지 않으면 켜지지 않음). 트리의 키(F2, Delete, F7, F5,
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
| New Connection… | 1256 `projectNode != null` | 프로젝트(어디서나) | – |
| New Query, Recent Scripts | 1258–1259 `dataSource != null` | 연결 아래 노드 | – |
| Set as Default | 1328 `canSetDefault` | `appliesSetDefault` | 연결됨, 기본값 아님 |
| New Table… | 1281 `canCreateTable(folder)` | 같음(폴더의 자식 종류, 드라이버) | – |
| New View/Procedure/Function… | `SourceEditCommands.update` `getNewKinds` | 같음(폴더 종류, 방언) | – |
| New &lt;종류&gt;… | `NewObjectCommands.update`(이미 숨김) | 같음 | – |
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
| Generate SQL | `ExplorerObjectCommands.update` 생성기 수 | 생성기가 있음 | – |
| View Diagram | 1268 `canShow` | 같음 | – |
| Object Details | 1326 | DB 노드이고 객체이거나 자식이 있는 폴더 | 연결 노드는 연결됨 |
| Tasks ▸ Back Up…/Restore… | 1272–1273 `canBackUp` | 같음 | – |
| Tasks ▸ Run Script with Native Client… | 1274 `canRunScript` | 같음, 연결 노드는 `BackupRestoreCommands.appliesRunScript`(PostgreSQL·MySQL/MariaDB의 네이티브 클라이언트, Oracle; 닫혀 있으면 알 수 없어 보임) | `canRunScript`(연결 노드는 DB 하나를 읽었음) |
| Tasks ▸ Import/Export/Copy Tables… | 1270–1275 | 그 객체에 해당, 연결 노드는 `DataTransferObjects.appliesToConnection`(맨 위가 스키마나 데이터베이스; 닫혀 있으면 보임) | 연결 노드는 연결했고 읽은 DB가 하나 |
| Tasks | 1277 | 하위 항목에서 | 하위 항목에서 |
| Maintenance… | 1279 `canMaintain` | 같음 | – |
| 유지 관리 명령(TRUNCATE, 트리거·테이블스페이스·파일 그룹 …) | `ObjectMaintenanceCommands.update`(이미 숨김) | 그대로(`supports`) | 그대로(`changesState`) |
| Connect | 1287 | 연결 아래 노드 | 연결 안 됨 |
| Disconnect | 1289 | 연결 아래 노드 | – |
| Disconnect Others | 1291 | 연결 아래 노드 | 다른 연결된 서버가 있음 |
| Disconnect All | 1292 | 프로젝트(어디서나) | 연결된 서버가 있음 |
| Reconnect, Invalidate/Reconnect | 1293–1294 | 연결 아래 노드 | 연결됨 |
| Activity Monitor, Locks, Server Dashboard, Disk Usage, Server Logs, Maintenance, Replication | 1295–1298 | 연결 아래 노드 | – |
| Find Objects…, Compare Schemas… | 1299, 1301 | 프로젝트(어디서나) | – |
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

- **연결 명령은 연결 아래 모든 노드에 해당한다**(Connect/Disconnect/Reconnect, Activity Monitor 등). 클래스 Javadoc의 설계("연결 아래 어느 노드에서나")와
  전의 켜짐을 그대로 지킨다. 테이블에서도 Connect가 꺼진 채 보인다(연결 여부는 "지금").
- **New Connection, Disconnect All, Find Objects, Compare Schemas는 프로젝트 명령이라 어디서나 해당한다**(여러 개 선택 포함). 전에 켜져 있던 곳에서 사라지지 않게 했다.
- **닫힌 연결 노드에서 데이터 원본이 있어야 아는 것**(Change Password, Run Script with Native Client, Import/Export/Copy Tables)은 꺼진 채 보인다.
  연결하면 지원하지 않는 DB(SQLite 등)에서는 숨는다. 그래서 같은 연결이 닫혔을 때와 열렸을 때 항목 수가 다를 수 있다.
- **연결 노드의 Import/Export/Copy Tables**는 맨 위가 스키마나 데이터베이스인 연결에 해당하고, DB가 여러 개이거나 아직 읽지 않았으면 꺼진다
  (DB 수와 읽었는지는 바뀔 수 있는 "지금").
- **SQL Server 에이전트가 없는 에디션**(Express)은 서버의 종류라 바꾸는 명령을 숨긴다(전에는 꺼짐과 도구 설명). 보기 명령은 남는다.
- **작업 스케줄러의 꺼짐**(Oracle 권한, pg_cron 미설치·다른 DB·버전 기능, 읽기 전용)은 그대로 둔다. 확장 설치·버전은 노드가 그대로인 채 바뀔 수 있어 "지금"으로 보았다.
- **읽기 전용 연결**에서 전에도 켜져 있던 Delete·Rename 등은 실행할 때 알리고 멈춘다(`checkModifiable`). 켜짐을 바꾸지 않았다(새로 끄면 단축키 동작이 바뀜).
- **여러 개 선택이 연결을 넘으면** 유지 관리 명령은 전처럼 숨는다(`ObjectMaintenanceCommands`의 한 연결 조건).

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

덤프·캡처: `D:\Tools\_scratch\explorer-menu-applicable\dumps\`(before.tsv, after.tsv, matrix.md, checks.txt, p2-*.tsv),
`D:\Tools\_scratch\explorer-menu-applicable\sheets\`.

## 다른 오른쪽 클릭 메뉴

코드에서 `JPopupMenu`, `setComponentPopupMenu`, `isPopupTrigger`, 팝업 `show(`로 찾은 메뉴 전체를 점검했다(2026-11-16). 고친 것은 "바꿈", 이미 규칙대로인 것은 "맞음",
고칠 것이 있지만 이번에 하지 않은 것은 "남김"이다. 줄 번호는 이 변경 전 devel 기준이다.

| 곳 | 파일:줄 | 항목과 판단 | 결과 |
|---|---|---|---|
| Registered Servers 트리 | `RegisteredServersPanel.java:369` | 서버·그룹·빈 곳마다 따로 만든다(종류는 이미 숨김). 서버의 Change Password…만 연결된 서버의 DB가 지원하지 않아도 꺼진 채 보였다(:382) | 바꿈: 연결된 서버가 지원하지 않으면 숨김, 연결 안 됨이면 꺼짐(개체 탐색기와 같음) |
| 문서 탭 | `DocumentTabMenu.java:235` | 저장·이름 바꾸기·연결 변경·경로 복사·상위 폴더 열기가 쿼리 창이 아닌 탭(ER 다이어그램, Activity Monitor, 디자이너)에서 꺼짐(:242–260) | 바꿈: 쿼리 창 탭에서만 보임, 저장 전 창의 경로 항목·실행 중 창의 연결 변경은 꺼짐, `ContextMenus.tidy` |
| Messages 탭 | `QueryResultsView.java:654` | "오류 위치로 가기"가 위치 없는 글자에서 꺼짐(:688) | 바꿈: 위치를 아는 오류 글자에서만 보임 |
| ER 다이어그램 개체 | `ErdDiagramPanel.java:489` | Design(뷰에서 꺼짐 :495), Select Top(:499) | 남김 |
| SQL 편집기 글 영역 | `SqlEditorPanel.java:3434`(RSyntaxTextArea 기본 메뉴) | 읽기 전용 글 영역의 실행 취소·잘라내기·붙여넣기·삭제, 접기 미지원의 Folding이 꺼짐(라이브러리가 만든 항목) | 남김: 라이브러리가 만든 항목을 위치·동작으로 골라 숨겨야 함(구조 변경) |
| 결과 그리드 셀 | `ResultSetGrid.java:1219`, `ResultSetViewerPanel.java:1084` | 행 삽입·복제·삭제(지원 안 함), Export Data(SELECT 아님), Generate SQL 항목(원본 테이블·키 없음), 파일 불러오기·저장·16진수·외부 편집기(값 종류), 가상 열의 Filter by Value, Saved Filters·Virtual Keys·Virtual Columns(원본 테이블 없음), Count Rows가 종류 이유로 꺼짐. Save and Commit은 커밋 방식(지금)으로 숨음(:1307) | 남김: 한 판단에 두 이유가 섞인 곳이 많아 나눠야 함(가장 큼, 약 하루) |
| 결과 열 머리글, Ctrl+B·Ctrl+Shift+1 팝업 | `ResultSetViewerPanel.java:924`, `:2584`, `:2604` | 누른 곳에 따라 추가, 꺼짐은 지금 상태 | 맞음 |
| 열린 트랜잭션 칸 | `QueryStatusLine.java:448` | 읽기 전용 트랜잭션은 다른 항목 | 맞음 |
| 집계 함수, 차트 값 | `ResultSetAggregatePanel.java:188`, `ResultSetChartView.java:443` | 숫자 열만 추가, 8개 제한은 꺼짐 | 맞음 |
| 관리 그리드(유지 관리, 복제, 서버 로그, 공간 사용량) | `AdminGridTable.java:94` | 복사 항목만, 늘 켜짐 | 맞음 |
| SQL 터미널 | `SqlTerminalView.java:74` | 복사는 선택(지금) | 맞음 |
| Scripts 트리 | `ScriptsPanel.java:434` | 루트·빈 곳의 이름 바꾸기·삭제가 꺼짐(:428) | 남김(작음) |
| Query Library 트리 | `QueryLibraryPanel.java:655` | 읽기 전용 루트의 새 쿼리·새 폴더·가져오기·복제·이름 바꾸기·삭제·일정·Commit & Push가 꺼짐, Desktop 미지원의 폴더 열기 | 남김 |
| Query Manager(기록) | `QueryManagerPanel.java:731` | 세션·트랜잭션 행만 고르면 열기·복사가 꺼짐, 트랜잭션 없는 행의 Show Transaction | 남김 |
| Database Tasks | `DatabaseTasksPanel.java:354` | 편집할 수 없는 작업 종류의 Edit가 꺼짐(도구 설명) | 남김(작음) |
| 작업 대화상자 Script, Data Search 결과, ERD 배경·내보내기, 대시보드, 도구 창 ⋮, 도구 막대 넘침, 업데이트 단추, Agent 플레이북·모델·기록, SQL 템플릿 고르기 | 각 파일 | 늘 해당하거나 지금 상태로만 꺼짐 | 맞음 |
| Text·Server Output·실행 계획 탭, 테이블 디자이너, Properties, Object Details, Find Objects, Activity Monitor, 결과 행 머리글, 레코드 보기 | – | 오른쪽 클릭 메뉴 없음 | – |

메뉴 막대(`MainFrame`)의 Query 메뉴(Debug, Trace, Native Script는 활성 편집기의 개체로 켬), Window 메뉴(열린 탭), File ▸ Recent는 선택에 따라 바뀌지만
메뉴 막대이므로 숨기지 않는다(규칙 참고).

## upstream 대비 변경점

DBeaver(SWT)의 탐색기 메뉴는 Eclipse 명령의 `visibleWhen`/`enabledWhen`으로 정했다. Swing 프런트엔드는 그것이 없으므로 이 도우미가 같은 두 답을 한곳에 둔다.

## 변경 이력

- 2026-11-16 최초 작성: 규칙, `ContextMenus` 도우미, 개체 탐색기 적용(앱 변경 #137)
- 2026-11-16 Registered Servers: 서버 메뉴의 Change Password…를 지원하지 않는 연결된 서버에서 숨김(앱 변경 #137 2부)
- 2026-11-16 문서 탭·Messages: 쿼리 창 항목은 쿼리 창 탭에서만, 오류 위치로 가기는 오류 글자에서만 보임(앱 변경 #137 2부)
