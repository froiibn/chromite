# 저장된 작업의 예약 실행 (앱 변경 #104)

## 목적

저장된 작업(백업, 복원, 스크립트 실행, SQL 스크립트, 데이터 내보내기·가져오기·테이블 복사, [swing-database-tasks.md](swing-database-tasks.md))에 일정을 붙여,
Chromite가 실행 중일 때 정해진 시각에 저절로 돌게 한다. 로드맵 12번 "범용 작업 예약"을 2026-11-12에 앞당겼다([roadmap-post-1.0.md](roadmap-post-1.0.md)).

사용자 결정:

| 날짜 | 결정 |
|---|---|
| 2026-11-06 (쿼리 예약, 여기에도 적용) | 예약 실행은 **Chromite가 실행 중일 때 앱 안에서만** 한다. OS 스케줄러 등록과 헤드리스 실행기는 기각 |
| 2026-11-12 | 앞당겨서 지금 작업. 스케줄러는 쿼리 예약과 하나로 공유. 무인 실행은 질문 창을 띄우지도 대신 답하지도 않고 암묵 커밋·롤백 없음 |
| 2026-11-12 | "예약관련 사항은 레거시와 동일하게", "규칙만 레거시처럼": 실행 방식(앱 안, 실행 중에만)은 그대로, 규칙은 DBeaver처럼. 모든 작업 형식을 예약할 수 있고 예약 실행은 저장된 작업을 손으로 실행할 때와 같이 돌린다. 손 실행이 매번 묻는 확인은 일정을 켤 때 한 번 묻고 그 답을 예약과 함께 저장한다 |

## 사용법

- **Database Tasks > 일정**(Schedule…, 도구 막대의 시계 아이콘·오른쪽 클릭 메뉴): 고른 작업의 일정 대화상자.
  - **이 작업을 일정에 따라 실행**(사용 여부), **실행 시기**(N분/N시간마다, 매일 hh:mm, 평일 hh:mm: 쿼리의 Schedule…과 같은 칸),
    **이 컴퓨터에서 사용**(기본 켬, 이 PC의 설정), **일정 제거**, 확인 / 취소.
  - 네이티브 도구 작업(백업, 복원, 네이티브 스크립트)은 실행할 명령줄과 출력 파일 이름 형식(`${timestamp}` 같은 변수 그대로)을 보이고
    "예약 실행은 이 명령을 묻지 않고 실행합니다(변수가 무엇으로 바뀌든)"라고 적는다.
  - 예약 실행이 실패할 수 있는 이유를 미리 보인다(아래 "켤 때 미리 알리는 것").
  - 확인을 누를 때 일정을 새로 켜면, 손으로 실행할 때마다 묻는 것(복원 확인, 대상 테이블 비우기·다시 만들기, SQL 스크립트 목록, 끝나고 실행할 명령)을
    같은 문구로 한 번 묻는다(**켜기** / 취소, 취소가 기본). 이미 켜져 있고 작업이 바뀌지 않았으면 다시 묻지 않는다.
- 목록의 열: **일정**("매분", "매일 02:00", 꺼져 있으면 "(사용 안 함)", 이 PC에서 끄면 "(이 컴퓨터에서 안 씀)", 이 PC에서 돌 수 없으면
  "(실행 안 함: 이유)"), **다음 실행**("11-13 02:00", 실행 중이면 "실행 중", 놓친 시각 "놓침 11-12 02:00(Chromite가 꺼져 있었음)",
  겹쳐 건너뛴 시각 "건너뜀 11-12 19:21: 이전 실행이 아직 진행 중이었음"). **마지막 결과**에 예약 실행이면 "(예약)"이 붙는다.
- 목록 아래 줄: "사용 중인 일정 N개. 다음: 작업, 시각. 일정은 Chromite가 실행 중일 때만 실행됩니다." 사용 중인 일정이 없으면 숨는다.
- 알림: 예약 실행이 실패하면 오류 토스트("'작업' 예약 실행이 실패했습니다"와 이유), 성공하면 조용하다(마지막 결과 열과 로그에 남음).
- 예약 실행의 로그는 손 실행과 같은 작업 로그(마지막 로그 보기)다. 묻지 않고 실행한 명령, 묻지 못해 실패한 질문, 보이지 않은 화면이 끝에 덧붙는다.

## 구조

