# Swing 데이터 가져오기·내보내기·테이블 복사 (Import and Export)

## 목적

DBeaver의 데이터 전송(`com.seizia.chromite.data.transfer` 모델 + `.ui` SWT 마법사)을 Swing 제품에 SSMS "SQL Server Import and Export Wizard" 모양으로 옮긴다.

1. **Export Data…** — Object Explorer(테이블·뷰 여러 개, 스키마·폴더)와 결과 표에서 모델이 등록한 모든 파일 형식으로 내보내기.
2. **Import Data…** — CSV 파일을 이미 있는 테이블에 넣기(열 매핑, 파일 설정, 넣기 설정).
3. **Copy Tables…** — 테이블·뷰를 다른 연결·데이터베이스(같은 서버의 다른 스키마 포함)로 복사(`DatabaseTransferConsumer`, SWT "Database → Database").
   Object Explorer Tasks ▸ Copy Tables…, 또는 Export Data…의 형식 목록에서 "Database". 아래 "테이블 복사".

세 명령은 테이블·뷰·스키마뿐 아니라 **데이터베이스 노드**(SQL Server·PostgreSQL·MariaDB 데이터베이스)와 데이터베이스가 하나인 연결 노드에서도 쓴다
(SSMS 데이터베이스 ▸ Tasks ▸ Import/Export Data). 아래 "데이터베이스 노드".

## 구조

| 클래스 (Swing 번들 `com.seizia.chromite.ui.swing`) | 역할 |
|---|---|
| `DataTransferWizard` (새) | 마법사 공통 틀: 왼쪽 단계 목록, 머리글, Back/Next/Finish/Cancel, 진행 페이지, 모델 실행(`DTTaskHandlerTransfer.executeWithSettings`) |
| `DataExportWizard` (새) | 내보내기 단계: 원본(테이블 목록 또는 결과 표의 쿼리) → 형식 → 형식 설정 → 출력 → 요약 |
| `DataImportWizard` (새) | 가져오기 단계: 파일·형식·파일 설정과 미리 보기 → 열 매핑 → 넣기 설정 → 요약 |
| `DataTransferObjects` (새) | 원본 객체 판단·읽기(테이블·뷰, 그 부모, 스키마를 담는 데이터베이스, 연결의 유일한 데이터베이스, 스키마별 읽기), Tasks 메뉴 도구 설명, 양식 줄, 안내 문구, 객체 칸 렌더러 |
| `DataImportTargetPage` (새) | 데이터베이스 노드에서 연 가져오기의 첫 단계: 스키마 + 기존 테이블 또는 새 테이블 이름 |
| `ObjectExplorerPanel` (변경) | 오른쪽 클릭 **Tasks ▸ Import Data… / Export Data… / Copy Tables…**, 내보내기 마법사에 쿼리 창 열기(`QueryWindows`)를 넘김, 세 명령의 대상 객체(`getTransferObject`)와 도구 설명 |
| `DatabaseCopyPages` (새) | 테이블 복사 단계: 대상(연결 → 데이터베이스 → 스키마) → 테이블·열 매핑과 대상 DDL 미리 보기 → 넣기 설정, 요약 줄, 실행 설정 |
| `DatabaseCopyMappings` (새) | SWT 매핑 페이지의 모델 호출을 화면과 떼어 옮김: 대상 컨테이너 단계, 매핑 만들기·대상 테이블/열 정하기, 형식 목록, 대상 DDL |
| `internal/DbCopyMessages` (새) | 테이블 복사 화면 문구(영어 기본 + `_ko`), 데이터베이스 노드 문구(`dbnode_`) |
| `ResultSetGrid` (변경) | 오른쪽 클릭 **Export Data…**, `Fetched.exportQuery`(다시 실행할 쿼리) |
| `SqlEditorPanel` (변경) | 쿼리의 첫 결과 표에 그 쿼리의 `SQLQueryDataContainer`를 넘김 |
| `ConnectionPropertiesTable` (변경, Javadoc만) | 처리기 속성 편집에도 쓴다는 설명 |
| `SqlExportPragma` (새, 2026-11-07) | 스크립트의 `@export` 명령 처리기(`plugin.xml`의 `com.seizia.chromite.sqlCommand` pragma `export`): 옵션 검사, 마법사 열기. 아래 "@export 명령" |

마법사를 위해 모델을 고치지는 않았다(새 테이블 형식 매핑의 모델·방언 수정은 아래 "새 테이블의 형식 매핑"). Swing 번들은 이미 `com.seizia.chromite.data.transfer`·`data.office`를 `Require-Bundle`로 쓰고 있었고(F단계), 새 번들 의존은 없다.

## SWT에서 옮긴 것 (근거)

| SWT | Swing | 내용 |
|---|---|---|
| `DataExportHandler.adaptTransferNode` | `DataExportWizard.prepareTransfer` | 원본마다 `new DatabaseTransferProducer(DBSDataContainer)` |
| `DataImportHandler.adaptTransferNode` | `DataImportWizard` 생성자 | 대상 `new DatabaseTransferConsumer(DBSDataManipulator)` |
| `DataTransferWizard.openWizard` | `DataTransferWizard.createSettings` | `new DataTransferSettings(producers, consumers, …, selectDefaultNodes=true, isExport, false, false)` |
| `DataTransferPagePipes` (형식 고르기) | `DataExportWizard.FormatPage` | `StreamTransferConsumer` 노드의 `getAvailableProcessors(원본)` → `selectConsumer(노드, 처리기, true)` |
| `StreamConsumerPageSettings.activatePage/deactivatePage` | `FormatSettingsPage` | `new PropertySourceCustom(processor.getProperties(), 기억한 값)` → `getPropertiesWithDefaults()` → `setProcessorProperties` |
| `StreamConsumerPageOutput` | `OutputPage` | `StreamConsumerSettings`의 폴더·패턴·문자 집합·BOM·하나의 파일·ZIP·클립보드, 이벤트 처리기 `showInExplorer`, `failedExportFileCleaner` |
| `StreamProducerPageSettings.updateSingleConsumer` | `DataImportWizard.loadMapping` | `new StreamTransferProducer(new StreamEntityMapping(file), 처리기)`, `updateProducerSettingsFromStream`, `new DatabaseMappingContainer(settings, entityMapping)` + `setTarget(테이블)` |
| `DatabaseConsumerPageMapping.setMappingTargetContainer` | 같은 곳 | `refreshMappingType(monitor, existing, …)`(이름·순서 자동 매핑) |
| `DataTransferWizard.DataTransferWizardExecutor.runTask` | `DataTransferWizard.startTransfer` | 임시 작업(`createTemporaryTask`) + `DTTaskHandlerTransfer.executeWithSettings` |
| `DataTransferPagePipes` 쿼리 원본(:707-714) | `DataExportWizard.readContextDefaults` | 쿼리 producer에 편집기의 기본 카탈로그·스키마(`setDefaultCatalog/Schema`) |
| `DataTransferPagePipes`(:347-351, 소비자 노드 고르기) | `DatabaseCopyPages.prepareMappings` | 원본마다 `DatabaseTransferProducer`, `selectConsumer(DatabaseTransferConsumer 노드, null, true)`, 노드의 `DatabaseConsumerSettings` |
| `DatabaseConsumerPageMapping.setSelectedNode`(:276-306) | 같은 곳 | `settings.setContainer(컨테이너)` 후 매핑마다 `setMappingTarget(…, getTargetName(), true, true)` |
| `DatabaseConsumerPageMapping.loadAndUpdateColumnsModel`(:1653-1754), `autoAssignMappings`(:1387-1404) | `DatabaseCopyMappings.createMappings` | 원본 컨테이너 `cacheStructure(STRUCT_ATTRIBUTES)`, `new DatabaseMappingContainer(settings, 원본)`, `getAttributeMappings`, `addDataMappings`, 파이프 2개 이상이면 `sortDataPipes`(참조되는 테이블 먼저) |
| `DatabaseConsumerPageMapping.setMappingTarget`(:1228-1293), `setMappingTargetContainer`(:1295-1366), `transformTargetName`(:1471-1480) | `DatabaseCopyMappings.setTableTarget`, `setColumnTarget`, `transformTargetName` | 이름 공간(`DBSNamespaceContainer`)·자식에서 기존 테이블 찾기 → `existing`, 없으면 `create`, 다른 종류 객체가 이름을 쓰면 `unspecified` + 오류 |
| `DatabaseConsumerPageMapping.createMappingTypeEditor`(:1064-1094), `applyMappingSkip/Transfer`(:857-883), `toggleRecreateSelection`(:1022-1061) | `DatabaseCopyMappings.getTableMappingTypes`, `setTableMappingType`, `DatabaseCopyPages.TableMappingModel` | 기존/새로 만들기/삭제 후 다시 만들기/건너뛰기, 다시 만들기는 확인 |
| `DatabaseConsumerPageMapping.createTargetEditor`(:1097-1135) | `DatabaseCopyMappings.readTargetTableNames` | 대상 이름 목록 = 컨테이너의 `DBSDataManipulator` 자식 |
| `ColumnsMappingDialog` 대상 형식 편집(:256-317) | `DatabaseCopyMappings.getTargetTypes`, `DatabaseCopyPages.ColumnTypeEditor` | 방언 `getDataTypes` + `DBPDataTypeProvider.getLocalDataTypes` + 매핑된 형식, `setTargetType` |
| `ConfigureMetadataStructureDialog.generateTableActions`(:495-528), `composeDdlText`(:468-492) | `DatabaseCopyMappings.generateTableDdl` | `DatabaseTransferUtils.generateTargetTableDDL(…, getChangedPropertiesMap(), settings)` → `SQLUtils.generateScript` |
| `DatabaseConsumerPageLoadSettings.loadUISettingsForDisableReferentialIntegrityCheckbox`(:345-388), `activatePage`(:394-427) | `DatabaseCopyPages.LoadSettingsPage` | 기존 대상의 `DBPReferentialIntegrityController.supportsChangingReferentialIntegrity`와 문장(도구 설명), `DBSDataBulkLoader` 어댑터가 없으면 대량 로드 끔 |
| `DataImportPropertyTester.supportsImport`(:42-44) | `DatabaseCopyPages.DestinationPage.leave` | `FEATURE_DATA_IMPORT`가 false인 연결은 대상으로 받지 않음 |
| `DataImportHandler.adaptTransferNode`(:66-96), `isObjectContainerSupportsImport`(:132-142) | `DataImportWizard` 생성자 | 컨테이너에서 연 가져오기의 소비자 `new DatabaseTransferConsumer(컨테이너)` |
| `StreamProducerPageSettings.updateSingleConsumer`(:338-359), `generateTableName`(:474-502) | `DataImportWizard.loadMapping`, `getTableNameFromFile` | 대상 테이블이 없으면 `setTarget(null)` + `setTargetName(파일 이름에서 만든 이름)`(매핑 create) |

## 설계 결정과 이유

### 실행

- 모델의 `DTTaskHandlerTransfer.executeWithSettings`를 그대로 부른다. 파이프 초기화, 대상 테이블 DDL, 참조 무결성, `DataTransferJob`, 이벤트 처리기,
  Stop/Retry/Skip 오류 질문이 SWT와 같다. 임시 작업은 SWT처럼 만들되 직렬화하지 않아 결과 표의 쿼리 같은 저장할 수 없는 원본도 쓴다.
  임시 작업의 상태(`getProperties()`)는 처음에 `null`이고 SWT 마법사는 실행 전에 채운다. 비워 두면 `DatabaseTransferProducer` →
  `DBTaskUtils.initFromContext`에서 NPE가 나므로(첫 실행에서 확인) 빈 상태를 넣는다.
- EDT를 막지 않도록 `AbstractJob`에서 돌리고, 모델이 받는 `DBRRunnableContext`는 그 Job 모니터로 바로 실행하는 `MonitorRunnableContext`다.
  Cancel은 Job 취소 → `AbstractJob`이 실행 중인 문장을 취소하고, `DTTaskHandlerTransfer`의 대기 루프가 `DataTransferJob`을 취소한다.
- 진행 페이지는 파이프마다 한 행(Source, Destination, Status, Rows, Message)이다. 모델 Job이 `DataTransferSettings.acquireDataPipe`로 파이프를
  차례로 가져가므로 이 메서드만 재정의해 현재 파이프를 안다(설정 객체를 만드는 `createSettings`의 익명 하위 클래스). 다음 파이프를 가져가면 앞 파이프는 성공이다.
  모니터 작업 이름("N rows fetched", "Insert rows (N)")을 현재 행의 Message에 보이고, 끝나면 원본(`rowsFetched`)·대상(`rowsUpdated`) 통계로 행 수를 쓴다.
  오류는 `DBTTaskExecutionListener.taskFinished`의 오류로 받아 그 파이프를 Error로, 남은 파이프를 Not run으로 표시하고 오류 전문을 아래에 보인다.
- 노드 설정은 `DataTransferSettings.loadNodeSettings`(저장된 대화상자 설정 읽기)를 부르지 않는다. 빈 설정을 읽으면
  `DatabaseProducerSettings.loadSettings`가 `openNewConnections`·`queryRowCount`를 `false`로 만든다(`CommonUtils.toBoolean(null)`).
  그래서 설정 객체의 필드 기본값에서 시작해 화면 값을 직접 넣고, 원본은 SWT 필드 기본값대로 새 연결(격리 문맥)·행 수 세기를 켠다.

### 내보내기

- **여러 테이블:** Object Explorer 트리는 단일 선택이다(다른 기능이 선택 하나를 전제로 함). 트리를 바꾸지 않고, SSMS "Select Source Tables and Views"처럼
  마법사 첫 단계에 같은 스키마(또는 고른 스키마·카탈로그·"Tables"/"Views" 폴더)의 테이블·뷰를 체크 상자로 보여 준다. 테이블에서 열면 그 테이블만 체크되어 있다.
  메뉴를 켤지 판단은 형식만 본다(`DataTransferObjects.isDataContainerParent`: 폴더의 자식 클래스, 컨테이너의 `getPrimaryChildType(null)` — SWT `DataImportHandler`와 같은 방식).
- **전체 행:** 테이블은 `DatabaseTransferProducer`가 테이블을 다시 읽는다. 결과 표는 그 쿼리의 `SQLQueryDataContainer`로 쿼리를 다시 실행한다(SWT 결과 표 내보내기의 기본,
  `ResultSetDataContainer.readData`가 가져온 행만 쓰는 옵션이 없으면 원래 컨테이너의 `readData`).
