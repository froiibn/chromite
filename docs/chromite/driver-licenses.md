# JDBC 드라이버 라이선스 목록

> 이 문서는 어떤 드라이버를 설치 파일에 넣을지 정하기 위한 엔지니어링 조사 결과다. **법률 자문이 아니다.**
> 배포하기 전에 라이선스 고지 방식과 함께 법률 검토를 받는다.

## 목적

[drivers.md](drivers.md)의 내장 드라이버를 정하려면 드라이버마다 재배포가 가능한지 알아야 한다.
이 문서는 `plugin.xml`이 참조하는 모든 JDBC 드라이버 파일의 버전, 라이선스, 출처, 크기를 정리하고
내장 가능 여부를 분류한다.

- **배포판(2026-11-07 결정):** Chromite는 배포판이 하나다. 주요 DB(SQL Server, Oracle, PostgreSQL, MariaDB, MySQL, SQLite)만 내장하고
  나머지는 필요할 때 내려받는다. 폐쇄망에는 별도 오프라인 설치 경로를 둔다(계획). 이전 결정(2026-11-06)은 공개판·내부판 두 판이었고,
  아래 분류 표의 마지막 열은 그때의 내부판 기준이다. 내부판은 만들지 않으므로 B·C 분류는 어느 경우에도 내장하지 않는다.

## 조사 방법 (2026-11-06)

- **대상:** `plugins/*/plugin.xml`의 `<driver>` 아래 `<file type="jar|lib">` 중 실제로 내려받는 파일.
  묶음 대체 경로(`bundle="drivers.*"`인 `drivers/...`)는 같은 파일을 가리키므로 제외했다.
  Maven 좌표 112개, `https:` URL 9개, `repo:/` 파일 5개다.
- **버전:** `RELEASE[x]`는 `x`가 고정 버전이다
  ([DriverLibraryMavenArtifact.java:119](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverLibraryMavenArtifact.java:119)).
  버전 없는 `RELEASE`·`LATEST`는 조사일의 `maven-metadata.xml` 최신 release를 적고 "(RELEASE)"로 표시했다.
  표의 버전은 Chromite가 지금 받는 버전이고, 비고의 "최신"은 조사일 기준 Maven Central 최신 버전이다.
- **라이선스:** Maven Central POM의 `<licenses>`(없으면 parent POM에서 상속), 벤더 약관 원문,
  `plugin.xml`의 `type="license"` 파일 순으로 확인했다. 출처 열은 실제로 확인한 문서다.
- **전이 의존성:** `maven-dependency-plugin` 3.8.1 `dependency:tree`로 compile·runtime 범위 의존성을 모았다(POM만 받고 jar는 받지 않음).
  `ignore-dependencies="true"` 항목은 의존성을 빼고 계산했다. DBeaver 자체 해석기와 결과가 조금 다를 수 있다.
  특히 `load-optional-dependencies="true"`인 ClickHouse, Databend, JDBCX, Redshift, TiDBLake는 선택 의존성까지 받으므로 실제 크기가 더 클 수 있다.
- **크기:** 저장소에 HTTP HEAD 요청을 보내 받은 `Content-Length`의 합(의존성 포함, 1MB = 10⁶ bytes).
  jar는 이미 압축돼 있어 설치 파일 크기도 이와 비슷하다.
- **`repo:/` 파일:** `repo:/`는 `ui.drivers.sources` 기본값인 `https://dbeaver.io/files/jdbc/`(DBeaver 소유 서버)에서 받는다
  ([ModelPreferences.java:411](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/ModelPreferences.java:411)).

## 분류 기준

| 분류 | 의미 | 내장 | (참고) 이전 내부판 기준 |
|---|---|---|---|
| A | OSI 승인 오픈소스 라이선스(Apache, MIT, BSD, MPL, EPL, LGPL 등) | 내장 가능. 라이선스 원문 동봉, LGPL은 jar 수정 없이 별도 파일로 두고 소스 위치 안내 | 내장 |
| A (GPL 조건) | GPLv2 + 예외 조항. OSI 라이선스로 전체 소스를 공개하는 앱과의 결합을 예외로 허용 | 내장 가능. jar 자체의 GPL 의무(원문 동봉, 대응 소스 제공)는 남는다 | 내장 |
| A* | 오픈소스는 아니지만 벤더 약관이 무상 재배포를 명시적으로 허용하고 조건이 단순(약관 동봉, 무수정 등) | 내장 가능. "Apache-2.0이 아닌 구성 요소"로 따로 고지 | 내장 |
| B | 재배포는 허용되나 최종 사용자 약관 전가·면책 같은 조건이 붙어 Apache-2.0 공개 배포와 맞지 않음, 또는 약관을 확인하지 못함 | 내장하지 않음(온라인 다운로드) | 내장 가능. 단 고객사 장비에 설치해 넘기면 재배포에 해당하므로 그 경우 조건 확인 |
| C | 재배포 금지, 서면 동의 필요, 클릭 동의 약관 | 내장하지 않음 | 내장하지 않음. 사용자(또는 고객사)가 자기 라이선스로 받은 jar를 지정 |

## 주요 DB 드라이버(처음 내장한 4종)

