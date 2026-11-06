# 내장 런타임과 플랫폼

## 목적

DataGrip처럼 사용자가 Java 런타임(JRE)을 따로 설치하지 않아도 Chromite를 바로 실행할 수 있게 한다.
또한 Java 앱의 단점으로 체감되는 느린 첫 실행, 큰 메모리 사용, 별도 런타임 필요 문제를 줄인다.

## 배경

- Java는 JVM(HotSpot)이 바이트코드를 해석하다가 자주 실행되는 코드를 실행 중에 기계어로 컴파일(JIT)한다.
  오래 실행되는 프로그램의 속도는 네이티브에 가깝지만, 첫 실행 속도와 메모리 사용량이 약점이다.
- DBeaver 코드와 JDBC 생태계를 쓰려면 Java를 유지해야 한다.
- 2026-11-06까지 이 저장소에는 JRE를 내장하는 빌드 설정이 없었다. DBeaver는 별도 빌드 저장소에서 JRE를 내장한다.
  제품 정의에는 macOS용 경로(`-vm ../Eclipse/jre/Contents/Home/lib/libjli.dylib`)만 남아 있었다.
  그래서 런처가 `PATH`의 Java를 골랐고, 이 PC처럼 `PATH`에 Oracle JDK 18이 있으면 `-vm` 없이는 실행되지 않았다.
- 2026-11-07부터 빌드가 JBR 25로 만든 런타임을 두 제품에 넣는다(아래 "구조").

## 결정 사항

| 항목 | 결정 |
|---|---|
| 런타임 | JBR (JetBrains Runtime) 25 LTS |
| 크기 축소 | jlink로 필요한 모듈만 포함한 전용 런타임 생성 |
| 시작 속도 | 기본 CDS 아카이브는 넣지 않음(측정상 차이 없음). AOT 캐시는 효과를 측정했고 넣을지는 미결정(아래 "시작 시간") |
| 실행 파일 | Equinox 네이티브 실행 파일이 내장 런타임을 사용. Windows는 실행 파일 옆 `jre/`를 자동으로 찾고, macOS는 `-vm ../Eclipse/jre/lib/libjli.dylib` |
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

### 쓰지 않는 방법: GraalVM Native Image

- Chromite는 연결할 때 JDBC 드라이버 jar를 동적으로 불러오고, 앱 자체도 OSGi 플러그인 구조다.
  Native Image는 실행 중 클래스 동적 로딩을 지원하지 않으므로 맞지 않는다.

## 구조

| 파일 | 하는 일 |
|---|---|
| `tools/jre/jbr.properties` | 고정한 JBR 버전·빌드, 다운로드 주소, 플랫폼별 SHA-256, jlink 모듈 목록과 옵션 |
| `tools/jre/BundleJre.java` | Maven 제품 빌드 뒤에 실행하는 Java 단일 파일 프로그램. JBR SDK를 받아(캐시, SHA-256 확인) `jmods`를 풀고, jlink로 런타임을 만들어 두 제품에 넣는다 |
| `product/community/DBeaver.product`, `product/swing/chromite-swing.product` | macOS 실행 옵션 `-vm ../Eclipse/jre/lib/libjli.dylib` (`<programArgsMac>`) |
| `.github/workflows/build.yml`, `release.yml` | Maven 빌드 뒤 `BundleJre.java` 실행, 다운로드 캐시, 구조 확인([ci.md](ci.md)) |
| `NOTICE` | JBR 출처, 라이선스(GPLv2 + Classpath Exception), 소스 위치 |

### 사용법

저장소 최상위에서, 빌드에 쓴 JDK 25로 실행한다.

```bash
mvn package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce,product-chromite-swing
java tools/jre/BundleJre.java windows-x64 osx-aarch64
```

- 인수는 넣을 플랫폼이다(`windows-x64`, `osx-aarch64`). 빌드된 제품(SWT, Swing) 중 있는 것에만 넣는다. 하나도 없으면 실패한다.
- 다운로드 위치: 환경 변수 `CHROMITE_JBR_CACHE`, 없으면 `~/.cache/chromite-jbr`(이 PC에서는 `D:\Tools\_downloads\jbr`로 지정).
  같은 SHA-256의 파일이 있으면 다시 받지 않는다. 받는 중에는 `.part` 이름으로 두고, 확인한 뒤에 이름을 바꾼다.
