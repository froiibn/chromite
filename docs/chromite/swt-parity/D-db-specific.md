# D. DB별 SWT UI 번들 (`ext.*.ui`, 32개) → Swing 패리티 감사

- 범위: 커밋 `263b8ddb45`에서 지운 `com.seizia.chromite.ext.*.ui` 32개(`ext.postgresql.debug.ui` 포함).
- 비교 대상: 작업 트리(HEAD `8e8aaaef60`)의 `plugins/com.seizia.chromite.ui.swing`, `plugins/com.seizia.chromite.model.admin`, 남은 `ext.*` 모델 번들,
  `docs/chromite/swing-*.md`, `altibase.md`, `swt-removal.md`.
- 방법: SWT 쪽은 `git show 263b8ddb45:<bundle>/plugin.xml`(확장 지점별 기여)과 각 Java 클래스의 실제 load/save 코드를 읽었다.
  Swing 쪽은 같은 기능의 클래스·문서를 찾고, 못 찾으면 grep 결과를 근거로 적었다. 앱은 실행하지 않았고 저장소는 고치지 않았다(읽기 전용).
- 연결 설정은 스크립트로 SWT 연결 화면이 쓰는 `setProviderProperty`/`setProperty`/`setAuthProperty` 키를 모아, 모델 번들 `plugin.xml`의
  `<provider-properties>`에 선언되어 있는지 대조했다. Swing의 Provider Properties 표는 선언된 속성만 보여 준다
  (`ConnectionSettingsPanel.java:220-230`, 저장은 선언된 키만 고침 `:398-409` — 선언되지 않은 키는 지우지 않고 그대로 둔다).

상태: **있음** / **일부** / **없음** / **의도적 제외** / **조용히 끊김**(모델은 남았는데 UI 고리가 없어 아무 일도 안 하거나 더 나쁜 동작).
영향: 높음(일상 DB 작업, 데이터 안전, 잠금·트랜잭션) / 중간 / 낮음.

## 요약

| 구역 | 행 | 있음 | 일부 | 없음 | 의도적 제외 | 조용히 끊김 |
|---|---|---|---|---|---|---|
| PostgreSQL (`ext.postgresql.ui`, `ext.postgresql.debug.ui`) | 29 | 13 | 7 | 9 | 0 | 0 |
| SQL Server (`ext.mssql.ui`) | 14 | 10 | 1 | 3 | 0 | 0 |
| MySQL/MariaDB (`ext.mysql.ui`) | 19 | 10 | 4 | 5 | 0 | 0 |
| Oracle (`ext.oracle.ui`) | 24 | 11 | 3 | 8 | 1 | 1 |
| Altibase (`ext.altibase.ui`) | 9 | 3 | 0 | 5 | 0 | 1 |
| 나머지 26개 번들 (+ 끊김 3행) | 29 | 2 | 16 | 8 | 0 | 3 |
| **합계** | **124** | **49** | **31** | **38** | **1** | **5** |

2026-11-08 3순위 Oracle·Altibase 관리(`chromite/p3-oracle-altibase-admin`): P23·S14·M17·O5·O16·O21·A2 없음 → 있음.

### 조용히 끊김 (먼저 볼 것)

| # | 기능 | 무엇이 끊겼나 | 근거 | 영향 |
|---|---|---|---|---|
| K1 | Oracle DBMS_OUTPUT | 모델이 모든 실행 컨텍스트에서 `DBMS_OUTPUT.ENABLE(1000000)`을 켠다(설정 기본값 켬). 출력을 읽는 쪽(`readServerOutput`, SWT SQL 편집기의 Output 패널)이 Swing에 없어 `PUT_LINE` 출력이 보이지 않는다. Oracle 문서상 `GET_LINE(S)`를 한 번도 부르지 않으면 줄이 계속 쌓이므로, 한 세션에서 출력이 100만 바이트를 넘으면 사용자 PL/SQL이 `ORU-10027: buffer overflow`로 실패할 수 있다(추론, 재현하지 않음) | `ext.oracle/.../OracleDataSource.java:335-342`(켜기), `:982-997`(1,000,000), `OracleConstants.java:210`, `ext.oracle/.../internal/OraclePreferencesInitializer.java:40`(기본 켬). `readServerOutput`·`DBCServerOutputReader` 호출: `ui.swing`, `model.sql`, `model.ai*`에서 grep 0건. **2026-11-07 고침:** 쿼리 창이 문장마다 읽어 비움([swing-server-output.md](../swing-server-output.md)) | 높음 |
| K2 | Altibase 서버 출력(PRINT/PRINTLN) | 모든 컨텍스트에 메시지 콜백을 등록하고 받은 글을 `StringBuilder`에 붙인다. 비우는 곳은 `readServerOutput`뿐인데 Swing이 부르지 않아, 출력이 보이지 않고 데이터 원본이 살아 있는 동안 메모리에 계속 쌓인다 | `ext.altibase/.../AltibaseDataSource.java:124-127`, `:728`, `:780`(append), `:817-819`(읽을 때만 비움), `AltibasePreferencesInitializer.java:36`(기본 켬). **2026-11-07 고침:** 쿼리 창이 문장마다 읽어 비움. 실행해 보니 기본값 초기화가 한 번도 실행되지 않아(번들에 활성화 코드가 없음) 설정이 꺼짐으로 읽혔다. `AltibaseActivator`를 더하고, 꺼져 있으면 콜백을 등록하지 않게 함([swing-server-output.md](../swing-server-output.md)) | 중간 |
| K3 | CUBRID DBMS_OUTPUT | Oracle과 같은 구조. 연결마다 `dbms_output.enable(10000)`(기본 버퍼 1만 바이트)을 켜고 읽지 않는다. 버퍼가 작아 넘침 가능성이 Oracle보다 크다(추론) | `ext.cubrid/.../CubridDataSource.java:333`, `:513-528`, `CubridPreferencesInitializer.java:35-36`. **2026-11-07 고침(코드 경로만, CUBRID 서버로 실행하지 않음):** 쿼리 창이 문장마다 읽어 비움 | 낮음(사용자 적음) |
| K4 | H2 초기화 스크립트 확인 | URL의 `INIT=`·연결 직후 SQL을 실행하기 전에 묻는 서비스(`UIServiceConnectionInit`, SWT `core`의 `UIServiceConnectionInitImpl`)가 Swing에 없다. 서비스가 없으면 "승인 안 됨"으로 연결을 거부한다(안전한 쪽으로 실패). 기능은 못 쓴다 | `ext.h2/.../H2DataSource.java:97`, `:112`, `H2InitScriptValidator.java:48-49`. Swing `plugin.xml`의 service 등록은 3개뿐(`:21-26`) | 낮음 |
| K5 | SQLite 확장 불러오기 승인 | 확장 불러오기를 승인하는 서비스(`SQLiteExtensionConfirmationImpl`, `ext.sqlite.ui`의 `com.seizia.chromite.service`)가 없어 승인이 늘 거부된다(안전한 쪽으로 실패). 오류 결과의 "확장 설정" 동작(`resultset.error`)도 없다 | `ext.sqlite/.../SQLiteDataSource.java:178-179` | 낮음 |

