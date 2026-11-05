# JDBC 드라이버 내장

## 목적

Chromite 사용자(특히 고객사 폐쇄망에서 작업하는 팀)가 인터넷 없이도 바로 DB에 연결할 수 있도록,
JDBC 드라이버를 설치 파일에 미리 넣는다.

## 현재 동작 (2026-11-06 코드 확인)

- **드라이버 정의:** `plugin.xml`에 드라이버 192개가 정의돼 있다. jar 파일 참조는 다음과 같다.
  - Maven 좌표(`maven:/...`) 150개 (중복 제외 110개)
  - 직접 다운로드 URL(`https:`, Athena·BigQuery zip 등) 11개
  - 앱 번들(`bundle:`) 7개, `repo:` 4개
- **다운로드:** 파일이 없으면 Maven Central(`https://repo1.maven.org/maven2/`)에서 받아 사용자 폴더 캐시에 저장한다
  ([registry/plugin.xml:132](../../plugins/org.jkiss.dbeaver.registry/plugin.xml:132)).
- **메모리 로딩은 이미 지연 방식이다:** [DriverLoaderDescriptor.loadDriver](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverLoaderDescriptor.java:151)는
  처음 연결할 때만 해당 드라이버 jar로 전용 클래스로더를 만든다. jar를 모두 내장해도 쓰지 않는 드라이버는 메모리에 올라가지 않는다.
- **로컬 Maven 저장소:** [MavenRegistry](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/maven/MavenRegistry.java)는
  설치 폴더의 `maven-local`을 로컬 저장소로 등록한다. upstream은 원격 저장소를 모두 시도한 뒤 **마지막에** 찾았다.
  Chromite는 2026-11-06부터 **가장 먼저** 찾는다(아래 "구현").
- **상대 경로 파일:** [DriverLibraryLocal.detectLocalFile](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverLibraryLocal.java:175)는
  `drivers/oracle` 같은 상대 경로를 설치 폴더에서 먼저 찾고, 없으면 사용자 드라이버 폴더에서 찾는다.
- **드라이버 묶음(product bundles):** 많은 드라이버가 파일을 두 가지로 정의한다.

  ```xml
  <file type="jar" path="maven:/com.oracle.database.jdbc:ojdbc11:RELEASE[23.2.0.0]" bundle="!drivers.oracle"/>
  <file type="jar" path="drivers/oracle" bundle="drivers.oracle"/>
  ```

  앱이 `org.jkiss.dbeaver.product.bundles` 확장 포인트로 `drivers.oracle` 묶음을 선언하면 설치 폴더의 `drivers/oracle`을 쓰고,
  선언하지 않으면 Maven에서 받는다([ProductBundleRegistry](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/ProductBundleRegistry.java)).
  이런 대체 정의가 있는 묶음은 약 45개다(`drivers.mssql.new`, `drivers.oracle`, `drivers.postgresql`, `drivers.mariadb`, `drivers.mysql`, `drivers.db2` 등).
  upstream 저장소에는 묶음 선언이 없다. Chromite 공개판은 4개를 선언한다(아래 "구현").
- **폴더 경로 해석:** `drivers/oracle` 같은 상대 경로는 설치 폴더 기준으로 먼저 찾는다
  ([DriverLibraryLocal.detectLocalFile](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverLibraryLocal.java:175)).
  경로가 폴더이면 그 폴더 **바로 아래의** `.jar`·`.zip` 파일만 클래스패스에 넣는다(하위 폴더는 보지 않음,
  [DriverLoaderDescriptor.readJarsFromDir](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverLoaderDescriptor.java)).
- **묶음을 선언하면 Maven 대체 파일은 사라진다:** `bundle="!drivers.X"`인 파일은 목록에서 빠지므로
  ([DriverUtils.matchesBundle](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverUtils.java)),
  묶음을 선언하고 폴더를 채우지 않으면 그 드라이버는 내려받을 곳도 없어진다. 묶음 선언과 폴더는 반드시 같은 feature로 배포한다.

## 설계

### 배포판 구성

