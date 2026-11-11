# Swing 결과의 저장한 필터, 최근 필터, 가상(계산) 열

## 목적

SWT 결과 뷰어에 있던 것 중 Swing 결과 뷰어([swing-result-viewer.md](swing-result-viewer.md))에 남아 있던 두 가지를 옮긴다.
SWT 대조 5순위 G13 작업이고 대상 항목은 다음과 같다([swt-parity/B-editors.md](swt-parity/B-editors.md)).

| 항목 | 내용 |
|---|---|
| B-R10 | 저장한 필터(열 필터와 정렬을 이름으로 저장해 고름), 필터 기록 |
| B-R22 | 가상(계산) 열(가상 고유 키·가상 외래 키는 [swing-grid-navigation.md](swing-grid-navigation.md) "가상 키"에서 이미 함) |

둘 다 데이터베이스를 바꾸지 않는다. 연결의 가상 모델(가상 키와 같은 곳, data-sources.json의 가상 메타데이터)에 저장한다.
사용자 규칙 "변경을 실행하는 확인은 Cancel이 기본 단추"와 "묻지 않고 커밋·롤백하지 않음"을 따른다: 설정을 바꾸는 대화상자(필터 저장, 가상 열 추가·고치기)와
지우기 확인의 기본 단추는 Cancel이고, 필터를 적용해 다시 읽기 전에 저장하지 않은 편집이 있으면 버릴지 묻는다(기존 정렬·필터와 같은 확인).

## 구조

Swing 클래스는 모두 `com.seizia.chromite.ui.swing` 번들이다. 새 번들·새 의존, 모델 변경은 없다.

| 클래스 | 새/변경 | 역할 |
|---|---|---|
| `ResultSetSavedFilters` | 새 | 저장한 필터·최근 필터를 가상 모델에 읽고 쓰기, 필터 ↔ JSON 값 변환, 저장·지우기·최근 필터 지우기 대화상자 |
| `ResultSetVirtualColumns` | 새 | 가상 열: 쿼리 결과에 단일 원본 테이블의 가상 열 붙이기, 행마다 값 계산, 서버 필터에서 가상 열 빼기, 추가·고치기·지우기 대화상자 |
| `ResultSetViewerPanel` | 변경 | 오른쪽 클릭 "저장한 필터"·"가상 열" 메뉴, 다시 읽을 때 최근 필터 남기기, 가상 열의 머리글 필터·Filter by Value 끄기, 가상 열 정렬은 표 안에서, 다시 읽는 필터에서 가상 열 빼기 |
| `ResultSetGridReceiver` | 변경 | 가상 열은 결과 집합에서 읽지 않고 행을 읽은 뒤 계산, 쿼리 결과에 가상 열 붙이기, 다음 페이지의 열 수 비교 |
| `ResultSetEditPersister` | 변경 | 저장 뒤 행 다시 읽기: 결과 집합의 열만 맞춰 읽고 가상 열은 다시 계산 |
| `ResultSetGenerateSql` | 변경 | 행에서 만드는 문장(INSERT·UPDATE·DELETE·SELECT·MERGE)에서 가상 열을 뺌 |
| `ResultSetHeaderRenderer` | 변경 | 깔때기를 열마다 끌 수 있게(`isFilterEnabled(int)`) |
| `GridVirtualMessages` (+ `_ko`) | 새 | 이 기능의 화면 문구(키 접두사 `gridvirt_`) |

## 저장한 필터와 최근 필터 (B-R10)

SWT: `ResultSetViewer.saveDataFilter`, `DataFilterRegistry`(작업 공간의 `saved-data-filter.xml`, 테이블마다 기본 필터 하나), `ResultSetFilterPanel`의
필터 기록(쿼리 글자마다 메모리).

### 화면

결과 표 오른쪽 클릭 "필터 지우기" 아래 **저장한 필터** 메뉴:

```
저장한 필터 ▸  지금 필터 저장
               ─────────
               (저장한 필터 이름들 — 누르면 적용, 도구 설명은 조건과 ORDER BY)
               ─────────
               최근 필터 ▸  (최근 조건들 — 누르면 적용) / ─ / 최근 필터 지우기
               저장한 필터 지우기 ▸  (이름들)
```

