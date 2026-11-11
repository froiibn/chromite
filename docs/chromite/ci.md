# CI (GitHub Actions)

## 목적

Chromite의 CI를 DBeaver 조직의 워크플로, 액션, 시크릿 없이 이 저장소 안의 설정만으로 돌린다.

- `devel`에 push하거나 `devel` 대상 PR을 열면 지원 플랫폼(Windows x64, macOS arm64)의 전체 제품을 빌드한다.
  제품은 Swing 제품(`product/swing`, [swing-frontend.md](swing-frontend.md)) 하나다. SWT 제품은 2026-11-07 삭제했다([swt-removal.md](swt-removal.md)).
  Windows 제품은 Windows 러너에서, macOS 제품은 Linux 러너에서 만든다(아래 "macOS 제품을 Linux 러너에서 만드는 이유").
- 빌드한 제품을 워크플로 결과물(artifact)로 올려 PR마다 바로 내려받아 실행해 볼 수 있게 한다.
- CodeQL로 Chromite 코드(`deps/` 제외)의 보안 취약점을 검사한다.

## 구조

| 파일 | 실행 시점 | 하는 일 |
|---|---|---|
| `.github/workflows/build.yml` | `devel` push, `devel` 대상 PR, 수동 실행(`workflow_dispatch`) | 작업 `build`를 행렬(matrix)로 두 번 실행: `windows-x64`(`windows-latest`), `macos-aarch64-on-linux`(`ubuntu-latest`). 제품 빌드 후 결과물 업로드. 작업 `rename-gate`(`ubuntu-latest`)가 `tools/rename/gate.py`로 옛 DBeaver 내부 이름 재유입과 깨진 참조를 검사([internal-rename.md](internal-rename.md)) |
| `.github/workflows/codeql.yml` | `devel` push, `devel` 대상 PR, 매주 화요일 03:30 UTC | 작업 `analyze`를 `actions`, `java-kotlin` 두 언어로 실행(`ubuntu-latest`) |
| `.github/workflows/release.yml` | `v*` 태그 push, `devel`에서 수동 실행 | 제품을 빌드·서명·확인하고 Windows 설치 프로그램(NSIS)을 만들어 서명·확인한 뒤 태그면 초안 Release에 올림([signing.md](signing.md), [distribution.md](distribution.md)) |
| `.github/workflows/macos-smoke.yml` | `release.yml`이 `v*` 태그에서 성공한 뒤(`workflow_run`), 수동 실행(`workflow_dispatch`: `source`, `tag`, `launcher_args`) | `macos-15` 러너에 서명한 dmg(또는 브랜치의 서명 없는 빌드)를 설치하고 서명·Gatekeeper 확인, 실행, 키체인, 앱 메뉴, 끝내기를 확인(아래 "macOS 설치·실행 시험") |
| `.github/codeql/codeql-config.yml` | `codeql.yml`이 읽음 | `paths-ignore: deps` |

### build.yml 단계

1. `actions/checkout@v7`
2. `actions/setup-java@v6`, `distribution: jetbrains`, `java-version: '25'` (JBR 25 JBRSDK)
3. `actions/cache@v6`: `~/.m2/repository`(Maven·Tycho·p2 결과물), `~/.m2/wrapper`(mvnw가 받은 Maven)
   - 키: `m2-<OS>-<arch>-<모든 pom.xml과 maven-wrapper.properties의 해시>`, 복원 키: `m2-<OS>-<arch>-`
   - `<OS>`, `<arch>`는 `runner.os`, `runner.arch`라서 러너가 바뀌면 키도 따라 바뀐다(macOS 제품 작업: `m2-Linux-X64-...`).
4. 빌드: `deps/dbeaver-common/mvnw -B -ntp package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing`
   - Windows는 `pwsh`에서 `mvnw.cmd`, Linux는 `bash`에서 `mvnw`를 실행한다. 두 단계의 인수는 같게 유지한다.
   - 프로파일을 여러 개 쓰게 되면 `pwsh`에서는 목록을 작은따옴표로 묶는다(PowerShell이 쉼표를 배열 구분자로 읽는다).
   - `MAVEN_OPTS=-Xmx3g`
5. 내장 Java 런타임([runtime.md](runtime.md)): `java tools/jre/BundleJre.java <플랫폼>`이 작업의 플랫폼(`windows-x64`, `osx-aarch64`)용
   JBR SDK를 받아(SHA-256 확인) 위 JDK 25의 jlink로 줄인 런타임을 제품에 넣는다(Windows `jre/`, macOS `Contents/Eclipse/jre`).
   - 받은 파일은 `~/.cache/chromite-jbr`에 두고 `actions/cache`로 캐시한다. 키: `jbr-<행렬 이름>-<tools/jre/jbr.properties의 해시>`.
   - 단계 끝에서 구조를 확인한다. Windows: `jre/bin/server/jvm.dll`, `jre/bin/javaw.exe`(런처가 찾는 파일).
     macOS: `lib/libjli.dylib`, 실행 권한이 있는 `bin/java`·`lib/jspawnhelper`, `lib/server/libjvm.dylib`가 arm64 Mach-O인지(`file`).
     jlink가 만드는 `release` 파일에는 `JAVA_VERSION`, `MODULES`만 있고 OS 정보가 없어서 라이브러리 자체를 본다.
6. 결과물 업로드(보관 7일). macOS 제품은 실행 파일의 실행 권한을 확인한 뒤 tar로 묶어 올린다.

### 결과물 (artifact)

Tycho는 `product/swing/target/products/<제품 ID>/<os>/<ws>/<arch>/<루트 폴더>`에 제품을 만든다
(`product/swing/pom.xml`의 `rootFolders`, 로컬 빌드 결과로 확인). 압축 파일(`archive-products`)은 만들지 않는다.
SWT 제품의 결과물(`chromite-windows-x64`, `chromite-macos-aarch64`)은 SWT 삭제와 함께 없어졌다.

