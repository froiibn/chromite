# Swing 루틴 명령: 프로시저 실행, 컴파일, 컴파일 오류 줄, 패키지 루틴으로 가기, DDL 형식, 구조 검증

## 목적

SWT 감사([swt-parity-audit.md](swt-parity-audit.md))의 4순위 루틴 묶음(`chromite/p4-routines`)이다. 저장 프로시저·함수를 다루는 매일 작업 가운데
SWT에 있었지만 Swing에 없던 것을 옮긴다.

| 감사 행 | 기능 | 이전 상태 |
|---|---|---|
| B-S42 | Execute Procedure…: 매개 변수 값(IN·OUT·IN OUT)을 받아 방언에 맞는 호출 스크립트를 만들고 쿼리 창에서 실행 | 없음 |
| D-O11 | 탐색기에서 Oracle 저장 코드 컴파일(Compile), 오류 목록, 오류 줄로 가기 | 일부(Maintenance의 무효 개체만) |
| D-O12 | Modify(직접 쓴 CREATE 포함) 뒤 `ALL_ERRORS`의 오류를 편집기 줄에 보이기 | 일부(드라이버 경고만) |
| D-O13 | 패키지 안 프로시저 → 소스 위치로 가기(Go to Source Code) | 없음 |
| D-O14 | View DDL의 DDL 형식(전체 / 저장소 제외 / 간단히) | 일부(형식 고르기 없음) |
| D-O20 | 테이블 구조 검증(VALIDATE STRUCTURE) | 없음 |
| D-A5 | Altibase TRUNCATE, 구체화된 뷰 새로 고침 | 없음 |
| D-A6 | Altibase 뷰·프로시저·패키지 컴파일 | 없음 |

## 화면

| 위치 | 항목 | 대상 |
|---|---|---|
| Object Explorer 오른쪽 클릭(Modify 아래) | **Execute Procedure…** | 저장된 프로시저·함수(모든 드라이버). 늘 보이고 맞지 않는 노드에서는 꺼진다 |
| 같은 곳 | **Go to Source Code** | Oracle 패키지 안의 프로시저·함수에서만 보인다 |
| 오른쪽 클릭(유지 관리 명령 묶음) | **Compile** | Oracle 프로시저·함수·패키지·뷰·구체화된 뷰·트리거·사용자 형식, Altibase 뷰·프로시저·함수·패키지. 고른 개체가 모두 대상일 때만 보인다 |
| 같은 곳 | Truncate Table… / Refresh Materialized View | 기존 명령([swing-explorer-object-actions.md](swing-explorer-object-actions.md) "유지 관리 명령")에 Altibase 테이블·구체화된 뷰를 더함 |
| View DDL 탭 머리글 오른쪽 | **DDL format** 콤보 | Oracle 테이블·뷰·구체화된 뷰 |
| Maintenance…(테이블) | **Validate Structure** 동작과 Indexes 선택(테이블만, CASCADE, CASCADE FAST, CASCADE ONLINE) | Oracle 테이블 |
| 쿼리 창 Messages 탭과 편집기 | 컴파일 오류 줄(빨강), 오류 줄 배경 | Oracle에서 저장 단위·뷰를 만든 CREATE 문 |

- 화면 문구는 `RoutineMessages`(새 묶음), `ObjectMaintenanceMessages`(Compile), `CoreMessages`(DDL 형식), 모델의 `MaintenanceMessages`(구조 검증)다. 영어 기본, 한국어 `_ko`.

## 구조