- 테이블 하나에서 온 결과(테이블 데이터, 그 테이블만 읽는 쿼리)에서만 쓴다. 아니면 메뉴에 "테이블 하나에서 온 결과가 아닙니다"만 보인다.
  필터를 테이블에 묶어야 다른 쿼리·다음 실행에서도 같은 필터를 고를 수 있기 때문이다.
- **지금 필터 저장:** 이름(기본 "필터 N")을 묻고, 이 결과의 열 필터(머리글 팝업·Filter by Value의 조건)와 정렬을 저장한다. 대화상자에 저장할 조건이
  SQL로 보인다. 이름이 같은 필터가 있으면 바꿀지 묻는다(Cancel 기본). 저장할 조건이나 정렬이 없으면 메뉴가 꺼지고 그 이유가 도구 설명에 있다.
- **저장한 필터 적용:** 결과의 조건과 정렬을 그 필터로 바꾸고 서버에서 다시 읽는다(머리글 필터와 같은 경로). 결과에 없는 열의 조건은 빼고
  Messages에 "필터 "이름": 이 결과에 없는 열 x의 조건은 빼고 적용했습니다"를 남긴다.
- **최근 필터:** 조건이 있는 필터로 다시 읽을 때마다 그 조건을 테이블의 최근 필터 맨 위에 남긴다(같은 조건은 위로 올림, 20개까지: 없앤 WHERE 필터 줄의 기록 수와 같음).
  정렬은 남기지 않는다(머리글을 누를 때마다 바뀌어 기록이 정렬로 넘친다). 적용하면 조건만 바꾸고 지금 정렬은 그대로다.
- 다시 읽을 수 없는 결과(여러 문장, 프로시저)에서는 적용 항목이 꺼지고 그 이유가 도구 설명에 있다. 저장·지우기는 된다.

### 저장 형식

가상 모델의 테이블 항목 속성 두 개다(`DBVEntity` 속성은 이미 JSON으로 저장되고 다시 읽히므로 모델을 바꾸지 않았다).

```json
"properties": {
  "chromite.savedFilters": [
    {"name": "필터 1", "text": "category IN (N'음료')  ORDER BY price DESC",
     "constraints": [
       {"column": "category", "operator": "IN", "value": {"list": [{"kind": "string", "text": "음료"}, null]},
        "popup": {"op": "VALUES"}},
       {"column": "price", "order": 1, "descending": true}]}],
  "chromite.recentFilters": [ {"text": "…", "constraints": [ … ]} ]
}
```

- 열 조건 하나: 열 이름, 조건 글자(`criteria`: 포함·시작·끝·범위처럼 팝업이 방언으로 만든 SQL 조각) 또는 연산자·뒤집기·값, 열 필터 팝업의 상태(고른 연산자와 입력한 글자,
  [swing-grid-column-filters.md](swing-grid-column-filters.md) "팝업 상태 보관"), 정렬 위치·방향.
- 값은 JSON에 넣을 수 있게 종류와 글자로 둔다: 글자·불리언은 그대로, 숫자는 정밀도를 잃지 않게 10진 글자, 날짜 같은 그 밖의 값은 열 값 처리기의 편집 형식 글자.
  적용할 때 열 값 처리기로 다시 값을 만든다(셀 편집기·필터 팝업과 같은 변환). 만들 수 없으면 글자 그대로 넘긴다. 값 고르기(IN)는 목록이고 NULL을 담는다.
- 빈 글자는 쓰지 않는다. 설정 파일 직렬화(`JSONUtils.serializeMap`)가 빈 글자를 빼므로, 같은 조건을 지금 만든 것과 파일에서 읽은 것이 같게 비교되어야
  최근 필터의 겹침 판단이 다시 시작한 뒤에도 맞는다.
- 열은 이름으로 찾는다(같은 이름, 없으면 대소문자만 다른 이름). 조건 글자는 저장할 때의 방언으로 만든 것이라 같은 연결에서만 쓴다(가상 모델이 연결의 것이므로 그렇다).

