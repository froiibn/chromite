# SQL Server 파일·파일 그룹 관리 (2026-11-15, 앱 변경 #128)

## 목적

사용자 요청 "추가 가능한 데이터베이스에 대해 저장소를 관리할 수 있는 기능을 추가한다"(#127–#129). 전에는 SQL Server 저장소가 공간 사용량 화면
([swing-admin-tools.md](swing-admin-tools.md) "공간 사용량")에서 보기만 됐고 Object Explorer에는 파일·파일 그룹 노드가 없었다. 이제 데이터베이스 아래
Storage 폴더에서 파일 그룹과 파일을 보고, 파일 그룹을 더하고·이름을 바꾸고·지우고·기본값으로·읽기 전용으로 바꾸며, 데이터·로그 파일을 더하고·크기와
자동 증가를 바꾸고·이름을 바꾸고·지운다. #126(Altibase 테이블스페이스 관리, [altibase.md](altibase.md) "테이블스페이스 관리")과 같은 사용자 규칙을 따른다:
"현재 어플리케이션의 UI UX흐름의 관점을 해치지 않게 하고 불필요한 주저리주저리 나열하는 멘트는 넣지 않는다". 모든 명령은 기존 흐름 안에 있다:
Object Explorer 메뉴 → (설정 대화상자) → 스크립트 미리 보기(취소가 기본) → 열린 트랜잭션 질문 → 실행 → 트리 갱신. 새 창 종류·마법사·설명 문단은 없다.

## 화면

트리(데이터베이스 노드 아래, Schemas·Database triggers 다음):

```
chr_ts_app
├─ Schemas
├─ Database triggers
└─ Storage                 (저장소)
   ├─ Filegroups           (파일 그룹)
   │  ├─ PRIMARY
   │  │  └─ Files          (파일)
   │  └─ fg1
   │     └─ Files
   │        └─ fg1_f1
   └─ Log Files            (로그 파일)
      └─ chr_ts_app_log
```

| 위치 | 메뉴 항목 | 흐름 | 문장 |
|---|---|---|---|
| Storage > Filegroups 폴더 | New Filegroup… / 새 파일 그룹 | 이름 대화상자(다른 종류의 이름만 받는 New와 같음) → 미리 보기 | `ALTER DATABASE d ADD FILEGROUP fg` |
| 파일 그룹 노드(PRIMARY 제외) | F2 Rename / Delete(기존 명령) | 이름 입력 → 미리 보기 / 삭제 확인 → 미리 보기 | `… MODIFY FILEGROUP fg NAME = new`, `… REMOVE FILEGROUP fg` |
| 파일 그룹 노드 | Set as Default Filegroup / 기본 파일 그룹으로 설정 | 트리거 사용/사용 안 함과 같음: 이미 기본이거나 파일이 없으면 꺼짐, 미리 보기가 확인 | `… MODIFY FILEGROUP fg DEFAULT` |
| 파일 그룹 노드(PRIMARY 제외) | Make Read-Only / Make Read-Write (읽기 전용으로 전환 / 읽기 쓰기로 전환) | 같음: 지금 상태의 항목과 파일이 없는 파일 그룹의 항목은 꺼짐 | `… MODIFY FILEGROUP fg READ_ONLY / READ_WRITE` |
| 파일 그룹의 Files 폴더(읽기 전용 파일 그룹 제외) | New File… / 새 파일 | 이름·파일·크기(MB)·자동 증가·증가(값 + MB/%)·최대 크기(MB) 대화상자 → 미리 보기 | `ALTER DATABASE d ADD FILE (NAME = N'…', FILENAME = N'…'[, SIZE = nMB][, FILEGROWTH = n(MB|%)][, MAXSIZE = nMB]) TO FILEGROUP fg` |
| Storage > Log Files 폴더 | New Log File… / 새 로그 파일 | 같은 대화상자 → 미리 보기 | `ALTER DATABASE d ADD LOG FILE (…)` |
| 파일 노드(데이터·로그, 읽기 전용 제외) | File Settings… / 파일 설정 | 지금 값으로 채운 대화상자(바뀐 칸만) → 미리 보기 | `… MODIFY FILE (NAME = N'f'[, SIZE = nMB][, FILEGROWTH = …][, MAXSIZE = nMB | UNLIMITED])` |
| 파일 노드(읽기 전용 제외) | F2 Rename / Delete(기존 명령, 주 데이터·로그 파일은 Delete 꺼짐) | 이름 입력 → 미리 보기 / 삭제 확인 → 미리 보기 | `… MODIFY FILE (NAME = N'old', NEWNAME = N'new')`, `… REMOVE FILE f` |

- 대화상자: 크기·증가·최대 크기는 정수 MB(칸 이름에 "(MB)"). 새 파일의 빈 칸은 문장에서 빠진다(서버 기본: model 데이터베이스의 크기, 64 MB 증가, 제한 없음).
  파일 칸은 서버의 기본 폴더(`SERVERPROPERTY('InstanceDefaultDataPath' | 'InstanceDefaultLogPath')`)로 채워지고, 사용자가 파일 칸을 고치기 전까지 이름을 따라간다
  (`<폴더><이름>.ndf`, 로그는 `.ldf`; SSMS 새 파일과 같은 이름). 자동 증가 확인란을 끄면 증가·최대 크기 칸이 꺼지고 `FILEGROWTH = 0`이 된다.
  파일 설정에서 최대 크기를 비우면 `MAXSIZE = UNLIMITED`다. 아무것도 바꾸지 않으면 속성 편집과 같은 "실행할 문장이 없다" 알림이다.
  대화상자는 메인 창 가운데에 뜬다(2026-11-16 앱 변경 #132; 새 파일·파일 설정은 이미 폼 너비 480보다 넓어 크기는 그대로). 크기·증가·최대 크기 칸에는
  숫자만 들어간다(앱 변경 #133). 파일 설정의 지금 크기·증가에 소수가 있으면(예: 4.5) 그대로 보이고, 그대로 두면 문장이 되지 않는다
  ([swing-create-objects.md](swing-create-objects.md) "대화상자 크기와 위치", "숫자 칸").
- 속성: 파일 그룹은 이름·종류·기본값·읽기 전용·파일 수, 파일은 논리 이름·파일 이름(실제 경로)·종류·크기(MB)·자동 증가("64 MB", "10%", "없음")·최대 크기("100 MB",
  "제한 없음")·상태·읽기 전용.
- 시스템 데이터베이스(master, tempdb, model, msdb)와 오프라인 데이터베이스에서는 노드는 보이지만 명령은 없다(아래 설계 결정).
- 파일 축소(`DBCC SHRINKFILE`)는 넣지 않았다(아래 설계 결정).

## 구조

| 위치 | 내용 |
|---|---|
| `ext.mssql` `model/SQLServerStorageDDL`(새) | 확인한 문법으로 문장을 만드는 한 곳: 파일 그룹 추가·이름·삭제·기본값·읽기 전용, 파일 추가·변경·이름·삭제 |
| `ext.mssql` `model/SQLServerFilegroup`(새) | `[db].sys.filegroups` 행 + 파일 수, 파일 목록 캐시, 지원 판단(행 데이터 파일 그룹, 주 파일 그룹), 새로 고침이 그 데이터베이스의 파일 그룹 행을 모두 다시 읽음 |
| `ext.mssql` `model/SQLServerDatabaseFile`(새) | `[db].sys.database_files` 행, 페이지 → MB, 지원 판단(읽기 전용, 주 파일), 새로 고침이 자기 행만 다시 읽음 |
| `ext.mssql` `SQLServerDatabase` | Storage 폴더의 두 목록(`getFilegroups`, `getLogFiles`, 폴더를 열 때만 읽음), `supportsStorageChanges`, 기본 폴더 읽기, 새로 고침에서 두 캐시 비우기 |
| `ext.mssql` `SQLServerDataSource` | 연결할 때 이미 읽던 엔진 에디션을 기억하고 `supportsDatabaseFiles`(1–4, Babelfish 제외) |
| `ext.mssql` `edit/SQLServerStorageObjectEditor`(새), `SQLServerFilegroupManager`(새), `SQLServerDatabaseFileManager`(새) | 객체 관리자: 만들기·삭제·이름 바꾸기 명령이 자동 커밋으로 저장되게 함(`OPTION_AVOID_TRANSACTIONS`), ADD 직후 새 행 읽기 |
| `ext.mssql` `plugin.xml`, `bundle(_ko).properties`, `SQLServerMessages(_ko)` | Storage 폴더(`visibleIf="object.dataSource.supportsDatabaseFiles()"`), 관리자 두 개, 노드·속성 이름, "없음"·"제한 없음" |
| `model.admin` `ObjectMaintenanceActions` | 동작 `FILEGROUP_DEFAULT`, `FILEGROUP_READ_ONLY`, `FILEGROUP_READ_WRITE`, `DATABASE_FILE_SETTINGS`(설정이 필요한 명령), `changesFilegroupState` |
| `ui.swing` `SQLServerStorageDialogs`(새) | 새 파일·새 로그 파일·파일 설정 대화상자(`DesignerDialogs.showDialog` 틀, 자기 칸 묶음) |
| `ui.swing` `NewObjectDialogs`, `ObjectMaintenanceCommands` | 대화상자 연결(디스패치 줄), 메뉴 네 항목, 실행 뒤 그 노드 다시 읽기 |
| `ui.swing` `CreateObjectMessages`, `ObjectMaintenanceMessages`(+`_ko`) | 화면 문구(아래 "문구") |

## 확인한 문법 (SQL Server 2022 CU27 16.0.4315.2 Developer, Linux; `127.0.0.1:1433`의 시험 서버, 임시 데이터베이스 `chr_ts_grammar`·`chr_ts_app`와 임시 로그인 `chr_ts_low`만, 끝에 모두 지움)

자료: `D:\Tools\_scratch\mssql-storage\evidence\0*-*.sql`·`.out`(JDBC, 드라이버 13.4.0, 앱과 같은 드라이버).

| 문장 | 서버의 답 |
|---|---|
| `ALTER DATABASE d ADD FILEGROUP [fg one]` | 됨(`sys.filegroups` 행, `type` FG). 같은 이름: 5035 "Filegroup 'fg one' already exists" |
| `… MODIFY FILEGROUP fg DEFAULT` / `READ_ONLY`(파일 없는 파일 그룹) | 5050 "Cannot change the properties of empty filegroup" |
| `… ADD FILE (NAME = N'f1', FILENAME = N'/var/opt/mssql/data/f1.ndf', SIZE = 4MB, FILEGROWTH = 2MB, MAXSIZE = 32MB) TO FILEGROUP fg` | 됨(크기 512, 증가 256, 최대 4096 페이지) |
| `… FILEGROWTH = 10%, MAXSIZE = UNLIMITED` / NAME·FILENAME만 / `SIZE = 5120KB, FILEGROWTH = 0` | 됨(증가 10·백분율, 최대 -1 / 1024 페이지·증가 8192(64 MB)·최대 -1 / 640 페이지·증가 0) |
| `SIZE = 4.5MB` | 102 구문 오류: 정수만 |
| 같은 논리 이름 / 이미 있는 실제 파일 / 상대 경로 `FILENAME = N'x.ndf'` | 1828 "logical file name … already in use" / 5170 "already exists" / 5105 "file activation error" |
| `… ADD LOG FILE (NAME = …, FILENAME = …, SIZE = 4MB, FILEGROWTH = 4MB, MAXSIZE = 64MB)` | 됨(종류 1). `MAXSIZE = UNLIMITED`인 로그는 최대 268435456 페이지(2 TB)로 기록 |
| `… MODIFY FILE (NAME = N'f1', SIZE = 8MB, FILEGROWTH = 3MB, MAXSIZE = 64MB)` | 됨: 세 값을 한 문장에 함께 바꿀 수 있음. 따로 바꾸는 것(`SIZE`, `FILEGROWTH = 10%`, `MAXSIZE = UNLIMITED`, `FILEGROWTH = 0`)도 됨 |
| `… MODIFY FILE (… SIZE = 6MB)`(지금보다 작거나 같음) | 5039 "Specified size is less than or equal to current size" |
| `… MAXSIZE = 5MB`(크기보다 작음) / `FILEGROWTH = 1024MB, MAXSIZE = 60MB` | 5040 "Size of file … is greater than MAXSIZE" / 5169 "FILEGROWTH cannot be greater than MAXSIZE" |
| `… MODIFY FILE (NAME = N'f5', NEWNAME = N'f5b')`, 주 데이터 파일, 한글 이름 `N'파일_한글'` | 됨(메시지 5021). 있는 이름으로: 1828 |
| `… MODIFY FILEGROUP [fg one] NAME = [fg two]` | 됨. 주 파일 그룹: 5012 "The name of the primary filegroup cannot be changed" |
| `… MODIFY FILEGROUP fg READ_ONLY / READ_WRITE`(옛 철자 `READONLY`·`READWRITE`도) | 됨(파일 그룹과 그 파일의 `is_read_only`). 다시: 5045 "already has the 'READ_ONLY' property". 주 파일 그룹: 5047. **다른 세션이 그 데이터베이스를 쓰면** 5070 "Database state cannot be changed while other users are using the database" |
| 기본 파일 그룹을 읽기 전용으로, 읽기 전용을 기본으로 | 둘 다 됨 |
| 읽기 전용 파일 그룹의 파일: 추가·`MODIFY FILE`·`NEWNAME` / `REMOVE FILE` | 5048 "The filegroup is read-only" / 5055 "The file is read-only". 파일 그룹 이름 바꾸기는 됨 |
| `… REMOVE FILE f`(빈 파일) | 됨(5044, 서버 디스크의 파일도 지움). 데이터가 든 파일: 5042 "cannot be removed because it is not empty". 주 로그 파일: 5020 "The primary data or log file cannot be removed" |
| 식별자 `REMOVE FILE [a]]b]`, 문자열 `N'q''x'` | 됨 |
| `… REMOVE FILEGROUP fg`(파일 있음 / 빈 / PRIMARY) | 5042 / 됨 / 5042 |
| `BEGIN TRANSACTION; ALTER DATABASE …` | 226 "ALTER DATABASE statement not allowed within multi-statement transaction", 트랜잭션은 그대로(`@@TRANCOUNT` 1) |
| 수동 커밋 세션(JDBC `setAutoCommit(false)` = 암시적 트랜잭션), 열린 트랜잭션 없음 | 됨, `@@TRANCOUNT` 0(문장이 트랜잭션을 열지 않음) |
| 같은 세션, `SELECT`로 이미 열린 암시적 트랜잭션 | 226, 트랜잭션은 그대로(커밋도 롤백도 되지 않음) |
| 다른 세션이 그 데이터베이스에서 트랜잭션을 열고 행을 바꿈 | `ADD FILEGROUP`·`MODIFY FILE`·`REMOVE FILEGROUP`은 기다리지 않고 됨, 그 트랜잭션은 그대로(`@@TRANCOUNT` 1) |
| 낮은 권한(데이터베이스 사용자, ALTER 없음) | `sys.filegroups`·`sys.database_files` 읽힘. `ADD FILEGROUP`·`MODIFY FILE`: 5011 "User does not have permission to alter database …" |
| 데이터베이스 사용자가 아닌 로그인 | `[db].sys.filegroups`: 916 "not able to access the database"(다른 폴더와 같음). `sys.master_files`는 빈 결과 |
| `SERVERPROPERTY('EngineEdition')` | 3(Developer). `InstanceDefaultDataPath`·`LogPath` = `/var/opt/mssql/data/` |

## 설계 결정

- **기존 흐름만.** 만들기는 폴더의 New &lt;종류&gt;…(파일 그룹은 이름만 받는 기존 대화상자, 파일은 대화상자 길), 삭제·이름 바꾸기는 기존 Delete·F2, 상태·설정
  명령은 유지 관리 명령(트리거·#126 테이블스페이스와 같은 메뉴 자리, 같은 미리 보기·트랜잭션 질문·격리 실행)이다. 대화상자에 안내 문구가 없다.
- **트리 자리와 이름.** SQL Server 데이터베이스 노드의 자식은 Schemas와 Database triggers 폴더다. 그 다음에 Oracle·Altibase처럼 "Storage" 폴더를 두고, 그 안을
  SSMS의 데이터베이스 속성 "Files"·"Filegroups"와 같은 나눔으로 Filegroups(각각 Files)와 Log Files로 나눴다. 로그 파일은 파일 그룹에 속하지 않기 때문이다.
  목록은 폴더를 열 때만 그 데이터베이스의 카탈로그 뷰(`[db].sys.filegroups`, `[db].sys.database_files`, 다른 폴더와 같은 `SQLServerUtils.getSystemTableName`)에서 읽는다.
  열지 않으면 질의가 없다.
- **트랜잭션 밖에서 실행.** `ALTER DATABASE`는 사용자 트랜잭션 안에서 실행되지 않는다(226). 만들기·삭제·이름 바꾸기는 `CREATE DATABASE`(`NewObjectDialogs`)처럼
  명령이 `OPTION_AVOID_TRANSACTIONS`를 켜서 저장 경로가 세션의 커밋 모드를 바꾸지 않고 자동 커밋으로 실행한다(`SQLServerStorageObjectEditor`). 유지 관리 명령은
  기존대로 격리된 실행 문맥에서 실행한다(수동 커밋이어도 새 세션이라 열린 트랜잭션이 없어 됨, 위 표). 실행 전 기존 질문이 연결의 열린 트랜잭션을 묻는다.
  수동 커밋 연결의 Main 세션에는 메타데이터 읽기가 연 암시적 트랜잭션(데이터 변경 없음)이 있을 수 있다(아래 확인). 기존 질문은 이것을 묻지 않고, 자동 커밋으로 바꿀 때
  끝난다(`CREATE DATABASE`와 같은 길). 데이터를 바꾼 트랜잭션이 질문 없이 남는 경우는 없고, 남더라도 서버가 226으로 거절하며 그 트랜잭션은 커밋도 롤백도 되지 않는다(위 표). SQL Server의 DDL은 트랜잭션을
  바로 커밋하지 않으므로(JDBC `dataDefinitionCausesTransactionCommit` = false) 미리 보기 머리글에 "DDL 문을 바로 커밋" 줄은 없다(다른 SQL Server DDL과 같음).
- **무엇을 할 수 있는지는 카탈로그 값으로.** 서버가 거절하는 것에는 명령을 두지 않는다: 주 파일 그룹(`data_space_id` 1, 서버가 정한 번호)의 이름·읽기 전용·삭제,
  파일 없는 파일 그룹의 기본값·읽기 전용(꺼짐), 읽기 전용 파일 그룹의 새 파일과 그 파일의 설정·이름·삭제, 주 데이터 파일(`file_id` 1)과 주 로그 파일(`file_id` 2)의
  삭제. FILESTREAM·메모리 최적화 파일 그룹(`type` FD·FX)은 보이기만 한다(파일이 디렉터리이고 크기가 없어 같은 대화상자로 다룰 수 없고, Linux 시험 서버에서 확인할 수
  없었다). 새 파일 그룹은 행 데이터 파일 그룹만 만든다.
- **시스템 데이터베이스에는 명령이 없다.** master·tempdb·model·msdb(`database_id` 1–4, 서버가 정한 번호)는 서버의 것이고, tempdb 파일의 크기·위치 변경은 서버를 다시
  시작해야 적용된다. 이것을 한 줄로 알릴 수 없어(설명 문구 없음 규칙) 시스템 데이터베이스의 노드는 보기만 한다. 공유 시험 서버의 시스템 데이터베이스는 바꾸지 않았다.
  오프라인 등 ONLINE이 아닌 데이터베이스에도 명령이 없다.
- **에디션.** Storage 폴더와 명령은 SQL Server 자체(`SERVERPROPERTY('EngineEdition')` 1–4)에만 있다. 값은 데이터 원본이 연결할 때 이미 읽던 것(Synapse 판단)을
  기억한 것이다. Azure SQL Database(5)는 파일 명령이 없고, Managed Instance(8)는 파일 경로를 받지 않으며, Synapse(6, 11)·Azure SQL Edge(9)·Babelfish도 다르므로
  폴더가 보이지 않는다(공간 사용량 화면은 그대로 파일을 보인다). 시험 서버에는 3만 있어 다른 에디션은 확인하지 못했다.
- **권한.** 노드는 그 데이터베이스의 카탈로그 뷰를 읽을 수 있는 사용자면 보인다. ALTER 권한이 없으면 서버의 5011 오류가 다른 실패한 변경처럼 보인다(미리 권한을 묻지 않음).
- **새로 고침.** 기본 파일 그룹을 바꾸면 이전 기본 파일 그룹의 값도 바뀌므로 파일 그룹 노드의 새로 고침은 그 데이터베이스의 파일 그룹 행을 모두 다시 읽고 바뀐 다른 파일 그룹에
  알린다. 파일 설정 뒤에는 그 파일의 행만 다시 읽는다(폴더의 F5는 데이터베이스 전체를 다시 읽는다). 파일을 더하거나 지우면 파일 그룹의 파일 수(기본값·읽기 전용
  가능 여부)를 같은 세션에서 다시 읽는다.
- **크기 단위.** 정수 MB(서버가 소수 MB를 거절함). 지금 크기가 정수 MB가 아니면 소수로 보이고, 그 칸을 고치지 않으면 문장에 들어가지 않는다. 증가는 값 칸과 단위
  콤보(MB, %) 하나다. 서버가 거절하는 값(작아지는 크기 5039, 크기보다 작은 최대 크기 5040, 최대 크기보다 큰 증가 5169)은 대화상자에서 막지 않고 서버 오류로 보인다.
- **파일 축소는 넣지 않음.** 기존 유지 관리 화면에 축소가 없다. `DBCC SHRINKFILE`은 그 데이터베이스 안에서만 실행되고(세션의 데이터베이스를 바꾸지 않는다는 규칙) 대상 크기·
  옵션을 받는 대화상자와 오래 걸리는 실행이 필요해, 한 명령과 작은 대화상자 하나로 들어가지 않는다.
- **새 파일의 파일 칸.** 서버는 상대 경로를 거절한다(5105). 그래서 서버의 기본 폴더 + 논리 이름 + 확장자로 채운다(서버가 알려 주지 않는 2012 이전 서버는 빈 칸).

## 확인 (2026-11-15, 이 브랜치 빌드, 사설 작업 공간, 앱 안 테스트 에이전트, 연결은 암호를 저장하지 않음)

시험 데이터베이스 `chr_ts_app`(파일 그룹 `chr_ts_fg_data`에 파일 `chr_ts_app_d1`과 행이 든 테이블, 빈 `chr_ts_fg_empty`). 카탈로그는 명령마다 `sys.filegroups`·
`sys.database_files`를 따로 읽어 비교했다(`D:\Tools\_scratch\mssql-storage\evidence\cat-*.out`, `tx-*.out`).

| 명령 | 미리 보기의 문장 | 서버 결과 | 트리 |
|---|---|---|---|
| New Filegroup…(대화상자에서 취소 / 미리 보기에서 취소) | — / `ALTER DATABASE chr_ts_app ADD FILEGROUP chr_ts_fg_new` | 바뀌지 않음 | 바뀌지 않음 |
| New Filegroup… 실행 | 같음 | 행 생김(`data_space_id` 4, FG) | 노드 생김, 파일 0이라 기본값·읽기 전용 항목 꺼짐 |
| 같은 이름 | 같은 모양 | 모델의 확인 "Object 'chr_ts_fg_data' already exists"(서버에 가기 전, 기존 공통 동작) | 바뀌지 않음 |
| New File…(이름 → 파일 칸 `/var/opt/mssql/data/chr_ts_app_n1.ndf`, 4 / 1 MB / 32) | `… ADD FILE (NAME = N'chr_ts_app_n1', FILENAME = N'/var/opt/mssql/data/chr_ts_app_n1.ndf', SIZE = 4MB, FILEGROWTH = 1MB, MAXSIZE = 32MB) TO FILEGROUP chr_ts_fg_new` | 파일 생김, 파일 그룹의 파일 수 1 | 파일 노드(비어 있던 Files 폴더는 접힌 채 펼침 표시가 생김: Swing 트리의 기존 동작) |
| New Log File…(이름만) | `… ADD LOG FILE (NAME = N'chr_ts_app_log2', FILENAME = N'/var/opt/mssql/data/chr_ts_app_log2.ldf')` | 로그 파일 생김(8 MB, 64 MB 증가) | 노드 생김 |
| 잘못된 값(크기 4.5, 증가 x %) | — | — | 오류 줄 "Enter the sizes in MB." / "Enter the growth as a whole number." |
| File Settings… 바꾸지 않고 OK | — | — | "This change produces no SQL statement…" 알림(속성 편집과 같음) |
| File Settings…(8 / 10 % / 최대 비움), 자동 증가 끄기 | `… MODIFY FILE (NAME = N'chr_ts_app_n1', SIZE = 8MB, FILEGROWTH = 10%, MAXSIZE = UNLIMITED)`, `… (NAME = …, FILEGROWTH = 0)` | 1024 페이지·10 %·-1, 증가 0 | 속성: 8, "10%", "Unlimited" → "None" |
| File Settings…(크기 6, 지금 8) | `… SIZE = 6MB` | 5039 "MODIFY FILE failed. Specified size is less than or equal to current size." 오류 창 | 바뀌지 않음 |
| F2 파일 | `… MODIFY FILE (NAME = N'chr_ts_app_n1', NEWNAME = N'chr_ts_app_n1r')` | 이름 바뀜(실제 파일은 그대로) | 노드 이름 바뀜 |
| Set as Default Filegroup | `… MODIFY FILEGROUP chr_ts_fg_new DEFAULT` | 새 기본, PRIMARY 0 | 새 노드의 항목 꺼짐, PRIMARY의 항목 켜짐(다시 읽은 행) |
| Make Read-Only / Make Read-Write | `… READ_ONLY` / `… READ_WRITE` | 파일 그룹과 파일의 `is_read_only` 1 / 0 | 읽기 전용 동안 그 파일의 File Settings 없음, Rename·Delete 꺼짐, Files 폴더의 New File 없음 |
| F2 파일 그룹 | `… MODIFY FILEGROUP chr_ts_fg_new NAME = chr_ts_fg_new2` | 바뀜 | 바뀜 |
| Delete 파일(기본 파일 그룹의 유일한 파일) | `… REMOVE FILE chr_ts_app_n1r` | 5031 "Cannot remove the file … because it is the only file in the DEFAULT filegroup." 오류 창 | 바뀌지 않음 |
| Delete 빈 파일, 로그 파일 | `… REMOVE FILE chr_ts_app_n1r`, `… REMOVE FILE chr_ts_app_log2` | 지워짐 | 노드 빠짐, 파일 그룹의 파일 수 0 |
| Delete 데이터가 든 파일 / 파일이 있는 파일 그룹 | `… REMOVE FILE chr_ts_app_d1` / `… REMOVE FILEGROUP chr_ts_fg_data` | 5042 "…cannot be removed because it is not empty." 오류 창 | 바뀌지 않음 |
| Delete 빈 파일 그룹 둘 | `… REMOVE FILEGROUP chr_ts_fg_new2` 등 | 지워짐 | 노드 빠짐 |
| 읽기 전용 연결 | — | New Filegroup·Set as Default·Delete·File Settings·F2 모두 "MS does not allow changing database objects…" | — |
| 낮은 권한 로그인(`chr_ts_low`, 데이터베이스 사용자, ALTER 없음) | `… ADD FILEGROUP chr_ts_fg_low`, `… MODIFY FILE (… SIZE = 5MB)` | 노드는 다 보임. 5011 "User does not have permission to alter database 'chr_ts_app'…" 오류 창 | 바뀌지 않음 |

- 메뉴: PRIMARY는 Set as Default Filegroup(기본일 때 꺼짐)만, 읽기 전용 항목과 Rename·Delete 꺼짐. 주 로그 파일은 File Settings·Rename만(Delete 꺼짐). 빈 파일 그룹은 세 상태 항목이 모두 꺼짐.
  master: Storage 아래 PRIMARY·mastlog가 보이지만 New Filegroup·New Log File·상태·설정 항목이 없고 Rename·Delete가 꺼짐.
- 열린 트랜잭션(수동 커밋 연결 MSMAN, 쿼리 창 `INSERT INTO chr_ts_app.dbo.chr_ts_t VALUES (99)`): New Filegroup… 실행 → "Uncommitted Transactions"
  (취소가 기본, 목록에 "MSMAN (SQLQuery1.sql) - open transaction with data changes").
  - 취소 → 파일 그룹 없음, 99는 커밋되지 않은 채(`NOLOCK`으로만 보임), 쿼리 창 세션 `open_transaction_count` 1.
  - 롤백 → 99 없어지고 파일 그룹 생김, 열린 트랜잭션 없음.
  - 새 INSERT 98 뒤 File Settings…(유지 관리 경로) → 같은 질문 → 커밋 → 98 남고 증가 3 MB.
  - 질문 전 Main 세션에도 `open_transaction_count` 1이 있었다(수동 커밋 연결의 메타데이터 읽기가 연 암시적 트랜잭션, 데이터 변경 없음; 기존 질문은 이것을 묻지 않는다).
    이런 세션에서 `ALTER DATABASE`는 226으로 거절되므로(위 문법 표) 객체 관리자 명령이 `OPTION_AVOID_TRANSACTIONS`로 자동 커밋으로 실행하는 것이 필요하다. 같은 연결에서
    열린 트랜잭션이 없을 때 Delete(파일 그룹)는 질문 없이 실행됐다.
- 연결 새로 고침(F5) 뒤 New Log File…, 쿼리 창의 `CREATE DATABASE` 뒤 File Settings…, 연결 끊기·다시 연결 뒤 Delete가 모두 위와 같이 됐다.
- 전과 같은 명령(같은 빌드, SQL Server): New Database…(미리 보기 `CREATE DATABASE chr_ts_newdb2`, 취소), 테이블 F2(`EXEC chr_ts_app.sys.sp_rename …`), 트리거 사용 안 함
  (`ALTER TABLE … DISABLE TRIGGER chr_ts_trg`, `is_disabled` 1), 뷰 Delete(`USE chr_ts_app; DROP VIEW dbo.chr_ts_v; USE master;`).
- #291 Altibase(시험 서버 7.3, SYS): Storage > Tablespaces의 New Tablespace… 대화상자가 열리고 미리 보기 `CREATE TABLESPACE CHR_TS_MSSQL_SMOKE DATAFILE 'chr_ts_mssql_smoke.dbf' SIZE 4M`
  (DDL 커밋 머리글 줄 있음)에서 취소했다. 시스템 테이블스페이스에는 새 항목이 없다. Altibase에서는 아무것도 실행하지 않았다.
- 화면: 영어·한국어, 라이트·다크, 100 %·150 %에서 메뉴, 대화상자, 미리 보기, 삭제 확인, 트리를 캡처했다(`D:\Tools\_scratch\mssql-storage\sheets\captures\`).
- 회귀 테스트는 작성·실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: SQL Server 데이터베이스 노드의 자식 목록과 새로 고침, 유지 관리 메뉴, New … 대화상자 고르기.

## 문구

| 키 | English | 한국어 |
|---|---|---|
| `tree.storage.node.name` / `.tip` | Storage / Filegroups and files of the database | 저장소 / 데이터베이스의 파일 그룹과 파일 |
| `tree.filegroups.node.name` / `.tip`, `tree.filegroup.node.name` | Filegroups / Filegroups of the database, Filegroup | 파일 그룹 / 데이터베이스의 파일 그룹, 파일 그룹 |
| `tree.dbFiles.node.name` / `.tip`, `tree.dbFile.node.name` | Files / Data files of the filegroup, File | 파일 / 파일 그룹의 데이터 파일, 파일 |
| `tree.logFiles.node.name` / `.tip`, `tree.logFile.node.name` | Log Files / Transaction log files of the database, Log File | 로그 파일 / 데이터베이스의 트랜잭션 로그 파일, 로그 파일 |
| 속성 이름(`meta.…SQLServerFilegroup.*`, `…SQLServerDatabaseFile.*`) | Name, Type, Default, Read-only, Files; Logical name, File name, Type, Size (MB), Autogrowth, Maximum size, State, Read-only | 이름, 종류, 기본값, 읽기 전용, 파일 수; 논리 이름, 파일 이름, 종류, 크기(MB), 자동 증가, 최대 크기, 상태, 읽기 전용 |
| `storage_growth_none`, `storage_size_unlimited`(`SQLServerMessages`) | None, Unlimited | 없음, 제한 없음 |
| `create_label_file`, `create_label_autogrowth`, `create_label_growth` | File:, Autogrowth, Growth: | 파일:, 자동 증가, 증가: |
| `create_error_file`, `create_error_growth` | Enter the file name. / Enter the growth as a whole number. | 파일 이름을 입력하십시오. / 증가를 정수로 입력하십시오. |
| `objmaint_mssql_fg_default` (+`_title`, `_tip`) | Set as Default Filegroup; Put new tables and indexes in this filegroup when no filegroup is named (ALTER DATABASE ... MODIFY FILEGROUP ... DEFAULT) | 기본 파일 그룹으로 설정; 파일 그룹을 지정하지 않은 새 테이블과 인덱스가 이 파일 그룹에 만들어지게 합니다(…). |
| `objmaint_mssql_fg_read_only` (+…) | Make Read-Only; Make the files of the filegroup read-only (… READ_ONLY) | 읽기 전용으로 전환; 파일 그룹의 파일을 읽기 전용으로 만듭니다(…). |
| `objmaint_mssql_fg_read_write` (+…) | Make Read-Write; Allow changes to the files of the filegroup again (… READ_WRITE) | 읽기 쓰기로 전환; 파일 그룹의 파일을 다시 바꿀 수 있게 합니다(…). |
| `objmaint_mssql_file_settings` (+…) | File Settings… / File Settings; Change the size, autogrowth and maximum size of the file (ALTER DATABASE ... MODIFY FILE) | 파일 설정; 파일의 크기, 자동 증가, 최대 크기를 바꿉니다(…). |

다시 쓴 문구: `create_label_name`, `create_label_size`, `create_label_max_size`("Maximum (MB):"), `create_error_name`, `create_error_size`, 삭제·이름 바꾸기·미리 보기·
트랜잭션 질문의 기존 문구. "New Filegroup…"·"New File…"·"New Log File…"은 기존 `create_new_object`("New {0}…" / "새 {0}")에 노드 종류 이름이 들어간 것이다.

## upstream 대비 변경점

- upstream(DBeaver)은 SQL Server 파일·파일 그룹을 Object Explorer에 보이지 않는다. 모델 클래스 세 개, 객체 관리자 세 개, 트리 폴더, 데이터 원본의 에디션 판단을 더했다.

## 변경 이력

- 2026-11-15 최초 작성: 파일·파일 그룹 노드와 명령(앱 변경 #128, 브랜치 `chromite/mssql-storage`).
- 2026-11-16 대화상자·미리 보기·트랜잭션 질문이 창 가운데에 뜨고(앱 변경 #132), 숫자 칸이 숫자만 받음(앱 변경 #133, `chromite/storage-dialog-fixes`).
  확인(임시 데이터베이스 `chr_sd_app`, 끝에 지움): New Filegroup… `ALTER DATABASE chr_sd_app ADD FILEGROUP chr_sd_fg`, New File…
  `… ADD FILE (NAME = N'chr_sd_d1', FILENAME = N'/var/opt/mssql/data/chr_sd_d1.ndf', SIZE = 4MB, FILEGROWTH = 1MB, MAXSIZE = 16MB) TO FILEGROUP chr_sd_fg`,
  파일 설정(4.5 MB 파일) `… MODIFY FILE (NAME = N'chr_sd_frac', SIZE = 5MB)` — 전과 같은 모양.
