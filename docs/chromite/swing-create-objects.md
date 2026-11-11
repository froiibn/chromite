# Swing 새 개체 만들기 (Object Explorer "New &lt;종류&gt;…", 새 데이터베이스, 틀, Oracle 디자이너)

## 목적

SWT 전수 조사 4순위 B7 "개체 만들기"를 옮긴다. 줄은 [swt-parity/C-navigator-tools.md](swt-parity/C-navigator-tools.md) 14,
[swt-parity/D-db-specific.md](swt-parity/D-db-specific.md) P13·P14·P15·S9·M6·M9·M11·O17·O18,
[swt-parity/B-editors.md](swt-parity/B-editors.md) E9다.

- **C-14:** 개체 탐색기의 모든 폴더에서 그 폴더가 담는 종류의 "New &lt;종류&gt;…"(SWT 탐색기의 "Create New …").
- **P13·S9·M6:** 새 데이터베이스 대화상자(PostgreSQL 소유자·템플릿·인코딩·LC_COLLATE·LC_CTYPE·테이블스페이스,
  SQL Server 소유자·정렬 규칙·복구 모델, MySQL/MariaDB 문자 집합·정렬 규칙).
- **P14:** PostgreSQL 새 스키마(소유자)·테이블스페이스(소유자·위치·옵션)·확장(설치할 수 있는 확장 목록, 스키마). 확장 제거는 이미 있는
  탐색기 Delete(`DROP EXTENSION … CASCADE`, swing-explorer-object-actions.md)다.
- **P15·M9·M11·O17:** 시퀀스·트리거·패키지·형식(그리고 PostgreSQL 이벤트 트리거·행 보안 정책, SQL Server DDL 트리거, Oracle 스키마 트리거·큐)의
  CREATE 틀을 쿼리 창에 연다(New View와 같은 길).
- **O18:** 테이블 디자이너의 Oracle 인덱스·외래 키·CHECK(전에는 열만 됨).
- **B-E9:** 개체 만들기 대화상자. 시퀀스는 위 틀로, 열·제약·외래 키·인덱스는 테이블 디자이너로 된다. 사전(가상 모델 설명 열)은 남겼다("남긴 것").

이 기능은 개체 탐색기의 폴더와 객체 관리자가 있는 모든 드라이버에서 "이름만" 대화상자로 동작한다(100개가 넘는 JDBC 데이터베이스 중
객체 관리자가 있는 것). 데이터베이스·스키마 대화상자와 틀은 PostgreSQL, SQL Server, MySQL/MariaDB, Oracle 네 방언에만 있다.

## 화면

| 폴더 | 메뉴 항목(예) | 하는 일 |
|---|---|---|
| 연결의 Databases (PostgreSQL, SQL Server, MySQL/MariaDB) | **New Database…** | 대화상자 → 스크립트 미리 보기 → 실행 |
| PostgreSQL 데이터베이스의 Schemas, Tablespaces, Extensions | **New Schema… / New Tablespace… / New Extension…** | 대화상자 → 미리 보기 → 실행 |
| 시퀀스 폴더 (네 방언, MariaDB) | **New Sequence…** | CREATE 틀을 새 쿼리 창에 |
| 테이블의 Triggers, 스키마의 모든 트리거 폴더 | **New Trigger…** | 틀(테이블 폴더는 그 테이블, 스키마 폴더는 고칠 자리 표시 이름) |
| SQL Server 데이터베이스 트리거, Oracle 스키마 트리거 | **New Database Trigger… / New Schema Trigger…**(드라이버의 종류 이름) | DDL 트리거 틀 |
| PostgreSQL 이벤트 트리거, 테이블의 Policies | **New Event Trigger… / New Policy…** | 틀 |
| Oracle·MariaDB Packages | **New Package…** | 명세와 본문 틀 |
| PostgreSQL·SQL Server·Oracle Types | **New Type…** | 틀(복합 형식, 별칭 형식, OBJECT 형식) |
| Oracle Queues | **New Queue…** | `DBMS_AQADM` 틀(큐 테이블, 큐, 시작) |
| 테이블의 Columns, Constraints/Keys, Foreign Keys, Indexes | **New Column… / New Index…** 등 | 테이블 디자이너를 연다 |
| 그 밖에 객체 관리자가 있는 폴더 | **New &lt;종류&gt;…** | 이름 대화상자 → 미리 보기 → 실행 |

- 메뉴 항목은 하나이고 이름이 폴더의 종류를 따른다(종류 이름은 드라이버 번들의 탐색기 이름이라 한국어 화면에서는 "새 시퀀스").
  New View…/New Procedure… 바로 아래에 있고, 맞는 폴더에서만 보인다(SQL Server 에이전트 항목과 같은 방식).
- New Table·New View·New Procedure·New Function·보안 주체(New Login/User/Role)·MySQL 이벤트(New Event)처럼 따로 항목이 있는 폴더에는
  나오지 않는다.
