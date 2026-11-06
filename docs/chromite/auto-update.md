# 자동 업데이트 (앱 안의 업데이터)

## 목적

Chromite가 스스로 새 버전을 확인하고, 뒤에서 받아 확인한 뒤, 사용자가 다시 시작하거나 닫을 때 설치한다.
사용자 결정(2026-11-07): 자동 업데이트와 내려받기 페이지를 Turbrix가 쓰는 서버의 https://chromite.seizia.com 에 둔다.
클라이언트 규칙은 Turbrix 데스크톱의 업데이터를 그대로 따른다(Turbrix `docs/contracts/step8-update-and-background.md`,
`apps/desktop/electron/updater.cjs`·`updateCheck.cjs`·`updateFiles.cjs`·`macUpdate.cjs`).

설치 프로그램, 업데이트 목록(releases.json) 생성, 배포 스크립트, 내려받기 사이트는 [distribution.md](distribution.md)(Part A)에 있다.
이 문서는 앱 쪽(Part B)이다.

## 구조

| 위치 | 하는 일 |
|---|---|
| `plugins/com.seizia.chromite.model.update` (새 모델 번들) | 목록 읽기, 버전 비교, 받기, 파일 확인, 상태, 끝날 때 적용. UI 없음 |
| `UpdateService` | 업데이터 본체: 시점(10초 뒤, 한 시간마다), 상태 기계, 실패 기록, 끝날 때 적용 |
| `UpdateFeed`, `UpdateEntry`, `UpdateTarget`, `UpdateVersion` | 목록 형식 검사와 고르기 |
| `UpdateHttp` | 목록·파일 요청(고정 User-Agent, 1 MB, 10초, 되돌려 보내기 안 따름) |
| `WindowsUpdateVerifier`, `MacUpdateVerifier`, `UpdateVerifier` | 받은 파일 확인 |
| `UpdatePlace` | 이 복사본이 업데이트할 수 있는지(개발 빌드, 설치본, zip 복사본) |
| `UpdateApplier` | 앱이 끝난 뒤 설치하는 도우미(Windows PowerShell, macOS 셸 스크립트), 설치 중 잠금 |
| `UpdatePrefs` | `<업데이트 폴더>/update.json`: 자동 확인 켬/끔, 마지막 확인 실패 기록 |
| `UpdateTestMode` | 개발 빌드 전용 시험 모드 |
| `plugins/com.seizia.chromite.ui.swing`: `UpdateUi` | 제목 표시줄 단추, 상태 표시줄 진행 |
| `UpdateOptionsPage` | Tools > Options > Updates 탭 |
| `ExitTransactionCheck` | 끝낼 때 커밋하지 않은 트랜잭션 질문(커밋 / 롤백 / 취소) |
| `internal/UpdateMessages*.properties` | 화면 문구(영어, `_ko`) |
| `MainFrame`(두 줄), `OptionsDialog`(세 줄), `ChromiteSwingApplication` | 연결 지점 |
| `features/com.seizia.chromite.swing.feature`, `plugins/pom.xml` | 새 번들 등록 |

업데이트 폴더: Windows `%LOCALAPPDATA%\Chromite\updates`(로밍 프로필이 아닌 곳, 설치 파일이 크다),
macOS `~/Library/Application Support/Chromite/updates`.

```
updates/
  update.json                       자동 확인 스위치, 실패 기록
  <버전>/Chromite-<버전>-x64-setup.exe   받아서 확인한 새 버전 (macOS: zip과 staging/<앱>.app)
  previous/<지금 버전>/...           되돌리기용 지금 버전 설치 파일 (Windows)
  before-<새 버전>/<앱>.app          바꿔 넣기 전의 앱 (macOS, 도우미가 옮김)
  apply.lock                        도우미가 설치하는 동안
  apply-update.log                  도우미 기록
```

## 동작

### 업데이트하는 복사본 (`UpdatePlace`)

