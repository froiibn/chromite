# Swing 결과 탐색 (외래 키 따라가기, 참조·집계 패널, 가상 키, 열 배치, 찾기, 텍스트 보기, 행 수, 오류 위치)

## 목적

SWT 결과 뷰어의 탐색 기능 중 Swing 결과 뷰어([swing-result-viewer.md](swing-result-viewer.md))에 없던 것을 옮긴다.
SWT 대조 4순위 B4 작업이고 대상 항목은 다음과 같다([swt-parity/B-editors.md](swt-parity/B-editors.md)).

| 항목 | 내용 |
|---|---|
| B-R7 | 외래 키 따라가기(참조되는 행, 참조하는 행), 뒤로·앞으로, 참조 패널 |
| B-R22 | 가상 고유 키(키 없는 테이블 편집), 가상 외래 키 |
| B-R13 | 전체 행 수 세기 |
| B-R24 | 열 숨기기·모두 보이기·빈 열 숨기기, 열 순서(머리글 끌기), 행·열로 가기, 확대·축소 |
| B-R25 | 결과 안 찾기 |
| B-R6 | 집계 패널(함수 고르기, 열마다·전체) |
| B-R27 | 오류 위치로 가기 |
| B-R2 | 텍스트 보기 |

사용자 규칙 "변경을 실행하는 확인은 Cancel이 기본 단추", "묻지 않고 커밋·롤백하지 않음"을 따른다. 이 기능은 데이터를 바꾸지 않는다
(가상 키는 연결 설정에만 저장). 탐색 전에 저장하지 않은 변경이 있으면 버릴지 묻는다(기존 정렬·필터와 같은 확인).

## 구조

Swing 클래스는 모두 `com.seizia.chromite.ui.swing` 번들이다. 새 번들·새 의존은 없다. 모델은 `SQLQueryDataContainer.countData` 하나를 고쳤다(아래 "전체 행 수").

| 클래스 | 새/변경 | 역할 |
|---|---|---|
| `ResultSetViewerPanel` | 변경 | 외래 키 따라가기와 기록(뒤로·앞으로), 옆 패널 탭(참조·집계), Navigate·Columns·Virtual Keys 메뉴, 단축키, 행 수 세기, 텍스트 보기 카드, 찾기 줄, 표를 다시 만들 때 열 배치·확대 옮기기 |
| `ResultSetReferences` | 새 | 결과의 외래 키 관계 읽기(실제·가상, 앞·뒤), 대상 행 필터(모델 `DBDReferenceUtils`), 읽기 전용 결과의 행 모델(`RowsModel`) |
| `ResultSetReferencesPanel` | 새 | 참조 패널: 관계 고르기, 고른 행의 관련 행을 결과 뷰어 하나로 보임, "열기" |
| `ResultSetAggregatePanel` | 새 | 집계 패널: 고른 칸을 열마다/전체로 모델 집계 함수로 계산 |
| `ResultSetColumnLayout` | 새 | 열 숨기기·보이기·빈 열, 배치 저장·복원, 행·열로 가기, 확대·축소 |
| `ResultSetFindBar` | 새 | 결과 안 찾기 줄 |
| `ResultSetTextView` | 새 | 텍스트 보기 |
| `ResultSetVirtualKeys` | 새 | 가상 고유 키 대화상자, 가상 외래 키 추가·지우기, 가상 모델 저장 |
| `ResultSetGrid` | 변경(작은 연결점) | 읽기 전용 줄에 "가상 키 정하기" 링크(`Host.defineVirtualKey`), `cancelChanges`를 패키지 공개로 |
| `DesignerDialogs` | 변경 | 외래 키 대화상자에 가상 모드(이름·규칙 없음, 참조 테이블의 가상 고유 키 포함) |
| `QueryResultsView` | 변경 | 오류 글자에 위치 속성, 두 번 누르기·오른쪽 클릭 "오류 위치로 가기", Messages 복사 |
| `SqlEditorPanel` | 변경 | 오류 머리글의 위치를 편집기 오프셋으로 넘김(`ErrorReport`), `goToSource` |
| `internal/GridNavMessages` (+ `.properties`, `_ko.properties`) | 새 | 이 기능의 화면 문구(영어·한국어) |

