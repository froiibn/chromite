# 실행 파일 이름 `Chromite` (앱 변경 #65)

## 목적

사용자(2026-11-12): "윈도우에서 실행파일이 chromite-swing.exe 로 나타나는 문제도 고쳐라".

제품의 런처 이름이 `chromite-swing`(SWT 제품의 `chromite`와 나누려고 붙인 이름, [swt-removal.md](swt-removal.md))이라 Windows의 작업 관리자·설치 폴더·바로 가기 대상에
`chromite-swing.exe`·`chromite-swingc.exe`·`chromite-swing.ini`가, macOS 앱 묶음 안에 `Contents/MacOS/chromite-swing`이 보였다.
Eclipse 런처의 시작 화면 창 제목도 런처 이름을 따라 "Chromite-swing"이었다(0.1.4 설치본을 띄워 창 제목으로 확인).

이제 런처 이름은 `Chromite`다: Windows `Chromite.exe`, 콘솔용 `Chromitec.exe`, `Chromite.ini`, macOS `Chromite.app/Contents/MacOS/Chromite`(`Info.plist`의 `CFBundleExecutable`),
`Chromite.app/Contents/Eclipse/Chromite.ini`.

## 구조

| 곳 | 바꾼 것 |
|---|---|
| `product/swing/chromite-swing.product` | `<launcher name="Chromite">`. 제품 파일 이름, 제품 ID, `CFBundleIdentifier`(`com.seizia.chromite.swing.product`)는 그대로 |
| `tools/installer/chromite.nsi` | `EXE` = `Chromite.exe`(설치 폴더 확인, 시작 메뉴·바탕 화면 바로 가기, 앱 목록 아이콘, 마침 화면의 시작). 옛 이름 시작 파일 `LEGACY_EXE`(아래), 옛 런처 파일 지우기 |
| `tools/installer/legacy-launcher.nsi` | 새 파일: 옛 이름 시작 파일 `chromite-swing.exe`(한 버전만) |
| `tools/installer/build-installer.ps1` | 필요한 파일 `Chromite.exe`. 1단계가 시작 파일도 만들고(서명 대상), 2단계가 `-LegacyLauncher`로 넣음 |
| `.github/workflows/release.yml` | `Chromite.exe` 서명, 시작 파일 서명 단계, 서명 확인 목록, 설치·제거 확인, zip 안의 런처 확인, macOS 실행 파일 `Chromite` |
| `.github/workflows/build.yml`, `macos-smoke.yml` | macOS 실행 파일 경로 `Contents/MacOS/Chromite` |
| `tools/release/macos-smoke.sh` | 통합 로그의 프로세스 이름을 `CFBundleExecutable`에서 읽음, `launch`가 프로세스가 `<앱>/Contents/MacOS/Chromite`인지 확인 |
| `tools/release/deploy-chromite-site.ps1` | macOS zip 안에 `Contents/MacOS/Chromite`가 있는지 확인 |
| `tools/release/entitlements.mac.plist`, `product/swing/pom.xml`, `README.md` | 주석·안내 |

이름을 읽기만 하고 고칠 필요가 없던 곳(검색으로 확인):

- 업데이터: 다시 띄울 런처를 이름으로 정하지 않고 실행 중인 프로세스의 `eclipse.launcher`로 안다(`UpdatePlace.detect`, `UpdateService.handOver`의 `r.launcher`,
  `UpdateApplier.startWindowsHelper`의 `$launcher`). 설치본 판정은 앱 목록 항목의 폴더로 한다(`UpdatePlace.installScope`). "실행 중" 판단은 설치 프로그램이
  `jre\bin\server\jvm.dll`로 한다. 그래서 Java 코드는 바꾸지 않았다.
- `BasePlatformImpl.getLauncherConfiguration`: 런처 이름으로 `<이름>.ini`를 찾는다(부르는 곳 없음). `release-macos.sh`, `macos_natives.py`: `CFBundleExecutable`을 읽는다.
- `tools/jre/BundleJre.java`, `build-installer.ps1`의 `ProductDir`, `release.yml`·`build.yml`의 경로: Windows 결과물 폴더 이름 `chromite-swing/`(`product/swing/pom.xml`의 `rootFolders`)이고
  런처 이름이 아니다. 아래 "패키지 이름"에서 그대로 둔 이유.

## 업데이트 호환 (한 버전)