- **결과 표의 필터와 정렬:** 결과 뷰어(`ResultSetViewerPanel`)가 서버에서 다시 읽은 조건과 정렬(`DBDDataFilter`: 필터 줄의 WHERE, Filter by Value,
  서버 정렬의 ORDER BY)을 `DatabaseTransferProducer(container, filter)`에 넘긴다(SWT `ResultSetHandlerMain.java:595`가 `rsv.getModel().getDataFilter()`를
  넘기는 것과 같음). 처음에는 필터 없이 원래 쿼리를 다시 실행해, 걸러 낸 표에서도 모든 행이 원래 순서로 나갔다. 표의 메뉴는 `ResultSetGrid.Host.exportData`로
  마법사를 열고 뷰어가 이 필터를 넣는다. 뷰어는 서버에서 읽은 필터를 따로 둔다(`serverFilter`): 모든 행을 이미 읽은 표나 서버 정렬이 꺼진 연결의 정렬은
  표 안에서만 하므로(`clientOrdered`) 내보내기가 따를 수 없고, 그때는 원본 페이지와 요약에 "내보낸 행은 쿼리가 돌려주는 순서"라고 적는다.
  필터는 늘 서버에서 다시 읽으므로 표 안에서만 거른 결과는 없다. 마법사의 원본 페이지와 요약은 적용되는 WHERE·ORDER BY를 보여 준다.
- **다시 실행해도 되는 결과만:** `Export Data…`는 (1) 쿼리의 첫 결과 집합이고 (2) 바꾸지 않는 SELECT(`SQLQuery.getType() == SELECT && !isModifying()` —
  `SELECT … INTO`, 데이터를 바꾸는 CTE, `FOR UPDATE` 제외)이면서 (3) 문장이 하나(`SQLScriptParser.parseScript`로 센다)일 때만 켜진다.
  `GO` 배치의 여러 문장은 쿼리 하나로 합쳐지고 그 형식은 첫 문장의 것이라, 셋째 조건이 없으면 `SELECT 1; DELETE …` 배치의 첫 결과 표에서도 켜졌다(SQL Server에서 확인 후 고침). `SQLQueryDataContainer`는 결과 집합을 하나만 읽고("Doesn't support multiple resulsets"),
  `GO` 배치는 여러 문장이 한 쿼리라 다시 실행하면 INSERT 등이 되풀이되기 때문이다. 첫 결과 집합인지는 `SqlEditorPanel`의 처리기가 쿼리 객체별로 처음 넘긴 표인지로 정한다
  (`SQLQueryExecuteJob`의 결과 번호는 스크립트 전체에서 이어지므로 쓸 수 없음).
  꺼진 메뉴의 도구 설명은 수신기가 아는 이유(`ResultSetSource.requeryBlocker`, 예: 연결이 끊김)가 있으면 그것을 보인다. 처음에는 늘 "SELECT 하나인 쿼리만" 문구라,
  SELECT 하나인 결과에서 이유를 잘못 알렸다. Query Library가 값을 바인딩한 실행도 2026-11-08부터 같은 값을 바인딩해 내보낸다
  ([swing-query-library.md](swing-query-library.md) "매개변수 대화상자와 바인딩 하나로", 그 전에는 "parameter values bound for this run only"로 꺼짐).
- **기존 "Export to CSV… / Export to Excel (XLSX)…"는 남겼다.** 이 둘은 표에 보이는 행(편집 중인 값 포함, 다시 실행할 수 없는 결과 포함)을 바로 쓰는 기능이고,
  마법사는 쿼리를 다시 실행한다. 표는 값을 표시 문자열로만 들고 있어(C단계 설계) 모델의 스트림 내보내기(값 처리기로 결과 집합에서 다시 읽음)에 "가져온 행"을 넣을 수 없으므로
  마법사가 두 항목을 대신하지 못한다. 그래서 `ResultSetExport`는 지우지 않았다(CLAUDE.md 4번: 쓸모없어진 코드가 아님).
- **형식:** `StreamTransferConsumer` 노드의 처리기 전체(순서 `order`, 이름). Swing 제품 기준 CSV, DbUnit, HTML, JSON, Markdown, SQL, Source code, TXT, XML, XLSX(`data.office`).
- **설정 기억:** 폴더, 파일 이름 패턴, 문자 집합, BOM, 형식, 폴더 열기는 이 번들 설정 저장소(`dataTransfer.export.*`)에 저장한다. 형식별 속성은 실행 중에만 기억한다.
- **같은 이름 파일:** 기본값은 Ask(모델이 `showUserChoice`로 묻는다). 모델의 `loadSettings` 기본값(PATCHNAME)이 아니라 필드 기본값(ASK)을 따랐다.

### 테이블 복사 (Database 대상)

- **진입점과 단계:** 내보내기 마법사(`DataExportWizard`)에 복사 단계를 넣었다. SWT도 같은 원본 목록에서 대상 노드(파일 또는 데이터베이스)를 고르게 한다.
  Export Data…의 형식 목록 맨 앞 "Database"를 고르면 형식 설정·출력 대신 대상·매핑·넣기 설정이 보이고(왼쪽 단계 목록도 바뀜, `DataTransferWizard.refreshSteps`),
  Tasks ▸ Copy Tables…는 같은 마법사를 형식 단계 없이 연다(제목 "Copy Tables"). 원본 체크 목록, 요약, 진행 페이지(테이블마다 한 행, 취소)를 그대로 쓴다.
  결과 표의 쿼리 원본에는 "Database"를 두지 않았다(아래 "남긴 것").
- **설정을 만드는 때:** 매핑(`DatabaseMappingContainer`)이 노드 설정 객체에 묶이므로 대상 단계를 떠날 때 `DataTransferSettings`를 만든다. 원본 체크가 바뀌면 다시 만들고,
  대상 컨테이너가 바뀌면 다시 매핑한다(SWT와 같이 처음은 `updateAttributesNames=false`, 바꿀 때는 `true`). 같은 대상으로 돌아오면 고친 매핑을 그대로 둔다.
- **대상 고르기:** SWT의 탐색기 트리 대화상자 대신 연결 → 데이터베이스 → 스키마 콤보(SSMS "Choose a Destination"). 단계는 모델의 주 자식 형식으로 정한다:
  `getPrimaryChildType`이 `DBSEntity`인 컨테이너에서 멈춘다(MariaDB·MySQL은 데이터베이스, PostgreSQL·SQL Server는 스키마, 단계가 없는 드라이버는 연결 자체).
  숨김·시스템 객체(`DBPSystemObject.isSystem`)는 목록에서 뺀다. 원본 연결을 미리 고르고, 스키마는 원본 스키마와 같은 이름 → 데이터베이스 기본 스키마(`getContextDefaults`) → 첫 항목 순으로 고른다.
- **매핑 단계:** 위 표는 원본 / 대상 이름(기존 테이블 목록 또는 입력) / 매핑(기존, 새로 만들기, 삭제 후 다시 만들기, 건너뛰기), 아래 탭은 고른 테이블의 열 매핑
  (대상 열 이름 또는 `[skip]`, 새 열의 대상 형식)과 대상 DDL 미리 보기다. DDL은 실행 때와 같은 `generateTargetTableDDL`로 만든다(PostgreSQL → SQL Server의 `CREATE TABLE`, 다시 만들기의 `DROP TABLE` + `CREATE TABLE`).
  기존 테이블에 새 열을 더하는 경우는 모델을 부르지 않고 더할 열만 보인다. 모델이 미리 보기에서도 새 열 객체를 실제 테이블의 열 캐시에 넣어(`generateStructTableDDL`의 `cacheObject`)
  다음 매핑에서 없는 열을 있는 것으로 보기 때문이다. "복사 전에 기존 대상 테이블 비우기(TRUNCATE)"는 이 단계에 두고 Finish 때 비울 테이블을 다시 확인한다.
- **매핑 표 갱신:** 셀 편집이 끝난 뒤(`invokeLater`) 행만 다시 그린다(`fireTableRowsUpdated`). 편집을 끝내는 도중에 표 전체를 바꾸면 고른 행이 풀리고,
  열 표의 편집을 끝내는 처리가 다시 불려 고른 테이블과 열 표가 어긋났다(첫 실행에서 확인 후 고침).
- **원본 = 대상 막기:** 같은 서버의 같은 스키마를 대상으로 고르면 모든 테이블이 자기 자신에 `existing`으로 매핑된다. 비우기·다시 만들기가 원본을 지우므로
  대상이 원본 테이블 자신인 매핑이 있으면 다음으로 가지 않는다(SWT에는 없는 검사). 이름 충돌 등 매핑 문제는 표 아래 빨간 글자로 테이블마다 한 줄씩 보인다.
- **건너뛴 테이블:** 실행 전에 그 파이프를 뺀다. 모델은 `skip` 매핑의 파이프도 원본을 읽고 대상 연결을 열기 때문이다.
- **넣기 설정:** 별도 연결, 트랜잭션·커밋 간격(일괄 처리 크기), 여러 행 INSERT, 하나씩 삽입, 자동 생성 열 값 복사, 참조 무결성 검사 끄기(기존 대상 테이블이
  지원할 때만 켜지고 도구 설명에 실행할 문장), 대량 로드(대상이 `DBSDataBulkLoader`일 때만), 끝나면 대상 테이블 열기. 모델의 `openTableOnFinish`(객체 편집기 열기)는
  Swing에 객체 편집기가 없어 끄고, 성공하면 대상 테이블마다(최대 5개) "상위 1000개 행 선택" 쿼리 창을 연다(`DataTransferWizard.transferSucceeded`).
- **형식 매핑은 모델에서:** 새 테이블의 열 형식은 모델의 `DBStructUtils.mapTargetDataType`(SWT와 같음)이 정하고 Swing은 결과를 고치지 않는다.
  잘못 매핑되던 형식(PostgreSQL `int4` → MariaDB `TINYINT`, 문자열 → SQL Server `varchar` 등)은 2026-11-07 모델과 방언에서 고쳤다(아래 "새 테이블의 형식 매핑").
  모델의 구조 DDL은 2026-11-07 밤부터 원본 테이블의 기본 키를 만든다(아래 "남은 매핑과 기본 키"). 괄호가 있는 형식(`varchar(100)`)은 키 열이 아니면
  원본의 NOT NULL을 옮기지 않는다(`generateStructTableDDL`, SWT도 같음).
- **취소:** 진행 중 Cancel은 내보내기와 같다. 넣던 일괄 처리가 취소되면 모델이 Stop / Retry / Skip / Skip All을 묻고(`DatabaseTransferConsumer`의 일괄 삽입 오류 처리),
  Stop을 누르면 끝난다. 이미 커밋한 행(커밋 간격마다)은 대상에 남는다.

### 가져오기

- 원본 형식은 파일 producer 노드에 등록된 가져오기 처리기다. 모델에는 CSV(`DataImporterCSV`)만 있어 XLSX 가져오기는 없다.
- 파일 설정(문자 집합, 구분자, 머리글, 따옴표, NULL 문자열, 날짜 형식, 공백 자르기 등)은 처리기 속성 표, 그 아래에 파일 앞 20줄을 그 문자 집합으로 보여 준다.
- 파일 단계를 떠날 때 진행 대화상자에서 열을 읽고 매핑을 만든다(SWT `deactivatePage`와 같은 시점).
  모델 자동 매핑은 맞는 열이 없는 원본 열을 `create`(대상 테이블에 열 추가 = ALTER TABLE)로 둔다. 기존 테이블 구조를 바꾸지 않도록 이 열들은 `skip`으로 바꾼다.
  매핑 표에서 열마다 대상 열 또는 `[skip]`을 고른다. 같은 대상 열을 두 번 고르거나 하나도 고르지 않으면 다음으로 가지 않는다.
- 넣기 설정: 테이블 비우기(Finish 때 다시 확인), 새 연결, 트랜잭션과 커밋 간격(배치 크기), 여러 행 INSERT와 문장당 행 수, 한 행씩 넣기.
  오류 처리는 모델 그대로(넣기 오류마다 Stop / Retry / Skip / Skip All). 끝난 뒤 테이블 열기(`openTableOnFinish`)는 Swing에 객체 편집기가 없어 끈다.

### 데이터베이스 노드 (2026-11-06)

- **근본 원인:** 세 명령을 켜는 판단이 테이블·뷰, 또는 테이블을 바로 담은 부모만 받았다(이전 코드 `DataExportWizard.java:244-249` `canExport` →
  `DataTransferObjects.java:64-78` `isDataContainerParent`: 컨테이너의 `getPrimaryChildType(null)`이 `DBSDataContainer`인지, 가져오기는 `DataImportWizard.java:172-174`
  `canImport` = `DBSDataManipulator`인 테이블만). SQL Server·PostgreSQL 데이터베이스의 주 자식 형식은 스키마다(`SQLServerDatabase.java:572-574` → `SQLServerSchema`,
  `PostgreDatabase.java:858-860` → `PostgreSchema`). 그래서 데이터베이스 노드에서는 꺼져 있었다. MariaDB 데이터베이스는 주 자식이 테이블이라
  (`MySQLCatalog.java:419-423` → `MySQLTable`) 내보내기·복사는 이미 켜졌고 가져오기만 꺼져 있었다. 연결 노드는 `ObjectExplorerPanel.java:708-711`
  `getSelectedObject`가 `DBNDataSource`에 `null`을 돌려주므로 늘 꺼졌다.
- **판단:** `isSchemaContainer` = 주 자식 형식이 `DBSSchema`인 컨테이너(SQL Server·PostgreSQL 데이터베이스, 데이터베이스 단계가 없는 Oracle 연결).
  `isDatabase` = 그것 또는 테이블을 담은 `DBSCatalog`(MariaDB·MySQL). 스키마 노드는 데이터베이스가 아니므로 그대로다(가져오기 꺼짐, 내보내기·복사는 그 스키마의 목록).
  모두 형식만 보고 EDT에서 메타데이터를 읽지 않는다.
