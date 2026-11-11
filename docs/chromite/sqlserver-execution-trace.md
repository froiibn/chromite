# SQL Server 실행 추적

## 목적

SQL Server 프로시저를 인수와 함께 한 번 실행하고, 실행된 문장마다 줄·시간·CPU·행 수·읽기·쓰기를 기록해 원본 옆에 보인다.
SQL Server에는 JDBC로 쓸 수 있는 서버 디버그 API가 없어 단계 실행 디버거를 만들지 않았다([procedure-debugger.md](procedure-debugger.md)
"다른 데이터베이스"). 그 문서가 더 작은 대안으로 적은 "실행 추적"(확장 이벤트 `sp_statement_completed`)을 2026-11-08에 만들었다.
어느 줄이 몇 번 실행됐고 어디서 시간이 걸렸는지, 호출된 프로시저·트리거·동적 SQL까지 한 화면에서 본다.

지원 범위: SQL Server 2012 이상과 Azure SQL Managed Instance의 저장 프로시저. Azure SQL Database(서버 범위 XE 없음), Babelfish,
Synapse, Sybase는 대상이 아니다. 함수는 메뉴가 꺼진다(아래 "제한").

## 사용법

- Object Explorer에서 SQL Server 프로시저를 오른쪽 클릭 → **Trace Execution…**(Debug… 다음). Modify로 연 쿼리 창에서는 **Query > Trace Execution…**.
- 추적 탭이 열리고 인수 대화상자가 뜬다. 표는 이름·형식·방향·값이다. 값은 T-SQL 식이다(`10`, `N'text'`, `GETDATE()`, `NULL`).
  - 빈 값: 입력 매개 변수는 넘기지 않아 프로시저의 기본값을 쓴다(기본값이 없으면 서버 오류). OUTPUT 매개 변수는 NULL로 시작한다.
  - 테이블 반환·커서 매개 변수는 값을 넣을 수 없다("빈 값으로 전달").
- **실행**을 누르면 진행 대화상자에서 호출이 돈다. 취소하면 실행 중인 문장이 취소되고 그때까지의 기록을 보인다.
- 끝나면:
  - 왼쪽 원본: 위의 "모듈" 목록(추적한 프로시저와 호출 중에 실행된 모듈). 실행된 줄은 옅은 초록 바탕과 여백 표시이고, 표시에 마우스를
    올리면 "N번 실행, 시간, 행, 논리적 읽기"가 보인다.
  - 오른쪽 **타임라인**: 문장이 끝난 순서. #, 모듈(중첩 수준만큼 들여 씀), 줄, 문장(도구 설명에 전체 글), 시간(ms), CPU(ms), 행, 논리적·물리적
    읽기, 쓰기, 호출 전체에서 그 문장이 차지한 구간 막대. 첫 열 "(호출)"은 호출 전체(EXEC 문)다. 행을 고르면 그 모듈의 원본으로 바꾸고
    그 줄을 노란 바탕으로 표시한다.
  - **줄별**: 같은 모듈·줄을 모아 실행 횟수, 합계·최대 시간, 행, 논리적 읽기. 합계 시간이 큰 순서(느린 줄 찾기). 행을 고르면 그 줄로 간다.
  - **메시지**: PRINT·RAISERROR(심각도 10 이하), 프로시저가 돌려준 결과 집합의 행 수, 오류, 취소, 추적에서 빠진 문장 수.
  - **결과**: 반환 값과 OUTPUT 매개 변수.
- 실행 세션에 커밋하지 않은 트랜잭션이 남으면 커밋 / 롤백 / 취소를 묻는다(취소가 기본, 아래 "트랜잭션 안전").
- 다시 실행은 **추적 실행**(F5). 마지막 인수가 다시 보인다(저장하지 않음).

## 구조

| 위치 | 내용 |
|---|---|
| `com.seizia.chromite.model.admin` `mssql/SQLServerExecutionTrace` (새) | 모델: 대상 읽기(`readTarget`: 모듈 정의, `sys.parameters`의 선언 형식), 실행(`run`: XE 세션 만들기·시작·시험 읽기, 호출, 표지, 버퍼 읽기, 세션 삭제, 모듈 이름·원본 읽기), 트랜잭션 상태·끝내기, `PrivilegeException`(로그인, GRANT 문) |
| `com.seizia.chromite.ui.swing` `ExecutionTraceCommands` (새) | 메뉴 동작(켜짐 = `SQLServerExecutionTrace.canTrace`), 탭 열기(열쇠 = 연결 + DB ID + 개체 ID), 권한 안내 문구 |
| `ExecutionTracePanel` (새) | 추적 문서 탭: 원본(읽기 전용 `SqlTextArea`, 실행된 줄 바탕·여백 표시, 고른 줄), 모듈 목록, 타임라인·줄별·메시지·결과 탭, 트랜잭션 질문(`ToolDocument#confirmClose`) |
| `ExecutionTraceStartDialog` (새) | 인수 대화상자 |
| `internal/TraceMessages` (새, 영어·한국어) | 화면 문구 |
| `ObjectExplorerPanel`, `MainFrame` | Trace Execution… 메뉴(Debug… 다음), 켜짐 |

