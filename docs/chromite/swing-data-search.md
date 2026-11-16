# Swing 데이터 검색 (Search Data: 여러 테이블에서 값 찾기)

## 목적

SWT에는 Search 대화상자의 "Search data" 페이지(`ui.search`의 `SearchDataPage`, `SearchDataQuery`)가 있었다. 값 하나를 고른 테이블이나
스키마의 모든 테이블 행에서 찾고, 찾은 테이블을 결과 목록에 보였다. SWT를 지운 뒤 Swing에는 이 기능이 없었다
([swt-parity/C-navigator-tools.md](swt-parity/C-navigator-tools.md) #29). 2026-11-08 두 번째 작업 묶음(X2, 우선순위 4)으로 옮겼다.

요구 사항(브리프):

- 스키마의 모든 테이블이나 고른 테이블에서 값을 찾는다.
- 모델의 검색 기능이 있으면 쓰고, 없으면 테이블마다 안전한 읽기 전용 매개 변수 쿼리를 만든다. 테이블마다 행 수 제한, 취소 가능,
  사용자의 트랜잭션에 닿지 않는 별도 자동 커밋 컨텍스트.
- 결과는 테이블·열·맞는 행을 보이고, 행을 결과 그리드에서 필터와 함께 연다.

## 구조

```
Object Explorer 오른쪽 클릭 ▸ Search Data…  (스키마·카탈로그·"Tables" 폴더 하나, 또는 같은 부모의 테이블 여러 개)
  → QueryWindows.openToolDocument("data-search:<연결 id>:<범위 이름>")
  → DataSearchPanel (문서 탭)
       ├ 찾을 값 [________] [검색/중지]
       ├ 옵션: [ ] 대/소문자 구분  [x] 숫자  [ ] 긴 텍스트 열  테이블당 행 수 [100]
       ├ 범위: <스키마> (<연결>)
       ├ 왼쪽: 테이블 목록(체크, 이름, 종류; 뷰는 기본 해제) [모두] [없음]
       ├ 오른쪽: 결과(테이블 | 열 | 행 | 값)  ── 두 번 클릭·Enter·오른쪽 클릭 "맞는 행 열기"
       └ 상태 줄(진행, 결과 요약, 문제 목록)
  검색 작업(AbstractJob) → DataSearch (model.admin, 테이블마다 search)
  행 열기 → ObjectScripts.generateFilteredSelect → 새 쿼리 창에서 실행
```

| 클래스·파일 | 새/변경 | 역할 |
|---|---|---|
| `DataSearch` (model.admin, `com.seizia.chromite.model.admin.search`) | 새 | 조건 고르기, 매개 변수 SELECT 만들기·바인딩·실행, 열마다 세기, 자기 자동 커밋 컨텍스트 |
| `model.admin` `MANIFEST.MF` | 변경 | `com.seizia.chromite.model.admin.search` 내보내기 |
| `DataSearchPanel` (ui.swing) | 새 | 문서 탭: 범위(`findScope`), 테이블 목록, 옵션, 검색 작업, 결과, 행 열기 |
| `DataSearchMessages` (+ `.properties`, `_ko`) | 새 | 이 기능의 화면 문구(키 접두사 `datasearch_`) |
| `ObjectScripts` | 변경 | `generateFilteredSelect`: Select와 같은 SELECT에 모델 조건 생성기의 WHERE와 행 수 제한 |
| `ObjectExplorerPanel` | 변경 | "Search Data…" 메뉴 항목(Find Objects 아래), 켜기 조건 |

## 설계 결정과 이유

### 모델의 검색 기능을 쓰는 방법

모델에는 데이터 검색 전용 API가 없다. SWT `SearchDataQuery`는 테이블마다 `DBDDataFilter`(열마다 조건, `setAnyConstraint(true)`로 OR)를 만들고
`DBSDataContainer#readData`로 읽었다. `readData`(JDBC는 `JDBCTable.readData`)는 `SQLUtils.appendQueryConditions` → 쿼리 생성기의
`appendConditionString(..., inlineCriteria=true)`로 **값을 SQL 문자열에 넣는다**(`StandardSQLDialectQueryGenerator.java:57-66`). 따옴표는 방언이
처리하지만 브리프는 매개 변수 쿼리를 요구한다. 그래서:

- 조건 고르기는 SWT 규칙을 그대로 쓴다: 문자열(`STRING`, 옵션이 켜지면 `CONTENT`)은 `LIKE '%값%'`, 대소문자를 무시하면 열이 ILIKE를 지원할 때 ILIKE,
  LIKE가 없으면 `=`; 숫자 열은 값이 숫자일 때 `=`; 형식 이름이 UUID인 열은 값이 UUID일 때 `=`; 불리언은 건너뜀. 연산자 지원은
  `DBUtils.getAttributeOperators`, 숨은·의사 열은 뺀다. 외부 테이블(`DBPForeignObject`)은 SWT 기본값처럼 건너뛴다.
- SQL은 같은 쿼리 생성기를 `inlineCriteria=false`로 불러 만든다(`SQLUtils.appendConditionString`). 조건마다 `?` 하나(방언의 형 변환 포함,
  PostgreSQL의 JSON·XML 같은 형식)가 생기고, 값은 열의 값 처리기 `DBDValueHandler#bindValueObject`로 바인딩한다.
  SELECT 목록과 FROM은 모델이 따옴표 처리한 이름(`DBUtils.getObjectFullName(..., DML)`)이다. 사용자 입력은 SQL 글자가 되지 않는다.
- 이진 열(`BINARY`)은 찾지 않는다. SWT는 "LOB 찾기"를 켜면 문자열 `%값%`을 이진 열에도 바인딩했는데, 값 처리기가 문자열을 바이트로 바꾸는 방식이 드라이버마다
  달라 결과를 믿을 수 없다.

### 숫자 값

JDBC 숫자 처리기는 열 형식에 맞춰 값을 줄인다(`JDBCNumberValueHandler.bindParameter`: INTEGER 열은 `setInt(number.intValue())`).
그대로 바인딩하면 `3.5`가 INTEGER 열의 `3`과 맞는다. 그래서 `DataSearch.numberFor`가 값을 정한다: 정수 형식(TINYINT~BIGINT, BIT)은 long에 들어가는 정수일
때만, NUMERIC·DECIMAL·FLOAT·DOUBLE·REAL은 정수면 long, 아니면 BigDecimal, 그 밖의 형식은 소수 자리(scale)가 있을 때만 소수 값을 넣는다.

### 행 수 제한과 취소

- 테이블마다 `DBCStatement#setLimit(0, 행 수 + 1)`(JDBC `setMaxRows`)로 읽고, 읽기 반복도 행 수에서 멈춘다. 하나 더 읽히면 "100+"로 보인다.
- 취소: 작업 취소(`AbstractJob#canceling`)가 모니터의 블록을 취소하고, JDBC 문장은 실행 중에 블록으로 걸려 있으므로(`JDBCStatementImpl.startBlock`)
  `Statement#cancel`이 불린다. 읽기 반복과 테이블 반복도 모니터를 본다.

### 트랜잭션 안전

- 검색은 자기 격리 컨텍스트(`DBSInstance#openIsolatedContext`, 이름 "Chromite data search")를 열고, 수동 커밋이면 자동 커밋으로 바꾼 뒤(자기 컨텍스트라
  끝나는 사용자 트랜잭션이 없다) 검색이 끝나면 닫는다. Activity Monitor(`AdminConnection`)와 같은 이유다: 수동 커밋이면 첫 SELECT가 연 트랜잭션이
  남고, PostgreSQL은 그 트랜잭션의 스냅숏을 계속 본다.
- 인스턴스는 범위 객체의 인스턴스(`DBUtils.getObjectOwnerInstance`)다. PostgreSQL은 데이터베이스마다 인스턴스가 있어 기본 인스턴스로 열면 다른 데이터베이스의
  테이블을 읽을 수 없다. 그래서 여러 테이블은 같은 부모(스키마)일 때만 범위로 받는다.
- "단일 연결"(드라이버가 연결 하나만 허용, SQL Server MFA 인증)이면 사용자의 기본 컨텍스트밖에 없다. 그 컨텍스트가 자동 커밋이면 그대로 쓰고,
  수동 커밋이면 `DataSearch.SharedTransactionException`으로 거절하고 "자동 커밋으로 바꾼 뒤 다시 검색하라"고 알린다. 사용자의 트랜잭션 안에서 읽지 않고,
  커밋 방식도 바꾸지 않는다(트랜잭션 안전 규칙).
- 결과에서 연 쿼리 창은 사용자의 쿼리 창이므로 그 창의 트랜잭션 규칙을 따른다(검색 컨텍스트와 상관없음).
- 다른 세션이 커밋하지 않은 행은 보이지 않는다. PostgreSQL은 그 행을 건너뛰고 찾는다. SQL Server(기본 READ COMMITTED, 읽기 커밋 스냅숏 꺼짐)는
  그 행의 잠금이 풀릴 때까지 기다린다. 상태 줄에 "customers 찾는 중"이 머물면 중지를 누르면 된다. 잠금 시간 제한(`SET LOCK_TIMEOUT`)이나
  `NOLOCK`은 넣지 않았다: 앞의 것은 연결 설정마다 다르게 정해야 하고, 뒤의 것은 커밋하지 않은 값을 결과로 보인다.
- 숫자 검색은 플래그 열(`BIT`, `BOOLEAN`, SQL Server `bit`은 숫자 종류)을 건너뛴다. SWT도 불리언을 건너뛰었다.

### 어느 열이 맞았는지

쿼리는 테이블 하나에 한 번(열 조건을 OR)이다. DB가 돌려준 행의 각 열 값을 같은 규칙으로 다시 비교해 열마다 센다: LIKE 패턴을 정규식으로 바꿔
(`%` → 임의 글자, `_` → 한 글자, 대소문자 무시) 맞추고, 숫자는 BigDecimal로, UUID·`=`는 대소문자 무시 문자열로 비교한다. CLOB은 64K 글자까지 읽는다.
DB의 데이터 정렬(악센트 무시 등) 때문에 어느 열로도 설명되지 않는 행은 "(기타)" 결과로 세고, 이 결과를 열면 그 테이블의 모든 열 조건(OR)으로 연다.
열이 하나뿐인 쿼리는 DB가 맞다고 한 행을 그 열의 행으로 센다.

### 한 열의 실패

OR 쿼리가 실패하면(한 열 형식이 LIKE를 받지 않는 등) 열마다 따로 다시 찾고, 실패한 열만 "테이블.열: 오류"로 상태 줄 문제 목록에 남긴다.
SWT는 그 테이블 전체를 로그에만 남기고 건너뛰었다. 테이블 단위 오류(권한 없음 등)도 문제 목록에 남기고 다음 테이블로 간다.

### 범위

- Object Explorer에서 연다(SWT도 탐색기에서 고른 노드로 범위를 채웠다). 메뉴는 Find Objects 바로 아래다.
- 스키마·카탈로그·"Tables" 폴더 하나(`DataTransferObjects.isDataContainerParent`, 형식만 봄) → 탭을 열 때 그 안의 테이블과 뷰를 읽는다
  (`DataTransferObjects.readDataContainers`). 뷰는 기본으로 체크하지 않는다(뷰 쿼리가 무겁고 같은 행이 원본 테이블에서 나온다).
- 같은 부모의 테이블 여러 개(또는 하나) → 그 테이블만, 모두 체크(뷰도 사용자가 고른 것이므로 체크).
- 데이터베이스 노드(스키마를 담는 노드)와 연결 노드는 받지 않는다. 모든 스키마를 한 번에 훑으면 운영 DB에 부담이 크다.
- 같은 연결·같은 범위 이름의 탭이 열려 있으면 그 탭을 보인다.

### 행 열기

결과를 두 번 누르거나 Enter, 오른쪽 클릭 "맞는 행 열기" → `ObjectScripts.generateFilteredSelect`가 Select Top과 같은 SELECT(`SQLGeneratorSelect`)에
`WHERE <그 열의 조건>`(모델 조건 생성기, 값은 글자로 들어감)과 행 수 제한(Select Top과 같은 1000행, 방언의 `TOP`/`LIMIT`)을 붙이고,
새 쿼리 창에서 바로 실행한다. 쿼리 창은 평범한 SQL을 실행하므로 여기서는 값이 SQL 안에 들어간다. 사용자가 보는 SQL이고, 따옴표는 방언이 처리한다.

### 와일드카드

입력의 `%`와 `_`는 SWT처럼 LIKE 와일드카드로 남는다(자리표시자 글에 적음). 이스케이프 문법(`ESCAPE`)이 방언마다 달라서다.

## upstream(SWT) 대비 변경점

| SWT | Chromite Swing |
|---|---|
| Search 대화상자 페이지 + Search 보기 | Object Explorer 메뉴 + 문서 탭 |
| `readData`로 값을 SQL에 넣음 | 같은 조건 생성기로 `?`를 만들고 값 처리기로 바인딩 |
| 연결의 유틸리티 컨텍스트(`DBUtils.openUtilSession`) | 자기 격리 자동 커밋 컨텍스트, 단일 연결 수동 커밋은 거절 |
| 테이블별 행 수만 | 테이블·열별 행 수와 첫 값, "(기타)" |
| 이진 열도 찾음(LOB 옵션) | 이진 열은 찾지 않음 |
| 테이블 하나 실패 → 건너뜀(로그) | 열마다 다시 찾고 실패한 열만 보고 |
| 결과에서 테이블 편집기를 필터와 함께 엶 | 조건을 넣은 SELECT를 새 쿼리 창에서 실행 |
| "인덱스가 있는 열만"(fast search) | 없음(아래 "남긴 것") |

## 남긴 것

- SWT의 "인덱스가 있는 열만 찾기"(fast search)와 "외부 테이블도 찾기" 옵션은 옮기지 않았다. 외부 테이블은 늘 건너뛴다.
- 여러 연결이나 데이터베이스 전체를 한 번에 찾지 않는다.
- 결과에서 연 쿼리 창의 SQL에는 값이 글자로 들어간다(쿼리 창이 평범한 SQL을 실행하므로).

## 확인

2026-11-08, PostgreSQL 17과 SQL Server 2022 연습 스키마(`chr_data_search.ds`, 수동 커밋 연결)에서 앱(한국어·영어, 밝은·어두운 테마)으로 확인했다.

- 문자열(대소문자 무시·구분, `_` 와일드카드, 따옴표가 든 값, 한글), 숫자(`42`, `3.5`; 정수 열에 `3.5`가 맞지 않음), UUID, 행 수 제한("100+"),
  긴 텍스트 열, 스키마·"Tables" 폴더·테이블 하나 범위, 데이터베이스 노드에서 메뉴 꺼짐.
- 매개 변수: PostgreSQL 서버 로그에 `... WHERE ("name" ILIKE $1) OR (email ILIKE $2) ...`, SQL Server 계획 캐시에
  `(@P0 bigint,@P1 nvarchar(4000),...)SELECT ... WHERE (id = @P0) OR (name LIKE @P1) ...`.
- 트랜잭션: 쿼리 창에서 커밋하지 않은 INSERT를 둔 채 그 값을 찾으면 PostgreSQL은 찾지 못하고(다른 세션), 검색 뒤에도 쿼리 창 세션은
  `idle in transaction`으로 남았다. 검색 세션("Chromite data search")은 초기화 뒤 `BEGIN` 없이(자동 커밋) SELECT만 실행하고 끝나면 닫혔다.
  SQL Server는 그 행의 잠금에서 기다렸고 중지로 끝났다.
- 취소: `pg_sleep(30)` 뷰를 찾다가 중지하면 서버 로그에 `canceling statement due to user request`, 상태 줄 "중지했습니다".
- 행 열기: PostgreSQL `SELECT id, "label" FROM ds.big WHERE "label" ILIKE '%needle%' LIMIT 1000;`(500행),
  SQL Server `SELECT TOP 1000 ... WHERE name LIKE N'%o''brien%';`(1행).

"단일 연결" 수동 커밋 거절 경로는 이 환경에 그런 드라이버가 없어 코드로만 확인했다. 회귀 테스트는 만들지 않았다(요청 없음).

## 변경 이력

- 2026-11-08 최초 작성: Search Data… 문서 탭, `DataSearch` 모델, 매개 변수 쿼리, 자기 자동 커밋 컨텍스트, 결과에서 행 열기 (`chromite/data-search`)