| 위치 | 내용 |
|---|---|
| `ui.swing` `ScheduleTiming` (새) | 시각 규칙 하나: 간격·매일·평일, 다음 시각(`nextRun`), 지난 시각(`previousRun`, 놓침 표시), 사용 여부, 문구, 저장 키. `QueryLibrarySchedule`이 상속 |
| `ui.swing` `RunScheduler` (새) | 스케줄러 하나: 15초 타이머(데몬 스레드), 항목(`Entry`)마다 Job 하나, 겹치면 건너뜀, 꺼진 항목은 다음 시각을 미래로, 변경 알림, 시작·정지 |
| `ui.swing` `QueryScheduler` | 쿼리 항목: 실행·기록·알림만(타이머는 `RunScheduler`) |
| `ui.swing` `TaskScheduler` (새) | 작업 항목: 정의 읽기(`tasks.json`의 `schedule`), 이 PC 활성(설정 `databaseTasks.schedule.active.<프로젝트>/<작업>`), 동의 지문, 켤 때의 확인·경고, 실행 전 확인, 실행, 로그 덧붙이기, 실패 토스트 |
| `ui.swing` `UnattendedRuns` (새) | 무인 실행 표시와 질문 거절(아래 "무인 실행") |
| `ui.swing` `ScheduleWhenPanel` (새) | 실행 시기 칸(쿼리·작업 대화상자 공용) |
| `ui.swing` `TaskScheduleDialog` (새) | 작업의 일정 대화상자 |
| `ui.swing` `DatabaseTasksPanel` | 일정 명령, 일정·다음 실행 열, 예약 표시, 아래 상태 줄 |
| `ui.swing` `MainFrame` | 메인 창이 열릴 때 `RunScheduler`·`TaskScheduler` 시작, 닫힐 때 정지(전에는 Query Library 창이 시작·정지) |
| `ui.swing` `SwingPlatformUI`, `SwingShellCommandsService`, `SwingDriversService`, `SwingConnectionInitService`, `SwingSqlService`, `SwingSqliteExtensionConfirmation` | 무인 실행 가드(각 메서드 첫 줄) |
| `ui.swing` `SettingsTransferCommands` | `databaseTasks.schedule.active.`를 이 PC의 상태 키로(설정 내보내기에서 뺌) |
| `model` `DBTTask` | `getSchedule`/`setSchedule`(키·값) |
| `model` `DBTTaskExecutionListener` | 기본 메서드 `getStartedBy`(실행 기록의 시작한 것), `checkBeforeRun`(처리기 전 확인), `isTaskConfirmed`(작업 확인을 이미 받음) |
| `registry` `TaskImpl`, `TaskManagerImpl`, `TaskConstants` | `schedule` 읽기·쓰기 |
| `registry` `TaskRunJob` | 위 세 기본 메서드를 씀 |

## 저장

- 예약은 작업과 함께 프로젝트의 `tasks.json`에 저장한다. 작업 항목의 `state`(처리기 설정) 옆에 `schedule` 객체를 둔다.

  ```json
  "schedule": { "daily": "02:00", "enabled": "true", "since": "2026-11-12T14:05", "consent": "<SHA-256>" }
  ```

  키는 쿼리 파일의 `-- @schedule` 블록과 같다(`every: 30m|2h`, `daily: hh:mm`, `weekdays: hh:mm`, `enabled`). `since`는 일정을 저장한 시각이고
  "놓침" 표시에만 쓴다(그 전의 시각은 놓친 것이 아니다). `consent`는 동의 지문이다(아래). 예약이 없으면 `schedule`을 쓰지 않는다.
- `state` 안에 두지 않은 이유: 작업 편집 화면 세 곳이 다시 저장할 때 `state` 전체를 바꾼다(`DTSavedTasks.java:352`, `NativeBackupRestore.java:496·536`,
  `DataTransferWizard.java:670`). 따로 두면 작업을 고쳐 저장해도 예약이 남는다. DBeaver는 모르는 키를 무시한다.
- 작업을 지우면 예약도 함께 지워진다(같은 항목).
- **이 PC에서 돌지**는 설정 `databaseTasks.schedule.active.<프로젝트 ID>/<작업 ID>`(기본 꺼짐)다. 공유한 프로젝트(Git 등)의 일정이 다른 PC마다 저절로
  돌지 않게 하기 위해서다(쿼리 예약의 `queryLibrary.schedule.active.*`와 같은 규칙). 설정 내보내기에서 뺀다.