### 0.1.4 업데이터가 하는 일 (태그 `v0.1.4`의 코드, 지금과 같음)

`git diff v0.1.4 HEAD -- plugins/com.seizia.chromite.model.update tools/installer`가 비어 있다(이 작업 전). 0.1.4는:

1. [지금 다시 시작]: 실행 중인 런처 경로(`eclipse.launcher` = `<설치 폴더>\chromite-swing.exe`)를 기록(`update-run.json`의 `launcher`)에 적고 앱의 사본을 띄운다.
   사본은 옛 앱이 끝나기를 기다린 뒤 `setup.exe /CurrentUser /S`(또는 승격 `/AllUsers /S`)를 실행하고, 종료 코드 0이면 기록의 런처를 `-nosplash`로 띄운다
   (`UpdateHandOver.startApp`: 파일이 없으면 "not started again: no launcher"로 끝나 앱이 다시 뜨지 않는다).
2. 그냥 닫을 때·사본을 못 만들 때: PowerShell 도우미가 같은 설치를 하고, 다시 시작이면 `Start-Process <같은 런처 경로>`(`UpdateApplier.WINDOWS_HELPER`).
3. 설치 프로그램은 설치된 `uninstall.exe /S /UPGRADE`를 먼저 돌린다. 0.1.4의 제거 프로그램은 자기 파일 목록(`chromite-swing.exe`, `chromite-swing.ini`, `chromite-swingc.exe` 포함)을 지운다.

그래서 새 버전이 옛 이름을 전혀 두지 않으면 0.1.4에서 자동 업데이트는 설치까지 되고 앱이 다시 뜨지 않는다(Mac 묶음 이름 바꿈 때와 같은 종류의 문제).

### 고른 방법: 옛 이름 시작 파일 (설치 프로그램만, 한 버전)

설치 프로그램이 `Chromite.exe` 옆에 `chromite-swing.exe`라는 작은 시작 파일을 둔다(`legacy-launcher.nsi`, NSIS로 만든 창 없는 exe). 시작 파일은 같은 폴더의
`Chromite.exe`를 받은 인자 그대로, 작업 폴더를 그 폴더로 하여 띄우고 바로 끝난다(띄우지 못하면 종료 코드 2). 버전 정보: ProductName·FileDescription `Chromite`, 설치 프로그램 버전.

- 0.1.4 업데이터의 다시 시작(`chromite-swing.exe -nosplash`)이 시작 파일을 거쳐 `Chromite.exe -nosplash`가 된다. 다시 뜬 앱은 `Chromite.exe`로 돌고, 그 `eclipse.launcher`도
  `Chromite.exe`라 다음 업데이트부터는 새 이름만 쓴다.
- 작업 표시줄에 고정한 옛 `chromite-swing.exe`도 이 버전 동안은 Chromite를 띄운다.

검토한 다른 방법:

| 방법 | 버리거나 미룬 이유 |
|---|---|
| 옛 이름의 런처 사본 + `chromite-swing.ini` | 업데이트 직후의 앱이 `chromite-swing.exe`로 돌아 작업 관리자에 옛 이름이 다시 보인다(요청한 문제 그대로). 그 프로세스의 `eclipse.launcher`가 옛 이름이라 다음 버전에서 사본을 지우면 그 세션의 업데이트가 또 다시 뜨지 않으므로 업데이터 코드에 이름 바꾸기 처리가 더 필요하다 |
| 아무것도 두지 않음 | 0.1.4의 업데이트가 앱을 다시 띄우지 않는다 |
| 업데이터(Java)에서 처리 | 0.1.4에 이미 설치된 업데이터는 바꿀 수 없다 |

시작 파일의 위험과 대응: 새 실행 파일이라 서명이 필요하다. `release.yml`이 1단계 뒤 `uninstall.exe`와 같은 방법으로 서명하고(`Sign chromite-swing.exe (ours, the old-name starter)`),
서명 확인 목록과 설치·제거 확인에 넣었다. 휴대용 zip에는 넣지 않는다(설치하지 않은 복사본은 업데이트를 적용하지 않고 알리기만 한다, [auto-update.md](auto-update.md)).

### 설치 프로그램이 하는 일

- 업그레이드(0.1.4 → 이 버전): 0.1.4의 제거 프로그램이 옛 런처 세 파일을 지우고, 새 설치 프로그램이 `RemoveProgramFiles`에서 한 번 더 지운다(옛 제거 프로그램이 없거나 실패한 경우).
  새 파일과 시작 파일을 넣는다.
