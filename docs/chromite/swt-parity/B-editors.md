# SWT → Swing 기능 대응 점검 — 영역 B: 편집기

- 기준: SWT는 `263b8ddb45` 트리, Swing은 `D:\Chromite` 작업 트리(HEAD `8e8aaaef60`). 저장소는 읽기만 했다(빌드·실행·git 쓰기 없음).
- 범위(삭제된 번들): `ui.editors.sql`, `ui.editors.sql.terminal`, `ui.editors.data`, `ui.editors.entity`, `ui.editors.connection`,
  `ui.editors.base`, `ui.editors.acl`, `ui.editors.hex`, `ui.editors.image`, `ui.editors.json`, `ui.editors.xml`, `ui.editors.session`,
  `ui.editors.locks`, `ui.editors.erd`. SQL 편집기의 트랜잭션 도구 막대·로그는 `core` 번들에 있었지만 SQL 편집기 기능이라 함께 넣었다.
- Swing 경로는 모두 `plugins/com.seizia.chromite.ui.swing/src/com/seizia/chromite/ui/swing/` 아래(이하 생략). 문서는 `docs/chromite/`.
- 판단은 코드로 했다. "실행 확인 못 함"이라고 적은 줄은 코드 경로로만 판단했다.

## 요약

| 상태 | SQL 편집기 | 결과 뷰어 | 개체 편집기 | 연결 편집기 | 그 밖의 편집기 | 합계 |
|---|---|---|---|---|---|---|
| 있음 | 33 | 17 | 6 | 16 | 2 | **74** |
| 일부 | 8 | 7 | 5 | 6 | 5 | **31** |
| 없음 | 3 | 4 | 0 | 0 | 0 | **7** |
| 조용히 끊김 | 0 | 0 | 1 | 0 | 0 | **1** |
| 의도적 제외 | 2 | 2 | 0 | 0 | 0 | **4** |
| 합계 | 46 | 30 | 12 | 22 | 7 | **117** |