K1–K3의 공통 원인은 SQL 편집기에 서버 출력 패널이 없는 것이다([swing-frontend.md](../swing-frontend.md) 708-709행 "서버 출력 읽기"를 C단계 남긴 일로 기록). 편집기 영역 감사와 겹친다.
2026-11-07 #105에서 고쳤다(쿼리 창의 Messages 탭, [swing-server-output.md](../swing-server-output.md)).

## 1. PostgreSQL (`ext.postgresql.ui`, 57 클래스 / `ext.postgresql.debug.ui`)

| # | 기능 | SWT 출처 | 상태 | 근거 (Swing) | 영향 | 비고 |
|---|---|---|---|---|---|---|
| P1 | 연결 주 양식(호스트·포트·DB·URL) | `PostgreConnectionPage` | 있음 | `ConnectionSettingsPanel`(범용 양식) | 높음 | |
| P2 | 연결 고급: 모든 DB 보기(show-non-default-db), 템플릿 DB, 사용 불가 DB, 모든 데이터 형식 읽기, 키를 열과 함께 읽기, 옛 시간대 바꾸기, `$$` 문자열 처리 2개, 세션 역할 | `PostgreConnectionPage`, `PostgreConnectionPageAdvanced` | 있음 | 모두 `ext.postgresql/plugin.xml` `<provider-properties>`에 선언 → Provider Properties 표(`ConnectionSettingsPanel.java:220`) | 중간 | 주 양식 확인란이 아니라 Options의 표 한 줄이다 |
| P3 | 연결 고급: DB 통계 보기(show-database-statistics), Prepared statement 사용(PgBouncer용) | `PostgreConnectionPageAdvanced:288-302` | 없음 | 두 키가 `<provider-properties>`에 없음. 모델은 그대로 읽음(`PostgreDataSource.java:99-110`, `:300-306`) | 중간 | 저장된 값(SWT 시절 데이터는 이름 바꿔 복사됨)은 계속 적용되지만 보거나 바꿀 수 없다. PgBouncer 서버 종류는 prepared statement를 켤 방법이 없다 |
| P4 | PgPass 인증 + pgpass 호스트 이름 바꾸기 | `PostgreAuthPgPassConfigurator` | 있음 | `ConnectionAuthExtras`([swing-conn-advanced.md](../swing-conn-advanced.md) 21, 69행) | 낮음 | |
| P5 | SSL(인증서·키·모드·factory·프록시 서비스) | `PostgreSSLConfigurator` | 일부 | `SslHandlerPanel`(swing-conn-advanced.md 136행) | 낮음 | "Use proxy service"(`sslProxyServer`, 모델 `PostgreDataSource.java:287`) 칸 없음, SSL factory는 목록 대신 직접 입력 |
| P6 | 로컬 클라이언트(pg_dump 위치) 선택 | `PostgreConnectionPageAdvanced`의 `ClientHomesSelector` | 있음 | `NativeBackupRestoreDialog.loadClients`·`browseClient`(swing-backup-restore.md 37행 표), 2026-11-08 연결 편집기 연결 속성의 로컬 클라이언트([swing-database-tasks.md](../swing-database-tasks.md)) | 낮음 | 연결 화면(공통 탭)과 백업 대화상자 둘 다에서 고른다 |
| P7 | 전역 환경설정 "PostgreSQL" 페이지(위 P2·P3 항목의 전역 기본값) | `PrefPagePostgreSQL`, `PostgrePreferencesInitializer` | 없음 | `OptionsDialog.java:178-180`(일반·편집기·색 탭뿐) | 낮음 | 연결마다 표로 바꿀 수는 있다 |
| P8 | 세션 관리자(유휴 보기, Terminate, Cancel query) | `PostgreSessionEditor` | 있음 | `SessionManagerPanel` + `PostgreActivitySessionManager`(swing-admin-tools.md 124행) | 높음 | Swing이 대기·막는 세션·트랜잭션 경과를 더함 |
| P9 | 잠금 관리자 | `PostgreLockEditor` | 있음 | `LockManagerPanel` + `PostgreBlockingLockManager`(swing-admin-tools.md 153행) | 높음 | |
| P10 | 사용 권한 탭(역할의 권한, 테이블·열·함수·스키마의 권한) | `RolePermissionsEditor`, `ObjectPermissionsEditor`, `PostgresPermissionsEditor` | 일부 | `PrincipalPropertiesDialog`, `ObjectPermissionsDialog`, `PostgreSecurityDialect`(DATABASE·SCHEMA 포함) | 중간 | 열 단위 권한, ALTER DEFAULT PRIVILEGES 없음(swing-security.md 158행) |
| P11 | 역할 만들기·속성 | `PostgreCreateRoleDialog`, `PostgreRoleConfigurator` | 있음 | `PrincipalPropertiesDialog`(swing-security.md 63행 표) | 중간 | |
| P12 | Source/DDL 탭(뷰·함수·트리거·규칙 등 정의 보기·제자리 편집 저장) | `PostgreSourceViewEditor` | 일부 | View DDL(`DdlViewerPanel`), Modify → 쿼리 창 `CREATE OR REPLACE`(swing-source-editor.md 7, 53행) | 중간 | 제자리 편집·저장 없음, 쿼리 창에서 실행 |
| P13 | 새 데이터베이스(소유자·템플릿·인코딩·테이블스페이스) | `PostgreCreateDatabaseDialog`, `PostgreDatabaseConfigurator` | 없음 | Object Explorer 메뉴의 새로 만들기는 New Table/View/Procedure/Function/로그인·역할뿐(`ObjectExplorerPanel.java:596-606`, `SourceEditMessages.properties`) | 중간 | |
| P14 | 새 스키마·테이블스페이스·확장, 확장 제거 | `PostgreCreateSchemaDialog`, `PostgreCreateTablespaceDialog`, `PostgreCreateExtensionDialog`, 도구 `PostgreToolExtensionUninstall` | 없음 | 위와 같음 | 중간 | |
| P15 | 시퀀스·트리거·이벤트 트리거·행 보안 정책 만들기 | `PostgreSequenceConfigurator`, `PostgreTriggerConfigurator`(+`PostgreTriggerEditPage`), `PostgreEventTriggerConfigurator`, `PropertyObjectConfigurator`(Policy) | 없음 | 위와 같음. 속성 창은 읽기 전용(`PropertiesPanel.java:68`) | 중간 | |
| P16 | 인덱스·제약·외래 키 만들기 | `PostgreIndexConfigurator`, `PostgreConstraintConfigurator`, `PostgreForeignKeyConfigurator` | 있음 | `TableDesignerPanel` + `design/PostgreTableDesignerDriver`(swing-table-designer.md 58행) | 중간 | |
| P17 | 함수·프로시저 만들기 | `PostgreProcedureConfigurator` | 있음 | `SourceEditCommands` New Function/Procedure(`SourceScripts`) | 중간 | |
| P18 | Backup(pg_dump) | 도구 `PostgreToolBackup`, `PostgreBackupWizard*` | 일부 | `NativeBackupRestoreDialog` | 중간 | 스키마·테이블 고르기, 도구 사용자·추가 인수 없음(swing-backup-restore.md 200행) |
| P19 | Global Backup(pg_dumpall) | 도구 `PostgreToolBackupAll`, `PostgreBackupAllWizard*` | 없음 | swing-backup-restore.md 200행 "전체 클러스터 백업(pg_dumpall)" 남긴 것 | 중간 | 역할·테이블스페이스 백업 수단이 없다 |
| P20 | Restore(pg_restore/psql) | 도구 `PostgreToolRestore`, `PostgreRestoreWizard` | 있음 | `NativeBackupRestoreDialog`(+ 첫 오류에서 멈춤) | 중간 | |
| P21 | psql로 스크립트 실행(도구) + SQL 편집기 "네이티브 클라이언트로 실행"(`sql.executors`) | `PostgreScriptExecuteWizard`, `PostgreScriptExecutor` | 있음(2026-11-08) | Tasks &gt; Run Script with Native Client…, Query &gt; Run with Native Client…, 작업 `pgScriptExecute` 저장·실행([swing-database-tasks.md](../swing-database-tasks.md)). 조사 당시: 일부 | 낮음 | 조사 당시 근거: 일반 형식 복원이 psql로 파일을 실행함. 편집기 연동은 grep `ScriptExecute`·`sql.executors` → `ui.swing` 0건(MySQL 복원용 1건 제외) |
| P22 | 유지 관리: ANALYZE, VACUUM | 도구 `PostgreToolAnalyze`, `PostgreToolVacuum` | 있음 | `MaintenanceDialog` + `PostgreMaintenanceDialect`(swing-admin-tools.md 368행) | 중간 | Swing이 REINDEX·CLUSTER·부풀림 추정을 더함 |
| P23 | TRUNCATE, 트리거 사용/사용 안 함, 구체화된 뷰 새로 고침 | 도구 `PostgreToolTruncate`, `PostgreToolTriggerEnable/Disable`, `PostgreToolRefreshMView` | 있음(2026-11-08) | Object Explorer 메뉴, `ObjectMaintenanceCommands` + `ObjectMaintenanceActions`(모델 도구의 SQL, swing-explorer-object-actions.md "유지 관리 명령") | 중간 | 조사 당시: 없음. 도구 옵션(ONLY, RESTART IDENTITY, CASCADE, CONCURRENTLY) 화면은 없음 |
| P24 | 외부 데이터 래퍼(FDW) 구성 마법사 | `PostgreFDWConfigWizard*`, 명령 `ext.postgresql.ui.fdw` | 없음 | grep `FDW`·`ForeignDataWrapper` → 0건 | 낮음 | |
| P25 | pgAgent 작업·일정 편집(분·시·요일 표) | `PostgreScheduleEditor`(+ 범용 편집기) | 일부 | 탐색기에 pgAgent 폴더는 모델 트리로 보임. 만들기·편집 없음. pg_cron은 Swing 새 기능(swing-job-schedulers.md 13, 75행) | 낮음 | |
| P26 | EXPLAIN 옵션(ANALYZE, VERBOSE, COSTS, BUFFERS, SETTINGS, WAL, TIMING, SUMMARY) | `PostgreExplainPlanConfigurator` | 일부 | `SqlPlanExplainer.java:123-126`(실제 계획 = ANALYZE만) | 낮음 | BUFFERS 등 진단 옵션 없음 |
| P27 | plpgsql_check로 함수 검사(SQL 생성기, 편집기 명령) | `SQLGeneratorProcedureCheck`, `SQLEditorHandlerCheckProcedureConsole` | 없음 | grep `plpgsql_check` → 0건 | 낮음 | |
| P28 | 값 편집기: ENUM 콤보, hstore(구조), money, bit string | `dataManager`: `PostgreEnumValueManager` 등 | 없음 | 결과 표 편집은 글자 칸. grep `ValueManager`·`getEnumValues` → `ResultSet*` 0건 | 낮음 | 글자로는 입력 가능 |
| P29 | PL/pgSQL 디버거(중단점, 단계 실행) | `ext.postgresql.debug.ui`(+ 모델 `debug.core`, `ext.postgresql.debug.core`도 함께 삭제) | 있음(2026-11-07) | 모델 복원, Swing 디버거 탭([procedure-debugger.md](../procedure-debugger.md)) | 중간 | Step Out은 pldbgapi에 없음 |

