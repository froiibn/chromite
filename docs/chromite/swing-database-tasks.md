# 저장된 작업(Database Tasks)과 네이티브 클라이언트 스크립트 실행

## 목적

- **Database Tasks 보기**: 백업·복원·스크립트 실행 설정을 작업으로 저장해 두고 다시 돌린다. 저장한 작업의 목록, 실행, 편집, 삭제, 마지막 결과와 로그를
  한 도구 창에서 본다. SWT의 Database Tasks 보기(`DatabaseTasksView`)에서 매일 쓰는 부분이다(swt-parity C-43).
- **네이티브 클라이언트로 스크립트 실행**: SQL 스크립트 파일을 DB의 명령줄 클라이언트(`psql`, `mysql`/`mariadb`, `sqlplus`)로 실행한다.
  큰 덤프 파일이나 클라이언트 전용 명령(`\copy`, `SOURCE`, `@file`)이 든 스크립트를 쿼리 창이 아니라 그 도구로 돌릴 때 쓴다
  (swt-parity C-23, C-47, P21, M15, O22).
- **연결 편집기의 로컬 클라이언트**: 도구의 클라이언트 폴더를 연결 설정에서 고른다. 전에는 백업·복원 대화상자에서만 정할 수 있었다(follow-up 27).
- **데이터 전송 작업**: 데이터 내보내기·가져오기·테이블 복사 마법사의 설정(원본, 대상, 매핑, 설정)을 작업으로 저장하고, Database Tasks에서
  실행·편집(마법사를 그 설정으로 다시 엶)·삭제한다. SWT에서 저장한 데이터 전송 작업도 같은 마법사로 열린다(swt-parity C-39, C-43, B-E12).
- **SQL 스크립트 작업**: 여러 SQL 스크립트 파일을 연결에서 순서대로 실행하는 작업, 첫 오류에서 멈춤. 수동 커밋 세션의 트랜잭션은 묻지 않고
  커밋·롤백하지 않는다(swt-parity C-41).

지원 범위: 네이티브 도구는 DB마다 다르다. 이 기능이 다루는 것은 PostgreSQL(`psql`), MySQL·MariaDB(`mysql`/`mariadb`), Oracle(`sqlplus`)의 스크립트 실행과
PostgreSQL·MySQL/MariaDB의 백업·복원([swing-backup-restore.md](swing-backup-restore.md))이다. Chromite가 연결할 수 있는 DB(JDBC로 100개 넘음) 가운데
이 셋만 해당한다. 클라이언트 도구는 사용자가 이 컴퓨터에 설치한 것만 쓰고, Chromite가 내려받거나 설치하지 않는다.

## 사용법

### 네이티브 클라이언트로 스크립트 실행

- Object Explorer에서 PostgreSQL 데이터베이스, MySQL/MariaDB 데이터베이스, Oracle 스키마 또는 Oracle 연결을 오른쪽 클릭 → **Tasks > Run Script with Native Client…**.
  데이터베이스가 하나뿐인 연결 노드에서도 켜진다(Import/Export와 같은 규칙).
- 쿼리 창에서는 **Query > Run with Native Client…**: 저장된 쿼리 창 파일을 그 창의 현재 데이터베이스(PostgreSQL, MySQL/MariaDB)나 스키마(Oracle)에서 실행한다.
  도구는 파일을 읽으므로 저장하지 않은 창은 먼저 저장하라고 알린다(SWT도 편집기의 파일을 넘겼다).
- 대화상자(백업·복원과 같은 틀):
  - **일반**: 실행할 곳, 스크립트 파일. "스크립트는 Chromite와 따로 도구의 새 세션에서 실행되고 쿼리 창의 세션이나 트랜잭션을 쓰지 않는다"는 안내.
  - **옵션**: PostgreSQL "첫 오류에서 멈춤"(`-v ON_ERROR_STOP=1`), MySQL "외래 키 검사 끄기"·"자세한 출력(-v)", Oracle은 안내만(아래).
  - **로컬 클라이언트**: 클라이언트 폴더, 찾아보기, 명령줄 미리 보기. Oracle은 표준 입력으로 보내는 로그온 명령도 보인다(암호는 `******`).
  - **확인**을 누르면 실행 전에 **명령줄과 별도 세션 안내를 보이고 묻는다**(실행 / 취소, 취소가 기본). 실행하면 메시지 페이지에 도구 출력이 줄마다 나온다.
- 커밋은 도구가 원래 하는 대로다: psql과 mysql은 스크립트가 트랜잭션을 시작하지 않으면 문장마다 커밋하고, SQL*Plus는 끝날 때 커밋한다.
  Chromite는 이 세션에 커밋이나 롤백을 하지 않는다.

### 작업으로 저장, Database Tasks 보기

- 백업, 복원, 네이티브 클라이언트로 스크립트 실행 대화상자의 **스크립트 > 작업으로 저장**: 이름을 물은 뒤 지금 설정을 프로젝트의 작업으로 저장한다.
  고른 로컬 클라이언트는 연결의 클라이언트 홈으로도 저장한다(실행할 때와 같음). 그 대화상자에서 다시 저장하면(**작업 저장**) 같은 작업을 고친다.
