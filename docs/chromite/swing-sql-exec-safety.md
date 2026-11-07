# Swing 쿼리 실행 안전 확인

쿼리 창(F5)과 스크립트의 `@ai` 명령이 SQL을 실행하기 전에 묻거나 막는 세 가지를 정리한다.
SWT → Swing 전수 조사([swt-parity-audit.md](swt-parity-audit.md))의 1순위 항목 B-S7, B-S8, C-58을 고친 기록이다.

## 목적

- **위험한 문 확인(B-S7).** SWT SQL 편집기는 WHERE 없는 UPDATE/DELETE와 DROP을 실행하기 전에 물었다(`SQLEditor.java:3264-3390`, 기준 `263b8ddb45`).
  Swing 쿼리 창은 묻지 않고 실행했다. 모델의 판단 도우미(`SQLQuery.isDeleteUpdateDangerous`, `isDropDangerous`)는 AI 도구 분류기(`AgentSqlClassifier`)만 썼다.
- **연결의 "스크립트 실행 제한"(B-S8).** 연결이나 연결 유형의 보안 제한 `PERMISSION_EXECUTE_SCRIPTS`(`edit.execute`)는 SWT 편집기만 검사했다
  (`SQLEditor.java:3106`). Swing에는 검사가 없어 제한해 둔 연결에서도 실행됐다(조용히 끊김).
- **`@ai` 명령 실행 확인(C-58).** AI가 만든 SQL을 실행하기 전 `AIUtils.confirmExecute`(`AIUtils.java:332-346`)는 `UIServiceSQL`이 있으면
  SQL을 보여 주는 확인을, 없으면 `confirmAction(제목, 문구)`를 쓴다. Swing에는 이 서비스가 없어 SQL 없이 "계속할까요"만 물었다.

## 구조

| 파일 | 역할 |
|---|---|
| `ui.swing/SqlExecutionSafety.java` (새 파일) | 실행 제한 검사, 위험한 문 찾기와 확인, 확인 설정 읽기·쓰기 |
| `ui.swing/SqlConfirmDialog.java` (새 파일) | 질문 문구 + 읽기 전용 SQL(편집기와 같은 구문 강조) + 실행/취소. 기본 단추와 Esc는 취소 |
| `ui.swing/SwingSqlService.java` (새 파일) | `UIServiceSQL`의 Swing 구현. `confirmQueryExecution`만 구현 |
| `ui.swing/plugin.xml` | `com.seizia.chromite.service` 확장에 `UIServiceSQL` → `SwingSqlService` 등록 |
| `ui.swing/SqlEditorPanel.java` | `execute()`에서 실행 제한 검사, 준비 Job(`prepareAndSchedule`)에서 문장을 나눈 뒤 위험한 문 확인 |
| `ui.swing/OptionsDialog.java` | 일반 ▸ 쿼리 편집기에 확인란 두 개 |
| `EditorMessages`, `CoreMessages` (+ `_ko`) | 화면 문구(영어, 한국어) |

### 실행 흐름

1. F5(선택 또는 전체) → `SqlEditorPanel.execute()`(EDT): 연결을 고른 뒤 `SqlExecutionSafety.checkExecutePermission`.
   연결(또는 연결 유형)이 `edit.execute`를 막으면 오류 대화상자("쿼리를 실행할 수 없음")를 띄우고 끝낸다. 연결하지 않는다.
2. 준비 Job: 연결 → 실행 문맥 → `SQLScriptBatches.extractQueries`로 문장(또는 `GO` 배치)을 나눔 → Query Library 바인딩
   → `SqlExecutionSafety.confirmDangerousStatements`. 위험한 문이 있으면 EDT에서 `SqlConfirmDialog`를 띄우고 기다린다.
   취소하면 엔진을 예약하지 않고 실행을 취소 상태로 끝낸다(상태 줄 "쿼리가 취소되었습니다.", 메시지 탭 "사용자가 쿼리를 취소했습니다.").
3. 엔진(`SQLQueryExecuteJob`) 안에서 `@ai` 명령 → `SQLCommandAI` → `AIUtils.confirmExecutionIfNeeded` → `SwingSqlService.confirmQueryExecution`
   → `SqlConfirmDialog`(AI가 만든 SQL을 보여 줌). 취소하면 명령이 실패 결과를 돌려주고 생성된 SQL은 실행되지 않는다.

## 설계 결정과 이유

