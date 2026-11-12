# 플랫폼 시작·종료: Windows 인증서 저장소, macOS 메인 스레드와 앱 메뉴, JCE 공급자, 시간대, IP 스택

## 목적

SWT 앱이 시작할 때 하던 플랫폼별 처리 두 가지가 Swing 앱에 빠져 있었다([SWT → Swing 조사](swt-parity-audit.md) 2순위, A-S6·A-S14).

- **Windows 인증서 저장소(A-S6)**: SWT `ChromiteApplication.start`는 설정 "Windows 신뢰 저장소 사용"이 켜져 있으면
  `javax.net.ssl.trustStoreType=WINDOWS-ROOT`로 두어, Windows에 설치된 인증서(사내 CA 등)로 서버 인증서를 확인했다.
  Swing 앱은 이 설정을 하지 않아 JDK 기본 저장소(`cacerts`)만 썼다. 사내 CA 인증서를 Windows에만 설치한 환경에서
  SSL 연결이 PKIX 오류로 실패할 수 있었다.
- **macOS 끝내기(A-S14)**: JDK의 macOS 기본 끝내기 동작은 `System.exit`이다. 그래서 Cmd+Q(앱 메뉴의 끝내기)가
  메인 창 닫기 경로(저장 질문, 열린 트랜잭션 질문 `ExitTransactionCheck`)를 건너뛸 수 있었다. 앱 메뉴의 정보·환경설정도
  JDK 기본(자바 정보 창, 동작 없음)이었다.
- **BouncyCastle JCE 공급자(A-S9, 2026-11-08)**: SWT `DesktopPlatform.initialize`는 설정 `security.jce.bc`(기본 켬)가 켜져 있으면 BouncyCastle 공급자를
  등록했다(`SecurityProviderUtils.registerSecurityProvider`). Swing 플랫폼은 하지 않았다.
- **클라이언트 시간대(A-S12, 2026-11-08)**: SWT `ChromiteApplication.start`는 설정 `java.client.timezone`이 기본값(`Default`)이 아니면
  JVM 기본 시간대를 그 시간대로 바꿨다(`TimezoneRegistry.overrideTimezone`). Swing 앱은 하지 않아 설정이 효과가 없었다.
- **IP 스택·네트워크 디버그(A-S13, 2026-11-08)**: SWT `ChromiteApplication.initializeApplication`은 설정 `connections.preferredIPType`·
  `connections.preferredIPAddresses`(IPv4 / IPv6 / Auto)로 `java.net.preferIPv4Stack`·`java.net.preferIPv6Addresses`를, `connections.debugNetworkConnections`로
  `javax.net.debug=all`을 정했다. Swing 앱은 하지 않았다.

## 구조

모두 `plugins/com.seizia.chromite.ui.swing`에 있다.

