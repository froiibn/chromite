# 제품 버전 체계

## 목적

화면, 파일 이름, 릴리스 태그, 보고에 같은 버전 번호 하나를 쓴다. 사용자가 받은 빌드가 어느 커밋에서 나왔는지 번호만으로 알 수 있게 한다.

사용자가 2026-11-07 Turbrix 프로젝트의 방식을 따르기로 정했다. 그전에는 버전을 정하지 않아 DBeaver 버전 26.2.5를 그대로 썼다.

## 규칙

- 형식은 `major.minor.dev`이고 0.1.0부터 시작한다.
  - `major`: 큰 변경. **1.0.0은 사용자가 정한다.** `bump-version.ps1`은 `-AllowMajor` 없이 major를 올리지 않는다.
  - `minor`: 작은 변경. minor가 오르면 셋째 수는 0으로 돌아간다.
  - `dev`: 개발 빌드 번호. 사용자에게 주는 빌드를 만들기 **전에** "version" 커밋에서 손으로 올린다.
- 한 번 쓴 번호는 다시 쓰지 않는다. 태그만 붙고 배포하지 않은 번호도 마찬가지다.
- CI 실행 번호(`run_number`)나 `-dev` 같은 꼬리표를 붙이지 않는다. 화면, 파일 이름, 태그, 보고가 모두 같은 번호를 쓴다.
- 릴리스 태그는 `v<버전>`(예: `v0.1.0`)이다. `v*` 태그를 push하면 서명 릴리스(`release.yml`)가 돈다.
  태그가 정확히 `v` + 커밋된 버전이 아니면 `version` 작업이 실패한다. 태그는 옮기지 않는다.
- 빌드마다 달라지는 정보(빌드 시각)는 OSGi 한정자(qualifier)에만 들어간다(예: 번들 버전 `0.1.0.202611071530`).
  화면과 파일 이름에는 한정자를 쓰지 않는다.

## 구조

### 유일한 원본과 사본

원본은 `product/swing/chromite-swing.product`의 `version="X.Y.Z.qualifier"`다. `release.yml`도 이 값을 읽는다.

Tycho는 `.product`, `MANIFEST.MF`, `pom.xml`의 버전을 Maven 속성으로 받지 못하고 글자 그대로 적어야 한다.
그래서 같은 번호가 파일 4곳에 있고, `tools/release/bump-version.ps1`만 이 4곳을 바꾼다.

| 파일 | 값 | 이 값이 필요한 이유 |
|---|---|---|
| `product/swing/chromite-swing.product` | `version="0.1.0.qualifier"` | **원본.** 제품 IU 버전. Tycho가 macOS `Info.plist`에 넣는다 |
| `plugins/com.seizia.chromite.ui.swing/META-INF/MANIFEST.MF` | `Bundle-Version: 0.1.0.qualifier` | 제품을 정의하는 번들. About이 이 번들 버전을 보여 준다(`GeneralUtils.getProductVersion()`, 아래) |
| `plugins/com.seizia.chromite.ui.swing/pom.xml` | `<version>0.1.0-SNAPSHOT</version>` | Tycho는 `eclipse-plugin`의 POM 버전이 MANIFEST 버전과 같아야 빌드한다 |
| `product/swing/pom.xml` | `<version>0.1.0-SNAPSHOT</version>` | 제품 모듈의 Maven 버전. `archiveFileName`이 `${unqualifiedVersion}`으로 이 값을 쓴다 |

### 원본에서 나오는 값

| 어디 | 값 | 어떻게 |
|---|---|---|
| About 대화상자 | `버전 0.1.0` / `Version 0.1.0` | `AboutDialog` → `GeneralUtils.getPlainVersion()` → 애플리케이션 기여 번들(`com.seizia.chromite.ui.swing`)의 `major.minor.micro` |
| 명령줄 `-version`, User-Agent 등 | `Chromite 0.1.0` 또는 `0.1.0.<한정자>` | 같은 `GeneralUtils.getProductTitle()`/`getProductVersion()` |
| p2 제품 IU (`target/p2content.xml`) | `0.1.0.<한정자>` | Tycho가 `.product`의 버전과 한정자로 만든다 |
| macOS `Info.plist` | `CFBundleShortVersionString` `0.1.0`, `CFBundleVersion` `0.1.0.<한정자>` | Tycho(p2 `BrandingIron`)가 제품 버전으로 바꾼다 |
| `.eclipseproduct` | 만들어지지 않음 | Tycho 5.0.4는 이 제품에 `.eclipseproduct`를 만들지 않는다(Windows·macOS 결과물 모두 없음). 그래서 `GeneralUtils.getProductPatchNumber()`는 늘 0이다(이 작업 전과 같음) |
| Windows 런처 버전 리소스 | 바뀌지 않음 | 아래 "Windows 런처 버전 리소스" |
| 릴리스 zip·dmg 이름 | `chromite-swing-0.1.0-windows-x64.zip`, `chromite-swing-0.1.0-macos-aarch64.{zip,dmg}` | `release.yml`의 `version` 작업 출력(`VERSION`) |
| 릴리스 태그 검사 | `v0.1.0` | `release.yml`의 `version` 작업 |
| 결과물 폴더 | `chromite-swing/`, `ChromiteSwing.app` | 버전 없음(설치 위치가 버전마다 바뀌지 않게) |
| `product/swing/target/com.seizia.chromite.swing.product-0.1.0-SNAPSHOT.zip` | 제품 모듈 POM 버전 | Tycho가 만드는 p2 메타데이터 zip(약 45 KB). 배포하지 않는다 |

