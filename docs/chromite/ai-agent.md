# AI 에이전트: 도구 카탈로그, 권한 정책, 로컬 MCP 서버, AI 엔진, 채팅 화면 (1·2단계)

## 목적

Chromite 안에서 AI 모델이 DBA처럼 일하게 한다. 사용자가 "이 쿼리 느린데 튜닝해 줘", "지금 DB 상태 어때?", "왜 막혀 있어?",
"이 프로시저 로직 검토해 줘" 같은 일을 맡기면, 모델이 도구를 골라 조합해 근거를 모으고 답한다. 정해 둔 시나리오만이 아니라
아무도 미리 적지 않은 DBA 문제도 도구를 조합해 풀 수 있어야 한다(사용자 결정, 2026-11-07 ~05:20).

같은 도구 카탈로그를 두 곳에서 쓴다.

- (a) 앱 안의 AI 채팅(2단계, 아래 "2단계 구현").
- (b) 이 PC에서만 접속할 수 있는 로컬 MCP 서버. Claude Code 같은 외부 에이전트가 Chromite의 연결로 일한다.

1단계(모델 계층)와 2단계(채팅 화면, 설정 화면, 확인 대화상자)의 **계약**이다. 코드와 다르면 코드가 사실이고, 이 문서를 고친다.

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
| `plugins/com.seizia.chromite.model.ai.tools` (새 번들) | 도구 구현(`...model.ai.tools`: `ConnectionTools`, `MetadataTools`, `QueryTools`, `PlanTools`, `DataTools`, `DdlTools`, `TransferTools`, `AdminTools`, `LibraryTools`, `DiagnosticSqlTool`, 공통 `AgentTool`, `AgentSql`, `AgentSqlClassifier`, `AgentJson`, `AgentNames`, `AgentValues`, `PlanParser`, `DiagnosticScript`, `DiagnosticPostProcessor`, `PermissionProbe`, `LibraryReader`, `ThresholdEvaluator`), 진단 SQL 자원(`.../tools/diag/<도구>.sql` 29개), MCP 서버(`...model.ai.tools.mcp`: `AIMcpServer`, `AIMcpSettings`, `AIMcpPluginService`, `AIAgentPlaybooks` + `playbooks/*.md`). SQL Server 백업·복원 모델(`model.admin`)과 `ext.mssql`에 의존하므로 `model.ai`와 나눴다 |
| `features/com.seizia.chromite.swing.feature` | 새 번들을 Swing 제품에 넣는다 |

모델 번들이라 SWT·Swing에 의존하지 않는다(AGENTS-Architecture.md). 화면이 필요한 곳(확인 대화상자, 쿼리 창 열기)은 인터페이스만 두고
2단계의 Swing 코드가 구현해 `AIAgentServices`에 등록한다.

### 실행 문맥

- 도구는 사용자의 쿼리 창이나 탐색기 메타데이터 문맥을 쓰지 않는다. 연결(그리고 `database` 인수)마다 **에이전트 전용 격리 문맥**
  (`DBSInstance.openIsolatedContext`, 이름 "Chromite AI agent", 서버 세션의 프로그램 이름에도 보임)의 작은 풀(최대 4개)을 쓴다. 이유:
  - 에이전트가 자동 커밋이나 읽기 전용 상태를 바꿔도 사용자의 트랜잭션에 영향이 없다.
  - 사용자의 열린 트랜잭션에 에이전트 문장이 섞이지 않는다.
  - 오래 걸리는 진단 쿼리가 탐색기의 메타데이터 읽기를 막지 않는다.
- 한 문맥에서는 한 번에 한 호출만 실행한다(문맥별 잠금). 바쁜 문맥이 있으면 풀에서 다른 문맥을 쓰거나 새로 연다. 문맥이 하나뿐이던 첫 구현에서는
  잠금에 걸린 에이전트 DDL(블로킹된 `CREATE INDEX`) 하나가 그 연결의 진단·`cancelQuery`까지 막았다(아래 "확인"). 쓰기는 열린 에이전트 트랜잭션이 있는
  문맥을 이어서 써야 하므로 그 문맥을 기다리고, 읽기는 그 문맥이 비어 있으면 그것을(커밋 전 변경이 보이게), 아니면 다른 문맥을 쓴다.