| 클래스·메서드 | 하는 일 |
|---|---|
| `ChromiteSwingApplication.useWindowsTrustStore` | Windows이고 설정 `connections.useWinTrustStoreType`(`ModelPreferences.PROP_USE_WIN_TRUST_STORE_TYPE`, Windows 기본 켬)이 켜져 있으며, 명령줄에 `javax.net.ssl.trustStore`·`trustStoreType`이 없으면 `javax.net.ssl.trustStoreType=WINDOWS-ROOT`로 둔다. 적용 결과를 디버그 로그에 남긴다(`SSL trust store type: ...`) |
| `ChromiteSwingApplication.installDesktopHandlers` | `java.awt.Desktop`이 지원하는 경우에만 끝내기·정보·환경설정 처리기를 등록한다. macOS에서 런처가 첫 스레드에서 시작했으면 `MacOSAppMenu`로 앱 메뉴와 끝내기 요청도 둔다 |
| `ChromiteSwingApplication.start` | macOS 첫(AppKit) 스레드에서 불리면 본문(`run`)을 `MacOSMainThread.runApplication`에 맡긴다. 그 밖에는 바로 `run`. 두 경로 모두 `runWithSplash`를 거쳐 시작이 어떻게 끝나도 시작 화면을 닫는다 |
| `StartupSplash` (2026-11-08) | Look and Feel 직후 런처 시작 화면을 이어받는 Swing 시작 화면. 띄운 뒤 `run`이 `applicationRunning`으로 런처 시작 화면을 내린다(Windows는 창을 만든 첫 스레드, macOS는 런처가 AppKit 스레드로 넘김). [branding.md](branding.md) "시작 화면" |
| `MacOSMainThread` (새) | 앱 본문을 스레드 `Chromite main`에서 돌리고 첫 스레드는 CoreFoundation 실행 루프를 돈다. 본문이 끝나면 OSGi 프레임워크를 멈추고 결과 코드로 끝낸다(아래 "macOS 메인 스레드") |
| `MacOSAppMenu` (새) | 앱 메뉴(정보, 옵션, 가리기, 기타 가리기, 모두 보기, 끝내기)와 앱 위임자(`applicationShouldTerminate:`)를 JNA·Objective-C 런타임으로 만든다(아래 "macOS 앱 메뉴"). 메인 창을 보이기 전에 그 작업이 끝나기를 기다린다(최대 5초, 화면 메뉴 막대의 창 메뉴가 새 메인 메뉴로 지워지지 않게, [mac-ui.md](mac-ui.md)) |
| `product/swing/pom.xml` `macos-info-plist` | 제품을 만든 뒤 macOS `Info.plist`를 고친다(아래 "macOS Info.plist") |
| `ChromiteSwingApplication.configureNetwork` (2026-11-08) | IP 스택·주소 선호(Auto면 JDK 기본 그대로)와 네트워크 디버그 시스템 속성. 모델 설정 저장소(`ModelPreferences.getPreferences`)에서 읽는다 |
| `ChromiteSwingApplication.run`의 `TimezoneRegistry.overrideTimezone()` (2026-11-08) | 플랫폼을 만든 직후(플랫폼 설정 저장소를 읽으므로), 연결·날짜 서식보다 앞 |
| `ChromiteSwingPlatform.initialize` (2026-11-08) | 맨 앞에서 `security.jce.bc`의 기본값(켬)을 정하고, 켜져 있으면 BouncyCastle 공급자를 등록한다(SWT `DesktopPreferencesInitializer`의 기본값과 같음) |

`com.seizia.chromite.ui.swing`의 `Require-Bundle`에 `com.sun.jna`를 더했다(이미 제품에 있는 번들, `model.security`가 쓴다).

### 네트워크·시간대·JCE를 정하는 시점

- JDK는 `java.net.preferIPv4Stack`·`preferIPv6Addresses`를 소켓을 처음 만들 때 읽는다. 그래서 신뢰 저장소와 같은 자리(플랫폼을 만들기 전)에서 정한다. Windows는 첫 스레드에서, macOS는 `MacOSMainThread`의 작업 스레드에서 같은 `run`을 지나므로 두 경로 모두 연결보다 앞이다(AWT 초기화는 소켓을 열지 않는다).
- 시간대는 플랫폼 설정 저장소(`DBWorkbench.getPlatform().getPreferenceStore()`)에서 읽으므로 플랫폼을 만든 직후, 연결 감시(`postInitialize`)와 메인 창보다 앞에서 바꾼다.
  설정 화면(Options의 시간대 칸)은 다른 작업(설정 화면 나머지)이 만든다. 이 문서는 시작할 때 적용하는 부분만 맡는다.
- BouncyCastle은 인증서 저장소·작업 공간보다 먼저 등록한다(SWT와 같은 순서). Chromite 코드에는 공급자 이름("BC")으로 찾는 곳이 없지만, 같은 JVM의 라이브러리
  (SSH 구현, JDBC 드라이버의 키 읽기)가 등록된 공급자를 쓸 수 있어 SWT와 같은 실행 환경을 맞춘다.

### 신뢰 저장소를 정하는 시점

JDK는 `javax.net.ssl.trustStoreType`을 기본 SSL 문맥을 처음 만들 때 한 번 읽는다. 그래서 설정 저장소를 읽을 수 있게 된 직후
(작업 공간 잠금, `initPreferences`, 디버그 로그 시작 뒤), 플랫폼을 만들기 전(`DBWorkbench.getPlatform`)에 정한다.
연결과 업데이트 확인(`UpdateService.start`, 메인 창이 뜬 뒤)은 모두 이보다 뒤다. 설정 값은 앱의 설정 저장소
(`getPreferenceStore`, 모델 설정과 같은 번들 노드)에서 읽는다. SWT·헤드리스 앱과 같은 키, 같은 기본값이다.

