# Swing SQL 편집기 확장: 실행 계획, 쿼리 기록, 서식, 템플릿, 매개변수

Swing 프런트엔드([swing-frontend.md](swing-frontend.md))의 SQL 편집기(쿼리 창)에 DBeaver SWT SQL 편집기
(`com.seizia.chromite.ui.editors.sql`)의 기능을 SQL Server 편집기 사용 방식(SSMS 단축키·화면 배치)으로 옮긴 작업이다.
화면 문구에는 다른 제품 이름을 쓰지 않는다.

## 목적

- 실행 계획: 예상 계획(Ctrl+L)과 실행하면서 받는 실제 계획(Ctrl+M 켜고 실행)을 결과 옆 **Execution plan** 탭에서 본다.
  탭 안에서 그래픽(연산자 그림) · 트리(속성 표) · 원본(서버가 돌려준 글자) 세 보기로 바꿔 본다(아래 "실행 계획 세 보기").
- 쿼리 기록: 쿼리 창에서 실행한 모든 문장을 남기고(재시작 뒤에도), **Query > Query History…** 창에서 찾고 다시 열거나 실행한다.
- SQL 서식과 편집 명령: Format SQL, 대문자·소문자, 주석 넣기·빼기, 들여쓰기.
- 템플릿: 이름 + Tab(`sel` + Tab), Ctrl+K Ctrl+X 목록, Tools > Options > Templates에서 편집.
- 매개변수: `:name`, `${var}`가 있는 문장을 실행하면 값을 묻는다.
- 상태 줄: 서버, 사용자, DB, 경과 시간, 행 수(이미 있음)에 커서 위치(Ln, Col)를 더한다.

## 구조

| 파일 | 역할 |
|---|---|
| `plugins/com.seizia.chromite.model.sql/src/com/seizia/chromite/model/sql/exec/SQLActualPlanProvider.java` | 실행하면서 실제 계획을 받는 연결의 어댑터. 실행마다 `startRun`이 세션에서 켜고 `Run`이 문장마다 계획을 읽고(결과 집합, 열린 문장, 닫은 문장) `finish`가 되돌림 |
| `plugins/com.seizia.chromite.model.sql/.../exec/SQLQueryExecuteHandler.java`, `SQLQueryExecuteJob.java` | `afterStatementExecuted`: 문장이 오류 없이 끝나고 결과를 다 읽은 뒤, 닫기 전에 부름(Altibase 실제 계획) |
| `plugins/com.seizia.chromite.ext.mssql/src/com/seizia/chromite/ext/mssql/model/plan/SQLServerQueryPlanner.java` | SQL Server 계획기: `DBCQueryPlanner`(예상, `SET SHOWPLAN_XML`) + `SQLActualPlanProvider`(실제, `SET STATISTICS XML`) |
| `plugins/com.seizia.chromite.ext.oracle/.../plan/OracleActualPlanProvider.java`, `OracleActualPlanNode.java` | Oracle 실제 계획: 세션 `statistics_level = ALL`, 문장마다 커서의 `V$SQL_PLAN_STATISTICS_ALL` 행과 `DISPLAY_CURSOR('ALLSTATS LAST')` 글자. 노드는 실행 통계(Starts, A-Rows, A-Time, Buffers, Reads)를 더한 하위 클래스 |
| `plugins/com.seizia.chromite.ext.altibase/.../plan/AltibaseActualPlanProvider.java` | Altibase 실제 계획: 드라이버 `setExplainPlan(ON)`, 열린 문장의 `getExplainPlan()` 글자 |
| `.../plan/SQLServerExecutionPlan.java`, `SQLServerPlanNode.java` | showplan XML을 DBeaver 계획 모델(`DBCPlan`, `DBCPlanCostNode`)로 바꿈 |
| `plugins/com.seizia.chromite.ext.mssql/.../model/SQLServerDataSource.java` | `getAdapter`에서 위 계획기를 돌려줌(Babelfish 제외) |
| `plugins/com.seizia.chromite.registry/.../BasePlatformImpl.java` | `getLocalConfigurationFile`이 작업 공간 설정 폴더를 만듦(매개변수 값 저장) |
| `plugins/com.seizia.chromite.ui.swing/.../ExecutionPlanView.java` | Execution plan 탭: 보기 전환 도구 막대, 문장별 계획 트리 표 + 노드 속성 표, 그래픽·원본 보기를 담음 |
| `.../PlanDiagram.java`, `PlanDiagramView.java` | 그래픽 계획: 계획 모델을 모르는 그림 내용(문장, 연산자 상자)과 그것을 배치·그리는 컴포넌트(확대, 도구 설명, 선택) |
| `.../PlanDiagramBuilder.java` | 계획 모델(`DBCPlan`)을 그림 내용으로: DB별 비용 %, 예상·실제 행, 경고, 연산자 아이콘 |
| `.../PlanRawView.java` | 원본 보기: 계획 원문(읽기 전용, XML·JSON 구문 색), 복사, 다른 이름으로 저장 |
| `.../internal/PlanMessages*.properties` | 세 보기의 화면 문구(영어, 한국어) |
| `plugins/com.seizia.chromite.ext.oracle/.../plan/OracleExecutionPlan.java` | `DBMS_XPLAN.DISPLAY` 글자를 계획 원문으로 남김 |
| `.../SqlPlanExplainer.java` | 계획기로 문장들의 예상 계획을 구하는 Job |
| `.../ActualPlanReceiver.java` | 실행 중 계획 결과 집합을 계획으로, 나머지는 결과 표로 보내는 수신기 |
| `.../QueryHistory.java`, `QueryHistoryDialog.java` | 쿼리 기록 저장소(작업 공간 `.metadata/chromite-query-history.jsonl`)와 Query History 창 |
| `.../SqlEditCommands.java` | Format SQL, 대소문자, 주석, 들여쓰기, Edit > Advanced 메뉴, 서식 설정 |
| `.../SqlTemplates.java`, `SqlTemplatesPage.java` | 템플릿 저장소·넣기와 Options의 Templates 탭 |
| `.../QueryParameterDialog.java`, `QueryParameterBinder.java` | 매개변수 값 대화상자(스크립트 모드)와 엔진의 매개변수 제공자(`QueryParameterBinder.ScriptProvider`). 2026-11-08에 Query Library와 합침(처음에는 `SqlParameterDialog.java`) |
| `SqlEditorPanel`, `MainFrame`, `OptionsDialog`, `QueryStatusLine`, `SqlCompletionPopup` | 위 기능을 잇는 작은 연결 지점(아래 "upstream·기존 코드 대비 변경점") |
| `tools/branding/gen_ui_icons.py`, `icons/ui/*/*/plan.svg`, `history.svg` | 실행 계획·쿼리 기록 아이콘(생성기에 추가, 두 팩 × 두 테마) |
| `icons/ui/*/*/plan-*.svg`, `copy.svg` | 그래픽 계획의 연산자 아이콘 17개와 원본 보기의 복사 아이콘(같은 생성기) |

## 단축키와 메뉴

| 기능 | 키 | 메뉴 |
|---|---|---|
| 예상 실행 계획 | Ctrl+L | Query > Display Estimated Execution Plan, 메인 창 SQL Editor 도구 막대의 계획 단추 |
| 실제 실행 계획 포함 | Ctrl+M (macOS ⇧⌘M, 쿼리 창마다 켜고 끔) | Query > Include Actual Execution Plan, SQL Editor 도구 막대의 Actual Plan 토글(메뉴와 같은 동작, 활성 쿼리 창의 값을 보임) |
| 쿼리 기록 | - | Query > Query History… |
| SQL 서식 | Ctrl+K Ctrl+D, Ctrl+Shift+F | Edit > Advanced > Format SQL |
| 대문자 / 소문자 | Ctrl+Shift+U / Ctrl+Shift+L | Edit > Advanced > Make Uppercase / Make Lowercase |
| 주석 넣기 / 빼기 | Ctrl+K Ctrl+C / Ctrl+K Ctrl+U | Edit > Advanced > Comment / Uncomment Selection |
| 들여쓰기 / 내어쓰기 | Tab / Shift+Tab (여러 줄 선택) | Edit > Advanced > Increase / Decrease Indent |
| 템플릿 | 이름 + Tab, Ctrl+K Ctrl+X(목록) | Edit > Advanced > Insert Template… |

Ctrl+K는 RSyntaxTextArea 기본값(다음 같은 단어로 이동)이었는데 SQL Server 편집기와 같이 두 번 누르는 키의 앞 키로 바꿨다.

## 설계 결정과 이유

### 실행 계획

- **계획 모델은 DBeaver 것을 그대로 쓴다.** SWT 편집기는 연결의 `DBCQueryPlanner` 어댑터로 계획을 구하고(`SQLEditor.explainQueryPlan`,
  `ExplainPlanViewer.ExplainPlanService.evaluate`) 단순 보기(`PlanNodesTree`)가 노드 클래스의 `@Property(viewable = true)`를 열로 보인다.
  Swing의 `SqlPlanExplainer`와 `ExecutionPlanView`는 이 순서와 열 결정 방법을 그대로 따른다(UTIL 세션, `DBExecUtils.tryExecuteRecover`,
  `OPTION_KEEP_ORIGINAL`, TABLE_SCAN 오류 색·INDEX_SCAN 경고 색). 그래서 PostgreSQL(`PostgreQueryPlaner`, `EXPLAIN (FORMAT XML)`)과
  MySQL/MariaDB(`MySQLPlanAnalyser`, `EXPLAIN FORMAT=JSON`)는 새 코드 없이 같은 화면에 나온다. 계획기 설정 대화상자
  (`DBEObjectConfigurator`, SWT UI 플러그인)는 쓰지 않고 기본 설정으로 계획한다.
- **SQL Server 계획기를 모델 플러그인에 추가했다.** DBeaver CE의 `ext.mssql`에는 계획기가 없다. UI 번들에서 SQL을 실행하지 않는다는
  아키텍처 규칙(AGENTS-Architecture.md)에 따라 Swing 번들이 아니라 `ext.mssql`에 Chromite 패키지로 넣고, 다른 DB와 같이 `getAdapter`로 노출했다.
  SWT 편집기에서도 SQL Server 계획을 볼 수 있게 된다.
- **예상 계획(SQL Server)**: `SET SHOWPLAN_XML ON` → 문장 실행(서버는 실행하지 않고 계획 XML 한 행을 돌려줌) → `SET SHOWPLAN_XML OFF`(오류가 나도 실행).
  SET 문은 일괄 처리에 혼자 있어야 해서 따로 실행한다. 비용 %는 연산자 자신의 비용(하위 트리 비용 - 자식 하위 트리 비용) / 문장 비용으로,
  SQL Server 계획 도구의 "Cost: N %"와 같은 정의다. 문장 행의 %는 일괄 처리 대비다. `GO` 배치 하나는 계획 하나이고 그 아래에 문장마다
  SELECT 등의 문장 노드가 있다(머리말의 문장 수는 문장 노드 수).
