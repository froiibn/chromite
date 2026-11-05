# CI (GitHub Actions)

## 목적

Chromite의 CI를 DBeaver 조직의 워크플로, 액션, 시크릿 없이 이 저장소 안의 설정만으로 돌린다.

- `devel`에 push하거나 `devel` 대상 PR을 열면 지원 플랫폼(Windows x64, macOS arm64)의 전체 제품을 빌드한다.
  제품은 두 가지다: SWT 제품(`product/community`)과 Swing 제품(`product/swing`, [swing-frontend.md](swing-frontend.md)).
  Windows 제품은 Windows 러너에서, macOS 제품은 Linux 러너에서 만든다(아래 "macOS 제품을 Linux 러너에서 만드는 이유").
- 빌드한 제품을 워크플로 결과물(artifact)로 올려 PR마다 바로 내려받아 실행해 볼 수 있게 한다.
- CodeQL로 Chromite 코드(`deps/` 제외)의 보안 취약점을 검사한다.

## 구조

| 파일 | 실행 시점 | 하는 일 |
|---|---|---|
| `.github/workflows/build.yml` | `devel` push, `devel` 대상 PR, 수동 실행(`workflow_dispatch`) | 작업 `build`를 행렬(matrix)로 두 번 실행: `windows-x64`(`windows-latest`), `macos-aarch64-on-linux`(`ubuntu-latest`). 제품 빌드 후 결과물 업로드 |
| `.github/workflows/codeql.yml` | `devel` push, `devel` 대상 PR, 매주 화요일 03:30 UTC | 작업 `analyze`를 `actions`, `java-kotlin` 두 언어로 실행(`ubuntu-latest`) |
| `.github/codeql/codeql-config.yml` | `codeql.yml`이 읽음 | `paths-ignore: deps` |

### build.yml 단계

1. `actions/checkout@v7`
2. `actions/setup-java@v6`, `distribution: jetbrains`, `java-version: '25'` (JBR 25 JBRSDK)
3. `actions/cache@v6`: `~/.m2/repository`(Maven·Tycho·p2 결과물), `~/.m2/wrapper`(mvnw가 받은 Maven)
   - 키: `m2-<OS>-<arch>-<모든 pom.xml과 maven-wrapper.properties의 해시>`, 복원 키: `m2-<OS>-<arch>-`
   - `<OS>`, `<arch>`는 `runner.os`, `runner.arch`라서 러너가 바뀌면 키도 따라 바뀐다(macOS 제품 작업: `m2-Linux-X64-...`).
4. 빌드: `deps/dbeaver-common/mvnw -B -ntp package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce,product-chromite-swing`
   - Maven 한 번으로 SWT 제품(`product-dbeaver-ce`)과 Swing 제품(`product-chromite-swing`, `product/pom.xml`의 프로파일)을 함께 만든다.
     두 제품은 같은 reactor의 번들을 쓰므로 따로 실행하면 같은 번들을 두 번 빌드한다.
   - Windows는 `pwsh`에서 `mvnw.cmd`, Linux는 `bash`에서 `mvnw`를 실행한다. 두 단계의 인수는 같게 유지한다.
   - `pwsh`에서는 프로파일 목록을 작은따옴표로 묶는다(`'-Pproduct-dbeaver-ce,product-chromite-swing'`).
     묶지 않으면 PowerShell이 쉼표를 배열 구분자로 읽어 인수를 나눌 수 있다.
   - `MAVEN_OPTS=-Xmx3g`
5. 결과물 업로드(보관 7일). macOS 제품은 실행 파일의 실행 권한을 확인한 뒤 tar로 묶어 올린다. 제품마다 결과물을 따로 올린다.

### 결과물 (artifact)

Tycho는 `product/<community|swing>/target/products/<제품 ID>/<os>/<ws>/<arch>/<루트 폴더>`에 제품을 만든다
(`product/community/pom.xml`, `product/swing/pom.xml`의 `rootFolders`, 로컬 빌드 결과로 확인). 압축 파일(`archive-products`)은 만들지 않는다.

