# Swing 프런트엔드

## 목적

DBeaver의 SWT/Eclipse RCP UI를 Swing으로 새로 만든다.
화면 배치는 SSMS를 따르고, 디자인은 DataGrip(다크)과 SSMS(라이트) 스타일로 한다.

## 왜 Swing인가

- DataGrip(IntelliJ 플랫폼)이 Swing 기반이다. DataGrip 수준의 디자인을 내려면 Swing이 유리하다.
- SWT는 각 OS의 네이티브 위젯을 쓰므로 트리, 탭, 스크롤바 등의 모양을 바꾸는 데 한계가 있다.
- SWT 안에 Swing을 섞는 방식(SWT_AWT 브리지)은 포커스, 키 입력, HiDPI 문제가 잦아 쓰지 않는다.

## 현재 코드 규모 (2026-11-06 측정)

| 구분 | 규모 |
|---|---|
| 전체 Java 파일 | 6,949개, 플러그인 163개 |
| UI 계층 (SWT·JFace·Eclipse UI·`org.jkiss.dbeaver.ui` 의존) | 약 2,050개 파일, 약 38.6만 줄 → 새로 작성 |
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

- **출발점:** [org.jkiss.dbeaver.headless](../../plugins/org.jkiss.dbeaver.headless) 플러그인이 SWT 없이 모델 계층을 띄운다.
  `DBeaverHeadlessApplication`(`DesktopApplicationImpl` 상속), `DBeaverTestPlatform`(`BasePlatformImpl` 상속),
  `DBeaverTestPlatformUI`(`ConsoleUserInterface` 상속)로 구성되며, 의존하는 번들은
  `equinox.app`, `core.resources`, `model`, `model.rcp`, `registry`뿐이다.
- **새 플러그인:** headless 앱과 같은 방식으로 `DesktopApplicationImpl`을 상속하고, 시작할 때 Swing 창을 연다.
- **모델 → UI 호출:** 모델 계층은 오류 표시, 확인 질문, 비밀번호 입력 등을
  [DBPPlatformUI](../../plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/runtime/ui/DBPPlatformUI.java)(메서드 약 28개)로 요청한다.
  이것을 Swing으로 구현하면 모델 계층을 고치지 않고 쓸 수 있다.
- **스레드:** 화면 작업은 Swing EDT에서 하고, DB 작업은 모델 계층의 Job으로 백그라운드에서 실행한다.
- **제품 구성:** SWT UI 플러그인을 뺀 Swing 전용 `.product`를 새로 만든다.
- **Java 버전:** 새 Swing 코드는 Java 25를 기준으로 한다(권장, [runtime.md](runtime.md) 참고).

## 외부 라이브러리 (후보)

| 라이브러리 | 용도 | 라이선스 |
|---|---|---|
| FlatLaf | IntelliJ/Darcula 계열 테마, JBR 사용자 정의 제목 표시줄 지원 | Apache 2.0 |
| RSyntaxTextArea | SQL 편집기 (구문 강조, 자동완성 팝업) | BSD |
| ModernDocking | SSMS식 도킹 창 배치 | MIT |
| JSVG | 탐색기 SVG 아이콘 그리기 (B단계 도입) | MIT |

도입 전에 각 라이브러리의 최신 라이선스를 다시 확인한다. ModernDocking은 B단계에서 검토했고 아직 쓰지 않는다("구현 현황 — B단계").

## 기존 코드 재사용 분석