### `tools/release/bump-version.ps1`

```powershell
pwsh tools/release/bump-version.ps1                # 지금 버전 출력. 4곳이 다르면 실패
pwsh tools/release/bump-version.ps1 0.1.1          # 4곳을 한 번에 0.1.1로
pwsh tools/release/bump-version.ps1 1.0.0 -AllowMajor   # 사용자가 1.0.0을 정했을 때만
```

새 버전을 거부하는 경우:

- `X.Y.Z` 형식이 아니다.
- 지금 버전보다 높지 않다(같은 번호 포함).
- 로컬의 `v*` 태그 중 같거나 더 높은 것이 있다(태그만 붙은 번호도 다시 쓰지 않는다). 다른 PC에서 붙인 태그도 보려면 먼저 `git fetch --tags`.
- major나 minor가 올랐는데 셋째 수가 0이 아니다.
- major가 바뀌는데 `-AllowMajor`가 없다.

4곳의 버전이 서로 다르면 검사 모드는 실패하고, 버전을 설정하면 가장 높은 값을 지금 버전으로 보고 4곳을 모두 고친다.
파일의 다른 글자(줄바꿈, 인코딩)는 바꾸지 않는다. PowerShell 7(`pwsh`)과 Windows PowerShell 5.1에서 돈다.
GitHub의 Linux 러너에도 `pwsh`가 있어서 `release.yml`이 같은 스크립트로 검사한다.

### 릴리스 순서

1. `devel`에서 `pwsh tools/release/bump-version.ps1 <새 버전>`, 바뀐 4개 파일을 "version" 커밋 하나로 만든다
   (예: `Set version 0.1.1`). PR로 `devel`에 넣는다.
2. 그 커밋으로 빌드해 사용자에게 준다. 로컬 빌드든 `release.yml` 수동 실행이든 화면과 파일 이름의 번호가 같다.
3. 서명 릴리스가 필요하면 그 커밋에 `git tag v<버전>`을 붙여 push한다. `version` 작업이 태그와 커밋된 버전을 비교한다.
4. 다음 빌드는 1번부터 다시 한다. 태그를 지우거나 다른 커밋으로 옮기지 않는다. 잘못된 태그가 있으면 그 번호를 건너뛴다.

### `release.yml`의 `version` 작업

- `tools/release/bump-version.ps1`을 인자 없이 실행해 커밋된 버전을 읽는다(4곳 일치 확인 포함). 필요한 폴더만 sparse checkout 한다.
- 버전이 `X.Y.Z`가 아니면 실패한다.
- 태그 push(`push` 이벤트)면 `GITHUB_REF`가 정확히 `refs/tags/v<버전>`이어야 한다. `v0.1.0-rc1`, `v0.1`도 실패한다.
- `workflow_dispatch`(`devel`에서만)는 태그 없이 커밋된 버전을 쓴다. 결과물 이름도 같은 버전이고, Release는 만들지 않는다.

## 설계 결정과 이유

### 원본을 `.product` 파일로 둔 이유

- `release.yml`이 이미 이 파일에서 버전을 읽고 있었다. 제품 IU 버전이라 Tycho가 `.eclipseproduct`와 `Info.plist`를 이 값으로 만든다.
- Maven 속성 하나(예: 루트 POM의 `chromite.version`)를 원본으로 두는 방법은 쓸 수 없다. Tycho는 `.product`의 `version`,
  `MANIFEST.MF`의 `Bundle-Version`, `pom.xml`의 `<version>`을 필터링하지 않는다(글자 그대로 읽는다).