`WINDOWS-ROOT`는 `jdk.crypto.mscapi` 모듈(공급자 `SunMSCAPI`)이 제공한다. 내장 런타임은 Windows용 jlink 모듈 목록
(`tools/jre/jbr.properties`의 `windows-x64.modules`)에 이미 이 모듈을 넣고 있어 바꿀 것이 없다([runtime.md](runtime.md) "모듈").

### macOS 메인 스레드

Eclipse 런처는 macOS에서 JVM을 언제나 같은 프로세스의 첫(AppKit 메인) 스레드에서 시작하고(SWT가 그 스레드를 써야 해서),
`JAVA_STARTED_ON_FIRST_THREAD_<pid>`를 둔다. Equinox는 그 스레드에서 `ChromiteSwingApplication.start`를 부른다. AWT는 초기화와
창 작업을 그 스레드의 실행 루프에 맡기고 기다리므로, `start`가 Swing 작업(`invokeAndWait`)을 기다리면 서로 기다려 창이 뜨지 않았다
(v0.1.1, 아래 "macOS 확인 기록").

`MacOSMainThread`는 JDK `java` 런처(libjli `MacOSXStartup`·`ParkEventLoop`)와 같은 배치로 바꾼다.

1. `pthread_main_np()`로 첫 스레드인지 본다(macOS에서만). 아니면(Windows 등) 아무것도 바꾸지 않는다.
2. AWT가 읽기 전에 `JAVA_STARTED_ON_FIRST_THREAD_<pid>`를 지운다(`unsetenv`). 남아 있으면 AWT가 SWT용 내장 모드로 시작해
   AppKit 이벤트 루프를 돌리지 않는다.
3. 앱 본문(`run`)을 새 스레드 `Chromite main`(데몬 아님)에서 돌린다.
4. 첫 스레드는 `CFRunLoopRunInMode(kCFRunLoopDefaultMode)`를 되풀이한다. AWT가 시작 블록을 그 루프에 맡기면 그 안에서 앱의
   이벤트 루프(`[NSApp run]`)가 돌고 다시 돌아오지 않는다.
5. 첫 스레드는 Equinox로 돌아가지 못하므로 본문이 끝나면 `Chromite main`이 Equinox가 하던 마무리를 한다: 시스템 번들(`Framework`)을
   멈추고(`stop`, `waitForStop` 최대 60초) 결과 코드(`Integer` 결과, 아니면 0, 예외면 13)로 `System.exit`. JDK 런처도 둘째 스레드에서
   `exit`로 끝난다. `EclipseStarter.shutdown`은 쓰지 않는다: 실행 중인 앱(돌아오지 않는 첫 스레드)을 1분 기다린다
   (`EclipseAppLauncher.shutdown`의 `runningLock.tryAcquire(1, MINUTES)`).

자동 업데이트 다시 시작은 바뀌지 않는다: 도우미가 이 프로세스(pid)가 끝나기를 기다린 뒤 앱을 바꾸고 `/usr/bin/open`으로 다시 띄운다
(`UpdateApplier`). 런처의 다시 시작 코드(23·24)는 Chromite가 쓰지 않는다.

### macOS 앱 메뉴

런처는 JVM을 올리기 전에 Apple event 처리기를 두면서 평범한 `NSApplication`을 만든다(`eclipseCocoa.c`의 `installAppleEventHandler`,
`reuseWorkbench`에서 언제나). 그래서 AWT는 자기 `NSApplicationAWT`를 만들지 못하고 그 앱을 돌리며, 앱 메뉴도 앱 위임자도 두지 않는다
(디버그 로그 `macOS application NSApplication, delegate none`). 위임자가 없으면 Cmd+Q·끝내기 Apple event·Dock의 끝내기가 Java 끝내기
처리기를 거치지 않고 프로세스를 바로 끝낸다(수정 중 실행에서 확인: `Main window closed` 없이 끝남). JDK의 `AWT_OVERRIDE_NSDELEGATE`도
JBR 25에서는 위임자를 두지 않았다.

