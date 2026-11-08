# Swing 결과 뷰어 (값 패널, 정렬·필터, 페이지, 레코드 보기)

## 목적

DBeaver SWT 결과 뷰어(`com.seizia.chromite.ui.editors.data`의 `ResultSetViewer`, 패널, 값 보기)의 주요 기능을 Swing 결과 그리드
([swing-frontend.md](swing-frontend.md) C·G단계의 `ResultSetGrid`)에 옮긴다. 우선순위는 다음과 같다.

1. 값 패널: 고른 칸의 값을 표 옆에서 크게 보기(줄 바꿈 글자, JSON·XML 정리, 이진 이미지 미리 보기, 16진수), 편집할 수 있는 표에서는 고치기
2. 정렬·필터: 머리글 눌러 정렬(서버 또는 표 안), 칸 값으로 거르기. WHERE 필터 줄과 기록은 2026-11-07 열 머리글 필터로 바뀜([swing-grid-column-filters.md](swing-grid-column-filters.md))
3. 다음 페이지: 끝까지 내리면 더 읽기, 모두 읽기, 읽은 행 수 표시
4. 레코드 보기: 한 행을 세로로
5. 표 편의 기능: 열 너비 맞춤, 복사 형식(CSV·TSV·SQL IN·Markdown), 선택 집계, NULL 표시, 날짜·숫자 형식

## 구조

모든 클래스는 `com.seizia.chromite.ui.swing` 번들(패키지 같음)에 있다. 새 번들·새 의존은 없다(JSON 정리에 쓰는 Gson은
`com.seizia.chromite.model` → `org.jkiss.utils`가 다시 내보내는 `com.google.gson`이다).

```
QueryResultsView (결과 창, 결과마다 하나씩 쌓음)
 └ ResultSetViewerPanel (결과 하나, SWT ResultSetViewer에 해당)
    ├ JSplitPane
    │  ├ CardLayout: ResultSetGrid (C·G단계 표, 머리글은 ResultSetHeaderRenderer) | ResultSetRecordView (레코드 보기)
    │  │             | ResultSetChartView (차트) | ResultSetSpatialView (공간, 2026-11-08 swing-result-charts-spatial.md)
    │  └ ResultSetValuePanel  값 패널 (F7)
    └ 상태 줄                   읽은 행 수, Fetch next / Fetch all, 필터 요약 | 선택 집계(ResultSetAggregates), 값 패널·레코드 단추
   (머리글 깔때기 → ResultSetColumnFilterPopup, 2026-11-07 swing-grid-column-filters.md)
```

| 클래스 | 새/변경 | 역할 |
|---|---|---|
| `ResultSetViewerPanel` | 새 | 표를 감싸고 정렬·필터·페이지·패널·상태 줄을 가진다. 행이 바뀌면 새 `ResultSetGrid`를 만들어 바꿔 끼운다 |
| `ResultSetSource` | 새 | 결과를 어디서 읽었는지(데이터 컨테이너, 실행 문맥, 한 번에 읽는 행 수, 다시 읽을 수 없는 이유)와 값 패널용 칸 값 |
| `ResultSetCellContent` | 새 | 칸 하나의 LOB 내용. 메모리에 있는 내용은 바로, 없는 LOB는 값 패널이 처음 볼 때 백그라운드에서 읽음 |
| `ResultSetValuePanel` | 새 | 값 패널(Auto/Text/JSON/XML/Image/Hex, 줄 바꿈, Apply/Revert) |
| `ResultSetFilterBar` | 삭제(2026-11-07) | 필터 줄과 기록. 열 머리글 필터로 바꿈([swing-grid-column-filters.md](swing-grid-column-filters.md)) |
| `ResultSetRecordView` | 새 | 레코드 보기(열 이름 · 자료형 · 값, 이전·다음 행, 고치기) |
| `ResultSetAggregates` | 새 | 고른 칸 집계(개수, 합계, 평균, 최솟값, 최댓값) |
| `ResultSetCopyFormats` | 새 | "Copy As" 메뉴(CSV, TSV, Markdown 표, SQL IN 목록) |
| `ResultSetColumnFit` | 새 | 열 너비를 내용에 맞추기 |
| `ResultSetGridReceiver` | 변경 | 칸 값(원래 값, LOB 내용)과 `ResultSetSource`를 함께 넘김, 다음 페이지용 수신기(`forNextSegment`) |
| `ResultSetGrid` | 변경(작은 연결점) | `Fetched`에 `source` 추가, `getTable()`, `setCellText()` |
| `QueryResultsView` | 변경(작은 연결점) | 결과마다 `ResultSetViewerPanel`로 감쌈, 지울 때 `dispose()`(남긴 LOB 값 놓기), 결과를 쌓을 때의 최소 높이 140 → 220px(필터 줄·상태 줄 몫) |