- **실제 계획은 문장을 한 번만 실행하면서 받는다(SSMS와 같음).** 어댑터 `SQLActualPlanProvider`가 있는 연결(SQL Server, Oracle, Altibase)만
  토글을 켤 수 있다. 편집기는 실행 직전에 창의 실행 문맥(같은 JDBC 연결)에서 `startRun`으로 켜고, 실행 Job이 끝나면(오류·취소 포함) `finish`로 되돌린다.
  `DBCQueryPlanner`는 "계획만 따로 구하기" API라 이 방식을 나타낼 수 없어서 어댑터를 따로 만들었다. DB별로 계획을 받는 때가 다르다
  (아래 "실제 계획 토글을 Altibase·Oracle로").
  - SQL Server: `SET STATISTICS XML ON` / `OFF`. 서버가 문장마다 계획 XML 결과 집합을 하나 더 돌려준다. 엔진은 열 정보를 `fetchStart`에서야 알려 주므로
    `ActualPlanReceiver`가 먼저 받아 열 이름이 `Microsoft SQL Server 2005 XML Showplan`이면 원래 값(XML 전체)을 읽어 계획으로, 아니면 결과 표 수신기에
    그대로 넘긴다. 계획마다 "Query N" 행의 글자는 계획 XML의 `StatementText`(배치 전체가 아니라 그 문장)이고, 계획 결과 집합은 Messages의 행 수에 넣지 않는다.
  - 어댑터가 없는 연결(PostgreSQL, MySQL/MariaDB 등): 토글이 꺼지고 도구 설명이 이유를 말한다. 처음에는 실행이 끝난 뒤 성공한 읽기 SELECT를 계획기의
    `ANALYZE`(PostgreSQL `EXPLAIN (ANALYZE)`)로 **다시 실행**해 보였는데, 2026-11-08 사용자 결정으로 지웠다(문장을 두 번 실행하는 실제 계획은 따로 설계).
- **탭 배치**: 메인 창의 Results / Messages 옆에 Execution plan 탭을 늘 둔다(SQL Server 도구는 계획이 있을 때만 탭을 보이지만,
  탭을 넣고 빼면 `MainFrame`의 탭 번호 처리가 늘어 다른 작업과 겹치기 쉬워 고정 탭으로 했다). 쿼리 창마다 마지막으로 본 탭을 기억한다.
  Ctrl+L은 계획 탭을 앞으로 가져오고, 실제 계획은 결과를 먼저 보인다(계획 탭은 눌러서 본다).
- **그래픽 계획 도면은 처음에 미뤘다가 2026-11-07에 더했다**(아래 "실행 계획 세 보기").
- **열 너비는 내용에 맞춘다.** 처음에는 모든 열을 고정 비율(연산 320, 나머지 110)로 보기 안에 밀어 넣어(`AUTO_RESIZE_SUBSEQUENT_COLUMNS`)
  속성 열이 많은 SQL Server 계획에서 연산 이름이 잘렸다. 계획이 들어올 때 머리글과 보이는 행을 표의 렌더러로 재어(들여쓰기 포함) 맞추고
  (연산 열 200~700px, 속성 열 60~360px, SWT `PlanNodesTree`의 상위 `ObjectListControl.java:520-541`이 내용을 채운 뒤 열을 다시 맞추는 것과 같은 의도),
  열이 보기보다 좁으면 보기를 채우고 넓으면 가로로 스크롤한다(`getScrollableTracksViewportWidth`). 연산 열은 계획 노드만 잰다:
  "Query N" 행은 SQL 글이라 길고, 전체는 오른쪽 상세 표에 보인다.

### 실행 계획 세 보기 (2026-11-07)

사용자 결정: 계획 탭에 세 보기를 두고 탭 자체의 작은 도구 막대에 있는 분할 단추로 바꾼다.

| 보기 | 내용 |
|---|---|
| **그래픽** (기본) | SQL Server 계획 도구 같은 연산자 그림. 데이터는 오른쪽(잎)에서 왼쪽(뿌리 SELECT)으로 흐르고, 화살표 굵기는 행 수, 상자는 아이콘·연산·개체·비용 % |
| **트리** | 기존 트리 표(위 설명 그대로) |
| **원본** | 서버가 돌려준 계획 글자 그대로(읽기 전용, 고정폭, XML·JSON 구문 색), 복사, 다른 이름으로 저장 |

- **배치(도구 막대):** `[그래픽 | 트리 | 원본]` 분할 단추(둥근 테두리 안 토글 단추 셋, FlatLaf에 분할 단추 형식이 없어 `ExecutionPlanView.SegmentedPanel`이 테두리를 그림),
  그다음에 보이는 보기의 도구(그래픽: 축소·배율(누르면 100%)·확대·창에 맞추기, 원본: 계획 목록·복사·다른 이름으로 저장), 오른쪽에 기존 머리말 글자
  ("예상 실행 계획 - 문 N개"). 그래픽·트리는 오른쪽 속성 표를 함께 쓰고, 원본은 탭 전체를 쓴다.
- **마지막 보기 기억:** 고른 보기를 이 번들의 사용자 설정 `plan.view`(`GRAPHICAL`·`TREE`·`RAW`)에 저장하고 새 쿼리 창의 계획 탭도 그 보기로 연다.
- **선택 연동:** 그림에서 상자를 누르면 트리의 같은 노드 행을 고르고(접힌 가지 안이면 속성만 보임), 트리에서 행을 고르면 그림의 상자도 선택된다.
  그래서 "상자를 누르면 오른쪽 속성 표에 전체 속성"이 트리 보기와 같은 코드(`showDetails`)로 된다. 문장 머리말을 누르면 문장 속성(문, 계획 쿼리, 예상/실제)이 보인다. 한 번에 계획한 일괄 처리(SQL Server는 스크립트 전체가 계획 하나이고 그 안에 문장 노드가 여럿)는
  머리말이 그 문장 노드를 고른다(모두 같은 계획을 고르면 모든 머리말이 함께 선택되어 보였다).
- **SQL 편집기는 바꾸지 않았다.** `SqlEditorPanel`은 `ExecutionPlanView`의 `clear`·`addEntry`·`getComponent`만 쓰므로 세 보기는 이 클래스 안에서 끝난다.
  `MainFrame`도 그대로다(탭에 끼우는 컴포넌트가 같음).

#### 그래픽 보기

- **라이브러리 없이 직접 배치한다.** 계획은 트리라서 배치가 간단하다: 깊이가 열(뿌리 문장이 왼쪽 첫 열), 부모는 첫 자식과 같은 줄, 다음 자식은 앞 자식의
  하위 트리 아래 줄부터. SQL Server 계획 도구와 같은 모양이고 선이 겹치지 않는다. JGraphX(BSD, 약 1.5MB, 개발 중단)나 JUNG 같은 그래프 라이브러리는
  일반 그래프 배치를 위한 것이라 이 트리에는 과하고, 테마 색·확대·도구 설명을 다시 맞춰야 해서 쓰지 않았다.
- **모델과 그림을 나눴다.** `PlanDiagramView`는 `PlanDiagram`(문장, 연산자 상자: 글자, 아이콘 id, 비용 %, 예상·실제 행, 경고, 도구 설명 줄)만 그리고,
  DB별 해석은 `PlanDiagramBuilder` 한곳에 있다. 새 DB를 지원할 때 그림 코드는 바뀌지 않는다.
- **상자:** 폭 168, 연산자 아이콘 32(16px 아이콘을 두 배로, 아이콘 팩·테마를 따름), 연산 이름(굵게), 개체(SQL Server는 DB·스키마를 뺀 `[테이블].[인덱스] [별칭]`,
  개체가 없는 조인은 논리 연산 `(Inner Join)`), `비용: N%`, 행(예상 계획 `N행`, 실제 계획 `실제 / 예상 (비율)`). 글자가 넘치면 …로 자르고 전체는 도구 설명과 속성 표에 있다.
- **비싼 연산자:** 문장 안에서 연산자 비용 %가 가장 큰 상자(1% 이상)는 오류 색(`Chromite.status.error`) 2px 테두리와 옅은 오류 색 바탕, 비용 글자도 오류 색 굵게.
  그 밖에 25% 이상인 상자는 경고 색 테두리.
- **경고 배지:** 서버가 계획에 적은 경고가 있으면 아이콘 오른쪽 아래에 경고 아이콘(`warning`)을 그리고, 도구 설명에 종류와 서버의 세부 내용을 보인다.
  - SQL Server(모델이 showplan에서 읽음, 아래 "모델 변경"): 암시적 변환(`PlanAffectingConvert`), 디스크 넘침(`SpillToTempDb`, `Sort/Hash/ExchangeSpillDetails`),
    조인 조건자 없음(`NoJoinPredicate`), 통계 없는 열(`ColumnsWithNoStatistics`), 메모리 부여(`MemoryGrantWarning`), 누락 인덱스(`MissingIndexes`), 그 밖의 경고 요소.
    문장 단위 경고(암시적 변환, 메모리 부여, 누락 인덱스)는 뿌리 SELECT 상자에 붙는다. 누락 인덱스는 문장 머리말에도 성공 색으로
    `누락 인덱스(영향 94): CREATE NONCLUSTERED INDEX … ON [dbo].[t] ([status], [amount])`를 보인다.
  - PostgreSQL(계획 속성으로 판단): 디스크 정렬(`Sort-Space-Type` = Disk), 해시 일괄 처리 증가(`Hash-Batches` &gt; 1), 해시 집계 디스크 사용(`Disk-Usage` &gt; 0).
    PostgreSQL 계획에는 암시적 변환·누락 인덱스 보고가 없다.
- **예상과 실제:** 실제 계획에서 상자의 행 줄은 `실제 / 예상 (비율)`이다. 두 값은 모두 "모든 실행" 합계다(SQL Server: 1회 예상 행 × 예상 실행 수
  (`1 + EstimateRebinds + EstimateRewinds`)와 스레드 합계 실제 행, PostgreSQL: 루프 1회 값 × `Actual-Loops`). 큰 값이 작은 값(1 이상)의 10배 이상이고
  차이가 100행 이상이면 행 줄을 경고 색 굵게 그리고 도구 설명에 "예상이 N배 어긋났습니다"를 더한다(몇 행짜리 차이는 표시하지 않음).
- **화살표:** 자식 상자 왼쪽에서 부모 상자 오른쪽으로(데이터 흐름 방향, 머리는 부모 쪽). 굵기는 그 연산자가 넘기는 행 수(실제 계획이면 실제, 아니면 예상)를
  문장 안 최대 행 수에 대한 로그 비율로 1~10px. 행 수 그대로 비례시키면 몇 행짜리 화살표는 보이지 않고 큰 화살표는 상자를 덮는다. 화살표에 마우스를 올리면 행 수가 보인다.
  자식이 여럿이면 부모에 들어가는 높이를 7px씩 내려 선이 합쳐지지 않게 한다.