```
ResultSetViewerPanel
 ├ ResultSetFindBar (위, Ctrl+F에 보임)
 ├ JSplitPane(가로)
 │  ├ JSplitPane(가로): 카드(표 | 레코드 | 텍스트 | 차트 | 공간) | 값 패널
 │  └ JTabbedPane: 참조(ResultSetReferencesPanel) | 집계(ResultSetAggregatePanel)   ← 결과 오른쪽, 켠 패널이 없으면 숨김
 └ 상태 줄: ← → [테이블 (키)] 행 수 · 숨긴 열 | 다음 N개 · 모두 읽기 · 행 수 세기 | … | 값 · 레코드 · 텍스트 · 차트 · 공간 · 참조 · 집계
```

## 설계 결정과 이유

### 외래 키 따라가기와 기록 (B-R7)

SWT: `ResultSetViewer.navigateAssociation`·`navigateReference`(기준 커밋 `263b8ddb45`의 ResultSetViewer.java:3981, 4012), `ResultSetReferenceMenu`,
`navigateHistory`(:4080), 모델 `DBDReferenceUtils`.

- **관계 읽기:** 앞으로(이 결과의 열 → 다른 테이블)는 `DBVUtils.getAllAssociations`(실제 외래 키 + 가상 외래 키), 뒤로(다른 테이블 → 이 결과의 키)는
  `DBStructUtils.readReferences`(실제 참조 + 다른 테이블의 가상 외래 키)다. 관계의 열이 모두 결과에 있어야 한다(SWT와 같은 조건).
  관계 목록은 메뉴·참조 패널이 처음 쓸 때 백그라운드 Job에서 한 번 읽고, 결과의 테이블이 같으면(정렬·필터·다음 페이지) 다시 읽지 않는다.
  읽다 난 오류는 SWT처럼 보이지 않고 "없음"으로 둔다(DB가 외래 키 메타데이터를 주지 않는 경우).
- **칸에서 바로(Ctrl+B):** 칸의 열이 묶인 외래 키는 결과를 받을 때 모델이 이미 읽어 둔다(`DBDAttributeBinding.getReferrers`, `lateBinding`).
  그래서 Ctrl+B와 Navigate 메뉴의 "참조되는 행: 테이블 (키)"는 DB를 읽지 않고 바로 나온다. 열에 외래 키가 여럿이면 고르는 메뉴를 띄운다.
  SWT의 Alt+Space는 Windows에서 창 메뉴를 열어 쓰지 않았고, DataGrip의 "Go to referenced data"(Ctrl+B)를 따랐다.
  Ctrl+Shift+1(SWT `referencesMenu`)은 고른 칸 자리에 참조되는·참조하는 테이블 메뉴를 띄운다.
- **대상 행:** 고른 행들의 키 값으로 모델의 `DBDReferenceUtils`가 필터를 만든다(한 행 `=`, 여러 행 `IN`, 여러 열 키의 여러 행은 `(a = 1 AND b = 2) OR …`).
  편집할 수 있는 결과는 편집 모델, 읽기 전용 결과는 수신기가 남긴 칸 값(`ResultSetReferences.RowsModel`)이 키 값을 준다. 행은 1,000개까지 쓴다(Oracle IN 목록 한도).
- **이 뷰어에서 읽기:** SWT처럼 대상 테이블을 데이터 컨테이너로 같은 뷰어에서 읽는다. 새 결과는 테이블의 결과라 정렬·필터·다음 페이지·편집(키가 있으면)이
  그대로 된다. 따라간 조건은 그 결과의 열 필터가 되어 상태 줄 필터 요약에 보이고 ✕로 지울 수 있다. 여러 열 키의 OR 형식이 다시 읽기에서 풀리지 않게
  필터를 새 열에 옮길 때 `useDisjunctiveNormalForm`도 옮긴다(`rebindFilter`, 이 작업에서 고침: 전에는 옮기지 않았지만 그때는 그런 필터가 없었다).
  테이블의 결과에는 쿼리가 없으므로 "Export Data…"는 꺼진다(마법사가 쿼리만 다시 실행).
