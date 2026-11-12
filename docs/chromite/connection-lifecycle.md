# 연결 수명 주기: 끊기·다시 연결·끝내기와 남은 조용히 끊긴 항목

## 목적

SWT 대조([swt-parity-audit.md](swt-parity-audit.md))의 4순위 B2 묶음이다. 연결을 끊고 다시 열고 앱을 끝내는 경로에서 SWT에 있던 기능을 옮기고,
화면 없이 멈춰 있던(조용히 끊김) 남은 항목을 고친다. 모든 경로는 사용자 규칙 "트랜잭션 안전"을 따른다: 세션을 닫거나 다시 여는 경로는
커밋하지 않은 트랜잭션을 먼저 찾아 커밋 / 롤백 / 취소를 묻고, 묻지 않고 커밋하거나 롤백하지 않는다.

| 대조 항목 | 내용 | 절 |
|---|---|---|
| A-S10, K4 (A-platform §1 S10) | H2 초기화 SQL(URL의 `INIT=`, 초기화 쿼리) 실행 전 승인 | "초기화 SQL과 SQLite 확장 승인" |
| A-S11, K5 (A-platform §1 S11) | SQLite 네이티브 확장 불러오기 승인 | 같은 절 |
| A-C7 | 모두 끊기, 다른 연결 끊기, 서버 그룹(폴더)·프로젝트 단위 끊기 | "여러 연결 끊기" |
| A-C8 | 무효화·다시 연결(연결을 끊지 않고 세션을 다시 엶) | "무효화·다시 연결" |
| A-C15, A-U3 | 연결 끊김 대화상자, 드라이버 설정 오류 안내, 드라이버 라이선스 오류 | "연결 끊김·드라이버 오류 대화상자" |
| A-S2 (A-platform §5.3) | 끝낼 때 실행 중인 작업 | "끝낼 때 실행 중인 작업" |
| B-S16 | 스마트 커밋 | "스마트 커밋" |
| A-C4 | 트랜잭션 로그의 지난 트랜잭션 | "트랜잭션 로그의 지난 트랜잭션" |
| A-S8 (A-platform §1 S8) | 시스템 절전 때 연결 닫기 | "절전" |
| (규칙) | 모든 닫기 경로가 트랜잭션 질문을 거치는지 | "닫기 경로 전수 점검" |

범위 밖: 실행 중 자동/수동 커밋 전환(B-S18)은 사용자 결정이 필요해 손대지 않았다. 2026-11-08 결정 뒤 [transactions.md](transactions.md) "커밋 방식 전환"에서 더했다.

## 구조

| 부분 | 번들 | 클래스 |
|---|---|---|
| 초기화 SQL 승인 서비스 | `ui.swing` | `SwingConnectionInitService`(`UIServiceConnectionInit`, `plugin.xml` 서비스 등록) |
| SQLite 확장 승인 서비스 | `ui.swing` | `SwingSqliteExtensionConfirmation`(`SQLiteExtensionConfirmation`, `plugin.xml` 서비스 등록, `MANIFEST.MF`에 `ext.sqlite`) |
| 승인 대화상자 | `ui.swing` | `ExecutionApprovalDialog`(셸 명령 승인과 같은 모양, `SwingShellCommandsService.createNote/createValue` 공용) |
| 여러 연결 끊기, 무효화·다시 연결 | `ui.swing` | `ConnectionCommands.disconnect(Component, Collection)`, `ConnectionCommands.invalidate` |
| 트랜잭션 질문 | `ui.swing` | `ExitTransactionCheck.confirmDisconnect(Component, Collection)`, `confirmReconnect` |
| 메뉴 | `ui.swing` | `ObjectExplorerPanel`(다른 연결 끊기, 모두 연결 끊기, 무효화/다시 연결) |
| 다시 열 연결 범위 | `model` | `InvalidateJob.collectDependentDataSources`(공개로 바꿈) |
| DB 오류 대화상자 | `ui.swing` | `DatabaseErrorDialogs`, `SwingPlatformUI.showError`, `SwingConnectionService`(연결 실패) |
| 끝낼 때 실행 중인 작업 | `ui.swing` | `RunningJobsCheck`, `MainFrame.requestExit` |
| 스마트 커밋 | `ui.swing` | `SmartCommit`, `SqlEditorPanel`(`QueryHandler.isSmartAutoCommit`, `QueryListener`, 상태 줄의 커밋 방식 다시 읽기), `ConnectionTypesPage` |
| 지난 트랜잭션 | `model`, `ui.swing` | `QMUtils.getRecentTransactionStatements`, `TransactionLogDialog` |
| 절전 | `model`, `ui.swing` | `DataSourceMonitorJob`(`isInUse`), `ChromiteSwingApplication.installDesktopHandlers` |
| 문구 | `ui.swing` | `LifecycleMessages`(영어·한국어) |

## 초기화 SQL과 SQLite 확장 승인

