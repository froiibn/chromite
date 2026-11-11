# Swing 개체 탐색기 생산성 (필터, 빠른 거르기, 편집기와 연결, 두 번 클릭, 긴 폴더, 선택하여 복사, 연결 복제, 개체 세부 정보, 기본 스키마)

## 목적

SWT 전수 조사([swt-parity-audit.md](swt-parity-audit.md)) 4순위 B8 묶음이다. 영역 C([swt-parity/C-navigator-tools.md](swt-parity/C-navigator-tools.md))의
#4, #5, #6, #7, #9, #12, #16, #18, #19와 영역 B([swt-parity/B-editors.md](swt-parity/B-editors.md))의 E7을 옮긴다.

| 줄 | 기능 | 화면 |
|---|---|---|
| C-4 | 필터 대화상자: 폴더마다 표시·숨길 이름 마스크 여러 개, 필터 적용 켜기/끄기, 이 폴더만/연결 전체 범위, 연결에 저장 | 폴더의 "필터"(도구 막대·오른쪽 클릭) |
| C-5 | 빠른 거르기: 이미 읽은 객체 폴더에서 이름에 글자가 든 객체만 보이기 | 도구 막대 아래 입력란(트리에서 Ctrl+F, Esc로 지움) |
| C-6 | 편집기와 연결(Link with Editor): 활성 문서의 객체·연결을 트리에서 고르기 | 도구 막대 토글 단추(연결 고리 아이콘) |
| C-7 | 두 번 클릭 설정: 객체(펼치기 / 편집기 열기 / 데이터 열기), 연결(연결하고 펼치기 / 새 쿼리 창 / 연결 편집) | 옵션 > 일반 > 개체 탐색기 |
| C-9 | 긴 폴더 나눠 보이기: 자식이 N개(기본 5000, 최소 100)보다 많으면 N개와 "더 읽기… (전체 중 보인 수)" 행 | 같은 옵션 절, "더 읽기" 행 두 번 클릭·Enter |
| C-12 | 선택하여 복사(이름·정규화된 이름·DDL, 구분자)와 연결 복제 | 오른쪽 클릭 "선택하여 복사…", 연결의 "연결 복제" |
| C-16 | 폴더 내용 표(SSMS "개체 탐색기 세부 정보"): 자식 목록을 속성 열로 | 오른쪽 클릭 "개체 세부 정보", F7 |
| B-E7 | 개체 편집기의 목록 탭: 테이블이면 열·제약·외래 키·인덱스·참조·종속성·트리거·파티션 탭 | 객체의 "개체 세부 정보"(F7) |
| C-18 | 트리에서 기본 데이터베이스·스키마 지정, 기본값은 굵게 | 데이터베이스·스키마의 "기본값으로 설정" |
| C-19 | Ctrl+9 연결 선택·Ctrl+0 데이터베이스/스키마 선택(걸러 고르는 목록) | 쿼리 메뉴 |

## 구조