- **뒤로·앞으로(Alt+←/→, 상태 줄 ←/→, Navigate 메뉴):** 떠난 결과를 그대로(행, 편집 모델, 필터, 정렬, 행 수) 기록에 두고 돌아갈 때 다시 읽지 않고 보인다.
  SWT는 다시 읽지만, Swing의 첫 결과는 다시 읽을 수 없는 결과(GO 배치, 여러 문장)일 수 있고 다시 실행하면 안 되는 문장일 수도 있어서 남겨 둔 것을 보인다.
  떠날 때 저장하지 않은 변경은 "버릴까요?"에 답한 대로 버린다(`ResultSetGrid.cancelChanges`): 돌아오면 읽은(저장한) 값이다.
  새로 따라가면 앞으로 갈 기록은 지운다(브라우저와 같음). 기록의 LOB 값은 뷰어를 닫거나 기록에서 지울 때 놓아 준다.
  상태 줄에 지금 테이블과 따라온 키("→ public.customers (fk_orders_customer)", 거꾸로 따라왔으면 ←)를 보인다. 열 배치는 테이블마다 처음부터다.

### 참조 패널 (B-R7)

SWT: `ReferencesPanel`, `ReferencesResultsContainer`.

- 결과 오른쪽 탭이다(결과 창은 넓고 낮아서 아래에 두면 표와 패널이 모두 몇 줄밖에 보이지 않았다. SWT도 오른쪽). 위 콤보에서 관계(→ 참조되는 테이블, ← 참조하는 테이블)를 고르고, 결과 표에서 고른 행이 바뀌면 0.3초 뒤에 그 행들의 관련 행을 읽는다.
  같은 행에서 다른 열로 옮기기만 하면 다시 읽지 않는다. 고른 관계는 뷰어가 열려 있는 동안 기억한다(SWT는 가상 모델 속성에 저장하지만, 연결 설정 파일을
  바꾸지 않으려고 메모리에만).
- 관련 행은 결과 뷰어 하나로 보인다(정렬·필터·다음 페이지·값 패널 그대로). 읽기 전용이다: 선택이 바뀔 때마다 새로 읽으므로 고치다 만 변경이 묻지 않고
  사라지지 않게 했다. "열기"는 결과 뷰어 자체를 그 행으로 옮긴다(외래 키 따라가기와 같고 Alt+←로 돌아옴). SWT 패널은 편집할 수 있다.

### 가상 키 (B-R22)

SWT: `ValidateUniqueKeyUsageDialog`, `VirtualUniqueKeyEditAction`, `VirtualForeignKeyEditAction`, `EditForeignKeyPage.createVirtualForeignKey`.

- **저장 위치:** 모델이 하던 대로 연결의 가상 모델(`DBVEntity`의 `VIRTUAL_KEY` 제약, `DBVEntityForeignKey`)에 넣고 `persistConfiguration()`으로
  연결 설정(data-sources.json의 가상 메타데이터)에 쓴다. DB는 바꾸지 않는다. SWT로 정한 가상 키도 그대로 읽힌다.
- **가상 고유 키:** 기본 키·고유 키·고유 인덱스·ROWID가 없는 테이블의 결과는 "읽기 전용: 고유 키 없음"이었다. 그 줄에 "가상 키 정하기" 링크가 생기고
  (오른쪽 클릭 Virtual Keys에도), 결과에 보이는 그 테이블의 열을 고르거나 "모든 열 쓰기"(SWT "Use all columns")를 고른다. 저장하면 결과를 다시 읽고,
  모델이 행 식별자로 그 키를 써서(`DBExecUtils`의 식별자 찾기: 실제 키가 없을 때만 가상 키) 편집할 수 있게 된다. 다시 읽을 수 없는 결과는 "쿼리를 다시
  실행하면 쓰입니다"를 알린다. 실제 키가 있는 테이블에서는 메뉴가 꺼진다. 대화상자의 기본 단추는 Cancel이다(설정을 바꾸는 대화상자). "키 지우기"도 있다.
