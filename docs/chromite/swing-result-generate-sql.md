# Swing 결과에서 SQL 만들기 (고른 행의 SELECT·INSERT·UPDATE·DELETE·MERGE·중복 키 INSERT, 결과를 담을 CREATE TABLE)

## 목적

SWT 결과 뷰어의 "Generate SQL"(고른 행에서 문장 만들기)과 "결과로 DDL 만들기"(`generate.ddl.by.resultSet`)를 Swing 결과 표로 옮긴다.
SWT 대조 B-S41([swt-parity/B-editors.md](swt-parity/B-editors.md))이고, 탐색기의 Generate SQL(C-17, [swing-explorer-object-actions.md](swing-explorer-object-actions.md))은
이미 있었다.

- 고른 행에서: SELECT … WHERE 키 = 값, SELECT … WHERE 키 IN (…), INSERT, UPDATE, DELETE, 그리고 데이터베이스에 있으면 MERGE와 중복 키 INSERT
  (PostgreSQL `ON CONFLICT`, MySQL·MariaDB `ON DUPLICATE KEY UPDATE`·`REPLACE INTO`·`INSERT IGNORE` 등).
- 결과에서: 그 결과의 열을 담을 수 있는 테이블의 CREATE TABLE.
- 만든 문장은 **실행하지 않는다.** 대화상자에서 복사하거나 새 쿼리 창으로 연다.

이 기능은 JDBC로 연결하는 모든 데이터베이스의 결과에서 동작한다. MERGE는 방언이 MERGE가 있다고 알리는 데이터베이스(지금은 SQL Server, Oracle)에서,
중복 키 INSERT는 방언이 데이터 전송용 중복 키 처리 방식을 등록한 데이터베이스(PostgreSQL 계열, MySQL·MariaDB, SQLite, Firebird, Oracle, YashanDB)에서만 보인다.
확인은 PostgreSQL, SQL Server, Oracle, MariaDB에서 했다(아래 "확인").

## 사용법

결과 표에서 행을 고르고 오른쪽 클릭 › **SQL 생성**(영어 UI: Generate SQL). 메뉴는 "다른 형식으로 복사"(Copy As) 바로 아래에 있다.

| 항목 | 만드는 것 | 켜지는 조건 |
|---|---|---|
| SELECT .. WHERE .. = | 행마다 SELECT, 키 열 조건(키가 없으면 모든 열) | 행을 골랐고 결과가 테이블 하나에서 옴 |
| SELECT .. WHERE .. IN | 고른 행 전체를 키 값 IN 목록으로 하나의 SELECT | 위 조건 + 결과에 그 테이블의 완전한 고유 키(가상 키 포함) |
| INSERT | 행마다 INSERT(결과의 모든 열) | 행을 골랐고 결과가 테이블 하나에서 옴 |
| UPDATE | 행마다 UPDATE … SET 키 아닌 열 … WHERE 키 | 위 조건 + 고유 키 |
| 고유 키로 DELETE | 행마다 DELETE … WHERE 키(키가 없으면 모든 열 값) | 행을 골랐고 결과가 테이블 하나에서 옴 |
| MERGE | 행마다 MERGE INTO … USING (그 행 값의 SELECT) ON 키 | 방언이 MERGE를 지원 + 고유 키 |
| 중복 키가 있을 때 INSERT › (방식) | 행마다 방언의 방식으로 INSERT | 방언에 방식이 등록됨 + 행을 골랐고 결과가 테이블 하나에서 옴 |
| 이 결과를 담을 CREATE TABLE | 결과의 열로 된 새 테이블 | 결과에 열이 있음(행 선택과 원본 테이블은 필요 없음) |

꺼진 항목은 도구 설명에 이유를 쓴다("행을 하나 이상 고르세요", "결과가 테이블 하나에서 온 것이 아닙니다", "결과에 테이블의 고유 키가 없습니다").
연결이 끊긴 결과는 메뉴 전체가 꺼진다.

결과 대화상자(제목 "SQL 생성 - 항목")는 머리글(무엇에서 몇 행으로 만들었는지, "아무것도 실행하지 않았습니다"), SQL 글 상자(읽기 전용),
**복사 / 쿼리 창에서 열기 / 닫기**만 있다. 닫기가 기본 단추다. 쿼리 창에서 열기는 그 연결의 새 쿼리 창에 글만 넣는다(실행하지 않음).

