# Swing 데이터베이스 백업·복원 (Back Up / Restore)

## 목적

Object Explorer의 데이터베이스 노드에서 SSMS처럼 **Tasks ▸ Back Up… / Restore…**로 데이터베이스를 백업하고 복원한다.

1. **SQL Server** — SSMS의 Back Up Database / Restore Database 대화상자와 같은 페이지와 T-SQL(`BACKUP DATABASE/LOG … TO DISK`,
   `RESTORE HEADERONLY/FILELISTONLY`, `RESTORE DATABASE/LOG … WITH MOVE, REPLACE, RECOVERY/NORECOVERY`). 백업 파일은 **서버의 경로**다.
2. **PostgreSQL, MySQL/MariaDB** — DBeaver의 네이티브 도구 작업(pg_dump/pg_restore/psql, mysqldump/mysql)을 이 컴퓨터의 **로컬 클라이언트**로 실행한다.
3. Oracle은 대상이 아니다(RMAN·Data Pump는 서버 쪽 도구이고 DBeaver CE에도 없다).

## 구조

| 클래스 | 번들 | 역할 |
|---|---|---|
| `SQLServerBackupSettings` (새) | `com.seizia.chromite.model.admin` (`.mssql`) | 백업 설정과 T-SQL 일괄 처리(BACKUP, 확인용 RESTORE VERIFYONLY) |
| `SQLServerRestoreSettings` (새) | 같은 곳 | 복원 설정, 기본 복원 계획(LSN), 파일 위치 기본값, T-SQL 일괄 처리 |
| `SQLServerBackupSet`, `SQLServerBackupFile` (새) | 같은 곳 | `RESTORE HEADERONLY`·`FILELISTONLY`의 한 행 |
| `SQLServerBackupRestore` (새) | 같은 곳 | 서버 정보·백업 기록·백업 파일 읽기, 일괄 처리 실행과 진행 메시지(STATS), 취소 |
| `DatabaseTaskDialog` (새) | `com.seizia.chromite.ui.swing` | 작업 대화상자 틀: 페이지 선택 목록, 연결·진행률, 스크립트 메뉴, 확인·취소, 메시지 페이지, 백그라운드 실행 |
| `SqlServerBackupDialog` (새) | 같은 곳 | 일반 / 미디어 옵션 / 백업 옵션 |
| `SqlServerRestoreDialog` (새) | 같은 곳 | 일반(백업 파일·대상·백업 세트) / 파일(WITH MOVE) / 옵션 |
| `NativeBackupRestoreDialog` (새) | 같은 곳 | PostgreSQL·MySQL 백업·복원: 일반(PostgreSQL 백업 범위) / 개체(백업, 2026-11-08) / 옵션 / 로컬 클라이언트(도구 계정, 추가 인수, 명령줄 미리 보기) |
| `NativeBackupRestore` (새, 2026-11-08) | `com.seizia.chromite.model.admin` (`.nativetool`) | PostgreSQL·MySQL 네이티브 도구 작업의 화면과 상관없는 부분: 메뉴 조건(`isSupported`), 로컬 클라이언트 목록(`findLocalClients`, 내려받는 클라이언트 제외), 작업 형식, 명령줄(`getCommandLine`), 처리기 실행(`execute`, pg_restore 종료 코드 1). 대화상자와 Chromite Agent 도구(`dumpDatabase`, `restoreDump`, [ai-agent.md](ai-agent.md))가 함께 쓴다 |
| `BackupRestoreCommands` (새) | 같은 곳 | 메뉴를 켤 조건, DB 종류별 대화상자 열기 |
| `internal/BackupRestoreMessages` (새) | 같은 곳 | 화면 문구(영어 기본 + `_ko`) |
| `ObjectExplorerPanel` (변경) | 같은 곳 | Tasks 메뉴에 Back Up… / Restore… 두 항목과 켜기 조건(작은 훅) |

모델 클래스는 SWT·Swing에 기대지 않는다. `com.seizia.chromite.model.admin`은 이미 `ext.mssql`과 `model.jdbc`를 쓰고 `.mssql` 패키지를 내보내므로
MANIFEST·feature·pom은 바꾸지 않았다. 2026-11-08에 `.nativetool` 패키지를 더하면서 `model.admin`이 `registry`(`TaskPreferenceStore`,
`RemoteNativeClientLocation`)와 `tasks.native`를 `Require-Bundle`에 더하고 `.nativetool`을 내보낸다. Swing 번들도 새 `Require-Bundle`이 없다. `com.seizia.chromite.tasks.native`는 `ext.postgresql`이
`visibility:=reexport`로 다시 내보내고, `registry`(`TaskPreferenceStore`, `DriverDescriptor`)·`ext.postgresql`·`ext.mysql`은 이미 쓰고 있었다.
`tasks.native`는 SWT에 기대지 않는다(`Require-Bundle: model, model.sql, registry`). 그래서 SWT에서 떼어 낼 모델 부분이 없었다.

## SWT·DBeaver에서 옮긴 것 (근거)

| DBeaver | Chromite | 내용 |
|---|---|---|
| (없음) ext.mssql `tasks`에는 테이블 도구(재작성, 트리거)만 있다 | `SQLServerBackupSettings`, `SQLServerRestoreSettings`, `SQLServerBackupRestore` | SQL Server 백업·복원은 새로 만들었다. 문장 모양은 SSMS가 만드는 스크립트를 따른다 |
| ext.postgresql.ui `PostgreBackupWizard`, `PostgreRestoreWizard` → `PostgreDatabaseBackupSettings`/`PostgreDatabaseRestoreSettings` + `PostgreDatabaseBackupHandler`/`PostgreDatabaseRestoreHandler` | `NativeBackupRestoreDialog.createSettings`·`runHandler` | 같은 설정을 채워 임시 작업에 저장(`saveSettings` → `TaskPreferenceStore`)하고 작업 형식의 처리기로 `executeTask` |
| ext.mysql.ui 백업·복원 마법사 → `MySQLExportSettings`/`MySQLScriptExecuteSettings` + `MySQLDatabaseExportHandler`/`MySQLScriptExecuteHandler` | 같은 곳 | 같음(`mysqlDatabaseBackup`, `mysqlDatabaseRestore` 작업 형식) |
| tasks.native.ui `NativeToolWizardDialog.NativeClientConfigDialog`(:123-152), ui.editors.connection `ClientHomesSelector.populateHomes`, `ClientHomesPanel`(:300-318) | `NativeBackupRestoreDialog.loadClients`·`browseClient`·`prepareRun` | 드라이버의 클라이언트 위치 + 클라이언트 관리자가 찾은 설치를 목록으로, 찾아보기로 더한 폴더는 `DriverDescriptor.setNativeClientLocations` + `saveDrivers`, 고른 위치는 연결의 `clientHomeId`로 `persistConfiguration` |
| `DataTransferWizard.DataTransferWizardExecutor`(임시 작업) | `NativeBackupRestoreDialog.runHandler` | `createTemporaryTask` + `MonitorRunnableContext` (Swing 데이터 전송 마법사와 같은 방식) |
| ext.postgresql/ext.mysql plugin.xml의 작업 조건(`supportsNativeClient()`, `supportsNativeClients()`) | `BackupRestoreCommands.canBackUp` | 메뉴를 켜는 조건 |

