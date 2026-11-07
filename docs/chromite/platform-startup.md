# 플랫폼 시작·종료: Windows 인증서 저장소, macOS 앱 메뉴

## 목적

SWT 앱이 시작할 때 하던 플랫폼별 처리 두 가지가 Swing 앱에 빠져 있었다([SWT → Swing 조사](swt-parity-audit.md) 2순위, A-S6·A-S14).

- **Windows 인증서 저장소(A-S6)**: SWT `ChromiteApplication.start`는 설정 "Windows 신뢰 저장소 사용"이 켜져 있으면
  `javax.net.ssl.trustStoreType=WINDOWS-ROOT`로 두어, Windows에 설치된 인증서(사내 CA 등)로 서버 인증서를 확인했다.
  Swing 앱은 이 설정을 하지 않아 JDK 기본 저장소(`cacerts`)만 썼다. 사내 CA 인증서를 Windows에만 설치한 환경에서
  SSL 연결이 PKIX 오류로 실패할 수 있었다.
- **macOS 끝내기(A-S14)**: JDK의 macOS 기본 끝내기 동작은 `System.exit`이다. 그래서 Cmd+Q(앱 메뉴의 끝내기)가
  메인 창 닫기 경로(저장 질문, 열린 트랜잭션 질문 `ExitTransactionCheck`)를 건너뛸 수 있었다. 앱 메뉴의 정보·환경설정도
  JDK 기본(자바 정보 창, 동작 없음)이었다.

## 구조

모두 `plugins/com.seizia.chromite.ui.swing`의 `ChromiteSwingApplication`에 있다.

| 메서드 | 하는 일 |
|---|---|
| `useWindowsTrustStore` | Windows이고 설정 `connections.useWinTrustStoreType`(`ModelPreferences.PROP_USE_WIN_TRUST_STORE_TYPE`, Windows 기본 켬)이 켜져 있으며, 명령줄에 `javax.net.ssl.trustStore`·`trustStoreType`이 없으면 `javax.net.ssl.trustStoreType=WINDOWS-ROOT`로 둔다. 적용 결과를 디버그 로그에 남긴다(`SSL trust store type: ...`) |
| `installDesktopHandlers` | `java.awt.Desktop`이 지원하는 경우에만 끝내기·정보·환경설정 처리기를 등록한다 |

### 신뢰 저장소를 정하는 시점

JDK는 `javax.net.ssl.trustStoreType`을 기본 SSL 문맥을 처음 만들 때 한 번 읽는다. 그래서 설정 저장소를 읽을 수 있게 된 직후
(작업 공간 잠금, `initPreferences`, 디버그 로그 시작 뒤), 플랫폼을 만들기 전(`DBWorkbench.getPlatform`)에 정한다.
연결과 업데이트 확인(`UpdateService.start`, 메인 창이 뜬 뒤)은 모두 이보다 뒤다. 설정 값은 앱의 설정 저장소
(`getPreferenceStore`, 모델 설정과 같은 번들 노드)에서 읽는다. SWT·헤드리스 앱과 같은 키, 같은 기본값이다.

`WINDOWS-ROOT`는 `jdk.crypto.mscapi` 모듈(공급자 `SunMSCAPI`)이 제공한다. 내장 런타임은 Windows용 jlink 모듈 목록
(`tools/jre/jbr.properties`의 `windows-x64.modules`)에 이미 이 모듈을 넣고 있어 바꿀 것이 없다([runtime.md](runtime.md) "모듈").

### macOS 앱 메뉴

- **끝내기**: `Desktop.setQuitHandler`. 처리기는 EDT에서 메인 창에 창 닫기 단추와 같은 `WINDOW_CLOSING` 이벤트를 보내고,
  JDK의 끝내기 요청은 항상 취소한다(`QuitResponse.cancelQuit`). 사용자가 끝내기로 하면 창이 닫히고 앱은 창을 닫을 때와 같은
  정상 경로(`start`의 래치 → 플랫폼 정리 → 업데이트 적용 → 반환)로 끝난다. 사용자가 취소하면 창이 남고 앱도 계속 돈다.
- **정보**: `Desktop.setAboutHandler` → 도움말 메뉴와 같은 `AboutDialog`.
- **환경설정**: `Desktop.setPreferencesHandler` → 도구 메뉴와 같은 `OptionsDialog`.
- 각각 `Desktop.isSupported(Action.APP_QUIT_HANDLER / APP_ABOUT / APP_PREFERENCES)`일 때만 등록한다. Windows는 셋 다
  지원하지 않으므로(아래 확인 기록) 등록되지 않고 동작이 바뀌지 않는다.

## 설계 결정과 이유

