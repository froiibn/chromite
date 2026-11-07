# Swing 개체 정의 편집 (Modify, Script as ALTER, New View/Procedure/Function)

## 목적

SSMS를 매일 쓰는 사용자가 가장 먼저 찾는 기능인 **Modify**(저장 프로시저·함수·뷰·트리거를 고치기)를 Swing 프런트엔드에 넣는다.
Object Explorer에서 개체를 오른쪽 클릭해 **Modify**를 고르면 그 개체의 정의가 **실행할 수 있는 변경 스크립트**(SQL Server `ALTER …`,
PostgreSQL·Oracle `CREATE OR REPLACE …`, MySQL/MariaDB `DROP … IF EXISTS` + `CREATE …`)로 새 쿼리 창에 열린다.
고친 뒤 F5로 실행하면 DB에 적용되고 Object Explorer의 그 개체를 다시 읽는다.
뷰·프로시저·함수 폴더의 **New View… / New Procedure… / New Function…**은 방언에 맞는 CREATE 템플릿을 같은 방식으로 연다.

SWT 버전에서는 개체 편집기의 소스 탭(`SQLSourceViewer` 계열)에서 고치고 Ctrl+S로 저장한다. Swing에서는 SSMS처럼 스크립트를 쿼리 창에서
실행하는 흐름으로 바꾸되, 스크립트를 만드는 부분은 SWT 저장과 같은 모델 API를 쓴다.

## 화면

| 위치 | 항목 | 대상 |
|---|---|---|
| Object Explorer 오른쪽 클릭 | **Modify** | 저장된 프로시저·함수·뷰(PostgreSQL 구체화된 뷰 포함)·트리거·패키지(Oracle) 중 드라이버가 정의를 고칠 수 있는 것 |
| Object Explorer 오른쪽 클릭 | Script as ▸ **ALTER To** ▸ New Query Editor Window / Clipboard | Modify와 같음. New Query Editor Window는 Modify와 같은 창을 연다 |
| Object Explorer 오른쪽 클릭 | **New View…** | 뷰 폴더(PostgreSQL 구체화된 뷰 폴더 제외) |
| Object Explorer 오른쪽 클릭 | **New Procedure…** / **New Function…** | 스키마·DB 아래 루틴 폴더. 프로시저와 함수를 한 목록에 두는 드라이버(SQL Server·PostgreSQL·MySQL)는 둘 다, Oracle은 Procedures 폴더에 New Procedure…, Functions 폴더에 New Function… |

- 메뉴 순서는 SSMS처럼 New Table… 아래에 New View… / New Procedure… / New Function…, Design 아래에 Modify, Script as 안에서 CREATE To 다음에 ALTER To다.
  다른 탐색기 메뉴 항목처럼 늘 보이고 맞지 않는 노드에서는 꺼진다.
- 열리는 창은 보통 쿼리 창(`SQLQueryN.sql - 연결`)이다. 만든 직후에는 고침 표시가 없고, 고치면 `*`가 붙으며, 닫을 때·끝낼 때 저장 확인과
  File > Save / Save As가 다른 쿼리 창과 같다(SSMS의 Modify 창도 보통 쿼리 창이다).
- 실행(F5)이 끝날 때마다(오류·취소 포함) Modify 창은 그 개체 노드를, New 창은 그 폴더 노드를 DB에서 다시 읽는다.
  만든 개체가 트리에 나타나고, 고친 개체의 DDL 보기·Script as가 새 정의를 쓴다.
- 화면 문구는 `SourceEditMessages`(영어 기본, `_ko` 한국어)다. 메뉴: 수정, ALTER 대상, 새 뷰…, 새 프로시저…, 새 함수…, 진행 제목 ALTER로 스크립팅.

## 구조