- **실행 제한은 문장 수와 관계없이 쿼리 창의 실행을 모두 막는다.** 작업 지시서는 "스크립트만 막고 단일 문장은 허용"이었지만 SWT 코드는 그렇지 않았다.
  `SQLEditor.processQueries`는 단일 문장 실행(Ctrl+Enter)도 지나가는 경로이고, 제한이 있으면 문장 수와 관계없이 "Query execution was restricted by
  connection configuration"으로 거절했다(`SQLEditor.java:3106-3112`). 권한 설명도 "사용자 스크립트(SQL) 실행을 제한합니다"다(`ModelResources_ko.properties:107`).
  Swing 쿼리 창에는 문장 실행과 스크립트 실행의 구분이 없고(F5 하나), 문장 수로 나누면 위험한 문을 한 문장씩 실행해 제한을 우회할 수 있다.
  그래서 SWT 동작(모두 거절, 안전 쪽)을 따랐다. 단일 문장을 허용하려면 `checkExecutePermission` 호출을 문장 수를 아는 준비 Job으로 옮기면 된다.
- **위험한 문은 실행 한 번에 한 번만 묻고 목록을 보여 준다.** SWT는 문장마다 물었다(여러 개면 "모두 예"). 스크립트 전체를 보고 한 번에 결정하는 편이
  안전하고 덜 번거롭다. 목록에는 줄 번호(편집기 기준, 문장 앞 주석이 있으면 그 주석의 줄), 종류, 문장 원문을 SQL 주석과 함께 보여 준다.
- **판단은 모델의 파서 도우미만 쓴다.** 새 파서를 만들지 않았다. `isDropDangerous`는 JSQLParser의 `Drop`과 객체 작업(DROP)을,
  `isDeleteUpdateDangerous`는 WHERE 없는 `Delete`/`Update`를 본다. TRUNCATE는 SWT와 같이 묻지 않는다.
- **`GO` 배치는 다시 문장으로 나눠 검사한다(근본 원인).** SQL Server처럼 배치 구분자가 있는 방언은 실행 요소가 배치 하나로 합쳐진 `SQLQuery`라
  (`SQLScriptBatches.addBatch`) 그대로 파서에 넘기면 여러 문장이어서 위험한 문을 찾지 못한다. 배치 구분자가 있는 방언이면 각 배치를
  `SQLScriptParser.extractScriptQueries`(편집기와 같은 파서 문맥 `SQLScriptBatches.createParserContext`)로 다시 나눈다. 반복 배치(`GO 2`)는 한 번만 본다.
- **설정 키와 값은 SWT와 같다.** `com.seizia.chromite.core.confirm.dangerous_sql`, `…drop_sql`(`ConfirmationConstants`, 플랫폼 설정 저장소).
  `always`·`never`면 묻지 않고, 그 밖의 값(없음, `prompt`)이면 묻는다(SWT `ConfirmationDialog.open`, `PrefPageConfirmations`). 기본값은 묻기.
  Options ▸ 일반 ▸ 쿼리 편집기의 "WHERE 절이 없는 UPDATE와 DELETE 실행 전에 확인", "DROP 문 실행 전에 확인"으로 켜고 끈다(끄면 `always`, 켜면 `prompt`).
  확인 대화상자에는 "다시 묻지 않음"을 두지 않았다. 끄는 곳을 Options 한 곳으로 두어 실수로 끄지 않게 했다.
- **확인 대화상자는 새로 만들었다(`SqlConfirmDialog`).** `ScriptPreviewDialog`는 머리말이 고정되어 있고 "쿼리 창으로 열기" 단추가 있는 미리 보기라
  질문 문구와 경고 아이콘을 넣을 수 없다. 위험한 문 확인과 `@ai` 확인이 같은 대화상자를 쓴다. 기본 단추는 취소다(SWT 기본 응답 No).
  대화상자를 기다리다 끊기거나 실패하면 실행하지 않는다.
- **`UIServiceSQL`은 `confirmQueryExecution`만 구현한다.** 남은 코드에서 이 서비스를 찾는 곳은 `AIUtils.confirmExecute` 하나다(`grep UIServiceSQL`).
  나머지 10개 메서드(`openSQLViewer`, `openSQLEditor`, `openGeneratedScriptViewer`, `openSQLConsole`, `createSQLPanel`, `setSQLPanelText`,
  `getSQLPanelText`, `openNewScript`, `openRecentScript`, `useIsolatedConnections`)는 SWT 편집기 부품을 돌려주는 것이라 Swing에 대응하는 것이 없고
  부르는 곳도 없다. 빈 값을 돌려주면 호출자가 성공으로 오해할 수 있어 `UnsupportedOperationException`을 던진다. 새 호출자를 만들면 먼저 구현해야 한다.

## upstream(SWT) 대비 변경점

| 항목 | SWT | Swing |
|---|---|---|
| 위험한 문 확인 | 문장마다 대화상자(DROP 먼저, 그다음 UPDATE/DELETE), "모두 예", "다시 묻지 않음" | 실행마다 한 번, 목록 표시, 끄기는 Options |
| DROP 판단 | `isDropDangerous` + `SQLObjectOperationRecognizer` | `isDropDangerous`만 |
| `GO` 배치 | 배치로 묶지 않음 | 배치를 다시 문장으로 나눠 검사 |
| 스크립트 실행 제한 | `processQueries`에서 모두 거절 | 같음(`execute()`에서 연결 전에 거절) |
| 읽기 전용 연결의 변경 문 거절(`SQLEditor.java:3115-3124`) | 있음 | 이 작업 범위 밖(남은 것 참고) |
| `@ai` 확인 | 메시지 상자 + "쿼리 보기" 단추 | 메시지와 SQL을 한 대화상자에 |

