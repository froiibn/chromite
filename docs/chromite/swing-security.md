# Swing 보안 편집기 (로그인·사용자·역할·사용 권한)

## 목적

Object Explorer에서 로그인·사용자·역할을 SSMS의 보안 화면처럼 다룬다.

- 로그인·사용자·역할의 **속성** 창: 일반(이름, 암호 바꾸기, 기본 데이터베이스, 로그인 사용/계정 잠금, 만료 시각, 역할 옵션),
  역할 멤버 자격(확인란 목록), SQL Server **사용자 매핑**(데이터베이스별 사용자·기본 스키마·데이터베이스 역할),
  **사용 권한**(보안 개체 목록 + 개체별 허용/권한 부여/거부, "개체 추가…").
- Logins·Roles·Users 폴더의 **새 로그인… / 새 역할… / 새 사용자…**(같은 창, 빈 값으로 시작).
- 로그인·사용자·역할의 **삭제**(Delete 키 또는 메뉴, 확인 후 스크립트).
- 테이블·뷰·시퀀스·함수·프로시저의 **사용 권한…**: 사용자·역할별 권한 허용/취소/거부.

모든 변경은 OK를 누르면 테이블 디자이너처럼 스크립트를 먼저 보여 주고(Execute / Open in Query Window / Copy / Cancel)
Execute를 고르면 실행한다. "스크립트" 단추는 실행 없이 스크립트만 보여 준다.

지원: SQL Server, PostgreSQL, MySQL/MariaDB. Oracle은 이번에 넣지 않았다("남긴 것").

## 화면

| 창 | 탭·구성 | 보이는 조건 |
|---|---|---|
| 로그인 속성 / 사용자 속성 / 역할 속성, 로그인 - 신규 / 역할 - 신규 / 사용자 - 신규 | 일반 | 항상. 칸은 DB가 지원하는 것만 |
| | 서버 역할(SQL Server) / 멤버 자격(PostgreSQL) / 역할(MariaDB) | 고를 역할이 있을 때 |
| | 사용자 매핑 | SQL Server 로그인 |
| | 사용 권한 | SQL Server 로그인(서버 역할 제외), PostgreSQL 역할, MySQL/MariaDB 계정·역할 |
| 사용 권한 - `<개체>` | 위: 사용자 또는 역할 목록(+ 사용자 또는 역할 추가…, 제거), 아래: 고른 사용자의 권한 | 테이블·뷰·시퀀스·함수·프로시저 |

- **일반 탭의 칸:** 이름(SQL Server 로그인 이름 / 사용자 이름 / 역할 이름), MySQL 호스트(`%` = 모든 호스트), 암호·암호 확인
  (기존 주체는 비워 두면 그대로), SQL Server 기본 데이터베이스와 "암호 정책 강제 적용"·"암호 만료 강제 적용",
  "로그인 사용"(SQL Server) / "로그인 가능"(PostgreSQL LOGIN) / "계정 잠김"(MySQL 5.7.6+·MariaDB 10.4+),
  PostgreSQL 만료 시각(VALID UNTIL)과 역할 옵션(슈퍼유저, 데이터베이스 만들기, 역할 만들기, 상속, 복제, 행 수준 보안 무시 — 서버가 지원하는 것만).
- **사용 권한 표:** 위 목록에서 보안 개체(또는 사용자·역할)를 고르면 아래 표에 그 개체 종류의 권한이 한 줄씩 나온다.
  열은 사용 권한 | 허용 | 권한 부여 | 거부(SQL Server만). 확인란 규칙은 SSMS와 같다: 권한 부여를 켜면 허용도 켜지고, 허용을 끄면 권한 부여도 꺼지고,
  거부를 켜면 둘 다 꺼진다. 목록에서 "제거"는 그 행의 권한을 모두 취소한다.
  SQL Server 로그인은 "데이터베이스:" 콤보에서 매핑된 데이터베이스를 골라 그 데이터베이스 사용자의 권한을 본다.