## 설계 결정과 이유

### 대화상자 틀 (`DatabaseTaskDialog`)

- SSMS 데이터베이스 작업 대화상자 배치: 왼쪽에 "페이지 선택", 연결(서버·연결 이름·사용자), 진행률(상태 글자 + 막대), 오른쪽에 페이지,
  아래에 **스크립트 ▾**(새 쿼리 창 / 클립보드), 확인, 취소. 화면 문구에는 다른 제품 이름을 쓰지 않는다.
- 확인을 누르면 설정을 읽어 백그라운드 Job에서 실행하고 **메시지** 페이지로 넘어간다. 서버 메시지(STATS의 "N percent processed.", 처리한 페이지 수, 완료)와
  도구 출력이 줄 단위로 쌓인다. 실행 중에는 페이지 목록을 잠근다(설정은 이미 읽었으므로 고쳐도 소용이 없다). 취소 단추·창 닫기·Esc는 Job을 취소한다.
- 성공해도 창을 닫지 않는다. 메시지를 다시 볼 수 있게 확인을 끄고 취소를 닫기로 바꾼다. 실패하면 오류 전문을 메시지에 남기고 다시 고쳐 실행할 수 있다.
- "새 쿼리 창에 작업 스크립트"는 스크립트를 연결의 새 쿼리 창(`QueryWindows.openScript`)에 넣고 대화상자를 닫는다(모달이라 그대로 두면 쿼리 창을 쓸 수 없다).

### SQL Server 백업

- 페이지: **일반**(데이터베이스, 복구 모델, 백업 유형 전체/차등/트랜잭션 로그, 복사 전용, 백업 파일), **미디어 옵션**(기존 세트에 추가 = `NOFORMAT, NOINIT`,
  모두 덮어쓰기 = `NOFORMAT, INIT`, 새 미디어 세트 = `FORMAT, INIT, MEDIANAME, MEDIADESCRIPTION`; 완료되면 확인, 체크섬, 오류 시 계속),
  **백업 옵션**(백업 세트 이름·설명, 압축: 서버 기본값 / `COMPRESSION` / `NO_COMPRESSION`).
- 스크립트 예: `BACKUP DATABASE [db] TO DISK = N'/var/opt/mssql/backup/db.bak' WITH NOFORMAT, INIT, NAME = N'db-Full Database Backup', SKIP, NOREWIND, NOUNLOAD, COMPRESSION, CHECKSUM, STATS = 10`.
  만료 날짜 옵션이 없으므로 SSMS 기본처럼 늘 `SKIP`이다. "완료되면 백업 확인"은 SSMS처럼 msdb에서 방금 만든 세트의 위치를 찾아 `RESTORE VERIFYONLY … WITH FILE = @backupSetId`를 실행하는 두 번째 일괄 처리다.
- 기본 백업 파일은 `SERVERPROPERTY('InstanceDefaultBackupPath')`(2019 이전은 없음 → 기본 데이터 폴더) + `<db>.bak`. 경로 구분자는 서버 경로에서 정한다(Linux 서버는 `/`).
  파일은 서버가 쓰므로 이 컴퓨터의 파일 대화상자를 두지 않고 "경로는 데이터베이스 서버의 경로" 안내를 보인다.
- 단순 복구 모델이면 트랜잭션 로그 항목을 흐리게 하고 고르지 못하게 한다(서버가 거부한다). 압축을 지원하지 않는 에디션(`EngineEdition` 2·3·8만 지원,
  Express 4는 아님)이면 압축 칸을 서버 기본값으로 고정한다. 파일로 백업할 수 없는 서버(Azure SQL Database 5, Synapse 6·11)는 열 때 안내만 한다.
  Azure·Fabric·Babelfish 드라이버와 Synapse는 메뉴부터 끈다.
- 백업 세트 이름은 사용자가 고치기 전까지 백업 유형을 따라 기본 이름(`<db>-Full Database Backup` 등, 한국어 화면은 한국어)으로 바뀐다.

### SQL Server 복원

- **일반**: 백업 파일(서버 경로, 편집 가능한 콤보). 목록은 msdb 백업 기록(`backupset` + `backupmediafamily`, 디스크 장치)의 파일로, 이 데이터베이스 것 먼저다.
  기록이 있으면 열 때 가장 최근 파일을 바로 읽는다. **읽기**는 `RESTORE HEADERONLY`로 세트 목록을 읽고 기본 복원 계획을 고른 뒤 첫 데이터베이스 세트의
  `FILELISTONLY`를 읽는다. 표에서 복원할 세트를 체크한다(이름, 유형, 데이터베이스, 위치, 시작·완료 날짜, 첫·마지막 LSN, 크기, 사용자, 서버).
- **기본 복원 계획**: 마지막 전체 백업 → 그 전체 백업을 기준으로 한 마지막 차등 백업(전체의 CheckpointLSN = 차등의 DatabaseBackupLSN, 복사 전용 전체는 기준이 아님)
  → 그 뒤로 끊기지 않는 로그 백업(FirstLSN ≤ 앞 세트의 LastLSN, 이미 덮인 로그는 건너뜀, 틈이 있으면 멈춤).
- **파일**: 논리 이름, 형식(행 데이터/로그), 원래 파일, 복원 위치(편집 가능). 대상 이름이 원래 데이터베이스와 같으면 원래 경로, 다르면 파일 이름 속 원래 DB 이름을
  대상 이름으로 바꾼다(없으면 앞에 붙인다) — 원본 파일과 부딪치지 않게 하려는 것. "모든 파일을 폴더로 재배치"를 켜면 서버 기본 데이터·로그 폴더(바꿀 수 있음)로 옮긴다.
  원래 경로와 다른 파일만 `MOVE`로 쓴다. 대상 이름·폴더 설정이 바뀌면 기본값으로 다시 계산한다.
- **옵션**: 기존 데이터베이스 덮어쓰기(`REPLACE`), 복구 상태(`RECOVERY`/`NORECOVERY`, 설명 글), 대상 데이터베이스에 대한 기존 연결 닫기.
- 스크립트: `USE [master]` → (연결 닫기이고 대상이 있으면) `ALTER DATABASE … SET SINGLE_USER WITH ROLLBACK IMMEDIATE` → 고른 세트마다
  `RESTORE DATABASE|LOG [대상] FROM DISK = N'…' WITH FILE = n, [MOVE …,] NORECOVERY|RECOVERY, NOUNLOAD, [REPLACE,] STATS = 10`
  (마지막 세트만 고른 복구 상태, `MOVE`·`REPLACE`는 첫 문장만) → (연결 닫기이고 `RECOVERY`이면) `SET MULTI_USER`.
  복원 중 상태로 남는 데이터베이스는 ALTER할 수 없으므로 `NORECOVERY`면 마지막 문장을 넣지 않는다. 대상이 서버에 있는지는 실행(또는 스크립트)할 때 `sys.databases`로 확인한다.
