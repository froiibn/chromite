# Swing 결과 그리드 열 필터 (머리글 필터·정렬 아이콘, 열 필터 팝업)

## 목적

사용자 요청(2026-11-07): "쿼리결과의 필터 입력 항목을 제거하고 그리드의 각 컬럼에 필터 아이콘을 배치하여 컬럼별 필터를 설정할 수 있도록 수정한다."

- 결과 뷰어 위의 WHERE 입력 줄(`ResultSetFilterBar`)을 없앤다.
- 열 머리글마다 DataGrip 결과 그리드처럼 열 아이콘, 이름, 오른쪽 끝에 필터(깔때기)·정렬 아이콘을 둔다. 깔때기를 누르면 그 열의 필터 팝업이 열린다.
- 필터는 예전 필터 줄처럼 서버에서 건다(쿼리를 다시 읽는다). 읽어 둔 행만 거르지 않는다.

참고 화면: 사용자가 준 DataGrip 결과 그리드 캡처(`D:\Tools\_briefs\ref\datagrip-results-grid.webp`, `datagrip-main.png`, 저장소에 넣지 않음).

## 구조

```
ResultSetViewerPanel (결과 하나)
 ├ JSplitPane
 │  ├ ResultSetGrid
 │  │   └ JTableHeader ← ResultSetHeaderRenderer (열 아이콘 | 이름 … 깔때기 정렬)
 │  └ ResultSetValuePanel
 └ 상태 줄: 행 수, Fetch next/all, [깔때기 "필터: 열1, 열2" ✕] … 선택 집계, [값 패널] [레코드]

깔때기 클릭 → ResultSetColumnFilterPopup (꾸밈 없는 모달리스 JDialog)
  ├ 열 이름·형식, 값 검색
  ├ 값 목록: [ ] NULL 12 / [ ] 값 34 …  모두 선택 · 모두 선택 해제 · "값 N개" 또는 "가장 많은 값 1,000개 · 더 읽기"
  ├ 조건: [선택한 값 | = | ≠ | 포함 | 다음으로 시작 | 다음으로 끝남 | LIKE | > | ≥ | < | ≤ | 범위 | IS NULL | IS NOT NULL] [값] (~ [값])
  └ 모든 필터 지우기 ………… [열 필터 지우기] [적용]
값 목록 읽기 → SQLQueryColumnValues (모델, com.seizia.chromite.model.sql.data)
```

| 클래스·파일 | 새/변경 | 역할 |
|---|---|---|
| `ResultSetHeaderRenderer` (ui.swing) | 새 | Look and Feel 머리글 렌더러를 감싸 열 아이콘을 넣고, 안쪽 테두리로 오른쪽 끝에 깔때기·정렬 아이콘을 그린다. 누른 자리(이름·필터·정렬) 계산, 마우스 올림 바탕 |
| `ResultSetColumnFilterPopup` (ui.swing) | 새 | 열 필터 팝업: 값 목록(서버), 조건, 적용·지우기. 열 제약(`DBDAttributeConstraint`)을 만든다 |
| `SQLQueryColumnValues` (model.sql) | 새 | 결과 쿼리를 감싼 `GROUP BY`로 열의 서로 다른 값과 행 수를 읽는다(다른 열 필터·검색어 반영, 저장점) |
| `GridFilterMessages` (+ `_ko`) | 새 | 이 기능의 화면 문구 |
| `SQLSemanticProcessor` (model) | 변경 | 드라이버가 결과 열의 테이블 이름을 주지 않을 때(SQL Server) 바인딩이 묶은 테이블로 필터를 원래 SELECT에 넣음(아래 "SQL Server 열 필터") |
| `ResultSetViewerPanel` | 변경 | 필터 줄 제거, 머리글 설치(클릭 자리별 동작, 도구 설명), 상태 줄의 필터 요약, 값 패널·레코드 단추를 상태 줄 오른쪽으로, 모든 필터 지우기 |
| `ResultSetFilterBar` | 삭제 | WHERE 입력 줄과 기록 |
| `EditorMessages` (+ properties, `_ko`) | 변경 | 필터 줄 문구 `editor_filter_*` 5개 삭제 |
| `UiSizes`, `ChromiteLightLaf/DarkLaf.properties` | 변경 | `Chromite.size.filterBar.padding` 삭제, `Chromite.size.gridHeader.action`(16)·`gap`(4), 색 `Chromite.Table.headerIconForeground`·`headerIconActiveForeground` 추가 |
| `QueryResultsView` | 변경(주석) | 결과 최소 높이 주석에서 필터 줄을 뺌(값 220은 그대로, 아래 "남긴 것") |

`ResultSetGrid`는 고치지 않았다(다른 작업과 겹치는 파일). 머리글 렌더러는 뷰어가 표를 끼울 때 바꾸고, 처음 열 너비가 아이콘 몫보다 좁으면
뷰어가 넓힌다(최대 320, 그리드의 처음 너비 상한과 같음).

## 설계 결정과 이유

### 머리글

- **배치:** 열 아이콘(16) – 이름 – 깔때기(16) – 정렬(16). DataGrip 캡처와 같은 순서다. 아이콘은 늘 보이고 흐린 색(`text.secondary`)이며,
  필터가 걸린 열의 깔때기는 강조색으로 채워 그린다. 정렬한 열의 화살표도 강조색이다(정렬 방향이 보여야 하므로 정렬 아이콘이 상태를 겸한다).
