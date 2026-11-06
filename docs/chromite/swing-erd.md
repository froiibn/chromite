# Swing ER 다이어그램

## 목적

Object Explorer에서 스키마나 테이블을 골라 **View Diagram**(다이어그램 보기)을 누르면 테이블과 외래 키 관계를 ER 다이어그램으로 보여 준다.
SSMS의 "Database Diagrams", SWT 버전의 "View Diagram"(ERD 편집기)에 해당한다. SWT의 ERD 편집기(`ui.editors.erd`)는 GEF/draw2d(SWT)로 만들어져
Swing 제품에 넣을 수 없으므로, SWT 의존이 없는 모델 번들 `org.jkiss.dbeaver.model.erd` 위에 Java2D로 다시 만들었다.

## 기능

| 기능 | 내용 |
|---|---|
| 열기 | Object Explorer 오른쪽 클릭 → View Diagram. 스키마(또는 스키마 없는 DB의 카탈로그): 그 안의 모든 테이블·뷰. 테이블: 그 테이블과 외래 키로 이어진 테이블(참조하는 쪽, 참조되는 쪽). 같은 객체의 탭이 열려 있으면 그 탭을 보인다 |
| 상자 | Islands 카드 모양(둥근 사각형, 머리글 띠). 머리글: 테이블·뷰 아이콘과 `스키마.테이블`. 열 행: 기본 키·외래 키·일반 열 아이콘, 이름, 형식(`형식 NOT NULL`). 고른 상자와 기준 테이블(테이블에서 연 경우)은 강조색 테두리 |
| 관계선 | 직각으로 꺾은 선, 까마귀발 끝 모양(외래 키 쪽 0..n 또는 1, 기본 키 쪽 1 또는 0..1). 선 위에 마우스를 올리면 굵게 강조하고 도구 설명에 `외래 키 이름: 자식(열) → 부모(열)` |
| 자동 배치 | 관계로 이어진 묶음은 층 배치(참조되는 테이블이 왼쪽), 관계 없는 테이블은 격자. 언제나 같은 결과(결정적) |
| 화면 다루기 | Ctrl+휠 확대·축소(마우스 위치 고정), 휠 스크롤(Shift+휠 좌우), 빈 곳 끌기 = 화면 이동, 상자 끌기 = 이동, Ctrl+클릭 = 여러 개 고르기, Ctrl+= / Ctrl+- / Ctrl+0 |
| 도구 막대 | 축소, 비율(누르면 100%), 확대, 창에 맞추기 / 자동 레이아웃 / 열: 모두·키만·없음, 데이터 형식 표시 / 새로 고침, 레이아웃 저장, 내보내기 |
| 내보내기 | PNG(두 배 해상도), SVG(직접 쓰는 SVG), 다이어그램 파일(.erd, SWT 형식) |
| 배치 저장 | 레이아웃 저장 단추. 연결 설정의 가상 모델(SWT와 같은 곳)에 저장하고 다음에 열 때 그 위치를 쓴다 |
| 상자 메뉴 | 두 번 클릭 = 개체 탐색기에서 표시. 오른쪽 클릭: 개체 탐색기에서 표시, 디자인(테이블 디자이너), 상위 1000개 행 선택 |
| 빈 곳 메뉴 | 자동 레이아웃, 창에 맞추기, 내보내기 3가지 |

## 구조

| 클래스 (`com.seizia.chromite.ui.swing`) | 역할 |
|---|---|
| `ErdDiagramPanel` | 문서 탭(`ToolDocument`): 도구 막대, 로드 작업, 저장, 내보내기, 메뉴 |
| `ErdDiagramModel` | 모델 API로 읽은 다이어그램 내용(테이블, 열, 관계, 끝 모양, 저장된 배치). 그릴 값은 모두 여기서 복사해 EDT가 DB 메타데이터를 읽지 않는다 |
| `ErdLayout` | 결정적 자동 배치(연결 요소 → 층 → barycenter 정렬 → 좌표 → 선반 배치) |
| `ErdRenderer` | 상자 크기 재기, 관계선 경로, 그리기, 마우스 위치 찾기, 테마 색 |
| `ErdPainter` | 그리기 대상: `Java2D`(화면·PNG), `Svg`(SVG 파일) |
| `ErdCanvas` | 스크롤 창 안의 캔버스: 확대, 화면 이동, 고르기, 끌기, 도구 설명 |
| `ErdDiagramState` | .erd XML(버전 1) 읽기·쓰기, 가상 모델 속성에 저장 |
| `internal/ErdMessages` | 화면 문구(영어 기본, `_ko` 한국어) |

- 연결 고리: `ObjectExplorerPanel`의 View Diagram 동작(활성 조건 `ErdDiagramModel.canShow`)과 `QueryWindows.viewDiagram`,
  `MainFrame.viewDiagram`(같은 객체 탭 재사용, 개체 탐색기 표시 콜백). 탭 닫기는 기존 `ToolDocument` 처리를 그대로 쓴다.