| 결과물 이름 | 만드는 러너 | 원본 경로 | 내용 |
|---|---|---|---|
| `chromite-windows-x64` | `windows-latest` | `product/community/target/products/org.jkiss.dbeaver.core.product/win32/win32/x86_64/` | zip 안에 `chromite/` 폴더(`chromite.exe` 포함) |
| `chromite-macos-aarch64` | `ubuntu-latest`(작업 `macos-aarch64-on-linux`) | `product/community/target/products/org.jkiss.dbeaver.core.product/macosx/cocoa/aarch64/Chromite.app` | zip 안에 `chromite-macos-aarch64.tar.gz`(`Chromite.app`) |
| `chromite-swing-windows-x64` | `windows-latest` | `product/swing/target/products/com.seizia.chromite.swing.product/win32/win32/x86_64/` | zip 안에 `chromite-swing/` 폴더(`chromite-swing.exe` 포함) |
| `chromite-swing-macos-aarch64` | `ubuntu-latest`(작업 `macos-aarch64-on-linux`) | `product/swing/target/products/com.seizia.chromite.swing.product/macosx/cocoa/aarch64/ChromiteSwing.app` | zip 안에 `chromite-swing-macos-aarch64.tar.gz`(`ChromiteSwing.app`, 런처 `Contents/MacOS/chromite-swing`) |

Swing 제품의 루트 폴더(`chromite-swing`, `ChromiteSwing.app`)는 `product/swing/pom.xml`의 `rootFolders`, 런처 이름(`chromite-swing`)은
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
    런처 이름은 `product/community/DBeaver.product`의 `<launcher name="chromite">`다.
    Swing 제품도 같은 방식이며 런처 이름은 `product/swing/chromite-swing.product`의 `<launcher name="chromite-swing">`다.
  - `ChmodAction`(`org.eclipse.equinox.p2.touchpoint.natives` 1.5.900 소스)은 빌드하는 OS가 Windows일 때만
    (`File.separatorChar == '\\'`, 30·100행) 아무것도 하지 않고, 그 밖의 OS에서는 `chmod 755 <파일>` 명령을 실행한다(191~208행).
    대상 제품의 OS가 아니라 빌드하는 OS를 본다. 그래서 Windows에서 만든 `Chromite.app`에는 실행 권한이 없고,
    Linux에서 만들면 macOS에서 만든 것과 같이 실행 권한이 붙는다.
  - `ChmodAction`은 `chmod`의 종료 코드를 확인하지 않는다(208~221행). 그래서 tar로 묶기 전에 런처에 실행 권한이 있는지
    확인하고, 없으면 작업을 실패시킨다(SWT·Swing 제품 각각). `chmod +x`를 따로 하지 않는 이유: 위 근거로 p2가 권한을 주므로 필요 없고,
    p2 동작이 바뀌었을 때 덮어 숨기지 않고 바로 드러내기 위해서다.
- Windows 제품은 계속 Windows 러너에서 만든다. 두 OS(Windows, Linux)에서 빌드하므로 경로 구분자, 대소문자 구분 같은
  OS 차이로 생기는 빌드 문제를 PR 단계에서 찾는다.
- 각 작업은 행렬 항목이 가리키는 플랫폼의 제품(SWT, Swing)만 올린다.
- 실제 macOS 러너는 나중에 다시 필요하다. 실행 시험(앱이 뜨는지), 코드 서명(`codesign`), 공증(notarization, `notarytool`)은
  macOS에서만 할 수 있다. 그때는 Linux 작업이 올린 `chromite-macos-aarch64`를 macOS 작업이 받아 처리하게 하면
  macOS 러너에서 빌드(수십 분)를 다시 하지 않아도 된다. macOS 러너 배정이 늦을 수 있으므로 그 작업은 PR을 막지 않게 둔다.

### macOS 결과물을 tar로 묶는 이유