- **개체 추가 / 사용자 또는 역할 추가:** 한 번에 읽은 목록을 이름 필터로 좁혀 여러 개 고른다(이미 있는 항목은 빠짐).
- **스크립트의 암호:** 미리 보기·복사·쿼리 창으로 열기에는 `'********'`로 보이고, 실행할 때만 실제 값이 들어간다.
  암호가 든 문은 쿼리 관리자 기록을 끄고 실행한다.

## 구조

| 위치 | 클래스 | 역할 |
|---|---|---|
| `com.seizia.chromite.model.admin` (`security` 패키지, 새로 내보냄) | `SecurityDialect` | DB별 부분의 기반: 읽기 도우미, 권한 차이 → GRANT/REVOKE/DENY, 스크립트 글, 격리 연결에서 실행 |
| | `PostgreSecurityDialect`, `SQLServerSecurityDialect`, `MySQLSecurityDialect` | DB별 읽기(시스템 카탈로그)와 문 만들기 |
| | `SecurityPrincipal`, `PermissionGrid`, `Securable`, `SecurityStatement` | 편집 상태(원본·편집본), 권한 표, 보안 개체 이름, 실행할 문/보여 줄 문 |
| `com.seizia.chromite.ui.swing` | `SecurityCommands` | 탐색기 명령의 판단·실행(속성, 새로 만들기, 삭제, 개체 사용 권한), 스크립트 미리 보기 → 실행 → 노드 새로 읽기 |
| | `PrincipalPropertiesDialog` | 로그인·사용자·역할 속성 / 새로 만들기 창 |
| | `ObjectPermissionsDialog` | 개체의 사용 권한 창 |
| | `PermissionEditorPanel` | 두 창이 함께 쓰는 권한 편집 부품(위 목록 + 아래 허용/권한 부여/거부 표) |
| | `SecurityPickerDialog` | 개체 추가 / 사용자 또는 역할 추가 |
| | `SecurityLabels`, `internal/SecurityMessages`(+ `_ko`) | 화면 문구 |

- 진입점: `ObjectExplorerPanel` 오른쪽 클릭 메뉴 — 새 로그인/역할/사용자…(폴더에 따라 이름이 바뀜), 사용 권한…, 속성(맨 아래),
  기존 Delete(연결 노드 외에 로그인·사용자·역할에서도). 탐색기 파일은 메뉴 항목·활성화 줄과 새로 고침 메서드 하나(`refreshNavigatorNode`)만 더했다.
- 실행 뒤 Logins·Roles·Users 폴더를 다시 읽어 새 주체·이름 변경·삭제가 트리에 보인다. 트리가 그 폴더를 보여 주고 있으면 Object Explorer의
  새로 고침(F5와 같은 경로, `ObjectExplorerPanel.refreshNavigatorNode`)으로, 아니면 탐색기 모델 노드만 `FORCE_REFRESH`로 다시 읽는다.
  폴더 노드의 새로 고침은 폴더의 부모 객체(SQL Server·MySQL은 데이터 원본)를 다시 읽어 트리 노드가 새로 만들어지므로, 모델 이벤트만으로는
  SQL Server Logins 폴더에 새 로그인이 나타나지 않았다(확인 중 발견).
- MANIFEST: `com.seizia.chromite.model.admin`이 `...security` 패키지를 내보낸다. Swing 번들은 이미 그 번들과 세 드라이버 번들에 의존하므로 바꾸지 않았다.

## SWT에서 옮긴 것 (근거)

