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
| Windows 설치 프로그램 | NSIS `setup.exe` (Turbrix와 같음) |
| 버전 | `major.minor.dev`, 태그 `v<버전>` ([versioning.md](versioning.md)) |
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
| `Chromite-<v>-arm64.zip` | macOS 업데이트 파일: 서명·공증·staple한 `ChromiteSwing.app`을 `ditto`로 묶은 zip |
| `Chromite-<v>-arm64.dmg` | macOS 디스크 이미지(서명·공증·staple). 다운로드 페이지용 |

GitHub Release에는 이 셋과 Windows 포터블 zip(`chromite-swing-<v>-windows-x64.zip`, 이름 그대로), `SHA256SUMS`가 올라간다. 포터블 zip은 서버에 올리지 않는다.

### Windows `setup.exe`

- Azure Artifact Signing 서명. 서명자 주체는 정확히 `CN=Seizia, O=Seizia, L=Cheongju-si, S=North Chungcheong, C=KR`([signing.md](signing.md) "게시자"), 타임스탬프 있음.
- 버전 리소스 `ProductVersion` = `FileVersion` = `<v>`(예: `0.1.1`). 고정 버전 필드(`VIProductVersion`)는 `<v>.0`.
- 조용한 실행 `/S`: 질문 없이 설치된 앱을 같은 폴더에서 바꾼다. 설치 폴더는 레지스트리(`HKCU\...\Uninstall\Chromite` `InstallLocation`)에서 읽는다.
  `/D=<폴더>`(맨 끝, 따옴표 없이)로 바꿀 수 있다. **조용한 실행은 Chromite를 시작하지 않는다.** 업데이트 기능이 종료 코드 0을 본 뒤 시작한다.
- 종료 코드: `0` 성공, `1` 사용자가 취소(대화형), `2` 실패. 실패에는 "Chromite가 60초 넘게 실행 중", "비어 있지 않은 다른 프로그램 폴더"가 있다. 실패하면 아무것도 바꾸지 않는다.
- 낮은 버전의 설치 프로그램도 같은 방식으로 덮어 설치한다(되돌리기).

### 업데이트 데이터 세대 `data_generation`

릴리스가 저장 데이터 형식을 바꿀 때 올리는 정수(1부터). 앱은 세대가 다른 이전 버전으로 되돌리기 전에 경고할 수 있다.
배포 스크립트의 `-DataGeneration`으로 버전마다 한 번 정하고, 서버의 `releases-meta.tsv`에 남는다. 한 번 정한 값은 바꾸지 않는다.

## 구조

| 파일 | 하는 일 |
|---|---|
| `tools/installer/chromite.nsi` | NSIS 설치 프로그램 스크립트(설치·업그레이드·제거) |
| `tools/installer/build-installer.ps1` | 설치 프로그램 빌드(2단계: 제거 프로그램 → 설치 프로그램). CI와 로컬이 같이 쓴다 |
| `.github/workflows/release.yml` | Windows 작업: NSIS 설치(고정), 2단계 빌드와 서명, 확인, 조용한 설치·제거 확인. macOS 작업: 계약 이름으로 zip·dmg |
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

### 사용자별 설치 (관리자 권한 없음)

- 위치 `%LOCALAPPDATA%\Programs\Chromite`, 레지스트리 `HKCU`, 시작 메뉴 바로 가기는 사용자 것, `RequestExecutionLevel user`.
- 이유: 업데이트 기능이 Chromite가 끝난 뒤 `setup.exe /S`를 실행한다. 컴퓨터 전체 설치(`Program Files`)면 업데이트마다 UAC 창이 뜨고,
  사용자가 자리에 없으면 업데이트가 멈춘다. 사용자별 설치는 승격 없이 바뀐다. Turbrix도 같다(`perMachine: false`).
- 회사 PC에서 `%LOCALAPPDATA%` 실행을 막는 정책이 있으면 설치 폴더를 고르면 된다(설치 화면, 또는 `/D=`).

### 화면