- 연결이 "단일 연결 사용"(`isForceUseSingleConnection`)이면 격리 문맥을 열 수 없어 기본 문맥을 쓴다.
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
- 단계: 1 = 이번 작업, 1b = 다음 작업(아래 "1b단계").

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
| `runSql` | 아무 문장 하나 실행(갱신 수 또는 결과). 연결의 커밋 방식 그대로: 수동 커밋 연결이면 트랜잭션을 열어 둔 채 `transactionOpen: true`를 알린다 | W (R→W/D) | `connection`, `sql` | `updateCount` 또는 표, `transactionOpen` | G | 1 |
| `runScript` | 스크립트(여러 문장, 드라이버의 구분자 규칙). 커밋 방식은 `runSql`과 같다 | W (W/D) | `connection`, `script`, `stopOnError` | 문장별 결과, `transactionOpen` | G | 1 |
| `getTransactionStatus` | 에이전트 문맥의 열린 트랜잭션(DB에 직접 확인: `@@TRANCOUNT`, `txid_current_if_assigned()`, `INNODB_TRX`, `DBMS_TRANSACTION`)과 이 연결의 쿼리 창 트랜잭션 | R | `connection` | 객체 | MS PG MY OR, G | 1 |
| `commitTransaction` | 에이전트의 열린 트랜잭션 커밋. **자동 실행 설정과 상관없이 늘 확인** | W | `connection` | 결과 | G | 1 |
| `rollbackTransaction` | 에이전트의 열린 트랜잭션 롤백. 늘 확인. 운영 연결에서도 에이전트가 자기 일을 되돌릴 수 있어야 하므로 분류는 READ(막히지 않음)이고, 되돌릴 것이 있으면 트랜잭션 안전 규칙이 늘 묻는다 | R | `connection` | 결과 | G | 1 |
| `cancelQuery` | 에이전트가 실행 중인 문장 취소 | R | `connection` | 취소한 수 | G | 1 |
| `explainQuery` | 예상 실행 계획을 **구조화 JSON**으로(연산자, 비용, 예상 행, 경고, 누락 인덱스, 매개변수 컴파일 값) | R | `connection`, `sql`, `database` | `plan` | MS PG MY OR, G(계획기) | 1 |
| `getActualPlan` | 실제 실행 계획과 실행 통계(실제 행, 반복, 시간, 논리 읽기·버퍼, SET STATISTICS IO/TIME, EXPLAIN ANALYZE BUFFERS) | R→W | `connection`, `sql`, `database` | `plan`, `runtime` | MS PG MY | 1 (OR 1b) |
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
| `getDeadlocks` | 최근 교착 상태를 구조화 JSON으로(MS: system_health 교착 그래프 XML 해석, MY: InnoDB 최신 교착 상태, PG: 횟수와 로그 안내) | R | `connection`, `limit` | `deadlocks[]` | MS MY PG | 1 (OR 1b) |
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
| `backupDatabase` | 백업(MS: T-SQL `BACKUP DATABASE`, `model.admin`의 `SQLServerBackupSettings`) | W | `connection`, `database`, `file`, `type`, `copyOnly` | 메시지 | MS | 1 (PG MY 네이티브 도구 1b) |
| `restoreDatabase` | 복원(MS, `SQLServerRestoreSettings`) | D | `connection`, `database`, `file`, `replace` | 메시지 | MS | 1 |
| SQL Server Agent 작업 관리(시작·중지·사용 설정·단계) | | W | | | MS | 1b (아래) |

### 성능 (`agent.performance`)