- **연결 노드:** 연결이 스키마를 바로 담으면(Oracle) 연결 자체, 아니면 Object Explorer가 **이미 읽은** 노드(연결 → "데이터베이스" 폴더 → 데이터베이스, 시스템·숨김 제외)에
  데이터베이스가 하나일 때 그 데이터베이스로 본다(`findConnectionDatabase`, 캐시만 쓰는 `LocalCacheProgressMonitor`). 데이터베이스가 여럿이면 어느 것인지 모르므로 끈다.
  PostgreSQL 연결은 기본으로 연결의 데이터베이스 하나만 보이므로 켜지고, 모든 데이터베이스를 보이는 SQL Server·MariaDB 연결은 꺼진다. 연결 노드를 펼치기 전에는
  꺼져 있다(메뉴를 열 때 다시 판단하므로 펼친 뒤에는 켜진다).
- **내보내기·복사 원본:** 데이터베이스의 스키마를 이름순으로 읽고(모델의 스키마 목록이라 연결의 탐색기 필터가 적용됨, 숨김 제외) 스키마마다 테이블·뷰를 읽는다(`readSchemaTables`,
  진행 대화상자에서, 취소 가능). 원본 표에는 스키마 머리 행(굵게, "n개 중 m개 선택") 아래에 그 스키마의 테이블·뷰를 들여 쓴다. 머리 행의 체크 상자가 그 스키마 전체를
  고르고 끈다("스키마 안 모두 선택"). 트리 대신 같은 표에 머리 행을 둔 것은 체크 상자, 모두 선택, 요약, 복사 단계가 같은 원본 목록(`sources`)을 그대로 쓰게 하기 위해서다.
  테이블·뷰가 없는 스키마(SQL Server의 `db_owner`·`guest` 등)는 보이지 않는다.
- **시스템 스키마:** `DBPSystemObject.isSystem`인 스키마(SQL Server `sys`·`INFORMATION_SCHEMA`, PostgreSQL `pg_catalog`·`information_schema`)는 기본으로 숨기고
  읽지도 않는다. "시스템 스키마 표시"를 켜면 그때 그 스키마들만 읽어 보이고, 끄면 숨기면서 체크를 푼다(숨은 원본이 내보내지지 않도록).
- **producer:** 스키마와 관계없이 체크한 원본마다 `DatabaseTransferProducer`를 만든다(기존 `prepareTransfer`, `DatabaseCopyPages.prepareMappings` 그대로).
  복사 매핑의 `cacheStructure`도 원본마다 부모 컨테이너를 모아 부르므로 여러 스키마에서 그대로 된다.
- **같은 이름 파일:** 기본 파일 이름 패턴 `${table}_${timestamp}`의 `${table}`은 스키마 없는 이름이라(`DTUtils.getTableName(…, true)`) 여러 스키마의 같은 이름 테이블은
  파일이 서로 덮어쓴다. 데이터베이스에서 연 마법사는 출력 단계에서 그런 테이블이 있고 패턴에 `${schema}`가 없으면 다음으로 가지 않고 넣으라고 알린다.
- **복사 대상은 하나:** 모델의 `DatabaseConsumerSettings`는 대상 컨테이너를 하나만 가지므로 여러 스키마의 테이블이 대상 스키마 하나로 간다(SWT와 같음).
  두 원본이 같은 대상 테이블로 가면(예: `dbo.orders`와 `sales.orders`) 매핑 단계 아래에 알리고 다음으로 가지 않는다. 대상 이름을 바꾸거나 건너뛰면 된다.
  둘째 테이블의 CREATE가 실패하거나, TRUNCATE가 첫 테이블의 행을 지우기 때문이다.
- **가져오기 대상 고르기:** 데이터베이스에서 연 가져오기는 첫 단계 "대상 테이블 선택"(`DataImportTargetPage`)이 붙는다. 데이터베이스(읽기 전용), 스키마(스키마 단계가
  있을 때), "기존 테이블"(그 스키마의 테이블, `DBSEntityType.TABLE`만) 또는 "새 테이블"(이름, 비우면 파일 이름). 스키마 목록은 복사 대상 단계와 같은
  `DatabaseCopyMappings.readChildContainers`(숨김·시스템 제외), 처음 고른 스키마는 연결의 기본 스키마(다른 데이터베이스면 같은 이름 dbo·public), 그 테이블은 마법사를 열기 전에
  읽고 다른 스키마의 테이블은 고를 때 읽는다. 테이블이 없는 스키마에서는 "새 테이블"만 고를 수 있다.
- **새 테이블로 가져오기:** 소비자는 SWT `DataImportHandler`가 컨테이너에 하듯 `new DatabaseTransferConsumer(데이터베이스)`이고, 파일의 열을 읽은 뒤 테이블 복사와 같은
  `DatabaseCopyMappings.setTableTarget`으로 매핑을 만든다(이름 대소문자 변환, 그 이름의 테이블이 있으면 그 테이블에 넣기(existing), 다른 종류 객체가 이름을 쓰면 오류).
  새 테이블이면 매핑이 `create`라 모델이 실행 때 테이블을 만든다(직접 DDL을 실행하지 않음). 열 단계는 "열" 탭에서 열 이름(`[skip]`으로 빼기)과 대상 형식
  (`DatabaseCopyMappings.getTargetTypes`)을 고치고 "대상 DDL" 탭에서 실행 때와 같은 `generateTargetTableDDL` 결과를 본다. 넣기 설정의 테이블 비우기는 새 테이블이면 끈다.
  기존 테이블을 고른 경우와 테이블 노드에서 연 경우는 전과 같다(맞는 열이 없는 원본 열은 건너뜀).
- **도구 설명:** 세 메뉴 항목에 지금 노드에서 무엇을 하는지(테이블·데이터베이스), 꺼져 있으면 어느 노드에서 쓰는지를 보인다(`updateTaskTooltips`).
  Tasks 하위 메뉴는 전과 같이 가져오기·내보내기·백업 중 하나라도 켜지면 켜진다(백업·복원과 같은 메뉴).

### 새 테이블의 형식 매핑 (2026-11-07)

Copy Tables와 새 테이블로 가져오기는 대상 열 형식을 모델이 정한다: `DatabaseMappingAttribute.getTargetType`(`DatabaseMappingAttribute.java:326-342`) →
`DBStructUtils.mapTargetDataType`(`DBStructUtils.java:334-514`, 고치기 전 줄 번호). 순서는 (1) 원본과 대상 데이터 소스 클래스가 같으면 원본 형식 그대로(:345-355),
(2) **대상** 방언의 `SQLDataTypeConverter.convertExternalDataType`(:357-372), (3) 대상 형식 목록에서 원본 형식 이름 찾기(:378-398), (4) 못 찾으면 이름 조각으로 추측(:399-489),
(5) 대상 방언의 `getColumnTypeModifiers`로 길이·정밀도 붙이기(:500-512). SWT도 같은 코드를 쓴다.

**근본 원인**

1. **정수·실수를 이름 조각으로 추측(:412-457).** PostgreSQL은 `int2`/`int4`/`int8`/`float4`/`float8`이라는 내부 이름을 쓰고 다른 DB는 이 이름을 모른다. (4)에서
   `typeNameLower.contains("int")`이면 대상 형식 목록에서 이름에 `int`가 든 **첫 형식**을 고른다(:449-456). MariaDB 드라이버의 `getTypeInfo` 순서는 `BIT, TINYINT, …`라
   `int2`·`int4`·`int8`이 모두 `TINYINT`(SHOW CREATE TABLE에서 `tinyint(4)`)가 되어 "Out of range value for column 'id'", SQL Server는 첫 형식이 `bigint`라 모두 `bigint`.
   `float8`은 `contains("float")`(:423-432)로 단정밀도 `FLOAT`. 원본 정밀도는 쓰이지 않는다(PostgreSQL 정수 열의 정밀도는 `null`, `PostgreAttribute.getPrecision`).
   같은 원인으로 MariaDB `DOUBLE` → SQL Server `int`(이름에 `double`이 든 형식이 없어 기본 형식 `int`, :478), Oracle `NUMBER(12,2)` → `float`
   (소수 자리 1~6이면 실수로 추측, :425), Oracle `NUMBER` → `int`, `BINARY_DOUBLE` → PostgreSQL `varbit`. 이름이 같아도 범위가 좁은 경우도 같은 원인이다
   (SQL Server `float`는 배정밀도, MariaDB `FLOAT`는 단정밀도, :380에서 이름으로 찾음).
2. **이름이 같으면 같은 형식으로 봄(:380).** SQL Server `varchar`/`char`/`text`는 열 데이터 정렬의 코드 페이지(기본 `SQL_Latin1_General_CP1_CI_AS`)로 저장되어
   한글이 `?`가 된다(확인: 고치기 전 PostgreSQL → SQL Server 복사에서 `한글 테스트 가나다` → `?? ??? ???`). 다른 DB의 문자열은 유니코드(PostgreSQL UTF8, MariaDB utf8mb4,
   Oracle AL32UTF8)라 SQL Server에서는 `nvarchar`/`nchar`여야 한다. SQL Server 방언에는 변환기가 없었고(`SQLServerDialectBase`는 `JDBCSQLDialect` 기본 변환기만 —
   길이 없는 `varchar`만 처리, `JDBCSQLDialect.java:452-475`) SSMS 가져오기·내보내기 마법사도 다른 DB의 문자열을 `nvarchar`(DT_WSTR)로 만든다.
   같은 원인: MariaDB `TIMESTAMP`는 1970~2038만 담는데 PostgreSQL `timestamp`·SQL Server `datetime2` → `TIMESTAMP`(1900-01-01 행이 깨짐), SQL Server `datetime`(1/300초,
   1753년부터)이 `timestamp`의 대상, MariaDB `TEXT`(64 KB)가 PostgreSQL `text`의 대상, Oracle `DATE`(시각 포함) → `date`.
3. **CONTENT·OBJECT 형식의 대체 선택.** 이름을 못 찾으면 데이터 종류를 받을 수 있는 첫 형식을 고른다(:403-407, :484-486). PostgreSQL `uuid`(OBJECT) → SQL Server
   `geometry`, `json`/`jsonb`·Oracle `CLOB`(CONTENT) → `varbinary`/`LONGBLOB`/`bytea`, SQL Server `uniqueidentifier` → `varchar(16)`(이진 길이 16), Oracle에서는 `uuid` → `TABLE`.
4. **길이를 받지 않는 형식에 길이를 붙임.** `AbstractSQLDialect.getColumnTypeModifiers`(:819-842)는 괄호 없는 문자열·이진 형식에 원본 길이를 붙인다.
   PostgreSQL `text`·`bytea`는 길이가 없어 `text(10485760)`(SQL Server `ntext`, MariaDB `longtext`), `bytea(4000)`(Oracle `BLOB`)으로 CREATE TABLE이 실패했다.
   MariaDB는 길이가 없는 원본 이진 데이터(PostgreSQL `bytea`, SQL Server `varbinary(max)`)가 `BINARY`(= `BINARY(1)`)나 `VARBINARY`(문법 오류)가 되었다.
5. **값 바인딩(형식을 고친 뒤 드러남).** 시각대가 있는 값이 다른 DB로 갈 때 순간(instant)을 잃었다:
   `JDBCDateTimeValueHandler.getTimestampValue`(`:293`)가 `OffsetDateTime`의 벽시계 시각을 JVM 시각대로 읽고, `ZonedDateTime`은 처리하지 않아
   "Timestamp format must be …"; SQL Server `datetimeoffset` 처리기(`SQLServerDateTimeOffsetHandler.java:124`)는 `Timestamp`를 드라이버 `setTimestamp`로 넘겨
   JVM 벽시계 시각에 `+00:00`을 붙였다; `TemporalAccessorValueHandler`(`:84`)는 `java.util.Date`를 받지 않아 Oracle `TIMESTAMP WITH TIME ZONE`에 넣을 수 없었다;
   PostgreSQL `jsonb` 처리기는 다른 처리기의 내용 객체(Oracle JSON)를 문자 데이터로 묶어 PostgreSQL이 거부했다.

**고친 곳**

| 파일 | 내용 |
|---|---|
| `DBStructUtils` (model) | 숫자 원본은 JDBC 형식과 값 범위(TINYINT < SMALLINT < INTEGER < BIGINT, REAL < DOUBLE; `unsigned`는 한 단계 위; JDBC FLOAT는 이진 정밀도 24 이하면 REAL)로 대상 형식을 고른다(`getNumericRank`, `findNumericTargetType`). 대상이 이름을 모르거나 같은 이름이 더 좁으면 범위를 담는 가장 작은 형식(이름 후보 `smallint`, `integer`, `bigint`, `double precision` …, 후보의 실제 JDBC 형식으로 범위 확인). 정밀도가 있는 소수(`NUMBER(12,2)`)는 대상의 `numeric`/`decimal`, 정밀도 없는 소수(Oracle `NUMBER`)는 배정밀도. 방언 변환기용 `isTimestamp`, `isTimestampWithTimeZone`. |
| `SQLServerDialectMssql` (mssql) | 새 `convertExternalDataType`(다른 DB 원본만): 문자열 → `nvarchar(n)`/`nchar(n)`(n ≤ 4000) 또는 `nvarchar(MAX)`, `uuid` → `uniqueidentifier`, `json`/`jsonb`/CLOB → `nvarchar(MAX)`, BLOB → `varbinary(MAX)`, 날짜시각 → `datetime2`, 시각대 있는 것 → `datetimeoffset`, 부호 있는 TINYINT → `smallint`. |
| `MySQLDialect` (mysql) | 새 `convertExternalDataType`: 날짜시각 → `DATETIME(6)`, `uuid`/`uniqueidentifier` → `UUID`(없으면 `CHAR(36)`), `json`/`jsonb` → `JSON`(형식 목록에 없으면 `LONGTEXT` — MariaDB), 길이 없거나 16383자 넘는 문자열·CLOB → `LONGTEXT`, 길이 없거나 65535바이트 넘는 이진·BLOB → `LONGBLOB`, 다른 DB의 TINYINT → `SMALLINT`. |
| `PostgreDialect` (postgresql) | 변환기에 `uniqueidentifier` → `uuid`, `clob`/`nclob` → `text`, 날짜시각 → `timestamp`/`timestamptz`(Oracle `DATE`, SQL Server `datetimeoffset`). `getColumnTypeModifiers`: `text`·`bytea`에는 길이를 붙이지 않음. |
| `OracleSQLDialect` (oracle) | 변환기에 `uuid`/`uniqueidentifier` → `VARCHAR2(36)`, 시각대 → `TIMESTAMP WITH TIME ZONE`, 실수 → `BINARY_FLOAT`/`BINARY_DOUBLE`(Oracle `FLOAT`·`DOUBLE PRECISION`은 지수 125까지인 NUMBER), 시각(time) → `TIMESTAMP`(Oracle에 TIME이 없어 ORA-00902), 길이 없거나 4000 넘는 문자열 → `CLOB`. |
| `JDBCDateTimeValueHandler` (model.jdbc) | `OffsetDateTime`·`ZonedDateTime` → 같은 순간의 `Timestamp`. 클래스 주석이 "string value handler"이던 것도 고침. |
| `SQLServerDateTimeOffsetHandler` (mssql) | `Timestamp`는 JVM 시각대 오프셋, `ZonedDateTime`은 자기 오프셋으로 ISO 문자열 바인딩. |
| `TemporalAccessorValueHandler` (model) | `java.util.Date`(다른 DB에서 읽은 `Timestamp`)를 같은 순간의 `LocalDateTime`/`ZonedDateTime`으로. |
| `PostgreJSONValueHandler` (postgresql) | 다른 처리기의 내용 객체(Oracle JSON)를 `PostgreContentJSON`으로(`Types.OTHER`로 바인딩). |