설치 폴더 → 구성 요소(Chromite 필수, 바탕 화면 바로 가기 선택, 기본 꺼짐) → 설치 → 마침("Chromite 시작" 선택).
Windows 언어가 한국어면 한국어, 아니면 영어(`MUI_LANGUAGE` 순서: 영어가 기본). 제거는 확인 → 제거.
이전에 바탕 화면 바로 가기가 있었으면 업그레이드에서도(조용한 실행 포함) 다시 만든다.

### 업그레이드(제자리)와 되돌리기

1. 이 폴더의 Chromite가 실행 중인지 본다: `jre\bin\server\jvm.dll`(두 런처 모두 이 파일을 불러온다)을 쓰기로 열 수 없으면 실행 중.
   대화형은 [다시 시도]/[취소], 조용한 실행은 1초 간격 60번 기다린 뒤 종료 코드 2.
2. 설치된 `uninstall.exe /S /UPGRADE`를 실행한다: 그 버전이 아는 프로그램 파일만 지운다(바로 가기, 앱 목록 항목은 둔다).
   그래서 버전 사이에 파일 목록이 바뀌어도 이전 파일이 남지 않는다. OSGi 캐시(`configuration\`)도 지워져 Equinox가 이전 번들 상태로 시작하지 않는다.
3. 새 버전이 아는 항목도 한 번 더 지운다(이전 제거 프로그램이 없거나 실패한 경우).
4. 새 파일을 복사하고 `uninstall.exe`, 시작 메뉴 바로 가기, 앱 목록 항목(이름, 버전, 게시자 Seizia, 크기, 아이콘, `https://chromite.seizia.com`)을 쓴다.

- 지우는 것은 제품 폴더의 최상위 항목 이름 목록(`files.nsh`, 빌드 때 만든다: `configuration`, `drivers`, `features`, `jre`, `licenses`, `p2`, `plugins`,
  `artifacts.xml`, `chromite-swing.exe`, `chromite-swing.ini`, `chromite-swingc.exe`)뿐이다. `$INSTDIR`을 통째로 지우지 않는다.
  그래서 설치 폴더 안의 다른 것(아래 `workspace\`)은 업그레이드·되돌리기·제거에서 남는다.
- 이미 있는 폴더는 비어 있거나 Chromite 설치 폴더(`chromite-swing.exe`나 `uninstall.exe`가 있음)일 때만 받는다. 다른 프로그램 폴더를 골랐을 때 같은 이름의 하위 폴더를 지우지 않기 위해서다.
- 설치 폴더 안의 `chromite-swing.ini`를 사용자가 고쳤다면(예: `-Xmx`) 업그레이드가 새 파일로 바꾼다(DBeaver와 같은 동작).

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

설치 프로그램은 제품 폴더를 그대로 담으므로, 런처(`chromite-swing.exe`)를 서명한 **뒤에** 만든다.
makensis는 260자가 넘는 경로를 열지 못한다(이 PC 작업 트리에서 `p2\...\cache\binary\...`를 못 열어 실패를 확인함. 제품 폴더 아래 가장 긴 파일이 145자,
GitHub 러너의 제품 폴더 경로가 약 120자). 그래서 빌드 스크립트가 작업 폴더에 짧은 이름의 디렉터리 연결(junction)을 만들어 그 경로로 넘기고, 끝나면 연결만 지운다.

### NSIS 버전 고정

`release.yml`이 공식 `nsis-3.12.zip`(SourceForge)을 받아 SHA-256 `56581F90DB321581C5381193D796FFFCF2D24B2F8FED2160A6C6A3BAA67F2C4F`를 확인한 뒤 그 `makensis.exe`를 쓴다.
러너 이미지에 깔린 NSIS는 이미지마다 버전이 바뀌므로 쓰지 않는다. 이 PC에는 같은 zip을 `D:\Tools\nsis-3.12`에 풀어 두었다
(zip의 `makensis.exe`가 이 PC에 따로 설치돼 있던 NSIS 3.12의 것과 바이트 단위로 같음을 확인).

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
   - macOS zip: `ChromiteSwing.app/Contents/MacOS/chromite-swing`, 서명 폴더(`_CodeSignature/CodeResources`), 공증 티켓(`Contents/CodeResources`), `Info.plist`의 `CFBundleShortVersionString` = 버전.
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
| `incoming/<시각>/` | 배포마다 올린 묶음(남긴다) |
| `old/<시각>/` | 배포마다 옆으로 옮긴 파일(남긴다) |

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

## 사이트 `site/`

| 경로 | 내용 |
|---|---|
| `/index.html` | 언어 선택. `assets/lang.js`가 브라우저 첫 언어가 한국어면 `/ko/`, 아니면 `/en/`으로 보낸다(서버는 정적 파일만) |
| `/ko/`, `/en/` | 홈: 어두운 스크롤 무대(첫 화면과 특장점 8장면, 마지막이 오픈 소스: GitHub 저장소, Apache License 2.0, `LICENSE.md`·`NOTICE` 링크, 아래 "스크롤 무대"), 다운로드 블록(`#download`: 제품 소개, 들어 있는 것, Windows 설치 파일, macOS 디스크 이미지: 버전, 크기, 게시일, SHA-256과 복사 단추, 방문자 운영체제 것이 먼저이고 다른 것은 단추로 펼침), 서명·자동 업데이트 안내 |
| `/ko|en/changelog.html` | 변경 내역. 버전마다 `<section id="v<버전>">` (`notes_url`의 앵커) |
| `/ko|en/privacy.html` | 개인정보: 업데이트 확인은 고정 User-Agent만 보낸다, Chromite Agent(설정 전에는 보내지 않음, 무엇을 어느 제공자로 보내는지, API 키 저장 위치, 로컬 MCP 서버), 사이트에 쿠키·분석 없음, 접속 기록 30일 |
| `/favicon.ico` | 16·32·48px 아이콘(아래 "파비콘과 공유 이미지") |
| `/assets/` | `site.css`, `site.js`, `stage.js`(스크롤 무대), `lang.js`, `chromite-icon.svg`(`chromite_logo_pack/chromite_icon_64.svg`), `fonts/`(Pretendard 1.3.9 웹 부분 집합 Regular·SemiBold, `OFL.txt`), `shots/`(앱 화면 WebP 4장: 첫 화면은 `-dark`, `-light`는 공유 이미지 생성기 입력, `shots/tour/` 기능 장면 화면 14장 `-dark`), `icons/`(Octicons 19.33.0 GitHub 마크 16·24, `LICENSE-octicons.txt`, 사이트 아이콘 PNG 3장), `og/`(공유 이미지 2장) |

모든 페이지 머리글에 GitHub 마크와 저장소 링크(`https://github.com/froiibn/chromite`)가 있고, 바닥글에 Apache License 2.0(`LICENSE.md`)과 GitHub 링크가 있다.

### 디자인 (2026-11-07 개편)

사용자 요청: 임시 디자인을 디자인 스킬로 세련되게 바꾸되 AI가 만든 티가 나지 않게, 오픈 소스와 GitHub 연결을 GitHub 아이콘으로 추가.

- 방향: 사이트가 앱의 화면 언어를 그대로 쓴다. 다운로드 상자는 앱의 도구 창(40px 제목 줄, 8px 모서리, 옅은 구분선), 릴리스 정보는 앱 속성 창 같은
  이름 | 값 격자다. 그 아래에 실제 앱 화면이 오므로 사이트와 화면이 한 제품으로 읽힌다. 색은 디자인 토큰([design-spec.md](design-spec.md)) 그대로이고,
  단추·링크 파랑만 글자 대비 4.5:1을 넘게 강조색을 조금 어둡게 했다(흰 글자 / `#3574F0`는 4.3:1, 단추 `#2F6AE6` 4.9:1, 링크 `#2C62D0` 5.6:1, 어두운 테마 링크 `#6F9CF9` 6.1:1).
- 다운로드 블록(2026-11-08까지는 첫 화면, 지금은 스크롤 무대 다음의 `section.get#download`): 왼쪽에 제목·소개와 "들어 있는 것"(지원 DB, 기능, 지원 환경), 오른쪽에 다운로드 상자.
  860px 아래에서는 소개 → 다운로드 → 들어 있는 것 순서로 쌓여(CSS `grid-template-areas`) 휴대폰에서도 내려받기 단추가 먼저 보인다.
- 나머지 절은 모두 "왼쪽 제목, 오른쪽 내용"(`.sect`) 한 가지 배치다. 홈, 변경 내역, 개인정보가 같은 리듬을 쓰고 760px 아래에서는 세로로 쌓인다.
- 피한 것: 그라데이션·빛나는 배경, 이모지 아이콘, 같은 모양 카드 세 장, 과장 문구, 대문자 꼬리표, 가운데 정렬 일색. 문구는 할 수 있는 일을 그대로 적는다.
- 글꼴은 이미 있는 Pretendard 400·600뿐이다(굵기를 늘리면 부분 집합 파일이 더 필요). 위계는 크기와 색으로 만든다. 한국어는 `word-break: keep-all`.
- 움직임은 홈의 스크롤 무대뿐이다(아래 "스크롤 무대", 움직임 줄이기면 없음). 그 밖에는 복사 단추의 "복사됨" 바뀜만. 키보드 초점은 강조색 2px 테두리.
- 앱 화면(`assets/shots/<ko|en>-<light|dark>.webp`, 1899×1188, 무손실 WebP 86~105KB): 테스트 빌드의 **사본**(`D:\Tools\_scratch\site-redesign\app`)을 사본 작업 공간으로
  실행해 창만 그렸다(앱 안에서 창을 그리는 시험 에이전트, 화면 전체가 아님). 한국어 화면은 `주문내역`, 영어 화면(`-nl en`)은 `orders`/`customers` 집계 쿼리.
  보이는 것은 연결 이름, `127.0.0.1` 주소, 시험 DB 이름, 로그인 이름 `sa`뿐이고 비밀번호는 없다. 개체 탐색기는 다른 제품 이름이 든 시험 DB 이름이 보이지 않게 스크롤했다.
  2026-11-08부터 홈은 어두운 무대에 `-dark` 한 장만 쓴다(`-light`는 공유 이미지 생성기가 읽어서 남김). 너비·높이 속성으로 자리를 미리 잡는다.
- GitHub 마크는 Octicons `mark-github`(MIT) 파일을 고치지 않고 두고, CSS `mask`로 글자색을 입힌다(밝은·어두운 테마 모두). 출처는 `NOTICE`.
- 오픈 소스 절에 "Apache License 2.0으로 공개된 다른 오픈 소스 프로젝트에서 출발했다"는 문장과 `NOTICE` 링크를 둔다. 원래 프로젝트의 상표 이름은 쓰지 않는다(CLAUDE.md "브랜딩").
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
  파일 이름·크기·SHA-256은 `releases.json`에 있고 `/downloads/<파일 이름>`으로 받는다는 안내, GitHub 릴리스 링크를 보여 준다.
  `site.js`는 실행되면 언제나 `ready`·`error`·`empty` 중 하나로 바꾼다. `state-loading` 문구와 CSS는 지웠다.

### 스크롤 무대 (2026-11-08)

사용자 요청: "애플 제품 페이지같이 슬라이드를 넘길때마다 변하는 스타일", "메인페이지를 수정대상으로 하고 애플 느낌으로 바꾼다. 움직임은 C"(스크롤에 맞춰 계속 움직이는 방식).
처음에는 첫 화면과 기능 두 개만 시제품으로 만들라고 했다가 "일단 페이지에 반영해봐"로 바뀌어 홈 전체(한국어·영어)를 바꿨다.
2026-11-07의 기능 둘러보기(한 화면씩 스냅, 화면이 보일 때 한 번 나타나기)는 사용자가 말한 방식이 아니어서 이것으로 대신했다.

- 순서: 머리글 → 어두운 무대(`div.stage`, 장면 9개) → 다운로드 블록(`section.get#download`: 제목, 소개, 들어 있는 것, 다운로드 상자) → 서명·자동 업데이트(`.sect`) → 바닥글.
  장면: 첫 화면(이름 "Chromite", 부제, 다운로드 단추), 트랜잭션 보호(`#transactions`), 쿼리 라이브러리(`#query-library`), Chromite Agent(`#chromite-agent`),
  폐쇄망(`#closed-networks`), 프로시저 디버거(`#debugger`), 서버 관리(`#administration`, Altibase 줄 포함), 비밀번호(`#passwords`), 오픈 소스(`#open-source`).
  순서와 문구 출처는 2026-11-07에 승인된 둘러보기이고, 문장을 줄이기만 하고 새 주장은 넣지 않았다. 앵커 id는 그대로다.
- 다운로드 자리: 첫 화면의 "다운로드" 단추가 `#download`(다운로드 블록)로 바로 간다. 무대가 길어서(1440×900에서 약 24화면) 다운로드 블록은 무대 바로 뒤에 둔다.
  다운로드 블록의 내용과 `site.js` 계약은 바꾸지 않았다. 예전 `h1`(지원 DB 문장)은 첫 화면 마지막 문장이 되고(2026-11-08부터 드라이버 수 문장, 아래 "데이터베이스 지원 문구"), 다운로드 블록 제목은 `h2` "Windows와 macOS용 Chromite 받기".
- 장면 구조: `section.scene` 안에 `div.pin`(제목 `.scene-head`, 그림 `.scene-media`, 짧은 문장 목록 `ul.slot`). 넓은 앱 화면은 위에서 아래로(`.scene-wide`),
  세로로 긴 화면·대화상자·저장소 카드는 글 옆에(`.scene-side`, 860px 아래에서는 위에서 아래로).
- 움직임(`assets/stage.js`): 장면이 화면보다 길고(문장 수에 따라 190~370vh) `.pin`이 `position: sticky`로 한 화면 높이에 머문다. 장면 안의 스크롤 위치가 진행도 p(0~1)이고
  모든 움직임이 p의 함수라서 되돌려 스크롤하면 거꾸로 움직이고, 중간에서 새로 고쳐도 그 자리 모습이 바로 나온다.
  - 무엇이 움직이는지는 페이지의 `data-t`에 적는다. 예: `data-t="s 1 1.7 .03 .17; x 0% -56% .03 .17"`(p가 .03→.17일 때 배율 1→1.7, 가로 이동 0→-56%).
    속성은 `o`(불투명도), `x`·`y`(이동, 단위 포함), `s`(배율), `r`(`rotateX`, 도). 언어별 화면 사진의 위치가 달라서 확대 목표를 페이지마다 적는다.
  - 첫 화면: 이름과 단추가 위로 사라지는 동안 앱 창이 기울어진 채(34°, 0.84배) 아래에서 올라와 평평하게 놓이고, 마지막에 지원 문장(드라이버 수)이 나타난다.
  - 기능 장면: 그림은 테두리(`.frame`, `overflow: hidden`) 안에서만 확대·이동한다(앱 창의 가장자리를 넘지 않음). 확대 위치는 원점 0,0 기준 이동으로 적고,
    이동 값을 -(배율-1)~0 사이에 두어 확대 중에도 틀 안이 비지 않는다.
    트랜잭션: 상태 줄의 열린 트랜잭션 칸과 커밋/롤백 메뉴로 확대했다가 돌아온다. 쿼리 라이브러리: Git 루트("팀 쿼리 main ↑1")에 확대된 채 시작해 전체로 물러난다.
    디버거: 14번 줄 중단점 → 변수 탭 → 전체. 서버 관리: 차단 체인 → 전체. 폐쇄망: 체크한 드라이버 줄. 비밀번호: 암호 입력란. Chromite Agent: 창이 조금 올라온다. 오픈 소스: 저장소 카드가 나타난다.
  - 문장(`ul.slot`의 `li`): 짧은 도입(진행도 0.08) 뒤 남은 진행도를 문장 수로 나눠 한 줄씩 바뀌고, 마지막 줄은 남는다. 경계에서는 앞 줄이 먼저 사라지고
    다음 줄이 올라온다. 처음에는 겹쳐 바꿨는데 반투명한 두 줄이 같은 자리에 겹쳐 읽을 수 없었다(리허설 화면 사진으로 확인).
    흐려진 줄은 클릭을 받지 않지만(`pointer-events`) 접근성 트리에는 남아 화면 읽기 프로그램이 모든 줄을 읽는다.
  - `transform`과 `opacity`만 바꾼다. 스크롤마다 `requestAnimationFrame` 한 번, `IntersectionObserver`가 보이는 장면만 갱신한다(시작과 창 크기 바뀜 때는 전부).
  - 속도: 문장 한 줄에 화면 높이의 절반쯤 스크롤한다("보통", 사용자 기본값). CSS `animation-timeline`은 쓰지 않았다(Firefox 미지원, 스크립트 하나로 충분).
- 움직이지 않는 경우: `stage.js`는 `<head>`에서 `defer` 없이 읽혀 첫 그리기 전에 `html.motion`을 붙인다. `IntersectionObserver`가 없거나, 움직임 줄이기를 켰거나
  (열린 뒤 켜도 바로 멈춤), JavaScript가 없으면 `motion`이 없고, CSS가 장면마다 보통 절(제목, 그림, 모든 문장 목록)로 보여 준다.
- 무대 색: 테마와 상관없이 늘 어둡다(바탕 `#000000`, 글 `#F7F8FA`, 보조 글 `#9DA0A8`, 선 `#393B40`, 링크 `#6F9CF9`, 앱 토큰). 무대의 화면 사진이 앱의 어두운 테마이고,
  장면마다 밝고 어두움이 바뀌면 스크롤 중에 번쩍인다. 머리글·다운로드 블록·서명 절·바닥글은 지금처럼 밝은/어두운 테마를 따른다(밝은 테마에서는 흰 머리글 → 검은 무대 → 밝은 다운로드).
- 글자: Pretendard 600, 첫 화면 이름 `clamp(76px, 14vw, 208px)`·자간 -0.055em, 장면 제목 `clamp(32px, 4.4vw, 64px)`·-0.035em, 문장 `clamp(19px, 1.75vw, 25px)`.
  글은 왼쪽 정렬이고 첫 화면의 앱 창과 마지막 문장만 가운데. 무대와 다운로드 블록이 같은 폭(`--page` 1180px)이라 왼쪽 끝이 맞는다.
- 화면 사진: 기능 장면은 `assets/shots/tour/<화면>-<ko|en>-dark.webp` 14장(2026-11-07 둘러보기의 사진 그대로, 1.5배 캡처)을 `srcset="… 1.25x"`로 넣어 앱보다 조금 크게 보인다.
  첫 화면은 `assets/shots/<ko|en>-dark.webp`(1899×1188). 첫 화면 사진만 바로 받고 나머지는 `loading="lazy"`.
  `*-light.webp`는 기능 장면 14장을 지웠고, `assets/shots/<ko|en>-light.webp` 두 장은 공유 이미지 생성기(`tools/branding/gen_site_images.py`)가 읽으므로 남겼다.
- 지운 것: `assets/tour.js`, CSS의 `.tour`·`.tour-index`·`.feature*`·`.more`·`.shot`·`.visually-hidden`·스크롤 스냅·`--shot-shadow`, 홈의 앱 화면 `figure.shot`, 둘러보기 목록.
  `.hero`(다운로드 블록)는 `.get`으로 이름을 바꿨다(이제 첫 화면이 아니므로).
- 둘러보기 화면 사진의 출처(2026-11-07): 테스트 빌드(#119)의 **사본**을 사본 작업 공간으로 실행하고, 앱 안에서 도는 시험 에이전트가 Swing 공개 API로 조작하고 창만 그렸다(OS 입력 없음).
  화면 배율 1.5. 연결 이름(Sales DB, Billing 등)과 `127.0.0.1` 외에 비밀번호·키·개인 정보는 없다. Chromite Agent 화면은 [ai-agent.md](ai-agent.md)의 방식대로 로컬 가짜
  Messages API(가짜 키)로 찍었고, 도구 호출과 결과는 실제 DB 값이며 답의 글은 시험용 제공자가 돌려준 것이라고 그림 설명에 적었다(무대에서도 그대로).
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

### 파비콘과 공유 이미지 (2026-11-07)

사용자 요청: "웹사이트는 파비콘과 og 이미지도 만들어서 반영한다".

- 파일: `/favicon.ico`(16·32·48px), `/assets/icons/icon-32.png`, `icon-192.png`, `apple-touch-icon.png`(180px, 불투명 남색 바탕), 기존 `/assets/chromite-icon.svg`,
  공유 이미지 `/assets/og/og-ko.png`, `og-en.png`(1200×630). 생성기 `tools/branding/gen_site_images.py`가 `gen_app_icons.py`의 크기별 아이콘 그림(16px는 16px용
  그림)과 `chromite_logo_pack/chromite_wordmark_light.svg`, 사이트의 Pretendard 부분 집합, 홈의 실제 앱 화면(`assets/shots/<언어>-light.webp`) 일부로 만든다.
  공유 이미지 문구(`OG_COPY`)는 "100가지가 넘는 드라이버로 / 여러 데이터베이스를 / 한 창에서 다룹니다", "More than 100 drivers, / many databases, / one window"이고,
  모든 페이지의 `og:image:alt`·`twitter:image:alt`가 이 문구를 그대로 적는다. 문구를 바꾸면 생성기를 다시 실행하고 alt 7쪽을 같이 고친다.
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

1. `devel`에서 버전을 올리고([versioning.md](versioning.md)), 같은 PR에 `site/ko|en/changelog.html`의 `<section id="v<버전>">`을 쓴다.
2. 그 커밋에 `v<버전>` 태그 → `release.yml`이 서명 파일과 `SHA256SUMS`로 **초안** Release를 만든다.
3. 저장 데이터 형식이 바뀌었는지 정해 `-DataGeneration`을 고른다(바뀌었으면 이전 값 + 1).
4. 사용자 승인 뒤 `deploy-chromite-site.ps1`로 배포(먼저 리허설). 확인이 모두 OK인지 본다.
5. 사용자가 GitHub 초안 Release를 공개한다(배포는 초안에서도 된다).

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
- 데이터베이스 지원 문구(2026-11-08): `-Down` 뒤 리허설을 처음부터 실행(`rehearse.ps1 -Tag v0.1.1 -DataGeneration 2`, 실제 0.1.1 파일) `ALL CHECKS OK`, 사이트 파일 42개.
  헤드리스 Edge(CDP, 자기 프로필)로 한국어·영어 × 밝은·어두운 × 1440×900·375×812 홈: 가로 스크롤 없음, 깨진 이미지 없음, 콘솔·CSP 오류 없음(정보 수준 지연 로드 알림만),
  Pretendard 400·600 로드, 첫 화면·Chromite Agent·폐쇄망·서버 관리·"데이터베이스" 줄과 설명 태그 3개가 새 문구. 변경 내역·개인정보 4쪽과 `/`의 `og:image:alt`·`twitter:image:alt`가 새 문구,
  `/assets/og/og-ko.png`·`og-en.png` 200 `image/png`(120,391·137,400 B). 새 한글이 Pretendard 부분 집합에 모두 있음(없는 글자를 .notdef 그림과 비교해 찾는 방법, `漢`으로 검출 확인).
  Chromite Agent 장면은 375px에서 문장 목록 끝이 화면 아래로 넘치는 양을 예전 문구와 비교해 같거나 적게 맞췄다(영어 24px, 예전과 같음).
  화면 사진: `D:\Tools\_scratch\chromite-site-db-wording\shots\`.

## 확인하지 못한 것

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
| `.github/workflows/release.yml` | NSIS 설치 프로그램 빌드·서명·확인, macOS 파일 이름 |
| `.gitattributes`, `.gitignore` | `tools/release`의 `.sh`는 LF, `.local/` 무시 |
| `NOTICE` | 사이트의 Pretendard 웹 글꼴, Octicons GitHub 마크(MIT) |

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