- 작업 폴더: `target/jre/<플랫폼>/jmods`(풀어 둔 jmod), `target/jre/<플랫폼>/image`(만든 런타임). `mvn clean`이 지운다.
- 넣는 위치: Windows `<제품 폴더>/jre/`, macOS `<앱>.app/Contents/Eclipse/jre/`. 있던 `jre/`는 지우고 새로 넣는다.
- `mvn package`만 실행하면 런타임이 없는 제품이 나온다. 이때는 지금까지처럼 설치된 JDK(SWT 21 이상, Swing 25 이상)로 실행된다.

## 고정한 JBR

| 항목 | 값 |
|---|---|
| 릴리스 | [jbr-release-25.0.4.1b508.41](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-25.0.4.1b508.41) (2026-10-27), 소스 태그 커밋 `74edaa21451b` |
| 종류 | JBRSDK (JCEF 없음) |
| windows-x64 | `jbrsdk-25.0.4.1-windows-x64-b508.41.tar.gz`, 292,308,483 바이트, SHA-256 `c477ba00c5d8ebc108a38b29eda533fe8d6e5fad534afc332c48addd9078f719` |
| osx-aarch64 | `jbrsdk-25.0.4.1-osx-aarch64-b508.41.tar.gz`, 239,972,251 바이트, SHA-256 `2aa9a2944224844105ed9adac704db0eee5fb82a13d6213e79a4551c5e67ae9b` |
| 받는 곳 | `https://cache-redirector.jetbrains.com/intellij-jbr/<파일 이름>` (릴리스 페이지의 링크) |

- 두 파일 모두 JetBrains가 올린 SHA-512(`<파일>.checksum`)와 일치하는 것을 확인한 뒤 SHA-256을 계산해 고정했다(2026-11-07).
- **JBRSDK를 쓰는 이유**: jlink는 `jmods/*.jmod`에서 런타임을 만든다. 릴리스의 "JBR (vanilla)" 파일(`jbr-...`)은 이미 jlink로
  만든 런타임이라 `jmods`가 없다. JDK 24부터 jmods 없이 실행 중인 런타임에서 링크하는 기능(JEP 493)이 있지만 같은 플랫폼에서만 되므로,
  Linux 러너에서 macOS 런타임을 만들 수 없다.
- **JCEF 없는 판을 쓰는 이유**: JCEF(내장 Chromium)는 IntelliJ의 웹 화면용이다. Chromite는 쓰지 않는다(SWT 제품의 브라우저는 SWT가
  Windows WebView2·macOS WebKit을 쓴다). JCEF 판은 훨씬 크다.
- **b508 계열을 고른 이유**: 25.0.4.1이 b329, b508, b635 세 계열로 나온다(IntelliJ 제품 계열별). 이 PC의 빌드용 JDK
  (`D:\Tools\jbrsdk-25`, `JBR-25.0.4.1+1-508.41`)와 같은 빌드라서 빌드 JDK와 실행 런타임이 같다.
- jlink는 자기와 같은 기능 릴리스(25)의 jmod만 링크한다. 그래서 `BundleJre.java`는 실행 중인 JDK가 25인지 먼저 확인한다.
  CI의 JDK(`actions/setup-java`, JBR 25 최신)는 빌드 번호가 다를 수 있지만 25이면 링크할 수 있고, 런타임 내용은 고정한 jmod에서 나온다.

## 런타임을 제품에 넣는 방식

Eclipse 제품에 JRE를 넣는 방법을 비교했다.

| 방법 | 판단 |
|---|---|
| Tycho 제품의 `includeJRE` (Tycho 5.0.4 `tycho-p2-publisher-plugin`, `tycho-p2-director-plugin`의 `productRepository`) | 쓰지 않음. Eclipse JustJ 저장소(`download.eclipse.org/justj/jres`)의 Temurin 런타임만 넣는다. JBR을 쓸 수 없고 모듈을 고를 수 없다 |
| 기능(feature)의 root 파일(`root.win32.win32.x86_64.folder.jre=...`) | 쓰지 않음. p2가 root 파일 묶음을 제품 안 `p2/org.eclipse.equinox.p2.core/cache/binary/`에 한 벌 더 보관한다. 로컬 빌드에서 `drivers.public.feature`의 root 파일(19.8MB)이 이렇게 두 번 들어 있는 것을 확인했다. 런타임이면 압축본 약 54MB가 제품마다 더 들어간다 |
| **Maven 빌드 뒤 `jre/` 폴더에 복사 (채택)** | Eclipse 런처가 찾는 위치(`jre/`)에 넣는다. p2는 이 폴더를 모른다. Chromite는 p2 업데이트를 쓰지 않으므로([signing.md](signing.md) "남은 위험") 문제가 없다. 앱 업데이트는 설치 파일 전체를 바꾸므로 런타임도 함께 바뀐다 |

