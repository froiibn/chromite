# Swing SQL 편집기 확장: 실행 계획, 쿼리 기록, 서식, 템플릿, 매개변수

Swing 프런트엔드([swing-frontend.md](swing-frontend.md))의 SQL 편집기(쿼리 창)에 DBeaver SWT SQL 편집기
(`org.jkiss.dbeaver.ui.editors.sql`)의 기능을 SQL Server 편집기 사용 방식(SSMS 단축키·화면 배치)으로 옮긴 작업이다.
화면 문구에는 다른 제품 이름을 쓰지 않는다.

## 목적

- 실행 계획: 예상 계획(Ctrl+L)과 실행하면서 받는 실제 계획(Ctrl+M 켜고 실행)을 결과 옆 **Execution plan** 탭에서 본다.
- 쿼리 기록: 쿼리 창에서 실행한 모든 문장을 남기고(재시작 뒤에도), **Query > Query History…** 창에서 찾고 다시 열거나 실행한다.
- SQL 서식과 편집 명령: Format SQL, 대문자·소문자, 주석 넣기·빼기, 들여쓰기.
- 템플릿: 이름 + Tab(`sel` + Tab), Ctrl+K Ctrl+X 목록, Tools > Options > Templates에서 편집.
- 매개변수: `:name`, `${var}`가 있는 문장을 실행하면 값을 묻는다.
- 상태 줄: 서버, 사용자, DB, 경과 시간, 행 수(이미 있음)에 커서 위치(Ln, Col)를 더한다.

## 구조

| 파일 | 역할 |
|---|---|
| `plugins/org.jkiss.dbeaver.model.sql/src/com/seizia/chromite/model/sql/exec/SQLActualPlanProvider.java` | 실행 중 실제 계획을 결과 집합으로 돌려주는 연결의 어댑터(켜는 문장, 끄는 문장, 계획 결과 집합 판별, 계획 해석) |
| `plugins/org.jkiss.dbeaver.ext.mssql/src/com/seizia/chromite/ext/mssql/model/plan/SQLServerQueryPlanner.java` | SQL Server 계획기: `DBCQueryPlanner`(예상, `SET SHOWPLAN_XML`) + `SQLActualPlanProvider`(실제, `SET STATISTICS XML`) |
| `.../plan/SQLServerExecutionPlan.java`, `SQLServerPlanNode.java` | showplan XML을 DBeaver 계획 모델(`DBCPlan`, `DBCPlanCostNode`)로 바꿈 |
| `plugins/org.jkiss.dbeaver.ext.mssql/.../model/SQLServerDataSource.java` | `getAdapter`에서 위 계획기를 돌려줌(Babelfish 제외) |
| `plugins/org.jkiss.dbeaver.registry/.../BasePlatformImpl.java` | `getLocalConfigurationFile`이 작업 공간 설정 폴더를 만듦(매개변수 값 저장) |
| `plugins/com.seizia.chromite.ui.swing/.../ExecutionPlanView.java` | Execution plan 탭: 문장별 계획 트리 표 + 노드 속성 표 |
| `.../SqlPlanExplainer.java` | 계획기로 문장들의 계획을 구하는 Job(예상, ANALYZE) |
| `.../ActualPlanReceiver.java` | 실행 중 계획 결과 집합을 계획으로, 나머지는 결과 표로 보내는 수신기 |
| `.../QueryHistory.java`, `QueryHistoryDialog.java` | 쿼리 기록 저장소(작업 공간 `.metadata/chromite-query-history.jsonl`)와 Query History 창 |
| `.../SqlEditCommands.java` | Format SQL, 대소문자, 주석, 들여쓰기, Edit > Advanced 메뉴, 서식 설정 |
| `.../SqlTemplates.java`, `SqlTemplatesPage.java` | 템플릿 저장소·넣기와 Options의 Templates 탭 |
| `.../SqlParameterDialog.java` | 매개변수 값 대화상자와 엔진의 매개변수 제공자 |
| `SqlEditorPanel`, `MainFrame`, `OptionsDialog`, `QueryStatusLine`, `SqlCompletionPopup` | 위 기능을 잇는 작은 연결 지점(아래 "upstream·기존 코드 대비 변경점") |
| `tools/branding/gen_ui_icons.py`, `icons/ui/*/*/plan.svg`, `history.svg` | 실행 계획·쿼리 기록 아이콘(생성기에 추가, 두 팩 × 두 테마) |

## 단축키와 메뉴