| 위치 | 역할 |
|---|---|
| `ObjectExplorerPanel` | 메뉴 항목·키(F7, Ctrl+F, Enter), 도구 막대(편집기와 연결 토글), 빠른 거르기 입력란, 두 번 클릭(`openDefault`), 자식 나누기·거르기(`setTreeChildren`, `replaceChildrenPreserving`, `ExplorerNode.allChildren`), "더 읽기" 행(`MoreItem`), `activeDocumentChanged`, 기본 데이터베이스·스키마 굵게, 필터가 걸린 폴더의 "필터됨" |
| `ObjectFilterDialog`(새 파일) | 필터 대화상자. 모델 `DBSObjectFilter`를 폴더(`DBNDatabaseNode#setNodeFilter`) 또는 연결 전체(`DBPDataSourceContainer#setObjectFilter`의 부모 null)에 저장 |
| `ExplorerPreferences`(새 파일) | 두 번 클릭·긴 폴더·편집기와 연결 설정(SWT와 같은 키 이름) |
| `ObjectDetailsPanel`(새 파일) | 개체 세부 정보 문서 탭(`ToolDocument`): 폴더면 목록 하나, 객체면 자식 폴더·항목 묶음마다 탭 |
| `CopySpecialDialog`(새 파일) | 선택하여 복사 |
| `DefaultObjectCommands`(새 파일) | 기본값으로 설정(연결 주 세션) |
| `ObjectChooserDialog`(새 파일) | Ctrl+9 / Ctrl+0의 걸러 고르는 목록 |
| `ConnectionCommands.duplicate` | 연결 복제 |
| `ExitTransactionCheck.confirmDefaultChange` | 기본값을 다른 데이터베이스 인스턴스로 옮기기 전 트랜잭션 질문 |
| `MainFrame` | 개체 세부 정보 탭 열기(같은 노드면 열린 탭), 활성 문서를 탐색기에 알림, 쿼리 > 연결 선택…(Ctrl+9)·데이터베이스/스키마 선택…(Ctrl+0), 기본값 변경 뒤 쿼리 창 DB 콤보 다시 읽기 |
| `OptionsDialog` | 일반 탭의 개체 탐색기 절 |
| `internal/ObjectExplorerMessages`(+`_ko`, 새 파일) | 화면 문구(`oe_*`). 같은 파일을 고치는 다른 작업과 메시지 파일에서 부딪히지 않게 따로 둠 |
| `icons/ui/*/*/link.svg`, `tools/branding/gen_ui_icons.py` | 편집기와 연결 아이콘(Tabler "link"를 16px 격자에, 생성기로 만듦) |

`CoreMessages`의 `explorer_filter_title`·`explorer_filter_prompt`(한 줄 입력 창)는 쓰지 않게 되어 지웠다.

## SWT에서 옮긴 것 (근거, 커밋 `263b8ddb45`)

- 필터: `EditObjectFilterDialog`(포함·제외 표, 켜기, 전역 필터 링크, 이름 붙인 저장 필터), `NavigatorHandlerFilterConfig`. 모델 `DataSourceDescriptor.getObjectFilter`/`FilterMapping.getFilter`:
  폴더 자신의 필터가 적용 가능하면 그것, 아니면 형식의 전역 필터.
- 빠른 거르기: `NavigatorHandlerFocusFilter`, `DatabaseNavigatorTreeFilter`(연결·컨테이너·객체 종류 거르기).
- 편집기와 연결: `NavigatorHandlerLinkEditor`, 설정 `navigator.sync.editor.datasource`.
- 두 번 클릭: `NavigatorViewBase`의 처리, `NavigatorPreferences.DoubleClickBehavior`(EDIT, CONNECT, SQL_EDITOR, EXPAND, SQL_EDITOR_NEW), 키 `navigator.object.doubleClick`(기본 EDIT)·`navigator.connection.doubleClick`(기본 EXPAND).
- 긴 폴더: `TreeNodeLazyExpander`("More ... (보인 수/전체)"), 키 `navigator.long.list.fetch.size`(기본 5000, 최소 100).
- 선택하여 복사: `NavigatorHandlerCopySpecial`(`CopyMode.ADVANCED`, 정규화된 이름). 연결 복제: `DataSourceDescriptorManager.createNewObject`의 복사(`copyFrom`, 이름은 `DataSourceUtils.generateUniqueDataSourceName`).
- 폴더 내용 표·목록 탭: `FolderEditor`, `ItemListControl`, 개체 편집기의 `ObjectListControl` 탭. 종속성·참조는 드라이버 `plugin.xml`의 가상 폴더(`virtual="true"`, 예: PostgreSQL 테이블의 Dependencies·References, Oracle의 Dependencies).
- 기본값 지정: `NavigatorHandlerSetDefaultObject`(주 문맥과 활성 편집기 문맥의 기본 카탈로그·스키마), 굵게: `DBNUtils.isDefaultElement`.
- Ctrl+9 / Ctrl+0: `SelectActiveDataSourceHandler`·`SelectDataSourceDialog`, `SelectActiveSchemaHandler`.

