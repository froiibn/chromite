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

## upstream 대비 변경점

- SWT `EntityEditor`의 Properties 탭(편집·Save·Revert·View Script)을 Properties 창과 Object Details 속성 탭의 바로 저장으로 바꿨다.
  미리 보기는 SWT에서 설정에 따라 선택이었지만 여기서는 늘 보인다(삭제·이름 바꾸기와 같음).
- SWT 속성 창은 엔터티 편집기 안에서만 편집되었다. Swing은 탐색기 선택의 Properties 창에서도 고친다.

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
