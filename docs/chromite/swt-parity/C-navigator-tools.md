# SWT → Swing 기능 대조: 영역 C (탐색기와 도구)

- 기준: SWT는 `263b8ddb45`의 트리(`git show`/`git ls-tree`/`git grep`), Swing은 `D:\Chromite` 작업 트리(HEAD `8e8aaaef60`).
- 읽기 전용 조사다. 저장소 파일은 고치지 않았고, 빌드·실행·회귀 테스트는 하지 않았다. 아래 판정은 코드와 문서를 읽은 결과이고 실제 화면으로 확인하지 않았다.
- 상태: **있음** / **일부** / **없음** / **의도적 제외** / **조용히 끊김**. 이 밖에 **해당 없음**을 따로 두었다.
  "해당 없음"은 SWT 제품에도 기능이 없었거나, 번들이 SWT 제품에 들어가지 않아 사용자가 볼 수 없던 경우다(집계에서 따로 셈).
- 영향: 높음(매일 쓰는 DB 작업, 데이터 안전, 잠금·트랜잭션) / 중간 / 낮음.

## 먼저 알아 둘 사실 (근거)

1. **SWT 제품에 실제로 들어 있던 번들.** `product/community/Chromite.product`는 `ce.feature` 하나를 쓰고, 그 아래 피처는
   `ui.feature`(`ui.navigator`, `ui.search`, `ui.ai`, `ui.charts`, `data.transfer.ui`, `data.gis.view`, `net.ssh.ui` 등),
   `ui.extra.feature`(`tasks.ui`, `tasks.ui.view`, `tasks.native.ui`, `tasks.sql.ui`, `cmp.simple.ui`, `ui.dashboard`)다.
   `debug.feature`, `git.feature`, `ext.office.feature`(`data.office.ui`), `ui.test.feature`(`ui.app.devtools`)는 `features/pom.xml`의 빌드 모듈일 뿐
   어느 제품·p2 카테고리(`product/community/category.xml`은 ui, runtime, db, db.ui 피처만)에도 들어 있지 않았다.
   `ui.datadam`은 어떤 피처에도 없다(docs/chromite/telemetry.md:110-113).
   → **PostgreSQL 디버거, Git, "Open in Excel", 개발자 Test 메뉴, DataDam은 Chromite SWT 제품 사용자도 쓸 수 없던 기능이다.**
2. **"의도적 제외" 근거가 있는 것은 AI 모델 카탈로그뿐이다.** 디버거와 Git은 문서에 "삭제 결정"이 아니라 "Swing으로 옮길 때까지 없는 기능"
   (docs/chromite/swt-removal.md:95, 103, 109)으로 적혀 있다. 브리프가 말한 "이식 대기"라는 낱말은 저장소 문서·브리프·메모리 어디에도 없다
   (`grep -rn '이식'` 0건). 그래서 이 둘은 "없음(이식 대기 목록에 있음)"으로 판정했다.
3. **SWT CE에는 작업 스케줄러 구현이 없었다.** 모델에 `DBTScheduler` 인터페이스와 레지스트리, `tasks.ui.view`에 속성 검사기(`TaskPropertyTester.java:54-58`)만 있고,
   어느 `plugin.xml`에도 `<scheduler` 기여가 없다(`git grep '<scheduler' 263b8ddb45 -- 'plugins/*/plugin.xml'` 0건). 예약 실행은 DBeaver 상용판 기능이다.
4. **"즐겨찾기(favorites)"는 SWT CE에 없던 기능이다.** `git grep -il favorite 263b8ddb45 -- plugins`는 샘플 DB(`Chinook.db`), 단어 목록(`DDBip39Wordlist`),
   Swing의 `DatabaseTypeCombo`만 나온다.
5. **"최근 연결"은 SWT에서 별도 목록이 아니었다.** SWT는 도구 막대의 활성 연결 콤보와 연결 선택 대화상자(`SelectActiveDataSourceHandler`, `SelectDataSourceDialog`)였고,
   `ui.navigator`/`core`/`ui`에서 `recent`로 찾으면 드라이버 기록과 `openRecentScript`(연결별 최근 스크립트)뿐이다.
6. **swt-removal.md의 표 한 줄이 낡았다.** `swt-removal.md:111`은 "AI 채팅·AI 자동완성 … `model.ai` 참조 0"이라고 하지만,
   #92(`d00afd8827`)로 AI Assistant 도구 창(`AiAssistantPanel`, `AiOptionsPage`)이 들어왔다. 보고만 한다(이 조사는 문서를 고치지 않음).