| 구분 | 공개판 | 내부판 |
|---|---|---|
| 내장 드라이버 | SQL Server, Oracle, PostgreSQL, MariaDB | 전체 |
| MySQL | MariaDB 드라이버로 연결. Connector/J는 재배포 가능으로 확인됐으나 포함 여부 미결정 | Connector/J 포함 |
| 그 밖의 드라이버 | 인터넷이 되면 Maven에서 온라인 다운로드. 폐쇄망에서는 jar 파일을 직접 지정 | 모두 내장, 원격 다운로드 끔 |

### 내장 방법

1. **드라이버 묶음이 있는 드라이버:** 배포판별 묶음 선언 파일에 해당 묶음 ID를 선언하고, 빌드할 때 `drivers/<이름>/` 폴더에 jar를 넣는다.
2. **묶음이 없는 드라이버(내부판):** 빌드할 때 Maven 아티팩트와 의존 라이브러리를 받아 `<설치폴더>/maven-local`에 Maven 구조로 넣는다.
   URL로 받는 zip은 `<설치폴더>/drivers/`에 넣는다.
3. **판 구분:** 공개판과 내부판의 차이는 묶음 선언 목록과 포함할 폴더 목록뿐이다.
   판마다 "묶음 선언 플러그인 + 그 폴더를 채우는 빌드 설정" 한 쌍을 두고, 제품이 어느 쌍을 포함할지로 판을 고른다(아래 "구현").

### 오프라인 우선 조회

- `MavenRegistry`에서 로컬 저장소(`maven-local`)를 **가장 먼저** 찾도록 바꾼다.
  지금처럼 원격을 먼저 시도하면 폐쇄망에서 시간 초과까지 기다리게 된다.
- 원격 다운로드를 끄는 오프라인 설정을 추가한다. 내부판은 기본값을 켬, 공개판은 끔으로 한다.

### 버전 고정

- 일부 드라이버는 버전 없이 `RELEASE`(최신)로 정의돼 있다(예: `com.mysql:mysql-connector-j:RELEASE`, `com.teradata.jdbc:terajdbc:RELEASE`).
  최신 버전을 알려면 원격 조회가 필요하므로 오프라인에서는 버전을 정할 수 없다.
- 내장할 때 버전을 고정하고, `maven-local`에 `maven-metadata.xml`을 함께 만든다.

### Swing UI

- "드라이버 다운로드" 대화상자 대신 내장 드라이버 사용 여부와 파일 위치를 보여 준다.
- 내장되지 않은 드라이버는 jar 파일을 직접 지정하는 화면을 제공한다.

## 구현 (공개판, 2026-11-06)

### 구조

| 위치 | 역할 |
|---|---|
| [plugins/com.seizia.chromite.drivers.public/plugin.xml](../../plugins/com.seizia.chromite.drivers.public/plugin.xml) | `org.jkiss.dbeaver.product.bundles`로 `drivers.mssql.new`, `drivers.oracle`, `drivers.postgresql`, `drivers.mariadb` 선언. 코드 없음 |
| [features/com.seizia.chromite.drivers.public.feature/feature.xml](../../features/com.seizia.chromite.drivers.public.feature/feature.xml) | 공개판 드라이버 전용 feature `com.seizia.chromite.drivers.public.feature`. 위 플러그인 포함 |
| [features/com.seizia.chromite.drivers.public.feature/pom.xml](../../features/com.seizia.chromite.drivers.public.feature/pom.xml) | `maven-dependency-plugin:copy`(실행 ID `bundle-public-drivers`)로 Maven Central에서 정확한 버전의 jar를 `target/generated/drivers/<이름>/`에 받는다. jar 목록·버전은 이곳에만 있다. jar는 저장소에 커밋하지 않는다 |
| [features/com.seizia.chromite.drivers.public.feature/drivers-root/drivers/](../../features/com.seizia.chromite.drivers.public.feature/drivers-root/drivers/) | 라이선스 원문, LGPL 소스 위치 안내(`SOURCE.txt`), 전체 목록(`README.txt`) |
| [features/com.seizia.chromite.drivers.public.feature/build.properties](../../features/com.seizia.chromite.drivers.public.feature/build.properties) | `root = target/generated,drivers-root` — 두 폴더가 설치 폴더의 `drivers/`로 합쳐진다 |
| [features/org.jkiss.dbeaver.ce.feature/feature.xml](../../features/org.jkiss.dbeaver.ce.feature/feature.xml), [features/com.seizia.chromite.swing.feature/feature.xml](../../features/com.seizia.chromite.swing.feature/feature.xml) | SWT 제품과 Swing 제품이 위 feature를 `<includes>`로 포함한다 |