## 구조

| 위치 | 새/변경 | 역할 |
|---|---|---|
| `ui.swing` `ResultSetGenerateSql` | 새 | 메뉴 채우기, 고른 행의 값으로 모델 생성기 실행, CREATE TABLE 만들기, 결과 대화상자·쿼리 창 열기 |
| `ui.swing` `ResultSetViewerPanel` | 변경 | 오른쪽 클릭 메뉴에 "SQL 생성"(Copy As 다음), `GenerateSqlSource`(열, 고른 행, 칸 값) |
| `ui.swing` `ResultSetCellContent` | 변경 | `toStatementValue`: LOB 칸을 문장 값(남긴 값 객체, 또는 받은 바이트·글자의 값 객체)으로 |
| `ui.swing` `ScriptPreviewDialog` | 변경 | `showGenerated`: 호출자가 정한 머리글, 실행 단추 없음 |
| `ui.swing` `ResultSetGrid.Host`, `QueryResultsView.Listener`, `SqlEditorPanel.Host` | 변경 | `openScript`: 결과가 만든 문장을 메인 창이 새 쿼리 창에 엶(실행하지 않음) |
| `ui.swing` `internal/GridSqlMessages` (+ `.properties`, `_ko.properties`) | 새 | 화면 문구(영어·한국어) |
| `model.sql` `SQLGeneratorMergeFromData` | 새 | 행마다 MERGE |
| `model.sql` `SQLGeneratorInsertReplaceFromData` | 새 | 방언의 중복 키 처리 방식(`DBDInsertReplaceMethod`)으로 INSERT |
| `model.sql` `SQLGeneratorResultSet` | 변경 | `isUniqueKeyRequired`, 결과 중 테이블 열만 고르는 `getTableColumns` |
| `model.sql` `SQLGeneratorUpdateFromData`, `SQLGeneratorSelectManyFromData` | 변경 | 고유 키가 꼭 필요하다고 알림. UPDATE의 SET에서 자동 생성 열(IDENTITY)을 뺌 |
| `model.sql` `SQLGeneratorDeleteFromData` | 변경 | 키 없는 결과에서 나던 `UnsupportedOperationException` 고침 |
| `model.sql` `plugin.xml`, `OSGI-INF/l10n/bundle*.properties`, `MANIFEST.MF` | 변경 | "DELETE by Unique Key" 레이블 번역 키, `generator.resultset` 패키지 내보내기 |
| `model` `SQLDialect` | 변경 | `supportsMergeStatement`(기본 false) |
| `ext.mssql` `SQLServerDialectMssql`, `ext.oracle` `OracleSQLDialect` | 변경 | MERGE 지원 알림 |
| `ext.postgresql` `PostgreDialect` | 변경 | 정밀도 없는 numeric(정밀도 131089로 보고됨)에 새 열의 정밀도를 붙이지 않음 |

확장점: 고른 행의 생성기는 `com.seizia.chromite.sqlGenerator`에 결과용(`SQLGeneratorResultSet`의 하위 클래스)으로 등록된 것을 순서대로 쓴다.
중복 키 INSERT는 방언의 `insertMethods`(확장점 `com.seizia.chromite.sqlInsertMethod`)를 쓴다.

## 설계 결정과 이유

- **모델 생성기를 그대로 쓴다.** SWT와 같은 생성기(`SQLGeneratorSelectFromData` 등)에 SWT 결과 모델 대신 고른 행만 담은
  `DBDResultSetDataProvider`(`RowsProvider`)를 넘긴다. 값은 결과 뷰어가 가진 칸 값이다. 편집할 수 있는 결과는 저장하지 않은 변경을 포함한 지금 값이다
  (SWT 결과 모델과 같음, Copy As와도 같음). 쿼리를 다시 실행하지 않는다.
- **LOB 칸.** 수신기는 LOB 값 객체를 놓아 주고 내용만 남긴다. 문장을 만들 때 남긴 값 객체(메모리에 없는 LOB) 또는 받은 바이트·글자로 만든
  `JDBCContentBytes`·`JDBCContentChars`를 넘겨서, 모델이 방언의 이진 리터럴(PostgreSQL `decode('…','hex')`, SQL Server `0x…`)이나 문자열로 쓰게 했다.
