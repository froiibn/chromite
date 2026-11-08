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
| `UpdateApplier` | 앱이 끝난 뒤 설치하는 도우미(Windows PowerShell, macOS 셸 스크립트), 설치 중 잠금, 앱의 사본 띄우기 |
| `UpdateRun` | 업데이트 기록 `update-run.json`, 업데이트 뒤 첫 시작의 판단, 단계 시간, 설치 폴더의 버전 |
| `UpdateHandOver` | 앱의 사본이 하는 일: 옛 앱 기다리기, SHA-256 재확인, 설치 프로그램을 자식으로 실행, Chromite 다시 띄우기 |
| `UpdatePrefs` | `<업데이트 폴더>/update.json`: 자동 확인 켬/끔, 마지막 확인 실패 기록 |
| `UpdateTestMode` | 개발 빌드 전용 시험 모드 |
| `plugins/com.seizia.chromite.ui.swing`: `UpdateUi` | 제목 표시줄 단추, 상태 표시줄 진행 |
| `UpdateProgress` | 업데이트 진행 창을 언제 무엇으로 보이나(받는 중 단추, [지금 다시 시작], 새 버전의 첫 시작) |
| `UpdateWindowCopy` | 앱의 사본(`<업데이트 폴더>/window-app/`) 만들기 |
| `update/UpdateView`, `update/UpdateWindow` | 진행 창의 내용(순수 함수)과 Swing 창. 사본에서도 돈다 |
| `update/UpdateWindowApp` | 사본의 진입점(OSGi 없이 도는 Java 프로그램) |
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
  update-run.json                   지금 적용하는 업데이트의 기록(옛 앱 → 사본 → 새 버전, 아래 "업데이트 진행 창")
  window-app/                       설치하는 동안 진행 창을 보이는 앱의 사본(jre/, lib/)
  <버전>/Chromite-<버전>-x64-setup.exe   받아서 확인한 새 버전 (macOS: zip과 staging/<앱>.app)
  previous/<지금 버전>/...           되돌리기용 지금 버전 설치 파일 (Windows)
  before-<새 버전>/<앱>.app          바꿔 넣기 전의 앱 (macOS, 도우미가 옮김)
  apply.lock                        도우미나 사본이 설치하는 동안
  apply-update.log                  도우미·사본 기록
  apply-result.json                 도우미(PowerShell, macOS 셸)의 결과, 다음 시작이 한 번 읽음