- 설정 내보내기·가져오기([settings-transfer.md](settings-transfer.md))는 지금 `tasks.json`을 옮기지 않는다. 그래서 일정은 프로젝트(작업 파일)와 함께 옮겨지고,
  설정 파일로는 옮겨지지 않는다(작업도 마찬가지).
- 활성 프로젝트의 작업만 예약한다(Database Tasks 창이 보이는 작업과 같다).

## 하나의 스케줄러

- 시각 규칙은 `ScheduleTiming` 하나다. 쿼리 예약(`QueryLibrarySchedule extends ScheduleTiming`)과 작업 예약이 같은 클래스를 쓴다.
- 타이머·겹침 건너뛰기·일시 정지·변경 알림·시작과 정지는 `RunScheduler` 하나다. 쿼리(`QueryScheduler.Entry`)와 작업(`TaskScheduler.TaskEntry`)은
  `RunScheduler.Entry`의 하위 클래스로 무엇을 실행하고 어떻게 기록하는지만 정한다.
- 일정 대화상자의 실행 시기 칸은 같은 부품(`ScheduleWhenPanel`)이다.
- 쿼리 예약의 동작과 파일 형식은 바뀌지 않았다. 바뀐 것: 시작·정지를 Query Library 창이 아니라 메인 창이 한다.

## 동의 지문과 한 번만 묻는 확인

- 손 실행이 매번 묻는 것(`TaskScheduler.getConfirmations`): 모델의 작업 확인(복원: `DBTaskUtils.collectConfirmationMessages`, 손 실행의 `TaskRunJob`과 같은 문구),
  Database Tasks의 실행 확인에 적는 작업 내용(대상 비우기, 다시 만들 테이블, SQL 스크립트 목록과 연결: `DatabaseTasksPanel.describeRun`), 데이터 내보내기가
  끝나고 실행할 명령(`eventProcessors.executeCommand.command`). 일정을 켤 때 이 문구를 한 번 묻는다.
- 네이티브 도구 작업의 명령줄은 대화상자에 보이고, 일정을 켜는 것이 그 명령을 무인으로 실행하는 데 대한 동의다(손 실행의 "정확한 명령 항상 허용"은
  손 실행과 예약 작업 밖의 명령에는 그대로).
- 동의 지문(`TaskScheduler.getConsentKey`, SHA-256)은 그 확인들이 근거로 삼는 설정으로 만든다.
  - 네이티브 도구 작업: 작업 설정 전체(명령줄이 거기서 나온다).
  - 데이터 전송: 대상 비우기 여부, 다시 만들 테이블 이름, 끝나고 실행할 명령.
  - SQL 스크립트 작업: 스크립트 파일과 연결.
  - 그 밖의 형식: 작업 설정 전체.
- 작업을 고쳐 지문이 바뀌면 일정은 쉰다. 목록 일정 열에 "(실행 안 함: 일정을 켠 뒤 작업이 바뀌어…)", 예약 실행은 시작하지 않는다(시각이 되면 실패한 실행으로
  남지 않고 다음 시각만 미뤄진다). 일정 대화상자에서 다시 켜면(확인) 새 확인을 묻고 새 지문을 저장한다. 다른 설정(출력 폴더, 파일 형식 등)을 고친
  내보내기는 지문이 같아 계속 돈다.

## 무인 실행

- 예약 실행은 그 Job을 `UnattendedRuns.begin`으로 표시하고 Job 안에서 작업 관리자의 `runTask`로 바로 실행한다. 표시된 Job이 일정에 올리는 Job도 표시된다
  (전역 Job 알림의 `scheduled`는 일정에 올리는 스레드에서 불린다). 데이터 전송이 파이프를 다른 Job(`DataTransferJob`)에서 돌려도 걸린다.
- 표시된 Job에서 불린 질문은 묻지 않는다. 취소 같은 답을 대신 돌려주지 않고 예외(`UnattendedRuns.QuestionRefused`)를 던져 실행이
  "A scheduled run can't ask, decide it in a run by hand: "제목" 내용"으로 실패한다. 취소를 받은 코드가 그 뒤에 무엇을 할지(건너뛰고 계속, 열어 두기)가 질문마다
  달라 "대신 답함"이 되기 때문이다.
