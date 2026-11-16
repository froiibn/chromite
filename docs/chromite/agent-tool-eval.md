# Chromite Agent 도구 선택 평가 세트

## 목적

Chromite Agent는 도구 87개(+ 채팅 전용 `findTools`, 2026-11-15 도구 정리 뒤)를 제공한다. 채팅은 제공자마다 정한 도구 묶음(최소·핵심·전체)만 보내고, 묶음 밖의 도구는
`findTools`로 찾는다([ai-agent.md](ai-agent.md) "요청 크기", "도구 선택 점검", "도구 묶음").
도구가 많으면 모델이 비슷한 도구를 잘못 고를 수 있다. 이 문서는 실제 모델로 그것을 재기 위한 질문 목록이다(앱 변경 #61).

- 실제 모델 실행은 사용자 키와 비용이 드므로 **사용자만 시작한다**. 에이전트는 실제 제공자에 접속하지 않는다(공통 규칙).
- 이 세트로 지금의 도구 설명(2026-11-12 고침)과, 나중에 요청 크기를 줄이는 방식(ai-agent.md "요청마다 보내는 도구 줄이기 제안")을 바꾸기 전과 후로 비교한다.

## 재는 방법

- **환경**: 운영이 아닌 시험 연결. 기본은 SQL Server(표의 DB 칸이 MS), PG는 PostgreSQL(가능하면 hypopg 설치), 빈 칸은 DB 종류와 상관없음.
  Options > Chromite Agent > 제공자의 "채팅 도구 묶음"을 재려는 묶음으로 정하고 결과 기록에 적는다(기본: vLLM·Ollama 최소, 나머지 핵심).
  질문마다 새 대화로 시작한다.
- **findTools**: 묶음에 허용 첫 도구가 없으면 모델은 `findTools`를 먼저 부르거나(최소 묶음은 `runReadOnlySql`로 시스템 뷰를 직접 읽을 수도 있음)
  허용 도구를 이름으로 부를 수 있다. 첫 도구가 `findTools`이고 그 다음 도구가 허용 칸에 있으면 맞음으로 센다(아래 "도구 묶음별 필요").
- **확인 대화상자**: 쓰기 도구의 확인이 뜨면 **취소**한다. 재는 것은 첫 도구 선택이지 실행 결과가 아니다.
- **기록**: 감사 기록(`ai-agent.md` "감사 기록")이나 채팅 카드에서 도구 호출 순서와 인수를 옮겨 적는다.
- **판정** (질문마다):
  - 첫 도구가 "허용" 칸에 있으면 맞음, "틀림" 칸에 있으면 틀림, 둘 다 아니면 중립(따로 센다).
  - 불필요한 호출 수: 답에 쓰이지 않은 호출(같은 도구를 같은 인수로 다시 부른 것 포함).
  - 틀린 인수: 필수 인수 누락, 형식 오류(JSON 배열 대신 쉼표 목록 등), 다른 도구의 인수 이름을 쓴 것.
- **지표**: 첫 도구 정확도(맞음 / (맞음 + 틀림)), 중립 비율, 질문당 평균 호출 수, 틀린 인수 수. 한국어·영어 질문을 나눠서도 본다.
- **질문의 `<SQL>`**: 시험 DB에 있는 실제 문장으로 바꿔 넣는다. 표·프로시저 이름도 시험 DB에 맞게 바꾼다.

## 질문

분야: 조회, 튜닝, 잠금, 권한, 데이터 변경, 백업·작업, 라이브러리, 편집기. 허용·틀림 칸은 첫 도구 기준이다.

| # | 분야 | 질문 | DB | 허용 첫 도구 | 틀린 첫 도구 (이유) |
|---|---|---|---|---|---|
| 1 | 조회 | 이 DB에 어떤 테이블이 있어? | MS | `listTables`, `listSchemas` | `searchObjects`(이름을 모름), `getTableStatistics`, `runReadOnlySql` |
| 2 | 조회 | orders 테이블 구조 보여줘 | MS | `describeTable`, `searchObjects` | `sampleRows`, `getObjectDdl` |
| 3 | 조회 | Which table has a column named customer_id? | MS | `searchObjects` | `listTables`, `describeTable`(추측한 표), `runReadOnlySql` |
| 4 | 조회 | Show me the CREATE statement of the invoices table | MS | `getObjectDdl` | `previewDdl`(변경용), `describeTable` |
| 5 | 조회 | 고객 테이블 데이터 10줄만 보여줘 | MS | `sampleRows`, `runReadOnlySql`, `searchObjects` | `exportData`, `countRows`, `profileColumns` |
| 6 | 조회 | How many rows does orders have? | MS | `countRows`, `getTableStatistics`, `runReadOnlySql` | `sampleRows`, `profileColumns` |
| 7 | 조회 | 지난달 지역별 매출 합계를 구해 줘 | MS | `describeTable`, `searchObjects`, `runReadOnlySql` | `runSql`, `sampleRows`, `getTopQueries` |
| 8 | 조회 | Which procedures and views use the Orders table? | MS | `getObjectDependencies` | `listRoutines`, `describeTable`(외래 키만), `getRoutineSource` |
| 9 | 조회 | usp_GetOrders 프로시저 코드 보여줘 | MS | `getRoutineSource` | `getRoutineStats`, `listRoutines`, `getCachedPlans` |
| 10 | 조회 | What databases are on this server? | MS | `listDatabases`, `getDatabaseSizes` | `listSchemas`, `listConnections` |
| 11 | 조회 | 내 연결 목록 보여줘 | | `listConnections` | `listDatabases`, `listSessions` |
| 12 | 조회 | 이 연결 지금 되는지 확인해 줘 | MS | `testConnection` | `getConnectionInfo`(연결하지 않음), `listConnections` |
| 13 | 조회 | 지금 DB 상태 어때? (대화에 연결 없음) | | `listConnections` | `getServerInfo`, `listSessions`(연결 미정) |
| 14 | 튜닝 | 이 쿼리 너무 느린데 분석해서 튜닝해 줘: `<SQL>` | MS | `explainQuery`, `getServerInfo` | `runReadOnlySql`(실행), `getTopQueries`, `validateSql` |
| 15 | 튜닝 | Why is this query slow? Do not change anything. `<SQL>` | MS | `explainQuery`, `getActualPlan` | `runSql`, `changeSchema`, `comparePlans` |
| 16 | 튜닝 | Which queries use the most CPU on this server? | MS | `getTopQueries`(source cache 또는 queryStore) | `getLongRunningQueries`, `getWaitStats`, `getEditorHistory` |
| 17 | 튜닝 | 지금 오래 돌고 있는 쿼리 있어? | MS | `getLongRunningQueries`, `listSessions` | `getTopQueries`, `getEditorHistory` |
| 18 | 튜닝 | 어제까지 빨랐던 쿼리가 오늘 느려졌어. 원인 찾아 줘 | MS | `getQueryStoreRegressions` | `getLongRunningQueries`, `getEditorHistory`, `explainQuery`(문장 모름) |
| 19 | 튜닝 | Are there missing indexes in this database? | MS | `getMissingIndexes` | `describeTable`, `getIndexUsage`, `getUnusedIndexes` |
| 20 | 튜닝 | 필요 없는 인덱스 정리하고 싶어 | MS | `getUnusedIndexes`, `getIndexUsage` | `changeSchema` dropIndex(먼저 확인), `describeTable` |
| 21 | 튜닝 | orders 테이블 인덱스 목록 보여줘 | MS | `describeTable` | `getIndexUsage`, `getMissingIndexes` |
| 22 | 튜닝 | Is this rewrite faster than the original? A: `<SQL>` B: `<SQL>` | MS | `comparePlans` | `compareQueryResults`(결과 비교), `runReadOnlySql` |
| 23 | 튜닝 | Do these two queries return the same rows? A: `<SQL>` B: `<SQL>` | MS | `compareQueryResults` | `comparePlans`(계획 비교), `runSql` |
| 24 | 튜닝 | 이 인덱스를 만들면 빨라질까? `<SQL>` / `CREATE INDEX ...` | PG | `whatIfIndex` | `changeSchema` createIndex(실제로 만듦), `getMissingIndexes` |
| 25 | 튜닝 | Which statement inside usp_Billing is the slow one? | MS | `getRoutineStats` | `explainQuery`, `listRoutines` |
| 26 | 튜닝 | 통계가 오래된 테이블 있어? | MS | `getTableStatistics` | `countRows`, `getServerConfig` |
| 27 | 튜닝 | 인덱스 조각화 확인해 줘 | MS | `getIndexFragmentation` | `getIndexUsage`, `getUnusedIndexes` |
| 28 | 튜닝 | Check the server configuration for bad settings | MS | `getServerConfig` | `getConnectionInfo`, `getAgentToolPolicy` |
| 29 | 잠금 | 지금 뭔가 막혀서 쿼리가 안 끝나 | MS | `getBlockingChain` | `killSession`, `getDeadlocks`, `cancelQuery` |
| 30 | 잠금 | Were there any deadlocks today? | MS | `getDeadlocks` | `getBlockingChain`(지금), `listLocks` |
| 31 | 잠금 | 세션 53이 잡고 있는 락 보여줘 | MS | `listLocks` | `killSession`, `getDeadlocks` |
| 32 | 잠금 | Kill the session that is blocking everyone | MS | `getBlockingChain`, `listSessions` | `killSession`(세션을 모름), `cancelQuery` |
| 33 | 잠금 | 세션 87 죽여 줘 | MS | `killSession`, `getBlockingChain`, `listSessions` | `cancelQuery`(에이전트 문장만), `disconnect` |
| 34 | 잠금 | Who is connected right now? | MS | `listSessions` | `listConnections`, `listPrincipals` |
| 35 | 잠금 | 트랜잭션 열어 둔 채 놀고 있는 세션 있어? | MS | `listOpenTransactions`, `listSessions` | `getTransactionStatus`(에이전트 것만) |
| 36 | 잠금 | Do you have an uncommitted transaction on this connection? | MS | `getTransactionStatus` | `listOpenTransactions`, `commitTransaction` |
| 37 | 권한 | kim 사용자에게 어떤 권한이 있어? | MS | `listGrants`, `listPrincipals` | `checkDiagnosticAccess`, `getAgentToolPolicy` |
| 38 | 권한 | Who can read the salaries table? | MS | `listGrants` | `checkDiagnosticAccess`, `getAgentToolPolicy` |
| 39 | 권한 | 어떤 역할이 있고 db_owner에는 누가 있어? | MS | `listPrincipals` | `checkDiagnosticAccess`, `getAgentToolPolicy` |
| 40 | 권한 | Does your login have enough rights to diagnose this server? | MS | `checkDiagnosticAccess` | `listGrants`, `listPrincipals` |
| 41 | 권한 | 이 연결에서 너는 어떤 도구를 쓸 수 있어? 삭제도 돼? | MS | `getAgentToolPolicy` | `checkDiagnosticAccess`, `listGrants` |
| 42 | 데이터 변경 | 주문 1001번 상태를 shipped로 바꿔 줘 | MS | `describeTable`, `updateRows`, `runSql`, `sampleRows` | `insertRows`, `changeSchema`, `runReadOnlySql` |
| 43 | 데이터 변경 | Delete all orders placed before 2020 | MS | `countRows`, `runReadOnlySql`, `runSql` | `deleteRows`(키 기반), `changeSchema` dropTable |
| 44 | 데이터 변경 | D:\data\customers.csv 파일을 customers 테이블에 넣어 줘 | MS | `importCsv`, `describeTable` | `insertRows`, `copyTable`, `exportData` |
| 45 | 데이터 변경 | Add a column email varchar(200) to customers | MS | `previewDdl`, `changeSchema` alterTable, `describeTable` | `changeSchema` createTable, `getObjectDdl` |
| 46 | 데이터 변경 | 방금 한 변경 커밋해 줘 | MS | `commitTransaction`, `getTransactionStatus` | `runSql`(`COMMIT`), `rollbackTransaction` |
| 47 | 데이터 변경 | orders 테이블 2024년 데이터를 엑셀 파일로 뽑아 줘 | MS | `exportData`, `describeTable` | `backupDatabase` |
| 48 | 백업·작업 | 마지막 백업이 언제야? | MS | `getBackupHistory` | `backupDatabase`, `getErrorLog` |
| 49 | 백업·작업 | Back up the Sales database now | MS | `backupDatabase`, `getBackupHistory` | `exportData` |
| 50 | 백업·작업 | app DB를 D:\backup\app.dump로 백업해 줘 | PG | `backupDatabase`(PG: 이 PC의 파일) | `exportData`, `restoreDatabase` |
| 51 | 백업·작업 | Restore D:\backup\app.dump into app_test | PG | `restoreDatabase`(PG: 이 PC의 파일) | `importCsv`, `backupDatabase` |
| 52 | 백업·작업 | Did any SQL Agent job fail last night? | MS | `getAgentJobHistory` | `getErrorLog`, `getBackupHistory` |
| 53 | 백업·작업 | 야간 ETL 작업 지금 돌려 줘 | MS | `listAgentJobs`, `getAgentJob`, `startAgentJob` | `runSql`(`sp_start_job`), `runLibraryQuery` |
| 54 | 라이브러리 | 라이브러리의 모니터링 점검 돌려서 시스템 상태 알려줘 | MS | `runMonitoringChecks`, `listLibraryQueries` | `listSessions`, `getServerInfo` |
| 55 | 라이브러리 | Run my saved query "daily sales" for yesterday | MS | `listLibraryQueries`, `getLibraryQuery`, `runLibraryQuery` | `getEditorHistory`, `runReadOnlySql` |
| 56 | 라이브러리 | 내가 아까 실행했던 쿼리 다시 찾아 줘 | MS | `getEditorHistory`, `getQueryWindowText` | `listLibraryQueries`, `getTopQueries` |
| 57 | 편집기 | 에디터에 있는 이 쿼리 설명해 줘 | MS | `getQueryWindowText`, `listQueryWindows` | `getEditorHistory`, `openInEditor` |
| 58 | 편집기 | Rewrite the selected SQL in my editor to use a JOIN | MS | `getQueryWindowText`, `listQueryWindows` | `openInEditor`, `runSql`, `editQueryWindow`(읽기 전) |
| 59 | 편집기 | 이 쿼리를 새 창에 열어 줘: `<SQL>` | MS | `openInEditor` | `editQueryWindow`, `runReadOnlySql` |
| 60 | 편집기 | Format this SQL nicely: `<SQL>` | | (도구 없이 답함: `formatSql`은 26.1.0에서 빠짐) | `validateSql`, `openInEditor` |

## 도구 묶음별 필요 (2026-11-12, 앱 변경 #64)

허용 첫 도구가 어느 묶음에 처음 들어 있는지다. 최소 = 13개(`findTools` 포함), 핵심 = 43개, 전체 = 87개([ai-agent.md](ai-agent.md) "도구 묶음",
2026-11-15 도구 정리 뒤).

| 필요한 묶음 | 질문 | 수 |
|---|---|---|
| 최소 | 1, 2, 3, 5, 6, 7, 11, 13, 14, 15, 21, 36, 42, 43, 44, 45, 46, 47, 56, 57, 58, 59, 60 | 23 |
| 핵심 (최소에서는 `findTools` 또는 `runReadOnlySql`) | 4, 9, 10, 16, 17, 19, 20, 22, 26, 28, 29, 30, 31, 32, 33, 34, 35, 40, 48, 49 | 20 |
| 전체 (핵심에서도 `findTools`) | 8, 12, 18, 23, 24, 25, 27, 37, 38, 39, 41, 50, 51, 52, 53, 54, 55 | 17 |

- 최소 묶음에서 맞는 질문 가운데 44(`importCsv`), 45(`previewDdl`·`changeSchema`), 47(`exportData`)은 허용 칸의 `describeTable`로 시작할 수 있을
  뿐 실제 작업 도구는 `findTools`로 찾아야 한다. 42·43은 `runSql`(조건 기반 변경)로 끝낼 수 있다.
- 60은 도구 없이 답하는 것이 맞다(`formatSql`은 26.1.0에서 빠짐). 도구를 부르면 틀림 칸이다.
- 2026-11-15 도구 정리로 6(`runSelect` → `runReadOnlySql`, 최소 묶음)과 60이 최소 칸으로 옮겼다. 옛 ID를 부르는 모델은 별칭으로 같은 도구에 닿으므로,
  옛 ID로 부른 첫 도구는 그 도구가 된 지금 ID로 판정한다([ai-agent.md](ai-agent.md) "별칭").
- 플레이북으로 시작한 대화는 플레이북 글이 이름으로 부르는 도구가 그 대화에 더해진다(ai-agent.md "도구 묶음"). 이 표는 자유 질문 기준이다.
- 이 묶음 구성으로 재면 "전체" 칸의 18개는 `findTools` 한 번(왕복 하나)이 더 든다. 그 질문들의 정확도가 떨어지면 해당 도구를 핵심으로
  올릴 근거다(`plugin.xml`의 `toolSet`).

## 결과 기록

실행한 날, 모델, 도구 설명의 판(커밋), 보낸 도구 수와 요청 크기, 위 지표를 표로 남긴다. 틀린 질문은 도구 설명이나 묶음을 고칠 근거로
ai-agent.md "도구 선택 점검"에 적는다.

| 날짜 | 모델 | 판 | 도구 묶음 | 보낸 도구 | 정확도 | 중립 | 평균 호출 | 틀린 인수 | 메모 |
|---|---|---|---|---|---|---|---|---|---|
| (아직 실행하지 않음) | | | | | | | | | |

## 부록: 도구 목록 (2026-11-15)

`plugins/com.seizia.chromite.model.ai.tools/plugin.xml`에서 뽑았다(도구 정리 뒤 87개 + `findTools`). 분류 R = READ, W = WRITE, D = DANGEROUS(괄호는 `writes`).
`changeSchema`는 정적 분류 W이고 호출마다 작업으로 정한다(dropTable·dropView·dropIndex는 D). 인수 칸은 거의 모든 도구가 받는 `connection`, `database`를
뺐다(백업·복원은 둠). 옛 ID 칸은 26.2.0까지 받는 별칭이다. 설명 전문과 DB별 지원은 plugin.xml이 원본이다.

| ID | 분야 | 분류 | 하는 일(설명 첫 문장) | 인수 (\* 필수) | 옛 ID |
|---|---|---|---|---|---|
| `findTools` | 에이전트 | R (채팅만) | Finds agent tools you were not given by English keywords and/or an area, and returns their ids, area, class and one-line description | `query`, `area`, `limit` | — |
| `listConnections` | 연결 | R | Lists the connections configured in Chromite: id, name, folder, driver, family, connection type, production, readOnly, color, connected | `contains` | — |
| `getConnectionInfo` | 연결 | R | Settings of one connection without secrets: host, port, database, user, auth model, network handlers (SSH, proxy), default auto-commit, and server product, server version and driver version when connected | — | — |
| `disconnect` | 연결 | W (none) | Closes the connection for the whole Chromite app, query windows included | — | — |
| `testConnection` | 연결 | R | Checks that a connection works: connects if it is closed, makes one round trip and returns ok, roundTripMs and the server product and version | — | `connect` |
| `getCurrentDatabase` | 연결 | R | Current database and schema of the agent's session (agentDatabase, agentSchema) and of the connection's main session (connectionDatabase, connectionSchema) | — | — |
| `setCurrentDatabase` | 연결 | R | Changes the database and/or schema that the agent's later calls on this connection use (the user's query windows do not change) and returns the getCurrentDatabase result | `schema` | — |
| `getServerInfo` | 연결 | R | Server product, version, edition, uptime, host facts and key settings | — | — |
| `checkDiagnosticAccess` | 연결 | R | What this login may read for diagnosis: system views, server permissions and extensions (VIEW SERVER STATE, pg_monitor, PROCESS, V$ access, pg_stat_statements, ...) and which tools are limited without them | — | `probePermissions` |
| `getTransactionStatus` | 연결 | R | Whether the agent has an open transaction on the connection (uncommitted changes, locks or a snapshot, or one begun explicitly, also in auto-commit mode), and whether the user's query windows have one or one that could not be checked | — | — |
| `commitTransaction` | 연결 | W | Commits the agent's open transaction on the connection (left open by runSql, runScript or a data tool) | — | — |
| `rollbackTransaction` | 연결 | R | Rolls back the agent's open transaction on the connection, discarding the agent's uncommitted changes | — | — |
| `getAgentToolPolicy` | 연결 | R | Chromite's permission for every agent tool on a connection: AUTO (runs), CONFIRM (asks the user), BLOCKED or READ_ONLY, with the reason, plus the production and read-only flags | — | `getToolPolicy` |
| `listDatabases` | 메타데이터 | R | Names of the databases (catalogs) on the server and the current one | — | — |
| `listSchemas` | 메타데이터 | R | Schemas of a database and the current schema | `contains` | — |
| `listTables` | 메타데이터 | R | Tables and views of one schema (MySQL/MariaDB: one database) with type and comment, count and hasMore | `schema`, `contains`, `types`, `limit` | — |
| `describeTable` | 메타데이터 | R | Full structure of one table or view: columns (type, size, nullable, default, auto-increment, comment), primary key, indexes, foreign keys and the foreign keys of other tables that reference it | `table`\* | `listIndexes`, `listForeignKeys` |
| `listRoutines` | 메타데이터 | R | Procedures and functions of a schema (MySQL/MariaDB: database): schema, name, type, comment | `schema`, `contains` | — |
| `getRoutineSource` | 메타데이터 | R | Source of one procedure, function, trigger or view with LINE NUMBERS (cite them), plus parameters and properties | `object`\* | — |
| `listTriggers` | 메타데이터 | R | Triggers of one table or of a schema: timing, events, enabled | `table`, `schema` | — |
| `listSequences` | 메타데이터 | R | Sequences with current value, increment and limits | `schema` | — |
| `listPrincipals` | 메타데이터 | R | Database security principals: logins, users, roles and role memberships | — | — |
| `listGrants` | 메타데이터 | R | Granted and denied permissions on an object or schema, or of a user or role (give object, principal or both) | `object`, `principal` | `listPermissions` |
| `getObjectDdl` | 메타데이터 | R | CREATE DDL of an existing table, view, routine or other object as Chromite generates it for the database | `object`\* | — |
| `getObjectDependencies` | 메타데이터 | R | Dependency graph of one object as nodes and edges: what it uses (tables, views, routines, foreign keys) and what uses it (views, routines, triggers); depth follows 'uses' edges | `object`\*, `depth` | — |
| `searchObjects` | 메타데이터 | R | Finds tables, views, routines (and columns when types includes column) whose name contains a text, in all schemas of one database | `contains`\*, `types`, `limit` | — |
| `runReadOnlySql` | 쿼리 | R | Runs one read-only statement you write (SELECT, system views, SHOW, read-only DBCC, sp_who/sp_help* procedures) in a rolled-back read-only transaction | `sql`\*, `limit`, `offset`, `rowFormat`, `maxCellChars` | `runSelect` |
| `runSql` | 쿼리 | W | Runs one statement that may change data or schema (INSERT, UPDATE, DELETE, MERGE, DDL, EXEC) | `sql`\*, `limit` | — |
| `runScript` | 쿼리 | W | Runs a script of several statements split by the database's delimiter rules (GO for SQL Server); returns each statement's result or error | `script`\*, `stopOnError` | — |
| `cancelQuery` | 쿼리 | R | Cancels the statements the agent itself is running on the connection; returns canceled | — | — |
| `explainQuery` | 쿼리 | R | Estimated execution plan without running the statement, as JSON: operator tree (op, object, estRows, cost, predicates), hotspots and findings (scans, key lookups, implicit conversions, missing statistics or indexes, compiled parameter values) | `sql`\* | — |
| `getActualPlan` | 쿼리 | R | Runs the statement and returns the actual plan with run-time values (actual rows, executions, time, reads/buffers, spills; SQL Server also STATISTICS IO/TIME messages) | `sql`\* | — |
| `comparePlans` | 쿼리 | R | Plans of two statements (a query and its rewrite) side by side with a diff: cost change and operators only in A or B; actual=true also runs both (read-only statements only) for measured time | `sqlA`\*, `sqlB`\*, `actual` | — |
| `validateSql` | 쿼리 | R | Checks one statement without running it: parse errors, statement type, readOnly and the access class the agent applies, plus a server check of syntax and names (SQL Server SET NOEXEC; PostgreSQL/MySQL EXPLAIN for SELECT, INSERT, UPDATE, DELETE) | `sql`\*, `server` | — |
| `getEditorHistory` | 쿼리 | R | Statements the user ran in Chromite's query windows (not server statistics), newest first, with time, duration, rows and errors | `contains`, `limit` | `getQueryHistory` |
| `sampleRows` | 데이터 | R | Reads rows of one table or view without writing SQL: optional where and orderBy fragments, limit (default 20) and offset; read-only | `table`\*, `where`, `orderBy`, `limit`, `offset`, `rowFormat` | — |
| `countRows` | 데이터 | R | Exact row count of a table or view, optionally with a where condition | `table`\*, `where` | — |
| `profileColumns` | 데이터 | R | Data profile per column: total and non-null rows, distinct values, min, max and the most frequent values | `table`\*, `columns`, `topN` | — |
| `compareQueryResults` | 데이터 | R | Runs two read-only SELECTs (same or different connections and databases) and compares the rows by key columns or as whole rows: counts and samples of rows only in A, only in B and different | `sqlA`\*, `sqlB`\*, `connectionB`, `databaseB`, `keyColumns`, `limit` | — |
| `insertRows` | 데이터 | W | Inserts rows given as JSON objects into a table; values are converted to the column types | `table`\*, `rows`\* | — |
| `updateRows` | 데이터 | W | Updates rows identified by key values that cover the primary key or a unique index (other keys are refused), setting the given column values | `table`\*, `key`\*, `values`\* | — |
| `deleteRows` | 데이터 | W | Deletes rows identified by key values that cover the primary key or a unique index (other keys are refused) | `table`\*, `key`\* | — |
| `previewDdl` | DDL | R | Builds the DDL of a changeSchema call without running it: the same operation and arguments, returns sql | `operation`\*, `table`, `view`, `columns`, `primaryKey`, `action`, `column`, `columnType`, `nullable`, `default`, `newName`, `select`, `replace`, `index`, `unique`, `include` | `generateDdl` |
| `changeSchema` | DDL | W | Creates, alters or drops a table, view or index, chosen by operation: createTable (columns, primaryKey), alterTable (action addColumn, dropColumn, renameColumn or alterColumnType of one column), dropTable, createView (a read-only select, replace), dropView, createIndex (key columns, unique, include), dropIndex | `operation`\*, `table`, `view`, `columns`, `primaryKey`, `action`, `column`, `columnType`, `nullable`, `default`, `newName`, `select`, `replace`, `index`, `unique`, `include` | `createTable`, `alterTable`, `dropTable`, `createView`, `dropView`, `createIndex`, `dropIndex` |
| `exportData` | 전송 | W (file) | Writes the rows of a read-only SELECT or of a table to a new CSV, JSON or XLSX file on this computer (limit, default 100,000 rows, max 1,000,000) | `sql`, `table`, `fileFormat`, `file`\*, `overwrite`, `delimiter`, `limit` | — |
| `importCsv` | 전송 | W | Inserts the rows of a CSV file on this computer (header = column names) into an existing table of 'connection' | `file`\*, `table`\*, `delimiter`, `encoding`, `limit` | — |
| `copyTable` | 전송 | W | Copies the rows of a table from sourceConnection (default: the same) into a table of 'connection', optionally creating the target table | `sourceConnection`, `sourceDatabase`, `table`\*, `targetTable`, `createTarget`, `limit` | — |
| `listSessions` | 상태·관리 | R | Server sessions with login, host, program, status, current wait and statement; activeOnly keeps those running something or holding a transaction | `activeOnly`, `limit` | — |
| `getBlockingChain` | 상태·관리 | R | Who blocks whom right now as a tree: head blockers (login, host, program, transaction age, isolation, open transactions, last SQL), waiters (wait type, time, resource, SQL), their locks mapped to objects and indexes, and findings with fix suggestions | — | — |
| `listLocks` | 상태·관리 | R | Held and waited locks grouped by session, object, index and mode, a summary, and lock escalation counters (SQL Server) | `session`, `limit` | — |
| `getDeadlocks` | 상태·관리 | R | Deadlocks that already happened: SQL Server system_health deadlock graphs as JSON (victims, processes with SQL and stack, resources with owners and waiters); MySQL/MariaDB the latest InnoDB deadlock; PostgreSQL the counters only; Oracle the ORA-00060 alert log entries with the trace file's deadlock graph | `limit` | — |
| `listOpenTransactions` | 상태·관리 | R | Server transactions open longer than minSeconds, including idle-in-transaction sessions, with age, locks and last SQL | `minSeconds` | — |
| `getLongRunningQueries` | 상태·관리 | R | Statements running now for longer than minSeconds with CPU, reads, waits and blocking | `minSeconds` | — |
| `killSession` | 상태·관리 | D | Ends another server session; its open transaction is rolled back | `session`\* | — |
| `getWaitStats` | 상태·관리 | R | What the server waits on: cumulative wait types since restart without benign ones (SQL Server, MySQL, Oracle) or current session waits (PostgreSQL) | `limit` | — |
| `getTopQueries` | 상태·관리 | R | Most expensive statements, ordered by cpu, io, duration or executions | `source`, `sortBy`, `limit`, `hours` | `getQueryStoreTopQueries` |
| `getDatabaseSizes` | 상태·관리 | R | Size of every database on the server (data and log) | — | — |
| `getSpaceUsage` | 상태·관리 | R | Space inside one database: data and log files with used and free space, log reuse wait and disk volumes (SQL Server); tablespaces, largest tables and WAL (PostgreSQL); schemas and largest tables (MySQL); tablespace usage (Oracle) | — | — |
| `getBackupHistory` | 상태·관리 | R | Backups already taken: last full, differential and log backup per database with age and warnings, and recent backups (SQL Server msdb); WAL archiving (PostgreSQL); RMAN jobs (Oracle); not MySQL | `limit` | — |
| `getJobHistory` | 상태·관리 | R | Recent runs of the server's scheduler, failures only by default: PostgreSQL pg_cron, MySQL events, Oracle scheduler | `failedOnly`, `days`, `limit` | — |
| `getErrorLog` | 상태·관리 | R | Recent server log entries, optionally only those containing a text: SQL Server error log, PostgreSQL current log file, MySQL 8 error_log table, Oracle alert log | `contains`, `limit` | — |
| `getServerConfig` | 상태·관리 | R | Key server and database settings with sanity findings (MAXDOP, cost threshold, memory, auto shrink, shared_buffers, work_mem, autovacuum, buffer pool, ...) | — | — |
| `backupDatabase` | 상태·관리 | W | Backs up a database | `connection`, `database`\*, `file`\*, `backupType`, `copyOnly`, `dumpFormat`, `schemas`, `tables`, `noOwner`, `noPrivileges`, `inserts`, `clean`, `create`, `method`, `noData`, `routines`, `events`, `removeDefiner` | `dumpDatabase` |
| `restoreDatabase` | 상태·관리 | D | Restores a database from a backup | `connection`, `database`\*, `file`\*, `replace`, `dumpFormat`, `clean`, `noOwner`, `stopOnError`, `disableForeignKeyChecks` | `restoreDump` |
| `listAgentJobs` | 상태·관리 | R | SQL Server Agent: the agent service state and the jobs with enabled flag, current status and step, last run outcome and time, next run, category, owner | `contains` | — |
| `getAgentJob` | 상태·관리 | R | SQL Server Agent: one job's definition: steps (subsystem, database, command, on success/failure actions, retries) and schedules (description, next run), plus its current state | `job`\* | — |
| `getAgentJobHistory` | 상태·관리 | R | SQL Server Agent: run history, newest first: each run's outcome (step 0) and its steps with status, duration, retries and the server message; one job or all jobs | `job`, `failedOnly`, `limit` | — |
| `startAgentJob` | 상태·관리 | W | SQL Server Agent: starts a job now (msdb.dbo.sp_start_job), optionally at a step | `job`\*, `step` | — |
| `stopAgentJob` | 상태·관리 | W | SQL Server Agent: stops a running job (msdb.dbo.sp_stop_job) | `job`\* | — |
| `setAgentJobEnabled` | 상태·관리 | W | SQL Server Agent: enables or disables a job (msdb.dbo.sp_update_job @enabled) | `job`\*, `enabled`\* | — |
| `getTableStatistics` | 성능 | R | Per table (one table or a schema): row count, table and index sizes, statistics last update, rows sampled, modifications since the update, dead rows and vacuum/analyze times | `table`, `schema`, `limit` | — |
| `getIndexUsage` | 성능 | R | How existing indexes were used since the server started: seeks, scans, lookups, updates, key and included columns, lock escalations; one table or a schema | `table`, `schema`, `limit` | — |
| `getMissingIndexes` | 성능 | R | Indexes that may be missing in a database: SQL Server optimizer suggestions with a CREATE INDEX; PostgreSQL large tables read mostly by sequential scans; Oracle full scans of large tables in cached plans with the filter predicates an index could serve; not MySQL | `limit` | — |
| `getUnusedIndexes` | 성능 | R | Indexes that are written but never read since the last restart, and duplicate or left-prefix indexes, in a database or schema | `schema` | — |
| `getIndexFragmentation` | 성능 | R | Fragmentation with REBUILD/REORGANIZE suggestions (SQL Server), dead rows and leaf density (PostgreSQL, pgstattuple if installed), reclaimable space (MySQL); one table or a schema; not Oracle | `table`, `schema`, `limit` | — |
| `getRoutineStats` | 성능 | R | Run-time statistics of one procedure, function or trigger and of each statement inside it with its start line (SQL Server, Oracle V$SQL PROGRAM_LINE#); function statistics and nested statements (PostgreSQL); program statistics (MySQL) | `object`\*, `limit` | — |
| `getCachedPlans` | 성능 | R | SQL Server only: plans in the plan cache for each statement of a routine (by start line) or of statements containing a text, parsed like explainQuery (operators, warnings, compiled parameter values) | `object`, `contains`, `limit` | — |
| `getQueryStoreRegressions` | 성능 | R | SQL Server Query Store of a database: queries whose average duration in the recent window is worse than in the earlier window, with the plan ids of both windows (plan_changed = a different plan is the likely cause) | `recentMinutes`, `historyMinutes`, `minExecutions`, `limit` | — |
| `getQueryStoreForcedPlans` | 성능 | R | SQL Server Query Store of a database: plans forced on queries (manually or by automatic tuning) with their force failure count and last failure reason | — | — |
| `whatIfIndex` | 성능 | R | PostgreSQL with the hypopg extension: estimated plan of a statement before and after a hypothetical index (nothing is built; it exists only in the agent's session for this call) and the plan comparison | `sql`\*, `index`\* | — |
| `listLibraryQueries` | 쿼리 라이브러리 | R | Saved queries of the user's Chromite Query Library: id, name, description, folder, target connection, parameters, thresholds | `folder`, `contains` | — |
| `getLibraryQuery` | 쿼리 라이브러리 | R | One Query Library entry with its SQL, parameters and thresholds | `id`\* | — |
| `runLibraryQuery` | 쿼리 라이브러리 | R | Runs one Query Library query with parameters (bound, not concatenated) on its target or the given connection and judges the result against its thresholds | `id`\*, `parameters`, `limit` | — |
| `runMonitoringChecks` | 쿼리 라이브러리 | R | Runs every read-only query of a Query Library folder and judges each against its @threshold entries: OK, WARN, CRITICAL, NO_THRESHOLDS, SKIPPED, ERROR, plus a summary | `folder`\* | — |
| `openInEditor` | 편집기 | R | Opens SQL in a new query window of the Chromite app for the user; does not run it | `sql`\*, `title` | — |
| `listQueryWindows` | 편집기 | R | Lists the query windows open in the Chromite app: id, title, connection, database, saved file, unsaved changes, active window, running query, text length, caret and selection | — | — |
| `getQueryWindowText` | 편집기 | R | Reads the text of an open query window (default: the active one): the whole text, the selection, or the SQL statement at the caret, with its offsets | `window`, `part`, `maxChars` | — |
| `editQueryWindow` | 편집기 | W (none) | Changes the text of an open query window (default: the active one): insert at the caret, replace the selection, or replace the whole text | `window`, `mode`\*, `text` | — |

## 변경 이력

- 2026-11-12 처음 작성(앱 변경 #61, `chromite/agent-tool-review`): 질문 60개(한국어 34·영어 26), 재는 방법, 도구 목록 부록.
- 2026-11-12 도구 묶음(앱 변경 #64, `chromite/agent-tool-sets`): 재는 방법의 설정을 "채팅 도구 묶음"으로, `findTools`를 거친 첫 도구 판정,
  질문별 필요한 묶음 표, 결과 기록에 묶음 칸. 부록에 `findTools`.
- 2026-11-15 도구 정리(앱 변경 #64 나머지, `chromite/agent-tool-cleanup`): 질문의 허용·틀림 칸을 새 ID로(합친 도구, 이름 바꾼 도구, 빠진 `formatSql`·`connect`),
  묶음별 필요 표(최소 23·핵심 20·전체 17), 부록을 plugin.xml에서 다시 뽑음(옛 ID 칸). 질문 자체는 그대로.