| 기능 | 키 | 메뉴 |
|---|---|---|
| 예상 실행 계획 | Ctrl+L | Query > Display Estimated Execution Plan, 쿼리 창 도구 막대의 계획 단추 |
| 실제 실행 계획 포함 | Ctrl+M (쿼리 창마다 켜고 끔) | Query > Include Actual Execution Plan, 도구 막대의 Actual Plan 토글 |
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
- **실제 계획은 두 방식이다.**
  1. 실행 중 결과 집합으로 받는 연결(SQL Server): 새 어댑터 `SQLActualPlanProvider`. 편집기는 실행 직전에 같은 실행 문맥(같은 JDBC 연결)에서
     `SET STATISTICS XML ON`을 실행하고, 실행 Job이 끝나면(오류·취소 포함) `OFF`를 실행한다. 문장은 **한 번만** 실행된다.
     엔진은 열 정보를 `fetchStart`에서야 알려 주므로 `ActualPlanReceiver`가 먼저 받아 열 이름이
     `Microsoft SQL Server 2005 XML Showplan`이면 원래 값(XML 전체)을 읽어 계획으로, 아니면 결과 표 수신기에 그대로 넘긴다.
     `DBCQueryPlanner`는 "계획만 따로 구하기" API라 이 방식을 나타낼 수 없어서 어댑터를 따로 만들었다.
     계획마다 "Query N" 행의 글자는 계획 XML의 `StatementText`(배치 전체가 아니라 그 문장)이고, 계획 결과 집합은 Messages의 행 수에 넣지 않는다.
  2. 그 밖의 연결: 실행이 끝난 뒤, 성공했고 데이터를 바꾸지 않는 SELECT(`SQLQuery.getType() == SELECT && !isModifying()`)만 계획기에
     `ANALYZE` 설정(`PostgreQueryPlaner.PARAM_ANALYSE`)을 켜고 다시 계획한다. PostgreSQL은 `EXPLAIN (ANALYZE)`로 문장을 다시 실행하고
     계획기가 롤백한다. 이 키를 모르는 계획기(MySQL/MariaDB)는 예상 계획을 돌려주므로, 실제인지는 계획기가 실행한 계획 문장에 `ANALYZE`가
     있는지로 판단해 머리말에 "Estimated"/"Actual"을 그대로 보인다(추측해서 "Actual"이라 쓰지 않음).
- **탭 배치**: 메인 창의 Results / Messages 옆에 Execution plan 탭을 늘 둔다(SQL Server 도구는 계획이 있을 때만 탭을 보이지만,
  탭을 넣고 빼면 `MainFrame`의 탭 번호 처리가 늘어 다른 작업과 겹치기 쉬워 고정 탭으로 했다). 쿼리 창마다 마지막으로 본 탭을 기억한다.
  Ctrl+L은 계획 탭을 앞으로 가져오고, 실제 계획은 결과를 먼저 보인다(계획 탭은 눌러서 본다).
- **그래픽 계획 도면은 미뤘다**(아래 "미룬 일"). 트리 표와 노드 속성 표가 같은 정보를 보인다.
- **열 너비는 내용에 맞춘다.** 처음에는 모든 열을 고정 비율(연산 320, 나머지 110)로 보기 안에 밀어 넣어(`AUTO_RESIZE_SUBSEQUENT_COLUMNS`)
  속성 열이 많은 SQL Server 계획에서 연산 이름이 잘렸다. 계획이 들어올 때 머리글과 보이는 행을 표의 렌더러로 재어(들여쓰기 포함) 맞추고
  (연산 열 200~700px, 속성 열 60~360px, SWT `PlanNodesTree`의 상위 `ObjectListControl.java:520-541`이 내용을 채운 뒤 열을 다시 맞추는 것과 같은 의도),
  열이 보기보다 좁으면 보기를 채우고 넓으면 가로로 스크롤한다(`getScrollableTracksViewportWidth`). 연산 열은 계획 노드만 잰다:
  "Query N" 행은 SQL 글이라 길고, 전체는 오른쪽 상세 표에 보인다.

### 쿼리 기록

- DBeaver 쿼리 관리자(QM)를 다시 쓰지 않은 이유: CE의 `QMUtils.getEventBrowser`는 `QMRegistryImpl`의 기본 이벤트 브라우저(이번 실행의 메모리 기록)이고,
  파일 기록(`QMLogFileWriter`)은 메타데이터 읽기 같은 내부 쿼리까지 섞인 글자 로그라 검색·재실행용으로 다시 읽을 수 없다.
