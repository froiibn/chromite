# 셸 명령 실행 승인

## 목적

저장된 설정에 들어 있는 셸 명령은 실행하기 전에 사용자에게 정확한 명령을 보여 주고 승인을 받는다.
대상은 세 가지다.

- 연결 이벤트 명령: 연결하기 전·후, 연결을 끊기 전·후.
- "셸 명령의 암호" 인증 방식(`shell_command`): 연결할 때 명령의 표준 출력에서 암호를 읽는다.
- 데이터 전송의 "프로세스 실행" 단계: 전송을 마친 뒤 명령을 실행한다.
- 저장된 네이티브 도구 작업(2026-11-08): 백업·복원·스크립트 실행 작업이 도구(pg_dump, psql, mysql, sqlplus 등)를 시작하기 전의 실제 명령줄.

이 명령들은 `data-sources.json`, 가져오거나 공유한 연결 설정, 저장된 작업에 들어 있을 수 있다. 사용자가 방금 입력한 것이 아니므로 묻지 않고 실행하면 안 된다.
Swing 화면에는 이 명령을 편집하는 곳이 없어서, 사용자가 모르는 명령이 연결할 때 실행될 수 있었다.
2026-11-08부터 연결 대화상자 Options의 Shell Commands 탭에서 연결 이벤트 명령을 고칠 수 있다([swing-conn-advanced.md](swing-conn-advanced.md) "셸 명령").
그 탭에서 입력하거나 고친 명령도 승인 기록에 바로 넣지 않는다. 처음 실행할 때 이 승인 대화상자를 거친다(SWT는 편집 화면에서 입력한 명령을 승인 기록에 넣었다).