```

## 동작

### 업데이트하는 복사본 (`UpdatePlace`)

| 경우 | 동작 |
|---|---|
| 런처 없이 실행(IDE), Maven 결과물(`.../target/products/...`)에서 실행한 개발 빌드, 지원하지 않는 OS·CPU | 확인하지 않는다(목록도 읽지 않음) |
| Windows: 설치 프로그램이 사용자별로 설치한 폴더(HKCU 앱 및 기능 제거 정보의 `InstallLocation`이나 `UninstallString`의 실행 파일이 이 폴더)이고 쓸 수 있음 | 받아서 확인하고, 끝날 때 설치(`setup.exe /CurrentUser /S`, 관리자 확인 없음) |
| Windows: 설치 프로그램이 모든 사용자용으로 설치한 폴더(그 제거 정보가 HKLM, 보통 Program Files) | 받아서 확인하고, [지금 다시 시작]의 진행 창에서 관리자 권한으로 설치(아래 "관리자 권한으로 설치"). 그냥 닫을 때는 설치하지 않는다 |
| Windows: 그 밖(릴리스 zip을 푼 폴더 등), 쓸 수 없는 사용자별 설치 폴더 | 새 버전이 있다고만 알리고 내려받는 곳을 연다 |
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
  쿼리 창이 활성이면 상태 표시줄이 그 창 연결 색으로 칠해지므로 진행 글자는 그 색 위의 대비 색을 따른다(2026-11-08, [swing-frontend.md](swing-frontend.md) "상태 표시줄의 쿼리 창 칸").
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
2. zip 안의 이름이 모두 한 앱 묶음(`<이름>.app/`) 안에 있고 `..`, 역슬래시, NUL이 없다(`zipinfo -1`, 풀기 전). 그 묶음은 실행 중인 앱의 이름이거나
   지금 이름 `Chromite.app`이다(2026-11-09: 0.1.3까지의 `ChromiteSwing.app`이 새 이름의 zip을 받는다, [distribution.md](distribution.md) "macOS 앱 이름").
   이름이 달라도 받는 근거는 5번의 번들 ID·Team ID다.
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
- 창이 닫힌 뒤 `ChromiteSwingApplication`이 `applyAtExit`를 부른다. 받은 파일을 **한 번 더** 확인하고 업데이트 기록(`update-run.json`)을 쓴 뒤
  설치를 맡길 프로그램을 띄우고 앱이 끝난다. 다시 확인에 실패하면 파일을 지우고 실패로 적고 설치하지 않는다.
- 설치를 맡는 것(2026-11-08부터, 아래 "업데이트 진행 창"): Windows의 [지금 다시 시작]은 **앱의 사본**이 진행 창을 보이며 설치 프로그램을 실행한다.
  그냥 닫을 때, 사본을 만들지 못했을 때, macOS는 아래의 도우미가 지금까지처럼 화면 없이 설치한다.
- Windows 도우미(PowerShell, `-EncodedCommand`, 값은 환경 변수로, 작업 폴더는 업데이트 폴더): Chromite 프로세스가 끝나기를 300초까지 기다리고,
  설치 파일의 SHA-256을 한 번 더 보고(앱이 확인한 뒤 바뀌지 않았는지), `setup.exe /S`를 실행해 끝나기를 기다린다.
  설치 프로그램은 앱을 띄우지 않고 종료 코드로 결과를 알린다(0 완료, 1 취소, 2 실패, 실패하면 아무것도 바꾸지 않음, [distribution.md](distribution.md)).
  [지금 다시 시작]이었으면 결과와 상관없이 런처를 다시 띄운다(실패면 이전 버전이 뜬다). 다시 띄울 때 작업 폴더는 설치 폴더다
  (시작 메뉴 바로 가기와 같게. 2026-11-07 현재 작업 공간이 작업 폴더의 `workspace`에 생기는 문제를 다른 작업에서 고치는 중이며, 고친 뒤에도 해가 없다).
  기록은 `apply-update.log`.
- 결과(`apply-result.json`): 도우미가 설치한 버전과 종료 코드를 적는다(도우미 자신의 실패는 음수: -1 도우미 오류, -2 확인 뒤 설치 파일이 바뀜,
  -3 Chromite가 끝나지 않음, -4 macOS 앱을 바꿔 넣지 못함). 앱의 사본은 같은 값을 업데이트 기록의 `installCode`에 적는다.
  다음 시작이 한 번 읽고 지운다. 실행 중인 버전이 그 버전이 아니고 코드가 0이 아니면
  알림 "업데이트를 설치하지 못함 — 버전 X를 설치하지 못했습니다(코드 N). 지금 버전을 계속 씁니다."을 보인다(사본의 창이 이미 설치 실패를 보였으면 알림은 없다).
  그 버전은 그냥 닫을 때 다시 적용하지 않고(끝날 때마다 실패를 되풀이하지 않게), [지금 다시 시작]은 다시 시도한다.
- macOS 도우미(`apply-update.sh`, 경로는 인자로): 앱이 끝나기를 기다리고, 지금 앱 묶음을 `before-<새 버전>/`으로 옮기고,
  새 앱 묶음을 같은 폴더에 zip 안의 이름으로 옮긴다(`UpdateApplier.macTarget`, 2026-11-09): `ChromiteSwing.app`(0.1.3까지)은 `Chromite.app`이 되고
  옛 묶음은 `before-<새 버전>/ChromiteSwing.app`에 남는다. 같은 폴더에 `Chromite.app`이 이미 있으면(다른 복사본) 그것을 건드리지 않고 지금 앱의 자리에
  옛 이름 그대로 놓는다(앱이 정할 때와 도우미가 옮기기 직전에 한 번 더 본다). 새 앱을 옮기지 못하면 옛 앱을 제자리로 돌린다. 다시 시작이면 새 앱의 경로를 `open`으로 띄운다.
  이름이 바뀌면 Dock에 고정한 옛 항목과 로그인 항목은 옛 경로를 가리킨다([distribution.md](distribution.md) "macOS 앱 이름"의 "사용자가 보는 것").
- 잠금(`apply.lock`, 도우미나 사본의 PID): 도우미가 설치하는 동안 Chromite를 다시 열면 "Chromite를 업데이트하는 중입니다. 잠시 후 다시 여십시오."를
  보이고 시작하지 않는다. 도우미는 다시 띄우기 직전에, 사본은 설치 프로그램이 끝나면 잠금을 지운다. 15분 넘은 잠금과 끝난 PID의 잠금은 버린다.
- 자동 확인이 꺼져 있으면 받아 둔 버전은 [지금 다시 시작]을 눌렀을 때만 적용한다(그냥 닫을 때는 적용하지 않음).
- 앱이 바깥에서 멈춰질 때(`IApplication.stop`)는 적용하지 않는다.

### 업데이트 진행 창 (2026-11-08, 앱 항목 #11)

사용자 요청(2026-11-08): "앱이 꺼진 이후 설치 현황을 보여줄 수 있게 하라. Turbrix도 최근 이 부분 수정을 하였으므로 어떤식으로 진행하는지 물어봐서 동일하게 하라."
Turbrix #747(main `fb55abc6`, 0.4.21부터)을 그대로 옮겼다. 원본: Turbrix `apps/desktop/electron/updateRun.cjs`·`updateHelper.cjs`·`updateView.cjs`·
`updateWindow.cjs`·`updateProgress.cjs`, `docs/design/DESKTOP.md` 5.3.2절, `docs/design/STYLE_GUIDE.md` 5.15절, `docs/handoff/c239.md`·`c241.md`.

**창**: 주 창과 따로 뜨는 작은 창 하나(`update/UpdateWindow`). 폭 440(배율은 `UIScale`), 높이는 내용에 맞춤, 테두리 없음, 크기 조절 없음, 가장자리는 1px 선.
위 줄은 Chromite 상징·"Chromite 업데이트"·"0.1.3 → 0.1.4"(끌면 창이 움직임), 그 아래 지금 하는 일(18px 제목과 한 줄 설명), 6px 막대,
막대 아래 받은 양과 "걸린 시간 m:ss", 단계 다섯, 아래 줄(안내 글 또는 [업데이트 내용], 단추). 색·글꼴은 앱 테마(FlatLaf, Chromite 라이트·다크)의 값만 쓴다.
작업 표시줄에 "Chromite 업데이트"로 보인다.

| 상태 | 제목 | 단계 표시 | 막대 | 단추(Esc) |
|---|---|---|---|---|
| 받는 중 | 새 버전을 받고 있습니다 | 받기 진행 | 받은 비율, "n% (x MB / y MB)" | [닫기] (Esc = 닫기), 안내 "창을 닫아도 계속 받습니다." |
| 받음 | 새 버전을 받았습니다 | 받기·확인 완료 | 100% | [지금 다시 시작] [나중에] (Esc = 나중에), [업데이트 내용] |
| 설치 중 | 새 버전을 설치하고 있습니다 | 다시 확인 중이면 "업데이트 파일 확인", 아니면 "설치" | 길이를 모르는 막대 | 없음(닫을 수 없음) |
| 시작 중 | Chromite를 시작하고 있습니다 | "Chromite 시작" | 길이를 모르는 막대 | 없음 |
| 완료 | 업데이트를 마쳤습니다 | 모두 완료 | 100% | 없음, 0.6초 뒤 닫힘 |
| 설치 실패 | 새 버전을 설치하지 못했습니다 | "설치" 실패 | 없음 | [다시 시도] [받는 곳 열기] [닫기] (Esc = 닫기) |
| 시작 실패 | Chromite를 시작하지 못했습니다 | "Chromite 시작" 실패 | 없음 | [이전 버전으로 되돌리기](있을 때) [로그 폴더 열기] [닫기] |

- 단계: 새 버전 받기 · 업데이트 파일 확인 · 설치 · Chromite 시작 · 창 열기. Turbrix의 "엔진 시작"은 Chromite에서 작업 공간·번들을 읽는 시작,
  "앱 열기"는 주 창이 뜨는 것이다. 끝난 단계만 걸린 시간을 보이고 모르는 시간은 비운다.
- 걸린 시간: 받는 중은 받기 시작부터, 그 뒤는 [지금 다시 시작]을 누른 때부터. 그냥 닫을 때 적용한 업데이트는 새 버전의 시작부터.
- 설치 실패 설명: "설치 프로그램이 끝까지 실행되지 않았습니다." + (설치 폴더가 아직 옛 버전일 때만) "지금 쓰는 버전은 {0}입니다." +
  "다시 시도하거나 받는 곳에서 직접 받으세요." 설치 폴더의 버전은 `configuration/config.ini`의 제품 번들을 `bundles.info`에서 찾아 읽는다(`UpdateRun.installedVersion`).
  읽지 못하면(지워짐, 반쯤 바뀜) 가운데 문장을 뺀다.
- 시작 실패 설명: 시작 오류 문구 + "자세한 내용은 로그 폴더에 있습니다." 로그 폴더는 작업 공간의 `.metadata`(디버그 로그).
- 진행 중(설치 중·시작 중·완료)에는 창을 닫을 수 없다(Alt+F4 포함). 상태가 바뀌면 첫 단추가 포커스를 받고 주 단추가 Enter 단추다.

**언제 뜨나**

1. 받는 중: 제목 표시줄에 "새 버전 X" 단추가 보이고(상태 표시줄 진행은 그대로), 누르면 창이 받기를 보인다. 창은 스스로 뜨지 않는다.
   받기가 끝나면 열린 창이 "받았습니다"가 된다. 받음 상태의 제목 표시줄 단추는 지금처럼 메뉴를 연다(Turbrix #579와 같음).
2. [지금 다시 시작](옛 버전): 주 창이 닫히면 창이 뜨고 "업데이트 파일 확인"(다시 확인, 서명 확인 약 3초)을 보인다. 그동안 Windows에서는 앱의 사본을 만든다.
3. 앱의 사본(Windows): 옛 앱이 `<업데이트 폴더>/window-app/`에 자기 Java 런타임(`jre/`)과 진행 창에 필요한 jar(`lib/`: Swing UI 번들, 그 안의 FlatLaf,
   업데이트 모델 번들, gson, `NLS`가 든 OSGi 프레임워크 jar)를 복사하고(`UpdateWindowCopy`), 기록에 설치 파일·옛 앱이 확인한 SHA-256·설치 폴더·런처·
   자기 PID·창의 자리·언어·테마를 쓰고, 사본을 `jre\bin\javaw.exe -cp ... UpdateWindowApp <업데이트 폴더>`로 띄운다(작업 폴더는 업데이트 폴더, 잠금 `apply.lock`).
   사본의 창이 떴다는 기록(`helperShownAt`)을 최대 8초 기다린 뒤 자기 창을 닫고 끝난다.
4. 사본(`UpdateWindowApp`, `UpdateHandOver`): 같은 자리에 같은 창을 띄우고, 옛 앱이 끝나기를 기다리고(최대 300초), 설치 파일의 SHA-256이 기록의 값인지 본 뒤
   `setup.exe /S`를 **자기 자식으로** 실행해 종료 코드를 안다. 시작·끝 시각과 코드를 기록에 쓰고 잠금을 지운다.
   0이면 "Chromite를 시작하고 있습니다"로 바꾸고 런처를 `-nosplash`로 띄운다(작업 폴더는 설치 폴더). 새 버전이 창을 이어받으면(`takenAt`) 닫고 끝난다.
   1분 안에 오지 않으면 한 번 더 띄우고, 다시 1분 안에 오지 않으면 시작 실패를 보인다(Turbrix는 조용히 끝나지만 항목 #11의 "시작 실패 → 로그·닫기(+ 되돌리기)"를 따름).
   0이 아니면 설치 실패를 보인다. [다시 시도]는 설치 프로그램을 다시 실행, [받는 곳 열기]는 업데이트 사이트의 받는 곳만, [닫기]는 기록을 지우고 끝난다.
5. 새 버전의 첫 시작: Look and Feel 설치 직후 **시작 화면 대신** 이 창을 띄운다(`UpdateProgress.openFirstStart`, 시작 화면 #193과 겹치지 않음).
   기록의 테마가 다크면 다크로 띄운다(저장된 테마는 작업 공간을 잠근 뒤에 읽는다). [지금 다시 시작]의 기록이면 `takenAt`을 써서 사본을 닫는다.
   작업 공간·번들·서비스를 읽는 동안 "Chromite 시작", 주 창이 뜨면 "업데이트를 마쳤습니다"(창은 주 창 위에 남음) 뒤 0.6초에 닫히고, 기록과 사본 폴더를 지운다.
   시작에 실패하면(작업 공간 잠금, 플랫폼 초기화) 시작 오류 대화상자 대신 이 창이 시작 실패를 보이고, 닫힐 때까지 기다린 뒤 앱이 끝난다.
6. 첫 시작의 판단(`UpdateRun.firstStart`, 기록을 먼저 본다): 기록의 `to`가 이번 버전이고 [지금 다시 시작]에서 1시간 안 → 기록의 시간으로.
   그냥 닫을 때 적용한 기록이고 7일 안 → 이번 시작부터. 기록이 없고 도우미 결과(`apply-result.json`)가 이번 버전의 성공이며 10분 안 →
   "Chromite 시작" 단계부터(이 기능이 없는 0.1.3의 도우미가 남긴 것, 시작 버전은 `previous/`의 버전). 그 밖에 이번 버전의 기록이 남아 있으면 지운다.

**그냥 닫을 때**: Turbrix처럼 설치하는 동안 창이 없다. 도우미가 지금처럼 조용히 설치하고, 기록(`quitAt`)이 다음 첫 시작(7일 안)에 창을 보이게 한다.

**되돌리기**([이전 버전으로 되돌리기], 시작 실패에서만, Windows): 업데이트를 받을 때 함께 받아 같은 확인을 거친 지금 버전의 설치 파일(`previous/<버전>/`)을
옛 앱이 기록의 `previous`(경로, 목록의 SHA-256)로 넘긴다. 사본은 같은 사본으로, 새 버전은 자기 사본을 만들어 같은 방법으로(SHA-256 확인, `/S`, 다시 띄우기)
설치한다. 설치 프로그램은 낮은 버전도 같은 폴더에 설치한다([distribution.md](distribution.md)). Turbrix와 달리 데이터 백업을 되돌리지 않으므로(작업 공간은
설치 폴더 밖이고 업데이트가 바꾸지 않는다) 경고 문구는 두지 않았다.

**macOS**: Turbrix처럼 사본을 쓰지 않는다. 옛 앱이 다시 확인하는 동안만 창을 보이고, 기록을 쓰고 도우미(`apply-update.sh`)가 지금처럼 앱을 바꿔 넣는다.
새 버전의 첫 시작 창은 같은 코드다(이 PC에서 확인하지 못함).

**설치 프로그램과의 관계**(`tools/installer/chromite.nsi`, Chromite 것): 설치 프로그램은 프로세스를 끝내지 않는다. 설치 폴더의 `jre\bin\server\jvm.dll`을 쓰기로 열 수
있을 때까지 기다리고(조용한 설치는 60초, 넘으면 코드 2), 옛 버전의 제거 프로그램이 파일을 지운 뒤 새 파일을 복사한다. 그래서
- 사본은 설치 폴더 밖의 자기 `jvm.dll`을 쓰고 작업 폴더도 업데이트 폴더라 설치를 막지 않는다. 설치 프로그램이 사본을 끝내지도 않는다
  (Turbrix의 electron-builder 설치 프로그램은 같은 이름의 프로세스를 끝내서 다른 이름이 필요했다).
- 사본은 옛 앱이 끝나기를 기다린 뒤 설치 프로그램을 실행한다(옛 앱이 `jvm.dll`을 쥐고 있으면 60초 뒤 2로 끝난다).
- 종료 코드 계약: 0 완료, 1 취소(대화형만), 2 실패(아무것도 바꾸지 않음). 사본은 0만 성공으로 본다.

**사본의 크기**(2026-11-08, 이 브랜치의 빌드, Windows): 91,756,592바이트(약 87.5 MB). 런처의 `jre/` 약 73 MB와 jar 다섯
(Swing UI 번들 약 12 MB, OSGi 프레임워크 1.6 MB, FlatLaf 1 MB, gson 0.3 MB, 업데이트 모델 0.1 MB). 같은 디스크(D:)에서 만드는 데 228–252 ms.

**알려진 한계**
- 0.1.3(이 기능이 없음)에서 올라오는 첫 업데이트는 설치하는 동안 창이 없다(0.1.3의 PowerShell 도우미가 화면 없이 설치). 새 버전(0.1.4)의 첫 시작 창만
  "Chromite 시작" 단계부터 보인다(도우미 결과가 10분 안일 때, 곧 [지금 다시 시작]일 때). 단계 1–3은 시간 없이 완료로 보인다.
- 사본을 쓰는 첫 업데이트는 0.1.4 → 0.1.5다.
- 설치 중 비율은 설치 프로그램이 알리지 않아 길이를 모르는 막대다.

### 관리자 권한으로 설치 (2026-11-09, 앱 변경 #23)

설치 프로그램의 기본이 모든 사용자(Program Files, HKLM)가 되었다([distribution.md](distribution.md) "설치 범위"). 일반 사용자는 Program Files에 쓸 수 없으므로
전에는 이런 설치가 "알리기만"이었다. 이제:

| 설치 범위(앱 및 기능 항목의 위치) | 받기·확인 | 설치하는 때 | 설치 명령 | 관리자 확인 |
|---|---|---|---|---|
| 사용자별(HKCU, 0.1.3까지 모두) | 지금처럼 | 끝날 때마다, [지금 다시 시작] | `setup.exe /CurrentUser /S`(사본의 자식) | 없음 |
| 모든 사용자(HKLM) | 지금처럼 | [지금 다시 시작]만(진행 창과 함께) | `setup.exe /AllUsers /S`, 셸 `runas` | UAC 1번 |

- **범위 판단**(`UpdatePlace`): 이 설치 폴더를 가리키는 제거 정보가 HKLM에 있으면 모든 사용자(`allUsers`), HKCU면 사용자별. 쓸 수 없는 폴더여도 모든 사용자 설치는 `APPLY`다.
- **승격 실행**(`UpdateHandOver.runElevated`, 앱의 사본): 승격된 프로세스는 자식으로 만들 수 없어서(`CreateProcess`) Windows PowerShell 5.1이
  `System.Diagnostics.Process.Start`(셸 실행, 동사 `runas`)로 설치 프로그램을 띄우고 끝나기를 기다려 그 종료 코드로 끝난다(`UpdateApplier.RUN_INSTALLER`,
  스크립트는 `-EncodedCommand`, 경로는 환경 변수). 사본의 창은 그동안 "설치" 단계다. 사본 자신과 다시 띄우는 Chromite는 승격되지 않는다.
- **UAC 거부**: 셸은 `ERROR_CANCELLED`(1223)로 답한다. 결과 코드 `-5`(`RESULT_ELEVATION_REFUSED`)가 되고 설치 프로그램은 실행되지 않는다. 창은 설치 실패로
  "관리자 확인이 취소되어 설치하지 못했습니다. 모든 사용자용으로 설치된 Chromite는 업데이트에 관리자 권한이 필요합니다." + "[다시 시도]를 누르고 사용자 계정 컨트롤 창에서
  [예]를 고르세요."와 [다시 시도]·[받는 곳 열기]·[닫기]를 보인다. [다시 시도]는 관리자 확인부터 다시 한다. 옛 버전 파일은 그대로라 "지금 쓰는 버전은 …"도 붙는다.
- **시간 초과**: 따로 두지 않았다. 기다리는 프로세스를 끝내도 UAC 창은 사라지지 않고, 나중에 [예]를 누르면 아무도 기다리지 않는 설치가 시작되기 때문이다.
  Windows가 답 없이 닫은 창도 같은 `ERROR_CANCELLED`로 올 것으로 보지만 확인하지 못했다(이 PC에서 UAC를 띄우지 않음).
- **그냥 닫을 때**: 모든 사용자 설치는 적용하지 않는다(`UpdateService.toApply`). 창을 닫은 뒤 맥락 없이 UAC 창이 뜨거나, 자리를 비운 사이 설치가 멈추지 않게.
  그래서 제목 표시줄 단추의 툴팁과 옵션의 "준비됨" 줄은 "모든 사용자용 설치라 [지금 다시 시작]을 누르고 관리자 확인을 허용하면 설치됩니다."다.
- **사본을 만들지 못했을 때**(도우미로 대신): PowerShell 도우미도 같은 `RUN_INSTALLER`로 승격 실행하고 거부는 `-5`로 결과 파일에 적는다. 다음 시작이 "업데이트를 설치하지 못함(코드 -5)"을 알린다.
- **되돌리기**(시작 실패의 [이전 버전으로 되돌리기]): 기록의 `allUsers`가 같이 넘어가 이전 버전 설치 파일도 같은 방법으로 승격 실행한다.
- **설치 프로그램 쪽**: `/AllUsers`인데 관리자가 아니면 조용한 실행은 종료 코드 2로 아무것도 바꾸지 않는다. 그래서 업데이터가 승격해서 띄운다.
  사용자별 업데이트에 `/CurrentUser`를 명시하는 것은 범위를 분명히 하려는 것이다(0.1.3의 설치 프로그램은 모르는 스위치를 무시한다, 되돌리기에 쓰일 수 있음).

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
| 받는 중 | "새 버전 X"(상태 표시줄 오른쪽에도 진행) | 메뉴 없음: 업데이트 진행 창이 받기를 보인다(아래 "업데이트 진행 창") |
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
연결 끊기 전에도 같은 찾기·질문·끝내기를 그 연결에 한다(`ExitTransactionCheck.confirmDisconnect`, [idle-transactions.md](idle-transactions.md) "연결 끊기 전 질문").

Chromite Agent·MCP 도구의 트랜잭션(2026-11-07 추가): 에이전트는 자기 격리 문맥의 JDBC 연결에 직접 SQL을 보내므로(`AgentSql`) 쿼리 관리자에
기록이 없고 `QMUtils.isTransactionActive`가 늘 false다. 그래서 수동 커밋 연결에 에이전트가 열어 둔 트랜잭션을 놓쳤다. 이제 연결마다
`AgentSql.hasPendingTransaction`(쓰기 뒤 DB에 직접 물어 기록한 값)도 보고, 목록에 "연결 이름 (Chromite Agent·MCP 도구)"로 넣는다. 같은 답으로
`AgentSql.endTransaction`이 커밋·롤백한다(에이전트 도구 `commitTransaction`/`rollbackTransaction`과 같은 경로, [ai-agent.md](ai-agent.md) "트랜잭션 안전").

### 시험 모드 (`UpdateTestMode`, 개발 빌드 전용)

이 PC에서 업데이트 경로 전체를 시험하려고 둔 스위치다. **릴리스 빌드에서는 켜지지 않는다.**

| 시스템 속성 | 뜻 |
|---|---|
| `chromite.update.test.origin` | 사이트 주소 `http(s)://host[:port]`. 이 속성이 있어야 시험 모드 |
| `chromite.update.test.publisher` | 기대하는 서명자 주체(없으면 실제 게시자) |
| `chromite.update.test.product` | 기대하는 ProductName(없으면 `Chromite`) |
| `chromite.update.test.installer.code` | 앱의 사본이 dry run에서 설치 프로그램의 종료 코드로 알릴 값(없으면 0). 설치 실패 화면 확인용(`-5` = 관리자 확인 거부) |
| `chromite.update.test.allusers` | `true`면 이 개발 빌드를 모든 사용자 설치(HKLM)처럼 다룬다. 사본이 승격 실행 경로를 타되 dry run이라 실행하지 않고 그 명령(`/AllUsers /S (elevated)`)을 기록한다 (2026-11-09) |