- 내부 이름 변경 작업(`chromite/internal-rename`)과 겹치지 않도록 pom은 고치지 않았다. 제품 파일은 macOS `-vm` 부분만 고쳤다.
- `BundleJre.java`를 Java 단일 파일 프로그램으로 만든 이유: 빌드에 어차피 필요한 JDK 25만으로 Windows·Linux·macOS에서 같게 돈다.
  tar.gz는 JDK에 API가 없어서 ustar 형식을 직접 읽는다. GNU 긴 이름과 pax `path`만 처리하고 `jmods/` 아래 `.jmod` 파일 이름만 쓰므로
  압축 파일 안의 경로가 작업 폴더 밖을 가리킬 수 없다. jlink는 `ToolProvider`로 같은 프로세스에서 부른다.
- 출력은 표준 출력으로 한다. 제품 코드가 아니라 빌드 도구라서 `org.jkiss.dbeaver.Log`를 쓰지 않는다.

## 실행 파일이 런타임을 찾는 방식

- **Windows**: `-vm`을 두지 않는다. Eclipse 런처는 `-vm`이 없으면 먼저 실행 파일 옆의 `jre\bin\javaw.exe`
  (콘솔 런처 `chromitec.exe`는 `java.exe`)를 찾고, 없을 때만 `PATH`를 본다. 찾은 실행 파일 옆의 `server\jvm.dll`을 같은 프로세스에 불러온다.
  - 이 PC에서 `PATH` 앞쪽에 Oracle JDK 18(`C:\Program Files\Common Files\Oracle\Java\javapath`)을, `JAVA_HOME`에도 JDK 18을 둔 채
    두 제품을 실행해 프로세스가 불러온 모듈이 `<제품>\jre\bin\server\jvm.dll`, `jre\bin\java.dll`인 것을 확인했다(아래 "확인한 것").
    런처 소스를 읽어서가 아니라 이 실행 결과로 판단했다.
- **macOS**: `-vm ../Eclipse/jre/lib/libjli.dylib`. 상대 경로는 `Contents/Eclipse/jre`를 가리킨다
  (Eclipse IDE macOS 배포판이 JustJ 런타임을 `../Eclipse/plugins/.../jre/lib/libjli.dylib`로 지정하는 것과 같은 방식).
  - DBeaver에서 온 원래 값은 `../Eclipse/jre/Contents/Home/lib/libjli.dylib`였다. DBeaver는 JDK 번들 폴더 구조(`Contents/Home`)를 통째로 넣는다.
    jlink 결과는 평평한 폴더(`bin/`, `lib/`, `conf/`, `legal/`)라서 Windows와 같은 구조로 두고 경로를 바꿨다. Swing 제품에는 `-vm`이 없었다.
  - Mac에서 실행해 확인하지는 못했다. CI는 구조(`lib/libjli.dylib`, arm64 `libjvm.dylib`, 실행 권한)만 확인한다.
- macOS 실행 권한: p2가 만드는 파일이 아니므로 `BundleJre.java`가 POSIX 호스트(CI의 Linux)에서 `bin/*`, `lib/jspawnhelper`를 755로 둔다.
  `jspawnhelper`는 macOS에서 `ProcessBuilder`가 자식 프로세스를 띄울 때 거치는 실행 파일이라 권한이 없으면 외부 프로그램(백업·복원 도구 등) 실행이 실패한다.
  Windows에서 만든 macOS 제품에는 권한이 없다(런처와 같은 제약, [ci.md](ci.md)). 배포용이 아니다.
- JBR의 macOS 바이너리는 ad-hoc 서명만 되어 있다(`macos_natives.py scan`, Mach-O 39개). jlink는 네이티브 파일을 바꾸지 않으므로 서명하지 않은
  CI 결과물도 Apple Silicon에서 실행할 수 있고, 릴리스는 [signing.md](signing.md)의 서명 단계가 우리 서명으로 바꾼다.

