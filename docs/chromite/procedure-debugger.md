# 프로시저 디버거

## 목적

함수·프로시저를 한 줄씩 실행하며 변수와 호출 스택을 보는 디버거를 Swing 제품에 둔다(사용자 요청 2026-11-07:
"프로시저 디버거는 당연히 구현해야 하고"). 첫 구현은 PostgreSQL(PL/pgSQL, `pldbgapi` 확장)이다.
SWT 제품에는 PostgreSQL 디버거가 있었지만 제품 피처에 들어 있지 않아 사용자가 쓸 수 없었고, SWT 삭제(#68, 커밋 `745f87f8ee`)에서
모델과 화면이 함께 지워졌다([swt-removal.md](swt-removal.md), [swt-parity/C-navigator-tools.md](swt-parity/C-navigator-tools.md) 62번).

## 사용법

- Object Explorer에서 함수·프로시저를 오른쪽 클릭 → **Debug…**. Modify로 연 쿼리 창(원본 편집기)에서는 **Query > Debug…**.
- 디버거 탭이 열리고 루틴 본문이 보인다. 여백(줄 번호 또는 그 왼쪽)을 누르면 중단점이 놓이고, 다시 누르면 지워진다.
- **시작 / 계속(F5)** → 시작 대화상자:
  - "이 인수로 루틴을 실행": 디버거가 자기 연결에서 루틴을 호출한다. 인수는 SQL 식이다(`10`, `'text'`, `NULL`).
  - "다른 세션이 호출할 때까지 기다림"(attach): 다른 세션(앱 서버 등)이 루틴을 호출하면 그 호출에 붙는다. 프로세스 ID를 주면 그 세션만.
- 멈추면 현재 줄에 노란 화살표와 바탕이 보인다. **프로시저 단위 실행(F10)**, **한 단계씩 코드 실행(F11)**, **계속(F5)**, **중지(Shift+F5)**.
  **프로시저 나가기(Shift+F11)**는 pldbgapi에 없어 꺼져 있다.
- 아래 탭: **변수**(이름·형식·값, 인수는 "(인수)" 표시, 멈춘 동안 값 칸을 고치면 서버에 넣는다), **호출 스택**(누르면 그 프레임의 원본·변수),
  **메시지**(RAISE NOTICE 등 서버 알림, 오류), **결과**(호출이 돌려준 행, 최대 200행).
- 끝나거나 중지하면, 디버거가 루틴을 실행한 세션에 커밋하지 않은 트랜잭션이 있을 때 커밋/롤백/취소를 묻는다("트랜잭션 안전").

## 구조

| 번들 | 내용 |
|---|---|
| `com.seizia.chromite.debug.core` (복원) | DB 중립 디버그 모델: `DBGController`, `DBGControllerFactory`, `DBGSession`, `DBGEvent`, `DBGStackFrame`, `DBGVariable`, `DBGBreakpointDescriptor`, `DBGTargetResult`(새), `DBGConstants`, JDBC 기반 `jdbc/DBGJDBCSession`·`DBGJDBCWorker`. UI·Eclipse 디버그 의존 없음 |
| `com.seizia.chromite.ext.postgresql.debug.core` (복원) | pldbgapi 구현: `PostgreDebugController`, `PostgreDebugSession`, 중단점·스택·변수 클래스. `plugin.xml`이 `PostgreProcedure`의 어댑터로 `DBGControllerFactory`를 등록 |
| `com.seizia.chromite.ui.swing` | `DebugCommands`(메뉴 동작, 열기), `ProcedureDebuggerPanel`(디버거 문서 탭), `DebugStartDialog`(시작 대화상자), `internal/DebugMessages`(영어·한국어) |

두 모델 번들은 `plugins/pom.xml`의 `desktop` 프로파일과 `com.seizia.chromite.swing.feature`에 들어간다.

연결 지점:

- `ObjectExplorerPanel`: 오른쪽 클릭 메뉴의 Debug…(Modify 다음). 다른 메뉴처럼 늘 보이고 디버깅할 수 없는 노드에서는 꺼진다.
- `MainFrame`: Query > Debug…(활성 쿼리 창이 Modify로 연 창이고 그 루틴을 디버깅할 수 있을 때 켜짐). `openSourceScript`가 창에 원본 객체를 넣는다
  (`SqlEditorPanel#setSourceObject`).
- `ToolDocument#confirmClose`(새 기본 메서드): 탭을 닫기 전과 앱을 끝내기 전(`requestExit`, `ExitTransactionCheck`보다 먼저)에 부른다.
  디버거 탭은 여기서 세션을 끝내며 트랜잭션을 묻고, 취소하면 탭·앱이 닫히지 않는다.

## 설계 결정

### 복원한 모델과 바꾼 것

`git show 745f87f8ee^:<경로>`에서 복원했다. 원래 `debug.core`는 두 부분이었다.

- DB 중립 모델(`com.seizia.chromite.debug`, `.jdbc`): 복원했다.
- Eclipse 디버그 플랫폼 연결(`debug.core.model`·`breakpoints`·`sourcelookup`, `DatabaseLaunchDelegate`, `DebugUtils`, 실행 구성·마커 확장):
  `org.eclipse.debug.core`·`org.eclipse.core.resources` 마커에 의존하고 SWT Debug 관점용이라 복원하지 않았다. Swing 화면은 모델을 직접 쓴다.
- 쓰는 곳이 없는 것은 복원하지 않았다: `DBGResolver`, `DBGObjectDescriptor`, `DBGDebugObject`, `DBGVariableType`, `PostgreResolver`,
  `PostgreDebugObjectDescriptor`, `PostgreBreakpointAdapterFactory`, 콘솔 시험 도구 `console/Debugger`, NLS 묶음(`DebugMessages`, `DebugCoreMessages`,
  `PostgreDebugCoreMessages`: 모델 예외 문구는 영어로 둔다, AGENTS.md "Hardcode").

바꾼 API와 이유:

| 바꾼 것 | 이유 |
|---|---|
| `DBGControllerFactory.createController(DBSObject 루틴, 구성)`, 어댑터를 연결(`DBPDataSourceContainer`)이 아니라 루틴 클래스(`PostgreProcedure`)에 등록 | Swing에는 실행 구성이 없고 Debug…는 루틴에서 시작한다. 디버거가 있는 드라이버의 루틴에서만 메뉴가 켜지고, 드라이버 클래스는 그 드라이버의 루틴을 어댑트할 때만 읽힌다(테이블 디자이너와 같은 방식) |
| `DBGController.readSource`·`getSourceIdentifier`, `describeBreakpoint(원본, 줄)` | 세션을 열기 전에 원본을 보이고 중단점을 놓기 위해. 마커 속성 맵이 없어졌다 |
| `DBGSession`: `resume`·`suspend` 삭제, `abort`·`readMessages`·`getTargetResult`·`getTransactionState`·`endTransaction` 추가 | `suspend`는 구현이 없었고 `resume`은 `execContinue`와 같았다. 끝내기를 "대상 멈추기"와 "연결 닫기"로 나눠야 트랜잭션을 물을 수 있다 |
| `DBGVariable.getType()`(늘 TEXT) → `getTypeName()`(서버 `format_type`), `isArgument()` | 변수 창에 형식을 보이려고 |
| `DBGJDBCWorker`: 실패를 세션에 알림(`workerFailed`), 실행 중인 문장 취소 | 전에는 실패가 작업 상태로만 남아 화면이 "실행 중"에서 멈췄다 |
| `DBGConstants`: 실행 구성·마커 상수 대신 시작 방식·대상 pid·인수 키 | Swing 시작 대화상자가 만드는 맵 |

`PostgreDebugSession`에서 고친 것:

- **대상 연결을 실행 직후 닫지 않는다.** SWT 버전은 함수가 끝나면 대상 연결을 바로 닫아, 수동 커밋 연결이면 트랜잭션을 조용히 롤백했다.
- **제어 연결은 자동 커밋.** `pldbg_*` 호출만 하는 새 연결이라 열린 트랜잭션이 없다. 수동 커밋 연결에서 제어 연결에 트랜잭션이 열려
  "커밋하지 않은 트랜잭션" 질문에 나오는 것을 막는다. 대상 연결은 연결 설정의 커밋 방식을 따른다.
- **중지:** 멈춘 대상은 `pldbg_abort_target`, 실행 중인 대상은 대상 문장의 JDBC 취소(아래 "pldbgapi 동작" 참고).
- **중단점 SQL은 중단점의 함수 OID를 쓴다.** 전에는 늘 디버깅하는 함수의 OID를 써서, 호출된 함수(Step Into로 들어간 함수)에 중단점을 놓을 수 없었다.
- **attach 모드:** SWT 대화상자는 Global 단추를 꺼 두었고 대상 pid 인수를 쓰지 않았다. 켜고, pid를 전역 중단점의 대상으로 넘긴다.
  끝낼 때 전역 중단점을 `pldbg_drop_breakpoint`로 지우지 않는다(아래).
- **프로시저 호출은 `CALL`.** JDBC escape `{ CALL … }`은 pgjdbc 기본 설정(`escapeSyntaxCallMode=select`)에서 `SELECT`로 바뀌어 프로시저에서 실패한다.
  프로시저의 OUT 인수에는 NULL을 넣는다(PostgreSQL 14+).
- 호출이 디버거에 닿기 전에 실패하면(인수 식 오류 등) "port number" 대신 서버 오류를 보인다.
- 디버깅할 수 있는 언어(`plpgsql`, EDB `edbspl`)인지 시작 전에 확인한다. pldbgapi가 없으면 설치 방법을 알린다.

### Swing 화면

- SWT Debug 관점(여러 보기)을 SSMS 디버거처럼 한 문서 탭에 모았다. 도구 창(도킹)으로 두지 않은 이유: 디버거마다 변수·스택이 달라
  한 쌍의 도구 창을 탭마다 바꿔 끼워야 하고, 도구 창 배치(저장되는 레이아웃)를 디버거 때문에 바꾸지 않기 위해서다.
- 원본 보기는 쿼리 창과 같은 `SqlEditorPanel.SqlTextArea`(구문 강조, 테마 색)를 읽기 전용으로 쓴다. 중단점은 RSyntaxTextArea 여백의 줄 추적 아이콘,
  현재 줄은 화살표 아이콘과 줄 바탕(`addLineHighlight`)이다. 빈 줄에는 중단점을 놓지 않는다.
- 중단점은 화면이 원하는 상태로 들고 있다가 대상이 멈출 때마다 세션에 맞춘다. 실행 중에는 제어 연결이 단계 명령으로 바쁘고,
  attach 모드에서는 대상이 붙기 전에 넣을 수 없기 때문이다. 멈춘 동안 놓은 중단점은 바로 넣는다.
- "원본 편집기"에서 시작: Modify 창은 `CREATE OR REPLACE` 전체 DDL이고 pldbgapi의 줄 번호는 함수 본문(`prosrc`) 기준이라, 중단점 여백은 디버거 탭의
  본문 보기에 둔다. Modify 창에서는 Query > Debug…로 같은 디버거 탭을 연다.
- 인수 입력: 쿼리 창의 매개 변수 대화상자(`SqlParameterDialog`)는 쿼리 글의 `:name` 매개 변수에 묶여 있어 쓰지 않았다. 값 규칙(SQL 식)은 같다.
  마지막 인수는 같은 탭에서 다시 시작할 때 다시 보인다(저장하지 않음).
- 단축키는 Visual Studio·SSMS와 같다. 탭 안에 초점이 있을 때만 받으므로(F5) 쿼리 창의 실행(F5)과 겹치지 않는다.

### 트랜잭션 안전

사용자 규칙(2026-11-07): 세션을 닫는 기능은 커밋하지 않은 트랜잭션을 찾아 커밋/롤백/취소를 묻고, 조용히 커밋·롤백하지 않는다.

- 찾기: 대상 세션 pid의 `pg_stat_activity.state`를 제어 연결로 읽는다. `idle in transaction` → 커밋/롤백/취소,
  `idle in transaction (aborted)`(오류로 끝났거나 중지) → 롤백/취소(커밋할 수 없다).
- 끝내기: 사용자의 답으로 대상 연결의 트랜잭션 관리자가 커밋 또는 롤백한 뒤에 연결을 닫는다. 취소하면 세션과 연결을 두고
  상태 줄에 "열린 트랜잭션"을 알린다. 중지(Shift+F5)·다시 시작·탭 닫기·앱 끝내기에서 다시 묻는다.
- 자동 커밋 연결에서는 호출이 끝날 때 서버가 커밋한다(쿼리 창에서 같은 호출을 실행한 것과 같다). 중지하면 서버가 그 문장을 취소하므로
  그 문장의 변경은 서버가 되돌린다(쿼리 창의 실행 취소와 같다).
- 제어 연결은 자동 커밋이라 묻지 않는다(디버거 API만 호출).
- attach 모드의 트랜잭션은 다른 세션의 것이라 묻지 않는다.

### pldbgapi 동작 (2026-11-07 PostgreSQL 17.10 + pldebugger, 확인한 것)

확인 도구: `D:\Tools\_scratch\procedure-debugger\probe\*.java`(pgjdbc 42.7.14로 직접 호출).

- 줄 번호: 스택(`pldbg_get_stack`)·중단점·변수 선언 줄은 `prosrc`의 1부터 센 줄이다. `$function$` 뒤 줄바꿈 때문에 1번 줄은 빈 줄이다.
  단계 함수(`pldbg_step_over` 등)가 돌려주는 행의 `linenumber`는 스택보다 1 크다(쓰지 않는다).
- 대상이 끝난 뒤 단계 함수는 `08006 select() failed waiting for target` 오류를 낸다. 그 뒤 `pldbg_abort_target`·`pldbg_drop_breakpoint`는
  돌아오지 않을 수 있다(attach 모드에서 drop이 멈춤, 로컬에서 취소 뒤 abort가 멈춤) → 대상이 끝나면 그 호출을 하지 않는다.
- 실행 중(`pldbg_continue` 대기)에 그 명령을 취소하면 대상은 디버거 없이 끝까지 실행된다. 대상 문장을 취소하면 대상이 `57014`로 끝나고
  continue는 `08006`으로 돌아온다 → 로컬 모드 중지는 대상 문장 취소.
- 수동 커밋 대상을 중지하면 대상 세션은 `idle in transaction (aborted)`가 되고, 롤백하면 `idle`이 된다.
- attach: `pldbg_create_listener` → `pldbg_set_global_breakpoint(세션, OID, -1, NULL)` → `pldbg_wait_for_target`이 다른 세션의 호출에서 돌아온다.
  제어 연결을 닫으면 서버가 그 리스너의 전역 중단점을 지운다(닫은 뒤 다시 호출해도 멈추지 않음).
- 권한: 로컬 모드의 `pldbg_oid_debug`는 함수 소유자나 슈퍼유저만 된다(`must be owner or superuser to create a breakpoint`),
  attach 모드의 전역 중단점은 슈퍼유저만 된다(`must be a superuser to create a breakpoint`). 둘 다 슈퍼유저가 아닌 역할로 확인했다.
- 서버 준비: `postgresql-<버전>-pldebugger` 패키지, `shared_preload_libraries = 'plugin_debugger'`, 재시작, 데이터베이스마다 `CREATE EXTENSION pldbgapi`.

## 다른 데이터베이스

| DB | 서버 디버그 API | 판단 |
|---|---|---|
| Oracle | `DBMS_DEBUG_JDWP`(권장), `DBMS_DEBUG`(Oracle이 사용 중단(deprecated)으로 표시, JDWP를 권함). 시험 DB(Oracle AI Database 26ai Free 23.26.3, 127.0.0.1:51521)에 `SYS.DBMS_DEBUG`, `DBMS_DEBUG_JDWP`, `DBMS_DEBUG_JDWP_CUSTOM` 패키지가 VALID로 있고, 권한 `DEBUG CONNECT SESSION`, `DEBUG CONNECT ANY`, `DEBUG ANY PROCEDURE`가 있다(읽기 조회로 확인) | 큰 작업. 설계·추정만(아래) |
| SQL Server | T-SQL 디버거는 SSMS 18에서 빠졌고(Visual Studio의 SQL Server 디버깅 구성 요소와 DCOM을 쓰던 방식), JDBC에서 쓸 수 있는 서버 디버그 API가 없다 | 만들지 않음. 대안 아래 |
| MySQL / MariaDB | 서버 디버그 API 없음. 시험 DB(MariaDB 11.8.9)에 debug 이름의 루틴·플러그인 0개(읽기 조회). 상용 도구는 루틴을 계측(줄마다 상태 기록 호출을 끼운 사본)해 흉내 낸다 | 만들지 않음 |
| Altibase | 알려진 PSM 디버그 API가 없다(출력은 `PRINT`·`DBMS_OUTPUT`). 시험 DB(Altibase 7.3)의 `SYSTEM_.SYS_PROCEDURES_`에 이름에 DEBUG·DBG가 들어간 프로시저·패키지 0개(SYSTEM_ 소유 43개 중, 읽기 조회) | 만들지 않음 |

### Oracle 계획

- 권장: `DBMS_DEBUG_JDWP`. 대상 세션이 `DBMS_DEBUG_JDWP.CONNECT_TCP(host, port)`로 Chromite가 연 JDWP 수신 포트에 접속하고, Chromite는
  JDI(`com.sun.jdi`, `jdk.jdi` 모듈)로 PL/SQL 단위를 Java 클래스처럼 다룬다(SQL Developer 방식). 중단점은 단위의 줄 위치, 변수는 프레임의 지역 변수.
  - 필요: `DEBUG CONNECT SESSION`, 대상 객체 `DEBUG`(또는 `DEBUG ANY PROCEDURE`), 네트워크 ACL `jdwp` 권한(`DBMS_NETWORK_ACL_ADMIN.APPEND_HOST_ACE`,
    Chromite 호스트), 변수를 보려면 `ALTER … COMPILE DEBUG` 또는 `PLSQL_OPTIMIZE_LEVEL=1`. DB 서버가 Chromite PC의 포트에 접속할 수 있어야 한다
    (Docker는 `host.docker.internal`, 방화벽).
  - 런타임: 내장 JRE에 `jdk.jdi`를 넣어야 한다([runtime.md](runtime.md)는 디버그 모듈을 뺐다).
  - 모델: `ext.oracle.debug.core`(새) — `DBGController`·`DBGSession` 구현, JDI 이벤트를 `DBGEvent`로. 화면은 이 문서의 디버거 탭을 그대로 쓴다.
  - 추정: 7–10일(JDI 연결·단위 이름 규칙·패키지 본문 줄 대응 3–4일, 변수·컬렉션 값 2일, ACL·권한 안내·오류 처리 1–2일, 23ai 검증 1–2일).
- 대안: `DBMS_DEBUG`(두 세션, `INITIALIZE`/`ATTACH_SESSION`/`SYNCHRONIZE`/`CONTINUE`/`GET_VALUE`). pldbgapi와 모양이 같아 모델에 잘 맞고
  네트워크 역방향 접속이 없다. 사용 중단 예고된 API이고 23ai에서 동작하는지는 실제 세션으로 확인해야 한다(시험 컨테이너를 고치지 않으려고 하지 않았다).
  추정: 3–5일.
- 공통 위험: 대상 세션의 트랜잭션 처리(로컬 모드)는 PostgreSQL과 같은 방식(대상 연결을 열어 두고 묻기)으로 한다.

### SQL Server 대안 (만들지 않음)

"단계 실행 흉내": 프로시저 본문을 배치·문장 단위로 나눠(파서), 같은 세션에서 `DECLARE`한 변수와 함께 한 문장씩 실행하고 매 문장 뒤
`SELECT @var…`로 변수를 보인다. 제어 흐름(`IF`, `WHILE`, `TRY…CATCH`, `GOTO`)과 중첩 호출을 해석해야 하고, 임시 테이블·트랜잭션·`RETURN`·
출력 매개 변수 의미를 그대로 지키기 어렵다. 추정: 기본(직선 코드 + `IF`/`WHILE`) 8–12일, 실제 디버거와 같은 정확도는 보장할 수 없다.
더 작은 대안: 실행 결과·메시지를 문장별로 보여 주는 "실행 추적"(확장 이벤트 `sp_statement_completed`) 3–4일.

## 제한

- PostgreSQL만 구현했다. PL/pgSQL(EDB SPL) 루틴만 디버깅할 수 있다(SQL·C 함수, 집계·창 함수 제외).
- 로컬 모드는 함수 소유자나 슈퍼유저, attach 모드는 슈퍼유저만 쓸 수 있다(pldbgapi 규칙, 서버 오류가 그대로 보인다).
- 프로시저 나가기(Step Out)가 없다(pldbgapi에 없음).
- attach 모드는 슈퍼유저가 필요하고, 다른 세션의 호출 하나가 끝나면 세션이 끝난다(다시 시작해서 기다린다). 실행 중에 중지하면 다른 세션의 호출은
  디버거 없이 계속된다. 멈춘 상태에서 중지하면 그 호출은 취소된다(SWT와 같음).
- 결과는 처음 200행만 보인다. 값은 서버가 보낸 글자다.
- 중단점과 인수는 탭을 닫으면 사라진다(저장하지 않음).
- 디버거가 멈춰 있는 동안 같은 연결을 끊으면(Disconnect) 끊기 전 트랜잭션 질문이 디버거 대상 연결을 볼 수 있다. 디버거 탭을 먼저 중지하는 것이 안전하다.

## 검증

2026-11-07, 빌드한 Swing 제품의 복사본(한국어 OS)과 임시 PostgreSQL 17.10 + pldebugger 컨테이너(55438, 끝난 뒤 삭제).
화면 조작은 앱 안에서 도는 시험 드라이버(java agent)가 Swing 컴포넌트·동작을 직접 불러 했다(OS 마우스·키 입력 없음, 다른 앱 창에 영향 없음).
메뉴 동작(Object Explorer Debug…, Modify 창의 Query > Debug…), 여백 클릭(여백 컴포넌트에 보낸 마우스 이벤트), 단축키(디버거 탭에 보낸 F5·F10·F11·Shift+F5)는
실제 경로를 탔다. 증거: `D:\Tools\_scratch\procedure-debugger\evidence\`(창 이미지, `driver-log-main.txt`, `driver-log-errors.txt`).

시험 함수 `dbg_demo(p_count, p_label)`: 반복문 안에서 중첩 함수 `dbg_square`를 부르고 `dbg_log`에 넣고 `RAISE NOTICE`, 끝에 `division_by_zero` 예외를 잡는다.

| 경우 | 결과 |
|---|---|
| Object Explorer > 디버그…, 9번 줄 중단점, 시작(3, 'drv-A') | 7번 줄에서 멈춤, F5 → 9번 줄 중단점, F10 → 10번 줄 |
| 변수 값 바뀜 | 1회차 9번 줄 `v_sq=1, i=1` → 2회차 `v_sq=4, i=2, v_total=1`. 변수 창에서 `v_total`을 100으로 바꾸자 이후 `v_total=104`, 알림 `step 2 total 104` |
| 중첩 호출로 들어가기 | 8번 줄에서 F11 → `dbg_square(integer)` 5번 줄, 호출 스택 2프레임, 원본이 `dbg_square`로 바뀜. 스택의 1번 프레임을 누르면 `dbg_demo`의 원본·변수 |
| 중간에 중지(자동 커밋) | 상태 "끝났습니다", 메시지 "디버거가 호출을 중지했습니다". 디버그 세션 2개(제어·대상)가 `pg_stat_activity`에서 사라지고 `pg_locks` 0행 |
| 끝까지 실행 | 결과 `dbg_demo = -5`, 메시지에 알림 3개와 "1개 행" |
| 수동 커밋, Modify 창 > 쿼리 > 디버그…, 11번 줄(INSERT 뒤)에서 멈춤 | 대상 세션 `active`, `dbg_log` `RowExclusiveLock` 등 잠금 보유 |
| 그 상태에서 중지 | "트랜잭션이 실패했습니다 … 롤백만" 질문(롤백/취소). 취소 → 상태 줄 안내, 대상 `idle in transaction (aborted)` 유지. 다시 중지 → 롤백 → 디버그 세션 없음, 잠금 0행, `drv-C` 행 0 |
| 수동 커밋, 끝까지 실행 | "커밋하지 않은 트랜잭션" 질문(커밋/롤백/취소) 동안 대상 `idle in transaction`, 커밋 → 세션 없음, `drv-D` 행 2 |
| attach 모드 | "다른 세션이 … 호출하기를 기다리는 중", 다른 JDBC 세션의 `select dbg_demo(2,'drv-E')`에 붙어 7번 줄에서 멈춤, 단계·계속 뒤 그 호출이 `-5`를 돌려받음, 디버그 세션 없음 |
| SQL 함수(`dbg_sql`)에서 디버그… | 탭을 열지 않고 "Only PL/pgSQL functions and procedures can be debugged (language of dbg_sql: sql)" |
| pldbgapi 없는 DB에서 시작 | 설치 안내 오류, 디버그 세션 남지 않음 |
| 수동 커밋 세션이 멈춘 채 앱 끝내기 | 끝내기 전에 디버거가 대상을 멈추고 실패한 트랜잭션 질문 → 롤백 → 앱 종료, 서버에 세션·잠금 0, `drv-I` 행 0 |

로그: `chromite-debug.log`에 오류 없음(취소·대상 종료에 따른 `57014`·`08006` 디버그 줄만), `.metadata/.log`에 오류 없음.
빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `python tools/rename/gate.py` ALL GATES PASSED.
회귀 테스트는 만들지 않았다(요청 없음).

## 변경 이력

- 2026-11-07 최초 작성: 디버그 모델·PostgreSQL 구현 복원(커밋 `745f87f8ee` 이전), Swing 디버거 탭·시작 대화상자·Debug… 메뉴, 트랜잭션 질문,
  다른 DB 조사.