- 가드가 있는 메서드(각 메서드 첫 줄):
  - `SwingPlatformUI`: `confirmAction`(3·4인수), `showUserChoice`, `showErrorStopRetryIgnore`, `promptUserCredentials`(8인수, 6인수는 이리 옴),
    `promptAuthModelCredentials`, `promptUserPasswordChange`, `promptProperty`(3인수, 2인수는 이리 옴), `promptText`, `acceptLicense`는 거절(실패).
    `showError`(3개), `showMessageBox`, `showWarningMessageBox`, `openEntityEditor`(2개), `openConnectionEditor`, `executeShellProgram`, `showInSystemExplorer`는
    보이지 않고 로그에만 남김.
  - `SwingShellCommandsService.validateByUser`: 기억한 "항상 허용"이 있으면 그대로, 예약 작업 자신의 명령(그 작업의 승인 범위, 또는 범위가 없는 데이터 전송 끝 명령)은
    일정을 켠 동의로 실행하고 로그에 남김, 그 밖의 명령(연결 이벤트)은 거절.
  - `SwingDriversService.downloadDriverFiles`: 거절(내려받지 않음, 인터넷에 닿지 않음).
  - `SwingConnectionInitService.confirmInitScripts`: 기억한 승인만, 없으면 거절.
  - `SwingSqlService.confirmQueryExecution`, `SwingSqliteExtensionConfirmation.confirmExtensionLoading`: 거절.
- 처리기 전 확인(`TaskScheduler.checkBeforeRun`, `TaskRunJob`이 실행 기록과 로그를 만든 뒤 부름): 연결이 이 PC에 없음, 네이티브 도구의 로컬 클라이언트 없음,
  일정을 켠 뒤 작업이 바뀜, 연결되어 있지 않고 암호가 저장되지 않음, SQL 스크립트 작업의 수동 커밋 세션("문장마다 커밋"이 꺼져 있을 때). 걸리면 처리기를
  부르지 않고 그 이유로 실패한 실행이 된다.
- 작업 확인(복원)은 동의 지문이 맞으면 다시 묻지 않는다(`isTaskConfirmed`, 로그 "The task's confirmation was given when its schedule was turned on").
- 자격 증명: 연결에 저장된 것만 쓴다. 묻지 않고, 새 곳에 암호를 저장하지 않는다.

### 작업 형식별 질문과 예약 실행의 처리

| 작업 형식 | 손 실행이 묻는 것 | 예약 실행 |
|---|---|---|
| 모든 형식 | 저장되지 않은 암호(연결할 때) | 처리기 전 확인에서 실패("암호가 저장되어 있지 않습니다") |
| 모든 형식 | 드라이버 파일 내려받기 | 실패(내려받지 않음) |
| 모든 형식 | 연결 초기화 SQL·SQLite 확장 승인 | 기억한 승인만, 없으면 실패 |
| 모든 형식 | (연결이 이 PC에 없음) | 일정이 쉬고 이유를 보임 |
| 백업·네이티브 스크립트(pg, MySQL/MariaDB, Oracle) | 실제 명령줄 승인(이번만 / 항상 허용) | **켤 때 동의**(명령줄을 보임), 실행마다 묻지 않음. 로컬 클라이언트가 없으면 실패 |
| 복원(pg, MySQL/MariaDB) | 모델의 복원 확인 + 명령줄 승인 | **켤 때 한 번 확인**(같은 문구) + 명령줄 동의 |
| SQL 스크립트 | 실행 확인(스크립트·연결 목록) | **켤 때 한 번 확인** |
| SQL 스크립트 | 세션에 이미 열린 트랜잭션(커밋 / 롤백 / 취소) | 실패, 아무것도 실행하지 않음 |
| SQL 스크립트 | 끝에 남은 트랜잭션(커밋 / 롤백 / 열어 두기) | 수동 커밋 세션은 처리기 전 확인에서 실패(실행하지 않음). 자동 커밋 세션에서 스크립트가 `BEGIN`으로 연 트랜잭션은 아래 "트랜잭션" |
| 데이터 내보내기(파일) | 실행 확인 | 켤 때 동의(작업 내용이 없으면 묻지 않음) |
| 데이터 내보내기(파일) | 파일이 있으면 묻기(저장된 설정 "묻기") | 파일이 있으면 실패, 쓰지 않음. 덮어쓰기·덧붙이기·새 이름이 저장되어 있으면 그대로 |
| 데이터 내보내기(파일) | 끝나고 실행할 명령 승인 | **켤 때 한 번 확인**, 실행마다 묻지 않음 |
| 데이터 내보내기(파일) | 끝나고 폴더 열기 | 열지 않음(로그) |
| 가져오기·테이블 복사 | 실행 확인(대상 비우기, 다시 만들 테이블) | **켤 때 한 번 확인** |
| 가져오기·테이블 복사 | 대상 세션에 이미 열린 트랜잭션(커밋 / 롤백 / 취소) | 실패, 넣지 않음 |
| 가져오기·테이블 복사 | 대상 테이블을 만들 수 없을 때 자동 DDL로 할지 | 실패 |
| 가져오기·테이블 복사 | 오류에서 멈춤 / 다시 / 건너뛰기 | 실패(멈춤) |
| 모든 형식 | 오류 대화상자 | 보이지 않음, 로그와 실패 토스트 |