- 대화상자의 "(기본값)"이나 빈 칸은 문장에 넣지 않는다(서버나 템플릿의 값). 대화상자 아래에 그렇게 적었다.
- 대화상자의 OK 다음은 언제나 스크립트 미리 보기다(복사, 쿼리 창에서 열기, 실행, **취소가 기본 단추**). DDL을 바로 커밋하는 DB
  (MySQL·MariaDB·Oracle)는 머리글에 "롤백할 수 없습니다" 줄이 붙는다. 실행을 고르면 그 연결에 커밋하지 않은 트랜잭션이 있을 때
  커밋 / 롤백 / 취소를 먼저 묻는다.
- 틀은 실행하지 않고 쿼리 창에 열린다. 실행(F5)이 끝날 때마다 그 폴더를 다시 읽어 새 개체가 트리에 나타난다(New View와 같음).
- 화면 문구는 `CreateObjectMessages`(영어 기본, `_ko` 한국어)다.

## 구조

| 클래스 | 역할 |
|---|---|
| `NewObjectCommands` (새) | 메뉴 항목 하나, 폴더 → 길(틀·디자이너·대화상자) 판단, 실행. 탐색기에는 항목 넣기와 `update`만 |
| `NewObjectDialogs` (새) | 데이터베이스·스키마·테이블스페이스·확장·이름 대화상자, 목록 읽기, 새 모델 객체에 값 넣기(`Configurer`), SQL Server 추가 문장 명령 |
| `SourceScripts` (고침) | `getTemplateKind`와 틀 문장(`getObjectTemplate`). `NewObjectKind`에 SEQUENCE·TRIGGER·DATABASE_TRIGGER·EVENT_TRIGGER·POLICY·PACKAGE·TYPE·QUEUE |
| `ExplorerObjectCommands` (고침) | 미리 보기·트랜잭션 질문·실행 경로(`runChange`)와 `Change`·`ChangeBuilder`·`createCommandContext`를 패키지 안에 엶 |
| `DesignerDialogs` (고침) | 폼 도우미(`addRow`, `showDialog`, `error`)를 패키지 안에 엶 |
| `design.OracleTableDesignerDriver` (새) | Oracle 인덱스·외래 키·CHECK의 드라이버 부분. `plugin.xml`에 `OracleTableBase` 어댑터 |
| `TableDesignerPanel` (고침) | 저장 전 열린 트랜잭션 질문 |
| `TableDesignSession` (고침) | 키·인덱스·외래 키 채우기가 실패하면 만든 객체를 거둠(`discardNew`) |
| `internal.CreateObjectMessages` (새) | 화면 문구(`.properties`, `_ko.properties`) |
| `ObjectExplorerPanel` (연결 지점) | `NewObjectCommands`를 만들고 메뉴에 넣고 선택이 바뀌면 `update` |
| `PostgreDatabase`·`PostgreDatabaseManager` (모델, 고침) | `setCollate`/`setCtype`, CREATE DATABASE의 `LC_COLLATE`·`LC_CTYPE` |
| `SQLServerDatabase` (모델, 고침) | `getCollationNames`: 정렬 규칙 목록(`sys.fn_helpcollations()`, 이미 읽던 목록)을 공개 |

흐름(대화상자·이름 길): 탐색기 메뉴 → (EDT) 진행 대화상자에서 목록 읽기 → 대화상자 → `ExplorerObjectCommands.runChange`:
작업 스레드에서 원자적 명령 묶음(`AbstractCommandContext(context, true)`)에 객체 관리자 `createNewObject`(`OPTION_SKIP_CONFIGURATION`) →
`Configurer`가 값 넣기 → 실행 동작으로 스크립트 → 미리 보기 → 열린 트랜잭션 질문 → `saveChanges`. 저장 뒤 `CreateObjectReflector`가
모델 캐시에 넣고 객체 추가 이벤트를 보내 탐색기에 노드가 생긴다.

## SWT에서 옮긴 것 (근거, 커밋 `263b8ddb45`)