- **그리는 방법:** 렌더러의 테두리 안쪽에 오른쪽 여백을 잡는 테두리를 겹쳐 그 자리에 아이콘을 그린다. Look and Feel의 머리글 테두리(구분선)는 그대로라
  모양이 바뀌지 않고, 열이 좁아지면 이름만 "…"로 준다. 열 너비 맞춤(`ResultSetColumnFit`)도 아이콘 몫을 잰다.
- **아이콘을 Java2D로 그린 이유:** 상태(평소, 필터 있음, 정렬 방향, 마우스 올림, 끔)마다 색이 바뀌어야 하는데 아이콘 팩 SVG는 색이 파일에 들어 있다.
  16 격자 1px 선(0.5 좌표)으로 그려 팩 아이콘과 굵기를 맞췄다. 열 아이콘은 팩의 `column`, 기본 키 열은 `column-pk`(결과의 행 식별자가 기본 키이고 그 열이 키에 들 때).
  DataGrip도 머리글에 자료형 대신 열 아이콘을 쓴다(design-spec "아이콘 요구 사항").
- **누르기:** 깔때기 → 팝업. 정렬 아이콘과 이름 자리 → 정렬(오름차순 → 내림차순 → 없음, Ctrl은 정렬에 더함). 예전 머리글 클릭 동작을 그대로 두어 익숙한 동작이
  바뀌지 않게 했다. 머리글 오른쪽 클릭 메뉴(정렬, 너비 맞춤)와 경계 두 번 누르기(너비 맞춤)도 그대로다.
- **도구 설명:** 이름 자리 "열 이름 (형식)", 깔때기 "열 필터" 또는 "필터: <SQL 조건>", 쿼리를 다시 읽을 수 없으면 그 이유, 정렬 아이콘은 다음 동작.
- 쿼리를 다시 읽을 수 없는 결과(GO 배치, 여러 문장, 프로시저 호출)와 다시 읽는 중에는 깔때기를 흐리게(40%) 그리고 누르지 못한다.

### 필터 팝업

- **서버에서 필터:** 팝업은 뷰어의 `DBDDataFilter` 사본에서 그 열의 제약만 바꿔 넘기고, 뷰어는 예전 필터 줄과 같은 경로(`requery` → `DBSDataContainer.readData`,
  방언의 `addFiltersToQuery`)로 첫 행부터 다시 읽는다. 저장하지 않은 편집이 있으면 먼저 버릴지 묻는다(SWT `checkForChanges`).
- **열마다 조건 하나:** 모델의 열 제약이 하나라 조건 콤보의 첫 항목 "선택한 값"이면 값 목록이, 다른 연산자면 값 칸이 필터가 된다. 확인란을 누르면 콤보가
  "선택한 값"으로 돌아간다. 열 사이는 AND다.
- **값 목록 의미:** 고른 값만 남긴다(`IN`). DataGrip 열 필터와 같다. 아무것도 고르지 않으면 이 열의 필터가 없다. 목록을 다 읽었고 모든 값을 골랐으면 같은 결과이므로
  필터를 두지 않는다. NULL은 따로 맨 위에 두고, 고르면 모델이 `IS NULL OR 열 IN (…)`을 만든다. 1,000개보다 많이 고를 수 없다(Oracle `IN` 목록 한도).
- **조건:**

  | 형식 | 연산자 |
  |---|---|
  | 모든 형식 | =, ≠(열의 값 처리기가 지원하면, SWT 필터 메뉴와 같은 기준), IS NULL, IS NOT NULL(늘) |
  | 문자열 | 포함, 다음으로 시작, 다음으로 끝남, LIKE(직접 쓴 패턴). 값 처리기가 LIKE를 지원할 때 |
  | 숫자·날짜 | >, ≥, <, ≤, 범위(BETWEEN). 값 처리기가 > 또는 <를 지원할 때 모두 |

  값 처리기의 지원 목록을 그대로 쓰지 않은 이유: JDBC 숫자·날짜 처리기는 `>`와 `<`만, 대부분의 처리기는 NULL 검사를 목록에 두지 않는다(2026-11-07 SQL Server
  `decimal`·`nvarchar` 열에서 확인). 비교할 수 있는 열이면 ≥, ≤, BETWEEN도 SQL 표준이고, NULL 검사는 어느 열에나 된다.

- **SQL 주입 방지:** 사용자가 쓴 글자는 그대로 SQL에 들어가지 않는다.
  - =, ≠, >, ≥, <, ≤, LIKE, IN은 모델 연산자로 넣고, 모델이 값 처리기와 방언으로 리터럴을 만든다(`SQLUtils.convertValueToSQL`, 문자열은 방언의 `getQuotedString`:
    작은따옴표 두 번, MySQL은 역슬래시도, SQL Server는 `N'…'`).
  - 값은 먼저 열의 값 처리기로 만든다(`getValueFromObject`, 셀 편집기와 같음). 숫자·날짜가 아니면 팝업에 "값이 올바르지 않습니다"를 보이고 적용하지 않는다.
  - 모델 연산자로 나타낼 수 없는 것은 같은 함수로만 조건 글자를 만든다: 포함·시작·끝은 `LIKE <방언 따옴표 패턴> ESCAPE '!'`(PostgreSQL처럼 열이 `ILIKE`를
    지원하면 대소문자 무시), 범위는 `BETWEEN <리터럴> AND <리터럴>`. 모델의 `BETWEEN` 연산자는 두 자리에 같은 값을 넣으므로(`StandardSQLDialectQueryGenerator.getConstraintCondition`) 쓰지 않았다.
  - 포함·시작·끝은 `%`, `_`, `[`(SQL Server 글자 묶음), `!`를 `!`로 이스케이프해 글자 그대로 찾는다. `ESCAPE`는 SQL 표준이라 PostgreSQL·SQL Server·MySQL·Oracle 모두 쓴다.