`actions/upload-artifact`는 파일 권한을 저장하지 않는다. `Chromite.app/Contents/MacOS/`(Swing: `ChromiteSwing.app/Contents/MacOS/`)
실행 파일의 실행 권한을 지키려고 `tar -czf`로 묶어서 올린다. 이미 압축한 파일이라 `compression-level: 0`으로 다시 압축하지 않는다.
Windows 제품은 권한 문제가 없어 폴더를 그대로 올린다(상위 폴더를 올려 결과물 안에 `chromite/`, `chromite-swing/` 폴더가 남는다).

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
- macOS 제품은 Linux에서 만들기 때문에 CI는 이 제품이 macOS에서 실제로 실행되는지 확인하지 않는다(위 "macOS 제품을 Linux 러너에서 만드는 이유").
- 빌드는 인터넷의 P2 저장소(`download.eclipse.org`, `repo.dbeaver.net/p2/ce/<버전>`)와 Maven Central을 쓴다([build.md](build.md)).
  이 서버가 응답하지 않으면 CI도 실패한다.
- `package` 단계까지만 실행하므로 테스트(`test/`)는 돌리지 않는다.
- 결과물은 서명하지 않았고 JRE를 내장하지 않는다. 실행하려면 JDK 21 이상(Swing 제품은 JDK 25 이상, [swing-frontend.md](swing-frontend.md))이
  설치되어 있어야 한다. 배포용이 아니라 확인용이며 7일 뒤 지워진다.
- `actions/cache` 저장 공간은 저장소당 10GB다. 두 OS의 캐시가 커지면 오래된 캐시부터 지워진다.
- 저장소 최상위 `SECURITY.md`는 아직 `security@dbeaver.com`을 안내한다(이 작업 범위 밖, 따로 고쳐야 함).

## upstream 대비 변경점

| 파일 | 변경 |
|---|---|
| `.github/workflows/push-pr-devel.yml` | 삭제 |
| `.github/workflows/build.yml` | 추가 |
| `.github/workflows/codeql.yml` | 액션 v3 → v4, `checkout` v4 → v7, `config-file` 연결, 템플릿 주석 삭제 |
| `.github/codeql/codeql-config.yml` | 추가 |
| `.github/ISSUE_TEMPLATE.md` | 삭제 |
| `.github/ISSUE_TEMPLATE/config.yml`, `bug_report.yaml`, `SUPPORT.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `copilot-instructions.md`, `skills/code-review/SKILL.md` | DBeaver 조직 링크·연락처·제품명을 Chromite 기준으로 변경 |

## 변경 이력

- 2026-11-06 최초 작성. DBeaver 재사용 워크플로를 Chromite 자체 빌드(`build.yml`, Windows x64·macOS arm64)로 바꾸고,
  checkstyle 린트를 CI에서 뺐다. CodeQL에서 `deps/`를 제외했다. `.github` 아래 DBeaver 조직 전용 링크와 설정을 정리했다.
- 2026-11-06 macOS arm64 제품을 `macos-latest` 대신 `ubuntu-latest`에서 만든다(행렬 항목 `macos-aarch64-on-linux`).
  `macos-latest` 작업이 러너 용량 부족으로 한 번도 배정되지 않았다. 결과물 이름(`chromite-macos-aarch64`)과 tar 묶음은 그대로이고,
  묶기 전에 런처 실행 권한을 확인하는 단계를 넣었다. 루트 `pom.xml`의 환경 수(6개 → 2개)가 바뀐 것을 반영했다.
- 2026-11-06 Swing 제품을 CI에 넣었다. 두 빌드 단계가 Maven 한 번으로 `-Pproduct-dbeaver-ce,product-chromite-swing`을 빌드하고,
  결과물 `chromite-swing-windows-x64`(Windows 작업), `chromite-swing-macos-aarch64`(Linux 작업, 런처 실행 권한 확인 후 tar)를 더 올린다.
  "Swing 제품을 추가하는 방법" 절은 할 일을 마쳐 지웠다.