`MacOSAppMenu`가 이 둘을 맡는다(AppKit 스레드에서, main dispatch queue로).

- 앱 메뉴: "Chromite 정보"(`main_about`), "옵션"(`main_options`, Cmd+,), "Chromite 가리기"(Cmd+H), "기타 가리기"(Opt+Cmd+H),
  "모두 보기", "Chromite 종료"(Cmd+Q, `terminate:`). 가리기·끝내기는 대상 없이 응답자 사슬로 `NSApp`에 간다.
- 앱에 위임자가 없으면 런타임에 만든 Objective-C 클래스 `ChromiteAppMenuTarget`의 객체를 위임자로 둔다. `applicationShouldTerminate:`는
  `requestQuit`(창 닫기 단추와 같은 `WINDOW_CLOSING`)을 EDT에 맡기고 `NSTerminateCancel`을 돌려준다. `Desktop` 끝내기 처리기와 같은
  동작이다(끝내기 요청은 취소하고 앱은 자기 경로로 끝남). 정보·옵션도 이 객체가 받아 `AboutDialog`·`OptionsDialog`를 EDT에서 연다.
- AWT는 창이 활성화될 때 메인 메뉴의 0번(앱 메뉴)만 남기고 지운다(`CMenuBar clearMenuBar`). 그래서 앱 메뉴가 남는다.

`Desktop` 처리기(아래)는 AWT가 앱을 가진 경우(런처 밖 실행)를 위해 그대로 둔다.

- **끝내기**: `Desktop.setQuitHandler`. 처리기는 EDT에서 메인 창에 창 닫기 단추와 같은 `WINDOW_CLOSING` 이벤트를 보내고,
  JDK의 끝내기 요청은 항상 취소한다(`QuitResponse.cancelQuit`). 사용자가 끝내기로 하면 창이 닫히고 앱은 창을 닫을 때와 같은
  정상 경로(`start`의 래치 → 플랫폼 정리 → 업데이트 적용 → 반환)로 끝난다. 사용자가 취소하면 창이 남고 앱도 계속 돈다.
- **정보**: `Desktop.setAboutHandler` → 도움말 메뉴와 같은 `AboutDialog`.
- **환경설정**: `Desktop.setPreferencesHandler` → 도구 메뉴와 같은 `OptionsDialog`.
- 각각 `Desktop.isSupported(Action.APP_QUIT_HANDLER / APP_ABOUT / APP_PREFERENCES)`일 때만 등록한다. Windows는 셋 다
  지원하지 않으므로(아래 확인 기록) 등록되지 않고 동작이 바뀌지 않는다.

### macOS Info.plist

p2(`BrandingIron`)는 앱 이름을 런처 이름에서 만들고("Chromite-swing") Eclipse 런처 틀의 값을 남긴다. `product/swing/pom.xml`의
`maven-antrun-plugin` 실행 `macos-info-plist`가 `materialize-products` 뒤(같은 `package` 단계, 뒤에 선언), 서명 전에 고친다.

| 키 | 전 | 후 |
|---|---|---|
| `CFBundleName`, `CFBundleDisplayName` | `Chromite-swing` | `Chromite`(메뉴 막대, Dock, Finder, 시스템 대화상자) |
| `CFBundleGetInfoString` | `Eclipse 4.41 for Mac OS X, Copyright IBM Corp. …` | 지움 |
| `Eclipse`(런처 인수 배열) | `-keyring ~/.eclipse_keyring` | 지움. 이 인수는 옛 Platform 인증 저장소(`InternalPlatform` → `AuthorizationHandler.setKeyringFile`)만 읽고 Chromite는 쓰지 않는다. 보안 저장소는 `-eclipse.keyring`만 읽는다(`SecurePreferencesMapper`, [credential-storage.md](credential-storage.md)) |
| `NSRequiresAquaSystemAppearance` | `true` | 지움(시스템 어두운 모드가 창 테두리에 닿음) |