## 2. SQL Server (`ext.mssql.ui`, 16 클래스)

| # | 기능 | SWT 출처 | 상태 | 근거 (Swing) | 영향 | 비고 |
|---|---|---|---|---|---|---|
| S1 | 인증 종류(SQL Server / Windows / NTLM / Kerberos / AD 계열) + NTLM integrated security | `SQLServerConnectionPage`(인증 패널), `SQLServerAuthConfigurator` | 있음 | `ConnectionAuthModels`, `ConnectionAuthExtras`(swing-conn-advanced.md 67행, swing-frontend.md 1424-1425행 표) | 높음 | |
| S2 | Trust server certificate | `SQLServerConnectionPage:173` | 있음 | `ConnectionDriverFields`(swing-conn-advanced.md 153행) | 높음 | Swing이 Encrypt(Optional/Mandatory/Strict)를 주 양식에 더함 |
| S3 | Show all schemas | `SQLServerConnectionPage:170` | 있음 | `<provider-properties>` 선언 → 표 | 낮음 | |
| S4 | Azure·Babelfish "Show all databases"(`show-all-databases-azure`) | `SQLServerConnectionPage:163` | 없음 | 키가 `<provider-properties>`에 없음. 모델 `SQLServerDataSource.java:759-763`은 값이 없으면 Azure·Babelfish에서 현재 DB만 보임 | 중간 | Azure SQL 사용자는 다른 DB를 탐색기에서 볼 수 없다(저장된 값은 동작) |
| S5 | Sybase/jTDS "Encrypt password" | `SQLServerConnectionPage:160` | 없음 | 키 미선언. 모델 `SQLServerGenericDataSource.java:91` | 낮음 | |
| S6 | SSL(키 저장소, 비밀번호, 인증서 호스트 이름) | `SQLServerSSLConfigurator` | 있음 | `SslHandlerPanel`(swing-conn-advanced.md 138행) | 중간 | |
| S7 | 세션 관리자(Only connections, Kill) | `SQLServerSessionEditor` | 있음 | `SessionManagerPanel`(swing-admin-tools.md 125행) | 높음 | Swing이 잠금 탭을 새로 더함(SWT에 없었음) |
| S8 | Source / Definition / DDL 탭 | `SQLServerSourceViewEditor` | 일부 | View DDL, Modify → 쿼리 창(swing-source-editor.md 53행) | 중간 | 제자리 편집 없음 |
| S9 | 새 데이터베이스 | `SQLServerCreateDatabaseDialog`, `SQLServerDatabaseConfigurator` | 없음 | 새로 만들기 메뉴에 없음(P13과 같은 근거) | 중간 | |
| S10 | 새 로그인 | `SQLServerLoginConfigurator`(이름·암호) | 있음 | 로그인 - 신규(`PrincipalPropertiesDialog`, swing-security.md 75행) | 중간 | Swing이 더 넓음(서버 역할, 사용자 매핑) |
| S11 | 열·고유 키·외래 키·인덱스·CHECK 제약 | `SQLServer{Column,UniqueKey,ForeignKey,Index,CheckConstraint}Configurator` | 있음 | `design/SQLServerTableDesignerDriver`(CHECK 포함, `:43-66`) | 중간 | |
| S12 | 프로시저 만들기(SQL Server, Sybase generic) | `SQLServerProcedureConfigurator`, `SQLServerGenericProcedureConfigurator` | 있음 | New Procedure/Function(`SourceScripts`, SQL Server 템플릿) | 중간 | Sybase generic 템플릿 없음 |
| S13 | 도구: 인덱스 다시 작성 | `SQLServerToolRebuild`(`mssqlToolTableRebuild`) | 있음 | `SQLServerMaintenanceDialect`(swing-admin-tools.md 368-370행) | 중간 | Swing이 조각화 목록·통계·CHECKDB를 더함 |
| S14 | 도구: 트리거 사용/사용 안 함 | `SQLServerToolTriggerEnable/Disable` | 있음(2026-11-08) | P23과 같음 | 낮음 | 조사 당시: 없음 |

