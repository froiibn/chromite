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

도입 전에 각 라이브러리의 최신 라이선스를 다시 확인한다.

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

## 변경 이력

- 2026-11-06 최초 작성. Swing 전환 결정, 구조, 재사용 분석, MVP 범위, F5 실행 사양을 정리.
- 2026-11-06 쿼리 실행 계층 분리: `SQLQueryJob`의 실행 로직을 `model.sql`의 `SQLQueryExecuteJob`·`SQLQueryExecuteHandler`로 옮기고,
  Swing 편집기 API, F5·GO 배치 연결 방법, `ResultSetModel`·`ResultSetPersister` 이전 분석을 추가.