| 클래스 | 역할 |
|---|---|
| `SourceScripts` (새) | UI 없는 부분. Modify 대상 판단, ALTER 스크립트 만들기, 실행 문맥의 DB/스키마 맞추기, 템플릿 만들기, 실행 뒤 노드 다시 읽기 |
| `SourceEditCommands` (새) | 메뉴 동작(Modify, ALTER To, New View/Procedure/Function)과 켜고 끄기. 진행 대화상자에서 스크립트를 만들고 메인 창에 창을 열어 달라고 한다 |
| `internal.SourceEditMessages` (새) | 화면 문구 묶음(`.properties`, `_ko.properties`) |
| `ObjectExplorerPanel` (연결 지점) | `SourceEditCommands`를 만들어 오른쪽 클릭 메뉴와 Script as 메뉴에 넣고 선택이 바뀌면 `update`. `QueryWindows.openSourceScript` 추가 |
| `MainFrame` (연결 지점) | `openSourceScript`: 새 쿼리 창에 스크립트를 넣고 실행 끝 알림에 노드 다시 읽기를 건다 |
| `SqlEditorPanel` (연결 지점) | `setExecutionFinishedListener`: 실행이 끝날 때(EDT) 부르는 알림 하나 |

- 새 번들 의존은 없다. `ext.mssql`·`ext.postgresql`·`ext.mysql`·`ext.oracle`은 이미 `Require-Bundle`에 있다(고급 연결 설정). SWT·JFace 번들은 쓰지 않는다.

## SWT에서 옮긴 것 (근거)

### ALTER 스크립트 = SWT 소스 탭의 저장

SWT 소스 탭은 고친 글을 속성 `objectDefinitionText`로 객체 편집기의 명령 묶음에 넣고, 저장할 때 드라이버의 객체 관리자가 실행 동작을 만든다.

| 단계 | SWT 위치 | Swing (`SourceScripts.generateAlter`) |
|---|---|---|
| 보여 줄 정의 읽기 | `SQLSourceViewer.getSourceText`(ui.editors.sql, 95행)·`getSourceOptions`(108행): `getObjectDefinitionText(monitor, {OPTION_DDL_SOURCE: true})` | 같은 호출. 관리자가 문장을 만들 때 쓰는 본문이 이때 읽힌다 |
| 고친 글을 명령으로 | `SQLServerSourceViewEditor.setSourceText`(ext.mssql.ui, 49행), `PostgreSourceViewEditor`(ext.postgresql.ui, 99행), `MySQLSourceViewEditor`(ext.mysql.ui, 42행), `OracleSourceDeclarationEditor`(ext.oracle.ui, 50–53행): `setPropertyValue(monitor, "objectDefinitionText", text)` → `PropertySourceEditable.setPropertyValue`(model, 92–127행)가 `makePropertyHandler`로 `DBECommandProperty`를 만든다 | `DBECommandProperty(object, editor.makePropertyHandler(object, property), source, source)`를 바로 명령 묶음(`AbstractCommandContext`)에 넣는다. `setPropertyValue`는 값이 같으면 명령을 만들지 않아(`updatePropertyValue` 202–204행) 쓸 수 없고, 객체에 값을 쓰지도 않는다 |
| 실행 동작 | `EntityEditor.showChanges`/`saveCommandContext`(ui.navigator): `getFinalCommands` → `validateCommand` → `getPersistActions(OPTION_OBJECT_SAVE)` → `SQLObjectEditor.ObjectChangeCommand.getPersistActions`(model, 401–406행) → 드라이버의 `addObjectModifyActions` | 같은 순서. 명령 묶음은 저장하지 않고 버린다 |
| 스크립트 글 | `SQLUtils.generateScript`(model, 1119행) | 같은 규칙(빈 동작 건너뛰기, 주석, 구분자)에 쿼리 창 실행용 구분자 규칙을 더함(아래) |

속성 설명자는 클래스의 `@Property` 주석에서 `DBConstants.PARAM_OBJECT_DEFINITION_TEXT`(`objectDefinitionText`, 편집 가능)를 찾는다
(`ObjectAttributeDescriptor.extractAnnotations`, 클래스마다 한 번). 이 속성과 객체 편집기(`DBEObjectEditor`)가 있고 `canEditObject`가 참인
프로시저·뷰·트리거·패키지만 Modify를 켠다. 그래서 드라이버를 이름으로 나누지 않는다.

### 방언별 결과 (드라이버 관리자가 정함)