- `MANIFEST.MF`: `org.jkiss.dbeaver.model.erd`를 `Require-Bundle`에 더했다. 이 번들은 `model`, `model.sql`만 요구하고
  이미 `org.jkiss.dbeaver.runtime.feature`에 들어 있어 Swing 제품에 SWT가 들어오지 않는다.
- 아이콘: `diagram`, `zoom-in`, `zoom-out`, `zoom-fit`, `layout`을 아이콘 생성기(`tools/branding/gen_ui_icons.py`)에 더했다(두 팩 × 두 테마).
- 설정(이 번들 설정 저장소): `erd.columns`(ALL·KEYS·NONE), `erd.showTypes`. 모든 다이어그램의 기본값이다.

## SWT에서 가져온 것과 바꾼 것

| SWT (ui.editors.erd) | Swing |
|---|---|
| `ViewDiagramHandler` → `ERDEditorEmbedded.loadFromDatabase` (ERDEditorEmbedded.java:228-297) | `ErdDiagramModel.load`: 같은 `ERDUtils.collectDatabaseTables`, `ERDDiagram.fillEntities`, `ERDContentProviderDefault`. 뷰 포함·파티션 제외는 SWT 기본 설정값(ERDPreferencesInitializer.java:34-35) |
| `ERDEditorEmbedded.doSave`, `isStateSaved` (ERDEditorEmbedded.java:300-337): 가상 모델 속성 `erd.diagram.state` → `serialized` | `ErdDiagramState.save/read`: 같은 키, 같은 형식. SWT와 Swing이 같은 배치를 연다 |
| `DiagramLoader.serializeDiagram`/`loadDiagram` (DiagramLoader.java:407-610, 140-405) | `ErdDiagramState.serialize/parsePositions`: 버전 1 XML의 entities(data-source, entity, path, x, y)와 relations만 다시 구현(draw2d 의존 때문). 색·글꼴·꺾인 점·메모는 Swing 화면에 없어 쓰지 않는다 |
| `CrowsFootDiagramNotation.applyNotationForArrows` (CrowsFootDiagramNotation.java) | `ErdDiagramModel.makeLink`: 같은 규칙(외래 키 열이 고유 인덱스면 1, 아니면 0..n / 외래 키가 NULL 허용이면 0..1, 아니면 1, 키-키 관계는 0..1) |
| `ERDUIUtils.getFullAttributeLabel` (ERDUIUtils.java:108-140): `이름: 형식 NOT NULL` | 이름과 `형식 NOT NULL`을 두 열로 맞춰 그림 |
| `GraphLayoutAuto`/`OrthoDirectedGraphLayout`(draw2d 그래프 배치) | `ErdLayout`(직접 구현한 층 배치). 같은 생각(관계 방향 층)이지만 draw2d가 없으므로 새로 만들었다 |
| 두 번 클릭 = 테이블 편집기 | 두 번 클릭 = 개체 탐색기에서 표시(Swing에는 테이블 편집기가 없고, SSMS처럼 탐색기로 보냄) |
| 이미지 저장(PNG·JPG·BMP·SVG via draw2d) | PNG(Java2D, 두 배), SVG(직접 씀), .erd 파일 |

## 설계 결정과 이유

- **배치 저장 위치:** 작업의 요구 "model.erd가 SWT 없이 .erd 형식을 지원하면 프로젝트 .erd 파일, 아니면 작업 공간 설정"을 확인했다.
  model.erd에는 형식 상수(`ERDPersistedState`)만 있고 읽고 쓰는 코드는 SWT 번들에 있다. 그래서 형식을 UI 없이 다시 구현했고,
  "View Diagram"의 배치는 SWT와 같은 곳(루트 객체의 가상 모델 속성)에 둔다. 작업 공간 설정보다 낫다: 연결 설정과 함께 저장되고(내보내기·공유 포함)
  SWT 화면과 배치를 같이 쓴다. 별도로 같은 XML을 .erd 파일로 내보낼 수 있다(대화상자는 프로젝트 `Diagrams` 폴더가 있으면 거기서 시작).
- **자동 저장하지 않음:** SWT처럼 저장 단추를 눌러야 저장한다(옮겨 보기만 하는 경우가 많다). 바뀐 뒤에는 상태 글자가 "레이아웃 변경됨(저장 안 됨)"이다.
- **결정적 배치:** 입력 순서를 제목 순으로 정렬하고, 순환 참조 끊기·위상 정렬·층 안 정렬에서 같으면 늘 번호 순으로 고른다. 난수와 해시 순서를 쓰지 않는다.
  상자를 끌어 맨 위로 올려도 그리는 순서만 바꾸고 모델 순서는 둔다.