`CFBundleIdentifier`는 서명·업데이트·배포 파일이 쓰므로 그대로다. `CFBundleExecutable`은 2026-11-12에 `chromite-swing`에서 `Chromite`로 바뀌었다(런처 이름, [launcher-name.md](launcher-name.md)). 앱 폴더 이름은 2026-11-09에 `ChromiteSwing.app`에서 `Chromite.app`으로 바꿨다([distribution.md](distribution.md) "macOS 앱 이름").
고친 뒤 값이 예상과 다르면(틀이 바뀌어 정규식이 맞지 않으면) 빌드가 실패한다(`<fail>` 조건).

## 설계 결정과 이유

- **끝내기 전략 대신 처리기**: `QuitStrategy.CLOSE_ALL_WINDOWS`는 모든 창(떠 있는 도구 창 포함)에 닫기 이벤트를 보내므로
  메인 창 하나의 질문 경로만 타게 하려면 처리기가 더 정확하다.
- **끝내기 요청은 항상 취소**: `performQuit`를 부르면 JDK가 바로 `System.exit`을 해 플랫폼 정리(연결 닫기, 작업 공간 저장)와
  끝날 때 업데이트 적용을 건너뛸 수 있다. 앱이 스스로 끝나는 정상 경로를 그대로 쓰기 위해 취소하고 창 닫기만 보낸다.
  예상되는 단점(확인 안 함): macOS 로그아웃·종료 중에 Chromite가 끝나면 macOS는 "앱이 로그아웃을 취소했다"고 보고, 사용자가 로그아웃을 다시 해야 한다.
- **`MainFrame`을 고치지 않음**: 창 닫기 이벤트를 그대로 보내면 `MainFrame`의 창 닫기 처리(`requestExit`)를 그대로 지난다.
  공유 파일 변경을 줄이고, 닫기 단추와 Cmd+Q가 같은 코드를 지난다는 것이 분명해진다.
- **화면 메뉴 막대(`apple.laf.useScreenMenuBar`)는 넣지 않음**: 요청 범위 밖이고 메뉴 배치가 바뀐다.
- **macOS 시작은 Java 쪽에서 고침**: 다른 방법을 실행으로 확인했다(ci.md "macOS 설치·실행 시험"). 런처의 `--launcher.secondThread`는 macOS 15에서
  런처가 AppKit 단정으로 죽는다. 런처는 macOS에서 언제나 JVM을 같은 프로세스에 올린다(`eclipseCocoa.c` `startJavaVM` → `startJavaJNI`,
  `-vm …/bin/java`를 줘도 같은 프로세스). 별도 `java` 프로세스로 띄우려면 런처를 바꾸거나 새로 만들어야 하고 `jre/bin/java`에 JIT 권한
  서명도 필요하다. Java 쪽 수정은 서명한 앱 하나를 그대로 두고, 권한(entitlements)도 바꾸지 않는다: JVM은 계속 런처 프로세스 안에 있고
  JNA 콜백(Objective-C 메서드, dispatch 함수)은 이미 있는 `allow-unsigned-executable-memory`로 돈다.
- **앱 메뉴 문구**: 앱의 도구 메뉴와 같은 "옵션"을 쓴다(macOS 기본 이름 "설정…" 대신).

## upstream 대비 변경점

- upstream(DBeaver)의 SWT 앱 시작 처리(`ChromiteApplication.start`의 신뢰 저장소)를 Swing 앱에 옮겼다. 조건과 키는 같다.
- 2026-11-08: `DesktopPlatform.initialize`의 BouncyCastle 등록, `ChromiteApplication.start`의 시간대, `initializeApplication`의 네트워크 설정도 같은 조건·키로 옮겼다.
  `initializeApplication`의 Windows Defender 정책 처리는 옮기지 않았다(SWT 설치 정책 전용).
- macOS 메뉴 통합은 upstream `ui.swt.macos`(SWT Cocoa)가 맡았다. Swing에서는 `java.awt.Desktop` API로 새로 구현했다.
- macOS에서 Swing 앱을 첫 스레드 밖에서 돌리는 `MacOSMainThread`, 런처의 `NSApplication`에 앱 메뉴·위임자를 두는 `MacOSAppMenu`, `Info.plist` 고치기는
  Chromite에서 새로 만들었다(upstream은 SWT라 첫 스레드에서 돈다).