| DB | 만드는 동작 (SWT 관리자) | 스크립트 모양 |
|---|---|---|
| SQL Server | `SQLServerProcedureManager.createOrReplaceProcedureQuery`(111행), `SQLServerViewManager.createOrReplaceViewQuery`, `SQLServerTableTriggerManager.createOrReplaceTriggerQuery`(71행): `SQLServerUtils.changeCreateToAlterDDL` | `USE <db>` / `GO` / `ALTER PROCEDURE·FUNCTION·VIEW·TRIGGER …` / `GO` |
| PostgreSQL | `PostgreProcedureManager.createOrReplaceProcedureQuery`(116행, `pg_get_functiondef`), `PostgreViewManager.createOrReplaceViewQuery`(140행), `PostgreTriggerManager.createOrReplaceTriggerQuery`(94행) | `CREATE OR REPLACE FUNCTION·PROCEDURE·VIEW …;`, 트리거는 `DROP TRIGGER IF EXISTS …;` + `CREATE TRIGGER …;` |
| MySQL/MariaDB | `MySQLProcedureManager.createOrReplaceProcedureQuery`(101행), `MySQLViewManager.createOrReplaceViewQuery` | `DROP PROCEDURE·FUNCTION IF EXISTS …;` + `DELIMITER $$` / `CREATE DEFINER=… BEGIN … END$$` / `DELIMITER ;`, 뷰는 `SHOW CREATE VIEW`의 `CREATE OR REPLACE … VIEW …;` |
| Oracle | `OracleProcedureManager.createOrReplaceProcedureQuery`(96행), `OraclePackageManager`(헤더 + 본문), `OracleViewManager`, `OracleUtils.addSchemaChangeActions`(464행) | `ALTER SESSION SET CURRENT_SCHEMA=<스키마>;` + `CREATE OR REPLACE …` |

### 쿼리 창에서 실행할 수 있게 한 것

- **구분자:** 배치 구분자가 있는 방언(SQL Server `GO`, `SQLDialect.getBatchDelimiters`)은 동작마다 `GO` 줄을 둔다. 쿼리 창은 `GO` 사이를 한 배치로 보내고
  ([swing-frontend.md](swing-frontend.md) "GO 배치"), `CREATE/ALTER PROCEDURE`는 배치의 첫 문장이어야 하기 때문이다(`generateScript`의 `;`로는 실패한다).
  그 밖의 방언은 `generateScript`처럼 기본 구분자(`;`)를 붙이고, 복합 동작(관리자가 `complex`로 표시한 루틴 본문)은 구분자를 바꾸는 방언(MySQL)에서
  `DELIMITER $$` … `$$` / `DELIMITER ;` 사이에 둔다. MySQL 방언에는 `BEGIN … END` 블록이 없어(`MySQLDialect.getBlockBoundStrings`가 null, 312행)
  쿼리 창의 구문 분석기가 본문 안의 `;`에서 문장을 나누기 때문이다(처음에 `DELIMITER` 없이 만들었다가 MariaDB에서 `CREATE PROCEDURE`가 1064 오류로
  잘려 보내지는 것을 확인하고 바꿨다). 쿼리 창은 `DELIMITER` 줄을 처리한다(실행 엔진의 구문 분석기가 구분자를 바꿈). `generateScript`와 달리
  `DELIMITER $$` 뒤의 빈 `$$` 문장은 넣지 않고, 줄 끝은 편집기와 같은 LF다.
- **DB/스키마 맞추기:** 쿼리 창은 연결의 기본(주) 실행 문맥에서 실행한다. 스크립트를 만들기 전에 그 문맥의 기본 카탈로그·스키마를 개체가 있는 곳으로 바꾼다
  (편집기의 기본 DB/스키마 콤보와 같은 `DBExecUtils.setExecutionContextDefaults`). 그래서
  - PostgreSQL은 다른 DB의 개체도 그 DB에서 실행된다(DB를 바꾸면 `PostgreDataSource.setActiveDatabase`로 기본 인스턴스가 바뀜).
  - 관리자가 DB·스키마 전환 문장(`USE`, `ALTER SESSION`)을 "기본값과 다를 때만" 넣고 끝에 원래대로 되돌리는 문장을 붙이는데, 맞춘 뒤 만들므로 되돌리는 문장이 없다.
  - 창의 기본 DB/스키마 콤보와 상태 줄이 개체의 DB/스키마를 보인다(SSMS에서 Modify 창이 `USE`로 그 DB에 있는 것과 같음).
- **SQL Server `USE`:** SSMS의 Modify처럼 맨 앞에 `USE <db>` 배치를 늘 넣는다(문장은 `SQLServerObjectManager.addDatabaseSwitchAction1` 39행과 같다).
  창을 연 뒤 기본 DB를 바꿔도 개체의 DB에서 실행되게 하기 위해서다. 다른 방언은 관리자의 문장이 스키마(MySQL은 DB)까지 붙인 이름을 쓴다.