## 모듈

`jbr.properties`의 `modules`. 근거는 2026-11-07 로컬 빌드의 두 제품(Windows, macOS) jar 404개(중첩 jar 포함, 중복 제거)에 대해
`jdeps --multi-release 25 --ignore-missing-deps -s`를 jar마다 실행한 결과다. 모듈 jar(`module-info.class`)가 다른 모듈을 찾지 못해
실패한 18개는 `module-info.class`를 뺀 사본으로 다시 돌렸다.

| 모듈 | 넣은 이유 |
|---|---|
| `java.se` | Java SE 표준 모듈 전체(20개). jdeps가 직접 찾은 표준 모듈은 16개(`java.base`, `java.xml`, `java.sql`, `java.desktop`, `java.logging`, `java.management`, `java.security.jgss`, `java.naming`, `java.net.http`, `java.datatransfer`, `java.transaction.xa`, `java.scripting`, `java.prefs`, `java.xml.crypto`, `java.sql.rowset`, `java.security.sasl`)이고, 나머지 4개(`java.compiler`, `java.instrument`, `java.management.rmi`, `java.rmi`)는 합쳐 0.45MB다. Chromite는 연결할 때 JDBC 드라이버를 내려받아 불러오므로, jdeps로 미리 볼 수 없는 드라이버가 표준 API를 쓰다 `NoClassDefFoundError`로 실패하지 않게 전체를 넣었다 |
| `jdk.charsets` | `MS949`(`x-windows-949`), `EUC-KR`, `ISO-2022-KR`, `Shift_JIS`, `GBK` 같은 확장 문자 집합. `java.base`에는 표준 문자 집합만 있다. 서비스로 찾으므로 jdeps에 나오지 않는다. 한국어 DB·파일 인코딩에 필요하다 |
| `jdk.localedata` | 한국어 등 영어 외 로케일의 날짜·숫자 형식(CLDR). `java.base`에는 영어만 있다. `--include-locales=en,ko`로 줄이면 10MB 작아지지만, 데이터 형식 설정에서 다른 로케일을 고르면 틀린 형식이 나오므로 모두 넣었다 |
| `jdk.naming.dns` | JNDI DNS 공급자. Kerberos가 KDC·realm을 DNS SRV로 찾을 때, `mssql-jdbc`가 Kerberos SPN의 realm을 찾을 때 `InitialDirContext`로 쓴다. 클래스 이름(`com.sun.jndi.dns.DnsContextFactory`)으로 불러오므로 jdeps에 나오지 않는다 |
| `jdk.security.auth` | jdeps: `org.jkiss.dbeaver.model`. JAAS `Krb5LoginModule` 등 |
| `jdk.security.jgss` | jdeps: `ojdbc11`. Kerberos·GSS 확장 |
| `jdk.net` | jdeps: `ojdbc11`, `mariadb`, Apache HttpClient 5(확장 소켓 옵션) |
| `jdk.httpserver` | jdeps: `msal4j`(Azure AD 대화형 로그인의 로컬 콜백), `org.jkiss.dbeaver.model.cli`, `org.jkiss.dbeaver.ui`, `org.jkiss.utils` |
| `jdk.xml.dom` | jdeps: Eclipse e4 CSS·테마 번들(SWT 제품) |
| `jdk.unsupported` | jdeps: Guava(`sun.misc.Unsafe`). 실행 옵션 `--sun-misc-unsafe-memory-access=allow`가 다루는 모듈이고, 내려받는 드라이버도 리플렉션으로 쓴다 |
| `jdk.zipfs` | zip·jar 파일 시스템 공급자(`FileSystems.newFileSystem`). 서비스로 찾는다. 런처(`DBeaverLauncher.java` 2852행)가 "이전 내장 런타임에는 `jdk.zipfs`가 없었다"며 따로 처리할 만큼 쓰이는 경로다 |
| `jdk.accessibility` | Windows Java Access Bridge(화면 낭독기가 Swing 화면을 읽음). Swing 제품의 접근성 |
| `jdk.crypto.mscapi` (Windows만) | `WINDOWS-ROOT` 키 저장소. 설정 "Windows 신뢰 저장소 사용"(`ModelPreferences.PROP_USE_WIN_TRUST_STORE_TYPE`)이 켜져 있으면 `DBeaverApplication.java` 337행이 `javax.net.ssl.trustStoreType=WINDOWS-ROOT`로 둔다. 모듈이 없으면 모든 TLS 연결이 실패한다. macOS JBR에는 이 모듈이 없다(macOS 키체인은 `java.base`의 `KeychainStore`) |