## 설계 결정과 이유

- **필터 범위를 대화상자 하나에.** SWT는 폴더 필터 대화상자와 전역 필터 대화상자가 따로였다. 범위 선택(이 폴더만 / 연결의 모든 같은 폴더) 하나로 합쳤다.
  전역으로 저장하면 이 폴더 자신의 필터를 지운다. 모델은 폴더 자신의 필터를 먼저 쓰므로 남겨 두면 전역 필터가 이 폴더에 적용되지 않는다.
  전역 필터를 바꾸면 같은 연결에서 읽어 둔 같은 형식의 폴더를 모두 다시 읽는다. 이름 붙인 저장 필터와 "선택한 개체로 포함/제외 추가"는 옮기지 않았다.
- **마스크는 적은 그대로.** 전의 한 줄 입력 창은 `%`가 없으면 앞뒤에 `%`를 붙였다. 여러 마스크에서는 SWT·모델 규칙(와일드카드가 없으면 이름 전체가 같아야 함)대로 저장하고 대화상자에 규칙을 적었다.
  모델 필터는 저장되는 서버 쪽 필터이고, 이름 일부로 빨리 찾는 일은 빠른 거르기가 맡는다.
- **빠른 거르기는 화면에서만.** SWT의 객체 거르기는 서버에서 다시 읽는 경로가 있었다. Swing은 이미 읽은 객체 폴더의 자식만 거른다(DB를 읽지 않고, 저장하지 않음).
  연결·데이터베이스·스키마·폴더는 거르지 않아 어느 폴더든 펼쳐 볼 수 있다. 글자는 이름에 들어 있으면 맞고(대소문자 무시) `*`·`%`는 아무 글자다.
  폴더 개수 글자는 거를 때 "보인 수/전체"다. 빠른 거르기가 숨긴 노드의 트리 노드는 버리지 않아(`ExplorerNode.childNodes`) 다시 보일 때 읽은 자식이 남는다.
  Find Objects·다이어그램에서 고른 노드가 거르기에 가려 있으면 거르기를 지우고 고른다. 편집기와 연결은 거르기를 지우지 않는다.
- **편집기와 연결은 이 창에 연 서버만.** Object Explorer는 SSMS처럼 연 서버만 보인다. 탭을 바꿀 때마다 서버 목록이 늘지 않게, 이 창에 없는 서버의 문서는 선택을 바꾸지 않는다.
  연결되지 않은 연결은 연결하지 않고 연결 노드만 고른다. 객체 노드는 탐색기 모델에 이미 있을 때만 찾는다(`DBNModel#getNodeByObject`, DB를 읽지 않음). 초점은 옮기지 않는다.
  알리는 객체: 쿼리 창은 수정(Modify) 창의 루틴, 아니면 창의 기본 데이터베이스·스키마. 테이블 디자이너는 테이블, 다이어그램은 시작 객체, 개체 세부 정보는 그 노드, 작업 모니터는 연결.
- **두 번 클릭 기본값은 지금 동작(펼치기).** SWT 객체 기본값은 편집기 열기였지만 Swing 트리는 처음부터 SSMS처럼 펼쳤다. 기본값을 바꾸면 손에 익은 동작이 바뀌므로 그대로 두고 설정으로 바꾸게 했다.
  트리 자체의 두 번 클릭 펼치기(`setToggleClickCount(0)`)는 끄고 설정에 따라 펼친다. SQL Server 에이전트·작업 스케줄러 노드의 기본 명령은 전처럼 먼저다.
  편집기 열기 = 디자인(테이블) → 수정(뷰·루틴·트리거·패키지) → 자식이 있으면 개체 세부 정보, 없으면 펼치기. 데이터 열기 = 상위 1000개 행 선택(테이블·뷰), 아니면 펼치기.
  폴더·연결·데이터베이스·스키마는 늘 펼친다(편집기 열기로 스키마 탭이 열리면 트리를 탐색하기 어렵다). SWT 연결의 "최근 스크립트"는 Swing에 스크립트-연결 묶기가 없어 두지 않았다.