| ID | 목적 | 분류 | 주요 매개변수 | 결과 | DB | 단계 |
|---|---|---|---|---|---|---|
| `getTableStatistics` | 행 수, 테이블·인덱스 크기, 통계 마지막 갱신, 표본 행, 갱신 후 변경 수, 데드 튜플·자동 분석 시각 | R | `connection`, `table` 또는 `schema` | `tables[]`, `statistics[]` | MS PG MY OR | 1 |
| `getIndexUsage` | 인덱스 사용 통계(탐색·스캔·조회·갱신), 잠금 에스컬레이션 시도 | R | `connection`, `table` 또는 `schema` | `indexes[]` | MS PG MY OR | 1 |
| `getMissingIndexes` | 누락 인덱스 제안(MS DMV, PG: 순차 스캔이 많은 큰 테이블) | R | `connection`, `database` | `suggestions[]` | MS PG | 1 (MY OR 1b) |
| `getUnusedIndexes` | 쓰이지 않는·중복 인덱스 | R | `connection`, `schema` | `unused[]`, `duplicates[]` | MS PG MY | 1 (OR 1b) |
| `getIndexFragmentation` | 조각화·부풀음(MS `dm_db_index_physical_stats` LIMITED, PG pgstattuple 있으면 그것, 없으면 추정, MY `data_free`) | R | `connection`, `table` 또는 `schema` | `indexes[]` | MS PG MY | 1 |
| `getRoutineStats` | 루틴의 실행 통계와 **문장별 통계**(MS: 문장 오프셋 → 줄 번호, PG: `pg_stat_user_functions` + `pg_stat_statements`, MY: performance_schema 프로그램 요약) | R | `connection`, `name` | `routine`, `statements[]` | MS PG MY | 1 (OR 1b) |
| `getCachedPlans` | 루틴·쿼리 텍스트의 캐시된 계획을 문장별 구조화 JSON으로(MS) | R | `connection`, `name` 또는 `contains`, `top` | `plans[]` | MS | 1 (PG는 계획 캐시가 없어 `explainQuery`로 대신) |
| `whatIfIndex` | 가상 인덱스로 계획 비교(PG hypopg 있으면) | R | | | PG | 1b |

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

### 운영 연결

Chromite는 연결 종류(`DBPConnectionConfiguration.getConnectionType()`)로 운영을 표시한다. 기본 종류 Production(`DBPConnectionType.PROD`, ID `prod`)은
"SQL 실행 확인"(`confirmExecute`)이 켜져 있고 자동 커밋이 꺼져 있다. 연결 색(`getConnectionColor`)은 연결 종류 색을 덮어쓰는 표시일 뿐이라 기준으로 쓰지 않는다
(Swing `ConnectionColors.getColor`도 색이 없으면 종류 색을 쓴다).

`AIToolPolicy.isProduction(연결)` = 연결 종류 ID가 `prod`이거나, 사용자 정의 종류의 `confirmExecute`가 켜져 있다.
사용자가 만든 운영용 종류도 대개 "SQL 실행 확인"을 켜므로 함께 잡는다.

### 확인

- CONFIRM이면 실행기가 `AIAgentServices.getConfirmationHandler()`의 `confirm(monitor, AIToolConfirmationRequest)`를 부른다(답: `AIToolConfirmationAnswer`). 이 호출은 작업 스레드에서
  결과가 나올 때까지 막는다(화면 쪽이 EDT로 넘겨 대화상자를 띄우고 기다린다).
- 요청에는 클라이언트(CHAT/MCP), 도구 ID·이름, 실제 분류, 연결, 비밀을 지운 인수, **미리 보기**(실행할 SQL·DDL 전체), 이유가 있다.
- 처리기가 없으면(화면 없음, 헤드리스) **거부**한다(`DENIED`, "no confirmation UI"). MCP의 기본값도 같다: 실행 중인 앱의 대화상자, 없으면 거부.
- 확인 대화상자의 "이 연결에서 항상 허용"은 2단계가 `AIToolPolicy.setPermission(연결 ID, 도구 ID, AUTO)`로 구현한다.

### 트랜잭션 안전 (사용자 규칙, 2026-11-07)