### 켤 때 미리 알리는 것

일정 대화상자는 연결하지 않고 알 수 있는 실패 이유를 "이 작업은 다음 때문에 예약 실행이 실패할 수 있습니다:" 아래에 보인다(`TaskScheduler.getWarnings`):
연결이 이 PC에 없음, 로컬 클라이언트 없음, 일정을 켠 뒤 작업이 바뀜, 암호가 저장되어 있지 않음, SQL 스크립트 작업의 수동 커밋 세션, 지금 열린 트랜잭션(SQL 스크립트,
가져오기·복사의 대상), 내보내기의 "파일이 있으면 묻기".

## 트랜잭션

- 예약 실행은 손 실행과 같은 처리기를 쓰므로 손 실행이 하지 않는 커밋·롤백을 하지 않는다.
- SQL 스크립트 작업은 연결의 주 실행 문맥에서 돈다([swing-database-tasks.md](swing-database-tasks.md) "SQL 스크립트 작업"). 수동 커밋 세션은 끝에 남은 트랜잭션을 물어야 하는데
  예약 실행은 물을 수 없으므로 실행하지 않는다(처리기 전 확인). 작업의 "문장마다 커밋"을 켜면 손 실행처럼 문장마다 커밋한다.
- 자동 커밋 세션에서 스크립트가 `BEGIN TRAN`으로 트랜잭션을 열고 끝내지 않으면: 아래 확인 결과 참고.
- 가져오기·복사의 넣기 트랜잭션(커밋 간격)은 작업 설정대로다(손 실행과 같음).

## 실행 중일 때만, 놓친 실행, 겹침, 서머타임

- 메인 창이 열릴 때(작업 공간과 연결을 읽은 뒤) 시작하고, 닫힐 때 멈춘다. 끝내기 전의 "실행 중인 작업" 질문([connection-lifecycle.md](connection-lifecycle.md)
  "끝낼 때 실행 중인 작업")에 예약 작업 Job("예약 작업 이름")이 나온다(시스템 Job이 아님). 멈추고 끝내면 실행이 취소되고 실행 기록에 "취소됨".
- Chromite가 꺼져 있던 동안의 시각은 몰아서 실행하지 않는다. 다음 실행 열에 마지막으로 놓친 시각을 "놓침 … (Chromite가 꺼져 있었음)"으로 보인다.
  일정을 저장하기 전의 시각은 놓친 것이 아니다(`since`).
- 같은 작업의 앞 실행이 아직 돌면 이번 실행은 건너뛰고 로그(앱 로그)와 다음 실행 열에 "건너뜀"으로 남긴다.
- 시각은 이 PC의 지역 시각이다(쿼리 예약과 같음). 서머타임: 앞으로 당기는 날 없는 시각(예: 02:30)은 지나간 것으로 보아 시계가 넘어간 뒤 첫 확인에서 한 번 돈다.
  뒤로 물리는 날 같은 시각이 두 번 오지만 한 번 돈 뒤 다음 시각은 다음 날이라 한 번만 돈다. 간격 예약은 벽시계로 더하므로 물리는 날 한 번 최대 1시간 늦어지고,
  당기는 날 한 번 일찍 돈다. 한국은 서머타임이 없다.

## upstream 대비 변경점

