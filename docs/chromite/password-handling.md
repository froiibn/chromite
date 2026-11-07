# 암호 처리 (문장의 따옴표, 쿼리 관리자 기록, 속성 표의 가리기)

`chromite/password-handling-fixes`(보안, 2026-11-08). 새 사용자의 암호 작업([swing-create-objects.md](swing-create-objects.md)
"새 사용자의 암호", #168)에서 범위 밖으로 남긴 것을 고치고, `plugins/`에서 암호를 SQL에 넣는 곳을 모두 다시 살폈다.

## 목적

- 암호는 사용자가 친 그대로 서버에 가야 한다. 따옴표 처리 때문에 다른 암호가 되거나 문장이 깨지면 안 된다.
- 암호가 든 문장은 쿼리 관리자(Query Manager) 기록과 SQL 로그 파일에 남으면 안 된다.
- 화면의 속성 표는 암호 속성의 값(서버가 주는 암호 해시 포함)을 보여 주면 안 된다.

## 원칙

- **암호는 식별자가 아니다.** `DBUtils.getQuotedIdentifier`(= `AbstractSQLDialect.getQuotedIdentifier`, `:457`)는
  1. 이미 따옴표로 싸인 글(`isQuotedIdentifier`, `:441`)을 그대로 둔다. 암호로 `"abc"`를 치면 서버에는 암호 `abc`가 간다.
  2. 이름 규칙에 맞는 글은 따옴표 없이 둔다(대소문자 규칙과 예약어 검사를 거친 결과에 따라 달라짐).
  그래서 암호에는 쓰지 않고, 드라이버마다 그 문법의 암호 함수로만 넣는다.
- **드라이버별 문법**

| 드라이버 | 문장 | 따옴표 | 함수 |
|---|---|---|---|
| Oracle, YashanDB | `IDENTIFIED BY "…" [REPLACE "…"]` | 늘 큰따옴표. 안의 `"`는 쓸 방법이 없다(ORA-25716). 보내기 전에 거절 | `OracleUtils.quotePassword` |
| Exasol | `IDENTIFIED BY "…" [REPLACE "…"]` | 늘 큰따옴표, 안의 `"`는 두 번 | `ExasolUtils.quotePassword` |
| SAP HANA | `ALTER USER … PASSWORD "…"` | 늘 큰따옴표, 안의 `"`는 두 번(구분 식별자 규칙) | `HANAUserPasswordManager.quotePassword` |
| PostgreSQL, SQL Server, Vertica, MySQL/MariaDB, CUBRID, Mimer, Exasol CONNECTION | 글자 상수 `'…'` | 방언의 `getQuotedString`/`escapeString`(작은따옴표 두 번, MySQL은 역슬래시도) | 바꾸지 않음(이미 맞음) |

- **기록 끄기:** 객체 관리자의 명령은 `setDisableSessionLogging(true)`, 직접 실행하는 암호 바꾸기는 `session.enableLogging(false)`.
  PostgreSQL 역할·SQL Server 로그인 관리자(DBeaver에서 온 방식), Exasol 사용자·Mimer 프로그램(#168)과 같다.
- **가리기:** 속성 값을 모델에서 읽는 자리에서 가린다. 화면 그리기(renderer)에서만 가리면 표 복사(Ctrl+C), 도구 설명, 찾기,
  정렬, 열 너비 계산이 실제 값을 읽는다.

## 고친 것

### 암호 바꾸기의 따옴표 (Exasol, Oracle, YashanDB, HANA)

연결한 사용자의 Change Password…(`ConnectionCommands.changePassword` → `DBAUserPasswordManager`)와 만료된 암호 바꾸기가 새 암호를
`getQuotedIdentifier`로 감쌌다.

| 위치 | 전 | 후 |
|---|---|---|
| `ext.oracle` `OracleChangeUserPasswordManager` | 새·옛 암호를 `getQuotedIdentifier` | `OracleUtils.quotePassword`. `"`가 들면 연결 전에 "The password cannot contain a double quote" |
| `ext.yashandb` `YashanDBChangeUserPasswordManager` | `"` 검사 뒤 `getQuotedIdentifier` | 자체 검사를 `OracleUtils.quotePassword`로 바꿈(옛 암호 확인 전에 거절하는 순서는 같음) |
| `ext.exasol` `ExasolChangeUserPasswordManager` | 새·옛 암호를 `getQuotedIdentifier` | `ExasolUtils.quotePassword`(#168의 `ExasolUserManager.quotePassword`를 옮겨 함께 씀) |
| `ext.hana` `HANAUserPasswordManager`, `HANADataSource`(만료된 암호) | `getQuotedIdentifier` | `HANAUserPasswordManager.quotePassword` |
| `ext.oracle` `OracleSchemaManager`(새 스키마 = 사용자) | `"` + 암호 + `"`(검사 없음), 기록 켜짐 | `OracleUtils.quotePassword`, 기록 끔. YashanDB 스키마 관리자도 물려받음 |

- 같은 원인의 다른 경우: 암호가 숫자로 시작하거나 예약어이거나 소문자이면 지금까지도 따옴표가 붙었지만, 대문자·숫자만인 암호는
  따옴표 없이 갔다. 이제 모두 같은 모양이다. 옛 Oracle 주석 "Do not use numbers in the password beginning"은 따옴표 없는 암호에만
  맞는 말이라 지웠다.

### 쿼리 관리자 기록

| 위치 | 암호가 든 문장 | 고침 |
|---|---|---|
| `ext.mimer` `MimerUserManager` | `CREATE IDENT … AS USER USING '…'`, `ALTER IDENT … SET PASSWORD '…'` | 암호가 있는 CREATE와 암호 ALTER의 기록 끔 |
| `ext.mimer` `MimerProgramManager` | `ALTER IDENT … SET PASSWORD '…'`(CREATE는 #168에서 끔) | 기록 끔 |
| `ext.exasol` `ExasolConnectionManager` | `CREATE/ALTER CONNECTION … USER '…' IDENTIFIED BY '…'` | 사용자와 암호가 있을 때 기록 끔 |
| `ext.oracle` `OracleSchemaManager` | `CREATE USER … IDENTIFIED BY "…"` | 기록 끔(위) |
| `ext.cubrid` `CubridUserManager` | `CREATE USER … PASSWORD '…'` | 암호가 있을 때 기록 끔(`buildBody`가 암호를 썼는지 알려 줌) |
| `ext.cubrid` `CubridServerManager` | `CREATE SERVER (… PASSWORD='…')`, `ALTER SERVER … CHANGE PASSWORD='…'` | 기록 끔 |

### Exasol

- **LDAP·Kerberos:** `CREATE/ALTER USER … AT LDAP AS '…'`와 `… BY KERBEROS PRINCIPAL '…'`가 값을 그대로 넣었다(`'`가 든 DN이면
  문장이 깨짐). `ExasolUtils.quoteString`으로 작은따옴표를 두 번 쓴다. Kerberos 주체는 같은 메서드의 같은 결함이라 함께 고쳤다.
- **CONNECTION DDL 보기:** `ExasolUtils.getConnectionDdl`의 `USER '…'`도 작은따옴표를 두 번 쓴다(암호는 원래 `'<password>'` 자리 표시).
- **New Connection…의 NullPointerException**
  - 원인: 이름만 대화상자 길이 `ExasolConnectionManager.createDatabaseObject`의 새 객체(이름 말고 모두 null)로 문장을 만들었고,
    `addObjectCreateActions`가 `getUserName().isEmpty()`를 불렀다. Exasol의 `CREATE CONNECTION`은 연결 문자열이 필수라 이름만으로는
    만들 수 없는 종류다.
  - 모델: `ExasolConnection.getConnectionString`을 `@Property(required = true)`로 선언했다. 관리자는 null을 견디고
    (`CommonUtils.isEmpty`), 연결 문자열이 없으면 "A connection string is required for the connection …"로 거절한다.
  - Swing: `NewObjectDialogs.hasUnaskedRequiredValue`가 이름 대화상자가 묻지 않는 필수 값(이름과 필수 암호가 아닌 것)을 모델이
    선언했는지 본다. 그런 종류는 `NewObjectCommands`가 이름만 길에서 뺀다(메뉴에 New Connection…이 나오지 않음).
    드라이버 이름을 ui.swing에 적지 않고 모델의 선언을 보므로 ui.swing이 ext.exasol에 의존하지 않는다.
  - 다른 종류에 주는 영향: 지금 `required = true`인 `@Property`는 Exasol 사용자·Mimer 프로그램의 암호(대화상자가 물음),
    `OracleSchedulerJob.getJobType`(객체 관리자 없음), AI 설정 속성(DB 객체 아님)뿐이라 다른 메뉴는 바뀌지 않는다.

### Swing 속성 표의 가리기

`@Property(password = true)` 또는 선언한 속성의 `password` 기능(`DBConstants.PROP_FEATURE_PASSWORD`)이면 값이 있을 때
`********`, 없으면 빈칸이다(SWT `ObjectListControl`과 같음). 함수는 `ObjectListTable.maskPassword` 하나다.

| 표 | 가리는 자리 |
|---|---|
| Properties 창(`PropertiesPanel`) | 속성 값을 읽을 때 |
| 폴더 Details 표(`ObjectDetailsPanel`, 예: 사용자·역할 목록의 Password 열) | 값 읽기(첫 읽기와 게으른 값 둘 다 같은 함수) |
| 세션·잠금 목록(`ObjectListTable`) | 표 모델 |
| 세션 상세(`SessionManagerPanel`) | 값 읽기 |
| 연결·드라이버·전송 속성 표(`ConnectionPropertiesTable`) | 표 모델. 전에는 그리기에서만 이름(password·token)으로 가렸다. 이제 암호 기능도 보고, 편집은 가린 입력 칸(`JPasswordField`)이 실제 값을 받아서 한다 |

- 예: Exasol 사용자의 "Password Hash"(서버의 암호 해시), Exasol CONNECTION·CUBRID 서버·Mimer 사용자/프로그램의 암호.
  PostgreSQL 역할의 Password는 새 역할에만 보이는 속성이다(아래 "확인").
- 가린 글자는 `ObjectListTable.MASKED_VALUE`(`********`) 하나로 맞췄다. 연결 속성 표는 전에 `••••••`였다.

## 살폈지만 고치지 않은 것

| 위치 | 판단 |
|---|---|
| `ext.postgresql` `PostgresUserPasswordManager`, `ext.mssql` `SQLServerLoginPasswordManager`, `ext.vertica` `VerticaChangeUserPasswordManager` | 글자 상수(`SQLUtils.quoteString`)와 기록 끔. 맞음 |
| `ext.mysql` `MySQLDataSource.changeUserPassword`(만료된 암호) | `SQLUtils.quoteString`, 앱 밖의 JDBC 연결이라 기록 없음. 맞음 |
| `ext.db2` `DB2DataSource.changeUserPassword` | 드라이버 API(`changeDB2Password`), SQL 없음 |
| `ext.postgresql` `PostgreRoleManager`, `ext.mssql` `SQLServerLoginManager`, `model.admin` 보안 편집기(`*SecurityDialect`) | 글자 상수(Oracle은 `"…"`와 `"` 거절), 기록 끔, 미리 보기는 가림. 맞음 |
| `ext.altibase` `AltibaseSQLDialect.getCreateSchemaQuery`, `ext.oracle` `OracleSQLDialect.getCreateSchemaQuery` | #168 표와 같음(서버 제품용, 부르는 곳 없음) |

## 남긴 것

- HANA 암호 바꾸기의 사용자 이름은 따옴표 없이 문장에 들어간다(`"ALTER USER " + userName`). 연결 설정의 사용자 이름이고, HANA는
  따옴표 없는 이름을 대문자로 바꾸므로 Oracle처럼 대소문자 변환 뒤 따옴표를 붙여야 맞다. HANA 서버로 확인하지 못해 바꾸지 않았다.
- HANA 암호의 `"` 두 번 쓰기는 구분 식별자 규칙을 따른 것이다. HANA 서버로 실행해 보지 못했다(Exasol·YashanDB·Mimer·CUBRID도 같음).
- 속성 편집(G4, `chromite/object-properties-edit`)이 Properties 창에서 암호 속성을 고칠 때도 가린 입력 칸을 써야 한다.

## 확인 (2026-11-08)

빌드한 제품 한 벌을 `CHROMITE_DATA`와 작업 공간을 scratch에 두고 실행했다. 입력은 앱 안의 드라이버(attach 에이전트)로만 보냈다.
시험 암호는 실행할 때마다 임의로 만들었고 기록·문서에 남기지 않았다(아래 표의 `<secret>`). 시험 뒤 앱 사본, 데이터 폴더, Docker의
시험 사용자·역할을 지웠다.

**Oracle(Docker Oracle Free 23ai, 시험 사용자 `CHR_G6_PW`)**: 실제 `OracleChangeUserPasswordManager`를 연결한 데이터 소스의
`DBAUserPasswordManager` 어댑터로 불렀다.

| 경우 | 결과 |
|---|---|
| 새 암호에 `"`와 `'` | "The password cannot contain a double quote"로 거절, 문장을 보내지 않음. 옛 암호로 계속 로그인됨 |
| 큰따옴표로 감싼 암호(옛 코드는 따옴표를 떼고 보냄) | 같은 거절. 옛 암호로 계속 로그인됨 |
| `'`가 들고 숫자로 시작하는 소문자 암호 | 바뀜. 새 암호로 로그인됨, 옛 암호는 ORA-01017 |
| 같은 서버에 sqlplus로 `IDENTIFIED BY "x""y…"` | ORA-25716(식별자에 큰따옴표). Oracle 암호에는 `"`를 넣을 수 없음을 확인 |

옛 코드의 `getQuotedIdentifier`가 만든 모양(같은 연결의 방언): `"pq"` → `"pq"`(그대로), `PQ12` → `PQ12`(따옴표 없음),
`pq12` → `"pq12"`, `1pq` → `"1pq"`, `p'q` → `"p'q"`.

**따옴표 함수**(같은 앱 안에서 호출)

| 입력 | Oracle·YashanDB | Exasol | HANA |
|---|---|---|---|
| `p'q` | `"p'q"` | `"p'q"` | `"p'q"` |
| `"pq"` | 거절 | `"""pq"""` | `"""pq"""` |
| `PQ12` | `"PQ12"` | `"PQ12"` | `"PQ12"` |
| `p"q` | 거절 | `"p""q"` | `"p""q"` |

**만들어지는 문장**(Exasol·Mimer 서버가 없어 연결하지 않은 데이터 소스 위에서 실제 객체 관리자로 만든 스크립트, 새 객체는
`createNewObject`, 바꾸기는 편집 가능한 속성 원본 `PropertySourceEditable`)

| 경우 | 문장 | 쿼리 관리자 기록 |
|---|---|---|
| Exasol CONNECTION, 이름만 | 거절: A connection string is required for the connection CHR_G6_CONN | - |
| Exasol CONNECTION, 사용자·암호 | `CREATE CONNECTION CHR_G6_CONN TO 'ftp://host''1/dir' USER 'us''er' IDENTIFIED BY '<secret>'` | 끔 |
| Exasol LDAP 사용자 만들기·바꾸기 | `… AT LDAP AS 'cn=O''Brien,dc=example'`, `ALTER USER … IDENTIFIED AT LDAP AS 'cn=D''Arcy,dc=example'` | 켬(암호 없음) |
| Exasol Kerberos 사용자 만들기·바꾸기 | `… BY KERBEROS PRINCIPAL 'o''brien@EXAMPLE'`, `… 'd''arcy@EXAMPLE'` | 켬(암호 없음) |
| Mimer 사용자, 암호 없음 / 있음 | `CREATE IDENT "CHR_G6_USER" AS USER` / `… AS USER USING '<secret>'` | 켬 / 끔 |
| Mimer 사용자·프로그램 암호 바꾸기 | `ALTER IDENT "…" SET PASSWORD '<secret>'` | 끔 |
| Mimer 프로그램 만들기 | `CREATE IDENT "CHR_G6_PROGRAM" AS PROGRAM USING '<secret>'` | 끔 |

`hasUnaskedRequiredValue`: ExasolConnection = true(New Connection… 메뉴 없음), ExasolUser·ExasolSchema = false.

**가리기**

| 표 | 결과 |
|---|---|
| `ObjectListTable`, Exasol 사용자 두 행(암호 해시 있음/없음) | "Password Hash" 열의 모델 값과 화면 글자가 `********` / 빈칸 |
| `ConnectionPropertiesTable`, 이름에 password가 없는 `password` 기능 속성 | 모델 값과 화면 글자 `********`, 편집기는 `JPasswordField`이고 실제 값을 받음. 보통 속성은 그대로 |
| PostgreSQL 역할(Docker PostgreSQL 17, 시험 슈퍼유저) | 저장된 역할의 Password 속성은 모델이 새 역할에만 보이므로(`visibleIf = PersistenceUserValidator`) Properties 창·Details 표에 나오지 않음. 값도 `pg_roles`의 가린 값 |

- 확인하지 못한 것: Exasol·HANA·YashanDB·Mimer·CUBRID 서버에서 실행해 보지 못했다(문장과 함수 결과만 확인). Properties 창·Details 표는
  실제 서버 객체로 가려진 값을 보지 못했다(위 PostgreSQL 사정). 두 창은 같은 `maskPassword`를 읽는 자리에서 부른다.
- 화면 문구를 더하거나 바꾸지 않았다(예외 메시지는 영어). 그래서 한국어 실행은 하지 않았다.

## 변경 이력

- 2026-11-08 최초 작성(`chromite/password-handling-fixes`): 암호 바꾸기의 따옴표(Exasol·Oracle·YashanDB·HANA), Oracle 새 스키마의 암호,
  Mimer·Exasol CONNECTION·CUBRID의 기록 끄기, Exasol LDAP·Kerberos 작은따옴표, Exasol New Connection…의 NullPointerException,
  Swing 속성 표의 암호 가리기.
