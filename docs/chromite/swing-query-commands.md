# Swing 쿼리 창 문장 명령, `@set` 변수 유지, `@include`, DDL 뒤 탐색기 갱신

## 목적

SWT SQL 편집기에는 F5(스크립트 실행) 말고도 커서 위치 문장 실행(Ctrl+Enter), 커서부터 실행, 행 수 세기, 식 계산, 이전·다음 문장 이동,
현재 쿼리 복사, 편집기 단위로 남는 `@set` 변수와 변수 패널, 다른 스크립트 파일을 실행하는 `@include`(MySQL `SOURCE`),
DDL 뒤 탐색기 갱신이 있었다. Swing 쿼리 창에는 이것들이 없거나 일부만 있었다(대조 감사 B-S2, S4, S5, S10, S11, S40, D-M19).

이 문서는 그 명령들을 Swing 쿼리 창에 옮긴 설계를 적는다.

## 동작

### Query 메뉴

| 메뉴 | 단축키 | 동작 |
|---|---|---|
| Execute Current Statement | Ctrl+Enter | 커서 위치의 문장을 실행한다. 선택이 있으면 선택한 글자를 한 문장으로 실행한다(SWT와 같음). 커서 위치가 `@set` 등 명령이면 그 명령을 실행한다 |
| Execute from Cursor | Alt+P | 커서가 있는 문장의 시작부터 끝까지 F5처럼 실행한다(`GO` 배치 포함) |
| Count Rows | 없음 | 커서 위치 문장을 `SELECT COUNT(*) FROM (…)`로 바꿔 실행한다(ORDER BY는 뺀다, 모델 `SQLQueryTransformerCount`) |
| Evaluate Expression | Ctrl+Alt+' | 선택한 식(없으면 커서 위치 문장)을 `SELECT 식 [FROM dual]`로 실행한다(모델 `SQLQueryTransformerExpression`) |
| Previous Statement / Next Statement | Alt+PageUp / Alt+PageDown | 앞·뒤 문장을 선택한다 |
| Copy Current Statement | 없음 | 커서 위치 문장(또는 선택)을 편집기에 있는 그대로 클립보드에 복사한다 |
| Variables… | 없음 | 이 쿼리 창이 기억하는 `@set` 변수를 보고, 값을 고치고, 지운다 |

- 실행 명령은 F5와 같은 경로다: 실행 제한, 결과 표 변경 버리기 확인, 위험한 문 확인(WHERE 없는 UPDATE/DELETE, TRUNCATE, DROP),
  매개변수 묻기, 결과·Messages·Terminal 탭, 실제 계획, 쿼리 기록이 모두 같다.
- 커서 위치에 문장이 없으면 "There is no statement at the cursor."로 끝난다. 행 수·식에서 커서 위치가 `@set` 같은 명령이면
  "The text at the cursor is a script command, not a query."로 끝난다.
- 연결이 되어 있지 않으면 실행 명령은 F5처럼 먼저 연결한다. 실행하지 않는 명령(이전·다음, 복사)은 연결되어 있으면 그 방언으로, 아니면
  표준 SQL로 문장을 찾는다.

### 단축키는 쿼리 창 안에서만

- F5(Execute), Ctrl+Enter, Alt+P, Ctrl+Alt+', Ctrl+L(예상 계획), Alt+PageUp/PageDown은 키보드 초점이 활성 쿼리 창의 편집기나
  결과(결과 탭: Results, Messages, Execution plan, Terminal)에 있을 때만 동작한다. 다른 곳(Chromite Agent 입력, Query Library,
  Object Explorer와 그 빠른 필터, 대화상자 등)에 초점이 있으면 아무것도 하지 않는다.
- 메뉴 항목을 누르거나(마우스, 메뉴를 열고 Enter) SQL Editor 도구 막대 단추를 누르면 초점과 관계없이 동작한다.

### `@set` 변수 유지

- 한 실행에서 `@set name = value`로 정한 변수는 같은 쿼리 창의 다음 실행에도 남는다. `${name}`, `:name`으로 쓴다.
- 창을 닫으면 사라진다. 연결을 바꿔도 남는다.
- Query > Variables…에서 이름과 값을 보고, 값 칸을 고치고(다음 실행부터 쓰임), Remove / Remove All로 지운다. 실행 중에는 열리지 않는다.

### `@include`

- `@include file.sql`(MySQL·MariaDB는 `SOURCE file.sql`도)은 그 파일의 문장을 같은 세션, 같은 트랜잭션에서 그 자리에서 실행한다.
  포함한 파일 안의 `@set`, `@include`, 매개변수, `GO` 배치도 쓸 수 있다.