## 검증 (2026-11-07)

복사한 작업 공간(`D:\Tools\_scratch\chromite-sql-exec-safety\ws`)과 이 브랜치의 빌드로 실행하고, 앱 안에서 Swing API로 화면을 조작하는 시험용 에이전트로
확인했다. 증거(로그, 창 캡처): `D:\Tools\_scratch\chromite-sql-exec-safety\run\` (`*.txt.log`, `shots\*.png`).

| 시험 | 결과 |
|---|---|
| PostgreSQL: `SELECT 1; -- cleanup DELETE FROM p1b_safety;` → 확인 창(줄 2, DELETE) → 취소 | 실행 안 됨, 상태 "쿼리가 취소되었습니다.", 행 수 3 그대로 (`pg-delete-confirm.png`, `s5`, `s6`) |
| PostgreSQL: `DROP TABLE p1b_safety` → 확인 창 → 실행 | 테이블 삭제됨(정리) (`pg-drop-confirm.png`, `s11`) |
| SQL Server: `;`로 나눈 DELETE·UPDATE(배치 1) + `GO` + DROP(배치 2) → 확인 창에 3개(줄 2, 3, 5) → 취소 | 행 수 3 그대로 (`mssql-script-confirm-delimited.png`, `s13`) |
| SQL Server: `DELETE FROM tempdb.dbo.p1b_safety`(구분자 없음) → 확인 → 취소 | 행 수 3 그대로 (`mssql-delete-confirm.png`, `s13`) |
| MariaDB(연결에 `edit.execute` 제한): `SELECT 1` F5 | "쿼리를 실행할 수 없음" 오류, 실행·연결 안 됨 (`mariadb-restricted.png`, `s14`) |
| `@ai delete every row of p1b_safety`(로컬 가짜 Ollama가 `DELETE FROM p1b_safety` 응답) | 확인 창에 생성된 SQL 표시, 취소 뒤 행 수 3 그대로 (`ai-confirm-sql.png`, `s9`, `s10`) |
| Options에서 DROP 확인 끄기 → `DROP TABLE IF EXISTS …` 바로 실행, 다시 켜기 → 확인 창 | 설정 저장 `…confirm.drop_sql=prompt` (`options-general.png`, `s16`) |

AI는 실제 키를 쓰지 않았다. 작업 공간 복사본의 `.metadata/.config/ai-configuration.json`에 Ollama 프로필(127.0.0.1의 가짜 서버)을 두었다.

## 남은 것

- **구분자 없는 T-SQL 여러 문장.** `SELECT 1` 다음 줄에 `DELETE FROM t`처럼 `;` 없이 이어 쓴 문장은 파서가 한 문장으로 보아 위험한 문을 찾지 못한다
  (SWT도 같은 파서를 썼다). 문장 하나만 있거나 `;`로 나눈 경우는 찾는다. 새 파서를 만들지 않는다는 결정에 따라 남겼다.
- **`@ai` 확인을 취소하면** 엔진이 명령 실패(`SQLControlResult.failure()`, `SQLQueryExecuteJob.java:467`)를 오류 없이 멈춤으로 처리해 상태 줄이
  "쿼리를 실행했습니다."로 끝나고 취소 메시지가 없다. 데이터는 바뀌지 않는다.
- **`@ai`를 쓸 AI 설정 화면이 없다.** 모델 AI 설정(`ai-configuration.json`)을 만드는 Swing 화면이 없어, 파일을 직접 만들지 않으면 `@ai`는
  "AI engine is not configured"로 실패한다(C-58 메모의 `AIPreferencePageMain` 없음). AI 확인 규칙(`AI_CONFIRM_*`) 설정 화면도 없다.
- **읽기 전용 연결에서 변경 문을 미리 거절**(SWT `SQLEditor.java:3115-3124`)은 Swing에 없다. 드라이버의 읽기 전용 연결 설정이 서버에서 막는다.
- 실행 제한은 쿼리 창에만 적용했다. Query Library 예약 실행, 테이블 디자이너 저장, AI 에이전트 도구는 SWT에서도 이 검사가 없었다.
- 제한을 편집하는 화면(연결 보안 제한, C13)은 없다. `data-sources.json`의 `security.permission-restrictions`나 연결 유형 설정으로만 정한다.

## 변경 이력

- 2026-11-07 최초 작성: 위험한 문 확인(B-S7), 스크립트 실행 제한(B-S8), `@ai` 실행 확인에 SQL 표시(C-58)
