# Swing 데이터 가져오기·내보내기 (Import and Export)

## 목적

DBeaver의 데이터 전송(`org.jkiss.dbeaver.data.transfer` 모델 + `.ui` SWT 마법사)을 Swing 제품에 SSMS "SQL Server Import and Export Wizard" 모양으로 옮긴다.

1. **Export Data…** — Object Explorer(테이블·뷰 여러 개, 스키마·폴더)와 결과 표에서 모델이 등록한 모든 파일 형식으로 내보내기.
2. **Import Data…** — CSV 파일을 이미 있는 테이블에 넣기(열 매핑, 파일 설정, 넣기 설정).
3. 다른 연결·DB로 테이블 복사(`DatabaseTransferConsumer`) — 이번에는 하지 않았다(아래 "남긴 것").

## 구조

| 클래스 (Swing 번들 `com.seizia.chromite.ui.swing`) | 역할 |
|---|---|
| `DataTransferWizard` (새) | 마법사 공통 틀: 왼쪽 단계 목록, 머리글, Back/Next/Finish/Cancel, 진행 페이지, 모델 실행(`DTTaskHandlerTransfer.executeWithSettings`) |
| `DataExportWizard` (새) | 내보내기 단계: 원본(테이블 목록 또는 결과 표의 쿼리) → 형식 → 형식 설정 → 출력 → 요약 |
| `DataImportWizard` (새) | 가져오기 단계: 파일·형식·파일 설정과 미리 보기 → 열 매핑 → 넣기 설정 → 요약 |
| `DataTransferObjects` (새) | 원본 객체 판단·읽기(테이블·뷰, 그 부모), 양식 줄, 안내 문구, 객체 칸 렌더러 |
| `ObjectExplorerPanel` (변경) | 오른쪽 클릭 **Tasks ▸ Import Data… / Export Data…** |
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
- **기존 "Export to CSV… / Export to Excel (XLSX)…"는 남겼다.** 이 둘은 표에 보이는 행(편집 중인 값 포함, 다시 실행할 수 없는 결과 포함)을 바로 쓰는 기능이고,
  마법사는 쿼리를 다시 실행한다. 표는 값을 표시 문자열로만 들고 있어(C단계 설계) 모델의 스트림 내보내기(값 처리기로 결과 집합에서 다시 읽음)에 "가져온 행"을 넣을 수 없으므로
  마법사가 두 항목을 대신하지 못한다. 그래서 `ResultSetExport`는 지우지 않았다(CLAUDE.md 4번: 쓸모없어진 코드가 아님).
- **형식:** `StreamTransferConsumer` 노드의 처리기 전체(순서 `order`, 이름). Swing 제품 기준 CSV, DbUnit, HTML, JSON, Markdown, SQL, Source code, TXT, XML, XLSX(`data.office`).
- **설정 기억:** 폴더, 파일 이름 패턴, 문자 집합, BOM, 형식, 폴더 열기는 이 번들 설정 저장소(`dataTransfer.export.*`)에 저장한다. 형식별 속성은 실행 중에만 기억한다.
- **같은 이름 파일:** 기본값은 Ask(모델이 `showUserChoice`로 묻는다). 모델의 `loadSettings` 기본값(PATCHNAME)이 아니라 필드 기본값(ASK)을 따랐다.

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
  분할 파일, 실행 명령 이벤트 처리기, 원격 파일 시스템, DB→DB 복사는 이 화면에 없다.
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

## 남긴 것

- 다른 연결·DB로 테이블 복사(`DatabaseTransferConsumer`, 대상 테이블 만들기 매핑) — 마법사 틀(`DataTransferWizard`)은 그대로 쓸 수 있다.
- XLSX 가져오기(모델에 처리기 없음), 여러 파일 한 번에 가져오기, 새 테이블로 가져오기.
- 내보내기 열 고르기, 형식 프로필(날짜·숫자 형식), 분할 파일, 작업으로 저장.
- 결과 표의 "가져온 행만/선택한 행만" 내보내기를 마법사에서 고르기(지금은 CSV·XLSX 항목).

## 변경 이력

- 2026-11-06 최초 작성: Export Data(Object Explorer·결과 표, 모든 스트림 형식), Import Data(CSV → 기존 테이블).
- 2026-11-06 필터·정렬한 결과 표의 Export Data…가 원래 쿼리(모든 행, 원래 순서)를 내보내던 문제: 뷰어가 서버에서 읽은 필터·정렬을 producer에 넘기고,
  표 안에서만 한 정렬은 마법사에 알린다.
