# Swing 결과 차트와 공간 보기

## 목적

결과 표에 두 가지 보기를 더한다.

1. **차트(Chart):** 읽은 행을 선·막대·원형 차트로 그린다. 가로축 열과 값 열, 행 범위를 고르고 PNG로 저장한다.
   SWT CE의 결과 표에는 차트 보기가 없었다(`ui.charts`는 대시보드 전용, [swt-parity/C-navigator-tools.md](swt-parity/C-navigator-tools.md) 3절).
   그래서 이 기능은 Chromite에서 새로 만든 것이다.
2. **공간(Spatial):** 기하 값(PostGIS·MySQL/MariaDB 등 모델 값 처리기가 만드는 `DBGeometry`, SQL Server `geometry`/`geography`)을
   평면에 그린다. SWT의 GIS 보기(`data.gis.view`, Leaflet 지도와 온라인 타일)를 대신한다. 폐쇄망에서 쓰므로 온라인 타일을 쓰지 않는다.

## 구조

모든 클래스는 `com.seizia.chromite.ui.swing` 번들에 있다. 새 라이브러리는 없다. 그리기는 Java2D, 기하는 이미 제품에 있는
`org.jkiss.bundle.gis`(JTS 1.20, WKG)를 `com.seizia.chromite.data.gis`를 거쳐 쓴다(Swing 번들 `Require-Bundle`에 `data.gis`를 더함).

```
ResultSetViewerPanel (결과 하나)
 └ CardLayout: ResultSetGrid | ResultSetRecordView | ResultSetChartView | ResultSetSpatialView
               (상태 줄 오른쪽 Value · Record · Chart · Spatial 단추, 표 오른쪽 클릭에도 같은 항목)
    ResultSetChartView   ── ResultSetChart (Java2D 선·막대·원형)
    ResultSetSpatialView ── SqlServerSpatialReader (SQL Server 바이트 → JTS), JTS ShapeWriter
```

| 클래스 | 새/변경 | 역할 |
|---|---|---|
| `ResultSetChartView` | 새 | 차트 종류(선·막대·원형), 가로축 열, 값 열(최대 8개), 행 범위(읽은 모든 행 / 선택한 행), PNG 저장, 알림 줄 |
| `ResultSetChart` | 새 | 차트 그리기: 값 축, 가로축(범주 또는 비례), 범례, 마우스 위치의 값 상자, PNG용 이미지 |
| `ResultSetSpatialView` | 새 | 기하 열 고르기, 맞추기, 끌어서 옮기기·휠 확대, 누르면 그 행 선택, 좌표 격자, 알림 줄 |
| `SqlServerSpatialReader` | 새 | SQL Server CLR 직렬화 형식([MS-SSCLRT] 2.1)을 JTS 기하로 읽음 |
| `ResultSetViewerPanel` | 변경 | 보기 방식(`ViewMode`: 표·레코드·차트·공간), 단추·메뉴, 두 보기의 원본, 기하 값을 값 패널에 넘김, "Filter by Value"에서 기하 제외 |
| `ResultSetGridReceiver` | 변경 | `DBGeometry` 값을 칸 값(`ResultSetSource.cellValues`)에 남김 |
| `DashboardChart` | 변경 | 계열 색 8개를 `seriesPalette()`로 내놓음(차트·공간 보기가 같은 색을 씀) |
| `EditorMessages` | 변경 | 영어·한국어 문구 |

## 설계 결정과 이유

### 차트 라이브러리: Java2D

- JFreeChart는 제품에 없다. 넣으려면 LGPL 라이브러리(약 1.6 MB)를 새로 들이고 NOTICE를 고쳐야 한다.
  필요한 것은 선·막대·원형, 값 축 하나, 범례, 마우스 위치의 값, PNG 저장뿐이어서, 서버 대시보드([swing-dashboard.md](swing-dashboard.md) "차트 라이브러리")처럼 Java2D로 직접 그렸다.
- 색은 대시보드와 같은 검증된 8색을 같은 순서로 쓴다(색각 이상 구분을 검사한 순서). 그래서 값 열은 8개까지 고를 수 있고, 8개를 고르면 나머지 칸이 꺼진다.
  원형은 8조각까지 그리고 더 많으면 큰 7조각과 회색 "Other (n)"로 묶는다.
- 그릴 때마다 Look and Feel의 색을 읽으므로 라이트·다크 전환을 따로 처리하지 않는다. 글자는 계열 색이 아니라 글자 색이다.
- 값 축은 하나다(두 축 차트 없음). 막대는 0에서 시작하고 음수는 0 아래로 그린다. 선은 값 범위에 맞춘다. 0을 지나는 값이면 0 선을 그린다.
- 선은 2px, 점이 50개 이하면 8px 표시점(바탕색 테두리). 막대는 끝만 4px 둥글고 막대 사이 2px 틈. 빈 칸(NULL·숫자가 아님)은 선을 끊는다.