넣지 않은 모듈:

| 모듈 | 이유 |
|---|---|
| `jdk.crypto.ec` | JDK 22부터 빈 모듈이다(`jmod list`: `module-info.class`와 `legal/`뿐). 타원 곡선 암호는 `java.base`의 SunEC에 있다 |
| `jdk.crypto.cryptoki` | PKCS#11(하드웨어 토큰). 코드·jdeps 어디에도 쓰는 곳이 없다. `java.security`의 공급자 목록에 있지만 모듈이 없으면 건너뛴다 |
| `jdk.management`, `jdk.management.agent`, `jdk.jfr`, `jdk.attach`, `jdk.jdwp.agent` | 진단·원격 관리·디버거 연결용. jdeps 결과에 없다. 필요하면 개발용 JDK로 실행한다 |
| `jdk.jsobject`, `jdk.unsupported.desktop`, `jdk.dynalink` | JavaFX·Nashorn용. 쓰는 곳이 없다 |
| `java.smartcardio` | `java.se`에 들어 있지 않고 쓰는 곳이 없다 |
| 컴파일러·개발 도구(`jdk.compiler`, `jdk.jlink`, `jdk.jshell` 등) | 실행에 필요 없다 |

jlink 옵션: `--strip-debug --no-man-pages --no-header-files --compress=zip-6`. `--generate-cds-archive`는 쓰지 않는다(아래 "시작 시간").

## 크기

2026-11-07 로컬 빌드. zip은 Deflate 6단계로 묶은 크기다(배포 zip과 같은 방식은 아니지만 비교용).

| 제품 | 런타임 설치 크기 | 런타임 zip | 제품 설치 크기 (전 → 후) | 제품 zip (전 → 후) |
|---|---|---|---|---|
| Windows SWT `chromite/` | 76.6MB | 53.7MB | 148.5MB → 225.1MB | 132.2MB → 186.0MB |
| Windows Swing `chromite-swing/` | 76.6MB | 53.7MB | 122.1MB → 198.7MB | 108.5MB → 162.3MB |
| macOS SWT `Chromite.app` | 79.3MB | 52.7MB | 147.3MB → 226.6MB | 131.5MB → 184.2MB |
| macOS Swing `ChromiteSwing.app` | 79.3MB | 52.7MB | 121.4MB → 200.7MB | 108.4MB → 161.1MB |

- 참고: 같은 JBR SDK 전체는 설치 크기 481MB이고 `lib/modules`가 66MB인데, 줄인 런타임의 `lib/modules`는 40MB다.
- jlink 기본 CDS 아카이브를 넣으면 Windows 런타임이 `classes.jsa` 31MB, `classes_nocoops.jsa` 32MB만큼 커진다(zip으로 약 8MB씩).

## 시작 시간 (CDS, AOT 캐시)

Swing 제품, 이 PC(논리 프로세서 12개). 프로세스 시작부터 주 창(제목 "Chromite")이 뜰 때까지의 경과 시간과 그 시점의 프로세스 CPU 시간.
세 경우를 번갈아 6번씩 실행했다. 다른 에이전트의 빌드로 CPU 사용률이 40~100%였기 때문에 경과 시간은 흔들림이 크다. CPU 시간이 더 믿을 만하다.

| 경우 | 경과 시간(ms) 6회 | 중앙값 | CPU 시간(ms) 6회 | 중앙값 |
|---|---|---|---|---|
| 기본 CDS 아카이브 있음 (`--generate-cds-archive`) | 3387, 4606, 4973, 10679, 4439, 5965 | 약 4790 | 7406, 8359, 7703, 8719, 7844, 7766 | 약 7805 |
| CDS 끔 (`-Xshare:off`) | 3442, 4259, 6906, 5706, 4564, 5732 | 약 5135 | 7312, 7875, 8734, 7812, 8219, 8062 | 약 7968 |
| AOT 캐시 (`-XX:AOTCache`, 학습 1회) | 3326, 2830, 4181, 11532, 6197, 3381 | 약 3780 | 7266, 7031, 6922, 7469, 7453, 7219 | 약 7240 |