| 경우 | 동작 |
|---|---|
| 런처 없이 실행(IDE), Maven 결과물(`.../target/products/...`)에서 실행한 개발 빌드, 지원하지 않는 OS·CPU | 확인하지 않는다(목록도 읽지 않음) |
| Windows: 설치 프로그램이 설치한 폴더(앱 및 기능 제거 정보의 `InstallLocation`이나 `UninstallString`의 실행 파일이 이 폴더)이고 쓸 수 있음 | 받아서 확인하고, 끝날 때 설치 |
| Windows: 그 밖(릴리스 zip을 푼 폴더 등), 쓸 수 없는 설치 폴더(관리자 설치) | 새 버전이 있다고만 알리고 내려받는 곳을 연다 |
| macOS: `<이름>.app` 묶음, Gatekeeper가 옮겨 실행 중(`/AppTranslocation/`)이 아니고 앱과 그 폴더에 쓸 수 있음 | 받아서 확인하고, 끝날 때 바꿔 넣기 |
| macOS: 옮겨 실행 중이거나 쓸 수 없음(디스크 이미지 등) | 알리기만 |

"개발 빌드"는 `eclipse.launcher` 시스템 속성(Eclipse 런처가 넣음)이 없거나, 런처가 Maven·Tycho 제품 결과물 폴더(`target` 다음 `products`) 아래 있는 경우다.
설치본 판정은 설치 프로그램이 정하는 제거 정보의 키 이름을 보지 않는다. 앱 및 기능 항목에는 `UninstallString`이 반드시 있다.

### 확인 (`UpdateService`, `UpdateFeed`, `UpdateHttp`)

- 시작하고 10초 뒤 한 번, 그 뒤 켜져 있는 동안 한 시간마다. 옵션의 [지금 확인]은 자동 확인이 꺼져 있어도 바로 돈다.
- 요청: `GET https://chromite.seizia.com/downloads/releases.json`. User-Agent는 고정 값 `Chromite`(버전, OS를 넣지 않음),
  `Accept: application/json` 외에 보내는 것이 없다(쿼리, 본문, 쿠키 없음). 되돌려 보내기(3xx)는 따르지 않는다.
  연결부터 본문 끝까지 10초, 본문 1 MB까지. UTF-8이 아니거나 엄격한 JSON이 아니면 버린다.
- 실패(오프라인, 폐쇄망, 시간 초과, 사이트 오류, 형식 오류)는 조용히 넘어가고 다음 차례에 다시 본다. 화면에 아무것도 보이지 않는다.
  옵션의 [지금 확인]을 눌렀을 때만 "업데이트 사이트에 연결하지 못했습니다"를 보인다.
- 목록: `format`이 정확히 1이 아니면 목록 전체를 버린다. 항목마다 확인하고 틀린 항목만 버린다.
  - `file`은 안전한 이름(`^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$`)이고, 종류별 계약 이름과 정확히 같다:
    `setup` → `Chromite-<v>-<arch>-setup.exe`, `zip` → `Chromite-<v>-<arch>.zip`, `dmg` → `Chromite-<v>-<arch>.dmg`.
  - `version`은 `^\d{1,6}\.\d{1,6}\.\d{1,6}$`, `platform`·`arch`·`kind`는 `^[a-z0-9]{1,16}$`, `size`는 1 이상 2 GB 이하 정수,
    `sha256`은 16진수 64자. `notes_url`은 같은 출처(`https://chromite.seizia.com/`로 시작, 512자 이하)일 때만 쓴다.
  - 받을 주소는 목록의 값이 아니라 같은 출처의 `/downloads/<file>`이다.
- 고르기: 이 설치의 `platform`·`arch`·`kind`(Windows `windows`/`x64`/`setup`, macOS `macos`/`arm64`/`zip`)가 같고,
  지금 버전보다 **엄격히 높은** 것 중 가장 높은 것. 같거나 낮은 버전은 고르지 않는다.

### 받기

- 뒤에서 `<업데이트 폴더>/<버전>/<file>.part`로 받고, 목록의 크기를 넘으면 그 자리에서 끊는다. 60초 동안 한 바이트도 오지 않으면 끊는다.
  다 받으면 크기가 같을 때만 이름을 바꾼다. 받는 동안 상태 표시줄 오른쪽에 "새 버전 X 받는 중 (n%)"과 막대를 보인다.
- 앞선 시작에 받아 둔 파일은 다시 받지 않고 확인만 한다. 이미 받아 확인한 버전이면 그 차례를 끝낸다.
- 받기가 끊기면(오프라인 등) 조용히 다음 차례에 다시 받는다. 실패로 적지 않는다.
- 새 버전이 확인을 통과하면, 지금 버전의 설치 파일(목록의 같은 platform·arch·kind·지금 버전)도 한 번 받아 같은 확인을 거친다
  (`previous/<지금 버전>/`, Windows). 되돌리기용이다. 실패하면 지우고 아무것도 알리지 않는다.
