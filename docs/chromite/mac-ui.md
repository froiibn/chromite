# macOS 화면 관례: 화면 메뉴 막대, 통합 제목 줄

## 목적

앱 변경 #27. 사용자 질문: "맥에서 파일, 편집, 보기 같은 메뉴는 보통 화면 맨 위 맥 상태 표시줄에 나타나지 않나? 우리 앱은 왜 화면 안에
배치되어 있는가". macOS에서 주 창의 메뉴를 화면 맨 위 메뉴 막대로 보내고, 그 때문에 같이 바뀌어야 하는 것(메뉴가 아닌 단추, 앱 메뉴와
겹치는 항목)을 정리한다. 앱 변경 #62는 주 창의 제목 줄과 도구 막대를 한 줄로 합친다(아래 "통합 제목 줄").
Windows는 바뀌지 않는다. macOS 단축키(Command)는 [shortcuts.md](shortcuts.md)(앱 변경 #24)다.

## 원인

Swing은 `apple.laf.useScreenMenuBar=true`일 때만 `JMenuBar`를 화면 메뉴 막대로 보낸다. 코드·제품 설정 어디에도 이 값이 없었다(grep 0건).
그런데 값만 켜서는 이 앱에서 동작하지 않는다는 것을 JDK 소스로 확인했다.

1. FlatLaf는 이 값이 켜져 있으면 메뉴 막대 UI를 JDK의 `com.apple.laf.AquaMenuBarUI`로 바꾼다(`FlatLaf.getDefaults`, Look and Feel을 설치할 때 읽음).
2. `AquaMenuBarUI`는 UI를 만들 때 `useScreenMenuBar = getScreenMenuBarProperty()`를 정하고, 이 메서드는
   `if (LWCToolkit.isEmbedded()) return false;` 뒤에 값을 읽는다(OpenJDK `AquaMenuBarUI.java`).
3. `isEmbedded`는 AWT가 시작할 때 `NSApp`이 `NSApplicationAWT`가 아니면 참이다(`LWCToolkit.m`, `+[AWTStarter starter:headless:]`).
4. Eclipse 런처는 JVM 전에 평범한 `NSApplication`을 만든다([platform-startup.md](platform-startup.md) "macOS 앱 메뉴", 디버그 로그
   `macOS application NSApplication, delegate none`). 그래서 이 앱의 AWT는 언제나 내장 상태이고, 값을 켜도 메뉴는 창 안에 남는다.
5. AWT의 메뉴 막대 자체(`CMenuBar.m`의 `activate:modallyDisabled:`)는 `[NSApp mainMenu]`만 쓰고 `NSApplicationAWT`를 요구하지 않는다
   (그 검사는 주석 처리됨). 0번(앱 메뉴)은 남기고 Java 메뉴를 더한다.

즉 막는 것은 `AquaMenuBarUI`의 내장 검사 하나다.

## 구조

| 구성 | 위치 | 하는 일 |
|---|---|---|
| 런처 인자 | `product/swing/chromite-swing.product` `vmArgsMac` | `-Dapple.laf.useScreenMenuBar=true`(JVM 시작 때, AWT·FlatLaf보다 앞), `--add-opens=java.desktop/com.apple.laf=ALL-UNNAMED` |
| `MacOSScreenMenuBar` (새) | `plugins/com.seizia.chromite.ui.swing/.../MacOSScreenMenuBar.java` | `isEnabled()`(macOS이고 값이 켜짐), `attach(JMenuBar)`: 메뉴 막대 UI가 `AquaMenuBarUI`면 그 `useScreenMenuBar` 필드를 켜고 창 안 메뉴 막대의 F10 키를 지움. 실패하면 FlatLaf 메뉴 막대 UI로 되돌림(창 안 메뉴) |
| `MainFrame.createMenuBar` | `MainFrame.java` | 메뉴 막대의 `updateUI`마다 `attach`(테마 바꾸기 뒤에도 화면 메뉴 막대). macOS에서 File > Exit, Tools > Options, Help 메뉴(About만 있음)를 넣지 않음 |
| `UpdateUi` | `UpdateUi.java` | `isEnabled()`면 업데이트 단추를 메뉴 막대 대신 상태 표시줄 오른쪽 끝, 업데이트 진행 옆에 둠 |
| `MacOSAppMenu.install` | `MacOSAppMenu.java` | 앱 메뉴 작업(AppKit 스레드)이 끝나기를 기다림(최대 5초) |

### 어느 자리에서 켜는가

- **런처 인자(채택):** `vmArgsMac`은 macOS 런처 설정(`Chromite.app/Contents/Eclipse/chromite-swing.ini`)에만 들어가고 JVM 시작 때 정해진다.
  FlatLaf가 값을 읽는 Look and Feel 설치(`ChromiteSwingApplication.installLookAndFeel`)와 AWT 시작보다 반드시 앞이다. `--add-opens`도 JVM
  인자라 같은 자리여야 한다(Windows JDK에는 `com.apple.laf` 패키지가 없어 Windows 인자에 넣으면 경고가 난다).
- **시작 코드 맨 앞(`System.setProperty`)은 쓰지 않음:** 값은 Look and Feel 설치 전이면 읽히지만, 필드를 여는 `--add-opens`는 코드로 줄 수
  없다. 예전 설정 파일(인자 없음)로 값만 켜지면 Aqua 메뉴 막대가 창 안에 그려지는 반쪽 상태가 된다. 두 값을 같은 곳에 둬서 함께 있거나
  함께 없게 했다. 인자가 없으면 지금처럼 창 안 메뉴다.
- 필드를 켜지 못하면(`--add-opens` 없음, JDK 내부 변경) 경고 한 줄을 남기고 FlatLaf 메뉴 막대로 되돌린다.

### 앱 메뉴 이름

화면 메뉴 막대의 굵은 앱 메뉴 이름은 앱 묶음의 `CFBundleName`(Chromite, [platform-startup.md](platform-startup.md) "macOS Info.plist")이고,
메뉴 내용은 `MacOSAppMenu`가 "Chromite 정보", "옵션…"(⌘,), 가리기, "Chromite 끝내기"(⌘Q)로 만든다(2026-11-08 macOS 확인 기록:
About Chromite, Options…, Hide Chromite, Hide Others, Show All, Quit Chromite). 이번에 바꾸지 않았다.

### 메뉴 순서와 앱 메뉴

`MacOSAppMenu`는 새 메인 메뉴를 둔다(`setMainMenu:`). AWT가 창 메뉴를 메인 메뉴에 붙인 뒤에 이것이 실행되면 창 메뉴가 사라진다(다른 앱으로
갔다 오기 전까지). 예전에는 비동기(`dispatch_async_f`)로 보내고 바로 창을 보였다. 이제 `install`이 작업이 끝나기를 기다린다. 호출은 메인
창을 보이기 직전(EDT)이고, AppKit 스레드가 EDT를 기다리는 일이 있어도 5초가 지나면 그대로 진행하므로 교착은 없다.

### 앱 메뉴와 겹치는 창 메뉴

macOS 관례대로 창 메뉴에서 뺐다. 앱 메뉴, ⌘Q, ⌘,가 같은 경로를 탄다(`ChromiteSwingApplication.installDesktopHandlers`, `MacOSAppMenu`).

| 창 메뉴 (Windows) | macOS |
|---|---|
| File > Exit | 앱 메뉴 > Chromite 끝내기(⌘Q). 같은 창 닫기 경로(저장·열린 트랜잭션 질문) |
| Tools > Options… | 앱 메뉴 > 옵션…(⌘,) |
| Help > About Chromite | 앱 메뉴 > Chromite 정보. Help 메뉴에는 About만 있어 메뉴째 뺌 |

이 세 항목은 화면 메뉴 막대를 켜지 못해 메뉴가 창 안에 남아도 macOS에서는 빠진다(앱 메뉴는 언제나 있다).

### 메뉴가 아닌 컴포넌트

화면 메뉴 막대에는 메뉴만 보인다. 메뉴 막대에 넣은 메뉴 아닌 것은 업데이트 단추(`UpdateUi`, glue 뒤) 하나다(`menuBar.add` 전수 검색).
macOS에서는 상태 표시줄 오른쪽 끝, 업데이트 진행 옆으로 옮겼다. 누르면 같은 메뉴·업데이트 진행 창을 연다. `UpdateUi`는 다른 작업
(`chromite/install-update-2`)도 고치는 파일이라 자리만 바꿨다(단추를 넣는 곳 두 줄, 다시 그리는 곳은 부모를 다시 그림).

### FlatLaf의 macOS 제목 표시줄

- FlatLaf의 창 장식(`TitlePane.*`, 메뉴 막대를 제목 표시줄에 넣기)은 Windows·Linux 전용이다. Chromite가 주 창에 둔
  `TITLE_BAR_SHOW_TITLE=false`, `TITLE_BAR_HEIGHT`도 그 장식에만 쓰인다. 화면 메뉴 막대와 겹치는 설정은 없다.
- 화면 메뉴 막대가 켜지면 창 안 `JMenuBar`는 크기 0이다(`AquaMenuBarUI.getPreferredSize`).
- macOS 주 창의 제목 줄은 아래 "통합 제목 줄"이다(앱 변경 #62). 대화상자는 시스템 제목 표시줄 그대로다.

## 통합 제목 줄 (앱 변경 #62)

사용자 요청(2026-11-12): "맥에서 창 스타일이 맥 기본 창 내부에 우리 앱이 있는 구조이다. 창을 어플리케이션 일체형으로 수정한다".
고치기 전(macos-smoke 캡처 2026-11-08)에는 macOS 제목 줄("Chromite" 글자와 신호등 단추)이 따로 있고 그 아래에서 앱의 도구 막대가 시작됐다.

### 구조

| 구성 | 위치 | 하는 일 |
|---|---|---|
| `MacOSUnifiedTitleBar` (새) | `plugins/com.seizia.chromite.ui.swing/.../MacOSUnifiedTitleBar.java` | `install(frame, toolBar)`: macOS이고 메뉴가 화면 메뉴 막대에 있으면(`MacOSScreenMenuBar.isAttached`) 루트 창 속성 `apple.awt.fullWindowContent`·`apple.awt.transparentTitleBar`=true, `apple.awt.windowTitleVisible`=false, 신호등 단추 자리 표시 패널 + Standard 도구 막대의 줄을 돌려줌. 아니면 도구 막대를 그대로 돌려줌 |
| `MainFrame` 생성자 | `MainFrame.java` | Standard 도구 막대를 `MacOSUnifiedTitleBar.install`을 거쳐 도구 막대 묶음 맨 위에 둠(메뉴 막대를 정한 뒤, 창을 보이기 전) |
| `MacOSScreenMenuBar.isAttached` | `MacOSScreenMenuBar.java` | 메뉴 막대 UI가 아직 Aqua 것인지(화면 메뉴 막대를 켜지 못하면 `attach`가 FlatLaf 것으로 되돌림) |

### FlatLaf 3.8이 해 주는 것 (소스로 확인)

- **신호등 단추 자리:** `FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_PLACEHOLDER`(3.4)를 단 `JPanel`은 크기가 실제 단추 범위
  (`FlatNativeMacLibrary.getWindowButtonsBounds`, 네이티브 라이브러리가 없으면 68×28)다. 값 `"mac zeroInFullScreen"`: macOS에서만, 전체 화면에서는 0×0.
  전체 화면을 오가면 FlatLaf가 루트 창 크기 변화에서 단추 범위를 다시 읽는다(`FullWindowContentSupport.macInstallListeners`). 그래서 여백을 직접 계산하지 않는다.
- **창 끌기와 두 번 누르기:** 3.8부터 `apple.awt.fullWindowContent`가 켜진 창에 네이티브 처리기를 단다(`FullWindowContentSupport.macInstallTitleBarCaption`,
  `MacTitleBarCaption.mm`). 제목 줄 높이(단추 범위 높이, 기본 28pt) 안의 마우스 누름을 AWT보다 먼저 보고, 그 자리가 "제목 줄"이면(마우스를 받는 컴포넌트가 없음:
  도구 막대 빈 곳·구분선·자리 표시 패널. `FlatToolBarUI`는 자식을 보라고 답함) `-[NSWindow performWindowDragWithEvent:]`로 끌고, 두 번 누르면
  `AppleActionOnDoubleClick`(시스템 설정: 확대/최소화/없음)대로 한다. 단추는 그대로 AWT로 간다.
- **단추 간격:** `MACOS_WINDOW_BUTTONS_SPACING`(medium/large)은 제목 줄을 더 높게 만든다(NSToolbar 모양). 쓰지 않았다: 도구 막대 줄은 32pt(촘촘한 모드 28pt)라
  기본 제목 줄(28pt)에 가깝다.
- **밝은·어두운 모양:** FlatLaf가 테마를 설치할 때마다 창의 `NSAppearance`를 Aqua/DarkAqua로 둔다(`FlatRootPaneUI.macSetWindowAppearance`). 신호등 단추가 테마를 따른다.
- 네이티브 라이브러리(`flatlaf.jar!/com/formdev/flatlaf/natives/libflatlaf-macos-*.dylib`)는 서명 단계가 jar 안까지 서명하고([signing.md](signing.md)),
  런처 권한에 `disable-library-validation`이 있다.

### 설계 결정

- **제목 줄 색:** 제목 줄 자리에는 내용 창(`MainFrame.CanvasPanel`, `Chromite.island.canvas`)이 그대로 보인다. 제목 줄이 투명하므로 테마마다 따로 칠할 것이 없다.
  도구 막대와 그 줄은 불투명하지 않다.
- **메뉴가 창 안에 남으면 켜지 않는다:** 화면 메뉴 막대를 켜지 못한 경우(런처 인자 없는 예전 설정) 창 안 메뉴 막대가 창 맨 위, 신호등 단추 줄에 놓인다.
- **Standard 줄만 올라간다:** SQL Editor 줄(`SqlEditorToolBar`)은 그 아래 그대로다.
- **제목 글자:** 숨기기만 한다. 창 이름은 Window 메뉴·Mission Control·Dock에서 그대로 "Chromite"다.
- **네이티브 라이브러리를 읽지 못하면** 도구 막대 줄로 창을 끌 수 없다. 창이 열릴 때 디버그 로그에 남긴다: 정상이면
  `macOS unified title bar: window buttons java.awt.Rectangle[…], placeholder java.awt.Dimension[…]`(DEBUG), 아니면 `… without FlatLaf's native library …`(WARN).
- **Windows는 그대로:** 분기가 `RuntimeUtils.isMacOS()`와 `isAttached`(macOS에서만 참)다. Windows는 이미 FlatLaf 창 장식으로 메뉴 막대가 제목 줄에 들어 있다.

### 확인

- Windows에서는 macOS 분기가 실행되지 않는다. 빌드(컴파일)와, Windows에서 `install`이 도구 막대를 그대로 돌려주는 것만 확인할 수 있다.
- `macos-smoke.sh`: `launch`가 `<label>-title-bar.png`(주 창 위쪽 90pt)와 디버그 로그 줄을 남기고, 새 단계 `titlebar`가 밝은·어두운 모양
  (`title-bar-light.png`, `title-bar-dark.png`, `main-window-dark.png`)과 전체 화면(`main-window-fullscreen.png`)을 찍는다([ci.md](ci.md)).
- 맞을 때 보이는 것: 창 맨 위 줄에 신호등 단추가 있고 그 오른쪽에 New Query부터 Standard 도구 막대가 같은 줄에 있다. "Chromite" 글자와 따로 된 회색 제목 줄이 없다.
  제목 줄 자리의 색은 아래 도구 막대·상태 표시줄 바탕(캔버스)과 같다(밝은 테마는 밝게, 어두운 테마는 어둡게). 전체 화면에서는 도구 막대가 왼쪽 끝에서 시작한다.
- 사용자 Mac에서 볼 것: 도구 막대 빈 곳을 끌어 창 이동, 두 번 눌러 확대(시스템 설정 "제목 막대를 두 번 클릭하면"), 단추가 그대로 눌리는지, 신호등 단추와 도구 막대 단추의 세로 가운데가
  맞는지(제목 줄 28pt, 도구 막대 줄 32pt라 2pt 차이가 날 수 있음), 대화상자는 예전 제목 줄인지.

## 설계 결정과 이유

- **JDK 내부 필드를 켜는 방법:** 다른 방법은 (1) 화면 메뉴 막대를 JNA로 직접 만들기(메뉴·체크·동적 메뉴를 모두 옮겨야 함), (2) 런처가 만든
  `NSApplication`을 바꾸기(런처는 `deps`가 아닌 Eclipse 바이너리, 시작 교착 수정과 얽힘)다. 필드 하나만 켜는 쪽이 가장 작고, 실패하면 예전
  동작으로 돌아간다.
- **업데이트 단추 자리:** 앱 변경 목록의 정한 기본값(상태 표시줄 오른쪽, 업데이트 진행 옆).
- **Windows는 그대로:** 모든 분기가 `RuntimeUtils.isMacOS()`나 `MacOSScreenMenuBar.isEnabled()`이고, 런처 인자는 `vmArgsMac`이다.

## upstream 대비 변경점

DBeaver(SWT)는 SWT가 화면 메뉴 막대를 직접 다뤘다. Swing 프런트엔드는 Chromite가 새로 만든 것이라 upstream 코드는 바뀌지 않았다.

## 확인

- Windows(로컬 빌드): 메뉴·업데이트 단추가 그대로임을 코드 분기로 확인(아래 변경 이력의 빌드). macOS 분기는 Windows에서 실행되지 않는다.
- `macos-smoke.yml`: `menus` 단계에 화면 메뉴 막대 확인을 더했다(System Events로 메뉴 막대 항목 이름을 읽어 File…Window가 있는지,
  `menu-bar.txt`). 이 작업에서는 실행하지 못했다. 워크플로는 `workflow_dispatch`(source: build)로 원격 브랜치에서만 돌릴 수 있는데,
  이 작업은 push하지 않는다. 브랜치를 올린 뒤 `gh workflow run macos-smoke.yml --repo froiibn/chromite --ref chromite/mac-ui -f source=build`로 돌린다.
- 화면 없이 볼 수 있는 것(smoke): 메뉴 막대 항목 이름, 앱 메뉴 항목, 정보·옵션 창이 뜨는지, ⌘Q 끝내기 경로. 사용자가 볼 것: 화면 메뉴 막대의
  메뉴가 열리고 항목이 동작하는지(체크 항목, 동적 메뉴 Recent Files·Window), 단축키가 메뉴에 ⌘로 보이고 한 번만 동작하는지, 상태 표시줄의
  업데이트 단추 모양, 테마를 바꾼 뒤에도 메뉴가 화면 메뉴 막대에 남는지.

## 남긴 것

- 메뉴의 니모닉(Alt+F 등)은 macOS에서도 걸려 있다. 화면 메뉴 막대에서는 ⌥+글자가 크기 0인 창 안 메뉴를 열 수 있다. 실제 Mac에서 확인하지 않았다.

## 변경 이력

- 2026-11-09 최초 작성(앱 변경 #27, `chromite/mac-ui`): 런처 `vmArgsMac`, `MacOSScreenMenuBar`, macOS에서 Exit·Options·Help 빼기, 업데이트 단추를
  상태 표시줄로, `MacOSAppMenu.install`이 앱 메뉴 작업을 기다림, `macos-smoke.sh menus`에 화면 메뉴 막대 확인.
- 2026-11-12 통합 제목 줄(앱 변경 #62, `chromite/mac-ui-2`): `MacOSUnifiedTitleBar`, `MacOSScreenMenuBar.isAttached`, "FlatLaf의 macOS 제목 표시줄"
  갱신, `macos-smoke.sh`의 제목 줄 캡처(`launch`, `titlebar`). macOS에서 실행하지 못했다(macos-smoke로 확인).