- **플러그인을 따로 만든 이유:** `ui.app.standalone`은 msstore·standalone·테스트 제품에도 들어간다. 그곳에 묶음을 선언하면
  jar가 없는 제품에서 4개 드라이버가 내려받을 곳도 잃는다. 선언(플러그인)과 파일(feature 루트 파일)을 같은 feature에 묶어
  함께만 배포되게 했다.
- **feature를 따로 만든 이유:** 처음에는 플러그인과 루트 파일을 `ce.feature`에 두었는데, Swing 제품(`com.seizia.chromite.swing.feature`)은
  SWT 번들을 끌어오는 `ce.feature`를 포함할 수 없다. jar 목록을 두 곳에 적지 않도록 "선언 + 파일" 한 쌍을 전용 feature로 옮기고
  두 제품이 모두 포함하게 했다. p2는 feature의 루트 파일을 그 feature와 함께 설치하므로(포함된 feature도 같다) 두 제품의 `drivers/`는 같은 원본에서 나온다.
- **설치 위치:** Windows는 `<설치폴더>/drivers/`, macOS는 `Chromite.app/Contents/Eclipse/drivers/`(Eclipse 설치 위치 =
  `Platform.getInstallLocation()`, 기존 `licenses/`와 같은 곳). Swing 제품은 Windows `chromite-swing/drivers/`,
  macOS `ChromiteSwing.app/Contents/Eclipse/drivers/`다.

### 설치 폴더 구성

| 폴더 | 파일 | 쓰는 드라이버 |
|---|---|---|
| `drivers/mssql/new/` | `mssql-jdbc-13.4.0.jre11.jar`, `msal4j-1.16.0.jar`와 의존 jar 12개(`dependency:tree` 결과 그대로), `LICENSE.txt`(MIT), `LICENSE-msal4j.txt`, `LICENSE-slf4j.txt`, `LICENSE-asm.txt` | SQL Server, Azure SQL Server, Babelfish |
| `drivers/oracle/` | `ojdbc11-23.2.0.0.jar`, `orai18n-23.2.0.0.jar`, `xdb6-18.3.0.0.jar`, `xmlparserv2-23.2.0.0.jar`, `LICENSE.txt`(FUTC 원문) | Oracle |
| `drivers/postgresql/` | `postgresql-42.7.14.jar`, `LICENSE.txt`(pgjdbc jar의 `META-INF/LICENSE`, BSD-2-Clause) | PostgreSQL 계열 전부(TimescaleDB, Greenplum, YugabyteDB 등 `drivers.postgresql`을 쓰는 드라이버) |
| `drivers/postgis/` | `postgis-jdbc-2.5.0.jar`, `postgis-geometry-2.5.0.jar`, `postgis-jdbc-jtsparser-2.5.0.jar`, `LICENSE.txt`(LGPL-2.1), `SOURCE.txt` | PostgreSQL 계열의 선택 파일 |
| `drivers/mariadb/` | `mariadb-java-client-3.5.10.jar`, `LICENSE.txt`(LGPL-2.1), `SOURCE.txt` | MariaDB. 공개판은 MySQL 서버도 이 드라이버로 연결한다 |