어떤 기능도 열린 트랜잭션을 조용히 커밋·롤백하지 않는다. 트랜잭션 방식 바꾸기, 연결 끊기·세션 닫기, 연결·DB 바꾸기 전에 커밋 안 된 트랜잭션을 찾고
사용자에게 **Commit / Roll back / Cancel**을 묻는다. 에이전트 도구에는 이렇게 적용한다.

- **실행기가 막는다**: 도구가 `AIToolTransactionGuard`를 구현하면 실행기가 실행 전에 `findPendingTransactions`를 부른다. 하나라도 있으면
  정책이 AUTO여도 확인 처리기에 묻는다(`AIToolConfirmationRequest.pendingTransactions`). 답이 COMMIT/ROLLBACK이면 `resolvePendingTransactions`로
  처리한 뒤 실행하고, DENY(또는 이 질문에 대한 ALLOW)는 취소다. 처리기가 없으면 거부한다. 정책의 BLOCKED는 그보다 먼저 막는다.
- **이 규칙이 걸리는 도구**:

  | 도구 | 찾는 트랜잭션 | Commit 가능 |
  |---|---|---|
  | `disconnect` | 이 연결의 모든 실행 문맥(쿼리 창은 QM `QMUtils.isTransactionActive`, 에이전트 문맥은 아래 표시) | 예 |
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
| `durationMs`, `error` | 실행 시간, 오류 문구 |

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
| OpenAI (`openai`) | 기존. Responses API(`OpenAiUtils.createOpenAiRequest`)가 `AIFunctionDescriptor` 목록을 `function` 도구로 보낸다. 이 작업의 도구도 같은 경로로 간다 | 예 |
| Ollama (`ollama`) | 이미 `plugin.xml`에 등록되어 있다(브리프의 "등록 안 됨"은 이전 상태). `/api/chat`의 `tools` | 예(응답의 첫 도구 호출) |
| Anthropic (`anthropic`, 새) | Messages API `POST /v1/messages`, `anthropic-version: 2023-06-01`, `x-api-key`. 모델: `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`(목록 API `GET /v1/models`가 되면 그 목록). 도구: `tools[{name, description, input_schema}]`, 응답 `tool_use` → `AIFunctionCall`(메타데이터 `tool_use_id`), 결과는 `tool_result` 블록 | 예 |

API 키는 `AIUtils.setSecretValue`(보안 저장소)에 두고 설정 파일(`ai-config`)에는 쓰지 않는다(`@SecureProperty`). 환경 변수 `ANTHROPIC_API_KEY`도 쓸 수 있다.

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
5. 다시 쓴 쿼리 또는 인덱스 후보를 만든다. `comparePlans`로 전후 비용 비교(인덱스 후보는 PG hypopg가 있으면 1b `whatIfIndex`, 없으면 예상 근거만).
6. **멈춤**: 바꿀 DDL(`generateDdl`)과 기대 효과를 보여 준다. 사용자가 원하면 `createIndex`(WRITE, 확인) 후 `getActualPlan`·`comparePlans`로 효과를 잰다.

### 2. "지금 DB 상태 어때?"

1. `getServerInfo`, `probePermissions`.
2. 권한 되는 것만: `listSessions`, `getBlockingChain`, `getLongRunningQueries`, `listOpenTransactions`, `getWaitStats`, `getTopQueries`,
   `getDatabaseSizes`, `getSpaceUsage`, `getBackupHistory`, `getJobHistory`(실패), `getErrorLog`(최근 오류), `getServerConfig`.
3. 항목별 정상·주의·위험과 근거, 건너뛴 항목과 이유. 아무것도 바꾸지 않는다.

### 3. "지금 이 쿼리가 너무 느린데 원인을 분석해 줘" (변경 없음)

