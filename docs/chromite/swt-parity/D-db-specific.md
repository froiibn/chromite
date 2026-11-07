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
| PostgreSQL (`ext.postgresql.ui`, `ext.postgresql.debug.ui`) | 29 | 19 | 6 | 4 | 0 | 0 |
| SQL Server (`ext.mssql.ui`) | 14 | 13 | 1 | 0 | 0 | 0 |
| MySQL/MariaDB (`ext.mysql.ui`) | 19 | 16 | 3 | 0 | 0 | 0 |
| Oracle (`ext.oracle.ui`) | 24 | 23 | 0 | 0 | 1 | 0 |
| Altibase (`ext.altibase.ui`) | 9 | 7 | 0 | 2 | 0 | 0 |
| 나머지 26개 번들 (+ 끊김 3행) | 29 | 15 | 12 | 2 | 0 | 0 |
| **합계** | **124** | **93** | **22** | **8** | **1** | **0** |

2026-11-08 다시 셈(devel #165 기준, 모든 행을 코드와 다시 대조): 위 수는 아래 표의 지금 상태 칸으로 센 것이다("수정함"·고침은 있음으로 셈).
그 전 표는 바뀐 행을 반영하지 않은 수였다. 조사 당시 수는 있음 41, 일부 31, 없음 46, 의도적 제외 1, 조용히 끊김 5였다.

2026-11-08 3순위 Oracle·Altibase 관리(`chromite/p3-oracle-altibase-admin`): P23·S14·M17·O5·O16·O21·A2 없음 → 있음.
2026-11-08 4순위 루틴(`chromite/p4-routines`): O11·O12·O14 일부 → 있음, O13·O20·A5·A6 없음 → 있음.
2026-11-08 4순위 연결 칸(`chromite/p4-connection-fields`): P3·S4·S5·O7 없음 → 있음, 번들 줄 bigquery·firebird·hana·snowflake 일부 → 있음,
databend·tidblake 없음 → 있음, clickhouse·mimer 없음 → 일부. 위 표의 수는 고치지 않음(공통 규칙: 메인 세션이 다시 셈).

2026-11-08 다른 도구에서 가져오기(`chromite/import-other-tools`): O23 없음 → 있음.

2026-11-08 4순위 결과 편집(`chromite/p4-grid-editing`): P28 없음 → 일부(ENUM 콤보), M18 없음 → 있음.

### 조용히 끊김 (먼저 볼 것)

| # | 기능 | 무엇이 끊겼나 | 근거 | 영향 |
|---|---|---|---|---|
| K1 | Oracle DBMS_OUTPUT | 모델이 모든 실행 컨텍스트에서 `DBMS_OUTPUT.ENABLE(1000000)`을 켠다(설정 기본값 켬). 출력을 읽는 쪽(`readServerOutput`, SWT SQL 편집기의 Output 패널)이 Swing에 없어 `PUT_LINE` 출력이 보이지 않는다. Oracle 문서상 `GET_LINE(S)`를 한 번도 부르지 않으면 줄이 계속 쌓이므로, 한 세션에서 출력이 100만 바이트를 넘으면 사용자 PL/SQL이 `ORU-10027: buffer overflow`로 실패할 수 있다(추론, 재현하지 않음) | `ext.oracle/.../OracleDataSource.java:335-342`(켜기), `:982-997`(1,000,000), `OracleConstants.java:210`, `ext.oracle/.../internal/OraclePreferencesInitializer.java:40`(기본 켬). `readServerOutput`·`DBCServerOutputReader` 호출: `ui.swing`, `model.sql`, `model.ai*`에서 grep 0건. **2026-11-07 고침:** 쿼리 창이 문장마다 읽어 비움([swing-server-output.md](../swing-server-output.md)) | 높음 |
| K2 | Altibase 서버 출력(PRINT/PRINTLN) | 모든 컨텍스트에 메시지 콜백을 등록하고 받은 글을 `StringBuilder`에 붙인다. 비우는 곳은 `readServerOutput`뿐인데 Swing이 부르지 않아, 출력이 보이지 않고 데이터 원본이 살아 있는 동안 메모리에 계속 쌓인다 | `ext.altibase/.../AltibaseDataSource.java:124-127`, `:728`, `:780`(append), `:817-819`(읽을 때만 비움), `AltibasePreferencesInitializer.java:36`(기본 켬). **2026-11-07 고침:** 쿼리 창이 문장마다 읽어 비움. 실행해 보니 기본값 초기화가 한 번도 실행되지 않아(번들에 활성화 코드가 없음) 설정이 꺼짐으로 읽혔다. `AltibaseActivator`를 더하고, 꺼져 있으면 콜백을 등록하지 않게 함([swing-server-output.md](../swing-server-output.md)) | 중간 |
| K3 | CUBRID DBMS_OUTPUT | Oracle과 같은 구조. 연결마다 `dbms_output.enable(10000)`(기본 버퍼 1만 바이트)을 켜고 읽지 않는다. 버퍼가 작아 넘침 가능성이 Oracle보다 크다(추론) | `ext.cubrid/.../CubridDataSource.java:333`, `:513-528`, `CubridPreferencesInitializer.java:35-36`. **2026-11-07 고침(코드 경로만, CUBRID 서버로 실행하지 않음):** 쿼리 창이 문장마다 읽어 비움 | 낮음(사용자 적음) |
| K4 | H2 초기화 스크립트 확인 | URL의 `INIT=`·연결 직후 SQL을 실행하기 전에 묻는 서비스(`UIServiceConnectionInit`, SWT `core`의 `UIServiceConnectionInitImpl`)가 Swing에 없다. 서비스가 없으면 "승인 안 됨"으로 연결을 거부한다(안전한 쪽으로 실패). 기능은 못 쓴다 | `ext.h2/.../H2DataSource.java:97`, `:112`, `H2InitScriptValidator.java:48-49`. Swing `plugin.xml`의 service 등록은 3개뿐(`:21-26`). **2026-11-08 고침:** `SwingConnectionInitService`가 묻는다(허용하고 연결 / 취소, SWT와 같은 승인 파일, [connection-lifecycle.md](../connection-lifecycle.md) "초기화 SQL과 SQLite 확장 승인") | 낮음 |
| K5 | SQLite 확장 불러오기 승인 | 확장 불러오기를 승인하는 서비스(`SQLiteExtensionConfirmationImpl`, `ext.sqlite.ui`의 `com.seizia.chromite.service`)가 없어 승인이 늘 거부된다(안전한 쪽으로 실패). 오류 결과의 "확장 설정" 동작(`resultset.error`)도 없다 | `ext.sqlite/.../SQLiteDataSource.java:178-179`. **2026-11-08 고침:** `SwingSqliteExtensionConfirmation`이 묻는다([connection-lifecycle.md](../connection-lifecycle.md)). 오류 결과의 "확장 설정" 동작은 여전히 없음 | 낮음 |

K1–K3의 공통 원인은 SQL 편집기에 서버 출력 패널이 없는 것이다([swing-frontend.md](../swing-frontend.md) 708-709행 "서버 출력 읽기"를 C단계 남긴 일로 기록). 편집기 영역 감사와 겹친다.
2026-11-07 #105에서 고쳤다(쿼리 창의 Messages 탭, [swing-server-output.md](../swing-server-output.md)).

## 1. PostgreSQL (`ext.postgresql.ui`, 57 클래스 / `ext.postgresql.debug.ui`)

| # | 기능 | SWT 출처 | 상태 | 근거 (Swing) | 영향 | 비고 |
|---|---|---|---|---|---|---|
| P1 | 연결 주 양식(호스트·포트·DB·URL) | `PostgreConnectionPage` | 있음 | `ConnectionSettingsPanel`(범용 양식) | 높음 | |
| P2 | 연결 고급: 모든 DB 보기(show-non-default-db), 템플릿 DB, 사용 불가 DB, 모든 데이터 형식 읽기, 키를 열과 함께 읽기, 옛 시간대 바꾸기, `$$` 문자열 처리 2개, 세션 역할 | `PostgreConnectionPage`, `PostgreConnectionPageAdvanced` | 있음 | 모두 `ext.postgresql/plugin.xml` `<provider-properties>`에 선언 → Provider Properties 표(`ConnectionSettingsPanel.java:220`) | 중간 | 주 양식 확인란이 아니라 Options의 표 한 줄이다 |
| P3 | 연결 고급: DB 통계 보기(show-database-statistics), Prepared statement 사용(PgBouncer용) | `PostgreConnectionPageAdvanced:288-302` | **있음** (2026-11-08, `chromite/p4-connection-fields`: 두 키를 `<provider-properties>`에 선언 → Provider Properties 표, [swing-conn-advanced.md](../swing-conn-advanced.md) "공급자 속성 선언"). 조사 당시: 없음 | 두 키가 `<provider-properties>`에 없음. 모델은 그대로 읽음(`PostgreDataSource.java:99-110`, `:300-306`) | 중간 | 조사 당시: 저장된 값은 계속 적용되지만 보거나 바꿀 수 없었다 |
| P4 | PgPass 인증 + pgpass 호스트 이름 바꾸기 | `PostgreAuthPgPassConfigurator` | 있음 | `ConnectionAuthExtras`([swing-conn-advanced.md](../swing-conn-advanced.md) 21, 69행) | 낮음 | |
| P5 | SSL(인증서·키·모드·factory·프록시 서비스) | `PostgreSSLConfigurator` | 일부 | `SslHandlerPanel`(swing-conn-advanced.md 136행) | 낮음 | SSL factory는 목록 대신 직접 입력. "Use proxy service"(`sslProxyServer`): SWT도 숨긴 칸(`PostgreSSLConfigurator.ENABLE_PROXY = false`)이고 켜면 모델이 SSL 설정을 하나도 넘기지 않는다(`initProxySSL` 비어 있음). 2026-11-08: 저장된 값이 켜져 있을 때만 확인란을 보여 끌 수 있게 함([swing-conn-advanced.md](../swing-conn-advanced.md) "SSL") |
| P6 | 로컬 클라이언트(pg_dump 위치) 선택 | `PostgreConnectionPageAdvanced`의 `ClientHomesSelector` | 있음 | `NativeBackupRestoreDialog.loadClients`·`browseClient`(swing-backup-restore.md 37행 표), 2026-11-08 연결 편집기 연결 속성의 로컬 클라이언트([swing-database-tasks.md](../swing-database-tasks.md)) | 낮음 | 연결 화면(공통 탭)과 백업 대화상자 둘 다에서 고른다 |
| P7 | 전역 환경설정 "PostgreSQL" 페이지(위 P2·P3 항목의 전역 기본값) | `PrefPagePostgreSQL`, `PostgrePreferencesInitializer` | 없음 | `OptionsDialog.java:178-180`(일반·편집기·색 탭뿐) | 낮음 | 연결마다 표로 바꿀 수는 있다. 2026-11-08 다시 확인: 그대로. DB별 Options 페이지는 Oracle·Altibase·CUBRID·Mimer SQL뿐(`OptionsDialog.java`:135) |
| P8 | 세션 관리자(유휴 보기, Terminate, Cancel query) | `PostgreSessionEditor` | 있음 | `SessionManagerPanel` + `PostgreActivitySessionManager`(swing-admin-tools.md 124행) | 높음 | Swing이 대기·막는 세션·트랜잭션 경과를 더함 |
| P9 | 잠금 관리자 | `PostgreLockEditor` | 있음 | `LockManagerPanel` + `PostgreBlockingLockManager`(swing-admin-tools.md 153행) | 높음 | |
| P10 | 사용 권한 탭(역할의 권한, 테이블·열·함수·스키마의 권한) | `RolePermissionsEditor`, `ObjectPermissionsEditor`, `PostgresPermissionsEditor` | 일부 | `PrincipalPropertiesDialog`, `ObjectPermissionsDialog`, `PostgreSecurityDialect`(DATABASE·SCHEMA 포함) | 중간 | 열 단위 권한, ALTER DEFAULT PRIVILEGES 없음(swing-security.md 158행) |
| P11 | 역할 만들기·속성 | `PostgreCreateRoleDialog`, `PostgreRoleConfigurator` | 있음 | `PrincipalPropertiesDialog`(swing-security.md 63행 표) | 중간 | |
| P12 | Source/DDL 탭(뷰·함수·트리거·규칙 등 정의 보기·제자리 편집 저장) | `PostgreSourceViewEditor` | 일부 | View DDL(`DdlViewerPanel`), Modify → 쿼리 창 `CREATE OR REPLACE`(swing-source-editor.md 7, 53행) | 중간 | 제자리 편집·저장 없음, 쿼리 창에서 실행 |
| P13 | 새 데이터베이스(소유자·템플릿·인코딩·테이블스페이스) | `PostgreCreateDatabaseDialog`, `PostgreDatabaseConfigurator` | 있음(2026-11-08) | Databases 폴더의 New Database…(`NewObjectCommands`, `NewObjectDialogs.askPostgreDatabase`): 이름·소유자·템플릿·인코딩·LC_COLLATE·LC_CTYPE·테이블스페이스 → 스크립트 미리 보기(취소가 기본) → 실행. 모델 `PostgreDatabaseManager`가 문장을 만든다. [swing-create-objects.md](../swing-create-objects.md) | 중간 | 조사 당시: 없음. LC_COLLATE·LC_CTYPE는 SWT에 없던 것 |
| P14 | 새 스키마·테이블스페이스·확장, 확장 제거 | `PostgreCreateSchemaDialog`, `PostgreCreateTablespaceDialog`, `PostgreCreateExtensionDialog`, 도구 `PostgreToolExtensionUninstall` | 있음(2026-11-08) | Schemas·Tablespaces·Extensions 폴더의 New Schema…/New Tablespace…/New Extension…(`NewObjectDialogs`: 소유자, 위치·옵션, 설치하지 않은 확장 표·스키마). 확장 제거는 확장 노드의 Delete(`DROP EXTENSION … CASCADE`, swing-explorer-object-actions.md). [swing-create-objects.md](../swing-create-objects.md) | 중간 | 조사 당시: 없음 |
| P15 | 시퀀스·트리거·이벤트 트리거·행 보안 정책 만들기 | `PostgreSequenceConfigurator`, `PostgreTriggerConfigurator`(+`PostgreTriggerEditPage`), `PostgreEventTriggerConfigurator`, `PropertyObjectConfigurator`(Policy) | 있음(2026-11-08) | 폴더의 New Sequence…/New Trigger…/New Event Trigger…/New Policy…/New Data type…가 실행할 수 있는 CREATE 틀을 쿼리 창에 연다(`SourceScripts.getTemplateKind`, 트리거·이벤트 트리거는 함수까지). [swing-create-objects.md](../swing-create-objects.md) | 중간 | 조사 당시: 없음. 대화상자 대신 틀(본문은 사용자가 씀) |
| P16 | 인덱스·제약·외래 키 만들기 | `PostgreIndexConfigurator`, `PostgreConstraintConfigurator`, `PostgreForeignKeyConfigurator` | 있음 | `TableDesignerPanel` + `design/PostgreTableDesignerDriver`(swing-table-designer.md 58행) | 중간 | |
| P17 | 함수·프로시저 만들기 | `PostgreProcedureConfigurator` | 있음 | `SourceEditCommands` New Function/Procedure(`SourceScripts`) | 중간 | |
| P18 | Backup(pg_dump) | 도구 `PostgreToolBackup`, `PostgreBackupWizard*` | 일부 | `NativeBackupRestoreDialog` | 중간 | 스키마·테이블 고르기, 도구 사용자·추가 인수 없음(swing-backup-restore.md 200행) |
| P19 | Global Backup(pg_dumpall) | 도구 `PostgreToolBackupAll`, `PostgreBackupAllWizard*` | 없음 | swing-backup-restore.md 200행 "전체 클러스터 백업(pg_dumpall)" 남긴 것 | 중간 | 역할·테이블스페이스 백업 수단이 없다. 2026-11-08 다시 확인: 그대로. SWT에서 저장한 `pgDatabaseBackupAll` 작업은 Database Tasks에서 실행만 됨(model.admin `NativeBackupRestore.java`:355-361) |
| P20 | Restore(pg_restore/psql) | 도구 `PostgreToolRestore`, `PostgreRestoreWizard` | 있음 | `NativeBackupRestoreDialog`(+ 첫 오류에서 멈춤) | 중간 | |
| P21 | psql로 스크립트 실행(도구) + SQL 편집기 "네이티브 클라이언트로 실행"(`sql.executors`) | `PostgreScriptExecuteWizard`, `PostgreScriptExecutor` | 있음(2026-11-08) | Tasks &gt; Run Script with Native Client…, Query &gt; Run with Native Client…, 작업 `pgScriptExecute` 저장·실행([swing-database-tasks.md](../swing-database-tasks.md)). 조사 당시: 일부 | 낮음 | 조사 당시 근거: 일반 형식 복원이 psql로 파일을 실행함. 편집기 연동은 grep `ScriptExecute`·`sql.executors` → `ui.swing` 0건(MySQL 복원용 1건 제외) |
| P22 | 유지 관리: ANALYZE, VACUUM | 도구 `PostgreToolAnalyze`, `PostgreToolVacuum` | 있음 | `MaintenanceDialog` + `PostgreMaintenanceDialect`(swing-admin-tools.md 368행) | 중간 | Swing이 REINDEX·CLUSTER·부풀림 추정을 더함 |
| P23 | TRUNCATE, 트리거 사용/사용 안 함, 구체화된 뷰 새로 고침 | 도구 `PostgreToolTruncate`, `PostgreToolTriggerEnable/Disable`, `PostgreToolRefreshMView` | 있음(2026-11-08) | Object Explorer 메뉴, `ObjectMaintenanceCommands` + `ObjectMaintenanceActions`(모델 도구의 SQL, swing-explorer-object-actions.md "유지 관리 명령") | 중간 | 조사 당시: 없음. 도구 옵션(ONLY, RESTART IDENTITY, CASCADE, CONCURRENTLY) 화면은 없음 |
| P24 | 외부 데이터 래퍼(FDW) 구성 마법사 | `PostgreFDWConfigWizard*`, 명령 `ext.postgresql.ui.fdw` | 없음 | grep `FDW`·`ForeignDataWrapper` → 0건 | 낮음 | |
| P25 | pgAgent 작업·일정 편집(분·시·요일 표) | `PostgreScheduleEditor`(+ 범용 편집기) | 일부 | 탐색기에 pgAgent 폴더는 모델 트리로 보임. 만들기·편집 없음. pg_cron은 Swing 새 기능(swing-job-schedulers.md 13, 75행) | 낮음 | 2026-11-08 다시 확인: 작업·일정·단계는 폴더의 New 명령(이름만)과 Delete·Rename으로 만들고 지움(`NewObjectCommands`, `ExplorerObjectCommands`). 일정 편집기(분·시·요일 표)는 없음 |
| P26 | EXPLAIN 옵션(ANALYZE, VERBOSE, COSTS, BUFFERS, SETTINGS, WAL, TIMING, SUMMARY) | `PostgreExplainPlanConfigurator` | 일부 | `SqlPlanExplainer.java:123-126`(실제 계획 = ANALYZE만) | 낮음 | BUFFERS 등 진단 옵션 없음 |
| P27 | plpgsql_check로 함수 검사(SQL 생성기, 편집기 명령) | `SQLGeneratorProcedureCheck`, `SQLEditorHandlerCheckProcedureConsole` | 없음 | grep `plpgsql_check` → 0건 | 낮음 | |
| P28 | 값 편집기: ENUM 콤보, hstore(구조), money, bit string | `dataManager`: `PostgreEnumValueManager` 등 | 있음 (2026-11-08) | ENUM 열은 허용 값 목록으로 편집(`ResultSetEnumValues`, [swing-grid-editing.md](../swing-grid-editing.md)). hstore 키·값 대화상자, bit string은 0·1만 입력, money는 글자(SWT도 주석 처리), 배열·복합 형식 대화상자([swing-value-editors.md](../swing-value-editors.md)) | 낮음 | bit string 길이는 저장 때 DB가 검사 |
| P29 | PL/pgSQL 디버거(중단점, 단계 실행) | `ext.postgresql.debug.ui`(+ 모델 `debug.core`, `ext.postgresql.debug.core`도 함께 삭제) | 있음(2026-11-07) | 모델 복원, Swing 디버거 탭([procedure-debugger.md](../procedure-debugger.md)) | 중간 | Step Out은 pldbgapi에 없음 |

## 2. SQL Server (`ext.mssql.ui`, 16 클래스)

| # | 기능 | SWT 출처 | 상태 | 근거 (Swing) | 영향 | 비고 |
|---|---|---|---|---|---|---|
| S1 | 인증 종류(SQL Server / Windows / NTLM / Kerberos / AD 계열) + NTLM integrated security | `SQLServerConnectionPage`(인증 패널), `SQLServerAuthConfigurator` | 있음 | `ConnectionAuthModels`, `ConnectionAuthExtras`(swing-conn-advanced.md 67행, swing-frontend.md 1424-1425행 표) | 높음 | |
| S2 | Trust server certificate | `SQLServerConnectionPage:173` | 있음 | `ConnectionDriverFields`(swing-conn-advanced.md 153행) | 높음 | Swing이 Encrypt(Optional/Mandatory/Strict)를 주 양식에 더함 |
| S3 | Show all schemas | `SQLServerConnectionPage:170` | 있음 | `<provider-properties>` 선언 → 표 | 낮음 | |
| S4 | Azure·Babelfish "Show all databases"(`show-all-databases-azure`) | `SQLServerConnectionPage:163` | **있음** (2026-11-08, `chromite/p4-connection-fields`: Azure·Babelfish 드라이버의 `<provider-properties>`에 선언(SQL Server 드라이버의 줄도 함께 복사) → 표). 조사 당시: 없음 | 키가 `<provider-properties>`에 없음. 모델 `SQLServerDataSource.java:759-763`은 값이 없으면 Azure·Babelfish에서 현재 DB만 보임 | 중간 | Azure SQL 사용자는 다른 DB를 탐색기에서 볼 수 없다(저장된 값은 동작) |
| S5 | Sybase/jTDS "Encrypt password" | `SQLServerConnectionPage:160` | **있음** (2026-11-08, `chromite/p4-connection-fields`: Generic(Sybase·예전) 공급자의 `<provider-properties>`에 선언 → 표. 같은 때 Azure·Babelfish 드라이버에 "Show all databases"(`show-all-databases-azure`)와 SQL Server 드라이버의 표 줄을 선언). 조사 당시: 없음 | 키 미선언. 모델 `SQLServerGenericDataSource.java:91` | 낮음 | |
| S6 | SSL(키 저장소, 비밀번호, 인증서 호스트 이름) | `SQLServerSSLConfigurator` | 있음 | `SslHandlerPanel`(swing-conn-advanced.md 138행) | 중간 | |
| S7 | 세션 관리자(Only connections, Kill) | `SQLServerSessionEditor` | 있음 | `SessionManagerPanel`(swing-admin-tools.md 125행) | 높음 | Swing이 잠금 탭을 새로 더함(SWT에 없었음) |
| S8 | Source / Definition / DDL 탭 | `SQLServerSourceViewEditor` | 일부 | View DDL, Modify → 쿼리 창(swing-source-editor.md 53행) | 중간 | 제자리 편집 없음 |
| S9 | 새 데이터베이스 | `SQLServerCreateDatabaseDialog`, `SQLServerDatabaseConfigurator` | 있음(2026-11-08) | Databases 폴더의 New Database…(`NewObjectDialogs.askSqlServerDatabase`): 이름·소유자·정렬 규칙·복구 모델 → 미리 보기 → 실행(자동 커밋, `CREATE DATABASE` 뒤 `ALTER DATABASE … COLLATE`, `ALTER AUTHORIZATION`, `SET RECOVERY`). [swing-create-objects.md](../swing-create-objects.md) | 중간 | 조사 당시: 없음. SWT는 이름만. 파일·파일 그룹 페이지는 없음 |
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
| M6 | 새 데이터베이스(문자 집합, 콜레이션) | `MySQLCreateDatabaseDialog`, `MySQLCatalogConfigurator` | 있음(2026-11-08) | Databases 폴더의 New Database…(`NewObjectDialogs.askMySQLDatabase`): 이름·문자 집합·정렬 규칙 → 미리 보기(DDL 바로 커밋 안내) → 열린 트랜잭션 질문 → 실행. [swing-create-objects.md](../swing-create-objects.md) | 중간 | 조사 당시: 없음 |
| M7 | 인덱스·제약·외래 키 | `MySQL{Index,Constraint,ForeignKey}Configurator` | 있음 | `design/MySQLTableDesignerDriver` | 중간 | |
| M8 | 프로시저·함수 만들기 | `MySQLProcedureConfigurator` | 있음 | New Procedure/Function | 중간 | |
| M9 | 트리거 만들기 | `MySQLTriggerConfigurator` | 있음(2026-11-08) | 테이블·데이터베이스의 Triggers 폴더 New Trigger…: `DELIMITER` 사이의 CREATE TRIGGER 틀을 쿼리 창에(`SourceScripts.getTemplateKind`). [swing-create-objects.md](../swing-create-objects.md) | 중간 | 조사 당시: 없음 |
| M10 | 이벤트 만들기 | `MySQLEventConfigurator` | 있음 | `MySQLEventDialog`(swing-job-schedulers.md 14행) | 낮음 | Swing이 사용/사용 안 함·스케줄러 상태를 더함 |
| M11 | 시퀀스 만들기(MariaDB) | `MySQLSequenceConfigurator` | 있음(2026-11-08) | Sequences 폴더 New Sequence…: CREATE SEQUENCE 틀. MariaDB 패키지(Packages 폴더 New Package…)도 같은 길. [swing-create-objects.md](../swing-create-objects.md) | 낮음 | 조사 당시: 없음 |
| M12 | Source 탭, 패키지 본문(MariaDB Oracle 모드) | `MySQLSourceViewEditor`, `MySQLPackageBodyEditor` | 일부 | View DDL, Modify(swing-source-editor.md 53행) | 낮음 | |
| M13 | 내보내기(mysqldump) | 도구 `MySQLToolExport`, `MySQLExportWizard*` | 일부 | `NativeBackupRestoreDialog` | 중간 | 테이블 고르기 없음(swing-backup-restore.md 200행) |
| M14 | 가져오기(mysql로 덤프 복원) | 도구 `MySQLToolImport` | 있음 | `NativeBackupRestoreDialog` | 중간 | |
| M15 | mysql로 스크립트 실행 + 편집기 네이티브 실행(`sql.executors`) | `MySQLScriptExecuteWizard`, `MySQLScriptExecutor` | 있음(2026-11-08) | 같은 메뉴, 작업 `mysqlScriptExecute`(MariaDB 11.8 클라이언트로 확인, [swing-database-tasks.md](../swing-database-tasks.md)). 조사 당시: 일부 | 낮음 | 조사 당시 근거: 복원이 `MySQLScriptExecuteHandler`로 파일 실행(`NativeBackupRestoreDialog.java:29-30, 516`). 편집기 연동 없음 |
| M16 | 테이블 유지 관리: ANALYZE, CHECK, OPTIMIZE, REPAIR | `MySQLToolAnalyse/Check/Optimize/Repair` | 있음 | `MySQLMaintenanceDialect`(swing-admin-tools.md 368행) | 중간 | |
| M17 | TRUNCATE | `MySQLToolTruncate` | 있음(2026-11-08) | P23과 같음(MariaDB 확인) | 중간 | 조사 당시: 없음. "외래 키 검사 끄기" 옵션 화면은 없음 |
| M18 | ENUM 값 편집기 | `MySQLEnumValueManager` | 있음 (2026-11-08) | `ENUM` 열은 허용 값 목록으로 편집(`ResultSetEnumValues`). `SET` 열은 확인란으로 여러 값 고르기([swing-value-editors.md](../swing-value-editors.md)) | 낮음 | |
| M19 | 스크립트의 `source`(다른 파일 포함) 명령 | `sqlCommand` `mysql.source` → `SQLCommandInclude`(`ui.editors.sql`) | **있음** (2026-11-08, [swing-query-commands.md](../swing-query-commands.md): ext.mysql `plugin.xml`이 `mysql.source`를 model.sql `SQLCommandInclude`에 등록, MariaDB 확인). 조사 당시: 없음 | 모델 명령 등록에 include 없음, grep `SQLCommandInclude` → 0건. 알 수 없는 명령은 오류(`SQLQueryExecuteJob.java:454-475`) | 낮음 | |

## 4. Oracle (`ext.oracle.ui`, 42 클래스)

| # | 기능 | SWT 출처 | 상태 | 근거 (Swing) | 영향 | 비고 |
|---|---|---|---|---|---|---|
| O1 | 연결 방식 Basic/TNS, SID/Service, TNS 폴더, 네트워크 별칭 | `OracleConnectionPage` | 있음 | `ConnectionDriverFields`(swing-conn-advanced.md 158-164행) | 높음 | |
| O2 | Custom(JDBC URL 직접) | `OracleConnectionPage` | 있음 | URL 방식으로 통합(같은 문서 160행) | 중간 | |
| O3 | 인증: OS 인증, Role(SYSDBA/SYSOPER) | `OracleAuthOSConfigurator`(칸 없음), `OracleAuthDatabaseNativeConfigurator` | 있음 | `ConnectionAuthExtras`(swing-conn-advanced.md 68행, swing-frontend.md 1427행) | 높음 | |
| O4 | Oracle 클라이언트(OCI) 홈 선택 | `OracleConnectionPage.createClientHomeGroup` | 의도적 제외 | swing-conn-advanced.md 165행. SWT도 `PRODUCT_FEATURE_ADVANCED_DATABASE_ADMINISTRATION`(상용)일 때만 보임(`OracleConnectionPage.java:112-114`) | 낮음 | |
| O5 | 세션 NLS: 언어, 지역, 날짜·타임스탬프 형식, 길이 의미(BYTE/CHAR), 통화 | `OracleConnectionExtraPage:103-122` | 있음(2026-11-08) | 연결 대화상자 Options의 세션 설정 탭(`ConnectionDriverFields` Oracle, swing-conn-advanced.md "Oracle 세션 설정") | 중간 | 조사 당시: 없음(6개 키가 `<provider-properties>`에 없어 저장값이 보이지 않게 적용됨) |
| O6 | 메타데이터 옵션: 옵티마이저 힌트, RULE 힌트, 단순 제약 질의, 다른 테이블 질의, DBA 뷰 쓰기·항상 DBA 보기, SYS 스키마, 빈 스키마 숨김, 한 스키마만, DATE를 날짜로, 동의어에서 찾기, OS 사용자 | `OracleConnectionExtraPage` | 있음 | 모두 선언(`ext.oracle/plugin.xml` provider-properties) → 표 | 낮음 | |
| O7 | 메타데이터 옵션: 열 주석 읽기, 시퀀스에서 찾기, 옵티마이저 버전 | `OracleConnectionExtraPage:138-171` | **있음** (2026-11-08, `chromite/p4-connection-fields`: 세 키를 Performance 묶음에 선언 → 표, 옵티마이저 버전은 SWT 목록 콤보). 조사 당시: 없음 | 키 미선언. 모델 `OracleSchema.java:151`, `:813`, `OracleDataSource.java:396` | 낮음 | |
| O8 | 전역·연결별 "Oracle" 환경설정(PLAN_TABLE 이름, ROWID 사용, DBMS_OUTPUT 켜기, 모든 동의어 읽기, 스크립트 escape 처리 끄기) | `PrefPageOracle`(preferencePages + propertyPages) | **있음** (2026-11-07, Options &gt; Oracle: PLAN_TABLE, ROWID, DBMS_OUTPUT, 모든 동의어, escape 처리(`OracleOptionsPage.java`:61-65, 101-121), 2026-11-08 연결별 값(`ConnectionSettingsOverrides.java`:73-74), [swing-options.md](../swing-options.md)). 조사 당시: 없음 | `OptionsDialog.java:178-180` | 낮음 | 기본값은 모델 쪽 `OraclePreferencesInitializer`가 그대로 넣는다(UI 번들의 같은 초기화가 지워져도 값 같음). K1을 끌 방법도 없다 |
| O9 | 세션 관리자(Kill, Disconnect, 백그라운드·비활성 보기) | `OracleSessionEditor` | 있음 | `OracleActivitySessionManager`(swing-admin-tools.md 127행) | 높음 | 2026-11-08 SWT에 없던 Cancel Query(18c+, `ALTER SYSTEM CANCEL SQL`)와 RAC 세션 번호(`@INST_ID`) 더함(swing-admin-tools.md "Oracle RAC와 쿼리 취소") |
| O10 | 잠금 관리자 | `OracleLockEditor` | 있음 | `OracleBlockingLockManager`(swing-admin-tools.md 154행) | 높음 | 2026-11-08 RAC(인스턴스 2개 이상)는 `GV$`로 모든 인스턴스를 읽음 |
| O11 | Compile 명령(탐색기에서 고른 PL/SQL 객체 컴파일, 오류 목록, 편집기의 오류 줄로 이동) | 명령 `ext.oracle.code.compile` → `CompileHandler`, `OracleCompilerDialog` | 있음(2026-11-08) | Object Explorer "Compile"(`ObjectMaintenanceActions` COMPILE, 모델 `getCompileActions`), `CompileErrorsDialog`(`ALL_ERRORS`, Go to Error → Modify 창의 오류 줄), [swing-routines.md](../swing-routines.md) | 중간 | 조사 당시: 일부(Maintenance의 무효 개체만, 노드에서 컴파일 불가) |
| O12 | PL/SQL 소스 편집기(패키지 선언·본문, 프로시저)와 저장 뒤 컴파일 오류 표시 | `OracleSourceDeclarationEditor`, `OracleSourceDefinitionEditor`, `OracleObjectDeclarationViewer`, (모델 쪽 `OracleObjectValidateAction`) | 있음(2026-11-08) | Modify → 쿼리 창. CREATE 뒤 `ALL_ERRORS`를 편집기 줄·열로 Messages에 쓰고 줄을 표시(`SqlEditorPanel`, `OracleCompileErrors`), [swing-routines.md](../swing-routines.md) | 중간 | 조사 당시: 일부(드라이버 경고만, 오류 줄·위치 없음) |
| O13 | 패키지 안 프로시저 → 소스 위치로 이동 | 명령 `ext.oracle.code.package.navigate` → `PackageNavigateHandler` | 있음(2026-11-08) | "Go to Source Code"(`RoutineCommands`): 패키지 Modify 창의 본문에서 루틴 머리글을 고름(겹침 번호 순서), [swing-routines.md](../swing-routines.md) | 낮음 | 조사 당시: 없음 |
| O14 | DDL 탭의 DDL 형식(FULL / 저장소 제외 / 간단히) | `OracleObjectDDLEditor`, `OracleEditorUtils.addDDLControl` | 있음(2026-11-08) | View DDL 탭의 DDL format 콤보(`DdlViewerPanel.Formats`, `ObjectScripts.generateCreate`), [swing-routines.md](../swing-routines.md) | 낮음 | 조사 당시: 일부(형식 콤보 없음) |
| O15 | 스케줄러 작업: 실행, 작업 동작 편집, 작업 로그 | 명령 `ext.oracle.job.run` → `JobRunHandler`, `SchedulerJobActionEditor`, `SchedulerJobLogEditor` | 있음 | `SchedulerCommands`, `OracleJobDialog`, 기록 탭(swing-job-schedulers.md 12, 23-25행) | 중간 | Swing이 더 넓음 |
| O16 | 사용자(스키마) 만들기, 역할·권한 부여 | `OracleSchemaConfigurator`(+ 범용 편집기의 권한 탭) | 있음(2026-11-08) | `OracleSecurityDialect` + 보안 창(swing-security.md "Oracle"): 사용자·역할 만들기·속성·삭제, 역할 소속, 시스템·객체 권한 | 높음 | 조사 당시: 없음. 패키지·열 권한, 테이블스페이스·할당량·프로필은 없음 |
| O17 | 시퀀스·큐·데이터 형식·패키지·테이블 트리거 만들기 | `Oracle{Sequence,Queue,DataType,Package,TableTrigger}Configurator` | 있음(2026-11-08) | 폴더의 New Sequence…/New Queue…/New Type…/New Package…/New Trigger…(테이블·스키마 트리거)가 CREATE 틀을 쿼리 창에 연다(큐는 `DBMS_AQADM`, 패키지는 명세와 본문). [swing-create-objects.md](../swing-create-objects.md) | 중간 | 조사 당시: 없음 |
| O18 | 인덱스·제약·외래 키 만들기 | `Oracle{Index,Constraint,ForeignKey}Configurator` | 있음(2026-11-08) | 테이블 디자이너의 Oracle 인덱스·외래 키·CHECK(`design.OracleTableDesignerDriver`, `OracleTableBase` 어댑터). Oracle이 받지 않는 외래 키 규칙(RESTRICT·SET DEFAULT·ON UPDATE)은 추가할 때 거절. 저장 전 열린 트랜잭션 질문. [swing-table-designer.md](../swing-table-designer.md), [swing-create-objects.md](../swing-create-objects.md) | 중간 | 조사 당시: 없음 |
| O19 | 통계 수집(테이블·인덱스) | `OracleToolGatherStatistics` | 있음 | `OracleMaintenanceDialect` GATHER_TABLE/INDEX/SCHEMA_STATS | 중간 | |
| O20 | 구조 검증(VALIDATE STRUCTURE) | `OracleToolValidateStructure` | 있음(2026-11-08) | Maintenance… 테이블 동작 Validate Structure(`OracleMaintenanceDialect` `validate`), [swing-routines.md](../swing-routines.md) | 낮음 | 조사 당시: 없음 |
| O21 | TRUNCATE, 트리거 사용/사용 안 함, 구체화된 뷰 새로 고침 | `OracleToolTruncate`, `OracleToolTriggerEnable/Disable`, `OracleToolRefreshMView` | 있음(2026-11-08) | P23과 같음 | 중간 | 조사 당시: 없음. REUSE STORAGE·새로 고침 방식 옵션 화면은 없음 |
| O22 | SQL*Plus로 스크립트 실행(도구 + 편집기 네이티브 실행) | `OracleToolScript`, `OracleScriptExecutor`(`sql.executors`) | 있음(2026-11-08) | 같은 메뉴(Oracle 스키마·연결), 작업 `oracleScriptExecute`. 암호를 명령줄에서 빼고 표준 입력 `CONNECT`로, 스키마를 현재 스키마로. Instant Client가 없어 명령 만들기만 확인([swing-database-tasks.md](../swing-database-tasks.md)). 조사 당시: 없음 | 낮음 | 조사 당시 근거: grep `sqlplus` → `ui.swing` 0건. 백업·복원도 Oracle은 대상 아님(swing-backup-restore.md 10행) |
| O23 | Toad·SQL Developer 연결 가져오기 | `importWizards`: `ConfigImportWizardToad`, `ConfigImportWizardSqlDeveloper` | 있음(2026-11-08) | File > Import Connections > From Another Tool…, `ext.oracle` `importer/SqlDeveloperConnectionReader`·`ToadConnectionReader`([connection-transfer.md](../connection-transfer.md) "다른 도구에서 가져오기"). 비밀번호는 SWT처럼 읽지 않음(Toad SSH 평문 비밀번호만) | 중간 | 조사 당시: 없음(swt-removal.md 115행). Oracle 사용자 이전 경로 |
| O24 | DBMS_OUTPUT 출력 | (SQL 편집기 Output 패널 + `PREF_DBMS_OUTPUT`) | 있음(2026-11-07, 조사 때 조용히 끊김) | 위 K1. 쿼리 창 Messages 탭 | 높음 | 조사 당시: 켜고 끄는 화면은 없음(O8). 2026-11-07 Options &gt; Oracle에서 켜고 끔(O8) |

## 5. Altibase (`ext.altibase.ui`, 8 클래스)

SWT에도 Altibase 전용 연결 화면은 없었다(`dataSourceConfigurator` 기여 없음). 연결은 범용 화면 그대로다.

| # | 기능 | SWT 출처 | 상태 | 근거 (Swing) | 영향 | 비고 |
|---|---|---|---|---|---|---|
| A1 | 세션 관리자(Disconnect) | `AltibaseSessionEditor` | 있음 | Activity Monitor의 "그 밖" 분기가 `AltibaseServerSessionManager` 어댑터 + Terminate(`ServerAdminProfile.java:253-260`) | 높음 | |
| A2 | 잠금 관리자 | `AltibaseLockEditor` | 있음(2026-11-08) | `ServerAdminProfile` Altibase 분기 + `AltibaseBlockingLockManager`(swing-admin-tools.md "Altibase 잠금") | 높음 | 조사 당시: 없음(모델 `AltibaseLockManager`가 연결되지 않음). Exasol은 남음 |
| A3 | 환경설정(계획 형식, DBMS 출력, 계획 깊이 접두어) | `PrefPageAltibase` | **있음** (2026-11-08, Options &gt; Altibase와 연결별 값: DBMS 출력, 계획 깊이 접두어(`AltibaseOptionsPage.java`:80-92, `ConnectionSettingsOverrides.java`:75-76), [swing-options.md](../swing-options.md)). 계획 형식은 `EXPLAIN PLAN = ON`이 문장을 실행해 규칙상 두지 않음(`AltibaseOptionsPage.java`:39-41). 조사 당시: 없음 | `OptionsDialog.java:178-180` | 낮음 | 기본값은 모델 `AltibasePreferencesInitializer` |
| A4 | 서버 출력(PRINT) | (SQL 편집기 Output 패널) | 있음(2026-11-07, 조사 때 조용히 끊김) | 위 K2. 쿼리 창 Messages 탭 | 중간 | 조사 당시: 켜고 끄는 화면은 없음(A3). 2026-11-08 Options &gt; Altibase에서 켜고 끔(A3), 열린 세션에도 다음 문장부터 적용(#165) |
| A5 | 구체화된 뷰 새로 고침, TRUNCATE | `AltibaseToolMViewRefresh`, `AltibaseToolTableTruncate` | 있음(2026-11-08) | 유지 관리 명령(`ObjectMaintenanceActions`)이 모델 도구의 문장을 씀, [swing-routines.md](../swing-routines.md) | 중간 | 조사 당시: 없음 |
| A6 | 뷰·프로시저·패키지 컴파일 | `AltibaseToolViewCompile/ProcedureCompile/PackageCompile` | 있음(2026-11-08) | Object Explorer "Compile"(모델 도구의 문장, 오류는 문장 오류로 보임), [swing-routines.md](../swing-routines.md) | 중간 | 조사 당시: 없음 |
| A7 | 복제 시작·중지 | `AltibaseToolReplicationStart/Stop` | 없음 | `model.admin` replication 방언에 Altibase 없음(읽기 전용 4개 DB) | 중간 | Altibase 운영 기능 |
| A8 | 프로시저·Typeset·시퀀스 만들기 | `Altibase{Procedure,Typeset,Sequence}Configurator`, `CreateTypesetPage` | 없음 | `SourceScripts` 템플릿 방언은 SQL Server·PostgreSQL·MySQL·Oracle뿐(`SourceScripts.java` 434-440행 부근) | 낮음 | 2026-11-08 다시 확인: 그대로(`SourceScripts.getDialect`는 4개 방언, `AltibaseSequenceManager`는 만들기를 허용하지 않음) |
| A9 | 패키지 선언 소스 보기 | `databaseEditor` `source.declaration` → `SQLSourceViewer` | 있음 | View DDL(`DdlViewerPanel`) | 낮음 | Swing이 저장 방식 색을 더함(altibase.md) |

## 6. DB별 연결 설정: 범용 Swing 화면에서 잃은 것과 "공급자 속성이라 여전히 동작하는가"

판단 규칙: (1) 키가 모델 `plugin.xml` `<provider-properties>`에 선언되어 있으면 Swing Provider Properties 표에 나온다(= 있음).
(2) 선언되지 않았으면 Swing에서 보이지 않는다. 하지만 모델은 키를 그대로 읽고, Swing 저장은 선언된 키만 고치므로
(`ConnectionSettingsPanel.java:398-409`) **이미 저장된 값은 계속 적용된다**. SWT 시절 연결은 이름을 바꿔 복사되므로(internal-rename.md 115-136행)
예전 값이 보이지 않은 채 남을 수 있다. 새 연결에는 `data-sources.json`을 손으로 고치지 않으면 넣을 수 없다.

| DB | 설정 | 저장 키 | 선언됨 | Swing에서 | 저장된 값이 동작 |
|---|---|---|---|---|---|
| PostgreSQL | Show all databases, 템플릿·사용 불가 DB, 모든 형식 읽기, 키와 열, 옛 시간대, `$$` 2개, 세션 역할 | `@chromite-show-non-default-db@` 등 | 예 | 표 | 예 |
| PostgreSQL | DB 통계 보기 | `show-database-statistics` | 예(2026-11-08) | 표 | 예(`PostgreDataSource.java:99-110`) |
| PostgreSQL | Prepared statement 사용(PgBouncer) | `@chromite-use-prepared-statements-db@` | 예(2026-11-08) | 표(Performance) | 예(`:300-306`) |
| PostgreSQL | pgpass 호스트 이름 | `overriddenUsername` | 아니오 | 주 양식 칸(`ConnectionAuthExtras`) | 예 |
| PostgreSQL | SSL 프록시 서비스 | 핸들러 속성 `sslProxyServer` | — | SSL 탭, 저장된 값이 켜져 있을 때만(2026-11-08, SWT도 숨김) | 예(켜면 SSL 설정을 넘기지 않음) |
| SQL Server | 인증 종류 / Windows / NTLM integrated | auth model, 드라이버 속성 `integratedSecurity` | — | 콤보 + 칸 | 예 |
| SQL Server | Trust server certificate, Show all schemas | `sslTrustServerCertificate`, `@chromite-show-all-schemas@` | 예 | 주 양식 / 표 | 예 |
| SQL Server | Azure·Babelfish Show all databases | `show-all-databases-azure` | 예(2026-11-08, Azure·Babelfish 드라이버) | 표 | 예(`SQLServerDataSource.java:759-763`) |
| SQL Server | Encrypt password(jTDS/Sybase) | `@chromite-encrypt-password@` | 예(2026-11-08, Generic 공급자) | 표 | 예 |
| MySQL | Show all databases, Server time zone | `@chromite-show-all-dbs@`, `@chromite-serverTimezone@` | 예 | 표 | 예 |
| MySQL | SSL 옵션 | `ssl.*` 핸들러 속성 | — | SSL 탭 | 예 |
| Oracle | Basic/TNS, TNS 경로, SID/Service | `@chromite-connection-type@`, `@chromite-tns-path@`, `@chromite-sid-service@` | 일부(sid-service만) | 주 양식 칸(`ConnectionDriverFields`) | 예 |
| Oracle | Role(SYSDBA 등), OS 인증 | 인증 속성 `oracle.logon-as`, auth model | 예 | 주 양식 칸 | 예 |
| Oracle | NLS 언어·지역·날짜·타임스탬프·길이·통화 | `@chromite-session-language@`, `@chromite-session-territory@`, `@chromite-session-nls-date-format@`, `session-nls-timestamp-format`, `session-nls-length-format`, `session-nls-currency-format` | 아니오 | Options의 세션 설정 탭(2026-11-08, swing-conn-advanced.md "Oracle 세션 설정") | 예(`OracleDataSource.java:361-372`) |
| Oracle | 열 주석 읽기, 시퀀스에서 찾기, 옵티마이저 버전 | `@chromite-meta-read-column-comments@`, `oracle.meta-search-in-sequences`, `oracle-meta-optimizer-version` | 예(2026-11-08) | 표(Performance) | 예 |
| Oracle | 그 밖의 메타데이터 옵션 11개 | O6 | 예 | 표 | 예 |
| 기타 | Firebird 내장 라이브러리 경로, Spanner 키 경로, BigQuery OAuth 종류·키 경로, Snowflake 스키마(`@chromite-schema@`)·인증자·역할, CUBRID shard, Mimer protocol·program, HANA 에디션 | 각 `*ConnectionPage`·`*AuthConfigurator` | 아니오 | 2026-11-08: CUBRID shard 말고 모두 주 양식 칸(드라이버별 칸·인증 방식별 칸, [swing-conn-advanced.md](../swing-conn-advanced.md)). CUBRID `shardType`·`shardValue`는 드라이버 속성이라 Driver Properties 표에서 고칠 수 있다(SWT의 값 검사 없음) | 예 |

## 7. 나머지 26개 번들 (한 번들 한 줄 + 끊김 행)

| 번들 | 주요 SWT 기능 | 상태 | 근거 | 영향 | 비고 |
|---|---|---|---|---|---|
| `ext.athena.ui` | 연결 화면(지역, S3 출력 위치, AWS 카탈로그 보기) | 일부 | `AwsRegion`, `S3OutputLocation`, `show-aws-catalogs` 선언 → 표. SWT는 새 키 `OutputLocation`에 쓰고 모델은 둘 다 읽음(`AthenaDataSource.java:59-61`) | 낮음 | |
| `ext.bigquery.ui` | 연결 화면, 인증(OAuth 종류, 서비스 계정 키 파일) | **있음** (2026-11-08) | 주 양식 Project 칸(전에는 예시 URL에 `{database}`가 없어 넣을 곳이 없었음), 인증 칸 OAuth type·Key file(드라이버 속성 `OAuthType`·`OAuthPvtKeyPath`). 조사 당시: 일부 | 낮음 | |
| `ext.clickhouse.ui` | SSL 칸, Cloud SSO·OIDC 인증, ENUM 값 편집기 | 일부 (2026-11-08: SSL 탭) | SSL 탭: CA·클라이언트 인증서·키, SSL mode STRICT/NONE. 남은 것: Cloud SSO·OIDC 인증 칸과 `UIServiceAuth`(`ClickhouseAuthPrompt.java:61`) 구현, ENUM 값 편집기. 조사 당시: 없음 | 낮음 | 사용자 이름/암호 연결은 범용 화면으로 됨 |
| `ext.cloudflare.d1.ui` | 연결 화면, 인증 칸, DB 고르기 | 일부 | 전용 방식 하나의 Login·Password만(swing-frontend.md 1432행) | 낮음 | DB 목록 고르기 없음 |
| `ext.cubrid.ui` | 연결 + shard 페이지, 프로시저·열·고유 키 만들기, OID 검색, 권한 편집기, 정보 패널, 계획 보기, 환경설정(DBMS 출력) | 일부 (2026-11-08: 환경설정은 Options &gt; CUBRID와 연결별 값(`CubridOptionsPage`, `ConnectionSettingsOverrides.java`:77-78, [swing-options.md](../swing-options.md)), 폴더의 이름만 묻는 New 명령으로 시퀀스·사용자 만들기(`NewObjectCommands`, 모델 `CubridSequenceManager`·`CubridUserManager`, [swing-create-objects.md](../swing-create-objects.md)). 프로시저 만들기, OID 검색, 권한 편집기, 정보 패널, 계획 보기는 없음). 조사 당시: 없음 | 연결 외 grep 0건. 결과 패널(`resultset.panel`)·계획 보기(`sql.plan.view`) 없음 | 낮음 | 출력은 아래 K3 |
| ↳ CUBRID DBMS_OUTPUT | (편집기 Output) | 있음(2026-11-07, 코드 경로만 확인) | K3 | 낮음 | |
| `ext.dameng.ui` | 스키마·시퀀스 만들기 | **있음** (2026-11-08, 폴더의 New Schema…/New Sequence…가 이름을 묻고 모델 `DamengSchemaManager`·`DamengSequenceManager`로 만듦(`NewObjectCommands`, [swing-create-objects.md](../swing-create-objects.md)). SWT도 이름만 물음. 코드 경로만, Dameng 서버로 실행하지 않음). 조사 당시: 없음 | 새로 만들기 메뉴 없음 | 낮음 | |
| `ext.databend.ui` | SSL 칸 | **있음** (2026-11-08) | SSL 탭(켜기만, 모델이 `sslmode=enable`을 넣음). 조사 당시: 없음 | 낮음 | 핸들러가 `desktop="false"`지만 SWT는 탭을 보였다 |
| `ext.db2.ui` | 연결 + 추적(trace) 페이지, FK·인덱스·스키마·시퀀스·고유 키 만들기, 루틴·테이블 DDL 편집기, 서버 애플리케이션(세션) 편집기, 테이블스페이스 고르기, 오류 보기 도구, REORG TABLE | 일부 | 세션은 `DB2DataSource`의 세션 관리자 어댑터로 Activity Monitor에 나옴. 나머지 grep 0건 | 낮음 | 2026-11-08 다시 확인: 스키마·시퀀스는 폴더의 New 명령(이름만, 모델 `DB2SchemaManager`·`DB2SequenceManager`). 디자이너의 DB2 인덱스·외래 키, 추적 페이지, 테이블스페이스 고르기, 오류 보기, REORG는 없음 |
| `ext.duckdb.ui` | 연결 + 고급(`$$` 처리), DB 파일 열기 처리기, MotherDuck 인증 | 일부 | `$$` 키 선언 → 표, MotherDuck은 Password(토큰) 칸(swing-frontend.md 1431행). 파일 처리기(`fileTypeHandler`) 없음 | 낮음 | |
| `ext.exasol.ui` | 연결(백업 호스트, 암호화), 사용자·역할·스키마·소비자 그룹·우선순위 그룹·PK/FK/인덱스 대화상자, 세션·잠금 편집기, 테이블 가져오기·내보내기 도구 | 일부 | 연결 키 선언 → 표, 세션은 어댑터로 나옴. 잠금은 모델 `ExasolLockManager`가 있으나 연결 안 됨(A2와 같은 이유). 사용자: Users 폴더의 New User…가 이름과 암호(가림, 필수)를 받음(2026-11-08, swing-create-objects.md "새 사용자의 암호"; SWT 대화상자의 LDAP·Kerberos·설명은 없음). CONNECTION은 연결 문자열이 필수라 이름만 길(New Connection…)에서 뺌(2026-11-08, 전에는 NullPointerException, [password-handling.md](../password-handling.md)). 나머지 없음 | 낮음 | |
| `ext.firebird.ui` | 연결, 내장(embedded) 연결 페이지(네이티브 라이브러리 경로) | **있음** (2026-11-08) | Firebird Embedded 주 양식에 Native library folder(공급자 속성 `nativeLibraryPath`, 폴더 고르기). 조사 당시: 일부(`nativeLibraryPath` 미선언) | 낮음 | |
| `ext.gaussdb.ui` | 연결, 새 DB, 함수·프로시저·패키지 만들기, 패키지 선언·본문 편집기 | 일부 | 연결은 범용, 나머지 없음 | 낮음 | 2026-11-08 다시 확인: `GaussDBDataSource`가 `PostgreDataSource`를 이어받아 PostgreSQL New Database 대화상자와 New Function/Procedure 틀이 적용됨(`NewObjectDialogs`, `SourceScripts`, 코드 경로만). 패키지는 `DBSPackage`가 아니라 이름만 묻는 New로 떨어지고(문장 미확인), 패키지 선언·본문 편집기는 없음 |
| `ext.generic.ui` | 범용 연결 화면, MS Access 파일 처리기, PK·FK·인덱스 만들기, 소스 보기 | 일부 | 연결·View DDL 있음. 디자이너의 인덱스·FK는 Generic 미지원(swing-table-designer.md 124행), 파일 처리기 없음 | 낮음 | |
| `ext.h2.ui` | 환경설정 "H2"(허용 Java 클래스 목록, 보안) | 없음 | `OptionsDialog`에 없음 | 낮음 | |
| ↳ H2 초기화 스크립트 확인 | (`core`의 `UIServiceConnectionInitImpl`) | 있음 (2026-11-08. 조사 당시: 조용히 끊김) | K4 | 낮음 | 수정함 |
| `ext.hana.ui` | 연결(에디션, 인스턴스 번호) | **있음** (2026-11-08) | 주 양식 Edition·Instance number·Tenant database. 모델은 `edition`·`instanceNumber`를 읽지 않으므로(표의 Instance Number 줄은 효과가 없었다) 대화상자가 SWT처럼 포트와 드라이버 속성 `databaseName`을 정한다. 만료 암호 변경·라이선스 입력 창은 `SwingPlatformUI`가 구현(`:360`, `:433`). 조사 당시: 일부 | 낮음 | |
| `ext.iotdb.ui` | 연결, 사용자 관리자(UI 번들의 objectManager)·사용자 편집기·권한 | 없음 | Swing 보안 방언은 3개 DB뿐 | 낮음 | |
| `ext.kingbase.ui` | 연결, 새 DB, 함수·프로시저 만들기 | **있음** (2026-11-08: `KingbaseDataSource`가 `PostgreDataSource`를 이어받아 PostgreSQL New Database 대화상자(`NewObjectDialogs`)와 New Function/Procedure 틀(`SourceScripts`)이 적용됨, [swing-create-objects.md](../swing-create-objects.md). 코드 경로만, Kingbase 서버로 실행하지 않음, Oracle 호환 모드 문법은 다루지 않음). 조사 당시: 일부 | gaussdb와 같음 | 낮음 | |
| `ext.mimer.ui` | 연결 설정(프로토콜, 프로그램 인증), 약 80개 만들기 페이지(databank, shadow, domain, 권한 등), 온라인 상태·shadow 복원 명령, XML 계획 보기, 환경설정 | 일부 (2026-11-08: 연결 설정) | 주 양식 Protocol·Program·Program password(2026-11-08). 나머지는 grep 0건. 조사 당시: 없음 | 낮음 | |
| `ext.oceanbase.ui` | 연결 화면(서버=테넌트, DB) | 있음 | 범용 양식의 서버·DB 칸 | 낮음 | |
| `ext.snowflake.ui` | 연결(창고, 스키마) + 고급(`ddString`) + 인증(authenticator, 역할) | **있음** (2026-11-08) | 주 양식 Warehouse·Schema(모델이 먼저 읽는 서버 이름·`@chromite-schema@`를 보임), 인증 칸 Role·Authenticator(인증 속성), `ddString`은 표. Test 뒤 DB·창고·스키마 목록 채우기는 없음. 조사 당시: 일부 | 낮음 | |
| `ext.spanner.ui` | 연결, 인증(개인 키 경로) | 일부 | 2026-11-08: 인증 칸 Key file(`PvtKeyPath`). 남은 것: 드라이버 예시 URL에 자리 표시가 없어 Project·Instance·Database 칸 대신 JDBC URL만 입력(`main-properties` 미사용) | 낮음 | |
| `ext.sqlite.ui` | 연결, DB 파일 처리기, 확장 불러오기 승인·오류 동작, LibSQL 토큰, 값 편집기 | 일부 | 파일 연결·LibSQL Token 있음(swing-frontend.md 1431행). 파일 처리기·값 편집기 없음 | 낮음 | |
| ↳ SQLite 확장 불러오기 승인 | `SQLiteExtensionConfirmationImpl`(`com.seizia.chromite.service`) | 있음 (2026-11-08. 조사 당시: 조용히 끊김) | K5 | 낮음 | 수정함. 오류 결과의 "확장 설정" 동작은 없음 |
| `ext.tidb.ui` | 연결 화면(MySQL 계열) | 있음 | 범용 양식 | 낮음 | |
| `ext.tidblake.ui` | SSL 칸 | **있음** (2026-11-08) | SSL 탭(켜기만). SWT 화면의 클라이언트 인증서 칸은 모델이 읽지 않아 옮기지 않음. 조사 당시: 없음 | 낮음 | 핸들러가 `desktop="false"`지만 SWT는 탭을 보였다 |
| `ext.vertica.ui` | 연결 + 고급(주석 읽기 끄기, `$$`), 시퀀스 만들기 | **있음** (2026-11-08: 시퀀스는 폴더의 New Sequence…가 이름을 묻고 모델 `VerticaSequenceManager`로 만듦(`NewObjectCommands`). 코드 경로만, Vertica 서버로 실행하지 않음). 조사 당시: 일부 | 고급 키 선언 → 표, 시퀀스 만들기 없음 | 낮음 | |
| `ext.yashandb.ui` | 연결, 데이터 형식·패키지·프로시저·스키마·트리거 만들기, 세션 편집기 | **있음** (2026-11-08: `YashanDBDataSource`가 `OracleDataSource`를 이어받아 Oracle의 New Type/Package/Procedure/Function/Trigger 틀(`SourceScripts`)과 사용자(=스키마) 만들기(`OracleSecurityDialect`, model.admin `SecurityDialect.java`:160)가 적용됨, [swing-create-objects.md](../swing-create-objects.md). 코드 경로만, YashanDB 서버로 실행하지 않음. Oracle 스케줄러는 YashanDB를 일부러 뺌(`SchedulerCommands`)). 조사 당시: 일부 | 세션은 `YashanDBDataSource` 어댑터로 나옴, 나머지 없음 | 낮음 | |

## 8. 이 영역과 겹치는 공통 항목 (다른 감사 영역, 참고)

- **DB 객체 삭제·이름 바꾸기:** Object Explorer의 Delete/Rename은 연결, 로그인·역할·사용자, 에이전트·스케줄러 항목에서만 동작한다
  (`ObjectExplorerPanel.java:553-569`). 테이블·뷰·시퀀스·인덱스 등 DB별 객체를 탐색기에서 지울 수 없다. SWT는 각 `DBEObjectManager`로 지웠다.
  (2026-11-07 고침, #119: 객체 관리자가 있는 모든 DB 객체를 지우고 이름을 바꿈(`ExplorerObjectCommands`), C-10·C-11, B-E11)
- **객체 속성 편집:** 속성 창은 읽기 전용(`PropertiesPanel.java:68`). SWT `databaseEditor`(17개 번들)·`*Configurator`로 하던 속성 편집은
  테이블 디자이너의 Table 탭(PostgreSQL·SQL Server·MySQL)에서만 된다(swing-table-designer.md 121행).
  (2026-11-08 고침: Properties 창과 Object Details 속성 탭에서 객체 관리자가 고칠 수 있다고 하는 속성을 고침, `ObjectPropertyEdits`, [swing-object-properties.md](../swing-object-properties.md), B-E1·B-E2·C-15)
- **서버 출력 패널:** K1–K3의 원인. SQL 편집기 영역. (2026-11-07 Messages 탭으로 고침)
- **DB별 환경설정 페이지 전부(PostgreSQL, Oracle, Altibase, CUBRID, H2, Mimer):** Swing `OptionsDialog`에 없다(`OptionsDialog.java:178-180`).
  (2026-11-07·08 Oracle·Altibase·CUBRID·Mimer SQL 페이지를 더함(`OptionsDialog.java`:135, O8, A3, §7). PostgreSQL(P7)·H2는 남음)
- **Swing에만 있는 DB 기능(SWT에 없던 것):** SQL Server 에이전트, SQL Server 백업·복원, 공간 사용량, 서버 로그, 복제 상태, SQL Server·MySQL 잠금, pg_cron, Oracle 스케줄러 확장,
  Altibase 저장 방식 색. 이번 표에는 넣지 않았다.

## 높은 순서로 본 빠진 것·끊긴 것

1. K1 / O24 Oracle DBMS_OUTPUT — 조용히 끊김, 높음. (2026-11-07 고침)
2. O16 Oracle 사용자·역할·권한 관리 — 없음, 높음. (2026-11-08 고침, [swing-security.md](../swing-security.md) "Oracle")
3. A2 Altibase 잠금 관리자(모델은 있음; Exasol도 같음) — 없음, 높음. (2026-11-08 고침, Exasol은 남음)
4. K2 / A4 Altibase 서버 출력(메모리에 쌓임) — 조용히 끊김, 중간. (2026-11-07 고침)
5. O5 Oracle 세션 NLS 설정 — 없음(저장값은 보이지 않게 적용), 중간. (2026-11-08 고침)
6. TRUNCATE·트리거 사용/사용 안 함·구체화된 뷰 새로 고침(P23, O21, S14, M17, A5) — 없음, 중간. (2026-11-08 고침, A5 Altibase도 2026-11-08 `chromite/p4-routines`)
7. DB·스키마·시퀀스·테이블스페이스·확장·트리거·패키지 만들기(P13-P15, S9, M6, M9, O17) — 없음, 중간. (2026-11-08 고침, [swing-create-objects.md](../swing-create-objects.md))
8. O18 Oracle 인덱스·제약·외래 키(디자이너 미지원) — 없음, 중간. (2026-11-08 고침)
9. P29 PostgreSQL 디버거(모델까지 삭제) — 없음, 중간. (2026-11-07 옮김, [procedure-debugger.md](../procedure-debugger.md))
10. A6·A7 Altibase 컴파일, 복제 시작·중지 — 없음, 중간. (A6은 2026-11-08 고침, A7은 남음)

다음 순위: S4 Azure·Babelfish 모든 DB 보기(2026-11-08 고침), P19 pg_dumpall, O23 Toad·SQL Developer 가져오기(2026-11-08 고침), P3 PgBouncer prepared statement(2026-11-08 고침), K3 CUBRID 출력.
"일부"이지만 영향이 큰 것: O11·O12 Oracle 컴파일과 컴파일 오류 표시(노드에서 컴파일 불가, Modify 뒤 오류 위치 없음). (2026-11-08 고침, [swing-routines.md](../swing-routines.md))

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
- 2026-11-08 4순위 결과 편집: P28 → 일부, M18 → 있음(ENUM 콤보, [swing-grid-editing.md](../swing-grid-editing.md)). 요약 표 수는 메인 세션이 다시 셈.
- 2026-11-08 4순위 루틴: O11·O12·O13·O14·O20·A5·A6 → 있음, 높은 순서 목록 갱신(요약 표 수는 devel 병합 뒤 다시 센다)([swing-routines.md](../swing-routines.md)).
- 2026-11-08 O23 Toad·SQL Developer 연결 가져오기 → 있음([connection-transfer.md](../connection-transfer.md) "다른 도구에서 가져오기"), 요약 표 갱신.
- 2026-11-08 후속 #69: O9·O10 비고(Oracle Cancel Query, RAC `GV$`). 상태 변화 없음.
- 2026-11-08 4순위 연결 칸: P3·S4·S5·O7 → 있음, P5 비고(프록시 서비스), §6 연결 설정 표, §7 bigquery·clickhouse·databend·firebird·hana·mimer·snowflake·spanner·tidblake 줄 갱신(요약 표 수는 메인 세션이 다시 셈)
  ([swing-conn-advanced.md](../swing-conn-advanced.md)).
- 2026-11-08 devel(#165) 코드와 모든 행을 다시 대조(`chromite/audit-recount`): O8·A3·dameng·kingbase·vertica·yashandb → 있음, cubrid → 일부, P7·P19·P25·A8·O24·A4·db2·gaussdb 비고, §8 두 줄에 고친 것 표시. 요약 표를 행 상태로 다시 셈(있음 92, 일부 23, 없음 8, 의도적 제외 1, 조용히 끊김 0).
- 2026-11-08 5순위 값 편집기 나머지(`chromite/value-editors-rest`, [swing-value-editors.md](../swing-value-editors.md)): P28 일부 → 있음(hstore 대화상자, bit string 입력),
  M18 비고(SET 고르기). 요약 표: PostgreSQL 있음 18 → 19, 일부 7 → 6(합계 있음 93, 일부 22).
