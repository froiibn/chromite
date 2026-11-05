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
| RSyntaxTextArea | SQL 편집기 (구문 강조, 자동완성 팝업, C단계 도입) | BSD |
| ModernDocking | SSMS식 도킹 창 배치 | MIT |
| JSVG | 탐색기 SVG 아이콘 그리기 (B단계 도입) | MIT |

도입 전에 각 라이브러리의 최신 라이선스를 다시 확인한다. ModernDocking은 B단계에서 검토했고 아직 쓰지 않는다("구현 현황 — B단계").

## 기존 코드 재사용 분석

| 기능 | 핵심 클래스 | 위치 | 판단 |
|---|---|---|---|
| 연결 정보, 드라이버 목록 | `DataSourceDescriptor`, `DriverDescriptor` | `registry` | 그대로 재사용 |
| 자동완성 | `SQLQueryCompletionAnalyzer` (SWT 기본 엔진), `SQLCompletionAnalyzer` (예전 엔진) | `model.sql` (SWT 의존 없음) | 분석 로직 재사용, 팝업 UI만 새로 작성. F단계에서 SWT 기본값과 같은 새 엔진을 연결("구현 현황 — F단계") |
| SQL 문장 분리 | `SQLScriptParser` | `model.sql` | 그대로 재사용 |
| DDL 보기, Script as | `DBPScriptObject` | `model` | 그대로 재사용 |
| 결과 내보내기 | `DataExporterCSV` 등 | `data.transfer` (SWT 의존 없음) | 엔진 재사용, 설정 화면만 새로 작성. E단계의 결과 표 내보내기는 표가 표시 문자열만 가지고 있어 Swing 번들에서 CSV를 직접 쓴다("구현 현황 — E단계"). XLSX는 F단계에서 `DataExporterXLSX`(`data.office`)를 그대로 쓴다("구현 현황 — F단계") |
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
  드라이버별 특수 설정이 범용 화면에서 되는 것과 안 되는 것은 "구현 현황 — D단계"의 "범용 화면에서 아직 못 하는 드라이버별 설정"에 정리했다.
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
| 피처 | `com.seizia.chromite.swing.feature` ([features/com.seizia.chromite.swing.feature](../../features/com.seizia.chromite.swing.feature)). 공개판 내장 드라이버 피처 `com.seizia.chromite.drivers.public.feature`를 SWT 제품과 같이 포함한다([drivers.md](drivers.md)) |
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
   모델 계층이 정상으로 떴는지 확인하는 용도다. (D단계부터 계산은 `MainFrame.describeWorkspace`에 있고, 연결을 추가·삭제하면 다시 센다.)
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
- 탐색기: 활성 프로젝트 전환, 객체 편집기, Script as, 필터. (Script as와 DDL 보기는 E단계에서 구현했다)
  (연결 화면·연결 명령과 모델 이벤트로 트리 갱신은 D단계에서 구현했다. "구현 현황 — D단계")
- 위 "DBPPlatformUI B단계 구현"의 남은 항목.
- 도킹(끌어다 놓기, 떠 있는 창)이 필요해지면 ModernDocking 재검토.

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
- 색은 테마마다 RSyntaxTextArea 테마 XML(`SqlEditorLight.xml`: SSMS풍 — 파란 키워드, 자홍 함수, 빨간 문자열, 초록 주석, 회색 연산자 /
  `SqlEditorDark.xml`: DataGrip풍 — `#1e1f22` 바탕)로 정한다. 색값만 참고했고 SSMS·DataGrip의 테마 파일은 쓰지 않았다.
- 테마 전환: Look and Feel이 바뀌면(`updateUI`) 트리 갱신이 끝난 뒤(`invokeLater`) 현재 테마의 XML을 다시 적용한다. 거터(줄 번호) 색도 테마가 정하므로
  처음 적용은 스크롤 창을 만든 뒤에 한다.
- 구문 강조는 RSyntaxTextArea의 일반 SQL 규칙이다. DBeaver의 방언별 키워드는 아직 연결하지 않았다. (자동완성은 F단계에서 연결했다, "구현 현황 — F단계")

### 실행 흐름 (F5)