1. 1번 플레이북의 1~4단계(쓰기 없이). 실제 계획은 읽기 전용일 때만.
2. 실행 중이면 `getLongRunningQueries`, `getBlockingChain`, `listLocks`로 블로킹·대기 확인.
3. 매개변수 스니핑: 계획의 매개변수 컴파일 값 vs 실행 값(`explainQuery`/`getCachedPlans`의 `parameters`).
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
| | `AIAgentProviders` | 제공자 3개(`anthropic`, `openai`, `ollama`)의 설정 프로필(프로필 ID = 엔진 ID), 설정 사본·저장(비밀은 보안 저장소), 엔진 만들기(대화의 모델로 사본을 만들어 기본 모델은 그대로), 모델 목록 |
| `model.ai` `AIToolPolicy` | `resolve(Settings, …)`, `setSettings` | 설정 화면이 저장 전 규칙으로 실행기와 같은 판정을 보이고, OK에서 한 번에 저장한다 |
| Swing `com.seizia.chromite.ui.swing` | `AiAssistantPanel` | 도구 창 "AI Assistant"/"AI 도우미" |
| | `AiMarkdownView` | 모델 글의 Markdown(제목, 굵게·기울임, 인라인 코드, 목록, 인용, 표, 가로줄)과 코드 블록(복사, 쿼리 창에서 열기) |
| | `AiToolCard` | 도구 호출 카드(접힘/펼침): 이름, 분류 배지, 상태(실행 중·완료·실패·거부·정책으로 막힘·읽기 전용 연결·취소·잘못된 호출), 시간, 결과 요약, 인수·결과 JSON |
| | `AiConfirmationDialog` (+ `Handler`) | `AIToolConfirmationHandler` 구현. Allow/Deny 또는 Commit/Roll back/Cancel |
| | `AiOptionsPage` | 옵션의 "AI Assistant" 탭: Providers, Tool Policy, MCP Server, Audit Log |
| | `QueryLibraryThresholdsDialog` | Query Library 쿼리의 `@threshold` 편집 |
| | `internal/AiMessages` (+ `_ko`) | 화면 문구 196개 |
| | `MainFrame`, `OptionsDialog`, `QueryLibraryEditor` | 연결 지점만: 도구 창·도구 막대 단추·View 메뉴, 확인 처리기·편집기 연결 등록, 쿼리 창 편집기 메뉴 항목 / 옵션 탭 / Thresholds… 단추 |
| `tools/branding/gen_ui_icons.py` | `ai-assistant` 아이콘 | 말풍선 + 반짝임, 두 팩·두 테마 |

### 채팅 도구 창

- 오른쪽 가장자리에 자기 열로 붙고 기본은 숨김이다. View > AI Assistant(Ctrl+Alt+I)와 주 도구 막대의 토글 단추(Properties 옆)로 연다. 채팅은 Properties 열보다 넓어야 해서
  같은 열에 넣지 않았다(기본 폭 = 오른쪽 열 비율 + 7%).
- 위에서 아래로: 대화 목록(최근 순) + 새 대화·대화 삭제·플레이북·AI 설정 단추 / 제공자(Anthropic Claude, OpenAI, Ollama)와 모델(편집 가능, 알려진 이름 +
  Test로 받은 목록) / 연결과 데이터베이스(편집 가능, 연결된 연결이면 카탈로그·스키마 목록) / 대화 / 입력(Enter 보내기, Shift+Enter 줄 바꿈), 보내기·중지, 상태 줄.
- 새 대화는 활성 쿼리 창의 연결과 현재 데이터베이스(그 연결의 기본 실행 문맥의 기본 카탈로그·스키마, 쿼리 창이 쓰는 문맥)로 시작한다. 마지막에 쓴 제공자·모델은
  이 번들 설정(`ai.chat.engine`, `ai.chat.model.<엔진>`)에 남는다. 빈 대화는 새로 만들지 않고 다시 쓴다.
- 모델 호출과 도구 실행은 작업(`AbstractJob`)에서 하고, 글 조각은 80 ms 타이머로 모아 다시 그린다. 바뀌지 않은 앞 블록은 다시 만들지 않는다.
- 중지: 작업을 취소한다 → 실행기가 스트림 대기를 끝내고 엔진(HTTP 연결)을 닫는다. 실행 중인 도구는 같은 모니터로 취소되고, 열린 확인 대화상자는 닫히며 DENY다.
  실행 중에는 대화·제공자·연결을 바꿀 수 없다.
