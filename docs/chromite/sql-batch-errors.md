# 배치의 문장 오류

## 목적

여러 문장을 한 번에 서버로 보내는 실행(SQL Server `GO` 배치, 프로시저 호출, 선택한 글자를 한 문장으로 실행)에서 뒤 문장의 오류가
사라지지 않게 한다. 오류를 숨기는 쿼리 도구는 정확성과 안전 문제다(후속 #37, 브랜치 `chromite/mssql-lost-error`).

- 오류마다 Messages 탭에 그 문장의 줄 번호 머리글(`Msg 208, State S0002, Line 3`)과 함께 쓴다.
- 실행은 실패로 끝난다(상태 줄 "Query completed with errors", 기록은 오류, 스크립트는 오류 처리 설정대로 멈춤).
- 앞 문장의 결과 표와 행 수는 남는다. 오류 뒤에도 서버가 실행한 문장의 결과와 행 수를 읽어 보인다.
- 트랜잭션은 묻지 않고 커밋하거나 롤백하지 않는다. 수동 커밋 세션은 열린 트랜잭션이 그대로 남고 상태 줄에 보인다.

## 문제

SQL Server에서 아래 스크립트를 F5로 실행하면 "Query executed successfully"로 끝났다. 208 오류는 Messages 맨 위에 보통 글자(경고)로만 나왔다.

```sql
SELECT * FROM dbo.t1;
UPDATE dbo.t1 SET v = v WHERE id = 1;
SELECT * FROM dbo.no_such_table;
```

같은 원인으로 다음도 틀렸다(고치기 전 빌드에서 확인).

| 경우 | 고치기 전 |
|---|---|
| 위 스크립트, 전체를 선택하고 Ctrl+Enter | 같음(성공, 오류는 경고) |
| 수동 커밋, `UPDATE …; SELECT * FROM dbo.no_such_table; UPDATE …` | 성공으로 표시. 첫 UPDATE는 열린 트랜잭션에 남음 |
| `SELECT …; SELECT 1/0; UPDATE …; SELECT …` | 오류는 보였지만 뒤 UPDATE(실제로 실행됨)와 SELECT 결과가 안 보임 |
| `UPDATE … 1/0 …; UPDATE …; RAISERROR(…); SELECT 5` | 첫 오류만 머리글, 두 번째는 첫 오류 글에 붙음. 실행된 UPDATE 행 수와 SELECT 5 결과가 안 보임 |
| `EXEC dbo.proc`(안에서 SELECT, RAISERROR, UPDATE, SELECT) | 성공으로 표시, 오류는 경고 |
| SQL 스크립트 작업으로 같은 `EXEC` | 작업이 오류 없이 끝남 |

`GO`로 나눈 경우, 오류 문장을 혼자 실행(Ctrl+Enter)한 경우, 배치의 첫 문장이 208로 실패한 경우는 원래 오류가 보였다.

## 근본 원인

문장 나누기는 원인이 아니다. SQL Server 방언은 `GO`를 배치 구분자로 가지므로 `GO` 사이의 문장을 SSMS처럼 한 번에 보낸다
([swing-frontend.md](swing-frontend.md) "GO 배치"). 같은 배치에서 `DECLARE`한 변수를 이어서 쓰려면 그래야 한다. SWT DBeaver는 `;`로 나눠 문장마다
보냈지만 그러면 배치 변수가 깨진다. 그래서 묶음은 그대로 두고 결과 읽기를 고쳤다. 원인은 세 가지가 겹쳐 있었다.

1. **오류를 경고로 바꿈.** SQL Server 드라이버는 배치 안의 문장 오류를 결과를 읽다가 그 자리에서 예외로 알린다(`getMoreResults`, `ResultSet.next`,
   첫 문장이면 `execute`). 예외 뒤에도 다음 결과를 읽을 수 있다. 모델은 이런 드라이버를 `DBPDataSourceInfo.isMultipleResultsFetchBroken()`(#2792,
   SQL Server·Sybase·jTDS)로 표시한다. 그런데 실행 엔진(`SQLQueryExecuteJob`)은 이 플래그일 때 `getMoreResults` 예외를 통계의 경고로 넣고 다음 결과로
   넘어갔고, 쿼리 오류로 기록하지 않았다. 작업 실행기(`SQLScriptProcessor`)는 로그에만 썼다. 행을 일부 읽은 뒤 난 읽기 오류도 경고로 바꿨다.
2. **오류 뒤 결과를 읽지 않음.** 첫 문장 `execute`나 결과 읽기(`ResultSet.next`)에서 오류가 나면 엔진은 바로 실패하고 문장을 닫았다. SQL Server는 문장 단위
   오류(0으로 나누기, `RAISERROR`, 키 위반 등) 뒤에도 배치의 나머지를 실행하므로, 실제로 실행된 뒤 문장의 결과가 안 보였다.
3. **오류 사슬을 읽으면 결과가 사라짐.** SQL Server 드라이버의 `SQLException.getNextException()`은 다음 오류를 찾으려고 문장의 남은 결과를 미리 읽고,
   그 사이의 행 수와 결과 집합을 버린다(드라이버 13.4.0으로 확인: 사슬을 읽지 않으면 `UPDATE` 행 수 2와 `SELECT 5`가 오고, 읽으면 둘 다 없음).
   `SQLServerDataSource.discoverErrorType`이 암호 만료·로그인 실패를 찾으려고 모든 오류의 사슬을 읽었고, 쿼리 관리자는 문장이 끝날 때마다
   (`QMMConnectionInfo.endExecution` → `DBExecUtils.isExecutionCanceled`) 이 함수를 불렀다. 그래서 1, 2를 고쳐도 첫 문장 오류 뒤의 결과가 사라졌다.

## 구조

| 위치 | 하는 일 |
|---|---|
| `model.sql` `exec/SQLBatchErrors` (새 파일) | 오류 뒤를 계속 읽을 수 있는지(`canReadPast`), 다음 결과로 넘어가며 문장 오류 모으기(`nextResults`), 첫 문장 `execute` 오류 뒤에 실행된 결과가 있으면 이어 읽기(`resumeAfterExecuteError`) |
| `model.sql` `exec/SQLQueryExecuteJob` | 위 셋으로 결과 읽기. 읽기 오류는 행 수와 상관없이 쿼리 오류로 모음. 모은 오류가 있으면 문장이 예외를 던지지 않았어도 쿼리 실패(`lastError`). 경고 경로(`statistics.addWarning`/`getWarnings`) 삭제 |
| `model.sql` `exec/SQLScriptProcessor` | SQL 스크립트 작업: 같은 처리. 모은 오류는 결과를 다 읽은 뒤 로그에 씀 |
| `model` `sql/SQLQueryResult` | `addError`, `getErrors()`: 첫 오류는 `getError()` 그대로, 뒤 오류는 순서대로 |
| `model` `DBPDataSourceInfo` | `isMultipleResultsFetchBroken()` 설명을 실제 뜻으로 고침 |
| `ext.mssql` `model/SQLServerDataSource` | `discoverErrorType`: 로그인 오류(18456, 18487, 18488)일 때만 오류 사슬을 읽음 |
| `ui.swing` `SqlEditorPanel.reportQueryResult` | 오류마다 머리글과 위치(오류로 가기)를 Messages와 SQL 터미널에 씀. 오류가 있어도 행 수를 씀 |
| `model.jdbc` `JDBCDataSource.refreshObject` | 연결 새로 고침에서 엔진의 데이터 원본 정보(위 플래그)를 버리지 않음(2026-11-12, 아래 "새로 고침 뒤 결과와 오류가 사라짐") |

## 설계 결정과 이유

- **계속 읽을지는 오류 자신과 원인만 보고 판단한다.** 원인 3 때문에 결과를 다 읽기 전에는 오류 도우미(`discoverErrorType`)를 부르거나 오류를 로그에 쓰지
  않는다(`Log`도 사슬을 쓴다). 대신 SQLSTATE로 연결 끊김(`08xxx`), 취소·시간 초과(`HY008`), jConnect 드라이버 오류(`JZ0xx`)를 가리고, 진행 모니터의 취소,
  닫힌 문장, 연속 1000번 오류에서 멈춘다. 이때는 예전처럼 예외를 던지므로 그 경우만 실행기의 복구(다시 연결 후 다시 실행)가 동작한다.
- **모은 오류로는 복구나 스마트 커밋 되돌리기를 하지 않는다.** 배치의 앞 문장이 이미 실행됐으므로 다시 실행하면 두 번 실행되고, 자동 커밋으로 되돌리면
  열린 트랜잭션을 묻지 않고 커밋한다. 그래서 모은 오류는 실행 함수(`executor`) 밖에서 쿼리 실패로 만든다. 트랜잭션은 사용자가 상태 줄이나 Query 메뉴로
  커밋·롤백한다(트랜잭션 안전 규칙).
- **SQL Server를 이름으로 검사하지 않는다.** `isMultipleResultsFetchBroken()`이 true인 드라이버(같은 플러그인의 Sybase, jTDS 포함)가 같은 경로를 쓴다.
  다른 드라이버는 `getMoreResults` 오류에서 예전처럼 바로 실패한다. 행을 일부 읽은 뒤 난 읽기 오류만 경고 대신 쿼리 오류가 된다.
- **첫 문장 오류 뒤에 결과가 없으면 예전 경로 그대로다.** 문장 하나의 오류, 배치를 끝내는 오류(208 등 이름 확인 오류는 SQL Server가 배치를 멈춤)는 예외를
  그대로 던진다. 그래서 단일 문장의 동작(복구, 스마트 커밋 되돌리기)은 바뀌지 않는다.
- **Messages에는 행 수를 먼저, 오류를 나중에 쓴다.** SSMS는 문장 순서대로 섞어 쓰지만, 순서를 맞추려면 결과와 오류의 순서를 따로 기록해야 한다.
  오류 머리글에 줄 번호가 있어 어느 문장인지 알 수 있으므로 이 순서로 두었다. "Commands completed successfully"와 실행 시간 줄은 오류가 없을 때만 쓴다.
- **`getError()`는 첫 오류로 둔다.** 기록, 예약 실행 알림, 계획처럼 오류 하나만 보는 곳은 바꾸지 않았다. 여러 오류는 쿼리 창만 모두 보인다.

## 다른 드라이버

| 드라이버 | 확인 결과 |
|---|---|
| PostgreSQL(pgjdbc 42.7.14) | `;`로 나눠 문장마다 실행한다. 선택한 여러 문장을 한 번에 보내면 드라이버가 `execute`에서 첫 오류를 던지고 앞 결과는 버린다(드라이버 동작). 오류는 항상 보임 |
| MariaDB(3.5.10) | `;`로 나눠 실행한다. `allowMultiQueries=true`여도 드라이버가 `execute`에서 오류를 던진다. 기본값에서는 여러 문장 선택이 구문 오류. 오류는 항상 보임 |
| Sybase, jTDS | SQL Server와 같은 플래그와 같은 데이터 원본 클래스라 같은 경로를 쓴다. 서버가 없어 실행하지 못함 |

## 확인

Docker SQL Server 2022(드라이버 13.4.0), PostgreSQL 17, MariaDB 11에서 각자 만든 `chr_mssql_lost_error` 데이터베이스로, 영어와 한국어 화면에서 확인했다.
같은 시나리오를 고치기 전 빌드(2026-11-08 테스트 빌드)에서도 돌려 비교했다. 화면 조작은 앱 안에서 Swing API로 했다(화면 입력 없음).

| 시나리오 | 고친 뒤 |
|---|---|
| 문제의 스크립트, F5 | 실패. `(3 rows affected)`, `(1 row affected)`, `Msg 208, State S0002, Line 3` + 메시지. 결과 표 1개(3행) |
| 같은 스크립트 전체 선택 Ctrl+Enter | 같음 |
| 셋째 줄만 Ctrl+Enter | 실패, `Line 3` (전과 같음) |
| `GO`로 나눔 | 배치마다 행 수, 셋째 배치 `Line 5` 오류 (전과 같음) |
| 오류가 첫 문장 | `Line 1` 오류, 서버가 배치를 멈춰 뒤 문장 실행 안 됨, 결과 표 없음 (전과 같음) |
| `SELECT; SELECT 1/0; UPDATE; SELECT` | 실패. 행 수 3·2·1, `Line 2` 오류, 결과 표 3개(가운데 0행) |
| `UPDATE …1/0…; UPDATE; RAISERROR; SELECT 5` | 실패. 경고 "The statement has been terminated.", 행 수 2·1, `Line 1`과 `Line 3` 오류 두 개, SELECT 5 결과 |
| `SELECT 1/0; SELECT 1/0; SELECT 2` | `Line 1`, `Line 2` 오류 두 개, SELECT 2 결과 |
| `SELECT; RAISERROR; UPDATE; RAISERROR; SELECT 5` | 행 수 3·2·1, `Line 2`, `Line 4` 오류, 결과 표 2개 |
| 수동 커밋 `UPDATE m1; SELECT 없는 표; UPDATE m2` | 실패, `(1 row affected)`, `Line 2`. `@@TRANCOUNT` 1, m1만 반영(배치 중단), 상태 줄 열린 트랜잭션 |
| 수동 커밋 `UPDATE n1; SELECT 1/0; UPDATE n2` | 실패, 행 수 1·1, `Line 2`. n1·n2 모두 열린 트랜잭션에 있음(전에는 n2가 Messages에 안 보였음) |
| `EXEC dbo.le_proc` | 실패, 결과 표 2개, 행 수, `proc failure` 오류 |
| SQL 스크립트 작업 `EXEC dbo.le_proc` | 작업 실패 "Script execute failed" ← `proc failure` (전에는 오류 없음) |
| PostgreSQL·MariaDB 같은 스크립트 F5 | 문장마다 실행, 행 수 3·1, 셋째 문장 오류, 실패 (전과 같음) |

## 새로 고침 뒤 결과와 오류가 사라짐 (2026-11-12, 앱 변경 #76)

### 증상

확인 작업(2026-11-12, devel 59571d2a36)에서 SQL Server 배치가 첫 결과만 보였다. `SELECT 0 AS a; SELECT 1 AS b`는 Result 1만,
`SELECT 0 AS a; RAISERROR('e16', 16, 1); SELECT 1 AS b`는 오류가 보고되지 않고 "Query executed successfully", 기록은 SUCCESS,
`SELECT 1/0; SELECT 1/0; SELECT 2`는 오류 하나만 보이고 SELECT 2 결과가 없었다. 같은 글을 JDBC로 직접 보내면 모두 나왔다.

### 회귀가 아님

위 "확인" 표의 커밋(42c7f0bec1, 2026-11-08)과 devel(8b8eca369b)을 빌드해 같은 세 경우를 돌렸다. 둘 다 새로 연결한 연결에서는 모든 결과와
오류가 보였고, 연결을 새로 고친 뒤에는 둘 다 위 증상이 났다. 이분 탐색할 "깨뜨린 커밋"은 없다. 결함은 포크 전부터 있었고
(upstream `db00b4ddec`, 2016), 확인 작업은 같은 연결에서 `CREATE DATABASE`·`DROP DATABASE`(#33 확인)를 먼저 실행해서 이 상태에 들어갔다.

### 근본 원인

`JDBCDataSource.refreshObject`가 데이터 원본 정보를 연결 전의 빈 정보(`new JDBCDataSourceInfo(container)`: 제품명 "?", 트랜잭션 없음,
여러 결과 없음)로 바꿨다. 새로 고침에서 `initialize()`를 다시 부르는 엔진(PostgreSQL, MySQL·MariaDB, Oracle, Altibase, DB2, 일반 JDBC)은
곧 새 정보를 만들지만, SQL Server의 `SQLServerDataSource.refreshObject`는 캐시만 비우고 `super.refreshObject`를 불러 빈 정보가 남았다.
그 뒤로 이 연결은 `supportsMultipleResults()`와 `isMultipleResultsFetchBroken()`이 false라서 실행 엔진(`SQLQueryExecuteJob`,
`SQLScriptProcessor`)이 첫 결과 뒤에 읽기를 멈추고(뒤 결과와 뒤 문장 오류가 읽히지 않음), 첫 문장 오류 뒤를 읽지 않았다(`SQLBatchErrors.canReadPast`).
`supportsTransactions()`도 false가 되었다(`JDBCExecutionContext.isSupportsTransactions`).

새로 고침은 두 곳에서 일어난다.

- 개체 탐색기에서 연결을 새로 고침(`DBNDatabaseNode.refreshNode` → `DataSourceDescriptor.refreshObject`).
- 쿼리 창의 DDL 뒤 탐색기 갱신(`SqlMetadataRefresh`, 2026-11-08 #160): `CREATE DATABASE`·`DROP DATABASE`처럼 연결 전체가 대상인 문장 뒤.

같은 원인을 가진 엔진: `refreshObject`에서 `initialize()`를 부르지 않는 SQL Server(같은 클래스를 쓰는 Azure SQL), Cubrid, Dameng, Denodo,
Exasol, IoTDB, Mimer. 여기서 서버로 확인한 것은 SQL Server뿐이다.

### 고침

`JDBCDataSource.refreshObject`는 정보를 그대로 둔다. 같은 서버에 연결된 채로 새로 고치므로 엔진이 만든 정보가 그대로 맞고,
서버를 다시 읽는 엔진은 전처럼 `initialize()`로 새 정보를 만든다. 실행 엔진, Swing 쿼리 창, 예약 실행은 고치지 않았다(원인이 아님).
그 덕에 `initialize()`를 다시 부르는 엔진도 새로 고치는 동안 잠깐 빈 정보가 보이던 틈이 없어졌다.

### 확인 (2026-11-12)

GCP 테스트 VM(SQL Server 2022, 드라이버 13.4.0; PostgreSQL 17; MariaDB 11.8; Oracle 23ai Free)에서 각자 만든 `chr_batch_results_lost`
데이터베이스로, 앱 안 테스트 에이전트(Swing API, 화면 입력 없음)로 확인했다. "전"은 devel 8b8eca369b, "후"는 이 고침이다. 둘 다 연결을
새로 고친 뒤 실행했다(전에는 정보가 `JDBCDataSourceInfo`로 바뀌고, 후에는 `SQLServerDataSourceInfo` 그대로).

| SQL Server 경우 | 전 | 후 |
|---|---|---|
| `SELECT 0 AS a; SELECT 1 AS b` | Result 1만, 행 수 하나, 성공, 기록 rows=1 | Result 1·2, 행 수 둘, 성공, 기록 rows=2 |
| `SELECT 0; RAISERROR(16); SELECT 1` | Result 1만, 오류 없음, "Query executed successfully", 기록 SUCCESS | Result 1·2, `Line 2` 오류, "completed with errors", 기록 ERROR |
| `SELECT 1/0; SELECT 1/0; SELECT 2` | `Line 1` 오류 하나, 결과 없음 | `Line 1`, `Line 2` 오류, SELECT 2 결과 |
| PRINT, SELECT, UPDATE, RAISERROR, SELECT, PRINT | SELECT 결과 하나, 성공(UPDATE·오류·SELECT 5 안 보임) | 결과 둘, 행 수 2·1·1, `Line 4` 오류, 실패 |
| 오류가 먼저(`RAISERROR; SELECT`) | 오류만, 결과 없음 | 오류와 뒤 SELECT 결과 |
| 오류가 끝(`SELECT; SELECT; RAISERROR`) | 결과 하나, 성공 | 결과 둘, `Line 3` 오류, 실패 |
| `GO` 두 배치, 첫 배치에 오류 | 첫 배치 오류 숨김, 둘째 배치까지 실행, 성공 | 첫 배치 결과 둘과 `Line 2` 오류, 실패로 멈춤(오류 처리 설정 STOP) |
| 실제 계획 켬, `SELECT; RAISERROR; SELECT` | 결과 하나, 성공 | 결과 둘은 표로, 계획은 실행 계획 탭으로, 오류, 실패 |
| `EXEC dbo.br_multi`(SELECT, RAISERROR, UPDATE, SELECT) | 결과 하나, 성공 | 결과 둘, 행 수, `pm` 오류, 실패 |
| `SET NOCOUNT ON`; SELECT, UPDATE, RAISERROR, SELECT | 결과 하나, 성공 | 결과 둘, 오류, 실패 |
| 최대 행 수(200)를 넘는 결과 뒤 RAISERROR, SELECT | 200행 결과만, 성공 | 200행(멈춤 알림) + 뒤 결과, 오류, 실패 |
| 다시 읽기(결과별 문장) | Result 1 = `SELECT 0 AS a` | Result 1 = `SELECT 0 AS a`, Result 2 = `SELECT 1 AS b`. 앞에 RAISERROR가 있는 결과는 문장을 정하지 못해 다시 읽기를 거절(sql-batch-reread.md 설계대로) |
| 수동 커밋 `UPDATE; SELECT 1/0; UPDATE` | 행 수 하나, 성공으로 표시, 열린 트랜잭션 표시 | 행 수 1·1, `Line 2` 오류, 실패, 열린 트랜잭션 표시(`@@TRANCOUNT` 1, 두 UPDATE 모두 트랜잭션 안). 커밋·롤백은 사용자가 한 것만 |
| 쿼리 창에서 `DROP DATABASE`(DDL 뒤 갱신) 다음 같은 배치 | 정보가 빈 정보로 바뀌고 오류 숨김(전 빌드에서 확인) | 정보 유지, 결과 둘과 오류, 실패 |

| 다른 실행 경로 (SQL Server, 새로 고침 뒤) | 전 | 후 |
|---|---|---|
| SQL 터미널 | 첫 표만, 성공 | 표 둘, 행 수, 오류, 실패 |
| Query Library 예약 실행(`QueryScheduler`) | OK "1 row" | ERROR `e16` |
| Chromite Agent `runSql`·`runReadOnlySql`·`runScript` | 새로 고침과 무관(JDBC를 직접 읽음). `runSql`은 오류를 알림 | 같음 |
| `@export` | 여러 문장은 전처럼 거절 | 같음 |

| 다른 엔진 (새로 고침 전·뒤) | 결과 |
|---|---|
| PostgreSQL, 두 SELECT를 선택해 한 번에 | 새로 고침 뒤에도 정보 유지, 결과 둘(전·후 같음) |
| MariaDB, 결과 둘인 프로시저 / 결과 뒤 SIGNAL | 결과 둘 / 드라이버가 `execute`에서 오류를 던져 앞 결과 없음, 오류 보임(전·후 같음, 위 "다른 드라이버") |
| Oracle, `DBMS_SQL.RETURN_RESULT` 두 개 | 첫 결과만(전·후 같음). Oracle 정보가 여러 결과를 지원하지 않는다고 함: 이 고침의 범위 밖 |
| MySQL 8.4, Altibase | 실행하지 않음(MySQL 드라이버는 내려받아야 함, Altibase 드라이버는 따로 둠). 둘 다 새로 고침에서 `initialize()`를 부름(코드 확인) |

## 한계

- 프로시저 안의 오류 줄 번호는 SQL Server가 프로시저 본문 기준으로 알려 준다. 머리글의 줄은 그 숫자를 `EXEC` 문장 위치에 더해 계산하므로 편집기 줄과 맞지 않을 수 있다(전과 같음).
- PostgreSQL에서 여러 문장을 선택해 한 번에 실행하면 서버가 오류 위치를 실패한 문장 기준으로 알려 주는데, 머리글은 선택 시작 기준으로 계산한다(전과 같음).
- 결과 표의 다시 읽기(필터, 정렬, 다음 페이지)가 배치 전체를 다시 실행하던 문제는 [sql-batch-reread.md](sql-batch-reread.md)에서 고쳤다(결과를 만든 SELECT만 실행).

## upstream 대비 변경점

- `SQLQueryExecuteJob`(SWT `SQLQueryJob`에서 옮긴 코드)의 #2792 처리: 경고 대신 쿼리 오류, 오류 뒤 결과 읽기.
- `SQLScriptProcessor`의 #2792 처리: 로그만 쓰던 것을 작업 실패로.
- `SQLServerDataSource.discoverErrorType`: 로그인 오류일 때만 오류 사슬을 읽음.
- `SQLQueryResult`에 여러 오류.
- `JDBCDataSource.refreshObject`: 데이터 원본 정보를 빈 정보로 바꾸지 않음(upstream은 바꿈).

## 변경 이력

- 2026-11-08 최초 작성: 배치 뒤 문장 오류를 쿼리 오류로 보고, 오류 뒤 결과 읽기, SQL Server 오류 사슬 미리 읽기 회피 (후속 #37)
- 2026-11-08 한계의 "다시 읽기가 배치 전체를 실행" 항목을 고침([sql-batch-reread.md](sql-batch-reread.md), 후속 #40)
- 2026-11-12 연결 새로 고침 뒤 SQL Server 배치의 뒤 결과와 오류가 사라지던 결함: `JDBCDataSource.refreshObject`가 엔진의 정보를 버리지 않음
  (앱 변경 #76, "새로 고침 뒤 결과와 오류가 사라짐")