**판단(손실 없는 쪽을 고름)**

- `jsonb` → MariaDB: `LONGTEXT`. MariaDB의 `JSON`은 `LONGTEXT` + `json_valid` CHECK의 별칭이고 형식 목록에 없다. 값은 같고, MySQL처럼 `JSON` 형식이 목록에 있으면 `JSON`을 쓴다.
- 시각대가 있는 값 → MariaDB `DATETIME(6)`: MariaDB에 시각대 형식이 없다. 순간은 JVM 시각대의 벽시계 시각으로 보존되고 원래 오프셋은 남지 않는다(PostgreSQL `timestamptz`도 오프셋을 저장하지 않음).
- Oracle `NUMBER`(정밀도 없음) → 배정밀도(SQL Server `float`, MariaDB `DOUBLE PRECISION`; PostgreSQL은 기존 변환기대로 `numeric`). 어떤 고정 소수 자리도 모든 값을 담지 못하고, 정수(이전)는 소수를 잘랐다. SQL Server Migration Assistant의 기본 매핑(`float(53)`)과 같다.
- Oracle `NUMBER(p,0)` → `numeric(p,0)`(이전 `int`/`INTEGER`): `NUMBER(10,0)`·`NUMBER(19,0)`의 값이 `int`를 넘을 수 있어(이전 SQL Server `NUMBER(19,0)` → `int`) 같은 자릿수의 소수 형식으로 둔다.
- 다른 DB의 TINYINT → SQL Server·MariaDB `smallint`: SQL Server `tinyint`는 0~255, MySQL·MariaDB는 -128~127이라 어느 쪽이 원본이든 넘칠 수 있다.
- 문자 길이: `nvarchar(n)`의 n은 원본 문자 수 그대로(4000 넘으면 `MAX`). MariaDB `VARCHAR`는 utf8mb4 16383자까지 두고 그보다 길면 `LONGTEXT`.

**Oracle 대상과 남은 매핑 (2026-11-07 오후)**

위 작업 뒤에도 Oracle로 가는 복사는 형식은 맞게 만들고 값 바인딩에서 실패했다(PostgreSQL → Oracle ORA-17004, MariaDB → Oracle ORA-01843).
실수 끝값(`1.2345678901234567e300`, `-2.5e-300`)을 줄이지 않고 다시 확인하니 SQL Server → Oracle도 "Underflow"로 실패했다(위 확인은 `±1e±100`으로 줄인 값).
원본에 열 두 개를 더해 확인했다: `c_korean_full`(한글 100자, 300바이트), PostgreSQL `c_numeric_free`(정밀도 없는 `numeric`, `12345678901234567890.123456789`, `-0.000000001`).
고치기 전 결과(이 작업 전 devel 빌드): 12쌍 중 PostgreSQL → Oracle "Overflow", MariaDB → Oracle·SQL Server → Oracle "Underflow", PostgreSQL → SQL Server
"Arithmetic overflow error converting numeric to data type numeric", PostgreSQL → MariaDB "Out of range value for column 'c_numeric_free'"(실수 오류가 먼저 나서 JSON·시각 오류는 가려짐).

근본 원인:

1. **다른 DB의 내용 객체가 자기 드라이버 방식으로 바인딩됨.** `DatabaseTransferConsumer.fetchRow`(`:364`)는 원본 값을 대상 처리기의 `getValueFromObject`로 넘기는데,
   `JDBCContentValueHandler.getValueFromObject`는 `DBDContent`를 그대로 돌려주고(`:239-243`, 고치기 전) 바인딩은 내용 객체의 `bindParameter`가 한다.
   PostgreSQL `json`의 `PostgreContentJSON`은 `setObject(…, Types.OTHER)`(`PostgreContentJSON.java:63`)라 Oracle 드라이버가 ORA-17004(부적합한 열 유형)를 냈다.
   드라이버 전용 바인딩을 가진 다른 내용 클래스(Oracle `OracleContentXML`·`OracleContentOpaque` 등)가 다른 DB로 갈 때도 같은 경로다(확인하지 않음).
2. **텍스트로 읽은 시각을 TIMESTAMP에 바인딩함.** MariaDB `TIME`은 24시간을 넘을 수 있어 문자열로 읽는다(`MySQLDateTimeValueHandler.java:75-77`). Oracle에는 TIME이 없어
   대상은 `TIMESTAMP`인데, timestamp 형식기가 `23:59:58`을 해석하지 못해 문자열이 남고(`DateTimeCustomValueHandler.java:67-81`), `OracleTimestampValueHandler.bindValueObject`는
   `Timestamp.valueOf`가 실패하면 `setString`으로 넘겨(`:155-157`) ORA-01843(부적합한 월). 같은 바인딩 경고에 보이던 "Cannot bind attribute 'c_uuid'"는
   원본 결과 집합의 메타데이터 연결 디버그 로그(`DBExecUtils.java:933`)로, 성공한 MariaDB → PostgreSQL·SQL Server 복사에도 같은 줄이 남아 오류와 관계없다.
3. **Oracle 드라이버의 `setDouble`은 NUMBER로 보냄.** `BINARY_DOUBLE` 열에도 일반 숫자 처리기가 `setDouble`/`setFloat`(`JDBCNumberValueHandler.bindParameter`)을 써서
   값이 NUMBER(지수 -130~125)로 바뀌어 |x| ≥ 1e126은 "Overflow", |x| < 1e-130은 "Underflow". 표 편집(UPDATE)도 같다.
4. **`VARCHAR2(n)`은 바이트 길이.** 시험 DB의 `NLS_LENGTH_SEMANTICS`는 기본값 `BYTE`(문자 집합 AL32UTF8)라 한글 100자(300바이트)가 `VARCHAR2(100)`에 들어가지 않는다(ORA-12899).
   다른 DB의 길이는 문자 수다. 또 Oracle 열 모델(`OracleTableColumn`)은 `CHAR_USED`로 문자 길이를 읽기만 하고 단위를 버려(`:81-82`, 고치기 전) `VARCHAR2(100 CHAR)` 열의
   DDL이 `VARCHAR2(100)`으로 나왔고(Oracle → Oracle 복사도 바이트 열이 됨), 새 열 형식으로 준 `VARCHAR2(50 CHAR)`은 숫자만 읽는 `AbstractAttribute.setFullTypeName`(`:137-159`)에서 길이를 잃었다.
5. **정밀도 없는 소수가 같은 이름의 형식을 받음.** PostgreSQL `numeric`(정밀도·소수 자리 없음)은 SQL Server·MariaDB 형식 목록에서 이름으로 찾아져(`DBStructUtils` 이름 찾기)
   정밀도 없는 소수 규칙(`findNumericTargetType`)에 닿지 않았다. 수식어 없는 SQL Server `numeric`은 방언 기본값 `numeric(18,0)`, MariaDB `NUMERIC`은 `DECIMAL(10,0)`이라
   소수가 잘리고 큰 값은 넘쳤다. Oracle은 변환기가 `NUMBER`를 주지만, 새 열에 원본의 정밀도·소수 자리(둘 다 없음)를 옮긴 뒤(`DatabaseTransferUtils.java:498-506`)
   `OracleSQLDialect.getColumnTypeModifiers`가 정밀도 0을 38로 바꿔 `NUMBER(38)`(정수)을 만들어 소수를 잃었다(매핑 표에는 `NUMBER`로 보여 실제 DDL에서만 드러남).
6. **MariaDB `uuid` → PostgreSQL `varchar(36)`.** MariaDB `uuid`는 문자열 종류(STRING)라 같은 이름의 PostgreSQL `uuid`(OBJECT)를 종류가 달라 버리고 문자열 기본 형식을 골랐다.
7. **Oracle 숨은 열 `SYS_IME_OSON_…`은 제품에서는 보이지 않는다.** 원본 열은 `DTUtils.getAttributes`(`:243-246`)와 결과 집합 연결, 대상 열은 `DatabaseMappingAttribute`(`:199`)와
   Swing `DatabaseCopyMappings.readTargetColumns`(`:380`)가 숨은 열을 뺀다. 마법사 열 탭(21열)과 개체 탐색기의 열 폴더에서 확인했다. 앞의 "남긴 것"에 적은 것은
   시험용 `TmProbe`가 `getAttributes`를 그대로 불러 보인 것이라 코드는 고치지 않았다.

| 파일 | 내용 |
|---|---|
| `JDBCContentValueHandler` (model.jdbc) | 다른 데이터 소스의 내용 객체는 대상 형식에 맞는 이 처리기의 빈 내용(`getValueFromObject(null)`: 문자 → `JDBCContentChars`, CLOB → `JDBCContentCLOB`, 이진 → `JDBCContentBytes`, BLOB → `JDBCContentBLOB`)에 `updateContents`로 옮긴다. LOB 대상은 원본 저장소를 넘겨받아 큰 값을 여기서 메모리로 읽지 않는다. |
| `JDBCDateTimeValueHandler` (model.jdbc) | `TIMESTAMP` 열에 시각 문자열(`HH:mm[:ss[.f]]`)이 오면 1970-01-01의 그 시각(`java.sql.Time` 값과 같은 규칙). |
| `OracleBinaryFloatValueHandler` (oracle, 새 파일), `OracleValueHandlerProvider`, `plugin.xml` | `BINARY_FLOAT`/`BINARY_DOUBLE` 열의 숫자를 드라이버 형식 코드 100/101(`OracleTypes.BINARY_FLOAT`/`BINARY_DOUBLE`)로 `setObject`해 IEEE 값 그대로 보낸다. 표 편집에도 쓰인다. |
| `OracleTableColumn` (oracle) | `CHAR_USED = 'C'`를 기억하고(`isCharLengthSemantics`), `setFullTypeName`이 `(n CHAR)`/`(n BYTE)`를 읽는다. |
| `OracleSQLDialect` (oracle) | 변환기: 길이 4000 이하인 다른 DB 문자열 → `VARCHAR2(n CHAR)`, 고정 길이(JDBC CHAR/NCHAR) → `CHAR(n CHAR)`(`VARCHAR`만 `VARCHAR2(n)`으로 바꾸던 분기는 이 규칙에 합침). `getColumnTypeModifiers`: 문자 길이 열이면 `(n CHAR)`, 정밀도·소수 자리가 모두 없는 `NUMBER`는 수식어 없이. |
| `DBStructUtils` (model) | 정밀도 없는 소수(`isDecimalWithoutPrecision`)는 같은 이름의 대상 형식을 쓰지 않고 정밀도 없는 소수 규칙(배정밀도)으로. |
| `PostgreDialect` (postgresql) | 변환기에 MariaDB `uuid` → `uuid`. |

판단:

- PostgreSQL `numeric`(정밀도 없음) → SQL Server `float`, MariaDB `DOUBLE PRECISION`(Oracle `NUMBER`와 같은 규칙). 두 DB에는 정밀도 없는 소수를 손실 없이 담는 형식이 없다
  (SQL Server `decimal`은 38자리, MariaDB `DECIMAL`은 65자리이고 소수 자리를 미리 정함). 고정 소수 자리(예: `numeric(38,10)`)는 더 작은 소수를 말없이 반올림하고 정수부가
  자리를 넘으면 실패하며, 이전 `numeric(18,0)`은 소수를 모두 잃었다. 배정밀도는 유효 숫자 15~17자리까지 보존하고 ±1e308 안의 값은 모두 들어간다.
  정확한 소수가 필요하면 열 탭에서 대상 형식을 `numeric(p,s)`로 바꾼다. PostgreSQL ↔ Oracle은 `numeric` ↔ `NUMBER`(38자리)로 둔다.
- 문자 길이 단위는 `CHAR`(`VARCHAR2(n CHAR)`, `CHAR(n CHAR)`). `NVARCHAR2`/`NCHAR`는 쓰지 않는다: 데이터베이스 문자 집합(AL32UTF8, 23ai 기본)이 모든 문자를 담고,
  `NVARCHAR2`는 AL16UTF16에서 2000자까지이며 국가 문자 바인딩(`setNString`)과 `N'…'` 리터럴이 필요하다. 그래서 SQL Server `nchar(10)`도 이전 `NCHAR(10)` 대신 `CHAR(10 CHAR)`.
  바이트 한도는 그대로라(`MAX_STRING_SIZE = STANDARD`이면 4000바이트) `VARCHAR2(4000 CHAR)`에 한글 1334자 이상은 ORA-12899로 실패한다(말없이 잘리지 않음).
- MariaDB `TIME`(문자열) → Oracle `TIMESTAMP`는 1970-01-01의 그 시각(PostgreSQL·SQL Server `time`과 같음). 24시간을 넘는 값(`838:59:59`)은 시각으로 바꾸지 않아 Oracle이 거부한다.
- 다른 DB의 내용 객체는 대상 처리기가 늘 자기 내용으로 바꾼다(같은 데이터 소스 안의 복사는 전처럼 그대로). 대상이 LOB가 아니면(`varbinary(max)`, `bytea` 등) 값을 메모리로 읽는다.

