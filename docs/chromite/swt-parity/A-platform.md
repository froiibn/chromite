# A — 플랫폼 서비스와 백그라운드 동작: SWT → Swing 대조

- SWT 기준: `263b8ddb45`(삭제 직전 devel). Swing 기준: 작업 트리 devel `8e8aaaef60`.
- 대상 번들(SWT, 삭제됨): `core`, `ui`, `ui.app.standalone`, `ui.app.config`, `ui.forms`, `ui.config.migration`, `ui.config.sample`,
  `ui.swt.windows`, `ui.swt.macos`, `launcher`. 연결 수명 주기 확인은 SWT에서 `ui.editors.connection`의 `DataSourceHandler`가 맡았으므로 함께 봤다.
- 방법: 남은 번들의 `DBWorkbench.getService(...)` 호출 전부, `DBWorkbench.getPlatformUI()` 메서드 사용 전부, SWT 앱이 덮어쓴 `DBPApplication` 메서드,
  SWT `DesktopPlatform`/`ChromiteApplication.start`의 초기화 단계를 Swing(`ui.swing/plugin.xml`, `SwingPlatformUI`, `ChromiteSwingApplication`,
  `ChromiteSwingPlatform`)과 하나씩 맞춰 봤다. 모든 판단은 코드 검색과 읽기로 했다. **빌드·실행은 하지 않았다**(지시서대로 읽기 전용).
- 줄 번호는 `plugins/` 아래 경로 기준이다. SWT 쪽 경로는 `263b8ddb45`에서 본 것이다.

## 1. 조용히 끊김 (가장 위험한 것부터)