## 설계 결정과 이유

### 표를 고치지 않고 감싼 이유

`ResultSetGrid`는 Job 스레드에서 표시 문자열을 미리 만들어 받는 구조(C단계)와 편집 모델(G단계)을 갖고 있고, 다른 작업과 함께 고치는 파일이다.
행을 다시 정렬하거나 다음 페이지를 붙이려고 표 안의 데이터 구조를 바꾸면 변경이 커진다. 그래서 표는 그대로 두고, 행이 바뀌면
바뀐 행으로 `Fetched`를 새로 만들어 새 표를 끼운다(열 너비, 스크롤 위치, 선택은 이어받음). 표에 더한 것은 표 접근(`getTable`)과
글자로 칸 값 바꾸기(`setCellText`, 셀 편집기와 같은 값 처리기 호출) 둘뿐이다. 오른쪽 클릭 메뉴 항목은 표의 메뉴에 밖에서 더한다.

편집할 수 있는 결과를 다시 만들 때는 저장하지 않은 변경이 없어야 한다(SWT `checkForChanges`와 같은 조건). 그때 편집 모델의 행(저장된 값, 저장 뒤 다시 읽은 값,
추가·삭제가 반영된 행)으로 새 편집 모델을 만든다.

### 값 패널

SWT: `ValueViewerPanel`(panel/valueviewer/ValueViewerPanel.java:60), `ContentPanelEditor`(data/editors/ContentPanelEditor.java:68).

- **보기 방식:** Auto, Text, JSON, XML, Image, Hex. Auto는 LOB의 내용 형식(`DBDContent.getContentType`, 예: PostgreSQL json은 `application/json`)과
  글자 모양으로 고른다. 이진 값은 이미지로 읽히면(JDK `ImageIO`: PNG, JPEG, GIF, BMP, TIFF) Image, 아니면 Hex다. 글자는 JSON이나 XML로 읽히면 그 보기, 아니면 Text다.
- **JSON:** Gson으로 다시 들여쓴다. 숫자는 원래 글자 그대로 남고(Gson의 지연 숫자), `<`, `&` 같은 글자를 `<`로 바꾸지 않게 HTML 이스케이프를 끈다.
  엄격 모드로 읽어 JSON이 아니면 원래 글자와 이유("not valid JSON: …")를 보인다.
- **XML:** JDK 파서와 변환기로 들여쓴다. DTD·외부 엔터티·XInclude를 막는다(XXE). SQL Server `xml` 열처럼 뿌리 요소가 여럿인 조각은 감싸서 읽고 자식마다 쓴다.
- **이미지:** 칸 크기에 맞게 줄여 가운데에 그린다(늘리지는 않음). 아래 줄에 크기(바이트)와 가로×세로.
- **Hex:** 오프셋, 16바이트, ASCII 덤프. 256 KiB까지 보인다.
- **스레드:** LOB 읽기, JSON·XML 정리, 이미지 디코딩, 16진수 덤프는 모두 백그라운드 Job(`AbstractJob`)에서 한다. 칸을 빨리 옮기면 마지막 요청의 결과만 보인다.
- **LOB 값 남기기(`ResultSetCellContent`):** 지금까지 수신기는 LOB 값 객체를 표시 문자열로 바꾼 뒤 바로 놓아 주었다. 값 패널은 내용 전체가 필요하므로
  - 드라이버가 이미 바이트·문자열로 준 내용(`DBDContentCached`: PostgreSQL bytea·json·jsonb, SQL Server varbinary·image, MariaDB BLOB·JSON)은 그 배열·문자열만 남기고 값 객체는 지금처럼 놓아 준다(추가 읽기 없음).
  - 메모리에 없는 LOB(BLOB·CLOB 로케이터, SQLXML)는 SWT 결과 모델처럼 값 객체를 남겨 두었다가 값 패널이 처음 보여 줄 때 `DBDContent.getContents`로 읽는다.
    1,000,000바이트(SWT 기본 `content.cache.maxsize`)까지 읽고 넘으면 "showing the first …"로 알린다. 결과를 지우거나 다시 읽을 때 놓아 준다.