| 결과물 이름 | 만드는 러너 | 원본 경로 | 내용 |
|---|---|---|---|
| `chromite-swing-windows-x64` | `windows-latest` | `product/swing/target/products/com.seizia.chromite.swing.product/win32/win32/x86_64/` | zip 안에 `chromite-swing/` 폴더(`chromite-swing.exe` 포함) |
| `chromite-swing-macos-aarch64` | `ubuntu-latest`(작업 `macos-aarch64-on-linux`) | `product/swing/target/products/com.seizia.chromite.swing.product/macosx/cocoa/aarch64/Chromite.app` | zip 안에 `chromite-swing-macos-aarch64.tar.gz`(`Chromite.app`, 런처 `Contents/MacOS/chromite-swing`) |

Swing 제품의 루트 폴더(`chromite-swing`, `Chromite.app`)는 `product/swing/pom.xml`의 `rootFolders`, 런처 이름(`chromite-swing`)은
`product/swing/chromite-swing.product`의 `<launcher name="chromite-swing">`이다. 로컬 빌드 결과
(`product/swing/target/products/com.seizia.chromite.swing.product/`)에서 `win32/win32/x86_64/chromite-swing/chromite-swing.exe`와
`macosx/cocoa/aarch64/ChromiteSwing.app/Contents/MacOS/chromite-swing`이 있는 것을 확인했다.

## 설계 결정과 이유

### DBeaver 재사용 워크플로를 쓰지 않는 이유

이전 `push-pr-devel.yml`은 `dbeaver/dbeaver-common/.github/workflows/mvn-package.yml@devel`과
`java-checkstyle.yml@devel`을 호출했다. (아래 줄 번호는 `deps/dbeaver-common/.github/workflows/`의 사본 기준)

- `@devel`은 고정되지 않은 참조라서 DBeaver가 워크플로를 바꾸면 Chromite CI가 그대로 바뀐다(CLAUDE.md "upstream 정책"과 맞지 않음).
- `mvn-package.yml`은 DBeaver 조직의 액션(`dbeaver/github-actions/prepare-for-a-maven-lifecycle@devel`, 52행)과
  DBeaver 시크릿(`DEVOPS_ISSUE_RO_TOKEN`, 33·55·62행)을 쓴다. Chromite 저장소에는 이 시크릿이 없다.
- 빌드는 `ubuntu-latest`에서만 돌고(40행) 기본 제한 시간이 10분이다(11행). Chromite가 지원하는 Windows·macOS에서는 빌드하지 않는다.
- 린트(`java-checkstyle.yml`)는 매 PR마다 실패했다. 아래 "린트를 뺀 이유"를 본다.

### JDK: JetBrains Runtime 25

- 로컬 빌드와 같은 JBR 25를 쓴다([runtime.md](runtime.md), 공통 빌드 명령). `deps/dbeaver-common/root/pom.xml`의
  `java.version`은 21이므로 컴파일 대상은 Java 21이고, JDK 25는 Maven·Tycho를 실행하고 컴파일하는 데 쓴다.