1. **범위:** 선택이 1자 이상이면 선택, 아니면 전체 텍스트("쿼리 실행 사양"). 공백뿐이면 실행하지 않는다.
   범위는 전체 텍스트 안의 위치로 넘기므로 쿼리의 위치가 편집기 위치와 같다(오류 줄 번호 계산에 씀).
2. **준비 Job (`AbstractJob`):** 연결이 없으면 `DBUtils.initDataSource`(→ `SwingConnectionService`)로 연결한다. 실행 문맥은 연결의 기본(주) 문맥
   (`DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false)`)이다. 그다음 `SQLScriptBatches.extractQueries`로 나눈다.
3. **실행 Job:** `SQLQueryExecuteJob`을 만들어 예약한다(`runDirectly`가 아니라 예약하는 것은 `DataSourceJob`의 연결 잡기 `acquire/release`를 그대로 쓰기 위해서).
   EDT는 기다리지 않는다. 실행 중에는 상태 줄의 경과 시간이 0.25초마다 바뀐다.
4. **취소:** 편집기 도구 막대의 Cancel 또는 Query > Cancel Executing Query(Alt+Break). 지금 도는 Job(준비 또는 실행)을 취소한다.
   준비 Job이 실행 Job을 예약하는 순간과 겹치지 않도록 같은 잠금 안에서 취소 표시를 확인하고 예약한다. 실행 Job의 취소는 `AbstractJob`이 실행 중인 문장을 취소한다.
5. **끝:** 실행 Job의 `done` 이벤트(또는 준비 단계의 조기 종료)에서 EDT로 상태를 마무리한다. 실행마다 `Execution` 객체를 두어, 끝난 실행의 늦은 결과가 다음 실행 화면에 섞이지 않게 한다.

- **커밋·오류 처리·최대 행 수:** 엔진 기본값을 쓴다. 커밋 방식 `NO_COMMIT`(연결의 자동 커밋 설정을 그대로 따름), 오류 처리 `STOP_ROLLBACK`,
  최대 행 수는 연결의 `ModelPreferences.RESULT_SET_MAX_ROWS`(기본 200).
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
- SQL Server인지 이름으로 검사하지 않고 구분자 유무로 판단해 같은 구분자를 쓰는 방언이 같은 경로를 쓴다.
  UI와 무관하고 단위 테스트할 수 있도록 실행 엔진 옆(`model.sql`의 `com.seizia.chromite.model.sql.exec`)에 두었다.
  파서 문맥은 SWT `SQLEditorBase`와 같이 만든다(연결의 설정 저장소, `loadRules(연결, false)`, `SQLPartitionScanner` 문서 분할기).

### Results / Messages

- SSMS처럼 결과는 쿼리 창마다 따로 있다. 메인 창 아래의 Results / Messages 탭에는 활성 편집기의 두 컴포넌트를 끼운다(편집기가 없으면 자리 표시).
  탭에서 빠져 있던 컴포넌트는 테마 전환을 받지 못했을 수 있어 끼울 때 Look and Feel을 맞춘다(`updateComponentTreeUI`, 마지막으로 맞춘 Look and Feel을 기억).
- **Results:** 결과 집합마다 표 하나를 위아래로 쌓는다. 모두 최소 높이(140px) 이상으로 들어가면 높이를 똑같이 나눠 채우고, 아니면 바깥 세로 스크롤.
  - 값은 `ResultSetGridReceiver`가 Job 스레드에서 결과 집합이 열려 있는 동안 읽어(`DBUtils.getAttributeBindings`, `fetchValueObject`) 표시 문자열(`DBDDisplayFormat.UI`)로 바꾸고
    값을 놓아 준다(`DBUtils.releaseValue`). EDT는 문자열만 다룬다. 행은 모아 두었다가 `fetchEnd`에서 한 번에 넘긴다(실패로 `fetchEnd`가 없으면 `close`에서).
  - 열 이름은 바인딩 이름(열 별칭), NULL은 흐린 기울임꼴 "NULL", 숫자 열(`DBPDataKind.NUMERIC`)은 오른쪽 정렬, 열 너비는 머리글과 앞 200행 글자 너비(40–320px),
    왼쪽에 행 번호 머리글(누르면 그 행 선택), 칸 선택과 Ctrl+C(탭으로 나눈 표시 문자열) 복사. 편집은 아직 없다.