- **편집:** 표가 편집할 수 있는 칸이고 글자 값이면 패널에서 고친 뒤 Apply(Ctrl+Enter)로 표에 넣는다. 값은 셀 편집기처럼 열의 값 처리기가 만들고(`ResultSetGrid.setCellText`),
  틀린 값이면 아래 줄에 빨간 글자로 이유를 쓴다. 저장은 표의 저장(Ctrl+S, Save)이다. 2026-11-08부터 글자 LOB(JSON·XML·CLOB)도 패널에서 고치고(JSON·XML 정리·검사),
  LOB는 파일에서 불러오기·파일로 저장을 한다([swing-grid-editing.md](swing-grid-editing.md) "LOB 편집과 파일").
- **글꼴과 색:** 구문 색은 SQL 편집기 테마 파일(`SqlEditorLight/Dark.xml`), 글꼴은 Messages·오류 상세와 같은 코드 글꼴 `monospaced.font`
  ([design-spec.md](design-spec.md) `font.code`)다. SQL 편집기 글꼴(`EditorFont`, Options에서 바꿈)은 따르지 않는다.
- **열고 닫기:** F7, 상태 줄 오른쪽 "Value" 단추(2026-11-07 전에는 필터 줄 오른쪽), 오른쪽 클릭 "Value Panel". 연 상태는 이 실행 동안 다음 결과에도 이어진다.

### 정렬과 필터

SWT: `ResultSetViewer.toggleSortOrder`(ResultSetViewer.java:2700), `reorderLocally`(:2768), `ResultSetModel.resetOrderingImpl`(ResultSetModel.java:1170),
`ResultSetModel.createDataFilter`(:183), `ResultSetFilterPanel.setCustomDataFilter`(ResultSetFilterPanel.java:618), `addFiltersHistory`(:651),
`ResultSetViewer.fillFiltersMenu`(:3893), `FilterByAttributeAction.run`(:42).

- **정렬 순서:** 머리글을 누르면 오름차순 → 내림차순 → 정렬 없음. Ctrl을 누른 채 누르면 다른 열의 정렬을 두고 이 열을 더한다(여러 열 정렬).
  SWT는 그냥 눌러도 열을 더하지만, Swing은 한 열 정렬을 기본으로 했다(다른 데이터베이스 도구에서 흔한 방식). 머리글 오른쪽 클릭에 Sort Ascending / Sort Descending / Clear Sorting.
  정렬 상태는 2026-11-07부터 머리글 오른쪽 끝의 정렬 아이콘(정렬 없음: 흐린 ⇅, 정렬: 강조색 화살표)이 보이고, 그 아이콘을 눌러도 같은 순서로 바뀐다
  (전에는 Look and Feel의 정렬 화살표 `Table.ascendingSortIcon`/`descendingSortIcon`, [swing-grid-column-filters.md](swing-grid-column-filters.md)).
- **서버 또는 표 안:** SWT 설정 `resultset.order.mode`(`OrderingStrategy`)를 그대로 따른다. SMART(기본)는 아직 읽지 않은 행이 있으면 서버, 다 읽었으면 표 안,
  CLIENT_SIDE는 항상 표 안, SERVER_SIDE는 항상 서버다. DB가 결과 정렬을 지원하지 않거나(`supportsResultSetOrdering`) 쿼리를 다시 읽을 수 없으면 표 안에서 정렬한다.
  서버 정렬은 `DBDDataFilter`의 열 정렬로 쿼리를 다시 읽는다(방언의 `addFiltersToQuery`가 ORDER BY를 넣음). SWT의 서버 정렬 확인 대화상자는 띄우지 않는다.
  2026-11-08부터 서버 정렬은 쿼리의 ORDER BY를 바꾸고(TOP·LIMIT처럼 ORDER BY가 행을 고르면 감싸서 정렬), 상태 줄에 "정렬: 열 ↓ ✕"와 쿼리 순서로 되돌리기가 있다
  ([swing-grid-column-filters.md](swing-grid-column-filters.md) "정렬과 쿼리의 ORDER BY").
- **표 안 정렬:** SWT처럼 먼저 읽은 순서로 되돌린 뒤 정렬 열마다 값 처리기의 비교기, 둘 다 문자열이면 대소문자 무시, 그 밖에는 `DBUtils.compareDataValues`(NULL은 뒤)로 비교한다.
  SWT는 모든 열에 누른 열의 비교기를 쓰지만 Swing은 열마다 그 열의 비교기를 쓴다. LOB처럼 표시 문자열만 아는 칸은 표시 문자열로 비교한다.