| 기능 | 핵심 클래스 | 위치 | 판단 |
|---|---|---|---|
| 연결 정보, 드라이버 목록 | `DataSourceDescriptor`, `DriverDescriptor` | `registry` | 그대로 재사용 |
| 자동완성 | `SQLCompletionAnalyzer` | `model.sql` (SWT 의존 없음) | 분석 로직 재사용, 팝업 UI만 새로 작성 |
| SQL 문장 분리 | `SQLScriptParser` | `model.sql` | 그대로 재사용 |
| DDL 보기, Script as | `DBPScriptObject` | `model` | 그대로 재사용 |
| 결과 내보내기 | `DataExporterCSV` 등 | `data.transfer` (SWT 의존 없음) | 엔진 재사용, 설정 화면만 새로 작성 |
| 쿼리 실행 | `SQLQueryExecuteJob` + `SQLQueryExecuteHandler` | `model.sql` (SWT 의존 없음) | 2026-11-06 `SQLQueryJob`에서 분리 완료. 그대로 재사용 ("쿼리 실행 계층") |
| 결과 데이터 모델 | `ResultSetModel` (1,261줄, UI import 1개) | `ui.editors.data` | 색상 처리를 떼어 낸 뒤 이전 ("쿼리 실행 계층"의 결과 모델 분석) |
| 결과 편집 저장 | `ResultSetPersister` (816줄, UI import 4개) | `ui.editors.data` | `ResultSetViewer` 의존을 끊은 뒤 이전 (같은 곳) |

## MVP 범위

1. **메인 창 (SSMS 배치):** 왼쪽 Object Explorer, 가운데 쿼리 탭, 아래 Results / Messages 탭, 오른쪽 Properties
2. **연결:** 범용 연결 화면 하나로 전체 드라이버를 지원한다. 드라이버 정의의 속성 목록으로 화면을 자동으로 구성한다
   (호스트, 포트, DB, 계정 + 드라이버 속성 표). 저장 형식은 기존 모델 계층을 그대로 써서 SWT 버전과 연결 목록을 공유한다.
3. **Object Explorer:** 서버 → DB → 스키마 → 테이블·뷰·프로시저 트리
4. **쿼리 편집기:** 구문 강조, 자동완성, 실행 (아래 "쿼리 실행 사양")
5. **결과 그리드:** 조회 결과 표시, 복사, 편집·저장 (SSMS의 Edit Top 200 Rows에 해당)
6. **Script as / DDL 보기:** 객체 우클릭 → CREATE·SELECT 스크립트 생성, DDL 보기
7. **결과 내보내기:** CSV, Excel
8. **테마:** DataGrip풍 다크, SSMS풍 라이트

MVP 이후: 테이블 디자이너, ER 다이어그램, 데이터 비교, AI 기능 등.

## 쿼리 실행 사양

| 단축키 | 동작 |
|---|---|
| F5 | 선택 영역이 있으면(1자 이상) 선택한 내용만 실행하고, 없으면 편집기 전체를 실행한다 |

- SWT 버전의 "스크립트 실행" 명령([SQLEditor.java:2965](../../plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/SQLEditor.java:2965))도
  같은 방식이지만 선택 길이가 2자 이상일 때만 선택으로 본다. Swing 버전은 1자부터 선택으로 본다.
- **SQL Server:** SSMS처럼 `GO`를 기준으로 배치를 나눠 배치 단위로 서버에 보낸다.
  같은 배치 안에서 `DECLARE`한 변수를 이어서 쓸 수 있어야 한다.
  (기존 DBeaver 방식은 구분자로 문장을 나눠 하나씩 실행한다.)
- **결과 표시:** 결과 창 하나에 결과 표를 위아래로 쌓고, 메시지(영향받은 행 수, 오류, PRINT 출력)는 Messages 탭에 모은다.
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
  ([SQLServerDialectBase.java:121-129](../../plugins/org.jkiss.dbeaver.ext.mssql/src/org/jkiss/dbeaver/ext/mssql/model/SQLServerDialectBase.java)).
  `extractScriptQueries(..., includeBatchDelimiterElements = true)`로 나누면 `GO` 자리에 `SQLBatchDelimiterElement`가 들어온다.
  Swing 편집기는 두 `GO` 사이의 문장들을 원문 범위 그대로 하나의 `SQLQuery`로 합쳐 Job에 넘긴다.
  엔진은 `SQLQuery` 하나를 JDBC 실행 한 번으로 보내므로 배치 안에서 `DECLARE`한 변수가 유지되고,
  배치가 돌려준 결과 집합마다 `getDataReceiver(query, n)`이 불려 결과 표가 차례로 쌓인다.
  영향받은 행 수는 `SQLQueryResult.getExecuteResults()`, PRINT 출력과 경고는 `SQLQueryResult.getWarnings()`(JDBC `SQLWarning`)로 받아 Messages 탭에 쓴다.
  `SQLBatchDelimiterElement`는 엔진에 넘기지 않는다(엔진은 `SQLQuery`와 제어 명령만 실행한다).
