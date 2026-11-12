# 스키마 비교 (Schema Compare)

## 목적

두 스키마(또는 데이터베이스)를 같은 연결이나 다른 연결에서 비교하고, 고른 차이를 대상에 반영하는 마이그레이션 스크립트를 만든다.
SSMS·Visual Studio의 Schema Compare, DataGrip의 "Compare Content / Migrate"에 해당한다.

- 열기: **Tools > Compare Schemas…**, 또는 Object Explorer 오른쪽 클릭 **Compare Schemas…**(Find Objects 아래). 고른 노드의 연결·데이터베이스·스키마가
  처음 원본이 된다. 명령마다 새 문서 탭을 연다(비교 두 개를 나란히 둘 수 있다).
- 범위: 테이블, 열(형식·NULL 허용·기본값), 기본 키, 외래 키, 인덱스, 뷰, 루틴(함수·프로시저, 정의 글), 트리거, 시퀀스.
- 안전: 스크립트는 대상 연결의 새 쿼리 창에 **열기만 하고 실행하지 않는다.** 대상에서 무언가 지우는 항목(대상에만 있음)은 처음에 꺼져 있다.

## upstream 대비

- DBeaver CE에는 스키마 비교·마이그레이션이 없다. SWT 제품의 `cmp.simple.ui` "Compare/Migrate > Simple compare"(`CompareObjectsWizard`)는
  고른 객체들의 속성 값을 표로 비교해 HTML 보고서로 내보낼 뿐 스크립트를 만들지 않았다(기준 `263b8ddb45`의 `cmp.simple.ui/plugin.xml`).
  스키마 비교와 마이그레이션은 DBeaver 상용판(Liquibase 기반) 기능이다.
- 그래서 Chromite에서 새로 설계했다. 모델 번들은 이미 제품에 들어 있던 `com.seizia.chromite.cmp.simple`(쓰는 곳 없음)에 새 패키지
  `com.seizia.chromite.tools.compare.schema`로 넣었다. 새 번들·피처를 만들지 않아 빌드·피처 구성은 바뀌지 않는다.
- Simple compare의 속성 보고서는 옮기지 않았다(아래 "남은 것").

## 구조

| 위치 | 클래스 | 역할 |
|---|---|---|
| `cmp.simple` (모델, UI 없음) | `SchemaComparer` | 두 컨테이너를 새로 읽고 객체를 짝지어 비교, `SchemaCompareResult`를 만든다 |
| | `SchemaCompareItem` | 차이 하나: 종류(테이블·뷰·루틴·시퀀스, 테이블의 열·기본 키·외래 키·인덱스·트리거), 상태(원본에만·대상에만·다름), 다른 속성, 선택 |
| | `SchemaCompareResult` | 결과: 항목, 같은 객체 수, 비교하지 않은 객체 수, 원본→대상 테이블 짝, 이름 바꾸기 |
| | `SchemaNameMapper` | 원본 정의의 스키마·DB 이름을 대상 이름으로, 다른 종류 DB에서 새 객체의 이름 대소문자, 대상 컨테이너의 정규화된 이름 |
| | `SchemaScriptGenerator` | 고른 항목의 스크립트(실행 동작 목록)와 머리말 주석 |
| | `SchemaCompareMessages` | 스크립트 주석 문구(영어, `_ko` 한국어) |
| `ext.mssql` | `SQLServerTableColumnManager` | 기본값 제약 조건이 있는 열 바꾸기 순서(아래 "SQL Server 기본값 제약 조건") |
| `ui.swing` | `SchemaComparePanel` | 문서 탭(`ToolDocument`): 원본·대상 고르기, 비교, 결과 트리(확인란), 두 DDL 나란히 보기(`TextDiffView`, [text-compare.md](text-compare.md)), Generate Script |
| | `SourceScripts.generateContainerScript` | 스크립트 글: Modify와 같은 구분자(SQL Server `GO`, MySQL `DELIMITER`)와 대상 DB 맞추기(`USE`, 기본 문맥) |
| | `internal/CompareMessages` | 화면 문구(영어, `_ko` 한국어) |
| | `MainFrame`, `ObjectExplorerPanel` | Tools 메뉴와 Object Explorer 메뉴의 Compare Schemas… (`QueryWindows.openToolDocument`로 탭 열기) |

- `ui.swing/META-INF/MANIFEST.MF`: `Require-Bundle`에 `com.seizia.chromite.cmp.simple`을 더했다. `cmp.simple`은 `registry`(→`model`)만 요구하고
  `runtime.feature`에 들어 있다. `cmp.simple/META-INF/MANIFEST.MF`는 새 패키지를 내보낸다.

