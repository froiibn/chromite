# Chromite Agent: 도구 카탈로그, 권한 정책, 로컬 MCP 서버, AI 엔진, 채팅 화면 (1·2단계)

## 목적

Chromite 안에서 AI 모델이 DBA처럼 일하게 한다. 사용자가 "이 쿼리 느린데 튜닝해 줘", "지금 DB 상태 어때?", "왜 막혀 있어?",
"이 프로시저 로직 검토해 줘" 같은 일을 맡기면, 모델이 도구를 골라 조합해 근거를 모으고 답한다. 정해 둔 시나리오만이 아니라
아무도 미리 적지 않은 DBA 문제도 도구를 조합해 풀 수 있어야 한다(사용자 결정, 2026-11-07 ~05:20).

같은 도구 카탈로그를 두 곳에서 쓴다.

- (a) 앱 안의 Chromite Agent 채팅 창(2단계, 아래 "2단계 구현").
- (b) 이 PC에서만 접속할 수 있는 로컬 MCP 서버. Claude Code 같은 외부 에이전트가 Chromite의 연결로 일한다.

1단계(모델 계층)와 2단계(채팅 화면, 설정 화면, 확인 대화상자)의 **계약**이다. 코드와 다르면 코드가 사실이고, 이 문서를 고친다.

## 명칭

이 기능의 공식 이름은 **Chromite Agent**다(사용자 결정, 2026-11-07: "이 앱의 에이전트 기능은 ai도우미가 아니다. 공식적으로 Chromite Agent 라는 용어를 사용하라"). 영어와 한국어 화면 모두 옮기지 않고
"Chromite Agent"로 쓰고, 한국어 조사는 소리대로 붙인다("Chromite Agent가", "Chromite Agent에게", "Chromite Agent 설정"). "AI 도우미", "AI Assistant", "도우미"로
이 기능을 부르지 않는다. 바꾸는 것은 사용자에게 보이는 글과 그 이름이 드러나는 값이다. 화면 문구, 모델 번들이 내는 오류 글, 서버 세션 목록에 보이는
격리 문맥 이름(아래 "실행 문맥"), 채팅 시스템 프롬프트의 첫 줄("You are Chromite Agent, the database agent inside Chromite, …", 모델이 자기를 소개할 때
이 이름을 쓰게)이다. 클래스·번들·확장 포인트 ID, 설정 키, 저장 이름, MCP 도구 ID, 도구 설명은 그대로 둔다. "AI 제공자"("AI provider")처럼 AI가 모델과
그 회사를 뜻하는 문구, MCP로 붙는 "외부 에이전트"·"다른 AI 도구"도 그대로다.

## 사용자 결정 (2026-11-07)

| 항목 | 결정 |
|---|---|
| 도구 수 | DB를 다루는 AI 에이전트에 필요한 도구를 가능한 한 많이 |
| 제공자 | Claude(Anthropic Messages API, 도구 사용), ChatGPT(OpenAI), 로컬 모델(Ollama) |
| 쓰기 도구 | 데이터·스키마·서버 상태를 바꾸는 도구. 연결별·도구별로 자동 실행을 켤 수 있다. 기본값은 "실행 전 확인" |
| 노출 | 앱 안 채팅과 로컬 MCP 서버가 같은 카탈로그를 쓴다 |
| 목표 시나리오 | 느린 쿼리 튜닝, DB 상태 점검, 느린 쿼리 원인 분석(아무것도 바꾸지 않음), Query Library 모니터링 쿼리와 기준값 판정 |
| 일반성 | 위 시나리오는 예시다. 블로킹·잠금 원인, 프로시저·함수·트리거 로직 검토까지 도구 조합으로 처리한다 |

## 구조

```
                ┌─────────────── 2단계 ───────────────┐
 채팅 화면(Swing) ── AIAssistantImpl.callFunction ──┐   확인 대화상자(Swing): AIToolConfirmationHandler 구현
                                                   │   편집기 연결(Swing):   AIEditorBridge 구현
 MCP 클라이언트 ── HTTP 127.0.0.1 ── AIMcpServer ──┤
                                                   ▼
                                         AIToolExecutor (model.ai)
                       연결 찾기 → 접근 분류(정적 + 호출별) → 읽기 전용 연결 검사 → AIToolPolicy 판정
                       → (CONFIRM이면) 확인 처리기 → 도구 실행 → 결과 크기 제한 → AIToolAuditLog 기록
                                                   │
                                   com.seizia.chromite.ai.function 확장점
                                   (기존 3개 + 이 작업의 도구, 한 레지스트리)
                                                   │
                                   도구 구현 (model.ai.tools 번들)
                       AgentSql: 에이전트 전용 격리 실행 문맥, 읽기 전용 보호, JSON 변환, 실행 중 문장 취소
                       진단 SQL 자원(diag/*.sql, DB 종류별 블록) + 후처리(계획·교착 상태 XML 해석, 블로킹 사슬 트리)
```

### 번들과 패키지

| 위치 | 내용 |
|---|---|
| `plugins/com.seizia.chromite.model.ai` (기존) | 확장점 `com.seizia.chromite.ai.function`에 속성 `access`, `writes` 추가(`schema/com.seizia.chromite.ai.function.exsd`). `AIFunctionDescriptor.getAccess()`, `getWrites()` |
| `model.ai` 패키지 `com.seizia.chromite.model.ai.agent` (새) | 정책·감사·실행기·확인·편집기 연결 등 채팅과 MCP가 함께 쓰는 부분: `AIToolAccess`, `AIToolWrites`, `AIToolPermission`, `AIToolClient`, `AIToolOutcome`, `AIToolPolicy`, `AIToolDecision`, `AIToolConfirmationHandler`, `AIToolConfirmationRequest`, `AIToolConfirmationAnswer`, `AIPendingTransaction`, `AIToolTransactionGuard`, `AIEditorBridge`, `AIAgentServices`, `AIToolAuditLog`, `AIToolExecutor`, `AIToolCallContext`, `AIToolCallResult`, `AIToolAccessClassifier`, `AIToolPreviewProvider`, `AIToolArgumentException`, `AIToolCatalog`, `AIToolConnections` |
| `model.ai` 패키지 `com.seizia.chromite.model.ai.engine.anthropic` (새) | Anthropic 엔진(Messages API, 도구 사용) |
| `plugins/com.seizia.chromite.model.ai.tools` (새 번들) | 도구 구현(`...model.ai.tools`: `ConnectionTools`, `MetadataTools`, `QueryTools`, `PlanTools`, `DataTools`, `DdlTools`, `TransferTools`, `AdminTools`, `AgentJobTools`(1b), `NativeBackupTools`(1b), `LibraryTools`, `DiagnosticSqlTool`, 공통 `AgentTool`, `AgentSql`, `AgentSqlClassifier`, `AgentJson`, `AgentNames`, `AgentValues`, `PlanParser`, `DiagnosticScript`, `DiagnosticPostProcessor`, `PermissionProbe`, `LibraryReader`, `ThresholdEvaluator`), 진단 SQL 자원(`.../tools/diag/<도구>.sql` 32개), MCP 서버(`...model.ai.tools.mcp`: `AIMcpServer`, `AIMcpSettings`, `AIMcpPluginService`, `AIAgentPlaybooks` + `playbooks/*.md`). SQL Server 백업·복원 모델(`model.admin`)과 `ext.mssql`에 의존하므로 `model.ai`와 나눴다. PostgreSQL·MySQL 백업·복원 도구 때문에 `ext.postgresql`, `ext.mysql`, `tasks.native`에도 의존한다(1b) |
| `features/com.seizia.chromite.swing.feature` | 새 번들을 Swing 제품에 넣는다 |

모델 번들이라 SWT·Swing에 의존하지 않는다(AGENTS-Architecture.md). 화면이 필요한 곳(확인 대화상자, 쿼리 창 열기)은 인터페이스만 두고
2단계의 Swing 코드가 구현해 `AIAgentServices`에 등록한다.

### 실행 문맥

- 도구는 사용자의 쿼리 창이나 탐색기 메타데이터 문맥을 쓰지 않는다. 연결(그리고 `database` 인수)마다 **에이전트 전용 격리 문맥**
  (`DBSInstance.openIsolatedContext`, 이름 "Chromite Agent" = `AgentSql.CONTEXT_NAME`)의 작은 풀(최대 4개)을 쓴다. 이유:
  - 에이전트가 자동 커밋이나 읽기 전용 상태를 바꿔도 사용자의 트랜잭션에 영향이 없다.
  - 사용자의 열린 트랜잭션에 에이전트 문장이 섞이지 않는다.
  - 오래 걸리는 진단 쿼리가 탐색기의 메타데이터 읽기를 막지 않는다.
- **DBA가 에이전트 세션을 찾는 법**: 문맥 이름은 드라이버가 서버에 알리는 프로그램 이름의 뒷부분이 된다(`DBUtils.getClientApplicationName`:
  `<제품 이름과 버전> - Chromite Agent`). PostgreSQL `pg_stat_activity.application_name`, SQL Server `sys.dm_exec_sessions.program_name`(64자에서 자름)
  등에서 `LIKE '%- Chromite Agent'`로 거른다(드라이버마다 이 값을 서버에 넘기는지는 다르다). 연결 설정에서 클라이언트 이름을 직접 정했으면(`META_CLIENT_NAME_OVERRIDE`) 그 값을 따른다.
  2026-11-07 이전 빌드는 "Chromite AI agent"였다.
- 한 문맥에서는 한 번에 한 호출만 실행한다(문맥별 잠금). 바쁜 문맥이 있으면 풀에서 다른 문맥을 쓰거나 새로 연다. 문맥이 하나뿐이던 첫 구현에서는
  잠금에 걸린 에이전트 DDL(블로킹된 `CREATE INDEX`) 하나가 그 연결의 진단·`cancelQuery`까지 막았다(아래 "확인"). 쓰기는 열린 에이전트 트랜잭션이 있는
  문맥을 이어서 써야 하므로 그 문맥을 기다리고, 읽기는 그 문맥이 비어 있으면 그것을(커밋 전 변경이 보이게), 아니면 다른 문맥을 쓴다.
- 연결이 "단일 연결 사용"(`isForceUseSingleConnection`)이면 격리 문맥을 열 수 없어 기본 문맥을 쓴다.
- 에이전트의 열린 트랜잭션 안에서 실행하는 호출은(PostgreSQL) 먼저 `pg_stat_clear_snapshot()`을 부른다(`AgentSql.clearStatisticsSnapshot`, 2026-11-08).
  PostgreSQL은 트랜잭션에서 처음 읽은 `pg_stat_activity`·통계 뷰를 트랜잭션이 끝날 때까지 그대로 돌려주므로, 수동 커밋 연결에 쓰고 트랜잭션을 열어 둔 동안
  `getBlockingChain`·`listSessions` 같은 진단이 그 뒤에 생긴 세션과 대기를 보지 못했다(Activity Monitor 잠금 탭과 같은 원인,
  [swing-admin-tools.md](swing-admin-tools.md) "모니터링 연결의 커밋 방식"). 스냅숏을 비워도 트랜잭션의 다른 상태는 그대로다. 열린 트랜잭션이 없는 읽기는
  호출마다 새 읽기 전용 트랜잭션이라 해당하지 않는다. SQL Server DMV, MySQL·MariaDB의 `information_schema`·`performance_schema`, Oracle `V$` 뷰는
  트랜잭션 스냅숏을 따르지 않는다.
- `setCurrentDatabase`는 에이전트 문맥의 기본 DB/스키마만 바꾼다. 사용자의 화면은 바뀌지 않는다.
- PostgreSQL처럼 DB마다 인스턴스가 다른 드라이버는 `database` 인수로 그 DB의 인스턴스 문맥을 쓴다.

### 읽기 전용 보호

읽기 도구(진단 SQL, `runSelect`, `runReadOnlySql`, 읽기 전용 쿼리의 실제 계획)는 아래 세 겹으로 보호한다.

1. **문장 분류기** (`AgentSqlClassifier`): JSQLParser 결과(`SQLQuery.getType() == SELECT`, `isMutatingStatement() == false`, `INTO`·`FOR UPDATE` 없음)이거나,
   읽기 전용으로 알려진 명령(`SHOW`, `DESCRIBE`, `EXPLAIN`(ANALYZE 없이), `DBCC SHOW_STATISTICS|SQLPERF|INPUTBUFFER|OPENTRAN|USEROPTIONS|TRACESTATUS`,
   `EXEC sp_who|sp_who2|sp_lock|sp_helpindex|sp_helptext|sp_help|sp_helpdb|sp_spaceused`(인수 없이))만 읽기로 본다.
   **읽기라고 증명하지 못한 문장(해석 실패 포함)은 쓰기로 본다.** `runSelect`·`runReadOnlySql`은 이런 문장을 거부하고 `runSql`(WRITE)을 쓰라고 알려 준다.
2. **읽기 전용 트랜잭션**: 자동 커밋을 끄고 `Connection.setReadOnly(true)`, 그리고 DB별 문장
   (PostgreSQL·Oracle `SET TRANSACTION READ ONLY`, MySQL/MariaDB `START TRANSACTION READ ONLY`). SQL Server는 읽기 전용 트랜잭션이 없다.
3. **항상 롤백**: 실행이 끝나면(성공이든 실패든) 롤백하고 자동 커밋·읽기 전용 상태를 되돌린다. SQL Server의 DDL·DML은 트랜잭션 안이라 롤백된다.

롤백으로도 되돌릴 수 없는 명령(SQL Server `BACKUP`, `RECONFIGURE`, `xp_cmdshell`, `KILL` 등)은 1번 분류기가 쓰기로 본다.

"단일 연결" 연결(공유 세션)에서는 2·3번이 없어 1번 분류기만 보호하고, 롤백에 기대어 쓰는 호출은 거절한다(아래 "공유 세션에서 롤백에 기대는 호출", 2026-11-12).

## 도구 카탈로그

### 공통 규칙

- 모든 도구는 `com.seizia.chromite.ai.function` 확장점에 등록한다(`purpose="ALL"`: 채팅과 MCP 모두). 이름은 camelCase 함수 ID이고,
  엔진에는 `db_<ID>`(도구 상자 ID `db` + `_` + ID, `AIFunctionDescriptor.getFullId()`), MCP에는 `<ID>` 그대로 보인다.
- 연결이 필요한 도구는 `connection` 인수(데이터 소스 ID 또는 이름, 대소문자 무시)를 받는다. 채팅에서는 생략하면 채팅의 현재 연결을 쓴다.
- 매개변수 형식은 `string`, `integer`, `number`, `boolean`, `object`만 쓴다. 목록은 쉼표로 구분한 문자열이다(OpenAI가 `items` 없는 `array`를 거부한다).
- 결과는 항상 JSON 텍스트다. 표 형태 결과는 기본으로 행마다 객체(`{"열": 값}`)이고 `format="arrays"`면 `columns` + 배열 행이다.
- 결과 크기 한도(`AgentLimits`): 행 수 기본 100·최대 10,000(`limit`), 칸 하나 4,000자(`maxCellChars`), 결과 전체 1 MB. 넘으면 잘라 내고
  `truncated: {rows, cells, bytes}`와 `hasMore`, `nextOffset`을 돌려준다.
- 이진 값은 16진수 앞부분만, LOB는 4,000자까지 읽는다. 비밀번호·토큰·키는 결과에 넣지 않는다(연결 정보는 `getConnectionInfo` 참고).
- 모든 도구는 `DBRProgressMonitor`로 실행되고 취소된다. MCP의 `notifications/cancelled`, 채팅의 중지 단추가 모니터를 취소한다.
- 쓰기 도구는 연결의 "읽기 전용" 설정(`DBPDataSourceContainer.isConnectionReadOnly()`)이면 실행하지 않는다(`writes="database"`인 도구만. 파일로 내보내기는 허용).
- DB 종류 표기: MS = SQL Server, PG = PostgreSQL(같은 드라이버 계열 포함), MY = MySQL/MariaDB, OR = Oracle, G = 모델 계층으로 모든 연결.
  표에 없는 DB에서 부르면 `unsupported` 오류와 함께 대신 쓸 도구(대개 `runReadOnlySql`)를 알려 준다.
- 분류: R = READ, W = WRITE, D = DANGEROUS. "R→W"는 호출별로 바뀐다(문장이 읽기라고 증명되지 않으면 WRITE, `DROP`·`TRUNCATE`·WHERE 없는 `DELETE/UPDATE`면 DANGEROUS).
- 단계: 1 = 1단계 작업, 1b = 1b단계 작업(2026-11-08, 아래 "1b단계 도구"). "1 (OR 1b)"는 1단계에서 만들고 1b단계에서 그 DB를 더한 것이다.
  아직 하지 않은 것은 "미룸"으로 적고 아래 "1b단계 (미룬 것)"에 둔다.

### 연결 (`agent.connections`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `listConnections` | 연결 목록: 폴더, 드라이버, 연결 종류, 색, 운영 여부, 읽기 전용, 연결 상태 | R | `filter` | `connections[]` | G | 1 |
| `getConnectionInfo` | 연결 상세(호스트, 포트, DB, 사용자 이름, 종류, 드라이버, 네트워크 처리기 종류, 자동 커밋). 비밀번호·토큰·SSH 키는 없음 | R | `connection` | 객체 | G | 1 |
| `connect` | 연결한다 | R | `connection` | 상태 | G | 1 |
| `disconnect` | 연결을 끊는다(사용자 창의 열린 트랜잭션도 끊기므로 확인) | W (`writes="none"`) | `connection` | 상태 | G | 1 |
| `testConnection` | 연결해서 왕복 시간과 서버 버전을 잰다 | R | `connection` | `ok`, `ms`, `version` | G | 1 |
| `getCurrentDatabase` | 에이전트 문맥과 기본 문맥의 기본 DB/스키마 | R | `connection` | 객체 | G | 1 |
| `setCurrentDatabase` | 에이전트 문맥의 기본 DB/스키마를 바꾼다(사용자 화면은 그대로) | R | `connection`, `database`, `schema` | 객체 | G | 1 |
| `getServerInfo` | 제품, 버전, 에디션, 가동 시간, 호스트, 주요 사실(DB가 고를 시스템 뷰를 정할 근거) | R | `connection` | 객체 | MS PG MY OR, G(버전만) | 1 |
| `probePermissions` | 이 로그인으로 읽을 수 있는 시스템 뷰·권한·확장 기능. 어느 도구가 되는지/안 되는지와 이유 | R | `connection` | `checks[]`, `tools{}` | MS PG MY OR | 1 |

### 메타데이터 (`agent.metadata`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `listDatabases` | 카탈로그(DB) 목록 | R | `connection` | `databases[]` | G | 1 |
| `listSchemas` | 스키마 목록 | R | `connection`, `database` | `schemas[]` | G | 1 |
| `listTables` | 테이블·뷰(종류, 이름 패턴) | R | `connection`, `database`, `schema`, `pattern`, `types` | `tables[]` | G | 1 |
| `describeTable` | 열(형식, NULL, 기본값, 설명), 기본 키, 고유 키, 외래 키(나가는·들어오는), 인덱스, 트리거 이름 | R | `connection`, `table` | 객체 | G | 1 |
| `listIndexes` | 테이블의 인덱스와 열, 고유 여부 | R | `connection`, `table` | `indexes[]` | G | 1 |
| `listForeignKeys` | 나가는·들어오는 외래 키 | R | `connection`, `table` | `outgoing[]`, `incoming[]` | G | 1 |
| `listRoutines` | 프로시저·함수와 매개변수 | R | `connection`, `database`, `schema`, `pattern` | `routines[]` | G | 1 |
| `getRoutineSource` | 프로시저·함수·트리거·뷰 원문(**줄 번호 붙음**)과 매개변수 | R | `connection`, `name`, `type` | `source`(줄 번호), `lineCount`, `parameters[]` | MS PG MY OR | 1 |
| `listTriggers` | 테이블 또는 스키마의 트리거(시점, 이벤트, 사용 여부) | R | `connection`, `table` 또는 `schema` | `triggers[]` | MS PG MY OR | 1 |
| `listSequences` | 시퀀스(현재 값, 증가, 최대) | R | `connection`, `schema` | `sequences[]` | MS PG MY(MariaDB) OR | 1 |
| `listPrincipals` | 로그인·사용자·역할과 역할 소속 | R | `connection` | `principals[]` | MS PG MY OR | 1 |
| `listPermissions` | 개체 또는 주체의 권한(GRANT/DENY) | R | `connection`, `object` 또는 `principal` | `permissions[]` | MS PG MY OR | 1 |
| `getObjectDdl` | 개체 DDL(드라이버의 DDL 생성기) | R | `connection`, `object` | `ddl` | G | 1 |
| `getObjectDependencies` | 의존 관계 그래프(이 개체가 쓰는 것, 이 개체를 쓰는 것), 깊이 1~3 | R | `connection`, `object`, `depth` | `nodes[]`, `edges[]`, `notes[]` | MS PG MY(뷰) OR | 1 |
| `searchObjects` | 이름으로 개체 찾기(테이블, 뷰, 프로시저, 열 등) | R | `connection`, `pattern`, `types` | `objects[]` | G | 1 |

### 쿼리 (`agent.query`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `runSelect` | SELECT 실행, 행 한도와 페이지 | R | `connection`, `sql`, `limit`, `offset`, `format` | 표 | G | 1 |
| `runReadOnlySql` | 모델이 쓴 진단 SQL(시스템 뷰, `SHOW`, 읽기 전용 `DBCC`·시스템 프로시저). 결과 집합 여러 개와 서버 메시지 | R | `connection`, `sql`, `database`, `limit` | `results[]`, `messages[]` | G | 1 |
| `runSql` | 아무 문장 하나 실행(갱신 수 또는 결과). 연결의 커밋 방식 그대로: 수동 커밋 연결이면 트랜잭션을 열어 둔 채 `transactionOpen: true`를 알린다 | W (R→W/D) | `connection`, `sql` | `updateCount` 또는 표, `transactionOpen`. 실패는 아래 "배치의 부분 실패" | G | 1 |
| `runScript` | 스크립트(여러 문장, 드라이버의 구분자 규칙). 커밋 방식은 `runSql`과 같다 | W (W/D) | `connection`, `script`, `stopOnError` | 문장별 결과, `transactionOpen`. 실패한 문장이 있으면 오류(아래 "배치의 부분 실패") | G | 1 |
| `getTransactionStatus` | 에이전트 문맥의 열린 트랜잭션(DB에 직접 확인: 쿼리 창과 같은 방언 질의 `JDBCTransactionProbe`, [idle-transactions.md](idle-transactions.md) "트랜잭션 상태")과 이 연결의 쿼리 창 트랜잭션(`otherOpenTransactions`, DB에 확인할 수 없는 것은 `otherUnverifiedTransactions`) | R | `connection` | 객체 | MS PG MY OR, G | 1 |
| `commitTransaction` | 에이전트의 열린 트랜잭션 커밋. **자동 실행 설정과 상관없이 늘 확인** | W | `connection` | 결과 | G | 1 |
| `rollbackTransaction` | 에이전트의 열린 트랜잭션 롤백. 늘 확인. 운영 연결에서도 에이전트가 자기 일을 되돌릴 수 있어야 하므로 분류는 READ(막히지 않음)이고, 되돌릴 것이 있으면 트랜잭션 안전 규칙이 늘 묻는다 | R | `connection` | 결과 | G | 1 |
| `cancelQuery` | 에이전트가 실행 중인 문장 취소 | R | `connection` | 취소한 수 | G | 1 |
| `explainQuery` | 예상 실행 계획을 **구조화 JSON**으로(연산자, 비용, 예상 행, 경고, 누락 인덱스, 매개변수 컴파일 값) | R | `connection`, `sql`, `database` | `plan` | MS PG MY OR, G(계획기) | 1 |
| `getActualPlan` | 실제 실행 계획과 실행 통계(실제 행, 반복, 시간, 논리 읽기·버퍼, SET STATISTICS IO/TIME, EXPLAIN ANALYZE BUFFERS, OR: `GATHER_PLAN_STATISTICS` + `DISPLAY_CURSOR('ALLSTATS LAST')`) | R→W | `connection`, `sql`, `database` | `plan`, `runtime` | MS PG MY OR | 1 (OR 1b) |
| `comparePlans` | 두 쿼리(또는 같은 쿼리의 인덱스 전후)의 계획 비교: 비용, 연산자 바뀜(스캔→탐색), 행 추정 | R | `connection`, `sqlA`, `sqlB`, `actual` | `a`, `b`, `diff` | MS PG MY OR | 1 |
| `validateSql` | 구문 검사(해석 오류 위치), 문장 분류 | R | `connection`, `sql` | `valid`, `type`, `readOnly`, `error` | G | 1 |
| `formatSql` | SQL 서식 | R | `connection`, `sql` | `sql` | G | 1 |
| `getQueryHistory` | 쿼리 창 실행 기록(Swing 기록 파일) | R | `connection`, `contains`, `limit` | `entries[]` | G | 1 |

### 데이터 (`agent.data`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `sampleRows` | 테이블 앞부분 행 | R | `connection`, `table`, `limit`, `where`, `orderBy` | 표 | G | 1 |
| `countRows` | 정확한 행 수(조건 선택) | R | `connection`, `table`, `where` | `count` | G | 1 |
| `profileColumns` | 열 프로필: NULL 비율, 고유 값 수, 최소·최대, 상위 값 | R | `connection`, `table`, `columns`, `topN` | 열별 객체 | G | 1 |
| `compareQueryResults` | 두 쿼리 결과 비교(키 기준 또는 다중 집합) | R | `connection`, `sqlA`, `sqlB`, `connectionB`, `keyColumns` | `onlyInA`, `onlyInB`, `different`, 표본 | G | 1 |
| `insertRows` | 행 넣기(값은 드라이버의 값 처리기로 형식 변환) | W | `connection`, `table`, `rows`(JSON 배열 문자열) | `inserted` | G | 1 |
| `updateRows` | 키로 행 고치기 | W | `connection`, `table`, `key`, `values` | `updated` | G | 1 |
| `deleteRows` | 키로 행 지우기 | W | `connection`, `table`, `key` | `deleted` | G | 1 |

`updateRows`·`deleteRows`는 `key`가 고유 키(기본 키 또는 고유 제약) 열 전체를 덮지 않으면 거부한다. 한 번에 여러 행이 바뀌는 사고를 막기 위해서다.

### DDL (`agent.ddl`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `generateDdl` | 아래 변경의 DDL만 만든다(실행하지 않음) | R | `operation` + 해당 인수 | `sql` | MS PG MY OR | 1 |
| `createTable` | 테이블 만들기(열 정의 JSON, 기본 키) | W | `connection`, `table`, `columns`, `primaryKey` | `sql`, 결과 | MS PG MY OR | 1 |
| `alterTable` | 열 추가·삭제·이름 바꾸기·형식 바꾸기 | W | `connection`, `table`, `action`, `column`, `type`, `newName` | `sql`, 결과 | MS PG MY OR | 1 |
| `dropTable` | 테이블 지우기 | D | `connection`, `table` | `sql`, 결과 | MS PG MY OR | 1 |
| `createView` | 뷰 만들기·바꾸기 | W | `connection`, `view`, `select`, `replace` | `sql`, 결과 | MS PG MY OR | 1 |
| `dropView` | 뷰 지우기 | D | `connection`, `view` | `sql`, 결과 | MS PG MY OR | 1 |
| `createIndex` | 인덱스 만들기(고유, 포함 열 MS·PG) | W | `connection`, `table`, `index`, `columns`, `unique`, `include` | `sql`, 결과 | MS PG MY OR | 1 |
| `dropIndex` | 인덱스 지우기 | W | `connection`, `table`, `index` | `sql`, 결과 | MS PG MY OR | 1 |

DDL 도구는 실행 전 확인 요청에 만든 DDL을 그대로 보여 준다(`AIToolPreviewProvider`). 프로시저 다시 쓰기 같은 임의 DDL은 `runSql`·`runScript`로 적용하고
확인 요청에 문장 전체가 보인다.

### 전송 (`agent.transfer`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `exportData` | 쿼리 또는 테이블을 CSV·XLSX·JSON 파일로 | W (`writes="file"`) | `connection`, `sql` 또는 `table`, `format`, `file`, `maxRows` | 파일 경로, 행 수, 크기 | G | 1 |
| `importCsv` | CSV를 기존 테이블에 넣기(머리글 = 열 이름, 한 트랜잭션) | W | `connection`, `file`, `table`, `delimiter`, `encoding` | 넣은 행 수 | G | 1 |
| `copyTable` | 테이블을 다른 연결(또는 같은 연결)의 테이블로 복사. 대상이 없으면 만들 수 있음(같은 DB 종류일 때 형식 그대로) | W | `connection`, `table`, `targetConnection`, `targetTable`, `createTarget`, `maxRows` | 복사한 행 수 | G | 1 |

- 파일 경로는 절대 경로여야 하고, 기존 파일을 덮어쓰지 않는다(`overwrite=true`일 때만).
- 열 형식 매핑, 대량 적재, 진행 화면이 필요한 큰 전송은 Swing 데이터 전송 마법사(`swing-data-transfer.md`)를 쓴다. 도구는 작은~중간 크기(기본 최대 100,000행)용이다.

### 상태·관리 (`agent.health`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `listSessions` | 세션: 로그인, 호스트, 프로그램, 상태, 대기, 현재 문장 | R | `connection`, `activeOnly` | `sessions[]` | MS PG MY OR | 1 |
| `getBlockingChain` | **블로킹 사슬 트리**: 머리 블로커(세션, 로그인, 호스트, 프로그램, 트랜잭션 나이, 격리 수준, 열린 트랜잭션 수, 마지막 SQL), 대기자(대기 종류·시간·자원·SQL), 관련 잠금(개체·인덱스로 바꿈), 규칙 기반 **소견**(왜 막히는지 후보와 해결 제안) | R | `connection`, `database` | `heads[]`(트리), `locks[]`, `findings[]` | MS PG MY OR | 1 |
| `listLocks` | 잡은·기다리는 잠금, 자원을 개체·인덱스로, 세션·개체별 잠금 수(에스컬레이션 단서) | R | `connection`, `database`, `session` | `locks[]`, `summary[]` | MS PG MY OR | 1 |
| `getDeadlocks` | 최근 교착 상태를 구조화 JSON으로(MS: system_health 교착 그래프 XML 해석, MY: InnoDB 최신 교착 상태, PG: 횟수와 로그 안내, OR: 경고 로그의 ORA-00060 항목 + 그 추적 파일의 교착 상태 그래프) | R | `connection`, `limit` | `deadlocks[]` | MS MY PG OR | 1 (OR 1b) |
| `listOpenTransactions` | 오래 열린 트랜잭션과 idle-in-transaction 세션 | R | `connection`, `minSeconds` | `transactions[]` | MS PG MY OR | 1 |
| `getLongRunningQueries` | 지금 오래 실행 중인 요청 | R | `connection`, `minSeconds` | `queries[]` | MS PG MY OR | 1 |
| `killSession` | 세션 끝내기 | D | `connection`, `session` | 결과 | MS PG MY OR | 1 |
| `getWaitStats` | 대기 통계(누적, 무해한 대기 제외) | R | `connection`, `top` | `waits[]` | MS PG MY OR | 1 |
| `getTopQueries` | CPU·I/O·시간 상위 쿼리(MS: `dm_exec_query_stats`, PG: `pg_stat_statements`, MY: performance_schema 다이제스트, OR: `v$sql`) | R | `connection`, `orderBy`, `top` | `queries[]` | MS PG MY OR | 1 |
| `getDatabaseSizes` | DB 크기(데이터·로그) | R | `connection` | `databases[]` | MS PG MY OR | 1 |
| `getSpaceUsage` | 파일·로그·테이블스페이스 사용량과 여유 | R | `connection`, `database` | `files[]` | MS PG MY OR | 1 |
| `getBackupHistory` | 백업 기록과 DB별 마지막 백업 나이 | R | `connection` | `databases[]`, `backups[]` | MS (PG: 아카이버 상태) | 1 |
| `getJobHistory` | 작업 실패·최근 실행(MS: msdb, PG: pg_cron 있으면, MY: 이벤트, OR: 스케줄러) | R | `connection`, `failedOnly` | `jobs[]` | MS PG MY OR | 1 |
| `getErrorLog` | 서버 오류 로그 최근 항목(MS: `sp_readerrorlog`, PG: `pg_current_logfile`+`pg_read_file`, MY: `performance_schema.error_log`, OR: `v$diag_alert_ext`) | R | `connection`, `contains`, `limit` | `entries[]` | MS PG MY OR | 1 |
| `getServerConfig` | 기본값과 다른 설정과 상식 검사(예: MAXDOP, 비용 임계값, shared_buffers, work_mem) | R | `connection` | `settings[]`, `findings[]` | MS PG MY OR | 1 |
| `backupDatabase` | 백업(MS: T-SQL `BACKUP DATABASE`, `model.admin`의 `SQLServerBackupSettings`). 파일은 서버의 경로 | W | `connection`, `database`, `file`, `type`, `copyOnly` | 메시지 | MS | 1 (PG MY: `dumpDatabase`) |
| `restoreDatabase` | 복원(MS, `SQLServerRestoreSettings`) | D | `connection`, `database`, `file`, `replace` | 메시지 | MS | 1 (PG MY: `restoreDump`) |
| `dumpDatabase` | 로컬 클라이언트 도구(pg_dump, mysqldump)로 DB 전체 또는 일부(PG 스키마, MY 테이블)를 **이 컴퓨터의 새 파일**로 백업. 클라이언트는 연결의 로컬 클라이언트 설정. 있는 파일을 덮어쓰지 않음 | W (`writes="file"`) | `connection`, `database`, `file`, `format`(PG), `schemas`(PG), `tables`(MY), PG `noOwner`·`noPrivileges`·`inserts`·`clean`·`create`, MY `method`·`noData`·`routines`·`events`·`removeDefiner` | `file`, `bytes`, `durationMs`, `command`, `log[]` | PG MY | 1b |
| `restoreDump` | 이 컴퓨터의 백업 파일을 **있는 DB**로 복원(pg_restore, 일반 SQL 스크립트는 psql, mysql). PG 형식은 파일에서 알아냄 | D | `connection`, `database`, `file`, `format`(PG), PG `clean`·`noOwner`·`stopOnError`, MY `disableForeignKeyChecks` | `durationMs`, `command`, `log[]`, `note` | PG MY | 1b |
| `listAgentJobs` | SQL Server Agent 서비스 상태와 작업 목록(사용 여부, 실행 중·현재 단계, 마지막 결과·시각, 다음 실행, 범주, 소유자) | R | `connection`, `filter` | `agent`, `jobs[]` | MS | 1b |
| `getAgentJob` | 작업 하나의 정의: 단계(하위 시스템, DB, 명령, 성공·실패 동작, 재시도)와 일정(설명, 시작 시각, 다음 실행) | R | `connection`, `job` | 객체, `steps[]`, `schedules[]` | MS | 1b |
| `getAgentJobHistory` | 실행 기록(최신 순): 실행 결과(단계 0)와 그 실행의 단계별 상태·시간·재시도·서버 메시지. 작업 하나 또는 전체 | R | `connection`, `job`, `failedOnly`, `limit` | `history[]` | MS | 1b |
| `startAgentJob` | 작업 시작(`sp_start_job`, 단계 지정 가능). 비동기 | W | `connection`, `job`, `step` | `statement`, `messages[]` | MS | 1b |
| `stopAgentJob` | 실행 중인 작업 중지(`sp_stop_job`) | W | `connection`, `job` | `statement`, `messages[]` | MS | 1b |
| `setAgentJobEnabled` | 작업 사용·사용 안 함(`sp_update_job @enabled`) | W | `connection`, `job`, `enabled` | `previouslyEnabled`, `enabled` | MS | 1b |