- 배치 문장은 JSQLParser가 하나의 문장으로 해석하지 못하므로 SELECT로 보지 않는다. 그래서 행 수 제한이 쿼리 변환(`TOP`)이 아니라
  가져오기 단계(최대 행 수까지만 읽기)에서만 적용되고, 운영(confirm) 연결에서는 실행 확인을 묻는다. 의도한 동작이다.

### 결과 모델 분석 (`ResultSetModel`, `ResultSetPersister`, 이번에는 옮기지 않음)

| 클래스 | 막는 의존 (파일:줄) | 해결 방향 |
|---|---|---|
| `ResultSetModel` | `SearchQuickFilterInfo`(`org.jkiss.dbeaver.ui` 번들, :39, :74, :109-119) — 클래스 자체는 SWT 없음 | 모델 번들로 옮기거나 `Predicate<ResultSetRow>`로 바꾼다 |
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

## 위험과 주의 사항

- **연결 설정 화면:** 각 DB의 전용 연결 화면은 SWT로 된 `ext.*.ui` 플러그인에 있어 재사용할 수 없다. MVP는 범용 화면으로 대신한다.
  드라이버별 특수 설정(예: Oracle SID/서비스명, SQL Server Windows 인증)이 범용 화면에서 표현되는지 확인해야 한다.
- **결과 편집 저장:** 키 판별, 트랜잭션, 오류 처리가 필요해 MVP에서 가장 신경 쓸 부분이다.
- **기능 공백:** SWT 버전에 있던 ER 다이어그램, 데이터 비교, 설정 화면, 드라이버 관리 등은 다시 만들기 전까지 Swing 버전에 없다.
- **디자인 자산:** JetBrains와 Microsoft의 아이콘, 로고 등 고유 디자인 자산은 복사하지 않는다.
  배치와 사용 흐름, 색감만 참고하고, 아이콘은 직접 그리거나 공개 라이선스 아이콘 세트를 쓴다.
- **아이콘 규모:** 기존 플러그인 아이콘은 약 1,060개다. 자주 보이는 것부터 단계적으로 만든다.

## 구현 현황 — A단계

A단계의 목표는 SWT 없이 모델 계층을 띄우고 빈 메인 창을 여는 배관 작업이다.
SSMS 배치, Object Explorer 등 실제 화면은 B단계에서 만든다.

### 식별자

새 Chromite 코드는 Java 패키지와 OSGi ID 모두 `com.seizia.chromite` 접두어를 쓴다(2026-11-06 결정). 기존 `org.jkiss.dbeaver.*`는 그대로 둔다.

| 구분 | ID / 위치 |
|---|---|
| 번들 | `com.seizia.chromite.ui.swing` ([plugins/com.seizia.chromite.ui.swing](../../plugins/com.seizia.chromite.ui.swing)), 패키지 `com.seizia.chromite.ui.swing` |
| Equinox 애플리케이션 | `com.seizia.chromite.ui.swing.application` |
| 제품 확장 | `com.seizia.chromite.ui.swing.product` (`org.eclipse.core.runtime.products`) |
| `org.jkiss.dbeaver.application` 확장 | `chromite-swing` (이름 `Chromite`, `GeneralUtils.getProductName()`이 읽음) |
| 피처 | `com.seizia.chromite.swing.feature` ([features/com.seizia.chromite.swing.feature](../../features/com.seizia.chromite.swing.feature)) |
| 제품 정의 | uid `com.seizia.chromite.swing.product`, [product/swing/chromite-swing.product](../../product/swing/chromite-swing.product) |
| 실행 파일 | `chromite-swing.exe` / `ChromiteSwing.app` (SWT 제품의 `chromite`를 덮어쓰지 않도록 이름을 나눔) |
| Maven 프로파일 | `product-chromite-swing` ([product/pom.xml](../../product/pom.xml)). Windows x64, macOS arm64만 빌드 |
| Maven groupId | `com.seizia.chromite` (번들, 피처, 제품 모듈) |

