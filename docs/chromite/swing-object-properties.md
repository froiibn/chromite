# Swing 개체 속성 편집 (Properties 창, Object Details 속성 탭)

## 목적

SWT 전수 조사의 "개체 속성 편집"(B-E1, B-E2, C-15)을 옮긴다. 테이블·뷰·열·인덱스·시퀀스·스키마 등의 Properties가 읽기 전용이거나
(시퀀스처럼 디자이너가 없는 개체는) 고칠 곳이 없었다. 이제 모델이 고칠 수 있다고 하는 속성은 Properties 창과 개체 편집기(Object Details의
속성 탭)에서 고치고, 고친 값은 다른 개체 명령(삭제, 이름 바꾸기, 새 개체)과 같은 길로 저장한다.

이 기능이 다루는 범위는 "Chromite가 지원하는 DB"가 아니라 **객체 관리자(`DBEObjectEditor`)가 있고 `@Property(updatable = true)`인 속성이 있는
드라이버**다. 어떤 속성이 고쳐지는지는 드라이버 모델이 정한다(아래 "고칠 수 있는 속성").

## 화면

- **Properties 창**(F4): Object Explorer에서 고른 개체의 속성 그리드. 고칠 수 있는 값은 보통 글자, 읽기 전용 값은 흐린 글자(SSMS처럼,
  고칠 수 있는 속성이 하나라도 있는 개체에서만)다. 값 칸을 두 번 누르거나 F2를 누르면 고친다.
  - 글·숫자: 칸 안 글 상자. Enter로 끝내고 Esc로 취소한다. 숫자가 아니면 "\"abc\"은(는) Max Value에 맞는 값이 아닙니다." 경고.
  - 참·거짓: True/False 콤보 상자. 고르면 바로 끝난다.
  - 가능한 값이 있는 속성(열거형, 목록 제공자: 테이블스페이스, 엔진, 문자 집합, 열 형식): 콤보 상자. 목록 밖 값을 받는 속성은 입력도 된다.
  - 여러 줄 글(주석·설명): "{속성} 편집" 대화상자(여러 줄 글 상자, 확인/취소).
- **Object Details**(F7): 객체(폴더가 아닌 노드)를 열면 첫 탭이 **속성** 탭이다(SWT 객체 편집기의 Properties 탭). 같은 그리드를 머리말 없이 쓰고,
  자식 목록 탭(열, 인덱스 등)은 그 뒤에 온다. 자식이 없는 객체(시퀀스)도 이제 Object Details를 열 수 있다(전에는 메뉴가 꺼졌다).
- **저장**: 값을 하나 바꾸면 바로
  1. 스크립트 미리 보기 "속성 바꾸기: {속성}"(복사, 쿼리 창에서 열기, 실행, **취소가 기본 단추와 처음 포커스**). DDL을 바로 커밋하는 DB(MariaDB·Oracle)는 그 한 줄이 붙는다.
  2. 실행을 고르면 그 연결에 커밋하지 않은 트랜잭션이 있을 때 커밋 / 롤백 / 취소 질문.
  3. 연결의 기본 실행 문맥에서 DDL을 실행한다. 오류면 "{개체}의 속성을 바꾸지 못했습니다." + DB 오류.
  4. 취소(미리 보기·트랜잭션 질문)와 오류는 모델의 값을 원래대로 되돌린다. 그리드는 저장 뒤 속성을 다시 읽는다.
- 객체 관리자가 그 속성에 대한 문장을 만들지 않으면 빈 미리 보기 대신 "이 데이터베이스에서는 이 변경에 대한 SQL 문이 만들어지지 않아 아무것도 바꾸지 않았습니다."를 알리고 되돌린다.

## 구조

| 위치 | 역할 |
|---|---|
| `ObjectPropertyEdits`(새 파일) | 고칠 수 있는지 판단(`createSource`, `describe`), 칸 편집기(글·콤보), 여러 줄 대화상자, 값 변환(`parse`)·변경 비교(`isChanged`), 저장(`apply`) |
| `PropertiesPanel` | 그리드에 편집기를 붙임: `isCellEditable`/`setValueAt`, 행마다 `getCellEditor`, 여러 줄은 `editCellAt`에서 대화상자, F2, 읽기 전용 값 흐리게, 저장 뒤 다시 읽기. 머리말 없이 Object Details에 넣는 생성자 인자 |
| `ObjectDetailsPanel` | 객체 노드의 첫 탭으로 `PropertiesPanel`(새로 고침 때 다시 읽음, 닫을 때 읽기 취소) |
| `ObjectExplorerPanel` | Object Details(F7) 메뉴를 자식이 없는 객체에서도 켬(폴더는 전처럼 자식이 있을 때만) |
| `ExplorerObjectCommands.runChange` | 미리 보기 → 트랜잭션 질문 → 실행 → 취소·오류면 `resetChanges(true)`. 문장이 없는 변경 알림을 더함 |
| `MainFrame` | Properties 창·Object Details에 쿼리 창 호스트를 넘김(미리 보기의 "쿼리 창에서 열기") |
| `internal/CoreMessages`(+`_ko`) | `properties_change_title`, `properties_change_error`, `properties_invalid_value`, `properties_edit_title` |
| `internal/NavigatorMessages`(+`_ko`) | `navigator_change_no_script` |

