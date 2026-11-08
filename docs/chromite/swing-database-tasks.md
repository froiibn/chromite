# 저장된 작업(Database Tasks)과 네이티브 클라이언트 스크립트 실행

## 목적

- **Database Tasks 보기**: 백업·복원·스크립트 실행 설정을 작업으로 저장해 두고 다시 돌린다. 저장한 작업의 목록, 실행, 편집, 삭제, 마지막 결과와 로그를
  한 도구 창에서 본다. SWT의 Database Tasks 보기(`DatabaseTasksView`)에서 매일 쓰는 부분이다(swt-parity C-43).
- **네이티브 클라이언트로 스크립트 실행**: SQL 스크립트 파일을 DB의 명령줄 클라이언트(`psql`, `mysql`/`mariadb`, `sqlplus`)로 실행한다.
  큰 덤프 파일이나 클라이언트 전용 명령(`\copy`, `SOURCE`, `@file`)이 든 스크립트를 쿼리 창이 아니라 그 도구로 돌릴 때 쓴다
  (swt-parity C-23, C-47, P21, M15, O22).
- **연결 편집기의 로컬 클라이언트**: 도구의 클라이언트 폴더를 연결 설정에서 고른다. 전에는 백업·복원 대화상자에서만 정할 수 있었다(follow-up 27).

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

- 백업…, 복원…, 네이티브 클라이언트로 스크립트 실행… 대화상자의 **스크립트 > 작업으로 저장…**: 이름을 물은 뒤 지금 설정을 프로젝트의 작업으로 저장한다.
  고른 로컬 클라이언트는 연결의 클라이언트 홈으로도 저장한다(실행할 때와 같음). 그 대화상자에서 다시 저장하면(**작업 저장**) 같은 작업을 고친다.
- **보기 > 데이터베이스 작업**(Database Tasks): 오른쪽 열에서 Query Library와 같은 탭 묶음으로 열린다(처음에는 숨김).
  - 열: 이름, 형식, 연결, 마지막 실행, 마지막 결과(성공 / 실패: 오류 / 취소됨 / 실행 중… / 아직 실행하지 않음), 걸린 시간.
  - 도구 막대·오른쪽 클릭: **실행**, **편집…**(두 번 클릭), **마지막 로그 보기**, **삭제…**(Delete 키), **새로 고침**(F5).
  - 실행은 백그라운드로 돌고, 끝나면 행을 다시 읽고 알림(성공 / 실패와 오류)을 띄운다.
  - 네이티브 도구 작업을 실행하면 처리기가 도구를 시작하기 전에 **실제 명령줄을 셸 명령 승인 대화상자로 묻는다**(이번만 실행 / 항상 허용 / 취소, 취소가 기본).
    저장된 설정은 사용자가 방금 입력한 것이 아니기 때문이다([shell-command-approval.md](shell-command-approval.md) "저장된 네이티브 도구 작업").
    취소하면 실행하지 않고 마지막 결과에 "Shell command was not approved…"가 남는다.
  - 다른 형식의 작업(예전 SWT에서 저장한 데이터 전송, SQL 스크립트 작업 등)은 이 창이 먼저 "실행할까요?"를 묻는다(취소가 기본). 편집 화면은 아직 없다.
  - 삭제는 삭제 / 취소로 묻는다(취소가 기본).
  - 연결에 로컬 클라이언트가 정해져 있지 않은 네이티브 도구 작업은 실행하지 않고 어디서 정하는지 알린다(아래 "로컬 클라이언트를 정하지 않으면").

### 연결 편집기의 로컬 클라이언트

- 연결 편집 > 옵션 >> > **연결 속성**의 **로컬 클라이언트**: 이 드라이버의 로컬 클라이언트 폴더 목록(내려받는 클라이언트는 빠짐), 찾아보기…로 폴더 더하기,
  "(정하지 않음)". 저장한 값이 이 컴퓨터에 없으면 "(이 컴퓨터에 없음)"으로 남겨 두고 바꾸지 않는다.
- 네이티브 클라이언트가 있는 드라이버(드라이버 공급자가 클라이언트를 찾거나 드라이버에 클라이언트 위치가 있는 것: PostgreSQL, MySQL/MariaDB, Oracle 등)에서만 보인다.
- 백업·복원, 스크립트 실행, 저장된 작업, Chromite Agent의 `dumpDatabase`·`restoreDump`가 모두 이 값(`clientHomeId`)을 쓴다.

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
| `ui.swing` `DatabaseTasksPanel` (새) | Database Tasks 도구 창 |
| `ui.swing` `ConnectionSettingsPanel` | 연결 속성의 로컬 클라이언트 칸 |
| `ui.swing` `internal/DatabaseTasksMessages` (새), `BackupRestoreMessages`, `ConnectionMessages` | 화면 문구(영어·한국어) |
| `model.ai.tools` `NativeBackupTools` | 로컬 클라이언트 안내 문구에 연결 편집기의 칸을 더함 |