## 확인 기록 (2026-11-07, Windows 11, 내장 런타임 JBR 25.0.4.1)

증거: `D:\Tools\_scratch\chromite-platform-startup\`.

- 시작 로그(`startup-debug.log`): `SSL trust store type: WINDOWS-ROOT (default pkcs12)`. 실행 중인 앱에 `jcmd VM.system_properties`:
  `javax.net.ssl.trustStoreType=WINDOWS-ROOT`(`jcmd-system-properties.txt`).
- 설정을 끈 작업 공간(`connections.useWinTrustStoreType=false`)에서는 `SSL trust store type:  (default pkcs12)`(`run-pref-off-debug.log`).
- 내장 런타임에서 `WINDOWS-ROOT` 키 저장소: 공급자 `SunMSCAPI`, 인증서 74개. 같은 런타임에서 Chromite `UpdateHttp.fetchFeed`로
  `https://chromite.seizia.com/downloads/releases.json`을 기본·`WINDOWS-ROOT` 둘 다 받음(`probe-default.txt`, `probe-windows-root.txt`).
  앱 안에서 업데이트 확인 결과는 로그에 남지 않아 앱 실행으로는 확인하지 못했다.
- 같은 내장 런타임과 내장 mssql-jdbc 13.4.0으로 Docker SQL Server에 `encrypt=true;trustServerCertificate=true`(시험 연결과 같은 설정)
  연결: 기본·`WINDOWS-ROOT` 둘 다 연결, `encrypt_option=TRUE`. 인증서 확인을 켜면(`trustServerCertificate=false`) 자체 서명 인증서라
  둘 다 PKIX 오류(예상대로)(`probe-mssql-tls.txt`).
- 앱에서 시험 SQL Server 연결은 연결됨(`02-connect.png`). 다만 이 연결 설정은 암호화하지 않는다(서버 `sys.dm_exec_connections`의
  `encrypt_option=FALSE`, `mssql-encrypt-option.txt`). SSL 연결 자체는 위 확인으로 대신했다.
- Windows에서 `Desktop.isSupported`: `APP_QUIT_HANDLER=false APP_ABOUT=false APP_PREFERENCES=false` → 처리기 등록 안 됨.
  메인 창에 `WM_CLOSE`를 보내면 `Main window closed` → 플랫폼 정리 → 정상 종료(`run-debug.log`).
- 열린 트랜잭션이 있을 때 창 닫기 질문은 실행으로 확인하지 못했다(다른 에이전트의 앱과 입력이 겹쳐 전역 키보드·마우스 입력을 멈춤).
  이 변경은 `MainFrame.requestExit`와 `ExitTransactionCheck`를 고치지 않았고, Windows에서는 새 처리기가 등록되지 않으므로 코드 경로는 그대로다.
- macOS 확인은 아래 "macOS 확인 기록"에 있다.

- 2026-11-08 macOS 시작 수정 뒤 로컬 빌드(`mvn -B clean package … -Pproduct-chromite-swing` BUILD SUCCESS)를 `CHROMITE_DATA`·작업 공간을 비공개 폴더로 두고 실행:
  `SSL trust store type: WINDOWS-ROOT`, 창 제목 Chromite, `WM_CLOSE` → `Main window closed` → `(Swing) shutdown completed`. 수정 전과 같다
  (`MacOSMainThread`·`MacOSAppMenu`는 macOS에서만 동작). 증거: `D:\Tools\_scratch\macos-startup-fix\windows-debug.log`, `windows-app-window.png`.

## 확인 기록 (2026-11-08, JCE·시간대·IP 스택, Windows 11)

개인 데이터 폴더·작업 공간으로 연 앱 하나에 시험 코드를 붙여 확인했다(`D:\Tools\_scratch\chromite-p4-connection-fields\ops-tools-*.log`).