- **여러 문장:** 문장마다 그림 하나를 위아래로 쌓고, 머리말은 트리와 같은 번호로 `쿼리 N: 쿼리 비용(일괄 처리 기준): X%`와 문장 첫 줄이다.
  일괄 처리 기준 비용은 탭의 모든 문장 비용(SQL Server 문장 하위 트리 비용, PostgreSQL 뿌리 Total-Cost, 그 밖은 뿌리 비용)의 합에 대한 비율이다.
  계획을 만들지 못한 문장은 머리말 아래에 오류 색으로 `계획 없음: 오류`를 보인다.
- **확대·이동:** 도구 막대 단추, Ctrl+휠(마우스 아래 점 고정), Ctrl+= / Ctrl+- / Ctrl+0(그림에 포커스가 있을 때). 배율 10~300%. 휠은 스크롤, 빈 곳을 끌면 화면 이동
  (ER 다이어그램 `ErdCanvas`와 같은 조작). 창에 맞추기는 100%를 넘지 않는다.
- **도구 설명:** 연산, 논리 연산, 개체(전체 이름), 연산자 비용, 예상 행(모든 실행), 실제 행, 하위 트리 비용, 실행 횟수, 시간, 조건자, 경고. 긴 값은 줄을 나누고 600자에서 자른다.
- **색:** 모두 테마 키를 그릴 때마다 읽는다(캔버스 `Chromite.editorBackground`, 상자 바탕은 캔버스에 글자색 3.5%, 테두리 `Component.borderColor`, 선택
  `Table.selectionBackground` + `Component.focusedBorderColor`, 보조 글자 `Chromite.secondaryForeground`, 화살표는 캔버스와 글자색의 45% 섞음,
  상태 색 `Chromite.status.*`). 경고 노랑은 밝은 바탕에서 글자로 읽기 어려워 글자에 쓸 때는 글자색과 35% 섞는다. 그래서 라이트·다크 전환 뒤 다시 그리기만 하면 된다.
