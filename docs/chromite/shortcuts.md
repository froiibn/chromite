# 단축키: Windows와 macOS

## 목적

앱 변경 #24("맥에서 단축키를 ^ 대신 커맨드 키를 사용하게 하라"). 단축키를 `InputEvent.CTRL_DOWN_MASK`로 박아 두어 macOS에서도
Control 키를 썼다(2026-11-09 devel 기준 Swing UI 19개 파일, `CTRL_DOWN_MASK`·`isControlDown` 97곳과 문자열 키 `"ctrl C"` 2곳, `getMenuShortcutKeyMaskEx` 사용 0곳). 화면 문구(.properties 70줄, 영어·한국어 35줄씩)에도
"Ctrl+"가 박혀 있었다. 단축키 하나씩 고치지 않고, 명령 키를 한 곳에서 정하고 도움말 글자를 단축키에서 만들도록 구조를 바꿨다.

## 구조

| 구성 | 위치 | 하는 일 |
|---|---|---|
| `UiKeys` (새) | `plugins/com.seizia.chromite.ui.swing/.../UiKeys.java` | 명령 키(`Toolkit.getMenuShortcutKeyMaskEx()`: Windows·Linux Ctrl, macOS Command), Windows 키 → 플랫폼 키(`command`), 모든 플랫폼에서 Control인 키(`control`), 마우스 수정자(`isCommandDown`), 단축키 글자(`getText`, `getChordText`, `format`, `formatDescription`) |
| `UiKeys.columnSelect`, `isColumnSelectDown` | 같은 파일 | 쿼리 편집기 열 선택의 키(Shift+Alt+방향키, macOS는 ⇧⌥+방향키가 단어 선택이라 ⇧⌥⌘+방향키)와 마우스 수정자(Alt/Option, Ctrl·Command 없이) |
| `UiKeys.MAC_KEYS` | 같은 파일 | macOS에서 Ctrl→Command로만 바꾸면 안 되는 키 표(아래 "macOS에서 다른 키") |
| `SqlEditorPanel.SqlTextArea.getChordText` | `SqlEditorPanel.java` | 두 키 조합(Ctrl+K, Ctrl+D)의 글자. 첫 키는 `CHORD_KEY`, 두 번째 키도 명령 키를 본다 |
| `UiKeysTest` (새) | `test/com.seizia.chromite.ui.swing.test` | Mac 없이 macOS 키 매핑을 시험(아래 "확인") |

- 단축키는 Windows 키로 적는다: `UiKeys.command(KeyEvent.VK_S, InputEvent.SHIFT_DOWN_MASK)`는 Windows Ctrl+Shift+S, macOS ⇧⌘S다.
  메뉴, 창 단축키, 편집기 키, 도구 설명이 모두 같은 함수를 지나므로 플랫폼마다 한 키만 걸린다.
- `.properties`에는 키 글자를 적지 않는다. 키 자리는 `{0}`이고 코드가 단축키에서 만든 글자를 넣는다(`UiKeys.format`). NLS 키 이름은 그대로다.
  마우스 안내("Ctrl+클릭", "Ctrl+휠")는 수정자 이름만 넣는다(`UiKeys.getCommandText()`: "Ctrl" / "⌘").