- 시작 메뉴 바로 가기(`<이름>.lnk`)와 바탕 화면 바로 가기(이전에 있었으면 다시 만듦)는 같은 파일 이름으로 다시 쓰므로 대상이 `Chromite.exe`가 되고 두 개가 되지 않는다.
  앱 목록 항목의 아이콘(`DisplayIcon`)도 `Chromite.exe`다.
- 제거: 시작 파일도 지운다(이 버전의 `RemoveProgramFiles`).
- 설치 폴더 확인(`CheckInstDir`): `Chromite.exe`, `chromite-swing.exe`, `uninstall.exe` 중 하나가 있으면 Chromite 폴더로 본다(0.1.4의 휴대용 폴더를 골랐을 때 예전처럼 받음).

### 작업 표시줄 고정 (사용자가 할 일)

Windows의 작업 표시줄 고정은 실행 파일 경로를 가리키고 설치 프로그램이 바꾸지 않는다.

- 이 버전: 옛 고정(`chromite-swing.exe`)을 누르면 시작 파일이 `Chromite.exe`를 띄운다. 실행 중인 창은 `Chromite.exe` 단추로 고정과 따로 보일 수 있다.
- 다음 버전: 시작 파일이 없어지므로 옛 고정은 "항목을 찾을 수 없음"이 된다. 옛 고정을 풀고, Chromite를 띄운 뒤 작업 표시줄 단추에서 "작업 표시줄에 고정"을 다시 한다.
  릴리스 노트에 적는다.

### macOS

macOS의 업데이트는 실행 파일 이름을 쓰지 않는다: 확인(`MacUpdateVerifier`)은 zip 안 묶음 이름, 번들 ID, Team ID, 서명, 버전을 보고, 도우미는 묶음 폴더를 통째로 바꾼 뒤
`open <묶음>`으로 띄운다. LaunchServices는 새 `Info.plist`의 `CFBundleExecutable`(`Chromite`)을 실행한다. 그래서 macOS에는 옛 이름이 필요 없다.
키체인 접근은 서명 신원(번들 ID·Team ID)에 묶여 실행 파일 이름과 상관없다. Dock 고정은 묶음(`Chromite.app`)을 가리키므로 그대로다.

## 패키지 이름 (그대로 둠)

포터블 zip `chromite-swing-<버전>-windows-x64.zip`, 그 안의 폴더 `chromite-swing/`, Tycho 결과물 폴더(`win32/win32/x86_64/chromite-swing/`), CI 결과물 이름
(`chromite-swing-windows-x64`, `chromite-swing-macos-aarch64`)은 바꾸지 않았다.

- 읽는 곳: `release.yml`(zip 만들기·확인), `build-installer.ps1`·`BundleJre.java`의 결과물 경로, `build.yml`, 문서. 업데이트 피드(`releases.sh`가 `Chromite-*`의 세 이름만 넣음),
  사이트(`site.js`의 이름 규칙), 업데이터(`UpdateFeed`: setup과 macOS zip 이름만)는 이 이름을 읽지 않는다.
- 그대로 둔 이유: zip 이름을 `Chromite-<버전>-windows-x64.zip`으로 바꾸면 배포 스크립트의 `gh release download --pattern 'Chromite-*'`에 걸려 배포 때마다 쓰지 않는 zip을 더 받는다
  (배포는 세 이름만 올리므로 사이트는 그대로지만 배포 경로가 바뀐다). 결과물 폴더 이름은 여러 스크립트와 다른 작업의 시험 절차가 쓰는 경로다. 실행 파일 이름 요청과 따로
  정할 일이라 이번에는 두었다.

## 다음 버전에서 지울 것 (릴리스 체크리스트)

이 변경이 처음 들어간 버전의 **다음 버전**에서:

- [ ] `tools/installer/legacy-launcher.nsi` 삭제.
- [ ] `tools/installer/chromite.nsi`: `LEGACY_LAUNCHER` 요구와 정의 설명, `LEGACY_EXE`와 그 사용(`RemoveProgramFiles`의 세 `Delete`, `CheckInstDir`, `File "/oname=..."`), 변경 이력 줄은 남김.
- [ ] `tools/installer/build-installer.ps1`: 시작 파일 만들기, `-LegacyLauncher`.
- [ ] `.github/workflows/release.yml`: "Sign chromite-swing.exe (ours, the old-name starter)", 서명 확인의 시작 파일, 설치 확인의 시작 파일 두 줄, 2단계의 `-LegacyLauncher`.
- [ ] `tools/release/deploy-chromite-site.ps1`: macOS zip 확인에서 `MacOS/chromite-swing` 허용을 지움(0.1.4 릴리스를 사이트만 다시 배포할 수 있게 남겨 둔 것, 새 실행 파일 이름의 릴리스가 배포된 뒤).
- [ ] 이 문서와 [distribution.md](distribution.md)의 설명.

그 뒤 0.1.4에서 바로 그 버전으로 올리는 Windows 설치본은 업데이트 뒤 앱이 다시 뜨지 않는다(설치는 됨, 시작 메뉴로 열면 된다). 이 버전의 제거 프로그램이 업그레이드 때
시작 파일을 지우므로 다음 버전 설치본에는 남지 않는다.

## 확인한 것