**형식 매핑 표 (고치기 전 → 고친 뒤)** — 원본 열 형식, 대상별 매핑 결과. "=" 는 바뀌지 않음.
`TmProbe`(시험용, 저장소 밖)가 앱 안에서 각 원본 열에 `DBStructUtils.mapTargetDataType(대상 데이터 소스, 열, true)`를 불러 얻은 값이고, 실제 마법사의 "대상 DDL" 탭과 같다.
2026-11-07 오후 작업(위 "Oracle 대상과 남은 매핑")으로 바뀐 칸은 "고친 뒤"에 반영했고 `c_korean_full`, `c_numeric_free` 행을 더했다(이 두 행의 "고치기 전"은 그 작업 전 devel).

원본 **PostgreSQL**:

| 열 | 원본 형식 | → SQL Server | → MariaDB | → Oracle |
|---|---|---|---|---|
| id | `int4` | `bigint` → **`int`** | `TINYINT` → **`INTEGER`** | `INTEGER` = |
| c_smallint | `int2` | `bigint` → **`smallint`** | `TINYINT` → **`SMALLINT`** | `INTEGER` → **`SMALLINT`** |
| c_bigint | `int8` | `bigint` = | `TINYINT` → **`BIGINT`** | `INTEGER` = |
| c_numeric | `numeric(12, 2)` | `numeric(12,2)` = | `NUMERIC(12,2)` = | `NUMBER(12,2)` = |
| c_real | `float4` | `float` → **`real`** | `FLOAT` = | `REAL` → **`BINARY_FLOAT`** |
| c_double | `float8` | `float` = | `FLOAT` → **`DOUBLE PRECISION`** | `REAL` → **`BINARY_DOUBLE`** |
| c_bool | `bool` | `bit` = | `BOOL` = | `BOOLEAN` = |
| c_date | `date` | `date` = | `DATE` = | `DATE` = |
| c_time | `time` | `time` = | `TIME` = | `TIME` → **`TIMESTAMP`** |
| c_timestamp | `timestamp` | `datetime` → **`datetime2`** | `TIMESTAMP` → **`DATETIME(6)`** | `TIMESTAMP` = |
| c_timestamptz | `timestamptz` | `datetime` → **`datetimeoffset`** | `TIMESTAMP` → **`DATETIME(6)`** | `TIMESTAMP` → **`TIMESTAMP WITH TIME ZONE`** |
| c_varchar | `varchar(50)` | `varchar(50)` → **`nvarchar(50)`** | `VARCHAR(50)` = | `VARCHAR2(50)` → **`VARCHAR2(50 CHAR)`** |
| c_text | `text` | `text` → **`nvarchar(MAX)`** | `TEXT` → **`LONGTEXT`** | `VARCHAR2(100)` → **`CLOB`** |
| c_char | `bpchar(10)` | `varchar(10)` → **`nchar(10)`** | `VARCHAR(10)` = | `VARCHAR2(10)` → **`VARCHAR2(10 CHAR)`** |
| c_uuid | `uuid` | `geometry(100)` → **`uniqueidentifier`** | `UUID` = | `TABLE(100)` → **`VARCHAR2(36)`** |
| c_json | `json` | `varbinary` → **`nvarchar(MAX)`** | `LONGBLOB` → **`LONGTEXT`** | `JSON` = |
| c_jsonb | `jsonb` | `varbinary` → **`nvarchar(MAX)`** | `LONGBLOB` → **`LONGTEXT`** | `JSON` = |
| c_bytea | `bytea` | `varbinary(MAX)` = | `BINARY` → **`LONGBLOB`** | `BLOB` = |
| c_korean | `varchar(100)` | `varchar(100)` → **`nvarchar(100)`** | `VARCHAR(100)` = | `VARCHAR2(100)` → **`VARCHAR2(100 CHAR)`** |
| c_korean_full | `varchar(100)` | `nvarchar(100)` = | `VARCHAR(100)` = | `VARCHAR2(100)` → **`VARCHAR2(100 CHAR)`** |
| c_numeric_free | `numeric` | `numeric(18,0)` → **`float`** | `NUMERIC` → **`DOUBLE PRECISION`** | `NUMBER` = (새 열 DDL `NUMBER(38)` → **`NUMBER`**) |

원본 **SQL Server**:

| 열 | 원본 형식 | → PostgreSQL | → MariaDB | → Oracle |
|---|---|---|---|---|
| id | `int` | `int` = | `INT` = | `INTEGER` = |
| c_smallint | `smallint` | `smallint` = | `SMALLINT` = | `SMALLINT` = |
| c_bigint | `bigint` | `bigint` = | `BIGINT` = | `INTEGER` = |
| c_numeric | `numeric(12,2)` | `numeric(12,2)` = | `NUMERIC(12,2)` = | `NUMBER(12,2)` = |
| c_real | `real` | `real` = | `REAL` = | `REAL` → **`BINARY_FLOAT`** |
| c_double | `float` | `float8` → **`double precision`** | `FLOAT` → **`DOUBLE PRECISION`** | `FLOAT` → **`BINARY_DOUBLE`** |
| c_bool | `bit` | `bit` → **`boolean`**(2026-11-07 밤) | `BIT` = | `DECIMAL(1,0)` = |
| c_date | `date` | `date` = | `DATE` = | `DATE` = |
| c_time | `time` | `time` = | `TIME` = | `TIME` → **`TIMESTAMP`** |
| c_timestamp | `datetime2` | `timestamp` = | `TIMESTAMP` → **`DATETIME(6)`** | `TIMESTAMP` = |
| c_timestamptz | `datetimeoffset` | `timestamp` → **`timestamptz`** | `TIMESTAMP` → **`DATETIME(6)`** | `TIMESTAMP` → **`TIMESTAMP WITH TIME ZONE`** |
| c_varchar | `varchar(50)` | `varchar(50)` = | `VARCHAR(50)` = | `VARCHAR2(50)` → **`VARCHAR2(50 CHAR)`** |
| c_text | `nvarchar(MAX)` | `varchar` = | `TEXT` → **`LONGTEXT`** | `VARCHAR2(100)` → **`CLOB`** |
| c_char | `char(10)` | `varchar(10)` = | `CHAR(10)` → **`CHAR(10 CHAR)`** | `CHAR(10)` = |
| c_uuid | `uniqueidentifier` | `varchar(16)` → **`uuid`** | `VARCHAR(16)` → **`UUID`** | `VARCHAR2(16)` → **`VARCHAR2(36)`** |
| c_json | `nvarchar(MAX)` | `varchar` = | `TEXT` → **`LONGTEXT`** | `VARCHAR2(100)` → **`CLOB`** |
| c_nchar | `nchar(10)` | `varchar(10)` = | `VARCHAR(10)` = | `NCHAR(10)` → **`CHAR(10 CHAR)`** |
| c_bytea | `varbinary(MAX)` | `bytea` = | `VARBINARY` → **`LONGBLOB`** | `BLOB` = |
| c_korean | `nvarchar(100)` | `varchar(100)` = | `VARCHAR(100)` = | `VARCHAR2(100)` → **`VARCHAR2(100 CHAR)`** |
| c_ntext_old | `ntext` | `text(10485760)` → **`text`** | `TEXT(65535)` → **`LONGTEXT`** | `VARCHAR2(4000)` → **`CLOB`** |
| c_text_old | `text` | `text(10485760)` → **`text`** | `TEXT(65535)` → **`LONGTEXT`** | `VARCHAR2(4000)` → **`CLOB`** |
| c_korean_full | `nvarchar(100)` | `varchar(100)` = | `VARCHAR(100)` = | `VARCHAR2(100)` → **`VARCHAR2(100 CHAR)`** |

원본 **MariaDB**:

| 열 | 원본 형식 | → PostgreSQL | → SQL Server | → Oracle |
|---|---|---|---|---|
| id | `int(11)` | `int` = | `int` = | `INTEGER` = |
| c_smallint | `smallint(6)` | `smallint` = | `smallint` = | `SMALLINT` = |
| c_bigint | `bigint(20)` | `bigint` = | `bigint` = | `INTEGER` = |
| c_numeric | `decimal(12,2)` | `decimal(12,2)` = | `decimal(12,2)` = | `DECIMAL(12,2)` = |
| c_real | `float` | `float8` → **`float4`** | `float` = | `FLOAT` → **`BINARY_FLOAT`** |
| c_double | `double` | `double precision` = | `int` → **`float`** | `DOUBLE PRECISION` → **`BINARY_DOUBLE`** |
| c_bool | `tinyint(1)` | `int4` → **`smallint`** | `tinyint` → **`smallint`** | `INTEGER` → **`SMALLINT`** |
| c_date | `date` | `date` = | `date` = | `DATE` = |
| c_time | `time` | `time` = | `time` = | `TIME` → **`TIMESTAMP`** |
| c_timestamp | `datetime(6)` | `timestamp` = | `datetime` → **`datetime2`** | `TIMESTAMP` = |
| c_timestamptz | `timestamp(6)` | `timestamp` = | `datetime` → **`datetime2`** | `TIMESTAMP` = |
| c_varchar | `varchar(50)` | `varchar(50)` = | `varchar(50)` → **`nvarchar(50)`** | `VARCHAR2(50)` → **`VARCHAR2(50 CHAR)`** |
| c_text | `longtext` | `text(10485760)` → **`text`** | `text` → **`nvarchar(MAX)`** | `VARCHAR2(4000)` → **`CLOB`** |
| c_char | `char(10)` | `varchar(10)` = | `char(10)` → **`nchar(10)`** | `CHAR(10)` → **`CHAR(10 CHAR)`** |
| c_uuid | `uuid` | `varchar(36)` → **`uuid`** | `varchar(36)` → **`uniqueidentifier`** | `VARCHAR2(36)` = |
| c_json | `longtext` | `text(10485760)` → **`text`** | `text` → **`nvarchar(MAX)`** | `VARCHAR2(4000)` → **`CLOB`** |
| c_tinyint | `tinyint(4)` | `int4` → **`smallint`** | `tinyint` → **`smallint`** | `INTEGER` → **`SMALLINT`** |
| c_bytea | `longblob` | `bytea` = | `varbinary(MAX)` = | `BLOB` = |
| c_korean | `varchar(100)` | `varchar(100)` = | `varchar(100)` → **`nvarchar(100)`** | `VARCHAR2(100)` → **`VARCHAR2(100 CHAR)`** |
| c_mediumint | `mediumint(9)` | `int4` → **`integer`** | `bigint` → **`int`** | `INTEGER` = |
| c_int_unsigned | `int(10) unsigned` | `int` → **`bigint`** | `int` → **`bigint`** | `INTEGER` = |
| c_korean_full | `varchar(100)` | `varchar(100)` = | `nvarchar(100)` = | `VARCHAR2(100)` → **`VARCHAR2(100 CHAR)`** |

원본 **Oracle**:

| 열 | 원본 형식 | → PostgreSQL | → SQL Server | → MariaDB |
|---|---|---|---|---|
| ID | `NUMBER(10,0)` | `numeric(10,0)` = | `int` → **`numeric(10,0)`** | `INTEGER` → **`NUMERIC(10,0)`** |
| C_SMALLINT | `NUMBER(5,0)` | `numeric(5,0)` = | `int` → **`numeric(5,0)`** | `SMALLINT` → **`NUMERIC(5,0)`** |
| C_BIGINT | `NUMBER(19,0)` | `numeric(19,0)` = | `int` → **`numeric(19,0)`** | `BIGINT` → **`NUMERIC(19,0)`** |
| C_NUMERIC | `NUMBER(12,2)` | `numeric(12,2)` = | `float` → **`numeric(12,2)`** | `FLOAT` → **`NUMERIC(12,2)`** |
| C_REAL | `BINARY_FLOAT` | `float8` → **`double precision`** | `float` = | `FLOAT` → **`DOUBLE PRECISION`** |
| C_DOUBLE | `BINARY_DOUBLE` | `varbit` → **`double precision`** | `int` → **`float`** | `DOUBLE` → **`DOUBLE PRECISION`** |
| C_BOOL | `BOOLEAN` | `boolean` = | `bit` = | `BOOLEAN` = |
| C_DATE | `DATE` | `date` → **`timestamp`** | `date` → **`datetime2`** | `DATE` → **`DATETIME(6)`** |
| C_TIMESTAMP | `TIMESTAMP` | `timestamp` = | `datetime` → **`datetime2`** | `TIMESTAMP` → **`DATETIME(6)`** |
| C_TIMESTAMPTZ | `TIMESTAMP WITH TIME ZONE` | `timestamp with time zone` → **`timestamptz`** | `datetime` → **`datetimeoffset`** | `TIMESTAMP` → **`DATETIME(6)`** |
| C_VARCHAR | `VARCHAR2(50)` | `varchar(50)` = | `varchar(50)` → **`nvarchar(50)`** | `VARCHAR(50)` = |
| C_TEXT | `CLOB` | `bytea(4000)` → **`text`** | `varbinary(4000)` → **`nvarchar(MAX)`** | `LONGBLOB` → **`LONGTEXT`** |
| C_CHAR | `CHAR(10)` | `varchar(10)` = | `char(10)` → **`nchar(10)`** | `CHAR(10)` = |
| C_UUID | `RAW(16)` | `bytea(16)` → **`bytea`** | `varbinary(16)` = | `BINARY(16)` = |
| C_JSON | `JSON(8200)` | `jsonb` = | `varbinary(8200)` → **`nvarchar(MAX)`** | `LONGBLOB` → **`LONGTEXT`** |
| C_NCLOB | `NCLOB` | `bytea(4000)` → **`text`** | `varbinary(4000)` → **`nvarchar(MAX)`** | `LONGBLOB` → **`LONGTEXT`** |
| C_BYTEA | `BLOB` | `bytea(4000)` → **`bytea`** | `varbinary(4000)` → **`varbinary(MAX)`** | `BLOB` → **`LONGBLOB`** |
| C_KOREAN | `NVARCHAR2(100)` | `varchar(100)` = | `varchar(100)` → **`nvarchar(100)`** | `VARCHAR(100)` = |
| C_NUMBER | `NUMBER` | `numeric` = | `int` → **`float`** | `BIGINT` → **`DOUBLE PRECISION`** |
| C_FLOAT | `FLOAT` | `float8` → **`double precision`** | `float` = | `FLOAT` → **`DOUBLE PRECISION`** |
| C_KOREAN_FULL | `VARCHAR2(100 CHAR)`(고치기 전 표시 `VARCHAR2(100)`) | `varchar(100)` = | `nvarchar(100)` = | `VARCHAR(100)` = |