- **기본 CDS 아카이브는 넣지 않았다.** 차이가 흔들림 안에 있다(CPU 중앙값 약 2%). 대부분의 시간은 JDK 핵심 클래스가 아니라 OSGi 번들의
  클래스 로딩과 JIT에 쓰인다. 반면 Windows 런타임을 31MB(설치) 키운다. 또 jlink는 자기가 실행 중인 플랫폼용으로만 만들 수 있어서
  Linux에서 만드는 macOS 런타임에는 어차피 넣을 수 없다. 넣지 않으면 어느 OS에서 링크해도 같은 런타임이 나온다.
- **AOT 캐시(JDK 25, JEP 483·514·515)는 효과가 있다.** CPU 시간 약 7%, 경과 시간 중앙값 약 20% 줄었다. 학습 실행
  (`-XX:AOTCacheOutput=<파일>`, 앱을 열었다 닫음)으로 만든 캐시는 58MB였고, JDK 클래스뿐 아니라 Equinox·Felix·Chromite 번들 클래스도 담겼다(`-Xlog:aot` 로그).
- **제안(구현하지 않음)**: AOT 캐시는 정확히 같은 런타임과 같은 클래스 경로에서만 쓰인다(다르면 JVM이 경고하고 캐시 없이 실행).
  그래서 빌드한 제품마다 대상 OS에서 학습 실행을 해야 한다. Windows는 CI Windows 작업에서 앱 창을 띄울 수 있는지, macOS는 서명 작업(`macos-15`)에서
  서명 전에 학습할 수 있는지 확인이 필요하다. 캐시 58MB(제품마다)와 그 확인 비용을 감수할지 정한 뒤 넣는다. 사용자 PC에서 처음 실행할 때 만드는 방식은
  ini에 사용자별로 쓸 수 있는 경로를 줄 방법이 없어서(런처가 `-vmargs` 안의 변수를 바꾸지 않음) 쓰지 않는다.

## 확인한 것