### 차트의 데이터

- **읽은 행만:** 차트는 표에 지금 읽혀 있는 행으로 그린다(쿼리를 다시 실행하지 않음). 더 읽을 행이 있으면 알림 줄에 "more rows are not loaded yet"을 쓴다.
  상태 줄의 "Fetch all"로 모두 읽으면 차트가 다시 그려진다(행이 바뀌면 `installGrid`가 차트를 다시 그림).
- **값:** 수신기가 남긴 칸의 원래 값을 쓴다(`Number`, 날짜는 epoch 밀리초). 가로축 글자는 표의 표시 문자열이라 표와 같은 형식(데이터 형식 프로필)이다.
  숫자 열은 표와 같이 `DBPDataKind.NUMERIC`이다.
- **처음 설정(Chart 단추를 누를 때마다):**
  - 칸을 둘 이상 골랐으면 고른 숫자 열이 값, 고른 숫자가 아닌 첫 열이 가로축이다. 행을 둘 이상 골랐으면 "Selected rows"로 시작한다.
  - 칸 하나만 골랐으면(보통 상태) 결과 전체: 숫자가 아닌 첫 열이 가로축(없으면 행 번호), 숫자 열(가로축 제외, 8개까지)이 값이다.
  - 가로축이 날짜·숫자면 선, 아니면 막대로 시작한다.
- **선의 가로축:** 가로축 열이 숫자·날짜이고 모든 행에 값이 있으면 값에 비례한 위치로 그리고 가로축 값 순서로 잇는다(날짜 눈금 형식은 범위에 따라 `MM-dd HH:mm`, `yyyy-MM-dd`, `yyyy-MM`).
  아니면 행 순서의 범주다. 막대와 원형은 항상 범주다. 겹치는 범주 이름은 건너뛰고 칸에 맞게 줄인다.
- **원형:** 첫 값 열 하나만 그린다(여러 개를 골랐으면 알림 줄에 적음). 0 이하·빈 값은 빼고 그 수를 알림 줄에 적는다.

### PNG 저장

- "Save as PNG..."는 화면에 보이는 차트 크기를 화면 배율(HiDPI면 2배 등)로 저장한다. 마우스 강조와 값 상자는 넣지 않는다. 바탕은 보기의 바탕색이다.
- 이미 있는 파일은 결과 내보내기와 같은 확인(`editor_export_replace_confirm`, 기본 단추가 Cancel인 `SwingPlatformUI.confirmChange`)을 거친다.

### 공간 보기: 밑그림 없는 평면

- SWT GIS 보기는 Leaflet과 온라인 타일(OpenStreetMap 등)을 썼다. Chromite는 폐쇄망에서도 쓰므로 타일을 쓰지 않고, 좌표를 그대로 x는 가로·y는 위로 그린다.
  경위도(SRID 4326 등)는 경도를 가로, 위도를 세로로 놓은 등장방형이다. 좌표 격자와 눈금 값을 보조 글자색으로 그린다. 알림 줄에 "coordinates as they are, no base map"을 쓴다.
- 좌표계 변환(SRID 바꾸기)은 하지 않는다. 같은 열의 값이 여러 SRID면 "mixed SRIDs"로 알린다.
- 점은 8px 원(바탕색 테두리), 선은 2px, 면은 옅게 칠하고 1.5px 테두리. 색은 계열 색의 첫 색이고, 표에서 고른 행은 둘째 색으로 맨 위에 그린다.
  기하를 누르면 그 행이 표에서 선택된다(값 패널과 레코드 보기가 따라감). 마우스를 올리면 행 번호, 기하 형식, 좌표를 도구 설명으로 보인다.
- 끌어서 옮기고 휠로 확대·축소(마우스 위치 기준). "Fit"은 모든 기하를 여백을 두고 보인다. 점 하나뿐이면 그 둘레 1단위를 보인다.

### 기하 값 읽기

- **모델의 공간 지원을 쓴다:** 열의 값 처리기가 `DBGeometry`를 만드는 열(`getValueObjectType`이 `DBGeometry`)이 기하 열이다. PostGIS(`PostgreGeometryValueHandler`),
  MySQL/MariaDB, SQLite, H2GIS, HANA, Exasol, ClickHouse, DuckDB, Altibase, Mimer 값 처리기가 여기에 든다. 곡선은 `DBGeometry.linearize()`(WKG)로 직선으로 나누고,
  JTS 기하가 없는 값(글자)은 WKT로 읽는다(EWKT의 `SRID=…;` 앞부분은 뗌). 그리기는 JTS `ShapeWriter`다(반 픽셀보다 가까운 꼭짓점은 뺌).