- **Messages:** 쿼리마다 경고(JDBC `SQLWarning` — SQL Server `PRINT`, PostgreSQL `NOTICE` 등), `(N rows affected)`(결과 집합은 읽은 행 수, 최대 행 수에 닿으면 그 사실도),
  `Execution time: N ms`를 쓴다. 오류는 빨간색으로 `Msg <오류 코드>, State <SQLSTATE>, Line L, Column C`와 예외 메시지를 쓴다.
  위치는 방언의 `DBPErrorAssistant.getErrorPosition`이 찾으면 그 위치(편집기 기준 줄·열), 못 찾으면 쿼리 시작 줄이다. 끝에 `Completion time`.
- **탭 전환:** 오류가 있거나 결과 표가 하나도 없으면 SSMS처럼 Messages 탭을, 아니면 Results 탭을 보여 주고, Results 도구 창이 숨어 있으면 다시 보인다.
  편집기마다 마지막으로 본 탭을 기억해 편집기 탭을 바꾸면 그 탭을 다시 보여 준다.
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
- Swing 번들의 `Require-Bundle`에 `org.jkiss.dbeaver.model.sql`(실행 엔진, 파서)을 더했다. 이미 런타임 피처에 들어 있는 번들이다.

### 확인

- 빌드: `-Pproduct-dbeaver-ce,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 129개, macOS arm64 130개(B단계와 같음,
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
  (`test/org.jkiss.dbeaver.test.platform`의 `SQLScriptBatchesTest`: `GO` 사이 문장 합치기와 원문 범위, `GO 2` 반복, 빈 배치 건너뛰기, 배치 구분자 없는 방언).
  `mvn verify -Pproduct-dbeaver-ce -Dtest=SQLScriptBatchesTest`로 실행해 3개 모두 통과했다.
  SQL Server 방언은 `DECLARE`를 블록 머리로 보아 `DECLARE …; SELECT …;`를 파서가 이미 쿼리 하나로 돌려주고, 일반 문장 두 개(`SELECT 2; SELECT 3`)는 이 클래스가 합친다.
- macOS 실행은 확인하지 못했다.

### C단계에서 남긴 것 (D단계 이후)

- 편집기: 파일 열기·저장(닫을 때 저장 확인, E단계에서 구현), 방언별 키워드·자동완성(자동완성은 F단계에서 구현), 실행한 문장 강조, 오류 위치로 이동(Messages 더블클릭).
- 실행: 편집기별 별도 연결(SSMS는 쿼리 창마다 연결), 오류 뒤 다음 배치 계속 실행(SSMS 기본 동작. 지금은 멈춤), 커밋 방식·트랜잭션 표시, 서버 출력 읽기
  (`DBCServerOutputReader`, Oracle `DBMS_OUTPUT`), 매개변수 입력 대화상자, `@set` 변수의 편집기 단위 유지.
- 결과: 편집(Edit Top 200 Rows, "결과 모델 분석"), 더 읽기(최대 행 수 이후), 정렬, 결과 내보내기(CSV는 E단계에서 구현), 텍스트로 결과 보기.
- SQL Server `GO` 배치는 SQL Server로 실행해 보지 못했다(아래 확인 참고).

## 구현 현황 — D단계

D단계의 목표는 Swing 앱에서 연결을 만들고, 고치고, 지우고, 연결하고, 끊는 것이다(SSMS "Connect to Server"). MVP 2번
"범용 연결 화면 하나로 전체 드라이버를 지원"을 구현했다.

### 구성

| 클래스 | 역할 |
|---|---|
| `ConnectionDialog` | 연결 만들기(드라이버 고르기 → 설정 2단계)·편집 대화상자. Test Connection, Save, 이름 만들기 |
| `DriverSelectionPanel` | 1단계: 검색 칸, 범주 콤보, 아이콘이 있는 드라이버 목록 |
| `ConnectionSettingsPanel` | 2단계: 드라이버 정의로 구성하는 범용 설정 화면(Main / Driver Properties / Provider Properties 탭) |
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
  [DataSourceRegistry.java:558-566, 626-636](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/DataSourceRegistry.java)) `OBJECT_ADD` 이벤트를 보낸다.
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
  다시 연결은 끊기가 끝난 뒤 연결. SWT가 끊기 전에 하던 열린 편집기 저장 확인과 진행 중 트랜잭션 확인은 아직 없다.

### 드라이버 파일 내려받기 (`SwingDriversService`)

- 드라이버를 처음 쓸 때(연결, Test Connection, Driver Properties 읽기) `DriverLoaderDescriptor.downloadDriverLibraries`가 `UIServiceDrivers`를 부른다
  ([DriverLoaderDescriptor.java:414-428](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverLoaderDescriptor.java)).
  지시서에는 `DBPPlatformUI`의 메서드로 적혀 있었지만 실제 호출 지점은 이 서비스라서, `SwingConnectionService`처럼 `org.jkiss.dbeaver.service` 확장으로 등록했다.
  SWT 제품은 `org.jkiss.dbeaver.core`의 `UIServiceDriversImpl`(`DriverDownloadDialog`)이다. Swing 제품에는 이 서비스가 없어서 지금까지는 묻지 않고 바로 내려받았다.
- 없는 라이브러리 이름(이 OS용, 끄지 않은 필수 라이브러리 중 로컬 파일이 없는 것)을 보여 주고 Download / Cancel을 묻는다. Download면 모델의 `DriverUtils.downloadDriverFiles`를
  모달 진행 대화상자(Cancel 가능)에서 돌리고 호출한 Job은 기다린다. Cancel이면 모델이 드라이버 클래스를 찾지 못했다는 오류를 낸다.
- 오프라인 모드(`ui.drivers.offline`, [drivers.md](drivers.md))에서는 모델이 이 서비스를 부르지 않고 로컬 파일만 찾은 뒤, 없으면 이유를 설명하는 오류를 낸다.
  그 오류는 Test Connection 오류 대화상자나 Driver Properties 표 아래에 그대로 보인다. 버전 선택과 의존성 트리 보기는 없다.

### 탐색기 모델 이벤트

B단계에서 남겼던 "모델 이벤트로 트리 갱신"을 구현했다. 다른 곳(연결 대화상자, 모델 Job, 다른 창)에서 일어난 변경이 다시 시작하지 않아도 보인다.

- `ObjectExplorerPanel`이 `DBNModel.addListener(INavigatorListener)`로 듣는다. 모델은 이벤트를 모아 약 100ms마다 `DBPPlatformUI.executeInMainThread`(= EDT)로 보낸다
  ([DBNModel.java:602-636](../../plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/model/navigator/DBNModel.java)). 연결 레지스트리 이벤트는 `DBNProjectDatabases`가
  탐색기 이벤트로 바꿔 준다([DBNProjectDatabases.java:351-470](../../plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/model/navigator/DBNProjectDatabases.java)).
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

### 확인 (2026-11-06)

- 빌드: `-Pproduct-dbeaver-ce,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 129개, macOS arm64 130개(C단계와 같음)이고,
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
  둘을 `org.jkiss.dbeaver.ce.feature`에서 전용 피처 `com.seizia.chromite.drivers.public.feature`로 옮기고 Swing 피처도 포함하게 했다(jar 목록은 그 피처 한곳).