- `tycho-versions-plugin:set-version`으로 바꾸는 방법도 있지만, Maven과 Tycho 반응기(134개 모듈) 전체를 읽어야 해서 느리고,
  "지금보다 높은 번호", "태그 번호 재사용 금지" 같은 규칙을 넣을 곳이 없다. 바꿀 곳은 4곳뿐이라 정규식 스크립트가 단순하고 확실하다.
  각 정규식은 정확히 한 곳에만 맞아야 하고, 아니면 스크립트가 실패한다.

### 번들 버전은 제품 버전을 따르지 않는다 (`ui.swing` 제외)

- 83개 번들과 feature는 DBeaver에서 온 버전(26.2.5, 1.0.x, 2.1.x 등)을 그대로 둔다. Maven 부모 POM(`chromite`, `plugins`, `features`, `product`)의
  `1.0.0-SNAPSHOT`도 그대로다.
- 이유
  - 번들 버전을 제품 버전에 묶으면 "version" 커밋마다 번들 수 × 2(`MANIFEST.MF`, `pom.xml`)개 파일과 feature 파일이 바뀐다.
    셋째 수를 빌드마다 올리므로 이 변경이 계속 되풀이되고, 다른 작업과 충돌하기 쉽다.
  - 번들 버전은 OSGi 호환성 정보다. Chromite는 p2 업데이트를 쓰지 않아 번들 버전을 사용자가 볼 일이 없다.
  - 빌드마다 달라지는 정보는 이미 모든 번들의 한정자(빌드 시각)에 들어간다.
- `com.seizia.chromite.ui.swing`만 제품 버전을 따른다. 이 번들이 애플리케이션과 제품 확장을 정의하고,
  `GeneralUtils.getProductVersion()`이 이 번들 버전을 화면에 보여 주기 때문이다.
  이 번들 버전이 26.2.5에서 0.1.0으로 내려갔지만, 이 번들을 `Require-Bundle`로 버전 범위와 함께 요구하는 번들은 없다(검색으로 확인).
- `deps/dbeaver-common/root/pom.xml`의 `dbeaver-version`(26.2.5)은 DBeaver 서드파티 p2 저장소 주소
  (`repo.dbeaver.net/p2/ce/26.2.5`)에 쓰이므로 바꾸지 않는다. 그래서 제품 `archiveFileName`은 더 이상 `${dbeaver-version}`을 쓰지 않고
  `${unqualifiedVersion}`(Tycho가 제품 모듈 POM 버전에서 만든 값)을 쓴다.

### Windows 런처 버전 리소스

Tycho(p2 `BrandingIron`)는 Windows 런처에 아이콘만 넣고 버전 리소스(FileVersion, ProductVersion)를 넣지 않는다.
빌드한 `chromite-swing.exe`에는 버전 리소스가 아예 없다(FileVersion, ProductVersion, ProductName 모두 빈 값, 아래 "확인한 것").
런처 브랜딩으로는 넣을 수 없으므로 제품 버전은 나중에 만들 NSIS 설치 프로그램이 담는다.
Turbrix의 업데이트 확인처럼 설치 파일의 ProductVersion을 검사하려면 설치 프로그램에서 해야 한다.

## upstream 대비 변경점

| 파일 | 변경 |
|---|---|
| `product/swing/chromite-swing.product`, `plugins/com.seizia.chromite.ui.swing/META-INF/MANIFEST.MF`, `plugins/com.seizia.chromite.ui.swing/pom.xml`, `product/swing/pom.xml` | 버전 26.2.5 → 0.1.0, 제품 `archiveFileName`을 `${unqualifiedVersion}`으로 |
| `tools/release/bump-version.ps1` | 추가 |
| `.github/workflows/release.yml` | `version` 작업이 스크립트로 버전을 읽고 태그를 정확히 비교 |
| `.github/ISSUE_TEMPLATE/bug_report.yaml` | 버전 칸 예시 26.2.5 → 0.1.0 |

DBeaver는 번들·제품 버전을 모두 DBeaver 릴리스 번호(예: 26.2.5)로 맞추고, 별도 빌드 저장소에서 바꾼다.

## 확인한 것