### 결정과 이유

- **SWT 파일을 읽지 않음:** SWT `saved-data-filter.xml`은 테이블마다 이름 없는 기본 필터 하나이고 작업 공간에 있었다. 브리핑대로 연결의 가상 모델에 두면
  연결을 내보내거나 옮길 때 함께 가고, 가상 키와 같은 곳이다. SWT 기본 필터를 열 때 자동 적용하는 동작은 옮기지 않았다(결과가 묻지 않고 걸러져 보이는 것을 피함).
- **적용은 묻지 않음:** 머리글 필터 팝업의 "적용"과 같다. 저장하지 않은 편집이 있으면 결과 뷰어의 기존 확인이 먼저 묻는다.

## 가상(계산) 열 (B-R22)

SWT: `VirtualAttributeAddAction`, `VirtualAttributeEditAction`, `VirtualAttributeDeleteAction`, `EditVirtualAttributePage`(JEXL 식).

### 화면

결과 표 오른쪽 클릭 "가상 키" 아래 **가상 열** 메뉴: 가상 열 추가, 가상 열 고치기 ▸, 가상 열 지우기 ▸(이름 = 식).

- 대화상자: 이름(기본 `vcolumn`, 겹치면 `vcolumn2`…), 형식(글자·숫자·참·거짓·날짜·시각), 식(JEXL), 열 넣기(식에서 쓸 수 있는 이름의 결과 열을 골라 넣음),
  도움말, 고른 행(없으면 첫 행)으로 계산한 미리 보기. 기본 단추는 Cancel이다(연결 설정을 바꾸는 대화상자, 가상 키 대화상자와 같음).
- 형식 "숫자"는 연결에 SQL 표준 `numeric`·`decimal` 형식이 있으면 그 이름으로 저장한다. 연결의 기본 숫자 형식은 PostgreSQL·SQL Server 모두 `int`라
  `price * qty`(10.5) 같은 값의 머리글이 `int`로 보였다(값 표시는 같음).
- 이름은 결과의 열, 테이블의 열, 그 테이블의 다른 가상 모델 항목과 겹치지 않아야 한다(대소문자 무시). 식이 그 이름으로 열을 찾기 때문이다.
- 저장하면 결과를 다시 읽어 열이 오른쪽 끝에 보인다. 다시 읽을 수 없는 결과는 "쿼리를 다시 실행하면 보입니다"를 알린다. 지우기는 확인(Cancel 기본) 뒤에 한다.
- 머리글 이름 자리 도구 설명은 "이름 (형식) — 가상 열: 식"이다.

### 설계

- **저장:** 모델이 하던 대로 결과 테이블의 가상 모델에 `custom` 표시와 식·데이터 종류·형식 이름을 가진 `DBVEntityAttribute`로 넣는다
  (`DBVModelSerializerModern`이 이미 쓰고 읽음). 그래서 SWT에서 만든 가상 열도 그대로 보인다. 형식 이름은 연결의 기본 데이터 형식 이름
  (`DBPDataTypeProvider.getDefaultDataTypeName`, SWT 대화상자와 같음. 숫자는 위 "화면"처럼 `numeric`·`decimal`이 먼저)이고 값 처리기가 그것으로 표시 형식을 정한다.
- **어느 결과에 붙나:** 그 테이블 하나에서 온 결과마다. 모델(`DBUtils.injectAndFilterAttributeBindings`)은 데이터 컨테이너가 테이블일 때만 가상 열을 붙이고,
  쿼리는 쿼리마다 메모리에만 있는 가상 엔터티를 쓴다(`DBVUtils.getVirtualEntity(DBSDataContainer)`). SWT는 추가할 때 단일 원본 테이블의 가상 엔터티에 넣고
  (`ResultSetModel.getVirtualEntity`), 다시 그릴 때는 데이터 컨테이너 기준으로 붙였다(`ResultSetViewer.refreshMetaData`). Chromite는 테이블 하나로 정하고,
  수신기가 열을 테이블에 묶은 뒤(`bindAttributes`) 단일 원본 테이블의 가상 열을 붙인다(`ResultSetVirtualColumns.appendTableColumns`).