## 3. MySQL / MariaDB (`ext.mysql.ui`, 34 클래스)

| # | 기능 | SWT 출처 | 상태 | 근거 (Swing) | 영향 | 비고 |
|---|---|---|---|---|---|---|
| M1 | 연결: 모든 DB 보기, 서버 시간대 | `MySQLConnectionPage`, `MySQLConnectionPageAdvanced` | 있음 | `@chromite-show-all-dbs@`, `@chromite-serverTimezone@` 모두 선언 → 표 | 중간 | 시간대는 SWT의 시간대 목록 콤보 대신 글자 입력 |
| M2 | 로컬 클라이언트(mysqldump 위치) | `MySQLConnectionPageAdvanced`의 `ClientHomesSelector` | 있음 | `NativeBackupRestoreDialog` | 낮음 | |
| M3 | SSL(인증서, 암호 모음, Require SSL, 서버 인증서 확인, 공개 키 가져오기) | `MySQLSSLConfigurator` | 있음 | `SslHandlerPanel`(swing-conn-advanced.md 137행) | 중간 | |
| M4 | 세션 관리자(Hide sleeping, 성능 정보, Kill connection/query) | `MySQLSessionEditor` | 있음 | swing-admin-tools.md 126행 | 높음 | Swing이 InnoDB 잠금 탭을 더함 |
| M5 | 사용자 편집기(General·Privileges), 사용자 관리자, GRANT | `MySQLUserEditorGeneral/Privileges`, `MySQLUserManager`(UI 번들의 objectManager), `MySQLCommandGrantPrivilege` | 일부 | `PrincipalPropertiesDialog` + `MySQLSecurityDialect`(swing-security.md 76-80행) | 중간 | SWT General 탭의 리소스 제한(MAX_QUERIES/UPDATES/CONNECTIONS, `MySQLUserEditorGeneral.java:103-107`) 없음(swing-security.md 157행) |
| M6 | 새 데이터베이스(문자 집합, 콜레이션) | `MySQLCreateDatabaseDialog`, `MySQLCatalogConfigurator` | 없음 | 새로 만들기 메뉴에 없음 | 중간 | |
| M7 | 인덱스·제약·외래 키 | `MySQL{Index,Constraint,ForeignKey}Configurator` | 있음 | `design/MySQLTableDesignerDriver` | 중간 | |
| M8 | 프로시저·함수 만들기 | `MySQLProcedureConfigurator` | 있음 | New Procedure/Function | 중간 | |
| M9 | 트리거 만들기 | `MySQLTriggerConfigurator` | 없음 | 새로 만들기 메뉴에 없음 | 중간 | |
| M10 | 이벤트 만들기 | `MySQLEventConfigurator` | 있음 | `MySQLEventDialog`(swing-job-schedulers.md 14행) | 낮음 | Swing이 사용/사용 안 함·스케줄러 상태를 더함 |
| M11 | 시퀀스 만들기(MariaDB) | `MySQLSequenceConfigurator` | 없음 | 새로 만들기 메뉴에 없음 | 낮음 | |
| M12 | Source 탭, 패키지 본문(MariaDB Oracle 모드) | `MySQLSourceViewEditor`, `MySQLPackageBodyEditor` | 일부 | View DDL, Modify(swing-source-editor.md 53행) | 낮음 | |
| M13 | 내보내기(mysqldump) | 도구 `MySQLToolExport`, `MySQLExportWizard*` | 일부 | `NativeBackupRestoreDialog` | 중간 | 테이블 고르기 없음(swing-backup-restore.md 200행) |
| M14 | 가져오기(mysql로 덤프 복원) | 도구 `MySQLToolImport` | 있음 | `NativeBackupRestoreDialog` | 중간 | |
| M15 | mysql로 스크립트 실행 + 편집기 네이티브 실행(`sql.executors`) | `MySQLScriptExecuteWizard`, `MySQLScriptExecutor` | 있음(2026-11-08) | 같은 메뉴, 작업 `mysqlScriptExecute`(MariaDB 11.8 클라이언트로 확인, [swing-database-tasks.md](../swing-database-tasks.md)). 조사 당시: 일부 | 낮음 | 조사 당시 근거: 복원이 `MySQLScriptExecuteHandler`로 파일 실행(`NativeBackupRestoreDialog.java:29-30, 516`). 편집기 연동 없음 |
| M16 | 테이블 유지 관리: ANALYZE, CHECK, OPTIMIZE, REPAIR | `MySQLToolAnalyse/Check/Optimize/Repair` | 있음 | `MySQLMaintenanceDialect`(swing-admin-tools.md 368행) | 중간 | |
| M17 | TRUNCATE | `MySQLToolTruncate` | 있음(2026-11-08) | P23과 같음(MariaDB 확인) | 중간 | 조사 당시: 없음. "외래 키 검사 끄기" 옵션 화면은 없음 |
| M18 | ENUM 값 편집기 | `MySQLEnumValueManager` | 없음 | P28과 같음 | 낮음 | |
| M19 | 스크립트의 `source`(다른 파일 포함) 명령 | `sqlCommand` `mysql.source` → `SQLCommandInclude`(`ui.editors.sql`) | 없음 | 모델 명령 등록에 include 없음, grep `SQLCommandInclude` → 0건. 알 수 없는 명령은 오류(`SQLQueryExecuteJob.java:454-475`) | 낮음 | |

## 4. Oracle (`ext.oracle.ui`, 42 클래스)