- 그래서 편집기가 실행한 문장만 엔진의 문장 끝 이벤트(`SQLQueryListener.onEndQuery`)에서 `QueryHistory`에 남긴다:
  시각, 연결 ID·이름, 기본 DB/스키마(실행 문맥의 기본값, 서버 호출 없음), 문장, 실행 시간, 행 수(가져온 행 또는 바뀐 행), 결과(성공·실패·취소), 오류 메시지.
  문장은 엔진이 서버에 보낸 글자다: 매개변수는 넣은 값으로 바뀌어 있고(다시 실행하면 같은 값), SQL Server `GO` 배치는 배치 하나가 한 기록이다.
  Query Library가 값을 바인딩한 문장은 서버에 `?`로 가므로 대신 값을 SQL 리터럴로 넣은 글자를 남긴다([swing-query-library.md](swing-query-library.md) "매개변수 바인딩").
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

- SWT의 `SQLEditorParametersProvider` + `SQLQueryParameterBindDialog`를 옮겼다. 엔진의 `SQLScriptContext`에 `SqlParameterDialog.Provider`
  (`SQLParametersProviderBase`)를 넘기면, 스크립트 변수·기본값으로 채워지지 않은 매개변수가 있을 때만 엔진이 부른다(작업 스레드에서 EDT 대화상자를 열고 기다림).
- 값은 SWT와 같이 SQL 글자로 문장에 들어간다(문자열은 따옴표). 이름이 같은 매개변수는 한 줄. 마지막 값은 DBeaver의 `SQLQueryParameterRegistry`
  (`parameter-bindings.xml`)에 저장되어 다음에 미리 채워진다. 단추는 SWT와 같은 Execute, Ignore(이 스크립트 끝까지 매개변수 무시), Cancel.
- 값이 저장되지 않던 근본 원인: `BasePlatformImpl.getLocalConfigurationFile`은 작업 공간 `.metadata/.config/<파일>` 경로를 돌려주는데
  폴더를 만들지 않고, 호출하는 쪽(매개변수 바인딩, 열·필터 상태 등)은 모두 그 파일을 바로 쓴다. SWT 제품에서는 다른 설정 저장이 먼저 폴더를 만들어 드러나지 않았고,
  Swing 제품의 새 작업 공간에서는 `NoSuchFileException`으로 저장되지 않았다(`.metadata/.log`). 호출하는 곳마다 고치지 않고 경로를 주는 쪽에서 폴더를 만든다.
- 합칠 수 있는 것: 쿼리 라이브러리 작업(`query-library`)이 같은 시간에 따로 매개변수 입력을 만들고 있다. 두 작업이 합쳐진 뒤 한 대화상자로 합칠 수 있다.
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

- 그래픽 계획 도면(SQL Server 계획 도구 같은 연산자 그림과 화살표): 트리 표가 같은 정보를 보이므로 미뤘다.
- MySQL/MariaDB 실제 계획: DBeaver MySQL 계획기는 `ANALYZE` 설정을 모른다. MariaDB `ANALYZE FORMAT=JSON` / MySQL `EXPLAIN ANALYZE`를 계획기에 넣는 일은
  `ext.mysql` 수정이 필요해 미뤘다(지금은 예상 계획을 "Estimated"로 표시).
- 템플릿 변수의 같은 이름 동시 편집(linked mode)과 문맥 값 제안(테이블 이름 목록).
- 매개변수 대화상자 통합(쿼리 라이브러리 작업과).
- 매개변수 대화상자의 Cancel은 그 문장을 실행하지 않고 끝내지만, 엔진이 이를 오류나 취소로 알리지 않아 상태 줄은 "Query executed successfully"로 남는다(실행 엔진 `SQLQueryExecuteJob`의 결과 보고를 바꿔야 함).

## 확인 (2026-11-06)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce,product-chromite-swing` → `BUILD SUCCESS`.
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

## 변경 이력

- 2026-11-06 최초 작성: 실행 계획(예상·실제, SQL Server 계획기), 쿼리 기록, SQL 서식·편집 명령, 템플릿, 매개변수 대화상자, 상태 줄 Ln/Col.
- 2026-11-06 실행 계획 표: 열이 너무 좁아 연산 이름이 잘리던 문제. 열을 내용에 맞추고 넘치면 가로 스크롤.
- 2026-11-06 매개변수 대화상자 Cancel 뒤 "Query executed successfully"가 보이던 문제: 엔진이 사용자 취소를 `isCanceledByUser()`로 알림.
