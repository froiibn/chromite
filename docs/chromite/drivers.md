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
  ([registry/plugin.xml:132](../../plugins/com.seizia.chromite.registry/plugin.xml:132)).
- **메모리 로딩은 이미 지연 방식이다:** [DriverLoaderDescriptor.loadDriver](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLoaderDescriptor.java:151)는
  처음 연결할 때만 해당 드라이버 jar로 전용 클래스로더를 만든다. jar를 모두 내장해도 쓰지 않는 드라이버는 메모리에 올라가지 않는다.
- **로컬 Maven 저장소:** [MavenRegistry](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/maven/MavenRegistry.java)는
  설치 폴더의 `maven-local`을 로컬 저장소로 등록한다. upstream은 원격 저장소를 모두 시도한 뒤 **마지막에** 찾았다.
  Chromite는 2026-11-06부터 **가장 먼저** 찾는다(아래 "구현").
- **상대 경로 파일:** [DriverLibraryLocal.detectLocalFile](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLibraryLocal.java:175)는
  `drivers/oracle` 같은 상대 경로를 설치 폴더에서 먼저 찾고, 없으면 사용자 드라이버 폴더에서 찾는다.
- **드라이버 묶음(product bundles):** 많은 드라이버가 파일을 두 가지로 정의한다.

  ```xml
  <file type="jar" path="maven:/com.oracle.database.jdbc:ojdbc11:RELEASE[23.2.0.0]" bundle="!drivers.oracle"/>
  <file type="jar" path="drivers/oracle" bundle="drivers.oracle"/>
  ```

  앱이 `com.seizia.chromite.product.bundles` 확장 포인트로 `drivers.oracle` 묶음을 선언하면 설치 폴더의 `drivers/oracle`을 쓰고,
  선언하지 않으면 Maven에서 받는다([ProductBundleRegistry](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/ProductBundleRegistry.java)).
  이런 대체 정의가 있는 묶음은 약 45개다(`drivers.mssql.new`, `drivers.oracle`, `drivers.postgresql`, `drivers.mariadb`, `drivers.mysql`, `drivers.db2` 등).
  upstream 저장소에는 묶음 선언이 없다. Chromite는 5개를 선언한다(아래 "구현").
- **폴더 경로 해석:** `drivers/oracle` 같은 상대 경로는 설치 폴더 기준으로 먼저 찾는다
  ([DriverLibraryLocal.detectLocalFile](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLibraryLocal.java:175)).
  경로가 폴더이면 그 폴더 **바로 아래의** `.jar`·`.zip` 파일만 클래스패스에 넣는다(하위 폴더는 보지 않음,
  [DriverLoaderDescriptor.readJarsFromDir](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLoaderDescriptor.java)).
- **묶음을 선언하면 Maven 대체 파일은 사라진다:** `bundle="!drivers.X"`인 파일은 목록에서 빠지므로
  ([DriverUtils.matchesBundle](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverUtils.java)),
  묶음을 선언하고 폴더를 채우지 않으면 그 드라이버는 내려받을 곳도 없어진다. 묶음 선언과 폴더는 반드시 같은 feature로 배포한다.

## 설계

### 배포판 구성 (2026-11-07 결정)

Chromite는 **배포판이 하나다.** 내부판(전체 드라이버 내장)은 만들지 않는다.

| 구분 | 내용 |
|---|---|
| 내장 드라이버 | 주요 DB만: SQL Server, Oracle, PostgreSQL, MariaDB, SQLite, ShardSphere(2026-11-14 추가, 아래 "ShardSphere") |
| MySQL | 내장하지 않음(2026-11-07 결정, 아래 "MySQL Connector/J 내장 취소"). 처음 쓸 때 Maven에서 받는다. 내장된 MariaDB 드라이버로도 MySQL 서버에 연결할 수 있다 |
| 그 밖의 드라이버 | 필요할 때 내려받는다. 인터넷이 되면 처음 연결할 때 Maven에서 온라인 다운로드 |
| 폐쇄망 | 인터넷 PC에서 만든 오프라인 설치 묶음(`.chromite-drivers`)을 설치한다([driver-offline-bundle.md](driver-offline-bundle.md)). 벤더에서만 받을 수 있는 jar는 직접 지정한다 |

- 이전 결정(2026-11-06)은 공개판(주요 DB만 내장)과 내부판(전체 내장, 원격 다운로드 끔)의 두 판이었다.
  사용자 결정(2026-11-07)으로 내부판을 없앴다. 주요 DB만 내장하고, 나머지는 내부망이든 외부망이든 필요할 때 받아 쓴다.
- 라이선스 분류([driver-licenses.md](driver-licenses.md))는 그대로 쓴다. B·C 분류는 내장하지 않는다.

### MySQL Connector/J 내장 취소 (2026-11-07 결정)

사용자 결정(2026-11-07, 원문):

> "빼자. 많이 쓰지도 않는 db이다"

- **이유:** 앞으로의 릴리스에서 GPLv2 3항의 대응 소스 제공 의무가 없어진다. 사용자 분야에서 MySQL은 드물게 쓴다.
- **이전 결정(2026-11-06):** MySQL Connector/J 9.7.0을 내장했다(`drivers.mysql` 선언, `drivers/mysql/mysql8/`에 jar,
  `drivers/mysql/`에 jar 안 `LICENSE` 원문과 `SOURCE.txt`, `NOTICE` 고지). 아래 "넣은 것과 뺀 것"의 MySQL 항목과 "확인 결과"의 MySQL 추가 기록은 그때의 기록이다.
- **v0.1.0은 Connector/J 9.7.0을 내장한 채 배포됐다**(`v0.1.0` 태그에 `drivers.mysql` 선언과 `drivers/mysql/` 텍스트가 있다).
  그 릴리스의 대응 소스 묶음은 GitHub `v0.1.0` Release(초안)에 첨부했다(아래 "미완료 항목").
- **이제 MySQL에 연결하는 방법:**
  - 온라인: 다른 내장되지 않은 드라이버처럼 처음 연결할 때 다운로드 대화상자가 열리고 Maven Central에서 받는다.
  - 폐쇄망: 인터넷 PC에서 MySQL을 넣은 오프라인 설치 묶음을 만들어 설치한다([driver-offline-bundle.md](driver-offline-bundle.md)).
  - 내장된 MariaDB Connector/J(LGPL)로도 MySQL 서버에 연결할 수 있다.
- **바꾼 것:** `drivers.mysql` 선언, feature `pom.xml`의 Connector/J `artifactItem`, `drivers-root/drivers/mysql/`(`LICENSE.txt`, `SOURCE.txt`),
  `drivers/README.txt`의 `mysql/` 절, `NOTICE`의 Connector/J 항목을 지웠다. 묶음을 선언하지 않으므로 MySQL 계열 드라이버의
  `bundle="!drivers.mysql"` Maven 항목이 다시 쓰이고 `bundle="drivers.mysql"` 폴더 항목은 빠진다(`DriverUtils.matchesBundle`).
  `plugin.xml`의 폴더 대체 항목은 upstream에 있던 것이라 그대로 두었다(2026-11-06 내장 전과 같은 상태).
- **Maven 버전(바꾸지 않음):** 내장 전과 같다. MySQL·NDB Cluster·Apache Doris `RELEASE[8.2.0]`(8.2.0 고정), Manticore `RELEASE[9.7.0]`,
  GreptimeDB·TiDB 버전 없는 `RELEASE`(최신). `RELEASE[x]`는 `x`를 고정 버전으로 받으므로 MySQL 오프라인 묶음은 늘 8.2.0이라 재현된다.
  GreptimeDB·TiDB는 만들 때의 최신판이 들어간다(다른 `RELEASE` 드라이버와 같음). NDB Cluster(`com.mysql.jdbc.Driver`,
  `jdbc:mysql:loadbalance:`)는 MySQL과 같은 8.2.0 jar를 받는다.
- **MySQL 5 (Legacy):** 내장할 때 `drivers.mysql5`로 나눴던 묶음 ID를 upstream대로 `drivers.mysql`로 되돌렸다. 묶음을 선언하지 않으면
  두 ID 모두 Maven 항목(5.1.49)이 쓰여 동작은 같다. 나눈 이유가 `drivers.mysql` 선언이었으므로 그 주석과 함께 없앴다.