| 항목 | DBeaver | Chromite |
|---|---|---|
| 작업 예약 | CE에는 없음. 모델 인터페이스 `DBTScheduler`·`DBTTaskScheduleConfiguration`·`DBTTaskScheduleInfo`와 `com.seizia.chromite.task` 확장점의 `scheduler`만 있고 구현은 상용판(Windows 작업 스케줄러, cron) | 앱 안 스케줄러. `DBTScheduler`는 구현하지 않음: 설정 모델이 OS 스케줄러용(빈도·반복·월·최대 시간·외부 설정)이라 쿼리 예약의 시각 규칙 옆에 두 번째 표현이 생김. 일정은 작업 자신에 있어 작업을 지우면 함께 지워짐(`TaskManagerImpl.deleteTaskConfiguration`의 스케줄러 호출은 구현이 없어 쓰이지 않음) |
| 예약 실행의 질문 | OS 스케줄러가 헤드리스로 실행, 확인은 `isHeadlessMode`면 예로 넘김(`DBTaskUtils.confirmTask`) | 묻지도 대신 답하지도 않음. 매번 묻는 확인은 켤 때 한 번 받고 저장 |

## OS 스케줄러로 한다면 (기각된 선택지, 참고)

Chromite가 꺼져 있어도 돌게 하려면: (1) 헤드리스 실행기(작업 공간을 열고 한 작업을 돌리고 끝나는 명령줄 진입점, SWT의 `-task` 같은 것), (2) OS 등록·해제
(Windows 작업 스케줄러 `schtasks`/COM, macOS `launchd` plist, Linux cron/systemd timer)과 실행 사용자 권한, (3) 헤드리스 프로세스가 OS 보안 저장소의 연결 암호를
읽는 경로(로그온하지 않은 세션에서는 못 읽을 수 있음), (4) 앱과 헤드리스가 같은 작업 공간을 동시에 여는 잠금, (5) 결과를 앱의 실행 기록·알림으로 되돌리는 경로,
(6) 앱 업데이트·제거 때 등록 정리가 필요하다. 2026-11-06 결정으로 하지 않는다.

## 확인 (2026-11-12)

