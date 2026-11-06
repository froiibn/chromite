# Swing: Registered Servers와 연 서버만 보이는 Object Explorer

## 목적

SSMS의 "등록된 서버(Registered Servers)"처럼 서버 그룹(중첩)과 등록된 서버를 Object Explorer와 따로 관리하는 창을 둔다.
Object Explorer는 SSMS처럼 사용자가 연 서버만 평평한 목록으로 보인다. 서버가 많은 환경(시스템 > 개발·품질·운영)에서
그룹으로 정리해 두고, 작업할 서버만 Object Explorer에 연다.

## 구조

| 위치 | 역할 |
|---|---|
| `plugins/com.seizia.chromite.ui.swing/.../RegisteredServersPanel.java` | 도구 창 내용: 그룹·서버 트리, 도구 막대, 오른쪽 클릭 메뉴, 키, 끌어 놓기, 펼친 그룹 저장 |
| `.../RegisteredServerCommands.java` | 그룹 만들기·이름 바꾸기·삭제, 서버·그룹 옮기기, 이름 자연 정렬, Object Explorer의 서버 고르기(Connect) |
| `.../ObjectExplorerServers.java` | Object Explorer에 연 서버 ID 목록(순서 유지), 번들 설정 저장소에 저장 |
| `.../ObjectExplorerPanel.java` | 최상위 = 연 서버 목록, `showServer`(넣고 고르고 펼치기), Disconnect = 목록에서 빼기, 도구 막대 Connect = 서버 고르기 |
| `.../ToolWindow.java` | `stackBelow`: 한 세로 열에 두 도구 창(Registered Servers 위, Object Explorer 아래) |
| `.../MainFrame.java` | 도구 창 만들기(View > Registered Servers, Ctrl+Alt+G), 명령 연결(New Query, Object Explorer) |
| `.../ConnectionDialog.java`, `ConnectionCommands.java` | 새 연결 명령이 만든 연결을 돌려준다 |
| `icons/ui/{duotone,color}/{light,dark}/new-group.svg`, `tools/branding/gen_ui_icons.py` | 도구 막대 New Server Group 아이콘(Tabler `folder-plus`를 다시 그림, 두 팩 × 두 테마) |

### 저장 형식 (새 형식 없음)

- 서버 그룹 = DBeaver 연결 폴더(`DBPDataSourceFolder`, 중첩 가능), 서버 = 활성 프로젝트 레지스트리(`DBPDataSourceRegistry`)의 연결.
  기존 연결과 폴더가 그대로 보이고, 바꾼 내용은 레지스트리의 보통 저장(`flushConfig`, `.dbeaver/data-sources.json`)으로 남는다.
  SWT 판에서 같은 작업 공간을 열면 같은 폴더 구조가 보인다.
- Object Explorer에 연 서버 목록은 연결 설정 파일이 아니라 Swing 번들의 설정 저장소(`objectExplorer.servers`, 연결 ID를 줄바꿈으로 나눔)에 둔다.
  DBeaver 형식에 Chromite 전용 속성을 넣지 않기 위해서다.
- Registered Servers의 펼친 그룹은 `registeredServers.expanded`(그룹 경로 목록)에 창을 닫을 때 저장한다.

### SWT에서 따른 모델 호출