- **수신기가 값을 남긴다:** 수신기는 값 객체(`DBDValue`)를 표시 문자열로 바꾼 뒤 놓아 주었다. `DBGeometry`는 DB 자원을 잡지 않는 값이라(`release()`가 빈 메서드)
  칸 값에 그대로 남긴다. 열은 지금처럼 편집하지 않는다(값 객체 열). 편집할 수 있는 결과에서도 값 패널·공간 보기가 이 값을 읽도록 `getCellValue`가
  LOB 내용처럼 칸 값을 돌려준다. 그래서 기하 칸의 값 패널은 표시 문자열 대신 열의 값 처리기가 만든 편집 형식(WKT)을 보인다.
- **편집 모델의 빈 값 객체:** 편집할 수 있는 결과에서 편집 모델의 값 객체 열은 null이 아닐 수 있다. 열을 테이블 열에 묶을 때(`DBExecUtils.bindAttributes`, DBExecUtils.java:964-971)
  값 처리기가 바뀌면 이미 읽은 값(값 객체 열은 null)을 새 처리기로 바꾸는데, `GISGeometryValueHandler.getValueFromObject(null)`은 빈 `DBGeometry`를 만든다.
  MariaDB `places`(기본 키 있음)에서 처음 만든 빌드는 이 빈 값을 읽어 기하 6개를 모두 "비어 있음"으로 셌다(에이전트로 `getCellValue`가 `DBGeometry@…`(빈 값),
  칸 값이 `POINT (126.978 37.5665)`임을 확인). 그래서 칸 값으로 돌아가는 조건을 `value == null`에서 `DBUtils.isNullValue(value)`로 바꿨다.
  같은 이유로 처리기가 바뀐 LOB 열도 값 패널이 남긴 내용을 보인다.
- **"Filter by Value"에는 넣지 않는다:** 전에는 기하 칸의 값이 없어 IS NULL·IS NOT NULL만 나왔다. 남긴 기하를 `=` 필터 값으로 쓰면 DB마다 기하 비교 규칙이 달라
  (PostGIS `=`는 버전에 따라 경계 상자 비교) 동작이 바뀌므로 지금처럼 둔다.
- **SQL Server:** 모델에 SQL Server 공간 값 처리기가 없고, Microsoft JDBC 드라이버는 `geometry`/`geography`를 바이트 배열로 준다. 모델에 값 처리기를 더하면
  표 표시·편집·저장·내보내기가 함께 바뀌므로, 공간 보기에서만 바이트를 읽는다(`SqlServerSpatialReader`). 연결이 `SQLServerDataSource`이고 열 형식 이름이
  `geometry`/`geography`인 열이 대상이다.
  - 형식: SRID(4바이트), 버전 1·2, 속성(Z, M, 단일 점, 단일 선분), 점 배열, Z·M 배열(그리지 않음), 도형(figure), 모양(shape, 부모 번호로 묶음). 버전 2의 곡선은
    `data.gis`의 `CircularArc`로 직선으로 나눈다(사분원당 약 32조각, `WKGUtils.linearize`와 같은 허용 오차).
  - `geography`의 점은 위도, 경도 순서다. 실제 값으로 확인했다: `geography::Point(37.5665, 126.978, 4326)`의 바이트
    `E6100000 01 0C 8D976E1283C84240 3BDF4F8D97BE5F40`의 첫 실수가 37.5665, 둘째가 126.978. 그래서 x = 경도, y = 위도로 바꾼다.
  - FullGlobe는 그릴 수 없어 빈 값으로 센다.
- **값 해석은 백그라운드:** 표의 값은 EDT에서 모으고, LOB 읽기(메모리에 없는 LOB)와 해석은 Job에서 한다. 마지막 요청의 결과만 보인다.
  읽지 못한 값은 수를 알림 줄에 쓰고 첫 오류만 디버그 기록에 남긴다.

### 보기 전환

- 레코드 보기의 `boolean`을 보기 방식 `ViewMode`(GRID, RECORD, CHART, SPATIAL)로 바꿨다. 한 번에 하나만 보이고, 같은 단추를 다시 누르면 표로 돌아간다.
- Spatial 단추와 메뉴 항목은 기하 열이 있는 결과에만 보인다. 차트 단추는 늘 보이고, 숫자 열이 없으면 차트 자리에 그 이유를 쓴다.
- 선택 집계(상태 줄의 Count·Sum…)는 표가 보일 때만 보인다(전에는 레코드 보기에서만 숨김).

## upstream 대비 변경점