- 버전은 각 `plugin.xml`의 Maven 대체 경로(`RELEASE[x]`)와 같다. 묶음 사용 여부와 상관없이 같은 드라이버를 쓴다.
- **넣은 것과 뺀 것(결정):**
  - `msal4j`와 의존 jar: 넣음. 묶음 모드에서는 Azure 드라이버의 `msal4j` Maven 항목이 `!drivers.mssql.new`로 빠지고
    대체 경로가 `drivers/mssql/new`뿐이라, 넣지 않으면 Entra ID(Azure AD) 인증이 동작하지 않는다. 모두 MIT·Apache-2.0·BSD-3.
  - `mssql-jdbc_auth` DLL: 뺌(Microsoft 약관, 내부판만). `drivers/mssql/new/auth/x64`는 `optional`이라 없어도 된다.
    공개판에서 Windows 통합 인증(`integratedSecurity=true`)을 쓰려면 드라이버 설정에서 DLL을 직접 추가해야 한다.
  - `xmlparserv2` 18.3.0.0: 뺌. `xdb6`의 의존성으로 딸려 오지만 23.2.0.0과 중복이다(`copy`는 전이 의존성을 받지 않는다).
  - PostGIS 3종: 넣음. Maven 모드에서도 선택 파일로 함께 받으므로 같은 기능을 유지한다. LGPL-2.1, jar를 수정하지 않고 소스 위치를 동봉.
  - `waffle-jna`(PostgreSQL SSPI 인증): 뺌. `plugin.xml`의 묶음 대체 경로(`drivers/waffle`)가 주석 처리돼 있어 넣어도 쓰이지 않고,
    JNA까지 약 3MB가 더 든다. 공개판에서 SSPI(`gsslib=sspi`)를 쓰려면 jar를 직접 추가한다.
  - `checker-qual`: 뺌. pgjdbc의 runtime 의존성이지만 어노테이션뿐이라 실행에 필요 없다.

### 오프라인 우선 조회와 오프라인 모드

- **조회 순서:** [MavenRegistry.findInRepositories](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/maven/MavenRegistry.java:240)는
  설치 폴더의 `maven-local`을 원격 저장소보다 먼저 찾는다(기존의 마지막 조회는 삭제).
- **설정 키:** `ui.drivers.offline`([ModelPreferences.UI_DRIVERS_OFFLINE](../../plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/ModelPreferences.java:282)),
  저장 위치는 `org.jkiss.dbeaver.core` 설정 노드, 기본값 `false`. 화면은 환경설정 > 연결 > 드라이버의 "오프라인 모드" 체크박스
  ([PrefPageDrivers](../../plugins/org.jkiss.dbeaver.ui.editors.connection/src/org/jkiss/dbeaver/ui/preferences/PrefPageDrivers.java:97)).
  판별은 [DriverUtils.isOfflineMode](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverUtils.java:69) 한곳이며,
  값을 매번 읽으므로 켜는 즉시 적용된다.
- **막는 곳(네트워크 접근 지점):**
  - [DriverLibraryAbstract.downloadLibraryFile](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverLibraryAbstract.java:266):
    Maven·URL·`repo:` 파일을 실제로 내려받는 유일한 경로. `file:` 출처는 허용(네트워크 공유의 Maven 저장소 등).
  - [MavenRepository.checkNetworkAccessAllowed](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/maven/MavenRepository.java:230):
    원격 저장소의 `maven-metadata.xml`([MavenArtifact.java:105](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/maven/MavenArtifact.java:105))과
    캐시에 없는 POM([MavenArtifactVersion.java:286](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/maven/MavenArtifactVersion.java:286)) 요청을 막는다.
    `maven-local`과 `file:` 저장소, 이미 받아 둔 캐시는 그대로 쓴다.
  - [DriverLoaderDescriptor.downloadDriverLibraries](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverLoaderDescriptor.java:420):
    다운로드 대화상자를 열지 않고 로컬 소스에서만 조용히 찾는다(내부판의 `maven-local` 해석도 이 경로).
  - [DriverLoaderDescriptor.loadDriver](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/driver/DriverLoaderDescriptor.java:185):
    그래도 드라이버 클래스를 불러오지 못하고 필수 파일이 없으면 "드라이버 파일이 설치본에 포함되어 있지 않고 오프라인 모드가 켜져 있습니다.
    드라이버 설정에서 JAR 파일을 직접 추가하세요."로 연결이 실패한다(`RegistryMessages.driver_offline_files_missing`, 영어·한국어).
    직접 추가한 jar로 클래스를 불러오면 Maven 항목이 남아 있어도 연결된다.
- **한계:** 오프라인 모드에서 실패한 Maven 항목은 세션 동안 "찾을 수 없음"으로 캐시된다. 오프라인 모드를 끈 뒤 다시 내려받으려면
  재시작해야 한다(설정 설명에 표기). 네이티브 클라이언트(`pg_dump` 등) 다운로드는 드라이버가 아니라 대상에서 뺐다.