번들과 피처는 `plugins/pom.xml`, `features/pom.xml`의 `desktop` 프로파일(헤드리스 빌드가 아닐 때 기본 활성)에 넣었다.
그래서 `-Pproduct-dbeaver-ce` 빌드에서도 함께 컴파일된다(SWT 제품에는 들어가지 않는다).

```bash
mvn package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing
```

결과물: `product/swing/target/products/com.seizia.chromite.swing.product/win32/win32/x86_64/chromite-swing/`,
`.../macosx/cocoa/aarch64/ChromiteSwing.app/`

### 시작 흐름

헤드리스 앱([org.jkiss.dbeaver.headless](../../plugins/org.jkiss.dbeaver.headless))과 같은 구조로 만들었다.

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
3. 작업 공간을 잠근다. `-data`가 없으면 SWT 버전과 같은 기본 작업 공간(`DBeaverData/workspace6`)을 쓴다.
   잠글 수 없으면 오류 대화상자를 띄우고 끝낸다.
4. `initializeApplicationServices()` → 디버그 로그(`.metadata/dbeaver-debug.log`, 표준 출력/오류를 함께 기록) →
   `DBWorkbench.getPlatform()`으로 플랫폼을 초기화한다(인증서 저장소 → `DesktopWorkspaceImpl` → 프로젝트 → Query Manager → 탐색기 모델).
   작업 공간 형식, 기본 프로젝트 이름(`General`), 탐색기 모델(`DesktopNavigatorModel`)은 SWT 버전과 같아서 연결 목록을 공유한다.
5. `postInitialize()`로 플러그인 서비스와 연결 감시 Job을 시작한다(SWT에서는 워크벤치 창 어드바이저가 호출).
6. 작업 공간의 모든 프로젝트에서 연결 수를 세어 상태 표시줄 문구(`N connections in workspace`)를 만든다.
   모델 계층이 정상으로 떴는지 확인하는 용도다.
7. EDT에서 `MainFrame`을 열고, 창이 닫힐 때까지 애플리케이션 스레드가 기다린다(`start()`가 반환되면 Equinox가 종료되기 때문).
8. 창을 닫거나 File > Exit를 누르면 창이 dispose되고, 애플리케이션 스레드가 작업 공간을 저장하고 플랫폼을 정리한 뒤 반환한다.
   `IApplication.stop()`(외부 종료 요청)은 창을 닫아 같은 경로로 끝낸다.

제품 설정에서 `org.eclipse.core.runtime`을 시작 수준 4로 자동 시작한다. SWT 제품에서는 워크벤치 번들이 시작시키지만
Swing 제품에는 그런 번들이 없어서, 빼면 `Unable to acquire application service` 오류로 실행되지 않는다(2026-11-06 확인).

SWT 버전의 `DesktopPreferencesInitializer`(`org.jkiss.dbeaver.core`)가 정하던 기본 설정은 Swing 제품에 없다.
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
  이 방식은 기존 `org.jkiss.dbeaver.core`의 `lib/awt.injector-1.0.0.jar`와 같다.
- 바이너리를 커밋하지 않는다. 번들 `pom.xml`의 `maven-dependency-plugin:copy`가 `generate-resources` 단계(컴파일 전)에
  Maven Central에서 받아 `lib/flatlaf.jar`로 복사한다. 버전은 `pom.xml`의 `flatlaf.version` 한 곳에만 있다.
  `lib/`는 번들의 `.gitignore`로 제외한다.
- 다른 방법(`pomDependencies`의 `wrapAsBundle`)은 루트 POM의 공유 빌드 설정과 피처·제품 모듈까지 바꿔야 해서 쓰지 않았다.
- FlatLaf의 네이티브 라이브러리(Windows 창 장식 등)는 jar 안에 들어 있고, FlatLaf가 실행 중에 임시 폴더로 풀어서 쓴다.
- `NOTICE`에 출처를 적었다. 라이브러리를 추가하면 이 표와 `NOTICE`를 함께 갱신한다.

### 창 아이콘과 JVM 실행 방식

JBR/JetBrains 브랜드가 화면에 보이지 않게 한다.