### 템플릿 (New View/Procedure/Function)

SWT의 "새 개체"는 객체를 모델에 먼저 만들고(`DBEObjectMaker.createNewObject`) 구성 대화상자(`ext.*.ui/config`의 `*ProcedureConfigurator`)로 이름을 받아
편집기를 연다. 그러면 저장하기 전에도 탐색기에 새 노드가 생긴다. Swing에서는 SSMS처럼 실행할 수 있는 CREATE 문만 열고, 실행한 뒤 폴더를 다시 읽어
DB에 생긴 개체를 보여 준다. 본문은 모델·SWT 구성기에 있는 기본 본문을 따랐다.

| DB | 뷰 | 프로시저 | 함수 | 근거 |
|---|---|---|---|---|
| SQL Server | `CREATE VIEW dbo.NewView AS SELECT 1 AS Column1` | `CREATE PROCEDURE … @Param1 int = 0 AS BEGIN SET NOCOUNT ON; … END` | 스칼라 함수 `RETURNS int … RETURN @Param1` | SSMS 템플릿 구성(문구는 새로 씀), 앞에 `USE <db>` / `GO`, 끝에 `GO` |
| PostgreSQL | `CREATE OR REPLACE VIEW … AS SELECT 1 AS column1;` | `CREATE OR REPLACE PROCEDURE …(param1 integer) LANGUAGE plpgsql AS $procedure$ … $procedure$;` | `… RETURNS integer LANGUAGE plpgsql AS $function$ … $function$;` | `PostgreProcedureConfigurator`(ext.postgresql.ui, 74–81행) |
| MySQL/MariaDB | `CREATE VIEW … AS SELECT 1 AS column1;` | `DELIMITER $$` / `CREATE PROCEDURE …(IN param1 INT) BEGIN … END$$` / `DELIMITER ;` | `… RETURNS INT DETERMINISTIC BEGIN RETURN param1; END$$`(같은 `DELIMITER` 줄) | `MySQLProcedure.getDeclaration`(ext.mysql, 119–130행, 새 객체 본문) |
| Oracle | `CREATE OR REPLACE VIEW … AS SELECT 1 AS COLUMN1 FROM DUAL;` | `CREATE OR REPLACE PROCEDURE …(P_PARAM1 IN NUMBER) IS BEGIN NULL; END NEW_PROCEDURE;` | `… RETURN NUMBER IS BEGIN RETURN P_PARAM1; END NEW_FUNCTION;` | `OracleProcedureConfigurator`(ext.oracle.ui, 53–59행) |

- 이름은 폴더에 없는 기본 이름이다(`NewView`, `NewView1`, …; PostgreSQL·MySQL은 `new_view`, Oracle은 `NEW_VIEW`). 스키마(MySQL은 DB)를 붙여 쓰고,
  따옴표는 방언이 필요할 때만 붙인다(`DBUtils.getQuotedIdentifier`).
- 폴더 구분: 자식 클래스가 `DBSView`이면 뷰(PostgreSQL 구체화된 뷰 제외), `DBSProcedure`이면 루틴 폴더다. 루틴 폴더는 탐색기 정의(`plugin.xml`)의
  항목 속성 이름(`DBXTreeItem.getPropertyName`)으로 나눈다: `proceduresOnly`는 프로시저만, `functionsOnly`(또는 `function`을 포함하고 `procedure`가 없는 이름)는
  함수만, 그 밖(`procedures`)은 둘 다. Oracle 패키지 아래의 프로시저·함수 폴더(부모가 스키마·DB가 아님)는 제외한다.
- 템플릿이 있는 방언은 데이터 소스 클래스(`SQLServerDataSource`, `PostgreDataSource`, `MySQLDataSource`, `OracleDataSource`)로 고른다.
  그 밖의 드라이버는 New 항목이 꺼진다(Modify는 드라이버와 상관없이 위 조건으로 켠다).

## 설계 결정과 이유

- **소스 탭 대신 쿼리 창:** SSMS 사용자의 흐름(Modify → 고치기 → F5)을 그대로 따른다. 실행 결과·오류 위치·PRINT/NOTICE가 쿼리 창의 Messages에 나오고,
  저장(파일)·닫을 때 확인이 기존 쿼리 창과 같아 새 문서 종류를 만들지 않았다.