| SWT | Swing |
|---|---|
| `NavigatorHandlerObjectCreateNew`(폴더 자식 종류의 `DBEObjectMaker`가 있으면 "Create New") | `NewObjectCommands.getTarget`(같은 조건 + 길 나누기) |
| `PostgreDatabaseConfigurator`·`PostgreCreateDatabaseDialog`(이름, 소유자, 템플릿, 인코딩, 테이블스페이스) | `NewObjectDialogs.askPostgreDatabase`(같은 setter, LC_COLLATE·LC_CTYPE 더함) |
| `SQLServerDatabaseConfigurator`·`SQLServerCreateDatabaseDialog`(이름만) | `askSqlServerDatabase`(이름 + 소유자·정렬 규칙·복구 모델) |
| `MySQLCatalogConfigurator`·`MySQLCreateDatabaseDialog`(이름, 문자 집합, 정렬 규칙) | `askMySQLDatabase` |
| `PostgreSchemaConfigurator`·`PostgreCreateSchemaDialog`(이름, 소유자) | `askPostgreSchema` |
| `PostgreTablespaceConfigurator`·`PostgreCreateTablespaceDialog`(이름, 소유자, 위치, 옵션) | `askPostgreTablespace` |
| `PostgreExtensionConfigurator`·`PostgreCreateExtensionDialog`(설치할 확장 표, 스키마) | `askPostgreExtension`(이미 설치된 확장은 뺌) |
| `PostgreSequenceConfigurator`(`CreateSequencePage`), `MySQLSequenceConfigurator`, `OracleSequenceConfigurator` | SEQUENCE 틀 |
| `PostgreTriggerConfigurator`(`PostgreTriggerEditPage`: 이름, 트리거 함수), `MySQLTriggerConfigurator`, `OracleTableTriggerConfigurator` | TRIGGER 틀(PostgreSQL은 트리거 함수까지) |
| `PostgreEventTriggerConfigurator`(이름, 이벤트, 함수), `PropertyObjectConfigurator`(정책) | EVENT_TRIGGER·POLICY 틀 |
| `OraclePackageConfigurator`, `OracleDataTypeConfigurator`, `OracleQueueConfigurator` | PACKAGE·TYPE·QUEUE 틀 |
| `OracleIndexConfigurator`, `OracleForeignKeyConfigurator`, `OracleConstraintConfigurator` | `OracleTableDesignerDriver` |

## 설계 결정과 이유

- **메뉴 항목 하나, 이름은 폴더의 종류.** 종류마다 항목을 두면 메뉴가 끝없이 길어진다. SWT도 "Create New &lt;종류&gt;"
  하나였다. 맞지 않는 폴더에서는 숨긴다(꺼진 항목을 늘 두면 다른 기능 항목과 섞여 길어짐). 같은 메뉴를 고치는 다른 작업(B8 탐색기)과
  겹치지 않게 탐색기에는 세 줄만 넣었다.
- **본문이 필요한 종류는 틀.** 트리거 함수, 패키지 본문, 형식의 속성은 어차피 사용자가 써야 한다. SWT 구성기는 이름만 받고 모델 기본값으로
  만든 뒤 개체 편집기를 열었다(Swing에는 그 편집기가 없음). 그래서 New View처럼 실행할 수 있는 틀을 쿼리 창에 연다. 틀은 습관처럼
  F5를 눌러도 해가 없게 만들었다:
  - `OR REPLACE`를 쓰지 않는다. 이름 확인은 그 폴더 안만 보므로(예: Oracle 트리거 이름은 스키마 전체에서 하나) 다른 개체를 덮어쓸 수 있다.
    PostgreSQL 트리거 함수(`<이름>_fn`)도 `CREATE FUNCTION`이라 같은 이름이 있으면 실패한다.
  - 트리거 본문은 아무것도 바꾸지 않는다(BEFORE … `RETURN NEW`, `NULL;`, 빈 `BEGIN END`, AFTER … `SET NOCOUNT ON`).
  - 뷰의 트리거는 틀을 주지 않는다. 뷰는 INSTEAD OF 트리거만 받는데, 빈 본문이면 INSERT한 행을 말없이 버린다.
  - 스키마 수준 트리거 폴더는 테이블을 고를 수 없어 자리 표시 이름(`table_name`, `TableName`, `TABLE_NAME`)을 쓴다. 고치지 않고 실행하면 실패한다.
  - 행 보안 정책은 `FOR SELECT … USING (true)`(행 보안이 켜진 테이블에서도 읽기만 허용).
  - PostgreSQL 10 이하는 `EXECUTE PROCEDURE`, 11 이상은 `EXECUTE FUNCTION`.
  - 이벤트 트리거 함수는 `public` 스키마에 둔다(이벤트 트리거는 데이터베이스 수준이라 폴더에 스키마가 없음).
- **테이블 자식 폴더는 테이블 디자이너.** 열·키·외래 키·인덱스·CHECK 관리자는 테이블을 컨테이너로 받고 열 구성이 필요하다.
  디자이너가 이미 그 대화상자와 저장 흐름을 가지고 있으므로 연다(SSMS의 New Column…도 디자이너). 디자이너 안에서 추가 단추를 누른다.
  스키마의 "모든 인덱스" 같은 폴더(컨테이너가 스키마)는 관리자가 받지 못하므로 항목을 보이지 않는다.
- **대화상자 길은 모델 객체 관리자.** 문장은 드라이버 관리자가 만들어 SWT와 같다(예: `PostgreSchemaManager`가 현재 사용자와 다른 소유자만
  `AUTHORIZATION`을 쓴다). 원자적 명령 묶음이라 미리 보기에서 취소하면 모델에 아무것도 남지 않고, 실행 경로는 삭제·이름 바꾸기와 같아
  미리 보기·트랜잭션 질문·연결이 끊겼을 때 다시 시도가 같다.