- **키가 필요한 문장은 끈다.** SWT UPDATE는 키가 없으면 WHERE 없이 테이블 전체를 바꾸는 문장을, SELECT … IN은 `WHERE  IN (…)`을 만들었다.
  UPDATE는 자동 생성 열(SQL Server IDENTITY)을 SET에 넣지 않는다: 그 열은 바꿀 수 없고(SQL Server가 문장을 거부) 지금 값을 다시 넣는 것뿐이다.
  생성기가 `isUniqueKeyRequired`로 알리고 메뉴가 끈다. DELETE와 SELECT … =는 SWT처럼 키가 없으면 모든 열 값으로 찾는다.
  키는 결과의 첫 행 식별자가 완전할 때만 쓴다(일부 키 열만 있는 결과는 다른 행까지 맞을 수 있음).
- **MERGE는 방언이 알린다.** 키워드 목록은 모든 방언에 MERGE를 넣어서 근거가 되지 못한다. `SQLDialect.supportsMergeStatement`를 두고
  SQL Server와 Oracle만 켰다. PostgreSQL 15의 MERGE는 켜지 않았다: 버전마다 다르고, PostgreSQL의 upsert는 `ON CONFLICT`(중복 키 INSERT)로 이미 만든다.
- **MERGE의 원본은 그 행 값의 SELECT다.** `USING (SELECT 1 AS id, 'a' AS name [FROM DUAL]) src`. `VALUES` 행 생성자보다 여러 DB에서 통하고,
  이중 테이블이 필요한 방언은 `getDualTableName`으로 붙인다. 테이블 별칭에 AS를 쓰지 않는다(Oracle). ON의 키 열은 UPDATE SET에서 뺀다(Oracle ORA-38104).
  자동 생성 열(SQL Server IDENTITY 등)은 UPDATE·INSERT에서 뺀다. 열은 결과 중 그 테이블의 열만 쓴다(식 열은 테이블에 없음).
- **중복 키 INSERT는 데이터 전송과 같은 방식 확장을 쓴다.** 방식의 앞·뒤 절이 데이터 전송이 실행하는 문장과 같아서 데이터베이스마다 코드를 두지 않았다.
  방식이 판단할 키를 못 찾아 일반 INSERT가 되는 경우(PostgreSQL ON CONFLICT DO UPDATE에 기본 키·고유 인덱스가 없음)는 오류로 알린다.
- **CREATE TABLE은 데이터 전송의 대상 테이블 DDL을 쓴다.** 테이블 복사 미리 보기와 같은 `DatabaseCopyMappings.generateTableDdl`
  (`DatabaseTransferUtils.generateTargetTableDDL`, 열 형식 매핑과 방언의 열 관리자)로 만든다. 데이터 전송은 쿼리 결과의 열을 쿼리를 다시 실행해 읽으므로,
  결과의 열 메타데이터를 그대로 돌려주는 데이터 컨테이너(`ResultColumns`, 로컬 문장·결과 집합)를 원본으로 넘겼다. SWT도 결과 모델을 감싼 컨테이너를 썼다.
  - 테이블은 쿼리 창의 기본 스키마(없으면 기본 데이터베이스, 그것도 없으면 테이블을 담는 연결 자체)에 만든다. 정규화되지 않은 CREATE TABLE이 갈 곳과 같다.
  - 이름은 결과의 원본 테이블 이름(조인이면 첫 테이블, 없으면 `new_table`)이고, 그 스키마에 같은 이름이 있으면 `_1`, `_2` …를 붙인다. 그대로 실행해도 실패하지 않게 하려는 것이다.
  - 기본 키는 넣지 않는다(쿼리 결과에는 테이블의 키 정의가 없음, SWT와 같음).
- **결과 대화상자.** 기존 스크립트 미리 보기 대화상자를 실행 단추 없이 쓴다. 머리글에 "아무것도 실행하지 않았습니다"를 쓴다.
  쿼리 창이 아닌 곳(앞으로 생길 다른 결과 표)에서는 "쿼리 창에서 열기"를 숨기도록 `ResultSetGrid.Host.canOpenScript`를 두었다.