새 번들은 없다. 모델은 SQL Server 관리 기능이 있는 `model.admin`(이미 `ext.mssql`에 의존)에 두었다. 디버그 모델(`debug.core`)의
`DBGController`에 맞추지 않은 이유: 추적은 멈춤·단계·변수가 없는 "한 번 실행하고 기록 읽기"라 그 모양(이벤트, 중단점, 스택)이 맞지 않는다.

## 설계 결정

### 확장 이벤트 세션

- 이벤트: `sqlserver.sp_statement_completed`(모듈과 동적 SQL 안의 문장. 줄은 모듈 정의 기준, `nest_level`, `object_id`,
  `source_database_id`, `object_type`)와 `sqlserver.sql_statement_completed`(호출 일괄 처리의 문장. EXEC 문이 호출 전체 시간이다).
  필터는 `sqlserver.session_id = 실행 세션` 하나다. 문장 글(`statement`)은 기본값대로 모은다(동적 SQL은 원본이 없어서).
- 대상: `package0.ring_buffer`(`max_events_limit` 20000, `max_memory` 16 MB). `event_file`은 서버 디스크 경로가 필요하고 남는 파일을 지워야
  한다. ring_buffer는 세션을 멈추면 비므로 세션을 지우기 전에 읽는다. 넘치면 오래된 것부터 밀려나므로 메시지에 "N개 중 M개는 추적에 없음"을 보인다
  (`totalEventsProcessed - eventCount`, `droppedCount` 중 큰 값).
- `MAX_DISPATCH_LATENCY = 1 SECONDS`: 이벤트는 1초 안에 버퍼로 간다. 호출 뒤 실행 세션에서 고유한 표지 문장(`SELECT 1 AS chromite_trace_end_<uuid>`)을
  실행하고, 그 글이 버퍼에 보일 때까지(`CHARINDEX`, 250 ms 간격, 최대 10초) 기다린 뒤 읽는다. 한 세션의 이벤트는 순서대로 가므로
  표지가 보이면 호출의 이벤트가 다 들어왔다. 제때 오지 않으면 읽은 것만 보이고 "마지막 문장 일부가 빠졌을 수 있음"을 알린다.
- 읽기: `sys.dm_xe_session_targets.target_data`를 StAX로 문서 순서대로 읽는다(DTD·외부 엔터티 끔). 서버 XQuery `nodes()`의 행 순서는 보장되지
  않아서 클라이언트에서 읽는다.
- `STARTUP_STATE = OFF`, 이름 `chromite_trace_<spid>_<임의 12자>`.

### 연결과 세션

- 연결의 격리 문맥 두 개(디버거와 같은 방식): **실행 세션**은 연결 설정의 커밋 방식을 따르고 호출이 끝난 뒤에도 열어 둔다(트랜잭션 질문).
  **제어 세션**은 자동 커밋이고 XE 세션 만들기·시작·읽기·삭제와 모듈 이름·원본 읽기만 한다. 실행 세션에서 XE DDL을 하지 않는 이유:
  수동 커밋 세션의 열린 트랜잭션과 섞이지 않게, 그리고 추적 읽기 자체가 추적되지 않게.
- "사용자의 세션에서 실행": 같은 로그인·같은 연결 설정의 새 세션이다. 쿼리 창의 세션을 쓰지 않은 이유: 끝난 뒤 트랜잭션을 물어 끝내고 세션을
  닫아야 하는데, 쿼리 창 세션을 닫으면 그 창의 작업이 끊긴다. 임시 테이블·SET 옵션은 쿼리 창과 공유되지 않는다(아래 "제한").
- 실행 세션은 먼저 `USE [루틴의 데이터베이스]`로 간다. 사용자 정의 형식(별칭 형식, 테이블 형식)은 그 데이터베이스에서만 선언할 수 있다.

### 호출 일괄 처리

`EXEC`는 식을 인수로 받지 않으므로(상수·변수만) 매개 변수 형식의 변수에 넣어 넘긴다.