SWT 제품은 두 서비스를 `core`(`UIServiceConnectionInitImpl`)와 `ext.sqlite.ui`(`SQLiteExtensionConfirmationImpl`)에 두었다. Swing 제품에는 두 번들이 없어
모델이 서비스를 찾지 못했고, 서비스가 없으면 승인 안 됨으로 보아 연결을 거부했다(안전한 쪽으로 실패). 그래서 H2 연결에 초기화 SQL을 넣거나
SQLite에서 확장 불러오기를 켜면 연결이 "not approved. Connection cancelled"로 끝났다. 두 서비스를 Swing에 등록했다.

- 대화상자(`ExecutionApprovalDialog`): 셸 명령 승인(#99, [shell-command-approval.md](shell-command-approval.md))과 같은 모양이다. 설명, 연결·URL, 실행할 내용을
  고칠 수 없는 고정폭 칸으로 그대로, 허용하면 무엇을 기억하는지, 취소하면 무엇이 멈추는지. 단추는 **허용하고 연결** / **취소**이고 기본 단추와
  처음 초점은 취소다(Enter나 창 닫기로 실행되지 않게).
- H2(`SwingConnectionInitService`): 연결 Job이 `H2DataSource.validateConnectionParameters`에서 부른다. URL의 `INIT=`는 스크립트마다, 연결의 초기화 쿼리는
  한 번에 묻는다(모델의 순서 그대로). 허용하면 SWT처럼 그 연결(프로젝트 경로·연결 ID·드라이버)·URL·SQL을 SHA-256 키로 전역 설정 파일
  `confirmed_connection_init_scripts.json`에 기록한다. 파일 이름과 키 계산이 SWT와 같아 SWT에서 허용한 것은 다시 묻지 않는다. SQL이나 URL이 바뀌면 다시 묻는다.
  헤드리스 모드에서는 묻지 않고 거부한다.
- SQLite(`SwingSqliteExtensionConfirmation`): 연결 설정에 확장 불러오기가 켜져 있으면(`enable_load_extension`, 확장 라이브러리) 연결 Job이 묻는다.
  내용은 SWT와 같다: 연결, URL, 라이브러리, 초기화 SQL. 승인 기억은 모델(`SQLiteExtensionPermissionStore`, 설정 지문)이 하고 설정이 바뀌면 다시 묻는다.
- 취소하면 두 경우 모두 모델이 연결을 멈추고 아무것도 실행하지 않는다. 오류 창에 모델 문구("… not approved. Connection cancelled")가 나온다.
- SWT의 오류 결과 동작 "확장 설정"(`resultset.error`, 결과의 `load_extension` 오류에서 확장을 켜기)은 옮기지 않았다. Swing 결과 창에는 그 확장점이 없다.

## 여러 연결 끊기

SWT의 모두 끊기(`DataSourceDisconnectAllHandler`), 다른 연결 끊기(`…OtherHandler`), 프로젝트 끊기(`…ProjectHandler`), 폴더 끊기(`FolderDisconnectHandler`)를 옮겼다.

- 자리(2026-11-11부터, 앱 변경 #48): Object Explorer의 연결 메뉴. **연결 끊기** 아래에 **다른 연결 끊기**, **모두 연결 끊기**. 대상은 프로젝트의
  연결된 연결 모두(Object Explorer에 보이지 않는 것 포함)라 모두 끊기가 SWT의 프로젝트 끊기다. 끊을 연결이 없으면 메뉴가 꺼진다. 끊은 서버는
  **연결 끊기**처럼 Object Explorer에서 빠진다(SSMS).
- 전에는 Registered Servers 창의 메뉴(서버, 그룹, 빈 곳)에 있었다. 사용자 요청 "등록된 서버는 커넥션을 맺는 게 아니고 서버 정보만 등록해 놓는 것이다"에 따라
  그 창의 연결·끊기 메뉴를 모두 뺐다([swing-registered-servers.md](swing-registered-servers.md) "서버 정보만"). Object Explorer에는 그룹이 없어
  그룹 연결 끊기(SWT `FolderDisconnectHandler`)는 옮기지 않고 없앴다(문구 `disconnect_group`도 지움).
- 트랜잭션: SWT는 연결마다 물었다. Chromite는 끊을 연결 모두의 열린 트랜잭션을 **한 질문**으로 묻는다(`ExitTransactionCheck.confirmDisconnect(Component, Collection)`,
  끝낼 때 질문과 같은 찾기·끝내기, 끊긴 세션은 버리기). 취소하면 아무 연결도 끊지 않는다. 연결·끊기 Job이 이미 있는 연결은 빼고 묻는다(하나 끊기와 같음).

## 무효화·다시 연결

SWT `DataSourceInvalidateHandler`(연결 메뉴 Invalidate/Reconnect)를 옮겼다. 연결을 끊지 않고 모든 세션(주, 메타데이터, 쿼리 창마다의 세션, 도구 세션)을
다시 연다. 쿼리 창과 Object Explorer는 그대로다. 기존 **다시 연결**은 끊은 뒤 연결하므로 쿼리 창의 세션이 닫히고 새로 열린다.

- 자리: Object Explorer 연결 메뉴의 **무효화/다시 연결**(다시 연결 아래). 연결되어 있지 않으면 연결한다.
- 범위: 다시 열기는 그 연결과 같은 네트워크 처리기(공유 SSH 터널)를 쓰는 연결까지 한다(`InvalidateJob.collectDependentDataSources`, 이를 위해 공개로 바꿈).
- 트랜잭션: 다시 열면 옛 JDBC 연결이 닫혀 열린 트랜잭션이 끝난다. 그래서 그 범위 모두의 열린 트랜잭션을 먼저 묻는다(`ExitTransactionCheck.confirmReconnect`).
  SWT도 `confirmInvalidate`에서 연결마다 `checkAndCloseActiveTransaction`을 물었다. 취소하면 아무것도 하지 않는다.
- 실행: 모델의 `InvalidateJob`(알림 처리기 `DefaultInvalidationFeedbackHandler`: 성공하면 "다시 연결" 정보 알림). 세션 하나라도 다시 열지 못하면
  실패 알림 대신 아래 연결 끊김 대화상자(다시 연결 실패 모양)를 띄운다(SWT와 같음).
- 모델 버그 고침(`InvalidateJob`): 다시 열기는 세 단계(닫기, 다시 열기, 뒤처리)로 하는데 결과를 마지막 단계만 셌다. 다시 열기(INVALIDATE)가 실패해도 뒤처리
  (AFTER_INVALIDATE)는 늘 성공하므로 "데이터 원본 연결을 다시 설정했습니다. 활성 연결 수: 3/3"이 뜨고 호출자도 성공으로 보았다(실행해 보고 찾음: 프록시를 끊은 PostgreSQL에서
  세 문맥 모두 "연결이 거부되었습니다"인데 3/3). 이제 단계마다 같은 자리(처리기·문맥)의 결과를 합쳐 한 단계라도 실패하면 실패로 센다. 같은 함수를 쓰는 연결 유지 핑의
  실패 횟수, 실행 복구의 다시 연결 알림, 사용자가 고른 다시 연결(`TransactionRecoveryGuard.reconnect`)도 이제 실패를 실패로 본다.

## 연결 끊김·드라이버 오류 대화상자

SWT `DesktopUI.showDatabaseError`는 모델이 `showError`로 넘긴 오류의 종류(`DBExecUtils.discoverErrorType`)를 보고 따로 된 대화상자를 띄웠다. Swing은 모두 일반
오류 창이었다. `SwingPlatformUI.showError`가 일반 오류 창 전에 `DatabaseErrorDialogs.show`를 부른다.

| 오류 | 조건 | 대화상자 | 단추(기본은 마지막) |
|---|---|---|---|
| 드라이버 라이선스 | 원인 사슬에 `DBCDriverLicenseException` | "연결 오류: {드라이버}의 드라이버 라이선스가 활성화되어 있지 않습니다" + 오류 상세 | 확인 |
| 연결 끊김 | `CONNECTION_LOST`이고 연결이 열려 있음(연결 중 오류는 일반 오류 창) | 제목 "연결 끊김: {연결}", 원래 문구, 안내, 오류 상세(접힘)·복사 | **다시 연결** / 닫기 |
| 다시 연결 실패 | 무효화·다시 연결에서 세션을 다시 열지 못함 | 같은 제목, "다시 열지 못했습니다" | **연결 끊기** / **다시 시도** / 닫기 |
| 드라이버 클래스 없음 | `DRIVER_CLASS_MISSING`(연결 중 `ClassNotFoundException`) | "{드라이버}의 드라이버 클래스를 찾지 못했습니다 …" + 오류 상세 | **드라이버 편집** / 닫기 |

- 다시 연결과 다시 시도는 위 무효화·다시 연결로 가서 열린 트랜잭션을 먼저 묻는다. 연결 끊기는 `ConnectionCommands.disconnect`로 가서 같은 질문을 거친다.
- 기본 단추: 세션을 바꾸는 동작이라 닫기가 기본이다(사용자 규칙 "실행 확인의 기본 단추"). SWT는 다시 연결·끊기가 기본이었고 다시 연결 실패 창을 닫으면 끊었다.
  Chromite는 닫으면 아무것도 하지 않는다.
- 드라이버 편집은 Driver Manager의 편집 대화상자(`DriverEditorDialog.openEdit`)다.
- 연결 실패: 연결 Job의 상태(`GeneralUtils.makeExceptionStatus`)에는 데이터 원본을 가진 연결 예외(`DBCConnectException`)가 빠지고 그 원인만 남는다.
  그래서 오류만 보고는 드라이버 클래스 없음을 알 수 없다(SWT도 같아서 이 경우 일반 오류 창이었다, 실행해 보고 찾음). 연결 서비스(`SwingConnectionService`)가 연결을 함께 넘겨
  `DatabaseErrorDialogs.showConnectError`가 상태의 예외 사슬에서 `ClassNotFoundException`·`DBCDriverLicenseException`을 찾는다.
- 쿼리 창의 실행 오류는 이 경로를 지나지 않는다. Messages 탭에 쓰고, 끊긴 연결은 실행 복구와 그 알림이 맡는다([transactions.md](transactions.md) "오류 복구와 열린 트랜잭션").
  이 대화상자가 뜨는 곳은 모델이 `showError`를 부르는 곳이다: Object Explorer 읽기 오류, 연결 Job 오류, 데이터 전송·작업 오류 등.
- 드라이버 라이선스 오류를 만드는 코드(`new DBCDriverLicenseException`)는 지금 저장소에 없다(검색 0). 상용 드라이버 쪽에서 올 수 있어 SWT처럼 처리만 두었다.

## 끝낼 때 실행 중인 작업

SWT는 끝낼 때 작업 관리자의 실행 중인 작업을 취소할지 물었다(`ApplicationWorkbenchAdvisor.cancelRunningTasks`). Swing은 묻지 않고 닫았고, 실행 중인 쿼리·내보내기는
플랫폼 종료가 끊었다.

- 순서(`MainFrame.requestExit`): 저장 질문 → 도구 문서(디버거) → **실행 중인 작업** → 열린 트랜잭션 질문 → 닫기. 트랜잭션 질문보다 먼저인 이유: 문장이 멈춰야
  커밋·롤백을 할 수 있고, 멈춘 문장이 남긴 트랜잭션도 그 질문에 나온다.
- 대상(`RunningJobsCheck`): 실행 중이거나 곧 실행될(대기) Job 중 시스템 Job이 아닌 것(연결 감시, 자동 완성 분석 같은 내부 작업은 시스템 Job)과 현재 프로젝트
  작업 관리자의 실행 중인 작업. Job 이름으로 최대 10개를 보이고 나머지는 "…외 N개".
- 질문: "다음 작업이 아직 실행 중입니다 …" 단추 **기다린 뒤 끝내기** / **멈추고 끝내기** / **취소**. 기본 단추는 취소이고 취소하면 앱을 닫지 않으며 작업도 그대로 실행된다.
  - 기다린 뒤 끝내기: 진행 대화상자에서 모두 끝날 때까지 기다린 뒤 끝내기를 계속한다(그다음 트랜잭션 질문). 기다리는 중에 취소하면 앱을 닫지 않는다.
  - 멈추고 끝내기: Job을 취소하고(문장 취소) 작업 관리자의 작업도 취소한다. 멈출 때까지 최대 10초 기다린 뒤 끝내기를 계속한다.
- SWT의 "종료 확인" 질문(`CONFIRM_EXIT`, 끝낼지 한 번 더 묻기)은 옮기지 않았다(지시서 범위는 실행 중인 작업).

## 스마트 커밋

자동 커밋 연결에서 쿼리 창의 문장이 데이터를 바꾸면 그 세션을 수동 커밋으로 바꿔, 변경을 아직 롤백할 수 있게 한다. 커밋·롤백 뒤에는 자동 커밋으로 돌아간다.
실행 엔진은 처리기의 `isSmartAutoCommit()`이 참일 때만 문장마다 이것을 하는데(`SQLQueryExecuteJob` → `DBExecUtils.checkSmartAutoCommit`), Swing 쿼리 창은 켜지 않아
연결 유형의 `smartCommit`이 저장만 되고 효과가 없었다(B-S16).

- 켜기: 연결의 설정 저장소에 `transaction.smart.commit`이 있으면 그 값, 없으면 연결 유형의 값(SWT `SQLEditor.isSmartAutoCommit`과 같음).
  화면은 Options > Connection Types의 트랜잭션 묶음 **스마트 커밋**, **커밋하거나 롤백한 뒤 자동 커밋으로 돌아가기**(앞 칸이 켜졌을 때만 켬). 모든 기본 유형은 꺼짐이다(업스트림 기본값).
- 바꾸는 판단은 엔진 그대로다: 자동 커밋이고 방언이 트랜잭션을 바꾸는 문장으로 보면(`SQLDialect.isTransactionModifyingQuery`) 실행 전에 수동 커밋으로 바꾼다.
  문장이 실패하면 엔진이 자동 커밋으로 되돌린다. 수동 커밋으로 바꾸는 것은 잃을 것이 없다(자동 커밋 세션에는 열린 트랜잭션이 없다).
- 바뀐 뒤: 상태 줄은 수동 커밋과 열린 트랜잭션을 보이고(되돌아가면 자동 커밋. 상태 줄은 1초마다 쿼리 관리자가 본 커밋 방식을 보고 바뀌면 다시 읽는다.
  전에는 실행·창 바꾸기 때만 읽어, 실행 밖에서 바뀐 커밋 방식이 계속 옛 값으로 보였다), 유휴 알림·열린 트랜잭션 목록·끝내기 질문이 다른 수동 커밋 세션과 똑같이 그 트랜잭션을 본다.
- 되돌리기(`SmartCommit`): 쿼리 창 문장 전에 자동 커밋이었고 끝난 뒤 수동 커밋이면 그 세션을 기억한다. 쿼리 관리자 처리기가 그 세션의 커밋·롤백(Query 메뉴,
  상태 줄, 편집기에 친 `COMMIT`, 유휴 알림, 끝내기 질문 등 모든 경로)을 받으면 Job에서 자동 커밋으로 바꾼다. 다만 그 세션에 열린 트랜잭션이 있거나 실행 중이면
  바꾸지 않고 다음 커밋·롤백을 기다린다. 자동 커밋으로 바꾸면 JDBC가 열린 트랜잭션을 묻지 않고 커밋하기 때문이다(트랜잭션 안전 규칙).
- 업스트림과 다른 점: 업스트림 `recoverSmartCommit`은 켜져 있으면 **모든** 커밋·롤백 뒤 자동 커밋으로 바꿔서, 처음부터 수동 커밋인 연결도 커밋 한 번에 자동 커밋이
  되었다. Chromite는 스마트 커밋이 바꾼 세션만 되돌린다. 결과 표 저장이 잠시 자동 커밋을 끄는 것도 스마트 커밋으로 보지 않는다.
- 연결별 값(연결 대화상자 Options > Settings)은 이 작업에서 더하지 않았다(그 화면은 [swing-options.md](swing-options.md)의 다른 작업이 맡는다). 저장된 연결별 값은 읽는다.

## 트랜잭션 로그의 지난 트랜잭션

SWT 트랜잭션 로그의 "Show previous transactions"를 옮겼다(A-C4의 남은 것).

- 트랜잭션 로그 위쪽의 **지난 트랜잭션 보이기**. 켜면 그 세션의 최근 트랜잭션 20개(현재 것 포함)의 문장을 보이고, **트랜잭션** 열에 각 문장의 트랜잭션이
  어떻게 끝났는지 적는다: "커밋함 12:01:05", "롤백함 12:01:05", "열림".
- 문장 기준은 현재 트랜잭션 로그·상태 줄과 같다(`QMUtils.isUserStatement`: 메타데이터 읽기·내부 작업 제외). 자동 커밋으로 실행한 문장은 트랜잭션이 없어 넣지 않는다.
- 쿼리 관리자가 메모리에 둔 그 세션의 기록만 읽는다(`QMUtils.getRecentTransactionStatements`, 최근 문장 5000개까지). 다시 연결하거나 다시 시작하면 그 전 기록은 없다.
- 커밋 방식을 바꾸면 쿼리 관리자는 그때까지의 트랜잭션을 커밋으로 기록한다(JDBC도 수동 → 자동 전환에서 커밋한다). 그래서 "커밋함"으로 보인다.
- "모든 문장(메타데이터 포함) 보이기"는 옮기지 않았다.

## 절전

`connection.closeOnSleep`(macOS 기본 켬)이면 시스템이 잠들 때 연결을 닫고, 깨어나면 연결을 다시 초기화한다(`DataSourceMonitorJob`). Swing에는 절전 알림을
받는 곳이 없어 닫기 경로가 한 번도 돌지 않았고(A-S8), 깨어난 뒤 다시 초기화는 감시 간격이 20초를 넘으면 돌았다.

- 절전 알림: `ChromiteSwingApplication.installDesktopHandlers`가 JDK의 `SystemSleepListener`를 등록해 `OperationSystemState.toggleSleepMode`를 부른다(SWT
  `ApplicationWorkbenchAdvisor.systemSleepListener`와 같음). JDK는 이 알림을 macOS에서만 준다(`Desktop.Action.APP_EVENT_SYSTEM_SLEEP`). Windows·Linux에서는 깨어남만
  감시 간격으로 알아챈다. 감시 Job은 3초마다 돌므로 잠들기 전에 한 번 돌지 못하면 닫기는 일어나지 않는다(업스트림과 같은 한계).
- 트랜잭션 안전: 잠들기 직전에는 물을 수 없고 깨어난 직후에는 사용자가 자리에 없을 수 있다. 그래서 묻는 대신 **쓰는 중인 연결은 건너뛴다**(`DataSourceMonitorJob.isInUse`,
  유휴 연결 닫기와 같은 기준: 어느 세션이든 열린 트랜잭션, 실행 중인 문장·Job·작업, Chromite Agent 세션). 전에는 절전 닫기가 모든 연결을 끊었고(끊기가 롤백),
  깨어난 뒤 다시 초기화가 모든 세션을 다시 열었다(옛 연결을 닫으며 롤백). 깨어난 뒤 다시 초기화는 같은 터널을 쓰는 연결까지 본다.
- 건너뛴 연결의 세션이 절전 동안 끊겼으면 다음 문장이나 연결 유지 핑의 복구가 다시 연결 전에 묻는다([transactions.md](transactions.md) "오류 복구와 열린 트랜잭션",
  "끊긴 세션의 쿼리 창").

## 닫기 경로 전수 점검

세션을 닫거나 다시 여는 모든 경로를 검색으로 찾아(`disconnect(`, `DisconnectJob`, `invalidateContext`, `invalidateDataSource`, `reconnect(`, `closeConnection`,
`openIsolatedContext`의 닫기) 트랜잭션 질문을 거치는지 확인했다(2026-11-08).

| 경로 | 위치 | 열린 트랜잭션 처리 | 상태 |
|---|---|---|---|
| 연결 끊기(Object Explorer, Registered Servers) | `ConnectionCommands.disconnect` | `ExitTransactionCheck.confirmDisconnect` | 이미 있음(#97) |
| 다시 연결 | `ConnectionCommands.reconnect` → 끊기 | 같음 | 이미 있음 |
| 연결 삭제 | `ConnectionCommands.delete` → 끊기 | 같음 | 이미 있음 |
| 연결 편집 뒤 다시 연결 | `ConnectionDialog` → `ConnectionCommands.reconnect` | 같음 | 이미 있음 |
| 모두·다른·그룹 연결 끊기 | `ConnectionCommands.disconnect(Component, Collection)` | 한 질문(`confirmDisconnect(Component, Collection)`) | 이번에 더함 |
| 무효화·다시 연결, 연결 끊김 대화상자의 다시 연결·다시 시도 | `ConnectionCommands.invalidate` | `confirmReconnect`(같은 터널의 연결 포함) | 이번에 더함 |
| 연결 끊김 대화상자의 연결 끊기 | `ConnectionCommands.disconnect` | `confirmDisconnect` | 이번에 더함 |
| 쿼리 창 닫기, DB 인스턴스 바꾸기, 연결 변경(연결 콤보는 2026-11-12에 없어지고 같은 날 명시적인 연결 변경 명령이 생김, [swing-frontend.md](swing-frontend.md) "쿼리 창의 연결 변경") | `SqlEditorPanel` | `ExitTransactionCheck.confirmEndSession` | 이미 있음 |
| Chromite 끝내기(창 닫기, File > Exit, 업데이트 다시 시작, macOS 끝내기) | `MainFrame.requestExit` | 실행 중인 작업 질문 뒤 `ExitTransactionCheck.confirm`. 그 뒤 플랫폼 종료(`DataSourceRegistry.closeConnections`)는 질문으로 끝난 뒤라 남은 트랜잭션이 없다 | 작업 질문을 이번에 더함 |
| 유휴 연결 닫기 | `DataSourceMonitorJob.endIdleConnection` | 쓰는 중이면 닫지 않음, 끊기 직전 다시 확인 | 이미 있음(`isInUse`로 뺌) |
| 유휴 트랜잭션 자동 롤백 | `EndIdleTransactionsJob` | 연결에서 사용자가 켰을 때만, 뒤에 알림(규칙의 예외) | 이미 있음 |
| 절전 때 연결 닫기 | `DataSourceMonitorJob.closeAllConnections` | 쓰는 중이면 건너뜀 | **고침**(전에는 묻지 않고 모두 끊음) |
| 깨어난 뒤 다시 초기화 | `DataSourceMonitorJob.invalidateSleptConnections` | 쓰는 중이면 건너뜀(같은 터널 포함) | **고침**(전에는 묻지 않고 모든 세션을 다시 엶) |
| 실행 복구(끊긴 연결, 중단된 트랜잭션), 연결 유지 핑 | `DBExecUtils.tryExecuteRecover`, `KeepAlivePingJob` | `TransactionRecoveryGuard`(멈추고 알림, 사용자가 고른 다시 연결) | 이미 있음 |
| 끊긴 격리 세션 다시 열기 | `TransactionRecoveryGuard.reopenLostSession` | 확인으로 끊긴 것이 확인된 세션만 | 이미 있음 |
| 무효화 뒤 모두 실패하면 끊기 | `InvalidateJob`(`disconnectOnFailure`) | 그 호출자가 먼저 거름(핑: `holdReconnect`, 깨어남: `isInUse`). 이때는 세션이 이미 닫혀 있다 | 해당 없음 |
| Chromite Agent `disconnect` 도구 | `ConnectionTools.disconnect` | 실행기의 `AIToolTransactionGuard`(커밋 / 롤백 / 취소, [ai-agent.md](ai-agent.md) "트랜잭션 안전") | 이미 있음 |
| Activity Monitor 창 닫기 | `AdminConnection.close` | 자기 격리 세션은 늘 자동 커밋이라 잃을 것이 없음(`keepAutoCommit`) | 해당 없음 |
| 연결 시험, 연결 실패 뒤 정리 | `ConnectionTestJob`, `ConnectJob` | 사용자 세션이 아님(임시 연결, 막 연 연결) | 해당 없음 |
| 탐색기 새로 고침의 다시 연결 | `DataSourceDescriptor.refreshObject` | JDBC 데이터 소스는 `DBPRefreshableObject`라 다시 연결하지 않음(JDBC 아닌 소스만) | 해당 없음 |
| 실행 전 다시 검증 | `SQLQueryExecuteJob`(`isInvalidateBeforeExecute`) | Swing은 켜지 않음(기본 `false`) | 해당 없음 |
| 결과 저장의 다시 열기 | `AbstractCommandContext.saveChanges` | 연결이 없을 때만 다시 엶(잃을 것 없음) | 해당 없음 |
| 프로젝트 닫기·삭제 | `BaseProjectImpl` → `DataSourceRegistry.dispose` | Swing에 프로젝트를 닫는 명령이 없음 | 해당 없음 |

## 설계 결정과 이유

- **새 문구 묶음 `LifecycleMessages`**: 다른 작업이 같은 묶음 파일을 동시에 고치므로 충돌을 줄이려고 따로 두었다(셸 명령 승인과 같은 방식).
- **여러 연결 끊기는 한 질문**: 연결마다 물으면 열린 연결 수만큼 대화상자가 뜬다. 끝낼 때 질문과 같은 목록 방식으로 했다.
- **승인 대화상자는 셸 명령 승인과 같은 모양**: 저장된 설정의 코드를 실행하기 전 질문이라는 점이 같아서 같은 규칙(정확한 내용, 취소 기본)을 따랐다.
- **H2 승인 파일과 키는 SWT 그대로**: SWT에서 허용한 작업 공간을 옮겨 와도 다시 묻지 않게.
- **절전은 묻지 않고 건너뜀**: 잠들기 직전에는 대화상자를 띄울 시간이 없고, 열린 트랜잭션을 버리는 것은 규칙 위반이다. 건너뛴 연결은 이후 복구 경로가 묻는다.
- **스마트 커밋은 바꾼 세션만 되돌림**: 업스트림처럼 모든 커밋 뒤 되돌리면 처음부터 수동 커밋인 연결의 커밋 방식이 사용자 모르게 바뀐다.

## upstream 대비 변경점

- 모델: `InvalidateJob.collectDependentDataSources` 공개, `InvalidateJob`의 결과를 모든 단계로 셈(마지막 단계만 세어 실패를 성공으로 알리던 버그), `QMUtils.getRecentTransactionStatements` 추가, `DataSourceMonitorJob`의 절전 닫기·깨어남 다시 초기화가
  쓰는 중인 연결을 건너뜀(`isInUse`, 유휴 연결 닫기의 조건을 이 함수로 뺌).
- SWT UI 클래스(`UIServiceConnectionInitImpl`, `SQLiteExtensionConfirmationImpl`, `ConnectionLostDialog`, `DataSourceInvalidateHandler`, `DataSourceDisconnect*Handler`,
  `FolderDisconnectHandler`, `TransactionLogDialog`의 지난 트랜잭션, `ApplicationWorkbenchAdvisor.cancelRunningTasks`·`systemSleepListener`)는 #68에서 지워졌다.
  Swing 클래스는 동작만 맞춰 새로 썼다.
- SWT와 다른 동작: 연결 끊김 대화상자의 기본 단추(닫기), 여러 연결 끊기의 한 질문, 스마트 커밋 되돌리기 범위, 실행 중인 작업 질문에 "기다리기"를 더함.

## 확인 (2026-11-08)

이 브랜치의 제품 빌드를 복사해 자기 작업 공간·데이터 폴더(`CHROMITE_DATA`)로 실행하고, 프로세스 안에서 Swing API로 누르는 스크립트(OS 입력 없음)로
한국어(OS 기본)와 영어(`-nl en`) 두 번 확인했다. 연결: PostgreSQL 17(55432, 수동 커밋, 끊김 시험을 위해 내가 띄운 TCP 전달기 55499를 거침), PostgreSQL 자동 커밋
(스마트 커밋 시험용 유형), SQL Server 2022(51433, 수동 커밋), H2 2.4 내장(scratch 파일, URL에 `INIT=`), SQLite(scratch 파일, `enable_load_extension=true`).
확인용 DB `chr_p4_lifecycle`은 확인 뒤 지웠다. H2 드라이버는 앱이 Maven Central에서 scratch 데이터 폴더로 내려받았다.
증거: `D:\Tools\_scratch\chromite-p4-conn-lifecycle\`(스크립트 `scripts\k*.txt`(한국어)·`e*.txt`(영어), 결과 `*.txt.log`, 창 그림 `scripts\shots\`, 앱 로그 `app-debug.log`).

| 확인 | 결과 |
|---|---|
| H2 연결(URL `INIT=`) | 승인 대화상자(연결·URL·SQL, 허용하고 연결 / 취소, 기본 취소). 취소 → "H2 initialization SQL execution was not approved", 연결 안 됨. 허용 → 연결, `settings\confirmed_connection_init_scripts.json`에 기록, 끊고 다시 연결하면 묻지 않음 |
| SQLite 확장 | 승인 대화상자(연결·URL). 취소 → 연결 안 됨. 허용 → 연결, 끊고 다시 연결하면 묻지 않음 |
| 그룹 연결 끊기·다른 연결 끊기(PG·SQL Server 창에 열린 UPDATE) | 한 질문에 두 세션("MSSQL test (SQLQuery2.sql)", "PG test (SQLQuery1.sql)"), 기본 취소. 취소 → 둘 다 연결 유지. 롤백 → 둘 다 끊김, 서버 세션 0, 행 그대로 |
| 무효화/다시 연결(PG 창에 열린 UPDATE) | 질문 "세션을 다시 열면 트랜잭션이 끝납니다". 취소 → 서버 `idle in transaction` 그대로. 롤백 → 주·메타데이터·창 세션이 모두 새 pid, 행 `orig`, 쿼리 창 그대로 |
| 연결 끊김(전달기를 끊고 PG에서 문장 오류를 `showError`로) | "연결 끊김: PG test", 08006 오류, 다시 연결 / 닫기(기본 닫기) |
| 다시 연결 실패(전달기 꺼짐) | 고치기 전: "활성 연결 수 3/3"이라는 거짓 성공 알림만(`InvalidateJob` 버그). 고친 뒤: "다시 열지 못했습니다" 08001, 연결 끊기 / 다시 시도 / 닫기. 전달기를 켜고 다시 시도 → "2/2" 성공. 영어에서 연결 끊기 → 끊김 |
| 드라이버 클래스 없음(H2 드라이버 클래스 이름을 메모리에서만 바꿈) | 고치기 전: 일반 오류 창. 고친 뒤: "드라이버 클래스를 찾지 못했습니다", 드라이버 편집… / 닫기 → 드라이버 편집 창이 열림 |
| 끝내기 + 실행 중인 쿼리(SQL Server `WAITFOR`) | "실행 중인 작업: Execute query", 기본 취소. 취소 → 앱·쿼리 그대로. 멈추고 끝내기(한국어) → 트랜잭션 질문 → 롤백 → 앱 종료, 행 `orig`, 서버 세션 0. 기다린 뒤 끝내기(영어) → 쿼리가 끝난 뒤 트랜잭션 질문 → 롤백 → 종료 |
| 스마트 커밋(Options > Connection Types에서 켬) | 자동 커밋 창에서 `SELECT`는 자동 커밋 그대로. `UPDATE` → 수동 커밋·열린 트랜잭션. 롤백·커밋 뒤 자동 커밋으로 돌아감(고치기 전 상태 줄은 수동 커밋으로 남아 보였음). 롤백한 행은 `orig`, 커밋한 행은 바뀜 |
| 트랜잭션 로그의 지난 트랜잭션 | PG: "커밋함 05:02:22", "롤백함 05:02:30", "열림" 세 줄. SQL Server(영어): Committed / Rolled back / Open |
| 절전(`OperationSystemState.toggleSleepMode`를 앱 안에서 부름, `connection.closeOnSleep` 켬) | 열린 트랜잭션이 있는 연결만 유지("kept open for sleep mode"), 나머지 원격 연결은 끊김. 한국어: PG 유지, SQL Server·PG 자동 끊김. 영어: SQL Server 유지, PG 둘 끊김 |

확인하지 못한 것: macOS의 실제 절전 알림(Windows에서는 JDK가 주지 않음), 깨어난 뒤 다시 초기화(감시 간격 20초 넘는 공백이 필요), 드라이버 라이선스 오류(만드는 코드가 없음).
## 남은 일

- SQLite 결과 오류의 "확장 설정" 동작(`resultset.error`).
- 스마트 커밋의 연결별 값 화면(연결 대화상자 Options > Settings).
- 끝낼지 한 번 더 묻는 "종료 확인"(`CONFIRM_EXIT`)과 "다시 묻지 않음" 관리(A-C27).
- Windows·Linux의 절전 알림(JDK가 주지 않는다). 깨어남은 감시 간격으로만 알아챈다.
- 스마트 커밋이 수동 커밋으로 바꾼 세션의 상태 줄 도구 설명은 "연결 설정은 지금 자동 커밋입니다. 다시 연결하면 적용됩니다"를 보인다(기존 문구). 커밋·롤백 뒤 돌아간다는 설명이 더 맞다.
- 끝낼 때 작업 목록의 이름은 모델 Job 이름이라 영어다(예: "Execute query").

## 변경 이력

- 2026-11-08 최초 작성: H2 초기화 SQL·SQLite 확장 승인, 여러 연결 끊기, 무효화·다시 연결, 연결 끊김·드라이버 오류 대화상자, 끝낼 때 실행 중인 작업,
  스마트 커밋, 트랜잭션 로그의 지난 트랜잭션, 절전 닫기·깨어남 다시 초기화의 트랜잭션 안전, 닫기 경로 전수 점검 (`chromite/p4-conn-lifecycle`).
  실행 확인 중 찾아 함께 고침: `InvalidateJob`의 거짓 성공, 연결 실패의 드라이버 클래스 없음 판단, 상태 줄 커밋 방식 다시 읽기
- 2026-11-08 B-S18(실행 중 커밋 방식 전환)을 [transactions.md](transactions.md) "커밋 방식 전환"에서 더해 범위 밖 줄을 고치고 남은 일에서 뺌
- 2026-11-11 다른 연결 끊기·모두 연결 끊기를 Registered Servers에서 Object Explorer 연결 메뉴로 옮기고 그룹 연결 끊기는 없앰(앱 변경 #48, `chromite/servers-tree`)
- 2026-11-12 닫기 경로 표의 쿼리 창 줄에 연결 변경(앱 변경 #73, `chromite/change-connection`)