- **스크립트는 드라이버 관리자가 만든다:** 정의 글에서 `CREATE`를 `ALTER`로 바꾸는 식의 방언별 처리를 다시 쓰지 않고 SWT 저장과 같은 실행 동작을 쓴다.
  SWT에서 저장되는 개체라면 같은 문장이 나오고, 드라이버가 나중에 고쳐져도 따라간다.
- **기본 문맥을 바꾸는 부작용:** Swing 쿼리 창은 아직 연결의 기본 문맥 하나를 함께 쓴다(편집기별 연결 없음, swing-frontend.md "E단계에서 남긴 것").
  그래서 Modify·New는 같은 연결의 다른 쿼리 창의 기본 DB/스키마도 바꾼다. 편집기 콤보로 바꿀 때와 같은 동작이고, 스크립트 자체는 DB·스키마를 지정하므로
  다른 창에서 다시 바꿔도 SQL Server·MySQL·Oracle·PostgreSQL(같은 DB 안)은 맞는 곳에서 실행된다. PostgreSQL의 다른 DB 개체만 기본 DB에 의존한다.
- **실행 뒤 다시 읽기:** 실행이 끝날 때마다(성공 여부와 상관없이) 다시 읽는다. MySQL처럼 `DROP` 뒤 `CREATE`가 실패하면 개체가 사라지므로 트리가 그 상태를 보여야 한다.
  Modify는 개체 노드만 다시 읽어 가볍다. 개체가 없어졌으면 모델이 노드를 지운다. New는 폴더를 다시 읽는데, 모델은 폴더의 부모(스키마)를 다시 읽는다
  (Object Explorer의 새로 고침과 같음).
- **새 노드를 미리 만들지 않음:** 테이블 디자이너와 달리 템플릿은 실행 전까지 DB에 아무것도 없으므로 모델에 저장 안 된 객체를 만들지 않는다.
- **SSMS와 다른 점:** `SET ANSI_NULLS ON` / `SET QUOTED_IDENTIFIER ON` 배치와 `/****** Object: … ******/` 머리말을 넣지 않는다(드라이버가 만들지 않음.
  JDBC 연결의 기본값이 둘 다 ON이라 실행 결과는 같다). SSMS 템플릿의 `<매개 변수, 형식, 값>` 자리표시자(Ctrl+Shift+M)는 없고 바로 실행되는 이름·본문을 넣었다.

## upstream 대비 변경점

- Swing 번들(`com.seizia.chromite.ui.swing`)에만 추가했다. DBeaver 모델·드라이버·SWT 번들은 고치지 않았다.

## 확인 (2026-11-06)

빌드 `-Pproduct-chromite-swt,product-chromite-swing` BUILD SUCCESS. Windows에서 in-process JVM(`-vm …\bin\server\jvm.dll`), 별도 작업 영역으로
Swing 제품을 띄우고 EDT 테스트 에이전트로 조작했다(한국어 OS 기본, `-nl en`, 라이트·다크). 테스트 DB는 각 서버의 `chr_source_editor`
(Oracle은 `CHR_SOURCE_EDITOR` 스키마, `system`으로 연결)에 표·뷰·프로시저·함수·트리거(Oracle 패키지, PostgreSQL 구체화된 뷰)를 만들어 썼다.