- **생성 옵션 화면은 없다.** SWT의 옵션(정규화된 이름, 간결한 SQL, 자동 생성 열 빼기 등)은 기본값(정규화된 이름 켬, 나머지 끔)으로 만든다.
  탐색기 Generate SQL과 같다.

## upstream 대비 변경점

- 모델: `SQLDialect.supportsMergeStatement`, `SQLGeneratorMergeFromData`·`SQLGeneratorInsertReplaceFromData`(새), `SQLGeneratorResultSet.isUniqueKeyRequired`·`getTableColumns`,
  `SQLGeneratorDeleteFromData`(키 없는 결과의 예외), `SQLGeneratorUpdateFromData`(자동 생성 열을 SET에서 뺌), `PostgreDialect.getColumnTypeModifiers`(정밀도 없는 numeric), "DELETE by Unique Key" 레이블 번역.
- `model.sql`이 `generator.resultset` 패키지를 내보낸다(SWT는 확장점으로만 만들었음).
- SWT와 다른 동작: 결과는 대화상자(복사·쿼리 창에서 열기)이고 옵션 화면이 없다. 키가 필요한 문장은 키가 없으면 꺼진다. MERGE·중복 키 INSERT·결과 DDL의
  스키마와 이름 정하기는 Chromite가 더한 것이다.

## 확인 (2026-11-08)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → `BUILD SUCCESS`, `python tools/rename/gate.py` → `ALL GATES PASSED`.
- 실행: 빌드한 제품을 `CHROMITE_DATA`와 작업 공간을 `D:\Tools\_scratch\chromite-sql-from-results`에 두고 열었다. 화면 조작은 실행 중인 앱 안의
  시험용 Java 에이전트(제품에 들어가지 않음)가 Swing API로 했다(OS 입력·앞 창 없음). 시험 테이블(`p5_items`: 기본 키, 한글·작은따옴표 문자열, NULL,
  날짜, LOB·이진 값, SQL Server는 IDENTITY 열 `seq` / `p5_nokey`: 키 없음)은 공용 Docker DB에 만들고(PostgreSQL·SQL Server·MariaDB DB `chr_sqlgen`,
  Oracle `SYSTEM.CHR_SQLGEN_*`) 끝난 뒤 지웠다.
- 만든 문장이 맞는지는 앱 밖에서 `docker exec`(psql·sqlcmd·sqlplus·mariadb)로 **트랜잭션 안에서 실행한 뒤 롤백**해 확인했다(DDL이 자동 커밋되는
  Oracle·MariaDB는 만든 테이블을 바로 지움). 모든 문장이 오류 없이 실행됐고 롤백 뒤 데이터는 그대로였다.
- **PostgreSQL**(한국어): 메뉴 = SELECT .. WHERE .. =, SELECT .. WHERE .. IN, INSERT, UPDATE, 고유 키로 DELETE, 중복 키가 있을 때 INSERT ›
  (ON CONFLICT DO NOTHING, ON CONFLICT DO UPDATE SET), 이 결과를 담을 CREATE TABLE(MERGE 없음). INSERT 값 `'{"a": 1}'::jsonb`, `decode('0102','hex')`.
  CREATE TABLE → `public.p5_items_1`(같은 이름이 있어 번호). "고유 키로 DELETE" → 쿼리 창에서 열기 → 새 쿼리 창에 `DELETE … WHERE id=3;`, psql에서 행 수 3 그대로(실행 안 됨).
  `p5_nokey`: SELECT … IN·UPDATE가 꺼지고 도구 설명 "결과에 테이블의 고유 키가 없습니다", DELETE는 `WHERE a=2 AND b IS NULL`(고치기 전 예외 경로).
  조인 결과: 행 문장이 모두 꺼지고 "결과가 테이블 하나에서 온 것이 아닙니다", CREATE TABLE은 `doubled numeric NULL`(고치기 전 "Numeric precision 131089" 오류).