- 상대 경로는 그 명령이 있는 스크립트 파일의 폴더 기준이다. 쿼리 창의 문장은 창의 파일 폴더, 포함한 파일 안의 `@include`는 그 파일의 폴더.
  저장하지 않은 쿼리 창에서 상대 경로를 쓰면 "needs a saved script" 오류다(절대 경로는 된다).
- 파일 이름의 `${var}`는 변수로 바꾸고, `'…'`, `"…"`, 방언의 식별자 따옴표로 감싼 이름은 벗긴다.
- 파일이 없으면 `File '…' not found` 오류로 그 자리에서 멈춘다(오류 처리 설정이 "건너뛰고 계속"이면 다음 문장으로).
- 포함 고리(스크립트가 자기 자신이나 자기를 포함한 파일을 다시 포함)는 `Include loop: a.sql > b.sql > a.sql`로 거절한다.
  같은 파일인지는 실제 파일로 비교한다(`./a.sql`, 대소문자, 기호 링크가 달라도 같은 파일).
- 포함한 파일의 위험한 문은 끼우기 전에 묻는다(목록 줄 앞에 파일 이름: `danger.sql, Line 2: DELETE without a WHERE clause`). Cancel이 기본이다.
- 포함한 파일 문장의 오류는 그 파일의 줄로 보인다: `Msg 0, State 42P01, File err.sql, Line 3, Column 15`.

### DDL 뒤 탐색기 갱신

- 쿼리 창에서 CREATE / ALTER / DROP / RENAME이 성공하면 실행이 끝난 뒤 Object Explorer가 바뀐 개체의 스키마(또는 DB, 연결)를 다시 읽는다.
  F5로 새로 고칠 필요가 없다.
- 수동 커밋 세션이면 커밋하거나 롤백해 트랜잭션이 끝난 뒤 다시 읽는다(커밋 전 DDL은 탐색기의 메타데이터 세션에서 보이지 않는다).
- 탐색기에서 아직 펼치지 않은 가지는 서버를 부르지 않고 모델 캐시만 비운다. 펼칠 때 새로 읽는다.
- 두 부분 이름(`a.t`)의 `a`는 엔진의 이름 구조로 읽는다. SQL Server·PostgreSQL·Oracle에서는 스키마, MySQL/MariaDB에서는 DB다
  (아래 "두 부분 이름 (#108)"). 그래서 SQL Server의 `CREATE TABLE dbo.t`는 `dbo` 스키마만 다시 읽는다.
- 연결 전체를 다시 읽어도(`CREATE DATABASE`, `DROP DATABASE`, 갈래를 모르는 개체의 DDL) 다른 쿼리 창의 세션과 트랜잭션은 그대로다. 2026-11-13 전에는
  PostgreSQL에서 연결의 세션이 모두 닫혀 다른 창의 트랜잭션이 묻지 않고 롤백되었다([connection-lifecycle.md](connection-lifecycle.md) "연결 새로 고침과 세션").

## 구조