- **창 아이콘:** 모든 최상위 창에 Chromite 아이콘을 지정한다(`ChromiteIcons`, `Window.setIconImages`).
  크기는 16, 20, 24, 32, 40, 48, 64, 128, 256이다. 16·32·48·64·128·256은 `product/community/icons-sources`의 Chromite 아이콘을
  그대로 복사했고, 20·24·40은 512px 원본을 축소해 만들었다(번들의 `icons/chromite<크기>.png`).
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
(macOS arm64 결과물은 129개, 같은 검사 0개). 런타임 피처(`org.jkiss.dbeaver.runtime.feature`)와 DB 피처(`org.jkiss.dbeaver.db.feature`)를
그대로 포함하고, Equinox 번들은 필요한 것만 직접 넣었다.

### A단계에서 남긴 것 (B단계 이후)

- 작업 공간 선택, 잠긴 작업 공간 무시하고 열기, 이전 버전 작업 공간 이전, 인스턴스 서버, CLI 명령 처리(SWT 앱의 `DBeaverApplication`에 있음)
- SWT `DesktopPreferencesInitializer`의 나머지 기본 설정(Query Manager 기록 기간 등). Swing용 기본 설정 초기화가 필요하다.
- 설정 저장 위치: 설정은 이 번들 ID(`com.seizia.chromite.ui.swing`) 기준으로 저장되어 SWT 버전(`org.jkiss.dbeaver.core`)과 공유되지 않는다.
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
| `ToolWindow` | 도구 창 하나(분할 창의 한쪽). 머리말(제목, 도구 단추, 숨기기 `×`), View 메뉴 토글, 크기·표시 여부 저장 |
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
- **문서 탭:** Welcome 탭 하나. SQL 편집기 탭은 C단계에서 넣는다.
- **메뉴:** File(Exit), View(도구 창 3개, Theme > Light/Dark), Help(About Chromite). F5는 쿼리 실행용으로 비워 둔다.
- **About:** 제품명, 부제, 버전, 저작권("© 2026 Chromite contributors. Based on DBeaver Community © DBeaver Corp"), 웹사이트
  (https://github.com/froiibn/chromite, 누르면 브라우저로 연다). 제품명·부제·저작권은 `plugin.xml` 제품 확장의 속성에서 읽는다.
  버전은 SWT 버전과 같이 제품 번들 버전(`GeneralUtils.getPlainVersion()`)이라, Swing 번들 버전을 제품 버전과 같은 `26.2.5`로 바꿨다
  (SWT의 제품 번들 `org.jkiss.dbeaver.ui.app.standalone`과 같은 방식. 이전 `1.0.0`은 About에 그대로 나왔다).

### 도킹 라이브러리 검토 (ModernDocking 대 `JSplitPane`)

B단계는 `JSplitPane`만 쓴다.

- B단계 배치는 SSMS의 기본 배치 하나(고정 위치의 도구 창 3개 + 문서 탭)이고, 필요한 동작은 크기 조절·숨기기·저장뿐이라 분할 창으로 충분하다.
- ModernDocking(MIT)을 쓰면 OSGi 매니페스트 없는 jar를 FlatLaf처럼 빌드 때 받아 `Bundle-ClassPath`에 넣고, `NOTICE`·체크섬을 관리하고,
  라이브러리 자체의 배치 저장 형식(XML)을 따라야 한다. 지금 얻는 것(끌어다 놓기, 떠 있는 창, 자동 숨김)에 비해 의존성과 테마·포커스 문제를 살필 범위가 크다.
- 도구 창은 `ToolWindow` 하나로 감싸 두었으므로, 끌어다 놓기나 떠 있는 창이 필요해지면 `ToolWindow`와 `MainFrame`의 배치 부분만 도킹 라이브러리로 바꾸면 된다.

### Object Explorer 데이터 흐름과 스레드

1. 트리 뿌리(보이지 않음)는 활성 프로젝트의 `DBNProject`다. 첫 단계에 `DBNProject.getDatabases()`의 자식(연결 폴더 `DBNLocalFolder`, 연결 `DBNDataSource`)을 읽는다.
   탐색기 모델은 SWT 버전과 같은 `DBWorkbench.getPlatform().getNavigatorModel()`(`DesktopNavigatorModel`)이다.
2. 자식이 있을 수 있는 노드(`DBNNode.hasChildren(true)`, 메타데이터만 보고 DB를 읽지 않음)에는 "Loading…" 자식을 미리 넣어 펼칠 수 있게 한다.
3. 노드를 펼치면(`TreeWillExpandListener`) `AbstractJob`을 예약하고, Job 스레드에서 `DBNUtils.getNodeChildrenFiltered(monitor, node, true)`
   (SWT `DatabaseNavigatorContentProvider`와 같은 걸러내기: 숨은 객체, 트리에 보이지 않는 메타 노드 제외, 정렬)를 부른다. EDT에서는 모델을 읽지 않는다.
4. 결과는 `SwingUtilities.invokeLater`로 EDT에서 트리 모델에 반영한다(`nodeStructureChanged`, 펼친 상태 유지). 노드마다 읽기 번호를 두어
   새로 고침으로 더 새 읽기가 시작되었으면 늦게 끝난 이전 결과를 버린다.
5. **연결:** 연결 노드의 자식을 읽으면 `DBNDatabaseNode.getChildren` → `initializeNode` → `DBUtils.initDataSource` →
   `DBWorkbench.getService(DBServiceConnections.class).initConnection(monitor, …)`로 연결한다
   ([DBNDatabaseNode.java:221-243](../../plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/model/navigator/DBNDatabaseNode.java),
   [DBUtils.java:2792-2808](../../plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/model/DBUtils.java)).
   SWT 제품에서는 `org.jkiss.dbeaver.core`의 `UIServiceConnectionsImpl`이 이 서비스인데 Swing 제품에는 없어서, 서비스가 없으면
   연결하지 않고 "Connection was canceled"로 끝난다. 그래서 `SwingConnectionService`를 `org.jkiss.dbeaver.service` 확장으로 등록했다.
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
  `DBPPropertySource`면 그것을, 아니면 `PropertyCollector`로 `@Property` 게터를 모은다. 둘 다 `org.jkiss.dbeaver.model`에 있고 UI에 의존하지 않아 그대로 쓸 수 있었다.
- 값 읽기는 게으른 속성(DB 조회가 필요한 값)을 포함하므로 백그라운드 Job에서 모니터를 넘겨 읽는다. 선택이 바뀌면 이전 Job을 취소한다.
  범주가 있는 속성은 범주 이름 줄(굵게) 아래에 모은다. 읽기 전용이다.

### 노드 아이콘

- `DBNNode.getNodeIcon()`은 `DBIcon`이고 위치는 `platform:/plugin/<번들>/<경로>`다. `Bundle.getEntry`로 번들 안의 파일을 직접 찾는다(SWT `DBeaverIcons`와 같은 위치 규칙).
- DBeaver 트리 아이콘은 대부분 SVG다(예: `org.jkiss.dbeaver.model/icons/tree`에 SVG 73개, PNG 2개). 그래서 PNG만 읽는 방식으로는 부족하고,
  SVG는 JSVG로 읽어 그릴 때마다 벡터로 그린다(HiDPI에서도 선명). PNG는 `@2x` 파일이 있으면 `BaseMultiResolutionImage`로 묶는다.
- 상태 겹침 아이콘(`DBIconComposite`, 예: 연결됨 표시)은 기본 아이콘 네 모서리에 겹쳐 그린다(SWT `OverlayImageDescriptor`와 같음).
- 읽을 수 없는 아이콘은 `DBIcon.TYPE_UNKNOWN`, 그것도 없으면 Look and Feel의 트리 잎 아이콘으로 대신한다. 위치별로 캐시한다(실패도 캐시).
- 아이콘 파일은 DBeaver 번들의 것(Apache 2.0)을 그대로 쓴다. JetBrains·Microsoft 아이콘은 쓰지 않는다.

### 테마

| 테마 | 클래스 | 바탕 | 바꾼 값 (`<클래스>.properties`) |
|---|---|---|---|
| Light (SSMS풍) | `ChromiteLightLaf` | `FlatLightLaf` | 강조색 `#0078d4`(운영체제 강조색 대신 고정), 바탕 `#f3f3f3` |
| Dark (DataGrip풍) | `ChromiteDarkLaf` | `FlatDarculaLaf` | 바탕 `#2b2d30`, 글자 `#dfe1e5`, 강조색 `#3574f0` |

- FlatLaf는 Look and Feel 클래스 계층마다 "클래스 이름.properties"를 읽어 덮어쓴다. 그래서 외부 테마 파일 없이 색 변수 몇 개만 바꿨다.
  JetBrains·Microsoft의 테마 파일과 아이콘은 쓰지 않고 색감만 참고했다.
- View > Theme에서 바꾸면 바로 `FlatLaf.setup()` + `FlatLaf.updateUI()`로 열린 모든 창을 다시 그리고, 설정에 저장해 다음 시작에도 쓴다.
  Look and Feel 색을 쓰는 직접 만든 테두리(상태 표시줄, 도구 창 머리말)는 `updateUI()`에서 다시 만든다.
- 시작 순서: 작업 공간을 잠그기 전에는 설정을 읽을 수 없으므로(인스턴스 위치가 정해지기 전) 시작 오류 대화상자용으로 라이트 테마를 먼저 설치하고,
  메인 창을 열기 직전에 저장된 테마로 바꾼다.

### 설정 저장 위치

테마, 창 위치·크기·최대화 여부, 도구 창 크기·표시 여부는 이 번들의 설정 저장소(`BundlePreferenceStore`, 작업 공간
`.metadata/.plugins/org.eclipse.core.runtime/.settings/com.seizia.chromite.ui.swing.prefs`)에 저장한다.

| 키 | 값 |
|---|---|
| `ui.theme` | `LIGHT` / `DARK` |
| `mainWindow.bounds`, `mainWindow.maximized` | `x,y,너비,높이`(최대화 전 크기), 최대화 여부 |
| `objectExplorer.visible`, `objectExplorer.size` 등 (`properties.*`, `results.*`) | 표시 여부, 너비(위·아래 분할은 높이) |

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

- 빌드: `-Pproduct-dbeaver-ce,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 129개, macOS arm64 130개(A단계 + JSVG)이고,
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
- **연결 화면:** 범용 연결 대화상자(`openConnectionEditor`), 새 연결 만들기·편집·삭제, 연결 끊기 메뉴.
- 탐색기: 모델 이벤트(`INavigatorListener`)로 트리 갱신(다른 곳에서 연결을 추가·삭제·연결 끊기 했을 때), 활성 프로젝트 전환, 객체 편집기, Script as, 필터.
- 위 "DBPPlatformUI B단계 구현"의 남은 항목.
- 도킹(끌어다 놓기, 떠 있는 창)이 필요해지면 ModernDocking 재검토.

## 변경 이력

- 2026-11-06 최초 작성. Swing 전환 결정, 구조, 재사용 분석, MVP 범위, F5 실행 사양을 정리.
- 2026-11-06 쿼리 실행 계층 분리: `SQLQueryJob`의 실행 로직을 `model.sql`의 `SQLQueryExecuteJob`·`SQLQueryExecuteHandler`로 옮기고,
  Swing 편집기 API, F5·GO 배치 연결 방법, `ResultSetModel`·`ResultSetPersister` 이전 분석을 추가.
- 2026-11-06 A단계 구현: `com.seizia.chromite.ui.swing` 번들(앱, 플랫폼, `SwingPlatformUI`, 메인 창), FlatLaf 3.8 연동,
  `com.seizia.chromite.swing.feature` 피처, `product-chromite-swing` 제품, Chromite 창 아이콘. "구현 현황 — A단계" 절 추가.
- 2026-11-06 B단계 구현: SSMS식 메인 창(Object Explorer, 문서 탭, Results/Messages, Properties, `JSplitPane` 기반 도구 창과 배치 저장),
  탐색기 모델 기반 Object Explorer(백그라운드 읽기, `SwingConnectionService`로 연결, JSVG 아이콘), 속성 표, 라이트/다크 테마와 저장,
  About 대화상자, 진행 대화상자·비모달 알림, Swing 번들 버전을 제품 버전(26.2.5)으로 변경. "구현 현황 — B단계" 절 추가.