- **열 필터(2026-11-07, 필터 줄 대신):** 머리글의 깔때기로 열마다 값 목록(서버에서 읽은 서로 다른 값과 행 수)이나 조건(=, ≠, 포함, >, 범위, IS NULL 등)을 건다.
  필터는 열 조건(`DBDAttributeConstraint`)으로 쿼리를 다시 읽는다. 지금 조건은 상태 줄 요약의 도구 설명(`SQLUtils.appendConditionString`)으로 보인다.
  자세한 것은 [swing-grid-column-filters.md](swing-grid-column-filters.md). 예전 WHERE 필터 줄(`setWhere`, 쿼리별 기록 20개)은 없앴다.
- **칸 값으로 거르기:** 오른쪽 클릭 "Filter by Value"에 SWT 필터 메뉴처럼 고른 칸의 값으로 `=`, `<>`, `>`, `<`와 `IS NULL`, `IS NOT NULL`(열의 값 처리기가 지원하는 연산자만),
  그 열의 필터 지우기가 있다. 열 조건(`DBDAttributeConstraint`의 연산자와 값)으로 넣으므로 별칭 열도 방언이 맞는 이름으로 바꾼다. LOB 칸 값으로는 거르지 않는다.
  "Clear Filter"는 모든 조건을 지운다.
- **다시 읽을 수 있는 결과:** 컨테이너가 `FEATURE_DATA_FILTER`를 지원하고 쿼리가 SELECT 하나(`SQLQuery.getType() == SELECT`, `isModifying()` 거짓)일 때만
  필터·서버 정렬·다음 페이지를 한다(`ResultSetSource.getRequeryBlocker`). SWT는 쿼리를 다시 실행하기 전에 데이터가 바뀌는 쿼리인지 묻지만(`verifyQuerySafety`, :4463),
  Swing의 프로시저 호출은 다시 실행하면 다른 문장까지 실행되고 두 번째 이후 결과 집합은 `readData`로 다시 얻을 수 없으므로 막는다. GO 배치·선택한 여러 문장의
  결과는 그 결과를 만든 SELECT만 다시 읽고, 그 문장을 정할 수 없으면 막는다(이유 "this result can't be read again without running the whole batch again",
  [sql-batch-reread.md](sql-batch-reread.md)).
  막힌 결과는 머리글의 깔때기가 흐려지고(2026-11-07 전에는 필터 줄이 꺼짐) 도구 설명에 이유("not a single SELECT query")가 보인다. 정렬은 표 안에서 된다.
- **저장하지 않은 변경:** 정렬·필터 전에 "변경을 버릴까요?"를 묻는다(SWT `checkForChanges`). 저장 중에는 하지 않는다.
- **다시 읽기:** 백그라운드 `DataSourceJob`에서 `DBSDataContainer.readData(…, FLAG_REFRESH, …)`로 한다. `FLAG_REFRESH`는 쿼리 매개변수를 다시 묻지 않고 지난 값을 쓰게 한다.
  실패하면 상태 줄에 "Read failed: …", Messages에 오류를 쓰고 표는 그대로 둔다.

### 다음 페이지

SWT: `ResultSetViewer.readNextSegment`(:4422), `readAllData`(:4471), `ResultSetDataReceiver`의 `nextSegmentRead`.

- 한 번에 읽는 행 수는 처음 실행에서 엔진이 쓴 최대 행 수(연결 설정 `resultset.maxrows`, 기본 200)다. 받은 행이 그 수에 닿으면 "200 rows fetched — more available"로 보이고
  상태 줄에 "Fetch next 200", "Fetch all" 링크가 나온다.
- 표를 끝까지 내리면(SWT 설정 `resultset.autofetch.next.segment`, 기본 켬) 다음 행을 읽는다. 같은 쿼리를 지금 행 수를 오프셋으로, 최대 행 수만큼 `readData`로 읽는다
  (오프셋·행 수는 모델 계층 `DBUtils.makeStatement`가 방언의 행 제한 변환이나 JDBC 행 건너뛰기로 처리).