| # | 기능 | SWT 출처 | 상태 | 근거 (Swing) | 영향 | 비고 |
|---|---|---|---|---|---|---|
| O1 | 연결 방식 Basic/TNS, SID/Service, TNS 폴더, 네트워크 별칭 | `OracleConnectionPage` | 있음 | `ConnectionDriverFields`(swing-conn-advanced.md 158-164행) | 높음 | |
| O2 | Custom(JDBC URL 직접) | `OracleConnectionPage` | 있음 | URL 방식으로 통합(같은 문서 160행) | 중간 | |
| O3 | 인증: OS 인증, Role(SYSDBA/SYSOPER) | `OracleAuthOSConfigurator`(칸 없음), `OracleAuthDatabaseNativeConfigurator` | 있음 | `ConnectionAuthExtras`(swing-conn-advanced.md 68행, swing-frontend.md 1427행) | 높음 | |
| O4 | Oracle 클라이언트(OCI) 홈 선택 | `OracleConnectionPage.createClientHomeGroup` | 의도적 제외 | swing-conn-advanced.md 165행. SWT도 `PRODUCT_FEATURE_ADVANCED_DATABASE_ADMINISTRATION`(상용)일 때만 보임(`OracleConnectionPage.java:112-114`) | 낮음 | |
| O5 | 세션 NLS: 언어, 지역, 날짜·타임스탬프 형식, 길이 의미(BYTE/CHAR), 통화 | `OracleConnectionExtraPage:103-122` | 있음(2026-11-08) | 연결 대화상자 Options의 세션 설정 탭(`ConnectionDriverFields` Oracle, swing-conn-advanced.md "Oracle 세션 설정") | 중간 | 조사 당시: 없음(6개 키가 `<provider-properties>`에 없어 저장값이 보이지 않게 적용됨) |
| O6 | 메타데이터 옵션: 옵티마이저 힌트, RULE 힌트, 단순 제약 질의, 다른 테이블 질의, DBA 뷰 쓰기·항상 DBA 보기, SYS 스키마, 빈 스키마 숨김, 한 스키마만, DATE를 날짜로, 동의어에서 찾기, OS 사용자 | `OracleConnectionExtraPage` | 있음 | 모두 선언(`ext.oracle/plugin.xml` provider-properties) → 표 | 낮음 | |
| O7 | 메타데이터 옵션: 열 주석 읽기, 시퀀스에서 찾기, 옵티마이저 버전 | `OracleConnectionExtraPage:138-171` | 없음 | 키 미선언. 모델 `OracleSchema.java:151`, `:813`, `OracleDataSource.java:396` | 낮음 | |
| O8 | 전역·연결별 "Oracle" 환경설정(PLAN_TABLE 이름, ROWID 사용, DBMS_OUTPUT 켜기, 모든 동의어 읽기, 스크립트 escape 처리 끄기) | `PrefPageOracle`(preferencePages + propertyPages) | 없음 | `OptionsDialog.java:178-180` | 낮음 | 기본값은 모델 쪽 `OraclePreferencesInitializer`가 그대로 넣는다(UI 번들의 같은 초기화가 지워져도 값 같음). K1을 끌 방법도 없다 |
| O9 | 세션 관리자(Kill, Disconnect, 백그라운드·비활성 보기) | `OracleSessionEditor` | 있음 | `OracleActivitySessionManager`(swing-admin-tools.md 127행) | 높음 | |
| O10 | 잠금 관리자 | `OracleLockEditor` | 있음 | `OracleBlockingLockManager`(swing-admin-tools.md 154행) | 높음 | |
| O11 | Compile 명령(탐색기에서 고른 PL/SQL 객체 컴파일, 오류 목록, 편집기의 오류 줄로 이동) | 명령 `ext.oracle.code.compile` → `CompileHandler`, `OracleCompilerDialog` | 일부 | Maintenance 대화상자의 무효 객체 `ALTER … COMPILE`·스키마 COMPILE_SCHEMA + `ALL_ERRORS` 표(swing-admin-tools.md 370행). 하지만 `OracleMaintenanceDialect.getTarget`(`:131-143`)은 테이블·인덱스·스키마만 받아 프로시저·패키지 노드에서는 열 수 없음 | 중간 | 유효한 객체를 다시 컴파일하거나 노드에서 바로 컴파일 불가 |
| O12 | PL/SQL 소스 편집기(패키지 선언·본문, 프로시저)와 저장 뒤 컴파일 오류 표시 | `OracleSourceDeclarationEditor`, `OracleSourceDefinitionEditor`, `OracleObjectDeclarationViewer`, (모델 쪽 `OracleObjectValidateAction`) | 일부 | Modify → 쿼리 창(swing-source-editor.md 142행). 오류는 드라이버 경고만(같은 문서 157행) | 중간 | 객체가 INVALID로 저장되어도 오류 줄·위치가 안 보인다 |
| O13 | 패키지 안 프로시저 → 소스 위치로 이동 | 명령 `ext.oracle.code.package.navigate` → `PackageNavigateHandler` | 없음 | grep `navigate` 관련 명령 없음 | 낮음 | |
| O14 | DDL 탭의 DDL 형식(FULL / 저장소 제외 / 간단히) | `OracleObjectDDLEditor`, `OracleEditorUtils.addDDLControl` | 일부 | View DDL은 있으나 형식 콤보 없음(`OracleDDLFormat` 참조 grep → `ui.swing` 0건) | 낮음 | |
| O15 | 스케줄러 작업: 실행, 작업 동작 편집, 작업 로그 | 명령 `ext.oracle.job.run` → `JobRunHandler`, `SchedulerJobActionEditor`, `SchedulerJobLogEditor` | 있음 | `SchedulerCommands`, `OracleJobDialog`, 기록 탭(swing-job-schedulers.md 12, 23-25행) | 중간 | Swing이 더 넓음 |
| O16 | 사용자(스키마) 만들기, 역할·권한 부여 | `OracleSchemaConfigurator`(+ 범용 편집기의 권한 탭) | 있음(2026-11-08) | `OracleSecurityDialect` + 보안 창(swing-security.md "Oracle"): 사용자·역할 만들기·속성·삭제, 역할 소속, 시스템·객체 권한 | 높음 | 조사 당시: 없음. 패키지·열 권한, 테이블스페이스·할당량·프로필은 없음 |
| O17 | 시퀀스·큐·데이터 형식·패키지·테이블 트리거 만들기 | `Oracle{Sequence,Queue,DataType,Package,TableTrigger}Configurator` | 없음 | 새로 만들기는 View/Procedure/Function뿐(swing-source-editor.md 21행) | 중간 | |
| O18 | 인덱스·제약·외래 키 만들기 | `Oracle{Index,Constraint,ForeignKey}Configurator` | 없음 | 테이블 디자이너는 Oracle 열만 되고 인덱스·FK·CHECK는 드라이버 구현 없음(swing-table-designer.md 74, 124행) | 중간 | |
| O19 | 통계 수집(테이블·인덱스) | `OracleToolGatherStatistics` | 있음 | `OracleMaintenanceDialect` GATHER_TABLE/INDEX/SCHEMA_STATS | 중간 | |
| O20 | 구조 검증(VALIDATE STRUCTURE) | `OracleToolValidateStructure` | 없음 | swing-admin-tools.md 600행 "미룬 것" | 낮음 | |
| O21 | TRUNCATE, 트리거 사용/사용 안 함, 구체화된 뷰 새로 고침 | `OracleToolTruncate`, `OracleToolTriggerEnable/Disable`, `OracleToolRefreshMView` | 있음(2026-11-08) | P23과 같음 | 중간 | 조사 당시: 없음. REUSE STORAGE·새로 고침 방식 옵션 화면은 없음 |
| O22 | SQL*Plus로 스크립트 실행(도구 + 편집기 네이티브 실행) | `OracleToolScript`, `OracleScriptExecutor`(`sql.executors`) | 있음(2026-11-08) | 같은 메뉴(Oracle 스키마·연결), 작업 `oracleScriptExecute`. 암호를 명령줄에서 빼고 표준 입력 `CONNECT`로, 스키마를 현재 스키마로. Instant Client가 없어 명령 만들기만 확인([swing-database-tasks.md](../swing-database-tasks.md)). 조사 당시: 없음 | 낮음 | 조사 당시 근거: grep `sqlplus` → `ui.swing` 0건. 백업·복원도 Oracle은 대상 아님(swing-backup-restore.md 10행) |
| O23 | Toad·SQL Developer 연결 가져오기 | `importWizards`: `ConfigImportWizardToad`, `ConfigImportWizardSqlDeveloper` | 없음 | swt-removal.md 115행(다른 도구의 연결 가져오기 없음) | 중간 | Oracle 사용자 이전 경로 |
| O24 | DBMS_OUTPUT 출력 | (SQL 편집기 Output 패널 + `PREF_DBMS_OUTPUT`) | 있음(2026-11-07, 조사 때 조용히 끊김) | 위 K1. 쿼리 창 Messages 탭 | 높음 | 켜고 끄는 화면은 없음(O8) |