- **긴 폴더는 트리 행만 나눈다.** 모델은 자식을 한 번에 읽는다(SWT도 같음). 나누는 목적은 수천 행 트리의 느린 그리기와 스크롤을 막는 것이다.
  "더 읽기"는 다음 묶음을 더해 보이고 새로 보인 첫 행을 고른다. 빠른 거르기와 함께 쓰면 거른 뒤의 수로 나눈다. 고르려는 노드가 묶음 뒤에 있으면 그 묶음까지 보인다.
- **선택하여 복사.** 이름 복사(Ctrl+C)와 정규화된 이름 복사(Ctrl+Shift+C)는 이미 있으므로 대화상자는 구분자(줄마다, 쉼표, 쉼표와 줄 바꿈)와 DDL(객체마다 DDL 보기와 같은 CREATE 문, 빈 줄로 나눔)을 더한다.
  DDL이 없는 객체가 섞이면 DDL을 끈다. 마지막 선택은 실행 중에 기억한다.
- **연결 복제는 레지스트리 복사로.** SWT의 `copyFrom`은 연결별 설정 저장소를 복사하지 않는다. `DBPDataSourceRegistry#createDataSource(원본)`(새 ID)은 연결 정보·저장한 자격 증명·네트워크 처리기·필터·연결별 설정·서버 그룹까지 복사한다.
  이름은 SWT처럼 겹치지 않는 이름(`이름 1`)이다. 만든 연결은 이 창에 넣고 고른다(연결하지 않음).
- **개체 세부 정보 = 폴더 내용 표 + 목록 탭.** 이 앱에는 SWT 개체 편집기가 없어 같은 문서가 두 줄(C-16, B-E7)을 맡는다. 탭은 드라이버 모델의 탐색기 구성이 정하므로 DB마다 다르다:
  PostgreSQL 테이블은 Columns·Constraints·Foreign Keys·Indexes·Dependencies·References·Partitions·Triggers 등, Oracle은 Dependencies 포함, SQL Server는 References(모델에 종속성 폴더가 없음, "남긴 것").
  열은 `ObjectListTable`과 같은 규칙(`@Property`의 viewable, 숨김 아님, 연결의 속성 필터)이고, 첫 열(노드 글자와 아이콘)과 겹치는 `name` 속성은 뺀다.
  인라인 자식(`inline="true"`: 인덱스·키 열, Oracle 종속성 묶음의 개체)은 그 항목 아래 들여 쓴 행으로 넣는다(SWT 목록도 항목 아래에 보였다). 그런 목록은 정렬하지 않는다.
  값은 작업 스레드에서 읽고, 게으른 속성(값마다 DB 조회)은 두 번째로 읽어 행이 먼저 보인다.
  행을 두 번 누르거나 Enter를 누르면 트리에서 그 노드를 고른다. 연결 노드의 세부 정보는 연결된 연결에서만 켠다(데이터베이스 목록을 읽으면 연결되므로).
- **기본값으로 설정은 주 세션만.** Swing 쿼리 창은 대개 자기 세션을 가지며(쿼리 창마다 별도 연결) 창의 DB 콤보와 Ctrl+0이 그 세션을 바꾼다.
  그래서 이 명령은 SWT와 달리 활성 편집기 문맥은 바꾸지 않고 주 세션만 바꾼다. 주 세션을 같이 쓰는 쿼리 창과 새로 여는 쿼리 창(주 세션의 기본값을 복사)이 따른다. 바꾼 뒤 그 연결의 쿼리 창 DB 콤보를 다시 읽는다.
- **트랜잭션(사용자 규칙).** 같은 인스턴스 안의 스키마·카탈로그 바꾸기(PostgreSQL `SET search_path`, Oracle `ALTER SESSION SET CURRENT_SCHEMA`, SQL Server `USE`)는 열린 트랜잭션을 끝내지 않는다.
  다른 데이터베이스 인스턴스로 옮기면(PostgreSQL 데이터베이스) 주 세션이 다른 서버 세션이 되어 열린 트랜잭션이 보이지 않게 남는다. 그때만 커밋 / 롤백 / 취소를 묻는다(쿼리 창 DB 콤보의 판단과 같음).
  Ctrl+0은 쿼리 창의 콤보를 지나므로 그 창의 기존 질문(자기 세션을 다른 인스턴스로 다시 연결할 때)을 그대로 쓴다.