2026-11-08 다시 셈(devel #165 기준, 모든 행을 코드와 다시 대조): 위 수는 아래 표의 지금 상태 칸으로 센 것이다("수정함"은 있음으로 셈). 조사 당시 수는 있음 25, 일부 42, 없음 39, 조용히 끊김 8, 의도적 제외 3이었다.

### 조용히 끊김 (가장 위험한 것부터)

1. **S23 연결 끊기·다시 연결·삭제 때 커밋하지 않은 트랜잭션을 묻지 않고 롤백한다.** SWT는 `DataSourceHandler.checkAndCloseActiveTransaction`으로 Commit/Rollback을 물었다.
   Swing `ConnectionCommands`는 바로 `DisconnectJob`을 돌리고(`ConnectionCommands.java:58` 주석이 "아직 없다"고 적음, `:188`),
   모델은 수동 커밋 연결을 닫을 때 롤백한다(`JDBCExecutionContext.java:206` `closeConnection(..., !isAutoCommit(false))`). 사용자 규칙 "트랜잭션 안전"에 어긋난다.
2. **C5 저장하지 않은 비밀번호로 SQL Server·Oracle에 연결하면 묻지 않고 연결이 취소된다(실행 확인 못 함).** `DataSourceDescriptor.askForPassword`(`:2133-2136`)는
   인증 방식 클래스가 정확히 `AuthModelDatabaseNative`가 아니면 `promptAuthModelCredentials`를 부른다. SQL Server 기본 `SQLServerAuthModelDatabase`, Oracle `OracleAuthModelDatabaseNative`가 여기에 해당한다.
   Swing `SwingPlatformUI`는 이 메서드를 구현하지 않아 기본값 `false`(`DBPPlatformUI.java:136-140`) → "취소됨"으로 끝난다. SWT는 `AuthModelCredentialsDialog`(ui.editors.connection)를 띄웠다.
   → **2026-11-07 수정**(#106, [swing-auth-prompts.md](../swing-auth-prompts.md)): `SwingPlatformUI.promptAuthModelCredentials`가 묻는다.
3. **R19 결과 그리드 저장 전 스크립트 미리 보기와 연결 종류의 "데이터 변경 확인"이 동작하지 않는다.** SWT `ResultSetViewer.applyChanges`(`ResultSetViewer.java:4735-4760`)는
   `resultset.confirm.beforeSave` 또는 `DBPConnectionType.isConfirmDataChange()`(Test·Production 기본 켬)이면 `SavePreviewDialog`를 띄웠다.
   Swing `ResultSetEditPersister`(`:151` `collectChanges` 뒤 바로 저장)에는 확인이 없다. 설정값은 남아 있지만 효과가 없다.
   → **2026-11-07 수정**(`chromite/grid-save-preview`): 저장 전 `ScriptPreviewDialog.confirmSave`로 문장을 보이고 Save/Cancel을 묻는다.
   Options에 설정 항목. [swing-frontend.md](../swing-frontend.md) "저장 전 스크립트 미리 보기".
4. **S16 스마트 커밋.** (2026-11-08 고침, [connection-lifecycle.md](../connection-lifecycle.md) "스마트 커밋") 엔진은 `handler.isSmartAutoCommit()`일 때만 `DBExecUtils.checkSmartAutoCommit`을 부른다(`SQLQueryExecuteJob.java:615`).
   인터페이스 기본값은 `false`(`SQLQueryExecuteHandler.java:91`)이고 Swing `SqlEditorPanel.QueryHandler`(`:1329`)는 재정의하지 않는다.
   연결 종류의 `smartCommit`과 `transaction.smart.commit` 설정은 저장·읽기(`DBExecUtils.java:528`)되지만 쿼리 창에서는 아무 일도 하지 않는다.
5. **S24 유휴 트랜잭션 자동 종료**(영역 A 지시서의 예와 같음, 교차 참조). `DataSourceMonitorJob.getTransactionTimeoutSeconds`·`getLastUserActivityTime`이 쓰는
   UI 고리(`UIServiceConnections` 구현, 마지막 사용자 활동 시각)가 Swing에 없다(`plugin.xml` 서비스 3개: Connections, Drivers, SystemAgent). → **2026-11-07 수정**(#97, [idle-transactions.md](../idle-transactions.md)).
6. **S12 `@export` 명령이 조용히 무시된다.** `SQLCommandExport`(model.sql)는 pragma만 남기고, 그 처리기 `SQLPragmaExport`는 삭제된 `data.transfer.ui`(`plugin.xml:118`)에 있었다.
   `SQLQueryExecuteJob.java:432`는 처리기가 없으면 건너뛰므로 다음 쿼리가 평소처럼 실행되고 파일은 만들어지지 않으며 오류도 없다.
7. **S8 연결 보안 제한 "스크립트 실행"(`PERMISSION_EXECUTE_SCRIPTS`)이 적용되지 않는다.** SWT는 `SQLEditor.java:3106`에서만 검사했다. Swing은 검사하지 않는다.
   (2026-11-07 고침: 쿼리 창이 실행 전에 검사한다, `chromite/sql-exec-safety`, [swing-sql-exec-safety.md](../swing-sql-exec-safety.md))
   SWT 시절에 저장한 제한(연결·연결 종류의 `permissions`)은 파일에 남아 있지만 쿼리 창에서는 무시된다(데이터 편집 제한 `PERMISSION_EDIT_DATA`는 모델이 검사하므로 동작한다).
8. **E12 `openEntityEditor`가 아무것도 하지 않는다.** `SwingPlatformUI.java:496-505`는 디버그 로그만 남긴다. 호출하는 곳: 데이터 전송 뒤 새 테이블 열기(`DatabaseTransferConsumer.java:815`). 영향은 작다.
   (2026-11-08 다시 확인: 남은 유일한 조용히 끊김. Swing 마법사는 그 옵션을 끄고 시작하지만 SWT에서 저장한 전송 작업은 여기에 닿는다. 표의 E12)

## A. SQL 편집기 (`ui.editors.sql`, `ui.editors.sql.terminal`, 트랜잭션 도구는 `core`)

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| S1 | 선택 영역·전체 실행, `GO` 배치 | `run.script`, `SQLEditor.processQueries` | 있음 | `SqlEditorPanel`(F5), `SQLScriptBatches`(model.sql), swing-frontend.md "쿼리 실행 사양" | 높음 | SSMS 방식. 선택 1자부터 선택으로 봄 |
| S2 | 커서 위치 문장 실행(Ctrl+Enter) | `run.statement` | **있음** (2026-11-08, [swing-query-commands.md](../swing-query-commands.md): Query > Execute Current Statement, Ctrl+Enter, 선택은 한 문장, `SqlQueryCommands`). 조사 당시: 일부 | Query 메뉴는 Execute·Cancel·완성·계획·기록뿐(`MainFrame.java:1511-1529`) | 중간 | 조사 당시: 문장을 선택해야 함 |
| S3 | 새 탭에 결과, 탭마다 결과 여러 개 | `run.statementNew`, `run.scriptNew`, `multipleResultsPerTab` | 일부 | `QueryResultsView`가 결과를 한 카드에 쌓음. 2026-11-08 결과 고정(Query &gt; Pin Results, Ctrl+Shift+P): 고정 탭은 다음 실행이 지우지 않음(`QueryResultsView.pinResults`, [swing-sql-editor-ergonomics.md](../swing-sql-editor-ergonomics.md)) | 낮음 | 탭 이름 바꾸기·분리, "새 탭에서 실행" 명령 없음 |
| S4 | 커서부터 스크립트 실행, 이전·다음 문장 이동, 현재 쿼리 복사 | `run.scriptFromPosition`, `query.next/prev`, `copy.query` | **있음** (2026-11-08, [swing-query-commands.md](../swing-query-commands.md): Execute from Cursor Alt+P, Previous/Next Statement Alt+PageUp/PageDown, Copy Current Statement). 조사 당시: 없음 | grep `FromPosition`, `query.next` 0건 | 낮음 | 이동 키는 SWT Alt+Up/Down 대신(편집기의 줄 옮기기) |
| S5 | 식 평가, 쿼리 행 수 세기 | `run.expression`, `run.count` | **있음** (2026-11-08, [swing-query-commands.md](../swing-query-commands.md): Evaluate Expression Ctrl+Alt+', Count Rows, 모델 변환기). 조사 당시: 없음 | grep 0건 | 낮음 | |
| S6 | 연결 종류 "SQL 실행 확인"(Production) | `SQLQueryJob` 확인 | 있음 | 모델 `SQLQueryExecuteJob.java:507` → 핸들러 기본 `confirmQueryExecution` → `SwingPlatformUI.confirmAction`(`:183`) | 높음 | 예/취소뿐. SWT의 "건너뛰기", "모두 예" 없음 |
| S7 | 위험한 문 확인(WHERE 없는 DELETE/UPDATE, DROP) | `SQLEditor.java:3288-3385`, `CONFIRM_DANGER_SQL`, `CONFIRM_DROP_SQL` | 있음(2026-11-07) | `SqlExecutionSafety.confirmDangerousStatements`(준비 Job, 같은 설정 키), Options ▸ 일반 ▸ 쿼리 편집기 | 높음 | 실행마다 한 번 묻고 목록 표시, `GO` 배치는 다시 나눠 검사. 구분자 없는 T-SQL 여러 문장은 못 찾음. [swing-sql-exec-safety.md](../swing-sql-exec-safety.md) |
| S8 | 연결 보안 제한: 스크립트 실행 금지 | `SQLEditor.java:3106` | 있음(2026-11-07) | `SqlExecutionSafety.checkExecutePermission`(`SqlEditorPanel.execute`, 연결 전) | 중간 | SWT처럼 문장 수와 관계없이 쿼리 창 실행을 모두 거절. 위 요약 7, [swing-sql-exec-safety.md](../swing-sql-exec-safety.md) |
| S9 | 매개변수 `:name`, `${var}` 입력 | `SQLQueryParameterBindDialog`, `SQLEditorParametersProvider` | 있음 | `QueryParameterDialog`(스크립트 모드), `QueryParameterBinder.ScriptProvider`, swing-sql-editor-plus.md "매개변수" | 중간 | 값 기억 포함 |
| S10 | `@set` 변수의 편집기 단위 유지, 변수 패널 | `SQLEditor` 전역 스크립트 문맥, `show.variables`, `SQLVariablesPanel` | **있음** (2026-11-08, [swing-query-commands.md](../swing-query-commands.md): 창의 변수 문맥이 실행 문맥의 부모, Query > Variables… 대화상자). 조사 당시: 일부 | 실행마다 새 `SQLScriptContext`(`SqlEditorPanel.java:1022`) | 중간 | 드라이버별 저장(SWT)은 하지 않음, 창을 닫으면 사라짐 |
| S11 | `@include` | `ui.editors.sql/plugin.xml:55` `SQLCommandInclude` | **있음** (2026-11-08, [swing-query-commands.md](../swing-query-commands.md): model.sql `SQLCommandInclude` 등록, 엔진이 같은 세션에서 끼워 실행, 상대 경로·중첩·고리 거절·위험한 문 확인·파일 줄 오류). 조사 당시: 없음 | model.sql은 set/unset/echo/export만 등록(`model.sql/plugin.xml:72-75`) | 낮음 | 조사 당시: 알 수 없는 명령 오류가 남 |
| S12 | `@export` | `SQLCommandExport` + `data.transfer.ui` `SQLPragmaExport` | **있음** (2026-11-07, [swing-data-transfer.md](../swing-data-transfer.md) "@export 명령": Swing 처리기 `SqlExportPragma`가 다음 쿼리를 실행하지 않고 형식·옵션을 채운 Export Data 마법사를 엶, 모르는 형식·옵션과 SELECT가 아닌 다음 문장은 오류). 조사 당시: 조용히 끊김 | `SQLQueryExecuteJob.java:432` 처리기 없음 → 건너뜀 | 중간 | 위 요약 6 |
| S13 | `@echo` 등 제어 명령 출력 | `SQLEditorOutputConsoleViewer` | 있음 | Messages 탭(swing-frontend.md C단계 "스크립트 문맥") | 낮음 | |
| S14 | 오류 때 묻기(Stop/Retry/Ignore/Ignore all), 오류 처리 설정 | `showErrorStopRetryIgnore`, `PrefPageSQLExecute` | 일부 | `SqlEditorPanel.java:1372-1379` 늘 STOP. 2026-11-07 오류 처리 설정 추가(Options > Query Execution: 멈춤 / 건너뛰고 계속, [swing-options.md](../swing-options.md)). 대화상자로 묻기는 여전히 없음(SSMS식 Messages 탭) | 중간 | 조사 당시: 무시하고 계속할 방법 없음. 함께 고침: 오류로 멈추면 엔진 기본값 `STOP_ROLLBACK`이 열린 트랜잭션을 묻지 않고 롤백하던 것 |
| S15 | 커밋 방식 설정(문장마다·스크립트 끝·N줄마다) | `PrefPageSQLExecute`, `SQLScriptCommitType` | 의도적 제외 (2026-11-07) | 엔진 기본 `NO_COMMIT`(swing-frontend.md C단계 "커밋·오류 처리"). 모든 선택지가 묻지 않고 커밋해 트랜잭션 안전 규칙에 어긋남([swing-options.md](../swing-options.md)) | 중간 | 조사 당시: 없음 |
| S16 | 스마트 커밋(변경 문에서 수동 커밋으로, 끝나면 복귀) | `SQLEditor.isSmartAutoCommit`, `TransactionMonitorToolbar`(`recoverSmartCommit`) | **있음** (2026-11-08, [connection-lifecycle.md](../connection-lifecycle.md) "스마트 커밋": 쿼리 창 처리기가 설정을 따르고, 스마트 커밋이 바꾼 세션만 커밋·롤백 뒤 열린 트랜잭션이 없을 때 자동 커밋으로 되돌림. Options > Connection Types에 두 칸). 조사 당시: **조용히 끊김** | 위 요약 4 | 높음 | 설정 화면도 없음(C15). 2026-11-07: 효과가 없어 연결 유형 편집에도 두지 않음([swing-options.md](../swing-options.md)) |
| S17 | Commit / Rollback 명령 | `core` 트랜잭션 도구 막대 | **있음** (2026-11-07, [transactions.md](../transactions.md), 영역 A-C1). 조사 당시: 없음 | swing-frontend.md:1898 "만들지 않았다… 결정이 필요" | 높음 | 수동 커밋 연결은 `COMMIT`을 직접 실행해야 함 |
| S18 | 실행 중 자동/수동 커밋 전환(도구 막대·메뉴) | `DataSourceAutoCommitHandler`, `DataSourceTransactionModeContributor` | 없음 | 연결 편집에서만 바꿈(`AutoCommitApplier`, swing-frontend.md "자동 커밋 상태") | 높음 | 도구 막대·메뉴 토글은 결정 대기(:1898). 2026-11-08 다시 확인: 그대로(상태 줄 칸은 연결 편집을 엶, `SqlEditorPanel.java`:515) |
| S18a | 상태 줄에서 커밋 방식 바꾸기 | (SWT 상태 표시와 도구 막대) | 의도적 제외 | swing-frontend.md:1824 사용자 결정 "상태표시줄에서 바꾸면 안된다" | 중간 | 상태 줄은 표시와 설정 안내만 |
| S19 | 격리 수준 바꾸기 | `DataSourceTransactionModeContributor` | 없음 | Swing grep `isolation` 0건 | 중간 | 조사 당시: 연결 설정에도 없음(C11). 2026-11-08: 연결의 기본 격리 수준은 연결 대화상자에 생김(C11), 실행 중 세션에서 바꾸기는 여전히 없음 |
| S20 | 트랜잭션 로그(현재 트랜잭션의 문 목록), 트랜잭션 모니터(문 수·경과 시간) | `core` `TransactionLogDialog`, `TransactionMonitorToolbar` | **있음** (2026-11-07: 모니터는 #97 상태 줄 칸([idle-transactions.md](../idle-transactions.md)), 로그는 [transactions.md](../transactions.md), 지난 트랜잭션 보이기는 없음). 조사 당시: 없음 | Swing grep `QMTransaction`/`TransactionLog` 0건 | 높음 | 끝낼 때·AI 도구만 열린 트랜잭션을 찾음(`ExitTransactionCheck`, `AutoCommitApplier`) |
| S21 | 미결 트랜잭션 보기 | `core` pending transactions view | **있음** (2026-11-07, [transactions.md](../transactions.md) "열린 트랜잭션 목록", 영역 A-C3). 조사 당시: 없음 | 지시서의 예, grep 0건 | 높음 | |
| S22 | 편집기별 별도 연결(트랜잭션 분리) | `SQLEditor` 격리 실행 문맥(`SEPARATE_CONNECTION`) | **있음** (2026-11-07, [transactions.md](../transactions.md) "쿼리 창마다 별도 연결": `QueryWindowContext`, SWT와 같은 키·기본값, Options와 연결 대화상자에서 바꿈). 조사 당시: 없음 | `SqlEditorPanel` Javadoc "실행 문맥은 연결의 기본(주) 문맥", swing-frontend.md:708·1018·1293 | 높음 | 같은 연결의 쿼리 창들이 한 트랜잭션을 나눠 씀. 결과 저장도 같은 문맥에서 자동 커밋을 끔 |
| S23 | 끊기·다시 연결·삭제 전 열린 트랜잭션 확인 | `ui.editors.connection` `DataSourceHandler.checkAndCloseActiveTransaction`(`:168, :206-228`) | **수정함** (2026-11-07, #97, [idle-transactions.md](../idle-transactions.md) "연결 끊기 전 질문", 영역 A-S3). 조사 당시: 조용히 끊김 | 위 요약 1 | 높음 | 끝낼 때(`ExitTransactionCheck`)만 묻는다 |
| S24 | 유휴 트랜잭션 자동 종료 | `core` `UIServiceConnectionsImpl`, 앱의 사용자 활동 시각 | **수정함** (2026-11-07, #97, [idle-transactions.md](../idle-transactions.md): 마지막 문장 기준 유휴, 기본은 알림과 커밋 / 롤백 / 열어 두기, 연결에서 켰을 때만 자동 롤백 뒤 알림, 영역 A-S1). 조사 당시: 조용히 끊김 | 위 요약 5 | 높음 | 영역 A와 중복 |
| S25 | 서버 출력(Oracle `DBMS_OUTPUT` 등) | `show.output`, `SQLEditorOutputViewer`, `DBCServerOutputReader` | 있음(2026-11-07) | `SqlEditorPanel.readServerOutput`: 문장마다 읽어(버퍼를 비움) Messages 탭에 씀. [swing-server-output.md](../swing-server-output.md) | 중간 | SWT의 별도 Output 패널(심각도 필터, 자동 표시) 대신 Messages 탭. SQL Server `PRINT`, PostgreSQL `NOTICE`는 전처럼 한 번만 나옴. 2026-11-08: Oracle 설정을 바꾸면 열린 쿼리 창 세션에도 다음 문장부터 적용([model-fixes.md](../model-fixes.md)) |
| S26 | 실행 로그(편집기별 쿼리 관리자 로그) | `show.log`, `SQLLogPanel`, `QueryLogViewer` | 일부 | `QueryHistory`/`QueryHistoryDialog`(swing-sql-editor-plus.md "쿼리 기록"), swt-removal.md:112 | 중간 | 편집기가 실행한 문만 기록. 내부 쿼리·트랜잭션 표시 없음 |
| S27 | 실행 계획(예상·실제) | `run.explain`, `ExplainPlanViewer` | 있음 | `ExecutionPlanView`, `PlanDiagramView`, `PlanRawView`, swing-sql-editor-plus.md | 중간 | 계획 파일 불러오기(`load.plan`)는 없음, 저장은 원본 보기에서 됨 |
| S28 | 아웃라인 | `show.outline`, `SQLEditorOutlinePage` | 일부 | 2026-11-08 View &gt; Outline 도구 창(`SqlOutlinePanel`, Ctrl+Alt+O): 문장 목록, 고르면 편집기에서 선택 ([swing-sql-editor-ergonomics.md](../swing-sql-editor-ergonomics.md)) | 낮음 | 문장 안 구조(SELECT 목록, FROM 표) 나무 없음 |
| S29 | 템플릿 | `SQLTemplateStore`, `PrefPageSQLTemplates` | 있음 | `SqlTemplates`, `SqlTemplatesPage` | 낮음 | 테이블·열 이름 변수 해석기, linked mode 없음(swing-sql-editor-plus.md:270) |
| S30 | SQL 서식 | `SQLFormattingStrategy`, `PrefPageSQLFormat`, 외부·SQL Workbench/J 서식기 | 일부 | `SqlEditCommands`, Options Formatting(Default/Compact, 키워드 대소문자) | 낮음 | 서식기 세부 설정·외부 서식기 없음 |
| S31 | 접기, 모두 펴기·접기 | `FoldingsEnabled`, `Expand/CollapseAllFoldings` | 있음 | 2026-11-08 `SqlFoldParser`(문장, BEGIN/CASE…END, 여러 줄 괄호, 블록 주석), Edit &gt; Advanced &gt; Folding ([swing-sql-editor-ergonomics.md](../swing-sql-editor-ergonomics.md)) | 낮음 | 접기 켜기·끄기 설정은 없음(늘 켬) |
| S32 | 찾기·바꾸기 | Eclipse find/replace | 있음 | `FindReplaceDialog`, `SqlEditorPanel.java:1746-1753` | 중간 | |
| S33 | 주석 넣기·빼기 | `comment.single`, `comment.multi` | 있음 | `SqlEditCommands`(Ctrl+K Ctrl+C/U), 2026-11-08 블록 주석 토글(Ctrl+Shift+/, `SqlTextTools.toggleBlockComment`) | 낮음 | |
| S34 | 자동완성 | `SQLCompletionProcessor`, `PrefPageSQLCompletion` | 일부 | `SqlCompletionPopup`, Options(지연, 프로시저 제안), CALL/EXEC 뒤 루틴 제안과 `EXEC dbo.` 예외 수정(2026-11-07, [swing-sql-completion.md](../swing-sql-completion.md)) | 중간 | 2026-11-08 제안 설명 창 추가([swing-sql-editor-ergonomics.md](../swing-sql-editor-ergonomics.md)). 변수·명령 제안, 세부 설정 없음(swing-frontend.md:1143). PL/SQL 블록 안 프로시저 호출, 스키마를 붙인 값 자리(`SELECT dbo.fn`)의 함수 제안 없음 |
| S35 | 마우스 올림 정보, Ctrl+클릭으로 개체 열기, 같은 단어 강조 | `SQLAnnotationHover`, `SQLHyperlinkDetector`, `navigate.object`, `SQLOccurrencesHighlighter` | 있음 | 2026-11-08 `SqlObjectNavigation`(의미 분석으로 별칭·열까지 풀기, 마우스 올림 설명, Ctrl+클릭), `setMarkOccurrences` ([swing-sql-editor-ergonomics.md](../swing-sql-editor-ergonomics.md)) | 낮음 | Ctrl+클릭한 DB 개체는 Object Explorer에서 고름(개체 편집기 없음). 같은 단어 강조는 대소문자가 같은 것만 |
| S36 | 의미 분석 오류 표시, 빠른 수정 | `SQLEditorSemanticMarkersManager`, `SQLEditorQuickAssistProcessor` | 없음 | grep 0건 | 낮음 | |
| S37 | 글자 도구: 줄 바꿈 토글, 공백 다듬기, 목록 변환, 소스 코드로 복사(Java·Python 등), 웹 검색 | `word.wrap`, `trim.*`, `morph.delimited.list`, `convert/*`, `search.web` | 있음 | 2026-11-08 Edit &gt; Advanced(`SqlTextTools`, 자동 줄 바꿈 Ctrl+Alt+Shift+W) ([swing-sql-editor-ergonomics.md](../swing-sql-editor-ergonomics.md)) | 낮음 | 대소문자 바꾸기는 있음(S30). 소스 코드로 복사는 옵션 대화상자 없이 기본 옵션 |
| S38 | 스크립트 파일 관리(프로젝트 Scripts 폴더, 연결별 스크립트 목록, 이름 바꾸기·삭제, 스크립트에 연결 기억, 자동 저장, 다시 시작할 때 열린 편집기 복원) | `showScripts`, `rename`, `deleteThisScript`, `ScriptsHandlerImpl`, `SQLEditorHandlerOpenEditor` | 있음(2026-11-08. 조사 당시: 일부) | `SqlFileContent`, `RecentFiles`, File Open/Save; Scripts 도구 창(2026-11-08: 폴더·이름 바꾸기·삭제·연결 묶기, `ScriptsPanel`); 자동 저장·다시 시작할 때 쿼리 창 복원(2026-11-08, `QueryWindowRecovery`: 연결·기본 DB/스키마, 연결하지 않고 복원); 연결별 최근 스크립트(2026-11-08, Object Explorer > Recent Scripts, `ScriptFiles.findLinkedScripts`); swt-removal.md:108 | - | SWT 자동 저장은 스크립트 파일에 썼고, Swing은 작업 공간의 복원 파일에 쓴다(파일 저장은 Save 질문 그대로). [swing-scripts-explorer.md](../swing-scripts-explorer.md), [swing-query-window-recovery.md](../swing-query-window-recovery.md) |
| S39 | 연결·기본 DB/스키마 바꾸기 | 활성 연결·스키마 선택(`SelectActiveSchemaHandler`) | 있음 | `SqlEditorPanel` 콤보, `SqlEditorToolBar` | 중간 | 탐색기와 연결 맞추기(`sync.connection`)는 없음 |
| S40 | DDL 실행 뒤 탐색기 갱신 | `SQLMetadataRefreshCoordinator` | **있음** (2026-11-08, [swing-query-commands.md](../swing-query-commands.md): `SqlMetadataRefresh`, 바뀐 개체의 스키마·DB를 실행 뒤, 수동 커밋이면 트랜잭션이 끝난 뒤 다시 읽음). 조사 당시: 일부 | Modify·New 창만 노드를 다시 읽음(`setExecutionFinishedListener`, swing-source-editor.md) | 중간 | 조사 당시: 일반 쿼리 창의 CREATE/DROP은 F5로 새로 고쳐야 보임 |
| S41 | SQL 만들기(INSERT·UPDATE·DELETE·MERGE·JOIN, 결과 행에서, 결과로 DDL) | `GenerateSQLDialog`, `SQLGeneratorContributor`, `generate.ddl.by.resultSet` | 일부 | `ObjectScripts`(CREATE·SELECT), `SourceEditCommands`(ALTER); swing-frontend.md:1020 | 중간 | 2026-11-07 탐색기 Generate SQL(SELECT·INSERT·UPDATE·DELETE·MERGE·JOIN·DDL·CALL, `ObjectScripts.getGenerators`, C-17). 결과 행에서 만들기(`dataInsert` 등)와 결과로 DDL은 없음(결과 클래스 grep 0) |
| S42 | 저장 프로시저 실행(매개변수 입력) | `procedure.execute`, `ProcedureParameterBindDialog` | 있음(2026-11-08) | Object Explorer "Execute Procedure…"(`ExecuteProcedureDialog`, 모델 `ProcedureCallScript`: 방언별 호출 스크립트, OUT 값), [swing-routines.md](../swing-routines.md) | 중간 | SSMS "Execute Stored Procedure"에 해당. 조사 당시: 없음 |
| S43 | SQL 터미널 | `ui.editors.sql.terminal` | 있음(2026-11-08) | Query > SQL Terminal(Ctrl+T), `SqlTerminalView`, `MainFrame.updateTerminalTab` | 낮음 | 쿼리 창마다 켜고 끔(저장 안 함). SWT 설정 3개는 없음. [swing-sql-terminal.md](../swing-sql-terminal.md) |
| S44 | 결과 창 배치(최대화, 가로/세로, 숨기기) | `maximize.result.panel`, `toggleLayout`, `toggle.result.panel` | 있음 | 도킹 도구 창(`ToolWindowDocking`), Results 자동 숨김(swing-frontend.md "Results 자동 숨김"), 2026-11-08 View &gt; Results Beside Editor·Maximize Results(Ctrl+Shift+T, `ToolWindow.SplitHost`) ([swing-sql-editor-ergonomics.md](../swing-sql-editor-ergonomics.md)) | 낮음 | 놓는 방향은 전역 |
| S45 | SQL 편집기 설정 화면 | `PrefPageSQLEditor`, `CodeEditing`, `Completion`, `Execute`, `Format`, `Dialects`, `Resources` | 일부 | `OptionsDialog`(Query Editor, Formatting, Templates, 글꼴·색), swt-removal.md:116 | 중간 | 조사 당시: 실행·방언·코드 편집 설정 없음. 2026-11-07·08 실행(`SqlExecutionOptionsPage`)·자동완성(`CodeCompletionOptionsPage`) 설정 더함. 코드 편집(따옴표·괄호 자동 닫기 등)·방언·스크립트 자원 설정은 없음 |

## B. 결과 뷰어 (`ui.editors.data`, GIS는 `data.gis.view`)

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| R1 | 그리드 보기 | `SpreadsheetPresentation` | 있음 | `ResultSetGrid`, `ResultSetViewerPanel` | 높음 | |
| R2 | 텍스트 보기 | `PlainTextPresentation`, `switch.presentation` | 있음 (2026-11-08) | `ResultSetTextView`, 상태 줄 "텍스트"(Ctrl+`), [swing-grid-navigation.md](../swing-grid-navigation.md). 조사 당시: 없음(grep `PlainText` 0건) | 낮음 | 읽기 전용, 20,000행까지 |
| R3 | 레코드 보기 | `toggleMode` | 있음 | `ResultSetRecordView` | 중간 | |
| R4 | 값 패널(글자·JSON·XML·이미지·Hex) | `ValueViewerPanel`, `ContentPanelEditor` | 있음 | `ResultSetValuePanel`, `ResultSetCellContent` | 중간 | 글자 값만 편집, LOB 읽기 전용 |
| R5 | 메타데이터 패널 | `MetaDataPanel` | 없음 | swing-result-viewer.md:179 | 낮음 | |
| R6 | 집계 패널 | `AggregateColumnsPanel`, `aggregateFunction` 확장 | 있음 (2026-11-08) | `ResultSetAggregatePanel`(함수 9개 고르기, 열마다·전체), [swing-grid-navigation.md](../swing-grid-navigation.md) "집계 패널". 조사 당시: 일부(상태 줄 집계만) | 낮음 | 합계·평균은 숫자 값만(SWT는 글자도 숫자로 읽음) |
| R7 | 참조 패널, 외래 키 따라가기(참조 행 열기, 참조하는 행 보기) | `ReferencesPanel`, `navigateLink`, `referencesMenu` | 있음 (2026-11-08) | Ctrl+B·Ctrl+Shift+1·Navigate 메뉴, 뒤로·앞으로(Alt+←/→), `ResultSetReferencesPanel`, [swing-grid-navigation.md](../swing-grid-navigation.md). 조사 당시: 없음 | 중간 | 참조 패널은 읽기 전용("열기"로 편집), 뒤로는 다시 읽지 않고 남긴 결과 |
| R8 | 그룹 패널(GROUP BY 보기, 중복만) | `GroupingPanel` | 없음 | grep `Grouping` 0건(결과) | 낮음 | |
| R9 | GIS(공간 데이터) 보기 | `data.gis.view` | 일부 (2026-11-08) | `ResultSetSpatialView`, `SqlServerSpatialReader`([swing-result-charts-spatial.md](../swing-result-charts-spatial.md)). 조사 당시: 없음(swt-removal.md:106) | 낮음 | 밑그림 지도(온라인 타일)·SRID 변환·GIS 설정·기하 값 편집 없음(폐쇄망용으로 일부러 뺌). SQL Server 바이트는 SWT보다 더 그림 |
| R10 | 필터 | `ResultSetFilterPanel`, `filterMenu`, `filterSave`, `filterSettings` | 일부 | `ResultSetColumnFilterPopup`, "Filter by Value"(swing-grid-column-filters.md) | 중간 | 저장한 필터, 필터 편집 대화상자, 고급 WHERE 입력 없음(swing-result-viewer.md:177). 2026-11-08 다시 확인: 그대로. WHERE 입력 줄은 사용자 요청으로 열 머리글 필터로 바꿨고([swing-grid-column-filters.md](../swing-grid-column-filters.md) "목적"), 저장한 필터·필터 기록은 그 문서의 "남긴 것" |
| R11 | 정렬(서버·표 안) | `toggleOrder`, `OrderingStrategy` | 있음 | `ResultSetViewerPanel`, swing-result-viewer.md "정렬과 필터" | 중간 | 2026-11-08 SWT와 다름: 서버 정렬이 쿼리의 ORDER BY를 바꾸고(SWT는 뒤에 덧붙임) 상태 줄에 정렬 요약·쿼리 순서로 되돌리기([swing-grid-column-filters.md](../swing-grid-column-filters.md) "정렬과 쿼리의 ORDER BY") |
| R12 | 페이지·더 읽기·모두 읽기, 최대 행 수 | `fetch.page`, `fetch.all`, `PrefPageResultSetMain` | 있음 | `ResultSetViewerPanel`, Options "Maximum rows" | 중간 | 조사 당시: JDBC fetch size 설정 없음. 2026-11-07 Options &gt; Query Results의 가져오기 크기(`ResultSetOptionsPage.java`:61) |
| R13 | 전체 행 수 세기 | `resultset.count` | 있음 (2026-11-08) | 상태 줄·Navigate "행 수 세기", 모델 `SQLQueryDataContainer.countData` 고침(늘 -1이었음), [swing-grid-navigation.md](../swing-grid-navigation.md) "전체 행 수". 조사 당시: 없음 | 낮음 | 다시 읽을 수 있는 결과만 |
| R14 | 형식별 값 편집기(날짜 선택, 불리언 확인란, 참조 값 고르기, 배열·구조체, 커서, LOB 파일 불러오기·저장, 외부 편집기, UUID 만들기) | `data/editors/*`, `data/managers/*`, `openWith`, `cell.save`, `generate.uuid` | 일부 | 2026-11-08 날짜·시각 선택기, 불리언·ENUM 목록, LOB 파일 불러오기·저장, LOB 값 패널 편집([swing-grid-editing.md](../swing-grid-editing.md)). 2026-11-08 참조 값 고르기(`ResultSetReferencePicker`), 배열·구조체(PostgreSQL)·hstore·SET 대화상자(`ResultSetValueDialogs`), 16진수 편집, 외부 편집기(저장하면 셀에 넣음), UUID 만들기([swing-value-editors.md](../swing-value-editors.md)) | 낮음 | 커서 보기, 일반 JDBC 구조체(Oracle 객체 형식) 편집은 남음 |
| R15 | 이진 표시 형식, 데이터 형식 설정 화면 | `BinaryFormatAction`, `PrefPageDataFormat` | **있음** (2026-11-08 다시 확인: 이진 값 표시 형식·길이는 Options &gt; Query Results와 연결별 값(`ResultSetOptionsPage.java`:64, 144), 형식 프로필·숫자·과학적 표기는 Options &gt; Data Formats(`DataFormatOptionsPage`), [swing-options.md](../swing-options.md)). 조사 당시: 일부 | Options "Show date/time values in the database format" 하나 | 낮음 | 결과 표 안에서 이진 형식을 바로 바꾸는 단추는 없음(설정 화면에서 바꿈) |
| R16 | 고급 복사(설정 대화상자, JSON·XML·HTML·SQL INSERT 등 처리기별 복사), 특수 붙여넣기 | `ResultSetHandlerCopySpecial`, `CopyAs`, `PasteSpecial` | 있음 (2026-11-08) | `ResultSetCopyFormats`(CSV·TSV·Markdown·SQL IN·JSON·XML·HTML·SQL INSERT, 고급 복사), `ResultSetPaste`(여러 칸 붙여넣기, 특수 붙여넣기), [swing-grid-editing.md](../swing-grid-editing.md) | 중간 | 복사 형식은 데이터 전송 처리기가 아니라 표의 값으로 만듦 |
| R17 | 그리드에서 내보내기 | `resultset.export` | 있음 | `ResultSetExport`(CSV·XLSX), `DataExportWizard`(Export Data…) | 중간 | |
| R18 | 편집·저장(셀, NULL, 행 추가·삭제, 저장·취소) | `ResultSetViewer`, `ResultSetPersister` | 있음 | `ResultSetEditModel`, `ResultSetEditPersister`(swing-frontend.md G단계) | 높음 | 자동 커밋 연결은 한 트랜잭션으로 저장 |
| R19 | 저장 전 스크립트 미리 보기, 연결 종류 "데이터 변경 확인" | `ResultSetViewer.java:4735`, `SavePreviewDialog`, `toggleConfirmSave` | 있음 (2026-11-07 수정, 조사 때 조용히 끊김) | `ResultSetGrid.confirmSave`, `ScriptPreviewDialog.confirmSave`, Options "Show the SQL script before saving grid changes" | 높음 | Test·Production 기본 켬. 도구 막대 토글·연쇄 삭제 선택 없음 |
| R20 | 저장하고 커밋, 변경 스크립트 보기, 연쇄 삭제 옵션 | `applyAndCommitChanges`, `generateScript`, `SaveScriptDialog` | 일부 (2026-11-08) | 저장하고 커밋(수동 커밋 연결, 그 전 변경은 먼저 물음), 변경 미리 보기([swing-grid-editing.md](../swing-grid-editing.md)) | 중간 | 연쇄 삭제는 남음 |
| R21 | 행 복제·위/아래 행 복사, 기본값으로, 칸 되돌리기, 실행 취소 | `row.copy*`, `cell.setDefault`, `cell.reset` | 있음 (2026-11-08) | `ResultSetGrid`·`ResultSetEditModel`, SWT 단축키([swing-grid-editing.md](../swing-grid-editing.md)) | 낮음 | 셀 되돌리기에 Esc 없음(표의 편집 취소 키) |
| R22 | 가상 키(키 없는 테이블 편집), 가상(계산) 열, 가상 외래 키 | `controls/resultset/virtual/*` | 일부 (2026-11-08) | 가상 고유 키(읽기 전용 줄의 "가상 키 정하기…"), 가상 외래 키 추가·지우기(`ResultSetVirtualKeys`, 가상 모델에 저장), [swing-grid-navigation.md](../swing-grid-navigation.md) "가상 키". 조사 당시: 없음 | 중간 | 가상(계산) 열은 남음 |
| R23 | 값에 따른 색, 행 색, 그룹 줄무늬 | `colors/*` | 없음 | grep 0건 | 낮음 | |
| R24 | 열 숨기기·순서·고정·빈 열 숨기기, 행·열로 가기, 확대·축소 | `grid.*` 명령, `zoomIn/Out` | 일부 (2026-11-08) | 숨기기·모두 보이기·빈 열 숨기기, 머리글 끌어 옮기기, Ctrl+G·Ctrl+Shift+G, Alt+0/Alt+9(`ResultSetColumnLayout`), [swing-grid-navigation.md](../swing-grid-navigation.md). 조사 당시: 열 너비 맞추기만 | 낮음 | 열 고정(pin)은 남음 |
| R25 | 결과 안 찾기 | Find/Replace on grid | 있음 (2026-11-08) | Ctrl+F 찾기 줄(`ResultSetFindBar`), [swing-grid-navigation.md](../swing-grid-navigation.md). 조사 당시: 없음 | 낮음 | 찾기만(바꾸기 없음), 읽은 행만 |
| R26 | 결과 뷰어 설정 화면 | `PrefPageResultSetMain/Presentation/Editors` | 일부 | Options: 최대 행 수, 날짜 형식 | 낮음 | 2026-11-08 다시 확인: Options &gt; Query Results가 읽기·정렬·저장 전 스크립트·이진 형식 등을 다룸(`ResultSetOptionsPage.java`:131-137), Binary and LOB 페이지. 표시(Presentation: 열 너비, 홀수 행 색 등)·편집기 페이지 나머지는 없음 |
| R27 | 오류 위치로 가기 | `GoToErrorAction`, `ErrorPresentation` | 있음 (2026-11-08) | Messages의 오류를 두 번 누르기·오른쪽 클릭 "오류 위치로 가기"(`QueryResultsView`, `SqlEditorPanel.ErrorReport`), [swing-grid-navigation.md](../swing-grid-navigation.md). 조사 당시: 일부(Line/Column 표시만) | 낮음 | `@include` 파일의 오류는 위치 없음 |
| R28 | 힌트·변환기(참조 힌트, 복합형 펼치기, 자료형 색) | `dataHintProvider`, `TransformerAction` | 없음 | grep 0건 | 낮음 | |
| R29 | 서버 정렬 전 확인 대화상자 | `ResultSetViewer` 정렬 확인 | 의도적 제외 | swing-result-viewer.md:90 "확인 대화상자는 띄우지 않는다" | 낮음 | |
| R30 | 데이터를 바꾸는 쿼리를 다시 실행하기 전 확인 | `verifyQuerySafety` | 의도적 제외 | swing-result-viewer.md:100-101: 묻는 대신 SELECT 하나만 다시 읽음 | 낮음 | 더 안전한 쪽으로 바꿈 |

## C. 개체 편집기 (`ui.editors.entity`, `ui.editors.base`, `ui.editors.erd`)

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| E1 | 속성 탭(보기) | `EntityEditor` 속성 페이지 | 일부 | `PropertiesPanel` Javadoc "읽기 전용이다" | 중간 | 보기는 됨, 고치기는 E2 |
| E2 | 속성 편집·저장(모든 개체·모든 DB) | `EntityEditor` + `DBEObjectManager` | 일부 | `TableDesignerPanel`(PostgreSQL·SQL Server·MySQL만, `design/*Driver`), `PrincipalPropertiesDialog`; swing-table-designer.md:121-124 | 중간 | 조사 당시: Oracle·DB2·Generic 테이블, 시퀀스 등 편집 없음. 2026-11-08 Oracle 테이블 디자이너(`design/OracleTableDesignerDriver`, D-O18). DB2·Generic 인덱스·외래 키, 시퀀스 등 다른 개체 속성 편집은 없음 |
| E3 | DDL 탭 | `SQLSourceViewer` | 있음 | `DdlViewerPanel`, Script as CREATE(`ObjectScripts`) | 중간 | |
| E4 | 소스 편집(뷰·프로시저·함수·트리거·패키지) | 소스 탭 + Ctrl+S | 있음 | `SourceEditCommands`, `SourceScripts`(swing-source-editor.md) | 높음 | Modify → 쿼리 창 실행으로 흐름을 바꿈 |
| E5 | 데이터 탭 | `DatabaseDataEditor` | 있음 | Select Top 1000 Rows(쿼리 창, 편집 가능) | 중간 | |
| E6 | ER 탭·ERD 편집기 | `ui.editors.erd` | 일부 | `ErdDiagramPanel`(보기·배치 저장·PNG/SVG/.erd 내보내기) | 낮음 | 다이어그램에서 편집, 메모, .erd 열기, 인쇄 없음(swing-erd.md:96-100) |
| E7 | 개체별 하위 목록 탭(열·제약·인덱스·참조·트리거·종속성·통계·파티션) | `ObjectListControl` 탭 | 있음 | 2026-11-08 개체 세부 정보(F7, `ObjectDetailsPanel`): 자식 폴더마다 속성 열이 있는 목록 탭, 인라인 자식(인덱스 열, Oracle 종속 개체)은 들여 쓴 행. 탭은 드라이버 모델의 구성: PostgreSQL·Oracle은 종속성·참조, SQL Server는 참조([swing-explorer-productivity.md](../swing-explorer-productivity.md)). 조사 당시: 일부(트리 + Properties) | 중간 | SQL Server 종속성은 모델에 폴더가 없어 없음 |
| E8 | DB별 편집기 페이지(`ext.*.ui`: 권한, 컴파일 등) | `databaseEditor` 확장 | 일부 | 보안 창(swing-security.md), 관리 도구(swing-admin-tools.md); swt-removal.md:117 | 중간 | |
| E9 | 개체 만들기 대화상자(열·제약·FK·인덱스·시퀀스·프로시저 이름, 사전) | `EditObjectDialog`, `Edit*Page`, `CreateSequencePage`, `EditDictionaryPage` | 일부 | `DesignerDialogs`(PostgreSQL·SQL Server·MySQL/MariaDB·Oracle), New View/Procedure/Function 틀, 2026-11-08 폴더의 New Sequence… 등 틀과 이름·데이터베이스 대화상자([swing-create-objects.md](../swing-create-objects.md)) | 중간 | 사전(가상 모델 설명 열) 없음: Swing에 쓰는 곳이 없음. 조사 당시: 시퀀스·사전 없음 |
| E10 | 저장 흐름과 스크립트 미리 보기 | `EntityEditor` 저장 확인 | 있음 | `ScriptPreviewDialog`(Execute / Open in Query Window / Copy) | 높음 | 디자이너·보안 창에서 |
| E11 | 개체 삭제·이름 바꾸기(스크립트 미리 보기, 연쇄 옵션) | `DBEObjectMaker.deleteObject`, `DBEObjectRenamer` | **있음** (2026-11-07, #119: Object Explorer의 Delete·Rename(F2)이 객체 관리자가 있는 DB 객체에서 켜짐, 연쇄 삭제·기존 연결 닫기 옵션, 늘 스크립트 미리 보기(`ExplorerObjectCommands.java`:318-420, :530), C-10·C-11, [swing-explorer-object-actions.md](../swing-explorer-object-actions.md)). 조사 당시: 없음 | Delete는 연결·로그인·사용자·역할만(swing-security.md), Rename은 연결만 | 중간 | DROP은 쿼리로 직접. 영역 A(탐색기)와 겹칠 수 있음 |
| E12 | `openEntityEditor` 호출 | `DesktopUI.openEntityEditor` | **조용히 끊김** | 위 요약 8 | 낮음 | 2026-11-08 다시 확인: 그대로(`SwingPlatformUI.java`:585-595, 디버그 로그만). Swing 가져오기·복사 마법사는 "끝나면 테이블 열기"를 끄고 시작하지만(`DataImportWizard.java`:196, `DatabaseCopyPages.java`:251), SWT에서 저장한 데이터 전송 작업은 기본값이 켜짐이라 여기에 닿는다 |

## D. 연결 편집기 (`ui.editors.connection`)

swing-security.md는 DB 보안(로그인·역할·권한) 문서이고 연결 보안 설정이 아니다. 연결 쪽은 swing-conn-advanced.md와 swing-frontend.md "Connect 대화상자"가 다룬다.

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| C1 | 일반: 이름, 연결 종류, 색, 읽기 전용 | `ConnectionPageGeneral` | 있음 | `ConnectionSettingsPanel`, `ConnectionColors` | 중간 | |
| C2 | 설명, 연결 폴더, 탐색기 설정(시스템 개체·단순 보기 등) | `ConnectionPageGeneral`, `EditConnectionNavigatorSettingsDialog` | 일부 | 폴더는 Registered Servers 그룹(`RegisteredServerCommands`); grep `NavigatorSettings` 0건 | 낮음 | 탐색기 설정 없음. 설명은 2026-11-08 Connection Properties 탭에 더함([swing-conn-advanced.md](../swing-conn-advanced.md) "4. 일반 설정") |
| C3 | 드라이버 속성, 공급자 속성 | `DriverPropertiesDialogPage`, `ConnectionPropertiesControl` | 있음 | `ConnectionPropertiesTable` | 중간 | |
| C4 | 인증 방식과 방식별 칸 | `AuthModelSelector`, `*AuthConfigurator` | 일부 | `ConnectionAuthModels`, `ConnectionAuthExtras` | 중간 | 셸 명령 비밀번호, ClickHouse SSO·OIDC 칸 없음. BigQuery·Snowflake·Spanner 칸은 2026-11-08 더함([swing-conn-advanced.md](../swing-conn-advanced.md) "1. 인증 방식") |
| C5 | 저장 안 한 비밀번호의 인증 방식 자격 증명 묻기 | `AuthModelCredentialsDialog`, `DesktopUI.promptAuthModelCredentials` | **수정함** (2026-11-07, #106, `SwingPlatformUI.promptAuthModelCredentials`(`SwingPlatformUI.java`:399): 인증 방식의 칸·비밀번호 저장을 묻고 연결, [swing-auth-prompts.md](../swing-auth-prompts.md), 영역 A-S5). 조사 당시: 조용히 끊김 | 위 요약 2 | 높음 | 조사 당시: 실행 확인 못 함(코드 경로). 수정 전 빌드에서 실행으로 확인한 뒤 고침(영역 A S5) |
| C6 | SSH 터널 | `SSHTunnelDefaultConfiguratorUI` | 있음 (2026-11-08: 점프 서버 목록 편집 `SshJumpServersDialog`). 조사 당시: 일부 | `SshTunnelPanel` | 중간 | |
| C7 | 프록시 | `SocksProxyConfiguratorUI` | 있음 | `SocksProxyPanel` | 낮음 | SOCKS만(HTTP는 모델에 없음) |
| C8 | SSL | `SSLConfiguratorTrustStoreUI`, DB별 SSL 화면 | 일부 | `SslHandlerPanel`(PostgreSQL·MySQL·SQL Server, 2026-11-08 ClickHouse·Databend·TiDB Lake) | 중간 | PostgreSQL SSL factory 목록 없음(직접 입력) |
| C9 | 네트워크 프로필 편집 | `PrefPageNetworkProfiles` 등 | 있음 (2026-11-08). 조사 당시: 없음 | `NetworkProfilesDialog`, 연결 대화상자의 Network profile 고르기([swing-conn-advanced.md](../swing-conn-advanced.md) "네트워크 프로필") | 낮음 | |
| C10 | 연결 직후 SQL(bootstrap) | `EditBootstrapQueriesDialog` | 있음 | `BootstrapQueriesDialog` | 중간 | |
| C11 | 초기화 설정(자동 커밋, 기본 DB/스키마, keep-alive, 유휴 연결 닫기, 격리 수준) | `ConnectionPageInitialization` | **있음** (2026-11-07, 연결 대화상자의 기본 격리 수준(`ConnectionSettingsPanel.java`:187, 882, 1236), [swing-options.md](../swing-options.md)). 조사 당시: 일부 | `ConnectionSettingsPanel` Options; 격리 수준 grep 0건 | 중간 | 조사 당시: 격리 수준만 없음 |
| C12 | 셸 명령(연결 전·후 이벤트) | `ConnectionPageShellCommands` | 있음 (2026-11-08). 조사 당시: 없음 | Options의 Shell Commands 탭(`ConnectionShellCommandsPage`, [swing-conn-advanced.md](../swing-conn-advanced.md) "셸 명령") | 낮음 | 실행 전 승인(#99)은 그대로. 편집한 명령도 처음 실행할 때 묻는다 |
| C13 | 연결 보안 제한(권한 편집), 연결 잠금 | `EditConnectionPermissionsDialog` | **일부** (2026-11-07, 연결 유형마다 제한 확인란(데이터 편집, 구조 편집, 스크립트 실행, 데이터 가져오기), Options &gt; Connection Types(`ConnectionTypesPage.java`:109, 209)). 연결마다 제한 편집과 연결 잠금은 없음. 조사 당시: 없음 | grep `setModifyPermission` 0건 | 중간 | 조사 당시: 편집 화면이 없고 S8처럼 일부 제한은 무시됨(S8은 2026-11-07 고침) |
| C14 | 연결 종류 편집기(새 종류, 자동 커밋·실행 확인·데이터 변경 확인·스마트 커밋·자동 닫기 기간·색) | `PrefPageConnectionTypes` | **있음** (2026-11-07, Options &gt; Connection Types(`ConnectionTypesPage.java`:84-108, `OptionsDialog.java`:132): 새 종류·삭제, 이름·설명·색, 자동 커밋, 실행 확인, 데이터 변경 확인, 유휴 트랜잭션·연결. 스마트 커밋·되돌리기는 2026-11-08, [swing-options.md](../swing-options.md)). 조사 당시: 없음 | `ConnectionSettingsPanel.java:632`는 종류 목록만 고름 | 중간 | 조사 당시: 기본 3종류의 값을 바꿀 수 없음 |
| C15 | 트랜잭션 설정(스마트 커밋·복귀, 유휴 트랜잭션 자동 닫기·시간) | `PrefPageTransactions` | 일부 (2026-11-08 스마트 커밋·되돌리기는 Options > Connection Types, [connection-lifecycle.md](../connection-lifecycle.md). 유휴 트랜잭션은 연결 대화상자, [idle-transactions.md](../idle-transactions.md)). 조사 당시: 없음 | grep `TRANSACTIONS_SMART_COMMIT`·`TRANSACTIONS_AUTO_CLOSE` Swing 0건 | 중간 | S16·S24의 설정 쪽 |
| C16 | 클라이언트 식별(애플리케이션 이름 끄기·바꾸기, 환경 변수 쓰기) | `PrefPageConnectionClient` | **있음** (2026-11-08, Options &gt; Client Identification과 연결별 값(`ClientIdentificationOptionsPage.java`:88-89), [swing-options.md](../swing-options.md)). 조사 당시: 없음 | Swing grep `META_CLIENT_NAME` 0건, 모델 키 `ModelPreferences.java:216-218` | 낮음 | 이름은 늘 `GeneralUtils.getProductTitle()`(swing-followup-fixes.md "Oracle 프로그램 이름") |
| C17 | 오류 처리(재연결 등), 메타데이터 읽기 설정 | `PrefPageErrorHandle`, `PrefPageMetaData` | **있음** (2026-11-08, Options &gt; Errors and Timeouts·Metadata와 연결별 값(`ConnectionErrorsOptionsPage.java`:100-121, `MetadataOptionsPage.java`:87-107), [swing-options.md](../swing-options.md)). 오류 때 롤백·세션 단위 취소는 트랜잭션 안전 규칙으로 두지 않음. 조사 당시: 없음 | grep 0건 | 낮음 | 조사 당시: 모델 기본값으로 동작 |
| C18 | 드라이버 관리 나머지(Maven 아티팩트, 의존성 트리, 전역 클래스패스) | `DriverEditDialog`, `PrefPageDrivers*` | 있음 (2026-11-08, 조사 때 일부) | `MavenArtifactDialog`, `DriverLibraryDetailsDialog`, `GlobalLibrariesDialog`([swing-driver-manager.md](../swing-driver-manager.md)) | 낮음 | Maven 저장소 목록과 라이브러리 내보내기는 남음 |
| C19 | 클라이언트 홈 선택 | `ClientHomesSelector`, `ClientHomesPanel` | 있음 (2026-11-08, 조사 때 일부) | 드라이버의 설치 목록은 드라이버 편집 창 Local Client 탭(`ClientHomesPanel`), 연결마다 고르기는 백업 대화상자(D-P6, M2). 새 연결은 기본 홈(`ConnectionDialog.java:340`) | 낮음 | Oracle OCI 홈은 SWT에서도 상용 기능이라 옮기지 않음(swing-conn-advanced.md:165) |
| C20 | URL로 새 연결, 드라이버 종류 바꾸기 | `new.connection.from.url`, Driver type | 일부 | Connect with: JDBC URL 방식은 있음 | 낮음 | |
| C21 | 현재 사용자 비밀번호 바꾸기 | `connection.changeCurrentPassword`, `PasswordChangeDialog` | 있음 (2026-11-08: Object Explorer·Registered Servers의 Change Password…). 조사 당시: 일부 | 만료 비밀번호 묻기(`SwingPlatformUI.promptUserPasswordChange`)와 메뉴(`ConnectionCommands.changePassword`, [swing-conn-advanced.md](../swing-conn-advanced.md) "비밀번호 바꾸기") | 낮음 | |
| C22 | Test Connection | `ConnectionWizard.testConnection` | 있음 | `ConnectionDialog`, swing-conn-advanced.md "5. Test Connection" | 중간 | |

## E. 그 밖의 편집기 (`session`, `locks`, `acl`, `hex`, `image`, `json`, `xml`)

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| O1 | 세션 관리자 | `ui.editors.session` `SessionManagerViewer` | 일부 | `SessionManagerPanel`, swing-admin-tools.md "Activity Monitor — Processes" | 중간 | SQL Server·MySQL/MariaDB·PostgreSQL·Oracle만. SWT는 세션 관리자가 있는 다른 DB도 지원. 2026-11-08 다시 확인: Altibase, 세션 관리자 어댑터가 있는 DB(DB2·Exasol·YashanDB 등)는 "그 밖" 분기로 나옴(`ServerAdminProfile`, D-A1) |
| O2 | 잠금 관리자(그래프) | `ui.editors.locks` `LockGraphicalView`, `LockManagerViewer` | 일부 | `LockManagerPanel`(트리·표, 4개 DB, 2026-11-08 Altibase를 더해 5개) | 중간 | 그래프 보기 없음 |
| O3 | 개체 권한(ACL) 편집기 | `ui.editors.acl` `ObjectACLEditor` | 일부 | `ObjectPermissionsDialog`(SQL Server·PostgreSQL·MySQL) | 낮음 | CE에서 `ObjectACLEditor`를 쓰는 확장은 없었음(`plugin.xml` 비어 있음). Oracle 없음 |
| O4 | HEX 편집기 | `ui.editors.hex` `BinaryEditor`, `HexEditControl` | 일부 | 값 패널 Hex 보기(256 KiB, 읽기 전용) | 낮음 | 편집·찾기·이동 없음(swt-removal.md:119) |
| O5 | 이미지 편집기 | `ui.editors.image` `ImageViewer` | 일부 | 값 패널 Image 보기 | 낮음 | 조사 당시: 확대·저장·불러오기 없음. 2026-11-08 LOB 파일 불러오기·저장(`ResultSetLobFiles`, B-R14). 확대는 없음(맞춰 줄이기만) |
| O6 | JSON 편집기 | `ui.editors.json` `JSONTextEditor`, `JSONPanelEditor` | 있음 (2026-11-08) | 값 패널 JSON 편집(정리, 검사, JSON 열은 잘못된 글자를 넣지 않음), [swing-grid-editing.md](../swing-grid-editing.md) | 낮음 | |
| O7 | XML 편집기 | `ui.editors.xml` `XMLEditor`, `XMLPanelEditor` | 있음 (2026-11-08) | 값 패널 XML 편집(정리, 검사, XXE 차단), [swing-grid-editing.md](../swing-grid-editing.md) | 낮음 | |

## 영향이 큰 빠진 기능·조용히 끊긴 기능 10개

1. S23 끊기·다시 연결·삭제 때 미커밋 트랜잭션을 묻지 않고 롤백 (조용히 끊김, 높음) — 2026-11-07 수정(#97)
2. C5 저장 안 한 비밀번호의 SQL Server·Oracle 연결이 묻지 않고 취소 (조용히 끊김, 높음, 실행 확인 못 함) — 2026-11-07 수정(#106)
3. R19 결과 저장 미리 보기·연결 종류 "데이터 변경 확인" 미동작 (조용히 끊김, 높음) — 2026-11-07 수정
4. S16 스마트 커밋 미동작 (조용히 끊김, 높음) — 2026-11-08 수정([connection-lifecycle.md](../connection-lifecycle.md) "스마트 커밋")
5. S24 유휴 트랜잭션 자동 종료 미동작 (조용히 끊김, 높음, 영역 A와 중복) — 2026-11-07 수정(#97)
6. S7 WHERE 없는 DELETE/UPDATE·DROP 확인 없음 (없음, 높음) — 2026-11-07 수정([swing-sql-exec-safety.md](../swing-sql-exec-safety.md))
7. S17·S18 Commit/Rollback·커밋 방식 전환 명령 없음 (없음, 높음, 결정 대기) — 2026-11-07 S17 수정([transactions.md](../transactions.md)), S18 남음
8. S22 쿼리 창별 별도 연결 없음 — 창들이 한 트랜잭션을 나눠 씀 (없음, 높음) — 2026-11-07 수정([transactions.md](../transactions.md) "쿼리 창마다 별도 연결")
9. S20·S21 트랜잭션 로그·모니터·미결 트랜잭션 보기 없음 (없음, 높음) — 2026-11-07 수정([transactions.md](../transactions.md))
10. S12 `@export` 무시 / S8 스크립트 실행 제한 무시 (조용히 끊김, 중간) — 2026-11-07 S12 수정([swing-data-transfer.md](../swing-data-transfer.md) "@export 명령"), S8 수정([swing-sql-exec-safety.md](../swing-sql-exec-safety.md))

## 확인하지 못한 것

- 앱을 실행하지 않았다. C5는 `DataSourceDescriptor.connect` → `askForPassword` 코드 경로로 판단했다. Swing Connect 대화상자로 연결할 때 다른 경로가 비밀번호를 넘기는지는 실행으로 확인해야 한다.
- RSyntaxTextArea 기본 기능(괄호로 이동 등)이 SWT의 `gotoMatchingBracket`과 같은지는 확인하지 않았다(목록에 넣지 않음).
- 영역 A(탐색기·플랫폼)와 겹치는 줄(S24, E11)은 중복으로 셀 수 있다.

## 변경 이력

- 2026-11-08 devel(#165) 코드와 모든 행을 다시 대조(`chromite/audit-recount`): C5 → 수정함, C11·C14·C16·C17·E11·R15 → 있음, C13 → 일부, S18·S19·S41·S45·R10·R12·R26·E2·E12·O1·O5 근거·비고 갱신, 조용히 끊김 요약과 10개 목록에 고친 것 표시. 요약 수를 행 상태로 다시 셈(있음 73, 일부 32, 없음 7, 조용히 끊김 1, 의도적 제외 4).
- 2026-11-08 5순위 값 편집기 나머지(`chromite/value-editors-rest`, [swing-value-editors.md](../swing-value-editors.md)): R14 근거·남은 것 갱신(일부 그대로, 영향 중간 → 낮음).
  요약 수는 메인 세션이 다시 셈.