- 켜지는 조건(모두): 이 복사본이 설치 프로그램으로 설치된 것이 아니고, Windows는 실행 중인 런처가 우리 게시자로 `Valid` 서명되어 있지 않다
  (릴리스 빌드의 런처는 CI가 서명한다), macOS는 실행 중인 앱에 Team ID가 없다(ad hoc). 아니면 속성을 무시하고 로그에 남긴다.
- 시험 모드에서는 개발 빌드도 "설치본"처럼 받고 확인한다. 서명 상태 `Valid`와 타임스탬프 요구는 그대로다.
  Windows가 신뢰하는 체인으로 서명된 파일만 통과하므로, 이 PC에서는 SEIZIA가 서명한 Turbrix 설치 파일로 시험했다
  (`chromite.update.test.product=Turbrix`). 로컬 시험 인증서를 신뢰 저장소에 넣는 방법은 보안 설정을 바꾸므로 쓰지 않았다.
- 시험 모드의 도우미와 앱의 사본은 설치 파일을 **실행하지 않는다**(dry run, 기록만 남김). 기다리기, SHA-256 재확인, 다시 시작은 그대로 한다.
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
- 조용한 업데이트: `setup.exe /S`가 같은 폴더에 덮어쓰고, 끝나면 종료 코드 0. 2026-11-09부터 범위 스위치를 붙인다: 사용자별 `/CurrentUser /S`,
  모든 사용자 `/AllUsers /S`(승격, 위 "관리자 권한으로 설치"). 스위치가 없으면 설치 프로그램이 이미 있는 설치의 범위를 쓴다(0.1.3 업데이터의 `/S`).