| 명령 | Chromite | SWT 원본 |
|---|---|---|
| 그룹 만들기 | `registry.addFolder` → `DBNModel.updateConfigAndRefreshDatabases` | `NavigatorHandlerLocalFolderCreate.createFolder` (ui.navigator, 89–99행) |
| 그룹 이름 바꾸기 | `DBNLocalFolder.rename`(경로 이동 `moveFolder` + 저장) | 같은 메서드 (`DBNLocalFolder.java` 251–256행) |
| 그룹 옮기기 | `registry.moveFolder(옛 경로, 새 경로)` → 저장 | `DBNLocalFolder.dropNodes` (`DBNLocalFolder.java` 221–242행) |
| 그룹 삭제 | `registry.removeFolder(folder, false)` → 저장 | `NavigatorObjectsDeleter.deleteLocalFolder` (ui.navigator, 234–237행) |
| 서버 옮기기 | `DBNDataSource.moveToFolder` → 저장 | `DBNLocalFolder.dropNodes`, `DBNDataSource.moveToFolder` (`DBNDataSource.java` 299–309행) |
| 서버 이름 바꾸기·편집·삭제·연결·끊기 | `ConnectionCommands`(Object Explorer와 같음) | `NavigatorHandlerObjectRename`, `DataSourceHandler`, `DataSourceDescriptorManager` |
| 정렬 | `AlphanumericComparator.compareIgnoreCase` | `DBNUtils.NodeNameComparator` (`DBNUtils.java` 280–293행) |

그룹을 지우면 무엇이 남는지는 `DataSourceRegistry.removeFolderContents`(registry, `DataSourceRegistry.java` 349–368행)를 따른다:
하위 그룹은 모두 지워지고, 그 그룹과 하위 그룹 안의 서버는 모두 지운 그룹의 상위 그룹(맨 위 그룹이면 최상위)으로 옮겨진다. 서버(연결)는 지우지 않는다.
확인 질문에 하위 그룹 수, 서버 수, 옮겨 갈 곳을 적는다.

## 동작

### Registered Servers 창

- 위치: 왼쪽 열에서 Object Explorer 위(SSMS 기본 도킹). View > Registered Servers(Ctrl+Alt+G)로 보이고 숨긴다. Window > Reset Window Layout에 포함된다.
- 트리: 그룹 다음 서버, 각각 이름의 자연 정렬("01. DEV" < "02. QAS" < "03. PRD", "2. Misc" < "10. Archive"). 숨김 연결(`isHidden`)은 탐색기처럼 뺀다.
- 아이콘: 그룹은 아이콘 팩의 폴더(펼치면 열린 폴더). 서버는 Object Explorer 연결 노드와 같은 `NodeIcons.getDataSourceIcon`
  (드라이버가 주는 DB별 아이콘: SQL Server, PostgreSQL, MariaDB 등 + 연결 상태 점: 연결됨 녹색, 연결 중 주황, 실패 빨강).
  같은 서버는 두 창에서 같은 아이콘이다. 새 공급자 로고는 그리거나 넣지 않았다(사용자 결정).
- 이름 뒤에 연결 호스트(host:port)를 보조 글자로 보인다(Object Explorer와 같음).
- 연결 색: 서버 이름을 감싸는 알약으로 보인다(아래 "연결 색 알약", Object Explorer와 같음). 색이 없는 연결(기본 Development)은 알약이 없다.
- 도구 막대: New Server Registration, New Server Group | Collapse All.
- 서버 메뉴: New Query, Object Explorer(Enter) | Connect, Disconnect | Move To ▸ | Properties…, Rename(F2), Delete(Delete).
  - New Query: 필요하면 연결하고 그 연결의 SQL 편집기를 연다. Object Explorer에는 넣지 않는다(SSMS).
  - Object Explorer: Object Explorer를 보이고 서버를 넣은 뒤 골라 펼친다(펼치면 연결). 더블클릭도 같다(SSMS).
  - Properties…: 기존 연결 대화상자(편집).
- 그룹 메뉴: New Server Registration…(그 그룹을 고른 연결 대화상자), New Server Group… | Expand All, Collapse All(그 그룹 아래) | Move To ▸ | Rename(F2), Delete(Delete).
- 빈 곳 메뉴: New Server Registration…, New Server Group…(최상위) | Expand All, Collapse All.
- 끌어 놓기: 서버와 그룹을 그룹 위, 그룹 안 서버 위(그 그룹), 빈 곳(최상위)으로 옮긴다. 그룹을 자기 자신이나 하위 그룹으로는 놓을 수 없다
  (`DBPDataSourceFolder.canMoveTo`). 같은 상위 그룹에 같은 이름의 그룹이 있으면 옮기지 않고 알린다(경로가 겹쳐 폴더가 합쳐지는 것을 막음).