- **팝업 상태 보관:** 고른 연산자와 입력한 글자는 열 제약의 옵션(`chromite.columnFilter`)에 둔다. 포함·시작·끝·범위는 조건 글자만으로 다시 알아낼 수 없기 때문이다.
  옵션은 SQL에 쓰이지 않고(`DBDAttributeConstraintBase.isDirty`에만 쓰임) 다시 읽을 때 열 이름으로 옮긴다. 오른쪽 클릭 "Filter by Value"나 열 필터 지우기는 옵션도 지운다.
  옵션이 없으면 `IN` 값 배열(확인란), 단일 연산자(값 칸)로 보인다.
- **창:** 꾸밈 없는 모달리스 `JDialog`(테두리 `PopupMenu.border`, 바탕 `PopupMenu.background`). `JPopupMenu` 안의 콤보는 목록을 고르는 순간 메뉴가 닫히는 문제가 있어서다.
  앱 안의 다른 곳을 누르거나 Esc로 닫히고(다른 앱으로 갔다 와도 남음, 고른 값을 잃지 않게), Enter는 적용이다. 같은 열의 깔때기를 다시 누르면 닫힌다(포커스를 잃어 닫힌 직후의 클릭으로 다시 열지 않음). 한 번에 하나만 연다.
  머리글 칸 아래 오른쪽 끝에 맞춰 열고, 화면 아래가 모자라면 위로 연다.

### 값 목록을 서버에서 읽는 방법 (`SQLQueryColumnValues`)

- SWT 값 목록(`GenericFilterValueEdit.loadAttributeEnum`, 2026-11-07 삭제 전 `plugins/…ui.editors.data/…/valuefilter/GenericFilterValueEdit.java:335`)은
  테이블 열의 `DBSAttributeEnumerable.getValueEnumeration`(`JDBCTableColumn.java:180`, `SELECT 열, count(*) FROM 테이블 GROUP BY 열`)을 쓴다.
  이것은 테이블 전체의 값이라 쿼리의 WHERE·조인·다른 열 필터를 따르지 않아, 고르면 0행이 되는 값이 섞이고 계산 열·별칭 열에는 쓸 수 없다.
- 그래서 **지금 결과의 쿼리를 감싸 묶는다**(SWT 그룹 패널 `GroupingResultsContainer`가 쓰는 모델 `SQLGroupingQueryGenerator` 재사용):
  1. 다른 열의 조건(이 열의 조건은 뺌)과 검색어(이 열의 `LIKE`/`ILIKE` 제약, 값은 방언 따옴표)를 결과를 다시 읽을 때와 같은 `addFiltersToQuery`로 쿼리에 넣는다.
     엑셀 자동 필터처럼 다른 열의 필터를 따른 값만 보인다.
  2. 바깥 `ORDER BY`를 JSQLParser로 지운다(TOP·LIMIT·OFFSET·FETCH가 없을 때). SQL Server가 파생 테이블 안의 ORDER BY를 거부하기 때문이다(`SQLQueryTransformerCount`와 같은 처리).
  3. `SELECT src.열, COUNT(*) FROM (쿼리) src GROUP BY src.열 ORDER BY count DESC`(CTE는 생성기가 쿼리 안에 넣음). 행이 많은 값부터 1,000개 + 1행을 읽어 더 있는지 안다.
  4. 새 `SQLQueryDataContainer`로 결과와 같은 실행 문맥·스크립트 문맥에서 `FLAG_REFRESH`로 읽는다. 쿼리 매개변수를 다시 묻지 않고, 행 수 제한은 방언 경로(`DBUtils.makeStatement`)다.
     값은 열의 값 처리기로 읽으므로(`fetchValueObject`) 그대로 필터 값(`IN`)이 된다.
- **같은 실행 문맥인 이유:** 임시 테이블, 세션 설정, 현재 DB가 결과와 같아야 한다. **수동 커밋 문맥에서는 저장점**을 두고 실패하면 저장점까지만 되돌리고, 성공하면 저장점을 놓는다.
  PostgreSQL은 문장 하나가 실패하면 열린 트랜잭션 전체를 못 쓰게 만들기 때문이다. 사용자 트랜잭션을 커밋하거나 되돌리지 않는다(트랜잭션 안전 규칙: 모드·연결은 바꾸지 않음).
- **화면 순서:** 값 순서(문자열은 대소문자 무시, 그 밖은 `DBUtils.compareDataValues`), NULL은 맨 위. 표시 글자는 그리드와 같은 `DBDDisplayFormat.UI`.
- **검색:** 문자열 열은 서버에서 다시 읽는다(입력 뒤 300ms). 다른 형식은 읽은 값의 표시 글자에서 찾는다. 고른 값은 검색과 관계없이 남는다.
- **더 읽기:** 1,000개를 넘으면 "가장 많은 값 1,000개 · 더 읽기", 누르면 1,000개씩 늘려 다시 읽는다.
- **값 목록을 보이지 않는 경우:** LOB·이진·구조·배열 형식(묶거나 같음 비교를 못 함), 이름이 같은 열이 있는 결과(바깥 쿼리에서 가리킬 수 없음), 쿼리가 아닌 결과. 이유를 보이고 조건만 쓴다.
  쿼리를 감쌀 수 없어 실패하면(예: SQL Server에서 이름 없는 열) 오류를 목록 아래에 보이고 조건은 그대로 쓸 수 있다.
- 읽기는 결과의 실행 문맥에서 `DataSourceJob`으로 하고, 팝업을 닫거나 다시 검색하면 앞의 읽기를 취소한다.