- **SQL Server**(한국어): 메뉴에 MERGE가 있고 중복 키 INSERT는 없음. 이름은 `chr_sqlgen.dbo.p5_items`. UPDATE의 SET에서 IDENTITY 열 `seq`가 빠짐
  (고치기 전 sqlcmd "Cannot update identity column 'seq'"). MERGE 원본은 `SELECT 1 AS id, N'Apple ''red''' AS name, … 0x0102 AS img`(seq 없음).
  CREATE TABLE은 쿼리 창의 기본 DB(master)에 `master.dbo.p5_items`로 만들고 머리글에 "master.dbo의 테이블"이라고 씀(실행은 롤백, master에 테이블 없음 확인).
  INSERT는 IDENTITY 열도 넣으므로 확인할 때 `SET IDENTITY_INSERT`를 켰다(아래 "남긴 것").
- **Oracle**(한국어): MERGE(`… FROM DUAL) src`, 별칭에 AS 없음), 중복 키 INSERT › INSERT IGNORE ROW INDEX, 날짜 `TIMESTAMP '…'`, BLOB `'0102'`(암시적 변환으로 실행됨),
  CREATE TABLE `SYSTEM.CHR_SQLGEN_ITEMS_1`(NUMBER(10,0), CLOB, BLOB).
- **MariaDB**(한국어): 중복 키 INSERT › INSERT IGNORE, REPLACE INTO, ON DUPLICATE KEY UPDATE, MERGE 없음. CREATE TABLE `chr_sqlgen.p5_items_1 … ENGINE=InnoDB`.
- **영어**(`-nl en`): 네 DB에서 메뉴 "Generate SQL"(SELECT …, DELETE by Unique Key, MERGE, INSERT on Duplicate Key ›, CREATE TABLE for This Result), 도구 설명
  "The result has no unique key of its table", 대화상자 제목 "Generate SQL - MERGE", 머리글 "MERGE for 1 selected row(s) of chr_sqlgen.dbo.p5_items. Nothing has been executed.",
  단추 Copy / Open in Query Window / Close(기본). SQL Server MERGE → Open in Query Window → 새 쿼리 창에 그 글.
- 확인하면서 고친 것: PostgreSQL 결과의 식 열(`price * 2`) CREATE TABLE 실패(`PostgreDialect`), SQL Server IDENTITY 열 UPDATE 실패(`SQLGeneratorUpdateFromData`),
  MERGE 원본에 쓰지 않는 자동 생성 열이 들어가던 것.
- 확인하지 못한 것: 저장하지 않은 편집이 있는 행에서 만들기(코드 경로만: 편집 모델의 지금 값), 메모리에 없는 큰 LOB(Oracle CLOB 1MB 넘음), 다른 DB(SQLite, Firebird 등의
  중복 키 방식), macOS, 다크 테마.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 표 오른쪽 클릭 메뉴, 스크립트 미리 보기 대화상자(테이블 디자이너·탐색기 변경),
  데이터 전송·테이블 복사의 PostgreSQL 대상 numeric 열 형식, 결과 생성기(UPDATE의 SET에서 자동 생성 열 빠짐), 탐색기 Generate SQL의 "DELETE by Unique Key" 레이블.

## 남긴 것

- 생성 옵션 화면(정규화된 이름 끄기, 간결한 SQL, 자동 생성 열 빼기, 사용자 날짜 형식).
- INSERT는 자동 생성 열(SQL Server IDENTITY)도 넣는다(SWT 기본값과 같음). 그대로 실행하려면 `SET IDENTITY_INSERT`가 필요하다.
- PostgreSQL 15 이상의 MERGE, DB2·H2·Firebird 등 MERGE가 있는 다른 방언의 `supportsMergeStatement`(확인할 서버가 없어 켜지 않음).
- CREATE TABLE의 기본 키(결과의 고유 키로 만들기), 같은 이름의 열이 둘인 결과(조인의 `id`, `id`)는 열 이름이 겹친 문장이 된다.

## 변경 이력

- 2026-11-08 최초 작성(`chromite/sql-from-results`): 결과 표의 "SQL 생성" 메뉴(고른 행의 SELECT·INSERT·UPDATE·DELETE, MERGE, 중복 키 INSERT, 결과를 담을 CREATE TABLE),
  키가 필요한 문장 끄기, DELETE의 키 없는 결과 예외 고침, UPDATE의 SET에서 자동 생성 열 빼기, PostgreSQL 정밀도 없는 numeric의 새 열 형식 고침.