이 브랜치의 빌드를 개인 작업 공간·설정·데이터 폴더(`D:\Tools\_scratch\task-scheduling\`)로 실행하고, 그 프로세스에 붙인 스크래치 시험 도구가 모델 API와 Swing 부품을
직접 불렀다(OS 입력 없음, 화면은 앱 창을 그 창 자신에게 그려 저장). 시험 DB는 GCP 시험 VM의 PostgreSQL 17·SQL Server 2022에 만든 `chr_task_scheduling`이고 끝나고 지웠다.
클라이언트 도구: `pg_dump`·`psql`은 `D:\Tools\pgsql-17\pgsql\bin`(로컬 클라이언트), `mysqldump`·`sqlplus`는 이 PC에 없어 실행하지 않았다.
증거: `D:\Tools\_scratch\task-scheduling\`(`logs\ops.log`, `logs\step*.txt`, `shots\`).

| # | 확인 | 결과 |
|---|---|---|
| 1 | SQL Server `dbo.src` → CSV(`ms_src_${timestamp}`), 매분 | 19:19~19:29 매분 성공, 파일 `ms_src_202611121919`…`…1923` 생김, 마지막 결과 "성공(예약)", 다음 실행 열 "11-12 19:2x", 실행 기록의 시작한 것 `Chromite scheduler` |
| 2 | PostgreSQL `public.src` → CSV, 매분 | 같음(`pg_src_…`) |
| 3 | pg_dump 백업, 파일 이름 `pgdump-${database}-${timestamp}.backup`, 일정 대화상자에서 매분으로 켬 | 19:19~19:23 다섯 번 성공, 승인 질문 없음. 로그 "Run without asking (the schedule was turned on for this task): …pg_dump.exe … --file …pgdump-chr_task_scheduling-202611121923.backup". 대화상자에 명령줄과 안내 줄(`shots\ko-schedule-pg-backup.png`) |
| 4 | psql 복원(평문) 일정 켜기 | 확인을 누르면 "일정 켜기"에 모델의 복원 확인 문구(데이터베이스 … 복원하려고 합니다), 켜기 → 19:47~19:55 매분 성공, 질문 없음, 로그 "The task's confirmation was given when its schedule was turned on". 둘째 세션 `restored` 행이 실행마다 늘어남 |
| 5 | 테이블 복사(PostgreSQL `public.src` → `tgt.src`, 넣기 전 비우기) 일정 켜기 | "일정 켜기"에 "넣기 전에 대상 테이블을 비웁니다." → 켜기 → 19:55 성공, 둘째 세션 `tgt.src` = 1,2,3(미리 넣은 99 없음) |
| 6 | 5의 작업을 고침(비우기 끔) | 일정 열 "매분(실행 안 함: 일정을 켠 뒤 작업이 바뀌어 …다시 켜십시오.)", 다음 실행 없음, 실행하지 않음 |
| 7 | 내보내기 "파일이 있으면 묻기", 같은 이름 파일이 있음 | 실패 "A scheduled run can't ask, decide it in a run by hand: "대상 데이터 파일이 이미 있음" fixed_name 파일이 이미 있습니다", 질문 창 없음. 기존 파일의 수정 시각 그대로(19:29:10, 19:50·19:51·19:52 실패 뒤). 고치기 전 빌드에서는 실패한 실행 뒤 기존 파일이 지워졌다(아래 "확인 중 고친 것") |
| 8 | 암호를 저장하지 않은 연결의 내보내기 | 실패 "'PG nopass' 연결의 암호가 저장되어 있지 않습니다. 예약 실행은 암호를 묻지 않습니다.", 질문 창 없음, 파일 없음. 같은 작업을 시험 도구가 손으로 만들려고 연결하자 손 실행의 인증 창이 떴다(비교) |
| 9 | 수동 커밋 SQL Server 연결의 SQL 스크립트 | 실패 "'MS manual' 연결은 수동 커밋이라 …", 스크립트 실행 안 함. 둘째 세션: `dbo.counter` 그대로, 세션 `open_transaction_count` 0 |
| 10 | 같은 연결, 작업의 "문장마다 커밋" 켬 | 매분 성공, 둘째 세션에서 행이 보임(커밋됨), 열린 트랜잭션 0 |
| 11 | 자동 커밋 SQL Server의 SQL 스크립트(INSERT) | 매분 성공, 둘째 세션에서 행이 보임, 열린 트랜잭션 0 |
| 12 | 자동 커밋 세션에서 `BEGIN TRAN; INSERT` 스크립트(끝내지 않음) | 첫 예약 실행 뒤 실패 알림 "예약 실행이 'MS tasks' 연결의 세션에 트랜잭션을 열어 둔 채 끝났습니다 … 커밋도 롤백도 하지 않았습니다"(로그에도), 다음 실행들은 시작 전 실패 "…커밋하지 않은 트랜잭션이 있습니다". 둘째 세션 `open_transaction_count` 1. 같은 스크립트를 손으로 실행하면 묻지 않고 열어 둔다(카운트 증가, 질문 창 없음): [swing-database-tasks.md](swing-database-tasks.md) "남긴 것" |
| 13 | 겹침: `WAITFOR DELAY '00:01:30'` 스크립트 매분 | 19:25 실행이 90초, 19:26은 건너뜀(앱 로그 "Scheduled task 'ms-slow': 건너뜀: 이전 실행이 아직 진행 중입니다", 다음 실행 열 "실행 중 · 건너뜀 11-12 20:00: …"). 같은 연결의 다른 SQL 스크립트 작업도 주 세션을 기다리다 건너뜀(같은 세션을 쓰므로) |
| 14 | 사용 안 함 / 다시 사용 | 일정 열 "매분(사용 안 함)", 실행 안 함 → 다시 켜면 "매분", 다음 실행 생김 |
| 15 | 이 컴퓨터에서 끔 | "매분(이 컴퓨터에서 안 씀)", 다음 실행 없음, 실행 안 함 |
| 16 | 일정 제거 | 일정·다음 실행 열이 빔, `tasks.json`에서 `schedule` 빠짐 |
| 17 | 연결이 이 PC에 없는 작업 | "매분(실행 안 함: 작업의 연결이 이 컴퓨터에 없습니다.)", 실행 기록 없음 |
| 18 | 다시 시작 | 앱을 끄고(19:32) 19:44에 다시 켬 → 일정이 돌아옴, 몰아서 실행하지 않음, 다음 실행 열 "11-12 19:45 · 놓침 11-12 19:44(Chromite가 꺼져 있었음)" |
| 19 | 실행 중에 끝내기 | "실행 중인 작업" 질문에 "예약 작업 ms-slow"와 "데이터베이스 작업"(`shots\ko-exit-running-jobs.png`), 멈추고 끝내기 → 작업 로그 "Task 'ms-slow' … canceled after 78557 ms", 앱이 끝남. `WAITFOR` 뒤의 INSERT는 실행되지 않음(둘째 세션 행 수 그대로) |
| 20 | 연결 끊은 뒤 | PostgreSQL 연결을 끊은 뒤 다음 예약 실행(복원·복사)이 저장된 암호로 다시 연결해 성공. 끊는 바로 그 순간 시작한 실행 하나는 "Cannot find database for restoring"으로 실패(19:53) |
| 21 | Query Library 예약(매분, 조건 rows > 0) | 같은 스케줄러로 19:49~19:52 매분 실행, 기록 `ALERT 3개 행; 조건 충족: 행 수 > 0` |
| 22 | 일정 대화상자의 경고 | 수동 커밋 연결의 스크립트 작업: "이 작업은 다음 때문에 예약 실행이 실패할 수 있습니다: - 'MS manual' 연결은 수동 커밋이라 …"(`shots\ko-schedule-warning.png`) |
| 22b | 일정 대화상자·"일정 켜기"·목록, 한국어·영어, 라이트·다크(150%) | 배율을 넣어 그린 캡처 `shots\ko-light-schedule-backup.png`, `ko-light-scaled-warning.png`, `ko-light-confirm-restore.png`, `ko-light-main.png`, `en-dark-*.png`(`ko-dark-*`, `en-light-*`는 배율 없이 그려 글 끝이 잘려 보임): 명령줄과 `# …pgdump-${database}-${timestamp}.backup` 줄, 경고, 복원 확인 문구(두 줄로 시작), 목록 아래 "사용 중인 일정 1개. 다음: …" |
| 23 | 인터넷 | 드라이버는 이미 있고, 내려받기 요청은 가드가 거절한다(코드 경로). 시험 중 바깥으로 나간 요청 없음(로컬 DB 터널과 VM만) |

확인 중 고친 것: 일정 대화상자의 안내 글과 명령줄을 고정 폭으로 줄 바꿈(창을 맞추기 전에 높이를 알게, 같은 최소 크기. 처음 판은 창 높이가 모자라 글이 잘렸다.
화면 배율을 넣지 않고 그린 캡처에서 글이 오른쪽에서 잘려 보였으나 배율을 넣어 그리면 잘리지 않음을 확인했다), 명령줄 미리 보기가 로컬 클라이언트를 몰라 실패하던 것,
출력 파일 이름 형식 줄이 나오지 않던 것(설정 키에 `export.` 앞말), "일정 켜기" 첫 줄이 잘리던 것(줄 바꿈), 이미 열려 있던 트랜잭션을 "이 실행이 열어 둠"으로 알리던 것,
**"파일이 이미 있음"을 취소하면 기존 파일이 지워지던 것**(모델 `StreamTransferConsumer`: 출력 목록에 먼저 넣고 물어서, 실패한 내보내기 정리가 쓰지도 않은 파일을 지움.
손 실행에서 취소해도 같았다. 질문이 끝나지 않으면 목록에서 뺌).

범위 밖에서 발견(고치지 않음): SQL Server 연결을 새로 연 앱에서 저장된 작업이 `dbo` 스키마의 테이블을 찾지 못한다("Child object 'src' not found in container
chr_task_scheduling.dbo", 손 실행도 같음, 스키마 객체 ID가 0). 같은 연결에서 스키마를 새로 고치면 찾는다. 첫 앱 실행에서는 시험 도구가 새로 고친 뒤라 성공했다.

회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: Query Library 예약 실행(스케줄러를 나눔), 저장된 작업의 손 실행(`TaskRunJob`의 시작한 것·사전 확인,
기본 동작은 같음), 모델이 묻는 모든 질문(가드는 예약 실행 Job에서만 동작), 데이터 내보내기의 "파일이 있음" 취소, 설정 내보내기(상태 키 하나 더).

## 남긴 것

- 활성 프로젝트가 아닌 프로젝트의 작업은 예약하지 않는다.
- 예약 실행 기록을 여러 개 보는 화면은 없다(마지막 실행과 로그만, 손 실행과 같음).

## 변경 이력

- 2026-11-12 최초 작성(`chromite/task-scheduling`, 앱 변경 #104): 하나의 스케줄러, 작업과 함께 저장, 일정 대화상자, 무인 실행 가드, 동의 지문, 확인 결과.