**데이터 확인** — 각 쌍을 Swing 제품의 Tasks ▸ Copy Tables…(새 테이블)로 복사하고, 시험용 비교기(`TmVerify`, 앱의 JDBC 연결로 두 테이블을 `ORDER BY` 첫 열로 읽어 열마다 비교:
정수·소수는 정확히, 실수는 상대 오차 1e-6, 날짜시각은 순간(1 µs 이내), 문자열·이진·UUID·JSON(공백 무시))로 원본과 비교했다. 원본 행: 최댓값·최솟값 행(int/smallint/bigint 끝값,
`9999999999.99`, `1.2345678901234567e300`, `-2.5e-300`, `1900-01-01`, 한글·일본어·악센트 문자열), 모두 NULL인 행, 5,000자 한글 `text`, 따옴표·쉼표 문자열, JSON 배열·객체.

| 원본 → 대상 | 결과 |
|---|---|
| PostgreSQL → SQL Server | 4/4행, 19열 모두 같음(한글 `nvarchar`, `timestamptz` → `datetimeoffset` 순간 같음, uuid, JSON, bytea). 고치기 전: `uuid` → `geometry`로 "binding not supported", 문자열만 복사하면 한글이 `?` |
| PostgreSQL → MariaDB | 4/4행, 모두 같음(int 끝값, `1.2345678901234567e300`, 1900-01-01, 5,000자 한글). 고치기 전: `id TINYINT` 등, `json` → `LONGBLOB`로 "Could not convert" |
| PostgreSQL → Oracle | 4/4행, 21열 모두 같음(2026-11-07 오후: `json`/`jsonb` → `JSON`, `float8` 1.23e300·-2.5e-300 → `BINARY_DOUBLE`, `time` → `TIMESTAMP` 1970-01-01, 한글 100자 → `VARCHAR2(100 CHAR)`, 정밀도 없는 `numeric` → `NUMBER`). 그 전: "Overflow"(그 뒤 ORA-17004) |
| SQL Server → PostgreSQL | 4/4행, 21열 모두 같음(`datetimeoffset` → `timestamptz`, `uniqueidentifier` → `uuid`, `ntext`/`text` → `text`) |
| SQL Server → MariaDB | 4/4행, 모두 같음(`float` → `DOUBLE PRECISION`, `varbinary(max)` → `LONGBLOB`, `uniqueidentifier` → `UUID`) |
| SQL Server → Oracle | 4/4행, 22열 모두 같음(`1900-01-01`은 Oracle에 `1900-01-01 00:00:00`으로 저장됨을 sqlplus로 확인). 2026-11-07 오후에 실수 끝값을 줄이지 않고 다시 확인: 그 전 "Underflow" |
| MariaDB → PostgreSQL | 4/4행, 모두 같음(`int unsigned` 4294967295 → `bigint`, `tinyint` -128 → `smallint`) |
| MariaDB → SQL Server | 4/4행, 모두 같음(`double` → `float`, `tinyint` -128 → `smallint`, 한글 `nvarchar`) |
| MariaDB → Oracle | 4/4행, 22열 모두 같음(2026-11-07 오후: `time` 문자열 → `TIMESTAMP` 1970-01-01, `uuid` → `VARCHAR2(36)`, `double` 끝값 → `BINARY_DOUBLE`). 그 전: "Underflow"(그 뒤 ORA-01843) |
| Oracle → PostgreSQL | 4/4행, 20열 모두 같음(`CLOB`/`NCLOB` → `text`, `JSON` → `jsonb`, `BINARY_DOUBLE` 1.23e300, `NUMBER` 123.456) |
| Oracle → SQL Server | 4/4행, 모두 같음(`NUMBER(19,0)` 끝값, `NUMBER` → `float` 123.456, `TIMESTAMP WITH TIME ZONE` → `datetimeoffset`) |
| Oracle → MariaDB | 4/4행, 모두 같음 |

2026-11-07 오후에 이 작업의 빌드로 12쌍을 모두 다시 복사했다(열 두 개를 더한 원본, 실수 끝값 그대로): 12쌍 모두 4/4행, 모든 열 같음(PostgreSQL·Oracle 원본 21열,
SQL Server·MariaDB 원본 22열). PostgreSQL → SQL Server·MariaDB의 `c_numeric_free`는 배정밀도라 비교기의 상대 오차(1e-6) 안에서 같고, 자릿수 17 넘는 부분은 남지 않는다(위 "판단").
비교기는 이때 시각대 없는 날짜시각을 JVM 시각대를 거치지 않는 벽시계 값(`LocalDateTime`)으로 비교하게 바꿨다(1900년 Asia/Seoul 지방시 오프셋 때문에 생기던 차이를 없앰).

### @export 명령 (2026-11-07)

쿼리 창 스크립트의 `@export` 명령은 오류 없이 무시되고 다음 쿼리가 쿼리 창에서 그냥 실행되었다(SWT 대조 B-S12).

- 원인: 모델의 `SQLCommandExport`(model.sql)는 명령을 읽어 스크립트 문맥에 pragma `export`를 두기만 한다. 실행 엔진은 다음 쿼리에서
  `com.seizia.chromite.sqlCommand` 확장점의 pragma 처리기를 찾는데(`SQLQueryExecuteJob.executeSingleQuery`), 처리기 `SQLPragmaExport`는 SWT 제거 때
  지운 `data.transfer.ui` 번들에 있었다. 처리기가 없으면 pragma를 건너뛰고 쿼리를 그대로 실행한다.
- 고친 것: Swing 번들이 같은 pragma 처리기 `SqlExportPragma`를 등록한다(`plugin.xml`). SWT처럼 다음 쿼리를 쿼리 창에서 실행하지 않고(쿼리 소비),
  명령의 형식·옵션을 채운 Export Data 마법사(`DataExportWizard.openForExportCommand`)를 연다. 내보내기는 데이터 전송 엔진이 한다.
  Messages 탭에 "@export: 다음 문장을 데이터 내보내기 마법사(CSV)로 보냈습니다. 쿼리 창에서는 실행하지 않았습니다." 줄을 쓴다.
  엔진은 소비한 쿼리 뒤에서 스크립트를 멈춘다(SWT와 같음, 뒤 문장은 실행하지 않음).
- 명령 형식(SWT와 같음): `@export {"type": "csv", "processor": {...}, "consumer": {...}, "producer": {...}}` 다음 줄에 내보낼 SELECT.
  - `type`: 처리기 `stream.<type>`(csv, json, xml, html, markdown.table, sql, txt, xlsx 등). `"searchSimilar": true`이면 AI 형식 이름·짧은 이름·파일 확장자도 찾는다.
  - `processor`: 형식 속성(예: CSV `delimiter`). 형식의 기본값 위에 덮어 형식 설정 단계에 보인다.
  - `consumer`: 출력 단계의 칸에 들어가는 것 — `outputFolder`, `outputFilePattern`, `outputEncoding`, `outputEncodingBOM`, `outputClipboard`, `compressResults`,
    `useSingleFile`, `openFolderOnFinish`, `deleteFileInCaseOfFail`, `dataFileConflictBehavior`, `valueFormat`, `lobExtractType`, `lobEncoding`.
    칸 없이 내보낼 때 설정에 넣는 것 — `outputTimestampPattern`, `splitOutFiles`, `maxOutFileSize`.
  - `producer`: `fetchSize`, `openNewConnections`, `queryRowCount`, `extractType`, `segmentSize`(마법사 기본: 새 연결, 행 수 세기).
- 거절(오류로 멈춤, Messages에 이유와 받는 값 목록): `type` 없음, 모르는 형식, 모르는 형식 속성, 위 목록 밖의 `consumer`/`producer` 옵션(`includePipesConfiguration`,
  `formatterProfile`, 실행 명령, 열 매핑, 결과 표 선택 옵션 등), 다음 문장이 데이터를 바꾸지 않는 SELECT 하나가 아님(`DataExportWizard.canExportQuery`).
  SWT는 모르는 형식 속성을 로그만 남기고 건너뛰었지만 그러면 사용자가 준 옵션이 조용히 무시된다. SELECT만 받는 것은 내보내기가 새 연결에서 쿼리를 실행하므로
  데이터를 바꾸는 문장이 쿼리 창의 수동 커밋 트랜잭션 밖에서 커밋되면 안 되기 때문이다.
- 명령으로 연 마법사는 폴더·패턴·형식·형식 속성을 기억하지 않는다. 스크립트의 옵션이 Export Data…의 기본값을 바꾸면 안 되기 때문이다.
- 경로는 Windows 형식으로 쓴다(JSON에서는 `"D:\\exports"`). `"D:/exports"`는 데이터 전송 엔진이 URI(`D` 파일 시스템)로 읽어
  "File system schema 'D' not recognized"로 실패한다(`DBFUtils.resolvePathFromString`, 마법사에 직접 입력해도 같음. 이 작업에서 고치지 않음).

### 남은 매핑과 기본 키 (2026-11-07 밤)

후속 과제 #39(나머지), #51, #52, #53. 결정(사용자 규칙 "제품 기능은 묻지 않는다"에 따라 주 에이전트가 정함): 기본 키를 만들고, `bit` → `boolean`.