2026-11-07, 이 PC(Windows 11 x64). 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C '-Pproduct-dbeaver-ce,product-chromite-swing'`
(BUILD SUCCESS, 6분 51초) 뒤 `java tools/jre/BundleJre.java windows-x64 osx-aarch64`.

- `PATH`에 Oracle JDK 18을 먼저 두고 `JAVA_HOME`도 JDK 18로 둔 채 `-vm` 없이 실행했다.
  - Swing 제품, SWT 제품 모두 주 창이 뜨고, 프로세스가 불러온 `jvm.dll`, `java.dll`이 각 제품의 `jre\bin\` 아래 파일이었다.
  - 두 제품 모두 한국어 화면(메뉴 "파일", "편집" 등)으로 떴다(창 캡처). SWT 로그에 `Java version: 25.0.4.1 by JetBrains s.r.o.`.
  - SWT 제품은 명령줄 연결(`-con`, 저장하지 않는 임시 연결)로 로컬 시험용 PostgreSQL 17의 이 작업용 DB(`chr_jbr_runtime`)에 연결해
    SQL 편집기가 `public@chr_jbr_runtime`으로 열렸다. 로그에 오류가 없다.
- 제품의 `jre\bin\java.exe`로 제품에 든 드라이버(`drivers/`)를 써서 확인했다.
  PostgreSQL 17(SCRAM 인증, 한국어 데이터 "안녕하세요, 크로마이트"), SQL Server 2022(`encrypt=true`, 연결의 `encrypt_option=TRUE`),
  MariaDB 11.8 연결, `MS949`·`EUC-KR` 문자 집합, 한국어 날짜·통화 형식("1970년 1월 1일 목요일", "₩1,234,567"),
  `WINDOWS-ROOT` 키 저장소(인증서 74개), JNDI DNS, zip 파일 시스템, `Krb5LoginModule`, `com.sun.net.httpserver` 모두 통과.
- macOS 런타임(이 PC에서 교차 링크): `lib/libjli.dylib`, `lib/server/libjvm.dylib`, `bin/java`가 arm64 Mach-O(`file`), 두 앱의 ini에
  `-vm ../Eclipse/jre/lib/libjli.dylib`. CI Linux 작업의 결과물은 [ci.md](ci.md)의 확인 단계로 본다.
- 확인하지 못함: Mac에서 실행, 서명·공증된 앱의 실행.
- 회귀 테스트는 실행하지 않았다.

## 갱신 절차

새 JBR 25 빌드로 바꿀 때(보안 업데이트 등). 사용자가 요청할 때 한다.

1. [JBR 릴리스 목록](https://github.com/JetBrains/JetBrainsRuntime/releases)에서 같은 계열(지금 b508)의 새 `jbr-release-25.0.x...` 태그를 고른다.
2. 두 파일(`jbrsdk-<버전>-windows-x64-<빌드>.tar.gz`, `jbrsdk-<버전>-osx-aarch64-<빌드>.tar.gz`)과 `.checksum`을 받아
   `sha512sum -c`로 확인하고, `sha256sum`으로 SHA-256을 구한다.
3. `tools/jre/jbr.properties`의 `jbr.version`, `jbr.build`, `windows-x64.sha256`, `osx-aarch64.sha256`을 바꾼다.
4. 새 JDK에서 모듈이 바뀌었는지 본다(`jmod list`, `java --list-modules`). 필요한 모듈이 바뀌면 이 문서의 "모듈" 표와 함께 고친다.
5. 빌드 후 `BundleJre.java`를 실행하고 위 "확인한 것"을 다시 한다. 이 문서의 "고정한 JBR"과 `NOTICE`의 버전·소스 링크를 고친다.
6. CI 다운로드 캐시 키는 `jbr.properties`의 해시라서 바꾸면 자동으로 새로 받는다.

## 플랫폼별 사항

| 항목 | Windows x64 | macOS arm64 |
|---|---|---|
| 내장 런타임 | JBR 25 windows-x64 | JBR 25 osx-aarch64 |
| 런타임 위치 | `<설치폴더>/jre/` | `<앱>.app/Contents/Eclipse/jre/` |
| 배포 형태 | 설치 프로그램(.exe/.msi) + zip (관리자 권한 없이 풀어 쓰는 폐쇄망 환경 고려) | .dmg |
| 서명 | Azure Artifact Signing으로 CI에서 서명 ([signing.md](signing.md)) | Developer ID 서명 + 공증, CI에서 실행 ([signing.md](signing.md)). 공증 결과를 붙여 두면(stapling) 폐쇄망에서도 실행 |
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

- AOT 캐시를 넣을지 결정하고, 넣으면 플랫폼별 학습 실행을 CI에 넣기(위 "시작 시간")
- Mac에서 내장 런타임으로 실행 확인(서명·공증한 앱 포함)
- 기본 메모리 설정(`-Xmx1024m`) 재검토
- 런타임 업데이트를 앱 업데이트에 포함하는 방식(지금은 설치 파일 전체 교체)
- `tools/build.cmd`, `tools/build.sh`는 Maven만 실행한다. 런타임까지 넣으려면 `BundleJre.java`를 따로 실행한다

## 변경 이력

- 2026-11-06 최초 작성. JBR 25 + jlink 내장, 플랫폼(Windows x64, macOS arm64) 결정을 정리.
- 2026-11-06 빌드 대상 환경을 Windows x64, macOS aarch64 두 가지로 줄였다(루트 `pom.xml`). 그 전에는 Linux gtk
  x86_64/aarch64, Windows aarch64, macOS x86_64 제품도 함께 만들었다. Linux·Solaris 전용 제품 설정과 아이콘,
  Linux 전용 플러그인 `org.jkiss.dbeaver.ui.swt.linux` 삭제.
- 2026-11-06 플랫폼별 "서명" 항목을 CI 서명 릴리스(`release.yml`, [signing.md](signing.md))에 맞게 고쳤다.
- 2026-11-07 내장 런타임 구현. JBR SDK 25.0.4.1 b508.41을 고정(SHA-256)하고 `tools/jre/BundleJre.java`가 jlink로 줄인 런타임을
  두 제품의 `jre/`(macOS `Contents/Eclipse/jre/`)에 넣는다. jdeps로 모듈 목록을 정하고 근거를 적었다. macOS `-vm`을
  `../Eclipse/jre/lib/libjli.dylib`로 바꾸고 Swing 제품에도 넣었다. CI·릴리스 워크플로에 단계 추가. CDS·AOT 시작 시간을 측정해
  기본 CDS 아카이브는 넣지 않기로 했다. "jlink 모듈" 절을 "모듈"로 바꿨다.