- 다음 페이지 수신기(`ResultSetGridReceiver.forNextSegment`)는 첫 결과의 열 바인딩을 그대로 쓰고 테이블 메타데이터에 다시 묶지 않는다(SWT와 같음). 열 수가 다르면 오류로 멈춘다.
- 편집할 수 있는 결과에 저장하지 않은 변경이 있으면 자동 읽기는 건너뛰고 상태 줄에 "Save or cancel the changes to fetch more rows."를 쓴다. 링크로 읽을 때는 같은 이유를 알린다.
- "Fetch all"은 확인한 뒤 나머지를 모두 읽는다(SWT `CONFIRM_RS_FETCH_ALL`과 같은 확인).
- 표 안에서 정렬한 상태면 붙인 뒤 같은 정렬을 다시 적용한다.
- 다시 읽거나(필터, 서버 정렬) 다음 행을 붙이면 뷰어가 `ResultSetGrid.Host.rowsChanged()`로 알리고, 쿼리 창이 쿼리 상태 줄의 행 수를
  모든 결과 표에 지금 보이는 행의 합(`QueryResultsView.getRowCount()`)으로 바꾼다. 처음에는 실행 때 받은 행 수만 세어 필터·다음 페이지 뒤에도 그대로였다.
  실행 중에는 실행이 세는 값을 그대로 쓴다.

### 레코드 보기

SWT: 결과 뷰어의 레코드 모드(`ResultSetViewer.toggleMode`).

- 상태 줄 오른쪽 "Record" 단추(2026-11-07 전에는 필터 줄 오른쪽)나 오른쪽 클릭 "Record"로 바꾼다. 표의 선택 행을 "Column | Type | Value"로 보여 주고 ◀ ▶(Alt+←/→)로 행을 옮긴다(표의 선택도 옮겨 돌아와도 같은 행).
- 편집할 수 있는 결과는 값 칸을 두 번 누르거나 F2로 고친다(표의 셀 편집기와 같은 값 처리기, 틀린 값은 빨간 테두리). 바뀐 칸은 표처럼 노란 바탕이고, Ctrl+S가 표의 변경을 저장한다.
- 레코드 보기로 바꾸면 표에서 고른 열의 줄이 선택된다(값 패널이 같은 칸을 계속 보인다).
- SWT의 Tab 단축키는 쓰지 않았다(표에서 Tab은 다음 칸으로 가는 키라서).

### 표 편의 기능

- **열 너비 맞춤:** 머리글 오른쪽 클릭 "Fit Column Width"/"Fit All Column Widths", 머리글 경계 두 번 누르기, 표 오른쪽 클릭 "Fit All Column Widths".
  표의 실제 렌더러로 머리글과 5,000행까지 재어 최대 900px로 맞춘다(처음 너비는 C단계 규칙: 200행, 최대 320px).
- **Copy As:** CSV(RFC 4180 따옴표), TSV(머리글 포함, 탭·줄바꿈은 공백), Markdown 표(숫자 열 오른쪽 정렬), SQL IN 목록(중복 없이, 숫자는 그대로, 글자는 방언의 따옴표).
  파일 내보내기(`ResultSetExport`, CSV·XLSX)는 바꾸지 않았다.
- **Export Data…(데이터 전송, [swing-data-transfer.md](swing-data-transfer.md)):** 정렬·필터·다음 페이지로 다시 읽은 결과도 처음 결과의
  다시 실행할 쿼리(`ResultSetGrid.Fetched#exportQuery`)를 이어받아 메뉴가 켜진 채로 남는다. 내보내기는 그 쿼리를 필터·정렬 없이 다시 실행해 모든 행을 쓴다.
  지금 필터·서버 정렬을 내보내기에 넘기려면(`DatabaseTransferProducer(컨테이너, DBDDataFilter)`) 마법사와 표의 메뉴에 필터를 넘기는 길이 더 필요해 남겼다(아래 "남긴 것").
- **선택 집계:** 칸을 둘 이상 고르면 상태 줄 오른쪽에 `Count: 6   Sum: …   Avg: …   Min: …   Max: …`. 모델 계층의 집계 함수(`FunctionSum`, `FunctionAvg`, `FunctionMin`, `FunctionMax`,
  SWT `AggregateColumnsPanel`(panel/aggregate/AggregateColumnsPanel.java:60)이 쓰는 것)를 쓰고 숫자 값만 넣는다. 선택이 바뀌고 100ms 뒤에 계산한다(SWT `ResultSetStatListener`와 같은 지연).