## 화면

- 위: 원본·대상 줄마다 연결 / 데이터베이스 / 스키마 콤보, 오른쪽에 Compare(비교)·Swap(바꾸기).
  - 단계는 모델 계층(`DBSObjectContainer.getPrimaryChildType`)으로 정한다. 카탈로그만 있는 DB(MySQL)는 스키마 콤보가, 스키마만 있는 DB(Oracle)는
    데이터베이스 콤보가, 둘 다 없는 DB(SQLite)는 둘 다 꺼진다. 시스템 스키마(`pg_catalog`, `sys` 등)는 목록에서 뺀다.
  - 연결을 고르면 연결되지 않은 연결은 연결하고(모달 진행 대화상자, 비밀번호 질문 가능) 목록을 읽는다. 처음 값은 연결의 기본 DB·스키마.
- 가운데: 결과 트리. "원본에만 있음 (n)", "대상에만 있음 (n)", "다름 (n)" 세 묶음. 다른 테이블 아래에 열·키·인덱스·트리거의 차이가 있고, 묶음과 다른 상태의 자식은
  "원본에만 있음"·"대상에만 있음"이라고 붙인다. 항목 글: `열 name — 형식: 원본 varchar(100), 대상 varchar(50); NULL 허용: 원본 NOT NULL, 대상 NULL`.
  - 확인란(누르기 또는 Space)으로 스크립트에 넣을 항목을 고른다. 테이블·묶음의 확인란은 아래 항목을 모두 켜거나 끈다.
  - 대상에만 있는 항목은 처음에 꺼져 있고, 도구 설명이 "체크하면 DROP 문이 들어가고 실행하면 데이터를 잃는다"고 알린다.
- 아래: 고른 항목의 원본·대상 DDL을 나란히 보인다(열·키·트리거는 그 테이블). 다른 줄을 칠하고(원본 초록, 대상 빨강, 반투명) 첫 다른 줄로 옮긴다.
  같은 종류 DB면 원본 DDL의 스키마 이름을 대상 이름으로 바꿔 보인다(머리글에 표시). 보기는 텍스트 비교와 함께 쓰는 `TextDiffView`다([text-compare.md](text-compare.md)):
  줄 앞뒤 공백을 무시한 Myers 줄 비교(EDT 밖), 좌우를 맞춘 빈 행, 바뀐 낱말의 진한 표시, 스크롤 맞춤, 오른쪽 개요 띠. 2026-11-12 전에는 줄 단위 최장 공통 부분열(LCS)이었고
  줄 수의 곱이 4백만을 넘으면 같은 위치끼리 비교했다.
- 아래 상태 줄: `차이 n개, 같은 객체 m개`(다른 종류 DB면 비교하지 않은 정의 수도), 오른쪽 **Generate Script(스크립트 만들기)**.

## 비교 (`SchemaComparer`)

- 읽기 전에 두 컨테이너의 탐색기 노드를 새로 고친다(Object Explorer의 F5와 같다). 캐시된 옛 메타데이터와 비교하지 않기 위해서다.
- 객체 모으기: 컨테이너의 탐색기 노드 아래 폴더를 세 단계까지 내려가며 항목을 모으고 모델 인터페이스로 나눈다
  (`DBSSequence` → 시퀀스, `DBSView` 또는 `DBSTable.isView()` → 뷰, `DBSTable` → 테이블, `DBSProcedure` → 루틴). 시스템·숨은 객체는 뺀다.
  시퀀스를 먼저 본다. PostgreSQL 시퀀스는 테이블이기도 하다(`PostgreSequence extends PostgreTableBase`).
  - 이유: 시퀀스·루틴을 돌려주는 메서드는 드라이버마다 이름이 다르고 공통 인터페이스가 없다. 탐색기 구조는 모든 드라이버가 `plugin.xml`로 갖고 있어
    같은 코드로 100개가 넘는 드라이버를 다룬다. 탐색기 필터(연결의 객체 필터)도 Object Explorer와 같이 적용된다.
  - 테이블의 열·키·외래 키·인덱스·트리거는 모델 API(`getAttributes`, `getConstraints`, `getAssociations`, `getIndexes`, `getTriggers`)로 읽는다(테이블 디자이너와 같다).
