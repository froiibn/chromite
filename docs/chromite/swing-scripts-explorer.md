# Swing Scripts 도구 창 (프로젝트·스크립트 탐색기)

## 목적

SWT에는 Projects·Project Explorer 보기와 연결별 Scripts 목록이 있었다(`ui.navigator`의 `ProjectExplorerView`, `ui.editors.sql`의
`ScriptsHandlerImpl`, 연결 묶기 `NavigatorHandlerAssociateScript`). 프로젝트의 `Scripts` 폴더에 SQL 스크립트를 두고, 폴더를 만들고,
이름을 바꾸고 지우고, 스크립트를 연결에 묶으면 그 연결로 열렸다. SWT를 지울 때 이 보기들도 지워져 Swing에는 File > Open File과
Recent Files만 남았다([swt-removal.md](swt-removal.md) "프로젝트·자원 탐색기", 대조 감사 C-22, B-S38, A-C13).

Swing에 SSMS의 Solution Explorer와 같은 자리의 **Scripts** 도구 창을 만든다.

## 동작

- View > Scripts(Ctrl+Alt+L, SSMS Solution Explorer 단축키). 처음에는 숨김이고, 보이면 오른쪽 열 Properties 위에 붙는다(Query Library와 같은 열).
  2026-11-08부터는 오른쪽 열 위쪽 Query Library 자리의 탭으로 열린다(Query Library가 없으면 Properties의 탭, 둘 다 없으면 Chromite Agent 위,
  [swing-frontend.md](swing-frontend.md) "기본 배치: Query Library와 Chromite Agent").
- 트리의 루트는 활성 프로젝트의 스크립트 폴더(기본 `<작업 공간>/General/Scripts`)다. 하위 폴더와 스크립트 확장자(`.sql`) 파일만 보이고,
  이름이 `.`으로 시작하는 것은 숨긴다. 폴더가 앞, 이름순이다. 폴더가 아직 없으면 루트 뒤에 "not created yet"이 보인다.
- 연결에 묶은 스크립트는 이름 뒤에 연결 이름이 흐린 글자로 보인다. 묶은 연결이 지워졌으면 "connection not found"가 보인다.
  도구 설명은 전체 경로와 묶은 연결이다.
- 명령(오른쪽 클릭 메뉴, 도구 막대, 키):

  | 명령 | 키·위치 | 동작 |
  |---|---|---|
  | Open | Enter, 더블클릭, 메뉴(굵게) | 쿼리 창에서 연다. 이미 열려 있으면 그 탭을 보인다. 묶은 연결로 연다 |
  | New Folder | 도구 막대, 메뉴 | 선택한 폴더(스크립트면 그 폴더, 없으면 루트) 안에 만든다. 스크립트 폴더가 없으면 함께 만든다 |
  | Rename | F2, 메뉴 | 스크립트는 확장자를 빼고 묻고 확장자는 그대로 둔다. 같은 이름이 있으면 `이름 (2)` |
  | Delete | Delete, 메뉴 | 확인(기본 단추 취소) 뒤 시스템 휴지통으로(없으면 바로 삭제). 쿼리 창에 열린 스크립트(가 든 폴더)는 지우지 않고 창을 먼저 닫으라고 알림 |
  | Link to Connection… | 도구 막대, 메뉴 | 연결을 골라 묶는다(지금 묶은 연결이 미리 골라져 있음) |
  | Remove Connection Link | 메뉴 | 묶기를 푼다 |
  | Refresh | F5(트리에 초점), 도구 막대, 메뉴 | 폴더를 다시 읽는다 |

- 연결 묶기의 효과: Scripts 창, File > Open File, Recent Files 어디서 열어도 묶은 연결로 연다. 묶지 않았거나 묶은 연결이 없으면 예전처럼
  Object Explorer에서 고른 연결 → 활성 쿼리 창의 연결 → 연결 없음 순서다.