- **기본 키(#39):** 새 테이블(새로 만들기, 삭제 후 다시 만들기)에 원본 테이블의 기본 키를 만든다. SWT도 만들지 않았지만, 기본 키 없이 복사된 테이블은 흔히 놀라는 결과라 만든다.
  - 원인: 구조 DDL(`DatabaseTransferUtils.generateStructTableDDL`)은 테이블·열 관리자로 `CREATE TABLE`만 만든다. 기본 키를 만드는 코드는 구조 DDL이 실패했을 때 쓰는
    일반 SQL 경로(`generateTargetTableDDL`의 `PRIMARY KEY (…)`)에만 있었고, 구조 DDL은 늘 쓰인다(`USE_STRUCT_DDL = true`).
  - 고친 것: 원본이 테이블이고 선언된 기본 키가 있으면(`getConstraints`의 `PRIMARY_KEY`, 고유 키·고유 인덱스는 NULL을 허용할 수 있어 쓰지 않음) `CREATE TABLE` 뒤에
    `ALTER TABLE <대상> ADD PRIMARY KEY (<열>…)`를 더한다. 이름은 데이터베이스가 정한다(PostgreSQL `<테이블>_pkey`, SQL Server `PK__…`, Oracle `SYS_C…`).
    키 열은 NOT NULL로 만든다(SQL Server는 NULL 허용 열에 기본 키를 만들지 못하고, 괄호가 있는 형식은 원본의 NOT NULL이 옮겨지지 않았다).
    키 열 가운데 건너뛴 열이 있으면 만들지 않는다. 대상 DDL 탭에 같은 문장이 보인다. 고유 키·인덱스·외래 키는 옮기지 않는다(아래 "남긴 것").
    `ALTER TABLE … ADD PRIMARY KEY`는 PostgreSQL·SQL Server·MariaDB·Oracle이 같은 문법이라 드라이버별 제약 조건 관리자를 쓰지 않았다.
- **Oracle 열 복사의 문자 길이 단위(#51):** 테이블 디자이너에서 기존 열을 복사·붙여넣으면(`OracleTableColumnManager.copyColumnProperties`) 길이 숫자만 옮겨
  `VARCHAR2(50 CHAR)` 열의 복사본이 `VARCHAR2(50)`(바이트)이 되었다. 단위(`OracleTableColumn.setCharLengthSemantics`)도 옮긴다.
- **`MAX_STRING_SIZE`(#52):** 다른 DB 문자열을 `VARCHAR2(n CHAR)`로 둘지 `CLOB`으로 둘지 정하는 한도가 4000으로 고정이었다(`OracleSQLDialect`).
  연결할 때(`initDriverSettings`) `V$PARAMETER`의 `max_string_size`를 읽어 `EXTENDED`면 32767, 아니면 4000. `V$PARAMETER`는 `SELECT_CATALOG_ROLE`이나
  권한이 있어야 읽히고(시험 사용자 `CHR_MFB`는 ORA-00942), 12c 전 서버에는 이 매개변수가 없다. 그때는 4000을 쓴다(더 긴 문자열은 모든 값을 담는 `CLOB`).
  바이트 한도는 그대로라 `EXTENDED`에서도 32767바이트를 넘는 한글 문자열은 ORA-12899로 실패한다(말없이 잘리지 않음).
- **`bit` → PostgreSQL(#53):** SQL Server `bit`가 PostgreSQL `bit`(비트 문자열)가 되었다. 이름 찾기(`DBStructUtils`)가 같은 이름을 찾고, 두 형식 모두 JDBC `BIT`라
  데이터 종류도 같았다. PostgreSQL 방언 변환기가 다른 DB의 한 비트 `BIT`(JDBC `BIT`, 정밀도 1 이하: SQL Server `bit`, MySQL/MariaDB `BIT(1)`)를 `boolean`으로 둔다.
  여러 비트(`BIT(8)`)와 PostgreSQL 계열 원본은 그대로다.
- **Oracle `NUMBER(p,0)`(#53):** 이미 같은 자릿수의 `numeric(p,0)`이다(PostgreSQL 방언 변환기의 `number`, 2026-11-07 오후 표의 `ID`). 다시 확인만 했고 코드는 바꾸지 않았다.
  DDL에는 PostgreSQL이 같은 형식으로 보는 `numeric(10)`으로 나온다.
- **아주 작은 수(#53):** 표에 `-2.5E-300`이 `-0`으로 보이던 것은 [swing-result-viewer.md](swing-result-viewer.md) "아주 작은 수의 표시".
- **NULL 열이 바뀌는 행의 삽입(확인 중 찾음):** PostgreSQL → SQL Server 복사에서 `B` 행의 NULL `note`에 앞 행 값 `한글`이 들어가고, `C` 행은 SQL Server 오류 245
  ("Conversion failed when converting the nvarchar value 'x' to data type bit")로 멈췄다(기본 키 없는 원본으로도 같음 — 이 작업 전부터 있던 결함).
  삽입 문장은 NULL인 열을 빼고 만드는데(`ExecuteInsertBatchImpl`), 행의 NULL 열이 앞 행과 다를 때 새 문장을 준비하는 조건이 "일괄 처리에 쌓인 문장이 있을 때"
  (`ExecuteBatchImpl.processBatch`, `statementsInBatch > 0`)라, 행마다 실행하는 경로(일괄 처리를 쓰지 않을 때)에서는 앞 행의 문장을 그대로 썼다. 그래서 새로 NULL이 된
  열의 매개변수에 앞 행 값이 남고 뒤 매개변수가 한 칸씩 밀렸다. 조건을 "준비한 문장이 있을 때"로 바꿨다(일괄 처리면 전처럼 먼저 실행). 같은 경로를 쓰는 스크립트 생성(값을 넣은
  INSERT 미리 보기)도 같은 원인이었다. 이 복사가 왜 일괄 처리를 쓰지 않았는지(SQL Server `supportsBatchUpdates`는 참, "행을 하나씩 삽입"은 꺼짐)는 찾지 못했다.

## upstream 대비 변경점

- SWT `data.transfer.ui` 마법사 대신 Swing 마법사(SSMS 단계 구성). 작업(task) 저장·편집, 형식 프로필, 열 고르기(`StreamMappingContainer`),
  분할 파일, 실행 명령 이벤트 처리기, 원격 파일 시스템은 이 화면에 없다.
- 테이블 복사의 대상은 트리 대화상자 대신 연결·데이터베이스·스키마 콤보로 고른다. 매핑 규칙 대화상자(이름 대소문자·공백 바꾸기), 열 변환기(transformer),
  새 테이블 속성 편집, 기존 테이블 고르기 대화상자([browse]), 매핑 저장·불러오기, 중복 키 처리 방법은 없다.
- 원본 테이블 자신을 대상으로 한 매핑을 막고, 건너뛴 테이블의 파이프는 실행하지 않는다.
- 가져오기 자동 매핑에서 맞는 열이 없는 원본 열은 새 열 만들기가 아니라 건너뛰기.
- 데이터베이스 노드의 원본 목록은 스키마별로 묶어 보이고(SWT는 데이터베이스에서 내보내기를 열 수 없음), 가져오기는 첫 단계에서 대상 테이블(기존 또는 새 테이블)을 고른다.
  여러 스키마의 같은 이름 테이블이 같은 파일·같은 대상 테이블로 가는 것을 막는다.
- 새 테이블의 형식 매핑과 값 바인딩, Oracle 열의 문자 길이 단위를 모델·방언에서 고쳤다(위 "새 테이블의 형식 매핑", SWT에도 적용). 그 밖의 모델 코드 변경은 오류 문구의 대상 이름(`DatabaseMappingContainer.getTargetFullName`, 2026-11-07),
  새 테이블의 기본 키와 NULL 열이 바뀌는 행의 삽입(`DatabaseTransferUtils`, `ExecuteBatchImpl`, 2026-11-07 밤, 위 "남은 매핑과 기본 키")이다. SWT는 새 테이블에 기본 키를 만들지 않았다.

## 확인 (2026-11-06)

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. 새 번들 의존 없음(`MANIFEST.MF` 변경 없음).
- 로컬 Docker DB 세 개(PostgreSQL 17, SQL Server 2022, MariaDB 11)에 `chr_data_transfer` DB를 만들고 `orders`(1,500행: 숫자, 날짜시각, NULL,
  쉼표·큰따옴표·줄바꿈·`=`로 시작하는 글자), `products`(50행, 불리언), 뷰 `v_big_orders`(700행), 빈 `orders_import`를 넣어 Swing 제품에서 확인했다.
  여러 Swing 앱이 동시에 떠 있어 OS 입력으로는 창 활성화가 계속 빼앗겨, 시험용 에이전트(Attach API, 저장소 밖 임시 코드)가 EDT에서 공개 Swing API
  (`doClick`, `setText`, 표 모델)로 메뉴·단추를 누르게 하고 창을 그림으로 저장했다(처음 몇 번은 마우스·키보드로 같은 흐름을 확인).
  - **Export Data (Object Explorer, 4개 객체 한 번에):** PostgreSQL에서 CSV, DbUnit, HTML, JSON, Markdown, Source code(PHP), SQL, TXT, XLSX, XML 10개 형식 모두 성공
    (행 수 1500/0/50/700, XLSX 시트 1,501행 = 머리글 + 1,500). SQL Server·MariaDB에서 CSV, XLSX, JSON, SQL 성공(같은 행 수).
  - **결과 표 Export Data:** 연결 최대 행 수(200)에서 멈춘 표에서 쿼리를 다시 실행해 PostgreSQL 1,200행, SQL Server(`TOP 900`) 900행, MariaDB 1,000행을 XLSX로 내보냄.
    SQL Server `select 1; select 2` 배치의 두 표, `select 1; delete …` 배치의 표, PostgreSQL `SELECT … FOR UPDATE` 표는 메뉴가 꺼지고 도구 설명이 이유를 알림.
  - **Import Data (CSV → `orders_import`):** 세 DB 모두 1,500행, `amount` 합계가 원본과 같고, 원본과 행별로 비교해 1,500행 일치
    (내보낸 CSV의 수식 이스케이프 `'`와 NULL → 빈 문자열은 CSV 기본 설정 그대로). 테이블에 없는 열이 있는 CSV(MariaDB)는 그 열이 `[skip]`이 되고 테이블 구조가 바뀌지 않음,
    빈 숫자 칸은 NULL, 따옴표 안 쉼표 유지.
  - **취소:** 300만 행 테이블 내보내기 중 Cancel → "The transfer was canceled", 진행 중 행 Canceled·다음 행 Canceled, 쓰다 만 파일 삭제.
  - 라이트·다크 테마의 모든 단계 화면(그림은 `D:\Tools\_scratch\data-transfer\shots\`).
- 첫 실행에서 찾아 고친 것: 임시 작업 상태 `null`로 인한 NPE(위 "실행"), `GO` 배치 첫 결과 표의 메뉴 켜짐(위 "내보내기"), 오류 문구 칸이 보이지 않던 것, 빈 테이블의 행 수 칸.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번).

### 테이블 복사 (2026-11-06)

- 같은 Docker DB 세 개에 `chr_db_copy`를 만들었다. PostgreSQL `public.customers`(1,500행: 기본 키, `varchar` NOT NULL, NULL 이메일 300개, 불리언, 날짜, 이름 일부에 한글)와
  `public.orders`(1,500행: `serial` 기본 키, `customers` 외래 키, `numeric(12,2)`, `timestamp`, NULL 375개·작은따옴표·쉼표가 든 `text`), 빈 스키마 `copy`,
  MariaDB에는 같은 이름의 기존 테이블(외래 키 포함)에 지울 행 2개씩을 넣었다. 확인 값: 행 수, `sum(amount)`, `count(distinct customer_id)`, `count(distinct name)`,
  `count(email)`, `sum(active)`, NULL 수, 특정 행(한글 이름, 따옴표 글).
  - **PostgreSQL → SQL Server(새 테이블):** Tasks ▸ Copy Tables…, 대상 `chr_db_copy.dbo`, 두 테이블 "새로 만들기", `customers.name`의 대상 형식을 `nvarchar(100)`으로 바꿈
    → DDL 미리 보기에 반영. 두 테이블 1,500행, 모든 확인 값이 원본과 같음(747277.50 / 1500 / 1500 / 1200 / 1000 / NULL 375, 한글 유지).
    "끝나면 대상 테이블 열기"로 쿼리 창 2개가 열리고 `SELECT TOP 1000`이 실행됨.
  - **SQL Server → MariaDB(기존 테이블, 비우기):** 매핑이 자동으로 "기존", 열은 이름으로 대응. TRUNCATE + 참조 무결성 끄기 + 여러 행 INSERT(500) → 비울 테이블 확인 →
    1,500행씩, 지울 행 0, 확인 값 같음, 끝난 뒤 `@@foreign_key_checks = 1`, 외래 키 유지. 영어 화면에서 PostgreSQL → MariaDB로도 같은 결과.
  - **같은 서버 복사(PostgreSQL `public` → `copy`):** Export Data…의 형식 "Database"로. 처음 고른 `public`(원본 스키마)에서는 두 테이블이 원본 자신이라 다음으로 가지 않음.
    `copy`로 바꾸면 "새로 만들기", 복사 후 `id`로 원본과 행별 비교 1,500 / 1,500행 일치. 다시 같은 대상으로 `customers` "삭제 후 다시 만들기"(확인 대화상자, DDL에 `DROP TABLE` + `CREATE TABLE`),
    `orders` "건너뛰기" → 진행 표에 한 행만, `customers` 1,500행, `orders`는 그대로.
  - **취소:** 30만 행 테이블을 SQL Server 새 테이블로 복사하던 중 Cancel → 모델의 Stop/Retry/Skip 질문 → Stop → "The transfer was canceled", 행 Canceled(커밋된 210,000행은 남음).
  - 한국어(OS 기본)·`-nl en`, 라이트·다크 화면(그림은 `D:\Tools\_scratch\db-copy\shots\`).

### 데이터베이스 노드 (2026-11-06)

- 같은 Docker DB 세 개에 `chr_db_node_transfer`를 만들었다. SQL Server `dbo.customers`(1,200행, 한글 이름 120개, NULL 이메일 300개), `dbo.orders`(2,500행),
  `dbo.import_target`(빈 테이블), `sales.regions`(5행, `서울`), `sales.orders`(800행 — `dbo.orders`와 이름이 같음). PostgreSQL은 `public`·`sales`에 같은 테이블과 뷰
  `sales.v_big_orders`(467행), 빈 스키마 `copy_target`. MariaDB는 `customers`·`orders`·`import_target`. 가져올 CSV는 300행(머리글, 쉼표·큰따옴표가 든 이름, 한글 이름 42개, 빈 금액 33개, 금액 합계 50126.25).
  검사는 `docker exec`로 행 수·합계·NULL 수·한글 행.
- **메뉴:** SQL Server·PostgreSQL·MariaDB 데이터베이스 노드에서 세 명령이 켜지고 도구 설명이 데이터베이스용, 백업·복원과 같은 작업 메뉴에 함께 있음.
  데이터베이스가 하나인 PostgreSQL 연결 노드(펼친 뒤)는 켜짐, 데이터베이스가 여럿인 SQL Server 연결과 펼치지 않은 연결은 꺼지고 도구 설명이 쓸 노드를 알림.
  PostgreSQL 스키마 노드는 전과 같음(가져오기 꺼짐, 내보내기·복사 켜짐).
- **내보내기:** SQL Server 데이터베이스에서 `dbo` 2개 + `sales` 1개를 CSV(1201/2501/6줄), `dbo.orders`·`sales.orders`·`sales.regions`를 XLSX(2501/801/6행).
  같은 이름 `orders` 두 개는 기본 패턴에서 출력 단계가 막히고 `${schema}_${table}_${timestamp}`로 바꾸자 `dbo_orders`·`sales_orders` 파일로 나감.
  PostgreSQL 연결 노드에서 `public` 2개 + `sales.regions` + 뷰를 CSV, 데이터베이스 노드에서 3개를 XLSX, MariaDB 데이터베이스에서 3개를 CSV·XLSX(행 수 모두 원본과 같음).
  시스템 스키마 표시를 켜면 SQL Server `INFORMATION_SCHEMA`·`sys`를 그때 읽어 보이고, 끄면 숨겨짐. 테이블이 없는 `db_owner`·`guest` 등은 보이지 않음.
- **테이블 복사:** SQL Server `dbo.customers`·`dbo.orders`·`sales.orders`·`sales.regions` → PostgreSQL `copy_target`: `orders` 두 개가 같은 대상이라 매핑 단계에서 막히고,
  `sales.orders`의 대상을 `sales_orders`로 바꾸자 4개 모두 성공(1200/2500/800/5행, 이메일 900, 합계 781562.50·961200.00, 한글 유지).
  PostgreSQL `public.customers`·`sales.orders`·`sales.regions` → SQL Server `copy_target`: 1200/800/5행, 합계 같음.
  PostgreSQL → MariaDB 새 테이블은 모델 형식 매핑이 `int4`를 `tinyint(4)`로 만들어 `id` 범위 오류가 났다(데이터베이스 노드와 관계없는 기존 모델 동작, 2026-11-07 고침 — 아래 "새 테이블의 형식 매핑").
- **가져오기:** SQL Server 데이터베이스에서 대상 단계로 `dbo.import_target`(기존) → 300행, 금액 267개 합계 50126.25, 한글 42행. `sales`에 새 테이블 `products_new`, 열 탭에서
  `name`을 `nvarchar(100)`, `amount`를 `price`로 바꿈 → DDL 미리 보기와 실제 테이블이 같고 300행. PostgreSQL 기존 `public.import_target` 300행, 이름을 비운 새 테이블은
  `products_2026`으로 만들어짐(300행). MariaDB 기존 `import_target`, 새 `products_new`(금액 형식 `decimal(12,2)`로 바꿈) 모두 300행·합계 같음. 새 테이블이면 테이블 비우기가 꺼짐.
- 한국어(OS 기본)·`-nl en`, 라이트·다크 화면(그림은 `D:\Tools\_scratch\db-node-transfer\shots\`).
- 첫 실행에서 찾아 고친 것: 데이터베이스에서 연 가져오기 생성자가 대상 테이블이 없을 때 NPE, 영어 대상 단계 설명이 잘리던 것(문구를 줄임).

### 새 테이블의 형식 매핑 (2026-11-07)

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS(origin/devel `38b6e0c39d` 위).
- 네 Docker DB에 `chr_type_mapping`(PostgreSQL·SQL Server·MariaDB 데이터베이스, Oracle 사용자)과 대상 스키마(`from_pg` 등, MariaDB는 `chr_type_mapping_from_pg` 등,
  Oracle은 `CHR_TM_FROM_PG` 등)를 만들고 위 원본 `type_matrix`를 넣었다. 고치기 전 제품(같은 모델 코드의 이전 빌드)과 고친 빌드에서 같은 시험을 했다.
- 각 쌍은 시험용 에이전트가 Swing 제품의 Tasks ▸ Copy Tables…를 EDT에서 눌러(대상 연결·데이터베이스·스키마 고르기 → 매핑 → "대상 DDL" 탭 읽기 → 마침) 실행했고,
  DDL은 마법사의 "대상 DDL" 탭에서, 데이터는 위 비교기로 확인했다(위 "데이터 확인" 표). 시각대 있는 값의 비교는 JVM 시각대(Asia/Seoul)에서 했다.
- UI는 바뀌지 않아 한국어·영어, 라이트·다크 화면은 다시 찍지 않았다. DDL 탭 그림은 `D:\Tools\_scratch\type-mapping\shots\a-*-ddl.png`.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: 모든 데이터 전송의 새 테이블·새 열 형식(SWT 포함), `datetimeoffset`·`timestamptz`·Oracle
  `TIMESTAMP WITH TIME ZONE` 값 편집과 바인딩(`OffsetDateTime`/`ZonedDateTime`/`Timestamp`), PostgreSQL `text`·`bytea` 열 DDL의 길이.

### Oracle 대상 복사 (2026-11-07 오후)

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS(devel `fe9f9b150f` 위).
- 네 Docker DB에 `chr_oracle_target`(PostgreSQL·SQL Server·MariaDB 데이터베이스, Oracle 사용자 `CHR_ORACLE_TARGET`)과 대상 스키마(`from_pg` 등, MariaDB는
  `chr_oracle_target_from_pg` 등, Oracle은 `CHR_OT_FROM_PG` 등)를 만들고 위 `type_matrix`에 `c_korean_full`, `c_numeric_free`(PostgreSQL)를 더해 넣었다.
  같은 시험(위 "새 테이블의 형식 매핑 (2026-11-07)"의 에이전트·비교기)을 이 작업 전 devel 빌드와 고친 빌드에서 했다(결과는 위 "Oracle 대상과 남은 매핑", "데이터 확인").
- sqlplus로 확인: `CHR_OT_FROM_PG.TYPE_MATRIX`의 문자열 열이 `CHAR_USED = 'C'`, 한글 100자 = 300바이트, `c_double` 1.235E+300, `c_time` `1970-01-01 23:59:58`,
  `c_numeric_free` `NUMBER`(`12345678901234567890.123456789` 그대로).
- Oracle 표 편집: `CHR_ORACLE_TARGET.TYPE_MATRIX`를 "상위 1000개 행 선택"으로 열고 한 행의 `C_DOUBLE`(`BINARY_DOUBLE`)을 `1.5E300`·`-2.5E-300`으로,
  `C_KOREAN_FULL`(`VARCHAR2(100 CHAR)`)을 한글 100자로 바꿔 저장 → sqlplus에서 값과 100자/300바이트 확인 후 원래 값으로 되돌림
  (그림 `D:\Tools\_scratch\oracle-target\shots\grid-edit-oracle-final.png`). DDL 탭 그림은 `D:\Tools\_scratch\oracle-target\shots\a-*-ddl.png`.
- UI는 바뀌지 않아 한국어·영어, 라이트·다크 화면은 다시 찍지 않았다.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: 다른 데이터 소스의 LOB·이진·JSON 값을 받는 모든 데이터 전송(내용 객체 변환), 시각 문자열을
  받는 `TIMESTAMP` 열, Oracle `BINARY_FLOAT`/`BINARY_DOUBLE` 값 편집, Oracle 문자 길이(`CHAR`) 열의 DDL·표 디자이너의 형식 입력, Oracle `NUMBER` 새 열의 DDL.

### 남은 매핑과 기본 키 (2026-11-07 밤)

- 빌드: `-Pproduct-chromite-swing` BUILD SUCCESS(origin/devel `897c37749e` 위). 시험 작업 공간 사본과 이 빌드로 앱 하나를 띄우고, 같은 프로세스의 시험 에이전트로
  Tasks ▸ Copy Tables…를 EDT에서 눌렀다(OS 입력 없음). 원본: PostgreSQL `postgres.chr_mfb.pk_src`(`code varchar(20)`, `id int`, `flag boolean`, `note text`,
  기본 키 `(code, id)`, NULL 위치가 다른 3행), SQL Server `chr_mfb.dbo.pk_src`(`id int` 기본 키, `flag bit`, `name nvarchar(30)`, 3행), Oracle `CHR_MFB.NUM_SRC`
  (`ID NUMBER(10,0)` 기본 키, `AMOUNT NUMBER(12,2)`, `NAME VARCHAR2(50 CHAR)`, `CODE VARCHAR2(20)`, 2행).
- PostgreSQL → SQL Server(`chr_mfb.from_pg`): 대상 DDL `code nvarchar(20) NOT NULL, id int NOT NULL, flag bit NULL, note nvarchar(MAX) NULL` +
  `ALTER TABLE chr_mfb.from_pg.pk_src ADD PRIMARY KEY (code, id)`, SUCCESS 3행. `sys.key_constraints`에 `PRIMARY_KEY_CONSTRAINT`(`code` 1, `id` 2), 값 `A|1|1|한글`, `B|2|0|NULL`, `C|3|NULL|x`.
  `ExecuteBatchImpl`을 고치기 전 빌드: 같은 복사가 `C` 행에서 오류 245, `B`의 `note`가 `한글`(기본 키 없는 원본 `nopk_src`도 같음).
- SQL Server → PostgreSQL(`chr_mfb_from_mssql`): `id int NOT NULL, flag boolean NULL, name varchar(30) NULL` + `ADD PRIMARY KEY (id)`, SUCCESS 3행.
  `pg_constraint` `pk_src_pkey PRIMARY KEY (id)`, `flag`는 `boolean`(`t`, `f`, NULL).
- Oracle → PostgreSQL(`chr_mfb_from_ora`): `"ID" numeric(10) NOT NULL, "AMOUNT" numeric(12, 2)` + `ADD PRIMARY KEY ("ID")`, SUCCESS 2행.
  `information_schema.columns`에서 `ID numeric` 정밀도 10·소수 자리 0, `num_src_pkey PRIMARY KEY ("ID")`, `1234567890`·`12.34`·`한글이름` 그대로.
- 모델 확인(같은 앱 안에서 부름): `DBStructUtils.mapTargetDataType` — SQL Server `flag bit` → PostgreSQL `boolean`, PostgreSQL `bool` → SQL Server `bit`(그대로),
  Oracle `NUMBER(10,0)` → `numeric(10,0)`. Oracle `NAME VARCHAR2(50 CHAR)` 열을 열 관리자로 복사한 새 열의 DDL `ADD NAME VARCHAR2(50 CHAR) NULL`,
  `CODE VARCHAR2(20)`(바이트)는 `VARCHAR2(20)`. Oracle 방언의 한도: 시험 DB `MAX_STRING_SIZE = STANDARD`라 4000(`system` 연결이 `V$PARAMETER`를 읽음).
  `EXTENDED` 경로는 시험하지 못했다(바꾸면 되돌릴 수 없는 설정이라 바꾸지 않음).
- 대상 테이블·시험 스키마·사용자는 확인 뒤 지웠다. UI 문구는 바뀌지 않아 화면 언어·테마별 그림은 찍지 않았다.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: 새 테이블을 만드는 모든 데이터 전송(기본 키 추가, 키 열 NOT NULL), 행마다 실행하는
  모든 삽입(데이터 전송, `ExecuteBatchImpl`을 쓰는 결과 저장의 INSERT)과 값을 넣은 INSERT 스크립트, PostgreSQL 대상의 `bit` 매핑, Oracle 열 복사, Oracle 대상 문자열 한도.

## 남긴 것

- 결과 표의 쿼리를 다른 DB 테이블로 복사(모델은 지원, 이 마법사에서는 확인하지 않아 "Database"를 두지 않음).
- 테이블 복사의 매핑 규칙·열 변환기·새 테이블 속성·중복 키 처리 방법·매핑 저장, 작업으로 저장, 외래 키 옮기기.
- XLSX 가져오기(모델에 처리기 없음), 여러 파일 한 번에 가져오기. 새 테이블로 가져오기는 데이터베이스 노드에서만 된다(테이블·스키마 노드는 전과 같음).
- 형식 매핑에 남은 것: MariaDB `TIME`의 24시간 넘는 값 → Oracle. (2026-11-07 오후에 Oracle 대상 값 바인딩, MariaDB `uuid`, 바이트 길이,
  정밀도 없는 `numeric`을, 밤에 `bit` → `boolean`, Oracle 열 복사의 길이 단위, `MAX_STRING_SIZE`를 고쳤고 `NUMBER(p,0)` → `numeric(p,0)`을 확인 —
  위 "Oracle 대상과 남은 매핑", "남은 매핑과 기본 키")
- 새 테이블에는 기본 키만 옮긴다. 고유 키·인덱스·외래 키·CHECK, 키가 아닌 열의 NOT NULL(괄호가 있는 형식일 때, 모델 그대로)은 옮기지 않는다.
- `MAX_STRING_SIZE = EXTENDED` 경로는 시험 DB에서 확인하지 못했다.
- 여러 스키마를 복사할 때 원본 스키마마다 같은 이름의 대상 스키마로 나눠 보내기(모델의 대상 컨테이너가 하나라 다루지 않음).
- 데이터베이스가 여럿인 연결 노드에서 데이터베이스를 고르게 하기(지금은 꺼짐), 펼치지 않은 연결 노드에서 켜기(메타데이터를 EDT에서 읽지 않기 위해 꺼짐).
- 내보내기 열 고르기, 형식 프로필(날짜·숫자 형식), 분할 파일, 작업으로 저장.
- 결과 표의 "가져온 행만/선택한 행만" 내보내기를 마법사에서 고르기(지금은 CSV·XLSX 항목).

## 변경 이력

- 2026-11-06 최초 작성: Export Data(Object Explorer·결과 표, 모든 스트림 형식), Import Data(CSV → 기존 테이블).
- 2026-11-06 필터·정렬한 결과 표의 Export Data…가 원래 쿼리(모든 행, 원래 순서)를 내보내던 문제: 뷰어가 서버에서 읽은 필터·정렬을 producer에 넘기고,
  표 안에서만 한 정렬은 마법사에 알린다.
- 2026-11-06 꺼진 Export Data…의 도구 설명이 실제 이유(바인딩한 실행 등) 대신 늘 "SELECT 하나인 쿼리만"이던 문제.
- 2026-11-06 테이블 복사: Tasks ▸ Copy Tables…와 Export Data…의 "Database" 대상(`DatabaseCopyPages`, `DatabaseCopyMappings`, `DbCopyMessages`).
- 2026-11-06 데이터베이스 노드: Import Data… / Export Data… / Copy Tables…를 데이터베이스 노드와 데이터베이스가 하나인 연결 노드에서 켬. 스키마별 원본 목록과 시스템 스키마 표시,
  같은 이름 파일·같은 대상 테이블 검사, 가져오기 대상 테이블 고르기와 새 테이블로 가져오기(`DataImportTargetPage`), 메뉴 도구 설명.
- 2026-11-07 새 테이블의 형식 매핑(모델·방언, SWT에도 적용): PostgreSQL `int4`/`int8` → MariaDB `TINYINT`, 다른 DB 문자열 → SQL Server `varchar`(한글 손실) 등을
  JDBC 형식·값 범위와 방언 변환기로 고침(`DBStructUtils`, SQL Server·MySQL·PostgreSQL·Oracle 방언), 시각대 있는 값과 JSON 내용의 바인딩(`JDBCDateTimeValueHandler`,
  `SQLServerDateTimeOffsetHandler`, `TemporalAccessorValueHandler`, `PostgreJSONValueHandler`). 매핑 표와 4×3 복사 확인.
- 2026-11-07 기존 테이블로 보낸 전송의 오류에 대상 이름 대신 `….null`이 나오던 것(`DatabaseMappingContainer.getTargetFullName`, SWT에도 적용).
  [swing-followup-fixes.md](swing-followup-fixes.md) "전송 대상 이름".
- 2026-11-07 Oracle 대상 복사와 남은 매핑(모델·방언, SWT에도 적용): 다른 DB의 내용 객체를 대상 처리기의 내용으로 바꿔 바인딩(`JDBCContentValueHandler`, PostgreSQL `json` → Oracle
  ORA-17004), 텍스트 시각 → `TIMESTAMP`(`JDBCDateTimeValueHandler`, MariaDB → Oracle ORA-01843), `BINARY_FLOAT`/`BINARY_DOUBLE`을 IEEE 값으로 바인딩
  (`OracleBinaryFloatValueHandler`), 다른 DB 문자열 → `VARCHAR2(n CHAR)`/`CHAR(n CHAR)`와 Oracle 열의 문자 길이 단위(`OracleTableColumn`, `OracleSQLDialect`),
  정밀도 없는 소수 → 배정밀도·Oracle `NUMBER`(`DBStructUtils`, `OracleSQLDialect`), MariaDB `uuid` → PostgreSQL `uuid`(`PostgreDialect`). 4×3 복사 다시 확인과 Oracle 표 편집.
- 2026-11-07 `@export` 명령: 조용히 무시되고 다음 쿼리가 실행되던 것을 고침. Swing pragma 처리기 `SqlExportPragma`가 다음 쿼리를 실행하지 않고 형식·옵션을 채운 Export Data 마법사를 연다(`DataExportWizard.openForExportCommand`). 모르는 형식·옵션, SELECT가 아닌 다음 문장은 오류. PostgreSQL(CSV, 구분자 `;`)·SQL Server(JSON)에서 파일 생성 확인(증거: `D:\Tools\_scratch\p3-transactions-export\`). 위 "@export 명령".
- 2026-11-07 남은 매핑과 기본 키(모델·방언, SWT에도 적용): 새 테이블에 원본의 기본 키(`DatabaseTransferUtils`, #39), Oracle 열 복사의 문자 길이 단위
  (`OracleTableColumnManager`, #51), `MAX_STRING_SIZE`에 따른 `VARCHAR2` 한도(`OracleSQLDialect`, #52), 다른 DB의 한 비트 `BIT` → PostgreSQL `boolean`(`PostgreDialect`, #53),
  NULL 열이 바뀌는 행을 행마다 삽입할 때 앞 행의 문장을 쓰던 것(`ExecuteBatchImpl`). `NUMBER(p,0)` → `numeric(p,0)`은 확인만 함.
- 2026-11-08 Query Library가 값을 바인딩한 결과의 Export Data…를 켬: 데이터 컨테이너가 같은 값을 바인딩해 다시 읽음([swing-query-library.md](swing-query-library.md)).