모델 쪽은 고치지 않았다: `PropertySourceEditable`(속성 값 쓰기와 명령), `ObjectPropertyDescriptor.isEditable`(관리자·`updatable`),
`SQLObjectEditor`의 `ObjectChangeCommand`(드라이버 관리자의 `addObjectModifyActions`·`addObjectExtraActions`가 ALTER/COMMENT를 만듦).

## 설계 결정

### 고칠 수 있는 속성

- SWT 객체 편집기와 같이 `PropertySourceEditable`로 속성을 모으고 `ObjectPropertyDescriptor.isEditable(object)`를 묻는다. 그 답은
  객체 관리자(`DBEObjectEditor`)가 있고 편집을 허락하며(`canEditObject`, 메타데이터 편집 권한), 저장된 객체에서는 `updatable`(또는 `updatableExpr`)이다.
- 여기서 더 빼는 것:
  - **이름**: 속성 명령은 ALTER … RENAME을 만들지 않는다. 이름은 Rename(F2, `DBEObjectRenamer`)으로 바꾼다.
  - **암호 속성**(`@Property(password = true)`): 고치지 않는다. 값이 미리 보기·쿼리 관리자에 남기 때문이다. 암호는 각 보안 대화상자의 가린 칸에서 바꾼다.
  - 그리드에서 편집기를 줄 수 없는 형식: 날짜, 목록 제공자가 없는 객체 참조. 가능한 값이 `null`인 목록 속성(예: 문자 집합이 없을 때의 MySQL Collation)도 읽기 전용이다.
- 읽기 전용 연결과 메타데이터 변경 제한(`DBUtils.isReadOnly`)이면 아무것도 고치지 않는다(삭제·이름 바꾸기와 같은 판단).
- 가능한 값은 DB를 읽을 수 있어(테이블스페이스 목록 등) 속성을 읽는 작업 스레드에서 미리 읽는다.

### 바로 저장(값마다 미리 보기)

SWT 편집기는 바꾼 값을 모아 두었다가 Save로 저장했다. Swing Properties 창은 탐색기 선택을 따라가므로, 모아 두면 다른 노드를 고를 때 저장하지 않은
값이 사라지거나 그때마다 질문을 남겨야 한다. 그래서 값 하나를 바꿀 때마다 저장 흐름을 탄다. 미리 보기가 곧 확인이고 취소가 기본이라
Enter를 습관처럼 눌러도 아무것도 실행되지 않는다. 시퀀스 설정처럼 여러 값을 바꾸면 값마다 미리 보기가 나온다(되돌리기 쉬운 결정, 아래 "다음 작업").

### 실행 문맥

다른 개체 명령과 같이 연결의 기본(주) 실행 문맥에서 실행한다(`DBUtils.getDefaultContext(object, false)`). 쿼리 창은 저마다 격리된 문맥을 쓰므로
([transactions.md](transactions.md) "쿼리 창마다 별도 연결") 사용자의 쿼리 트랜잭션에 DDL이 섞이지 않는다. "단일 연결" 연결처럼 주 문맥을 함께 쓰는
경우는 실행 전 트랜잭션 질문(`ExitTransactionCheck.confirmObjectChange`)이 막는다. 모델의 저장 경로는 명령마다 커밋한다.

### 되돌리기

`PropertySourceEditable.setPropertyValue`는 값을 객체에 바로 쓰고 명령을 쌓는다. 그래서 명령 묶음은 원자적이 아닌 `AbstractCommandContext(…, false)`다.
삭제·이름 바꾸기가 쓰는 원자적 묶음은 되돌릴 때 반영기(`undoCommand`)를 부르지 않아 객체에 새 값이 남는다. 미리 보기 취소, 트랜잭션 질문 취소,
실행 오류, 문장이 없는 변경이면 `runChange`가 `resetChanges(true)`로 명령을 되돌려 객체가 원래 값으로 돌아간다(더러운 상태로 남지 않음).
실패한 저장은 모델의 `saveChanges`가 그 명령의 트랜잭션을 롤백한다.

## PostgreSQL 테이블스페이스 (앱 변경 #129)

### 목적