- **이름만 길에서 빼는 종류.** 뷰·루틴·테이블·트리거·패키지·형식·별칭(SQL Server 시노님은 대상이 필요)은 이름만으로 맞는
  문장이 나오지 않는다(예: `CREATE SYNONYM x FOR null`). MySQL 이벤트는 작업 스케줄러가, Oracle 스키마는 사용자(보안)가 만든다
  (모델은 암호 없는 `CREATE USER`를 만든다). 틀이 없는 드라이버의 시퀀스는 관리자 기본값으로 `CREATE SEQUENCE`가 되므로 이름만 길에
  남겼다(관리자가 만들 수 있다고 할 때만, `canCreateObject`. Generic·Altibase 시퀀스 관리자는 아니라고 해서 항목이 없다).
  모델이 이름 대화상자가 묻지 않는 필수 값(`@Property(required = true)`, 이름과 필수 암호 말고)을 선언한 종류도 뺀다
  (`NewObjectDialogs.hasUnaskedRequiredValue`, 2026-11-08: Exasol CONNECTION의 연결 문자열, [password-handling.md](password-handling.md)).
- **PostgreSQL 스키마 소유자.** `PostgreSchemaManager`가 현재 연결 사용자와 같은 소유자는 `AUTHORIZATION`을 빼는데,
  연결 대화상자에서 입력한 사용자 이름은 연결 설정에 저장되지 않아(비밀번호를 저장하지 않는 연결) 같은 사용자여도 `AUTHORIZATION`이 붙는다.
  결과는 같다.
- **"(기본값)"은 문장에서 뺀다.** SWT는 소유자·인코딩·테이블스페이스를 현재 DB 값으로 미리 골라 늘 문장에 넣었다. 빼도 결과가 같고
  스크립트에 사용자가 바꾼 값만 보인다. 예외: PostgreSQL 테이블스페이스는 모델 문장이 `OWNER`를 늘 쓰므로 SWT처럼 현재 DB 소유자를 미리 고른다.
- **SQL Server 새 데이터베이스.** 모델은 `CREATE DATABASE [이름];`만 쓴다. SSMS 새 데이터베이스의 일반 페이지처럼 소유자·정렬 규칙·
  복구 모델을 받아 같은 객체의 추가 명령이 `ALTER DATABASE … COLLATE`, `ALTER AUTHORIZATION ON DATABASE::… TO …`,
  `ALTER DATABASE … SET RECOVERY …`를 잇는다. `CREATE DATABASE`는 사용자 트랜잭션 안에서 실행할 수 없으므로(Msg 226) 추가 명령이
  `OPTION_AVOID_TRANSACTIONS`로 저장을 자동 커밋으로 한다(추가 문장이 없어도 넣음). 정렬 규칙은 목록에 있는 이름만 받는다(식별자 글자만).
- **PostgreSQL LC_COLLATE·LC_CTYPE.** SWT 대화상자에는 없었다. 모델에 이미 읽기용 필드(`datcollate`, `datctype`)가 있어 setter만 더하고
  관리자가 새 DB의 CREATE에 쓴다. 목록은 `pg_collation`의 로캘이고 직접 쓸 수도 있다. 템플릿과 다른 로캘은 서버가 template0을 요구한다
  (오류 메시지가 알려 줌).
- **트랜잭션(사용자 규칙 "트랜잭션 안전").** 대화상자 길은 `ExplorerObjectCommands`의 질문을 그대로 쓴다. 테이블 디자이너 저장은 전에
  묻지 않았다: 저장 경로(`AbstractCommandContext.saveChanges`)는 명령마다 커밋하고 MySQL·Oracle DDL은 암묵 커밋이므로, 수동 커밋 연결의
  열린 트랜잭션이 함께 커밋될 수 있었다. 미리 보기에서 실행을 고른 뒤 같은 질문(`ExitTransactionCheck.confirmObjectChange`)을 하게 했다.
  틀은 실행하지 않고 쿼리 창에 여므로 이 질문이 없다. 쿼리 창에서 실행하는 DDL은 사용자가 쓴 다른 문장과 같다("남긴 것").
- **Oracle 외래 키 규칙.** Oracle은 `ON DELETE CASCADE`·`SET NULL`만 받고 `ON UPDATE`가 없다(`OracleTableForeignKey.setUpdateRule`은 무시).
  SWT 설정기는 다섯 규칙을 보여 RESTRICT·SET DEFAULT를 고르면 서버가 거절했다. 공통 대화상자는 그대로 두고 드라이버가 추가할 때 이유와
  함께 거절한다. 디자이너는 관리자가 새 객체를 명령 묶음과 캐시에 넣은 뒤 드라이버가 채우므로, 채우기가 실패하면 그 객체를 지우는 명령으로
  거둔다(`TableDesignSession.discardNew`, 만들기와 지우기 명령이 합쳐져 아무것도 저장되지 않음). 키·인덱스·외래 키 모두 같다.

## upstream 대비 변경점