- 그룹 이름: 비어 있으면 안 되고 모델의 자원 이름 규칙(`GeneralUtils.validateResourceNameUnconditionally`: `/` 등 금지, 점으로 시작·끝 금지)을 따른다.

### Object Explorer

- 최상위는 연 서버만의 평평한 목록이다. 연결 폴더 노드는 없다. 순서는 SSMS처럼 연 순서(새로 연 서버가 아래)다.
- 서버가 들어오는 길: Registered Servers의 Object Explorer(더블클릭·Enter), 도구 막대 Connect(등록된 서버 목록에서 고르기, 그룹 경로와 함께 보임),
  New Connection(File 메뉴·주 도구 막대 포함, 만든 서버를 넣고 고름).
  새 연결은 레지스트리의 `OBJECT_ADD` 이벤트가 지연 Job(`DataSourceRegistry.notifyDataSourceListeners`, 20ms)으로 가므로 대화상자가 닫힌
  직후에는 탐색기 노드가 아직 없다. 그래서 ID를 먼저 목록에 넣고 고를 서버를 노드가 아닌 연결로 기억해 두었다가, 탐색기 `ADD` 이벤트로
  목록을 다시 읽을 때 고른다.
- Disconnect(오른쪽 클릭·도구 막대): 연결을 끊고 목록에서 뺀다(SSMS). 연결이 끊긴 채 남은 노드도 Disconnect로 뺄 수 있게 연결 여부와 관계없이 켠다.
- 다른 곳(Registered Servers의 Disconnect, 편집기)에서 끊으면 노드는 접힌 채 남는다. 다시 펼치면 연결한다.
- 오른쪽 클릭 Connect는 고른 노드의 서버를 연결한다(그대로).

### 연결 색 알약 (사용자 결정 2026-11-06, B안)

시안 비교(`D:\Tools\_scratch\conn-color-mock\compare.png`: 현재 줄 전체, A1 이름 뒤 점, A2 아이콘 모서리 점, B 이름 감싸기)에서 사용자가 B를 골랐다.
Registered Servers와 Object Explorer의 서버 행이 같은 클래스(`ConnectionPill`)로 그린다.

| 항목 | 값 |
|---|---|
| 모양 | 서버 이름만 감싸는 둥근 사각형. 아이콘과 호스트(host:port)는 감싸지 않는다. |
| 색 | `ConnectionColors.getColor`: 연결에 정한 색(Custom color)이 있으면 그 색, 없으면 연결 종류의 라이트/다크 색(Test 214,250,207 / 64,89,66, Production 250,207,207 / 97,61,63). 흰색(기본 Development)은 알약 없음. |
| 크기(논리 px, UI 배율 적용) | 좌우 여백 6. 위 = 이름 기준선에서 대문자 높이 + 4, 아래 = 기준선에서 descent + 1. 둥글기(arc) 10. |
| 간격 | 아이콘→알약 6(아이콘-글자 간격 6 + 여백 6에서 여백만큼 알약이 왼쪽으로 나옴), 알약→호스트 6. |
| 글자 색 | 바꾸지 않는다. |
| 선택·마우스 올림 | 줄 바탕(선택 색, bg.hover)은 그대로 그리고 알약은 그 위에 그린다. 선택한 줄과 마우스를 올린 줄에서도 알약이 보인다. |
| Production 테두리 | 1px(색으로 Test와 Production을 구별하기 어려운 사용자를 위해). 바탕색과 같은 색상(hue)에서 채도를 0.6 이상으로 올리고 밝기를 바탕과 먼 쪽으로 0.01씩 옮겨 바탕과 대비 3:1(WCAG 비텍스트 대비)이 되는 첫 색. 라이트 250,207,207 → 204,82,82, 다크 97,61,63 → 245,98,106(어두운 바탕은 더 어두운 색으로 3:1이 안 되어 밝은 쪽). Production 연결에 정한 색도 같은 규칙(예 255,200,120 → 145,110,58). Production 판단은 연결 종류 ID `prod`(`DBPConnectionType.PROD`). |

