# 빌드 의존 저장소 (deps/)

## 목적

Chromite를 이 저장소 하나만 받아서 빌드할 수 있게 한다.

DBeaver는 여러 저장소로 나뉘어 있고, 빌드할 때 `dbeaver-common`과 `datadam-api` 저장소를
DBeaver 저장소 **옆 폴더**에 clone해 두어야 했다. Chromite는 2026-11-06부터 DBeaver와 독립적으로 개발하므로,
빌드에 필요한 두 저장소를 이 저장소 안에 넣었다.

## 구조

```
chromite/
├── pom.xml                      부모 POM: deps/dbeaver-common/pom.xml
├── deps/
│   ├── dbeaver-common/          com.dbeaver.common:* (부모 POM, org.jkiss.utils 등 공통 번들)
│   └── datadam-api/             com.dbeaver.datadam:* (apis/ 아래 API 번들)
└── product/aggregate/pom.xml    reactor: deps/dbeaver-common, deps/datadam-api/apis, 저장소 루트
```

| 폴더 | upstream | 가져온 커밋 (devel) |
|---|---|---|
| `deps/dbeaver-common` | https://github.com/dbeaver/dbeaver-common | `ea9ab625b056f603cc8b1d156d1c48fb8d069f6f` |
| `deps/datadam-api` | https://github.com/dbeaver/datadam-api | `e1b28c3b4572cb29ddf0f04db1435fc51e835c45` |

- 두 폴더는 `git subtree add --squash`로 가져왔다. upstream 이력은 커밋 하나로 합쳐져 있고,
  그 커밋 메시지의 `git-subtree-dir`, `git-subtree-split`에 가져온 폴더와 upstream 커밋 ID가 남아 있다.
- 두 폴더의 파일은 고치지 않았다. 라이선스(Apache 2.0)와 출처는 `NOTICE`에 적었다.
- Maven 부모 관계
  - `pom.xml`(Chromite 루트)의 부모: `com.dbeaver.common:com.dbeaver.common.main:2.9.0-SNAPSHOT` →
    `deps/dbeaver-common/pom.xml`
  - `deps/dbeaver-common/pom.xml`의 부모: `com.dbeaver.common:root` → `deps/dbeaver-common/root/pom.xml`
    (Tycho 버전, Eclipse 버전, P2 저장소 주소, Java 버전이 여기에 있다)
  - `deps/datadam-api/apis/pom.xml`의 부모: `relativePath`가 `../../dbeaver-common`이라 `deps/dbeaver-common`을 가리킨다.

### 빌드 명령

```bash
mvn package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing
```

`tools/build.cmd`, `tools/build.sh`는 Maven을 설치하지 않아도 되도록 `deps/dbeaver-common`의 Maven Wrapper(`mvnw`)로
전체 빌드를 실행한다(`mvnw`가 Maven 3.9.16을 받아서 쓴다. 프로파일은 스크립트 안에 적힌 대로다).

### 내장 Java 런타임

Maven이 만든 제품에는 Java 런타임이 없다. 런타임을 넣으려면 빌드 뒤에 저장소 최상위에서 빌드에 쓴 JDK 25로 실행한다.

```bash
java tools/jre/BundleJre.java windows-x64 osx-aarch64
```

고정한 JBR SDK를 받아(SHA-256 확인, 다운로드 캐시) jlink로 줄인 런타임을 두 제품의 `jre/`(macOS `Contents/Eclipse/jre/`)에 넣는다.
CI는 작업마다 자기 플랫폼만 넣는다. 자세한 내용은 [runtime.md](runtime.md). `tools/build.*`는 이 단계를 실행하지 않는다.

### 빌드하는 제품

| 프로파일 (`product/pom.xml`) | 모듈 | 제품 |
|---|---|---|
| `product-chromite-swing` | `product/swing` | Swing 제품 `chromite-swing` ([swing-frontend.md](swing-frontend.md)) |