- **아이콘:** 생성기(`gen_ui_icons.py`)에 연산자 17개를 더했다. 데이터 객체는 중립색, 동작은 파랑, 데이터 변경은 표 위 배지(삽입 초록 +, 수정 파랑 연필, 삭제 빨강 ×).
  `plan-select`(문), `plan-scan`, `plan-seek`, `plan-lookup`(키), `plan-join-nested`, `plan-join-hash`(#), `plan-join-merge`, `plan-sort`, `plan-aggregate`(Σ),
  `plan-filter`, `plan-compute`(=), `plan-top`, `plan-parallelism`, `plan-insert`, `plan-update`, `plan-delete`, `plan-operator`(그 밖).
  연산 이름의 낱말로 고른다(`PlanDiagramBuilder.classify`): DB마다 같은 연산의 이름이 달라(`Clustered Index Seek` / `Index Scan`+`Index-Cond` / `INDEX RANGE SCAN` /
  MySQL `access_type` ref) 공통 종류(`DBCPlanNodeKind`)로는 Seek·Scan·Lookup을 나누지 못한다. 순서가 중요하다: 해시로 하는 집계는 집계, 병렬 스캔은 스캔.

#### 원본 보기

- 계획 모델이 남긴 원문(`DBCPlan.getPlanSourceData`)을 바꾸지 않고 보인다. 서식을 다시 맞추지 않으므로 SQL Server showplan XML은 한 줄이고,
  2,000자보다 긴 줄이 있으면 화면에서만 줄을 접는다(복사·저장은 원문 그대로). 구문 색은 RSyntaxTextArea가 아는 XML·JSON, 테마와 고정폭 글꼴은 값 패널과 같이
  SQL 편집기 테마 파일과 `monospaced.font`다.
- 계획이 여럿이면(실제 계획은 문장마다 XML 하나) 도구 막대에 `쿼리 N` 목록이 보인다. 계획이 더 들어와도 보던 항목을 유지한다.
- 다른 이름으로 저장: 형식에 맞는 확장자 하나(SQL Server showplan `.sqlplan`, 그 밖의 XML `.xml`, JSON `.json`, 글자 `.txt`), UTF-8. 파일이 있으면 바꿀지 묻는다.
- DB별 원문(모델이 실제로 남기는 것, 확인한 코드):

| DB | 원문 | 모델 |
|---|---|---|
| SQL Server | showplan XML(예상 `SHOWPLAN_XML`, 실제 `STATISTICS XML`) | `SQLServerExecutionPlan.getPlanSourceData` (원래도 남김) |
| PostgreSQL | `EXPLAIN (FORMAT XML …)` 결과 XML. DBeaver 계획기가 XML 형식으로 요청하므로 JSON이 아니라 XML이다(서버가 돌려준 그대로) | `PostgreExecutionPlan.planText` (원래도 남김) |
| MySQL / MariaDB | `EXPLAIN FORMAT=JSON` 결과 JSON(예전 서버는 `EXPLAIN EXTENDED` 결과 집합 → 열 맞춘 표 글자) | `MySQLPlanJSON.jsonPlan`, `MySQLPlanClassic.rawResultSet` (원래도 남김) |
| Oracle | `DBMS_XPLAN.DISPLAY(plan_table, statement_id, 'TYPICAL')` 글자. 읽지 못하면(권한 등) PLAN_TABLE 행 표 글자 | **모델 변경**: 원래는 PLAN_TABLE 행(`CachedResultSet`)만 남아 Oracle 사용자가 읽는 형태가 아니었다 |

#### DB별 지원 범위

| DB | 그래픽 보기 | 원본 |
|---|---|---|
| SQL Server | 전체: 연산자 아이콘(Key Lookup 포함), 연산자 비용 %, 예상·실제 행(모든 실행), 어긋남 표시, 경고 배지 전부, 누락 인덱스 머리말, 일괄 처리 기준 문장 비용 | showplan XML |
| PostgreSQL | 전체: 비용 %(Total-Cost에서 자식 비용을 뺀 값), 예상·실제 행(× Actual-Loops), 어긋남 표시, 넘침 경고, 문장 상자(SQL 첫 단어) | EXPLAIN XML |
| MySQL / MariaDB | 공통 모델: JSON 노드 이름(`query_block`, `nested_loop`, `table`…), 개체 `테이블 (access_type)`, `access_type`으로 Seek/Scan 아이콘, 예상 행(`rows`/`rows_examined_per_scan`), 비용 %(공통 방식. 자기 비용이 없는 중간 노드는 0%, 하위 트리 비용은 자식 비용의 합 — 2026-11-07 모델 수정, [model-fixes.md](model-fixes.md)). 뿌리 `select`/`query_block`이 문장 상자. 실제 계획 없음(아래 "미룬 일") | JSON |
| Oracle | 공통 모델: 연산(옵션)(`TABLE ACCESS (FULL)`, `INDEX (RANGE SCAN)`…, 뿌리 `SELECT STATEMENT`가 문장 상자), 개체 `소유자.이름`, 비용 %(하위 트리 COST에서 자식 COST를 뺀 값), 예상 행(CARDINALITY). 적응 계획은 쓰는 행만(아래 "Oracle 적응 계획"). 실제 계획(2026-11-08): 실제 행(A-Rows), 예상 행 × Starts, 어긋남 표시, 실행 횟수, 시간 | 예상 DBMS_XPLAN.DISPLAY, 실제 DISPLAY_CURSOR(ALLSTATS LAST) 글자 |
| Altibase | 공통 모델: 계획 줄 이름(`PROJECT`, `SCAN`…). 실제 계획(2026-11-08)은 같은 모양이고 계획 글자의 ACCESS가 그 실행의 실제 접근 수(예상은 `??`) | 드라이버가 준 계획 글자 |
| 그 밖의 DB | 공통 모델(`DBCPlanNode` 이름·개체·조건, `DBCPlanCostNode` 비용·행). 아이콘은 연산 이름의 낱말로 | 모델이 남긴 원문 |

#### 모델 변경

- `SQLServerExecutionPlan`/`SQLServerPlanNode`(Chromite 파일): showplan의 경고(`RelOp/Warnings`, `QueryPlan/Warnings`), 누락 인덱스(`QueryPlan/MissingIndexes`),
  예상 실행 수(`EstimateRebinds`/`EstimateRewinds`)를 읽는다. 속성 표에 `예상 실행 횟수`, `경고`(서버가 쓴 요소 이름과 세부 내용), `누락 인덱스`(CREATE 문)가 더해진다
  (번들 지역화 `meta.*` 키, 영어·한국어). 키 조회는 XML에서 `PhysicalOp="Clustered Index Seek"`이고 `IndexScan Lookup="1"`로만 구별되므로
  연산 이름을 SQL Server 도구처럼 `Key Lookup`으로 바꿨다(트리 보기에도 같은 이름이 보인다).
- `ext.mssql` MANIFEST: Swing 번들이 SQL Server 계획 노드를 읽도록 `com.seizia.chromite.ext.mssql.model.plan`을 내보낸다(다른 DB의 계획 패키지는 원래 내보냄).
- `OracleExecutionPlan`(DBeaver 파일, Chromite 수정 표시와 Javadoc): 계획 행을 읽은 뒤 같은 세션·같은 STATEMENT_ID로 `DBMS_XPLAN.DISPLAY` 글자를 읽어 원문으로 둔다.
  `getPlanSourceDataFormat`은 그 글자가 있으면 TEXT, 없으면 예전처럼 RESULT_SET이다. 이 값을 읽는 다른 코드는 없다(검색으로 확인: `getPlanSourceData` 호출은 원본 보기뿐).

#### Oracle 적응 계획 (2026-11-08, 후속 과제 #71)

- 증상: 적응 계획(adaptive plan)의 트리·그림에 쓰이지 않는 행이 함께 보였다. 예: `t1 JOIN t2 … WHERE t1.grp = 3 AND t1.val LIKE 'v1%'`의 PLAN_TABLE은 9행
  (`HASH JOIN` 아래 `NESTED LOOPS` ×2, `STATISTICS COLLECTOR`, `INDEX (RANGE SCAN)`, `TABLE ACCESS (BY INDEX ROWID)`, `TABLE ACCESS (FULL)` ×2)인데
  `DBMS_XPLAN.DISPLAY` 기본 출력(원본 보기)은 4행(`SELECT STATEMENT` → `HASH JOIN` → `T1`, `T2` 전체 읽기)이다. `+ADAPTIVE`로 보면 나머지 5행은 '-'(쓰지 않음)다.
- 원인: `OracleExecutionPlan.readPlanNodes`는 PLAN_TABLE의 모든 행을 PARENT_ID로 이었다. 어느 행을 쓰는지는 첫 계획 행(ID 1)의 `OTHER_XML` 안
  `<display_map>`에 있다: 행마다 `op`(PLAN_TABLE ID), `dis`(보이는 번호), `par`(보이는 부모의 번호), `skp`(1이면 쓰지 않음). 쓰는 행의 부모는 PARENT_ID가 아니라
  `par`일 수 있다(위 예에서 `T1`의 PARENT_ID는 `STATISTICS COLLECTOR`, 보이는 부모는 `HASH JOIN`).
- 고친 것: 계획 행을 다 읽은 뒤 `display_map`에 `skp="1"` 행이 있으면 그 행을 빼고 나머지를 `par`의 노드 아래로 다시 잇는다(`hideInactiveRows`,
  `OraclePlanNode.clearNested`·`linkToDisplayParent`). 문장 행(ID 0)은 맵에 없어 그대로다. 비용 합계(`updateCosts`)는 다시 이은 뒤에 한다.
  모델에서 거르므로 트리·그림 보기와 Chromite Agent의 계획 도구(`PlanTools`)가 같은 계획을 본다. 원본 보기(DBMS_XPLAN 글자)는 원래 쓰는 행만 보였다.
- 적응 계획이 아니거나 쓰지 않는 행이 없으면, 또는 `OTHER_XML`을 읽지 못하면 예전처럼 모든 행을 PARENT_ID로 잇는다.
- `EXPLAIN PLAN`의 적응 계획은 실행 전 기본 계획(옵티마이저가 처음 고른 쪽)이다. 실행 중 통계 수집기가 다른 조인으로 바꿀 수 있지만 그것은 실제 계획(V$SQL_PLAN)에서만 보인다.
  2026-11-08부터 실제 계획 토글이 커서 계획을 같은 코드로 거른다(커서의 display_map은 실행 중 고른 계획, 아래 "실제 계획 토글을 Altibase·Oracle로").
- 확인(2026-11-08, Oracle 23ai, 시험 스키마 `CHR_RESULTS_PLAN`의 t1 10,000행·t2 50,000행): 트리 `SELECT STATEMENT`(77) → `HASH JOIN`(77) → `TABLE ACCESS (FULL) T1`(9),
  `TABLE ACCESS (FULL) T2`(68). 그림은 `HASH JOIN` 0%·`T1` 12%·`T2` 88%. 적응 계획이 아닌 `WHERE id = 7`은 `TABLE ACCESS (BY INDEX ROWID)` → `INDEX (UNIQUE SCAN)` 그대로.
  같은 계획의 PLAN_TABLE 9행과 display_map은 `sqlplus`로 읽어 맞춰 보았다.

### 실제 계획 토글을 Altibase·Oracle로 (2026-11-08)

사용자 결정: 실행 계획은 SSMS처럼 동작한다. 실제 계획은 사용자가 쿼리 창마다 켤 때만(기본 꺼짐) 실행 결과와 함께 보이고, 예상 계획(Ctrl+L)은 실행하지 않는다.
SQL Server에만 있던 토글을 Oracle과 Altibase로 넓혔다. 문장은 **한 번**, 창의 세션·트랜잭션에서 실행되고, 아무것도 암묵적으로 커밋·롤백하지 않으며,
바꾼 세션 설정은 문장이 실패해도 되돌린다.

#### 어댑터 모양

예전 `SQLActualPlanProvider`는 "켜는 SQL 목록, 끄는 SQL 목록, 계획 결과 집합 판별"이었다. Altibase는 드라이버 API로 켜야 하고, Oracle은 켜기 전 값을 읽어 두었다
되돌려야 해서 SQL 목록으로 나타낼 수 없다. 그래서 `startRun(session)`이 실행 단위 `Run`(켜기 전 값을 담음)을 돌려주고, `Run`에 계획을 받는 세 자리를 두었다.

| 자리 | 언제 | 쓰는 DB |
|---|---|---|
| `isPlanResultSet` / `parsePlan` | 결과 집합을 받을 때(`ActualPlanReceiver`) | SQL Server |
| `readOpenStatementPlan` | 문장이 오류 없이 끝나고 결과를 다 읽은 뒤, 닫기 전. 엔진 처리기의 새 훅 `afterStatementExecuted`(엔진 `SQLQueryExecuteJob.executeStatement`) | Altibase |
| `readStatementPlan` | 문장을 닫은 뒤, 문장 끝 이벤트(`onEndQuery`)의 맨 앞(서버 출력 읽기·스마트 커밋보다 먼저) | Oracle |
| `finish` | 실행 Job이 끝날 때(오류·취소 포함), 준비 중 예외로 Job이 시작하지 못했을 때, 한 번만 | 모두 |

켜기에 실패하면(권한 등) Messages에 이유를 쓰고 문장은 원래대로(계획 없이) 실행한다. 계획 탭에는 같은 이유가 보인다.
되돌리기에 실패하면 Messages에 오류로 알린다.

#### Oracle

- 세션 `ALTER SESSION SET STATISTICS_LEVEL = ALL`로 행 원천 통계를 모으고, 실행이 끝나면 켜기 전 값(`V$PARAMETER`는 이 세션의 값, 보통 TYPICAL)으로 되돌린다.
  이미 ALL이면 바꾸지 않는다. 켜기 전 값을 읽지 못하면 되돌릴 값을 모르므로 켜지 않는다. `ALTER SESSION`은 트랜잭션을 끝내지 않는다.
- Chromite Agent의 `getActualPlan`(`PlanTools`)은 `GATHER_PLAN_STATISTICS` 힌트와 표시 주석을 문장에 끼우고 V$SQL에서 그 주석으로 커서를 찾는다.
  쿼리 창에서는 쓰지 않았다: 사용자 문장 글자가 바뀌면 오류 위치(드라이버가 주는 글자 위치)가 어긋나고, 원본 보기에 힌트가 보이며, 사용자의 힌트와 합쳐야 한다.
  가져온 것은 같은 계획 행 뷰(`V$SQL_PLAN_STATISTICS_ALL`), `DISPLAY_CURSOR(sql_id, child, 'ALLSTATS LAST')` 글자, 권한 안내(`GRANT SELECT_CATALOG_ROLE`),
  DISPLAY_CURSOR가 권한 없음을 글자로 돌려주는 경우의 판별이다.
- 커서 찾기: 문장이 열려 있을 때 드라이버가 준 SQL_ID(`OracleStatement.getSqlId()`, ojdbc 23부터, 2026-11-12 "실제 계획 토글 점검")와, 문장을 닫은 직후
  같은 세션의 `V$SESSION.PREV_SQL_ID/PREV_CHILD_NUMBER`. PREV가 같은 SQL_ID면 그 자식 번호, 아니면 그 SQL_ID에서 가장 최근에 쓰인 자식이다. SQL_ID를 주지 않는
  옛 드라이버는 PREV만 쓴다. 커서를 닫아야 그 실행의 통계가 끝난다(200행만 가져온 SELECT도 A-Rows는 가져온 만큼). 그 사이 같은 세션에서 다른 문장이 돌았으면 엉뚱한 계획을 보이지 않도록, 커서 글자(`V$SQL.SQL_TEXT`, 앞 1,000자)가 실행한 글자와 같은지 본다
  (공백 차이, 드라이버가 `?`를 바꾼 `:1`은 같게 본다). 다르면 그 문장 항목에 이유를 보인다.
- 계획이 없는 문장(PL/SQL 블록, DDL, COMMIT 등)은 첫 낱말(앞 주석·괄호 다음, SELECT/WITH/INSERT/UPDATE/DELETE/MERGE)로 걸러 서버에 묻지 않는다.
- 계획 모델: `OracleExecutionPlan.readCursorPlan`이 커서 계획 행을 PLAN_TABLE과 같은 코드(`readNodes`)로 잇는다. 열 이름이 같고, 적응 계획 거르기(`hideInactiveRows`)도
  그대로 쓴다. 노드는 `OracleActualPlanNode`(마지막 실행의 Starts, A-Rows, A-Time(ms), Buffers(일관+현재 모드), Reads). 하위 클래스로 둔 이유는 트리 열이 노드
  클래스의 보이는 속성이라 예상 계획에 빈 열이 생기지 않게 하려는 것이다. 문장 행(ID 0)의 CARDINALITY는 커서 계획에서 NULL이라 예상 행을 비운다.
  그래픽 보기는 실제 행, 예상 행 × Starts(E-Rows는 시작 1회 값), 실행 횟수, 시간을 보인다(`PlanDiagramBuilder`). 원본 보기는 DISPLAY_CURSOR 글자다.
- 필요한 권한: `V$PARAMETER`, `V$SESSION`, `V$SQL`, `V$SQL_PLAN_STATISTICS_ALL` 읽기(`SELECT_CATALOG_ROLE` 또는 `SELECT ANY DICTIONARY`). Diagnostics/Tuning Pack은 필요 없다.

#### Altibase

- 세션의 계획 모드를 드라이버 API `setExplainPlan(byte)`로 `EXPLAIN PLAN = ON`(1)으로 바꾸고, 끝나면 켜기 전 값으로 되돌린다(보통 OFF 0).
  SQL(`ALTER SESSION SET EXPLAIN PLAN = ON`)로 바꾸지 않는 이유: 드라이버가 모드를 따로 기억하고 그 값이 꺼짐이면 `getExplainPlan()`이 오류(334404)를 낸다
  (드라이버 7.3 바이트코드 `AltibaseConnection.setExplainPlan`, `AltibaseStatement.throwErrorForExplainTurnedOff`로 확인). 켜기 전 값은 드라이버의
  `explainPlanMode()`(패키지 전용)로 읽는다. 읽지 못하면 켜지 않는다.
- 계획은 문장이 열려 있는 동안 `getExplainPlan()`으로 받는다(준비된 문장의 id로 서버에 묻는다). 결과를 다 읽은 뒤라 ACCESS는 그 실행의 실제 접근 수다.
  모델은 예상 계획과 같은 `AltibaseExecutionPlan`(계획 글자 → `AltibasePlanBuilder`)이고, 원본 보기는 그 글자다. 계획이 없는 문장은 항목을 만들지 않는다.
- `setExplainPlan`은 세션 속성만 보내므로 트랜잭션을 끝내지 않는다(열린 트랜잭션에서 모드를 바꾼 뒤 다른 세션에서 미확정 값이 보이지 않고 롤백되는 것을 확인).
- 예상 계획(Ctrl+L)은 그대로 `EXPLAIN PLAN = ONLY`(전역 설정 `altibase.explain.plan.type`의 기본값)이고, 그 설정은 Options에 두지 않는다.

#### 지원하지 않는 연결

- 어댑터를 주지 않는 서버의 창은 토글이 꺼지고, 도구 설명(메뉴 항목과 SQL Editor 도구 막대 단추)이 이유를 말한다
  ("실제 실행 계획은 SQL Server, Oracle, Altibase 연결에서 쓸 수 있습니다. 이 연결에서는 계획을 보려면 문장을 한 번 더 실행해야 합니다.").
  2026-11-12부터 연결 전에 드라이버로 판단해 처음부터 꺼진다(아래 "실제 계획이 없는 DB는 토글을 처음부터 끔"). 전에는 연결된 뒤에야 알아 연결 전에 켤 수 있었다.
  어댑터가 없는 서버의 창은 토글이 켜져 있었어도 실행할 때 계획을 구하지 않는다.
- SQL Server 연결이라도 Babelfish는 어댑터가 없다(`SQLServerQueryPlanner.isSupported`, 2026-11-12에 `SQLServerDataSource.getAdapter`에서 옮김).

#### 확인 (2026-11-08)

빌드 `BUILD SUCCESS`, `tools/rename/gate.py` ALL GATES PASSED. 빌드한 제품을 개인 작업 공간과 `CHROMITE_DATA`로 한 벌 띄우고, 시험용 EDT 에이전트(제품에 넣지 않음)가
자기 프로세스 안에서 Query 메뉴 동작(Ctrl+M과 같은 `Action`)과 실행을 불러 확인했다(화면 좌표·전경 창 입력 없음). 영어(`-nl en`)와 한국어(`-nl ko`).

| 항목 | 결과 |
|---|---|
| Oracle 23ai SELECT(수동 커밋, 시험 사용자 `CHR_APLAN` + `SELECT_CATALOG_ROLE`) | 결과 10행과 계획 1개. 트리 열에 시작 횟수·실제 행·실제 시간·버퍼·디스크 읽기, `HASH JOIN` 예상 6,080 / 실제 5,560, 원본은 DISPLAY_CURSOR(ALLSTATS LAST) 글자 |
| Oracle UPDATE(수동 커밋) | 1,000행, 계획 1개(`UPDATE STATEMENT` → `UPDATE` → `TABLE ACCESS (FULL)` A-Rows 1,000). 다른 세션(system)에서: 바뀐 행 0(미확정), 그 창 세션의 `V$TRANSACTION` 1건(USED_UREC 1000), 커서 `EXECUTIONS=1`, 세션 `statistics_level=typical`(되돌림) |
| Oracle 오류 문장 | ORA-00942, 계획 없음, `statistics_level=typical`, 앞의 트랜잭션은 그대로 열림 |
| Oracle 섞인 스크립트 | PL/SQL 블록·CREATE TABLE·COMMIT은 계획 없음(서버에 묻지 않음), INSERT와 머리 주석이 있는 SELECT는 계획 2개 |
| Oracle 적응 계획 | `t1.val LIKE 'v12%' AND t1.grp = 2`: "this is an adaptive plan", 실행 중 해시 조인으로 바뀜. V$SQL_PLAN 9행 중 쓰는 4행만 트리·그림에(문장 행 포함 5행), T2 예상 5 / 실제 50,000 어긋남 표시 |
| Oracle 권한 없음(`CHR_APLAN_NOPRIV`) | Messages와 계획 탭에 "Can't include … GRANT SELECT_CATALOG_ROLE …", 문장은 그대로 실행(1행) |
| Altibase 7.3(`chromite-test-altibase`, 수동 커밋) | SELECT: 계획 글자의 ACCESS 400·10(실제), 원본 보기. UPDATE: 50행, 계획 1개(ACCESS 500), 다른 세션에서 바뀐 행 0(미확정), 상태 줄 열린 트랜잭션. 끝난 뒤 창 연결의 드라이버 모드 0(OFF, 되돌림) |
| SQL Server | 두 문장: 결과 5행·1행, 계획 2개(실제 / 예상 행, 실행 횟수), 계획 결과 집합은 행 수에 없음(이전과 같음) |
| PostgreSQL | 연결 전 토글 켜고 실행: 계획 탭에 지원 안 함 이유. 연결 뒤 토글·도구 막대 단추 꺼짐, 도구 설명이 이유(영어·한국어) |

화면: `D:\Tools\_scratch\actual-plan-more-dbs\shots\`(en/ko Oracle 그래픽·트리·원본, Altibase 그래픽·원본·트리, PostgreSQL 꺼진 토글). 회귀 테스트는 실행하지 않았다.

### 실제 계획 토글 점검 (2026-11-12, 앱 변경 #58)

사용자 보고(기업 환경): "실제 실행계획 토글이 제대로 안 켜지는 문제, 쿼리가 실행되고 실행계획이 제대로 안 나오는 문제". 재현 조건은 몰랐다.
기대 동작(SSMS와 같음, 설계 그대로): 예상 계획 단추는 실행하지 않고 바로 예상 계획, 실제 계획은 끌 때까지 켜져 있는 토글이고 켜져 있으면 실행할 때마다
결과와 함께 실행 계획 탭에 실제 계획(문장은 한 번만 실행).

시험 DB VM(SQL Server 2022, Oracle 23.26 Free, PostgreSQL 17, MariaDB 11, MySQL 8.4)에서 devel 빌드로 재현했다. 찾은 원인은 셋이고, 토글 자체가
꺼지는 경우는 찾지 못했다(아래 "토글").

#### 원인 1: Oracle, 최대 행 수에 닿은 쿼리의 계획이 나오지 않음

- 증상: 결과가 최대 행 수(기본 200) 이상인 쿼리는 계획 탭에 계획 대신 "The statement ran, but another statement ran on the session before its plan was read".
  200행 미만이면 나온다. 실무 쿼리는 대개 200행을 넘으므로 "가끔 안 나온다"로 보였다.
- 근거: 세션 SQL trace로 문장 사이에 다른 문장이 돌지 않은 것을 확인했다. 같은 연결에서 JDBC로 다시 해 보니 `setMaxRows(200)`을 건 문장을 끝까지 읽지 않고
  닫으면 `V$SESSION.PREV_SQL_ID`가 그 문장이 아니라 그 앞 문장을 가리킨다(`setMaxRows(0)`이면 맞게 가리킨다). Chromite는 모든 쿼리에 결과 행 수 제한을
  `setMaxRows`로 건다(`JDBCStatementImpl.setLimit`).
- 고침: 커서의 SQL_ID를 문장이 열려 있는 동안 드라이버에게 받아 둔다(`OracleActualPlanProvider`의 `Run.readOpenStatementPlan`, 엔진 훅
  `afterStatementExecuted`). 계획은 지금처럼 닫은 뒤 읽는다(통계가 끝난 뒤). 자식 커서는 PREV가 같은 SQL_ID면 그 번호, 아니면 그 SQL_ID에서 가장 최근에
  쓰인 자식(`statistics_level = ALL` 세션은 따로 자식 커서를 쓰므로 다른 세션과 섞일 일이 거의 없다). 커서 글자 확인은 그대로다. 같은 원인의 다른 경우:
  가져오다 취소한 쿼리, 결과를 끝까지 읽지 않은 모든 쿼리. 남는 경우: ojdbc 23 전 드라이버는 SQL_ID를 주지 않아 지금처럼 PREV만 쓴다.

#### 원인 2: SQL Server, SHOWPLAN 권한이 없는 로그인은 문장이 실행되지 않음

- 증상: 토글을 켜고 실행하면 모든 문장이 "SHOWPLAN permission denied in database …"(오류 262)로 실패하고 빈 결과 표가 생기며, 계획 탭은 "서버가 실제 실행
  계획을 반환하지 않았습니다". 사용자에게는 쿼리도 계획도 안 나오는 것으로 보인다.
- 원인: `SET STATISTICS XML ON`은 권한 없이도 성공하고, 권한은 문장을 실행할 때 확인된다(SSMS도 같은 오류를 낸다).
- 고침: 켜기 전에 세션의 현재 DB에서 `HAS_PERMS_BY_NAME(DB_NAME(), 'DATABASE', 'SHOWPLAN')`을 확인한다(`SQLServerQueryPlanner.startRun`). 없으면 켜지 않고,
  Messages와 계획 탭에 "실제 실행 계획을 포함하지 못했습니다: Actual plans need the SHOWPLAN permission in database '…' (GRANT SHOWPLAN TO &lt;user&gt;)"를
  보이며 문장은 계획 없이 실행된다(Oracle 권한 부족과 같은 흐름). 이 확인은 표를 읽지 않으므로 수동 커밋에서도 암묵 트랜잭션을 시작하지 않는다.
  남는 경우: 실행 중 `USE`로 권한 없는 DB로 옮기면 그 뒤 문장은 서버 오류 262로 실패한다(SSMS와 같음).

#### 원인 3: 계획이 와도 결과 탭이 앞에 남아 보이지 않음

- #226(두 단계 탭) 뒤 계획은 위 탭 줄의 실행 계획 탭에 쓰이지만, 실행이 끝나면 Grid(오류면 Messages)가 앞에 오고 계획 탭에는 아무 표시가 없었다.
- 고침: 실제 계획을 켠 실행이 끝나면 실행 계획 탭이 앞에 없을 때 점을 붙인다(`QueryResultsView.markPlanUnread`). 계획이 왔으면 초록 점("방금 실행의 실제 실행 계획"),
  계획 대신 이유만 있으면(권한·지원 안 함·계획 없음) 노란 점("방금 실행의 실제 실행 계획 없음: 이유는 탭에 있습니다"). 탭을 보면 점이 없어지고, 다음 실행이 지운다.
  탭을 앞으로 가져오지 않는 것은 SSMS와 같다(결과가 앞).

#### 토글

토글이 꺼지는 경우는 재현되지 않았다. 확인한 것: 도구 막대 단추, Query 메뉴 항목, Ctrl+M(쿼리 창 글 칸에 보낸 키 이벤트)이 같은 상태를 바꾸고, 실행 뒤·다른 쿼리
창으로 갔다 온 뒤·DB 바꾼 뒤에도 켜진 모양(회색 바탕)이 남는다. 창마다 따로다(새 창은 꺼짐). 사용자가 "안 켜진다"고 볼 수 있는 경우는 설계대로인 하나다:
연결하기 전에는 어댑터를 몰라 켤 수 있고, 실행해 연결된 뒤 지원하지 않는 연결(PostgreSQL, MySQL·MariaDB 등)이면 단추가 꺼지고 흐려지며 도구 설명과 계획 탭이
이유를 말한다. 이 경우는 앱 변경 #70에서 연결 전부터 끄는 것으로 바뀌었다(아래 "실제 계획이 없는 DB는 토글을 처음부터 끔").

#### 확인 (2026-11-12)

빌드 `BUILD SUCCESS`, `tools/rename/gate.py` ALL GATES PASSED. 시험 DB VM에 터널로 붙어, 빌드한 제품 한 벌을 개인 작업 공간·설정·`CHROMITE_DATA`로 띄우고
프로세스 안 시험 에이전트(제품에 넣지 않음, OS 입력 없음)가 도구 막대 단추(`doClick`)·Query 메뉴 항목·Ctrl+M 키 이벤트로 토글하고 실행했다. 한국어, Windows 11 150%.
권한을 줄인 계정(SQL Server 로그인 SHOWPLAN 없음, Oracle 사용자 CREATE SESSION과 표 권한만)은 브랜치 이름의 시험 DB에 만들었다가 지웠다.

| 항목 | 고치기 전(devel) | 고친 뒤 |
|---|---|---|
| SQL Server sa: 한 문장, 여러 문장(SELECT·UPDATE·0행), 선택, GO 일괄 처리, 200행 넘는 결과 | 계획 나옴(`SELECT 1`처럼 FROM 없는 문장은 서버가 계획을 주지 않음) | 같음, 실행 계획 탭에 초록 점, 탭을 보면 점 없어짐 |
| SQL Server SHOWPLAN 없는 로그인 | 두 문장 모두 오류 262, 빈 결과 표, 계획 탭 "서버가 실제 실행 계획을 반환하지 않았습니다" | 결과 5행·1행, Messages와 계획 탭에 권한 안내, 노란 점 |
| SQL Server 수동 커밋에서 토글 켜고 `SELECT 1` 뒤 `@@TRANCOUNT` | - | 0(권한 확인이 트랜잭션을 열지 않음) |
| Oracle system: 200행 미만 SELECT·UPDATE·0행 | 계획 나옴 | 같음 |
| Oracle system: 최대 행 수(200)에 닿은 SELECT(넘음·딱 200행), 그 문장만 선택 | "another statement ran on the session before its plan was read" | 계획 나옴(A-Rows 200), 다섯 문장 스크립트에서 계획 5개 |
| Oracle 오류 문장 | 오류, 계획 탭 "반환하지 않았습니다" | 같음, 노란 점 |
| Oracle V$ 권한 없는 사용자 | Messages와 계획 탭에 권한 안내, 문장 실행 | 같음, 노란 점 |
| PostgreSQL 17, MariaDB 11, MySQL 8.4(MariaDB Connector/J) | 연결 전 켜고 실행: 계획 탭에 지원 안 함 이유, 단추 꺼지고 흐려짐, 도구 설명에 이유 | 같음, 노란 점 |
| 토글 유지 | 실행 뒤, 다른 쿼리 창 갔다 옴, DB 선택 바꿈: 켜진 모양 유지. 창마다 따로. 끄면 다음 실행은 계획 없음 | 같음 |

회귀 테스트는 실행하지 않았다(사용자 요청 없음).

### 실제 계획이 없는 DB는 토글을 처음부터 끔 (2026-11-12, 앱 변경 #70)

사용자 결정(2026-11-12): 실제 계획을 주지 않는 DB에서 토글을 처음부터 비활성으로 보일지 물음에 "처음부터 비활성". 전에는 어댑터
(`SQLActualPlanProvider`)를 데이터 소스가 만들어 연결된 뒤에야 알았으므로, PostgreSQL·MariaDB·MySQL 창에서도 연결 전에는 토글을 켤 수 있었고 첫 실행 뒤
꺼지고 흐려졌다(#58 보고의 "토글이 제대로 안 켜진다"로 보였을 수 있음). #69 뒤로 창마다 서버가 정해져 있으므로 서버가 정해지는 때에 판단한다.

#### 연결 전 판단

- 어댑터를 주는 데이터 소스는 셋뿐이다: `SQLServerDataSource`(Babelfish 제외, 드라이버로 가림), `OracleDataSource`(그 하위 클래스인 YashanDB 포함),
  `AltibaseDataSource`. 어떤 데이터 소스 클래스가 생기는지는 연결하지 않고도 드라이버의 데이터 소스 제공자(일반 JDBC 제공자는 드라이버의 메타 모델)로 정해진다.
  그래서 제공자가 답하는 인터페이스 `SQLActualPlanSupport`(`model.sql`)를 두었다. 구현: `SQLServerDataSourceProvider`(`SQLServerQueryPlanner.isSupported`:
  데이터 소스 어댑터와 같은 드라이버 조건을 한 곳에 모음), `OracleDataSourceProvider`(늘 참, YashanDB 제공자가 물려받음), `GenericDataSourceProvider`
  (`GenericMetaModelRegistry.getMetaModel`이 고르는 메타 모델에 물음: 드라이버 매개변수·드라이버 클래스만 읽음), `AltibaseMetaModel`(늘 참).
  이것을 구현하지 않은 제공자의 연결은 실제 계획이 없다고 본다(PostgreSQL, MySQL·MariaDB 등 그 밖의 모든 DB).
- plugin.xml에 능력 속성을 두지 않은 이유: 어댑터는 데이터 소스 클래스가 정하고, Altibase처럼 일반 JDBC 메타 모델로 생기는 경우까지 같은 곳에서 답하려면
  데이터 소스를 만드는 코드(제공자·메타 모델)가 답하는 편이 어긋나지 않는다.
- 편집기는 연결 여부와 상관없이 이 선언만 본다(`SqlEditorPanel.getActualPlanSupport`). 그래서 토글 모양이 연결 전후에 바뀌지 않는다. 실행할 때는 지금처럼
  데이터 소스의 어댑터를 쓴다. 선언과 어댑터가 어긋나는 경우(선언은 참인데 어댑터가 없음)만 계획 탭에 지원 안 함 이유가 남는다(`finishActualPlan`).
- 연결해야 알 수 있는 조건은 그대로 실행 때 알린다: Oracle V$ 권한, SQL Server SHOWPLAN 권한(#233, 위 "원인 2").

#### 동작

- 지원하지 않는 서버의 창: 도구 막대 단추, Query 메뉴 항목, Ctrl+M이 처음부터 꺼지고(`Action` 하나를 함께 씀) 눌린 모양이 아니며, 도구 설명(단추와 메뉴 항목)이
  이유를 말한다. 연결한 뒤에도 같다.
- 탭을 바꾸면 활성 창의 서버로 바로 다시 정한다(`MainFrame.updateQueryActions`, 원래 흐름).
- 서버가 없는 창: 토글은 켜져 있다(어느 서버가 될지 모름). 서버를 고르면(`chooseServer`) 그 서버로 정한다. 켜 둔 채 지원하지 않는 서버를 고르면 토글이 꺼진 모양이
  되고 그 창의 실행은 계획을 구하지 않는다(서버 없이 F5를 눌러 실행 중에 고른 경우도 같음).

#### 확인 (2026-11-12)

빌드 `BUILD SUCCESS`, `tools/rename/gate.py` ALL GATES PASSED. 시험 DB VM에 터널로 붙어, 빌드한 제품 한 벌을 개인 작업 공간·설정·`CHROMITE_DATA`로 띄우고
프로세스 안 시험 에이전트(제품에 넣지 않음, OS 입력 없음)로 확인했다. 한국어. 시험 DB에는 아무것도 만들지 않았다(시스템 카탈로그 조회만).

| 항목 | 결과 |
|---|---|
| PostgreSQL 17, MariaDB 11, MySQL 8.4(MariaDB Connector/J), 연결 전 | 단추·동작·메뉴 항목 꺼짐, 눌리지 않음, 도구 설명에 이유. PostgreSQL 창에서 단추 클릭·메뉴 항목 클릭·Ctrl+M: 상태 그대로(꺼짐) |
| MySQL 드라이버(`mysql8`, 한 번도 연결하지 않음, 드라이버 파일 없음) | 꺼짐 |
| 위 세 DB 실행 뒤(연결됨) | 그대로 꺼짐, 계획 탭은 빈 안내(계획을 구하지 않음) |
| SQL Server 2022, Oracle 23.26, Altibase 7.3, 연결 전 | 켜짐(누를 수 있음) |
| SQL Server(단추로 켬), Oracle(Ctrl+M), Altibase(메뉴 항목) 실행 | 결과와 실제 계획 1개씩, 실행 계획 탭에 초록 점, 토글은 켜진 채 |
| 탭 전환 PostgreSQL ↔ SQL Server(켜짐) | 새로 고치지 않은 상태로 바로 꺼짐 ↔ 켜짐·눌림, 메뉴 항목도 같음 |
| 서버 없는 창 | 켜짐. 켜 둔 채 PostgreSQL을 고르면 꺼짐·눌리지 않음, SQL Server를 고르면 켜짐. 켜 둔 채 F5 → 실행 중 PostgreSQL 고름: 결과 1행, 계획 없음 |

화면: `D:\Tools\_scratch\actual-plan-unsupported\shots\`(밝은·어두운 테마 × 100%·150%, PostgreSQL 꺼짐·SQL Server 켜짐·Oracle 꺼 둠·서버 없음 도구 막대와
단추 4배 확대). 꺼진 단추는 FlatLaf의 꺼진 아이콘·글자 색이고, 이 변경 전 연결 뒤 꺼질 때와 같은 모양이다. 회귀 테스트는 실행하지 않았다(사용자 요청 없음).

### 쿼리 기록

- DBeaver 쿼리 관리자(QM)를 다시 쓰지 않은 이유: CE의 `QMUtils.getEventBrowser`는 `QMRegistryImpl`의 기본 이벤트 브라우저(이번 실행의 메모리 기록)이고,
  파일 기록(`QMLogFileWriter`)은 메타데이터 읽기 같은 내부 쿼리까지 섞인 글자 로그라 검색·재실행용으로 다시 읽을 수 없다.
- 그래서 편집기가 실행한 문장만 엔진의 문장 끝 이벤트(`SQLQueryListener.onEndQuery`)에서 `QueryHistory`에 남긴다:
  시각, 연결 ID·이름, 기본 DB/스키마(실행 문맥의 기본값, 서버 호출 없음), 문장, 실행 시간, 행 수(가져온 행 또는 바뀐 행), 결과(성공·실패·취소), 오류 메시지.
  문장은 엔진이 서버에 보낸 글자다: 매개변수는 넣은 값으로 바뀌어 있고(다시 실행하면 같은 값), SQL Server `GO` 배치는 배치 하나가 한 기록이다.
  Query Library가 값을 바인딩한 문장은 서버에 `?`로 가므로 대신 `:name`이 남은 글자와 바인딩한 값을 남기고, 기록에서 Execute하면 같은 값을 다시 바인딩한다
  ([swing-query-library.md](swing-query-library.md) "매개변수 대화상자와 바인딩 하나로", 2026-11-08 전에는 값을 SQL 리터럴로 넣은 글자).
  실제 계획 결과 집합은 행 수에 넣지 않는다.
- 저장은 작업 공간 `.metadata/chromite-query-history.jsonl`에 기록마다 JSON 한 줄 덧붙이기다. 실행할 때마다 파일 전체를 다시 쓰지 않고,
  비정상 종료되어도 그때까지 남는다. 처음 읽을 때 보관 기간이 지난 기록과 깨진 줄을 빼고 다시 쓴다. 최대 20,000개.
- 보관 기간: Tools > Options > General > Query History "Keep history" (일, 기본 30, 0이면 기록하지 않음). 이 번들의 설정 저장소 키 `sqlEditor.history.retentionDays`.
- Query History 창: 모달이 아닌 창 하나. 찾기(문장·연결·DB, 대소문자 무시), 연결, 기간(Today, Last 7 days, Last 30 days, All)으로 거르고,
  두 번 누르기·Enter·Open in Editor는 기록의 연결로 새 쿼리 창에 문장을 넣고, Execute는 넣고 실행한다. 연결을 지웠으면 연결 없이 연다.

### SQL 서식과 편집 명령

- 서식은 SWT의 서식 전략(`SQLFormattingStrategy.format`)과 같이 DBeaver 서식기(`SQLFormatUtils.formatSQL`, `SQLFormatterConfigurationRegistry`)를 쓴다.
  연결 방언과 연결 설정 저장소로 `SQLSyntaxManager`를 만들고, 연결이 없으면 표준 SQL 방언이다(연결 안 된 연결은 드라이버의 스크립트 방언).
- 설정: Tools > Options > Editor > Formatting의 Style(Default, Compact = 서식기 `default`, `compact`)과 Keyword case(Database default, Upper, Lower, As typed).
  SWT 설정과 같은 플랫폼 설정 키(`sql.format.formatter`, `sql.format.keywordCase`)에 쓰므로 연결별로 따로 정하지 않은 모든 연결에 적용된다.
  기본값은 DBeaver 그대로(Default, Database default: 키워드를 DB가 이름을 저장하는 대소문자로 바꿈)다. 외부 서식기는 명령줄 설정이 필요해 목록에 넣지 않았다.
- 선택이 있으면 선택만, 없으면 문서 전체를 바꾸고 되돌리기 한 번에 취소된다(`beginAtomicEdit`). 서식기가 지운 선택 끝의 줄바꿈은 되살린다.
- 주석: 방언의 한 줄 주석 기호(`--`)를 선택한 줄의 가장 작은 들여쓰기 위치에 넣고(빈 줄 제외), 빼기는 줄 앞(들여쓰기 뒤)의 기호를 지운다.
- 들여쓰기는 RSyntaxTextArea 동작(여러 줄 선택에서 Tab = 들여쓰기, `RSTA.DecreaseIndentAction`)을 그대로 쓴다.
- 두 번 누르는 키(Ctrl+K, Ctrl+D 등): 편집 영역의 키 리스너가 두 번째 키를 받는다. 키 리스너는 키 바인딩보다 먼저 불리므로 Ctrl+K 뒤의 Ctrl+C가
  복사로 가지 않는다. 메뉴 단축키는 한 번 누르는 키만 나타낼 수 있어 두 번 누르는 키는 메뉴 항목 글자에 적었다.

### 템플릿

- SWT 템플릿(`SQLTemplatesRegistry`, `SQLTemplateStore`)은 JFace 템플릿 저장소라 SWT UI 플러그인에만 있다. 필요한 부분만 `SqlTemplates`로 다시 만들었다.
- 기본 템플릿: SWT `templates/default-templates.xml`의 다섯 개(scount, swhere, scgb, sob, sf — 같은 id·내용)와 Chromite의 sel, ins, upd, del.
- 사용자 템플릿은 SWT와 같은 작업 공간 설정 파일 `templates.xml`(`DBConfigurationController`)에 JFace `TemplateReaderWriter` 형식으로 둔다.
  id 없는 항목 = 사용자 템플릿, 기본 템플릿과 같은 id = 바꾼 기본 템플릿, `deleted="true"` = 지운 기본 템플릿. 같은 작업 공간의 SWT 제품과 함께 쓸 수 있다.
- 넣기: 커서 바로 앞 단어가 템플릿 이름이면 Tab이 템플릿으로 바꾼다. `${name}`은 이름 글자로 넣고 첫 변수를 선택하며, 템플릿 안에 있는 동안 Tab이
  다음 변수로 간다. `${cursor}`는 끝 위치, `$$`는 `$`. 여러 줄 템플릿은 현재 줄 들여쓰기를 잇는다.
  자동완성 목록이 떠 있을 때 템플릿 이름 뒤의 Tab은 목록을 닫고 템플릿을 넣는다(Enter는 그대로 제안을 넣음, `SqlCompletionPopup`의 작은 연결 지점).
- Options > Templates: 목록과 이름·설명·내용·사용 여부 편집, New, Remove, Restore Defaults(기본 템플릿을 원래대로, 사용자 템플릿은 그대로). OK에서 저장.

### 매개변수

- SWT의 `SQLEditorParametersProvider` + `SQLQueryParameterBindDialog`를 옮겼다. 엔진의 `SQLScriptContext`에 `QueryParameterBinder.ScriptProvider`
  (`SQLParametersProviderBase`, 2026-11-08 전에는 `SqlParameterDialog.Provider`)를 넘기면, 스크립트 변수·기본값으로 채워지지 않은 매개변수가 있을 때만
  엔진이 부른다(작업 스레드에서 EDT 대화상자를 열고 기다림). 대화상자는 Query Library와 같은 `QueryParameterDialog`의 스크립트 모드다(위에 그 문장, 줄마다 입력란).
- 값은 SWT와 같이 SQL 글자로 문장에 들어간다(문자열은 따옴표). 이름이 같은 매개변수는 한 줄. 마지막 값은 DBeaver의 `SQLQueryParameterRegistry`
  (`parameter-bindings.xml`)에 저장되어 다음에 미리 채워진다. 단추는 SWT와 같은 Execute, Ignore(이 스크립트 끝까지 매개변수 무시), Cancel.
- 값이 저장되지 않던 근본 원인: `BasePlatformImpl.getLocalConfigurationFile`은 작업 공간 `.metadata/.config/<파일>` 경로를 돌려주는데
  폴더를 만들지 않고, 호출하는 쪽(매개변수 바인딩, 열·필터 상태 등)은 모두 그 파일을 바로 쓴다. SWT 제품에서는 다른 설정 저장이 먼저 폴더를 만들어 드러나지 않았고,
  Swing 제품의 새 작업 공간에서는 `NoSuchFileException`으로 저장되지 않았다(`.metadata/.log`). 호출하는 곳마다 고치지 않고 경로를 주는 쪽에서 폴더를 만든다.
- 쿼리 라이브러리의 매개변수 입력과 하나로 합쳤다(2026-11-08, [swing-query-library.md](swing-query-library.md) "매개변수 대화상자와 바인딩 하나로").
  값이 SQL 글자로 들어가는 것, 값 기억, Execute / Ignore / Cancel은 그대로다. 표 대신 입력란이라 Enter가 Execute다.
- Cancel: 엔진(`SQLQueryExecuteJob`)은 매개변수 채우기가 취소되면(`fillQueryParameters`가 false) 오류 없이 스크립트를 멈추고 Job은
  OK 상태("SQL job completed")로 끝난다. 실행 확인 대화상자의 취소도 같다. 그래서 상태 줄이 "Query executed successfully"였다.
  엔진이 이 두 경우를 `isCanceledByUser()`로 알리고, 편집기는 그때 취소 상태("Query was canceled", Messages에 "Query was canceled by user.")를 보인다.
  Job 상태는 바꾸지 않았다: SWT `SQLQueryJob`이 같은 엔진을 쓰고 그 동작(오류·취소 표시 없음)은 그대로 둔다.

### 상태 줄

- 서버, 사용자, DB/스키마, 경과 시간, 행 수는 P1에서 이미 `QueryStatusLine`에 있다. SQL Server 도구의 Ln/Col을 끝 칸으로 더했다(편집 영역의 커서 리스너).

## upstream·기존 코드 대비 변경점

- DBeaver 파일: `SQLServerDataSource.getAdapter`에 계획기 어댑터(저작권 줄 아래 Chromite 수정 표시, 클래스 Javadoc 추가),
  `BasePlatformImpl.getLocalConfigurationFile`이 작업 공간 설정 폴더를 만듦(위 "매개변수").
- Chromite 기존 파일(작은 연결 지점):
  - `SqlEditorPanel`: 실행 계획(도구 막대 단추, Ctrl+L 동작, 실제 계획 켜기·끄기, 수신기 감싸기), 기록 남기기, 매개변수 제공자, 편집 영역 키(편집 명령, 두 번 누르는 키, 템플릿 Tab), 커서 위치.
  - `MainFrame`: Execution plan 탭, Query 메뉴 3항목(예상 계획, 실제 계획 포함, Query History…), Edit > Advanced 하위 메뉴, `Host.showPlan`.
  - `OptionsDialog`: Formatting 두 줄, Query History 한 줄, Templates 탭.
  - `QueryStatusLine`: Ln/Col 칸. `SqlCompletionPopup`: 템플릿 이름 뒤 Tab.
- `ResultSetGrid`, `QueryResultsView`, `ResultSetGridReceiver`는 바꾸지 않았다(계획 결과 집합은 감싸는 수신기에서 걸러 냄).

## 미룬 일

- ~~그래픽 계획 도면~~: 2026-11-07 "실행 계획 세 보기"로 더함.
- 원본 보기의 서식 맞춤(SQL Server showplan XML 들여쓰기): 요청이 "서버가 돌려준 그대로"라 넣지 않았다. 지금은 긴 줄을 화면에서만 접는다.
- ~~MySQL/MariaDB 그래픽 보기의 중간 노드 비용~~: 2026-11-07 `MySQLPlanNodeJSON.getNodeCost`가 자식 비용을 소수로 더하도록 고침(`long`으로 더해 하위 트리 비용이 0,
  뿌리에 문장 비용 전체가 몰렸다). [model-fixes.md](model-fixes.md) "MySQL/MariaDB 계획의 비용". 2026-11-08 다시 확인(MariaDB 11, `t1 JOIN t2`):
  `nested_loop#1` 하위 트리 비용 0.205, `nested_loop#2` 0.981, 그림은 `t1` 17%·`t2` 83%(합 100%), 중간 노드 0%.
- ~~Oracle 적응 계획: PLAN_TABLE에는 쓰이지 않는 행도 있어 트리·그림에 함께 보인다.~~ 2026-11-08 고침(위 "Oracle 적응 계획").
- 문장 머리말의 누락 인덱스 줄을 눌러 CREATE 문을 편집기로 여는 동작(SQL Server 도구에는 있음): 요청 범위 밖.
- PostgreSQL·MySQL/MariaDB 등의 실제 계획: `EXPLAIN ANALYZE`(MariaDB `ANALYZE FORMAT=JSON`)는 결과를 보이려면 문장을 한 번 더 실행해야 해서
  토글을 끈다(2026-11-08 사용자 결정). 별도 설계로 미룸.
- 템플릿 변수의 같은 이름 동시 편집(linked mode)과 문맥 값 제안(테이블 이름 목록).
- 매개변수 대화상자의 Cancel은 그 문장을 실행하지 않고 끝내지만, 엔진이 이를 오류나 취소로 알리지 않아 상태 줄은 "Query executed successfully"로 남는다(실행 엔진 `SQLQueryExecuteJob`의 결과 보고를 바꿔야 함).

## 확인 (2026-11-06)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swt,product-chromite-swing` → `BUILD SUCCESS`.
빌드한 Swing 제품을 개인 작업 공간으로 띄우고, 로컬 테스트 DB(PostgreSQL 17, SQL Server 2022, MariaDB 11)에 `chr_sql_editor_plus` DB를 만들어
`customers`(500행), `orders`(20,000행, `customer_id` 인덱스)로 확인했다. 다른 프로그램이 Windows 전경 창을 계속 잡고 있어 키 입력 대신
시험용 Java 에이전트(제품에 넣지 않음, 작업 폴더에만 있음)가 EDT에서 메뉴·단추·키 이벤트를 보내는 방법으로 조작했다(키 이벤트는 편집 영역에 직접 보내 바인딩·리스너를 그대로 탄다).

| 항목 | 결과 |
|---|---|
| SQL Server 예상 계획(Ctrl+L) | 두 문장 배치: 문장 노드 2개, Sort/Stream Aggregate/Nested Loops/Hash Match/Clustered Index Scan·Seek, 비용 %(Scan 52.1%), 술어·출력 열·Seek 술어 |
| SQL Server 실제 계획(Ctrl+M + F5) | 결과 3행 + 10행, 계획 2개(문장마다), 실제 행·시간·실행 수. Messages에 계획 결과 집합의 "(1 row affected)"가 없음 |
| PostgreSQL 예상 / 실제 계획 | `EXPLAIN (FORMAT XML)` 노드(Hash Join, Bitmap Heap/Index Scan, Seq Scan), 실제 계획은 머리말 "Actual", Time 열에 실행 시간 |
| MariaDB 예상 계획 / Ctrl+M | `EXPLAIN FORMAT=JSON` 노드. Ctrl+M로 실행하면 계획기가 ANALYZE를 모르므로 "Estimated"로 표시 |
| 쿼리 기록 | 세 DB의 실행이 남고 앱을 다시 시작해도(새 빌드 포함) 10개 그대로. 찾기 "seoul" 4개, 연결 MSSQL과 함께 3개. Open in Editor가 기록의 연결로 새 쿼리 창을 엶 |
| 서식·편집 | Ctrl+K Ctrl+D 서식, Ctrl+Shift+U/L 대소문자, Ctrl+K Ctrl+C/U 주석(MariaDB는 방언 기호 `-- `), Options Keyword case Upper 후 Ctrl+Shift+F에서 키워드만 대문자 |
| 템플릿 | `sel`+Tab → `select * from table;`(table 선택), `upd`+Tab 후 Tab으로 변수 이동, 들여쓰기 유지, 템플릿 이름이 아닌 단어의 Tab은 그대로 탭. Options에서 만든 `cnt`가 `templates.xml`에 저장되고 바로 쓰임 |
| 매개변수 | `:cid`, `:cname`(같은 이름 두 번은 한 줄) 값 입력 후 2행 결과, 값이 `parameter-bindings.xml`에 저장되고 다음 실행에 미리 채워짐 |
| 상태 줄 | 서버, 사용자, DB/스키마, 시간, 행 수, Ln/Col |

화면(작업 폴더 `D:\Tools\_scratch\sql-editor-plus\shots\`): 밝은 테마 b01·b03·b04·b05·c01·d01·f01–f06, 어두운 테마 d02–d06·e01–e05.
회귀 테스트는 실행하지 않았다.

## 확인 — 실행 계획 세 보기 (2026-11-07)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → `BUILD SUCCESS`, `java tools/jre/BundleJre.java windows-x64` → 런타임 설치.
빌드한 제품을 개인 작업 공간으로 띄워 한국어(OS 기본)와 `-nl en`, 라이트·다크에서 시험용 EDT 에이전트(제품에 넣지 않음)로 조작했다.
DB는 로컬 테스트 DB에 만든 `chr_plan_three_views`(`chr_plan_customer` 5,000행, `chr_plan_order` 200,000행(MariaDB 50,000, Oracle 100,000), `customer_id` 인덱스,
SQL Server는 통계를 다시 계산하지 않는 `chr_plan_stale`)이다.

| 항목 | 결과 |
|---|---|
| SQL Server 예상 계획(네 문장 일괄 처리) | 문장마다 그림 하나, 머리말 일괄 처리 기준 30% / 1% / 69% / 0%. 조인 + Key Lookup(97%, 오류 색 강조) + Sort, `N'…'` 비교의 암시적 변환 경고(SELECT 상자 배지), 누락 인덱스 머리말(영향 94, `CREATE NONCLUSTERED INDEX … ([status], [amount])`), 쉼표 조인의 "조인 조건자 없음" 배지 |
| SQL Server 실제 계획 | 실제 / 예상 행(`240 / 예상 255 (94%)`), 오래된 통계의 어긋남(`40,000 / 예상 1`, 경고 색), `MAX_GRANT_PERCENT = 0` 정렬의 디스크 넘침(`SpillLevel=8`)과 메모리 부여 경고 |
| PostgreSQL EXPLAIN / EXPLAIN ANALYZE | Hash Join(Inner) + Parallel Seq Scan(71%) + Hash + Seq Scan, Gather Merge(병렬 아이콘), 실제 행 = 루프 값 × Actual-Loops(40,000), 원본은 EXPLAIN XML |
| MariaDB | JSON 노드(select → filesort → temporary_table → nested_loop → table `c (range)`/`o (ref)` Seek 아이콘), 원본 JSON |
| Oracle | `SELECT STATEMENT`, `SORT (ORDER BY)`, `HASH JOIN`, `NESTED LOOPS`, `TABLE ACCESS (BY INDEX ROWID BATCHED)`(Lookup), `INDEX (RANGE SCAN)`(Seek), 원본은 DBMS_XPLAN 글자 |
| 선택·속성 | 그림의 Key Lookup·Parallel Seq Scan 상자를 누르면 오른쪽 속성 표에 모든 속성(SQL Server 예상 실행 횟수 254.93, PostgreSQL 계획 속성 전체) |
| 도구 설명 | 상자: 연산·논리 연산·개체·비용·예상 행·하위 트리 비용·조건자·경고, 화살표: 행 수 (창 캡처에는 도구 설명 창이 담기지 않아 글자로 확인) |
| 원본 보기 | 복사 → 클립보드에 showplan XML, 다른 이름으로 저장… → `.xml` 확장자를 붙여 저장(PostgreSQL), 여러 계획이면 `쿼리 N` 목록 |
| 보기 기억 | 트리를 고르고 다시 시작 → 설정 `plan.view=TREE`, 새 계획 탭이 트리로 열림 |

화면(작업 폴더 `D:\Tools\_scratch\plan-three-views\shots\`, `f-` 파일): SQL Server 예상(ko·en 라이트 그래픽·트리·원본), 실제(ko 라이트·다크 그래픽, 다크 트리·원본),
PostgreSQL(ko 라이트·다크 그래픽·트리·원본, en 다크 그래픽), MariaDB(en 라이트 그래픽·원본), Oracle(en 라이트·다크 그래픽, 다크 트리·원본), 보기 기억(en 다크 트리).
회귀 테스트는 실행하지 않았다.

## 변경 이력

- 2026-11-06 최초 작성: 실행 계획(예상·실제, SQL Server 계획기), 쿼리 기록, SQL 서식·편집 명령, 템플릿, 매개변수 대화상자, 상태 줄 Ln/Col.
- 2026-11-06 실행 계획 표: 열이 너무 좁아 연산 이름이 잘리던 문제. 열을 내용에 맞추고 넘치면 가로 스크롤.
- 2026-11-06 매개변수 대화상자 Cancel 뒤 "Query executed successfully"가 보이던 문제: 엔진이 사용자 취소를 `isCanceledByUser()`로 알림.
- 2026-11-07 계획 단추와 Actual Plan 토글이 메인 창 둘째 줄의 SQL Editor 도구 막대로 옮겨짐(사용자 결정). [swing-frontend.md](swing-frontend.md) "SQL Editor 도구 막대".
- 2026-11-07 실행 계획 세 보기(사용자 결정): 그래픽(연산자 그림, 비용·행·경고·예상 어긋남), 트리, 원본(복사·저장) 보기 전환과 마지막 보기 기억.
  SQL Server 계획 모델이 경고·누락 인덱스·예상 실행 수를 읽고 키 조회를 Key Lookup으로, Oracle 계획 원문을 DBMS_XPLAN 글자로. "미룬 일"의 그래픽 계획 도면 항목을 닫음.
- 2026-11-07 MySQL/MariaDB 계획의 중간 노드 비용(#70): 모델이 자식 비용을 소수로 더함. "미룬 일"의 항목을 닫음.
- 2026-11-08 Oracle 적응 계획(#71): `OTHER_XML`의 display_map으로 쓰지 않는 행을 빼고 보이는 부모로 다시 이음. "미룬 일"의 항목을 닫음. #70을 MariaDB에서 다시 확인.
- 2026-11-08 매개변수 대화상자를 Query Library의 `QueryParameterDialog`(스크립트 모드)로 합치고 `SqlParameterDialog`를 지움. 엔진 제공자는 `QueryParameterBinder.ScriptProvider`.
  바인딩한 실행의 기록은 리터럴 대신 `:name` 글자와 값. "미룬 일"의 대화상자 통합 항목을 닫음.
- 2026-11-08 실제 계획 토글을 Oracle·Altibase로(사용자 결정, "실제 계획 토글을 Altibase·Oracle로"): 어댑터를 실행 단위(`startRun`/`Run`)로 바꾸고 엔진 훅
  `afterStatementExecuted`를 더함. Oracle은 `statistics_level = ALL` + 커서 계획(`OracleActualPlanProvider`, `OracleActualPlanNode`), Altibase는 드라이버
  `EXPLAIN PLAN = ON`(`AltibaseActualPlanProvider`). 어댑터가 없는 연결에서 SELECT를 `EXPLAIN ANALYZE`로 다시 실행하던 실제 계획을 지우고 토글을 끔(도구 설명에 이유).
- 2026-11-09 실제 계획 포함의 macOS 키 ⇧⌘M(⌘M은 최소화, 앱 변경 #24, `chromite/mac-ui`, [shortcuts.md](shortcuts.md)).
- 2026-11-12 실제 계획 토글 점검(앱 변경 #58, "실제 계획 토글 점검"): Oracle은 커서를 드라이버의 SQL_ID로 찾음(최대 행 수에 닿은 쿼리의 계획이 나오지 않던 것),
  SQL Server는 켜기 전에 SHOWPLAN 권한을 확인(권한 없는 로그인에서 모든 문장이 오류 262로 실패하던 것), 실행 계획 탭의 새 계획 점.
- 2026-11-12 실제 계획이 없는 DB는 토글을 처음부터 끔(앱 변경 #70, "실제 계획이 없는 DB는 토글을 처음부터 끔"): 제공자가 연결 전에 답하는
  `SQLActualPlanSupport`, SQL Server 드라이버 조건을 `SQLServerQueryPlanner.isSupported`로 모음.