- **라이선스 표기:** 이제 사용자가 직접 받으므로 드라이버 설정의 License 탭에 보이는 원문이 맞아야 한다. MySQL 계열 6개 드라이버(MySQL, NDB Cluster,
  Apache Doris, GreptimeDB, Manticore Search, TiDB)의 `type="license"`를 `licenses/external/gnu3.txt`(GPLv3)에서
  `licenses/external/gpl-2.0-universal-foss-exception.txt`로 바꿨다. 이 파일은 Connector/J `LICENSE`(9.7.0 jar 안)의 GPLv2 원문과
  Universal FOSS Exception 1.0 원문을 고치지 않고 옮기고, 앞에 무엇인지와 버전별 전체 고지는 jar 안 `LICENSE`에 있다는 안내를 붙였다.
  버전마다 다른 제3자 고지와 "9.7.0" 같은 버전 문구는 넣지 않았다(8.2.0·최신판도 같은 표기를 쓴다). TiDB의 묶음 모드 표기(`drivers/mysql/LICENSE.txt`)는
  upstream 항목이라 그대로 두었다(묶음을 선언하지 않아 쓰이지 않는다). `gnu3.txt`는 쓰는 드라이버가 없어져 지웠다.
  MySQL 5 (Legacy)(`lgpl-2.0.txt`, 실제 GPLv2 + 예전 MySQL FOSS License Exception)와 StarRocks는 다른 jar라 이번 범위에서 고치지 않았다
  ([driver-licenses.md](driver-licenses.md) "plugin.xml의 라이선스 표기와 실제가 다른 항목"). 2026-11-08에 고쳤다: MySQL 5 (Legacy)는 새 파일
  `gpl-2.0-mysql-foss-exception.txt`, StarRocks는 `gpl-2.0-universal-foss-exception.txt`(앞 안내에 StarRocks 문단 추가).
- **v0.1.0에서 올린 사용자:** 작업 공간의 `drivers.xml`에 `drivers/mysql/mysql8` 라이브러리가 저장돼 있어도, 사용자 정의가 아닌 라이브러리가
  `plugin.xml`에 없으면 읽을 때 건너뛴다(`DriverDescriptorSerializerLegacy`의 "Skip obsolete custom library"). 그래서 Maven 항목이 쓰이고 처음 연결할 때 내려받는다.
- **Connect 대화상자:** Database type 콤보의 주요 DB 목록(`DatabaseTypeCombo.FAVORITES`)에는 MySQL을 그대로 둔다. 결정은 내장 여부만 정했다.

### 내장 방법

1. **드라이버 묶음이 있는 드라이버:** 묶음 선언 플러그인에 해당 묶음 ID를 선언하고, 빌드할 때 `drivers/<이름>/` 폴더에 jar를 넣는다.
2. **"묶음 선언 플러그인 + 그 폴더를 채우는 빌드 설정"을 한 feature로 묶는다.** 제품은 이 feature를 포함한다(아래 "구현").

### 오프라인 우선 조회

- `MavenRegistry`에서 로컬 저장소(`maven-local`)를 **가장 먼저** 찾도록 바꾼다.
  지금처럼 원격을 먼저 시도하면 폐쇄망에서 시간 초과까지 기다리게 된다.
- 원격 다운로드를 끄는 오프라인 설정을 추가한다. 기본값은 끔이다.

### 버전 고정

- 일부 드라이버는 버전 없이 `RELEASE`(최신)로 정의돼 있다(예: `com.mysql:mysql-connector-j:RELEASE`, `com.teradata.jdbc:terajdbc:RELEASE`).
  최신 버전을 알려면 원격 조회가 필요하므로 오프라인에서는 버전을 정할 수 없다.
- 내장할 때 버전을 고정하고, `maven-local`에 `maven-metadata.xml`을 함께 만든다.

### Swing UI

- "드라이버 다운로드" 대화상자 대신 내장 드라이버 사용 여부와 파일 위치를 보여 준다.
- 내장되지 않은 드라이버는 jar 파일을 직접 지정하는 화면을 제공한다.

## 구현 (2026-11-06, SQLite 2026-11-07)

### 구조

| 위치 | 역할 |
|---|---|
| [plugins/com.seizia.chromite.drivers.public/plugin.xml](../../plugins/com.seizia.chromite.drivers.public/plugin.xml) | `com.seizia.chromite.product.bundles`로 `drivers.mssql.new`, `drivers.oracle`, `drivers.postgresql`, `drivers.mariadb`, `drivers.sqlite.xerial`, `drivers.shardsphere` 선언. 코드 없음(`drivers.mysql`은 2026-11-07에 뺌) |
| [features/com.seizia.chromite.drivers.public.feature/feature.xml](../../features/com.seizia.chromite.drivers.public.feature/feature.xml) | 내장 드라이버 전용 feature `com.seizia.chromite.drivers.public.feature`. 위 플러그인 포함 |
| [features/com.seizia.chromite.drivers.public.feature/pom.xml](../../features/com.seizia.chromite.drivers.public.feature/pom.xml) | `maven-dependency-plugin:copy`(실행 ID `bundle-public-drivers`)로 Maven Central에서 정확한 버전의 jar를 `target/generated/drivers/<이름>/`에 받는다. jar 목록·버전은 이곳에만 있다. jar는 저장소에 커밋하지 않는다(예외: Maven Central에 없는 ShardSphere jar, 아래 행) |
| [features/com.seizia.chromite.drivers.public.feature/drivers-root/drivers/](../../features/com.seizia.chromite.drivers.public.feature/drivers-root/drivers/) | 라이선스 원문, LGPL 소스 위치 안내(`SOURCE.txt`), 전체 목록(`README.txt`). 루트 [.gitattributes](../../.gitattributes)로 `core.autocrlf`와 상관없이 항상 LF로 체크아웃된다. `shardsphere/shardsphere.jar`는 저장소에 커밋한 유일한 드라이버 jar다(`.gitattributes`의 `*.jar binary`, 아래 "ShardSphere") |
| [features/com.seizia.chromite.drivers.public.feature/build.properties](../../features/com.seizia.chromite.drivers.public.feature/build.properties) | `root = target/generated,drivers-root` — 두 폴더가 설치 폴더의 `drivers/`로 합쳐진다 |
| [features/com.seizia.chromite.swing.feature/feature.xml](../../features/com.seizia.chromite.swing.feature/feature.xml) | Swing 제품이 위 feature를 `<includes>`로 포함한다(SWT 제품은 2026-11-07 삭제, [swt-removal.md](swt-removal.md)) |

- **플러그인을 따로 만든 이유:** 묶음 선언을 `ui.app.standalone` 같은 다른 번들에 두면, 그 번들은 들어가고 jar는 없는 제품에서
  묶음 드라이버가 내려받을 곳도 잃는다. 선언(플러그인)과 파일(feature 루트 파일)을 같은 feature에 묶어
  함께만 배포되게 했다.
- **feature를 따로 만든 이유:** 처음에는 플러그인과 루트 파일을 `ce.feature`에 두었는데, Swing 제품(`com.seizia.chromite.swing.feature`)은
  SWT 번들을 끌어오는 `ce.feature`를 포함할 수 없다. jar 목록을 두 곳에 적지 않도록 "선언 + 파일" 한 쌍을 전용 feature로 옮기고
  두 제품이 모두 포함하게 했다. p2는 feature의 루트 파일을 그 feature와 함께 설치하므로(포함된 feature도 같다) 두 제품의 `drivers/`는 같은 원본에서 나왔다.
  SWT 제품을 삭제한 뒤에도 "선언 + 파일"을 함께만 배포하는 단위가 되므로 그대로 둔다. ID의 `public`은 공개판·내부판을 나누던 때의 이름이다.
  배포판이 하나가 된 뒤(2026-11-07)에도 ID는 바꾸지 않았다. 제품 feature, 모듈 목록, 문서에 걸친 이름 변경이라 동작은 같고 바뀌는 곳만 많다.
- **설치 위치:** Windows는 `<설치폴더>/drivers/`, macOS는 `Chromite.app/Contents/Eclipse/drivers/`(Eclipse 설치 위치 =
  `Platform.getInstallLocation()`, 기존 `licenses/`와 같은 곳). Swing 제품은 Windows `chromite-swing/drivers/`,
  macOS `Chromite.app/Contents/Eclipse/drivers/`다.

### 설치 폴더 구성