작업은 모델의 작업 관리자(`DBTTaskManager`, 프로젝트의 `.dbeaver/tasks.json`)에 저장되고, 실행 기록과 로그도 모델이 남긴다(`TaskRunJob`, 작업 통계 폴더).
작업 형식은 모델에 이미 있는 것을 그대로 쓴다: `pgDatabaseBackup`, `pgDatabaseRestore`, `pgScriptExecute`, `mysqlDatabaseBackup`, `mysqlDatabaseRestore`,
`mysqlScriptExecute`, `oracleScriptExecute`. 그래서 SWT에서 저장한 같은 형식의 작업도 편집·실행된다.

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

## upstream 대비 변경점

| 항목 | DBeaver(SWT) | Chromite |
|---|---|---|
| 작업 목록 | Database Tasks 보기(폴더, 범주·형식별 묶기, 만들기 마법사, 작업 변수, 최근 작업 메뉴, 완료 소리) | 한 표(이름, 형식, 연결, 마지막 실행·결과·시간), 실행·편집·로그·삭제. 폴더·묶기·변수·최근 작업 메뉴 없음 |
| 저장된 네이티브 작업 실행 | 묻지 않고 실행 | 실행할 때 실제 명령줄 승인(이번만 / 항상 허용 / 취소) |
| 로컬 클라이언트 없을 때 | 드라이버의 첫 클라이언트(내려받을 수 있음) | 실행하지 않고 안내 |
| SQL*Plus 로그온 | `user/"암호"@url` 인수 | `/nolog` + 표준 입력 `CONNECT`, 실패하면 종료, 스키마를 현재 스키마로 |
| 스크립트 실행 화면 | 마법사(작업 설정 페이지) + 편집기 "네이티브 클라이언트로 실행"(`sql.executors`) | 백업·복원 대화상자의 스크립트 방식, Query 메뉴 항목, 실행 전 확인 |
| 로컬 클라이언트 고르기 | PostgreSQL·MySQL 연결 페이지 | 연결 속성의 로컬 클라이언트 칸(네이티브 클라이언트가 있는 드라이버) + 대화상자의 로컬 클라이언트 페이지 |

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

## 남긴 것

- 데이터 전송(내보내기·가져오기)을 작업으로 저장·편집(C-39), 여러 스크립트 × 여러 연결의 SQL 스크립트 작업(C-41)은 만들지 않았다. 그런 작업이 있으면
  목록에 보이고 실행·삭제만 된다.
- 작업 폴더, 형식별 묶기, 작업 변수, 최근 작업 메뉴, 예약 실행(SWT CE에도 없음), 실행 기록 여러 개 보기(마지막 실행만).
- ~~백업 고급(C-46, P18, P19, M13)~~ 2026-11-08 채움([swing-backup-restore.md](swing-backup-restore.md) "고급 백업·복원"): 스키마·테이블, pg_dumpall,
  도구 계정, 추가 인수를 대화상자가 읽고 저장한다. `pgDatabaseBackupAll` 작업도 편집된다. 도구 계정의 암호만은 작업에 저장하지 않으므로
  SWT에서 암호와 함께 저장한 작업을 여기서 편집해 저장하면 암호가 빠진다(실행만 하면 그대로 쓰인다).
- Oracle SQL*Plus 실행은 이 PC에 Instant Client가 없어 명령 만들기(명령줄, 표준 입력 로그온 미리 보기)만 확인했다. 실제 실행은 확인하지 못했다.
- SQL*Plus의 `WHENEVER SQLERROR EXIT FAILURE`가 로그온 실패에 걸리는지는 Oracle 문서와 흔한 사용법을 근거로 했고 실행해 보지 못했다.

## 변경 이력

- 2026-11-08 최초 작성(`chromite/p4-tasks-native`): Database Tasks 도구 창, 작업으로 저장·편집, 네이티브 클라이언트로 스크립트 실행(Object Explorer, Query 메뉴),
  저장된 네이티브 도구 작업의 명령줄 승인, SQL*Plus 암호를 명령줄에서 뺌, 연결 편집기의 로컬 클라이언트 칸.
- 2026-11-08 (`chromite/native-backup-advanced`) 백업 고급 설정을 대화상자가 읽고 저장하게 되어 "남긴 것"의 해당 줄을 고침. `pgDatabaseBackupAll` 작업도 편집 대상.