- 플레이북 6개: 이 쿼리 튜닝, DB 상태 점검, 이 쿼리가 느린 이유, 시스템 점검(쿼리 라이브러리), 블로킹 분석, 프로시저 검토. `AIAgentPlaybooks.getText`의 글에
  `{connection}`(연결 이름), `{sql}`(활성 쿼리 창의 선택 또는 커서의 문장), `{folder}`(묻기, 기본 "Monitoring"), `{object}`(묻기)를 채워 보낸다. 대화에는 짧은 이름만 보이고
  전체 글은 도구 설명에 있다.
- 쿼리 창 편집기의 오른쪽 클릭 메뉴 "Ask AI About This Query"/"이 쿼리에 대해 AI에게 묻기": 선택한 글, 없으면 커서의 문장(자동완성과 같은
  `SQLScriptParser.extractQueryAtPos`, 연결이 없으면 전체 글)을 쿼리 창의 연결로 보낸다. 지금 대화가 비어 있지 않으면 새 대화로 시작한다.
- 대화 기록에는 도구 결과(DB 값)가 들어 있어 작업 공간 밖에 두지 않는다. 로그에는 대화 내용·인수·키를 쓰지 않는다.

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
모델 글의 코드 블록 "쿼리 창에서 열기"도 같은 경로(대화의 연결)다.

### 설정 (Tools > Options > AI Assistant)

| 탭 | 내용 |
|---|---|
| Providers | 제공자마다 API 키(Ollama 제외), 기본 URL(OpenAI는 호환 엔드포인트, Ollama는 호스트), 기본 모델, Test. 키 입력란은 늘 비어 있고 "저장됨" 안내만 보인다(키를 화면에 다시 보이지 않음). 새 키를 넣을 때만 바꾸고, "제거"로 지운다. Test는 저장하지 않은 값으로 모델 목록을 받아 보고 받은 목록을 채팅 모델 선택에 남긴다. 저장은 OK에서 바뀐 제공자만 `AIAgentProviders.saveProperties`(설정 파일 `ai-configuration.json`에는 키가 없고 보안 저장소에만) |
| Tool Policy | 맨 위에 늘 보이는 규칙 3개(운영 연결은 쓰기·위험 막힘, 읽기 전용 연결은 어떤 규칙으로도 바꿀 수 없음, 위험 도구 자동 실행은 연결 하나·도구 하나 규칙으로만). 규칙 대상(모든 연결 = 기본값, 또는 연결 하나, 운영 연결은 "(운영)" 표시), 분류별 규칙 3개, 도구 필터, 도구 표(도구, 그룹, 분류, 규칙: 상속/자동 실행/실행 전 확인/막음, 실제 권한). 실제 권한은 저장 전 규칙으로 `AIToolPolicy.resolve(Settings, …)` 판정(읽기 전용 연결이면 "실행 안 함"). OK에서 `setSettings` |
| MCP Server | 사용 여부, 포트(OK에서 저장하면 서버가 바로 시작·중지·재시작), 상태(실행 중/중지/오류), 주소, 토큰(가림·보기·복사·다시 만들기는 묻고 즉시), Claude Code 명령(`<CHROMITE_MCP_TOKEN>` 자리 표시, 복사) |
| Audit Log | `readRecent(2000)`을 작업에서 읽어 표(시각, 클라이언트, 도구, 분류, 연결, 정책, 결과, ms), 글자·클라이언트·결과로 거르기, 고른 기록의 JSON 전체, 파일 경로 |

### Query Library 기준값 편집