- **값 계산:** 결과 집합에 없는 열이라 읽지 않고, 행을 읽은 뒤 모델 `DBUtils.getAttributeValue`(`DBVUtils.executeExpression`, JEXL `DBVDataContext`)로 계산한다.
  식 오류는 그 칸에 문구로, 서로를 가리키는 식(스택 넘침)은 오류 칸이 된다. LOB처럼 읽은 뒤 놓아 주는 값은 식에서 NULL이다.
  - **NULL:** 모델 문맥(`DBVAbstractContext.has`)은 값이 NULL인 열을 정의되지 않은 변수로 보아 JEXL이 "variable 'category' is undefined" 오류를 낸다
    (2026-11-08 SQL Server에서 확인). 그래서 도움말에 `category ?? ''`(JEXL의 NULL 대체)를 적었다. 확인: `name + ' / ' + (category ?? '-')`가 NULL 행에 `Item 299 / -`.
  - **이름이 식별자가 아닌 열:** 식의 변수는 열 레이블로 찾으므로 공백·기호가 든 이름은 쓸 수 없다. 문맥의 `row` 객체(`row['unit price']`)도 모델의
    JEXL 권한(`DBVUtils.parseExpression`: 식 이름공간 클래스만 허용)이 막아 "undefined property"다(2026-11-08 확인). 그래서 열 넣기 목록에서 뺐다.
    모델 권한을 넓히는 것은 보안 경계를 바꾸므로 하지 않았다(아래 "남긴 것").
  - 다음 페이지 행도, 저장 뒤 다시 읽은 행도 다시 계산한다. 칸을 고치는 동안에는 저장해야 다시 계산된다.
- **고칠 수 없음:** 바인딩에 행 식별자가 없어 편집 모델이 읽기 전용 열로 두고(값 패널에서도), 새 행 INSERT와 UPDATE에서 빠진다
  (`ResultSetEditPersister.shouldInsertAttribute`, 모델 `DBDResultSetDataUpdater.isVirtualColumn`). 결과의 다른 열 편집은 그대로다.
- **필터·정렬:** DB에 없는 열이라 머리글 깔때기와 "Filter by Value"를 끄고 이유를 보인다. 정렬은 읽은 행 안에서(표 안 정렬) 하고, 서버에서 다시 읽는 필터
  (다른 열 필터, 다음 페이지, 값 목록)에서는 가상 열의 정렬을 뺀다(`withoutVirtualColumns`). 그래서 다시 읽으면 가상 열 정렬은 풀린다.
  행에서 만드는 SQL(INSERT·UPDATE 등)에서도 뺀다. 결과를 담을 CREATE TABLE에는 남긴다(결과의 열이므로).
- **고친 기존 문제(코드로 확인):** 테이블 데이터 컨테이너는 모델이 가상 열을 바인딩에 붙이는데, 수신기가 그 열을 결과 집합의 없는 열 번호로 읽어
  (`fetchValueObject(…, ordinal ≥ 열 수)`) SWT에서 만든 가상 열이 있는 테이블은 그 칸마다 오류였다. 또 다음 페이지가 결과 집합 열 수와 바인딩 수를 비교해
  "열이 바뀌었다"로 실패했고, 저장 뒤 행 다시 읽기(`RowDataReceiver`)는 열 수가 달라 건너뛰었다. 세 곳 모두 가상 열을 뺀 수로 맞췄다.

## upstream 대비 변경점

- 저장한 필터는 SWT의 작업 공간 파일(테이블마다 기본 필터 하나) 대신 연결의 가상 모델에 이름별로 여러 개를 둔다. 열 때 자동 적용하지 않는다.
- 필터 기록은 SWT의 쿼리 글자별 메모리 기록 대신 테이블별로 연결 설정에 남는다(조건만).
- 가상 열은 쿼리 결과에도 단일 원본 테이블 기준으로 붙는다. 정렬은 표 안에서만 하고 필터는 걸 수 없다.
- 모델 파일은 바꾸지 않았다.