## 5. Altibase (`ext.altibase.ui`, 8 클래스)

SWT에도 Altibase 전용 연결 화면은 없었다(`dataSourceConfigurator` 기여 없음). 연결은 범용 화면 그대로다.

| # | 기능 | SWT 출처 | 상태 | 근거 (Swing) | 영향 | 비고 |
|---|---|---|---|---|---|---|
| A1 | 세션 관리자(Disconnect) | `AltibaseSessionEditor` | 있음 | Activity Monitor의 "그 밖" 분기가 `AltibaseServerSessionManager` 어댑터 + Terminate(`ServerAdminProfile.java:253-260`) | 높음 | |
| A2 | 잠금 관리자 | `AltibaseLockEditor` | 있음(2026-11-08) | `ServerAdminProfile` Altibase 분기 + `AltibaseBlockingLockManager`(swing-admin-tools.md "Altibase 잠금") | 높음 | 조사 당시: 없음(모델 `AltibaseLockManager`가 연결되지 않음). Exasol은 남음 |
| A3 | 환경설정(계획 형식, DBMS 출력, 계획 깊이 접두어) | `PrefPageAltibase` | 없음 | `OptionsDialog.java:178-180` | 낮음 | 기본값은 모델 `AltibasePreferencesInitializer` |
| A4 | 서버 출력(PRINT) | (SQL 편집기 Output 패널) | 있음(2026-11-07, 조사 때 조용히 끊김) | 위 K2. 쿼리 창 Messages 탭 | 중간 | 켜고 끄는 화면은 없음(A3) |
| A5 | 구체화된 뷰 새로 고침, TRUNCATE | `AltibaseToolMViewRefresh`, `AltibaseToolTableTruncate` | 없음 | P23과 같은 grep, Altibase 유지 관리 방언 없음(`model.admin` maintenance 방언 4개) | 중간 | |
| A6 | 뷰·프로시저·패키지 컴파일 | `AltibaseToolViewCompile/ProcedureCompile/PackageCompile` | 없음 | 위와 같음 | 중간 | |
| A7 | 복제 시작·중지 | `AltibaseToolReplicationStart/Stop` | 없음 | `model.admin` replication 방언에 Altibase 없음(읽기 전용 4개 DB) | 중간 | Altibase 운영 기능 |
| A8 | 프로시저·Typeset·시퀀스 만들기 | `Altibase{Procedure,Typeset,Sequence}Configurator`, `CreateTypesetPage` | 없음 | `SourceScripts` 템플릿 방언은 SQL Server·PostgreSQL·MySQL·Oracle뿐(`SourceScripts.java` 434-440행 부근) | 낮음 | |
| A9 | 패키지 선언 소스 보기 | `databaseEditor` `source.declaration` → `SQLSourceViewer` | 있음 | View DDL(`DdlViewerPanel`) | 낮음 | Swing이 저장 방식 색을 더함(altibase.md) |

## 6. DB별 연결 설정: 범용 Swing 화면에서 잃은 것과 "공급자 속성이라 여전히 동작하는가"

판단 규칙: (1) 키가 모델 `plugin.xml` `<provider-properties>`에 선언되어 있으면 Swing Provider Properties 표에 나온다(= 있음).
(2) 선언되지 않았으면 Swing에서 보이지 않는다. 하지만 모델은 키를 그대로 읽고, Swing 저장은 선언된 키만 고치므로
(`ConnectionSettingsPanel.java:398-409`) **이미 저장된 값은 계속 적용된다**. SWT 시절 연결은 이름을 바꿔 복사되므로(internal-rename.md 115-136행)
예전 값이 보이지 않은 채 남을 수 있다. 새 연결에는 `data-sources.json`을 손으로 고치지 않으면 넣을 수 없다.

| DB | 설정 | 저장 키 | 선언됨 | Swing에서 | 저장된 값이 동작 |
|---|---|---|---|---|---|
| PostgreSQL | Show all databases, 템플릿·사용 불가 DB, 모든 형식 읽기, 키와 열, 옛 시간대, `$$` 2개, 세션 역할 | `@chromite-show-non-default-db@` 등 | 예 | 표 | 예 |
| PostgreSQL | DB 통계 보기 | `show-database-statistics` | 아니오 | 없음 | 예(`PostgreDataSource.java:99-110`) |
| PostgreSQL | Prepared statement 사용(PgBouncer) | `@chromite-use-prepared-statements-db@` | 아니오 | 없음 | 예(`:300-306`) |
| PostgreSQL | pgpass 호스트 이름 | `overriddenUsername` | 아니오 | 주 양식 칸(`ConnectionAuthExtras`) | 예 |
| PostgreSQL | SSL 프록시 서비스 | 핸들러 속성 `sslProxyServer` | — | 없음 | 예 |
| SQL Server | 인증 종류 / Windows / NTLM integrated | auth model, 드라이버 속성 `integratedSecurity` | — | 콤보 + 칸 | 예 |
| SQL Server | Trust server certificate, Show all schemas | `sslTrustServerCertificate`, `@chromite-show-all-schemas@` | 예 | 주 양식 / 표 | 예 |
| SQL Server | Azure·Babelfish Show all databases | `show-all-databases-azure` | 아니오 | 없음 | 예(`SQLServerDataSource.java:759-763`) |
| SQL Server | Encrypt password(jTDS/Sybase) | `@chromite-encrypt-password@` | 아니오 | 없음 | 예 |
| MySQL | Show all databases, Server time zone | `@chromite-show-all-dbs@`, `@chromite-serverTimezone@` | 예 | 표 | 예 |
| MySQL | SSL 옵션 | `ssl.*` 핸들러 속성 | — | SSL 탭 | 예 |
| Oracle | Basic/TNS, TNS 경로, SID/Service | `@chromite-connection-type@`, `@chromite-tns-path@`, `@chromite-sid-service@` | 일부(sid-service만) | 주 양식 칸(`ConnectionDriverFields`) | 예 |
| Oracle | Role(SYSDBA 등), OS 인증 | 인증 속성 `oracle.logon-as`, auth model | 예 | 주 양식 칸 | 예 |
| Oracle | NLS 언어·지역·날짜·타임스탬프·길이·통화 | `@chromite-session-language@`, `@chromite-session-territory@`, `@chromite-session-nls-date-format@`, `session-nls-timestamp-format`, `session-nls-length-format`, `session-nls-currency-format` | 아니오 | Options의 세션 설정 탭(2026-11-08, swing-conn-advanced.md "Oracle 세션 설정") | 예(`OracleDataSource.java:361-372`) |
| Oracle | 열 주석 읽기, 시퀀스에서 찾기, 옵티마이저 버전 | `@chromite-meta-read-column-comments@`, `oracle.meta-search-in-sequences`, `oracle-meta-optimizer-version` | 아니오 | 없음 | 예 |
| Oracle | 그 밖의 메타데이터 옵션 11개 | O6 | 예 | 표 | 예 |
| 기타 | Firebird 내장 라이브러리 경로, Spanner 키 경로, BigQuery OAuth 종류·키 경로, Snowflake 스키마(`@chromite-schema@`)·인증자·역할, CUBRID shard, Mimer protocol·program, HANA 에디션 | 각 `*ConnectionPage`·`*AuthConfigurator` | 아니오 | 없음 | 예 |