- 2026-11-07 Part A의 설치 프로그램(`tools/installer/chromite.nsi`, 그 작업 트리에서 읽음)과 그 로컬 빌드
  `Chromite-0.1.1-x64-setup.exe`(서명 없음)로 위 조건을 확인했다: `ProductName` = `Chromite`, `ProductVersion` = `0.1.1`,
  HKCU `...\Uninstall\Chromite`의 `InstallLocation`·`UninstallString`, `RequestExecutionLevel user`, 설치 폴더 `%LOCALAPPDATA%\Programs\Chromite`.
  서명이 없어서 이 파일은 "서명" 단계에서 통과하지 않는다(의도한 동작).
- macOS zip: 맨 위가 묶음 하나(`ditto -c -k --keepParent`, `__MACOSX/`가 생기는 `--sequesterRsrc`를 쓰지 않음).
  0.1.3까지의 업데이터는 실행 중인 앱과 같은 이름만 받으므로 0.1.4의 zip은 옛 이름 `ChromiteSwing.app`이다. 0.1.4부터의 업데이터는 `Chromite.app`도 받는다
  ([distribution.md](distribution.md) "macOS 앱 이름", 0.1.5부터 zip은 `Chromite.app`).
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
- **되돌리기는 업데이트 진행 창의 시작 실패에만 있다**(2026-11-08): 받아 둔 이전 버전 설치 파일(`previous/`)을 같은 방법으로 설치한다.
  `data_generation`을 보는 일반 되돌리기 화면은 아직 없다.
