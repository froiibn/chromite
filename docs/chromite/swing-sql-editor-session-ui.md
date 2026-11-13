# 쿼리 창 세션 표시와 실행 중 표시 (앱 변경 #33, #44, #40, #50, #57)

기업 환경 실테스트(2026-11-09, 2026-11-11)에서 나온 쿼리 창 문제 다섯 가지를 고친 기록이다.
편집기 전체 구조는 [swing-frontend.md](swing-frontend.md), 실행 엔진 연결은 [swing-query-commands.md](swing-query-commands.md),
자동완성은 [swing-sql-completion.md](swing-sql-completion.md), 단축키 표는 [shortcuts.md](shortcuts.md)에 있다.

## 목적

- **현재 DB 따라가기(#33):** 쿼리 창에서 `USE`, `SET search_path`, `ALTER SESSION SET CURRENT_SCHEMA`를 실행하면 SQL Editor 도구 막대의
  DB/스키마 선택, 상태 표시줄의 DB 칸, 자동완성, Chromite Agent 문맥 기본값이 세션의 실제 값(`DB_NAME()` 등)을 따른다.
- **실행 중 표시(#44):** 쿼리가 실행 중인지 분명히 보인다. 상태 표시줄 상태 칸의 회전 스피너와 "실행 중 2/3 · 00:00:12", 쿼리 창 탭의 스피너,
  편집기에서 지금 실행 중인 문장의 바탕색과 줄 번호 옆 아이콘(끝나면 지움, 오류 난 문장은 오류 색으로 남김).
- **자동완성 창 닫기(#40):** 자동완성 목록이 Esc뿐 아니라 목록 밖을 누르거나 휠을 돌리거나 초점을 잃으면 닫힌다.
- **단축키를 도구 설명에(#50):** 결과 창 숨기기(Ctrl+R, SSMS와 같음)는 이미 있었지만 사용자가 몰랐다. 닫기 단추와 단축키가 있는 아이콘 단추의
  도구 설명이 단축키를 함께 보인다("닫기 (Ctrl+R)", macOS "⌘R").
- **닫기 단추 모양(#57):** 도구 창 머리글과 결과 영역의 숨기기 단추가 "−"라서 Windows 사용자가 최소화로 읽었다. "×"(아이콘 팩의 close)로 바꿨다.

## 구조

모두 `com.seizia.chromite.ui.swing` 번들이고, #33의 PostgreSQL 수정만 `com.seizia.chromite.ext.postgresql`이다.

| 클래스 | 역할 |
|---|---|
| `SqlEditorPanel` | `QueryListener`가 문장 뒤(조회·DML이 아닌 문장)와 실행 끝에 세션의 현재 DB/스키마를 서버에서 다시 읽음(`readSessionDefaults`). 실행 밖에서 트랜잭션이 끝나면 다시 읽음(`sessionReadPending`). 실행 중 상태 글자(`showRunState`), 탭 스피너(`getTabIcon`), 실행 중·실패 문장 표시(`showRunningStatement`, `markFailedStatement`, `clearStatementMarks`, 실행 중 편집 따라가기 `mapOffset`), 편집 영역의 아이콘 열 |
| `PostgreExecutionContext` | `refreshDefaults`: 읽은 뒤에 값을 바꿈, `SET ROLE`은 세션을 시작할 때만 |
| `SpinnerIcon` (새) | 회전 도넛 스피너 아이콘(Java2D, 테마 강조 색), 운영체제 움직임 줄이기 설정이면 돌지 않음. 대화상자·패널용 `createLabel`(#60) |
| `QueryStatusLine` | 실행 중 상태 아이콘을 스피너로 |
| `SqlCompletionPopup` | 목록이 보이거나 계산하는 동안 앱의 마우스 누름·휠을 듣고(`listenOutside`) 목록 밖이면 닫음 |
| `UiIcons` | 아이콘 단추(`createActionButton`, `createToolButton`)의 도구 설명이 동작의 단축키를 함께 보임(`ShortcutTipButton`, `withShortcut`) |
| `ToolWindow` | 숨기기 단추: 아이콘 `close`(×), 이름 "닫기", 도구 창 토글의 단축키를 도구 설명에 |
| `EditorMessages`, `CoreMessages` | `editor_sql_executing_statement`, `editor_sql_executing_elapsed`, `editor_sql_state_elapsed`, `common_tip_shortcut`, `toolwindow_close`(새). 쓰지 않게 된 `editor_sql_executing`, `toolwindow_hide` 삭제 |
| `tools/branding/gen_ui_icons.py` | 쓰지 않게 된 `hide`("−") 아이콘 삭제(팩 두 개 × 밝은·어두운 SVG 네 개도) |

## 현재 DB 따라가기 (#33)

**근본 원인:** 쿼리 창의 DB/스키마 선택·상태 표시줄 DB 칸·자동완성·Agent 문맥은 모두 실행 문맥(`DBCExecutionContextDefaults`)이 기억한 값을 읽는다.
그 값은 세션을 열 때(`refreshDefaults(…, true)`)와 선택 상자로 바꿀 때만 정해지고, 사용자의 문장이 세션을 바꾼 뒤에는 아무도 서버에서 다시 읽지 않았다.
실행 뒤의 `refreshSchemas`는 기억한 값을 다시 그릴 뿐이었다. SWT 편집기는 실행이 끝나면 `DBUtils.refreshContextDefaultsAndReflect`를 불렀지만
(`SQLEditor.refreshContextDefaults`, "Refresh active schema after SQL execution", SWT 기본값은 꺼짐) Swing으로 옮길 때 빠졌다.
그래서 SQL Server `USE`만이 아니라 같은 원인으로 MySQL·MariaDB `USE`, PostgreSQL `SET search_path`, Oracle `ALTER SESSION SET CURRENT_SCHEMA`도
표시가 바뀌지 않았다.

**고침:** 추측(문장 글자로 `USE`를 찾기)이 아니라 세션의 실제 값을 서버에서 읽는다.

- 문장 하나가 끝나면, 조회(`SELECT`)나 DML(`INSERT`/`UPDATE`/`DELETE`/`MERGE`)이 아니고 오류가 없을 때 그 세션에서 다시 읽는다. 다음 문장의 기록(쿼리 기록의 DB)도 맞게 된다.
  조회·DML은 세션의 DB를 바꾸지 않으므로 긴 스크립트에 왕복을 더하지 않는다.
- 실행이 끝나면(`onEndSqlJob`), 마지막 문장 뒤에 읽지 않았으면 한 번 더 읽는다. 일부가 실패한 배치(`USE x` 뒤 오류)와 `SELECT set_config('search_path', …)` 같은 경우다.
- 실행 밖에서 트랜잭션이 끝나면(상태 줄, Query 메뉴, 유휴 트랜잭션 알림) 다시 읽는다. PostgreSQL은 롤백한 트랜잭션의 `SET search_path`를 되돌린다.
- 읽기는 실행 작업 스레드에서 그 세션의 연결로 한다(`DBCExecutionPurpose.META`). 실패하면 기록만 하고 알던 값을 둔다.
- 저장 프로시저 안의 `USE`는 프로시저가 끝나면 세션에 남지 않는다. 서버 값을 읽으므로 표시도 바뀌지 않는다.
- 각 DB의 읽기: SQL Server `SELECT db_name(), schema_name(), original_login()`(테이블이 없는 SELECT라 암시적 트랜잭션을 시작하지 않음),
  MySQL·MariaDB `SELECT DATABASE()`, PostgreSQL `search_path`와 `current_schema()`, Oracle `SYS_CONTEXT('USERENV','CURRENT_SCHEMA')`.

**PostgreSQL 수정(같은 원인의 다른 경로):** `PostgreExecutionContext.refreshDefaults`는 값을 먼저 지우고 읽었다. 오류로 중단된 트랜잭션에서는 읽기가 실패해 활성 스키마가 사라졌다.
이제 읽은 뒤에 바꾼다. 또 연결 설정의 역할(`SET ROLE`)을 읽을 때마다 다시 실행해 사용자의 `SET ROLE`을 되돌렸으므로, 세션을 시작할 때(`useBootstrapSettings`)만 한다.

**알려진 한계:** PostgreSQL 수동 커밋에서 스크립트가 `COMMIT`으로 끝나면 다시 읽는 SELECT가 드라이버의 `BEGIN`으로 새 트랜잭션을 연다.
쥔 것(트랜잭션 ID, 잠금)이 없어 열린 트랜잭션으로 보이지 않는 것이 [idle-transactions.md](idle-transactions.md)의 기준과 맞지만, 서버의
`idle_in_transaction_session_timeout`이 짧으면 그 세션을 끊을 수 있다.

## 캐시에 없는 현재 DB (#79)

**증상(2026-11-12 확인 작업):** SQL Server에서 다른 세션이 만든 DB로 `USE` 하면 DB 선택이 "< N/A >"가 되고 상태 줄에서 DB가 빠졌다.
같은 창에서 만든 DB나 새 연결에서는 정상이었다.

**근본 원인:** 위 "현재 DB 따라가기"로 세션의 현재 DB 이름은 서버에서 맞게 읽는다. 그런데 실행 문맥은 그 이름을 객체로 바꿀 때 연결할 때 읽어 둔 DB 목록
캐시에서만 찾았다(`SQLServerExecutionContext.getDefaultCatalog` → `SQLServerDataSource.getDatabase(name)` → `getCachedObject`, 고치기 전 75–76행).
캐시를 읽은 뒤에 만들어진 DB는 없으니 null이고, 쿼리 창(`SqlEditorPanel.showSchemas`)은 목록에서 기본 객체를 찾지 못해 N/A를 보였다.
같은 원인이 다른 엔진에도 있었다(고치기 전 빌드에서 재현): MariaDB `USE`(`MySQLExecutionContext`, `getCatalog` = 캐시만) → N/A,
PostgreSQL 다른 세션이 만든 스키마로 `SET search_path`(`PostgreExecutionContext.setActiveSchema`, 스키마 캐시를 다 읽은 뒤의 `getSchema`는 찾지 못함)
→ 선택과 상태 줄이 예전 `public`(틀린 값). Oracle `ALTER SESSION SET CURRENT_SCHEMA`는 정상이었다(스키마 캐시가 이름 하나를 서버에서 읽음).

**고침:** 서버가 알려 준 현재 DB/스키마가 캐시에 없으면 목록을 다시 읽는다. 공통 메서드 `JDBCObjectCache.getObjectReadingNew`:
이름으로 찾고, 없고 캐시를 다 읽은 상태면 목록을 다시 읽는다(`setFullCache(false)` → `loadObjects`). `mergeCache`가 이미 읽은 객체를 그대로 두므로
탐색기·열린 편집기가 가진 객체는 바뀌지 않는다. SQL Server·MySQL/MariaDB `refreshDefaults`, PostgreSQL `setActiveSchema`가 이 메서드를 쓴다.
목록을 다시 읽는 것은 이름이 캐시에 없을 때뿐이다. 첫 실행 문맥은 데이터 원본 생성자 안에서 열려 캐시가 아직 없으므로(목록은 `initialize`가 읽음)
그때는 건너뛴다. 아직 읽지 않은 목록도 처음 읽지 않는다(아래 "서버 버전을 읽기 전의 목록"). 다시 읽기가 실패하면 기록만 하고 알던 값을 둔다. 탐색기 트리에는 새 DB를 넣지 않는다(새로 고침하면 보임).

**확인(이 브랜치 빌드, GCP 테스트 VM):** 연결한 뒤 다른 세션(격리 세션)에서 `chr_small_fixes_r3`를 만들고 쿼리 창에서 `USE`:
SQL Server·MariaDB 선택과 상태 줄이 그 DB, PostgreSQL `SET search_path TO chr_s2`(다른 세션이 만든 스키마)가 `chr_s2`, Oracle은 그대로 정상.
연결 새로 고침(Object Explorer Refresh) 뒤, 끊었다 다시 연결한 뒤에도 같았다. 증거 `D:\Tools\_scratch\small-fixes-r3\sheets\79-*`.

### 다시 읽어도 없는 이름 (#79 검토 후속)

**문제:** 서버의 현재 DB/스키마가 목록 캐시에 영영 들어오지 않는 경우가 있다. 그러면 `getObjectReadingNew`가 쿼리 창의 실행마다 목록 전체를 다시 읽었다.
기본값 읽기(`refreshDefaults`, PostgreSQL `setActiveSchema`)는 F5 한 번에 적어도 한 번 돈다: 조회·DML이 아닌 문장이 오류 없이 끝날 때마다, 그리고 실행 끝에
마지막 문장 뒤에 읽지 않았으면 한 번(위 "현재 DB 따라가기"). `SELECT 1` 열 번이면 기본값 읽기 열 번이다(아래 표의 read-defaults).

**고침:** 기억은 다시 읽는 곳인 캐시(`JDBCObjectCache`)에 둔다. 다시 읽어도 없는 이름을 기억하고, 같은 이름을 다시 물으면 읽지 않는다. 캐시를 비우면
(연결 Refresh, 다시 연결은 새 캐시) 또는 다른 이름을 물으면 다시 한 번 읽을 수 있다.

**화면:** 목록에 없는 현재 DB는 "&lt; N/A &gt;" 대신 서버가 알려 준 이름을 글로 보인다(`SqlEditorPanel.getUnlistedDefaultName`, 실행 문맥의
`getCachedDefault`: 목록이 DB면 DB 이름, 스키마면 스키마 이름). 고를 수 없는 글 항목이고, 상태 줄도 그 이름이다. 이름이 없으면 전처럼 N/A다.
PostgreSQL은 현재 스키마가 목록에 없으면 실행 문맥이 search_path에서 목록에 있는 첫 스키마를 기본으로 삼으므로(전과 같음) 그 스키마가 보인다.

**확인(쿼리 창에서 `USE`/`SET search_path` 한 번, 이어서 `SELECT 1` 열 번. 앱의 쿼리 관리자 처리기로 센 목록 질의 수):**

| 경우 | 고치기 전 `USE` + 10번 | 고친 뒤 `USE` + 10번 | 고친 뒤 선택·상태 줄 |
|---|---|---|---|
| SQL Server, Object Explorer 필터가 `chr_small_fixes_r3`를 숨김 | 1 + 10 | 1 + 0 | `chr_small_fixes_r3`(전: N/A, 상태 줄 비어 있음) |
| SQL Server, DB 하나만 보는 연결(`show-all-databases-azure=false`, master) | 1 + 10 | 1 + 0 | 같음 |
| MariaDB, 필터가 DB를 숨김 | 1 + 10 | 1 + 0 | 같음 |
| PostgreSQL `SET search_path = pg_catalog`(목록에 있음) | 0 + 0 | 0 + 0 | `pg_catalog` |
| PostgreSQL 임시 표 뒤 `SET search_path = pg_temp, public`(`pg_temp_N`은 유틸리티 스키마라 목록에 없음) | 2 + 10(DDL 새로 고침 포함) | 1 + 0 | `public`(전과 같음) |
| PostgreSQL, 필터가 `chr_s2`를 숨김, `SET search_path TO chr_s2` | 1 + 10 | 1 + 0 | `public`(전과 같음, 실제 세션 값과 다름) |

연결 Refresh 뒤 `SELECT 1` 열 번: 목록 질의 2(Refresh 자신의 1 + 한 번 더 1). `USE master` 뒤 다시 `USE` + 다섯 번: 1. 다시 연결 뒤 `USE` + 다섯 번: 1.
다른 세션이 만든 DB/스키마(위 #79 경우)는 고친 뒤에도 그 DB/스키마가 보였다(SQL Server, MariaDB, PostgreSQL, 목록 질의 1번).
SQL Server의 제한된 로그인은 확인하지 않았다: 목록 질의(`SELECT db.* FROM sys.databases db`)에 접근 권한 조건이 없고, `sys.databases`는 기본 권한으로 모든 DB를 보인다.
지워진 DB: SQL Server·PostgreSQL은 쓰고 있는 DB를 지울 수 없고, MariaDB는 현재 DB를 지우면 `DATABASE()`가 NULL이라 이름을 찾지 않는다(확인하지 않음).
증거 `D:\Tools\_scratch\small-fixes-r3\sheets\79-followup-*.log.txt`.

**범위 밖에서 본 것:** SQL Server에서 `USE db; CREATE TABLE dbo.t …; SELECT …`처럼 DDL이 든 배치를 실행하면 끝난 뒤 선택이 N/A가 될 때가 있다.
2026-11-13 고침(앱 변경 #89): 아래 "연결을 다시 읽은 뒤의 DB 선택".

### 서버 버전을 읽기 전의 목록 (#107)

**증상(작업 예약 구현 보고, 2026-11-12):** 앱을 새로 시작한 뒤 SQL Server 테이블이 원본인 저장된 작업(내보내기, 테이블 복사)이
"Child object 'src' not found in container chr_task_scheduling.dbo"로 실패했다. 손으로 실행해도 같고, 연결을 새로 고치면 됐다.
재현해 보니 앱 시작과 상관없이 **연결할 때마다** 생겼다: 연결의 기본 DB에서는 Object Explorer의 dbo > Tables도 비어 있었다.

**근본 원인:** `JDBCDataSource.initialize`는 메타데이터 실행 문맥을 먼저 열고(`JDBCRemoteInstance.initializeMetaContext`, `JDBCDataSource.java:653`)
그다음에 서버 버전을 읽는다(`readDatabaseServerVersion`, 657행). 문맥을 열면 `SQLServerExecutionContext.refreshDefaults`가 돌고, #79가 넣은
`getObjectReadingNew`(195행)가 아직 읽지 않은 DB 목록을 거기서 처음 읽었다. 목록을 읽으면 현재 DB의 생성자가 스키마를 읽고(`SQLServerDatabase.java:136`),
스키마 생성자는 버전으로 열 이름을 고른다(`SQLServerSchema.java:91`: 2005 이상이면 `schema_id`, 아니면 `uid`). 버전을 모르면 `isServerVersionAtLeast`가
false라 `sys.schemas`에 없는 `uid`를 읽어 **모든 스키마 ID가 0**이 되었다. 테이블 캐시는 `o.schema_id = 0`으로 읽으므로 테이블이 하나도 없고,
`DBUtils.findObjectById`의 "없으면 스키마를 새로 고쳐 다시 찾기"도 ID가 그대로라 소용없었다. 연결 Refresh는 DB 목록을 비우고 버전을 아는 상태에서 다시
읽어서 고쳐졌다. 같은 때 스키마 목록 질의도 버전 분기로 `sysobjects`와 묶여 `INFORMATION_SCHEMA`가 빠졌고, 교차 DB 질의 여부도 false로 판단했다.

두 상태(앱 안 시험 도구로 모델을 그대로 읽음, 증거 `D:\Tools\_scratch\task-defects\logs\before-ops.log`, `before-app.log`):

| | 연결 직후(고치기 전) | 연결 Refresh 뒤 |
|---|---|---|
| `JDBCDataSource.databaseVersion` | 16.0.0(목록을 읽을 때는 아직 null) | 16.0.0 |
| `chr_task_defects`의 스키마 | dbo 0, s2 0, sys 0(`INFORMATION_SCHEMA` 없음) | dbo 1, INFORMATION_SCHEMA 3, s2 5, sys 4 |
| dbo의 테이블 | 없음 | counter, src |
| 다른 DB(`chr_task_defects_b`, 나중에 읽음) | dbo 1, 테이블 other | 같음 |

앱 로그의 경고 "Checking server version before connection initialization" 여섯 개의 스택이 모두
`JDBCDataSource.initialize` → `initializeMetaContext` → `refreshDefaults` → `getObjectReadingNew` → `SQLServerDatabase.<init>` → `SQLServerSchema.<init>`
(3개), `SchemaCache.prepareObjectsStatement`(1개), `SQLServerUtils.supportsCrossDatabaseQueries`(2개)이었다.

**고침:** `getObjectReadingNew`는 아직 읽지 않은 목록(`isFullyCached()`가 false)을 읽지 않고 캐시에 있는 것만 돌려준다. 다시 읽을 옛 목록이 없고,
첫 읽기는 목록의 주인(`SQLServerDataSource.initialize`, `MySQLDataSource.initialize`, PostgreSQL `setActiveSchema`의 `getSchemas`)이 버전을 안 뒤에 한다.
#79 이전의 읽는 순서로 돌아가고, #79의 경우(다른 세션이 만든 DB로 `USE`)는 목록을 이미 읽은 뒤이므로 그대로 다시 읽는다.
작업 실행기에서 새로 고침을 강제하지 않았다. 모델이 틀린 ID를 만드는 것이 원인이고, 같은 모델을 Object Explorer·자동 완성·Chromite Agent 도구·스키마 비교가 함께 쓴다.

**같은 원인을 쓰는 곳:** 저장된 경로로 객체를 찾는 기능(`DBUtils.findObjectById`: 데이터 전송 작업의 원본·대상, SQL 도구 작업, 네이티브 도구 작업,
Chromite Agent `AITextUtils`)과 연결의 기본 DB 스키마를 읽는 모든 기능. Query Library의 기본값 고르기는 이름으로 찾으므로(`QueryLibraryDefaultsPicker.java:303`)
실패하지 않았다. 다른 DB의 스키마는 나중에(버전을 안 뒤) 읽으므로 맞았다. MySQL/MariaDB도 메타데이터 문맥을 열 때 DB 목록을 처음 읽었지만 DB 객체 생성에
버전 분기가 없어 결과가 같았다(고친 뒤에는 `initialize`에서 읽음). PostgreSQL은 #79 전부터 문맥을 열 때 스키마를 읽어 바뀐 것이 없다. Oracle은 이 메서드를 쓰지 않는다.

**확인(이 브랜치 빌드, GCP 테스트 VM, `D:\Tools\_scratch\task-defects\logs\ops.log`):** 저장된 내보내기(dbo 테이블, 기본 스키마가 아닌 s2 테이블, 기본 DB가 아닌 DB의 테이블)를 앱을 새로
시작해 손으로 실행: 고치기 전 dbo·s2 실패, 다른 DB 성공 → 고친 뒤 모두 성공, 스키마 ID 1·5, Tables에 counter·src. 시작 때 먼저 연결한 경우, 연결 Refresh 뒤,
끊었다 다시 연결한 뒤에도 성공. PostgreSQL·MariaDB·Oracle 내보내기도 새로 시작한 뒤, Refresh 뒤, 다시 연결한 뒤 성공. 다른 세션이 만든 DB로 `USE`(#79) 뒤
기본 DB가 그 DB. 고친 뒤 앱 로그에 서버 버전 경고 없음.

### 연결을 다시 읽은 뒤의 DB 선택 (#89)

**증상(#245 작업 중 보고, 2026-11-12):** SQL Server에서 `USE db; CREATE TABLE dbo.t …; SELECT …`를 실행하면 다음 실행까지 DB 선택이 "&lt; N/A &gt;"로 남을 때가 있다.
실행 문맥의 현재 DB는 맞다. #245 뒤의 빌드(devel `a0ef7da598`)에서는 "&lt; N/A &gt;" 대신 이름 글(목록의 DB 객체가 아닌, 고를 수 없는 글 항목,
`getUnlistedDefaultName`)로 남는다. 상태 줄의 DB 칸은 그 이름이라 맞게 보이지만, 선택 목록에는 같은 DB가 객체와 글로 두 번 있다.

**재현(고치기 전 빌드 = devel `a0ef7da598`, GCP 시험 VM의 SQL Server 2022, 앱 안 시험 에이전트가 쿼리 창을 F5로 실행하고 창이 가라앉은 뒤 선택을 읽음):**
연결 Refresh 하나로도 생긴다(실행 없이, 10번 중 10번 글). `USE db; CREATE DATABASE x; SELECT 1`도 10번 중 10번 글. 1부 이름 `CREATE TABLE t`는 20번 중 0번.

**두 상태(작업과 캐시를 1 ms 간격으로 기록, `D:\Tools\_scratch\picker-and-agent-shared\run\explore2.txt.log`):**

| 시각(ms) | 일 | DB 목록 캐시 |
|---|---|---|
| 140 | 실행 끝: "Refresh metadata after DDL"와 선택 읽기("Read default schema") 작업이 함께 시작 | 다 읽음, 6개, `chr_picker_agent`=@51459584 |
| 148 | DDL 새로 고침 작업이 연결 전체를 다시 읽음(`SQLServerDataSource.refreshObject`가 `databaseCache.clearCache()`, 617행) | 비어 있음, 0개 |
| 173 | 연결의 OBJECT_UPDATE 알림으로 선택 읽기 두 번 더(`SqlEditorPanel.refreshSchemas`). 읽기는 `getDefaultCatalog()`를 먼저 물음 → null | 비어 있음 |
| 173–193 | 같은 읽기가 이어서 `getChildren`으로 목록을 다시 읽음(`ContextDefaultObjectsReader.run:133`) | 다시 다 읽음, 새 객체 @460294963 |
| 결과 | `readerDefault=null`, 선택은 글 항목 "chr_picker_agent", 다음 실행까지 그대로 | |

**근본 원인:** `ContextDefaultObjectsReader.run`(고치기 전 111행)이 기본 카탈로그(`contextDefaults.getDefaultCatalog()`)를 목록을 읽기(133행) **전에** 묻는다.
SQL Server 실행 문맥의 `getDefaultCatalog`는 진행 모니터가 없어 목록 캐시에서만 찾는다(`SQLServerExecutionContext.java:80` → `SQLServerDataSource.getDatabase(name)`
→ `getCachedObject`, 586행). 연결을 다시 읽으면(Refresh, 연결 전체를 다시 읽는 DDL) 목록이 비고, 그 사이의 읽기는 null을 받거나(위 표) 다시 읽기 전 목록의
객체를 받아 다시 읽은 목록과 같은 객체가 아니게 된다. `showSchemas`는 목록에서 기본 객체를 같은 객체로 찾으므로 못 찾고 이름 글을 넣는다.
경쟁이 아니라 순서 문제다: 목록이 빈 동안 읽으면 늘 생긴다. Object Explorer가 연결의 하위 노드를 읽어 둔 상태(펼침, Refresh 뒤)에서는 새로 고침 작업 자신이 목록을
다시 읽어 선택 읽기 전에 채워지므로 생기지 않았다(아래 표의 "예" 열).

**이 순서를 이 문장에서 드러낸 것:** `USE db; CREATE TABLE dbo.t`의 DDL 뒤 새로 고침이 연결 전체가 된다. `SQLMetadataRefreshTargetResolver`(upstream, 113–118행)는 두 부분
이름에서 실행 문맥이 스키마를 바꿀 수 없고 카탈로그를 바꿀 수 있으면(`supportsSchemaChange` false, SQL Server) 첫 부분을 **DB**로 본다. 그래서 `dbo`를 DB로 찾고,
없으니 `SqlMetadataRefresh.resolveTarget`(237행)이 연결 전체를 대상으로 삼는다. 이것은 고치지 않았다(아래 "남은 것").

**고침:** `ContextDefaultObjectsReader.run`이 카탈로그를 고르는 경우 기본 카탈로그를 묻기 전에 카탈로그 목록을 읽는다(`objectContainer.getChildren(monitor)`;
다 읽은 목록이면 서버를 부르지 않음). 기본 객체와 목록이 같은 캐시에서 나오므로 같은 객체다. 다시 그리기를 더하거나 문장 모양을 따로 다루지 않았다.

**같은 원인을 쓰는 곳:**
- 상태 줄의 DB 칸: 선택에서 정하므로 같이 고쳐진다(고치기 전에도 이름 글이라 맞게 보였다).
- MySQL/MariaDB: `MySQLExecutionContext.getDefaultCatalog`도 캐시에서만 찾는다(72행). 다만 MariaDB 연결 새로 고침은 목록을 그 자리에서 다시 읽어
  고치기 전에도 생기지 않았다(`CREATE TABLE db.t`, `CREATE TABLE t`, `CREATE DATABASE`, `DROP DATABASE`, Refresh 모두 객체).
- PostgreSQL: 기본 카탈로그는 문맥의 인스턴스 자신(`PostgreExecutionContext.java:79`)이라 캐시와 상관없다. 선택은 스키마 목록이다.
- Chromite Agent `getCurrentDatabase`의 `connectionDatabase`: 같은 `getDefaultCatalog`를 쓴다(코드 읽기). Refresh 바로 뒤에 불러도 `master`가 나왔다(1번,
  Object Explorer가 목록을 다시 읽은 뒤). 목록이 빈 순간에 부르면 null일 수 있으나 재현하지 못해 고치지 않았다.
- 연결 대화상자의 DB 목록: 임시 연결로 서버에 따로 묻는 목록(`ConnectionDatabaseList`, `ServerDatabaseNames`)이라 이 캐시를 쓰지 않는다.

**확인(고치기 전 = devel `a0ef7da598` 빌드, 고친 뒤 = 이 브랜치 빌드, 같은 시나리오 각 20번).** 매번 쿼리 창에서 `USE master`를 먼저 실행하고,
"새 DB"는 그 직전에 다른 세션이 만든 DB(매번 새 이름, 끝나고 지움), 한 번에는 `USE db;\nCREATE TABLE dbo.tN (i int);\nSELECT DB_NAME() AS d;`를 F5로,
문장마다는 세 번 따로 실행했다(값은 CREATE 뒤 / SELECT 뒤). 상태: "연결"은 매번 끊고 다시 연결, "Refresh"는 매번 연결 Refresh, "다시 연결"은 `reconnect`.
Object Explorer "예"는 매번 Databases > `chr_picker_agent` > Schemas > dbo > Tables까지 펼침. 객체 = 선택이 목록의 DB 객체, 글 = 이름 글 항목.

| 상태 | Object Explorer | 실행 | 기존 DB 고치기 전 | 새 DB 고치기 전 | 기존 DB 고친 뒤 | 새 DB 고친 뒤 |
|---|---|---|---|---|---|---|
| 연결 | 아니오 | 한 번에 | 글 20 | 글 20 | 객체 20 | 객체 20 |
| 연결 | 아니오 | 문장마다 | CREATE 뒤 글 19·객체 1, SELECT 뒤 객체 20 | 같음 | 객체 20 / 객체 20 | 객체 20 / 객체 20 |
| 연결 | 예 | 둘 다 | 객체 20 | 객체 20 | 객체 20 | 객체 20 |
| Refresh | 아니오·예 | 둘 다 | 객체 20 | 객체 20 | 객체 20 | 객체 20 |
| 다시 연결 | 아니오 | 한 번에 | 글 20 | 글 20 | 객체 20 | 객체 20 |
| 다시 연결 | 아니오 | 문장마다 | CREATE 뒤 글 20, SELECT 뒤 객체 20 | CREATE 뒤 글 18·객체 2 | 객체 20 / 객체 20 | 객체 20 / 객체 20 |
| 다시 연결 | 예 | 둘 다 | 객체 20 | 객체 20 | 객체 20 | 객체 20 |
| 연결, 1부 이름 `CREATE TABLE tN` | 아니오 | 한 번에 | 객체 20 | 객체 20 | 객체 20 | 객체 20 |

Refresh 상태에서 고치기 전에도 객체였던 것은 Refresh가 연결 노드의 하위 노드를 읽어 두어, DDL 뒤 새로 고침 작업이 목록을 스스로 다시 읽었기 때문이다(펼침과 같음).
실행 없이 연결 Refresh만 한 뒤: 고치기 전 글 10/10 → 고친 뒤 객체 10/10. `USE db; CREATE DATABASE x; SELECT 1`: 글 10/10 → 객체 10/10.
#79(다른 세션이 만든 DB로 USE)는 위 "새 DB" 열이다. #107: 고친 빌드에서 `chr_picker_agent`가 기본 DB인 새 연결을 연결하자마자 dbo > Tables가 비지 않았고(527개)
Agent `listTables`도 dbo 표를 돌려주었다(저장된 작업 자체는 실행하지 않았다). MariaDB(`CREATE TABLE db.t`, `CREATE TABLE t`, `CREATE DATABASE`, Refresh)와
PostgreSQL(`SET search_path` 뒤 `CREATE TABLE`, `CREATE SCHEMA` 뒤 `SET search_path`, Refresh)은 고친 뒤에도 선택이 객체, 값이 맞았다.
증거 `D:\Tools\_scratch\picker-and-agent-shared\run\`(`matrix-before.txt.log`, `matrix-after.txt.log`, `triggers-*.log`, `explore*.log`, `others-after.txt.log`).

**남은 것:**
- `SQLMetadataRefreshTargetResolver`가 SQL Server의 `schema.table`을 `db.table`로 읽어, `CREATE TABLE dbo.t` 같은 흔한 DDL 하나에 연결 전체(모든 DB 목록, Object Explorer의
  읽어 둔 노드)를 다시 읽는다. 선택은 이제 맞지만 새로 고침 범위가 넓은 것은 그대로다. upstream 코드이고 MySQL의 `db.table`과 나눌 기준(카탈로그 아래 스키마가 있는지)이
  필요해 이 항목에서 고치지 않았다(따로 처리할 항목으로 보고).
- 회귀 테스트는 요청되지 않아 실행하지 않았다(추가한다면 목록이 빈 캐시에서 `ContextDefaultObjectsReader`가 목록의 객체를 기본값으로 돌려주는 단위 시험).

## 실행 중 표시 (#44)

- **상태 칸:** 실행 중 아이콘은 회전 도넛 스피너(`SpinnerIcon`, 16, 1초에 한 바퀴, 테마 강조 색 호와 옅은 고리)다. 글자는 문장이 둘 이상이면 "실행 중 2/3 · 00:00:12",
  하나면 "실행 중 · 00:00:03", 연결 중·취소 중이면 "연결하는 중 · 00:00:01"이다. 실행 중에는 경과 시간 칸을 숨기고(같은 시간을 두 번 보이지 않음) 끝나면 전체 시간을 보인다.
- **번호:** 몇 번째 문장인지는 실행할 문장 목록에서 그 문장의 위치다. `GO 2`로 되풀이하는 문장은 번호가 같고, `@include` 파일의 문장은 include 명령의 번호에 머문다.
- **탭:** 실행 중인 쿼리 창의 탭 아이콘이 스피너다. 다른 탭을 보고 있어도 보인다. 탭에는 아이콘만 바꾼다(탭 메뉴·고정은 #42).
- **편집기:** 지금 실행 중인 문장의 줄 바탕을 연두색(2026-11-12 #99부터, 전에는 강조 색 투명도 40, 아래 "실행 중 문장 색")으로 칠하고 첫 줄의 줄 번호 옆 아이콘 열에 실행 아이콘을 둔다. 다음 문장으로 옮기고 실행이 끝나면 지운다.
  오류 난 문장은 오류 색 바탕과 오류 아이콘으로 다음 실행까지 남긴다(Messages의 오류를 누르면 같은 곳으로 간다).
  편집기는 그 문장으로 스크롤하지 않는다(사용자가 다른 곳을 편집 중일 수 있음, 정한 기본값).
- **실행 중 편집:** 편집은 지금처럼 막지 않는다. 실행이 시작된 뒤의 편집을 기록해 두고(`Execution.edits`) 문장 위치를 지금 글자 위치로 옮겨 표시한다.
  표시한 뒤의 편집은 줄 강조와 아이콘이 스스로 따라간다(문서 위치).
- **아이콘 열:** 편집 영역 줄 번호 옆에 아이콘 열을 늘 켠다(IDE의 중단점 열과 같은 자리). 실행 중에만 켜면 글자가 좌우로 움직이기 때문이다.
- **움직임 줄이기:** Windows "애니메이션 효과" 끔(`SPI_GETCLIENTAREAANIMATION`), macOS "동작 줄이기"(`NSWorkspace.accessibilityDisplayShouldReduceMotion`)면 같은 모양으로 멈춰 있다.
- Windows 작업 표시줄 진행 표시는 하지 않는다(사용자 "windows까지는 할 필요 없다").

### 실행 중 문장 색 (#99)

**사용자(2026-11-12, 사내 26.0.5):** "여러 쿼리 실행 시 실행중인 쿼리가 전체가 다 선택된다." 이어서 "오라클, mssql 모두 현재 실행중인 쿼리가
파란계열로 나와서 눈에 띄지 않는다. 노란색이나 연두색쯤으로 실행중인 쿼리 영역을 표시한다." 그래서 이번 범위는 **표시의 색**이다.
SQL Server 배치 안에서 지금 실행 중인 문장을 따로 찾는 것은 하지 않았다(아래 "배치 안의 문장: 하지 않은 것").

**원인 두 가지(고치기 전 빌드에서 확인):**

- 색: 실행 중 표시는 `Component.accentColor`(`#3871E1`)를 투명도 40으로 칠했다. 바탕 위에서 라이트 `#E0E9FA`, 다크 `#1E283B`가 되어
  편집기 바탕과의 대비가 1.22 / 1.18이고, 색상이 글자 선택(라이트 `#A6D2FF`, 다크 `#214283`)과 같은 파랑이라 "선택된" 것처럼 보였다.
- 첫 줄이 가려짐: RSyntaxTextArea는 줄 강조(`paintLineHighlights`)를 먼저 칠하고 현재 줄(`paintCurrentLineHighlight`)을 그 위에 칠한다
  (`RTextAreaUI.paintBackground`, 4.0.1). F5·Ctrl+Enter를 누른 캐럿 줄은 대개 실행 중인 문장의 첫 줄이라 그 줄은 표시 대신 현재 줄 색이었다.

**고침:**

- 색은 테마 키 `Chromite.editor.runningStatementBackground`(불투명)다. 라이트 `#E8F5A0`, 다크 `#3D4718`(어두운 연두). `ChromiteLightLaf.properties`,
  `ChromiteDarkLaf.properties`에 있고 `SqlEditorPanel.showRunningStatement`가 읽는다. 오류 난 문장 표시(`Chromite.status.error` 투명도 40)는 그대로다.
- 캐럿 줄이 실행 중인 문장 안이면 그 줄의 현재 줄 색을 실행 중 색으로 바꾼다(`SqlTextArea.setRunningLines`, `getCurrentLineHighlightColor`).
  실행이 끝나거나 표시가 다음 문장으로 옮기면 현재 줄 색이 돌아온다.
- 줄 번호 옆의 실행 아이콘(초록 삼각형 테두리)은 거터에 있어 새 색 위에 놓이지 않는다. 바꾸지 않았다.
- 오류 표시도 캐럿 줄에서는 현재 줄 색에 가려진다(전과 같음, 이번 범위 밖, 보고만).

**색과 대비(WCAG 대비율):**

| | 라이트 | 다크 |
|---|---|---|
| 지금(파랑, 바탕에 합친 값) | `#E0E9FA`, 바탕 대비 1.22 | `#1E283B`, 바탕 대비 1.18 |
| 제안·적용(연두) | `#E8F5A0`, 바탕 대비 1.17 | `#3D4718`, 바탕 대비 1.75 |
| 대안 1(더 노랑) | `#FFF2A0`, 1.14 | `#4A4318`, 1.75 |
| 대안 2(더 초록) | `#D6F0B8`, 1.23 | `#2E4420`, 1.63 |

| 구문 색 | 라이트: 흰 바탕 → 연두 위 | 다크: 편집기 바탕 → 연두 위 |
|---|---|---|
| 글자 | 21.0 → 18.0 | 12.7 → 7.2 |
| 키워드 | 8.6 → 7.4 | 5.9 → 3.4 |
| 연산자 | 3.9 → 3.4 | 8.4 → 4.8 |
| 함수 | 3.1 → 2.7 | 7.4 → 4.2 |
| 문자열 | 4.0 → 3.4 | 6.0 → 3.4 |
| 숫자 | 3.4 → 2.9 | 12.1 → 6.9 |
| 주석 | 5.1 → 4.4 | 5.8 → 3.3 |

라이트는 바탕과의 밝기 차가 지금 파랑과 비슷하고(1.17) 색상(노랑-연두)으로 눈에 띈다. 구문 색 대비는 지금 파랑 위와 거의 같다(파랑 위 함수 2.6, 숫자 2.8).
다크는 바탕과 1.75로 지금보다 뚜렷하고, 구문 색은 모두 3.3 이상이다. 다크의 주석 초록(`#57A64A`)은 같은 계열 색 위라 가장 낮다(3.3, 기울임은 그대로).

**이웃 색(같은 편집기에 함께 보이는 것):**

| 표시 | 라이트 | 다크 | 실행 중 표시와 |
|---|---|---|---|
| 글자 선택 | `#A6D2FF` | `#214283` | 선택이 위에 칠해져 실행 중 줄 안에서도 보임 |
| 현재 줄 | `#F5F8FE` | `#212326` | 실행 중 문장 안이면 실행 중 색 |
| 찾기 모두 표시·같은 이름 | `#EDEBFC` | `#373B39` | 실행 중 줄 밖 글자 바탕, 색상이 다름(라벤더 / 회색) |
| 짝 괄호 | `#93D9D9` | `#43454A` | 글자 한 칸 |
| 오류 난 문장 | `#C9303F` 투명도 40 | `#E66A6A` 투명도 40 | 빨강 계열, 실행이 끝나도 남음 |
| 결과 표의 고친 칸(편집기 밖) | — | `#5A4A1F` | 그리드에만 있음 |

**배치 안의 문장: 하지 않은 것(사용자 결정 2026-11-12, 색만 고침).** 재현(고치기 전 빌드, 테스트 VM, 느린 문장 `WAITFOR DELAY`, `pg_sleep`, `SLEEP`,
`DBMS_SESSION.SLEEP`, Altibase `EXEC SLEEP`)에서 표시가 덮는 범위:

| 경우 | SQL Server | Oracle | PostgreSQL | MariaDB | Altibase |
|---|---|---|---|---|---|
| F5 전체, `;`로 나눈 5문장 | 5줄 전체(배치 하나, 1/1) | 문장마다(1/5…5/5) | 문장마다 | 문장마다 | 문장마다 |
| F5 전체, 줄바꿈만 | 5줄 전체(배치 하나) | 한 문장으로 보내 ORA-03048 | 한 문장으로 보내 구문 오류 | 1064 | 구문 오류 |
| F5 선택한 3문장 | 3줄 전체(배치 하나) | 문장마다 | 문장마다 | 문장마다 | 문장마다 |
| `GO`로 나눈 배치 | 배치마다 | — | — | — | — |
| Ctrl+Enter 캐럿 문장 | 그 문장 | 그 문장 | 그 문장 | 그 문장 | 그 문장 |
| Ctrl+Enter 선택한 3문장 | 3줄 전체(선택을 한 문장으로) | 3줄, ORA-03405 | 3줄, 한 번에 실행 | 3줄, 1064 | 3줄, 구문 오류 |
| 블록(`BEGIN … END`, `DO`) | 블록 전체 | 블록 하나 | 블록 하나 | 파서가 `;`에서 나눠 오류 | 확인 안 함 |
| 프로시저 호출 뒤 문장 | 둘 다(배치 하나) | 호출, 그다음 문장 | 호출, 그다음 문장 | 호출, 그다음 문장 | 확인 안 함 |

근본 원인: SQL Server 방언은 배치 구분자 `GO`가 있어 `SQLScriptBatches.groupBatches`(`addBatch` 116–147행)가 `GO` 사이의 문장을 원문 그대로 `SQLQuery` 하나로
합치고, 실행 엔진은 그것을 JDBC 실행 한 번으로 보낸다. 엔진이 알리는 시작(`onStartQuery`)은 배치마다 한 번이라 `showRunningStatement`가 배치 전체를 칠한다.
다른 엔진은 문장마다 보내므로 이미 문장마다다. MySQL 8.4는 같은 방언·드라이버(MariaDB 드라이버) 경로라 따로 캡처하지 않았다.

검토한 방법(구현하지 않음):

1. **서버가 알려 주는 위치:** `sys.dm_exec_requests`의 `statement_start_offset`/`statement_end_offset`(배치 글의 UTF-16 바이트). 창의 세션이 바쁘므로
   연결의 메타데이터 문맥(따로 연 연결이면 늘 자동 커밋, `JDBCRemoteInstance.initializeMetaContext` 134행)에서 읽어야 한다. 창 세션은 드라이버의
   `ISQLServerConnection.getClientConnectionId()`(서버 왕복 없음)로 `sys.dm_exec_connections.connection_id`와 맞출 수 있다. 서버가 돌려준 문장 글을
   보낸 배치 글과 비교해 같을 때만 그 문장을 칠하면 틀린 문장을 가리키지 않는다. 한계: VIEW SERVER STATE가 없는 로그인은 다른 세션의 요청을 보지 못한다
   (문서상, 확인하지 않음), 프로시저 안의 문장은 프로시저 글이라 배치의 `EXEC`만 알 수 있다, 매개변수·`@set` 치환으로 글이 바뀌면 맞출 수 없다, 폴링 비용.
   Sybase·jTDS는 이 DMV가 없다.
2. **결과가 올 때마다 다음 문장으로:** `SET NOCOUNT ON`(행 수 없음), 결과 없는 문장(`WAITFOR`, `DECLARE`, `IF` 분기), 프로시저 안의 결과에서
   틀린 문장을 가리키므로 쓰지 않는다.

**확인(이 브랜치 빌드, 2026-11-12, 테스트 VM, 한국어, 100%, 라이트·다크):** Oracle 세 문장의 두 번째 실행 중과 SQL Server 배치 실행 중을 지금(파랑)·제안·대안 둘로 캡처했다
(지금 파랑은 같은 빌드에서 키를 예전 값 `#3871E1` 투명도 40으로 바꿔 그림: 같은 그리기 경로). 오류 표시·찾기 표시·선택·짝 괄호를 함께 둔 이웃 캡처, 캐럿이 실행 중 줄 안일 때,
실행이 끝난 뒤(표시 지움, 현재 줄 색 돌아옴). SQL Server는 연결 Refresh 뒤와 끊었다 다시 연결한 뒤에도 같은 색. 범위는 고치기 전과 같다(위 표).
증거 `D:\Tools\_scratch\running-statement-mark\sheets\`(overview, light/dark-full, light/dark-neighbours)와 `s\*.txt.log`.

### 스피너 하나와 길이를 모르는 진행 (#60)

앱의 도넛 스피너 구현은 `SpinnerIcon` 하나다. 연결 대화상자의 데이터베이스 목록(#39)이 따로 만든 `DonutSpinner`(JComponent)는 지우고,
대화상자·패널은 `SpinnerIcon.createLabel()`(스피너 아이콘을 단 `JLabel`, 보일 때만 돌고 숨기거나 창을 닫으면 멈춤)을 쓴다. 움직임 줄이기 설정도 그대로 따른다.

원칙(사용자 결정): 길이를 모르는 짧은 기다림은 도넛 스피너, 길이를 아는 진행은 막대. 2026-11-12에 Swing UI의 길이를 모르는(indeterminate) `JProgressBar`를 모두 찾아 나눴다.

| 곳 | 기다리는 것 | 처리 |
|---|---|---|
| `ProgressDialog`(EDT에서 부른 짧은 작업의 모달 진행 창) | 연결 시험, 드라이버 속성 읽기, 메타데이터·스키마 읽기, Agent 작업 읽기, 연결 내보내기·가져오기 등 | 스피너로(작업 문구 앞) |
| `MaintenanceDialog` 상태 줄 | 열 때 대상 상태·권한 읽기(짧음), 유지 관리 명령 실행(길이 모름, 취소 단추) | 스피너로 — 한 표시를 두 기다림이 같이 쓰고 둘 다 길이를 모른다 |
| `ConnectionDatabaseList` | 데이터베이스 목록 읽기 | 이미 스피너(#39), 구현만 `SpinnerIcon`으로 |
| `DatabaseTaskDialog.reportProgress`·`start` | 네이티브 클라이언트 작업(백업·복원·스크립트). 도구가 %를 알리면 % 막대 | 막대 유지(길이를 아는 진행으로 바뀜, 오래 걸림) |
| `DataTransferWizard` 실행 | 데이터 전송. 첫 파이프가 끝나기 전만 길이를 모름, 그 뒤 끝난 파이프 수/전체 | 막대 유지 |
| `update/UpdateWindow`(`UpdateView.Bar.BUSY`) | 업데이트 설치·시작 단계 | 막대 유지(업데이트 진행 창의 단계 막대, #60 행) |
| `UpdateUi` | 받기 % | 막대(길이를 앎) |

확인(2026-11-12, 테스트 에이전트, 150%·100%): `ProgressDialog.runTask`에 3초 작업 → 진행 창에 `SpinnerIcon` 라벨 하나, `JProgressBar` 없음, 작업 문구 앞에서 돎
(`D:\Tools\_scratch\chromite-small-fixes-r2\shots\60-progress-dialog-*.png`). `MaintenanceDialog`와 연결 대화상자의 목록 스피너는 DB 없이 열 수 없어 화면으로 보지 않았다(빌드만).

## 자동완성 창 닫기 (#40)

**근본 원인:** 목록은 초점을 받지 않는 창이라 편집기가 초점을 잃을 때와 메인 창이 움직일 때만 닫혔다. 편집기 안, 빈 영역, 도구 막대, 도구 창 머리글, 상태 표시줄처럼
누르는 곳이 초점을 가져가지 않으면 편집기 초점이 그대로라 닫히지 않았다.

**고침:** 목록이 보이거나 계산 중인 동안만 앱 전체의 마우스 누름과 휠 이벤트를 듣는다(`Toolkit.addAWTEventListener`). 목록·설명 창 밖이면 닫는다(계산 중이면 취소).
이벤트를 소비하지 않으므로 편집기 안을 누르면 닫히고 커서도 그 자리로 간다. 목록 안의 누름·휠은 그대로 고르고 넘긴다.
다른 앱으로 가면 편집기가 초점을 잃어 닫힌다(그대로). 입력 중 자동으로 여는 동작은 바꾸지 않았다.

**다른 편집기 팝업:** 마우스 올림 설명은 Swing 도구 설명(`ToolTipManager`)이라 밖을 누르면 이미 닫힌다. 매개변수 힌트 창은 없다. 템플릿 메뉴는 `JPopupMenu`라 이미 닫힌다.

## 단축키를 도구 설명에 (#50)

**확인:** Ctrl+R(macOS ⌘R)은 View 메뉴의 Results 토글(`ToolWindow`의 단축키, 메뉴 가속키)이다. 편집기(RSyntaxTextArea 4.0.1의 기본 키 지도에 Ctrl+R 없음), 결과 그리드,
Messages, Object Explorer, 결과 탭 줄에 키가 갈 때 모두 보이기·숨기기가 바뀌는지 앱에서 확인했다(아래 확인 기록). 다른 명령이 Ctrl+R을 쓰지 않는다.
사용자가 몰랐던 것이 원인이라 찾기 쉽게 했다.

- `UiIcons`의 아이콘 단추는 동작에 단축키(`Action.ACCELERATOR_KEY`)가 있고 도구 설명에 그 글자가 없으면 "도구 설명 (단축키)"로 보인다. 도구 설명을 보일 때 만들므로
  동작이 설명을 나중에 바꿔도 단축키가 남는다. 단축키 글자는 `UiKeys.getText`(Windows "Ctrl+R", macOS "⌘R")다.
- 숨기기(닫기) 단추는 도구 창 토글의 단축키를 단추 동작에도 둔다(단추는 가속키를 묶지 않으므로 키는 그대로 View 메뉴로만 간다).
- 메뉴: View &gt; Results에 단축키 글자가 이미 보인다(메뉴 가속키).

## 닫기 단추 모양 (#57)

도구 창 머리글(Object Explorer, Registered Servers, Query Library, Properties, Chromite Agent 등 도킹 도구 창)과 결과 탭 줄 오른쪽의 숨기기 단추 아이콘을
`hide`("−")에서 `close`("×", 아이콘 팩에 이미 있던 Tabler x 모양, 밝은·어두운 두 팩)로 바꿨다. 동작은 그대로 숨기기다(View 메뉴로 다시 보임).
이름과 도구 설명은 "닫기 (단축키)" / "Close (shortcut)"다. 마우스 올림 상태는 다른 아이콘 단추와 같은 FlatLaf 도구 막대 단추 바탕이다.
`hide` 아이콘은 다른 곳에서 쓰지 않아 생성기와 리소스에서 지웠다.

## upstream 대비 변경점

- SWT 편집기는 실행 뒤 기본 DB/스키마 다시 읽기가 설정(기본 꺼짐)이었다. Swing은 설정 없이 늘 읽는다(사용자 요구 "실제 세션 값과 콤보가 늘 같아야 함").
- `PostgreExecutionContext.refreshDefaults`: 읽은 뒤 바꾸기, `SET ROLE`은 시작할 때만.
- `JDBCObjectCache.getObjectReadingNew`와 그것을 쓰는 SQL Server·MySQL/MariaDB·PostgreSQL 실행 문맥(위 "캐시에 없는 현재 DB").
- `ContextDefaultObjectsReader.run`: 기본 카탈로그를 묻기 전에 카탈로그 목록을 읽음(위 "연결을 다시 읽은 뒤의 DB 선택"). upstream은 기본값을 먼저 묻는다.
- 나머지(스피너, 문장 표시, 바깥 누름으로 닫기, 도구 설명 단축키, 닫기 아이콘)는 Swing 프런트엔드에만 있다.

## 확인 기록

2026-11-11, Windows 11, 150%(일부 100%), JBR 25, 빈 작업 공간과 `CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트(OS 입력 없음).
Docker가 멈춰 있어 SQL Server·Oracle 컨테이너 대신 D:의 MariaDB 11.8과 PostgreSQL 17을 임시 서버로 띄워 확인했다.

- #33 MariaDB: `USE db2` 뒤 선택·상태 줄·문맥 기본값 모두 db2. `USE db1; SELECT 1; USE db2; SELECT 잘못된 열`(마지막 문장 오류) 뒤에도 db2.
- #33 PostgreSQL: `SET search_path TO s2` → s2. `BEGIN; SET search_path TO s1; ROLLBACK` → s2 그대로.
  `SELECT set_config('search_path','s1',false)` → s1(실행 끝 읽기). `DO` 블록 안의 `set_config(…, true)`(트랜잭션 안에서만, 프로시저 안 USE와 같은 경우) → 표시 그대로.
  `BEGIN; SET search_path TO s2; SELECT 잘못된 열`(중단된 트랜잭션, 실행 끝 읽기 실패) → 알던 s2 유지, 다음 `ROLLBACK` → s1.
- #33 SQL Server·Oracle: Docker가 멈춰 확인하지 못함(보류).
- #44: 5문장 스크립트 실행 중 상태 칸 "실행 중 1/5 · 00:00:00" → "실행 중 3/5 · 00:00:02", 아이콘·탭 아이콘 `SpinnerIcon`, 실행 중 줄 1 → 3, 경과 시간 칸 숨김.
  끝난 뒤 상태 아이콘·탭 아이콘 원래대로, 실행 중 표시 없음, 오류 난 4번 줄에 오류 바탕과 아이콘, 경과 시간 칸 "00:00:03.051".
- #40: 목록을 띄운 뒤 편집기 안 누름(닫힘, 커서 14 → 22), 상태 표시줄·도구 막대·Object Explorer 누름, 편집기 휠(모두 닫힘), 목록 안 누름(열린 채).
- #50: 편집기, 결과 그리드, Messages, Object Explorer, 결과 탭 줄에서 Ctrl+R이 결과를 숨기고 다시 보임. View &gt; 결과에 Ctrl+R.
  아이콘 단추 도구 설명 조사: 닫기 단추 "닫기 (Ctrl+R)", "닫기 (F8)", "닫기 (Ctrl+Alt+G)", "닫기 (Ctrl+Alt+T)", "닫기 (Ctrl+Alt+I)"와
  쿼리 실행 취소 "… (Alt+Pause)"가 새로 단축키를 보인다. 나머지 단추는 이미 단축키를 보이고 있었다.
- #57: 도구 창 머리글과 결과 탭 줄의 단추가 ×.

**확인하지 못한 것:** 떠 있는(도킹을 뗀) 도구 창에서는 메뉴 막대가 없어 메뉴 가속키(Ctrl+R 포함)가 가지 않을 수 있다(이번 범위 밖, 보고만).

## 변경 이력

- 2026-11-11 최초 작성: #33(현재 DB 따라가기), #44(실행 중 표시), #40(자동완성 창 닫기), #50(단축키를 도구 설명에), #57(닫기 단추 모양)
- 2026-11-12 스피너 하나(앱 변경 #60, `chromite/small-fixes-r2`): `DonutSpinner` 삭제, `SpinnerIcon.createLabel`, `ProgressDialog`·`MaintenanceDialog`의 길이를 모르는 막대를
  스피너로. "스피너 하나와 길이를 모르는 진행" 절 추가. 회귀 테스트는 실행하지 않았다.
- 2026-11-12 앱 변경 #79(`chromite/small-fixes-r3`): 서버가 알려 준 현재 DB/스키마가 캐시에 없으면 목록을 다시 읽음(SQL Server, MySQL/MariaDB, PostgreSQL).
  "캐시에 없는 현재 DB" 절 추가. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 쿼리 창 DB 선택·상태 줄, 연결할 때의 기본 DB 읽기, DB/스키마 목록 캐시.
- 2026-11-12 #79 검토 후속: 다시 읽어도 없는 이름은 다시 읽지 않음(캐시가 기억), 목록에 없는 현재 DB는 이름을 글로 보임. "다시 읽어도 없는 이름" 절. 회귀 테스트는 실행하지 않았다.
- 2026-11-12 앱 변경 #99(`chromite/running-statement-mark`): 실행 중 문장 표시를 파랑에서 테마 키 `Chromite.editor.runningStatementBackground`의 연두로,
  캐럿 줄이 그 문장 안이면 현재 줄 색이 표시를 가리지 않음. SQL Server 배치 안의 문장 찾기는 검토만 함. "실행 중 문장 색" 절. 회귀 테스트는 실행하지 않았다.
  영향받을 수 있는 기능: 쿼리 창 편집기의 현재 줄 강조, 실행 중·오류 문장 표시, 테마 전환.
- 2026-11-12 앱 변경 #107(`chromite/task-defects`): `getObjectReadingNew`가 아직 읽지 않은 목록을 처음 읽지 않음. SQL Server 메타데이터 문맥이
  서버 버전을 읽기 전에 DB 목록을 읽어 현재 DB의 스키마 ID가 0이 되었다. "서버 버전을 읽기 전의 목록" 절 추가. 회귀 테스트는 실행하지 않았다.
  영향받을 수 있는 기능: 연결할 때 DB·스키마 목록을 읽는 순서(SQL Server, MySQL/MariaDB, PostgreSQL), 쿼리 창 DB 선택·상태 줄, 저장된 작업의 객체 찾기.
- 2026-11-13 앱 변경 #89(`chromite/picker-and-agent-shared`): `ContextDefaultObjectsReader`가 기본 카탈로그를 묻기 전에 카탈로그 목록을 읽음. 연결을 다시 읽은 뒤
  (Refresh, `CREATE TABLE dbo.t`·`CREATE/DROP DATABASE`처럼 연결 전체를 다시 읽는 DDL) 쿼리 창의 DB 선택이 목록의 객체 대신 이름 글(#245 전에는 N/A)로 남았다.
  "연결을 다시 읽은 뒤의 DB 선택" 절 추가. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 쿼리 창 DB/스키마 선택·상태 줄(모든 DB 종류), 연결 직후 첫 선택 읽기.