## 7. 나머지 26개 번들 (한 번들 한 줄 + 끊김 행)

| 번들 | 주요 SWT 기능 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|
| `ext.athena.ui` | 연결 화면(지역, S3 출력 위치, AWS 카탈로그 보기) | 일부 | `AwsRegion`, `S3OutputLocation`, `show-aws-catalogs` 선언 → 표. SWT는 새 키 `OutputLocation`에 쓰고 모델은 둘 다 읽음(`AthenaDataSource.java:59-61`) | 낮음 | |
| `ext.bigquery.ui` | 연결 화면, 인증(OAuth 종류, 서비스 계정 키 파일) | 일부 | `AdditionalProjects`만 선언. 전용 인증 칸 없음(swing-conn-advanced.md 226행) | 낮음 | |
| `ext.clickhouse.ui` | SSL 칸, Cloud SSO·OIDC 인증, ENUM 값 편집기 | 없음 | swing-conn-advanced.md 225-226행, swing-frontend.md 1429행. 인증 서비스 `UIServiceAuth`(`ClickhouseAuthPrompt.java:61`) 구현 없음 | 낮음 | 사용자 이름/암호 연결은 범용 화면으로 됨 |
| `ext.cloudflare.d1.ui` | 연결 화면, 인증 칸, DB 고르기 | 일부 | 전용 방식 하나의 Login·Password만(swing-frontend.md 1432행) | 낮음 | DB 목록 고르기 없음 |
| `ext.cubrid.ui` | 연결 + shard 페이지, 프로시저·열·고유 키 만들기, OID 검색, 권한 편집기, 정보 패널, 계획 보기, 환경설정(DBMS 출력) | 없음 | 연결 외 grep 0건. 결과 패널(`resultset.panel`)·계획 보기(`sql.plan.view`) 없음 | 낮음 | 출력은 아래 K3 |
| ↳ CUBRID DBMS_OUTPUT | (편집기 Output) | 있음(2026-11-07, 코드 경로만 확인) | K3 | 낮음 | |
| `ext.dameng.ui` | 스키마·시퀀스 만들기 | 없음 | 새로 만들기 메뉴 없음 | 낮음 | |
| `ext.databend.ui` | SSL 칸 | 없음 | swing-conn-advanced.md 225행 | 낮음 | |
| `ext.db2.ui` | 연결 + 추적(trace) 페이지, FK·인덱스·스키마·시퀀스·고유 키 만들기, 루틴·테이블 DDL 편집기, 서버 애플리케이션(세션) 편집기, 테이블스페이스 고르기, 오류 보기 도구, REORG TABLE | 일부 | 세션은 `DB2DataSource`의 세션 관리자 어댑터로 Activity Monitor에 나옴. 나머지 grep 0건 | 낮음 | |
| `ext.duckdb.ui` | 연결 + 고급(`$$` 처리), DB 파일 열기 처리기, MotherDuck 인증 | 일부 | `$$` 키 선언 → 표, MotherDuck은 Password(토큰) 칸(swing-frontend.md 1431행). 파일 처리기(`fileTypeHandler`) 없음 | 낮음 | |
| `ext.exasol.ui` | 연결(백업 호스트, 암호화), 사용자·역할·스키마·소비자 그룹·우선순위 그룹·PK/FK/인덱스 대화상자, 세션·잠금 편집기, 테이블 가져오기·내보내기 도구 | 일부 | 연결 키 선언 → 표, 세션은 어댑터로 나옴. 잠금은 모델 `ExasolLockManager`가 있으나 연결 안 됨(A2와 같은 이유). 나머지 없음 | 낮음 | |
| `ext.firebird.ui` | 연결, 내장(embedded) 연결 페이지(네이티브 라이브러리 경로) | 일부 | `nativeLibraryPath` 미선언 | 낮음 | |
| `ext.gaussdb.ui` | 연결, 새 DB, 함수·프로시저·패키지 만들기, 패키지 선언·본문 편집기 | 일부 | 연결은 범용, 나머지 없음 | 낮음 | |
| `ext.generic.ui` | 범용 연결 화면, MS Access 파일 처리기, PK·FK·인덱스 만들기, 소스 보기 | 일부 | 연결·View DDL 있음. 디자이너의 인덱스·FK는 Generic 미지원(swing-table-designer.md 124행), 파일 처리기 없음 | 낮음 | |
| `ext.h2.ui` | 환경설정 "H2"(허용 Java 클래스 목록, 보안) | 없음 | `OptionsDialog`에 없음 | 낮음 | |
| ↳ H2 초기화 스크립트 확인 | (`core`의 `UIServiceConnectionInitImpl`) | 조용히 끊김 | K4 | 낮음 | 안전한 쪽(거부)으로 실패 |
| `ext.hana.ui` | 연결(에디션, 인스턴스 번호) | 일부 | `instanceNumber`만 선언, 에디션 고르기 없음. 만료 암호 변경·라이선스 입력 창은 `SwingPlatformUI`가 구현(`:360`, `:433`) | 낮음 | |
| `ext.iotdb.ui` | 연결, 사용자 관리자(UI 번들의 objectManager)·사용자 편집기·권한 | 없음 | Swing 보안 방언은 3개 DB뿐 | 낮음 | |
| `ext.kingbase.ui` | 연결, 새 DB, 함수·프로시저 만들기 | 일부 | gaussdb와 같음 | 낮음 | |
| `ext.mimer.ui` | 연결 설정(프로토콜, 프로그램 인증), 약 80개 만들기 페이지(databank, shadow, domain, 권한 등), 온라인 상태·shadow 복원 명령, XML 계획 보기, 환경설정 | 없음 | 연결 외 grep 0건 | 낮음 | |
| `ext.oceanbase.ui` | 연결 화면(서버=테넌트, DB) | 있음 | 범용 양식의 서버·DB 칸 | 낮음 | |
| `ext.snowflake.ui` | 연결(창고, 스키마) + 고급(`ddString`) + 인증(authenticator, 역할) | 일부 | `warehouse`, `ddString` 선언. SWT가 쓰는 스키마 키 `@chromite-schema@`(`SnowflakeConstants.java:28`)와 인증 속성은 칸 없음 | 낮음 | |
| `ext.spanner.ui` | 연결, 인증(개인 키 경로) | 일부 | `PvtKeyPath` 미선언(swing-conn-advanced.md 226행) | 낮음 | |
| `ext.sqlite.ui` | 연결, DB 파일 처리기, 확장 불러오기 승인·오류 동작, LibSQL 토큰, 값 편집기 | 일부 | 파일 연결·LibSQL Token 있음(swing-frontend.md 1431행). 파일 처리기·값 편집기 없음 | 낮음 | |
| ↳ SQLite 확장 불러오기 승인 | `SQLiteExtensionConfirmationImpl`(`com.seizia.chromite.service`) | 조용히 끊김 | K5 | 낮음 | 안전한 쪽(거부)으로 실패 |
| `ext.tidb.ui` | 연결 화면(MySQL 계열) | 있음 | 범용 양식 | 낮음 | |
| `ext.tidblake.ui` | SSL 칸 | 없음 | swing-conn-advanced.md 225행 | 낮음 | |
| `ext.vertica.ui` | 연결 + 고급(주석 읽기 끄기, `$$`), 시퀀스 만들기 | 일부 | 고급 키 선언 → 표, 시퀀스 만들기 없음 | 낮음 | |
| `ext.yashandb.ui` | 연결, 데이터 형식·패키지·프로시저·스키마·트리거 만들기, 세션 편집기 | 일부 | 세션은 `YashanDBDataSource` 어댑터로 나옴, 나머지 없음 | 낮음 | |