- **진행 창은 앱의 사본이 보인다**(2026-11-08, Turbrix #747 A안): 설치 프로그램은 설치 폴더의 Java 런타임이 풀려야 진행하므로 앱 자신은 설치하는 동안 창을
  보일 수 없다. 작은 Win32 창이나 설치 프로그램의 진행 쪽은 앱 테마·문구를 쓸 수 없어서, 같은 Swing 코드를 같은 런타임의 사본으로 띄운다.
  사본은 런타임 전체와 jar 다섯이다(진입점이 쓰는 클래스만 고르는 대신 번들 jar를 통째로 복사: 고를 목록을 유지하지 않아도 되고 복사는 1–2초다).
- **도우미 결과 파일(`apply-result.json`)은 남겼다**: 그냥 닫을 때와 macOS는 여전히 셸·PowerShell 도우미가 설치하고, 셸 스크립트가 JSON 기록을 고치지 않게
  자기 결과 파일을 쓴다. 사본의 결과만 기록(`installCode`)에 들어간다. 0.1.3의 도우미가 남긴 결과도 같은 읽기로 첫 시작을 안다.
- **사본의 창이 1분씩 두 번 새 버전을 기다린 뒤 시작 실패를 보인다**: Turbrix는 조용히 끝나지만, 항목 #11의 "시작 실패 → 시작하지 못했습니다 + 로그·닫기(+ 되돌리기)"를 따랐다.

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

### 업데이트 진행 창 (2026-11-08)

빌드한 Swing 제품(시험 모드, 로컬 사이트 `127.0.0.1`, 데이터·작업 공간·업데이트 폴더는 `D:\Tools\_scratch\update-window`)을 우리가 띄운 프로세스에 붙인
시험 에이전트(attach API, Swing 객체를 EDT에서 직접 조작, OS 입력 없음)로 확인했다. 그림은 창 자체를 150%로 그린 것이다(`D:\Tools\_scratch\update-window\shots\`).
dry run이라 설치 프로그램은 실행하지 않았다. 사이트의 새 버전은 SEIZIA가 서명한 Turbrix 0.3.0 설치 파일(`chromite.update.test.product=Turbrix`)이다.

| 확인 | 결과 |
|---|---|
| 받는 중 제목 표시줄 단추 | "새 버전 0.3.0", 툴팁 "새 버전을 받고 있습니다". 누르면 창: 비율·"14% (17.7 MB / 126.4 MB)"·걸린 시간, [닫기]에 포커스 (`01-downloading`) |
| 받기가 끝나면 | 열린 창이 "새 버전을 받았습니다", 받기 0:26, 126.4 MB, [지금 다시 시작]이 Enter 단추, [업데이트 내용] (`02-ready`) |
| [지금 다시 시작](옛 앱) | 주 창이 닫힌 뒤 "업데이트 파일 확인"이 현재 단계(`04`), 1–2초 뒤 확인 0:01·"설치"가 현재 단계(`05`). 그동안 사본을 만들고 띄움 |
| 사본 | 같은 자리(1060, 505)에 같은 창, 옛 앱이 끝나기를 기다림(기다리는 동안 `07-copy-installing`), 기록에 `helperShownAt`·`installStartAt`·`installEndAt`·`installCode` |
| 설치 실패(시험 코드 2) | "새 버전을 설치하지 못했습니다", "…지금 쓰는 버전은 0.1.3입니다.…"(설치 폴더의 `bundles.info`가 0.1.3), 단계 "설치" 실패, [다시 시도]가 Enter 단추(`06`). [다시 시도]는 다시 설치(기록에 두 번째 실행). 시작 버전이 설치 폴더와 다르면 가운데 문장이 빠짐 |
| 설치 실패의 [닫기]·Esc | 기록을 지우고 사본이 끝남 |
| 설치 실패 뒤 옛 버전의 다음 시작 | 기록과 사본 폴더를 지우고 "Update 0.3.0 was not installed: code 2"를 기록. 사본이 이미 보였으므로 알림 없음 |
| 성공(옛 앱 역할은 시험 스크립트가 기록을 써서 대신함: dry run은 버전을 바꾸지 않으므로 기록의 새 버전을 개발 빌드의 0.1.3으로) | 사본이 `-nosplash`로 띄운 새 프로세스가 0.25초에 뜨고, 첫 시작 창이 같은 자리에 "Chromite를 시작하고 있습니다"(받기 0:37·확인 0:05·설치 0:06, `08`), 사본은 `takenAt`을 보고 끝남. 주 창이 뜨면 "업데이트를 마쳤습니다"(Chromite 시작 0:02·창 열기 0:00, 창이 주 창 위, `09`), 1.2초 뒤 창 없음. 기록·사본 폴더 지워짐 |
| 새 버전의 시작 실패(작업 공간이 다른 인스턴스에 잠김) | 오류 대화상자 대신 창이 "Chromite를 시작하지 못했습니다" + 잠금 문구 + "자세한 내용은 로그 폴더에 있습니다.", [이전 버전으로 되돌리기]·[로그 폴더 열기]·[닫기](`10`) |
| [이전 버전으로 되돌리기] | 창이 "0.1.3 → 0.1.2 설치"로 바뀌고(`11`) 새 사본을 만들어 넘김. 사본이 이전 설치 파일의 SHA-256을 확인하고(dry run) 런처를 다시 띄움(`12`, 기록 `up for 0.1.2`) |
| 사본에서 새 버전이 오지 않음(런처 없음, 다크·영어) | 1분 뒤 한 번 더 띄우기, 다시 1분 뒤 "Chromite could not start"(`13`, `14`). 영어 단추 셋이 한 줄에 들어가지 않아 두 줄로 놓임 |
| 0.1.3 도우미가 적용한 업데이트(기록 없음, `apply-result.json` 코드 0, `previous/0.1.2/`) | 시작 화면 대신 창이 "0.1.2 → 0.1.3", 단계 1–3 완료(시간 없음), "Chromite 시작"부터(`15`), 완료 뒤 닫힘(`16`). 결과 파일은 업데이트 확인이 지움 |

- 사본의 크기: 위 "사본의 크기". 사본은 `jre\bin\javaw.exe`(설치 폴더 밖)로 돌고, 설치 폴더의 파일을 열지 않는다.
- 시험 중 고친 것: (1) 창의 폭 상수 이름이 Swing 부모 클래스의 `WIDTH`(=1)에 가려져 창 폭이 1px이 됨 → 이름 바꿈. (2) `JTextArea`의 낱말 줄바꿈이 한국어 낱말을
  음절 사이에서 끊음 → 띄어쓰기에서만 줄을 나눔. (3) 시험 에이전트가 사본의 EDT를 먼저 띄우면 그 스레드에 문맥 클래스 로더가 없어 FlatLaf UI 클래스를
  찾지 못함(모든 구성 요소가 UI 없이 1×1) → 사본이 EDT의 문맥 클래스 로더를 정함(접근성 브리지 같은 다른 도구도 같은 일을 할 수 있음). (4) 영어 시작 실패의 단추가 폭을 넘음 → 줄을 나눔.
- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` `BUILD SUCCESS`, `java tools/jre/BundleJre.java windows-x64`,
  `python tools/rename/gate.py` `ALL GATES PASSED`. 회귀 테스트는 요청되지 않아 작성·실행하지 않았다.

### 설치 범위와 macOS 이름 (2026-11-09, `chromite/install-update-2`)

빌드 `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` `BUILD SUCCESS`(결과물 `macosx/cocoa/aarch64/Chromite.app`, `Info.plist` 확인 통과).
모델 함수는 OSGi 없이 같은 jar로 시험했다(작업 폴더 `D:\Tools\_scratch\chromite-install-update-2\harness`, 저장소에 넣지 않음). 회귀 테스트는 요청되지 않아 작성·실행하지 않았다.

| 확인 | 결과 |
|---|---|
| `MacUpdateVerifier.bundleInside` | 새 이름 zip: 실행 중 `ChromiteSwing.app`·`Chromite.app`·이름을 바꾼 앱 모두 `Chromite.app`. 옛 이름 zip: `ChromiteSwing.app` 앱만 받음(`Chromite.app` 앱은 null). 다른 앱·묶음 둘·`..`·빈 목록은 null |
| `UpdateApplier.macTarget` | `ChromiteSwing.app` + 새 `Chromite.app` → 같은 폴더 `Chromite.app`. 같은 이름·새 앱 없음 → 지금 자리. `Chromite.app`이 이미 있으면 지금 자리 |
| macOS 도우미 스크립트(Git Bash의 sh, 가짜 묶음, `open`은 기록으로 바꿈) | 이름 바꿈: `apps/Chromite.app`=새 것, `before-0.1.5/ChromiteSwing.app`=옛 것, 결과 코드 0, 새 경로를 염. 새 이름이 이미 있음: 그 복사본 그대로, `ChromiteSwing.app` 자리에 새 것. 같은 이름, 다시 시작만도 지금처럼 |
| `UpdatePlace.installScope` | 시험 설치(HKCU) 폴더 false, HKLM 항목의 폴더(7-Zip) true, 등록되지 않은 폴더 null |
| `RUN_INSTALLER`/`ELEVATED_RUN`(Windows PowerShell 5.1, UAC 없이: 동사를 끄거나 예외를 흉내) | 설치 프로그램 종료 코드 3·0이 그대로 나옴. `Win32Exception(1223)`(PowerShell이 감싼 것과 직접 던진 것 모두) → -5. 없는 파일(오류 2) → -1 |
| Windows 도우미(PowerShell, 가짜 설치 파일) | 사용자별: `running …\fake-setup.exe /CurrentUser /S`, 종료 코드가 결과 파일에. 모든 사용자 dry run: `test mode: not run: … /AllUsers /S (elevated)` |
| 앱의 사본(`UpdateWindowApp`, 기록 `allUsers=true`·dry run·코드 -5) | 기록 `test mode: not run: …\fake-setup.exe /AllUsers /S (elevated) (code -5)`, 창이 "새 버전을 설치하지 못했습니다" + 관리자 확인 거부 문구, 단계 "설치" 실패, [다시 시도]가 Enter 단추(한국어 라이트 `01-allusers-uac-refused-ko.png`, 영어 다크 `02-…-en-dark.png`, 150%로 그린 창, `D:\Tools\_scratch\chromite-install-update-2\shots\`). 사용자별 코드 2는 지금 문구 그대로(`03`), 명령은 `/CurrentUser /S` |

## 확인하지 못한 것

- 모든 사용자 설치의 실제 승격 업데이트(UAC 창, [예]·[아니요], 시간 초과, 창이 앞에 뜨는지): 이 PC에서 UAC를 띄우지 않았다(브리프). 승격 경로는 시험 모드의 명령 기록과
  PowerShell 스크립트의 분기(흉내 낸 1223)로만 확인했다. 숨긴 PowerShell이 요청한 UAC 창이 바로 앞에 뜨는지, 작업 표시줄에서 깜박이는지도 확인하지 못했다.
- macOS 이름 바꿈의 실제 한 바퀴(0.1.4 `ChromiteSwing.app` → 0.1.5 `Chromite.app`), Dock·Spotlight·로그인 항목의 실제 모습: Mac이 없고 서명된 두 릴리스가 필요하다.
- 실제 서명된 Chromite 릴리스(첫 `v*` 태그) 사이의 업데이트: 0.1.0 설치본 → 0.1.1 설치 파일 받기 → 확인 → `setup.exe /S` → 다시 시작.
  Chromite 설치 프로그램(Part A)이 아직 없어서 이 PC에서는 SEIZIA가 서명한 Turbrix 설치 파일로 확인 단계까지만, 설치는 dry run으로 했다.
- macOS 전체(확인 도구 호출, 바꿔 넣기, `open`으로 다시 시작, Gatekeeper "앱 관리" 보호). Windows에서는 순수 함수(이름 검사,
  `codesign`·`spctl` 출력 해석, 게시자 비교)와 도우미 스크립트의 옮기기 논리(POSIX sh)만 시험했다.
  - 2026-11-08 후속 26 점검(`chromite/followups-4`, 워크플로를 실행하지 않고 파일로 확인): `macos-smoke.yml`은 릴리스 없이도
    `source: build`(그 ref의 서명 없는 빌드, ad hoc 서명)로 설치·시작·Keychain·앱 메뉴·끝내기(Apple 이벤트, Cmd+Q)·열린 트랜잭션 질문을 본다.
    그러나 업데이터는 어느 단계도 건드리지 않는다. 릴리스 경로는 서명·공증된 zip과 새 버전이 실린 목록이 있어야 하고(`release.yml`이 만든 초안 릴리스),
    시험 모드(`chromite.update.test.origin`, ad hoc 앱에서 켜짐)는 받기·확인까지만 하고 앱을 바꿔 넣지 않으므로(위 "시험 모드") 다시 시작과 zip 내용을 볼 수 없다.
  - 릴리스 없이 더할 수 있는 것(제안, 이번에 하지 않음): smoke 스크립트에 하위 명령을 하나 더해, 러너에서 풀어 둔 앱을 `ditto -c -k --keepParent`로 zip 하고
    (`MacUpdateVerifier`의 이름 검사와 같은 `zipinfo -1`), 그 사본을 "새 앱"으로 `UpdateApplier.MAC_HELPER_SCRIPT`를 가짜 pid로 실행해 옮겨 두기·바꿔 넣기·`open` 다시 시작·결과 파일을
    실제 macOS에서 보는 것. 이 브랜치는 푸시하지 않고 워크플로를 실행하지 않는 작업이라(브리프) 만들어도 돌려 볼 수 없어 넣지 않았다.
  - 그래도 남는 것: 공증된 zip의 `codesign`·`spctl` 확인, Gatekeeper "앱 관리"가 다른 앱의 바꿔 넣기를 막는지, 실제 이전 버전 → 새 버전 업데이트. 이것들은 서명된 두 릴리스가 있어야 한다.
- 실제 NSIS 설치 프로그램을 도우미가 `/S`로 실행하는 것(시험 모드는 실행하지 않음, 가짜 설치 파일로만 도우미를 확인). 설치 프로그램이 이 PC의
  사용자별 설치 폴더(C:)를 바꾸므로 이 작업에서는 하지 않았다.
- 그냥 닫은 직후 Windows를 끄면 조용한 설치가 중간에 끊길 수 있다(Turbrix와 같은 위험). 시험하지 않았다.
- 회사 프록시·TLS 검사 장비 뒤에서의 동작.
- 업데이트 진행 창(2026-11-08): 사본이 실제 NSIS 설치 프로그램을 `/S`로 실행하는 것(설치 프로그램이 사본을 끝내지 않고 사본이 설치를 막지 않는 근거는
  `chromite.nsi`를 읽은 것), 서명된 두 Chromite 버전 사이의 실제 한 바퀴(0.1.4 → 0.1.5가 처음), 0.1.3 → 0.1.4의 실제 첫 시작 창(시험은 결과 파일을 흉내 냄),
  macOS 전체(옛 앱의 확인 창과 첫 시작 창), 화면 읽기 프로그램, 200% 글자 배율. 사본 만들기 시간은 같은 디스크·캐시에서만 쟀다(C:·백신 검사 몫은 재지 않음).

## 변경 이력

- 2026-11-07 최초 작성. 앱 안의 업데이터(모델 번들 `com.seizia.chromite.model.update`, Swing 화면), 끝낼 때 트랜잭션 질문.
- 2026-11-07 끝낼 때 트랜잭션 질문이 AI 에이전트 격리 문맥의 열린 트랜잭션도 찾음(후속 과제 #78). 확인: PostgreSQL 수동 커밋 연결에서
  채팅이 INSERT를 열어 둔 채 File > 끝내기 → 질문에 "FB PG Manual (AI 도우미·MCP 도구)", [취소]는 앱과 트랜잭션을 그대로 두고
  (`pg_stat_activity` "idle in transaction"), [커밋]은 행을 커밋하고 닫음(psql로 행 확인). 영어 화면에서 [Roll Back]은 행 없음.
- 2026-11-07 에이전트 기능의 공식 이름을 Chromite Agent로 정함([ai-agent.md](ai-agent.md) "명칭"): 끝낼 때 질문의 목록 줄이
  "연결 이름 (Chromite Agent·MCP 도구)"(영어 "(Chromite Agent and MCP tools)"). 위 확인 기록의 옛 문구는 그때의 화면이라 그대로 둠.
- 2026-11-08 업데이트 진행 창(앱 항목 #11, Turbrix #747과 같게): 받는 중 제목 표시줄 단추와 창, [지금 다시 시작]의 창, Windows 앱의 사본이 설치 프로그램을
  자식으로 실행, 새 버전의 첫 시작 창(시작 화면 대신), 설치 실패·시작 실패와 되돌리기, 업데이트 기록 `update-run.json`, 시험 모드 `chromite.update.test.installer.code`.
- 2026-11-08 상태 표시줄 진행 글자가 연결 색으로 칠한 상태 표시줄 위의 대비 색을 따름(앱 변경 #2, `chromite/result-tabs`).
- 2026-11-08 후속 26 점검(`chromite/followups-4`): `macos-smoke.yml`이 릴리스 없이 볼 수 있는 것과 없는 것, 더할 수 있는 smoke 단계를 "확인하지 못한 것"에 적음(코드 변경 없음).
- 2026-11-09 macOS 화면 메뉴 막대에는 메뉴만 보이므로 업데이트 단추를 상태 표시줄 오른쪽 끝, 진행 옆에 둠. Windows는 그대로(앱 변경 #27, `chromite/mac-ui`, [mac-ui.md](mac-ui.md)).
- 2026-11-09 설치 범위(앱 변경 #23, `chromite/install-update-2`): 모든 사용자 설치(HKLM)는 "알리기만"이 아니라 받고 확인하고 [지금 다시 시작]의 진행 창에서
  관리자 권한으로 설치("관리자 권한으로 설치"), UAC 거부 문구와 [다시 시도], 그냥 닫을 때는 적용하지 않음, 사용자별 설치는 `/CurrentUser /S`, 시험 모드 `chromite.update.test.allusers`.
  macOS 앱 이름(앱 변경 #22): `Chromite.app` zip을 옛 이름 앱도 받고 적용 때 `Chromite.app`으로 놓음("macOS 확인", "적용").