- 확인: `-Pproduct-dbeaver-ce,product-chromite-swing` BUILD SUCCESS. Swing 결과물 Windows `chromite-swing/drivers/`, macOS `ChromiteSwing.app/Contents/Eclipse/drivers/`의
  파일 34개(18,917,823 bytes)가 SWT 제품의 `drivers/`와 이름·크기·SHA-256이 모두 같고, SWT 제품의 `drivers/`도 바꾸기 전과 같다.
  `com.seizia.chromite.drivers.public` 번들이 `bundles.info`에 들어가 번들 수는 Windows x64 130개, macOS arm64 131개이며, `swt`·`jface`·`org.eclipse.ui`·`org.eclipse.e4.ui` 번들은 여전히 0개다.
- Windows in-process JVM 실행(C단계 작업 공간 사본): New Connection → PostgreSQL → Driver Properties 탭이 내려받기 확인 없이 pgjdbc 속성(adaptiveFetch 등)을 바로 보였고,
  Test Connection은 pgjdbc의 "Connection refused"(localhost:5432, 서버 없음)로 끝났다. 사용자 드라이버 폴더(`DBeaverData/drivers`)에는 PostgreSQL jar가 없으므로 설치 폴더의 `drivers/postgresql`에서 불러온 것이다.

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

- **연결 콤보:** 편집기 도구 막대(Execute, Cancel 오른쪽)에 활성 프로젝트의 연결 목록(숨김·임시 연결 제외, 이름순, 드라이버 아이콘)을 보여 준다.
  고르면 그 편집기의 연결이 바뀌고 탭 제목이 바뀐다. 연결은 C단계처럼 실행할 때 필요하면 연다. 목록은 콤보를 열 때마다 다시 채워 연결 추가·삭제·이름 변경을 반영한다.
  실행 중에는 콤보를 끈다(실행 중인 Job은 시작할 때의 연결을 쓴다).
