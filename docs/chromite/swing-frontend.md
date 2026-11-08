# Swing 프런트엔드

## 목적

DBeaver의 SWT/Eclipse RCP UI를 Swing으로 새로 만든다.
화면 배치와 동작은 SSMS를 따르고, 모양은 다크·라이트 모두 DataGrip New UI를 따른다(2026-11-06 결정, 목표 사양은 [design-spec.md](design-spec.md)).

2026-11-07부터 Chromite는 Swing 제품만 만든다. SWT 제품과 SWT UI 번들은 삭제했다([swt-removal.md](swt-removal.md)).
이 문서에서 "SWT 버전", "SWT 구현"으로 가리키는 클래스와 파일 링크(`ui.*`, `ext.*.ui`, `core` 번들)는 Swing으로 옮길 때 참고한
삭제 전 코드다. 삭제 직전 커밋 `263b8ddb45`(git 이력)에서 볼 수 있다.

## 왜 Swing인가

- DataGrip(IntelliJ 플랫폼)이 Swing 기반이다. DataGrip 수준의 디자인을 내려면 Swing이 유리하다.
- SWT는 각 OS의 네이티브 위젯을 쓰므로 트리, 탭, 스크롤바 등의 모양을 바꾸는 데 한계가 있다.
- SWT 안에 Swing을 섞는 방식(SWT_AWT 브리지)은 포커스, 키 입력, HiDPI 문제가 잦아 쓰지 않는다.

## 현재 코드 규모 (2026-11-06 측정)

| 구분 | 규모 |
|---|---|
| 전체 Java 파일 | 6,949개, 플러그인 163개 |
| UI 계층 (SWT·JFace·Eclipse UI·`com.seizia.chromite.ui` 의존) | 약 2,050개 파일, 약 38.6만 줄 → 새로 작성 |
| 모델 계층 (UI 무관) | 약 5,200개 파일 → 재사용 |

UI는 SWT 위젯뿐 아니라 Eclipse RCP 워크벤치(뷰, 편집기, 퍼스펙티브, 명령, 단축키, 설정 화면, `plugin.xml` UI 확장)에 묶여 있어
점진적으로 바꿀 수 없다. Swing UI를 새로 만들고 모델 계층 위에 올린다.

## 구조

```
┌──────────────────────────────────────────┐
│ Chromite Swing UI (새 플러그인)            │
│  FlatLaf 테마 · 도킹 · SQL 편집기 · 그리드  │
│  SwingPlatformUI (DBPPlatformUI 구현)      │
├──────────────────────────────────────────┤
│ 기존 모델 계층 (재사용)                     │
│  model · model.sql · registry · data.transfer · ext.* │
├──────────────────────────────────────────┤
│ OSGi (Equinox) 런타임                      │
└──────────────────────────────────────────┘
```

- **출발점:** [com.seizia.chromite.headless](../../plugins/com.seizia.chromite.headless) 플러그인이 SWT 없이 모델 계층을 띄운다.
  `ChromiteHeadlessApplication`(`DesktopApplicationImpl` 상속), `ChromiteTestPlatform`(`BasePlatformImpl` 상속),
  `ChromiteTestPlatformUI`(`ConsoleUserInterface` 상속)로 구성되며, 의존하는 번들은
  `equinox.app`, `core.resources`, `model`, `model.rcp`, `registry`뿐이다.
- **새 플러그인:** headless 앱과 같은 방식으로 `DesktopApplicationImpl`을 상속하고, 시작할 때 Swing 창을 연다.
- **모델 → UI 호출:** 모델 계층은 오류 표시, 확인 질문, 비밀번호 입력 등을
  [DBPPlatformUI](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/runtime/ui/DBPPlatformUI.java)(메서드 약 28개)로 요청한다.
  이것을 Swing으로 구현하면 모델 계층을 고치지 않고 쓸 수 있다.
- **스레드:** 화면 작업은 Swing EDT에서 하고, DB 작업은 모델 계층의 Job으로 백그라운드에서 실행한다.
- **제품 구성:** SWT UI 플러그인을 뺀 Swing 전용 `.product`를 새로 만든다. 2026-11-07부터 유일한 제품이다.
- **Java 버전:** 새 Swing 코드는 Java 25를 기준으로 한다(권장, [runtime.md](runtime.md) 참고).

## 외부 라이브러리 (후보)

| 라이브러리 | 용도 | 라이선스 |
|---|---|---|
| FlatLaf | IntelliJ/Darcula 계열 테마, JBR 사용자 정의 제목 표시줄 지원 | Apache 2.0 |
| RSyntaxTextArea | SQL 편집기 (구문 강조, 자동완성 팝업, C단계 도입) | BSD |
| ModernDocking | 도구 창 도킹 (2026-11-06 도입, "도구 창 도킹") | MIT |
| JSVG | 탐색기 SVG 아이콘 그리기 (B단계 도입) | MIT |

도입 전에 각 라이브러리의 최신 라이선스를 다시 확인한다. ModernDocking은 B단계에서 검토만 했고 2026-11-06에 도입했다("도구 창 도킹").

## 기존 코드 재사용 분석

| 기능 | 핵심 클래스 | 위치 | 판단 |
|---|---|---|---|
| 연결 정보, 드라이버 목록 | `DataSourceDescriptor`, `DriverDescriptor` | `registry` | 그대로 재사용 |
| 자동완성 | `SQLQueryCompletionAnalyzer` (SWT 기본 엔진), `SQLCompletionAnalyzer` (예전 엔진) | `model.sql` (SWT 의존 없음) | 분석 로직 재사용, 팝업 UI만 새로 작성. F단계에서 SWT 기본값과 같은 새 엔진을 연결("구현 현황 — F단계") |
| SQL 문장 분리 | `SQLScriptParser` | `model.sql` | 그대로 재사용 |
| DDL 보기, Script as | `DBPScriptObject` | `model` | 그대로 재사용 |
| 결과 내보내기 | `DataExporterCSV` 등 | `data.transfer` (SWT 의존 없음) | 엔진 재사용, 설정 화면만 새로 작성. E단계의 결과 표 내보내기는 표가 표시 문자열만 가지고 있어 Swing 번들에서 CSV를 직접 쓴다("구현 현황 — E단계"). XLSX는 F단계에서 `DataExporterXLSX`(`data.office`)를 그대로 쓴다("구현 현황 — F단계") |
| 쿼리 실행 | `SQLQueryExecuteJob` + `SQLQueryExecuteHandler` | `model.sql` (SWT 의존 없음) | 2026-11-06 `SQLQueryJob`에서 분리 완료. 그대로 재사용 ("쿼리 실행 계층") |
| 결과 데이터 모델 | `ResultSetModel` (1,261줄, UI import 1개) | `ui.editors.data` | 색상 처리를 떼어 낸 뒤 이전 ("쿼리 실행 계층"의 결과 모델 분석). G단계에서는 옮기지 않고 모델 계층 인터페이스 `DBDResultSetModel`을 Swing 번들에서 구현("구현 현황 — G단계") |
| 결과 편집 저장 | `ResultSetPersister` (816줄, UI import 4개) | `ui.editors.data` | `ResultSetViewer` 의존을 끊은 뒤 이전 (같은 곳). G단계에서는 옮기지 않고 모델 계층 저장 엔진 `DBDResultSetDataUpdater`를 Swing 번들에서 상속(같은 곳) |

## MVP 범위

1. **메인 창 (SSMS 배치):** 왼쪽 Object Explorer, 가운데 쿼리 탭, 아래 Results / Messages 탭, 오른쪽 Properties
2. **연결:** 범용 연결 화면 하나로 전체 드라이버를 지원한다. 드라이버 정의의 속성 목록으로 화면을 자동으로 구성한다
   (호스트, 포트, DB, 계정 + 드라이버 속성 표). 저장 형식은 기존 모델 계층을 그대로 써서 SWT 버전과 연결 목록을 공유한다.
3. **Object Explorer:** 서버 → DB → 스키마 → 테이블·뷰·프로시저 트리
4. **쿼리 편집기:** 구문 강조, 자동완성, 실행 (아래 "쿼리 실행 사양")
5. **결과 그리드:** 조회 결과 표시, 복사, 편집·저장 (SSMS의 Edit Top 200 Rows에 해당)
6. **Script as / DDL 보기:** 객체 우클릭 → CREATE·SELECT 스크립트 생성, DDL 보기
7. **결과 내보내기:** CSV, Excel
8. **테마:** 다크·라이트 모두 DataGrip New UI 모양(목표 사양은 [design-spec.md](design-spec.md))

MVP 이후: 테이블 디자이너, ER 다이어그램, 데이터 비교, AI 기능 등.

## 쿼리 실행 사양

| 단축키 | 동작 |
|---|---|
| F5 | 선택 영역이 있으면(1자 이상) 선택한 내용만 실행하고, 없으면 편집기 전체를 실행한다 |
| Ctrl+Enter, Alt+P 등 | 커서 위치 문장, 커서부터, 행 수, 식(2026-11-08, [swing-query-commands.md](swing-query-commands.md)) |

- SWT 버전의 "스크립트 실행" 명령([SQLEditor.java:2965](../../plugins/com.seizia.chromite.ui.editors.sql/src/com/seizia/chromite/ui/editors/sql/SQLEditor.java:2965))도
  같은 방식이지만 선택 길이가 2자 이상일 때만 선택으로 본다. Swing 버전은 1자부터 선택으로 본다.
- **SQL Server:** SSMS처럼 `GO`를 기준으로 배치를 나눠 배치 단위로 서버에 보낸다.
  같은 배치 안에서 `DECLARE`한 변수를 이어서 쓸 수 있어야 한다.
  (기존 DBeaver 방식은 구분자로 문장을 나눠 하나씩 실행한다.)
- **결과 표시:** 결과 집합마다 탭 하나(Result1, Result2…)이고, 메시지(영향받은 행 수, 오류, PRINT 출력)는 같은 탭 줄의 Messages 탭에 모은다.
  처음에는 SSMS처럼 결과 창 하나에 결과 표를 위아래로 쌓았는데, 2026-11-08 사용자 결정으로 SSMS를 따르지 않고 결과별 탭으로 바꿨다("결과 탭").
- F5 사양은 Swing 버전에만 적용한다. SWT 버전의 단축키는 바꾸지 않는다.

## 쿼리 실행 계층

SQL 실행 로직을 SWT 플러그인에서 모델 계층으로 옮겨 Swing 편집기가 그대로 쓰게 했다.

### 무엇을 옮겼나

| 클래스 | 위치 | 역할 |
|---|---|---|
| `SQLQueryExecuteJob` | `model.sql` 번들, `com.seizia.chromite.model.sql.exec` | 실행 엔진. `SQLQueryJob`의 실행 코드를 그대로 옮겼다 |
| `SQLQueryExecuteHandler` | 같은 곳 | 실행 중 UI가 개입하는 지점(결과 수신기, 대화상자, 편집기 설정값)을 모은 인터페이스 |
| `SQLQueryJob` | `ui.editors.sql` (기존) | `SQLQueryExecuteJob`을 상속하고 SWT용 `SQLQueryExecuteHandler` 구현만 둔다 |

- **엔진에 남은 일 (UI 무관):** 세션 열기, 쿼리 관리자(QM) 기록, pragma·제어 명령(`@set`, `@include` 등) 처리,
  매개변수 바인딩(`SQLScriptContext.fillQueryParameters`), 필터 적용, 문장 준비(`DBUtils.makeStatement`), 시간 제한,
  실행과 연결 끊김 복구(`DBExecUtils.tryExecuteRecover`), 다중 결과 순회(`nextResults`), `DBDDataReceiver`로 행 전달,
  최대 행 수 제한, 영향받은 행 수·경고 수집(`SQLQueryResult`), 트랜잭션(자동 커밋 전환, COMMIT/ROLLBACK 문, 끝날 때 커밋·롤백),
  오류 처리 방식(`SQLScriptErrorHandling`), 통계(`DBCStatistics`)와 통계 결과 집합 생성, 쿼리 이벤트(`SQLQueryListener`).
- **핸들러로 넘긴 일 (분리 전 `SQLQueryJob` 위치):**

  | 핸들러 메서드 | 분리 전 코드 | SWT 구현 | 기본 구현 |
  |---|---|---|---|
  | `getDataReceiver` | `SQLResultsConsumer.getDataReceiver` (:519, :764) | 결과 탭 | 없음(필수) |
  | `getStatisticsDataReceiver` | `IDataTransferConsumer` 제외 (:874-875) | 내보내기면 제외 | `getDataReceiver` |
  | `isSmartAutoCommit` | `DBCSmartTransactionManager` 검사 (:596) | 같음 | false |
  | `confirmQueryExecution` | SWT 확인 대화상자 (:1164-1227, :494-505) | 같은 대화상자 | `DBPPlatformUI.confirmAction` |
  | `handleExecutionError` | `ExecutionQueueErrorJob.showError` (:271) | 같음 | `DBPPlatformUI.showErrorStopRetryIgnore` |
  | `confirmFetchManyResultSets` | 결과 집합이 너무 많을 때 확인 (:732-742) | 같은 문구 | `DBPPlatformUI.confirmAction` |
  | `isIncludedScript` | `IncludedScriptFileEditorInput` 검사 (:376-378) | 같음 | false |
  | `getExecutionController` | `partSite.getPart()` (:690) | 같음 | null |
  | `getStatementTimeout`, `isInvalidateBeforeExecute`, `getMaxResultSetsPerQuery`, `isKeepStatementOpen`, `isShowExecutionResult` | `SQLPreferenceConstants`, `ResultSetPreferences` 읽기 (:564, :702, :732, :867-907, :1069) | 같은 설정 키 | SWT 설정 기본값 |
  | `getDefaultResultSetName`, `getStatisticsResultSetName` | `SQLEditorMessages` (:933, :950, :1025) | 같은 문구 | "Results", "Statistics" |

  줄 번호는 분리 전 `SQLQueryJob.java`(커밋 d57fcea821) 기준이다.
- **SWT 동작:** 바뀌지 않는다. 설정값은 분리 전처럼 쓰는 순간마다 연결의 설정 저장소에서 읽고(단일 쿼리 결과를 새로 고칠 때 같은 Job을 다시 쓰므로),
  대화상자도 같은 것을 띄운다. 공개 메서드와 `STATS_RESULTS`는 상위 클래스에서 물려받아 호출부(`QueryProcessor`, `QueryResultsContainer`, `SQLEditor`)를 고치지 않았다.
  로그 이름도 실제 클래스 이름(`SQLQueryJob`)을 쓰고, Job 상태의 플러그인 ID도 `getStatusPluginId()`로 그대로 둔다.
- **기존 `SQLScriptProcessor`(model.sql)를 쓰지 않은 이유:** 작업(task)의 "SQL 스크립트 실행"용이라 결과 수신기가 하나뿐이고(결과 집합 번호 없음),
  쿼리 이벤트·쿼리별 결과(`SQLQueryResult`)·최대 행 수 제한·확인 대화상자가 없다. 편집기 결과 창에는 부족하다.
- **새 번들을 만들지 않은 이유:** 엔진이 쓰는 것(`SQLScriptContext`, `SQLCommandsRegistry`, `SQLQueryDataContainer`)이 모두 `model.sql`에 있다.
  새 번들은 기능(feature)·제품 구성까지 바꿔야 해서, 기존 번들에 Chromite 패키지(`com.seizia.chromite.model.sql.exec`)를 추가하고 내보냈다.

### Swing 편집기가 호출할 API

```java
// 1. 결과 받을 곳과 UI 동작을 구현한다 (getDataReceiver만 필수)
SQLQueryExecuteHandler handler = new SQLQueryExecuteHandler() {
    public DBDDataReceiver getDataReceiver(SQLQuery query, int resultSetNumber) {
        return resultsPanel.newGridReceiver(query, resultSetNumber);   // 결과 표 하나 = 수신기 하나
    }
};
// 2. 쿼리 이벤트는 SQLQueryListener로 받는다 (Messages 탭)
SQLQueryListener listener = new SQLQueryListener() {
    public void onEndQuery(DBCSession s, SQLQueryResult r, DBCStatistics st) { /* 행 수, 오류, 경고 */ }
    public void onEndScript(DBCStatistics st, boolean hasErrors) { /* 완료 표시 */ }
};
// 3. Job을 만들어 실행한다
SQLQueryExecuteJob job = new SQLQueryExecuteJob(
    "Execute query", executionContext, null, scriptElements, scriptContext, listener, handler);
job.setCommitType(SQLScriptCommitType.NO_COMMIT);           // 선택: 기본값은 SWT 설정 기본값과 같다
job.setErrorHandling(SQLScriptErrorHandling.STOP_ROLLBACK);
job.schedule();                                             // 또는 작업 스레드에서 job.runDirectly(monitor)
```

- 생성자: `SQLQueryExecuteJob(String name, DBCExecutionContext, @Nullable DBSDataContainer, List<SQLScriptElement>, SQLScriptContext, @Nullable SQLQueryListener, SQLQueryExecuteHandler)`.
  `scriptContext`는 `new SQLScriptContext(null, contextProvider, sourceFile, outputWriter, null)`처럼 만든다(SQLEditor와 같은 방식).
- 선택 설정: `setCommitType`, `setErrorHandling`, `setFetchResultSets`, `setResultSetLimit(offset, maxRows)`(기본 최대 행 수는 연결의 `ModelPreferences.RESULT_SET_MAX_ROWS`), `setFetchSize`.
- 취소: `job.cancel()`. 실행 중 문장은 `getCurrentStatement()`로 얻어 서버 출력(`DBCServerOutputReader`)을 읽을 수 있다.
- 핸들러 메서드는 Job 스레드에서 불린다. 화면을 바꾸거나 대화상자를 띄우는 구현은 `SwingUtilities.invokeAndWait`로 EDT에서 처리한다.
  `DBDDataReceiver.fetchRow`도 Job 스레드에서 불리므로 행은 모아 두었다가 `fetchEnd`에서 EDT로 넘긴다.
- 대화상자 기본 구현은 `DBWorkbench.getPlatformUI()`를 쓰므로 `SwingPlatformUI`(DBPPlatformUI 구현)가 있으면 따로 구현하지 않아도 된다.

### F5와 GO 배치를 실행 계층에 연결하는 방법 (Phase B)

- **F5 선택/전체:** 편집기에서 선택 길이가 1자 이상이면 선택 범위, 아니면 전체 텍스트를 `SQLScriptParser.extractScriptQueries`로 나눠
  `List<SQLScriptElement>`를 만들고 위 Job 하나에 넘긴다. 문장이 1개여도 같은 경로를 쓴다(SWT처럼 단일 쿼리용 경로를 따로 두지 않는다).
- **SQL Server `GO` 배치:** SQL Server 방언은 `getScriptDelimiters()`가 `;`와 `GO`, `getBatchDelimiters()`가 `GO`다
  ([SQLServerDialectBase.java:121-129](../../plugins/com.seizia.chromite.ext.mssql/src/com/seizia/chromite/ext/mssql/model/SQLServerDialectBase.java)).
  `extractScriptQueries(..., includeBatchDelimiterElements = true)`로 나누면 `GO` 자리에 `SQLBatchDelimiterElement`가 들어온다.
  Swing 편집기는 두 `GO` 사이의 문장들을 원문 범위 그대로 하나의 `SQLQuery`로 합쳐 Job에 넘긴다
  (C단계에서 `SQLScriptBatches`로 구현, "구현 현황 — C단계").
  엔진은 `SQLQuery` 하나를 JDBC 실행 한 번으로 보내므로 배치 안에서 `DECLARE`한 변수가 유지되고,
  배치가 돌려준 결과 집합마다 `getDataReceiver(query, n)`이 불려 결과 표가 차례로 쌓인다.
  영향받은 행 수는 `SQLQueryResult.getExecuteResults()`, PRINT 출력과 경고는 `SQLQueryResult.getWarnings()`(JDBC `SQLWarning`)로 받아 Messages 탭에 쓴다.
  `SQLBatchDelimiterElement`는 엔진에 넘기지 않는다(엔진은 `SQLQuery`와 제어 명령만 실행한다).
- 배치 문장은 JSQLParser가 하나의 문장으로 해석하지 못하므로 SELECT로 보지 않는다. 그래서 행 수 제한이 쿼리 변환(`TOP`)이 아니라
  가져오기 단계(최대 행 수까지만 읽기)에서만 적용되고, 운영(confirm) 연결에서는 실행 확인을 묻는다. 의도한 동작이다.

### 결과 모델 분석 (`ResultSetModel`, `ResultSetPersister`, 이번에는 옮기지 않음)

| 클래스 | 막는 의존 (파일:줄) | 해결 방향 |
|---|---|---|
| `ResultSetModel` | `SearchQuickFilterInfo`(`com.seizia.chromite.ui` 번들, :39, :74, :109-119) — 클래스 자체는 SWT 없음 | 모델 번들로 옮기거나 `Predicate<ResultSetRow>`로 바꾼다 |
| | `ResultSetRowColorHelper`(:101, :104, :818, :822, :852, :1207) — SWT `Color`/`RGB`, `UIUtils` 사용 | 색상 계산을 결과 모델 밖(표시 계층)으로 옮긴다 |
| | `ResultSetRow.ColorInfo`(ResultSetRow.java:19, :41-49) — SWT `Color` 필드 | 색상을 문자열/RGB 값으로 저장하고 표시 계층이 변환한다 |
| | `ResultSetPreferences` 상수(:174, :1227), `ResultSetCellLocation` | SWT 없음. 함께 옮기면 된다 |
| `ResultSetPersister` | `ResultSetViewer`(:128-137, :174-215, :409-423, :481-547, :572-611, :722-811) — 다시 그리기, 상태 표시, 데이터 읽기 잠금 | 저장 결과를 콜백 인터페이스로 알리고, 화면 갱신은 UI가 한다 |
| | `UIUtils.asyncExec/syncExec/runInProgressService`(:106, :403, :567, :778), `UITask`(:523) | 콜백 인터페이스로 바꾼다 |
| | `ValidateUniqueKeyUsageDialog`(:523), `ConfirmationDialog`(:593-596), `ResultSetMessages`·`NLS`(:41, :577-584) | 키 확인·0행 경고를 콜백으로 바꾼다 |
| | `ResultSetJobAbstract`, `ResultSetExecutionSource`(:217, :703-710) | UI 번들 클래스. 행 새로 고침 Job을 모델 쪽 Job으로 바꾼다 |
| | `IStatus`/`Status`(:19-20) | Eclipse 런타임이라 문제 없음 |

- 저장 핵심 로직(INSERT/UPDATE/DELETE 생성, 키 판별, 트랜잭션)은 이미 모델 계층의 `DBDResultSetDataUpdater`, `DataUpdaterJob`
  (`model/data/resultset`)에 있다. `ResultSetPersister`는 그 위에 화면 갱신을 붙인 얇은 층이다.
- **Swing 그리드 계획 (Phase B 이후):**
  1. 첫 단계는 `ResultSetModel`을 옮기지 않는다. Swing 그리드는 자체 `DBDDataReceiver`로 행(`Object[]`)과 `DBDAttributeBinding`을 받아 `AbstractTableModel`로 보여 준다(조회 전용 MVP).
  2. 편집·저장(Edit Top 200 Rows)이 필요해지면 위 표의 의존을 끊어 `ResultSetModel`·`ResultSetRow`를 모델 번들로 옮기고,
     `ResultSetPersister`의 `ResultSetViewer` 호출을 콜백 인터페이스로 바꾼다. SWT 결과 뷰어는 그 인터페이스를 구현한다.
  3. 저장은 `DBDResultSetDataUpdater`를 그대로 쓴다.
  - G단계에서 2번 대신 한 것: 저장 계약이 이미 모델 계층의 인터페이스·추상 클래스로 나뉘어 있어 SWT 클래스를 옮기지 않고 Swing 번들에서 구현·상속했다("구현 현황 — G단계").

## 위험과 주의 사항

- **연결 설정 화면:** 각 DB의 전용 연결 화면은 SWT로 된 `ext.*.ui` 플러그인에 있어 재사용할 수 없다. MVP는 범용 화면으로 대신한다.
  드라이버별 특수 설정이 범용 화면에서 되는 것과 안 되는 것은 "구현 현황 — D단계"의 "범용 화면에서 아직 못 하는 드라이버별 설정"에 정리했다.
- **결과 편집 저장:** 키 판별, 트랜잭션, 오류 처리가 필요해 MVP에서 가장 신경 쓸 부분이다.
- **기능 공백:** SWT 버전에 있던 기능 중 Swing으로 아직 만들지 않은 것은 SWT 삭제와 함께 없어졌다. 목록은 [swt-removal.md](swt-removal.md) "Swing으로 옮길 때까지 없는 기능".
- **디자인 자산:** JetBrains와 Microsoft의 아이콘, 로고 등 고유 디자인 자산은 복사하지 않는다.
  배치와 사용 흐름, 색감만 참고하고, 아이콘은 직접 그리거나 공개 라이선스 아이콘 세트를 쓴다.
- **아이콘 규모:** 기존 플러그인 아이콘은 약 1,060개다. 자주 보이는 것부터 단계적으로 만든다.

## 구현 현황 — A단계

A단계의 목표는 SWT 없이 모델 계층을 띄우고 빈 메인 창을 여는 배관 작업이다.
SSMS 배치, Object Explorer 등 실제 화면은 B단계에서 만든다.

### 식별자

새 Chromite 코드는 Java 패키지와 OSGi ID 모두 `com.seizia.chromite` 접두어를 쓴다(2026-11-06 결정). DBeaver에서 온 코드도 2026-11-07 같은 접두어로 바꿨다([internal-rename.md](internal-rename.md)).

| 구분 | ID / 위치 |
|---|---|
| 번들 | `com.seizia.chromite.ui.swing` ([plugins/com.seizia.chromite.ui.swing](../../plugins/com.seizia.chromite.ui.swing)), 패키지 `com.seizia.chromite.ui.swing` |
| Equinox 애플리케이션 | `com.seizia.chromite.ui.swing.application` |
| 제품 확장 | `com.seizia.chromite.ui.swing.product` (`org.eclipse.core.runtime.products`) |
| `com.seizia.chromite.application` 확장 | `chromite-swing` (이름 `Chromite`, `GeneralUtils.getProductName()`이 읽음) |
| 피처 | `com.seizia.chromite.swing.feature` ([features/com.seizia.chromite.swing.feature](../../features/com.seizia.chromite.swing.feature)). 공개판 내장 드라이버 피처 `com.seizia.chromite.drivers.public.feature`를 포함한다([drivers.md](drivers.md)). 설치 폴더의 `licenses/`(`docs/root`)도 이 피처가 넣는다 |
| 제품 정의 | uid `com.seizia.chromite.swing.product`, [product/swing/chromite-swing.product](../../product/swing/chromite-swing.product) |
| 실행 파일 | `chromite-swing.exe` / `ChromiteSwing.app` (SWT 제품의 `chromite`와 나누려고 붙인 이름. SWT 삭제 뒤에도 서명·릴리스 파일 이름이 이 이름을 써서 유지) |
| Maven 프로파일 | `product-chromite-swing` ([product/pom.xml](../../product/pom.xml)). Windows x64, macOS arm64만 빌드 |
| Maven groupId | `com.seizia.chromite` (번들, 피처, 제품 모듈) |

번들과 피처는 `plugins/pom.xml`, `features/pom.xml`의 `desktop` 프로파일(헤드리스 빌드가 아닐 때 기본 활성)에 넣었다.

```bash
mvn package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing
```

결과물: `product/swing/target/products/com.seizia.chromite.swing.product/win32/win32/x86_64/chromite-swing/`,
`.../macosx/cocoa/aarch64/ChromiteSwing.app/`

### 시작 흐름

헤드리스 앱([com.seizia.chromite.headless](../../plugins/com.seizia.chromite.headless))과 같은 구조로 만들었다.

| 클래스 | 역할 |
|---|---|
| `ChromiteSwingActivator` (`Plugin`) | 제품 번들. `ModelPreferences.setMainBundle()`, 설정 저장소, 상태 폴더(인증서 저장소) 제공 |
| `ChromiteSwingApplication` (`DesktopApplicationImpl`) | Equinox `IApplication` 진입점 |
| `ChromiteSwingPlatform` (`BasePlatformImpl`, `DBPPlatformDesktop`) | `DesktopPlatform`(SWT)의 초기화 순서를 UI 의존 없이 재구현 |
| `SwingPlatformUI` (`DBPPlatformUI`) | 모델 계층의 UI 요청을 Swing 대화상자로 처리 |
| `MainFrame` (`JFrame`) | 메인 창. File > Exit 메뉴, 상태 표시줄 |
| `ChromiteIcons` | 창 아이콘(여러 크기) |

1. Equinox가 `ChromiteSwingApplication.start()`를 애플리케이션 스레드(main)에서 호출한다.
2. EDT에서 FlatLaf(`FlatLightLaf.setup()`)를 적용한다.
3. 작업 공간을 잠근다. `-data`가 없으면 SWT 버전과 같은 기본 작업 공간(`ChromiteData/workspace6`)을 쓴다(현재 폴더와 무관).
   잠글 수 없으면 오류 대화상자를 띄우고 끝낸다. `-data`가 없었으면 위치를 고치기 전 빌드의 작업 공간을 한 번 복사하고, 그 뒤에 처음 설정 저장소를
   만든다(`ChromiteSwingActivator.initPreferences`, [workspace-location.md](workspace-location.md)).
4. `initializeApplicationServices()` → 디버그 로그(`.metadata/dbeaver-debug.log`, 표준 출력/오류를 함께 기록) →
   `DBWorkbench.getPlatform()`으로 플랫폼을 초기화한다(인증서 저장소 → `DesktopWorkspaceImpl` → 프로젝트 → Query Manager → 탐색기 모델).
   작업 공간 형식, 기본 프로젝트 이름(`General`), 탐색기 모델(`DesktopNavigatorModel`)은 SWT 버전과 같아서 연결 목록을 공유한다.
5. `postInitialize()`로 플러그인 서비스와 연결 감시 Job을 시작한다(SWT에서는 워크벤치 창 어드바이저가 호출).
6. 작업 공간의 모든 프로젝트에서 연결 수를 세어 상태 표시줄 문구(`N connections in workspace`)를 만든다.
   모델 계층이 정상으로 떴는지 확인하는 용도다. (D단계부터 계산은 `MainFrame.describeWorkspace`에 있고, 연결을 추가·삭제하면 다시 센다.)
7. EDT에서 `MainFrame`을 열고, 창이 닫힐 때까지 애플리케이션 스레드가 기다린다(`start()`가 반환되면 Equinox가 종료되기 때문).
8. 창을 닫거나 File > Exit를 누르면 창이 dispose되고, 애플리케이션 스레드가 작업 공간을 저장하고 플랫폼을 정리한 뒤 반환한다.
   `IApplication.stop()`(외부 종료 요청)은 창을 닫아 같은 경로로 끝낸다.

제품 설정에서 `org.eclipse.core.runtime`을 시작 수준 4로 자동 시작한다. SWT 제품에서는 워크벤치 번들이 시작시키지만
Swing 제품에는 그런 번들이 없어서, 빼면 `Unable to acquire application service` 오류로 실행되지 않는다(2026-11-06 확인).

SWT 버전의 `DesktopPreferencesInitializer`(`com.seizia.chromite.core`)가 정하던 기본 설정은 Swing 제품에 없다.
A단계에서는 디버그 로그 순환 한도(`logs.files.output.maxSize`, `logs.files.output.maxCount`)만 같은 값으로 정한다.
이 값이 없으면 한도가 0이 되어 로그가 쓸 때마다 순환되어 사라진다(2026-11-06 확인).

### DBPPlatformUI 구현 현황

모든 대화상자는 EDT에서 띄운다. 호출 스레드가 EDT가 아니면 `SwingUtilities.invokeAndWait`로 결과를 기다린다.

| 메서드 | A단계 구현 | B단계에서 할 일 |
|---|---|---|
| `showError` (3종) | `JOptionPane` 오류 대화상자. 상태·예외 메시지를 줄바꿈되는 텍스트로 표시 | DB 오류별 처리(연결 끊김, 드라이버 파일 없음), 상세 정보 창 |
| `showMessageBox`, `showWarningMessageBox` | `JOptionPane` 정보·오류·경고 대화상자 | |
| `confirmAction` (3종) | 예/아니오 또는 지정 버튼/취소. 경고일 때 기본 버튼은 취소 | |
| `showUserChoice` | 선택 버튼 + "모두 적용" 체크박스(서로 배타) | |
| `showErrorStopRetryIgnore` | Stop/Retry(+Skip/Skip All), SWT `ExecutionQueueErrorDialog`와 같은 응답 매핑 | |
| `promptUserCredentials` (2종) | 사용자/비밀번호/비밀번호 저장 입력 대화상자 | |
| `promptUserPasswordChange` | 사용자/이전 비밀번호/새 비밀번호/확인 입력, 불일치 시 다시 묻기 | |
| `promptProperty` (2종), `promptText` | 한 줄/여러 줄 입력 대화상자 | |
| `acceptLicense` | 라이선스 본문 + Accept/Decline | |
| `executeInMainThread` | EDT에서 동기 실행(SWT의 `syncExec`와 같음) | |
| `executeProcess` | 콘솔 구현과 같음(실패 시 오류 대화상자) | |
| `copyTextToClipboard` | 시스템 클립보드에 일반 텍스트 | HTML 형식 |
| `executeShellProgram` | `Desktop.open`(파일) / `Desktop.browse`(URL) | |
| `showInSystemExplorer` | Windows `explorer /select,`, macOS `open -R` | |
| `openFileSystemSelector` | SWT `DesktopUI`와 같이 "지원하지 않음" 메시지 후 `null` | |
| `showNotification`, `showWarningNotification` | **로그만 남김** | 모달이 아닌 알림 팝업(피드백 동작 포함) |
| `selectObject` | **`null` 반환** | 탐색기 트리 선택 대화상자 |
| `openEntityEditor` (2종) | **아무것도 하지 않음** | 객체 편집기(속성, DDL) |
| `openConnectionEditor` | **아무것도 하지 않음** | 범용 연결 화면(MVP 2번) |
| `executeWithProgressBlocking` | **호출 스레드에서 실행, 진행 표시 없음** | Job + 진행 표시 |
| `runWithMonitor`, `runWithProgress` | **호출 스레드에서 실행, 진행 표시 없음** | 진행 대화상자 |
| `createLoadingService` | **콘솔 구현과 같음(Job 스레드에서 완료 통지)** | Swing 로딩 표시, EDT에서 완료 통지 |
| `readAndDispatchEvents` | **`false`** | EDT에서 기다릴 때 `SecondaryLoop` 사용 검토 |
| `promptAuthModelCredentials` (기본 메서드) | **인터페이스 기본값 `false`** | 인증 모델별 입력 화면 |

코드에서는 B단계 항목을 `// Phase B:` 주석으로 표시했다.

### 외부 라이브러리 연결 방식

| 라이브러리 | 버전 | 라이선스 | 출처 |
|---|---|---|---|
| FlatLaf (`com.formdev:flatlaf`) | 3.8 | Apache 2.0 | Maven Central. SHA-1 `5e06f19b28ef1fa1543fa99ac632903aa2cede6b`, SHA-256 `8d8489dee6b9512a669be7670fd2d010f6a7dcfb23b622329ce3c61f8529e76b` 확인 |

- FlatLaf jar에는 OSGi 매니페스트가 없다(`Bundle-SymbolicName` 없음). 그래서 번들 안에 넣고 `Bundle-ClassPath: ., lib/flatlaf.jar`로 쓴다.
  이 방식은 기존 `com.seizia.chromite.core`의 `lib/awt.injector-1.0.0.jar`와 같다.
- 바이너리를 커밋하지 않는다. 번들 `pom.xml`의 `maven-dependency-plugin:copy`가 `generate-resources` 단계(컴파일 전)에
  Maven Central에서 받아 `lib/flatlaf.jar`로 복사한다. 버전은 `pom.xml`의 `flatlaf.version` 한 곳에만 있다.
  `lib/`는 번들의 `.gitignore`로 제외한다.
- 다른 방법(`pomDependencies`의 `wrapAsBundle`)은 루트 POM의 공유 빌드 설정과 피처·제품 모듈까지 바꿔야 해서 쓰지 않았다.
- FlatLaf의 네이티브 라이브러리(Windows 창 장식 등)는 jar 안에 들어 있고, FlatLaf가 실행 중에 임시 폴더로 풀어서 쓴다.
- `NOTICE`에 출처를 적었다. 라이브러리를 추가하면 이 표와 `NOTICE`를 함께 갱신한다.

### 창 아이콘과 JVM 실행 방식

JBR/JetBrains 브랜드가 화면에 보이지 않게 한다.

- **창 아이콘:** 모든 최상위 창에 Chromite 아이콘을 지정한다(`ChromiteIcons`, `Window.setIconImages`).
  크기는 16, 20, 24, 32, 40, 48, 64, 128, 256이다(번들의 `icons/chromite<크기>.png`).
  `tools/branding/gen_app_icons.py`가 크기마다 그 크기의 픽셀 격자에 맞춰 그린다([branding.md](branding.md) "아이콘").
  지정하지 않으면 JBR의 기본 아이콘(JetBrains "JB")이 제목 표시줄과 작업 표시줄에 나온다.
- **대화상자:** `SwingPlatformUI`는 활성 창(없으면 보이는 프레임)을 부모로 넘겨 대화상자가 그 아이콘을 물려받는다.
  부모 없이 띄우는 대화상자(시작 오류 등)는 Swing의 공유 소유 프레임(`JOptionPane.getRootFrame()`)이 소유하므로,
  시작할 때 그 프레임에도 아이콘을 지정한다.
- **JVM 실행 방식 (Windows):** Equinox 런처는 `-vm`에 따라 JVM을 다르게 띄운다(2026-11-06 확인).
  - `-vm ...\bin\javaw.exe`: 런처(`chromite-swing.exe`)가 별도 `javaw.exe` 프로세스를 띄우고 창은 그 프로세스에 속한다.
    `javaw.exe`의 실행 파일 아이콘은 JetBrains 아이콘이고, 작업 표시줄 그룹·고정도 `javaw.exe` 기준이 된다.
    창 아이콘을 지정하기 전에는 작업 표시줄에 "JB" 아이콘이 보였다.
  - `-vm ...\bin\server\jvm.dll` 또는 런처 옆 `jre/` 폴더(내장 런타임): 런처가 JVM을 자기 프로세스 안에 올린다.
    프로세스는 Chromite 아이콘의 `chromite-swing.exe` 하나다. 내장 런타임 배포([runtime.md](runtime.md))에서는 이 방식이 된다.
- **AppUserModelID:** 보류. 지정하려면 JNA로 `SetCurrentProcessExplicitAppUserModelID`를 부를 수 있지만, 명시적 ID를 쓰면
  같은 ID와 재실행 명령(`System.AppUserModel.RelaunchCommand`)을 가진 바로가기가 있어야 작업 표시줄 고정이 제대로 동작한다.
  설치 프로그램을 만들 때(런타임 내장 단계) 바로가기와 함께 정한다. 그 전까지는 in-process 실행으로 `chromite-swing.exe` 기준 그룹이 된다.

### Java 버전 결정

- 새 번들은 `Bundle-RequiredExecutionEnvironment: JavaSE-25`이고, 번들 `pom.xml`에서 `java.version`을 25로 바꾼다.
  공유 설정(`deps/dbeaver-common/root/pom.xml`의 `java.version` 21)과 루트 `pom.xml`의 `tycho-compiler-plugin` 설정은
  `${java.version}`을 참조하므로, 이 번들에서만 25로 컴파일된다. `deps/`와 공유 빌드 설정은 고치지 않았다.
- 확인 결과(2026-11-06, Tycho 5.0.4, JBR 25.0.4): 빌드 로그에 이 번들의 컴파일이 `javac [debug release 25]`로 나오고,
  만든 클래스 파일의 major 버전이 69(Java 25)다. 피처·제품 단계의 의존성 해석에도 문제가 없었다.
- 제품 실행 옵션은 `-Dosgi.requiredJavaVersion=25`다. 나머지 JVM 옵션은 SWT 제품과 같고,
  macOS의 `-XstartOnFirstThread`(SWT용, AWT를 막음)는 넣지 않았다.

### SWT 미포함 확인 (2026-11-06)

Windows x64 결과물의 `plugins/`에는 번들 128개가 있고, `swt`, `jface`, `org.eclipse.ui`, `org.eclipse.e4.ui`가 들어간 이름은 0개다
(macOS arm64 결과물은 129개, 같은 검사 0개). 런타임 피처(`com.seizia.chromite.runtime.feature`)와 DB 피처(`com.seizia.chromite.db.feature`)를
그대로 포함하고, Equinox 번들은 필요한 것만 직접 넣었다.

### A단계에서 남긴 것 (B단계 이후)

- 작업 공간 선택, 잠긴 작업 공간 무시하고 열기, 이전 버전 작업 공간 이전, 인스턴스 서버, CLI 명령 처리(SWT 앱의 `ChromiteApplication`에 있음)
- SWT `DesktopPreferencesInitializer`의 나머지 기본 설정(Query Manager 기록 기간 등). Swing용 기본 설정 초기화가 필요하다.
- 설정 저장 위치: 설정은 이 번들 ID(`com.seizia.chromite.ui.swing`) 기준으로 저장된다(SWT 버전은 자기 제품 번들 기준이라 공유되지 않았다).
- 테마 전환(DataGrip풍 다크), macOS 메뉴 막대 통합, Windows AppUserModelID(위 "창 아이콘과 JVM 실행 방식")
- macOS arm64 실행은 확인하지 못했다(빌드 결과만 확인). AWT는 Equinox 런처가 만든 보조 스레드에서 시작된다.

## 구현 현황 — B단계

B단계의 목표는 SSMS식 메인 창, 실제 데이터를 보여 주는 Object Explorer, 라이트/다크 테마다.
SQL 편집기, 결과 그리드, 연결 화면은 C단계에서 만든다.

### 화면 배치

```
┌ 메뉴(File · View · Help, FlatLaf 통합 제목 표시줄 안) ─────────────────────────┐
│ Object Explorer │ 문서 탭 (Welcome)                        │ Properties      │
│  [Refresh] [×]  │                                          │  [×]            │
│  연결            ├──────────────────────────────────────────┤  이름 / 종류     │
│   └ Tables      │ Results | Messages                  [×] │  속성 표         │
│      └ 테이블    │                                          │                 │
├─────────────────┴──────────────────────────────────────────┴─────────────────┤
│ 상태 표시줄 (N connections in workspace)                                       │
└──────────────────────────────────────────────────────────────────────────────┘
```

| 클래스 | 역할 |
|---|---|
| `MainFrame` | 메인 창. 분할 창 3개, 메뉴, 상태 표시줄, 창·도구 창 배치 저장, 알림 표시 |
| `ToolWindow` | 도구 창 하나(분할 창의 한쪽). 머리말(제목, 숨기기 아이콘 `—`. 디자인 적용 전에는 도구 단추와 `×`), View 메뉴 토글, 크기·표시 여부 저장 |
| `ObjectExplorerPanel` | 탐색기 트리 |
| `PropertiesPanel` | 선택한 노드의 이름·종류·설명과 속성 표 |
| `NodeIcons` | 노드 아이콘(`DBPImage`) → Swing `Icon` |
| `ChromiteTheme`, `ChromiteLightLaf`, `ChromiteDarkLaf` | 테마와 그 저장 |
| `AboutDialog` | Help > About Chromite |
| `ProgressDialog`, `NotificationToast` | `DBPPlatformUI`의 진행 대화상자, 비모달 알림 |
| `SwingConnectionService` | `DBServiceConnections` 구현(탐색기에서 연결 열기) |
| `JsvgLogManager` | JSVG 로깅 공급자(JSVG 번들이 필수로 요구하는 능력, 아래 "외부 라이브러리") |

- **분할 구조:** Object Explorer | ((문서 탭 / Results·Messages) | Properties). Properties는 SSMS처럼 문서와 결과 옆에 세로로 길게 붙는다.
  창을 키우면 늘어난 공간은 문서 영역이 가져가고 도구 창 크기는 그대로다(오른쪽·아래 분할 창의 `resizeWeight = 1`).
- **숨기기:** View 메뉴 토글(Object Explorer F8, Properties F4, Results Ctrl+R, SSMS와 같은 단축키) 또는 도구 창의 `×`.
  숨긴 컴포넌트는 분할 창에서 빼지 않고 `setVisible(false)`로 감추고 구분선 크기를 0으로 둔다(테마 전환이 숨은 창에도 적용되게).
- **Results / Messages:** 지금은 자리 표시용 내용만 있다. 탭 줄 오른쪽에 숨기기 단추가 있다(FlatLaf `JTabbedPane.trailingComponent`).
- **문서 탭:** Welcome 탭 하나. SQL 편집기 탭은 C단계에서 넣는다. (2026-11-06 Welcome 탭을 없애고 빈 문서 영역으로 바꿈: "빈 문서 영역")
- **메뉴:** File(Exit), View(도구 창 3개, Theme > Light/Dark), Help(About Chromite). F5는 쿼리 실행용으로 비워 둔다.
- **About:** 제품명, 부제, 버전, 저작권("© 2026 Chromite contributors. Based on DBeaver Community © DBeaver Corp"), 웹사이트
  (https://github.com/froiibn/chromite, 누르면 브라우저로 연다). 제품명·부제·저작권은 `plugin.xml` 제품 확장의 속성에서 읽는다.
  버전은 SWT 버전과 같이 제품 번들 버전(`GeneralUtils.getPlainVersion()`)이라, Swing 번들 버전을 제품 버전과 같은 `26.2.5`로 바꿨다
  (SWT의 제품 번들 `com.seizia.chromite.ui.app.standalone`과 같은 방식. 이전 `1.0.0`은 About에 그대로 나왔다).

### 도킹 라이브러리 검토 (ModernDocking 대 `JSplitPane`)

B단계는 `JSplitPane`만 쓴다. (2026-11-06 사용자가 끌어서 옮기기·떠 있는 창을 요청해 ModernDocking으로 바꿨다: "도구 창 도킹". 아래는 B단계 당시 판단이다.)

- B단계 배치는 SSMS의 기본 배치 하나(고정 위치의 도구 창 3개 + 문서 탭)이고, 필요한 동작은 크기 조절·숨기기·저장뿐이라 분할 창으로 충분하다.
- ModernDocking(MIT)을 쓰면 OSGi 매니페스트 없는 jar를 FlatLaf처럼 빌드 때 받아 `Bundle-ClassPath`에 넣고, `NOTICE`·체크섬을 관리하고,
  라이브러리 자체의 배치 저장 형식(XML)을 따라야 한다. 지금 얻는 것(끌어다 놓기, 떠 있는 창, 자동 숨김)에 비해 의존성과 테마·포커스 문제를 살필 범위가 크다.
- 도구 창은 `ToolWindow` 하나로 감싸 두었으므로, 끌어다 놓기나 떠 있는 창이 필요해지면 `ToolWindow`와 `MainFrame`의 배치 부분만 도킹 라이브러리로 바꾸면 된다.

### Object Explorer 데이터 흐름과 스레드

1. 트리 뿌리(보이지 않음)는 활성 프로젝트의 `DBNProject`다. 첫 단계에 `DBNProject.getDatabases()`의 자식(연결 폴더 `DBNLocalFolder`, 연결 `DBNDataSource`)을 읽는다.
   탐색기 모델은 SWT 버전과 같은 `DBWorkbench.getPlatform().getNavigatorModel()`(`DesktopNavigatorModel`)이다.
2. 자식이 있을 수 있는 노드(메타데이터만 보고 DB를 읽지 않음)에는 "Loading…" 자식을 미리 넣어 펼칠 수 있게 한다.
   SWT 트리와 달리 탐색 불가 항목도 자식으로 친다("Object Explorer 후속 수정" 절).
3. 노드를 펼치면(`TreeWillExpandListener`) `AbstractJob`을 예약하고, Job 스레드에서 `DBNNode.getChildren`을 부른 뒤 숨은 객체를 빼고 정렬한다.
   EDT에서는 모델을 읽지 않는다.
4. 결과는 `SwingUtilities.invokeLater`로 EDT에서 트리 모델에 반영한다(`nodeStructureChanged`, 펼친 상태 유지). 노드마다 읽기 번호를 두어
   새로 고침으로 더 새 읽기가 시작되었으면 늦게 끝난 이전 결과를 버린다.
5. **연결:** 연결 노드의 자식을 읽으면 `DBNDatabaseNode.getChildren` → `initializeNode` → `DBUtils.initDataSource` →
   `DBWorkbench.getService(DBServiceConnections.class).initConnection(monitor, …)`로 연결한다
   ([DBNDatabaseNode.java:221-243](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/model/navigator/DBNDatabaseNode.java),
   [DBUtils.java:2792-2808](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/model/DBUtils.java)).
   SWT 제품에서는 `com.seizia.chromite.core`의 `UIServiceConnectionsImpl`이 이 서비스인데 Swing 제품에는 없어서, 서비스가 없으면
   연결하지 않고 "Connection was canceled"로 끝난다. 그래서 `SwingConnectionService`를 `com.seizia.chromite.service` 확장으로 등록했다.
   SWT `DataSourceHandler.connectToDataSource`의 UI 없는 부분(이미 연결 중이면 그 Job을 기다림, 모델 계층의 `ConnectJob`을 탐색기 Job 스레드에서 `runSync`)을 옮겼다.
   공유 자격 증명 확인(팀/클라우드 기능)과 기능 사용 통계는 옮기지 않았다.
6. **오류:** Job에서 난 예외는 `DBWorkbench.getPlatformUI().showError`(`SwingPlatformUI`)로 보여 주고, 노드를 접고 "Loading…" 자식을 되돌려 다시 펼치면 다시 읽는다.
   연결 실패·취소(`DBInterruptedException`)는 연결 서비스가 이미 알렸으므로 다시 알리지 않는다.
7. **새로 고침:** F5는 쿼리 실행용이라 쓰지 않고, Object Explorer 머리말의 Refresh 단추와 오른쪽 클릭 메뉴 "Refresh"로 한다.
   선택한 노드(없으면 연결 목록 전체)에 `DBNNode.refreshNode(monitor, …)`를 부른 뒤 자식을 다시 읽는다. 새로 고침이 조상 노드로 넘어가면 그 조상의 하위 트리를 다시 그린다.
   아직 펼친 적 없는 노드는 자식을 읽으면 연결이 열리므로 다시 그리기만 한다.
8. **선택:** 선택이 바뀌면 Properties에 노드를 넘긴다. 더블클릭은 Swing 트리의 기본 동작(펼치기·접기)만 하고, 객체 편집기는 C단계 이후다.

### Properties

- 머리말에 이름(`getNodeDisplayName`), 종류(`getNodeTypeLabel`), 설명(`getNodeDescription`)을 보여 준다.
- 속성 표는 SWT 속성 보기와 같은 모델을 쓴다(`NavigatorAdapterFactory`의 변환): 노드가 감싼 객체(`DBSWrapper.getObject()`, 아니면 노드)가
  `DBPPropertySource`면 그것을, 아니면 `PropertyCollector`로 `@Property` 게터를 모은다. 둘 다 `com.seizia.chromite.model`에 있고 UI에 의존하지 않아 그대로 쓸 수 있었다.
- 값 읽기는 게으른 속성(DB 조회가 필요한 값)을 포함하므로 백그라운드 Job에서 모니터를 넘겨 읽는다. 선택이 바뀌면 이전 Job을 취소한다.
  범주가 있는 속성은 범주 이름 줄(굵게) 아래에 모은다. 읽기 전용이다.

### 노드 아이콘

- `DBNNode.getNodeIcon()`은 `DBIcon`이고 위치는 `platform:/plugin/<번들>/<경로>`다. `Bundle.getEntry`로 번들 안의 파일을 직접 찾는다(SWT `SwtIcons`와 같은 위치 규칙).
- DBeaver 트리 아이콘은 대부분 SVG다(예: `com.seizia.chromite.model/icons/tree`에 SVG 73개, PNG 2개). 그래서 PNG만 읽는 방식으로는 부족하고,
  SVG는 JSVG로 읽어 그릴 때마다 벡터로 그린다(HiDPI에서도 선명). PNG는 `@2x` 파일이 있으면 `BaseMultiResolutionImage`로 묶는다.
- 상태 겹침 아이콘(`DBIconComposite`, 예: 연결됨 표시)은 기본 아이콘 네 모서리에 겹쳐 그린다(SWT `OverlayImageDescriptor`와 같음).
- 읽을 수 없는 아이콘은 `DBIcon.TYPE_UNKNOWN`, 그것도 없으면 Look and Feel의 트리 잎 아이콘으로 대신한다. 위치별로 캐시한다(실패도 캐시).
- 아이콘 파일은 DBeaver 번들의 것(Apache 2.0)을 그대로 쓴다. JetBrains·Microsoft 아이콘은 쓰지 않는다.
- (디자인 적용, 2026-11-06) 자주 보는 모델 아이콘은 Chromite 아이콘 팩으로 바꿔 그리고, 연결의 PNG 겹침 아이콘 대신 상태 점을 쓴다. 아래 "디자인 적용"과
  [design-spec.md](design-spec.md) "적용 현황 — 아이콘 팩"을 본다.

### 테마

| 테마 | 클래스 | 바탕 | 바꾼 값 (`<클래스>.properties`) |
|---|---|---|---|
| Light (DataGrip New UI Light) | `ChromiteLightLaf` | `FlatLightLaf` | 디자인 토큰 전체(색, 치수, 글꼴). B단계에는 SSMS풍 강조색 `#0078d4`, 바탕 `#f3f3f3`만 바꿨다 |
| Dark (DataGrip New UI Dark) | `ChromiteDarkLaf` | `FlatDarkLaf` | 디자인 토큰 전체. B단계에는 `FlatDarculaLaf` 기반에 바탕·글자·강조색만 바꿨다 |

- 2026-11-06 디자인 적용으로 두 테마 모두 DataGrip New UI 값을 쓴다. 값과 FlatLaf 키는 [design-spec.md](design-spec.md) "디자인 토큰", 적용 결과는 같은 문서 "적용 현황"에 있다.
- FlatLaf는 Look and Feel 클래스 계층마다 "클래스 이름.properties"를 읽어 덮어쓴다. 그래서 외부 테마 파일 없이 색 변수 몇 개만 바꿨다.
  JetBrains·Microsoft의 테마 파일과 아이콘은 쓰지 않고 색감만 참고했다.
- View > Theme에서 바꾸면 바로 `FlatLaf.setup()` + `FlatLaf.updateUI()`로 열린 모든 창을 다시 그리고, 설정에 저장해 다음 시작에도 쓴다.
  Look and Feel 색을 쓰는 직접 만든 테두리(상태 표시줄, 도구 창 머리말)는 `updateUI()`에서 다시 만든다.
- 스크롤바 트랙은 스크롤하는 내용(뷰)의 바탕색으로 칠한다(`ChromiteScrollBarUI`). 편집기는 트리 갱신이 끝난 뒤 테마 색을 다시 적용하므로,
  스크롤바 UI가 뷰·뷰포트의 `background`·`opaque` 변경을 듣고 스크롤바를 다시 그린다(2026-11-07, 그 전에는 트랙이 옛 테마 색으로 남았다).
- 시작 순서: 작업 공간을 잠그기 전에는 설정을 읽을 수 없으므로(인스턴스 위치가 정해지기 전) 시작 오류 대화상자용으로 라이트 테마를 먼저 설치하고,
  메인 창을 열기 직전에 저장된 테마로 바꾼다. 저장된 밀도(View > Compact Mode)가 기본(B)과 다를 때도 이때 다시 설치한다.
- View > Compact Mode(2026-11-08): 테마와 따로 저장하는 밀도 전환이다. 테마를 다시 설치하는 같은 길로 바로 바뀐다(다시 시작하지 않음).
  값과 구현은 [design-spec.md](design-spec.md) "적용 현황 — 촘촘한 모드".

### 설정 저장 위치

테마, 창 위치·크기·최대화 여부, 도구 창 크기·표시 여부는 이 번들의 설정 저장소(`BundlePreferenceStore`, 작업 공간
`.metadata/.plugins/org.eclipse.core.runtime/.settings/com.seizia.chromite.ui.swing.prefs`)에 저장한다.

| 키 | 값 |
|---|---|
| `ui.theme` | `LIGHT` / `DARK` |
| `ui.iconStyle` | `DUOTONE`(기본) / `COLOR` (디자인 적용) |
| `sqlEditor.font.family`, `sqlEditor.font.size` | 편집기 글꼴 이름(없으면·설치되지 않았으면 Cascadia Mono), 크기(기본 13) (2026-11-06) |
| `sqlEditor.color.<light\|dark>.<항목>` | 바꾼 편집기 색만: `rrggbb[,bold][,italic]`. 항목은 `text`, `keyword`, `operator`, `function`, `string`, `number`, `comment`, `data_type`, `variable`, `background`, `current_line`, `selection`, `line_number`, `current_line_number` (2026-11-06) |
| `mainWindow.bounds`, `mainWindow.maximized` | `x,y,너비,높이`(최대화 전 크기), 최대화 여부 |
| `results.size` | Results 높이. 도구 창 배치는 2026-11-06부터 배치 파일과 `<id>.restorePlace`("도구 창 도킹"). 표시 여부(`results.visible`)는 2026-11-07부터 저장하지 않는다("Results 자동 숨김") |

- Java Preferences 대신 이 저장소를 고른 이유: SWT 버전도 화면 설정을 작업 공간 설정에 둔다. Windows에서 Java Preferences는 레지스트리에 쓰므로
  작업 공간을 복사해 하는 스모크 테스트가 사용자 설정을 바꾸게 되고, 작업 공간과 함께 옮겨지지도 않는다. 기본 작업 공간은 사용자별 폴더에 있어 "사용자별 저장"도 된다.
- 창 배치는 창을 닫을 때 한 번 저장하고, 테마는 바꿀 때 바로 저장한다. 저장된 창 위치가 어느 화면에도 없으면(모니터 분리 등) 기본 크기로 가운데에 연다.
- 이 저장소는 기본값과 같은 값을 저장하지 않고 지운다(`BundlePreferenceStore.putBoolean` 등). 그래서 도구 창의 기본값(표시함, 기본 크기)을
  `setDefault`로 먼저 선언한 뒤 읽는다. 선언하지 않으면 숨김(`false`)이 저장되지 않아 다음 시작에 다시 보였다(2026-11-06 스모크 테스트에서 발견).

### DBPPlatformUI B단계 구현

| 메서드 | B단계 구현 |
|---|---|
| `runWithProgress`, `runWithMonitor` | EDT에서 부르면 작업을 `AbstractJob`으로 돌리고 모달 진행 대화상자(`ProgressDialog`, 작업 이름, 진행 막대, Cancel)에서 기다린다. 다른 스레드면 그 스레드에서 실행 |
| `executeWithProgressBlocking` | SWT `DesktopUI`와 같은 Job. EDT에서는 진행 대화상자에서 기다리고(취소 시 `runnable.cancel()`), 다른 스레드는 `join` |
| `showNotification`, `showWarningNotification` | 로그 + 메인 창 오른쪽 아래의 비모달 알림(`NotificationToast`, 레이어 창에 올림, 몇 초 뒤 사라짐, 누르면 `feedback` 실행) |

- 진행 대화상자는 짧은 작업에서 깜빡이지 않도록 처음 300ms는 대화상자 없이 기다린다(SWT의 짧은 대기와 같은 방식).
  기다리는 동안 EDT는 모달 대화상자의 이벤트 루프에 있으므로, 작업이 `invokeAndWait`로 띄우는 대화상자(비밀번호 입력 등)도 정상으로 뜬다.
- 남은 항목(코드에 `// Phase C:`로 표시): `showError`의 DB별 처리와 상세 정보 창, `selectObject`(탐색기 선택 대화상자),
  `openEntityEditor`(객체 편집기), `openConnectionEditor`(범용 연결 화면), `createLoadingService`(Swing 로딩 표시, EDT에서 완료 통지),
  `copyTextToClipboard`의 HTML 형식, `readAndDispatchEvents`, `promptAuthModelCredentials`(인터페이스 기본값 `false`).

### 외부 라이브러리 (B단계 추가)

| 라이브러리 | 버전 | 라이선스 | 출처 |
|---|---|---|---|
| JSVG (`com.github.weisj.jsvg`) | 2.1.0 | MIT | Eclipse 2026-09 릴리스 저장소(빌드 대상 플랫폼)의 OSGi 번들. SHA-1 `c1a403f318de302fa81e4403fe8fb4bd6056a41f`, SHA-256 `a538142d27c57c55066da02a535cc8eee3afd0d37b3daff8eba6344bd0ffc1b6` 확인 |

- 이미 OSGi 번들(매니페스트에 `Bundle-License: MIT`, jar 안에 `META-INF/LICENSE`)이라 FlatLaf처럼 jar를 받아 넣을 필요 없이 `Require-Bundle`로 쓰고,
  Swing 피처(`com.seizia.chromite.swing.feature`)에 넣었다. 번들이 요구하는 SPI Fly(`org.apache.aries.spifly.dynamic.bundle`)는 런타임 피처에 이미 들어 있다.
- **SWT가 끌려 들어오는 문제:** JSVG 번들은 `Require-Capability`로 `osgi.serviceloader`(`com.github.weisj.jsvg.logging.LogManager` 공급자)를
  필수로 요구한다. Eclipse 저장소에서 이 능력을 제공하는 것은 SWT 조각 `org.eclipse.swt.svg`뿐이라, JSVG만 피처에 넣은 첫 빌드에서는
  p2가 `org.eclipse.swt`, `org.eclipse.swt.svg`, `org.eclipse.swt.win32.win32.x86_64`를 제품에 넣었다(2026-11-06 확인).
  그래서 Swing 번들이 같은 능력을 제공한다: `MANIFEST.MF`의 `Provide-Capability: osgi.serviceloader;…;register:=…JsvgLogManager`,
  `META-INF/services/com.github.weisj.jsvg.logging.LogManager`, `JsvgLogManager`(JSVG 로그를 Chromite 로그로 보냄).
  SPI Fly가 동작하지 않으면 JSVG는 기본 로거(java.util.logging)를 쓰며, 아이콘 그리기에는 영향이 없다.
- FlatLaf의 SVG 아이콘(`flatlaf-extras`의 `FlatSVGIcon`)도 JSVG를 쓰므로, JSVG를 직접 쓰고 `flatlaf-extras`는 넣지 않았다.
- `NOTICE`에 출처를 적었다.

### 확인 (2026-11-06)

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 129개, macOS arm64 130개(A단계 + JSVG)이고,
  이름에 `swt`, `jface`, `org.eclipse.ui`, `org.eclipse.e4.ui`가 들어간 번들은 둘 다 0개다.
- Windows에서 in-process JVM(`-vm …\bin\server\jvm.dll`)으로 샘플 SQLite 연결이 있는 작업 공간 사본을 열어 키보드로 확인했다:
  연결 펼치기(연결됨, 연결 아이콘에 연결 표시) → 폴더(테이블, 뷰, 인덱스, 시퀀스, 테이블 트리거, 데이터 타입) → 테이블 12개 → 테이블 선택 시 Properties 표.
  Refresh 단추, Results 숨기기(Ctrl+R)·Properties 숨기기(F4), 실행 중 다크 테마 전환, About, 다시 시작했을 때 다크 테마와 숨긴 Properties 복원.
- 디버그 로그(`.metadata/dbeaver-debug.log`)에 ERROR/Exception 없음.
- macOS 실행은 확인하지 못했다.

### B단계에서 남긴 것 (C단계 이후)

- **SQL 편집기:** RSyntaxTextArea 기반 편집기 탭, F5 실행(선택 1자 이상이면 선택, 아니면 전체), SQL Server `GO` 배치 묶기를
  `SQLQueryExecuteJob`·`SQLQueryExecuteHandler`로 연결("F5와 GO 배치를 실행 계층에 연결하는 방법").
- **결과 그리드:** Results 탭에 결과 표 쌓기(자체 `DBDDataReceiver` + `AbstractTableModel`), Messages 탭에 행 수·경고·PRINT 출력.
- 탐색기: 활성 프로젝트 전환, 객체 편집기, Script as, 필터. (Script as와 DDL 보기는 E단계에서 구현했다)
  (연결 화면·연결 명령과 모델 이벤트로 트리 갱신은 D단계에서 구현했다. "구현 현황 — D단계")
- 위 "DBPPlatformUI B단계 구현"의 남은 항목.
- ~~도킹(끌어다 놓기, 떠 있는 창)이 필요해지면 ModernDocking 재검토.~~ 2026-11-06 도입("도구 창 도킹").

## 구현 현황 — C단계

C단계의 목표는 SSMS식 SQL 편집기와 결과 창이다: 편집기 탭을 열고, F5로 실행하고, 결과 표와 메시지를 본다.

### 구성

| 클래스 | 위치 | 역할 |
|---|---|---|
| `SqlEditorPanel` | Swing 번들 | 편집기 탭 하나(SSMS 쿼리 창). 도구 막대(Execute, Cancel, 연결 이름), RSyntaxTextArea 편집기, 쿼리 상태 줄, 실행 흐름 |
| `QueryResultsView` | Swing 번들 | 편집기 하나의 Results(결과 표 쌓기) / Messages(메시지) 내용 |
| `ResultSetGrid` | Swing 번들 | 결과 집합 하나의 읽기 전용 표(`JTable` + `AbstractTableModel`, 행 번호 머리글) |
| `ResultSetGridReceiver` | Swing 번들 | 엔진이 Job 스레드에서 부르는 `DBDDataReceiver`. 값을 표시 문자열로 바꿔 모은다 |
| `SQLScriptBatches` | `model.sql`, `com.seizia.chromite.model.sql.exec` | 편집기 텍스트 → 실행할 요소. 배치 구분자가 있는 방언은 `GO` 사이 문장을 쿼리 하나로 묶는다 |
| `MainFrame` (변경) | Swing 번들 | New Query(메뉴·도구 막대), Query 메뉴(F5, 취소), 닫을 수 있는 편집기 탭, 활성 편집기의 결과를 아래 탭에 끼우기 |
| `ObjectExplorerPanel` (변경) | Swing 번들 | 오른쪽 클릭 "New Query", 선택한 노드의 연결 조회 |

### 편집기 열기

- Object Explorer에서 연결이나 그 아래 아무 노드를 오른쪽 클릭 → **New Query**: 그 노드의 연결(`DBNDatabaseNode.getDataSourceContainer()`)로 연다.
- **File > New Query (Ctrl+N)**, 메뉴 아래 도구 막대의 **New Query**: Object Explorer에서 선택한 연결, 없으면 활성 편집기의 연결. 둘 다 없으면 안내 메시지.
- 탭 제목은 SSMS처럼 `SQLQueryN.sql - <연결 이름>`(N은 실행 중 1부터 증가). 편집기 탭에는 닫기 단추가 있고(FlatLaf `tabClosable`, 탭 줄은 여러 줄로 감지 않고 한 줄 스크롤), 닫으면 실행 중인 쿼리를 취소한다.
  아직 파일 저장이 없어서 닫을 때 저장 여부를 묻지 않는다. (E단계에서 파일 열기·저장과 닫을 때 저장 확인을 넣고, 제목에 고침 표시 `*`를 붙였다)
- 연결은 편집기를 열 때가 아니라 실행할 때 필요하면 연다(SSMS는 열 때 연결한다. 연결 실패를 실행 결과와 같은 곳에서 다루기 위해 실행 시점으로 했다).

### 편집기 (RSyntaxTextArea)

- `RSyntaxTextArea`(`SYNTAX_STYLE_SQL`) + `RTextScrollPane`(줄 번호). 실행 취소·다시 실행, 괄호 짝 표시 등은 라이브러리 기본 기능이다.
- 색은 테마마다 RSyntaxTextArea 테마 XML(`SqlEditorLight.xml`, `SqlEditorDark.xml`)로 정한다. C단계에는 라이트가 SSMS풍이었고,
  디자인 적용(2026-11-06)에서 두 테마 모두 DataGrip New UI 색으로 바꿨다가, "편집기 글꼴과 SQL 색"(2026-11-06)에서 SSMS 색으로 바꿨다
  ([design-spec.md](design-spec.md) "SQL 구문 색"). 색값만 옮겼고 SSMS·DataGrip의 테마 파일은 쓰지 않았다. 사용자가 Options에서 바꾼 색은 XML 위에 덮어쓴다(`EditorColors`).
  글꼴은 XML이 아니라 코드에서 정한다(`EditorFont`, Cascadia Mono 합성 글꼴. 2026-11-06 전에는 Look and Feel의 `monospaced.font`).
- 테마 전환: Look and Feel이 바뀌면(`updateUI`) 트리 갱신이 끝난 뒤(`invokeLater`) 현재 테마의 XML을 다시 적용한다. 거터(줄 번호) 색도 테마가 정하므로
  처음 적용은 스크롤 창을 만든 뒤에 한다.
- 구문 강조는 RSyntaxTextArea의 일반 SQL 규칙이다. DBeaver의 방언별 키워드는 아직 연결하지 않았다. (자동완성은 F단계에서 연결했다, "구현 현황 — F단계")
  → 2026-11-06 연결함: `SqlTokenClassifier`가 편집기 연결의 방언으로 키워드·함수·자료형을 고친다("편집기 글꼴과 SQL 색").

### 실행 흐름 (F5)

1. **범위:** 선택이 1자 이상이면 선택, 아니면 전체 텍스트("쿼리 실행 사양"). 공백뿐이면 실행하지 않는다.
   범위는 전체 텍스트 안의 위치로 넘기므로 쿼리의 위치가 편집기 위치와 같다(오류 줄 번호 계산에 씀).
2. **준비 Job (`AbstractJob`):** 연결이 없으면 `DBUtils.initDataSource`(→ `SwingConnectionService`)로 연결한다. 실행 문맥은 연결의 기본(주) 문맥
   (`DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false)`)이다. 그다음 `SQLScriptBatches.extractQueries`로 나눈다.
3. **실행 Job:** `SQLQueryExecuteJob`을 만들어 예약한다(`runDirectly`가 아니라 예약하는 것은 `DataSourceJob`의 연결 잡기 `acquire/release`를 그대로 쓰기 위해서).
   EDT는 기다리지 않는다. 실행 중에는 상태 줄의 경과 시간이 0.25초마다 바뀐다.
4. **취소:** 메인 창 SQL Editor 도구 막대의 중지(■) 또는 Query > Cancel Executing Query(Alt+Break). 지금 도는 Job(준비 또는 실행)을 취소한다.
   준비 Job이 실행 Job을 예약하는 순간과 겹치지 않도록 같은 잠금 안에서 취소 표시를 확인하고 예약한다. 실행 Job의 취소는 `AbstractJob`이 실행 중인 문장을 취소한다.
5. **끝:** 실행 Job의 `done` 이벤트(또는 준비 단계의 조기 종료)에서 EDT로 상태를 마무리한다. 실행마다 `Execution` 객체를 두어, 끝난 실행의 늦은 결과가 다음 실행 화면에 섞이지 않게 한다.

- **커밋·오류 처리·최대 행 수:** 커밋 방식은 엔진 기본값 `NO_COMMIT`(연결의 자동 커밋 설정을 그대로 따름), 최대 행 수는 연결의
  `ModelPreferences.RESULT_SET_MAX_ROWS`(기본 200). 오류 처리는 2026-11-07부터 Options > Query Execution 설정을 넘긴다(멈춤 = `STOP_COMMIT`,
  건너뛰고 계속 = `IGNORE`). 그 전에는 엔진 기본값 `STOP_ROLLBACK`이어서 오류로 멈춘 스크립트가 수동 커밋 세션의 열린 트랜잭션을 묻지 않고 롤백했다
  ([swing-options.md](swing-options.md) "오류 처리와 트랜잭션").
- **핸들러(`SQLQueryExecuteHandler`):** `getDataReceiver`는 결과 집합마다 `ResultSetGridReceiver`를 만든다. `handleExecutionError`는 대화상자를 띄우지 않고
  `STOP`을 돌려준다(오류는 Messages 탭에 이미 썼다. 쿼리를 보내기 전에 난 오류 — 매개변수, pragma, 제어 명령 — 는 여기서 Messages에 쓴다).
  나머지(확인 대화상자, 설정값)는 인터페이스 기본 구현(`DBPPlatformUI` = `SwingPlatformUI`)이다.
- **스크립트 문맥:** 실행마다 새 `SQLScriptContext`(부모 없음, 매개변수 입력기 없음). 제어 명령 출력(`@echo` 등)은 Messages 탭에 쓴다.

### GO 배치 (`SQLScriptBatches`)

- 방언에 배치 구분자가 있으면(`SQLSyntaxManager.getBatchDelimiters()`, SQL Server·Sybase의 `GO`. 설정 `SCRIPT_IGNORE_NATIVE_DELIMITER`를 켜면 없음)
  `extractScriptQueries(..., includeBatchDelimiterElements = true)`로 나누고, 두 `SQLBatchDelimiterElement` 사이의 `SQLQuery`들을 첫 문장 시작부터 마지막 문장 끝까지의
  원문 그대로 `SQLQuery` 하나로 합친다(사이의 `;`·주석 포함, 매개변수는 합친 범위에서 다시 찾음). 문장이 하나뿐인 배치는 파서가 만든 쿼리를 그대로 쓴다.
- `GO 2`처럼 반복 횟수가 있으면 SSMS·sqlcmd처럼 그 배치를 그만큼 실행한다. 빈 배치(처음·연속 `GO`)는 건너뛴다. 제어 명령은 배치를 끊고 따로 실행한다.
- 배치 구분자가 없는 방언은 지금까지처럼 문장마다 하나다.
- 배치 안의 여러 문장이 낸 오류와 결과는 [sql-batch-errors.md](sql-batch-errors.md)대로 읽는다(오류마다 줄 번호와 함께 Messages에 쓰고, 오류 뒤에 실행된
  문장의 결과도 읽고, 실행은 실패로 끝남).
- SQL Server인지 이름으로 검사하지 않고 구분자 유무로 판단해 같은 구분자를 쓰는 방언이 같은 경로를 쓴다.
  UI와 무관하고 단위 테스트할 수 있도록 실행 엔진 옆(`model.sql`의 `com.seizia.chromite.model.sql.exec`)에 두었다.
  파서 문맥은 SWT `SQLEditorBase`와 같이 만든다(연결의 설정 저장소, `loadRules(연결, false)`, `SQLPartitionScanner` 문서 분할기).

### Results / Messages

- SSMS처럼 결과는 쿼리 창마다 따로 있다. 메인 창 아래의 Results / Messages 탭에는 활성 편집기의 두 컴포넌트를 끼운다(편집기가 없으면 자리 표시. 2026-11-07부터는 Results 카드를 숨김, "Results 자동 숨김").
  탭에서 빠져 있던 컴포넌트는 테마 전환을 받지 못했을 수 있어 끼울 때 Look and Feel을 맞춘다(`updateComponentTreeUI`, 마지막으로 맞춘 Look and Feel을 기억).
  2026-11-08부터 바깥 탭은 없고 쿼리 창마다의 탭 줄 `Result1 | … | Messages | Execution plan`을 통째로 끼운다("결과 탭").
- **Results:** 결과 집합마다 표 하나를 위아래로 쌓는다. 모두 최소 높이(140px) 이상으로 들어가면 높이를 똑같이 나눠 채우고, 아니면 바깥 세로 스크롤.
  2026-11-08부터 결과 집합마다 탭이다("결과 탭").
  - 값은 `ResultSetGridReceiver`가 Job 스레드에서 결과 집합이 열려 있는 동안 읽어(`DBUtils.getAttributeBindings`, `fetchValueObject`) 표시 문자열(`DBDDisplayFormat.UI`)로 바꾸고
    값을 놓아 준다(`DBUtils.releaseValue`). EDT는 문자열만 다룬다. 행은 모아 두었다가 `fetchEnd`에서 한 번에 넘긴다(실패로 `fetchEnd`가 없으면 `close`에서).
  - 열 이름은 바인딩 이름(열 별칭), NULL은 흐린 기울임꼴 "NULL", 숫자 열(`DBPDataKind.NUMERIC`)은 오른쪽 정렬, 열 너비는 머리글과 앞 200행 글자 너비(40–320px),
    왼쪽에 행 번호 머리글(누르면 그 행 선택), 칸 선택과 Ctrl+C(탭으로 나눈 표시 문자열) 복사. 편집은 아직 없다(G단계에서 구현, "구현 현황 — G단계").
  - 열 머리글(2026-11-07): 열 아이콘 + 이름 + 오른쪽 끝의 필터(깔때기)·정렬 아이콘. 깔때기는 그 열의 필터 팝업(서버에서 읽은 값 목록과 조건, 서버에서 다시 읽음),
    정렬 아이콘과 이름은 정렬. 결과 위의 WHERE 필터 줄은 없앴다. [swing-grid-column-filters.md](swing-grid-column-filters.md).
- **Messages:** 쿼리마다 경고(JDBC `SQLWarning` — SQL Server `PRINT`, PostgreSQL `NOTICE` 등), `(N rows affected)`(결과 집합은 읽은 행 수, 최대 행 수에 닿으면 그 사실도),
  `Execution time: N ms`를 쓴다. 오류는 빨간색으로 `Msg <오류 코드>, State <SQLSTATE>, Line L, Column C`와 예외 메시지를 쓴다.
  위치는 방언의 `DBPErrorAssistant.getErrorPosition`이 찾으면 그 위치(편집기 기준 줄·열), 못 찾으면 쿼리 시작 줄이다. 끝에 `Completion time`.
- **탭 전환:** 오류가 있거나 결과 표가 하나도 없으면 SSMS처럼 Messages 탭을, 아니면 Results 탭을 보여 주고, Results 도구 창이 숨어 있으면 다시 보인다.
  편집기마다 마지막으로 본 탭을 기억해 편집기 탭을 바꾸면 그 탭을 다시 보여 준다. 2026-11-08부터의 규칙은 "결과 탭".
- **상태 줄(쿼리 창 아래):** "Executing query…" / "Query executed successfully." / "Query completed with errors." / "Query was canceled.", 경과 시간(`hh:mm:ss.SSS`), 읽은 행 수.

### 메뉴와 단축키

| 메뉴 | 항목 | 단축키 |
|---|---|---|
| File | New Query | Ctrl+N |
| Query | Execute | F5 |
| Query | Cancel Executing Query | Alt+Break |

Query 메뉴 항목은 활성 편집기에 적용되고, 메뉴 단축키라 포커스가 결과 표에 있어도 동작한다. 편집기가 없거나 실행 중이면 해당 항목이 꺼진다.

### DBPPlatformUI (C단계)

- 실행 중 오류는 `SQLQueryExecuteHandler.handleExecutionError`를 구현해 Messages 탭으로 보냈다. 그래서 `SwingPlatformUI`는 고치지 않았다.
- 연결 실패는 B단계처럼 `SwingConnectionService`가 `showError` 대화상자로 알리고, Messages에는 "Not connected to '…'"를 쓴다.
- 남은 항목(`// Phase C:` 표시 그대로): `showError`의 DB별 처리와 상세 정보 창, `selectObject`, `openEntityEditor`, `createLoadingService`,
  `copyTextToClipboard`의 HTML 형식, `readAndDispatchEvents`, `promptAuthModelCredentials`. (`openConnectionEditor`는 D단계에서 구현)

### 외부 라이브러리 (C단계 추가)

| 라이브러리 | 버전 | 라이선스 | 출처 |
|---|---|---|---|
| RSyntaxTextArea (`com.fifesoft:rsyntaxtextarea`) | 4.0.1 (2026-07 Maven Central 최신 안정판) | BSD 3-Clause | Maven Central. SHA-1 `d3fc52cef78c172174a8a64e9c89758ebe674f1c`(Central의 `.sha1`과 같음), SHA-256 `fbe88919231bc468f9dddbd4450ef30634612a6e2ef3f84dff4f2789c8fc508e` 확인 |

- jar에 OSGi 매니페스트가 있지만 모든 패키지가 `Private-Package`이고 `Export-Package`가 없어 `Require-Bundle`로 쓸 수 없다. 그래서 FlatLaf처럼
  `maven-dependency-plugin`으로 받아 `lib/rsyntaxtextarea.jar`로 넣고 `Bundle-ClassPath`에 더했다. 버전은 번들 `pom.xml`의 `rsyntaxtextarea.version` 한 곳에 있다.
- 라이선스 본문은 jar 안 `META-INF/LICENSE`에 있다. `NOTICE`에 출처를 적었다.
- Swing 번들의 `Require-Bundle`에 `com.seizia.chromite.model.sql`(실행 엔진, 파서)을 더했다. 이미 런타임 피처에 들어 있는 번들이다.

### 확인

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 129개, macOS arm64 130개(B단계와 같음,
  RSyntaxTextArea는 Swing 번들 안의 jar)이고, 이름에 `swt`, `jface`, `org.eclipse.ui`, `org.eclipse.e4.ui`가 들어간 번들은 둘 다 0개다.
  Swing 번들 jar에 `lib/rsyntaxtextarea.jar`(SHA-256 위와 같음)와 테마 XML 두 개가 들어 있다.
- Windows에서 in-process JVM(`-vm …\bin\server\jvm.dll`)으로 샘플 SQLite 연결이 있는 작업 공간 사본을 열어 키보드·마우스로 확인했다:
  - Object Explorer 연결 오른쪽 클릭 → New Query: `SQLQuery1.sql - Chromite Sample Database (SQLite)` 탭, 연결 전 상태에서 F5 → 연결 후 실행.
  - SELECT 두 개인 스크립트 전체 F5 → 결과 표 두 개가 위아래로 높이를 나눠 쌓임(5행, 8행), NULL 표시, 숫자 오른쪽 정렬, 행 번호, 상태 줄 `13 rows`,
    Messages에 쿼리마다 `(N rows affected)`·실행 시간.
  - 두 번째 문장만 선택하고 F5 → 표 하나(8행)만.
  - Ctrl+N으로 새 편집기, `SELECT 1 AS ok;` + 없는 테이블 SELECT → Messages 탭으로 전환, 첫 쿼리 `(1 row affected)`, 오류 `Msg 1, Line 2`(SQLite는 오류 위치를 주지 않아 쿼리 시작 줄)와 SQLite 메시지(빨간색), 상태 줄 "Query completed with errors.".
  - 끝나지 않는 재귀 CTE 실행 중 EDT가 막히지 않음(경과 시간 갱신), Cancel 단추와 Alt+Break(Query 메뉴)로 취소 → "Query was canceled.".
  - 실행 중 다크 테마 전환 → 편집기(DataGrip풍 색)와 결과 표가 다크로 바뀜. 다른 편집기 탭의 결과·메시지(탭에서 빠져 있던 것)도 탭을 바꾸면 다크로 보임.
  - 편집기 탭 닫기 → 다음 편집기의 결과와 마지막으로 보던 탭(Messages)이 아래에 나옴.
- 디버그 로그(`.metadata/dbeaver-debug.log`)에 ERROR/WARN 없음. Eclipse 로그(`.metadata/.log`)에는 일부러 낸 SQL 오류(엔진이 `log.error`로 남김, SWT와 같음)만 있다.
  첫 시험에서 SQLite는 경고가 없을 때 `SQLQueryResult.getWarnings()`가 `null`이라 Messages가 비는 문제를 찾아 고쳤다.
- SQL Server `GO` 배치는 SQL Server가 없어 실행해 보지 못했다. 배치 묶기는 실제 SQL Server 방언으로 단위 테스트했다
  (`test/com.seizia.chromite.test.platform`의 `SQLScriptBatchesTest`: `GO` 사이 문장 합치기와 원문 범위, `GO 2` 반복, 빈 배치 건너뛰기, 배치 구분자 없는 방언).
  `mvn verify -Pproduct-chromite-swt -Dtest=SQLScriptBatchesTest`로 실행해 3개 모두 통과했다.
  SQL Server 방언은 `DECLARE`를 블록 머리로 보아 `DECLARE …; SELECT …;`를 파서가 이미 쿼리 하나로 돌려주고, 일반 문장 두 개(`SELECT 2; SELECT 3`)는 이 클래스가 합친다.
- macOS 실행은 확인하지 못했다.

### C단계에서 남긴 것 (D단계 이후)

- 편집기: 파일 열기·저장(닫을 때 저장 확인, E단계에서 구현), 방언별 키워드·자동완성(자동완성은 F단계에서 구현), 실행한 문장 강조, 오류 위치로 이동(Messages 더블클릭).
- 실행: 편집기별 별도 연결(SSMS는 쿼리 창마다 연결. 2026-11-07 구현, [transactions.md](transactions.md) "쿼리 창마다 별도 연결"), 오류 뒤 다음 배치 계속 실행(SSMS 기본 동작. 지금은 멈춤), 커밋 방식·트랜잭션 표시, 서버 출력 읽기
  (`DBCServerOutputReader`, Oracle `DBMS_OUTPUT`. 2026-11-07 구현, [swing-server-output.md](swing-server-output.md)), 매개변수 입력 대화상자, `@set` 변수의 편집기 단위 유지(2026-11-08 구현, [swing-query-commands.md](swing-query-commands.md)).
- 결과: 편집(Edit Top 200 Rows, "결과 모델 분석", G단계에서 구현), 더 읽기(최대 행 수 이후), 정렬, 결과 내보내기(CSV는 E단계에서 구현), 텍스트로 결과 보기.
- SQL Server `GO` 배치는 SQL Server로 실행해 보지 못했다(아래 확인 참고).

## 구현 현황 — D단계

D단계의 목표는 Swing 앱에서 연결을 만들고, 고치고, 지우고, 연결하고, 끊는 것이다(SSMS "Connect to Server"). MVP 2번
"범용 연결 화면 하나로 전체 드라이버를 지원"을 구현했다.

### 구성

| 클래스 | 역할 |
|---|---|
| `ConnectionDialog` | 연결 만들기(드라이버 고르기 → 설정 2단계)·편집 대화상자. Test Connection, Save, 이름 만들기 (→ 한 화면으로 바뀜: "Connect 대화상자 (SSMS식)") |
| `DriverSelectionPanel` | 1단계: 검색 칸, 범주 콤보, 아이콘이 있는 드라이버 목록 (→ "More database types…" 창으로 바뀜) |
| `ConnectionSettingsPanel` | 2단계: 드라이버 정의로 구성하는 범용 설정 화면(Main / Driver Properties / Provider Properties 탭) (→ 주 양식 + Options 탭으로 바뀜) |
| `ConnectionPropertiesTable` | 드라이버 속성·공급자 속성 표(범주·이름·값, 값 편집, 사용자 속성 추가·삭제, 기본값으로 되돌리기) |
| `ConnectionCommands` | 새로 만들기, 편집, 이름 바꾸기, 삭제, 연결, 연결 끊기, 다시 연결 |
| `SwingDriversService` | `UIServiceDrivers` 구현: 드라이버 파일이 없으면 내려받을지 묻고 진행 대화상자에서 내려받기 |
| `ObjectExplorerPanel` (변경) | 연결 명령 메뉴·단축키, 탐색기 모델 이벤트로 트리 갱신 |
| `MainFrame` (변경) | File > New Connection, 도구 막대 New Connection, 연결 레지스트리 이벤트로 상태 표시줄 연결 수 갱신 |
| `SwingPlatformUI` (변경) | `openConnectionEditor` = 연결 편집 대화상자(SWT처럼 닫힐 때까지 기다림) |

### 메뉴와 단축키

| 위치 | 항목 | 대상 |
|---|---|---|
| File, 도구 막대, Object Explorer 오른쪽 클릭(빈 곳 포함) | New Connection… | 활성 프로젝트(연결 폴더를 고른 상태면 그 폴더) |
| Object Explorer 오른쪽 클릭 | Connect / Disconnect / Reconnect | 선택한 노드가 속한 연결(연결 아래 어느 노드에서나) |
| Object Explorer 오른쪽 클릭, Enter | Edit Connection… | 연결 노드 (다른 노드에서 Enter는 펼치기·접기) |
| Object Explorer 오른쪽 클릭, F2 | Rename | 연결 노드 |
| Object Explorer 오른쪽 클릭, Delete | Delete (확인 후) | 연결 노드 |

### 새 연결: 드라이버 고르기

- 목록은 모델의 `DriverUtils.getAllDrivers()`(활성 공급자의 `getEnabledDrivers`: 꺼짐·대체됨·이 OS 미지원 제외, 이름순)에서
  임시 드라이버(`isTemporary`), 폐기된 드라이버 자리표시(`getDriverStub`), 상용 드라이버 자리표시(`DBPDriverWithLicense`, SWT도 설정을 켜야 보임),
  JDK 내장 ODBC 브리지(`isInternalDriver`)를 뺀 것이다.
- 검색은 SWT `DataSourceTypeViewer`처럼 대소문자·공백을 무시하고 이름, 전체 이름, ID, 설명, 범주에서 찾는다. 범주 콤보는 `getCategories()`로 거른다
  (`getCategory()`는 폐기 예정 API라 쓰지 않음). 아이콘은 Object Explorer와 같은 `NodeIcons`로 그린다.
- 검색 칸에서 위·아래 화살표로 목록을 움직이고, Enter·두 번 누르기·Next로 2단계로 간다. Back으로 돌아가 다른 드라이버를 고르면 이전 드라이버로 만든 설정은 버린다.

### 범용 설정 화면 (`ConnectionSettingsPanel`)

SWT 범용 화면(`GenericConnectionPage`)의 규칙을 모델 코드로 따랐다.

- **입력 칸:** 드라이버 `sampleURL`을 `DatabaseURL.getUrlPattern`으로 읽어 들어 있는 자리 표시의 칸만 보인다.
  `{host}`·`{port}` → Host·Port, `{server}` → Server, `{database}` → Database, `{file}` → Database file + Browse(파일 선택), `{folder}` → Folder + Browse(폴더 선택).
  예시 URL이 없거나 위 자리 표시가 하나도 없으면 URL만 입력한다(`GenericConnectionPage.parseSampleURL`과 같은 판단).
- **연결 방식:** Host(칸 입력. JDBC URL 칸은 읽기 전용으로 `DBPDriver.getConnectionURL`이 만든 URL을 입력하는 대로 보여 주고, 만들 수 없으면 이유를 도움말로) /
  URL(JDBC URL 직접 입력). `DBPDriverConfigurationType`(MANUAL/URL)으로 저장한다.
- **기본값(새 연결):** 드라이버의 기본 호스트(없으면 localhost)·포트·서버·DB·사용자(`ConnectionPageWithAuth`와 같음). 연결 종류는 기본 종류, 비밀번호 저장은 켬.
- **필수 값:** URL 템플릿의 필수 자리 표시(`getMandatoryPropertyNames`)가 비어 있으면 Test·Save를 끄고 무엇이 빠졌는지 보여 준다.
- **인증:** 사용자, 비밀번호, Save password. 드라이버가 익명 접속이면 숨긴다. 비밀번호를 저장하지 않으면 저장할 때 비밀번호(인증 방식의 비밀번호 속성 포함)를 지운다.
  Test Connection에는 입력한 비밀번호를 쓴다(SWT가 임시 연결에서 하는 것과 같음). 인증 방식은 고를 수 없고 연결의 현재 방식(`detectConnectionAuthModel`, 새 연결은 Database Native)을 저장한다.
- **Driver Properties 탭:** 처음 열 때 모델의 `DBPDataSourceProvider.getConnectionProperties`(JDBC 드라이버의 `getPropertyInfo`, SWT `ConnectionPropertiesControl`과 같은 호출)를
  현재 폼 값을 쓴 임시 연결로 진행 대화상자에서 읽는다. 드라이버 속성이 아닌 값은 "User Properties" 범주로 보인다. 값은 모델의 `PropertySourceCustom`에 두고,
  저장할 때 SWT `DriverPropertiesDialogPage.savePropertyValues`처럼 연결의 드라이버 속성을 그 값(원래 값 + 바꾼 값)으로 바꾼다. 탭을 열지 않았으면 드라이버 속성을 건드리지 않는다.
  읽기에 실패하면(드라이버 파일 없음, 오프라인 모드 등) 이유를 표 아래에 쓰고, 탭을 다시 열면 다시 읽는다.
- **Provider Properties 탭:** 드라이버 정의의 공급자 속성(`getProviderPropertyDescriptors`, `plugin.xml`의 `provider-properties`)이 있을 때만 보인다.
  표에 있는 속성만 연결의 공급자 속성(`setProviderProperty`/`removeProviderProperty`)으로 저장하고, 표에 없는 공급자 속성(다른 설정이 쓰는 것)은 그대로 둔다.
- **값 편집기:** 가능한 값이 있으면 편집 가능한 콤보, 불리언은 true/false 콤보, 나머지는 글자. 값을 지우면 기본값으로 돌아간다. 기본값과 다른 값은 굵게,
  이름에 password·token이 있는 값은 가린다(SWT와 같은 규칙).

### 저장, 시험, 편집

- **새 연결 Save** (SWT `NewConnectionWizard.persistDataSource`): 아직 등록하지 않은 `DataSourceDescriptor`(`registry.createDataSource(새 ID, 드라이버, 설정)`)에 폼을 쓰고
  `DBPDataSourceRegistry.addDataSource`로 등록한다. `addDataSource`가 설정 파일(`.dbeaver/data-sources.json`)을 바로 저장하고(`persistDataSourceCreate` → `saveDataSources`,
  [DataSourceRegistry.java:558-566, 626-636](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/DataSourceRegistry.java)) `OBJECT_ADD` 이벤트를 보낸다.
  그래서 `flushConfig`는 따로 부르지 않는다(SWT 마법사도 부르지 않음).
- **이름:** 비워 두면 SWT 기본 이름 규칙(`${host_or_database}`의 legacy 규칙, `ConnectionNameResolver`: DB 이름의 마지막 부분 → 호스트 → 서버 → 드라이버 이름, 50자)으로 만든다.
  다른 연결과 겹치면 `DataSourceUtils.generateUniqueDataSourceName`으로 `이름 (1)`처럼 만든다. 사용자가 바꾼 이름 규칙 설정(`navigator.settings.default.connectionPattern`)은 아직 읽지 않는다
  (`ConnectionNameResolver`가 SWT 번들에 있음).
- **Test Connection** (SWT `ConnectionWizard.testConnection`): 폼 값을 쓴 임시 연결(새 ID, `setTemporary`, 메타데이터 별도 연결 `NEVER`)로 모델의 `ConnectionTestJob`을 돌리고
  진행 대화상자(Cancel 가능)에서 기다린다. 성공하면 연결 시간, 서버 버전, 드라이버 버전을, 실패하면 오류를 보여 준다. 끝나면 임시 연결을 버린다.
- **편집** (SWT `EditConnectionWizard`): 원본의 사본(같은 ID)으로 폼을 채운다. Save에서 원본 사본과 바뀐 사본을 `equalSettings`로 비교해 바뀐 것이 있을 때만 원본에 쓰고
  `persistConfiguration`으로 저장한다. 연결 중이면 다시 연결할지 묻는다. 폼에 없는 설정(폴더, 연결 종류, 읽기 전용, 탐색기 설정, 필터, 초기화 SQL, 네트워크 핸들러)은 그대로 남는다.
- **이름 바꾸기:** `DBNDataSource.rename`(이름 저장 + 갱신 이벤트, SWT 이름 바꾸기와 같음).
- **삭제** (SWT `DataSourceDescriptorManager.deleteObject`): 확인 → 연결 중이면 `DisconnectJob`으로 끊은 뒤 → `DBPDataSourceRegistry.removeDataSource`(설정 파일 저장, 비밀 정보 삭제, `OBJECT_REMOVE`).
- **연결 / 끊기 / 다시 연결** (SWT `DataSourceHandler`): 연결은 `SwingConnectionService`(B단계, 연결 Job 예약), 끊기는 모델의 `DisconnectJob`(이미 연결·끊기 Job이 있으면 무시),
  다시 연결은 끊기가 끝난 뒤 연결. SWT가 끊기 전에 하던 열린 편집기 저장 확인은 아직 없다. 열린 트랜잭션은 끊기 전에 묻는다
  (2026-11-07, [idle-transactions.md](idle-transactions.md) "연결 끊기 전 질문").

### 드라이버 파일 내려받기 (`SwingDriversService`)

- 드라이버를 처음 쓸 때(연결, Test Connection, Driver Properties 읽기) `DriverLoaderDescriptor.downloadDriverLibraries`가 `UIServiceDrivers`를 부른다
  ([DriverLoaderDescriptor.java:414-428](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLoaderDescriptor.java)).
  지시서에는 `DBPPlatformUI`의 메서드로 적혀 있었지만 실제 호출 지점은 이 서비스라서, `SwingConnectionService`처럼 `com.seizia.chromite.service` 확장으로 등록했다.
  SWT 제품은 `com.seizia.chromite.core`의 `UIServiceDriversImpl`(`DriverDownloadDialog`)이다. Swing 제품에는 이 서비스가 없어서 지금까지는 묻지 않고 바로 내려받았다.
- 없는 라이브러리 이름(이 OS용, 끄지 않은 필수 라이브러리 중 로컬 파일이 없는 것)을 보여 주고 Download / Cancel을 묻는다. Download면 모델의 `DriverUtils.downloadDriverFiles`를
  모달 진행 대화상자(Cancel 가능)에서 돌리고 호출한 Job은 기다린다. Cancel이면 모델이 드라이버 클래스를 찾지 못했다는 오류를 낸다.
- 오프라인 모드(`ui.drivers.offline`, [drivers.md](drivers.md))에서는 모델이 이 서비스를 부르지 않고 로컬 파일만 찾은 뒤, 없으면 이유를 설명하는 오류를 낸다.
  그 오류는 Test Connection 오류 대화상자나 Driver Properties 표 아래에 그대로 보인다. 버전 선택과 의존성 트리 보기는 없다.

### 탐색기 모델 이벤트

B단계에서 남겼던 "모델 이벤트로 트리 갱신"을 구현했다. 다른 곳(연결 대화상자, 모델 Job, 다른 창)에서 일어난 변경이 다시 시작하지 않아도 보인다.

- `ObjectExplorerPanel`이 `DBNModel.addListener(INavigatorListener)`로 듣는다. 모델은 이벤트를 모아 약 100ms마다 `DBPPlatformUI.executeInMainThread`(= EDT)로 보낸다
  ([DBNModel.java:602-636](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/model/navigator/DBNModel.java)). 연결 레지스트리 이벤트는 `DBNProjectDatabases`가
  탐색기 이벤트로 바꿔 준다([DBNProjectDatabases.java:351-470](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/model/navigator/DBNProjectDatabases.java)).
- **연결 목록**(활성 프로젝트의 `DBNDataSource`·`DBNLocalFolder`·`DBNProjectDatabases`): 추가·삭제·이름 변경(`REFRESH`)·목록 재구성(`STRUCT_REFRESH`)이면 목록을 백그라운드에서 다시 읽고,
  이미 있는 트리 노드(펼친 하위 트리와 펼침 상태 포함)와 선택은 그대로 둔다. 이 앱에서 만든 연결(`ADD` + `SELECT`)은 목록을 읽은 뒤 선택해 보여 준다.
- **연결 상태:** 끊김(`UNLOAD`)이면 그 연결을 접고 자식을 버려 다시 펼칠 때 연결해서 읽게 한다(그 연결을 지금 읽는 중이면 그대로 둔다: 연결 실패 처리가 끝나야 하므로).
  연결(`LOAD`, `BEFORE_LOAD`/`AFTER_LOAD`)이면 아이콘(연결 표시)을 다시 그린다.
- **DB 객체:** 모델이 지운 노드(`REMOVE`)는 트리에서 뺀다. 다른 곳에서 새로 만든 객체(`ADD`)는 아직 반영하지 않는다(새로 고침으로 보인다. 객체를 만드는 화면이 아직 없음).
- 이 패널이 원천인 이벤트(Refresh의 `refreshNode(monitor, 이 패널)`)는 무시한다. 이미 `loadChildren`이 반영하기 때문이다.
- **상태 표시줄:** `MainFrame`이 모든 프로젝트의 연결 레지스트리에 `DBPEventListener`를 달아 연결 추가·삭제 때 `N connections in workspace`를 다시 센다.
  창을 닫을 때 두 리스너를 뗀다.

### 범용 화면에서 아직 못 하는 드라이버별 설정

SWT의 DB별 연결 화면(`ext.*.ui`, 32개 번들)에만 있는 설정은 범용 화면에서 고를 수 없다. 공급자 속성으로 정의된 것과 JDBC 드라이버 속성은 표로 고칠 수 있다.

| 드라이버 | 범용 화면에서 되는 것 | 아직 못 하는 것 (SWT 화면) |
|---|---|---|
| 공통 | 호스트·포트·DB·서버·파일, URL 직접 입력, 사용자·비밀번호, 드라이버 속성, 공급자 속성 | 인증 방식 선택(`AuthModelSelector`: Kerberos, NTLM, pgpass, 클라우드 IAM 등), SSH·프록시·SSL 네트워크 핸들러, 연결 종류·폴더·읽기 전용·설명(`ConnectionPageGeneral`), 초기화 SQL·자동 커밋·격리 수준(`ConnectionPageInitialization`), 드라이버 대체(Driver type), 드라이버 편집·라이선스 보기, 내장 DB 만들기(Create), 연결 변수 도움말 |
| SQL Server | 공급자 속성 "Show all schemas", "Trust Server Certificate", JDBC 드라이버 속성(`encrypt`, `integratedSecurity`, `authentication` 등, 드라이버 파일이 있을 때) | 인증 종류 콤보(SQL Server / Windows / Kerberos / Azure AD 인증 방식), Azure "Show All Databases"(`show-all-databases-azure`), "Encrypt password" |
| Oracle | 공급자 속성 "Service type"(SID/SERVICE), "Role"(SYSDBA 등), "Set Username to OS_USER", 메타데이터 옵션들 | 연결 방식 Basic/TNS/Custom 선택과 TNS 이름·`tnsnames.ora` 경로(`PROP_CONNECTION_TYPE`, `PROP_TNS_PATH`), Oracle 클라이언트(OCI) 선택 |
| PostgreSQL | 공급자 속성 "Show all databases", 템플릿·사용할 수 없는 DB 보기, "Read all data types", "Read table keys with columns", "Replace legacy timezone", `$$` 문자열 처리, "User role", JDBC 드라이버 속성(SSL 등) | "Show database statistics", "Use prepared statements"(공급자 속성 목록에 없음), pgpass 인증 |
| MySQL / MariaDB | 공급자 속성 "Show all databases", "Server Time Zone", JDBC 드라이버 속성(useSSL 등) | 고급 페이지(`MySQLConnectionPageAdvanced`)의 나머지 설정, 로컬 클라이언트 선택 |
| DuckDB, Snowflake, Vertica 등 | 공급자 속성이 정의된 것 | 각 `*ConnectionPageAdvanced`의 추가 설정 |

- 연결 편집에서 위 설정은 원래 값이 그대로 남는다(폼이 쓰지 않는다). 다만 Driver Properties 탭을 열고 저장하면 SWT처럼 연결의 드라이버 속성 전체를 표의 값으로 바꾼다.
- 인증 방식 선택은 인증 방식별 입력 화면(`promptAuthModelCredentials`와 같은 문제)이 필요해 다음 단계로 남긴다.
- (2026-11-06) "Connect 대화상자 (SSMS식)"에서 범용 칸(Login·Password)만으로 끝나는 인증 방식 선택과 연결 종류·읽기 전용·자동 커밋·기본 DB/스키마·keep-alive·유휴 연결 닫기를 추가했다.

### 확인 (2026-11-06)

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 129개, macOS arm64 130개(C단계와 같음)이고,
  이름에 `swt`, `jface`, `org.eclipse.ui`, `org.eclipse.e4.ui`가 들어간 번들은 둘 다 0개다.
- Windows에서 in-process JVM(`-vm …\bin\server\jvm.dll`)으로 C단계 작업 공간 사본(샘플 SQLite 연결 1개)을 열어 키보드·마우스로 확인했다:
  - **새 연결:** 도구 막대 New Connection → 드라이버 목록(아이콘, 범주) → "sqlite" 검색 → Enter → 범용 화면에 `{file}`용 "Database file" + Browse만 보임(인증 칸 없음, SQLite는 익명 접속)
    → 샘플 Chinook.db 사본 경로 붙여 넣기 → JDBC URL 칸이 `jdbc:sqlite:<경로>`로 바로 바뀜 → Driver Properties 탭에 SQLite JDBC 드라이버 속성(application_id 등) 표시
    → Test Connection: "Connected (148 ms) / Server: SQLite 3.54.1 / Driver: SQLite JDBC 3.54.1.0" → Save → Object Explorer에 `test.db`가 바로 생기고 선택됨,
    상태 표시줄 `2 connections in workspace`, `.dbeaver/data-sources.json`에 연결 추가(이름 `test.db`, `configurationType` MANUAL, URL).
  - 펼치기 → 연결됨 표시 → 테이블 11개 → New Query에서 `select count(*) from Album` → `347`.
  - **편집:** 연결 노드에서 Enter → 편집 대화상자(값 채워짐) → 이름을 `Chinook Test`로 바꿔 Save → 트리 이름이 바로 바뀌고 정렬 위치가 바뀌었으며 펼친 하위 트리와 선택은 유지,
    "다시 연결할까요?" → 예 → 연결이 접히고(끊김) 다시 연결됨 표시.
  - **연결 끊기 / 연결:** 오른쪽 클릭 메뉴(연결 상태에 따라 Connect/Disconnect/Reconnect가 켜지고 꺼짐) → Disconnect → 연결 표시 사라짐 → Connect → 연결 표시.
  - **이름 바꾸기:** F2 → `Chinook F2` → 트리와 설정 파일에 반영.
  - **삭제:** Delete 키 → 확인 → (연결 중이라 끊은 뒤) 트리에서 사라지고 상태 표시줄 `1 connection in workspace`, 설정 파일에서 연결이 빠짐.
  - **PostgreSQL 범용 화면:** "postgres" 검색 → PostgreSQL이 맨 위 → Host·Port(5432)·Database(postgres)·User(postgres)·Password·Save password, JDBC URL `jdbc:postgresql://localhost:5432/postgres`,
    Provider Properties 탭(Show all databases 등 9개). 드라이버 파일이 없어 Driver Properties·Test Connection에서 "Download driver files" 확인이 떴고, Cancel하면
    표 아래·오류 대화상자에 드라이버 클래스를 찾지 못했다는 모델 오류가 보였다(내려받기는 실행하지 않음).
- 디버그 로그에 ERROR 없음. WARN/예외는 일부러 취소한 PostgreSQL 드라이버 내려받기("Can't load driver properties", `ClassNotFoundException: org.postgresql.Driver`)뿐이다.
- 확인하지 못한 것: 실제 드라이버 내려받기(네트워크), 오프라인 모드에서의 오류 문구(모델 코드 경로만 확인), 서버형 DB(PostgreSQL 등) 실제 연결, macOS 실행.

### 내장 드라이버 (2026-11-06)

- D단계 결과물에는 공개판 내장 드라이버(묶음 선언 번들과 설치 폴더 `drivers/`)가 없어서, SWT 제품에서는 내장된 PostgreSQL 드라이버도 내려받기를 물었다.
  둘을 `com.seizia.chromite.ce.feature`에서 전용 피처 `com.seizia.chromite.drivers.public.feature`로 옮기고 Swing 피처도 포함하게 했다(jar 목록은 그 피처 한곳).
- 확인: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 결과물 Windows `chromite-swing/drivers/`, macOS `ChromiteSwing.app/Contents/Eclipse/drivers/`의
  파일 34개(18,917,823 bytes)가 SWT 제품의 `drivers/`와 이름·크기·SHA-256이 모두 같고, SWT 제품의 `drivers/`도 바꾸기 전과 같다.
  `com.seizia.chromite.drivers.public` 번들이 `bundles.info`에 들어가 번들 수는 Windows x64 130개, macOS arm64 131개이며, `swt`·`jface`·`org.eclipse.ui`·`org.eclipse.e4.ui` 번들은 여전히 0개다.
- Windows in-process JVM 실행(C단계 작업 공간 사본): New Connection → PostgreSQL → Driver Properties 탭이 내려받기 확인 없이 pgjdbc 속성(adaptiveFetch 등)을 바로 보였고,
  Test Connection은 pgjdbc의 "Connection refused"(localhost:5432, 서버 없음)로 끝났다. 사용자 드라이버 폴더(`ChromiteData/drivers`)에는 PostgreSQL jar가 없으므로 설치 폴더의 `drivers/postgresql`에서 불러온 것이다.

### D단계에서 남긴 것

- 인증 방식 선택, 네트워크 핸들러(SSH 등), 연결 일반 설정(폴더, 연결 종류, 읽기 전용, 설명), 초기화 설정 — 위 표.
- 연결 폴더 만들기·옮기기, 연결 복사, 연결 내보내기·가져오기.
- 지운 연결을 쓰는 열린 SQL 편집기는 그대로 남는다(실행하면 연결 오류). SSMS처럼 연결 끊김 상태로 표시하는 처리가 필요하다.
  연결 이름을 바꿔도 열린 편집기 탭 제목(`SQLQueryN.sql - 연결 이름`)은 바뀌지 않는다. (탭 제목은 E단계에서 연결 레지스트리 이벤트로 바뀌게 했다)
- 다른 곳에서 만든 DB 객체를 트리에 바로 넣기(`ADD` 이벤트), 활성 프로젝트 전환.
- 이름 규칙 설정(`navigator.settings.default.connectionPattern`) 읽기.

## 구현 현황 — E단계

E단계의 목표는 SSMS처럼 SQL 파일을 열고 저장하고, 편집기에서 연결과 기본 DB/스키마를 바꾸고, Object Explorer에서 스크립트를 만들고,
결과를 복사·내보내는 것이다. MVP 6번(Script as / DDL 보기)과 7번(결과 내보내기 중 CSV)을 구현했다.

### 구성

| 클래스 | 역할 |
|---|---|
| `SqlFileContent` (새) | SQL 파일 하나의 내용과 저장 형식(문자 집합, BOM, 줄바꿈). 읽기·쓰기 |
| `RecentFiles` (새) | File > Recent Files 목록(최근 10개) 저장 |
| `ObjectScripts` (새) | Script as / Select Top / View DDL의 SQL 만들기(모델의 SQL 생성기) |
| `DdlViewerPanel` (새) | View DDL 읽기 전용 문서 탭 |
| `ResultSetExport` (새) | 결과 표 복사(머리글 포함/미포함), CSV 내보내기 |
| `SqlEditorPanel` (변경) | 연결 없는 편집기, 연결 콤보, 기본 DB/스키마 콤보, 파일 내용·고침 표시, 편집기별 최대 행 수 |
| `MainFrame` (변경) | File 메뉴(Open File, Recent Files, Save, Save As), 닫을 때·끝낼 때 저장 확인, 스크립트 편집기·DDL 탭, 연결 변경을 편집기에 알림 |
| `ObjectExplorerPanel` (변경) | Select Top 1000 Rows, Script as, View DDL 메뉴 |
| `ResultSetGrid` (변경) | 오른쪽 클릭 메뉴(Copy, Copy with Headers, Export to CSV…) |

### 메뉴와 단축키

| 위치 | 항목 | 단축키 |
|---|---|---|
| File, 도구 막대 | Open File… | Ctrl+O |
| File | Recent Files ▸ (최근 10개, 번호 1–9가 니모닉) | |
| File, 도구 막대 | Save | Ctrl+S |
| File | Save As… | |
| File | Exit (고친 편집기가 있으면 저장 확인) | |
| Object Explorer 오른쪽 클릭 | Select Top 1000 Rows | |
| Object Explorer 오른쪽 클릭 | Script as ▸ CREATE To ▸ New Query Editor Window / Clipboard | |
| Object Explorer 오른쪽 클릭 | Script as ▸ SELECT To ▸ New Query Editor Window / Clipboard | |
| Object Explorer 오른쪽 클릭 | View DDL | |
| 결과 표 오른쪽 클릭 | Copy | Ctrl+C |
| 결과 표 오른쪽 클릭 | Copy with Headers | Ctrl+Shift+C (SSMS와 같음) |
| 결과 표 오른쪽 클릭 | Export to CSV… | |

### SQL 파일

- **열기:** File > Open File(Ctrl+O, `*.sql` 필터) 또는 Recent Files. 새 편집기 탭으로 열고 탭 제목은 SSMS처럼 `파일 이름 - 연결 이름`, 도구 설명은 전체 경로다.
  연결은 Object Explorer에서 고른 연결(연결 아래 어느 노드든), 없으면 활성 편집기의 연결, 그것도 없으면 연결 없이 연다(`파일 이름 - not connected`).
  연결 없는 편집기는 도구 막대의 연결 콤보로 고르고, 고르기 전에 F5를 누르면 안내 메시지를 띄운다. 이미 열린 파일을 다시 열면 그 탭을 보여 준다.
  없는 파일(최근 파일에서 지워진 것)은 알리고 목록에서 뺀다.
- **문자 집합(`SqlFileContent`):** BOM(UTF-8, UTF-16 LE/BE)이 있으면 그 문자 집합으로 읽고, 저장할 때 BOM을 다시 쓴다. BOM이 없으면 UTF-8로 엄격하게 읽고,
  잘못된 바이트가 있으면 운영체제 기본 문자 집합(`native.encoding`, 한국어 Windows는 MS949)으로 읽는다. 대체 문자로 읽은 뒤 저장해 원본을 망가뜨리지 않기 위해서다.
  저장할 때 그 문자 집합으로 쓸 수 없는 글자가 있으면 아무것도 쓰지 않고 오류를 보여 준다. 새 쿼리는 UTF-8(BOM 없음)이다.
- **줄바꿈:** 편집기는 `\n`만 다루므로 읽을 때 첫 줄바꿈이 `\r\n`이면 CRLF로 기억했다가 저장할 때 되돌린다. 새 쿼리는 플랫폼 줄바꿈(Windows CRLF, SSMS와 같음)이다.
- **저장:** File > Save(Ctrl+S)는 파일이 있으면 그 파일에, 없으면(새 쿼리) Save As처럼 묻는다. Save As는 현재 이름(`SQLQueryN.sql` 또는 파일 이름)을 제안하고,
  확장자가 없으면 `.sql`을 붙이고, 다른 파일을 덮어쓸 때 확인한다. 저장하면 탭 제목이 파일 이름으로 바뀌고 최근 파일 맨 앞에 들어간다.
  파일 대화상자의 폴더는 실행 중에만 기억한다.
- **고침 표시:** 문서가 바뀌면(입력·삭제) 탭 제목 끝에 `*`(SSMS와 같음). 파일을 열거나 스크립트로 채운 직후와 저장한 뒤에는 없다.
  되돌리기로 원래 내용이 되어도 `*`는 남는다(SSMS와 같음).
- **닫을 때 확인:** 고친 편집기 탭을 닫으면 그 탭을 보여 주고 "Save changes to …?" Save / Don't Save / Cancel. Save에서 파일 선택을 취소하거나 저장에 실패하면 닫지 않는다.
- **끝낼 때 확인:** 창 닫기와 File > Exit는 고친 편집기를 모두 모아 한 번에 묻는다(SSMS "Save changes to the following items?"). Save는 하나씩 저장하고
  하나라도 취소·실패하면 끝내지 않는다. 그래서 메인 창은 `DO_NOTHING_ON_CLOSE`로 닫기를 직접 처리한다. 외부 종료 요청(`IApplication.stop`)은 묻지 않고 닫는다.
- **최근 파일(`RecentFiles`):** 이 번들 설정 저장소의 `sqlEditor.recentFiles`(경로를 줄바꿈으로 이음)에 열거나 저장할 때마다 바로 저장한다(비정상 종료에도 남게). 최대 10개.

### 편집기 연결과 기본 DB/스키마

- **연결 콤보:** 메인 창 SQL Editor 도구 막대(실행·계획 단추 오른쪽, 2026-11-07부터. 활성 쿼리 창의 콤보를 끼움)에 활성 프로젝트의 연결 목록(숨김·임시 연결 제외, 이름순, 드라이버 아이콘)을 보여 준다.
  고르면 그 편집기의 연결이 바뀌고 탭 제목이 바뀐다. 연결은 C단계처럼 실행할 때 필요하면 연다. 목록은 콤보를 열 때마다 다시 채워 연결 추가·삭제·이름 변경을 반영한다.
  실행 중에는 콤보를 끈다(실행 중인 Job은 시작할 때의 연결을 쓴다).
- **기본 DB/스키마 콤보 (SSMS "Available Databases"):** SWT의 활성 스키마 선택(`SelectActiveSchemaHandler`)과 같은 모델 API를 쓴다.
  - 목록과 현재 값: 모델의 `ContextDefaultObjectsReader`(`com.seizia.chromite.model.impl.struct`)를 작업 스레드에서 돌린다. 주 자식 형식이 카탈로그면 카탈로그 목록
    (카탈로그 아래 스키마가 있고 스키마를 바꿀 수 있으면 현재 카탈로그의 스키마 목록), 스키마면 스키마 목록이다. SWT처럼 `DBSObjectContainer`인 것만 보이고,
    기본 객체 또는 그 부모를 고른다.
  - 바꾸기: `DBExecUtils.setExecutionContextDefaults(monitor, 데이터 소스, 문맥, 새 카탈로그, 현재 카탈로그, 새 스키마)`를 SWT와 같은 인자로 부른다
    (카탈로그를 고르면 새 카탈로그 = 그 이름, 스키마를 고르면 새 카탈로그 = 현재 카탈로그·새 스키마 = 그 이름). 실패하면 오류 대화상자를 띄우고 다시 읽는다.
  - 바꾼 기본값은 그 창의 실행 문맥에 적용된다. 2026-11-07부터 쿼리 창마다 자기 세션을 쓰므로(기본) 그 창에만 적용된다. 창들이 연결의 주 문맥을 함께 쓰는 설정이면
    같은 연결의 그 창들에도 적용된다([transactions.md](transactions.md) "쿼리 창마다 별도 연결").
  - 연결되어 있지 않거나, 문맥에 기본값 기능(`DBCExecutionContextDefaults`)이 없거나, 카탈로그·스키마를 바꿀 수 없으면 `< N/A >`로 끈다. SQLite가 그렇다.
  - 다시 읽는 때: 편집기를 열 때, 연결을 바꿀 때, 실행이 끝날 때(연결이 열렸거나 `USE`로 바뀌었을 수 있음), 연결 레지스트리 이벤트(연결, 끊기, 이름 바꾸기).
  - SWT가 하는 것 중 안 한 것: 트랜잭션 모드에서 기본값 변경이 트랜잭션에 속하는 DB(`isDefaultsChangeTransactional`)의 커밋 확인, 다른 인스턴스(카탈로그)로 넘어가 스키마 고르기.
- **연결 변경 알림:** `MainFrame`이 이미 듣던 연결 레지스트리 이벤트 중 `OBJECT_UPDATE`(연결, 끊기, 설정 저장·이름 바꾸기)를 그 연결을 쓰는 편집기에 알려
  탭 제목과 기본 스키마 콤보를 갱신한다. D단계에서 남긴 "연결 이름을 바꿔도 탭 제목이 바뀌지 않음"이 이것으로 해결되었다.

### Script as, Select Top 1000 Rows, View DDL (`ObjectScripts`)

SWT의 "Generate SQL" 메뉴가 쓰는 모델 계층 생성기(`com.seizia.chromite.model.sql.generator`, UI 의존 없음)를 그대로 쓴다. 모두 DB 메타데이터를 읽으므로
진행 대화상자(`ProgressDialog`, 300ms 뒤에 보임, Cancel)의 작업 스레드에서 만든다.

| 메뉴 | 대상 (노드의 객체) | 만드는 방법 |
|---|---|---|
| Script as > CREATE To | `DBPScriptObject`(테이블, 뷰, 프로시저, 함수, 트리거, 인덱스 등. 연결·폴더 제외) | `SQLGeneratorDDL`(SWT "Generate SQL > DDL"): 테이블·뷰는 `DBStructUtils.generateTableListDDL`, 그 밖은 `getObjectDefinitionText` |
| Script as > SELECT To | `DBSEntity`이면서 `DBSDataContainer`(테이블, 뷰) | `SQLGeneratorSelect`(SWT "Generate SQL > SELECT"): 숨은 열을 뺀 열 목록, 완전한 이름 |
| Select Top 1000 Rows | 위와 같음 | SELECT + 방언의 행 수 제한 → 새 편집기에서 바로 실행 |
| View DDL | Script as CREATE와 같음 | 같은 DDL을 읽기 전용 탭(`DdlViewerPanel`, 제목 `객체 이름 [DDL]`)에 보임 |

- **행 수 제한 문법:** 방언 이름으로 분기하지 않고, 실행 엔진이 최대 행 수를 SQL에 넣을 때 쓰는 연결의 `DBCQueryTransformProvider.createQueryTransformer(RESULT_SET_LIMIT)`로
  만든 SELECT를 바꾼다(`setParameters(0, 1000)` → `transformQueryString`). SQL Server는 `QueryTransformerTop`(`SELECT TOP 1000 …`), SQLite·MySQL·PostgreSQL 등은
  `QueryTransformerLimit`(`… LIMIT 1000`)이다. 변환기가 없거나 적용할 수 없으면 SELECT를 그대로 두고 가져오기 단계에서 자른다. 끝에 방언의 기본 구분자를 붙인다.
- **편집기 최대 행 수:** 편집기는 연결의 최대 행 수(`resultset.maxrows`, 기본 200)까지만 가져오므로 `LIMIT 1000`이어도 200행에서 멈춘다.
  그래서 Select Top으로 연 편집기는 최대 행 수를 1000(연결 설정이 더 크면 그 값)으로 올린다(`SqlEditorPanel.setMaxRows` → `SQLQueryExecuteJob.setResultSetLimit`).
  이 편집기에서 나중에 실행하는 쿼리에도 같은 한도가 적용된다.
- **New Query Editor Window:** 노드의 연결로 새 편집기(`SQLQueryN.sql`)를 열고 스크립트를 채운다. 만든 직후에는 고침 표시가 없다(다시 만들 수 있는 내용).
  **Clipboard:** `DBPPlatformUI.copyTextToClipboard`.
- **알려진 차이:** `SQLGeneratorDDL`은 `OPTION_INCLUDE_OBJECT_DROP`을 켠다. 이 옵션을 읽는 것은 지금 MySQL 테이블(`MySQLTableManager`)뿐이라
  MySQL 테이블의 CREATE 스크립트에는 SWT처럼 `DROP TABLE` 문이 앞에 붙는다(SSMS의 "CREATE To"에는 없음). 생성기 옵션(완전한 이름, 주석 포함 등)은 기본값이다.

### 결과 복사와 CSV 내보내기 (`ResultSetExport`)

- **Copy (Ctrl+C):** C단계와 같은 `JTable` 전송 처리기(선택한 칸의 표시 문자열, 탭·줄바꿈 구분). 메뉴 항목은 같은 동작을 부른다.
- **Copy with Headers (Ctrl+Shift+C):** 선택한 행·열의 열 이름 줄을 앞에 붙여 같은 형식으로 복사한다.
- 오른쪽 클릭한 칸이 선택 밖이면 그 칸을 선택하고 메뉴를 연다.
- **Export to CSV…:** 파일 대화상자 옆에 옵션(열 머리글 포함, 구분자 쉼표·세미콜론·탭, UTF-8 BOM(Excel용), 수식 이스케이프)과 안내 문구를 둔다.
  안내: "이 표가 이미 가져온 N행을 내보내며 쿼리를 다시 실행하지 않는다", 최대 행 수에서 멈춘 결과는 "그 뒤 행은 내보내지 않는다"를 굵게. 옵션은 실행 중에 기억한다.
  - 쓰기는 진행 대화상자의 작업 스레드에서 한다(1000행마다 진행·취소 확인). 취소하거나 실패하면 쓰다 만 파일을 지운다.
  - 형식은 RFC 4180: 구분자·큰따옴표·CR·LF가 있는 값만 큰따옴표로 감싸고 안의 큰따옴표는 두 번 쓴다. 줄 끝은 CRLF, 문자 집합은 UTF-8. NULL은 빈 칸(`DataExporterCSV` 기본값과 같음).
  - 수식 이스케이프(기본 켬, `DataExporterCSV`의 기본 동작과 같은 규칙): `= + - @`, 탭, CR, LF(전각 포함)로 시작하는 값 앞에 `'`를 붙인다(CSV injection 방지). 숫자 열과 NULL은 건드리지 않는다.
- **`DataExporterCSV`(data.transfer)를 쓰지 않은 이유:** `data.transfer` 번들은 Swing 제품에 들어 있지만(`com.seizia.chromite.runtime.feature`), 이 내보내기는 원래 값(`Object[]`),
  열 바인딩(`DBDAttributeBinding[]`), LOB 읽기용 열린 세션(`exportRow(DBCSession, …)`)이 필요하다. Swing 결과 표는 C단계 설계대로 작업 스레드에서 값을 표시 문자열로 바꾸고
  원래 값을 놓아 주므로(`ResultSetGridReceiver`) 그 입력이 없다. 원래 값을 결과 표에 계속 들고 있게 바꾸는 것은 LOB·세션 수명 문제가 커서, 화면에 보이는 값을 그대로 쓰는 쪽을 골랐다.
  그래서 숫자·날짜는 화면 표시 형식이다(숫자 묶음 기호는 기본 설정에서 꺼져 있음, `DataFormatterProfile`).
- **XLSX 내보내기: 보류(F단계에서 구현, "구현 현황 — F단계").** 모델의 XLSX 내보내기(`DataExporterXLSX`, `com.seizia.chromite.data.office`)는 SWT 의존이 없지만, 그 번들과 Apache POI 번들(`org.jkiss.bundle.apache.poi`)은
  `com.seizia.chromite.ext.office.feature`에만 있고 Swing 피처(`com.seizia.chromite.swing.feature`)에는 없다. 피처를 바꾸는 작업은 이번 범위(Swing 플러그인) 밖이고,
  위와 같은 이유로 원래 값도 필요하다. 메뉴에 넣지 않았다.

### 확인

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 129개, macOS arm64 130개(D단계와 같음)이고,
  이름에 `swt`, `jface`, `org.eclipse.ui`, `org.eclipse.e4.ui`가 들어간 번들은 둘 다 0개다. 새 번들 의존은 없다(`data.transfer`·`data.office`를 쓰지 않음).
- Windows에서 in-process JVM(`-vm …\bin\server\jvm.dll`)으로 C단계 작업 공간 사본에 SQLite 연결 하나(`Chinook Copy`, Album 1번 제목을 `Copy: …`로 바꾼 사본)를 더해 열고
  키보드·마우스로 확인했다:
  - **파일:** Ctrl+O로 CRLF·BOM 없는 `test1.sql`을 Object Explorer 선택 없이 열기 → `test1.sql - not connected`, 기본 스키마 `< N/A >`. F5 → "연결을 먼저 고르라"는 안내.
    연결 콤보에서 `Chromite Sample Database` → 제목이 바뀌고 F5로 3행. 한 줄 입력 → 제목 끝 `*`. Ctrl+S → `*` 사라짐, 파일이 CRLF·BOM 없이 저장됨(바이트 확인).
    다시 입력 후 탭 닫기 → "Save changes to test1.sql?" → Cancel이면 탭이 남고, Don't Save면 닫히며 파일에는 저장한 내용만 있음.
    Object Explorer에서 연결을 고른 뒤 File > Recent Files > `1 …test1.sql` → 그 연결로 다시 열리고 저장한 내용이 그대로.
    UTF-8 BOM·LF·한글 주석 파일을 열어 고치고 저장 → BOM(`EF BB BF`), LF, 한글이 그대로.
  - **연결 바꾸기:** 같은 편집기에서 연결 콤보를 `Chinook Copy`로 바꾸고 F5 → 1행 제목이 `Copy: For Those About To Rock…`(사본 DB에서 실행), 탭 제목도 바뀜.
    SQLite는 카탈로그·스키마 기본값이 없어 기본 스키마 콤보는 `< N/A >`로 꺼져 있다.
  - **Script as > CREATE To > New Query Editor Window (Album):** `SQLQuery1.sql`에 `CREATE TABLE [Album] (…)`(기본 키, 외래 키, 인덱스 포함), 고침 표시 없음.
  - **View DDL (Album):** `Album [DDL]` 읽기 전용 탭, 같은 DDL. 이 탭에서는 아래 Results/Messages가 자리 표시로 바뀜.
  - **Select Top 1000 Rows (Track, 3503행):** `SELECT TrackId, Name, … FROM Track LIMIT 1000;`(SQLite 변환기의 `LIMIT`)이 바로 실행되어 1000행(연결 최대 행 수 200을 넘음).
  - **CSV (Album, Select Top으로 347행):** 결과 표 오른쪽 클릭 → Export to CSV… → 옵션과 "347 rows already fetched … not run again" 안내 → 저장.
    파일: UTF-8 BOM, 머리글 `AlbumId,Title,ArtistId`, 348줄, 줄 끝 CRLF, 쉼표가 있는 제목만 큰따옴표(`54,"Chronicle, Vol. 1",76`).
    특수 값 쿼리(`'a "q" b'`, `'x,y'`, NULL, `'=1+1'`, 줄바꿈이 든 값, `-5`)를 내보내면 `"a ""q"" b","x,y",,'=1+1,"l1␊l2",-5`.
  - **복사:** Ctrl+Shift+C는 머리글 줄 + 탭 구분 값, Ctrl+C는 값만(같은 형식).
  - **끝내기:** 고친 편집기가 있는 상태에서 File > Exit → "Save changes to the following items? SQLQuery3.sql" → Cancel이면 앱이 남고, Don't Save면 종료.
- 디버그 로그(`.metadata/dbeaver-debug.log`)에 ERROR/WARN/예외 없음. Eclipse 로그(`.metadata/.log`)에 새 항목 없음.
- 확인하지 못한 것: 기본 스키마 바꾸기(카탈로그·스키마가 있는 서버형 DB가 없음, 모델 코드 경로만 확인), SQL Server `TOP` 변환, MySQL 테이블 CREATE의 DROP 문, macOS 실행.

### E단계에서 남긴 것

- XLSX 내보내기(위 "결과 복사와 CSV 내보내기", F단계에서 구현), "다시 실행해서 모든 행 내보내기"(지금은 가져온 행만).
- 기본 스키마: 트랜잭션에 속하는 기본값 변경의 커밋 확인, 다른 카탈로그의 스키마 고르기. 서버형 DB에서 실행 확인.
- ~~편집기별 별도 연결(SSMS는 쿼리 창마다 연결) — 기본 스키마가 같은 연결의 모든 편집기에 적용되는 원인.~~ 2026-11-07 고침([transactions.md](transactions.md) "쿼리 창마다 별도 연결").
- 파일: 다른 프로그램이 바꾼 파일 다시 읽기, 문자 집합 직접 고르기(Save with Encoding), 파일 끌어다 놓기, 프로젝트 `Scripts` 폴더 연동.
- Script as의 DROP / ALTER / INSERT / UPDATE / DELETE, Edit Top 200 Rows(결과 편집, G단계에서 구현), 생성기 옵션 설정 화면.
  (2026-11-07: INSERT / UPDATE / DELETE 등은 "SQL 생성" 메뉴, DROP 스크립트는 삭제의 미리 보기로 옮김. "Object Explorer 개체 작업" 절)

## 구현 현황 — F단계

F단계의 목표는 DataGrip의 핵심 강점인 SQL 자동완성과, MVP 7번의 남은 항목인 결과 표 Excel(XLSX) 내보내기다.

### 구성

| 클래스 | 역할 |
|---|---|
| `SqlCompletionAnalysis` (새) | 제안 계산(작업 스레드). 커서 위치의 문장을 파서로 찾아 의미 모델을 만들고 model.sql의 `SQLQueryCompletionAnalyzer`를 돌린다 |
| `SqlCompletionPopup` (새) | 자동완성 팝업과 활성화(Ctrl+Space, `.`, 글자 입력), 비동기 분석·취소, 입력에 따른 거르기, 넣기 |
| `SqlEditorPanel` (변경) | 팝업 연결, `complete()`, 탭을 닫으면 분석 취소 |
| `MainFrame` (변경) | Query > Complete(Ctrl+Space), Query > Auto-Complete While Typing |
| `SQLScriptBatches` (변경, model.sql) | `createParserContext`를 공개(자동완성이 실행과 같은 파서 문맥을 씀) |
| `ResultSetGridReceiver` (변경) | 숫자·불리언·날짜 원래 값과 열 바인딩을 표시 문자열과 함께 넘김 |
| `ResultSetGrid` (변경) | `Data`에 원래 값·바인딩, 오른쪽 클릭 "Export to Excel (XLSX)…" |
| `ResultSetExport` (변경) | XLSX 내보내기(`DataExporterXLSX`), CSV와 같은 파일 대화상자·안내 문구를 함께 씀 |
| `com.seizia.chromite.swing.feature` (변경) | `com.seizia.chromite.data.office`, `org.jkiss.bundle.apache.poi`와 Apache Commons 번들 6개 |

### 자동완성: 분석기 선택 근거 (SWT 편집기 조사)

- **SWT가 쓰는 분석기:** `SQLCompletionProcessor.computeCompletionProposals`([SQLCompletionProcessor.java:177-233](../../plugins/com.seizia.chromite.ui.editors.sql/src/com/seizia/chromite/ui/editors/sql/syntax/SQLCompletionProcessor.java))는
  설정 `SQLEditor.ContentAssistant.experimental.mode`(기본값 `NEW`, `SQLEditorPreferencesInitializer.java:86`)가 새 엔진을 켜고, 고급 강조·메타데이터 읽기 설정이 켜져 있고(둘 다 기본 켬, 같은 파일 :115-116),
  방언이 `BasicSQLDialect`이면 새 엔진 `SQLQueryCompletionAnalyzer`(model.sql `semantics.completion`)를 쓴다. 예전 엔진 `SQLCompletionAnalyzer`는 모드가 `DEFAULT`/`COMBINED`이거나 새 엔진 조건이 안 맞을 때만 쓴다.
  `SQLDialect`를 구현하는 유일한 추상 클래스 `AbstractSQLDialect`의 하위 클래스는 `BasicSQLDialect` 하나라 모든 방언이 조건을 만족한다. 그래서 **SWT 기본 설정은 새 엔진만 쓴다.** Swing 편집기도 새 엔진만 쓴다.
- **실행 방식:** SWT는 분석기마다 시스템 Job(`ProposalSearchJob`/`NewProposalSearchJob`)을 예약하고 `DBExecUtils.tryExecuteRecover`(연결 끊김 복구)로 감싼다. UI 스레드는 `UIUtils.waitJobCompletion`으로 이벤트를 돌리며 기다리고,
  그동안 커서가 움직였으면(`evaluateJobResult`) 결과를 거르거나 Job을 다시 만든다. 시간 제한은 없고 취소만 있다.
- **요청 만들기:** `new SQLCompletionRequest(completionContext, document, offset, editor.extractQueryAtPos(offset), simpleMode)` + `setContentType(분할 영역)`. `simpleMode`는 자동 실행일 때 켠다.
  커서 바로 앞이 한 줄 주석·제어 명령이면 제안하지 않고, 커서 위치가 일반 SQL·문자열·따옴표 이름일 때만 분석한다.
- **완성 문맥:** 새 엔진은 SWT 편집기의 백그라운드 파싱 Job이 만든 의미 모델에서 문맥을 얻는다(`SQLBackgroundParsingJob.obtainCompletionContext`). Swing 편집기에는 그 Job이 없어서,
  요청마다 커서가 있는 문장 하나를 `SQLScriptParser.extractQueryAtPos`로 찾아 SWT 파싱 Job과 같은 인자로 `SQLQueryModelRecognizer.recognizeQuery`를 부르고
  `SQLDocumentSyntaxContext` → `SQLQueryCompletionContext.prepareCompletionContext`로 문맥을 만든다(모델 계층만 쓰는 선례: `model.lsp`의 `LspSQLCompletionContextParser`).
  문장 밖(빈 줄, 문장 사이)은 `prepareOffquery`(문장 첫 키워드)다.
- **완성 옵션(`SQLCompletionContext`):** SWT 설정 키(`SQLPreferenceConstants`)는 SWT 번들에 있고 Swing 제품에는 설정 화면이 없으므로 SWT 기본값을 그대로 옮겼다
  (이름 안 어디든 일치, 이름순 정렬, 제안 뒤 공백, 값 제안 끔, 프로시저 검색 끔 등). 짧은/완전한 이름 형식은 모델 설정(`SQLModelPreferences`)을 연결 설정에서 읽는다.
  메타데이터 읽기·함수 검사도 SWT 기본값(켬/끔)이다. 모델 계층에는 이 둘의 기본값이 없어 설정을 그대로 읽으면 꺼져서 테이블·열을 찾지 못한다. 연결의 `META_DISABLE_EXTRA_READ`는 SWT처럼 따른다.
- **안 한 것:** 스크립트 변수(`@set`) 추적(편집기 단위 변수 유지는 2026-11-08에 생겼지만 자동완성은 그 변수를 제안하지 않음), 제어 명령(`@`) 이름 제안, 템플릿, 제안 설명 창(설명은 목록 도구 설명으로만 보임).

### 자동완성: 동작

| 동작 | 내용 |
|---|---|
| Ctrl+Space (편집기, Query > Complete) | 커서 위치의 제안. 연결되어 있지 않으면 F5처럼 연결한다. 제안이 하나뿐이면 목록 없이 바로 넣는다(SWT 기본 "단일 제안 자동 삽입") |
| `.` 입력 | 별칭·스키마 뒤 이름 목록(항상 켬) |
| 글자·`_` 입력 | Query > Auto-Complete While Typing이 켜져 있으면(기본 켬, SWT도 기본 켬) 입력이 멈춘 뒤 자동 실행. 연결은 열지 않는다 |
| 위·아래, Page Up/Down | 목록 이동 |
| Enter, Tab, 두 번 누르기 | 넣기 |
| Esc | 닫기(분석 중이면 취소) |

- **비동기와 취소:** 분석은 시스템 Job에서 하고 EDT는 기다리지 않는다. 자동 실행은 마지막 입력 뒤 지연 시간(기본 200ms)이 지나야 시작하고, 분석 중에 문서가 바뀌면 그 Job을 취소하고
  지연 시간 뒤 새 위치에서 다시 분석한다(계속 입력하는 동안에는 분석을 끝까지 돌리지 않음). 요청 번호로 늦게 끝난 이전 결과를 버린다.
- **목록이 뜬 뒤:** 이름 글자를 더 입력하거나 지우면 다시 분석하지 않고 받은 제안을 거른다. 일치·점수는 엔진의 `SQLQueryCompletionProposal.validate`, 정렬은 SWT 팝업과 같은
  `SQLCompletionProposalComparator`(점수 → 이름 → 종류)다. 남는 제안이 없거나, 이름이 아닌 글자를 입력하거나, 커서가 단어 앞으로 가거나, 편집기가 초점을 잃거나 창이 움직이면 닫는다. `.`을 입력하면 닫고 새로 분석한다.
- **넣기:** SWT 새 엔진 제안(`SQLEditorQueryCompletionProposal.apply`)과 같이 교체 시작 위치부터 커서까지를 엔진이 만든 글자로 바꾸고 커서를 그 뒤에 둔다.
  따옴표가 필요한 이름의 따옴표, 대소문자, 테이블 별칭(`Album a`), 테이블·열 뒤 공백은 엔진(`SQLQueryCompletionTextProvider`, `prepareReplacementString`)이 정한다. 되돌리기 한 번에 취소되게 한 편집으로 묶었다.
  뒤 공백은 커서 뒤에 이미 공백이 있어도 붙는다(`select a.| from` → `a.Title  from`). SWT 새 엔진도 같은 방식으로 넣는다.
- **표시:** 아이콘은 Object Explorer와 같은 `NodeIcons`로 엔진이 고른 객체 종류 아이콘(테이블, 뷰, 열, 함수, 키워드 등)을 그리고, 오른쪽에 엔진의 꾸밈 글자(열의 테이블 등)를 흐리게 쓴다.
- **팝업 창:** 초점을 가져가지 않는 소유 창(`JWindow`, `focusableWindowState = false`)이라 입력은 편집기로 계속 가고, 목록 키는 편집기의 키 리스너가 키 바인딩보다 먼저 받아 소비한다.
  단어 아래(화면에 자리가 없으면 위)에 뜬다. 테마를 바꾸면 FlatLaf가 모든 창을 다시 그리므로 팝업도 바뀐다.

설정(이 번들 설정 저장소):

| 키 | 기본값 | 뜻 |
|---|---|---|
| `sqlEditor.completion.autoActivateOnLetters` | `true` | 글자·`_` 입력 뒤 자동 실행 (Query > Auto-Complete While Typing) |
| `sqlEditor.completion.autoActivationDelay` | `200` | 자동 실행 전 기다리는 시간(ms). 설정 화면은 없다 |

### AutoComplete 라이브러리를 쓰지 않은 이유

RSyntaxTextArea의 `com.fifesoft:autocomplete`(BSD-3) 3.3.2 소스를 확인했다.

- `AutoCompletion.refreshPopupWindow`(:818-839)가 EDT에서 `CompletionProvider.getCompletions`를 동기로 부르고, 목록이 떠 있는 동안 입력마다 다시 부른다(`AutoCompletePopupWindow.doAutocomplete`, :275).
  DB 메타데이터를 읽는 분석을 EDT 밖에서 취소하며 돌리려면 제공자를 결과 캐시로 바꾸고 활성화를 따로 만들어야 한다.
- `insertCompletion`(:627-650)은 커서 앞 "이미 입력한 글자"(`getAlreadyEnteredText`)만 바꾼다. 엔진 제안의 교체 시작 위치(따옴표 이름, 구조 구분자 뒤 등)를 쓰려면 이것도 바꿔야 한다.
- 제공자·활성화·넣기를 모두 바꾸면 남는 것은 팝업 창뿐이라, 의존성(빌드 때 받기, `Bundle-ClassPath`, 체크섬, NOTICE)을 늘리지 않고 팝업을 직접 만들었다(`SqlCompletionPopup`).

### XLSX 내보내기

- **번들:** `com.seizia.chromite.data.office`(`DataExporterXLSX`, SWT 의존 없음, `Require-Bundle`은 `data.transfer`와 POI뿐)와 `org.jkiss.bundle.apache.poi` 5.4.3
  (POI 5.4.0과 의존 jar를 담은 DBeaver 번들, `Require-Bundle`은 Apache Commons 6개, `Import-Package`는 `org.slf4j`)를 Swing 피처에 넣었다.
  두 번들은 Office 확장 피처 `com.seizia.chromite.ext.office.feature`에만 있고, 이 피처는 SWT 제품에 들어 있지 않다(설치 원본이던 CE 업데이트 저장소 `product/repositories`는 2026-11-07 삭제, [build.md](build.md) "빌드하는 제품").
  피처의 나머지 번들 `com.seizia.chromite.data.office.ui`(SWT)는 넣지 않았다. Swing 번들은 `data.transfer`·`data.office`를 `Require-Bundle`로 쓴다.
  B단계의 JSVG처럼 SWT만 제공하는 능력을 요구하는지 확인했다: 새 번들 매니페스트에 `Require-Capability`가 없고(POI 번들은 `osgi.ee`뿐), 빌드 결과에 SWT 계열 번들이 들어오지 않았다(아래 확인).
- **원래 값을 남기기로 한 근거:** E단계 결과 표는 값을 표시 문자열로만 가지고 있다. 표시 문자열을 다시 숫자로 읽는 방법은 정확하지 않다.
  숫자 표시 형식은 지역 설정의 `NumberFormat`이고(소수점 기호가 지역마다 다름) 최대 소수 자릿수가 10 이상으로 잘린다(`NumberFormatSample` :56, `NumberDataFormatter` :69-80). 날짜도 표시 형식을 알아야 다시 읽을 수 있다.
  그래서 수신기가 결과 집합이 열려 있는 동안 받은 값 중 숫자·불리언·날짜(`java.util.Date`, `java.sql.Time` 제외)만 표시 문자열과 함께 남긴다. 이 값들은 결과 집합·세션과 무관한 불변 객체라
  LOB처럼 수명 문제가 없다. 원래 값이 하나도 없는 행은 배열을 만들지 않는다.
- **메모리 측정 (2026-11-06):** 샘플 DB의 `Track` 1000행(9열: INTEGER 6, NVARCHAR 2, NUMERIC 1)을 JBR 25에서 같은 방식으로 담아 GC 후 힙 차이를 쟀다(20벌 평균).
  표시 문자열 행만 437,280바이트, 원래 값 사본을 더하면 146,950바이트가 늘었다(약 34%). 기본 최대 행 수(200)나 Select Top 1000 기준으로 결과 표 하나에 수백 KB 이내라 받아들였다.
- **쓰기:** 모델의 `DataExporterXLSX`를 그대로 쓴다(머리글 굵게·테두리, 숫자·불리언·날짜 셀, 셀 최대 글자 수, 시트당 최대 행 수 나누기 등 SWT와 같은 결과). 세션·결과 집합 없이 부르도록
  내보내기 사이트(`IStreamDataExporterSite`)를 결과 표 데이터로 구현했다: 열은 결과 집합의 바인딩, 행은 남긴 원래 값이 있으면 그 값, NULL이면 빈 칸, 나머지는 표시 문자열(값 처리기를 다시 거치지 않음).
  속성은 Office 확장의 "Open in Excel"(`OpenSpreadsheetHandler`)과 같은 `DataExporterXLSX.getDefaultProperties()`(행 번호 없음, 얇은 테두리, 기본 시트 이름)에 머리글 포함 여부와 날짜 형식 `yyyy-MM-dd HH:mm:ss`를 더했다.
  기본 속성의 날짜 형식은 빈 문자열이라 날짜 셀이 숫자로 보이고, 이 형식은 엑셀 셀 형식과 엔진이 1900년 이전 날짜에 쓰는 `SimpleDateFormat`에서 뜻이 같다.
- **대화상자:** CSV처럼 저장 대화상자 옆에 "Include column headers"와 "이미 가져온 N행만 내보내며 쿼리를 다시 실행하지 않는다" 안내(최대 행 수에서 멈춘 결과는 굵게 경고).
  쓰기는 진행 대화상자의 작업 스레드에서 하고, 취소·실패하면 파일을 지운다. CSV 동작은 바뀌지 않았다(파일 대화상자·안내 문구 코드만 함께 씀).

### 확인 (2026-11-06)

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 137개, macOS arm64 138개(E단계 130/131 + 7)이고,
  이름에 `swt`, `jface`, `org.eclipse.ui`, `org.eclipse.e4.ui`가 들어간 번들은 둘 다 0개다. 새 번들: `com.seizia.chromite.data.office`, `org.jkiss.bundle.apache.poi` 5.4.3,
  `org.apache.commons.commons-collections4` 4.6.0, `commons-compress` 1.28.0, `commons-io` 2.22.0, `lang3` 3.20.0, `text` 1.15.0
  (`commons-codec`은 이미 있었음). `bundles.info`에 모두 들어 있다.
- Windows에서 in-process JVM(`-vm …\bin\server\jvm.dll`)으로 E단계 작업 공간 사본(샘플 SQLite 연결 2개)을 열어 키보드·마우스로 확인했다. 팝업은 별도 창이라 창별로 찍어 확인했다.
  - **테이블:** `select * from Al` + Ctrl+Space → `Album a`, `Playlist p`, `PlaylistTrack pt`(이름 안 일치, 테이블 아이콘, "- Table") → Enter → `select * from Album a `.
    라이트 테마에서 `select * from A` + Ctrl+Space → 테이블 7개 목록, 아래 화살표 + Enter → `Artist a`.
  - **별칭 뒤 열:** `select a from Album a`에서 `a` 뒤에 `.` 입력 → 자동으로 `AlbumId : INTEGER`, `ArtistId : INTEGER`, `Title : NVARCHAR`(열 아이콘) → `Ti` 입력하면 `Title`, `ArtistId`로 걸러짐 → Enter → `a.Title`.
  - **키워드:** 새 줄에서 `sel` + Ctrl+Space → 제안이 하나라 바로 `SELECT `가 들어감. 글자 입력 자동 실행으로 `up` → `UPDATE`(키워드 아이콘) 목록.
  - **글자 입력 자동 실행:** `select * from Ar`을 입력하고 멈추면 `Artist a`, `PlaylistTrack pt`.
  - **연결 전:** 연결하지 않은 `Chinook Copy`의 새 편집기에서 `select * from Ge` + Ctrl+Space → 연결되고(탐색기에 연결 표시) `Genre g`가 들어감.
  - **테마:** 다크 테마에서 연 편집기를 라이트로 바꾼 뒤 처음 띄운 팝업이 다크 색으로 남는 문제를 찾아 고쳤다(팝업 창을 처음 만들 때 현재 테마로 맞춤). 고친 뒤 라이트 테마 팝업 확인.
  - **XLSX:** Album에서 Select Top 1000 Rows(347행) → 결과 표 오른쪽 클릭 → Export to Excel (XLSX)… → 옵션("Include column headers", "Exports the 347 rows already fetched…") → 저장.
    Python `zipfile` + `xml.etree`로 읽은 결과: 시트 1개, 348행(머리글 `AlbumId`, `Title`, `ArtistId` + 347행), `AlbumId`·`ArtistId`는 모두 숫자 셀, `Title`은 문자열.
    `select InvoiceId, InvoiceDate, BillingState, Total, Total > 10 as big from Invoice limit 5` → `Total`(NUMERIC) `1.98` 등 숫자 셀, NULL인 `BillingState`는 빈 칸,
    `styles.xml`에 날짜 형식 `yyyy-MM-dd HH:mm:ss`. SQLite 드라이버는 `InvoiceDate`(DATETIME)를 문자열로 돌려주어 날짜 셀은 실제로 확인하지 못했다(문자열 셀).
  - **CSV:** 같은 결과의 CSV 내보내기 대화상자·파일 형식이 E단계와 같음(`1,2009-01-01 00:00:00,,1.98,0`).
- 디버그 로그에 ERROR/WARN/예외 없음. 다만 XLSX를 처음 쓸 때 POI가 쓰는 Log4j API가 표준 오류로
  `Log4j API could not find a logging provider.`를 한 번 남긴다(Swing 제품에 Log4j 구현이 없음. 기록만 안 될 뿐 내보내기에는 영향 없음).
- 확인하지 못한 것: 날짜·시각 셀(날짜 객체를 돌려주는 서버형 DB 없음), 큰 스크립트에서의 분석 시간, macOS 실행.


### F단계에서 남긴 것

- 자동완성: 설정 화면(지연 시간, 이름 형식, 대소문자 등), 제안 설명 창(객체 정보), 스크립트 변수·제어 명령·템플릿 제안, 함수 인자 도움말, 편집기별 연결(지금은 연결의 기본 문맥).
  큰 스크립트에서 SWT처럼 의미 모델을 미리 만들어 두는 백그라운드 파싱(지금은 요청마다 커서 문장 하나만 분석).
- XLSX: 시트 이름·날짜 형식·행 번호 등 내보내기 옵션 화면, "다시 실행해서 모든 행 내보내기", 시각(`TIME`) 셀 형식.
  POI의 Log4j API 경고("could not find a logging provider")를 없애려면 Log4j를 slf4j로 보내는 구현 번들이나 실행 옵션이 필요하다(제품 구성 변경이라 이번에는 하지 않음).

## 구현 현황 — G단계

G단계의 목표는 쿼리 결과를 표에서 고치고 DB에 저장하는 것이다(SSMS "Edit Top 200 Rows", DataGrip 그리드 편집, MVP 5번의 남은 항목).

### 구성

| 클래스 | 역할 |
|---|---|
| `ResultSetEditModel` (새) | 편집할 수 있는 결과 하나의 행(지금 값·표시 문자열·처음 값·상태)과 편집 연산. 모델 계층의 `DBDResultSetModel` 구현. 편집 가능 판단(`getReadOnlyReason`) |
| `ResultSetEditPersister` (새) | 모델 계층의 저장 엔진 `DBDResultSetDataUpdater`를 상속. 저장 Job(`DataUpdaterJob` + 트랜잭션), 생성된 키 받기, 저장한 행 다시 읽기 |
| `ResultSetGridReceiver` (변경) | 값 객체가 아닌 원래 값을 `fetchEnd`까지 남기고, `DBExecUtils.bindAttributes`로 열을 테이블 열·키에 묶은 뒤 편집 모델을 함께 넘김 |
| `ResultSetGrid` (변경) | 셀 편집기, NULL·행 추가·삭제, 바뀐 셀 색, 표 위 상태 줄(Save / Cancel, 바뀐 수, 읽기 전용 이유), 저장 흐름 |
| `QueryResultsView` (변경) | 저장 메시지를 Messages에 쓰기, 편집기에 저장하지 않은 변경·저장 중 여부 알리기, 닫기 전 모두 저장 |
| `SqlEditorPanel` (변경) | 다시 실행 전 저장하지 않은 변경 확인, 저장 중 실행 막기 |
| `MainFrame` (변경) | 탭 닫기·앱 끝내기에서 결과 변경도 Save / Don't Save / Cancel로 묻기(E단계 확인 흐름 재사용) |

새 번들 의존은 없다. 저장 엔진(`com.seizia.chromite.model.data.resultset`), 행 다시 읽기(`RowDataReceiver`, `SQLQueryDataContainer`)는 이미 쓰던 `model`·`model.sql` 번들에 있다.

### SWT 편집 구조 조사와 재사용 방식

- **편집 가능 판단 (SWT):** `ResultSetDataReceiver.fetchEnd`가 `DBExecUtils.bindAttributes(session, entity, resultSet, bindings, rows)`를 부른다
  ([ResultSetDataReceiver.java:163](../../plugins/com.seizia.chromite.ui.editors.data/src/com/seizia/chromite/ui/controls/resultset/ResultSetDataReceiver.java)).
  이 함수가 결과 열을 테이블 열(`setEntityAttribute`)에 묶고, `bindUniqueIdentifiers` → `getBestIdentifier`(기본 키 인덱스 → 고유 인덱스 → 제약 조건 → ROWID → 가상 키)로
  테이블마다 `DBDRowIdentifier`를 만들어 각 열에 단다([DBExecUtils.java:759-1030](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/model/exec/DBExecUtils.java)).
  `ResultSetModel.setData`는 `DBExecUtils.detectSingleSourceTable`로 단일 원본 테이블을 정하고(열의 식별자 테이블이 둘 이상이면 없음),
  `ResultSetViewer.isReadOnly`/`getReadOnlyStatus`는 연결 상태(`DBExecUtils.getResultSetReadOnlyStatus`), 키(`isUniqueKeyUndefinedButRequired`)로,
  열 하나는 `DBExecUtils.isAttributeReadOnly`(메타 읽기 전용, 행 식별자, `DBSDataManipulator.FEATURE_DATA_UPDATE`)로 판단한다.
  행 추가·삭제는 원본 테이블의 `FEATURE_DATA_INSERT`/`FEATURE_DATA_DELETE`다([ResultSetViewer.java:2918-2975](../../plugins/com.seizia.chromite.ui.editors.data/src/com/seizia/chromite/ui/controls/resultset/ResultSetViewer.java)).
- **저장 (SWT):** `ResultSetViewer.saveChanges` → `ResultSetPersister`(`DBDResultSetDataUpdater` 상속) → `prepareStatements` → `DataUpdaterJob`.
  엔진은 테이블의 `DBSDataManipulator.insertData/updateData/deleteData`로 문장을 만들고 실행한다(SQL을 직접 만들지 않음). 성공하면 설정 `resultset.edit.refreshAfterUpdate`(기본 켬)에 따라
  추가·수정한 행을 키로 다시 읽는다(`refreshInsertedRows`/`RowRefreshJob`).
- **재사용 방식:** "결과 모델 분석"의 계획 2단계는 `ResultSetModel`·`ResultSetPersister`를 모델 번들로 옮기는 것이었다. 조사해 보니 저장에 필요한 계약은 이미 모델 계층의
  인터페이스(`DBDResultSetModel`, `DBDValueRow`)와 추상 클래스(`DBDResultSetDataUpdater`, `DBDDataStatementInfo`, `DataUpdaterJob`)로 나뉘어 있고,
  `ResultSetPersister`는 그 위에 `ResultSetViewer` 갱신, SWT 대화상자(가상 키 정의, 저장 미리 보기, 0행 경고)를 붙인 것이다. 그래서 SWT 클래스는 옮기지 않고(SWT 결과 뷰어를 바꾸지 않음)
  Swing 번들에서 같은 추상 클래스를 상속해 SWT와 같은 규칙으로 채웠다. 문장 생성·실행·저장점·키 받기는 모델 코드를 그대로 쓴다.

### 편집 가능 판단 (`ResultSetEditModel.getReadOnlyReason`)

수신기(`fetchEnd`, Job 스레드)가 `bindAttributes` 뒤에 차례로 검사한다. 읽기 전용이면 표 위 상태 줄에 `read-only: <이유>`를 쓴다.

| 순서 | 검사 | 이유 문구 |
|---|---|---|
| 1 | `DBExecUtils.getResultSetReadOnlyStatus`(연결 안 됨, 읽기 전용 연결, 읽기 전용 데이터, 편집 권한 없음), `isResultSetReadOnly` | 모델 문구 그대로(예: `Connection is in read-only state`) |
| 2 | `bindAttributes` 실패 | `can't read table metadata (…)` |
| 3 | 단일 원본 테이블 없음(`detectSingleSourceTable`) | 열에 식별자가 있으면 `columns of several tables`(조인), 없으면 `no table columns`(식, 상수, 테이블을 모르는 결과) |
| 4 | 기본 행 식별자(첫 열 순서의 식별자, SWT `getDefaultRowIdentifier`)가 없거나 불완전하거나 유효하지 않음 | `no unique key` |
| 5 | 키 열 값을 읽지 못함(값 객체·읽기 오류) | `key values can't be read` |
| 6 | 원본 테이블이 `FEATURE_DATA_UPDATE`를 지원하지 않음 | `the table doesn't support data changes` |
| 7 | 편집할 수 있는 열이 하나도 없음 | `no editable columns` |

- **열 단위:** 원본 테이블의 열이면서 `DBExecUtils.isAttributeReadOnly(binding, true)`가 거짓이고, 의사 열(ROWID 등)이 아니고, 자료형이 문자열·숫자·불리언·날짜인 열만 편집한다.
  식 열(`upper(Name)`)은 같은 결과 안에서도 읽기 전용이다. LOB·배열·구조체처럼 값 객체로 오는 열은 수신기가 표시 문자열을 만든 뒤 놓아 주므로 편집하지 않는다(글자 편집기로 다룰 수 없음).
- **SWT와 다른 점:** 키가 없을 때 SWT는 가상 키를 정하는 대화상자(`ValidateUniqueKeyUsageDialog`, "모든 열을 키로")를 띄우지만 Swing은 읽기 전용으로 둔다(잘못된 행을 고칠 위험을 피함).
  SWT에서 가상 키를 이미 정해 둔 테이블은 모델이 그 키를 돌려주므로 편집할 수 있다. 조인 결과에서 한 테이블의 열만 고르면(`select a.* from Artist a join Album b …`) 단일 원본이 그 테이블이고
  키가 있으면 SWT처럼 편집할 수 있다(키로 찾으므로 안전하다).
- **수신기 변경:** 값 객체가 아닌 원래 값(문자열 포함)을 `fetchEnd`까지 남긴다. `bindAttributes`가 테이블 열의 값 처리기로 바꾼 열은 남긴 값을 변환하므로 표시 문자열도 다시 만든다.
  편집할 수 있으면 원래 값을 편집 모델에 넘기고, 읽기 전용이면 F단계처럼 XLSX용 값(숫자·불리언·날짜)만 남긴다. `bindAttributes`는 SWT가 모든 결과에 부르는 것과 같아서
  테이블 메타데이터(열, 인덱스, 제약 조건)를 처음 한 번 읽는다(연결 설정 `isExtraMetadataReadEnabled`가 꺼져 있으면 캐시만 씀).

### 편집 동작

| 동작 | 방법 |
|---|---|
| 셀 편집 | 두 번 누르기, F2, 오른쪽 클릭 "Edit Cell". Enter로 끝내고 Esc로 취소 |
| NULL | Ctrl+0, 오른쪽 클릭 "Set NULL"(선택한 칸 모두) |
| 행 추가 | Alt+Insert, 오른쪽 클릭 "Insert Row"(선택한 행 아래, 행 머리글 `*`) |
| 행 삭제 | Delete, 오른쪽 클릭 "Delete Row"(선택한 행. 새 행은 바로 사라지고, 다른 행은 저장 전까지 취소선) |
| 저장 | Ctrl+S(포커스가 표나 셀 편집기에 있을 때), 표 위 Save, 오른쪽 클릭 "Save Changes" |
| 취소 | 표 위 Cancel, 오른쪽 클릭 "Cancel Changes"(바뀐 셀·새 행·지울 행을 모두 되돌림) |

- **셀 편집기:** 값 처리기의 편집 형식(`DBDDisplayFormat.EDIT`)으로 시작하고, 끝낼 때 SWT `StringInlineEditor`처럼 `DBDValueHandler.getValueFromObject(session, 열, 글자, false, true)`로 값을 만든다
  (세션은 결과 실행 문맥의 유틸리티 세션, SWT와 같음). 숫자 열에 숫자가 아닌 글자를 넣으면 편집기에 빨간 테두리를 두르고 상태 줄에 이유를 쓴 뒤 편집을 계속한다.
  글자를 바꾸지 않고 끝내면 값을 바꾸지 않는다(NULL 칸의 빈 편집기가 빈 문자열이 되지 않게). 빈 문자열과 NULL은 값 처리기 규칙을 따른다(숫자 열의 빈 글자는 NULL, 문자열 열은 빈 문자열).
- **글자 입력으로 편집 시작 안 함:** `JTable`은 처리하지 않은 키(F5, Ctrl+N 등 메뉴 단축키 포함)가 오면 메뉴보다 먼저 셀 편집을 시작한다. 그래서 `JTable.autoStartsEdit`를 끄고
  두 번 누르기·F2로만 시작한다.
- **색:** 바뀐 칸(노랑), 새 행(초록), 지울 행(빨강 + 취소선). 다크 테마는 어두운 같은 계열 색이다.
- **상태 줄(표 위):** `Editable (Artist) · 2 pending changes` / `Saving…` / `Save failed: …`. 바뀐 수는 SWT처럼 새 행·지울 행은 하나, 다른 행은 바뀐 칸 수로 센다.
- **Ctrl+S:** 편집할 수 있는 표에 포커스가 있으면 바뀐 것이 없어도 표가 받는다(File > Save의 SQL 파일 저장 대화상자가 뜨지 않게). 읽기 전용 표에서는 지금처럼 SQL 파일 저장이다.
- **복사·내보내기:** 지금 보이는 내용(저장하지 않은 변경 포함)을 쓴다.

### 저장 (`ResultSetEditPersister`)

1. EDT: 열린 셀 편집을 끝내고(값이 틀리면 멈춤) 바뀐 행을 모아(`collectChanges`) SWT `checkEntityIdentifiers`와 같은 규칙으로 검사한다(실패하면 Messages에 쓰고 멈춤).
2. 저장 Job(`DataUpdaterJob`, `DataSourceJob`)에서 `prepareStatements`(DELETE → INSERT → UPDATE 문장 정보)와 엔진의 `executeStatements`를 실행한다. EDT는 기다리지 않는다(저장 중에는 편집과 F5를 막음).
3. **트랜잭션:**
   - 자동 커밋 연결: 저장 동안 자동 커밋을 끄고(`DBCTransactionManager.setAutoCommit(false)`) 모든 문장을 한 트랜잭션으로 실행한 뒤 커밋한다. 하나라도 실패하면 모두 롤백하고 자동 커밋을 되돌린다.
     SWT는 자동 커밋 연결에서 문장마다 커밋되어 중간에 실패하면 앞 문장만 적용되지만, Swing은 "전부 저장되거나 하나도 저장되지 않음"으로 해서 실패하면 바뀐 내용을 모두 그대로 둘 수 있게 했다.
   - 수동 커밋 연결: SWT처럼 열린 트랜잭션 안에서 실행한다. 엔진이 저장점을 잡고 실패하면 저장점까지 롤백한다. 성공하면 커밋은 사용자 몫이라
     Messages에 "manual commit mode … until you COMMIT"을 쓴다(Swing에는 아직 커밋 단추가 없으므로 편집기에서 `COMMIT`을 실행한다).
   - 트랜잭션을 지원하지 않는 연결: 문장마다 바로 적용된다. 실패하면 이미 실행한 문장의 행은 저장된 것으로 반영하고 나머지는 남긴다(SWT와 같음).
4. **INSERT 값:** 원본 테이블 열만 넣는다(`shouldInsertAttribute`). 엔진의 INSERT 묶음이 NULL 값을 빼므로 새 행에서 정하지 않은 열은 DB 기본값·자동 증가 값을 받는다.
   생성된 키는 SWT `KeyDataReceiver`와 같은 규칙(키 이름 → 자동 생성 열)으로 받아 새 행에 넣는다. 더해서 새 행에 기본 행 식별자의 키 값이 없으면 열이 자동 생성으로
   표시되지 않았어도 생성된 키를 요청하고(SQLite의 `INTEGER` 기본 키는 rowid 별칭이지만 모델이 자동 생성 열로 표시하지 않는다), 드라이버가 붙인 이름(`last_insert_rowid()`)의
   키 값 하나는 그 행에 없는 키 열에 넣는다. 그래야 새 행을 키로 다시 읽고 이어서 고치거나 지울 수 있다(SWT는 이 경우 키가 `NULL`로 남는다).
5. **끝(EDT):** 성공하면 Messages에 `Saved changes to Artist: 1 inserted, 1 updated, 0 deleted.`와 저장 시간, 0행 문장이 있으면 경고(SWT의 0행 경고 대화상자 대신)를 쓰고,
   지운 행은 빼고 나머지는 저장된 값을 새 기준으로 삼는다. 실패하면 Messages에 오류(무엇이 롤백되었는지와 DB 오류 메시지)를 빨간색으로 쓰고 Messages 탭을 보이며, 바뀐 내용은 그대로 둔다.
6. **저장 뒤 새로 고침:** SWT 기본값처럼 추가·수정한 행만 키로 다시 읽는다. 원래 쿼리를 키 조건으로 거른 쿼리(`SQLQueryDataContainer.readData` + 방언의 `addFiltersToQuery`)라 열 구성이 같고,
   DB가 정한 값(자동 증가 키, 기본값, 트리거가 바꾼 값)이 보인다. **쿼리를 다시 실행하지 않은 이유:** 편집기 실행은 스크립트 전체(다른 DML 포함)를 다시 실행하게 되고,
   같은 실행의 다른 결과 표와 스크롤·선택 위치가 사라진다. 다시 읽기에 실패해도 저장은 끝났으므로 입력한 값을 그대로 두고 로그만 남긴다. 키를 모르는 새 행(키가 생성되지 않은 경우)은 다시 읽지 않는다.

### 닫기·다시 실행 확인

- **탭 닫기:** 결과 표에 저장하지 않은 변경이 있으면 E단계 확인과 같은 "Save / Don't Save / Cancel"을 먼저 묻는다(`Save changes to the results of SQLQuery1.sql?`).
  Save면 결과 변경을 진행 대화상자에서 기다리며 저장하고, 실패하면 탭을 닫지 않는다(오류는 Messages). 그다음 편집기 글자가 고쳐져 있으면 E단계처럼 다시 묻는다.
- **앱 끝내기:** E단계의 "Save changes to the following items?" 목록에 `Results of SQLQuery1.sql (2 unsaved changes)` 항목을 더한다. Save면 결과 변경을 먼저 저장하고 SQL 파일을 저장한다.
  하나라도 실패하거나 취소되면 끝내지 않는다.
- **저장 중:** 결과를 저장하는 중인 편집기는 닫거나 끝내지 않고 "Try again when the save is over"를 알린다.
- **다시 실행(F5):** 실행하면 결과 표를 새로 만들므로, 저장하지 않은 변경이 있으면 "Discard and Execute / Cancel"을 묻는다. 저장 중에는 실행하지 않는다.

### 확인 (2026-11-06)

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 137개, macOS arm64 138개(F단계와 같음, 새 번들 없음)이고,
  이름에 `swt`, `jface`, `org.eclipse.ui`, `org.eclipse.e4.ui`가 들어간 번들은 둘 다 0개다.
- Windows에서 in-process JVM(`-vm …\bin\server\jvm.dll`)으로 F단계 작업 공간 사본(연결 두 개를 사본 폴더의 DB 파일로 바꿈)을 열어 키보드·마우스로 확인했다.
  편집은 사본 DB(`chinook-copy.db`, `Chinook Copy (SQLite)` 연결)에만 했고, 저장 결과는 Python 표준 `sqlite3`로 DB 파일을 읽기 전용으로 열어 따로 확인했다.
  - **(a) 고치기:** `select * from Artist` → 표 위 `Editable (Artist) · no pending changes` → 2행 Name을 두 번 눌러 `Accept - edited in Chromite G`로 바꾸고 Enter →
    노란 칸, `1 pending change` → 표에서 Ctrl+S → `no pending changes`(선택 위치 유지), Messages `Saved changes to Artist: 0 inserted, 1 updated, 0 deleted.` →
    DB 파일 `(2, 'Accept - edited in Chromite G')` → F5 다시 실행 → 새 값.
  - **(b) 행 추가:** `select * from Artist where ArtistId > 270` → 마지막 행에서 Alt+Insert → 행 머리글 `*`, 초록 행, ArtistId `NULL` → Name `Chromite G New Artist` → Ctrl+S →
    표의 ArtistId가 `276`(생성된 키를 받아 키로 다시 읽음), `1 inserted`, DB 파일 `(276, 'Chromite G New Artist')`.
    첫 시험에서는 저장은 되었지만 표의 ArtistId가 `NULL`로 남았다. SQLite 모델이 rowid 별칭 키(`INTEGER` 기본 키)를 자동 생성 열로 표시하지 않아 엔진이 생성된 키를 요청하지 않았기 때문이다.
    키 값이 없는 새 행은 생성된 키를 요청하고, 드라이버가 붙인 이름(`last_insert_rowid()`)의 키 값 하나는 그 행에 없는 키 열에 넣도록 고쳤다.
  - **(c) 행 삭제:** 276행에서 Delete → 빨간 취소선 행, `1 pending change` → Ctrl+S → 행이 사라짐, `0 inserted, 0 updated, 1 deleted.`, DB 파일에서 275까지만 남음.
    첫 시험에서 실행하지 않은 종류의 수가 `-1`(값이 없는 `DBCStatistics`)로 나오던 것을 고쳤다.
  - **(d) 읽기 전용:** `select ar.ArtistId, ar.Name, al.AlbumId, al.Title from Artist ar join Album al on al.ArtistId = ar.ArtistId` → `read-only: columns of several tables`,
    두 번 눌러도 편집기가 열리지 않음. `select Name, upper(Name) as upper_name from Artist`(키 열 없음) → `read-only: no unique key`.
  - **(e) 오류:** 2행 Name 변경(먼저 실행되는 UPDATE) + 4행 ArtistId `abc` + 6행 Ctrl+0(`NULL`) → `3 pending changes` → Ctrl+S → Messages에 빨간색으로
    "Can't save changes to Artist. The save transaction was rolled back; nothing was saved. Your changes are kept in the grid." + `[SQLITE_MISMATCH] Data type mismatch`,
    Messages 탭으로 전환 → 표에 변경 3개 그대로(`Save failed: 3 pending changes kept (see Messages)`) → DB 파일의 2행 Name은 (a)의 값 그대로(먼저 실행된 UPDATE도 롤백됨)
    → Cancel → 모든 칸이 원래 값, `no pending changes`. 3행 ArtistId를 `1`로 바꿔 저장 → `[SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed (UNIQUE constraint failed: Artist.ArtistId)`, DB 파일 그대로.
  - 숫자 열에 `abc`가 들어간 이유: SQLite 모델의 값 처리기(`SQLiteValueHandler`)는 SQLite의 동적 자료형을 따라 글자를 그대로 값으로 받는다(SWT도 같음). 그래서 편집기의
    "틀린 값" 표시(빨간 테두리, 편집 계속)는 이번 DB로 확인하지 못했다. DB가 거부한 값은 위처럼 저장 오류로 처리된다.
  - **닫기:** 변경 1개가 있는 탭의 × → `Save changes to the results of SQLQuery1.sql?` / `Results of SQLQuery1.sql (1 unsaved change)` → Cancel이면 탭이 남음 →
    다시 × → Save → DB 파일 `(3, 'Saved on close G')` → 이어서 E단계의 SQL 글자 확인 → Don't Save → 탭이 닫힘.
  - **다시 실행·끝내기:** 새 편집기에서 변경 1개를 둔 채 F5 → "The results have 1 unsaved change. Executing the query replaces them. Discard the changes?"(Discard and Execute / Cancel) → Cancel.
    Alt+F4 → `Save changes to the following items?` 목록에 `SQLQuery2.sql`, `Results of SQLQuery2.sql (1 unsaved change)` → Cancel이면 앱이 남고, 다시 Alt+F4 → Don't Save면 끝나며 DB 파일의 4행은 그대로.
- 디버그 로그(`.metadata/dbeaver-debug.log`)에 ERROR/WARN/예외 없음, Eclipse 로그(`.metadata/.log`) 없음.
- 확인하지 못한 것: 값 처리기가 거부하는 글자(서버형 DB의 숫자·날짜 열), 수동 커밋 연결·트랜잭션 없는 연결의 저장, 0행 경고, 다크 테마의 변경 색,
  읽기 전용 이유 중 연결 상태·테이블 기능에 따른 것, macOS 실행.

### G단계에서 남긴 것

- 연쇄 삭제 옵션. 커밋·롤백 명령은 2026-11-07([transactions.md](transactions.md)), 저장하고 커밋은 2026-11-08([swing-grid-editing.md](swing-grid-editing.md)).
  SWT의 저장 미리 보기(`resultset.confirm.beforeSave`, 연결 종류의 "데이터 변경 확인")는 2026-11-07에 옮겼다("저장 전 스크립트 미리 보기").
- 키 없는 테이블의 가상 키 정의, 배열 값 편집기, 글자 입력으로 편집 시작. LOB·JSON 편집, 날짜 선택기·불리언 목록, 여러 칸 붙여넣기, 행 복제, 실행 취소(Ctrl+Z)는
  2026-11-08에 했다([swing-grid-editing.md](swing-grid-editing.md)).
- 원본 테이블이 `FEATURE_DATA_REFRESH_AFTER_UPDATE`를 지원하면 SWT는 저장 뒤 결과 전체를 다시 읽는다(관계형 DB에는 해당 없음). Swing은 행 다시 읽기만 한다.
- 편집기별 별도 연결: 저장은 결과를 읽은 문맥에서 자동 커밋을 잠시 끄므로, 같은 문맥을 쓰는 다른 편집기가 그 사이에 실행한 문장도 저장 트랜잭션에 들어갈 수 있다(SWT도 같은 문맥을 쓰면 같은 문제).
  2026-11-07부터 쿼리 창마다 자기 세션을 쓰면(기본) 다른 창의 문장이 섞이지 않는다. 창들이 주 문맥을 함께 쓰는 설정에서는 그대로다([transactions.md](transactions.md)).

### 저장 전 스크립트 미리 보기 (2026-11-07)

전수 조사 항목 B-R19([swt-parity/B-editors.md](swt-parity/B-editors.md)). SWT `ResultSetViewer.applyChanges`는 설정 `resultset.confirm.beforeSave`가 켜졌거나
연결 유형의 "데이터 변경 확인"(`DBPConnectionType.isConfirmDataChange()`, 시스템 유형 기본값: Test·Production 켬, Development 끔)이 켜졌으면
`SavePreviewDialog`로 저장할 SQL을 보여 주고 Save를 눌러야 저장했다. Swing `ResultSetGrid.save`는 키 검사 뒤 바로 저장 Job을 돌려서,
Test·Production 연결에서도 확인 없이 저장됐다. 설정값과 연결 유형 값은 남아 있었지만 효과가 없었다(조용히 끊김).

**동작**

- 결과 표 저장(Ctrl+S, Save 단추, 메뉴 Save Changes, 창을 닫거나 끝낼 때의 Save)은 키 검사(`checkEntityIdentifiers`) 뒤 조건을 본다
  (`ResultSetGrid.isSavePreviewRequired`). 설정은 SWT처럼 연결의 설정 저장소에서 읽는다. 연결별 값이 없으면 전체 설정을 따른다.
- 조건이 맞으면 저장할 문장을 만들어 `ScriptPreviewDialog.confirmSave`로 보여 준다.
  - 머리글: 연결 이름, 추가·변경·삭제 행 수, "저장하시겠습니까?".
  - 글 상자: 읽기 전용 SQL. 맨 위에 SWT와 같은 뜻의 주석 한 줄("자동 생성한 스크립트, 이진·복합 값은 실제와 다를 수 있음")을 둔다.
  - 단추: Copy, Save, Cancel. 기본 단추는 SWT처럼 Cancel이다(Enter로 바로 저장되지 않게).
- Save: 기존 저장 흐름 그대로다(`createSaveJob`, 트랜잭션 처리와 `finishSave`의 수동 커밋 안내 포함).
- Cancel: 아무 문장도 실행하지 않는다. 바뀐 내용은 표에 남고 상태 줄에 "Save canceled: N pending changes kept"를 보인다.
  창 닫기·끝내기에서 Cancel이면 `save(true)`가 false를 돌려주므로 닫기도 취소된다.
- 문장을 만들지 못하면 저장하지 않고, 저장 실패와 같은 방식으로 Messages에 오류를 쓴다. 문장 만들기를 진행 대화상자에서 취소해도 저장하지 않는다.

**설계**

- 미리 보기 문장은 저장과 같은 엔진으로 만든다. `ResultSetEditPersister.createScriptJob`은 `DataUpdaterJob`의 스크립트 생성 모드(`generateScript = true`)로
  `prepareStatements` 뒤 엔진의 `executeStatements`를 돌린다. 이 모드에서 `DBSDataManipulator.ExecuteBatch`는 문장을 실행하지 않고
  `generatePersistActions`로 동작(`DBEPersistAction`)만 모은다. SWT `ResultSetViewer.generateChangesScript`와 같은 길이고,
  저장 설정(`ResultSetSaveSettings`)도 저장 Job과 같으므로 미리 보기와 실제 저장 문장이 같다. 스크립트 글은 `SQLUtils.generateScript`로 만든다.
- 미리 보기와 저장은 같은 persister 객체를 쓴다. 저장 Job의 `prepareStatements`가 이전 문장과 동작을 지우고 다시 만들기 때문에 미리 보기의 상태가 남지 않는다.
- 미리 보기는 DB에서 아무 문장도 실행하지 않는다(엔진이 세션은 열지만 실행·자동 커밋 전환·저장점은 스크립트 모드에서 건너뜀).
  그래서 트랜잭션 동작은 바뀌지 않았다. 문장 만들기는 백그라운드 Job이고 EDT는 진행 대화상자(`ProgressDialog.waitForJob`)로 기다린다.
- 대화상자는 `ScriptPreviewDialog`를 다시 썼다. 머리글과 실행 단추 이름을 호출자가 정하는 생성자를 더했다. 저장 확인에서는 "Open in Query Window"를 숨긴다.
  바뀐 내용이 표에 남아 있어 쓸모가 적고, 결과 표의 `Host`에는 쿼리 창을 여는 방법이 없다.
- Options > General > Query Results에 "Show the SQL script before saving grid changes"(`resultset.confirm.beforeSave`, 기본 끔)를 두었다.
  (2026-11-08 Options > Query Results 탭으로 옮겼고 연결별 값도 둘 수 있다, [swing-options.md](swing-options.md) "연결별 설정".)
  Maximum rows처럼 플랫폼 설정 저장소에 쓴다. 키 이름은 SWT와 같아서 SWT 시절에 켠 값도 그대로 읽힌다.

**upstream 대비 변경점**

- SWT 결과 도구 막대의 토글(`toggleConfirmSave`)은 옮기지 않았다. Swing 결과 표에는 그 도구 막대가 없고, 설정은 Options에서 바꾼다.
- SWT `SavePreviewDialog`의 연쇄 삭제(Delete cascade, Deep cascade) 선택은 없다. Swing 저장 엔진이 연쇄 삭제를 하지 않기 때문이다
  (`prepareDeleteCascade` 기본 구현은 빈 목록). 연쇄 삭제는 B-R20과 함께 남긴다.
- Swing에는 연결 유형 편집 화면이 없다. 연결 편집의 Connection type 콤보로 유형만 고를 수 있고,
  "데이터 변경 확인" 값은 시스템 유형 기본값이나 `data-sources.json`의 `connection-types.<id>.confirm-data-change`를 따른다.

**확인 (2026-11-07)**

`product-chromite-swing` 빌드, 복사한 작업 공간(`D:\Tools\_scratch\chromite-grid-save-preview\ws`), PostgreSQL 테스트 DB의 3행짜리 테스트 테이블.
화면 조작은 실행 중인 앱 안에서 Swing API로 했다(같은 폴더의 `s*.txt`와 `.log`, 그림은 `shots\`).

- **Test 유형 연결**(복사본에서 연결 유형만 `test`로 바꿈): 칸 하나를 고치고 저장 → "변경 내용 미리 보기"가 열림.
  `UPDATE public.p1c_save_preview SET "name"='alpha-edited' WHERE id=1;`, 단추 복사·저장·취소, 기본 단추 취소(`p1c-test-preview-ko.png`).
  취소 → 표에 `alpha-edited`가 남고 상태 줄 "저장 취소: 보류 중인 변경 내용 1개를 유지함", DB는 그대로 `alpha`.
  이어서 행 하나를 지우고 저장 → 미리 보기에 DELETE와 UPDATE → 저장 → 보류 0개, DB `1|alpha-edited`, `2|beta`.
- **Development 유형 연결, 설정 끔**: 고치고 저장 → 미리 보기 없이 바로 저장, DB에 반영.
- **Development 유형 연결, Options에서 설정 켬**: 미리 보기가 열리고, 취소하면 남고, 저장하면 반영됨. 설정을 다시 끔.
- **영어(`-nl en`)**: Options 항목, "Preview Changes" 머리글·주석·단추(Copy, Save, Cancel), 새 행의 `INSERT INTO public.p1c_save_preview (id,"name") VALUES (4,'delta-en');`,
  취소 뒤 "Save canceled: 1 pending changes kept", DB에 4행 없음.
- 앱 로그에 오류 없음.
- 확인하지 못한 것: 창을 닫거나 끝낼 때의 저장 경로(같은 `save` 메서드), 수동 커밋 연결, SQL Server·Oracle·MariaDB, 다크 테마, macOS.

## 디자인 적용 (2026-11-06)

[design-spec.md](design-spec.md)의 P0 전체와 P1 일부를 적용했다(배치·동작은 SSMS, 모양은 DataGrip New UI 다크·라이트). 항목별 측정값과 못 한 것은 그 문서의 "적용 현황"에 있다.

### 구성

| 파일 | 바뀐 것 |
|---|---|
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | 디자인 토큰 전체(색, 선택, 트리·표·탭·컨트롤 치수, arc, 분할선, 글꼴). `Chromite.*` 키는 Chromite 컴포넌트가 읽는 값 |
| `ChromiteDarkLaf` | 상위 클래스 `FlatDarculaLaf` → `FlatDarkLaf`(Darcula의 삼각 화살표와 밝은 트리·표 바탕을 피함) |
| `ChromiteTheme` | Look and Feel 설치 전에 Inter·JetBrains Mono 글꼴 등록 |
| `UiIcons`(새 파일) | 아이콘 팩 SVG(현재 팩·테마를 그릴 때 고름), 비활성 아이콘, 연결 상태 점, 아이콘 단추 |
| `UiIconStyle`(새 파일) | 아이콘 팩 2종과 저장(View > Theme > Icon Style) |
| `NodeIcons` | 모델 아이콘 → 아이콘 팩 대응, Object Explorer 노드 아이콘(객체 종류, 펼친 폴더, 드라이버 로고 + 상태 점) |
| `ObjectExplorerPanel` | 머리말 아래 아이콘 도구 막대, Filter·Collapse All, 보조 글자, 마우스 올림 표시 |
| `ToolWindow` | 머리말 40, 숨기기 아이콘 단추. 쓰이지 않게 된 머리말 도구 인자 삭제 |
| `MainFrame` | 아이콘 주 도구 막대, 문서 탭(40, 편집기 바탕, 아이콘), 결과 탭(32, 아이콘), 1px 분할선, 상태 표시줄 28, Icon Style 메뉴 |
| `SqlEditorPanel`, `SqlEditorLight.xml`, `SqlEditorDark.xml` | 도구 막대 아이콘(▶ Execute, ■), 탭 아이콘, DataGrip 구문 색, 편집기 글꼴 |
| `ResultSetGrid`, `QueryResultsView` | 그리드 글꼴·격자선·머리글 정렬·행 번호 열·NULL·편집 색, Messages 글꼴·오류 색 |
| `icons/ui/**`, `tools/branding/gen_ui_icons.py` | 아이콘 팩 SVG 188개(2팩 × 2테마 × 47)와 생성기, `icons/ui/LICENSE-tabler.txt` |

### 외부 라이브러리 (디자인 적용 추가)

| 라이브러리 | 버전 | 라이선스 | 출처 |
|---|---|---|---|
| Inter 글꼴 (`com.formdev:flatlaf-fonts-inter`) | 4.1 | 글꼴 SIL OFL 1.1, jar Apache 2.0 | Maven Central. SHA-1 `5b22a28695d69d6865bceb206db1debb729e26ac`(Central `.sha1`과 같음), SHA-256 `053f7274d6489900e53d4ad397323440428733a41b7fe2f642daf3feb7c730e8` 확인 |
| JetBrains Mono 글꼴 (`com.formdev:flatlaf-fonts-jetbrains-mono`) | 2.304 | 글꼴 SIL OFL 1.1, jar Apache 2.0 | Maven Central. SHA-1 `68c779e849aec16c69f69d4405fb1edea04e9b8f`(Central `.sha1`과 같음), SHA-256 `bc8e93479960b2a5e3b45fe724e16439a69f28cc43cac3a2b87b7f7f325fd3a5` 확인 |
| Tabler Icons(모양만 참고해 다시 그림) | 3.50.0 | MIT | 아이콘 파일은 Chromite 생성기 출력. 라이선스 전문을 `icons/ui/LICENSE-tabler.txt`로 함께 배포 |

- 글꼴 jar는 FlatLaf와 같은 방식이다: `pom.xml`의 `maven-dependency-plugin:copy`가 `lib/`에 복사하고 `Bundle-ClassPath`·`bin.includes`에 넣는다. 저장소에는 넣지 않는다. `NOTICE`에 적었다.

### 확인 (2026-11-06)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C '-Pproduct-chromite-swt,product-chromite-swing'` → `BUILD SUCCESS`.
  Swing 제품의 `plugins/`에 `swt`·`jface`·`org.eclipse.ui`·`e4.ui` 번들 0개. Swing 번들 jar에 `lib/flatlaf-fonts-inter.jar`(1,988,916 bytes), `lib/flatlaf-fonts-jetbrains-mono.jar`(537,127 bytes), SVG 188개, `icons/ui/LICENSE-tabler.txt`가 들어 있다.
- 실행(작업 공간 사본 `D:\Tools\_smoke\swing-design`, Windows 11, 150%): 라이트·다크 × 아이콘 팩 A·B 네 조합에서 트리(Tables → Album → 컬럼·Unique 키·인덱스, 뷰 폴더), SQL 편집기와 결과 그리드, Properties를 캡처해 사양 값과 비교했다(측정값은 design-spec.md "적용 현황").
  테마와 아이콘 팩은 실행 중 메뉴로 바뀌고, 다시 시작해도 저장된 값(`DARK`, `COLOR`)으로 열렸다. 연결 대화상자, 자동완성 목록, Filter(마스크 걸기·풀기와 `data-sources.json` 저장), 한글 트리·편집기·그리드, 연결 상태 점(연결됨 초록, 없는 폴더의 SQLite 파일로 연결 실패 빨강)도 확인했다.
- 디버그 로그(`.metadata/dbeaver-debug.log`)에 ERROR/WARN/예외 없음, Eclipse 로그(`.metadata/.log`) 없음.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 그리드 편집 색과 행 번호 클릭, 트리 키 조작(Enter, F2, Delete), 도구 창 숨기기, 테마 전환.

## Connect 대화상자 (SSMS식)

D단계의 2단계 마법사(드라이버 고르기 → 설정, `< Back`/`Next >`)를 SSMS "Connect to Server"처럼 한 화면으로 바꿨다. 모양은 DataGrip New UI 기준의
[design-spec.md](design-spec.md) "11. 대화상자 (연결)"을 따르고, 드라이버 정의로 칸을 만드는 범용 구조(D단계)는 그대로 쓴다.
D단계 절의 "새 연결: 드라이버 고르기", 탭 구성(Main / Driver Properties / Provider Properties), "인증 방식은 고를 수 없음"은 이 절로 바뀌었다.

### 구성

| 클래스 | 역할 |
|---|---|
| `ConnectionDialog` (변경) | 한 화면 대화상자. 제목 `Connect to Server`(편집은 `Edit Connection - 이름`). 단추: 왼쪽 Test Connection, 오른쪽 **Connect**(편집은 Save) / Cancel / `Options >>` |
| `DatabaseTypeCombo` (새로) | 첫 줄 "Database type" 콤보: 주요 DB, 최근 드라이버, "More database types…"(전체 드라이버 검색 창) |
| `DriverSelectionPanel` (변경) | 1단계 화면 → "More database types…" 창의 내용(검색, 범주, 전체 드라이버 목록) |
| `ConnectionSettingsPanel` (변경) | 주 양식 + 접었다 펴는 Options 탭(Connection Properties, Driver Properties, Provider Properties, Additional Connection Parameters) |
| `ConnectionAuthModels` (새로) | Authentication 콤보에 넣을 인증 방식과 방식별 칸(Login, Password) 판단 |
| `ConnectionHistory` (새로) | 드라이버별 최근 서버 이름 10개, 최근 드라이버 10개(이 번들의 설정 저장소) |

`ConnectionPropertiesTable`, `ConnectionCommands`, `SwingDriversService`는 바꾸지 않았다.

### 화면 배치

```
Database type    [SQL Server ▾]            ← 공급자 아이콘
Server name      [localhost        ▾]      ← {host}·{port}: 호스트[:포트], 서버 기록
Database         [master          ]        ← {database} (파일 DB는 File + Browse…, 폴더 DB는 Folder)
                                            (묶음 사이 20)
Authentication   [SQL Server Authentication ▾]   ← 고를 방식이 둘 이상일 때만
Login            [                ]
Password         [                ]
                 [x] Remember password

Connection name  [비우면 DB·서버 이름으로]
[Test Connection]  (빠진 값 안내)          [Connect] [Cancel] [Options >>]
── Options >> 를 누르면 아래에 탭이 펼쳐짐 ──
Connection Properties | Driver Properties | Provider Properties | Additional Connection Parameters
```

- 라벨 끝에 `:`를 붙이지 않는다(SSMS). 간격은 design-spec 토큰: 라벨–입력 6, 줄 사이 6, 묶음 사이 20, 대화상자 안쪽 여백 10/12.
- 색은 코드에 쓰지 않고 테마 키에서 읽는다: 오류 글자 `Chromite.status.error`(없으면 `Component.error.focusedBorderColor`), 보조 글자
  `Chromite.secondaryForeground`(없으면 `Label.disabledForeground`), 콤보 목록 구분선 `Chromite.dividerColor`(없으면 `Separator.foreground`).
  다른 작업의 테마 파일이 이 키를 넣으면 그대로 적용된다.
- 대화상자 높이는 내용에 맞춘다(`pack`). 인증 방식을 바꿔 칸이 숨거나 보이면, Options를 펼치거나 접으면 다시 맞춘다. 폭은 최소 640이고 사용자가 넓힌 폭은 유지한다.
- 포커스는 처음 열 때 첫 입력 칸(Server name, File 등)에 둔다. 드라이버를 바꾸면 포커스는 Database type 콤보에 남는다(글자로 고르는 중에 칸이 바뀌어도 입력이 엉뚱한 칸에 들어가지 않게).

### Database type 콤보 (`DatabaseTypeCombo`)

- 순서: 주요 DB(SQL Server `sqlserver:microsoft`, PostgreSQL `postgresql:postgres-jdbc`, MySQL `mysql:mysql8`,
  MariaDB `mysql:mariaDB`, Oracle `oracle:oracle_thin`, SQLite `sqlite:sqlite_jdbc`) → 최근에 연결을 만든 다른 드라이버 → "More database types…".
  MySQL을 뺀 다섯은 드라이버가 내장돼 있다. MySQL은 2026-11-07부터 내장하지 않지만(처음 연결할 때 다운로드, [drivers.md](drivers.md)) 목록에는 그대로 둔다.
  새 연결은 첫 항목(SQL Server)으로 시작한다(SSMS의 기본 서버 종류와 같음).
- "More database types…"는 D단계의 드라이버 고르기 화면(`DriverSelectionPanel`: 검색, 범주, 전체 드라이버)을 작은 창으로 연다. 고른 드라이버는 콤보의 주요 DB 다음에 들어간다.
- 콤보에 포커스가 있을 때 글자를 치면 이름이 그 글자들로 시작하는 항목으로 간다(1초 안에 친 글자를 이어 붙임. 예: `sqli` → SQLite).
- 목록을 연 채 화살표로 움직이는 동안에는 드라이버를 바꾸지 않고, 목록이 닫힐 때 고른 것으로 바꾼다(화살표마다 설정 화면을 다시 만들지 않게).
- 편집에서는 콤보를 끈다(드라이버를 바꾸는 것은 SWT도 별도 기능이다).
- 결정: 모든 드라이버(100개 이상)를 편집 가능한 콤보에 넣고 글자로 거르는 방식은 쓰지 않았다. Swing의 편집 가능한 콤보는 편집 칸에 공급자 아이콘을 보일 수 없고,
  목록이 길어 찾기 어렵다. design-spec의 "고급 드라이버 목록은 콤보의 More…"와 같은 결정이다.

### 서버 이름과 기록

- 드라이버의 URL 템플릿에 `{host}`가 있으면 Host·Port 두 칸 대신 "Server name" 한 칸이다. `호스트`, `호스트:포트`, SSMS식 `호스트,포트`, `[IPv6]:포트`를 받는다.
  포트를 적지 않으면 드라이버의 기본 포트로 저장하고, 기본 포트와 같은 포트는 칸에 보이지 않는다. 포트 자리가 숫자가 아니면 Connect·Test를 끈다.
  템플릿에 `{port}`가 없으면 칸 전체가 호스트다. `{server}`는 `{host}`와 함께 있으면 "Server instance", 혼자면 "Server name"이다.
- 기록(`ConnectionHistory`): Connect(새 연결)나 Save(편집)가 끝나면 그때의 서버 이름을 드라이버 전체 ID별 목록 맨 앞에 넣는다(최대 10개). 저장 위치는
  이 번들의 설정 저장소(작업 공간 `.metadata`, 키 `connectDialog.serverHistory.<공급자:드라이버>`, 줄바꿈으로 구분)로, 최근 파일 목록(`RecentFiles`)과 같다.
  연결 설정 파일이 아니라 UI 설정에 두므로 연결을 지워도 서버 이름은 남는다(SSMS와 같음). 최근 드라이버는 `connectDialog.recentDrivers`.
- Server name은 편집 가능한 콤보로 기록을 목록에 보인다. 새 연결은 그 드라이버의 마지막 서버 이름으로 시작한다(없으면 드라이버의 기본 호스트, 그것도 없으면 localhost. SSMS는 마지막 서버로 시작).
- JDBC URL로 연결하는 경우(Additional Connection Parameters)는 기록하지 않는다.

### 인증 방식 (`ConnectionAuthModels`)

근거: 인증 방식은 `com.seizia.chromite.dataSourceAuth` 확장(`DataSourceAuthModelDescriptor`)이고, 드라이버에 맞는 목록은 모델 계층의
`DBPDataSourceProviderRegistry.getApplicableAuthModels`가 준다(데스크톱에서는 서버 앱 전용 제외, `replace`로 대체된 방식 제외,
[DataSourceProviderRegistry.java:857-875](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/DataSourceProviderRegistry.java)).
SWT는 방식마다 SWT 설정 화면(`propertyConfigurator`의 `uiClass`, 예: `DatabaseNativeAuthModelConfigurator`, `PostgreAuthPgPassConfigurator`)을 쓰는데,
Swing 제품에는 그 번들이 없다. 그래서 범용 칸(Login, Password)만으로 끝나는 방식만 고를 수 있게 했다. 판단은 모델 코드만으로 한다.

- 구현이 `AuthModelDatabaseNative`다(사용자·비밀번호를 연결의 `userName`·`userPassword`에 두고 `isUserNameApplicable`·`isUserPasswordApplicable`로 칸이 필요한지 알림.
  SWT 범용 화면 `DatabaseNativeAuthModelConfigurator.createControl`도 이 두 값만 본다).
- 자격 증명 객체의 보이는 속성(`createCredentialsSource`, 숨김 제외)이 `userName`·`userPassword`뿐이다.
- 보일 칸이 하나 이상이거나 자격 증명이 필요 없는 방식(`isCredentialsPromptRequired() == false`)이다.
- 외부 인증 공급자가 필요 없다(`getRequiredAuthProviderId`).
- 연결이 지금 쓰는 방식(`detectConnectionAuthModel` + `getReplacedBy`, SWT `ConnectionPageWithAuth.loadSettings`와 같음)은 조건과 관계없이 넣는다. 다른 화면에서 고른 방식을 편집에서 지우지 않기 위해서다.
- 칸 이름은 SSMS식 Login·Password이고, 자격 증명이 속성 이름을 바꾸면 그 이름을 쓴다(LibSQL의 `Token`). 순서는 SWT `AuthModelSelector`와 같다(기본 방식 먼저, 그다음 이름순).
  고를 방식이 하나뿐이면 Authentication 줄을 숨긴다(SWT도 둘 이상일 때만 콤보를 보임).
- 저장: 고른 방식의 ID를 `setAuthModelId`로 쓰고, 그 방식이 쓰는 칸만 쓴다(`DatabaseNativeAuthModelConfigurator.saveSettings`와 같음: 비밀번호 칸이 없는 방식은 비밀번호를 지움).
  `AuthModelDatabaseNative`가 아닌 방식은 사용자·비밀번호를 건드리지 않는다. 인증 속성(`authProperties`)은 어느 방식에서도 건드리지 않는다.

드라이버별로 고를 수 있는 방식(코드의 `plugin.xml` 선언과 위 규칙에서 나온 것. SQL Server·PostgreSQL은 실행해서 확인):

| 드라이버 | Authentication 콤보 | 칸 | 빠진 방식과 이유 |
|---|---|---|---|
| SQL Server (`microsoft`) | SQL Server Authentication(기본), Active Directory - Integrated, Active Directory - MFA, Active Directory - MSI, Active Directory - Password, Custom, NTLM, Windows Authentication | 기본·AD Password·Custom·NTLM: Login+Password. MFA·MSI: Login만(MSI는 클라이언트 ID). Integrated·Windows: 칸 없음 | 없음 |
| SQL Server jTDS (`jtds`) | SQL Server Authentication, Custom, Windows Authentication | 위와 같음 | NTLM·AD 방식은 jTDS에서 쓰지 않도록 선언되어 있다(`if="object.getDriverParameter('jtds') == null"`) |
| PostgreSQL 계열 | Username/password(기본), PostgreSQL PgPass | PgPass: Login만(비밀번호는 pgpass 파일) | 없음 |
| Oracle | Username/password(`oracle_native`), OS Authentication | OS: 칸 없음 | 없음 |
| MySQL, MariaDB 등 대부분 | 줄 없음(Username/password 하나) | Login+Password | 셸 명령 비밀번호(`shell_command`)는 명령·작업 폴더 칸이 필요해 뺐다(설정 `connection.shellAuthModelEnabled`를 켜야 보이는 방식) |
| ClickHouse (`com_clickhouse`) | 줄 없음(Username/password 하나) | Login+Password | ClickHouse Cloud SSO, SSO/OpenID Connect: 브라우저 로그인·클라이언트 ID 설정이 필요하다(자격 증명 속성을 모두 숨김) |
| SQLite, DuckDB 등 익명 접속 | 인증 칸 없음 | — | — |
| LibSQL, MotherDuck | 줄 없음(토큰 방식 하나) | LibSQL: Token. MotherDuck: Password(토큰) | — |
| BigQuery, Snowflake, Spanner, Cloudflare D1, OceanBase | 줄 없음(그 드라이버 전용 방식 하나, 지금 방식이라 넣음) | 전용 방식의 Login·Password | 전용 방식의 다른 설정(키 파일, 역할 등)은 Provider Properties에 있는 것만 고칠 수 있다(D단계와 같음) |

### Options 탭

- **Connection Properties**: 연결 종류(`DataSourceProviderRegistry.getConnectionTypes`), 자동 커밋(Default / Auto-commit / Manual commit → `bootstrap.defaultAutoCommit` null/true/false),
  Default database·Default schema(`bootstrap.defaultCatalogName`·`defaultSchemaName`, 비우면 설정 안 함), Read-only connection(`setConnectionReadOnly`),
  Keep-alive(초, `keepAliveInterval`), Close idle connection after(초, `closeIdleConnection`·`closeIdleInterval`).
  값과 저장 규칙은 SWT `ConnectionPageGeneral.saveSettings`(종류, 읽기 전용)와 `ConnectionPageInitialization.saveSettings`(나머지)를 따른다.
  연결 종류를 바꾸면 그 종류의 유휴 연결 닫기 기본값으로 바꾸고, 유휴 시간은 켜져 있고 종류의 기본값과 다를 때만 저장한다.
  `DBPConnectionConfiguration`에 연결 시간 초과 값은 없다(드라이버 속성으로 정함).
- **Driver Properties**, **Provider Properties**: D단계의 표 그대로. Driver Properties는 그 탭이 보일 때 처음 읽는다(Options를 펼쳤을 때 그 탭이 선택되어 있으면 그때).
- **Additional Connection Parameters**: 연결 방식(Server name and database fields / JDBC URL)과 JDBC URL 칸. 칸으로 연결하면 드라이버 템플릿으로 만든 URL을 읽기 전용으로 보인다.
  다른 드라이버 매개변수는 Driver Properties의 Add…로 넣는다. URL만 받는 드라이버는 이 탭 대신 주 양식에 JDBC URL 줄이 있다.
- 탭은 한 줄로 두고 넘치면 스크롤한다(여러 줄로 감으면 고른 탭 줄이 아래로 옮겨져 탭 위치가 바뀌기 때문이다).

### Connect, Test, Save

- **Connect**(새 연결): D단계의 저장(`addDataSource`)을 한 뒤 대화상자를 닫고 `ConnectionCommands.connect`로 연결한다. Object Explorer는 `ADD` + `SELECT` 이벤트로 새 연결을
  고르고, 연결되면 연결 표시를 그린다. 연결에 실패하면 연결 서비스가 오류를 보이고 연결 설정은 남는다(SSMS는 실패하면 대화상자를 다시 보이지만, 여기서는 연결 Job이 대화상자를 닫은 뒤 돈다).
- **Save**(편집), **Test Connection**: D단계와 같다. Test는 폼의 비밀번호를 저장 여부와 관계없이 쓴다.
- **Help**: 열 사용자 도움말 문서가 아직 없어 두지 않았다(지시대로).
- 드라이버 파일이 없거나 오프라인 모드: D단계의 흐름 그대로다(Test·Connect·Driver Properties 읽기 때 `SwingDriversService`가 내려받기를 묻고, 오프라인이면 모델 오류).

### 확인 (2026-11-06)

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 결과물 `bundles.info`의 번들 수는 Windows x64 137개, macOS arm64 138개이고,
  `swt`·`jface`·`org.eclipse.ui`·`org.eclipse.e4.ui` 번들은 둘 다 0개다.
- Windows에서 in-process JVM(`-vm …\bin\server\jvm.dll`)으로 G단계 작업 공간 사본(SQLite 연결 2개, 연결 파일 경로를 사본으로 바꿈)을 열어 키보드·마우스로 확인했다:
  - **새 연결 기본 화면:** New Connection → `Connect to Server`, Database type SQL Server, Server name `localhost`, Database `master`,
    Authentication `SQL Server Authentication`, Login, Password, Remember password, Connection name. Authentication 목록은 SQL Server Authentication,
    Active Directory - Integrated / MFA / MSI / Password, Custom, NTLM, Windows Authentication 8개이고, Windows Authentication을 고르면 Login·Password 줄이 사라지고 대화상자 높이가 줄었다.
  - **SQLite:** Database type에서 `sqli`를 쳐서 SQLite → File + Browse만 남음(인증 칸 없음), 비어 있으면 "Enter the file." → 경로 붙여 넣기 → Connect →
    대화상자가 닫히고 Object Explorer에 `new-test.db`가 생겨 선택되고 연결 표시가 붙음 → New Query에서 `select count(*) from Album` → `347`.
  - **PostgreSQL + Options:** Server name `pg-test.local:5433` → Options >> → Connection Properties(연결 종류 Development, 자동 커밋, 기본 DB·스키마, 읽기 전용, keep-alive 0, 유휴 연결 닫기 14400),
    Additional Connection Parameters의 URL `jdbc:postgresql://pg-test.local:5433/postgres`, Driver Properties에 pgjdbc 속성(adaptiveFetch 등).
    Authentication 목록은 Username/password, PostgreSQL PgPass. Connect → 연결 서비스가 "Unknown host pg-test.local" 오류를 보이고 연결 설정은 남음
    (`host` pg-test.local, `port` 5433, `auth-model` native).
  - **서버 기록:** 다시 New Connection → PostgreSQL → Server name이 `pg-test.local:5433`으로 시작하고 목록에 그 서버가 보임.
  - **편집:** `new-test.db`에서 Enter → `Edit Connection - new-test.db`(Database type 꺼짐, 단추 Save) → 이름 `Chinook New Test`, Options의 Read-only connection 켬 → Save →
    트리 이름이 바로 바뀌고 정렬 위치가 바뀜, 설정 파일에 `"name": "Chinook New Test"`, `"read-only": true`.
  - **More database types…:** 콤보 끝 항목 → 드라이버 검색 창 → `duck` → Enter → DuckDB가 콤보에 들어가고 File 칸으로 바뀜.
- 디버그 로그에 이 기능의 오류·예외는 없다(있는 오류는 입력 도구가 잘못 친 SQL 한 건의 구문 오류).
- 확인하지 못한 것: 실제 SQL Server·PostgreSQL 서버 연결(서버 없음), Windows·AD 인증의 실제 연결, 드라이버 파일 내려받기와 오프라인 모드(D단계 흐름을 바꾸지 않음), macOS 실행,
  다크 테마 화면, 다른 작업이 넣을 `Chromite.*` 색 키가 있을 때의 모양.

### 남긴 것

- SSMS의 History(즐겨찾기, 최근 연결 목록 창), 연결 색(Color), Encrypt·Trust Server Certificate를 주 양식에 꺼내 두기(지금은 SQL Server의 Provider Properties·Driver Properties에 있음).
- 브라우저 로그인 인증 방식(ClickHouse SSO·OIDC, Azure 등 외부 공급자), 셸 명령 비밀번호, 인증 방식별 추가 칸(`AuthModelSelector`의 SWT 설정 화면 몫).
- 연결 실패 시 대화상자 다시 열기(SSMS), 비밀번호 보기 단추, 연결 폴더·설명, 격리 수준·초기화 SQL.

## Islands 적용 (2026-11-06)

사용자 의견("아직 딱딱하고 DBeaver 같다, DataGrip은 둥글다")에 따라 모양 기준을 DataGrip 2025.3부터의 기본 테마 **Islands**로 바꿨다.
값과 근거는 [design-spec.md](design-spec.md) "적용 현황 — Islands 적용"에 있다.

| 파일 | 바뀐 것 |
|---|---|
| `IslandPanel`(새 파일) | 둥근 카드: 자식을 그린 뒤 모서리 바깥을 캔버스 색으로 앤티에일리어싱해 덮음, 카드가 그리기 시작점 |
| `ToolWindow` | 도구 창(머리말 + 내용)을 카드로 감쌈 |
| `MainFrame` | 문서 탭 카드, 캔버스 바탕(내용 패널·분할 창·상태 표시줄, 좌우 6 틈), 분할선 = 6px 틈(선 없음), 주 도구 막대 투명, 문서 탭 `IslandTabbedPaneUI`(알약 선택, 밑줄 없음) |
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | Islands 팔레트(캔버스, 카드, 선택, 글자, 테두리, 강조색 `#3871E1`)와 `Chromite.island.*` 토큰, 제목 표시줄 캔버스색 |
| `SqlEditorDark.xml` | 편집기 바탕·거터 `#191A1C`, 현재 줄 `#212326` |

- 확인: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS, 작업 공간 사본(`D:\Tools\_smoke\swing-p1`)으로 실행해 라이트·다크 × 아이콘 팩 A 캡처
  (`D:\Tools\_scratch\swing-p1\islands-light.png`, `islands-dark.png`), 실행 중 테마 전환. 회귀 테스트는 실행하지 않았다(요청 없음).
  영향받을 수 있는 기능: 도구 창 숨기기·크기 저장(카드 단위로 바뀜), 분할선 끌기(6px 틈), 테마 전환.

## P1 디자인 적용 (2026-11-06)

[design-spec.md](design-spec.md)의 P1 항목 중 아래를 적용했다(Islands 적용 위에). 측정값은 그 문서의 "적용 현황 — P1 결과"에 있다.

| 파일 | 바뀐 것 |
|---|---|
| `ResultSetGrid` | 열 너비 여백을 Look and Feel 렌더러·셀 테두리에서 계산, 행 번호를 눌러도 가로로 스크롤하지 않음 |
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | 제목 표시줄 40(`TitlePane.buttonSize` 46,40, 제목 글자 `text.secondary`), 메뉴 항목 24(`@menuItemMargin`), 스크롤바, Properties 분류 띠 색 |
| `EditMenu`(새 파일) | Edit 메뉴: Undo, Redo, Cut, Copy, Paste, Select All, Find…, Replace…, Find Next, Find Previous |
| `FindReplaceDialog`(새 파일) | 찾기·바꾸기 대화상자(RSyntaxTextArea `SearchEngine`) |
| `OptionsDialog`(새 파일) | Tools > Options: 테마, 아이콘 스타일, 입력 중 자동완성, 자동완성 지연, 최대 행 수 |
| `MainFrame` | 메뉴 순서 File, Edit, View, Query, Tools, Window, Help, Window 메뉴(Reset Window Layout, 열린 문서 탭), 탭 닫기 단추·가운데 단추, 쿼리 상태 줄 자리 |
| `ToolWindow` | 기본 크기로 되돌리기(`reset`) |
| `SqlEditorPanel`, `DdlViewerPanel` | 편집기 찾기 키, 상태 줄 `QueryStatusLine`, 탭 도구 설명(전체 경로, 연결·서버) |
| `QueryStatusLine`(새 파일) | SSMS 쿼리 상태 줄: 상태 아이콘, 문구, 서버, 사용자, DB/스키마, 시간, 행 수 |
| `ObjectExplorerPanel` | 트리에 초점이 있을 때 F5 = 새로 고침 |
| `PropertiesPanel` | SSMS 속성 그리드(분류별·이름순, 분류 접기, 설명 칸) |
| `SqlCompletionPopup` | 행 24, 오른쪽 형식 글자, 둥근 선택, 지연 설정 접근자 |
| `ChromiteScrollBarUI`(새 파일), `ChromiteLightLaf`, `ChromiteDarkLaf` | 스크롤바: 보이지 않는 트랙, 막대 7(마우스 올리면 9) |
| `icons/ui/**/categorized.svg`, `sort-alpha.svg`, `tools/branding/gen_ui_icons.py` | Properties 도구 막대 아이콘 2개 × 2팩 × 2테마(생성기에 추가, 기존 SVG는 바이트 단위로 같음) |

### 결과 그리드 머리글이 잘려 보이던 것 (근본 원인)

- 보고: 첫 데이터 열 머리글이 `AlbumId` 대신 `ıld`처럼 보였다(`swing-design/m-light-A-1-0.png`).
- 원인: 열 너비 계산이 아니라 **가로 스크롤**이다. 같은 결과를 스크롤하지 않은 캡처(`swing-design/w5-query-0.png`)에서는 `AlbumId` 열이 102px로 머리글이 다 보이고,
  잘린 캡처는 가로 스크롤 막대가 오른쪽으로 옮겨져 있고(막대 시작 525 → 580px) 행 번호 열(행 머리글, 스크롤하지 않음) 옆의 첫 열이 그만큼 가려져 있다.
  캡처 직전 단계(`scene.ps1`)가 Title 칸에서 Shift+→로 선택을 Artist 열까지 넓혔고, `JTable.changeSelection`이 선택한 칸 전체가 보이도록 표를 오른쪽으로 스크롤했다.
  키보드로 칸을 고를 때의 이 스크롤은 표의 정상 동작이라 바꾸지 않았다.
- 같은 원인으로 실제로 잘못된 경우: 행 번호를 누르면 `changeSelection`을 첫 열과 마지막 열에 두 번 불러, 표가 **마지막 열까지 가로로 스크롤**해 앞쪽 열이 가려졌다.
  행 번호 클릭은 자동 스크롤을 잠시 끄고 행을 고르도록 고쳤다(누른 행은 이미 보임).
- 함께 고친 것: 열 너비의 여백이 고정값 18(셀 여백 8 + 8 + 격자선 1을 가정)이었다. Look and Feel이 여백을 배율로 키우는 환경(FlatLaf 사용자 배율 > 1)이나 테마가
  `TableHeader.cellMargins`를 바꾸면 머리글이 잘린다. 머리글 너비는 머리글 렌더러의 선호 너비, 셀은 셀 테두리(`Table.cellNoFocusBorder`)의 여백으로 계산한다(모든 열).

### 제목 표시줄과 메뉴

- 제목 표시줄 40(창 단추 46×40, `TitlePane.buttonSize`), 제목 글자 `text.secondary`. 메뉴 항목 높이 24(`@menuItemMargin` 3,8,4,8). 팝업 모서리 8, 선택 arc 8·좌우 4는 디자인 적용 때 그대로.
- 주 메뉴는 SSMS 순서 File, Edit, View, Query, Tools, Window, Help.
- **Edit**(`EditMenu`): Undo부터 Select All까지는 키보드 초점 컴포넌트에 적용한다(Swing 메뉴는 초점을 가져가지 않음). 편집기(RSyntaxTextArea)는 실행 취소 기록,
  입력 칸은 `JTextComponent`, 표·트리의 복사는 그 컴포넌트의 전송 처리기다. 단축키는 메뉴 단축키라 초점 컴포넌트가 같은 키를 직접 처리하면 그쪽이 먼저다.
  켜고 끄기는 초점이 바뀔 때(컴포넌트 종류) 정하고, 메뉴를 열 때 선택·실행 취소 기록까지 보고 다시 정한다.
- **Find/Replace**(`FindReplaceDialog`): Ctrl+F, Ctrl+H(SSMS), F3·Shift+F3. 편집기가 이 키를 직접 받아(텍스트 영역의 Ctrl+H 뒤로 지우기보다 우선) 활성 문서 탭의 편집기를 대상으로 연다.
  RSyntaxTextArea `SearchEngine`(대소문자, 단어 단위, 정규식, 끝에서 처음으로 이어 찾기, 맞는 곳 모두 표시)을 쓴다. 대화상자는 모달이 아니고 닫으면 표시를 지운다. DDL 탭에서는 바꾸기를 끈다.
  RSTA의 찾기 대화상자(RSTAUI)는 별도 라이브러리라 넣지 않았다.
- **Tools > Options**(`OptionsDialog`): 이미 있는 설정만 한곳에 모았다. 테마·아이콘 스타일(`ui.theme`, `ui.iconStyle`), 입력 중 자동완성·지연(`sqlEditor.completion.*`),
  최대 행 수(`resultset.maxrows`, 실행 엔진이 연결 설정 저장소 → 플랫폼 설정 저장소 순으로 읽는 값, 0 = 제한 없음). OK를 누를 때 바뀐 값만 적용·저장한다.
  같은 설정을 보이는 메뉴 항목(View > Theme, Icon Style, Query > Auto-Complete While Typing)은 메뉴를 열 때 현재 값으로 맞춘다.
  자동완성 설정의 기본값은 처음 읽을 때 선언한다(편집기를 열기 전에 Options를 열어도 기본값이 보이게).
- **Window**: Reset Window Layout(도구 창을 모두 보이고 기본 크기로), 열린 문서 탭 목록(활성 탭에 표시, 고르면 그 탭으로).

### 문서 탭

- 닫기 단추(×)는 선택한 탭과 마우스를 올린 탭에만 그린다(`IslandTabbedPaneUI.paintTabCloseButton`). 단추 자리는 모든 탭에 남겨 두어 마우스를 움직여도 탭 폭이 바뀌지 않는다.
- 가운데 단추로 누르면 닫는다(닫을 수 있는 탭만, 저장 확인은 닫기 단추와 같음).
- 도구 설명: 전체 파일 경로(저장 안 한 쿼리는 `SQLQueryN.sql (not saved)`)와 연결 이름·위치(URL 또는 파일)·DB 제품 버전.

### F5 (SSMS)

트리에 초점이 있으면 F5 = 새로 고침, 그 밖에서는 F5 = 실행이다. 트리의 키 바인딩(초점 컴포넌트)이 메인 창 Query 메뉴의 F5 단축키(창 전체)보다 먼저 처리되는 Swing 순서를 쓴다.
Object Explorer 오른쪽 클릭 메뉴의 Refresh에 F5를 보인다.

### 쿼리 상태 줄 (SSMS)

- `QueryStatusLine`: 상태 아이콘 16 + 문구 │ 서버 │ 사용자 │ DB/스키마 │ 경과 시간 │ 행 수. 높이 28, 위 1px 선, 글자 `text.secondary`, 칸 사이 1px 구분선.
  상태 아이콘은 실행 전 연결 아이콘(연결되어 있으면 초록 상태 점), 실행 중 ▶, 성공 체크, 오류, 취소 경고다.
- 서버는 호스트[:포트], 파일 DB는 파일 이름이고, 도구 설명에 전체 URL·경로와 DB 제품 버전(SSMS의 "서버 (버전)")을 둔다. 사용자는 연결의 사용자 이름, DB/스키마는 편집기 도구 막대의 기본 DB/스키마 콤보 값이다. 빈 칸은 숨긴다.
- 위치: SSMS처럼 쿼리 창 맨 아래 = Results 카드의 아래쪽. Results를 숨기면(Ctrl+R) 문서 카드의 아래쪽(편집기 아래)으로 옮긴다(2026-11-08부터 메인 창 상태 표시줄, "상태 표시줄의 쿼리 창 칸"). 빈 문서 영역(예전 시작 탭)·DDL 탭에는 없다.
  상태 줄은 편집기마다 있고 메인 창이 활성 편집기의 것을 끼운다(결과 탭과 같은 방식, 탭에서 빠져 있던 동안의 테마 전환을 끼울 때 맞춤).

### Properties (SSMS 속성 그리드)

- 머리말 아래 도구 막대: 분류별·이름순 아이콘 토글(새 아이콘 `categorized`, `sort-alpha`). 열 머리글 없는 이름 | 값 두 열, 행 24.
- 분류 줄: 셰브런 + semibold 이름, 바탕 `Chromite.PropertyGrid.categoryBackground`(카드 바탕 위의 띠). 누르기, ←/→, Enter로 접고 편다. 분류 아래 속성 이름은 분류 이름 위치에 맞춰 들여 쓴다.
  분류 없는 속성은 SWT 속성 보기처럼 제목 없이 맨 앞이다.
- 가로선 `grid.lineSubtle`, 이름과 값 사이 1px `border.subtle`(속성 줄만). 아래 설명 칸: 고른 속성의 이름과 `DBPPropertyDescriptor.getDescription()`.
- 읽기 전용 그대로. 접은 분류와 정렬 방식은 실행 중에 기억한다.

### 자동완성 목록

- 행 24(고정 높이라 목록이 제안마다 렌더러 높이를 묻지 않음), 아이콘 16과 이름 사이 6, 오른쪽 형식·테이블 글자 `text.secondary`, 최대 12행, 폭은 내용에 맞추고 280~640.
- 선택은 테마 목록 선택과 같은 둥근 사각형(arc 8, 좌우 4)이고 글자색을 바꾸지 않는다. 목록은 초점을 갖지 않으므로(편집기가 초점 유지) 초점 있는 선택색을 테마에서 직접 읽는다.

### 스크롤바

`ChromiteScrollBarUI`(FlatLaf `FlatScrollBarUI` 상속, Look and Feel의 `ScrollBarUI`로 등록): 트랙은 스크롤하는 내용의 바탕색으로 칠해 보이지 않고, 막대는 폭 11 안의 둥근 7,
마우스를 스크롤바에 올리면 9로 넓어진다. 막대 색은 Islands `ScrollBar.thumbColor` `#80808059`, 마우스 올림 `#8080808C`(라이트도 같은 값).

### 확인 (2026-11-06)

- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Swing 제품의 `plugins/`에 `swt`·`jface`·`org.eclipse.ui`·`e4.ui` 번들 0개.
- 실행(작업 공간 사본 `D:\Tools\_smoke\swing-p1`, Windows 11, 150%, in-process JVM), 캡처는 `D:\Tools\_scratch\swing-p1\`:
  - 그리드 머리글 라이트·다크(`grid-light.png`, `grid-dark-0.png`): `AlbumId` 머리글 전체 보임, 행 번호 5를 눌러도 가로 스크롤 막대가 왼쪽 그대로.
  - 메뉴(`menu-edit2-0.png`, `menu-tools-0.png`, `menu-window2-0.png`, 팝업은 별도 창으로 캡처), Options(`options-0.png`): 테마 Dark·최대 행 500으로 OK →
    창이 다크로 바뀌고 설정 파일에 `ui.theme=DARK`, `resultset.maxrows=500`.
  - 탭(`tab-hover-crop.png`): 마우스를 올린 탭에만 ×와 옅은 알약, 도구 설명에 경로·연결·`SQLite 3.54.1`. 가운데 단추로 두 번째 탭이 닫힘(`tab-middle-crop.png`).
  - F5: 사본 DB에 다른 프로그램(sqlite3)으로 테이블을 하나 만든 뒤 트리의 테이블 폴더에서 F5 → 다시 읽어 `테이블 14`와 `P1RefreshCheck`가 보임(`f5-expanded-crop.png`), 쿼리는 실행되지 않음.
  - 쿼리 상태 줄(`grid-dark-0.png`): 체크 아이콘, `Query executed successfully.` │ `chinook-copy.db` │ 시간 │ `39 rows`(SQLite라 사용자·DB 칸은 숨김).
  - Properties(`props-crop.png`, `props2-crop.png`): 연결 노드의 Driver·Server 분류, Driver 접기, 고른 속성의 설명 칸.
  - 자동완성(`completion2-0.png`), 찾기·바꾸기(`find-0.png`: `ar.Name` 찾기, 끝에서 처음으로 이어 찾기).
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 그리드 열 너비·행 번호 클릭, 메뉴 단축키(Ctrl+Z/Y/X/C/V/A가 초점 컴포넌트에서 처리되지 않을 때),
  편집기 Ctrl+H(이제 바꾸기), Object Explorer F5, 상태 줄 위치(Results 숨김·보임), Properties 키 조작, 자동완성 목록 모양, 모든 스크롤 영역.

### 남긴 것

- 주 상태 표시줄 오른쪽 위젯(Ln/Col, 인코딩, 줄바꿈), 연결별 상태 줄 색(SSMS), 상태 줄의 사용자 spid.
- 자동완성 맞는 글자 강조(`completion.match`), 팝업 창 둥근 모서리(자동완성은 `JWindow`라 Windows 11 둥근 창이 아님).
- Properties 열 경계 끌기, 이름순·접힌 분류의 저장(지금은 실행 중에만 기억).
- ~~Options의 다른 설정(편집기 글꼴 등)은 설정 자체가 아직 없어 넣지 않았다.~~ 2026-11-06 Editor·Editor Colors 탭("편집기 글꼴과 SQL 색").

## 편집기 글꼴과 SQL 색 (2026-11-06)

사용자 결정에 따라 SQL 편집기 글꼴을 Cascadia Mono 13(번들)으로, 구문 색을 SSMS 기본 색으로 바꾸고, 둘 다 Tools > Options에서 바꿀 수 있게 했다.
결정, 색값, 근거, 라이선스는 [design-spec.md](design-spec.md) "SQL 구문 색"과 "적용 현황 — 편집기 글꼴과 SQL 색"에 있다.

| 파일 | 바뀐 것 |
|---|---|
| `EditorFont`(새 파일) | 편집기 글꼴: 번들 Cascadia Mono 지연 등록, 저장한 글꼴 → Cascadia Mono → JetBrains Mono 대체, 합성 글꼴(한글 대체), 고정폭 글꼴 목록, 설정 저장 |
| `EditorColors`(새 파일) | 편집기 색 항목 14개(토큰 9, 편집기 5), 테마 XML 기본값 + 바꾼 항목만 저장, 테마 XML에 덮어쓰기 |
| `SqlTokenClassifier`(새 파일) | RSTA SQL 토크나이저 결과를 방언(키워드·함수·자료형)과 SSMS 규칙(논리 연산자, 날짜 부분, `N'…'`, 변수, 문장 부호)으로 고침. 텍스트 영역마다 `TokenMakerFactory` |
| `SqlEditorPanel` | `SqlTextArea`: 토큰 분류기 설치, 연결 방언 반영(`setDataSourceContainer`, `refreshSchemas`에서), 색·글꼴 적용(`applyTheme`), 열린 모든 편집기에 적용(`applyThemeToAll`) |
| `SqlEditorLight.xml`, `SqlEditorDark.xml` | SSMS 토큰 색(숫자 연두색, 주석 기울임) |
| `OptionsDialog` | General·Editor·Editor Colors 탭, 미리 보기 |
| `ChromiteTheme` | Look and Feel 설치 전에 편집기 글꼴 등록 |
| `DdlViewerPanel`, `MainFrame`, `ObjectExplorerPanel` | View DDL이 객체의 연결을 넘겨 DDL 탭도 그 방언으로 강조 |
| `fonts/cascadia-mono/*`(새 파일), `build.properties`, `NOTICE` | Cascadia Mono 정적 TTF 4개와 OFL, 번들에 포함 |

### 확인 (2026-11-06)

Windows 11, 화면 배율 150%, JBR 25, 작업 공간 사본 `D:\Tools\_smoke\swing-font`(연결 `Chinook Copy (SQLite)`), 창만 캡처(`shot.ps1`). 캡처는 `D:\Tools\_scratch\editor-font\`.

- 시작 로그: `SQL editor font: Cascadia Mono 13 (font Cascadia Mono Regular, requested Cascadia Mono)`.
- 예문(`SELECT a.AlbumId, COUNT(*) AS cnt, UPPER(a.Title) -- 한글 주석` / `FROM Album a` / `WHERE a.Title LIKE N'%한글%' AND a.AlbumId > 42.5` / `GROUP BY a.AlbumId;`)을
  붙여 넣고 글자 픽셀의 가장 많은 색을 읽었다(`light-editor2-0.png`, `dark-editor-0.png`, 확대 `light-editor-zoom.png`, `dark-editor-zoom.png`):

  | 토큰 | Light | Dark |
  |---|---|---|
  | `SELECT` | `#0000FF` | `#569CD6` |
  | `COUNT`, `UPPER` | `#FF00FF` | `#D48CF5` |
  | `N'%한글%'` | `#FF0000` | `#F07068` |
  | `42.5` | `#669900` | `#B5E853` |
  | `-- 한글 주석`(기울임) | `#008000` | `#57A64A` |
  | `LIKE`, `AND`, `,` | `#808080` | `#B4B4B4` |
  | `AlbumId` | `#000000` | `#DCDCDC` |

  한글은 □ 없이 맑은 고딕으로 보이고, 한글이 있는 1·3행과 없는 2·4행의 줄 간격이 같다(27px, 150%).
- 사용자 SSMS 사진의 문장을 다크 편집기에 넣어 나란히 비교(`compare-ssms-dark.png`): 키워드·연산자 단어·문자열·`DATEDIFF(SECOND, …)`·숫자 색이 사진과 같은 분류로 칠해진다.
  다른 점: 사진에서 파란 `NOLOCK`(테이블 힌트)은 SQLite 연결의 방언에 없는 단어라 글자색이다.
- Options(`opt-dark-editor-0.png`, `opt-dark-colors-0.png`, `opt-light-editor-0.png`, `opt-light-colors-0.png`): Editor 탭의 글꼴 목록에서 Lucida Sans Typewriter를
  고르면 미리 보기가 바로 바뀐다(`opt-light-editor-lucida-0.png`, 취소함). Editor Colors에서 Keyword를 Bold로 하고 OK → 열린 편집기의 키워드가 바로 굵게 바뀌고(`dark-editor-keyword-bold-crop.png`)
  설정 파일에 `sqlEditor.color.dark.keyword=569cd6,bold`만 저장됨. Reset to Defaults + OK → 그 키가 지워짐. Options에서 테마를 Light로 바꾸면 편집기 색도 라이트 값으로 바뀐다.
- 빌드: `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS, Swing 제품 `plugins/`에 `swt`·`jface`·`org.eclipse.ui`·`e4.ui` 번들 0개, 번들 jar에 `fonts/cascadia-mono/*` 포함.
- 확인하지 못한 것: macOS(Cascadia Mono 등록, Apple SD Gothic Neo 대체), Windows 10(운영체제 Cascadia Mono 없음 → 번들 글꼴 등록 경로), DDL 탭의 방언 강조(화면 확인 안 함), 연결된 SQL Server 방언.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: SQL 편집기·DDL 탭의 구문 강조와 글꼴, 테마 전환 뒤 편집기 색, Tools > Options(탭 구성), View DDL.
- 범위 밖 관찰: Options에서 라이트로 바꾼 직후 편집기 오른쪽(세로 스크롤 막대 자리)에 다크 바탕 띠가 한 번 남았고, 편집기를 누르니 다시 그려져 사라졌다(원인 미조사).

### 남긴 것

- 표·열·스키마 이름 색(SWT의 의미 분석 색), SSMS의 시스템 테이블·뷰(초록)·시스템 저장 프로시저(적갈색) 색.
- `@set` 같은 클라이언트 명령 색(SWT command), `[이름]`·`"이름"` 구분(지금 `"이름"`은 문자열 색, RSTA 토크나이저 그대로).
- 편집기 줄 간격 1.2(RSTA에 설정 없음), macOS의 Cascadia Mono·한글 대체 확인.

## 도구 막대 (2026-11-06)

사용자 결정("가")에 따라 주 도구 막대와 쿼리 창 도구 막대를 SSMS 배치로 늘렸다. 메뉴로만 갈 수 있던 기능을 단추로 꺼낸 것이고,
새 동작은 Save All 하나다. 나머지 단추는 메뉴와 같은 `Action`이라 켜고 끄기·단축키·동작이 메뉴와 같다.

> 2026-11-07: 쿼리 창 도구 막대는 쿼리 창마다 있지 않고 메인 창 둘째 줄의 SQL Editor 도구 막대가 되었다(사용자 결정). 구성과 단추는 같고,
> 바뀐 것은 아래 "SQL Editor 도구 막대" 절에 있다. 이 절의 쿼리 창 도구 막대 설명·측정은 2026-11-06 당시의 것이다.

### 구성

| 도구 막대 | 순서 (│ = 구분선) |
|---|---|
| 주 도구 막대 (SSMS Standard) | New Query(아이콘 + 글자), New Connection │ Open, Save, **Save All** │ **Undo**, **Redo** │ **Activity Monitor** │ **Registered Servers**, **Object Explorer**, **Query Library**, **Properties** │ **Find** … (오른쪽 끝) Chromite Agent(아이콘 + 글자, 2026-11-08 아래 "Chromite Agent 단추를 오른쪽 끝으로") |
| 쿼리 창 도구 막대 (SSMS SQL Editor) | Execute, Stop │ Estimated Plan, Actual Plan │ 연결 ▾ │ DB/스키마 ▾ │ **Comment**, **Uncomment** │ **Decrease Indent**, **Increase Indent** │ **Format SQL** │ **Query History** (2026-11-08부터 콤보가 맨 앞: "SQL Editor 도구 막대") |

| 단추 | 쓰는 동작 (다시 쓴 것) | 단축키 |
|---|---|---|
| Save All | 새 `MainFrame#saveAll` (File > Save All에도 추가) | Ctrl+Shift+S (SSMS) |
| Undo / Redo / Find | `EditMenu`의 Undo·Redo·Find… 동작(`getUndoAction` 등으로 내줌) | Ctrl+Z / Ctrl+Y / Ctrl+F |
| Activity Monitor | Tools > Activity Monitor를 `Action`으로 바꿔 같이 씀(`openActivityMonitor`) | Ctrl+Alt+A (새로 붙임, SSMS) |
| 도구 창 4개 | `ToolWindow#getToggleAction`(View 메뉴 체크 항목과 같은 동작, `SELECTED_KEY`로 눌린 모양이 따라감) | Ctrl+Alt+G, F8, Ctrl+Alt+T, F4 |
| Comment ~ Format SQL | `SqlTextArea#runCommand`(Edit > Advanced와 같은 `SqlEditCommands`) | Ctrl+K Ctrl+C, Ctrl+K Ctrl+U, Shift+Tab, Tab, Ctrl+K Ctrl+D |
| Query History | Query > Query History…와 같은 `MainFrame#openQueryHistory`(`SqlEditorPanel.Host`에 추가) | 없음(메뉴에도 없음) |

도구 설명은 `ToolbarMessages`(영어 + `_ko`)에 있고 끝에 메뉴와 같은 단축키를 적는다. 아이콘은 생성기로 13개를 새로 만들었고(`save-all`, `undo`, `redo`, `activity-monitor`,
`registered-servers`, `object-explorer`, `properties`, `comment`, `uncomment`, `indent`, `outdent`, `format`, `overflow`), Find·Query Library·Query History는 기존
`search`·`library`·`history`를 쓴다([design-spec.md](design-spec.md) "아이콘 팩").

### 설계 결정

- **Save All**: 고친 쿼리 창은 Save와 같은 `saveEditor`(파일이 없으면 저장 대화상자, Query Library 쿼리는 확장의 저장), 변경이 있는 테이블 디자이너는 디자이너의 Save
  (변경 스크립트 미리 보기)로 저장한다. 저장하는 문서를 앞으로 가져오고, 취소하거나 실패하면 거기서 멈추며(끝내기 때의 저장과 같은 규칙), 끝나면 원래 탭으로 돌아온다.
  결과 표의 저장하지 않은 변경은 문서가 아니라서 넣지 않았다(닫을 때 묻기는 그대로). 고친 문서가 없으면 꺼진다(`titleChanged`, `designerChanged`에서 다시 셈).
- **Undo / Redo**: Edit 메뉴처럼 키보드 초점이 있는 편집기에 적용한다. 도구 막대 단추는 초점을 가져가지 않으므로 누르기 전의 편집기가 대상이다.
  단추가 늘 켜져 보이지 않도록 Undo·Redo의 켜고 끄기를 실행 취소 기록(`canUndo`/`canRedo`)에 맞췄다. 초점 편집기의 문서 변경을 듣고 다음 이벤트에서 다시 정한다
  (실행 취소 관리자는 문서 이벤트 뒤에 기록을 바꾸기 때문). 메뉴 단축키도 같은 상태를 따르지만 편집기가 Ctrl+Z/Ctrl+Y를 직접 받으므로 동작은 같다.
- **Activity Monitor**: Object Explorer에서 고른 연결, 없으면 활성 쿼리 창의 연결(`getCurrentConnection`). 둘 다 없으면 단추와 Tools 메뉴 항목이 꺼지고
  도구 설명이 "개체 탐색기에서 연결을 선택하거나…"로 바뀐다. Object Explorer 선택 이벤트(생성자 인자의 선택 리스너)와 활성 문서·연결 변경에서 다시 정한다.
  Locks 메뉴 항목은 이전처럼 안내 메시지를 띄운다.
- **넘침(`OverflowToolBar`)**: JToolBar 기본 배치는 넘치는 단추를 오른쪽에서 잘라 누를 수 없었다. 새 배치 관리자가 먼저 줄일 수 있다고 표시한 컴포넌트
  (쿼리 창의 연결·DB 콤보, 최소 100·100)를 기본 폭과의 차이에 비례해 줄이고, 그래도 넘치는 뒤쪽 단추는 크기 0으로 두고 오른쪽 끝 » 단추의 메뉴로 보인다(아이콘·이름,
  켜고 끄는 단추는 체크 항목, 꺼진 단추는 꺼진 항목). 넘친 자리 바로 앞의 구분선은 그리지 않는다. 배치 중에 보임 상태를 바꾸면 배치가 반복될 수 있어 크기 0으로 숨긴다.
  주 도구 막대는 1280px·150%(논리 폭 853)에서도 다 들어가므로 넘침은 그보다 좁을 때만 생긴다. 글자는 New Query만 보인다(SSMS).
- **높이**: 쿼리 창 도구 막대는 `size.toolbar.editor` 36(콤보 28 + 위아래 4), 주 도구 막대는 `size.toolbar.main` 36 그대로다. 새 배치 관리자도 높이는 가장 큰 컴포넌트로 정한다.
- **Increase Indent**: 기존 Edit > Advanced와 같이 RSyntaxTextArea의 Tab 동작이라, 선택이 없으면 커서 자리에 탭을 넣는다(여러 줄 선택은 줄마다 들여씀). 메뉴와 같게 두었다.

### 바뀐 파일

| 파일 | 바뀐 것 |
|---|---|
| `OverflowToolBar`(새 파일) | 줄일 수 있는 콤보와 넘침 메뉴가 있는 가로 도구 막대 |
| `internal/ToolbarMessages`(새 파일) + `.properties`, `_ko.properties` | 도구 설명, Save All, 편집 명령 이름, 넘침 단추 |
| `MainFrame` | 주 도구 막대 구성, Save All(메뉴·단축키), Activity Monitor 동작·켜고 끄기, Query History 진입점(`openQueryHistory`) |
| `EditMenu` | Undo·Redo·Find 동작을 내줌, Undo·Redo를 실행 취소 기록에 맞춰 켜고 끔 |
| `SqlEditorPanel` | 쿼리 창 도구 막대 단추, `Host#openQueryHistory`, 콤보 최소 폭 |
| `tools/branding/gen_ui_icons.py`, `icons/ui/**` | 아이콘 13개 × 2팩 × 2테마 |
| `ChromiteDarkLaf.properties` | `ToggleButton.toolbar.selectedBackground = #ffffff29`(눌린 단추와 같은 값). FlatLaf 기본값 `#2A2C2F`는 캔버스 `#26282C`·패널 `#2B2D30`과 거의 같아 눌린 도구 창 단추(기존 Properties의 범주별·Actual Plan도)가 안 보였다 |

### 확인 (2026-11-06)

Windows 11, 150%, JBR 25(`-vm D:\Tools\jbrsdk-25\bin\server\jvm.dll`), 개인 작업 공간 `D:\Tools\_scratch\toolbars\ws`, SQL Server 2022 테스트 DB `chr_toolbars`.
EDT 에이전트로 단추를 누르고(`doClick`) 결과를 읽었다. 캡처는 창만(`shot.ps1`) `D:\Tools\_scratch\toolbars\`.

- 처음(연결 없음): Activity Monitor 꺼짐, 도구 설명 = 이유. Save·Save All·Undo·Redo·Find 꺼짐. 보이는 도구 창 3개의 단추가 눌린 모양.
- Activity Monitor: Object Explorer에서 `TB MSSQL`을 고른 뒤 켜지고, 누르면 "작업 모니터 - TB MSSQL" 탭(프로세스·잠금)이 열림. Ctrl+Alt+A는 열린 탭을 앞으로.
- Save All: 새 쿼리 창 2개에 글을 넣고 누르면 저장 대화상자가 차례로 2번 → `q1.sql`, `q2.sql`이 디스크에 생기고 탭 제목의 `*`가 없어지며 Save All이 꺼짐.
  두 파일을 다시 고치고 누르면 대화상자 없이 둘 다 저장(파일 내용 확인). 원래 선택한 탭으로 돌아옴. Ctrl+Shift+S도 저장.
- Undo/Redo: 편집기에 `\nWHERE id = 1`을 넣고 Undo → 글이 없어짐, Redo → 다시 생김.
- Comment/Uncomment: 두 줄 선택 → `--SELECT…`/`--WHERE…` → 원래대로. Increase/Decrease Indent: 두 줄 앞에 탭 → 원래대로. Format SQL(선택 없음): 문서 전체가
  `SELECT\n    *\nFROM\n    dbo.items\nWHERE\n    id = 1`로 바뀜.
- Query History: "쿼리 기록" 창이 열림. Find: "찾기" 창이 열림.
- 도구 창 단추 4개: 누를 때마다 Object Explorer·Registered Servers·Query Library·Properties가 숨었다 나타나고, 단추의 눌린 모양이 따라감.
- 넘침(캡처 `{ko,en}-{light,dark}-{1920,1280}*.png`, `ko-light-main-overflow-*.png`): 1920px(논리 1280)에서 주 도구 막대는 460(영어 487)만 써서 넘침 없음.
  1280px(논리 853)에서도 주 도구 막대는 839 안에 다 들어감(잘림 없음). 600px까지 좁히면 뒤쪽 4개(개체 탐색기·쿼리 라이브러리·속성·찾기)가 » 메뉴로 감.
  쿼리 창 도구 막대(기본 배치: Registered Servers·Object Explorer와 Properties가 열린 상태): 1920px에서 642 폭이라 콤보가 최소(100·100)로 줄고, 한국어는 모두 보이며
  영어는 글자가 길어 Format SQL·Query History가 » 메뉴로 감. 1280px에서는 215 폭이라 Execute·Stop·Estimated Plan 뒤가 모두 » 메뉴(Actual Plan ~ Query History)로 가고,
  연결·DB 콤보는 메뉴에 넣을 수 없어 보이지 않는다(도구 창을 닫으면 다시 보임).
- 다크 테마에서 눌린 도구 창 단추 바탕이 `#494B4E`로 보임(바꾸기 전 `#2A2C2F`, 캔버스 `#26282C`).
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: Edit 메뉴 Undo/Redo의 켜고 끄기, Tools > Activity Monitor, File 메뉴, 쿼리 창 도구 막대 배치.

### Chromite Agent 단추를 오른쪽 끝으로 (2026-11-08, 앱 변경 #6)

사용자 요청(2026-11-08): "맨 위 단축 아이콘 중 Chromite Agent 아이콘을 가장 우측에 배치하고 아이콘과 글자를 함께 나타내라".
"가장 우측"은 도구 막대 오른쪽 끝 정렬로 해석했다(남는 공간을 사이에 둠, DataGrip의 AI 단추 자리).

- **자리와 모양:** 주 도구 막대 마지막 단추(Find) 뒤, 남는 공간 다음 오른쪽 끝. 아이콘 `ai-assistant` + 글자 "Chromite Agent"(토글 동작의 이름 = `AiMessages.ai_title`).
  글자 단추 여백은 New Query와 같다(`UiIcons.useToolButtonMargin`, 이번에 `createToolButton`의 여백 코드를 토글 단추도 쓰게 떼어 냄). 도구 창 단추 묶음에서는 빠졌고
  Properties 뒤 구분선 다음이 Find다.
- **그대로인 것:** 도구 창 토글 동작(`ToolWindow#getToggleAction`, 보이면 눌린 모양), 도구 설명 "Chromite Agent (Ctrl+Alt+I)", View 메뉴와 단축키.
- **오른쪽 끝 정렬(`OverflowToolBar.setTrailing`):** 이 배치 관리자는 컴포넌트를 기본 폭으로만 놓으므로 접착 컴포넌트(`Box.createHorizontalGlue`)로 공간을 채울 수 없고,
  넘침 순서도 정할 수 없다. 그래서 컴포넌트에 "오른쪽 끝" 표시를 두고, 배치할 때 그 폭을 오른쪽 끝에 먼저 잡은 뒤 나머지를 왼쪽부터 놓는다.
  폭이 모자라면 나머지 단추가 뒤에서부터 » 메뉴로 넘치고, » 단추는 Agent 단추 바로 왼쪽에 놓인다. 도구 막대가 Agent 단추와 » 단추도 못 담을 만큼 좁으면
  (실제 창에서는 생기지 않는 폭) Agent 단추도 다른 단추처럼 차례대로 놓이고 넘친다.

## SQL Editor 도구 막대 (2026-11-07)

사용자 결정(2026-11-07): 메인 창 위의 도구 막대를 SSMS처럼 **두 줄**로 한다. 1줄은 지금의 Standard 도구 막대(그대로), 2줄은 **활성 쿼리 창**에 적용하는
SQL Editor 도구 막대다. 쿼리 창마다 있던 도구 막대는 없앴다(편집기가 한 줄 높아지고 단추가 늘 같은 자리에 있다).

### 구성과 동작

| 2줄 (│ = 구분선) | 연결 ▾ │ DB/스키마 ▾ │ Execute(아이콘 + 글자), Stop │ Estimated Plan, Actual Plan(아이콘 + 글자, 토글) │ Commit, Roll Back, 커밋 방식 │ Comment, Uncomment │ Decrease Indent, Increase Indent │ Format SQL │ Query History |
|---|---|

(2026-11-08 순서: 콤보가 맨 앞이다. 아래 "콤보를 맨 앞으로, 연결 콤보 220" 참고. Commit·Roll Back·커밋 방식은 [transactions.md](transactions.md).)

- **활성 쿼리 창을 따름:** 문서 탭을 바꾸면 2줄에 그 쿼리 창의 연결·DB 콤보가 끼워지고, 단추 상태(실행 중이면 Execute 꺼짐·Stop 켜짐, 예상 계획 중이면 계획 꺼짐,
  Actual Plan 눌림)가 그 창의 것으로 바뀐다. Actual Plan은 쿼리 창마다 기억한다(이전과 같음).
  창의 연결이 실제 계획을 주지 않으면(SQL Server·Oracle·Altibase 밖, 연결된 뒤 판단) Actual Plan은 꺼지고 도구 설명이 이유를 말한다
  ([swing-sql-editor-plus.md](swing-sql-editor-plus.md) "실제 계획 토글을 Altibase·Oracle로").
- **쿼리 창이 아닐 때:** 활성 문서가 테이블 디자이너·ER 다이어그램·도구 문서·DDL 탭이거나 문서가 없으면 2줄은 보인 채 모든 컨트롤이 꺼진다(SSMS).
  콤보 자리에는 같은 폭의 빈 콤보(꺼짐)를 두어 막대가 움직이지 않는다.
- **단축키:** 그대로다. F5(Execute), Alt+Break(Stop), Ctrl+L(예상 계획), Ctrl+M(실제 계획)은 Query 메뉴 단축키이고 2줄 단추와 같은 동작을 쓴다.
  편집 명령의 키(Ctrl+K Ctrl+C 등)는 편집 영역이 직접 받는다.
- **좁은 창:** 두 줄 모두 `OverflowToolBar`다. 폭이 모자라면 2줄의 콤보가 먼저 줄고(최소 100·100), 그래도 넘치는 뒤쪽 단추는 오른쪽 끝 » 메뉴로 간다.
  콤보는 메뉴에 넣을 수 없어 넘치면 보이지 않는다(창을 넓히면 다시 보임).
- **크기·모양:** 두 줄 모두 캔버스 위 투명 도구 막대(주 도구 막대 모양)이고 높이는 밀도 B 32다. 1줄은 `size.toolbar.main`(단추 24 + 위아래 4),
  2줄은 28px 콤보가 들어가는 `size.toolbar.editor`(콤보 28 + 위아래 2)를 쓴다. 같은 여백을 쓰면 2줄이 36이 되어 두 줄 높이가 달라진다.

### 설계

- **단추 = Query 메뉴 동작:** Execute·Stop·예상 계획은 `MainFrame`의 Query 메뉴 `Action`, Actual Plan은 Include Actual Execution Plan을 선택 상태가 있는 `Action`
  (`SELECTED_KEY`)으로 바꿔 메뉴 체크 항목과 토글 단추가 같이 쓴다. 켜짐은 `updateQueryActions`가 활성 쿼리 창의 `canExecute`·`canCancel`·`canExplain`으로 정한다.
  쿼리 창 도구 막대에 있던 세밀한 규칙(결과 저장 중·계획 중에는 Execute 끔, Stop을 누른 뒤에는 Stop 끔)이 이제 메뉴에도 적용된다.
  쿼리 창은 상태가 바뀔 때 `Host#executionStateChanged`로 알린다(Stop을 누를 때도).
- **콤보는 쿼리 창의 것:** 연결·DB 콤보의 모델·리스너·켜짐(실행 중 끔, 스키마를 바꿀 수 없으면 끔)은 창마다의 상태다. 한 쌍을 두고 모델을 바꿔 끼우면
  `SqlEditorPanel`의 콤보 코드를 크게 고쳐야 하므로, 쿼리 창이 콤보를 만들고 가지며 2줄이 활성 창의 콤보를 그 자리에 끼운다(결과 탭·쿼리 상태 줄과 같은 방식).
  2줄 밖에 있던 콤보(비활성 창의 것, 빈 콤보)는 테마 전환을 받지 못했으므로 끼울 때 Look and Feel을 맞춘다(`MainFrame.updateLookAndFeel`).
- **편집 명령·Query History:** 2줄의 동작이다. 활성 쿼리 창이 없으면 꺼진다. 편집 명령은 Edit > Advanced와 같은 `SqlTextArea#runCommand`이고 편집 영역에 초점을 돌려준다.
  Query > Query History… 메뉴는 그대로 쿼리 창이 없어도 열린다.
- **Query Library 쿼리 창:** 머리말(이름·설명·대상)은 도구 막대 아래 대신 편집기 위에 붙는다.

### 바뀐 파일

| 파일 | 바뀐 것 |
|---|---|
| `SqlEditorToolBar`(새 파일) | 2줄: 단추, 활성 창 콤보 끼우기, 빈 콤보, 편집 명령·Query History 동작 |
| `MainFrame` | 두 줄 배치, Include Actual Execution Plan을 `Action`으로, Query 동작 켜짐을 쿼리 창 상태로, 활성 문서가 바뀌면 2줄 갱신, `updateLookAndFeel`을 2줄과 같이 씀 |
| `SqlEditorPanel` | 도구 막대 삭제, 콤보 설정을 `createPickers`로, `getConnectionCombo`·`getSchemaCombo`·`sizePicker`, `canExecute`·`canCancel`·`canExplain`, 실제 계획을 필드로 |
| `OverflowToolBar`, `UiSizes`, `SqlEditorExtension`, `QueryLibraryEditor`, `internal/ToolbarMessages` | 주석(쿼리 창 도구 막대 → SQL Editor 도구 막대) |
| `internal/EditorMessages`(`.properties`, `_ko`) | 쓰지 않게 된 `editor_sql_cancel` 삭제 |

지운 코드(규칙 4): `SqlEditorPanel`의 `createToolBar`, `createCommandButton`, `executeAction`·`cancelAction`·`estimatedPlanAction`, 실제 계획 토글 단추(`actualPlanButton`),
`Host#openQueryHistory`(이제 `MainFrame` 안의 메서드), 문구 `editor_sql_cancel`("Cancel"/"실행 취소", 쿼리 창 Stop 동작의 이름). 아이콘은 모두 2줄이 계속 쓴다.

### 확인 (2026-11-07)

빌드 `-Pproduct-chromite-swing` BUILD SUCCESS, `java tools/jre/BundleJre.java windows-x64`로 번들 JRE를 넣고 `-vm` 없이 실행. Windows 11, 150%, 창 1280×800 논리,
개인 작업 공간 `D:\Tools\_scratch\ssms-two-row-toolbar\ws`, 한국어(OS 기본)와 `-nl en`, 라이트·다크. 시험 DB는 SQL Server 2022와 PostgreSQL 17의 `chr_ssms_two_row_toolbar`.
프로세스 안 EDT 시험 에이전트(OS 입력 없음)로 2줄 단추를 누르고(`doClick`) 상태를 읽었다. 캡처는 창만 `D:\Tools\_scratch\ssms-two-row-toolbar\shots\`.

| 경우 | 결과 |
|---|---|
| 문서 없음 | 1줄·2줄 모두 높이 32. 2줄 단추·빈 콤보(260·200) 모두 꺼짐, 막대는 보임(`*-01-no-query`) |
| Object Explorer New Query로 SQL Server·PostgreSQL 쿼리 창 | 2줄 콤보가 활성 창의 것(연결 `TR PG`·스키마 `public` / `TR MSSQL`·DB `chr_ssms_two_row_toolbar`), 단추 켜짐 |
| 탭 바꾸기 | 연결·DB 콤보와 단추 상태가 그 창의 것으로 바뀜 |
| 2줄 Execute(PostgreSQL) | 실행, Results 탭에 결과 |
| SQL Server `WAITFOR DELAY '00:00:20'` 실행 중 | Execute 꺼짐·Stop 켜짐·콤보 꺼짐. PostgreSQL 탭으로 가면 Execute 켜짐·Stop 꺼짐, 돌아오면 다시 실행 중 상태 |
| 2줄 Stop | 취소, Messages 탭, Execute 켜짐·Stop 꺼짐 |
| 2줄 예상 계획 | Execution plan 탭에 계획(Clustered Index Seek) |
| 2줄 Actual Plan | 그 창만 켜짐(다른 탭에서는 꺼짐, 돌아오면 켜짐), 다시 누르면 꺼짐. F5(메뉴 단축키)로 실행 |
| 좁은 창(논리 560) | 2줄 콤보가 최소 100까지 줄고 들여쓰기·Format SQL·Query History가 » 메뉴로. 900에서는 콤보만 줄어듦(248·193) |
| 쿼리 창을 모두 닫음 | 2줄 다시 꺼짐(빈 콤보). 다크에서 빈 콤보도 다크(끼울 때 Look and Feel 맞춤 확인) |
| Results 기본 높이 | 처음 연 쿼리 창의 Results 카드 280(Change A) |

- 연결하는 중에 Stop을 누르면 "'TR MSSQL'에 연결하지 못했습니다 / Connection has been canceled" 오류 대화상자가 뜬다. Stop은 이전 쿼리 창 Cancel과 같은 `SqlEditorPanel#cancel`을 부르므로
  이번 변경과 무관하다(사용자가 취소한 연결을 오류로 보이는 것은 따로 볼 일).
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: Query 메뉴 Execute·Cancel·계획의 켜고 끄기, 연결·DB 콤보(연결 바꾸기, 기본 DB 바꾸기),
  Query Library 쿼리 창의 머리말 위치, 좁은 창의 도구 막대 넘침.

### 남긴 것

- 연결·DB 콤보가 넘침 메뉴에 들어가지 않는 것(이전과 같음). 아주 좁은 창에서는 콤보가 보이지 않는다.
- 관찰(이번 범위 밖, 고치지 않음): 문서 탭이 열린 채 테마를 바꾼 뒤 탭을 모두 닫고 새 쿼리 창을 열면 `BasicTabbedPaneUI.getTextViewForTab`에서
  `ArrayIndexOutOfBoundsException`이 나고 문서 탭이 깨진다. `MainFrame`의 문서 탭 `ContainerListener`(`updateDocumentCard`의 `CardLayout.show` → validate)가
  테마 전환으로 다시 등록된 탭 UI의 리스너보다 먼저 불려, UI가 탭 목록을 갱신하기 전에 배치하기 때문으로 보인다. 확인 중 테마는 문서가 없을 때만 바꿔 피했다.
- Actual Plan 토글은 글자 단추라 다른 단추(24)보다 1 높다(25, design-spec "Pretendard와 밀도 B"에 적은 것과 같음). 막대 높이(콤보 28)에는 영향이 없다.

### 콤보를 맨 앞으로, 연결 콤보 220 (2026-11-08, 앱 변경 #3)

사용자 요청(2026-11-08): "연결선택 콤보가 1번 DB 스키마 선택이 2번으로 되게 한다", "연결 선택 콤보의 크기를 조금 더 줄인다".

- **순서:** 연결 ▾ │ DB/스키마 ▾ │ Execute, Stop │ 예상 계획, 실제 계획 │ Commit, Roll Back, 커밋 방식 │ Comment, Uncomment │ Decrease Indent, Increase Indent │
  Format SQL │ Query History. 콤보 뒤의 순서와 구분선은 그대로다(`SqlEditorToolBar` 생성자).
- **연결 콤보 폭:** `SqlEditorPanel.CONNECTION_PICKER_WIDTH` 260 → 220(약 15%, DB/스키마 200보다는 넓게). 빈 콤보(`emptyConnectionCombo`)도 같은 상수라 같이 바뀐다.
- **긴 이름:** 콤보는 폭이 고정이라 긴 이름은 렌더러(JLabel)가 말줄임표로 줄인다. 그래서 두 콤보의 도구 설명에 고른 항목의 전체 이름을 넣었다
  (`editor_sql_connection_selected_tip` "이 쿼리 창의 연결: {0}", `editor_sql_schema_selected_tip`). 고른 것이 없으면 이전 도구 설명이다.
  항목은 `setModel`로 다시 채우므로(항목 이벤트 없음) 선택 이벤트와 `model` 속성 변경, 연결 이름 변경(`connectionChanged`)에서 다시 쓴다.
  DB/스키마 콤보도 같은 원인(고정 폭 200)이라 같이 고쳤다. 펼친 목록은 FlatLaf가 항목 폭에 맞춰 넓히므로 잘리지 않는다(아래 확인).
- **위치로 바꿔 끼우기:** 활성 쿼리 창이 바뀌면 `replace`가 빼낸 콤보의 위치(`getComponentIndex`)에 새 콤보를 넣으므로 순서와 상관없이 맞다(아래 확인).
- **좁은 창:** 배치(`OverflowToolBar`)는 그대로다. 콤보가 먼저 최소 100까지 줄고, 그래도 넘치면 뒤쪽 단추(Query History)부터 » 메뉴로 간다.
  콤보가 앞이라 Execute는 콤보 두 개가 최소 폭이 된 뒤에도 남는다.
- 확인 결과는 "도구 창 도킹"의 "확인 (2026-11-08, 앱 변경 #3·#6·#12)"에 함께 적었다.

## 자동 커밋 상태 (2026-11-07)

사용자 요청(2026-11-07): "AutoCommit이 지금 어떤상태인지 상태표시줄에 표시해준다". 이어서 사용자 결정:
- "상태표시줄에서 바꾸면 안된다. 사고난다. 옵션 설정은 설정 화면에서 하도록 해야 한다. 다만 상태표시줄에서 오토커밋을 클릭하면 옵션 창으로 안내까지는 허용한다."
- "열려있는 세션에도 반영되는 방법을 찾아야 한다." (설정을 바꾸면 다시 연결하지 않아도 열린 세션에 적용)
- "on일 경우 초록색 동그라미, off일 경우 빨간색 동그라미로 색상도 나타내야 한다."

2026-11-08 사용자 결정(B-S18): 열린 세션 하나의 방식은 Query &gt; Auto-Commit과 SQL Editor 도구 막대 단추로 바꾼다(연결 설정은 그대로,
[transactions.md](transactions.md) "커밋 방식 전환"). 상태 줄은 이 절대로 보이기와 설정 안내만 한다.

### 표시

- **위치: 쿼리 상태 줄**(서버 │ 사용자 │ DB/스키마 │ **커밋 방식** │ 시간 │ 행 │ Ln, Col). 커밋 방식은 쿼리 창이 쓰는 실행 컨텍스트(연결의 주 컨텍스트)의 상태라
  연결마다 다르다. 메인 창 상태 표시줄은 작업 공간 전체(연결 수)를 보이고, 연결별 정보(서버, 사용자, DB)는 쿼리 상태 줄에 있으므로(design-spec "9. 쿼리 상태 줄") 여기에 둔다.
  메인 창은 활성 쿼리 창의 상태 줄을 끼우므로 활성 쿼리 창의 값이 보이고, 쿼리 창이 없으면 줄 자체가 없다.
- **모양:** 점(size.statusDot 6) + 글자. 자동 커밋은 초록 점(`Chromite.status.success`) + "자동 커밋"/"Auto-commit", 수동 커밋은 빨간 점(`Chromite.status.error`) +
  "수동 커밋"/"Manual commit". 색은 두 테마의 상태 색 토큰을 그릴 때 읽는다(라이트 `#208A3C`/`#DB3B4B`, 다크 `#5FAD65`/`#DB5C5C`). 글자가 함께 있어 색만으로 구별하지 않는다.
  점은 연결 상태 점(`UiIcons.withStatusDot`)과 같은 크기·색 규칙의 새 `UiIcons.statusDot`이다.
- **숨김:** 연결되어 있지 않으면(연결 전, 연결 끊김) 칸을 숨긴다(빨간 점을 보이지 않는다). 트랜잭션 관리자가 없는 연결도 숨긴다.
- **도구 설명:** 상태 설명(수동 커밋은 "COMMIT을 실행할 때까지 열린 트랜잭션에 남는다, ROLLBACK은 버린다"), 연결 설정에 저장한 값이 실제 세션과 다르면
  "연결 설정은 지금 …입니다. 다시 연결하면 적용됩니다", 끝에 "클릭하면 연결의 자동 커밋 설정을 엽니다".
- **보이는 값은 열린 세션의 실제 상태다.** 연결 설정의 기본값이 아니라 컨텍스트의 `DBCTransactionManager#isAutoCommit`을 읽는다. 컨텍스트가 값을 캐시하지만 연결 직후 첫 읽기는
  드라이버에 묻으므로 작업 스레드에서 읽는다(`SqlEditorPanel#refreshTransactionMode`, SWT `DataSourceAutoCommitHandler#updateElement`와 같은 API).
- **갱신 시점:** 연결 고르기(2줄 연결 콤보), 연결·연결 끊기·연결 설정 변경(연결 갱신 이벤트 → `connectionChanged`), 실행이 끝날 때, 기본 DB 바꾸기(모두 `refreshSchemas`를 거침),
  쿼리 창이 활성이 될 때(같은 연결의 다른 창이 세션을 함께 쓴다), 열린 세션에 적용한 뒤.
  SQL Server `SET IMPLICIT_TRANSACTIONS ON`처럼 드라이버의 자동 커밋 플래그를 바꾸지 않는 문은 알 수 없다(JDBC `getAutoCommit`은 그대로 true). 서버에 물어야 하므로 이번에는 하지 않았다.

### 바꾸기 (설정 화면에서만)

- 상태 줄은 커밋 방식을 **바꾸지 않는다**(토글·팝업 메뉴 없음). 칸을 누르면 활성 쿼리 창 연결의 **연결 편집** 대화상자가 Options를 펼치고
  Connection Properties 탭의 **Auto-commit** 칸에 초점을 둔 채 열린다(`ConnectionCommands.editAutoCommit` → `ConnectionDialog.openEditAutoCommit` → `ConnectionSettingsPanel.focusAutoCommit`).
  커밋 방식을 실제로 정하는 설정은 이것(연결의 bootstrap `defaultAutoCommit`, "기본값(연결 유형)"이면 연결 유형의 자동 커밋)뿐이다. Swing Options에는 커밋 방식 설정이 없다.
- **열린 세션에 적용(`AutoCommitApplier`):** 모델은 이 기본값을 컨텍스트를 열 때만 읽는다(`JDBCExecutionContext.connect`의 `isDefaultAutoCommit`).
  그래서 연결 편집에서 Save했을 때 Auto-commit의 실제 값(연결 유형 포함)이 바뀌었고 연결되어 있으면, 그 연결의 모든 인스턴스의 모든 컨텍스트(주 컨텍스트 = 쿼리 창들,
  메타데이터, 관리 도구의 격리 컨텍스트)에 `DBCTransactionManager#setAutoCommit`을 부른다. 다시 연결했을 때와 같은 결과이고, 다시 연결 질문은 그 밖의 설정도 바뀌었을 때만 묻는다.
- **안전:** 자동 커밋을 켜면 JDBC가 열린 트랜잭션을 조용히 커밋한다. 그래서 먼저 쿼리 관리자의 트랜잭션 추적(`QMUtils.isTransactionActive`)으로 커밋하지 않은 변경이 있는 컨텍스트를 찾고,
  있으면 한 대화상자에 목록(쿼리 창 이름과 연결, 쿼리 창이 아닌 세션은 "TR MSSQL의 Metadata 세션" 식)을 보이고 **커밋하고 적용 / 롤백하고 적용 / 취소**를 묻는다(기본은 취소).
  취소하면 열린 세션은 수동 커밋 그대로이고, 저장한 설정은 다음 연결부터 적용된다(도구 설명이 이를 알린다). 끄기(자동 → 수동)는 묻지 않는다.
- **오류:** 전환하지 못한 컨텍스트(드라이버 오류)는 창 이름과 오류 문구를 한 대화상자로 알린다. 끝나면 연결 갱신 이벤트를 보내 모든 쿼리 창이 실제 상태를 다시 읽으므로 상태 줄은 실제 값을 보인다.
- JDBC 호출(현재 값 읽기, 커밋·롤백, 전환)은 모두 작업 스레드에서 하고, 질문·결과만 EDT에서 보인다.

### 바뀐 파일

| 파일 | 바뀐 것 |
|---|---|
| `QueryStatusLine` | 커밋 방식 칸(점 + 글자, 도구 설명, 누르면 설정으로 안내하는 동작) |
| `SqlEditorPanel` | `refreshTransactionMode`(작업 스레드에서 실제 상태 읽기), `refreshSchemas`에서 부름, 칸의 동작을 연결 설정 열기로 |
| `MainFrame` | 쿼리 창이 활성이 되면 커밋 방식을 다시 읽음 |
| `AutoCommitApplier`(새 파일) | 열린 세션에 적용, 커밋하지 않은 변경 확인 대화상자, 오류 알림 |
| `ConnectionDialog` | `openEditAutoCommit`, Save 뒤 Auto-commit이 바뀌었으면 열린 세션에 적용하고 그 밖의 변경이 있을 때만 다시 연결 질문 |
| `ConnectionSettingsPanel` | `focusAutoCommit`(Options 펼치고 Auto-commit 칸에 초점) |
| `ConnectionCommands` | `editAutoCommit` |
| `UiIcons` | `statusDot`(아이콘 없는 상태 점) |
| `internal/EditorMessages`, `internal/ConnectionMessages`(`_ko`) | 칸 글자·도구 설명, 적용 대화상자 문구 |

### 확인 (2026-11-07)

빌드 `-Pproduct-chromite-swing` BUILD SUCCESS, 번들 JRE로 실행. 개인 작업 공간, SQL Server 2022·PostgreSQL 17의 `chr_ssms_two_row_toolbar`, EDT 시험 에이전트.
캡처는 창만 `D:\Tools\_scratch\ssms-two-row-toolbar\shots\c-*.png`.

| 경우 | 결과 |
|---|---|
| SQL Server 쿼리 창 2개 + PostgreSQL 쿼리 창 1개, 처음 | 셋 다 초록 점 "Auto-commit"(연결 유형 Development 기본값) |
| 상태 줄 칸 클릭 | 연결 편집이 Options를 펼치고 Auto-commit 칸에 초점(`c-en-light-02-settings-dialog`) |
| SQL Server를 Manual commit으로 Save | 다시 연결 질문 없이 SQL Server 창 2개 모두 빨간 점 "Manual commit", PostgreSQL은 그대로 |
| 수동 커밋에서 `INSERT … (10, …)` 뒤 Auto-commit으로 Save | 대화상자에 "SQLQuery1.sql - TR MSSQL, SQLQuery2.sql - TR MSSQL"(`c-en-light-05-pending-dialog`). 롤백하고 적용 → 두 창 Auto-commit, `sqlcmd`로 Id 10 = 0행 |
| 같은 순서로 `INSERT … (11, …)` 뒤 커밋하고 적용 | 두 창 Auto-commit, Id 11 = 1행 |
| PostgreSQL에서 같은 두 경우 | 롤백 → id 10 없음, 커밋 → id 11 있음(`psql`) |
| PostgreSQL 수동 + 커밋 안 한 INSERT에서 Auto-commit으로 Save → 취소 | 세션은 Manual commit 그대로, 도구 설명 "The connection setting is now Auto-commit; it applies when you reconnect" |
| PostgreSQL 연결 끊기 | PostgreSQL 창의 칸이 숨겨짐(빨간 점 없음). 다시 실행해 연결하면 다시 보임 |
| 탭 바꾸기 | 각 창의 값(SQL Server 수동, PostgreSQL 자동)이 보임. 라이트·다크(`c-en-*`, `c-ko-*`) |
| 한국어: 2줄 연결 콤보로 SQL Server 창을 PostgreSQL로 바꿈 | 연결 전에는 칸 숨김, 실행해 연결하면 초록 점 "자동 커밋" |
| 한국어: SQL Server 수동 + `INSERT … (12, …)` → 자동 커밋으로 저장 | 대화상자 "• SQLQuery1.sql - TR MSSQL"(`c-ko-light-04-pending-dialog`), 롤백하고 적용 → Id 12 = 0행 |

- 기본 배치(Object Explorer·Properties가 열린 1280 창)에서는 칸이 하나 늘어 왼쪽 상태 문구가 "…"로 줄어든다(아이콘은 보임, 도구 설명에 전문).
  (2026-11-08 고침: 폭이 모자라면 서버 칸이 먼저 줄거나 빠진다. 상태 문구에는 도구 설명이 없었다. [swing-followup-fixes.md](swing-followup-fixes.md) "작은 화면 수정")
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 연결 편집의 Save(다시 연결 질문 조건이 바뀜), 쿼리 상태 줄 배치(칸이 하나 늘어 좁은 창에서 왼쪽 상태 문구가 더 일찍 줄어듦),
  수동 커밋 연결의 실행.

### 남긴 것 (사용자에게 물을 것)

- 실행 중 커밋 방식 전환(토글)과 Commit/Rollback 단추는 요청 범위가 아니라 만들지 않았다. 지금 수동 커밋 연결에서 변경을 확정하려면 쿼리 창에서 `COMMIT`/`ROLLBACK`을 실행해야 한다.
  상태 줄에서는 바꾸지 않는다는 결정과 별개로, 도구 막대나 Query 메뉴에 Commit/Rollback을 둘지 결정이 필요하다.
- `SET IMPLICIT_TRANSACTIONS ON`(SQL Server) 같은 서버 쪽 트랜잭션 모드는 표시하지 않는다(위 "갱신 시점").
- 쿼리 창마다 따로 세션을 쓰는 기능이 생기면 적용 대상 목록의 이름 붙이기(지금은 주 컨텍스트 = 그 연결의 모든 쿼리 창)를 고쳐야 한다.

## 도구 창 도킹 (2026-11-06)

사용자 결정(2026-11-06): 도구 창을 익숙한 SQL Server 도구·IDE처럼 옮길 수 있게 한다. 머리말을 끌어 왼쪽·오른쪽·아래나 다른 도구 창 안(탭)에 놓고,
떠 있는 창으로 띄우고, 배치를 다음 실행에도 쓰고, Window > Reset Window Layout으로 기본 배치로 되돌린다. 자동 숨기기는 선택 사항이었고 라이브러리에 있어 넣었다.

### 배치

```
┌ 메뉴 · 주 도구 막대 (캔버스) ─────────────────────────────────────────────────────┐
│ Object Explorer   │ 문서 탭 (편집기, 디자이너, 다이어그램, 도구 문서)    │ Properties       │
│  (왼쪽 22%)        │                                                │  (오른쪽 18%)     │
├───────────────────┤                                                │  Query Library는 │
│ Registered Servers│────────────────────────────────────────────────│  보이면 이 위     │
│                   │ Results | Messages | Execution plan (쿼리 창 안) │                  │
├───────────────────┴────────────────────────────────────────────────┴──────────────────┤
│ 상태 표시줄                                                                            │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

- **기본 배치:** 왼쪽 열 = Object Explorer(위 60%) + Registered Servers(아래 40%), 오른쪽 열 = Properties, Query Library는 숨김(Ctrl+Alt+T로 보이면 Properties 위 50%).
  이전 배치는 Registered Servers가 Object Explorer 위였다(사용자 결정으로 바꿈).
- **너비:** 기본 열 너비는 픽셀이 아니라 비율이다(왼쪽 22%, 오른쪽 18%). 1280px 창에서 두 열이 300px이면 쿼리 창이 좁아(합친 뒤 약 215px) 문서 영역이 60%를 갖게 했다.
- **가운데:** 문서 영역(문서 탭 카드 + 그 아래 Results 카드)은 움직이지 않는 도킹 대상 하나다. Results/Messages/실행 계획은 SSMS처럼 쿼리 창 안이라 도킹하지 않고
  지금처럼 문서 영역의 분할 창 아래쪽이다(`ToolWindow.SplitHost`, Ctrl+R, 크기는 `results.size`. 표시 여부는 활성 쿼리 창이 정한다: "Results 자동 숨김").
- **도구 창 = 도킹 대상:** Object Explorer, Registered Servers, Properties, Query Library. 머리말(높이 40)이 끌기 손잡이이고 오른쪽에 [⋮ 옵션] [— 숨기기]가 있다.
  옵션 메뉴: Float(떠 있는 창), Dock(떠 있는 창·자동 숨기기에서 메인 창의 마지막 자리로), Auto Hide(창 가장자리 막대로 접기), Hide.
- **끌어 놓기:** 머리말을 끌면 놓을 자리가 강조색으로 칠해지고(창 가장자리 손잡이, 도구 창 손잡이: 위·아래·왼쪽·오른쪽·가운데=탭), 메인 창 밖에 놓으면 떠 있는 창이 된다.
  같은 탭 묶음의 도구 창은 아래쪽 탭으로 바꾼다. 문서 영역 한가운데에 놓으면 문서 탭에 섞이지 않도록 마우스에서 가장 가까운 가장자리에 놓는다.
- **View 메뉴와 단축키:** 그대로다(Object Explorer F8, Registered Servers Ctrl+Alt+G, Properties F4, Query Library Ctrl+Alt+T, Results Ctrl+R).
  토글 상태는 실제 배치에서 다시 읽으므로 떠 있는 창을 닫거나 탭을 닫아도 맞다. 숨긴 도구 창을 다시 보이면 숨기기 전 자리(이웃 도구 창이나 창 가장자리, 방향, 비율)에 놓는다.

### 라이브러리 선택: ModernDocking 1.4.9

| 항목 | 확인한 것 (2026-11-06) |
|---|---|
| 버전 | Maven Central `io.github.andrewauclair:modern-docking-api` 최신 1.4.9(`maven-metadata.xml` latest/release, 2026-06-10), GitHub 릴리스 1.4.9(prerelease 아님) |
| 라이선스 | MIT(POM `<licenses>`, GitHub 저장소 LICENSE `Copyright (c) 2022 Andrew Auclair`). jar 안에 LICENSE가 없어 전문을 `META-INF/LICENSE-ModernDocking.txt`로 번들에 넣었다 |
| 의존성 | `modern-docking-api`는 의존성 없음(module-info: `java.desktop`, `java.logging`만). `modern-docking-single-app`(전역 정적 인스턴스)과 `modern-docking-ui`(FlatLaf 머리말, 실행 시 `flatlaf-extras` 필요)는 쓰지 않는다 |
| SWT/OSGi | AWT/Swing만 쓰고 SWT 의존 없음. OSGi 매니페스트가 없어 FlatLaf처럼 `Bundle-ClassPath`에 넣었다(번들 안에서는 module-info를 무시). 바이트코드 Java 11(major 55) |
| JBR 25 | `-vm ...\jbrsdk-25\bin\server\jvm.dll`로 실행해 끌기 인식기(`sun.awt.windows.WMouseDragGestureRecognizer`), 떠 있는 창, 자동 숨기기, 배치 저장·복원 확인 |
| 체크섬 | jar SHA-1 `9228607916d1b696764c78c187039ca923cc2edf`(Central `.sha1`과 같음), SHA-256 `6897798a0f1f04c57c2d6916350e4dda37312675fda34fcbaa83f3deab76bb22` |
| Islands 모양 | 머리말은 `Dockable.createHeaderUI`로 직접 만들고, 패널 테두리·바탕은 바꿀 수 있어(아래 "모양") 그대로 쓸 수 있었다. 다른 라이브러리 검토는 하지 않았다 |

- jar는 FlatLaf와 같은 방식이다: 번들 `pom.xml`의 `maven-dependency-plugin:copy`가 `lib/modern-docking-api.jar`로 복사하고(버전은 `moderndocking.version` 한 곳),
  `MANIFEST.MF`의 `Bundle-ClassPath`와 `build.properties`의 `bin.includes`에 넣었다. `NOTICE`에 출처를 적었다.
- 라이브러리의 전역 설정 두 가지를 바꾼다: 마우스를 올린 도킹 패널의 테두리 강조를 끄고(Islands 카드가 이미 구분), AWT 전체의 끌기 문턱값(`awt.dnd.drag.threshold`)을
  바꾸지 않게 한다(트리·표의 끌기 동작 유지).

### 구성

| 파일 | 역할 |
|---|---|
| `ToolWindowDocking`(새 파일) | 메인 창의 `DockingAPI` 하나, 도킹 루트(Islands 카드 그리기), 문서 영역 도킹 대상, 도구 창 도킹 대상과 머리말·옵션 메뉴, 보이기·숨기기와 마지막 자리, 기본 배치, 배치 파일 읽기·검사·저장, 이전 설정 옮기기 |
| `ToolWindow` | 제목, View 메뉴 토글, 숨기기 단추, `isShown`/`setShown`(공개 API 그대로). 배치는 `ToolWindow.Host`: 도킹(`ToolWindowDocking`) 또는 분할 창(`SplitHost`, Results) |
| `MainFrame` | 도구 창을 `ToolWindowDocking`에 등록(기본 자리), Results는 `SplitHost`, Reset Window Layout = 기본 도킹 배치 + Results 기본 크기, 창을 닫을 때 저장·떠 있는 창 닫기 |
| `IslandPanel` | 모서리 덮기를 `paintCorners`로 떼어 냄(도킹 루트도 씀), 그리기 영역 밖의 모서리는 건너뜀 |
| `internal/DockingMessages`(새 파일, `_ko`) | 옵션 메뉴 문구 |
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | `ModernDocking.overlayBackground`(놓을 자리 강조색 30%), `handleBackground`·`handleForeground`(손잡이) |
| `icons/ui/**/more.svg`, `tools/branding/gen_ui_icons.py` | 머리말 옵션 아이콘 ⋮(두 팩 × 두 테마, 생성기에 추가) |

지운 코드(규칙 4): `ToolWindow.stackBelow`(한 열에 두 도구 창 쌓기)와 그 열 관리(`upper`/`lower`, `getSlot`, 열 숨기기), `setDefaultShown`,
도구 창마다의 `load`/`save`/`applySize`/`reset`(도킹 도구 창), 도구 창 머리말 `createHeader`/`SeparatedPanel`(→ `ToolWindowDocking.Header`),
`MainFrame`의 Object Explorer·Properties 분할 창(`explorerSplit`, `propertiesSplit`)과 바깥 여백 패널(`islands`, 여백은 도킹 루트가 그림).

### 저장

| 무엇 | 어디 |
|---|---|
| 도킹 배치(메인 창 트리, 탭 묶음, 자동 숨긴 도구 창, 떠 있는 창의 위치·크기·내용) | 작업 공간 `.metadata/chromite-window-layout-1.xml`(라이브러리 XML 형식) |
| 숨긴 도구 창의 마지막 자리 | 번들 설정 `<id>.restorePlace` = `이웃 id|방향|비율`(창 가장자리면 이웃 id 비움) |
| 메인 창 위치·크기·최대화, Results 크기 | 그대로(`mainWindow.*`, `results.size`). 배치 파일의 메인 창 크기는 쓰지 않는다. Results 표시 여부는 2026-11-07부터 저장하지 않는다("Results 자동 숨김") |

- 창을 닫을 때 한 번 저장한다(지금까지와 같음). 떠 있는 창은 메인 창이 보인 뒤에 연다(메인 창 뒤로 숨지 않게).
- **맞지 않는 파일:** 파일이 없거나, XML을 읽을 수 없거나, 모르는 도구 창 id·같은 id 두 번·문서 영역 없음·떠 있는 창의 문서 영역·최대화·지원하지 않는 노드가 있으면 경고 로그를 남기고 기본 배치로 시작한다.
  모르는 id를 라이브러리에 넘기면 저장된 클래스 이름으로 객체를 만들려 하므로 복원 전에 검사한다. 파일 이름의 `-1`은 형식 버전이라, 형식을 바꾸면 이름을 올려 예전 파일을 읽지 않는다.
- **이전 설정 옮기기:** 배치 파일이 없는 첫 실행은 기본 배치로 시작하고, 분할 창 배치가 저장했던 보이기 여부(`objectExplorer.visible` 등, 기본값과 다를 때만 저장되어 있음)만 옮긴 뒤
  `<id>.visible`·`<id>.size`를 지운다. 너비·높이와 Registered Servers가 위였던 순서는 옮기지 않는다(사용자 결정의 새 기본 배치를 쓰기 위해).

### 모양 (Islands)

[design-spec.md](design-spec.md) "적용 현황 — 도구 창 도킹"에 값이 있다. 요점: 라이브러리 패널(분할 창, 탭 묶음, 표시 패널)은 투명하게 하고 1px 테두리를 지운다.
도킹 루트가 캔버스와 카드(도구 창 하나 또는 탭 묶음 하나) 바탕을 칠하고, 자식을 그린 뒤 카드 모서리를 캔버스 색으로 덮는다(`IslandPanel`과 같은 방식, 루트가 그리기 시작점).
카드 사이 틈은 라이브러리 분할 창의 분할선(`SplitPane.dividerSize` 6)이다. 테마를 바꾸면 라이브러리가 테두리를 다시 넣으므로 그 뒤에 다시 꾸민다.

### 확인 (2026-11-06)

빌드 `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Windows 11, 150%, JBR 25(`-vm ...\server\jvm.dll`), 개인 작업 공간 `D:\Tools\_scratch\docking\ws`.
프로세스 안 EDT 시험 에이전트(OS 입력 없음, `D:\Tools\_scratch\docking\agent`, 도킹 명령은 리플렉션으로 `ToolWindowDocking`·`DockingAPI` 호출)로 조작했다. 캡처는 `D:\Tools\_scratch\docking\`.

| 경우 | 결과 |
|---|---|
| 기본 배치 | `H(H(V(objectExplorer / registeredServers) | documents) | properties)`, Query Library 숨김, 토글 4개 상태 일치 |
| Registered Servers를 오른쪽 열로 | `dock(registeredServers, properties, NORTH)` → `... | V(registeredServers / properties)` |
| Properties와 Query Library 탭 | View 토글로 Query Library 보이기 → `dock(queryLibrary, properties, CENTER)` → `Tabs[properties queryLibrary]`, 아래쪽 탭 |
| Object Explorer 떠 있는 창 → 다시 도킹 | 옵션 메뉴 Float → 떠 있는 창 "개체 탐색기", 메뉴 Float 비활성·Dock 활성 → Dock → 숨기기 전 자리(`documents` 왼쪽) |
| 다시 시작 | 떠 있는 Object Explorer와 오른쪽 열·탭 묶음이 그대로 복원(배치 파일 내용 확인) |
| Reset Window Layout | 떠 있는 창이 닫히고 기본 배치, Query Library 숨김 |
| 자동 숨기기 | Properties Auto Hide → 오른쪽 가장자리 세로 막대, 다시 끄면 오른쪽 열로 |
| 테마 | 라이트 ↔ 다크 전환 뒤 카드·캔버스·머리말·탭 묶음 색 |
| 1280px | 기본 배치(창 1280×800, 도킹 영역 1266): Object Explorer 열 272, 문서 영역 745(59%), Properties 225. 1920에서 1280으로 줄이면 라이브러리 분할 창이 양쪽에서 줄여 문서 영역 728(57%) |
| 이전 설정 옮기기 | `properties.visible=false`, `queryLibrary.visible=true`, `*.size`가 있는 작업 공간: 기본 배치에서 Properties 숨김, Query Library가 오른쪽 가장자리, 끝낸 뒤 `.visible`·`.size` 키 없음. Properties를 다시 보이면 오른쪽 가장자리 |
| 끌기 | 실제 마우스 끌기는 OS 입력이 필요해 하지 않았다. 머리말에 라이브러리 끌기 인식기가 붙어 있음을 확인했고, 놓는 자리 처리(`DockingAPI.dock`)는 위 API 호출로 확인했다 |

- 한국어(OS 기본)와 영어(`-nl en`) 둘 다 위 경우를 실행했다(옵션 메뉴 "부동 / 도킹 / 자동 숨기기 / 숨기기", "Float / Dock / Auto Hide / Hide"). 디버그 로그와 `.metadata/.log`에 오류 없음.
- 메인 창이 보이기 전(이전 설정을 옮기며 숨길 때)에는 분할선이 배치되지 않아 비율이 맞지 않으므로 마지막 자리를 기록하지 않는다(처음 확인에서 Properties의 자리가 `EAST 0.83`으로 저장되던 것을 고침).
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 도구 창 보이기·숨기기와 단축키, 도구 막대의 도구 창 토글, Results 숨기기(Ctrl+R)와 쿼리 상태 줄 위치, 테마 전환, 창 닫을 때 저장.

### 남긴 것

- 문서 탭을 끌어 떠 있는 창으로 띄우기(선택 사항, 하지 않음). 떠 있는 창의 카드 둥근 모서리(떠 있는 창은 캔버스 바탕만 맞춤).
- 떠 있는 도구 창 안에서 메인 창 메뉴 단축키(F8 등)는 동작하지 않는다(메뉴 막대가 메인 창에만 있음).
- 숨긴 도구 창을 다시 보일 때 떠 있던 창이면 떠 있는 창이 아니라 메인 창의 마지막 자리에 놓는다.

### 도구 창 최소 폭 (2026-11-07)

사용자 보고(2026-11-07): Properties 창이 일정 폭 이하로 줄어들지 않는다.

- **원인:** 라이브러리의 분할 창(`JSplitPane`)은 양쪽 카드를 최소 크기보다 작게 만들지 않는다. 카드의 최소 폭은 머리말과 내용의 최소 폭에서 나왔고,
  Swing 기본값으로는 내용의 최소 폭이 사실상 전체 폭이었다. Properties(실행 중 덤프, 150%, 논리 픽셀):
  - 위쪽 이름·종류·설명 `JLabel`(`GridLayout`): 레이블의 최소 폭 = 글자 전체 폭. 긴 테이블 이름 378, 테이블 주석 575 → 카드 최소 591. 고른 개체에 따라 바뀐다.
  - 아래 설명 칸의 줄 바꿈 `JTextArea`: 줄 바꿈 뷰(`WrappedPlainView`)의 최소 폭 = 지금 폭이다. 그래서 아무것도 고르지 않아도 카드 최소 폭이 지금 폭(385)과 같아
    전혀 줄일 수 없었고, 한 번 넓히면(585) 다시 줄지 않았다.
  - 같은 원인(내용이 최소 폭을 정함)이 다른 도구 창에도 있었다: 도구 막대(`JToolBar`의 `BoxLayout`, 최소 폭 = 모든 단추) Object Explorer 174, Query Library 200,
    Registered Servers 95, 머리말 제목 레이블(전체 제목 폭 + 단추) Registered Servers 138(영어 제목은 더 넓음).
- **고친 것(`ToolWindowDocking`):** 카드의 최소 폭은 머리말(`Header.getMinimumSize`)이 정하고 내용(`ToolWindowDockable`)은 최소 폭이 없다(높이는 그대로).
  머리말 최소 폭 = 여백 + 제목 첫 글자와 말줄임표 + 간격 + [⋮ 옵션] [— 숨기기] 단추 전체. 한국어 99, 영어 96~98(논리 픽셀).
  내용은 이보다 좁게 배치되고 각 부분이 스스로 줄어든다: 레이블은 말줄임표(JLabel UI), 속성 그리드는 열이 좁아지고 칸 글자가 말줄임표, 줄 바꿈 칸은 다시 줄 바꿈,
  트리는 가로 스크롤.
- **도구 막대(`ObjectExplorerPanel`, `RegisteredServersPanel`, `QueryLibraryPanel`):** `OverflowToolBar`(주 도구 막대와 같음)로 바꿨다.
  들어가지 않는 뒤쪽 단추는 오른쪽 끝 » 메뉴로 간다(최소 폭에서 잘려 누를 수 없게 되지 않도록). Properties 도구 막대(단추 둘, 68)는 머리말 최소 폭보다 좁아 그대로 둔다.
- **왜 이 최소값인가:** 머리말 단추(옵션, 숨기기)는 늘 누를 수 있어야 하고, 제목 첫 글자가 있어야 어느 도구 창인지 안다. 그 이상은 사용자가 끌어서 정한다.
  내용 기준 최소값(가장 긴 레이블, 도구 막대 전체)은 데이터·언어·글꼴에 따라 바뀌고 줄 바꿈 칸처럼 커지기만 할 수도 있어 쓰지 않는다.
- 문서 영역 쪽 최소 크기는 바꾸지 않았다. 메인 창 최소 폭도 함께 작아진다(Properties 긴 이름 기준 1209 → 642).

확인(2026-11-07, `-Pproduct-chromite-swt,product-chromite-swing`, JBR 25 번들 런타임, 150%, 개인 작업 공간 `D:\Tools\_scratch\toolwindow-min-width\ws`, PostgreSQL
`chr_toolwindow_min_width`의 긴 이름·주석 테이블). EDT 시험 에이전트가 분할선에 마우스 누르기·끌기·놓기 이벤트를 보내 끌었다. 캡처는 `D:\Tools\_scratch\toolwindow-min-width\run\shots\`.

| 경우 | 고치기 전 | 고친 뒤 |
|---|---|---|
| 긴 이름 테이블을 고르고 Properties 분할선을 오른쪽으로 끌기 | 385 → 591로 오히려 넓어지고(왼쪽 열이 밀림) 줄지 않음 | 99까지 줄고 제목 "속...", 이름·설명·그리드 칸이 말줄임표 |
| Properties를 585로 넓힌 뒤 다시 좁히기 | 585에서 멈춤 | 99 |
| Object Explorer·Registered Servers 열을 왼쪽으로 끌기 | 174에서 멈춤 | 98~99, 도구 막대 »에 연결 끊기·필터·새로 고침·모두 축소 |
| Query Library를 보인 오른쪽 열 | 최소 200(도구 막대) | 98~99, 도구 막대는 실행 + », 검색 칸 말줄임 |
| 한국어·영어(`-nl en`) × 라이트·다크 | | 위와 같음, 겹침·잘린 단추 없음 |

### Query Library 자리 (2026-11-07)

사용자 결정(2026-11-07): Query Library는 기본으로 숨기고, 보이면 오른쪽 위, Properties 위(같은 오른쪽 열을 위아래로 나눔)에 놓는다.

- **이미 맞던 것:** 기본 배치에서 숨김(`addToolWindow(..., false, ...)`), 처음 보이면 `Place.next(properties, NORTH, 0.5)`로 Properties 위.
  View 메뉴, 주 도구 막대 토글, Ctrl+Alt+T, Query Library의 "창 보이기"(Save to Query Library… 뒤)는 모두 같은 `ToolWindow.setShown(true)` → `ToolWindowDocking.show`를 거친다.
- **틀리던 경우(고치기 전 확인):** 한쪽이 숨어 있을 때 다른 쪽을 보이면 두 도구 창이 따로 열을 만들었다.
  - Properties를 숨기고 Query Library를 보인 뒤 Properties를 보이면 `[문서 | Query Library | Properties]` 두 열.
  - 그 상태에서 Query Library를 숨겼다가(마지막 자리 = 오른쪽 가장자리) Properties가 보일 때 다시 보이면(메뉴·도구 막대·Ctrl+Alt+T 모두) `[문서 | Properties | Query Library]`.
  - 분할 창 시절 설정(`properties.visible=false`, `queryLibrary.visible=true`)을 옮긴 첫 실행 뒤 Properties를 보일 때도 두 열(위 "확인"의 "이전 설정 옮기기" 줄).
  - 왼쪽 열도 같았다: Registered Servers와 Object Explorer를 차례로 숨기고 차례로 보이면 Object Explorer가 문서 영역 옆에 따로 열.
- **원인:** 숨긴 도구 창은 마지막 자리(`<id>.restorePlace`)로 돌아간다. 그 자리는 숨길 때 계산되므로, 이웃이 숨어 있던 때면 "오른쪽 가장자리"나 "문서 영역 왼쪽"처럼
  이웃을 모르는 자리다. 또 이웃 관계가 한쪽에만 있었다(Query Library → Properties 위, Registered Servers → Object Explorer 아래). Properties와 Object Explorer의 기본 자리는 가장자리뿐이었다.
- **고친 것:**
  - `MainFrame`: Properties의 기본 자리 맨 앞에 `next(queryLibrary, SOUTH, 0.5)`, Object Explorer에 `next(registeredServers, NORTH, 0.6)`.
    기본 배치는 그대로다(기본 배치는 앞에 놓인 이웃만 쓰고, Query Library는 숨김이며 Object Explorer가 Registered Servers보다 먼저 놓인다).
  - `ToolWindowDocking.show`: 마지막 자리가 문서 영역 옆 열(창 가장자리, 또는 문서 영역의 왼쪽·오른쪽)이고 기본 이웃이 지금 문서 영역의 같은 쪽에 있으면,
    마지막 자리보다 이웃 자리를 먼저 쓴다(`getNeighbourColumnPlace`). 쪽은 배치 트리에서 도구 창과 문서 영역을 함께 가진 첫 분할로 정한다(`getSideOfDocuments`,
    창이 보이기 전에도 같음). 마지막 자리가 다른 도구 창 옆(탭, 위아래)이면 그대로 쓴다.
- **저장된 배치는 옮기지 않는다(결정):** 배치 파일(`chromite-window-layout-1.xml`)은 사용자가 끌어서 만든 배치이고 형식도 같다. Query Library가 그 안에서 다른 곳에 보이면 사용자가
  옮긴 것이므로 그대로 복원한다. 숨김 상태로 저장된 경우는 다음에 보일 때 위 규칙으로 Properties 위에 간다. 이전 형식(분할 창 설정)은 보이기 여부만 옮기는 지금 방식 그대로다.
- **알려진 차이:** 오른쪽 가장자리에 `[Query Library | Properties]`를 일부러 두 열로 둔 뒤 Properties를 숨겼다가 보이면 Query Library 아래로 들어간다
  (마지막 자리 "오른쪽 가장자리"로는 두 경우를 구별할 수 없다). 다시 끌어 놓으면 된다.

확인(2026-11-07, 같은 빌드·환경, 새 작업 공간 `ws-q3`, `ws-old-after`). 캡처는 `D:\Tools\_scratch\toolwindow-min-width\run\shots\before-S*`, `after-S*`.

| 경우 | 고치기 전 | 고친 뒤 |
|---|---|---|
| S1 새 작업 공간, View > Query Library | `V(queryLibrary / properties)` (이미 맞음) | 같음 |
| S2 Properties 숨김 → Query Library 보임 → Properties 보임 | `H(... | queryLibrary) | properties` 두 열 | `V(queryLibrary / properties)` |
| S3 Query Library 마지막 자리 = 오른쪽 가장자리, 도구 막대 토글로 보임 | `... | properties) | queryLibrary` 두 열 | `V(queryLibrary / properties)` |
| S5 Ctrl+Alt+T 숨김·보임 | S3과 같음 | `V(queryLibrary / properties)` |
| S4 Registered Servers·Object Explorer 숨김 → 차례로 보임 | `registeredServers | H(objectExplorer | documents ...)` | `V(objectExplorer / registeredServers)` |
| S6 분할 창 설정을 옮긴 작업 공간 → Properties 보임 | `H(... | queryLibrary) | properties` | `V(queryLibrary / properties)` |
| S7 닫고 다시 시작 | | 배치 파일에서 `V(queryLibrary / properties)` 그대로 |
| 다크 테마 | | 카드·머리말 색 정상 |

## 빈 문서 영역 (2026-11-06)

사용자 결정(2026-11-06): "Welcome"/"시작" 문서 탭을 없앤다. 문서가 하나도 열려 있지 않으면 가운데 문서 영역 자체가 시작 내용을 바탕으로 보인다(IDE의 빈 편집기 화면).
첫 문서가 열리면 사라지고 마지막 문서를 닫으면 돌아온다. 탭이 아니므로 닫을 것도, Window 메뉴의 문서 목록에 나올 것도 없다.

| 파일 | 바뀐 것 |
|---|---|
| `WelcomePanel`(새 파일) | 가로 로고(JSVG 벡터), 안내 두 줄, 단축키 세 줄(New Query Ctrl+N, Open File Ctrl+O, Registered Servers Ctrl+Alt+G) |
| `MainFrame` | 문서 카드 안을 `CardLayout`(문서 탭 / 빈 화면)으로, 문서 탭이 추가·삭제될 때 전환(`updateDocumentCard`). Welcome 탭 만들기 코드 삭제 |
| `icons/brand/lockup-light.svg`, `lockup-dark.svg`(새 파일), `tools/branding/gen_app_icons.py` | PR #41의 가로 잠금 로고(심볼 + "CHROMITE" + "DATABASE WORKBENCH", 원래 로고 글자)를 생성기가 Swing 번들에도 쓴다(여백 0, 바탕 없음). 손으로 복사하지 않는다 |
| `CoreMessages`(`_ko`) | 쓰지 않게 된 `main_welcome`(탭 제목 "Welcome"/"시작") 삭제. 안내 문구 `main_welcome_hint`, `main_welcome_next`는 그대로 씀 |
| `ChromiteIcons` | 주석만: 로고 아이콘(`getLogoIcon`)은 이제 About만 씀 |

- **로고:** 라이트 테마는 남색 글자 판(`lockup-light.svg`), 다크 테마는 흰 글자·회색 부제 판(`lockup-dark.svg`). 그릴 때마다 현재 테마를 보고 JSVG로 화면 배율에 맞게 벡터로 그린다(150%에서 선명).
  바탕 상자 없이 편집기 바탕(`Chromite.editorBackground`) 위다. 테마를 바꾸면 바로 바뀐다.
- **크기:** 가운데 정렬, 최대 너비 420(논리 픽셀), 문서 영역이 좁으면 양쪽 여백 32를 남기고 줄인다(최소 80).
- **글자:** 안내 두 줄(영어 "Expand a connection in Object Explorer…", "Right-click a connection and choose New Query (Ctrl+N)…", 한국어 그대로)과 단축키 이름은 `text.secondary`(`Chromite.secondaryForeground`), 작은 글꼴(FlatLaf `small`).
  단축키 줄은 메뉴와 같은 `Action`의 이름·단축키에서 만들고, 이름 끝의 "…"는 뺀다.
- **결과 카드:** 문서가 없으면 숨기므로 빈 화면이 문서 영역을 다 쓴다(2026-11-07 "Results 자동 숨김". 그 전에는 "결과가 없습니다" 자리 표시가 아래에 있었다).
- 이전 시작 탭의 정사각 아이콘 로고(128)와 큰 제품명 글자는 없앴다(가로 로고에 제품명이 있음).
- **전환 시점(2026-11-07 수정):** 빈 화면 ↔ 문서 탭 전환(`updateDocumentCard`)은 `EditorTabbedPane`의 `insertTab`·`removeTabAt`이 **끝난 뒤** 부른다.
  처음에는 문서 탭의 `ContainerListener`에서 불렀는데, 컨테이너 이벤트는 탭 추가 도중에 오고 `BasicTabbedPaneUI`는 탭마다의 제목 뷰(`htmlViews`)를
  자기 컨테이너 리스너에서 맞춘다. `updateUI`(테마 전환)가 그 리스너를 우리 리스너 뒤에 다시 붙이므로, 테마를 바꾼 뒤 탭을 모두 닫고 새로 열면
  `CardLayout.show`의 즉시 레이아웃이 UI보다 먼저 돌아 `ArrayIndexOutOfBoundsException`(`BasicTabbedPaneUI.getTextViewForTab`)이 났고,
  `insertTab`이 중간에 끊겨 탭 영역이 계속 깨졌다(선택 -1, 그릴 때마다 같은 예외). `invokeLater`로 미루지 않은 것은 전환이 늦으면 같은 이벤트 안의
  코드가 아직 숨은 탭 창을 볼 수 있기 때문이다(포커스 요청, `isShowing`). 다른 탭 창(Results 탭, 도구 창 탭, 대화상자 탭)에는 컨테이너 리스너가 없고, 선택 변경 리스너는 `updateUI` 중에 불리지 않는다.

### 확인 (2026-11-06)

"도구 창 도킹 — 확인"과 같은 실행에서 캡처했다(`D:\Tools\_scratch\docking\welcome-*.png`): 한국어(OS 기본)·영어(`-nl en`) × 라이트·다크 × 1920·1280.
문서 탭이 없을 때 빈 화면, New Query로 탭이 생기면 문서 탭, 탭을 모두 닫으면 다시 빈 화면(카드 전환을 에이전트로 확인).

## Results 자동 숨김 (2026-11-07)

사용자 결정(2026-11-06, "DataGrip도 이렇게 동작한다"): 아래 Results 카드(Results / Messages / Execution plan 탭과 쿼리 상태 줄)는 **활성 문서 탭이 쿼리 창일 때만** 보인다.

| 활성 문서 | Results 카드 | Ctrl+R, View > Results |
|---|---|---|
| 없음(빈 문서 영역) | 숨김. 빈 화면(로고)이 문서 영역을 다 쓴다 | 꺼짐, 도구 설명 "Results (Ctrl+R): open or activate a query window first" / "결과(Ctrl+R): 먼저 쿼리 창을 열거나 활성화하십시오" |
| 쿼리 창(새 쿼리, 파일, 스크립트·개체 정의 편집 창) | 그 쿼리 창에서 숨기지 않았으면 보임 | 켜짐. 그 쿼리 창의 Results를 숨기고 보임 |
| 다른 문서(테이블 디자이너, ER 다이어그램, Activity Monitor·Find Objects 같은 도구 문서, DDL 탭) | 숨김 | 꺼짐 |

- **쿼리 창마다 기억:** Ctrl+R, View 메뉴, 탭 줄의 — 단추로 숨긴 것은 그 쿼리 창에만 남는다. 쿼리 A에서 숨기고 쿼리 B로 가면 B는 보이고, A로 돌아오면 다시 숨김이다.
  실행(F5)·예상 실행 계획(Ctrl+L)·실제 계획과 함께 실행은 지금처럼 그 쿼리 창의 Results를 다시 보인다(`showResults`/`showPlan`이 사용자 선택을 "보임"으로 바꿈).
- **크기:** Results 높이는 하나(전역)이고, 숨길 때 기억해 다시 보일 때 쓴다. 문서를 바꿔 숨겼다가 돌아와도, 다시 시작해도(`results.size`) 같은 높이다.
  사용자가 높이를 바꾸지 않았으면 기본 높이를 쓴다. 설정 저장소는 기본값과 같은 값을 저장하지 않으므로(`SplitHost.load`), 기본 높이를 바꾸면 높이를 바꾼 적 없는 사용자에게도 새 기본값이 적용된다.
- **Window > Reset Window Layout:** 기본 도킹 배치, Results 기본 높이(280. 2026-11-07 사용자 결정으로 220에서 바꿈, `MainFrame.DEFAULT_RESULTS_HEIGHT`), 열린 모든 쿼리 창의 "숨김"을 지운다(기본 = 보임). 쿼리 창이 활성이 아니면 숨긴 채로 둔다.
- **쿼리 상태 줄:** 지금처럼 Results가 보이면 Results 카드 아래, 숨겨져 있으면 문서 카드 아래(편집기 아래)다. 쿼리 창이 아니면 상태 줄이 없다.
  (2026-11-08부터 활성 쿼리 창의 칸은 Results 표시와 상관없이 메인 창 상태 표시줄에 있다, "상태 표시줄의 쿼리 창 칸".)
  활성 문서가 바뀔 때 먼저 Results를 보이거나 숨기고(`SplitHost.update`) 그다음 상태 줄을 끼우므로 두 위치가 항상 맞다.

### 설계

- **근본 원인:** 지금까지 Results 표시 여부는 `ToolWindow.SplitHost`가 가진 전역 상태 하나(카드의 `isVisible`, 설정 `results.visible`)였다. 그래서 문서를 바꿔도 그대로였고
  빈 문서 영역·다른 문서에서도 자리 표시 내용이 있는 Results 카드가 공간을 차지했다. 표시 여부를 문서별 규칙으로 바꾸기 위해 상태의 주인을 바꿨다.
- **`ToolWindow.SplitHost.Context`(새 인터페이스):** "지금 보일지"(`isShown`)와 "사용자가 고른 것 기억"(`setShown`). `SplitHost`는 자기 상태를 갖지 않고
  `setShown`(View 메뉴, — 단추, 명령)을 Context에 넘긴 뒤 `update()`로 Context가 말하는 대로 카드를 보이거나 숨긴다. 크기 기억·분할선 0·테마 전환을 위해 카드를 빼지 않고 감추는 방식은 그대로다.
- **`MainFrame`의 Context:** 활성 문서가 `SqlEditorPanel`이고 그 편집기에 숨김 표시(클라이언트 속성 `chromite.resultsHidden`)가 없으면 보임. 사용자 선택은 활성 쿼리 창의 클라이언트 속성에 쓴다.
  표시를 편집기 객체에 두어 편집기를 닫으면 같이 사라진다(따로 지울 목록이 없고, 동시 작업 중인 `SqlEditorPanel`을 고치지 않는다).
- **적용 시점:** 문서 탭이 바뀔 때(`activeEditorChanged`, 마지막 탭을 닫을 때 포함) `resultsSplit.update()`. 활성 문서가 정하는 값이라 저장하지 않는다.
- **토글 끄기:** `updateQueryActions`가 Results 토글 `Action`을 쿼리 창이 없으면 끄고 도구 설명을 넣는다(Activity Monitor 단추와 같은 방식). 꺼진 메뉴 항목의 단축키(Ctrl+R)는 동작하지 않는다.
- **도킹 배치와 충돌 없음:** Results는 도킹 대상이 아니라 배치 파일에 없다. 배치 파일·`<id>.restorePlace`는 그대로다.

### 바뀐 파일

| 파일 | 바뀐 것 |
|---|---|
| `ToolWindow` | `SplitHost.Context` 추가, `SplitHost`가 표시 여부를 Context에서 읽음(`update`), `reset`은 크기만, `load`/`save`는 크기만 |
| `MainFrame` | Results Context(활성 쿼리 창마다 숨김 기억), 문서 탭이 바뀔 때 `update`, 쿼리 창이 없으면 Results 토글 끔, Reset Window Layout이 쿼리 창들의 숨김을 지움, 클래스 Javadoc |
| `internal/CoreMessages`(`_ko`) | 꺼진 Results 토글의 도구 설명 `main_results_unavailable_tip` 추가 |

지운 코드(규칙 4): 설정 `results.visible`(`PREF_VISIBLE_SUFFIX`, 읽기·쓰기), 쿼리 창이 없을 때 Results / Messages / Execution plan 탭의 자리 표시
(`resultsPlaceholder`·`messagesPlaceholder`·`planPlaceholder`, `createPlaceholder`, 문구 `main_no_results`·`main_no_messages`·`main_no_plan`). 쿼리 창이 없으면 탭은 내용 없이(`null`) 숨겨진다.
예전 작업 공간에 남은 `results.visible` 값은 읽지 않는다(지우지는 않음).

### 확인 (2026-11-07)

빌드 `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Windows 11, 150%, JBR 25(`-vm ...\server\jvm.dll`), 개인 작업 공간 `D:\Tools\_scratch\results-autohide\ws-ko`, `ws-en`(`-nl en`).
프로세스 안 EDT 시험 에이전트(OS 입력 없음, 리플렉션으로 상태 읽기)로 조작했다. SQL Server 2022 시험 DB `chr_results_autohide`(Customer, Orders). 캡처는 `D:\Tools\_scratch\results-autohide\shots\`.

| 경우 | 결과 |
|---|---|
| 빈 문서 영역 | 카드 숨김, 분할선 0, 빈 화면 높이 = 문서 영역 높이(686). Results 토글·View 메뉴 항목 꺼짐과 도구 설명, Ctrl+R 눌러도 그대로 |
| 쿼리 창 열고 F5 | 보임(높이 220), 상태 줄은 Results 아래 |
| 테이블 디자이너, ER 다이어그램, DDL 탭, Activity Monitor | 숨김, 토글 꺼짐, 상태 줄 없음. 쿼리 창으로 돌아오면 보임(같은 높이) |
| 쿼리 A에서 Ctrl+R → 새 쿼리 B → A → B | A 숨김(상태 줄은 편집기 아래) → B 보임 → A 숨김 → B 보임 |
| B의 — 단추 → View > Results | 숨김 → 보임 |
| 숨긴 A에서 F5 / 숨긴 A에서 Ctrl+L | 다시 보임(Results·Messages 탭) / 다시 보임(Execution plan 탭) |
| 모두 닫기 | 숨김, 빈 화면이 문서 영역을 다 씀 |
| 높이 기억 | 320으로 바꾸고 디자이너로 갔다가 돌아옴 → 320. 300으로 바꾸고 끝냄 → 다시 시작 뒤 쿼리 창 높이 300(`results.size=300`), `results.visible` 저장 안 함 |
| Reset Window Layout | 숨긴 쿼리 창이 활성일 때 → 보임, 높이 220. 디자이너가 활성일 때 → 숨김 유지, 쿼리 창으로 가면 보임 |
| 다시 시작 | 빈 문서 영역에서 숨김으로 시작, 다크 테마 유지 |

- 한국어(OS 기본)와 영어(`-nl en`), 라이트·다크로 캡처했다(`ko-light-*`, `ko-dark-*`, `en-light-*`, `en-dark-*`).
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: Results 숨기기·보이기(Ctrl+R, View 메뉴, — 단추), 실행·실행 계획 뒤 Results 표시, 쿼리 상태 줄 위치,
  Reset Window Layout, 창을 닫을 때 Results 높이 저장, 테마 전환 뒤 숨겨져 있던 결과 컴포넌트 모양.

## 오류 문구 (2026-11-07)

사용자 보고(2026-11-07, 테이블 복사 화면): 오류 문구가 `com.seizia.chromite.DBRuntimeException: Error while finishing result set fetching into '…':`로 시작했다.
화면 문구에 "dbeaver"와 Java 클래스 이름이 나오면 안 된다(브랜딩 규칙). 같은 날 보고된 백업·복원 메시지 끝의
`Service 'com.seizia.chromite.runtime.ui.UIServiceSystemAgent' not found`(followups #40)도 같은 묶음으로 고쳤다.

### 근본 원인

- **예외 → 문구를 곳곳에서 따로 만들었다.** Swing 번들의 30여 곳이 `CommonUtils.getAllExceptionMessages(e)`, `e.getMessage()`,
  `CommonUtils.getRootCause(e).getMessage()`로 문구를 만들었다. `getAllExceptionMessages`는 각 예외의 `getMessage()`를 이어 붙일 뿐이라
  `Throwable(Throwable cause)` 생성자로 만든 예외(메시지 = 원인의 `toString()` = `패키지.클래스: 메시지`)가 사슬에 있으면 클래스 이름이 그대로 나온다.
  모델 계층의 `GeneralUtils.getExceptionMessage`(작업 상태 `IStatus`의 메시지)도 클래스가 `toString()`을 선언하면 `toString()`을 쓴다.
  메시지가 없는 예외는 빈 문구가 되었고, JVM이 만든 NPE 문구(`Cannot invoke "org.jkiss…"`)는 패키지 이름을 담는다.
- **`UIServiceSystemAgent`가 Swing 제품에 없었다.** 네이티브 도구 처리기(`AbstractNativeToolHandler.notifyToolFinish`, `com.seizia.chromite.tasks.native`
  `AbstractNativeToolHandler.java:270`)가 작업이 끝나면 `DBWorkbench.getService(UIServiceSystemAgent.class)`를 부르고, 서비스가 없으면
  `DBWorkbench.getService`(`com.seizia.chromite.model` `DBWorkbench.java:86-92`)가 `log.debug("Service '…' not found")`를 남긴다. 작업 스레드의 로그는
  작업 출력(`Log.setLogWriter`)으로 가므로 메시지 페이지에 그대로 찍혔다. SWT 제품은 `com.seizia.chromite.core/plugin.xml:1112`의
  `com.seizia.chromite.service` 확장으로 `UIServiceSystemAgentImpl`(트레이 알림)을 등록하는데, Swing 제품에는 이 번들이 없다.

### 구조

| 클래스 | 역할 |
|---|---|
| `ErrorText`(새) | 예외·`IStatus` → 화면 문구의 유일한 변환기. `describe`(여러 줄), `describeMessages`(SQL 머리말이 따로 있는 곳), `describeLine`(상태 줄·셀·도구 설명), `summary`(가장 안쪽 메시지, 표 셀), `describe(IStatus)`, `clean`(모델이 넘긴 문자열), `stackTrace`, `isStackTraceLine` |
| `ErrorDialog`(새) | 오류 대화상자 본문: 문구 + 접힌 **자세히**(스택 추적) + **복사**. `JOptionPane`의 메시지 자리에 들어가므로 단추(확인, 중지/다시 시도/건너뛰기)는 호출하는 쪽이 정한다. 인라인 오류 영역용 `DetailsButton`("자세히…") |
| `SwingSystemAgent`(새) | `UIServiceSystemAgent`의 Swing 구현. `plugin.xml`의 `com.seizia.chromite.service` 확장으로 등록(SWT와 같은 방식). 오래 걸린 작업이 끝나면 알림 창(`NotificationToast`)으로 알린다 |
| `internal/ErrorMessages`(새, `_ko`) | 위 문구. `CoreMessages`는 같은 날 PR #55가 고쳐 병합 충돌을 피하려고 따로 두었다 |

### 문구 규칙 (`ErrorText`)

1. 원인 사슬을 따라(같은 예외는 한 번, `SQLException.getNextException()` 사슬 포함: 배치 오류의 실제 원인이 거기 있다) 각 예외의 `getLocalizedMessage()`를 한 줄씩 모은다.
2. 메시지 앞의 예외 클래스 이름(`java.sql.SQLException: `, `Caused by: …`)을 떼고, 여러 겹이면 모두 뗀다. 클래스 이름뿐인 메시지는 버린다.
   "클래스 이름"은 소문자로 시작하는 패키지 + 대문자로 시작하는 이름이고, 그 이름이 사슬에 있는 예외 클래스이거나 `Exception`/`Error`/`Throwable`/`Warning`/`Fault`로 끝날 때만이다
   (`dbo.Table: …` 같은 DB 개체 이름은 건드리지 않는다).
3. 문장 안의 제품·플랫폼 패키지 이름(`org.jkiss.`, `com.seizia.`, `org.eclipse.`, `java.`, `javax.`, `jdk.`, `sun.`, `/` 표기 포함)은 단순 이름만 남긴다.
   다른 점 이름(`sales.dbo.Orders`, 드라이버 클래스 `org.postgresql.Driver`)은 사용자에게 필요한 정보라 그대로 둔다.
4. 앞 줄에 이미 들어 있는 메시지는 버리고, 바로 앞 줄을 품은 더 긴 메시지는 앞 줄을 대신한다(DBeaver 래퍼가 원인 메시지를 반복하는 경우).
5. 메시지가 인자뿐인 예외는 종류 이름을 붙인다: 알 수 없는 호스트, 파일을 찾을 수 없음(`NoSuchFileException`), 파일이 이미 있음, 액세스 거부, 폴더가 비어 있지 않음, 폴더가 아님, 클래스를 찾을 수 없음.
6. JVM이 코드를 설명하는 예외(`NullPointerException`, `ClassCastException`, `IndexOutOfBoundsException`, `ArrayStoreException`)의 메시지는 보이지 않는다.
7. 보여 줄 메시지가 하나도 없으면 "예기치 않은 오류가 발생했습니다(메모리 부족)"처럼 일반 문구 + 가장 안쪽 종류(메모리 부족, 스택 오버플로, 취소됨, 시간 초과, 입출력 오류, 내부 오류)만 보인다.
8. DB 오류 번호·SQLSTATE: `DBDatabaseException`이 메시지에 이미 넣는다(`SQL 오류 [208] [S0002]: …`). 문구에 번호나 상태가 없을 때만 쿼리 메시지 머리말과 같은 꼴
   (`메시지 208, 상태 S0002`, `EditorMessages.editor_sql_error_*`와 같은 용어)로 마지막 줄에 붙인다. 쿼리 메시지 탭은 머리말에 이미 있으므로 붙이지 않는다(`describeMessages`).
9. `IStatus`: 상태 메시지(정리한 것)와 상태의 예외 문구를 같은 중복 규칙으로 모은다. 대화상자의 자세히에는 상태 트리의 첫 예외 스택 추적을 넣는다.

**설계 결정 — 스택 추적은 접힌 자세히에만:** 스택 추적에는 패키지 이름(`com.seizia.chromite…`)이 그대로 있다. 버그 보고에 필요하므로 지우지 않고,
처음 보이는 문구에는 넣지 않으며, 오류 대화상자의 접힌 "자세히"(펼치면 고정폭 글꼴, 14줄)와 "복사"(문구 + 스택 추적을 클립보드로)로만 볼 수 있게 했다.
인라인 오류 영역(테이블 복사·데이터 전송의 진행 페이지, 백업·복원의 메시지 페이지)은 실패하면 "자세히…" 단추가 나타나 같은 대화상자를 연다.
쿼리 메시지 탭, 상태 줄, 표 셀, 도구 설명에는 자세히를 두지 않았다(서버 오류는 스택 추적이 쓸모없고, 공간이 없다). 스택 추적은 지금처럼 디버그 로그에 남는다.

### 적용한 곳

| 위치 | 전 | 후 |
|---|---|---|
| `SwingPlatformUI.showError(…, Throwable)`, `showError(…, IStatus)` | `getAllExceptionMessages`, 상태 메시지 그대로 | `ErrorText.describe` + `ErrorDialog`(자세히·복사) |
| `SwingPlatformUI.showErrorStopRetryIgnore`(데이터 전송 중 "실행 오류") | `getAllExceptionMessages` | 같은 규칙 + 자세히·복사, 단추는 그대로 |
| `SwingPlatformUI.showError(title, message)`, `showMessageBox(…, true)`, 알림 창 | 모델 문자열 그대로 | `ErrorText.clean` |
| `DataTransferWizard` 진행 표 메시지 열 / 오류 영역 | `getRootCause().getMessage()` / `getAllExceptionMessages` | `summary` / `describe` + "자세히…" |
| `DatabaseTaskDialog`(백업·복원) 메시지 페이지·스크립트·준비 오류 | `getAllExceptionMessages`, `JOptionPane` | `describe`, `ErrorDialog`, 실패 뒤 "자세히…" |
| `SqlServerBackupDialog`·`SqlServerRestoreDialog` | `SQLServerBackupRestore.getErrorText`(SQL 아닌 오류는 가장 안쪽 메시지만) | SQL 오류는 서버 메시지 전부(그대로), 그 밖은 `describe` |
| `NativeBackupRestoreDialog` 메시지 페이지 | `\tat`, `\t...` 줄만 뺌 | 스택 추적 머리줄(`…Exception: …`, `Caused by:`, `Suppressed:`)도 빼고 남은 줄은 `clean` |
| `SqlEditorPanel` 메시지 탭(쿼리 오류 머리말 아래, 준비 오류, 실제 계획, 작업 상태 오류), 쿼리 기록 | `getAllExceptionMessages`, `status.getMessage()` | `describeMessages` / `describe` / `describe(IStatus)` |
| `SqlPlanExplainer`, `ActualPlanReceiver`(실행 계획 탭) | `getAllExceptionMessages` | `describe` |
| `ResultSetGrid`(저장 오류, 잘못된 값), `ResultSetGridReceiver`(셀 읽기 오류), `ResultSetEditModel`(읽기 전용 이유), `ResultSetViewerPanel`(다시 읽기 오류), `ResultSetValuePanel`(값 읽기, JSON·XML 오류) | `getMessage()`, `getAllExceptionMessages` | `describeLine` / `describe` |
| `LockManagerPanel`, `SessionManagerPanel`(관리 도구 상태 줄·도구 설명), `ObjectSearchPanel`(개체 찾기 문제 목록) | `getMessage()` | `describeLine` |
| `QueryScheduler`(예약 쿼리 실행 기록·알림), `QueryParameterDialog`(조회 값 오류), `QueryParameterBinder`·`QueryLibraryParameter`(매개 변수 값 오류) | `getAllExceptionMessages`, `getMessage()` | `describe` / `describeLine` |
| `ConnectionSettingsPanel`(URL·드라이버 속성 오류), `RegisteredServerCommands`(그룹 이름 검사), `PropertiesPanel`, `DatabaseCopyPages`·`DataImportWizard`(DDL 미리 보기, 파일 미리 보기), `ChromiteSwingApplication`(시작 오류) | `getMessage()`, `getAllExceptionMessages` | `describe` / `describeLine` |
| `ProgressDialog.runTask` | 감싼 오류 `"Internal error"`(영어) | `ErrorMessages.error_unexpected`(지역화) |
| `SwingConnectionService`(연결 실패 대화상자) | 제목 = 모델 Job 이름 `Connect to 'X'`(영어) | 제목 "연결", 문구 "'X'에 연결하지 못했습니다." |
| `ConnectionCommands.disconnect` | 제목 = `Disconnect from 'X'`(영어) | 제목 "연결 끊기", 문구 "'X'의 연결을 끊지 못했습니다." |

그 밖의 오류 대화상자(테이블 디자이너, 개체 탐색기, ER 다이어그램, 쿼리 라이브러리, 연결 대화상자, 보안 편집기, 가져오기·내보내기 등)는 모두 `DBWorkbench.getPlatformUI().showError`를 거치므로 위 첫 줄로 함께 바뀐다.

### 오래 걸린 작업 알림 (`SwingSystemAgent`)

- 등록: Swing 번들 `plugin.xml`의 `com.seizia.chromite.service` 확장(`UIServiceSystemAgent` → `SwingSystemAgent`). SWT의 `com.seizia.chromite.core/plugin.xml:1112`와 같은 방식이고,
  `ServiceRegistry`가 확장 지점에서 읽어 `DBWorkbench.getService`가 찾는다(`SwingConnectionService`, `SwingDriversService`와 같음).
- 설정 키는 SWT와 같다(`agent.long.operation.notify`, `agent.long.operation.timeout`, `ChromitePreferences`). Swing 제품에는 기본값을 넣는 `DesktopPreferencesInitializer`가 없어
  값이 없으면 기본값(알림 켬, 30초)을 쓴다. SWT는 트레이가 있는 Windows만 기본으로 켜지만 Swing 알림 창은 모든 OS에 있어 켬으로 했다.
- 알림: 제목 "오래 걸린 작업 완료", 내용은 모델이 넘긴 문자열(`<작업 유형> - <작업 이름> has finished`, 모델 영어 문구)이다.

### 남긴 것

- 모델 계층의 영어 문구(오류 메시지 본문, 작업 이름, 진행 단계 이름, 위 알림 내용)는 번역하지 않았다. 사용자 보고의 `'chr_type_mapping_from_ora.null'`은
  `DBDDataReceiver`(`com.seizia.chromite.model`, `DBDDataReceiver.java:78`)가 대상 소비자의 `toString()`을 문구에 넣은 것으로, 모델 코드라 고치지 않았다.
- 쿼리 메시지 탭의 경고(`SQLWarning`, PRINT·NOTICE)는 서버 문구라 그대로 둔다(`SqlEditorPanel.reportQueryResult`).
- 이 변경 전에 저장된 쿼리 기록·예약 쿼리 기록의 오류 문구는 저장된 그대로 보인다.
- `ProgressDialog`의 "Canceling…"은 지역화되지 않은 영어 문구다(오류 문구 범위 밖, 보고만).
- 격리 컨텍스트 이름 등 화면에 나오지 않는 내부 이름은 그대로 둔다. 로그 파일 이름(`DBConstants.DEBUG_LOG_FILE_NAME`)은 내부 이름 변경에서 `chromite-debug.log`가 됐다([internal-rename.md](internal-rename.md)).

### 확인 (2026-11-07)

빌드 `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Windows 11, JBR 25(`-vm ...\server\jvm.dll`), 개인 작업 공간 `D:\Tools\_scratch\error-text\ws`,
한국어(OS 기본)와 `-nl en`. 프로세스 안 EDT 시험 에이전트(OS 입력 없음)로 조작했다. 시험 DB: PostgreSQL 17·SQL Server 2022의 `chr_error_text`.
캡처는 `D:\Tools\_scratch\error-text\shots\`(`ko-light-*`, `ko-dark-*`, `en-light-*`).

| 경우 | 보이는 문구(클래스·패키지 이름 없음) | 자세히 |
|---|---|---|
| a. 테이블 복사 PostgreSQL `copy_src` → SQL Server 기존 `copy_src`(`varchar(3)`, 삽입 실패) | "실행 오류" 대화상자: `SQL 오류 [2628] [S0001]: String or binary data would be truncated …`. 진행 페이지 오류 영역: `Error while finishing result set fetching into '…'` / `Can't insert row` / `SQL 오류 [2628] …`(전에는 맨 앞에 `com.seizia.chromite.DBRuntimeException: `), 표 메시지 열은 가장 안쪽 메시지 | 실행 오류 대화상자와 오류 영역의 "자세히…" 모두 스택 추적(`DBDatabaseException: com.seizia.chromite.DBRuntimeException: …`이 원인이었음을 보여 줌) |
| b. `SELECT * FROM no_such_table` | PostgreSQL `메시지 0, 상태 42P01, 줄 1, 열 15` / `SQL 오류 [42P01]: ERROR: relation "no_such_table" does not exist`, SQL Server `메시지 208, 상태 S0002, 줄 1` / `SQL 오류 [208] [S0002]: Invalid object name …` | (메시지 탭, 해당 없음) |
| c. 잘못된 포트(55439) | 제목 "연결", `'ET Wrong Port'에 연결하지 못했습니다.` / 드라이버 메시지(전에는 제목 `Connect to 'ET Wrong Port'`) | 펼침, 고정폭 스택 추적, 복사 |
| d. pg_dump 백업 | 메시지 페이지 끝이 `Task … finished …` / `데이터베이스 'chr_error_text'을(를) 백업했습니다.`, `UIServiceSystemAgent` 줄 없음(작업 공간 디버그 로그에도 0건). 잘못된 파일 복원(pg_restore 실패) | 실패 뒤 "자세히…" 단추 → 스택 추적 |
| e. 테이블 디자이너 저장 실패(SQL Server `dsg.txt`를 int로) | `'dsg'을(를) 저장하지 못했습니다.` / `SQL 오류 [447] [S0001]: Expression type int is invalid for COLLATE clause.` | 펼침, 복사 → 클립보드에 문구 + 스택 추적 |

- 영어(`-nl en`)에서 a~d를 다시 확인했다(`Can't connect to 'ET Wrong Port'`, `Msg 208, State S0002, Line 1`, Details/Copy).
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 모든 오류 대화상자(`showError`, 실행 오류 중지·다시 시도), 데이터 전송·테이블 복사 진행 페이지,
  백업·복원 메시지 페이지, 쿼리 메시지 탭·쿼리 기록·예약 쿼리 기록의 오류 문구, 관리 도구 상태 줄, 결과 그리드 편집 오류, 오래 걸린 네이티브 작업 뒤 알림 창.
- 시험 중 본 범위 밖 문제(보고만): 테이블 디자이너가 문자열 열을 `int`로 바꿀 때 `COLLATE`를 남긴 `ALTER COLUMN`을 만든다(위 e의 서버 오류).
  도킹 탭 넘침 단추의 도구 설명 "Show Hidden Tabs"(ModernDocking 라이브러리 문구)가 번역되지 않았다.

## Object Explorer 후속 수정 (2026-11-07)

통합 중 모은 후속 과제 중 탐색기 항목 #31, #34, #35, #36을 고쳤다(번호는 2026-11-06 통합 후속 목록). #2·#25(보조 글자 기준선)는
이미 고쳐져 있어 건너뛰었다: Object Explorer(`ExplorerCellRenderer.paint`)와 Registered Servers(`RegisteredServersPanel.java:913`)가 모두
레이블의 실제 기준선(`getBaseline`)에 보조 글자를 그린다(연결 색 알약 커밋 `1099b00c3b`).
화면 확인은 Swing 제품을 개인 작업 공간으로 실행해(`-vm` JBR 25) EDT 시험 에이전트로 했고, 이전 동작은 같은 순서를 수정 전 빌드
(`mssql-functions-folder` 작업 트리 제품)에서 돌려 확인했다. 스크린숏은 `D:\Tools\_scratch\fixes-navigator\run\shots\`에 있다.
회귀 테스트는 실행하지 않았다(요청 없음).

### Object Explorer 탐색 불가 항목

- 증상: 프로시저·함수 아래에 매개 변수가 없었다(#34). MySQL/MariaDB "시스템 정보" 아래 세션·전역 상태, 세션·전역 변수, 엔진,
  문자 집합의 데이터 정렬, 사용자 권한, 플러그인 폴더가 펼쳐지지 않았다(#31). MySQL 프로시저의 "프로시저 매개 변수" 폴더도 펼쳐지지 않았다.
- 근본 원인: 이 항목들은 `plugin.xml`에서 `navigable="false"`다(예: `ext.mssql/plugin.xml:130,135`, `ext.mysql/plugin.xml:91,129-152`,
  `ext.postgresql/plugin.xml:260`). Object Explorer는 SWT 트리처럼 `DBNNode.hasChildren(true)`(→ `DBXTreeNode.hasChildren(context, true)`,
  `DBXTreeNode.java:197-213`)로 펼칠 수 있는지 정하고 `DBNUtils.getNodeChildrenFiltered(monitor, node, true)`(`DBNUtils.java:101-136`)로
  탐색 불가 항목을 뺐다. SWT도 트리에서는 똑같이 빼지만(`DatabaseNavigatorContentProvider.java:91,108`, `TreeLoadService.java:50`)
  그 목록을 객체 편집기의 탭(`ItemListControl.java:286`, `forTree=false`)에서 보여 준다. Swing에는 객체 편집기가 없어 볼 곳이 없었다.
  같은 원인으로 인덱스·키·외래 키의 열, PostgreSQL 역할의 구성원 폴더도 보이지 않았다.
- 수정(`ObjectExplorerPanel`): `hasTreeChildren`이 탐색 가능 조건만 뺀 `DBXTreeNode.hasChildren(node, false)`로 펼침을 정하고,
  `readChildren`이 `DBNNode.getChildren` 뒤 숨김 노드(`DBPHiddenObject`)만 빼고 `DBNUtils.filterNavigableChildren(…, false)`로 정렬한다.
  `DBNNode.hasChildren(false)`를 그대로 쓰지 않은 것은 구조체 형식 열·매개 변수의 동적 속성(`DBNDatabaseNode.hasDynamicStructChildren`)까지
  찾아 EDT에서 데이터 형식을 읽기 때문이다(이전에도 이 트리에 없던 항목).
- 매개 변수 뒤 보조 글자는 형식이다(열처럼 길이 포함: `DBSTypedObject`이면 그 형식, 아니면 `DBSParameter.getParameterType()`).
- SQL Server 스칼라 함수의 반환 값은 이름 없는 매개 변수(`sys.parameters.parameter_id` 0)라 탐색기 이름이 `toString`(클래스 이름)이었다.
  이름이 없고 종류가 `RETURN`이거나 순서가 0인 매개 변수는 트리와 속성 창 제목에 "Returns"/"반환 값"으로 보인다
  (`ObjectExplorerPanel.getNodeText`, 새 묶음 `internal/NavigatorMessages`). MySQL·generic 모델은 이런 매개 변수에 모델에서 "RETURN" 이름을 붙인다
  (`MySQLCatalog.java:1007`, `GenericProcedure.java:179`).
- SWT 화면은 바뀌지 않는다(Swing 번들만 고침).
- 확인(SQL Server 2022, MariaDB 11, PostgreSQL 17, 한국어·영어, 라이트·다크): 수정 전에는 프로시저·함수가 펼쳐지지 않고 MariaDB 시스템 정보 폴더들이 잎 노드였다
  (`before-ko-light-maria-system-info.png`). 수정 뒤 SQL Server `get_orders` 아래 `@customer nvarchar(100)`, `@min_amount decimal(10,2)`, `@total int`,
  `order_count` 아래 "반환 값 int"/"Returns int"와 `@customer`, MariaDB 프로시저의 "프로시저 매개 변수" 아래 `p_customer`, `p_total`(함수는 `RETURN` 포함),
  시스템 정보의 세션·전역 상태, 세션·전역 변수(수백 개), 엔진 8개, 문자 집합별 데이터 정렬이 보인다. 변수를 고르면 속성 창에 값이 나온다
  (`ko-light-maria-session-variable.png`: `autocommit` = ON). PostgreSQL 함수·프로시저의 "함수 매개 변수" 폴더 아래 `p_customer varchar`,
  `p_min numeric` 등이 보인다. 인덱스 노드는 이제 열을 펼친다.

### SQL Server 스키마 새로 고침 뒤 트리거

- 증상(#36): 스키마(또는 그 아래 폴더)를 새로 고치면 테이블의 "트리거" 폴더가 다시 연결할 때까지 비었다.
- 근본 원인: `SQLServerSchema.refreshObject`(수정 전 `SQLServerSchema.java:193-205`)가 테이블·인덱스·제약 조건·프로시저 캐시는 비우고 트리거 캐시는
  비우지 않았다. 테이블 캐시를 다시 읽으면 테이블 객체가 새로 생기는데, 남은 트리거는 이전 테이블 객체를 가리키고 캐시는 "다 읽음" 상태라 다시 읽지 않는다.
  `SQLServerTableBase.getTriggers`(`SQLServerTableBase.java:321-333`)는 `this == trigger.getTable()`로 거르므로 새 테이블에는 트리거가 하나도 없다.
  스키마 아래 "테이블 트리거" 가상 폴더(`plugin.xml:144`)는 이전 테이블 객체의 트리거를 보여 줬다.
- 수정: `refreshObject`에서 `triggerCache.clearCache()`도 부른다. MySQL(`MySQLCatalog.java:550`)과 SQL Server 데이터베이스
  (`SQLServerDatabase.java:289`)의 새로 고침은 이미 트리거 캐시를 비운다. 테이블 하나의 새로 고침(`SQLServerTableBase.refreshObject`)은
  같은 테이블 객체를 다시 채우고 그 테이블의 트리거만 지워(`clearChildrenOf`, 캐시를 "덜 읽음"으로 돌림) 원래 문제가 없었다.
- 모델 수정이라 SWT에도 같이 적용된다(upstream에도 있는 버그).
- 확인(SQL Server 2022, 트리거 2개인 `orders`): 수정 전 빌드에서 `dbo`를 F5로 새로 고친 뒤 `orders` > 트리거가 비었다
  (`before-ko-light-mssql-triggers-after-schema-refresh.png`). 수정 뒤에는 `trg_orders_delete`, `trg_orders_insert`가 그대로 보인다(한국어·영어).

### 새 객체의 부모 폴더

- 증상(#35): SQL Server "저장 프로시저"와 "함수" 폴더를 나눈 뒤(swing-l10n.md "SQL Server 함수 폴더"), 탐색기에 아직 없는 함수가 모델 추가 이벤트
  (`OBJECT_ADD`, 컨테이너 폴더 없이)로 들어오면 "저장 프로시저" 폴더 아래에 붙었다. Oracle·generic의 프로시저/함수 폴더도 같은 구조다.
- 근본 원인: `DBNModel.getParentNode`(수정 전 `DBNModel.java:418-450`)가 객체 클래스를 받는 첫 폴더를 부모로 골랐다. 두 폴더가 같은 클래스
  (`SQLServerProcedure`)를 받고 항목 속성(`proceduresOnly`/`functionsOnly`)으로 객체를 나누므로 클래스만으로는 폴더를 알 수 없다.
  `DBNModel.cacheNodeChildren`(수정 전 `DBNModel.java:385-415`, 객체로 노드를 찾을 때)도 같은 원인으로, 필터가 걸린 첫 폴더에 다른 폴더의 객체를 넣을 수 있었다.
- 수정: `DBNModel.findObjectFolder`는 클래스가 맞는 폴더가 여럿이면 읽은 폴더 중 항목 속성이 그 객체를 담는 폴더를 고른다
  (`DBNDatabaseNode.isCachedChildObject`: 속성을 `LocalCacheProgressMonitor`로 읽어 캐시만 본다. 이벤트는 UI 스레드에서 올 수 있어 DB를 읽지 않는다.
  객체 관리자는 이벤트 전에 객체를 캐시에 넣는다, `AbstractObjectManager.CreateObjectReflector`). 담는 폴더가 없으면 아직 읽지 않은 폴더,
  그것도 없으면 첫 폴더다. 폴더가 하나면 이전과 같고 아무것도 읽지 않는다. `cacheNodeChildren`은 필터로 걸러진 객체를 이 폴더에만 넣는다.
- 특정 드라이버를 분기하지 않았다. 같은 클래스를 받는 형제 폴더(SQL Server·Oracle·generic의 프로시저/함수) 모두에 적용되고, 모델 수정이라 SWT에도 적용된다.
- Swing의 "새 함수…"는 실행 뒤 폴더를 새로 고치고(폴더의 새로 고침은 스키마를 다시 읽음) 폴더마다 자기 속성으로 다시 읽으므로 원래도 맞는 폴더에 보였다.
  이 문제는 객체 관리자로 만든 객체(SWT의 스키마 노드에서 만들기 등)나 다른 곳에서 모델에 추가한 객체에서 생긴다.
- 확인(SQL Server 2022): DB에 함수를 만든 뒤 시험 에이전트가 그 함수를 스키마의 루틴 캐시에 읽어 넣고 컨테이너 없이 `OBJECT_ADD`를 보냈다
  (객체 관리자와 같은 순서). 수정 전 빌드는 `getParentNode`가 `.../schema/dbo_1/procedure`를 돌려주고 새 함수가 "저장 프로시저" 아래에 붙었다
  (`before-ko-light-mssql-new-function-parent.png`). 수정 뒤에는 `.../function`을 돌려주고 "함수" 아래에 붙는다(`ko-light-mssql-new-function-parent.png`).
  같은 방법으로 넣은 저장 프로시저는 "저장 프로시저" 아래에 붙는다.
  Swing "새 함수…"(New Function…) 템플릿을 실행한 뒤에도 `NewFunction`은 "함수"(Functions) 아래에 "Returns int", `@Param1`과 함께 보인다(영어).

## Object Explorer 개체 작업 (2026-11-07)

전수 조사 3순위의 탐색기 줄(C-10·11·12·17). 자세한 설계와 확인은 [swing-explorer-object-actions.md](swing-explorer-object-actions.md).

- 트리는 여러 노드를 고를 수 있다(`DISCONTIGUOUS_TREE_SELECTION`). 삭제, 이름 복사, SQL 생성은 선택 전체에 적용하고, 한 노드용 명령과
  속성 창은 둘 이상 고르면 꺼진다(`getSelectedNavigatorNode`가 null). 오른쪽 클릭이 고른 행 위면 선택을 유지한다.
- 삭제(Delete)·이름 바꾸기(F2): 연결·보안 주체·에이전트·스케줄러 갈래 다음에 DB 객체를 모델의 객체 관리자로 처리한다(`ExplorerObjectCommands`).
  확인(목록, 연쇄 삭제 옵션) → 스크립트 미리 보기(`ScriptPreviewDialog.confirmChange`, 취소가 기본) → 열린 트랜잭션 질문 → 실행.
- 이름 복사(Ctrl+C, Edit > 복사)·정규화된 이름 복사(Ctrl+Shift+C): 트리의 전송 처리기가 객체 이름을 복사한다.
- SQL 생성 메뉴: 생성기 확장점의 생성기(SELECT, INSERT, UPDATE, DELETE, MERGE, JOIN, CALL, DDL, PostgreSQL 전용 등). 결과는 새 쿼리 창.

## 제목 표시줄 (2026-11-07)

사용자 결정(2026-11-07): "화면 제목표시줄 가운데 chromite 문구는 안보이게 제거하라". 주 창의 FlatLaf 제목 표시줄은 창 제목을 그리지 않는다
(`JRootPane`의 `FlatClientProperties.TITLE_BAR_SHOW_TITLE = false`, `MainFrame` 생성자).

- 창 제목 자체("Chromite")는 그대로 둔다. Windows 작업 표시줄, Alt+Tab, macOS 창 메뉴는 이 이름을 쓴다.
- 주 창에만 건다. 테마 전체 설정(`TitlePane.showTitle`)으로 끄면 대화상자 제목까지 사라진다.

## 실행 확인의 기본 단추 (2026-11-08)

DDL·DML·작업·세션·파일을 바꾸는 확인과 스크립트 미리 보기 중 일부가 실행(Execute, 예, 확인)을 기본 단추로 두고 있었다.
습관처럼 Enter를 누르면 DROP, DELETE, 작업 실행, 세션 종료가 실행됐다. Object Explorer 삭제(#119), TRUNCATE·보안 편집기(#128)는 이미 고쳤고,
이번에 `ui.swing` 전체를 훑어 남은 곳을 같은 규칙으로 맞췄다.

### 규칙

- 무언가를 바꾸는 확인은 안전한 답(취소, 아니요, 닫기)이 기본 단추이고 처음 포커스도 그 단추에 있다. Enter는 아무것도 실행하지 않는다.
- 실행 단추에는 하는 일을 쓴다(삭제, Kill Session, 바꾸기, 실행…). 예/확인은 동사로 바꾸기 쉬운 곳만 바꿨다. 모델 계층의 질문(`confirmAction`)은
  문구를 호출자가 정하므로 예/아니요를 그대로 두고 기본만 아니요로 했다.
- 읽기만 하는 대화상자, 알림, 값을 입력받는 폼(OK가 폼을 적용하거나 사용자가 이미 고른 실행에 값을 넣는 것)은 그대로 둔다.
  폼의 OK가 바로 바꾸는 일을 시작하면(백업·복원, 전송 마법사의 Finish) 기본 단추를 없앤다. 폼에서 취소를 기본으로 하면 칸에서 친 Enter가
  설정을 버리고 창을 닫거나, 실행 중인 작업을 취소하기 때문이다.

### 공통 구현

| 위치 | 내용 |
|------|------|
| `SwingPlatformUI.confirmChange` (새 정적 메서드) | 동사 단추 / 취소 확인. `JOptionPane.showOptionDialog`의 초깃값을 취소로 주면 그 단추가 기본 단추이자 처음 포커스다(`BasicOptionPaneUI`). EDT에서 부른다 |
| `SwingPlatformUI.confirmAction(title, message, isWarning)` | 경고(`isWarning = true`)면 아니요가 기본. 단추 이름은 예/아니요 그대로. 경고가 아니면 예가 기본(전과 같음) |
| `ScriptPreviewDialog` | 실행 단추가 있든 없든 Cancel(Close)이 기본 단추이고 처음 포커스를 받는다. 늘 참이 된 `cancelDefault` 플래그를 지웠다 |
| `CoreMessages.common_replace` (새 키) | 파일 덮어쓰기 확인의 "Replace" / "바꾸기" |
| `DebugMessages.debug_compile_button` (새 키) | 디버그 컴파일 확인의 "Compile" / "컴파일" |

### 바꾼 대화상자

| 대화상자 (클래스) | 바꾸는 것 | 전: 단추(기본) | 후: 단추(기본) |
|------|------|------|------|
| 스크립트 미리 보기, 실행 단추 있음 (`ScriptPreviewDialog.show(…, true)`) | 테이블 디자이너 Save, SQL Server 에이전트 삭제·속성 OK, 작업 스케줄러 삭제·속성 OK의 스크립트 | Copy / Open / Execute / Cancel (Execute) | 같음 (Cancel, 포커스) |
| 결과 표 저장 미리 보기, 개체 변경 미리 보기 (`confirmSave`, `confirmChange`) | 결과 표 DML, Object Explorer 삭제·이름 바꾸기·유지 관리, 보안 편집기 | 같음 (Cancel) | 같음 (Cancel, 포커스 추가) |
| 모델의 경고 확인 (`SwingPlatformUI.confirmAction(…, true)`) | 확인이 필요한 연결 유형의 변경 쿼리(`SQLQueryExecuteHandler.confirmQueryExecution` 기본 구현), 결과 표 편집을 버리는 다시 읽기, 결과 집합이 많을 때 계속 읽기, Mimer 파일 이름 바꾸기, `@ai` 확인의 대체 경로 | 예 / 아니요 (예) | 예 / 아니요 (아니요) |
| 에이전트 개체 삭제 (`AgentCommands.delete`) | 작업·경고·운영자 DROP | 예 / 아니요 (예) | 삭제 / 취소 (취소) |
| 스케줄러 개체 삭제 (`SchedulerCommands.delete`) | 작업·이벤트 DROP | 예 / 아니요 (예) | 삭제 / 취소 (취소) |
| 지금 실행 (`SchedulerCommands`, Oracle) | 작업 실행 | 확인 / 취소 (확인) | 지금 실행 / 취소 (취소) |
| 연결 삭제 (`ConnectionCommands.delete`) | 연결 설정 삭제 | 예 / 아니요 (예) | 삭제 / 취소 (취소) |
| Kill Session (`LockManagerPanel`) | 잠금을 쥔 세션 종료 | 예 / 아니요 (예) | Kill Session / 취소 (취소) |
| 세션 명령 (`SessionManagerPanel`) | 세션 종료, 쿼리 취소 등 | 예 / 아니요 (예) | 명령 이름 / 취소 (취소) |
| 유지 관리 실행 (`MaintenanceDialog.confirm`) | VACUUM, OPTIMIZE, 인덱스 다시 작성 등 | 확인 / 취소 (확인) | 실행 / 취소 (취소) |
| 실행 중 닫기 (`MaintenanceDialog.closeRequested`) | 실행 중인 유지 관리 명령 취소 | 예 / 아니요 (예) | 예 / 아니요 (아니요) |
| 테이블 복사 TRUNCATE (`DatabaseCopyPages`) | 대상 테이블 행 삭제 | 예 / 아니요 (예) | 잘라내기 / 취소 (취소) |
| 테이블 복사 다시 만들기 (`DatabaseCopyPages`) | 매핑을 "삭제 후 다시 만들기"로 | 예 / 아니요 (예) | 삭제 후 다시 만들기 / 취소 (취소) |
| 가져오기 TRUNCATE (`DataImportWizard`) | 대상 테이블 행 삭제 | 예 / 아니요 (예) | 잘라내기 / 취소 (취소) |
| 데이터 전송 마법사 마지막 페이지 (`DataTransferWizard`) | 전송 시작 | Finish (Finish) | 같음 (기본 단추 없음. 앞 페이지의 Next, 진행 중 Cancel은 그대로) |
| 데이터베이스 작업 대화상자 (`DatabaseTaskDialog`) | 백업·복원 실행, 에이전트·스케줄러 속성 적용 | OK / Cancel (OK) | 같음 (기본 단추 없음) |
| 연결 가져오기 미리 보기 (`ConnectionImportDialog`) | 연결 추가, 충돌을 Replace로 둔 연결 바꾸기 | Import / Cancel (Import) | 같음 (Cancel, 포커스) |
| 오프라인 드라이버 묶음 설치 (`DriverBundleInstallDialog`) | 드라이버 파일 쓰기, 다른 파일 바꾸기 | Install / Cancel (바꿀 것이 있으면 Install) | 같음 (Cancel, 포커스) |
| 드라이버 삭제 (`DriverManagerDialog`) | 사용자 드라이버 삭제, 기본 드라이버 끄기 | 예 / 아니요 (예) | 삭제 / 취소 (취소) |
| 드라이버 라이브러리 제거 (`DriverLibrariesPanel`) | 드라이버 파일 목록에서 제거 | 예 / 아니요 (예) | 제거 / 취소 (취소) |
| 연결 유형 삭제 (`ConnectionTypesPage`) | 연결 유형 삭제, 쓰던 연결 옮기기 | 확인 / 취소 (확인) | 삭제 / 취소 (취소) |
| 서버 그룹 삭제 (`RegisteredServerCommands`) | 그룹 삭제, 서버 옮기기 | 예 / 아니요 (예) | 삭제 / 취소 (취소) |
| Query Library 삭제 (`QueryLibraryPanel`) | 쿼리 파일·폴더 삭제 | 확인 / 취소 (확인) | 삭제 / 취소 (취소) |
| 쿼리 기록 지우기 (`QueryHistoryDialog`) | 기록 전체 삭제 | 확인 / 취소 (확인) | 기록 지우기 / 취소 (취소) |
| AI 대화 삭제 (`AiAssistantPanel`) | 저장된 대화 삭제 | 확인 / 취소 (확인) | 대화 삭제 / 취소 (취소) |
| MCP 토큰 다시 만들기 (`AiOptionsPage`) | 기존 클라이언트의 토큰 무효화 | 확인 / 취소 (확인) | 다시 만들기 / 취소 (취소) |
| 디버그용 컴파일 (`DebugCommands`) | 루틴을 디버그 정보와 함께 다시 컴파일(DDL) | 확인 / 취소 (확인) | 컴파일 / 취소 (취소) |
| 비밀 기본값 경고 (`QueryParameterDefinitionsDialog`) | 비밀처럼 보이는 기본값을 공유 파일에 저장 | 예 / 아니요 (예) | 저장 / 취소 (취소) |
| 결과 표 변경 저장 질문 (`MainFrame.askSave`) | 쿼리 창 닫기·끝내기 때 결과 표 DML 저장(미리 보기가 꺼진 연결이면 바로 실행) | 저장 / 저장 안 함 / 취소 (저장) | 같음 (결과 표 변경이 있으면 취소, 텍스트 파일만이면 저장) |
| 파일 덮어쓰기 8곳 | 기존 파일 바꾸기: Save As(`MainFrame`), 결과 내보내기(`ResultSetExport`), ERD 그림(`ErdDiagramPanel`), 계획 원본(`PlanRawView`), Query Library 내보내기(`QueryLibraryPanel`)·Save As .sql(`QueryLibraryEditor`), 연결 내보내기(`ConnectionTransferCommands`), 드라이버 묶음(`DriverBundleCreateDialog`) | 예 / 아니요 (예) | 바꾸기 / 취소 (취소) |

### 이미 안전해서 그대로 둔 것

| 대화상자 (클래스) | 단추(기본) |
|------|------|
| Object Explorer 삭제 확인 (`ExplorerObjectCommands`, #119) | 삭제 / 취소 (취소) |
| TRUNCATE 확인 (`ObjectMaintenanceCommands`, #128) | 잘라내기 / 취소 (취소) |
| 보안 주체 삭제 (`SecurityCommands`, #128) | 삭제 / 취소 (취소) |
| 위험한 문·`@ai` 실행 확인 (`SqlConfirmDialog`) | 실행 / 취소 (취소, 포커스) |
| Chromite Agent 도구 확인 (`AiConfirmationDialog`) | 허용 / 거부, 트랜잭션이면 커밋 / 롤백 / 취소 (거부·취소, 포커스) |
| 셸 명령 승인 (`SwingShellCommandsService`) | 한 번 실행 / 항상 / 취소 (취소) |
| 열린 트랜잭션 질문 (`ExitTransactionCheck`, `AutoCommitApplier`, `ProcedureDebuggerPanel`) | 커밋 / 롤백 / 취소 (취소) |
| 결과 변경을 버리고 실행 (`SqlEditorPanel`) | 버리고 실행 / 취소 (취소) |
| Query Library 저장 충돌 (`QueryLibraryEditor.confirmOverwrite`) | 덮어쓰기 / 다시 읽기 / 취소 (취소) |
| SSH 호스트 키 (`JschUserInfoPrompter.promptYesNo`) | 예 / 아니요 (아니요) |
| 모델의 동사 확인 (`SwingPlatformUI.confirmAction(…, buttonLabel, true)`), 라이선스 동의 | 동사 / 취소 (취소), 동의 / 거절 (거절) |
| 생성 스크립트만 보기 (`ScriptPreviewDialog.show(…, false)`: Generate Script, 보안 Script) | Copy / Open / Close (Close) |
| MySQL 이벤트 스케줄러 상태 (`SchedulerCommands`) | 명령 열기 / 복사 / 닫기 (닫기) |

### 바꾸지 않은 것과 이유

- 텍스트 파일 저장 질문(`MainFrame.askSave`의 텍스트만, `TableDesignerPanel.confirmClose`): 저장이 기본. 텍스트 파일 저장은 DB를 바꾸지 않고,
  테이블 디자이너의 저장은 Cancel이 기본인 스크립트 미리 보기를 다시 거친다.
- 테이블 디자이너 Revert(`TableDesignerPanel.revert`): 확인이 기본. DB를 바꾸지 않고 디자이너의 편집만 버린다.
- 드라이버 파일 내려받기 질문(`SwingDriversService`): 내려받기가 기본. 사용자가 연결을 시작해서 뜬 질문이고, 없는 파일을 더하기만 한다.
- Query Library 가져오기 충돌(`QueryLibraryPanel`): 복사본 만들기가 기본(기존 쿼리를 바꾸지 않음). 지워진 쿼리 파일 다시 저장(`QueryLibraryEditor`): 사용자가 누른 저장이고 잃는 것이 없다.
- 지운 드라이버 되살리기(`DriverManagerDialog` Restore Deleted…): 되살리기만 한다.
- 값을 입력받는 폼: 연결 대화상자(Connect), 쿼리 매개변수(`QueryParameterDialog`의 Run과 스크립트 모드의 Execute: 사용자가 이미 고른 실행에 값을 넣음),
  디버그 시작(`DebugStartDialog`), 보안 속성(`PrincipalPropertiesDialog`, `ObjectPermissionsDialog`: OK가 Cancel 기본 미리 보기를 거침),
  일정(`AgentScheduleDialog`, `ScheduleBuilderDialog`, `QueryScheduleDialog`), 디자이너 하위 창(`DesignerDialogs`), Options, 드라이버 편집, 찾기·바꾸기,
  열 필터, Query Library 루트·기준값·매개변수 정의, 부트스트랩 쿼리, 연결 내보내기, 드라이버 묶음 만들기(파일 덮어쓰기는 따로 확인), 자격 증명·비밀번호·암호 문구·SSH 질문,
  Git 커밋 메시지(글 상자가 Enter를 받음), Query Library 저장 폼, 연결·서버 고르기.
- 모델 계층의 경고가 아닌 확인(`confirmAction(title, message)`): 예가 기본. 모두 가져오기, 바뀐 연결 다시 연결, AI 메타데이터 전송 동의, 새 비밀번호 저장 같은
  질문이 섞여 있어 기본을 일괄로 바꾸지 않았다. 이 중 변경을 하는 호출(Oracle PLAN_TABLE 만들기 `OracleDataSource`, 대상 DDL 자동 생성
  `DatabaseTransferUtils`, 리소스 복사·이동 `DBNPathBase`, 작업 실행 확인 `DBTaskUtils.confirmTask`)은 2026-11-08에 모델 호출자가 `isWarning`을 켜
  아니요가 기본이 됐다([model-fixes.md](model-fixes.md) "작은 모델·안전·라이선스 수정").

### 확인 (2026-11-08)

- `mvn package -Pproduct-chromite-swing` BUILD SUCCESS, `tools/rename/gate.py` ALL GATES PASSED. 회귀 테스트는 실행하지 않았다(요청 없음).
- 빌드한 앱(영어)을 테스트 DB에 붙여, 같은 프로세스 안에서 대화상자의 포커스 단추에 Enter 키 이벤트를 보내 확인했다(스크래치 객체만 쓰고 뒤에 지움).
  다섯 곳 모두 기본 단추·포커스가 취소(아니요)였고 Enter로 창만 닫혔다.

  | 대화상자 | 단추 (기본) | Enter 뒤 |
  |------|------|------|
  | Delete Connection (MariaDB 연결) | Delete / Cancel (Cancel) | 연결이 트리와 `data-sources.json`에 남음 |
  | 스케줄러 Delete (MariaDB 이벤트 `cds_evt`) | Delete / Cancel (Cancel) | `information_schema.EVENTS`에 이벤트 남음 |
  | Script - Delete cds_evt (`DROP EVENT`) | Copy / Open in Query Window / Execute / Cancel (Cancel) | 이벤트 남음 |
  | Confirm query execution (Production 유형 PostgreSQL, `DELETE … WHERE id = 1`) | Yes / No (No) | 행 남음, 트랜잭션 중인 세션 없음 |
  | Clear History | Clear History / Cancel (Cancel) | 기록 파일 3줄 그대로 |
- 영향받을 수 있는 기능: 위 표의 모든 확인(Enter가 이제 취소), 데이터베이스 작업 대화상자와 전송 마법사 마지막 페이지(Enter가 아무것도 하지 않음),
  모델의 경고 확인을 쓰는 확인 필요 연결 유형의 쿼리 실행.

## 앞 쿼리 창의 결과가 남던 문제 (2026-11-08)

사용자 보고(앱 변경 #18): 일반 쿼리 창에서 실행해 결과가 보이는 상태에서 Query Library > New Query로 연 탭(또는 이미 열린 그 탭)으로 가면
결과 창에 앞 창의 결과가 그대로 남았다.

### 재현과 근본 원인

- **재현:** 프로세스 안 시험 에이전트(OS 입력 없음)로 SQLite 쿼리 창 A를 실행하고, Query Library > New Query(`RtLib1`)를 만든 뒤 A → `RtLib1`로 돌아갔다.
  컴포넌트 트리는 맞았다: Results 탭의 컴포넌트는 `RtLib1`의 것이고 A의 결과 뷰어는 보이지 않았다(`isShowing() = false`).
  그런데 `RepaintManager`에 들어온 다시 그리기 요청을 기록하니 결과 영역에는 상태 줄과 26px 띠뿐이었다. 화면에는 A의 표 그림이 그대로 남는다.
- **근본 원인:** `MainFrame.attachResultTab`(origin/devel `MainFrame.java` 1868–1876행)은 `JTabbedPane.setComponentAt`으로 활성 쿼리 창의 컴포넌트를 끼운다.
  JDK의 `setComponentAt`은 컴포넌트를 바꿀 때 `revalidate()`만 하고 `repaint()`는 `null`로 바꿀 때만 한다. 끼운 컴포넌트가 전에 같은 탭에서 같은 크기로 배치된 적이 있으면
  배치가 위치·크기를 바꾸지 않으므로 다시 그리기 요청이 생기지 않는다. 결과 표가 있는 창은 표가 스스로 다시 그려 문제가 가려졌고, 결과가 없는 창(실행하지 않은 Query Library 편집 창)에서 드러났다.
- **같은 원인의 다른 경우:** 한 번 보였던 쿼리 창 중 결과 표가 없는 창은 모두 같다(실행 전 일반 쿼리 창, Query Library 실행·편집 창, 기록·Query Manager에서 연 창, 결과 없이 끝난 창).
  Messages·Execution plan·Terminal 탭도 같은 `attachResultTab`을 쓴다. 처음 여는 창은 컴포넌트 크기가 0에서 바뀌어 다시 그려졌고,
  DDL 탭·도구 문서 탭·빈 문서 영역은 Results 카드를 숨겼다가 다시 보이므로 카드 전체가 다시 그려졌다(같은 기록으로 확인).
- **수정:** 컴포넌트를 바꾼 뒤 탭 영역을 다시 그린다(`resultTabs.repaint()`). 같은 날 "결과 탭"으로 끼우는 단위가 쿼리 창의 탭 줄 전체가 되었고,
  그 끼우기(`MainFrame.attachResults`)도 바꾼 뒤 Results 카드를 다시 그린다. 같은 기록으로 모든 전환(쿼리 창 ↔ 쿼리 창, 실행하지 않은 Query Library 탭,
  새 쿼리 창, 새 Query Library 쿼리, DDL 탭에서 돌아오기)에서 카드 전체(745×279)가 다시 그려지는 것을 확인했다.

## 결과 탭 (2026-11-08)

사용자 요청(앱 변경 #7): "여러 쿼리를 실행한 결과가 한 화면의 스크롤로 나타난다. 이 부분은 ssms를 따라가지 말고 쿼리 결과별 탭으로 분리한다.
Result1, Result2 이런식으로 표시하면 된다". 결정: 한 줄에 나란히 `Result1 | Result2 | … | Messages`(메인 창 아래 Results / Messages 바깥 탭을 이 한 줄로 바꿈).

### 동작

- **탭 줄:** `결과 탭… | Messages | Execution plan | Terminal`(Terminal은 그 쿼리 창이 SQL Terminal을 켰을 때만). 결과가 하나여도 "Result1"(한국어 "결과1")이다.
  번호는 실행마다 1부터 결과가 나온 순서다. 결과 탭의 도구 설명은 그 결과를 만든 SQL의 첫 줄(120자까지)이다. 탭이 넘치면 한 줄 스크롤과 숨은 탭 목록(FlatLaf).
- **앞으로 오는 탭:** 실행을 시작하면 Messages(아직 결과가 없다). 그 실행의 첫 결과가 나오면 Result1. 뒤의 결과는 탭만 생기고 사용자가 고른 탭을 빼앗지 않는다.
  끝났을 때 오류로 멈췄거나 결과가 하나도 없으면 Messages, 아니면 그대로다. SQL Terminal을 켠 실행은 지금처럼 Terminal이 앞이다.
- **중간 오류(기본, 멈춤):** `SELECT t1; SELECT t2; SELECT nope; SELECT t3` → Result1·Result2는 남고, 셋째 뒤는 실행하지 않으며, Messages가 앞으로 와 오류
  (`메시지 1, 줄 3`, 오류 위치로 가기)를 보인다. 오류 문장용 빈 Result3은 없다. 엔진은 오류에서 멈추고 커밋도 롤백도 하지 않는다(커밋 방식 `NO_COMMIT`, 트랜잭션 안전 규칙).
- **건너뛰고 계속(Options):** 실패한 문장은 Messages에 쓰고 다음 결과가 이어서 Result3이 된다. 끝난 뒤 결과가 있으면 결과 탭을 그대로 두고,
  Messages 탭에 오류 점(아이콘 오른쪽 아래 빨간 점)과 개수(`Messages (1)`, 도구 설명 "Errors in the messages: 1")를 붙인다. 오류 점·개수는 멈춤 모드에서도 붙고 다음 실행에서 지운다.
- **행 수(쿼리 상태 줄):** 고른 결과 탭의 행 수다. 다른 탭(Messages, 계획, 터미널)이 앞이면 그 실행에서 읽은 모든 결과의 행 수다. 탭을 바꾸거나 결과를 다시 읽으면(필터, 정렬, 더 읽기) 바뀐다.
- **결과 고정(Query &gt; Pin Results, Ctrl+Shift+P):** 고른 결과 탭(다른 탭이 앞이면 마지막으로 고른 결과 탭)을 고정한다. 탭에 핀 아이콘과 고정 시각
  (`Result3 (23:15:58)`, 도구 설명 `SELECT * FROM t3 (pinned at 23:15:58)`)이 붙고 닫기 단추가 생긴다. 다음 실행은 고정하지 않은 결과 탭만 지우고 새 결과(번호는 1부터)를
  고정 탭 앞에 놓는다. 이미 고정한 탭을 다시 고정하면 상태 줄에 "These results are pinned already."다. 고정 탭 닫기와 저장하지 않은 변경 묻기(기본 Cancel)는 그대로다.
- **쿼리 창마다:** 탭 줄은 쿼리 창마다 하나이고 메인 창이 활성 쿼리 창의 탭 줄을 Results 카드에 끼운다. 고른 탭은 창에 남아 다른 창에 갔다 돌아와도 그대로다.
  활성이 아닌 창의 실행도 그 창의 탭 줄을 바꾼다(돌아오면 그 실행의 결과나 Messages가 앞이다). Results 카드를 보이는 것은 활성 창만이다.

### 설계

- **탭 줄의 주인은 쿼리 창(`QueryResultsView`):** 전에는 메인 창의 탭 하나(`resultTabs`)에 활성 창의 Results·Messages·계획·터미널 컴포넌트를 탭 번호로 끼우고,
  창마다 마지막으로 본 탭을 따로 기억했다(`messagesTabSelected`, `planTabSelected`, `terminalTabSelected`). 결과 탭 수가 창마다·실행마다 달라지면 탭 번호가 고정일 수 없으므로,
  쿼리 창이 탭 줄 전체(`JTabbedPane`)를 갖고 메인 창은 그것을 통째로 끼운다(`MainFrame.attachResults`). 고른 탭은 탭 줄 자체가 기억하므로 따로 기억할 상태와 탭 번호 상수를 지웠다.
- **숨기기 단추:** Results 카드의 — 단추는 하나이고 끼운 탭 줄의 뒤쪽 컴포넌트(FlatLaf `JTabbedPane.trailingComponent`)로 옮긴다. 빼는 탭 줄에서는 속성을 지운다
  (FlatLaf가 다른 부모에 들어간 단추의 위치를 바꾸지 않게).
- **결과 탭 내용:** 결과 뷰어(`ResultSetViewerPanel`) 하나가 탭을 채운다. 높이를 나누던 `GridStack`, 최소 높이 `MIN_GRID_HEIGHT`, 바깥 스크롤, 결과가 없을 때의 자리 표시
  ("No results.", `editor_results_none`)와 고정 탭 줄의 첫 탭 이름(`editor_results_current`)을 지웠다. 결과가 없는 창은 Messages와 Execution plan 탭만 있다.
- **고정의 단위:** 전에는 지금 결과 표 전부를 고정 탭 하나로 옮겼다. 결과가 탭으로 나뉘었으므로 고른 결과 탭 하나를 고정한다. 탭은 그 자리에 두고(이름과 아이콘만 바뀜)
  다음 실행이 새 결과를 고정 탭 앞에 넣는다.
- **오류 뒤 Messages:** 멈춤 모드에서 오류가 났거나(`hasErrors && !continueOnError`) 결과가 없으면 Messages를 앞으로. 건너뛰고 계속은 결과가 남아 있으므로 결과를 두고
  탭의 점·개수로 알린다. 오류 처리 방식은 준비 Job이 엔진에 넘기는 값과 같은 값을 실행 객체에 둔다(`Execution.continueOnError`).
- **핀 아이콘:** 아이콘 생성기(`tools/branding/gen_ui_icons.py`)에 `pin`(Tabler pin을 16px 격자로 다시 그림, 두 팩·밝은/어두운 테마)을 더했다.

### 바뀐 파일

| 파일 | 바뀐 것 |
|---|---|
| `QueryResultsView` | 탭 줄(결과 탭, Messages, Execution plan, Terminal), 결과 탭 더하기·지우기, 고른 결과 탭 고정, Messages 오류 점·개수, 고른 결과 탭의 행 수. `GridStack`·자리 표시 삭제 |
| `SqlEditorPanel` | 탭 줄에 계획 탭을 넘김, 실행 시작 Messages, 첫 결과만 앞으로, 끝의 Messages 규칙(`continueOnError`), 상태 줄 행 수(`updateRowCount`), 고정 결과 문구, 터미널 탭 붙이고 빼기. `planTabSelected`·`terminalTabSelected`와 접근자, `getPlanView`·`getTerminalView` 삭제 |
| `MainFrame` | 바깥 Results / Messages / Execution plan / Terminal 탭과 탭 번호 상수·`switchingResults`·`selectResultTab`·`updateTerminalTab`·`attachResultTab` 삭제, `attachResults`, 숨기기 단추 옮기기, `showResults`·`showPlan`·`showTerminal`이 창의 탭 줄을 바꿈 |
| `internal/EditorMessages`(`_ko`) | `editor_results_tab`, `editor_results_pinned_tip`, `editor_results_already_pinned`, `editor_results_messages_errors`(`_tip`) 추가, `editor_results_pinned` 형식, `editor_results_none`·`editor_results_current` 삭제 |
| `tools/branding/gen_ui_icons.py`, `icons/ui/*/*/pin.svg` | 고정 탭 아이콘 |

### 확인 (2026-11-08)

빌드 `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS. Windows 11, 150%, 빌드 결과물 한 벌(`D:\Tools\_scratch\chromite-result-tabs\app`),
`CHROMITE_DATA`와 작업 공간을 scratch에 두고 실행했다. 조작은 같은 프로세스에 붙인 시험 에이전트가 EDT에서 Swing 객체를 직접 다뤘다(화면 좌표·키보드 입력 없음).
SQLite 시험 DB(t1 3행, t2 5행, t3 7행). 캡처는 `D:\Tools\_scratch\chromite-result-tabs\shots\7-*.png`.

| 경우 | 결과 |
|---|---|
| SELECT 세 개 | `결과1 결과2 결과3 메시지 실행 계획`, 결과1이 앞, 도구 설명은 각 SQL, 상태 줄 `3개 행` |
| 결과2 / 메시지 고름 | 상태 줄 `5개 행` / `15개 행`(실행 전체) |
| 결과3 고정, 다시 고정 | `결과3 (23:15:58)`, 핀 아이콘, 닫기 단추 / "이미 고정한 결과입니다." |
| 다시 실행(SELECT 두 개) | `결과1 결과2 결과3 (23:15:58) 메시지 실행 계획`, 새 결과1이 앞, `1개 행` |
| 셋째 문장 오류(멈춤) | `결과1 결과2 [고정] 메시지 (1)`, 메시지가 앞(빨간 점), 결과3 없음, t3는 실행 안 됨. 오류 위치로 가기 → 3줄 |
| 셋째 문장 오류(건너뛰고 계속) | `결과1 결과2 결과3 [고정] 메시지 (1)`, 결과1이 앞 |
| 오류만 / UPDATE만 | 메시지가 앞, 결과 탭 없음(고정 탭만), `0개 행` |
| 새 창 Y → X로 돌아옴 | Y는 `메시지 실행 계획`만. X는 고른 결과2 그대로, `5개 행` |
| SQL Terminal 켬·실행·끔 | 실행 계획 뒤에 터미널 탭, 실행 중 터미널이 앞 / 끄면 탭이 빠지고 결과1이 앞 |
| SELECT 14개 | 결과1–결과14, 탭 줄 스크롤과 숨은 탭 목록 단추 |
| 고정 탭 닫기(변경 없음) | 묻지 않고 닫힘 |
| 탭 전환 다시 그리기(#18 기록) | 모든 전환에서 Results 카드 전체가 다시 그려짐 |

- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 표 편집·저장과 다시 실행 전 버리기 묻기, 창을 닫을 때 결과 저장 묻기, 결과 다시 읽기(필터·정렬·더 읽기),
  Generate SQL, 실행 계획 탭(Ctrl+L, 실제 계획), SQL Terminal, Results 숨기기·옆에 놓기·최대화, 테마 전환 뒤 활성이 아니던 창의 탭 줄 모양, Chromite Agent·MCP가 연 쿼리 창.

## 상태 표시줄의 쿼리 창 칸 (2026-11-08)

사용자 요청(앱 변경 #2): "결과 표 아래 상태표시줄에 나타나는 항목들을 화면전체 상태표시줄로 옮긴다". 결정: 표시줄 전체를 연결 색으로 칠한다.

### 동작

- **칸:** 활성 쿼리 창의 상태 칸(상태 아이콘·문구 │ 서버 │ 사용자 │ DB/스키마 │ 커밋 방식 │ 열린 트랜잭션 │ 경과 시간 │ 행 수 │ 줄·열)이 메인 창 상태 표시줄에 있다.
  상태 문구는 왼쪽, 칸은 오른쪽으로 붙고, 업데이트 받기 진행("새 버전 X 받는 중 (n%)"과 막대)은 그 뒤 오른쪽 끝이다. 폭이 모자라면 지금처럼 서버 칸이 먼저 줄거나 빠진다.
- **쿼리 창이 아닐 때:** 빈 문서 영역, DDL 탭, 도구 문서(테이블 디자이너, ER 다이어그램, Activity Monitor 등)에서는 지금 문구(작업 공간 연결 수)이고 바탕은 캔버스다.
- **연결 색:** 활성 쿼리 창의 연결에 색(연결에 정한 색, 없으면 연결 종류의 색)이 있으면 상태 표시줄 전체 바탕이 그 색이다. 테마 글자 색이 그 색과 4.5:1이 안 되면
  모든 글자를 대비 색(`ConnectionColors.getTextColor`)으로 그린다. 업데이트 진행 글자도 같다. 색이 없는 연결(기본 Development)은 캔버스 그대로다.
  탭을 바꿀 때, 연결 설정을 바꿀 때(`connectionChanged` → `setConnection`), 테마를 바꿀 때 바로 바뀐다.
- **누르기와 도구 설명:** 커밋 방식 칸(누르면 연결 설정의 Auto-commit으로 안내), 열린 트랜잭션 칸(누르면 커밋 / 롤백 / 트랜잭션 로그 메뉴), 모든 칸의 도구 설명은 그대로다.
- **결과 아래 줄은 없다:** Results 카드 아래(숨기면 편집기 아래)에 있던 쿼리 상태 줄 자리를 지웠다(규칙 4).

### 설계

- **칸은 쿼리 창의 것, 칠하기는 상태 표시줄:** `QueryStatusLine`은 쿼리 창마다 하나이고 그 창이 값을 넣는다(실행, 커밋 방식, 열린 트랜잭션 1초 갱신). 메인 창은 활성 창의
  줄을 상태 표시줄 가운데에 끼우고(`StatusBar.setLine`), 작업 공간 문구는 그동안 숨긴다. 줄은 바탕을 그리지 않고(투명) 연결 색과 대비 글자 색을 정해 속성
  `chromite.band`(`QueryStatusLine.BAND_PROPERTY`)로 알린다. 상태 표시줄이 그 값으로 바탕을 칠하고 대비 글자 색을 클라이언트 속성 `chromite.statusBarForeground`에 둔다.
  업데이트 진행 글자(`UpdateUi`)는 그릴 때 이 속성을 읽는다(FlatLaf 스타일은 이 번들의 익명 클래스에 색을 넣지 못하므로 보조 글자 색도 직접 읽는다, `QueryStatusLine`의 `BandLabel`과 같은 방식).
- **위 선과 높이:** 쿼리 상태 줄의 위 1px 선은 상태 표시줄에 구분선이 없으므로(`StatusBar.background` = 캔버스, `topBorderWidth 0`) 지웠다. 칸 높이는 상태 표시줄 높이
  (`size.statusBar`)이고 따로 있던 `size.statusLine.query`(`Chromite.size.queryStatusLine.height`, 테마 세 파일)를 지웠다. 두 값은 같았다(26, 촘촘한 모드 24).
- **테마 전환:** 활성이 아니던 창의 줄은 끼울 때 Look and Feel을 맞추고(`updateLookAndFeel`), 줄의 `updateUI`가 테마별 연결 종류 색을 다시 정해 알린다.
  상태 표시줄의 `updateUI`도 지금 줄의 색을 다시 칠한다(부모가 자식보다 먼저 불리므로 둘 다 필요하다).

### 바뀐 파일

| 파일 | 바뀐 것 |
|---|---|
| `MainFrame` | `StatusBar.setLine`·`applyBand`(연결 색, 대비 글자 색 속성 `STATUS_BAR_FOREGROUND_KEY`), `attachStatusLine`이 상태 표시줄에 끼움. `resultsStatusHolder`·`documentStatusHolder`·`placeStatusLine`·Results 카드의 표시 리스너 삭제, 클래스 Javadoc |
| `QueryStatusLine` | 투명, 위 1px 선 삭제, 연결 색을 `BAND_PROPERTY`로 알림(`getBandColor`, `getBandForeground`), 높이 `size.statusBar`, 클래스 Javadoc |
| `UpdateUi` | 진행 글자가 상태 표시줄의 대비 글자 색을 따름 |
| `UiSizes`, `ChromiteLightLaf`·`ChromiteDarkLaf`·`ChromiteCompact.properties` | `QUERY_STATUS_LINE_HEIGHT`(`Chromite.size.queryStatusLine.height`) 삭제 |
| `SqlEditorPanel` | 주석(상태 줄이 메인 창 상태 표시줄에 보임) |

### 확인 (2026-11-08)

"결과 탭"과 같은 환경(빌드 결과물 한 벌, scratch 작업 공간, EDT 시험 에이전트, 150%). 연결: RT Plain(색 없음), RT Prod(Production 유형 색), RT Dark(연결 색 40,40,120).
캡처는 `D:\Tools\_scratch\chromite-result-tabs\shots\2-*.png`.

| 경우 | 결과 |
|---|---|
| 문서 없음 / DDL 탭 | 바탕 캔버스(233,234,238), "작업 영역의 연결 3개", 쿼리 칸 없음 |
| RT Plain | 바탕 캔버스, 쿼리 칸(상태, `rt.db`, 자동 커밋, 시간, `3개 행`, `줄 1, 열 18`), 작업 공간 문구 숨김, 높이 26, 결과 카드에는 탭 줄만 |
| RT Prod(밝은 테마 / 어두운 테마) | 바탕 250,207,207 / 97,61,63, 글자는 테마 색(대비 충분) |
| RT Dark + 업데이트 진행 | 바탕 40,40,120, 모든 칸과 진행 글자 흰색(대비 색), 진행은 오른쪽 끝 |
| RT Plain에 연결 색 255,200,0을 넣고 연결 변경 알림 / 지움 | 바탕 255,200,0 / 캔버스 |
| 수동 커밋으로 바꾸고 UPDATE | 열린 트랜잭션 칸(손 모양 커서, 도구 설명). 누르면 메뉴 `[커밋] [롤백] [트랜잭션 로그…]`, 롤백 → 칸 사라짐 |
| 커밋 방식 칸 | 손 모양 커서와 도구 설명(누르기 동작은 그대로) |

- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 상태 표시줄의 작업 공간 연결 수, 업데이트 받기 진행 표시, 쿼리 상태 칸의 커밋 방식·열린 트랜잭션 누르기,
  촘촘한 모드의 상태 표시줄 높이, Results 숨기기(Ctrl+R) 뒤의 상태 칸 위치.
- 그대로 둔 것(보고): 대비 색은 지금처럼 본문 글자 색(`Label.foreground`)으로만 판단한다. 어두운 테마의 Production 색(97,61,63) 위의 보조 글자(`#73767c`)는 대비가 낮다
  (전에도 같은 색의 줄이었다).

## 변경 이력

- 2026-11-06 최초 작성. Swing 전환 결정, 구조, 재사용 분석, MVP 범위, F5 실행 사양을 정리.
- 2026-11-06 쿼리 실행 계층 분리: `SQLQueryJob`의 실행 로직을 `model.sql`의 `SQLQueryExecuteJob`·`SQLQueryExecuteHandler`로 옮기고,
  Swing 편집기 API, F5·GO 배치 연결 방법, `ResultSetModel`·`ResultSetPersister` 이전 분석을 추가.
- 2026-11-06 A단계 구현: `com.seizia.chromite.ui.swing` 번들(앱, 플랫폼, `SwingPlatformUI`, 메인 창), FlatLaf 3.8 연동,
  `com.seizia.chromite.swing.feature` 피처, `product-chromite-swing` 제품, Chromite 창 아이콘. "구현 현황 — A단계" 절 추가.
- 2026-11-06 B단계 구현: SSMS식 메인 창(Object Explorer, 문서 탭, Results/Messages, Properties, `JSplitPane` 기반 도구 창과 배치 저장),
  탐색기 모델 기반 Object Explorer(백그라운드 읽기, `SwingConnectionService`로 연결, JSVG 아이콘), 속성 표, 라이트/다크 테마와 저장,
  About 대화상자, 진행 대화상자·비모달 알림, Swing 번들 버전을 제품 버전(26.2.5)으로 변경. "구현 현황 — B단계" 절 추가.
- 2026-11-06 C단계 구현: RSyntaxTextArea 4.0.1 기반 SQL 편집기 탭(New Query, F5 실행·취소), `GO` 배치 묶기(`SQLScriptBatches`, model.sql),
  편집기별 Results(결과 표 쌓기)·Messages(행 수, 경고·PRINT, 오류 위치), 쿼리 상태 줄. "구현 현황 — C단계" 절 추가.
- 2026-11-06 D단계 구현: 연결 만들기·편집 대화상자(드라이버 고르기, 드라이버 정의로 구성하는 범용 설정 화면, 드라이버·공급자 속성 표, Test Connection),
  연결 명령(연결, 끊기, 다시 연결, 이름 바꾸기, 삭제), `openConnectionEditor`, 드라이버 파일 내려받기 확인(`UIServiceDrivers`),
  탐색기 모델 이벤트로 Object Explorer 갱신과 상태 표시줄 연결 수 갱신. "구현 현황 — D단계" 절 추가, B·C단계의 남긴 항목에서 구현한 것을 뺌.
- 2026-11-06 Swing 제품에 공개판 내장 드라이버 포함(`com.seizia.chromite.drivers.public.feature`). "내장 드라이버" 절 추가, D단계의 남긴 항목에서 뺌.
- 2026-11-06 E단계 구현: SQL 파일 열기·저장·다른 이름으로 저장·최근 파일(문자 집합·BOM·줄바꿈 유지), 고침 표시와 닫을 때·끝낼 때 저장 확인,
  편집기 도구 막대의 연결 콤보와 기본 DB/스키마 콤보, Object Explorer의 Select Top 1000 Rows·Script as(CREATE·SELECT)·View DDL,
  결과 표 오른쪽 클릭 메뉴(복사, 머리글 포함 복사, CSV 내보내기). "구현 현황 — E단계" 절 추가, 기존 단계의 남긴 항목에 구현 표시. XLSX 내보내기는 보류.
- 2026-11-06 F단계 구현: SQL 자동완성(model.sql의 `SQLQueryCompletionAnalyzer`, Ctrl+Space·`.`·글자 입력 뒤 자동 실행, 비동기 분석과 입력 중 취소, 엔진 일치 규칙으로 거르기),
  결과 표 XLSX 내보내기(`DataExporterXLSX`, 숫자·불리언·날짜 원래 값 유지), Swing 피처에 `data.office`·Apache POI·Commons 번들 추가, NOTICE 갱신.
  "구현 현황 — F단계" 절 추가, 재사용 분석 표와 이전 단계의 남긴 항목에 구현 표시.
- 2026-11-06 G단계 구현: 결과 표 편집·저장(단일 테이블·고유 키 결과만, 모델 계층 `DBDResultSetModel` 구현과 `DBDResultSetDataUpdater` 상속, `bindAttributes`로 편집 가능 판단),
  셀 편집·NULL·행 추가·삭제, 한 트랜잭션 저장과 저장 뒤 행 다시 읽기, 실패 시 Messages와 변경 유지, 닫기·끝내기·다시 실행 전 확인.
  "구현 현황 — G단계" 절 추가, 재사용 분석 표와 이전 단계의 남긴 항목에 구현 표시.
- 2026-11-06 디자인 방향 변경: 배치·동작은 SSMS, 모양은 다크·라이트 모두 DataGrip New UI. 목표 사양을 [design-spec.md](design-spec.md)로 분리하고 목적·MVP·테마 절에 연결.
- 2026-11-06 디자인 적용: design-spec.md의 P0 전체와 P1 일부 적용(다크 기반 `FlatDarkLaf`, 토큰 properties, Inter·JetBrains Mono 글꼴 jar,
  아이콘 팩 2종 duo-tone·SSMS color와 View > Theme > Icon Style, 트리·머리말·도구 막대·탭·그리드·편집기 모양, Object Explorer Filter·Collapse All).
  "디자인 적용" 절 추가, 테마·노드 아이콘·편집기 색·설정 키 절을 갱신.
- 2026-11-06 SSMS식 Connect 대화상자: 2단계 마법사 → 한 화면(Database type 콤보와 More database types…, Server name 한 칸과 드라이버별 서버 기록,
  Authentication 콤보(범용 칸만으로 되는 인증 방식), Options >> 탭(Connection Properties, Driver·Provider Properties, Additional Connection Parameters),
  새 연결 Connect = 저장 + 연결). "Connect 대화상자 (SSMS식)" 절 추가, D단계 절에 바뀐 곳 표시.
- 2026-11-06 Islands 적용: 모양 기준을 DataGrip Islands로 바꿈(캔버스 위 둥근 카드, 카드 사이 6px 틈의 분할선, 알약 모양 문서 탭, Islands 팔레트).
  "Islands 적용" 절 추가.
- 2026-11-06 P1 디자인 적용: 결과 그리드 머리글(가로 스크롤 원인, 행 번호 클릭 스크롤, 열 너비 여백), 제목 표시줄 40·메뉴 24, Edit·Tools·Window 메뉴(찾기·바꾸기, Options),
  문서 탭 닫기 단추·가운데 단추·도구 설명, F5 포커스 분기, SSMS 쿼리 상태 줄, SSMS 속성 그리드, 자동완성 목록, 스크롤바. "P1 디자인 적용" 절 추가.
- 2026-11-06 편집기 글꼴과 SQL 색: 편집기 글꼴 Cascadia Mono 13(번들, Options > Editor에서 변경), SSMS 구문 색, 방언 기반 단어 분류(`SqlTokenClassifier`),
  Options > Editor Colors(테마별 사용자 색), DDL 탭도 객체 연결의 방언. "편집기 글꼴과 SQL 색" 절 추가, 편집기·설정 키·P1 남긴 것 갱신.
- 2026-11-06 화면 문구에서 다른 제품 이름 제거: 아이콘 팩 "Duo-tone"·"Full color", Options > Editor Colors의 "Reset to Defaults".
- 2026-11-06 Registered Servers 도구 창(서버 그룹 = 연결 폴더), Object Explorer는 연 서버만 보이는 평평한 목록으로 바꿈.
  [swing-registered-servers.md](swing-registered-servers.md).
- 2026-11-06 관리 도구: Tools·Object Explorer 메뉴의 Activity Monitor(세션, 잠금)와 Find Objects. 설계는 [swing-admin-tools.md](swing-admin-tools.md).
- 2026-11-06 데이터 가져오기·내보내기: Object Explorer Tasks > Import Data… / Export Data…, 결과 표 Export Data…(쿼리를 다시 실행해 모든 행). 설계는 [swing-data-transfer.md](swing-data-transfer.md).
- 2026-11-06 결과 뷰어: 결과 표마다 값 패널(JSON·XML·이미지·16진수, 편집), 머리글 정렬(서버·표 안), WHERE 필터와 기록, 칸 값으로 거르기, 다음 페이지·모두 읽기, 레코드 보기,
  열 너비 맞춤, Copy As, 선택 집계. 설계는 [swing-result-viewer.md](swing-result-viewer.md).
- 2026-11-06 SQL 편집기 확장: Execution plan 탭(Ctrl+L, Ctrl+M), Query History, Edit > Advanced(서식·대소문자·주석·들여쓰기), 템플릿, 매개변수 대화상자, 상태 줄 Ln/Col.
  [swing-sql-editor-plus.md](swing-sql-editor-plus.md)에 정리.
- 2026-11-06 고급 연결 설정: 인증 방식별 칸, 네트워크 핸들러 탭(SSH, Proxy, SSL), SQL Server·Oracle 주 칸, 연결 색(쿼리 상태 줄, Object Explorer), 연결 직후 SQL.
  [swing-conn-advanced.md](swing-conn-advanced.md)로 분리. "Connect 대화상자 (SSMS식)"의 남긴 것 중 해당 항목은 그 문서에서 구현했다.
- 2026-11-06 테이블 디자이너: Object Explorer의 Design·New Table…, 열·키·외래 키·인덱스 편집과 변경 스크립트 미리 보기·저장, 다른 곳에서 추가·새로 읽은 객체 노드를 트리에 반영. [swing-table-designer.md](swing-table-designer.md) 참고.
- 2026-11-06 Query Library: 왼쪽 도구 창(Ctrl+Alt+T)에서 쿼리를 트리로 관리·실행, 매개변수 바인딩, 쿼리 창 확장 지점(`SqlEditorExtension`)과 실행 엔진의 네이티브 바인딩 지점.
  [swing-query-library.md](swing-query-library.md).
- 2026-11-06 알림(`NotificationToast`): 메시지가 있는 새 알림이 창 높이만큼 크게 그려지던 문제. `layoutToasts`가 한 번도 배치되지 않은 토스트의
  선호 높이를 재어 줄바꿈 글 상자가 너비 없이 줄을 나눴다. 토스트를 그 너비로 먼저 배치(`validate`)한 뒤 높이를 잰다.
- 2026-11-06 연결 색 알약: Registered Servers와 Object Explorer의 서버 이름을 연결 색 알약으로 감쌈(줄 전체·노드 바탕 대신), 색 규칙 통일, 보조 글자 기준선 고침.
  [swing-registered-servers.md](swing-registered-servers.md) "연결 색 알약".
- 2026-11-06 화면 언어: 화면 문구를 NLS 묶음(영어 기본, 한국어 `_ko`)으로 옮겨 OS 언어를 따르게 함, 한국어 semibold 한글을 굵은 대체 글꼴로 그림. [swing-l10n.md](swing-l10n.md).
- 2026-11-06 DBeaver 모델 번들 한국어 보충: 개체 탐색기 폴더·속성 이름·세션/잠금/계획 열·데이터 전송 속성·인증 방식 이름이 한국어로 나옴. [swing-l10n.md](swing-l10n.md) "DBeaver 플러그인 한국어 보충".
- 2026-11-06 창 아이콘 `icons/chromite<크기>.png`를 사용자가 고른 새 아이콘으로 교체(`tools/branding/gen_app_icons.py`로 생성). [branding.md](branding.md) "아이콘".
- 2026-11-06 ER 다이어그램: Object Explorer의 View Diagram(스키마·테이블), Java2D 다이어그램 탭(자동 배치, 까마귀발 관계선, 확대·이동, PNG·SVG·.erd 내보내기, 배치 저장). [swing-erd.md](swing-erd.md).
- 2026-11-06 개체 정의 편집: Object Explorer의 Modify, Script as ▸ ALTER To, New View… / New Procedure… / New Function…. [swing-source-editor.md](swing-source-editor.md).
- 2026-11-06 백업·복원: Object Explorer의 데이터베이스 Tasks ▸ Back Up… / Restore…(SQL Server T-SQL, PostgreSQL·MySQL 로컬 클라이언트 도구). [swing-backup-restore.md](swing-backup-restore.md).
- 2026-11-06 테이블 복사: Object Explorer Tasks > Copy Tables…, Export Data…의 "Database" 대상(다른 연결·DB로 테이블 복사, 매핑·대상 DDL 미리 보기). [swing-data-transfer.md](swing-data-transfer.md) "테이블 복사".
- 2026-11-06 보안 편집기: 로그인·사용자·역할 속성과 새로 만들기·삭제, 테이블·뷰·루틴의 사용 권한(SQL Server, PostgreSQL, MySQL/MariaDB). [swing-security.md](swing-security.md).
- 2026-11-06 도구 막대 확장(사용자 결정 "가"): 주 도구 막대를 SSMS Standard 배치로(Save All, Undo/Redo, Activity Monitor, 도구 창 켜고 끄기, Find), 쿼리 창 도구 막대에 Comment·Uncomment·들여쓰기·Format SQL·Query History, 좁은 창의 넘침 메뉴. "도구 막대" 절 추가.
- 2026-11-06 데이터베이스 노드의 Import Data… / Export Data… / Copy Tables…(스키마별 원본 목록, 가져오기 대상 테이블 고르기·새 테이블). [swing-data-transfer.md](swing-data-transfer.md) "데이터베이스 노드".
- 2026-11-06 도구 창 도킹: ModernDocking 1.4.9 도입, 분할 창 배치(`stackBelow`, `setDefaultShown`)를 대체, 끌어 옮기기·탭·떠 있는 창·자동 숨기기, 배치 파일 저장,
  기본 배치 Object Explorer 위·Registered Servers 아래, 기본 열 너비 비율(22%·18%). "도구 창 도킹" 절 추가, B단계 배치·설정 절에 표시.
- 2026-11-06 빈 문서 영역: Welcome 탭을 없애고 문서가 없을 때 문서 영역에 가로 로고(SVG, 테마별)·안내·단축키. "빈 문서 영역" 절 추가.
- 2026-11-07 Results 자동 숨김(사용자 결정): Results 카드는 활성 문서가 쿼리 창일 때만 보이고, 숨김은 쿼리 창마다 기억, 쿼리 창이 없으면 Ctrl+R·View > Results를 끔.
  표시 여부 설정 `results.visible`과 Results 자리 표시 삭제. "Results 자동 숨김" 절 추가, B단계 설정 표·C단계 Results 절·도구 창 도킹·빈 문서 영역 절에 표시.
- 2026-11-07 오류 문구: 예외를 화면 문구로 바꾸는 곳을 `ErrorText` 하나로 모아 클래스·패키지 이름이 보이지 않게 함, 오류 대화상자에 접힌 자세히(스택 추적)·복사,
  인라인 오류 영역에 "자세히…", Swing `UIServiceSystemAgent`(`SwingSystemAgent`) 등록으로 백업·복원 메시지의 "Service … not found" 줄 제거,
  연결·연결 끊기 오류 제목 지역화. "오류 문구" 절 추가.
- 2026-11-07 내부 이름 변경([internal-rename.md](internal-rename.md)): "식별자" 절과 오류 문구 절의 로그 파일 이름 설명 갱신. 문서 안 경로·클래스 이름은 도구가 함께 바꿨다.
- 2026-11-07 XLSX 내보내기 설명: Office 확장 피처의 설치 원본(CE 업데이트 저장소)을 삭제한 것을 반영.
- 2026-11-07 UI 글꼴 Pretendard 13(번들, `UiFont`)과 기본 밀도 B: 컴포넌트에 흩어져 있던 치수를 테마의 `Chromite.size.*` 키(`UiSizes`)로 모으고
  트리 22·그리드 24·도구 창 머리말 34·주 도구 막대 32 등으로 낮춤. 결과 창 기본 높이는 그대로(결정 대기). design-spec.md "치수 토큰", "적용 현황 — Pretendard와 밀도 B".
- 2026-11-07 Object Explorer 후속 수정: 탐색 불가 항목(프로시저·함수 매개 변수, MySQL/MariaDB 시스템 정보의 변수·상태·엔진 등)을 트리에 보여 줌,
  반환 값 매개 변수 이름. "Object Explorer 후속 수정" 절 추가, B단계 데이터 흐름 2·3 갱신.
- 2026-11-07 SQL Server 스키마를 새로 고친 뒤 테이블 트리거가 비던 문제(트리거 캐시도 비움). "Object Explorer 후속 수정" 절.
- 2026-11-07 새 객체의 부모 폴더: 같은 클래스를 받는 형제 폴더 중 객체를 담는 폴더를 고름(`DBNModel`, 새 함수가 "저장 프로시저"에 붙던 문제).
  "Object Explorer 후속 수정" 절.
- 2026-11-07 후속 수정(편집기·결과·도구): 자동완성 프로시저 제안 설정, 날짜/시간 데이터베이스 형식 설정, 남은 영어 문구 등. [swing-followup-fixes.md](swing-followup-fixes.md).
- 2026-11-07 서버 대시보드: Activity Monitor 첫 탭 "개요"(연결별 실시간 차트), Tools·Object Explorer의 Server Dashboard. [swing-dashboard.md](swing-dashboard.md).
- 2026-11-07 드라이버 관리자: Tools > Driver Manager…, Connect 대화상자의 Edit Driver…(드라이버 만들기·편집, 로컬 jar·폴더 라이브러리, 오프라인 모드). [swing-driver-manager.md](swing-driver-manager.md).
- 2026-11-07 SWT 프런트엔드 삭제([swt-removal.md](swt-removal.md)): 목적 절에 안내, 식별자 표(피처가 `licenses/`를 넣음, 실행 파일 이름을 유지하는 이유), 기능 공백·설정 저장 위치 설명 갱신.
- 2026-11-07 테마 전환 뒤 SQL 편집기·값 패널 세로 스크롤바 트랙이 옛 테마 색으로 남던 문제: `ChromiteScrollBarUI`가 뷰 바탕색 변경을 듣고 다시 그린다. "테마" 절.
- 2026-11-07 자동 업데이트: 제목 표시줄의 "새 버전 X · 다시 시작" 단추, 상태 표시줄의 받기 진행, Tools > Options > Updates, 끝낼 때 커밋하지 않은 트랜잭션 질문. [auto-update.md](auto-update.md).
- 2026-11-07 도구 창 최소 폭: 카드 최소 폭을 머리말(단추 + 제목 첫 글자와 말줄임표)이 정하고 내용은 최소 폭을 갖지 않음(Properties가 일정 폭 이하로 줄지 않던 문제),
  도구 창 도구 막대를 `OverflowToolBar`로. "도구 창 도킹 — 도구 창 최소 폭" 절.
- 2026-11-07 Query Library 자리: 한 열의 두 도구 창(Query Library·Properties, Object Explorer·Registered Servers)이 서로를 기본 이웃으로 두고,
  문서 영역 옆 열로 돌아가는 도구 창은 이웃이 같은 쪽에 있으면 이웃의 열로. "도구 창 도킹 — Query Library 자리" 절.
- 2026-11-07 Results 기본 높이 220 → 280(사용자 결정, 밀도 B 때 결정 대기였던 것). "Results 자동 숨김" 절의 크기·Reset Window Layout.
- 2026-11-07 Object Explorer 테이블 저장 방식 표시(Altibase 메모리 빨강, 디스크 파랑, 휘발성 호박색, 툴팁). [altibase.md](altibase.md).
- 2026-11-07 도구 막대 두 줄(사용자 결정): 쿼리 창마다의 도구 막대를 없애고 메인 창 둘째 줄에 활성 쿼리 창을 따르는 SQL Editor 도구 막대.
  "SQL Editor 도구 막대" 절 추가, "도구 막대" 절에 안내, C단계 취소·E단계 연결 콤보 위치 갱신.
- 2026-11-07 자동 커밋 상태(사용자 요청·결정): 쿼리 상태 줄의 커밋 방식 칸(초록·빨간 점), 누르면 연결 설정의 Auto-commit으로 안내,
  설정을 바꾸면 열린 세션에도 적용(커밋하지 않은 변경은 커밋/롤백/취소를 물음). "자동 커밋 상태" 절 추가.
- 2026-11-07 테마를 바꾼 뒤 문서 탭을 모두 닫고 새로 열면 문서 탭이 `ArrayIndexOutOfBoundsException`으로 깨지던 문제 수정:
  빈 화면 전환을 컨테이너 리스너 대신 탭 추가·삭제가 끝난 뒤에. "빈 문서 영역" 절에 원인과 설계.
- 2026-11-07 주 창 제목 표시줄 가운데의 "Chromite"를 그리지 않음(사용자 결정). "제목 표시줄" 절.
- 2026-11-07 작업 공간 위치: `-data`가 없으면 현재 폴더가 아니라 기본 작업 공간을 씀(시작 순서 3번, [workspace-location.md](workspace-location.md)).
- 2026-11-07 SQL Server 에이전트 관리: 개체 탐색기의 에이전트 노드, 작업·작업 활동 모니터·경고·운영자·오류 로그. [swing-sqlserver-agent.md](swing-sqlserver-agent.md).
- 2026-11-07 실행 계획 세 보기: 계획 탭에 그래픽(연산자 그림)·트리·원본 보기 전환. [swing-sql-editor-plus.md](swing-sql-editor-plus.md) "실행 계획 세 보기".
- 2026-11-07 결과 그리드 열 필터: WHERE 필터 줄 삭제, 열 머리글의 필터·정렬 아이콘과 열 필터 팝업, 상태 줄 필터 요약. "Results / Messages" 절, [swing-grid-column-filters.md](swing-grid-column-filters.md).
- 2026-11-07 AI 도우미(2단계): 오른쪽 도구 창 "AI Assistant", AI 도구 확인 대화상자, 쿼리 창 편집기의 "Ask AI About This Query", Options > AI Assistant, Query Library 기준값 편집. [ai-agent.md](ai-agent.md) "2단계 구현".
- 2026-11-07 작업 스케줄러(Oracle DBMS_SCHEDULER, PostgreSQL pg_cron, MySQL/MariaDB 이벤트): 탐색기 노드·명령·창·기록 탭. [swing-job-schedulers.md](swing-job-schedulers.md).
- 2026-11-07 결과 표 저장 전 스크립트 미리 보기: 설정 `resultset.confirm.beforeSave`와 연결 유형 "데이터 변경 확인"이 다시 동작(전수 조사 B-R19), Options 항목. "저장 전 스크립트 미리 보기" 절.
- 2026-11-07 Object Explorer 개체 작업: DB 객체 삭제·이름 바꾸기(스크립트 미리 보기, 열린 트랜잭션 질문), 이름·정규화된 이름 복사, 여러 개 선택,
  SQL 생성 메뉴. "Object Explorer 개체 작업" 절, E단계 남긴 것에 표시. [swing-explorer-object-actions.md](swing-explorer-object-actions.md).
- 2026-11-07 쿼리 실행의 오류 처리를 설정에서 넘김(오류 뒤 무확인 롤백 고침), C단계 "커밋·오류 처리" 항목 갱신. [swing-options.md](swing-options.md).
- 2026-11-07 쿼리 창마다 별도 연결: 기본 DB/스키마 콤보, E·G·C단계 "남긴 것"의 편집기별 별도 연결 항목 갱신. [transactions.md](transactions.md) "쿼리 창마다 별도 연결".
- 2026-11-07 Database type 콤보 설명: MySQL 드라이버 내장을 없앴으므로 "공개판에 드라이버가 내장된 주요 DB"를 "주요 DB"로 고침(목록은 그대로). [drivers.md](drivers.md).
- 2026-11-08 실행 확인의 기본 단추: 데이터·스키마·작업·세션·파일을 바꾸는 확인과 실행 미리 보기는 취소(아니요, 닫기)가 기본 단추이고 처음 포커스,
  실행 단추에 동사(삭제, Kill Session, 바꾸기…). 백업·복원 대화상자와 전송 마법사의 Finish는 기본 단추가 없음. "실행 확인의 기본 단추" 절.
- 2026-11-08 View > Compact Mode(촘촘한 모드): "테마" 절의 시작 순서와 전환 설명. 상세는 [design-spec.md](design-spec.md) "적용 현황 — 촘촘한 모드".
- 2026-11-08 "실행 확인의 기본 단추"의 쿼리 매개변수 폼: 쿼리 창 대화상자(`SqlParameterDialog`)가 `QueryParameterDialog`의 스크립트 모드로 합쳐짐
  ([swing-query-library.md](swing-query-library.md) "매개변수 대화상자와 바인딩 하나로").
- 2026-11-08 "바꾸지 않은 것과 이유"에 남겼던 모델 호출자 네 곳(PLAN_TABLE 만들기, 대상 DDL 자동 생성, 파일 복사·이동, 작업 실행 확인)을 경고 확인으로 바꿈
  ([model-fixes.md](model-fixes.md) "작은 모델·안전·라이선스 수정").
- 2026-11-08 결과 편집(B3): G단계 "남긴 것" 갱신, 수동 커밋 저장 안내 문구가 저장하고 커밋·쿼리 > 커밋을 가리킴. [swing-grid-editing.md](swing-grid-editing.md).
- 2026-11-08 "자동 커밋 상태"에 B-S18 사용자 결정(세션 전환은 Query 메뉴와 SQL Editor 도구 막대) 안내. [transactions.md](transactions.md) "커밋 방식 전환".
- 2026-11-08 SQL Editor 도구 막대: Actual Plan이 실제 계획을 주지 않는 연결에서 꺼지고 도구 설명이 이유를 말함. [swing-sql-editor-plus.md](swing-sql-editor-plus.md) "실제 계획 토글을 Altibase·Oracle로".
- 2026-11-08 앞 쿼리 창의 결과가 남던 문제(앱 변경 #18): 결과 탭에 컴포넌트를 끼운 뒤 다시 그림. "앞 쿼리 창의 결과가 남던 문제" 절.
- 2026-11-08 결과 탭(앱 변경 #7, 사용자 결정): 결과 집합마다 탭 Result1…, 쿼리 창마다의 탭 줄이 바깥 Results / Messages 탭을 대신함, 고른 결과 탭 고정,
  중간 오류 규칙, Messages 오류 점·개수, 상태 줄 행 수는 고른 결과 탭. "결과 탭" 절 추가, 쿼리 실행 사양·C단계 Results / Messages 절에 표시.
- 2026-11-08 상태 표시줄의 쿼리 창 칸(앱 변경 #2, 사용자 결정): 쿼리 상태 칸을 결과 아래에서 메인 창 상태 표시줄로 옮기고 표시줄 전체를 연결 색으로 칠함,
  `size.statusLine.query` 삭제. "상태 표시줄의 쿼리 창 칸" 절 추가, P1 쿼리 상태 줄·Results 자동 숨김 절에 표시.
- 2026-11-08 SQL Editor 도구 막대의 연결·DB 콤보를 맨 앞으로, 연결 콤보 260 → 220, 콤보 도구 설명에 전체 이름(앱 변경 #3): "콤보를 맨 앞으로, 연결 콤보 220" 절.
- 2026-11-08 주 도구 막대의 Chromite Agent 단추를 오른쪽 끝에 아이콘 + 글자로(앱 변경 #6, `OverflowToolBar.setTrailing`): "Chromite Agent 단추를 오른쪽 끝으로" 절.