- `actions/setup-java`의 `jetbrains` 배포판은 v5까지 JBR의 네 자리 버전(예: `25.0.4.1`)을 읽지 못해
  `Invalid Version` 오류로 실패했다(actions/setup-java#1008). 이 문제는 PR #1009(2026-06-18 병합)로 고쳐졌고
  v6.0.0(2026-08-24)에 들어 있다. v6에는 JBR 릴리스 목록 페이지 처리(#1218)와 토큰 처리(#1224) 수정도 있다. 그래서 v6을 쓴다.
- JBR 설치기는 GitHub API로 릴리스 목록을 읽으므로, 공용 러너의 비인증 호출 한도에 걸리지 않도록 `GITHUB_TOKEN`을 넘긴다.
- JBR 설치가 다시 깨지면 `distribution: temurin`, `java-version: '25'`로 바꾼다. 빌드 결과는 같다(컴파일 대상이 Java 21).

### Maven: deps/dbeaver-common의 Maven Wrapper

- `deps/dbeaver-common/.mvn/wrapper/maven-wrapper.properties`가 Maven 3.9.16을 고정한다. 로컬 빌드와 `tools/build.*`가
  같은 Maven을 쓰므로 러너에 미리 깔린 Maven 버전에 영향을 받지 않는다([build.md](build.md) ".mvn/ 폴더를 만들지 않은 이유").
- `-ntp`(다운로드 진행 표시 끄기)는 로그 크기만 줄인다. 빌드 결과에는 영향이 없다.

### macOS 제품을 Linux 러너에서 만드는 이유

- 처음에는 macOS 제품을 `macos-latest`(arm64) 러너에서 만들었다. 이 작업은 한 번도 시작되지 않았다. GitHub이 러너를
  배정하지 못해 15분 뒤 취소했고, 실행 주석은 "The job was not acquired by Runner of type hosted even after multiple attempts",
  "Due to capacity constraints, jobs targeting macOS arm64 runners may experience longer queue times"였다
  (실행 42094444213, 42097033922와 그 뒤 `devel` push 실행). 같은 실행의 Windows 작업은 통과했다.
- macOS 러너가 없어도 macOS 제품을 만들 수 있다. 루트 `pom.xml`의 `target-platform-configuration`(44~55행)에
  두 환경(win32/win32/x86_64, macosx/cocoa/aarch64)이 있어 어느 OS에서 빌드해도 두 제품 폴더가 모두 만들어진다.
  그래서 러너가 넉넉한 `ubuntu-latest`에서 만든다.
- 실행 권한: `Chromite.app/Contents/MacOS/chromite`의 실행 권한은 p2가 제품을 만들 때(materialize) native touchpoint의
  `chmod` 동작으로 준다.
  - p2 publisher가 macOS 런처 IU에 `chmod(targetDir:${installFolder}/../MacOS/, targetFile:<런처 이름>, permissions:755)`를
    넣는다(`org.eclipse.equinox.p2.publisher.eclipse` 1.6.900 소스, `EquinoxExecutableAction.computeMacInstallActions` 209~213행).
    런처 이름은 `product/swing/chromite-swing.product`의 `<launcher name="chromite-swing">`다
    (이 절의 `Chromite.app`은 처음 확인한 SWT 제품이다. 같은 방식이 `ChromiteSwing.app`에 적용된다).
  - `ChmodAction`(`org.eclipse.equinox.p2.touchpoint.natives` 1.5.900 소스)은 빌드하는 OS가 Windows일 때만
    (`File.separatorChar == '\\'`, 30·100행) 아무것도 하지 않고, 그 밖의 OS에서는 `chmod 755 <파일>` 명령을 실행한다(191~208행).
    대상 제품의 OS가 아니라 빌드하는 OS를 본다. 그래서 Windows에서 만든 `Chromite.app`에는 실행 권한이 없고,
    Linux에서 만들면 macOS에서 만든 것과 같이 실행 권한이 붙는다.
  - `ChmodAction`은 `chmod`의 종료 코드를 확인하지 않는다(208~221행). 그래서 tar로 묶기 전에 런처에 실행 권한이 있는지
    확인하고, 없으면 작업을 실패시킨다. `chmod +x`를 따로 하지 않는 이유: 위 근거로 p2가 권한을 주므로 필요 없고,
    p2 동작이 바뀌었을 때 덮어 숨기지 않고 바로 드러내기 위해서다.
- Windows 제품은 계속 Windows 러너에서 만든다. 두 OS(Windows, Linux)에서 빌드하므로 경로 구분자, 대소문자 구분 같은
  OS 차이로 생기는 빌드 문제를 PR 단계에서 찾는다.
- 각 작업은 행렬 항목이 가리키는 플랫폼의 제품만 올린다.
- 실제 macOS 러너는 나중에 다시 필요하다. 실행 시험(앱이 뜨는지), 코드 서명(`codesign`), 공증(notarization, `notarytool`)은
  macOS에서만 할 수 있다. 그때는 Linux 작업이 올린 `chromite-macos-aarch64`를 macOS 작업이 받아 처리하게 하면
  macOS 러너에서 빌드(수십 분)를 다시 하지 않아도 된다. macOS 러너 배정이 늦을 수 있으므로 그 작업은 PR을 막지 않게 둔다.
  - 서명·공증은 이 방식으로 `release.yml`에 넣었다(Linux 작업이 만든 앱을 `macos-15` 작업이 받아 서명, [signing.md](signing.md)).
    `build.yml`은 바뀌지 않았고 PR을 막지 않는다. 실행 시험은 `macos-smoke.yml`이 한다(아래 "macOS 설치·실행 시험").

### macOS 결과물을 tar로 묶는 이유

`actions/upload-artifact`는 파일 권한을 저장하지 않는다. `Chromite.app/Contents/MacOS/`
실행 파일의 실행 권한을 지키려고 `tar -czf`로 묶어서 올린다. 이미 압축한 파일이라 `compression-level: 0`으로 다시 압축하지 않는다.
Windows 제품은 권한 문제가 없어 폴더를 그대로 올린다(상위 폴더를 올려 결과물 안에 `chromite-swing/` 폴더가 남는다).

### 캐시

- Tycho는 p2 저장소에서 받은 번들도 `~/.m2/repository`(`p2/`, `.cache/tycho/`)에 저장하므로 이 폴더 하나로 대부분을 캐시한다.
- `actions/cache`는 작업이 성공했을 때만, 그리고 키가 정확히 맞지 않았을 때만 저장한다. pom이 바뀌면 새 키로 저장하고,
  그 전까지는 복원 키로 가장 최근 캐시를 쓴다.
- PR에서 만든 캐시는 그 PR에서만 쓰이고, `devel` push에서 만든 캐시는 모든 PR이 쓴다(GitHub 캐시 범위 규칙).

### 린트(checkstyle)를 뺀 이유

CI에서 checkstyle을 돌리지 않기로 했다. 깔끔하게 유지할 방법이 없어서다.

- DBeaver 설정(`deps/dbeaver-common/.github/dbeaver-checkstyle-config.xml`)은 사용자 정의 검사
  `NullabilityAnnotationsCheck`(368행)를 쓴다. 이 검사는 DBeaver 저장소 `dbeaver/checkstyle-nullability-annotations`를
  빌드해야 쓸 수 있다(`java-checkstyle.yml` 30행). 보고는 DBeaver의 reviewdog 포크(`dbeaver/action-java-checkstyle`, 45행)가 한다.
- 그대로 쓰면 DBeaver 저장소에 다시 의존하고, 설정을 고치면 `deps/`를 고쳐야 한다(CLAUDE.md "빌드 환경").
- 원래 워크플로도 `fail_on_error: false`(51행)라서 위반이 있어도 PR을 막지 않았다. 실패 원인은 위반이 아니라
  `deps/`와 기존 DBeaver 코드에서 나온 수천 건의 결과가 GitHub 주석(annotation) 한도를 넘은 것이었다.
- 다시 넣으려면: Chromite 소유의 설정 파일(사용자 정의 검사 제외)을 `.github/` 아래에 두고, Checkstyle 공식 jar로
  PR에서 바뀐 `.java` 파일(`deps/` 제외)만 검사해 로그나 요약(`$GITHUB_STEP_SUMMARY`)으로만 보고한다.

### CodeQL: Java는 build-mode none

- `build-mode: none`은 소스를 빌드하지 않고 분석한다. JDK 25와 Tycho 빌드(수십 분)가 필요 없다.
- GitHub 문서에 따르면 컴파일 언어에서 `paths-ignore`는 빌드하지 않고 분석할 때(C/C++, C#, Java, Rust)만 적용된다.
  빌드하는 방식(`autobuild`, `manual`)에서는 Maven reactor가 `deps/`를 함께 컴파일하므로 `deps/`가 분석에서 빠지지 않는다.
- `deps/`는 고치지 않은 upstream 사본이라 결과가 나와도 이 저장소에서 고칠 수 없다. 그래서 `paths-ignore: deps`로 뺀다.
  같은 설정으로 `actions` 분석에서도 `deps/*/.github/workflows`가 빠진다(GitHub이 실행하지 않는 파일).
- 저장소에 Kotlin 파일이 없으므로(`git ls-files '*.kt' '*.kts'` 결과 0개) Java만 분석해도 빠지는 코드가 없다.

### 동시 실행

같은 PR(또는 같은 브랜치)에 새 커밋이 오면 `build.yml`의 이전 실행을 취소한다(`concurrency`, 이전 워크플로와 같은 규칙).

## macOS 설치·실행 시험 (macos-smoke.yml)

Mac이 없어 코드로만 확인한 macOS 동작(앱 시작, Cmd+Q의 끝내기 경로, 키체인 보안 저장소, 앱 메뉴)을 GitHub의 `macos-15`(arm64) 러너에서 실제로 돌려 본다.
이 저장소는 공개 저장소라 러너가 무료이고, 러너에는 로그인한 화면 세션이 있다. 확인 명령은 모두 `tools/release/macos-smoke.sh`의 하위 명령이다
(`release-macos.sh`처럼 워크플로 단계 하나가 하위 명령 하나).

### 작업

| 작업 | 러너 | 권한 | 하는 일 |
|---|---|---|---|
| `release-assets` | `ubuntu-latest` | `contents: write` | 태그(입력 `tag`, `workflow_run`이면 그 태그, 비면 가장 최근 릴리스)의 `Chromite-<v>-arm64.dmg`와 `SHA256SUMS`를 `gh release download`로 받아 SHA-256을 확인하고 결과물 `smoke-input-release-dmg`(보관 1일)로 넘김. 릴리스가 없으면(서명 없는 릴리스 실행은 초안을 만들지 않음) 안내만 하고 끝 |
| `build` | `ubuntu-latest` | 읽기 | `source: build`일 때만. 실행한 브랜치를 `release.yml`의 `macos-build`와 같은 단계로 빌드해 `Chromite.app`을 tar로 넘김 |
| `smoke` | `macos-15` | 읽기 | 아래 단계. 로그·스크린숏을 결과물 `macos-smoke-<source>`(보관 14일)로 올리고 `result.txt`를 작업 요약에 씀 |

`smoke` 단계(`macos-smoke.sh` 하위 명령):

1. `install <dmg>`(release): dmg에 `com.apple.quarantine`을 붙이고(브라우저로 받은 파일처럼) `hdiutil attach` → `ditto`로 `/Applications/Chromite.app`에 복사 → `hdiutil detach`.
   복사본이 이미지의 격리 속성을 물려받는지 확인한다. `unpack <tar.gz>`(build): 풀고 임시 서명(`codesign --sign -`, Apple silicon은 서명 없는 코드를 실행하지 않음). 격리·서명 확인은 하지 않는다.
2. `verify <dmg>`(release): `codesign --verify --deep --strict`, Developer ID·타임스탬프, `spctl --assess --type execute`(격리된 복사본, "accepted", `source=Notarized Developer ID`),
   앱과 dmg의 `xcrun stapler validate`, dmg의 `spctl --assess --type open`, `syspolicy_check distribution`. Gatekeeper가 꺼져 있으면 러너에서만 켠다(`release-macos.sh`와 같음).
3. `workspace`: 데이터 폴더(`CHROMITE_DATA`)와 작업 공간을 임시 폴더에 만든다. 프로젝트 `General`에 SQLite 연결 하나(연결 유형 `smoke`: 수동 커밋, 실행 전 확인 없음)와
   #111 이전 형식의 `credentials-config.json`(고정 키 AES, 가짜 비밀번호)을 둔다. 첫 시작이 이 파일을 보안 저장소로 옮기므로 키체인 항목이 생긴다.
4. `launch <이름>`: `open -n --env CHROMITE_DATA=… --stdout … --stderr … <앱> --args -data <작업 공간>`. 120초 안에 디버그 로그(`<작업 공간>/.metadata/chromite-debug.log`)에
   `Startup times`(`ChromiteSwingApplication.run`이 메인 창을 띄운 뒤 남김, 프로세스 시작부터 단계별 ms)가 나오고 앱 프로세스에 창이 생기기를 기다린다.
   창은 System Events(손쉬운 사용 API)로 센다. 그 줄보다 먼저 보이는 창은 시작 화면이라 스크린숏(`*-splash.png`)과 창 목록을 남긴다.
   메인 창이 뜬 뒤 시작 화면 크기(560×320 pt)의 창이 남아 있으면 실패, 시작 화면을 봤고 닫혔으면 PASS, 시간 줄은 NOTE로 남긴다([branding.md](branding.md) "시작 화면").
   메인 창 전에는 0.3초마다 창의 자리·크기를 `*-splash-positions.txt`(시각 x y 너비 높이)에 남기고, 창이 바뀔 때마다 스크린숏을 찍는다(`*-splash.png`, `*-splash-2..4.png`).
   시작 화면 크기 창의 자리가 하나뿐이면 PASS, 둘 이상이면 FAIL 줄(단계는 계속, 앱 변경 #52, branding.md "자리"). 디버그 로그의 `Startup splash at …` 줄과
   `macOS unified title bar …` 줄은 NOTE로 남기고(네이티브 라이브러리 없음은 FAIL 줄), 메인 창 위쪽 90pt를 `*-title-bar.png`로 찍는다([mac-ui.md](mac-ui.md) "통합 제목 줄").
   (2026-11-08 전에는 플랫폼을 만들기 전의 `SSL trust store type` 줄을 기다렸다. 시작 화면이 생긴 뒤로는 그 시점의 창이 메인 창이 아닐 수 있다.)
   Gatekeeper의 첫 실행 질문이 보이면 스크린숏을 찍고 System Events로 "Open"을 한 번 누른다.
   창이 뜨지 않으면 프로세스·작업 공간·데이터 폴더·Equinox 로그·통합 로그(`*-diagnostics.txt`), 네이티브 스택(`sample`, `*-sample.txt`), JVM 스레드 덤프(SIGQUIT, `*-stdout.txt`)를 남긴다.
5. `secure-storage`: `<데이터 폴더>/ChromiteData/secure/secure_storage`가 있고 평문 비밀번호가 없으며 값이 키체인 제공자(`MacOSSecureStorageKeychain`)로 암호화됐는지,
   예전 파일이 옮겨지고 지워졌는지(`credentials-storage.properties`의 `legacy.migrated.…`), 로그인 키체인에 `com.seizia.chromite.SecureStorage`/`master-password` 항목이 있는지
   (`security find-generic-password`, `-g`/`-w` 없이 속성만), 디버그 로그에 보안 저장소 오류가 없는지. 키체인 잠금 상태(`security show-keychain-info`)도 남긴다.
6. `menus`: System Events로 화면 메뉴 막대의 항목 이름을 읽어 주 창 메뉴(File…Window)가 있는지 보고([mac-ui.md](mac-ui.md), `menu-bar.txt`), 앱 메뉴(메뉴 막대 항목 2)의 항목 이름을 읽고 1번(정보)과 "Options…"(Chromite 앱 메뉴)·"Settings…"·"Preferences…" 중 있는 것을 눌러 창이 하나 더 생기는지 보고 스크린숏, Esc로 닫는다.
   이어서 `titlebar`(앱 변경 #62): 앱을 다시 활성화해 `title-bar-light.png`, 시스템 모양을 어둡게 바꾸고 Finder를 거쳐 앱을 다시 활성화해(`SystemTheme`이 그때 다시 읽음)
   `title-bar-dark.png`·`main-window-dark.png`, 밝게 되돌린 뒤 손쉬운 사용 속성 `AXFullScreen`으로 전체 화면을 켜 `main-window-fullscreen.png`, 다시 끈다.
   그림은 눈으로 판단한다([mac-ui.md](mac-ui.md) "통합 제목 줄" 확인).
7. `quit appleevent`: `osascript -e 'tell application id "<번들 ID>" to quit'`(끝내기 처리기). 프로세스가 끝나고 디버그 로그에 `Main window closed`와 `(Swing) shutdown completed`가
   있어야 한다(JDK 기본 끝내기 `System.exit`이면 이 줄 없이 끝남). 새 크래시 보고서가 없어야 한다.
8. 두 번째 시작(`launch second-launch`, 보안 저장소에서 비밀번호를 읽음) → `quit keystroke`(System Events의 Cmd+Q).
9. 세 번째 시작 → `txn`(실패해도 작업을 실패시키지 않음): 탐색기 첫 줄 선택(아래 화살표) → Cmd+N 새 쿼리 창(0.1.4부터 macOS는 Command 키, 2026-11-12에 Ctrl+N에서 고침) → `INSERT` 입력 → F5 → Cmd+Q → "Don't Save" →
   "Uncommitted Transactions" 질문 스크린숏 → Cancel이면 앱이 남는지 → 다시 Cmd+Q → "Roll Back" → 정상 종료와 SQLite 파일에 행이 없는지.
10. `collect`(항상): 남은 앱 프로세스 정리, 디버그 로그, Equinox `.log`, `Info.plist`, 런처 ini, 크래시 보고서, Gatekeeper·키체인 통합 로그.

### 설계 결정

- **초안 릴리스 다운로드만 쓰기 권한**: 초안 릴리스는 push 권한이 있는 토큰에만 보인다. 그래서 다운로드 작업만 `contents: write`를 갖고 제품 코드를 실행하지 않는다.
  앱을 실행하는 `smoke` 작업은 읽기 권한이다. 비밀값은 쓰지 않는다(가짜 비밀번호 상수, API 키 없음).
- **`workflow_run`**: GitHub은 기본 브랜치(`devel`)에 있는 이 파일만 `workflow_run`으로 실행한다. 병합 전에는 수동 실행으로만 돈다.
  `head_branch`(태그 이름)는 셸에 환경 변수로 넘긴다(식 주입 방지).
- **`/Applications`에 `ditto`로 복사**: 사용자처럼 `/Applications`에 두되 Finder가 아니라서 격리 속성이 남는다. 그래서 첫 실행 때 Gatekeeper 질문과
  App Translocation(임의의 읽기 전용 경로에서 실행)이 생긴다. dmg에서 바로 실행하거나 다운로드 폴더에서 실행하는 사용자와 같은 경로다.
- **창은 System Events로 센다**: 처음에는 창 서버 목록(`CGWindowListCopyWindowInfo`)을 썼지만 러너의 셸 도구에는 화면 기록 권한이 없어 다른 프로세스의 창이 보이지 않았다(Dock만 보임).
  System Events(손쉬운 사용 권한)는 러너에서 동작했다. `screencapture -x`는 다른 앱의 창도 찍었다.
- **`launcher_args`**: 실험용(예: `--launcher.secondThread`). 비어 있으면 출시한 제품 그대로다. 결과 파일에 쓴 인수를 적는다.
- **서명 없는 빌드 경로(`source: build`)**: 릴리스 없이 브랜치를 확인하는 빠른 길. 서명·공증·격리는 확인하지 않는다.

### 결과 (2026-11-08, v0.1.1 초안 릴리스, macOS 15.8.2 arm64 가상 머신)

| 확인 | 결과 |
|---|---|
| dmg 설치, 격리 속성 전달 | 통과. 복사본이 이미지의 `com.apple.quarantine`을 물려받음 |
| `codesign --verify --deep --strict` | 통과. `Developer ID Application: Jaeyoung Ko (F3Z4RU59X6)`, 타임스탬프 있음 |
| `spctl` 앱(execute)·dmg(open) | 통과. 둘 다 `accepted`, `source=Notarized Developer ID` |
| `stapler validate` 앱·dmg, `syspolicy_check distribution` | 통과 |
| Gatekeeper 첫 실행 질문 | 나옴: "“ChromiteSwing” is an app downloaded from the Internet…", "Apple checked it for malicious software and none was detected." System Events로 Open을 누를 수 있음 |
| **앱 시작** | **실패(제품 결함)**. 프로세스는 뜨지만 창이 없고 디버그 로그도 생기지 않는다(아래) |
| 보안 저장소·키체인, 앱 메뉴, 끝내기(Apple event·Cmd+Q), 열린 트랜잭션 질문 | 앱이 시작하지 않아 실행하지 못함 |
| 그 밖에 본 것 | 앱 메뉴·크래시 대화상자의 앱 이름이 "Chromite-swing"(`Info.plist`의 `CFBundleName`, 브랜딩 결함). `Info.plist`의 `Eclipse` 인수에 Eclipse 기본값 `-keyring ~/.eclipse_keyring`. `NSRequiresAquaSystemAppearance`가 `true`(시스템 어두운 모드를 따르지 않음) |

`secure-storage`·`menus`·`quit`·`txn`(5~9단계)은 이때 앱이 시작하지 않아 실행되지 않았다. 시작 수정 뒤 결과는 아래 "결과 (시작 수정 뒤)".

**앱이 시작하지 않는 원인**: Eclipse 런처는 macOS에서 JVM을 프로세스의 첫(AppKit 메인) 스레드에서 시작한다. JVM 스레드 덤프에서 `main`은
`ChromiteSwingApplication.installLookAndFeel`(`SwingUtilities.invokeAndWait`)에서 기다리고, `AWT-EventQueue-0`은 `CGraphicsEnvironment.initMetal`에 있다.
AWT는 초기화 일부를 AppKit 메인 스레드에서 하고 끝나기를 기다리는데, 그 스레드가 Java `main`으로 막혀 있어 서로 기다린다(교착). `sample`도 메인 스레드가
`startJavaJNI` → `JVM_MonitorWait`에 있음을 보인다. SWT는 첫 스레드에서 돌아야 해서 런처의 기본이 이렇고, Swing에는 맞지 않는다.
런처의 `--launcher.secondThread`(JVM을 둘째 스레드에서 시작)로 실험하면 런처 자신이 메인 스레드에서 AppKit 단정(`_NSAssertMainEventQueueIsCurrentEventQueue`,
`eclipse_11924.so +[KeyWindow dispatch]`)으로 `EXC_BREAKPOINT` 종료한다("Chromite-swing quit unexpectedly"). 명령줄 `-vm …/jre/bin/java`를 더해도 런처가
같은 프로세스에 JVM을 올려(프로세스 하나, 같은 스레드 덤프) 결과가 같다. 이 브랜치(`devel`과 같은 제품 코드)의 서명 없는 빌드(`source: build`, 격리·App Translocation 없음)도
같은 곳에서 멈춰, 서명·Gatekeeper·Translocation 때문이 아니다. 수정은 [platform-startup.md](platform-startup.md) "macOS 메인 스레드".

증거(실행 URL과 파일): 실행 42400660270(`first-launch-sample.txt`), 42401087028(`--launcher.secondThread`, 크래시 보고서), 42401477114(`first-launch-stdout.txt` 스레드 덤프),
42401481357(서명 없는 빌드, 같은 스레드 덤프), 42402015208(서명 없는 빌드 + `-vm …/bin/java`).
내려받은 결과물은 `D:\Tools\_scratch\macos-smoke-ci\run*\`.

### 결과 (시작 수정 뒤, 2026-11-08, `source: build`, 실행 42407606192, `devel`에 다시 얹은 뒤 42408742370)

브랜치 `chromite/macos-startup-fix`의 서명 없는 빌드로 모든 단계가 통과했다(작업 결론 success).

| 확인 | 결과 |
|---|---|
| 시작(세 번) | 메인 창, 시작 줄 |
| 보안 저장소 | `<데이터 폴더>/ChromiteData/secure/secure_storage`, 평문 없음, 값의 모듈 `com.seizia.chromite.model.security.macossecurestoragekeychain`(Equinox가 소문자로 씀), 예전 파일 이전·삭제, 로그인 키체인에 `com.seizia.chromite.SecureStorage`/`master-password`, 로그 오류 없음. 러너의 로그인 키체인은 잠겨 있지 않았다(`security show-keychain-info`: `no-timeout`) |
| 앱 메뉴 | 정보·옵션 창이 열리고 Esc로 닫힘 |
| 끝내기(Apple event, Cmd+Q) | `Main window closed`·`shutdown completed`, 크래시 없음. Apple event는 앱이 끝내기를 취소하므로 osascript가 "User canceled (-128)"를 받는다(정상) |
| 열린 트랜잭션 | "Uncommitted Transactions" 질문, Cancel이면 남음, Roll Back이면 롤백 후 정상 종료 |

이 실행으로 고친 스크립트의 점(처음 실행에서 드러남):

- 디버그 로그는 시작마다 새로 쓰인다. 줄 수로 이어 읽지 않고 시작 전에 이전 로그를 `debug-log-before-<이름>.txt`로 옮긴다.
- 창 수는 System Events로 세고, `entire contents`는 변수에 한 번 읽은 뒤 돈다(목록에 `whose`를 쓸 수 없음). Swing 단추는 AX 제목(name)이 비어 있고
  글자가 AX 설명(description)에 있다.
- 탐색기 첫 줄은 Home이 아니라 아래 화살표로 고른다(macOS에서 Home이 트리 첫 줄에 묶여 있지 않음).
- 보안 저장소 확인은 모든 항목을 본 뒤 실패하고, 키와 모듈 ID만 남긴 사본(`secure-storage-redacted.txt`)을 남긴다.
- 앱 메뉴의 옵션 항목 이름은 "Options…"(Chromite 앱 메뉴)도 받는다.

서명한 빌드(다음 릴리스 뒤 `-f tag=<태그>`)에서만 확인할 것: 강화된 런타임에서 JNA 콜백(앱 메뉴, 끝내기 위임자), 격리된 앱의 첫 실행과 App Translocation에서의
시작, Developer ID 앱의 키체인 항목 위치. 수정에 권한(entitlements) 변경은 필요 없었다(JVM은 런처 프로세스 안, JNA 콜백은 기존 `allow-unsigned-executable-memory`).

## .github 파일 분류

| 파일 | 결정 | 이유 |
|---|---|---|
| `workflows/push-pr-devel.yml` | 삭제 | DBeaver 재사용 워크플로·액션·시크릿에 의존. `build.yml`로 대체 |
| `workflows/build.yml` | 새로 만듦 | Chromite 자체 빌드 |
| `workflows/codeql.yml` | 고쳐 씀 | 액션 버전 v4로 올림, 설정 파일 연결, GitHub 기본 템플릿 주석 정리 |
| `codeql/codeql-config.yml` | 새로 만듦 | `deps/` 제외 |
| `ISSUE_TEMPLATE.md` | 삭제 | 옛 단일 템플릿 형식으로 `ISSUE_TEMPLATE/bug_report.yaml`과 역할이 겹치고, DBeaver 위키·이슈 링크를 안내함 |
| `ISSUE_TEMPLATE/config.yml` | 고쳐 씀 | DBeaver PRO 체험판 광고, DBeaver 위키·YouTube·X 링크 삭제. `blank_issues_enabled: false`만 남김 |
| `ISSUE_TEMPLATE/bug_report.yaml` | 고쳐 씀 | 제품명 DBeaver → Chromite, DBeaver 위키 링크를 로그 위치 안내로 바꿈, 예시 버전·OS를 Chromite 기준으로 |
| `ISSUE_TEMPLATE/feature_request.md` | 유지 | 일반 내용 |
| `SUPPORT.md` | 고쳐 씀 | DBeaver 위키·이슈 트래커 대신 Chromite 이슈 트래커와 설계 문서 |
| `CONTRIBUTING.md` | 고쳐 씀 | DBeaver 기여 가이드 대신 Chromite 브랜치·PR 규칙(CLAUDE.md "Git 작업 방식") |
| `CODE_OF_CONDUCT.md` | 고쳐 씀(한 줄) | 신고 연락처 `support@dbeaver.com`을 Chromite 저장소로 바꿈. 본문(Contributor Covenant 1.4)은 유지 |
| `copilot-instructions.md` | 고쳐 씀 | 제목을 Chromite로, CLAUDE.md가 우선한다는 안내 추가 |
| `copilot-code-review-instructions.md` | 유지 | DBeaver 조직과 관계없는 일반 리뷰 지침 |
| `skills/code-review/SKILL.md` | 고쳐 씀(한 줄) | 잘못된 경로 `.agents/skilla/` → `.agents/skills/` |

`dependabot.yml`, `CODEOWNERS`는 원래 없었고 새로 만들지 않았다.

## 알려진 제약

- GitHub Actions를 로컬에서 실행할 수 없어 이 설정은 push한 뒤 첫 실행으로만 확인할 수 있다(작성 시점에 미확인).
- 두 작업 모두 두 플랫폼의 제품을 다 만들고 그중 한 플랫폼의 것만 올린다(루트 `pom.xml`, `product/swing/pom.xml`의 두 환경).
- macOS 제품은 Linux에서 만들기 때문에 `build.yml`은 이 제품이 macOS에서 실제로 실행되는지 확인하지 않는다. 실행 확인은 `macos-smoke.yml`이 릴리스 뒤나 수동으로 한다(위 "macOS 설치·실행 시험").
- 빌드는 인터넷의 P2 저장소(`download.eclipse.org`, `repo.dbeaver.net/p2/ce/<버전>`)와 Maven Central을 쓴다([build.md](build.md)).
  이 서버가 응답하지 않으면 CI도 실패한다.
- `package` 단계까지만 실행하므로 테스트(`test/`)는 돌리지 않는다.
- 결과물은 서명하지 않았다. 내장 런타임(`jre/`)이 들어 있어 Java를 따로 설치하지 않아도 실행된다([runtime.md](runtime.md)).
  배포용이 아니라 확인용이며 7일 뒤 지워진다. 서명한 배포 파일은 `release.yml`이 만든다([signing.md](signing.md)).
- macOS 런타임은 Linux에서 jlink로 만든다(교차 링크). `build.yml`은 구조와 실행 권한만 확인한다. `macos-smoke.yml`에서 내장 런타임의 JVM이 macOS에서 뜨는 것은 확인했다(스레드 덤프의 `java.base@25.0.4.1`).
- `macos-smoke.yml`은 `workflow_run` 때문에 `devel`에 병합된 뒤에만 릴리스를 따라 돈다. 병합 전에는 수동 실행으로만 돈다. GUI 조작(System Events)은 러너 이미지의 손쉬운 사용 권한에 기댄다.
- `actions/cache` 저장 공간은 저장소당 10GB다. 두 OS의 캐시가 커지면 오래된 캐시부터 지워진다.
  JBR SDK 다운로드 캐시는 플랫폼마다 하나(Windows 약 280MB, macOS 약 230MB)다.

## upstream 대비 변경점

| 파일 | 변경 |
|---|---|
| `.github/workflows/push-pr-devel.yml` | 삭제 |
| `.github/workflows/build.yml` | 추가 |
| `.github/workflows/codeql.yml` | 액션 v3 → v4, `checkout` v4 → v7, `config-file` 연결, 템플릿 주석 삭제 |
| `.github/codeql/codeql-config.yml` | 추가 |
| `.github/ISSUE_TEMPLATE.md` | 삭제 |
| `.github/ISSUE_TEMPLATE/config.yml`, `bug_report.yaml`, `SUPPORT.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `copilot-instructions.md`, `skills/code-review/SKILL.md` | DBeaver 조직 링크·연락처·제품명을 Chromite 기준으로 변경 |
| `SECURITY.md`(저장소 최상위) | 보고처를 `security@dbeaver.com`에서 GitHub Security Advisories(froiibn/chromite)로 변경, 지원 버전(최신 `devel`)과 upstream 보고 안내 추가 |

## 변경 이력

- 2026-11-06 최초 작성. DBeaver 재사용 워크플로를 Chromite 자체 빌드(`build.yml`, Windows x64·macOS arm64)로 바꾸고,
  checkstyle 린트를 CI에서 뺐다. CodeQL에서 `deps/`를 제외했다. `.github` 아래 DBeaver 조직 전용 링크와 설정을 정리했다.
- 2026-11-06 macOS arm64 제품을 `macos-latest` 대신 `ubuntu-latest`에서 만든다(행렬 항목 `macos-aarch64-on-linux`).
  `macos-latest` 작업이 러너 용량 부족으로 한 번도 배정되지 않았다. 결과물 이름(`chromite-macos-aarch64`)과 tar 묶음은 그대로이고,
  묶기 전에 런처 실행 권한을 확인하는 단계를 넣었다. 루트 `pom.xml`의 환경 수(6개 → 2개)가 바뀐 것을 반영했다.
- 2026-11-06 Swing 제품을 CI에 넣었다. 두 빌드 단계가 Maven 한 번으로 `-Pproduct-chromite-swt,product-chromite-swing`을 빌드하고,
  결과물 `chromite-swing-windows-x64`(Windows 작업), `chromite-swing-macos-aarch64`(Linux 작업, 런처 실행 권한 확인 후 tar)를 더 올린다.
  "Swing 제품을 추가하는 방법" 절은 할 일을 마쳐 지웠다.
- 2026-11-06 `SECURITY.md`를 Chromite 기준으로 고쳐(GitHub Security Advisories로 비공개 보고) 남은 문제 항목에서 지웠다.
- 2026-11-06 서명 릴리스 워크플로 `release.yml`을 추가했다(Turbrix 서명 안내서 기준, [signing.md](signing.md)). `build.yml`은 바꾸지 않았다.
- 2026-11-07 내부 이름 변경: `build.yml`에 작업 `rename-gate` 추가(빌드 없이 수 초). SWT 프로파일 이름 `product-chromite-swt`, SWT 제품 폴더 `com.seizia.chromite.core.product`.
- 2026-11-07 내장 Java 런타임 단계를 넣었다(`build.yml`, `release.yml`의 `windows`·`macos-build` 작업). 고정한 JBR SDK를 받아 캐시하고
  `tools/jre/BundleJre.java`로 두 제품에 넣은 뒤(SWT 삭제 뒤에는 Swing 제품 하나) 구조를 확인한다([runtime.md](runtime.md)). "알려진 제약"의 JRE 설치 필요 항목을 고쳤다.
  브랜치 `chromite/jbr-runtime`에서 수동 실행(실행 42267311710)으로 두 작업 성공과 macOS 결과물의 런타임 구조를 확인했다. `release.yml`은 실행하지 않았다.
- 2026-11-07 SWT 프런트엔드 삭제([swt-removal.md](swt-removal.md)): `build.yml`·`release.yml`에서 SWT 제품 빌드·업로드·서명·패키징 단계 삭제, 프로파일은 `product-chromite-swing`만. 결과물 표와 설명 갱신.
- 2026-11-07 `release.yml` Windows 작업에 NSIS 설치 프로그램(고정한 NSIS 3.12, 2단계 빌드·서명, 조용한 설치·제거 확인)을 넣고 macOS 결과 파일 이름을 계약 이름으로 바꿨다([distribution.md](distribution.md)). `build.yml`은 바꾸지 않았다.
- 2026-11-08 `macos-smoke.yml`과 `tools/release/macos-smoke.sh` 추가("macOS 설치·실행 시험"): `macos-15` 러너에서 v0.1.1 초안 dmg의 설치·서명·Gatekeeper는 통과,
  앱 시작은 런처가 JVM을 AppKit 메인 스레드에서 돌려 AWT와 교착해 실패함을 확인(제품 결함, 보고). "알려진 제약"의 macOS 실행 항목을 고침.
- 2026-11-08 macOS 시작 수정([platform-startup.md](platform-startup.md))과 함께 `macos-smoke.sh`의 처음 실행된 단계를 고침(디버그 로그 교체, System Events 창 세기·AX 설명,
  트리 선택 키, 보안 저장소 확인). 서명 없는 빌드로 모든 단계 통과(실행 42407606192, "결과 (시작 수정 뒤)").
- 2026-11-08 시작 화면(`chromite/startup-splash`, [branding.md](branding.md) "시작 화면"): `launch`가 메인 창 뒤의 `Startup times` 줄을 기다리고, 그 전에 보인 시작 화면을 찍고, 메인 창이 뜬 뒤 시작 화면이 닫혔는지 확인한다.
- 2026-11-09 `macos-smoke.sh menus`가 화면 메뉴 막대의 주 창 메뉴를 확인(앱 변경 #27, `chromite/mac-ui`, [mac-ui.md](mac-ui.md)). 아직 실행하지 않음.
- 2026-11-12 `macos-smoke.sh launch`가 시작 화면 자리를 0.3초마다 기록·비교하고 바뀔 때 찍음(앱 변경 #52), 메인 창 제목 줄 캡처와 새 단계 `titlebar`
  (밝은·어두운 모양, 전체 화면, 앱 변경 #62), `macos-smoke.yml`에 그 단계(`chromite/mac-ui-2`). 아직 실행하지 않음.
- 2026-11-09 macOS 앱 묶음 이름을 `ChromiteSwing.app`에서 `Chromite.app`으로 바꾼 것을 반영(앱 변경 #22, `chromite/install-update-2`, [distribution.md](distribution.md) "macOS 앱 이름"). 지난 확인 기록의 옛 이름은 그때의 결과라 그대로 둠.
