# 내장 런타임과 플랫폼

## 목적

DataGrip처럼 사용자가 Java 런타임(JRE)을 따로 설치하지 않아도 Chromite를 바로 실행할 수 있게 한다.
또한 Java 앱의 단점으로 체감되는 느린 첫 실행, 큰 메모리 사용, 별도 런타임 필요 문제를 줄인다.

## 배경

- Java는 JVM(HotSpot)이 바이트코드를 해석하다가 자주 실행되는 코드를 실행 중에 기계어로 컴파일(JIT)한다.
  오래 실행되는 프로그램의 속도는 네이티브에 가깝지만, 첫 실행 속도와 메모리 사용량이 약점이다.
- DBeaver 코드와 JDBC 생태계를 쓰려면 Java를 유지해야 한다.
- 이 저장소에는 JRE를 내장하는 빌드 설정이 없다(2026-11-06 확인). DBeaver는 별도 빌드 저장소에서 JRE를 내장한다.
  제품 정의에는 macOS용 경로(`-vm ../Eclipse/jre/...`)만 남아 있다.

## 결정 사항

| 항목 | 결정 |
|---|---|
| 런타임 | JBR (JetBrains Runtime) 25 LTS |
| 크기 축소 | jlink로 필요한 모듈만 포함한 전용 런타임 생성 |
| 시작 속도 | CDS / AOT 캐시 아카이브를 빌드할 때 만들어 함께 배포 |
| 실행 파일 | Equinox 네이티브 실행 파일(`chromite.exe`, `Chromite.app`)이 설치 폴더의 `jre/`를 자동으로 사용 |
| 플랫폼 | Windows x64, macOS Apple Silicon(arm64) |
| 새 Swing 코드의 Java 기준 | Java 25 (권장) |

## 설계 결정과 이유

### JBR을 쓰는 이유

- DataGrip과 IntelliJ가 쓰는 OpenJDK 기반 런타임으로, Swing 화면 품질이 일반 OpenJDK보다 좋다
  (Windows·macOS 글꼴 렌더링, HiDPI, 사용자 정의 제목 표시줄).
- FlatLaf가 JBR의 사용자 정의 제목 표시줄을 지원한다.
- 라이선스는 GPLv2 + Classpath Exception이다. 앱과 함께 재배포할 수 있고, Chromite 코드에 GPL 의무가 생기지 않는다.
  JBR 라이선스를 동봉하고 소스 위치를 안내한다. JBR 자체를 수정하면 그 수정분은 공개해야 한다.

### Java 25를 쓰는 이유 (Java 27이 아닌 이유)

- Java 27은 2026-09-14에 출시된 최신 버전이지만 LTS가 아니다. 2027년 3월 Java 28이 나오면 업데이트가 끝난다.
- Java 25는 LTS이고, JBR도 LTS 기준으로 나온다(2026-11-06 기준 JBR 25.0.4).
- Java 21 대비 장점:
  - AOT 클래스 로딩·링크(JEP 483, Java 24)와 AOT 개선(JEP 514, 515, Java 25)으로 첫 실행이 빨라진다.
  - 압축 객체 헤더(JEP 519, Java 25 정식)로 메모리 사용이 줄어든다.
- 기존 실행 옵션에 이미 Java 23 이상용 옵션(`--sun-misc-unsafe-memory-access=allow`)이 있고,
  DBeaver 공식 배포판도 OpenJDK 25를 내장하므로 25에서 실행해도 문제가 생길 가능성은 낮다.
- 기존 SWT 코드는 지금처럼 Java 21 기준으로 컴파일하고, 새 Swing 코드는 25 기준으로 시작한다.

### jlink 모듈

- 예상 모듈: `java.desktop`(Swing), `java.sql`, `java.naming`, `java.security.jgss`(Kerberos·Windows 인증), `jdk.crypto.ec`, `jdk.unsupported` 등
- 정확한 목록은 `jdeps`로 실제 의존성을 분석해 정한다. JDBC 드라이버가 쓰는 모듈(예: `java.management`, `jdk.net`)도 확인한다.

### 쓰지 않는 방법: GraalVM Native Image

- Chromite는 연결할 때 JDBC 드라이버 jar를 동적으로 불러오고, 앱 자체도 OSGi 플러그인 구조다.
  Native Image는 실행 중 클래스 동적 로딩을 지원하지 않으므로 맞지 않는다.