- 모델 `PostgreDatabase`: `setCollate`, `setCtype` 추가. `PostgreDatabaseManager`: 새 DB의 `LC_COLLATE`·`LC_CTYPE`.
- 모델 `SQLServerDatabase`: `getCollationNames` 추가(이미 있던 비공개 목록을 복사해 돌려줌).
- 모델 `PostgreSchemaManager`: 소유자 ID가 0(고르지 않음)이면 `AUTHORIZATION`을 쓰지 않음(전에는 `AUTHORIZATION public`).
- `ext.mysql` `plugin.xml`: MariaDB 시퀀스 항목 이름을 단수 키 `tree.sequence.node.name`(영어 "Sequence", 한국어 "시퀀스", 다른 언어는 영어로
  보임)으로. upstream은 폴더 이름 키(복수)를 썼다.
- SWT 구성기 대화상자 대신 Swing 대화상자·틀. 위 설계 결정.
- 모델 `ExasolUserManager`: 새 사용자의 고정 암호 `"password"`를 없앰. 빈 암호의 CREATE·ALTER는 거절. 암호 따옴표 처리, 쿼리 관리자 기록 끄기.
  `ExasolUser.getPassword`는 `password = true, required = true`(아래 "새 사용자의 암호").
- 모델 `MimerProgram`: 암호를 필수로 선언, 빈 암호의 `CREATE IDENT … AS PROGRAM`은 거절. `MimerProgramManager`: CREATE의 기록 끄기.

## 확인 (2026-11-08)

빌드한 제품 한 벌을 시험 데이터 폴더(`CHROMITE_DATA`)와 작업 공간을 scratch에 두고 영어(`-nl en`)와 한국어로 실행했다. 입력은 앱 안의
드라이버(attach 에이전트)로만 보냈다. Docker 시험 DB에 이 작업의 DB·스키마(`chr_p4_create`, Oracle `CHR_P4C`)를 만들어 쓰고 끝에 지웠다.

| DB | 확인한 것 | 결과 |
|---|---|---|
| PostgreSQL 17 | New Database…(소유자 postgres, 템플릿 template0, UTF8, LC_COLLATE·LC_CTYPE `C`) | 미리 보기 `CREATE DATABASE … OWNER … TEMPLATE … ENCODING … LC_COLLATE = 'C' LC_CTYPE = 'C';`, 취소가 기본. 실행 뒤 pg_database의 소유자·인코딩·로캘이 맞고 트리에 노드가 생김 |
| PostgreSQL | New Schema…(소유자), New Tablespace…(위치 빈 칸이면 오류 문구, 옵션), New Extension…(pg_trgm, public) | 각 미리 보기 뒤 실행, pg_namespace·pg_tablespace(옵션 `seq_page_cost=1.0`, 위치)·pg_extension 확인. 확장 Delete → `DROP EXTENSION pg_trgm CASCADE;` 실행 |
| PostgreSQL | New Sequence…, New Data type…, 테이블 New Trigger…(트리거 함수 + `EXECUTE FUNCTION`), New Policy…, New Event Trigger… | 틀을 F5로 실행해 모두 만들어짐. 두 번째 New Sequence…는 `new_sequence1`. 스키마의 Indexes 폴더에는 항목이 없고 테이블의 Indexes 폴더 New Index…는 디자이너를 엶 |
| SQL Server 2022 | New Database…(소유자 sa, 정렬 규칙 Latin1_General_100_CI_AS, SIMPLE) | 미리 보기 `CREATE DATABASE`, `ALTER DATABASE … COLLATE`, `ALTER AUTHORIZATION`, `SET RECOVERY`. 실행 뒤 sys.databases의 값이 맞음 |
| SQL Server | New Sequence…, New Data Type…, 테이블 New Trigger…, Database triggers New Trigger…(DDL 트리거), 스키마의 Triggers(자리 표시 `dbo.TableName`) | 틀에 `USE`·`GO`, 실행 뒤 sys.sequences·sys.types·sys.triggers 확인. Synonyms 폴더에는 항목이 없음 |
| MariaDB 11.8 | 수동 커밋 연결, 쿼리 창에서 INSERT로 트랜잭션을 연 뒤 New Database…(utf8mb4, utf8mb4_unicode_ci) | 미리 보기 머리글에 DDL 바로 커밋 안내. 실행 → "커밋하지 않은 트랜잭션" 질문(취소가 기본). 취소하면 DB가 생기지 않음, 롤백을 고르면 만들어지고 INSERT는 없어짐 |
| MariaDB | New Sequence…, New Package…(명세·본문, `DELIMITER $$`), 테이블 New Trigger… | 실행 뒤 information_schema에 시퀀스, PACKAGE·PACKAGE BODY, 트리거. Events 폴더에는 작업 스케줄러의 New Event…만 |
| Oracle Free 23ai | New Sequence…, New Queue…(`DBMS_AQADM`), New Type…, New Package…, Schema Triggers New Trigger…(`AFTER DDL ON CHR_P4C.SCHEMA`), 테이블 New Trigger… | 실행 뒤 ALL_OBJECTS에 모두 VALID, ALL_QUEUES에 큐(사용). Schemas 폴더에는 항목이 없음 |
| Oracle | 수동 커밋 연결. 디자이너에서 인덱스(REF_ID), CHECK, 외래 키(먼저 RESTRICT → "Oracle supports ON DELETE CASCADE or SET NULL only" 거절, 다시 CASCADE). 쿼리 창에서 INSERT로 트랜잭션을 연 뒤 저장 | 미리 보기에 세 문장만(거절된 외래 키가 남지 않음), 실행 → 트랜잭션 질문 → 롤백. ALL_CONSTRAINTS·ALL_INDEXES에 세 객체, CHR_P4C 세션에 열린 트랜잭션 없음 |