- **행 번호 열:** 맨 왼쪽(SSMS·DataGrip처럼), 그리드 바탕에 보조 글자색(`Chromite.secondaryForeground`)이다. 2026-11-08부터 선택한 행의 번호도 선택색으로 칠하지 않는다:
  셀 하나만 골라도 번호 칸이 함께 칠해져 선택이 번호 열까지 이어졌다가 끊긴 것처럼 보였다(사용자 #4). 그 칠하기만을 위해 있던 표의 선택·포커스 리스너도 지웠다.
  번호를 누르면 그 행 전체를 고르는 동작(가로 스크롤 없이)은 그대로다. 열 전체 선택은 머리글 이름 자리를 누른다([swing-grid-column-filters.md](swing-grid-column-filters.md) "머리글 누르기").
  확인(2026-11-08, 앱 안 시험 에이전트): 칸 3행×3열 선택, 행 번호 클릭(그 행 7열 모두 선택), 표가 포커스를 잃은 상태에서 번호 칸 렌더러 바탕이 모두 그리드 바탕(#ffffff),
  글자 #73767c. 캡처 `D:\Tools\_scratch\grid-header\shots\4-1-cells-selected.png`, `4-2-row-selected.png`.
- **NULL 표시:** C단계 그대로 `NULL`을 보조 글자색으로 그린다(문자열 "NULL"과 구분). 레코드 보기와 값 패널도 같다.
- **날짜·숫자 형식:** 표의 표시 문자열은 C단계부터 열의 값 처리기 `getValueDisplayString(…, DBDDisplayFormat.UI)`로 만들고, 값 처리기는 연결의 데이터 형식 프로필
  (`DBDDataFormatterProfile`, SWT 설정 화면의 Data Formats)을 쓴다. 그래서 SWT 그리드와 같은 형식이다. 값 패널은 SWT 패널처럼 편집 형식(`DBDDisplayFormat.EDIT`, 전체 정밀도)이다.
- **열 고정(freeze):** 하지 않았다(아래 "남긴 것").

### 아주 작은 수의 표시 (2026-11-07)

- 증상: `double precision` 값 `-2.5E-300`이 표에 `-0`, `2.5E-300`이 `0`으로 보임. 값 패널과 칸 편집기도 `-0`·`0`이었다.
- 원인 1(표, UI 형식): 숫자 형식기(`NumberDataFormatter`)는 최대 소수 자릿수(기본 10)로 반올림한다. 그보다 작은 0이 아닌 값을 지수로 보이는 설정
  `scientificSmallValues`(Data Formats의 "Use scientific notation for small numbers")가 있지만 기본값이 꺼짐이었다(`NumberFormatSample`). 음수는 반올림해도 부호가 남아 `-0`.
- 원인 2(편집·원래 형식, EDIT·NATIVE): `DBValueFormatting.convertNumberToNativeString`은 지수 표기를 피하려고 `Double`을 소수 16자리(`Float`은 8자리)로 고쳐 썼다.
  그보다 작은 자리의 값은 0이 되었고, 이 글자가 칸 편집기·값 패널의 처음 값이 되고 생성한 SQL(INSERT 등, `SQLUtils.convertValueToSQL`)의 값이 되었다.
  편집기를 열고 그대로 저장하거나 SQL을 만들면 값이 0으로 바뀔 수 있었다. `1.2345678901234567E-5` 같은 값도 끝자리를 잃었다.
- 고친 것: `scientificSmallValues` 기본값을 켬(표에 `-2.5E-300`). 원래 형식은 소수 자릿수 안에 들어갈 때만 지수 없이 쓰고, 아니면 Java의 글자(지수 포함)를 그대로 쓴다.
  작업 공간에 이미 저장한 형식 프로필의 값은 그대로다(바꾼 적 없는 설정만 새 기본값을 따름). SWT에도 같은 모델이라 함께 바뀐다.
- 다시 확인(2026-11-08, 후속 과제 #53, 고칠 것 없음): 새 작업 공간과 데이터 폴더로 띄운 빌드에서 표에 `-2.5E-300`, `1E-12`, `0.000123`이 보였다
  (PostgreSQL `double precision`, SQL Server `float`, Oracle `BINARY_DOUBLE`). Oracle `NUMBER` 리터럴 `1E-120`·`-2.5E-11`도 지수로 보였다.
  Oracle에서 `-2.5E-300`을 `NUMBER` 리터럴로 넣으면 `NUMBER` 범위(약 1E-130) 밖이라 저장 전에 0이 된다(`-2.5E-300d`로 넣어야 함, DB 동작).

## upstream 대비 변경점

- upstream(SWT) 코드는 바꾸지 않았다. Swing 번들만 바뀌었다. 모델은 2026-11-07에 SQL Server `datetimeoffset` 편집 글자 처리(`SQLServerDateTimeOffsetHandler`)와 아주 작은 수의 표시(`NumberFormatSample`의 기본값, `DBValueFormatting.convertNumberToNativeString`)를 고쳤다(SWT에도 적용).
- SWT와 다른 동작: 머리글 클릭은 한 열 정렬(Ctrl로 더하기), 서버 정렬 확인 대화상자 없음, 표 안 정렬에서 열마다 그 열의 비교기, 다시 실행해도 안전한지 묻는 대신 SELECT 하나만 다시 읽음,
  선택 집계는 숫자 값만, 필터 기록은 메모리에만.

## 확인 (2026-11-06)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C '-Pproduct-chromite-swt,product-chromite-swing'` → `BUILD SUCCESS`.
  Swing 제품 `plugins/`에 `swt`·`jface`·`org.eclipse.ui`·`e4.ui` 번들 0개(새 번들 의존 없음).
- 실행: 빌드한 Swing 제품을 따로 복사해 별도 작업 공간으로 열고, 로컬 Docker DB(PostgreSQL 17, SQL Server 2022, MariaDB 11)에 시험용 DB `chr_result_viewer`를 만들어 확인했다
  (`items` 1,000행: 숫자·날짜·json·jsonb·xml·이미지·이진·여러 줄 글자 열). 같은 PC에서 다른 앱 창과 Windows 방화벽 알림이 앞 창을 잡고 있어,
  키보드·마우스 대신 시험용 Java 에이전트(제품에 들어가지 않음)로 EDT에서 단추·메뉴·머리글을 눌렀고 화면은 창 캡처로 확인했다.
  - **값 패널:** SQL Server `nvarchar(max)` JSON → JSON 들여쓰기, `xml`(뿌리 둘인 조각) → XML 들여쓰기, `image` → PNG 미리 보기(48×32, 1,871 bytes), `varbinary` → Hex.
    PostgreSQL `json`·`jsonb`(`text/json`), `xml`(그리드에 `[XML]`로 보이는 메모리에 없는 LOB, 패널이 백그라운드에서 읽음), `bytea` 이미지·Hex. MariaDB `json`(longtext), `blob` 이미지.
  - **값 패널 편집:** SQL Server 편집 결과에서 이름 칸을 패널에서 고쳐 Apply → `1 pending change` → Save → `sqlcmd`로 DB 값 확인(`Item 0998 edited in panel`).
  - **정렬:** SQL Server 200행(더 있음)에서 `price` 머리글 두 번 → 서버 정렬(내림차순, 편집 가능 유지), PostgreSQL에서 모두 읽은 뒤 `name`·`note` 머리글 → 표 안 정렬, GO 배치 결과 → 표 안 정렬.
  - **필터:** `qty > 45 and name like 'Item 09%'` → 8행, 칸 값으로 거르기 `name = 'Item 0998'` → 필터 줄에 `name = N'Item 0998'`, 1행. MariaDB `qty = 7 and id > 900` → 2행. 지우기 → 원래 결과.
    `declare … GO` 배치 결과는 필터 줄이 꺼진다.
  - **다음 페이지:** SQL Server 서버 정렬 상태에서 끝까지 내리기 → `400 rows fetched — more available`, 200·201번째 행이 정렬 순서대로 이어짐(Copy As CSV로 확인).
    PostgreSQL "Fetch all" → 확인 대화상자 → `1,000 rows`.
  - **레코드 보기, 선택 집계, Copy As(CSV), 라이트·다크 테마**를 화면으로 확인했다. CSV·TSV·Markdown·SQL IN 형식과 JSON·XML·Hex 정리, XXE 차단은 같은 클래스를 직접 부르는 시험 코드로도 확인했다.
- 확인하면서 고친 것: 서버 정렬 뒤 편집할 수 없게 되던 것(다시 읽을 때 쿼리를 실행 원본 설명자로 넘기지 않아 SQL Server에서 열의 테이블을 찾지 못함),
  값 패널을 열고 닫으면 표의 키보드 포커스가 빠지던 것, Hex가 줄 바꿈되던 것, Record 단추가 두 번 바뀌어 동작하지 않던 것, 새 결과에서 값 패널이 너무 좁게 열리던 것.
- 확인하지 못한 것: 메모리에 없는 BLOB·CLOB(Oracle 등) 지연 읽기의 오류 경로, 수동 커밋 연결에서의 다음 페이지, macOS.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 그리드 편집·저장(G단계), 결과 복사·CSV·XLSX 내보내기, 결과가 여러 개인 실행의 결과 창 높이.

## 남긴 것

- 열 고정(freeze), 필터 편집 대화상자, 자동완성되는 필터 입력. 열 숨기기·순서 바꾸기와 행 수 세기는 2026-11-08에 했다
  ([swing-grid-navigation.md](swing-grid-navigation.md)). 저장한 필터·필터 기록과 가상(계산) 열도 2026-11-08에 했다
  ([swing-saved-filters-virtual-columns.md](swing-saved-filters-virtual-columns.md)).
- 이진 값 형식 바꾸기(SWT의 Binary format 메뉴). LOB·JSON 열 편집과 파일로 저장·파일에서 불러오기는 2026-11-08에 했다([swing-grid-editing.md](swing-grid-editing.md)).
- 그룹 패널, 메타데이터 패널. 결과 안 찾기(찾기만)와 참조 패널은 2026-11-08에 했다([swing-grid-navigation.md](swing-grid-navigation.md)).

## 변경 이력

- 2026-11-06 최초 작성: 값 패널, 정렬·필터·기록·칸 값 거르기, 다음 페이지·모두 읽기, 레코드 보기, 열 너비 맞춤, Copy As, 선택 집계.
- 2026-11-06 데이터 전송과 합침: `ResultSetGrid.Fetched`에 두 기능의 항목(`source`, `exportQuery`)을 함께 두고, 다시 읽은 결과도 Export Data…의 쿼리를 이어받음.
- 2026-11-06 필터·다음 페이지 뒤 쿼리 상태 줄의 행 수가 바뀌지 않던 문제: 뷰어가 행 수 변경을 알리고 쿼리 창이 지금 보이는 행 수로 바꿈.
- 2026-11-07 날짜/시간 값을 소수 초까지 보이고 편집해도 남게 하는 Tools > Options 설정(SWT "Disable date/time formatting"과 같은 설정),
  SQL Server `datetimeoffset` 편집이 NULL로 저장되던 것. [swing-followup-fixes.md](swing-followup-fixes.md) "날짜/시간 소수 초".
- 2026-11-07 WHERE 필터 줄(`ResultSetFilterBar`)과 기록을 없애고 열 머리글의 필터·정렬 아이콘과 열 필터 팝업으로 바꿈(사용자 요청). 값 패널·레코드 단추는 상태 줄로.
  [swing-grid-column-filters.md](swing-grid-column-filters.md).
- 2026-11-07 값 패널 글꼴 설명을 코드에 맞춤: `ResultSetValuePanel` 설계 주석이 "SQL 편집기와 같은 글꼴"이라 했지만 코드와 design-spec.md는 `monospaced.font`(코드는 그대로).
- 2026-11-07 아주 작은 수의 표시(#53): `scientificSmallValues` 기본값을 켜고, 원래 형식(편집·생성 SQL)이 값을 반올림하면 지수 표기를 둠. 위 "아주 작은 수의 표시".
- 2026-11-08 값 패널에서 글자 LOB 편집, JSON·XML 정리·검사, LOB 파일 불러오기·저장, Copy As의 JSON·XML·HTML·SQL INSERT와 고급 복사. 값 패널 "편집"과 "남긴 것" 갱신.
  [swing-grid-editing.md](swing-grid-editing.md).
- 2026-11-08 #53을 PostgreSQL·SQL Server·Oracle에서 다시 확인함(고칠 것 없음). 서버 정렬이 쿼리의 ORDER BY를 바꾸고 상태 줄에 정렬 요약을 보임(#75, swing-grid-column-filters.md).
- 2026-11-08 상태 줄에 Chart·Spatial 단추, 표 자리에 차트·공간 보기(레코드 보기와 같은 방식, 한 번에 하나). 기하 값(`DBGeometry`)을 칸 값에 남겨 값 패널이
  편집 형식(WKT)으로 보임. 선택 집계는 표가 보일 때만. [swing-result-charts-spatial.md](swing-result-charts-spatial.md).
- 2026-11-08 결과 탐색(B4): 외래 키 따라가기와 뒤로·앞으로, 참조·집계 패널, 가상 키, 열 숨기기·순서·빈 열, 행·열로 가기, 확대·축소, 찾기, 텍스트 보기,
  전체 행 수. "남긴 것" 갱신. [swing-grid-navigation.md](swing-grid-navigation.md).
- 2026-11-08 "남긴 것"에서 저장한 필터를 지움(저장한 필터·최근 필터·가상 열, [swing-saved-filters-virtual-columns.md](swing-saved-filters-virtual-columns.md)).
- 2026-11-08 배치 결과 다시 읽기: 결과를 만든 SELECT만 실행, 정할 수 없으면 막고 이유를 보임. [sql-batch-reread.md](sql-batch-reread.md).
- 2026-11-08 행 번호 칸을 선택·포커스와 관계없이 늘 그리드 바탕으로("표 편의 기능").