- 호스트 글자의 기준선: 렌더러가 글꼴 지표로 따로 계산하던 세로 위치(`insets.top + (높이 - 글꼴 높이) / 2 + ascent`)를 지우고 라벨의 실제 기준선(`JLabel.getBaseline`)을 쓴다.
  라벨은 `SwingUtilities.layoutCompoundLabel`로 `높이/2 - 라벨 높이/2`(정수 나눗셈을 따로 함)에 놓아서, 줄 높이 24·라벨 높이 17에서 라벨 기준선은 17, 옛 계산은 16이었다
  (호스트 글자가 이름보다 1px 위). 알약도 같은 기준선에서 잰다. Object Explorer의 다른 보조 글자(열 형식, 폴더 개수)도 같은 고침을 받는다.
- 이전 방식은 지웠다: Registered Servers의 줄 전체 연결 종류 색(`ServersTree.paintRowBackgrounds`, `getConnectionTypeColor`, `NO_COLOR`), Object Explorer의 글자 폭 바탕(`connectionColor`, 고급 연결 설정에서 넣음).
- 색 규칙을 한곳으로: 이전에는 Registered Servers가 연결 종류 색만(연결에 정한 색 무시), Object Explorer가 `ConnectionColors`를 썼다. 지금은 둘 다 `ConnectionColors`라 같은 서버가 두 창에서 같은 색이다.
- 쿼리 창 상태 줄(`QueryStatusLine`)은 바꾸지 않았다: 이미 같은 `ConnectionColors`(같은 다크 색)로 상태 줄 띠 전체를 칠하고, 테마를 바꾸면 `updateUI`에서 다시 칠한다(편집기를 다시 붙일 때도 `MainFrame.attachStatusLine` → `updateLookAndFeel`).

## 설계 결정과 이유