Query Library 쿼리 창 머리말의 Parameters… 옆 "Thresholds…"/"기준값…"(개수 표시). 표(열, 경고, 위험, 범위 any/all/max/min/sum/count, 표시 이름)를 고치고, 조건은
평가기와 같은 문법(`<연산자> <값>`)만 받는다. 기준값은 Swing 파서가 모르는 머리말 줄(`QueryLibraryItem.getExtraHeader()`)로 보관되므로, 저장할 때 파일의 모르는 머리말에서
`@threshold` 블록만 바꾸고 다른 줄은 그대로 둔다. Swing 파서·항목 클래스에 필드를 더하지 않았다(형식의 주인은 에이전트 도구 `LibraryReader`).

### 계약과 다른 점

- **채팅은 `AIAssistantImpl`/`AIChatSession`을 거치지 않는다.** 위 표의 "채팅의 도구 호출" 행은 기존 채팅 경로를 적었지만, 그 경로는 QM 대화 저장소, 할당량, DB 문맥 스냅숏
  (`AIDatabaseContext`, 연결과 논리 데이터 소스를 만든다)을 함께 쓴다. 에이전트 채팅은 연결을 도구 인수로 넘기고 정책·확인·감사는 실행기가 하므로, 실행기를 직접 부르는
  작은 실행기(`AIAgentChatRunner`)를 두었다. 실행기에 채팅 문맥(null)을 넘기지 않는 대신 `connection` 인수를 채운다. 기존 `AIAssistantImpl.callFunction` 경로는 그대로다.
- **데이터베이스 문맥은 시스템 프롬프트로 알린다.** `database` 인수의 뜻이 도구마다 달라(인스턴스 선택, 필터) 자동으로 넣지 않았다.
- **설정 화면은 탭 하나("AI Assistant") 안의 하위 탭 4개다.** 옵션의 위 탭 줄이 넘치지 않게 했다.
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
| 제공자 설정 | Options > AI Assistant > Providers에 키·기본 URL 입력 → Test "Connected: 3 models available". `ai-configuration.json`에는 기본 URL·모델만 있고 키는 없다(작업 공간 전체 검색 0건). 앱을 다시 시작한 뒤에도 채팅이 동작(키를 보안 저장소에서 읽음), 입력란은 "저장됨" 안내만 |
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

## 1b단계 (미룬 것)

| 항목 | 이유 |
|---|---|
| SQL Server Agent 작업 관리 도구(작업 목록·상세·시작·중지·사용 설정·단계 기록) | 모델이 `chromite/sqlserver-agent` 가지에서 만들어지는 중이고 아직 합쳐지지 않았다. 합쳐지면 그 모델로 도구를 만든다. 실패 기록 읽기는 `getJobHistory`(msdb 직접 조회)로 1단계에 있다 |
| PostgreSQL·MySQL 백업·복원 도구 | 로컬 클라이언트 도구(`pg_dump`, `mysqldump`) 실행이 필요하다(`tasks.native`). 경로 설정과 장시간 작업 진행 표시가 필요해 미뤘다 |
| Oracle 실제 계획(`gather_plan_statistics` + `DBMS_XPLAN.DISPLAY_CURSOR`), Oracle 교착 상태(경고 로그·추적 파일), Oracle 루틴 문장 통계, 누락·미사용 인덱스 | 테스트 DB에서 권한(`SELECT_CATALOG_ROLE`) 확인과 출력 해석이 더 필요하다 |
| `whatIfIndex` (PG hypopg, SQL Server는 없음) | 확장 설치가 필요하고 테스트 DB에 없다 |
| SQL Server Query Store 기반 상위 쿼리·계획 회귀 | 1단계는 `dm_exec_query_stats`(플랜 캐시)만 쓴다 |

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
- **MCP는 JDK HTTP 서버**: 새 라이브러리 없이 `com.sun.net.httpserver`(내장 런타임에 `jdk.httpserver` 있음, `runtime.md`). 기존 CLI REST 서버(`BearerRequestHandler`)와 같다.

## upstream 대비 변경점

- 확장점 스키마 `com.seizia.chromite.ai.function.exsd`에 `access`, `writes` 속성 추가.
- `AIFunctionDescriptor`에 `getAccess()`, `getWrites()` 기본 메서드 추가.
- `AIAssistantImpl.callFunction`이 도구 실행기를 거친다(정책·감사).
- 새 패키지 `model.ai.agent`, `model.ai.engine.anthropic`, 새 번들 `model.ai.tools`.

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