2026-11-12, Windows 11, `chromite/launcher-rename`(`origin/devel` 59571d2a36에서). 회귀 테스트는 요청되지 않아 작성·실행하지 않았다.
기록은 `D:\Tools\_scratch\launcher-rename\evidence\`(아래 파일 이름).

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `java tools/jre/BundleJre.java windows-x64`.
  결과물: Windows `Chromite.exe`, `Chromitec.exe`, `Chromite.ini`(옛 이름 없음). macOS `Contents/MacOS/Chromite`, `Contents/Eclipse/Chromite.ini`, `Info.plist`
  `CFBundleExecutable` = `Chromite`, `CFBundleName` = `Chromite`, `CFBundleIdentifier` = `com.seizia.chromite.swing.product`(그대로). `Chromite.exe`의 버전 리소스는 전처럼 비어 있다
  ([versioning.md](versioning.md) "Windows 런처 버전 리소스").
- 시험 설치 프로그램: 저장소의 `build-installer.ps1`·`chromite.nsi`를 작업 폴더에 복사해 `INSTANCE` 줄만 `ChromiteTestLR`로 바꿔 만들었다(다른 줄이 같음을 비교해 기록).
  새 빌드 `0.1.5`·`0.1.6`(버전 리소스만 다른 같은 제품). 0.1.4는 GitHub 릴리스 `v0.1.4`의 포터블 zip(SHA256SUMS 일치, 런처 서명 `Valid` Seizia)을 `v0.1.4`의 `chromite.nsi`로 묶었다.
  모두 `/CurrentUser /S /D=D:\Tools\_scratch\launcher-rename\inst\Chromite`, UAC 없음. 앱은 `CHROMITE_DATA`·`LOCALAPPDATA`·`APPDATA`를 작업 폴더로, 업데이트 확인은
  시험 업데이트 폴더의 `update.json`(`enabled: false`)으로 꺼서 어떤 피드에도 연결하지 않았다.

| 확인 | 결과 | 기록 |
|---|---|---|
| 새로 설치 | 설치 폴더 `Chromite.exe`, `Chromitec.exe`, `Chromite.ini`, `chromite-swing.exe`(시작 파일, ProductName `Chromite`), 시작 메뉴 대상 `Chromite.exe`, 앱 목록 아이콘 `Chromite.exe,0`. 실행하면 프로세스 `Chromite.exe`, `eclipse.launcher` = `...\Chromite.exe`, `Updates: APPLY (installed)` | `01-fresh-install.log` |
| 시작 파일로 시작(작업 표시줄 고정과 같음) | 시작 파일 종료 코드 0, `Chromite.exe`가 그 자식으로 같은 인자(`-data`·`-configuration`)를 받아 뜸 | `02-starter.log` |
| 제거 | 설치 폴더, HKCU 항목, 바로 가기 모두 없음 | `03-uninstall.log` |
| 0.1.4 → 새 빌드, [지금 다시 시작] 경로 | 0.1.4 앱의 `eclipse.launcher` = `...\chromite-swing.exe`. 0.1.4 설치본의 jar로 만든 앱의 사본(0.1.4 코드)이 옛 앱을 기다려 `setup /CurrentUser /S` 종료 코드 0, "started again". 다시 뜬 프로세스 `Chromite.exe -nosplash`(부모는 끝난 시작 파일), `eclipse.launcher` = `...\Chromite.exe`, 새 버전이 창을 이어받아 기록이 지워짐. 옛 `chromite-swing.ini`·`chromite-swingc.exe` 없음, 바로 가기 대상 `Chromite.exe` | `04-update-014-to-new-window-copy.log` |
| 그다음 업데이트(새 빌드 → 0.1.6, 새 코드의 사본) | 기록된 런처 `...\Chromite.exe`, 설치 종료 코드 0, `Chromite.exe -nosplash`로 다시 뜸, 앱 목록 0.1.6 | `05-update-new-to-newer.log` |
| 0.1.4 → 새 빌드, PowerShell 도우미 경로 | 0.1.4의 `UpdateApplier.startWindowsHelper`(재시작 = `chromite-swing.exe`): "the app ended", 종료 코드 0, "started again", 프로세스 `Chromite.exe` | `06-update-014-to-new-powershell-helper.log` |
| 설치 프로그램으로 제자리 업그레이드(0.1.4 위에 새 setup `/S`) | 종료 코드 0, 같은 폴더, 옛 런처 파일 없음, 바로 가기 대상 `Chromite.exe`, 기본 폴더에 두 번째 설치 없음, 바로 가기 대상으로 시작됨, 제거 뒤 아무것도 남지 않음 | `07-inplace-upgrade-by-installer.log` |
| 사용자의 실제 설치·데이터 | `C:\Program Files\Chromite` 파일의 시각·크기 그대로, HKCU에 Chromite 항목 없음, `%APPDATA%\ChromiteData`에 시험 중 쓴 파일 0개 | `01-fresh-install.log`, `08-final-state.log` |

업데이트 시험에서 받기와 서명 확인은 하지 않았다: 0.1.4의 설치본은 게시자(Seizia)가 서명한 설치 파일만 받고, 시험 모드는 설치된 복사본에서 꺼지며 설치 파일을 실행하지 않는다
([auto-update.md](auto-update.md) "시험 모드"). 그래서 0.1.4 앱의 [지금 다시 시작]이 받기·확인 뒤에 하는 일(`UpdateService.newRecord`·`handOver`와 같은 기록, `UpdateApplier.startWindowCopy`)을
시험 코드가 0.1.4의 클래스로 대신했고, 그 뒤(사본, 설치, 다시 시작)는 0.1.4의 코드가 그대로 했다. 받기·확인은 실행 파일 이름을 쓰지 않는다.

## 확인하지 못한 것

- 서명된 실제 릴리스 사이의 업데이트(받기·서명 확인 포함): 게시자 서명이 필요하다. 첫 릴리스 뒤 실제 0.1.4 설치본에서 본다.
- 모든 사용자 설치(Program Files, 승격 설치)의 업데이트: UAC를 띄우지 않았다. 다시 시작 경로는 사용자별과 같은 코드다.
- 바탕 화면 바로 가기 다시 만들기: 이 PC의 바탕 화면이 OneDrive 폴더라 시험하지 않았다(코드는 전과 같고 대상만 `EXE`).
- 작업 표시줄 고정의 실제 모습(아이콘, 단추 묶음).
- `release.yml`의 시작 파일 서명 단계(GitHub Actions, Azure): 태그·push가 없어 돌리지 않았다.
- macOS 전부: 아래 smoke 실행이 보여야 할 것.
  - `macos-smoke.yml`(`source: build`)의 "Pack the app"이 `Contents/MacOS/Chromite`를 찾고, `launch first-launch`·`second-launch`·`txn-launch`의 `result.txt`에
    `PASS: <label>: the process is .../Chromite.app/Contents/MacOS/Chromite (CFBundleExecutable Chromite)`가 있고, 나머지 단계가 전처럼 통과한다.
    산출물의 `Chromite.ini`(`collect`가 `Contents/Eclipse/*.ini`를 복사)가 있다.

## upstream 대비 변경점

DBeaver의 런처 이름은 `dbeaver`(DBeaver 제품 파일)이고 이 저장소에 그 제품은 없다. 위 파일은 모두 Chromite의 것이다.

## 변경 이력

- 2026-11-12 최초 작성(앱 변경 #65): 런처 이름 `Chromite`, 0.1.4 업데이터를 위한 옛 이름 시작 파일(한 버전), 실제 설치·업데이트 확인.