| 동작 | SWT·모델 위치 | Swing |
|---|---|---|
| PostgreSQL 역할 만들기·옵션 | `PostgreRoleManager.addRoleOptions`(ext.postgresql `PostgreRoleManager.java:138-182`): 서버 확장이 지원하는 옵션만, 만들 때 PASSWORD(기록 끔)·VALID UNTIL | `PostgreSecurityDialect.generateScript`(바꿀 때는 바뀐 옵션만) |
| PostgreSQL 이름 바꾸기·지우기 | `PostgreRoleManager.java:116-130` `ALTER ROLE … RENAME TO`, `DROP ROLE` | 같은 문 |
| PostgreSQL 암호 | `PostgresUserPasswordManager.changeUserPassword`(`ALTER USER … WITH PASSWORD`, 기록 끔) | `ALTER ROLE … WITH PASSWORD`, 기록 끔 |
| PostgreSQL 역할 멤버 | `PostgreRole.getObjectDefinitionText` 479–488행 `GRANT <role> TO <member>` | 같은 문과 `REVOKE <role> FROM <member>` |
| PostgreSQL 권한 문 | `PostgreCommandGrantPrivilege.getPersistActions` 146행 `GRANT … ON <TABLE|SEQUENCE|FUNCTION|SCHEMA|DATABASE> … TO`, 233행 PUBLIC은 따옴표 없이 | `PostgreSecurityDialect.formatTarget/formatGrantee` |
| PostgreSQL 권한 종류 | `RolePermissionsEditor.getSupportedPrivilegeTypes`(ext.postgresql.ui): `getSupportedPrivilegeTypes()`를 `isValid`·`supportsType(객체 클래스)`로 거름 | 같은 거르기(종류별 대표 클래스) |
| PostgreSQL 권한 편집 화면 | `PostgresPermissionsEditor`(ext.postgresql.ui): 객체 트리 + 권한 확인란 표 + With Grant | `PermissionEditorPanel` |
| SQL Server 로그인 | `SQLServerLoginManager`(ext.mssql): `CREATE LOGIN … WITH PASSWORD =` 59–61행(기록 끔), `ENABLE|DISABLE` 74행, `DROP LOGIN` 82행, `WITH NAME =` 104행 | 같은 문 + 기본 데이터베이스·암호 정책 `ALTER LOGIN … WITH` |
| SQL Server 로그인 만들기 대화상자 | `SQLServerLoginConfigurator`(ext.mssql.ui, 이름·암호만) | 로그인 - 신규 창 |
| MySQL 사용자 만들기·암호 | `MySQLCommandChangeUser`(ext.mysql.ui) `CREATE USER 'u'@'h' IDENTIFIED BY`, `isAlterUSerSupported`면 `ALTER USER … IDENTIFIED BY`, 아니면 `SET PASSWORD … PASSWORD()` | `MySQLSecurityDialect.generateScript` |
| MySQL 이름 바꾸기·지우기 | `MySQLUserManager.CommandRenameUser`·`CommandDropUser`(ext.mysql.ui) `RENAME USER`, `DROP USER` | 같은 문 |
| MySQL 권한 문 | `MySQLCommandGrantPrivilege`(ext.mysql.ui) `GRANT <PRIV> ON `db`.`tbl` TO 'u'@'h' [WITH GRANT OPTION]`, 권한 이름은 `getFixedPrivilegeName()` 대문자 | 같은 모양, 권한 종류는 `MySQLDataSource.getPrivileges`(`SHOW PRIVILEGES`)의 Context로 거름 |
| MySQL 사용자 편집 화면 | `MySQLUserEditorGeneral`·`MySQLUserEditorPrivileges`(ext.mysql.ui) | `PrincipalPropertiesDialog`의 일반·사용 권한 탭 |
| 공통 ACL 편집기 | `ui.editors.acl`(`ObjectACLManager`, `ACLCommandChangePrivilege`) — 어느 드라이버도 쓰지 않음(검색 결과 참조 없음) | 옮기지 않음 |

## 설계 결정과 이유

- **모델 명령 묶음(`DBECommandContext`) 대신 "원본 → 편집본" 차이를 SQL 문 목록으로.** `AbstractCommandContext`는 명령 대상 객체마다
  객체 관리자를 찾는데(`AbstractCommandContext.java:600-603`, 없으면 예외), MySQL 사용자 관리자는 SWT 플러그인(ext.mysql.ui)에만 있고
  SQL Server 데이터베이스 사용자·역할·권한은 모델 클래스 자체가 없다. SSMS 속성 창도 OK에서 한 번에 적용하므로, 편집 상태 두 벌의 차이를
  DB별 문으로 만들고 문 모양은 위 표의 DBeaver 클래스와 같게 했다. 모델 쪽 재사용은 객체 식별(탐색기 객체), 권한 종류 목록
  (`PostgrePrivilegeType`, `MySQLPrivilege`), 서버 기능 판단(`PostgreServerExtension`, `MySQLUtils.isAlterUSerSupported`), 실행 문맥이다.