- **가상 외래 키:** 테이블 디자이너의 외래 키 대화상자를 가상 모드로 다시 쓴다(`DesignerDialogs.editForeignKey(..., virtual=true)`: 이름·ON DELETE/UPDATE 없음,
  참조할 키에 참조 테이블의 가상 고유 키도 들어감). 참조할 테이블은 같은 스키마의 테이블과 뷰다. 저장은 진행 대화상자에서 한다(가상 외래 키는 참조 테이블의
  탐색기 노드 경로를 남기므로 노드를 찾음). 저장하면 결과를 다시 읽어 칸의 외래 키(Ctrl+B)와 참조 메뉴에 나온다. 지우기는 확인(기본 Cancel) 뒤에 한다.
- **가상(계산) 열**은 2026-11-08 [swing-saved-filters-virtual-columns.md](swing-saved-filters-virtual-columns.md) "가상 열"에서 했다. 가상 키·가상 열을 바꾼 뒤
  다시 읽는 메서드는 하나다(`readAgainForVirtualModel`).

### 열 배치와 이동 (B-R24)

- **숨기기:** 머리글·표 오른쪽 클릭 "열 숨기기"(고른 열 모두), "모든 열 보이기(N개 숨김)", "빈 열 숨기기"(읽은 행이 모두 NULL이거나 빈 글자인 열).
  숨기기는 표의 열 모델에서 열을 빼는 것이라 값·편집·저장·복사·내보내기는 모델 열 번호로 그대로다. 숨긴 열 수는 상태 줄 행 수 옆에 보인다.
- **순서:** 머리글을 끌어 옮긴다(SWT moveColumnLeft/Right 대신). 끌기는 머리글 클릭 정렬로 치지 않는다.
- **유지:** 정렬·다음 페이지는 표를 새로 만들므로 배치와 너비를 옮기고, 필터·서버 정렬로 다시 읽은 같은 열의 결과는 숨김과 순서만 옮긴다(너비는 다시 맞춤).
  외래 키로 다른 테이블로 가면 처음 배치다.
- **행·열로 가기:** Ctrl+G(SWT gotoRow)는 읽은 행의 번호, Ctrl+Shift+G(SWT gotoColumn)는 이름으로 거르는 열 목록이다. 숨긴 열을 고르면 다시 보인다.
- **확대·축소:** Alt+0 / Alt+9(SWT zoomIn/zoomOut 단축키), 메뉴의 되돌리기. 표 글꼴과 행 높이를 10%씩, -50%~+100%. 이 뷰어 안에서만 유지한다.
- **레코드 보기 고침:** 레코드 보기의 줄은 모델 열인데 뷰어가 표의 열 번호처럼 바꿔 써 왔다(열을 숨기거나 옮기면 값 패널이 다른 칸을 보였을 것). 이제 모델 열로 쓴다.
- **열 고정(pin)은 하지 않았다**(아래 "남긴 것").

### 결과 안 찾기 (B-R25)

SWT: `ResultsetFindReplaceOverlay`, `SpreadsheetFindReplaceTarget`.

- Ctrl+F(오른쪽 클릭 찾기…)가 결과 위에 찾기 줄을 연다(고른 칸의 짧은 글자를 처음 찾는 말로). 표에 보이는 글자(보이는 열, 표의 열 순서)에서 고른 칸 다음부터
  찾고 끝에 닿으면 처음부터다. NULL 칸은 건너뛴다. 대소문자 구분을 고를 수 있다. "3 / 17"처럼 몇 번째인지 보인다(2,000,000칸이 넘으면 세지 않음).
- Enter/F3 다음, Shift+Enter/Shift+F3 이전, Esc 닫기. 읽은 행만 찾는다. 바꾸기는 하지 않았다(값 바꾸기는 셀 편집·붙여넣기로).

### 집계 패널 (B-R6)

SWT: `AggregateColumnsPanel`.

- 결과 오른쪽 탭(참조 패널과 같은 자리). 고른 칸을 "열마다"(한 줄에 한 열) 또는 전체 한 줄로, 고른 함수로 집계한다. 함수는 개수, 서로 다른 값, NULL 수, 합계, 평균, 최솟값, 최댓값,
  중앙값, 최빈값(모델 `com.seizia.chromite.model.data.aggregate`)이고 기본은 개수·합계·평균·최솟값·최댓값이다. 고른 함수는 이 실행 동안 기억한다.