PostgreSQL 테이블스페이스는 New Tablespace…(소유자·위치·옵션)와 삭제만 되었다. 이름, 소유자, 옵션을 바꿀 수 없었다.
사용자 결정(2026-11-15): "추가 가능한 데이터베이스에 대해 저장소를 관리할 수 있는 기능을 추가한다". 조건은 Altibase 테이블스페이스(#291)와 같다:
지금 앱의 흐름 안에서만, 설명 문구 없이.

### 무엇을 어디서

| 명령 | 길 | 문장 |
|---|---|---|
| 이름 바꾸기 | 탐색기 F2 / 메뉴 "이름 바꾸기"(다른 개체와 같은 입력 창 → 미리 보기) | `ALTER TABLESPACE a RENAME TO b` |
| 소유자 | Properties 창·Object Details 속성 탭의 Owner(역할 목록 콤보) | `ALTER TABLESPACE t OWNER TO r` |
| 옵션 | 같은 곳의 Options(글: `이름=값`을 쉼표로) | 바뀌거나 새로 넣은 옵션 `ALTER TABLESPACE t SET (a=1, b=2)`, 뺀 옵션 `ALTER TABLESPACE t RESET (c)` |

새 메뉴 항목, 대화상자, 문구는 없다. 속성 이름(Owner/소유자, Options/옵션)은 드라이버 번들에 원래 있던 것이다.

### 속성 편집을 고른 이유

데이터베이스 소유자가 이미 이 길로 바뀐다(`PostgreDatabase.getDBA`: `editable`·`updatable`·역할 목록, `PostgreDatabaseManager`가 `OWNER TO`를 만듦).
테이블스페이스의 소유자와 옵션도 같은 속성이라 같은 선언으로 고칠 수 있다. 그래서 "Tablespace Settings…" 대화상자를 따로 만들지 않았다.
값마다 미리 보기가 나오고, 취소·오류면 속성 편집이 모델을 되돌린다(위 "되돌리기").

### 구조

- `PostgreTablespace`(ext.postgresql): `DBPNamedObject2`(이름 바꾸기 반영기가 이름과 캐시를 바꿈), Owner·Options를 `editable = true, updatable = true`,
  Owner의 목록 `RoleListProvider`(데이터베이스의 역할), `setOwner`. 서버에 있는 옵션 `getSavedOptions`(카탈로그에서 읽고, 실행된 SET/RESET·CREATE 뒤 갱신),
  `parseOptions`(이름은 소문자: 서버가 따옴표 없는 이름을 접음), `isSystemTablespace`.
- `PostgreTablespaceManager`: `DBEObjectRenamer`, `addObjectRenameActions`, `addObjectModifyActions`(소유자, 옵션의 SET/RESET), `canEditObject`·`canRenameObject`.
- UI 코드는 바꾸지 않았다: 이름 바꾸기는 `ExplorerObjectCommands.rename`, 속성은 `ObjectPropertyEdits`가 관리자를 보고 켠다.

### 확인한 문법 (PostgreSQL 17.11, 시험 서버, 임시 개체 `chr_ts_*`)

| 문장 | 서버 답 |
|---|---|
| `ALTER TABLESPACE chr_ts_a RENAME TO chr_ts_b` | 성공, `pg_tablespace.spcname` 바뀜(OID 그대로) |
| `… RENAME TO pg_chr_ts` / `… RENAME TO pg_default` | 42939 `unacceptable tablespace name`, "The prefix "pg_" is reserved for system tablespaces." |
| `… RENAME TO "chr_ts_Mixed"` | 성공(따옴표 이름 그대로) |
| `ALTER TABLESPACE chr_ts_b OWNER TO chr_ts_owner` | 성공, `spcowner` 바뀜. 없는 역할은 42704 |
| `… SET (random_page_cost=3, effective_io_concurrency=10)` | 성공, `spcoptions`에 더해짐(있던 옵션은 그대로) |
| `… SET (seq_page_cost = 2)`, `SET (SEQ_PAGE_COST=1.25)`, `SET (seq_page_cost='1.75')` | 성공(공백·대문자·따옴표 값 모두 받음, 이름은 소문자로 저장) |
| `… RESET (effective_io_concurrency)` / 없는 옵션 RESET | 성공 / 성공(바뀌는 것 없음) |
| `… SET (seq_page_cost=1) RESET (random_page_cost)` | 42601 구문 오류: 한 문장에 SET과 RESET을 함께 받지 않음 → 두 문장 |
| `… SET (fillfactor=50)` / `SET (seq_page_cost=abc)` | 22023 `unrecognized parameter` / `invalid value for floating point option` |
| 트랜잭션 안 `ALTER TABLESPACE … RENAME` 뒤 롤백 | 이름이 돌아옴. 같은 트랜잭션의 앞 INSERT도 남음(ALTER가 커밋하지 않음) |
| 트랜잭션 안 `DROP TABLESPACE` | 25001 `cannot run inside a transaction block`(기존 삭제·만들기는 그래서 자동 커밋 동작) |
| 권한 없는 로그인 역할의 RENAME / OWNER TO / SET | 42501 `permission denied for tablespace` / `must be owner of tablespace` |

JDBC `dataDefinitionCausesTransactionCommit` = false: 미리 보기에 "DDL을 바로 커밋" 줄이 붙지 않는다(다른 PostgreSQL DDL과 같음).
ALTER는 트랜잭션 안에서 되므로 원자적 동작(자동 커밋으로 바꿈)이 아닌 보통 동작이다. 연결의 기본 실행 문맥에서 실행하고 명령마다 커밋한다(위 "실행 문맥").

### 시스템 테이블스페이스

pg_default와 pg_global에는 새 명령이 없다(이름 바꾸기 꺼짐, 속성 읽기 전용). 이름 목록이 아니라 카탈로그로 가린다: initdb가 만든 개체의
OID는 FirstNormalObjectId(16384)보다 작다(시험 서버 1663, 1664. `PostgreDialect`의 잠금 질의도 같은 경계를 쓴다). 사용자가 만드는 테이블스페이스는
서버가 "pg_" 이름을 막으므로(위 표) 그 둘과 겹치지 않는다. 시스템 테이블스페이스 자체를 바꾸는 문장은 시험 서버에서 실행하지 않았다(남이 만든 저장소는 바꾸지 않음).
기존 삭제는 그대로다(관리자가 이름 pg_default·pg_global을 보고 오류를 보임, 범위 밖).

### 확인 기록 (2026-11-15, Windows, 이 브랜치 빌드, 새 작업 공간, 시험 서버 PostgreSQL 17)

DB 상태는 매번 JDBC로 `pg_tablespace`·`pg_roles`를 읽어 확인했다. 증거: `D:\Tools\_scratch\pg-mysql-storage\evidence`, `run\*.log`.

| 확인 | 미리 보기 문장 | 카탈로그 전 → 뒤 | 트리·속성 뒤 |
|---|---|---|---|
| chr_ts_b에서 F2 → 입력 창 취소 | (없음) | 그대로 | 그대로 |
| F2 → chr_ts_r → 미리 보기 취소 | `ALTER TABLESPACE chr_ts_b RENAME TO chr_ts_r;` | 그대로 | chr_ts_b |
| 같은 것 → 실행 | 같음 | spcname chr_ts_b → chr_ts_r(OID 18287) | chr_ts_r |
| Owner chr_ts_owner → postgres, 미리 보기 취소 / 실행 | `ALTER TABLESPACE chr_ts_r OWNER TO postgres;` | 취소: 그대로 / 실행: spcowner postgres | 취소: chr_ts_owner / 실행: postgres |
| Options `random_page_cost=3,seq_page_cost=1.75` → `random_page_cost=2, effective_io_concurrency=5` | `SET (random_page_cost=2, effective_io_concurrency=5);` `RESET (seq_page_cost);` | `{random_page_cost=2,effective_io_concurrency=5}` | 새 값 |
| 같은 옵션을 공백만 바꿔 입력 | (문장 없음) | 그대로 | "이 변경에 대한 SQL 문이 만들어지지 않아…" 알림, 되돌림 |
| `random_page_cost`(값 없음) | (만들지 않음) | 그대로 | 오류 "Tablespace option is not name=value: random_page_cost", 되돌림 |
| `…, fillfactor=50` 더함 → 실행 | `SET (fillfactor=50);` | 그대로 | 서버 오류 22023, 원래 값 |
| New Tablespace… chr_ts_n(소유자 chr_ts_owner, /tmp/chr_ts_pg2, `seq_page_cost=1.5`) 뒤 Options `random_page_cost=4` | `SET (random_page_cost=4);` `RESET (seq_page_cost);` | 만든 옵션과 비교됨 | 새 값 |
| 폴더 새로 고침 뒤 Options 비움 | `RESET (random_page_cost);` | spcoptions null | 빈 값 |
| chr_ts_n → chr_ts_r(있는 이름) / pg_chr_ts | 각 RENAME | 그대로 | 서버 오류 42710 / 42939, 트리 chr_ts_n |
| pg_default·pg_global | — | — | 메뉴 "Rename" 꺼짐, 속성 모두 읽기 전용 |
| 읽기 전용 연결에서 F2 | — | 그대로 | "PG does not allow changing database objects…" 알림, 속성 읽기 전용 |
| 권한 없는 로그인(chr_ts_low)으로 F2 / Options | 각 문장 | 그대로 | 42501 오류, 트리·값 그대로 |
| 수동 커밋 연결(PGM), 쿼리 창에 데이터 변경 트랜잭션 뒤 F2 → 실행 | 각 RENAME | | "커밋하지 않은 트랜잭션" 질문(취소 기본) |
| 그 질문에서 취소 / 롤백 / 커밋 | | 취소: 그대로 / 롤백: 쿼리 창 트랜잭션 롤백 뒤 이름 바뀜 / 커밋: 커밋 뒤 이름 바뀜 | `pg_stat_activity`로 쿼리 창 세션이 idle(트랜잭션 없음)인 것 확인 |
| 기존 명령: New Schema chr_ts_s → F2 chr_ts_s2 → 삭제 | `CREATE SCHEMA`, `ALTER SCHEMA … RENAME`, `DROP SCHEMA … CASCADE` | 만들어지고 바뀌고 지워짐 | 같음 |
| #291 Altibase: Tablespaces 메뉴, New Tablespace… 열고 취소 | — | — | 대화상자 그대로(이름·종류·데이터 파일·크기) |

확인하지 못한 것: 여러 데이터베이스를 보이는 연결에서 다른 데이터베이스 노드의 테이블스페이스 목록(데이터베이스마다 캐시가 따로라 새로 고침 전까지 옛 이름이 보일 수 있다, 아래 "알려진 문제").

### 알려진 문제 (범위 밖, 보고만)

- 테이블스페이스는 클러스터 개체인데 모델은 데이터베이스마다 캐시를 둔다. 한 데이터베이스 노드에서 이름을 바꾸면 다른 데이터베이스·다른 연결 노드는 새로 고침 전까지 옛 이름이다.
- Properties 창은 이름 바꾸기 뒤 다른 값을 고치거나 노드를 다시 고를 때까지 옛 이름을 보인다(스키마도 같음).

## 한 번만 읽기 (2026-11-25, `chromite/properties-read-once`)

### 문제

Properties 창(`PropertiesPanel.showNode`)은 부를 때마다 앞 읽기 작업을 취소하고 새로 읽는다. Object Explorer는 트리 선택 사건마다 선택 노드를 넘겼는데
(`ObjectExplorerPanel` 선택 리스너), 가지를 모델에서 다시 보일 때(`replaceChildrenPreserving`: 연결 새로 고침, DDL 뒤 스키마 새로 고침, 노드 추가)
`nodeStructureChanged`가 그 아래 선택을 빼고(선택 없음 사건) 남은 경로를 다시 고르는(같은 노드 사건) 두 사건이 났다. 연결 새로 고침은 읽어 둔 층마다
따로 다시 보여, 표 하나를 고른 채 연결을 한 번 새로 고치면 같은 표를 8번 읽고 7번 취소했다(아래 표). 취소된 읽기의 문장은 Query Manager에 사용자의 취소
오류로 남았다([swing-query-manager.md](swing-query-manager.md) "앱이 취소한 읽기").

### 고친 것

- 선택 리스너에는 **선택 노드나 그 객체가 바뀔 때만** 넘긴다(`ObjectExplorerPanel.selectionChanged`): 마지막으로 넘긴 노드와 그때의 객체
  (`DBNDatabaseNode.getObject`)를 기억해, 같으면 넘기지 않는다. 다른 노드, 같은 노드라도 모델이 다시 읽어 객체가 바뀌었으면 넘긴다.
- 가지를 다시 보이는 동안(`replaceChildrenPreserving`)의 선택 사건은 무시하고, 끝난 뒤 결과만 본다(선택 노드가 없어졌으면 선택 없음, 객체가 바뀌었으면 다시 읽기).
- **새로 고침은 객체가 같아도 한 번 읽는다**(`selectionRefreshed`). 선택 노드나 그 위 노드가 새로 고쳐지면 — 여기서 F5로 다시 읽은 가지(`showChildren`의
  새로 고침), 탐색기 밖의 노드 새로 고침 알림(`navigatorNodeChanged`의 UPDATE·REFRESH, 연결의 REFRESH) — 그 알림 묶음과 트리 변경이 끝난 뒤 한 번 다시 읽는다.
  PostgreSQL·SQL Server·MariaDB는 테이블·뷰·루틴을 같은 객체에 캐시만 비워 새로 고치는 때가 있어(같은 객체, 새 값), 객체 비교만으로는 새 값을 보이지 못했다
  (아래 "새로 고침 뒤의 값"). 새로 고침이 아닌 노드 변경 알림 뒤에는 객체가 바뀐 때만 읽는다.
- `showSelectionAgain`(Altibase 계열 작업 실행 뒤)은 전처럼 늘 다시 넘긴다. Properties 창의 값 저장 뒤 다시 읽기, Object Details 속성 탭의 새로 고침은
  `PropertiesPanel`이 직접 부르므로 바뀌지 않는다.
- 더는 필요 없는 읽기는 전처럼 버린다: 다른 노드를 고르면 앞 읽기를 취소하고, 늦게 끝난 결과는 번호로 거른다.

### 측정 (Properties 창의 읽기 작업 수 / 그중 취소, 시험 VM, 스크래치 작업 공간, 시험 개체 `chr_properties_read_once`)

읽기는 작업 "Read properties of …"의 수, 상황마다 6초를 기다려 셌다. 문장 수는 Query Manager 모델(메타데이터 포함)의 같은 구간 실행 수로, 트리 읽기도 들어 있다.

고친 뒤 값은 마지막 빌드(새로 고침 뒤 다시 읽기 포함)의 것이다.

| DB | 노드 | 선택 | 연결 새로 고침 | F5(고른 노드) | 다시 연결 뒤 선택 | 노드를 바꾸는 DDL |
|---|---|---|---|---|---|---|
| PostgreSQL 17 | 테이블 | 1 → 1 | 8/7 → 1/0 | 0 → 1 | 1 → 1 | 2/1 → 1/0 |
| | 뷰 | 1 → 1 | 8/6 → 1/0 | 0 → 0 | 1 → 1 | 2/0 → 1/0 |
| | 함수 | 1 → 1 | 8/6 → 1/0 | 0 → 0 | 1 → 1 | 2/1 → 1/0 |
| | 스키마 | 1 → 1 | 5/4 → 1/0 | 0 → 1 | 1 → 1 | 0 → 1 |
| | 연결 | 1 → 1 | 2/0 → 1 | 1 → 1 | 0 → 0 | 0 → 0 |
| SQL Server 2022 | 테이블 | 1 → 1 | 9/6 → 2/0 | 0 → 1 | 1 → 1 | 2/1 → 1/0 |
| | 뷰 | 1 → 1 | 8/0 → 2/0 | 0 → 0 | 1 → 1 | 6/0 → 2/0 |
| | 저장 프로시저 | 1 → 1 | 8/0 → 1/0 | 0 → 0 | 1 → 1 | 6/0 → 2/0 |
| | 스키마 | 1 → 1 | 7/0 → 2/0 | 0 → 1 | 1 → 1 | 0 → 1 |
| | 연결 | 1 → 1 | 3/0 → 1 | 2 → 1 | 0 → 0 | 1/0 → 1 |
| MariaDB 11 | 테이블 | 1 → 1 | 6/4 → 1/0 | 0 → 1 | 1 → 1 | 2/1 → 1/0 |
| | 뷰 | 1 → 1 | 6/5 → 1/0 | 0 → 0 | 1 → 1 | 2/1 → 1/0 |
| | 함수 | 1 → 1 | 6/0 → 1/0 | 0 → 0 | 1 → 1 | 2/1 → 1/0 |
| | 데이터베이스 | 1 → 1 | 4/2 → 1/0 | 0 → 1 | 1 → 1 | 0 → 1 |
| | 연결 | 1 → 1 | 1/0 → 1 | 1 → 1 | 0 → 0 | 1/0 → 1 |

- 연결 새로 고침은 탐색기 밖에서 연결 노드를 새로 고친 것(쿼리 창의 CREATE DATABASE 뒤와 같은 길, `DBNNode.refreshNode`)이다.
- DDL은 노드를 고른 채 쿼리 창에서 실행했다: 테이블 열 더하기, 뷰·루틴 바꾸기, 스키마(MariaDB는 데이터베이스)에 테이블 만들기, 연결에 스키마·데이터베이스 만들기.
  SQL Server의 뷰·프로시저는 `USE`와 `GO`로 나눴다(첫 실행은 `;`로만 이어 써서 "ALTER VIEW must be the first statement in a query batch"로 실패해 다시 쟀다).
  PostgreSQL의 `CREATE SCHEMA`는 연결이 아니라 데이터베이스 노드를 새로 고치므로 고른 연결은 다시 읽지 않는다(0).
- 다시 연결(Object Explorer의 Reconnect)은 고친 전후 모두 읽기 0이다. 다시 연결은 가지를 접고 선택을 빼며, 선택 없음은 읽지 않는다.
- F5 열의 테이블·스키마는 펼쳐 둔 노드(고친 뒤 측정에서 앞 단계가 펼침), 뷰·루틴은 펼친 적 없는 노드다. 펼친 적 없는 노드의 F5는 서버를 부르지 않고 글자만
  다시 그린다(`refreshSelection`의 "Never expanded" 분기, 이 변경 밖, 아래 "새로 고침 뒤의 값"의 (a)).
- SQL Server의 2/0은 알림 묶음 뒤의 한 번과, 그 뒤 가지를 다시 보일 때 모델이 객체를 새로 만들어 생긴 한 번이다. 둘 다 끝까지 읽고(취소 0) 마지막 값이 새 값이다.

### 새로 고침 뒤의 값 (2026-11-25)

표·뷰·루틴을 고르고 속성을 보인 뒤, 앱 밖의 둘째 세션(드라이버로 직접 연 JDBC 연결)에서 Properties 창이 보이는 주석(SQL Server는 `MS_Description`)을 바꾸고
(a) 고른 노드에서 F5(펼친 적 없음), (a2) 펼친 뒤 F5, (b) 부모 폴더, (c) 스키마, (d) 연결을 새로 고쳤다((b)~(d)는 탐색기 밖의 노드 새로 고침, `DBNNode.refreshNode`).
MariaDB의 뷰에는 주석 속성이 없어 표와 루틴만 쟀다. 칸은 "읽기 수, 새 값이면 새·옛 값이면 옛"이고, 세 빌드는 이 변경 전(9112f945c9), 첫 고침(239d8562f8),
마지막 고침이다.

| DB | 노드 | (a) F5 | (a2) 펼친 뒤 F5 | (b) 폴더 | (c) 스키마 | (d) 연결 |
|---|---|---|---|---|---|---|
| PostgreSQL | 테이블 | 0 옛 / 0 옛 / 0 옛 | 0 옛 / 0 옛 / 1 새 | 2 새 / 1 새 / 1 새 | 2 새 / 1 새 / 1 새 | 8 새 / 1 새 / 1 새 |
| | 뷰 | 0 옛 / 0 옛 / 0 옛 | 0 옛 / 0 옛 / 1 새 | 2 새 / 1 새 / 1 새 | 2 새 / 1 새 / 1 새 | 7 새 / 1 새 / 1 새 |
| | 함수 | 0 옛 / 0 옛 / 0 옛 | 0 옛 / 0 옛 / 1 새 | 2 새 / 1 새 / 1 새 | 2 새 / 1 새 / 1 새 | 8 새 / 1 새 / 1 새 |
| SQL Server | 테이블 | 0 옛 / 0 옛 / 0 옛 | 0 옛 / 0 옛 / 1 새 | 2 새 / 1 새 / 1 새 | 2 새 / 1 새 / 1 새 | 8 새 / 1 새 / 1 새 |
| | 뷰 | 0 옛 / 0 옛 / 0 옛 | 0 옛 / 0 옛 / 1 새 | 2 새 / 1 새 / 1 새 | 2 새 / 1 새 / 1 새 | 9 새 / 1 새 / 1 새 |
| | 프로시저 | 0 옛 / 0 옛 / 0 옛 | 0 옛 / 0 옛 / 1 새 | 2 새 / 1 새 / 1 새 | 2 새 / 1 새 / 1 새 | 8 새 / 1 새 / 2 새 |
| MariaDB | 테이블 | 0 옛 / 0 옛 / 0 옛 | 0 옛 / 0 옛 / 1 새 | 2 새 / 1 새 / 1 새 | 2 새 / 1 새 / 1 새 | 7 새 / 1 새 / 1 새 |
| | 함수 | 0 옛 / 0 옛 / 0 옛 | 0 옛 / 0 옛 / 1 새 | 2 새 / 1 새 / 1 새 | 2 새 / 1 새 / 1 새 | 5 새 / 1 새 / 1 새 |

- 마지막 고침의 (a2)~(d)는 SQL Server 프로시저의 (d) 2번을 빼고 모두 한 번 읽고 새 값이다. (a2)는 이 변경 전과 첫 고침에서 옛 값이었다(고른 노드 자신의 행은
  선택 사건이 없음). 세 DB는 (b)~(d)에서 객체를 새로 만들어 첫 고침도 새 값이었지만, 객체를 그대로 두고 캐시만 비우는 드라이버에서는 객체 비교로 알 수 없으므로
  새로 고침 알림으로 읽는다.
- (a) 펼친 적 없는 노드의 F5는 세 빌드 모두 서버를 부르지 않아 옛 값이다(`refreshSelection`, 이 변경 밖, 보고만).

### 확인하지 못한 것

- Altibase·ShardSphere·Oracle에서는 재지 않았다(Oracle 1522는 쓰지 않음). 고친 곳은 DB와 상관없는 탐색기 코드다.

## upstream 대비 변경점

- SWT `EntityEditor`의 Properties 탭(편집·Save·Revert·View Script)을 Properties 창과 Object Details 속성 탭의 바로 저장으로 바꿨다.
  미리 보기는 SWT에서 설정에 따라 선택이었지만 여기서는 늘 보인다(삭제·이름 바꾸기와 같음).
- SWT 속성 창은 엔터티 편집기 안에서만 편집되었다. Swing은 탐색기 선택의 Properties 창에서도 고친다.
- upstream의 PostgreSQL 테이블스페이스는 이름 바꾸기·편집이 없었다(`canEditObject` false, 이름 바꾸기 관리자 없음). Chromite는 사용자 테이블스페이스에 둘 다 연다.

## 확인

회귀 테스트는 만들지 않았다(요청 없음). 앱 사본에서 확인한 내용은 아래 "확인 기록"에 있다.

### 확인 기록 (2026-11-08, Windows, 이 브랜치 빌드, 작업 공간 사본, Docker 테스트 DB의 임시 DB·스키마 `chr_object_properties_edit`·`CHR_OPE`, 프로세스 안 드라이버로 조작)

DB 상태는 매번 `docker exec`로 카탈로그를 읽어 확인했다.

| 확인 | 결과 |
|---|---|
| PostgreSQL 테이블 Properties(영어) | 고칠 수 있음: Tablespace(목록), Has Row-Level Security(참·거짓), Partition by(글), Comment(여러 줄). 나머지는 흐리게(`#a8adbd`) |
| Comment 두 번 누름 → "Edit Comment" 대화상자 → 글 바꿈 → OK | 미리 보기 "Change Property: Comment", `COMMENT ON TABLE ope.ope_table IS '…';`, Cancel이 기본 단추·포커스 |
| 미리 보기 Cancel | 그리드가 원래 값(`original table comment`), DB도 그대로 |
| 다시 바꾸고 Execute | DB `obj_description` = `changed by properties`, 그리드 같은 값 |
| Has Row-Level Security → true, Execute | `ALTER TABLE ope.ope_table ENABLE ROW LEVEL SECURITY;`, DB `relrowsecurity` = t |
| Partition by = `id`, Execute (모델이 잘못된 문장을 만듦) | 오류 "The property of ope.ope_table could not be changed. … syntax error at or near "PARTITION"", 그리드가 빈 값으로 돌아감 |
| Tablespace = pg_global, Execute | 오류 "only shared relations can be placed in pg_global tablespace", 그리드 `pg_default`, DB 그대로 |
| 시퀀스 Increment By 5, Execute | `ALTER SEQUENCE ope.ope_seq INCREMENT BY 5;`, DB 5 |
| 시퀀스 Min Value 5000(> Max 1000), Execute | 오류 "MINVALUE (5000) must be less than MAXVALUE (1000)", 그리드 1 |
| 시퀀스 Max Value `abc` | 경고 "\"abc\" is not a valid value for Max Value.", 아무것도 실행하지 않음 |
| 시퀀스 Comment, Execute | `COMMENT ON SEQUENCE …`, DB 반영 |
| SQL Server 테이블 "Object description (comment)", Execute | `EXEC … sp_addextendedproperty 'MS_Description', N'…'`, `fn_listextendedproperty` 반영 |
| SQL Server 열 Length 50 → 100, Execute | `ALTER TABLE … ALTER COLUMN name nvarchar(100) COLLATE … NULL;`, `sys.columns.max_length` 200 |
| SQL Server 시퀀스 | 모델에 고칠 수 있는 속성이 없어 모두 읽기 전용 |
| MariaDB 테이블 Engine = MyISAM | 미리 보기에 "DDL을 바로 커밋" 줄, Cancel → 그리드·DB `InnoDB` |
| MariaDB 테이블 Description, Auto Increment 500, Execute | `ALTER TABLE … COMMENT='…'`, `AUTO_INCREMENT=500`, DB 주석 반영 |
| MariaDB 시퀀스 | 모델에 고칠 수 있는 속성이 없어 읽기 전용(소스 편집으로 바꿈) |
| Oracle 테이블 Comment, Execute | `COMMENT ON TABLE CHR_OPE.OPE_TABLE IS '…'`, `all_tab_comments` 반영 |
| Oracle 시퀀스(한 번도 쓰지 않음) Increment 3, Cache 50 | 오류 ORA-04007(모델이 MINVALUE를 늘 다시 적음, 아래 "알려진 문제"), 그리드 원래 값 |
| Oracle NEXTVAL 한 번 뒤 Increment 3, Execute | `ALTER SEQUENCE … INCREMENT BY 3 …`, `all_sequences.increment_by` 3 |
| Oracle Cycle true → Cancel, Max Value 0 → 오류 ORA-04004 | 둘 다 그리드 원래 값 |
| 한국어(`-nl ko`) 시퀀스 노드에서 개체 세부 정보(전에는 꺼짐) | 문서 탭 "ope_seq - 세부 정보", 탭 `[속성]` 하나 |
| 속성 탭에서 캐시 10, 실행 | 미리 보기 "속성 바꾸기: 캐시", 머리글 "다음 스크립트를 PostgreSQL 테스트에서 실행합니다. 지금 실행하시겠습니까?", 단추 복사·쿼리 창에서 열기·실행·**취소(기본·포커스)**. DB `cache_size` 10 |
| 최대값 `abc` / 주석 편집 대화상자 | "\"abc\"은(는) 최대값에 맞는 값이 아닙니다." / "주석 편집", 확인·취소 |
| 수동 커밋 연결 유형에서 쿼리 창 `INSERT` 실행 뒤 순환 true → 실행 | "커밋하지 않은 트랜잭션": "PostgreSQL 테스트 (SQLQuery1.sql)", 커밋·롤백·**취소(기본·포커스)** |
| 그 질문에서 취소 | 그리드 순환 false(되돌림), DB 그대로 |
| 다시 → 롤백 | INSERT가 롤백됨(행 0), 순환 true가 실행됨 |
| SQL Server 인덱스 고유 true → 쿼리 창에서 열기 | 새 쿼리 창 `SQLQuery2.sql - SQL Server 테스트`에 DROP/CREATE INDEX 스크립트, 그리드 false(되돌림), 실행 안 함 |

확인하지 못한 것: 문장이 없는 변경의 알림(이 네 DB에서 그런 속성을 찾지 못함, 코드로만 확인), 읽기 전용 연결에서 편집이 꺼지는 경로(코드로만 확인).

### 알려진 문제 (범위 밖, 보고만)

두 가지 모두 2026-11-08에 고쳤다([model-fixes.md](model-fixes.md) "모델 DDL 수정"). 위 확인 기록에서 Partition by와 ORA-04007이 나오는 줄은 고치기 전 결과다.

- Oracle `OracleSequenceManager`는 ALTER SEQUENCE에 늘 MINVALUE·MAXVALUE·CACHE를 다시 적는다. 한 번도 쓰지 않은 시퀀스는 Oracle 23ai에서
  ORA-04007("MINVALUE cannot exceed the current value")로 실패한다. → 바꾼 절만 적는다.
- PostgreSQL `Partition by`는 저장된 테이블에서도 `updatable`이라 잘못된 `ALTER TABLE … PARTITION BY …`를 만든다(오류로 끝나고 되돌림).
  → 저장된 테이블에서 읽기 전용(파티션의 범위도 같음).

## 다음 작업

- 여러 값을 한 번에 저장하는 방식(SWT Save처럼 모아 두기)은 사용자가 원하면 Object Details 속성 탭에만 둘 수 있다.
- `openEntityEditor`(B-E12)는 아직 로그만 남긴다. Object Details를 여는 것으로 이을 수 있다.

## 변경 이력

- 2026-11-08 최초 작성: Properties 창과 Object Details 속성 탭의 속성 편집(SWT 감사 B-E1, B-E2, C-15, `chromite/object-properties-edit`)
- 2026-11-08 알려진 문제 두 가지(Oracle ALTER SEQUENCE, PostgreSQL Partition by)를 고쳤다고 적음(`chromite/model-ddl-fixes`)
- 2026-11-15 PostgreSQL 테이블스페이스: 이름 바꾸기(F2), 소유자·옵션 속성 편집(앱 변경 #129, `chromite/pg-mysql-storage`). 위 "PostgreSQL 테이블스페이스"
- 2026-11-25 "한 번만 읽기": Object Explorer는 선택 노드나 그 객체가 바뀔 때만 Properties 창에 넘김. 연결 새로 고침 한 번에 같은 표를 8번 읽던 것을 1번으로
  (`chromite/properties-read-once`). 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: Properties 창과 Activity Monitor 단추의 선택 따라가기, 새로 고침·DDL·노드 추가 뒤의
  선택 유지, Altibase 계열 작업 실행 뒤 다시 보이기.
- 2026-11-25 "새로 고침 뒤의 값": 고른 노드나 그 위 노드를 새로 고치면 객체가 같아도 속성을 한 번 다시 읽음(`ObjectExplorerPanel.selectionRefreshed`). 둘째 세션에서
  주석을 바꾸고 펼친 노드의 F5·폴더·스키마·연결 새로 고침 뒤 값을 세 빌드에서 잼(`chromite/properties-read-once`). 회귀 테스트는 실행하지 않았다.