- 성공하면 탐색기에서 데이터베이스가 든 폴더를 다시 읽는다(새 이름의 데이터베이스나 바뀐 상태가 보이게).

### 실행과 진행 메시지 (`SQLServerBackupRestore.execute`)

- 연결의 **격리 문맥**(`openIsolatedContext`, Activity Monitor와 같음)에서 일괄 처리마다 따로 실행하고 닫는다. 긴 백업이 쿼리 창의 연결을 붙잡지 않고,
  복원의 `USE [master]`가 쿼리 창의 현재 DB를 바꾸지 않게 하려는 것이다. "단일 연결" 설정이면 기본 문맥을 쓴다. BACKUP/RESTORE는 트랜잭션 안에서 실행할 수 없으므로
  자동 커밋으로 실행한다(수동 커밋 연결이면 이 실행 동안만 켠다).
- `STATS` 메시지는 JDBC 경고로 온다. 드라이버는 응답을 읽으면서 경고를 문장에 붙이므로, 실행하는 동안 감시 스레드가 200ms마다 경고 사슬에서 새 것만 넘긴다.
  진행률은 메시지 번호 3211(언어와 상관없음)의 첫 숫자다. 일괄 처리의 나머지 결과(`getMoreResults`)도 읽어 뒤 문장의 메시지를 받는다.
- 취소: 모니터가 취소되면 감시 스레드가 `Statement.cancel()`을 보낸다.
- 오류 글은 `SQLException` 사슬의 메시지를 모두 보인다(원인 메시지가 "terminating abnormally" 앞에 온다).

### PostgreSQL·MySQL/MariaDB (`NativeBackupRestoreDialog`)

- DBeaver 네이티브 도구 작업을 그대로 쓴다. 설정 객체를 화면 값으로 채우고(`loadSettingsFromInput` + setter), 임시 작업에 `saveSettings`로 저장한 뒤
  작업 형식의 처리기(`pgDatabaseBackup`, `pgDatabaseRestore`, `mysqlDatabaseBackup`, `mysqlDatabaseRestore`)의 `executeTask`를 부른다. SWT 마법사가
  작업에 설정을 저장하고 처리기가 다시 읽는 흐름과 같다. 처리기의 로그 스트림(명령줄, 도구 출력)은 메시지 페이지에 줄 단위로 보인다.
- 2026-11-08부터 설정을 채우는 것(화면 값)만 대화상자에 있고, 클라이언트 목록·작업 형식·명령줄·처리기 실행·pg_restore 종료 코드 1 처리는 모델의
  `NativeBackupRestore`에 있다. Chromite Agent의 PostgreSQL·MySQL 백업·복원 도구가 같은 경로로 실행하게 하려는 것이다(두 벌의 실행 코드가
  어긋나지 않게). 동작은 그대로다(아래 "확인 (2026-11-08)").
- 페이지: **일반**(데이터베이스, 출력 폴더·파일 이름 패턴 또는 입력 파일, PostgreSQL 형식·압축·인코딩, MySQL 덤프 방법), **옵션**(SWT 마법사의 주요 옵션:
  PostgreSQL `--no-owner`, `--no-privileges`, `--inserts`, `--clean`, `--create`; MySQL `--no-create-info`, `--add-drop-table`, `--disable-keys`,
  `--extended-insert`, `--no-data`, `--routines`, `--events`, `--comments`, DEFINER 제거, `--hex-blob`; 복원 쪽 `--clean`, `--create`, 첫 오류에서 멈춤(2026-11-07), 외래 키 검사 끄기, `-v`),
  **로컬 클라이언트**(클라이언트 폴더, 찾아보기, 상태, 명령줄 미리 보기). 스크립트 메뉴는 "명령줄 복사"와 "작업으로 저장…"(2026-11-08)이다.
- 2026-11-08 같은 대화상자에 세 번째 방식 "네이티브 클라이언트로 스크립트 실행"(psql, mysql, sqlplus)을 더하고, 설정을 작업으로 저장해 Database Tasks 보기에서
  다시 실행·편집하게 했다. 로컬 클라이언트는 연결 편집기(연결 속성)에서도 고른다. 자세한 것은 [swing-database-tasks.md](swing-database-tasks.md).
- 파일은 **이 컴퓨터의 파일**이다(도구가 로컬에서 읽고 쓴다). 파일 이름 기본값은 SWT와 같은 `dump-${database}-${timestamp}` + 형식의 확장자.
- **로컬 클라이언트**: 목록은 드라이버의 사용자 위치와 클라이언트 관리자가 찾은 설치(Windows 레지스트리 등)다. **내려받는 클라이언트**
  (`RemoteNativeClientLocation`, 처음 쓸 때 원격 저장소에서 받음)는 넣지 않는다 — 클라이언트 도구를 묻지 않고 내려받지 않으려는 것이다.
  처리기는 연결의 `clientHomeId`가 비어 있으면 목록 첫 항목(내려받는 클라이언트일 수 있음)을 고르므로, 실행 전에 늘 고른 로컬 위치를 연결에 저장한다.
  찾아보기로 더한 폴더는 드라이버를 "수정됨"(`setModified(true)`)으로 표시한 뒤 저장한다. `drivers.xml`에는 수정된 드라이버만 쓰이므로
  표시하지 않으면 다시 시작한 뒤 폴더가 사라진다(확인 중에 발견). SWT `ClientHomesPanel`(:300-318)도 표시하지 않아 같은 문제가 있을 수 있다(SWT라 고치지 않음).
  로컬 클라이언트가 없으면 "로컬 클라이언트가 설치되어 있지 않습니다… 찾아보기…로 폴더를 선택하십시오."를 보이고 실행하지 않는다.
- **pg_restore 종료 코드 1**: pg_restore는 오류가 하나라도 나면 1로 끝난다. 로그인 실패나 읽을 수 없는 파일처럼 아무것도 복원하지 못한
  경우도 같다. 모델 처리기 `PostgreDatabaseRestoreHandler.validateErrorCode`(:162-168)는 1을 "경고와 함께 끝남" 알림으로만 보내고 정상 종료하므로,
  그대로 쓰면 이 대화상자가 "완료 / 복원했습니다"를 보였다(실제 클라이언트로 확인하다 발견). 그래서 PostgreSQL 복원은 처리기를 상속한
  `CheckedPostgreRestore`로 실행해 종료 코드 1을 기억하고, 실행이 끝나면 "pg_restore가 오류를 보고했습니다(종료 코드 1)… 위의 메시지를 확인하십시오"로
  실패를 보인다. `--clean`으로 아직 없는 개체를 지우다 난 오류처럼 무시해도 되는 경우도 1이므로 문구에 그 점을 적었다.
  모델 처리기는 SWT에서도 쓰는 DBeaver 코드라 고치지 않았다. 다른 처리기(pg_dump, psql, mysqldump, mysql)는 기본 `validateErrorCode`(0이 아니면 실패)를 쓴다.