- **읽기는 시스템 카탈로그를 직접.** 모델 캐시는 이 창이 바꾼 뒤 낡고, 모델의 권한 읽기는 빠진 정보가 있다:
  PostgreSQL `PostgreRole.getPrivileges`는 스키마·시퀀스 권한의 WITH GRANT OPTION을 버리고(`PostgreRole.java:629-636`에서 `grantable=false`로 만듦),
  MySQL `MySQLUser.getGrants`는 `SHOW GRANTS` 줄을 통째로 대문자로 바꿔(`MySQLUser.java:185`) 대소문자를 가리는 서버에서 객체 이름이 틀린다.
  그래서 PostgreSQL은 ACL 열을 `aclexplode`로, SQL Server는 `[db].sys.database_permissions`·`sys.server_principals`로,
  MySQL/MariaDB는 `information_schema.SCHEMA_PRIVILEGES`·`TABLE_PRIVILEGES`와 `mysql.procs_priv`로 읽는다.
  객체 소유자의 암묵적 권한은 고칠 대상이 아니라 PostgreSQL에서 뺐다(`grantee <> owner`).
- **격리 연결에서 실행.** `DBSInstance.openIsolatedContext`로 새 연결을 열어 문을 차례로 실행하고 닫는다. SQL Server의 데이터베이스 안 문
  (사용자, 역할, 권한)은 `USE [db]`가 필요한데, 사용자 쿼리 창이나 탐색기 연결의 현재 데이터베이스를 바꾸지 않기 위해서다.
  스크립트에도 같은 `USE`가 보이므로 쿼리 창으로 열어 실행해도 결과가 같다. 연결이 수동 커밋이면 끝에 커밋하고 실패하면 되돌린다.
  DDL이 트랜잭션에 묶이지 않는 DB(MySQL, SQL Server 로그인)는 실패한 문 앞까지 적용되므로, 실패 뒤에도 폴더를 다시 읽고
  실패한 문을 오류 창에 보여 준다.
- **SQL Server는 로그인 하나에서 모두.** 탐색기에 데이터베이스 사용자·역할 폴더가 없어(ext.mssql `plugin.xml`의 Security 폴더는 Logins뿐)
  SSMS 로그인 속성의 "사용자 매핑"으로 데이터베이스별 사용자·기본 스키마·데이터베이스 역할을, "사용 권한"으로 그 사용자의 권한을 편집한다.
  트리에 폴더를 더하려면 새 모델 클래스와 `ext.mssql/plugin.xml` 수정이 필요해 이번에는 하지 않았다(같은 파일을 다른 작업이 고치는 중).
  개체의 사용 권한 창은 그 데이터베이스의 사용자·역할(고정 역할, dbo, sys, INFORMATION_SCHEMA 제외 — GRANT 대상이 될 수 없음)을 고른다.
- **MySQL의 GRANT OPTION은 객체마다 하나.** MySQL은 권한마다가 아니라 (계정, 객체)마다 GRANT OPTION을 둔다. 그래서 MySQL 문 만들기는 한 객체의
  권한 부여 확인란 중 하나라도 켜져 있으면 그 객체의 모든 권한을 `WITH GRANT OPTION`으로 주고, 모두 꺼지면 `REVOKE GRANT OPTION ON`을 쓴다.
  MariaDB 역할은 호스트 없이(`'role'`) 쓰고, `information_schema`의 `'role'@''`도 같은 이름으로 맞춘다.
- **권한 종류 목록.** PostgreSQL·MySQL은 모델(위 표)에서, SQL Server는 모델에 없어 SSMS 보안 개체 페이지가 보여 주는 객체 종류별 목록
  (Microsoft 문서 "Permissions (Database Engine)")을 쓴다. 목록에 없는 권한이 이미 있으면 표에 더해 보여 준다(취소할 수 있게).