| 결정 | 이유 / 되돌리는 방법 |
|---|---|
| 그룹 = 연결 폴더, 새 저장 형식 없음 | 기존 연결·폴더를 그대로 쓰고 SWT 판과 작업 공간을 함께 쓸 수 있다(사용자 지시). |
| 연 서버 목록은 번들 설정 저장소 | 연결 설정 파일 형식을 바꾸지 않는다. 키 `objectExplorer.servers`를 지우면 처음 상태로 돌아간다. |
| 업그레이드 뒤 첫 실행: 저장된 값이 없으면 모든 연결을 보이고 그 목록을 저장 | 업그레이드로 Object Explorer의 서버가 갑자기 사라지지 않게 했다. 기본값을 `*`로 둬서 빈 목록과 구분한다(`BundlePreferenceStore`는 기본값과 같은 값을 지움). 되돌리기: `ObjectExplorerServers` 생성자에서 첫 실행 분기를 빈 목록으로. |
| Object Explorer Disconnect = 목록에서 빼기 | SSMS와 같다. 다른 곳의 연결 끊기는 노드를 빼지 않는다(사용자가 Object Explorer에서 한 동작만 목록을 바꿈). |
| 도구 막대 Connect = 서버 고르기 | SSMS의 Connect 단추처럼 대상을 묻는다. 고른 노드를 연결하는 명령은 오른쪽 클릭 메뉴에 남겼다. |
| Registered Servers를 Object Explorer 위에 쌓기(`ToolWindow.stackBelow`) | SSMS 기본 도킹과 같고, 지금의 분할 창 배치·저장·Reset Window Layout을 그대로 쓴다. 아래 창(Object Explorer)이 열의 너비(기존 `objectExplorer.size` 그대로), 위 창이 높이(`registeredServers.size`, 기본 320)를 저장한다. |
| Registered Servers 기본 표시 | 연 서버만 보이는 Object Explorer에서 다른 서버를 찾는 길이 이 창이라 처음부터 보인다. View 메뉴로 숨기면 그 상태가 저장된다. |
| 처음에는 모든 그룹을 펼침 | 저장한 펼침 상태가 없을 때 서버가 모두 보이게. |
| 서버 아이콘 = 드라이버의 DB별 아이콘 + 상태 점 | 사용자 결정(두 창이 같은 아이콘). 저장소에 있는 아이콘만 쓴다. |
| New Server Registration은 기존 연결 대화상자 | SSMS는 등록만 하고 연결하지 않지만, 지금 대화상자는 새 연결을 저장하고 바로 연결한다(Connect 단추). 대화상자 동작은 다른 작업과 겹치는 파일이라 바꾸지 않았다. 등록한 서버는 Object Explorer에 넣지 않는다. |
| 연결 색 = 이름 알약(B안) | 사용자 결정. 줄 바탕을 선택·마우스 올림 표시에 남기고, 선택한 줄에서도 색이 보인다. 되돌리기: `ConnectionPill`과 두 렌더러의 알약 호출을 빼면 색 없음. |
| 알약 그리기를 `ConnectionPill` 한 클래스로 | 두 창이 같은 크기·색·테두리 규칙을 갖게(따로 고치다 어긋나지 않게). 두 트리 클래스의 마우스 올림 그리기는 원래부터 각자 있어 그대로 두었다. |
| Production 테두리 색을 바탕에서 계산 | 연결에 정한 색에도 같은 규칙을 쓰기 위해. 다크 테마는 밝은 쪽 테두리(어두운 바탕보다 더 어두운 색으로는 3:1이 나오지 않음). |
| 트리를 이벤트마다 다시 만들기 | 레지스트리 목록은 메모리에 있어 싸다. 펼친 그룹과 선택은 객체로 기억해 이름을 바꾸거나 옮긴 그룹도 펼친 채로 남는다. |

## upstream 대비 변경점

- SWT 판에는 이 창이 없다(Database Navigator가 폴더와 연결을 함께 보인다). 모델 코드는 바꾸지 않았다.
- Swing Object Explorer에서 연결 폴더 노드와 그 처리(폴더 아이콘 분기, 폴더 다시 읽기)를 뺐다.

## 확인 (2026-11-06, Swing 제품, 개인 작업 공간)

로컬 테스트 DB(SQL Server 2022, PostgreSQL 17, MariaDB 11, 각각 `chr_registered_servers` DB)로 확인했다. 회귀 테스트는 실행하지 않았다.

- 업그레이드 첫 실행: 연결 폴더 "12. QMS > 01. DEV / 02. QAS / 03. PRD"와 빈 폴더 "2. Misc", "10. Archive", 세 연결이 있는 작업 공간을 열자
  Registered Servers에 기존 폴더·연결이 그대로 보이고(자연 정렬: 2. Misc, 10. Archive, 12. QMS), Object Explorer에는 세 서버가 모두 보였다.