```sql
DECLARE @chromite_return INT;
DECLARE @chromite_p1 int = 3;
DECLARE @chromite_p2 nvarchar(50) = N'drv-A';
DECLARE @chromite_p3 int;
EXEC @chromite_return = [chr_exec_trace].[dbo].[trace_demo] @p_count = @chromite_p1, @p_label = @chromite_p2, @p_total = @chromite_p3 OUTPUT;
SELECT @chromite_return AS [chromite_return_value], @chromite_p3 AS [@p_total];
```

- 형식은 `sys.parameters`·`sys.types`로 만든다. 길이는 바이트라 `nvarchar`·`nchar`는 2로 나누고 -1은 `MAX`, `decimal`·`numeric`은
  (정밀도, 소수 자릿수), `datetime2`·`time`·`datetimeoffset`은 (소수 자릿수), 사용자 정의 형식은 `[스키마].[이름]`, 커서는 `CURSOR`.
  모델의 `DBSProcedureParameter` 형식 이름을 쓰지 않은 이유: `max_length`(바이트)를 그대로 써 `nvarchar(100)`처럼 두 배가 된다.
- 빈 입력 값은 그 매개 변수를 빼서 서버 기본값을 쓴다. `sys.parameters.has_default_value`는 T-SQL 프로시저에서 늘 0이라 기본값이 있는지
  미리 알 수 없다(서버 오류로 알림).
- 결과: 첫 열이 `chromite_return_value`인 마지막 결과 집합이 반환 값·OUTPUT이고(화면은 그 열 이름을 "반환 값"으로 바꿔 보인다), 나머지는
  프로시저의 결과 집합이다(행 수만 보인다). 문장 오류(0으로 나누기 등)는 서버에서 일괄 처리를 멈추지 않는다. 드라이버는 오류를 그 자리에서
  알리므로 `getMoreResults`로 계속 읽어 오류 뒤의 메시지와 출력 SELECT까지 받는다(오류 50개까지). 일괄 처리가 중단된 오류(변환 오류,
  `XACT_ABORT`)나 취소면 출력 SELECT가 없어 결과 탭이 비고 결과 집합은 모두 프로시저의 것이다.
- XE에서 일괄 처리 문장 중 EXEC만 "(호출)" 행으로 남기고(오프셋으로 찾음: XE 오프셋은 바이트라 글자 위치의 두 배), 변수 선언·마지막
  SELECT·표지는 뺀다.

### 권한

- 필요한 것: XE 세션 만들기·시작·삭제(`ALTER ANY EVENT SESSION`, SQL Server 2022는 세분 권한 `CREATE/ALTER/DROP ANY EVENT SESSION` 등도 됨)와
  버퍼 읽기(`VIEW SERVER STATE`, 2022는 `VIEW SERVER PERFORMANCE STATE`도 됨), 프로시저 `EXECUTE`, 원본을 보려면 `VIEW DEFINITION`.
- 확인 방법: 호출 **전에** XE 세션을 만들고 시작하고 버퍼를 한 번 읽어 본다. 실패하면 `HAS_PERMS_BY_NAME`으로 빠진 권한을 찾아
  "로그인 X는 … 만들 수 없습니다. 서버 관리자에게 요청하세요: `GRANT ALTER ANY EVENT SESSION TO [X];` `GRANT VIEW SERVER STATE TO [X];`"를
  보이고 프로시저는 실행하지 않는다. 이름만 보는 사전 확인으로 막지 않은 이유: 2022의 세분 권한만 받은 로그인을 잘못 막는다.
  두 권한이 다 있는데 실패하면(다른 원인) 서버 오류를 그대로 보인다. GRANT는 실행하지 않는다.

### 정리

- XE 세션은 `run`의 finally에서 늘 지운다(`IF EXISTS … DROP EVENT SESSION`, 새 진행 모니터로): 성공, 호출 오류, 취소, 권한 실패, 버퍼 읽기
  실패. 실행은 모달 진행 대화상자라 실행 중에 탭 닫기·앱 끝내기가 끼어들지 않는다.
- 앱이 실행 중에 죽은 경우만 세션이 남을 수 있다. `STARTUP_STATE = OFF`라 서버를 다시 시작하면 멈춘 정의만 남는다. 찾기:
  `SELECT name FROM sys.server_event_sessions WHERE name LIKE 'chromite_trace%'`. 다른 사용자의 추적일 수 있어 다음 실행이 지우지 않는다.

### 트랜잭션 안전

사용자 규칙(2026-11-07): 세션을 닫는 기능은 커밋하지 않은 트랜잭션을 찾아 커밋/롤백/취소를 묻고, 조용히 커밋·롤백하지 않는다.