## 1. `ui.navigator` (Database Navigator, 프로젝트·자원, 탐색기 동작)

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|---|
| 1 | DB 탐색기 트리(연결 → DB → 스키마 → 객체, 백그라운드 지연 읽기, 새로 고침 F5, 모델 이벤트 반영, 아이콘·보조 글자·툴팁) | `DatabaseNavigatorView`, `DatabaseNavigatorTree`, `NavigatorHandlerRefresh` | 있음 | `ObjectExplorerPanel.java:100-160`(설계 주석), `:1607`(노드 설명 툴팁) | 높음 | SSMS식으로 "연 서버만" 보인다(`swing-registered-servers.md:5-7`) |
| 2 | 연결 노드 명령: 새 연결, 연결·끊기·다시 연결, 연결 편집, 이름 바꾸기(F2), 삭제(Delete) | `DataSourceHandler`(core), `NavigatorHandlerObjectRename/Delete` | 있음 | `ObjectExplorerPanel.java:124-126, 553-569`, `ConnectionCommands` | 높음 | |
| 3 | 연결 폴더 만들기·이름 바꾸기·삭제·옮기기(끌어 놓기 포함) | `NavigatorHandlerLocalFolderCreate`, `DBNLocalFolder.dropNodes`, `NavigatorObjectsDeleter.deleteLocalFolder` | 있음 | Registered Servers의 서버 그룹(`swing-registered-servers.md:36-46`, `RegisteredServerCommands`) | 중간 | 저장 형식은 같은 연결 폴더 |
| 4 | 탐색기 필터: 필터 편집 대화상자(포함·제외 마스크 여러 개, 전역 필터), 선택 객체로 포함/제외 추가, 필터 켜기/끄기, 지우기 | `EditObjectFilterDialog`, `NavigatorHandlerFilterConfig/Include/Exclude/Toggle/Clear` | 일부 | `ObjectExplorerPanel.java:887-915`: 고른 폴더에 **포함 마스크 하나**만 입력 창으로 건다(빈 값이면 지움). 제외 마스크, 여러 마스크, 켜기/끄기, 선택에서 추가는 없음 | 중간 | 같은 `DBSObjectFilter`라 저장된 필터는 그대로 적용된다 |
| 5 | 트리 빠른 거르기(검색 상자, `navigator.filter.focus`), "연결된 것만" 필터, 객체 종류 필터 | `NavigatorHandlerFocusFilter`, `NavigatorHandlerConnectionFilter`, `NavigatorHandlerFilterObjectType`, `TreeNodeFilterSearch` | 없음 | `ObjectExplorerPanel`에 거르기 입력란 없음. 이름 찾기는 별도 문서 탭 Find Objects(#27) | 낮음 | |
| 6 | 편집기와 연결(Link with editor, 편집기 바꿀 때 탐색기 선택 동기화) | `NavigatorHandlerLinkEditor`, `NAVIGATOR_SYNC_EDITOR_DATASOURCE` | 없음 | `revealNode`는 Find Objects·ER 다이어그램에서만 부름(`MainFrame.java:770-790`). 동기화 코드 grep 0 | 낮음 | |
| 7 | 두 번 클릭 동작 설정(연결: 편집/연결/최근 스크립트/새 스크립트, 객체: 편집기 열기/펼치기, 기본 편집기 페이지) | `NavigatorViewBase.java:157-221`, `NavigatorPreferences.DoubleClickBehavior` | 없음 | `ObjectExplorerPanel.java:122`: 두 번 클릭은 펼치기/접기만(에이전트·스케줄러 노드 예외 `:661-668`) | 낮음 | |
| 8 | 탐색기 환경설정(연결 시 펼치기, 상태 복원 깊이, 드라이버별 묶기, 통계·호스트·설명·자식 수 표시, 툴팁, 긴 목록 크기 등) | `PrefPageDatabaseNavigator`, `NavigatorPreferences` | 없음 | `OptionsDialog.java:178-183` 탭은 General, Editor, Editor Colors, Templates, Updates, AI뿐 | 낮음 | |
| 9 | 긴 목록 나눠 읽기("더 보기" 노드), 통계 렌더러(테이블 크기 막대) | `TreeNodeLazyExpander`, `StatisticsNavigatorNodeRenderer` | 없음 | `ObjectExplorerPanel`은 자식을 한 번에 다 읽는다(`readChildren`). grep `lazy|fetch size` 0 | 낮음 | 테이블 수천 개 스키마에서 느릴 수 있음(미확인). 크기는 Disk Usage 탭에 있음 |
| 10 | DB 객체 이름 바꾸기(F2, `DBEObjectRenamer`: 테이블·열·뷰 등) | `NavigatorHandlerObjectRename` | 있음 | 2026-11-07 옮김: F2·메뉴 "이름 바꾸기"가 객체 관리자가 이름을 바꿀 수 있는 DB 객체에서 켜진다(`ExplorerObjectCommands.canRename`/`rename`: `DBEObjectRenamer`, 원자적 명령 묶음, 늘 스크립트 미리 보기, 실행 전 열린 트랜잭션 질문). [swing-explorer-object-actions.md](../swing-explorer-object-actions.md) | 중간 | 트리 안 인라인 편집이 아니라 입력 창(연결 이름 바꾸기와 같음) |
| 11 | DB 객체 삭제(DROP, 여러 개, 연쇄 옵션, 스크립트 미리 보기) | `NavigatorHandlerObjectDelete`, `NavigatorObjectsDeleter`, `NavigatorNodesDeletionConfirmations` | 있음 | 2026-11-07 옮김: Delete 키·메뉴 "삭제"가 객체 관리자가 지울 수 있는 DB 객체(여러 개, 한 연결)에서 켜진다(`ExplorerObjectCommands.canDelete`/`delete`): 목록 확인, 연쇄 삭제·기존 연결 닫기 옵션, 늘 스크립트 미리 보기, 실행 전 열린 트랜잭션 질문. [swing-explorer-object-actions.md](../swing-explorer-object-actions.md) | 중간 | 열 삭제의 종속 객체 확인(`DBEObjectWithDependencies`)은 없음. Script as의 DROP은 여전히 없음(삭제의 미리 보기 "쿼리 창에서 열기"로 대신) |
| 12 | 복사(Ctrl+C: 객체 이름·정규화 이름), Copy Special(고급 복사), 붙여넣기로 객체·연결 복제 | `NavigatorHandlerCopyObject`, `NavigatorHandlerCopySpecial`, `NavigatorHandlerObjectCreateCopy` | 일부 | 2026-11-07: Object Explorer의 "이름 복사"(Ctrl+C, Edit > 복사)·"정규화된 이름 복사"(Ctrl+Shift+C, 방언 따옴표), 여러 개 선택(`ExplorerObjectCommands`, 트리 `DISCONTIGUOUS_TREE_SELECTION`). [swing-explorer-object-actions.md](../swing-explorer-object-actions.md). Copy Special 대화상자, 붙여넣기 복제는 없음 | 중간 | 연결 복제(같은 설정으로 새 연결)도 없음 |
| 13 | 끌어 놓기: 객체를 SQL 편집기로(이름 넣기), 연결을 폴더로 | `NavigatorDragSourceListener`, `NavigatorDropTargetListener` | 일부 | 서버 → 그룹 끌어 놓기는 `RegisteredServersPanel`에 있음. `ObjectExplorerPanel`에는 `TransferHandler` 없음 | 낮음 | |
| 14 | 새 객체 만들기(모든 폴더의 "Create New …": 스키마·시퀀스·트리거·인덱스·데이터베이스 등), 열에서 인덱스·제약 만들기 | `NavigatorHandlerObjectCreateNew`, `NavigatorHandlerCreateColumnIndex/Constraint` | 일부 | Swing은 New Table / View / Procedure / Function / Login·Role·User / 에이전트·스케줄러 작업만(`ObjectExplorerPanel.java:596-603`). 인덱스·제약은 테이블 디자이너. 범용 `DBEObjectMaker` 사용은 `TableDesignSession`뿐 | 중간 | |
| 15 | 객체 편집기(엔터티 편집기: 속성 편집·저장·되돌리기·미리 보기, 폴더 탭, 하이퍼링크) | `EntityEditor`, `ObjectPropertiesEditor`, `FolderEditor`(이 번들 안) | 일부 | 읽기 전용 Properties 창(`PropertiesPanel` 주석 "읽기 전용이다"), View DDL, Design, Modify. `openEntityEditor`는 로그만(`SwingPlatformUI.java:496-505`) | 중간 | 편집기 영역 조사와 겹침 |
| 16 | 폴더 내용 표(폴더를 열면 객체 목록을 속성 열로) | `FolderEditor`, `ItemListControl` | 없음 | `ObjectListTable`은 세션·잠금·에이전트 활동에만 쓰임(`AgentJobActivityPanel.java:88`, `LockManagerPanel.java:108,115`) | 낮음 | SSMS "Object Explorer Details"에 해당 |
| 17 | Generate SQL 메뉴(SELECT, INSERT, UPDATE, DELETE, MERGE, SIMPLE/INNER JOIN, CALL, DDL, PG `INSERT ON CONFLICT`·`UPDATE FROM`·`DELETE USING`, Firebird 2종) | `ui.editors.sql` `SQLGeneratorContributor` + `model.sql` 생성기 | 있음 | 2026-11-07 옮김: 메뉴 "SQL 생성"이 생성기 확장점에서 선택(여러 개 포함)에 맞는 생성기를 모두 보인다(`ObjectScripts.getGenerators`, SWT와 같은 순서·구분선). 결과는 새 쿼리 창(실행하지 않음). [swing-explorer-object-actions.md](../swing-explorer-object-actions.md) | 중간 | 결과 행에서 만드는 생성기(`dataInsert` 등)는 결과 영역. 생성기 옵션 화면은 없음 |
| 18 | 트리에서 기본 스키마·카탈로그 지정 | `NavigatorHandlerSetDefaultObject` | 일부 | 쿼리 창 도구 막대의 연결·DB 콤보(`swing-frontend.md:933`) | 낮음 | |
| 19 | 활성 연결·스키마 고르기(도구 막대 콤보, Ctrl+9 연결 선택·Ctrl+0 스키마 선택 대화상자) ― 브리프의 "최근 연결" | `SelectActiveDataSourceHandler`, `SelectActiveSchemaHandler`, `SelectDataSourceCombo` | 일부 | 콤보 있음(`SqlEditorToolBar`), Connect 대화상자의 드라이버별 최근 서버 기록(`ConnectionHistory`). Ctrl+9/0 대화상자 없음(`VK_9` grep 0) | 낮음 | 위 "먼저 알아 둘 사실" 5 |
| 20 | 북마크(Ctrl+Alt+Shift+D로 추가, 프로젝트의 Bookmarks 폴더, 북마크로 이동) | `core` `AddBookmarkHandler`, `NavigateBookmarkHandler`, `model.rcp` 자원 형식 `bookmark` | 없음 | Swing에서 `bookmark`는 주석 한 줄뿐(`ObjectExplorerPanel.java:1093`) | 낮음 | swt-removal.md:108에 포함 |
| 21 | 프로젝트 관리(Projects 보기, 새 프로젝트 마법사, 활성 프로젝트 바꾸기, 프로젝트 설정·자원 위치 설정 페이지) | `ProjectNavigatorView`, `ProjectCreateWizard`, `NavigatorHandlerProjectSetActive/Select`, `PrefPageProjectResourceSettings` | 없음 | Swing은 `getActiveProject()`만 쓰고 `setActiveProject` 호출 0. swt-removal.md:108 | 낮음 | 프로젝트가 하나면 영향 없음. 다른 프로젝트의 연결은 Swing에서 볼 수 없음 |
| 22 | 자원 탐색기(Scripts 폴더·파일 트리, 새 파일·폴더, 파일·폴더 링크, 파일 시스템 탐색기, 스크립트와 연결 묶기, 시스템 탐색기에서 보기, 연결별 최근 스크립트 열기) | `ProjectExplorerView`, `FileSystemExplorerView`, `NavigatorHandlerCreateFile/Folder`, `LinkFile/FolderHandler`, `NavigatorHandlerAssociateScript`, `NavigatorHandlerShowInExplorer` | 없음 | Swing은 SSMS식 File > Open / Save / Save As / Recent Files(`MainFrame.java:112-114`). 스크립트-연결 묶기 grep 0(연 파일은 고른 연결로 열림) | 중간 | swt-removal.md:108 |
| 23 | 스크립트를 네이티브 클라이언트로 실행(psql·mysql 등) | `NavigatorHandlerExec`, `SQLNativeExecutorRegistry` | 없음 | `scriptNative` grep 0 | 낮음 | |
| 24 | 연결 노드 인라인 단추(연결 설정, 터널, 읽기 전용, 연결 보기) | `NNAHDataSourceConfiguration/Tunnel/ReadOnly/ConnectionView` | 일부 | 같은 일은 메뉴·연결 대화상자로(읽기 전용은 `ConnectionSettingsPanel`) | 낮음 | |
| 25 | Database Browser 보기(하위 트리를 따로 연 보기) | `DatabaseBrowserView` | 없음 | 해당 클래스·명령 없음 | 낮음 | |
| 26 | 노드 이동 명령(맨 위·아래·위·아래로) | `NavigatorHandlerObjectMove` | 없음 | grep 0 | 낮음 | 순서가 있는 객체에만 쓰이던 명령 |
| 27 | 객체로 가기(이름으로 찾아 열기) | `GotoObjectDialog`, `NavigatorHandlerObjectGoto` | 있음 | Find Objects 문서 탭(`ObjectSearchPanel`, 결과에서 `revealNode`) | 중간 | |
| — | 즐겨찾기 | — | 해당 없음 | SWT CE에 기능 없음(위 사실 4) | — | |

## 2. `ui.search`

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|---|
| 28 | 메타데이터 검색(이름·종류·대소문자, 여러 연결, 범위) | `SearchMetadataPage`, `SearchMetadataQuery` | 있음 | `ObjectSearchPanel` 설계 주석(같은 `DBSStructureAssistant#findObjectsByMask`) | 중간 | |
| 29 | 데이터 검색(여러 테이블의 값 찾기) | `SearchDataPage`, `SearchDataQuery` | 있음 | 2026-11-08 옮김: Object Explorer "Search Data…" 문서 탭(`DataSearchPanel`, 모델 `DataSearch`, [swing-data-search.md](../swing-data-search.md)) | 중간 | 인덱스 열만 찾기·외부 테이블 옵션은 옮기지 않음 |
| 30 | Quick Search(작업 공간 파일 글자 찾기) | `OpenQuickSearchAction`(`org.eclipse.text.quicksearch`) | 없음 | grep 0. 파일 트리 자체가 없음(#22) | 낮음 | |

## 3. `ui.dashboard`, `ui.charts`

`ui.charts`는 JFreeChart의 SWT 연결부이고 쓰는 곳은 `ui.dashboard`뿐이다(`git grep com.seizia.chromite.ui.charts 263b8ddb45 -- '*/MANIFEST.MF'`). 결과 표의 차트 보기는 CE에 없었다.
2026-11-08 Chromite가 결과 표의 차트 보기를 새로 더했다(선·막대·원형, PNG 저장, [swing-result-charts-spatial.md](../swing-result-charts-spatial.md)). 아래 33(대시보드 차트 상호작용)과는 별개다.

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|---|
| 31 | 연결의 서버 대시보드(기본 차트, 실시간 갱신, 간격, 차트 고르기, 일시 중지) | `DataSourceDashboardView`, `DashboardUpdater` | 있음 | Activity Monitor 개요 탭(`DashboardPanel`, `swing-dashboard.md:9-15, 22-41`) | 중간 | 차트 정의는 같은 `DashboardRegistry` |
| 32 | 사용자 정의 대시보드(만들기·삭제, 여러 대시보드, 항목 추가·제거·초기화, 카탈로그, 항목 구성 편집, 보기 설정, 브라우저 보기, `.dashboard` 자원, 대시보드 환경설정) | `HandlerDashboard*`, `DashboardItemConfigurationDialog`, `DashboardManagerDialog`, `DashboardCatalogPanel`, `PrefPageDashboards` | 없음 | `swing-dashboard.md:159-160`, swt-removal.md:107 | 낮음 | |
| 33 | 차트 상호작용(큰 창으로 보기, 이미지 저장·복사·인쇄, 차트 속성 편집, 범례·격자·축 끄기, 막대·원형) | `DashboardItemViewDialog`, `ui.charts` `SWTChartEditor`, `ChartPrintJob` | 없음 | `swing-dashboard.md:161-162`. `DashboardChart`에 저장·인쇄 코드 없음 | 낮음 | |

## 4. `data.transfer.ui`, `data.office.ui`

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|---|
| 34 | 내보내기 마법사(테이블·뷰·스키마·쿼리 결과 → CSV, DbUnit, HTML, JSON, Markdown, SQL, Source code, TXT, XML, XLSX; 형식 속성; 폴더·이름 패턴·문자 집합·BOM·한 파일·ZIP·클립보드; 결과 표의 필터·정렬 반영) | `DataTransferWizard`, `StreamConsumerPageSettings/Output`, `DataExportHandler` | 있음 | `DataExportWizard`, `swing-data-transfer.md:34-60, 79-100` | 높음 | |
| 35 | 가져오기(CSV → 기존·새 테이블, 열 매핑, 미리 보기) | `DataImportHandler`, `StreamProducerPageSettings`, `PreviewMappingDialog` | 있음 | `DataImportWizard`(미리 보기 `DataImportWizard.java:447-455`), `swing-data-transfer.md:135-145` | 높음 | XLSX 가져오기는 모델에 처리기가 없어 SWT에도 없음 |
| 36 | DB 간 테이블 복사(대상 고르기, 매핑 종류, 새 테이블 형식, 참조 무결성 끄기, 대량 로드) | `DatabaseConsumerPageMapping`, `ColumnsMappingDialog`, `ConfigureMetadataStructureDialog`, `DatabaseConsumerPageLoadSettings` | 일부 | `DatabaseCopyPages`. 없는 것: 매핑 규칙(`MappingRulesDialog`), 열 변환기(`AttributeTransformerSettingsDialog`), 새 테이블 속성, 기존 테이블 고르기 대화상자, 매핑 저장·불러오기, 중복 키 처리, 외래 키 옮기기(`swing-data-transfer.md:432-433, 527-528`) | 중간 | |
| 37 | 내보내기 고급(열 고르기, 값 형식 프로필, 파일 분할, 추출 설정: 별도 연결·가져오기 크기·선택 행만) | `ConfigureColumnsDialog`, `ValueFormatSelector`, `DatabaseProducerPageExtractSettings` | 일부 | 마법사에 없음(`swing-data-transfer.md:431, 535-536`). 선택 행·가져온 행만은 결과 표의 Export to CSV/XLSX로만 | 낮음 | |
| 38 | 끝난 뒤 처리기: 폴더 열기, 실패한 파일 지우기, 명령 실행 | `ShowInExplorer…`, `FailedExportFileCleaner…`, `ExecuteCommandEventProcessorConfigurator` | 일부 | 앞의 둘은 있음(`swing-data-transfer.md:44`), 명령 실행은 없음(`:431`) | 낮음 | 모델의 명령 실행은 `UIServiceShellCommands`가 필요한데(`ExecuteCommandEventProcessor.java:48`) Swing에 구현 없음. 화면에서 켤 수 없어 끊길 경로는 없음 |
| 39 | 데이터 전송을 작업으로 저장·편집 | `DataTransferTaskConfigurator` | 없음 | `swing-data-transfer.md:431, 528`. Swing은 임시 작업만(`DataTransferWizard.java:524-533`) | 중간 | #43과 같은 원인 |
| 40 | 데이터 전송 환경설정(대체 출력 폴더, 새 이름 대소문자·공백 바꾸기 규칙, 다시 연결, 작업 로그) | `PrefPageDataTransfer` | 없음 | Options 탭에 없음(`OptionsDialog.java:178-183`) | 낮음 | |
| 41 | SQL 스크립트 실행 작업(여러 스크립트 × 여러 연결) | `SQLScriptTaskConfigurator`, `SQLScriptTaskPageSettings` (작업 형식 `scriptExecute`) | 없음 | swt-removal.md:104 "SQL 스크립트 작업", grep `scriptExecute` 0 | 낮음 | |
| 42 | 결과를 스프레드시트로 바로 열기(Open in Excel) | `data.office.ui` `OpenSpreadsheetHandler` | 일부 | 결과 표의 Export to Excel (XLSX)(`ResultSetExport.java:63`)로 파일을 만든 뒤 사용자가 연다 | 낮음 | `ext.office.feature`는 SWT 제품에 없었다(위 사실 1) |

## 5. `tasks.ui`, `tasks.ui.view`, `tasks.sql.ui`, `tasks.native.ui`

작업 형식(`263b8ddb45`의 `<task id=`): 데이터 `dataExport`, `dataImport`, `scriptExecute`; 네이티브 `pgDatabaseBackup`, `pgDatabaseBackupAll`, `pgDatabaseRestore`,
`pgScriptExecute`, `mysqlDatabaseBackup`, `mysqlDatabaseRestore`, `mysqlScriptExecute`, `oracleScriptExecute`; SQL 도구 PostgreSQL(VACUUM·ANALYZE 데이터베이스/테이블,
MView 새로 고침, TRUNCATE, 트리거 켜기/끄기), MySQL(ANALYZE·CHECK·OPTIMIZE·REPAIR·TRUNCATE), Oracle(통계 수집 테이블/인덱스, MView 새로 고침, TRUNCATE,
트리거 켜기/끄기, 구조 검증), SQL Server(테이블 다시 작성, 트리거 켜기/끄기), DB2(RUNSTATS, REORG 인덱스·검사, TRUNCATE), Exasol(TRUNCATE), Altibase(7종).

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|---|
| 43 | Database Tasks 보기(저장한 작업 목록, 폴더, 범주·형식별 묶기, 만들기·편집·복사·삭제·실행, 최근 작업 메뉴, 실행 기록·로그, 작업 변수, 완료 알림·소리) | `DatabaseTasksView`, `TaskHandler*`, `RecentTasksMenuContributor`, `EditTaskVariablesDialog`, `NewTaskConfigurationWizard`, `tasks.ui` 알림 `task.execute.success/failure` | 없음 | swt-removal.md:104. Swing의 `DBTTask`는 화면 없는 임시 작업뿐(`DataTransferWizard.java:524-533`, `NativeBackupRestoreDialog.java:649-653`) | 중간 | 반복 내보내기·백업을 저장해 두고 다시 돌리는 흐름이 없음 |
| — | 작업 예약(스케줄러) | `DBTScheduler`(모델 인터페이스만) | 해당 없음 | 위 사실 3. 대신 Chromite 고유의 Query Library 예약 실행(`swing-query-library.md:14`, Chromite가 켜져 있을 때만)과 서버 쪽 스케줄러 화면(`swing-job-schedulers.md`, `swing-sqlserver-agent.md`)이 있다 | — | |
| 44 | DB 도구 메뉴와 SQL 도구 마법사(Tools 하위 메뉴, 여러 객체 고르기, 설정, 결과 상태 페이지) | `tasks.ui` `ToolsRegistry`/`ExecuteToolHandler`, `tasks.ui.view` `DataSourceToolsContributor`, `tasks.sql.ui` `SQLToolTaskWizard` | 일부 | Maintenance 대화상자(`MaintenanceDialog`, `swing-admin-tools.md:13, 346-353`)가 PG(VACUUM·ANALYZE·REINDEX·CLUSTER), MySQL(ANALYZE·OPTIMIZE·CHECK·REPAIR), Oracle(통계·다시 작성·컴파일), SQL Server(다시 작성·재구성·통계·CHECKDB)를 덮는다(방언 `OP_*` 상수). **없는 것:** Oracle 구조 검증, DB2·Exasol·Altibase 도구(TRUNCATE, 트리거 켜기/끄기, MView 새로 고침은 2026-11-08 Object Explorer 메뉴로 더함, swing-explorer-object-actions.md "유지 관리 명령") | 중간 | DB별 `ext.*.ui` 조사와 겹침 |
| 45 | 네이티브 백업·복원 PostgreSQL·MySQL/MariaDB(형식, 압축, 인코딩, 주요 옵션, 로그, 로컬 클라이언트) | `tasks.native.ui` `AbstractNativeToolWizard`, `NativeToolWizardDialog`, `ext.*.ui` 마법사 | 있음 | `NativeBackupRestoreDialog`, `swing-backup-restore.md:96-111` | 높음 | SQL Server 백업·복원은 Chromite에서 새로 만듦 |
| 46 | 네이티브 백업 고급: 스키마·테이블 고르기, 전체 클러스터 백업(`pgDatabaseBackupAll`, pg_dumpall), 도구 계정 따로, 추가 인수 | 같은 마법사의 객체 선택·설정 페이지 | 없음 | `swing-backup-restore.md:202`. `NativeBackupRestoreDialog.java:534-536`은 backup/restore 두 형식만 | 낮음 | |
| 47 | 네이티브 클라이언트로 스크립트 실행(psql, mysql, sqlplus) | `AbstractNativeScriptExecuteWizard`, 작업 `pgScriptExecute`·`mysqlScriptExecute`·`oracleScriptExecute` | 일부 | MySQL 복원과 PostgreSQL 평문 복원이 같은 처리기를 쓴다(`NativeBackupRestoreDialog.java:516-521`). 임의 스크립트 실행 화면과 Oracle SQL*Plus 실행은 없음 | 낮음 | |
| 48 | 네이티브 클라이언트 위치 고르기·더하기 | `NativeToolWizardDialog.NativeClientConfigDialog` | 있음 | `swing-backup-restore.md:106-111` | 낮음 | |

## 6. `cmp.simple.ui`

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|---|
| 49 | 객체 비교(Compare/Migrate > Simple compare: 두 개 이상 객체의 속성·구조 비교 보고서, 파일 출력) | `CompareObjectsHandler`, `CompareObjectsWizard` (명령 `com.seizia.chromite.core.compare.simple`) | 일부(2026-11-08) | 조사 당시: swt-removal.md:110, `ui.swing`에서 `CompareObjects`·`cmp.simple` grep 0. 2026-11-08 Schema Compare(Tools > Compare Schemas…, 두 스키마의 구조 비교와 마이그레이션 스크립트, `cmp.simple`의 새 패키지 `tools.compare.schema`)를 더함([schema-compare.md](../schema-compare.md)). 여러 객체의 속성 보고서·HTML 파일 출력은 없음 | 중간 | Schema Compare는 SWT CE에 없던 기능(상용판 기능)이다 |

## 7. `net.ssh.ui`

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|---|
| 50 | SSH 터널 설정(호스트·포트·사용자, 비밀번호/공개 키/에이전트, 비밀번호 저장, 구현 선택, Keep-alive·시간 초과, 로컬·원격 주소, 호스트 키 확인 건너뛰기, 터널 공유, Test tunnel) | `SSHTunnelDefaultConfiguratorUI` | 있음 | `SshTunnelPanel.java:47-63`, `swing-conn-advanced.md:94-103` | 높음 | |
| 51 | 점프 서버 목록 편집 | `SSHTunnelDefaultConfiguratorUI`의 점프 서버 표 | 일부 | 읽은 점프 서버를 그대로 저장하고 개수만 안내(`swing-conn-advanced.md:102`, `SshTunnelPanel.java:194-196`) | 낮음 | 새로 더하거나 고칠 수 없음 |
| 52 | 네트워크 프로필을 쓰는 연결의 SSH 자격 증명만 입력 | `SSHTunnelCredsOnlyConfiguratorUI` | 없음 | 프로필을 쓰면 칸을 끄고 프로필 이름만 보임, 프로필 편집 화면 없음(`swing-conn-advanced.md:92`) | 낮음 | |
| 53 | SSH 터널 탐색기 보기(열린 터널 목록) | `SSHTunnelView` | 없음 | grep 0 | 낮음 | |
| 54 | **JSch 구현의 호스트 키 확인 질문과 키보드 대화형(OTP) 입력** | `JSCHUIPromptProviderAdapterFactory` → `JSCHUIPromptProvider`(`org.eclipse.jsch.ui.UserInfoPrompter`, `promptYesNo` → `confirmAction`) | **수정함** (2026-11-07, [swing-auth-prompts.md](../swing-auth-prompts.md): `JschPromptAdapterFactory` → `JschUserInfoPrompter`). 조사 당시: 조용히 끊김 | 어댑터가 없어 `GeneralUtils.adapt`가 null을 주고 모델 기본 `JschUserInfo`를 쓴다(`JSCHSessionController.java:90-99`). 이것은 `promptYesNo`가 늘 false, 키보드 대화형 질문마다 저장된 비밀번호를 돌려준다(`:178-220`). 호스트 키 확인은 `StrictHostKeyChecking=ask`(`:126-151`) | 중간 | 결과: JSch를 고른 연결은 known_hosts에 없거나 바뀐 호스트에 묻지 않고 거절(연결 실패), OTP를 묻는 서버에는 로그인 불가. 확인을 건너뛰는 방향은 아니다. 기본 구현 SSHJ는 `KnownHostsVerifier`가 `getPlatformUI().confirmAction`을 써서 Swing에서도 묻는다(`KnownHostsVerifier.java:64-84`). JSch는 `runtime.feature`에 들어 있고 Swing 구현 콤보에 보인다(`SshTunnelPanel.java:116`) |

## 8. `ui.ai`

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|---|
| 55 | AI 채팅 창(대화 목록, 새로·삭제, 제공자·모델 고르기, 연결 문맥, 함수 호출 허용) | `AIChatView`, `AIChatControl`, `AIFunctionAllowMenu` | 있음 | AI Assistant 도구 창(`AiAssistantPanel`, `ai-agent.md:521-575`). 에이전트 도구·확인·감사로 새로 설계 | 중간 | swt-removal.md:111은 낡음(위 사실 6) |
| 56 | 채팅 이미지 첨부·붙여넣기 | `AIImageAttachments*`, `AIImageClipboardTransfer` | 없음 | `AiAssistantPanel`에 `image`·`attach` grep 0 | 낮음 | |
| 57 | SQL 편집기 안 AI 번역(자연어 → SQL, `ui.ai.showCompletion`, SQL 편집기 메뉴·도구 막대) | `AITranslateHandler` | 일부 | "Ask AI About This Query"(`ai-agent.md:558`), 채팅의 `openInEditor` 도구(`ai-agent.md:575`). 편집기에서 바로 SQL을 만들어 넣는 명령은 없음 | 중간 | |
| 58 | **SQL 스크립트의 `@ai` 명령 실행 확인** | `ui.editors.sql` `UIServiceSQL.confirmQueryExecution`(SQL을 보여 주는 확인창), `ui.ai` `AIPreferencesInitializer` | 일부(2026-11-07: 확인창에 SQL 표시, `SwingSqlService`, [swing-sql-exec-safety.md](../swing-sql-exec-safety.md). 확인 규칙 설정 화면·기본값 초기화는 없음) | `@ai` 명령은 모델에 남아 Swing 실행 엔진에서도 돈다(`model.ai/plugin.xml:60-64`, `SQLQueryExecuteJob.java:454-460`). AI가 만든 문장을 실행하기 전 `AIUtils.confirmExecutionIfNeeded`(`SQLCommandAI.java:167`) → `UIServiceSQL`이 없어 `confirmAction(제목, 메시지)`로 떨어진다(`AIUtils.java:342-345`): **실행할 SQL을 보여 주지 않고** "데이터를 바꿀 수 있다, 계속?"만 묻는다. 확인 규칙 설정 화면(`AIPreferencePageMain`)도 없다. 기본값 초기화가 없어져 `AI_SEND_TYPE_INFO`가 true→false(`AISchemaGenerationOptions.java:64`), `AI_CONFIRM_SQL`이 EXECUTE→CONFIRM(더 보수적) | 중간 | 사용자는 AI가 만든 DML·DDL을 보지 않고 승인하게 된다. 에이전트 채팅 경로(`AiConfirmationDialog`)와 다른 경로라 정책·감사를 거치지 않는다(실행은 일반 편집기 실행). 실제로 돌려 보지는 않았다 |
| 59 | AI 설정: 제공자 설정(OpenAI, Ollama, Copilot), ChatGPT 계정 로그인, 여러 프로필, 채팅 설정, 프롬프트 사용자 정의 | `AIPreferencePageEngines/Configuration/Chat/Prompts`, `OpenAiConfigurator`, `OpenAIAuthMethodDialog`, `CopilotConfigurator`, `AIProfileCreateDialog` | 일부 | Options > AI Assistant(`AiOptionsPage.java:154-260`): OpenAI·Ollama·Anthropic의 키·주소·모델·Test. **없는 것:** GitHub Copilot, ChatGPT 계정 로그인, 프로필, 프롬프트 편집 | 낮음 | 사용자 결정의 제공자는 Claude·OpenAI·Ollama(`ai-agent.md:21`). Copilot을 뺀다는 명시적 결정 문구는 없음 |
| 60 | 연결별 AI 설정(AI 사용 여부, 보낼 범위·테이블 고르기) | `PrefPageDataSourceAI`, `ScopeSelectorDialog`, `connectionPageConfigurator` | 일부 | 연결별 도구 정책(`AiOptionsPage.java:409-476`). 범위 고르기는 도구 방식이라 해당 개념이 없음 | 낮음 | |
| 61 | DBeaver가 운영하는 AI 모델 카탈로그(dbeaver.com) | `model.ai` `AIModelCatalog`(설정 화면이 사용) | 의도적 제외 | `ai.md:7, 11, 67` | 낮음 | |

## 9. 디버거, Git, 개발자 도구, DataDam, GIS

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|---|
| 62 | PostgreSQL 프로시저 디버거(중단점, 단계 실행, 변수, 호출 스택, 디버그 구성·실행 바로 가기) | `debug.ui`, `debug.core`, `ext.postgresql.debug.ui/core` | 있음(2026-11-07) | 모델 복원(`debug.core`, `ext.postgresql.debug.core`), Swing `ProcedureDebuggerPanel`·`DebugStartDialog`·`DebugCommands`([procedure-debugger.md](../procedure-debugger.md)) | 낮음 | 디버그 구성·실행 바로 가기(Eclipse 실행 구성)는 옮기지 않고 Debug… 메뉴와 시작 대화상자로 바꿈. attach(Global) 모드는 SWT에서 꺼져 있던 것을 켬. Step Out은 pldbgapi에 없음. SWT에 없던 Oracle 디버거(`ext.oracle.debug.core`, DBMS_DEBUG, Step Out 있음)를 2026-11-07 더함 |
| 63 | Git 연동(프로젝트 공유, Git에서 프로젝트 가져오기, 커밋, 갱신, 기록, Repositories 보기) | `team.git.ui` (+EGit) | 없음(이식 대기) | swt-removal.md:109, build.md:82-85 | 낮음 | `git.feature`는 SWT 제품에 없었다. 삭제 결정 문서는 없음(위 사실 2) |
| 64 | 공간 데이터(GIS) 보기: 결과의 지도 보기(Leaflet, 타일 고르기, SRID), 기하 값 패널, GIS 환경설정 | `data.gis.view` `GeometryValueManager`, `results.geometry` 프레젠테이션, `PrefPageGIS` | 일부 (2026-11-08) | 결과 공간 보기 `ResultSetSpatialView`, 값 패널의 WKT([swing-result-charts-spatial.md](../swing-result-charts-spatial.md)). 조사 당시: 없음(swt-removal.md:106, `DBGeometry` grep 0) | 낮음 | 밑그림 없는 평면(폐쇄망), 타일 고르기·SRID 변환·GIS 환경설정 없음 |
| — | 개발자 Test 메뉴(노드·연결 검증, 연결 스트레스 시험, 대화상자·아이콘·색 보기) | `ui.app.devtools` | 해당 없음 | `ui.test.feature`(테스트 제품)에만 포함 | — | |
| — | DataDam(프로젝트 공유·동기화, DataDam AI 엔진, MCP 연결) | `ui.datadam` | 해당 없음 | 어떤 피처에도 없음(telemetry.md:110-113, swt-removal.md:29, 140) | — | 모델 번들 정리는 swt-removal.md "남은 일" |

## 집계

| 상태 | 수 | 번호 |
|---|---|---|
| 있음 | 17 | 1, 2, 3, 10, 11, 17, 27, 28, 29, 31, 34, 35, 45, 48, 50, 55, 62 |
| 일부 | 20 | 4, 12, 13, 14, 15, 18, 19, 24, 36, 37, 38, 42, 44, 47, 49, 51, 57, 59, 60, 64 |
| 없음 | 24 | 5, 6, 7, 8, 9, 16, 20, 21, 22, 23, 25, 26, 30, 32, 33, 39, 40, 41, 43, 46, 52, 53, 56, 63 |
| 의도적 제외 | 1 | 61 |
| 조용히 끊김 | 2 | 54, 58 |
| 해당 없음 | 4 | 즐겨찾기, 작업 스케줄러, 개발자 Test 메뉴, DataDam |

64(GIS)는 2026-11-08 결과 공간 보기로 일부가 되었다([swing-result-charts-spatial.md](../swing-result-charts-spatial.md)).
29(데이터 검색)는 2026-11-08 옮겼고([swing-data-search.md](../swing-data-search.md)), 49는 Schema Compare로 일부가 되었다([schema-compare.md](../schema-compare.md)). 위 집계는 행 상태에서 다시 센 것이다.
조사 당시 없음 27개 중 63(Git)은 SWT 제품에도 없던 번들이라 실제 사용자 손실은 없다. 62(디버거)는 2026-11-07 옮겼다([procedure-debugger.md](../procedure-debugger.md)).
10, 11, 17은 2026-11-07 옮겼고 12는 일부가 되었다([swing-explorer-object-actions.md](../swing-explorer-object-actions.md)).

## 영향순 상위 10 (없음·일부·조용히 끊김)

이 영역에는 "높음"인 빈칸이 없다. 매일 쓰는 내보내기·가져오기·백업·탐색·SSH 설정은 옮겨졌다. 아래는 모두 "중간"이고, 조용히 끊긴 두 개를 앞에 두었다.

1. **(조용히 끊김) `@ai` 명령의 실행 확인에 SQL이 보이지 않음** (#58) — AI가 만든 DML·DDL을 내용 없이 승인. 확인 규칙 설정도 없음.
2. **(조용히 끊김) JSch SSH 구현의 호스트 키 질문·OTP 입력 없음** (#54) — JSch를 고른 연결은 새·바뀐 호스트에서 묻지 않고 실패, 2단계 인증 서버에 로그인 불가. 2026-11-07 수정함([swing-auth-prompts.md](../swing-auth-prompts.md)).
3. DB 객체 삭제(DROP) 명령 없음 (#11) — 2026-11-07 옮김([swing-explorer-object-actions.md](../swing-explorer-object-actions.md))
4. 객체 이름 복사·연결 복제·붙여넣기 없음 (#12) — 2026-11-07 이름 복사만 옮김(일부)
5. Generate SQL의 INSERT/UPDATE/DELETE/MERGE/JOIN/CALL/DROP 없음 (#17) — 2026-11-07 옮김(DROP은 삭제의 스크립트 미리 보기)
6. 저장한 작업(Task) 보기·재실행·실행 기록 없음 (#43), 데이터 전송을 작업으로 저장 없음 (#39)
7. DB 도구 일부 없음: TRUNCATE, 트리거 켜기/끄기, MView 새로 고침, DB2·Exasol·Altibase 도구 (#44)
8. 데이터 검색(여러 테이블 값 찾기) 없음 (#29) — 2026-11-08 옮김([swing-data-search.md](../swing-data-search.md))
9. 객체 비교(Simple compare) 없음 (#49) — 2026-11-08 Schema Compare(구조 비교·마이그레이션 스크립트)를 더해 일부가 됨([schema-compare.md](../schema-compare.md))
10. 자원 탐색기(Scripts 폴더, 스크립트-연결 묶기, 연결별 최근 스크립트) 없음 (#22)

그다음: 범용 새 객체 만들기 일부(#14), 필터 편집 일부(#4), 테이블 복사 고급 옵션(#36), 편집기 안 AI 번역(#57), 객체 편집기(#15, 편집기 영역과 겹침).

## 확인하지 못한 것

- 앱을 띄워 확인하지 않았다. #54(JSch)와 #58(`@ai`)은 코드 경로를 읽어 판단했다.
- #9의 큰 스키마 성능, #64의 결과 표 기하 값 표시는 확인하지 않았다.
- DB별 SWT UI(`ext.*.ui`)의 도구 화면과 SQL 편집기·결과 영역의 Generate SQL(결과 행)은 다른 영역 조사에 맡겼고, 여기서는 틀(#44, #17)만 보았다.