- **기본 DB/스키마 콤보 (SSMS "Available Databases"):** SWT의 활성 스키마 선택(`SelectActiveSchemaHandler`)과 같은 모델 API를 쓴다.
  - 목록과 현재 값: 모델의 `ContextDefaultObjectsReader`(`org.jkiss.dbeaver.model.impl.struct`)를 작업 스레드에서 돌린다. 주 자식 형식이 카탈로그면 카탈로그 목록
    (카탈로그 아래 스키마가 있고 스키마를 바꿀 수 있으면 현재 카탈로그의 스키마 목록), 스키마면 스키마 목록이다. SWT처럼 `DBSObjectContainer`인 것만 보이고,
    기본 객체 또는 그 부모를 고른다.
  - 바꾸기: `DBExecUtils.setExecutionContextDefaults(monitor, 데이터 소스, 문맥, 새 카탈로그, 현재 카탈로그, 새 스키마)`를 SWT와 같은 인자로 부른다
    (카탈로그를 고르면 새 카탈로그 = 그 이름, 스키마를 고르면 새 카탈로그 = 현재 카탈로그·새 스키마 = 그 이름). 실패하면 오류 대화상자를 띄우고 다시 읽는다.
  - 실행 문맥이 연결의 기본(주) 문맥이므로 바꾼 기본값은 같은 연결의 다른 편집기에도 적용된다(편집기별 별도 연결은 아직 없음).
  - 연결되어 있지 않거나, 문맥에 기본값 기능(`DBCExecutionContextDefaults`)이 없거나, 카탈로그·스키마를 바꿀 수 없으면 `< N/A >`로 끈다. SQLite가 그렇다.
  - 다시 읽는 때: 편집기를 열 때, 연결을 바꿀 때, 실행이 끝날 때(연결이 열렸거나 `USE`로 바뀌었을 수 있음), 연결 레지스트리 이벤트(연결, 끊기, 이름 바꾸기).
  - SWT가 하는 것 중 안 한 것: 트랜잭션 모드에서 기본값 변경이 트랜잭션에 속하는 DB(`isDefaultsChangeTransactional`)의 커밋 확인, 다른 인스턴스(카탈로그)로 넘어가 스키마 고르기.
- **연결 변경 알림:** `MainFrame`이 이미 듣던 연결 레지스트리 이벤트 중 `OBJECT_UPDATE`(연결, 끊기, 설정 저장·이름 바꾸기)를 그 연결을 쓰는 편집기에 알려
  탭 제목과 기본 스키마 콤보를 갱신한다. D단계에서 남긴 "연결 이름을 바꿔도 탭 제목이 바뀌지 않음"이 이것으로 해결되었다.

### Script as, Select Top 1000 Rows, View DDL (`ObjectScripts`)

SWT의 "Generate SQL" 메뉴가 쓰는 모델 계층 생성기(`org.jkiss.dbeaver.model.sql.generator`, UI 의존 없음)를 그대로 쓴다. 모두 DB 메타데이터를 읽으므로
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
- **`DataExporterCSV`(data.transfer)를 쓰지 않은 이유:** `data.transfer` 번들은 Swing 제품에 들어 있지만(`org.jkiss.dbeaver.runtime.feature`), 이 내보내기는 원래 값(`Object[]`),
  열 바인딩(`DBDAttributeBinding[]`), LOB 읽기용 열린 세션(`exportRow(DBCSession, …)`)이 필요하다. Swing 결과 표는 C단계 설계대로 작업 스레드에서 값을 표시 문자열로 바꾸고
  원래 값을 놓아 주므로(`ResultSetGridReceiver`) 그 입력이 없다. 원래 값을 결과 표에 계속 들고 있게 바꾸는 것은 LOB·세션 수명 문제가 커서, 화면에 보이는 값을 그대로 쓰는 쪽을 골랐다.
  그래서 숫자·날짜는 화면 표시 형식이다(숫자 묶음 기호는 기본 설정에서 꺼져 있음, `DataFormatterProfile`).