- 찾기: 호출이 끝나면 실행 세션에서 `SELECT @@TRANCOUNT, XACT_STATE()`. `XACT_STATE() = -1`(커밋할 수 없음) → 롤백/취소, `@@TRANCOUNT > 0` →
  커밋/롤백/취소. 자동 커밋 연결도 묻는다: 프로시저가 `BEGIN TRAN`을 열어 둘 수 있다.
- 끝내기: 문장으로 한다(`WHILE @@TRANCOUNT > 0 COMMIT TRANSACTION` / `IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION`). JDBC `commit()`은 자동 커밋
  연결에서 아무것도 하지 않아 프로시저가 연 트랜잭션이 그대로 남는다.
- 취소하면 세션을 두고 상태 줄에 알린다. **트랜잭션 끝내기**, 다시 실행, 탭 닫기, 앱 끝내기에서 다시 묻는다.
- 상태를 읽을 수 없으면(연결이 끊김) "세션을 닫을까요? 커밋하지 않은 변경은 서버가 롤백합니다"를 세션 닫기 / 취소(기본)로 묻는다.
- 호출은 끝났는데 추적을 읽지 못한 경우(제어 세션 오류)에도 실행 세션은 열어 두고 같은 질문을 한다. 호출 전에 실패하면(권한 등) 실행한 것이
  없으므로 묻지 않고 닫는다.
- 표지 문장은 테이블을 읽지 않아 수동 커밋 세션(`IMPLICIT_TRANSACTIONS ON`)에서 트랜잭션을 열지 않는다.

## upstream 대비 변경점

DBeaver CE에는 SQL Server 실행 추적이 없다(SWT에도 없음). 새 기능이다.

## 제한

- 저장 프로시저만. 스칼라 함수는 SQL Server 2019 이상에서 인라인되면 문장 이벤트가 없고, 함수 호출 일괄 처리 모양도 달라 메뉴를 끈다.
- 실행 세션은 쿼리 창과 다른 세션이다. 쿼리 창에서 만든 `#임시 테이블`, `SET` 옵션, 컨텍스트 정보는 보이지 않는다.
- 테이블 반환 매개 변수는 빈 테이블로만 넘긴다.
- 문장이 20000개를 넘으면 마지막 20000개만 남는다(메시지에 빠진 수). ring_buffer XML이 아주 크면 서버가 잘라 보낼 수 있다.
- 시간은 XE 측정값(마이크로초)이고 문장이 끝난 시각은 밀리초 단위다. 타임라인 막대의 시작은 끝 − 시간으로 그린다.
- 동적 SQL 문장은 원본이 없어 원본 보기에 표시하지 않는다(문장 글은 표의 도구 설명).
- 서버가 그 세션에서 스스로 실행한 문장도 기록된다. 예: 자동 통계 만들기(`SELECT StatMan(...)`)는 "(동적 SQL)"로 보인다(확인함). 그 세션이 실제로
  쓴 시간이라 빼지 않았다.
- 원본은 `sys.sql_modules.definition`이다. 암호화된 모듈이나 `VIEW DEFINITION`이 없는 모듈은 "원본을 읽을 수 없음"으로 보이고 줄 번호만 있다.
- 실행 중에는 진행 대화상자가 앱을 막는다(긴 프로시저는 취소로 멈춘다).

## 검증

2026-11-08, 빌드한 Swing 제품(한국어 OS)을 `CHROMITE_DATA`와 작업 공간을 scratch에 두고 실행했다. 시험 DB는 SQL Server 2022 RTM-CU27
(127.0.0.1:51433), 시험 데이터베이스 `chr_exec_trace`와 로그인 `chr_trace_user`(ALTER ANY EVENT SESSION, VIEW SERVER STATE, 스키마 EXECUTE·
VIEW DEFINITION)·`chr_trace_noperm`(서버 권한 없음)을 만들고 끝난 뒤 지웠다. 연결 세 개(자동 커밋, 수동 커밋, 권한 없음)는 "비밀번호 저장" 꺼짐,
비밀번호는 앱의 인증 대화상자에 넣었다. 화면 조작은 앱 안에서 도는 시험 드라이버(java agent)가 Swing 동작·단추를 직접 불러 했다(OS 마우스·키
입력 없음). 서버 확인은 sa 세션의 `sys.server_event_sessions`·`sys.dm_xe_sessions`·`sys.dm_exec_sessions`·`sys.dm_tran_locks`.