한국어 화면: 메뉴 "새 데이터베이스…", "새 시퀀스…", 대화상자 문구와 오류("이름을 입력하십시오."), 미리 보기, 트랜잭션 질문을 확인했다.
이름만 대화상자 길은 실행해 보지 못했다: 시험 DB 다섯(Altibase 포함)에서 그 길로 가는 폴더가 없다(남는 관리자는 pgAgent 작업뿐이고 시험
컨테이너에 pgAgent가 없음, Altibase 시퀀스 관리자는 만들 수 없다고 함). 대화상자 길과 다른 점은 이름 입력과 `setName`뿐이다.
시험 중 찾아 고친 것: 디자이너에서 드라이버가 외래 키 규칙을 거절하면 이름 없는 외래 키가 명령 묶음에 남아 다음 저장 스크립트가
"Empty foreign key name"으로 실패했다(`TableDesignSession.discardNew`). MariaDB 시퀀스 폴더의 항목 이름이 복수형이라 "New Sequences…"로
보였다(ext.mysql `plugin.xml`의 항목 이름을 단수 `tree.sequence.node.name`으로). PostgreSQL New Schema…에서 소유자를 "(기본값)"으로 두면
`AUTHORIZATION public`이 나가 "role "public" does not exist"로 실패했다: 소유자 ID 0을 역할 캐시가 PUBLIC 의사 역할로 읽는다. SWT도
연결 설정에 사용자 이름이 없으면 같은 문장이 나갔다. 모델 `PostgreSchemaManager`가 ID 0이면 `AUTHORIZATION`을 쓰지 않는다.

## 새 사용자의 암호 (2026-11-08)

`chromite/exasol-password-fix`(보안). 이름만 대화상자 길에서 고정 암호의 사용자가 만들어지던 것을 고쳤다.

- **증상:** Exasol 연결의 Users 폴더에서 New User…(이름만 대화상자 길, 이 문서 "구조")를 고르면 이름만 받고
  `CREATE USER "이름" IDENTIFIED BY "password"`를 만들었다. 실행하면 누구나 아는 암호의 계정이 생긴다.
  - `ExasolUserManager.createDatabaseObject`(고치기 전 `:69`)가 새 사용자를 암호 `"password"`로 만들었다.
  - `addObjectCreateActions`(`:81`)가 그 값을 따옴표 처리 없이 문장에 넣었다.
- **근본 원인:** SWT에서는 드라이버 UI 구성기(`263b8ddb45`의 `ExasolUserConfigurator`·`ExasolUserDialog`)가 암호를 가려서 받아(LOCAL이면 필수)
  setter로 덮어썼다. Swing의 이름만 길은 구성기를 건너뛰므로(`OPTION_SKIP_CONFIGURATION`) 관리자의 자리 표시 기본값이 그대로 문장이 되었다.
  관리자가 필수 비밀값을 지어내는 곳이면 어디서나 같은 일이 생긴다.
- **전수 조사:** `plugins/`의 `*UserManager`·`*RoleManager`·`*LoginManager`·`*SchemaManager`, 사용자형 객체(`objectType`이 User·Role·Login·Ident·
  Program·Privilege)의 관리자, `IDENTIFIED BY`·`PASSWORD`·`USING` 문자열을 모두 찾아 읽었다.