- 짝짓기: 같은 이름, 남은 것끼리 대소문자 무시. 오버로드된 루틴은 인자 형식을 붙인 이름(`DBPOverloadedObject`, PostgreSQL `fn_total(int4)`).
  외래 키는 정의(`열 → 참조 테이블(열)`)로 먼저, 인덱스는 이름 다음에 정의(UNIQUE, 열)로 짝짓는다(자동 이름이 DB마다 다르다). 기본 키는 열만 비교한다.
- 열: 형식은 "원본 열로 대상에 만들 형식"(데이터 옮기기와 같은 `DBStructUtils.mapTargetDataType`)과 대상 형식을 공백·대소문자 없이 비교한다.
  같은 종류 DB는 원본 형식 그대로다. `decimal`은 `numeric`으로 본다(SQL 표준의 같은 형식). 다른 종류 DB에서 이름이 달라도 두 이름을 대상의 형식 목록에서 찾아
  JDBC 형식과 데이터 종류가 같고 괄호 안(길이·정밀도)이 같으면 같게 본다. 바꾼 형식이 대상 형식의 다른 이름인 경우다
  (SQL Server `int` → PostgreSQL `int`와 대상 `int4`, 시퀀스 기본값이 붙은 `serial4`). 그 대신 같은 JDBC 형식으로 보고되는 형식(PostgreSQL `varchar`와 `text`)도 같게 본다.
  NULL 허용과 기본값도 비교한다. 기본값은 바깥 괄호(SQL Server `((0))`), PostgreSQL 형 변환(`::text`), `N'…'` 접두사, 공백·대소문자를 걷어 비교한다.
  다른 종류 DB에서는 식별 열·자동 생성 열의 기본값과, 원본 기본값이 리터럴(숫자, 문자열)이 아닌 식(`now()`)인 기본값은 비교하지 않는다. 시퀀스 기본값과 IDENTITY는 같은 뜻이고,
  원본의 식은 대상 DB로 옮길 수 없으므로(대상 `getdate()`를 지우는 스크립트가 되므로) 차이로 보이지 않는다.
- 외래 키 규칙: `RESTRICT`와 `NO ACTION`(그리고 알 수 없음)은 같게 본다. DB마다 둘 중 하나로 보고한다.
- 인덱스: 기본 키 인덱스(`isPrimary`)와 이름이 테이블 제약 조건과 같은 인덱스(PK·UNIQUE 제약이 만든 인덱스)는 뺀다.
- 정의(뷰·루틴·트리거)와 시퀀스(증가값·최솟값·최댓값, 현재 값은 데이터라 뺌)는 **같은 종류 DB일 때만** 비교한다.
  시퀀스 값은 정의를 먼저 읽어 불러온다. PostgreSQL은 값을 모니터를 받는 지연 읽기로만 채우고(`PostgreSequence.getAdditionalInfo`) `DBSSequence` 조회는 읽지 않은 0을 돌려준다. 원본 정의의 스키마 이름을 대상 이름으로 바꾸고
  공백을 하나로 모아 비교한다. 다른 종류 DB 사이에는 문법이 달라 비교하지 않고 "비교하지 않은 객체" 수로 센다.
- "같은 종류"는 데이터 소스 클래스가 같은 것이다(`mapTargetDataType`과 같은 기준).

## 스크립트 (`SchemaScriptGenerator`)

- 문장은 될 수 있는 대로 **대상 드라이버의 객체 관리자**가 만든다. 버려지는 명령 묶음(`AbstractCommandContext`)에 명령을 넣고 실행 동작을 글로 읽은 뒤
  `resetChanges(true)`로 모델을 되돌린다. Object Explorer의 Delete·Rename(`ExplorerObjectCommands.prepare`/`discard`)과 테이블 디자이너가 쓰는 길이다.
  그래서 DB마다 다른 문법을 드라이버가 정한다(SQL Server 기본값 제약 조건 다시 만들기, PostgreSQL `ALTER COLUMN … TYPE`, 인자가 붙는 `DROP FUNCTION`,
  SQL Server `DROP INDEX … ON`).
  - 지우기(모든 종류): 관리자의 `deleteObject`.
  - 새 테이블·열 더하기: 관리자의 `createNewObject`로 대상에 만들고 원본 열의 이름·형식·NOT NULL·기본값을 넣는다(데이터 옮기기의 새 테이블과 같은 방법).
  - 열 바꾸기: 대상 열의 속성을 `PropertySourceEditable`로 바꿔 관리자의 변경 명령을 만든다(테이블 디자이너와 같다). 형식 속성은 드라이버마다 달라
    `fullTypeName`(PostgreSQL), `dataType`+`maxLength`·`precision`·`scale`(SQL Server), `typeName` 순서로 편집할 수 있는 것을 쓴다. `required`, `defaultValue`.