- 더 새 버전이 확인을 통과하면 이전에 받은 다른 버전 폴더를 지운다(`previous/`, `before-*`는 남김).

### Windows 확인 (`WindowsUpdateVerifier`)

실행하기 전에, 받은 직후와 끝날 때(적용 직전) 두 번:

1. 크기, SHA-256이 목록과 같다.
2. Authenticode 상태가 `Valid`다.
3. 서명자 인증서의 주체가 정확히 `CN=Seizia, O=Seizia, L=Cheongju-si, S=North Chungcheong, C=KR`다
   (Azure Artifact Signing의 SEIZIA 인증서, Turbrix와 같은 주체. [signing.md](signing.md)).
4. 서명에 타임스탬프가 있다.
5. 파일의 ProductName이 `Chromite`다(아래 "계약에 더한 확인").
6. 파일의 ProductVersion이 목록의 버전과 같다(서명된 옛 설치 파일을 새 버전으로 내미는 것 차단).

서명과 버전 정보는 Windows PowerShell 5.1(`%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe`)의
`Get-AuthenticodeSignature`(내부에서 WinVerifyTrust)와 `(Get-Item).VersionInfo`로 읽는다. 스크립트는 `-EncodedCommand`로 넘기고
(스크립트 파일이 아니라 실행 정책과 상관없음), 파일 경로는 환경 변수로 넘긴다(명령줄에 글로 넣지 않음).
자식 프로세스에는 `PSModulePath`를 넘기지 않는다. PowerShell 7에서 띄운 Chromite가 그 값을 물려주면 5.1이
`Microsoft.PowerShell.Security` 모듈을 불러오지 못한다(이 PC에서 재현).
회사 PC에서 PowerShell이 막혀 있으면 "서명을 읽지 못함"으로 실패한다.

### macOS 확인 (`MacUpdateVerifier`)

1. 크기, SHA-256이 목록과 같다.
2. zip 안의 이름이 모두 실행 중인 앱 묶음 이름(`<이름>.app/`)으로 시작하고 `..`, 역슬래시, NUL이 없다(`zipinfo -1`, 풀기 전).
3. `ditto -x -k`로 `<버전>/staging/`에 한 번 푼다.
4. `codesign --verify --deep --strict`가 통과한다.
5. `codesign -dv`의 번들 ID와 Team ID가 실행 중인 앱과 같다. 실행 중인 앱에 Team ID가 없으면(서명 없는 빌드) 통과하지 않는다.
6. `spctl -a -t exec -vv`가 "accepted"이고 `source=Notarized Developer ID`다.
7. 앱의 `CFBundleShortVersionString`이 목록의 버전과 같다.

다시 확인(끝날 때, 다음 시작)은 zip을 다시 풀지 않고 풀어 둔 앱을 4~7로 확인한다. 도구는 모두 절대 경로로 부른다.

### 적용 (`UpdateService.applyAtExit`, `UpdateApplier`)

- 사용자가 창을 닫거나 File > Exit, 또는 제목 표시줄 단추의 [지금 다시 시작]을 고르면 **정상 종료 경로**(`MainFrame.requestExit`)를 탄다:
  저장하지 않은 편집기·결과 변경 질문 → 커밋하지 않은 트랜잭션 질문(커밋 / 롤백 / 취소) → 창 닫기.
  어디서든 취소하면 앱은 닫히지 않고 다시 시작 요청도 지운다. [지금 다시 시작]은 창 닫기 이벤트(`WINDOW_CLOSING`)를 보내 같은 경로를 탄다.
- 창이 닫힌 뒤 `ChromiteSwingApplication`이 `applyAtExit`를 부른다. 받은 파일을 **한 번 더** 확인하고 도우미를 띄운 뒤 앱이 끝난다.
  다시 확인에 실패하면 파일을 지우고 실패로 적고 설치하지 않는다.