- **열 표시를 바꾸면:** 상자 크기가 바뀌므로 자동 배치 상태면 다시 배치한다. 사용자가 옮겼거나 저장된 배치면 위치를 둔다.
- **SVG를 직접 씀:** Batik 같은 Graphics2D→SVG 라이브러리를 들이지 않으려고 그리기 동작을 `ErdPainter` 네 가지(채우기, 선, 글자, 아이콘)로 줄였다.
  도형은 `PathIterator`로 path를 그대로 옮긴다. 아이콘은 아이콘 팩 SVG를 `<symbol>`로 한 번 넣고 `<use>`로 쓴다(파일마다 같은 mask id `cut`을 symbol마다 다른 이름으로 바꿈).
  글자는 `font-family`로 쓰므로 받는 쪽에 Inter가 없으면 대체 글꼴로 보인다.
- **테마 색:** 테마 파일에 새 키를 더하지 않고 기존 키를 쓴다. 캔버스 `Chromite.PropertyGrid.categoryBackground`와 점 격자 `Chromite.dividerColor`,
  상자 `Chromite.panelBackground`·`Component.borderColor`, 머리글 `Chromite.island.tabSelectedBackground`, 선·형식 글자 `Chromite.secondaryForeground`,
  강조 `Component.focusColor`. 그릴 때마다 읽으므로 테마를 바꾸면 바로 따른다. PNG·SVG도 지금 테마 색이다.
- **"상위 1000개 행 선택" 행 수:** Object Explorer의 상수(`ObjectExplorerPanel.SELECT_TOP_ROWS`)를 같이 쓰려고 package-private으로 바꿨다.

## 확인 (2026-11-06)

전체 빌드(`product-dbeaver-ce,product-chromite-swing`) 뒤 Swing 제품을 개인 작업 공간으로 띄워 EDT 테스트 에이전트로 확인했다.
검증용 DB는 각 서버에 따로 만들었다: PostgreSQL `chr_erd`의 `shop`, SQL Server `chr_erd`의 `sales`(각 10개 테이블 + 뷰 1개,
자기 참조 2, 복합 기본 키, 1:1(PK=FK, UNIQUE FK), NULL 허용 FK, 관계 없는 테이블), Oracle `CHR_ERD`(8개 테이블).

| 항목 | 결과 |
|---|---|
| View Diagram 메뉴 | 스키마·테이블에서 켜지고, 연결·폴더에서는 꺼짐. PostgreSQL 스키마 11개 상자·관계 11개, SQL Server 같음, Oracle 8개·8개 |
| 테이블에서 열기 | `sales.orders`: orders와 관련 테이블 5개(customers, addresses, employees, order_items, payments), 관계 7개. 기준 테이블 강조 테두리 |
| 끝 모양 | 일반 FK 0..n → 1, NULL 허용 FK(parent_id, manager_id, shipping_address_id, sales_rep_id) → 0..1, UNIQUE FK(payments.order_id)와 PK=FK(customer_profiles) → 1 |
| 자동 배치 | 같은 다이어그램을 다시 열어도 같은 좌표(두 번 실행 비교). 층을 건너는 선은 사이 상자를 피해 통로로 돌아감 |
| 마우스 | 관계선 도구 설명 `외래 키 orders_customer_id_fkey: shop.orders(customer_id) → shop.customers(id)`, 상자 끌기(확대 40%에서 60px → 149 다이어그램 px), 두 번 클릭 → 개체 탐색기에서 `payments` 선택 |
| 메뉴 | 상자: 개체 탐색기에서 표시·디자인(테이블 디자이너 탭 열림)·상위 1000개 행 선택(쿼리 창에서 실행). 빈 곳: 자동 레이아웃·창에 맞추기·내보내기 |
| 열 표시 | 키만: 기본 키·외래 키 열만, 상자 크기 다시 재고 자동 배치 |
| 레이아웃 저장 | 저장 → 앱을 다시 시작해 열면 옮긴 위치 그대로(가상 모델에서 읽음). .erd 내보내기의 path·x·y·relation이 SWT 형식과 같음 |
| 내보내기 | PNG(두 배, 라이트·다크), SVG(JSVG로 다시 그려 확인), .erd |
| 화면 언어·테마 | 한국어(OS 기본)·영어(`-nl en`), 라이트·다크. 한국어 도구 막대 문구가 잘리지 않음 |

스크린숏과 내보낸 파일: `D:\Tools\_scratch\erd\shots\`(저장소 밖).

## 남긴 것

- .erd 파일 열기(파일 메뉴에서 SWT 다이어그램 파일을 여는 편집기), 다이어그램에 테이블 더하기·빼기, 메모, 꺾인 점 편집, 색·글꼴 바꾸기.
- 인쇄(요구 범위 밖).
- 관계 이름을 선 위에 늘 표시하기(지금은 도구 설명).

## 변경 이력

- 2026-11-06 최초 작성: View Diagram(스키마·테이블), Java2D 다이어그램 탭, 자동 배치, 확대·이동·고르기·끌기, 까마귀발 관계선과 도구 설명,
  열 표시·형식 표시, PNG·SVG·.erd 내보내기, SWT와 같은 가상 모델 속성에 배치 저장, 개체 탐색기 표시·디자인·상위 1000개 행 선택 메뉴.