시험 프로시저: `trace_demo(@p_count, @p_label, @p_total OUTPUT)`(반복문에서 `trace_square`를 부르고 `trace_log`에 INSERT, 트리거 `trg_trace_log`,
결과 집합, `RETURN`), `trace_dyn`(sp_executesql, EXEC(...)), `trace_open_tran`(BEGIN TRAN만), `trace_error`(0으로 나누기), `trace_slow`(WAITFOR 반복),
`trace_types`(decimal, nvarchar, 기본값 있는 varchar(max), datetime2(3), OUTPUT).

| 경우 | 결과 |
|---|---|
| Object Explorer > Trace Execution…(trace_demo, 자동 커밋), 인수 3, N'drv-A', 빈 OUTPUT | 문장 42개, "(호출)" 행 1개. 모듈 열은 trace_demo → 들여 쓴 trace_square → 더 들여 쓴 trg_trace_log. 결과 `반환 값=14 @p_total=14`, 메시지 "결과 집합 1: 3개 행". 실행된 줄 9개 표시. Debug…는 꺼짐(SQL Server) |
| 타임라인에서 trace_square 행 | 원본이 trace_square로 바뀌고 4번 줄 표시(실행된 줄 3개), 트리거 행 → trg_trace_log 4번 줄, trace_demo INSERT 행 → 14번 줄 |
| 줄별 | 합계 시간 순: trace_demo 12번 줄(EXEC trace_square, 3번), trace_square 6번 줄(INSERT, 3번) … 고르면 12번 줄 표시 |
| 실행 중 / 끝난 뒤 XE | `trace_slow` 실행 중 `chromite_trace_63_…` running, 끝난 뒤(성공·오류·취소·권한 실패 모두) `chromite_trace%` 0개 |
| 수동 커밋, Modify 창 > Query > Trace Execution… | "커밋하지 않은 트랜잭션" 질문(커밋/롤백/취소) 동안 실행 세션 `open_transaction_count=1`. 취소 → 상태 줄 안내, 트랜잭션 끝내기 켜짐, 세션 유지. 트랜잭션 끝내기 → 롤백 → 추적 세션 0개, `drv-B` 행 0 |
| 자동 커밋, `trace_open_tran` | 서버 오류 266(BEGIN/COMMIT 수 불일치)이 메시지에, 질문 동안 실행 세션 KEY X·OBJECT IX 잠금. 커밋 → 세션 0개, `drv-C` 행 1 |
| `trace_error` | 메시지 "before the error", "after the error"(오류 뒤도 읽음), "오류: Divide by zero error encountered.", 결과 `반환 값=-6` |
| `trace_dyn` | 동적 SQL 두 문장이 "(동적 SQL)"로, 메시지 PRINT와 결과 집합 2개 |
| `trace_types`(12.5, N'abc', 빈 값, '2026-11-08T10:11:12.345', 빈 OUTPUT) | 선언 형식 `decimal(10, 2)`, `nvarchar(20)`, `varchar(MAX)`, `datetime2(3)`, `nvarchar(30)`. 빈 @v는 기본값 'dflt', 결과 `반환 값=7` |
| `trace_slow` 30초, 4초 뒤 진행 대화상자 취소 | 상태 "호출을 취소했습니다. 문장 14개", WAITFOR 4번까지 기록, XE 세션 0개 |
| 권한 없는 로그인 | 탭은 열리고 실행에서 오류: "로그인 chr_trace_noperm은(는) … 만들 수 없습니다 … `GRANT ALTER ANY EVENT SESSION TO [chr_trace_noperm];` `GRANT VIEW SERVER STATE TO [chr_trace_noperm];`". `drv-E` 행 0(실행 안 함), XE·추적 세션 0개 |
| 수동 커밋, 질문 취소 뒤 앱 끝내기 | 끝내기 전에 같은 질문 → 롤백 → 앱 종료. 서버에 `drv-I` 행 0, 추적 세션 0개, XE 세션 0개 |

로그: `.metadata/.log`에 오류 없음, `chromite-debug.log`에 오류·경고 없음(호출 일괄 처리 디버그 줄만).
증거: `D:\Tools\_scratch\chromite-sqlserver-exec-trace\evidence\`(창 이미지, `driver-log.txt`).
빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `python tools/rename/gate.py` ALL GATES PASSED.
회귀 테스트는 만들지 않았다(요청 없음).

## 변경 이력

- 2026-11-08 최초 작성: XE 세션(sp/sql_statement_completed, ring_buffer, 표지 대기), 호출 일괄 처리, 권한 확인·GRANT 안내, 정리, 추적 탭
  (타임라인·줄별·메시지·결과, 실행된 줄 표시), 트랜잭션 질문, Object Explorer·Query 메뉴.
