# 단축키: Windows와 macOS

## 목적

앱 변경 #24("맥에서 단축키를 ^ 대신 커맨드 키를 사용하게 하라"). 단축키를 `InputEvent.CTRL_DOWN_MASK`로 박아 두어 macOS에서도
Control 키를 썼다(2026-11-09 devel 기준 Swing UI 19개 파일, `CTRL_DOWN_MASK`·`isControlDown` 97곳과 문자열 키 `"ctrl C"` 2곳, `getMenuShortcutKeyMaskEx` 사용 0곳). 화면 문구(.properties 70줄, 영어·한국어 35줄씩)에도
"Ctrl+"가 박혀 있었다. 단축키 하나씩 고치지 않고, 명령 키를 한 곳에서 정하고 도움말 글자를 단축키에서 만들도록 구조를 바꿨다.

## 구조

| 구성 | 위치 | 하는 일 |
|---|---|---|
| `UiKeys` (새) | `plugins/com.seizia.chromite.ui.swing/.../UiKeys.java` | 명령 키(`Toolkit.getMenuShortcutKeyMaskEx()`: Windows·Linux Ctrl, macOS Command), Windows 키 → 플랫폼 키(`command`), 모든 플랫폼에서 Control인 키(`control`), 마우스 수정자(`isCommandDown`), 단축키 글자(`getText`, `getChordText`, `format`, `formatDescription`) |
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
| Execute / Cancel | F5 / Alt+Pause | F5 / ⌥Pause |
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
| Pin Results / Maximize Results | Ctrl+Shift+P / Ctrl+Shift+T | ⇧⌘P / ⇧⌘T |
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
- Option(⌥) 조합(⌥P 등)은 글자 입력에서 특수 문자를 만든다. 키 바인딩이 먼저 받지만 실제 Mac에서 확인하지 않았다.

## 변경 이력

- 2026-11-09 최초 작성(앱 변경 #24, `chromite/mac-ui`): `UiKeys`로 명령 키를 한 곳에서 정함(Swing UI 24개 파일, 키 바인딩·도움말 글자 106곳, 수정자 검사 5곳), macOS 예외 표,
  .properties의 "Ctrl+" 70줄을 `{0}`으로 바꾸고 글자를 단축키에서 만듦, `WelcomePanel.getKeyText`를 `UiKeys.getText`로 옮김, `UiKeysTest`.