| 번들 | 클래스 | 역할 |
|---|---|---|
| `model.admin` | `routines.ProcedureCallScript` (새) | 실행 대화상자의 매개 변수 고르기와 방언별 호출 스크립트 |
| `model.admin` | `oracle.OracleCompileErrors` (새) | `ALL_ERRORS` 읽기, CREATE 머리글 해석(형식·스키마·이름), 오류 줄을 스크립트 위치로 바꾸기 |
| `model.admin` | `objects.ObjectMaintenanceActions` | `COMPILE` 동작, Altibase TRUNCATE·새로 고침·컴파일 도구, 컴파일 뒤 상태 다시 읽기(`refreshObjectStates`), 오류 읽기(`readCompileErrors`) |
| `model.admin` | `oracle.OracleMaintenanceDialect` | `validate` 동작 |
| `ui.swing` | `RoutineCommands` (새) | Execute Procedure…, Go to Source Code, 컴파일 오류에서 Modify 창 열기 |
| `ui.swing` | `ExecuteProcedureDialog` (새), `RoutineArgumentTable` (새) | 실행 대화상자. 인수 표는 디버거 시작 대화상자에서 옮겨 함께 쓴다 |
| `ui.swing` | `CompileErrorsDialog` (새) | 컴파일 결과(개체, 형식, 줄, 열, 메시지)와 오류로 가기 |
| `ui.swing` | `ObjectMaintenanceCommands` | Compile 메뉴 항목과 결과 처리 |
| `ui.swing` | `SqlEditorPanel` | Oracle CREATE 뒤 컴파일 오류 줄, `showSourcePosition`(고르기·오류 줄 표시) |
| `ui.swing` | `DdlViewerPanel`, `ObjectScripts` | DDL 형식 콤보와 형식별 DDL |
| `ui.swing` | `ObjectExplorerPanel`, `MainFrame`, `SourceEditCommands`, `SourceScripts`, `DebugStartDialog` | 연결 지점(메뉴, `openSourceScript`의 위치 인수, `showDdl`의 형식 인수, 문맥 맞추기 공유, 인수 표 공유) |

- MANIFEST: `ext.altibase`가 `tasks` 패키지를 내보낸다(Altibase 도구 처리기). `model.admin`이 `routines` 패키지를 내보낸다. 새 번들 의존은 없다.

## 설계 결정과 이유

### Execute Procedure… (B-S42)

- **SWT와 다른 점:** SWT "Execute procedure"(`SQLEditorHandlerRunProcedureConsole`)는 방언의 호출문 생성기(`generateStoredProcedureCall`)로
  `:이름` 매개 변수가 든 호출문을 콘솔에 열고 실행할 때 값을 물었다. OUT 값은 볼 수 없었다. Swing은 SSMS "Execute Stored Procedure"처럼 대화상자에서
  값을 받아 값이 든 스크립트를 만들고 OUT·IN OUT 값과 반환 값을 보이게 한다. 실행은 새 쿼리 창의 F5와 같은 경로라 실행 제한·결과·Messages·
  트랜잭션 표시가 다른 쿼리와 같고, 사용자가 스크립트를 고쳐 다시 실행할 수 있다.
- **값 규칙:** 디버거 시작 대화상자와 같은 SQL 식(`10`, `'text'`, `NULL`)이다. 비어 있으면 NULL이다. SQL Server는 `DEFAULT`를 쓰면 매개 변수의 기본값이다
  (T-SQL 프로시저의 기본값은 `sys.parameters`에 없어 미리 채울 수 없다).
- **기본 단추:** 실행 단추가 루틴을 실행하므로 기본 단추는 취소다(확인의 취소 기본 규칙). "Open in Query Window"는 실행하지 않고 연다.
- **DB·스키마:** Modify와 같은 `SourceScripts.bindDefaultContext`로 새 창이 루틴의 DB·스키마에서 실행된다(PostgreSQL은 다른 DB의 루틴도 그 DB에서).
- **재사용:** 인수 표는 디버거 시작 대화상자의 표를 `RoutineArgumentTable`로 옮겨 함께 쓴다. 형식 칸은 매개 변수 자신의 길이·정밀도를 보이게 바꿨다
  (전에는 데이터 형식 이름이라 SQL Server에서 `nvarchar(4000)`, `decimal(38,38)`처럼 선언과 달랐다). 스칼라 함수와 테이블 반환 함수의 구분은
  #122의 `SQLDialect.isCallStatementTarget`을 쓴다. Oracle 변수 선언은 Oracle 디버거(`OracleDebugCall`)와 같은 규칙이다(그 클래스는 디버그 번들의
  내부 클래스이고 바인드 변수로 돌려받는 구조라 그대로 쓰지 않았다).