- 관리자가 없는 것은 표준 SQL로 만든다: `ALTER TABLE … ADD [CONSTRAINT n] PRIMARY KEY (…)`, `… FOREIGN KEY (…) REFERENCES … (…) [ON DELETE …] [ON UPDATE …]`,
  `CREATE [UNIQUE] INDEX n ON t (…)`(같은 종류 DB에서 원본 인덱스 정의가 있으면 그 정의), `ALTER SEQUENCE … INCREMENT BY/MINVALUE/MAXVALUE`.
  제약 조건 이름은 같은 종류 DB에서만 옮긴다(SQL Server `PK__…` 같은 자동 이름은 다른 DB에서 뜻이 없다).
- 뷰·루틴·트리거·시퀀스 만들기는 같은 종류 DB에서만 원본 정의(View DDL과 같은 `getObjectDefinitionText`)를 대상 이름으로 바꿔 쓴다. 다른 종류 DB로는
  "직접 만드세요" 주석을 남긴다. 바꾸기: 루틴·트리거는 정의가 `CREATE OR REPLACE`/`CREATE OR ALTER`면 그것만, 아니면 지우고 다시 만든다.
  뷰는 늘 지우고 다시 만든다(PostgreSQL은 열이 바뀐 뷰를 REPLACE하지 못한다, 뷰를 지워도 데이터는 잃지 않는다).
- 순서: 지우기(외래 키 → 뷰·루틴·트리거 → 인덱스 → 기본 키 → 열 → 테이블 → 시퀀스) 다음 만들기(시퀀스 → 테이블 → 열 → 기본 키 → 인덱스 → 외래 키 → 뷰·루틴·트리거).
  의존하는 것을 먼저 지우고 가리킬 것을 먼저 만든다.
- 다른 종류 DB의 기본값은 리터럴(숫자, 문자열)만 옮기고, 식(`now()` 등)은 "복사하지 않음" 주석을 남긴다. 이때 대상 열의 기본값은 그대로 둔다.

### SQL Server 기본값 제약 조건

SQL Server는 DEFAULT 제약 조건이 걸린 열을 `ALTER COLUMN`으로 바꾸지 못한다(오류 5074 "The object 'DF__…' is dependent on column", 4922).
시험에서 원본 `varchar(20)` → `nvarchar(20)` 바꾸기 스크립트가 이 오류로 멈췄다. 원인은 드라이버의 열 관리자
(`ext.mssql` `SQLServerTableColumnManager.addObjectModifyActions`)였다. 기본값이 바뀔 때만 제약 조건을 지우고 다시 만들었고, 그것도 `ALTER COLUMN`보다 앞에서 다시 만들었다.
형식·NULL 허용이 바뀌고 열에 기본값 제약 조건이 있으면 제약 조건 지우기 → `ALTER COLUMN` → 기본값(새 값이나 원래 값) 다시 만들기 순서로 고쳤다.
테이블 디자이너도 같은 관리자로 변경 스크립트를 만들므로 기본값이 있는 열의 형식을 바꿀 때 함께 고쳐진다(디자이너 화면에서는 확인하지 않았다).
- 이름: 같은 종류 DB는 원본 이름 그대로, 다른 종류 DB의 새 객체는 대상의 대소문자 규칙(`DBObjectNameCaseTransformer`, 데이터 옮기기와 같다).
  키·인덱스의 열과 외래 키의 참조 테이블은 짝지은 대상 객체의 이름을 쓴다.
- 머리말 주석: 원본, 대상, "실행 전에 검토하세요. 아직 아무것도 실행하지 않았습니다. 지우는 문장은 데이터를 잃을 수 있습니다."
- 쿼리 창: `SourceScripts.generateContainerScript`가 연결의 기본 문맥을 대상 DB·스키마로 맞추고(Modify와 같다) SQL Server는 `USE <db>`로 시작한다.
  `openScript(…, execute=false)`라서 실행은 사용자가 쿼리 창에서 한다. 쿼리 창의 실행 확인(DROP 확인 등)과 트랜잭션 규칙이 그대로 적용된다.

## 설계 결정