| DB | 확인한 것 | 결과 |
|---|---|---|
| SQL Server 2022 (기본 DB `master`) | Modify: 프로시저·함수·뷰·테이블 트리거, 프로시저 본문을 고쳐 F5 | `USE chr_source_editor` / `GO` / `ALTER …` / `GO`, `OBJECT_DEFINITION`에 고친 본문. 다시 Modify하면 새 본문(노드 다시 읽기) |
| | New View… / New Procedure… / New Function…(저장 프로시저 폴더에 둘 다) → F5 | `NewView`·`NewProcedure`·`NewFunction`이 DB와 트리에 생김. 다시 고르면 `NewProcedure1`처럼 없는 이름 |
| PostgreSQL 17 (기본 DB `postgres`, 다른 DB 보기) | Modify: 함수·프로시저·뷰·구체화된 뷰(`DROP` + `CREATE MATERIALIZED VIEW`)·트리거(`DROP TRIGGER IF EXISTS` + `CREATE TRIGGER`), 뷰와 함수를 고쳐 F5 | 다른 DB(`chr_source_editor`)의 개체가 그 DB에서 바뀜(`postgres` DB에는 없음). 뷰 본문의 스키마 없는 `orders`도 기본 스키마를 맞춰 실행됨 |
| | 함수 폴더의 New View… / New Function… / New Procedure… → F5 | `new_view`·`new_function`·`new_procedure` 생성, 트리에 나타남 |
| MariaDB 11 | Modify: 프로시저(`DROP` + `DELIMITER $$` 본문)·함수·뷰·트리거, 프로시저를 고쳐 F5 | `routine_definition`에 고친 본문. 처음 빌드(구분자 없음)에서는 1064 오류로 `DROP`만 실행되어 개체가 사라졌고, `DELIMITER`를 넣은 뒤 정상 |
| | New View… / New Procedure… / New Function…(프로시저 폴더에 둘 다) → F5 | 셋 다 생성, 트리에 나타남 |
| Oracle 23ai | Modify: 프로시저·함수·패키지(헤더 + 본문 두 단위)·뷰·테이블 트리거, 패키지에 함수를 더해 F5 | `ALTER SESSION SET CURRENT_SCHEMA` + `CREATE OR REPLACE …`, 두 단위가 나뉘어 실행되고 `ALL_OBJECTS`가 모두 VALID |
| | New Procedure…(프로시저 폴더만)·New Function…(함수 폴더만)·New View… → F5 | `NEW_PROCEDURE`·`NEW_FUNCTION`·`NEW_VIEW` 생성 |

- Script as ▸ ALTER To ▸ Clipboard: PostgreSQL 프로시저의 `CREATE OR REPLACE PROCEDURE …`가 클립보드에 들어감.
- 저장 확인: 고친 Modify 창이 있는 채로 File ▸ 끝내기 → "다음 항목의 변경 내용을 저장하시겠습니까?"에 그 창이 나옴(다른 쿼리 창과 같음).
- 메뉴: 프로시저 노드에서 Modify·Script as ▸ ALTER To가 켜지고 New …은 꺼짐, 폴더에서는 반대. 한국어(수정, ALTER 대상, 새 뷰…, 새 프로시저…, 새 함수…)와 영어 모두 잘리지 않음.
- 스크린샷: `D:\Tools\_scratch\source-editor\shots\`(ko-light-*, ko-dark-*, en-light-*, en-dark-*).
- 확인하지 못한 것: macOS, SQL Server 데이터베이스 트리거(DB 수준), PostgreSQL 이벤트 트리거, MySQL 패키지(객체 관리자가 없어 Modify가 꺼짐).
- 범위 밖에서 본 것(고치지 않음): SQL Server에서 스키마를 새로 고치면(Object Explorer의 새로 고침도 같음) 테이블 아래 트리거 폴더가 빈다(연결을 다시 열면 보임).
  뷰 폴더에서 New Table…이 켜진다(`TableDesignSession.canCreateTable`이 뷰 클래스도 `DBSTable`로 봄).

## 남긴 것

- View Dependencies, Script as DROP / DROP And CREATE. Execute Stored Procedure…는 2026-11-08에 더했다([swing-routines.md](swing-routines.md)).
- SSMS 템플릿 매개 변수 바꾸기(Ctrl+Shift+M), 템플릿 탐색기.
- ~~Oracle 컴파일 오류 표시~~ 2026-11-08: Oracle 쿼리 창이 CREATE 뒤 `ALL_ERRORS`를 읽어 오류 줄을 Messages와 편집기에 보인다([swing-routines.md](swing-routines.md) "쿼리 창의 컴파일 오류 줄").
- 편집기별 연결(위 "기본 문맥을 바꾸는 부작용"의 원인).

## 변경 이력

- 2026-11-06 최초 작성: Modify, Script as ▸ ALTER To, New View… / New Procedure… / New Function…, 실행 뒤 노드 다시 읽기.
  MySQL 루틴은 `DELIMITER $$` 사이에 둠(MariaDB 확인에서 구분자 없는 본문이 잘려 실행되는 것을 찾음).
- 2026-11-08 "남긴 것"의 Execute Stored Procedure…·Oracle 컴파일 오류 표시를 [swing-routines.md](swing-routines.md)로 옮김(완료). 열 때 보일 위치(`openSourceScript`의 위치 인수)를 더함.