- Windows 도우미(PowerShell, `-EncodedCommand`, 값은 환경 변수로, 작업 폴더는 업데이트 폴더): Chromite 프로세스가 끝나기를 300초까지 기다리고,
  설치 파일의 SHA-256을 한 번 더 보고(앱이 확인한 뒤 바뀌지 않았는지), `setup.exe /S`를 실행해 끝나기를 기다린다.
  설치 프로그램은 앱을 띄우지 않고 종료 코드로 결과를 알린다(0 완료, 1 취소, 2 실패, 실패하면 아무것도 바꾸지 않음, [distribution.md](distribution.md)).
  [지금 다시 시작]이었으면 결과와 상관없이 런처를 다시 띄운다(실패면 이전 버전이 뜬다). 다시 띄울 때 작업 폴더는 설치 폴더다
  (시작 메뉴 바로 가기와 같게. 2026-11-07 현재 작업 공간이 작업 폴더의 `workspace`에 생기는 문제를 다른 작업에서 고치는 중이며, 고친 뒤에도 해가 없다).
  기록은 `apply-update.log`.
- 결과(`apply-result.json`): 도우미가 설치한 버전과 종료 코드를 적는다(도우미 자신의 실패는 음수: -1 도우미 오류, -2 확인 뒤 설치 파일이 바뀜,
  -3 Chromite가 끝나지 않음, -4 macOS 앱을 바꿔 넣지 못함). 다음 시작이 한 번 읽고 지운다. 실행 중인 버전이 그 버전이 아니고 코드가 0이 아니면
  알림 "업데이트를 설치하지 못함 — 버전 X를 설치하지 못했습니다(코드 N). 지금 버전을 계속 씁니다."을 보인다.
  그 버전은 그냥 닫을 때 다시 적용하지 않고(끝날 때마다 실패를 되풀이하지 않게), [지금 다시 시작]은 다시 시도한다.
- macOS 도우미(`apply-update.sh`, 경로는 인자로): 앱이 끝나기를 기다리고, 지금 앱 묶음을 `before-<새 버전>/`으로 옮기고,
  새 앱 묶음을 같은 경로로 옮긴다. 새 앱을 옮기지 못하면 옛 앱을 제자리로 돌린다. 다시 시작이면 `open`으로 띄운다.
- 잠금(`apply.lock`, 도우미 PID): 도우미가 설치하는 동안 Chromite를 다시 열면 "Chromite를 업데이트하는 중입니다. 잠시 후 다시 여십시오."를
  보이고 시작하지 않는다. 도우미는 다시 띄우기 직전에 잠금을 지운다. 15분 넘은 잠금과 끝난 PID의 잠금은 버린다.
- 자동 확인이 꺼져 있으면 받아 둔 버전은 [지금 다시 시작]을 눌렀을 때만 적용한다(그냥 닫을 때는 적용하지 않음).
- 앱이 바깥에서 멈춰질 때(`IApplication.stop`)는 적용하지 않는다.

### 실패

- 받은 파일이 확인을 통과하지 못하면 그 버전 폴더를 지우고 실패로 적는다(`update.json`의 `failed`: 버전, 시각, 단계).
- 그 버전은 스스로는 하루에 한 번까지만 다시 받는다. 그 사이 한 시간마다의 차례는 목록만 읽는다. 다시 시작해도 하루가 줄지 않는다.
- 제목 표시줄에 경고 단추 "업데이트 파일 확인 실패". 설명은 단추의 툴팁과 메뉴 첫 줄이다:
  "받은 버전 X 파일이 확인(단계)을 통과하지 못해 지웠습니다. 회사 네트워크의 보안 장비나 프록시가 내려받기를 막거나 바꿀 수 있습니다. 내일 다시 시도합니다."
  메뉴는 [다시 시도](바로 받기) [알림 끄기]다.
- [알림 끄기]는 그 버전의 실패를 숨긴다(기록에 `dismissed`, 다시 시작해도 숨김). 하루 한 번 재시도는 계속된다.
  통과하면 기록이 지워지고 보통의 "다시 시작" 단추가 보인다. 다른 버전이 실패하면 다시 보인다.
- 받아서 확인한 버전이 있으면 더 높은 버전이 실패해도 그 버전을 계속 보이고 끝날 때 적용한다.

### 화면 (`UpdateUi`, `UpdateOptionsPage`)