- 서버 아이콘: SQL Server·PostgreSQL·MariaDB 드라이버 아이콘과 연결 상태 점이 두 창에서 같게 보였다. 연결 종류 색(Test 연두, Production 분홍) 표시.
- Object Explorer Disconnect → 노드가 빠짐. Registered Servers에서 더블클릭 → Object Explorer 맨 아래에 다시 들어오고 골라 펼쳐 연결됨.
- New Query(Registered Servers): PostgreSQL `SELECT version()`, SQL Server `SELECT @@VERSION`, MariaDB `SELECT version()` 실행 성공. Object Explorer는 바뀌지 않음.
- 끌어 놓기: 서버를 다른 그룹으로, 그룹 "12. QMS"를 "10. Archive" 안으로, 다시 최상위로 옮김(설정 파일의 폴더 경로가 바뀜). "10. Archive"를 자기 하위 그룹으로 놓는 것은 막힘.
- 그룹 메뉴 New Server Group…("04. TST" 생성), Delete(확인 질문에 서버가 상위 그룹으로 옮겨진다고 표시, 실제로 옮겨짐), 툴바 New Server Group.
- 다시 시작: 그룹 구조, 서버 위치, Object Explorer 서버 목록과 순서(`objectExplorer.servers`), 펼친 그룹(`registeredServers.expanded`)이 그대로 돌아옴.
- 테마·아이콘 팩: 밝은·어두운 테마 × duo-tone·color 팩에서 그룹 폴더, 서버 아이콘, 연결 종류 색을 확인.

### 확인 — 연결 색 알약 (2026-11-06)

Swing 제품을 개인 작업 공간으로 열고, 프로세스 안에서 EDT로 동작하는 시험 에이전트(OS 입력 없음)로 조작했다. 로컬 테스트 DB의 `chr_conn_color_pill`에 연결한
QMS SQL Server(SQL Server, Development), QMS PostgreSQL(Test), QMS MariaDB(Production), QMS Report DB(SQL Server, Production + 연결에 정한 색 255,200,120).
화면은 `D:\Tools\_scratch\conn-color-pill\shots\`, 시안과 나란히 놓은 그림은 `D:\Tools\_scratch\conn-color-pill\compare-mock-vs-real.png`. 회귀 테스트는 실행하지 않았다.

- 두 창에서 같은 서버가 같은 알약(Development 없음, Test 연두, Production 분홍 + 진한 테두리, Report DB 주황 + 갈색 테두리). 크기·간격이 시안 B와 같다.
- 선택한 줄(QMS PostgreSQL)과 마우스를 올린 줄(QMS MariaDB)에서 줄 바탕 위에 알약이 보인다. 라이트 → 다크 테마를 바꾸면 알약이 다크 색으로 바뀐다.
- 기준선: 라벨 기준선 17 = 호스트 글자 기준선(옛 계산 16). 확대 화면에서 이름과 호스트 글자의 아래 끝이 맞는다.
- 쿼리 창 상태 줄: QMS Report DB의 New Query → 상태 줄 띠가 같은 주황(라이트·다크 모두 같은 값).
- 확인한 문제(바꾸지 않음): 연결에 정한 밝은 색은 다크 테마에서도 그대로라(모델에 다크 값이 없음) 기본 밝은 글자와 대비가 낮다(알약 이름, 상태 줄 "Ready"가 거의 안 보임).
  SWT 탐색기는 이때 글자를 대비 색(`UIStyles.getContrastColor`)으로 바꾼다. 글자 색을 바꾸지 않는다는 결정과 부딪혀 남겼다.

## 변경 이력

- 2026-11-06 최초 작성: Registered Servers 창, 연 서버만 보이는 Object Explorer, 한 열에 두 도구 창 쌓기, New Server Group 아이콘.
- 2026-11-06 New Connection(File 메뉴·Object Explorer 도구 막대)으로 만든 서버가 Object Explorer에 들어오지 않던 문제: 탐색기 노드가
  생기기 전이면 `showServer`가 그냥 돌아가던 것을 고침(ID를 먼저 넣고 탐색기 `ADD` 이벤트로 목록을 다시 읽을 때 고름).
- 2026-11-06 연결 색 알약(B안): 두 창의 서버 이름을 연결 색 알약으로 감쌈(`ConnectionPill`), 줄 전체 색과 Object Explorer 글자 폭 바탕 삭제, 색 규칙을 `ConnectionColors`로 통일,
  Production 1px 테두리, 보조 글자를 라벨 기준선에 맞춤(1px 위로 뜨던 문제).
