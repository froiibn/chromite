# 배포: 설치 프로그램, 업데이트 피드, 배포 스크립트, 다운로드 사이트

## 목적

Chromite를 https://chromite.seizia.com 에서 내려받고, 앱이 스스로 업데이트하게 한다.
이 문서는 그중 배포 쪽(Part A)을 다룬다. 앱 안의 업데이트 기능(Part B, 같은 계약을 따르는 별도 작업)은 다루지 않는다.

- Windows 설치 프로그램(NSIS `setup.exe`): 사용자가 설치하고, 업데이트 기능이 조용히(`/S`) 실행해 설치된 앱을 바꾼다.
- 업데이트 피드 `releases.json`: 앱과 다운로드 페이지가 읽는 릴리스 목록.
- 배포 스크립트: CI가 만든 서명 릴리스를 이 PC에서 확인하고 서버에 올린다(CI는 서버에 접근하지 않는다).
- 다운로드 사이트: 최신 Windows 설치 파일과 macOS 디스크 이미지, 변경 내역, 개인정보 안내.

## 결정 사항 (사용자, 2026-11-07)

| 항목 | 결정 |
|---|---|
| 주소 | https://chromite.seizia.com (dbeaver.io는 쓰지 않는다) |
| 서버 | Turbrix의 EC2 + Caddy. 정적 파일만. `/srv/chromite/site`(사이트), `/srv/chromite/downloads`(`/downloads/`) |
| 업로드 | CI 업로드 없음. 이 PC에서 확인한 뒤 배포 스크립트로 올린다 |
| Windows 설치 프로그램 | NSIS `setup.exe` (Turbrix와 같음). 설치 범위는 기본 Program Files(모든 사용자) + "나만 사용" 선택(2026-11-08, 아래 "설치 범위") |
| 버전 | `YY.seq.dev`(2026-11-12부터, 0.1.4 다음이 26.0.5), 태그 `v<버전>` ([versioning.md](versioning.md)) |
| 본보기 | Turbrix의 `releases.json`(format 1), `releases.sh`, 로컬 배포 방식 |

## 공유 계약 (Part A·B 공통)

이 절은 앱의 업데이트 기능과 맞춘 계약이다. 바꾸면 양쪽을 함께 바꾼다.

### 피드 `https://chromite.seizia.com/downloads/releases.json`

```json
{"format":1,"generated_at":1793996707,"files":[
{"file":"Chromite-0.1.1-x64-setup.exe","version":"0.1.1","arch":"x64","kind":"setup","platform":"windows","size":155818206,"sha256":"…","data_generation":1,"notes_url":"https://chromite.seizia.com/ko/changelog.html#v0.1.1"},
{"file":"Chromite-0.1.1-arm64.zip","version":"0.1.1","arch":"arm64","kind":"zip","platform":"macos",…},
{"file":"Chromite-0.1.1-arm64.dmg","version":"0.1.1","arch":"arm64","kind":"dmg","platform":"macos",…},
{"file":"Chromite-0.1.0-x64-setup.exe","version":"0.1.0",…}]}
```

- `format`은 1. 첫머리는 정확히 `{"format":1,"generated_at":<unix>,"files":[`(서버 쪽 확인이 이 글자를 본다).
- 항목마다 `file`, `version`, `arch`, `kind`(`setup`|`zip`|`dmg`), `platform`(`windows`|`macos`), `size`, `sha256`(소문자 16진수 64자),
  `data_generation`(1 이상의 정수), `notes_url`(https). 모든 항목에 모든 필드가 있다.
- 새 버전이 먼저 온다(숫자 순서). 바로 이전 버전의 파일도 남는다(되돌리기용, 아래 "보관 규칙").

### 파일 이름

| 파일 | 내용 |
|---|---|
| `Chromite-<v>-x64-setup.exe` | Windows 설치 프로그램(서명). 업데이트와 다운로드 페이지 모두 이 파일 |
| `Chromite-<v>-arm64.zip` | macOS 업데이트 파일: 서명·공증·staple한 `Chromite.app`을 `ditto`로 묶은 zip. **0.1.4만** 같은 묶음을 옛 이름 `ChromiteSwing.app`으로 담는다(아래 "macOS 앱 이름") |
| `Chromite-<v>-arm64.dmg` | macOS 디스크 이미지(서명·공증·staple, 볼륨 이름 "Chromite", `Chromite.app`과 Applications 링크, 창 배치는 아래 "macOS 디스크 이미지 창"). 다운로드 페이지용 |

GitHub Release에는 이 셋과 Windows 포터블 zip(`chromite-swing-<v>-windows-x64.zip`, 이름 그대로), `SHA256SUMS`가 올라간다. 포터블 zip은 서버에 올리지 않는다.

### Windows `setup.exe`

- Azure Artifact Signing 서명. 서명자 주체는 정확히 `CN=Seizia, O=Seizia, L=Cheongju-si, S=North Chungcheong, C=KR`([signing.md](signing.md) "게시자"), 타임스탬프 있음.
- 버전 리소스 `ProductVersion` = `FileVersion` = `<v>`(예: `0.1.1`). 고정 버전 필드(`VIProductVersion`)는 `<v>.0`.
- 조용한 실행 `/S`: 질문 없이 설치된 앱을 같은 폴더에서 바꾼다. 설치 폴더는 레지스트리(`HKCU` 또는 `HKLM`의 `...\Uninstall\Chromite` `InstallLocation`)에서 읽는다.
  설치 범위는 `/AllUsers`(모든 사용자, 관리자 권한 필요) 또는 `/CurrentUser`(나만)로 고른다. 스위치가 없으면 이미 있는 설치의 범위, 설치가 없으면 모든 사용자(아래 "설치 범위").
  새로 설치할 때만 `/D=<폴더>`(맨 끝, 따옴표 없이)로 폴더를 바꿀 수 있다. **조용한 실행은 Chromite를 시작하지 않는다.** 업데이트 기능이 종료 코드 0을 본 뒤 시작한다.
- 종료 코드: `0` 성공, `1` 사용자가 취소(대화형), `2` 실패. 실패에는 "Chromite가 60초 넘게 실행 중", "비어 있지 않은 다른 프로그램 폴더", "관리자 권한 없이 모든 사용자 설치(조용한 실행)",
  "다른 범위에 이미 설치됨"이 있다. 실패하면 아무것도 바꾸지 않는다.
- 낮은 버전의 설치 프로그램도 같은 방식으로 덮어 설치한다(되돌리기).

### 업데이트 데이터 세대 `data_generation`

릴리스가 저장 데이터 형식을 바꿀 때 올리는 정수(1부터). 앱은 세대가 다른 이전 버전으로 되돌리기 전에 경고할 수 있다.
배포 스크립트의 `-DataGeneration`으로 버전마다 한 번 정하고, 서버의 `releases-meta.tsv`에 남는다. 한 번 정한 값은 바꾸지 않는다.

## 구조

| 파일 | 하는 일 |
|---|---|
| `tools/installer/chromite.nsi` | NSIS 설치 프로그램 스크립트(설치·업그레이드·제거) |
| `tools/installer/build-installer.ps1` | 설치 프로그램 빌드(2단계: 제거 프로그램 → 설치 프로그램). CI와 로컬이 같이 쓴다 |
| `.github/workflows/release.yml` | Windows 작업: NSIS 설치(고정), 2단계 빌드와 서명, 확인, 두 설치 범위의 조용한 설치·업그레이드·제거 확인. macOS 작업: 계약 이름으로 zip·dmg, dmg 창 사진 |
| `tools/release/release-macos.sh` | macOS 서명·공증·묶기(`package`: zip, dmgbuild로 dmg)·확인(`verify`)·dmg 창 사진(`dmg-window`) |
| `tools/release/dmg/` | dmg 창: `dmg-settings.py`(dmgbuild 설정), `background.png`·`background@2x.png`와 그 생성기 `DmgBackground.java`, `requirements.txt`(dmgbuild 고정) |
| `tools/release/releases.sh` | 피드 생성기(`releases.json`, `releases.js`). 서버와 이 PC(Git Bash)에서 돈다 |
| `tools/release/deploy-chromite-site.ps1` | 배포 스크립트(이 PC에서 실행) |
| `tools/release/deploy-remote.sh` | 배포의 서버 쪽 절반(배포 스크립트가 올려서 실행) |
| `tools/release/rehearsal/` | 서버를 흉내 낸 로컬 Docker(sshd + Caddy)와 리허설 스크립트 |
| `site/` | 다운로드 사이트(정적 HTML·CSS·JS, 한국어·영어) |

## 설치 프로그램

### 로컬 빌드

```powershell
mvn package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing
java tools/jre/BundleJre.java windows-x64
pwsh tools/installer/build-installer.ps1 -Makensis D:\Tools\nsis-3.12\makensis.exe
# -> product/swing/target/installer/Chromite-<버전>-x64-setup.exe (서명 없음)
```

버전은 `bump-version.ps1` 검사 모드로 읽는다(`-Version`으로 바꿀 수 있음). `-ProductDir`, `-OutDir`, `-Stage`(`all`|`uninstaller`|`setup`)는 스크립트 도움말에 있다.

### 설치 범위 (2026-11-08 결정, 앱 변경 #23)

사용자 요청: "윈도우에서는 AppData가 아니고 ProgramFiles에 설치되게 기본값을 설정하라". 결정: **기본 Program Files + 선택**.

| 범위 | 폴더(기본) | 앱 목록 항목 | 바로 가기 | 권한 | 스위치 |
|---|---|---|---|---|---|
| 모든 사용자(기본) | `%ProgramFiles%\Chromite`(`$PROGRAMFILES64`) | `HKLM\...\Uninstall\Chromite`(64비트 보기) | 모든 사용자 시작 메뉴·공용 바탕 화면 | 관리자(UAC) | `/AllUsers` |
| 나만 | `%LOCALAPPDATA%\Programs\Chromite` | `HKCU\...\Uninstall\Chromite` | 사용자 시작 메뉴·바탕 화면 | 없음 | `/CurrentUser` |

- **범위를 정하는 순서**: 스위치 → 이미 있는 설치(HKCU 먼저: 0.1.3까지는 모두 사용자별) → 모든 사용자. 대화형이고 스위치도 설치도 없을 때만 "설치 범위" 화면이 묻는다
  ("이 컴퓨터의 모든 사용자 (권장)"가 기본, 관리자 권한이 필요하면 [다음]에 UAC 방패).
- **두 벌 설치 금지**: 이미 있는 설치는 그 범위·그 폴더에서 업그레이드한다(폴더 화면과 `/D=`는 새 설치에만). 0.1.3의 사용자별 설치는 0.1.3 업데이터의
  `setup.exe /S`로도, 직접 실행한 설치 프로그램으로도 그대로 사용자별로 올라간다. 스위치가 이미 있는 설치의 다른 범위를 가리키면(예: 사용자별 설치가 있는데 `/AllUsers`)
  "이미 … 설치되어 있습니다. 먼저 제거하세요"로 끝난다(종료 코드 2).
- **승격**: 설치 프로그램은 `RequestExecutionLevel user`(호출한 권한 그대로)다. 모든 사용자 범위인데 관리자가 아니면 대화형은 자기를 셸의 `runas`로
  `/AllUsers`(와 받은 `/D=`)를 붙여 다시 실행하고(UAC) 끝난다. UAC를 거부하면 "관리자 확인이 취소되어 모든 사용자용으로 설치하지 못했습니다. 다시 시도하거나 [나만 사용]을 고르세요."
  조용한 실행은 승격하지 않고 종료 코드 2로 끝난다(관리자 셸이나 배포 도구에서 실행하거나 `/CurrentUser`를 쓴다. 앱의 업데이터는 승격해서 실행한다, [auto-update.md](auto-update.md)).
- **NSIS `MultiUser.nsh`를 그대로 쓰지 않은 이유**: 두 범위를 고르는 모드는 `MULTIUSER_EXECUTIONLEVEL Highest`(매니페스트 `highestAvailable`)가 있어야 한다.
  그러면 관리자 계정은 설치 프로그램을 시작할 때마다 UAC를 받는다. 사용자별 업데이트도 마찬가지이고, 업데이터가 자식으로 띄우는 `CreateProcess`는 아예 실패한다
  (`ERROR_ELEVATION_REQUIRED`). 0.1.3 업데이터의 조용한 업데이트에도 UAC 창이 뜬다. 그래서 같은 방식(두 범위, 스위치, `SHCTX`, 범위 화면)을 직접 쓰고
  모든 사용자 범위만 승격을 요청한다.
- **제거**: 제거 프로그램은 자기 범위만 지운다. 자기 폴더를 가리키는 `HKLM` 항목이 있으면 모든 사용자(관리자 아니면 `runas`로 다시 실행, 조용한 실행은 종료 코드 2), 아니면 사용자별.
  지우는 것은 그 범위의 바로 가기와 앱 목록 항목(`SHCTX`)이다.