### 성능 (`agent.performance`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `getTableStatistics` | 행 수, 테이블·인덱스 크기, 통계 마지막 갱신, 표본 행, 갱신 후 변경 수, 데드 튜플·자동 분석 시각 | R | `connection`, `table` 또는 `schema` | `tables[]`, `statistics[]` | MS PG MY OR | 1 |
| `getIndexUsage` | 인덱스 사용 통계(탐색·스캔·조회·갱신), 잠금 에스컬레이션 시도 | R | `connection`, `table` 또는 `schema` | `indexes[]` | MS PG MY OR | 1 |
| `getMissingIndexes` | 누락 인덱스 제안(MS DMV, PG: 순차 스캔이 많은 큰 테이블, OR: 캐시된 계획에서 1만 행 넘는 테이블의 전체 스캔과 그 필터 조건) | R | `connection`, `database` | `rows[]` | MS PG OR | 1 (OR 1b, MY 미룸) |
| `getUnusedIndexes` | 쓰이지 않는·중복 인덱스(OR: 인덱스 사용 추적 `DBA_INDEX_USAGE`, 캐시된 계획에 있는지) | R | `connection`, `schema` | `unused[]`, `duplicates[]` | MS PG MY OR | 1 (OR 1b) |
| `getIndexFragmentation` | 조각화·부풀음(MS `dm_db_index_physical_stats` LIMITED, PG pgstattuple 있으면 그것, 없으면 추정, MY `data_free`) | R | `connection`, `table` 또는 `schema` | `indexes[]` | MS PG MY | 1 |
| `getRoutineStats` | 루틴의 실행 통계와 **문장별 통계**(MS: 문장 오프셋 → 줄 번호, PG: `pg_stat_user_functions` + `pg_stat_statements`, MY: performance_schema 프로그램 요약, OR: `V$SQL`의 `PROGRAM_ID`·`PROGRAM_LINE#`) | R | `connection`, `name` | `routine`, `statements[]` | MS PG MY OR | 1 (OR 1b) |
| `getCachedPlans` | 루틴·쿼리 텍스트의 캐시된 계획을 문장별 구조화 JSON으로(MS) | R | `connection`, `name` 또는 `contains`, `top` | `plans[]` | MS | 1 (PG는 계획 캐시가 없어 `explainQuery`로 대신) |
| `getQueryStoreTopQueries` | Query Store의 최근 N시간 상위 쿼리(CPU·시간·논리 읽기·실행 수, 계획 수). 재시작·계획 캐시 제거에도 남는다 | R | `connection`, `database`, `orderBy`, `hours`, `top` | `queryStore`, `queries[]` | MS | 1b |
| `getQueryStoreRegressions` | 최근 창의 평균 시간이 앞 창보다 나빠진 쿼리, 두 창의 계획 ID와 `plan_changed` | R | `connection`, `database`, `recentMinutes`, `historyMinutes`, `minExecutions`, `top` | `queryStore`, `regressions[]` | MS | 1b |
| `getQueryStoreForcedPlans` | 강제된 계획(수동·자동 조정), 강제 실패 수와 마지막 실패 이유. 읽기만 | R | `connection`, `database` | `queryStore`, `forcedPlans[]` | MS | 1b |
| `whatIfIndex` | 가상 인덱스(PG hypopg)를 만든 전후의 예상 계획과 비교. hypopg가 그 DB에 없으면 `available: false`와 이유 | R | `connection`, `database`, `sql`, `index` | `before`, `after`, `diff`, `indexUsed` | PG | 1b |

### Query Library (`agent.library`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `listLibraryQueries` | 라이브러리 쿼리 목록(루트, 폴더, 이름, 설명, 대상, 매개변수, 기준값 유무) | R | `folder`, `contains` | `queries[]` | — | 1 |
| `getLibraryQuery` | 쿼리 하나: SQL, 매개변수 정의, 기준값 | R | `id` 또는 `path` | 객체 | — | 1 |
| `runLibraryQuery` | 매개변수를 묶어 실행하고 기준값으로 판정 | R→W | `id`, `connection`, `parameters`(JSON) | 표, `evaluation` | G | 1 |
| `runMonitoringChecks` | 폴더(또는 이름 포함)의 쿼리를 모두 실행해 기준값으로 판정, 요약 | R | `folder`, `connection` | `checks[]`, `summary` | G | 1 |

### 편집기 연결 (`agent.editor`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `openInEditor` | SQL 텍스트를 새 쿼리 창에 연다(실행하지 않음). 화면 쪽 `AIEditorBridge`가 없으면 오류 | R | `connection`, `sql`, `title` | 결과 | — | 1 (Swing 구현은 2단계) |
| `listQueryWindows` | 열린 쿼리 창 목록: ID(`w1`…), 제목(파일 이름), 연결, DB/스키마, 저장 파일, 저장 안 됨, 활성 창, 실행 중, 길이, 커서·선택 | R | — | `windows[]` | — | 2026-11-08 |
| `getQueryWindowText` | 쿼리 창(기본: 활성 창)의 글: 전체, 선택, 커서의 문장(연결 방언의 파서, 연결이 없으면 전체)과 위치 | R | `window`, `part`(all/selection/statement), `maxChars`(기본 100,000) | `text`, `start`, `end` | — | 2026-11-08 |
| `editQueryWindow` | 쿼리 창의 글 고치기: 커서에 넣기, 선택 바꾸기, 전체 바꾸기. 실행·저장하지 않고 Ctrl+Z 한 번으로 되돌림. 확인 미리 보기는 바뀌는 글의 줄 차이 | W (`writes="none"`) | `window`, `mode`(insert/replaceSelection/replaceAll), `text` | 바뀐 범위 | — | 2026-11-08 |

### 에이전트 (`agent.meta`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `getToolPolicy` | 이 연결에서 각 도구의 실제 권한(AUTO/CONFIRM/BLOCKED)과 이유. 모델이 막힌 도구를 미리 알고 계획을 세우게 한다 | R | `connection` | `tools[]` | — | 1 |

기존 3개 함수(`listSchemaNames`, `listTableNames`, `getTableDetails`)는 그대로 두고 READ로 분류된다. 이들은 채팅의 데이터베이스 문맥을 읽으므로 채팅에서만 쓰고 MCP 목록에서는 뺀다(`AIToolCatalog.getTools`).

## 권한 정책

### 분류

- 정적 분류: `plugin.xml`의 `access="READ|WRITE|DANGEROUS"`. 없으면 `type`에서 정한다(INFORMATION → READ, ACTION → WRITE).
- 호출별 분류: 도구가 `AIToolAccessClassifier`를 구현하면 인수를 보고 더 높은 분류를 돌려줄 수 있다(정적 분류보다 낮아지지 않는다).
  예: `runSql`의 `SELECT`는 READ… 가 아니라 정적 WRITE 그대로다. `runSql`의 `DROP TABLE`은 DANGEROUS, `getActualPlan`의 `UPDATE`는 WRITE.
- `writes`: `database`(기본), `file`, `none`. 연결의 읽기 전용 설정은 `database`만 막는다.

### 설정과 판정 순서

설정 파일: 작업 공간 `.metadata/chromite-ai/tool-policy.json`.

```json
{
  "defaults":    { "runSql": "CONFIRM", "@WRITE": "CONFIRM" },
  "connections": { "postgres-jdbc-…": { "insertRows": "AUTO", "@READ": "AUTO" } }
}
```

키는 도구 ID 또는 분류(`@READ`, `@WRITE`, `@DANGEROUS`), 값은 `AUTO`(자동 실행), `CONFIRM`(실행 전 확인), `BLOCKED`(막음).
`AIToolPolicy.resolve(연결, 도구, 실제 분류)`는 처음 맞는 규칙을 쓴다.

1. 연결이 읽기 전용이고 `writes="database"`인 WRITE/DANGEROUS 도구 → 실행 안 함(`READ_ONLY`). 설정으로 바꿀 수 없다.
2. 연결별 도구 설정 `connections[c][도구]`.
3. 연결별 분류 설정 `connections[c]["@분류"]`. 단, DANGEROUS 도구를 AUTO로 만드는 것은 2번(도구별 설정)만 할 수 있다.
4. 연결이 **운영**이면 WRITE/DANGEROUS → `BLOCKED`(이유 "production connection"). 운영 연결에서 쓰기를 허용하려면 2·3번처럼 그 연결에 직접 정해야 한다.
5. 전역 도구 설정 `defaults[도구]`(DANGEROUS를 AUTO로 둘 수 없다. AUTO면 CONFIRM으로 본다).
6. 전역 분류 설정 `defaults["@분류"]`(같은 제한).
7. 기본값: READ → AUTO, WRITE → CONFIRM, DANGEROUS → CONFIRM.

연결이 없는 도구(예: `listConnections`, `formatSql`)는 5~7번만 쓴다.