| 상태 | 제목 표시줄 오른쪽 단추 | 단추 메뉴 |
|---|---|---|
| 받는 중 | 없음(상태 표시줄 오른쪽에 진행) | - |
| 받아서 확인함 | "새 버전 X · 다시 시작" | [지금 다시 시작] [업데이트 내용] |
| 알리기만(zip 복사본 등) | "새 버전 X" | [받는 곳 열기] [업데이트 내용] |
| 확인 실패 | "업데이트 파일 확인 실패"(경고 색) | 설명, [다시 시도] [알림 끄기] |

단추는 메뉴 막대의 가로 여백(glue) 뒤에 넣는다. FlatLaf가 Windows에서 메뉴 막대를 제목 표시줄에 넣으므로 단추가 제목 표시줄 오른쪽에 보인다.
[업데이트 내용]은 목록의 `notes_url`(같은 출처만)을 브라우저로 연다.

옵션(Tools > Options > Updates): "자동으로 업데이트 확인"(기본 켬), 현재 버전, [지금 확인]과 결과 줄, 이 복사본이 업데이트하지 않거나
알리기만 하는 이유, 확인이 보내는 것의 설명. 스위치는 OK를 누를 때 저장한다(설치 단위, 작업 공간이 아님).

### 끝낼 때 트랜잭션 (`ExitTransactionCheck`)