| 드라이버 | 버전 | 라이선스 | 출처 | 크기(MB) | 분류 | 비고 |
|---|---|---|---|---|---|---|
| SQL Server (`microsoft`, `babelfish`) | `mssql-jdbc` 13.4.0.jre11 | MIT | [repo1.maven.org](https://repo1.maven.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.4.0.jre11/mssql-jdbc-13.4.0.jre11.pom) | 1.5 | A | 최신 14.1.0.jre11-preview. 의존성 없음 |
| Azure SQL (`azure`) 추가분 | `msal4j` 1.16.0 | MIT (의존성 Apache-2.0·MIT·BSD-3) | [repo1.maven.org](https://repo1.maven.org/maven2/com/microsoft/azure/msal4j/1.16.0/msal4j-1.16.0.pom) | 4.3 | A | Entra ID 인증용. 의존 jar 13개 |
| SQL Server Windows 통합 인증 DLL | `mssql-jdbc_auth` 13.4.0.x64 | Microsoft Software License Terms | [raw.githubusercontent.com](https://raw.githubusercontent.com/microsoft/mssql-jdbc/v13.4.0/mssql-jdbc_auth_LICENSE) | 0.1 | B | 재배포는 허용되나 최종 사용자에게 Microsoft를 같은 수준으로 보호하는 약관 동의, 면책(indemnify) 의무. 내장하지 않음 |
| Oracle (`oracle_thin`) | `ojdbc11` 23.2.0.0<br>`orai18n` 23.2.0.0<br>`xdb6` 18.3.0.0<br>`xmlparserv2` 23.2.0.0 | Oracle Free Use Terms and Conditions (FUTC) | [www.oracle.com](https://www.oracle.com/downloads/licenses/oracle-free-license.html) | 12.7 | A* | 최신 23.26.4.0.0. `xdb6` 18.3.0.0이 `xmlparserv2` 18.3.0.0(1.9MB)을 끌어와 23.2.0.0과 중복된다(크기에 포함) |
| PostgreSQL (`postgres-jdbc`) | `postgresql` 42.7.14<br>`postgis-jdbc` 2.5.0<br>`postgis-geometry` 2.5.0<br>`waffle-jna` 3.5.1 | BSD-2-Clause (pgjdbc), LGPL-2.1 (postgis, 선택), MIT (waffle, 선택) | [repo1.maven.org](https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.pom) | 6.2 | A | pgjdbc 본체+checker-qual(MIT)은 1.5MB. waffle-jna는 SSPI(`gsslib=sspi`)일 때만 쓰며 JNA(LGPL-2.1+ 또는 Apache-2.0 선택)를 포함 |
| MariaDB (`mariaDB`) | `mariadb-java-client` 3.5.10 | LGPL-2.1-or-later | [repo1.maven.org](https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.10/mariadb-java-client-3.5.10.pom) | 0.8 | A | 최신 4.0.0. 의존성 없음 |

드라이버별 조건:

- **SQL Server:** `mssql-jdbc`와 `msal4j`는 MIT라 고지만 하면 된다.
  Windows 통합 인증용 `mssql-jdbc_auth` DLL은 Microsoft 독점 약관이다. REDIST 목록상 재배포 대상이지만
  배포자가 최종 사용자에게 Microsoft를 같은 수준으로 보호하는 약관에 동의를 받고 Microsoft를 면책해야 한다.
  Apache-2.0으로 배포하는 Chromite와 맞지 않으므로 **넣지 않는다**(묶음의 `drivers/mssql/new/auth/x64`도 비운다,
  [mssql/plugin.xml:201](../../plugins/com.seizia.chromite.ext.mssql/plugin.xml:201)).
  DLL 없이도 `authenticationScheme=JavaKerberos`, `NTLM` 같은 순수 Java 인증은 쓸 수 있다.
- **Oracle:** FUTC는 오픈소스 라이선스가 아니지만 "수정하지 않은 프로그램의 재배포"를 허용한다. 조건은
  ① 배포할 때 FUTC 사본 포함, ② 최종 사용자에게 프로그램 사용료를 따로 받지 않음, ③ Oracle 표시 제거 금지,
  ④ 수출 통제 준수, ⑤ 리버스 엔지니어링 금지다(2021-06-09 판, Maven POM의 `xdb6` 18.3.0.0은 2018-10-08 판 원문 수록).
  Chromite는 무료로 배포하므로 충족한다. **drivers.md의 "원문 확인 필요"는 재배포 가능으로 확인됐다.**
  `xdb6` 18.3.0.0이 `xmlparserv2` 18.3.0.0을 의존성으로 끌어와 23.2.0.0과 중복된다. 내장할 때는 23.2.0.0 하나만 넣는다(10.9MB).
- **PostgreSQL:** pgjdbc는 BSD-2-Clause다. PostGIS(LGPL-2.1)와 waffle-jna(MIT, SSPI 인증 전용)는 선택 파일이다.
  waffle이 끌어오는 JNA는 LGPL-2.1+와 Apache-2.0 중 선택할 수 있어 Apache-2.0으로 쓰면 된다.
- **MariaDB:** LGPL-2.1-or-later다. jar를 수정하지 않고 별도 파일로 두며, LGPL 원문과 소스 위치(GitHub 태그) 안내를 동봉한다.

크기 합계:

| 구성 | 크기 |
|---|---|
| 처음 4종 전체(Azure용 msal4j, PostGIS, waffle 포함, Oracle 중복 제외) | 약 23.7MB |
| 최소 구성(SQL Server jar, Oracle 4종, pgjdbc, MariaDB) | 약 14.7MB |
| MySQL Connector/J 9.x 추가 시 | +약 4.5MB (protobuf-java 포함 조사값. 실제로는 protobuf-java를 빼고 9.7.0 jar 2.6MB만 넣음) |
| SQLite(Xerial) 추가 시 | +12.1MB(jar 12,132,578 bytes, 의존성 없음) |

## MySQL Connector/J

| 드라이버 | 버전 | 라이선스 | 출처 | 크기(MB) | 분류 | 비고 |
|---|---|---|---|---|---|---|
| MySQL 8 / NDB / Apache Doris | `mysql-connector-j` 8.2.0 | GPLv2 + Universal FOSS Exception 1.0 (protobuf-java는 BSD-3) | [raw.githubusercontent.com](https://raw.githubusercontent.com/mysql/mysql-connector-j/release/8.x/LICENSE) | 4.2 | A (GPL 조건) | 최신 26.10.0 |
| Manticore Search | `mysql-connector-j` 9.7.0 | GPLv2 + Universal FOSS Exception 1.0 | [raw.githubusercontent.com](https://raw.githubusercontent.com/mysql/mysql-connector-j/release/9.x/LICENSE) | 4.5 | A (GPL 조건) |  |
| GreptimeDB, TiDB | `mysql-connector-j` 26.10.0 (RELEASE) | GPLv2 + Universal FOSS Exception 1.0 | [raw.githubusercontent.com](https://raw.githubusercontent.com/mysql/mysql-connector-j/trunk/LICENSE) | 4.4 | A (GPL 조건) | TiDB는 `maven:/com/mysql:...`로 적혀 있으나 그룹 `com/mysql`이 URL에서 `com.mysql`과 같은 경로가 되어 정상 동작 |
| MySQL 5 (Legacy), MySQL (Old) | `mysql-connector-java` 5.1.49 | GPLv2 + MySQL FOSS License Exception | [raw.githubusercontent.com](https://raw.githubusercontent.com/mysql/mysql-connector-j/release/5.1/README) | 1.0 | A (GPL 조건) | 2020년 이후 갱신 없음. 내장 비권장 |
| StarRocks | `starrocks-connector-j` 1.1.2 | GPLv2 + Universal FOSS Exception 1.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/starrocks/starrocks-connector-j/1.1.2/starrocks-connector-j-1.1.2.pom) | 4.5 | A (GPL 조건) | Connector/J 포크 |

- **현재 라이선스:** 8.x, 9.x, 26.x 모두 "GPLv2 + Universal FOSS Exception 1.0"이다(각 브랜치 `LICENSE`와 POM `<licenses>`에서 확인).
- **Universal FOSS Exception의 내용:** OSI 승인 또는 FSF 자유 라이선스로 **전체 대응 소스와 함께** 배포하는 소프트웨어("Other FOSS")는
  Connector/J와 정적·동적으로 결합해 배포해도 Other FOSS 쪽 라이선스를 바꿀 필요가 없다(예외 iii항).
  Connector/J 자체는 계속 GPLv2다.
- **내장할 수 있다(조건부).** Chromite는 Apache-2.0으로 전체 소스를 공개하므로 Other FOSS에 해당한다. 남는 의무는 다음과 같다.
  - Connector/J jar를 수정하지 않는다.
  - GPLv2 원문과 Universal FOSS Exception 원문(Connector/J `LICENSE` 파일)을 동봉한다.
  - GPLv2 3항에 따라 Connector/J 대응 소스를 함께 제공하거나 3년간 유효한 서면 제공 약속을 동봉한다.
- **주의:**
  - 예외는 Other FOSS와 함께 배포할 때만 적용된다. 누군가 Chromite를 소스 비공개로 개조해 재배포하면 그쪽에서는 Connector/J를 빼야 한다.
    Apache-2.0은 이런 재배포를 허용하므로 Chromite 고지(`NOTICE`)에 이 점을 적는다.
  - 비공개 코드를 넣은 Chromite를 고객사에 넘기면 Other FOSS 조건을 다시 확인해야 한다.
  - Connector/J 5.1.49(`mysql-connector-java`)는 예전 "MySQL FOSS License Exception"이 적용되며 2020년 이후 갱신이 없어 내장하지 않는다.
  - 지금 MySQL 드라이버는 8.2.0으로 고정돼 있다. 내장한다면 9.x(최신 9.7.0) 또는 26.x로 올린다.
- **결론:** drivers.md의 "보류"는 "내장 가능(조건부)"로 바뀐다.
- **결정 (2026-11-06, 사용자):** MySQL Connector/J를 내장한다. 구현은 [drivers.md](drivers.md)의 "구현" 참고.
  - 넣은 것: `mysql-connector-j` 9.7.0 jar 하나(Maven Central 원본, SHA-1 `4e6e3e8765e4a9d0609744b3e8fe75afe1b98cba`
    = Maven Central `.sha1`, SHA-256 `0353648eaa1c91e0f4020c959abf756bc866ffd583df22ae6b6f6e0cbd43eb44`).
    Oracle 소스 배포본(`mysql-connector-j-9.7.0.tar.gz`) 안의 jar와 SHA-256이 같다.
  - 위 의무의 이행: jar 무수정. `drivers/mysql/LICENSE.txt`는 jar 안 `LICENSE`를 바꾸지 않고 넣었다(GPLv2, Universal FOSS Exception 1.0,
    제3자 고지 포함. GitHub `9.7.0` 태그의 `LICENSE`와는 "Last updated" 날짜 한 줄만 다르다).
    `drivers/mysql/SOURCE.txt`에 대응 소스 위치(Oracle 소스 배포본, GitHub 태그, Maven `-sources.jar`)와 소스 비공개 포크 주의를 적었고,
    `NOTICE`에도 같은 주의를 적었다.
  - 남은 것: GPLv2 3항을 문자 그대로 따르려면 소스를 설치 파일과 같은 곳에 함께 두거나 서면 제공 약속을 넣어야 한다.
    지금은 Oracle이 공개한 위치만 안내한다(법률 검토 항목, [drivers.md](drivers.md) "미완료 항목").
  - `mysql-connector-java` 5.1.49(MySQL 5 (Legacy))는 위 방침대로 내장하지 않는다. 묶음 ID를 `drivers.mysql5`로 분리해
    Maven 다운로드가 그대로 동작한다.

## 전체 목록

### A: 오픈소스

| 드라이버 | 버전 | 라이선스 | 출처 | 크기(MB) | 분류 | 비고 |
|---|---|---|---|---|---|---|
| Greenplum / Cloudberry / Greengage | `postgresql` 42.7.14<br>`postgis-jdbc` 2.5.0<br>`postgis-jdbc-jtsparser` 2.5.0<br>`postgis-geometry` 2.5.0 | BSD-2-Clause, LGPL-2.1 | [repo1.maven.org](https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.pom) | 1.5 | A | TimescaleDB, YugabyteDB, CockroachDB, Materialize, CrateDB, RisingWave, PostgreSQL(Old)도 같은 pgjdbc만 사용(1.5MB) |
| Amazon Redshift | `redshift-jdbc42` 2.2.6<br>`ssooidc` 2.31.2<br>`jackson-core` 2.16.0<br>`jackson-databind` 2.16.0 | Apache-2.0 (POM 기준. GitHub 저장소 표기는 BSD-2-Clause) | [repo1.maven.org](https://repo1.maven.org/maven2/com/amazon/redshift/redshift-jdbc42/2.2.6/redshift-jdbc42-2.2.6.pom) | 13.7 | A | 최신 2.3.0 |
| Google Cloud SQL - PostgreSQL | `postgresql` 42.7.14<br>`postgres-socket-factory` 1.30.2 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.pom) | 61.5 | A | Google Cloud SDK 의존성 78개 |
| Yellowbrick | `yellowbrick-jdbc` 0.9.3 | MIT | [repo1.maven.org](https://repo1.maven.org/maven2/io/yellowbrick/yellowbrick-jdbc/0.9.3/yellowbrick-jdbc-0.9.3.pom) | 2.5 | A |  |
| OceanBase | `oceanbase-client` 2.4.18 | LGPL-2.1 | [repo1.maven.org](https://repo1.maven.org/maven2/com/oceanbase/oceanbase-client/2.4.18/oceanbase-client-2.4.18.pom) | 1.2 | A |  |
| jTDS (SQL Server Old, Sybase jTDS) | `jtds` 1.3.1 (RELEASE) | LGPL-2.1 | [repo1.maven.org](https://repo1.maven.org/maven2/net/sourceforge/jtds/jtds/1.3.1/jtds-1.3.1.pom) | 0.4 | A | `ntlmauth.dll`(x86·x64)은 DBeaver 서버(`repo:/`)에서 받는다. jTDS 배포본 구성 요소 |
| Snowflake | `snowflake-jdbc` 4.3.4 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/net/snowflake/snowflake-jdbc/4.3.4/snowflake-jdbc-4.3.4.pom) | 106.9 | A | 의존성을 모두 포함한 단일 jar |
| Databricks | `databricks-jdbc` 3.4.4 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/databricks/databricks-jdbc/3.4.4/databricks-jdbc-3.4.4.pom) | 43.0 | A | 오픈소스 드라이버(3.x). 단일 jar |
| Google BigQuery | `google-cloud-bigquery-jdbc` 1.2.0 | Apache-2.0 (의존성 Apache·BSD·MIT·Go License·Public Domain) | [repo1.maven.org](https://repo1.maven.org/maven2/com/google/cloud/google-cloud-bigquery-jdbc/1.2.0/google-cloud-bigquery-jdbc-1.2.0.pom) | 63.0 | A | Google 자체 드라이버. 의존 jar 107개. 최신 1.8.0 |
| Google Cloud Spanner | `google-cloud-spanner-jdbc` 2.33.1 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/google/cloud/google-cloud-spanner-jdbc/2.33.1/google-cloud-spanner-jdbc-2.33.1.pom) | 47.9 | A | 의존 jar 73개 |
| Google Cloud Spanner (Deprecated) | `spanner-jdbc` 1.1.6 (RELEASE) | MIT | [repo1.maven.org](https://repo1.maven.org/maven2/nl/topicus/spanner-jdbc/1.1.6/spanner-jdbc-1.1.6.pom) | 21.1 | A |  |
| ClickHouse | `clickhouse-jdbc` 0.10.0<br>`httpclient5` 5.4.4 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/clickhouse/clickhouse-jdbc/0.10.0/clickhouse-jdbc-0.10.0.pom) | 12.0 | A | `load-optional-dependencies`라 실제로는 더 받을 수 있다 |
| ClickHouse (Legacy) | `clickhouse-jdbc` 0.3.2 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/ru/yandex/clickhouse/clickhouse-jdbc/0.3.2/clickhouse-jdbc-0.3.2.pom) | 3.4 | A |  |
| DuckDB, MotherDuck | `duckdb_jdbc` 2.0.0.1 (RELEASE)<br>`duckdb_jdbc:windows_arm64` 2.0.0.1 (RELEASE) | MIT | [repo1.maven.org](https://repo1.maven.org/maven2/org/duckdb/duckdb_jdbc/2.0.0.1/duckdb_jdbc-2.0.0.1.pom) | 95.2 | A | 기본 jar에 전 플랫폼 네이티브 라이브러리 포함(80MB) + Windows ARM64 전용 jar(15MB) |
| SQLite (Xerial) | `sqlite-jdbc` 3.54.1.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.54.1.0/sqlite-jdbc-3.54.1.0.pom) | 12.1 | A | 네이티브 SQLite(Public Domain) 포함. **2026-11-07 내장**(사용자 결정, 3.54.1.0 고정, [drivers.md](drivers.md)) |
| LibSQL | `com.dbeaver.jdbc.driver.libsql` 1.0.8 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/dbeaver/jdbc/com.dbeaver.jdbc.driver.libsql/1.0.8/com.dbeaver.jdbc.driver.libsql-1.0.8.pom) | 0.0 | A | DBeaver 사 배포 |
| H2 Embedded / Server | `h2` 2.4.240 | MPL-2.0 또는 EPL-1.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/h2database/h2/2.4.240/h2-2.4.240.pom) | 2.7 | A | 1.4.199(2.2MB), 2.1.210(2.5MB) 정의도 같은 라이선스. 최신 2.5.252 |
| H2GIS | `h2gis` 2.2.5 (RELEASE) | LGPL-3.0+ (의존성 EPL-2.0/EDL, ISC 등) | [repo1.maven.org](https://repo1.maven.org/maven2/org/orbisgis/h2gis/2.2.5/h2gis-2.2.5.pom) | 13.1 | A |  |
| Apache Derby | `derby` 10.17.1.0<br>`derbyclient` 10.17.1.0<br>`derbyshared` 10.17.1.0<br>`derbytools` 10.17.1.0 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/derby/derby/10.17.1.0/derby-10.17.1.0.pom) | 4.5 | A |  |
| HSQLDB | `hsqldb` 2.7.4 (RELEASE) | HSQLDB License (BSD 계열) | [repo1.maven.org](https://repo1.maven.org/maven2/org/hsqldb/hsqldb/2.7.4/hsqldb-2.7.4.pom) | 1.7 | A |  |
| Firebird (Jaybird) | `jaybird` 5.0.4.java11<br>`jna` 5.14.0 | LGPL-2.1+ (일부 BSD-3), JNA는 LGPL-2.1+ 또는 Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/firebirdsql/jdbc/jaybird/5.0.4.java11/jaybird-5.0.4.java11.pom) | 3.4 | A | JNA는 Embedded 드라이버만 사용. 최신 6.0.7 |
| Db2 for IBM i (JTOpen) | `jt400` 21.0.7 (RELEASE) | IBM Public License 1.0 (OSI 승인) | [repo1.maven.org](https://repo1.maven.org/maven2/net/sf/jt400/jt400/21.0.7/jt400-21.0.7.pom) | 6.3 | A |  |
| Trino | `trino-jdbc` 484 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/io/trino/trino-jdbc/484/trino-jdbc-484.pom) | 10.6 | A |  |
| PrestoDB | `presto-jdbc` 0.300 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/facebook/presto/presto-jdbc/0.300/presto-jdbc-0.300.pom) | 15.5 | A |  |
| PrestoSQL | `presto-jdbc` 350 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/io/prestosql/presto-jdbc/350/presto-jdbc-350.pom) | 6.7 | A | 2020년 이후 갱신 없음(Trino로 이름 변경) |
| Apache Hive 4+ | `hive-jdbc:standalone` 4.0.1 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/hive/hive-jdbc/4.0.1/hive-jdbc-4.0.1.pom) | 43.7 | A |  |
| Apache Hive 2 | `hive-jdbc-uber` 2.6.5.0-292 | Apache-2.0 (plugin.xml 표기) | [github.com](https://github.com/timveil/hive-jdbc-uber-jar/releases/download/v1.9-2.6.5/hive-jdbc-uber-2.6.5.0-292.jar) | 18.5 | A | 개인 GitHub 저장소의 uber jar. 원 저장소 라이선스 파일은 확인하지 못함 |
| Apache Kyuubi | `kyuubi-hive-jdbc-shaded` 1.12.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/kyuubi/kyuubi-hive-jdbc-shaded/1.12.0/kyuubi-hive-jdbc-shaded-1.12.0.pom) | 21.0 | A |  |
| Apache Phoenix | `phoenix-client` 4.14.1-HBase-1.4 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/phoenix/phoenix-client/4.14.1-HBase-1.4/phoenix-client-4.14.1-HBase-1.4.pom) | 108.0 | A | 단일 jar 108MB |
| Apache Drill | `drill-jdbc-all` 1.22.0 (RELEASE) | Apache-2.0 (logback EPL-1.0/LGPL-2.1) | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/drill/exec/drill-jdbc-all/1.22.0/drill-jdbc-all-1.22.0.pom) | 59.4 | A |  |
| Apache Ignite | `ignite-core` 2.16.0 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/ignite/ignite-core/2.16.0/ignite-core-2.16.0.pom) | 14.5 | A |  |
| Apache Calcite Avatica | `avatica-core` 1.28.0 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/calcite/avatica/avatica-core/1.28.0/avatica-core-1.28.0.pom) | 7.7 | A |  |
| Apache Kylin | `kylin-jdbc` 5.0.5 (RELEASE)<br>`jaxb-api` 2.4.0-b180830.0359 (RELEASE) | Apache-2.0, jaxb-api CDDL-1.1/GPL-2.0-CE | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/kylin/kylin-jdbc/5.0.5/kylin-jdbc-5.0.5.pom) | 20.8 | A |  |
| Apache Solr | `solr-solrj` 10.1.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/solr/solr-solrj/10.1.0/solr-solrj-10.1.0.pom) | 4.3 | A |  |
| Apache Arrow Flight SQL | `flight-sql-jdbc-driver` 19.0.0 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/arrow/flight-sql-jdbc-driver/19.0.0/flight-sql-jdbc-driver-19.0.0.pom) | 47.3 | A |  |
| GizmoSQL | `gizmosql-jdbc-driver` 1.8.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/gizmodata/gizmosql-jdbc-driver/1.8.0/gizmosql-jdbc-driver-1.8.0.pom) | 48.2 | A |  |
| Apache IoTDB, TimechoDB | `iotdb-jdbc` 2.0.5 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/apache/iotdb/iotdb-jdbc/2.0.5/iotdb-jdbc-2.0.5.pom) | 24.6 | A |  |
| TDengine, TDengine Cloud | `taos-jdbcdriver` 3.10.0 (RELEASE) | MIT | [repo1.maven.org](https://repo1.maven.org/maven2/com/taosdata/jdbc/taos-jdbcdriver/3.10.0/taos-jdbcdriver-3.10.0.pom) | 26.2 | A |  |
| DolphinDB | `jdbc` 3.00.1.0 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/dolphindb/jdbc/3.00.1.0/jdbc-3.00.1.0.pom) | 1.5 | A |  |
| OrientDB | `orientdb-jdbc` 3.2.57 (RELEASE) | Apache-2.0 (의존성 Bouncy Castle, ICU, GraalVM UPL, jnr-posix EPL/GPL/LGPL) | [repo1.maven.org](https://repo1.maven.org/maven2/com/orientechnologies/orientdb-jdbc/3.2.57/orientdb-jdbc-3.2.57.pom) | 70.8 | A |  |
| OmniSci | `omnisci-jdbc` 5.10.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/omnisci/omnisci-jdbc/5.10.0/omnisci-jdbc-5.10.0.pom) | 6.8 | A |  |
| CrateDB (Legacy) | `crate-jdbc` 2.7.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/io/crate/crate-jdbc/2.7.0/crate-jdbc-2.7.0.pom) | 8.3 | A |  |
| CUBRID | `cubrid-jdbc` 11.3.2.0053 (RELEASE) | BSD-3-Clause | [repo1.maven.org](https://repo1.maven.org/maven2/org/cubrid/cubrid-jdbc/11.3.2.0053/cubrid-jdbc-11.3.2.0053.pom) | 0.3 | A |  |
| MonetDB | `monetdb-jdbc` 12.2 (RELEASE) | MPL-2.0 | [repo.clojars.org](https://repo.clojars.org/monetdb/monetdb-jdbc/12.2/monetdb-jdbc-12.2.pom) | 0.2 | A | Clojars 저장소(`monetdb` 그룹 전용 저장소로 등록) |
| NuoDB | `nuodb-jdbc` 24.3.1 (RELEASE) | BSD-3-Clause | [repo1.maven.org](https://repo1.maven.org/maven2/com/nuodb/jdbc/nuodb-jdbc/24.3.1/nuodb-jdbc-24.3.1.pom) | 0.5 | A |  |
| SQream DB | `sqream-jdbc` 6.6 (RELEASE) | MIT | [repo1.maven.org](https://repo1.maven.org/maven2/com/sqream/sqream-jdbc/6.6/sqream-jdbc-6.6.pom) | 2.0 | A |  |
| Teiid | `teiid:jdbc` 16.0.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/teiid/teiid/16.0.0/teiid-16.0.0.pom) | 0.6 | A |  |
| CSV (csvjdbc) | `csvjdbc` 1.0.46 (RELEASE) | LGPL-2.1 | [repo1.maven.org](https://repo1.maven.org/maven2/net/sourceforge/csvjdbc/csvjdbc/1.0.46/csvjdbc-1.0.46.pom) | 0.3 | A | DBF 드라이버는 추가로 `dans-dbf-lib`(아래 B) 사용 |
| MS Access (UCanAccess) | `ucanaccess` 5.2.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/io/github/spannm/ucanaccess/5.2.0/ucanaccess-5.2.0.pom) | 3.5 | A |  |
| JDBCX | `jdbcx-driver` 0.8.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/io/github/jdbcx/jdbcx-driver/0.8.0/jdbcx-driver-0.8.0.pom) | 2.1 | A |  |
| OpenSearch | `opensearch-sql-jdbc` 1.4.0.1 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/org/opensearch/driver/opensearch-sql-jdbc/1.4.0.1/opensearch-sql-jdbc-1.4.0.1.pom) | 6.0 | A |  |
| Open Distro Elasticsearch | `opendistro-sql-jdbc` 1.1.0.0 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/amazon/opendistroforelasticsearch/client/opendistro-sql-jdbc/1.1.0.0/opendistro-sql-jdbc-1.1.0.0.pom) | 6.0 | A |  |
| Salesforce Data 360 | `jdbc:shaded` 1.2.0 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/salesforce/datacloud/jdbc/1.2.0/jdbc-1.2.0.pom) | 50.2 | A |  |
| Salesforce Data 360 (Legacy) | `Salesforce-CDP-jdbc` 1.19.8 | Apache-2.0 | [github.com](https://github.com/forcedotcom/Salesforce-CDP-jdbc/blob/develop/LICENSE) | 24.0 | A | GitHub 릴리스 jar |
| Gemfire XD | `gemfirexd-client` 2.0-BETA (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/io/snappydata/gemfirexd-client/2.0-BETA/gemfirexd-client-2.0-BETA.pom) | 10.2 | A | 2.0-BETA |
| Ocient | `ocient-jdbc4` 4.4.2 (RELEASE) | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/ocient/ocient-jdbc4/4.4.2/ocient-jdbc4-4.4.2.pom) | 209.7 | A | 의존 jar 182개(가장 큼) |
| Timeplus | `timeplus-native-jdbc` 2.0.9 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/timeplus/timeplus-native-jdbc/2.0.9/timeplus-native-jdbc-2.0.9.pom) | 0.5 | A |  |
| Timeplus Proton | `proton-jdbc` 0.7.1 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/timeplus/proton-jdbc/0.7.1/proton-jdbc-0.7.1.pom) | 3.3 | A |  |
| Databend | `databend-jdbc` 0.4.8 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/databend/databend-jdbc/0.4.8/databend-jdbc-0.4.8.pom) | 16.9 | A |  |
| TiDBLake | `lake-jdbc` 0.4.6 | Apache-2.0 | [repo1.maven.org](https://repo1.maven.org/maven2/com/tidbcloud/lake-jdbc/0.4.6/lake-jdbc-0.4.6.pom) | 16.9 | A |  |
| Virtuoso | `virtjdbc4_3` 3.123 (RELEASE) | GPLv2 + Client Protocol Driver exemption | [raw.githubusercontent.com](https://raw.githubusercontent.com/openlink/virtuoso-opensource/develop/7/LICENSE) | 0.3 | A (조건) | 수정하지 않은 클라이언트 드라이버는 어떤 앱에서도 GPL 의무 없이 사용 가능 |
| Dameng | `DmJdbcDriver18` 8.1.3.62 | Apache-2.0 (POM 선언) | [repo1.maven.org](https://repo1.maven.org/maven2/com/dameng/DmJdbcDriver18/8.1.3.62/DmJdbcDriver18-8.1.3.62.pom) | 1.3 | A | 소스 비공개 벤더 jar. POM 선언만 근거 |
| Kingbase | `kingbase8` 9.0.1 | Apache-2.0 (POM 선언) | [repo1.maven.org](https://repo1.maven.org/maven2/cn/com/kingbase/kingbase8/9.0.1/kingbase8-9.0.1.pom) | 1.2 | A | 소스 비공개 벤더 jar. POM 선언만 근거 |
| YashanDB | `yashandb-jdbc` 1.10.7 (RELEASE) | Apache-2.0 (POM 선언) | [repo1.maven.org](https://repo1.maven.org/maven2/com/yashandb/yashandb-jdbc/1.10.7/yashandb-jdbc-1.10.7.pom) | 0.5 | A | 소스 비공개 벤더 jar. POM 선언만 근거 |

### A*: 재배포를 허용하는 벤더 약관

Oracle은 위 주요 DB 표에 있다.

| 드라이버 | 버전 | 라이선스 | 출처 | 크기(MB) | 분류 | 비고 |
|---|---|---|---|---|---|---|
| Mimer SQL | `mimjdbc` 11.0.4 | Mimer 라이선스(MIT 형식, 수정 권한 없음) | [download.mimer.com](https://download.mimer.com/pub/dist/jdbc/license.txt) | 0.3 | A* | 사용·복사·배포 허용, 저작권 고지 포함 |
| Exasol | `exasol-jdbc` 25.2.3 | EXAClient License | [repo1.maven.org](https://repo1.maven.org/maven2/com/exasol/exasol-jdbc/25.2.3/exasol-jdbc-25.2.3-license.txt) | 10.7 | A* | 2.2항: 다른 소프트웨어의 일부로 무상 재배포 허용. 최신 26.2.10 |
| Elasticsearch | `x-pack-sql-jdbc` 8.17.1 | Elastic License 2.0 | [raw.githubusercontent.com](https://raw.githubusercontent.com/elastic/elasticsearch/v8.17.1/licenses/ELASTIC-LICENSE-2.0.txt) | 0.8 | A* | 재배포 허용(라이선스 고지 유지, 관리형 서비스 제공·라이선스 키 우회 금지). 서버 쪽 JDBC 기능은 Elastic 유료 구독 필요 |

### B: 내장하지 않음(이전 결정에서는 내부판만 내장)

| 드라이버 | 버전 | 라이선스 | 출처 | 크기(MB) | 분류 | 비고 |
|---|---|---|---|---|---|---|
| SQL Server 통합 인증 DLL | `mssql-jdbc_auth` 13.4.0.x64 | Microsoft Software License Terms | [download.microsoft.com](https://download.microsoft.com/download/1/7/c/17c9447c-dfa6-49e0-abdf-90095a85d986/jdbc/REDIST.TXT) | 0.1 | B | REDIST 목록상 전체 재배포 가능. 단 최종 사용자 약관 동의·면책 의무 |
| Db2 for LUW / z/OS / LUW Old | `jcc` 11.5.9.0 | IBM International Program License Agreement (IPLA) | [www.ibm.com](https://www.ibm.com/support/customer/csol/terms/?ref=L-AJVM-KLN94L-01-11-2023-zz-en) | 6.5 | B | 재배포 가능 항목(Redistributables)이지만 자사 응용의 일부로만, 최종 사용자 약관이 IBM을 같은 수준 이상 보호해야 함. z/OS 접속은 별도 라이선스 jar 필요. 최신 12.1.5.0 |
| Informix | `jdbc` 4.50.10.1 | IBM Informix JDBC Software License Agreement | [repo1.maven.org](https://repo1.maven.org/maven2/com/ibm/informix/jdbc/4.50.10.1/jdbc-4.50.10.1.pom) | 1.5 | B | IBM 독점 약관. 원문 페이지가 스크립트로 렌더링돼 조항을 직접 확인하지 못함 |
| Vertica | `vertica-jdbc` 25.3.0-0 (RELEASE) | Vertica Client License Agreement | [www.vertica.com](https://www.vertica.com/wp-content/uploads/2024/02/Vertica-Client-License-Agreement_Feb2023.pdf) | 1.9 | B | 프로그램에 포함한 재배포 허용. 단 Micro Focus 면책 의무, 수정 금지 |
| Altibase | `altibase-jdbc` 7.1.0.9.0 | Commercial License (POM) | [repo1.maven.org](https://repo1.maven.org/maven2/com/altibase/altibase-jdbc/7.1.0.9.0/altibase-jdbc-7.1.0.9.0.pom) | 0.7 | B | 약관 원문 없음(POM은 회사 홈페이지만 가리킴) |
| Ingres (Actian) | `iijdbc` 12.0-4.4.4 | Actian Commercial | [communities.actian.com](https://communities.actian.com/s/supportservices/policy/policy-license-terms) | 0.6 | B | 약관 페이지가 스크립트로 렌더링돼 직접 확인하지 못함 |
| InterSystems IRIS | `intersystems-jdbc` 3.10.5 | InterSystems 약관(IERTU) | [www.intersystems.com](https://www.intersystems.com/IERTU/) | 0.6 | B | 약관 페이지 접근 거부(403). plugin.xml은 MIT로 표기 |
| InterSystems Caché | `CacheDB.jar` (버전 미표기) | InterSystems 독점 (확인 못함) | [dbeaver.io](https://dbeaver.io/files/jdbc/drivers/cache/CacheDB.jar) | 2.2 | B | DBeaver 서버(`repo:/`)에서 받는 jar. plugin.xml은 Apache-2.0으로 표기 |
| Machbase | `machjdbc` 8.7.0 (RELEASE) | Machbase EULA | [repo1.maven.org](https://repo1.maven.org/maven2/com/machbase/machjdbc/8.7.0/machjdbc-8.7.0.pom) | 0.3 | B | POM이 가리키는 약관 URL이 404 |
| Amazon Athena | 3.7.0 (zip) | AWS Customer Agreement (AWS Content) | [downloads.athena.us-east-1.amazonaws.com](https://downloads.athena.us-east-1.amazonaws.com/drivers/JDBC/3.7.0/LICENSE.txt) | 34.2 | B | 라이선스 파일은 "AWS Content, Customer Agreement 적용" 한 줄. 고객 약관은 AWS 서비스와 함께 쓰는 사용·복사만 허용하고 재배포 권한이 없음 |
| Amazon Athena (Legacy, Simba) | 2.0.34.1000 | Simba 드라이버 (확인 못함) | [s3.amazonaws.com](https://s3.amazonaws.com/athena-downloads/drivers/JDBC/SimbaAthenaJDBC-2.0.34.1000/docs/LICENSE.txt) | 14.5 | B | 연결된 LICENSE.txt에는 AWS SDK의 Apache-2.0 문구만 있음 |
| Denodo | 9 (버전 미고정) | Denodo 독점 (확인 못함) | [community.denodo.com](https://community.denodo.com/drivers/jdbc/9/denodo-vdp-jdbcdriver) | 44.3 | B | 버전 없는 다운로드 URL |
| Dremio | LATEST | 확인 못함 (plugin.xml은 Apache-2.0 표기) | [download.dremio.com](https://download.dremio.com/jdbc-driver/dremio-jdbc-driver-LATEST.jar) | 48.6 | B | `LATEST` URL이라 버전 고정 불가. Arrow Flight SQL 드라이버로 대체 가능 |
| GaussDB | `gsjdbc4` 1.1 | 확인 못함 | [repo.huaweicloud.com](https://repo.huaweicloud.com/repository/maven/huaweicloudsdk/gsjdbc4/gsjdbc4/1.1/gsjdbc4-1.1.jar) | 1.1 | B | Huawei 저장소 POM에 라이선스 정보 없음 |
| SnappyData | 1.3.0 | 확인 못함 | [github.com](https://github.com/TIBCOSoftware/snappydata/releases/download/v1.3.0/snappydata-jdbc_2.11-1.3.0.jar) | 7.5 | B | GitHub 저장소 라이선스 판별 불가(NOASSERTION) |
| DBF (`dans-dbf-lib`) | 1.0.0-beta-10 | 확인 못함 | [dbeaver.io](https://dbeaver.io/files/jdbc/drivers/dbf/dans-dbf-lib-1.0.0-beta-10.jar) | 0.1 | B | DBeaver 서버(`repo:/`)에서 받는 jar |
| Apache Spark (Hive) | `hive-jdbc` 1.2.1.spark2 (RELEASE)<br>`hadoop-core` 1.2.1 (RELEASE) | Apache-2.0, 단 의존성에 JSON License(`org.json:json:20090211`) | [repo1.maven.org](https://repo1.maven.org/maven2/org/spark-project/hive/hive-jdbc/1.2.1.spark2/hive-jdbc-1.2.1.spark2.pom) | 64.7 | B | JSON License("Good, not Evil" 조항)는 OSI·FSF 기준 자유 라이선스가 아님. 의존 jar 128개 |

### C: 내장하지 않음

| 드라이버 | 버전 | 라이선스 | 출처 | 크기(MB) | 분류 | 비고 |
|---|---|---|---|---|---|---|
| Teradata | `terajdbc` 20.00.00.62 (RELEASE) | Teradata Generic Download License | [repo1.maven.org](https://repo1.maven.org/maven2/com/teradata/jdbc/terajdbc/20.00.00.62/terajdbc-20.00.00.62.pom) | 1.3 | C | Teradata 서면 동의 없이 복사·임베드·재배포 금지(조항은 plugin.xml이 가리키는 `repo:/drivers/teradata/LICENSE.txt` 16.20 판 기준) |
| SAP HANA | `ngdbc` 2.30.11 (RELEASE) | SAP Developer License Agreement 3.2 | [tools.hana.ondemand.com](https://tools.hana.ondemand.com/developer-license-3_2.txt) | 1.6 | C | 클릭 동의 방식의 개발자 라이선스. 사용자가 소유·관리하는 기기에서 쓰는 권한만 부여. plugin.xml은 Apache-2.0으로 잘못 표기 |
| SAP ASE (Sybase jConnect) | `jconn4.jar` (버전 미표기) | Sybase jConnect License Agreement | [dbeaver.io](https://dbeaver.io/files/jdbc/drivers/sybase/jconnect/LICENSE.txt) | 1.9 | C | 클릭 동의 방식. 조직 내부 개발 용도로만 사용, Sybase 서면 동의 없이 양도·재라이선스 금지 |
| GBase 8s | `jdbc` 3.5.1 | GBase License Agreement | [www.gbasedbt.com](https://www.gbasedbt.com/licenses/LA.txt) | 3.0 | C | "internal use only" 사용권. plugin.xml은 Apache-2.0으로 표기 |
| BigQuery (Legacy, Simba) | 1.6.5.1002 (zip) | insightsoftware(Simba) 라이선스 | [docs.cloud.google.com](https://docs.cloud.google.com/bigquery/partners/simba-drivers) | 40.8 | C | Google 문서: 재라이선스·OEM 재배포는 insightsoftware와 별도 계약 필요. plugin.xml은 LGPL-3.0으로 잘못 표기 |

### 크기를 구하지 못한 항목

- **Cloudflare D1:** `com.dbeaver.jdbc:com.dbeaver.jdbc.driver.d1`이 Maven Central과 등록된 다른 저장소 어디에도 없다(404). 지금 정의로는 받을 수 없다.
- **Jennifer:** GitHub 태그 zip(`jennifer5-jdbc-driver` v5.5.3.2)이라 크기 헤더가 없고, 저장소 라이선스도 확인하지 못했다(B로 취급).
- **WMI:** 드라이버 파일이 플러그인 안의 DLL(`platform:/plugin/org.jkiss.wmi/...`)이라 다운로드 대상이 아니다.

## plugin.xml의 라이선스 표기와 실제가 다른 항목

`type="license"` 파일은 드라이버 다운로드 대화상자에 보여 주는 용도다. 아래 항목은 실제 라이선스와 다르다.
코드 변경 범위 밖이라 고치지 않았다. 내장 작업 때 함께 바로잡는다.

| 드라이버 | plugin.xml 표기 | 실제 |
|---|---|---|
| MySQL 8, NDB, Doris, Manticore, GreptimeDB, TiDB | `gnu3.txt`(GPLv3) | GPLv2 + Universal FOSS Exception |
| MySQL 5 | `lgpl-2.0.txt` | GPLv2 + MySQL FOSS License Exception |
| StarRocks | GPLv2 원문 | GPLv2 + Universal FOSS Exception |
| SAP HANA | `asl-2.0.txt` | SAP Developer License Agreement |
| GBase 8s | `asl-2.0.txt` | GBase License Agreement(내부 사용 한정) |
| Exasol | `asl-2.0.txt` | EXAClient License |
| InterSystems Caché | `asl-2.0.txt` | InterSystems 독점(확인 못함) |
| InterSystems IRIS | `mit.txt` | InterSystems 약관(IERTU) |
| BigQuery (Legacy, Simba) | `lgpl-3.0.txt` | insightsoftware(Simba) 라이선스 |
| CSV | `asl-2.0.txt` | LGPL-2.1 |
| Virtuoso | `lgpl-2.0.txt` | GPLv2 + Client Protocol Driver exemption |
| Informix | node-ibm_db 저장소 `LICENSE`(MIT) | IBM Informix JDBC Software License Agreement |
| NuoDB | nuodb-odbc 저장소 `LICENSE`(MIT) | BSD-3-Clause |
| SQL Server | sql-server-samples 저장소 `license.txt`(MIT) | 드라이버는 MIT(일치). 통합 인증 DLL은 Microsoft 독점이라 따로 고지 필요 |

## 크기

| 구성 | 파일 수 | 크기 |
|---|---|---|
| 처음 4종 | 31 | 약 23.7MB |
| 5종 실제 배포(MySQL 포함, 2026-11-06 빌드의 `drivers/`, 라이선스·안내 텍스트 포함) | 37 | 21,608,448 bytes |
| 6종 실제 배포(SQLite 포함, 2026-11-07 빌드의 `drivers/`, 라이선스·안내 텍스트 포함) | 40 | 33,754,109 bytes |
| 전체(Maven 고유 아티팩트 + URL·`repo:/` 파일) | 944 + 15 | 약 1,725MB (Maven 1,487MB + URL 238MB) |

큰 드라이버는 다음과 같다(드라이버별 의존성 포함 크기라 서로 겹치는 jar가 있다).

| 드라이버 | 크기(MB) |
|---|---|
| Ocient | 209.7 |
| Apache Phoenix | 108.0 |
| Snowflake | 106.9 |
| DuckDB (Windows ARM64 포함) | 95.2 |
| OrientDB | 70.8 |
| Apache Spark (Hive) | 64.7 |
| Google BigQuery | 63.0 |
| Google Cloud SQL - PostgreSQL | 61.5 |
| Apache Drill | 59.4 |
| Salesforce Data 360 | 50.2 |

- 같은 아티팩트의 다른 버전(예: H2 3종, Connector/J 3종)은 따로 센다. 같은 버전은 한 번만 센다.
- 전체 내장(이전 내부판 구상)은 2026-11-07 결정으로 하지 않는다. 위 전체 크기는 조사 기록으로 남긴다.

## 남은 확인 사항

- B 분류 중 "확인 못함" 항목(Informix, Ingres, InterSystems, Machbase, Athena Legacy, Denodo, Dremio, GaussDB, SnappyData, `dans-dbf-lib`, Jennifer)의 약관 원문.
- Dameng, Kingbase, YashanDB는 POM이 Apache-2.0을 선언하지만 소스가 공개돼 있지 않다. 내장하거나 폐쇄망 설치 묶음에 넣기 전 벤더 배포 페이지의 약관과 대조한다.
- 내장 드라이버 라이선스 고지 형식(Apache-2.0이 아닌 구성 요소 목록, GPL·LGPL 소스 제공 방법)에 대한 법률 검토.

## 변경 이력

- 2026-11-06 최초 작성. plugin.xml 전체 드라이버의 라이선스·버전·크기 조사, 공개판 4종 확인, MySQL Connector/J 재배포 조건 정리.
- 2026-11-06 MySQL Connector/J 9.7.0 공개판 내장 결정과 의무 이행 내용 기록.
- 2026-11-07 배포판을 하나로 정함(사용자 결정, 이전 결정 2026-11-06: 공개판·내부판). 주요 DB만 내장, 나머지는 필요할 때 다운로드,
  폐쇄망은 별도 오프라인 설치 경로(계획). "공개판"을 단일 제품 기준으로 고치고 분류 표의 내부판 열은 참고로 남김. B·C 분류는 계속 내장하지 않음.
  SQLite(Xerial `sqlite-jdbc` 3.54.1.0) 내장 기록.