## 확인 (2026-11-08)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → `BUILD SUCCESS`. `python tools/rename/gate.py` → `ALL GATES PASSED`.
- 실행: 빌드한 Swing 제품을 `-vm` JBR 25, `CHROMITE_DATA`와 작업 공간은 `D:\Tools\_scratch\chromite-saved-filters-virtual-columns`로 열고, 실행 중인 앱 안의
  시험용 Java 에이전트(제품에 들어가지 않음)가 Swing API로 메뉴·대화상자·머리글을 눌렀다(OS 입력·앞 창 없음). 연결의 비밀번호는 Chromite 연결 대화상자에 넣었다.
  공용 Docker DB에 `chr_saved_filters` DB를 만들고(PostgreSQL 17, SQL Server 2022) `sfv_items` 300행(`price` = id × 1.5, `qty` = id % 7 + 1, `category`는
  음료·Books·Tools·NULL 75행씩, `created` 날짜)과 PostgreSQL `sfv_orders`(외래 키)를 넣었다. 한 번에 읽는 행은 200이다. 끝난 뒤 DB를 지웠다.
- **PostgreSQL, 한국어:**
  - 가상 열 추가: 대화상자(기본 단추 취소), `total` = `price * qty`(숫자), 미리 보기 `3.00` → 확인 → 다시 읽은 결과 오른쪽 끝에 `total`(3, 9, 18…, id 7은 10.5).
    `total` 칸은 고칠 수 없고(`isCellEditable` false) 다른 열은 고칠 수 있음. 머리글 도구 설명 "total (…) — 가상 열: price * qty", 깔때기 도구 설명
    "가상 열은 Chromite가 계산하므로 데이터베이스에서 거를 수 없습니다", "값으로 필터" 꺼짐.
  - `total` 정렬: 읽은 200행 안에서(상태 줄 "정렬: total ↑", 도구 설명 "이미 읽은 행을 그리드에서 정렬했습니다"), 내림차순 2,047.5부터.
  - `category` 값 고르기 `음료` → 75행, 가상 열 정렬은 풀리고 값은 다시 계산. `price` 내림차순 → 지금 필터 저장… → 대화상자에 `category IN ('음료')  ORDER BY price DESC`
    → "음료 비싼 순" 저장 → 상태 줄 "필터를 저장했습니다: 음료 비싼 순".
  - 필터 지우기 → 저장한 필터 "음료 비싼 순" → 75행, id 300·296·292…(서버 정렬 "정렬: price ↓ · ORDER BY 대신"). 최근 필터 `category IN ('음료')` → 조건만 바뀌고 지금 정렬(id) 유지.
  - 다음 페이지: 300행, 250번째 행 `total` 229.5(76.5 × 3). 셀 편집 `qty` 7 → 10, 변경 내용 저장 → 저장 뒤 다시 읽은 행의 `total` 3,150 → 4,500(psql에서 qty 10 확인).
  - 숫자·날짜·글자 조건: `price > 400`(34행), `created ≥ 2026-02-15`(16행), `name` 포함 `Item 29`(10행) → "복합 필터" 저장. 최근 필터에 네 항목.
- **PostgreSQL, 영어(`-nl en`), 앱을 다시 시작한 뒤:** 저장한 필터 두 개·최근 필터 네 개·가상 열이 연결 설정에서 다시 읽힘. "복합 필터" 적용 → 10행
  (숫자는 10진 글자, 날짜는 값 처리기의 편집 형식 글자로 저장했다가 다시 값으로 만듦). 최근 필터 `price > 400` 적용 → 목록 맨 위로 올라가고 겹치지 않음(파일에서 읽은 항목과 같다고 비교).
  가상 열 고치기 `price * qty * 1.1` → 미리 보기 `4950.000`, 결과 4,950·2,960.1…, 형식 `numeric`(고치기 전 `int`). `SELECT 1 AS a, 'x' AS b`는 두 메뉴 모두
  "The result is not from a single table". `sfv_orders`에서 외래 키로 `sfv_items`로 가면(테이블이 데이터 컨테이너, 모델이 가상 열을 붙이는 경로) `total` 4,950이 계산됨.