### 확인 결과 (2026-11-06, Windows x86_64 빌드)

- 빌드 결과물의 `drivers/` 파일 34개(18,916,472 bytes)를 확인했고, macOS aarch64 결과물의
  `Chromite.app/Contents/Eclipse/drivers/`도 파일 이름·크기가 같다.
- 오프라인 모드를 켠 작업 공간에서 127.0.0.1:1(닫힌 포트)로 연결을 시도했다. PostgreSQL·MariaDB는 "Connection refused",
  Oracle은 `ORA-12541`, SQL Server는 TCP/IP 연결 실패로 끝났다. 다운로드 대화상자 없이 드라이버 클래스가 내장 jar에서 로드됐다는 뜻이다.
- 내장되지 않은 SQLite는 다운로드 대화상자 없이 위 안내 문구(한국어)로 실패했다. 환경설정 > 연결 > 드라이버에 체크박스가 보인다.
- Swing 제품(2026-11-06, 드라이버 feature 분리 후): Windows·macOS 결과물의 `drivers/` 34개 파일이 SWT 제품과 이름·크기·SHA-256이 같고(18,917,823 bytes,
  위 첫 확인보다 1,351바이트 큰 것은 `core.autocrlf=true` 체크아웃에서 라이선스 텍스트 1,351줄이 CRLF로 들어갔기 때문이다), SWT 제품의 `drivers/`도 분리 전과 같다. Swing 제품에서 오프라인 모드를 끈 채 PostgreSQL의
  Driver Properties를 열면 내려받기 확인 없이 속성이 보이고, Test Connection은 pgjdbc의 "Connection refused"로 끝났다([swing-frontend.md](swing-frontend.md)).
- 오프라인 모드를 끈 상태(공개판 기본값)의 SWT 제품 내장 드라이버는 실행해 보지 않았다. 코드상 묶음 모드에서는 4개 드라이버에
  내려받을 항목이 남지 않으므로(`getDriverDependencies`가 `null`) 다운로드 대화상자가 열리지 않는다.

### 공개판에 드라이버를 추가하는 방법

1. 드라이버의 `plugin.xml`에 `bundle="drivers.X"`인 폴더 경로와 `bundle="!drivers.X"`인 Maven 항목이 있는지 확인한다.
   없으면 그 둘을 추가해야 한다(Maven 항목에 `bundle="!drivers.X"`, 폴더 항목 `path="drivers/<이름>" bundle="drivers.X"`).
2. [driver-licenses.md](driver-licenses.md)에서 공개판 내장이 가능한지(A 분류) 확인한다.
3. `com.seizia.chromite.drivers.public/plugin.xml`에 `<bundle id="drivers.X"/>`를 추가한다.
4. `com.seizia.chromite.drivers.public.feature/pom.xml`의 `bundle-public-drivers`에 jar를 `artifactItem`으로 추가한다. 버전은 `plugin.xml`의 `RELEASE[x]`와 같게,
   필요한 전이 의존성은 `dependency:tree`로 확인해 하나씩 적는다(`copy`는 전이 의존성을 받지 않는다).
5. 같은 feature의 `drivers-root/drivers/<이름>/`에 라이선스 원문(LGPL·GPL이면 `SOURCE.txt`도)을 넣고 `drivers/README.txt`와 `NOTICE`를 갱신한다.

### 내부판 계획 (미구현)

- `com.seizia.chromite.drivers.internal` 플러그인(전체 묶음 선언)과 그 jar를 루트 파일로 넣는 전용 feature를 만들고,
  제품(SWT·Swing)이 `com.seizia.chromite.drivers.public.feature` 대신 포함한다.
- 묶음이 없는 드라이버는 빌드할 때 `maven-local`에 Maven 구조(POM, `maven-metadata.xml` 포함)로 넣는다. 조회 순서 변경으로 원격보다 먼저 쓰인다.
- 제품의 `plugin_customization.ini`에 `org.jkiss.dbeaver.core/ui.drivers.offline=true`를 넣어 오프라인 모드를 기본으로 켠다
  (`PrefUtils.setDefaultPreferenceValue`는 이미 정해진 기본값을 덮어쓰지 않는다).