- **보기 > 데이터베이스 작업**(Database Tasks): 오른쪽 열에서 Query Library와 같은 탭 묶음으로 열린다(처음에는 숨김). 2026-11-08부터 Query Library가 없으면
  Properties의 탭, 둘 다 없으면 Chromite Agent 위에 열린다([swing-frontend.md](swing-frontend.md) "기본 배치: Query Library와 Chromite Agent").
  - 열: 이름, 형식, 연결, 마지막 실행, 마지막 결과(성공 / 실패: 오류 / 취소됨 / 실행 중 / 아직 실행하지 않음), 걸린 시간.
  - 도구 막대·오른쪽 클릭: **실행**, **편집**(두 번 클릭), **마지막 로그 보기**, **삭제**(Delete 키), **새로 고침**(F5).
  - 실행은 백그라운드로 돌고, 끝나면 행을 다시 읽고 알림(성공 / 실패와 오류)을 띄운다.
  - 네이티브 도구 작업을 실행하면 처리기가 도구를 시작하기 전에 **실제 명령줄을 셸 명령 승인 대화상자로 묻는다**(이번만 실행 / 항상 허용 / 취소, 취소가 기본).
    저장된 설정은 사용자가 방금 입력한 것이 아니기 때문이다([shell-command-approval.md](shell-command-approval.md) "저장된 네이티브 도구 작업").
    취소하면 실행하지 않고 마지막 결과에 "Shell command was not approved…"가 남는다.
  - 네이티브 도구가 아닌 작업(데이터 전송, SQL 스크립트 등)은 이 창이 먼저 "실행할까요?"를 묻는다(취소가 기본). 데이터 전송이 대상 테이블을
    비우거나 다시 만들면, SQL 스크립트 작업이면 어떤 스크립트를 어느 연결에서 돌리는지를 같은 질문에 적는다(아래 두 절).
  - 편집 화면이 없는 형식(예: SWT의 다른 작업 형식)은 실행·삭제만 된다. 편집… 도구 설명이 그렇다고 알린다.
  - 삭제는 삭제 / 취소로 묻는다(취소가 기본).
  - **일정**(2026-11-12, 앱 변경 #104): 고른 작업을 정해진 시각에 저절로 실행한다(Chromite가 실행 중일 때만). 열 **일정**·**다음 실행**, 마지막 결과의
    "(예약)", 목록 아래 사용 중인 일정 줄. 손 실행이 매번 묻는 확인은 일정을 켤 때 한 번 받는다. 자세한 것은 [task-scheduling.md](task-scheduling.md).
  - 연결에 로컬 클라이언트가 정해져 있지 않은 네이티브 도구 작업은 실행하지 않고 어디서 정하는지 알린다(아래 "로컬 클라이언트를 정하지 않으면").

### 연결 편집기의 로컬 클라이언트

- 연결 편집 > 옵션 >> > **연결 속성**의 **로컬 클라이언트**: 이 드라이버의 로컬 클라이언트 폴더 목록(내려받는 클라이언트는 빠짐), 찾아보기로 폴더 더하기,
  "(정하지 않음)". 저장한 값이 이 컴퓨터에 없으면 "(이 컴퓨터에 없음)"으로 남겨 두고 바꾸지 않는다.
- 네이티브 클라이언트가 있는 드라이버(드라이버 공급자가 클라이언트를 찾거나 드라이버에 클라이언트 위치가 있는 것: PostgreSQL, MySQL/MariaDB, Oracle 등)에서만 보인다.
- 백업·복원, 스크립트 실행, 저장된 작업, Chromite Agent의 `dumpDatabase`·`restoreDump`가 모두 이 값(`clientHomeId`)을 쓴다.

### 데이터 전송 작업

- **작업으로 저장**: 데이터 내보내기, 데이터 가져오기, 테이블 복사(개체 탐색기 > 작업, 결과 표의 내보내기, `@export` 명령) 마법사의
  아래 왼쪽 단추. 마침처럼 모든 단계를 떠나 설정을 만들지만 실행하지 않고, 이름을 물은 뒤 프로젝트의 작업으로 저장한다.
  - 저장하는 것: 원본(테이블·뷰 또는 쿼리), 대상(파일 형식·형식 속성·출력 폴더·파일 이름 패턴 등, 또는 대상 연결·스키마), 테이블·열 매핑
    (대상 이름, 새로 만들기·기존·다시 만들기·건너뛰기, 새 열의 형식), 넣기 설정(비우기, 트랜잭션·커밋 간격, 여러 행 INSERT 등).
  - 저장할 때는 실행 확인(테이블 비우기 확인)을 묻지 않고, Export Data…의 기본값(폴더, 형식)도 바꾸지 않는다. 실행할 때 묻는다(아래).
  - 한 번 저장하거나 작업에서 연 마법사의 단추는 **작업 저장**이 되어 같은 작업을 고친다(이름도 바꿀 수 있음). 제목 끝에 작업 이름이 붙는다.
  - 결과 표의 필터나 정렬이 걸린 결과는 저장할 수 없다(단추가 꺼지고 도구 설명이 이유를 말함). 모델이 필터를 저장하지만 다시 읽지 않아
    작업이 다른 행을 내보내게 되기 때문이다.
- **Database Tasks**에서:
  - **실행**: "실행할까요?"(실행 / 취소, 취소가 기본). 대상 테이블을 비우는 작업이면 "넣기 전에 대상 테이블을 비웁니다", 다시 만드는 매핑이 있으면
    그 테이블 이름을 함께 보이고 경고 아이콘으로 묻는다. 실행은 모델의 처리기가 하고 마지막 결과·로그·알림은 다른 작업과 같다.
  - **편집**: 작업의 설정을 모델이 읽은 뒤(원본·대상 객체를 찾으므로 연결함) 그 작업의 마법사를 연다. 파일 원본이면 데이터 가져오기,
    아니면 데이터 내보내기(파일) 또는 테이블 복사(데이터베이스). 마침은 그 설정으로 실행하고, 작업 저장은 같은 작업을 고친다.
    - 내보내기: 작업의 테이블이 체크된 채 같은 스키마의 다른 테이블·뷰도 목록에 나온다. 형식·형식 속성·출력 칸이 작업의 값이다.
      쿼리 작업은 쿼리 페이지로 열리고 저장한 카탈로그·스키마에서 다시 실행한다.
    - 테이블 복사: 대상 단계에 작업의 연결·데이터베이스·스키마가 골라져 있고, 매핑 단계에 작업의 매핑(대상 이름·형식·다시 만들기)이 그대로 보인다.
      다른 대상이나 원본을 고르면 처음 열 때처럼 다시 매핑한다.
    - 가져오기: 파일·형식·형식 설정·넣기 설정이 작업의 값이다. 대상 테이블이 있으면 그 테이블로, 아직 없는 새 테이블이면 첫 단계에 그 스키마와
      이름이 골라진 채 열린다. 열 매핑은 파일을 읽을 때 다시 만들고 작업의 열 선택(건너뛰기, 대상 열, 새 열의 이름·형식)을 그 위에 적용한다.
    - 이 마법사들이 보여 주지 않는 작업(여러 파일 가져오기, 다른 원본 종류)은 "이 창이 보여 주지 않는 방식"이라고 알리고 열지 않는다. 실행·삭제는 된다.
  - **삭제**: 다른 작업과 같다(삭제 / 취소, 취소가 기본).
- **SWT에서 저장한 작업**: 저장 형식이 같아 목록에 나오고 위와 같이 실행·편집·삭제된다. SWT 작업의 "끝나면 테이블 열기"(Swing 마법사는 끔)는
  이제 그 테이블을 개체 탐색기에서 보여 준다(전에는 아무 일도 하지 않음, swt-parity B-E12). 화면에 칸이 없는 SWT 설정(날짜·숫자 형식 프로필,
  내보낸 뒤 명령)은 편집 후 다시 저장해도 남는다.
- 연결 열: 내보내기·복사는 원본 연결, 가져오기는 대상 연결(저장된 설정에서 읽음, 연결하지 않음).

### SQL 스크립트 작업

- **새 SQL 스크립트 작업**: Database Tasks 도구 막대와 오른쪽 클릭 메뉴. 대화상자에서 정하는 것:
  - 이름, 연결(목록, 위의 콤보에서 골라 추가), 스크립트 파일(파일 추가, 제거, 위로·아래로; 이 순서로 실행).
  - **첫 오류에서 멈춤**(기본 켬): 스크립트 안의 오류에서 멈추고 다음 스크립트도 실행하지 않는다. 끄면 오류 문장을 건너뛰고 계속한다.
  - **문장마다 커밋(자동 커밋)**(기본 끔): SWT의 같은 옵션. 켜면 수동 커밋 연결에서도 문장마다 커밋한다(사용자가 고른 것).
  - **쿼리 결과를 작업 로그에 쓰기**(기본 끔).
  - 저장(기본 단추, 실행하지 않음) / 취소. 편집…은 같은 대화상자로 작업을 연다(연결하지 않음).
- 실행(Database Tasks의 실행): 확인에 스크립트 목록과 연결을 보인다(취소가 기본). 각 스크립트를 각 연결의 **주 실행 문맥**에서 차례로 실행한다(SWT와 같음).
  - 세션의 커밋 방식을 바꾸지 않는다. 자동 커밋 세션은 문장마다 커밋된다(연결 설정 그대로).
  - 수동 커밋 세션: 그 세션에서 첫 스크립트를 실행하기 전에 세션에 이미 열린 트랜잭션이 있으면 **커밋 / 롤백 / 취소**를 묻는다(취소가 기본, 취소하면
    실행하지 않고 마지막 결과가 "취소됨"). 끝나면(오류로 멈춰도) 스크립트가 남긴 트랜잭션에 대해 **커밋 / 롤백 / 열어 두기**를 묻는다(열어 두기가 기본).
    열어 둔 트랜잭션은 도구 > 열린 트랜잭션, 유휴 트랜잭션 알림, 연결 끊기 질문이 보인다. 고른 답은 작업 로그에 남는다.
  - Chromite는 묻지 않고 커밋하거나 롤백하지 않는다. SWT는 끝에 커밋하고 오류면 롤백했다(아래 "설계 결정").

## 구조

| 위치 | 내용 |
|---|---|
| `model` `runtime/ui/UIServiceShellCommands` | `getTaskScope(DBTTask)`: 저장된 작업의 승인 범위(`task:<프로젝트 경로>\|<작업 ID>`) |
| `model` `messages/ModelMessages` | 승인 문맥 `model_shell_command_context_task`, `model_shell_command_event_saved_task`(영어·한국어) |
| `tasks.native` `AbstractNativeToolHandler` | `executeProcess`: 임시가 아닌 작업이면 시작 전에 실제 명령줄을 승인받음(`approveSavedTaskCommand`), 거절하면 `InterruptedException` |
| `ext.oracle` `tasks/OracleScriptExecuteHandler` | `sqlplus /nolog`로 시작하고 로그온 명령을 표준 입력으로 보냄(`getLogonScript`). 암호가 명령줄에 없음 |
| `model.admin` `nativetool/NativeBackupRestore` | 스크립트 실행(`isScriptSupported`, `getScriptToolName`, `createScriptSettings`, 작업 형식, 명령줄, `getStandardInputPreview`), 저장된 작업(`isEditableTaskType`, `isNativeToolTask`, `loadTaskSettings`, `getTaskTarget`, `getTaskConnection`, `saveTask`), 로컬 클라이언트(`hasNativeClients`, `addLocalClient`, `findConnectionClient`) |
| `ui.swing` `NativeBackupRestoreDialog` | 스크립트 방식(세 번째 방식), 실행 전 확인(`confirmRun`), 작업으로 저장·작업 저장(`saveAsTask`), 저장된 작업 열기(`openTask`, `applySettings`) |
| `ui.swing` `BackupRestoreCommands` | Run Script with Native Client…(Object Explorer), Run with Native Client…(쿼리 창, `findEditorTarget`) |
| `ui.swing` `ObjectExplorerPanel` | Tasks 메뉴 항목, 대상(`getScriptTarget`) |
| `ui.swing` `MainFrame` | Database Tasks 도구 창(`databaseTasks`), Query 메뉴 항목 |
| `ui.swing` `DatabaseTasksPanel` (새) | Database Tasks 도구 창. 2026-11-08 데이터 전송·SQL 스크립트 작업 편집, 새 SQL 스크립트 작업, 실행 확인의 작업 내용 |
| `ui.swing` `ConnectionSettingsPanel` | 연결 속성의 로컬 클라이언트 칸 |
| `ui.swing` `internal/DatabaseTasksMessages` (새), `BackupRestoreMessages`, `ConnectionMessages` | 화면 문구(영어·한국어) |
| `model.ai.tools` `NativeBackupTools` | 로컬 클라이언트 안내 문구에 연결 편집기의 칸을 더함 |
| `data.transfer` `tools/transfer/task/DTSavedTasks` (새) | 데이터 전송 작업 저장(`saveTransferTask`, SWT 형식)·읽기(`loadTransferTask`), SQL 스크립트 작업 저장·읽기, 목록용 연결(`findTaskConnection`)·대상 변경(`getTargetChanges`) |
| `data.transfer` `tools/sql/task/SQLScriptExecuteHandler` | 커밋 방식을 바꾸지 않음, 묻지 않고 커밋·롤백하지 않음, 실행 전·후 열린 트랜잭션 질문 |
| `data.transfer` `internal/DTMessages` | 스크립트 작업의 트랜잭션 질문 문구(`sql_script_task_txn_*`, 영어·한국어) |
| `ui.swing` `DataTransferWizard` | 작업으로 저장 단추(`saveAsTask`), 편집 중인 작업(`setSavedTask`), `prepareTransfer(run)`, 작업 설정을 진행 페이지와 돌리는 `ReportingSettings`·`loadTaskSettings` |
| `ui.swing` `DataExportWizard` | 내보내기·복사 작업 편집(`openForTask`, 작업의 값을 `ExportCommand`로), 필터 걸린 쿼리는 저장하지 않음 |
| `ui.swing` `DatabaseCopyPages` | 복사 작업의 설정·매핑을 그대로 씀(`applyTask`), 대상 미리 고르기, 건너뛴 테이블 파이프 되살리기 |
| `ui.swing` `DataImportWizard`, `DataImportTargetPage` | 가져오기 작업 편집(`openForTask`, 열 선택 다시 적용), 새 테이블 이름·스키마 미리 고르기 |
| `ui.swing` `DataTransferObjects` | 저장된 형식 속성을 속성 형식으로 바꾸기(`convertProcessorProperties`) |
| `ui.swing` `SqlScriptTaskDialog` (새) | SQL 스크립트 작업 만들기·고치기 |
| `ui.swing` `SwingPlatformUI` | `openEntityEditor`: 개체 탐색기에서 그 객체를 보임(B-E12) |

작업은 모델의 작업 관리자(`DBTTaskManager`, 프로젝트의 `.dbeaver/tasks.json`)에 저장되고, 실행 기록과 로그도 모델이 남긴다(`TaskRunJob`, 작업 통계 폴더).
작업 형식은 모델에 이미 있는 것을 그대로 쓴다: `pgDatabaseBackup`, `pgDatabaseRestore`, `pgScriptExecute`, `mysqlDatabaseBackup`, `mysqlDatabaseRestore`,
`mysqlScriptExecute`, `oracleScriptExecute`, 데이터 전송 `dataExport`(내보내기·테이블 복사)·`dataImport`(가져오기), SQL 스크립트 `scriptExecute`.
그래서 SWT에서 저장한 같은 형식의 작업도 편집·실행된다.

## 설계 결정과 이유

- **백업·복원 대화상자에 세 번째 방식으로 넣었다.** 스크립트 실행은 복원처럼 입력 파일을 읽고, 로컬 클라이언트 고르기·명령줄 미리 보기·메시지 페이지·
  pg_restore처럼 모델 처리기를 부르는 경로가 같다. 따로 대화상자를 만들면 같은 코드가 두 벌이 된다. 모델 쪽도 `NativeBackupRestore`에 더했다
  (Chromite Agent 도구와 같은 실행 경로).
- **별도 세션이라는 것을 화면에서 말한다.** 도구는 Chromite의 연결과 상관없이 새로 접속한다. 일반 페이지의 안내와 실행 전 확인에 적어,
  쿼리 창의 열린 트랜잭션과 섞일 것이라고 오해하지 않게 한다. Chromite는 그 세션에 커밋·롤백하지 않는다(트랜잭션 규칙).
- **실행 전 확인에 명령줄을 보인다.** 명령줄은 로컬 클라이언트 페이지에 있는데 사용자가 그 페이지를 열지 않을 수 있다. 확인 대화상자(취소가 기본)가
  실제로 실행할 명령과 표준 입력으로 보내는 것을 보인다. 백업·복원은 전과 같이 묻지 않는다(바뀐 것 없음).
- **SQL*Plus 암호를 명령줄에서 뺐다(근본 원인 수정).** 모델의 Oracle 처리기는 `user/"암호"@url`을 인수로 넘겼다. 이 컴퓨터의 다른 사용자가 프로세스 목록에서
  볼 수 있고, 실행 로그의 가리기(`NativeToolUtils.isSecureString`, 암호로 *끝나는* 인수만 가림)도 이 인수를 가리지 못해 작업 로그에 암호가 남았다.
  SQL*Plus에는 PGPASSWORD·MYSQL_PWD 같은 환경 변수가 없으므로 `sqlplus /nolog`로 시작하고, 스크립트 앞에 표준 입력으로 다음을 보낸다.
  ```
  WHENEVER SQLERROR EXIT FAILURE
  CONNECT user/"암호"@연결 식별자 [AS SYSDBA]
  WHENEVER SQLERROR CONTINUE NONE
  ALTER SESSION SET CURRENT_SCHEMA = "스키마";   -- 스키마에서 실행할 때만
  ```
  로그온이 실패하면 SQL*Plus가 실패 코드로 끝나 스크립트를 세션 없이 실행하지 않게 했다(아래 "남긴 것": 실행해 보지는 못했다).
  처리기 자체를 고쳤으므로 대화상자, 저장된 작업, SWT에서 저장한 작업 모두 같다. 스키마를 고르면 그 스키마를 현재 스키마로 정한다(SWT는 스키마를 골라도 무시했다).
- **저장된 네이티브 도구 작업은 실행할 때마다 명령줄 승인을 받는다(#99와 같은 승인).** 작업 설정은 `tasks.json`에 있어 가져온 프로젝트나 다른 사람이 고친
  파일일 수 있다. 승인은 처리기(`AbstractNativeToolHandler`)에서 받으므로 이 창뿐 아니라 작업을 실행하는 모든 경로에 적용된다. 범위는 작업마다(프로젝트 경로 + 작업 ID)이고,
  항상 허용은 [범위, 작업 폴더, 명령] 그대로 기억한다. `${timestamp}`가 든 백업 파일 이름처럼 실행마다 명령이 바뀌면 매번 다시 묻는다(정확한 명령을 승인하는 것이 원칙).
  대화상자와 Chromite Agent 도구가 만든 임시 작업은 묻지 않는다: 사용자가 그 자리에서 설정을 보고(에이전트는 자기 확인 대화상자에서) 실행한다.
- **로컬 클라이언트를 정하지 않으면 저장된 작업을 실행하지 않는다.** 처리기는 연결의 클라이언트 홈이 없으면 드라이버의 첫 클라이언트를 고르는데, 그것이 처음 쓸 때
  내려받는 클라이언트일 수 있다. 클라이언트를 내려받지 않는다는 규칙 때문에 실행 전에 막고 어디서 정하는지 알린다.
- **편집은 같은 대화상자로 한다.** 저장된 설정을 모델 설정 객체로 읽어(`loadTaskSettings`, 처리기가 읽는 것과 같은 `loadSettings`) 칸을 채운다. 그 뒤의
  저장은 같은 작업을 고친다(이름도 바꿀 수 있음). 이 화면이 다루지 않는 SWT 설정(백업할 스키마·테이블 고르기, 도구 계정, 추가 인수)은 그대로 저장되지 않으니,
  SWT에서 그런 설정으로 저장한 작업을 여기서 다시 저장하면 그 설정은 빠진다(아래 "남긴 것").
- **삭제는 작업 설정만 지운다.** 모델의 `deleteTaskConfiguration`과 같다(실행 기록 폴더는 SWT처럼 남는다).
- **연결 편집기의 칸은 Connection Properties에 둔다.** SWT는 PostgreSQL·MySQL 연결 페이지의 "로컬 클라이언트" 고르기였다. Swing 연결 대화상자는 DB별 페이지
  대신 공통 탭을 쓰므로, 드라이버에 네이티브 클라이언트가 있을 때만 공통 탭에 한 줄을 더했다. 찾아보기로 더한 폴더는 백업·복원 대화상자와 같은 모델
  코드(`addLocalClient`)로 드라이버의 사용자 위치에 저장한다.

### 데이터 전송 작업

- **저장 형식은 SWT와 같다.** `producers`·`consumers`(노드 직렬화기의 위치), `configuration`(노드 설정 절, 노드·처리기 ID, 고른 처리기의 속성).
  모델의 처리기(`DTTaskHandlerTransfer`)가 그대로 돌리고, SWT 작업과 Swing 작업이 서로 열린다. 다른 점: SWT는 대화상자 설정에서 읽은 노드만
  저장하지만(`DataTransferSettings.saveNodeSettings`는 읽지 않은 노드의 원래 절을 복사), Swing 마법사는 노드 설정을 대화상자 설정에서 읽지 않고
  화면 값을 직접 넣으므로 고른 producer·consumer의 설정 객체를 그대로 저장한다(`DTSavedTasks.saveConfiguration`). 모델 쪽에 둔 이유: 저장·읽기에
  화면이 필요 없고, Chromite Agent 같은 다른 프런트엔드도 같은 코드를 쓸 수 있게.
- **저장은 마침과 같은 설정을 만든다.** 모든 단계를 떠나 `prepareTransfer(false)`로 설정을 만들므로 저장한 작업은 마침이 실행할 것과 같다.
  실행 확인(테이블 비우기)과 Export Data… 기본값 기억은 `run`이 true일 때만 한다. 저장은 아무것도 바꾸지 않고, 실행 확인은 작업을 실행할 때
  Database Tasks가 한다(저장된 JSON에서 비우기·다시 만들기를 읽어 질문에 적음).
- **편집은 같은 마법사를 그 설정으로 연다.** 새 편집 화면을 만들지 않았다. 마법사마다 채우는 방법이 다르다.
  - 파일 내보내기: 작업의 형식·형식 속성·출력·원본 옵션을 `@export` 명령과 같은 `ExportCommand`로 만들어 넘긴다. 이미 있는 길이라 칸이 있는 옵션은
    칸에, 없는 옵션(파일 나누기, 가져오기 크기 등)은 설정에 들어가고, Export Data…의 기본값은 바뀌지 않는다. 그 밖의 SWT 설정(형식 프로필, 끝난 뒤 명령)은
    읽은 출력 설정에서 그대로 옮긴다.
  - 테이블 복사: 모델이 읽은 설정과 매핑을 그대로 복사 단계의 설정으로 쓴다(`DatabaseCopyPages.applyTask`). 다시 매핑하면 작업에서 고친 대상 이름·
    형식·다시 만들기가 사라지기 때문이다. 진행 페이지는 마법사가 만든 설정의 파이프 가져가기(`acquireDataPipe`)로 진행을 알았는데, 읽은 설정은 마법사보다
    먼저 생기므로 같은 하위 클래스(`ReportingSettings`)로 읽고 실행할 때 마법사를 이어 준다(`DTSavedTasks.SettingsFactory`).
  - 가져오기: 마법사는 파일 단계를 떠날 때마다 파일의 열을 읽어 매핑을 새로 만든다. 읽은 매핑을 그대로 쓰면 그 흐름과 어긋나므로, 작업의 열 선택을
    원본 열 이름으로 기억해 두었다가 새 매핑 위에 적용한다.
- **복사 단계의 건너뛴 테이블.** 전에는 마침에서 건너뛴 테이블의 파이프를 설정에서 지웠다(모델이 그 원본도 읽기 때문). 작업으로 저장한 뒤에도 마법사를
  계속 쓰므로, 지우기 전 파이프를 기억해 단계로 돌아오거나 다시 저장·실행할 때 되살린다.
- **B-E12.** 모델은 새 테이블로 옮긴 뒤 "끝나면 테이블 열기"가 켜져 있으면 객체 편집기를 연다(`openEntityEditor`). Swing에는 객체 편집기가 없어
  디버그 로그만 남겼고, SWT에서 저장한 작업은 이 옵션이 켜져 있어 조용히 아무 일도 하지 않았다. Swing에서 객체를 여는 가장 가까운 동작인
  개체 탐색기에서 보이기로 바꿨다(오류 탐색기의 "개체 탐색기에서 보기"와 같은 `revealNode`).

### SQL 스크립트 작업

- **주 실행 문맥에서 돈다(SWT와 같음).** 작업마다 격리 세션을 열면 끝에 "열어 두기"를 고른 트랜잭션이 아무 창에도 속하지 않은 세션에 남아 세션이
  쌓인다. 주 문맥이면 열린 트랜잭션 목록·유휴 알림·끊기 질문이 이미 그 세션을 본다. 대신 다른 작업의 트랜잭션과 섞이지 않게 실행 전에 묻는다.
- **커밋 방식을 바꾸지 않는다.** SWT 처리기는 자동 커밋을 끄면 `AT_END`(끝에 커밋, 오류면 롤백)로 돌렸고, 자동 커밋 세션에서는 세션을 수동으로 바꿨다가
  되돌리며 JDBC가 커밋했다. 트랜잭션 규칙("세션의 트랜잭션 모드를 바꾸는 기능은 먼저 트랜잭션을 찾아 묻는다, 묻지 않고 커밋·롤백하지 않는다")에 맞게
  세션이 자동 커밋이면 `AUTOCOMMIT`, 수동이면 `NO_COMMIT`로 돌리고, 오류에서 멈춤은 `STOP_COMMIT`(`NO_COMMIT`과 함께면 멈추기만 함)이다.
  처리기 자체를 고쳤으므로 SWT에서 저장한 작업도 같다.
- **"문장마다 커밋"은 남겼다.** SWT 작업의 설정이고 사용자가 고른 커밋이다. 실행 전 질문 뒤라 끝낼 트랜잭션이 없을 때만 세션을 자동 커밋으로 바꿨다 되돌린다.
- **연결은 목록이다.** 브리프는 한 연결이지만 SWT 작업은 여러 연결을 저장할 수 있고, 한 개만 받으면 그런 작업을 고쳐 저장할 때 연결이 빠진다.

## upstream 대비 변경점

| 항목 | DBeaver(SWT) | Chromite |
|---|---|---|
| 작업 목록 | Database Tasks 보기(폴더, 범주·형식별 묶기, 만들기 마법사, 작업 변수, 최근 작업 메뉴, 완료 소리) | 한 표(이름, 형식, 연결, 마지막 실행·결과·시간), 실행·편집·로그·삭제. 폴더·묶기·변수·최근 작업 메뉴 없음 |
| 저장된 네이티브 작업 실행 | 묻지 않고 실행 | 실행할 때 실제 명령줄 승인(이번만 / 항상 허용 / 취소) |
| 로컬 클라이언트 없을 때 | 드라이버의 첫 클라이언트(내려받을 수 있음) | 실행하지 않고 안내 |
| SQL*Plus 로그온 | `user/"암호"@url` 인수 | `/nolog` + 표준 입력 `CONNECT`, 실패하면 종료, 스키마를 현재 스키마로 |
| 스크립트 실행 화면 | 마법사(작업 설정 페이지) + 편집기 "네이티브 클라이언트로 실행"(`sql.executors`) | 백업·복원 대화상자의 스크립트 방식, Query 메뉴 항목, 실행 전 확인 |
| 로컬 클라이언트 고르기 | PostgreSQL·MySQL 연결 페이지 | 연결 속성의 로컬 클라이언트 칸(네이티브 클라이언트가 있는 드라이버) + 대화상자의 로컬 클라이언트 페이지 |
| 데이터 전송 작업 저장·편집 | 작업 설정 마법사(작업 페이지, 작업 변수) | 각 마법사의 작업으로 저장…(이름만 물음), 편집은 같은 마법사. 저장 형식은 같음. 작업 변수·폴더 없음 |
| 작업 실행 확인 | 묻지 않음 | 실행 / 취소(취소가 기본), 대상 비우기·다시 만들기와 스크립트 목록을 적음 |
| SQL 스크립트 작업의 커밋 | 자동 커밋을 끄면 끝에 커밋, 오류면 롤백. 자동 커밋 세션은 수동으로 바꿨다 되돌림 | 세션의 커밋 방식을 바꾸지 않음. 수동 커밋 세션은 실행 전·후에 커밋 / 롤백을 물음 |
| SWT 작업의 "끝나면 테이블 열기" | 객체 편집기 | 개체 탐색기에서 그 테이블을 보임 |

## 확인 (2026-11-08)

같은 빌드의 Swing 제품을 개인 작업 공간(`CHROMITE_DATA`와 `-data`가 스크래치 폴더)으로 한 벌 실행하고, 그 프로세스에 붙인 스크래치 시험 도구가
Swing 부품을 직접 눌렀다(화면 좌표·OS 입력 없음). 한국어(OS 기본)와 `-nl en`. 공용 Docker 컨테이너에 만든 시험 DB `chr_p4_tasks`(PostgreSQL 17, MariaDB 11)만 썼고
끝나고 지웠다. 클라이언트는 D: 드라이브에 푼 PostgreSQL 17.11과 MariaDB 11.8 zip. 증거: `D:\Tools\_scratch\chromite-p4-tasks-native\` (`run\*.txt.log`, `shots\`).

| 순서 | 동작 | 결과 |
|---|---|---|
| 1 | 연결 편집 > 연결 속성 > 로컬 클라이언트에서 PostgreSQL 클라이언트 폴더를 고르고 저장 | 연결의 `clientHomeId`가 그 폴더. 다시 열면 그대로 선택됨(`ko-conn-local-client.png`, `en-conn-local-client.png`) |
| 2 | PostgreSQL DB > 네이티브 클라이언트로 스크립트 실행, 확인 | 일반 페이지에 별도 세션 안내. 실행 전 확인에 `psql.exe --host=… --username=postgres --echo-errors chr_p4_tasks < …\pg-ok.sql`, 기본 단추 취소 |
| 3 | 확인에서 취소 | psql을 실행하지 않음(`to_regclass('p4_native')`가 NULL) |
| 4 | 다시 확인 → 실행 | 메시지 페이지에 psql 출력(`CREATE TABLE`, `INSERT 0 2`, `rows_now 2`), 완료. 테이블 행 2개 |
| 5 | 오류가 있는 스크립트, 첫 오류에서 멈춤, 작업으로 저장 | 작업 `pgScriptExecute`, 속성에 `-v ON_ERROR_STOP=1`·입력 파일·DB ID, 암호 없음. Database Tasks에 "아직 실행하지 않음" |
| 6 | Database Tasks > 실행 | 셸 명령 승인 대화상자(프로젝트, 작업, 연결, "저장된 작업 실행", 작업 폴더, 명령), 기본 단추 취소 |
| 7 | 취소 | 실행 안 함, 마지막 결과 "실패: Shell command was not approved …" |
| 8 | 다시 실행 → 이번만 실행 | psql이 둘째 문장에서 멈춤(종료 코드 3), 마지막 결과 "실패: Process failed (exit code = 3) …", 행 3만 들어감(1·2·3). 마지막 로그 보기에 psql 오류 줄 |
| 9 | 편집… | 같은 대화상자가 저장된 파일·옵션으로 열림. 파일과 옵션을 바꾸고 이름을 고쳐 작업 저장 → 같은 작업 ID, 새 이름과 설정 |
| 10 | 삭제… → 취소 / 삭제 | 기본 단추 취소, 취소하면 남고 삭제하면 목록과 `tasks.json`에서 빠짐 |
| 11 | MariaDB 스크립트(`mysql.exe --no-beep -u root … chr_p4_tasks < …`) 실행, 작업으로 저장, 작업 실행 → 항상 허용 | `confirmed_shell_commands.json`에 `task:<작업 공간>\General\|<작업 ID>` 항목. 다시 실행하면 묻지 않음, 성공 |
| 12 | PostgreSQL 백업을 작업으로 저장 → 실행 → 이번만 실행 | `pg_dump.exe … --format=c --file …\pg-task-backup.dump`, 성공, 파일 생김 |
| 13 | 연결의 로컬 클라이언트를 지우고 MariaDB 작업 실행 | 실행하지 않고 "로컬 클라이언트가 정해져 있지 않습니다 …" 안내 |
| 14 | Oracle 스키마 SYSTEM, Oracle 연결(시험용 빈 `sqlplus.exe` 폴더, 실행하지 않음) | 명령줄 `sqlplus.exe /nolog < …\ora.sql`, 표준 입력 미리 보기 `WHENEVER SQLERROR EXIT FAILURE` / `CONNECT system/"******"@//127.0.0.1:51521/FREEPDB1` / `WHENEVER SQLERROR CONTINUE NONE` / (스키마일 때) `ALTER SESSION SET CURRENT_SCHEMA = SYSTEM;` |
| 15 | 쿼리 창: 저장하지 않은 PostgreSQL·MariaDB 창, 저장된 파일을 연 창에서 Query > Run with Native Client… | 저장하지 않은 창은 "먼저 저장하십시오", 대상은 PostgreSQL `chr_p4_tasks`·MariaDB 카탈로그 `chr_p4_tasks`. 파일을 연 창은 그 파일로 대화상자가 열림 |

확인 중 고친 것: 실행을 시작할 때 표 전체를 다시 그려 선택이 풀리던 것(행 갱신으로 바꿈), 마지막 결과가 바깥 오류 "Error executing process"만 보이던 것
(저장된 스택 추적의 가장 안쪽 원인을 보임), 좁은 열에서 결과가 안 보이던 열 순서(이름 다음에 결과), 실행 확인의 명령 칸 높이, 승인 대화상자에
스크립트 파일이 나오지 않던 것(스크립트 실행은 `< 파일`을 붙여 승인).

회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 백업·복원 대화상자(같은 클래스), Chromite Agent `dumpDatabase`·`restoreDump`(같은 모델 클래스,
임시 작업이라 승인 대상 아님), 예전에 저장된 네이티브 도구 작업(이제 실행할 때 승인), Oracle SQL*Plus 작업(로그온 방식이 바뀜, 실행 확인 못 함).

## 확인: 데이터 전송·SQL 스크립트 작업 (2026-11-08)

`chromite/transfer-tasks` 빌드의 Swing 제품을 개인 작업 공간(`CHROMITE_DATA`와 `-data`가 스크래치 폴더)으로 한 벌 실행하고, 그 프로세스에 붙인 스크래치
시험 도구가 Swing 부품을 직접 눌렀다(화면 좌표·OS 입력 없음). `-nl en`과 한국어(OS 기본). 공용 Docker 컨테이너에 만든 시험 DB `chr_transfer_tasks`
(PostgreSQL 17, SQL Server 2022, MariaDB 11)만 썼고 끝나고 지웠다. 증거: `D:\Tools\_scratch\chromite-transfer-tasks\` (`run\*.txt.log`, `shots\`).

| 순서 | 동작 | 결과 |
|---|---|---|
| 1 | PostgreSQL `public.src_a` 데이터 내보내기(CSV), 출력 폴더·패턴을 바꾸고 작업으로 저장… | 작업 `dataExport`: producers(엔티티 ID), consumers, configuration(`DatabaseTransferProducer`, `StreamTransferConsumer` 출력 설정, `processor=stream.csv`, 처리기 속성). 단추가 "작업 저장"으로 바뀜. 목록 연결 열 = 원본 연결 |
| 2 | Database Tasks > 실행 → 취소 / 다시 실행 → 실행 | 질문의 기본 단추 취소, 취소하면 파일 없음. 실행하면 성공, `src_a_task.csv` 생김 |
| 3 | 편집… | "데이터 내보내기 - 작업 이름" 마법사, src_a 체크, 형식 CSV, 폴더·패턴·문자 집합·실패 시 지우기가 작업 값. 패턴을 바꾸고 이름을 바꿔 작업 저장 → 같은 작업 ID에 새 이름·패턴 |
| 4 | SQL Server `dbo.src_a` 테이블 복사 → MariaDB `chr_transfer_tasks`(새 테이블), 작업으로 저장, 실행 | 매핑 `create`, 실행 성공, MariaDB에 3행(한글 값 포함) |
| 5 | 그 작업 편집… | 대상 단계에 MA 연결·데이터베이스가 골라져 있음, 매핑 `existing`(이제 테이블이 있음). 대상 비우기를 켜고 작업 저장 → `truncateBeforeLoad=true` |
| 6 | 다시 실행 | 질문에 "넣기 전에 대상 테이블을 비웁니다", 경고 아이콘, 기본 단추 취소. 실행하면 여전히 3행 |
| 7 | PostgreSQL `tgt.imp`에 CSV 가져오기, `amount` 열을 건너뛰고 작업으로 저장, 실행 | `dataImport`, 매핑 `amount=skip`. 실행 성공, 3행(`amount` 비어 있음) |
| 8 | 그 작업 편집… → 다음 | 파일·형식·넣기 설정이 작업 값, 열 매핑에 `amount → [skip]`이 다시 적용됨 |
| 9 | 데이터베이스 노드에서 가져오기, 새 테이블 `tgt.imp_new`, 저장만(실행 안 함) → 편집… | 첫 단계에 스키마 `tgt`, "새 테이블", 이름 `imp_new`가 골라져 열림. 열 매핑 `create` |
| 10 | SWT 형식으로 만든 복사 작업(PostgreSQL → MariaDB 새 테이블 `src_swt`, "끝나면 테이블 열기" 켜짐) | 목록에 나오고 편집…이 테이블 복사 마법사로 엶(대상·매핑 `create`). 실행 성공, 개체 탐색기에서 `src_swt`가 골라짐(B-E12) |
| 11 | 그 작업을 편집해 마침으로 실행(대상 비우기 켬) | 잘라내기 / 취소 확인(취소가 기본) 뒤 진행 페이지에 행마다 성공·3행(읽은 설정도 진행이 보임) |
| 12 | 새 SQL 스크립트 작업…: 빈 이름으로 저장 | "작업 이름을 입력하십시오" 경고 |
| 13 | 수동 커밋 PostgreSQL 연결에 스크립트 두 개(`CREATE TABLE`+INSERT, INSERT 둘), 저장, 실행 | 실행 질문에 스크립트 순서와 연결, 기본 취소. 끝에 커밋 / 롤백 / 열어 두기(기본 열어 두기) → 커밋 → 1·2·3행이 다른 세션에서 보임. 로그에 "Committed the transaction…" |
| 14 | 주 세션에 INSERT(100)를 해 두고 오류 스크립트 작업 실행 | 실행 전 커밋 / 롤백 / 취소(기본 취소) → 취소: 실행 안 함, 마지막 결과 "취소됨", 트랜잭션 그대로 |
| 15 | 다시 실행 → 실행 전 롤백 | 100 롤백, 첫 스크립트가 INSERT(4) 뒤 오류에서 멈춤, 둘째 스크립트(6) 실행 안 함. 끝 질문 → 롤백 → 4 없음. 결과 "실패: relation "no_such_table" does not exist" |
| 16 | SQL Server·MariaDB 두 연결(자동 커밋)에 스크립트 두 개 | 질문 없이 성공, 두 DB 모두 2행. 편집…이 연결 2개·스크립트·옵션을 보이고, 첫 오류에서 멈춤을 꺼 저장하면 `ignoreErrors=true` |
| 17 | 한국어: 목록 열, 실행 확인(비우기 줄), 편집 마법사 제목·"작업 저장", 저장 이름 질문, 스크립트 대화상자, 실행 전·후 질문 | 모두 한국어. 기본 단추는 영어와 같음(실행 확인 취소, 실행 전 취소, 끝 열어 두기) |

확인 중 고친 것: 트랜잭션 질문 문구가 첫 줄만 보이던 것(속성 파일의 줄바꿈), 스크립트 대화상자의 목록이 한 줄 높이로 줄던 것(목록 행 높이와 두 번 `pack`).

회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 데이터 내보내기·가져오기·테이블 복사 마법사(마침 경로가 `leavePages`·`prepareTransfer(true)`로 바뀜,
복사 단계의 파이프 되살리기), `@export` 명령(같은 `ExportCommand` 길), SWT에서 저장한 SQL 스크립트 작업(이제 끝에 커밋하지 않고 물음), 모델의
`openEntityEditor`를 부르는 곳(데이터 전송 뒤 테이블 열기뿐).

## 남긴 것

- 데이터 전송 작업: 여러 파일을 한 번에 가져오는 SWT 작업, 데이터베이스가 아닌 다른 원본 종류는 편집 화면이 없다(실행·삭제만). 결과 표의 필터·정렬이 걸린 쿼리는
  작업으로 저장하지 않는다(모델 `DatabaseTransferProducer` 직렬화기가 필터를 저장하지만 다시 읽지 않음, 모델 수정은 범위 밖). 작업 변수(SWT의 쿼리 매개변수를
  작업 변수로 저장)는 저장하지 않는다.
- SWT 작업은 이 창에서 저장한 것과 같은 형식으로 직접 만든 작업으로 확인했다(시험 PC에 SWT 제품이 없음).
- SQL 스크립트 작업의 실행 전 질문은 그 세션의 쿼리 관리자 기록으로 트랜잭션을 찾는다(열린 트랜잭션 목록과 같은 기준). Chromite Agent 격리 세션은 주 세션이
  아니므로 대상이 아니다. Oracle·SQL Server의 수동 커밋 연결은 실행해 보지 않았다(PostgreSQL 수동 커밋, SQL Server·MariaDB 자동 커밋을 확인).
- 작업 폴더, 형식별 묶기, 작업 변수, 최근 작업 메뉴, 실행 기록 여러 개 보기(마지막 실행만). 예약 실행은 2026-11-12에 더함([task-scheduling.md](task-scheduling.md)).
- 범위 밖에서 발견(2026-11-12, `chromite/task-scheduling`): SQL 스크립트 작업을 자동 커밋 세션에서 손으로 실행할 때 스크립트가 `BEGIN TRAN`으로 연 트랜잭션을
  끝내지 않으면, 처리기(`SQLScriptExecuteHandler.askEndTransaction`·`askOpenTransaction`)가 묻지 않고 열어 둔다. 두 질문이 쿼리 관리자의
  `isTransactionActive`(수동 커밋의 트랜잭션)만 보고 명시적 BEGIN을 보는 `getTransactionState().isOpen()`(데이터 넣기가 쓰는 것)을 보지 않기 때문이다.
  SQL Server에서 확인: 손 실행 뒤 그 세션의 `open_transaction_count`가 늘었고 아무것도 묻지 않았다. 이어서 같은 세션에서 돈 다른 스크립트의 변경도 그 트랜잭션에
  들어간다. 제안: 두 질문이 `getTransactionState().isOpen()`도 보게 한다(손 실행 동작이 바뀌므로 사용자 결정 뒤). 예약 실행은 이번에 따로 막았다(시작 전 실패,
  끝나고 열려 있으면 실패 알림).
- ~~백업 고급(C-46, P18, P19, M13)~~ 2026-11-08 채움([swing-backup-restore.md](swing-backup-restore.md) "고급 백업·복원"): 스키마·테이블, pg_dumpall,
  도구 계정, 추가 인수를 대화상자가 읽고 저장한다. `pgDatabaseBackupAll` 작업도 편집된다. 도구 계정의 암호만은 작업에 저장하지 않으므로
  SWT에서 암호와 함께 저장한 작업을 여기서 편집해 저장하면 암호가 빠진다(실행만 하면 그대로 쓰인다).
- Oracle SQL*Plus 실행은 이 PC에 Instant Client가 없어 명령 만들기(명령줄, 표준 입력 로그온 미리 보기)만 확인했다. 실제 실행은 확인하지 못했다.
- SQL*Plus의 `WHENEVER SQLERROR EXIT FAILURE`가 로그온 실패에 걸리는지는 Oracle 문서와 흔한 사용법을 근거로 했고 실행해 보지 못했다.

## 변경 이력

- 2026-11-08 최초 작성(`chromite/p4-tasks-native`): Database Tasks 도구 창, 작업으로 저장·편집, 네이티브 클라이언트로 스크립트 실행(Object Explorer, Query 메뉴),
  저장된 네이티브 도구 작업의 명령줄 승인, SQL*Plus 암호를 명령줄에서 뺌, 연결 편집기의 로컬 클라이언트 칸.
- 2026-11-08 (`chromite/native-backup-advanced`) 백업 고급 설정을 대화상자가 읽고 저장하게 되어 "남긴 것"의 해당 줄을 고침. `pgDatabaseBackupAll` 작업도 편집 대상.
- 2026-11-08 데이터 전송 작업과 SQL 스크립트 작업(`chromite/transfer-tasks`, swt-parity C-39·C-41·C-43·B-E12): 세 마법사의 작업으로 저장…, 저장된
  데이터 전송 작업(SWT 작업 포함) 편집, 새 SQL 스크립트 작업…과 편집, 실행 확인의 작업 내용(대상 비우기·다시 만들기, 스크립트와 연결), 스크립트 작업이
  세션의 커밋 방식을 바꾸지 않고 실행 전·후 열린 트랜잭션을 물음(묻지 않고 커밋·롤백하지 않음), `openEntityEditor`가 개체 탐색기에서 객체를 보임.
- 2026-11-08 기본 자리를 새 기본 배치(앱 변경 #12)에 맞춤: Query Library 탭 → Properties 탭 → Chromite Agent 위 → 오른쪽 가장자리(`chromite/ui-layout`).
- 2026-11-12 예약 실행(앱 변경 #104, `chromite/task-scheduling`): 일정 명령, 일정·다음 실행 열, 예약 실행 표시, 사용 중인 일정 줄. 범위 밖에서 발견한
  자동 커밋 세션의 명시적 BEGIN("남긴 것"). [task-scheduling.md](task-scheduling.md).
