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
- **로컬 Maven 저장소:** [MavenRegistry](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/maven/MavenRegistry.java:102)는
  설치 폴더의 `maven-local`을 로컬 저장소로 등록한다. 단, 원격 저장소를 모두 시도한 뒤 **마지막에** 찾는다
  ([MavenRegistry.java:266](../../plugins/org.jkiss.dbeaver.registry/src/org/jkiss/dbeaver/registry/maven/MavenRegistry.java:266)).
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
  이 저장소에는 묶음 선언이 없다.

## 설계

### 배포판 구성

| 구분 | 공개판 | 내부판 |
|---|---|---|
| 내장 드라이버 | SQL Server, Oracle, PostgreSQL, MariaDB | 전체 |
| MySQL | MariaDB 드라이버로 연결 (GPL인 Connector/J 미포함) | Connector/J 포함 (라이선스 확인 후) |
| 그 밖의 드라이버 | 인터넷이 되면 Maven에서 온라인 다운로드. 폐쇄망에서는 jar 파일을 직접 지정 | 모두 내장, 원격 다운로드 끔 |

### 내장 방법

1. **드라이버 묶음이 있는 드라이버:** 배포판별 묶음 선언 파일에 해당 묶음 ID를 선언하고, 빌드할 때 `drivers/<이름>/` 폴더에 jar를 넣는다.
2. **묶음이 없는 드라이버(내부판):** 빌드할 때 Maven 아티팩트와 의존 라이브러리를 받아 `<설치폴더>/maven-local`에 Maven 구조로 넣는다.
   URL로 받는 zip은 `<설치폴더>/drivers/`에 넣는다.
3. **판 구분:** 공개판과 내부판의 차이는 묶음 선언 목록과 포함할 폴더 목록뿐이다. 빌드 설정(드라이버 목록 파일) 하나로 두 판을 만든다.

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

## 라이선스

- 드라이버 정의에 라이선스 동의 항목(`type="license"`)이 118개 있다. 내장 전에 드라이버별 라이선스 원문을 확인해 목록으로 만든다.
- 현재 파악한 내용(원문 확인 전, 법률 자문 아님):

| 드라이버 | 라이선스 | 공개판 내장 |
|---|---|---|
| SQL Server (`mssql-jdbc`) | MIT | 가능 |
| PostgreSQL | BSD | 가능 |
| MariaDB Connector/J | LGPL 2.1 | 가능 (jar 수정 없이, 라이선스 동봉) |
| Oracle (`ojdbc11`) | Oracle Free Use Terms and Conditions | 재배포 허용으로 파악, 원문 확인 필요 |
| MySQL Connector/J | GPLv2 (Universal FOSS Exception 여부 확인 필요) | 보류 |
| IBM DB2·Informix, Teradata, SAP HANA 등 | 각 사 라이선스 | 재배포 제한 가능성, 내부판도 사용 약관 확인 필요 |

- Chromite는 오픈소스(Apache 2.0)로 공개하기로 했다. MySQL Connector/J에 Universal FOSS Exception이 적용되면
  공개판에도 넣을 수 있으므로 원문 확인 후 다시 결정한다.

## 미완료 항목

- 드라이버별 라이선스 목록 작성
- 전체 내장 시 크기 실측 (주요 4종은 수십 MB, Snowflake·Databricks·BigQuery·Hive 등을 포함하면 수백 MB~1GB 이상으로 추정)
- MariaDB 드라이버로 MySQL 8 연결 시 인증 방식(`caching_sha2_password`) 등 호환성 실측

## 변경 이력

- 2026-11-06 최초 작성. 현재 드라이버 로딩 구조 분석, 공개판·내부판 구성, 오프라인 우선 조회 설계를 정리.