SWT를 지운 뒤(#68) 승인 서비스(`UIServiceShellCommands`)를 등록하는 곳이 없어졌다. 세 호출부가 모두 `if (service != null)`로 확인을 건너뛰어 승인 없이 실행했다.
감사 기록: [swt-parity/A-platform.md](swt-parity/A-platform.md) S4.

## 구조

| 위치 | 역할 |
|---|---|
| model `runtime/ui/UIServiceShellCommands` | 승인 서비스 계약. `validate`(정적): 등록된 서비스에 묻고, 서비스가 없으면 실행을 거부한다. `getConnectionScope`: 연결의 승인 범위. `getCommandText`: 보여 주고 기억하는 명령 문자열 |
| model `model/preferences/ConfirmedShellCommandsStore` | "항상 허용" 기록. 전역 설정 폴더의 `confirmed_shell_commands.json`. 읽기·쓰기는 `ConfirmedActionsStore` |
| registry `DataSourceDescriptor.processEvents` | 연결 이벤트 명령. 범위는 그 연결, 문맥은 프로젝트·데이터 원본·이벤트 유형 |
| model `impl/auth/AuthModelDatabaseShellCommand.resolvePassword` | 암호 명령. 범위는 그 연결, 문맥은 프로젝트·연결·이벤트("암호 읽기(연결할 때)") |
| data.transfer `processor/ExecuteCommandEventProcessor` | 전송 뒤 명령. 범위 없음(매번 묻는다), 문맥은 작업 이름·이벤트("데이터 전송을 마친 뒤") |
| tasks.native `AbstractNativeToolHandler.executeProcess` | 저장된(임시가 아닌) 네이티브 도구 작업의 명령줄. 범위는 그 작업(`getTaskScope`), 문맥은 프로젝트·작업(이름과 형식)·연결·이벤트("저장된 작업 실행") |
| ui.swing `SwingShellCommandsService` | Swing 승인 대화상자. `plugin.xml`의 `com.seizia.chromite.service` 확장으로 등록 |
| ui.swing `internal/ShellCommandMessages` | 대화상자 문구(영어, 한국어) |

문맥 문구는 각 호출부의 NLS 묶음(`RegistryMessages`, `ModelMessages`, `DTMessages`)에 있다. 대화상자 문구는 `ShellCommandMessages`에 있다.

## 동작

1. 호출부가 `UIServiceShellCommands.validate(command, scope, context)`를 부른다.
2. 서비스가 없으면 예외를 던지고 명령을 실행하지 않는다.
3. 명령 문자열이 비어 있으면 실행할 것이 없으므로 묻지 않는다.
4. 범위가 있고 같은 [범위, 작업 폴더, 명령]이 기록에 있으면 묻지 않고 실행한다.
5. 그 밖에는 대화상자로 묻는다. 호출 스레드(대개 연결 Job)는 답할 때까지 기다린다.
   - 경고 문장, 문맥(프로젝트, 연결, 이벤트), 작업 폴더(있을 때), 정확한 명령을 보인다. 명령은 고정폭 읽기 전용 칸에 줄을 바꾸지 않고 보인다.
   - **Run Once**(이번만 실행): 실행하고 기억하지 않는다.
   - **Always Allow**(항상 허용): 기록에 넣고 실행한다. 범위가 없으면 이 단추를 보이지 않는다.
   - **Cancel**(취소): 기본 단추다. Esc나 창 닫기도 같다. 예외를 던지므로 명령을 실행하지 않고 그 작업도 멈춘다.
     연결은 연결 오류 대화상자에 "Shell command was not approved (…)"가 나오고 연결되지 않는다.
     끊기 전 명령을 취소하면 연결 끊기가 실패하고 연결은 그대로 남는다(SWT와 같음).

## 설계 결정과 이유

- **서비스가 없으면 거부한다(fail closed).** 전에는 서비스가 없으면 승인 없이 실행했다. 이제 그 판단을 `validate` 한 곳에 두었다.
  - 다른 앱에 미치는 영향: 이 서비스를 등록하는 제품은 Swing 제품뿐이다. 헤드리스 앱(`com.seizia.chromite.headless`)은 단위 테스트용이다.
    헤드리스 앱에서는 셸 명령이 설정된 연결만 실패하고, 실패 이유가 오류 문구에 나온다. 별도의 CLI 제품은 없다(`product/pom.xml` 프로필은 `product-chromite-swing`뿐).
  - 테스트 `AuthModelDatabaseShellCommandTest.commandResolvesPasswordWithoutUiService`는 옛 동작(서비스 없이 실행)을 확인했다.
    바뀐 동작에 맞춰 `commandIsRefusedWithoutUiService`(실행하지 않음, 암호 없음)로 바꿨다.
- **승인은 연결마다, 정확한 명령과 작업 폴더로 기억한다.**
  - SWT는 명령 문자열만 전역으로 기억했다. 그래서 한 연결에서 승인한 명령은 다른 연결이나 가져온 연결에서도 묻지 않고 실행됐다.
  - Chromite는 항목 하나를 `[범위, 작업 폴더, 명령]` JSON 배열 문자열로 만든다. 다른 연결, 한 글자라도 바뀐 명령, 바뀐 작업 폴더는 다시 묻는다.
    작업 폴더까지 묶는 이유: `run.bat` 같은 상대 경로 명령은 작업 폴더가 바뀌면 다른 파일을 실행한다.
  - 연결 범위는 `connection:<프로젝트 절대 경로>|<연결 ID>`다(SQLite 확장 승인 `SQLiteExtensionSettings`와 같은 방식).
    작업 공간을 옮기거나 다른 PC에서 가져온 프로젝트는 다시 묻는다.
  - Connect 대화상자의 연결 시험은 임시 복사본(새 ID)으로 연결한다. `ATTRIBUTE_TEST_CONNECTION_SOURCE`로 원래 연결을 찾아 그 범위를 쓴다.
  - 명령은 변수(`${host}` 등)를 바꾸기 전의 문자열로 보이고 기억한다. 바꾼 뒤의 문자열은 `${password}` 같은 비밀 값을 담을 수 있어 화면과 파일에 남기지 않는다(SWT와 같음).
- **기록 파일은 SWT와 같은 파일을 쓴다.** 전역 설정 폴더(`ChromiteData\settings`, Windows 기본 `%AppData%\ChromiteData\settings`, 환경 변수 `CHROMITE_DATA`로 바꿀 수 있음)의
  `confirmed_shell_commands.json`, JSON 문자열 배열이다.
  - 프로젝트 폴더 밖이라서 가져오거나 공유한 연결 설정이 승인을 함께 들여올 수 없다.
  - SWT가 남긴 항목(명령 문자열만)은 새 형식과 맞지 않아 쓰이지 않고 파일에 그대로 남는다. 전역 승인을 이어받지 않으므로 처음 한 번은 다시 묻는다.
  - 승인을 지우는 화면은 없다. 파일에서 해당 줄을 지우면 된다(SWT의 "승인한 명령 관리" 링크는 SWT 연결 이벤트 편집 화면에 있었다).
- **데이터 전송 단계는 기억하지 않는다.** 연결에 묶이지 않는 단계이고, Swing 내보내기 마법사는 이 단계를 고를 수 없다(SWT에서 저장한 작업으로만 닿는다).
  범위를 따로 만들지 않고 매번 묻는다.
- **기본 단추는 Cancel이다.** Enter를 누르거나 창을 닫아서 실행되는 일이 없게 한다.
- **취소는 오류로 알린다.** SWT처럼 예외를 던진다. 연결이 왜 안 됐는지 사용자가 알 수 있다. 예외 문구는 영어다(AGENTS.md "Hardcode").

## 저장된 네이티브 도구 작업

2026-11-08 Database Tasks 보기([swing-database-tasks.md](swing-database-tasks.md))로 저장된 작업을 다시 실행할 수 있게 되면서 대상에 더했다.
작업 설정(`tasks.json`)은 가져온 프로젝트나 손으로 고친 파일일 수 있고, 네이티브 도구 작업은 그 설정으로 이 컴퓨터의 실행 파일을 돌린다.

- 승인은 처리기(`AbstractNativeToolHandler.executeProcess`)가 도구를 시작하기 바로 전에, 실제로 실행할 명령줄(`getCommandLine`의 결과)로 받는다.
  화면이 아니라 처리기에서 받으므로 작업을 실행하는 모든 경로(Database Tasks 보기, 앞으로의 예약 실행 등)에 적용된다.
- 작업 폴더는 도구 실행 파일의 폴더(`ProcessBuilder.directory`와 같음)다. 암호는 명령줄에 없다(PGPASSWORD·MYSQL_PWD, SQL*Plus는 표준 입력).
  그래도 암호로 끝나는 인수는 실행 로그처럼 `******`로 가린다.
- 범위는 작업마다다(`UIServiceShellCommands.getTaskScope`: `task:<프로젝트 절대 경로>|<작업 ID>`). 같은 명령이라도 다른 작업이나 다른 작업 공간에서 가져온 작업은 다시 묻는다.
  `${timestamp}`가 든 백업 파일 이름처럼 실행마다 명령이 바뀌면 항상 허용해도 매번 다시 묻는다(정확한 명령만 기억한다).
- 취소하면 `InterruptedException`(작업 확인을 거절했을 때와 같은 방식)으로 실행을 멈추고, 실행 기록에 "Shell command was not approved …"가 남는다.
- 대화상자와 Chromite Agent 도구가 만든 임시 작업은 묻지 않는다. 사용자가 그 자리에서 설정을 보고 실행하거나(대화상자, 스크립트 실행은 명령줄 확인),
  에이전트의 확인 대화상자가 명령줄을 보인다.
- 대화상자의 "항상 허용" 안내는 "이 연결이나 저장된 작업에서"로 바꿨다.

확인(2026-11-08, `chromite/p4-tasks-native`): 저장한 psql 스크립트 작업을 실행 → 대화상자에 프로젝트·작업·연결·이벤트·작업 폴더·명령(`psql.exe … -v ON_ERROR_STOP=1
--echo-errors chr_p4_tasks`), 기본 단추는 취소 → 취소하면 psql을 실행하지 않고 마지막 결과가 "실패: Shell command was not approved …". 이번만 실행 → 실행됨.
MariaDB 작업에서 항상 허용 → `confirmed_shell_commands.json`에 `task:…|<작업 ID>` 항목이 생기고 다시 실행할 때 묻지 않음.

## upstream 대비 변경점

| 항목 | DBeaver(SWT) | Chromite |
|---|---|---|
| 서비스가 없을 때 | 승인 없이 실행 | 실행 거부 |
| 대화상자 단추 | Add / Cancel(승인하면 항상 기억) | Run Once / Always Allow / Cancel |
| 기억 단위 | 명령 문자열(전역) | 연결 범위 + 작업 폴더 + 명령 |
| 대화상자 문맥 | 호출부가 준 값 | 같음 + 작업 폴더. 암호 명령·전송 단계 문구를 NLS로 옮김(전에는 영어 고정) |
| 서비스 인터페이스 | `isShellCommandExecutionEnabled`, `addConfirmedCommand`, `FEATURE_CONNECTION_SHELL_COMMANDS` | 삭제. SWT 연결 이벤트 편집 화면만 썼고 Swing에는 호출부가 없다. `validateByUser`에 승인 범위 인자 추가 |
| 기록 저장 | `ConfirmedShellCommandsStore`가 파일을 직접 읽고 씀, `prepareFile`(관리 링크용) | `ConfirmedActionsStore`에 맡김(임시 파일 뒤 교체), `prepareFile` 삭제 |
| 저장된 네이티브 도구 작업 | 묻지 않고 실행 | 실행할 때마다 실제 명령줄 승인(작업 범위로 기억) |

## 남은 것

- 승인 기록을 보고 지우는 화면이 없다(파일을 직접 고친다).
- Swing에는 연결 이벤트 명령을 편집하는 화면이 없다(A-C11 셸 프로세스 보기도 없음).
- 외부 SQL 서식기(`SQLFormatterExternal`)의 명령은 설정에서 읽어 승인 없이 실행한다. SWT도 같았고 이 서비스의 대상이 아니다. 별도 검토 대상이다.

## 확인

2026-11-07, Swing 제품을 개인 작업 공간 사본으로 실행해 확인했다(증거: `D:\Tools\_scratch\chromite-shell-command-approval\`).
복사한 `data-sources.json`의 PostgreSQL 테스트 연결에 연결하기 전 명령 `cmd /c echo ran>>…\marker.txt`를 넣었다.

| 순서 | 동작 | 결과 |
|---|---|---|
| 1 | 연결 | 대화상자에 프로젝트, 데이터 원본, 이벤트 유형, 정확한 명령이 나옴(`shots/1-dialog-before-connect.png`) |
| 2 | 취소 | 연결 오류 "Shell command was not approved …", 연결 안 됨, `marker.txt` 없음(`shots/2-cancel-connect-error.png`) |
| 3 | 연결 → 이번만 실행 | 연결됨, `marker.txt` 1줄(`shots/3-dialog-run-once.png`) |
| 4 | 끊고 다시 연결 | 다시 물음(`shots/4-dialog-asked-again.png`) → 항상 허용 → 연결됨, 2줄. `data\ChromiteData\settings\confirmed_shell_commands.json`에 [범위, 작업 폴더, 명령] 항목 하나 |
| 5 | 끊고 다시 연결 | 묻지 않고 연결됨, 3줄(`shots/5-connected-without-dialog.png`) |
| 6 | 앱을 끄고 명령을 `ran2`로 바꿔 다시 실행, 연결 | 다시 물음(`shots/6-dialog-edited-command.png`) → 취소, 줄 수 그대로 |
| 7 | 앱을 끄고 명령을 원래대로 되돌려 다시 실행, 연결 | 묻지 않고 연결됨(재시작 뒤에도 기억), 4줄 |

UI 조작은 실행 중인 앱에 붙이는 스크래치 테스트 도구(`agent\`, `run.ps1`, 단계별 `*.txt`와 `*.txt.log`)로 했다. 화면 사진은 창만 찍었다.
기록 파일의 `>`는 Gson이 `>`로 적는다(읽을 때는 같은 문자).

회귀 테스트는 실행하지 않았다(요청 없음). 기존 단위 테스트 `AuthModelDatabaseShellCommandTest`는 바뀐 계약에 맞춰 고쳤고 빌드에서 컴파일만 확인했다.

## 변경 이력

- 2026-11-07 최초 작성: Swing 승인 서비스 등록, 서비스가 없을 때 거부, 연결 범위 승인, 대화상자 문구 NLS(`chromite/shell-command-approval`)
- 2026-11-08 저장된 네이티브 도구 작업의 명령줄 승인(작업 범위 `getTaskScope`, 문맥 문구 `model_shell_command_context_task`·`model_shell_command_event_saved_task`),
  "항상 허용" 안내 문구(`chromite/p4-tasks-native`, [swing-database-tasks.md](swing-database-tasks.md))
- 2026-11-08 연결 이벤트 명령 편집 탭이 생김. 편집한 명령도 실행 전에 승인을 받는다(실행 확인: Cancel이면 연결 안 됨, Run Once면 연결됨)(`chromite/p4-connection-tools`)