- **SQL Server, 영어:** 가상 열 `label` = `name + ' / ' + category`(글자), 미리 보기 `Item 001 / Books`. NULL `category` 행은 "variable 'category' is undefined"
  → `name + ' / ' + (category ?? '-')`로 고쳐 `Item 299 / -`. `row['price'] * 2`는 "undefined property"(위 "이름이 식별자가 아닌 열"), 괄호가 빠진 식은
  "The expression is not valid: … parsing error", 이름 `price`는 "The result has a column named "price" already."(대화상자 유지). `category` 값 고르기 NULL + `음료`
  → `category IS NULL OR category IN (N'음료')` 150행, `price` 내림차순 → "Drinks or none" 저장 → 필터 지우기 → 적용 → 150행, id 300·299·296…. 가상 열 지우기의 확인은 취소가 기본이고 취소하면 남음.
- **SQL Server, 한국어(다시 시작한 뒤):** "Drinks or none" 적용 150행, 가상 열 `label` 계산(`Item 003 / -`). 저장한 필터 지우기 확인(취소 기본) → 취소 → 남음 → 지우기 → 사라짐.
  최근 필터 지우기 확인 → 지우기 → "아직 쓴 필터가 없습니다". 가상 열 지우기 → 다시 읽은 결과에서 `label`이 사라짐. 연결 설정 파일의 가상 모델에서 저장한 필터와 `label` 항목이 없어짐.
- 캡처: `D:\Tools\_scratch\chromite-saved-filters-virtual-columns\shots\`(`pg-ko-vcol-dialog`, `pg-ko-save-filter`, `pg-en-vcol-edit`, `ms-en-vcol-add`, `ms-en-main`,
  `ms-ko-vcol-add`, `ms-ko-main`).
- 확인하면서 고친 것: 숫자 가상 열의 형식 이름이 `int`(위 "화면"), 도움말의 `row['…']`(모델 권한이 막음) → 쓸 수 있는 이름만 열 넣기에, NULL 안내 추가, 식 칸이 한 줄로
  줄어든 것(GridBag 최소 크기), 최근 필터 지우기 대화상자 제목의 "…".
- 확인하지 못한 것: MySQL·Oracle 등 다른 DB, macOS, 수동 커밋 연결에서 가상 열이 있는 결과 저장, 다크 테마.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 그리드 읽기·다음 페이지·저장 뒤 행 다시 읽기(수신기와 저장 엔진의 열 수 처리),
  머리글 필터 아이콘(열마다 끔), 다시 읽는 필터(가상 열 정렬을 뺌), 결과 행에서 SQL 만들기(가상 열 뺌), 가상 키 메뉴(다시 읽는 메서드를 합침).

## 남긴 것

- 모든 열의 조건·정렬을 한 화면에서 고치는 SWT 필터 편집 대화상자(`filterSettings`)는 옮기지 않았다. 조건은 머리글 팝업, 정렬은 머리글, 열 보이기는 열 메뉴로 한다.
- 저장한 필터 이름 바꾸기(지우고 다시 저장으로 대신), 저장한 필터를 결과를 열 때 자동 적용하기.
- 가상 열의 서버 필터·서버 정렬(계산 값이라 불가), 칸을 고치는 동안의 즉시 다시 계산.
- 이름에 공백·기호가 든 열을 식에서 쓰기(모델 JEXL 권한에 `DBVDataContext`의 `row` 객체를 더해야 함), NULL 값 열을 NULL로 다루기(모델 문맥의 `has`).

## 변경 이력

- 2026-11-08 최초 작성(`chromite/saved-filters-virtual-columns`): 저장한 필터와 최근 필터(B-R10), 가상(계산) 열(B-R22).
  가상 열이 붙은 테이블의 수신기·다음 페이지·저장 뒤 다시 읽기 문제를 고침.
