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
mvn package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce
```

`tools/build.cmd`, `tools/build.sh`는 Maven을 설치하지 않아도 되도록 `deps/dbeaver-common`의 Maven Wrapper(`mvnw`)로
전체 빌드를 실행한다(`mvnw`가 Maven 3.9.16을 받아서 쓴다. 프로파일은 스크립트 안에 적힌 대로다).

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
  `.github/workflows/push-pr-devel.yml`에서도 `project-deps` 입력을 뺐다.
- `deps/datadam-api/project.deps`는 그대로 두었다. 이 저장소에서 읽는 곳이 없고(하위 폴더의 `.github/workflows`는
  GitHub이 실행하지 않는다), upstream과 같게 유지해야 갱신할 때 충돌이 없다.

## upstream 대비 변경점

| 파일 | 변경 |
|---|---|
| `pom.xml` | 부모 `relativePath`: `../dbeaver-common/pom.xml` → `deps/dbeaver-common/pom.xml`. Tycho 대상 환경을 `win32/win32/x86_64`, `macosx/cocoa/aarch64`로 축소([runtime.md](runtime.md) "플랫폼별 사항") |
| `product/aggregate/pom.xml` | 모듈: `../../../dbeaver-common`, `../../../datadam-api/apis` → `../../deps/...` |
| `project.deps` | 삭제 |
| `.github/workflows/push-pr-devel.yml` | `project-deps` 입력 삭제, `project-directory`를 `./chromite/product/aggregate`로 변경 (CI는 저장소 이름 폴더에 checkout한다) |
| `tools/build.cmd`, `tools/build.sh` | 옆 폴더 clone 단계 삭제, `deps/dbeaver-common/mvnw`로 빌드 |
| `osgi-app.properties` | IDEA 작업 공간 생성기용 번들 경로를 `dbeaver/deps/...`로 변경 |

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
  `deps/dbeaver-common/root/pom.xml`의 `repo.p2.dbeaver-ce.url`이 DBeaver의 서드파티 번들 저장소다.
- CI(`push-pr-devel.yml`)는 DBeaver의 재사용 워크플로(`dbeaver/dbeaver-common/.github/workflows/*.yml@devel`)를 그대로 호출한다.
  재사용 워크플로는 저장소 최상위 `.github/workflows`에 있어야 하므로 `deps/` 안의 사본은 쓸 수 없다.
- `tools/generate_workspace.*`(IDEA 작업 공간 생성)는 여전히 옆 폴더의 `idea-rcp-launch-config-generator`와
  `dbeaver-common/mvnw`를 쓰고, `osgi-app.properties`의 경로는 저장소 폴더 이름이 `dbeaver`라고 가정한다.

## 변경 이력

- 2026-11-06 `dbeaver-common`, `datadam-api`를 `git subtree --squash`로 `deps/`에 넣고, 빌드 경로를 저장소 안으로 변경.
  루트 `project.deps` 삭제. `NOTICE`에 출처 추가.
- 2026-11-06 루트 `pom.xml`의 Tycho 대상 환경을 Windows x64, macOS aarch64만 남기도록 축소. 제품 빌드가 Linux,
  Windows ARM, Intel Mac 제품을 더 이상 만들지 않는다.