- 상태 줄 집계와 같은 규칙: 합계·평균·중앙값은 숫자 값만(코드 열의 "001"이 합계에 들어가지 않게, SWT는 글자도 숫자로 읽어 봄). 최솟값·최댓값은 숫자·날짜·글자를
  비교하고 글자·날짜는 표에 보이는 글자로 쓴다. EDT에서 계산하므로 100,000칸까지 센다.

### 텍스트 보기 (B-R2)

SWT: `PlainTextPresentation`(Ctrl+` switchPresentation).

- 상태 줄 "텍스트"(Ctrl+`, macOS에서도 Control+`: ⌘`는 창 순환)가 표 자리에 고정폭 글자로 열을 맞춘 결과를 보인다(머리글, `-` 줄, 숫자 오른쪽 맞춤, NULL은 `NULL`). 보이는 열과 표의 순서를 따른다.
- 열 너비는 가장 긴 값까지 최대 255자(SWT 기본), 한글·한자는 두 칸으로 센다. 20,000행까지 쓴다. 읽기 전용이고 골라 복사한다.
  코드 글꼴의 한글 글자 폭이 정확히 영문 두 글자가 아니어서 한글이 든 줄은 화면에서 조금 어긋난다(복사한 글은 맞음, 2026-11-08 PostgreSQL에서 확인).

### 전체 행 수 (B-R13)

SWT: `resultset.count`, `ResultSetViewer.readRowCount`.

- 더 읽을 행이 있으면 상태 줄에 "행 수 세기"가 나온다(Navigate 메뉴에도). 모델의 `DBUtils.readRowCount`(컨테이너의 `countData`, 쿼리는 `SELECT COUNT(*)`로 감쌈)로
  지금 필터 조건 그대로 센다. 그 뒤 상태 줄은 "전체 12,345행 중 200행 읽음"이다. 필터를 바꾸거나 다른 테이블로 가면 다시 세야 한다.
  다시 읽을 수 없는 결과(여러 문장, 프로시저)는 세지 않는다(같은 이유로 다시 실행하지 않음).
- **모델 고침:** 쿼리 결과의 컨테이너(`SQLQueryDataContainer`)는 `FEATURE_DATA_COUNT`를 내세우면서 `countData`가 늘 -1이었다(상태 줄에 "전체 -1행").
  이제 필터 조건(정렬은 뺌)을 방언의 `addFiltersToQuery`로 넣고, 모델의 `SQLQueryTransformerCount`(SWT가 쿼리 결과를 셀 때 쓴 것)로 COUNT 쿼리를 만들어
  같은 컨테이너의 `readData`로 읽는다. 그래서 매개변수는 행을 읽을 때처럼 채우거나 바인딩한다(`FLAG_REFRESH`: 다시 묻지 않음). SELECT 하나가 아니면 세지 않는다
  (감싸면 그 문장이 실행되므로). 데이터 전송의 "행 수 세기"(기본 켬)도 이 메서드를 써서, 쿼리 내보내기 전에 COUNT 쿼리를 하나 더 실행한다(SWT와 같음).

### 오류 위치로 가기 (B-R27)

SWT: `GoToErrorAction`, `ErrorPresentation`.

- Messages의 오류 머리글("Msg 208, Line 3, Column 15")은 이미 방언의 오류 도우미가 준 위치를 썼다. 이제 그 위치를 편집기 글의 오프셋으로 함께 남기고,
  오류 글자를 두 번 누르거나 오른쪽 클릭 "오류 위치로 가기"로 쿼리 창의 그 자리에 커서를 두고 줄을 오류 색으로 표시한다(기존 `showSourcePosition`).
  오류 글자 위에서는 손 모양 커서와 도구 설명이 보인다. `@include`로 읽은 파일의 오류는 창의 글이 아니라서 위치를 남기지 않는다.
- 위치는 실행 때의 글 기준이다. 그 뒤 글을 고쳤으면 어긋날 수 있다(SSMS와 같음). Messages 오른쪽 클릭에 복사도 넣었다(메뉴가 생겨서).

## upstream 대비 변경점