| 폴더 | 파일 | 쓰는 드라이버 |
|---|---|---|
| `drivers/mssql/new/` | `mssql-jdbc-13.4.0.jre11.jar`, `msal4j-1.16.0.jar`와 의존 jar 12개(`dependency:tree` 결과 그대로), `LICENSE.txt`(MIT), `LICENSE-msal4j.txt`, `LICENSE-slf4j.txt`, `LICENSE-asm.txt` | SQL Server, Azure SQL Server, Babelfish |
| `drivers/oracle/` | `ojdbc11-23.2.0.0.jar`, `orai18n-23.2.0.0.jar`, `xdb6-18.3.0.0.jar`, `xmlparserv2-23.2.0.0.jar`, `LICENSE.txt`(FUTC 원문) | Oracle |
| `drivers/postgresql/` | `postgresql-42.7.14.jar`, `LICENSE.txt`(pgjdbc jar의 `META-INF/LICENSE`, BSD-2-Clause) | PostgreSQL 계열 전부(TimescaleDB, Greenplum, YugabyteDB 등 `drivers.postgresql`을 쓰는 드라이버) |
| `drivers/postgis/` | `postgis-jdbc-2.5.0.jar`, `postgis-geometry-2.5.0.jar`, `postgis-jdbc-jtsparser-2.5.0.jar`, `LICENSE.txt`(LGPL-2.1), `SOURCE.txt` | PostgreSQL 계열의 선택 파일 |
| `drivers/mariadb/` | `mariadb-java-client-3.5.10.jar`, `LICENSE.txt`(LGPL-2.1), `SOURCE.txt` | MariaDB. MySQL 서버에도 쓸 수 있다 |
| `drivers/sqlite/` | `xerial/sqlite-jdbc-3.54.1.0.jar`, `LICENSE.txt`(jar 안 `META-INF/maven/org.xerial/sqlite-jdbc/LICENSE`, Apache-2.0), `LICENSE-zentus.txt`(jar 안 `LICENSE.zentus`, 원본 Zentus 드라이버의 BSD 형식 라이선스) | SQLite(`sqlite_jdbc`, `drivers.sqlite.xerial`을 쓰는 유일한 드라이버) |
| `drivers/shardsphere/` | `shardsphere.jar`(저장소에 커밋, 빌드 때 받지 않음), `README.txt`(무엇인지, SHA-256, 라이선스 상황) | ShardSphere(`altibase:shardsphere`, `drivers.shardsphere`를 쓰는 유일한 드라이버) |

- 버전은 각 `plugin.xml`의 Maven 대체 경로(`RELEASE[x]`)와 같다. 묶음 사용 여부와 상관없이 같은 드라이버를 쓴다.
  (2026-11-06~07에 내장했던 MySQL은 예외였다. `drivers/mysql/mysql8` 하나를 쓰는 드라이버들의 Maven 대체 경로가 서로 달라
  (MySQL·NDB·Doris `RELEASE[8.2.0]`, Manticore `RELEASE[9.7.0]`, GreptimeDB·TiDB 버전 없는 `RELEASE`) 9.7.0 하나만 넣었다.)
  SQLite는 Maven 대체 경로가 버전 없는 `RELEASE`(`maven:/org.xerial:sqlite-jdbc:RELEASE`)라 내장 버전을 feature `pom.xml`에서 고정했다.
  내장한 2026-11-07에 `RELEASE`가 가리키는 버전(3.54.1.0)과 같다. `plugin.xml`은 고치지 않았다(묶음 모드에서는 Maven 항목이 빠진다).
- **넣은 것과 뺀 것(결정):**
  - `msal4j`와 의존 jar: 넣음. 묶음 모드에서는 Azure 드라이버의 `msal4j` Maven 항목이 `!drivers.mssql.new`로 빠지고
    대체 경로가 `drivers/mssql/new`뿐이라, 넣지 않으면 Entra ID(Azure AD) 인증이 동작하지 않는다. 모두 MIT·Apache-2.0·BSD-3.
  - `mssql-jdbc_auth` DLL: 뺌(Microsoft 약관). `drivers/mssql/new/auth/x64`는 `optional`이라 없어도 된다.
    Windows 통합 인증(`integratedSecurity=true`)을 쓰려면 드라이버 설정에서 DLL을 직접 추가해야 한다.
  - `xmlparserv2` 18.3.0.0: 뺌. `xdb6`의 의존성으로 딸려 오지만 23.2.0.0과 중복이다(`copy`는 전이 의존성을 받지 않는다).
  - PostGIS 3종: 넣음. Maven 모드에서도 선택 파일로 함께 받으므로 같은 기능을 유지한다. LGPL-2.1, jar를 수정하지 않고 소스 위치를 동봉.
  - `waffle-jna`(PostgreSQL SSPI 인증): 뺌. `plugin.xml`의 묶음 대체 경로(`drivers/waffle`)가 주석 처리돼 있어 넣어도 쓰이지 않고,
    JNA까지 약 3MB가 더 든다. SSPI(`gsslib=sspi`)를 쓰려면 jar를 직접 추가한다.
  - `checker-qual`: 뺌. pgjdbc의 runtime 의존성이지만 어노테이션뿐이라 실행에 필요 없다.
  - MySQL Connector/J: 2026-11-07부터 뺌(위 "MySQL Connector/J 내장 취소"). 아래 세 항목은 2026-11-06에 내장할 때의 결정 기록이다.
  - (기록) MySQL Connector/J 버전: 9.7.0. 저장소에 이미 고정된 버전(Manticore, upstream이 2026-09에 추가)이고,
    [driver-licenses.md](driver-licenses.md)에서 내장할 때 9.x 또는 26.x로 올리기로 정리했다. 8.2.0(2023년)은 오래됐고,
    26.10.0은 `RELEASE`가 지금 가리키는 최신판이라 검증 이력이 가장 짧다. 9.7.0 소스에서 서버 버전으로 연결을 거부하는 곳은
    아래 `CLIENT_PLUGIN_AUTH` 검사뿐이고(5.x 서버용 `versionMeetsMinimum(5, ...)` 분기는 남아 있다),
    NDB 드라이버가 쓰는 `com.mysql.jdbc.Driver` 클래스도 들어 있다.
    Maven 모드의 MySQL·NDB·Doris(8.2.0)와 묶음 모드의 버전이 다르다는 점은 남는다.
  - (기록) `protobuf-java`(Connector/J의 compile 의존성): 뺌. X DevAPI(`mysqlx:`) 전용이다. `jdeps`로 보면 protobuf를 참조하는 패키지는
    `com.mysql.cj.protocol.x`, `com.mysql.cj.x.protobuf`, `com.mysql.cj.xdevapi`뿐이고, JDBC 쪽에서 이 패키지로 가는 경로는
    `CoreSession.getDataStoreMetadata()` 하나인데 이 메서드는 `xdevapi` 클래스만 부른다. jar의 OSGi `Import-Package`도
    `com.google.protobuf;resolution:=optional`이다. Chromite의 MySQL 계열 드라이버는 모두 `jdbc:mysql:` URL을 쓴다.
    선택 의존성 `oci-java-sdk-common`(OCI IAM 인증)도 넣지 않는다(Maven 모드에서도 받지 않는 선택 의존성).
  - (기록) `mysql-connector-java` 5.1.49(MySQL 5 (Legacy)): 뺌. 이 드라이버는 `drivers/mysql/mysql5`를 대체 경로로 두지만,
    9.7.0으로 대신할 수 없다. 9.7.0은 `CLIENT_PLUGIN_AUTH`가 없는 서버(MySQL 5.5.7 미만)를 거부하는데
    (`NativeAuthenticationProvider.java:145`의 "CLIENT_PLUGIN_AUTH is required", 8.2.0에도 같은 검사가 있다), Legacy 드라이버는 그런 예전 서버용이다.
    5.1.49는 예전 "MySQL FOSS License Exception"이 적용되고 2020년 이후 갱신이 없어 내장하지 않기로 했다([driver-licenses.md](driver-licenses.md)).
    그래서 [ext.mysql/plugin.xml](../../plugins/com.seizia.chromite.ext.mysql/plugin.xml)의 Legacy 드라이버 두 항목만 묶음 ID를
    `drivers.mysql5`로 바꿨다. Chromite는 이 묶음을 선언하지 않으므로 Legacy 드라이버는 예전처럼 처음 쓸 때 Maven에서 5.1.49를 받는다.
    바꾸지 않으면 `drivers.mysql` 선언과 함께 Maven 항목이 빠지고 폴더도 없어 파일 출처가 사라진다.
    2026-11-07 내장 취소 때 `drivers.mysql`로 되돌렸다.
    StarRocks(`starrocks-connector-j`)와 "MySQL (Old)"(ext.generic)는 묶음 속성이 없어 영향이 없다.
  - SQLite(2026-11-07 사용자 결정): Xerial `sqlite-jdbc` 3.54.1.0 jar 하나를 넣음. Apache-2.0(A 분류), 12,132,578 bytes.
    `dependency:tree`로 보면 runtime 의존성이 없다(`slf4j-api`는 `optional`이고 `plugin.xml`도 선택 의존성을 받지 않는다).
    전 플랫폼 네이티브 SQLite(Public Domain)가 jar 안에 들어 있어 Windows·macOS 결과물이 같은 jar를 쓴다.
    버전은 [driver-licenses.md](driver-licenses.md)에 조사 때 적은 3.54.1.0이다. 그보다 새 버전은 없다(2026-11-07 Maven Central `maven-metadata.xml`).
    다만 Maven Central 게시일이 2026-10-29라 내장 시점에 9일밖에 지나지 않았다. 바로 전 버전은 3.53.4.0(2026-08-26 게시)이다.
    jar 안 라이선스 두 개(`LICENSE`, `LICENSE.zentus`)를 줄바꿈만 LF로 바꿔 넣었다(`.gitattributes`가 LF로 고정).

### 오프라인 우선 조회와 오프라인 모드

- **조회 순서:** [MavenRegistry.findInRepositories](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/maven/MavenRegistry.java:240)는
  설치 폴더의 `maven-local`을 원격 저장소보다 먼저 찾는다(기존의 마지막 조회는 삭제).