모델 코드는 남아 있는데 UI 연결 지점(서비스 등록, `DBPPlatformUI` 메서드, `DBPApplication` 재정의, 플랫폼 초기화)이 없어져서
아무 일도 안 하거나 확인 질문을 건너뛰는 것들이다.

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| S1 | 유휴 트랜잭션 자동 롤백과 그 확인 질문 | `ui.app.standalone` `ChromiteApplication.getLastUserActivityTime`(177-180, 입력 필터 540-546), `core` `UIServiceConnectionsImpl`(core `plugin.xml`:1110) | **수정함** (2026-11-07, #97, [idle-transactions.md](../idle-transactions.md): 유휴 시간을 그 트랜잭션 문맥의 마지막 사용자 문장부터 잼(전역 활동 시각 대신), 기본은 알림과 커밋 / 롤백 / 열어 두기, 연결에서 켰을 때만 자동 롤백 뒤 알림. 묻지 않고 롤백하던 `EndIdleTransactionsJob` 경로와 `confirmTransactionsClose`를 지움). 조사 당시: 조용히 끊김 (**알려진 건, 다른 에이전트가 수정 중**) | `ChromiteSwingApplication`에 `getLastUserActivityTime` 재정의 없음 → 기본값 -1 → `DataSourceMonitorJob.getLastUserActivityTime(lastPingTime)`(model `runtime/jobs/DataSourceMonitorJob.java`:173, 365-371)이 직전 점검 시각(약 3초 전)을 돌려줌 → `idleInterval`(270)이 시간 제한을 넘지 못함. `ui.swing/plugin.xml`:20-27에 `UIServiceConnections` 등록 없음 → `EndIdleTransactionsJob.java`:63-70이 확인 없이 롤백함(활동 시각만 고치면 이 경로가 열림) | 높음 | 수정 때 S2·S3·S7도 함께 봐야 한다(아래) |
| S2 | 유휴 연결 자동 끊기(Close idle connection) | 같은 `DataSourceMonitorJob` + SWT 활동 시각 | **수정함** (2026-11-07, `chromite/idle-close-notifications`, [idle-transactions.md](../idle-transactions.md) "유휴 연결 닫기": 연결별 마지막 사용 시각(쿼리 관리자 기록, 커밋·롤백, 연결 시각, AI 도우미 세션)으로 잼, 열린 트랜잭션·실행 중이면 닫지 않고 그 트랜잭션은 유휴 트랜잭션 감시에 맡김, 끊기 직전 다시 확인, 끊은 뒤 경고 알림. PostgreSQL·SQL Server에서 실행 확인). 조사 당시: 조용히 끊김 | Swing 연결 대화상자는 체크 상자와 시간을 **보여 주고 저장한다**(`ConnectionSettingsPanel.java`:888-892). 그러나 S1과 같은 원인으로 `DataSourceMonitorJob.java`:276 조건이 참이 되지 않아 끊지 않는다. 끊기가 동작하게 되면 `DisconnectJob` → `JDBCDataSource.closeConnection`(model.jdbc `JDBCDataSource.java`:514-519)이 열린 트랜잭션을 **묻지 않고 롤백**한다 | 높음 | 화면에 있는 설정이 효과가 없다. S1 수정이 이 경로도 같이 열기 때문에 S3의 확인이 먼저 필요하다 |
| S3 | 연결 끊기·다시 연결·연결 삭제·설정 변경 후 다시 연결 때 열린 트랜잭션 확인(Commit / Rollback / Cancel) | `ui.editors.connection` `DataSourceHandler.checkAndCloseActiveTransaction`(SWT `ApplicationWorkbenchAdvisor.closeActiveTransactions`:538-545도 같은 함수), `UIServiceConnections.checkAndCloseActiveTransaction` | **수정함** (2026-11-07, #97, [idle-transactions.md](../idle-transactions.md) "연결 끊기 전 질문": 끊기·다시 연결·삭제·편집 뒤 다시 연결이 `ExitTransactionCheck.confirmDisconnect`로 커밋 / 롤백 / 취소를 물음). 조사 당시: 조용히 끊김 | Swing `ConnectionCommands.disconnect`(`ConnectionCommands.java`:177-203)는 `DisconnectJob`만 예약한다. `reconnect`(122-128), `delete`(133-149), 연결 편집 뒤 다시 연결(`ConnectionDialog.java`:463)이 모두 이 경로다. 모델은 닫을 때 롤백한다(`JDBCDataSource.java`:514-519, 주석 "Any valuable transaction changes should be committed by UI"). 앱 종료만 `ExitTransactionCheck`로 묻는다(`MainFrame.java`:1191). [swing-frontend.md](../swing-frontend.md):789에 "진행 중 트랜잭션 확인은 아직 없다"로 기록됨 | 높음 | 사용자 규칙 "트랜잭션 안전"(암묵적 커밋·롤백 금지)에 어긋남 |
| S4 | 셸 명령 실행 전 사용자 승인: 연결 이벤트(연결 전·후, 끊기 전·후 명령), 셸 명령 비밀번호 인증 방식, 내보내기 끝난 뒤 명령 실행 | `core` `UIServiceShellCommandsImpl`(core `plugin.xml`:1113, `ConfirmShellCommandDialog`, 승인 기록 `ConfirmedShellCommandsStore`) | **수정함** (2026-11-07, [shell-command-approval.md](../shell-command-approval.md): Swing `SwingShellCommandsService` 등록, 서비스가 없으면 실행 거부). 조사 당시: 조용히 끊김 (**확인 건너뜀**) | `UIServiceShellCommands`가 Swing에 등록되지 않음. 세 호출부 모두 `if (service != null) validateByUser(...)` 형태라 서비스가 없으면 승인 없이 실행한다: registry `DataSourceDescriptor.java`:1475-1483, model `impl/auth/AuthModelDatabaseShellCommand.java`:116-122, data.transfer `ExecuteCommandEventProcessor.java`:48-52 | 높음 (보안) | Swing에는 이 명령을 설정하는 화면이 없지만, `data-sources.json`에 이미 있거나 가져온·공유한 연결 설정에 들어 있으면 연결할 때 그대로 실행된다. 승인 대화상자의 목적이 바로 이 경우다 |
| S5 | 비밀번호를 저장하지 않은 연결의 자격 증명 질문(기본 방식이 아닌 인증 방식) | `core` `DesktopUI.promptAuthModelCredentials`(465-475) → `AuthModelCredentialsDialog` | **수정함** (2026-11-07, [swing-auth-prompts.md](../swing-auth-prompts.md): `SwingPlatformUI.promptAuthModelCredentials`). 조사 당시: 조용히 끊김 (수정 전 빌드에서 실행 확인함: 질문·알림 없이 연결되지 않음) | registry `DataSourceDescriptor.java`:2133-2139는 인증 방식 클래스가 정확히 `AuthModelDatabaseNative`가 아니면 `promptAuthModelCredentials`를 부른다. Swing은 재정의하지 않아 기본값 `false`(model `runtime/ui/DBPPlatformUI.java`:136-140) → `connect`가 `false`(1176-1181) → `ConnectJob`이 `CANCEL_STATUS` → `SwingConnectionService.java`:114가 취소는 알리지 않음. 해당 방식: SQL Server 기본 "SQL Server Authentication"(`SQLServerAuthModelDatabase`, ext.mssql `plugin.xml`:641-649 `default="true"`), Oracle 기본 "Username/password"(`OracleAuthModelDatabaseNative`, ext.oracle `plugin.xml`:470-476), pgpass, BigQuery, Snowflake, Spanner 등 `AuthModelDatabaseNative` 하위 클래스 | 높음 | 코드 근거만으로 판단, 실행 확인 안 함. "Save password"를 끈 SQL Server·Oracle 연결은 연결 시도가 아무 메시지 없이 끝날 것으로 보인다. SWT는 이 경우 대화상자를 띄웠다 |
| S6 | Windows 인증서 저장소 사용(`javax.net.ssl.trustStoreType=WINDOWS-ROOT`) | `ui.app.standalone` `ChromiteApplication.start`:332-339 | **수정함** (2026-11-07, [platform-startup.md](../platform-startup.md): Swing 앱 시작에서 같은 조건으로 설정, Windows에서 실행 확인). 조사 당시: 조용히 끊김 | Swing 시작 코드에 없음(`ui.swing/src`에서 `trustStore` 검색 0). 설정 기본값은 Windows에서 켬(model `ModelPreferences.java`:426). 헤드리스 앱은 같은 코드를 갖고 있다(headless `ChromiteHeadlessApplication.java`:50-54) | 높음 (해당 환경) | 사내 CA로 서명한 서버 인증서를 Windows에만 설치한 환경에서 SSL 연결이 PKIX 오류로 실패할 수 있다. 실행 확인 안 함 |
| S7 | 모델 알림: 유휴 연결 끊김, 유휴 트랜잭션 롤백, 절전 뒤 연결 재검증 성공·실패 | `ui.app.standalone` `CoreApplicationActivator.configureNotifications`(57-80) → `NotificationUtils` 팝업 | **수정함** (2026-11-07, `chromite/idle-close-notifications`, [idle-transactions.md](../idle-transactions.md) "모델 알림": `SwingModelNotifications` 등록, 종류 유지, 경고·오류는 닫을 때까지 남음. 수정 때 확인: 유휴 트랜잭션 롤백은 #97부터 모델 알림을 쓰지 않고, 절전 뒤 재검증은 처리기 없이 불려 알림이 없음. 다시 연결 성공·실패는 연결 유지 핑과 실행 중 복구에서 옴). 조사 당시: 조용히 끊김 | Swing은 `ChromiteNotifications.setHandler`를 부르지 않음(`ui.swing/src` 검색 0) → 기본 `ConsoleHandler`가 `System.out`에만 씀(model `runtime/ChromiteNotifications.java`:96-117). 호출부: `DataSourceMonitorJob.java`:374, `EndIdleTransactionsJob.java`:79, `DefaultInvalidationFeedbackHandler.java`:40, 53 | 중간 | S1·S2가 고쳐지면 "롤백했다"는 사실이 화면에 안 나온다. Swing에는 이미 알림 창(`NotificationToast`, `SwingPlatformUI.showNotification`)이 있으니 연결만 하면 된다 |
| S8 | 시스템 절전 때 연결 닫기(`connection.closeOnSleep`, macOS 기본 켬) | `ui.app.standalone` `ApplicationWorkbenchAdvisor.systemSleepListener`(223-233) → `OperationSystemState.toggleSleepMode` | **수정함** (2026-11-08, [connection-lifecycle.md](../connection-lifecycle.md) "절전": JDK 절전 알림(macOS만)을 연결 감시에 넘김, 절전 닫기와 깨어난 뒤 다시 초기화는 쓰는 중인 연결(열린 트랜잭션, 실행 중, Chromite Agent)을 건너뜀. 깨어난 뒤 다시 초기화도 전에는 묻지 않고 롤백했다). 조사 당시: 조용히 끊김 | `toggleSleepMode`를 부르는 곳이 없음(전체 검색 0). `DataSourceMonitorJob.java`:91-109의 "절전 진입 → 모든 연결 닫기" 분기가 실행되지 않는다. 깨어남 감지(점검 간격 20초 초과로 추정)는 그대로 동작 | 중간 | 동작하게 되면 이것도 S3처럼 묻지 않고 롤백한다. 2026-11-07 S2 수정은 이 경로를 열지 않았다(`isInSleepMode`가 여전히 거짓). 열 때는 유휴 연결 닫기와 같은 트랜잭션 확인을 거쳐야 한다 |
| S9 | BouncyCastle JCE 공급자 등록(`security.jce.bc`, 기본 켬) | `core` `DesktopPlatform.initialize`:96-98 → `SecurityProviderUtils.registerSecurityProvider` | **수정함** (2026-11-08, `chromite/p4-connection-tools`, [platform-startup.md](../platform-startup.md): `ChromiteSwingPlatform.initialize` 맨 앞에서 등록, 기본 켬. 실행 확인: `Security.getProvider("BC")` 있음). 조사 당시: 조용히 끊김 | `ChromiteSwingPlatform.initialize`에 없음. 남은 코드에서 `registerSecurityProvider` 호출 0 | 중간 | 어떤 기능이 공급자 "BC"를 이름으로 찾는지는 확인하지 못했다(PEM·PKCS 키 파일, 일부 드라이버의 키 형식 의심). 실행 확인 필요 |
| S10 | H2 연결 URL의 `INIT=` 스크립트와 H2 연결의 초기화 SQL(부트스트랩 쿼리) 실행 확인 | `core` `UIServiceConnectionInitImpl`(core `plugin.xml`:1106-1107) | **수정함** (2026-11-08, [connection-lifecycle.md](../connection-lifecycle.md) "초기화 SQL과 SQLite 확장 승인": `SwingConnectionInitService`, 허용하고 연결 / 취소, SWT와 같은 승인 파일). 조사 당시: 조용히 끊김 (안전 쪽으로 실패) | ext.h2 `H2DataSource.java`:97, 112: 서비스가 없으면 `false` → `H2InitScriptValidator.java`:48-49가 "not approved. Connection cancelled" 예외 | 낮음 | 오류는 보인다. 다만 Swing 연결 대화상자는 모든 드라이버에 초기화 SQL 편집(`BootstrapQueriesDialog`)을 주므로 H2에서는 넣는 순간 연결이 안 된다 |
| S11 | SQLite 네이티브 확장 로드 승인 | `ext.sqlite.ui` `SQLiteExtensionConfirmationImpl`(ext.sqlite.ui `plugin.xml`:7) | **수정함** (2026-11-08, [connection-lifecycle.md](../connection-lifecycle.md) "초기화 SQL과 SQLite 확장 승인": `SwingSqliteExtensionConfirmation`). 조사 당시: 조용히 끊김 (안전 쪽으로 실패) | ext.sqlite `SQLiteDataSource.java`:178-179 → `SQLiteExtensionLoading.java`:77-78 "not approved. Connection cancelled" | 낮음 | 확장을 켠 SQLite 연결은 열리지 않는다 |
| S12 | 클라이언트 시간대 설정(`java.client.timezone`) 적용 | `ui.app.standalone` `ChromiteApplication.start`:330 `TimezoneRegistry.overrideTimezone()` | **수정함** (2026-11-08, `chromite/p4-connection-tools`, [platform-startup.md](../platform-startup.md): 플랫폼을 만든 직후 적용, `Asia/Tokyo`로 저장하고 다시 시작해 기본 시간대가 바뀜을 확인. 설정 칸은 Options > Connections([swing-options.md](../swing-options.md))). 조사 당시: 조용히 끊김 | Swing에서 호출 0. 설정 화면도 없음(SWT `PrefPageGeneral`) | 낮음 | SWT에서 시간대를 바꿔 둔 작업 공간이면 날짜 표시가 달라질 수 있다(설정 노드가 달라서 사실상 읽지도 않음, A-P1 참고) |
| S13 | 네트워크 IP 스택·네트워크 디버그 설정 적용 | `ui.app.standalone` `ChromiteApplication.initializeApplication`:493-518 | **수정함** (2026-11-08, `chromite/p4-connection-tools`, [platform-startup.md](../platform-startup.md): `ChromiteSwingApplication.configureNetwork`, IPv4로 저장하고 다시 시작해 `java.net.preferIPv4Stack=true` 확인). 조사 당시: 조용히 끊김 | Swing에 없음(`IPType` 검색 0) | 낮음 | |
| S14 | macOS 메뉴 통합(Cmd+Q, About, 환경설정) | `ui.swt.macos` `CocoaUIService`(fragment.xml `pluginService`) | **수정함, macOS 실행 확인 안 함** (2026-11-07, [platform-startup.md](../platform-startup.md): `Desktop` 끝내기 처리기가 메인 창 닫기 경로로 보냄, 정보·환경설정 연결). 조사 당시: 조용히 끊김 (**확인 필요**) | Swing에 `Desktop.setQuitHandler`/`setQuitStrategy`/`apple.laf.useScreenMenuBar` 없음(검색 0). JDK 기본 종료 방식은 `System.exit`이라 Cmd+Q가 창 닫기 경로(`MainFrame`의 저장 확인과 `ExitTransactionCheck`)를 건너뛸 수 있다 | 높음 (macOS, 미확인) | macOS 실행은 지금까지 확인한 적이 없다([swing-frontend.md](../swing-frontend.md):384). 실제 동작을 macOS에서 확인해야 한다 |

## 2. `DBWorkbench.getService(...)` 전수 (남은 번들, Swing 제외)

| 호출 위치 | 서비스 | SWT 구현 | Swing 구현 | 서비스가 없을 때 | 분류 |
|---|---|---|---|---|---|
| model `model/DBUtils.java`:2799 | `DBServiceConnections.initConnection` | `UIServiceConnectionsImpl` | `SwingConnectionService` | - | 있음 |
| model `runtime/net/GlobalProxyAuthenticator.java`:117 | `DBServiceConnections.getGlobalProxyConfiguration` | `UIServiceConnectionsImpl`:126-140(Eclipse `IProxyService` 자격 증명) | 기본 메서드(`null`) | Eclipse 네트워크 설정의 프록시 자격 증명을 쓰지 않음 | 없음 (낮음) |
| registry `driver/DriverLoaderDescriptor.java`:424 | `UIServiceDrivers` | `UIServiceDriversImpl` | `SwingDriversService` | - | 있음 |
| tasks.native `AbstractNativeToolHandler.java`:280 | `UIServiceSystemAgent` | `UIServiceSystemAgentImpl`(트레이) | `SwingSystemAgent`(알림 창) | - | 있음 |
| model `runtime/jobs/EndIdleTransactionsJob.java`:63 | `UIServiceConnections.confirmTransactionsClose` | `UIServiceConnectionsImpl` | 호출 삭제(#97) | - | S1 수정함(이 호출과 묻지 않는 롤백 경로를 지움). 조사 당시: 조용히 끊김 |
| registry `DataSourceDescriptor.java`:1476 | `UIServiceShellCommands.validateByUser` | `UIServiceShellCommandsImpl` | `SwingShellCommandsService` (2026-11-07) | 실행 거부(`UIServiceShellCommands.validate`) | 있음 (S4 수정함. 조사 당시: 승인 없이 실행) |
| model `impl/auth/AuthModelDatabaseShellCommand.java`:116 | 같음 | 같음 | 같음 | 같음 | 있음 (S4 수정함) |
| data.transfer `processor/ExecuteCommandEventProcessor.java`:48 | 같음 | 같음 | 같음 | 같음 | 있음 (S4 수정함). Swing 내보내기 마법사는 이 처리기를 고를 수 없어 SWT에서 저장한 작업으로만 닿음 |
| ext.h2 `H2DataSource.java`:97, 112 | `UIServiceConnectionInit` | `UIServiceConnectionInitImpl` | `SwingConnectionInitService` (2026-11-08) | 연결 거부 | 있음 (S10 수정함. 조사 당시: 조용히 끊김) |
| ext.sqlite `SQLiteDataSource.java`:178 | `SQLiteExtensionConfirmation` | `ext.sqlite.ui` | `SwingSqliteExtensionConfirmation` (2026-11-08) | 연결 거부 | 있음 (S11 수정함. 조사 당시: 조용히 끊김) |
| ext.clickhouse `auth/ClickhouseAuthPrompt.java`:61 | `UIServiceAuth`(브라우저·코드 인증 팝업) | `UIServiceAuthImpl`(+`CodeAuthDialog`) | 없음 | `DBException` "available only in desktop applications" | 없음 (낮음). 새 연결에서는 Swing이 이 방식을 목록에서 뺀다(`ConnectionAuthModels.java` 클래스 주석) |
| model.ai `utils/AIUtils.java`:342 | `UIServiceSQL.confirmQueryExecution` | ui.editors.sql `UIServiceSQLImpl` | 없음 | `confirmAction`으로 대신 물음(SQL 미리 보기 없음) | 일부 (낮음) |
| model `impl/auth/SessionContextImpl.java`:60 | `SMSessionProviderService` | SWT도 등록 없음 | 없음 | 변화 없음 | 해당 없음 |

## 3. `DBPPlatformUI` 메서드 (남은 번들이 쓰는 것 중 Swing이 하지 않는 것)

남은 번들(Swing 제외)에서 쓰는 메서드는 24종이다(`getPlatformUI().X` 검색: showError 34회, confirmAction 14회, …). 그중 Swing이 SWT와 같은 일을 하지 않는 것:

| 메서드 | Swing 동작 | 남은 호출부 | 결과 | 분류 / 영향 |
|---|---|---|---|---|
| `promptAuthModelCredentials` | `SwingPlatformUI` (2026-11-07). 조사 당시: 재정의 없음 → `false` | registry `DataSourceDescriptor.java`:2136 | 연결이 조용히 취소됨 | 있음 (S5 수정함. 조사 당시: 조용히 끊김, 높음) |
| `showError`의 DB별 처리 | `// Phase C`(`SwingPlatformUI.java`:108, 115): 일반 오류 창만 | 모든 오류 표시 | SWT는 연결 끊김이면 "다시 연결" 대화상자(`ConnectionLostDialog`), 드라이버 클래스 없음이면 드라이버 설정 안내, 드라이버 라이선스 오류 창을 띄웠다(`DesktopUI.showDatabaseError`) | 있음 (2026-11-08, `DatabaseErrorDialogs`, [connection-lifecycle.md](../connection-lifecycle.md) "연결 끊김·드라이버 오류 대화상자"). 조사 당시: 일부, 중간 |
| `openEntityEditor` | 디버그 로그만(`SwingPlatformUI.java`:495-505) | data.transfer `DatabaseTransferConsumer.java`:815(전송 뒤 대상 테이블 열기) | 아무 일 없음 | 일부, 낮음 |
| `copyTextToClipboard(…, html=true)` | 일반 텍스트로 복사(`SwingPlatformUI.java`:634) | data.transfer `StreamTransferConsumer.java`:671(클립보드로 내보내기) | HTML 형식 없음 | 일부, 낮음 |
| `readAndDispatchEvents` | `false`(`SwingPlatformUI.java`:693-697) | model `RuntimeUtils.java`:316, 706, `SimpleExclusiveLock.java`:73 | EDT에서 기다리면 화면이 멈춤(이벤트를 돌리지 않음) | 일부, 낮음 |
| `createLoadingService` | 작업 스레드에서 완료 처리(`// Phase C`) | model `PropertySourceAbstract.java`:192 | 지연 속성 로딩 표시 없음 | 일부, 낮음 |
| `selectObject` | `null` | 남은 번들 호출 0 | 영향 없음 | 해당 없음 |
| `openFileSystemSelector` | 지원 안 함 메시지(SWT와 같음) | - | 같음 | 있음 |

나머지(`showError`/`showMessageBox`/알림/확인/`showUserChoice`/`showErrorStopRetryIgnore`/자격 증명·비밀번호 변경·텍스트·라이선스 입력/
`openConnectionEditor`/`executeProcess`/`executeInMainThread`/진행 대화상자 3종/셸 열기/탐색기에서 보기)는 Swing에 구현되어 있다.

## 4. `DBPApplication`·플랫폼 초기화 차이

| 항목 | SWT | Swing | 분류 |
|---|---|---|---|
| `getLastUserActivityTime` | 키·마우스 입력 필터로 갱신(`ChromiteApplication.java`:177-180, 540-546) | 메서드 삭제(2026-11-07): 유휴 시간은 문맥·연결별 사용 시각으로 잼 | S1·S2 수정함. 조사 당시: 조용히 끊김 |
| `isStandalone`/`isPrimaryInstance`/`isHeadlessMode`/`isExclusiveMode`/`isCommunity`/`getDefaultProjectName` | 재정의(인스턴스 서버·CLI 모드에 따라 바뀜) | 기본값(true/true/false/false) + `isCommunity`, `getDefaultProjectName` 재정의 | 있음 (일반 실행에서 값이 같다) |
| `getPreferenceStore` / 모델 설정 노드 | `com.seizia.chromite.core`(`ChromiteActivator.java`:71 `setMainBundle`) | `com.seizia.chromite.ui.swing`(`ChromiteSwingActivator.java`:65) | 일부 — A-P1 |
| `initializeApplication` | IP 스택, 네트워크 디버그, Defender 정책 | 없음 | 조용히 끊김 (S13) |
| `start`의 Windows 인증서 저장소, 시간대 | 있음(:330-339) | 인증서 저장소만 있음(`ChromiteSwingApplication.useWindowsTrustStore`) | S6 수정함. 시간대는 조용히 끊김 (S12) |
| `DesktopPlatform.initialize`의 BouncyCastle | 있음 | 없음 | 조용히 끊김 (S9) |
| `DesktopPlatform.dispose`의 `setHeadlessMode(true)` | 종료 중 UI 질문을 막음 | 없음 | 일부, 낮음(종료 중 모델이 대화상자를 띄울 수 있음, 실행 확인 안 함) |
| 알림 처리기(`ChromiteNotifications.setHandler`) | `CoreApplicationActivator` | `SwingModelNotifications`(`ChromiteSwingApplication.start`) | 있음 (S7 수정함. 조사 당시: 조용히 끊김) |
| 절전 감지 | `ApplicationWorkbenchAdvisor` | 없음 | 조용히 끊김 (S8) |
| Eclipse 프록시 서비스 활성화 | `ApplicationWorkbenchAdvisor.activateProxyService`:626-643 | 없음 | 없음 (낮음) |
| 연결 감시 Job(`DataSourceMonitorJob`) 시작 | 워크벤치 창 advisor | `platform.postInitialize()`(`ChromiteSwingApplication.java` start) | 있음 (S1·S2 수정함) |
| 디버그 로그 | `initDebugWriter` | 같은 방식(`ChromiteSwingApplication.initDebugWriter`) | 있음 |

## 5. 기능 인벤토리 (번들별)

### 5.1 `core` (SWT 데스크톱 플랫폼, 연결·트랜잭션 명령, QM 보기)

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| A-C1 | 커밋 / 롤백 명령(도구 막대, 메뉴, 단축키) | `DataSourceCommitHandler`, `DataSourceRollbackHandler` | **있음** (2026-11-07, [transactions.md](../transactions.md): Query &gt; Commit / Roll Back, SQL Editor 도구 막대 단추, SWT 단축키 Ctrl+Alt+Shift+K / R(macOS·Linux Ctrl+4 / 8), 상태 줄 메뉴와 같은 커밋 경로). 조사 당시: 없음 | Swing에 커밋·롤백 동작 없음(`commit(`/`rollback(` 검색: `ExitTransactionCheck`, `AutoCommitApplier`, `ResultSetEditPersister`뿐). [swing-frontend.md](../swing-frontend.md):1236 "Swing에는 아직 커밋 단추가 없으므로 편집기에서 `COMMIT`을 실행한다" | 높음 | 수동 커밋 연결을 쓰려면 SQL을 직접 쳐야 한다 |
| A-C2 | 열린 트랜잭션 표시(문장 수·경과 시간 도구 막대) | `TransactionMonitorToolbar` | **있음** (2026-11-07, #97, [idle-transactions.md](../idle-transactions.md): 쿼리 창 상태 줄의 열린 트랜잭션 칸(경과 시간, 문장 수), 누르면 커밋·롤백·트랜잭션 로그). 조사 당시: 없음 | Swing은 커밋 방식(자동/수동)만 보여 준다(swing-frontend.md "자동 커밋 상태", `QueryStatusLine`). 보류 문장 수·시간 없음 | 높음 | 잠금을 오래 쥐고 있는지 알 수 없다 |
| A-C3 | 보류 중 트랜잭션 창(모든 연결의 커밋 안 한 문장, 거기서 커밋·롤백) | `PendingTransactionsDialog`, `DataSourcePendingTransactionsHandler` | **있음** (2026-11-07, [transactions.md](../transactions.md) "열린 트랜잭션 목록": Tools &gt; Open Transactions…, 연결·DB·세션·시작·문장 수·유휴, 고른 행 커밋·롤백. "Show all connections"는 없음). 조사 당시: 없음 | Swing 검색 0(`QMTransactionState`, `getTransactionState` 0). 지시서의 예와 같음 | 높음 | |
| A-C4 | 트랜잭션 로그(현재 트랜잭션의 문장 목록) | `TransactionLogDialog`, `TransactionInfoDialog`, `DataSourceTransactionLogHandler` | **있음** (2026-11-07, [transactions.md](../transactions.md) "트랜잭션 로그": Query 메뉴·상태 줄·목록에서 열고 현재 트랜잭션의 시각·종류·SQL·시간·행·결과. 지난 트랜잭션 보이기는 2026-11-08 더함([connection-lifecycle.md](../connection-lifecycle.md) "트랜잭션 로그의 지난 트랜잭션")). 조사 당시: 없음 | Swing 검색 0 | 중간 | |
| A-C5 | 자동 커밋 전환, 격리 수준, 스마트 커밋 메뉴 | `DataSourceAutoCommitHandler`, `DataSourceTransactionModeContributor` | 일부 | 자동 커밋: 상태 표시 + 연결 편집으로 안내, 열린 세션에 적용(swing-frontend.md "자동 커밋 상태", 사용자 결정 "상태표시줄에서 바꾸면 안된다"). 격리 수준·스마트 커밋 전환은 없음 | 중간 | 자동 커밋을 상태 줄에서 바꾸지 않는 것은 의도한 것 |
| A-C6 | Query Manager 보기(모든 실행 기록, 필터, 지우기) | `views/qm/QueryManagerView` 외 | 일부 | Swing `QueryHistory`(편집기 문장만, 클래스 주석). [swt-removal.md](../swt-removal.md):112 | 중간 | 트랜잭션 경계·메타데이터 쿼리는 안 보인다 |
| A-C7 | 연결 끊기 변형(모두 끊기, 다른 연결 끊기, 프로젝트·폴더 단위 끊기) | `DataSourceDisconnectAllHandler`, `…OtherHandler`, `…ProjectHandler`, `FolderDisconnectHandler` | **있음** (2026-11-08, [connection-lifecycle.md](../connection-lifecycle.md) "여러 연결 끊기": Registered Servers의 다른 연결 끊기·모두 연결 끊기·그룹 연결 끊기, 열린 트랜잭션은 한 질문). 조사 당시: 없음 | Swing 연결 명령은 하나씩만(`ConnectionCommands`) | 낮음 | |
| A-C8 | 연결 무효화·다시 연결(세션 유지 재연결) | `DataSourceInvalidateHandler` | **있음** (2026-11-08, [connection-lifecycle.md](../connection-lifecycle.md) "무효화·다시 연결": Object Explorer 무효화/다시 연결, 같은 터널의 연결까지 열린 트랜잭션을 먼저 물음. 기존 다시 연결의 무확인 롤백은 #97에서 고침). 조사 당시: 일부 | Swing "다시 연결"은 끊고 다시 연결(`ConnectionCommands.java`:122-128) → S3의 무확인 롤백 | 중간 | |
| A-C9 | 읽기 전용 전환(도구 막대) | `DataSourceReadonlyHandler` | 일부 | 연결 설정 체크 상자(`ConnectionSettingsPanel.java`:887) | 낮음 | |
| A-C10 | 서버 출력 보기(Oracle DBMS_OUTPUT 등) | `views/DatabaseOutputView` | 일부 (2026-11-07 쿼리 창 출력은 고침) | 쿼리 창이 문장마다 `DBCServerOutputReader`로 읽어 Messages 탭에 씀(`SqlEditorPanel.readServerOutput`, [swing-server-output.md](../swing-server-output.md)). 별도 Output 보기는 없음 | 중간 | 출력은 SQL Server PRINT·PostgreSQL NOTICE와 같은 Messages 탭에 차례대로 나온다 |
| A-C11 | 셸 프로세스 보기(연결 이벤트 명령 출력, 중지) | `views/process/ShellProcessView` | 없음 | Swing 검색 0 | 낮음 | |
| A-C12 | 북마크 | `resources/bookmarks/*` | 없음 | swt-removal.md:108(프로젝트·자원 탐색기) | 낮음 | |
| A-C13 | 프로젝트 패널·자원 처리기(스크립트 폴더, 바로 가기) | `controls/ProjectsPanel`, `resources/*Handler*` | 일부(2026-11-08. 조사 당시: 없음) | Scripts 도구 창(`ScriptsPanel`, 스크립트 폴더), swt-removal.md:108 | 중간 | 바로 가기·다른 자원 처리기는 없음. [swing-scripts-explorer.md](../swing-scripts-explorer.md) |
| A-C14 | 설정·프로젝트·스크립트 내보내기/가져오기 마법사 | `tools/configuration`, `tools/project`, `tools/scripts` | 일부 (2026-11-07 연결만. 조사 당시: 없음) | 연결·그룹·비밀번호는 암호로 암호화한 파일로 옮김([connection-transfer.md](../connection-transfer.md)). 설정·스크립트·프로젝트 자원은 없음 | 중간 | 연결 외의 작업 공간 내용은 다른 PC로 옮기는 공식 경로가 없다 |
| A-C15 | 연결 끊김 시 다시 연결 대화상자, 드라이버 설정 오류 안내, 드라이버 라이선스 오류 | `DesktopUI.showDatabaseError` | **있음** (2026-11-08, [connection-lifecycle.md](../connection-lifecycle.md) "연결 끊김·드라이버 오류 대화상자"). 조사 당시: 일부 | §3 | 중간 | 모델의 자동 복구(`execute.recover.*`)는 남아 있음 |
| A-C16 | 인증 방식 자격 증명 대화상자 | `DesktopUI.promptAuthModelCredentials` | 있음 (2026-11-07 수정함. 조사 당시: 조용히 끊김) | S5, [swing-auth-prompts.md](../swing-auth-prompts.md) | 높음 | |
| A-C17 | 셸 명령 승인 | `UIServiceShellCommandsImpl` | 있음 (2026-11-07 수정. 조사 당시: 조용히 끊김) | S4, `SwingShellCommandsService` | 높음 | [shell-command-approval.md](../shell-command-approval.md) |
| A-C18 | H2 초기화 스크립트 승인 | `UIServiceConnectionInitImpl` | 있음 (2026-11-08 수정. 조사 당시: 조용히 끊김) | S10 | 낮음 | |
| A-C19 | 브라우저·코드 인증 팝업 | `UIServiceAuthImpl` | 없음 | §2 | 낮음 | |
| A-C20 | 연결 서비스(연결 Job, 확인) | `UIServiceConnectionsImpl` | 일부 | `SwingConnectionService`는 `DBServiceConnections`만. `UIServiceConnections`(트랜잭션 확인, 다시 연결 질문) 없음. 트랜잭션 확인은 `ExitTransactionCheck`(S3)와 `SwingIdleTransactionService`(S1)가 대신함(#97) | 높음 | S1, S3 |
| A-C21 | 드라이버 내려받기 확인 | `UIServiceDriversImpl` | 있음 | `SwingDriversService` | - | |
| A-C22 | 오래 걸린 작업 알림 | `UIServiceSystemAgentImpl` | 있음 | `SwingSystemAgent` | - | 트레이 대신 알림 창 |
| A-C23 | 상태 줄 breadcrumbs | `controls/BreadcrumbTrim` | 일부 | `QueryStatusLine`(서버·사용자·DB/스키마) | 낮음 | |
| A-C24 | EULA 대화상자 | `ui/eula/*` | 없음 | Swing 검색 0 | 낮음 | |
| A-C25 | 내장 브라우저 보기 | `views/BaseBrowserView`, `SWTBrowserRegistry` | 없음 | Swing은 시스템 브라우저(`SwingPlatformUI.executeShellProgram`) | 낮음 | |
| A-C26 | 도구 막대 사용자 지정, 접근성(화면 판독기) | `PrefPageToolbarCustomization`, `PrefPageAccessibility` | 없음 | Swing 검색 0 | 낮음 | |
| A-C27 | 확인 질문 "다시 묻지 않음" 관리(종료·드라이버 내려받기·테스트 연결 저장) | `com.seizia.chromite.confirmations`, `PrefPageConfirmations` | 없음 | Swing 확인 대화상자에 기억 기능·관리 화면 없음 | 낮음 | |
| A-C28 | Eclipse 전역 프록시 자격 증명 | `UIServiceConnectionsImpl.getGlobalProxyConfiguration` | 없음 | §2 | 낮음 | |

### 5.2 `ui` (공용 UI: 대화상자, 알림, 컨트롤)

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| A-U1 | 오류 대화상자(상세, 복사) | `StandardErrorDialog`, `BaseErrorDialog` | 있음 | `ErrorDialog`, `ErrorText` | - | |
| A-U2 | 실행 큐 오류 Stop/Retry/Skip/Skip all | `dialogs/exec/ExecutionQueueErrorDialog` | 있음 | `SwingPlatformUI.java`:271-297 | - | |
| A-U3 | 연결 끊김 대화상자 | `ConnectionLostDialog` | **있음** (2026-11-08, `DatabaseErrorDialogs.showConnectionLost`: 다시 연결 / 닫기, 다시 연결 실패면 연결 끊기 / 다시 시도 / 닫기). 조사 당시: 없음 | §3 | 중간 | A-C15와 같은 항목 |
| A-U4 | 자격 증명·비밀번호 변경·텍스트 입력·라이선스 동의 대화상자 | `BaseAuthDialog`(ui.editors.connection), `PasswordChangeDialog`, `EditTextDialog`, `AcceptLicenseDialog` | 있음 | `SwingPlatformUI.java`:302-475 | - | |
| A-U5 | 진행 대화상자 | `BaseProgressDialog` | 있음 | `ProgressDialog` | - | |
| A-U6 | 알림 팝업(+데이터베이스 알림, 알림 소리) | `notifications/*`, `NotificationSound`, `BeepSoundProvider` | 일부 | `NotificationToast`(DBPPlatformUI 알림, 2026-11-07부터 모델 알림도 `SwingModelNotifications`로, S7). 소리 없음 | 중간 | |
| A-U7 | 트레이 아이콘 알림 | `TrayIconHandler` | 일부 | `SwingSystemAgent` → 알림 창 | 낮음 | |
| A-U8 | 확인 대화상자(토글 포함), 새 메시지 상자 | `ConfirmationDialog`, `MessageBoxModern` | 일부 | `SwingPlatformUI.confirmAction`(토글 없음) | 낮음 | |
| A-U9 | 찾기·바꾸기, 자동 새로 고침, 속성 보기, 테마·글꼴 | `controls/findandreplace`, `controls/autorefresh`, `properties`, `css`, `UIFonts` | 있음 | `FindReplaceDialog`, `AutoRefreshBox`, `PropertiesPanel`, `ChromiteTheme`, `EditorFont`, `EditorColors` | - | 다른 영역에서 자세히 봄 |
| A-U10 | OAuth 브라우저 팝업 | `ui/oauth` | 없음 | Swing 검색 0 | 낮음 | |
| A-U11 | 화면 판독기 지원 | `screenreaders/*` | 없음 | Swing 검색 0 | 낮음 | |

### 5.3 `ui.app.standalone` (SWT 제품 앱)

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| A-S1 | 종료 때 저장 확인과 열린 트랜잭션 확인 | `ApplicationWorkbenchAdvisor.preShutdown`, `closeActiveTransactions` | 있음 | `MainFrame.java`:1160-1193, `ExitTransactionCheck` | - | macOS Cmd+Q는 S14 |
| A-S2 | 종료 때 실행 중인 DB 작업 취소 확인, 종료 확인 질문 | `cancelRunningTasks`(547-571), `CONFIRM_EXIT`(489-500) | **일부** (2026-11-08, [connection-lifecycle.md](../connection-lifecycle.md) "끝낼 때 실행 중인 작업": 실행 중인 Job·작업을 보이고 기다린 뒤 끝내기 / 멈추고 끝내기 / 취소(기본). 종료 확인 질문은 없음). 조사 당시: 없음 | Swing 종료 순서에 없음(`MainFrame.java`:1160-1193) | 낮음 | |
| A-S3 | 작업 공간 선택·전환, 잠긴 작업 공간 무시하고 열기 | `ChromiteWorkspaces`, `setDefaultWorkspacePath` | 없음 | swing-frontend.md:380(B단계 이후로 미룸), `ChromiteSwingApplication.java` lockWorkspace "Phase B" | 낮음 | |
| A-S4 | 인스턴스 서버(두 번째 실행이 파일을 첫 창에 넘김) | `rpc/ChromiteInstanceServer` | 없음 | 두 번째 실행은 "작업 공간을 잠글 수 없음" 오류 | 낮음 | |
| A-S5 | 명령줄 처리(SQL 번역, DB 정보, 프로젝트 연결·해제, 파일 열기 인수) | `ChromiteCommandLine`, `cli/*`(`plugin.xml`:487-501) | 없음 | swing-frontend.md:380 | 낮음 | |
| A-S6 | 다시 시작할 때 설정·작업 공간 구성 초기화 | `ResetSettingsHandler`, `loadStartupActions` | 없음 | Swing 검색 0 | 낮음 | |
| A-S7 | 진단 정보 수집(민감 정보 지움) | `CollectDiagnosticInfoHandler`, `DiagnosticInfoSanitizer` | 없음 | Swing 검색 0 | 낮음 | 지원 대응에 쓰던 기능 |
| A-S8 | 기록 지우기(브라우저 쿠키 등) | `ClearHistoryHandler` | 없음 | Swing 검색 0 | 낮음 | |
| A-S9 | About | `about/*` | 있음 | `AboutDialog` | - | |
| A-S10 | 업데이트 확인·설치 | `update/*` | 있음 | Chromite 자동 업데이트([auto-update.md](../auto-update.md)), `UpdateUi` | - | dbeaver.io 확인은 브랜딩 규칙으로 쓰지 않음 |
| A-S11 | 오늘의 팁 | `tipoftheday/*` | 없음 | Swing 검색 0. [commercial-upsell.md](../commercial-upsell.md):28-36은 상용 홍보 팁만 지운 것 | 낮음 | |
| A-S12 | 도움말(위키 도움말, 가벼운 도움말 보기) | `help/GitHubWikiHelpUI`, `LightweightHelpView` | 없음 | Help 메뉴는 About뿐 | 낮음 | |
| A-S13 | 파일 열기, 최근 파일 | `OpenLocalFileActionExt` | 있음 | File > Open File, Recent Files(`RecentFiles`) | - | |
| A-S14 | 비상 종료, 창 최소화 명령 | `EmergentExitAction`, `MinimizeWindowHandler` | 없음 | | 낮음 | |
| A-S15 | 스플래시 | `ChromiteSplashHandler` | 없음 | swt-removal.md:69(SWT 스플래시 생성 삭제) | 낮음 | |
| A-S16 | 절전 감지, Windows 인증서 저장소, 시간대, IP 스택, 프록시 서비스, 알림 처리기 | `ApplicationWorkbenchAdvisor`, `ChromiteApplication`, `CoreApplicationActivator` | 조용히 끊김 (조사 당시. S6·S7·S8 수정함, S12·S13·프록시 서비스는 남음) | S6, S7, S8, S12, S13, A-C28 | (각 항목) | |
| A-S17 | 편집기 다시 열기(다시 시작 뒤), 연결 끊을 때 편집기 유지 | `PrefPageDatabaseEditors` 키 | 있음(2026-11-08. 조사 당시: 없음) | 쿼리 창 자동 저장·복원(`QueryWindowRecovery`): 시작할 때 한 번 묻고(Restore가 기본) 연결하지 않고 엶. 연결을 끊어도 쿼리 창은 닫히지 않음(`MainFrame`은 탭 닫기에서만 창을 뺌). 조사 당시: Swing에 쿼리 창 복원 없음 | - | 켜고 끄는 설정은 없음(매번 묻고, Discard로 버림). [swing-query-window-recovery.md](../swing-query-window-recovery.md) |

### 5.4 나머지 삭제 번들

| # | 기능 | SWT 출처 | 상태 | 근거 | 영향 | 메모 |
|---|---|---|---|---|---|---|
| A-X1 | 첫 실행 제품 구성 마법사(모양, 기능 켜기, 마지막 단계) | `ui.app.config` `ProductConfigWizard` | 없음 | Swing 검색 0 | 낮음 | |
| A-X2 | 마법사의 "Data collection" 페이지(사용 통계) | `ui.statistics`(마법사 확장) | 의도적 제외 | [telemetry.md](../telemetry.md):27, 146 | - | |
| A-X3 | 샘플 데이터베이스 만들기 | `ui.config.sample` | 없음 | Swing 검색 0 | 낮음 | |
| A-X4 | 다른 도구 설정 가져오기(DbVisualizer, Navicat, SQuirreL, DataGrip, pgAdmin, SQL Workbench/J, 사용자 정의) | `ui.config.migration` | 일부 (2026-11-08, File > Import Connections > From Another Tool…: Oracle SQL Developer, Toad, CSV(사용자 정의를 대신함), Chromite 파일과 같은 미리 보기·충돌 화면. 조사 당시: 없음) | [connection-transfer.md](../connection-transfer.md) "다른 도구에서 가져오기". 읽는 코드가 모두 UI 번들에 있어 SWT와 함께 없어졌으므로 모델(`registry`, `ext.oracle`)로 옮김. DbVisualizer, Navicat, SQuirreL, DataGrip, pgAdmin, SQL Workbench/J는 남음 | 중간 | 다른 도구에서 옮겨 오는 사용자 |
| A-X5 | 사용자 정의 런처 | `launcher` | 의도적 제외 | swt-removal.md:53 "Swing 제품은 기본 `org.eclipse.equinox.launcher`를 쓴다" | - | |
| A-X6 | macOS 메뉴 통합, 마우스 휠 설정 | `ui.swt.macos` `CocoaUIService`, `PrefPageMouseConfigurator` | 없음 | S14 | 높음(Cmd+Q, 미확인) / 낮음(마우스) | |
| A-X7 | 내장 브라우저 도우미, Windows 창 테마 | `ui.swt.windows`, `ui.swt.macos` `ChromiteBrowserHelper`, `SWTThemeUtils` | 해당 없음 | SWT 위젯 전용. Swing은 FlatLaf | - | 사용자 기능 없음 |
| A-X8 | 폼 빌더 | `ui.forms` | 해당 없음 | 내부 도구(사용자 기능 없음) | - | 표에서 셈하지 않음 |
| A-P1 | SWT에서 바꾼 전역 설정 값 | `core` 설정 노드 `com.seizia.chromite.core.prefs` | 일부 | 모델 설정의 주 번들이 SWT `core`(`ChromiteActivator.java`:71)에서 `ui.swing`(`ChromiteSwingActivator.java`:65)으로 바뀜. [swing-frontend.md](../swing-frontend.md):382 "공유되지 않았다". 연결별 설정(`data-sources.json`)은 그대로 | 중간 | SWT에서 정한 트랜잭션 자동 종료, 시간 제한, 결과 최대 행 수 같은 전역 값은 Swing에서 기본값으로 돌아간다 |

## 6. SWT 환경설정 페이지 전수와 Swing 노출

`263b8ddb45`의 모든 `plugin.xml`에서 `org.eclipse.ui.preferencePages`(전역)와 `org.eclipse.ui.propertyPages`(연결별·프로젝트별, 표의 "연결별")를 모았다(서로 다른 id 62개, 한 줄에 여러 페이지를 묶은 곳이 있다).
키는 페이지 클래스가 참조하는 상수를 값으로 풀어 적었다(자동 추출 후 확인, 일부 하위 페이지·위임 클래스의 키는 빠졌을 수 있음).
"Swing" 열은 같은 키를 Swing 화면(Tools > Options, 연결 대화상자, 드라이버 관리자)에서 바꿀 수 있는지다. Swing이 내부에서 읽기만 하는 키는 "노출"로 보지 않았다.

Swing에서 바꿀 수 있는 설정: Options — 테마, 아이콘 모양, 입력 중 자동완성·지연·프로시저 제안(Swing 자체 키 `sqlEditor.completion.*`), 최대 행 수(`resultset.maxrows`),
날짜/시간 원본 형식(`resultset.format.datetime.native`), 편집기 글꼴·색, SQL 서식기(`sql.format.formatter`)와 키워드 대소문자(`sql.format.keywordCase`), 쿼리 기록 보관 일수(자체 키),
템플릿(자체 저장), Query Library 보존, 업데이트(자체 저장), AI. 드라이버 관리자 — 오프라인 모드(`ui.drivers.offline`).
연결 대화상자(연결별) — 연결 종류 선택, 자동 커밋, 기본 DB·스키마, 읽기 전용, keep-alive, 유휴 연결 닫기, 색, 초기화 SQL, SSH·프록시·SSL.

| 페이지 (id 끝부분) | SWT 번들 / 클래스 | 연결별 | 노출한 키 | Swing | 영향 |
|---|---|---|---|---|---|
| General (`general`) | core `PrefPageGeneral` | | `ui.auto.update.check`, `java.client.timezone` | 있음: 업데이트는 Options > Updates(자체 저장). 시간대는 2026-11-08 Options > Connections(키, [swing-options.md](../swing-options.md))와 시작할 때 적용(S12) | 낮음 |
| User Interface (`main`) | core `PrefPageDatabaseUserInterface` | | `ui.use.redirect.auth`, `ui.statusBar.show.statusLine`, `ui.statusBar.show.breadcrumbs`, `ui.showZoomRestartPrompt`, Colors and Fonts 링크 | 없음(같은 키). 테마·글꼴은 Swing 자체 Options | 낮음 |
| Editors (`editors`) | core `PrefPageDatabaseEditors` | | `ui.editors.reopen-after-restart`, `ui.editors.keep-editors-on-disconnect`, `ui.editors.disconnect-on-editors-close`, `navigator.refresh.editor.open`, `navigator.editor.full-name`, `navigator.editor.show.preview`, `navigator.sync.editor.datasource` | 없음 | 낮음 |
| Query Manager (`misc.qm`, 연결별 `main.qm`) | core `PrefPageQueryManager` | ✓ | `qm.maxEntries`, `qm.storeLogs`, `qm.historyDays`, `qm.objectTypes`, `qm.queryTypes`, `qm.logDirectory` | 있음(2026-11-08, Options > Logs 5키, [swing-options.md](../swing-options.md)). `qm.maxEntries`는 효과 없음. 조사 당시: 없음 | 중간 |
| Toolbar Customization | core | ✓ | 도구 막대 항목 표시 | 없음 | 낮음 |
| Accessibility | core | | `screen.reader.accessibility`, `screen.reader.force.focus` | 없음 | 낮음 |
| Errors and Timeouts (`main.errorHandle`) | ui.editors.connection `PrefPageErrorHandle` | ✓ | `connection.open.timeout`, `connection.close.timeout`, `connection.validation.timeout`, `query.rollback-on-error`, `execute.recover.enabled`, `execute.recover.retryCount`, `execute.cancel.checkTimeout` | 있음(2026-11-08, Options > Errors and Timeouts와 연결별 값, [swing-options.md](../swing-options.md)): 시간 제한 3, 자동 복구 2. 오류 때 롤백·세션 단위 취소는 트랜잭션 안전 규칙으로 두지 않음. 조사 당시: 없음(모델 기본값으로만 동작) | 중간 |
| Error Logs (`main.errorLogs`) | core `PrefPageErrorLogs` | | `logs.debug.enabled`, `logs.debug.location`, `logs.files.output.maxSize`, `logs.files.output.maxCount` | 일부(2026-11-08, Options > Logs: 크기·개수, 바로 적용, [swing-options.md](../swing-options.md)). 위치·켜기 키는 효과 없음(Swing은 항상 `.metadata/chromite-debug.log`) | 낮음 |
| Connections (`main.connections`) | core `PrefPageConnectionsGeneral` | ✓ | `navigator.settings.default.connectionPattern`, `connection.closeOnSleep`, `connection.shellAuthModelEnabled`, `connection.sanitizeDriverProperties`, `connections.useWinTrustStoreType` | 일부(2026-11-08, Options > Connections 2키, [swing-options.md](../swing-options.md)). 셸 명령 인증 허용은 Swing 연결 대화상자가 그 방식을 고를 수 없어 효과 없음, 연결 이름 패턴은 읽는 곳 없음, 절전 때 닫기는 경로 없음(A-S8). 조사 당시: 없음 | 중간 |
| Client Identification (`client.connections`) | ui.editors.connection `PrefPageConnectionClient` | | `database.meta.client.name.disable`, `database.meta.client.name.override`, `database.meta.client.name.value`, `database.connect.processEnvVars` | 있음(2026-11-08, Options > Client Identification과 연결별 값, [swing-options.md](../swing-options.md)). 조사 당시: 없음 | 낮음 |
| Transactions (`main.transactions`) | ui.editors.connection `PrefPageTransactions` | ✓ | `transaction.smart.commit`, `transaction.smart.commit.recover`, `transaction.auto.close.enabled`, `transaction.auto.close.ttl`, `transaction.show.notifications`, `transaction.ddl.behavior` | 해당 키 효과 없음(2026-11-07 확인, [swing-options.md](../swing-options.md): 스마트 커밋은 쿼리 창이 켜지 않음, 자동 종료 키는 #97에서 지움, 나머지는 읽는 곳 없음). 같은 기능은 연결 유형 편집(Options > Connection Types)과 연결 대화상자(자동 커밋, 격리 수준, 유휴 트랜잭션 알림)에 있음. 조사 당시: 없음 | 높음 |
| Metadata (`main.meta`) | ui.editors.connection `PrefPageMetaData` | ✓ | `database.meta.separate.connection`, `database.props.expensive`, `database.meta.casesensitive`, `database.meta.server.side.filters`, `database.meta.extra.ddl.info`, `database.meta.disableAdditionalRead` | 있음(2026-11-08, Options > Metadata와 연결별 값 5키, 별도 연결은 연결 대화상자, [swing-options.md](../swing-options.md)). 조사 당시: 없음(Swing은 두 키를 내부에서 읽기만) | 중간 |
| Drivers (`drivers`) | ui.editors.connection `PrefPageDrivers` | | `ui.drivers.version.update`, `driver.selector.showCommercialDrivers`, `ui.proxy.host`, `ui.proxy.port`, `ui.proxy.user`(+비밀번호), `ui.drivers.home`, `ui.drivers.sources`, `ui.drivers.offline` | 있음(2026-11-08, Options > Drivers: 폴더, 파일 원본, 버전 갱신, 프록시. 프록시 사용자·비밀번호는 보안 저장소, [swing-options.md](../swing-options.md)). 오프라인 모드는 드라이버 관리자. 조사 당시: 오프라인 모드만 | 낮음 |
| Maven (`drivers.maven`) | ui.editors.connection | | Maven 저장소 목록 | 있음(2026-11-08, Options > Drivers의 Maven 저장소: 사용 여부, 추가·편집·제거. 순서 바꾸기 없음, [swing-options.md](../swing-options.md)). 드라이버의 Maven 아티팩트 추가·편집은 드라이버 편집 창(B-C18). 조사 당시: 없음 | 낮음 |
| Classpath (`drivers.classpath`) | ui.editors.connection | | `ui.drivers.global.libraries` | 있음(2026-11-08, Driver Manager > Global Libraries…, [swing-driver-manager.md](../swing-driver-manager.md) "전역 라이브러리"). 조사 당시: 없음 | 낮음 |
| Network Profiles (전역 `globalNetworkProfiles`, 프로젝트별) | ui.editors.connection | ✓ | 네트워크 프로필(SSH·프록시 묶음) | 있음 (2026-11-08): Tools > Network Profiles…(전역·프로젝트, `NetworkProfilesDialog`)와 연결 대화상자의 Network profile 고르기([swing-conn-advanced.md](../swing-conn-advanced.md) "네트워크 프로필"). 조사 당시: 일부(읽기 전용 표시) | 낮음 |
| Connection Types (`connectionTypes`) | ui.editors.connection `PrefPageConnectionTypes` | ✓ | 연결 종류 만들기·고치기(이름, 색, 자동 커밋, 실행 확인, 데이터 변경 확인, 스마트 커밋, 유휴 트랜잭션·연결 자동 종료) | 있음(2026-11-07, Options > Connection Types, [swing-options.md](../swing-options.md)): 스마트 커밋만 뺌(효과 없음). 자동 커밋 변경은 열린 세션에 적용하며 커밋하지 않은 변경을 물음. 조사 당시: 일부(고르기만) | 중간 (운영 연결의 "실행 확인" 같은 안전 장치를 새로 정할 수 없음) |
| Navigator (`navigator`, 연결별 `Database Navigator`) | ui.navigator `PrefPageDatabaseNavigator` | ✓ | `navigator.*` 25개(호스트 표시, 설명, 통계, 정렬, 자식 수, 드라이버별 묶기, 연결 시 펼치기, 필터 복원, 긴 목록 가져오기 크기, 더블클릭 동작 등) | 일부(2026-11-08, Options > Object Explorer: 정렬 3키, [swing-options.md](../swing-options.md)). 나머지는 Swing에서 읽는 곳 없음 | 낮음 (탐색기 영역에서 자세히) |
| Confirmations | core `PrefPageConfirmations` | | `com.seizia.chromite.core.confirm.*` | 없음 | 낮음 |
| Notifications | core `PrefPageNotifications` | | `notifications.enabled`, `notifications.closeDelay`, `notifications.soundEnabled`, `notifications.soundVolume`, `agent.long.operation.notify`, `agent.long.operation.timeout` | 일부(2026-11-08, Options > Notifications: 긴 작업 알림 2키, [swing-options.md](../swing-options.md)). 알림 창 키 4개는 Swing 알림 창이 읽지 않음 | 낮음 |
| Miscellaneous | core `PrefPageMiscellaneous`(+macOS 마우스) | | `ui.show.holiday.decorations` | 없음 | 낮음 |
| Expert Settings (`network.expert`) | core `PrefPageNetworkExpert` | | `connections.debugNetworkConnections`, `connections.preferredIPType`, `connections.preferredIPAddresses` | 없음(적용 코드도 없음, S13) | 낮음 |
| Data Transfer | data.transfer.ui `PrefPageDataTransfer` | ✓ | `nameCaseMapping`, `replaceMapping`, `maxTypeLengthMapping`, `reconnectToLastDatabase`, `captureUnsavedTaskLogs`, `fallbackOutputDirectory` | 일부(2026-11-08, Options > Data Transfer와 연결별 값: 매핑 3키, [swing-options.md](../swing-options.md)). 나머지 3키는 읽는 곳 없음 | 낮음 |
| GIS Viewer | data.gis.view | | `gis.view.defaultSRID`, `gis.view.maxObjectsRender`, `gis.view.minZoomLevel` | 없음(GIS 보기 없음, swt-removal.md:106) | 낮음 |
| Altibase / CUBRID / H2 / Mimer SQL | ext.*.ui | 일부 ✓ | Altibase: `altibase.explain.plan.type`, `altibase.dbms.output`, `altibase.plan.prefix`. CUBRID: `pref.dbms.output`, `pref.dbms.output.buffer.size`, `statistic*` 4개, `oid_navigator`. H2: 허용 클래스(보안). Mimer: `mimer.confirm.drop_cascade.*`, `mimer.gis.*` 4개 | 일부(2026-11-08, Options > Altibase·CUBRID·Mimer SQL, Altibase·CUBRID는 연결별 값도, [swing-options.md](../swing-options.md)). Altibase 실행 계획 방식은 두지 않음(계획 보기가 문장을 실행). CUBRID 통계·OID 키, H2는 없음 | 낮음 |
| Oracle (`oracle.general`, 연결별 `oracle.main`) | ext.oracle.ui `PrefPageOracle` | ✓ | `oracle.explain.table`, `oracle.support.rowid`, `oracle.dbms.output`, `oracle.read.all.synonyms`, `oracle.disable.script.escape`, `oracle.meta-search-in-synonyms`, `oracle.meta-search-in-sequences`, `oracle.show-date-as-date` | 있음(2026-11-07, Options > Oracle 5키. 나머지 3키는 연결 대화상자 Provider Properties, [swing-options.md](../swing-options.md)). 2026-11-08 연결별 값(연결 대화상자 Settings). 조사 당시: 없음 | 중간 (주 대상 DB) |
| PostgreSQL | ext.postgresql.ui `PrefPagePostgreSQL` | | `show-database-statistics`, `read-keys-with-columns`, `replace-legacy-timezone`, `postgresql.dd.plain.string`, `postgresql.dd.tag.string` | 일부: 4키는 연결 대화상자 Provider Properties. `show-database-statistics`는 효과 없음(2026-11-08 확인, [swing-options.md](../swing-options.md)) | 중간 (주 대상 DB) |
| AI, Model configurations, Settings, Chat, Prompts | ui.ai `AIPreferencePage*` | ✓ | 엔진·모델 설정(구성 관리자), 채팅, 프롬프트 | 일부: Options > AI Assistant(`AiOptionsPage`, 제공자·도구 권한·MCP·감사) | 낮음 |
| DataDam, Projects | ui.datadam | | `datadam.mcp.desktop.enabled`, 동기화 프로젝트 | 의도적 제외에 준함: 삭제 전에도 어떤 제품에도 없던 기능(swt-removal.md:29) | - |
| Data Editor (`main.resultset`) | ui.editors.data `PrefPageResultSetMain` | ✓ | 24개: `resultset.maxrows`, `resultset.autofetch.next.segment`, `resultset.reread.on.scroll`, `resultset.maxrows.sql`, `resultset.automatic.row.count`, `resultset.cancel.timeout`, `sql.query.filter.force.subselect`, `resultset.edit.key.use_all_columns`, `resultset.edit.disableIfKeyMissing`, `resultset.edit.undoLevel`, `resultset.edit.new.row.after`, `resultset.edit.refreshAfterUpdate`, `keep.statement.open`, `resultset.order.mode`, `resultset.order.policy`, `resultset.fetch.size`, `resultset.filter.use.navigator`, `resultset.confirm.beforeSave`, `resultset.show.errorDialog`, `resultset.mark.cell.value.occurrences`, `resultset.column.label.ignore`, `resultset.image.browser`, `resultset.datetime.editor` 등 | 일부: 최대 행 수, 저장 전 스크립트 표시, 가져오기 크기(2026-11-07, 2026-11-08부터 Options > Query Results와 연결별 값), 2026-11-08 오후 읽기와 정렬 5키(SQL에 행 수 제한, 끝까지 내리면 더 읽기, 열 정렬 위치, 필터할 때 감싸기, 열 이름). 정렬 정책과 나머지는 효과 없음([swing-options.md](../swing-options.md)) | 중간 |
| Binary Editor | ui.editors.data `PrefPageResultSetEditors` | ✓ | `resultset.string.use.content.editor`, `content.memory.maxsize`, `resultset.binary.representation`, `resultset.binary.editor`, `resultset.binary.stringMaxLength`, `content.hex.encoding`, `content.cache.*` 3개, `resultset.edit.longaslob`, `resultset.edit.maxtextsize`, `resultset.commit.oneditapply`, `resultset.commit.oncontentapply` | 일부(2026-11-07): 이진 값 표시 형식·길이(Options > Query Results, 2026-11-08부터 연결별 값도). `content.*` 5키는 2026-11-08 오후 Options > Binary and LOB([swing-options.md](../swing-options.md)), 나머지는 효과 없음 | 낮음 |
| Appearance / Grid / Plain text | ui.editors.data | ✓ | 결과 화면 표시 38개(필터 패널, 열 너비, 숫자 오른쪽 정렬, 홀수 행 색, 불리언 체크 상자, 공백 문자 표시, 텍스트 모드 구분자 등) | 없음 | 낮음 |
| Data Formats | ui.editors.data `PrefPageDataFormat` | ✓ | 형식 프로필, `resultset.format.datetime.native`, `resultset.format.numeric.native`, `resultset.format.numeric.scientific` | 있음: 날짜/시간·숫자 원본 형식, 과학적 표기(2026-11-07, Options > Query Results), 형식 프로필(로캘, 형식별 패턴: 2026-11-08 Options > Data Formats), 둘 다 연결별 값 있음. 이름 있는 프로필 관리는 두지 않음(Swing은 내보내기 작업 설정에서만 읽음, [swing-options.md](../swing-options.md)) | 중간 |
| Dictionaries | ui.editors.data `PrefPageDataViewer` | ✓ | `resultset.reference.value.description.column.patterns`, `dictionary.max.rows` | 해당 키 효과 없음(2026-11-08 확인: 사전 화면이 Swing에 없음, [swing-options.md](../swing-options.md)) | 낮음 |
| Diagram Editor, ERD Settings | ui.editors.erd `ERDPreferencePage` | ✓ | `erd.*` 14개(설명 지연 읽기, 뷰·파티션 표시, 격자, 인쇄 여백 등) | 없음 | 낮음 |
| HEX Editor | ui.editors.hex | | `hex.font.*` 3개, `default.hex.width` | 없음(HEX 편집기 없음) | 낮음 |
| SQL Terminal | ui.editors.sql.terminal | ✓ | `SQLEditor.ConsoleView.showConsoleViewByDefault`, `SQLEditor.SQLTerminal.showQueryText`, `SQLEditor.SQLTerminal.showServerOutput` | 일부(2026-11-08: 터미널은 Query > SQL Terminal로 옮김, 이 설정 키 3개는 없음, [swing-sql-terminal.md](../swing-sql-terminal.md)) | 낮음 |
| SQL Editor | ui.editors.sql `PrefPageSQLEditor` | ✓ | `database.editor.separate.connection`, `database.editor.connect.on.activate`, `database.editor.connect.on.execute`, `SQLEditor.autoSave*` 4개, `SQLEditor.resultSet.*` 4개, `SQLEditor.outputPanel.autoShow` | 일부: `database.editor.separate.connection`(2026-11-07, Options > Query Execution과 연결 대화상자, [transactions.md](../transactions.md) "쿼리 창마다 별도 연결") | 중간. 조사 당시: 편집기별 별도 연결을 정할 수 없음 |
| Code Editor | ui.editors.sql `PrefPageSQLCodeEditing` | ✓ | 접기, 단어 이동, 같은 단어 표시, 문제 표시, 의미 분석 3개, 따옴표·괄호 자동 닫기 3개, 키워드 대소문자 자동, 원본에서 서식 추출 | 없음 | 낮음 |
| Code Completion | ui.editors.sql `PrefPageSQLCompletion` | ✓ | `SQLEditor.ContentAssistant.*` 21개 + `sql.proposals.insert.table.alias` | 일부: 자동 활성·지연·프로시저 제안(Swing 자체 키 `sqlEditor.completion.*`, `SqlCompletionPopup.java`:94-104), 2026-11-08 Options > Code Completion(테이블 별칭, 짧은·전체 이름, 연결별 값, [swing-options.md](../swing-options.md)) | 낮음 |
| Scripts (`sql.resources`) | ui.editors.sql `PrefPageSQLResources` | ✓ | `script.auto.folders`, `script.auto.connection.folders`, `script.title.pattern`, `script.file.name.pattern`, `SQLEditor.script.bigFileLengthBoundary`, `new.script.template(.enabled)`, `SQLEditor.script.bind.*` 4개, `script.auto.connection.attach`, `script.delete.empty` | 없음 | 낮음 |
| Formatting | ui.editors.sql `PrefPageSQLFormat` | ✓ | `sql.format.formatter`, `sql.format.keywordCase`, `SQLEditor.format.boldKeywords`, `SQLEditor.format.activeQuery` | 일부: 서식기·키워드 대소문자 | 낮음 |
| SQL Processing (`sqlexecute`) | ui.editors.sql `PrefPageSQLExecute` | ✓ | 26개: `statement.timeout`, `script.commit.type`, `script.error.handling`, `script.commit.lines`, `script.fetch.resultset`, `script.sql.delimiter*` 4개, `sql.parameter.*` 6개, `sql.variables.enabled`, `SQLEditor.*` 실행 후 동작 8개 등 | 일부(2026-11-07, Options > Query Execution, [swing-options.md](../swing-options.md)): 구분 기호 4키, 매개 변수 7키, 오류 처리(멈춤 / 건너뛰고 계속, 트랜잭션은 그대로). 커밋 방식은 트랜잭션 안전 규칙으로 두지 않음, 나머지는 효과 없음. 2026-11-08 연결별 값(연결 대화상자 Settings). 조사 당시: 없음 | 중간 (스크립트 커밋 방식·오류 처리·문장 시간 제한을 정할 수 없음) |
| Templates | ui.editors.sql `PrefPageSQLTemplates` | ✓ | Eclipse 템플릿 저장소(`com.seizia.chromite.core.sql_templates`) | 일부: Options > Templates(Swing 자체 저장, `SqlTemplatesPage`) | 낮음 |
| Project Resources, File details | ui.navigator | 프로젝트별 | 자원 폴더 위치, 파일 세부 | 없음 | 낮음 |

## 7. 집계

§5 표(조용히 끊김은 §1의 S1-S14로 한 번만 셈, §5에서 같은 내용을 가리키는 A-C16·A-C17·A-C18·A-S16과 내부 도구 A-X7·A-X8은 빼고 셈):

| 상태 | 수 |
|---|---|
| 있음 | 11 (A-C21, A-C22, A-U1, A-U2, A-U4, A-U5, A-U9, A-S1, A-S9, A-S10, A-S13) |
| 일부 | 14 (A-C5, A-C6, A-C8, A-C9, A-C10, A-C14, A-C15, A-C20, A-C23, A-U6, A-U7, A-U8, A-P1, A-X4(2026-11-08)) |
| 없음 | 32 (A-C1~A-C4, A-C7, A-C11~A-C13, A-C19, A-C24~A-C28, A-U3, A-U10, A-U11, A-S2~A-S8, A-S11, A-S12, A-S14, A-S15, A-S17, A-X1, A-X3, A-X6) |
| 의도적 제외 | 2 (A-X2 사용 통계 페이지, A-X5 런처) |
| 조용히 끊김 | 14 (S1~S14, 조사 당시. 그 뒤 S1·S2·S3·S4·S5·S6·S7 수정함, S14는 수정했지만 macOS 실행 확인 필요. S10·S11은 안전 쪽으로 실패) |

§3의 `DBPPlatformUI` 차이(`openEntityEditor`, HTML 복사, `readAndDispatchEvents`, `createLoadingService`)는 모두 일부·낮음이고 위 수에 넣지 않았다.

환경설정 페이지(§6): 서로 다른 페이지 id 62개(전역과 연결별이 같은 id면 하나로 셈) 중 Swing에서 같은 설정을 바꿀 수 있는 것은 **일부 노출 15개, 전혀 없음 45개, 의도적 제외에 준함 2개(DataDam)**다.
연결별(`propertyPages`) 설정 화면은 Swing에 하나도 없다. 연결 대화상자의 Options는 연결 설정(`DBPConnectionConfiguration`)만 다루고 연결별 설정 저장소(`DataSourcePreferenceStore`)는 다루지 않는다.
2026-11-07 갱신: 키 단위로 다시 셌다([swing-options.md](../swing-options.md) 부록). 키 306개 중 Swing에서 효과가 있는 것은 109개이고, 그중 화면이 있는 것은
추가함 22 + 이미 있음 7 + 공급자 속성 7, 규칙상 두지 않음 2, 남은 것(다음 작업) 71개다. 나머지 197개는 Swing에서 읽는 곳이 없어 화면을 만들지 않는다.
2026-11-08 갱신: 연결별 설정 화면을 만들었다(연결 대화상자 Options > Settings: Query Execution, Query Results, Data Formats, Errors and Timeouts, Metadata,
Client Identification, Oracle). Errors and Timeouts·Metadata·Client Identification·데이터 형식 프로필을 더해 추가함 37, 규칙상 두지 않음 4, 다음 작업 55개다.
같은 날 전역 라이브러리(`ui.drivers.global.libraries`)를 Driver Manager > Global Libraries…로 만들어 추가함 38, 다음 작업 54개다.

## 8. 영향이 큰 순서 (없음·조용히 끊김)

1. **S3** 연결 끊기·다시 연결·삭제 때 열린 트랜잭션을 묻지 않고 롤백(트랜잭션 안전 규칙 위반, 매일 쓰는 명령). 2026-11-07 수정함(#97).
2. **S1/S2** 유휴 트랜잭션 롤백·유휴 연결 끊기가 동작하지 않음(알려진 건). 고칠 때 S3의 확인과 S7의 알림이 같이 있어야 "묻지 않는 롤백"이 새로 생기지 않는다. 2026-11-07 S1 수정함(#97), S2 수정함([idle-transactions.md](../idle-transactions.md) "유휴 연결 닫기").
3. **S5** 비밀번호를 저장하지 않은 SQL Server(SQL 인증)·Oracle 연결이 질문 없이 취소됨. 2026-11-07 실행으로 확인한 뒤 수정함([swing-auth-prompts.md](../swing-auth-prompts.md)).
4. **S4** 연결 이벤트·셸 인증·내보내기 후 셸 명령이 승인 없이 실행됨(보안). 2026-11-07 수정함([shell-command-approval.md](../shell-command-approval.md)).
5. **A-C1/A-C3/A-C2** 커밋·롤백 명령, 보류 트랜잭션 창, 열린 트랜잭션 표시가 없음(수동 커밋 연결을 안전하게 쓰기 어렵다). 2026-11-07 A-C2 수정함(#97), A-C1·A-C3·A-C4 수정함([transactions.md](../transactions.md)).
6. **S6** Windows 인증서 저장소 기본 사용이 빠져 사내 CA SSL 연결이 실패할 수 있음.
7. **S14** macOS Cmd+Q가 저장·트랜잭션 확인을 건너뛸 가능성(실행 확인 필요).
8. **Transactions·Connection Types 설정 화면 없음**(§6) — 트랜잭션 자동 종료 시간, 스마트 커밋, 운영 연결의 실행 확인 같은 안전 설정을 정할 수 없음.
   2026-11-07 연결 유형 편집(Options > Connection Types)을 더함. Transactions 페이지의 키는 Swing에서 효과가 없음을 확인([swing-options.md](../swing-options.md)).
9. **A-P1** SWT에서 정한 전역 설정이 Swing으로 넘어오지 않음.
10. **S7** 모델 알림(롤백·끊김·재검증)이 콘솔에만 찍힘. 2026-11-07 수정함([idle-transactions.md](../idle-transactions.md) "모델 알림").

## 9. 확인하지 못한 것

- 빌드·실행을 하지 않았다. S5, S6, S9, S14는 코드만으로 판단했다. 특히 S5는 실제로 대화상자 없이 끝나는지, S14는 macOS에서 Cmd+Q가 `System.exit`으로 가는지 실행해서 확인해야 한다.
- S9(BouncyCastle)는 어떤 기능이 깨지는지 호출 경로를 찾지 못했다.
- §6의 키 목록은 페이지 클래스가 직접 참조하는 상수를 자동으로 풀어 만든 것이다. 상위 클래스·위임 클래스·구성 관리자 화면 안의 키는 빠졌을 수 있다.
- 다른 영역(SQL 편집기, 결과 표, 탐색기, 데이터 전송)과 겹치는 항목(A-C10, §6의 편집기·결과 페이지)은 해당 영역 보고서에서 더 자세히 봐야 한다.