## 플랫폼별 사항

| 항목 | Windows x64 | macOS arm64 |
|---|---|---|
| 내장 런타임 | JBR 25 windows-x64 | JBR 25 osx-aarch64 |
| 런타임 위치 | `<설치폴더>/jre/` | `Chromite.app/Contents/` 안 |
| 배포 형태 | 설치 프로그램(.exe/.msi) + zip (관리자 권한 없이 풀어 쓰는 폐쇄망 환경 고려) | .dmg |
| 서명 | 코드 서명 인증서 검토 (없으면 SmartScreen 경고) | Apple Developer 계정으로 서명 + 공증. 공증 결과를 붙여 두면(stapling) 폐쇄망에서도 실행 |
| 기타 | | 화면 상단 메뉴 막대, Cmd 단축키, 제목 표시줄 통합 |

- Linux, Intel Mac, Windows ARM은 지원하지 않는다. 그래서 빌드 대상 환경도 두 가지만 둔다.
  - 루트 `pom.xml`의 `target-platform-configuration` `<environments>`: `win32/win32/x86_64`, `macosx/cocoa/aarch64`.
    Chromite의 모든 플러그인, 기능, 제품 모듈의 부모 POM이라 여기서 정하면 제품 빌드(`product/community`)에도 적용된다.
    `deps/dbeaver-common/root/pom.xml`에는 대상 환경 설정이 없다.
  - 그래서 `product/community/target/products/org.jkiss.dbeaver.core.product/`에는 `win32/win32/x86_64`와
    `macosx/cocoa/aarch64` 폴더만 생긴다.
  - Linux·Solaris 전용 제품 설정(`DBeaver.product`의 `<linux icon>`, `<solaris/>`, `product/community/pom.xml`의
    `<linux>`, `<solaris>` 루트 폴더, `features/org.jkiss.dbeaver.ce.feature/build.properties`의 `root.linux.*`)과
    거기에만 쓰이던 아이콘(`product/community/icons/dbeaver.xpm`, `dbeaver.png`)은 삭제했다.
  - Linux 전용 SWT 프래그먼트 플러그인 `org.jkiss.dbeaver.ui.swt.linux`도 삭제했다(`plugins/pom.xml` 모듈,
    `org.jkiss.dbeaver.app.base.feature`의 `os="linux"` 항목 포함). `Eclipse-PlatformFilter`가 gtk/linux라 대상 환경에
    Linux가 없으면 Tycho가 의존성을 풀지 못하고("cannot be installed in this environment because its filter is not
    applicable"), 이 플러그인의 대상 환경만 Linux로 바꾸면 기능 패키징이 "was not found in the target platform"으로 실패한다.
    이 플러그인의 `DBeaverBrowserHelper`는 `BrowserClearCookiesHandler`가 클래스 이름으로 찾는데, Windows·macOS에는
    각각 `ui.swt.windows`, `ui.swt.macos`의 같은 이름 클래스가 있다.
  - 기능(feature)의 외부 Eclipse 번들 `os="linux"` 항목(`org.jkiss.dbeaver.rcp.feature`의 `org.eclipse.equinox.security.linux`,
    `org.eclipse.core.filesystem.linux.*`)은 빌드에 문제가 없고 제품에 들어가지 않아 그대로 두었다.
- Apple은 macOS 26 Tahoe가 Intel Mac을 지원하는 마지막 버전이라고 발표했다. 그래서 Apple Silicon만 지원한다.

## 미완료 항목

- `jdeps`로 필요한 모듈 목록 확정
- AOT 캐시 생성 절차를 빌드에 넣고 시작 시간 실측
- 기본 메모리 설정(`-Xmx1024m`) 재검토
- 런타임 업데이트를 앱 업데이트에 포함하는 방식

## 변경 이력

- 2026-11-06 최초 작성. JBR 25 + jlink 내장, 플랫폼(Windows x64, macOS arm64) 결정을 정리.
- 2026-11-06 빌드 대상 환경을 Windows x64, macOS aarch64 두 가지로 줄였다(루트 `pom.xml`). 그 전에는 Linux gtk
  x86_64/aarch64, Windows aarch64, macOS x86_64 제품도 함께 만들었다. Linux·Solaris 전용 제품 설정과 아이콘,
  Linux 전용 플러그인 `org.jkiss.dbeaver.ui.swt.linux` 삭제.