- **설정 키:** `ui.drivers.offline`([ModelPreferences.UI_DRIVERS_OFFLINE](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/ModelPreferences.java:282)),
  저장 위치는 플랫폼 설정 저장소를 가진 제품 번들의 설정 노드(Swing 제품은 `com.seizia.chromite.ui.swing`), 기본값 `false`.
  화면은 Swing Driver Manager의 "오프라인 모드" 체크 상자다([swing-driver-manager.md](swing-driver-manager.md)). SWT 환경설정의 드라이버 페이지는 SWT와 함께 삭제했다.
  판별은 [DriverUtils.isOfflineMode](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverUtils.java:69) 한곳이며,
  값을 매번 읽으므로 켜는 즉시 적용된다.
- **막는 곳(네트워크 접근 지점):**
  - [DriverLibraryAbstract.downloadLibraryFile](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLibraryAbstract.java:266):
    Maven·URL·`repo:` 파일을 실제로 내려받는 유일한 경로. `file:` 출처는 허용(네트워크 공유의 Maven 저장소 등).
  - [MavenRepository.checkNetworkAccessAllowed](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/maven/MavenRepository.java:230):
    원격 저장소의 `maven-metadata.xml`([MavenArtifact.java:105](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/maven/MavenArtifact.java:105))과
    캐시에 없는 POM([MavenArtifactVersion.java:286](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/maven/MavenArtifactVersion.java:286)) 요청을 막는다.
    `maven-local`과 `file:` 저장소, 이미 받아 둔 캐시는 그대로 쓴다.
  - [DriverLoaderDescriptor.downloadDriverLibraries](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLoaderDescriptor.java:420):
    다운로드 대화상자를 열지 않고 로컬 소스에서만 조용히 찾는다(`maven-local` 해석도 이 경로).
  - [DriverLoaderDescriptor.loadDriver](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLoaderDescriptor.java:185):
    그래도 드라이버 클래스를 불러오지 못하고 필수 파일이 없으면 "드라이버 파일이 설치본에 포함되어 있지 않고 오프라인 모드가 켜져 있습니다.
    드라이버 설정에서 JAR 파일을 직접 추가하세요."로 연결이 실패한다(`RegistryMessages.driver_offline_files_missing`, 영어·한국어).
    직접 추가한 jar로 클래스를 불러오면 Maven 항목이 남아 있어도 연결된다.
- **한계:** 오프라인 모드에서 실패한 Maven 항목은 세션 동안 "찾을 수 없음"으로 캐시된다. 오프라인 모드를 끈 뒤 다시 내려받으려면
  재시작해야 한다(설정 설명에 표기). 네이티브 클라이언트(`pg_dump` 등) 다운로드는 드라이버가 아니라 대상에서 뺐다.

### 로컬 파일 우선 (2026-11-08)