- 모델은 `SQLQueryDataContainer.countData`만 바꿨다: upstream은 -1을 돌려주었고 SWT는 SQL 편집기의 자기 컨테이너(`QueryResultsContainer`)로 셌다.
- SWT와 다른 동작:
  - 뒤로·앞으로는 다시 읽지 않고 남겨 둔 결과를 보인다.
  - Ctrl+B로 참조되는 행(SWT Alt+Space).
  - 참조 패널은 읽기 전용이고 "열기"로 편집한다. 고른 관계는 설정 파일에 남기지 않는다.
  - 집계 패널은 숫자 값만 합계·평균에 넣는다.
  - 열은 머리글을 끌어 옮긴다(왼쪽·오른쪽 옮기기 명령 없음).
  - 열 고정, 그룹 패널, 바꾸기 없음.

## 확인 (2026-11-08)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → `BUILD SUCCESS`, `python tools/rename/gate.py` → `ALL GATES PASSED`.
- 실행: 빌드한 Swing 제품을 `D:\Tools\_scratch\chromite-p4-grid-navigation`에 복사해 `CHROMITE_DATA`와 작업 공간을 그 폴더에 두고 열었다.
  화면 조작은 실행 중인 앱 안의 시험용 Java 에이전트(제품에 들어가지 않음)가 Swing API로 했다(OS 입력·앞 창 없음). DB 값은 앱 밖의 다른 세션
  (`docker exec`의 psql·sqlcmd·sqlplus)으로 확인했다. 시험 객체는 공용 Docker DB에 만들고(PostgreSQL 스키마 `p4nav`, SQL Server DB `chr_p4nav`,
  Oracle 사용자 `CHR_P4NAV`: customers, orders(외래 키), nokey(키 없음), many(500행)) 끝난 뒤 지웠다.