사용자 규칙 "트랜잭션 안전"(2026-11-07)에 따라, 끝낼 때(창 닫기, File > Exit, [지금 다시 시작]) 모든 연결의 실행 문맥에서
커밋하지 않은 트랜잭션(`QMUtils.isTransactionActive`)을 찾는다. 있으면 연결 목록과 함께 [커밋] [롤백] [취소]를 묻는다.
답은 모든 문맥에 적용하고, 진행 대화상자에서 `DBCTransactionManager.commit`/`rollback`을 한다. 하나라도 실패하면 오류를 보이고 앱을 닫지 않는다.
SWT의 `ApplicationWorkbenchAdvisor.closeActiveTransactions`와 `DataSourceHandler.checkAndCloseActiveTransaction`(#68에서 삭제)을 옮겼다.
SWT는 문맥마다 물었지만, 끝낼 때는 편집기 저장 질문처럼 한 번에 묻는다. 이 작업 전의 Swing 종료 경로는 트랜잭션을 묻지 않았다.

### 시험 모드 (`UpdateTestMode`, 개발 빌드 전용)

이 PC에서 업데이트 경로 전체를 시험하려고 둔 스위치다. **릴리스 빌드에서는 켜지지 않는다.**

| 시스템 속성 | 뜻 |
|---|---|
| `chromite.update.test.origin` | 사이트 주소 `http(s)://host[:port]`. 이 속성이 있어야 시험 모드 |
| `chromite.update.test.publisher` | 기대하는 서명자 주체(없으면 실제 게시자) |
| `chromite.update.test.product` | 기대하는 ProductName(없으면 `Chromite`) |

- 켜지는 조건(모두): 이 복사본이 설치 프로그램으로 설치된 것이 아니고, Windows는 실행 중인 런처가 우리 게시자로 `Valid` 서명되어 있지 않다
  (릴리스 빌드의 런처는 CI가 서명한다), macOS는 실행 중인 앱에 Team ID가 없다(ad hoc). 아니면 속성을 무시하고 로그에 남긴다.
- 시험 모드에서는 개발 빌드도 "설치본"처럼 받고 확인한다. 서명 상태 `Valid`와 타임스탬프 요구는 그대로다.
  Windows가 신뢰하는 체인으로 서명된 파일만 통과하므로, 이 PC에서는 SEIZIA가 서명한 Turbrix 설치 파일로 시험했다
  (`chromite.update.test.product=Turbrix`). 로컬 시험 인증서를 신뢰 저장소에 넣는 방법은 보안 설정을 바꾸므로 쓰지 않았다.
- 시험 모드의 도우미는 설치 파일을 **실행하지 않는다**(dry run, 기록만 남김). 기다리기, SHA-256 재확인, 다시 시작은 그대로 한다.
  macOS 시험 모드도 앱을 바꿔 넣지 않는다.
- 속성은 `-vmargs` 대신 환경 변수 `JAVA_TOOL_OPTIONS`로 넘기면 도우미가 다시 띄운 Chromite도 같은 시험 모드로 뜬다(도우미가 환경을 물려줌).
  업데이트 폴더를 C:에서 옮기려면 `LOCALAPPDATA`를 바꿔 띄운다.

## 계약(Part A)과의 관계

공유 계약(목록 형식, 파일 이름, 설치 파일 조건)을 그대로 따른다. 계약에 없던 것과 Part A가 지켜야 하는 것:

- **ProductName 확인(계약에 더한 확인)**: SEIZIA는 Turbrix와 Chromite를 같은 게시자로 서명한다. 게시자·버전만 보면 사이트가 뚫렸을 때
  서명된 Turbrix 설치 파일을 Chromite 업데이트로 내밀 수 있다(이 PC에서 Turbrix 0.3.0 설치 파일이 게시자·타임스탬프·버전 확인을 모두 통과함).
  그래서 설치 파일의 VersionInfo `ProductName`이 정확히 `Chromite`여야 한다(NSIS `VIAddVersionKey ProductName "Chromite"`).
- `ProductVersion` 문자열은 정확히 `<v>`(예: `0.1.1`)여야 한다. `0.1.1.0`은 통과하지 않는다(NSIS `VIProductVersion`은 네 자리지만
  `VIAddVersionKey ProductVersion`은 세 자리 문자열로).
- 설치본 판정: 앱 및 기능 항목의 `InstallLocation` 또는 `UninstallString`이 실제 설치 폴더(`chromite-swing.exe`가 있는 폴더)를 가리켜야 한다.
- 조용한 업데이트: `setup.exe /S`가 같은 폴더에 덮어쓰고, 끝나면 종료 코드 0. 업데이트는 사용자별 설치(쓸 수 있는 폴더)에서만 적용한다.
- 2026-11-07 Part A의 설치 프로그램(`tools/installer/chromite.nsi`, 그 작업 트리에서 읽음)과 그 로컬 빌드
  `Chromite-0.1.1-x64-setup.exe`(서명 없음)로 위 조건을 확인했다: `ProductName` = `Chromite`, `ProductVersion` = `0.1.1`,
  HKCU `...\Uninstall\Chromite`의 `InstallLocation`·`UninstallString`, `RequestExecutionLevel user`, 설치 폴더 `%LOCALAPPDATA%\Programs\Chromite`.
  서명이 없어서 이 파일은 "서명" 단계에서 통과하지 않는다(의도한 동작).
- macOS zip: 맨 위가 실행 중인 앱과 같은 이름의 묶음 하나(`ditto -c -k --keepParent`, `__MACOSX/`가 생기는 `--sequesterRsrc`를 쓰지 않음).
  앱 묶음 이름을 바꾸면 이전 이름의 앱은 그 zip을 받지 않는다(이름 확인).
- 사이트의 개인정보 안내: 업데이터는 고정 User-Agent `Chromite`만 보낸다.

## 설계 결정과 이유

- **모델 번들을 새로 만들었다**(`com.seizia.chromite.model.update`): 모델·UI 분리 규칙. UI 번들은 화면만 갖고, 목록·확인·상태는 OSGi 없이도
  돌 수 있게 했다(시험 하네스가 같은 클래스를 그대로 씀). Swing 제품 피처에만 넣었다(헤드리스 런타임 피처에는 넣지 않음).
- **PowerShell로 서명 확인**: 계약이 허용한 두 방법 중 Turbrix가 쓰는 방법. WinVerifyTrust를 JNA로 부르면 서명자 주체와 타임스탬프를 읽기 위해
  암호화 API를 여럿 더 불러야 한다. PowerShell은 서명 하나에 약 2.6초(이 PC)라 받은 직후와 끝날 때만 부른다.
- **ProductName 확인을 더했다**: 위 "계약(Part A)과의 관계". 보고서에도 적었다.
- **도우미 스크립트를 파일로 쓰지 않음(Windows)**: `-EncodedCommand`는 실행 정책의 적용을 받지 않고, 업데이트 폴더에 실행 파일을 남기지 않는다.
- **끝날 때마다 적용**(다시 시작뿐 아니라 그냥 닫을 때도): Turbrix의 "Claude Code 방식"(사용자 결정, Turbrix 2026-10-18)을 따랐다.
  자동 확인을 끄면 그냥 닫을 때는 적용하지 않는다(끈 사용자의 뜻).
- **실행기(데몬 스레드 하나)**: Eclipse Job 대신 쓴 이유는 진행 UI가 없는 주기 작업이고, 모든 확인·받기를 한 스레드에서 차례로 돌리면
  겹침과 잠금 문제가 없기 때문이다.
- **되돌리기 화면은 만들지 않았다**: 계약은 "이전 버전 설치 파일을 한 번 받아 확인"까지다. 받아 둔 파일과 `data_generation`으로
  나중에 되돌리기(경고 포함)를 만들 수 있다.

## upstream 대비 변경점

DBeaver는 p2 업데이트 사이트와 dbeaver.io 버전 확인을 쓴다. Chromite Swing 제품에는 그 기능이 없고(SWT 삭제, [swt-removal.md](swt-removal.md)),
이 업데이터는 새로 만들었다. dbeaver.io를 쓰지 않는다.

| 파일 | 변경 |
|---|---|
| `plugins/com.seizia.chromite.model.update/**` | 추가 |
| `plugins/com.seizia.chromite.ui.swing/.../UpdateUi.java`, `UpdateOptionsPage.java`, `ExitTransactionCheck.java`, `internal/UpdateMessages*` | 추가 |
| `MainFrame.java` | 상태 표시줄을 만든 뒤 `UpdateUi.install` 한 줄, `requestExit`에서 창을 닫기 전에 `ExitTransactionCheck.confirm` |
| `OptionsDialog.java` | Updates 탭과 OK 때 적용 |
| `ChromiteSwingApplication.java` | 시작할 때 설치 중 잠금 확인, 창이 닫힌 뒤 `applyAtExit`, `stop()` 표시 |
| `plugins/com.seizia.chromite.ui.swing/META-INF/MANIFEST.MF` | `com.seizia.chromite.model.update` 요구 |
| `plugins/pom.xml`, `features/com.seizia.chromite.swing.feature/feature.xml` | 새 번들 |

## 확인한 것

2026-11-07, Windows 11 PC, 이 브랜치. 회귀 테스트는 요청되지 않아 저장소에 넣지 않았다. 아래 시험 코드는 작업 폴더(`D:\Tools\_scratch\auto-updater`)에 있다.

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` `BUILD SUCCESS`, 이어서
  `java tools/jre/BundleJre.java windows-x64` 성공. `python tools/rename/gate.py` `ALL GATES PASSED`.
- 모델 시험(OSGi 없이 같은 클래스, 181개 통과): 버전 비교와 형식, 목록(계약 예시, format 2·문자열·없음, 엄격한 JSON, 항목마다 경로·이름·버전·크기·SHA·토큰 거부,
  다른 출처의 notes_url), 고르기(가장 높은 것, 같은·낮은 버전과 다른 arch 안 고름, dmg 대신 zip), macOS 순수 함수(zip 이름, `codesign`·`spctl` 출력, 게시자 비교),
  설치본 판정(Maven 결과물은 꺼짐, 등록되지 않은 폴더는 알리기만, 이 PC의 실제 앱 및 기능 항목 인식), `update.json`, 잠금,
  HTTP(로컬 서버: 보낸 헤더가 `Host`·`Accept`·`User-Agent: Chromite`뿐, 1 MB 초과 거부·정확히 1 MB 허용, 3xx 안 따름, 500·UTF-8 아님 거부,
  응답 지연·본문 지연 모두 10초에 끊김, 받기의 크기 초과·부족·Content-Length 불일치·취소·404, 60초 정지에 끊김),
  상태 기계(받기 → 확인 → 준비, 이전 버전 설치 파일, 실패 기록과 하루 한 번, [다시 시도], [알림 끄기]가 다시 시작해도 남음, 오프라인, 알리기만, 개발 빌드,
  자동 확인 끔, 끝날 때 바뀐 파일은 적용 안 함, 설치 실패 결과를 한 번 알림, 실패한 버전을 그냥 닫을 때 다시 적용 안 함).
- Windows 서명 확인(실제 파일): SEIZIA가 서명한 Turbrix 0.3.0 설치 파일은 `Valid`·게시자·타임스탬프·ProductVersion을 모두 통과하고
  ProductName에서만 거부된다(시험 모드로 ProductName을 바꾸면 모두 통과). 한 바이트 바꾼 사본 "서명", Microsoft 서명 `notepad.exe` "게시자",
  서명 없는 파일 "서명", 크기·SHA 불일치, 공백·한글·`'`·`$`·`;`·백틱·대괄호가 든 경로. 서명 하나 읽기 약 2.6초.
  Part A의 로컬 빌드 `Chromite-0.1.1-x64-setup.exe`는 ProductName `Chromite`, ProductVersion `0.1.1`, 서명 없음(`NotSigned`)으로 읽혔다.
- Windows 도우미(실제 PowerShell, 가짜 설치 파일·런처): 앱이 끝나기를 기다림, `/S`로 실행, 종료 코드 0·2를 결과 파일에 적음, 설치 파일이 바뀌면 실행하지 않음,
  다시 띄운 런처의 작업 폴더가 런처 폴더, 환경 변수 지움, 잠금 지움.
- macOS 도우미 스크립트(Git Bash의 POSIX sh, 가짜 앱 묶음): 바꿔 넣기와 옛 앱 보관, 새 앱을 옮기지 못하면 옛 앱 복구, 다시 시작만, 결과 파일.
- 앱(빌드한 Swing 제품, 시험 모드, 로컬 사이트 `python` 서버, 데이터·업데이트 폴더는 D:): 10초 뒤 확인, 상태 표시줄 받기 진행(속도를 줄인 서버),
  제목 표시줄 "새 버전 0.3.0 · 다시 시작"과 메뉴, 옵션 Updates 탭, Part A의 서명 없는 설치 파일로 "업데이트 파일 확인 실패"(서명)와 메뉴·[알림 끄기],
  다음 시작의 "업데이트를 설치하지 못함" 알림, 한국어·영어, 라이트·다크.
  사이트 기록에 남은 요청은 `User-Agent: Chromite`, `Accept`, `Host`뿐이었다.
- 끝낼 때 트랜잭션(PostgreSQL 17 `chr_auto_updater`, 수동 커밋 연결에서 INSERT, 서버에서 `idle in transaction` 확인):
  [지금 다시 시작] → 저장 질문 → "커밋하지 않은 트랜잭션" 질문. [취소]는 앱이 남고 트랜잭션도 그대로(`idle in transaction`, 행 0),
  [롤백]은 롤백 뒤 앱이 끝나고(행 0, 열린 트랜잭션 없음) 도우미가 기다렸다가(dry run) 새 프로세스로 다시 띄웠다(작업 폴더 = 설치 폴더).
- 그냥 닫을 때 적용하지 않는 경우: 지난번 설치에 실패한 버전은 창을 닫아도 도우미가 뜨지 않았다.

## 확인하지 못한 것

- 실제 서명된 Chromite 릴리스(첫 `v*` 태그) 사이의 업데이트: 0.1.0 설치본 → 0.1.1 설치 파일 받기 → 확인 → `setup.exe /S` → 다시 시작.
  Chromite 설치 프로그램(Part A)이 아직 없어서 이 PC에서는 SEIZIA가 서명한 Turbrix 설치 파일로 확인 단계까지만, 설치는 dry run으로 했다.
- macOS 전체(확인 도구 호출, 바꿔 넣기, `open`으로 다시 시작, Gatekeeper "앱 관리" 보호). Windows에서는 순수 함수(이름 검사,
  `codesign`·`spctl` 출력 해석, 게시자 비교)와 도우미 스크립트의 옮기기 논리(POSIX sh)만 시험했다.
- 실제 NSIS 설치 프로그램을 도우미가 `/S`로 실행하는 것(시험 모드는 실행하지 않음, 가짜 설치 파일로만 도우미를 확인). 설치 프로그램이 이 PC의
  사용자별 설치 폴더(C:)를 바꾸므로 이 작업에서는 하지 않았다.
- 그냥 닫은 직후 Windows를 끄면 조용한 설치가 중간에 끊길 수 있다(Turbrix와 같은 위험). 시험하지 않았다.
- 회사 프록시·TLS 검사 장비 뒤에서의 동작.

## 변경 이력

- 2026-11-07 최초 작성. 앱 안의 업데이터(모델 번들 `com.seizia.chromite.model.update`, Swing 화면), 끝낼 때 트랜잭션 질문.