## 8. 이 영역과 겹치는 공통 항목 (다른 감사 영역, 참고)

- **DB 객체 삭제·이름 바꾸기:** Object Explorer의 Delete/Rename은 연결, 로그인·역할·사용자, 에이전트·스케줄러 항목에서만 동작한다
  (`ObjectExplorerPanel.java:553-569`). 테이블·뷰·시퀀스·인덱스 등 DB별 객체를 탐색기에서 지울 수 없다. SWT는 각 `DBEObjectManager`로 지웠다.
- **객체 속성 편집:** 속성 창은 읽기 전용(`PropertiesPanel.java:68`). SWT `databaseEditor`(17개 번들)·`*Configurator`로 하던 속성 편집은
  테이블 디자이너의 Table 탭(PostgreSQL·SQL Server·MySQL)에서만 된다(swing-table-designer.md 121행).
- **서버 출력 패널:** K1–K3의 원인. SQL 편집기 영역. (2026-11-07 Messages 탭으로 고침)
- **DB별 환경설정 페이지 전부(PostgreSQL, Oracle, Altibase, CUBRID, H2, Mimer):** Swing `OptionsDialog`에 없다(`OptionsDialog.java:178-180`).
- **Swing에만 있는 DB 기능(SWT에 없던 것):** SQL Server 에이전트, SQL Server 백업·복원, 공간 사용량, 서버 로그, 복제 상태, SQL Server·MySQL 잠금, pg_cron, Oracle 스케줄러 확장,
  Altibase 저장 방식 색. 이번 표에는 넣지 않았다.

## 높은 순서로 본 빠진 것·끊긴 것

1. K1 / O24 Oracle DBMS_OUTPUT — 조용히 끊김, 높음. (2026-11-07 고침)
2. O16 Oracle 사용자·역할·권한 관리 — 없음, 높음. (2026-11-08 고침, [swing-security.md](../swing-security.md) "Oracle")
3. A2 Altibase 잠금 관리자(모델은 있음; Exasol도 같음) — 없음, 높음. (2026-11-08 고침, Exasol은 남음)
4. K2 / A4 Altibase 서버 출력(메모리에 쌓임) — 조용히 끊김, 중간. (2026-11-07 고침)
5. O5 Oracle 세션 NLS 설정 — 없음(저장값은 보이지 않게 적용), 중간. (2026-11-08 고침)
6. TRUNCATE·트리거 사용/사용 안 함·구체화된 뷰 새로 고침(P23, O21, S14, M17, A5) — 없음, 중간. (2026-11-08 고침, A5 Altibase는 남음)
7. DB·스키마·시퀀스·테이블스페이스·확장·트리거·패키지 만들기(P13-P15, S9, M6, M9, O17) — 없음, 중간.
8. O18 Oracle 인덱스·제약·외래 키(디자이너 미지원) — 없음, 중간.
9. P29 PostgreSQL 디버거(모델까지 삭제) — 없음, 중간. (2026-11-07 옮김, [procedure-debugger.md](../procedure-debugger.md))
10. A6·A7 Altibase 컴파일, 복제 시작·중지 — 없음, 중간.

다음 순위: S4 Azure·Babelfish 모든 DB 보기, P19 pg_dumpall, O23 Toad·SQL Developer 가져오기, P3 PgBouncer prepared statement, K3 CUBRID 출력.
"일부"이지만 영향이 큰 것: O11·O12 Oracle 컴파일과 컴파일 오류 표시(노드에서 컴파일 불가, Modify 뒤 오류 위치 없음).

## 확인하지 못한 것

- 앱을 실행하지 않았다(브리프의 읽기 전용 조건). 모든 판단은 코드·`plugin.xml`·문서 대조다.
- K1의 `ORU-10027`은 Oracle `DBMS_OUTPUT` 문서 동작(가져가지 않은 줄이 계속 쌓임)과 코드로 추론했다. 실제 넘침은 재현하지 않았다. K3도 같다.
- K2의 메모리 증가는 코드 경로로만 판단했다(Altibase 드라이버가 콜백을 실제로 몇 번 부르는지 확인 안 함).
- 연결 설정 대조 스크립트는 `setProviderProperty`/`setProperty`/`setAuthProperty` 첫 인자만 풀었다. 맵에 직접 넣는 경우(Oracle 추가 페이지)는 손으로 확인했다.
  나머지 26개 번들은 클래스 목록·`plugin.xml` 기여 수준으로만 보았다.
- `ext.*` 모델 번들에서 쓰는 UI 서비스는 grep으로 모두 찾았다(`UIServiceAuth`, `UIServiceConnectionInit`, `SQLiteExtensionConfirmation`, `UIServiceSystemAgent`).
  `DBWorkbench.getPlatformUI()`의 확인·입력 메서드는 `SwingPlatformUI`가 모두 구현한다(`confirmAction` `:178-210`, `promptUserPasswordChange` `:360`, `showUserChoice` `:212`).

## 변경 이력

- 2026-11-08 3순위 Oracle·Altibase 관리: P23·S14·M17·O5·O16·O21·A2 → 있음, 요약 표와 높은 순서 목록 갱신.