방언별 스크립트(값은 예):

| DB | 프로시저 | 함수 | OUT 값 |
|---|---|---|---|
| SQL Server | `USE db` / `GO` / `DECLARE @return_value int;` / OUTPUT 매개 변수마다 `DECLARE @b int = NULL;` / `EXEC @return_value = dbo.p @a = 21, @b = @b OUTPUT;` / `SELECT @b AS [@b];` / `SELECT @return_value AS [Return Value];` / `GO` | 스칼라 `SELECT dbo.f(1, 2) AS [Return Value];`, 테이블 반환 `SELECT * FROM dbo.f(1);` | 결과 표 |
| PostgreSQL | `CALL s.p(5, 'abc', NULL);`(OUT 자리 NULL) | `SELECT * FROM s.f(2, 40);` | 서버가 돌려준 결과 행 |
| Oracle | `DECLARE v_1 VARCHAR2(32767); … BEGIN S.P(A => 7, B => v_1, …); DBMS_OUTPUT.PUT_LINE('B = ' \|\| v_1); END;` | `v_result := S.F(…)` 뒤 `PUT_LINE('Return Value = ' …)` | Messages 탭(DBMS_OUTPUT, 기본 켬) |
| MySQL/MariaDB | `SET @c = 'hey';` / `CALL db.p(4, @b, @c);` / `SELECT @b AS b, @c AS c;` | ``SELECT db.f(5, 6) AS `Return Value`;`` | 결과 표 |
| Altibase | Oracle과 같은 익명 블록, 출력은 `PRINTLN`, 변수는 매개 변수의 선언 형식 | OUT이 없으면 `SELECT S.F(3, 4) AS "Return Value" FROM DUAL;` | Messages 탭 |
| 그 밖의 드라이버 | 방언의 실행 키워드(`CALL` 등), OUT 자리 NULL | `SELECT f(…)`(+ 방언의 DUAL) | 받지 않음(대화상자가 알림) |

- Oracle BOOLEAN은 `TRUE`/`FALSE` 글자로, REF CURSOR·레코드·컬렉션·객체는 그 형식으로 받되 값 대신 "(형식)"을 쓴다(디버거와 같음).
- Oracle·Altibase 블록 끝에 `/` 줄을 두지 않는다. 처음에는 넣었는데 Oracle 쿼리 창이 `END;`에서 블록을 끝내고 `/`를 따로 보내 ORA-00900이 났다
  (실행으로 확인). 뺀 뒤 Oracle·Altibase 모두 다시 실행해 확인했다.
- Altibase `EXEC p(1, NULL, 'x')`는 OUT 자리 NULL을 ERR-31147로 거부해(7.3에서 확인) 익명 블록을 쓴다.

### 컴파일 (O11, A6)

- 문장은 SWT와 같다. Oracle은 SWT Compile(`CompileHandler.compileUnit`)이 쓰던 모델의 `OracleSourceObject.getCompileActions`(패키지는 `COMPILE` + `COMPILE BODY`),
  Altibase는 SWT 도구(`AltibaseToolViewCompile`·`ProcedureCompile`·`PackageCompile`)의 문장이다.
- 흐름은 기존 유지 관리 명령과 같다: 스크립트 미리 보기(취소 기본, DDL 암시적 커밋 안내) → 열린 트랜잭션 질문 → 대상 인스턴스의 격리된 연결에서 실행.
  SWT는 기본 실행 문맥(사용자 세션)에서 실행했다.