| 위치 | 새 객체의 암호 | 판단 |
|---|---|---|
| `ext.exasol` `ExasolUserManager` | 고정 `"password"`, 따옴표 처리 없음 | **고침** |
| `ext.mimer` `MimerProgram.buildCreateDDL`(`MimerProgramManager`) | 없으면 빈 암호 `USING ''`. 문서상 필수(클래스 Javadoc) | **고침**: 지어낸(빈) 암호와 같은 원인 |
| `ext.mimer` `MimerUserManager` | 선택 사항, 비면 `USING`을 쓰지 않음(OS 로그인만) | 해당 없음 |
| `ext.cubrid` `CubridUserManager` | 속성에 암호가 있을 때만 `PASSWORD` | 해당 없음 |
| `ext.postgresql` `PostgreRoleManager`, `ext.mssql` `SQLServerLoginManager` | 객체 값, 비면 문장에서 뺌. Swing에서는 보안 편집기가 만듦(swing-security.md) | 해당 없음 |
| `ext.oracle` `OracleSchemaManager` | 객체 값, 비면 뺌. 이름만 길에서 빠진 종류(`OracleSchema`) | 해당 없음 |
| `ext.db2` `DB2RoleManager`, `ext.exasol` `ExasolRoleManager` | 암호 없음 | 해당 없음 |
| `ext.exasol` `ExasolConnectionManager`(CONNECTION 개체) | 사용자와 암호가 둘 다 있을 때만 `USER … IDENTIFIED BY` | 해당 없음 |
| `ext.altibase` `AltibaseSQLDialect.getCreateSchemaQuery` | 암호 = 사용자 이름 | 남김: 부르는 곳이 `InternalDB.createSchemaIfNotExists`뿐이고 `InternalDB`의 하위 클래스가 없다(서버 제품용) |
| `ext.oracle` `OracleSQLDialect.getCreateSchemaQuery` | 임의 암호(`SecurityUtils.generatePassword`) | 남김: 같은 이유 |
| `ext.exasol` `ExasolUtils.getConnectionDdl` | `'<password>'` 자리 표시(CONNECTION의 DDL 보기) | 해당 없음(사용자 DDL이 아님) |

- **고친 것:**
  - **모델(Exasol):** `ExasolUserManager`는 암호를 지어내지 않는다(빈 값). LOCAL 사용자의 CREATE와 암호를 바꾸는 ALTER는 암호가 비면
    `DBException`("A password is required for the local user …")으로 문장을 만들지 않는다. 그래서 대화상자가 없는 경로(다른 호출자)도
    고정 암호의 사용자를 만들 수 없다.
    - 암호는 `"…"`로 감싸고 안의 `"`를 두 번 쓴다. `DBUtils.getQuotedIdentifier`는 이미 따옴표로 싸인 글을 그대로 두므로 쓰지 않았다.
    - 암호가 든 명령은 쿼리 관리자 기록을 끈다(`setDisableSessionLogging`, PostgreSQL 역할·SQL Server 로그인 관리자와 같음).
    - `ExasolUser.getPassword`를 `@Property(password = true, required = true)`로 선언했다.
  - **모델(Mimer):** `MimerProgram.getPassword`를 필수로 선언했다. `buildCreateDDL`은 빈 암호를 거절하고, `MimerProgramManager`는 CREATE의 기록을 끈다.
  - **Swing(`NewObjectDialogs`):** 이름 대화상자가 그 종류의 필수 암호 속성(`password = true, required = true`, 편집 가능)을 보면
    암호와 암호 확인을 `JPasswordField`로 받는다. 비었거나 서로 다르면 OK가 오류 줄을 보인다("Enter a password." / "The passwords do not match.").
    - 드라이버마다 대화상자를 두지 않고 모델의 선언을 본다. ui.swing이 ext.exasol에 의존하지 않고, 같은 선언을 한 다른 드라이버에도 적용된다.
    - 선택 사항인 암호(Mimer 사용자, Exasol CONNECTION 등)는 묻지 않는다. 관리자가 문장에서 빼므로 지어낸 값이 나가지 않는다.
    - 값은 속성 설명자(`ObjectPropertyDescriptor.writeValue`)로 넣는다.
  - **미리 보기에서 가리기(`ExplorerObjectCommands.Secrets`):** 스크립트를 만드는 동안 새 객체에는 `********`가 들어간다. 그래서 미리 보기·
    복사·쿼리 창으로 열기에는 실제 암호가 나가지 않는다(보안 편집기의 `SecurityStatement#displaySql`과 같은 방식). 실행을 고르면 저장 직전에
    실제 암호를 넣고(`reveal`), 저장이 끝나면 성공·실패와 관계없이 지운다(`clear`). 저장 경로는 실행 동작을 객체에서 다시 만들기 때문에 이렇게 할 수 있다.
- **확인 (2026-11-08):** Exasol 서버가 없다. 빌드한 제품 한 벌을 `CHROMITE_DATA`와 작업 공간을 scratch에 두고 영어(`-nl en`)와 한국어(`-nl ko`)로
  실행했고, 입력은 앱 안의 드라이버(attach 에이전트)로만 보냈다.
  - 시험 방법: 에이전트가 연결하지 않은 Exasol 데이터 소스를 만들었다(드라이버의 데이터 소스 설명자 + 연결 없는 `ExasolDataSource`, 등록하지 않음).
    그 위에서 실제 `NewObjectCommands.create`(이름 대화상자 → 객체 관리자 → 스크립트 미리 보기)를 실행했다.
  - 실제 관리자·대화상자 메서드를 직접 부르는 확인도 했다. 시험 암호는 실행할 때마다 임의로 만들었고 `"`를 하나 넣었다.