### 필터 요약

- 필터가 있을 때만 상태 줄(행 수 옆)에 강조색 깔때기와 "필터: 열1, 열2", ✕(모든 필터 지우기)를 보인다. 도구 설명은 전체 SQL 조건이다.
- 줄을 새로 두지 않은 이유: 필터 줄을 없앤 자리에 다시 줄을 두면 결과 영역이 그만큼 줄고, 상태 줄은 이미 "(필터됨)" 행 수를 보이는 곳이라 함께 읽힌다.
  (2026-11-08: 폭이 모자랄 때만 상태 줄이 두 줄로 늘어난다, 아래 "정렬과 쿼리의 ORDER BY".)
  어느 열에 필터가 있는지는 머리글의 강조색 깔때기로도 보인다.
- 그리드 오른쪽 클릭의 "Filter by Value"와 "Clear Filter"는 그대로 두었다(Clear Filter = 모든 필터 지우기).

### 필터 줄과 함께 없앤 것

- `ResultSetFilterBar`(WHERE 입력, 쿼리별 기록 20개), 문구 `editor_filter_hint`·`editor_filter_apply`·`editor_filter_apply_tip`·`editor_filter_clear_tip`·`editor_filter_not_available`
  (영어·한국어), 치수 키 `Chromite.size.filterBar.padding`(`UiSizes.FILTER_BAR_PADDING`), 뷰어의 `applyWhere`·`getQueryText`(기록 키),
  다시 읽을 때 `DBDDataFilter.setWhere`를 옮기던 줄(이제 WHERE 글자를 넣는 곳이 없음). `filter` 아이콘은 개체 탐색기 필터가 계속 쓴다.
- 값 패널·레코드 단추는 필터 줄 오른쪽에 있었으므로 상태 줄 오른쪽(선택 집계 뒤)으로 옮겼다. F7과 오른쪽 클릭 메뉴는 그대로다.

## upstream 대비 변경점

- upstream(SWT) 결과 뷰어의 필터는 필터 줄(WHERE 글자, `ResultSetFilterPanel`)과 열 머리글 메뉴의 값 필터(`FilterValueEditPopup`)다. Chromite Swing은 WHERE 줄을 없애고
  열마다 머리글 팝업으로만 건다. 값 목록은 테이블 열 열거 대신 지금 쿼리를 감싸 읽는다(위 이유).
- 모델에 `SQLQueryColumnValues`(새 Chromite 파일)를 더했다.
- upstream 모델 파일 `SQLSemanticProcessor`를 고쳤다(아래 "SQL Server 열 필터").

### SQL Server 열 필터 (모델 수정, 근본 원인)

- 증상: SQL Server에서 `SELECT * FROM dbo.chr_colfilter_items ORDER BY id` 결과에 열 필터를 걸면 "The ORDER BY clause is invalid in views, inline functions,
  derived tables…"로 다시 읽기가 실패했다. 예전 그리드 오른쪽 클릭 "Filter by Value"도 같았다(WHERE 글자 필터 줄은 열 조건이 없어 이 경로를 타지 않았다).
- 원인(근거: EDT 에이전트로 결과 바인딩을 읽음): SQL Server JDBC 드라이버는 결과 열의 테이블 이름을 주지 않는다(`getMetaAttribute().getEntityName()` = 빈 글자,
  `getEntityMetaData()` = null). 결과 바인딩은 쿼리에서 테이블을 찾아 열을 묶었지만(`getEntityAttribute()` = `id`, `name` …),
  `SQLSemanticProcessor.getConstraintTable`·`isValidTableColumn`은 드라이버의 테이블 이름만 보고 "열의 테이블을 모름"으로 판단해 WHERE를 넣지 않고 쿼리를 감쌌다
  (`SELECT * FROM (원래 쿼리) z_q WHERE …`). SQL Server는 파생 테이블 안의 ORDER BY를 거부한다.