- 실행 뒤(실패해도) 객체 상태(`DBPStatefulObject.refreshObjectState`)를 다시 읽고 바뀌었으면 탐색기에 알린다(SWT와 같음).
- Oracle 컴파일 오류는 예외가 아니라 경고로 오므로 `ALL_ERRORS`를 읽어 `CompileErrorsDialog`에 보인다. 오류가 없으면 "오류 없이 컴파일" 알림이다.
  Altibase는 컴파일 오류가 예외로 온다(ERR-31031 등, 7.3에서 확인). 그 오류 대화상자가 결과다.
- **오류로 가기:** 객체 편집기가 없으므로 그 객체의 Modify 창을 열고 오류 줄을 빨간 배경으로 표시하고 고른 오류부터 그 줄 끝까지 고른다
  (커서 줄의 현재 줄 색이 오류 색을 덮어서 고르기로 위치를 보인다). 줄 번호는 스크립트 안의 그 단위(패키지 선언·본문)가 시작하는 곳을 기준으로 바꾼다.
- 범위 밖: Oracle `OracleDataType.getCompileActions`는 형식 컴파일의 객체 종류를 VIEW로 적는다(모델 버그). 오류 읽기는 이 값을 쓰지 않고 객체 클래스로
  형식(TYPE, TYPE BODY)을 정해 영향이 없다. 고치지 않았다.

### 쿼리 창의 컴파일 오류 줄 (O12)

- Oracle에서 저장 단위(PROCEDURE, FUNCTION, PACKAGE [BODY], TYPE [BODY], TRIGGER)나 VIEW·MATERIALIZED VIEW를 만든 CREATE 문이 성공하면, 그 문장의 세션에서
  `ALL_ERRORS`를 읽어 Messages 탭에 "PROCEDURE S.P was created with compilation errors:"와 줄마다 "Line 6, Column 3: PLS-00201: …"(편집기 줄·열)를 쓰고,
  오류 줄을 빨간 배경으로 표시한다. ERROR가 있으면 실행이 오류로 끝나 Messages 탭이 앞으로 온다. 표시는 다음 실행에서 지운다.
- Modify 창만이 아니라 모든 Oracle 쿼리 창이다. 근본 원인이 쿼리 창이 경고(ORA-17110)만 보이고 오류 내용을 읽지 않는 것이기 때문이다.
- **줄 기준(23ai에서 확인):** 저장 단위는 형식 키워드(`PROCEDURE p …`)가 있는 줄이 1이다(`CREATE OR REPLACE`가 앞 줄에 있어도). 트리거는 트리거 본문의
  PL/SQL 블록(`DECLARE`/`BEGIN`)이 있는 줄이 1이다. 1번 줄의 열은 같은 줄 앞부분 길이를 더한다.
- 머리글은 정규식으로 읽는다(앞의 주석·공백, `OR REPLACE`, `[NO] FORCE`, `EDITIONABLE` 등, 스키마가 붙은 이름, 따옴표 이름). 스키마가 없으면 세션의
  `CURRENT_SCHEMA`(Modify 창의 `ALTER SESSION SET CURRENT_SCHEMA` 뒤).
- 읽기는 SELECT라 트랜잭션을 시작하지 않는다.

### Go to Source Code (O13)

- SWT `PackageNavigateHandler`처럼 패키지를 열고 루틴 머리글(주석 줄이 아닌 줄의 `PROCEDURE 이름`)을 찾는다. Swing에는 객체 편집기가 없어 패키지의 Modify 창을
  열고 본문(없으면 선언)에서 고른다. 겹친 루틴은 겹침 번호(`OVERLOAD`) 순서의 머리글이다(SWT는 늘 첫 번째).

### DDL 형식 (O14)

- SWT DDL 탭 콤보(`OracleEditorUtils.addDDLControl`)와 같다. 고른 형식은 연결의 현재 DDL 형식(`oracle.ddl.format`, 테이블이 읽음)이 되고 생성기 옵션으로도
  넘긴다(뷰·구체화된 뷰가 읽음). 그래서 Script as CREATE에도 쓰인다(SWT와 같음).

### 구조 검증 (O20)