- 저장: 쿼리 창을 스크립트 폴더 안에 저장(Save, Save As, Save All)하면 Scripts 창이 다시 읽고, 그 파일에 묶은 연결이 없으면 쿼리 창의 연결에 묶는다.
- 이름을 바꾸면 그 스크립트(바꾼 폴더 안의 스크립트)를 연 쿼리 창이 새 경로로 따라간다(창 제목, 이후 저장 위치). 고치던 내용과 고침 표시는 그대로다.

### 연결별 최근 스크립트

- Object Explorer에서 연결(또는 그 연결의 아무 노드)을 오른쪽 클릭 > **Recent Scripts**(New Query 아래). 그 연결에 묶은 스크립트를 최근에 고친 것부터
  15개 보인다. 이름은 스크립트 폴더 기준 경로(`reports/a.sql`), 도구 설명은 전체 경로다. 고르면 File > Open File과 같이 연다(묶은 연결, 이미 열려 있으면 그 탭).
  묶은 스크립트가 없으면 "No scripts linked to this connection"(꺼진 항목)이다.
- SWT는 연결의 스크립트 목록을 고르는 창(`SQLEditorHandlerOpenEditor` → `ScriptSelectorPanel`, `SQLEditorUtils.findScriptTree`)과 가장 최근 스크립트 열기
  (`findRecentScript`, 고친 시각 순)였다. Swing은 같은 기준(연결 묶기, 고친 시각)의 하위 메뉴다. 스크립트가 없을 때 새 스크립트를 만들던 SWT 동작은 New Query가 대신한다.
- 찾는 방법: 모든 프로젝트의 자원 속성에서 `default-datasource`가 그 연결 ID인 경로를 묻고(`DBPProject.findResources`), 스크립트 폴더 안의 있는 스크립트 파일이면서
  묶기의 프로젝트까지 같은 연결인 것만 남긴다(`ScriptFiles.findLinkedScripts`). 폴더 트리를 읽지 않으므로 메뉴를 열 때 바로 만든다.

## 구조

| 위치 | 역할 |
|---|---|
| `ui.swing` `ScriptsPanel` (새로 만듦) | 도구 창: 작업 스레드에서 폴더 읽기, 트리 다시 만들기(펼침·선택 유지), 명령, 셀 그리기(보조 글자) |
| `ui.swing` `ScriptFiles` (새로 만듦) | 스크립트 폴더 찾기(자원 형식 `sql-script`의 루트), 스크립트 확장자, 연결 묶기 읽기·쓰기, 이름 바꾸기·삭제 때 자원 속성 옮기기·지우기, 연결에 묶은 스크립트 찾기(`findLinkedScripts`) |
| `ui.swing` `ObjectExplorerPanel` | Recent Scripts 하위 메뉴(메뉴를 열 때 채움), `QueryWindows.openScriptFile` |
| `ui.swing` `MainFrame` | Scripts 도구 창 등록(View 메뉴, Ctrl+Alt+L, 기본 위치), 파일 열 때 묶은 연결 우선, 스크립트 폴더에 저장할 때 묶기와 다시 읽기 |
| `ui.swing` `SqlEditorPanel` | `fileMoved`: 이름을 바꾼 파일을 창이 따라감 |
| `ui.swing` `QueryLibraryPanel.LibraryTree`, `QueryLibrary.deleteRecursively` | Query Library의 마우스 올림 트리와 삭제를 함께 쓰도록 패키지 안에 엶(동작은 그대로) |
| `ui.swing` `internal.ScriptsMessages` (새로 만듦) | 화면 문구(영어, 한국어) |
| `model.sql` `plugin.xml` 자원 형식 `sql-script` | 스크립트 폴더 이름(`Scripts`)과 확장자. 바꾸지 않았다 |
| `model` `DBPProject` 자원 속성 | 묶기 저장(`default-datasource`, `default-projectId`). 프로젝트의 `.chromite/project-metadata.json`. 바꾸지 않았다 |

## 설계 결정과 이유