- **XLSX 내보내기: 보류(F단계에서 구현, "구현 현황 — F단계").** 모델의 XLSX 내보내기(`DataExporterXLSX`, `org.jkiss.dbeaver.data.office`)는 SWT 의존이 없지만, 그 번들과 Apache POI 번들(`org.jkiss.bundle.apache.poi`)은
  `org.jkiss.dbeaver.ext.office.feature`에만 있고 Swing 피처(`com.seizia.chromite.swing.feature`)에는 없다. 피처를 바꾸는 작업은 이번 범위(Swing 플러그인) 밖이고,
  위와 같은 이유로 원래 값도 필요하다. 메뉴에 넣지 않았다.

### 확인

- 빌드: `-Pproduct-dbeaver-ce,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 129개, macOS arm64 130개(D단계와 같음)이고,
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
- 편집기별 별도 연결(SSMS는 쿼리 창마다 연결) — 기본 스키마가 같은 연결의 모든 편집기에 적용되는 원인.
- 파일: 다른 프로그램이 바꾼 파일 다시 읽기, 문자 집합 직접 고르기(Save with Encoding), 파일 끌어다 놓기, 프로젝트 `Scripts` 폴더 연동.
- Script as의 DROP / ALTER / INSERT / UPDATE / DELETE, Edit Top 200 Rows(결과 편집), 생성기 옵션 설정 화면.

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
| `com.seizia.chromite.swing.feature` (변경) | `org.jkiss.dbeaver.data.office`, `org.jkiss.bundle.apache.poi`와 Apache Commons 번들 6개 |

### 자동완성: 분석기 선택 근거 (SWT 편집기 조사)

- **SWT가 쓰는 분석기:** `SQLCompletionProcessor.computeCompletionProposals`([SQLCompletionProcessor.java:177-233](../../plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/syntax/SQLCompletionProcessor.java))는
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
- **안 한 것:** 스크립트 변수(`@set`) 추적(편집기 단위 변수 유지가 아직 없음), 제어 명령(`@`) 이름 제안, 템플릿, 제안 설명 창(설명은 목록 도구 설명으로만 보임).

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

- **번들:** `org.jkiss.dbeaver.data.office`(`DataExporterXLSX`, SWT 의존 없음, `Require-Bundle`은 `data.transfer`와 POI뿐)와 `org.jkiss.bundle.apache.poi` 5.4.3
  (POI 5.4.0과 의존 jar를 담은 DBeaver 번들, `Require-Bundle`은 Apache Commons 6개, `Import-Package`는 `org.slf4j`)를 Swing 피처에 넣었다.
  두 번들은 Office 확장 피처 `org.jkiss.dbeaver.ext.office.feature`에만 있고, 이 피처는 SWT 제품에 들어 있지 않고 CE 업데이트 저장소(`org.jkiss.dbeaver.ce.repository/category.xml`)에서 설치하는 확장이다.
  피처의 나머지 번들 `org.jkiss.dbeaver.data.office.ui`(SWT)는 넣지 않았다. Swing 번들은 `data.transfer`·`data.office`를 `Require-Bundle`로 쓴다.
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

- 빌드: `-Pproduct-dbeaver-ce,product-chromite-swing` BUILD SUCCESS. Swing 결과물 번들 수는 Windows x64 137개, macOS arm64 138개(E단계 130/131 + 7)이고,
  이름에 `swt`, `jface`, `org.eclipse.ui`, `org.eclipse.e4.ui`가 들어간 번들은 둘 다 0개다. 새 번들: `org.jkiss.dbeaver.data.office`, `org.jkiss.bundle.apache.poi` 5.4.3,
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