- **마침 화면의 "Chromite 시작"**: 승격된 설치 프로그램은 Chromite를 탐색기(`explorer.exe`)로 띄운다. Chromite가 관리자 권한이 아니라 로그인한 사용자로 돈다.
- **설치 폴더에 쓰지 않는다**(Program Files는 일반 사용자가 쓸 수 없다): 작업 공간·설정·로그는 `%APPDATA%\ChromiteData`([workspace-location.md](workspace-location.md)),
  내려받은 드라이버는 같은 데이터 폴더의 `drivers`(`DriverDescriptor.getCustomDriversHome`), 업데이트 파일은 `%LOCALAPPDATA%\Chromite\updates`([auto-update.md](auto-update.md)).
  설치 폴더는 읽기만 한다: 내장 드라이버(`DriverLibraryLocal`·`DriverLibraryBundle`), 로컬 Maven 저장소 찾기(`MavenRegistry`), 옛 작업 공간 복사의 원본(`WorkspaceLocationMigration`).
  예외는 Equinox의 OSGi 캐시(`configuration\`)다. 쓸 수 있으면 설치 폴더에, 쓸 수 없으면 Equinox가 사용자 홈(`%USERPROFILE%\.eclipse\…`)에 둔다(아래 "확인한 것").
- 업데이트: 업데이터는 범위를 앱 목록 항목의 위치(HKLM/HKCU)로 안다. 모든 사용자 설치는 받고·확인하고 [지금 다시 시작]의 진행 창에서 설치 프로그램을 승격해 실행한다([auto-update.md](auto-update.md) "관리자 권한으로 설치").
- 회사 PC에서 관리자 권한이 없으면 "나만 사용"(`/CurrentUser`)으로 설치한다. `%LOCALAPPDATA%` 실행을 막는 정책이 있으면 폴더를 고른다(설치 화면, 또는 `/D=`).

이전 결정(2026-11-07, 사용자별 설치만): 업데이트가 앱이 끝난 뒤 `setup.exe /S`를 실행하므로 Program Files면 업데이트마다 UAC 창이 뜨고 자리에 없으면 업데이트가 멈춘다는 이유로
`%LOCALAPPDATA%\Programs\Chromite`·HKCU만 썼다(Turbrix도 `perMachine: false`). 새 결정은 이 문제를 모든 사용자 설치를 [지금 다시 시작]에서만, 진행 창과 함께 승격해 설치하는 것으로 푼다.

### 화면

설치 범위(스위치도 설치도 없을 때만) → 설치 폴더(새 설치만) → 구성 요소(Chromite 필수, 바탕 화면 바로 가기 선택, 기본 꺼짐) → 설치 → 마침("Chromite 시작" 선택).
Windows 언어가 한국어면 한국어, 아니면 영어(`MUI_LANGUAGE` 순서: 영어가 기본). 제거는 확인 → 제거.
이전에 바탕 화면 바로 가기가 있었으면 업그레이드에서도(조용한 실행 포함) 다시 만든다.

### 업그레이드(제자리)와 되돌리기

1. 이 폴더의 Chromite가 실행 중인지 본다: `jre\bin\server\jvm.dll`(두 런처 모두 이 파일을 불러온다)을 쓰기로 열 수 없으면 실행 중.
   대화형은 [다시 시도]/[취소], 조용한 실행은 1초 간격 60번 기다린 뒤 종료 코드 2.
   [다시 시도] 뒤에도 실행 중이면 창 없이 남은 프로세스를 찾는 방법(다른 창 뒤의 안내 창, 작업 관리자의 `Chromite.exe`·`chromite-swing.exe`,
   다른 로그인 사용자)을 보인다. 설치 프로그램은 프로세스를 끝내지 않는다(2026-11-12, 앱 변경 #96, [exit-process.md](exit-process.md)).
2. 설치된 `uninstall.exe /S /UPGRADE`를 실행한다: 그 버전이 아는 프로그램 파일만 지운다(바로 가기, 앱 목록 항목은 둔다).
   그래서 버전 사이에 파일 목록이 바뀌어도 이전 파일이 남지 않는다. OSGi 캐시(`configuration\`)도 지워져 Equinox가 이전 번들 상태로 시작하지 않는다.
3. 새 버전이 아는 항목도 한 번 더 지운다(이전 제거 프로그램이 없거나 실패한 경우).
4. 새 파일을 복사하고 `uninstall.exe`, 시작 메뉴 바로 가기, 앱 목록 항목(이름, 버전, 게시자 Seizia, 크기, 아이콘, `https://chromite.seizia.com`)을 쓴다.

- 지우는 것은 제품 폴더의 최상위 항목 이름 목록(`files.nsh`, 빌드 때 만든다: `configuration`, `drivers`, `features`, `jre`, `licenses`, `p2`, `plugins`,
  `artifacts.xml`, `Chromite.exe`, `Chromite.ini`, `Chromitec.exe`)과, 한 버전 동안 옛 런처 이름의 파일(`chromite-swing.exe` 시작 파일, 0.1.4까지의 `chromite-swing.ini`·`chromite-swingc.exe`,
  [launcher-name.md](launcher-name.md))뿐이다. `$INSTDIR`을 통째로 지우지 않는다.
  그래서 설치 폴더 안의 다른 것(아래 `workspace\`)은 업그레이드·되돌리기·제거에서 남는다.
- 이미 있는 폴더는 비어 있거나 Chromite 설치 폴더(`Chromite.exe`, 한 버전 동안 `chromite-swing.exe`, 또는 `uninstall.exe`가 있음)일 때만 받는다. 다른 프로그램 폴더를 골랐을 때 같은 이름의 하위 폴더를 지우지 않기 위해서다.
- 설치 폴더 안의 `Chromite.ini`를 사용자가 고쳤다면(예: `-Xmx`) 업그레이드가 새 파일로 바꾼다(DBeaver와 같은 동작). 0.1.4까지의 `chromite-swing.ini`를 고쳤다면 그 값은 이름이 바뀌는 업그레이드에서 사라진다.

### 사용자 데이터의 위치

사용자 데이터(작업 공간)는 `%APPDATA%\ChromiteData\workspace6`에 있고 설치 폴더에는 없다(계약의 가정과 같다). 처음 확인했을 때는 달라서
2026-11-07에 고쳤다([workspace-location.md](workspace-location.md)).

- 고치기 전: Swing 제품은 `-data`가 없으면 실행할 때의 현재 폴더 아래 `workspace\`를 썼다. 설치한 앱을 시작 메뉴 바로 가기와 같은 현재 폴더(설치 폴더)로,
  `APPDATA`만 시험 폴더로 바꿔 실행하니 로그 `Instance path: 'file:/D:/tools/_scratch/installer-feed-site/install/Chromite/workspace/'`, 설치 폴더에
  `workspace\.metadata\…`(설정, 디버그 로그)와 `workspace\General\.project`가 생기고 `%APPDATA%\ChromiteData`에는 `drivers\`만 생겼다.
  원인은 Swing 번들의 활성화 클래스가 앱이 작업 공간을 정하기 전에 설정을 읽어 Equinox 기본값(`user.dir`/`workspace`)이 정해진 것이다
  (jdb 추적은 workspace-location.md "원인").
- 고친 뒤: 현재 폴더와 관계없이 `%APPDATA%\ChromiteData\workspace6`(`-data`가 있으면 그 폴더). 기본 작업 공간이 비어 있으면 처음 시작할 때
  `<설치 폴더>\workspace`(없으면 `<현재 폴더>\workspace`)의 Chromite 작업 공간을 한 번 복사한다. 옛 `workspace\`는 지우지 않는다.
- 설치 프로그램은 설치 폴더의 `workspace\`를 계속 지우지 않는다(업그레이드·되돌리기·제거 모두 확인). 고치기 전 버전 사용자의 원본이고, 첫 시작의 복사가
  읽는 곳이다. 시작 메뉴 바로 가기의 "시작 위치"는 설치 폴더지만, 이제 업데이트 기능이 새 버전을 어느 현재 폴더에서 시작해도 같은 작업 공간이 열린다.

### 서명과 2단계 빌드

NSIS 제거 프로그램은 설치 프로그램 안에서 만들어지므로, 설치 프로그램만 서명하면 설치된 `uninstall.exe`는 서명이 없다. 그래서 두 번 빌드한다.

1. 1단계(`/DINNER`): 제거 프로그램을 쓰기만 하는 exe를 만들고 실행해 `uninstall.exe`를 얻는다 → CI가 서명한다.
2. 2단계: 서명한 `uninstall.exe`를 파일로 넣어 `setup.exe`를 만든다 → CI가 서명한다.

설치 프로그램은 제품 폴더를 그대로 담으므로, 런처(`Chromite.exe`)를 서명한 **뒤에** 만든다. 한 버전 동안 1단계는 옛 이름 시작 파일 `chromite-swing.exe`도 만들고
CI가 `uninstall.exe`처럼 서명한 뒤 2단계가 넣는다([launcher-name.md](launcher-name.md)).
makensis는 260자가 넘는 경로를 열지 못한다(이 PC 작업 트리에서 `p2\...\cache\binary\...`를 못 열어 실패를 확인함. 제품 폴더 아래 가장 긴 파일이 145자,
GitHub 러너의 제품 폴더 경로가 약 120자). 그래서 빌드 스크립트가 작업 폴더에 짧은 이름의 디렉터리 연결(junction)을 만들어 그 경로로 넘기고, 끝나면 연결만 지운다.

### NSIS 버전 고정

`release.yml`이 공식 `nsis-3.12.zip`(SourceForge)을 받아 SHA-256 `56581F90DB321581C5381193D796FFFCF2D24B2F8FED2160A6C6A3BAA67F2C4F`를 확인한 뒤 그 `makensis.exe`를 쓴다.
러너 이미지에 깔린 NSIS는 이미지마다 버전이 바뀌므로 쓰지 않는다. 이 PC에는 같은 zip을 `D:\Tools\nsis-3.12`에 풀어 두었다
(zip의 `makensis.exe`가 이 PC에 따로 설치돼 있던 NSIS 3.12의 것과 바이트 단위로 같음을 확인).

## macOS 앱 이름 (2026-11-08 결정, 앱 변경 #22)

사용자: "먼저 이름이 ChromiteSwing 으로 나온다". Finder·응용 프로그램 폴더·Launchpad·Spotlight·dmg 창은 묶음 폴더 이름을 보인다
(메뉴 막대 이름은 `Info.plist`의 `CFBundleName`이라 이미 Chromite였다, [platform-startup.md](platform-startup.md)).

- 묶음 이름은 `Chromite.app`이다: Tycho `rootFolders`(`product/swing/pom.xml`), `BundleJre.java`, `build.yml`·`macos-smoke.yml`·`release.yml`,
  `deploy-chromite-site.ps1`. 번들 ID(`CFBundleIdentifier`)는 그대로다(서명·키체인·업데이터가 쓴다). 실행 파일 이름은 그때 `chromite-swing`으로 두었다가
  2026-11-12에 `Chromite`로 바꿨다(업데이터는 실행 파일 이름을 쓰지 않음, [launcher-name.md](launcher-name.md)).
- **자동 업데이트 유지(결정)**: 0.1.3의 업데이터(`MacUpdateVerifier`)는 zip 안의 이름이 모두 실행 중인 묶음 이름(`ChromiteSwing.app/`)으로 시작해야 받는다.
  그래서 **0.1.4만** 두 이름을 쓴다.
  - dmg(새 설치): `Chromite.app`.
  - 업데이트 zip: 같은 서명·공증·staple 묶음을 옛 이름 `ChromiteSwing.app`으로(`release-macos.sh package <app> <이름> dist ChromiteSwing.app`). 묶음 폴더 이름은
    코드 서명의 봉인과 공증 티켓(cdhash)에 들어가지 않는다. `release-macos.sh verify`가 서명한 앱(`Chromite.app`), dmg 안의 앱(`Chromite.app`), zip 안의 앱(`ChromiteSwing.app`)
    셋 모두에 `codesign --verify --deep --strict`, 팀 Developer ID·Mach-O 검사, `stapler validate`, `spctl -a -t exec`("Notarized Developer ID")를 한다(이름 둘 다 확인).
  - 0.1.3 Mac → 0.1.4: 0.1.3의 도우미가 `ChromiteSwing.app`을 같은 이름으로 바꿔 넣는다(이름은 그대로). 0.1.4 → 26.0.5에서 0.1.4의 업데이터가 이름을 바꾼다.
- **0.1.4부터의 업데이터**: 실행 중인 묶음과 이름이 달라도 zip의 묶음이 `Chromite.app`이면 받는다(번들 ID·Team ID가 실행 중인 앱과 같아야 하는 것 등 다른 확인은 같다).
  적용할 때 새 앱을 같은 폴더에 `Chromite.app`으로 놓고 옛 `ChromiteSwing.app`은 업데이트 폴더의 `before-<새 버전>/`으로 옮긴 뒤 `Chromite.app`을 연다.
  같은 폴더에 `Chromite.app`이 이미 있으면(다른 복사본) 건드리지 않고 지금 앱의 자리에 옛 이름 그대로 놓는다([auto-update.md](auto-update.md) "적용").
- **이름이 바뀔 때 사용자가 보는 것**(0.1.4 → 26.0.5의 Mac, 실제 Mac에서는 확인하지 못함):
  - 응용 프로그램 폴더·Launchpad·Spotlight에는 "Chromite"가 보인다. LaunchServices는 `open`으로 새 앱을 열 때 새 경로를 등록한다. 옛 묶음은 `~/Library/Application Support/Chromite/updates/before-<버전>/`에
    남으므로(되돌리기용) Spotlight가 그것을 함께 찾을 수 있다.
  - Dock에 고정한 옛 "ChromiteSwing" 항목은 옛 경로를 가리킨다. macOS가 그 항목을 옮겨진 옛 묶음으로 찾아가거나(파일 ID) 물음표로 보일 수 있다.
    실행 중인 새 앱은 Dock에 따로 "Chromite"로 보인다. 옛 항목은 Dock에서 빼고 새 아이콘을 "Dock에 유지"로 고정하면 된다(릴리스 노트에 적을 것).
  - 로그인 항목에 넣어 둔 옛 경로도 같은 이유로 다시 넣어야 할 수 있다.
- **26.0.5에서 한 일(릴리스 체크리스트, 2026-11-12 `chromite/release-26-0-5`)**: `release.yml` "Zip and disk image of each app"의 마지막 인자 `ChromiteSwing.app`을 지웠고(zip도 `Chromite.app`),
  `deploy-chromite-site.ps1`의 `ChromiteSwing.app` 허용과, 옛 이름 zip에만 있던 `Contents/MacOS/chromite-swing` 허용도 지웠다.
  0.1.4의 업데이터(`MacUpdateVerifier.bundleInside`, `UpdateApplier.macTarget`, 태그 `v0.1.4`의 코드)는 실행 중인 묶음이 `ChromiteSwing.app`이든 `Chromite.app`이든
  zip의 `Chromite.app`을 받고 같은 폴더에 `Chromite.app`으로 놓는다. 0.1.3의 업데이터는 zip의 이름이 실행 중인 이름(`ChromiteSwing.app`)으로 시작해야만 받으므로
  (`v0.1.3`의 `MacUpdateVerifier.verify`), 0.1.3 이하에서 26.0.5로 바로 올리는 Mac은 자동 업데이트가 안 된다(확인 단계 `EXTRACT`에서 실패, dmg로 한 번 설치).

## macOS 디스크 이미지 창 (2026-11-08 요청, 앱 변경 #26)

사용자: "dmg파일을 실행하면 … 아이콘 배치를 위에서 아래로 드래그하도록 배치하고 아이콘 크기를 더 키운다".

- 이전: `hdiutil create -srcfolder`로 앱과 Applications 링크만 넣었다(창 배치·배경 없음, 작은 아이콘이 가로로, 볼륨 이름 = 묶음 이름 "ChromiteSwing").
- 지금(`release-macos.sh package`): [dmgbuild](https://github.com/dmgbuild/dmgbuild)가 `tools/release/dmg/dmg-settings.py`로 dmg를 만든다.
  - 볼륨 이름 "Chromite", 창 480×580pt(위치 고정, 도구 막대·경로 막대·사이드바·상태 막대 없음), 아이콘 보기, 아이콘 144pt, 글자 13pt.
  - 앱 중심 (240, 150), Applications 중심 (240, 430). 그 사이에 아래로 향하는 화살표가 있는 배경(`background.png` 480×580, `background@2x.png` 960×1160을
    `tiffutil -cathidpicheck`로 한 TIFF로 묶어 Retina에서도 선명).
  - 배경색 `#0876D6`: 로고 팩의 물결 파랑(`#0A86F2`, `chromite_icon_macos.svg`)을 같은 색상에서 상대 휘도 0.179로 맞춘 것. Finder는 아이콘 이름을 밝은 모드에서 검정,
    어두운 모드에서 흰색으로 쓰고 바탕 그림에 맞춰 바꾸지 않는다. 이 바탕에서 검정 4.57:1, 흰색 4.59:1(둘 다 4.5:1 이상, 생성기가 계산해 출력)이라 밝은·어두운 Finder 모두 읽힌다.
    화살표는 로고 팩의 하늘색 `#D3E8FC`(바탕과 3.66:1). 생성기: `java tools/release/dmg/DmgBackground.java`(그림은 저장소에 있다, 바꿀 때만 실행).
  - 방법: dmgbuild는 창의 `.DS_Store`를 Finder 스크립트(AppleScript) 없이 직접 쓴다(`ds_store`, `mac_alias`). CI 러너에서 Finder 자동화는 불안정해서 이 방법을 골랐다.
  - 고정: `tools/release/dmg/requirements.txt`(dmgbuild 1.6.7, ds_store 1.3.3, mac_alias 2.2.3, 버전과 SHA-256, wheel만). `release.yml`이 venv에
    `pip install --require-hashes --only-binary :all: --no-deps`로 설치한다(러너의 Python은 외부 관리라 venv). 셋 모두 MIT이고 빌드 때만 쓰며 dmg에 들어가는 것은
    그것이 만든 `.DS_Store`·배경 그림(우리 것)뿐이라 `NOTICE`에 넣지 않았다.
- 서명·공증·staple·`verify`는 그대로다: dmgbuild가 만든 dmg를 `sign-dmg` → `notarize` → `verify`가 확인한다(dmg 안의 앱도 `codesign`·`spctl`).
- 증거: `release.yml`의 "Disk image window" 단계가 `release-macos.sh dmg-window`로 dmg를 사용자처럼 붙이고 Finder로 열어 `screencapture`한 그림(`dmg-window.png`)과
  `.DS_Store` 내용(`dmg-ds-store.txt`)을 산출물 `macos-dmg-window-<버전>`으로 올린다(실패해도 릴리스는 계속). `release.yml`은 태그나 `devel`의 수동 실행에서만 돈다.

## 피드 생성기 `tools/release/releases.sh`

```sh
sh tools/release/releases.sh [downloads 폴더] [meta 파일]
# 기본값: /srv/chromite/downloads, <downloads 폴더>/../releases-meta.tsv
```

- Turbrix `service/deploy/releases.sh`를 따랐다. 이름 규칙에 맞는 파일만 목록에 넣고 크기와 SHA-256은 직접 계산한다.
- `releases-meta.tsv`: 버전마다 한 줄, TAB으로 나눈 `<버전> <data_generation> <notes_url>`(`#`은 주석). Turbrix와 달리 **서빙하지 않는 위치**(`/srv/chromite/`)에 둔다.
- Turbrix와 다른 점: 목록에 들어갈 버전의 줄이 없거나 값이 잘못되면(세대가 양의 정수가 아님, https가 아님, 따옴표·백슬래시·공백) **멈추고 기존 `releases.json`을 그대로 둔다.**
  계약상 모든 항목에 두 필드가 있어야 하고, 스크립트가 값을 짐작하지 않게 하기 위해서다.
- 같은 폴더의 임시 파일에 쓴 뒤 이름을 바꾼다(원자적). 파일 모드 644.
- `releases.js`도 같이 쓴다: `window.chromiteReleases = <releases.json과 같은 JSON>;`와 파일마다 올린 시각 `window.chromiteReleaseDates`.
  다운로드 페이지용이다(아래 "사이트"). 업데이트 기능은 `releases.json`만 읽는다. 계약에는 영향이 없다.

## 배포 스크립트 `tools/release/deploy-chromite-site.ps1`

```powershell
pwsh tools/release/deploy-chromite-site.ps1 -Tag v0.1.0 -DataGeneration 1 `
    -Target <사용자>@<서버> -KnownHosts <known_hosts 파일> [-IdentityFile <키>] [-Port 22] [-Yes]
```

- 서버 접속 정보(호스트, 사용자, 키, known_hosts)는 인수로만 받는다. 스크립트에 넣지 않았고, 이 작업에서 키를 찾거나 쓰지 않았다(접속은 Turbrix 세션 또는 사용자).
  `StrictHostKeyChecking=yes`라 known_hosts에 서버 키가 있어야 한다.
- 저장소에 둔 이유: Turbrix는 버전마다 `.local/`에 스크립트를 따로 두지만, 이 스크립트는 비밀값이 없고 인수로 바뀌므로 리뷰할 수 있게 저장소에 둔다.
  작업 파일(받은 릴리스, 올릴 묶음)은 `.local/release/<태그>/`(git 무시)에 둔다.

단계:

1. 릴리스 파일: `gh release download <태그> --repo froiibn/chromite`(초안도 됨) 또는 `-ArtifactsDir`.
2. 이 PC에서 확인(하나라도 틀리면 서버에 아무것도 하지 않는다):
   - 세 파일이 `SHA256SUMS`와 같은지.
   - `setup.exe`: Authenticode `Valid`, 타임스탬프, 서명자 주체가 정확히 게시자 문자열, `ProductVersion`·`FileVersion` = 버전.
   - macOS zip: 묶음 하나(`Chromite.app`, 0.1.4만 `ChromiteSwing.app`)의 `Contents/MacOS/Chromite`(0.1.4까지 `chromite-swing`), 서명 폴더(`_CodeSignature/CodeResources`), 공증 티켓(`Contents/CodeResources`), `Info.plist`의 `CFBundleShortVersionString` = 버전.
     Mac에서 실행하면 `codesign --verify --deep --strict`와 `spctl`("Notarized Developer ID")도 확인한다. Windows에서는 `release.yml`의 확인을 믿는다.
   - `site/ko|en/changelog.html`에 `id="v<버전>"`가 있는지(`notes_url`이 가리키는 곳).
3. 올릴 묶음: `files/`, `site/`, `releases.sh`, `deploy-remote.sh`(LF로 바꿔서), 모든 파일의 `SUMS`.
4. 확인 질문("yes", `-Yes`면 생략) → `scp`로 `<root>/incoming/<시각>/`에 복사 → 서버에서 `deploy-remote.sh` 실행.
5. 실제 사이트 확인: `releases.json`이 세 파일을 이 PC의 크기·SHA-256·세대·`notes_url`과 같게 싣는지, 파일이 200과 같은 크기로 응답하는지,
   `releases.js`가 같은 JSON인지, 페이지·자원이 200인지, CSP에 `script-src 'self'`가 있는지.

### 서버 쪽 `deploy-remote.sh`

서버 폴더(기본 `/srv/chromite`):

| 경로 | 내용 |
|---|---|
| `site/` | 사이트(프록시가 읽기 전용으로 연결) |
| `downloads/` | `releases.json`, `releases.js`, 릴리스 파일(프록시가 읽기 전용으로 연결) |
| `releases-meta.tsv` | 버전별 세대와 `notes_url` |
| `bin/releases.sh` | 피드 생성기 |
| `incoming/<시각>/` | 배포마다 올린 묶음. 가장 최근 2개만 남기고 더 오래된 것은 배포 끝에 지운다(2026-11-08 사용자 결정, `deploy-remote.sh` 5단계). `old/<시각>/`은 그대로 둔다 |
| `old/<시각>/` | 배포마다 옆으로 옮긴 파일. 가장 최근 3개만 남기고 더 오래된 것은 배포 끝에 지운다(2026-11-08 사용자 결정, `deploy-remote.sh` 6단계). 최근 배포는 되돌릴 수 있다 |

- **지우지 않는다.** 바뀌는 파일은 먼저 `old/<시각>/`로 옮긴다. 확인은 모두 첫 변경 전에 한다:
  `SUMS`, 같은 이름인데 바이트가 다른 릴리스 파일(번호 재사용 금지), 이미 있는 더 높은 버전, 같은 버전의 다른 meta 줄.
- `site/`, `downloads/`는 프록시 컨테이너에 폴더 단위로 연결(bind mount)돼 있어 폴더 자체를 바꾸면 컨테이너는 옛 폴더를 계속 본다. 그래서 폴더 안의 항목만 옮긴다.
- 릴리스 파일은 같은 파일 시스템 안의 이름 바꾸기로 넣는다(반쯤 복사된 파일이 서빙되지 않는다).
- **보관 규칙**: `downloads/`에는 가장 높은 두 버전의 파일만 둔다. 바로 이전 버전의 `setup.exe`·zip이 업데이트 기능의 되돌리기용으로 피드에 남는다. 더 오래된 것은 `old/<시각>/downloads/`로.
- 사이트는 이전 항목을 모두 `old/<시각>/site/`로 옮긴 뒤 새 항목을 넣는다(밀리초 단위 공백).
- 서빙하는 파일은 644/755(프록시는 파일 권한 우회 능력 없이 읽는다).

### 되돌리기

- **앱**: 피드에 바로 이전 버전 설치 파일이 남아 있으므로 업데이트 기능이 그것으로 되돌릴 수 있다(설치 프로그램은 낮은 버전 덮어 설치를 받는다).
  `data_generation`이 다르면 앱이 경고한다.
- **잘못 올린 릴리스 거두기**: 서버에서 그 버전의 파일을 `old/<시각>/`로 옮기고 `sh /srv/chromite/bin/releases.sh`. 그 번호는 다시 쓰지 않는다.
- **사이트**: `old/<시각>/site/`의 항목을 `site/`로 되옮긴다(현재 항목은 다른 `old/` 폴더로).

## 리허설 (로컬 Docker)

```powershell
pwsh tools/release/rehearsal/rehearse.ps1 -Tag v0.1.0 -ArtifactsDir <파일 폴더> [-DataGeneration 1]
pwsh tools/release/rehearsal/rehearse.ps1 -Down
```

- `server`(Ubuntu 24.04 + sshd, 사용자 `ubuntu`, `127.0.0.1:2222`)와 `proxy`(`caddy:2`, 서버 프록시가 만들어진 같은 이미지, `127.0.0.1:8088`)가 볼륨 하나를 나눠 쓴다.
  프록시는 `site/`, `downloads/`를 볼륨 하위 경로로 읽기 전용 연결한다(서버의 bind mount와 같은 동작). 읽기 전용 루트, `cap_drop: ALL`도 서버와 같다.
- `Caddyfile`은 서버의 `chromite.seizia.com` 블록(헤더, CSP, `/downloads/` 처리)과 같고, 로컬이라 HTTP·접근 기록 위치만 다르다.
- 시작할 때 서버의 첫 상태(빈 `releases.json`, 자리 표시 `index.html`)를 만든다.
- 리허설 키와 known_hosts(컨테이너의 호스트 키를 직접 읽어 씀)는 `.local/rehearsal/`에 만든다.
- 배포 스크립트를 `-Rehearsal`로 실행한다: 서명 확인 실패를 "REHEARSAL, NOT CHECKED"로 넘긴다. `-Rehearsal`은 대상과 사이트가 127.0.0.1/localhost일 때만 허용된다.

### 이 PC에 Docker가 없을 때: 다른 Linux 호스트에서 리허설 (2026-11-12, 26.0.5)

`rehearse.ps1`은 이 PC의 Docker를 쓴다. 이 PC의 Docker를 쓸 수 없으면 같은 구성을 Docker가 있는 Linux 호스트(26.0.5 때는 시험용 DB VM)에 띄우고,
이 PC에서는 포트만 연결해 배포 스크립트를 그대로 `-Rehearsal`로 실행한다. `-Rehearsal`은 대상이 127.0.0.1일 때만 허용되므로 SSH 포트 전달로 맞춘다.

1. `tools/release/rehearsal/`을 호스트에 복사한다(`git archive <커밋> tools/release/rehearsal`).
2. 리허설 전용 키를 이 PC에서 만든다(`ssh-keygen -t ed25519`, 암호 없음. PowerShell에서 빈 암호 인자는 Git Bash로 넘긴다: `-N ''`).
3. 호스트에서 `REHEARSAL_AUTHORIZED_KEY=<공개 키> REHEARSAL_SSH_PORT=2222 REHEARSAL_HTTP_PORT=8088 docker compose -f compose.yaml up -d --build --wait`.
   두 컨테이너 모두 호스트의 127.0.0.1에만 열린다. 방화벽 포트는 열지 않는다.
4. 컨테이너의 호스트 키(`docker compose exec -T server cat /etc/ssh/ssh_host_ed25519_key.pub`)로 `known_hosts`를 만든다(`[127.0.0.1]:2222 <형식> <키>`).
5. 이 PC에서 `ssh -N -L 127.0.0.1:2222:127.0.0.1:2222 -L 127.0.0.1:8088:127.0.0.1:8088 <호스트>`.
6. `deploy-chromite-site.ps1 -Tag <태그> -DataGeneration <값> -Target ubuntu@127.0.0.1 -Port 2222 -IdentityFile <키> -KnownHosts <파일> -SiteUrl http://127.0.0.1:8088 -ArtifactsDir <파일 폴더> -Rehearsal -Yes`.
7. 끝나면 `docker compose down --volumes`, 이미지와 복사한 폴더 삭제, 포트 전달 종료.

운영과 같은 상태에서 시작하려면 먼저 지금 운영 중인 버전을 같은 방법으로 넣는다. 배포 스크립트가 옛 버전의 파일을 받지 않게 바뀐 뒤라면
(26.0.5부터 0.1.4의 zip을 거부) 그 버전을 넣을 때만 바뀌기 전 커밋의 스크립트를 쓴다.
태그를 붙이기 전에는 `release.yml`을 `devel`에서 수동 실행해(태그 없음, 초안 Release 없음, 번호를 쓰지 않음) 나온 서명 파일로 같은 리허설을 미리 할 수 있다.
수동 실행의 산출물에는 `SHA256SUMS`가 없으므로 `sha256sum`으로 만들어 넣는다.

## 사이트 `site/`

| 경로 | 내용 |
|---|---|
| `/index.html` | 언어 선택. `assets/lang.js`가 브라우저 첫 언어가 한국어면 `/ko/`, 아니면 `/en/`으로 보낸다(서버는 정적 파일만) |
| `/ko/`, `/en/` | 홈: 어두운 무대(첫 화면: 가로 로고·제목·부제·근거 두 묶음과 앱 창, 특장점 8장면, 마지막이 "모든 기능 무료": GitHub 저장소, Apache License 2.0, `LICENSE.md`·`NOTICE` 링크, 아래 "홈 무대"), 다운로드 블록(`#download`: 제품 소개, 들어 있는 것, Windows 설치 파일, macOS 디스크 이미지: 버전, 크기, 게시일, 방문자 운영체제 것이 먼저이고 다른 것은 단추로 펼침) |
| `/ko|en/changelog.html` | 변경 내역. 버전마다 `<section id="v<버전>">` (`notes_url`의 앵커) |
| `/ko|en/support.html` | 기능과 지원 범위: 데이터베이스(행 110개) × 기능(열 17개) 표, 찾기 칸, 열 설명, 데이터베이스별 기능, 라이선스와 출처(아래 "기능과 지원 범위 페이지") |
| `/ko|en/privacy.html` | 개인정보: 업데이트 확인은 고정 User-Agent만 보낸다, Chromite Agent(설정 전에는 보내지 않음, 무엇을 어느 제공자로 보내는지, API 키 저장 위치, 로컬 MCP 서버), 사이트에 쿠키·분석 없음, 접속 기록 30일 |
| `/favicon.ico` | 16·32·48px 아이콘(아래 "파비콘과 공유 이미지") |
| `/assets/` | `site.css`, `site.js`, `reveal.js`(무대의 한 번 등장과 반복 재생 켜고 끄기), `support.js`(지원 범위 표의 찾기 칸), `lang.js`, `chromite-icon.svg`(`chromite_logo_pack/chromite_icon_64.svg`), `fonts/`(Pretendard 1.3.9 웹 부분 집합 Regular·SemiBold, `OFL.txt`), `shots/`(앱 화면 WebP 4장: 첫 화면은 `-dark`, `-light`는 공유 이미지 생성기 입력, `shots/tour/` 기능 장면 화면 14장 `-dark`), `icons/`(Octicons 19.33.0 GitHub 마크 16·24, `LICENSE-octicons.txt`, 사이트 아이콘 PNG 3장), `og/`(공유 이미지 2장, `og-<언어>-3.png`), `brand/`(워드마크·가로 로고 SVG, 밝은·어두운 글자 각 2장, [branding.md](branding.md) "사이트의 로고") |

모든 페이지 머리글 왼쪽에 워드마크(`CHROMITE` 글자만, 홈으로 가는 링크), 오른쪽에 메뉴(다운로드, 지원 범위, 변경 내역, 개인정보)와 GitHub 마크와 저장소 링크(`https://github.com/froiibn/chromite`)가 있고,
바닥글에 지원 범위, Apache License 2.0(`LICENSE.md`), GitHub 링크와 출처 고지 한 줄(`NOTICE` 링크, [branding.md](branding.md) "사이트의 출처 고지")이 있다.

### 디자인 (2026-11-07 개편)

사용자 요청: 임시 디자인을 디자인 스킬로 세련되게 바꾸되 AI가 만든 티가 나지 않게, 오픈 소스와 GitHub 연결을 GitHub 아이콘으로 추가.

- 방향: 사이트가 앱의 화면 언어를 그대로 쓴다. 다운로드 상자는 앱의 도구 창(40px 제목 줄, 8px 모서리, 옅은 구분선), 릴리스 정보는 앱 속성 창 같은
  이름 | 값 격자다. 그 아래에 실제 앱 화면이 오므로 사이트와 화면이 한 제품으로 읽힌다. 색은 디자인 토큰([design-spec.md](design-spec.md)) 그대로이고,
  단추·링크 파랑만 글자 대비 4.5:1을 넘게 강조색을 조금 어둡게 했다(흰 글자 / `#3574F0`는 4.3:1, 단추 `#2F6AE6` 4.9:1, 링크 `#2C62D0` 5.6:1, 어두운 테마 링크 `#6F9CF9` 6.1:1).
- 다운로드 블록(2026-11-08까지는 첫 화면, 지금은 홈 무대 다음의 `section.get#download`): 왼쪽에 제목·소개와 "들어 있는 것"(지원 DB, 기능, 지원 환경), 오른쪽에 다운로드 상자.
  860px 아래에서는 소개 → 다운로드 → 들어 있는 것 순서로 쌓여(CSS `grid-template-areas`) 휴대폰에서도 내려받기 단추가 먼저 보인다.
- 나머지 절은 모두 "왼쪽 제목, 오른쪽 내용"(`.sect`) 한 가지 배치다. 홈, 변경 내역, 개인정보가 같은 리듬을 쓰고 760px 아래에서는 세로로 쌓인다.
- 피한 것: 그라데이션·빛나는 배경, 이모지 아이콘, 같은 모양 카드 세 장, 과장 문구, 대문자 꼬리표, 가운데 정렬 일색. 문구는 할 수 있는 일을 그대로 적는다.
- 글꼴은 이미 있는 Pretendard 400·600뿐이다(굵기를 늘리면 부분 집합 파일이 더 필요). 위계는 크기와 색으로 만든다. 한국어는 `word-break: keep-all`.
- 움직임은 홈 무대의 한 번 등장과 화면 사진의 반복 확대뿐이다(아래 "홈 무대", 움직임 줄이기면 없음). 그 밖에는 움직이는 것이 없다(2026-11-08 SHA-256 복사 단추를 지움). 키보드 초점은 강조색 2px 테두리.
- 앱 화면(`assets/shots/<ko|en>-<light|dark>.webp`, 1899×1188, 무손실 WebP 86~105KB): 테스트 빌드의 **사본**(`D:\Tools\_scratch\site-redesign\app`)을 사본 작업 공간으로
  실행해 창만 그렸다(앱 안에서 창을 그리는 시험 에이전트, 화면 전체가 아님). 한국어 화면은 `주문내역`, 영어 화면(`-nl en`)은 `orders`/`customers` 집계 쿼리.
  보이는 것은 연결 이름, `127.0.0.1` 주소, 시험 DB 이름, 로그인 이름 `sa`뿐이고 비밀번호는 없다. 개체 탐색기는 다른 제품 이름이 든 시험 DB 이름이 보이지 않게 스크롤했다.
  2026-11-08부터 홈은 어두운 무대에 `-dark` 한 장만 쓴다(`-light`는 공유 이미지 생성기가 읽어서 남김). 너비·높이 속성으로 자리를 미리 잡는다.
  2026-11-08 네 장 모두 다시 찍었다: 한 벤더처럼 보이던 "QMS SQL Server"·`TOP (100)`·시험 DB 이름 대신 "Sales DB"(PostgreSQL)의 `sales` 집계 쿼리와
  등록된 서버의 ERP·HR·Sales DB·Shop(아래 "파비콘과 공유 이미지"의 "그림"). 로그인 이름은 `postgres`.
- GitHub 마크는 Octicons `mark-github`(MIT) 파일을 고치지 않고 두고, CSS `mask`로 글자색을 입힌다(밝은·어두운 테마 모두). 출처는 `NOTICE`.
- 오픈 소스 절에 "Apache License 2.0으로 공개된 다른 오픈 소스 프로젝트에서 출발했다"는 문장과 `NOTICE` 링크를 둔다. 원래 프로젝트의 상표 이름은 쓰지 않는다(CLAUDE.md "브랜딩").
  예외는 2026-11-08부터 바닥글과 지원 범위 페이지의 법적 출처 고지 한 문장뿐이다([branding.md](branding.md) "사이트의 출처 고지").
- 개편 때 `site.js`, `lang.js`, 데이터 계약(`data-platform`·`data-field`·`data-state`·`data-ready`)은 바꾸지 않았다. 개인정보 내용도 그대로다(배치만 바뀜).

### 방문자 운영체제에 맞춘 다운로드 (2026-11-07)

사용자 요청: "윈도우로 접속하면 윈도우 버전 다운로드, 맥으로 접속하면 맥 버전 다운로드를 노출".

- HTML에는 두 카드가 다 있고, `site.js`가 좁힌다(점진적 향상). 방문자가 Windows 또는 macOS로 확실하고 그 카드에 파일이 있을 때만
  그 카드를 맨 위로 옮기고, 다른 카드는 `hidden`으로 접은 뒤 그 사이에 단추 "다른 운영체제용 받기 (macOS)" / "Download for macOS instead"
  (반대도 같음)를 넣는다. 단추는 실제 `<button>`이고 `aria-expanded`, `aria-controls`(`download-windows`/`download-macos`)를 가진다. 누르면 펼치고, 다시 누르면 접는다.
  단추 글은 카드의 `data-other-label`(페이지 언어별)에서 읽는다.
- 운영체제 판별: `navigator.userAgentData.platform`이 있으면 그것, 없으면 `navigator.platform`, 그것도 비면 User-Agent(`Windows NT`, `Macintosh`).
  `Win…` → Windows, `Mac…` → macOS. iPad는 `MacIntel`로 Mac처럼 보이므로 터치 지점이 2개 이상인 "Mac"은 모름으로 친다.
  모름(Linux, Android, iPhone·iPad, 봇)이면 지금처럼 두 카드 모두. 방문자 카드에 파일이 없을 때(예: Windows 파일만 있는 피드에 Mac 방문자)도 두 카드 모두.
- Intel Mac과 Apple Silicon Mac은 브라우저에서 확실히 구별할 수 없다. 그래서 macOS 카드의 "Apple Silicon(M1 이후)" 요구 사항 글은 언제나 보인다.
- JavaScript가 없을 때: 처음 상태를 `loading`에서 `static`으로 바꿨다. 전에는 스크립트 없이 "최신 버전을 확인하는 중입니다…"만 계속 보이고 카드가 없었다
  (`releases.js`와 `site.js`는 지연 스크립트라 실제 "읽는 중" 순간은 없고, 그 문구는 스크립트가 없을 때만 남았다). 이제 두 카드(요구 사항)와,
  파일 이름은 `releases.json`에 있고 `/downloads/<파일 이름>`으로 받는다는 안내, GitHub 릴리스 링크를 보여 준다.
  `site.js`는 실행되면 언제나 `ready`·`error`·`empty` 중 하나로 바꾼다. `state-loading` 문구와 CSS는 지웠다.

### 홈 무대 (2026-11-08)

사용자 요청: "애플 제품 페이지같이 슬라이드를 넘길때마다 변하는 스타일", "메인페이지를 수정대상으로 하고 애플 느낌으로 바꾼다. 움직임은 C"(스크롤에 맞춰 계속 움직이는 방식).
처음에는 첫 화면과 기능 두 개만 시제품으로 만들라고 했다가 "일단 페이지에 반영해봐"로 바뀌어 홈 전체(한국어·영어)를 바꿨다.
2026-11-07의 기능 둘러보기(한 화면씩 스냅, 화면이 보일 때 한 번 나타나기)는 사용자가 말한 방식이 아니어서 이것으로 대신했다.

같은 날 사용자가 스크롤에 묶인 움직임이 버벅이고 방문자도 불편해할 것이라고 해서, 화면 전환은 보이게 두되 훨씬 정적인 **A안, 등장형**(장면이 처음 보일 때 한 번 나타남)으로 바꿨다.

등장형이 배포된 뒤(#191) 사용자가 강조 상자는 원하던 것이 아니라고 했다: "예전 스크롤 애니메이션을 정적인 화면 위의 애니메이션으로. GIF처럼 페이지가 살아 있는 느낌".
그래서 페이지는 그대로 정적으로 두고(스크롤이 움직임을 만들지 않음, sticky·긴 장면 없음), 강조 상자 대신 **화면 사진이 스스로 반복 재생**한다(아래 "반복 재생").

- 스크롤 연동을 버린 이유(`stage.js`와 `site.css`에서 찾은 원인):
  큰 화면 사진을 1.8배까지 키우고 스크롤 프레임마다 옮겼는데 레이어 승격이 없어 계속 다시 그렸다. 움직임이 스크롤 위치에 1:1로 묶여 있어
  Windows 마우스 휠(한 칸 약 100px)에서는 계단처럼 튀었다. 프레임마다 레이아웃을 읽고 스타일을 쓴 뒤 다시 읽었다(강제 레이아웃).
  장면이 190~370vh에 sticky로 붙어 있어 페이지가 붙잡히는 느낌이었다.
- 순서: 머리글 → 어두운 무대(`div.stage`, 장면 9개) → 다운로드 블록(`section.get#download`: 제목, 소개, 들어 있는 것, 다운로드 상자) → 바닥글. 서명·자동 업데이트 절(`.sects`)은 2026-11-08에 지웠다(아래 "1차 수정 요청").
  장면: 첫 화면, 트랜잭션 보호(`#transactions`), 쿼리 라이브러리(`#query-library`), Chromite Agent(`#chromite-agent`),
  폐쇄망(`#closed-networks`), 프로시저 디버거(`#debugger`), 서버 관리(`#administration`), 비밀번호(`#passwords`), 오픈 소스(`#open-source`).
  특장점 순서와 문구 출처는 2026-11-07에 승인된 둘러보기이고, 문장을 줄이기만 하고 새 주장은 넣지 않았다. 앵커 id는 그대로다.
- 첫 화면: 가로 로고(`h1` 안의 그림, `alt="Chromite"`), 제목(`.hero-sub`), 부제(`.hero-lead`), 다운로드 단추, 근거 두 묶음(`.hero-proof`: 편하게·안전하게), 앱 창. 제목·부제·근거는 아래 "데이터베이스 지원 문구".
- 다운로드 자리: 첫 화면의 "무료로 다운로드" 단추(2026-11-08 2차 수정부터 가운데)가 `#download`(다운로드 블록)로 바로 간다. 다운로드 블록의 내용과 `site.js` 계약은 바꾸지 않았다.
- 단추 아이콘(2026-11-08 사용자 요청 "윈도우면 윈도우 아이콘만 나오고 맥이면 맥 아이콘만"): 단추에는 Windows·Apple 마크(`svg[data-os]`)가 둘 다 있고, `site.js`가 다운로드 카드와 같은 판단(`visitorPlatform`)으로 운영체제가 분명할 때 `<html data-visitor-os>`를 붙이면 `site.css`가 다른 마크를 숨긴다. 피드 확인과 관계없이 먼저 붙인다(단추는 다운로드 블록으로 이동만 하므로). Linux·휴대폰·태블릿·스크립트 없는 페이지는 둘 다 보인다.
- 단추 주소(2026-11-08 사용자 요청 "랜딩에서 바로 받게"): 방문자 운영체제가 분명하고 확인을 통과한 피드에 그 운영체제 파일이 있으면 `site.js`가 단추 주소를 그 파일(`/downloads/<파일>`, 다운로드 카드와 같은 주소)로 바꿔 누르면 바로 받는다. 운영체제를 모르거나 피드 오류·스크립트 없음이면 지금처럼 `#download`로 간다.
  다운로드 블록 제목은 `h2` "Windows와 macOS용 Chromite 받기". 페이지 높이는 1440×900에서 한국어 약 10,900px, 영어 약 12,200px(스크롤 연동 때 약 22,800px).
- 장면 구조: `section.scene` 안에 `div.scene-body`(제목 `.scene-head`, 그림 `.scene-media`, 문장 목록 `ul.lines`). 장면은 보통 높이의 절이고 문장은 모두 보이는 목록이다.
  넓은 앱 화면은 위에서 아래로(`.scene-wide`), 세로로 긴 화면·대화상자·저장소 카드는 글 옆에(`.scene-side`, 860px 아래에서는 위에서 아래로).
- 등장(`assets/reveal.js`, CSS는 `site.css`의 `.reveal`): 장면의 위쪽 끝이 화면 아래 20% 선을 지나면(`IntersectionObserver`, `rootMargin` 아래 -20%, 문턱 0)
  `is-in`을 붙이고 관찰을 끝낸다. 장면 비율(예: 25% 보임)로 하지 않은 것은 휴대폰에서 몇 화면 높이인 장면이 그 비율에 닿지 않기 때문이다.
  - 제목·설명은 0.5~0.6초 동안 나타나며 24px 올라오고, 그림은 0.12초 뒤 16px 올라오며 0.98→1배, 문장은 0.24초부터 0.08초 간격으로 한 줄씩
    나타난다. 첫 화면은 이름·제목·부제·단추·근거·앱 창 순서(0.08초 간격). 34° 기울어진 채 올라오던 앱 창과 마지막 지원 문장은 없앴다.
  - 시간 기반 CSS 전환이고 `transform`·`opacity`만 바뀐다. 스크립트는 스크롤 처리기, `requestAnimationFrame`, 레이아웃 읽기가 없다(클래스만 붙임).
    등장 요소에는 `will-change`가 없다: 전환이 도는 동안만 레이어가 생기고 끝나면 돌려준다.
- 반복 재생(2026-11-08, CSS는 `site.css`의 "the loop" 블록): 장면마다 화면 사진이 시간에 맞춰 설명하는 곳으로 확대 → 멈춤 → 다음 곳으로 이동 또는 축소 → 전체 화면을
  되풀이한다. 확대 목표는 스크롤 무대(#133, `2fe1a0bb4e`~#191 직전)의 `data-t`에 있던 것이고, 같은 방식(`transform-origin: 0 0`, 그림 크기 기준 % 이동 뒤 배율)이다.
  한국어·영어 화면 사진이 달라 키프레임이 언어별로 있고(`zoom-<장면>`과 `zoom-<장면>-ko`, `:lang(ko)`로 고름), 시간표는 같다. 그림 틀(`.frame`)이 넘치는 부분을 잘라
  확대가 창 가장자리 안에 머문다.

  | 장면 | 한 주기 | 확대하는 곳(배율) | 밝게 두는 문장 |
  |---|---|---|---|
  | 트랜잭션 | 8초 | 상태 줄의 열린 트랜잭션 칸과 그 칸이 연 커밋·롤백 메뉴(1.7) | 1번(상태 줄, 커밋·롤백) |
  | 쿼리 라이브러리 | 8초 | 라이브러리 트리의 Git 루트 "팀 쿼리 main ↑1"(1.6) | 1번(Git 루트) |
  | Chromite Agent | 12초 | 읽기 도구 두 개(차단 체인, 열린 트랜잭션)(1.6) → 원인·할 일 답(1.4) | 1번(묻는 것, 읽는 것, 원인과 할 일) 두 단계 모두(2026-11-08 문장을 다시 쓰며 2번에서 바꿈) |
  | 폐쇄망 | 8초 | 체크한 드라이버 두 줄(Derby Embedded, DuckDB)(1.6) | 2번(오프라인 묶음) |
  | 디버거 | 12초 | 14번 줄 중단점(1.8) → 변수·호출 스택·메시지·결과 탭과 변수 목록(1.5) | 2번(중단점) → 3번(변수, 호출 스택) |
  | 서버 관리 | 8초 | 작업 모니터 잠금 탭의 차단 체인 표(1.7) | 1번(세션과 잠금) |
  | 비밀번호 | 8초 | 연결 내보내기의 암호·암호 확인 입력란(1.6) | 2번(암호로 암호화한 연결 파일) |

  - 시간표: 한 곳이면 8초(전체 2초 → 1초 동안 확대 → 2.5초 멈춤 → 1초 동안 축소 → 전체 1.5초), 두 곳이면 12초(전체 2초 → 확대 1초 → 2.5초 → 이동 1초 → 2.5초 →
    축소 1초 → 전체 2초). 구간마다 같은 완만한 가감속(`cubic-bezier(0.45, 0, 0.25, 1)`)이고, 키프레임의 처음과 끝이 같은 전체 화면이라 다시 시작할 때 튀지 않는다.
  - 문장 맞추기: 문장은 모두 보이는 채로 두고, 사진이 어떤 곳을 보여 주는 동안 그 곳을 설명하는 문장은 밝게(불투명도 1), 나머지는 0.45로 흐리게 한다. 전체 화면일 때는 모두 1이다.
    문장을 숨기거나 글을 바꿔 끼우지 않으므로 움직임과 상관없이 읽히는 정적인 페이지다. 단계 번호와 문장 번호가 같지 않은 장면은 사진이 보여 주는 내용에 맞는 문장을 골랐다(위 표).
  - 바꾼 확대 목표: 디버거 두 번째 곳은 예전 값(오른쪽으로 25% 옮긴 1.5배)이면 변수 이름도 값도 잘려 빈 칸이 대부분이어서, 탭 이름과 변수 이름이 보이도록 왼쪽 아래로 옮겼다
    (문장 "변수, 호출 스택, 서버 메시지, 결과를 봅니다"와 맞음). Chromite Agent는 예전에 확대가 없었고(세로로 올라오기만), 도구 호출 → 답 두 단계를 새로 정했다.
    좁고 긴 화면이라 확대하면 글 오른쪽이 잘린다. 비밀번호는 왼쪽으로 6% 옮기던 것을 없애 입력란 이름 첫 글자가 잘리지 않게 했다. 나머지는 예전 목표와 배율 그대로다(트랜잭션은 칸과 메뉴가 붙어 있어 한 번 확대로 둘 다 보인다).
  - 성능(스크롤 무대를 버린 이유를 되풀이하지 않기 위해): 움직이는 것은 그림의 `transform`(그림에 `will-change: transform`, 합성기 레이어)과 문장의 `opacity`뿐이라
    프레임마다 레이아웃·그리기가 없다. 스크립트는 프레임마다 하는 일이 없다: `reveal.js`의 두 번째 `IntersectionObserver`(여백 없음)가 장면이 화면에 조금이라도 보이는 동안
    `is-playing`을 붙이고, 화면 밖 장면은 `animation-play-state: paused`로 그 자리에서 멈춘다. 탭이 숨으면(`visibilitychange`) 모든 장면에서 떼고, 다시 보이면 화면 안 장면에만 붙인다.
    스크롤 처리기와 `requestAnimationFrame`은 없다.
  - 등장과의 순서: 반복 애니메이션은 `is-in`과 `is-playing`이 모두 있을 때만 돌고, 1.5초 지연(`animation-delay`, 돌 때만 흐름) 뒤에 시작한다. 그 사이에 등장 전환(문장의
    `opacity`, 그림의 올라오기)이 끝나므로 애니메이션이 등장을 덮어쓰지 않는다. 첫 주기는 전체 화면에서 시작한다.
  - 첫 화면의 앱 창은 움직이지 않는다(브리프: 차분하게, 기울기 없음). 오픈 소스 장면은 그림이 없어 반복이 없다.
- 움직이지 않는 경우: `reveal.js`는 `<head>`에서 `defer` 없이 읽혀 첫 그리기 전에 `html.reveal`을 붙인다. 숨김 상태는 `html.reveal`이 있고
  `@media screen and (prefers-reduced-motion: no-preference)`일 때만 있다. 그래서 JavaScript가 없거나, `IntersectionObserver`가 없거나, 움직임 줄이기를 켰거나
  (열린 뒤 켜도 CSS가 바로 모두 보임), 인쇄할 때는 처음부터 모두 보인다. 관찰자 설정이 실패하면 `reveal`을 다시 뗀다(내용이 숨은 채 남지 않음).
  반복 재생도 같은 조건(`html.reveal`과 `prefers-reduced-motion: no-preference`) 안에만 있어서, 이 경우에는 확대하지 않은 전체 화면 사진과 흐리지 않은 문장만 보인다.
- 무대 색: 테마와 상관없이 늘 어둡다(바탕 `#000000`, 글 `#F7F8FA`, 보조 글 `#9DA0A8`, 선 `#393B40`, 링크 `#6F9CF9`, 앱 토큰). 무대의 화면 사진이 앱의 어두운 테마이고,
  장면마다 밝고 어두움이 바뀌면 스크롤 중에 번쩍인다. 머리글·다운로드 블록·바닥글은 밝은/어두운 테마를 따른다(밝은 테마에서는 흰 머리글 → 검은 무대 → 밝은 다운로드).
- 글자: Pretendard 600, 첫 화면 로고 폭 `clamp(260px, 40vw, 560px)`(예전 글자 이름 `clamp(76px, 14vw, 208px)` 자리), 첫 화면 제목 `clamp(26px, 3.2vw, 46px)`, 장면 제목 `clamp(32px, 4.4vw, 64px)`·-0.035em,
  문장 `clamp(19px, 1.75vw, 25px)`. 글은 왼쪽 정렬이고 첫 화면의 앱 창만 가운데. 무대와 다운로드 블록이 같은 폭(`--page` 1180px)이라 왼쪽 끝이 맞는다.
- 화면 사진: 기능 장면은 `assets/shots/tour/<화면>-<ko|en>-dark.webp` 14장(2026-11-07 둘러보기의 사진 그대로, 1.5배 캡처)을 `srcset="… 1.25x"`로 넣어 앱보다 조금 크게 보인다.
  첫 화면은 `assets/shots/<ko|en>-dark.webp`(1899×1188). 첫 화면 사진만 바로 받고 나머지는 `loading="lazy"`.
  `assets/shots/<ko|en>-light.webp` 두 장은 공유 이미지 생성기의 입력이다(아래 "파비콘과 공유 이미지").
- 지운 것(스크롤 무대 → 등장형): `assets/stage.js`, CSS의 `.motion`·`.pin`·`.slot`·`.hero-statement`·`.slot .small`(쓰는 곳 없음)과 그림의 `transform-origin`·`perspective`,
  페이지의 `data-t` 속성 10개씩과 장면 높이용 `n2`·`n3`·`n6` 클래스, 첫 화면의 지원 문장. `div.pin`은 `div.scene-body`, `ul.slot`은 `ul.lines`로 바꿨다.
  (2026-11-08 처음 무대를 만들 때 지운 것: `assets/tour.js`, CSS의 `.tour`·`.tour-index`·`.feature*`·`.more`·`.shot`·`.visually-hidden`·스크롤 스냅·`--shot-shadow`,
  홈의 앱 화면 `figure.shot`, 둘러보기 목록. `.hero`(다운로드 블록)는 `.get`으로 이름을 바꿨다.)
  (2026-11-08 반복 재생으로 바꾸며 지운 것: 강조 상자 마크업 `span.mark`(언어마다 7개)와 CSS `.mark`·위치 규칙·등장 전환·600px 이름표 숨김, 그림 틀의 `position: relative`.)
- 둘러보기 화면 사진의 출처(2026-11-07): 테스트 빌드(#119)의 **사본**을 사본 작업 공간으로 실행하고, 앱 안에서 도는 시험 에이전트가 Swing 공개 API로 조작하고 창만 그렸다(OS 입력 없음).
  화면 배율 1.5. 연결 이름(Sales DB, Billing 등)과 `127.0.0.1` 외에 비밀번호·키·개인 정보는 없다. Chromite Agent 화면은 [ai-agent.md](ai-agent.md)의 방식대로 로컬 가짜
  Messages API(가짜 키)로 찍었고, 도구 호출과 결과는 실제 DB 값이며 답의 글은 시험용 제공자가 돌려준 것이다. 이 사실을 그림 아래 설명(`figcaption`)에 적었으나 2026-11-08 사용자 요청으로 그림 설명을 모두 지웠다.
  잠금 화면은 시험 PostgreSQL의 임시 DB에서 `psql` 세션이 행을 잡고 쿼리 창의 UPDATE가 기다리게 만들었고, 디버거 화면은 pldebugger를 넣은 임시 컨테이너로 찍었다. WebP 품질 82, 장당 16~82KB.

### 데이터베이스 지원 문구 (2026-11-08)

사용자 의견: "우리 도구는 4개 db만 다루지 않는다. 드라이버만 미리 세팅되어있을 뿐이다. 오히려 이 4개 db만 된다는 오해를 살 수 있다. 이건 특장점이 아니다".

- 근거: `plugins/*/plugin.xml` 44개에 `<driver id=…>` 요소가 181개 있고(XML로 읽어 셈. 정규식으로는 주석 속 3개를 더해 184), 그중 `label`이 있는
  드라이버 정의가 144개다. 나머지 37개는 옛 드라이버 ID를 데이터 소스 종류에 잇는 이름 없는 항목이다. 44개 플러그인은 모두 `db`·`drivers.public`·`swing` 기능에 들어 있다.
  이름이 있는 드라이버를 제품 단위로 묶으면(같은 제품의 옛·클라우드·임베디드 판, CSV·DBF·JDBCX·Avatica·Arrow Flight 같은 파일·연합 드라이버 제외) 약 92개라
  "100가지가 넘는 데이터베이스"는 확실히 참이라고 할 수 없다. 그래서 사이트는 "100가지가 넘는 드라이버"라고 쓴다(144개, 옛·사용 중단 표시 약 18개를 빼도 120개 이상).
  드라이버 관리자에서 사용자가 JDBC 드라이버를 새로 더할 수 있다(`DriverManagerDialog.createDriver`).
- 미리 설치된 드라이버(SQL Server, Oracle, PostgreSQL, MariaDB, SQLite, [drivers.md](drivers.md))는 편의로 적고 한계처럼 적지 않는다. 나머지는 처음 연결할 때 받거나 오프라인 묶음으로 설치한다.
- 첫 화면 문장, 홈의 `description`·`og:description`·`twitter:description`, 공유 이미지와 모든 페이지의 그림 설명: 드라이버 수와 "여러 데이터베이스".
- 서버 관리 장면 제목은 DB 수가 아니라 도구("DBA 도구가 처음부터 들어 있습니다" / "DBA tools built in")이고, 설명에서 깊은 관리 도구가 지금 다루는 DB(SQL Server, Oracle,
  PostgreSQL, MariaDB, Altibase는 세션과 잠금)와 다른 DB도 연결해 쿼리·개체 탐색을 한다는 것을 한 번 적는다. 관리 코드에는 MySQL 경로도 있지만
  ([swing-admin-tools.md](swing-admin-tools.md)) 시험은 MariaDB로만 했으므로 사이트에는 MariaDB만 적었다. 마지막 줄 "Altibase도 연결합니다"는 다른 DB는 안 된다는 뜻으로 읽혀 "Altibase는 …"로 바꿨다.
- Chromite Agent 장면: 스키마·데이터·쿼리 도구는 모든 연결(도구 카탈로그의 G), 진단 도구(실행 계획, 잠금, 상태 점검)는 SQL Server, PostgreSQL, MariaDB·MySQL, Oracle
  ([ai-agent.md](ai-agent.md) "도구 카탈로그"의 MS·PG·MY·OR). 이 장면은 375px에서 문장 목록이 원래도 화면 아래로 조금 넘친다(영어 약 24px). 설명을 길게 쓰면 더 넘쳐서
  예전 설명과 같은 줄 수로 줄였다(헤드리스 Edge로 잼). 넘침 자체는 이 작업 범위 밖이라 그대로 두었다.
- 변경 내역(0.1.0의 "JDBC 드라이버 내장", 0.1.1의 MySQL 드라이버 안내)은 그 버전에서 있었던 일이라 고치지 않았다.
- 2026-11-08 두 번째 의견: "데이터베이스 개수는 특장점이 아니다". 드라이버 수도 제목·특장점으로 쓰지 않는다. 첫 화면 문장(드라이버 수)을 지우고,
  홈의 설명 태그 3개와 공유 이미지·alt를 아래 제목으로, 폐쇄망 장면 첫 줄은 미리 설치된 드라이버부터 적게 바꿨다. 다운로드 블록 "데이터베이스" 줄은 정보로 남기되
  수로 시작하지 않는다("…미리 설치되어 있고, 그 밖의 드라이버(모두 100가지가 넘음)는…").
- 제목과 부제(사용자 결정, 2026-11-08. 처음 고른 "누구나 안전하게 다루는"에 "편하게"를 더함: 쿼리 라이브러리가 편한 쪽, 트랜잭션 기능이 안전한 쪽):
  - 한국어: "누구나 편하고 안전하게 다루는 데이터베이스 도구" / "시스템·애플리케이션 운영자도 조회부터 데이터 수정까지 쉽고 실수 없이"
  - 영어: "A database tool anyone can use easily and safely" / "Even system and application operators can query and fix data with ease and without slip-ups"
  - 쓰는 곳: 첫 화면(`.hero-sub`·`.hero-lead`), 홈의 `description`·`og:description`·`twitter:description`("Chromite - 제목. 부제. Windows와 macOS용 설치 파일을 받습니다."),
    공유 이미지(생성기 `OG_COPY`), 7쪽의 `og:image:alt`·`twitter:image:alt`. 문구를 바꾸면 이 네 곳을 같이 고치고 생성기를 다시 실행한다.
- 첫 화면 근거 두 묶음(`.hero-proof`). 문장마다 지금 앱에서 참인지 코드로 확인했다:
  - 편하게: 자주 쓰는 쿼리를 쿼리 라이브러리에 저장하고 값만 넣어 실행(#137, 실행할 때 매개 변수 대화상자. 공유 이미지 촬영 중에도 "Parameters - …" 창이 뜸),
    팀과 공유 폴더나 Git으로 함께 씀(#112, 라이브러리 루트, `library_git_pull`·`library_git_commit_push`).
  - 안전하게: WHERE 없는 UPDATE·DELETE, DROP, TRUNCATE는 실행 전에 확인(`SqlExecutionSafety.confirmDangerousStatements`, 기본값 묻기).
    결과 표 편집의 SQL 미리 보기는 **"볼 수 있음"**으로 적었다: 저장 전 스크립트 표시(`ResultSetGrid.isSavePreviewRequired`)는 설정
    `resultset.confirm.beforeSave`(기본 꺼짐) 또는 연결 종류의 "데이터 변경 확인"일 때만 저절로 나오고, 그 밖에는 "변경 미리 보기" 명령으로 본다.
    받은 문구 "저장 전에 SQL을 미리 보여 줌"은 기본 설정의 개발 연결에서는 참이 아니다.
    열린 트랜잭션 표시와 묻지 않는 커밋·롤백 없음([transactions.md](transactions.md), [idle-transactions.md](idle-transactions.md)). 예외는 사용자가 연결 종류에서
    직접 켠 유휴 트랜잭션 자동 롤백(알림이 뒤따름)뿐이다. 2차 수정부터 이 줄은 "묻지 않고 커밋하지 않음, 자동 롤백은 켠 경우에만"이다(아래).
- 2026-11-08 2차 수정(아래 "2차 수정 요청" 11·13번): 홈에는 데이터베이스 이름과 데이터베이스·드라이버 수를 쓰지 않는다. 위에 적은 서버 관리 장면의 DB 나열, Agent 장면의 네 DB 문장,
  미리 설치된 드라이버 이름은 모두 홈에서 빠지고, 데이터베이스별 세부 사항은 지원 범위 페이지(`support.html`)에만 둔다. 변경 내역의 지난 기록은 그대로다.

### 1차 수정 요청 (2026-11-08)

사용자가 8가지를 모아 "일단 여기까지 수정해서 반영하라. 확인 후 추가 수정사항을 도출하겠다"고 했다. 한국어·영어 페이지에 똑같이 적용했다.

| # | 요청 | 바꾼 것 |
|---|---|---|
| 1 | 머리글은 로고의 글자만 | 모든 페이지 머리글 `.brand`가 워드마크 그림(밝은·어두운 테마별, [branding.md](branding.md) "사이트의 로고") |
| 2 | 페이지 맨 위에는 풀 로고 | 홈 첫 화면의 큰 글자 "Chromite" 자리가 가로 로고 그림(`h1` 안, `alt="Chromite"`), 언어 선택 페이지 `/`의 아이콘·이름 자리도 가로 로고. 제목·부제는 그대로 |
| 3 | 비밀번호 장면 | 제목 "비밀번호는 내 컴퓨터에 안전하게 보관됩니다" / "Your passwords stay safely on your computer", 첫 줄 "…암호화해 보관합니다" |
| 4 | 트랜잭션 장면 | 제목 "안전한 트랜잭션 관리" / "Safe transaction management" |
| 5 | 폐쇄망 장면 | 제목 "기업용 폐쇄망을 위한 편의성" / "Made for closed corporate networks". 설명 "폐쇄망에서도 드라이버를 따로 받을 필요가 없습니다" / "…even on a closed network", 둘째 줄 "…폐쇄망 PC로 옮깁니다". 변경 내역 0.1.1은 그대로 |
| 6 | 서명·업데이트·SHA-256 내용을 모든 페이지에서 뺌 | 다운로드 블록 아래 "서명과 확인"·"자동 업데이트" 절, 카드의 SHA-256 줄과 복사 단추, 폐쇄망 장면의 "설치 전에 모든 파일의 SHA-256을 확인합니다", JavaScript 없을 때 안내의 "(크기, SHA-256 포함)", "무료" 장면의 코드 서명 설치 파일·자동 업데이트 문장. CSS `.sects`·`.hash`·`.copy`와 그것만 쓰던 `--selection` 토큰, `site.js`의 SHA-256 채우기와 복사 처리기를 지움 |
| 7 | 상업적·비상업적 관계없이 모든 기능 무료 | 첫 화면 안내 줄, "무료 오픈 소스" 장면(제목·설명·첫 줄), 공유 이미지 작은 줄(`og-<언어>-3.png`) |
| 8 | Chromite Agent 장면 전면 재작성 | 도구 목록 6줄을 읽는 사람이 물을 수 있는 것과 얻는 것으로: 설명 한 줄과 문장 4개 |

- 첫 화면 로고는 두 테마 모두 흰 글자 판(`logo-dark.svg`)이다. 무대가 테마와 상관없이 늘 검은색이라 남색 글자 판은 보이지 않는다. 남색 판(`logo-light.svg`)은
  밝은 테마의 언어 선택 페이지가 쓴다. 언어 선택 페이지에는 머리글이 없어서, 아이콘과 이름이 있던 맨 위 자리에 풀 로고를 두었다(사용자 말 "페이지 상단에 풀 로고").

- 남긴 것: `site.js`의 피드 형식 검사(64자리 16진수 `sha256`)는 화면에 보이지 않고 잘못된 항목이 내려받기 단추에 오르지 않게 막으므로 그대로 둔다.
  개인정보 페이지의 "업데이트 확인이 보내는 정보"는 사실 고지라 그대로다. 폐쇄망 장면의 "자동 업데이트 확인은 옵션에서 끌 수 있습니다"와 비밀번호 장면의
  "밖으로 나가는 요청은 업데이트 확인, …"도 폐쇄망·개인정보에 관한 사실이라 그대로 두었다(사용자가 지울 것으로 꼽은 목록에 없음).
- 무료 문구: 첫 화면 "Windows와 macOS용. 회사에서도 개인도, 모든 기능 무료." / "For Windows and macOS. Every feature free, at work or at home.".
  장면 제목 "회사에서도 개인도, 모든 기능이 무료" / "Every feature free, at work or at home", 설명 "업무에 써도 개인적으로 써도 유료판, 기능 제한, 구독이 없습니다." /
  "Commercial or not, there is no paid edition, no feature limit and no subscription.", 첫 줄은 근거: 상업적 사용을 허용하는 Apache License 2.0과 GitHub 공개 소스.
  Chromite에는 유료판·기능 제한·구독이 없다(제품 문서 어디에도 없음). Claude·OpenAI 사용료는 각 제공자의 것이라 Agent 장면에 "직접 넣은 API 키"로 적었다.
- Chromite Agent 문장마다 지금 앱에서 참인지 [ai-agent.md](ai-agent.md)로 확인했다:
  - 평소 말로 묻고 도구로 근거를 모아 답함: "목적", 채팅 도구 창.
  - 실행 계획, 잠금과 차단 체인, 세션·대기·공간: `agent.performance`·`agent.health`의 `getBlockingChain`·`listLocks`·`listSessions`·`getWaitStats`·`getSpaceUsage` 등, DB 표기 MS PG MY OR.
    그래서 "이런 진단은 SQL Server, PostgreSQL, MariaDB·MySQL, Oracle에서 합니다"를 붙였다(그 밖의 DB는 `unsupported`).
  - 바꾸는 일은 SQL을 보여 주고 확인 뒤, 운영 연결의 쓰기는 막음: "권한 정책" 판정 순서 4·7번(WRITE·DANGEROUS 기본 CONFIRM, 운영 연결 WRITE/DANGEROUS → BLOCKED),
    "확인"의 미리 보기(실행할 SQL·DDL 전체). 사용자가 연결별로 AUTO로 바꿀 수 있어서 "(기본 설정)"을 붙였다.
  - 모든 도구 호출은 감사 기록: "감사 기록"(거부·막힘 포함 `audit.jsonl`).
  - 제공자 Claude, OpenAI, Ollama와 로컬 MCP 서버(기본 꺼짐, 루프백만): "사용자 결정", "로컬 MCP 서버".
- Agent 장면의 반복 재생: 화면 사진(질문 → 차단 체인·열린 트랜잭션 도구 → 원인과 할 일 답)이 새 1번 문장(묻고, 읽고, 원인과 할 일을 알려 줌)과 맞아서
  두 단계 모두 1번을 밝게 둔다(예전 2번 "블로킹과 교착 상태"). 확대 키프레임과 시간표는 그대로다. 목록의 `tasks` 클래스와 굵은 소제목 CSS(`.lines.tasks`)는 지웠다.
- 머리글 워드마크는 높이 16px, 첫 화면 로고는 폭 `clamp(260px, 40vw, 560px)`, 언어 선택 페이지 로고는 320px이다. 다운로드 블록 뒤의 서명 절을 지워
  `.get`에 아래 여백(80px, 760px 아래 56px, 예전 `.sects` 위 여백과 같음)을 주었다.
- 새 한글은 Pretendard 부분 집합에 모두 있다(페이지와 생성기 문구, 없는 글자를 `.notdef` 그림과 비교해 찾는 방법).

### 2차 수정 요청 (2026-11-08)

1차가 배포된 뒤 사용자가 모은 7가지(9~15번)를 "지금까지 모은 것을 모두 반영"하라고 했다. 한국어·영어 페이지에 똑같이 적용했다. 사용자 결정은 그대로 따랐다.

| # | 요청 | 바꾼 것 |
|---|---|---|
| 9 | 다운로드 단추를 turbrix.com처럼 가운데, "무료로 다운로드", 왼쪽에 Windows·Apple 아이콘 | 첫 화면 `.hero-cta`가 `div`로 바뀌고 단추와 안내 줄만 가운데 정렬(로고·제목·부제·근거는 그대로 왼쪽). 단추 글 "무료로 다운로드" / "Download free", 둥근 알약 모양(`border-radius: 999px`, 높이 48px). 안내 줄 "Windows와 macOS용. 회사에서도 개인도, 모든 기능 무료."는 단추 아래 가운데. 방문자 운영체제 우선(#101)과 다운로드 블록은 그대로 |
| 10 | 트랜잭션 장면과 첫 화면 근거 줄 | 장면 둘째 줄 "…알림으로 다시 묻거나 자동 롤백 처리합니다." / "…after the time you set, or rolled back automatically." 근거 줄 "열린 트랜잭션을 표시하고, 묻지 않고 커밋하지 않음, 자동 롤백은 켠 경우에만" / "Open transactions are shown, nothing is committed without asking, and automatic rollback happens only if you turn it on" |
| 11 | 홈에서 DB 이름·수를 모두 뺌 | Agent 장면의 "이런 진단은 SQL Server, …에서 합니다" → "어느 데이터베이스에서나 스키마·데이터·쿼리를 다루고, 시스템 정보를 직접 조회해 잠금·실행 계획·서버 상태를 진단합니다." 서버 관리 장면 설명은 할 수 있는 일("작업 모니터에서 세션과 잠금, 작업 스케줄러, 공간과 로그, 유지 관리, 복제 상태를 한곳에서 다룹니다."), 작업 스케줄러 줄은 "데이터베이스의 작업 스케줄러에 등록된 작업을 보고, 만들고, 고칩니다.", Altibase 줄은 지움(내용은 지원 범위 페이지). 폐쇄망 장면 첫 줄과 다운로드 블록 "데이터베이스" 줄은 "자주 쓰는 드라이버는 미리 들어 있고, 다른 드라이버는 처음 연결할 때 받거나 오프라인 묶음으로 설치"(이름·수 없음, "모두 100가지가 넘음"도 뺌). 그림 설명에서 `PL/pgSQL`과 체크된 드라이버 이름(Derby Embedded, DuckDB)을 뺌 |
| 12 | 디버거 장면 | 제목 "프로시저 디버깅 지원" / "Procedure debugging". 첫 줄 "함수와 프로시저를 디버거에서 실행합니다.", 둘째 줄 "…프로시저 단위 실행과 한 단계씩 코드 실행으로 디버깅합니다. 데이터베이스가 지원하면 프로시저 나가기도 씁니다." / "…debug with Step Over and Step Into, and Step Out where the database supports it." DB 이름은 지원 범위 페이지로 |
| 13 | 기능별 지원 범위 새 페이지 | `/ko/support.html`, `/en/support.html`(아래 "기능과 지원 범위 페이지"). 관련 장면(Chromite Agent, 폐쇄망, 디버거, 서버 관리)의 제목 아래와 다운로드 블록 "데이터베이스" 줄 끝에 "지원 범위 자세히" / "Database support details" 링크(`.scene-more`). 모든 페이지의 머리글 메뉴("지원 범위" / "Database support")와 바닥글에도 링크. 배포 스크립트의 실제 사이트 확인에 두 쪽과 `support.js`를 더함 |
| 14 | 무료 장면 | 첫 줄 "Chromite는 상업적 사용을 허용하는 Apache License 2.0을 따릅니다." / "Chromite is licensed under the Apache License 2.0, which allows commercial use." 뒤의 소스 공개 문장은 그대로 |
| 15 | 원래 프로젝트 출처 고지 | 모든 페이지 바닥글과 지원 범위 페이지 맨 아래("라이선스와 출처")에 고지 한 문장과 `NOTICE` 링크. 홈 본문·첫 화면·메타·공유 이미지에는 없음. 문구와 규칙은 [branding.md](branding.md) "사이트의 출처 고지" |

- 아이콘: 사이트 안의 인라인 SVG(외부 리소스 없음, CSP 그대로). Windows 마크는 네 칸 정사각형을 직접 그렸고, Apple 마크는 Simple Icons 13.21.0의 `apple` 경로(CC0 1.0, 출처 표시 의무 없음)다.
  `fill="currentColor"`라 단추 글자색(흰색)을 따르고, 장식이라 `aria-hidden="true"`(단추 이름은 글자).
- 단추 색: turbrix.com은 밝은 바탕의 어두운 알약이지만 Chromite 무대는 늘 검은 바탕이라 어두운 단추가 보이지 않는다. 모양(알약, 가운데, 아래 작은 줄)만 따르고 색은 사이트의 단추 파랑(`#2F6AE6`, 흰 글자 4.9:1)을 그대로 썼다.
- 반복 재생: 서버 관리 장면은 문장이 4개에서 3개로 줄었지만 밝게 두는 문장(1번)은 그대로다. 디버거 장면은 문장 수(4개)와 밝게 두는 문장(2번 → 3번)이 그대로다.
- 바뀐 문구의 한글과 지원 범위 페이지의 글자(“ ” 따옴표 포함, 448자)는 모두 Pretendard 부분 집합에 있어 글꼴 파일은 바꾸지 않았다(공유 이미지 생성기도 같은 파일). 공유 이미지 문구(`OG_COPY`)는 바뀌지 않아 그림을 다시 만들지 않았다.
- 홈과 개인정보 4쪽에 데이터베이스 제품 이름이 없음을 확인했다(바닥글의 출처 고지만 있음, 아래 "확인한 것").

### 기능과 지원 범위 페이지 (2026-11-08)

사용자 결정: 랜딩(홈)은 특장점만 쓰고, 데이터베이스별 세부 지원은 별도 페이지의 표로. 세로축은 드라이버 정의가 있는 모든 데이터베이스, 가로축은 기능, 칸은 지원 / 일부 / 미지원과 짧은 이유,
이유는 "DB가 제공하지 않음"과 "Chromite에서 아직 지원하지 않음"을 구분, 코드·설계 문서로 확인한 사실만.

- 파일: `site/ko/support.html`, `site/en/support.html`, `site/assets/support.js`, `site.css`의 `.matrix*`·`.filter*`·`--state-*` 토큰. 문서형 쪽(변경 내역·개인정보와 같은 머리글·`.page-head`·`.sect`·바닥글).
- 행 110개, 세 묶음:
  - 주요 데이터베이스 7개: SQL Server, Oracle, PostgreSQL, MySQL, MariaDB, Altibase, SQLite(Chromite가 직접 시험하고 고친 DB와 미리 설치된 드라이버).
  - 그 밖의 데이터베이스 98개, 이름순("Apache "는 빼고 정렬).
  - 파일·연합 드라이버 5개: CSV, DBF, JDBCX, Apache Calcite Avatica, Apache Arrow Flight SQL.
- 행을 만든 기준: `plugins/*/plugin.xml`에서 `label`이 있는 `<driver>` 144개(XML로 읽어 셈)를 제품 단위로 묶었다. 같은 제품의 옛·사용 중단·클라우드 판은 한 행이다(MySQL 5·MySQL (Old)는 MySQL,
  PostgreSQL (Old)는 PostgreSQL, Sybase jTDS·jConnect·SAP ASE jConnect는 SAP ASE, PrestoSQL은 Trino, TDengine Cloud는 TDengine, Timeplus Proton은 Timeplus, H2 서버·임베디드 판은 H2).
  기능이 다른 판은 따로 둔다: Azure SQL, Babelfish(같은 SQL Server 데이터 소스지만 실행 추적·에이전트·백업·계획 판단이 다름), OceanBase MySQL 모드·Oracle 모드, Db2 LUW·IBM i·z/OS.
- 열 17개: 쿼리 편집기·결과, 프로시저 디버거, 실행 추적(SQL Server), 실제 실행 계획, 예상 실행 계획, 세션·잠금, 작업 스케줄러, 공간·로그, 유지 관리, 복제 상태, 보안 편집기,
  네이티브 백업·복원, 스키마 비교, Chromite Agent 공통 도구(스키마·데이터·쿼리 도구와 `runReadOnlySql`), Agent 전용 진단 도구, 드라이버(미리 설치 / 처음 연결할 때 받음 / 직접 지정), 오프라인 묶음.
  Agent를 두 열로 나눈 것은 "어느 데이터베이스에서나 진단"(공통 도구, 모든 행 지원)과 "전용 진단 도구는 다섯 계열만"을 한 칸에 "일부"로 섞으면 모든 DB가 제한된 것처럼 읽히기 때문이다.
- 칸 판정 규칙:
  - 지원: 그 DB용 구현이 코드에 있고 그 DB를 위해 만든 것(Chromite가 시험했거나 upstream이 그 DB용으로 둔 코드). 작은 글씨는 조건(예: "이 PC의 pg_dump·pg_restore 사용").
  - 일부: 핵심의 일부가 빠짐. 빠진 것과 이유의 종류를 적었다. 작은 글씨는 "구체적인 이유(이유의 종류)" 순서다(예: "MySQL 8 역할은 없음(Chromite에서 아직 지원하지 않음)").
  - 미지원 · DB가 제공하지 않음: 설계 문서가 DB에 기능·API가 없다고 적은 경우만(예: SQL Server 디버거, [procedure-debugger.md](procedure-debugger.md) "다른 데이터베이스", 대신 실행 추적).
  - 미지원 · Chromite에서 아직 지원하지 않음: 그 DB용 구현이 없음. DB 자체에 기능이 있는지는 판단하지 않는다(쪽의 "표 읽는 법"에 적음). 근거 없이 "DB가 제공하지 않음"이라고 쓰지 않으려고 고른 규칙이다.
  - 확인 필요: 부모 플러그인의 클래스를 물려받아(`instanceof`, 어댑터 팩터리, 서버 종류) 메뉴는 켜지지만 그 DB를 위해 만들거나 시험하지 않은 경우.
- 근거(칸마다 확인한 곳):
  - 디버거: `DebugCommands`(어댑터 `DBGControllerFactory`), `ext.postgresql.debug.core`·`ext.oracle.debug.core`의 `plugin.xml`, PostgreSQL 프로시저 나가기 없음(`PostgreDebugSession.canStepReturn`), [procedure-debugger.md](procedure-debugger.md).
  - 실행 추적: `SQLServerExecutionTrace.canTrace`, [sqlserver-execution-trace.md](sqlserver-execution-trace.md)(Azure SQL Database는 서버 범위 XE 없음).
  - 실제 계획: `SQLActualPlanProvider` 어댑터(SQL Server, Oracle, Altibase), PostgreSQL·MySQL은 문장을 두 번 실행하는 방식이라 넣지 않음([swing-sql-editor-plus.md](swing-sql-editor-plus.md)).
  - 예상 계획: `DBCQueryPlanner`를 주는 플러그인(PostgreSQL 계열, MySQL·TiDB·OceanBase MySQL, SQL Server, Oracle, Altibase, Db2 LUW, HANA, Firebird, Exasol, Ocient, H2, HSQLDB, Hive, Mimer, CUBRID).
    CockroachDB·Materialize는 서버 종류가 `supportsExplainPlan() = false`라 확인 필요, YashanDB는 계획기를 일부러 끔.
  - 관리 축(세션·잠금, 공간, 로그, 유지 관리, 복제, 보안): `com.seizia.chromite.model.admin`의 `ServerAdminProfile`·`ServerSpaceDialect`·`ServerLogDialect`·`MaintenanceDialect`·`ReplicationDialect`·`SecurityDialect`의 `forDataSource`
    ([swing-admin-tools.md](swing-admin-tools.md), [swing-security.md](swing-security.md)). PostgreSQL 축은 서버 종류가 `PostgreServerPostgreSQL`(TimescaleDB, Google Cloud SQL 포함)일 때만이라 다른 PostgreSQL 계열은 옛 세션 관리자(확인 필요) 또는 미지원.
    TiDB·OceanBase MySQL·NDB·YashanDB는 `MySQLDataSource`·`OracleDataSource`를 물려받아 확인 필요. Db2 LUW·Exasol은 프로세스 목록만(일부).
  - 작업 스케줄러: [swing-job-schedulers.md](swing-job-schedulers.md), [swing-sqlserver-agent.md](swing-sqlserver-agent.md). pg_cron 노드는 서버 종류가 확장을 지원할 때(Greenplum 계열, YugabyteDB, EDB, Yellowbrick은 확인 필요).
  - 백업·복원: `BackupRestoreCommands.canBackUp`, `NativeBackupRestore.isSupported`([swing-backup-restore.md](swing-backup-restore.md)). Oracle은 RMAN·Data Pump가 없어 미지원.
  - 스키마 비교: [schema-compare.md](schema-compare.md)(개체 탐색기 기반, 모든 드라이버 같은 코드).
  - Agent: [ai-agent.md](ai-agent.md) "도구 카탈로그", `DbFamily.of`(드라이버 제공자 ID, 그다음 서버 제품 이름), `runReadOnlySql`(모든 DB, 읽기 판정 `AgentSqlClassifier`).
  - 드라이버: `drivers.public` 번들 5개를 `bundle="drivers.…"`로 쓰는 드라이버가 미리 설치(SQL Server·Azure·Babelfish, Oracle, PostgreSQL·TimescaleDB·YugabyteDB·CockroachDB·CrateDB·RisingWave·Google Cloud SQL·Greenplum·Cloudberry·Greengage, MariaDB, SQLite).
    파일 정의가 없는 Netezza·Cloudera Impala·OpenEdge·Pervasive SQL·EDB와 받을 수 없는 Cloudflare D1(404, [driver-licenses.md](driver-licenses.md))은 직접 지정. Db2 z/OS는 IBM 별도 라이선스 JAR.
  - 오프라인 묶음: [driver-offline-bundle.md](driver-offline-bundle.md)(받을 파일이 있는 드라이버는 모두, 미리 설치된 것은 필요 없음).
- 확인 필요 칸(111칸, 23행): Amazon Redshift(디버거, 세션, Agent 전용), Apache Doris·StarRocks(Agent 전용: MySQL 프로토콜이라 계열 판별 결과 미확인), Azure SQL·Babelfish(세션, 공간·로그, 유지 관리, 복제, 보안, Agent 전용),
  Cloudberry·Greengage·Greenplum·YugabyteDB·Yellowbrick(디버거, 세션, 작업 스케줄러, 보안, 백업, Agent 전용), CockroachDB(디버거, 예상 계획, 세션, 보안, 백업, Agent 전용), CrateDB(디버거, 세션, 백업, Agent 전용),
  EDB(세션, 작업 스케줄러, 보안, 백업, Agent 전용), GaussDB·Kingbase·RisingWave(디버거, 세션, 보안, Agent 전용), Google Cloud SQL for PostgreSQL(디버거), Materialize(디버거, 예상 계획, 세션, 백업, Agent 전용),
  NDB Cluster·TiDB·OceanBase MySQL 모드(세션, 공간·로그, 유지 관리, 복제, 보안, 백업, Agent 전용, NDB는 작업 스케줄러도), OceanBase Oracle 모드(Agent 전용), YashanDB(디버거, 실제 계획, 세션, 공간·로그, 유지 관리, 복제, 보안, Agent 전용).
  실제 서버로 시험하면 지원·미지원으로 바꾼다.
- 데이터베이스별 기능 절: SQL Server 에이전트(Express에는 없음), Altibase(저장 방식별 테이블 색, 서버 출력, 세션과 잠금, 실제 계획, 복제 상태와 명령; 홈에서 지운 Altibase 줄의 내용).
- 쓰기 편의: 표는 상자(`.matrix`) 안에서 가로·세로로 스크롤한다. sticky 칸은 가장 가까운 스크롤 상자에 붙으므로, 상자가 세로로도 스크롤해야 머리 행이 붙는다(상자 높이 `100vh - 96px`, 600px 아래 `75vh`).
  머리 행(`thead th`)과 첫 열(`tbody th`)이 sticky이고 왼쪽 위 칸이 가장 위(`z-index` 3). 묶음 행은 칸이 표 전체 폭이라 글자만 sticky(`span`). 쪽 자체는 가로로 스크롤하지 않는다.
  칸은 상태를 글로 쓰고(지원/일부/미지원/확인 필요), 점의 색과 모양은 같은 뜻을 되풀이할 뿐이다(색만으로 구별하지 않음).
- 찾기 칸(`support.js`): HTML에서는 `hidden`이고 스크립트가 보이게 한다. JavaScript가 없으면 칸 없이 표 전체가 보인다. 행의 `data-terms`(이름과 다른 이름, 소문자)에 입력한 글자가 들어 있으면 보이고,
  행이 모두 숨은 묶음(`tbody`)도 숨긴다. 표시 수 문구는 상자의 `data-count`(언어별)에서 읽어 스크립트에 화면 글이 없다. CSP(`script-src 'self'`) 때문에 같은 출처 파일이다.
- 고칠 때: 표는 두 언어 HTML에 직접 있다(행마다 `<tr data-terms>`, 칸은 `<td class="y|p|n|u">상태<small>이유</small></td>`). 처음 만들 때는 칸 판정을 한 곳에 모은 일회용 스크립트로 두 언어를
  함께 썼다(저장소에 넣지 않음). 기능이 바뀌면 두 언어의 같은 칸을 함께 고치고, 근거를 이 절에 더한다.

### 파비콘과 공유 이미지 (2026-11-07)

사용자 요청: "웹사이트는 파비콘과 og 이미지도 만들어서 반영한다".

- 파일: `/favicon.ico`(16·32·48px), `/assets/icons/icon-32.png`, `icon-192.png`, `apple-touch-icon.png`(180px, 불투명 남색 바탕), 기존 `/assets/chromite-icon.svg`,
  공유 이미지 `/assets/og/og-ko-3.png`, `og-en-3.png`(1200×630). 생성기 `tools/branding/gen_site_images.py`가 `gen_app_icons.py`의 크기별 아이콘 그림(16px는 16px용
  그림)과 `chromite_logo_pack/chromite_wordmark_light.svg`, 사이트의 Pretendard 부분 집합, 실제 앱 화면(`assets/shots/<언어>-light.webp`) 일부로 만든다.
  공유 이미지 문구(`OG_COPY`)는 홈의 제목과 부제(위 "데이터베이스 지원 문구")와 작은 줄 "회사에서도 개인도, 모든 기능 무료 · Windows와 macOS" / "Every feature free, at work or at home · Windows, macOS"이고(작은 줄은 나누지 않으므로 글 칸 폭 538px 안에 들게 썼다), 생성기가 글 칸 폭에 맞춰 띄어쓰기에서 줄을 나눈다
  (작은 줄은 아래 여백이 남을 때만). 모든 페이지의 `og:image:alt`·`twitter:image:alt`가 제목을 적는다. 문구를 바꾸면 생성기를 다시 실행하고 alt 7쪽을 같이 고친다.
- 파일 이름의 `-3`(`OG_VERSION`): 카카오톡 등 메신저와 SNS는 링크 미리 보기를 이미지 주소로 캐시해서, 같은 이름에 새 그림을 올려도 예전 그림(네 DB 이름을 적은 첫 판)을
  계속 보여 준다. 그림을 바꿀 때는 `OG_VERSION`을 올리고 7쪽의 `og:image`·`twitter:image`를 새 이름으로 바꾼다. 예전 `og-ko.png`·`og-en.png`, `og-ko-2.png`·`og-en-2.png`는 다른 곳에서 쓰지 않아 지웠다.
- 그림(2026-11-08): 메인 창(개체 탐색기, 쿼리, 결과 표, 등록된 서버)을 중립 이름으로 다시 찍었다. 한 벤더 이름처럼 보이던 "QMS SQL Server"·`TOP (100)` 대신
  연결 "Sales DB"(PostgreSQL)의 `sales` DB와 `orders`·`customers` 도시별 집계이고, 등록된 서버에 ERP·HR·Sales DB·Shop(SQL Server·Oracle·PostgreSQL·MariaDB 아이콘)이
  보인다. 첫 화면의 `<언어>-dark.webp`도 같은 이유("QMS SQL Server"가 보였음)로 같은 장면을 어두운 테마로 다시 찍었다. 드라이버 고르기 화면은 다시 수를 파는 그림이라 쓰지 않았다. 촬영: v0.1.3 zip의 **사본**(`D:\Tools\_scratch\site-reveal\app`)을 `CHROMITE_DATA`와 사본 작업 공간으로
  실행하고 앱 안의 시험 에이전트가 창만 그렸다(OS 입력 없음, 화면 배율 1.5, 창 1266×792). 시험 데이터는 공유 PostgreSQL에 임시 `sales` DB를 만들어 넣고 촬영 뒤 지웠다.
  비밀번호·키·개인 정보는 없다.
  새 한글은 Pretendard 부분 집합에 있는 글자인지 확인한다(없는 글자는 빈 네모로 그려진다).
  색은 디자인 토큰(바탕 `#F7F8FA`, 글 `#1E1F22`·`#6C707E`, 테두리 `#DFE1E5`)이고 그라데이션 바탕은 없다.
- 모든 페이지(`/`, `/ko|en/`, 변경 내역, 개인정보)의 `<head>`: 아이콘 링크 5개, `<link rel="canonical">`(https://chromite.seizia.com/… ), `og:type`·`og:site_name`·`og:title`·
  `og:description`·`og:url`·`og:image`(절대 https 주소)·`og:image:type`·`og:image:width`·`og:image:height`·`og:image:alt`·`og:locale`(`ko_KR`/`en_US`)·
  `og:locale:alternate`, `twitter:card=summary_large_image`와 같은 제목·설명·이미지. 제목·설명은 그 페이지의 `<title>`·`description`(홈은 "Chromite - 데이터베이스 워크벤치").
  언어 선택 페이지 `/`는 영어 이미지를 쓴다.
- `site.webmanifest`는 만들지 않았다: CSP에 `manifest-src`가 없어 `default-src 'none'`이 매니페스트를 막는다. CSP를 바꾸지 않기로 했으므로 넣어도 쓰이지 않는다.

### CSP와 `releases.js`

서버 CSP(Turbrix 세션이 설정, 2026-11-07):
`default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; font-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`

- `connect-src`가 없어 페이지가 `fetch('/downloads/releases.json')`을 할 수 없다. CSP를 바꾸지 않고 `releases.sh`가 같은 JSON을 스크립트 `releases.js`로도 쓰고,
  페이지가 `<script src="/downloads/releases.js">`로 읽는다. `/downloads/`의 JSON이 아닌 파일에 붙는 `Content-Disposition: attachment`는 스크립트 실행에 영향이 없다(리허설에서 확인).
- 사이트에 필요한 CSP: 지금 것 그대로. 인라인 스크립트·스타일 없음(외부 `site.css`), 외부 출처 없음(글꼴도 같은 출처).
  나중에 `connect-src 'self'`를 더하면 `releases.js` 없이 `releases.json`을 바로 읽게 바꿀 수 있다.
- 피드에 파일별 날짜가 없어서 게시일은 `releases.js`의 `chromiteReleaseDates`(서버에 올라간 시각)로 보여 준다.
- 밝은·어두운 테마는 OS 설정(`prefers-color-scheme`)을 따른다. 색은 앱의 디자인 토큰([design-spec.md](design-spec.md))과 같다.
- 화면 글에 다른 제품 이름을 쓰지 않는다. 소스 링크는 `https://github.com/froiibn/chromite`(공개 저장소, `NOTICE` 포함).

## 릴리스 순서

1. `devel`에서 버전을 올리고([versioning.md](versioning.md): 첫 사용자 배포 판 전에는 `26.0.x`의 셋째 수, 첫 사용자 배포 판(26.1.0)은 사용자가 정한 뒤 `-FirstUserRelease`),
   같은 PR에 `site/ko|en/changelog.html`의 `<section id="v<버전>">`을 쓴다.
2. 그 커밋에 `v<버전>` 태그 → `release.yml`이 서명 파일과 `SHA256SUMS`로 **초안** Release를 만든다.
3. 저장 데이터 형식이 바뀌었는지 정해 `-DataGeneration`을 고른다(바뀌었으면 이전 값 + 1).
4. 사용자 승인 뒤 `deploy-chromite-site.ps1`로 배포(먼저 리허설). 확인이 모두 OK인지 본다.
5. 사용자가 GitHub 초안 Release를 공개한다(배포는 초안에서도 된다).

릴리스 체크리스트(버전별):
- [x] **26.0.5**(문서의 옛 "0.1.5"): macOS 업데이트 zip의 옛 이름을 없앤다. `release.yml` "Zip and disk image of each app"의 마지막 인자 `ChromiteSwing.app`과
  `deploy-chromite-site.ps1`의 `ChromiteSwing.app` 허용을 지운다(위 "macOS 앱 이름"). 26.0.5 릴리스 노트에 Mac 이름 변경(Dock 항목 다시 고정)을 적는다.
  (2026-11-12 `chromite/release-26-0-5`. 지운 뒤 0.1.4 태그를 다시 배포하면 배포 스크립트가 zip을 거부한다.)
- [x] **런처 이름 `Chromite`가 처음 들어간 버전(26.0.5)**: 릴리스 노트에 Windows 실행 파일 이름 변경과 작업 표시줄 고정(다음 버전부터 다시 고정)을 적는다(2026-11-12 `site/ko|en/changelog.html` `#v26.0.5`).
- [ ] **0.1.4 설치본이 남아 있지 않을 때**(26.0.6에서는 하지 않음): 옛 이름 시작 파일 `chromite-swing.exe`를 지운다([launcher-name.md](launcher-name.md) "다음 버전에서 지울 것").
  26.0.6은 26.0.5가 나온 당일의 수정판이라 0.1.4 설치본이 남아 있다. 0.1.4에서 26.0.6으로 바로 업데이트하는 설치본은 업데이트 뒤 옛 이름으로 다시 시작하므로 시작 파일이 있어야 한다.

## 확인한 것

2026-11-07, 이 PC(Windows 11). 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` `BUILD SUCCESS`(3분 8초),
`java tools/jre/BundleJre.java windows-x64` 성공. 시험용 0.1.1은 `bump-version.ps1 0.1.1`로 같은 빌드를 한 번 더 한 뒤 버전 파일을 되돌렸다(커밋하지 않음).

- 설치 프로그램 빌드(NSIS 3.12, `D:\Tools\nsis-3.12`): `Chromite-0.1.0-x64-setup.exe` 155,813,247 B, `Chromite-0.1.1-x64-setup.exe` 155,818,206 B, 각 약 2분.
  `ProductVersion`/`FileVersion` `0.1.0`/`0.1.1`, `ProductName` Chromite, `CompanyName` Seizia. 한국어 첫 화면 캡처(`D:\Tools\_scratch\installer-feed-site\shots\setup-ko.png-0.png`).
- 설치·업그레이드·되돌리기·제거(시험 폴더 `D:\Tools\_scratch\installer-feed-site\install\Chromite`, 모두 `/S`):
  - 0.1.0 설치: 종료 코드 0, 제품 파일 729개 모두 같은 바이트 + `uninstall.exe`. 앱 목록 항목(Chromite, 0.1.0, Seizia, 크기, 조용한 제거 명령), 시작 메뉴 바로 가기(시작 위치 = 설치 폴더).
  - 앱 실행 중 0.1.1 `/S`: 61초 뒤 종료 코드 2, 아무것도 바뀌지 않음(0.1.0 번들, 앱 목록 0.1.0).
  - 앱 종료 뒤 0.1.1 `/S`: 종료 코드 0(15초). 제품 파일 729개 모두 0.1.1과 같은 바이트, 남은 옛 파일 0개, OSGi 캐시 지워짐, `workspace\` 안의 표식 파일 유지.
  - 0.1.0 `/S`로 되돌리기: 종료 코드 0, 0.1.0 번들, 남은 파일 0개, 작업 공간 15개 파일 해시 그대로. 다시 0.1.1로 올려 앱 시작(`bundles.info`가 0.1.1, 창 뜸, 같은 작업 공간), 정상 종료.
  - `uninstall.exe /S`: 설치 폴더에는 `workspace\`만 남고, 앱 목록 항목·시작 메뉴 바로 가기 없음, 시험 `APPDATA\ChromiteData` 유지.
  - 다른 파일이 든 폴더로 `/S /D=`: 종료 코드 2, 그 파일 유지, 앱 목록 항목 만들지 않음.
- `release.yml` Windows 단계 중 "Install NSIS 3.12 (pinned)", "Installer pass 1", "Installer pass 2", "Silent install and uninstall check"를 작업의 `run` 그대로(환경 변수만 맞춰) 이 PC에서 실행해 통과.
  서명 단계와 "Package (zip)"(7-Zip 없음)는 실행하지 않았다. YAML은 PyYAML로 읽어 단계 목록을 확인했다.
- 피드 생성기(Git Bash, 서버와 같은 Ubuntu 컨테이너의 dash): 이름이 맞지 않는 파일·폴더 무시, 숫자 순서(0.1.10 > 0.1.9), meta 줄 없음·공백 든 URL에서 멈추고 기존 파일 유지, 빈 폴더 `{"format":1,…,"files":[]}`.
- 리허설(로컬 Docker, 서버와 같은 CSP): 서명 없는 0.1.0 설치 파일(실물)과 가짜 macOS 파일로 0.1.0 → 0.1.1 배포, 모든 확인 OK, 피드에 0.1.1·0.1.0 설치 파일.
  0.1.0 다시 배포 거부("newer than"), 0.1.1 변경 내역 앵커 없을 때 거부, 서버 쪽에서 0.1.2(가짜)로 보관 규칙(0.1.0 파일 `old/`로), 같은 번호 다른 바이트 거부.
  자리 표시 페이지와 빈 `releases.json`이 `old/<시각>/`에 남는 것 확인.
- 사이트(리허설 주소, 내장 브라우저): 한국어·영어, 밝은·어두운 테마, 변경 내역·개인정보 페이지, 모바일 폭(375px, 가로 스크롤 없음), `/`에서 `/ko/`로 이동,
  콘솔 오류 없음(CSP 위반 없음), Pretendard 400·600 적용.
- 사이트 개편(2026-11-07, "디자인"): 서명된 실제 0.1.0 파일로 리허설을 처음부터 다시 실행(`-Down` 뒤 `rehearse.ps1 -Tag v0.1.0`) `ALL CHECKS OK`, 사이트 파일 21개.
  헤드리스 Edge(CDP)로 한국어·영어 × 밝은·어두운 × 1440px·375px 전 페이지를 찍고 확인: 가로 스크롤 없음(`scrollWidth` = 폭), 콘솔·CSP 오류 없음,
  Pretendard 400·600 로드, 테마에 맞는 화면 이미지(`*-light.webp`/`*-dark.webp`), 내려받기 링크 `/downloads/Chromite-0.1.0-x64-setup.exe`·`/downloads/Chromite-0.1.0-arm64.dmg`,
  복사 단추(글자가 "복사됨"으로 바뀌었다 돌아오고 클립보드 = 화면의 SHA-256), `/`가 `ko-KR`이면 `/ko/`·`en-US`이면 `/en/`.
  `releases.js`를 가로채 바꾼 세 경우: 없음(404) → 오류 안내, 빈 피드 → "아직 공개된 릴리스가 없습니다", Windows만 → Windows 카드만 채우고 macOS는 "아직 없습니다".
  영어 개인정보 페이지가 375px에서 긴 `<code>` URL 때문에 가로로 넘치던 것(첫 확인에서 455px)을 `code { overflow-wrap: anywhere }`로 고쳤다.
  화면 사진: `D:\Tools\_scratch\site-redesign\shots\`.
- 방문자 운영체제(2026-11-07): 리허설(실제 0.1.0 파일)에서 헤드리스 Edge로 User-Agent·`navigator.platform`·Client Hints를 바꿔 한국어·영어 페이지 확인.
  Windows(Client Hints `Windows`) → Windows 카드만, 단추 "다른 운영체제용 받기 (macOS)". macOS(`macOS`)와 Client Hints 없는 Safari Mac(`MacIntel`) → macOS 카드만,
  단추 "… (Windows)". 단추 `aria-expanded` false → 누름 true(다른 카드 보임) → 다시 누름 false(다시 숨김). Linux, iPad(`MacIntel` + 터치 5) → 두 카드.
  JavaScript 끔 → 두 카드(요구 사항)와 `static` 안내. 어두운 테마, 375px 모두 가로 스크롤 없음, 콘솔·CSP 오류 없음.
  기존 확인(복사 단추, 빈 피드·Windows만·`releases.js` 없음, `/` 언어 이동)을 다시 돌려 같은 결과. 화면 사진: `D:\Tools\_scratch\site-redesign\shots-platform\`.
- 기능 둘러보기·파비콘·공유 이미지(2026-11-07): 서명된 실제 0.1.0 파일로 리허설을 처음부터 다시 실행(`-Down` 뒤 `rehearse.ps1 -Tag v0.1.0`) `ALL CHECKS OK`.
  헤드리스 Edge(CDP, 자기 프로필)로 한국어·영어 × 밝은·어두운 × 1440×900·375×812에서 화면 8개를 차례로 찍었다: 가로 스크롤 없음, 콘솔·CSP 오류 없음,
  화면마다 목록의 현재 항목이 그 화면(`aria-current`), 스크롤이 끝나면 숨은 글 없음. 움직임 줄이기(`prefers-reduced-motion: reduce`)와 JavaScript 끔에서는
  `is-animated`가 붙지 않고 처음부터 모든 글이 보인다. 스냅 비교: `proximity`는 첫 화면·다운로드에서 자유롭게 움직이고 기능 화면 시작 근처에서 멈춘다.
  `mandatory`를 넣어 보면 `scrollTo(0, 0)`이 첫 기능 화면(1868px)으로 끌려가 첫 화면을 볼 수 없다(그래서 `proximity`).
  `/favicon.ico` 200 `image/vnd.microsoft.icon`, 아이콘 PNG·공유 이미지 200 `image/png`, 모든 페이지 7개에 아이콘·canonical·OG·Twitter 태그 23개.
  공유 이미지는 서버에서 받은 파일을 1200×630 그대로, 그리고 600×315로 줄여 글이 읽히는지 봤다. 화면 사진: `D:\Tools\_scratch\site-feature-tour\check3\`(처음부터 한 리허설),
  `check4\`(글 옆 그림 칸을 넓힌 CSS 한 줄 뒤, 같은 리허설 서버에 사이트 파일만 다시 복사해 같은 확인).
  헤드리스 브라우저에는 탭 줄이 없어서 탭의 파비콘은 직접 보지 못했다. 대신 `favicon.ico`의 16·32·48px 항목을 밝은·어두운 탭 색 위에 그려 확인했다
  (`D:\Tools\_scratch\site-feature-tour\evidence-favicon-tabs.png`).

- 스크롤 무대(2026-11-08): `-Down` 뒤 리허설을 처음부터 실행(`rehearse.ps1 -Tag v0.1.1 -DataGeneration 2`, 실제 0.1.1 파일) `ALL CHECKS OK`, 사이트 파일 42개.
  헤드리스 Edge(CDP, 자기 프로필)로 한국어·영어 × 밝은·어두운 × 1440×900·375×812 홈 8가지와 움직임 줄이기·JavaScript 끔 8가지, 변경 내역·개인정보 4쪽을 확인했다:
  가로 스크롤 없음(`scrollWidth` = 폭), 콘솔·CSP 오류 없음(Edge의 정보 수준 "지연 로드 이미지 자리 표시" 알림만), 깨진 이미지 없음, 내려받기 링크
  `/downloads/Chromite-0.1.1-x64-setup.exe`(헤드리스 Edge는 Windows라 Windows 카드가 먼저, macOS 카드는 단추 뒤), 복사 단추("복사됨"/"Copied", 클립보드 = 화면의 SHA-256).
  움직이는 경우 `html.motion`, 문장 30개 중 22개가 흐려진 상태(장면마다 한 줄씩 보임). 움직임 줄이기와 JavaScript 끔은 `motion` 없음, 문장 30개 모두 보임,
  JavaScript 끔에서 다운로드 상자는 `static`(두 카드와 파일 위치 안내). 페이지 높이: 움직일 때 약 22,800px(1440×900), 움직이지 않을 때 약 10,900px.
  장면마다 진행도 0·25·50·75·100%(한국어 9장면, 영어 0·50·100%), 375px 한 장씩, 밝은 테마 첫 화면·무대에서 다운로드로 넘어가는 곳, 무대 전체를 100px씩
  스크롤한 GIF(720×450, 7.5MB): `D:\Tools\_scratch\site-apple-scroll\proto\`. 움직임 줄이기·JavaScript 끔 전체 페이지: `D:\Tools\_scratch\site-apple-scroll\check\`.
- 홈 무대 등장형·제목·공유 이미지(2026-11-08): `-Down` 뒤 리허설을 처음부터 실행(`rehearse.ps1 -Tag v0.1.2`, 이어서 `-Tag v0.1.3`, `-DataGeneration 2`, 실제 릴리스 파일) 둘 다 `ALL CHECKS OK`(사이트 파일 47개).
  리허설 서버(실제 CSP)에서 헤드리스 Edge(CDP, 실행마다 새 프로필)로 한국어·영어 × 밝은·어두운 × 1440×900·375×812 홈 8가지, 움직임 줄이기·JavaScript 끔 8가지,
  변경 내역·개인정보 4쪽과 `/`: 가로 스크롤 없음, 콘솔·CSP 오류 없음(정보 수준 지연 로드 알림만), 깨진 이미지 없음, 7쪽의 `og:image`·`twitter:image`가 `og-<언어>-2.png`,
  `og-ko-2.png`·`og-en-2.png` 200 `image/png`(137,878·148,595 B), 예전 `og-ko.png`·`og-en.png`·`stage.js`는 404. 움직일 때 처음에는 숨는 부분 59개 중 53개(첫 화면의 보이는 것은 바로 등장),
  장면마다 화면에 들인 뒤 1.3초 후 화면 안에서 숨은 부분 0개. 움직임 줄이기·JavaScript 끔은 처음부터 숨은 부분 0개(`html.reveal` 없음), JavaScript 끔에서 다운로드 상자 `static`.
  같은 브라우저로 쪽을 20번 넘게 연달아 읽으면 아래쪽 장면 몇 개에서 관찰자 알림이 오지 않는 일이 있었다(같은 쪽을 새 브라우저로 읽으면 재현되지 않음). 시험 도구 쪽 현상으로 보고
  변형을 묶음마다 새 브라우저로 나눠 확인했다. 강조 상자 위치: 6장면 × 2언어 × 1440·375px 화면 사진으로 하나씩 봄.
  성능(정적 서버, 1440×900 어두운 한국어 홈, 휠 100px씩 50ms 간격으로 무대 끝까지, 성능 추적): 긴 작업(50ms 넘는 작업)은 예전·지금 모두 0개.
  예전은 215칸 동안 스크립트 18ms, `requestAnimationFrame` 229번·함수 호출 461번(스크롤마다), 그리기 380번. 지금은 80칸(무대 길이가 약 1/3) 동안 스크립트 0ms,
  함수 호출 7번(관찰자 알림), 처음 지날 때만 등장 전환과 지연 로드 그림 때문에 스타일 계산 618번·그리기 439번. 한 번 다 본 뒤 다시 지나가면 예전은 그대로(스크립트 18ms,
  그리기 343번, 작업 212ms), 지금은 스크립트 0ms·스타일 계산 0번·그리기 16번·작업 64ms. 헤드리스라 GPU 래스터 비용은 추적에 다 잡히지 않는다.
  공유 이미지 두 장은 1200×630으로 직접 봄. 화면 사진: 강조 상자 `D:\Tools\_scratch\site-reveal\marks\`, 전체 쪽 `D:\Tools\_scratch\site-reveal\check\`,
  성능 결과 `D:\Tools\_scratch\site-reveal\perf\`.
- 데이터베이스 지원 문구(2026-11-08): `-Down` 뒤 리허설을 처음부터 실행(`rehearse.ps1 -Tag v0.1.1 -DataGeneration 2`, 실제 0.1.1 파일) `ALL CHECKS OK`, 사이트 파일 42개.
  헤드리스 Edge(CDP, 자기 프로필)로 한국어·영어 × 밝은·어두운 × 1440×900·375×812 홈: 가로 스크롤 없음, 깨진 이미지 없음, 콘솔·CSP 오류 없음(정보 수준 지연 로드 알림만),
  Pretendard 400·600 로드, 첫 화면·Chromite Agent·폐쇄망·서버 관리·"데이터베이스" 줄과 설명 태그 3개가 새 문구. 변경 내역·개인정보 4쪽과 `/`의 `og:image:alt`·`twitter:image:alt`가 새 문구,
  `/assets/og/og-ko.png`·`og-en.png` 200 `image/png`(120,391·137,400 B). 새 한글이 Pretendard 부분 집합에 모두 있음(없는 글자를 .notdef 그림과 비교해 찾는 방법, `漢`으로 검출 확인).
  Chromite Agent 장면은 375px에서 문장 목록 끝이 화면 아래로 넘치는 양을 예전 문구와 비교해 같거나 적게 맞췄다(영어 24px, 예전과 같음).
  화면 사진: `D:\Tools\_scratch\chromite-site-db-wording\shots\`.
- 홈 무대 반복 재생(2026-11-08): 리허설 컨테이너가 없는 상태에서 처음부터 실행(`rehearse.ps1 -Tag v0.1.2`, 이어서 `-Tag v0.1.3`, `-DataGeneration 2`, GitHub 릴리스에서 받은 실제 파일) 둘 다 `ALL CHECKS OK`.
  리허설 서버(실제 CSP)에서 헤드리스 Edge(CDP, 실행마다 새 프로필)로 한국어·영어 × 밝은·어두운 × 1440×900·375×812 홈 8가지, 움직임 줄이기·JavaScript 끔 8가지, 변경 내역·개인정보 4쪽과 `/`:
  가로 스크롤 없음, 깨진 이미지 없음, 콘솔·CSP 오류 없음(정보 수준 지연 로드 알림만), 다운로드 상자 `ready`(JavaScript 끔은 `static`), `span.mark` 0개.
  움직일 때 애니메이션 29개(그림 7, 문장 22) 중 화면 안 장면의 것만 `running`이고 나머지는 멈춤. 움직임 줄이기는 `html.reveal` 없음, 애니메이션 0개, 확대된 그림 0개, 흐린 문장 0개.
  장면마다 주기 안의 시점(전체·각 곳의 멈춤 가운데·전체)으로 애니메이션을 옮겨 그림 틀과 장면 전체를 찍어 확대가 맞는 곳에 닿는지 봤다(한국어·영어 × 1440·375px,
  `D:\Tools\_scratch\site-loop\frames\`). 디버거 장면을 실제 시간으로 0.4초마다 찍은 한 주기: `D:\Tools\_scratch\site-loop\seq\`, 모아 본 그림 `seq-debugger-sheet.png`.
  탭 숨김: 헤드리스 탭은 실제로 숨길 수 없어 페이지 안에서 `document.hidden`을 참으로 바꾸고 `visibilitychange`를 보냈다. 화면 안 두 장면(폐쇄망, 디버거)의 `is-playing`이 빠지고
  애니메이션 8개가 멈춘 채 1.5초 동안 시간이 그대로였고, 다시 거짓으로 바꾸자 두 장면만 다시 돌았다.
  성능(정적 서버, 1440×900 어두운 한국어 홈, 성능 추적): 디버거 아래쪽과 서버 관리 위쪽 두 장면이 도는 동안 10초 가만히: 긴 작업 0개, 스크립트 0ms, 레이아웃 0번,
  메인 스레드 프레임 138개에 스타일 계산이 프레임당 약 0.12ms(애니메이션 요소 9개), 그리기 11번(1.4ms). 휠 100px씩 무대 끝까지(한 번 다 본 뒤): 긴 작업 0개, 스크립트 0.3ms
  (관찰자 알림 14번), 레이아웃 0번, 그리기 20번. 같은 측정으로 #191 등장형은 스크립트 0ms·그리기 16번, 스크롤 무대(#191 직전)는 215칸 동안 스크립트 14.8ms·`requestAnimationFrame` 215번·
  레이아웃 4번·그리기 357번. 결과 `D:\Tools\_scratch\site-loop\perf\`. 헤드리스라 GPU 래스터 비용은 추적에 다 잡히지 않는다.
- 사이트 1차 수정 요청(2026-11-08, `chromite/site-round1`): 리허설 컨테이너가 없는 상태에서 처음부터 실행(`rehearse.ps1 -Tag v0.1.2`, 이어서 `-Tag v0.1.3`, `-DataGeneration 2`, GitHub 릴리스에서 받은 실제 파일) 둘 다 `ALL CHECKS OK`, 뒤에 `-Down`.
  리허설 서버(실제 CSP)에서 헤드리스 Edge(CDP, 묶음마다 새 프로필)로 한국어·영어 × 밝은·어두운 × 1440×900·375×812 홈 8가지, 움직임 줄이기 2가지, JavaScript 끔 2가지, 변경 내역·개인정보 × 두 테마 8쪽,
  `/`(JavaScript 끔, 두 테마 × 두 폭): 가로 스크롤 없음, 깨진 이미지 없음, CSP 위반·4xx 없음, 다운로드 상자 `ready`(JavaScript 끔은 `static`).
  머리글 그림이 테마에 맞게 `wordmark-light.svg`/`wordmark-dark.svg`(138.8×16), 첫 화면 로고 `logo-dark.svg` 560×123(1440)·260×57(375), `/`는 `logo-light.svg`/`logo-dark.svg` 320×70, `h1` 이름 "Chromite".
  모든 쪽에서 화면 글과 HTML에 "SHA" 없음(`curl`로 받은 7쪽도 0개), 복사 단추 0개, 홈의 `.sects` 0개, `og:image`·`twitter:image`가 `og-<언어>-3.png`, `og-ko-3.png`·`og-en-3.png` 200 `image/png`
  (139,226·149,495 B), 예전 `og-ko-2.png`는 404, `brand/*.svg` 200 `image/svg+xml`. 콘솔에는 정보 수준 지연 로드 알림과, 테마를 바꿔 가며 같은 브라우저로 읽을 때 `<picture>`의 다른 테마 그림 요청이
  취소된 `net::ERR_ABORTED` 3건(차단·오류 응답 아님, 보인 그림은 맞는 테마)만 있었다. 머리글과 첫 화면은 장치 배율 2로 찍어 선명한지 봤다(SVG).
  Agent 장면 반복 재생: 두 단계의 멈춤 가운데로 애니메이션을 옮기면 한국어·영어 × 1440·375px 모두 문장 불투명도 `1,0.45,0.45,0.45`, 전체 화면일 때 `1,1,1,1`, 확대는 예전과 같은 1.6·1.4배.
  공유 이미지 두 장은 1200×630으로 직접 봄(영어 작은 줄이 글 칸을 넘지 않게 "Windows, macOS"로 줄임). 같은 브라우저로 쪽을 많이 읽으면 화면 캡처가 검게 나오는 일이 있어(시험 도구 쪽 현상) 묶음마다 브라우저를 새로 띄웠다.
  화면 사진: `D:\Tools\_scratch\chromite-site-round1\shots\`(머리글 `*-header@2x.png`, 첫 화면 `*-hero@2x.png`, 장면 `*-scene-<id>.png`, 다운로드 `*-download.png`), Agent 단계 `D:\Tools\_scratch\chromite-site-round1\agent\`.
- 사이트 2차 수정 요청과 지원 범위 페이지(2026-11-08, `chromite/site-round2`): 리허설을 `-Down` 상태에서 처음부터 실행(`rehearse.ps1 -Tag v0.1.2`, 이어서 `-Tag v0.1.3`, 둘 다 `-DataGeneration 2`,
  GitHub 릴리스에서 받은 실제 파일) 둘 다 `ALL CHECKS OK`. 새 확인 항목 `/ko/support.html`·`/en/support.html`·`/assets/support.js` 200 포함. 뒤에 `-Down`.
  리허설 서버(실제 CSP)에서 헤드리스 Edge(CDP, 경우마다 새 브라우저와 새 프로필, OS 입력 없음)로 홈·지원 범위 × 한국어·영어 × 밝은·어두운 × 1440×900·375×812 16가지, JavaScript 끔 6가지
  (홈 375 두 언어, 지원 범위 375·1440 두 언어), 변경 내역·개인정보 × 두 언어 × 두 테마 8쪽, 모두 30가지:
  - 모든 쪽 가로 스크롤 없음(`scrollWidth` = 폭), 깨진 이미지 없음, 콘솔·CSP 오류와 4xx 없음(정보 수준 지연 로드 알림만). 모든 쪽 머리글에 지원 범위 메뉴, 바닥글에 출처 고지 문장과 `NOTICE` 링크.
  - 첫 화면: 단추와 안내 줄의 가운데가 첫 화면 글 칸의 가운데와 0px 차이(1440·375 모두), 안내 줄이 단추 12px 아래, 로고는 왼쪽 끝 그대로(0px), 단추 글 "무료로 다운로드"/"Download free",
    아이콘 SVG 2개, 모서리 `999px`.
  - 지원 범위 표: 행 110개. 표 상자를 아래로 1500px, 옆으로 900px 스크롤해도 머리 행·첫 열·왼쪽 위 칸이 상자 가장자리에 붙어 있음(테두리 1px 차이, 1440 상자 1114×802·표 2416×9330~9956, 375 상자 341×607).
    찾기: "postgres" → 17행(묶음 2개만 보임), 표시 "17 / 110개 표시"/"Showing 17 of 110", "zzzz" → 0행과 "찾는 데이터베이스가 없습니다.", "db2" → Db2 3행, 지우면 110행.
    JavaScript 끔: 찾기 칸이 숨고 110행이 모두 보임(스크롤 상자와 sticky는 CSS라 같음).
  - 홈의 DB 이름: 리허설에서 받은 홈·개인정보 4쪽과 `/`에 SQL Server, Oracle, PostgreSQL, MySQL, MariaDB, SQLite, Altibase, Derby, DuckDB, PL/pgSQL, pg_cron, DBMS_SCHEDULER, Db2, Redshift, Azure 등이 0개.
    "DBeaver"는 바닥글 출처 고지의 4번뿐(`/`에는 바닥글이 없어 0). 변경 내역(지난 기록)과 지원 범위 페이지에만 DB 이름이 있다.
  - Pretendard: 모든 쪽과 공유 이미지 생성기의 ASCII 밖 글자 448자가 Regular·SemiBold 부분 집합에 모두 있음(.notdef 그림 비교, `漢`은 없음으로 검출, `가`는 있음으로 검출).
  - `python tools/rename/gate.py` `ALL GATES PASSED`.
  화면 사진: `D:\Tools\_scratch\site-round2\shots\<경우>\`(첫 화면 `*-hero.png`, 장면 `*-scene-<id>.png`, 다운로드 `*-download.png`, 바닥글 `*-footer.png`, 머리글 `*-header.png`,
  지원 범위 `*-top.png`·`*-table-scrolled.png`).

### 설치 범위, macOS 이름, dmg 창 (2026-11-09, `chromite/install-update-2`, 앱 변경 #22·#23·#26)

이 PC(Windows 11, 관리자 계정의 승격하지 않은 셸)에서. 회귀 테스트는 요청되지 않아 작성·실행하지 않았다. UAC는 한 번도 띄우지 않았다(설치 프로그램은
호출한 권한으로 돌고 조용한 실행은 승격을 요청하지 않는다).

- 설치 프로그램 빌드: `build-installer.ps1`로 가짜 제품 폴더(실행 파일 하나, `jvm.dll` 자리, `configuration\config.ini`)에서 두 단계 모두 성공(경고 없음).
  실제 설치는 사용자의 진짜 설치(`%LOCALAPPDATA%\Programs\Chromite`, 시작 메뉴 `Chromite.lnk`)를 건드리지 않도록 `/DINSTANCE=ChromiteTestR1`로 만든 시험 설치 프로그램으로,
  모두 `D:\Tools\_scratch\chromite-install-update-2\` 아래에 했다. 0.1.3 설치 프로그램은 `origin/devel`의 `chromite.nsi`에 같은 시험 이름만 바꿔 넣어 만들었다.

| 경우 | 결과 |
|---|---|
| 스위치 없음, 설치 없음, `/S /D=t1`(관리자 아님) | 종료 코드 2, 폴더·HKCU·HKLM·바로 가기 없음(기본 = 모든 사용자, 조용한 실행은 승격하지 않음) |
| `/AllUsers /S /D=t2`(관리자 아님) | 종료 코드 2, 아무것도 없음 |
| `/CurrentUser /S /D=t3` | 0, 파일, HKCU `0.1.4@t3`, 사용자 시작 메뉴 바로 가기, HKLM 없음 |
| 그 뒤 `/S`(0.1.3 업데이터가 하는 것) | 0, t3에서 제자리 업그레이드(`uninstall.exe` 다시 씀), 기본 폴더 `%LOCALAPPDATA%\Programs\ChromiteTestR1` 안 생김 |
| 그 뒤 `/S /D=t5` | 0, 여전히 t3(두 벌 금지, `t5` 안 생김) |
| 그 뒤 `/AllUsers /S` | 2("이미 나만 사용으로 설치"), 그대로 |
| 그 뒤 `/CurrentUser /S`(0.1.4 업데이터가 하는 것) | 0, t3 |
| `t3\uninstall.exe /S` | 0, HKCU 항목·바로 가기·프로그램 파일 지움(`_?=`로 돌린 `uninstall.exe` 자신만 남음, 전과 같음) |
| 0.1.3 설치 프로그램 `/S /D=t9` → 0.1.4 `/S` | HKCU `0.1.3@t9` → `0.1.4@t9`, `uninstall.exe` 0.1.4, HKLM 없음. 0.1.4 제거 프로그램으로 지움 |

- 설치 폴더에 쓰지 않음(Program Files처럼 읽기·실행만): 빌드한 제품을 시험 폴더에 복사해 상속을 끊고 이 사용자에게 읽기·실행만 준 뒤(`icacls /inheritance:r /grant:r <사용자>:(OI)(CI)RX`)
  `-vm`(JBR 25)·`-data <시험 작업 공간>`·`CHROMITE_DATA`·`APPDATA`·`LOCALAPPDATA`·`user.home`을 모두 시험 폴더로 두고 실행했다. 메인 창까지 떴다(`Startup times … main window 3874`,
  `Updates: NOTIFY (not installed by the setup)`). Equinox는 OSGi 캐시를 `<user.home>\.eclipse\1762393238_win32_win32_x86_64\configuration`에 만들었고,
  실행 전후 설치 폴더 파일 414개의 크기·수정 시각이 모두 같았다.
- 코드: 설치 폴더(`Platform.getInstallLocation()`)를 쓰는 곳은 읽기뿐이다: `DriverLibraryLocal`·`DriverLibraryBundle`(내장 드라이버), `MavenRegistry`(로컬 저장소 찾기),
  `WorkspaceLocationMigration`(옛 작업 공간 복사의 원본), `SystemVariablesResolver`·`GeneralUtils`·`DriverLibrariesPanel`(경로 표시). `BasePlatformImpl.getApplicationConfiguration`은 부르는 곳이 없다.
- macOS(`release-macos.sh`): `bash -n` 통과. dmgbuild 설정(`dmg-settings.py`)과 `package`·`verify`·`dmg-window`는 Mac이 없어 실행하지 못했다(아래).
  배경 그림의 대비는 생성기가 계산했다(검정 4.57:1, 흰색 4.59:1). dmgbuild·ds_store·mac_alias의 버전·SHA-256은 PyPI JSON과 simple 색인 두 곳에서 같은 값을 읽었다.

## 확인하지 못한 것

- **0.1.4의 macOS 파일 전부(#22·#26)**: `release.yml`(태그·`devel` 수동 실행에서만 돎)과 Mac이 필요하다. 첫 실행에서 볼 것: dmgbuild 설치와 dmg 생성,
  서명·공증·staple한 dmg 안 `Chromite.app`과 zip 안 `ChromiteSwing.app` 둘 다의 `codesign --verify --deep --strict`·`spctl`(`verify signed`가 함),
  "Disk image window" 산출물의 창 사진(밝은 Finder만, 러너는 밝은 모드)과 `.DS_Store` 내용(`Iloc` 앱 (240, 150)·Applications (240, 430), `icvp`의 `iconSize` 144, `bwsp` 창 크기).
  어두운 Finder의 이름 글자는 대비 계산으로만 확인했다. dmgbuild가 앱을 복사할 때 서명이 유지되는지는 `verify`가 dmg 안의 앱으로 확인한다.
- 모든 사용자 범위의 실제 설치(Program Files, HKLM, 모든 사용자 바로 가기, 승격된 마침 화면의 탐색기 실행, 제거 프로그램의 `runas` 다시 실행): UAC가 필요해 하지 않았다.
  `release.yml`의 Windows 작업(러너는 관리자)이 `/AllUsers`로 설치·업그레이드·제거를 실제로 한다.
- 대화형 설치 범위 화면(라디오 단추, UAC 방패, 거부 문구): 이 PC 화면에 설치 프로그램 창을 띄우지 않아 보지 못했다.
- GitHub Actions에서 `release.yml` 실행(태그·push 없음): Azure 서명 단계에서 `uninstall.exe`·`setup.exe` 서명, 서명된 파일의 정확한 서명자 주체.
  주체 문자열은 Turbrix `docs/SIGNING.md`(같은 Azure 계정의 게시자)에서 가져왔다. 다르면 확인 단계가 실패한다(의도한 동작). 첫 실행에서 확인한다.
- 서명된 설치 프로그램의 SmartScreen 동작, 대화형 설치 화면의 나머지 단계(구성 요소·마침), 바탕 화면 바로 가기 유지(바탕 화면이 OneDrive 폴더라 시험하지 않음).
- `%LOCALAPPDATA%\Programs\Chromite` 기본 위치 설치(시험은 `/D=`로 D: 폴더에만).
- macOS zip(업데이트 파일)을 Mac에서 풀어 확인하는 것. dmg는 아래처럼 확인했다.
- **macOS dmg 설치·실행(2026-11-08, `macos-smoke.yml`, [ci.md](ci.md) "macOS 설치·실행 시험")**: v0.1.1 초안의 `Chromite-0.1.1-arm64.dmg`를 `macos-15` 러너에서 격리 속성을 붙여
  설치했다. `codesign --verify --deep --strict`, `spctl`(앱 execute·dmg open, `source=Notarized Developer ID`), `stapler validate`(앱·dmg), `syspolicy_check distribution`은 통과했고
  첫 실행 때 Gatekeeper가 "Apple checked it for malicious software and none was detected" 질문을 보였다. **앱은 창 없이 멈춘다**(제품 결함: 런처가 JVM을 AppKit
  메인 스레드에서 돌려 AWT와 교착, ci.md의 원인 분석). 그래서 v0.1.1의 macOS 파일은 받아도 쓸 수 없다(공개·배포 여부는 사용자가 정한다).
  앱 메뉴 이름이 "Chromite-swing"(`Info.plist`의 `CFBundleName`)으로 보이는 것도 확인했다(크래시 대화상자 문구).
  **수정(2026-11-08, [platform-startup.md](platform-startup.md) "macOS 메인 스레드"·"macOS Info.plist")**: 다음 릴리스부터 앱이 시작하고 이름이 Chromite다.
  서명 없는 빌드로는 시작·앱 메뉴·끝내기·키체인을 확인했다. 서명·공증한 dmg에서의 시작(격리, App Translocation, 강화된 런타임)은 다음 릴리스 뒤 `macos-smoke.yml`로 확인한다.
  v0.1.1 macOS 파일은 이 수정이 없으므로 그대로는 쓸 수 없다.
- 실제 서버 배포(사용자 승인 전이라 하지 않음).
- 공유 미리 보기를 실제 서비스(메신저, SNS 카드 검사기)에서 본 것: `og:image`가 실제 주소(https://chromite.seizia.com/…)라 배포 뒤에만 볼 수 있다.

## 사용자가 해야 할 일

- [x] 사용자 데이터 위치 결정(위 "사용자 데이터의 위치"): `%APPDATA%\ChromiteData\workspace6`로 고정하고 기존 작업 공간은 첫 시작 때 복사(2026-11-07).
- [ ] 첫 태그: 이 작업이 `devel`에 들어간 뒤 [versioning.md](versioning.md)의 순서로 `v<버전>` 태그(이미 커밋된 0.1.0이면 `v0.1.0`).
- [ ] 첫 실제 배포 승인. 서버 접속(호스트, 사용자, 키, known_hosts)은 Turbrix 세션 또는 사용자가 준다. 배포 전 리허설.

## upstream 대비 변경점

DBeaver는 이 저장소에 설치 프로그램·배포 설정이 없다(별도 빌드 저장소, dbeaver.io). 모두 Chromite에서 새로 만들었다.

| 파일 | 변경 |
|---|---|
| `tools/installer/`, `tools/release/releases.sh`, `deploy-chromite-site.ps1`, `deploy-remote.sh`, `tools/release/rehearsal/`, `site/` | 추가 |
| `.github/workflows/release.yml` | NSIS 설치 프로그램 빌드·서명·확인(두 설치 범위), macOS 파일 이름, dmgbuild 설치와 dmg 창 사진 |
| `tools/release/dmg/` | dmg 창 설정·배경 그림·생성기·dmgbuild 고정(추가) |
| `.gitattributes`, `.gitignore` | `tools/release`의 `.sh`는 LF, `.local/` 무시 |
| `NOTICE` | 사이트의 Pretendard 웹 글꼴, Octicons GitHub 마크(MIT) |

- 26.0.5 릴리스(2026-11-12, 태그 `v26.0.5` = `412780207b`, #245~#248 포함):
  - 태그 전 `release.yml` 수동 실행 두 번(`2ade8c6996`: 옛 이름 시작 파일 서명 단계가 처음 도는 것 확인, `73448a993a`: 버전 26.0.5와 macOS zip `Chromite.app`) 모두 성공.
  - 태그 실행(43171860410): 모든 작업 성공, 초안 Release에 파일 4개와 `SHA256SUMS`, 내려받아 `sha256sum -c` 4개 OK.
  - 리허설(위 "다른 Linux 호스트에서 리허설", 시험용 DB VM): 실제 0.1.4 릴리스를 넣은 서버 사본에 수동 실행 파일로 한 번, 처음 상태로 되돌려 태그의 파일로 한 번,
    둘 다 `ALL CHECKS OK: 26.0.5 is live`(OK 35건, "REHEARSAL, NOT CHECKED" 없음, `setups listed: 26.0.5, 0.1.4`). 끝난 뒤 컨테이너·볼륨·이미지 삭제.
  - 운영 배포(Turbrix 세션이 같은 태그의 스크립트와 같은 파일로 실행, `-DataGeneration 2`): `ALL CHECKS OK: 26.0.5 is live at https://chromite.seizia.com`,
    옮겨 둔 이전 파일 `/srv/chromite/old/20261112T071941Z`(0.1.3 파일 3개 포함).
  - 배포 뒤 확인: 운영 `releases.json`의 26.0.5 세 파일의 크기·SHA-256이 `SHA256SUMS`와 같음, 세 파일 200과 같은 크기, `/`, `/ko/`, `/en/`, 변경 내역 두 쪽 200, `#v26.0.5` 있음.
  - 0.1.4에서 보이는지: v0.1.4 릴리스의 포터블 zip을 풀어 임시 `-data`, `-configuration`, `CHROMITE_DATA`로 실행하니 약 50초 안에 제목 줄에 "새 버전 26.0.5"가 떴다
    (알림 모드: 설치 프로그램으로 설치한 것이 아님. 내려받기·설치는 실행하지 않음). 설치본의 자동 업데이트(내려받기, 설치 프로그램 실행, 옛 이름 시작 파일로 다시 시작)는
    이 PC의 실제 설치를 건드리지 않으려고 실행하지 않았다: #241에서 격리 설치로 확인한 경로이고, 서명된 실물로는 사용자의 PC가 첫 확인이다.
  - 확인하지 않은 것: 실제 Mac에서 0.1.4 → 26.0.5 업데이트(코드로만 확인). 회귀 테스트는 실행하지 않았다.
- 26.0.6 릴리스(2026-11-13, 태그 `v26.0.6` = `91063ef088`, #249~#261 포함):
  - 태그 전 `release.yml` 수동 실행(43311889413, `91063ef088`): Windows 서명·검증, macOS 서명·공증·검증 모두 성공.
  - 태그 실행(43315202504): 모든 작업 성공, 초안 Release에 파일 4개와 `SHA256SUMS`, 내려받아 `sha256sum -c` 4개 OK.
  - 리허설(위 "다른 Linux 호스트에서 리허설", 시험용 DB VM): 서버 사본에 실제 0.1.4 릴리스(#247 전 커밋의 스크립트), 실제 26.0.5 릴리스(태그 `v26.0.5`의 스크립트)를
    차례로 넣어 운영과 같은 상태로 만들고(`releases.json`이 운영 것과 `generated_at`만 다름), 태그 `v26.0.6`의 스크립트와 파일로 실행:
    `ALL CHECKS OK: 26.0.6 is live`(OK 35건, 실패 없음). 0.1.4 파일 3개는 `old/<stamp>/downloads/`로 옮겨짐(최신 두 버전만 남김). 끝난 뒤 컨테이너·볼륨·이미지 삭제.
  - 운영 배포(Turbrix 세션이 같은 태그의 스크립트와 같은 파일로 실행, `-DataGeneration 2`): `ALL CHECKS OK: 26.0.6 is live at https://chromite.seizia.com`,
    옮겨 둔 이전 파일 `/srv/chromite/old/20261112T221227Z`(0.1.4 파일 3개 포함). `setups listed: 26.0.6, 26.0.5`.
  - 배포 뒤 확인: 운영 `releases.json`의 26.0.6 세 파일의 크기·SHA-256이 `SHA256SUMS`와 같음, 설치 파일과 dmg 200과 같은 크기, `/`, `/ko/`, `/en/`, 변경 내역 두 쪽 200, `#v26.0.6` 있음.
    0.1.4 설치 파일은 운영에서 내려감(404). 0.1.4 설치본은 피드의 최신 버전(26.0.6)으로 바로 업데이트한다.
  - 26.0.5에서 보이는지: v26.0.5 릴리스의 포터블 zip을 풀어 임시 `-data`, `-configuration`, `CHROMITE_DATA`로 실행하니 약 55초 안에 제목 줄에 "새 버전 26.0.6"이 떴다
    (알림 모드. 내려받기·설치는 실행하지 않음).
  - 확인하지 않은 것: 설치본의 26.0.5 → 26.0.6, 0.1.4 → 26.0.6 자동 업데이트(이 PC의 실제 설치를 건드리지 않으려고 실행하지 않음), 실제 Mac. 회귀 테스트는 실행하지 않았다.
- 26.0.7 릴리스(2026-11-13, 태그 `v26.0.7` = `2634415455`, #263~#269 포함. PostgreSQL 연결 새로 고침의 세션 손실 #266이 26.0.6에도 있어 당일에 냄):
  - 태그 전 `release.yml` 수동 실행(43388741011, `2634415455`): Windows 서명·검증, macOS 서명·공증·검증 모두 성공.
  - 태그 실행(43390994938): 모든 작업 성공, 초안 Release에 파일 4개와 `SHA256SUMS`, 내려받아 `sha256sum -c` 4개 OK.
  - 리허설(위 "다른 Linux 호스트에서 리허설", 시험용 DB VM): 서버 사본에 실제 0.1.4(#247 전 커밋의 스크립트), 26.0.5, 26.0.6 릴리스(각 태그의 스크립트)를 차례로 넣어
    운영과 같은 상태로 만들고(`releases.json`의 파일 목록·크기·해시가 운영과 같음), 태그 `v26.0.7`의 스크립트와 파일로 실행: `ALL CHECKS OK: 26.0.7 is live`(OK 35건, 실패 없음).
    26.0.5 파일 3개는 `old/<stamp>/downloads/`로 옮겨짐. 끝난 뒤 컨테이너·볼륨·이미지 삭제.
  - 운영 배포(Turbrix 세션이 같은 태그의 스크립트와 같은 파일로 실행, `-DataGeneration 2`): `ALL CHECKS OK: 26.0.7 is live at https://chromite.seizia.com`,
    옮겨 둔 이전 파일 `/srv/chromite/old/20261113T082903Z`(26.0.5 파일 3개 포함). `setups listed: 26.0.7, 26.0.6`.
  - 배포 뒤 확인: 운영 `releases.json`의 26.0.7 세 파일의 크기·SHA-256이 `SHA256SUMS`와 같음, 설치 파일·dmg·zip 200과 같은 크기, `/`, `/ko/`, `/en/`, 변경 내역 두 쪽 200, `#v26.0.7` 있음.
    26.0.5 설치 파일은 운영에서 내려감(404).
  - 26.0.6에서 보이는지: v26.0.6 릴리스의 포터블 zip을 풀어 임시 `-data`, `-configuration`, `CHROMITE_DATA`로 실행하니 약 55초 안에 제목 줄에 "새 버전 26.0.7"이 떴다
    (알림 모드. 내려받기·설치는 실행하지 않음).
  - 확인하지 않은 것: 설치본의 26.0.6 → 26.0.7 자동 업데이트(이 PC의 실제 설치를 건드리지 않으려고 실행하지 않음), 실제 Mac. 회귀 테스트는 실행하지 않았다.

## 변경 이력

- 2026-11-07 최초 작성. NSIS 설치 프로그램(사용자별, 제자리 업그레이드·되돌리기, 2단계 서명), `release.yml` 연결, 피드 생성기(`releases.json`, `releases.js`),
  배포 스크립트와 로컬 Docker 리허설, 다운로드 사이트. Swing 제품의 작업 공간이 `%APPDATA%\ChromiteData`가 아니라 현재 폴더 아래 `workspace\`임을 확인해 기록.
- 2026-11-07 작업 공간 위치 수정 반영: "사용자 데이터의 위치"를 고친 뒤 기준으로 바꾸고 사용자 할 일 완료 표시([workspace-location.md](workspace-location.md)).
- 2026-11-07 다운로드 사이트 개편("사이트" > "디자인"): 앱 토큰의 도구 창·속성 격자 모양, 실제 앱 화면(한국어·영어 × 밝은·어두운 WebP 4장),
  "들어 있는 것", 오픈 소스 절과 모든 페이지의 GitHub 마크 링크(Octicons, MIT, `NOTICE`), 왼쪽 제목·오른쪽 내용 배치. `site.js`·`lang.js`·피드 계약은 그대로.
- 2026-11-07 방문자 운영체제에 맞춘 다운로드("사이트" > "방문자 운영체제에 맞춘 다운로드"): Windows·macOS 방문자에게 그 카드만, 다른 운영체제는 단추로 펼침.
  JavaScript 없을 때의 첫 상태를 `loading`에서 `static`(두 카드와 파일 위치 안내)으로 바꿈.
- 2026-11-07 개인정보 페이지에 "AI 도우미" 절 추가(사용자 결정, 배포 전). 문장마다 코드로 확인했다: 제공자 3개와 설정 전 요청 없음(`AIAgentProviders.PROVIDERS`,
  `createEngine`의 `isValidConfiguration` 검사, 요청은 채팅 실행과 설정의 Test에서만), 보내는 것(`AIAgentChatRunner.buildSystemPrompt`·`toModelMessages`, 도구 결과
  한도 `AgentLimits`), 기본 권한(`AIToolPolicy.resolve`), 로컬 MCP 서버(`AIMcpServer` 루프백 바인딩·403·Bearer, `AIMcpSettings` 기본 꺼짐).
  API 키 저장은 문서·화면 문구("보안 저장소")와 달리 `LocalSecretController`(암호화 없음)를 거쳐 작업 공간의 `com.seizia.chromite.ui.swing.prefs`에
  평문으로 저장됨을 코드와 시험 작업 공간 파일로 확인해 그대로 적었다(앱 쪽 수정은 별도 작업으로 보고).
- 2026-11-07 개인정보 페이지(`site/ko|en/privacy.html`)의 "AI 도우미"/"AI assistant" 절을 "Chromite Agent"로 바꿈(절 이름, 옵션 탭 이름, 본문에서
  기능을 가리키는 말). 다른 AI 도구(MCP로 붙는 외부 도구)를 말하는 문장은 그대로. 사용자 결정([ai-agent.md](ai-agent.md) "명칭"). 배포하지 않음.
- 2026-11-07 홈에 기능 둘러보기 추가("사이트" > "기능 둘러보기"): 한 화면에 특장점 하나씩 8개, 실제 앱 화면 28장(WebP), `assets/tour.js`(나타나기·현재 화면 표시),
  CSS 스크롤 스냅(`proximity`). 예전 "오픈 소스" 절은 마지막 화면으로 옮김(`#open-source` 그대로). 배포하지 않음.
- 2026-11-07 파비콘과 공유 이미지 추가("사이트" > "파비콘과 공유 이미지"): `favicon.ico`, 아이콘 PNG 3장, 언어별 OG 이미지, 모든 페이지의 canonical·OG·Twitter 태그,
  생성기 `tools/branding/gen_site_images.py`. CSP는 그대로(매니페스트는 CSP가 막아 만들지 않음). 배포하지 않음.
- 2026-11-07 MySQL 드라이버를 내장하지 않게 된 것(#123, [drivers.md](drivers.md))에 맞춰 홈의 "들어 있는 것"과 기능 둘러보기 "폐쇄망" 화면의 문구를 고침:
  내장 드라이버는 SQL Server, Oracle, PostgreSQL, MariaDB, SQLite이고 MySQL은 처음 연결할 때 받거나 오프라인 묶음으로 설치한다. 지원 DB 목록(제목·설명)의 MySQL은 그대로.
- 2026-11-08 홈을 스크롤 무대로 바꿈("사이트" > "스크롤 무대", 사용자 요청 "애플 제품 페이지 같은" 스크롤 연동 움직임): 어두운 무대에 첫 화면과 특장점 8장면,
  `assets/stage.js`(진행도에 묶인 `transform`·`opacity`, `data-t` 시간표), 다운로드 블록은 무대 뒤 `#download`(첫 화면의 다운로드 단추가 바로 감).
  기능 둘러보기(`assets/tour.js`, 스크롤 스냅, 목록, `.tour`·`.feature*`·`.shot` CSS)와 밝은 테마 기능 화면 14장을 지움. `site.js`·`lang.js`·피드 계약과 앵커는 그대로. 배포하지 않음.
- 2026-11-08 사이트가 네다섯 DB만 다루는 것처럼 읽히던 문구를 고침("사이트" > "데이터베이스 지원 문구", 사용자 의견): 첫 화면 문장, 홈의 설명 태그 3개, 공유 이미지 2장(생성기 `OG_COPY`)과
  모든 페이지의 그림 설명, 서버 관리 장면 제목·설명과 Altibase 줄, Chromite Agent 장면 설명, 폐쇄망 장면 첫 줄, 다운로드 블록 "데이터베이스" 줄. 기준은 드라이버 정의 144개("100가지가 넘는 드라이버").
  아이콘은 다시 만들어도 같은 바이트라 바뀌지 않음. 배포하지 않음.
- 2026-11-08 "확인하지 못한 것"의 macOS 항목을 `macos-smoke.yml` 결과로 바꿈: v0.1.1 dmg 서명·공증·Gatekeeper 통과, 앱은 시작하지 못함(제품 결함, [ci.md](ci.md)).
- 2026-11-08 macOS 시작 교착과 `Info.plist` 이름 수정을 "확인하지 못한 것"의 macOS 항목에 기록(서명한 dmg 확인은 다음 릴리스 뒤).
- 2026-11-08 홈(ko·en)의 그림 아래 작은 설명(`figcaption`) 7개씩과 CSS `.scene-media figcaption` 규칙 두 개를 지움(사용자 요청). 디버거 장면 설명에 있던 서버 조건(pldebugger, Oracle 디버그 권한, SQL Server 미지원)과 Chromite Agent 화면의 답이 시험용 제공자 글이라는 안내도 함께 빠짐.
- 2026-11-08 서버 `incoming/`에 배포마다 전체 묶음(약 0.5 GB)이 쌓여 2 GB가 되어, `deploy-remote.sh` 5단계에서 가장 최근 2개만 남기고 지우게 함(사용자 결정). 리허설 0.1.0→0.1.1→0.1.2 세 번째 배포에서 가장 오래된 묶음 하나가 지워지고 `ALL CHECKS OK`.
- 2026-11-08 홈 무대를 스크롤 연동에서 등장형으로 바꿈("사이트" > "홈 무대", 사용자: 버벅임): 보통 높이 장면, 장면이 처음 보일 때 한 번 CSS 전환
  (`assets/reveal.js`, `transform`·`opacity`만, 스크롤마다 하는 일 없음), 확대 대신 강조 상자(언어별 % 위치). `assets/stage.js`, `.motion`·`.pin`·`.slot` CSS, `data-t`를 지움.
  첫 화면 제목·부제를 사용자가 고른 문구로, 근거 두 묶음(편하게·안전하게)을 더함. 드라이버 수를 제목·설명·공유 이미지에서 뺌(사용자: "데이터베이스 개수는 특장점이 아니다").
  공유 이미지를 중립 이름의 메인 창 화면으로 다시 찍고 `og-<언어>-2.png`로 이름을 바꿈(메신저 미리 보기 캐시). 배포하지 않음.
- 2026-11-08 서버 `old/`에 판마다 옛 설치 파일(약 0.5 GB)이 쌓여, `deploy-remote.sh` 6단계에서 가장 최근 3개 폴더만 남기고 지우게 함(사용자 결정: 권장대로). 옛 설치 파일은 GitHub 릴리스에 남아 있다.
- 2026-11-08 홈 무대의 강조 상자를 화면 사진의 반복 재생으로 바꿈("사이트" > "홈 무대" > "반복 재생", 사용자: "GIF처럼 페이지가 살아 있는 느낌"): 페이지는 정적인 채로,
  장면마다 사진이 스크롤 무대의 확대 목표로 8초(한 곳)·12초(두 곳)마다 확대·멈춤·축소를 되풀이하고 그 곳을 설명하는 문장만 밝게 둠. CSS 키프레임(`transform`·`opacity`)만,
  `reveal.js`는 화면 안 장면에 `is-playing`을 붙이는 관찰자와 탭 숨김 처리만 더함. 강조 상자 마크업·CSS를 지움. 배포하지 않음.
- 2026-11-08 사이트 1차 수정 요청 8가지("사이트" > "1차 수정 요청", `chromite/site-round1`): 머리글 워드마크·첫 화면과 언어 선택 페이지의 가로 로고(`site/assets/brand/`, 생성기 `gen_site_images.py`),
  비밀번호·트랜잭션·폐쇄망 장면 제목과 문구, 서명·업데이트·SHA-256 내용 삭제(절 2개, 카드의 SHA-256 줄과 복사 단추, 관련 CSS·JS), "회사에서도 개인도, 모든 기능 무료"(첫 화면, 장면, 공유 이미지 `og-<언어>-3.png`),
  Chromite Agent 장면 재작성(반복 재생에서 밝게 두는 문장을 1번으로). 배포하지 않음.
- 2026-11-08 사이트 2차 수정 요청 7가지("사이트" > "2차 수정 요청", `chromite/site-round2`): 첫 화면 다운로드 단추만 가운데(알약 모양, "무료로 다운로드", Windows·Apple 아이콘, 아래 안내 줄),
  트랜잭션 문구(자동 롤백), 홈의 DB 이름·수 모두 제거(Agent·서버 관리·폐쇄망·디버거 장면, 다운로드 블록, 그림 설명), 디버거 장면 제목과 디버깅 용어, 무료 장면 라이선스 문장,
  모든 페이지 바닥글의 출처 고지([branding.md](branding.md) "사이트의 출처 고지"). 새 쪽 "기능과 지원 범위"(`support.html`, 110행 × 17열, "기능과 지원 범위 페이지")와 `assets/support.js`,
  머리글 메뉴·바닥글·관련 장면의 링크. 배포 스크립트의 실제 사이트 확인에 새 쪽 두 개와 `support.js`를 더함. 배포하지 않음.
- 2026-11-08 첫 화면 다운로드 단추에 방문자 운영체제의 마크만 보임(사용자 요청, "다운로드 자리"). 알 수 없는 운영체제와 스크립트 없는 페이지는 두 마크 그대로.
- 2026-11-08 첫 화면 다운로드 단추가 방문자 운영체제의 최신 파일을 바로 내려받음(사용자 요청, "다운로드 자리"). 알 수 없으면 다운로드 블록으로.
- 2026-11-09 앱 변경 #22·#23·#26(`chromite/install-update-2`): macOS 앱 이름 `Chromite.app`과 0.1.4만의 옛 이름 zip("macOS 앱 이름", 릴리스 체크리스트의 0.1.5 항목),
  Windows 설치 범위 "기본 Program Files + 선택"("설치 범위", 이전의 "사용자별 설치" 절과 그 이유는 그 절 끝의 "이전 결정"으로), dmgbuild로 만든 dmg 창("macOS 디스크 이미지 창"),
  `release.yml`의 두 범위 설치 확인과 dmg 창 사진, 배포 스크립트의 zip 묶음 이름.
- 2026-11-12 앱 변경 #65(`chromite/launcher-rename`): 런처 이름 `Chromite.exe`(전 `chromite-swing.exe`). 설치 프로그램이 한 버전 동안 옛 이름 시작 파일을 두고 옛 런처 파일을 지움,
  바로 가기·앱 목록 아이콘이 `Chromite.exe`, 배포 스크립트의 macOS 실행 파일 확인 `Contents/MacOS/Chromite`. 설계와 확인은 [launcher-name.md](launcher-name.md).
- 2026-11-12 앱 변경 #63(`chromite/release-26-0-5`): 버전 체계 `YY.seq.dev`, 0.1.4 다음은 26.0.5. 릴리스 체크리스트의 "0.1.5" 항목(macOS zip 옛 이름 `ChromiteSwing.app` 없앰,
  배포 스크립트의 옛 이름 허용 삭제)과 런처 이름 항목(릴리스 노트)을 26.0.5에서 함. "macOS 앱 이름"에 0.1.4·0.1.3 업데이터가 26.0.5 zip을 받는지(코드 근거)를 적음.
- 2026-11-12 26.0.5 릴리스 기록: "확인한 것"에 태그·리허설·운영 배포·배포 뒤 확인을 적고, "리허설"에 이 PC의 Docker 없이 다른 Linux 호스트에서 하는 방법을 더함.
- 2026-11-12 앱 변경 #96(`chromite/exit-leaves-process`): [다시 시도] 뒤에도 실행 중이면 창 없이 남은 프로세스를 찾는 방법을 보이는 두 번째 문구(`LangRunningStill`, "업그레이드(제자리)와 되돌리기" 1). [exit-process.md](exit-process.md).
- 2026-11-12 26.0.6 준비(`chromite/release-26-0-6`): 버전 26.0.6과 변경 내역. 옛 이름 시작 파일은 26.0.6에서 지우지 않음(0.1.4 설치본이 남아 있음), 체크리스트 항목을 "0.1.4 설치본이 남아 있지 않을 때"로 고침.
- 2026-11-13 26.0.6 릴리스 기록: 26.0.5 기록 아래에 태그·리허설·운영 배포·배포 뒤 확인을 적음.
- 2026-11-13 26.0.7 준비(`chromite/release-26-0-7`): 버전 26.0.7과 변경 내역(#263~#268). PostgreSQL 연결 새로 고침의 세션 손실(#266)이 26.0.6에도 있어 당일 수정판으로 냄.
- 2026-11-13 26.0.7 릴리스 기록: 26.0.6 기록 아래에 태그·리허설·운영 배포·배포 뒤 확인을 적음.
