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
| `/ko/`, `/en/` | 다운로드: Windows 설치 파일, macOS 디스크 이미지(버전, 크기, 게시일, SHA-256과 복사 단추), 서명·자동 업데이트 안내 |
| `/ko|en/changelog.html` | 변경 내역. 버전마다 `<section id="v<버전>">` (`notes_url`의 앵커) |
| `/ko|en/privacy.html` | 개인정보: 업데이트 확인은 고정 User-Agent만 보낸다, 사이트에 쿠키·분석 없음, 접속 기록 30일 |
| `/assets/` | `site.css`, `site.js`, `lang.js`, `chromite-icon.svg`(`chromite_logo_pack/chromite_icon_64.svg`), `fonts/`(Pretendard 1.3.9 웹 부분 집합 Regular·SemiBold, `OFL.txt`) |

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

## 확인하지 못한 것

- GitHub Actions에서 `release.yml` 실행(태그·push 없음): Azure 서명 단계에서 `uninstall.exe`·`setup.exe` 서명, 서명된 파일의 정확한 서명자 주체.
  주체 문자열은 Turbrix `docs/SIGNING.md`(같은 Azure 계정의 게시자)에서 가져왔다. 다르면 확인 단계가 실패한다(의도한 동작). 첫 실행에서 확인한다.
- 서명된 설치 프로그램의 SmartScreen 동작, 대화형 설치 화면의 나머지 단계(구성 요소·마침), 바탕 화면 바로 가기 유지(바탕 화면이 OneDrive 폴더라 시험하지 않음).
- `%LOCALAPPDATA%\Programs\Chromite` 기본 위치 설치(시험은 `/D=`로 D: 폴더에만).
- macOS zip·dmg의 새 이름으로 서명·공증 작업 실행, Mac에서 `codesign`/`spctl` 확인 경로.
- 실제 서버 배포(사용자 승인 전이라 하지 않음).

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
| `NOTICE` | 사이트의 Pretendard 웹 글꼴 |

## 변경 이력

- 2026-11-07 최초 작성. NSIS 설치 프로그램(사용자별, 제자리 업그레이드·되돌리기, 2단계 서명), `release.yml` 연결, 피드 생성기(`releases.json`, `releases.js`),
  배포 스크립트와 로컬 Docker 리허설, 다운로드 사이트. Swing 제품의 작업 공간이 `%APPDATA%\ChromiteData`가 아니라 현재 폴더 아래 `workspace\`임을 확인해 기록.
- 2026-11-07 작업 공간 위치 수정 반영: "사용자 데이터의 위치"를 고친 뒤 기준으로 바꾸고 사용자 할 일 완료 표시([workspace-location.md](workspace-location.md)).