**늘 확인하는 도구(2026-11-12, 앱 변경 #71 g):** `commitTransaction`과 `rollbackTransaction`은 끝낼 에이전트 트랜잭션이 있으면 정책과 상관없이 트랜잭션 안전 규칙이
Commit / Roll back / Cancel을 묻는다(`AIToolTransactionGuard`). 그래서 `plugin.xml`에 `alwaysConfirmed="true"`를 적고(`AIFunctionDescriptor.isAlwaysConfirmed`),
Options의 권한 정책 표는 막히지 않는 한 실제 권한을 "늘 확인"(Always asks)으로 보인다. 전에는 `rollbackTransaction`이 "읽기 / 자동 실행", 쓰기를 자동으로 둔
`commitTransaction`이 "자동 실행"으로 보였다. 분류와 동작은 그대로다(`rollbackTransaction`은 일부러 READ). `getToolPolicy` 결과에도 `alwaysConfirmed: true`가 붙는다.
다른 가드 도구(`disconnect`, `setCurrentDatabase`, `killSession`, DDL·`runSql`의 암묵적 커밋)는 열린 트랜잭션이 있을 때만 물어서 표시하지 않는다.

### 운영 연결

Chromite는 연결 종류(`DBPConnectionConfiguration.getConnectionType()`)로 운영을 표시한다. 기본 종류 Production(`DBPConnectionType.PROD`, ID `prod`)은
"SQL 실행 확인"(`confirmExecute`)이 켜져 있고 자동 커밋이 꺼져 있다. 연결 색(`getConnectionColor`)은 연결 종류 색을 덮어쓰는 표시일 뿐이라 기준으로 쓰지 않는다
(Swing `ConnectionColors.getColor`도 색이 없으면 종류 색을 쓴다).

`AIToolPolicy.isProduction(연결)` = 연결 종류 ID가 `prod`이거나, 사용자 정의 종류의 `confirmExecute`가 켜져 있다.
사용자가 만든 운영용 종류도 대개 "SQL 실행 확인"을 켜므로 함께 잡는다.

### 확인

- CONFIRM이면 실행기가 `AIAgentServices.getConfirmationHandler()`의 `confirm(monitor, AIToolConfirmationRequest)`를 부른다(답: `AIToolConfirmationAnswer`). 이 호출은 작업 스레드에서
  결과가 나올 때까지 막는다(화면 쪽이 EDT로 넘겨 대화상자를 띄우고 기다린다).
- 요청에는 클라이언트(CHAT/MCP), 도구 ID·이름, 실제 분류, 연결, 비밀을 지운 인수, **미리 보기**(실행할 SQL·DDL·명령줄 전체, 또는 쿼리 창에서 바뀌는 글)와
  그 종류(`AIToolPreviewProvider.Kind`: `RUN` / `TEXT_CHANGE`, 2026-11-08), 이유가 있다. 확인 대화상자는 종류로 미리 보기 제목("실행할 내용" / "바뀌는 글")과
  첫 줄을 고른다.
- 처리기가 없으면(화면 없음, 헤드리스) **거부**한다(`DENIED`, "no confirmation UI"). MCP의 기본값도 같다: 실행 중인 앱의 대화상자, 없으면 거부.
- 확인 대화상자의 "이 연결에서 항상 허용"은 2단계가 `AIToolPolicy.setPermission(연결 ID, 도구 ID, AUTO)`로 구현한다.

### 트랜잭션 안전 (사용자 규칙, 2026-11-07)

어떤 기능도 열린 트랜잭션을 조용히 커밋·롤백하지 않는다. 트랜잭션 방식 바꾸기, 연결 끊기·세션 닫기, 연결·DB 바꾸기 전에 커밋 안 된 트랜잭션을 찾고
사용자에게 **Commit / Roll back / Cancel**을 묻는다. 에이전트 도구에는 이렇게 적용한다.

- **실행기가 막는다**: 도구가 `AIToolTransactionGuard`를 구현하면 실행기가 실행 전에 `findPendingTransactions`를 부른다. 하나라도 있으면
  정책이 AUTO여도 확인 처리기에 묻는다(`AIToolConfirmationRequest.pendingTransactions`). 답이 COMMIT/ROLLBACK이면 `resolvePendingTransactions`로
  처리한 뒤 실행하고, DENY(또는 이 질문에 대한 ALLOW)는 취소다. 처리기가 없으면 거부한다. 정책의 BLOCKED는 그보다 먼저 막는다.
- **답을 결과에 남긴다**(2026-11-07): 도구는 Commit이든 Roll back이든 실행되므로, 실행기가 결과 JSON에
  `transactionsBeforeCall`(`action` COMMITTED/ROLLED_BACK, `count`, `note`)을 덧붙인다. 결과가 JSON 객체가 아니면 `{"result": …}`로 감싼다.
  오류·취소 결과에도 붙인다. 이것이 없으면 롤백으로 답한 `commitTransaction`이 커밋과 같은 상태 결과("agentTransactionOpen": false)를 돌려주어
  모델은 커밋으로 알고, 채팅 카드는 "완료"만 보였다. 채팅 카드는 상태 앞에 "열린 트랜잭션 n개 롤백 → 완료"처럼 보인다(`AiToolCard`).
- **이 규칙이 걸리는 도구**:

  | 도구 | 찾는 트랜잭션 | Commit 가능 |
  |---|---|---|
  | `disconnect` | 이 연결의 모든 실행 문맥(쿼리 창은 상태 줄과 같은 규칙 `QMTransactionState.isOpen()`, 에이전트 문맥은 아래 표시) | 예 |
  | `setCurrentDatabase` | 이 연결의 에이전트 문맥 | 예 |
  | `commitTransaction`, `rollbackTransaction` | 에이전트 문맥(답이 곧 커밋·롤백 결정) | 예 |
  | `runSql`, `runScript`, DDL 도구 (MySQL/MariaDB·Oracle) | DDL이 열린 에이전트 트랜잭션을 암묵적으로 커밋하므로 | 예 |
  | `killSession` | 대상 서버 세션의 열린 트랜잭션(끝내면 서버가 롤백) | 아니요(Roll back / Cancel) |

- **쓰기 도구의 커밋**: 에이전트 문맥은 연결의 기본 커밋 방식(`DBPDataSourceContainer.isDefaultAutoCommit()`, 연결 종류)을 그대로 쓴다.
  - 자동 커밋 연결: 문장마다 커밋된다. 여러 행을 바꾸는 도구(`insertRows`, `importCsv`, `copyTable` 등)는 한 호출을 한 트랜잭션으로 묶어 끝에 커밋한다
    (사용자가 정한 자동 커밋과 같은 결과이고, 중간 실패 시 반만 들어가는 일을 막는다).
  - 수동 커밋 연결(운영 종류 기본값): DML·DDL을 실행하고 **트랜잭션을 열어 둔다**. 결과에 `transactionOpen: true`와 "commitTransaction 또는
    rollbackTransaction을 부르라"는 안내가 들어간다. 커밋은 모델이 `commitTransaction`을 부르고 사용자가 확인했을 때만 한다.
- **읽기 도구**: 에이전트 문맥에 열린 쓰기 트랜잭션이 없을 때만 읽기 전용 트랜잭션을 열고 끝에 롤백한다(쓰기가 없어 잃는 것이 없다).
  열린 트랜잭션이 있으면 그 안에서 읽고(바뀐 내용이 보인다) 롤백하지 않는다. 이때 읽기 보호는 분류기만 한다.
- 에이전트 문맥의 열린 트랜잭션은 쓰기 뒤에 DB에 직접 확인해(`getTransactionStatus`와 같은 쿼리) 기록한다.
  2026-11-08부터 쿼리 창과 같은 방언 질의(`JDBCTransactionProbe`, `AgentSql.isTransactionOpen`)다. 무언가를 쥐었거나(변경·잠금·스냅숏·명시적 시작·중단)
  확인할 수 없으면 열린 것이다. 자동 커밋 연결에서도 묻는다(`runSql`로 `BEGIN TRAN`을 연 경우). 예전 질의는 SQL Server `@@TRANCOUNT > 0`(수동 커밋에서는 SELECT도 1),
  MySQL `INNODB_TRX` 행 수(PROCESS 권한이 없으면 오류라 늘 열림), PostgreSQL `txid_current_if_assigned()`(테이블 잠금·중단을 모름), Oracle `SELECT … FROM dual`
  (직렬화 세션에서 트랜잭션을 시작함)이었고, 수동 커밋 문맥에서만 물었다.
- 커밋·롤백(`AgentSql.endTransaction`): 자동 커밋 문맥의 명시적 트랜잭션은 `COMMIT`/`ROLLBACK` 문장으로 끝낸다(드라이버가 `commit()`을 거절). 커밋은 DB가 아직
  트랜잭션이 있다고 답하는 동안 되풀이한다(SQL Server 중첩 `BEGIN TRAN`). 쿼리 창의 트랜잭션(`disconnect` 답)은 공용 경로 `IdleTransactionMonitor.commitOrRollback`.

### 감사 기록

모든 도구 호출(거부·막힘 포함)을 작업 공간 `.metadata/chromite-ai/audit.jsonl`에 JSON 한 줄씩 덧붙인다.

| 필드 | 뜻 |
|---|---|
| `time` | ISO-8601 |
| `client` | `CHAT` / `MCP` |
| `tool`, `access` | 도구 ID, 실제 분류 |
| `connectionId`, `connectionName` | 연결(없으면 생략) |
| `arguments` | 인수. 이름에 password·secret·token·key·credential이 들어가면 `***`, 긴 문자열은 2,000자에서 자름 |
| `permission`, `reason` | 정책 판정과 이유 |
| `outcome` | `SUCCESS`, `ERROR`, `DENIED`, `BLOCKED`, `READ_ONLY`, `CANCELED`, `INVALID` |
| `durationMs`, `error` | 실행 시간, 오류 문구(부분 실패면 도구 결과의 `error`와 같은 요약, 아래 "배치의 부분 실패") |
| `transactionsBeforeCall` | 트랜잭션 질문에 Commit / Roll back으로 답했을 때만: `{"action": "COMMITTED"\|"ROLLED_BACK", "count": n}`. 그런 호출의 정책 판정은 대개 AUTO라 이것이 사용자의 결정을 남긴다. Cancel은 `outcome` DENIED와 `error`로 남는다 |

파일이 10 MB를 넘으면 `audit.1.jsonl`로 바꾸고 새로 시작한다(하나만 보관).

## 로컬 MCP 서버

| 항목 | 내용 |
|---|---|
| 전송 | MCP Streamable HTTP. `POST /mcp`에 JSON-RPC 2.0 요청, 응답은 `application/json` 한 건(서버가 먼저 보내는 스트림이 없어 SSE를 쓰지 않는다). `GET /mcp`는 405 |
| 프로토콜 버전 | 구현 기준 `2025-06-18`. 클라이언트가 `2025-11-25`, `2025-06-18`, `2025-03-26` 중 하나를 요청하면 그 버전으로 답하고, 아니면 `2025-06-18`. 쓰는 기능(tools, prompts, ping, 취소 알림)은 세 버전에서 같다 |
| 메서드 | `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`, `prompts/list`, `prompts/get`, `notifications/cancelled` |
| 세션 | `initialize` 응답에 `Mcp-Session-Id`(무작위). 이후 요청에 이 머리글이 있으면 확인하고, 모르는 ID면 404 |
| 주소 | `127.0.0.1`에만 묶는다. 원격 주소가 루프백이 아니면 403 |
| 포트 | 설정 `chromite.ai.mcp.port`, 기본 47821 |
| 켜기 | 설정 `chromite.ai.mcp.enabled`, 기본 꺼짐. 앱 시작 때(`com.seizia.chromite.pluginService` 확장 `AIMcpPluginService`) 켜져 있으면 시작하고, `AIMcpSettings.setEnabled/setPort`가 시작·중지·재시작한다 |
| 설정 저장 | 번들 `com.seizia.chromite.model.ai.tools`의 설정 저장소: `<작업 공간>/.metadata/.plugins/org.eclipse.core.runtime/.settings/com.seizia.chromite.model.ai.tools.prefs` |
| 인증 | `Authorization: Bearer <토큰>`. 토큰은 32바이트 무작위(base64url), 작업 공간 `.metadata/chromite-ai/mcp-token`. 설정 화면에 보이고 다시 만들 수 있다(2단계). 비교는 상수 시간 |
| DNS rebinding 방어 | `Origin` 머리글이 있으면 `http://127.0.0.1:<포트>`·`http://localhost:<포트>`만 허용, `Host`도 이 둘만 허용 |
| 요청 크기 | 본문 최대 4 MB |
| 도구 | `tools/list`는 카탈로그 전체(`inputSchema`, `annotations.readOnlyHint` = READ, `destructiveHint` = DANGEROUS, `title`). `tools/call`은 실행기(`AIToolClient.MCP`)를 거쳐 정책·확인·감사가 채팅과 같다. 결과는 `content[0].text`(JSON 텍스트)와 `structuredContent`(같은 JSON 객체), 실패는 `isError: true` |
| 프롬프트 | 아래 플레이북 6개를 MCP 프롬프트로 낸다(`prompts/get`의 인수: `connection`, 시나리오별 `sql`·`object`·`folder`) |

### Claude Code 연결

```bash
claude mcp add --transport http chromite http://127.0.0.1:47821/mcp --header "Authorization: Bearer <CHROMITE_MCP_TOKEN>"
```

또는 프로젝트의 `.mcp.json`:

```json
{
  "mcpServers": {
    "chromite": {
      "type": "http",
      "url": "http://127.0.0.1:47821/mcp",
      "headers": { "Authorization": "Bearer ${CHROMITE_MCP_TOKEN}" }
    }
  }
}
```

토큰은 Chromite 설정 화면(2단계) 또는 `<작업 공간>/.metadata/chromite-ai/mcp-token` 파일에서 복사한다. 토큰을 저장소에 넣지 않도록
`.mcp.json`에는 환경 변수(`${CHROMITE_MCP_TOKEN}`)를 쓴다.

## 보안

- **비밀 없음**: 도구는 비밀번호, 인증 속성, SSH 키·암호, 프록시 비밀번호를 돌려주지 않는다. `getConnectionInfo`는 인증 속성 가운데 사용자 이름만 낸다.
  감사 기록도 비밀 이름의 인수를 지운다.
- **로컬만**: MCP 서버는 루프백에만 묶고, 토큰과 Origin/Host 검사를 함께 한다. 꺼진 것이 기본이다.
- **쓰기 확인 기본**: WRITE·DANGEROUS는 기본 확인, 운영 연결은 기본 막힘, 읽기 전용 연결은 늘 막힘. 화면이 없으면 확인은 거부다.
- **읽기 보호**: 읽기 도구는 분류기 + 읽기 전용 트랜잭션 + 롤백으로 실행한다(위 "읽기 전용 보호").
- **SQL 주입**: 진단 SQL은 매개변수를 `?`로 묶는다. 식별자는 드라이버 방언으로 따옴표를 붙인다(`DBUtils.getQuotedIdentifier`). `where`·`orderBy` 인수가
  들어간 문장은 전체를 분류기로 다시 검사한다.
- **크기 제한**: 결과 크기를 잘라 모델 문맥과 메모리를 보호한다.
- **프롬프트 주입**: DB의 값(예: 테이블 내용, 오류 로그)은 데이터다. 도구 결과에 섞인 지시문이 쓰기 도구를 부르더라도 정책·확인을 거친다.
  확인 대화상자는 실제로 실행할 SQL을 보여 준다.

## 엔진

| 엔진 | 상태 | 도구 사용 |
|---|---|---|
| OpenAI (`openai`) | 기존. Responses API(`OpenAiUtils.createOpenAiRequest`)가 `AIFunctionDescriptor` 목록을 `function` 도구로 보낸다. 이 작업의 도구도 같은 경로로 간다. `temperature`는 명시적으로 정했을 때만 보낸다(`OpenAIProperties.getRequestTemperature`, 2026-11-08, Anthropic과 같은 원인) | 예 |
| Ollama (`ollama`) | 이미 `plugin.xml`에 등록되어 있다(브리프의 "등록 안 됨"은 이전 상태). `/api/chat`의 `tools` | 예(응답의 첫 도구 호출) |
| vLLM (`vllm`, 2026-11-11) | OpenAI 호환 Chat Completions(`POST <기본 URL>/chat/completions`, 스트리밍), 엔진 `ChatCompletionsEngine` + 설정 `VllmProperties`. 아래 "Chat Completions 엔진" | 예(`tools`/`tool_calls`, 서버가 도구 호출을 켜고 떠 있어야 함) |
| Google Gemini (`gemini`, 2026-11-11) | Gemini API의 OpenAI 호환 엔드포인트, 같은 엔진 + `GeminiProperties`. 아래 "Chat Completions 엔진" | 예 |
| Anthropic (`anthropic`, 새) | Messages API `POST /v1/messages`, `anthropic-version: 2023-06-01`, `x-api-key`. 모델: `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`(목록 API `GET /v1/models`가 되면 그 목록). 도구: `tools[{name, description, input_schema}]`, 응답 `tool_use` → `AIFunctionCall`(메타데이터 `tool_use_id`), 결과는 `tool_result` 블록. `temperature`는 명시적으로 정했을 때만 보낸다(`AnthropicProperties.getRequestTemperature`, 아래 "실제 API 확인") | 예 |

API 키는 설정 파일(`ai-configuration.json`)에 쓰지 않고(`@SecureProperty`) `AIUtils.setSecretValue`가 OS 보안 저장소에 둔다(아래 "API 키 저장").
환경 변수 `ANTHROPIC_API_KEY`도 쓸 수 있다.

### API 키 저장 (2026-11-07)

0.1.0까지는 문서와 설정 화면이 "보안 저장소"라고 적었지만 키는 평문으로 저장되었다. `AIUtils.setSecretValue`가 작업 공간의 전역 비밀 컨트롤러
(`DBSSecretController.getGlobalSecretControllerOrNull` → `LocalWorkspaceSession.getSecretController` → `LocalSecretController`, Javadoc "Doesn't encrypt
values")에 썼기 때문이다. 결과는 `<작업 공간>/.metadata/.plugins/org.eclipse.core.runtime/.settings/com.seizia.chromite.ui.swing.prefs`의
`//secrets/anthropic.apiKey_anthropic=<키>` 같은 줄이다. SWT 시절(`263b8ddb45`)에도 `LocalWorkspaceSession`이 같은 컨트롤러를 돌려주었으므로 Swing 전환에서 생긴
차이가 아니라 upstream 구조이고, Chromite의 문서·화면 문구가 이를 보안 저장소라고 잘못 적었다.

| 항목 | 내용 |
|---|---|
| 대상 | `AIUtils.getSecretValueOrDefault`/`setSecretValue`/`deleteSecretValue`를 쓰는 AI 비밀 전부: Anthropic·OpenAI·Google Gemini·vLLM API 키(`gemini.apiKey`, `vllm.apiKey`, 2026-11-11), OpenAI 사용자 헤더, Copilot 토큰(DataDam 엔진도 같은 경로) |
| 저장소 | Eclipse secure storage + OS 제공자(`DBSecurityUtils.findNativeOSProvider`): Windows는 `org.eclipse.equinox.security.win32`(마스터 비밀번호를 DPAPI로 보호, 값은 그 비밀번호로 암호화), macOS는 `model.security`의 키체인 제공자. 노드 `chromite`, 키 이름은 예전과 같다(`anthropic.apiKey_anthropic`). 파일은 기본 위치(`%USERPROFILE%\.eclipse\org.eclipse.equinox.security\secure_storage`, `-eclipse.keyring`으로 바꿀 수 있음)라 작업 공간이 아니라 OS 사용자 단위다. Swing 제품에 두 제공자 번들이 이미 있다(`com.seizia.chromite.swing.feature`) |
| 쓰기 | 값을 쓰고 `isPrivateSecretEncrypted`로 암호화를 확인한 뒤 flush한다. 암호화되지 않았으면 지우고 예외. 이어서 같은 키의 평문 사본이 있으면 지운다 |
| 이전 | 읽을 때 보안 저장소에 값이 없고 평문 사본이 있으면 보안 저장소에 쓰고(확인·flush) 평문 줄을 지운 뒤 설정 파일을 저장한다. 로그에는 키 이름만 남긴다(`AI secret '…' was moved from the workspace settings to the secure storage`). 보안 저장소에 이미 값이 있으면(다른 작업 공간이 먼저 옮김) 이 작업 공간의 평문 사본은 쓰이지 않으므로 지운다 |
| 실패 | OS 보안 저장소가 없거나(헤드리스, 제공자 없음) 열 수 없으면 `DBSecurityException`. 평문으로 대신 저장하지 않는다. 설정 화면은 OK에서 "보안 저장소에 키를 저장할 수 없습니다. 키를 대신 설정 파일에 저장하지는 않습니다"(`ai_options_save_failed_secure_storage`)를 보이고, `AIAgentProviders.save`가 비밀을 먼저 쓰므로 그 제공자의 설정 파일도 저장하지 않는다. 읽기 실패는 "제공자 미설정"으로 보인다 |
| 제공자 예외 | Windows 제공자는 DPAPI 복호화 실패를 JNA `Win32Exception`(unchecked)으로 던진다. `SecurePreferencesSecretController`가 이를 `DBSecurityException`으로 바꾼다. 바꾸기 전에는 AI 창의 설정 확인(`AiAssistantPanel.updateSetupNotice`)을 거쳐 앱 시작이 멈췄다(아래 시험 4) |

작업 공간 전역 비밀 컨트롤러 자체는 바꾸지 않았다. 같은 컨트롤러를 전역 네트워크 프로필 비밀(`GlobalNetworkProfileManager`), 전역 프록시 사용자·비밀번호
(`GlobalProxyAuthenticator`), Maven 저장소 인증(`MavenRegistry`)도 쓰고 이들도 같은 설정 파일에 평문으로 남는다. 이들은 시작 중·백그라운드 저장 경로에서
읽고 쓰며 실패를 로그로만 남기므로, 저장소를 바꾸면 OS 저장소 실패가 비밀번호를 조용히 잃는 일로 바뀐다. 각 기능의 실패 처리와 이전을 따로 정해야 해서
이번 범위에서 뺐다(아래 "위험과 남은 일"). 연결 비밀번호는 이 컨트롤러를 쓰지 않는다(`isUseSecretStorage()`가 false, `credentials-config.json`).
**2026-11-07 후속:** 전역 비밀 컨트롤러와 연결 비밀번호도 OS 보안 저장소로 옮겼다([credential-storage.md](credential-storage.md)). AI 키는 이 절의 위치에 그대로 있다.

확인(2026-11-07, Windows 11): 빌드 `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS. 빌드한 제품과
`D:\Chromite-Test`의 작업 공간 사본을 `D:\Tools\_scratch\ai-key-secure-storage`에 두고 `-eclipse.keyring <사본 폴더>\keyringN\secure_storage`로 실행했다
(사용자 홈의 보안 저장소는 만들어지지 않음을 확인). 키는 가짜 문자열, 제공자는 로컬 가짜 Messages API(받은 `x-api-key`가 어느 가짜 키인지만 기록). 회귀 테스트는
요청되지 않아 실행하지 않았다(바뀐 동작에 맞춰 기존 테스트 4개의 목만 고침).

| 시험 | 결과 |
|---|---|
| 1. 새로 저장 | Providers에 키 입력 → Test "Connected", OK. 작업 공간·데이터·앱 폴더 전체에서 키 문자열 0건, 설정 파일 `secrets` 줄 0, keyring에 `/chromite/anthropic.apiKey_anthropic`(암호문)과 Windows 제공자 항목. 채팅 요청에 같은 키 |
| 2. 다시 시작 | 키를 입력하지 않고 Test "Connected", 채팅 동작. 가짜 서버가 받은 키 = 저장한 키 |
| 3. 이전 | 빈 keyring + 설정 파일에 0.1.0 형식 평문 줄 → 시작 후 Test가 그 키로 동작, 평문 줄 사라짐, keyring에 암호문. 로그에 키 이름만 |
| 3b. 남은 평문 | keyring에 키가 있는 상태에서 다른 가짜 키의 평문 줄을 넣음 → keyring의 키를 쓰고 평문 줄은 지워짐 |
| 4. 저장소 실패 | keyring의 DPAPI 마스터 비밀번호를 망가뜨려 시작 → 앱 정상 시작(고치기 전에는 `Win32Exception`으로 시작이 멈춤), 키 입력 후 OK → "Settings of Anthropic Claude were not saved: the secure storage of this computer could not store the key. The key is never saved in a settings file instead." 설정 파일·작업 공간에 키 0건 |

### Chat Completions 엔진 (vLLM, Google Gemini, 2026-11-11)

앱 변경 #34(사용자: "llm을 연결하는 옵션에 vllm도 추가한다"), #35("코파일럿과 gemini도 추가한다" 중 Gemini). #36 Copilot은 사용자 결정을 기다려 이번 범위가 아니다.

| 항목 | 내용 |
|---|---|
| 왜 따로 | OpenAI 엔진은 Responses API(`/v1/responses`, `OpenAiAPIStreamConsumer`)를 쓴다. vLLM은 Chat Completions가 기본이고 Responses 지원은 버전마다 다르다. Gemini의 OpenAI 호환 엔드포인트도 Chat Completions다. 그래서 OpenAI 엔진을 고치지 않고 Chat Completions 엔진 하나를 새로 두고 두 제공자가 같이 쓴다. 기존 `OpenAIClientChat`은 도구 호출이 없고 스트리밍이 Responses 요청이라 쓰지 않았다 |
| 구조 | `model.ai` 패키지 `com.seizia.chromite.model.ai.engine.chatcompletions`: `ChatCompletionsEngine`(두 `completionEngine` 확장 `vllm`·`gemini`가 같은 클래스), `ChatCompletionsClient`(HTTP, 요청 조립, SSE 해석), `ChatCompletionsProperties`(기본 URL, 키, 모델; 키는 `@SecureProperty`), 하위 클래스 `VllmProperties`·`GeminiProperties`(기본 URL, 키 필요 여부, 오류 문구 이름, 도구 호출 안내, 모델 ID → 요청 이름). 채팅 쪽은 `AIAgentProviders.PROVIDERS`에 `gemini`·`vllm`을 더했을 뿐이라 모델 선택(제공자 제목)과 Providers 탭이 목록을 따라간다. 순서: Anthropic Claude, OpenAI, Google Gemini, Ollama, vLLM(호스팅 서비스 다음 직접 띄우는 서버) |
| 요청 | `POST chat/completions`: `model`, `messages`(system 하나를 맨 앞에 모음, user는 글 또는 글+`image_url` data URL, 도구 호출은 assistant `tool_calls[{id, type: function, function: {name, arguments(JSON 글)}}]` + `tool` 메시지 `{tool_call_id, name, content}`), `tools[{type: function, function: {name, description, parameters}}]`, `tool_choice: "auto"`, 스트리밍이면 `stream: true`, `stream_options.include_usage: true`. `temperature`는 명시적으로 정했을 때만(`ChatCompletionsProperties.getRequestTemperature`), `max_tokens`는 보내지 않는다(서버 기본값). 인증은 키가 있을 때만 `Authorization: Bearer <키>` |
| 응답 | SSE `data:` 줄. `delta.content`는 바로 조각으로, `delta.tool_calls` 조각은 `index`별로 모아 응답이 끝날 때 도구 호출로 넘긴다. `index`가 없거나 같은 `index`에 다른 `id`가 오면(호환 계층) 새 `id`를 새 호출로 본다. 사용량은 `usage`가 있는 조각에서. 흐름 중 `{"error": …}`는 오류로. 도구 호출의 `id`와 `extra_content`(Gemini 3 thought signature)는 `AIFunctionCall` 메타데이터에 두었다가 다음 요청에 그대로 돌려보낸다(대화 저장에도 남음) |
| 도구 스키마 | 속성 없는 `object` 매개변수(지금은 `runLibraryQuery.parameters` 하나)는 "(JSON object text)"를 덧붙인 `string`으로 보낸다. Gemini는 속성 없는 OBJECT 스키마를 거부하고, 도구는 JSON 글을 받는다(`AgentArgs.getJson`) |
| 도구 호출을 못 하는 서버 | vLLM은 `--enable-auto-tool-choice --tool-call-parser <모델별>` 없이 띄우면 `tool_choice: auto` 요청을 400("\"auto\" tool choice requires --enable-auto-tool-choice and --tool-call-parser to be set")으로 거부한다. `ChatCompletionsClient.mapHttpError`가 이를 알아보고 "This vLLM server does not support tool calls, which Chromite Agent needs to work with databases. Start the vLLM server with --enable-auto-tool-choice and the --tool-call-parser of the model (for example hermes, llama3_json or mistral). Server message: …"로 바꾼다(채팅 오류 항목). Providers의 Test는 모델 목록 뒤에 같은 모델로 1토큰 요청(가짜 도구 하나, `max_tokens: 1`)을 보내 미리 알린다: Test 줄에 "연결됨: 모델 N개. 하지만 도구 호출이 되지 않습니다", 그 아래 입력란 폭으로 줄 바꾼 경고 글에 위 안내 전체(긴 글을 한 줄 상태 글에 넣으면 모든 양식이 그 폭으로 늘어나 가로 스크롤이 생겼다). 이 확인은 도구 호출 안내가 있는 제공자(vLLM)만 한다 |
| vLLM 설정 | 기본 URL(예 `http://host:8000/v1`, 기본값 없음, 끝 `/` 없어도 됨), API 키 선택(서버를 `--api-key`로 띄웠을 때만), 모델은 `GET <기본 URL>/models` 목록에서 고르거나 직접 입력 |
| Gemini 설정 | API 키(필수), 기본 URL 기본값 `https://generativelanguage.googleapis.com/v1beta/openai/`(바꿀 수 있음), 모델 목록 `GET models`. 목록 ID의 `models/` 접두어는 뗀다(`GeminiProperties.toModelName`, 요청 예시는 접두어 없는 이름). 기본 모델은 두지 않는다(목록 API에서 고름) |
| 네트워크 | 다른 엔진과 같은 `java.net.http.HttpClient`(`AbstractHttpAIClient`, HTTP/1.1)를 쓴다. 사내 인증서·프록시는 JVM 설정(신뢰 저장소, 시스템 프록시)을 따르고, 연결별 네트워크 설정(SSH·프록시 프로필)은 쓰지 않는다. 다른 AI 엔진과 같다 |
| 환경 변수 | 읽지 않는다(요청되지 않은 설정을 늘리지 않음) |

확인(2026-11-11, Windows 11, 150%와 100%, 한국어·영어): 빌드 `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS,
`python tools/rename/gate.py` ALL GATES PASSED. 실제 제공자는 부르지 않았다. 127.0.0.1의 가짜 OpenAI 호환 서버(vLLM 꼴 `/v1`, Gemini 꼴
`/gemini/v1beta/openai/`: Bearer 키 검사, `models/` 접두어 ID, 한 조각에 `index` 없는 병렬 호출 2개와 thought signature)를 띄우고, 빈 작업 공간·
`-configuration`·`CHROMITE_DATA`·`-eclipse.keyring`을 모두 개인 폴더로 둔 빌드 사본을 앱 안 시험 에이전트(Swing 객체 직접 조작)로 시험했다. Gemini의 기본 URL은
Test 전에 가짜 서버로 바꿨고, 첫 요청부터 모든 요청이 가짜 서버 기록(33줄)에 남았다. 회귀 테스트는 요청되지 않아 실행하지 않았다.

| 시험 | 결과 |
|---|---|
| 목록 | 모델 선택: Anthropic Claude, OpenAI, Google Gemini, Ollama, vLLM 제목(설정 전 "설정 필요"). Providers: 다섯 양식, vLLM "API 키(선택)"·자리 표시 `http://localhost:8000/v1`, Gemini 기본 URL이 보임 |
| Test | vLLM "연결됨: 모델 2개"(`GET /v1/models` + 도구 확인 `POST`, `max_tokens: 1`), Gemini "연결됨: 모델 2개"(`Authorization: Bearer <가짜 키>`, 목록 `gemini-fake-pro`·`gemini-fake-flash`로 접두어 뗌). OK 뒤 `ai-configuration.json`에 URL·모델만 있고 키 없음, keyring에 `/chromite/gemini.apiKey_gemini`(암호문), 작업 공간·데이터 폴더에 키 0건 |
| 도구 호출 못 하는 서버 | 모델 `fake-notools`(vLLM 400 문구): Test "연결됨: 모델 2개. 하지만 도구 호출이 되지 않습니다" + 그 아래 경고 글(양식 폭 그대로, 가로 스크롤 없음). 채팅은 오류 항목에 같은 안내 |
| vLLM 채팅 | 글 조각 → `index` 있는 도구 호출 조각 3개 → `listConnections`(READ, 자동 실행) → 다음 요청에 assistant `tool_calls` + `tool`(`tool_call_id` 같음, `name`) → 답 글. `stream_options.include_usage`, 상태 줄 "토큰: 입력 2,468, 출력 112". `temperature` 없음, 설정 파일에 `ai.useServerDefaultTemperature: false`, `0.3`을 넣으면 `temperature: 0.3` |
| Gemini 채팅 | `model: gemini-fake-pro`/`gemini-fake-flash`(접두어 없음), 병렬 호출 2개 모두 실행, 다음 요청에 두 `id` 짝과 첫 호출의 `extra_content.google.thought_signature`가 그대로 돌아감(가짜 서버 판정 ids-ok, signature-echoed) |
| 도구 스키마 | 요청의 도구 101개, 매개변수 형식은 `boolean`·`integer`·`string`만(`object` 없음) |

미룬 것(결정 필요):

- **Vertex AI**: 기업은 Google Cloud 프로젝트·서비스 계정으로 Vertex AI의 Gemini를 쓸 수 있다. 인증(서비스 계정 JSON 또는 ADC, OAuth 액세스 토큰 갱신)과 주소
  (`https://<region>-aiplatform.googleapis.com/v1/projects/<프로젝트>/locations/<region>/endpoints/openapi`)가 달라 이번 범위(API 키)에서 뺐다. 넣는다면
  같은 Chat Completions 엔진에 토큰 공급자를 더하는 방식이 가장 작다.
- **실제 서버 확인**: 실제 vLLM 서버와 실제 Gemini 키 없이 가짜 서버로만 확인했다(아래 표). Gemini 호환 엔드포인트의 실제 스트리밍 도구 호출 형식
  (`index` 유무, thought signature 위치)은 공개 문서에 자세한 예가 없어 두 형식을 모두 받게 만들었다. 첫 실제 사용 때 확인이 필요하다.

## 시나리오 플레이북

2단계는 이 절을 채팅의 프롬프트(스킬)로, MCP는 `prompts/get`으로 쓴다. 모든 플레이북에 공통:

- 처음에 `getServerInfo`(버전·에디션 → 쓸 시스템 뷰)와 `probePermissions`(안 되는 검사를 미리 앎)를 부른다.
- 권한이 없어 건너뛴 검사는 이유와 필요한 권한(예: `VIEW SERVER STATE`, `pg_monitor`, `PROCESS`, `SELECT_CATALOG_ROLE`)을 말한다.
- 쓰기가 필요하면 먼저 DDL·SQL을 보여 주고 사용자에게 묻는다(멈춤 지점). 실행은 WRITE 도구로 하고 정책이 확인을 받는다.
- 근거(도구 결과의 수치, 계획 연산자, 원문 줄 번호)를 답에 붙인다.

### 1. "이 쿼리 너무 느린데 분석해서 튜닝해 줘"

1. `getServerInfo`, `probePermissions`.
2. `explainQuery` → 비싼 연산자, 스캔·키 조회, 경고(암시적 변환, 통계 없음), 누락 인덱스.
3. 쿼리가 읽기 전용이면 `getActualPlan`(실제 행·시간·I/O). 읽기가 아니면 **멈춤**: 실제 실행은 WRITE이므로 사용자에게 묻는다.
4. `describeTable`, `getTableStatistics`(행 수, 통계 갱신 시각, 변경 수), `listIndexes`, `getIndexUsage`, `getMissingIndexes`, `getIndexFragmentation`.
5. 다시 쓴 쿼리 또는 인덱스 후보를 만든다. `comparePlans`로 전후 비용 비교(인덱스 후보는 PG hypopg가 있으면 `whatIfIndex`, 없으면 예상 근거만).
6. **멈춤**: 바꿀 DDL(`generateDdl`)과 기대 효과를 보여 준다. 사용자가 원하면 `createIndex`(WRITE, 확인) 후 `getActualPlan`·`comparePlans`로 효과를 잰다.

### 2. "지금 DB 상태 어때?"

1. `getServerInfo`, `probePermissions`.
2. 권한 되는 것만: `listSessions`, `getBlockingChain`, `getLongRunningQueries`, `listOpenTransactions`, `getWaitStats`, `getTopQueries`,
   `getDatabaseSizes`, `getSpaceUsage`, `getBackupHistory`, `getJobHistory`(실패), `getErrorLog`(최근 오류), `getServerConfig`.
   SQL Server는 `getQueryStoreTopQueries`·`getQueryStoreRegressions`(Query Store가 켜져 있을 때, 꺼져 있으면 도구가 그렇게 말함)와 `listAgentJobs`(에이전트 상태, 마지막 결과)도.
3. 항목별 정상·주의·위험과 근거, 건너뛴 항목과 이유. 아무것도 바꾸지 않는다.

### 3. "지금 이 쿼리가 너무 느린데 원인을 분석해 줘" (변경 없음)

1. 1번 플레이북의 1~4단계(쓰기 없이). 실제 계획은 읽기 전용일 때만.
2. 실행 중이면 `getLongRunningQueries`, `getBlockingChain`, `listLocks`로 블로킹·대기 확인.
3. 매개변수 스니핑: 계획의 매개변수 컴파일 값 vs 실행 값(`explainQuery`/`getCachedPlans`의 `parameters`). SQL Server는 `getQueryStoreRegressions`로 새 계획과 함께 느려졌는지 본다.
4. 원인을 **순위 목록**으로: 원인, 근거(연산자·수치), 확신도, 고치는 방법(텍스트만).

### 4. "시스템 상태 어때?" (Query Library 모니터링)

1. `listLibraryQueries`로 모니터링 폴더(사용자가 정한 폴더, 예: "Monitoring")를 찾는다. 없으면 **멈춤**: 어느 폴더인지 묻는다.
2. `runMonitoringChecks(folder, connection)`: 쿼리마다 실행하고 기준값으로 판정.
3. 기준값이 없는 쿼리는 값만 보고하고 "기준값 없음"이라고 밝히며, 명백한 문제(예: 0이 아닌 오류 수)만 표시한다.

### 5. 블로킹·잠금 원인 분석

1. `getServerInfo`, `probePermissions`.
2. `getBlockingChain`: 머리 블로커, 대기자 트리, 머리 블로커의 마지막 SQL·트랜잭션 나이·격리 수준·열린 트랜잭션 수, 소견.
3. `listLocks`(머리 블로커 세션): 잡은 잠금 → 개체·인덱스, 잠금 수(에스컬레이션).
4. 필요하면 머리 블로커 문장의 `explainQuery`(스캔이면 잠금 범위가 넓다), 관련 테이블 `listIndexes`.
5. `listOpenTransactions`(idle in transaction), `getDeadlocks`(최근 교착 상태 그래프).
6. 답: **왜** 막히는지(예: "앱 X(host Y)의 세션 57이 10:02부터 트랜잭션을 연 채 sleeping, `UPDATE dbo.Orders …`가 잡은 키 잠금에 세션 61·64가 대기"),
   해결 제안(인덱스, 트랜잭션 짧게, 격리 수준 RCSI, 앱 수정). **멈춤**: 세션 끝내기는 `killSession`(DANGEROUS, 확인)이고 마지막 수단이라고 밝힌다.

### 6. 프로시저·함수·트리거 로직 검토

1. `getRoutineSource`(줄 번호 원문, 매개변수), 트리거면 `listTriggers`로 대상 테이블과 이벤트.
2. `getObjectDependencies`(depth 2): 부르는 루틴, 쓰는 테이블. 필요한 루틴·테이블 원문·구조를 이어서 읽는다.
3. `getRoutineStats`(문장별 실행 수·CPU·읽기·줄 번호), `getCachedPlans`(문장별 계획, 매개변수 컴파일 값).
4. 검토 항목: 커서·행 단위 처리, 비SARG 조건, 암시적 변환, 트랜잭션·오류 처리(TRY/CATCH, XACT_ABORT, 롤백 경로), 매개변수 스니핑 위험,
   동적 SQL 주입 위험, 쓰이지 않는 변수·죽은 분기, 한 행만 가정한 트리거(`inserted`/`NEW`를 변수에 받는 것).
5. 답: 문제마다 줄 번호, 근거, 심각도, 고친 코드(텍스트). **멈춤**: 적용은 `runScript`(WRITE, DDL 전체 미리 보기, 확인).

## Query Library 기준값

라이브러리 쿼리 파일 머리말(`swing-query-library.md` "저장 형식")에 선택 항목 `@threshold`를 더한다. Swing의 파서는 모르는 머리말 줄을 그대로
보존하므로(`QueryLibraryItem`의 "모르는 머리말 줄") Swing에서 저장해도 지워지지 않는다.

```sql
-- @threshold blocked_sessions
--   warn: > 0
--   critical: >= 5
--   label: 블로킹된 세션 수
-- @threshold log_used_pct
--   warn: > 70
--   critical: > 90
--   scope: max
```

| 하위 항목 | 뜻 |
|---|---|
| `@threshold <열>` | 판정할 결과 열(대소문자 무시) |
| `warn`, `critical` | `<연산자> <값>`. 연산자 `>`, `>=`, `<`, `<=`, `=`, `!=`, `contains`, `not-contains`. 값은 수 또는 문자열 |
| `scope` | `any`(기본: 한 행이라도 맞으면), `all`, `max`, `min`, `sum`, `count`(행 수를 값으로) |
| `label` | 보고에 쓸 이름 |

판정 결과(`evaluation`): 기준값마다 `status`(`OK`/`WARN`/`CRITICAL`/`NO_DATA`), 관측값, 맞은 행 번호. 쿼리 전체 상태는 가장 나쁜 것.
기준값이 없으면 `status: "NO_THRESHOLDS"`. 기준값 편집 화면은 Query Library 쿼리 창 머리말의 "Thresholds…"(2단계, 아래 "2단계 구현")다.

## 2단계(채팅 화면) 인터페이스

| 할 일 | 쓸 API |
|---|---|
| 도구 목록 | `AIToolCatalog.getTools()` (`AIFunctionDescriptor`: `getId`, `getName`, `getAiDescription`, `getParameters`, `getAccess`, `getCategoryId`) |
| 채팅의 도구 호출 | `AIAssistantImpl.callFunction`이 이미 `AIToolExecutor.execute(monitor, AIToolClient.CHAT, 도구, 인수, 채팅의 AIDatabaseContext)`로 간다. 채팅의 현재 연결이 `connection` 기본값 |
| 확인 대화상자 | `AIToolConfirmationHandler` 구현 → `AIAgentServices.setConfirmationHandler`. 작업 스레드에서 불리므로 EDT에서 대화상자를 띄우고 결과를 기다려 `AIToolConfirmationAnswer`를 돌려준다. 보통은 Allow/Deny(ALLOW/DENY), `request.pendingTransactions()`가 있으면 Commit / Roll back / Cancel(COMMIT/ROLLBACK/DENY, 커밋할 수 없는 트랜잭션이 있으면 Commit 끔). `request.preview()`(SQL·DDL)를 고정폭 글꼴로 보여 준다. "이 연결에서 항상 허용" → `AIToolPolicy.getInstance().setPermission(...)`(트랜잭션 질문에는 없음) |
| 쿼리 창 열기 | `AIEditorBridge` 구현 → `AIAgentServices.setEditorBridge` |
| 권한 설정 화면 | `AIToolPolicy.getInstance()`: `getSettings()`(복사본), `setPermission(연결 ID 또는 null, 도구 ID 또는 "@분류", 권한 또는 null(상속))`, `resolve(...)`로 실제 값 표시, `isProduction(연결)` |
| 감사 기록 화면 | `AIToolAuditLog.getInstance().readRecent(n)`, `getFile()` |
| MCP 설정 화면 | `AIMcpSettings`: `isEnabled/setEnabled`, `getPort/setPort`, `getToken()`, `regenerateToken()`, `getUrl()`, `getClaudeCodeCommand()`. `AIMcpServer.getInstance().isRunning()`, `getLastError()` |
| Anthropic 설정 화면 | 엔진 `anthropic`, 속성 `AnthropicProperties`(API 키는 비밀 저장소, base URL, 모델, 컨텍스트 크기, 최대 출력 토큰, temperature) |
| 플레이북 | 이 문서의 "시나리오 플레이북"(MCP `prompts/get`과 같은 글, `AIAgentPlaybooks`) |

화면 문구는 Swing 쪽 NLS 묶음(`swing-l10n.md`)에 둔다. 모델의 도구 설명(`plugin.xml`의 `description`)은 모델에게 주는 글이라 영어로 둔다.

## 2단계 구현 (2026-11-07)

위 인터페이스 표대로 구현했다. 표와 다른 점은 아래 "계약과 다른 점"에 모았다.

### 구성

| 위치 | 클래스 | 역할 |
|---|---|---|
| `model.ai.tools` 패키지 `...model.ai.tools.chat` (새, 공개) | `AIAgentChat` | 대화 하나: 보이는 항목(사용자 글, 모델 글, 도구 호출과 결과, 오류)과 제공자·모델·연결·데이터베이스. 모델 메시지는 항목에서 다시 만든다(`toModelMessages`) |
| | `AIAgentChatRunner` | 한 차례 실행: 스트림 요청 → 도구 호출을 `AIToolExecutor.execute(…, AIToolClient.CHAT, …, null)`로 실행 → 결과를 보내고 반복(최대 40번). `connection` 인수가 없으면 대화의 연결을 넣는다. 시스템 프롬프트 = MCP의 `AIAgentPlaybooks.INSTRUCTIONS` + 현재 연결(운영·읽기 전용 표시)·데이터베이스 |
| | `AIAgentChatStore` | 대화 저장: 작업 공간 `.metadata/chromite-ai/chats/<id>.json`(임시 파일 + 이동) |
| | `AIAgentToolSelection` | 요청에 보낼 도구 고르기와 "도구를 모두 보냄" 설정(아래 "요청 크기", 2026-11-08) |
| | `AIAgentProviders` | 제공자 3개(`anthropic`, `openai`, `ollama`)의 설정 프로필(프로필 ID = 엔진 ID), 설정 사본·저장(비밀은 보안 저장소), 엔진 만들기(대화의 모델로 사본을 만들어 기본 모델은 그대로), 모델 목록 |
| `model.ai` `AIToolPolicy` | `resolve(Settings, …)`, `setSettings` | 설정 화면이 저장 전 규칙으로 실행기와 같은 판정을 보이고, OK에서 한 번에 저장한다 |
| Swing `com.seizia.chromite.ui.swing` | `AiAssistantPanel` | 도구 창 "Chromite Agent" |
| | `AiMarkdownView` | 모델 글의 Markdown(제목, 굵게·기울임, 인라인 코드, 목록, 인용, 표, 가로줄)과 코드 블록(복사, 쿼리 창에서 열기) |
| | `AiToolCard` | 도구 호출 카드(접힘/펼침): 이름, 분류 배지, 상태(실행 중·완료·실패·거부·정책으로 막힘·읽기 전용 연결·취소·잘못된 호출), 시간, 결과 요약, 인수·결과 JSON |
| | `AiConfirmationDialog` (+ `Handler`) | `AIToolConfirmationHandler` 구현. Allow/Deny 또는 Commit/Roll back/Cancel |
| | `AiOptionsPage` | 옵션의 "Chromite Agent" 탭: Providers, Tool Policy(2026-11-08부터 "도구를 모두 보냄" 확인란), MCP Server, Audit Log |
| | `QueryLibraryThresholdsDialog` | Query Library 쿼리의 `@threshold` 편집 |
| | `internal/AiMessages` (+ `_ko`) | 화면 문구 198개 |
| | `MainFrame`, `OptionsDialog`, `QueryLibraryEditor` | 연결 지점만: 도구 창·도구 막대 단추·View 메뉴, 확인 처리기·편집기 연결 등록, 쿼리 창 편집기 메뉴 항목 / 옵션 탭 / Thresholds… 단추 |
| `tools/branding/gen_ui_icons.py` | `ai-assistant` 아이콘 | 말풍선 + 반짝임, 두 팩·두 테마 |

### 채팅 도구 창

- 오른쪽 가장자리에 자기 열로 붙고 기본은 숨김이다. View > Chromite Agent(Ctrl+Alt+I)와 주 도구 막대의 토글 단추(Properties 옆)로 연다. 채팅은 Properties 열보다 넓어야 해서
  같은 열에 넣지 않았다(기본 폭 = 오른쪽 열 비율 + 7%).
  **2026-11-08 바뀜(앱 변경 #6·#12):** 기본 배치에서 보이고, 오른쪽 열의 아래(Query Library 아래, 높이 0.6)에 있다. 열 폭은 이전 Agent 열 폭(25%)이다.
  주 도구 막대의 단추는 단추 묶음의 마지막(Find 뒤, 2026-11-12 앱 변경 #53, 그 전에는 창 오른쪽 끝)에 아이콘 + 글자 "Chromite Agent"로 있다
  ([swing-frontend.md](swing-frontend.md) "기본 배치: Query Library와 Chromite Agent", "Chromite Agent 단추를 단추 묶음의 마지막으로"). 시작할 때부터 보이므로 공급자 미설정 안내는 대화를 읽어 모델 선택을 채운 뒤(`showChat`)에도 다시 정한다.
- 위에서 아래로(2026-11-08 바꿈): 도구 막대(새 대화·대화 삭제·대화 기록 │ 플레이북·Chromite Agent 설정, 다른 도구 창처럼 왼쪽 정렬) / 연결과 데이터베이스(편집 가능,
  연결된 연결이면 카탈로그·스키마 목록) / 대화 / 입력(Enter 보내기, Shift+Enter 줄 바꿈) / 모델 선택(왼쪽)과 보내기·중지(오른쪽) / 상태 줄.
- **모델 선택**(2026-11-08, 앱 변경 #14): 제공자와 모델을 콤보 하나로 고른다. 목록은 제공자 이름(굵게, 고를 수 없음) 아래에 그 제공자의 모델(알려진 이름 + Test로 받은
  목록 + 마지막에 쓴 모델)을 들여 쓰고, 설정되지 않은 제공자(키 없음 등)는 모델 대신 "설정 필요"(고르면 Options의 Chromite Agent 페이지 = Providers 탭, 아래 "설정 열기"와 같은 경로)를, 끝에는 "모델 이름 직접 입력"
  (제공자와 이름을 묻는 작은 대화상자, 전의 편집 가능 모델 콤보를 대신함)을 둔다. 닫힌 콤보는 "제공자 · 모델"이고, 좁은 창에서는 줄임표로 줄고 도구 설명에 전체가
  보인다. 제목·"설정 필요"·"직접 입력"은 목록이 닫힐 때 처리하고 선택을 대화의 모델로 되돌린다(키보드로 지나가기만 해서는 동작하지 않음, Esc나 바깥 클릭은 취소).
  실행 중에는 바꿀 수 없다. 저장은 그대로다: 대화마다 제공자·모델, 마지막 사용은 `ai.chat.engine`, `ai.chat.model.<엔진>`. 위쪽 제공자·모델 줄과 그 문구 키
  (`ai_provider_tip`)는 지웠다.
- **대화 기록**(2026-11-08, 앱 변경 #15): 맨 위의 대화 콤보를 지우고(문구 키 `ai_chats_tip`도) 도구 막대에 "대화 기록" 단추(시계 아이콘)를 두었다. 누르면 단추 아래에
  팝업 목록이 열린다: 위에 거르기 입력란(제목·연결 이름), 최근 순, 줄마다 제목(지금 대화는 굵게 + 체크 표시)과 마지막 시각 · 연결 이름, 고른 줄 오른쪽 끝의 ×.
  줄을 누르거나 Enter면 그 대화로 간다(대화 전환과 같은 `showChat`). ×나 Delete(거르기 칸이 비어 있을 때, 아니면 Shift+Delete)는 삭제를 묻고(기본 취소, 빈 대화는
  목록에 없음) 답한 뒤(지웠든 취소했든) 같은 거르기로 목록을 다시 연다. 지금 대화를 지우면 가장 최근 대화를 보인다. 실행 중에는 단추가 꺼진다. 위·아래 화살표는 거르기 칸에 초점을 둔 채
  목록을 움직인다.
- **토큰 사용량**(2026-11-08, 후속 39): 차례가 끝나면 상태 줄에 그 차례의 토큰(입력·출력, 캐시가 있으면 캐시)을 보인다. 한 차례의 요청(도구마다 한 번)이 엔진에서 받은
  사용량(`AIEngineResponseConsumer.usage`, 요청마다 마지막 값)을 `AIAgentChatRunner`가 더해 `Listener.usage`로 알린다. 요청마다 대화를 다시 보내므로 입력은 요청들의
  합이다. 중지하면 "중지했습니다."가 우선하고, 다른 대화로 옮기면 지운다. 대화에 저장하지는 않는다.
- 새 대화는 활성 쿼리 창의 연결과 현재 데이터베이스(그 쿼리 창의 실행 문맥, 곧 창의 자기 세션 또는 함께 쓰는 주 문맥의 기본 카탈로그·스키마)로 시작한다.
  "이 쿼리에 대해 묻기"도 그 창의 문맥이다. 대화에서 연결만 바꾸면 그 연결의 주 문맥 값이다([transactions.md](transactions.md) "쿼리 창마다 별도 연결"). 마지막에 쓴 제공자·모델은
  이 번들 설정(`ai.chat.engine`, `ai.chat.model.<엔진>`)에 남는다. 빈 대화는 새로 만들지 않고 다시 쓴다.
- 모델 호출과 도구 실행은 작업(`AbstractJob`)에서 하고, 글 조각은 80 ms 타이머로 모아 다시 그린다. 바뀌지 않은 앞 블록은 다시 만들지 않는다
  (아래 "글 조각 그리기").
- 중지: 작업을 취소한다 → 실행기가 스트림 대기를 끝내고 엔진(HTTP 연결)을 닫는다. 엔진을 닫으면 받는 중인 응답도 끝낸다(`MonitoredHttpClient.close`, 아래 "실제 API 확인").
  실행 중인 도구는 같은 모니터로 취소되고, 열린 확인 대화상자는 닫히며 DENY다.
  실행 중에는 대화·제공자·연결을 바꿀 수 없다.
- **제공자 설정 안내**(2026-11-12, 앱 변경 #71 f, #72): 공급자가 설정되지 않으면 입력란 위에 "시작하려면 AI 제공자(API 키 또는 로컬 모델)를 설정하세요." 글과
  톱니 아이콘 단추가 보인다. 단추는 도구 막대의 설정 단추와 같은 아이콘·동작·모양(아이콘 단추 크기, 마우스 올림 바탕)이고, 도구 설명과 접근성 이름은
  "Chromite Agent 설정"이다(Options의 Chromite Agent 페이지를 엶). 전의 글자 단추처럼 Tab 키로 갈 수 있다(도구 막대 단추는 포커스를 받지 않음).
  톱니는 글 오른쪽에서 글의 첫 줄 가운데에 놓이고(둘 중 낮은 쪽을 높이 차의 절반만큼 내림), 글은 나머지 폭을 모두 쓰며 공백에서만 줄을 바꾼다
  (`WrappingNote`, 줄보다 넓은 낱말만 글자 사이에서 끊음). 사용자 결정(2026-11-12, 앱 변경 #72): "그냥 톱니바퀴 아이콘으로 교체해. 그러면 편하잖아".
  처음에는 HTML 라벨 옆(BorderLayout 동쪽)에 [Chromite Agent 설정] 글자 단추가 있어 단추가 폭을 가져가고 글이 좁은 칸으로 접혀 잘렸다(Mac 1024px 캡처 "provi", "to sta",
  Windows 100%에서도 좁게 접힘, #71 f). 도구 없음 안내(`toolsOffNotice`)도 같은 HTML 라벨이라 `WrappingNote`로 바꿨다. 확인: 패널 폭 101(최소 카드 폭)·227·296·707,
  한국어·영어 × 100%·150% × 밝은 새로 시작·어두운 전환 뒤, 촘촘한 모드, 마우스 올림과 도구 설명, 누르면 Options의 Chromite Agent 페이지.
  확대 비교 `D:\Tools\_scratch\visual-recheck-fixes\sheets\i-setup-gear.png`(#71 f의 처음 판은 `f-agent-setup-hint.png`).
- 플레이북 6개: 이 쿼리 튜닝, DB 상태 점검, 이 쿼리가 느린 이유, 시스템 점검(쿼리 라이브러리), 블로킹 분석, 프로시저 검토. `AIAgentPlaybooks.getText`의 글에
  `{connection}`(연결 이름), `{sql}`(활성 쿼리 창의 선택 또는 커서의 문장), `{folder}`(묻기, 기본 "Monitoring"), `{object}`(묻기)를 채워 보낸다. 대화에는 짧은 이름만 보이고
  전체 글은 도구 설명에 있다.
- 쿼리 창 편집기의 오른쪽 클릭 메뉴 "Ask Chromite Agent About This Query"/"이 쿼리에 대해 Chromite Agent에게 묻기": 선택한 글, 없으면 커서의 문장(자동완성과 같은
  `SQLScriptParser.extractQueryAtPos`, 연결이 없으면 전체 글)을 쿼리 창의 연결로 보낸다. 지금 대화가 비어 있지 않으면 새 대화로 시작한다.
- 대화 기록에는 도구 결과(DB 값)가 들어 있어 작업 공간 밖에 두지 않는다. 로그에는 대화 내용·인수·키를 쓰지 않는다.

### 글 조각 그리기 (2026-11-08)

- 엔진의 글 조각은 `AIAgentChatRunner.StreamCollector`가 대화 항목에 붙이고 `Listener.textAppended`로 알린다. 화면(`AiAssistantPanel`)은 새 모델 글 항목을
  만들 때 그때까지의 글로 그리고, 그 뒤의 조각은 80 ms 타이머가 모아 다시 그린다(`renderPending`). 차례가 끝나면(`turnFinished`) 남은 것을 그린다.
- 문제: 다시 그릴 항목을 필드 하나(`pendingRender`)에 기억했다. 도구가 타이머 간격보다 빨리 끝나면(7–60 ms) 다음 응답의 글이 타이머 전에 와서 앞 항목을 덮어썼고,
  앞 항목은 처음 그린 조각에서 멈췄다("Now the transact", 16자 조각). 차례가 끝날 때는 마지막 항목만 그려 맨 끝 답은 늘 온전했다. 도구 앞의 글(같은 응답의 글)도
  다음 응답이 빨리 오면 같은 식으로 잘렸다.
- 고친 것: 다시 그릴 항목을 모음(`LinkedHashSet`)으로 두고 타이머·차례 끝에서 모두 그린다. 엔진 쪽은 원인이 아니다: Anthropic Messages(`AnthropicClient.StreamState`,
  `text_delta`마다 조각 하나), OpenAI Responses(`OpenAiAPIStreamConsumer`, `response.output_text.delta`, 함수 호출 항목 동안만 글을 건너뜀), Ollama(`OllamaClient`,
  줄마다 `message.content`, 도구 호출이 있는 줄은 글과 호출을 함께)는 조각을 모두 넘긴다. 요청마다 새 소비자를 만들어 도구 결과 뒤 상태가 남지 않는다.
- 확인: 16자 조각으로 보내는 가짜 Messages API(글 + `getBlockingChain` → 글 + `listOpenTransactions` → 답). 고치기 전 "I will look at t", "Now the transaction of the
  blocking session. Thi"에서 멈춤, 고친 뒤 세 글 모두 끝까지(영어·한국어 화면).

### 확인 대화상자

- `MainFrame`이 시작할 때 `AIAgentServices.setConfirmationHandler(new AiConfirmationDialog.Handler(...))`를 등록하고 닫을 때 지운다. 채팅과 MCP 서버가 같은 처리기를 쓰므로
  외부 에이전트의 쓰기 도구도 실행 중인 앱에 같은 대화상자를 띄운다(요청한 곳: "로컬 MCP 서버를 통한 외부 에이전트").
- 처리기는 작업 스레드에서 EDT에 모달 대화상자를 띄우고 100 ms마다 모니터 취소를 본다. 공정 잠금으로 대화상자는 한 번에 하나다.
- 보이는 것: 제목 줄(쓰기/위험, 채팅/MCP에 따라), 도구 이름·ID·분류 배지, 연결(운영이면 "Production" 배지), 요청한 곳, 묻는 이유(정책 이유를 화면 언어로), 실행할 SQL(읽기 전용
  SQL 편집기, 쿼리 창과 같은 색), 인수 표(실행기가 비밀을 지운 값, 긴 값은 400자에서 자름).
- WRITE: "\"도구\" 항상 허용(이 연결)" 확인란 → `AIToolPolicy.setPermission(연결 ID, 도구 ID, AUTO)`. DANGEROUS: 확인란 대신 "설정의 연결별·도구별 규칙으로만" 안내.
  트랜잭션 질문(제목 "Open Transaction"/"열린 트랜잭션"): 열린 트랜잭션 목록과 Commit / Roll back / Cancel. 커밋할 수 없는 트랜잭션이 있으면 Commit을 끈다.
- 기본 단추는 Deny / Cancel, Esc와 창 닫기도 거부다.

### 편집기 연결

`AIAgentServices.setEditorBridge`: `openInEditor` 도구가 부르면 EDT에서 새 쿼리 창을 연다(연결, 글, 제목 → 파일 이름 `제목.sql`). 실행하지 않는다.
모델 글의 코드 블록 "쿼리 창에서 열기"도 같은 경로(대화의 연결)다. 2026-11-08부터 Swing 구현은 `AiQueryWindowBridge`이고 쿼리 창 목록·읽기·고치기도 맡는다
(아래 "편집기 도구").

### 편집기 도구 (2026-11-08, 앱 변경 #16)

사용자 질문 "현재 열려있는 쿼리 창을 보거나 쓸 수 있는 도구가 없나?"에 대한 결정(읽기·쓰기 모두)이다. 전에는 쿼리 창 관련 도구가 새 창을 여는 `openInEditor`
하나였고, 모델이 열린 창의 글을 읽거나 고칠 방법이 없었다(사용자가 오른쪽 클릭 "이 쿼리에 대해 Chromite Agent에게 묻기"로 보내는 길만 있었다).

| 위치 | 내용 |
|---|---|
| `model.ai` `AIEditorBridge` | 메서드 3개와 값 레코드(`QueryWindow`, `TextPart`, `QueryWindowText`, `EditMode`, `QueryWindowEdit`). 고치기는 `apply` 인수 하나로 미리 보기(계산만)와 실제 고치기가 같은 범위 계산을 쓴다 |
| `model.ai` `AIToolPreviewProvider.Kind`, `AIToolConfirmationRequest.previewKind` | 미리 보기 종류 `RUN`(실행할 SQL·DDL·명령줄) / `TEXT_CHANGE`(바뀌는 글). 모델 번들은 종류만 알리고 문구는 화면이 정한다 |
| `model.ai.tools` `EditorTools` | 세 도구. 인수 읽기, 결과 JSON, 확인 미리 보기(줄 차이). `plugin.xml` 분류 `agent.editor` |
| Swing `AiQueryWindowBridge` | 브리지 구현. 모든 읽기·고치기를 EDT에서(`invokeAndWait`) 하고 결과를 돌려준다. `MainFrame`이 시작할 때 등록하고 닫을 때 지운다 |
| Swing `AiConfirmationDialog` | `TEXT_CHANGE`면 제목 "바뀌는 글(- 지움, + 넣음)", 첫 줄 "…쿼리 창의 글을 바꾸려고 합니다. 실행하거나 저장하지 않습니다.", 기본 정책의 묻는 이유 "쿼리 창의 글을 바꾸는 도구는 실행 전에 묻습니다(기본값, Tool Policy에서 묻지 않게 할 수 있음)", 지운 줄·넣은 줄을 결과 표의 지운 행·넣은 행 색(`Chromite.Table.removedBackground`/`addedBackground`)으로 칠함 |

- **창 ID**: 창 객체마다 처음 볼 때 `w1`, `w2`…를 정한다(약한 참조, 닫힌 창은 잊음). 탭 위치는 닫거나 옮기면 바뀌고 제목은 겹칠 수 있어서다. `window`를 빼면 활성 창
  (활성 창이 없고 창이 하나면 그 창), ID가 맞지 않으면 제목이 하나로 정해질 때만 받는다. 못 찾으면 열린 창의 ID와 제목을 오류에 적는다.
- **읽기**(`getQueryWindowText`): `part=statement`는 "이 쿼리에 대해 묻기"와 같은 파서(`SQLScriptParser.extractQueryAtPos`, `AiAssistantPanel.findStatementAtCaret`)이고
  연결이 연결되어 있지 않으면 전체 글(`part: all`로 알림)이다. `maxChars`를 넘으면 자르고 `truncated`와 안내를 넣는다.
- **고치기**(`editQueryWindow`): `insert`(커서, 빈 글이면 INVALID), `replaceSelection`(선택이 없으면 오류: insert나 replaceAll을 쓰라고 알림, 빈 글이면 선택 삭제),
  `replaceAll`. 결과의 `window`는 고친 뒤의 창(저장 안 됨, 새 글 선택)이다. 줄 바꿈은 문서 형식(`\n`)으로 바꾸고 저장할 때 파일의 줄 바꿈으로 돌아간다. `beginAtomicEdit`/`endAtomicEdit`로 묶어 Ctrl+Z 한 번에 되돌아간다.
  고친 뒤 그 창 탭을 앞에 두고 새 글을 선택한다(초점은 옮기지 않음: 사용자가 채팅에 쓰는 중일 수 있다). 창은 "저장 안 됨"이 되고 실행·저장하지 않는다.
  쿼리가 실행 중인 창과 편집할 수 없는 창은 거절한다. 이 검사는 미리 보기에서도 해서 확인을 묻기 전에 거절한다.
- **정책**: WRITE, `writes="none"`(DB를 바꾸지 않으므로 읽기 전용 연결 규칙과 상관없음), 연결 인수가 없어 운영 연결 규칙도 걸리지 않는다. 기본은 실행 전 확인이고
  Options > Chromite Agent > Tool Policy에서 이 도구를 "자동 실행"으로 둘 수 있다(전역 도구 규칙, 판정 순서 5). 확인 대화상자의 "이 연결에서 항상 허용"은 연결이 없어
  보이지 않는다.
- **미리 보기**: 첫 줄 `@@ <창 제목> - <연결>`, 그다음 바뀐 곳마다 `@@ line N`(고친 뒤 글의 줄 번호)과 앞뒤 2줄의 바뀌지 않은 줄, `- `/`+ ` 줄. 줄 차이는 쿼리 재구성에
  쓰는 Myers 차이(`com.seizia.chromite.model.sql.Diff`)로 구하고, 앞뒤의 같은 줄을 먼저 잘라 바뀐 부분만 비교한다. 바뀐 줄이 합쳐 5,000줄을 넘으면 비교하지 않고 지운 줄과
  넣은 줄을 그대로 보이고, 미리 보기는 2,000줄에서 자르고 남은 바뀐 줄 수를 적는다.
- **감사**: 세 도구 모두 다른 도구처럼 실행기가 감사 기록에 남긴다(MCP로 붙은 외부 에이전트가 사용자의 글을 읽는 것 포함, 위 "감사 기록"). 기록에는 인수만 있고 읽은 글은 없다.
- **도구 수**: MCP `tools/list`는 101개(READ 75, WRITE 21, DANGEROUS 5)다. 채팅은 연결 인수가 없는 도구라 늘 보낸다(`AIAgentToolSelection`). 사이트의 지원 범위 페이지는
  Agent 도구 수를 말하지 않아 고치지 않았다.

#### 확인 (2026-11-08, `chromite/agent-panel-2`)

빌드한 Swing 제품을 개인 작업 공간·데이터 폴더로 띄우고, 가짜 Messages API·Responses API(로컬, 스크립트로 정한 도구 호출, 사용량 보고)를 제공자 주소로 두어
앱 안의 시험 에이전트(Attach API, Swing 객체를 직접 다룸, 화면 좌표·OS 입력 없음)와 Python MCP 클라이언트로 확인했다. 한국어 다크, 영어(`-nl en`), 화면 배율 150%.

| 시험 | 결과 |
|---|---|
| 모델 선택 | 목록: 제공자 제목(굵게) 아래 모델(들여 씀), 설정되지 않은 Ollama에 "설정 필요", 끝에 "모델 이름 직접 입력". 닫힌 콤보 "Anthropic Claude · claude-sonnet-5-5"와 같은 도구 설명. 모델을 고르면 대화의 엔진·모델이 바뀜, 취소(Esc)·제목은 대화의 모델로 되돌림, "설정 필요"는 Options의 Chromite Agent 페이지를 엶, 직접 입력(제공자 + 이름)은 대화와 목록에 들어감. 보내기 줄 왼쪽에 글자 폭만큼, 좁은 창에서는 줄임표 |
| 대화 기록 | 최근 순 3개, 지금 대화 굵게 + 체크, 거르기("인덱스") → 1개 → Enter로 그 대화, 위·아래 화살표, Esc로 닫힘, × → 삭제 질문(취소 기본) → 취소하면 목록이 다시 열리고 거르기에 초점, 삭제하면 목록에서 빠짐, 지금 대화를 지우면 가장 최근 대화 |
| 편집기 도구(채팅) | `listQueryWindows`(w1, 선택 범위), `getQueryWindowText`(연결 없으면 statement → all, 선택), `editQueryWindow` 넣기: 확인 대화상자 제목 "바뀌는 글", 지운 줄·넣은 줄 색, 거부 → 글 그대로, 허용 → 넣은 글 선택·저장 안 됨 표시, Ctrl+Z(`undoLastAction`) 한 번에 원래 글. 선택 없음 `replaceSelection` → 묻기 전에 오류. `replaceAll`의 줄 차이. Tool Policy 전역 규칙 AUTO → 묻지 않고 고침(감사 `global rule for editQueryWindow`) |
| 편집기 도구(MCP) | `tools/list` 101개(READ 75, W 21, D 5), 세 도구의 한국어 제목과 `readOnlyHint`. `listQueryWindows`·`getQueryWindowText`가 감사 기록에 `client: MCP`로 남음. PostgreSQL에 연결된 창에서 `part=statement`가 커서의 둘째 문장만(시작 16, 끝 52). MCP `editQueryWindow` → "외부 에이전트(MCP)가 쿼리 창의 글을 바꾸려고 합니다" 확인 → 거부 → `isError` |
| 후속 39 | 상태 줄 "토큰: 입력 14,863(캐시 100), 출력 50"(영어 "Tokens: 14,418 in (100 cached), 50 out"), 도구가 있는 차례는 요청들의 합. 가짜 API가 받은 요청에 `temperature` 없음(Anthropic·OpenAI 모두). 없는 모델: "Anthropic request failed: model: claude-does-not-exist-9 (not found, HTTP 404)", OpenAI "… does not exist or you do not have access to it. (not found, HTTP 404)" |

### 설정 (Tools > Options > Chromite Agent)

| 탭 | 내용 |
|---|---|
| Providers | 제공자(Anthropic Claude, OpenAI, Google Gemini, Ollama, vLLM)마다 API 키(Ollama 제외, vLLM은 "선택"), 기본 URL(OpenAI는 호환 엔드포인트, Ollama는 호스트, vLLM은 자리 표시 `http://localhost:8000/v1`, Gemini는 기본값이 보임), 기본 모델, Test. vLLM의 Test는 모델 목록 뒤에 그 모델로 도구 호출이 되는지도 확인한다(아래 "Chat Completions 엔진"). 키 입력란은 늘 비어 있고 "저장됨" 안내만 보인다(키를 화면에 다시 보이지 않음). 새 키를 넣을 때만 바꾸고, "제거"로 지운다. Test는 저장하지 않은 값으로 모델 목록을 받아 보고 받은 목록을 채팅 모델 선택에 남긴다. 저장은 OK에서 바뀐 제공자만 `AIAgentProviders.saveProperties`(설정 파일 `ai-configuration.json`에는 키가 없고 OS 보안 저장소에만, 위 "API 키 저장". 보안 저장소가 키를 저장하지 못하면 그 이유를 알리고 아무것도 저장하지 않음) |
| Tool Policy | 맨 위에 늘 보이는 규칙 3개(운영 연결은 쓰기·위험 막힘, 읽기 전용 연결은 어떤 규칙으로도 바꿀 수 없음, 위험 도구 자동 실행은 연결 하나·도구 하나 규칙으로만). 규칙 대상(모든 연결 = 기본값, 또는 연결 하나, 운영 연결은 "(운영)" 표시), 분류별 규칙 3개, 도구 필터, 도구 표(도구, 그룹, 분류, 규칙: 상속/자동 실행/실행 전 확인/막음, 실제 권한). 실제 권한은 저장 전 규칙으로 `AIToolPolicy.resolve(Settings, …)` 판정(읽기 전용 연결이면 "실행 안 함"). OK에서 `setSettings` |
| MCP Server | 사용 여부, 포트(OK에서 저장하면 서버가 바로 시작·중지·재시작), 상태(실행 중/중지/오류), 주소, 토큰(가림·보기·복사·다시 만들기는 묻고 즉시), Claude Code 명령(`<CHROMITE_MCP_TOKEN>` 자리 표시, 복사) |
| Audit Log | `readRecent(2000)`을 작업에서 읽어 표(시각, 클라이언트, 도구, 분류, 연결, 정책, 결과, ms), 글자·클라이언트·결과로 거르기, 고른 기록의 JSON 전체, 파일 경로 |

#### 설정 열기 (2026-11-08)

- Chromite Agent 창의 도구 막대 설정 단추와 공급자 미설정 안내의 설정 단추는 Options를 Chromite Agent 페이지로 연다(전에는 첫 페이지 General).
  `AiAssistantPanel.Host.openOptions(OptionsDialog.PAGE_AGENT)` → `OptionsDialog.open(owner, pageId)`. 페이지는 화면 이름이 아니라 식별자(`agent`)로 고른다
  ([swing-options.md](swing-options.md) "목록, 찾기, 페이지 열기").
- 모델 선택의 "설정 필요" 항목(앱 변경 #14, 위 "채팅 도구 창")도 같은 경로로 연다.
- 새 대화상자의 Agent 페이지는 첫 탭 Providers를 보인다. 미설정 안내에서 열 때 필요한 탭이 이것이라 하위 탭을 따로 고르는 식별자는 두지 않았다.
- Providers 양식은 자기 폭(입력란 28자)을 지킨다. Options 창이 넓어져 입력란이 오른쪽 끝까지 늘어나던 것.

### Query Library 기준값 편집

Query Library 쿼리 창 머리말의 Parameters… 옆 "Thresholds…"/"기준값"(개수 표시). 표(열, 경고, 위험, 범위 any/all/max/min/sum/count, 표시 이름)를 고치고, 조건은
평가기와 같은 문법(`<연산자> <값>`)만 받는다. 기준값은 Swing 파서가 모르는 머리말 줄(`QueryLibraryItem.getExtraHeader()`)로 보관되므로, 저장할 때 파일의 모르는 머리말에서
`@threshold` 블록만 바꾸고 다른 줄은 그대로 둔다. Swing 파서·항목 클래스에 필드를 더하지 않았다(형식의 주인은 에이전트 도구 `LibraryReader`).

### 계약과 다른 점

- **채팅은 `AIAssistantImpl`/`AIChatSession`을 거치지 않는다.** 위 표의 "채팅의 도구 호출" 행은 기존 채팅 경로를 적었지만, 그 경로는 QM 대화 저장소, 할당량, DB 문맥 스냅숏
  (`AIDatabaseContext`, 연결과 논리 데이터 소스를 만든다)을 함께 쓴다. 에이전트 채팅은 연결을 도구 인수로 넘기고 정책·확인·감사는 실행기가 하므로, 실행기를 직접 부르는
  작은 실행기(`AIAgentChatRunner`)를 두었다. 실행기에 채팅 문맥(null)을 넘기지 않는 대신 `connection` 인수를 채운다. 기존 `AIAssistantImpl.callFunction` 경로는 그대로다.
- **데이터베이스 문맥은 시스템 프롬프트로 알린다.** `database` 인수의 뜻이 도구마다 달라(인스턴스 선택, 필터) 자동으로 넣지 않았다.
- **설정 화면은 탭 하나("Chromite Agent") 안의 하위 탭 4개다.** 옵션의 위 탭 줄이 넘치지 않게 했다.
- **Ollama**: 엔진이 응답의 첫 도구 호출만 돌려준다(1단계 "엔진"). 이 PC에 Ollama가 없어 실행해 보지 못했다.
- `AIToolPolicy`에 공개 메서드 2개를 더했다(위 "구성").

### 확인 (2단계, 2026-11-07)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `java tools/jre/BundleJre.java windows-x64`, `python tools/rename/gate.py`
ALL GATES PASSED. 회귀 테스트는 요청되지 않아 실행하지 않았다.

유료 API 키가 이 PC에 없어 실제 LLM은 부르지 않았다. 로컬 가짜 Messages API(스크립트로 정한 응답: 도구 호출 → 결과 → 다음 도구, SSE 스트림, 조각마다 지연)를
Anthropic 제공자의 기본 URL로 두고, 빌드한 Swing 제품을 개인 작업 공간으로 띄워 EDT 시험 에이전트(공개 Swing API로 단추·입력란 조작, 창만 그림)로 시험했다.
연결: PostgreSQL 17의 시험 DB `chr_ai_agent_chat`에 개발용, 수동 커밋 종류(자동 커밋 끔), Production 종류 3개. Ollama는 설치되어 있지 않아 시험하지 않았다(설치하지 않음).

| 시험 | 결과 |
|---|---|
| 제공자 설정 | Options > AI Assistant > Providers에 키·기본 URL 입력 → Test "Connected: 3 models available". `ai-configuration.json`에는 기본 URL·모델만 있고 키는 없다(작업 공간 전체 검색 0건). 앱을 다시 시작한 뒤에도 채팅이 동작(키를 보안 저장소에서 읽음), 입력란은 "저장됨" 안내만. **2026-11-07 정정**: 이때 키는 실제로는 `.metadata/.plugins/org.eclipse.core.runtime/.settings/com.seizia.chromite.ui.swing.prefs`에 평문으로 있었다("작업 공간 전체 검색 0건"은 틀린 기록이다). 위 "API 키 저장"에서 고쳤다 |
| 대화 + Allow | READ `getServerInfo`·`listTables`(자동 실행) → WRITE `runSql INSERT` 확인 대화상자(도구, 쓰기 배지, 연결, 요청한 곳, 이유, 실행할 SQL, 인수) → 허용 → 행 1개 들어감(DB 확인), 모델의 Markdown 답(제목, 목록, 표, SQL 코드 블록)과 도구 카드 3개 |
| Deny | 같은 흐름에서 거부 → 카드 "거부됨 - Denied by the user", 모델이 거부를 받아 "nothing changed" 답, DB에 행 없음 |
| 열린 트랜잭션 | 수동 커밋 연결에서 `runSql` 허용 → 카드 "트랜잭션 열림" → `commitTransaction`이 "열린 트랜잭션" 대화상자(Commit / Roll Back / Cancel, 기본 Cancel). 영어 실행에서 Commit → 행이 커밋됨, 한국어 다크 실행에서 롤백 → 행 없음(DB 확인) |
| 항상 허용 | 확인 대화상자에서 "Always allow \"Run SQL\" on \"Chat PG\"" + 허용 → `tool-policy.json`에 `chr-chat-pg`의 `runSql: AUTO`. 다음 대화의 같은 `runSql`은 묻지 않고 실행 |
| MCP | 설정에서 MCP 서버를 켜고 Python MCP 클라이언트(`urllib`, 토큰은 작업 공간 파일)로 `runSql INSERT` 호출 → 실행 중인 앱에 "외부 에이전트(MCP)" 확인 대화상자 → 허용 → 클라이언트가 `updateCounts [1]`을 받음 |
| 편집기 연결 | MCP `openInEditor`(연결, SQL, 제목) → "AI suggestion.sql - Chat PG" 쿼리 창이 열림(실행 안 함) |
| Ask AI | 그 쿼리 창 편집기 메뉴 "Ask AI About This Query" → 새 대화, 연결 Chat PG, 데이터베이스 `chr_ai_agent_chat`(쿼리 창 문맥), 문장이 SQL 코드 블록으로 감 |
| 중지 | 긴 스트림 중 중지 → 상태 "Stopped.", 글이 멈추고 입력이 다시 가능 |
| 기준값 | Query Library 쿼리 창의 "기준값(1)…" → 경고 값 고침 + 새 기준값(scope max, 한국어 표시 이름) → 저장. 파일의 `@threshold` 블록 2개, 다른 머리말 그대로. MCP `getLibraryQuery`가 두 기준값을 읽고 `runMonitoringChecks` 판정 |
| 감사 기록 | 위 호출이 모두 보임(클라이언트 CHAT/MCP, 정책 AUTO/CONFIRM, 결과 완료/거부됨), 거르기 동작 |
| 언어·테마 | `-nl en` 라이트, OS 기본(한국어) 라이트·다크. 글자 잘림 없음, 다크에서 Markdown·카드·대화상자 색이 테마를 따름 |

시험 중에 찾아 고친 것: Markdown 가로줄 정규식이 클래스 초기화에서 실패(모델 글이 안 보임), 코드 블록이 좁은 창에서 가로 스크롤 막대에 가려짐(줄 바꿈으로),
설정 후에도 "제공자를 설정하세요" 안내가 남음(AI 설정 변경 이벤트로 갱신), 연결을 고를 때 데이터베이스가 비어 있음(쿼리 창 문맥의 기본값으로), 테마를 바꿔도
Markdown 글자색이 그대로(외형 값을 따르게).

### 실제 API 확인 (2026-11-08)

사용자가 임시 Claude API 키를 시험 빌드(`D:\Chromite-Test`, devel `2bf537048a`)에 넣은 뒤 실제 Anthropic API로 처음 확인했다. 데이터 폴더와 작업 공간은
사본(`D:\Tools\_scratch\agent-real-api`)으로 쓰고, 화면은 앱 안의 시험 에이전트(Attach API, Swing 객체를 직접 조작, 화면·키보드 입력 없음)로 조작했다.
모델은 사용자가 정한 `claude-sonnet-5-5`, 연결은 PostgreSQL 17의 시험 DB `chr_agent_real_api`(테이블 2개, 5행·12행). API 요청은 모두 21번이다.

| 시험 | 결과 |
|---|---|
| 첫 요청 | 사용자 설정 그대로 모든 요청이 "Anthropic request failed: \`temperature\` is deprecated for this model."로 실패(아래 1). 사본의 설정에 `ai.useServerDefaultTemperature: true`를 넣어 우회하고 나머지를 시험했다 |
| 대화 | 한국어·영어 질문 모두 답이 스트리밍되어 끝남. 토큰·사용량 표시는 없다(`AIAgentChatRunner.StreamCollector.usage`가 받지만 보이지 않음). 2026-11-08 고침: 차례가 끝나면 상태 줄에 보인다(위 "채팅 도구 창") |
| 읽기 도구 | "public 스키마의 테이블과 정확한 행 수" → `listTables`, `countRows` 2번(자동 실행, 카드 3개) → 표에 5·12(psql과 같음) |
| 쓰기 + 거부 | `insertRows` 확인 대화상자(쓰기 배지, 실행할 내용, 인수, 기본 단추 Deny에 초점) → Deny → 카드 "거부됨", DB 그대로(psql 5행) |
| 쓰기 + 허용 | 같은 요청에 Allow → 1행 들어감(psql 6행), 자동 커밋 연결이라 `transactionOpen: false`, 열린 트랜잭션 없음(`pg_stat_activity`) |
| 수동 커밋 | 연결의 자동 커밋을 끔 → `insertRows` 허용 → `transactionOpen: true`, 다른 세션에서 행이 안 보이고 에이전트 세션은 `idle in transaction`. `rollbackTransaction`이 "Open Transaction"(Commit / Roll Back / Cancel, 기본 Cancel) → Roll Back → 행 없음, 트랜잭션 없음 |
| 운영 연결 | 연결 종류를 Production으로 → `insertRows` BLOCKED("production connection …"), DB 그대로 |
| 중지 | 긴 답 중 중지 → 글은 멈췄지만 상태가 "Stopping…"으로 약 20초 남고 그동안 보내기가 안 됨(아래 2). 오류 대화상자 없음, 끝난 뒤 다시 보낼 수 있음 |
| API 오류 | 없는 모델 이름 → 대화에 "Anthropic request failed: model: claude-does-not-exist-9"(404 `not_found_error`의 문구). 키 없음. 2026-11-08 고침: 404 응답의 글에 "not found"가 없으면 "(not found, HTTP 404)"를 덧붙인다(`AIHttpUtils.parseOpenAIStyleErrorMessage`, 모든 엔진). OpenAI의 "The model … does not exist …"도 같다 |
| 감사 기록 | 도구 호출 9건이 정책(AUTO/CONFIRM/BLOCKED)과 결과(SUCCESS/DENIED/BLOCKED)로 남음. 트랜잭션 질문의 답(Roll back)은 없었다(아래 3) |
| 키 | 사본 작업 공간·데이터 폴더·로그·증거 파일에서 `sk-ant-` 0건 |

찾아 고친 것:

1. **temperature**: 설정 파일에는 늘 `gpt.model.temperature: 0.0`이 저장되고(`BaseAIEngineProperties.temperature`), `useServerDefaultTemperature`가 없으면
   `getRequestTemperature()`가 그 값을 돌려주어 요청마다 `temperature: 0.0`이 갔다. 지금의 Claude 모델은 이 매개변수가 있으면 요청 전체를 거부한다. Chromite 화면에는
   temperature를 정하는 곳이 없으므로(`setTemperature` 호출 없음), `AnthropicProperties.getRequestTemperature()`가 명시적으로 정했을 때만 값을 돌려주게 했다.
   고친 빌드에서 사용자 설정 그대로(우회 없이) 요청이 성공했다. OpenAI 엔진도 같은 기본값을 보내지만(`OpenAIProperties.getTemperature`는 전역 설정값) 키가 없어
   확인하지 못했다(아래 "위험과 남은 일").
2. **중지가 답이 끝날 때까지 기다림**: 중지하면 실행기는 스트림 대기를 끝내고 엔진을 닫는데, `MonitoredHttpClient.close()`의 `HttpClient.close()`는 진행 중인
   요청이 끝날 때까지 기다린다. 받는 중인 응답을 먼저 닫는 코드는 콜백 스레드의 `ThreadLocal`만 보아 작업 스레드에서 닫을 때는 아무것도 닫지 않았다. 그래서
   모델이 답을 다 쓸 때까지(약 20초) 받기가 이어지고(그만큼 과금), 화면은 "Stopping…"이었다. `close()`가 `HttpClient.shutdownNow()`로 진행 중인 요청을 먼저 끝내게
   했다. 엔진을 닫는 곳(`AIAgentChatRunner`, `AIEngineResponseConsumerImpl`, 모델 목록·`generateText`의 try-with-resources)은 모두 응답을 다 받았거나 사용자가
   멈춘 뒤다. 고친 빌드에서 같은 시험: 중지 후 250 ms 안에 "Stopped.", 보내기 단추가 돌아옴.
3. **감사 기록에 트랜잭션 답이 없음**: `rollbackTransaction`은 READ(AUTO)라 기록이 `permission: AUTO`, `outcome: SUCCESS`뿐이었고, 사용자가 "Open Transaction"
   질문에 Roll back을 고른 것은 결과 JSON(`transactionsBeforeCall`)에만 있었다. 실행기가 감사 기록에도 같은 답을 넣는다(위 "감사 기록"). 고친 빌드에서 수동 커밋
   연결에 `insertRows`(허용) → `rollbackTransaction`(Roll Back)을 실행기로 직접 불러 확인: 기록에 `"transactionsBeforeCall":{"action":"ROLLED_BACK","count":1}`.

## 1b단계 도구 (2026-11-08)

1단계에서 미룬 도구를 같은 카탈로그·정책·감사·읽기 전용 보호로 더했다(위 카탈로그의 단계 "1b"). MCP `tools/list`는 96개(READ 73, WRITE 19,
DANGEROUS 4)다. 표시 이름은 `model.ai.tools`의 `OSGI-INF/l10n/bundle*.properties`(영어·한국어), 도구 ID와 모델에 주는 설명은 영어다.

### SQL Server Agent 작업 (`AgentJobTools`)

- 모델은 #83의 `ext.mssql` `model.agent`다(브리프의 `model.admin`이 아니다: [swing-sqlserver-agent.md](swing-sqlserver-agent.md) "모델은 `ext.mssql`에 둔다").
  목록·기록은 `SQLServerAgentManager.readJobs`/`readHistory`, 정의는 `SQLServerAgentJobDefinition.load`, 서비스 상태는 `SQLServerAgent.readState`,
  바꾸는 문은 `SQLServerAgentManager.startJobStatement`/`stopJobStatement`/`enableJobStatement`, 실행은 `SQLServerAgentManager.execute`(격리 연결, 한
  트랜잭션, 커밋)다. Swing 에이전트 메뉴와 같은 T-SQL이 확인 대화상자에 보인다.
- 읽기는 msdb 도움말 프로시저라 에이전트 역할(SQLAgentUserRole·ReaderRole·OperatorRole)이면 된다. 모델의 읽기 메서드가 DBeaver 세션을 받으므로
  **메타데이터 세션**에서 읽는다(탐색기 에이전트 노드와 같다). 처음에는 유틸 세션(연결의 Main 문맥)에서 읽었는데, 수동 커밋 연결(운영 종류)에서
  `sp_help_job`이 사용자 Main 문맥에 트랜잭션을 열어 앱을 닫을 때 "커밋하지 않은 트랜잭션: MSSQL PROD (Main)"이 떴다. 메타데이터 세션으로 바꾼 뒤
  같은 호출 후 질문 없이 닫혔다. 에이전트 격리 문맥(JDBC 직접)은 DBeaver 세션을 줄 수 없고, 세션 문장을 쓰면 쿼리 관리자가 에이전트 문맥의
  트랜잭션을 따로 세게 되어 쓰지 않았다.
- 시작·중지는 에이전트 서비스에 보내는 요청이라 되돌릴 수 없다. 그래서 수동 커밋 연결에서도 에이전트 트랜잭션에 넣지 않고 모델이 바로 커밋한다
  (SQL Server 백업 도구와 같다). 정책은 다른 WRITE 도구와 같다(기본 확인, 운영 연결 막힘, 읽기 전용 연결 실행 안 함).
- 서버가 "이미 실행 중"·"실행 중이 아님"에도 "에이전트에 알릴 수 없음"과 같은 오류 번호(22022)를 내어 모델의 오류 분류가 "에이전트가 멈춤"으로
  잘못 알린다. 그래서 실행 전에 작업 상태와 서비스 상태(`canRunJobs`)를 보고 정확한 이유를 돌려준다. 사용 설정은 msdb만 바꾸므로 에이전트가 멈춰도 된다.
- 상태·결과 이름은 모델의 표시 이름(화면 언어)과 코드(`lastRunOutcomeCode`, `outcomeCode`)를 함께 낸다. 일정 설명은 서버 글(`Every day at 30000`처럼 시각이
  hhmmss 수)이라 `activeStartTime`·`activeEndTime`(HH:MM:SS)을 더했다.

### Oracle 진단

ORACLE LICENSE GUARD: 쓰는 것은 `V$SQL`, `V$SQL_PLAN`, `V$SQL_PLAN_STATISTICS_ALL`, `DBMS_XPLAN.DISPLAY_CURSOR`, `V$DIAG_ALERT_EXT`,
`V$DIAG_TRACE_FILE_CONTENTS`, `DBA_INDEX_USAGE`, `ALL_*`/`DBA_SEGMENTS`다. ASH·AWR(`V$ACTIVE_SESSION_HISTORY`, `DBA_HIST_*`), SQL Monitor, SQL Tuning·Access
Advisor는 쓰지 않는다(Diagnostics/Tuning Pack).

| 도구 | Oracle 구현 | 필요한 권한(없으면 도구가 이 GRANT를 알림) |
|---|---|---|
| `getActualPlan` | 첫 DML 키워드(WITH 쿼리는 첫 SELECT) 뒤에 `/*+ GATHER_PLAN_STATISTICS */`와 표지 주석을 넣어 실행하고 행을 끝까지 읽는다. 커서는 세션의 이전 문장이 아니라 표지로 `V$SQL`에서 찾는다(드라이버가 그 사이에 문장을 낼 수 있다). 사용자 힌트가 있으면 그 힌트 안에 더한다(Oracle은 키워드 뒤 첫 주석만 힌트로 읽음). `V$SQL_PLAN_STATISTICS_ALL`의 `last_*`(A-Rows는 모든 Starts의 합) → 공통 노드(`actualRows`, `executions`, `reads`, `actualTimeMs`), `DISPLAY_CURSOR('ALLSTATS LAST')` 글. 세션 설정(statistics_level)은 바꾸지 않는다. 읽기 문장은 읽기 전용 트랜잭션, 아니면 확인 뒤 롤백 | `SELECT_CATALOG_ROLE`(또는 `SELECT ANY DICTIONARY`). `DISPLAY_CURSOR`가 권한 부족을 오류가 아니라 계획 글로 내는 경우도 잡는다 |
| `getDeadlocks` | `V$DIAG_ALERT_EXT`의 ORA-00060 항목(30일) + 항목이 가리키는 추적 파일의 "DEADLOCK DETECTED" 절(교착 상태 그래프, 대기 세션과 SQL)을 `V$DIAG_TRACE_FILE_CONTENTS`에서 읽음. 한 추적 파일의 n번째 오래된 항목 = n번째 절 | `V$DIAG_ALERT_EXT`는 `SELECT_CATALOG_ROLE`로 읽을 수 없다(시험에서 ORA-00942): SYS로 `GRANT SELECT ON SYS.V_$DIAG_ALERT_EXT TO <user>`. 그래프는 `SELECT_CATALOG_ROLE` |
| `getRoutineStats` | `V$SQL`의 `PROGRAM_ID`(루틴 객체)·`PROGRAM_LINE#`(줄)로 문장별 실행 수·CPU·시간·읽기. 루틴 행은 그 문장들의 합(팩 없이 호출당 합계는 없음) | `SELECT_CATALOG_ROLE` |
| `getMissingIndexes` | 캐시된 계획(`V$SQL_PLAN`)에서 1만 행 넘는 테이블의 `TABLE ACCESS … FULL`과 그 필터 조건, `V$SQL` 읽기 수 순(Oracle 자체 인덱스 조언은 Tuning Pack) | `SELECT_CATALOG_ROLE` |
| `getUnusedIndexes` | 인덱스 사용 추적(`DBA_INDEX_USAGE`, 12.2+, 팩 없음)에 접근이 없는 비고유 인덱스, 캐시된 계획에 있는지(`in_cached_plan`), 크기. 중복·왼쪽 접두 인덱스(`ALL_IND_COLUMNS`) | `SELECT_CATALOG_ROLE`(`DBA_*`) |

- `getErrorLog`의 Oracle 필요 권한 글도 같은 GRANT로 고쳤다. `probePermissions`에 `alert_log` 검사(위 GRANT)를 더하고 Oracle V$ 검사에 위 도구들을 넣었다.
- 예상 계획(`explainQuery`)과 실제 계획이 같은 해석기(`PlanParser.parseOracle`)를 쓴다. 예상 계획에도 전체 테이블 스캔 소견과 hotspots가 생겼다.
- 시간대가 있는 시각(경고 로그의 `originating_timestamp`)이 `oracle.sql.TIMESTAMPTZ@…`로 나가던 것(1단계 `getErrorLog`도 같음)을 `AgentJson`에서 고쳤다.

### SQL Server Query Store

- 도구 3개(`getQueryStoreTopQueries`, `getQueryStoreRegressions`, `getQueryStoreForcedPlans`)는 진단 SQL(`diag/*.sql`)이다. 각 도구의 첫 문장이
  `sys.database_query_store_options`이고, 후처리가 그 행을 `queryStore` 객체로 바꾼다. **꺼져 있으면** `queryStoreOff: true`와 "Query Store is OFF in
  database …: it has recorded nothing … ALTER DATABASE … SET QUERY_STORE = ON …" 문장을 낸다. READ_ONLY(저장 한도 등)·ERROR 상태도 문장으로 알린다.
  master·tempdb처럼 옵션 행이 없는 DB는 "이 DB에는 Query Store가 없다, `database` 인수로 사용자 DB를 주라"고 알린다(시험에서 연결 기본 DB가 master였다).
- 회귀는 두 창(최근 `recentMinutes`, 그 앞 `historyMinutes`, 분 단위)의 평균 시간 비교다. 구간(interval)은 시작 시각으로 창을 정한다. `plan_changed` = 최근 창에
  앞 창에 없던 계획이 있음. 분 단위로 둔 것은 짧은 구간(1분)의 시험과 "방금 느려짐" 질문을 위해서다.
- 강제 계획은 읽기만 한다(강제·해제 도구는 없음). `plan_forcing_type_desc`(MANUAL/AUTO)는 SQL Server 2019부터 있다.
- 권한: `VIEW DATABASE STATE`(2022는 `VIEW DATABASE PERFORMANCE STATE`). `probePermissions`의 `view_database_state` 검사에 넣었다.

### whatIfIndex (PostgreSQL hypopg)

- 그 DB에 hypopg가 있으면(`pg_extension`): 예상 계획 → `hypopg_create_index(<CREATE INDEX>)` → 예상 계획 → `hypopg_drop_index`. 가상 인덱스는 백엔드
  메모리에만 있어(카탈로그·디스크 변경 없음, 다른 세션에 안 보임) READ이고 읽기 전용 트랜잭션에서 돈다. 결과: `before`, `after`, `diff`(`comparePlans`와 같은
  비교), `indexUsed`, 가상 인덱스 이름·예상 크기.
- 없으면 `available: false`와 이유: 서버에 설치되어 있지만 그 DB에 없음(`CREATE EXTENSION hypopg`는 사용자·DBA 몫, 에이전트는 확장을 만들지 않음) /
  서버에 설치되지 않음. PostgreSQL이 아닌 연결도 이유를 돌려준다.
- 공용 시험 DB(PostgreSQL 17)에는 hypopg가 설치되어 있지 않아(`pg_available_extensions`에 없음) **"없음" 경로만 시험했다**. 설치하지 않았다(브리프).

### PostgreSQL·MySQL 백업·복원 도구 (`NativeBackupTools`, 2026-11-08)

1단계에서 미룬 PostgreSQL·MySQL/MariaDB 백업(`dumpDatabase`)과 복원(`restoreDump`)이다. 이 두 도구로 MCP `tools/list`는 98개(READ 73, WRITE 20,
DANGEROUS 5)다. SQL Server의 `backupDatabase`·`restoreDatabase`(서버가 서버 경로의 파일을 씀)와 달리 **이 컴퓨터의 로컬 클라이언트 도구가 이 컴퓨터의
파일**을 읽고 쓰므로 도구를 나눴다. 쓰는 곳(`writes`)이 다르기 때문이다: 백업은 DB를 읽기만 하고 로컬 파일을 쓰므로 `writes="file"`(연결이 읽기 전용이어도
실행), 복원은 DB를 바꾸므로 `writes="database"`다. 채팅 요청에는 연결의 DB 종류에 맞는 쪽만 간다(`AgentTool.supports`).

- **분류**: `dumpDatabase` W(파일을 씀), `restoreDump` D. 둘 다 실행기(`AIToolExecutor`)를 거치므로 정책(운영 연결 막힘, 기본 확인, DANGEROUS는 전역 AUTO 불가),
  확인 대화상자, 감사 기록이 다른 도구와 같다.
- **같은 실행 경로**: Swing 백업·복원 대화상자의 모델 부분을 `model.admin`의 `NativeBackupRestore`로 옮겨 대화상자와 도구가 함께 쓴다
  ([swing-backup-restore.md](swing-backup-restore.md)). DBeaver 네이티브 도구 작업(`tasks.native`)의 설정 객체를 인수로 채우고 임시 작업에 저장한 뒤 작업 형식의
  처리기를 부른다. pg_restore 종료 코드 1(오류 있음, 로그인 실패도 같음)은 실패로 본다(대화상자와 같은 `CheckedPostgreRestore`).
- **로컬 클라이언트**: 연결의 로컬 클라이언트 설정(`clientHomeId`)만 쓴다. Swing에서는 연결 편집기의 연결 속성 &gt; 로컬 클라이언트(2026-11-08)나
  Object Explorer의 데이터베이스 &gt; Tasks &gt; Back Up… / Restore…의 로컬 클라이언트 페이지에서 고른다(대화상자는 실행할 때 연결에 저장). 설정이 없거나, 내려받는 클라이언트(`RemoteNativeClientLocation`)를 가리키거나,
  그 폴더가 없어졌거나, 도구가 그 폴더에 없으면 실행하지 않고 무엇을 하면 되는지와 이 컴퓨터에서 찾은 클라이언트 폴더를 알린다. 클라이언트를 내려받지 않는다.
  모델이 실행 파일 폴더를 인수로 고르게 하지 않았다(모델이 고른 실행 파일을 돌리는 길을 만들지 않으려는 것).
- **비밀**: 암호는 명령줄에 없다. 처리기가 PostgreSQL은 `PGPASSWORD`, MySQL은 `MYSQL_PWD` 환경 변수로 넘긴다(Swing 대화상자와 같음). 확인 요청의 미리 보기와
  결과의 `command`는 실제로 실행할 명령줄이고, 처리기 로그의 명령줄도 암호 인수를 가린다.
- **파일**: 절대 경로만 받는다. 백업은 있는 경로를 거부하고(덮어쓰지 않음, MySQL 처리기도 같은 규칙) 그 폴더가 있어야 한다. `${…}` 변수는 받지 않는다(확인한
  명령과 실행하는 명령이 같도록). 복원의 PostgreSQL 형식은 파일에서 알아낸다: `toc.dat`가 있는 폴더 = directory, 머리 `PGDMP` = custom, 257바이트의
  `ustar` = tar, 그 밖 = plain(psql). `format` 인수로 바꿀 수 있다.
- **진행**: 채팅에서는 도구가 1초마다 `AIToolCallContext.reportProgress`로 지난 시간, 백업 파일 크기(directory 형식은 폴더 합계), 도구 출력의 마지막 줄을
  알리고 도구 카드가 "실행 중: 12.3 MB 씀, 0:07 지남 - pg_dump: …"처럼 보인다(숫자가 앞: 좁은 도구 창은 줄 끝을 자르고, 도구 이름은 카드 제목에 있다)(`AIAgentChat.Entry.getProgress`, 저장하지 않음, `Listener.toolProgress`).
  모니터의 `subTask`를 쓰지 않은 것은 JDBC·처리기도 `subTask`를 불러 도구가 고르지 않은 글까지 카드에 나오기 때문이다. MCP는 호출마다 JSON 응답 하나라
  (SSE 없음) 진행을 보내지 않고 결과에 `durationMs`와 도구 출력 끝부분(`log`, 60줄, 스택 추적 줄 제외)을 넣는다.
- **취소**: 채팅의 중지, MCP `notifications/cancelled`가 모니터를 취소하면 처리기가 도구 프로세스를 끝낸다. 이 호출이 만든 백업 파일(호출 전에 없던 경로)은
  지운다. 실패한 백업의 파일은 대화상자처럼 지우지 않고 오류에 경로와 크기, "쓸 수 있는 백업이 아님"을 알린다.
- **psql 일반 스크립트 복원**: `stopOnError`가 꺼져 있으면 psql은 SQL 오류가 있어도 0으로 끝난다(대화상자의 기본값과 같이 끔). 결과의 `note`가 이를 알리고
  모델이 `log`의 ERROR 줄을 보게 한다. 켜면 `-v ON_ERROR_STOP=1`(종료 코드 3 = 실패), pg_restore는 `--exit-on-error`.
- **트랜잭션 안전**: 두 도구는 다른 프로세스(클라이언트 도구)가 따로 접속하므로 사용자 세션·에이전트 문맥의 트랜잭션 방식을 바꾸거나 끝내지 않는다. 다만
  `clean`으로 복원하면 사용자의 열린 트랜잭션이 잡은 잠금 때문에 pg_restore가 기다릴 수 있다(취소하면 끝남).
- **지원 범위**: 연결이 PostgreSQL 드라이버 계열이고 서버 종류가 네이티브 클라이언트를 지원하거나(`supportsNativeClient`), MySQL/MariaDB 드라이버이고 서버가
  네이티브 클라이언트를 지원할 때(`supportsNativeClients`)다. Swing 메뉴 조건과 같은 `NativeBackupRestore.isSupported`다. 그 밖의 DB는 `unsupported`.

**확인 (2026-11-08)**: 빌드 `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `python tools/rename/gate.py`
ALL GATES PASSED. 회귀 테스트는 요청되지 않아 실행하지 않았다. 빌드한 Swing 제품 하나를 작업 공간 사본과 `CHROMITE_DATA`(작업 폴더)로 띄우고 MCP(Python
클라이언트)와 채팅(가짜 Messages API, 앱 안에 붙인 시험 에이전트가 Swing 구성 요소를 직접 다룸, 화면 좌표·OS 입력 없음)으로 불렀다. 클라이언트는 이 PC의
D: 드라이브에 이미 풀어 둔 PostgreSQL 17.11(`pgsql-17`)·MariaDB 11.8.9(`mariadb-11.8`) 공식 zip이다(새로 설치한 것 없음). 시험 DB는 공용 컨테이너의
`chr_agent_backup`(PG: 주문 40만 행, 스키마 `extra`, 뷰 / MariaDB: 주문 20만 행, 프로시저)과 복원 대상 `chr_agent_backup_restore`였고 끝나고 지웠다.

| 시험 | 결과 |
|---|---|
| 목록 | MCP `tools/list` 98개. `dumpDatabase`(destructiveHint false), `restoreDump`(destructiveHint true), 한국어 제목 |
| 로컬 클라이언트 없음 | 연결에 설정이 없을 때 PG·MariaDB 모두 "has no Local Client setting … Tasks > Back Up... / Restore... … Local Client page …"와 찾은 설치(없음)를 알리고 실행 안 함 |
| 인수 오류 | 상대 경로 → INVALID, 없는 DB → INVALID(연결의 DB 목록), 있는 파일 → INVALID("never overwrites") |
| 확인 | 기본 정책(CONFIRM): "Chromite Agent 도구 실행 확인"에 실제 명령줄(`pg_dump.exe --verbose --host=… --username=postgres --format=c --file … chr_agent_backup`, 암호 없음), DB, 파일, 클라이언트 폴더. 거부 → DENIED, 파일 없음. 허용 → SUCCESS, 3.6 MB, `pg_restore --list`에 스키마·뷰·테이블 데이터 |
| PG 백업 | custom, plain + `schemas=extra`(다른 스키마 없음), directory(폴더 3.6 MB), plain + `inserts`(48 MB, 약 2분) |
| PG 복원 | custom(형식 자동 인식) → 40만/1,000/1행과 뷰. 같은 파일을 `clean` 없이 다시 → pg_restore 종료 코드 1 → ERROR와 마지막 출력("multiple primary keys …"). `clean` → 성공. directory + `clean` → 성공. plain(psql) → 성공 + `note`(ERROR 줄은 `log`에), `stopOnError` → 종료 코드 3으로 ERROR |
| MariaDB | 백업 17.8 MB(`--single-transaction --routines …`, `> 파일`), `tables=customers`·`routines=false` → customers만. 복원(`mysql … < 파일`) → 20만/1,000행, 프로시저 1개 |
| 정책 | 운영 연결 `dumpDatabase` BLOCKED, 읽기 전용 연결: `dumpDatabase` 실행(파일만 씀), `restoreDump` READ_ONLY. 연결별 도구 규칙 AUTO(DANGEROUS `restoreDump`도 도구별 규칙이라 허용)는 확인 없이 실행 |
| 취소 | MCP `notifications/cancelled`(4초) → CANCELED, 만들던 파일 지움, pg_dump 프로세스 없음. 채팅 중지(5초) → 카드 "취소됨", 파일 지움 |
| 진행 | 채팅 도구 카드: "실행 중: 10.0 MB 씀, 0:05 지남 - pg_dump: \"public.orders\" 테이블의 자료를 처리 중"이 1초마다 바뀜, 끝나면 "완료, 24.8초". 영어(`-nl en`) "running: 340.0 KB written, 0:05 elapsed - …". 도구 출력은 클라이언트의 언어(한국어 OS에서 한국어) |
| 감사·비밀 | 감사 기록에 위 호출이 분류·정책·결과(SUCCESS, ERROR, INVALID, DENIED, BLOCKED, READ_ONLY, CANCELED)로 남음. 작업 공간·데이터 폴더·결과·로그 83개 파일에서 두 시험 암호 0건 |
| Swing 대화상자(옮긴 뒤) | PG 백업 "완료", 같은 파일을 `clean` 없이 복원 → "실패" + pg_restore 종료 코드 1 안내(`NativeBackupRestore`를 거친 같은 결과) |

### 확인 (1b, 2026-11-08)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `python tools/rename/gate.py` ALL GATES PASSED.
회귀 테스트는 요청되지 않아 실행하지 않았다. 빌드한 Swing 제품 하나를 개인 작업 공간 사본과 `CHROMITE_DATA`(작업 폴더)로 띄우고, MCP(Python 클라이언트, 포트
47871)와 채팅(앱 안에 붙인 시험 에이전트가 `AIAgentChatRunner`를 부름, 가짜 Messages API)으로 불렀다. 확인 대화상자는 시험 에이전트가 Swing API로
눌렀다(화면 좌표·OS 입력 없음). 시험 객체: SQL Server DB `chr_agent_1b`(Query Store 1분 구간), `chr_agent_1b_qs_off`(꺼짐), 작업 `chr_agent_1b_job`(40초
대기 + 0으로 나누기 단계), Oracle 사용자 `CHR_AGENT_1B`(SELECT_CATALOG_ROLE + V_$DIAG_ALERT_EXT GRANT), `CHR_AGENT_1B_NOPRIV`(권한 없음). 끝나고 모두 지웠다.

| 시험 | 결과 |
|---|---|
| 에이전트 작업 읽기 | `listAgentJobs`: 서비스 RUNNING, 작업 1개. `getAgentJob`: 단계 2개(명령, "go to the next step"/"quit reporting failure"), 일정(사용 안 함). PostgreSQL 연결에서 부르면 "SQL Server 연결이 필요", 없는 작업은 INVALID |
| 작업 시작·중지(확인) | MCP `startAgentJob` → 앱에 "Chromite Agent 도구 실행 확인"(쓰기 배지, 실행할 SQL `EXEC msdb.dbo.sp_start_job @job_id = N'…'`, 이유 "기본값") → 허용 → 상태 "실행 중, 1 (wait)". `stopAgentJob` 허용 → "Job … stopped successfully.", 마지막 결과 취소됨. 실행 중이 아닐 때 `stopAgentJob` → "is not running … nothing to stop" |
| 사용 설정 | `setAgentJobEnabled false` 거부 → DENIED(바뀌지 않음), 허용 → `previouslyEnabled: true`, 목록에서 사용 안 함 |
| 기록 | 다시 시작해 40초 뒤 2단계 실패 → `getAgentJobHistory`(`failedOnly`): 실행 결과 "The job failed … last step to run was step 2 (fail)", 단계 2 메시지(0으로 나누기) |
| 운영 연결 | 같은 서버의 Production 종류 연결: 읽기 3개 성공, `startAgentJob` BLOCKED("production connection"). 읽기 뒤 앱을 닫을 때 트랜잭션 질문 없음(위 메타데이터 세션) |
| 감사 기록 | W 호출 7건이 `CONFIRM`/`BLOCKED`와 결과(SUCCESS 4, ERROR 1, DENIED 1, BLOCKED 1)로 남음. 비밀번호·가짜 키 문자열 0건 |
| Query Store | 상위 쿼리: `dbo.customer_total` 문장(계획 2개, 5,641회). 회귀: 인덱스를 지운 뒤 같은 쿼리 평균 2.84 → 11.03 ms(3.88배), 논리 읽기 1,392 → 5,906. 강제 계획: 지운 인덱스를 쓰던 계획을 강제 → `force_failure_count 1`, `NO_INDEX`. 꺼진 DB → `queryStoreOff` 문장, master → "Query Store가 없다" 문장, Oracle 연결 → 지원 안 함 |
| Oracle 실제 계획 | `SELECT COUNT(*) … status='OPEN' AND customer_id=7`: `TABLE ACCESS FULL` E-Rows 20 / A-Rows 6, Buffers 91, 소견 "TABLE ACCESS FULL … with filter", `DISPLAY_CURSOR` 표(Starts, E-Rows, A-Rows, A-Time, Buffers). WITH 쿼리(힌트가 CTE 안)도 모든 노드에 실행 통계 |
| Oracle 교착 상태 | 두 세션으로 ORA-00060을 만듦 → 경고 로그 항목(시각, 추적 파일 이름) + 그래프(TX 자원 2개, 세션 44·197, 각 현재 SQL) |
| Oracle 루틴·인덱스 | `getRoutineStats ORDER_REPORT`: 줄 5·6·7·9의 문장(7행은 루프 안 12회). `getMissingIndexes`: `ORDERS` 전체 스캔과 필터 `CUSTOMER_ID=:B1 AND STATUS='OPEN'`. `getUnusedIndexes`: 사용 없는 인덱스 3개, `IX_ORDERS_STATUS`가 `IX_ORDERS_STATUS_AMOUNT`의 왼쪽 접두 |
| Oracle 권한 없음 | `CHR_AGENT_1B_NOPRIV`: 실제 계획 "The statement ran, but … GRANT SELECT_CATALOG_ROLE …", 교착 상태 "… GRANT SELECT ON SYS.V_$DIAG_ALERT_EXT …", 누락·루틴 `requires`, `probePermissions`의 `alert_log`·`vdollar` 불가와 해당 도구 |
| whatIfIndex | PostgreSQL: "hypopg … not installed on this PostgreSQL server", SQL Server: "needs PostgreSQL with hypopg", `DROP TABLE`을 인덱스로 주면 INVALID |
| 채팅 | 가짜 Messages API로 SQL Server 대화(위 도구 포함 22개 호출)와 Oracle 대화(`getActualPlan`, `getDeadlocks`, `getRoutineStats`)가 정책·감사를 거쳐 실행됨 |

## 1b단계 (미룬 것)

| 항목 | 이유 |
|---|---|
| MySQL/MariaDB 누락 인덱스(`getMissingIndexes`) | 브리프(1b)에 없었다. performance_schema 다이제스트의 `SUM_NO_INDEX_USED`로 만들 수 있다 |
| hypopg가 있는 PostgreSQL에서 `whatIfIndex` 실행 확인 | 공용 시험 DB에 hypopg가 없고 설치하지 않는다(위 "whatIfIndex") |
| Query Store 계획 강제·해제, SQL Server Agent 작업 만들기·고치기 도구 | 브리프의 1b 범위 밖이다(Query Store는 읽기만, 작업은 시작·중지·사용 설정만). Swing 화면에는 작업 편집이 있다 |

2026-11-08에 SQL Server Agent 작업 도구, Oracle 진단 4개, Query Store, `whatIfIndex`를 만들었다(위 "1b단계 도구"). 같은 날 PostgreSQL·MySQL 백업·복원
도구도 만들었다(위 "PostgreSQL·MySQL 백업·복원 도구").

## 요청 크기 (후속 과제 #76, 2026-11-08)

채팅 요청마다 도구 정의 전체와 대화 기록 전체를 보내던 것을 줄였다. MCP 서버는 그대로다(`tools/list`는 연결이 정해지지 않아 늘 전체이고 기록은 MCP
클라이언트의 몫이다).

### 도구 고르기 (`AIAgentToolSelection`)

- 대화의 연결이 있으면 그 연결에서 쓸 수 없는 도구를 빼고 보낸다.
  - **DB 종류**: `AgentTool.supports(도구, DbFamily)`. 진단 SQL 도구는 SQL 자원에 그 DB 종류의 블록이 있는지(실행기와 같은 규칙), `AdminTools`는
    백업·복원 = SQL Server, `killSession` = 알려진 4종, `AgentJobTools` = SQL Server, `whatIfIndex` = PostgreSQL. 나머지(모델 계층·드라이버 계획기)는 모두.
    plugin.xml 속성으로 따로 적지 않은 것은 SQL 자원과 어긋나지 않게 하기 위해서다.
  - **정책**: 실행기와 같은 판정으로 막히는 도구(읽기 전용 연결의 DB 쓰기 도구, `AIToolPolicy.resolve`가 BLOCKED). 정적 분류로 판정한다. 호출별로
    분류가 올라가는 도구(`runSql` 등)는 정적 분류로 보내지고 실행기가 호출마다 다시 판정한다.
  - 연결 인수가 없는 도구(`listConnections`, `formatSql` 등)는 늘 보낸다. 대화에 연결이 없으면 모두 보낸다.
- 빠진 도구 ID는 시스템 프롬프트 끝에 알린다("Tools the user's policy blocks on this connection are not offered: …", "Tools for other database types are not
  offered: …"). 모델이 막힌 도구를 사용자에게 설명하거나 연결을 바꾸라고 할 수 있게 하기 위해서다.
- ~~**"도구를 모두 보냄"**: Options > Chromite Agent > Tool Policy 맨 위 확인란(`ai_policy_send_all_tools`, 기본 꺼짐). 번들 설정
  `chromite.ai.chat.sendAllTools`.~~ 2026-11-12 지움: 제공자별 도구 묶음의 "전체"가 같은 동작이고 저장값은 그리로 옮긴다(아래 "도구 묶음").
- 2026-11-12부터 이 거르기는 최소·핵심 묶음 안에서 한다(아래 "도구 묶음").

### 기록 줄이기 (`AIAgentChat.toModelMessages`)

- 예산: 기록 글자 수 60,000자(`HISTORY_BUDGET_CHARS`, JSON·영어 기준 약 15,000토큰). 글자 수는 요청에 실제로 실리는 것과 같게 센다: 글, 함수 이름과
  인수 JSON, 결과(성공한 결과는 `AIMessage`가 "<함수> was completed.\n"을 앞에 붙이므로 함께 셈. 처음에는 빠뜨려 예산을 400~900자 넘었다).
- 차례 = 사용자 글 하나와 다음 사용자 글 전까지. 최근 2차례(`KEEP_TURNS`, 지금 차례와 그 앞)는 그대로 둔다.
- 넘으면 이 순서로 맞춘다: ① 그 앞 차례들의 도구 결과(600자 넘는 것)를 오래된 것부터 앞 300자 미리 보기 + `originalChars` + "shortened … call the tool again"으로
  바꾼다 ② 그래도 넘으면 오래된 차례를 통째로 뺀다 ③ 그래도 넘으면(최근 차례 자체가 큼) 최근 차례의 오래된 결과를 줄이되 가장 최근 결과 4개는 둔다.
- 호출과 결과는 한 메시지(`AIMessage.functionCall`)이고 차례는 통째로 빠지므로 짝이 나뉘지 않고 기록은 늘 사용자 글로 시작한다. 저장된 대화와 화면은
  그대로이고 보내는 사본만 줄인다. 줄였으면 시스템 프롬프트 끝에 "N earlier turn(s) … left out and M earlier tool result(s) were shortened …"를 붙인다.

### 크기 (2026-11-08 측정)

가짜 Messages API가 받은 요청 본문을 쟀다(바이트, UTF-8 JSON. 토큰은 대략 바이트/4). "전"은 바꾸기 전 동작(도구 96개 전체 + 기록 전체)이다. 도구 정의는
"도구를 모두 보냄"으로 잰 값, 기록은 저장된 대화에서 엔진과 같은 모양으로 다시 만든 값이다(같은 지점의 실제 요청과 0.6% 안에서 맞음).

| 대상 | 전 | 후 |
|---|---|---|
| 도구 정의, SQL Server 연결 | 96개 52,589 B | 95개 51,866 B (-1%, `whatIfIndex`만 빠짐) |
| 도구 정의, Oracle 연결 | 96개 52,589 B | 82개 43,972 B (-16%) |
| 도구 정의, 운영 SQL Server 연결(쓰기 막힘) | 96개 52,589 B | 72개 38,005 B (-28%) |
| 도구 정의, 그 밖의 DB(Altibase) | 96개 | 54개 (진단 SQL 도구 42개 빠짐) |
| 6차례 SQL Server 대화(도구 22회)의 마지막 요청 | 약 161.5 KB(도구 52.6 + 시스템 1.5 + 기록 107.4) ≈ 40k 토큰 | 119.0 KB(51.9 + 1.6 + 65.5) ≈ 30k 토큰 (-26%) |
| 같은 대화를 12차례(도구 44회)로 늘린 마지막 요청 | 약 339.3 KB(기록 285.2 KB) ≈ 85k 토큰 | 119.0 KB ≈ 30k 토큰 (-65%) |
| 기록 글자 수, 12차례 | 251,818자 | 56,975자(예산 60,000) |

- 12차례 대화의 56개 요청에서 기록 글자 수는 최대 59,739자로 예산 안이었고, 모든 요청에서 `tool_use`/`tool_result` 짝 오류 0건, 기록 첫 메시지는 늘 사용자 글.
- 도구 정의(약 52 KB)가 이제 요청에서 가장 큰 고정 몫이다. SQL Server 연결에서는 고르기로 거의 줄지 않는다(아래 "위험과 남은 일").

## 도구 선택 점검 (앱 변경 #61, 2026-11-12)

사용자 질문: 도구가 많으면 모델이 도구를 잘못 고를 수 있으니 점검해 보자. 결정(2026-11-12): **정적 점검부터.** 실제 모델 호출은 하지 않았다.
평가용 질문 60개와 도구 목록은 [agent-tool-eval.md](agent-tool-eval.md)에 있다.

### 사실

- 도구 101개(`plugin.xml`의 `<function>` 101개): READ 75, WRITE 21, DANGEROUS 5. 분야: 상태·관리 25, 메타데이터 15, 연결 13, 쿼리 11, 성능 11, DDL 8,
  데이터 7, 쿼리 라이브러리 4, 편집기 4, 전송 3.
- 채팅은 `AIAgentToolSelection.select`가 대화 연결의 DB 종류에 안 맞는 도구와 정책으로 막힌 도구만 뺀다. 질문 내용으로는 고르지 않는다. SQL Server 연결은
  `whatIfIndex`(PG)와 `dumpDatabase`·`restoreDump`(PG·MySQL)만 빠진다. MCP `tools/list`는 늘 전체다.
- 모델이 도구를 고르는 근거는 도구 ID, 설명, 인수 설명뿐이다(시스템 프롬프트 `AIAgentPlaybooks.INSTRUCTIONS`는 몇 개 도구만 이름으로 든다).

### 헷갈리는 묶음

| # | 묶음 | 도구 | 헷갈리는 점 (근거) |
|---|---|---|---|
| 1 | SQL 실행 | `runSelect`, `runReadOnlySql`, `runSql`, `runScript`, `sampleRows` | `runSelect`와 `runReadOnlySql`은 둘 다 같은 읽기 판정(`QueryTools.requireReadOnly`)을 쓴다. 차이는 결과 모양뿐이다: 한 페이지 + `nextOffset`(`AgentSql.Runner.queryPage`) 대 모든 결과 집합·메시지(`Runner.execute`). 옛 설명은 이 차이를 "diagnostic"이라는 말로만 보였다 |
| 2 | 실행 계획 | `explainQuery`, `getActualPlan`, `comparePlans`, `getCachedPlans`, `whatIfIndex`, `validateSql` | 모두 "plan"을 말한다. 추정(실행 안 함)·실측(실행, 쓰기면 롤백)·두 문장 비교·서버 캐시의 계획·가상 인덱스로 갈리는데 옛 설명에 언제 어느 것인지 없었다 |
| 3 | "권한"의 세 뜻 | `probePermissions`, `listPermissions`, `listPrincipals`, `getToolPolicy` | 진단에 필요한 로그인 권한, DB의 GRANT, DB 사용자·역할, Chromite의 도구 정책이 모두 "permission"이다. 가장 잘못 고르기 쉬운 묶음이다 |
| 4 | 연결 확인 | `connect`, `testConnection`, `getConnectionInfo`, `getServerInfo`, `getCurrentDatabase`, `listDatabases` | `connect`는 `getConnectionInfo`의 결과 + `connectMs`(`ConnectionTools.connect`), `testConnection`은 연결 + 왕복 시간이다. 셋 다 "연결 정보"로 읽힌다 |
| 5 | 표 구조 | `describeTable`, `listIndexes`, `listForeignKeys`, `getObjectDdl`, `getRoutineSource`, `generateDdl` | `describeTable`은 인덱스와 외래 키를 이미 담는다(`MetadataTools.describeTable`, 216–223행). `getObjectDdl`(있는 객체의 DDL)과 `generateDdl`(바꿀 DDL 만들기)은 이름이 비슷하다 |
| 6 | 인덱스 | `listIndexes`, `getIndexUsage`, `getUnusedIndexes`, `getMissingIndexes`, `getIndexFragmentation`, `whatIfIndex`, `createIndex`, `dropIndex` | 정의·사용량·안 쓰는 것·없는 것·조각화가 모두 "index"다 |
| 7 | 느린 쿼리의 출처 | `getTopQueries`, `getQueryStoreTopQueries`, `getQueryStoreRegressions`, `getLongRunningQueries`, `getRoutineStats`, `getQueryHistory` | 누적 통계·Query Store·지금 실행 중·루틴 안·사용자가 앱에서 실행한 기록. `getQueryHistory`는 서버가 아니라 앱의 기록이다 |
| 8 | 세션·잠금·트랜잭션 | `listSessions`, `getBlockingChain`, `listLocks`, `getDeadlocks`, `listOpenTransactions`, `getTransactionStatus`, `killSession`, `cancelQuery`, `disconnect` | 지금의 블로킹과 지난 교착 상태, 서버 전체의 열린 트랜잭션과 에이전트 자신의 트랜잭션, 남의 세션 끝내기와 에이전트 문장 취소와 연결 닫기 |
| 9 | 크기·행 수 | `getDatabaseSizes`, `getSpaceUsage`, `getTableStatistics`, `countRows` | 서버 전체 DB 크기, 한 DB 안의 파일·공간, 표별 추정 행 수·크기, 정확한 행 수 |
| 10 | 백업·복원 | `backupDatabase`·`restoreDatabase`(SQL Server, 서버의 파일), `dumpDatabase`·`restoreDump`(PG·MySQL, 이 PC의 파일), `getBackupHistory`, `exportData` | 이름만으로는 DB 종류와 파일 위치가 갈리는 것을 알 수 없다. 채팅은 DB 종류로 한쪽만 보내지만(`AdminTools.supports`, `NativeBackupTools.supports`) MCP는 모두 보낸다 |
| 11 | 작업 | `getJobHistory`, `getAgentJobHistory`, `listAgentJobs`, `getAgentJob` | SQL Server에서 `getJobHistory`와 `getAgentJobHistory`는 같은 `msdb.dbo.sysjobhistory`를 읽는다(`diag/getJobHistory.sql`). `failedOnly` 기본값이 true와 false로 다르다 |
| 12 | 행 쓰기 | `insertRows`, `importCsv`, `copyTable`, `updateRows`, `deleteRows`, `runSql` | 키 기반 편집(도구)과 조건 기반 변경(`runSql`)의 경계가 설명에 없었다 |
| 13 | 비교 | `comparePlans`, `compareQueryResults` | 계획 비교와 결과 행 비교 |
| 14 | 라이브러리·기록 | `listLibraryQueries`, `getLibraryQuery`, `runLibraryQuery`, `runMonitoringChecks`, `getQueryHistory` | 저장한 쿼리와 실행 기록, 쿼리 하나와 폴더 전체 점검 |
| 15 | 편집기 | `openInEditor`, `getQueryWindowText`, `editQueryWindow`, `listQueryWindows` | 새 창에 열기와 열린 창 고치기 |

설명의 다른 문제(고치기 전): 언제 쓰고 언제 다른 도구를 쓸지 없는 설명이 대부분이었고(`listDatabases`, `listTables`, `cancelQuery`, `formatSql`, `dropView` 등은
한 줄짜리), 결과 모양이 없는 설명(`listSchemas`, `getDatabaseSizes`, `getLibraryQuery` 등), DB 지원을 적지 않은 설명(`getWaitStats`, `getMissingIndexes`의 MySQL 없음,
`getBackupHistory`의 MySQL 없음, `getIndexFragmentation`의 Oracle 없음)이 있었다.

### 고친 것

- **도구 설명 99개**를 같은 모양으로 고쳤다(101개 모두 보았고, `getQueryStoreTopQueries`·`getQueryStoreForcedPlans`는 이미 그 모양이라 그대로 둠):
  하는 일과 결과 모양, 언제 쓰는지, 이웃한 경우에 대신 쓸 도구 이름, 쓰기 도구는 확인 방식, DB 지원.
  `plugin.xml` 머리 주석에 이 모양을 지키라고 적었다(새 도구를 더하면 이웃 도구의 "대신" 안내에도 넣는다).
- **인수 설명 14개**: `getQueryHistory.connection`(채팅에서는 채팅 연결로 거른다), `listLocks.session`, `backupDatabase.file`·`restoreDatabase.file`(서버의 경로),
  `exportData.file`, `getJobHistory.failedOnly`(기본값 차이), `sampleRows.orderBy`(SQL 조각), `listPermissions.object`·`principal`, `getQueryWindowText.part`,
  `editQueryWindow.mode`, `runSql.sql`, `listTables.schema`, `getRoutineStats.name`.
- **선택값 2개**: 설명에만 있던 열거값을 `validValues`로 적었다(`getQueryWindowText.part` = all/selection/statement, `editQueryWindow.mode` =
  insert/replaceSelection/replaceAll). 엔진이 JSON 스키마의 `enum`으로 보낸다. 실행기는 전처럼 대소문자·구분자를 가리지 않고 받는다(`EditorTools.normalize`).
- 도구 ID, 인수 이름, 기본값, 분류(`access`), 도구 수는 바꾸지 않았다. 저장된 도구별 정책, 감사 기록, MCP 클라이언트가 ID에 묶여 있기 때문이다.
- **크기**: 도구 설명 글은 16,128 B → 27,119 B, 인수 설명은 10,868 B → 11,207 B(UTF-8). 요청마다 약 11 KB(≈2.8k 토큰) 늘었다. 설명이 정확한 쪽을
  골랐고, 크기는 아래 제안으로 줄인다.

### 확인 (2026-11-12)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS(10:30).
- 빌드한 앱 사본(빈 작업 공간·설정·데이터·키링, 가짜 Anthropic 서버 127.0.0.1만)에서: 채팅 도구 101개·MCP 도구 101개(`AIToolCatalog.getTools`).
  연결 없는 대화 두 차례(`listConnections`, `getToolPolicy` 호출)가 가짜 서버에 닿았고, 요청마다 도구 101개, 새 설명, `editQueryWindow.mode`의 `enum`이 실렸다.
  `tool_use`/`tool_result` 짝 오류 0건. 도구 정의는 69,400 B(위 "크기" 표와 같은 셈법인 들여쓰기 없는 `json.dumps`. 2026-11-08에는 도구 96개 52,589 B),
  첫 요청 본문 68,378 B. MCP `tools/list`는 101개이고 `getQueryWindowText.part`의 `enum`과 새 설명을 돌려준다.
- 실제 모델로는 재지 않았다(평가 세트는 사용자가 실행).

### 사용자가 정할 것: 이름 바꾸기·합치기·빼기 (하지 않음)

ID나 인수를 바꾸면 저장된 정책(`AIToolPolicy`의 도구별 규칙), 감사 기록 검색, MCP 클라이언트 설정이 깨진다. 바꾸려면 옛 ID를 별칭으로 한 판 동안 받아야 한다.

| 제안 | 대상 | 이유 | 비용 |
|---|---|---|---|
| 합치기 | `listIndexes`, `listForeignKeys` → `describeTable` | `describeTable`이 둘을 포함한다 | 정책·감사의 두 ID |
| 합치기 | `connect` + `testConnection` → 하나 | 결과만 조금 다르다 | 작음 |
| 합치기 | `runSelect` + `runReadOnlySql` → 읽기 실행 하나(페이지 인수 + 여러 결과 집합) | 같은 판정, 다른 결과 모양 | 플레이북·지시문 수정 |
| 합치기 | DDL 도구 7개 + `generateDdl` → `changeSchema(operation, preview)` 하나 | 8개가 한 묶음의 인수를 나눠 쓴다(`DdlTools`가 이미 한 클래스) | 정책을 작업별로 나눌 수 없게 됨(`dropTable`만 DANGEROUS) |
| 합치기 | `backupDatabase`+`dumpDatabase` → `backupDatabase`, `restoreDatabase`+`restoreDump` → `restoreDatabase`(DB 종류로 나눔) | 사용자에게는 같은 일이다 | 인수가 DB 종류마다 달라 설명이 길어짐 |
| 합치기 | SQL Server 부분의 `getJobHistory` → `getAgentJobHistory` | 같은 표를 읽는다 | 다른 DB의 `getJobHistory`는 남김 |
| 합치기 | `getQueryStoreTopQueries` → `getTopQueries(source=queryStore)` | 같은 질문의 다른 출처 | 작음 |
| 이름 | `listPermissions` → `listGrants`, `probePermissions` → `checkDiagnosticAccess`, `getToolPolicy` → `getAgentToolPolicy` | 묶음 3의 "permission" 세 뜻을 이름으로 가름 | 별칭 필요 |
| 이름 | `generateDdl` → `previewDdl` | `getObjectDdl`과 구분 | 별칭 필요 |
| 이름 | `getQueryHistory` → `getEditorHistory` | 서버 통계로 오해함 | 별칭 필요 |
| 인수 | 행 수 상한 `limit`·`top`·`maxRows` → `limit` | 같은 뜻에 세 이름(`getWaitStats`·`getTopQueries`·`getCachedPlans`·Query Store는 `top`, 전송·비교는 `maxRows`) | 정책과 무관, MCP 클라이언트만 |
| 인수 | 이름 거르기 `pattern`·`contains`·`filter` → 하나 | 목록 도구마다 다름 | 같음 |
| 인수 | 대상 객체 `table`·`object`·`name` | `getRoutineSource`·`getRoutineStats`는 `name`, `getObjectDdl`·`getObjectDependencies`·`listPermissions`는 `object` | 같음 |
| 인수 | 한 이름 두 뜻: `format`(결과 모양 / 파일 형식 / 덤프 형식), `type`(열 형식 / 백업 종류), `orderBy`(SQL 조각 / 열거값) | 다른 도구에서 본 값을 넣기 쉽다 | 같음 |
| 인수 | `session`의 형식: `listLocks`는 integer, `killSession`은 string(Oracle `sid,serial#`) | 같은 값을 다른 형식으로 | 같음 |
| 분류 | `dropIndex`는 WRITE, `dropTable`·`dropView`는 DANGEROUS | DROP의 분류가 다르다(`runSql`은 모든 DROP을 DANGEROUS로 본다) | 사용자 정책이 바뀜 |
| 빼기 | `connect`, `formatSql` | 다른 도구가 연결하고, 모델이 직접 정리할 수 있다 | 정책·감사의 ID |

드물게 쓰는 도구(아래 "도구 찾기" 뒤로 보낼 후보): `whatIfIndex`, `getQueryStoreForcedPlans`, `getQueryStoreRegressions`, `listSequences`, `listTriggers`,
`getAgentJob`, `startAgentJob`, `stopAgentJob`, `setAgentJobEnabled`, `backupDatabase`, `restoreDatabase`, `dumpDatabase`, `restoreDump`, `copyTable`, `importCsv`,
`compareQueryResults`, `profileColumns`, `getIndexFragmentation`, `getObjectDependencies`, `formatSql`, `connect`, `disconnect`, `testConnection`, `setCurrentDatabase`,
`getCurrentDatabase`, `cancelQuery`, `getToolPolicy`, `getQueryHistory`, DDL 도구 8개, `insertRows`·`updateRows`·`deleteRows`, `getLibraryQuery`, `runLibraryQuery`,
`listQueryWindows`, `editQueryWindow`.

### 요청마다 보내는 도구 줄이기 제안 (문서만, 구현 안 함)

지금 SQL Server 연결은 요청마다 도구 98개를 보낸다. 줄이는 방법 넷을 견주었다. 크기 비율은 `plugin.xml`의 설명·인수 글자로 어림했다(READ 72%, WRITE·DANGEROUS 28%).

| 방식 | 하는 일 | 좋은 점 | 나쁜 점 |
|---|---|---|---|
| A. 두 단계 고르기 | 첫 요청은 분야(연결, 메타데이터, 쿼리, ...) 목록만 보내고 모델이 분야를 고르면 그 분야 도구를 보냄 | 요청이 가장 작다 | 매 차례 왕복 하나가 늘고, 분야를 틀리면 그 차례 전체가 틀린다. 튜닝처럼 여러 분야(쿼리·성능·메타데이터)를 넘나드는 질문이 많다 |
| B. 핵심 묶음 + 도구 찾기 | 자주 쓰는 도구(약 43개)는 늘 보내고, 나머지는 메타 도구 `findTools(query)`가 이름·설명을 찾아 돌려준 뒤 그 대화의 다음 요청부터 함께 보냄 | 흔한 질문은 지금과 같고 왕복이 늘지 않는다. 드문 일만 한 번 더 부른다. 언어와 상관없다 | 핵심 묶음을 정하고 관리해야 한다. 찾은 도구가 생기면 도구 목록이 바뀌어 제공자의 프롬프트 캐시가 한 번 깨진다 |
| C. 쓰기 도구는 쓰기 차례에만 | 사용자 글이 변경을 요청할 때만 WRITE·DANGEROUS 도구를 보냄 | 약 28% 줄고, 묻기만 한 차례에 쓰기 도구를 부를 수 없어 안전하다 | "요청이 변경인지"를 판단해야 한다. 글 규칙은 한국어·영어 표현을 다 덮지 못하고, 모델에게 묻으면 왕복이 는다. 놓치면 모델이 "할 수 없다"고 답한다 |
| D. 플레이북별 묶음 | 플레이북(튜닝, 상태 점검, 블로킹 등)으로 시작한 대화는 그 플레이북 도구만 보냄 | 플레이북 안에서는 가장 정확하다 | 채팅의 자유 질문 대부분은 플레이북으로 시작하지 않는다. 범위를 벗어나면 막힌다 |

**권하는 것: B(핵심 묶음 + 도구 찾기).** 흔한 질문의 정확도와 왕복을 지키면서 정의 크기를 절반 넘게 줄인다(핵심 43개 ≈ 정의 글자의 42%).
쓰기 도구 대부분(DDL 8개, 행 편집 3개, 전송, 백업, 작업)이 찾기 뒤로 가므로 C의 안전 효과도 상당 부분 얻는다(`runSql`, `runScript`,
`commitTransaction`, `rollbackTransaction`은 핵심에 둠). 필요한 것:

1. 핵심 묶음 정의: 도구의 `plugin.xml` 속성(예: `core="true"`) 하나. 후보는 [agent-tool-eval.md](agent-tool-eval.md)의 허용 첫 도구에 자주 나오는 것.
2. 메타 도구 `findTools`(READ, 연결 인수 없음): 질문 글로 ID·설명·분야를 찾아 상위 몇 개의 이름과 한 줄 설명을 돌려준다. 정책·DB 종류로 빠진 도구는 찾지 않는다.
3. `AIAgentToolSelection`이 대화마다 "찾은 도구" 집합을 기억해 다음 요청에 더한다(`AIAgentChat`에 저장, 대화를 다시 열면 이어짐).
4. 시스템 프롬프트에 한 줄: 핵심에 없는 일은 `findTools`로 찾는다.
5. MCP는 그대로 전체를 보낸다(클라이언트가 고르는 몫). "도구를 모두 보냄"은 지금처럼 고르기를 끈다.
6. 바꾸기 전과 후를 [agent-tool-eval.md](agent-tool-eval.md)로 실제 모델에서 잰다(사용자가 실행). 첫 도구 정확도가 떨어지지 않아야 한다.

일부 제공자는 자체 "도구 검색"(도구 정의를 나중에 싣는 기능)을 제공하지만, Chromite는 Anthropic·OpenAI 호환·Ollama를 함께 지원하므로 앱 쪽에서 하는 B가 맞다.
제공자 기능은 이번에 확인하지 않았다.

**구현(2026-11-12, 앱 변경 #64)**: B를 세 단계 묶음으로 구현했다. 아래 "도구 묶음".

## 도구 묶음 (앱 변경 #64, 2026-11-12)

사용자(2026-11-12): "43개도 많지 않나? 가장 걱정되는 건 vllm이다.. 이걸 받을 수 있을지 모르겠다". 결정: 세 단계 도구 묶음으로 진행. 회사 vLLM의
모델·컨텍스트·도구 호출 옵션은 아직 모르므로 가장 보수적인 조건(컨텍스트 8k, 도구 호출 불확실)을 기준으로 했다. 합치기·이름 바꾸기·인수 통일
(위 "사용자가 정할 것")은 이번 범위가 아니다.

### 묶음

| 묶음 | 도구 | 설명 | 연결로 거르기 |
|---|---|---|---|
| 최소 | 13개: `listConnections`, `listTables`, `searchObjects`, `describeTable`, `runReadOnlySql`, `explainQuery`, `runSql`, `getTransactionStatus`, `commitTransaction`, `rollbackTransaction`, `getQueryWindowText`, `openInEditor`, `findTools` | 짧은 판(`shortDescription`, 도구·인수마다 한두 문장) | 함 |
| 핵심 | 44개: 최소 + `getConnectionInfo`, `getServerInfo`, `probePermissions`, `listDatabases`, `listSchemas`, `listRoutines`, `getRoutineSource`, `getObjectDdl`, `runSelect`, `runScript`, `getActualPlan`, `comparePlans`, `validateSql`, `sampleRows`, `countRows`, `listSessions`, `getBlockingChain`, `listLocks`, `getDeadlocks`, `listOpenTransactions`, `getLongRunningQueries`, `getWaitStats`, `getTopQueries`, `getDatabaseSizes`, `getSpaceUsage`, `getBackupHistory`, `getServerConfig`, `getTableStatistics`, `getIndexUsage`, `getMissingIndexes`, `getUnusedIndexes` | 긴 판 | 함 |
| 전체 | 101개(`findTools` 없음: 밖에 찾을 도구가 없다) | 긴 판 | 안 함(예전 "도구를 모두 보냄"과 같음) |

- 구성원은 `plugin.xml`의 `toolSet="minimal|core"` 속성이다(`AIFunctionDescriptor.getToolSet`, 확장점 스키마에 더함). 최소 ⊂ 핵심 ⊂ 전체. 코드 목록으로
  두지 않은 것은 도구를 더할 때 한 곳만 고치게 하기 위해서다. 새 도구는 속성 없이 두면 `findTools`로 찾힌다(`plugin.xml` 머리 주석).
- **최소 묶음을 행의 설계와 맞춰 본 것**: 행의 "listTables/findObjects"는 실제 ID로 `listTables`(한 스키마 둘러보기)와 `searchObjects`(이름·열로 모든
  스키마에서 찾기) 둘이다. 하나로는 평가 질문 1(어떤 표가 있나)과 3(어느 표에 열이 있나)을 함께 덮지 못해 둘 다 넣었다. `commitTransaction`·
  `rollbackTransaction`을 더했다: `runSql`은 수동 커밋 연결에서 트랜잭션을 열어 둔 채 끝나는데(`runSql` 설명), 끝낼 도구가 없으면 작은 모델이 "커밋해 줘"
  (평가 46)를 `findTools` 없이는 할 수 없다. 나머지(`listConnections`, `describeTable`, `runReadOnlySql`, `explainQuery`, `runSql`, `getTransactionStatus`,
  `getQueryWindowText`, `openInEditor`, `findTools`)는 행 그대로다. 그래서 행의 "약 10개"보다 3개 많은 13개다.
- **핵심 묶음**: #61의 "드물게 쓰는 도구"를 빼고, 평가 세트의 허용 첫 도구와 공용 지시문(`AIAgentPlaybooks.INSTRUCTIONS`)이 이름으로 부르는 도구를 남겼다.
  권한 3종(`listPermissions`, `listPrincipals`, `getToolPolicy`), 작업·백업 실행, DDL, 행 편집, 전송, Query Library, Query Store, `getErrorLog`·`getJobHistory`는
  `findTools`로 찾는다. 질문별로 필요한 묶음은 [agent-tool-eval.md](agent-tool-eval.md) "도구 묶음별 필요".

### findTools (`FindTools`, READ, 채팅만)

- 인수: `query`(영어 낱말), `area`(connections, metadata, query, data, ddl, transfer, health, performance, library, editor), `connection`, `limit`(기본 8, 최대 20).
  분야 `agent.meta`("Agent")를 새로 두었다.
- 찾는 범위: 지금 요청에 없는 채팅 도구. 연결이 있으면 요청 고르기와 같은 거르기(`AIAgentToolSelection.select`: DB 종류, 정책)로 쓸 수 없는 도구는
  찾지 않는다. 지금 요청의 도구는 실행기가 도는 같은 스레드에서 읽는다(`AIAgentToolSets.getOffered`).
- 점수: ID 낱말(camelCase로 나눔)과 같으면 3, ID에 들어 있으면 2, 설명·분야에 있으면 1. 끝의 s/es를 떼고 비교한다. 결과는 `tools[{id, area, access,
  description(첫 문장)}]`과 "다음 단계부터 쓸 수 있다"는 안내. 맞는 것이 없으면 분야별 도구 ID 목록(`areas`)을 돌려 다시 찾게 한다.
- 결과의 도구는 그 대화의 "찾은 도구"(`AIAgentChat.foundTools`, 대화 파일에 저장, 예전 대화는 없음으로 읽힘)에 더해져 다음 요청부터 함께 간다(최소
  묶음이어도 긴 설명으로). 모델이 제공되지 않은 도구를 이름으로 부르면(플레이북·`areas` 목록을 보고) 실행기가 평소처럼 정책을 적용해 실행하고 그 도구도
  찾은 도구에 더한다. 사용자 글이 도구 ID를 그대로 쓰면(플레이북 글) 그 도구들도 차례 시작에 더한다. 플레이북이 최소·핵심 묶음에서도 동작하게 하기 위해서다.
- 정책·확인·트랜잭션 안전·감사는 모두 실행기(`AIToolExecutor`)에 있으므로 이렇게 들어온 도구에도 그대로 적용된다. `findTools` 자신도 감사 기록에 남는다.
- MCP `tools/list`에는 없다(`purpose="TOOL"`). MCP 클라이언트는 지금처럼 전체 101개를 받는다.
- 시스템 프롬프트: 핵심 묶음은 공용 지시문 + "Only part of the tools are offered. If none fits the task, call findTools …" 한 줄. 최소 묶음은 공용 지시문
  (최소 묶음에 없는 `getServerInfo`·`probePermissions`·`getBlockingChain` 등을 이름으로 부름) 대신 짧은 지시문: 표는 `listTables`/`searchObjects`/
  `describeTable`, 데이터와 진단은 시스템 뷰 SQL을 직접 써서 `runReadOnlySql`, 느린 쿼리는 `explainQuery`, 없는 도구는 `findTools`. 전체 묶음은 공용 지시문만.

### 설정: 제공자마다 채팅 도구 묶음

- Options > Chromite Agent > 제공자의 각 양식에 "채팅 도구 묶음"(자동 / 최소(도구 N개, 짧은 설명) / 핵심(도구 N개) / 전체(도구 N개), 개수는 카탈로그에서
  셈)과, Ollama·vLLM은 "컨텍스트 크기(토큰)"를 더했다. 둘 다 값이라 저장 표준의 [저장]/[되돌리기]를 따른다(바꾸면 바뀐 수가 늘고 [저장]이 켜짐,
  [swing-options.md](swing-options.md) "옵션 화면 모양과 저장 (#59)"). 양식의 기존 행 도우미(`ProviderForm.addRow`, 제목 테두리 양식이라 `OptionsForm.addRow`
  카드는 쓰지 않음)로 모델 행 아래에 둔다. 번들 설정 `chromite.ai.chat.toolSet.<엔진>`(`com.seizia.chromite.model.ai.tools.prefs`).
- 기본값: vLLM·Ollama = 최소, Anthropic Claude·OpenAI·Google Gemini = 핵심(`AIAgentToolSets.getDefaultToolSet`).
- 자동: 도구 정의가 컨텍스트의 25%(`AUTOMATIC_SHARE_PERCENT`)를 넘지 않는 가장 큰 묶음(전체 → 핵심 → 최소, 최소보다 아래 없음). 연결로 거른 뒤의
  정의 크기로 판단한다.
- 컨텍스트 크기(`AIAgentProviders.getContextTokens`): 제공자 설정의 값. Ollama는 원래 있던 값(`num_ctx`로 서버에 보냄, 기본 30,000), vLLM은 새 설정
  `chat.contextWindowSize`(`ChatCompletionsProperties`, 비우면 8,192로 봄 — 회사 서버의 가장 작은 경우), Anthropic은 원래 값(기본 200,000), OpenAI·Gemini는
  값이 없으면 128,000. vLLM의 Test는 모델 목록(`GET /v1/models`)의 `max_model_len`이 있으면 빈 칸을 그 값으로 채운다.
- **"도구를 모두 보냄"을 지움**: Tool Policy의 확인란(`ai_policy_send_all_tools`)과 설정 `chromite.ai.chat.sendAllTools`를 지웠다. 전체 묶음이 같은 동작이다.
  저장된 값이 켜짐이면 처음 읽을 때 모든 제공자의 묶음 설정(직접 정한 것이 없을 때)을 "전체"로 옮기고 옛 키를 지운다(`AIAgentToolSets.getStore`).

### 보내기 전 크기 확인

- 요청마다 `도구 정의 + 시스템 프롬프트 + 답할 몫(컨텍스트의 1/8, 최대 4,096토큰)`을 컨텍스트에서 빼고, 남은 토큰에 맞게 기록을 줄인다(위 "기록 줄이기"의
  글자 예산을 그 토큰에 맞춰 좁힘. 한국어는 글자당 토큰이 많아 어림한 토큰이 넘으면 예산을 비율로 다시 좁혀 최대 3번).
- 그래도 넘으면(남겨 두는 최근 2차례만으로도 큼) 요청하지 않고 대화에 오류 항목으로 알린다(`AIToolsMessages.chat_request_too_large`, 화면 언어): 컨텍스트,
  도구 정의, 대화, 지시·답 몫의 토큰과 할 일(새 대화, 글 줄이기, 최소 묶음, 컨텍스트 크기 확인). 전에는 서버의 문맥 초과 오류로만 드러났다.
- 토큰 어림(`AIAgentToolSets.estimateTokens`): ASCII 4글자 = 1토큰, 그 밖의 글자 1글자 = 1토큰. 토크나이저는 모델마다 달라 요청 전에 알 수 없다.
  한국어를 넉넉히 세어 작은 컨텍스트를 넘기지 않는 쪽을 골랐다.

### 도구 없음 방식

- vLLM을 도구 호출 옵션(`--enable-auto-tool-choice --tool-call-parser`) 없이 띄우면 도구가 실린 요청을 400으로 거부한다(위 "Chat Completions 엔진").
  이것을 Test(`checkToolCalls`)나 채팅 요청의 400(`ChatCompletionsClient.ToolCallsUnsupportedException`)이 알아내면 그 제공자·모델을 기억한다
  (`chromite.ai.chat.toolsOff.<엔진>`, 모델 이름 목록). 채팅은 400을 받은 요청을 도구 없이 바로 다시 보내므로 사용자는 답을 받는다.
- 그 뒤 요청은 도구 정의를 싣지 않고, 기록의 도구 호출·결과도 빼며(도구 호출을 모르는 서버가 거부할 수 있음, 화면과 저장된 대화는 그대로), 시스템
  프롬프트는 "도구를 부를 수 없다, SQL은 ```sql 블록으로 제안하면 사용자가 쿼리 창에서 열어 실행한다"고 한다. 사용자는 지금 있는 [쿼리 창에서 열기]를 쓴다.
- 채팅 창 입력란 위에 늘 보이는 경고 글(`ai_tools_off_notice`): 이 모델은 도구 없이 동작하고, 서버에서 도구 호출을 켠 뒤에는 Test를 누르라고 한다.
  Test가 도구 호출이 되는 것을 확인하면 그 기억을 지운다. 대화의 모델이 바뀌면 그 모델 기준으로 다시 정한다.
- Ollama의 "does not support tools" 오류는 Ollama 엔진이 따로 처리하므로 이번에 알아보지 않는다(아래 "위험과 남은 일").

### 크기 (2026-11-12 측정)

가짜 서버가 받은 요청에서 쟀다. 도구 정의 = `tools` 배열을 Messages API 모양의 빈칸 없는 JSON으로 다시 쓴 바이트(Chat Completions는 도구마다 감싸개
`{"type":"function","function":…}`가 조금 더 붙음). 토큰은 `AIAgentToolSets.estimateTokens` 어림(영어·JSON은 바이트/4). "전"은 이 변경 전 devel(#224)의
동작으로, 연결 없는 대화는 요청마다 도구 101개를 보냈다(#61의 69,400 B는 같은 101개를 빈칸 있는 `json.dumps`로 잰 값).

| 묶음 | 도구 수 | 도구 정의 | 어림 토큰 | 8k 컨텍스트 대비 | 32k 대비 | 첫 요청 본문(연결 없음, 짧은 질문) |
|---|---|---|---|---|---|---|
| 전 (모두) | 101 | 66,553 B | 약 16.7k | 204% | 51% | 68,367 B |
| 최소 (vLLM·Ollama 기본) | 13 | 5,316 B | 약 1.3k (1,341) | 16% | 4% | 7,071 B |
| 핵심 (호스팅 모델 기본) | 44 | 27,846 B | 약 7.0k (6,988) | 85% | 21% | 29,559 B |
| 전체 | 101 | 66,553 B | 약 16.7k (16,732) | 204% | 51% | 68,367 B |

- 최소 묶음은 전보다 도구 정의가 92% 작다.
- findTools로 8개를 찾은 뒤의 요청: 최소 13 → 21개 12,733 B, 핵심 44 → 52개 35,104 B.
- 자동(컨텍스트의 25%): 8,192·16,384 → 최소, 32,768·65,536 → 핵심, 128,000 이상 → 전체.
- 8k 컨텍스트의 최소 묶음 요청은 도구 약 1.3k + 시스템 프롬프트 약 0.3k + 답할 몫 1k(1/8)를 빼면 기록에 약 5.5k 토큰이 남는다.

### 확인 (2026-11-12)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS. 빌드한 앱 사본을 빈 작업 공간·설정·데이터·키링으로
띄우고(`CHROMITE_DATA` 포함), 127.0.0.1의 가짜 서버(Anthropic 꼴 `/anthropic/v1/…`, vLLM 꼴 `/v1/…`)만 쓰도록 두 제공자를 정했다. 첫 채팅 요청 전에
Providers의 Test가 가짜 서버에 닿은 것(`GET /anthropic/v1/models`, `GET /v1/models`)을 서버 기록으로 확인했다. 실제 제공자는 부르지 않았다. 앱 안 시험
에이전트(채팅 실행기를 직접 부르고 Swing 객체를 직접 다룸)로 확인했다. 회귀 테스트는 요청되지 않아 실행하지 않았다(지운 설정을 가정하던 기존 테스트 한 줄만 고침).

| 시험 | 결과 |
|---|---|
| 예전 설정 옮기기 | 작업 공간 설정에 `chromite.ai.chat.sendAllTools=true`를 넣고 시작 → 다섯 제공자 모두 "전체", 옛 키는 설정 파일에서 사라짐 |
| 카탈로그 | 채팅 도구 102개(`findTools` 포함), MCP 도구 101개(`findTools` 없음) |
| 핵심(Anthropic) | 요청 도구 44개, `findTools` 있음, 긴 설명 |
| findTools(핵심) | 모델이 `findTools(query="backup history")` → 결과 8개(`backupDatabase`, `getAgentJobHistory`, `getJobHistory`, …. 이미 있던 `getBackupHistory`는 빠짐) → 같은 차례의 다음 요청 도구 52개, 다음 차례에도 52개. 대화 파일에 `foundTools` 저장 |
| 전체(Anthropic) | 도구 101개, `findTools` 없음 |
| 자동(Anthropic, 200k) | 전체(101개) |
| 최소(vLLM, 8,192) | 도구 13개, 짧은 설명(`listConnections`: "Lists the connections: id, name, driver, production, readOnly. …"), 최소 묶음 지시문 |
| findTools(최소) | 결과 8개(`getBackupHistory` 첫째) → 다음 요청 21개 |
| 이름으로 부른 도구 | 사용자 글 "Please run getErrorLog and getJobHistory" → 그 차례 요청에 `getErrorLog` 더해짐(`getJobHistory`는 이미 찾음) |
| 도구 없음 | 모델 `fake-notools`: 도구 13개 요청이 400 → 같은 요청을 도구 0개·도구 없음 지시문으로 다시 보내 답을 받음. 다음 차례도 도구 0개. 설정에 `chromite.ai.chat.toolsOff.vllm=fake-notools` |
| 보내기 전 확인 | vLLM 8,192에 한국어 약 35,000자 글 → 서버에 요청하지 않고 대화에 "이 요청은 모델의 컨텍스트(8,192토큰)에 들어가지 않습니다. 도구 정의 약 1,341, 대화 약 28,127, 지시와 답할 몫 약 1,307토큰입니다. …" |
| 예전 대화 | 0.1.4 형식 대화 파일(`foundTools` 없음, 도구 항목 포함)을 넣고 `AIAgentChatStore.loadAll` → 읽힘(항목 4개, 찾은 도구 없음) |
| Providers 화면 | 150%와 100%, 한국어: 다섯 양식에 "채팅 도구 묶음:"(자동(컨텍스트 크기로) / 최소(도구 13개, 짧은 설명) / 핵심(도구 44개) / 전체(도구 101개)), Ollama·vLLM에 "컨텍스트 크기(토큰):"(vLLM 자리 표시 "비우면 8192"). 묶음·크기를 바꾸면 "저장하지 않은 변경 N개"와 [저장]·[되돌리기]가 켜지고, [저장]은 설정 파일에, [되돌리기]는 저장된 값으로. vLLM Test가 빈 칸을 `max_model_len` 8192로 채움. Tool Policy 탭은 눈으로 보지 않았다(확인란 코드만 지움) |
| 채팅 창 안내 | 모델을 vLLM `fake-notools`로 고르면 입력란 위에 경고 색 안내 "이 모델은 도구를 호출할 수 없어 도구 없이 답합니다. …"가 보이고 `fake-qwen`으로 바꾸면 사라짐 |

## 배치의 부분 실패 (앱 변경 #83, 2026-11-12)

### 문제

SQL 도구(`runSql` 등)는 JDBC를 직접 읽는다(`AgentSql.Runner.execute`, 쿼리 창의 `SQLQueryExecuteJob`을 거치지 않음). 결과 읽기 루프가 첫 오류에서
예외로 끝나서, 그때까지 읽은 결과와 행 수가 버려졌고 오류 뒤는 읽지 않았다. SQL Server는 문장 오류(RAISERROR, 0으로 나누기, 키 위반) 뒤에도 배치의
나머지를 실행하므로, `INSERT …; RAISERROR('mid',16,1); INSERT …; SELECT …`를 `runSql`로 실행하면 도구 결과·감사 기록·채팅 카드가 모두 `"error":"mid"`
하나였지만 두 INSERT는 이미 커밋돼 있었다(자동 커밋). 모델은 "실패"만 보고 배치를 다시 실행해 DML을 두 번 실행할 수 있었다. 같은 원인으로:

- MariaDB·MySQL에서 프로시저가 결과를 낸 뒤 INSERT하고 SIGNAL로 실패하면, 결과는 `mid`뿐이고 INSERT는 커밋돼 있었다(자동 커밋이라는 말도 없음).
- `runScript`는 실패한 문장이 있어도 호출 전체가 `SUCCESS`로 감사되고 카드도 "완료"였다(`failed: n`만 있음).
- 오류 결과에는 트랜잭션 상태(`transactionOpen`)가 없었다. 수동 커밋 연결에서 실패한 호출 뒤 트랜잭션이 열려 있다는 것을 모델이 알 수 없었다.

### 규칙

- **끝까지 읽는다**: `Runner.execute`는 오류가 나도 결과를 계속 읽는다. 오류 뒤를 읽을지는 쿼리 창과 같은 규칙(`SQLBatchErrors.canReadPast`, 이 작업에서
  공개하고 데이터 원본·모니터로 묻는 판을 더함: 드라이버가 `isMultipleResultsFetchBroken`(SQL Server, Sybase, jTDS)이고, 취소되지 않았고, 연결 끊김·취소
  오류가 아님). 결과를 다 읽은 뒤에만 오류 위치(`DBPErrorAssistant.getErrorPosition`)를 묻는다(SQL Server 드라이버는 오류 사슬을 읽을 때 뒤 결과를 미리
  읽어 버린다, [sql-batch-errors.md](sql-batch-errors.md) 원인 3).
- **결과는 오류, 숨기지 않음**: 오류와 함께 다른 결과가 있으면 `AgentSql.PartialResultException`이 결과 JSON을 들고 올라오고, `AgentTool.callFunction`이
  그것을 예외가 담긴 도구 결과로 돌려준다. 실행기(`AIToolExecutor`)는 `ERROR`로 감사하고, 오류 JSON(`error`, `outcome`, `tool`, `policy`) 뒤에 도구가
  한 일을 붙인다. 감사 기록의 `error`, 결과의 `error`(채팅 카드가 보이는 글), 모델이 받는 글이 같은 요약이다.
- **결과 모양**(오류일 때만, 성공 결과는 그대로):

  | 필드 | 뜻 |
  |---|---|
  | `steps` | 순서대로: `{"resultSet": i}`(i = `results`의 위치), `{"updateCount": n}`, `{"error": 글, "code": 벤더 코드, "line": 줄}`(알 때만) |
  | `results`, `updateCounts`, `messages` | 성공 결과와 같은 모양(행 수·크기 한도 그대로) |
  | `ranAfterError` | 첫 오류 뒤에 결과·행 수·다른 오류가 있었을 때만: "Statements after the first error ran: 1 update count, 1 result set came after it. …" |
  | `transactionOpen`, `transactionNote` | 호출 뒤 트랜잭션 상태(DB에 물은 값)와 그 뜻 |
  | `error`(요약) | 오류 뒤가 실행됐으면 "Statements after the error ran (1 update count, 1 result set came after it). First error: mid (line 2)", 아니면 첫 오류 글(+ "; before it: 1 update count") |

- **혼자인 오류**: 오류 하나뿐이고 다른 결과가 없으면 오류 글은 예전 그대로다. 호출이 한 일이 남는 호출(NATIVE·ATOMIC, 열린 트랜잭션 안의 읽기, 단일 연결)이면
  같은 글로 `transactionOpen`·`transactionNote`만 붙인다. MySQL·MariaDB 드라이버는 CALL의 응답을 `execute`에서 다 읽고 오류 앞 결과를 버리므로(드라이버
  동작, 아래 표) 이것이 "프로시저가 이미 커밋했을 수 있다"를 알리는 유일한 길이다. 스스로 롤백하는 호출(읽기 전용 보호, 측정)의 혼자인 오류는 예전 그대로다.
- **`transactionNote`**(`AgentSql.describeTransaction`, `run`이 호출 뒤 트랜잭션 상태를 DB에 물은 다음 붙임):
  - 열림: "What ran, before and after the error, is in the agent's open transaction and not committed. Ask the user, then call commitTransaction or
    rollbackTransaction". PostgreSQL은 오류가 트랜잭션을 중단시키므로 "…PostgreSQL aborted it at the error: nothing in it can be committed any more. … rollbackTransaction".
  - 도구가 스스로 롤백함(`READ_ONLY`, `ROLLBACK`, 자동 커밋의 `ATOMIC`): "Nothing this call did was kept: …".
  - 자동 커밋, PostgreSQL, 한 번의 실행(`runSql`): "PostgreSQL ran the statements of this call as one transaction, so the error rolled all of them back, unless
    the SQL committed by itself" (pgjdbc가 한 `execute`의 문장을 Sync 하나로 보내 서버가 암시적 트랜잭션 하나로 실행함, 아래 확인).
  - 그 밖의 자동 커밋: "statements that ran before or after the error are already committed; a failed procedure call may have committed part of its work".
  - 수동 커밋인데 열린 것이 없음: "No agent transaction is open after the call".
  - **공유 세션**("단일 연결" 연결, `isForceUseSingleConnection`: 격리 문맥을 열 수 없어 에이전트가 쿼리 창과 같은 기본 문맥에서 실행): 호출 뒤 그 세션의
    트랜잭션을 쿼리 창과 같은 규칙으로 DB에 묻고 기록한다(`QMUtils.refreshTransactionState`, 모드·커밋은 건드리지 않음). 열려 있으면 `transactionOpen: true`와
    `AgentSql.SHARED_SESSION_NOTE`("… what ran is in that session's open transaction and not committed. commitTransaction and rollbackTransaction do not end it
    (they end only the agent's own sessions); ask the user to commit or roll it back in a query window (status bar or Query menu)", PostgreSQL이면 "aborted …
    only a rollback ends it"를 덧붙임). 수동 커밋인데 열린 것이 없으면 "No transaction is open in the connection's shared session after the call", 자동 커밋은 위와 같다.
    성공 결과(`QueryTools.addTransactionState`)도 같은 값으로 `transactionOpen`을 알린다. 전에는 이 경우 `pending`이 늘 거짓이라 커밋 안 된 INSERT 뒤에도
    `transactionOpen: false`, 실패 때는 "nothing … is waiting for a commit"이었고, 쿼리 창 상태 줄도 그 트랜잭션을 몰랐다(쿼리 관리자는 에이전트 문장을 보지 못함).
    이 트랜잭션은 사용자의 것이기도 하므로 `pending`으로 두지 않는다. 그래서 `commitTransaction`·`rollbackTransaction`은 묻지 않고(정책의 Allow/Deny만) 아무것도
    끝내지 않으며, 결과의 `otherOpenTransactions`에 그 세션("Main")이 보인다. `getTransactionStatus`도 `agentTransactionOpen: false`,
    `otherOpenTransactions: ["Main"]`이다. 끝내는 것은 사용자가 쿼리 창(상태 줄, Query 메뉴)에서 한다.
- **자르기**: 오류 결과가 실행기 한도(`AIToolExecutor.MAX_RESULT_CHARS`, 1 MB)를 넘으면 글 전체를 자르지 않고(전에는 `truncatedText` 하나만 남아 뒤쪽의
  `ranAfterError`·`transactionOpen`·`transactionNote`·`steps`가 잘렸다) JSON을 먼저 읽어 가장 큰 멤버부터 줄인다(`fitResult`): 객체는 큰 멤버를, 배열은 앞
  항목을 남기고 넘치는 첫 항목을 다시 줄인다(표는 앞 행을 남김). 작은 멤버는 그대로이고, 무엇을 줄였는지 `cut`에 적는다
  (예: "results[0].rows: 3384 of 3404 items kept; results: 1 of 2 items kept").
- **트랜잭션은 건드리지 않는다**: 오류 때문에 커밋·롤백하지 않는다(사용자 규칙). 바뀐 것은 결과 읽기와 보고뿐이다. 원래 있던 롤백만 그대로다: 읽기 전용
  보호와 측정 호출의 매번 롤백, 자동 커밋 연결에서 `insertRows`·`importCsv`·`copyTable`이 자기 호출을 묶은 트랜잭션을 오류 때 롤백(`runAtomic`, 위
  "쓰기 도구의 커밋"). 앞의 둘은 쓰기가 없거나 되돌리는 것이 목적이고, 셋째는 이 호출이 시작한 자기 트랜잭션만 되돌린다(열린 에이전트 트랜잭션이 있으면
  `ATOMIC`도 그 안에서 실행하고 롤백하지 않음). 그 결과는 "Nothing this call did was kept"로 알린다.
- **`runScript`**: 문장(드라이버 구분자로 나눈 것)마다 실행하는 것은 그대로다. 실패한 문장이 있으면 호출을 오류로 끝내되 문장마다의 결과를 모두 돌려준다
  (요약 "1 of 3 statements failed; statement 2: … (1 statement(s) after it ran)" 또는 "(the script stopped there)"). 실패한 문장 안에서 오류 뒤가 실행됐으면
  그 문장 항목에 `steps`·`ranAfterError`가 붙는다. `stopOnError=false`로 실패 뒤 문장이 실행됐으면 `ranAfterError`.
- **같은 실행 경로의 다른 도구**: `Runner.execute`/`query`를 쓰는 도구는 모두 같은 결과를 낸다(`AgentTool`에서 한 번 처리): `runReadOnlySql`, `runLibraryQuery`,
  DDL 도구, `killSession`, 계획 도구의 질의. 진단 SQL 도구(`DiagnosticSqlTool`)는 문장마다 오류를 잡아 `{"error": …}`로 두는 기존 방식 그대로이고(고정된
  읽기 전용 SQL), 오류 글은 위 요약이다. `runSelect`·`sampleRows` 등 `queryPage`는 `executeQuery` 하나라 배치가 없다.
- **도구 설명**: 바꾸지 않았다(토큰 변화 0). 실패한 호출의 결과 자체가 무엇이 실행됐는지, 커밋됐는지를 말하므로 모델이 호출 전에 알 필요가 없다. 최소 묶음의
  도구 정의는 그대로 5,316 B다(가짜 서버 기록).

### 엔진별 동작 (2026-11-12 확인)

| 엔진·호출 | 서버·드라이버가 하는 일 | 고치기 전 결과 | 고친 뒤 결과 | 두 번째 세션의 표 |
|---|---|---|---|---|
| SQL Server `runSql` `INSERT 1; RAISERROR('mid'); INSERT 2; SELECT` (자동 커밋) | 배치 계속 실행 | `"error":"mid"`뿐 | 오류 + `steps` [행 수 1, 오류 mid line 2 code 50000, 행 수 1, 결과 0], 표(1, 2), `ranAfterError`, 자동 커밋 안내 | 1 before, 2 after |
| SQL Server `RAISERROR('first'); UPDATE; SELECT` | 첫 문장 오류 뒤 계속 | `"error":"first"` | "Statements after the error ran (1 update count, 1 result set …)" | id 1 changed |
| SQL Server `SELECT 1/0; INSERT 3; RAISERROR('second'); SELECT count` | 오류 두 개, 사이 DML 실행 | 0으로 나누기 오류뿐 | 오류 두 개(line 1, 3), 행 수 1, 결과 n=3 | 3 between |
| SQL Server `INSERT 4; SELECT 없는 표; INSERT 5` | 이름 오류(208)는 배치를 멈춤 | 208 오류뿐 | "Invalid object name … (line 2); before it: 1 update count", 뒤 없음 | 4 kept, 5 없음 |
| SQL Server `runReadOnlySql` `SELECT 1; RAISERROR; SELECT 2` | 계속 | `ro mid`뿐 | 결과 2개 + 오류, "Nothing this call did was kept" | — |
| SQL Server 수동 커밋 `INSERT 11; RAISERROR; INSERT 12; SELECT` | 계속, 트랜잭션 열림 | (고치기 전 빌드로는 돌리지 않음) | 오류 + 결과, `transactionOpen: true`, 열린 트랜잭션 안내. `getTransactionStatus` 열림. 두 번째 세션의 SELECT는 에이전트 세션에 막힘(LCK_M_S). `rollbackTransaction`(확인 Roll back) 뒤 | 행 없음 |
| SQL Server `runScript` `INSERT 6; SELECT 1/0; INSERT 7`, `stopOnError=false` | 문장마다 | `SUCCESS`, `failed: 1` | `ERROR` "1 of 3 statements failed; statement 2: … (1 statement(s) after it ran)" | 6, 7 |
| SQL Server `runScript` `INSERT 8; RAISERROR; INSERT 9 GO SELECT` | 문장마다(파서가 `;`로 나눔), 오류에서 멈춤 | `SUCCESS`, `failed: 1` | `ERROR` "… (the script stopped there)" | 8 |
| PostgreSQL `runSql` `INSERT 1; SELECT 1/0; INSERT 2; SELECT` (자동 커밋) | pgjdbc가 `execute`에서 오류, 서버는 한 암시적 트랜잭션을 롤백 | 오류뿐 | 오류 + "PostgreSQL ran … as one transaction, so the error rolled all of them back" | 없음 |
| PostgreSQL 같은 호출, 수동 커밋 | 트랜잭션 중단(aborted) | 오류뿐(열림을 모름) | 오류 + `transactionOpen: true`, "aborted … rollbackTransaction" | 없음 |
| PostgreSQL `runReadOnlySql` 여러 문장 | `execute`에서 오류, 앞 결과는 드라이버가 버림 | 오류뿐 | 같음(읽기라 남는 것이 없음) | — |
| MariaDB 11 `CALL p_mid()`(SELECT, INSERT, SIGNAL, INSERT, SELECT) 자동 커밋 | 드라이버(3.5.10)가 응답을 `execute`에서 읽고 SIGNAL에서 오류, 앞 결과는 버림 | `"(conn=…) mid"`뿐 | 같은 오류 + 자동 커밋 안내("a failed procedure call may have committed part of its work") | 1 before |
| MariaDB 같은 호출, 수동 커밋 | 트랜잭션 열림 | (고치기 전 빌드로는 돌리지 않음) | `transactionOpen: true`. 확인에서 Commit 뒤 | 1 before |
| MySQL 8.4 `CALL p_mid()`(번들 MariaDB 드라이버로) | MariaDB와 같음 | `mid`뿐 | MariaDB와 같음 | 1 before |
| Oracle 23ai `CALL p_mid()`(INSERT 뒤 RAISE_APPLICATION_ERROR) 자동 커밋 | 실패한 문장(호출)을 문장 단위로 롤백 | 오류뿐 | 오류 + 자동 커밋 안내 | 없음 |
| Oracle `runScript` `INSERT 3; BEGIN INSERT 4; RAISE…; END; INSERT 5` | 블록만 롤백, 앞뒤 문장은 커밋 | `SUCCESS` | `ERROR` "… (1 statement(s) after it ran)" | 3, 5 |
| Oracle 수동 커밋 `runScript` `INSERT 13; CALL p_mid(); INSERT 14` | 13, 14가 열린 트랜잭션에 | `SUCCESS` | `ERROR`, `transactionOpen: true`. Roll back 뒤 | 없음 |

### 확인 (2026-11-12)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing`(JBR 25) BUILD SUCCESS. 빌드한 앱 사본을 빈 작업 공간·설정·데이터
(`CHROMITE_DATA`)로 띄우고 앱 안 시험 에이전트가 도구를 실행기(`AIToolExecutor`, 클라이언트 MCP)로 불렀다. 같은 시나리오를 고치기 전 빌드(devel
8b8eca369b)에서도 돌렸다. 시험 DB는 GCP VM의 공용 서버(SQL Server 2022, PostgreSQL 17, MariaDB 11, MySQL 8.4, Oracle 23ai FREEPDB1)의 각자 만든
`chr_agent_runsql_batch` 데이터베이스·스키마이고, 표의 내용은 두 번째 세션(따로 연 연결)에서 읽었다. 확인 대화상자 대신 시험 에이전트의 처리기가 답했다.

- 위 표의 고친 뒤 결과와 감사 기록 `error`가 같은 요약이다(예: `"outcome":"ERROR","error":"Statements after the error ran (1 update count, 1 result set came after it). First error: mid (line 2)"`).
- 연결 새로 고침(탐색기의 Refresh와 같은 `DBNNode.refreshNode`) 뒤, 연결 끊고 다시 연결한 뒤, 에이전트로 DDL(`CREATE TABLE`/`DROP TABLE`)을 실행하고 새로 고친
  뒤에도 SQL Server 사례가 같았다. 새로 고침 뒤 결과는 #243(`JDBCDataSource.refreshObject`가 엔진의 정보를 버리지 않음) 위에서 맞다. #243 전 devel 위의 첫 빌드에서는
  새로 고친 연결에서 오류 뒤를 읽지 않았다(`isMultipleResultsFetchBroken`이 빈 정보에서 false).
- 채팅 경로: 127.0.0.1의 가짜 Chat Completions 서버만 쓰도록 제공자를 정하고, 가짜 모델이 `runSql`을 부르게 했다. 도구 카드 머리말 "Failed, 22 ms - Statements after the
  error ran (1 update count, 1 result set came after it). First error: mid (line 2)", 감사 기록 `client: CHAT`의 `error`와 같음. 가짜 서버 기록에 요청이 남았고 실제 제공자는
  부르지 않았다.
- 한 문장 오류(`INSERT` 중복 키)는 예전과 같은 글에 트랜잭션 상태만 붙는다. 성공 결과는 바뀌지 않았다.
- 공유 세션(검토 뒤 고침): SQL Server·PostgreSQL 연결에서 `isForceUseSingleConnection`을 시험 에이전트가 켜고(이 값은 드라이버 속성에서 오고 연결 화면에
  설정이 없어 저장되지 않음) 쿼리 창 하나를 열어 확인했다.

  | 경우 | 고치기 전 | 고친 뒤 | 두 번째 세션 | 쿼리 창 상태 줄 |
  |---|---|---|---|---|
  | SQL Server 수동 커밋 `INSERT 1; RAISERROR; INSERT 2` | `transactionOpen: false`, "No agent transaction is open … nothing … waiting for a commit" | `transactionOpen: true`, 공유 세션 안내 | READPAST 0행, NOLOCK 1·2(커밋 안 됨) | 전: 표시 없음 / 뒤: "Open transaction 00:00:00 (changes)" |
  | 같은 상태에서 `commitTransaction`(Allow) | — | SUCCESS, `otherOpenTransactions: ["Main"]`, 아무것도 커밋하지 않음 | 그대로 커밋 안 됨 | 열림 그대로 |
  | 쿼리 창 Roll Back | 전: 상태를 몰라 아무것도 안 함(행이 남음) | 롤백됨 | 0행 | 표시 사라짐 |
  | SQL Server 수동 커밋 `INSERT 3` 성공 | `transactionOpen: false` | `transactionOpen: true`, 공유 세션 안내 | 커밋 안 됨 | 열림 표시 |
  | SQL Server 자동 커밋 같은 배치 | 자동 커밋 안내 | 같음 | 4·5 커밋됨 | 표시 없음 |
  | PostgreSQL 수동 커밋 `INSERT 1` 성공, 뒤 `INSERT; SELECT 1/0; INSERT` | `false`, "No agent transaction is open …" | 둘 다 `true`, 오류에는 "aborted … only a rollback ends it" | 0행 | "Open transaction … (changes)" → 오류 뒤 "(0 statements)", Roll Back 뒤 사라짐 |
  | PostgreSQL 자동 커밋 | PostgreSQL 자동 커밋 안내 | 같음 | 8만 | 표시 없음 |

- 자르기(검토 뒤 고침): SQL Server `runSql`(limit 10000) `SELECT TOP 4000 REPLICATE('x',300) …; RAISERROR('cut mid'); SELECT TOP 4000 REPLICATE('y',300) …; INSERT 99`.
  전: 1,062,480자, 키는 `error, outcome, tool, policy, policyReason, truncatedText, note`뿐(`ranAfterError`·`transactionNote`·`steps` 없음). 뒤: 1,046,743자, `steps`,
  `ranAfterError`, `transactionOpen`, `transactionNote` 그대로, `results`는 첫 표의 앞 3,384행, `cut` "results[0].rows: 3384 of 3404 items kept; results: 1 of 2 items kept".
- 회귀 테스트는 요청되지 않아 실행하지 않았다.

### 남은 것

- MySQL·MariaDB의 CALL에서 오류 앞 결과 집합은 드라이버가 버려 보일 수 없다. `setFetchSize`로 흘려 읽으면 앞 결과는 읽히지만, 오류 뒤
  `getMoreResults`가 멈추거나(시험에서 끝나지 않음) `NullPointerException`을 던져서 쓰지 않았다.
- 결과 집합의 행을 읽다 난 오류(SQL Server `SELECT 1/0`)는 그 결과 집합의 이미 읽은 행을 보이지 않는다(오류 단계만). 쿼리 창은 빈 표를 보인다.
- `runSql`은 끝의 `;`를 모두 지워(`AgentSqlClassifier.stripTrailingDelimiter`) Oracle PL/SQL 블록(`BEGIN … END;`)이 PLS-00103으로 실패한다. 이 작업의 범위가 아니라 고치지 않았다.
- ~~공유 세션에서는 `AgentSql.run`이 모드를 적용하지 않고 바로 실행한다: 읽기 전용 보호의 롤백도, 측정(`ROLLBACK` 모드, `getActualPlan`의 DML)의 롤백도 없다.~~
  2026-11-12 고침(앱 변경 #91): 아래 "공유 세션에서 롤백에 기대는 호출".
  공유 세션의 `commitTransaction`은 아무것도 커밋하지 않아도 SUCCESS다(결과의 `otherOpenTransactions`로만 알 수 있음). 이 작업에서 고치지 않았다.

## 공유 세션에서 롤백에 기대는 호출 (앱 변경 #91, 2026-11-12)

### 문제

"단일 연결" 연결(`DBPDataSourceContainer.isForceUseSingleConnection()`)에서는 에이전트가 격리 문맥을 열 수 없어 쿼리 창과 같은 기본 문맥(공유 세션)에서
실행한다. `AgentSql.run`의 공유 세션 분기는 사용자 트랜잭션을 건드리지 않으려고 호출 방식(`READ_ONLY`, `ROLLBACK`)을 적용하지 않고 작업을 그대로 실행했다.
그래서 자기 트랜잭션을 열고 끝에 롤백하는 것으로 안전을 지키는 호출이 공유 세션에서는 문장을 그대로 실행했다(고치기 전 빌드, 두 번째 세션에서 읽음).

| 경우 | 고치기 전 결과 | 두 번째 세션 |
|---|---|---|
| SQL Server 자동 커밋 `getActualPlan` `UPDATE dbo.t SET v='shared-ac' WHERE id=1` | SUCCESS, "The statement was executed in a transaction that was rolled back." | id 1 = `shared-ac`, 커밋됨 |
| SQL Server 수동 커밋 같은 호출(id 2) | SUCCESS, 같은 글 | READPAST에서 id 2 안 보임(잠김), NOLOCK에서 `shared-mc`. 쿼리 창 "Open transaction (changes)" |
| PostgreSQL 자동 커밋 같은 호출 | SUCCESS, 같은 글 | id 1 = `pg-shared`, 커밋됨 |
| PostgreSQL `validateSql` `SELECT 1; INSERT INTO t VALUES (20, …)` | SUCCESS, `serverValid: true` | 20이 들어감(격리 문맥에서는 "cannot execute INSERT in a read-only transaction") |
| Oracle 수동 커밋 `explainQuery`(SELECT) | SUCCESS | 쿼리 창에 "Open transaction (0 statements)": EXPLAIN PLAN이 사용자 세션에 PLAN_TABLE 행을 쓰고 트랜잭션을 열어 둠 |
| Oracle 수동 커밋 `getActualPlan` UPDATE | SUCCESS, "rolled back" | 사용자 세션의 열린 트랜잭션에 UPDATE가 남음(쿼리 창 Roll Back으로 지움) |

같은 조사에서, 읽기 도구를 지키는 분류기(`AgentSqlClassifier.classify`)가 글의 첫 문장만 보았다. JSQLParser의 `Statement()`가 첫 문장의 구분자에서 멈추므로
`SELECT 1; INSERT …`가 SELECT(읽기)였다(`validateSql`의 `readOnly: true`, PostgreSQL·Oracle·SQL Server 모두). 공유 세션에서는 이 분류가 읽기 도구의 유일한 보호다.
SQL Server는 읽기 전용 트랜잭션이 없어 격리 문맥에서도 `runReadOnlySql` `SELECT 1 AS a; INSERT INTO dbo.t VALUES (40, …); COMMIT`의 40이 커밋돼 남았다.

### 원인

- `AgentSql.run`(고치기 전 261~270행): `!agentContext.isolated`이면 `work.run`을 바로 부르고 `mode`를 보지 않는다. `ROLLBACK`(측정한 DML, Oracle EXPLAIN PLAN)과
  `READ_ONLY`(읽기 전용 트랜잭션 + 롤백)의 두·세 번째 겹이 없다.
- `PlanTools.plan`(고치기 전 252행)은 읽기가 아닌 문장의 실제 계획을 `ROLLBACK`으로 부르고, 결과에 "rolled back"을 적는다. 확인 요청의 미리 보기도
  "then rolls it back"이다(`getPreview`).
- `QueryTools.validate`의 서버 검사(PostgreSQL·MySQL `EXPLAIN <sql>`)는 글의 첫 문장만 계획하고 나머지는 실행한다. 격리 문맥에서는 읽기 전용 트랜잭션이 막았다.
- `AgentSqlClassifier.classify`는 `SQLQuery`(JSQLParser `Statement()`) 결과 하나로 판정하므로 첫 문장 뒤를 보지 않는다.

### 고친 것

공유 세션의 커밋 방식을 바꾸거나, 그 세션에 트랜잭션을 열거나, 롤백하는 방법은 쓰지 않는다(그 세션은 사용자의 것이다). 롤백에 기대는 호출은 실행하지 않고 거절한다.

- `AgentSql.isSharedSession(container)`: 에이전트 호출이 공유 세션에서 실행되는 연결(`isForceUseSingleConnection`).
- `AgentSql.sharedSessionRefusal(why, instead)`: 거절 글. "Refused, nothing ran: <무엇을 하려 했나>. This connection uses one shared session (its "single
  connection" setting) that the user's query windows also use, so Chromite cannot run this call in a transaction of its own and roll it back: it would really
  run in the user's session. <대신 할 일>". `AIToolArgumentException`이라 실행기 결과는 `INVALID`(읽기 도구가 쓰기를 거절할 때와 같음)다.
- `AgentSql.run`: 격리되지 않은 문맥에서 `Mode.ROLLBACK`이면 작업 전에 거절한다(대신: 쿼리 창에서 사용자가 하기, 또는 `runSql`). `ROLLBACK`은 쓰고 되돌리는 호출만
  쓰는 방식이라 문장을 볼 필요가 없다. Oracle `explainQuery`·`comparePlans`(EXPLAIN PLAN이 PLAN_TABLE을 씀)와 읽기가 아닌 문장의 실제 계획이 여기에 걸린다.
- `PlanTools.classify`(`getActualPlan`): 공유 세션에서 문장이 읽기라고 증명되지 않으면(분류기, 판정 못 하면 거절) 확인 요청 전에 거절한다
  (대신: `explainQuery`로 예상 계획, 실제로 실행하려면 `runSql`). 확인 대화상자에 사실이 아닌 "실행 후 롤백"을 보이지 않기 위해서다.
- `QueryTools.validate`: 공유 세션에서 문장이 둘 이상인 글은 서버에서 검사하지 않고 `serverNote`로 알린다(문장 하나는 그대로 EXPLAIN·NOEXEC).
- `AgentSqlClassifier.classify`: 연결 방언의 스크립트 파서(`QueryTools.splitScript`)로 나눈 문장이 둘 이상이면 읽기가 아니다("the text holds more than one
  statement"). `runSelect`·`runReadOnlySql`·`compareQueryResults`·`exportData`·`runLibraryQuery`·`getActualPlan`의 분류가 모두 이것을 쓴다.
- 읽기는 그대로다: 분류기가 읽기라고 증명한 문장은 공유 세션에서도 실행한다(`getActualPlan`의 SELECT 측정 포함).
- 도구 설명은 바꾸지 않았다(토큰 변화 0). 거절 결과가 이유와 대신 할 일을 말한다.

### "단일 연결" 연결 (2026-11-12 코드 기준)

값은 드라이버 정의의 `singleConnection="true"`(`DriverDescriptor`, `RegistryConstants.ATTR_SINGLE_CONNECTION`)에서 연결을 만들 때 복사된다
(`DataSourceDescriptor` 237·392행). 연결 화면에 설정이 없고 저장되지 않는다. 인증 방식이 연결할 때 켜기도 한다.

| 번들(제품에 포함) | 드라이버 ID | 이름 | 위치 |
|---|---|---|---|
| `ext.mssql` | (인증 방식) | SQL Server, 인증 "MFA"(Entra ID 대화형) | `SQLServerAuthModelMFA.java:51` `setForceUseSingleConnection(true)` |
| `ext.snowflake` | `snowflake_jdbc` | Snowflake | `plugin.xml:44` |
| `ext.clickhouse` | `yandex_clickhouse`, `com_clickhouse` | ClickHouse (Legacy), ClickHouse | `plugin.xml:43`, `:66` |
| `ext.bigquery` | `google_bigquery_jdbc_simba`, `google_bigquery_jdbc` | BigQuery (Legacy), BigQuery | `plugin.xml:43`, `:70` |
| `ext.athena` | `aws_athena_jdbc_amazon`, `aws_athena_jdbc_legacy` | Athena, Athena (Legacy) | `plugin.xml:40`, `:67` |
| `ext.databricks` | `databricks-oss` | Databricks | `plugin.xml:44` |
| `ext.spanner` | `spanner_jdbc_official` | Google Cloud Spanner | `plugin.xml:41` |
| `ext.sqlite` | `libsql_jdbc` | LibSQL | `plugin.xml:90` |
| `ext.generic` | `jdbcx`, `phoenix_hbase`, `gemfire_xd`, `snappydata_store_client`, `spark_hive`, `kyuubi_hive`, `apache_drill` | JDBCX, Apache Phoenix, Gemfire XD, SnappyData, Apache Spark, Apache Kyuubi, Apache Drill | `plugin.xml:858`, `:924`, `:942`, `:957`, `:974`, `:995`, `:1013` |

SQL Server·PostgreSQL·MySQL·MariaDB·Oracle의 기본 드라이버는 해당하지 않는다. 그래서 실제로 #91이 걸리는 곳은 MFA 인증 SQL Server(실제 계획이 있는
DB 종류)와 위 DB들의 읽기 도구(분류기만 보호)다. 위 DB들은 `DbFamily`가 OTHER라 계획 도구가 드라이버 계획기를 쓰고 실제 계획은 지원하지 않는다.

### 도구별 실행 방식 (`AgentSql.Mode`)

| 방식 | 격리 문맥에서 | 공유 세션에서(고친 뒤) | 도구 |
|---|---|---|---|
| `READ_ONLY` | 열린 에이전트 트랜잭션이 없으면 자동 커밋 끔, 읽기 전용 트랜잭션, 끝에 롤백 | 그대로 실행(모드 없음). 사용자가 쓴 SQL은 분류기가 읽기라고 증명한 것만 | `runSelect`, `runReadOnlySql`, `explainQuery`(Oracle 제외), `getActualPlan`(읽기 문장, Oracle 포함), `comparePlans`(Oracle 제외), `whatIfIndex`, `validateSql`(서버 검사), `sampleRows`, `countRows`, `profileColumns`, `compareQueryResults`, `exportData`(읽기), `copyTable`(원본 읽기), `generateDdl`, 메타데이터 도구, `testConnection`, `getCurrentDatabase`, `setCurrentDatabase`, `killSession`의 트랜잭션 확인, 진단 SQL 도구(`DiagnosticSqlTool`), `runLibraryQuery`·`runMonitoringChecks`(읽기 쿼리) |
| `ROLLBACK` | 자동 커밋 끔, 실행, 늘 롤백. 에이전트 트랜잭션이 열려 있으면 거절 | **거절**(`sharedSessionRefusal`) | `getActualPlan`(읽기가 아닌 문장, 분류에서 먼저 거절), Oracle `explainQuery`·`comparePlans`(EXPLAIN PLAN) |
| `NATIVE` | 연결의 커밋 방식 그대로, 열린 트랜잭션 기록 | 그대로 실행, 세션의 트랜잭션을 DB에 물어 알림 | `runSql`, `runScript`, DDL 도구, `killSession`, `runLibraryQuery`(쓰기 쿼리) |
| `ATOMIC` | 자동 커밋이면 호출을 한 트랜잭션으로 묶고 오류면 롤백, 수동 커밋이면 그대로 | 그대로 실행(묶지 않음) | `insertRows`, `updateRows`, `deleteRows`, `importCsv`, `copyTable`(대상 쓰기) |

`NATIVE`·`ATOMIC`은 쓰기라 사용자가 확인하고, 쓴 것은 남는 것이 목적이므로 거절하지 않는다(공유 세션의 `ATOMIC`이 묶지 못하는 것은 아래 "남은 것").

### 확인 (2026-11-12)

빌드(JBR 25) BUILD SUCCESS. 빌드한 앱 사본을 빈 작업 공간·설정·데이터(`CHROMITE_DATA`)로 띄우고 앱 안 시험 에이전트가 도구를 실행기(클라이언트 MCP)로
불렀다. 번들 드라이버 중 단일 연결을 강제하는 것(위 목록)은 시험 서버가 없어서, #83과 같이 시험 에이전트가 SQL Server·PostgreSQL·Oracle 연결의
`setForceUseSingleConnection(true)`를 켜고(MFA 인증이 하는 것과 같은 값, 저장되지 않아 앱을 다시 띄우면 다시 켬) 다시 연결해 쿼리 창을 하나 열었다. 시험 DB는
GCP VM 공용 서버의 `chr_agent_tools_r2` 데이터베이스·스키마, 두 번째 세션은 따로 연 연결이다.

| 경우 | 두 번째 세션 전 | 결과(고친 뒤) | 두 번째 세션 뒤 |
|---|---|---|---|
| SQL Server 공유 세션 자동 커밋 `getActualPlan` `UPDATE … id=1` | (1,a),(2,b) | `INVALID` "Refused, nothing ran: getActualPlan would execute this statement to measure it, and the statement is UPDATE. …" (확인 요청 없음, 감사 `error` 같은 글) | (1,a),(2,b) |
| 같은 연결 `runReadOnlySql` `INSERT …` / `SELECT 1 AS a; INSERT …; COMMIT` | 2행 | 둘 다 `INVALID`("the statement is INSERT" / "the text holds more than one statement") | 2행 |
| 같은 연결 `getActualPlan` `SELECT count(*)`, `runReadOnlySql` `SELECT count(*)` | 2행 | SUCCESS(실제 계획, n=2) | 2행 |
| 연결 Refresh 뒤, 끊고 다시 연결한 뒤 `getActualPlan` UPDATE / SELECT | (1,a),(2,b) | UPDATE `INVALID`, SELECT SUCCESS | (1,a),(2,b) |
| SQL Server 공유 세션 수동 커밋 `getActualPlan` UPDATE | (1,a),(2,b) | `INVALID`, 쿼리 창에 트랜잭션 표시 없음 | 그대로 |
| PostgreSQL 공유 세션 `getActualPlan` UPDATE | (1,a),(2,b) | `INVALID` | (1,a),(2,b) |
| PostgreSQL 공유 세션 `validateSql` `SELECT 1; INSERT … (20)` | 2행 | SUCCESS, `readOnly: false`, `serverNote` "Not checked on the server …" | 20 없음 |
| PostgreSQL 공유 세션 `validateSql` `UPDATE … WHERE id = 1`(문장 하나) | — | `serverChecked: EXPLAIN`, `serverValid: true`(실행 안 됨) | 값 그대로 |
| Oracle 공유 세션 수동 커밋 `explainQuery` SELECT | 상태 줄에 트랜잭션 없음 | `INVALID` "Refused, nothing ran: this call writes (Oracle EXPLAIN PLAN fills PLAN_TABLE …)" | 상태 줄 그대로 트랜잭션 없음 |
| Oracle 공유 세션 `getActualPlan` UPDATE / SELECT | (1,a) | `INVALID` / SUCCESS | (1,a), 트랜잭션 없음 |
| 격리 SQL Server `runReadOnlySql` `SELECT 1 AS a; INSERT … (40); COMMIT` | 2행 | `INVALID`(고치기 전에는 40이 커밋됨) | 2행 |
| 격리 SQL Server `getActualPlan` UPDATE(바뀌지 않아야 함) | (1,a) | SUCCESS, "rolled back" | (1,a) |
| 격리 PostgreSQL `validateSql` `SELECT 1; INSERT …` | — | `readOnly: false`, 서버 검사는 그대로(읽기 전용 트랜잭션이 INSERT를 거절) | 들어가지 않음 |

- 공유 세션의 커밋 방식·트랜잭션은 바꾸지 않았다: 위 수동 커밋 사례의 상태 줄은 거절 전후 같았다.
- 회귀 테스트는 요청되지 않아 실행하지 않았다(추가한다면 `AgentSqlClassifier.classify`의 여러 문장 판정, `PlanTools.classify`의 공유 세션 거절 단위 시험).

### 남은 것

- 분류기가 읽기라고 판정한 문장이 함수 안에서 쓰면 공유 세션에서 실제로 쓴다: PostgreSQL `runReadOnlySql` `SELECT f_write()`(INSERT하는 SQL 함수)가
  공유 세션에서 행을 넣었다(격리 문맥은 "cannot execute INSERT in a read-only transaction"). 브리프의 규칙("읽기는 그대로")에 따라 막지 않았다.
- SQL Server는 `;` 없이 문장을 이어 쓸 수 있는데 JSQLParser는 `SELECT 1\nDELETE FROM dbo.t\nCOMMIT`을 별칭 붙은 SELECT 하나로 읽는다(`DELETE`가 열 별칭,
  `COMMIT`이 테이블 별칭). 그래서 격리 문맥에서도 `runReadOnlySql`이 이것을 읽기로 실행해 행을 지우고 커밋했다(2026-11-12 확인). 이 작업의 범위가 아니라
  고치지 않았다(분류기가 예약어 별칭을 거절하거나 방언 토큰으로 쓰기 키워드를 찾는 등 따로 정해야 함).
- 공유 세션의 `ATOMIC`은 호출을 한 트랜잭션으로 묶지 못한다(자동 커밋이면 오류 전 행이 커밋됨). 결과의 `transactionNote`는 사실대로 "already committed"다.
- 공유 세션에서 수동 커밋이면 읽기도 그 세션에 트랜잭션을 시작한다(드라이버가 자동 커밋이 아닌 세션의 첫 문장에서 시작). 쿼리 창에서 읽어도 같다.

## 설계 결정과 이유

- **확장점 하나, 레지스트리 하나**: 새 도구도 기존 `com.seizia.chromite.ai.function`에 등록해 채팅과 MCP가 같은 목록을 본다. 분류를 위해 `access`, `writes` 속성만 더했다.
- **정책은 실행기 한 곳**: 기존 채팅의 "항상 허용/묻기"(`AIFunctionAllowMode`)는 함수별 전역 설정이라 연결별·분류별 정책을 담지 못한다. 새 도구는
  `defaultAllowMode`를 기본값(ALWAYS_ALLOW)으로 두어 이전 흐름이 다시 묻지 않게 하고, 실행기의 정책이 유일한 판정이 되게 했다.
- **진단은 SQL 자원 + 얇은 Java**: 상태·성능 도구 대부분은 DB 종류별 시스템 뷰 쿼리다. SQL을 `diag/<도구>.sql`의 DB 종류별 블록으로 두고
  공통 실행기(`DiagnosticSqlTool`)가 읽기 전용 보호, 매개변수 묶기, JSON 변환, 권한 오류 안내를 한다. 해석이 필요한 것(실행 계획 XML, 교착 상태 그래프,
  블로킹 트리, 줄 번호)만 Java 후처리를 둔다. DB 종류를 더할 때 SQL 블록만 더하면 된다.
- **계획은 DB 원래 형식에서 직접**: 모델의 `DBCQueryPlanner` 노드는 DB마다 속성이 달라 실제 행·경고·누락 인덱스·매개변수 컴파일 값이 빠진다. SQL Server는
  `SHOWPLAN_XML`/`STATISTICS XML`, PostgreSQL은 `EXPLAIN (FORMAT JSON)`을 직접 읽어 공통 노드 형식으로 바꾼다. 다른 DB는 계획기 노드로 대신한다.
- **에이전트 전용 격리 문맥**: 위 "실행 문맥".
- **데이터 전송은 간단한 자체 구현**: Swing 마법사의 데이터 전송 모델(`DataTransferSettings`, 처리기 노드 설정)은 화면 설정이 많아 도구 인수로 옮기기 어렵다.
  도구는 JDBC로 읽고 CSV·JSON(직접)·XLSX(Apache POI)로 쓰며, 넣기는 값 처리기로 형식을 맞춘다. 큰 전송은 마법사를 쓰라고 안내한다.
- **Query Library는 파일을 직접 읽음**: 라이브러리 저장소 클래스는 Swing 번들 안(`QueryLibrary`, `QueryLibraryFile`)에 있다. 모델 번들이 Swing에 의존할 수 없고,
  다른 에이전트가 Query Library 창을 고치는 중이라 옮기지 않았다. 도구는 같은 설정(`com.seizia.chromite.ui.swing` 설정 `queryLibrary.roots`, 기본
  `<작업 공간>/QueryLibrary`)과 같은 머리말 형식을 읽기만 한다. 2단계 이후 저장소를 모델 번들로 옮기면 이 읽기 코드를 지운다(위험 목록).
- **AI 비밀만 OS 보안 저장소로**: 작업 공간 전역 비밀 컨트롤러를 바꾸면 모든 호출자(네트워크 프로필, 프록시, Maven)의 실패 처리가 함께 바뀐다. AI 비밀만
  `AIUtils`에서 옮겼다. 이미 OpenAI 계정 토큰과 DataDam이 같은 방식(`SecurePreferencesSecretController` + `DBSecurityUtils.findNativeOSProvider`, 암호화 확인)을
  쓰고 있어 새 의존성이 없다. 저장 위치는 OS 사용자 단위라 같은 사용자의 작업 공간들이 제공자 키 하나를 함께 쓴다(프로필 ID = 엔진 ID). 평문 대체 저장은
  두지 않았다(위 "API 키 저장").
- **MCP는 JDK HTTP 서버**: 새 라이브러리 없이 `com.sun.net.httpserver`(내장 런타임에 `jdk.httpserver` 있음, `runtime.md`). 기존 CLI REST 서버(`BearerRequestHandler`)와 같다.

## upstream 대비 변경점

- 확장점 스키마 `com.seizia.chromite.ai.function.exsd`에 `access`, `writes` 속성 추가.
- `AIFunctionDescriptor`에 `getAccess()`, `getWrites()` 기본 메서드 추가.
- `AIAssistantImpl.callFunction`이 도구 실행기를 거친다(정책·감사).
- 새 패키지 `model.ai.agent`, `model.ai.engine.anthropic`, `model.ai.engine.chatcompletions`(2026-11-11), 새 번들 `model.ai.tools`.
- `AIUtils.getSecretValueOrDefault`/`setSecretValue`/`deleteSecretValue`가 전역 비밀 컨트롤러 대신 OS 보안 저장소를 쓰고 평문 값을 옮긴다. upstream은 전역 비밀 컨트롤러(데스크톱은 평문 설정)를 쓴다.
- `SecurePreferencesSecretController`가 OS 제공자의 unchecked 예외를 `DBSecurityException`으로 바꾼다.

## 확인 (1단계, 2026-11-07)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing`(SWT 제거 PR #68이 합쳐진 뒤) BUILD SUCCESS, 이어서
`java tools/jre/BundleJre.java windows-x64`. 회귀 테스트는 요청되지 않아 실행하지 않았다.

시험 방법: 빌드한 Swing 제품을 개인 작업 공간으로 띄우고(MCP 켬, 포트 47831), MCP 클라이언트 스크립트(`curl` 대신 Python `urllib`, 토큰은 작업 공간 파일에서 읽음)로
`tools/call`을 불렀다. 연결 6개: PostgreSQL 17(일반, Production 종류, 읽기 전용), SQL Server 2022, MariaDB 11, Oracle 23ai. 시험 객체는 모두 `chr_ai_` 접두어
(DB `chr_ai_agent_tools`, Oracle 스키마 `CHR_AI_AGENT`). 시험용 정책 파일: 일반 PG·MSSQL·MariaDB는 `@WRITE` AUTO, PG의 `dropTable`·`killSession` AUTO, MSSQL `@DANGEROUS` AUTO
(규칙상 CONFIRM으로 낮아짐), Production PG는 `insertRows`·`runSql`만 AUTO.

| 시험 | 결과 |
|---|---|
| 카탈로그 | `tools/list` 86개(READ 66, WRITE 16, DANGEROUS 4; 채팅 문맥이 필요한 기존 3개는 MCP에서 뺌), `prompts/list` 6개, `prompts/get` 인수 채움 |
| 그룹별 READ·WRITE | 연결·메타데이터·쿼리·데이터·DDL·전송·상태·성능·라이브러리 모두 PG에서 READ와 WRITE 각각 성공(예: `describeTable`/`insertRows`, `explainQuery`/`createIndex`, `exportData` CSV·JSON·XLSX, `importCsv`, MariaDB→PG `copyTable` 1,000행). SQL Server 49단계 중 47, MariaDB·Oracle 38단계 중 37 성공(나머지는 아래 의도한 거부) |
| 읽기 보호 | `runSelect "SELECT chr_ai_write_fn()"`(쓰는 함수): 분류기는 SELECT로 통과, PostgreSQL 읽기 전용 트랜잭션이 "cannot execute INSERT in a read-only transaction", MariaDB도 "Cannot execute statement in a READ ONLY transaction"으로 막음. `DELETE`, `SELECT INTO`는 분류기가 거부 |
| 정책 | Production PG: `createIndex`·`dropTable`·`commitTransaction`·`disconnect` BLOCKED, 연결별 규칙을 둔 `runSql`·`insertRows`는 실행되고 `transactionOpen: true`. 읽기 전용 PG: `insertRows` READ_ONLY, `exportData`(파일)는 허용. 규칙 없는 Oracle `insertRows`, PG `dropView`, MSSQL `restoreDatabase`·`killSession`: CONFIRM → 확인 화면이 없어 DENIED |
| 트랜잭션 안전 | Production PG에 열린 에이전트 트랜잭션이 있을 때 `rollbackTransaction`(READ라 막히지 않음)과 `setCurrentDatabase`(READ, AUTO)도 Commit/Roll back/Cancel 질문으로 바뀌어 DENIED. 열린 트랜잭션이 있는 서버 세션에 대한 `killSession`(PG, AUTO 규칙)도 질문으로 바뀌어 DENIED |
| 감사 기록 | `.metadata/chromite-ai/audit.jsonl`에 386건(SUCCESS 336, ERROR 16, DENIED 13, INVALID 11, BLOCKED 8, READ_ONLY 2), 비밀번호 문자열 0건 |
| 블로킹 RCA | psql·sqlcmd·mariadb로 "트랜잭션을 연 채 쉬는 세션 + 같은 행을 기다리는 세션"을 만들고 `getBlockingChain`: 세 DB 모두 머리 블로커(프로그램, 로그인, 트랜잭션 나이, 마지막 SQL), 대기자(대기 자원, SQL), 잠금(개체·인덱스), "idle with a transaction open … did not commit" 소견, MariaDB는 REPEATABLE READ 소견까지 |
| 동시 호출과 취소 | 블로킹 중 에이전트의 `createIndex`가 잠금을 기다리는 동안 다른 클라이언트의 `getBlockingChain`이 28 ms에 돌아와 에이전트 세션을 대기자로 보여 주고, `cancelQuery`가 그 문장을 취소(`canceled: 1`, "canceling statement due to user request") |
| 교착 상태 | SQL Server 교착 상태 2건을 만들고 `getDeadlocks`: 희생자, 프로세스(격리 수준, 대기 자원, inputbuf), 자원(키 잠금, 소유자·대기자) JSON. MariaDB는 InnoDB 최신 교착 상태 글 |
| 튜닝 | PG `explainQuery` Seq Scan(cost 999) → `createIndex`(INCLUDE) → `comparePlans`(actual): Bitmap Index Scan cost 143.3, 0.156 ms vs 비SARG `customer_id + 0` Seq Scan 3.03 ms. MSSQL `explainQuery`가 누락 인덱스(영향 97%) 제안, `createIndex` 뒤 Index Seek |
| 루틴 검토 | MSSQL `getRoutineSource`(줄 번호), `getRoutineStats`: 10행 문장 333회·논리 읽기 88,578(커서 안 집계), `getCachedPlans`: 10행 계획의 `PlanAffectingConvert`·`CONVERT_IMPLICIT(nvarchar(20), status)` 소견. `getObjectDependencies` depth 2 |
| Query Library | `runMonitoringChecks Monitoring`: OK 1, WARN 1(`used_pct` 8.0 > 0.5), NO_THRESHOLDS 1, SKIPPED 1(쓰기 쿼리) |
| 엔진 | API 키가 이 PC에 없어 유료 API는 부르지 않았다. 로컬 가짜 Messages API(`D:\Tools\_scratch\ai-agent-tools\engine\mock_anthropic.py`)와 제품 jar로 `AnthropicClient`를 실행: 모델 목록, `tool_use` 응답 → `AIFunctionCall`(+`tool_use_id`), SSE 스트림(글자 조각 + `input_json_delta` 모음 → 도구 호출 1개, usage), 요청 머리글 `x-api-key`, `anthropic-version: 2023-06-01`, `tool_use`/`tool_result` 짝. OpenAI Responses·Ollama 요청에 같은 도구가 `function` 도구로 들어감을 오프라인으로 확인 |

시험 중에 찾아 고친 것: 문맥 하나가 막히면 연결 전체가 막힘(풀 도입), MySQL `SET TRANSACTION READ ONLY`가 다음 쓰기 트랜잭션에 남음(`START TRANSACTION READ ONLY`로),
Oracle 진단 결과의 대문자 열 이름(소문자로 맞춤), PostgreSQL에서 NULL 정수 매개변수를 VARCHAR로 보냄(형식 있는 NULL), SQL Server 백업 집합 이름 누락,
교착 상태 XML의 실행 스택 해석, 고유 키 중복 표시.

## 위험과 남은 일

- **실제 LLM은 Anthropic만 시험했다**: 2026-11-08에 실제 Anthropic API(`claude-sonnet-5-5`)로 확인했다(위 "실제 API 확인"). OpenAI Responses·Ollama 스트림의
  도구 호출 흐름은 확인하지 못했다. ~~OpenAI 엔진도 요청마다 temperature(전역 설정값, 기본 0.0)를 보내므로 그것을 받지 않는 모델에서는 같은 식으로 실패할 수 있다.~~
  2026-11-08 고침(후속 39): OpenAI도 명시적으로 정했을 때만 보낸다(`OpenAIProperties.getRequestTemperature`). 같은 클래스를 잇는 DataDam 엔진 설정도 같다.
  Ollama는 여전히 설정값(기본 0.0)을 보낸다. Ollama는 이 값을 받아들이므로(거부하지 않음) 바꾸지 않았다.
  긴 대화의 문맥 크기는 2026-11-08에 줄였다(위 "요청 크기").
- **요청 크기의 남은 점(2026-11-08)**: 도구 정의 약 52 KB(≈13k 토큰)가 요청마다 간다. SQL Server 연결은 거의 모든 도구를 쓸 수 있어 고르기로 줄지 않는다.
  더 줄이려면 도구 설명을 짧게 하거나, 제공자의 프롬프트 캐시(Anthropic `cache_control`)를 쓰거나, 묶음을 필요할 때만 여는 방식(도구 검색)이 필요하다.
  2026-11-12 도구 설명에 "언제 쓰고 대신 무엇을 쓸지"를 넣어 설명 글이 약 11 KB 늘었다. 줄이는 방식은 위 "요청마다 보내는 도구 줄이기 제안"(권함: 핵심 묶음 + 도구 찾기).
  예산 60,000자는 고정값이다. 32k 문맥의 로컬 모델(Ollama)에는 도구 정의와 합쳐 클 수 있고, 모델의 문맥 크기 설정(`AnthropicProperties.contextWindowSize` 등)을
  따르게 하지 않았다. 대화의 연결과 다른 연결의 DB 종류 도구는 "도구를 모두 보냄"을 켜야 보인다. 이 확인란은 눈으로 확인하지 않았다(빌드·NLS 키만 확인).
  **2026-11-12 후속(앱 변경 #64)**: 도구 묶음(최소 13·핵심 44·전체 101)과 `findTools`, 컨텍스트 크기에 맞춘 기록 예산과 보내기 전 확인을 넣었다(위 "도구 묶음").
  "도구를 모두 보냄"은 묶음의 "전체"로 바뀌었다.
- **도구 묶음의 남은 점(2026-11-12)**: 실제 모델로 묶음별 정확도를 재지 않았다([agent-tool-eval.md](agent-tool-eval.md), 사용자가 실행). 묶음 구성은 그 결과로
  고칠 수 있다(`toolSet` 속성). 토큰 수는 어림이라 실제 토크나이저와 다르다(한국어는 넉넉히 셈). 도구 없음 방식은 vLLM(Chat Completions 엔진)의 400만
  알아본다. Ollama에서 도구를 모르는 모델("does not support tools")은 지금처럼 오류로 끝나고, 사용자가 다른 모델을 고르거나 Ollama 설정에서 바꿔야 한다.
  OpenAI·Gemini는 컨텍스트 크기 칸이 없어(호스팅 모델) 설정값이 없으면 128,000으로 본다. `findTools`는 영어 낱말로 찾으므로 모델이 한국어 낱말만 주면
  분야 목록으로 답한다. 실제 vLLM 서버와 사내 소형 모델로는 확인하지 않았다(회사 vLLM 정보 없음).
- **열린 에이전트 트랜잭션과 앱 종료**: 2026-11-07 확인 결과 `ExitTransactionCheck`는 쿼리 관리자만 보아 에이전트 격리 문맥의 트랜잭션을 찾지 못했다
  (에이전트는 JDBC 연결을 직접 쓴다). 이제 `AgentSql.hasPendingTransaction`도 보고 같은 질문으로 끝낸다([auto-update.md](auto-update.md) "끝낼 때 트랜잭션").
- **트랜잭션 질문에 롤백으로 답하면 원래 도구는 실행된다**: 1단계 실행기 규칙이다(롤백 후 `commitTransaction`은 커밋할 것이 없어 "완료"). 이 계약은 그대로 두고
  결과와 카드에 답을 남긴다(위 "트랜잭션 안전"의 `transactionsBeforeCall`). `commitTransaction`에 Roll back을 고를 수 있는 것 자체(질문이 Commit / Roll back /
  Cancel 셋으로 고정)는 계약의 문제로 남긴다: 도구별로 답을 줄이거나(commitTransaction은 Commit / Cancel), 도구가 답과 다르면 실행하지 않게 하려면 계약을 바꿔야 한다.
- **분류기는 보수적이다**: JSQLParser가 해석하지 못하는 읽기 문장(예: PostgreSQL `make_interval(secs => …)`)은 WRITE로 본다. 모델은 `runReadOnlySql` 대신 `runSql`을 써야 하고
  사용자가 확인한다. SQL Server는 읽기 전용 트랜잭션이 없어 두 번째 겹이 롤백뿐이다.
- **Query Library 읽기 코드 중복**: `LibraryReader`는 Swing `QueryLibraryFile`의 머리말 형식을 따로 읽는다. 저장소가 모델 번들로 옮겨지면 지운다.
- **에이전트 문맥 수**: 연결·DB마다 최대 4개의 서버 세션을 더 연다. 연결을 끊으면 닫힌다.
- **다른 비밀은 아직 평문이다**: 전역 네트워크 프로필 비밀, 전역 프록시 사용자·비밀번호, Maven 저장소 인증은 작업 공간 설정 파일
  (`com.seizia.chromite.ui.swing.prefs`의 `//secrets/…`)에 평문으로 남는다(위 "API 키 저장"). 연결 비밀번호의 `credentials-config.json`은 소스에 있는 고정 키
  (`BaseProjectImpl.LOCAL_KEY_CACHE`)로 암호화되어 OS 계정으로 보호되지 않는다. 둘 다 이번 범위 밖이다. **2026-11-07 후속에서 둘 다 OS 보안 저장소로 옮겼다**
  ([credential-storage.md](credential-storage.md)).
- **AI 키 저장의 남은 점**: macOS 키체인은 Mac이 없어 실행해 보지 못했다(첫 접근 때 키체인 허용 질문이 뜰 수 있다). OpenAI 계정 토큰(`AIUtils.setAccountToken`)은
  OS 제공자가 없으면 여전히 전역 컨트롤러(평문)에 쓴다. Swing 화면에는 계정 로그인이 없어 지금은 쓰이지 않는다. 같은 OS 사용자의 작업 공간들은 제공자 키를
  함께 쓰므로, 0.1.0에서 작업 공간마다 다른 키를 두었다면 먼저 연 작업 공간의 키가 옮겨져 모두에 쓰이고 다른 작업 공간의 평문 키는 지워진다(다시 넣어야 한다).
- **PostgreSQL·MySQL 백업·복원 도구의 남은 점(2026-11-08)**: ~~연결의 로컬 클라이언트는 아직 백업·복원 대화상자를 실행할 때만 저장된다(연결 편집 화면에
  칸이 없음, swt-parity D-db-specific P6).~~ 2026-11-08 연결 편집기의 연결 속성에 로컬 클라이언트 칸을 더했고 도구의 안내 문구도 그 칸을 가리킨다
  ([swing-database-tasks.md](swing-database-tasks.md)). ~~확인 대화상자의 미리 보기 제목은 "실행할 SQL"인데 이 도구에서는 명령줄이다. 기존 `backupDatabase`를
  SQL Server가 아닌 연결로 MCP에서 부르면 확인을 먼저 묻고 실행할 때 거절한다(미리 보기가 DB 종류를 보지 않음, 채팅은 도구 고르기로 보내지 않음).~~
  2026-11-08 고침(evening #27): 미리 보기 제목을 SQL·명령줄 모두에 맞는 "실행할 내용"(What will run)으로 바꿨고, `backupDatabase`의 미리 보기가
  `restoreDatabase`처럼 먼저 연결 종류를 확인해 묻기 전에 거절한다. MySQL 8 서버와 mysqldump 8은 이 PC에 없어 MariaDB로만 확인했다.
- **진단 SQL은 버전에 따라 다르다**: 시험은 PG 17, SQL Server 2022, MariaDB 11, Oracle 23ai에서만 했다. MySQL 8 블록(`performance_schema.data_lock_waits`, `error_log`)은 MySQL 서버가 없어 실행해 보지 못했다.

## 변경 이력
- 2026-11-12 앱 변경 #91(`chromite/agent-tools-r2`): "단일 연결" 연결의 공유 세션에서 롤백에 기대는 호출(읽기가 아닌 문장의 실제 계획, Oracle
  EXPLAIN PLAN)을 거절, 문장이 여럿인 글은 공유 세션에서 서버 검사(`validateSql`)를 하지 않음, 분류기가 문장이 여럿인 글을 읽기로 보지 않음.
  위 "공유 세션에서 롤백에 기대는 호출". 도구 설명은 그대로. 회귀 테스트는 실행하지 않았다.
- 2026-11-12 앱 변경 #83 검토 뒤 고침: "단일 연결" 연결의 공유 세션에 남은 트랜잭션을 DB에 물어 결과(`transactionOpen`, 안내)와 쿼리 창 상태 줄에 보임,
  오류 결과가 1 MB를 넘으면 큰 멤버만 줄임. 위 "배치의 부분 실패". 회귀 테스트는 실행하지 않았다.
- 2026-11-12 앱 변경 #83(`chromite/agent-runsql-batch`): SQL 도구가 배치 중간 오류에서 결과 읽기를 멈추지 않고, 오류 결과에 모든 결과·행 수·오류와
  오류 뒤 실행 여부, 호출 뒤 트랜잭션 상태를 담음. `runScript`는 실패한 문장이 있으면 오류. 위 "배치의 부분 실패". 도구 설명은 그대로. 회귀 테스트는 실행하지 않았다.
- 2026-11-12 제공자 설정 안내의 글자 단추를 톱니 아이콘 단추로, 글 오른쪽 첫 줄 가운데에(사용자 결정, 앱 변경 #72, `chromite/visual-recheck-fixes`).
  위 "채팅 도구 창"의 "제공자 설정 안내". 회귀 테스트는 실행하지 않았다.
- 2026-11-12 화면 재점검(앱 변경 #71 g, `chromite/visual-recheck-fixes`): `alwaysConfirmed` 속성, 권한 정책 표의 "늘 확인", 위 "설정과 판정 순서"의 "늘 확인하는 도구".
  확인: 표에서 commitTransaction "쓰기 / 상속 / 늘 확인", rollbackTransaction "읽기 / 상속 / 늘 확인"(한국어·영어). 회귀 테스트는 실행하지 않았다.
- 2026-11-12 화면 재점검(앱 변경 #71 f, `chromite/visual-recheck-fixes`): 제공자 설정 안내의 글이 패널 폭을 씀(단추 자리는 #72에서 톱니 아이콘으로), 두 안내를 `WrappingNote`로.
  위 "채팅 도구 창". 회귀 테스트는 실행하지 않았다.
- 2026-11-12 앱 변경 #64 도구 묶음(`chromite/agent-tool-sets`): 최소 13·핵심 44·전체 101 묶음(`plugin.xml` `toolSet`, 최소는 `shortDescription`),
  메타 도구 `findTools`와 대화별 찾은 도구, 제공자별 "채팅 도구 묶음"·Ollama/vLLM 컨텍스트 크기 설정, "도구를 모두 보냄" 지움(전체로 옮김), 보내기 전 크기
  확인, 도구 없음 방식과 채팅 창 안내, vLLM Test가 `max_model_len`으로 컨텍스트 크기를 채움. 위 "도구 묶음".

- 2026-11-12 앱 변경 #53(`chromite/tabs-toolbar`): 주 도구 막대의 Chromite Agent 단추를 단추 묶음의 마지막(Find 뒤)으로. 위 창 배치 설명을 고침.
- 2026-11-12 앱 변경 #61(`chromite/agent-tool-review`): 도구 선택 정적 점검. 헷갈리는 묶음 15개, 도구 설명 99개와 인수 설명 14개를 "하는 일·언제·대신 쓸 도구"
  모양으로 고침, `part`·`mode`에 `validValues`, 이름 바꾸기·합치기·빼기 제안(사용자 결정), 요청 크기 줄이기 제안(권함: 핵심 묶음 + 도구 찾기),
  평가 세트 [agent-tool-eval.md](agent-tool-eval.md). 도구 ID·인수 이름·분류·도구 수(101)는 그대로. 위 "도구 선택 점검".
- 2026-11-11 앱 변경 #34·#35(`chromite/agent-providers`): vLLM·Google Gemini 제공자. OpenAI 호환 Chat Completions 엔진(스트리밍, 도구 호출,
  thought signature 돌려보내기), 도구 호출을 못 하는 vLLM 서버의 안내(채팅 오류와 Test), 모델 선택·Providers 탭·키 저장. 위 "Chat Completions 엔진".

- 2026-11-09 에이전트 문맥의 열린 트랜잭션 확인을 쿼리 창과 같은 방언 질의로(`chromite/txn-real-state`, [idle-transactions.md](idle-transactions.md) "트랜잭션 상태"):
  SELECT만 한 트랜잭션(SQL Server 암시적, MySQL `INNODB_TRX` 행)을 열림으로 보던 것과 자동 커밋 문맥의 `BEGIN TRAN`을 놓치던 것을 고침, 자동 커밋 트랜잭션은
  `COMMIT`/`ROLLBACK` 문장으로 끝냄, `getTransactionStatus`에 `otherUnverifiedTransactions`. 위 "트랜잭션 안전".
- 2026-11-08 앱 변경 #6·#12(`chromite/ui-layout`): Agent 창이 기본 배치에서 보이고 오른쪽 열 아래(Query Library 아래), 주 도구 막대 단추는 오른쪽 끝에 아이콘 + 글자.
  시작할 때부터 보이는 Agent 창에서 공급자 미설정 안내가 안 보이던 문제: `showChat`이 모델 선택을 채운 뒤 안내를 다시 정함. 위 "채팅 도구 창".
- 2026-11-08 Chromite Agent 창의 설정 단추 두 개가 Options를 Chromite Agent 페이지(Providers)로 엶, Providers 양식 폭(app round 1 #13·#8,
  위 "설정 열기").
- 2026-11-08 앱 변경 #14·#15·#16과 후속 39(`chromite/agent-panel-2`): 제공자를 함께 고르는 모델 선택 하나를 보내기 줄 왼쪽에(위쪽 제공자·모델 줄 지움), 맨 위 대화
  콤보를 지우고 대화 기록 팝업(거르기, 이동, 삭제), 편집기 도구 `listQueryWindows`·`getQueryWindowText`·`editQueryWindow`(확인 미리 보기 종류 `TEXT_CHANGE`),
  OpenAI temperature를 명시적으로 정했을 때만, 채팅 상태 줄에 차례의 토큰 사용량, 404 오류 글에 "not found". evening #27의 Agent 부분 2개는 이미 고쳐져 있어 확인만
  했다(`chromite/followups-3`). 위 "채팅 도구 창", "편집기 도구", "엔진", "실제 API 확인".
- 2026-11-08 실제 Anthropic API 확인(`chromite/agent-real-api-test`): Anthropic 요청에 temperature를 명시적으로 정했을 때만 넣음. 위 "실제 API 확인".
- 2026-11-08 AI 엔진을 닫으면 받는 중인 응답을 끝냄: 채팅의 중지가 답이 끝날 때까지 기다리던 것. 위 "실제 API 확인" 2.
- 2026-11-08 감사 기록에 트랜잭션 질문의 Commit/Roll back 답(`transactionsBeforeCall`)을 남김. 위 "감사 기록", "실제 API 확인" 3.
- 2026-11-08 확인 대화상자의 미리 보기 제목을 "실행할 내용"(What will run)으로(명령줄 미리 보기도 있음), `backupDatabase`가 SQL Server가 아닌 연결을
  확인 전에 거절(evening #27 2·3부, `chromite/followups-3`). 위 "위험과 남은 일".
- 2026-11-08 `dumpDatabase`·`restoreDump`의 로컬 클라이언트 안내가 연결 편집기의 로컬 클라이언트 칸도 가리킴([swing-database-tasks.md](swing-database-tasks.md)).
- 2026-11-08 `getActualPlan`(Oracle)의 첫 키워드 찾기: 앞 공백·`--` 주석·블록 주석은 반복문(`PlanTools.skipLeadingComments`)으로 건너뛰고 정규식은 그 자리의
  키워드만 본다. 주석 본문 패턴의 그룹이 글자마다 반복되어 약 2,000자 넘는 머리 주석에서 `StackOverflowError`가 났다(ReDoS 수정 뒤 남은 문제).
  동작은 같다(옛 정규식과 무작위 입력 30만 개 비교). 회귀 테스트 `PlanToolsOracleHintTest`(10만 자 주석·공백, ReDoS 입력 3만 번),
  [regression-tests.md](regression-tests.md).
- 2026-11-08 1b단계에서 미룬 PostgreSQL·MySQL/MariaDB 백업·복원 도구 `dumpDatabase`(W, 파일)·`restoreDump`(D): 로컬 클라이언트 도구(`tasks.native`)를
  Swing 대화상자와 같은 경로(`model.admin` `NativeBackupRestore`)로 실행, 연결의 로컬 클라이언트 설정만 씀, 덮어쓰지 않음, 취소 시 만든 파일 지움.
  도구 진행 알림(`AIToolCallContext.reportProgress`, `AIToolExecutor.execute`의 진행 수신자, `AIAgentChat.Entry.getProgress`, `Listener.toolProgress`, 도구 카드
  "실행 중: …"). `AdminTools`의 SQL Server 전용 오류가 새 도구를 알려 줌. 위 "PostgreSQL·MySQL 백업·복원 도구", "1b단계 (미룬 것)".
- 2026-11-08 1b단계 도구: SQL Server Agent 작업 도구 6개(`AgentJobTools`, `ext.mssql` 에이전트 모델), Oracle 실제 계획·교착 상태(경고 로그 + 추적
  파일 그래프)·루틴 문장 통계·누락·미사용 인덱스(팩 뷰 없음, 권한 부족 시 필요한 GRANT), Query Store 도구 3개(꺼져 있으면 문장으로 알림), `whatIfIndex`
  (hypopg 있을 때만, 없으면 이유), `probePermissions` 검사 추가, Oracle 시간대 시각 JSON 수정, Oracle 계획 해석을 `PlanParser.parseOracle`로, 플레이북 3개에
  새 도구. 위 "1b단계 도구", "1b단계 (미룬 것)".
- 2026-11-08 요청 크기(후속 과제 #76): 채팅 요청에 연결의 DB 종류와 정책에 맞는 도구만 보내기(`AgentTool.supports`, `AIAgentToolSelection`, "도구를 모두
  보냄" 설정), 기록 크기 예산(`AIAgentChat.toModelMessages`), 빠진 것을 시스템 프롬프트로 알림. 위 "요청 크기".
- 2026-11-08 도구 뒤 글이 첫 조각에서 멈추던 문제: 채팅 창이 다시 그릴 글 항목을 모음으로 둠(위 "글 조각 그리기").
- 2026-11-08 에이전트의 열린 트랜잭션 안 호출은 PostgreSQL 통계 스냅숏을 먼저 비움(위 "실행 문맥", [swing-admin-tools.md](swing-admin-tools.md) "모니터링 연결의 커밋 방식").
- 2026-11-07 쿼리 창마다 별도 연결: 새 대화·"이 쿼리에 대해 묻기"의 현재 데이터베이스를 쿼리 창의 실행 문맥에서 읽음(`AiAssistantPanel.getCurrentDatabase(SqlEditorPanel)`).
- 2026-11-07 모델 번들(`model.ai.tools`)의 남은 옛 이름을 Chromite Agent로: 제공자 미설정 오류의 경로 "Options > Chromite Agent > Providers"
  (`AIAgentProviders`), 요청 한도 오류 "Chromite Agent made … requests in one turn …"(`AIAgentChatRunner`, 둘 다 영어 고정 글이라 영어만),
  채팅 시스템 프롬프트 첫 줄 "You are Chromite Agent, the database agent inside Chromite, …", 격리 문맥 이름 `AgentSql.CONTEXT_NAME`
  "Chromite AI agent" → "Chromite Agent"(위 "실행 문맥"에 DBA가 거르는 법). 위 "명칭"을 이에 맞춰 고치고 "위험과 남은 일"의 해당 항목을 지움.
- 2026-11-07 기능의 공식 이름을 Chromite Agent로 정함(사용자 결정, 위 "명칭"). Swing 화면 문구(`AiMessages`, `UpdateMessages`의 `exit_txn_agent_item`,
  영어·한국어), 개인정보 페이지의 절 이름과 본문, 이 문서의 현재 설명(제목, "2단계 구현")을 바꿈. "2단계" 확인 표와 변경 이력의 옛 이름은 그때의 기록이라 그대로 둠.
- 2026-11-07 전역 비밀 컨트롤러·연결 비밀번호 이전([credential-storage.md](credential-storage.md))에 맞춰 "API 키 저장"과 "위험과 남은 일"에 후속 안내를 더함(AI 키 동작은 그대로).
- 2026-11-07 AI 키를 OS 보안 저장소로: `AIUtils`의 AI 비밀을 Eclipse secure storage + OS 제공자에 저장하고 0.1.0의 평문 값을 옮김, 저장소 실패 시 평문 대체 없이
  설정 화면에 안내(`ai_options_save_failed_secure_storage`), `AIAgentProviders.save`가 비밀을 먼저 저장, `SecurePreferencesSecretController`가 제공자의 unchecked
  예외를 `DBSecurityException`으로. 2단계 확인 표의 "작업 공간 전체 검색 0건" 정정. 위 "API 키 저장".
- 2026-11-07 후속 과제 #77: 트랜잭션 질문의 Commit/Roll back 답을 결과(`transactionsBeforeCall`)와 채팅 카드에 남김.
  확인: PostgreSQL 수동 커밋 연결, 가짜 Messages API로 scenario-tx(INSERT → commitTransaction)에 롤백 → 카드 "열린 트랜잭션 1개 롤백 → 완료, …"
  (영어 "Rolled back 1 open transaction(s) → Done, …", 한국어 라이트·다크), 모델에 간 결과에 `"action":"ROLLED_BACK"`, 테이블에 행 없음(psql).
- 2026-11-07 후속 과제 #78: 앱 종료 질문(`ExitTransactionCheck`)이 에이전트 격리 문맥의 트랜잭션도 찾음([auto-update.md](auto-update.md) "끝낼 때 트랜잭션").
- 2026-11-07 2단계 구현과 확인: AI Assistant 도구 창(대화, 제공자·모델·연결·데이터베이스, 스트리밍 Markdown, 도구 카드, 중지, 플레이북, Ask AI), 확인 대화상자
  (Allow/Deny, Commit/Roll back/Cancel, WRITE "항상 허용"), 편집기 연결, Options > AI Assistant(Providers, Tool Policy, MCP Server, Audit Log), Query Library 기준값 편집.
  모델 쪽 `model.ai.tools.chat` 패키지, `AIToolPolicy.resolve(Settings, …)`·`setSettings`. 위 "2단계 구현", "위험과 남은 일".
- 2026-11-07 구현과 확인: 도구 89개(카탈로그 86 + 기존 3), 진단 SQL 29개, MCP 서버, Anthropic 엔진. 에이전트 문맥 풀, `rollbackTransaction`을 READ로
  (운영 연결에서도 막히지 않고 늘 확인), 위 "확인"과 "위험과 남은 일".
- 2026-11-07 사용자 규칙 "트랜잭션 안전" 반영: `AIToolTransactionGuard`, 확인 답 Commit/Roll back/Cancel, 수동 커밋 연결에서 트랜잭션을 열어 두는 쓰기,
  `getTransactionStatus`·`commitTransaction`·`rollbackTransaction` 도구.
- 2026-11-07 문서 작성(1단계 계약): 구조, 도구 카탈로그, 권한 정책, MCP 서버, 보안, 엔진, 플레이북 6개, Query Library 기준값, 2단계 인터페이스, 1b단계.