- SWT 제품(`product/community`, 프로파일 `product-chromite-swt`)은 2026-11-07 삭제했다([swt-removal.md](swt-removal.md)).
  결과물은 `product/swing/target/products/com.seizia.chromite.swing.product/<os>/<ws>/<arch>/`(Windows `chromite-swing/`, macOS `ChromiteSwing.app`)이다.

- `product/testing/ChromiteUnitTest.product`는 Maven 모듈이 아니다. 테스트 기반 클래스 `ChromiteUnitTest`의
  `@RunWithProduct("ChromiteUnitTest.product")`와 `osgi-app.properties`의 `productsPaths`(IDEA 작업 공간 생성기)가 이 파일을 쓴다.
- 2026-11-07 Chromite가 빌드하거나 배포하지 않는 제품을 지웠다(내부 이름 변경 계획 결정 4, 이름을 바꿀 대상을 줄이기 위해).

  | 삭제 | 내용 |
  |---|---|
  | `product/community-msstore`, 프로파일 `product-dbeaver-ce-msstore` | Microsoft Store 제품 |
  | `product/repositories`, 프로파일 `product-dbeaver-eclipse-ce` | Eclipse 플러그인 배포판과 확장 p2 저장소 4개(`ce`, `debug`, `git`, `office`) |
  | `product/snap-build` | Linux Snap 패키지 설정(Linux는 빌드하지 않는다, [runtime.md](runtime.md)) |
  | `features/com.seizia.chromite.ide.feature`, `plugins/com.seizia.chromite.ui.app.eclipse` | Eclipse 플러그인 배포판 feature와 번들 |
  | `features/com.seizia.chromite.msstore.feature`, `plugins/com.seizia.chromite.ui.app.msstore` | Microsoft Store feature와 번들 |
  | `ui.app.standalone/plugin.xml`의 `msstore` 애플리케이션·제품·스플래시 연결 | Microsoft Store 제품만 썼다 |
  | `IEnvironmentPathMapper`, `RuntimeUtils.isWindowsStoreApplication()`, `DBConstants.IS_WINDOWS_STORE_APP`, `NativeClientDescriptor`의 Store 경로 검사 | 유일한 구현과 속성 설정이 `ui.app.msstore` 번들에 있었다 |
  | `CoreMessages.pref_page_eclipse_ui_general_group_{general,label}`과 `CoreResources*.properties` 키 | Eclipse 플러그인 설정 페이지만 썼다 |
  | `osgi-app.properties`의 `optionalFeatureRepositories`, `tools/build.*`의 `product-dbeaver-eclipse-ce`, `appstore` 프로파일 | 지운 폴더와 없는 프로파일을 가리켰다 |

- 남긴 것: Office·Git·Debug 확장 feature(`com.seizia.chromite.{ext.office,git,debug}.feature`)와 그 번들은 그대로 빌드한다.
  이 feature들은 이제 어느 제품이나 p2 저장소에도 들어가지 않지만, 번들 일부(`data.office`, `bundle.apache.poi`)를 Swing 제품이 쓰고
  나머지는 SWT 확장 기능(PostgreSQL 디버거, Git, Office 내보내기 UI)이라, 지울지는 따로 정한다.
  → 같은 날 SWT 프런트엔드 삭제에서 세 feature와 SWT 번들을 지웠다. `data.office`는 Swing feature가 직접 포함한다([swt-removal.md](swt-removal.md)).

## 설계 결정과 이유

### 저장소 안에 넣은 이유

- Chromite는 DBeaver와 독립적으로 개발한다(CLAUDE.md "upstream 정책"). 옆 폴더에 upstream 저장소의 최신 `devel`을
  clone하는 방식은 빌드할 때마다 upstream 변경이 들어오고, Chromite 커밋과 의존 저장소 버전이 묶이지 않는다.
- 저장소 하나로 빌드되어야 CI와 다른 개발자 환경에서 같은 결과를 얻는다.

### `git subtree --squash`를 쓴 이유