| 결정 | 이유 |
|---|---|
| 모델 코드를 `cmp.simple` 번들의 새 패키지에 | 이미 제품에 있는 비교 번들이고 쓰는 곳이 없었다. 새 번들은 피처·빌드 모듈을 바꿔야 한다 |
| 객체 모으기는 탐색기 노드, 테이블 부품은 모델 API | 시퀀스·루틴에 공통 API가 없다. 탐색기 구조는 모든 드라이버에 있다 |
| ALTER는 드라이버의 객체 관리자, 키·인덱스는 표준 SQL | 열 바꾸기 문법은 DB마다 크게 다르고 드라이버에 이미 있다. 키·인덱스 더하기는 SQL 표준이고 관리자로 만들려면 드라이버별 설정(구성 대화상자)이 필요하다 |
| 모델 객체를 잠깐 바꾸고 되돌림(`resetChanges`) | Delete·Rename 미리 보기, 테이블 디자이너와 같은 기존 길. 관리자는 바뀐 객체에서 문장을 만든다 |
| 정의는 같은 종류 DB에서만 비교·복사 | 다른 종류 DB의 정의 변환(T-SQL ↔ PL/pgSQL)은 이 기능 범위 밖이다 |
| DROP 항목은 처음에 꺼 둠, 스크립트는 실행하지 않음 | 사용자 요구(브리프 "Safety"). 데이터를 잃는 문장은 사용자가 일부러 골라야 한다 |
| 뷰는 늘 DROP + CREATE | PostgreSQL `CREATE OR REPLACE VIEW`는 열이 줄거나 형식이 바뀌면 실패한다 |
| 결과는 탭마다(명령마다 새 탭) | 여러 비교를 나란히 둔다. 같은 원본·대상을 다시 비교하려면 Compare를 다시 누른다 |

## 알려진 한계

- 스키마 이름 바꾸기는 이름 뒤에 `.`이 오는 곳을 글자로 바꾼다. 문자열 안이나 스키마와 이름이 같은 별칭(`app_v1.col`)도 바뀔 수 있다. 스크립트는 검토용이다.
- 비교하지 않는 것: UNIQUE·CHECK 제약 조건, 열 주석·정렬 규칙(collation), 테이블 저장 옵션(테이블스페이스, 파티션), 권한, 열 순서, 동의어·패키지·사용자 정의 형식.
- 다른 종류 DB 사이에는 뷰·루틴·트리거·시퀀스의 정의를 비교·복사하지 않는다(있고 없음만 본다).
- 새 테이블의 NOT NULL 열을 데이터가 있는 기존 테이블에 더하면(기본값 없이) DB가 거부할 수 있다. 형식 바꾸기가 데이터를 자를 수 있는지는 검사하지 않는다.
- PostgreSQL처럼 서버가 정의를 정규화해 돌려주는 DB는 서버 버전이 다르면 같은 뷰도 다르게 보일 수 있다.
- 드라이버에 객체 관리자가 없거나 관리자가 그 종류를 만들거나 지우지 못하면 "직접 하세요" 주석을 남긴다.

## 확인 (2026-11-08)

빌드한 Swing 제품(`-vm` JBR 25, 시험 작업 공간·`CHROMITE_DATA`는 작업 폴더)에 같은 프로세스 안에서 도는 시험 코드를 붙여 모델과 화면을 불렀다
(화면 좌표·전면 창 입력 없음, Swing API로 단추·트리·콤보만 다룸). DB: PostgreSQL 17(`chr_schema_compare` DB의 `cmp_src`, `cmp_tgt` 스키마),
SQL Server 2022(`chr_schema_compare` DB의 `cmp_ms` 스키마). 화면 문구는 OS 언어(한국어)로 나왔다.