- **명령줄 미리 보기**: 처리기의 `getCommandLine`(보호 메서드)을 미리 보기용 하위 클래스로 불러, 실행할 명령을 그대로 보인다(MySQL은 출력·입력 리디렉션 `>`/`<`을 덧붙임).
  암호는 명령줄에 없다(`PGPASSWORD`, `MYSQL_PWD` 환경 변수). 도구가 그 폴더에 없으면 미리 보기 대신 "{도구}를 찾을 수 없습니다"를 보인다.

### 실패한 백업과 psql 오류 (2026-11-07)

- **빈 출력 파일**: 도구가 아무것도 출력하지 못하고 실패하면(로그인 실패, 잘못된 옵션) 0바이트 파일이 남았다. 출력을 파일로 옮기는 작업
  (`AbstractNativeToolHandler.DumpCopierJob.runDump`, DEFINER를 지우는 `MySQLDatabaseExportHandler.DumpFilterJob.runDump`)이 첫 바이트를 받기 전에
  파일을 만들고(`Files.newOutputStream`) 실패해도 그대로 두었기 때문이다. MySQL 백업은 덮어쓰기를 막으려고 시작할 때 파일이 있으면
  "Output file already exists"로 멈추므로(`MySQLDatabaseExportHandler.startProcessHandler`, DBeaver #11532) 같은 이름으로 다시 실행할 수 없었다.
  - 고친 것: 두 작업이 출력을 하나도 받지 못했으면 만든 파일을 지운다(`AbstractNativeToolHandler.deleteIfEmpty`). 그 파일은 시작할 때 없던 것을
    이 실행이 만든 것이므로(있으면 위 검사에서 멈춤) 지워도 잃는 것이 없다. 내용이 조금이라도 있는 파일(중간에 실패한 덤프)은 지우지 않는다.
    사용자가 확인할 수 있게 두고, 지우는 판단을 "내용이 없음" 하나로만 하려는 것이다. 그런 파일이 남으면 MySQL 재실행은 지금처럼 멈춘다.
  - pg_dump는 로컬 파일을 `--file`로 직접 쓰고(`PostgreDatabaseBackupHandler.fillProcessParameters`) 같은 이름이 있으면 덮어쓰므로 재실행이 막히지 않는다.
    원격 파일 시스템 경로일 때만 `DumpCopierJob`을 거치고 이 수정이 같이 적용된다. SWT 백업 마법사도 같은 처리기를 쓰므로 같이 고쳐진다.
- **psql 일반 형식 복원의 오류**: psql은 스크립트를 끝까지 실행하고 SQL 오류가 있어도 종료 코드 0으로 끝나므로 복원이 성공으로 보였다(DBeaver도 같음.
  `PostgreDatabaseRestoreHandler.getCommandLine`은 `ON_ERROR_STOP`을 넘기지 않고, 복원 처리기는 도구의 오류 출력을 작업 오류로 모으지 않는다 — `isLogInputStream() = false`).
  - 고친 것: PostgreSQL 복원 옵션에 **첫 오류에서 멈춤**(Stop at the first error)을 더했다. 일반 형식이면 `-v ON_ERROR_STOP=1`(psql은 멈추고 종료 코드 3),
    다른 형식이면 `pg_restore --exit-on-error`(종료 코드 1)를 설정의 추가 인수(`setExtraCommandArgs`, `PostgreNativeToolHandler`가 명령줄에 넣음)로 넘긴다.
    종료 코드 3은 모델 처리기가, 1은 `CheckedPostgreRestore`가 실패로 보인다. 일반 형식 안내 문구도 이 옵션만 psql에 적용된다고 고쳤다.
  - 결정: 기본값은 끔(DBeaver와 같음). `--clean`으로 만든 일반 스크립트(`--if-exists` 없이)는 아직 없는 개체를 지우는 첫 `DROP`에서 오류가 나므로
    켜 두면 빈 데이터베이스로의 복원이 바로 멈춘다. psql의 오류 줄을 글자로 찾아 실패로 보이는 방법은 도구의 언어(한국어 OS에서는 메시지 일부가 한국어)에
    따라 달라 쓰지 않았다.
- 확인(MariaDB 11.8.9 mysqldump, PostgreSQL 17.11 psql·pg_restore, `chr_fixes_editor`):
  - 백업 전용 로그인(`fx_bk`)으로 연결한 뒤 서버에서 그 로그인의 암호를 바꾸고 백업 → `Access denied`, "실패", 출력 폴더에 파일 없음.
    암호를 되돌리고 같은 대화상자에서 같은 이름으로 다시 실행 → "완료", 2,350바이트 덤프. DEFINER 제거를 켠 백업도 실패 시 파일 없음.
  - 일반 형식 복원(`CREATE TABLE fx_ok1; SELECT * FROM 없는 테이블; CREATE TABLE fx_ok2;`): 옵션을 켜면 명령줄에 `-v ON_ERROR_STOP=1`, 종료 코드 3으로 "실패",
    `fx_ok2` 없음. 끄면 예전처럼 "완료"와 두 테이블. 사용자 지정 형식은 명령줄에 `--exit-on-error`.
  - 화면: 한국어·영어(`ko-light-pg-restore-options.png`, `en-light-pg-restore-options.png` 등, `D:\Tools\_scratch\fixes-editor\run\shots\`).

### 고급 백업·복원 (2026-11-08)

SWT 백업 마법사의 개체 페이지와 설정 페이지에 있던 것을 같은 대화상자에 더했다(C-46, D-P18, D-P19, D-M13). 근거는 `263b8ddb45`의
ext.postgresql.ui `PostgreBackupWizardPageObjects`·`PostgreBackupWizardPageSettings`·`PostgreBackupAllWizardPage*`·`PostgreRestoreWizardPageSettings`·
`PostgreToolWizardPageSettings.createSecurityGroup`, ext.mysql.ui `MySQLExportWizardPageObjects`·`MySQLExportWizardPageSettings`, tasks.native.ui
`AbstractNativeToolWizardPage.createExtraArgsInput`이다. 설정 객체와 처리기는 모델에 그대로 있으므로 화면만 새로 만들었다.

- **백업 범위(PostgreSQL, General 페이지)**: 이 데이터베이스(pg_dump) / 서버의 전역 개체: 역할·테이블스페이스(pg_dumpall) / 전체 클러스터(pg_dumpall).
  pg_dumpall은 모델의 `pgDatabaseBackupAll` 작업 형식(`PostgreBackupAllSettings` + `PostgreDatabaseBackupAllHandler`)으로 실행한다.
  일반 SQL 스크립트만 쓰므로 형식·압축 칸을 끄고, 기본 파일 이름은 `globals-${host}-${timestamp}.sql`·`cluster-${host}-${timestamp}.sql`이다
  (데이터베이스가 아니라 서버의 백업). 복원은 복원 대화상자의 일반(SQL 스크립트) 형식(psql)으로 한다. SWT처럼 따로 메뉴를 두지 않은 것은
  같은 클라이언트·출력 폴더·작업 저장을 그대로 쓰고, 메뉴를 늘리지 않으려는 것이다.
- **개체 페이지(백업에만)**:
  - PostgreSQL 이 데이터베이스: 스키마 목록과, 목록에서 선택한 스키마의 테이블 목록(뷰 표시). 선택 규칙은 SWT와 같은 모델 클래스
    `PostgreDatabaseBackupSelection`이다. 모두 고르면 `-n`/`-t` 없이 데이터베이스 전체(확장 같은 데이터베이스 수준 개체 포함, `fullSchemaBackup`).
    스키마 전체는 `-n`, 일부 테이블은 `-t`이고, pg_dump가 `-t`가 있으면 `-n`을 무시하므로 일부 테이블을 고른 스키마마다 pg_dump를 따로 실행해
    `-1`, `-2` 번호를 붙인 파일에 쓴다(모델 `PostgreDatabaseBackupSettings.getOutputFile`). 명령줄 미리 보기는 실행마다 한 줄이다.
  - PostgreSQL 전역 개체: 역할·테이블스페이스 확인란. 둘 다 `--globals-only`, 하나만 `--roles-only`/`--tablespaces-only`.
  - PostgreSQL 전체 클러스터: 연결이 보이는 데이터베이스 목록. 해제한 것은 처리기가 `--exclude-database`로 뺀다(pg_dumpall에는 포함 옵션이 없음).
    모두 고르면 목록을 비워 저장해 제외 인수가 없다. 연결이 보이지 않는 데이터베이스는 늘 포함된다.
  - MySQL: 데이터베이스의 테이블(뷰 표시). 모두 고르면 지금처럼 데이터베이스 전체(`--routines`), 일부면 `mysqldump db 테이블…`(처리기가 루틴을 뺌, SWT와 같음).
    SWT처럼 여러 데이터베이스를 한 번에 고르는 것은 넣지 않았다(대화상자가 데이터베이스 하나에서 열림).
  - 목록은 대화상자를 열 때 읽는다(PostgreSQL 테이블은 스키마를 선택할 때, 진행 대화상자). 읽지 못하면 안내만 하고 전체 백업이 된다.
- **pg_dumpall 옵션(옵션 페이지, 범위에 따라 바뀜)**: 구조만(`--schema-only`, 전체 클러스터만), `--no-owner`, `--no-privileges`, 역할 암호 포함
  (기본 끔 = `--no-role-passwords`, 모델 처리기와 DBeaver의 기본값. 켜면 파일에 암호 해시가 들어가고 `pg_authid`를 읽으므로 슈퍼유저가 필요).
- **MySQL `--compress`**: SWT에 있던 "압축" 확인란.
- **도구 계정(Local Client 페이지)**: 연결과 다른 사용자로 도구를 실행한다(SWT `createSecurityGroup`의 인증 단추). PostgreSQL·MySQL의 백업·복원·스크립트
  실행에 있고 Oracle(SQL*Plus, 표준 입력 로그온)에는 없다. 암호는 처리기가 `PGPASSWORD`/`MYSQL_PWD` 환경 변수로만 넘긴다(명령줄에 없음).
  - 작업에 암호를 저장하지 않는다(`NativeBackupRestore.saveTask`). 모델은 프로젝트의 고정 로컬 키로 암호화해 `tasks.json`에 쓰는데, 그것은 보안 저장소가 아니다.
    사용자 이름은 저장한다. 예전 SWT 작업에 저장된 암호는 편집할 때 칸에 채워 그 실행에는 쓰지만, 다시 저장하면 빠진다.
  - 모델 처리기의 근본 수정: 도구 사용자가 있고 암호가 없으면 연결의 암호를 넘기지 않는다(`PostgreNativeToolHandler`, `MySQLNativeToolHandler`).
    전에는 연결 사용자의 암호를 다른 계정에 넘겼고, PGPASSWORD가 있으면 libpq가 pgpass 파일을 읽지 않는다. PostgreSQL은 이때 `--no-password`를 붙인다.
    도구의 표준 입력이 Java의 파이프라 암호를 물으면 실행이 멈추기 때문이다. MySQL은 `-p`를 넘기지 않으므로 묻지 않는다. 그래서 도구 계정이 있는
    저장된 작업은 클라이언트의 암호 파일(pgpass.conf, MySQL 옵션 파일)을 쓰거나 인증 오류로 끝난다.
  - MySQL의 "자격 증명 파일" 방식(`MySQLNativeCredentialsSettings.overrideCredentials`, 임시 `.my.cnf`에 암호를 씀)은 대화상자에 두지 않는다. 암호는 환경 변수로만 넘긴다.
- **추가 인수**: 명령줄에 붙는다(모델 `addExtraCommandArgs`가 공백마다 나누므로 인수 안에 공백을 넣을 수 없고, 대화상자가 연속 공백을 하나로 줄인다).
  "첫 오류에서 멈춤"은 지금처럼 추가 인수로 넘기되 사용자의 인수 뒤에 붙이고, 저장된 작업을 열 때 끝에서 떼어 확인란으로 되돌린다
  (그 칸이 없던 작업은 그 인수만 있었다). 추가 인수에 암호를 넣지 말라는 안내를 둔다(명령줄은 다른 프로그램이 볼 수 있음).
- **저장된 작업**: 위 설정은 모델 설정 객체의 키로 저장된다(SWT와 같은 키라 SWT 작업도 열린다). `pgDatabaseBackupAll` 작업도 Database Tasks에서 편집된다.
  작업의 대상은 대화상자를 연 데이터베이스다(pg_dumpall 작업도 `databaseObjects`에 그 데이터베이스를 둠).

## upstream 대비 변경점

- DBeaver 코드는 고치지 않았다. Chromite 번들(`model.admin`, `ui.swing`)에 클래스를 더하고 `ObjectExplorerPanel`에 메뉴 훅을 넣었다.
  예외: 2026-11-07에 빈 출력 파일을 지우도록 tasks.native `AbstractNativeToolHandler`와 ext.mysql `MySQLDatabaseExportHandler`를 고쳤다(SWT에도 적용, 위 "실패한 백업과 psql 오류").
  2026-11-08에 도구 계정에 암호가 없을 때 연결의 암호를 넘기지 않도록 ext.postgresql `PostgreNativeToolHandler`와 ext.mysql `MySQLNativeToolHandler`를 고쳤다(위 "고급 백업·복원").
- DBeaver CE에 없는 SQL Server 백업·복원을 더했다.
- PostgreSQL·MySQL은 DBeaver와 같은 처리기를 쓰되, 내려받는 클라이언트를 목록에서 뺐다(위 "로컬 클라이언트").

## 확인 (2026-11-06)

전체 빌드(`product-chromite-swt,product-chromite-swing`) 후 Swing 제품을 개인 작업 영역으로 띄워 EDT 테스트 에이전트로 조작했다. 화면은 한국어(OS 기본)와
`-nl en`, 라이트·다크로 찍었다(`D:\Tools\_scratch\backup-restore\shots\`).

- **SQL Server 2022(Linux 컨테이너), `chr_backup_restore`(약 67MB)**
  - 백업: 전체, 모두 덮어쓰기(INIT), 압축, 체크섬, 완료 후 확인 → 메시지 페이지에 "10 percent processed." … "100 percent processed.", 처리한 페이지 수,
    "The backup set on file 1 is valid."가 차례로 나옴. `sqlcmd`로 `RESTORE HEADERONLY`(Compressed = 1, 세트 이름 한국어 그대로), msdb의
    `has_backup_checksums = 1` 확인. 차등·트랜잭션 로그 백업(추가, NOINIT)도 성공.
  - 데이터를 바꾼 뒤(UPDATE, DELETE, INSERT) **복원**: REPLACE + 기존 연결 닫기 → 원래 3행으로 돌아옴, `user_access_desc = MULTI_USER`,
    `msdb.dbo.restorehistory`의 replace = 1(`sqlcmd`로 확인).
  - 기본 복원 계획: 전체(1) + 차등(2)을 고르고 차등에 이미 덮인 로그(3, LastLSN = 차등의 LastLSN)는 빼고, 그 뒤 로그(4)를 고름.
    새 이름 `chr_backup_restore_copy`로 복원: 파일 이름이 `chr_backup_restore_copy.mdf` / `_copy_log.ldf`로 바뀌고(MOVE),
    전체 → 차등 → 로그 세 문장이 NORECOVERY, NORECOVERY, RECOVERY로 실행됨. 로그로만 들어간 행까지 복원됨(`sqlcmd`). "새 쿼리 창에 작업 스크립트"로 같은 스크립트가 쿼리 창에 열림.
    복원 뒤 Object Explorer의 데이터베이스 목록에 새 데이터베이스가 나타남.
  - 약 1GB 데이터베이스(`chr_backup_restore_big`, 단순 복구 모델): 트랜잭션 로그 항목이 꺼지고 안내가 나옴. 백업 중 "10 percent processed."가
    실행 중에 나오고 진행률이 "10% 처리됨"으로 바뀜(메시지가 끝까지 기다리지 않고 들어옴). 취소 → "취소됨", msdb에 백업 세트 없음, 실행 중인 BACKUP 요청 없음.
- **PostgreSQL 17, MariaDB 11 — 실제 로컬 클라이언트로 확인(2026-11-06 밤)**. 클라이언트는 저장소에 넣지 않고 이 컴퓨터의 D: 드라이브에
  공식 압축 파일을 풀어 썼다(설치 프로그램·PATH·레지스트리 변경 없음):
  - PostgreSQL 17.11 — EDB "PostgreSQL Binaries" Windows x86-64 zip(`postgresql-17.11-5-windows-x64-binaries.zip`) → `D:\Tools\pgsql-17\pgsql`
    (pg_dump·pg_restore·psql 17.11, 서버도 17.11).
  - MariaDB 11.8.9 — downloads.mariadb.org `mariadb-11.8.9-winx64.zip`(공개 SHA-256과 일치) → `D:\Tools\mariadb-11.8`
    (mysqldump·mariadb-dump·mysql·mariadb, 서버 11.8.9).
  - 처음에는 클라이언트 폴더 목록이 비어 "로컬 클라이언트가 설치되어 있지 않습니다…"가 나오고, 찾아보기로 위 폴더를 고르면 `Local: <폴더>`가 선택되고
    명령줄 미리 보기가 채워짐. PostgreSQL은 `<폴더>\bin\pg_dump.exe`, MariaDB는 `<폴더>\bin\mysqldump.exe`를 고름(`MySQLUtils`는 mysqldump를 먼저 찾음).
  - PostgreSQL `chr_backup_restore`: 사용자 지정 형식 백업(`pg.backup`, `pg_restore --list`로 테이블·데이터·제약 조건 확인) → `docker exec psql`로 데이터를
    바꿈(UPDATE·DELETE·INSERT) → 복원(--clean) → 원래 3행(한글 값 포함)으로 돌아옴. 일반(SQL 스크립트) 형식 백업(`--format=p --clean`)과 psql 복원도 성공.
    pg_dump·pg_restore의 진행 로그(한국어 OS에서는 한국어)가 메시지 페이지에 줄 단위로 나옴.
  - MariaDB `chr_backup_restore`: 백업(`maria.sql`, DROP/CREATE/INSERT 확인) → 데이터를 바꿈 → 복원 → 원래 3행으로 돌아옴.
  - 같은 이름의 파일이 이미 있으면 MySQL 백업은 덮어쓰지 않고 "Output file already exists"로 실패함(모델 처리기의 의도된 동작).
  - 잘못된 암호: 앱이 연결한 뒤 서버에서 시험용 로그인의 암호를 바꿔 확인. pg_dump·mysqldump·mysql은 도구의 인증 오류
    (`password authentication failed`, `Access denied … (using password: YES)`)와 "실패" 상태를 보임. pg_restore는 고치기 전에는 "완료 / 복원했습니다"를
    보였고(위 "pg_restore 종료 코드 1"), 고친 뒤 "실패"와 안내 문구를 보임. 정상 복원은 그대로 "완료".
  - 다시 시작한 뒤에도 클라이언트 폴더가 남고(`drivers.xml`의 `clientHome`, 연결의 `home`) 대화상자를 열면 바로 선택되어 있음.
  - 화면: 한국어(OS 기본)와 `-nl en`, 라이트(`D:\Tools\_scratch\native-client\shots\`).
  - 남은 것: 실행이 끝날 때 메시지 페이지에 "Service 'com.seizia.chromite.runtime.ui.UIServiceSystemAgent' not found" 한 줄이 나온다.
    처리기가 긴 작업 알림 서비스(SWT `core`의 `UIServiceSystemAgentImpl`, 트레이 알림)를 찾는데 Swing에는 없고, 작업 중에는 `Log`의 출력이
    작업 로그 스트림으로 가기 때문이다. 고치려면 Swing용 알림 서비스가 필요해 이번에는 남겼다.
    실패한 백업은 0바이트 출력 파일을 남기고(모델 처리기), MySQL 백업은 그 파일 때문에 같은 이름으로 다시 실행할 수 없다.
    psql(일반 형식 복원)은 SQL 오류가 나도 종료 코드 0이라 성공으로 보인다(`ON_ERROR_STOP`을 쓰지 않음, DBeaver와 같음).
    → 2026-11-07 빈 파일은 지우고, 복원에 "첫 오류에서 멈춤" 옵션을 더함(위 "실패한 백업과 psql 오류").
- 회귀 테스트는 실행하지 않았다(요청 없음).

## 확인 (2026-11-08)

모델 부분을 `NativeBackupRestore`로 옮긴 뒤 같은 빌드의 Swing 제품(작업 공간 사본, `CHROMITE_DATA`)에서 앱 안 시험 에이전트가 대화상자를 열고 칸을 채워
확인을 눌렀다(화면 좌표·OS 입력 없음). PostgreSQL 17.11 로컬 클라이언트, 공용 컨테이너의 시험 DB `chr_agent_backup`(끝나고 지움).

- 백업(사용자 지정 형식): 명령줄 미리 보기가 전과 같고(`pg_dump.exe --verbose … --format=c --file …`), 메시지 페이지에 pg_dump 진행 줄, "완료",
  "데이터베이스 'chr_agent_backup'을(를) 백업했습니다."
- 같은 파일을 `--clean` 없이 이미 개체가 있는 DB로 복원: pg_restore 오류 줄들, 진행률 "실패", 끝에 `native_error_pg_restore_errors` 문구(종료 코드 1을
  실패로 보는 동작이 그대로).
- 화면: `D:\Tools\_scratch\chromite-agent-backup-tools\shots\ko-dialog-pg-backup.png`, `ko-dialog-pg-restore-end.png`.
- 회귀 테스트는 실행하지 않았다(요청 없음).

## 확인 (2026-11-08, 고급 백업·복원)

빌드 `-Pproduct-chromite-swing` BUILD SUCCESS. 그 Swing 제품을 개인 작업 공간과 `CHROMITE_DATA`(스크래치)로 한 번 띄우고, 같은 프로세스에 붙인 시험 에이전트가
Swing 구성 요소를 직접 다뤘다(화면 좌표·OS 입력 없음). 클라이언트는 D:의 PostgreSQL 17.11(`D:\Tools\pgsql-17\pgsql`), MariaDB 11.8.9(`D:\Tools\mariadb-11.8`).
PostgreSQL은 역할·전체 클러스터 복원이 공용 컨테이너의 다른 역할·데이터베이스를 건드리지 않도록 **이 확인만의 PostgreSQL 17.11 컨테이너**(끝나고 볼륨까지 지움)에
`chr_nba_one`(스키마 `s1`·`s2`·`"Mixed Case"`, 뷰, 확장 `pg_trgm`), `chr_nba_two`, `chr_nba_three`, 역할 `chr_nba_tool`(읽기 전용, 암호)·`chr_nba_scratch`를 만들었다.
MariaDB는 공용 컨테이너의 `chr_native_backup_adv`(테이블 a·b·c, 뷰 v, 프로시저)와 사용자 `chr_nba_tool`(끝나고 지움).

| # | 한 일 | 결과 |
|---|---|---|
| 1 | PostgreSQL 백업, 아무것도 바꾸지 않음 | 명령줄이 전과 같음(`-n`/`-t` 없음). 개체 페이지는 스키마 모두 체크 |
| 2 | `s1`은 `t1`만, `s2` 전체, `"Mixed Case"` 해제 | 명령줄 두 줄: `-t "s1.t1"` → `part-chr_nba_one-1.backup`, `-n "public" -n "s2"` → `-2.backup`. `pg_restore --list`로 각각 t1만, s2(t3·t4)+public만 확인 |
| 3 | 2의 파일을 `--clean` + 첫 오류에서 멈춤 + 추가 인수 `--single-transaction`으로 복원 | 명령줄 `… --single-transaction --exit-on-error --clean …`. 뷰 `s1.v1`이 t1에 기대 DROP 실패 → "실패"와 종료 코드 1 문구, 한 트랜잭션이라 바뀐 것 없음 |
| 4 | t1 행을 지우고 추가 인수 `--data-only --single-transaction`으로 복원 | "완료", 원래 3행(한글 값 포함) |
| 5 | 4를 작업으로 저장 → 다시 열기 | 작업의 추가 인수 `--data-only --single-transaction --exit-on-error`, 다시 열면 칸은 `--data-only --single-transaction`, "첫 오류에서 멈춤" 체크 |
| 6 | 도구 계정 `chr_nba_tool` + 암호, 일반 형식, 추가 인수 `--no-comments`, 2와 같은 선택 | 명령줄 `--username=chr_nba_tool --no-comments …`(암호 없음), "완료", 파일 두 개 |
| 7 | 6을 작업으로 저장 → 작업 속성 | `tool.user`는 암호화된 값, `tool.password`는 빈 값. 다시 열면 사용자 이름·선택(`s1`은 t1만, `"Mixed Case"` 해제)이 돌아오고 암호 칸은 비어 있음 |
| 8 | 7을 암호 없이 실행 | 명령줄에 `--no-password`, 곧바로 `fe_sendauth: no password supplied`로 "실패"(멈추지 않음, 연결의 암호를 넘기지 않음) |
| 9 | 범위 "서버의 전역 개체" | 파일 이름 `globals-${host}-${timestamp}.sql`, 형식·압축 꺼짐, `pg_dumpall … --globals-only --no-role-passwords --file …`. 역할만 → `--roles-only`, 둘 다 해제 → 미리 보기에 "역할이나 테이블스페이스를 하나 이상 고르십시오." "서버의 전역 개체를 백업했습니다." |
| 10 | 9를 작업으로 저장(`pgDatabaseBackupAll`) → 다시 열기 → 작업 관리자로 실행 | 다시 열면 범위·옵션이 그대로. 실행하면 셸 명령 승인 대화상자에 같은 명령줄, Run Once → 성공(마지막 실행 success) |
| 11 | 전역 개체 복원: `chr_nba_scratch`를 지우고 9의 파일을 복원 대화상자(일반 형식, psql)로 | 있는 역할의 "already exists" 오류 줄, "완료", 역할과 주석이 돌아옴 |
| 12 | 범위 "전체 클러스터", `chr_nba_three` 해제, `--no-owner` | `pg_dumpall … --no-owner --no-role-passwords --file … --exclude-database="chr_nba_three"`, 파일에 `chr_nba_three` 없음. 작업으로 저장 → 다시 열면 해제가 그대로 |
| 13 | 클러스터 복원: `chr_nba_two`·`chr_nba_three`를 지우고 12의 파일을 `postgres`에 복원 | `chr_nba_two`가 다시 생기고 marker = 2, `chr_nba_three`는 없음(제외됨). 있는 개체의 오류 줄은 psql이 넘어감 |
| 14 | `"Mixed Case"` 스키마만 백업 | `pg_restore --list`에 그 스키마와 `"Odd Table"`만. 미리 보기가 따옴표를 두 번 싸던 것을 고침(아래) |
| 15 | MariaDB: 뷰 표시, b·c 해제, `--compress`, 도구 계정 + 암호, 추가 인수 `--skip-dump-date` | `mysqldump … --compress … --skip-dump-date -u chr_nba_tool … chr_native_backup_adv a v > …`, "완료", 파일에 a와 v만(루틴 없음) |
| 16 | 15를 작업으로 저장 → 다시 열기 | `tool.password` 빈 값, 표시·선택·옵션·추가 인수가 돌아옴 |
| 17 | MariaDB 복원: a·v를 지우고 15의 파일을 추가 인수 `--default-character-set=utf8mb4`로 복원 | "완료", a 3행(한글 포함)과 v |

- 화면: 한국어(OS 기본)와 `-nl en`, `D:\Tools\_scratch\native-backup-advanced\shots\`(`ko-pg-objects-partial.png`, `ko-pg-scope-globals.png`,
  `ko-pg-client-account.png`, `ko-my-objects-partial.png`, `en-pg-objects-cluster.png`, `en-pg-client-account.png` 등).
- 확인 중 고친 것: 명령줄 미리 보기가 처리기가 이미 따옴표로 싼 인수(Windows의 스키마·테이블 이름, `escapeCLIIdentifier`)를 공백이 있다고 한 번 더 싸
  `""\"Mixed Case\"""`로 보였다. 이미 따옴표로 시작하고 끝나는 인수는 프로세스 실행기처럼 그대로 보이게 했다(`NativeBackupRestore.formatCommand`). 실제 실행은 원래 맞았다.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 백업·복원·스크립트 실행 대화상자 전체(같은 클래스), Chromite Agent `dumpDatabase`·`restoreDump`
  (같은 `getCommandLine`, 실행 하나면 한 줄로 전과 같음), 도구 계정이 있는 예전 SWT 작업(암호가 저장되어 있지 않으면 이제 연결의 암호를 쓰지 않음).

## 남긴 것

- Oracle 백업·복원(RMAN·Data Pump) — 대상 아님.
- SQL Server: 여러 파일로 나눠 쓰는 백업(스트라이프), URL(Azure Blob) 백업, 백업 암호화, 만료 날짜, 꼬리 로그 백업, 지정 시간 복원(STOPAT),
  `STANDBY` 복구 상태, 서버 파일 탐색기(`xp_dirtree`/`sys.dm_os_enumerate_filesystem`), "데이터베이스에서 복원"(msdb 기록의 백업 세트를 파일 대신 고르기).
- 2026-11-08 고급 백업·복원으로 채움: PostgreSQL 스키마·테이블 고르기, pg_dumpall(전역 개체, 전체 클러스터), MySQL 테이블 고르기, 도구 계정, 추가 인수.
  남은 것: MySQL 여러 데이터베이스를 한 백업에서 고르기, 도구 계정 암호를 OS 보안 저장소에 두고 저장된 작업이 쓰게 하기,
  MySQL 복원 로그 수준 Debug(지금은 보통·자세히), PostgreSQL 복원의 `--clean` 확인 대화상자(SWT에 있었음).

## 변경 이력

- 2026-11-06 최초 작성: SQL Server 백업·복원(T-SQL, 진행 메시지, 취소), PostgreSQL·MySQL 네이티브 도구 백업·복원(로컬 클라이언트, 명령줄 미리 보기).
  확인 중 고친 것: 단순 복구 모델 표시용 콤보 렌더러가 선택 항목(인덱스 -1)까지 흐리게 그리던 것, 복원 뒤 탐색기가 새 데이터베이스를 보이지 않던 것
  (연결 노드 새로 고침만으로는 탐색기가 자식을 다시 읽지 않음 → 데이터베이스 폴더에 REFRESH 이벤트), 긴 안내 글이 잘리던 것(줄 바꿈 글 상자),
  찾아보기로 더한 클라이언트 폴더가 다시 시작하면 사라지던 것(드라이버 수정됨 표시), 도구 로그의 스택 추적 줄.
- 2026-11-06 PostgreSQL 17.11·MariaDB 11.8.9 실제 로컬 클라이언트(D: 드라이브에 푼 공식 zip)로 백업·복원을 끝까지 확인.
  확인 중 고친 것: pg_restore가 오류로 끝난 복원(종료 코드 1, 로그인 실패 포함)을 성공으로 보이던 것(`CheckedPostgreRestore`, 문구 `native_error_pg_restore_errors`).
- 2026-11-07 실패한 백업이 0바이트 파일을 남겨 MySQL 재실행이 "Output file already exists"로 막히던 것(모델 처리기에서 빈 출력 파일을 지움),
  PostgreSQL 복원 옵션 "첫 오류에서 멈춤"(psql `-v ON_ERROR_STOP=1`, pg_restore `--exit-on-error`, 기본 끔). "실패한 백업과 psql 오류" 절 추가.
- 2026-11-08 스크립트 메뉴 "작업으로 저장…", 저장된 작업 편집(Database Tasks 보기), 같은 대화상자의 "네이티브 클라이언트로 스크립트 실행" 방식, 찾아보기로 더한 폴더를
  저장하는 코드를 `NativeBackupRestore.addLocalClient`로 옮김(연결 편집기와 함께 씀) ([swing-database-tasks.md](swing-database-tasks.md)).
- 2026-11-08 대화상자의 모델 부분(클라이언트 목록, 작업 형식, 명령줄 미리 보기, 처리기 실행, `CheckedPostgreRestore`)을 `model.admin`의
  `NativeBackupRestore`로 옮김. 메뉴 조건(`BackupRestoreCommands.canBackUp`)의 PostgreSQL·MySQL 부분도 `NativeBackupRestore.isSupported`로.
  Chromite Agent `dumpDatabase`·`restoreDump`가 같은 경로를 쓴다([ai-agent.md](ai-agent.md) "PostgreSQL·MySQL 백업·복원 도구").
- 2026-11-08 (`chromite/native-backup-advanced`) 고급 백업·복원(C-46, D-P18, D-P19, D-M13): PostgreSQL 백업 범위(pg_dumpall 전역 개체·전체 클러스터,
  `pgDatabaseBackupAll` 작업 저장·편집), 개체 페이지(스키마·테이블, 클러스터 데이터베이스, 역할·테이블스페이스, MySQL 테이블), pg_dumpall 옵션,
  MySQL `--compress`, 도구 계정(암호는 환경 변수로만, 작업에 저장하지 않음), 추가 인수. 모델 처리기: 도구 계정에 암호가 없으면 연결의 암호를 넘기지 않음
  (PostgreSQL은 `--no-password`). 명령줄 미리 보기: 실행마다 한 줄, 이미 따옴표로 싼 인수는 그대로. "고급 백업·복원"·"확인 (2026-11-08, 고급 백업·복원)" 절 추가.