- submodule과 달리 clone 한 번으로 모든 파일이 받아진다. 별도 명령(`git submodule update`)이 필요 없다.
- `--squash`로 upstream 이력 전체 대신 커밋 하나만 남겨 Chromite 이력을 깔끔하게 유지한다.
  가져온 upstream 커밋 ID는 squash 커밋 메시지에 남아 나중에 갱신할 때 기준이 된다.

### `deps/` 폴더에 둔 이유

- Chromite 코드(`plugins/`, `features/`, `product/`)와 외부에서 가져온 코드를 구분한다.
- 두 저장소를 같은 폴더 아래 형제로 두면 upstream의 상대 경로를 고치지 않아도 된다.
  `datadam-api`는 `dbeaver-common`이 옆 폴더에 있다고 가정한다.
  - `deps/datadam-api/apis/pom.xml`의 부모 `relativePath`: `../../dbeaver-common`
  - `deps/datadam-api/apis/aggregate/pom.xml`의 모듈: `../../../dbeaver-common`
- 그래서 가져온 파일을 하나도 고치지 않았고, 나중에 upstream 버전으로 갱신할 때 충돌이 생기지 않는다.

### `.mvn/` 폴더를 만들지 않은 이유

- `dbeaver-common`의 `.mvn/`에는 `wrapper/maven-wrapper.properties`만 있다. `extensions.xml`이나 `maven.config`는 없다.
- `mvnw`는 스크립트 자신의 위치에서 `.mvn/wrapper/maven-wrapper.properties`를 읽으므로 `deps/` 안에서도 그대로 동작한다.
- 어느 POM도 `${maven.multiModuleProjectDirectory}`를 쓰지 않는다. 그래서 Chromite 루트에 `.mvn/`이 필요 없다.

### `project.deps` 처리

- `project.deps`는 DBeaver의 CI(`dbeaver/github-actions/clone-repositories`)와 AGENTS.md "Repo dependencies"가
  옆 폴더에 clone할 저장소 목록으로 읽는 파일이다.
- Chromite 루트의 `project.deps`는 삭제했다. 의존 저장소가 저장소 안에 있으므로 clone할 대상이 없다.
  `.github/workflows/push-pr-devel.yml`에서도 `project-deps` 입력을 뺐다(이 워크플로는 나중에 Chromite 자체 CI로 바꾸면서 삭제했다, [ci.md](ci.md)).
- `deps/datadam-api/project.deps`는 그대로 두었다. 이 저장소에서 읽는 곳이 없고(하위 폴더의 `.github/workflows`는
  GitHub이 실행하지 않는다), upstream과 같게 유지해야 갱신할 때 충돌이 없다.

## upstream 대비 변경점

| 파일 | 변경 |
|---|---|
| `pom.xml` | 부모 `relativePath`: `../dbeaver-common/pom.xml` → `deps/dbeaver-common/pom.xml`. Tycho 대상 환경을 `win32/win32/x86_64`, `macosx/cocoa/aarch64`로 축소([runtime.md](runtime.md) "플랫폼별 사항") |
| `product/aggregate/pom.xml` | 모듈: `../../../dbeaver-common`, `../../../datadam-api/apis` → `../../deps/...` |
| `project.deps` | 삭제 |
| `.github/workflows/push-pr-devel.yml` | 삭제. Chromite 자체 워크플로 `.github/workflows/build.yml`로 대체([ci.md](ci.md)) |
| `tools/build.cmd`, `tools/build.sh` | 옆 폴더 clone 단계 삭제, `deps/dbeaver-common/mvnw`로 빌드 |
| `osgi-app.properties` | IDEA 작업 공간 생성기용 번들 경로를 `dbeaver/deps/...`로 변경. `optionalFeatureRepositories`(`product/repositories`) 삭제 |
| `product/pom.xml`, `features/pom.xml`, `plugins/pom.xml` | Microsoft Store, Eclipse 플러그인, Snap 제품과 그 feature·번들 삭제(위 "빌드하는 제품") |