- 모델 도구 `OracleToolTableValidateStructure`(SWT "Validate structure")와 같은 `ANALYZE TABLE … VALIDATE STRUCTURE [CASCADE | CASCADE FAST | CASCADE ONLINE]`을
  유지 관리 대화상자의 테이블 동작으로 넣었다(미리 보기, 확인, 별도 자동 커밋 연결). ONLINE이 아니면 쓰기를 막는다는 경고를 보인다.
  남의 스키마는 ANALYZE ANY가 필요하다(통계 수집과 같은 확인).

### Altibase TRUNCATE·구체화된 뷰 새로 고침 (A5)

- 모델의 Altibase 도구(`AltibaseToolTableTruncate`, `AltibaseToolMViewRefresh`) 문장을 기존 유지 관리 명령에 넣었다(`TRUNCATE TABLE`, `EXEC SYSTEM_.REFRESH_MATERIALIZED_VIEW('S', 'MV')`).
  Altibase는 DDL이 바로 커밋되므로 잘라내기 경고에 암시적 커밋 안내가 나온다(JDBC `dataDefinitionCausesTransactionCommit`).

## upstream 대비 변경점

- `ext.altibase` MANIFEST가 `tasks` 패키지를 내보낸다. 그 밖의 DBeaver 모델·드라이버 코드는 고치지 않았다.

## 확인 (2026-11-08)

빌드 `-Pproduct-chromite-swing`. Windows에서 앱 하나를 `CHROMITE_DATA`와 작업 공간을 스크래치에 두고 띄워(`-nl en`), 앱 안 시험 드라이버(EDT, OS 입력 없음)로
조작했다. 개체는 이 작업이 만든 스크래치 스키마(PostgreSQL `chr_p4r` 스키마, SQL Server·MariaDB `chr_p4r` DB, Oracle·Altibase `CHR_P4R` 사용자)에만 만들었다.