- **PostgreSQL:**
  - orders의 customer_id 칸 Ctrl+B → customers 1행, 상태 줄 "→ p4nav.customers (orders_customer_id_fkey)", 필터 요약 "필터: id". Alt+← → orders 5행, Alt+→ → customers.
    customers 행에서 이동 › 참조하는 테이블 › p4nav.orders → 그 고객의 주문 2행("← …"), 뒤로 두 번 → 처음 결과(기록 위치 0).
  - 참조 패널: 행 2~3을 고르면 customers 2행. 집계 패널: 열마다(customer_id 개수 5·합계 9·평균 1.8·최솟값 1·최댓값 3, item 최솟값 apple·최댓값 사과),
    "열마다" 끔 → 한 줄(15칸, 합계 27). 텍스트 보기(Ctrl+`) → 열을 맞춘 글.
  - 500행 쿼리(200행 읽음): 행 수 세기 → "전체 500행 중 200행 읽음"(모델 고치기 전에는 "전체 -1행"). Ctrl+G 150 → 150행. Ctrl+F "x17" → 2 / 11, 3 / 11, 이전 2 / 11.
    빈 열 숨기기 → empty_col 숨김(상태 줄 "열 1개 숨김"), 열 숨기기 → id, 열 옮기기 → [label, val], 서버 정렬 뒤에도 그 배치, 모든 열 보이기(2개 숨김) → 네 열.
    Alt+0 두 번 → 글꼴 12 → 14.4, Alt+9 → 13.2. Ctrl+Shift+G "lab" → label 열.
  - nokey: "읽기 전용: 고유 키 없음"과 "가상 키 정하기…" → customer_id를 골라 확인 → "편집 가능(nokey)" → 칸을 고쳐 Ctrl+S → psql에서 `n-two-edited`.
    작업 공간 사본의 data-sources.json "virtual-models"에 `VIRTUAL_PK`(type vpk, customer_id)가 남음.
  - 가상 외래 키 추가: 대화상자(참조 테이블 customers, 키 customers_pkey, 열 customer_id) → 다시 읽은 nokey에서 Ctrl+B → customers의 Alice.
    가상 키 › 가상 외래 키 지우기 › "customer_id → p4nav.customers (id)" → 확인 대화상자 취소 → 남음 → 지우기 → 메뉴에서 사라짐.
  - 오류 위치로 가기: 세 번째 줄의 없는 열 → Messages의 오류에 손 모양 커서·도구 설명, 두 번 누르기 → 편집기 3줄 12열(그 열 이름의 시작).
- **SQL Server**(DB `chr_p4nav`, 세 부분 이름): Ctrl+B → "→ chr_p4nav.dbo.customers (fk_orders_customer)", 참조하는 테이블 → orders 2행, 참조 패널 2행,
  행 수 세기 500, 가상 키 → 저장 → sqlcmd에서 `n-three-mssql`, 가상 외래 키(PK__customer__…) → Ctrl+B → 김철수, 오류 위치 → 3줄 1열(SQL Server는 줄만 줌).
- **Oracle**(사용자 CHR_P4NAV): Ctrl+B → "→ CHR_P4NAV.CUSTOMERS (FK_ORDERS_CUSTOMER)", 참조하는 테이블 → 2행, 참조 패널 2행, 행 수 세기 500,
  가상 키 → 저장 → sqlplus에서 `n-one-oracle`, 오류 위치 → 3줄 12열.
- **언어·테마:** 한국어(OS 언어)와 영어(`-nl en`)에서 오른쪽 클릭 메뉴(이동·열·가상 키), 상태 줄, 찾기 줄, 텍스트 보기, 참조·집계 패널, 가상 고유 키·가상 외래 키·행으로 가기
  대화상자를 화면으로 확인했다. 다크 테마에서 옆 패널·찾기 줄·상태 줄을 확인했다.
- 확인하면서 고친 것:
  - 쿼리 결과의 행 수가 -1: 모델 `SQLQueryDataContainer.countData`가 구현되지 않았다(위 "전체 행 수").
  - 참조·집계 패널을 결과 아래에 두니 쿼리 창의 기본 결과 높이에서 표와 패널이 모두 몇 줄만 보였다 → 결과 오른쪽으로.
  - 가상 외래 키 메뉴 이름에 탐색기 노드 경로의 끝(예: `customers_28502`)이 보이던 것 → 참조 테이블 이름.
- 확인하지 못한 것: MariaDB·Altibase 등 다른 DB, 여러 열 외래 키를 여러 행에서 따라가기(OR 형식, 코드 경로만), 수동 커밋 연결에서 가상 키로 저장, macOS.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 뷰어의 정렬·필터·다음 페이지(표를 다시 만들 때 열 배치를 옮김), 값 패널·레코드 보기(선택 열을 모델 열로),
  Messages 탭(오른쪽 클릭 메뉴), 테이블 디자이너의 외래 키 대화상자, 쿼리 결과 내보내기의 "행 수 세기"(COUNT 쿼리를 하나 더 실행).
## 남긴 것

- **열 고정(pin):** Swing `JTable`은 고정 열이 없다. 두 번째 표를 행 머리글 자리에 두고 선택·편집·키·값 패널을 두 표에 맞춰야 해서 따로 한다.
- ~~**가상(계산) 열**~~ 2026-11-08 함([swing-saved-filters-virtual-columns.md](swing-saved-filters-virtual-columns.md)).
- 그룹 패널(B-R8), 결과 안 바꾸기, 참조 패널에서 편집, 외래 키 칸의 링크 표시(SWT는 칸에 화살표를 그림)와 클릭으로 따라가기, 뒤로 갈 때 다시 읽기.
- 가상 외래 키의 참조 테이블은 같은 스키마에서만 고른다(다른 스키마·다른 연결은 SWT처럼 탐색기 트리로 골라야 함).

## 변경 이력

- 2026-11-08 최초 작성(`chromite/p4-grid-navigation`): 외래 키 따라가기와 기록, 참조 패널, 가상 고유 키·가상 외래 키, 열 숨기기·순서·빈 열·행/열로 가기·확대,
  찾기, 집계 패널, 텍스트 보기, 전체 행 수, 오류 위치로 가기. `rebindFilter`가 여러 열 키의 OR 형식(`useDisjunctiveNormalForm`)을 옮김.
  레코드 보기의 줄을 모델 열로 씀.
- 2026-11-08 가상(계산) 열을 [swing-saved-filters-virtual-columns.md](swing-saved-filters-virtual-columns.md)에서 함: "가상 키"·"남긴 것"·upstream 대비 변경점을 고침.
- 2026-11-09 텍스트 보기 키는 macOS에서도 Control+`(앱 변경 #24, `chromite/mac-ui`, [shortcuts.md](shortcuts.md)).