- **새 주체의 기본값.** SSMS 새 로그인처럼 SQL Server는 기본 데이터베이스 master, 암호 정책 켬·만료 끔, 로그인 사용.
  PostgreSQL 새 역할은 로그인 가능·상속 켬(그 밖의 옵션 끔), MySQL 새 사용자는 호스트 `%`.
- **암호는 화면 밖으로 나가지 않는다.** 스크립트 미리 보기·복사·쿼리 창으로 열기는 가린 글(`SecurityStatement.displaySql`)만 쓰고,
  실행할 때는 쿼리 관리자 기록을 끈다. 창은 실행 뒤 편집본의 암호를 지우고, 암호 칸의 글자 배열은 읽은 뒤 0으로 채운다.
- **화면 문구.** 기능 전용 묶음 `SecurityMessages`(영어 기본 + `_ko`)이고, 한국어 용어는 SSMS 한국어판 보안 화면을 따랐다
  (사용 권한, 허용, 권한 부여, 거부, 보안 개체, 사용자 매핑, 멤버 자격). 권한 이름(SELECT 등)은 SQL 토큰이라 옮기지 않는다.
  화면 문구에는 다른 제품 이름을 쓰지 않는다(2026-11-06 사용자 규칙).

## upstream 대비 변경점

- 새 파일: 모델 번들 `com.seizia.chromite.model.admin/.../security/*`(8개), Swing 번들 `SecurityCommands`, `PrincipalPropertiesDialog`,
  `ObjectPermissionsDialog`, `PermissionEditorPanel`, `SecurityPickerDialog`, `SecurityLabels`, `internal/SecurityMessages`(+ properties 2개).
- 고친 파일: `ObjectExplorerPanel`(메뉴 항목 3개, Delete가 주체도 지움, `refreshNavigatorNode`), `com.seizia.chromite.model.admin/META-INF/MANIFEST.MF`(패키지 내보내기).
- DBeaver 원본(모델·드라이버·SWT) 파일은 고치지 않았다.

## 확인 (2026-11-06)

빌드한 Swing 제품(개인 작업 공간)에서 화면으로 하고, 결과를 `docker exec`로 DB에서 확인했다(테스트 DB `chr_security_editor`, 테스트 암호는 그때 만든 값).

- **PostgreSQL 17:** Roles 폴더 → 새 역할…(`chr_sec_app`, 로그인 가능, `chr_sec_readers` 멤버, `orders` SELECT·DELETE WITH GRANT OPTION) → 스크립트 → Execute.
  `pg_authid`(로그인·상속·암호 있음), `role_table_grants`, `pg_auth_members`로 확인하고 그 암호로 접속해 `orders`를 읽었다.
  속성에서 DELETE 취소·멤버 해제·만료 시각 → `ALTER ROLE … VALID UNTIL`, `REVOKE DELETE`, `REVOKE chr_sec_readers` 확인.
  `customer`의 사용 권한…에서 사용자 추가(INSERT·UPDATE)와 기존 역할 제거(REVOKE) 확인.
  권한이 남은 역할 삭제 → 실패한 문(`DROP ROLE`)과 DB 오류가 오류 창에 나오고, 권한을 모두 제거한 뒤 삭제하면 트리에서 사라졌다.
- **SQL Server 2022:** 새 로그인…(기본 데이터베이스, `chr_security_editor` 매핑·`chr_sec_readers` 역할, `orders` SELECT 허용·INSERT 권한 부여·DELETE 거부) →
  스크립트(`CREATE LOGIN …`, `USE`, `CREATE USER … FOR LOGIN`, `ALTER ROLE … ADD MEMBER`, `GRANT`/`DENY`) → Execute.
  `sys.server_principals`·`sys.database_principals`·`sys.database_role_members`·`sys.database_permissions`와
  `EXECUTE AS LOGIN` + `fn_my_permissions`(orders: SELECT·INSERT, customer: 역할에서 받은 SELECT)로 유효 권한을 확인하고, 그 로그인으로 접속했다.
  속성에서 로그인 사용 끔(`ALTER LOGIN … DISABLE`), 개체 사용 권한에서 `REVOKE GRANT OPTION FOR … CASCADE`·`REVOKE`·`DENY`·역할에 GRANT,
  매핑 해제(`DROP USER`), 삭제(`DROP LOGIN`) 확인.
