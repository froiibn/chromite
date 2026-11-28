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
2026-11-13 고침(앱 변경 #108): 두 부분 이름을 엔진의 이름 구조로 읽어 `dbo.t`는 스키마 `dbo`만 다시 읽는다(swing-query-commands.md "두 부분 이름 (#108)").
이 절의 선택 고침은 그대로 필요하다(연결 Refresh, `CREATE/DROP DATABASE`는 여전히 연결 전체를 다시 읽는다).

**고침:** `ContextDefaultObjectsReader.run`이 카탈로그를 고르는 경우 기본 카탈로그를 묻기 전에 카탈로그 목록을 읽는다(`objectContainer.getChildren(monitor)`;
다 읽은 목록이면 서버를 부르지 않음). 기본 객체와 목록이 같은 캐시에서 나오므로 같은 객체다. 다시 그리기를 더하거나 문장 모양을 따로 다루지 않았다.
이 먼저 읽기가 실패하면(`DBException`) 기록만 하고 예전 순서대로 계속한다(검토 후속): 기본 카탈로그가 스키마를 갖고 스키마를 바꿀 수 있는 엔진은 전에 이 메서드에서
카탈로그 목록을 읽지 않고 기본 카탈로그의 스키마만 읽었으므로, 새 읽기의 실패로 선택 읽기가 실패하면 안 된다. 뒤의 목록 읽기는 예전과 같다(실패하던 곳에서 실패).

**새 읽기가 닿는 엔진(코드 읽기, 카탈로그가 맨 위 + 기본 카탈로그 아래 스키마 + `supportsSchemaChange()`가 참):**

| 엔진 | 그 분기를 타는 근거 | 데이터 원본 `getChildren` |
|---|---|---|
| PostgreSQL, Greenplum·Cloudberry(`GreenplumDataSource extends PostgreDataSource`) | 맨 위 `PostgreDatabase`(`PostgreDataSource.java:561`), `PostgreExecutionContext.java:95` 참 | `getDatabases()` = `databaseCache.getCachedObjects()`(`PostgreDataSource.java:550–552`, `466–469`): 질의 없음, 예외 없음 |
| Generic JDBC(카탈로그가 있을 때), Snowflake(`SnowflakeExecutionContext.java:91` 참), Databricks·Vertica(`GenericExecutionContext.java:250`, 드라이버 정보에 따름), Doris(`DorisExecutionContext.java:119`), StarRocks(`StarRocksExecutionContext.java:109`), IoTDB 표 모델(`IoTDBExecutionContext.java:82`), Dameng·Mimer(카탈로그가 있을 때) | 맨 위 `GenericCatalog`(`GenericDataSource.java:729–737`, 카탈로그 목록이 비어 있지 않을 때) | 연결할 때 읽어 둔 `catalogs` 필드(`GenericDataSource.java:700–712`, `312–314`): 질의 없음. 이 확장들은 `getChildren`·`getCatalogs`를 다시 정의하지 않는다(검색) |
| 그 밖: SQL Server·MySQL/MariaDB(`supportsSchemaChange` 거짓), Oracle·DB2·Exasol·Altibase(맨 위가 스키마) | 분기 아님 | SQL Server·MySQL은 전에도 같은 메서드 133행에서 같은 목록을 읽었다 |

그래서 선택 읽기마다 무거운 질의를 새로 부르는 엔진은 없다. 목록을 캐시에서만 찾는 엔진으로 읽기를 좁히지 않았다.

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
  필요해 이 항목에서 고치지 않았다(따로 처리할 항목으로 보고). 2026-11-13 앱 변경 #108에서 고침: 기준은 데이터 원본의 맨 위가 카탈로그이고 카탈로그 아래에
  스키마가 없는지다(swing-query-commands.md "두 부분 이름 (#108)"). 고친 빌드에서 #89의 두 경우(기존 DB, 펼치지 않음, 한 번에·문장마다)를 연결·Refresh·다시
  연결 뒤 각 10번 실행해 모두 객체였다("새 DB" 열은 다시 돌리지 않았다).
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

## New Query가 활성 창의 DB를 이어받음 (2026-11-22, 앱 변경 #172)

File > New Query·도구 막대·Ctrl+N은 활성 쿼리 창의 연결과, 그 창의 DB 선택이 보이는 값(위 "현재 DB 따라가기"로 서버에서 다시 읽은 값 포함,
`SqlEditorPanel.getDefaultObjectNames`)으로 새 창을 연다. 새 창은 그 이름을 다시 시작 뒤 복원과 같은 길(`setRestoredDefaults` → 세션이 열릴 때
`openWindowContext`)로 자기 세션에만 정하고, 활성 창의 세션에는 아무것도 보내지 않는다. 주 세션을 함께 쓰는 창은 정하지 않는다.
목록에 없어 이름 글로만 보이는 현재 DB(위 "다시 읽어도 없는 이름")는 객체로 찾을 수 없어 넘기지 못하고 새 창은 연결 기본값이다.
동작·설계·확인은 [swing-frontend.md](swing-frontend.md) "새 쿼리는 활성 탭의 연결과 DB로".

## 다시 연결 뒤의 DB (2026-11-25)

**보고(2026-11-21~23, 자세한 내용 없음):** "다시 연결하면 기본 데이터베이스로 돌아감".

**고치기 전 사실(이 브랜치의 고치기 전 빌드 = `origin/command` `bdb0ab42a6`, 시험 VM, 앱 안 시험 에이전트).** 쿼리 창(자기 세션)을 바꾼 뒤
네 가지로 다시 연결하고, 창의 DB 선택·상태 줄과 창 세션의 서버 값(`db_name()`/`DATABASE()`/`current_schema()`/`CURRENT_SCHEMA`)을 읽었다.
"필터"는 Object Explorer 필터가 숨긴 DB(목록에 없어 선택에 이름 글로만 보임)다. 끊긴 세션은 내 관리자 세션에서 창의 세션만 `KILL`(PostgreSQL
`pg_terminate_backend`)하고 창에서 `SELECT 1`을 실행했다. Oracle은 `ALTER SYSTEM`이 공용 시험 DB에서 금지라 끊지 않았다.

| DB | 바꾸기 | Invalidate/Reconnect | 끊긴 세션 다시 열기 | Reconnect | Disconnect → Connect |
|---|---|---|---|---|---|
| SQL Server 1433 | `USE`, 선택 상자 | 지킴(서버·표시 같음) | 지킴 | 기본 DB `master`(표시도 `master`) | `master`(끊긴 동안 선택 "&lt; N/A &gt;") |
| SQL Server, 필터 | `USE` | **표시 `…_h`, 서버 `master`** | 다음 실행 뒤 표시가 `master`로(그 실행은 `master`에서) | `master` | `master` |
| MariaDB 3306 | `USE`, 선택 상자 | 지킴 | 지킴 | DB 없음(N/A) | DB 없음 |
| MariaDB, 필터 | `USE` | **표시 `…_h`, 서버 DB 없음** | 다시 연 세션은 DB 없음, 실행 끝 읽기 뒤 표시 N/A | DB 없음 | DB 없음 |
| MySQL 8.4 3307(번들 MariaDB 드라이버) | `USE`, 선택 상자 | 지킴 | 지킴 | DB 없음 | DB 없음 |
| MySQL, 필터 | `USE` | **표시 `…_h`, 서버 DB 없음** | MariaDB 필터와 같음 | DB 없음 | DB 없음 |
| PostgreSQL 5432 | `SET search_path`, 선택 상자 | 지킴(#350) | 지킴 | `public` | `public` |
| Oracle 1521 | `ALTER SESSION SET CURRENT_SCHEMA`, 선택 상자 | 지킴(#165) | 확인 안 함(끊지 않음) | `SYSTEM` | `SYSTEM` |

쿼리 창에는 따로 "다시 연결" 명령이 없다. 창에서 쓰는 다시 연결은 끊긴 세션 다시 열기(`SQLQueryRecovery` → `TransactionRecoveryGuard.reopenLostSession`)와
연결 끊김 대화상자의 다시 연결(= Invalidate/Reconnect)이다. 둘 다 `JDBCExecutionContext.invalidateContext`로 세션을 자기 자신에서 다시 연다.

**원인 1(표시와 세션이 다름):** Invalidate/Reconnect와 끊긴 세션 다시 열기는 `initFrom`이 그 문맥 자신이다. SQL Server
(`SQLServerDataSource.initializeContextState`)와 MySQL/MariaDB(`MySQLDataSource.initializeContextState`)는 원본의 DB를 **개체**
(`getDefaultCatalog()`, 목록 캐시에서만 찾음)로 받아 `USE` 했다. 목록에 없는 DB면 null이라 아무것도 하지 않았고, 문맥은 옛 이름을 그대로 가진 채
새 JDBC 연결은 로그인 기본 DB(MySQL은 연결 설정의 DB, 없으면 DB 없음)에 있었다. 선택·상태 줄은 그 이름을 보이므로 다음 문장이 다른 DB에서 조용히
실행된다. `USE`가 실패할 때(그새 지운 DB, 오프라인)도 같다(SQL Server는 오류만 기록, MySQL은 예외가 `connect`에서 기록만 됨). PostgreSQL(#350)과
Oracle(#165)은 이미 이름으로 받고, 받지 못하면 세션의 실제 값을 읽는다.

**원인 2(사용자의 선택을 잃음):** Disconnect와 Reconnect(끊고 연결)는 데이터 소스를 새로 만든다. 창은 새 데이터 소스에서 자기 세션을 주 문맥에서 새로 연다
(`QueryWindowContext.open`)므로 연결의 기본 DB로 돌아갔다. 표시는 새 세션과 같았지만, 앱은 다시 시작 뒤에는 창의 DB/스키마를 복원하면서(
`QueryWindowRecovery` → `setRestoredDefaults`) 끊고 다시 연결할 때는 지키지 않았다. 모든 DB에서 같다.

**고친 것:**

| 자리 | 바꾼 것 |
|---|---|
| `SQLServerExecutionContext.setCurrentDatabaseFrom`(새), `SQLServerDataSource.initializeContextState` | 원본이 가진 이름(`activeDatabaseName`)으로 `USE`. 이름이 없거나 실패하면 세션의 실제 DB·스키마를 읽는다(`refreshDefaults(…, false)`). 기본 스키마 이름(`setDefaultSchemaFrom`)은 DB를 받았을 때만 받는다 |
| `MySQLExecutionContext.setCurrentDatabaseFrom`(새), `MySQLDataSource.initializeContextState` | 같은 방식. 원본에 DB가 없으면 서버가 연 그대로 둔다(전과 같음). 읽기 전용 연결은 전처럼 `refreshDefaults(…, true)`(세션의 값을 읽으므로 표시는 맞고, 선택은 연결 설정의 DB로 돌아감) |
| `ConnectionCommands.invalidate` | 작업이 끝나면 다시 연 연결들의 쿼리 창이 DB 선택·상태 줄을 다시 읽음(`QueryWindows.connectionDefaultsChanged`, "기본값으로 설정" 뒤와 같은 길). 전에는 Invalidate/Reconnect 뒤 아무도 다시 읽지 않아, 다시 연 세션이 원래 DB를 받지 못한 경우(아래 확인의 오프라인·삭제한 DB) 창이 옛 DB를 보인 채 다음 문장을 로그인 기본 DB에서 실행했다 |
| `DB2ExecutionContext.setCurrentSchemaFrom`·`ExasolExecutionContext.setCurrentSchemaFrom`(새), 각 `initializeContextState` | 같은 방식으로 스키마를 이름으로(아래 "같은 원인을 본 다른 곳", 코드 읽기만) |
| `SqlEditorPanel.keepDefaultsOfEndedSession`(새, `refreshTransactionMode`의 처음에서 부름), `QueryWindowContext.isOwn`(새) | 창의 자기 세션이 데이터 소스와 함께 끝나면(창이 본 데이터 소스와 지금 데이터 소스가 다름) 창이 보이던 DB/스키마를 다시 시작 복원과 같은 자리(`restoredDefaults`)에 둔다. 끊긴 동안 선택은 그 이름(비활성), 다음 세션이 열릴 때 `openWindowContext`가 정한다. 주 세션을 함께 쓰는 창은 두지 않는다 |

**고르지 않은 것과 이유:**
- 목록에 없는 DB를 이름으로 지키는 것은 Oracle #165의 "바뀌는 것"과 같다. "표시를 기본값으로 바꾸기"만으로도 표시와 세션은 같아지지만, 이름으로 `USE`가
  되면 사용자의 선택도 지켜진다. `USE`가 안 되면 세션의 실제 값을 읽으므로 어느 쪽이든 표시와 세션이 다르지 않다.
- 끊고 다시 연결할 때 지키는 이름은 목록의 객체로 보이던 것만이다(`getDefaultObjectNames`). 목록에 없어 이름 글로만 보이던 DB는 New Query(#172)와 같이
  넘기지 않고 연결 기본값으로 연다(표시도 기본값).
- 다시 연결한 세션에는 트랜잭션이 없다. 새 세션에서 `USE`/`SET search_path`/다른 DB로 다시 열기를 할 뿐 커밋·롤백하지 않고, 끊기 전 문장을 다시 보내지 않는다.
  끊기·Reconnect의 열린 트랜잭션 질문(`ExitTransactionCheck`)은 바뀌지 않았다.
- 원본 DB를 받지 못했을 때는 세션의 **이름만** 읽고 목록은 읽지 않는다: SQL Server `refreshDefaults(…, false, false)`(목록 다시 읽기 `getObjectReadingNew`를
  건너뛰는 private 겹침), MySQL `refreshDefaults(…, false, false)`(`DATABASE()`만, #186이 읽기 전용 연결의 다시 열기에 쓰는 것과 같은 길), DB2·Exasol `refreshDefaults(…, false)`(현재 스키마 질의만, 목록을 읽지 않음).
  Invalidate/Reconnect(`InvalidateJob.invalidateInstances`, `InvalidateJob.java:388`)와 끊긴 세션 다시 열기(`TransactionRecoveryGuard.java:235`)는 인스턴스의
  배타 잠금을 쥔 채 `invalidateContext` → `initializeContextState`를 부르므로, 거기서 목록을 읽으면 #165·#350의 교착 모양이 된다. 목록은 쿼리 창이 다음 실행 뒤에 읽는다.
  (2026-11-25 검토 후속: 처음에는 `refreshDefaults(…, false)`를 불러 목록을 다시 읽을 수 있었다.)
- 앱 변경 #186 뒤에는 닫힌 세션을 읽기가 다시 열 때(`JDBCExecutionContext.getConnection`)도 `initFrom`이 그 문맥 자신이라 같은 길(이름으로 받기, 못 받으면
  이름만 읽기)을 지난다. 그 길은 목록 캐시를 쥔 스레드에서도 올 수 있어, 이 길에서 목록을 읽지 않는 것이 더 중요하다.

**같은 원인을 본 다른 곳(코드 읽기):** Generic(Altibase 포함)의 `GenericExecutionContext.initDefaultsFrom`은 원본 기본값을 개체로 찾지 못하면 데이터 소스의
기본 카탈로그/스키마로 `USE`/`setSchema` 하고 그 이름을 가지므로 표시와 세션은 같다(필터로 숨긴 스키마는 선택을 잃음). 바꾸지 않았다.
DB2(`DB2DataSource`)와 Exasol(`ExasolDataSource`)은 원본의 스키마를 개체(`getDefaultSchema()`, 목록 캐시에서만 찾음)로 받아, 목록을 읽지 않았거나
목록에 없으면 null이라 아무것도 하지 않았다(`setCurrentSchema(null)`은 기록만). SQL Server·MySQL과 같은 원인이라 같은 방식으로 고쳤다
(`setCurrentSchemaFrom`: 이름으로 `SET CURRENT SCHEMA`/`OPEN SCHEMA`, 실패하면 세션의 스키마를 읽음, 그 읽기는 목록을 읽지 않음). 시험 서버가 없어
코드 읽기와 빌드로만 확인했다.

**확인:** 아래 "확인 기록 (2026-11-25)".

## 다른 곳에서 바뀐 세션의 DB (2026-11-25)

**보고:** "다시 연결 뒤의 DB"의 남은 것(PostgreSQL 창이 다른 데이터베이스에서 `postgres`로 다시 열림, 중간 확신).

**사실(`origin/command` `9112f945c9` 빌드, 시험 VM PostgreSQL 17, 연결 설정 "모든 데이터베이스 표시", 시험 DB `chr_postgres_window_database_restore` = X).**
PostgreSQL에서는 DB마다 인스턴스(세션)가 따로다. 쿼리 창의 DB 선택은 창이 있는 DB의 스키마만 보이므로 선택으로는 다른 DB로 옮길 수 없다. 창이 다른 DB에
가는 길과, 그 뒤(Invalidate/Reconnect, Reconnect, Disconnect → Connect, 앱 다시 시작) 창이 보이는 것·기억하는 이름(`getDefaultObjectNames`, 끊긴 동안과
다시 시작 뒤에는 `restoredDefaults`)·세션의 `current_database()`:

| 창이 열린 길(자기 세션) | 열렸을 때 | 네 가지 다시 연결·다시 시작 뒤 |
|---|---|---|
| 개체 탐색기 X 노드의 새 쿼리 | `postgres`(표시 `public`, 이름 `postgres.public`) | 같음 |
| X 표의 상위 1000개 행 선택 | `postgres`(위와 같음. `chr_s.chr_t`는 `postgres`에 없음, 아래 "범위 밖") | 같음 |
| X의 기본값으로 설정 뒤 새 쿼리 | X(표시 `public`, 이름 `X.public`) | X, 끊긴 동안 선택 `X.public`(비활성) |
| 그 창에서 File &gt; New Query | X(`X.public`) | X |
| X 함수의 수정(Modify) | X(표시 `chr_s`, 이름 `X.chr_s`) | X(다시 시작에는 고치지 않은 Modify 창은 복원하지 않음) |

자기 세션을 가진 창은 모든 경우 보이는 것·기억하는 이름·세션이 같았다. 보고된 경우는 시험 에이전트가 창의 세션을 창 밖에서 바꾼 것으로 재현된다:
바꾼 뒤 창은 `postgres.public`을 기억한 채였다(세션은 X). 선택 상자·실행·연결 명령은 바꾼 뒤 창이 선택을 다시 읽으므로 이 상태가 생기지 않는다.

**같은 원인의 실제 경우(결함):** 주 세션을 함께 쓰는 창("쿼리 창마다 별도 연결" = NEVER). 수정(Modify)·프로시저 실행·새 개체 틀은 새 창을 열기 전에 연결의
주 세션을 개체의 DB/스키마로 옮긴다(`SourceScripts.bindDefaultContext`). PostgreSQL에서는 주 세션이 X의 주 세션으로 바뀌는데, 그 주 세션을 함께 쓰던 창은
`postgres`의 `public`(이름 `postgres.public`)을 보인 채 다음 문장을 X의 `chr_s`에서 실행했다. "기본값으로 설정"은 끝난 뒤 창들이 선택을 다시 읽어
(`connectionDefaultsChanged`) 이 문제가 없었다. SQL Server·MySQL/MariaDB도 `bindDefaultContext`가 주 세션에 `USE` 하므로 같은 원인이다(코드 읽기).

**고친 것:** 모델은 세션의 기본 카탈로그/스키마를 바꿀 때 실행 문맥을 붙인 `OBJECT_SELECT` 이벤트를 연결에 보낸다(`DBUtils.fireObjectSelectionChange`,
각 드라이버의 `setDefaultCatalog`/`setDefaultSchema`, PostgreSQL `setActiveDatabase`). `MainFrame`의 레지스트리 수신기가 이 이벤트를 쿼리 창에 넘기고
(`SqlEditorPanel.contextDefaultsChanged`), 선택이 그 문맥의 기본값을 보이는 창은 선택을 다시 읽는다. 바꾼 쪽이 어디든(위 명령, 앞으로의 다른 명령, 모델 호출)
창이 따라간다. 문맥이 붙지 않은 이벤트(개체 새로 고침 `fireObjectRefresh`)는 받지 않는다. 실행 중인 창은 받지 않는다(실행 끝에 읽는다).

**고르지 않은 것:** `bindDefaultContext`를 부르는 곳마다 창 다시 읽기를 넣는 것(직접 부르는 곳이 네 곳이고 Schema Compare 등 그 위의 명령이 더 있어, 새로 생기는 곳에서 빠뜨리기 쉬움). 다시 읽기는
세션에 문장을 보내지 않고(목록·기본값 읽기는 메타데이터 세션과 메모리), 커밋·롤백하지 않는다. PostgreSQL 창은 DB 이름 없이 스키마만 보인다(바뀌지 않음,
보이는 스키마는 세션의 것과 같다).

**확인:** 아래 "확인 기록 (2026-11-25, 다른 곳에서 바뀐 세션의 DB)".

## 개체에서 연 창의 DB (2026-11-28)

**보고("다른 곳에서 바뀐 세션의 DB"의 범위 밖, 중간 확신):** "모든 데이터베이스 표시"를 켠 PostgreSQL에서 다른 데이터베이스의 표나 노드에서
"상위 1000개 행 선택"·"새 쿼리"를 하면 창이 연결의 기본 데이터베이스에서 열려, 만든 `SELECT … FROM 스키마.표`가 `postgres`에서 돈다.

**고치기 전 사실(`origin/command` `e470c1f25c` 빌드, 시험 VM, 앱 안 시험 에이전트).** 시험 DB `chr_postgres_open_in_object_database`(= X)와
기본 DB `postgres`에 같은 이름의 표 `chr_postgres_open_in_object_database.chr_t`를 두고 행을 다르게 넣었다(X: `(1,'X'),(2,'X')`, `postgres`: `(1,'postgres')`).
SQL Server·MariaDB(3306)·MySQL 8.4(3307)는 연결의 기본 DB를 `…_d`로 하고 그 DB에 같은 이름의 표(`(1,'default')`)를 두었다. X의 개체에서 명령마다 연 창의
표시(DB 선택·상태 줄), 창 세션의 서버 값(`current_database()`/`db_name()`/`DATABASE()`), 첫 실행의 결과:

| 명령 | PostgreSQL(창마다 자기 세션, 기본값) | PostgreSQL(주 세션을 함께 씀, NEVER) | SQL Server | MariaDB·MySQL |
|---|---|---|---|---|
| 새 쿼리(DB·스키마·표 노드) | `postgres`(표시 `public`) | `postgres` | `…_d` | `…_d` |
| 상위 1000개 행 선택 | `postgres`, **`(1,'postgres')`**(기본 DB의 같은 이름 표, 편집 가능으로) | 같음 | `…_d`, 글이 `X.dbo.chr_t`라 X의 행(맞음) | `…_d`, 글이 `` X.chr_t ``라 X의 행(맞음) |
| 스크립팅 ▸ SELECT 대상 → F5 | `postgres`, **`(1,'postgres')`** | 같음 | `…_d`, X의 행 | `…_d`, X의 행 |
| SQL 생성 ▸ SELECT → F5 | `postgres`, **`(1,'postgres')`** | 같음 | `…_d`, X의 행 | `…_d`, X의 행 |
| SQL 생성 ▸ INSERT·UPDATE·DELETE(실행 안 함) | `postgres`(글에 DB 없음: 기본 DB의 같은 이름 표를 바꿈) | 같음 | `…_d`(글이 DB를 씀) | `…_d`(글이 DB를 씀) |
| 스크립팅 ▸ CREATE 대상, SQL 생성 ▸ DDL(실행 안 함) | `postgres`(글에 DB 없음) | 같음 | `…_d`, 글이 `USE X` / `GO`로 시작 | `…_d`, **글이 `` CREATE TABLE `chr_t` ``**(DB 없음: `…_d`에서 돈다) |
| 삭제 ▸ 쿼리 창에서 열기(`DROP`) | `postgres`(코드 읽기: 같은 `openScript` 길) | 같음 | 코드 읽기 | 코드 읽기 |
| 수정(Modify), 프로시저 실행 | X(`SourceScripts.bindDefaultContext`가 주 세션을 X로 옮긴 뒤 새 세션이 복사) | X(주 세션이 X로, 함께 쓰는 창 모두 따라감) | X(`USE` 배치) | X |

"데이터 편집·데이터 보기"는 Object Explorer에 따로 없다. 데이터 명령은 "상위 1000개 행 선택"(두 번 누르기 설정 "데이터 열기"도 이 명령)이고 그 결과 그리드가 편집한다.
"스크립팅 ▸ DROP·EXECUTE"는 삭제의 "쿼리 창에서 열기"와 SQL 생성 ▸ CALL·프로시저 실행이다.

**근본 원인:** 개체에서 창을 여는 길(`ObjectExplorerPanel`의 새 쿼리·상위 N개·스크립팅, `ExplorerObjectCommands`의 SQL 생성·바꾸기 스크립트,
그 밖에 아래 표의 길)은 `QueryWindows.openQuery(연결)`/`openScript(연결, 글, …)`로 **연결만** 넘겼다(`ObjectExplorerPanel.java` 옛 618–625행,
643–651행, 1176–1194행). 창은 연결의 기본값에서 세션을 열고(`QueryWindowContext.open`), 개체가 어느 DB/스키마에 있는지 몰랐다. 만든 글은 PostgreSQL에서
DB를 쓸 수 없어(`스키마.표`) 창이 있는 DB에서 돈다. SQL Server·MySQL은 DML 글이 DB를 쓰므로 결과는 맞았지만 창은 개체의 DB를 보이지 않았다.
수정·프로시저 실행은 `bindDefaultContext`로 연결의 주 세션을 먼저 옮겨서 맞았다.

**고친 것(앱이 가진 길로):** 창을 여는 쪽이 개체를 함께 넘기고(`QueryWindows.openQuery(연결, 개체)`,
`openScript(연결, 개체, 글, 행 수, 실행)`), `MainFrame.runWhereObjectIs`가 개체의 카탈로그·스키마 이름을 새 창의 `setRestoredDefaults`에 둔다.
New Query(#172)·다시 시작 복원과 같은 자리다: 창의 자기 세션이 열릴 때(`openWindowContext`, 상위 N개의 실행도 이 길) 그 세션에만 `setExecutionContextDefaults`
(DB 선택과 같은 호출, PostgreSQL은 격리 세션을 그 DB로 다시 연결, SQL Server·MySQL `USE`, Oracle `ALTER SESSION SET CURRENT_SCHEMA`)를 하고,
DB 선택·상태 줄은 그 뒤 세션에서 읽는다. 연결의 주 세션과 다른 창은 바뀌지 않는다. 새 창이라 열린 트랜잭션이 없고, 커밋·롤백하지 않는다.

| 길 | 넘기는 개체 |
|---|---|
| 새 쿼리(Object Explorer 노드) | 선택한 개체·폴더(DB·스키마 아래가 아니면 이름이 없어 전과 같음). 연결 노드와 연결 두 번 누르기는 넘기지 않음 |
| 상위 N개 행 선택(Object Explorer, ERD, 데이터베이스 복사 뒤 표 열기), 데이터 검색의 행 열기 | 그 표 |
| 스크립팅 ▸ CREATE·SELECT 대상 ▸ 새 쿼리 편집기 창 | 선택한 개체 |
| SQL 생성 | 첫 개체(한 글은 한 DB에서만 돈다) |
| 바꾸기 미리 보기의 "쿼리 창에서 열기"(삭제·이름 바꾸기·새 개체·속성 바꾸기 `runChange`, 유지 관리 명령, 테이블 디자이너의 스크립트·저장) | 첫 변경의 개체, 첫 개체, 그 표. 실행 단추는 전부터 개체의 문맥에서 돈다 |
| 결과 그리드 ▸ SQL 생성 ▸ 쿼리 창에서 열기(`ResultSetGenerateSql.show` → `ResultSetGrid.Host`·`QueryResultsView.Listener`·`SqlEditorPanel.Host`의 `openScript`) | 결과 세션의 기본 스키마(없으면 DB). 원본 표 대신 세션을 고른 이유: CREATE TABLE(결과를 담을 표)은 원본 표가 없을 수 있고 그 세션의 기본 스키마에 만든다(`getTargetContainer`). 행 문장은 원본 표 하나에서 오며 PostgreSQL에서는 그 표가 세션의 DB에만 있을 수 있고, 스키마는 글이 쓴다 |
| 사용 권한·로그인/사용자/역할 대화상자의 스크립트(`SecurityCommands.showScript`·`applyScript`) | 대화상자를 연 개체(`SecurityDialect.getContext`, 실행 `SecurityDialect.execute`도 그 개체의 인스턴스에서 돈다) |
| 작업 스케줄러 스크립트(`SchedulerCommands` 삭제, `SchedulerScriptDialog`) | `Backend.location`: pg_cron은 설치된 DB(`PostgreCronScheduler.getDatabase`, 실행도 그 DB), MySQL 이벤트는 그 DB. Oracle은 없음(전과 같음) |

연결만 넘기는 길(`openScript(연결, 글, …)`)로 남은 것과 그 이유(프로시저 실행·스키마 비교 스크립트·수정·새 개체 틀은 2026-11-29부터 개체를 넘긴다,
아래 "주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창"): SQL Server 에이전트(`AgentCommands`·`AgentObjectDialog`)는 문장이
`EXEC msdb.dbo.…`로 DB를 쓴다. 백업·복원 작업 대화상자(`DatabaseTaskDialog`의 SQL Server 백업·복원)는 `BACKUP DATABASE [db]`·`USE [master]`로 DB를 쓴다.
세션 관리자(`SessionManagerPanel`)의 세션 읽기는 서버 전체 보기다. MySQL 이벤트 스케줄러 켜기(`SET GLOBAL event_scheduler`)는 서버 설정이다.

**주 세션을 함께 쓰는 창(쿼리 창마다 별도 연결 = NEVER, 단일 연결):** 주 세션을 옮기지 않는다(`openWindowContext`의 전부터 있던 규칙). 그 세션을 옮기면 함께 쓰는
모든 창이 옮겨지고, PostgreSQL에서는 다른 DB가 다른 서버 세션이며 DB 선택이 스키마만 보여 옮겨진 것이 창에 드러나지 않는다(같은 이름 스키마면 표시가 그대로).
PostgreSQL(데이터베이스마다 세션이 따로)에서는 새 창이 개체의 DB의 주 세션을 함께 쓴다(아래 "주 세션을 함께 쓰는 창: 개체의 DB의 주 세션"). 그 밖의 드라이버에서는
새 창이 주 세션이 있는 곳에서 돌고 그곳을 보인다(창이 보이는 곳과 다른 곳에서 돌지 않음). 수정·프로시저 실행도 2026-11-29부터 같은 길이다(아래
"주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창").

**고르지 않은 것:** 위 명령들도 주 세션을 개체의 곳으로 옮기기(당시 수정·프로시저 실행이 쓰던 `bindDefaultContext`). 창마다 자기 세션인 기본 설정에서도 연결의 기본 DB(탐색기의 굵은 표시, 다음 새 창의 DB)가
"상위 1000개 행 선택" 한 번에 바뀌고, 주 세션을 함께 쓰는 창은 표시 없이 옮겨진다.

**남은 것:**
- ~~주 세션을 함께 쓰는 PostgreSQL 창에서 다른 DB 개체의 글: 기본 DB에서 돈다.~~ 2026-11-28 고침(아래 "주 세션을 함께 쓰는 창: 개체의 DB의 주 세션").
  주 세션을 옮기지 않고 그 DB의 주 세션을 함께 쓰므로 트랜잭션 질문이 필요 없다.
- 파일 ▸ 새 쿼리(Ctrl+N)는 활성 창이 없으면 Object Explorer에서 선택한 노드의 **연결**로 연다(#172의 설계). 노드의 DB는 넘기지 않는다(바꾸지 않음).

**확인:** 아래 "확인 기록 (2026-11-28, 개체에서 연 창의 DB)".

### 주 세션을 함께 쓰는 창: 개체의 DB의 주 세션 (2026-11-28 후속)

**사용자 결정(2026-11-28):** "기존 세션은 건들지 않게 한다. 의도치 않게 시스템이 세션을 바꿔버리면 위험하다." 명령이 부수 효과로 기존 세션을 다른 DB/스키마로
옮기지 않는다(질문이 있어도). 세션을 옮기는 것은 사용자가 직접 하는 것(창의 DB 선택, "기본값으로 설정")뿐이다.

**고치기 전 사실(`origin/command` `bc380caa34` 빌드, 시험 VM, 앱 안 시험 에이전트).** 연결마다 수동 커밋, "쿼리 창마다 별도 연결" NEVER, 기본 DB A
(`chr_shared_session_object_database_a`), PostgreSQL은 "모든 데이터베이스 표시". A와 B에 같은 이름 표 `chr_shared_session_object_database_t`(A: 1, B: 20·21).
창 W1(주 세션)에서 A의 표에 `INSERT … VALUES (5)`(커밋 안 함)한 뒤 B의 표에서 "상위 1000개 행 선택"으로 W2를 열었다.

| DB | W2가 도는 세션 | W2 결과 | W1·트랜잭션 |
|---|---|---|---|
| PostgreSQL 17 | A의 주 세션(`Main <…_a>`, 표시 `public`) | **`1`, `5`**: A의 표, W1의 커밋 안 한 행까지 | A 주 세션 `idle in transaction`, 그대로. B의 주 세션은 B를 펼칠 때 이미 열려 있었음 |
| SQL Server 2022 | 같은 주 세션, `db_name()` A | `20`, `21`(글이 `…_b.dbo.…`) | 그대로(`open_transaction_count` 1) |
| MariaDB 11.8 · MySQL 8.4 | 같은 주 세션, `DATABASE()` A | `20`, `21`(글이 `` …_b.… ``) | 그대로(`INNODB_TRX` `RUNNING`) |

SQL Server·MariaDB·MySQL 공유 창에는 `USE`가 가지 않고, 상위 N개·SELECT 글이 DB를 쓰므로 결과가 맞다. 바꾸지 않았다. "SQL 생성 ▸ DDL" 글은 SQL Server가
`USE …_b` / `GO`로 시작하고(실행하면 그 글이 공유 세션을 옮긴다: 사용자가 실행한 글), MariaDB·MySQL은 `` CREATE TABLE `…_t` ``로 DB가 없다(공유 창에서 실행하면 A에
만든다, 범위 밖으로 보고).

**원인:** 공유 창의 문맥은 늘 연결의 기본 인스턴스의 주 문맥이었다(`QueryWindowContext.open`·`get`의 `DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false)`).
`openWindowContext`는 공유 창에 개체의 이름을 적용하지 않았다(적용하면 주 세션이 옮겨진다). PostgreSQL은 데이터베이스마다 인스턴스(`PostgreDatabase`)와 그 주·메타데이터
세션이 따로 있고(`PostgreDatabase.checkInstanceConnection` 238–252행), 기본 인스턴스는 `PostgreDataSource.setActiveDatabase`만 바꾼다. 그런데 공유 창은 그 B의 주 세션을 쓸 길이 없었다.

**고친 것:** 공유 창이 받은 이름(`setRestoredDefaults`: 개체에서 연 창, 그 창의 New Query, 다시 시작 복원, 다시 연결 뒤 지킨 이름)의 DB가 연결의 인스턴스이면
(`DBSCatalog`인 `DBSInstance`, 기본 인스턴스가 아닌 것) 창이 그 인스턴스의 주 문맥을 함께 쓴다(`QueryWindowContext.shareInstance`). 그 주 세션이 아직 없으면
Object Explorer가 그 DB를 펼칠 때와 같은 `DBSInstanceLazy.checkInstanceConnection`으로 연다(주·메타데이터 한 쌍, 기본 인스턴스는 바뀌지 않음). 어느 세션에도 아무것도
보내지 않는다(`USE`·`SET search_path`·기본 인스턴스 바꾸기 없음). 스키마 이름은 쓰지 않는다: 그 DB의 주 세션의 `search_path`를 바꾸게 되기 때문이다. 창은 그 주 세션의
스키마를 보이고, PostgreSQL 상위 N개·SQL 생성 글은 스키마를 쓴다.
- 한 데이터베이스에 주 세션 하나: 같은 DB에 묶인 공유 창들은 그 DB의 주 세션을 함께 쓴다(설정 "쿼리 창마다 별도 연결" 끔의 뜻 그대로: 창마다 세션을 열지 않음).
- 묶은 DB는 데이터 소스 동안 지킨다. 연결 새로 고침은 세션이 있는 DB 객체를 그대로 두므로(`PostgreDataSource.reloadDatabases`) 그대로다. Disconnect → Connect는
  창이 그 DB 이름을 지켜(`SqlEditorPanel.keepDefaultsOfEndedSession`, `QueryWindowContext.isSharedIn`) 새 데이터 소스에서 다시 묶는다. 다시 시작 복원은 저장한 DB로 묶는다.
- "기본값으로 설정"으로 연결의 기본 DB가 바뀌어도 묶인 창은 따라가지 않는다(묶지 않은 공유 창은 전처럼 따라간다).
- 묶인 창의 DB 선택(PostgreSQL은 그 DB의 스키마 목록)은 그 DB의 주 세션의 스키마를 바꾼다. 사용자가 그 창에서 직접 고른 것이므로 그대로 둔다. 같은 DB에 묶인 창은
  같은 세션이라 함께 바뀌고, 다른 DB의 창은 바뀌지 않는다.
- 여러 작업이 동시에 창의 세션을 열 때: 이름을 먼저 가져간 작업이 묶은 뒤 다른 작업은 묶인 문맥을 다시 받는다(`openWindowContext`). 처음 빌드에서 다시 시작 복원 뒤
  DB 선택이 A의 세션을 읽은 채 실행은 B에서 돈 것을 보고 고쳤다.
- `shareInstance`는 DB 이름 하나만 받는다: 개체 명령 말고도 Query Library 머리말 DB가 창을 한 DB의 주 세션에 묶는 데 쓴다(아래 "Query Library 머리말 DB: 주 세션을 함께 쓰는 창").

**표시:** PostgreSQL 창의 DB 선택·상태 줄은 스키마만 보인다(자기 세션 창과 같음, 새 화면 요소는 만들지 않음). 창이 어느 DB에서 도는지는 실행 결과와 저장된 이름
(`X.public`)으로 드러난다. 표시와 실제 세션이 다른 경우는 없다(선택이 읽는 문맥 = 실행하는 문맥). 탭의 세션 번호(아래 "탭의 세션 번호")는 그 DB의 주 세션의
번호다(같은 문맥을 읽음): A의 창들과 B에 묶인 창들의 번호가 다르다.

**Query Library 머리말 DB:** 이 절을 쓸 때 남겨 둔 경우(머리말 Database가 다른 DB인 Query Library 실행이 공유 세션을 옮김)는 2026-11-29에 같은
`shareInstance`로 고쳤다(아래 "Query Library 머리말 DB: 주 세션을 함께 쓰는 창").

**SQL Server·MySQL/MariaDB·Oracle 등:** DB가 인스턴스가 아니므로(`shareInstance`가 이름을 찾지 못함) 전과 같다: 공유 창은 주 세션이 있는 곳에서 돌고 그곳을 보인다.

## 주 세션을 다른 DB로 옮기기 전 트랜잭션 질문 (2026-11-28)

**2026-11-29에 지움:** 명령들이 주 세션을 옮기지 않게 되어 이 질문(`SourceScripts.confirmBindDefaultContext`)과 `bindDefaultContext`를 지웠다(아래
"주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창"). 아래는 그때의 기록이다. "기본값으로 설정"의 질문(`ExitTransactionCheck.confirmDefaultChange`)은 그대로다.

**보고:** 다른 작업(Task R)의 코드 읽기. Modify 등이 주 세션을 개체의 DB로 옮길 때 열린 트랜잭션을 묻지 않는다(재현 전).

**사실(`origin/command` `6d1b523e53` 빌드, 시험 VM, 연결마다 수동 커밋·"쿼리 창마다 별도 연결" = NEVER, 시험 DB `chr_bdct_a` = A(연결 기본값)·`chr_bdct_b` = B).**
주 세션을 함께 쓰는 창에서 A의 표에 `INSERT`(커밋 안 함)한 뒤 B의 개체에 수정(Modify), 프로시저 실행(실행), 새 뷰를 했다.

| DB | 명령 뒤 주 세션 | 열린 트랜잭션 | 결함 |
|---|---|---|---|
| PostgreSQL 17 | 연결의 기본 인스턴스가 B, 창은 B의 주 세션에서 실행(쿼리 관리자: 창의 문맥에 트랜잭션 없음) | A의 주 세션에 남음: `pg_stat_activity` `idle in transaction`(`backend_xid` 있음), 다른 세션에서 본 A의 행 0개. 질문·표시 없음 | 있음(세 명령 모두) |
| SQL Server 2022 | 같은 세션이 `USE chr_bdct_b` | 같은 세션에 그대로(`open_transaction_count` 1), 창의 문맥이 쥐고 있음 | 없음 |
| MariaDB 11.8 | 같은 세션이 `USE chr_bdct_b` | 같은 세션에 그대로(`INNODB_TRX` `RUNNING`, 바뀐 행 1), 창의 문맥이 쥐고 있음 | 없음 |

SQL Server·MariaDB는 서버 세션 하나가 모든 DB를 다루고 `USE`는 트랜잭션을 끝내지 않으므로, 창이 계속 그 트랜잭션을 보이고 끝낼 수 있다. 그래서 바꾸지 않았다.

**원인:** `SourceScripts.bindDefaultContext`가 작업 스레드에서 `DBExecUtils.setExecutionContextDefaults`를 부른다. PostgreSQL 주 문맥(격리되지 않은 문맥)은
DB를 바꾸면 다시 연결하지 않고 연결의 기본 인스턴스만 바꾼다(`PostgreExecutionContext.setDefaultCatalog` → `PostgreDataSource.setActiveDatabase`).
그래서 이전 DB의 주 세션이 트랜잭션을 쥔 채 남고, 주 세션을 함께 쓰는 창은 새 DB의 세션으로 옮겨 간다. "기본값으로 설정"(`DefaultObjectCommands`)만 그 전에
`ExitTransactionCheck.confirmDefaultChange`로 물었다. 같은 `bindDefaultContext`를 부르는 길: 수정과 ALTER 대상(새 쿼리 창·클립보드), Go to Source Code·
오류로 가기(Oracle 패키지, `generateAlter`), 새 뷰/프로시저/함수와 "새 &lt;종류&gt;" 틀(`generateNew`), 프로시저 실행, Schema Compare 스크립트(`generateContainerScript`).

**고친 것:** `SourceScripts.confirmBindDefaultContext`(EDT, 메모리만 읽음)가 바꿀 개체의 데이터베이스 인스턴스가 주 문맥의 인스턴스와 다를 때만
`confirmDefaultChange`로 커밋/롤백/취소를 묻는다(기본값으로 설정과 같은 판단과 같은 질문). 위 명령들은 진행 대화상자를 띄우기 전에 이것을 부르고,
취소나 끝내기 실패면 아무것도 하지 않는다(주 세션·트랜잭션 그대로). 커밋·롤백은 사용자가 고른 것만 한다. 같은 인스턴스 안의 DB·스키마 바꾸기는
묻지 않는다. 질문 글은 기본값으로 설정의 글을 그대로 쓴다: 이 명령들도 그 DB를 주 세션의 기본값으로 만들기 때문이다(새 대화상자·새 글 없음).
글에 넣는 이름은 옮겨 갈 DB다(스키마 이름 `public`만으로는 어느 DB인지 알 수 없다).

**고르지 않은 것:** `bindDefaultContext` 안에서(작업 스레드) EDT로 묻는 것. 진행 대화상자가 떠 있는 동안 질문과 커밋·롤백 진행 대화상자가 겹치고,
취소하면 이미 시작한 작업을 되돌려야 한다. 명령마다 한 줄씩이지만 기본값으로 설정과 같은 자리(작업 전, EDT)에서 묻는다.

**확인:** 아래 "확인 기록 (2026-11-28, 주 세션을 다른 DB로 옮기기 전 트랜잭션 질문)".

**사용자 결정 뒤 남은 것(2026-11-28, 구현하지 않음):** 사용자 결정 "기존 세션은 건들지 않게 한다. 의도치 않게 시스템이 세션을 바꿔버리면 위험하다."에 따르면
위 명령들도 주 세션을 옮기지 말고(질문이 있어도) 개체의 DB/스키마에 묶인 창에서 돌아야 한다. 사실(`bc380caa34` 빌드와 코드):
- `bindDefaultContext`는 창마다 자기 세션인 연결에서도 주 세션을 옮긴다(새 창의 자기 세션이 주 세션의 기본값을 복사하기 때문). PostgreSQL 다른 DB는 기본 인스턴스를
  바꾸고(질문 뒤), 같은 DB 다른 스키마는 주 세션의 `search_path`를, SQL Server·MySQL/MariaDB는 주 세션에 `USE`를, Oracle은 `ALTER SESSION SET CURRENT_SCHEMA`를
  묻지 않고 보낸다. "ALTER 대상 ▸ 클립보드"(`SourceEditCommands` 156행)는 창이 없는데도 옮긴다. 주 세션을 함께 쓰는 창은 모델 이벤트로 선택을 다시 읽어 옮겨진 것을 보인다
  (위 "다른 곳에서 바뀐 세션의 DB"). 같은 DB 안의 스키마 바꾸기도 세션 바꾸기이지만 묻지 않는다.
- 같은 길(`runWhereObjectIs` → `setRestoredDefaults`, PostgreSQL 공유 창은 그 DB의 주 세션)로 바꾸려면: `openSourceScript`·`openScript`가 개체를 받고
  (`SourceEditCommands` 158·170행, `RoutineCommands` 142·165·257행, `NewObjectCommands` 302·334행, `SchemaComparePanel` 720행), `generateAlter`·`generateNew`·
  `generateContainerScript`는 기본값을 바꾸지 않은 개체 인스턴스의 주 문맥으로 글을 만든다. 그러면 `confirmBindDefaultContext`(호출 6곳)와 그 문서가 쓸모없어진다.
- 작지 않은 이유: 객체 관리자 일부가 글을 만들 때 문맥의 기본 DB를 본다. 묶지 않으면 같은 명령의 글이 바뀐다. MySQL 트리거는 기본 DB가 트리거의 DB와 다르면
  `USE db` … `USE 원래 db`를 넣고(`MySQLTriggerManager` 72–80행, 이벤트·시퀀스 관리자도 같은 모양), SQL Server는 `USE [db]` … `USE [기본 db]`를 넣는다
  (`SQLServerObjectManager.addDatabaseSwitchAction1/2`, 앞에 이미 `getDatabaseSwitch`의 `USE` 배치가 있음). 또 공유 창에서는 스키마·SQL Server/MySQL의 DB를 창에
  정할 수 없어(세션을 옮기게 됨) 글이 이름을 써야 한다: MySQL/MariaDB의 "SQL 생성 ▸ DDL"은 표 이름에 DB가 없다(아래 확인 기록). 드라이버 4개 × 명령 6개 × 개체 종류의
  글을 다시 확인해야 하고 바뀌는 파일이 7개 이상이라 이번 범위(작은 변경)를 넘는다.
- 제안: (1) 위 호출을 개체를 넘기는 길로 바꾸고 `bindDefaultContext`·`confirmBindDefaultContext`를 지운다. (2) 글 만들기용 문맥은 개체의 DB 인스턴스 주 문맥으로 하되,
  기본 DB를 보는 관리자의 `USE` 쌍은 쿼리 창 글에서 빼거나(창이 이미 그 DB) 그대로 두고 확인한다. (3) MySQL/MariaDB DDL·CREATE 대상 글에 DB 이름을 넣는다
  (SQL Server의 `inObjectDatabase`와 같은 자리). (4) 스키마만 다른 경우(PostgreSQL `search_path`, Oracle 현재 스키마)는 (1)로 함께 사라진다.
## 자기 세션을 다른 DB로 다시 연결할 때 (2026-11-28)

**보고:** Query Library 작업(Task V)이 지나가며 본 것. PostgreSQL에서 자기 세션을 쓰는 창이 다른 DB로 옮기면 열린 트랜잭션이 묻지 않고 롤백되고,
그 뒤의 트랜잭션은 앱에 보이지 않는다.

**사실(`origin/command` `bc380caa34` 빌드, 시험 VM PostgreSQL 17, 연결 수동 커밋, "쿼리 창마다 별도 연결" ALWAYS, "모든 데이터베이스 표시",
기본 DB `chr_pg_own_session_reconnect_a` = A, 다른 DB `…_b` = B).** PostgreSQL 세션은 DB 하나에 묶이므로 자기 세션(격리 문맥)의 DB를 바꾸면
`PostgreExecutionContext.setDefaultCatalog`가 세션을 끊고 B에 다시 연다. 그 길에 닿는 곳(코드 검색, `setExecutionContextDefaults`·`setDefaultCatalog`·
`setDefaultSchema`를 부르는 모든 곳):

| 길 | 열린 트랜잭션이 있을 수 있나 | 고치기 전 |
|---|---|---|
| Query Library 창의 실행, 머리말 Database가 다른 DB(`QueryParameterBinder.applyDefaults`, 트리의 Run·Run on…·F5 모두 같은 `SqlEditorPanel.execute`) | 있음 | 질문 없이 다시 연결, A의 행이 롤백됨(아래 확인 기록) |
| 개체에서 연 창·New Query·다시 시작 복원(`SqlEditorPanel.openWindowContext`, `setRestoredDefaults`) | 없음(세션을 처음 열 때만) | 다시 연결은 맞지만 그 뒤 트랜잭션이 앱에 보이지 않음 |
| 쿼리 창의 DB 선택(`SqlEditorPanel.changeDefaultObject`) | — | PostgreSQL에서는 세션 DB의 스키마만 보여(`ContextDefaultObjectsReader`) 다른 DB를 고를 수 없음. 고를 수 있으면 이미 묻는다(`confirmEndSession`) |
| 예약 실행(`QueryScheduler`), 데이터 전송, 저장된 작업(`DBTaskUtils`) | 없음(새로 연 자기 세션) | 다시 연결 뒤 쿼리 관리자가 추적하지 않음, 문맥이 처음 DB의 목록에 남음 |
| AI 에이전트(`AgentSql`) | — | DB마다 그 DB 인스턴스에서 세션을 열어 다시 연결하지 않음 |
| 기본값으로 설정(주 세션을 옮기는 명령. 수정·프로시저 실행 등의 `SourceScripts.bindDefaultContext`는 2026-11-29에 지움) | — | 주 문맥은 격리 문맥이 아니라 다시 연결하지 않음(위 절) |

**원인:**
1. 묻지 않은 롤백: `QueryParameterBinder.applyDefaults`가 작업 스레드에서 `DBExecUtils.setExecutionContextDefaults`를 부르고, PostgreSQL 격리 문맥은
   다시 연결하며 `JDBCDataSource.closeConnection(…, doRollback = !autoCommit)`으로 트랜잭션을 롤백한다. 실행 전(EDT)에 묻는 곳이 없었다.
2. 추적 잃음: 다시 연결이 `disconnect()`(= `disconnect(true)`)로 쿼리 관리자에 문맥이 닫혔다고 알리고(`QMMCollectorImpl.handleContextClose`,
   `QMTransactionTracker.handleContextClose`), 다시 연 `connect(…, addContext = false)`는 다시 열렸다고 알리지 않았다. 그래서 그 뒤의 문장은 기록되지 않고
   (`QMUtils.getTransactionState`: 기록 없음 = 트랜잭션 없음) 상태 줄, 창 닫기 질문, 연결 끊기 질문이 트랜잭션을 보지 못했다. 같은 다시 연결이
   `setOwnerInstance`만 바꿔 문맥은 A의 문맥 목록에 남고 B의 목록에는 들지 않았다(닫을 때 B에서 빼려 해 A 목록에 닫힌 문맥이 남음).

**고친 것:**
- 모델(`JDBCExecutionContext`): 쿼리 관리자에 닫혔다고 알린 문맥이 다시 연결하면 `connect`가 새 서버 세션을 다시 알린다(`handleContextOpen`, 커밋 방식과 함께).
  다른 인스턴스로 옮기는 `reconnectTo`는 옛 인스턴스의 목록에서 빼고(`closeContext(true)`) 새 인스턴스에 연다(`connect(…, addContext = true)`).
  커밋 방식·격리 수준은 전과 같이 연결의 기본값이다(그 인스턴스에서 새로 연 세션과 같음). `PostgreExecutionContext.setDefaultCatalog`는 이것을 부른다.
  다시 연결하는 드라이버는 PostgreSQL 실행 문맥뿐이다(검색: 다른 `setDefaultCatalog`·`setDefaultSchema` 구현은 같은 세션에서 `USE`·`SET`).
  PostgreSQL 실행 문맥을 그대로 쓰는 Greenplum·GaussDB·Kingbase와 PostgreSQL 서버 종류도 같은 길이다.
- 창(`QueryLibraryEditor.prepareExecution`, EDT): 매개변수를 물은 뒤, 쿼리의 Database가 창의 자기 세션과 다른 DB 인스턴스면
  (`QueryParameterBinder.changesInstance`, 메모리만 읽음) 창 닫기·DB 선택과 같은 질문을 한다(`SqlEditorPanel.confirmEndSession` → 커밋/롤백/취소,
  트랜잭션이 열려 있을 때만). 취소하면 실행하지 않는다. 새 글은 없다. 주 세션을 함께 쓰는 창은 이 질문을 하지 않는다(`confirmEndSession`은 자기 세션만 본다).
  그런 창은 세션을 옮기지 않고 그 DB의 주 세션에서 돌며, 떠나는 세션의 트랜잭션은 남는 창이 없을 때만 따로 묻는다(아래 "Query Library 머리말 DB: 주 세션을 함께 쓰는 창").

**고르지 않은 것:** 다시 연결할 때 문맥의 커밋 방식을 지키는 것. 스마트 커밋이 바꾼 상태는 쿼리 관리자에 닫힐 때 지워지므로
(`SmartCommit.handleContextClose`) 모드만 지키면 둘이 어긋난다. 전처럼 연결의 기본값으로 연다(아래 "확인하지 못한 것").

**확인:** 아래 "확인 기록 (2026-11-28, 자기 세션을 다른 DB로 다시 연결할 때)".

## 탭의 세션 번호 (2026-11-28, 앱 변경 #193)

**요청:** "쿼리탭에 ssms처럼 세션 번호를 표기할 수 있나?" 정한 모양: 쿼리 창 탭 제목 끝에 서버 세션 번호를 괄호로(`SQLQuery1.sql - PG (52)`,
SSMS의 SPID처럼), 탭 도구 설명에 한 줄 `Session ID : 52`. 도구 설명 글은 사용자가 정했다("단순하게 … Session ID : 52 와 같은 방식으로만").
한국어 화면도 같은 글이다. 새 설정·상태 줄 변경은 없다.

**모양:** 제목 `파일 - 연결 (번호)*` — 고침 표시 `*`는 SSMS처럼 맨 끝에 남는다. 도구 설명은 지금의 두 줄(파일, 연결) 아래 `Session ID : 번호` 한 줄.
창에 세션이 없으면(연결 전, 끊김, 번호를 읽을 수 없는 DB) 제목과 도구 설명은 전과 같다.

**어느 세션인가:** 창이 실제로 실행되는 세션(`QueryWindowContext`): 창마다 별도 연결이면 창 자신의 세션, "쿼리 창마다 별도 연결" = 안 함이면 연결의 주 세션.
주 세션을 함께 쓰는 창들은 같은 번호를 보인다(의도한 것). PostgreSQL에서 다른 DB에 묶인 공유 창은 그 DB의 주 세션 번호다(위 "주 세션을 함께 쓰는 창: 개체의 DB의 주 세션").

**읽는 때와 자리:** 세션을 여는 `JDBCExecutionContext.connect`에서 한 번, 새 세션에서 무엇보다 먼저(자동 커밋·격리 수준을 정한 뒤, 부트스트랩 문장·상태 초기화 전,
인스턴스의 배타 잠금 아래) 읽어 문맥이 들고 있다(`getServerSessionId`, 메모리만, 아무 스레드). 연결 끊기는 지우고(`disconnect`), Invalidate/Reconnect는
같은 문맥 객체로 새 세션을 열므로(`invalidateContext` → `connect`) 새 번호를 읽는다. 닫힌 세션을 다시 여는 `getConnection`, 자기 세션을 다른 DB 인스턴스로 옮기는 `reconnectTo`(위 "자기 세션을 다른 DB로 다시 연결할 때", 쿼리 관리자에 다시 알린 뒤 읽음)도 같다.
쿼리 창은 EDT의 1초 타이머(열린 트랜잭션 표시와 같은 타이머)에서 지금 세션의 저장된 번호만 비교하고 바뀌면 탭을 고친다(`SqlEditorPanel.refreshSessionId`
→ `Host.titleChanged`). 그래서 다시 연결, 연결 바꾸기, 창 닫기, 주 세션이 다른 DB 인스턴스로 옮겨 간 경우를 따로 듣지 않는다.

**DB별 읽기**(방언이 `JDBCServerSessionIdReader`를 구현, UI에는 드라이버 판단이 없다):

| DB | 읽기 | 부작용이 없는 근거(시험 VM, 수동 커밋) |
|---|---|---|
| PostgreSQL | pgjdbc `getBackendPID()`(로그인 때 받은 BackendKeyData, 문장 없음). PostgreSQL 서버만(`probeTransaction`과 같은 조건) | 수동 커밋 드라이버는 트랜잭션 밖 아무 문장 앞에 BEGIN을 보내므로 문장을 쓰지 않음. 읽은 뒤 드라이버 상태 `IDLE`, `pg_stat_activity` `idle`·`backend_xid`·`xact_start` 없음(SERIALIZABLE 포함) |
| SQL Server | `SELECT @@SPID`(`probeTransaction`과 같은 서버) | 표를 읽지 않는 SELECT는 IMPLICIT_TRANSACTIONS에서도 트랜잭션을 시작하지 않음: `@@TRANCOUNT` 0, `sys.dm_tran_session_transactions` 행 없음, `open_transaction_count` 0(SERIALIZABLE 포함) |
| MySQL·MariaDB | `SELECT CONNECTION_ID()` | 드라이버의 서버 상태 SERVER_STATUS_IN_TRANS 꺼짐, MariaDB `@@in_transaction` 0, `INNODB_TRX` 행 없음(MariaDB·MySQL Connector/J·MariaDB 드라이버로 MySQL, SERIALIZABLE 포함) |
| Oracle | PL/SQL `BEGIN ? := SYS_CONTEXT('USERENV', 'SID'); END;` | `DBMS_TRANSACTION.LOCAL_TRANSACTION_ID` null, `V$TRANSACTION` 행 없음, `V$SESSION.TADDR` null(SERIALIZABLE 포함). 같은 값을 `SELECT … FROM dual`로 읽으면 SERIALIZABLE 세션에서 트랜잭션이 시작되었다(`7.15.2151`). 그래서 PL/SQL 호출 |
| 그 밖 | 읽지 않음 | 번호 없음, 탭은 전과 같다 |

읽기 문장은 트랜잭션 질의(`JDBCTransactionProbe`)처럼 JDBC 연결에 직접 보낸다. 쿼리 관리자·쿼리 기록에 사용자 문장으로 남지 않는다(확인 기록).
시간 제한 5초, 실패하면 기록만 하고 번호 없이 지나간다. 연결을 열 때 SQL Server·MySQL/MariaDB·Oracle 세션마다(주·메타데이터·창·도구 문맥) 왕복이 하나 는다.

**구조:**

| 자리 | 바뀐 것 |
|---|---|
| `DBCExecutionContext` (`com.seizia.chromite.model`) | 기본 메서드 `getServerSessionId()`(null) |
| `JDBCServerSessionIdReader` (새, `com.seizia.chromite.model.jdbc`) | 방언의 읽기 능력, `read`(던지지 않음) |
| `JDBCExecutionContext` | `connect`에서 읽어 둠, `disconnect`에서 지움, `getServerSessionId` |
| `PostgreDialect`, `SQLServerDialectMssql`, `MySQLDialect`, `OracleSQLDialect` | `readServerSessionId` |
| `SqlEditorPanel` | `getTitle`, `getToolTip`, `getServerSessionId`, `refreshSessionId`(1초 타이머) |
| `EditorMessages` (`_ko`) | `editor_sql_tip_session_id` = `Session ID : {0}`(두 언어 같음) |

**고르지 않은 것:** 창이 세션에 묶일 때 작업 스레드에서 따로 읽기. Invalidate/Reconnect는 창의 묶음을 바꾸지 않고 같은 문맥에 새 세션을 열므로 창이 그때를
따로 알아야 하고, 사용자의 트랜잭션 안에서 읽게 될 수 있다. 세션을 여는 자리에서 읽으면 모든 길(연결, 다시 연결, 다시 열기)이 같고 언제나 빈 세션이다.

**확인:** 아래 "확인 기록 (2026-11-28, 탭의 세션 번호)".

## Query Library 머리말 DB: 주 세션을 함께 쓰는 창 (2026-11-29)

**사용자 결정(2026-11-28, 위 "주 세션을 함께 쓰는 창: 개체의 DB의 주 세션"):** 명령이 부수 효과로 기존 세션을 다른 DB/스키마로 옮기지 않는다.

**고치기 전 사실(`origin/command` `069caa9a69` 빌드, 시험 VM PostgreSQL 17, 연결 수동 커밋, "쿼리 창마다 별도 연결" NEVER, "모든 데이터베이스 표시",
기본 DB `chr_qlss_a` = A, 다른 DB `chr_qlss_b` = B·`chr_qlss_c` = C, 각 DB에 같은 이름 표 `chr_qlss_t`(A: 1, B: 20, C: 30)와 스키마 `chr_qlss_s`의 같은 이름 표(A: 6, B: 25, C: 35)).**
W1(주 세션)이 A에 `INSERT … VALUES (5)`(커밋 안 함), W2는 같은 연결의 다른 창. Query Library 트리의 Run으로 머리말 Database가 B인 항목, 이어서 C인 항목을 실행했다.

| 실행 | 실행이 돈 곳 | 연결의 기본 인스턴스 | W1·W2 |
|---|---|---|---|
| 머리말 B | **A**의 주 세션(`current_database()` A, 서버 세션 202759) | **B로 옮겨짐**(`PostgreDataSource.setActiveDatabase`) | W1 탭 번호가 B의 주 세션(202785)으로 바뀌고 상태 줄에 열린 트랜잭션이 없음. A의 주 세션은 `idle in transaction`(xid 있음)으로 남아 어느 창에도 보이지 않음. W2의 다음 실행은 B |
| 머리말 C | **B**의 주 세션 | **C로 옮겨짐** | 모든 공유 창이 C(탭 번호 C의 세션), A의 트랜잭션은 그대로 보이지 않음 |
| 머리말 B + Schema `chr_qlss_s` | **C**의 `public`(결과 `30`: 다른 DB의 다른 표) | B로 옮겨지고 B의 주 세션 `search_path`가 `chr_qlss_s`로 바뀜 | 다음 실행부터 모든 공유 창이 B·`chr_qlss_s` |

PR 398의 질문(`QueryLibraryEditor.prepareExecution` → `SqlEditorPanel.confirmEndSession`)은 공유 창에서 묻지 않는다(자기 세션만 봄). 질문을 더해도 사용자 결정에 맞지 않는다.

**원인:** `SqlEditorPanel.prepareAndSchedule`이 창의 문맥을 먼저 잡고(`openWindowContext`) 그 문맥으로 `SqlParameterBinding.prepare`를 부른다.
`QueryParameterBinder.applyDefaults`는 그 문맥에 `DBExecUtils.setExecutionContextDefaults`를 부르고, 격리되지 않은 PostgreSQL 주 문맥은 다른 DB로
다시 연결하지 않고 연결의 기본 인스턴스만 바꾼다(`PostgreExecutionContext.setDefaultCatalog` → `PostgreDataSource.setActiveDatabase`; 스키마는 새 기본
인스턴스의 주 세션에 `SET search_path`). 그래서 공유 세션이 옮겨지고 실행은 이미 잡은 옛 문맥에서 돈다.

**고친 것:**
- 실행이 문맥을 잡을 때(`SqlEditorPanel.getRunContext`, 작업 스레드, `openWindowContext` 바로 뒤) 바인딩의 DB/스키마(`SqlParameterBinding.getDefaultObjectNames`,
  `QueryParameterBinder`가 머리말 값을 돌려줌)를 본다. 창이 주 세션을 함께 쓰고 그 세션의 인스턴스가 데이터베이스(`DBSCatalog`, PostgreSQL)이면 PR 400의
  `QueryWindowContext.shareInstance`로 창을 그 DB의 주 세션에 묶고 그 문맥으로 실행한다. 어느 세션도 옮기지 않는다(기본 인스턴스·`search_path` 그대로).
  그 DB의 주 세션이 없으면 Object Explorer가 그 DB를 펼칠 때와 같이 연다. 바인딩의 `applyDefaults`는 이미 맞는 기본값을 보고 아무것도 하지 않는다.
- 창은 실행 뒤에도 그 DB에 묶여 있다(DB 선택·상태 줄·탭 번호가 그 DB의 주 세션, 연결 새로 고침·끊고 다시 연결해도 그대로: 위 절의 묶음과 같음).
  머리말 Database를 비우면 묶인 DB에서 돈다.
- `shareInstance`: 다른 DB에 묶인 창은 연결의 기본 인스턴스에도 묶는다. 전에는 기본 인스턴스이면 아무것도 하지 않아, B에 묶인 창에서 머리말 A인 실행이 B에서
  돌고 `applyDefaults`가 기본 인스턴스의 주 세션 스키마를 바꿀 수 있었다. 묶지 않은 창(기본 인스턴스를 따라감)은 전과 같다.
- 자기 세션 창(PR 398의 질문 뒤 다시 연결), SQL Server·MySQL/MariaDB·Oracle 등 DB가 인스턴스가 아닌 드라이버는 바꾸지 않았다(아래).
- 떠나는 세션의 열린 트랜잭션(2026-11-29 검토 뒤): 창이 옮겨 가면 그 창이 떠난 주 세션의 트랜잭션은 열린 채 남는다. 그 세션에서 도는 다른 쿼리 창이 있으면
  그 창의 상태 줄·닫기 질문이 계속 보이므로 묻지 않는다. 남는 창이 없으면 어느 창에도 보이지 않게 되므로(아래 사실) 실행 전(EDT, 매개변수를 물은 뒤)
  묻는다(`QueryLibraryEditor.confirmLeaveSession` → `ExitTransactionCheck.confirmWindowMove`: 커밋/롤백/취소, 트랜잭션이 열려
  있을 때만, 취소하면 실행하지 않음). 같은 판단(`QueryParameterBinder.changesInstance`: 머리말 DB가 창의 세션과 다른 DB 인스턴스)이 자기 세션 창에서는
  PR 398의 질문(`SqlEditorPanel.confirmEndSession`)으로 간다.
  - 묻지 않을 때의 사실(`6d477fa555`의 동작을 고친 빌드에서 시험 에이전트가 `shareInstance`를 직접 불러 재현): 라이브러리 창 L만 A에 있고 L이 A에
    `INSERT`(커밋 안 함)한 뒤 B로 옮기면, L의 상태 줄에서 "열린 트랜잭션"이 사라지고 탭 번호가 B의 세션이 된다. L을 닫을 때 묻지 않는다(자기 세션이 아님).
    A의 트랜잭션은 연결 끊기·끝낼 때의 질문(`ExitTransactionCheck.findActive`는 모든 인스턴스의 문맥을 봄)과 유휴 트랜잭션 알림(연결 종류의 기간 뒤,
    코드 읽기)에서만 다시 보인다.
  - 질문: 기본값으로 설정(`confirmDefaultChange`)과 같은 제목("커밋하지 않은 트랜잭션"), 같은 첫 부분(주 세션과 트랜잭션 목록), 같은 커밋/롤백/취소 단추이고
    설명 줄만 다르다(`ConnectionMessages.window_move_txn_message`): "이 창이 {2}(으)로 옮겨 가면 트랜잭션은 지금 세션에 남고 이 창에서는 보이지 않습니다.
    먼저 커밋하거나 롤백하시겠습니까? 취소하면 실행하지 않습니다."(영어 "If this window moves to {2}, the transaction stays in the current session and is no
    longer shown in this window. Commit or roll back first? Cancel does not run the query."). 끝내기에 실패하면 창 세션 질문의 오류 글(`window_txn_error`)을 쓴다.
    처음 고친 빌드(`fb0c7b17ee`)는 기본값으로 설정의 글을 그대로 써서 "기본값으로 만들면 주 세션이 … 옮겨 가고"라고 했다. 여기서는 아무것도 기본값이 되지 않고
    주 세션도 옮기지 않으므로 검토에서 이 글로 바꿨다. 두 메서드는 질문 코드를 함께 쓴다(`ExitTransactionCheck.confirmMainSession`).

**스키마(고른 것):** 머리말 Schema는 공유 세션의 지금 스키마와 같을 때만 그대로 실행한다. 다르면 실행하지 않고(문장 없음, 세션 그대로) Messages에
"먼저 창의 스키마 목록에서 {0}을(를) 고르십시오."(영어 "Choose schema {0} in the window's schema list first.",
`EditorMessages.editor_sql_query_schema_shared`, 2026-11-29 검토 뒤 짧게: 화면 글은 설명 없이 짧게)를 낸다. 창은 이미 그 DB에 묶였으므로 창의 스키마 목록이 그 DB의 스키마를 보이고, 사용자가 고르면(직접 하는
세션 바꾸기, 위 절과 같이 허용) 다음 실행이 돈다. 같은 DB 안에서 스키마만 다른 머리말(Database 칸 비움)도 같다.
- 이유: 그 DB의 주 세션은 같은 DB에 묶인 공유 창과 그 DB가 기본이 된 뒤의 공유 창이 함께 쓴다. `search_path`를 바꾸면 그 창들이 말없이 옮겨진다(사용자 결정에 어긋남).
  바꾸지 않고 실행하면 이름에 스키마를 쓰지 않은 표가 다른 스키마의 표로 풀린다(고치기 전 세 번째 줄처럼 다른 데이터를 읽거나 쓴다). 그래서 실행하지 않고 사용자가 고르게 했다.
  PR 400도 공유 창에 개체의 스키마를 적용하지 않는다(위 절).
- 검사는 바인딩이 실제로 바꿀 경우와 같게 했다: DB 이름으로 찾은 그 DB의 스키마(`DBExecUtils.changeDefaultObject`와 같은 조회)가 있고 지금 스키마가 아니며
  시스템 스키마가 아닐 때만(`PostgreExecutionContext.changeDefaultSchema`는 시스템 스키마를 정하지 않음). 연결 목록에 없는 DB 이름이면 바인딩도 스키마를 찾지 못하므로 그대로 실행한다.
- 고르지 않은 것: 새 질문(새 대화상자), 실행만을 위한 세션(설정 "쿼리 창마다 별도 연결"과 어긋나는 새 동작). 사용자가 SQL Server·MySQL/MariaDB의 `USE`처럼
  "공유 세션 대신 자기 세션"을 정하면 스키마도 그 결정을 따라 다시 볼 수 있다.

**SQL Server·MariaDB(·MySQL) 공유 창(바꾸지 않음, 사용자 결정 대기):** 머리말 Database가 다른 DB이면 `applyDefaults`가 공유 주 세션에 `USE`를 보낸다.
같은 서버 세션이라 열린 트랜잭션은 끝나지 않고 창들이 계속 보인다(`open_transaction_count` 1, `INNODB_TRX` `RUNNING`). 실행은 그 DB에서 돌지만, 같은 연결의 모든 공유 창이
말없이 그 DB로 옮겨진다(W1의 다음 실행이 B). 결정(공유 세션에 `USE` vs 실행 창의 자기 세션)이 나기 전이라 그대로 두었다. Oracle 등 스키마만 있는 드라이버의 머리말 Schema도
공유 세션에 `ALTER SESSION SET CURRENT_SCHEMA`를 보내는 같은 모양이다(코드 읽기, 시험하지 않음).

**구조:**

| 자리 | 바뀐 것 |
|---|---|
| `SqlParameterBinding` | 기본 메서드 `getDefaultObjectNames()`(null) |
| `QueryParameterBinder` | `getDefaultObjectNames()`: 머리말 Database/Schema |
| `SqlEditorPanel` | `prepareAndSchedule`이 `getRunContext`로 실행 문맥을 정함 |
| `QueryLibraryEditor` | `confirmLeaveSession`: 떠나는 세션에 남는 창이 없으면 열린 트랜잭션을 물음(자기 세션은 전과 같이 `confirmEndSession`) |
| `QueryWindowContext` | `shareInstance`가 다른 DB에 묶인 창을 기본 인스턴스에도 묶음 |
| `EditorMessages` (`_ko`) | `editor_sql_query_schema_shared` |

**확인:** 아래 "확인 기록 (2026-11-29, Query Library 머리말 DB: 주 세션을 함께 쓰는 창)".

## 주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창 (2026-11-29)

**사용자 결정(2026-11-29, "주 세션을 옮기는 나머지 명령(수정·프로시저 실행 등)도 고칠까요?"에):** "고친다". 규칙은 2026-11-28의 "기존 세션은 건들지 않게 한다.
의도치 않게 시스템이 세션을 바꿔버리면 위험하다."다. 세션을 옮기는 것은 창의 DB/스키마 선택과 "기본값으로 설정"뿐이다.

**고치기 전 사실(`origin/command` `e597c96cc4` 빌드, 시험 VM, 연결 수동 커밋, 기본 DB/스키마 A, 다른 DB/스키마 B).** 수정(Modify)·ALTER 대상(새 창·클립보드)·
프로시저 실행·새 뷰/프로시저/함수·"새 &lt;종류&gt;" 틀·Schema Compare 스크립트·Go to Source Code·오류로 가기가 스크립트를 만들기 전에 연결의 주 세션을 개체의
DB/스키마로 옮겼다(`SourceScripts.bindDefaultContext` → `DBExecUtils.setExecutionContextDefaults`). 창마다 자기 세션인 연결에서도 옮겼다(새 창이 주 세션의 기본값을 복사).
- PostgreSQL("쿼리 창마다 별도 연결" 안 함, 모든 데이터베이스 표시): W1이 A에 `INSERT`(커밋 안 함)한 뒤 B 함수의 수정 → PR 396의 질문. 롤백하자 연결의 기본 인스턴스가 B로
  바뀌고 W1의 탭 번호가 B의 주 세션 번호가 됨(W1의 다음 문장이 B에서 돎). A의 다른 스키마(`chr_ckms_s`) 함수의 수정은 A 주 세션의 `search_path`를 그 스키마로 바꿈.
- SQL Server·MariaDB(같은 설정): B의 수정이 주 세션에 `USE B`. W1의 다음 `SELECT DB_NAME()`/`DATABASE()`가 B, 열린 트랜잭션은 같은 세션에 남음.
  Oracle(기본 스키마 A): B의 수정이 주 세션에 `ALTER SESSION SET CURRENT_SCHEMA=B`(`v$session.schemaname` B).

**고친 것:**
- 명령은 어느 세션의 기본값도 바꾸지 않는다. `SourceScripts.bindDefaultContext`와 그 앞의 질문 `confirmBindDefaultContext`를 지웠다(질문 호출 7곳: `SourceEditCommands` 2,
  `NewObjectCommands` 1, `RoutineCommands` 3, `SchemaComparePanel` 1; 바인드 호출 4곳: `SourceScripts` 3, `RoutineCommands` 1. 다른 번들·`plugin.xml`·메시지 파일에는 없음).
- 스크립트를 받는 창은 개체에서 연 다른 창과 같은 길로 연다(`MainFrame.runWhereObjectIs` → `SqlEditorPanel.setRestoredDefaults`). `QueryWindows.openSourceScript`가 개체를 받고
  (수정·Go to Source·오류로 가기: 그 개체, 새 틀: 폴더의 개체), 프로시저 실행은 `openCallScript(연결, 루틴, …)`(아래 "프로시저 실행 창의 스키마"), Schema Compare는 `openScript(연결, 대상, …)`이다.
  자기 세션 창은 그 세션만 개체의 DB/스키마로 열고, 주 세션을 함께 쓰는 PostgreSQL 창은 그 DB의 주 세션을 함께 쓴다(`QueryWindowContext.shareInstance`, 스키마는 바꾸지 않음).
  주 세션을 함께 쓰는 다른 드라이버의 창은 주 세션이 있는 곳에서 연다. 서버 확장 폴더의 틀(Altibase 계열)은 전처럼 개체를 넘기지 않는다(전에도 주 세션을 옮기지 않았음).
- ALTER 글은 개체의 DB 인스턴스 주 문맥을 기본값 그대로 써서 만든다(`SourceScripts.getObjectContext`, PostgreSQL에서 그 DB의 세션이 아직 없으면 Object Explorer가 펼칠 때와
  같이 연다). 새 틀·프로시저 호출·Schema Compare 글은 문맥을 보지 않는다(Schema Compare의 문장은 전부터 대상 인스턴스의 주 문맥으로 만들었다).
- 질문이 쓸모없어졌다: 주 세션이 옮겨지지 않으므로 그 세션의 트랜잭션은 그 세션을 쓰는 창에 계속 보인다. "기본값으로 설정"의 `ExitTransactionCheck.confirmDefaultChange`,
  Query Library의 `confirmWindowMove`는 그대로다. 질문 글은 기본값으로 설정의 글을 함께 썼으므로 지운 메시지 키는 없다.

**스크립트 글(전·후 비교, 같은 개체를 같은 연결에서):** PostgreSQL(함수·프로시저·뷰, 기본 DB·다른 DB·다른 스키마), MariaDB 프로시저·뷰, Oracle 뷰, 새 뷰·새 시퀀스 틀,
그리고 주 세션이 있는 곳(기본 DB/스키마)의 개체는 모든 드라이버에서 글자 하나까지 같다. 다른 곳의 개체는 드라이버 관리자가 문맥의 기본값을 보고 글을 더한다:

| 드라이버·개체(주 세션 A, 개체 B) | 고치기 전 | 고친 뒤: 주 세션을 함께 쓰는 창, ALTER 대상 ▸ 클립보드 | 고친 뒤: 자기 세션 창 |
|---|---|---|---|
| SQL Server 프로시저·뷰·트리거(`SQLServerObjectManager.addDatabaseSwitchAction1/2`, 뷰·트리거 관리자) | `USE B` / `ALTER …` | `USE B` / `USE B` / `ALTER …` / `USE A` | `USE B` / `USE B` / `ALTER …` |
| MariaDB 트리거(`MySQLTriggerManager`) | `DROP TRIGGER IF EXISTS b.t` / `CREATE … TRIGGER b.t …` | 그 사이에 `USE b;`, 끝에 `USE a;` | 그 사이에 `USE b;` |
| Oracle 프로시저·패키지·트리거(`OracleUtils.addSchemaChangeActions`) | `ALTER SESSION SET CURRENT_SCHEMA=B` / `CREATE OR REPLACE …` | 끝에 `ALTER SESSION SET CURRENT_SCHEMA=A` | 고치기 전과 같음 |

더해진 문장은 개체의 곳으로 갔다가 문맥의 원래 자리로 돌아오는 쌍이다(DBeaver 개체 편집기의 저장과 같은 글). 글은 어느 경우에도 개체의 곳에서 실행된다(이름이 DB/스키마를
쓰거나 앞의 `USE`·`ALTER SESSION`). 창이 실행 뒤 있을 곳에 맞춰 두 모양을 만든다(사용자 결정 2026-11-29 "둘 다 고친다"):
- 주 세션을 함께 쓰는 창: 끝의 문장이 공유 세션을 원래 자리(A)로 돌려 놓는다(그대로 둠). 고치기 전 글이었다면 앞의 `USE B`·`ALTER SESSION`이 공유 세션을 B에 남겼다.
- 자기 세션 창(B에서 열림): 창은 개체의 곳에 남아야 하므로 끝의 문장을 뺀다. 앞의 `USE B`·`ALTER SESSION`(개체의 곳으로 가기)은 둔다: 이미 B인 세션에서는
  아무것도 바꾸지 않고, SQL Server는 맨 앞의 `getDatabaseSwitch` `USE` 배치도 그대로다. 실행 뒤 창의 세션은 B다(SQL Server `DB_NAME()`, MariaDB `DATABASE()`,
  Oracle 창의 스키마 선택·`v$session.schemaname`으로 확인). 고친 첫 빌드(`2c887213cc`)는 끝의 문장을 넣어 창이 A로 옮겨졌다.
- ALTER 대상 ▸ 클립보드: 어디에 붙여 실행할지 모르므로 스스로 완결된 글(가기와 돌아오기 쌍)을 그대로 둔다. 붙여 실행한 세션은 실행 뒤 주 세션의 자리(A)로 간다.

어느 모양인지는 새 창이 세션을 정하는 것과 같은 판단으로 정한다(`QueryWindowContext.isSeparate`: 연결의 "쿼리 창마다 별도 연결" 설정, 기본은 단일 연결·내장 DB 빼고 자기 세션).
UI에는 드라이버 판단이 없다: `SourceScripts.generateAlter(…, forQueryWindow)`가 자기 세션 창의 글이면 명령 옵션 `DBPScriptObject.OPTION_SKIP_DEFAULTS_RESTORE`를 넣고,
끝에 돌아가는 문장을 넣는 관리자가 그 옵션을 본다: `SQLServerObjectManager.addDatabaseSwitchAction2`(옵션을 받는 것, 프로시저 관리자가 씀), `SQLServerViewManager`,
`SQLServerTableTriggerManager`, `MySQLTriggerManager`, `OracleUtils.addSchemaChangeActions`(옵션을 받는 것, Oracle 프로시저·패키지·테이블 트리거 관리자가 씀).
트리거 관리자는 명령 옵션이 닿도록 `SQLTriggerManager`에 옵션을 받는 `createOrReplaceTriggerQuery`를 더했다(기본은 옵션 없는 것과 같음, 다른 드라이버의 트리거 관리자는 그대로).
옵션이 없으면 전과 같은 글이므로 Modify 밖의 쓰임(SWT에서 온 저장 경로, Schema Compare 스크립트, 삭제 문장, Oracle 형식 관리자)은 바뀌지 않는다.

**주 세션을 함께 쓰는 창에서 개체의 스키마가 공유 세션의 스키마와 다를 때:** 공유 세션의 스키마는 바꾸지 않는다. 수정·새 틀·프로시저 호출의 글은 모두 스키마(SQL Server·MariaDB는
DB도)를 이름에 쓰거나 앞에서 옮기므로(Oracle `ALTER SESSION`, SQL Server `USE`) 그 문장은 맞는 개체에서 돈다. 다만 PostgreSQL 함수·프로시저 본문의 스키마 없는 이름은 호출한
세션의 `search_path`로 풀린다: 고친 첫 빌드(`2c887213cc`)에서 공유 창으로 A의 `chr_ckms_s.chr_ckms_p`를 실행하자 본문의 `chr_ckms_t`가 `public`의 표였다(결과
`A s chr_ckms_a.public 5`; 그보다 전에는 공유 세션의 `search_path`를 옮겨 `chr_ckms_s`의 표).

**프로시저 실행 창의 스키마(사용자 결정 2026-11-29 "둘 다 고친다"):** 공유 세션의 스키마를 바꾸지 않고, 실행을 멈추고 PR 402와 같은 글을 Messages에 낸다
("먼저 창의 스키마 목록에서 {0}을(를) 고르십시오.", `EditorMessages.editor_sql_query_schema_shared`, 새 글 없음). 사용자가 그 창의 스키마 목록에서 루틴의 스키마를 고르면
(직접 하는 세션 바꾸기) 다음 실행이 돈다.
- 고른 방법: 실행할 때의 검사(PR 402의 `SqlEditorPanel.getRunContext`). 프로시저 실행 창은 `QueryWindows.openCallScript`로 열리고, 창이 루틴의 DB/스키마를 실행 기준으로
  기억한다(`SqlEditorPanel.setRunDefaults`, 바인딩이 이름을 주지 않을 때 쓰임). 그래서 명령의 첫 실행과 그 뒤의 F5가 같은 검사를 거친다. 창을 열 때 한 번만 막는 방법
  (실행하지 않고 열기 + 글)은 사용자가 고르지 않고 F5를 누르면 다른 스키마에서 돌아 고르지 않았다. 검사가 쓰는 판단·조회·글은 Query Library와 같다: 창이 주 세션을 함께
  쓰고 그 세션의 인스턴스가 데이터베이스(`DBSCatalog`, PostgreSQL 계열)이며 그 DB의 스키마가 지금 스키마가 아니고 시스템 스키마가 아닐 때만 멈춘다.
- 기억은 창의 동안만이다(다시 시작해 복원한 창에는 없음). 창의 글을 다른 문장으로 바꿔도 검사는 남는다(그 창은 루틴의 창이다). 자기 세션 창은 루틴의 스키마로 열려
  검사에 걸리지 않는다(`A s chr_ckms_a.chr_ckms_s 6`, 전과 같음).
- 드라이버별: PostgreSQL 계열만 해당한다(호출자의 `search_path`로 본문 이름을 푼다, 위 판단도 그 계열만 고름). SQL Server 프로시저는 본문의 스키마 없는 이름을 프로시저의
  스키마에서 먼저 찾고, 호출 글이 `USE <db>`·스키마를 쓴다. MySQL/MariaDB 루틴은 실행하는 동안 루틴의 DB가 기본 DB가 된다(시험: 공유 창 A에서 `CALL b.p` → `DATABASE()` B).
  Oracle은 정의자 권한 루틴의 이름을 컴파일할 때 소유자 스키마로 풀고, 호출은 스키마를 쓴 익명 블록이다. 그래서 셋은 공유 세션의 현재 스키마에 영향받지 않는다
  (호출자 권한(`AUTHID CURRENT_USER`) Oracle 루틴은 현재 스키마로 풀 수 있으나 시험하지 않았고 이번 범위 밖).

**SQL Server·MariaDB 공유 창의 프로시저 실행(바꾸지 않음, 사용자 결정 대기):** SQL Server 호출 글은 `USE B` 배치로 시작하므로 창을 열자마자 실행하면 그 문장이 공유 세션을
B로 옮긴다(시스템이 아니라 글의 문장이지만 명령이 곧바로 실행한다). W1의 다음 `DB_NAME()`이 B였다. 트랜잭션은 같은 세션에 남는다(`open_transaction_count` 1).
MariaDB 호출 글(`CALL b.p(…)`)은 세션을 옮기지 않는다(W1은 A, 루틴은 B에서 돎). Oracle 호출(익명 블록, 스키마를 씀)도 옮기지 않는다.

**남은 것:**
- ~~자기 세션 창의 끝 문장~~: 2026-11-29 고침(위 표, `OPTION_SKIP_DEFAULTS_RESTORE`).
- ~~PostgreSQL 공유 창에서 다른 스키마 루틴의 프로시저 실행~~: 2026-11-29 고침(위 "프로시저 실행 창의 스키마").
- SQL Server 공유 창의 프로시저 실행 `USE`: 공유 창의 자기 세션 결정과 함께.
- SQL Server 자기 세션 창의 글은 맨 앞 `USE B` 배치가 둘이다(`getDatabaseSwitch`와 관리자의 가기 문장). 해는 없어 그대로 두었다.

**확인:** 아래 "확인 기록 (2026-11-29, 주 세션을 옮기던 명령)".

## upstream 대비 변경점

- SWT 편집기는 실행 뒤 기본 DB/스키마 다시 읽기가 설정(기본 꺼짐)이었다. Swing은 설정 없이 늘 읽는다(사용자 요구 "실제 세션 값과 콤보가 늘 같아야 함").
- `PostgreExecutionContext.refreshDefaults`: 읽은 뒤 바꾸기, `SET ROLE`은 시작할 때만.
- `JDBCObjectCache.getObjectReadingNew`와 그것을 쓰는 SQL Server·MySQL/MariaDB·PostgreSQL 실행 문맥(위 "캐시에 없는 현재 DB").
- `ContextDefaultObjectsReader.run`: 기본 카탈로그를 묻기 전에 카탈로그 목록을 읽음(위 "연결을 다시 읽은 뒤의 DB 선택"). upstream은 기본값을 먼저 묻는다.
- SQL Server·MySQL/MariaDB `initializeContextState`: 원본 세션의 DB를 개체 대신 이름으로 받고, 받지 못하면 세션의 값을 읽음(위 "다시 연결 뒤의 DB").
  upstream은 목록에서 찾은 개체로만 `USE` 한다.
- `JDBCExecutionContext`: 쿼리 관리자에 닫혔다고 알린 문맥이 다시 연결하면 다시 알림, 다른 인스턴스로 옮기기(`reconnectTo`). `PostgreExecutionContext.setDefaultCatalog`가
  격리 문맥에 그것을 씀(위 "자기 세션을 다른 DB로 다시 연결할 때"). upstream은 다시 연 세션을 알리지 않고 문맥을 처음 DB의 목록에 둔다.
- 나머지(스피너, 문장 표시, 바깥 누름으로 닫기, 도구 설명 단축키, 닫기 아이콘, 끊고 다시 연결한 쿼리 창의 DB 지키기, 개체에서 연 창의 DB)는 Swing 프런트엔드에만 있다.
- 탭의 세션 번호: `DBCExecutionContext.getServerSessionId`, `JDBCServerSessionIdReader`, `JDBCExecutionContext`가 세션을 열 때 번호를 읽음, 네 방언의 읽기(위 절).

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

### 확인 기록 (2026-11-25, "다시 연결 뒤의 DB")

Windows 11, 150%, 한국어, 빈 작업 공간·`CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트(OS 입력 없음), 시험 VM의 SQL Server 2022·MariaDB 11·
MySQL 8.4(번들 MariaDB 드라이버: MySQL 드라이버는 번들에 없어 내려받기 창이 떴다)·PostgreSQL 17·Oracle 1521, 시험 개체 `chr_reconnect_keeps_database*`.
고치기 전 빌드는 위 "다시 연결 뒤의 DB"의 표. 고친 빌드에서 같은 절차:

| DB | Invalidate/Reconnect | 끊긴 세션 다시 열기 | Reconnect | Disconnect → Connect |
|---|---|---|---|---|
| SQL Server | 지킴 | 지킴 | 지킴(고른 DB) | 끊긴 동안 선택 `chr_reconnect_keeps_database.dbo`(비활성, 글), 연결 뒤 지킴 |
| SQL Server, 필터 | 지킴(서버도 `…_h`) | 지킴 | `master`(이름 글은 넘기지 않음, 표시도 `master`) | `master` |
| MariaDB·MySQL | 지킴 | 지킴 | 지킴 | 지킴 |
| MariaDB·MySQL, 필터 | 지킴(서버도 `…_h`) | 지킴 | DB 없음(표시 N/A) | DB 없음 |
| PostgreSQL | 지킴 | 지킴 | 지킴 | 지킴(끊긴 동안 `postgres.chr_reconnect_keeps_database`) |
| Oracle | 지킴 | 확인 안 함 | 지킴 | 지킴 |

- DB를 받지 못하는 경우: 창이 쓰던 SQL Server DB를 내 관리자 세션이 `OFFLINE`으로, MariaDB DB를 `DROP` 한 뒤 Invalidate/Reconnect. 1차 수정 빌드에서 창 세션은
  `master`/DB 없음을 가졌지만 선택·상태 줄은 옛 DB였다(아무도 다시 읽지 않음). `ConnectionCommands.invalidate`의 다시 읽기를 더한 빌드(고친 뒤 2차): SQL Server 선택·상태 줄 `master`, 세션 `master`; MariaDB 선택
  "&lt; N/A &gt;", 세션 DB 없음. 고치기 전 빌드에서는 이 경우를 돌리지 않았다(코드로는 SQL Server는 `USE` 실패를 기록만 하고 옛 이름을 가짐, MariaDB는 예외가
  `connect`에서 기록만 됨). 시험 DB `chr_reconnect_keeps_database_x`는 끝에 `ONLINE` 뒤 지웠다.
- 주 세션을 함께 쓰는 창(연결 설정 "쿼리 창마다 별도 연결" = NEVER, SQL Server): Disconnect → Connect 뒤 전처럼 `master`(지키지 않음, 표시도 `master`).
- PostgreSQL 창을 다른 데이터베이스(`chr_reconnect_keeps_database_db`, 모든 데이터베이스 표시)로 옮긴 경우: Invalidate/Reconnect는 고치기 전·후 모두 지킴.
  Disconnect → Connect는 고치기 전·후 모두 `postgres`/`public`(아래 "남은 것").
- 캡처(내 앱 창만, Swing 구성 요소로 그림): `D:\Tools\_scratch\reconnect-keeps-database\before-1…4.png`, `after-1…4.png`.
  1: 필터로 숨긴 DB에서 Invalidate/Reconnect 뒤(전후 모두 선택 `…_h`), 2: 그 창에서 `SELECT db_name()`(전 `master`, 후 `chr_reconnect_keeps_database_h`),
  3: `USE chr_reconnect_keeps_database` 뒤 Disconnect(전 "&lt; N/A &gt;", 후 `chr_reconnect_keeps_database.dbo` 비활성), 4: Connect 뒤(전 `master`, 후 그 DB).

**남은 것(2026-11-25 뒤 확인, 아래 "다른 곳에서 바뀐 세션의 DB"):** PostgreSQL 창이 다른 데이터베이스에 있을 때 창이 기억하는 이름이
`postgres.public`이어서 Disconnect → Connect 뒤 `postgres`로 열렸다고 적었다. 앱의 실제 길로는 재현되지 않았다. 그 확인은 시험 에이전트가 창 밖에서
창의 세션을 바꾸고(`DBExecUtils.setExecutionContextDefaults`) 창이 다시 읽지 않은 상태였다. 같은 원인(창 밖에서 바뀐 세션을 창이 다시 읽지 않음)이
앱의 실제 길에서는 주 세션을 함께 쓰는 창에 있었고 그것을 고쳤다.

### 확인 기록 (2026-11-25, 다른 곳에서 바뀐 세션의 DB)

Windows 11, 150%, 한국어, 빈 작업 공간·`CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트, 시험 VM PostgreSQL 17. 고치기 전(`9112f945c9`)과 고친 빌드에서
위 표의 여섯 창을 같은 절차로 열고 Invalidate/Reconnect, Reconnect, Disconnect → Connect, 다시 시작(복원 → 연결)을 했다. 자기 세션 창의 결과는 전후가 같다(표).
주 세션을 함께 쓰는 창(연결 PGS, NEVER):

| 단계 | 고치기 전 | 고친 뒤 |
|---|---|---|
| `postgres` 기본값 | 선택 `public`, 이름 `postgres.public`, 세션 `postgres` | 같음 |
| X 함수의 수정 뒤 | 선택 `public`, 이름 `postgres.public`, 세션 X·`chr_s` | 선택 `chr_s`, 이름 `X.chr_s`, 세션 X·`chr_s` |
| 그 창에서 실행 뒤 | 선택 `chr_s`(실행 끝 읽기) | 같음 |

시험 에이전트가 자기 세션 창을 창 밖에서 X로 옮긴 경우(앞 확인의 절차): 고치기 전 이름 `postgres.public`(세션 X), 고친 뒤 `X.public`.
캡처(내 앱 창만, Swing 구성 요소로 그림): `D:\Tools\_scratch\postgres-window-database-restore\s\before-shots2\shots\`와 `…\after-shared\shots\`의
`1-shared-window-on-postgres.png`, `2-shared-window-after-modify.png`(전 `public`, 후 `chr_s`), `3-shared-window-after-run.png`.

**확인하지 못한 것:** 떠 있는(도킹을 뗀) 도구 창에서는 메뉴 막대가 없어 메뉴 가속키(Ctrl+R 포함)가 가지 않을 수 있다(이번 범위 밖, 보고만).

### 확인 기록 (2026-11-28, 개체에서 연 창의 DB)

Windows 11, 150%, 한국어, 빈 작업 공간·설정·`CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트(OS 입력 없음), 시험 VM의 PostgreSQL 17·SQL Server 2022·
MariaDB 11.8·MySQL 8.4(번들 MariaDB 드라이버). 고치기 전은 위 "개체에서 연 창의 DB"의 표(`e470c1f25c` 빌드). 고친 빌드에서 같은 절차:

| 명령 | PostgreSQL(자기 세션) | PostgreSQL(주 세션을 함께 씀) | SQL Server | MariaDB·MySQL |
|---|---|---|---|---|
| 새 쿼리(DB 노드) | X(표시 `public`, 이름 `X.public`) | `postgres`(전과 같음) | X | X |
| 새 쿼리(스키마·표 노드) | X, 표시 그 스키마 | `postgres` | X | X |
| 상위 1000개 행 선택 | X, `(1,'X'),(2,'X')` | `postgres`, `(1,'postgres')`(정한 동작) | X, X의 행 | X, X의 행 |
| 스크립팅 ▸ SELECT 대상 → F5, SQL 생성 ▸ SELECT → F5 | X, X의 행 | `postgres`, `(1,'postgres')` | X, X의 행 | X, X의 행 |
| SQL 생성 ▸ INSERT·UPDATE·DELETE·DDL, 스크립팅 ▸ CREATE 대상(실행 안 함) | 창 세션 X | `postgres` | X | X(DB 없는 `CREATE TABLE`도 X에서 돈다) |
| 삭제 ▸ 쿼리 창에서 열기(`DROP TABLE …chr_only_x`, 실행 안 함) | 창 세션 X | 확인 안 함 | 확인 안 함 | 확인 안 함 |
| 수정, 프로시저 실행 | X(전과 같음) | X(전과 같음, 함께 쓰는 창이 따라감) | X | X |

- PostgreSQL 자기 세션 창: 연결 새로 고침 뒤, Invalidate/Reconnect 뒤, Disconnect → Connect 뒤에도 새 "상위 1000개 행 선택"은 X의 행. Disconnect 동안 앞서 연
  "상위 1000개 행 선택" 창의 선택은 `X.X`(비활성, "다시 연결 뒤의 DB"의 자리), Connect 뒤 그 창의 세션은 X이고 다시 실행하면 X의 행.
- 다른 창의 세션과 연결의 주 세션은 바뀌지 않았다(창마다 서버 값을 읽음, 주 세션 `postgres`).
- 로그: `D:\Tools\_scratch\postgres-open-in-object-database\s\before\*.log`, `…\before-extra\*.log`, `…\after\*.log`, `…\after-extra\01.txt.log`.
- 캡처(내 앱 창만, Swing 구성 요소로 그림): `…\before-extra\shots\before-1-pg-select-top-other-database.png`(X의 표에서 연 창이 `public`,
  행 `postgres`), `…\after-extra\shots\after-1-pg-select-top-other-database.png`(같은 명령, 선택·상태 줄 X의 스키마, 행 `X` 둘),
  `…\after-extra\shots\after-2-pgs-shared-window-select-top.png`(주 세션을 함께 쓰는 연결: `public`, 행 `postgres`).
- 확인하지 못한 것: Oracle(창의 현재 스키마가 개체의 스키마로 바뀜, `ALTER SESSION SET CURRENT_SCHEMA`)·Altibase 등 그 밖의 드라이버, ERD·데이터 검색·데이터베이스 복사·
  유지 관리·테이블 디자이너·이름 바꾸기·새 개체·속성 바꾸기의 창(같은 `openScript` 길, 코드 읽기와 빌드만). 회귀 테스트는 실행하지 않았다.
- 후속(같은 날, 결과 그리드·권한·스케줄러): 고치기 전 빌드(`2c6b655af7`)와 고친 빌드에서 같은 절차(`D:\Tools\_scratch\postgres-open-in-object-database\s\followup\`).
  PostgreSQL: X의 표에서 연 창(X)의 결과 ▸ SQL 생성 ▸ INSERT ▸ 쿼리 창에서 열기 — 전 새 창 세션 `postgres`(표시 `public`), 후 X(표시 X의 스키마).
  X의 표 ▸ 사용 권한 ▸ SELECT를 postgres에 줌 ▸ 스크립트 ▸ 쿼리 창에서 열기(`GRANT SELECT ON TABLE …chr_t TO postgres`, 실행 안 함) — 전 `postgres`, 후 X.
  SQL Server: X의 표에서 연 창의 결과 ▸ INSERT — 전 기본 DB `…_d`(글이 DB를 써서 결과는 맞음), 후 X. 연결의 주 세션은 전후 모두 기본 DB 그대로.
  캡처 `…\followup\before\shots\before-1-pg-grid-generate-sql-window.png`, `before-2-pg-permissions-script-window.png`, `…\followup\after\shots\after-1…`, `after-2…`.
  스케줄러(pg_cron·MySQL 이벤트)의 창은 코드 읽기와 빌드만(시험 서버에 pg_cron 작업 없음).

### 확인 기록 (2026-11-28, 주 세션을 다른 DB로 옮기기 전 트랜잭션 질문)

Windows 11, 150%, 한국어, 빈 작업 공간·`CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트(Object Explorer 메뉴 항목을 그대로 누름, OS 입력 없음),
시험 VM PostgreSQL 17·SQL Server 2022·MariaDB 11.8. 연결은 수동 커밋, "쿼리 창마다 별도 연결" NEVER(PostgreSQL은 "모든 데이터베이스 표시"), 기본 DB A.
트랜잭션은 매번 주 세션을 함께 쓰는 창의 `INSERT INTO chr_bdct_t VALUES (n)`(A)로 열었다. 서버 쪽은 따로 연 관리 세션에서 읽었다(PostgreSQL `pg_stat_activity`,
SQL Server `sys.dm_exec_sessions.open_transaction_count`, MariaDB `INNODB_TRX`, 다른 세션에서 본 커밋된 행 수).

| 경우 | 고치기 전(`6d1b523e53`) | 고친 뒤 |
|---|---|---|
| PostgreSQL, B 함수의 수정 | 질문 없음, 기본 인스턴스 B, A 주 세션 `idle in transaction`, A 행 0개 | 질문(아래 글). 취소: 창 열리지 않음, 기본 A, 트랜잭션 그대로. 롤백: A 주 세션 `idle`, 창 열림, 기본 B |
| PostgreSQL, B 함수의 프로시저 실행(실행) | 질문 없음, A에 트랜잭션 남음 | 실행 대화상자 뒤 질문. 커밋: A 행 1개(사용자가 고른 커밋), 호출은 B에서 실행 |
| PostgreSQL, B의 새 뷰 | 질문 없음, A에 트랜잭션 남음 | 질문. 롤백: A `idle`, 틀 창 열림 |
| PostgreSQL, B의 새 시퀀스(일반 "새 &lt;종류&gt;" 틀) | (같은 `bindDefaultContext`) | 질문. 취소: 창 열리지 않음, 트랜잭션 그대로 |
| PostgreSQL, A 함수의 수정(같은 DB, 트랜잭션 열림) | — | 질문 없음, 트랜잭션 그대로 |
| PostgreSQL, 트랜잭션 없이 B 함수의 수정 | — | 질문 없음 |
| PostgreSQL, 연결 Refresh 뒤 A·B 함수의 수정 | — | A 질문 없음, B 질문(롤백) |
| SQL Server·MariaDB, B의 수정·프로시저 실행·새 뷰 | 질문 없음, 같은 세션이 B로, 트랜잭션 그대로(창이 쥠) | 같음(바꾸지 않음) |

질문 글: "PGS의 주 세션에 커밋하지 않은 트랜잭션이 있습니다: PGS (SQLQuery1.sql) - 열린 트랜잭션: 데이터 변경 있음 / chr_bdct_b을(를) 기본값으로 만들면
주 세션이 다른 데이터베이스로 옮겨 가고, 트랜잭션은 이전 세션에 남습니다. …" [커밋] [롤백] [취소(기본)].
시험에서 연 트랜잭션은 모두 명시적으로 끝냈다(질문의 커밋·롤백, 남은 것은 시험 에이전트가 앱의 `IdleTransactionMonitor.commitOrRollback`으로 롤백).
시험 DB는 끝에 지우고 카탈로그로 확인했다(세 서버 모두 `chr_bdct%` 0개).
캡처(내 앱 창만, Swing 구성 요소로 그림): `D:\Tools\_scratch\bind-default-context-transaction\s\before\shots\`(`pg-before-after-modify.png`,
`pg-before-first-window-after-new-view.png`, `ms-before-first-window.png`, `ma-before-first-window.png`)와 `…\s\after\shots\`
(`pg-after-modify-question-popup0.png`, `pg-after-execute-question-popup0.png`, `ms-after-first-window.png`, `ma-after-first-window.png`).

**확인하지 못한 것:** Schema Compare의 스크립트 만들기(같은 `confirmBindDefaultContext`를 부름, 화면에서는 확인하지 않음). Go to Source Code·오류로 가기는
Oracle 패키지에만 있고 Oracle은 인스턴스가 하나라 묻는 경우가 없다(코드 읽기).

### 확인 기록 (2026-11-28, 자기 세션을 다른 DB로 다시 연결할 때)

Windows 11, 150%, 한국어, 빈 작업 공간·`CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트(OS 입력 없음), 시험 VM PostgreSQL 17.
연결 수동 커밋, "쿼리 창마다 별도 연결" ALWAYS, "모든 데이터베이스 표시", 기본 DB A, 다른 DB B, 두 DB에 같은 이름의 빈 표.
"앱"은 쿼리 관리자의 트랜잭션 상태(상태 줄의 "열린 트랜잭션"과 창 닫기·연결 끊기 질문이 쓰는 값), "서버"는 따로 연 관리 세션의 `pg_stat_activity`와 커밋된 행 수다.
창 닫기 질문은 탭 닫기와 같은 `SqlEditorPanel.confirmEndSession`을 불러 보았다.

| 경우 | 고치기 전(`bc380caa34`) | 고친 뒤 |
|---|---|---|
| Query Library 창(자기 세션): A에서 `INSERT`(커밋 안 함), 머리말 Database B로 F5 | 질문 없음. A의 세션이 사라지고 A의 커밋된 행 0개(묻지 않은 롤백), 창은 B에서 실행 | 질문(창 닫기와 같은 글). 취소: 실행 안 함, A 세션 `idle in transaction`(xid 있음) 그대로. 롤백: A 세션 끝, 창은 B에서 실행 |
| 위 창이 B로 옮긴 뒤 `INSERT` | 서버 `idle in transaction`(xid 있음), 앱 트랜잭션 없음, 상태 줄 "수동 커밋"만, 창 닫기 질문 없음 | 앱 열린 트랜잭션(변경 있음), 상태 줄 "열린 트랜잭션 … (변경 있음)", 창 닫기 질문 |
| 위 창의 문맥이 든 목록 | A의 목록(소유 인스턴스는 B) | B의 목록 |
| 트랜잭션 없이 머리말 Database A로 F5 | — | 질문 없음, A로 다시 연결, A의 목록 |
| 처음부터 B에서 열린 새 창(`setRestoredDefaults`: 개체에서 연 창·New Query·다시 시작 복원과 같은 호출)의 `INSERT` | 앱 트랜잭션 없음, 창 닫기 질문 없음, 문맥은 A의 목록 | 앱 열린 트랜잭션, 창 닫기 질문(취소), B의 목록. 트랜잭션을 끝낸 뒤 창을 닫자 B의 목록에서 빠지고 서버 세션도 끝남 |
| 연결 Reconnect 뒤 위 Library 창(머리말 B)의 `INSERT` | — | 앱 열린 트랜잭션, 창 닫기 질문 |
| 쿼리 창의 DB 선택 | 항목은 세션 DB의 스키마뿐(`information_schema`, `pg_catalog`, `public`): 다른 DB로 옮길 수 없음 | (바꾸지 않음) |

시험에서 연 트랜잭션은 모두 명시적으로 끝냈다(질문의 롤백, 남은 것은 시험 에이전트가 앱의 `IdleTransactionMonitor.commitOrRollback`으로 롤백). 두 DB의 커밋된 행은
끝까지 0개였다. 시험 DB는 끝에 지우고 카탈로그로 확인했다(`pg_database`에서 `chr_pg_own_session_reconnect%` 0개).
캡처(내 앱 창만, Swing 구성 요소로 그림): `D:\Tools\_scratch\pg-own-session-reconnect-transaction\s\before\shots\`(`before-library-no-question.png`: 머리말 B로
실행한 뒤 질문 없이 B의 결과, `before-library-untracked.png`·`before-restored-untracked.png`: B에서 `INSERT`한 뒤 상태 줄에 열린 트랜잭션 없음)와
`…\s\after\shots\`(`after-library-question.png`: 실행 전 질문, `after-library-tracked.png`: 상태 줄 "열린 트랜잭션 (변경 있음)",
`after-restored-close-question.png`: 창 닫기 질문).

**확인하지 못한 것:** 예약 실행(`QueryScheduler`)과 데이터 전송에서 다시 연결한 문맥(같은 `reconnectTo`, 코드 읽기만). 다시 연결한 세션의 커밋 방식은 전과 같이
연결의 기본값이다(창에서 커밋 방식을 바꾼 뒤 다시 연결하는 경우는 시험하지 않음).

### 확인 기록 (2026-11-28, 탭의 세션 번호)

Windows 11, 150%, 한국어(영어 한 번), 빈 작업 공간·설정·`CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트(OS 입력 없음), 시험 VM의 PostgreSQL 17·
SQL Server 2022·MariaDB 11.8·MySQL 8.4(번들 MariaDB 드라이버)·Oracle 26ai(1521). 연결은 모두 수동 커밋. PG(창마다 세션), PGS("쿼리 창마다 별도 연결" = 안 함).
서버 쪽은 따로 연 관리 세션(자동 커밋)에서 읽었다.

- 읽기 자체의 부작용(앱 밖 JDBC, 같은 드라이버): 위 표의 근거. 로그 `D:\Tools\_scratch\query-tab-session-id\evidence\jdbc-side-effects.txt`.
- 보이는 번호 = 서버가 보는 그 창의 세션: PostgreSQL `pg_stat_activity.application_name` "… SQLEditor &lt;SQLQuery1.sql&gt;"의 `pid`, SQL Server
  `sys.dm_exec_sessions.program_name`의 `session_id`, Oracle `V$SESSION.PROGRAM`의 `SID`가 탭의 번호와 같다. MariaDB·MySQL은 그 창에서 실행한
  `SELECT CONNECTION_ID()`의 결과가 탭의 번호와 같다(이 문장은 트랜잭션을 열지 않음: 창의 쿼리 관리자 상태 `txOpen=false`).
- 창마다 세션인 PG 창 둘: 다른 번호(200488, 200489). PGS 창 둘: 같은 번호(주 세션 200476).
- 창을 연 뒤 모든 창 세션: PostgreSQL `idle`·`backend_xid` 없음·`xact_start` 없음, SQL Server `open_transaction_count` 0·`dm_tran_session_transactions` 없음,
  MariaDB·MySQL `INNODB_TRX` 없음, Oracle `TADDR` null.
- 쿼리 관리자: 연결·창 열기 동안 기록된 문장에 `@@SPID`, `CONNECTION_ID()`, `SYS_CONTEXT('USERENV', 'SID')`가 없다.
- Invalidate/Reconnect: 같은 문맥 객체(창의 문맥 id 그대로)에서 새 번호(PG 200488 → 200540, PGS 두 창 200476 → 200542, SQL Server 56 → 55, Oracle 38 → 258),
  서버 쪽도 새 번호가 그 창의 세션(옛 번호는 사라지거나 다른 세션). Disconnect: 번호가 빠짐(제목 `SQLQuery6.sql - MA*`). Connect: 새 번호(MA 47013 → 47025).
- 고침 표시와 이름 바꾸기: `chr_renamed.sql - PG (200488)*`. 별도 창: 탭과 창 제목 `SQLQuery2.sql - PG (200560) - Chromite`, Invalidate/Reconnect 뒤 새 번호.
  다시 시작 복원: 창 이름은 번호 없이 복원되고(`chr_renamed.sql`), 연결하면 새 번호.
- 캡처(내 앱 창만, Swing 구성 요소로 그림, 도구 설명은 창에 초점이 없어 탭의 `JToolTip`을 팝업 층에 놓고 그림): 전 `D:\Tools\_scratch\query-tab-session-id\s\before\shots\`
  (`before-ko-pg-tab-tooltip.png`, `before-ko-ms-tab-tooltip.png`, `before-en-ms-tab-tooltip.png`), 후 `…\s\after\shots\`(`after-ko-pg-own-tab-tooltip.png`,
  `after-ko-pgs-shared-tab-tooltip.png`, `after-ko-ms-tab-tooltip.png`, `after-ko-ora-tab-tooltip.png`, `after-ko-pg-dirty-renamed.png`, `after-ko-disconnected.png`,
  `after-ko-pg-separate-window.png`, `after-en-ms-tab-tooltip.png`).
- `command` 병합 뒤(#398의 `reconnectTo`와 함께, 같은 날): PostgreSQL 자기 세션 창(Query Library 창, 자동 커밋)의 머리말 Database를 시험 DB `chr_query_tab_session_id`로 바꾸고 F5 →
  같은 문맥 객체가 그 DB 인스턴스로 옮겨 가고 번호 201231 → 201235, 서버에서 201235는 그 DB의 `SQLEditor <QL-SID.sql>` 세션(`idle`), 201231은 없어짐.
  캡처 `D:\Tools\_scratch\query-tab-session-id\s\merge\shots\merge-ko-pg-library-moved-tab-tooltip.png`. 시험 DB는 끝에 지웠다(`pg_database` 0개).
- 확인하지 못한 것: 쿼리 창의 연결 변경과 주 세션이 다른 PostgreSQL DB 인스턴스로 옮겨 간 경우(같은 타이머 길, 코드 읽기만), 끊긴 세션을 읽기가 다시 여는 길
  (같은 `connect`), 번호를 읽지 않는 드라이버(Altibase 등)의 탭(코드 읽기: 방언이 읽기를 구현하지 않으면 null). 회귀 테스트는 실행하지 않았다.
### 확인 기록 (2026-11-28, 주 세션을 함께 쓰는 창: 개체의 DB의 주 세션)

Windows 11, 150%, 한국어, 빈 작업 공간·설정·`CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트(Object Explorer 메뉴 항목을 그대로 누름, OS 입력 없음),
시험 VM PostgreSQL 17·SQL Server 2022·MariaDB 11.8·MySQL 8.4(번들 MariaDB 드라이버). 연결과 시험 DB는 위 "주 세션을 함께 쓰는 창: 개체의 DB의 주 세션"의 고치기 전 사실과 같다.
서버 쪽은 따로 연 관리 세션에서 읽었다(`pg_stat_activity`, `sys.dm_exec_sessions`, `INNODB_TRX`, 다른 세션에서 본 커밋된 행 수). 고친 빌드(마지막 빌드)에서:

| 경우(PostgreSQL) | 결과 |
|---|---|
| W1이 A에 `INSERT 5`(커밋 안 함) 뒤 B 표의 상위 1000개 행 선택(W2) | W2 결과 `20`, `21`, W2 문맥 `Main <…_b>`. 기본 인스턴스 A 그대로. A 주 세션 `idle in transaction`(xid 있음), 다른 세션에서 본 A의 행 1개(커밋 안 됨) |
| 그 뒤 W1에서 `current_database()` | A, W1은 여전히 A의 트랜잭션을 보임(상태 줄 "열린 트랜잭션 (변경 있음)") |
| W2의 DB 선택에서 B의 스키마 `…_s` | W2 `current_schema()` `…_s`(B의 주 세션). W1은 A·`public` 그대로 |
| W2에서 새 쿼리(W3) | W3 `current_database()` B(같은 B 주 세션, 스키마 `…_s`) |
| 연결 Refresh 뒤 W2 | B |
| Disconnect 동안 W2 / Connect 뒤 W2 · W1 | 선택 `…_b.public`(비활성, 글) / B · A |
| 다시 시작(복원) → 연결 | 첫 실행 전 선택이 읽은 문맥이 B, 실행 B. W1 A. B의 주 세션은 복원 창이 처음 쓸 때 열림(Object Explorer에서 B를 펼치지 않음) |
| B 함수의 수정(Modify), A의 트랜잭션 열림 | 전과 같음: 기본값으로 설정과 같은 질문, 취소 → 아무것도 바뀌지 않음(이번 범위에서 바꾸지 않음) |

SQL Server·MariaDB·MySQL: 고치기 전과 같다(W2 결과 B의 행, W2·W1의 서버 DB A, A의 트랜잭션 그대로, 공유 세션에 `USE` 없음).
첫 고친 빌드에서는 다시 시작 복원 뒤 연결할 때 W2의 DB 선택이 A의 문맥을 읽고(실행은 B) 있었다. 두 작업이 동시에 창의 세션을 열면서 먼저 연 문맥을 돌려준 것이었고,
이름이 없는 쪽이 묶인 문맥을 다시 받게 고친 뒤(위 "여러 작업이 동시에") 위 표를 다시 돌렸다.
시험에서 연 트랜잭션은 모두 앱의 `IdleTransactionMonitor.commitOrRollback`으로 명시적으로 롤백했다. 시험 DB는 끝에 지우고 카탈로그로 확인했다.
캡처(내 앱 창만, Swing 구성 요소로 그림): `D:\Tools\_scratch\shared-session-object-database\s\before\shots\`(`pg-1-w2-select-top-b.png`: 결과 `1`·`5`,
`pg-1-w1-after.png`, `pg-2-modify-question.png`, `ms-1-…`, `ma-1-…`, `my-1-w2-select-top-b.png`)와 `…\s\final\shots\`(`pg-1-w2-select-top-b.png`: 결과 `20`·`21`,
`pg-3-w1-after-w2-picker.png`, `pg-3-w3-new-query-from-w2.png`, `pg-4-w2-after-reconnect.png`, `pg-5-restored-w2.png`, 드라이버별 `*-1-w2-select-top-b.png`).
로그: 같은 폴더의 `*.txt.log`.

**확인하지 못한 것:** 단일 연결 드라이버(내장 DB 등)와 PostgreSQL 계열 다른 서버(공유 데이터베이스 `isSharedDatabase`), 연결의 기본 DB를 지운 경우.
ERD·데이터 검색·데이터베이스 복사·테이블 디자이너·보안·스케줄러 창(같은 `setRestoredDefaults` 길, 코드 읽기와 빌드만). 회귀 테스트는 실행하지 않았다.

### 확인 기록 (2026-11-29, Query Library 머리말 DB: 주 세션을 함께 쓰는 창)

Windows 11, 150%, 한국어, 빈 작업 공간·설정·`CHROMITE_DATA`를 둔 앱 사본, 프로세스 안 시험 에이전트(Query Library 트리의 Run과 같은 `QueryLibraryEditor.run`,
창의 F5 `SqlEditorPanel.execute`, OS 입력 없음), 시험 VM PostgreSQL 17·SQL Server 2022·MariaDB 11.8. 연결과 시험 DB는 위 절의 고치기 전 사실과 같다.
서버 쪽은 따로 연 관리 세션에서 읽었다(`pg_stat_activity`, `sys.dm_exec_sessions`, `INNODB_TRX`, 다른 세션에서 본 커밋된 행 수). 고친 빌드에서(PostgreSQL):

| 경우 | 결과 |
|---|---|
| W1이 A에 `INSERT 5`(커밋 안 함) 뒤 머리말 B 실행 | `current_database()` B, 서버 세션 B의 주 세션(203068). 기본 인스턴스 A 그대로. A 주 세션 `idle in transaction`(xid 있음), 커밋된 A의 행 1개 |
| 그 뒤 W1·W2 | W1 탭 `(203020)`(A의 주 세션), 상태 줄 "열린 트랜잭션 … (변경 있음)". W2 `current_database()` A(203020) |
| 머리말 C 실행 | C(203085), 기본 인스턴스 A, W1·W2 그대로 A, 머리말 B 창은 B 그대로 |
| 머리말 B + Schema `chr_qlss_s` | 실행하지 않음, Messages에 스키마 글, 서버 세션·기본 인스턴스·`search_path` 그대로. 창은 B에 묶임(탭 203068) |
| (검토 뒤 빌드) 라이브러리 창 M만 A에 있고 M이 A에 `INSERT 9` 뒤 머리말 B로 F5 | 질문("커밋하지 않은 트랜잭션", 목록 `PGS (QL-M.sql)`). 취소 → 실행하지 않음, M은 A·"열린 트랜잭션 (변경 있음)" 그대로. 다시 F5 → 롤백 → A의 트랜잭션 롤백(커밋된 A의 행 1개), 실행은 B |
| (검토 뒤 빌드) W1이 A에 `INSERT 10`, 라이브러리 창 N도 A에서 머리말 B로 F5 | 묻지 않음, N은 B에서 돎, A의 트랜잭션은 W1의 세션에 그대로(W1이 보임) |
| (검토 뒤 빌드) 머리말 Schema만 `chr_qlss_s`(A) | Messages "먼저 창의 스키마 목록에서 chr_qlss_s을(를) 고르십시오.", 실행하지 않음 |
| 머리말 Schema만 `chr_qlss_s`(DB 비움, 창은 A) | 같은 글로 실행하지 않음. W2 `current_schema()` `public` 그대로 |
| 위 B 창의 스키마 목록에서 `chr_qlss_s`를 고른 뒤 F5 | B·`chr_qlss_s`, 결과 `25` |
| B에 묶인 창의 머리말을 A(기본 DB)로 바꾸고 F5 | A(203020), 창 탭 `(203020)` |
| C에 묶인 창의 머리말 Database를 비우고 F5 / 연결 Refresh 뒤 / Disconnect·Connect 뒤 | C / C / C(새 C 주 세션). W2는 A |
| 자기 세션 연결(ALWAYS)의 라이브러리 창에서 `INSERT 7` 뒤 머리말을 B로 바꿔 F5 | 전과 같음: "커밋하지 않은 트랜잭션" 질문(커밋/롤백/취소), 취소 → 실행하지 않음, 트랜잭션 그대로 |

SQL Server·MariaDB 공유 창: 고치기 전과 같다(라이브러리 창 결과 B, 공유 주 세션이 B로 `USE`, W1의 다음 실행도 B, 트랜잭션은 같은 세션에 열린 채 앱이 보임).
시험에서 연 트랜잭션은 모두 앱의 `IdleTransactionMonitor.commitOrRollback`으로 명시적으로 롤백했다. 시험 DB는 끝에 지우고 카탈로그로 확인했다(`pg_database`, `sys.databases`,
`information_schema.SCHEMATA`에 `chr_qlss%` 0개).
캡처(내 앱 창만, Swing 구성 요소로 그림): `D:\Tools\_scratch\query-library-shared-session\s\before\shots\pg-before-w1-after-QL-B.png`(W1 탭이 B의 세션 번호, 열린 트랜잭션 표시 없음),
`…\s\after\shots\`(`pg-after-QL-B-library-window.png`, `pg-after-w1-after-QL-B.png`: W1 탭 A의 번호·열린 트랜잭션, `pg-after-QL-BS-schema-stop.png`,
`pg-after-QL-BS-after-pick.png`, `pg-after-own-session-question.png`). 검토 뒤: `…\s\f2\shots\`(`f2-emulated-6d477fa-L-after-move.png`: 옮긴 뒤 L에
열린 트랜잭션 표시 없음, `f2-emulated-disconnect-question.png`, `f2-after-M-question.png`(첫 글), `f2-after-schema-message.png`), 두 번째 검토 뒤의 질문 글: `…\s\f3\shots\f3-window-move-question-popup0.png`
(같은 M 경우, "이 창이 chr_qlss_b(으)로 옮겨 가면 …", 취소 → 실행하지 않음, A의 트랜잭션은 그 뒤 명시적으로 롤백). 로그: 같은 폴더의 `*.txt.log`.

**확인하지 못한 것:** MySQL 8.4(MariaDB와 같은 코드 경로라 보고 시험하지 않음), Oracle·그 밖의 드라이버, PostgreSQL 계열 다른 서버, "모든 데이터베이스 표시"를 끈 연결
(머리말 DB가 연결 목록에 없음: 코드 읽기로 바인딩도 아무것도 바꾸지 않고 그 자리에서 돎), 다시 시작 뒤 복원한 라이브러리 창. 회귀 테스트는 실행하지 않았다.

### 확인 기록 (2026-11-29, 주 세션을 옮기던 명령)

Windows 11, 150%, 한국어, 빈 작업 공간·설정·`CHROMITE_DATA`를 둔 앱 사본 하나, 프로세스 안 시험 에이전트(Object Explorer 메뉴 항목을 그대로 누름, OS 입력 없음),
시험 VM PostgreSQL 17·SQL Server 2022·MariaDB 11.8·Oracle 26ai(1521). 고치기 전 `e597c96cc4` 빌드, 고친 빌드는 이 브랜치. 연결은 모두 수동 커밋. PGS·MS·MA·ORA는
"쿼리 창마다 별도 연결" 안 함, PGO·MSO는 기본값(창마다 자기 세션). PostgreSQL은 "모든 데이터베이스 표시", 기본 DB `chr_ckms_a`(A), 다른 DB `chr_ckms_b`(B), 각 DB에
`public`과 `chr_ckms_s` 스키마, 같은 이름 표·함수·프로시저·뷰(본문이 `current_database()`·`current_schema()`·스키마 없는 표의 최댓값을 돌려줌). SQL Server·MariaDB는 DB A·B,
Oracle은 연결 기본 스키마 `CHR_CKMS_A`(사용자 `system`), 다른 스키마 `CHR_CKMS_B`(암호 없는 스키마 전용 사용자). 서버 쪽은 따로 연 관리 세션에서 읽었다
(`pg_stat_activity`, `sys.dm_exec_sessions`, `PROCESSLIST`+`INNODB_TRX`, `v$session`+`v$transaction`).

| 경우 | 고치기 전 | 고친 뒤 |
|---|---|---|
| PGS: W1(A 주 세션)이 `INSERT`(커밋 안 함), B 함수의 수정 | PR 396의 질문. 롤백하자 기본 인스턴스 B, W1 탭이 B의 세션 번호(208482 → 208515) | 질문 없음. 기본 인스턴스 A, 새 창은 B의 주 세션(탭 209570), W1은 A·같은 번호(209558), 상태 줄 "열린 트랜잭션 (변경 있음)", 서버 A 주 세션 `idle in transaction`(xid 있음) |
| PGS: A의 `chr_ckms_s` 함수 수정 | A 주 세션의 스키마가 `chr_ckms_s`로 바뀜 | 바뀌지 않음(`public`), 새 창은 A 주 세션 |
| PGS: B 프로시저 실행 | (기본 인스턴스가 이미 B) | B의 주 세션에서 실행, 결과 `B public chr_ckms_b.public 20`, 기본 인스턴스 A |
| PGS: A의 `chr_ckms_s` 프로시저 실행 | 공유 세션의 스키마를 옮긴 뒤 실행 | A 주 세션에서 실행, 본문은 `public`으로 풀림(`A s chr_ckms_a.public 5`, 위 절) |
| PGS: B의 새 뷰·새 시퀀스 | 질문(트랜잭션이 있을 때)·기본 인스턴스 이동 | 질문 없음, 창은 B의 주 세션, 기본 A |
| PGS: 연결 Refresh 뒤 W1 `INSERT`, B 함수의 수정 | — | 같음(창은 B, W1 A, 트랜잭션 그대로) |
| PGO: W1(자기 세션 A) `INSERT`, B 함수의 수정, A `chr_ckms_s` 프로시저 실행 | (주 세션을 옮긴 뒤 새 세션이 복사) | 주 세션 A·`public` 그대로. 수정 창은 자기 세션 B, 실행 창은 자기 세션 A·`chr_ckms_s`(결과 `A s chr_ckms_a.chr_ckms_s 6`), W1 A, 트랜잭션 그대로 |
| MS: W1 `INSERT`(A), B 프로시저 수정 | 주 세션 `USE B`, W1 B | 주 세션 A 그대로, W1 A |
| MS: 이어서 B 프로시저 실행 | 주 세션 B | 글의 `USE B`가 공유 세션을 B로(위 절, 바꾸지 않음). 트랜잭션 그대로(`open_transaction_count` 1) |
| MSO: B 프로시저 수정 | — | 창 자기 세션 B, 주 세션 A. 글을 실행하자 끝의 `USE A`로 창 세션 A(위 표) |
| MA: W1 `INSERT`(A), B 트리거 수정, B 프로시저 실행 | 수정이 주 세션 `USE B`, W1 B | 주 세션 A 그대로, 실행 결과 `B, chr_ckms_b, 20`, W1 `DATABASE()` A, `INNODB_TRX` `RUNNING` 그대로 |
| ORA: W1 `INSERT`(A), B 프로시저 수정·실행, B 패키지 루틴의 소스 코드로 이동 | 수정이 주 세션 `CURRENT_SCHEMA=B` | 주 세션 `CHR_CKMS_A` 그대로(`v$session`), 트랜잭션 그대로 |

스크립트 글은 고치기 전·후에 같은 개체의 "ALTER 대상" 글(`SourceScripts.generateAlter`, 클립보드 항목과 같은 메서드를 시험 에이전트가 직접 부름, 시스템 클립보드는
쓰지 않음)과 수정·새 틀 창의 글을 파일로 남겨 비교했다(`D:\Tools\_scratch\commands-keep-main-session\s\text-compare.txt`): 31개 중 같음 21, 다름 10(위 절의 표:
SQL Server B의 프로시저·뷰·트리거와 수정 창, MariaDB B의 트리거와 수정 창, Oracle B의 프로시저·패키지·트리거와 수정 창).
시험에서 연 트랜잭션은 모두 명시적으로 끝냈다(질문의 롤백, 남은 것은 시험 에이전트가 앱의 `IdleTransactionMonitor.commitOrRollback`으로 롤백). 커밋된 행은 끝까지
DB마다 처음 1행이었다. 시험 개체는 끝에 지우고 카탈로그로 확인했다(`pg_database`·`sys.databases`·`information_schema.SCHEMATA`에 `chr_ckms%` 0개, Oracle `dba_users`·`dba_objects`에
`CHR_CKMS%` 0개).
캡처(내 앱 창만, Swing 구성 요소로 그림): `D:\Tools\_scratch\commands-keep-main-session\s\before\shots\pg-before-question.png`(지운 질문),
`pg-before-w1.png`, `…\s\after\shots\pg-after-modify-b.png`(질문 없이 열린 수정 창: 탭 번호 B, 탐색기의 굵은 기본 DB A), `pg-after-w1.png`(W1 탭 A의 번호·열린 트랜잭션).
로그: 같은 폴더의 `*.txt.log`.

**확인하지 못한 것:** Schema Compare 스크립트 창(화면에서 비교를 돌리지 않음, 코드 읽기와 빌드만), 오류로 가기(Oracle 컴파일 오류, 같은 `openSourceScript` 길), MySQL 8.4,
Oracle 자기 세션 창, 다시 시작·연결 끊기 뒤의 수정 창(개체에서 연 창과 같은 `setRestoredDefaults` 길). 회귀 테스트는 실행하지 않았다.

**둘째 고침 뒤(같은 날, 사용자 결정 "둘 다 고친다"):** 같은 시험 서버·같은 시험 개체를 다시 만들어 이 브랜치의 둘째 커밋 빌드에서 확인했다
(로그·글 `D:\Tools\_scratch\commands-keep-main-session\s\after2\`). MSO·MAO·ORAO는 창마다 자기 세션, MS·MA·ORA는 "쿼리 창마다 별도 연결" 안 함.

| 경우 | 첫 고침(`2c887213cc`) | 둘째 고침 |
|---|---|---|
| MSO: B 프로시저 수정 글, 창에서 실행 | 글 끝 `USE chr_ckms_a`, 실행 뒤 창 세션 A | 끝 문장 없음, 실행 뒤 창 `DB_NAME()` B(서버 `dm_exec_sessions` B) |
| MSO: B 뷰·트리거, A 프로시저의 글 | 뷰·트리거 끝 `USE A` | 뷰·트리거 끝 문장 없음, A 프로시저는 전과 같음 |
| MAO: B 트리거 수정 글, 창에서 실행(DROP 확인에 실행) | 글 끝 `USE chr_ckms_a;` | 끝 문장 없음, 실행 뒤 창 `DATABASE()` B, 트리거 다시 만들어짐 |
| ORAO: B 프로시저 수정 글, 창에서 실행 | 글 끝 `ALTER SESSION SET CURRENT_SCHEMA=CHR_CKMS_A` | 끝 문장 없음, 실행 뒤 창의 스키마 선택·`v$session.schemaname` `CHR_CKMS_B` |
| ORAO: B 패키지·테이블 트리거, A 프로시저의 글 | 끝 `ALTER SESSION … A`(B만) | 끝 문장 없음(B), A는 전과 같음 |
| MS·MA·ORA(공유): B 개체 수정 글을 창에서 실행 | 끝 문장 있음 | 같음. 실행 뒤 공유 주 세션 A(`DB_NAME()`·`DATABASE()` A, ORA 스키마 선택 `CHR_CKMS_A`) |
| ALTER 대상 ▸ 클립보드 글(MSO·MAO·ORAO, `generateAlter(…, false)`, 시스템 클립보드는 쓰지 않음) | 끝 문장 있음 | 같음(스스로 완결된 글) |
| PGS(공유): A의 `chr_ckms_s.chr_ckms_p` 프로시저 실행(A 주 세션 `public`) | 실행됨, `A s chr_ckms_a.public 5` | 실행 안 함: Messages "먼저 창의 스키마 목록에서 chr_ckms_s을(를) 고르십시오.", 상태 줄 "쿼리가 완료되었지만 오류가 있습니다", 문장 없음(트랜잭션 없음) |
| 같은 창에서 고르지 않고 F5 | — | 같은 글로 멈춤 |
| 같은 창의 스키마 목록에서 `chr_ckms_s`를 고르고 F5 | — | `SET search_path = "chr_ckms_s",…`(사용자 선택) 뒤 실행, `A s chr_ckms_a.chr_ckms_s 6` |
| PGS: B의 `public.chr_ckms_p`(B 주 세션 `public`) | 실행됨 | 실행됨, `B public chr_ckms_b.public 20` |
| PGO(자기 세션): A의 `chr_ckms_s.chr_ckms_p` | `A s chr_ckms_a.chr_ckms_s 6` | 같음 |

시험 트랜잭션은 모두 앱의 `IdleTransactionMonitor.commitOrRollback`으로 명시적으로 롤백했고(SQL Server `ALTER`도 롤백), 커밋된 행은 DB마다 처음 1행이었다. MariaDB·Oracle의
DDL(트리거·프로시저 다시 만들기, 같은 본문)은 서버가 바로 커밋한다. 시험 개체는 끝에 지우고 카탈로그로 확인했다(네 서버 모두 0개).
캡처(내 앱 창만): `…\s\after2\shots\pg2-execute-schema-message.png`(프로시저 실행 창의 Messages 글), `pg2-execute-after-pick.png`(스키마를 고른 뒤 결과).

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
- 2026-11-13 #89 검토 후속: 먼저 읽기의 실패는 기록만 하고 계속함, 새 읽기가 닿는 엔진 표. 고치기 전에 실패하던 행(펼치지 않음, 연결·다시 연결)을 다시 확인. 회귀 테스트는 실행하지 않았다.
- 2026-11-13 앱 변경 #108(`chromite/ddl-refresh-target`): `CREATE TABLE dbo.t`가 연결 전체를 다시 읽던 것을 고쳐 "연결을 다시 읽은 뒤의 DB 선택"의
  "이 순서를 이 문장에서 드러낸 것"과 "남은 것"에 고친 내용을 적음. #89의 선택 고침은 바꾸지 않았다.
- 2026-11-22 앱 변경 #172(`chromite/new-query-active-tab`): "New Query가 활성 창의 DB를 이어받음". 쿼리 창의 DB 선택과 세션 코드는 바꾸지 않고 주석만 고쳤다. 회귀 테스트는 실행하지 않았다.
- 2026-11-25 `chromite/reconnect-keeps-database`: "다시 연결 뒤의 DB"와 그 확인 기록. SQL Server·MySQL/MariaDB(시험), DB2·Exasol(코드만)의 다시 연 세션이 DB/스키마를
  이름으로 받고 받지 못하면 세션의 값을 읽음, Invalidate/Reconnect 뒤 쿼리 창이 선택을 다시 읽음, Disconnect·Reconnect 뒤 창의 자기 세션이 보이던 DB/스키마를
  지킴. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 쿼리 창의 새 세션·다시 연 세션의 DB(SQL Server, MySQL/MariaDB, DB2, Exasol), 쿼리 창 DB 선택·상태 줄,
  다시 시작 뒤 복원·New Query의 DB 이어받기(같은 `restoredDefaults` 자리), Invalidate/Reconnect 뒤의 선택 읽기.
- 2026-11-25 검토 후속(같은 브랜치): DB/스키마를 받지 못했을 때의 대체 읽기가 목록을 읽지 않음(이름만). 다시 여는 길은 인스턴스의 배타 잠금 아래라 목록 읽기가
  교착 모양이 된다. 회귀 테스트는 실행하지 않았다.
- 2026-11-25 `command` 병합(앱 변경 #186과 함께): MySQL의 대체 읽기를 #186의 `refreshDefaults(…, false, false)`로 합침(같은 일을 하던 `readCurrentDatabaseName` 삭제).
  회귀 테스트는 실행하지 않았다.
- 2026-11-25 `chromite/postgres-window-database-restore`: "다른 곳에서 바뀐 세션의 DB"와 그 확인 기록, "다시 연결 뒤의 DB"의 남은 것을 고침. 쿼리 창이 세션의
  기본값이 바뀐 모델 이벤트를 받아 선택을 다시 읽음(`MainFrame` 레지스트리 수신기, `SqlEditorPanel.contextDefaultsChanged`). 회귀 테스트는 실행하지 않았다.
  영향받을 수 있는 기능: 주 세션을 함께 쓰는 쿼리 창의 DB 선택·상태 줄·기억하는 이름(New Query, 다시 시작 복원), 수정·프로시저 실행·새 개체 틀·기본값으로
  설정 뒤의 쿼리 창, 모든 드라이버의 기본값 바꾸기 뒤 선택 다시 읽기 횟수.
- 2026-11-28 `chromite/postgres-open-in-object-database`: "개체에서 연 창의 DB"와 그 확인 기록. 개체에서 연 쿼리 창(새 쿼리, 상위 N개 행 선택, 스크립팅,
  SQL 생성, 바꾸기 스크립트의 쿼리 창에서 열기, ERD·데이터 검색·데이터베이스 복사·테이블 디자이너)이 개체의 DB/스키마에서 돎(`MainFrame.runWhereObjectIs` →
  `setRestoredDefaults`). 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 위 명령으로 연 창의 첫 세션과 DB 선택(모든 드라이버: SQL Server·MySQL `USE`,
  Oracle·Altibase 등 현재 스키마), 주 세션을 함께 쓰는 창(바뀌지 않음), 다시 연결·다시 시작 뒤 그 창의 복원 이름.
- 2026-11-28 같은 브랜치 후속: 같은 원인의 남은 길을 고침. 결과 그리드의 SQL 생성(결과 세션의 기본 스키마·DB), 사용 권한·보안 대화상자의 스크립트
  (`SecurityDialect.getContext`), 작업 스케줄러 스크립트(`Backend.location`: pg_cron·MySQL 이벤트의 DB). 연결만 넘기는 나머지 길의 이유를 적음.
  회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 결과 그리드 SQL 생성·보안 대화상자·스케줄러 대화상자에서 연 창의 첫 세션과 DB 선택.
- 2026-11-28 `chromite/bind-default-context-transaction`: "주 세션을 다른 DB로 옮기기 전 트랜잭션 질문"과 그 확인 기록. 수정·ALTER 대상·프로시저 실행·새 뷰/프로시저/함수·
  "새 &lt;종류&gt;" 틀·Schema Compare 스크립트·Go to Source Code·오류로 가기가 주 세션을 다른 데이터베이스 인스턴스(PostgreSQL DB)로 옮기기 전에 열린 트랜잭션을
  묻는다(`SourceScripts.confirmBindDefaultContext` → `ExitTransactionCheck.confirmDefaultChange`). SQL Server·MariaDB는 결함이 없어 바꾸지 않았다.
  회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 위 명령들의 시작(질문·취소), PostgreSQL 다른 DB 개체의 명령, 주 세션을 함께 쓰는 쿼리 창의 트랜잭션.
- 2026-11-28 `chromite/pg-own-session-reconnect-transaction`: "자기 세션을 다른 DB로 다시 연결할 때"와 그 확인 기록. 쿼리 관리자에 닫혔다고 알린 문맥이 다시 연결하면
  다시 알림, 다른 인스턴스로 옮기는 `JDBCExecutionContext.reconnectTo`(PostgreSQL 격리 문맥의 DB 바꾸기), Query Library 창의 Database가 자기 세션을 다른 DB로
  옮길 때 실행 전 질문. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: PostgreSQL 계열(Greenplum·GaussDB·Kingbase 포함) 자기 세션의 DB 바꾸기(개체에서 연 창,
  New Query, 다시 시작 복원, Query Library, 예약 실행, 데이터 전송), 쿼리 관리자 기록·상태 줄 트랜잭션 표시, 닫힌 문맥을 읽기가 다시 열 때의 쿼리 관리자 기록(모든 JDBC 드라이버).
- 2026-11-28 `chromite/query-tab-session-id`(앱 변경 #193): "탭의 세션 번호"와 그 확인 기록. 쿼리 창 탭 제목 끝의 세션 번호 `(52)`, 탭 도구 설명의 한 줄
  `Session ID : 52`. 세션을 열 때 방언이 번호를 읽어 문맥이 들고 있음(PostgreSQL 드라이버 값, SQL Server `@@SPID`, MySQL/MariaDB `CONNECTION_ID()`, Oracle PL/SQL `SYS_CONTEXT`).
  회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 쿼리 창 탭 제목·도구 설명·별도 창 제목·Window 메뉴 항목(제목을 씀), 자동 커밋 적용 대화상자의 창 이름
  (`AutoCommitApplier`가 탭 제목을 씀), SQL Server·MySQL/MariaDB·Oracle 세션을 열 때의 왕복 하나(모든 문맥), 세션 열기 실패 시의 기록.
- 2026-11-28 `chromite/shared-session-object-database`: "주 세션을 함께 쓰는 창: 개체의 DB의 주 세션"과 그 확인 기록. 주 세션을 함께 쓰는 PostgreSQL 창이 받은 DB
  (개체에서 연 창, 그 창의 New Query, 다시 시작 복원, 다시 연결)의 주 세션을 함께 쓴다(`QueryWindowContext.shareInstance`). 어느 세션도 옮기지 않는다(사용자 결정).
  수정·프로시저 실행 등 `bindDefaultContext`의 길은 바꾸지 않고 "사용자 결정 뒤 남은 것"에 사실과 제안을 적었다. 회귀 테스트는 실행하지 않았다.
  영향받을 수 있는 기능: 주 세션을 함께 쓰는 쿼리 창(모든 드라이버)의 첫 세션·DB 선택·상태 줄·트랜잭션 표시, 다시 연결·다시 시작 뒤 그 창의 DB, New Query의 DB 이어받기,
  PostgreSQL에서 그 창이 처음 쓸 때 여는 데이터베이스별 주·메타데이터 세션.
- 2026-11-29 `chromite/query-library-shared-session`: "Query Library 머리말 DB: 주 세션을 함께 쓰는 창"과 그 확인 기록. 주 세션을 함께 쓰는 PostgreSQL 창의
  Query Library 실행이 머리말 DB의 주 세션에서 돈다(`SqlEditorPanel.getRunContext`, `QueryWindowContext.shareInstance`). 전에는 공유 세션(연결의 기본 DB)을 옮기고 실행은
  옛 DB에서 돌았다. 머리말 스키마가 공유 세션의 스키마가 아니면 실행하지 않는다. "주 세션을 함께 쓰는 창: 개체의 DB의 주 세션"의 남은 것을 고침. 회귀 테스트는 실행하지 않았다.
  영향받을 수 있는 기능: Query Library 창의 실행(Run, Run on…, F5) 문맥, 주 세션을 함께 쓰는 PostgreSQL 창의 DB 묶음(다른 DB에 묶인 창이 기본 DB에도 묶임), 머리말 스키마가 있는 공유 창 실행.
- 2026-11-29 같은 브랜치 검토 뒤: 주 세션을 함께 쓰는 창이 머리말 DB로 옮겨 갈 때 떠나는 세션에 열린 트랜잭션이 있고 그 세션에 남는 쿼리 창이 없으면 실행 전에
  기본값으로 설정의 질문을 함(`QueryLibraryEditor.confirmLeaveSession`), 스키마 글을 짧게. 같은 절과 확인 기록. 회귀 테스트는 실행하지 않았다.
  영향받을 수 있는 기능: Query Library 창의 실행 시작(질문), 자기 세션 창의 PR 398 질문(같은 자리로 옮김).
- 2026-11-29 같은 브랜치 두 번째 검토 뒤: 위 질문의 설명 줄을 기본값으로 설정의 글에서 이 경우의 글(`window_move_txn_message`, `ExitTransactionCheck.confirmWindowMove`)로
  바꿈. 제목·목록·단추는 같다. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 기본값으로 설정·Modify 등의 질문(같은 코드를 나눠 씀, 글은 그대로).
- 2026-11-29 `chromite/commands-keep-main-session`: "주 세션을 옮기던 명령: 개체의 DB/스키마에서 여는 창"과 그 확인 기록. 수정·ALTER 대상·프로시저 실행·새 개체 틀·
  Schema Compare 스크립트·Go to Source Code·오류로 가기가 주 세션을 옮기지 않고, 창이 개체의 DB/스키마에서 열린다(`openSourceScript`가 개체를 받음). `bindDefaultContext`와
  PR 396의 질문 `confirmBindDefaultContext`를 지움("주 세션을 다른 DB로 옮기기 전 트랜잭션 질문"에 표시, "개체에서 연 창의 DB"의 해당 문장 고침). 회귀 테스트는 실행하지 않았다.
  영향받을 수 있는 기능: 위 명령들의 창이 도는 DB/스키마(자기 세션·공유 세션 모두), 다른 DB/스키마 개체의 ALTER 글(SQL Server·MySQL 트리거·Oracle에 돌아오는 문장이 더해짐),
  ALTER 대상 ▸ 클립보드의 글, PostgreSQL에서 그 DB의 세션을 처음 여는 때.
- 2026-11-29 같은 브랜치 둘째 커밋(사용자 결정 "둘 다 고친다"): 자기 세션 창의 ALTER 글은 끝의 "기본 DB/스키마로 돌아가기" 문장을 뺀다(모델 옵션
  `DBPScriptObject.OPTION_SKIP_DEFAULTS_RESTORE`, SQL Server·MySQL 트리거·Oracle 관리자가 봄, `SQLTriggerManager`에 옵션을 받는 메서드). 공유 창·클립보드 글은 그대로.
  프로시저 실행 창은 실행마다 루틴의 스키마를 보고(`QueryWindows.openCallScript`, `SqlEditorPanel.setRunDefaults`, PR 402의 검사), 공유 PostgreSQL 세션의 스키마가 다르면
  실행하지 않는다. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: SWT에서 온 객체 저장 경로(옵션 없으면 전과 같은 글), SQL Server·MySQL·Oracle·Altibase·generic·PostgreSQL
  트리거 관리자의 만들기·고치기(`SQLTriggerManager`의 새 메서드를 거침), 프로시저 실행 창의 모든 실행(F5 포함).