- **Ctrl+9 / Ctrl+0은 걸러 고르는 목록.** 콤보보다 긴 목록을 키보드로 빨리 고르게 한다. 고른 값은 활성 쿼리 창의 콤보에 넣어 그 창의 기존 경로(세션 질문, 기본값 바꾸기)를 지난다.
  2026-11-12부터 Ctrl+9는 서버가 없는 쿼리 창의 서버를 한 번 고를 때만 쓴다. 서버가 있는 창에서는 상태 줄에 그 창의 서버라고 알리고 아무것도 바꾸지 않는다
  (쿼리 창은 한 서버의 창, 앱 변경 #69, [swing-frontend.md](swing-frontend.md) "쿼리 창은 한 서버의 창").
  쿼리 창이 없거나 DB를 바꿀 수 없으면(연결 안 됨, 실행 중, 지원 안 함) 이유를 알린다.

## upstream 대비 변경점

- SWT 번들(`ui.navigator`)은 지웠으므로 위 기능은 Swing에 새로 만든 것이다. 모델 코드는 고치지 않았다.
- 설정 키 이름은 SWT와 같지만 저장소는 Swing 번들 설정이다(SWT 값은 지운 번들에 있었음).
- 연결 복제는 SWT보다 연결별 설정을 더 복사한다. 기본값으로 설정은 활성 편집기 문맥을 바꾸지 않는다. 두 번 클릭 기본값은 펼치기다.

## 확인 (2026-11-08)

`-Pproduct-chromite-swing` 빌드를 작업 공간·데이터 폴더를 scratch에 둔 앱 하나로 띄우고, 같은 프로세스 안의 스크립트(OS 입력 없음)로 눌러 확인했다.
Docker 테스트 DB에 만든 자기 객체만 썼다(PostgreSQL 스키마 `p4_explorer`·테이블 152개·데이터베이스 `chr_p4_explorer`, SQL Server 데이터베이스 `chr_p4_explorer`,
Oracle 스키마 `CHR_P4_EXPLORER`). 끝난 뒤 모두 지웠다. 한국어(OS 언어)로 모두, 영어(`-nl en`)로 메뉴·옵션·대화상자·세부 정보 탭 이름을 보았다.

- 긴 폴더: 한 번에 100개로 두면 테이블 152개 폴더가 `bulk_001`…`bulk_100`과 "더 읽기… (152개 중 100개 표시)", 두 번 클릭하면 나머지가 보이고 `bulk_101`이 골라진다.
  Oracle 스키마 47개는 한 묶음이다.
- 빠른 거르기: `PAR` → 데이터베이스 목록·스키마는 그대로, 테이블 폴더는 `parent_t`만, 개수 `1/152`. Esc → 152개와 펼친 상태가 돌아온다.
  처음 만든 판은 데이터베이스 폴더의 데이터베이스까지 걸렀다(`데이터베이스 [0/1]`). 연결·데이터베이스·스키마·폴더는 거르지 않게 고쳤다.
- 필터 대화상자: 표시 `bulk_14%`·`parent_t`, 숨김 `bulk_145` → `테이블 [10 · 필터됨]`, `data-sources.json`에 `"include": ["bulk_14%","parent_t"]`, `"exclude": ["bulk_145"]`.
  범위를 "연결의 모든 테이블 폴더"로 `parent%` → 폴더 자신의 필터가 지워지고 형식의 전역 필터로 저장, `테이블 [1 · 필터됨]`. 필터 제거 → 152개, 설정 파일에서 사라짐.
- 개체 세부 정보(F7): PostgreSQL `parent_t` → 탭 열·제약 조건·외래 키·인덱스·종속성·참조·파티션·트리거·규칙·정책. 종속성 탭에 `child_t_parent_id_fkey`·`parent_t_pkey`·뷰 규칙,
  참조 탭에 `child_t.child_t_parent_id_fkey`(참조 제약 `parent_t_pkey`). SQL Server `parent_t` → 참조 탭에 `child_t`의 외래 키. Oracle `P4X_PARENT` → 종속성 탭에
  "종속성"·"종속 개체"와 그 아래 들여 쓴 `P4X_V | VIEW | CHR_P4_EXPLORER | HARD`(인라인 자식을 넣기 전에는 묶음 두 줄만 보였다), 인덱스 탭에 인덱스와 그 열.
- 편집기와 연결: 켜고 Oracle 쿼리 창 → `Oracle 테스트` 노드, 세부 정보 탭 → `parent_t`, SQL Server 쿼리 창 → `master`. 초점은 편집기에 남는다.
- 두 번 클릭: 데이터 열기 → 상위 1000개 행 쿼리 창, 연결 = 새 쿼리 창 → 새 창, 편집기 열기 → 테이블은 디자이너 탭, 뷰는 수정 스크립트 창. 다시 펼치기로 되돌림.
- 선택하여 복사: 이름 → `parent_t`, DDL → `CREATE TABLE p4_explorer.parent_t (…)`.
- 연결 복제: `PostgreSQL 테스트 (1)`이 저장되고, 펼치면 비밀번호를 묻지 않고 연결된다(자격 증명 복사). 확인한 뒤 지웠다.
- 기본값으로 설정: SQL Server `chr_p4_explorer` → 굵게, `master`는 보통 글꼴. PostgreSQL(모든 데이터베이스 표시, 수동 커밋, 쿼리 창이 주 세션을 같이 씀)에서
  `INSERT` 뒤 데이터베이스 `chr_p4_explorer`를 기본값으로 → "커밋하지 않은 트랜잭션" 질문(커밋 / 롤백 / 취소, 취소가 기본). 취소 → `postgres`가 그대로 굵게.
  롤백 → 기본값이 바뀌고 행이 남지 않음(`count = 0`). 같은 인스턴스의 스키마 `p4_explorer`를 기본값으로 → 질문 없이 바뀌고 서버의 세션은 계속 `idle in transaction`(트랜잭션이 끝나지 않음),
  쿼리 > 롤백으로 끝냄.
- Ctrl+9 / Ctrl+0: 편집기에서 Ctrl+0 → 목록에서 `p4_ex` → `chr_p4_explorer` → 창의 DB 콤보가 바뀜. Ctrl+9 → `postgre` → 창의 연결이 PostgreSQL로 바뀜.

## 남긴 것

- SQL Server 종속성 탭: 모델(`ext.mssql`)에 종속성 폴더가 없다. `sys.sql_expression_dependencies`를 읽는 모델 객체와 `plugin.xml` 폴더가 필요하다.
- 필터: 이름 붙인 저장 필터, "선택한 개체로 포함/제외 추가", "연결된 것만"·객체 종류 거르기.
- 긴 폴더: 통계 렌더러(테이블 크기 막대).
- 붙여넣기로 DB 객체 복제(C-12의 나머지).
- PostgreSQL에서 열린 트랜잭션 안의 스키마 바꾸기(`SET search_path`)는 롤백하면 서버에서도 되돌아가지만, 모델의 문맥은 바꾼 스키마를 기억한다(쿼리 창 DB 콤보도 같음, 모델 동작).

## 변경 이력

- 2026-11-08 최초 작성(`chromite/p4-explorer`): C-4·C-6·C-7·C-16·C-18·C-19·B-E7 → 있음, C-5·C-9 → 일부, C-12 일부(선택하여 복사·연결 복제 추가).
- 2026-11-08 객체의 Object Details 첫 탭이 속성 탭(속성 편집), 자식이 없는 객체(시퀀스)에서도 F7이 켜짐([swing-object-properties.md](swing-object-properties.md)).