| DB | 확인한 것 | 결과 |
|---|---|---|
| PostgreSQL 17 | `p_out(a, INOUT c, OUT b)` 실행(5, 'abc') | `CALL chr_p4r.p_out(5, 'abc', NULL);` → 결과 행 c=`abc!`, b=10 |
| | `f_add(2, 40)`, `f_tab(3)`(OUT 열) | `SELECT * FROM chr_p4r.f_add(2, 40);` → 42, `SELECT * FROM chr_p4r.f_tab(3);` |
| SQL Server 2022 | `p_out @a, @b OUTPUT, @c OUTPUT`(21) | SSMS 모양 스크립트, 결과 표 `@b`=42, `@c`=`!`, Return Value 7, 프로시저 자신의 결과 표 |
| | `p_types`(nvarchar(max), decimal(10,2), datetime2(3) OUTPUT) | 변수 선언이 매개 변수와 같음(`nvarchar(MAX)`, `decimal(10,2)`, `datetime2(3)`) |
| | 스칼라 `f_add(1, 2)`, 인라인 테이블 함수 `f_tab(1)` | `SELECT dbo.f_add(1, 2) AS [Return Value];` → 3, `SELECT * FROM dbo.f_tab(1);` → 1행 |
| MariaDB 11 | `p_out(IN a, OUT b, INOUT c)`(4, 'hey'), `f_add(5, 6)` | b=8, c=`hey!`; 11 |
| Oracle Free 23ai | `P_OUT(A, B OUT, C IN OUT, F OUT BOOLEAN)`(7, 'ora') | Messages `B = 14`, `C = ora!`, `F = TRUE` |
| | Modify `P_BAD` → F5 | "PROCEDURE CHR_P4R.P_BAD was created with compilation errors:", "Line 6, Column 3: PLS-00201 …", 6번 줄 빨강, 실행 오류로 끝남 |
| | Modify 패키지 `PKG`(본문 오류) → F5 | "PACKAGE BODY … Line 15, Column 12: PLS-00201: identifier 'ZZ' …", 15번 줄 빨강 |
| | Modify 트리거 `TRG_BAD` → F5 | "Line 9, Column 8: PLS-00201: identifier 'UNDEFINED_X' …"(DECLARE 기준 4번 줄 → 편집기 9번 줄) |
| | Compile `P_BAD`, `PKG` | 미리 보기 `ALTER PROCEDURE … COMPILE;`, `ALTER PACKAGE … COMPILE;` + `COMPILE BODY;` → 결과 대화상자(줄 4·열 3, PACKAGE BODY 줄 8·열 12) → Go to Error가 Modify 창의 6번 줄(패키지 15번 줄)로 감 |
| | Go to Source Code `PKG2.TWICE`, 두 번째 `PKG2.OV`(겹침) | 본문의 `FUNCTION TWICE` 19번 줄 고름, 두 번째 `PROCEDURE OV` 고름(주석 줄 `-- PROCEDURE OV …`는 건너뜀) |
| | View DDL `T1`, 형식 Compact로 바꾸기 | 콤보가 다시 읽고 형식을 바꿈. 이 컨테이너에서는 메타데이터 세션의 `DBMS_METADATA.GET_DDL`이 ORA-38802로 실패해 모델이 일반 DDL로 대신한다(아래 "범위 밖") |
| | Maintenance… `T1` → Validate Structure, CASCADE / CASCADE ONLINE | 미리 보기 `ANALYZE TABLE "CHR_P4R"."T1" VALIDATE STRUCTURE CASCADE;`(ONLINE이 아니면 경고), 실행 "Done in 0.1 s." |
| Altibase 7.3 | Truncate Table… `T1`, Refresh Materialized View `MV1` | 경고에 "On Altibase, TRUNCATE commits implicitly", `TRUNCATE TABLE CHR_P4R.T1;`, `EXEC SYSTEM_.REFRESH_MATERIALIZED_VIEW('CHR_P4R', 'MV1');` → T1·MV1 0행(isql) |
| | Compile `V1`, `PKGA`, 무효 `P_DEP` | "compiled without errors" 두 번, `P_DEP`는 "Table or view was not found … 0003 : INSERT INTO T2" 오류 대화상자 |
| | Execute `P_OUT`(3, 'al'), `F_ADD(3, 4)` | Messages `B = 6`, `C = al!`; 결과 7 |

- 화면: `D:\Tools\_scratch\chromite-p4-routines\shots\`.
- 범위 밖에서 본 것(고치지 않음): 이 Oracle 23ai 컨테이너에서 View DDL·Script as CREATE의 테이블 DDL이 메타데이터 세션에서 ORA-38802("edition does not exist")로
  실패해 모델이 일반 DDL로 대신한다. 같은 사용자의 쿼리 창과 sqlplus에서는 `DBMS_METADATA.GET_DDL`이 된다. 이 작업의 변경과 상관없이 나는 오류다(첫 View DDL에서도 남).

## 남긴 것

- Oracle REF CURSOR OUT 값을 결과 표로 보이기(`DBMS_SQL.RETURN_RESULT`), 레코드·컬렉션 값 보이기.
- SQL Server 매개 변수 기본값 미리 채우기(T-SQL 기본값은 메타데이터에 없음), PostgreSQL VARIADIC 인수의 `VARIADIC` 키워드.
- 쿼리 창의 `ALTER … COMPILE` 문 뒤 오류 보이기(지금은 CREATE 문만), Messages의 오류 줄을 눌러 편집기로 가기.
- 인덱스 구조 검증(`ANALYZE INDEX … VALIDATE STRUCTURE`, SWT에도 없었음).

## 변경 이력

- 2026-11-08 최초 작성: Execute Procedure…(B-S42), Compile과 결과(O11·A6), 쿼리 창 컴파일 오류 줄(O12), Go to Source Code(O13), DDL 형식(O14),
  구조 검증(O20), Altibase TRUNCATE·구체화된 뷰 새로 고침(A5). PostgreSQL·SQL Server·MariaDB·Oracle·Altibase에서 실행 확인.