2026-11-07, Windows 11 PC, 이 브랜치(버전 0.1.0).

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing`(JBR 25, Maven) `BUILD SUCCESS`,
  이어서 `java tools/jre/BundleJre.java windows-x64` 성공. 한정자는 `202611061939`(Tycho는 UTC 시각을 쓴다).
- 앱 화면: 빌드한 `chromite-swing.exe`를 빈 작업 공간(`-data`)으로 실행해 Help > About을 열었다. "Chromite 정보" 대화상자에 `버전 0.1.0`.
- 번들: `plugins/com.seizia.chromite.ui.swing_0.1.0.202611061939.jar`, `bundles.info`도 같은 버전.
  feature 폴더는 `com.seizia.chromite.swing.feature_26.2.5.202611061939` 등 그대로(번들·feature 버전은 따르지 않음).
- p2 제품 IU: `p2content.xml`의 `com.seizia.chromite.swing.product` 버전 `0.1.0.202611061939`.
- macOS `Info.plist`(Windows에서 만든 `macosx/cocoa/aarch64/ChromiteSwing.app/Contents/Info.plist`): `CFBundleShortVersionString` = `0.1.0`,
  `CFBundleVersion` = `0.1.0.202611061939`, `CFBundleIdentifier` = `com.seizia.chromite.swing.product`.
- `.eclipseproduct`: Windows `chromite-swing/`, macOS `ChromiteSwing.app` 어디에도 없다.
- Windows 런처 버전 리소스: `(Get-Item chromite-swing.exe).VersionInfo`의 FileVersion, ProductVersion, ProductName이 모두 비어 있다.
- 릴리스 zip 이름: `release.yml`의 Windows "Package (zip)" 단계를 서명 없이 그대로 실행해(`VERSION`은 `bump-version.ps1` 출력)
  `chromite-swing-0.1.0-windows-x64-UNSIGNED.zip`(742개 파일)이 나왔다.
- `release.yml` `version` 작업: 작업의 bash 스크립트를 이 PC에서 환경 변수만 바꿔 실행. `v0.1.0` 통과, `v0.1.1`·`v0.1.0-rc1`·`v0.1` 실패, `devel` 수동 실행 통과.
- `bump-version.ps1`(pwsh 7, Windows PowerShell 5.1): 검사 모드 `0.1.0`. 거부: `0.1.0`(같음), `0.0.9`(낮음), `0.2.1`(셋째 수 0 아님),
  `1.0.0`(`-AllowMajor` 없음), `0.1`·`abc`(형식), 태그 `v0.1.3`이 있을 때 `0.1.2`. 복사본에서 `0.1.4`, `0.2.0` 설정 성공,
  부모 POM 버전은 그대로, 한 파일만 다르게 고친 뒤 검사 모드 실패, 다시 설정하면 4곳 복구.
- `python tools/rename/gate.py`: `ALL GATES PASSED`.

확인하지 못한 것: GitHub Actions에서 `release.yml` 실행(push·태그 없음), Mac에서 앱 실행·서명·공증.

## 남은 위험

- **`CFBundleVersion`이 네 자리다**(`0.1.0.202611061939`). Apple 문서는 정수 세 개까지를 권한다. 공증과 Gatekeeper가 이를 막는지는 확인하지 않았다
  (이 작업 전에도 `26.2.5.<한정자>`로 같은 형식이었다). 문제가 되면 `Info.plist`를 패키징 단계에서 고치거나 한정자 형식을 바꾼다.
- **번들 버전이 내려갔다**(`ui.swing` 26.2.5 → 0.1.0). 새로 푼 설치 폴더는 문제없다. 예전 폴더 위에 덮어 풀면 `plugins/`에 두 버전이 함께 남는다
  (`bundles.info`가 0.1.0만 가리키므로 실행되는 것은 0.1.0이다). 제품 버전을 이전 실행 버전과 비교하는 코드(예: 시작할 때의 데이터 이전)가 생기면
  26.2.5 → 0.1.0을 "낮아짐"으로 보지 않게 해야 한다.
- **태그 검사는 로컬 태그만 본다.** `bump-version.ps1`은 원격 태그를 받지 않는다. 다른 곳에서 붙인 태그를 놓치지 않으려면 먼저 `git fetch --tags`.
  놓쳐도 같은 번호의 태그를 다시 push하면 GitHub이 거부하고(태그는 옮기지 않는다), `release` 작업은 공개된 Release를 바꾸지 않는다.
- **사본 4곳**: 누군가 스크립트 없이 한 곳만 고치면 Tycho는 `ui.swing`의 POM·MANIFEST 불일치만 잡는다. 나머지 불일치는 `release.yml`의 `version` 작업
  (그리고 `bump-version.ps1` 검사 모드)에서야 드러난다. `build.yml`에는 이 검사를 넣지 않았다.
- `docs/chromite/telemetry.md`의 User-Agent 예시(`Chromite 26.2.5`)와 지난 작업 기록(`swing-frontend.md`)의 26.2.5는 이력이라 그대로 두었다.

## 변경 이력

- 2026-11-07 최초 작성. 버전 체계 `major.minor.dev`(사용자 결정, Turbrix 방식)를 적용하고 버전을 0.1.0으로 설정.
  `tools/release/bump-version.ps1` 추가, `release.yml` `version` 작업 변경. 26.2.5 → 0.1.0은 번호가 내려가므로 스크립트가 아니라 손으로 바꿨다.
