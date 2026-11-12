# 저장된 작업의 예약 실행 (앱 변경 #104)

## 목적

저장된 작업(백업, 스크립트 실행, 데이터 내보내기 등, [swing-database-tasks.md](swing-database-tasks.md))에 예약을 붙여, Chromite가 실행 중일 때
정해진 시각에 저절로 돌게 한다. 로드맵 12번 "범용 작업 예약"을 2026-11-12에 앞당겼다.

## 저장

- 예약은 작업과 함께 프로젝트의 `tasks.json`에 저장한다. 작업 항목의 `state`(처리기 설정) 옆에 `schedule` 객체를 둔다.

  ```json
  "schedule": { "daily": "02:00", "enabled": "true", "since": "2026-11-12T14:05" }
  ```

  키는 쿼리 파일의 `-- @schedule` 블록과 같다(`every: 30m|2h`, `daily: hh:mm`, `weekdays: hh:mm`, `enabled`). `since`는 예약을 저장한 시각이고
  "놓침" 표시에만 쓴다(그 전의 시각은 놓친 것이 아니다). 예약이 없으면 `schedule`을 쓰지 않는다.
- `state` 안에 두지 않은 이유: 작업 편집 화면 세 곳이 다시 저장할 때 `state` 전체를 바꾼다(`DTSavedTasks.java:352`, `NativeBackupRestore.java:496·536`,
  `DataTransferWizard.java:670`). 따로 두면 작업을 고쳐 저장해도 예약이 남는다. 모델: `DBTTask.getSchedule`/`setSchedule`, `TaskImpl`,
  `TaskManagerImpl`(읽기·쓰기), `TaskConstants.TAG_SCHEDULE`. DBeaver는 모르는 키를 무시하므로 SWT에서 같은 파일을 읽어도 문제없다.
- 작업을 지우면 예약도 함께 지워진다(같은 항목).

## 변경 이력

- 2026-11-12 최초 작성(`chromite/task-scheduling`): 저장 형식.