| 위치 | 역할 |
|---|---|
| `model.sql` `commands.SQLCommandInclude` (다시 만듦) | `@include`·`SOURCE` 처리기: 파일 찾기(상대 경로, 변수, 따옴표), 포함 고리 거절, 읽기(BOM·UTF-8·OS 기본 문자 집합), 연결 방언으로 나누기(`SQLScriptBatches`), 포함 파일의 스크립트 문맥 만들기 |
| `model.sql` `SQLIncludedScript` (새로 만듦) | 포함한 파일의 문장, 글, 실제 경로, 스크립트 문맥. `SQLScript`를 상속해 `SQLControlResult.transform`으로 돌려준다 |
| `model.sql` `exec.SQLQueryExecuteJob` | 포함한 문장을 남은 문장 앞에 끼워 같은 루프로 실행, 문장마다 그 파일의 스크립트 문맥, `getCurrentScriptContext`, 다시 실행하면 처음 문장부터 |
| `model.sql` `exec.SQLQueryExecuteHandler` | `confirmIncludedScript`: 끼우기 전에 프런트엔드가 검사(기본 true) |
| `model.sql` `exec.SQLScriptProcessor` | 작업(Task)의 스크립트 실행도 포함한 문장을 그 파일의 문맥으로 실행 |
| `model.sql` `SQLScriptContext` | `getParametersProvider`: 포함 파일 문맥에도 같은 매개변수 제공자 |
| `model.sql` `plugin.xml`, `ext.mysql` `plugin.xml` | `sqlCommand` 확장 `include`, `mysql.source` |
| `ui.swing` `SqlQueryCommands` (새로 만듦) | 실행 방식(`ExecuteMode`)별 문장 찾기(준비 Job), 커서 위치 문장, 이전·다음 선택 |
| `ui.swing` `SqlEditorPanel` | `execute(ExecuteMode)`, 창의 변수 문맥(`windowVariables`), 실행 문맥에 창의 파일, 포함 파일 검사와 오류 줄, 복사·이동·변수 명령, DDL 수집과 갱신 시점, 편집기의 Ctrl+Enter 풀기 |
| `ui.swing` `SqlMetadataRefresh` (새로 만듦) | 성공한 문장의 바뀐 개체 그릇 모으기, 트랜잭션이 끝난 뒤 탐색기 노드 다시 읽기 |
| `model.sql` `SQLMetadataRefreshTargetResolver` (upstream, 2026-11-13 고침) | 바뀐 개체를 담는 가장 좁은 그릇(스키마, DB, 연결 전체) 정하기. 두 부분 이름은 이름 구조로 읽음(#108) |
| `ui.swing` `QueryVariablesDialog` (새로 만듦) | Query > Variables… |
| `ui.swing` `SqlExecutionSafety` | 위험한 문 목록에 포함 파일 이름 |
| `ui.swing` `MainFrame` | Query 메뉴 항목과 단축키, 켜짐 |
| `ui.swing` `internal.QueryCommandMessages` (새로 만듦) | 화면 문구(영어, 한국어) |

## 설계 결정과 이유

- **실행 명령은 F5 경로 하나에 모드만 더한다.** 안전 확인, 매개변수, 결과 표시, 트랜잭션 처리를 명령마다 다시 만들지 않는다.
  문장 찾기는 연결의 방언이 필요하므로(구분자, 블록, `GO`) 연결한 뒤 준비 Job에서 한다.
- **문장 찾기는 SWT와 같은 모델 함수다.** `SQLScriptParser.extractActiveQuery`(선택은 한 문장), `extractQueryAtPos`, `extractNextQuery`.
  빈 줄 구분(Blank line is statement delimiter), 블록, 구분자 설정이 SWT 편집기와 같게 동작한다.
- **Ctrl+Enter는 편집기의 단어 완성에서 뺐다.** RSyntaxTextArea는 Ctrl+Enter를 `RTA.DumbCompleteWordAction`에 묶는다(4.0.1). 쿼리 편집기의
  입력 맵에서 `none`으로 풀어 메인 창 메뉴 단축키가 받게 했다. 자동완성은 Ctrl+Space가 있다.
- **이전·다음 문장은 Alt+PageUp/PageDown.** SWT는 Alt+Up/Down이었지만 RSyntaxTextArea가 줄 옮기기(`RTA.LineUpAction`/`LineDownAction`)에 쓴다.
- **`@set` 변수는 창 단위, 메모리에만.** SWT는 편집기마다 전역 스크립트 문맥을 두고 실행 문맥의 부모로 넘겼다. 같은 구조다.
  SWT는 변수를 드라이버별로 작업 공간에 저장해 다른 편집기·다음 시작에도 불러왔지만, 그러면 한 창의 변수가 같은 드라이버의 다른 창에
  나타난다. 대조 감사 행의 요구는 "편집기 단위 유지"라 저장하지 않았다. 필요하면 나중에 더한다.
- **변수 패널 대신 대화상자.** 결과 영역의 탭(Results, Messages, Execution plan, Terminal)은 메인 창이 나눠 쓰고, 변수는 가끔 본다.
- **`@include`는 편집기를 열지 않고 엔진 안에서.** SWT는 포함한 파일을 새 SQL 편집기로 열어 실행하고 끝나기를 기다렸다(`ui.editors.sql`, 삭제됨).
  Swing에는 그런 편집기 연결이 없고, 엔진 안에서 처리하면 오류 처리·취소·결과·쿼리 기록이 한 실행에 모인다. 명령 처리기는 모델 번들에 있어
  다른 실행기(작업의 스크립트 실행 `SQLScriptProcessor`)에서도 쓴다.
- **끼우기(엔진)와 재귀(작업 실행기).** `SQLQueryExecuteJob`은 문장마다 오류 질문·건너뛰기·취소·진행을 한 루프에서 처리하므로 포함한 문장을
  그 루프에 끼운다. `SQLScriptProcessor`는 원래 재귀(`SQLScript`)라 그 자리에서 문맥만 바꿔 실행한다.
- **상대 경로에 현재 폴더를 쓰지 않는다.** SWT는 스크립트 폴더에서 찾지 못하면 프로그램의 현재 폴더에서도 찾았다. 데스크톱 앱의 현재 폴더는
  설치 폴더 등이라 뜻이 없고, 같은 이름의 엉뚱한 파일을 실행할 수 있다.
- **포함 고리는 실제 파일로 비교한다.** SWT는 경로 문자열을 비교해 `./a.sql`과 `a.sql`을 다른 파일로 보아 끝없이 포함할 수 있었다.
  `Files.isSameFile`(Windows는 볼륨·파일 번호)로 비교한다. 같은 파일을 여러 번 포함하는 것(고리가 아닌 다이아몬드)은 허용한다.
- **포함 파일도 위험한 문을 묻는다.** 쿼리 창의 확인은 실행 전에 창의 문장만 본다. 포함한 파일의 문장은 실행 중에 읽으므로 엔진이 끼우기 전에
  프런트엔드에 넘긴다(`confirmIncludedScript`). 확인을 끈 사용자에게는 묻지 않는다(같은 설정).
- **DDL 갱신은 트랜잭션이 끝난 뒤.** 위 "DDL 뒤 탐색기 갱신". 갱신 시점은 상태 줄의 열린 트랜잭션 표시(1초마다, 실행이 끝날 때)가
  "없음"일 때다. 그래서 커밋을 어디서 하든(Query 메뉴, 상태 줄, 유휴 트랜잭션 알림, 스크립트의 COMMIT) 따라간다.
- **실행 단축키는 초점이 쿼리 창에 있을 때만(`MainFrame.isShortcutFromOutside`).** 메뉴 단축키는 메인 창 어디서나 동작하므로(Swing 메뉴 막대의
  `WHEN_IN_FOCUSED_WINDOW`), 많은 채팅 입력이 보내기로 쓰는 Ctrl+Enter를 Chromite Agent 입력칸에서 누르면 활성 쿼리 창의 문장이 실행되었다
  (F5도 같았다). 글자 칸마다 키를 막으면 새 칸이 생길 때마다 빠지므로, 실행 동작 쪽에서 막는다. 지금 처리 중인 이벤트
  (`EventQueue.getCurrentEvent`)가 그 동작의 단축키와 같은 키 이벤트이면 단축키로 보고 초점 위치를 본다. 마우스 클릭, 도구 막대 단추, 열린 메뉴에서
  Enter로 고르기는 다른 이벤트라 그대로 동작한다. 쿼리 창은 편집기(`SqlEditorPanel`)와 활성 창의 결과를 보이는 결과 탭(`resultTabs`, 2026-11-08부터 활성 창의 탭 줄을 끼우는 `resultsArea`)이다.
  Commit / Roll Back(Ctrl+Alt+Shift+K/R)은 실행 명령이 아니라 바꾸지 않았다.
- **화면 문구는 새 묶음(`QueryCommandMessages`).** 같은 때 쿼리 창을 고치는 다른 작업과 `EditorMessages`를 함께 고치지 않으려는 것이다(SQL 터미널의
  `TerminalMessages`와 같은 방식). 모델의 오류 문구는 영어다(예외 문구 규칙).

### 두 부분 이름 (#108)

**결함(#89 작업 중 발견, 2026-11-13):** SQL Server에서 `CREATE TABLE dbo.t`처럼 두 부분 이름의 DDL을 실행하면 연결 전체를 다시 읽었다(DB 목록을 비우고 다시 읽기,
읽어 둔 모든 DB의 스키마·개체 목록 다시 읽기). SQL Server에서 가장 흔히 쓰는 이름 모양이라 거의 모든 DDL이 그랬다.

**근본 원인:** upstream `SQLMetadataRefreshTargetResolver.doCreateTarget`(고치기 전 113–118행)이 두 부분 이름의 첫 부분을 "세션이 스키마는 못 바꾸고
카탈로그는 바꿀 수 있으면"(`!supportsSchemaChange() && supportsCatalogChange()`) 카탈로그로 읽었다. 세션이 무엇을 바꿀 수 있는지는 이름이 어떻게 생겼는지와 다른
질문이다. SQL Server 세션은 스키마를 바꿀 수 없지만(`SQLServerExecutionContext.java:115`) 이름은 DB > 스키마 > 개체다. 그래서 `dbo`를 DB로 찾고, 없으니
`SqlMetadataRefresh.resolveTarget`(237행)이 연결 전체를 대상으로 삼았다. 같은 원인으로 `CREATE TABLE other_schema.t`도 연결 전체였다.

**고침:** 두 부분 이름은 데이터 원본의 맨 위가 카탈로그이고(`getPrimaryChildType`이 `DBSCatalog`) 카탈로그 아래에 스키마가 없을 때(기본 카탈로그의
`getPrimaryChildType`이 `DBSSchema`가 아님)만 `catalog.object`로, 그 밖에는 `schema.object`로 읽는다(`hasCatalogsWithoutSchemas`). Object Explorer가 보여 주는
구조이고, `ContextDefaultObjectsReader.run`(120–140행)이 DB 선택을 정할 때 쓰는 것과 같은 모델 사실이다. 기본 카탈로그가 없으면(MySQL에서 DB를 고르지 않음)
카탈로그 목록의 첫 카탈로그로 본다. 이름에 맞는 그릇이 정말 없을 때 연결 전체를 읽는 `resolveTarget`의 보수적 동작은 그대로 두었다.
다른 기준은 맞지 않았다: 방언의 `getSchemaUsage`는 MySQL도 `USAGE_ALL`(`MySQLDialect.java:454`)이고, 기본 스키마가 있는지는 연결 상태(캐시)에 따라 null일 수 있다.

**엔진별 `a.b`의 `a`(코드 읽기, "실행"은 GCP 시험 VM에서 확인한 것):**

| 엔진 | 이름 구조(근거) | 고치기 전 | 고친 뒤 |
|---|---|---|---|
| SQL Server | `SQLServerDatabase`(`SQLServerDataSource.java:606`) > `SQLServerSchema`(`SQLServerDatabase.java:587`) | DB(틀림, 연결 전체) | 스키마(실행) |
| MySQL·MariaDB, TiDB, OceanBase MySQL | `MySQLCatalog`(`MySQLDataSource.java:513`, `TiDBMySQLDataSource.java:128`, `OceanbaseMySQLDataSource.java:87`) > `MySQLTable`(`MySQLCatalog.java:424`) | DB | DB(같음, MariaDB 실행; MySQL 8.4는 드라이버 파일이 없어 실행 못 함) |
| PostgreSQL(Greenplum 등) | `PostgreDatabase`(`PostgreDataSource.java:561`) > `PostgreSchema`(`PostgreDatabase.java:898`), 스키마를 바꿈 | 스키마 | 스키마(같음, 실행) |
| Oracle, DB2, Exasol, Altibase, YashanDB | 맨 위가 스키마(`OracleDataSource.java:683`, `DB2DataSource.java:475`, `ExasolDataSource.java:504`, `AltibaseDataSource.java:288`, `YashanDBDataSource.java:130`) | 스키마 | 스키마(같음, Oracle 실행) |
| Snowflake | Generic 카탈로그 > 스키마(`GenericDataSource.java:729`, `GenericCatalog.java:157`), 스키마를 바꿈(`SnowflakeExecutionContext.java:91`) | 스키마 | 스키마(같음) |
| Generic(카탈로그와 스키마 둘 다: Databricks 등, 드라이버에 따름), DuckDB | 같은 Generic 구조. `GenericExecutionContext.supportsSchemaChange`는 카탈로그가 있으면 거짓(250행~), `supportsCatalogChange`는 카탈로그 선택을 지원하면 참, DuckDB는 늘 참(`DuckDBExecutionContext.java:48`) | 카탈로그를 바꿀 수 있으면 DB(틀림), 아니면 스키마 | 스키마 |
| Doris, StarRocks | `DorisCatalog` > `DorisDatabase`(`GenericSchema`, `DorisCatalog.java:103`), StarRocks 같음(`StarRocksCatalog.java:103`), 스키마를 바꿈 | 스키마(그 엔진의 DB) | 같음 |
| Generic(카탈로그만), ClickHouse(드라이버 버전에 따라 카탈로그 또는 스키마), Cloudflare D1 | `GenericCatalog` 아래 표(`GenericCatalog.java:160–164`), D1 `CloudflareD1Catalog` > 표(`CloudflareD1Catalog.java:176`) | 카탈로그를 바꿀 수 있으면 DB, 아니면 스키마 | DB(구조대로) |
| SQLite, Firebird, Timeplus | 맨 위가 표(`SQLiteDataSource.java:194`, `FireBirdDataSource.java:178`, `TimeplusDataSource.java:77`) | 스키마 | 스키마(같음; 이름에 맞는 그릇이 없어 연결 전체인 것도 같음) |

D1·ClickHouse·Generic·DuckDB·Snowflake·Doris는 실행해 보지 않았다. D1의 SQL에서 `main.t`(SQLite 스키마)는 고친 뒤 없는 DB `main`으로 읽혀 연결 전체를 다시 읽는다
(고치기 전에는 DB를 고른 연결에서 그 DB로 좁혀졌다). 모델의 구조(D1 DB 아래 바로 표)를 따른 결과이고, 엔진 이름으로 나누지 않았다.

**측정(SQL Server 2022, DB 네 개가 보이는 연결, Object Explorer에서 DB 세 개의 `Schemas > dbo > Tables`를 펼침, 쿼리 창의 DB `chr_ddl_refresh`):**
"Refresh metadata after DDL" 작업 시간, 다시 읽은 탐색기 노드(`DBNEvent`), 그동안 연결에 보낸 질의(QM), DB 목록 캐시가 비었는지를 기록했다.

| 문장 | 고치기 전 | 고친 뒤 |
|---|---|---|
| `CREATE TABLE dbo.t` | 대상 `Schema(dbo, dbo)` → 연결 전체, DB 목록 비움, 163 ms, 질의 10개(`sys.databases`, 읽어 둔 DB 3개의 `sys.schemas`·`sys.all_objects`, 서버 속성, 에이전트 상태). 새 표가 `dbo > Tables`에 나타나지 않음 | `Schema(chr_ddl_refresh, dbo)` → `dbo` 노드, 18 ms, 질의 2개(`db_name()`, 그 DB의 `sys.all_objects`). 새 표가 나타남 |
| `ALTER TABLE dbo.t ADD …` / `CREATE VIEW dbo.v` / `CREATE PROCEDURE dbo.p` | 연결 전체, 168 / 165 / 164 ms | `dbo`, 16 / 16 / 17 ms |
| `DROP TABLE dbo.t` | 연결 전체, 165 ms | `dbo`, 16 ms, 표가 사라짐 |
| `CREATE TABLE t` | `dbo`, 17 ms | 같음 |
| `CREATE TABLE chr_ddl_refresh.dbo.t`(현재 DB) / `chr_ddl_refresh_b.dbo.t`(다른 DB) | 그 DB의 `dbo`, 16 ms | 같음 |
| `CREATE TABLE other_schema.t` | `Schema(other_schema, dbo)` → 연결 전체, 165 ms | `other_schema` 노드, 17 ms, 새 표가 나타남 |
| `CREATE SCHEMA s` | DB `chr_ddl_refresh`, 88 ms | 같음(57 ms). 빈 스키마는 연결 설정 "모든 스키마 보기"가 꺼져 있어 보이지 않음(전과 같음) |

펼친 노드는 고치기 전에도 접히지 않았다(Swing Object Explorer는 연결 노드의 REFRESH에서 펼침을 지킨다, 14개 → 14개). 연결 전체를 다시 읽을 때 눈에 보이던
결과는 새 표가 나타나지 않는 것이었다(연결 노드의 REFRESH는 연결 목록만 다시 그린다, `ObjectExplorerPanel.connectionUpdated`).
고친 뒤 연결 Refresh 뒤와 다시 연결 뒤에도 `CREATE TABLE dbo.t`는 `dbo`만(18 ms, 16 ms), `CREATE DATABASE`·`DROP DATABASE`는 전처럼 연결 전체, 그 뒤의
`CREATE TABLE dbo.t`는 다시 `dbo`만이었다. 쿼리 창 DB 선택(#89의 두 경우: 펼치지 않음, 한 번에·문장마다, 연결·Refresh·다시 연결 뒤 각 10번)은 모두 목록의 객체였다.
MariaDB `CREATE TABLE db.t`(다른 DB, 현재 DB, Refresh 뒤, 다시 연결 뒤)는 그 DB만(DB를 고르지 않은 창에서 계산한 대상도 그 DB), PostgreSQL `CREATE TABLE s.t`·`CREATE TABLE t`(`search_path` 바꾼 뒤 포함)는 그 스키마만,
`CREATE SCHEMA`는 DB만, Oracle `CREATE TABLE SCHEMA.T`·`CREATE VIEW`·`DROP TABLE`은 그 스키마만 다시 읽었다(고친 빌드, 고치기 전과 같은 대상).
증거 `D:\Tools\_scratch\ddl-refresh-target\run\`(`before-ms.log`, `after-ms*.txt.log`, `after-my.txt.log`, `after-pg-ora.txt.log`).

**같은 규칙을 쓰는 곳:** 이 해석기를 부르는 곳은 `SqlMetadataRefresh.collect` 하나다(검색). Chromite Agent의 개체 찾기(`MetadataTools.objectDdl`)는 자기 이름
해석(`AgentNames.resolve`, 엔진 종류별)을 쓰고 DDL 뒤 새로 고치지 않는다. 작업의 스크립트 실행기(`SQLScriptProcessor`)는 DDL 뒤 메타데이터를 다시 읽지 않는다.

## upstream 대비 변경점

- SWT `SQLCommandInclude`(ui.editors.sql)는 SWT 프런트엔드와 함께 삭제되었다. 모델 번들에 같은 이름으로 다시 만들었다(편집기 대신 엔진, 현재 폴더 찾기 없음,
  실제 파일로 고리 비교, 파일 문자 집합 판별). `IncludedScriptFileEditorInput`, "포함한 스크립트 실행 뒤 닫기" 설정은 없다.
- `SQLQueryExecuteJob`(SWT `SQLQueryJob`에서 옮긴 Chromite 엔진)이 포함 문장을 끼워 실행한다. `SQLScriptProcessor`는 포함 문장을 그 파일 문맥으로 실행한다.
- SWT 변수 패널(`SQLVariablesPanel`)과 드라이버별 변수 저장은 옮기지 않았다(대화상자, 창 단위 메모리).
- SWT `SQLMetadataRefreshCoordinator`·`SQLMetadataRefreshTransactionCoordinator`의 동작을 Swing 클래스 하나(`SqlMetadataRefresh`)로 새로 썼다.
  SWT의 갱신 알림·끄기 설정(`SHOW_METADATA_REFRESH_NOTIFICATION`)은 없다.
- upstream `SQLMetadataRefreshTargetResolver`의 두 부분 이름 해석을 세션이 바꿀 수 있는 것 대신 이름 구조로 바꿨다(위 "두 부분 이름 (#108)").
- 단축키: 이전·다음 문장이 Alt+Up/Down에서 Alt+PageUp/PageDown으로 바뀌었다(위 결정). "새 탭에 결과"(Ctrl+\) 명령은 옮기지 않았다(B-S3).

## 확인

회귀 테스트는 만들지 않았다(요청 없음). 앱 사본에서 확인한 내용은 아래 "확인 기록"에 있다.

### 확인 기록 (2026-11-08, Windows, 이 브랜치 빌드, 작업 공간 사본, Docker 테스트 DB의 임시 스키마·DB, 프로세스 안 드라이버로 조작)

| 확인 | 결과 |
|---|---|
| PostgreSQL, 네 문장 창에서 둘째 문장에 커서, Execute Current Statement | 그 문장만 실행(결과 표 1개, `second`) |
| 편집기에 Ctrl+Enter 키 이벤트 | 편집기 입력 맵의 Ctrl+Enter가 `none`, 메뉴 단축키가 받아 커서 위치 문장 실행 |
| 셋째 문장에 커서, Execute from Cursor | 셋째·넷째 문장 실행(결과 표 2개) |
| Next, Next, Previous / Copy Current Statement | 둘째 → 셋째 → 둘째 문장 선택. 넷째 문장이 클립보드에, 상태 줄 `Statement copied` |
| 문장 일부를 선택하고 Execute Current Statement | 선택한 글자를 한 문장으로 실행 |
| `generate_series(1, 7)` 문장에서 Count Rows | `count = 7` |
| `2 * 21` 선택 / 커서만 두고 Evaluate Expression | 둘 다 `42` |
| `@set` 줄에서 Count Rows | "The text at the cursor is a script command, not a query." (한국어 "커서 위치의 글자는 쿼리가 아니라 스크립트 명령입니다.") |
| 주석뿐인 창에서 Execute Current Statement | "There is no statement at the cursor." |
| `@set keep_me = 7` 줄만 실행, 다음 실행에서 `SELECT ${keep_me}, :keep_me` 문장만 실행(두 번) | 두 번 모두 `7`, `7`, 대화상자 없음 |
| Variables… 값 칸을 8로 고침 → 실행 / Remove All → 실행 | `8`, `8` / 변수가 없어져 매개변수 대화상자가 뜸(Cancel로 끝냄) |
| 저장한 창(`main_pg.sql`)에서 `@set` + `@include child_pg.sql` → `@include sub/grand_pg.sql` → `@include ../leaf_pg.sql` | 6개 결과 표, 포함 파일에서 정한 `${qc_child}`를 손자 파일이 씀, 창 변수에 `qc_val`, `qc_child` |
| 같은 실행의 `CREATE TABLE p4_qc.inc_t`(포함 파일) | 미리 펼친 탐색기 `p4_qc > Tables`에 실행 뒤 `inc_t`가 나타남 |
| `loop_a.sql` → `loop_b.sql` → `./LOOP_A.sql`(대소문자 다름) | `Include loop: loop_a.sql > loop_b.sql > loop_a.sql.` a, b 결과 표는 남음 |
| 저장하지 않은 창에서 `@include 'D:\…\loop_a.sql'`(절대 경로, 작은따옴표) | 같은 고리 거절 |
| 없는 파일 | `File 'D:\…\nothing_here.sql' not found`, 앞 문장 결과는 남고 뒤 문장은 실행하지 않음 |
| 저장하지 않은 창에서 상대 경로 | "The relative file name 'child_pg.sql' needs a saved script to start from. …" |
| 포함 파일 셋째 줄의 오류 | `Msg 0, State 42P01, File err_pg.sql, Line 3, Column 15` (한국어 `메시지 0, 상태 42P01, 파일 err_pg.sql, 줄 3, 열 15`) |
| 포함 파일의 WHERE 없는 DELETE | 위험한 문 확인에 `-- danger_pg.sql, Line 2: DELETE without a WHERE clause`, 기본 단추·초점 Cancel, Cancel → 취소, 행 2개 그대로 |
| 수동 커밋으로 바꾼 창 세션에서 `CREATE TABLE p4_qc.tx_t` | 상태 줄 `Manual commit`, `Open transaction`, 3초 뒤에도 탐색기에 없음. Commit 뒤 `tx_t`가 나타남 |
| 자동 커밋에서 `DROP TABLE p4_qc.tx_t`(DROP 확인 → Execute) | 실행 뒤 탐색기에서 사라짐 |
| SQL Server, `GO` 스크립트에서 커서 위치 문장 / 커서부터 | 그 배치의 문장만 / 둘째 배치부터 끝까지(포함 파일 안의 `DECLARE @x` 배치가 이어서 동작해 `doubled = 6`, `${ms_val}` = 5) |
| 같은 창 다음 실행에서 `SELECT ${ms_val}`만 | `5` |
| 포함 파일(`@include [D:\…\err_ms.sql]`, 대괄호 따옴표)의 오류 | `Msg 208, State S0002, File err_ms.sql, Line 3` |
| `CREATE TABLE dbo.inc_ms`(포함 파일) | 미리 펼친 `p4_qc_db > dbo > Tables`에 `inc_ms`가 나타남 |
| Count Rows(ORDER BY가 있는 SELECT) / `GETDATE()` 선택 후 Evaluate Expression | `1` / 현재 시각 |
| `GO`를 사이에 둔 다음 문장 이동 | `SELECT … ORDER BY id\nGO`, 그다음 문장 선택 |
| `@include sub\..\loop_ms_a.sql`(자기 자신) | `Include loop: loop_ms_a.sql > loop_ms_a.sql.` |
| MariaDB, `SOURCE child_maria.sql`(D-M19) | CREATE·INSERT·SELECT가 같은 실행에서 돌고 `maria_count = 3`, 뒤 문장 계속 |
| (단축키 고친 뒤) Chromite Agent 입력에 초점, Ctrl+Enter·Alt+P·F5 키 이벤트 | 아무것도 실행하지 않음(상태 줄 그대로, 결과 없음) |
| (단축키 고친 뒤) 편집기에 초점, 같은 키 / 초점이 Agent 입력일 때 메뉴 동작 실행(클릭과 같은 경로) | 전처럼 실행 / 실행 |
| 한국어(`-nl ko`) | Query 메뉴 `현재 문장 실행`·`커서부터 실행`·`행 수 세기`·`식 계산`·`이전 문장`·`다음 문장`·`현재 문장 복사`·`변수…`, 대화상자 `변수 - vars_pg.sql`, `[지우기] [모두 지우기] [닫기]`, 상태 줄 `문장을 복사했습니다`, 위험한 문 목록 `danger_pg.sql, 줄 2: WHERE 절이 없는 DELETE` |

임시 개체(PostgreSQL 스키마 `p4_qc`, SQL Server·MariaDB DB `p4_qc_db`)는 확인 뒤 지웠다.

## 변경 이력

- 2026-11-08 최초 작성: 문장 명령(현재 문장, 커서부터, 행 수, 식, 이전·다음, 복사), 창 단위 `@set` 변수와 Variables 대화상자, `@include`·`SOURCE`,
  DDL 뒤 탐색기 갱신
- 2026-11-08 실행 단축키(F5, Ctrl+Enter, Alt+P, Ctrl+Alt+', Ctrl+L, Alt+PageUp/PageDown)는 키보드 초점이 쿼리 창의 편집기나 결과에 있을 때만 동작.
  다른 글자 칸(Chromite Agent 입력 등)에서 누르면 활성 쿼리 창을 실행하던 것을 고침
- 2026-11-08 결과 탭(앱 변경 #7): 초점 검사의 결과 쪽이 메인 창의 결과 탭(`resultTabs`)에서 활성 창의 탭 줄을 끼우는 `resultsArea`로 바뀜
- 2026-11-13 두 부분 이름(`dbo.t`)을 엔진의 이름 구조로 읽음(앱 변경 #108): SQL Server의 `CREATE TABLE dbo.t`가 연결 전체 대신 `dbo` 스키마만 다시 읽는다.
  "두 부분 이름 (#108)" 절 추가. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 쿼리 창 DDL 뒤 Object Explorer 갱신(모든 DB 종류, 특히 Generic·DuckDB·D1의 두 부분 이름).
- 2026-11-13 "DDL 뒤 탐색기 갱신"에 연결 전체 새로 고침이 세션을 닫지 않는다는 줄(앱 변경 #109, `chromite/pg-refresh-sessions`, 고친 곳은 `PostgreDataSource`)