- **끝내기 전략 대신 처리기**: `QuitStrategy.CLOSE_ALL_WINDOWS`는 모든 창(떠 있는 도구 창 포함)에 닫기 이벤트를 보내므로
  메인 창 하나의 질문 경로만 타게 하려면 처리기가 더 정확하다.
- **끝내기 요청은 항상 취소**: `performQuit`를 부르면 JDK가 바로 `System.exit`을 해 플랫폼 정리(연결 닫기, 작업 공간 저장)와
  끝날 때 업데이트 적용을 건너뛸 수 있다. 앱이 스스로 끝나는 정상 경로를 그대로 쓰기 위해 취소하고 창 닫기만 보낸다.
  예상되는 단점(확인 안 함): macOS 로그아웃·종료 중에 Chromite가 끝나면 macOS는 "앱이 로그아웃을 취소했다"고 보고, 사용자가 로그아웃을 다시 해야 한다.
- **`MainFrame`을 고치지 않음**: 창 닫기 이벤트를 그대로 보내면 `MainFrame`의 창 닫기 처리(`requestExit`)를 그대로 지난다.
  공유 파일 변경을 줄이고, 닫기 단추와 Cmd+Q가 같은 코드를 지난다는 것이 분명해진다.
- **화면 메뉴 막대(`apple.laf.useScreenMenuBar`)는 넣지 않음**: 요청 범위 밖이고 메뉴 배치가 바뀐다.

## upstream 대비 변경점

- upstream(DBeaver)의 SWT 앱 시작 처리(`ChromiteApplication.start`의 신뢰 저장소)를 Swing 앱에 옮겼다. 조건과 키는 같다.
- macOS 메뉴 통합은 upstream `ui.swt.macos`(SWT Cocoa)가 맡았다. Swing에서는 `java.awt.Desktop` API로 새로 구현했다.

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

## macOS 확인 기록 (2026-11-08, GitHub `macos-15` 러너, macOS 15.8.2 arm64)

`macos-smoke.yml`([ci.md](ci.md) "macOS 설치·실행 시험")이 앱을 실제로 띄워 끝내기(Apple event `quit`과 System Events의 Cmd+Q), 앱 메뉴의 정보·환경설정,
열린 트랜잭션이 있을 때의 Cmd+Q(저장 질문 → "Uncommitted Transactions" → Cancel이면 앱이 남는지 → Roll Back이면 롤백 후 정상 종료)를 확인하도록 만들었다.
판정은 디버그 로그의 `Main window closed`·`(Swing) shutdown completed`(JDK 기본 끝내기 `System.exit`이면 이 줄 없이 끝남)와 크래시 보고서 유무다.

- **확인하지 못했다: 앱이 macOS에서 시작하지 않는다(제품 결함).** v0.1.1 서명 dmg와 이 브랜치의 서명 없는 빌드 모두 프로세스는 뜨지만 창이 없고 디버그 로그도 생기지 않는다.
  JVM 스레드 덤프: `main`이 `installLookAndFeel`의 `SwingUtilities.invokeAndWait`(`ChromiteSwingApplication.java:189`)에서, `AWT-EventQueue-0`이
  `CGraphicsEnvironment.initMetal`에서 멈춤. Eclipse 런처가 JVM을 AppKit 메인 스레드에서 시작해(`sample`: 메인 스레드 `startJavaJNI` → `JVM_MonitorWait`)
  AWT가 메인 스레드에 맡긴 초기화를 아무도 처리하지 못한다. 그래서 이 문서의 끝내기·정보·환경설정 처리기(`installDesktopHandlers`)는 아직 한 번도 실행되지 않았다.
- `--launcher.secondThread`(JVM을 둘째 스레드에서)로 실험하면 런처가 AppKit 단정으로 바로 죽는다(`+[KeyWindow dispatch]`, `EXC_BREAKPOINT`).
  명령줄 `-vm …/jre/bin/java`도 런처가 같은 프로세스에 JVM을 올려 결과가 같다. 고치는 방법은 따로 정해야 한다(보고).
- 앱이 시작하면 같은 워크플로를 다시 돌려 위 항목을 확인한다(`gh workflow run macos-smoke.yml --repo froiibn/chromite -f source=build` 또는 `-f tag=<태그>`).

## 변경 이력

- 2026-11-07 최초 작성. A-S6(Windows 인증서 저장소)과 A-S14(macOS 끝내기·정보·환경설정)를 Swing 앱 시작에 옮김.
- 2026-11-08 macOS 확인 기록 추가: `macos-smoke.yml`로 끝내기·앱 메뉴·열린 트랜잭션 확인 경로를 만들었으나 앱이 macOS에서 시작하지 않아(런처·AWT 교착) 실행하지 못함.