- `mssql-jdbc_auth` DLL을 `drivers/mssql/new/auth/x64/`에 넣는다.

## 라이선스

- 드라이버별 버전, 라이선스, 출처, 크기, 분류 전체 목록은 [driver-licenses.md](driver-licenses.md)에 있다(법률 자문 아님).
- 요약(2026-11-06 원문 확인):

| 드라이버 | 라이선스 | 공개판 내장 |
|---|---|---|
| SQL Server (`mssql-jdbc`, Azure용 `msal4j`) | MIT | 가능 |
| SQL Server 통합 인증 DLL (`mssql-jdbc_auth`) | Microsoft Software License Terms (최종 사용자 약관 전가·면책 의무) | 제외. 내부판만 |
| PostgreSQL (`postgresql`) | BSD-2-Clause | 가능 |
| MariaDB Connector/J | LGPL-2.1-or-later | 가능 (jar 수정 없이, 라이선스·소스 위치 동봉) |
| Oracle (`ojdbc11`, `orai18n`, `xdb6`, `xmlparserv2`) | Oracle Free Use Terms and Conditions | 가능. 수정 없는 재배포 허용, 약관 사본 동봉·사용료 미부과 조건 |
| MySQL Connector/J | GPLv2 + Universal FOSS Exception 1.0 | 가능(조건부). Apache-2.0 공개판은 예외가 적용되는 "Other FOSS"에 해당. GPL 원문 동봉과 Connector/J 소스 제공 의무 |
| IBM Db2·Informix, Vertica | 재배포 허용이지만 최종 사용자 약관 전가·면책 조건 | 제외. 내부판만 |
| Teradata, SAP HANA, SAP ASE(jConnect), GBase 8s, Simba BigQuery | 재배포 금지 또는 서면 동의 필요 | 제외. 내부판에도 내장하지 않음 |

- 공개판 4종 크기는 의존성 포함 약 23.7MB(최소 구성 약 14.7MB)다. 전체를 내장하면 약 1.7GB다.
- MySQL Connector/J를 공개판에 넣을 수 있게 됐으므로, 넣을지(+약 4.5MB) 다시 결정한다.

## 미완료 항목

- 공개판에 MySQL Connector/J 포함 여부 결정
- [driver-licenses.md](driver-licenses.md)의 "확인 못함" 항목 약관 원문 확인, 공개판 라이선스 고지 형식 법률 검토
- MariaDB 드라이버로 MySQL 8 연결 시 인증 방식(`caching_sha2_password`) 등 호환성 실측
- 내장 드라이버로 실제 DB(SQL Server, Oracle, PostgreSQL, MariaDB)에 로그인까지 하는 확인(드라이버 로드까지만 확인함), macOS 실행 확인
- 내부판 구현(위 "내부판 계획")

## 변경 이력

- 2026-11-06 최초 작성. 현재 드라이버 로딩 구조 분석, 공개판·내부판 구성, 오프라인 우선 조회 설계를 정리.
- 2026-11-06 드라이버별 라이선스 조사 결과([driver-licenses.md](driver-licenses.md)) 반영. Oracle FUTC 재배포 허용 확인,
  MySQL Connector/J에 Universal FOSS Exception 적용 확인, SQL Server 통합 인증 DLL 공개판 제외, 크기 실측.
- 2026-11-06 공개판 구현. `com.seizia.chromite.drivers.public`으로 4개 묶음 선언, `ce.feature` 빌드에서 jar를 받아 `drivers/`에 설치,
  라이선스 원문·LGPL 소스 안내·`NOTICE` 추가. `maven-local`을 가장 먼저 찾도록 변경, 오프라인 모드 설정(`ui.drivers.offline`) 추가.
- 2026-11-06 묶음 선언 플러그인과 jar 받기·루트 파일을 `ce.feature`에서 전용 feature `com.seizia.chromite.drivers.public.feature`로 옮김.
  SWT 제품과 Swing 제품이 같은 feature를 포함해 같은 `drivers/`를 배포한다.