- 단축키 글자: Windows는 AWT 이름("Ctrl+Shift+F", 예전 `WelcomePanel.getKeyText`와 같음), macOS는 Apple 순서의 기호를 붙여 쓴다("⇧⌘F",
  ⌃⌥⇧⌘). 문장 부호 키는 글자로 쓴다(Ctrl+`, AWT 이름 "Back Quote" 대신).
- 마우스: Ctrl+클릭(다중 선택, 열 머리글의 선택·정렬 더하기, ERD 선택 토글)과 Ctrl+휠(ERD·실행 계획 확대)은 macOS에서 Command다.
  macOS에서 Control+클릭은 오른쪽 클릭(팝업 메뉴)이라 Control로는 다중 선택을 할 수 없다. `JTable`·`JList`의 기본 다중 선택은 Swing이
  이미 플랫폼 키를 본다(`BasicGraphicsUtils.isMenuShortcutKeyDown`).

### 텍스트 편집기의 기본 키

RSyntaxTextArea 4.0.1의 기본 키맵(`RTADefaultInputMap`, `RSyntaxTextAreaDefaultInputMap`)을 `javap`로 읽어 확인했다.

- 기본 수정자는 `RTextArea.getDefaultModifier()`이고 이는 `Toolkit.getMenuShortcutKeyMaskEx()`다. 잘라내기·복사·붙여넣기·실행 취소(⌘Z)·
  다시 실행(⌘Y)·접기(⌘숫자 패드 -, +, /, *)·줄 주석(⌘/)·다음 단어 찾기(⌘K) 등이 모두 이 수정자를 쓴다.
- macOS에서는 여기에 ⇧⌘Z(다시 실행), ⌘←/→(줄 처음·끝), ⌘↑/↓(문서 처음·끝), ⌃F/B/A/E/P/N/O(Emacs식 이동)가 더해진다.
- 그래서 Chromite가 편집기에 거는 키(찾기, 바꾸기, 두 키 조합, 서식, 대소문자, 블록 주석, 줄 바꿈, Ctrl+Enter를 막는 `none`)도 같은 명령
  키여야 한다. 예전에는 Chromite 키가 Control, 편집기 기본 키가 Command여서 macOS에서 두 키가 함께 걸렸다(⌘Enter는 편집기의 단어 완성,
  Control+Enter는 실행). 이제 둘 다 Command다.
- FlatLaf는 macOS에서 `JTextField`·`JTextArea` 등의 기본 키를 Command로 둔다(Chromite가 덮지 않는다).

## macOS에서 다른 키

Ctrl→Command로만 바꾸지 않은 키다. `UiKeys.MAC_KEYS` 한 곳에 있고, 같은 Windows 키를 쓰는 곳은 모두 같은 macOS 키가 된다.

| Windows | macOS | 명령 | 이유 |
|---|---|---|---|
| Ctrl+H | ⌥⌘F | 바꾸기(Edit 메뉴, 편집기) | ⌘H는 앱 가리기. Xcode·JetBrains IDE의 바꾸기 |
| Ctrl+M | ⇧⌘M | 실제 실행 계획 포함(Query 메뉴, SQL Editor 도구 막대) | ⌘M은 창 최소화 |
| Ctrl+Y | ⇧⌘Z | 다시 실행(Edit 메뉴, 결과 표) | macOS 관례. 편집기는 ⌘Y도 받는다(RSyntaxTextArea 기본) |
| Ctrl+Shift+/ | ⌥⌘/ | 블록 주석 토글(편집기, Edit > Advanced) | ⇧⌘/는 macOS의 도움말 메뉴 검색. JetBrains IDE의 블록 주석 |
| Ctrl+` | ⌃` | 결과를 텍스트로 보기 | ⌘`는 같은 앱의 다음 창으로 이동 |
| Ctrl+Alt+D | ⇧⌘D | 아래 행 값 복사(결과 표) | ⌥⌘D는 Dock 보이기·가리기 |
| Ctrl+Space | ⌃Space | 자동 완성(Query 메뉴, 편집기) | ⌘Space는 Spotlight. JetBrains IDE와 같은 Control |

- ⌘Q(끝내기)와 ⌘,(옵션)는 앱 메뉴의 항목이다(`MacOSAppMenu`, [platform-startup.md](platform-startup.md) "macOS 앱 메뉴"). 창 메뉴의
  File > Exit와 Tools > Options는 macOS에서 빼서 겹치지 않는다([mac-ui.md](mac-ui.md)).
- **⌃Space와 입력 소스:** macOS 기본 설정에서 ⌃Space는 "이전 입력 소스 선택"이다. 한글·영문처럼 입력 소스가 둘 이상이면 시스템이 먼저
  받아 자동 완성이 열리지 않는다. 시스템 설정 > 키보드 > 키보드 단축키 > 입력 소스에서 그 단축키를 바꾸거나 끄면 된다. Query > Complete
  메뉴로도 열 수 있다.
- Ctrl+Tab(탭 전환): Chromite가 걸지 않는다. Swing·FlatLaf 기본(초점 이동, 탭 전환 Ctrl+PageUp/PageDown)을 그대로 쓴다.

## 단축키 표

Windows 키와 macOS 키. "같음"이 아닌 줄은 위 표의 키다. 같은 키가 여러 곳에 있으면(예: 결과 표와 Edit 메뉴의 Ctrl+C) 초점이 있는
컴포넌트의 키가 먼저다.

### 주 창 (File, Edit, View, Query, Tools)

| 명령 | Windows | macOS |
|---|---|---|
| New Query | Ctrl+N | ⌘N |
| Open File | Ctrl+O | ⌘O |
| Save | Ctrl+S | ⌘S |
| Save All | Ctrl+Shift+S | ⇧⌘S |
| Undo / Redo | Ctrl+Z / Ctrl+Y | ⌘Z / **⇧⌘Z** |
| Cut / Copy / Paste / Select All | Ctrl+X / C / V / A | ⌘X / ⌘C / ⌘V / ⌘A |
| Find… / Replace… | Ctrl+F / Ctrl+H | ⌘F / **⌥⌘F** |
| Find Next / Previous | F3 / Shift+F3 | F3 / ⇧F3 |
| Execute / Cancel | F5 또는 Alt+X / Alt+Pause | F5 또는 ⌥X / ⌥Pause |
| Execute Current Statement | Ctrl+Enter | ⌘Enter(↩) |
| Execute From Cursor | Alt+P | ⌥P |
| Evaluate Expression | Ctrl+Alt+' | ⌥⌘' |
| Previous / Next Statement | Alt+PageUp / Alt+PageDown | ⌥PageUp / ⌥PageDown |
| Complete | Ctrl+Space | **⌃Space** |
| Display Estimated Plan | Ctrl+L | ⌘L |
| Include Actual Plan | Ctrl+M | **⇧⌘M** |
| Terminal | Ctrl+T | ⌘T |
| Select Connection / Schema | Ctrl+9 / Ctrl+0 | ⌘9 / ⌘0 |
| Commit / Roll Back | Ctrl+Alt+Shift+K / R (Linux Ctrl+4 / Ctrl+8) | ⌘4 / ⌘8 |
| Pin Results / Maximize Results | Ctrl+Shift+P / Ctrl+Shift+R | ⇧⌘P / ⇧⌘R |
| Reopen Closed Tab(문서 탭 메뉴, Window 메뉴) | Ctrl+Shift+T | ⇧⌘T |
| Activity Monitor | Ctrl+Alt+A | ⌥⌘A |
| Results 보이기·숨기기 | Ctrl+R | ⌘R |
| Registered Servers / Query Library | Ctrl+Alt+G / Ctrl+Alt+T | ⌥⌘G / ⌥⌘T |
| Scripts / Outline / Chromite Agent | Ctrl+Alt+L / Ctrl+Alt+O / Ctrl+Alt+I | ⌥⌘L / ⌥⌘O / ⌥⌘I |
| Object Explorer / Properties | F8 / F4 | F8 / F4 |

### 쿼리 편집기

| 명령 | Windows | macOS |
|---|---|---|
| Format SQL | Ctrl+Shift+F, 또는 Ctrl+K, Ctrl+D | ⇧⌘F, 또는 ⌘K, ⌘D |
| Comment / Uncomment | Ctrl+K, Ctrl+C / Ctrl+K, Ctrl+U | ⌘K, ⌘C / ⌘K, ⌘U |
| Insert Template | Ctrl+K, Ctrl+X | ⌘K, ⌘X |
| Upper / Lower Case | Ctrl+Shift+U / Ctrl+Shift+L | ⇧⌘U / ⇧⌘L |
| Toggle Block Comment | Ctrl+Shift+/ | **⌥⌘/** |
| Word Wrap | Ctrl+Alt+Shift+W | ⌥⇧⌘W |
| 접기 / 펴기 / 모두 접기 / 모두 펴기(RSyntaxTextArea) | Ctrl+NumPad - / + / / / * | ⌘NumPad - / + / / / * |
| Indent / Outdent | Tab / Shift+Tab | ⇥ / ⇧⇥ |
| 열(사각형) 선택(swing-sql-column-selection.md) | Alt+끌기, Shift+Alt+방향키 | ⌥+끌기, **⇧⌥⌘+방향키** |

### 결과 표와 결과 보기

| 명령 | Windows | macOS |
|---|---|---|
| Copy / Advanced Copy | Ctrl+C / Ctrl+Shift+C | ⌘C / ⇧⌘C |
| Paste / Paste Special | Ctrl+V / Ctrl+Shift+V | ⌘V / ⇧⌘V |
| Save(표 편집) | Ctrl+S | ⌘S |
| Set NULL / Set Default | Ctrl+0 / Ctrl+Backspace | ⌘0 / ⌘⌫ |
| Generate UUID | Ctrl+Alt+Shift+U | ⌥⇧⌘U |
| Duplicate Rows | Ctrl+Alt+Insert | ⌥⌘Insert |
| Copy from Row Above / Below | Ctrl+D / Ctrl+Alt+D | ⌘D / **⇧⌘D** |
| Undo / Redo(셀 값) | Ctrl+Z / Ctrl+Y | ⌘Z / **⇧⌘Z** |
| Find / Go to Row / Go to Column | Ctrl+F / Ctrl+G / Ctrl+Shift+G | ⌘F / ⌘G / ⇧⌘G |
| Navigate Link / References | Ctrl+B / Ctrl+Shift+1 | ⌘B / ⇧⌘1 |
| Text view | Ctrl+` | **⌃`** |
| 값 패널: Apply / Format | Ctrl+Enter / Ctrl+Shift+F | ⌘Enter / ⇧⌘F |
| 16진 편집기: OK | Ctrl+Enter | ⌘Enter |

### 그 밖의 창

| 명령 | Windows | macOS |
|---|---|---|
| Object Explorer: Copy Name / Qualified Name | Ctrl+C / Ctrl+Shift+C | ⌘C / ⇧⌘C |
| Object Explorer: Quick Filter | Ctrl+F | ⌘F |
| Query Library: Duplicate | Ctrl+D | ⌘D |
| Options: 검색 | Ctrl+F | ⌘F |
| 관리 표(Admin): Copy | Ctrl+C | ⌘C |
| ERD·실행 계획: 확대 / 축소 / 100% | Ctrl+= (또는 Ctrl+NumPad +) / Ctrl+- / Ctrl+0 | ⌘= / ⌘- / ⌘0 |
| ERD·실행 계획: 휠 확대 | Ctrl+휠 | ⌘+휠 |
| ERD: 선택에 더하기·빼기 | Ctrl+클릭 | ⌘+클릭 |
| 비교 탭: 이전 차이 / 다음 차이(앱 변경 #105, [text-compare.md](text-compare.md)) | Alt+↑ / Alt+↓ | ⌥↑ / ⌥↓ |
| 결과 열 머리글: 열 선택에 더하기 / 정렬에 더하기 | Ctrl+클릭 | ⌘+클릭 |

## 설계 결정과 이유

- **Windows 키로 적고 한 함수가 바꾼다:** 예외(⌘H 등)를 부르는 곳마다 두지 않고 표 한 곳에 둔다. 같은 Windows 키(Edit 메뉴와 편집기의
  Ctrl+H)는 언제나 같은 macOS 키가 되어, 메뉴가 보이는 키와 편집기가 받는 키가 어긋나지 않는다.
- **Commit / Roll Back은 macOS에서 ⌘4 / ⌘8:** SWT는 macOS(cocoa)에서 Control+4 / Control+8이었다(옛 `plugin.xml`의 `CTRL+4`, SWT의 CTRL은
  Control). 사용자 요청(Command 사용)을 따라 명령 키로 바꿨다. 시스템 키와 겹치지 않는다(⇧⌘4는 스크린숏이지만 ⌘4는 아니다).
- **실제 계획은 ⇧⌘M:** Azure Data Studio는 macOS에서도 Control+M이지만 사용자 요청(Command)과 맞추고 ⌘M(최소화)을 피했다. 되돌리기 쉽다
  (`MAC_KEYS` 한 줄).
- **결과 텍스트 보기는 ⌃`:** ⌘`는 macOS의 창 순환이라 쓸 수 없다. Control+`는 macOS에 다른 뜻이 없다.
- **도움말 글자는 키에서 만든다:** 문구에 키를 적어 두면 플랫폼마다 틀린 문구가 된다. 단축키를 바꾸면 도구 설명도 함께 바뀐다.
- **Alt 키와 메뉴 막대(Windows, 2026-11-12, 앱 변경 #102):** Alt만 눌렀다 떼면 메뉴 막대의 첫 메뉴가 골라지고(FlatLaf), F10과 Alt+글자(니모닉)도
  그대로다. 다만 Alt를 누른 채 마우스를 썼으면(누르기·클릭·끌기·휠, 어느 창·구성 요소든) 그 Alt 떼기는 메뉴를 고르지 않는다: 열 선택(Alt+끌기)과
  긴 줄의 Alt+휠 뒤에 포커스가 메뉴로 가지 않게. 한 곳 `AltMouseMenuGuard`(키 디스패처 + AWT 마우스 리스너)가 맡는다.
  자세한 근거와 확인은 [swing-sql-column-selection.md](swing-sql-column-selection.md) "모든 창으로 넓힘".
- **Execute의 둘째 키 Alt+X(2026-11-15, 앱 변경 #122):** 포크 시점 DBeaver의 스크립트 실행 키다(SQL 편집기 플러그인의 `plugin.xml` 키 바인딩).
  F5와 **같은 동작 객체**에 더한 키라 선택이 있으면 선택, 없으면 전체 스크립트, 확인 대화상자, 꺼짐 상태, 실행 중 동작이 모두 F5와 같다.
  메뉴 항목은 단축키를 하나만 가지므로 메뉴·도구 설명은 F5를 보이고, Alt+X는 루트 창의 `WHEN_IN_FOCUSED_WINDOW` 키 맵(메뉴 단축키와 같은 창 전체 수준)에
  걸었다(`MainFrame.createEditorAction`의 추가 키). F5처럼 초점이 쿼리 창(편집기와 결과)에 있을 때만 실행한다(`isShortcutFromOutside`가 동작의 모든 키를 봄).
  - 겹침 확인: 주 메뉴 니모닉은 File F, Edit E, View V, Query Q, Tools T, Window W, Help H라 X가 없다(File > Exit의 X는 File 메뉴가 열려 있을 때만).
    RSyntaxTextArea 기본 키맵의 X는 잘라내기(명령 키 + X)뿐이고, Chromite의 X 키는 잘라내기(Ctrl+X)와 두 키 조합 Ctrl+K, Ctrl+X(Insert Template)뿐이다.
  - Windows에서는 Alt+X의 글자 이벤트가 입력되지 않는다(`SunToolkit.isPrintableCharacterModifiersMask`: Alt만 누른 글자는 인쇄 글자가 아님, 실행해 확인). Alt+P와 같다.
  - macOS ⌥X는 ⌥P와 같은 방식(Swing 키 바인딩이 키 누름을 받음)이다. 그 글자("≈", "π")는 아래 "Option 단축키의 글자"가 막는다.
- **Option 단축키의 글자(macOS, 2026-11-15, 앱 변경 #122):** 단축키가 처리한 Option 키 누름의 글자 이벤트는 텍스트 구성 요소에 가지 않는다.
  - 원인(JDK 25 소스를 읽음, jdk25u, 줄 번호는 가져온 사본에서 센 것): macOS AWT는 키 하나에 `CPlatformResponder.handleKeyEvent`(136행)에서
    누름을 보내고(223행) 이어서 Command가 없으면(235–236행) **같은 수정자**로 글자 이벤트를 보낸다(242행). 둘 다 `LWWindowPeer.notifyKeyEvent`(1000행)가
    이벤트 큐에 넣으므로(1022행 `postEvent`) EDT에서 누름이 끝까지 처리된 뒤에 글자 이벤트가 온다. Option 글자는 macOS에서 인쇄 글자이고
    (`LWCToolkit.isPrintableCharacterModifiersMask` 861–862행: Command·Control만 봄), Swing의 기본 글자 동작이 그것을 넣는다
    (`DefaultEditorKit.DefaultKeyTypedAction` 904행, RSyntaxTextArea의 같은 동작은 이것에 맡김). 키 바인딩이 누름을 처리해도 Swing은 뒤의 글자 이벤트를
    버리지 않는다(`DefaultKeyboardFocusManager.consumeNextKeyTyped` 1159–1176행: 초점 이동 키와 열린 메뉴의 니모닉에만 쓰임).
    그래서 ⌥P는 실행하고 "π"도 SQL에 넣었고(다음 실행이 그 글자에서 실패), ⌥X도 "≈"를 넣었을 것이다.
  - 고침: macOS에서만 설치하는 키 디스패처 `MacOptionKeyGuard`. Option(Command 없이) 누름을 기억하고, 바로 다음 글자 이벤트가 올 때 그 누름이
    소비되었으면(단축키·키 바인딩이 처리하면 `JComponent.processKeyEvent`가 소비함) 글자 이벤트를 버린다. 단축키 목록을 두지 않으므로 앞으로 더할
    Option 단축키에도 맞는다. 처리되지 않은 누름(⌥O "ø")의 글자는 그대로 가고, 데드 키(⌥E, ⌥N)는 누름에 글자 이벤트가 없고 다음 키의 누름이
    기억을 지우므로 조합도 그대로다. 디스패처는 누름에서 `false`를 돌려주어 처리 순서를 바꾸지 않는다.
  - Windows·Linux에는 설치하지 않는다: Alt만 누른 글자는 인쇄 글자가 아니라(`SunToolkit.isPrintableCharacterModifiersMask`) 이 문제가 없다.

## upstream 대비 변경점

DBeaver(SWT)는 Eclipse 키 바인딩(`M1` = 플랫폼 명령 키)으로 같은 일을 했다. Swing 프런트엔드는 Chromite가 새로 만든 것이라 upstream 코드는 바뀌지 않았다.

## 확인 (2026-11-09)

- `UiKeysTest`(JUnit, `test/com.seizia.chromite.ui.swing.test`): 앱이 `UiKeys.command`로 거는 Windows 키 전체(57개)를 macOS 명령 키로
  돌려 (1) Windows에서는 그대로 Ctrl, (2) 위 "macOS에서 다른 키" 표, (3) 어느 키도 macOS 시스템 키(⌘H, ⌥⌘H, ⌘M, ⌥⌘M, ⌘Q, ⌘W, ⌘,, ⌘`,
  ⇧⌘/, ⌥⌘D, ⌘Space, ⌘Tab, ⇧⌘3/4/5, ⇧⌘Q, ⌥⌘Esc, ⌃⌘F/Q/Space/D)가 되지 않음, (4) 서로 다른 Windows 키가 같은 macOS 키가 되지 않음,
  (5) 글자(⇧⌘F, ⌥⇧⌘W, ⌃`, Windows "Ctrl+Shift+F"), (6) 마우스 수정자(macOS Control+클릭은 다중 선택이 아님)를 본다.
- 결과와 실행 방법은 아래 변경 이력과 [regression-tests.md](regression-tests.md)를 본다. 실제 Mac에서 키를 누르는 확인은 사용자 몫이다
  ([mac-ui.md](mac-ui.md) "확인").

## 남긴 것

- Mac 키보드에 없는 키: Insert(Duplicate Rows), Pause(Cancel Query). 메뉴와 표의 팝업 메뉴로는 된다. macOS용 다른 키는 정하지 않았다.
- F 키(F5 실행, F3, F4, F8): macOS 기본 설정에서는 fn과 함께 눌러야 한다. 바꾸지 않았다.
- Option(⌥) 단축키(⌥P, ⌥X)의 글자: 위 "Option 단축키의 글자"로 막았지만 실제 Mac에서는 확인하지 않았다. 확인한 것은 JDK 소스 읽기와,
  Windows에서 macOS의 이벤트 순서를 Swing 수준에서 흉내 낸 시험뿐이다. 실제 Mac에서 남은 것: 화면 메뉴 막대(Aqua)에서도 메뉴 단축키가 Swing
  키 바인딩으로 처리되어 누름이 소비되는지, 한글 등 입력기 사용 중의 ⌥ 키, 데드 키의 실제 조합 경로([regression-tests.md](regression-tests.md) Mac 확인 목록).

## 변경 이력

- 2026-11-09 최초 작성(앱 변경 #24, `chromite/mac-ui`): `UiKeys`로 명령 키를 한 곳에서 정함(Swing UI 24개 파일, 키 바인딩·도움말 글자 106곳, 수정자 검사 5곳), macOS 예외 표,
  .properties의 "Ctrl+" 70줄을 `{0}`으로 바꾸고 글자를 단축키에서 만듦, `WelcomePanel.getKeyText`를 `UiKeys.getText`로 옮김, `UiKeysTest`.
- 2026-11-12 쿼리 편집기 열 선택 키 `UiKeys.columnSelect`·`isColumnSelectDown`과 표의 줄(앱 변경 #49, [swing-sql-column-selection.md](swing-sql-column-selection.md)).
- 2026-11-12 Ctrl+Shift+T(⇧⌘T)를 Reopen Closed Tab으로, Maximize Results는 Ctrl+Shift+R(⇧⌘R, Results 보이기·숨기기 Ctrl+R의 짝)로 옮김. ⇧⌘R·⇧⌘T는
  macOS 예약 키가 아니고 다른 명령과 겹치지 않는다(`UiKeysTest` 목록에 Ctrl+Shift+R을 더함, 테스트는 실행하지 않음). 앱 변경 #66, `chromite/small-fixes-r2`.
  확인(테스트 에이전트, 150%): 닫은 쿼리 창이 Ctrl+Shift+T로 글과 함께 돌아오고, Ctrl+Shift+R이 결과 최대화를 켜고 끔. Window 메뉴에 "닫은 탭 다시 열기 Ctrl+Shift+T".
- 2026-11-12 비교 탭의 이전/다음 차이 Alt+↑/Alt+↓(⌥↑/⌥↓): 초점이 그 탭 안에 있을 때만 걸림(탭 패널의 `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT`, 그 탭의 두 편집기는 줄 옮기기 키를 덮음).
  메인 창은 Alt+↑/↓를 묶지 않고(Query 메뉴는 Alt+PageUp/PageDown), Alt를 떼어도 그 사이에 다른 키를 눌렀으므로 메뉴 막대가 골라지지 않는다(앱 변경 #102와 무관). F7(Object Details)은 그대로.
  비교 명령 자체에는 단축키를 두지 않았다(앱 변경 #105, `chromite/text-compare`).
- 2026-11-12 "Alt 키와 메뉴 막대"(위 설계 결정): 마우스에 쓴 Alt의 떼기는 메뉴 막대를 고르지 않음, `AltMouseMenuGuard`(앱 변경 #102, `chromite/alt-mouse-menu`).
- 2026-11-15 Execute가 F5 외에 Alt+X(⌥X)도 받음(앱 변경 #122, `chromite/execute-alt-x`, 위 "Execute의 둘째 키"): 표의 Execute 줄, `UiKeysTest`에 Alt+X가
  macOS 예약 키가 아니고 명령 키 단축키와 겹치지 않는지 보는 줄(테스트는 실행하지 않음), "남긴 것"에 ⌥ 글자 입력을 JDK 소스로 읽은 결과.
  확인(Windows, 빌드한 앱, 시험 PostgreSQL, 앱 창 안 Robot 키 입력, 영어·한국어 화면): 편집기(선택 없음·한 줄 선택)·결과 표·Messages에서 Alt+X가
  F5와 같은 문장을 실행(쿼리 관리자 기록), Object Explorer 트리·ERD 탭에서는 둘 다 실행하지 않음, 실행 중에는 둘 다 아무것도 하지 않음, 서버 없는 창에서는
  둘 다 Select Server 대화상자, Alt+X로 실행한 DROP DATABASE도 위험한 문 확인을 보임. 편집기에 글자가 들어가지 않고 메뉴가 열리지 않음, Alt만 누르면 예전처럼 File 메뉴,
  Alt+P 그대로, 한글 입력 모드(한/영 키로 'r'이 'ㄱ'이 됨)에서도 Alt+X 실행. 연결 새로 고침, CREATE/DROP DATABASE, 끊고 다시 연결한 뒤에도 같음.
  메뉴 항목·도구 막대 도구 설명은 F5 그대로. 열 선택이 켜져 있으면 F5·Alt+X·Alt+P 모두 실행되지 않는데, 이는 이 변경 전부터 있던
  `SqlColumnSelection.handleKeyPressed`의 예외(바인딩 없는 키에서 `Set.of(...).contains(null)`) 때문이다(고침은 따로 `chromite/column-selection-keys`).
- 2026-11-15 macOS: 단축키가 처리한 Option 키 누름의 글자를 버리는 `MacOptionKeyGuard`(앱 변경 #122, `chromite/execute-alt-x`, 위 "Option 단축키의 글자"),
  "남긴 것"의 ⌥ 줄을 고침. 확인(Windows, 빌드한 앱, 시험 에이전트가 macOS 경로만 켜고 이벤트 큐에 누름·글자·떼기를 넣음): ⌥P·⌥X는 실행하고 글자가
  편집기에 가지 않음, 묶이지 않은 ⌥O는 글자가 편집기에 감('ø'), 데드 키 흉내(⌥N 누름 뒤 조합 글자 'ñ')도 들어감(⌥E는 Windows에서 Edit 메뉴
  니모닉이라 흉내에 쓰지 않음). 가드 없이는 같은 순서에서 'π'가 편집기에 들어감.
  Windows에는 설치되지 않음(키 디스패처 목록). 실제 Mac은 확인하지 않음. 회귀 테스트는 실행하지 않았다.