## 갱신 방법 (사용자가 요청할 때만)

upstream을 정기적으로 가져오지 않는다(CLAUDE.md "upstream 정책"). 보안 패치나 빌드 문제로 갱신이 필요하고
사용자가 요청할 때만 아래처럼 갱신한다.

```bash
git subtree pull --prefix=deps/dbeaver-common --squash https://github.com/dbeaver/dbeaver-common devel
git subtree pull --prefix=deps/datadam-api   --squash https://github.com/dbeaver/datadam-api devel
```

- 갱신 전에 바뀌는 내용(특히 `root/pom.xml`의 Tycho·Eclipse 버전, P2 저장소 주소, 버전 번호)을 사용자에게 알린다.
- `com.dbeaver.common.main` 버전이 바뀌면 Chromite `pom.xml`의 `<parent>` 버전도 같이 바꾼다.
- 갱신한 뒤 이 문서의 커밋 ID 표와 `NOTICE`의 커밋 ID를 고치고, 변경 이력에 적는다.

## 남은 위험과 미완료 항목

- 빌드는 아직 실행해서 확인하지 않았다(2026-11-06 기준 Maven 미설치). 경로와 부모 POM 좌표는 파일을 읽어 확인했다.
- 빌드는 여전히 인터넷의 P2 저장소(`download.eclipse.org`, `repo.dbeaver.net/p2/ce/<버전>`)와 Maven Central을 쓴다.
  내장 런타임 단계는 JetBrains 다운로드 서버(`cache-redirector.jetbrains.com`)에서 JBR SDK를 받는다(캐시에 있으면 받지 않음).
  `deps/dbeaver-common/root/pom.xml`의 `repo.p2.dbeaver-ce.url`이 DBeaver의 서드파티 번들 저장소다.
- CI는 Chromite 자체 워크플로(`.github/workflows/build.yml`)가 이 문서의 빌드 명령을 Maven Wrapper로 실행한다([ci.md](ci.md)).
  DBeaver의 재사용 워크플로(`dbeaver/dbeaver-common/.github/workflows/*.yml@devel`)는 더 이상 호출하지 않는다.
- `tools/generate_workspace.*`(IDEA 작업 공간 생성)는 여전히 옆 폴더의 `idea-rcp-launch-config-generator`와
  `dbeaver-common/mvnw`를 쓰고, `osgi-app.properties`의 경로는 저장소 폴더 이름이 `dbeaver`라고 가정한다.

## 변경 이력

- 2026-11-06 `dbeaver-common`, `datadam-api`를 `git subtree --squash`로 `deps/`에 넣고, 빌드 경로를 저장소 안으로 변경.
  루트 `project.deps` 삭제. `NOTICE`에 출처 추가.
- 2026-11-06 루트 `pom.xml`의 Tycho 대상 환경을 Windows x64, macOS aarch64만 남기도록 축소. 제품 빌드가 Linux,
  Windows ARM, Intel Mac 제품을 더 이상 만들지 않는다.
- 2026-11-06 CI를 Chromite 자체 워크플로로 바꾸면서 `push-pr-devel.yml`을 삭제. CI 관련 설명을 [ci.md](ci.md)로 옮김.
- 2026-11-07 "내장 Java 런타임" 절 추가(`tools/jre/BundleJre.java`, [runtime.md](runtime.md)).
- 2026-11-07 쓰지 않는 제품 삭제: Microsoft Store(`community-msstore`), Eclipse 플러그인(`repositories`, `ide.feature`, `ui.app.eclipse`),
  Snap(`snap-build`)과 그것만 쓰던 코드·메시지 키. `tools/build.*`의 프로파일을 `product-chromite-swt`만 남김. "빌드하는 제품" 절 추가.
- 2026-11-07 SWT 프런트엔드 삭제([swt-removal.md](swt-removal.md)): 빌드 명령과 `tools/build.*`의 프로파일을 `product-chromite-swing`으로, "빌드하는 제품" 표에서 SWT 제품 삭제.