- upstream(SWT) 코드와 모델 번들은 바꾸지 않았다. Swing 번들만 바뀌었다(`data.gis` 의존 추가).
- SWT와 다른 점: 결과 차트는 CE에 없던 기능이다. 공간 보기는 지도 타일·SRID 변환·GIS 환경설정(`gis.view.*`)·기하 값 편집기가 없고, 대신 폐쇄망에서도 동작한다.
  SWT GIS 보기는 SQL Server 바이트를 그리지 못했다(값 처리기 없음). Swing은 그린다.

## 확인 (2026-11-08)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → `BUILD SUCCESS`. `python tools/rename/gate.py` → `ALL GATES PASSED`.
- 실행: 빌드한 Swing 제품을 따로 복사해 별도 데이터 폴더(`CHROMITE_DATA`)·작업 공간으로 열고, 시험용 Java 에이전트(제품에 들어가지 않음)로 EDT에서 단추·콤보·메뉴를 누르고
  보기에 마우스 이벤트를 직접 보냈다(화면·키보드 입력 없음). 화면은 구성 요소를 이미지로 그려 확인했다. 시험 DB는 로컬 Docker의 공용 컨테이너에 만든
  `chr_charts_spatial`(PostgreSQL 17 `sales`, SQL Server 2022 `shapes`·`sales`, MariaDB 11 `places`)이고 확인 뒤 지웠다.
  PostgreSQL 컨테이너에 PostGIS가 없어(`pg_available_extensions`에 없음) `DBGeometry` 경로는 MariaDB(`MySQLGeometryValueHandler`)로 확인했다.
  - **차트(PostgreSQL):** 결과 전체 → 가로축 `month`(날짜, 선), 값 `amount, units, ratio`. 마우스 위치의 값 상자, 막대(띠 강조), 원형(8조각 넘음 → "기타(3)", 0 이하·빈 값 2개 뺌 알림).
    칸 고르기(행 0–5, region·amount) → 가로축 `region`, 값 `amount`, "선택한 행(6)", 막대. PNG 저장(파일 대화상자 제목 "차트를 PNG로 저장", 마우스 강조 없는 그림).
    숫자 열 없는 결과 → "The result has no numeric columns to chart.", 값·PNG 단추 꺼짐.
  - **차트(SQL Server):** `GROUP BY region` 결과 → 막대·원형(North 67%, South 33%).
  - **공간(SQL Server):** `geometry` 9행 → 기하 8개(구멍 있는 다각형, MultiPoint, GeometryCollection, MultiPolygon, CircularString(호), Z 점, 선), `POINT EMPTY` 1개 "비어 있음".
    `geography` → SRID 4326, 경도 가로·위도 세로(서울·부산 선이 맞는 방향), NULL 5개. 다각형을 누르면 표의 3번째 행이 선택되고 주황으로 그려짐, 도구 설명 "행 3: Polygon(64.71, 31.76)".
    휠 확대, 맞추기.
  - **공간(MariaDB, 편집할 수 있는 결과):** 기하 5개, NULL 1개, SRID 4326. 처음 빌드는 0개(위 "편집 모델의 빈 값 객체")였고 고친 뒤 5개.
  - 라이트·다크(보기 > 테마 전환 뒤 차트·공간 보기·저장한 PNG), 한국어·영어(`-nl en`) 문구.
- 확인하지 못한 것: PostGIS(컨테이너에 확장 없음), 메모리에 없는 LOB로 오는 기하(Oracle 등), Fetch all 뒤 차트 다시 그리기(코드 경로만: `installGrid`가 `chartView.refresh()`), macOS.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 표의 값 패널(기하·처리기가 바뀐 LOB 칸), 레코드 보기 전환, 선택 집계 표시, "Filter by Value" 메뉴.

## 남긴 것

- 차트: 막대 쌓기, 가로 막대, 산점도, 두 축, 차트 복사(클립보드)·인쇄, 범주별 합계(GROUP BY 없이 같은 범주 묶기). 지금은 행마다 한 점이라 같은 범주가 여러 번 나온다.
- 공간: 좌표계 변환(예: 4326 → 3857), 밑그림(오프라인 타일·해안선), 기하 값 편집, `gis.view.*` 설정(최대 개수·기본 SRID), Z·M 값 보기, PNG 저장.
- SQL Server 공간 값을 모델 값 처리기로 읽어 표에도 WKT로 보이기(표시·편집·내보내기가 함께 바뀌므로 따로 계획).

## 변경 이력

- 2026-11-08 최초 작성: 결과 표 차트 보기(선·막대·원형, 축 고르기, PNG 저장), 공간 보기(DBGeometry, SQL Server geometry/geography, 밑그림 없음).