- **SWT와 같은 폴더·같은 속성.** 스크립트 폴더는 모델의 자원 형식 `sql-script`가 정하는 곳이고(`ResourceTypeDescriptor.getDefaultRoot`,
  프로젝트 설정으로 바꾼 폴더도 따른다), 묶기는 SWT `EditorUtils.setFileDataSource`와 같은 자원 속성 키(`DBConstants.PROP_RESOURCE_DEFAULT_DATASOURCE`,
  `PROP_RESOURCE_DEFAULT_PROJECT_ID`)와 같은 경로 모양(프로젝트 기준 `Scripts/a.sql`)이다. 그래서 SWT 시절의 스크립트와 묶기가 그대로 보이고 동작한다.
- **활성 프로젝트만.** SWT Project Explorer의 루트도 활성 프로젝트였다(`ProjectExplorerView.getRootNode`). Swing은 활성 프로젝트를 바꾸는 화면이 없다.
- **`java.nio`로 다룬다.** Swing 화면은 Eclipse 자원 트리(`IResource`)를 쓰지 않는다. 자원 트리를 거치면 동기화(새로 고침)만 늘어난다.
- **파일 감시 없음, 저장 뒤와 명령 뒤에 다시 읽기.** 이 창과 메인 창의 저장이 바꾸는 경우를 모두 다시 읽는다. 탐색기에서 바꾼 것은 Refresh로 읽는다.
  Query Library처럼 공유 폴더를 여러 사람이 쓰는 기능이 아니어서 감시 비용을 들이지 않았다.
- **이름·같은 이름 처리는 Query Library와 같다.** 파일 이름으로 쓸 수 없는 글자는 `_`(`QueryLibraryFile.toFileName`), 같은 이름은 `이름 (2)`
  (`QueryLibrary.uniquePath`), 삭제는 휴지통 우선. 같은 도구 창 두 곳의 규칙이 달라지지 않게 했다.
- **삭제 확인의 기본 단추는 취소**(`SwingPlatformUI.confirmChange`, 앱의 공통 규칙).
- **열린 스크립트는 지우지 않는다.** 지우면 쿼리 창의 글이 파일 없이 남고, 저장하면 지운 파일이 되살아난다. 먼저 창을 닫게 한다.
- **이름 바꾸기·삭제는 자원 속성도 옮기거나 지운다.** 옮기지 않으면 묶기가 사라지고, 지우지 않으면 같은 이름으로 새로 만든 스크립트가 지운 스크립트의
  연결을 물려받는다. 폴더는 그 아래 모든 경로의 속성을 옮긴다(`DBPProject.findResources`에 조건 없이 물어 모든 속성 경로를 얻음).
  속성을 지울 때는 항목째 지운다(`BaseProjectImpl.resetResourceProperties`, 키를 하나씩 지우면 메타데이터 파일에 빈 항목이 남는다).
- **묶기를 바꿔도 열린 창의 연결은 그대로.** SWT는 열린 편집기의 연결도 바꿨다. Swing에서 창의 연결을 바꾸면 그 창의 세션이 끝나므로 커밋하지 않은
  트랜잭션을 물어야 한다(트랜잭션 안전 규칙). 묶기는 다음에 열 때 적용한다. 2026-11-12부터는 쿼리 창이 한 서버의 창이라 열린 창의 연결을 바꾸는 길 자체가 없다([swing-frontend.md](swing-frontend.md) "쿼리 창은 한 서버의 창").
- **저장할 때는 묶기가 없을 때만 묶는다.** SWT는 편집기의 연결이 바뀔 때마다 묶기를 덮어썼다. Swing은 사용자가 고른 묶기를, 다른 연결로 열어 둔
  창의 저장이 몰래 바꾸지 않게 했다. 새로 저장한 스크립트는 그 창의 연결에 묶여 다음에도 같은 연결로 열린다.
- **도구 막대 단추는 두지 않았다(주 도구 막대).** 도구 창 켜고 끄기는 View 메뉴와 Ctrl+Alt+L이다. 주 도구 막대의 도구 창 단추 줄에 아이콘을 더하려면
  새 아이콘이 필요해 이번 범위에서 뺐다.

## upstream 대비 변경점