- **MariaDB 11.8:** 새 사용자…(`chr_sec_user`@`%`) → 역할 `chr_sec_reader`, 데이터베이스 SHOW VIEW, `orders` SELECT·INSERT WITH GRANT OPTION, 프로시저 EXECUTE →
  `SHOW GRANTS`와 그 계정으로 접속(`SET ROLE`, 읽기, `CALL`) 확인. 계정 잠금(`ACCOUNT LOCK`, `mysql.global_priv` 확인), 테이블 권한 제거
  (`REVOKE …`, `REVOKE GRANT OPTION ON …`), `customer` 사용 권한, 삭제(`DROP USER`) 확인.
  처음 실행에서 `GRANT chr_sec_reader TO …`가 "Access denied"로 실패했다: 역할을 `root@localhost`가 만들어 연결 계정 `root@%`에 ADMIN OPTION이 없었다
  (DB 권한 문제, 오류 창에 실패한 문과 함께 보임). 테스트 준비에서 ADMIN OPTION을 준 뒤 성공했다.
- **화면:** 한국어(OS 기본)·영어(`-nl en`), 라이트·다크에서 속성 창·사용 권한 창·개체 추가·스크립트 미리 보기를 확인했다(글자 잘림 없음).
  OK·스크립트의 입력 검사가 다른 탭의 칸을 가리키면 그 탭으로 옮긴다.
- **확인 중 고친 것:** 새 로그인의 데이터베이스 역할이 현재 로그인(sa)의 것으로 채워지던 문제(`SUSER_SID('')`가 현재 로그인의 SID),
  표 열 수가 필드 초기화 순서 때문에 거부 열 없이 만들어지던 문제, 실행 뒤 SQL Server Logins 폴더에 새 로그인이 안 보이던 문제(Object Explorer 새로 고침 경로로 바꿈),
  스키마 이름 앞에 데이터베이스가 붙던 표시, 미리 보기 창 제목이 속성 창과 같아 구별되지 않던 것("스크립트 - …").
- **알려진 동작:** SQL Server·MySQL에서 Logins/Users 폴더를 새로 고치면 폴더의 부모(데이터 원본)를 다시 읽으므로 F5와 같이 보안 폴더가 접힌다.

## 남긴 것

- **Oracle:** 모델에 `OracleUser`·`OracleRole`·`OraclePrivTable`(읽기)이 있으나 만들기·바꾸기 관리자가 없고 권한 종류 목록도 따로 만들어야 해 이번에 넣지 않았다.
- **SQL Server 데이터베이스 사용자·역할 폴더와 새 데이터베이스 역할:** 탐색기에 폴더가 없다(위 설계 결정). 서버 역할 만들기, 서버 수준 권한(보안 개체의 서버 종류),
  열 단위 권한, 인증서·비대칭 키 로그인도 하지 않았다.
- **MySQL 8 역할**(`mysql.role_edges`)과 MariaDB 새 역할 만들기(`CREATE ROLE`): 기존 MariaDB 역할의 편집·멤버 자격·사용 권한은 된다.
  계정 만료(`PASSWORD EXPIRE`), 리소스 제한(MAX_QUERIES_PER_HOUR 등)도 아직 없다.
- **PostgreSQL 기본 권한(ALTER DEFAULT PRIVILEGES), 열 단위 권한, 연결 수 제한(CONNECTION LIMIT), 역할 설명(COMMENT).**
- **유효 권한 보기**(역할을 통해 받은 권한까지 합친 SSMS "유효" 탭).
- SQL Server 로그인 속성을 열 때 접근 가능한 모든 데이터베이스를 차례로 읽는다(데이터베이스가 아주 많으면 느릴 수 있음).

## 변경 이력

- 2026-11-06 최초 작성: 로그인·사용자·역할 속성과 새로 만들기, 삭제, 개체 사용 권한(SQL Server, PostgreSQL, MySQL/MariaDB).