| 확인한 것 | 결과 |
|---|---|
| 대화상자 | 이름·암호·암호 확인(가림). 암호 없이 OK → "Enter a password." / "암호를 입력하십시오.". 다르게 입력 → "The passwords do not match." / "두 암호가 다릅니다." |
| 미리 보기 | `CREATE USER chr_g2_user IDENTIFIED  BY "********";`, 기본 단추 Cancel / 취소. 취소하면 아무것도 남지 않음 |
| 저장 때의 문장(`reveal`) | 임의 암호가 `"`를 두 번 쓴 형태로 들어감. 명령의 `isDisableSessionLogging` = true. 저장 뒤(`clear`) 객체의 암호는 빈 값 |
| 대화상자 없이 관리자로 만든 사용자 | `A password is required for the local user CHR_G2_NOASK`로 거절(문장 없음) |
| Mimer 프로그램 | 빈 암호 → `A password is required for the program ident CHR_G2_PROG`로 거절. 암호가 있으면 `CREATE IDENT "CHR_G2_PROG" AS PROGRAM USING '…'` |
| 필수 암호 속성 찾기 | ExasolUser·MimerProgram은 찾음. MimerUser·ExasolConnection(선택 사항)은 없음(이름만) |

  - 실제 Exasol·Mimer 서버에서 실행해 보지는 못했다.
  - 연결 없는 데이터 소스라 미리 보기 전에 드라이버 파일 다운로드 질문이 떴고 Cancel을 눌렀다. DDL 커밋 여부를 읽으려고 세션을 여는 단계다.
    서버가 있는 실제 연결에서는 나오지 않는다.

## 남긴 것

- **사전(B-E9 "사전", SWT `EditDictionaryPage`):** 가상 모델의 "설명 열"을 고르는 페이지다. Swing 결과 표에는 이를 쓰는 곳(외래 키 값
  옆에 설명 열 보여 주기)이 아직 없어 페이지만 만들면 효과가 없다. 그 기능과 함께 만든다.
- 테이블 자식 폴더의 New Index… 등은 디자이너를 열기만 한다. 디자이너의 추가 대화상자를 바로 띄우지는 않는다.
- 쿼리 창에서 실행하는 DDL(틀 포함)은 수동 커밋 세션의 열린 트랜잭션을 묻지 않는다. 사용자가 직접 쓴 DDL과 같은 실행이라 이 작업에서
  바꾸지 않았다. 쿼리 창 실행 전체의 규칙으로 따로 정한다.
- 공통 외래 키 대화상자의 규칙 목록을 드라이버별로 줄이기(지금은 Oracle이 받지 않는 규칙을 고르면 추가할 때 알림).
- PostgreSQL 데이터베이스의 `CONNECTION LIMIT`, `IS_TEMPLATE`, `ALLOW_CONNECTIONS` 등 SWT에도 없던 옵션.
- SQL Server 데이터베이스 파일(크기, 증가, 경로)과 파일 그룹(SSMS 새 데이터베이스의 파일 페이지).
- 새 사용자의 암호(위 "새 사용자의 암호")에서 범위 밖으로 남긴 것.
  - 암호 바꾸기의 따옴표(Exasol·Oracle·YashanDB·HANA), Mimer의 쿼리 관리자 기록, Exasol LDAP의 작은따옴표, Exasol New Connection…의
    NullPointerException, Swing 속성 표의 암호 가리기는 2026-11-08 고쳤다([password-handling.md](password-handling.md)).
    New Connection…은 연결 문자열이 필수라 이름만 길에서 뺐다(모델이 `required = true`로 선언).
  - "드라이버 파일 다운로드" 질문의 기본 단추가 Download다.

## 변경 이력

- 2026-11-08 최초 작성: 폴더의 New &lt;종류&gt;…, 새 데이터베이스(PostgreSQL·SQL Server·MySQL/MariaDB), PostgreSQL 스키마·테이블스페이스·확장,
  시퀀스·트리거·DDL 트리거·이벤트 트리거·정책·패키지·형식·큐 틀, Oracle 디자이너 인덱스·외래 키·CHECK, 디자이너 저장 전 트랜잭션 질문
  (`chromite/p4-create-objects`). 확인 중 고침: 디자이너에서 거절된 외래 키가 남던 것, MariaDB "New Sequences…" 이름, 틀이 없는
  드라이버의 시퀀스를 이름만 길에 둠, 소유자를 고르지 않은 PostgreSQL 새 스키마의 `AUTHORIZATION public`.
- 2026-11-08 새 사용자의 암호(`chromite/exasol-password-fix`): Exasol 새 사용자의 고정 암호 `"password"`를 없애고, 이름 대화상자가 모델의
  필수 암호 속성을 가려서 받음(미리 보기는 `********`, 실제 암호는 저장하는 동안만). 빈 암호의 Exasol 사용자·Mimer 프로그램 CREATE는 거절.
- 2026-11-08 묻지 않는 필수 값이 있는 종류를 이름만 길에서 뺌(Exasol New Connection…), "남긴 것"의 암호 항목을 고쳤다고 적음
  (`chromite/password-handling-fixes`, [password-handling.md](password-handling.md)).