- 수정: 드라이버 테이블 이름이 없으면 바인딩이 묶은 테이블 열의 테이블을 쓰고, 그 열 이름이 결과 열 이름과 같을 때만(별칭이 아닐 때, upstream #6927의 뜻) 원래 SELECT에 WHERE를 넣는다.
  PostgreSQL처럼 드라이버가 테이블 이름을 주는 경우는 그대로다.
- ~~남은 것: 별칭·계산 열에 필터를 걸면 여전히 감싸므로 SQL Server에서 원래 쿼리에 ORDER BY가 있으면 실패한다.~~ 2026-11-08 고침: 감쌀 때 ORDER BY를
  바깥으로 옮긴다(아래 "정렬과 쿼리의 ORDER BY").
- ~~정렬 관찰(바꾸지 않음): 원래 쿼리에 ORDER BY가 있으면 머리글 정렬은 그 뒤에 덧붙는다.~~ 2026-11-08 고침: 머리글 정렬이 쿼리의 ORDER BY를 바꾼다(아래).

## 정렬과 쿼리의 ORDER BY (2026-11-08)

후속 과제 #73(SQL Server 별칭·계산 열 필터)과 #75(머리글 정렬이 ORDER BY 뒤에 붙어 순서가 그대로)다.

### 증상과 근본 원인

- **#73:** SQL Server에서 `SELECT id, name, price * qty AS total FROM … ORDER BY id DESC` 결과의 `total`에 필터를 걸면 "The ORDER BY clause is invalid in views,
  inline functions, derived tables…"(오류 1033)로 다시 읽기가 실패했다. 계산 열·별칭 열은 WHERE에 넣을 수 없어 모델이 쿼리를 감싸는데
  (`StandardSQLDialectQueryGenerator.getWrappedFilterQuery`: `SELECT * FROM (원래 쿼리) z_q WHERE …`), 원래 쿼리의 ORDER BY가 파생 테이블 안에 남았다.
  SQL Server는 TOP·OFFSET·FOR XML 없는 파생 테이블의 ORDER BY를 거부한다. PostgreSQL·Oracle은 받아들이지만 바깥 결과의 순서는 SQL이 보장하지 않는다.
  같은 원인으로 실패하던 다른 경로: 설정 "필터를 늘 하위 쿼리로"(`SQL_FILTER_FORCE_SUBSELECT`), UNION 쿼리의 필터(평범한 SELECT가 아니라 늘 감쌈).
- **#75:** 머리글 정렬은 `SQLSemanticProcessor.addOrderByClause`가 쿼리의 ORDER BY **뒤에** 덧붙였다(upstream). `ORDER BY id` 결과를 `name`으로 정렬하면
  `ORDER BY id, "name"`이 되어 보이는 순서가 바뀌지 않았다. 쿼리를 감싸는 경로도 안쪽 ORDER BY를 남기고 바깥에 정렬을 붙였다.

### 결정과 설계

- **머리글 정렬은 쿼리의 ORDER BY를 바꾼다**(주 에이전트 결정). 그 결과를 다시 읽는 동안에만 바꾸고, 쿼리 글자(편집기)는 그대로다. 정렬을 없애면 쿼리의 순서로 돌아간다.
- **정렬은 쿼리가 돌려주는 행을 바꾸지 않는다.** ORDER BY가 행을 고르는 쿼리(TOP·LIMIT·OFFSET·FETCH, PostgreSQL `DISTINCT ON`,
  `SQLSemanticProcessor.isOrderDecidingRows`)는 ORDER BY를 바꾸면 다른 행이 나온다. 그래서 그 ORDER BY는 그대로 두고 쿼리를 감싸 바깥에서 정렬한다
  (`SELECT * FROM (SELECT TOP 300 … ORDER BY id) z_q ORDER BY z_q.price DESC`, `getQueryWithAppliedFilters`). 필터 조건은 예전처럼 쿼리 안에 넣는다
  (정렬 여부에 따라 필터가 거르는 행이 달라지지 않게). WITH 절이 있는 쿼리는 SQL Server가 파생 테이블로 받지 않아 예전처럼 ORDER BY 뒤에 덧붙인다(`isSortedOutside`).
- **감쌀 때 ORDER BY를 바깥으로 옮긴다**(`SQLSemanticProcessor.moveOrderByOutside`): 안쪽 쿼리에서 ORDER BY를 지우고, 각 항목을 파생 테이블의 결과 열 참조로 바꿔
  바깥에 둔다. 이름은 SQL과 같은 순서로 찾는다: 결과 별칭 → 고른 열(`p.id`처럼 테이블 이름·별칭이 붙으면 그 테이블의 열) → `*`·`t.*`의 열 → 별칭이 있는 같은 식 → 열 번호(`ORDER BY 2`).
  UNION은 ORDER BY가 이미 결과 열 이름이라 그대로 옮긴다. 머리글 정렬이 있으면 옮기지 않고 지운다(정렬이 바꾸므로).
  ORDER BY가 행을 고르면 안쪽에 남긴다(TOP·OFFSET이 있으면 SQL Server도 받음).
- **결과에 없는 것으로 정렬하는 ORDER BY**(`ORDER BY name`인데 `name`을 고르지 않음, 2026-11-08 evening #23): 정렬 값을 안쪽 SELECT의 숨은 열
  (`name AS z_o1`)로 더하고 바깥은 그 열로 정렬한다. 숨은 열이 결과에 보이지 않도록 바깥 열 목록을 `SELECT *` 대신 원래 결과 열로 쓴다
  (`SELECT z_q.id, z_q.total FROM (SELECT id, price * qty AS total, name AS z_o1 FROM …) z_q WHERE total > 3000.0 ORDER BY z_q.z_o1`,
  `SQLSemanticProcessor.WrappedQuery.outerColumns`). `TOP 100 PERCENT`나 `OFFSET 0 ROWS`로 안쪽 ORDER BY를 살리는 방법은 SQL Server가 받지만
  바깥 결과의 순서를 보장하지 않아 쓰지 않았다. 바깥 열 목록을 만들 수 없는 쿼리(UNION, DISTINCT, `*`·`t.*`, 별칭 없는 식)와 SQL을 읽지 못하는 쿼리는
  예전처럼 ORDER BY를 안쪽에 남긴다. DISTINCT는 숨은 열이 행을 바꾸고, 이름 없는 식은 SQL Server 파생 테이블이 어차피 받지 않는다(오류 8155).
- **상태 줄 정렬 요약:** 정렬이 있으면 필터 요약 옆에 강조색 정렬 화살표와 "정렬: name ↓ ✕"를 보인다. 서버에서 다시 읽은 정렬이 쿼리의 ORDER BY를 바꿨으면
  "정렬: name ↓ · ORDER BY 대신"이고 도구 설명이 바뀐 ORDER BY를 보인다("… 쿼리의 ORDER BY id 대신 이 열로 정렬해 다시 읽었습니다…"). 표 안 정렬(모두 읽은 결과)과 행을 고르는
  쿼리의 정렬은 "정렬: …"만 보이고 도구 설명이 그 방식(표 안에서 / 다시 읽음)을 말한다. ✕("쿼리 순서로 되돌리기")는 정렬을 없애고 쿼리를 그 순서로 다시 읽는다
  (머리글 오른쪽 클릭의 "Clear Sorting"과 같은 동작). 바꾼 ORDER BY는 모델의 `SQLSemanticProcessor.getReplaceableOrderBy`로 알아낸다(UI는 SQL을 직접 파싱하지 않음).
  문구는 짧게 했다("· replaces ORDER BY").
- **상태 줄이 넘칠 때:** 상태 줄 왼쪽(행 수, Fetch next·Fetch all, 필터 요약, 정렬 요약)은 `FlowLayout`이라 폭이 모자라면 다음 줄로 넘기지만, `FlowLayout`의 선호 높이는
  한 줄이라 넘긴 줄이 상태 줄 밖에서 가려졌다(오른쪽에 도구 창이 있는 1500px 창에서 필터와 정렬을 함께 걸면 정렬 요약이 사라짐, 캡처로 확인).
  필터 요약도 같은 원인으로 가려질 수 있었다. 그래서 줄 수를 세는 흐름 배치(`ResultSetViewerPanel.WrapFlowLayout`)로 바꿔, 넘칠 때만 상태 줄이 두 줄이 된다.
  평소(한 줄에 들어갈 때)는 높이가 그대로다.

### 바뀐 파일

| 파일 | 바뀐 것 |
|---|---|
| `SQLSemanticProcessor` (model) | `addOrderByClause`가 ORDER BY를 바꿈(행을 고르는 ORDER BY는 그대로), `isOrderDecidingRows`·`isSortedOutside`·`getReplaceableOrderBy`·`moveOrderByOutside` |
| `StandardSQLDialectQueryGenerator` (model) | `getWrappedFilterQuery`가 ORDER BY를 바깥으로 옮김, `getQueryWithAppliedFilters`가 행을 고르는 쿼리의 정렬을 감싸서 함 |
| `ResultSetViewerPanel` | 상태 줄 정렬 요약과 ✕ |
| `ResultSetHeaderRenderer` | 강조색 정렬 화살표 아이콘(`createActiveSortIcon`) |
| `GridFilterMessages` (+ `_ko`) | `gridfilter_sort_*` 6개 |

### 확인 (2026-11-08)

빌드한 Swing 제품(`-vm` JBR 25, `CHROMITE_DATA`와 작업 공간은 scratch)에 EDT 에이전트를 붙여 머리글·팝업·상태 줄 단추를 눌렀다(OS 입력 없음).
DB마다 `chr_results_plan`(시험 사용자 `chr_results_plan`)에 `chr_rpf_items` 500행(`price` = id × 1.5, 한 번에 읽는 행 200)을 만들었다. "읽은 쿼리"는 뷰어의 필터로 방언이 만든 SQL이다.

| 확인 | SQL Server 2022 | PostgreSQL 17 | Oracle 23ai |
|---|---|---|---|
| #73 `… price * qty AS total … ORDER BY id DESC`, `total > 3000` | 69행, id 496·495·494…(내림차순 유지). 읽은 쿼리 `SELECT * FROM ( … ) z_q WHERE total > 3000.0 ORDER BY z_q.id DESC`. 고치기 전 감싸기를 `sqlcmd`로 실행하면 오류 1033 | 같음(69행, 같은 순서) | 같음 |
| 별칭 `name AS item_name … ORDER BY item_name DESC`, 시작 `Item 4` | 100행, Item 499부터(테이블 열이라 WHERE에 넣음) | 같음 | - |
| `p.price * 2 AS dbl … ORDER BY p.id DESC`, `dbl < 100` | 33행, `ORDER BY z_q.id DESC` | - | - |
| UNION … `ORDER BY qty DESC, name`, `qty < 3` | 8행, `ORDER BY z_q.qty DESC, z_q.name` | - | - |
| #75 `… ORDER BY id`, 머리글 `name` 내림차순 | 읽은 쿼리 `ORDER BY name DESC`, Item 499부터. 요약 "Sort: name ↓ · replaces ORDER BY", 도구 설명에 `ORDER BY id` | 같음(오름차순 → 내림차순 → 없음 = id 1부터) | 같음 |
| ✕ 쿼리 순서로 되돌리기 | id 1, 2, 3…, 요약 사라짐 | 같음 | 같음 |
| 행을 고르는 쿼리 `TOP 300` / `LIMIT 300` / `FETCH FIRST 300 ROWS ONLY` + `price` 내림차순 | 첫 행 id 300(그 300행 안에서 정렬, 고치기 전 방식이면 id 500). `SELECT * FROM (SELECT TOP 300 … ORDER BY id) z_q ORDER BY z_q.price DESC` | 같음 | 같음 |
| 위 + 필터 `qty = 3` | 조건은 안쪽 WHERE(정렬 없을 때와 같음), 72행 | - | - |
| 결과에 없는 열로 정렬 `… ORDER BY name`(name 안 고름) + 계산 열 필터 | 오류 1033(남긴 것). 2026-11-08 고침, 아래 "확인 (2026-11-08, evening #23)" | - | - |
| 필터 + 정렬을 함께(계산 열 `total > 1000`, `name` 정렬, 1500px 창에 오른쪽 속성 창) | 감싼 쿼리 `… z_q WHERE total > 1000.0 ORDER BY z_q.name`, 요약 "Sort: name ↑ · replaces ORDER BY" | 한국어: 상태 줄이 두 줄(높이 25 → 38), "정렬: name ↑ · ORDER BY 대신", 도구 설명 "쿼리의 ORDER BY id DESC 대신 …". ✕ 뒤 한 줄(25)로 돌아오고 id 500부터. 라이트·다크 | - |

캡처: `D:\Tools\_scratch\chromite-results-plan-fixes\run\shots\`(`en-light-*`, `en-dark-*`, `ko-light-*`, `ko-dark-*`, `ms-*`, `pg-*`, `ora-*`).

- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 그리드 서버 정렬·필터·다음 페이지, Export Data…의 필터·정렬 전달(같은 생성기),
  열 필터 값 목록(감싸기 경로, 정렬 없음), "필터를 늘 하위 쿼리로" 설정.

### 확인 (2026-11-08, evening #23)

`chromite/followups-3` 빌드, 작업 공간 사본과 별도 데이터 폴더, 프로세스 안 드라이버(OS 입력 없음). SQL Server 2022의 `chr_followups_3` DB에 `fu3_items` 500행
(`price` = id × 1.5, `qty` = id % 7 + 1).

| 확인 | 결과 |
|---|---|
| 고치기 전 감싸기 `SELECT * FROM (… ORDER BY name) z_q WHERE total > 3000.0`(sqlcmd) | 오류 1033 |
| 한국어: `SELECT id, price * qty AS total … ORDER BY name DESC`, `total` > 3000 | 69행, id 496·495·494·489·488…(name 내림차순 유지). 읽은 쿼리 `SELECT z_q.id, z_q.total FROM ( SELECT id, price * qty AS total, name AS z_o1 FROM dbo.fu3_items ) z_q WHERE total > 3000.0 ORDER BY z_q.z_o1 DESC`, 결과 열은 id·total 둘 |
| 영어: `SELECT p.id, p.price * 2 AS dbl … ORDER BY p.name, p.qty DESC`, `dbl` < 100 | 33행, id 1·2·3…. 숨은 열 둘(`p.name AS z_o1, p.qty AS z_o2`), `ORDER BY z_q.z_o1, z_q.z_o2 DESC`, "33 rows (filtered)" |

캡처: `D:\Tools\_scratch\chromite-followups-3\shots\ms-23-ko.png`, `ms-23-en.png`.

## 확인 (2026-11-07)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → `BUILD SUCCESS`, `java tools/jre/BundleJre.java windows-x64`.
  번들 JRE로 `-vm` 없이 실행. `python tools/rename/gate.py` → `ALL GATES PASSED`.
- 실행: 빌드한 Swing 제품을 별도 작업 공간으로 열고 EDT 에이전트(제품에 들어가지 않음)로 머리글·팝업을 눌렀다. 화면은 창 캡처(PrintWindow).
  로컬 Docker DB(SQL Server 2022, PostgreSQL 17)에 `chr_grid_column_filters` DB와 `chr_colfilter_items` 1,000행(글자·한글·`%`·`_`·작은따옴표, 숫자, 날짜, 날짜 시간,
  불리언, NULL)을 만들었다. 한 번에 읽는 행 수는 200이다. 기대 행 수는 같은 조건을 `psql`로 세어 맞췄다.

  | 확인 | DB·언어 | 결과 |
  |---|---|---|
  | 머리글 아이콘·도구 설명(이름 "category (nvarchar(30))", 깔때기, 정렬 다음 동작) | SQL Server 한국어 | 맞음 |
  | 값 목록: NULL 111 + 값 5개와 행 수, 다른 열 필터를 따른 목록(name 288개) | SQL Server | 맞음 |
  | 값 고르기 `음료`+NULL → `category IS NULL OR category IN (N'음료')`, 289행(읽은 200행 + 더 있음) | SQL Server | 맞음 |
  | 두 열: + name 포함 `100%`(글자 `%`) → `LIKE N'%100!%%' ESCAPE '!'`, 22행 | SQL Server | 맞음 |
  | 서버 필터 증명: 값 검색 `0951` → 읽은 200행 밖의 `Item 0951` 1개, 적용하면 id 951 한 행 | SQL Server | 맞음 |
  | 한글 검색 `상품 05` → 14개, 모두 선택 → 14행 | SQL Server, PostgreSQL | 맞음 |
  | `>` 400 → 134, 범위 100–200 → 201(더 있음), IS NULL → 76, `=` 7 → 17, `abc` → "값이 올바르지 않습니다: abc" | SQL Server | 맞음 |
  | 열 필터 지우기(다른 열 유지), 모든 필터 지우기(팝업 링크, 상태 줄 ✕) | SQL Server, PostgreSQL | 맞음 |
  | 날짜 값 목록 120개(날마다 8–9행), `≥ 2026-04-01` → 240(더 있음), + `Books` → 42 | PostgreSQL 영어 | 맞음 |
  | 다음으로 시작 `Item 09` → 76, 다음으로 끝남 `0007` → 1, LIKE `한글%` → 140, 포함 `item_`(글자 `_`, ILIKE) → 77 | PostgreSQL | 맞음 |
  | `=` `O'Brien's` → `'O''Brien''s'`(오류 없음), `≠` 7 → 983(더 있음), `<` 날짜 시간 → 39, IS NOT NULL → 924, `≤` 0.5 → 2, 불리언 `false` → 333 | PostgreSQL | 맞음 |
  | 정렬 아이콘: 오름차순 → 내림차순 → 없음(서버 ORDER BY, NULL 위치는 DB대로), 이름 자리 클릭도 정렬 | SQL Server, PostgreSQL | 맞음 |
  | 다시 읽을 수 없는 결과(`DECLARE …` + `SELECT`): 깔때기 흐림, 도구 설명 "필터를 쓸 수 없음: 단일 SELECT 쿼리가 아님", 팝업 안 열림 | SQL Server | 맞음 |
  | 라이트·다크 테마의 머리글(흐린 아이콘, 강조색 깔때기·화살표), 팝업, 상태 줄 요약 | 둘 다 | 맞음 |

- 캡처: `D:\Tools\_scratch\grid-column-filters\run\shots\`의 `ms-ko-{light,dark}-*`, `pg-en-{light,dark}-*`(`-0`은 팝업 창, `-1`은 주 창).
  에이전트가 그린 합성 캡처(`shotmain`)는 `column-pk` 아이콘의 SVG 마스크가 깨져 보이지만 실제 화면(창 캡처)은 정상이다.
- 확인하지 못한 것: 수동 커밋 연결에서 값 목록 읽기 실패 시 저장점 되돌리기(코드 경로만 확인), MySQL·Oracle, macOS, 1,000개가 넘는 값의 "더 읽기"(가장 많은 값 목록 자체는 925개 열에서 확인).
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 그리드 정렬·다음 페이지, 오른쪽 클릭 Filter by Value, Export Data…의 필터 전달,
  SQL Server 결과의 필터·서버 정렬(모델 수정), 값 패널·레코드 단추(위치 이동).

## 남긴 것

- 결과 창 기본 높이(280)에서는 결과 표의 최소 높이(220)가 커서 뷰어 상태 줄(행 수, 필터 요약)이 바깥 스크롤 밑에 있다(전부터 그랬음, 필터 줄 34px가 빠져 조금 나아짐).
  결과 창을 키우면 보인다. 필터가 걸린 열은 머리글의 강조색 깔때기로도 보인다.
  (2026-11-08 다시 확인: 지금 기본 배치에서는 결과 영역이 224로 220보다 커서 상태 줄이 보인다. 고치지 않음,
  [swing-followup-fixes.md](swing-followup-fixes.md) "결과 상태 줄과 결과 영역 높이")

- DataGrip 캡처에 있는 것 중 이번 요청 밖: 그리드 아래 가운데 떠 있는 페이지 이동 알약(`1-500 ▾ 501+`), 결과 도구 막대의 "Tx: 자동" 표시. 요청하면 만든다.
- 열마다 조건 둘 이상(예: `> 10 AND < 20` 대신 범위로 됨, `OR` 조합), 열 사이 OR. ~~저장한 필터, 필터 기록~~ 2026-11-08 함
  ([swing-saved-filters-virtual-columns.md](swing-saved-filters-virtual-columns.md): 저장한 필터, 테이블별 최근 필터).
- 값 목록의 행 수 정렬 전환(지금은 값 순서), 대소문자 구분 선택.
- ~~결과에 없는 값으로 정렬하는 쿼리(`SELECT id, price * qty AS total … ORDER BY name`)에 계산 열·별칭 열 필터를 걸면 SQL Server에서 여전히 오류 1033이다.~~
  2026-11-08 고침(evening #23): 정렬 값을 숨은 열로 더하고 바깥 열 목록을 따로 쓴다(위 "결정과 설계"). 남은 경우: UNION·DISTINCT·`*`가 든 쿼리가
  결과에 없는 것으로 정렬하면 바깥 열 목록을 만들 수 없어 예전처럼 SQL Server에서 오류 1033이다.
- 행을 고르는 쿼리(TOP·LIMIT)에 필터를 걸면 조건이 쿼리 안에 들어가 "조건에 맞는 행 중 처음 N개"가 된다(upstream 그대로, 정렬해도 같음). "보이는 N개 중 거르기"로 바꿀지는 정하지 않았다.
- 같은 "ORDER BY가 행을 고름" 판단이 값 목록(`SQLQueryColumnValues.removeOrdering`, `DISTINCT ON`을 보지 않음)과 행 수 세기(`SQLQueryTransformerCount`, LIMIT이 있어도 ORDER BY를 지움)에도 따로 있다.
  두 곳 모두 세는 데에만 쓰여 결과 순서에는 영향이 없어 고치지 않았다.

## 변경 이력

- 2026-11-07 최초 작성: 결과 필터 줄 삭제, 열 머리글 필터·정렬 아이콘, 열 필터 팝업(서버 값 목록, 조건), 상태 줄 필터 요약,
  SQL Server 열 필터가 ORDER BY 쿼리에서 실패하던 모델 문제 수정.
- 2026-11-08 "남긴 것"의 결과 영역 높이를 다시 확인함(지금 기본 배치에서는 생기지 않음).
- 2026-11-08 정렬과 쿼리의 ORDER BY(#73, #75): 감싼 필터 쿼리의 ORDER BY를 바깥으로 옮김, 머리글 정렬이 쿼리의 ORDER BY를 바꿈(행을 고르는 쿼리는 감싸서 정렬),
  상태 줄 정렬 요약과 쿼리 순서로 되돌리기.
- 2026-11-08 결과에 없는 것으로 정렬하는 쿼리의 계산 열·별칭 열 필터: 정렬 값을 숨은 열로 더하고 바깥 열 목록을 따로 씀(SQL Server 오류 1033, evening #23,
  `chromite/followups-3`).
- 2026-11-08 "남긴 것"의 저장한 필터·필터 기록을 지움([swing-saved-filters-virtual-columns.md](swing-saved-filters-virtual-columns.md)). 가상(계산) 열은
  머리글 깔때기가 꺼진다(같은 문서 "가상 열").
- 2026-11-08 Query Library가 값을 바인딩한 결과에서도 열 필터와 값 목록이 됨: `SQLQueryColumnValues`가 결과 컨테이너의 바인딩 방법을 넘겨받음
  ([swing-query-library.md](swing-query-library.md) "매개변수 대화상자와 바인딩 하나로").