- **문제(후속 #62):** Altibase처럼 기본 Maven 항목(`com.altibase:altibase-jdbc`)만 있는 드라이버에 사용자가 로컬 jar를 추가해도, 연결할 때마다
  다운로드를 물었다. 취소하면 로컬 jar가 있어도 "Class not found"로 끝났고, Maven 항목을 지워야만 연결됐다.
- **근본 원인:** `DriverLoaderDescriptor.getDriverDependencies`는 받지 않은 다운로드 항목을 모두 후보로 넣고, `validateFilesPresence`는 다운로드가
  취소되면(`downloadDriverLibraries`가 false) 로컬 파일까지 빈 목록을 돌려준다. 사용자 파일이 드라이버인지 보지 않았다.
- **규칙:** 사용자가 추가한 로컬 라이브러리(사용자 정의 `custom`, 다운로드하지 않는 jar 파일·폴더, 이 OS용, 켜짐)에 드라이버 클래스
  (`getDriverClassName()`의 `.class` 항목)가 있으면, 미리 정의된(plugin.xml) 다운로드 항목은 "대체됨"으로 본다
  ([DriverLoaderDescriptor.hasCustomLocalDriverClass, isReplacedByLocalLibrary](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLoaderDescriptor.java)).
  대체된 항목은
  - 다운로드 후보에서 빠지고(연결할 때 묻지 않음, Download/Update도 받지 않음),
  - 클래스패스에도 넣지 않는다(전에 받아 둔 파일이 사용자 jar의 클래스를 가리지 않게),
  - 오프라인 모드의 "필수 파일 없음" 판단(`needsExternalDependencies`)에서도 빠진다.
- **사용자 정의 라이브러리만 보는 이유:** 미리 정의된 로컬 라이브러리와 미리 정의된 Maven 항목이 함께 필요한 드라이버가 있다.
  모든 `plugin.xml`을 훑으니 `postgres-gcloud-jdbc` 하나였다(내장 `drivers/postgresql` + Maven `postgres-socket-factory`, 소켓 팩토리는 연결할 때 필요).
  미리 정의된 로컬 파일까지 보면 이 드라이버의 소켓 팩토리를 받지 않게 된다. 내장 드라이버는 `bundle="!drivers.X"`로 Maven 항목이 빠지므로 해당 없다.
- **사용자가 추가한 Maven 항목은 그대로 받는다:** 사용자 정의 다운로드 항목은 사용자가 일부러 더한 의존성이라 대체하지 않는다.
  드라이버를 복사하면 라이브러리가 모두 사용자 정의가 되므로 복사본의 Maven 항목도 그대로 받는다.
- **검사 방법:** jar는 ZIP 항목만 본다(클래스 로더·클래스 초기화 없음). 폴더는 폴더 안의 `.class` 파일과 바로 아래 jar를 본다(모델이 클래스패스에 넣는 것과 같음).
  안에 jar가 든 ZIP은 보지 않는다(이전 동작).
- **화면:** Driver Manager 라이브러리 탭에서 대체된 항목은 "사용 안 함: 로컬 파일에 드라이버 클래스가 있음"으로 보이고 다운로드 단추의 대상에서 빠진다
  ([swing-driver-manager.md](swing-driver-manager.md)).
- **확인(2026-11-08):** Altibase에 Maven 항목을 남긴 채 로컬 `Altibase.jar`를 더하고 연결 테스트 → 다운로드 질문 없이 Altibase 7.3에 연결됐다
  ([swing-driver-manager.md](swing-driver-manager.md) "확인 (2026-11-08 …)").

### 내려받기를 취소하면 (2026-11-12, 앱 변경 #85)

- **결함:** MySQL처럼 파일을 내려받아야 하는 드라이버로 연결할 때 다운로드 질문에서 취소를 눌러도, 한 번의 연결 시도에 질문이 세 번 떴다.
- **근본 원인:** 질문의 대답이 시도에 남지 않았다. `DriverLoaderDescriptor.validateFilesPresence`는 다운로드가 되지 않으면(`downloadDriverLibraries`가 false)
  빈 파일 목록을 돌려주고, 부른 쪽은 그대로 이어 갔다. 그래서 드라이버 클래스를 읽는 단계마다 다시 물었다(고치기 전 빌드의 스택):
  `DataSourceDescriptor.connect`의 `validateFilesPresence`(1081행), `MySQLDataSource.getInternalConnectionProperties` → `getDriverInstance` → `loadDriver`,
  `JDBCDataSource.createDriverInstance` → `getDriverInstance` → `loadDriver`. 마지막에 "드라이버 클래스를 찾지 못함"으로 끝났다.
  연결, 연결 테스트, 데이터베이스 목록, 쿼리 창의 첫 실행이 모두 `DataSourceDescriptor.connect`를 지나므로 모두 세 번이었다.
- **고침:** 연결 시도의 처음(`DriverDescriptor.validateFilesPresence(monitor, container)`, 인터페이스가 이미 `DBException`을 선언)이
  `DriverLoaderDescriptor.checkFilesPresence`를 부른다. 질문·다운로드를 취소했거나 실패해 파일을 내려받지 않았으면 그 시도를 끝낸다:
  "'MySQL' 드라이버 파일을 내려받지 않아 연결하지 않았습니다. 다시 연결하면 내려받을 수 있고, …"(`RegistryMessages.driver_files_not_downloaded`).
  안쪽 `validateFilesPresence(monitor, …)`가 "내려받지 않음"을 null로 알리고, 공개 메서드는 전처럼 빈 목록을 돌려준다(다른 호출자 동작 그대로).
  오프라인 모드는 묻지 않고 클래스를 읽을 때 자기 안내("오프라인 모드…")를 내므로 여기서 끝내지 않는다. 다음 연결 시도는 다시 묻는다.
- **확인(이 브랜치 빌드, 한국어 150%, MySQL 8.4 테스트 서버, 내려받지 않음):** 연결, 쿼리 창의 첫 실행, 연결 테스트, 연결 대화상자의 데이터베이스 목록
  각각 질문 한 번, 취소하면 위 안내로 끝남(데이터베이스 목록은 칸 아래 안내). 파일이 있는 경우는 내장 드라이버(PostgreSQL, SQL Server, Oracle, MariaDB)
  연결로 확인(질문 없음). 증거 `D:\Tools\_scratch\small-fixes-r3\sheets\85-*`. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 모든 연결 시작,
  드라이버 다운로드 질문.

### 확인 결과 (2026-11-06, Windows x86_64 빌드)

- 빌드 결과물의 `drivers/` 파일 34개(18,916,472 bytes)를 확인했고, macOS aarch64 결과물의
  `Chromite.app/Contents/Eclipse/drivers/`도 파일 이름·크기가 같다.
- 오프라인 모드를 켠 작업 공간에서 127.0.0.1:1(닫힌 포트)로 연결을 시도했다. PostgreSQL·MariaDB는 "Connection refused",
  Oracle은 `ORA-12541`, SQL Server는 TCP/IP 연결 실패로 끝났다. 다운로드 대화상자 없이 드라이버 클래스가 내장 jar에서 로드됐다는 뜻이다.
- 당시 내장되지 않았던 SQLite(2026-11-07부터 내장)는 다운로드 대화상자 없이 위 안내 문구(한국어)로 실패했다. 환경설정 > 연결 > 드라이버에 체크박스가 보인다.
- Swing 제품(2026-11-06, 드라이버 feature 분리 후): Windows·macOS 결과물의 `drivers/` 34개 파일이 SWT 제품과 이름·크기·SHA-256이 같고(18,917,823 bytes,
  위 첫 확인보다 1,351바이트 큰 것은 `core.autocrlf=true` 체크아웃에서 라이선스 텍스트 1,351줄이 CRLF로 들어갔기 때문이다.
  이후 `.gitattributes`로 이 텍스트를 항상 LF로 고정해 크기가 체크아웃 설정에 따라 달라지지 않는다), SWT 제품의 `drivers/`도 분리 전과 같다. Swing 제품에서 오프라인 모드를 끈 채 PostgreSQL의
  Driver Properties를 열면 내려받기 확인 없이 속성이 보이고, Test Connection은 pgjdbc의 "Connection refused"로 끝났다([swing-frontend.md](swing-frontend.md)).
- MySQL 추가 후(2026-11-06, `-Pproduct-chromite-swt,product-chromite-swing` 빌드): 네 결과물(SWT·Swing × Windows·macOS)의 `drivers/`가
  37개 파일, 21,608,448 bytes로 이름·크기·SHA-256이 모두 같다. 늘어난 파일은 `mysql/LICENSE.txt`(82,896), `mysql/SOURCE.txt`,
  `mysql/mysql8/mysql-connector-j-9.7.0.jar`(2,607,237, SHA-256 `0353648e…43eb44`)이고 protobuf-java는 없다.
  SWT Windows 제품을 오프라인 모드를 끈 새 작업 공간(기본값)으로 실행해 127.0.0.1:1에 연결했다. MySQL과 NDB Cluster
  (`jdbc:mysql:loadbalance:`, `com.mysql.jdbc.Driver`)는 다운로드 대화상자 없이 Connector/J의 "Communications link failure /
  Connection refused"로 끝났다. 스택의 `NonRegisteringDriver.java:180`은 9.7.0 소스와 맞는다(8.2.0은 189줄). MySQL 5 (Legacy)는
  예상대로 `mysql:mysql-connector-java` 5.1.49 다운로드 대화상자가 열렸다(취소함).
- 오프라인 모드를 끈 상태(기본값)의 SWT 제품 내장 드라이버는 실행해 보지 않았다(MySQL 추가 전 4종 기준). 코드상 묶음 모드에서는 4개 드라이버에
  내려받을 항목이 남지 않으므로(`getDriverDependencies`가 `null`) 다운로드 대화상자가 열리지 않는다.
- SQLite 추가 후(2026-11-07, `-Pproduct-chromite-swing` 빌드): Windows x86_64·macOS aarch64 결과물의 `drivers/`가 40개 파일,
  33,754,109 bytes로 이름·크기·SHA-256이 같다. 늘어난 파일은 `sqlite/xerial/sqlite-jdbc-3.54.1.0.jar`(12,132,578,
  SHA-256 `f8dce420…be4639`, Maven Central `.sha1` `b74a59de…61a676`과 일치), `sqlite/LICENSE.txt`(11,358), `sqlite/LICENSE-zentus.txt`(1,288)이고
  `README.txt`가 437 bytes 늘었다. 결과물의 `com.seizia.chromite.drivers.public` jar `plugin.xml`에 `drivers.sqlite.xerial`이 선언돼 있다.
  `sqlite/` 폴더를 zip으로 묶으면 12,107,861 bytes라 설치 파일은 플랫폼마다 약 12MB 커진다.
- 내장 jar만 클래스패스에 두고 내장 런타임(JBR 25.0.4.1, jlink)으로 작은 JDBC 프로그램을 실행했다. 새 `.db` 파일을 열어
  `select sqlite_version()`이 `3.54.1`을 돌려주고 표 만들기·넣기·읽기가 됐다(다운로드 없음). xerial은 처음 연결할 때 jar 안의
  네이티브 라이브러리(`sqlitejdbc.dll`, 약 1MB)를 `java.io.tmpdir`에 풀어 쓴다. Maven 모드에서도 같은 jar라 같은 동작이다.
- MySQL 내장 취소 후(2026-11-07, `-Pproduct-chromite-swing` 빌드): Windows x86_64·macOS aarch64 결과물의 `drivers/`가 37개 파일,
  31,062,133 bytes로 이름·크기·SHA-256이 같다. 직전 빌드(`42a31c9c2b`, 40개, 33,754,109 bytes)와 비교해 `mysql/LICENSE.txt`(82,896),
  `mysql/SOURCE.txt`(1,435), `mysql/mysql8/mysql-connector-j-9.7.0.jar`(2,607,237)가 없어졌고 `README.txt`(3,989 → 3,581 bytes, `mysql/` 절)만 바뀌었다.
  나머지 34개 파일은 이름·크기·SHA-256이 같다. 없어진 세 파일을 zip으로 묶으면 2,471,789 bytes라 설치 파일은 약 2.5MB 작아진다.
  `licenses/external/`에는 `gpl-2.0-universal-foss-exception.txt`(22,650 bytes, CRLF 체크아웃 기준)가 생기고 `gnu3.txt`(35,823)가 없어졌다.
  결과물 `drivers.public` jar의 `plugin.xml`에 `drivers.mysql` 선언이 없다.
  - 빌드 결과물을 새 데이터 폴더·작업 공간으로 실행(영어 화면, 오프라인 모드 끔): Driver Manager에서 MySQL·NDB Cluster·Apache Doris는
    `com.mysql:mysql-connector-j:RELEASE [8.2.0]`, Manticore는 `[9.7.0]`, GreptimeDB·TiDB는 `RELEASE`, MySQL 5 (Legacy)는 `mysql-connector-java [5.1.49]`가
    "Not downloaded"였고, Connector/J 계열 6개의 라이선스 항목은 모두 `licenses/external/gpl-2.0-universal-foss-exception.txt`("Bundled")였다.
    MySQL 드라이버의 License 탭은 새 파일의 GPLv2·Universal FOSS Exception 원문을 보였다.
  - MySQL 드라이버로 MariaDB 시험 서버(127.0.0.1:53306)에 연결: "Download driver files" 대화상자("Driver files for 'MySQL' are missing.
    com.mysql:mysql-connector-j:RELEASE")가 열렸고, 다운로드 뒤 연결됐다(`mysql-connector-j-8.2.0.jar`와 의존성 `protobuf-java-3.21.9.jar`를 Maven Central에서 받음,
    로그 "driverVersion='mysql-connector-j-8.2.0'"). 받은 뒤 라이브러리 탭은 "Downloaded"였다.
  - 같은 작업 공간에서 MySQL 오프라인 묶음을 만들었다(드라이버 1개, 파일 2개, 4MiB: Connector/J 8.2.0과 protobuf-java 3.21.9).
  - 두 번째 새 데이터 폴더·작업 공간(같은 앱)에서 오프라인 모드를 켜자 MySQL 라이브러리가 "Not downloaded (offline mode)"였고, 연결하면
    다운로드 대화상자 없이 "Driver files for 'MySQL' are not bundled and offline mode is enabled. Add the JAR files manually in the driver settings."로 끝났다.
    위 묶음을 설치("Installed 1 drivers: 2 files copied")하자 "Downloaded"가 되고, 오프라인 모드 그대로 원격 요청 없이 연결됐다.
  - NDB Cluster(`com.mysql.jdbc.Driver`): 오프라인 모드에서는 같은 안내로 실패(묶음은 MySQL 드라이버에만 등록되므로), 오프라인 모드를 끄고 다시 시작하자
    다운로드 대화상자 뒤 같은 8.2.0 jar로 연결됐다. 저장된 연결 URL은 두 드라이버 모두 `jdbc:mysql://`이다(MySQL 공급자가 URL을 만듦, 이번 변경과 무관).
  - 확인 뒤 데이터 폴더·작업 공간·묶음 파일은 지웠다.
- 앱(Swing)으로 SQLite 연결을 만드는 확인은 하지 않았다. 묶음 선언(`drivers.sqlite.xerial`)과 `plugin.xml`의 폴더 경로(`drivers/sqlite/xerial`),
  결과물의 폴더가 서로 맞는 것까지 확인했다.

### 폐쇄망의 내장되지 않은 드라이버

인터넷 PC의 드라이버 관리자에서 드라이버를 골라 설치 묶음 파일(`.chromite-drivers`)을 만들고, 폐쇄망 PC에서 그 파일을 설치한다.
파일은 온라인 다운로드와 같은 드라이버 폴더에 놓이고 같은 방식으로 등록되므로 오프라인 모드에서도 연결된다([driver-offline-bundle.md](driver-offline-bundle.md)).

### 내장 드라이버를 추가하는 방법

1. 드라이버의 `plugin.xml`에 `bundle="drivers.X"`인 폴더 경로와 `bundle="!drivers.X"`인 Maven 항목이 있는지 확인한다.
   없으면 그 둘을 추가해야 한다(Maven 항목에 `bundle="!drivers.X"`, 폴더 항목 `path="drivers/<이름>" bundle="drivers.X"`).
2. [driver-licenses.md](driver-licenses.md)에서 내장이 가능한지(A 분류) 확인한다.
3. `com.seizia.chromite.drivers.public/plugin.xml`에 `<bundle id="drivers.X"/>`를 추가한다.
4. `com.seizia.chromite.drivers.public.feature/pom.xml`의 `bundle-public-drivers`에 jar를 `artifactItem`으로 추가한다. 버전은 `plugin.xml`의 `RELEASE[x]`와 같게,
   필요한 전이 의존성은 `dependency:tree`로 확인해 하나씩 적는다(`copy`는 전이 의존성을 받지 않는다).
5. 같은 feature의 `drivers-root/drivers/<이름>/`에 라이선스 원문(LGPL·GPL이면 `SOURCE.txt`도)을 넣고 `drivers/README.txt`와 `NOTICE`를 갱신한다.

Maven Central에 없는 jar(ShardSphere)는 4번 대신 jar를 `drivers-root/drivers/<이름>/`에 커밋한다(루트 파일로 함께 설치된다). Maven 대체 항목이 없으므로
1번의 `bundle="!drivers.X"` 항목도 없다. 커밋한 blob의 SHA-256이 받은 파일과 같은지 확인한다.

### ShardSphere (2026-11-14, 앱 변경 #113)

- **무엇인가:** 데이터베이스 제품 ShardSphere(CISPHERE Inc.)의 JDBC 드라이버. Apache ShardingSphere와는 관계없다. 사용자가 jar를 줬다
  (`shardsphere.jar`, 430,801 bytes, SHA-256 `6752544cf4fe66b1728dfdbc5d4cc6a0a7d6930b8fac16560d8f95390ecf9987`, 버전 문자열 "ShardSphere 5.1.9 for JDBC 3.0",
  JDK 1.5로 빌드, 의존 jar 없음: `jdeps`로 JDK 밖 참조 없음). 클래스와 통신 연산 이름이 Altibase JDBC 드라이버와 같고 이름만 다르다(Altibase 계열 제품).
- **결정:** 사용자 결정(2026-11-14) "기본 내장 드라이버를 하나 더 추가해야 한다", 재배포 권한을 묻자 "권한 있다, 내장하라". 라이선스 기록은
  [driver-licenses.md](driver-licenses.md) "ShardSphere".
- **드라이버 정의:** [ext.altibase/plugin.xml](../../plugins/com.seizia.chromite.ext.altibase/plugin.xml)의 Altibase 공급자에 두 번째 `<driver>`(`id="shardsphere"`)를 두고,
  `generic.meta`의 `driverClass` 목록에 `shardsphere.jdbc.driver.shardsphereDriver`를 더했다. 그래서 Altibase 데이터 원본·메타 모델(시스템 카탈로그 질의, 트리, DDL, 값 처리기)을 쓴다.
  드라이버 클래스로 메타 모델을 고르므로([GenericMetaModelRegistry.getMetaModel](../../plugins/com.seizia.chromite.ext.generic/src/com/seizia/chromite/ext/generic/GenericMetaModelRegistry.java))
  드라이버를 복사한 사용자 드라이버도 같은 모델을 쓴다(`meta-model` 매개변수를 쓰지 않은 이유).
  - 이름·아이콘: 자기 데이터 원본 유형 `shardsphere`("ShardSphere")와 자기 아이콘(`icons/shardsphere_icon*.png`, 아래 "아이콘")을 쓴다. 유형을 두지 않으면 드라이버가
    공급자의 `altibase` 유형을 물려받아 큰 아이콘·로고·유형 이름(MCP 데이터 원본 목록의 `database_type`)이 Altibase가 된다(`DriverDescriptor` 생성자).
  - 기본값: 포트 `55321`은 사용자 확인(2026-11-14, 시험 서버의 `V$PROPERTY` `PORT_NO`도 55321). 드라이버 자체는 포트를 주지 않으면 25001을 쓴다
    (`shardsphereConnection.loadDefaultValues`, `javap -c`로 읽음). 데이터베이스 `memdb`는 같은 메서드의 기본값이다. 기본 사용자는 jar에 없어 비워 두었다.
  - URL 예시 `jdbc:shardsphere://{host}:{port}/{database}`, MANUAL·URL. 드라이버 매개변수는 Altibase 드라이버의 것을 그대로 썼다(모두 엔진에 관한 것, `V$DATABASE` 질의 포함).
- **고친 코드(근본 원인):** Altibase 데이터 원본은 서버 출력(PRINT) 콜백 인터페이스를 `<패키지>.jdbc.driver.AltibaseMessageCallback` 이름으로 찾았다. ShardSphere 드라이버의
  인터페이스는 `shardsphere.jdbc.driver.shardsphereMessageCallback`이라 클래스를 찾지 못하고, 서버 출력 기본값이 켜져 있어 연결 초기화(`initializeContextState`)가
  예외로 끝난다(코드로 확인). 콜백 인터페이스를 연결 클래스의 `registerMessageCallback` 매개변수에서 얻도록 바꿨다([AltibaseDataSource](../../plugins/com.seizia.chromite.ext.altibase/src/com/seizia/chromite/ext/altibase/model/AltibaseDataSource.java)).
  이름 접미사 상수 두 개는 지웠다. 실행 계획은 메서드 이름(`setExplainPlan`, `getExplainPlan`)으로 찾으므로 ShardSphere 드라이버에도 있다(`javap`).
- **묶음:** `drivers.shardsphere` 선언, 드라이버 파일은 `<file type="jar" path="drivers/shardsphere" bundle="drivers.shardsphere"/>` 하나다. Maven 대체 항목이 없으므로
  이 묶음을 선언하지 않은 제품에서는 드라이버에 파일이 없다: 연결하면 드라이버 클래스를 찾지 못하고, 사용자가 Driver Manager에서 jar를 추가한다(코드로 확인, 실행하지 않음).
  Chromite 제품은 묶음과 폴더를 한 feature로 배포하므로 해당 없다.
- **아이콘:** 사용자 지시(2026-11-14) "데이터베이스 아이콘은 turbrix에게 제공받아라". 원본은 다른 프로젝트의 44×44 PNG(SHA-256 `6833e95a…6b87f`, CISPHERE Inc. 로고의 심볼)다.
  내용은 고치지 않고 크기만 바꿨다: `shardsphere_icon.png` 16×16·`@2x` 32×32(Lanczos 축소), `shardsphere_icon_big.png` 64×64(원본 44px을 투명 64px 가운데),
  `@2x` 128×128(원본을 픽셀 두 배 88px로 가운데, 새 색을 만들지 않으려고 최근접 보간). 넓은 로고는 없어 `logoImage`를 두지 않았다. 고지는 `NOTICE`와
  [driver-licenses.md](driver-licenses.md) "ShardSphere 아이콘". 지울 때는 네 파일, `plugin.xml`의 `icon`·`iconBig` 세 곳, 그 두 고지만 고친다.
- **어두운 테마(남은 일):** 남색 사각형이 어두운 배경에서 거의 보이지 않고 녹색 부분만 보인다(아래 확인의 캡처). 벤더 아이콘에 밝은 판이나 어두운 변형을 주는 방법은
  Chromite에 없다(밝음·어두움 변형은 자체 UI 아이콘 팩 `icons/ui/<팩>/<light|dark>/`뿐, [UiIcons](../../plugins/com.seizia.chromite.ui.swing/src/com/seizia/chromite/ui/swing/UiIcons.java)).
  이 브랜치에서는 만들지 않았다. 제안: 드라이버·유형 아이콘에 선택 속성(예: `iconDark`)을 두고 `NodeIcons`가 FlatLaf 어두운 테마에서 그것을 쓰게 하거나, 벤더가 어두운 배경용 표지를 주면 그것을 쓴다.
- **확인(2026-11-14~15, 이 브랜치 빌드, Windows x86_64, 내장 런타임 JBR 25.0.4.1(`tools/jre/BundleJre.java`로 만든 `jre/`), 사설 작업 공간·데이터 폴더, EDT 테스트 에이전트):**
  - 결과물 `drivers/`는 39개 파일, 31,494,810 bytes. `drivers/shardsphere/`에 `shardsphere.jar`(SHA-256 `6752544c…9987`, 받은 파일과 같음)와 `README.txt`.
  - 드라이버 목록 검색("shard")·Driver Manager·Connect 대화상자에 ShardSphere와 그 아이콘이 보인다. Driver Manager의 라이브러리는 `drivers/shardsphere`(있음),
    `needsExternalDependencies=false`(내려받을 것 없음).
  - Connect 대화상자에서 `127.0.0.1:1`로 Test Connection: "Can't connect to the database / Communication link failure: Connection refused: connect, Msg 334337, State 08S01".
    드라이버 자체의 오류이므로 내장 jar의 클래스가 내장 런타임에서 로드됐다.
  - 실제 ShardSphere 서버(사용자 제공, 읽기만 함): 연결됨. 제품 "ShardSphere 5.1.9", 드라이버 "ShardSphere JDBC driver 5.1.9", Altibase 메타 모델, 서버 출력 콜백 등록 성공.
    Object Explorer의 스키마·테이블·열·제약·인덱스·뷰·패키지·테이블스페이스·사용자·속성이 읽혔고, 쿼리 창의 SELECT와 상태 표시줄(서버 주소:55321, `sys`, `memdb`, Auto-commit)이 맞다.
    처음 확인에서 실패한 두 가지는 아래 "고친 것(2026-11-14 검토 뒤)"에서 고쳤다: Global metadata > Replications(`SYSTEM_.SYS_REPLICATIONS_`, `SYS_REPL_HOSTS_`가 없음,
    오류 200753 42S02), 독립 함수의 매개변수(`SYS.SYS_CONTEXT`: 드라이버가 JDBC 3.0이라 `getFunctionColumns`가 없음, `AbstractMethodError`).
  - Altibase 7.3 시험 서버에 ShardSphere 드라이버: "Invalid communication protocol"(연결되지 않음). 실제 ShardSphere 서버의 프로토콜 버전은 7.1.6, Altibase 7.3은 7.1.8이다.
  - Altibase 드라이버(로컬 `Altibase.jar` 7.3.0.0.6)로 같은 서버: 연결, 트리, SELECT, `EXEC PRINTLN`의 서버 출력이 그대로 됨(바꾼 콜백 코드).
  - 증거: `D:\Tools\_scratch\shardsphere-driver\shots\`. 회귀 테스트는 작성·실행하지 않았다.
- **고친 것(2026-11-14 검토 뒤):**
  - 기본 포트: Connect 대화상자가 URL 템플릿의 `{port}`가 필수이면 기본 포트가 있어도 포트를 물어 호스트만으로는 Connect가 꺼져 있었다(Altibase도 같음).
    기본 포트가 없는 드라이버만 묻는다([swing-frontend.md](swing-frontend.md) "서버 이름과 기록", `ConnectionSettingsPanel.validateAddress`).
  - 복제 목록: 복제 카탈로그가 없는 서버는 복제가 없는 것이다. `AltibaseDataSource.getReplications`가 그 질의의 "표 없음"(SQLState 42S02 또는 0x31031)만 빈 목록으로 읽고
    다른 오류는 그대로 알린다. 제품 이름으로 가르지 않는다.
  - 함수 매개변수: `GenericProcedure.loadProcedureColumns`가 `getFunctionColumns`에서 `AbstractMethodError`나 `SQLFeatureNotSupportedException`을 받으면
    `getProcedureColumns`로 읽고 그 상수(procedureColumn*)로 종류를 해석한다. 메서드가 있는 드라이버는 이 catch에 들어가지 않으므로 전과 같다.
  - 확인(같은 날, 이 브랜치 빌드, 내장 런타임): 실제 ShardSphere 서버에 서버 이름 칸에 호스트만 적고 연결 → 55321로 연결, 저장된 연결의 포트 55321,
    URL `jdbc:shardsphere://<호스트>:55321/memdb`. Altibase 7.3에 `127.0.0.1`만 → 20300으로 연결, 저장된 포트 20300. 기본 포트가 없는 Apache Arrow Flight SQL은
    여전히 "Enter the port (server name:port)."이고 `localhost:31337`이면 문제 없음. 적은 포트(`127.0.0.1:1`)는 그대로 1로 저장된다.
    ShardSphere의 Replications는 빈 폴더(오류 없음), `SYS_CONTEXT`의 매개변수 RETURN_VALUE·NAMESPACE·PARAMETER가 보인다(디버그 로그에 대체 경로 한 번).
    Altibase 7.3의 Replications(시험 서버에 복제 없음, 전과 같이 0개)와 `SYS_CONTEXT` 매개변수 3개는 전과 같고 대체 경로를 타지 않았다.
    PostgreSQL 17은 자체 모델(`PostgreProcedure`, `GenericProcedure`를 쓰지 않음)이라 영향이 없다: 임시 스키마의 함수 하나의 매개변수 a·b가 보였다(스키마는 지움).

### 폐쇄망 오프라인 설치 경로

- 2026-11-07 결정으로 전체 드라이버를 내장하는 내부판은 만들지 않는다(위 "배포판 구성").
- 내장되지 않은 드라이버는 오프라인 설치 묶음으로 옮긴다(위 "폐쇄망의 내장되지 않은 드라이버", [driver-offline-bundle.md](driver-offline-bundle.md)).
- 벤더에서만 받을 수 있는 드라이버는 Driver Manager에서 jar 파일을 직접 추가한다([swing-driver-manager.md](swing-driver-manager.md)).

## 라이선스

- 드라이버별 버전, 라이선스, 출처, 크기, 분류 전체 목록은 [driver-licenses.md](driver-licenses.md)에 있다(법률 자문 아님).
- 요약(2026-11-06 원문 확인):

| 드라이버 | 라이선스 | 내장 |
|---|---|---|
| SQL Server (`mssql-jdbc`, Azure용 `msal4j`) | MIT | 가능 |
| SQL Server 통합 인증 DLL (`mssql-jdbc_auth`) | Microsoft Software License Terms (최종 사용자 약관 전가·면책 의무) | 제외 |
| PostgreSQL (`postgresql`) | BSD-2-Clause | 가능 |
| MariaDB Connector/J | LGPL-2.1-or-later | 가능 (jar 수정 없이, 라이선스·소스 위치 동봉) |
| Oracle (`ojdbc11`, `orai18n`, `xdb6`, `xmlparserv2`) | Oracle Free Use Terms and Conditions | 가능. 수정 없는 재배포 허용, 약관 사본 동봉·사용료 미부과 조건 |
| MySQL Connector/J | GPLv2 + Universal FOSS Exception 1.0 | 내장하지 않음(2026-11-07 결정, 처음 쓸 때 다운로드). 이전 결정(2026-11-06)은 내장: Apache-2.0으로 전체 소스를 공개하는 Chromite는 예외가 적용되는 "Other FOSS"에 해당, jar 무수정, 라이선스 원문·소스 위치(`SOURCE.txt`) 동봉. v0.1.0은 내장한 채 배포됨 |
| SQLite (Xerial `sqlite-jdbc`) | Apache-2.0 (원본 Zentus 코드는 BSD 형식, 네이티브 SQLite는 Public Domain) | 내장함(2026-11-07 결정) |
| IBM Db2·Informix, Vertica | 재배포 허용이지만 최종 사용자 약관 전가·면책 조건 | 제외(필요할 때 다운로드) |
| Teradata, SAP HANA, SAP ASE(jConnect), GBase 8s, Simba BigQuery | 재배포 금지 또는 서면 동의 필요 | 제외(필요할 때 다운로드, 사용자가 받은 jar 지정) |

- 처음 내장한 4종 크기는 의존성 포함 약 23.7MB(최소 구성 약 14.7MB)다. MySQL Connector/J 9.7.0(jar 2,607,237 bytes와
  `LICENSE.txt`·`SOURCE.txt`)은 2026-11-07에 다시 뺐다. SQLite는 jar 12,132,578 bytes와 라이선스 텍스트 두 개가 더해진다. 전체를 내장하면 약 1.7GB다.

## 미완료 항목

- v0.1.0(Connector/J 9.7.0 내장)의 GPLv2 3항 대응 소스 제공: 그 릴리스의 `SOURCE.txt`는 Oracle의 공식 소스 배포 위치만 적었다. 3항을 문자 그대로 따르려고
  설치 파일과 같은 곳에 소스 묶음을 올렸다(2026-11-08, 메인 작업): Oracle 공식 소스 배포본 `mysql-connector-j-9.7.0.tar.gz`(4,556,015 bytes,
  SHA-256 `53d8fdf1b9b8040907cecd470bf3333af96b00371d74dbe4230a15c58f0cb310`, `https://downloads.mysql.com/archives/get/p/3/file/mysql-connector-j-9.7.0.tar.gz`,
  `.java` 698개와 빌드 파일)을 GitHub `v0.1.0` Release에 첨부했다. **남은 것:** Release가 아직 초안이라, 사용자가 게시할 때 소스 묶음도 공개된다.
  0.1.1부터는 Connector/J를 내장하지 않으므로(#123) 이후 릴리스에는 소스를 첨부할 필요가 없다.
- [driver-licenses.md](driver-licenses.md)의 "확인 못함" 항목 약관 원문 확인, 내장 드라이버 라이선스 고지 형식 법률 검토
- 내장 드라이버로 실제 DB(SQL Server, Oracle, PostgreSQL, MariaDB)에 로그인까지 하는 확인(드라이버 로드까지만 확인함), macOS 실행 확인
- ShardSphere(위 "ShardSphere"): 어두운 테마에서 아이콘의 남색 부분이 보이지 않는다. 기본 사용자, 샤딩이 켜진 서버(시험 서버는 `SHARD_META_ENABLE=0`)에서의 동작, macOS는 확인하지 않았다.

## 변경 이력

- 2026-11-06 최초 작성. 현재 드라이버 로딩 구조 분석, 공개판·내부판 구성, 오프라인 우선 조회 설계를 정리.
- 2026-11-06 드라이버별 라이선스 조사 결과([driver-licenses.md](driver-licenses.md)) 반영. Oracle FUTC 재배포 허용 확인,
  MySQL Connector/J에 Universal FOSS Exception 적용 확인, SQL Server 통합 인증 DLL 공개판 제외, 크기 실측.
- 2026-11-06 공개판 구현. `com.seizia.chromite.drivers.public`으로 4개 묶음 선언, `ce.feature` 빌드에서 jar를 받아 `drivers/`에 설치,
  라이선스 원문·LGPL 소스 안내·`NOTICE` 추가. `maven-local`을 가장 먼저 찾도록 변경, 오프라인 모드 설정(`ui.drivers.offline`) 추가.
- 2026-11-06 묶음 선언 플러그인과 jar 받기·루트 파일을 `ce.feature`에서 전용 feature `com.seizia.chromite.drivers.public.feature`로 옮김.
  SWT 제품과 Swing 제품이 같은 feature를 포함해 같은 `drivers/`를 배포한다.
- 2026-11-06 `drivers-root/**/*.txt`를 루트 `.gitattributes`에서 `text eol=lf`로 고정. Windows `core.autocrlf=true` 체크아웃에서도
  라이선스 텍스트가 LF로 들어가 배포되는 `drivers/` 바이트가 체크아웃 설정에 따라 달라지지 않는다(텍스트 11개, 70,322 bytes).
- 2026-11-06 설치 폴더의 `licenses/external/readme.txt`(원본 `docs/root/licenses/external/readme.txt`)가 "드라이버는 제품에 포함되지 않는다"고
  적고 있어, 일부 드라이버는 내장되며 `drivers/README.txt`와 `NOTICE`를 보라고 고침.
- 2026-11-06 공개판에 MySQL Connector/J 9.7.0 내장(사용자 결정). `drivers.mysql` 선언, `drivers/mysql/mysql8/`에 jar,
  `drivers/mysql/`에 jar 안의 `LICENSE` 원문과 `SOURCE.txt`, `README.txt`·`NOTICE` 갱신. protobuf-java(X DevAPI 전용)는 넣지 않음.
  MySQL 5 (Legacy) 드라이버의 묶음 ID를 `ext.mysql/plugin.xml`에서 `drivers.mysql5`로 분리해 Maven 다운로드(5.1.49)를 유지.
- 2026-11-07 Microsoft Store 제품 삭제([build.md](build.md) "빌드하는 제품")에 맞춰 "플러그인을 따로 만든 이유"에서 msstore 제품 언급을 뺌.
- 2026-11-07 SWT 프런트엔드 삭제([swt-removal.md](swt-removal.md)): 드라이버 feature를 포함하는 제품은 Swing 하나, 오프라인 모드 설정 노드는 `com.seizia.chromite.ui.swing`, 설정 화면은 SWT와 함께 없어짐.
- 2026-11-07 배포판을 하나로 정함(사용자 결정). 이전 결정(2026-11-06)은 공개판(주요 DB만 내장)과 내부판(전체 내장)의 두 판이었다.
  이제 주요 DB(SQL Server, Oracle, PostgreSQL, MariaDB, MySQL, SQLite)만 내장하고 나머지는 필요할 때 내려받는다.
  폐쇄망용 별도 오프라인 설치 경로는 계획으로 남김(별도 작업). "내부판 계획" 절을 "폐쇄망 오프라인 설치 경로"로 바꾸고 "공개판"을 단일 제품 기준으로 고침.
  SQLite(Xerial `sqlite-jdbc` 3.54.1.0) 내장: `drivers.sqlite.xerial` 선언, `drivers/sqlite/xerial/`에 jar, `drivers/sqlite/`에 jar 안의 라이선스 두 개,
  `README.txt`·`NOTICE` 갱신. feature·플러그인 표시 이름에서 "(Public Edition)"을 뺌. ID(`drivers.public`)는 그대로 둠.
  `drivers/README.txt`가 오프라인 모드 위치를 SWT와 함께 없어진 "Preferences > Connections > Drivers"로 적고 있어 "Tools > Driver Manager"로 고침.
- 2026-11-07 "폐쇄망의 내장되지 않은 드라이버" 절 추가: 오프라인 설치 묶음([driver-offline-bundle.md](driver-offline-bundle.md)).
- 2026-11-07 MySQL Connector/J 내장 취소(사용자 결정 "빼자. 많이 쓰지도 않는 db이다", 이전 결정 2026-11-06 내장). `drivers.mysql` 선언·`artifactItem`·
  `drivers/mysql/` 텍스트·`README.txt` 절·`NOTICE` 항목을 지우고, MySQL 5 (Legacy)의 묶음 ID를 `drivers.mysql`로 되돌림.
  MySQL 계열 6개 드라이버의 라이선스 표기를 GPLv3(`gnu3.txt`, 삭제)에서 GPLv2 + Universal FOSS Exception(`gpl-2.0-universal-foss-exception.txt`)으로 고침.
  v0.1.0은 Connector/J를 내장한 채 배포됐다는 기록 추가. 내장 목록은 SQL Server, Oracle, PostgreSQL, MariaDB, SQLite.
- 2026-11-08 "미완료 항목": v0.1.0 Release에 Connector/J 9.7.0 소스 묶음을 첨부한 기록(크기·SHA-256·출처, 초안 Release 게시 때 공개, 0.1.1부터 해당 없음).
  MySQL 5 (Legacy)·StarRocks 라이선스 표기를 고치고(evening #12) 미완료 항목에서 뺌.
- 2026-11-08 "로컬 파일 우선" 절 추가(후속 #62): 사용자가 추가한 로컬 jar·폴더에 드라이버 클래스가 있으면 미리 정의된 다운로드 항목을
  받지도 클래스패스에 넣지도 않는다(`DriverLoaderDescriptor`).
- 2026-11-09 macOS 앱 묶음 이름을 `ChromiteSwing.app`에서 `Chromite.app`으로 바꾼 것을 반영(앱 변경 #22, `chromite/install-update-2`, [distribution.md](distribution.md) "macOS 앱 이름"). 지난 확인 기록의 옛 이름은 그때의 결과라 그대로 둠.
- 2026-11-12 "내려받기를 취소하면" 절 추가(앱 변경 #85, `chromite/small-fixes-r3`): 다운로드를 취소하면 그 연결 시도를 끝냄(`DriverLoaderDescriptor.checkFilesPresence`).
- 2026-11-14 ShardSphere 드라이버 내장(앱 변경 #113, `chromite/shardsphere-driver`, 사용자 결정): Altibase 공급자의 두 번째 드라이버와 메타 모델,
  `drivers.shardsphere` 선언, Maven Central에 없는 jar를 저장소에 커밋(첫 예외), 자기 유형·아이콘, Altibase 서버 출력 콜백을 클래스 이름이 아니라 메서드 매개변수로 찾게 고침.
  내장 목록은 SQL Server, Oracle, PostgreSQL, MariaDB, SQLite, ShardSphere.
- 2026-11-14 검토 뒤 고침(같은 브랜치): Connect 대화상자의 기본 포트, 복제 카탈로그가 없는 서버의 복제 목록, `getFunctionColumns`가 없는 드라이버의 함수 매개변수
  ("ShardSphere"의 "고친 것").
