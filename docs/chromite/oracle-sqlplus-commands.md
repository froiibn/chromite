# Oracle 쿼리 창의 SQL*Plus 명령

## 목적

기업 환경 사용자 보고(앱 변경 #46, 2026-11-09): "오라클에서 SET SERVEROUTPUT ON 명령어가 오류가 발생한다".

`SET SERVEROUTPUT ON`은 SQL이 아니라 SQL*Plus(Oracle 명령줄 클라이언트) 명령이다. Chromite는 이 줄을 SQL 문장으로 보고 서버에 보냈고,
서버는 SQL로 받지 않는다(`SET`으로 시작하는 SQL은 `SET TRANSACTION`, `SET ROLE`, `SET CONSTRAINTS`뿐이어서 ORA-00922 등). 사내 스크립트는
SQL*Plus로 만든 것이 많아서 같은 원인으로 다른 SQL*Plus 줄도 실패했다.

## 근본 원인 (고치기 전, origin/devel `5d4780584c`)

- Oracle 방언에는 SQL*Plus 줄을 알아보는 규칙이 없었다. 문장 나누기(`SQLScriptParser`)는 세미콜론과 빈 줄로만 나누므로, 세미콜론 없는
  SQL*Plus 줄들은 다음 SQL과 한 문장으로 붙어 서버에 갔다. upstream의 클라이언트 명령은 `@` 접두어 명령(`@set`, `@include`, `@echo` …)과
  MySQL `SOURCE`, Exasol `DEFINE`(방언 규칙 `TPRuleProvider` CONTROL 위치)뿐이다. Oracle 방언 규칙(`OracleDialectRules`)은 q-문자열 규칙만 있었다.
- `/`만 있는 줄도 문장 구분자가 아니었다(Oracle 방언의 문장 구분자는 `;`만). 그래서 PL/SQL 블록 뒤의 `/`가 다음 문장의 첫 글자가 되어
  그 문장이 실패했다. Altibase 방언은 문장 구분자에 `/`를 넣어 처리한다(`AltibaseSQLDialect.getScriptDelimiters`).
- 확인(고치기 전 빌드, 앱 안 시험 에이전트로 Oracle 방언 `SQLScriptParser.parseScript`): 아래 스크립트가 9개 문장으로 나뉘었고 SQL*Plus 줄이
  하나도 명령으로 나오지 않았다.

  | 문장 | 나뉜 글자(요약) |
  |---|---|
  | #1 | `SET SERVEROUTPUT ON SIZE UNLIMITED` + `SET DEFINE OFF` + `set echo off` + `SET FEEDBACK ON` |
  | #2 | `SET LINESIZE 200` … `PROMPT === start ===` + `SPOOL out.log` + `BEGIN … END` |
  | #3 | `/` + `EXEC …` + `execute …` |
  | #5 | `/` + `SELECT 'x' AS prompt FROM dual` |
  | #9 | `SPOOL OFF` + `PROMPT` + `SET SERVEROUTPUT OFF` + `SELECT 1 FROM dual` + `/` |

## 동작

Oracle 연결의 쿼리 창(그리고 Oracle 방언을 물려받는 YashanDB)에서, **줄 첫 칸에서 시작하는** 다음 줄은 서버에 보내지 않고 Chromite가 처리한다.
세미콜론은 없어도 되고 있어도 된다.

| 줄 | 처리 | Messages 탭 (DBMS_OUTPUT 줄은 Server Output 탭) |
|---|---|---|
| `SET SERVEROUTPUT ON [SIZE n\|UNLIMITED] [FORMAT …]` (`SET SERVEROUT`) | 이 쿼리 창이 쓰는 세션에서 `DBMS_OUTPUT.ENABLE(n)`(UNLIMITED는 `NULL`, SIZE가 없으면 무제한). 연결 설정은 바꾸지 않음 | `SET SERVEROUTPUT ON: 이 쿼리 창의 세션에서 서버 출력을 켰습니다(버퍼: 무제한). 연결 설정은 바뀌지 않습니다.` FORMAT이 있으면 무시했다는 줄 |
| `SET SERVEROUTPUT OFF` | 그 세션에서 `DBMS_OUTPUT.DISABLE` | `… 서버 출력을 껐습니다 …` |
| `SET DEFINE OFF` (`SET SCAN OFF`) | 이 실행의 나머지 문장에서 매개변수를 묻지 않음(매개변수 대화상자의 "무시"와 같음) | `SET DEFINE OFF: 이 실행의 나머지 문장에서 매개변수를 묻지 않습니다.` |
| `SET DEFINE ON` | 다시 물음 | `… 매개변수를 다시 묻습니다.` |
| `SET DEFINE ^` 등 다른 치환 문자 | 무시 | `…: SQL*Plus 클라이언트 설정이라 무시했습니다.` |
| `SET ECHO/FEEDBACK/TIMING/LINESIZE/PAGESIZE/VERIFY/HEADING/TERMOUT/TRIMSPOOL/LONG/…` (SQL*Plus 줄임 포함) | 무시 | `SET ECHO OFF: SQL*Plus 클라이언트 설정이라 무시했습니다.` |
| `SPOOL 파일`, `SPOOL OFF` (`SPO`) | 무시 | 위와 같은 무시 안내 |
| `PROMPT 글` (`PRO`) | 글을 Messages에 씀(빈 `PROMPT`는 아무것도 쓰지 않음) | 그 글 |
| `EXEC 호출` (`EXECUTE`) | `BEGIN 호출; END;`로 바꿔 평범한 문장처럼 실행(DBMS_OUTPUT, 오류, 트랜잭션 처리가 같음) | 그 문장의 메시지 |
| `/`만 있는 줄 | 문장 끝. 세미콜론 없는 앞 문장을 끝내고, PL/SQL 블록 뒤의 `/`는 빈 문장이라 버려짐 | 없음 |

`SET SERVEROUTPUT`의 세션 값은 그 세션이 살아 있는 동안 연결 설정("DBMS_OUTPUT 읽기", Oracle 설정 페이지)보다 앞선다. 문장 전에 설정에
맞추는 동작(`syncServerOutput`)이 되돌리지 않고, 문장 뒤에 출력을 읽을지도 세션 값으로 정하며, 다시 연결(`initializeContextState`)해도 유지된다.
연결을 끊고 다시 연결하면 새 세션이므로 설정을 따른다.

## 구조

| 위치 | 역할 |
|---|---|
| `ext.oracle` `sql.OracleSqlPlus`(새로 만듦) | 줄이 SQL*Plus 명령인지와 무엇을 하는지(종류, 옵션 이름, 나머지 글). 규칙과 처리기가 같은 판단을 씀 |
| `ext.oracle` `sql.OracleSqlPlusRule`(새로 만듦) | 줄 첫 칸의 명령 줄을 `SQLControlToken("oracle.sqlplus")`로, `/`만 있는 줄을 `SQLDelimiterToken`으로 돌려주는 토큰 규칙 |
| `ext.oracle` `sql.OracleDialectRules` | CONTROL 위치에 `OracleSqlPlusRule`을 더함(upstream MySQL `SOURCE`와 같은 자리) |
| `ext.oracle` `sql.OracleSqlPlusCommandHandler`(새로 만듦), `plugin.xml`의 `com.seizia.chromite.sqlCommand` `oracle.sqlplus` | 명령 실행. 엔진(`SQLQueryExecuteJob`)이 다른 `@` 명령처럼 부름 |
| `ext.oracle` `model.OracleDataSource.OracleOutputReader` | `setSessionServerOutput`(세션 값 기억 후 `ENABLE`/`DISABLE`), `isServerOutputEnabled(context)`(세션 값 → 설정), `enableServerOutput`이 세션 값의 버퍼 크기를 씀, `syncServerOutput`·`initializeContextState`가 세션 기준 |
| `ext.oracle` `model.OracleExecutionContext` | 세션 값(`getServerOutputOverride`, `getServerOutputSize`) |
| `model` `exec.output.DBCServerOutputReader` | 기본 메서드 `isServerOutputEnabled(DBCExecutionContext)`(세션 값이 없는 읽기 도구는 설정 값) |
| `ui.swing` `SqlEditorPanel.readServerOutput` | 출력을 읽을지 세션 기준으로 물음(한 줄). 설정이 꺼진 연결에서 `SET SERVEROUTPUT ON`한 창의 출력을 읽기 위해 |
| `ext.oracle` `internal.OracleMessages`(+`_ko`) | `sqlplus_*` 문구 |

## 설계 결정과 이유

- **방언 토큰 규칙으로 줄 단위로 알아본다.** SQL*Plus 명령은 한 줄이고 세미콜론이 없어도 된다. 문장을 나눈 뒤에 문장 글자를 보면 이미 다음
  SQL과 붙어 있다(위 근본 원인). upstream이 MySQL `SOURCE`, Exasol `DEFINE`에 쓰는 같은 확장점(`TPRuleProvider` CONTROL, `SQLControlToken`,
  `sqlCommand` 확장)을 따랐다. 엔진·쿼리 창은 바꾸지 않았다(쿼리 창은 출력 읽기 판단 한 줄만).
- **줄 첫 칸에서만, SQL과 겹치지 않는 모양만.** 제어 토큰은 SQL 문장 중간에 오면 문장을 끊는다(upstream 파서 동작). 그래서
  - upstream `SQLFullLineRule`처럼 줄 첫 칸에서만 알아본다. 들여 쓴 PL/SQL 본문의 `EXECUTE IMMEDIATE` 같은 줄을 오인하지 않는다.
    들여 쓴 `  SET SERVEROUTPUT ON`은 지금처럼 서버로 간다.
  - `SET`은 SQL*Plus 시스템 변수 이름(줄임 포함)일 때만, 그리고 뒤가 `=`가 아닐 때만 받는다. `UPDATE t` 다음 줄의 `SET echo = 1`,
    `ALTER SESSION` 다음 줄의 `SET TIME_ZONE = …`은 SQL로 남는다(`TIME_ZONE`은 `_` 때문에 `TIME`과 다른 낱말).
  - `EXEC`는 뒤가 `IMMEDIATE`(PL/SQL)나 `ON`(`GRANT` 다음 줄의 `EXECUTE ON`)이면 받지 않는다.
  - `PROMPT`는 뒤가 `, . )`로 시작하면 받지 않는다(선택 목록 줄 첫 칸의 열 이름 `prompt`). `PROMPT === 시작 ===`처럼 `=`로 시작하는
    글은 흔해서 막지 않는다.
  - 앞 줄(공백·빈 줄 건너뜀)이 `,`나 `(`로 끝나면 문장이 이어지는 줄이라 명령으로 보지 않는다(`SELECT a,` / `prompt` / `FROM t`).
  - 남는 위험: 위에 걸리지 않는 SQL 중간의 줄 첫 칸 명령 모양(예: `WHERE` 다음 줄의 `prompt = 1`)은 명령으로 보여 문장이 끊긴다.
- **`SET SERVEROUTPUT`은 창 세션 기준, 설정은 그대로.** 사용자가 정한 연결 설정을 스크립트 한 줄이 바꾸면 다른 창·다음 연결까지 바뀐다.
  SQL*Plus의 `SET SERVEROUTPUT`도 그 세션에만 해당한다. 이미 있는 서버 출력 경로(`OracleOutputReader`, `DBMS_OUTPUT.ENABLE/DISABLE`,
  문장 뒤 `GET_LINE` 읽기, [swing-server-output.md](swing-server-output.md))를 그대로 쓰고, 세션 값만 더했다. 쿼리 창이 연결의 기본 세션을
  함께 쓰는 설정(창마다 따로 연결하지 않음)이면 그 기본 세션을 쓰는 창이 모두 같은 값을 본다.
- **SIZE 없는 `ON`은 무제한.** SQL*Plus 10.2부터의 기본과 같다. 설정으로 켠 세션은 지금처럼 1,000,000바이트다.
- **`SET DEFINE OFF`는 매개변수 "무시"와 같게.** SQL*Plus의 `&` 치환에 해당하는 Chromite 기능은 매개변수(`:name`, 설정한 접두어,
  `${var}`)다. 매개변수 대화상자의 Ignore가 이미 "이 실행의 나머지 문장에서 묻지 않음"(`SQLScriptContext.setIgnoreParameters`)이므로 같은
  동작으로 했다. 실행 단위다(다음 실행은 다시 묻는다). 기본 설정에서 `&`는 매개변수 접두어가 아니므로 `'A&B'` 같은 글자는 원래 묻지 않는다.
- **`SET AUTOCOMMIT`은 알아보지 않는다.** 무시하면 사용자는 자동 커밋이라 믿는데 트랜잭션이 열려 있고, 따르면 묻지 않고 커밋 방식을 바꾼다
  (트랜잭션 안전 규칙). 서버로 가서 지금처럼 오류가 나고 스크립트가 멈춘다. 커밋 방식은 도구 막대·상태 줄에서 바꾼다.
- **`EXEC`는 익명 블록으로.** SQL*Plus가 하는 그대로다. 엔진의 `SQLControlResult.transform`(upstream `@include`가 쓰는 길)으로 평범한 문장이
  되므로 서버 출력·오류 위치·트랜잭션 표시가 다른 문장과 같다.
- **`/`는 줄 단위 구분자.** Altibase처럼 방언의 문장 구분자에 `/`를 넣으면 `SELECT 4/2 FROM dual` 같은 나눗셈에서도 문장이 끊긴다.
  `/`만 있는 줄만 구분자로 한다. 파서가 블록 안이라고 보는 동안의 구분자는 무시되므로(upstream 동작), 블록을 잘못 센 경우까지 `/`가 강제로
  끝내지는 않는다.
- **`@oracle.sqlplus`.** upstream 명령 등록 방식 때문에 `@oracle.sqlplus …` 줄도 명령 규칙에 걸린다(MySQL `@mysql.source`와 같음). 이 형태는
  SQL*Plus 줄이 아니므로 처리기가 `Not a SQL*Plus command` 오류를 낸다.

## 다른 DB

- **Altibase:** 문장 구분자에 `;`와 `/`가 있다(`AltibaseSQLDialect.getScriptDelimiters`). 그래서 `/`만 있는 줄은 이미 문장을 끝낸다. 대신 줄
  가운데의 `/`도 구분자라 `SELECT 4/2 FROM dual`이 두 문장으로 나뉜다(아래 확인 표, 범위 밖이라 고치지 않고 보고만 한다). SQL*Plus 줄 규칙은
  Altibase에 붙이지 않았다: Altibase의 클라이언트 iSQL 명령은 SQL*Plus와 다르고(`SET SERVEROUTPUT`이 없음, 출력은 PRINT 콜백), Altibase
  서버가 `EXEC`를 SQL로 받는지 등은 확인하지 못했다(테스트 VM에 Altibase가 없다).
- **Tibero:** Chromite에 Tibero 드라이버·방언이 없다(저장소에 `tibero` 없음). 확인할 대상이 없다.
- **YashanDB:** 방언(`YashanDBSQLDialect`)과 실행 문맥이 Oracle 것을 물려받으므로 같은 규칙과 처리기가 적용된다(확인하지 않음).

## upstream 대비 변경점

- upstream DBeaver는 Oracle의 SQL*Plus 줄을 서버에 보낸다(같은 오류). Chromite는 위 명령과 `/` 줄을 클라이언트에서 처리한다.
- `DBCServerOutputReader`에 세션 기준 `isServerOutputEnabled(DBCExecutionContext)` 기본 메서드를 더했다.

## 남은 일과 한계

- `VARIABLE`, `PRINT`, `DEFINE 이름 = 값`, `COLUMN … FORMAT`, `WHENEVER SQLERROR`, `REM`, `@파일`(SQL*Plus의 `@`는 Chromite의 명령 접두어),
  줄 끝 `-` 이어 쓰기는 처리하지 않는다(요청 범위 밖).
- 들여 쓴 SQL*Plus 줄은 알아보지 않는다(위 설계 결정).

## 확인 (2026-11-11, Windows, 이 브랜치 빌드, 빈 작업 공간, 프로세스 안 시험 에이전트)

빌드 `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS.

이날은 로컬 Docker Desktop이 시작하지 못해(`sailor-ingest.sock`) 연결 없이 돌릴 수 있는 부분만 확인했다. 실제 Oracle 확인은 아래 2026-11-12 절.

- **문장 나누기**(Oracle 방언, `SQLScriptParser.parseScript`): 근본 원인 절의 스크립트가 고친 뒤 23개 요소가 되었다. `SET …` 9줄, `PROMPT`,
  `SPOOL`, `EXEC`/`execute`가 각각 명령(`oracle.sqlplus`), `BEGIN … END` 뒤 `/`는 사라지고 다음 문장 앞에 붙지 않음, `SELECT 1 FROM dual` 다음
  `/`가 문장을 끝냄. SQL로 남아야 하는 줄도 그대로 SQL이었다: `UPDATE t` / `SET echo = 1`, `ALTER SESSION` / `SET TIME_ZONE = …`,
  `GRANT` / `EXECUTE ON …`, `CREATE PROCEDURE … EXECUTE IMMEDIATE …`, `SELECT a,` / `prompt` / `FROM t`, `SELECT f(` / `prompt` / `)`,
  `SELECT prompt, b …`, `SELECT 4/2 FROM dual`, 들여 쓴 `  SET SERVEROUTPUT ON`(설계대로 명령 아님). 줄임(`set serverout`, `pro`, `spo`, `exec`)도 됐다.
- **명령 처리**(엔진과 같은 `SQLScriptContext.executeControlCommand` → `plugin.xml` 등록 → 처리기, 연결 없는 스크립트 문맥):
  `SET DEFINE OFF` → 매개변수 무시 켜짐과 안내, `SET ECHO/FEEDBACK/LINESIZE/PAGESIZE/TIMING/VERIFY/HEADING`·`SPOOL` → 무시 안내,
  `PROMPT === start ===` → `=== start ===`, `EXEC dbms_output.put_line('exec')` → `BEGIN dbms_output.put_line('exec'); END;`로 바뀜,
  `exec chr_sp_p;` → `BEGIN chr_sp_p; END;`, `SET SERVEROUTPUT …`(연결 없음) → `SET SERVEROUTPUT works only in an Oracle connection` 오류.
- **Altibase 방언**(같은 스크립트): SQL*Plus 줄은 모두 SQL로 붙어 서버로 간다(이번 변경 범위 밖, 위 "다른 DB"). `/` 줄은 이미 구분자다.
  `SELECT 4/2 FROM dual`은 `SELECT 4`와 `2 FROM dual` 두 문장으로 나뉜다(기존 동작, 문장 구분자에 `/`가 있어서).

## 확인 (2026-11-12, Oracle 26ai Free 23.26 `FREEPDB1` AL32UTF8과 PostgreSQL 17, GCP 테스트 VM, `origin/devel` #228까지 병합한 빌드)

빈 작업 공간·설정·`CHROMITE_DATA`, 프로세스 안 시험 에이전트(Swing 객체 직접 조작). Oracle 연결의 "DBMS_OUTPUT 읽기" 설정은 **끔**으로 두고
창의 `SET SERVEROUTPUT`만으로 출력이 나오는지 봤다. 임시 스키마 `CHR_ORACLE_SQLPLUS`(로그인 없는 스키마).
#226 뒤로 결과 영역은 Messages와 Server Output 탭으로 나뉜다. 명령 안내는 Messages(스크립트 출력 `MessagesWriter`), DBMS_OUTPUT 줄은
Server Output(`readServerOutput` → `output`)에 들어갔다.

| 스크립트 | Messages | Server Output |
|---|---|---|
| `SET SERVEROUTPUT ON` + `BEGIN put_line('line-on-1') END;` + `/` | `SET SERVEROUTPUT ON: … 켰습니다(버퍼: 무제한)`, 명령 완료 | `line-on-1` |
| 같은 창에서 `SET SERVEROUTPUT OFF` + 블록 | `… 껐습니다 …`, 명령 완료 | 비어 있음(설정도 꺼져 있으므로 읽지 않음) |
| `SET SERVEROUT ON SIZE 2000 FORMAT WRAPPED` + 블록 + `SELECT 1 FROM dual` + `/` | `버퍼: 2000`, FORMAT 무시 안내, 명령 완료, `(1 row affected)` | `size-2000` |
| `set serveroutput on size unlimited format word_wrapped;` + 블록 | `버퍼: 무제한`, FORMAT 무시 안내 | `unlimited` |
| `SET SERVEROUTPUT ON SIZE abc` + `SELECT` | `Invalid SET SERVEROUTPUT SIZE: abc`, 스크립트가 거기서 멈춤(오류 처리 설정 "멈춤") | 비어 있음 |
| `SET ECHO/FEEDBACK/TIMING/LINESIZE/PAGESIZE/VERIFY/HEADING/TERMOUT/TRIMSPOOL`, `PROMPT hello from prompt`, `SPOOL C:	emp\out.log`, `SELECT`, `SPOOL OFF` | 줄마다 무시 안내, `hello from prompt`, `(1 row affected)` | 비어 있음 |
| `SET SERVEROUTPUT ON`, `EXEC chr_oracle_sqlplus.say('exec1')`, `execute …('exec2');`, `exec dbms_output.put_line('exec3');` | 켬 안내, 명령 완료 3번 | `say: exec1`, `say: exec2`, `exec3` |
| `SELECT 10 … ` + `/` + `SELECT 20 …` + `/` (세미콜론 없음) | `(1 row affected)` 2번, 결과 탭 2개 | 비어 있음 |
| `SET DEFINE OFF` + `SELECT :p FROM dual;` | 무시 안내 뒤 매개변수 대화상자 없이 서버로 가서 ORA-01008(값 없는 바인드). 다시 실행하면 드라이버가 "JDBC 드라이버 내부 오류"로 알림 | 비어 있음 |
| 섞인 배포 스크립트(`SET SERVEROUTPUT ON SIZE 1000000`, `SET DEFINE OFF`, 화면 `SET`, `SPOOL`, `PROMPT` 4번, `CREATE TABLE`, `'A&B'` INSERT, `CREATE PROCEDURE … /`, `EXEC` 세미콜론 없음, `DECLARE … /`, `UPDATE` / `SET echo = 5`, `SELECT a,` / `prompt`, `COMMIT`) | 설정·무시 안내, PROMPT 4줄, 문장마다 행 수, 오류 없음 | `rows: 1`, `name: A&B` |

- **PostgreSQL 17** 연결은 바뀌지 않았다: `SET search_path TO public;`은 서버에서 실행되고 `RAISE NOTICE`는 Server Output에 나왔다.
  같은 `SET SERVEROUTPUT ON` 스크립트는 지금처럼 서버로 가서 `syntax error at or near "ON"`이다(Oracle 규칙은 Oracle 방언에만 붙는다).
- 한국어 화면(`-nl ko`)에서 `sqlplus_*` 문구가 깨지지 않고 나왔다.
- 확인하지 못한 것: Altibase(테스트 VM에 없음), YashanDB, KO16MSWIN949 DB(1522)에서의 실행, 다시 연결한 뒤 세션 값 유지.

회귀 테스트는 실행하지 않았다(CLAUDE.md 7번).

## 변경 이력

- 2026-11-11 최초 작성: Oracle 쿼리 창의 SQL*Plus 명령(`SET SERVEROUTPUT`, `SET DEFINE`, 화면 `SET`, `PROMPT`, `EXEC`, `SPOOL`)과 `/` 줄
  (앱 변경 #46, `chromite/oracle-sqlplus`)
- 2026-11-12 실제 Oracle(GCP 테스트 VM)에서 확인, #226 결과 영역에서 안내는 Messages·DBMS_OUTPUT은 Server Output 탭으로 가는 것 확인