- SWT 보기를 옮기지 않고 Swing 도구 창으로 새로 썼다. 자원 처리기(`DBPResourceHandler`)와 Eclipse 자원 트리를 쓰지 않는다.
- 없는 것(SWT에는 있었음): 프로젝트 관리(여러 프로젝트, 활성 프로젝트 바꾸기, C-21), 새 스크립트 파일 만들기(새 쿼리 창을 스크립트 폴더에 저장하면 된다),
  끌어다 옮기기, 파일·폴더 링크, 파일 시스템 탐색기, 시스템 탐색기에서 보기, Object Explorer의 연결 아래 Scripts 폴더 노드(목록은 Recent Scripts 메뉴),
  북마크, 스크립트 폴더 밖 파일의 연결 기억(SWT `DBPExternalFileManager`), 묶을 때 기본 DB/스키마 기억(`default-catalog`, `default-schema`, 읽지도 쓰지도 않는다.
  SWT가 쓴 값은 그대로 둔다), 여러 개 선택.

## 확인

회귀 테스트는 만들지 않았다(요청 없음). 앱 사본에서 확인한 내용은 아래 "확인 기록"에 있다.

### 확인 기록 (2026-11-08, Windows, 이 브랜치 빌드, 작업 공간 사본, 프로세스 안 드라이버로 조작)

작업 공간 사본의 `General/Scripts`에 스크립트·폴더·`.txt` 파일을 두고, SWT가 쓰던 모양으로 `project-metadata.json`에 묶기 둘을 미리 적었다.

| 확인 | 결과 |
|---|---|
| View > Scripts | 오른쪽 열 Properties 위에 열림. 루트 `Scripts`, 폴더 먼저, `.txt`는 안 보임. SWT 모양으로 묶은 두 스크립트 뒤에 연결 이름 |
| SWT 묶기 스크립트에서 Enter | `swt-linked.sql - PostgreSQL 테스트` 탭으로 열림(Object Explorer 선택과 관계없이 묶은 연결) |
| Link to Connection… → MariaDB | `project-metadata.json`에 `default-datasource`, `default-projectId` 기록 |
| 열린 스크립트 F2 이름 바꾸기 | 파일 이름, 메타데이터 키, 열린 탭 제목이 함께 바뀜 |
| 열린 스크립트 Delete | "is open in a query window" 알림, 지우지 않음 |
| 다른 스크립트 Delete | 확인 대화상자의 초점이 Cancel. Delete를 누르면 파일이 휴지통으로, 메타데이터 항목도 지워짐 |
| 폴더에서 New Folder | 하위 폴더가 만들어지고 선택됨 |
| SQL Server 쿼리 창을 Save As로 `Scripts/reports`에 저장 | Scripts 창에 바로 보이고 SQL Server 연결에 묶임 |
| Remove Connection Link | 메타데이터 항목이 지워짐 |
| 열린 스크립트가 든 폴더 이름 바꾸기 후 Save | 메타데이터 경로가 옮겨지고, 창이 새 폴더의 파일에 저장(옛 폴더가 다시 생기지 않음) |
| 한국어(`-nl ko`), 다크 테마 | 메뉴 `스크립트`, 오른쪽 클릭 메뉴 `열기, 연결에 묶기…, 연결 묶기 해제, 새 폴더, 이름 바꾸기, 삭제, 새로 고침` |
| 고친 것 | 선택한 행의 연결 이름이 길면 트리가 오른쪽으로 스크롤되어 폴더 손잡이가 가려졌다. 선택을 되살릴 때 세로로만 스크롤하게 고침 |

## 변경 이력

- 2026-11-08 최초 작성: Scripts 도구 창(열기, 새 폴더, 이름 바꾸기, 삭제, 연결 묶기·풀기, 새로 고침), 묶은 연결로 파일 열기, 저장할 때 묶기
- 2026-11-08 연결별 최근 스크립트: Object Explorer > Recent Scripts(SWT 감사 C-22·B-S38의 연결별 스크립트 목록, `chromite/script-autosave`). 확인은
  [swing-query-window-recovery.md](swing-query-window-recovery.md) "확인 기록"
- 2026-11-08 기본 자리를 새 기본 배치(앱 변경 #12)에 맞춤: Properties 위 대신 Query Library 탭 → Properties 탭 → Chromite Agent 위 → 오른쪽 가장자리(`chromite/ui-layout`).
