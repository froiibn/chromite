# macOS 화면 관례: 화면 메뉴 막대

## 목적

앱 변경 #27. 사용자 질문: "맥에서 파일, 편집, 보기 같은 메뉴는 보통 화면 맨 위 맥 상태 표시줄에 나타나지 않나? 우리 앱은 왜 화면 안에
배치되어 있는가". macOS에서 주 창의 메뉴를 화면 맨 위 메뉴 막대로 보내고, 그 때문에 같이 바뀌어야 하는 것(메뉴가 아닌 단추, 앱 메뉴와
겹치는 항목)을 정리한다. Windows는 바뀌지 않는다. macOS 단축키(Command)는 [shortcuts.md](shortcuts.md)(앱 변경 #24)다.

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

- Chromite는 FlatLaf의 macOS 제목 표시줄 설정(`apple.awt.fullWindowContent`, `apple.awt.transparentTitleBar`,
  `FlatClientProperties.MACOS_*`)을 쓰지 않는다(grep 0건). macOS 창은 시스템 제목 표시줄이다.
- FlatLaf의 창 장식(`TitlePane.*`, 메뉴 막대를 제목 표시줄에 넣기)은 Windows·Linux 전용이다. Chromite가 주 창에 둔
  `TITLE_BAR_SHOW_TITLE=false`도 그 장식에만 쓰인다. 화면 메뉴 막대와 겹치는 설정은 없다.
- 화면 메뉴 막대가 켜지면 창 안 `JMenuBar`는 크기 0이다(`AquaMenuBarUI.getPreferredSize`).

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