- **실제 LLM으로 시험하지 않았다(2단계)**: 유료 키가 없어 가짜 Messages API로만 시험했다. 실제 모델의 도구 선택, 긴 대화의 문맥 크기(도구 86개 정의 + 결과가 매번
  다시 감, 잘라 내기 없음), OpenAI Responses·Ollama 스트림의 도구 호출 흐름은 확인하지 못했다. 대화가 길어지면 제공자의 문맥 한도에 걸릴 수 있다.
- **열린 에이전트 트랜잭션과 앱 종료**: 채팅이 수동 커밋 연결에 남긴 에이전트 트랜잭션은 채팅 카드("트랜잭션 열림")로만 보인다. 앱 종료 시 묻는 열린 트랜잭션 확인
  (`ExitTransactionCheck`)이 에이전트 격리 문맥을 찾는지는 확인하지 않았다.
- **트랜잭션 질문에 롤백으로 답하면 원래 도구는 실행된다**: 1단계 실행기 규칙이다(롤백 후 `commitTransaction`은 커밋할 것이 없어 "완료"). 카드가 "완료"로 보여 헷갈릴 수 있다.
- **분류기는 보수적이다**: JSQLParser가 해석하지 못하는 읽기 문장(예: PostgreSQL `make_interval(secs => …)`)은 WRITE로 본다. 모델은 `runReadOnlySql` 대신 `runSql`을 써야 하고
  사용자가 확인한다. SQL Server는 읽기 전용 트랜잭션이 없어 두 번째 겹이 롤백뿐이다.
- **Query Library 읽기 코드 중복**: `LibraryReader`는 Swing `QueryLibraryFile`의 머리말 형식을 따로 읽는다. 저장소가 모델 번들로 옮겨지면 지운다.
- **에이전트 문맥 수**: 연결·DB마다 최대 4개의 서버 세션을 더 연다. 연결을 끊으면 닫힌다.
- **진단 SQL은 버전에 따라 다르다**: 시험은 PG 17, SQL Server 2022, MariaDB 11, Oracle 23ai에서만 했다. MySQL 8 블록(`performance_schema.data_lock_waits`, `error_log`)은 MySQL 서버가 없어 실행해 보지 못했다.

## 변경 이력

- 2026-11-07 2단계 구현과 확인: AI Assistant 도구 창(대화, 제공자·모델·연결·데이터베이스, 스트리밍 Markdown, 도구 카드, 중지, 플레이북, Ask AI), 확인 대화상자
  (Allow/Deny, Commit/Roll back/Cancel, WRITE "항상 허용"), 편집기 연결, Options > AI Assistant(Providers, Tool Policy, MCP Server, Audit Log), Query Library 기준값 편집.
  모델 쪽 `model.ai.tools.chat` 패키지, `AIToolPolicy.resolve(Settings, …)`·`setSettings`. 위 "2단계 구현", "위험과 남은 일".
- 2026-11-07 구현과 확인: 도구 89개(카탈로그 86 + 기존 3), 진단 SQL 29개, MCP 서버, Anthropic 엔진. 에이전트 문맥 풀, `rollbackTransaction`을 READ로
  (운영 연결에서도 막히지 않고 늘 확인), 위 "확인"과 "위험과 남은 일".
- 2026-11-07 사용자 규칙 "트랜잭션 안전" 반영: `AIToolTransactionGuard`, 확인 답 Commit/Roll back/Cancel, 수동 커밋 연결에서 트랜잭션을 열어 두는 쓰기,
  `getTransactionStatus`·`commitTransaction`·`rollbackTransaction` 도구.
- 2026-11-07 문서 작성(1단계 계약): 구조, 도구 카탈로그, 권한 정책, MCP 서버, 보안, 엔진, 플레이북 6개, Query Library 기준값, 2단계 인터페이스, 1b단계.