- **PostgreSQL → PostgreSQL** (원본 `cmp_src`, 대상 `cmp_tgt`, 알고 넣은 차이): 결과 트리
  원본에만(테이블 `products`), 대상에만(테이블 `old_logs`, 뷰 `v_old`), 다름 5개: `customers`(열 `name` 형식 `varchar(100)`/`varchar(50)`·NULL 허용,
  열 `status` 기본값 `'active'`/`'new'`, 열 `legacy_code` 대상에만), `orders`(열 `amount` `numeric(12, 2)`/`numeric(10, 2)`, 외래 키 삭제 시 계단식/작업 안 함,
  인덱스 `ix_orders_customer` 원본에만, `ix_orders_amount` 대상에만, 트리거 `trg_orders_note` 원본에만), 뷰 `v_customer_orders` 정의, 루틴 `fn_total(int4)` 정의,
  시퀀스 `order_seq` 증가값 1/5. 상태 줄 "차이 8개, 같은 객체 2개". DROP 항목 3개는 꺼져 있고 도구 설명이 나온다.
  - `legacy_code`를 체크하고 스크립트 만들기 → 대상 연결(`PG compare`, 기본 스키마 `cmp_tgt`)의 새 쿼리 창에 열리고 실행되지 않았다(결과 탭 비어 있음).
    스크립트: FK 지우기 → 뷰 지우기 → `DROP COLUMN legacy_code` → `ALTER SEQUENCE … INCREMENT BY 1` → `CREATE TABLE products` → `ALTER COLUMN … TYPE`/`SET NOT NULL`/`SET DEFAULT`
    → PK·인덱스·FK(`ON DELETE CASCADE`) → 트리거 → 뷰 → 함수(`CREATE OR REPLACE`). 스키마 이름은 모두 `cmp_tgt`.
  - 그 스크립트를 psql로 대상에 실행(오류 없음)한 뒤 다시 비교: 남은 차이는 체크하지 않은 DROP 3개(`old_logs`, `v_old`, `ix_orders_amount`)뿐, 같은 객체 8개.
  - "대상에만"을 모두 체크한 스크립트에는 `DROP VIEW v_old`, `DROP INDEX cmp_tgt.ix_orders_amount`, `DROP COLUMN legacy_code`, `DROP TABLE old_logs`가 이 순서로 들어간다(뷰가 테이블보다 먼저).
  - DDL 나란히 보기: `v_customer_orders`를 고르면 원본 1줄(`o.amount`), 대상 2줄(`o.amount,`, `o.id`)이 칠해진다.
- **PostgreSQL → SQL Server** (테이블·열): 열 `email`·`status`의 형식(`varchar(200)` → 바꾼 형식 `nvarchar(200)`, 대상 `varchar(200)`), 열 `note`·인덱스·외래 키 원본에만,
  `extra_ms`·`ms_only` 대상에만, 테이블 `products` 원본에만. 뷰·루틴·트리거·시퀀스는 원본에만으로 나오고 스크립트에는 "직접 만드세요" 주석만 들어간다.
  `created_at`(원본 `now()`, 대상 `getdate()`)은 차이로 나오지 않는다. 스크립트는 `USE chr_schema_compare` + `GO` 배치로 쿼리 창에 열리고,
  `status`는 `DROP CONSTRAINT DF__…` → `ALTER COLUMN status nvarchar(20)` → `ADD DEFAULT 'active' FOR status`. sqlcmd로 실행(오류 없음) 뒤 다시 비교하면
  테이블·열·키·인덱스 차이는 없고 남은 것은 다른 종류 DB로 옮기지 않는 정의 객체와 체크하지 않은 DROP뿐이다.
- **SQL Server → PostgreSQL**(Swap 단추로 바꿈): 고치기 전에는 `int`/`int4`, `int`/`serial4`가 형식 차이로 나왔다. 고친 뒤 차이는 `extra_ms`, `ms_only`(원본에만)와 PostgreSQL에만 있는 정의 객체뿐이다.
- Tools > 스키마 비교…와 Object Explorer 메뉴 항목(도움말 글 포함)이 새 탭을 연다. Swap은 원본·대상의 연결·DB·스키마를 서로 바꾼다.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: SQL Server 테이블 디자이너의 열 바꾸기(기본값 있는 열), Modify 스크립트(`SourceScripts`),
  Tools 메뉴·Object Explorer 메뉴.

## 남은 것

- SWT Simple compare(여러 객체의 속성 비교 보고서, HTML 파일 출력)는 옮기지 않았다. 구조 비교와 스크립트는 이 기능이 덮는다.
- UNIQUE·CHECK 제약, 열 주석의 비교와 스크립트.
- 비교 결과 저장·다시 열기, 비교 설정(무시할 종류·이름 규칙).

## 변경 이력

- 2026-11-08 최초 작성 (`chromite/schema-compare`). SQL Server 열 관리자의 기본값 제약 조건 순서 고침(위 "SQL Server 기본값 제약 조건").
- 2026-11-12 DDL 나란히 보기를 텍스트 비교와 함께 쓰는 `TextDiffView`로(앱 변경 #105, `chromite/text-compare`): LCS를 지우고 모델의 Myers `Diff`, 색·줄 앞뒤 공백 무시·첫 차이로 이동은 그대로,
  좌우 맞춤 빈 행·낱말 표시·스크롤 맞춤·개요 띠가 더해짐([text-compare.md](text-compare.md) "스키마 비교와 함께 쓰기").
