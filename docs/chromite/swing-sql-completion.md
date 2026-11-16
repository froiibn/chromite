# SQL 자동완성 — 호출문 뒤 프로시저 제안

SQL 편집기 자동완성(model.sql의 새 완성 엔진 `SQLQueryCompletionContext`, Swing은 `SqlCompletionAnalysis`가 연결)에서
`CALL`·`EXEC` 뒤에 저장 프로시저가 나오지 않던 것(#57)과 SQL Server `EXEC dbo.` 뒤의 `NullPointerException`(#56)을 고친 기록이다.
자동완성의 전체 구조는 [swing-frontend.md](swing-frontend.md) "구현 현황 — F단계", 값 자리의 프로시저 제안 설정은
[swing-followup-fixes.md](swing-followup-fixes.md) "자동완성 프로시저"에 있다.

## 목적

- SSMS처럼 `EXEC ` 뒤에 프로시저를 제안한다. PostgreSQL·MariaDB/MySQL·Oracle의 `CALL`, Oracle의 `EXEC`도 같다.
- 스키마를 붙여 쓰면(`EXEC dbo.`, `CALL public.`, `EXEC CHR_CP.CHR_PKG.`) 그 스키마·패키지의 루틴만 제안한다.
- 입력 중인 끝나지 않은 이름이 예외를 내지 않게 한다.

## 구조

| 위치 | 바뀐 것 |
|---|---|
| `model` `SQLDialect.isCallStatementTarget(DBSProcedure)` (새 기본 메서드) | 호출문이 부를 수 있는 루틴인지. 기본은 함수(`DBSProcedureType.FUNCTION`)가 아닌 루틴 |
| `ext.mssql` `SQLServerDialectBase.isCallStatementTarget` | 프로시저와 스칼라 함수(`FN`, `FS`). 테이블 반환 함수(`IF`, `TF`, `FT`)는 뺌 |
| `model.sql` `SQLQuerySymbolOrigin` | 거르기 방식 `DbObjectFilterMode.CALL`, `DbObjectRef`의 거르기 방식(기본 `DEFAULT`), `CALL`이면 테이블 자리가 아니어도 적용 |
| `model.sql` `SQLQueryCallModel` | 루틴 이름의 원천(이름 앞 빈칸, 첫 조각, 점 뒤)을 `CALL`로 표시 |
| `model.sql` `SQLQueryObjectDataModel` | 끝나지 않은 이름은 객체 찾기에 넘기지 않고 앞쪽 조각만 부분 해석. 거르기 방식을 생성자로 받음(기본 `OBJECT`) |
| `model.sql` `SQLQueryCompletionContext.prepareCallTargetCompletions` | 기본 스키마(또는 앞 조각의 스키마·패키지)의 호출 대상 루틴, 패키지, 그리고 스키마·카탈로그 |
| `model.sql` `SQLQueryCompletionItem.forCallTarget`, `SQLQueryCompletionTextProvider`, `SQLQueryCompletionExtraTextProvider` | 호출 대상은 이름만 넣고, PostgreSQL은 회색 글자에 매개변수 형식 목록 |
| `ui.swing` `SqlCompletionAnalysis` | 설명만 바꿈(설정은 값 자리용) |

## 근본 원인

### `EXEC dbo.` 예외(#56)

- 이름이 점으로 끝나면 `SQLQueryModelRecognizer.collectQualifiedName`이 마지막 조각을 `null`로 두고 `invalidPartsCount`를 올린다
  (`SQLQueryModelRecognizer.java:707-709`). `SQLQueryComplexName.stringParts`도 `["dbo", null]`이 된다.
- 테이블 참조(`SQLQueryRowsTableDataModel.java:124`)와 함수 호출(`SQLQueryValueFunctionExpression.java:108`)은 이때 부분 해석만 하는데,
  `SQLQueryObjectDataModel`은 확인 없이 `findRealObjects`에 넘겼다(고치기 전 `SQLQueryObjectDataModel.java:110`).
- 그 이름은 `SQLSearchUtils.findObjectsByFQN`에서 `DBUtils.getUnQuotedIdentifier(dataSource, null)`(`SQLSearchUtils.java:132`)를 거쳐
  방언의 `getUnquotedIdentifier(identifier)`(SQL Server `SQLServerDialectBase.java:371`)에서 `NullPointerException`이 된다.
- 같은 원인의 다른 경우: `SQLQueryObjectDataModel`을 쓰는 모든 문장 — `CALL x.`, `DROP PROCEDURE/FUNCTION x.`(`SQLQueryRoutineDropModel`),
  `CREATE PROCEDURE x.`(`SQLQueryRoutineStatementModel`), `CREATE/DROP TRIGGER x.`, `DROP <객체> x.`(`SQLQueryObjectDropModel`). 모든 방언에서 같다.
- 고친 것: `invalidPartsCount > 0`이면 테이블·함수 모델처럼 `performPartialResolution`으로 앞쪽 조각만 해석하고(점 뒤 위치의 원천도 이때 정해짐)
  "Invalid object reference" 오류를 남긴다.

### `CALL`/`EXEC` 뒤 제안 없음(#57)

세 가지가 겹쳤다.

1. `SQLQueryCallModel`이 이름 자리에 원천 `DbObjectRef(TYPE_PROCEDURE, TYPE_PACKAGE)`를 두지만, 완성은 테이블 자리가 아닌 곳에서 이 원천을
   버렸다. `DbObjectRef.isApplicable`이 구문 검사의 `expectingTableReference()`(`tableName` 규칙에 닿는지)만 봤고, `callStatement`의
   `qualifiedName`은 `tableName`이 아니다(`SQLStandardParser.g4:616`). 자료형 이름만 예외였다.
2. 원천을 써도 프로시저 목록은 "저장 프로시저와 함수도 제안"(`isSearchProcedures`)이 켜졌을 때만 읽었다
   (`prepareObjectCompletions`, `collectProcedures`, `collectPackages`). 이 설정은 SELECT 목록·WHERE 같은 값 자리용(설정 도구 설명 그대로)이다.
3. 점 뒤(`EXEC dbo.`)의 원천은 `DbObjectFilterMode.OBJECT`라서 스키마의 모든 자식(테이블 등)을 찾고 프로시저는 찾지 않았다.

고친 것: 호출문의 원천을 `CALL`로 표시하고, `CALL` 원천은 구문 검사와 상관없이 쓰며, 완성은 `prepareCallTargetCompletions`로
방언이 허용하는 루틴·패키지·스키마를 설정과 상관없이 제안한다. 예전 엔진도 `EXEC` 뒤에서는 설정과 상관없이 프로시저를 제안했다
(`SQLCompletionAnalyzer.java:355-358`).

## 설계 결정

- **어떤 루틴을 제안하나 — 방언 규칙.** PostgreSQL·MySQL의 `CALL`은 함수를 거부하고 Oracle `EXEC`도 함수는 대입이 있어야 한다.
  SQL Server `EXECUTE`는 스칼라 함수도 실행한다(`EXEC @r = dbo.fn @x = 1`). 그래서 기본은 함수를 빼고 SQL Server만 스칼라 함수를 더한다.
  완성 엔진(model.sql)은 SQL Server 클래스를 볼 수 없으므로 판단을 `SQLDialect`의 기본 메서드에 두었다.
- **넣는 글자는 이름만.** 엔진은 원래 `name(:a, :b)`를 넣는다(값 자리의 함수 호출용). 인자 문법이 데이터베이스마다 달라
  (`EXEC p @a = 1`, `CALL p(1)`) SQL Server에서는 틀린 문장이 되므로 호출 대상은 이름만 넣는다. 매개변수를 읽지 않으니
  루틴마다 메타데이터 질의도 생기지 않는다(SQL Server·MySQL·Oracle의 매개변수 캐시는 루틴마다 따로 읽는다).
- **매개변수 목록 표시는 싼 경우만.** PostgreSQL은 루틴 목록을 읽을 때 시그니처(`DBPOverloadedObject.getOverloadedName`)를
  이미 만들어 두므로 회색 글자에 `(int4, text) - Procedure`처럼 붙인다. 다른 데이터베이스는 질의가 루틴마다 필요해 붙이지 않았다.
- **패키지·스키마도 제안.** `EXEC ` 뒤에서 Oracle 패키지(`DBSPackage`)와 스키마·카탈로그를 함께 제안해 `EXEC CHR_CP.CHR_PKG.PKG_PROC`처럼
  이어서 고를 수 있다. 정렬은 다른 제안과 같은 이름순이다.
- **값 자리 설정은 그대로.** "저장 프로시저와 함수도 제안"(기본 끔)은 값 자리에만 적용된다(#33, [swing-followup-fixes.md](swing-followup-fixes.md)).
- `DbObjectRef` 적용 예외는 `CALL`에만 넓혔다. `DROP PROCEDURE` 등 다른 객체 이름 자리도 같은 이유로 원천이 버려질 수 있지만
  이번 범위(호출문)를 넘으므로 바꾸지 않았다(아래 "남긴 것").

## upstream 대비 변경점

- `SQLDialect`에 기본 메서드 하나, `SQLServerDialectBase`에 재정의 하나.
- `SQLQuerySymbolOrigin.DbObjectFilterMode.CALL`, `DbObjectRef`의 4인자 생성자·`getFilterMode()`.
- `SQLQueryObjectDataModel`의 5인자 생성자와 끝나지 않은 이름 처리, `SQLQueryCallModel`의 `CALL` 표시.
- `SQLQueryCompletionItem.forCallTarget`, `SQLProcedureCompletionItem.isCallTarget()`(생성자에 인자 추가).
- `SQLQueryCompletionContext`의 `CALL` 분기와 `prepareCallTargetCompletions`.

## 확인 (2026-11-07)

브랜치 빌드를 테스트 작업 공간 사본으로 실행하고, 각 DB에 임시 DB·스키마(`chr_completion_procedures`, Oracle 사용자 `CHR_CP`)를 만들어
프로시저 두 개(`usp_chr_orders(2개 인자)`, `usp_chr_refresh()`)와 함수(`fn_chr_scalar`, SQL Server는 테이블 반환 함수 `fn_chr_table`도),
Oracle 패키지(`CHR_PKG`: `PKG_PROC`, `PKG_FN`)를 두었다. 확인 뒤 모두 지웠다. 입력은 앱 안에서 편집기에 글자를 넣고 Ctrl+Space를 보내는 방식이다.

- SQL Server `EXEC dbo.` → `fn_chr_scalar - Function`, `usp_chr_orders`, `usp_chr_refresh`(테이블 반환 함수 없음). 예외 없음(`chromite-debug.log`에 오류 없음).
  `EXEC usp` → 두 프로시저. `EXEC ` → 프로시저, 스칼라 함수, 스키마, 데이터베이스. `EXEC chr_completion_procedures.dbo.`도 같은 세 개.
- PostgreSQL `CALL ` → `usp_chr_orders (int4, text) - Procedure`, `usp_chr_refresh () - Procedure`, 스키마·카탈로그(함수 없음). `CALL public.`도 두 프로시저.
- MariaDB `CALL ` → 두 프로시저와 데이터베이스(함수 없음). `CALL usp_chr_` → 두 프로시저.
- Oracle `EXEC CHR_CP.` → 두 프로시저와 `CHR_PKG`(함수 없음). `EXEC CHR_CP.CHR_PKG.` → 하나뿐인 `PKG_PROC`가 바로 들어감. `CALL CHR_CP.usp` → 두 프로시저.
- 넣는 글자는 이름만(`usp_chr_orders`).
- 값 자리(#33): 설정을 끄면 `SELECT fn_chr`에 함수가 없고, 켜면 PostgreSQL·MariaDB·SQL Server `SELECT fn_chr`에 `fn_chr_scalar(:x)`가 나옴.
  켠 상태에서도 `CALL ` 결과는 같음.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: SQL 자동완성 전체(특히 `DROP/CREATE PROCEDURE·TRIGGER` 뒤 이름),
  의미 분석의 오류 표시(끝나지 않은 객체 이름), `SQLDialect`를 구현하는 모든 방언(기본 메서드라 동작 변화 없음).

## 남긴 것

- Oracle PL/SQL 블록(`BEGIN … END;`) 안의 프로시저 호출(`CHR_CP.usp_chr_orders(1, 'x');`)은 제안하지 않는다. 문법에서 블록 안의 일반 문장은
  `proceduralStatementTokens`라서(`SQLStandardParser.g4:710-713`) 호출문 모델이 없다. 블록 안에서도 `CALL`/`EXEC`로 쓰면 제안된다.
- 스키마를 붙인 값 자리(`SELECT dbo.fn`, `SELECT public.fn`, FROM 없음)는 설정을 켜도 함수를 제안하지 않는다(PostgreSQL·SQL Server에서 확인).
  스키마 없이 쓰면 나온다. 값 참조의 원천 문제로 이번 범위 밖이다.
- `DROP PROCEDURE x`·`DROP TRIGGER x` 같은 다른 객체 이름 자리도 `DbObjectRef`가 테이블 자리에서만 쓰이는 같은 제한이 있을 수 있다(확인하지 않음).
- 값 자리의 함수 제안은 SQL Server에서 테이블 반환 함수와 프로시저도 섞어 내고 `name(:@x)`를 넣는다(upstream 동작, 바꾸지 않음).

## 변경 이력

- 2026-11-07 최초 작성: `EXEC dbo.` 예외(#56), `CALL`/`EXEC` 뒤 프로시저 제안(#57), 값 자리 함수 제안 설정(#33) 확인.
