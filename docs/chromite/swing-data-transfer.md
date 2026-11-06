# Swing 데이터 가져오기·내보내기·테이블 복사 (Import and Export)

## 목적

DBeaver의 데이터 전송(`org.jkiss.dbeaver.data.transfer` 모델 + `.ui` SWT 마법사)을 Swing 제품에 SSMS "SQL Server Import and Export Wizard" 모양으로 옮긴다.

1. **Export Data…** — Object Explorer(테이블·뷰 여러 개, 스키마·폴더)와 결과 표에서 모델이 등록한 모든 파일 형식으로 내보내기.
2. **Import Data…** — CSV 파일을 이미 있는 테이블에 넣기(열 매핑, 파일 설정, 넣기 설정).
3. **Copy Tables…** — 테이블·뷰를 다른 연결·데이터베이스(같은 서버의 다른 스키마 포함)로 복사(`DatabaseTransferConsumer`, SWT "Database → Database").
   Object Explorer Tasks ▸ Copy Tables…, 또는 Export Data…의 형식 목록에서 "Database". 아래 "테이블 복사".

## 구조

| 클래스 (Swing 번들 `com.seizia.chromite.ui.swing`) | 역할 |
|---|---|
| `DataTransferWizard` (새) | 마법사 공통 틀: 왼쪽 단계 목록, 머리글, Back/Next/Finish/Cancel, 진행 페이지, 모델 실행(`DTTaskHandlerTransfer.executeWithSettings`) |
| `DataExportWizard` (새) | 내보내기 단계: 원본(테이블 목록 또는 결과 표의 쿼리) → 형식 → 형식 설정 → 출력 → 요약 |
| `DataImportWizard` (새) | 가져오기 단계: 파일·형식·파일 설정과 미리 보기 → 열 매핑 → 넣기 설정 → 요약 |
| `DataTransferObjects` (새) | 원본 객체 판단·읽기(테이블·뷰, 그 부모), 양식 줄, 안내 문구, 객체 칸 렌더러 |
| `ObjectExplorerPanel` (변경) | 오른쪽 클릭 **Tasks ▸ Import Data… / Export Data… / Copy Tables…**, 내보내기 마법사에 쿼리 창 열기(`QueryWindows`)를 넘김 |
| `DatabaseCopyPages` (새) | 테이블 복사 단계: 대상(연결 → 데이터베이스 → 스키마) → 테이블·열 매핑과 대상 DDL 미리 보기 → 넣기 설정, 요약 줄, 실행 설정 |
| `DatabaseCopyMappings` (새) | SWT 매핑 페이지의 모델 호출을 화면과 떼어 옮김: 대상 컨테이너 단계, 매핑 만들기·대상 테이블/열 정하기, 형식 목록, 대상 DDL |
| `internal/DbCopyMessages` (새) | 테이블 복사 화면 문구(영어 기본 + `_ko`) |
| `ResultSetGrid` (변경) | 오른쪽 클릭 **Export Data…**, `Fetched.exportQuery`(다시 실행할 쿼리) |
| `SqlEditorPanel` (변경) | 쿼리의 첫 결과 표에 그 쿼리의 `SQLQueryDataContainer`를 넘김 |
| `ConnectionPropertiesTable` (변경, Javadoc만) | 처리기 속성 편집에도 쓴다는 설명 |

모델 쪽은 하나도 고치지 않았다. Swing 번들은 이미 `org.jkiss.dbeaver.data.transfer`·`data.office`를 `Require-Bundle`로 쓰고 있었고(F단계), 새 번들 의존은 없다.

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
  꺼진 메뉴의 도구 설명은 수신기가 아는 이유(`ResultSetSource.requeryBlocker`, 예: Query Library가 값을 바인딩한 실행의 "parameter values bound for this run only")가
  있으면 그것을 보인다. 처음에는 늘 "SELECT 하나인 쿼리만" 문구라, SELECT 하나인 바인딩 실행에서 이유를 잘못 알렸다.
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
- **형식 매핑은 모델 그대로:** `DBStructUtils.mapTargetDataType` 결과를 고치지 않는다. 확인에서 본 예: PostgreSQL `int4` → SQL Server `bigint`, `bool` → `bit`,
  `timestamp` → `datetime`, `text` → `text`, `varchar(n)` → `varchar(n)`(유니코드가 아님). 그래서 한글이 든 열은 대상 형식을 `nvarchar(n)`으로 바꿔야 한다(열 탭에서 편집).
  모델의 구조 DDL은 기본 키를 만들지 않고, 괄호가 있는 형식(`varchar(100)`)은 원본의 NOT NULL을 옮기지 않는다(`generateStructTableDDL`, SWT도 같음).
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

## upstream 대비 변경점

- SWT `data.transfer.ui` 마법사 대신 Swing 마법사(SSMS 단계 구성). 작업(task) 저장·편집, 형식 프로필, 열 고르기(`StreamMappingContainer`),
  분할 파일, 실행 명령 이벤트 처리기, 원격 파일 시스템은 이 화면에 없다.
- 테이블 복사의 대상은 트리 대화상자 대신 연결·데이터베이스·스키마 콤보로 고른다. 매핑 규칙 대화상자(이름 대소문자·공백 바꾸기), 열 변환기(transformer),
  새 테이블 속성 편집, 기존 테이블 고르기 대화상자([browse]), 매핑 저장·불러오기, 중복 키 처리 방법은 없다.
- 원본 테이블 자신을 대상으로 한 매핑을 막고, 건너뛴 테이블의 파이프는 실행하지 않는다.
- 가져오기 자동 매핑에서 맞는 열이 없는 원본 열은 새 열 만들기가 아니라 건너뛰기.
- 모델 코드 변경 없음.

## 확인 (2026-11-06)

- 빌드: `-Pproduct-dbeaver-ce,product-chromite-swing` BUILD SUCCESS. 새 번들 의존 없음(`MANIFEST.MF` 변경 없음).
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

## 남긴 것

- 결과 표의 쿼리를 다른 DB 테이블로 복사(모델은 지원, 이 마법사에서는 확인하지 않아 "Database"를 두지 않음).
- 테이블 복사의 매핑 규칙·열 변환기·새 테이블 속성·중복 키 처리 방법·매핑 저장, 작업으로 저장, 외래 키 옮기기.
- XLSX 가져오기(모델에 처리기 없음), 여러 파일 한 번에 가져오기, 새 테이블로 가져오기.
- 내보내기 열 고르기, 형식 프로필(날짜·숫자 형식), 분할 파일, 작업으로 저장.
- 결과 표의 "가져온 행만/선택한 행만" 내보내기를 마법사에서 고르기(지금은 CSV·XLSX 항목).

## 변경 이력

- 2026-11-06 최초 작성: Export Data(Object Explorer·결과 표, 모든 스트림 형식), Import Data(CSV → 기존 테이블).
- 2026-11-06 필터·정렬한 결과 표의 Export Data…가 원래 쿼리(모든 행, 원래 순서)를 내보내던 문제: 뷰어가 서버에서 읽은 필터·정렬을 producer에 넘기고,
  표 안에서만 한 정렬은 마법사에 알린다.
- 2026-11-06 꺼진 Export Data…의 도구 설명이 실제 이유(바인딩한 실행 등) 대신 늘 "SELECT 하나인 쿼리만"이던 문제.
- 2026-11-06 테이블 복사: Tasks ▸ Copy Tables…와 Export Data…의 "Database" 대상(`DatabaseCopyPages`, `DatabaseCopyMappings`, `DbCopyMessages`).