- 첫 시작: `Security.getProvider("BC")` 있음, `security.jce.bc=true`. 기본 시간대 `Asia/Seoul`(OS), `java.net.preferIPv4Stack` 없음(설정 Auto).
- 설정 `java.client.timezone=Asia/Tokyo`, `connections.preferredIPType=IPV4`를 저장하고 다시 시작: 기본 시간대 `Asia/Tokyo`, `java.net.preferIPv4Stack=true`.
- 두 설정을 기본값으로 되돌리고 끝냄. `connections.debugNetworkConnections`(SSL 디버그 출력)는 실행해 보지 않았다.
- macOS 시작 수정(#153)과 합친 뒤 Windows에서 다시 확인(같은 방법, `ops-merge2-*.log`): 시간대·IPv4·BC 모두 위와 같음. macOS 작업 스레드 경로는 실행하지 못했다(macOS 기기 없음).

## macOS 확인 기록 (2026-11-08, GitHub `macos-15` 러너, macOS 15.8.2 arm64)

`macos-smoke.yml`([ci.md](ci.md) "macOS 설치·실행 시험")이 앱을 실제로 띄워 끝내기(Apple event `quit`과 System Events의 Cmd+Q), 앱 메뉴의 정보·환경설정,
열린 트랜잭션이 있을 때의 Cmd+Q(저장 질문 → "Uncommitted Transactions" → Cancel이면 앱이 남는지 → Roll Back이면 롤백 후 정상 종료)를 확인하도록 만들었다.
판정은 디버그 로그의 `Main window closed`·`(Swing) shutdown completed`(JDK 기본 끝내기 `System.exit`이면 이 줄 없이 끝남)와 크래시 보고서 유무다.

### v0.1.1: 시작하지 않음

- v0.1.1 서명 dmg와 수정 전 브랜치의 서명 없는 빌드 모두 프로세스는 뜨지만 창이 없고 디버그 로그도 생기지 않았다.
  JVM 스레드 덤프: `main`이 `installLookAndFeel`의 `SwingUtilities.invokeAndWait`(`ChromiteSwingApplication.java:189`)에서, `AWT-EventQueue-0`이
  `CGraphicsEnvironment.initMetal`에서 멈춤. Eclipse 런처가 JVM을 AppKit 메인 스레드에서 시작해(`sample`: 메인 스레드 `startJavaJNI` → `JVM_MonitorWait`)
  AWT가 메인 스레드에 맡긴 초기화를 아무도 처리하지 못했다(위 "macOS 메인 스레드").
- `--launcher.secondThread`(JVM을 둘째 스레드에서)로 실험하면 런처가 AppKit 단정으로 바로 죽었다(`+[KeyWindow dispatch]`, `EXC_BREAKPOINT`).
  명령줄 `-vm …/jre/bin/java`도 런처가 같은 프로세스에 JVM을 올려 결과가 같았다.

### 수정 뒤 (2026-11-08, 브랜치 `chromite/macos-startup-fix`의 서명 없는 빌드, 실행 42407606192·42408742370)

`source: build`(Linux에서 빌드, 러너에서 임시 서명, 격리 없음)로 모든 단계가 통과했다. 증거: `D:\Tools\_scratch\macos-startup-fix\ci6\`.

| 확인 | 결과 |
|---|---|
| 시작 | 세 번 모두 메인 창이 뜸(`first-launch-main-window.png`). 디버그 로그 `macOS application NSApplication, delegate none`(런처가 만든 앱, AWT 위임자 없음) |
| 앱 메뉴 | System Events가 읽은 항목: About Chromite, Options…, Hide Chromite, Hide Others, Show All, Quit Chromite(+ AppKit이 붙이는 Quit and Keep Windows). 정보 → `AboutDialog`(`about.png`), 옵션 → `OptionsDialog`(`preferences.png`), Esc로 닫힘 |
| 끝내기 Apple event | `tell application id … to quit` → 위임자가 끝내기를 취소(osascript "User canceled -128")하고 창 닫기 경로로 끝남: `Main window closed`, `Saving workspace.`, `(Swing) shutdown completed`, 크래시 보고서 없음 |
| Cmd+Q(System Events 키) | 같은 경로로 끝남 |
| 다시 시작 | 다음 시작의 Equinox 로그에 "workspace exited with unsaved changes"가 없음(프레임워크 정지로 작업 공간이 저장됨). 수정 중 프로세스를 죽였을 때는 이 경고가 났다 |
| 열린 트랜잭션 + Cmd+Q | 수동 커밋 연결에서 `INSERT` 실행(상태 줄 "Open transaction") → Cmd+Q → 저장 질문 → Don't Save → "Uncommitted Transactions"(Commit / Roll Back / Cancel, 기본 Cancel, `txn-4-transaction-question.png`) → Cancel이면 앱과 트랜잭션이 남음 → 다시 Cmd+Q → Roll Back → 정상 종료, SQLite 파일에 행 없음 |
| 보안 저장소 | 아래 [credential-storage.md](credential-storage.md) "macOS" |

서명한 빌드에서만 확인할 수 있는 것(다음 릴리스 뒤 `-f tag=<태그>`): 강화된 런타임과 권한에서 JNA 콜백(Objective-C 메서드, dispatch 함수)이 도는지,
격리된 앱의 첫 실행(Gatekeeper 질문, App Translocation), Developer ID 서명 앱의 키체인 항목 위치.

## 변경 이력

- 2026-11-07 최초 작성. A-S6(Windows 인증서 저장소)과 A-S14(macOS 끝내기·정보·환경설정)를 Swing 앱 시작에 옮김.
- 2026-11-08 A-S9(BouncyCastle), A-S12(클라이언트 시간대), A-S13(IP 스택·네트워크 디버그)를 Swing 시작에 옮김(`chromite/p4-connection-tools`).
  macOS 시작 수정(#153)과 합침: 세 설정은 `run` 안에 있어 Windows 첫 스레드와 macOS 작업 스레드(`MacOSMainThread`) 모두 연결보다 앞에서 적용된다.
  합친 빌드로 Windows에서 다시 확인: 시간대 `Asia/Tokyo`, `java.net.preferIPv4Stack=true`, BC 등록(`ops-merge2-*.log`). macOS 경로는 실행하지 못했다.
- 2026-11-08 macOS 확인 기록 추가: `macos-smoke.yml`로 끝내기·앱 메뉴·열린 트랜잭션 확인 경로를 만들었으나 앱이 macOS에서 시작하지 않아(런처·AWT 교착) 실행하지 못함.
- 2026-11-08 macOS 시작 교착 수정: 첫 스레드에서는 본문을 `Chromite main` 스레드로 옮기고 첫 스레드가 AppKit 실행 루프를 돈다(`MacOSMainThread`, "macOS 메인 스레드").
  런처가 만든 `NSApplication`에 앱 메뉴와 끝내기 위임자를 둔다(`MacOSAppMenu`, "macOS 앱 메뉴"). `Info.plist`의 이름(Chromite)과 Eclipse 틀 값 정리("macOS Info.plist").
  `macos-smoke.yml` 서명 없는 빌드로 시작·앱 메뉴·끝내기·열린 트랜잭션·키체인을 확인("macOS 확인 기록"). Windows 시작은 그대로임을 로컬 실행으로 확인.
- 2026-11-08 시작 화면([branding.md](branding.md) "시작 화면"): `applicationRunning`을 메인 창 직전에서 Swing 시작 화면 직후로 옮김(런처 시작 화면을 내리는 호출). macOS에서는 작업 스레드에서 불려도 런처가 닫기를 AppKit 스레드로 넘기고 기다리지 않아 AppKit 스레드를 막지 않는다. `runWithSplash` 추가.
- 2026-11-09 `MacOSAppMenu.install`이 앱 메뉴 작업이 끝나기를 기다림(최대 5초): macOS 화면 메뉴 막대(앱 변경 #27, `chromite/mac-ui`, [mac-ui.md](mac-ui.md)).
- 2026-11-09 macOS 앱 묶음 이름을 `ChromiteSwing.app`에서 `Chromite.app`으로 바꾼 것을 반영(앱 변경 #22, `chromite/install-update-2`, [distribution.md](distribution.md) "macOS 앱 이름"). 지난 확인 기록의 옛 이름은 그때의 결과라 그대로 둠.
- 2026-11-12 런처 이름 `Chromite`(앱 변경 #65, `chromite/launcher-rename`, [launcher-name.md](launcher-name.md))를 반영. 지난 확인 기록의 옛 이름(`chromite-swing`)은 그때의 결과라 그대로 둠.
