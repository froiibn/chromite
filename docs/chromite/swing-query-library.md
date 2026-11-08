# Query Library (Swing)

## 목적

DBA가 자주 쓰는 상태 확인 쿼리(세션 수, 대기, 디스크 사용량 등)를 매번 `.sql` 파일에서 불러오지 않고, Chromite 안의 트리에서 관리하고 바로 실행한다.
저장 프로시저처럼 서버에 두는 것이 아니라 Chromite 쪽에 두는 평범한 SQL이다. Chromite 고유 기능이다(DBeaver에는 대응 기능이 없다).

사용자 로드맵과 상태:

| 단계 | 내용 | 상태 |
|---|---|---|
| ① | 라이브러리 트리 + 실행 | 구현 (2026-11-06) |
| ② | 매개변수 매핑 | 구현 (2026-11-06) |
| ③ | 예약 실행 (Chromite가 실행 중일 때만) | 구현 (2026-11-06, 아래 "예약 실행") |
| ④ | 다른 사람과 공유 | 구현 (2026-11-07, 아래 "공유 (④)"): 공유 폴더 자동 새로 고침, Git Pull·Commit & Push, 내보내기·가져오기, 저장 충돌 확인, Save As .sql |

## 구조

| 클래스 (`com.seizia.chromite.ui.swing`) | 역할 |
|---|---|
| `QueryLibraryPanel` | 왼쪽 도구 창 "Query Library": 트리, 도구 막대, 검색란, 오른쪽 클릭 메뉴, 끌어다 놓기, Query > Save to Query Library… |
| `QueryLibrary` | 저장소: 루트 목록, 폴더 트리 읽기, 쿼리 파일 만들기·저장·이름 바꾸기·옮기기·복제·삭제, 자연 정렬, ID → 파일 색인 |
| `QueryLibraryItem` | 쿼리 하나(파일 하나)의 값: 이름, 설명, SQL, 기본 대상, 기본 DB/스키마, 시각, 매개변수 정의, 모르는 머리말 줄 |
| `QueryLibraryParameter` | 매개변수 정의(표시 이름, 형식, 기본값, 필수, 선택 목록, 조회 쿼리)와 형식 변환·검사 |
| `QueryLibraryFile` | 파일 형식 읽기·쓰기(머리말 주석 + SQL) |
| `QueryLibraryState` | 이 PC에만 두는 쿼리별 상태(매개변수 마지막 입력값) |
| `QueryLibraryEditor` | 쿼리 창 확장: 이름·설명·대상·DB/스키마·매개변수 머리말과 저장 단추, 라이브러리로 저장, 실행 전 매개변수 묻기, 실행 대상 찾기 |
| `QueryLibraryDefaultsPicker` | 머리말의 Database·Schema 칸: 직접 입력할 수 있는 콤보와 연결의 DB/스키마 목록(작업 스레드에서 읽음), 연결이 쓰지 않는 칸 끄기 |
| `QueryParameterBinder` | 쿼리 창의 매개변수 바인딩 하나: `:name` → `?` + JDBC 바인딩, `${name}` → SQL 글자, 결과 다시 읽기의 바인딩, 기록용 글자·값, 기록에서 연 실행(`Recorded`), 평범한 쿼리 창의 엔진 매개변수 제공자(`ScriptProvider`), 연결 없이 매개변수 찾기 |
| `QueryParameterDialog` | 실행할 때 값을 묻는 대화상자 하나(라이브러리 쿼리, 기록에서 연 실행, 평범한 쿼리 창의 스크립트 모드) |
| `QueryParameterDefinitionsDialog` | 편집기의 "Parameters…": 매개변수 정의 표 |
| `QueryLibraryRootsDialog` | "Library Roots…": 루트 목록 |
| `SqlEditorExtension`, `SqlParameterBinding` | 쿼리 창(`SqlEditorPanel`)에 붙이는 확장 지점과 실행별 바인딩 계약 |
| `QueryLibrarySchedule` | ③ 예약 정의(간격·매일·평일, 대상 서버, 매개변수 값, 조건, 사용 여부)와 다음 실행 시각 계산 |
| `QueryScheduler` | ③ 예약 실행기(데몬 타이머 + 쿼리별 Job, 화면 없는 실행, 조건·알림) |
| `QueryLibraryHistory` | ③ 실행 기록(작업 공간 메타데이터의 쿼리별 TSV 파일, 보존 기간·개수) |
| `QueryScheduleDialog`, `QueryScheduledDialog`, `QueryRunHistoryDialog` | ③ Schedule…, Scheduled Queries…, History… 화면 (`QueryRunHistoryDialog`는 SQL 편집기 확장의 쿼리 창 기록 `QueryHistoryDialog`와 이름이 겹쳐 바꾼 이름) |
| `QueryLibraryWatcher` | ④ 자동 새로 고침: `WatchService` + 주기 확인, 폴더 목록·파일 크기·수정 시각 지문 비교 |
| `QueryLibraryGit` | ④ Git 작업 사본 루트: 시스템 `git`으로 상태(브랜치, 앞섬/뒤처짐, 충돌 파일), Pull, Commit & Push |
| `QueryLibraryArchive` | ④ `.chromite-queries` 내보내기·가져오기(검사, 크기·개수 제한, ID 충돌 선택) |

공유 파일에 넣은 연결 지점(다른 작업과 겹치는 곳을 줄이기 위해 작게 둠):

| 파일 | 바꾼 것 |
|---|---|
| `MainFrame` | Query Library 도구 창(오른쪽 열에서 Properties 위, `ToolWindow.stackBelow`(2026-11-06부터 도킹 기본 자리), 처음에는 숨김, View > Query Library, Ctrl+Alt+T), Query > Save to Query Library…, `QueryLibraryPanel.Host` 구현(창 열기·찾기, 현재 연결), 확장이 붙은 쿼리 창의 저장을 확장에 넘김 |
| `SqlEditorPanel` | `setExtension`(머리말을 도구 막대 아래에), `markModified`, 탭 아이콘, 실행 전 `prepareExecution`, 준비 Job에서 `SqlParameterBinding.prepare`, 엔진 핸들러의 바인딩 메서드, 결과의 데이터 컨테이너(`SqlParameterBinding.createDataContainer`), 기록에서 연 실행(`setRecordedParameters`) |
| `ToolWindow` | ~~`setDefaultShown(false)`~~: 처음에는 숨긴 도구 창(Reset Window Layout도 숨김으로 되돌림). 2026-11-06부터 `ToolWindowDocking.addToolWindow(..., false, ...)` |
| `SQLQueryExecuteHandler`, `SQLQueryExecuteJob` (model.sql, Chromite 실행 엔진) | `hasBoundParameters`/`bindParameters`: 핸들러가 값을 바인딩하는 쿼리는 스크립트 문장 대신 prepared statement로 만들고 바인딩. 기본값은 바인딩 없음이라 SWT 편집기 동작은 같다 |
| `SQLQueryDataContainer`, `SQLQueryColumnValues` (model.sql, 2026-11-08) | `ParameterBinder`: 바인딩 방법이 있는 컨테이너는 다시 읽을 때 필터를 넣은 최종 글자에서 찾은 `:name`을 그 방법으로 `?`로 바꾸고 prepared statement로 바인딩한다. 열 필터 값 목록도 같은 방법을 넘겨받는다. 없으면 예전과 같다 |
| `QueryHistory`, `QueryHistoryDialog`, `MainFrame` (2026-11-08) | 기록의 `parameters`(바인딩한 값·형식), 미리 보기의 값 주석 줄, Open·Execute가 기록 전체를 넘겨 다시 바인딩 |
| `tools/branding/gen_ui_icons.py` | 아이콘 `library`(Tabler books), `library-query`(문서 + 실행 배지), `new-folder`(폴더 + 더하기 배지), ③ `schedule`(Tabler clock), 두 팩·두 테마. History…는 SQL 편집기 확장의 `history` 아이콘(Tabler history)을 함께 쓴다 |
| `OptionsDialog` (③, ④) | Tools > Options의 "Query Library" 절: 기록 보존 실행 수·일수, ④ 폴더 확인 간격 |
| `MainFrame` (④) | 확장이 붙은 쿼리 창의 Save As는 `SqlEditorExtension.saveAs`(라이브러리 쿼리는 SQL만 평범한 `.sql`로 내보냄) |
| `SqlEditorExtension` (④) | `saveAs` 추가(Save As를 저장과 나눔) |
| `SqlFileContent` (④) | `parse(byte[])`: 디스크에 없는 바이트(묶음 파일 항목)를 같은 문자 집합·BOM·줄바꿈 규칙으로 읽음 |
| `SqlEditorPanel`, `QueryLibraryPanel.Host`, `MainFrame` (2026-11-08) | 머리말 저장 단추: 고침 상태 속성 `DIRTY_PROPERTY`(바뀔 때 알림), `Host.getSaveAction`(메인 창의 Save 동작을 넘김) |

## 화면

```
┌ Registered Servers ┐┌ 문서 탭 ...   ┐┌ Query Library ─────────── — ┐
│                    ││               ││ [▶] | [+문서] [+폴더] | [⟳] [⚙] │
├ Object Explorer ───┤│               ││ [🔍 Search queries        ] │
│                    ││               ││ 📚 My Queries               │
│                    ││               ││  └ 📁 01. 상태 모니터링      │
│                    ││               ││     ├ ▶ 01. 세션 수   QL PostgreSQL
│                    ││               ││     ├ ▶ 02. 대기      ask
│                    ││               ││ 📚 Team (shared)  read-only │
│                    ││               │├ Properties ──────────────── ┤
```

- **열기:** View > Query Library(Ctrl+Alt+T). 오른쪽 열에서 Properties 위에 쌓는다(`ToolWindow.stackBelow`, SSMS의 Template Browser처럼 오른쪽 도킹. 2026-11-06부터 도킹의 기본 자리이고 끌어서 옮길 수 있다: [swing-frontend.md](swing-frontend.md) "도구 창 도킹").
  처음에는 숨김이고 Window > Reset Window Layout도 숨김으로 되돌린다. 처음 설계는 Object Explorer와 문서 사이의 자기 열이었으나, 1920px 창에서도
  문서 영역이 좁아져 시작 화면 글자가 잘려 합칠 때 오른쪽 열로 옮겼다(되돌리기: `MainFrame`에서 `propertiesWindow.stackBelow(libraryWindow)` 대신
  예전처럼 가로 분할 열을 만든다).
  Properties를 숨긴 사이에 보였다가 Properties를 다시 보여도 같은 열에 쌓인다(2026-11-07, [swing-frontend.md](swing-frontend.md) "Query Library 자리").
  **2026-11-08 바뀜(앱 변경 #12, 사용자 결정):** 기본 배치에서 보이고 오른쪽 열 위(0.4), 아래는 Chromite Agent다. Properties는 기본 숨김이고 열면 Query Library의 탭이다.
  Reset Window Layout도 이 배치로 되돌린다([swing-frontend.md](swing-frontend.md) "기본 배치: Query Library와 Chromite Agent").
  Ctrl+Alt+T는 SSMS의 Template Browser 단축키이고 이 번들에서 비어 있다(2026-11-06 `KeyStroke.getKeyStroke` 전체 검색).
- **트리:** 루트(책 아이콘, 읽기 전용이면 "read-only") → 폴더 → 쿼리(이름 뒤 보조 글자: 고정 대상 연결 이름, 또는 "ask"). 폴더가 쿼리보다 앞이고, 둘 다 자연 정렬("2. …" < "10. …").
  도구 설명은 설명과 파일 경로다. 모양(아이콘 팩, 행 높이, 마우스 올림 둥근 바탕, 보조 글자)은 Object Explorer와 같다.
- **검색:** 이름·설명에 글자가 들어 있는 쿼리와 상위 폴더만 남기고 모두 펼친다. 아래 화살표로 트리로 간다.
- **명령:**

  | 명령 | 방법 |
  |---|---|
  | Run | Enter, 도구 막대 ▶, 메뉴(굵게) |
  | Run on… | 메뉴: 연결을 골라 실행 |
  | Edit | 더블클릭, 메뉴 |
  | New Query… / New Folder… | 도구 막대, 메뉴 (선택한 폴더, 쿼리를 골랐으면 그 폴더) |
  | Duplicate | 메뉴, Ctrl+D (읽기 전용 루트의 쿼리는 첫 편집 가능 루트로 복사) |
  | Rename… | F2, 메뉴 (쿼리는 머리말의 이름과 파일 이름을 함께 바꿈) |
  | Delete | Delete 키, 메뉴 (확인 후 휴지통, 휴지통이 없는 시스템이면 삭제) |
  | Open Folder | 메뉴: 파일 탐색기에서 폴더 열기(공유 폴더·Git 작업에 편함) |
  | Save As .sql… | 메뉴(쿼리): SQL만 평범한 `.sql` 파일로 내보냄 (④) |
  | Export… / Import… | 메뉴: 폴더를 `.chromite-queries`로 내보냄(폴더·루트) / 선택한 폴더로 가져옴 (④) |
  | Pull / Commit & Push… | 메뉴: 루트가 Git 작업 사본일 때만 보임 (④) |
  | Refresh | F5(트리에 초점이 있을 때, Object Explorer와 같은 규칙), 도구 막대 |
  | Library Roots… | 도구 막대 ⚙, 메뉴 |
  | 끌어다 놓기 | 쿼리·폴더를 다른 폴더로 옮김. 파일 탐색기의 `.sql` 파일을 놓으면 그 폴더로 복사 |

  읽기 전용 루트에서는 쓰기 명령(새로 만들기, 이름 바꾸기, 삭제, 놓기)을 끈다.
- **Save to Query Library…** (Query 메뉴): 활성 쿼리 창의 선택(없으면 전체)을 새 쿼리로 저장한다. 이름(창 파일 이름), 설명, 폴더(편집 가능한 루트의 모든 폴더),
  "Run on <연결> by default"(창의 연결을 고정 대상으로)를 묻고, 저장 뒤 도구 창을 보여 주고 새 쿼리를 고른다.

## 저장 형식

쿼리 하나 = `.sql` 파일 하나. 폴더 구조 = 트리. 별도 색인 파일이 없다.

```sql
-- @chromite-query: 1
-- @id: 0d7b2c1e-5c1f-4a57-9a52-3c1f2f3d9e10
-- @name: 02. 오래 실행 중인 세션
-- @description: :min_minutes분 넘게 실행 중인 세션
-- @target: connection
-- @target-id: postgres-jdbc-19a2b3c4d5e-6f7a8b9c0d1e2f3a
-- @target-name: QL PostgreSQL
-- @database: postgres
-- @created: 2026-11-06T10:15:00+09:00
-- @param min_minutes
--   label: 최소 경과(분)
--   type: number
--   default: 5
--   required: true
-- @param state
--   type: choice
--   choice: active
--   choice: idle in transaction

SELECT pid, usename, state, now() - query_start AS elapsed
FROM pg_stat_activity
WHERE state = :state
  AND now() - query_start > make_interval(mins => :min_minutes)
```

| 항목 | 뜻 |
|---|---|
| `@chromite-query` | 형식 버전(지금 1). 아직 이 값에 따라 달라지는 것은 없다 |
| `@id` | UUID. 파일을 옮기거나 이름을 바꿔도 그대로다. 이 PC의 상태(마지막 입력값, 예약 활성 여부)를 찾는 열쇠 |
| `@name`, `@description` | 트리에 보이는 이름과 설명 |
| `@target` | `ask`(실행할 때마다 묻기) / `current`(Object Explorer에서 고른 연결, 없으면 활성 쿼리 창의 연결, 없으면 묻기, 기본값) / `connection`(정해진 연결) |
| `@target-id`, `@target-name` | 정해진 연결의 데이터 소스 ID와 이름. 실행할 때 ID로 찾고, 없으면 이름으로 찾는다(동료 PC는 ID가 다름). 둘 다 없으면 연결을 고르게 한다 |
| `@database`, `@schema` | 실행 전에 연결의 기본 DB(카탈로그)/스키마를 바꾼다(쿼리 창의 DB/스키마 콤보와 같은 `DBExecUtils.setExecutionContextDefaults`). 비우면 연결의 것을 그대로 쓴다 |
| `@created` | 만든 시각, ISO-8601(초 단위, 지역 오프셋). 저장할 때 바뀌지 않는다 |
| ~~`@updated`~~ | 2026-11-08부터 쓰지 않는다. 읽으면 버리고 다음 저장 때 파일에서 빠진다(아래 "고친 시각을 파일에 두지 않음") |
| `@param <이름>` + 하위 줄 | 매개변수 정의(`label`, `type`, `default`, `required`, `choice`(여러 줄), `lookup`). 아무 정의가 없는 매개변수는 쓰지 않는다 |

규칙(`QueryLibraryFile`):

- 머리말은 파일 맨 앞에서 `-- @`로 시작하는 줄과 그 아래 `--   `(하이픈 둘 + 공백 셋)으로 시작하는 하위 줄이다. 다른 줄이 나오면 끝나고, 바로 뒤의 빈 줄 하나는 구분 줄이다.
  값 안의 줄바꿈과 역슬래시는 `\n`, `\\`로 쓴다(한 항목 = 한 줄이라 Git 비교가 읽기 쉽다).
- 항목 순서는 늘 같고 값이 없는 항목은 쓰지 않는다. 저장할 때마다 바뀌는 항목도 없다. 그래서 같은 내용을 다시 저장하면 줄이 바뀌지 않는다.
- 모르는 항목(새 버전의 항목, 예: 공유·예약 정보)은 원래 줄 그대로 머리말 끝에 다시 쓴다. 다른 버전이 쓴 정보를 지우지 않는다.
- 머리말 전체가 SQL 주석이라 파일을 그대로 다른 SQL 도구에서 열고 실행할 수 있다(`:name`은 그 도구의 매개변수 문법에 따름).
- 머리말이 없는 `.sql` 파일(다른 도구에서 만든 파일, 탐색기에서 끌어다 놓은 파일)도 쿼리로 보인다. 이름은 파일 이름이고, Chromite에서 저장하면 머리말과 ID가 붙는다.
- 문자 집합·BOM·줄바꿈은 기존 파일의 것을 유지한다(`SqlFileContent`). 새 파일은 UTF-8(BOM 없음), LF다. 운영체제에 따라 공유 파일의 줄바꿈이 바뀌지 않게 하기 위해서다.
- 파일 이름은 쿼리 이름에서 만든다: 파일 시스템이 거부하는 글자(`\ / : * ? " < > |`, 제어 문자)는 `_`, 끝의 점·공백은 뺌, 120자까지. 같은 이름이 있으면 ` (2)`를 붙인다.
  한글 이름이 그대로 파일 이름이 된다(예: `02. 오래 실행 중인 세션.sql`).
- 이름이 `.`으로 시작하는 폴더·파일(`.git` 등)은 읽지 않는다.

### 고친 시각을 파일에 두지 않음 (2026-11-08)

- 예전에는 저장할 때마다 `@updated`(고친 시각)를 바꿨다. 두 사람이 같은 쿼리를 동시에 고치면 SQL의 다른 줄을 고쳤어도 이 줄이 늘 Git 충돌이 되었다
  (2026-11-07 "Git 루트"의 알려진 점).
- 이 줄은 충돌 확인에 쓰이지 않았다. 저장 충돌 확인(④ "열린 창과 저장 충돌")은 창이 다루는 항목을 파일 형식 글자로 비교하고(`QueryLibraryEditor.getEditedContent`),
  `@updated`는 비교에서 빼고 있었다. `@updated`를 쓰던 곳은 충돌 질문의 "마지막 수정 {1}" 글자 하나뿐이었다.
- 고른 방법: `@updated`를 파일에서 뺀다. 충돌 질문의 시각은 파일의 수정 시각(`Files.getLastModifiedTime`)이다. 파일 시스템이 이미 갖고 있는 정보라
  따로 저장할 필요가 없고, 공유 폴더에서는 다른 사람이 저장한 시각이며, Git pull 뒤에는 pull한 시각이다(누가 언제 고쳤는지는 Git 기록이 정확하다).
- 고르지 않은 방법: Git 상태로 시각 얻기(Git 루트에만 쓸 수 있고 `git log`를 불러야 함), `@updated`를 남기고 Pull 때 충돌 줄을 Chromite가 고르기
  ("Chromite는 합치지 않는다"를 어김), 고친 사람·시각을 이 PC 설정에 두기(공유 루트에서 다른 사람의 저장을 알 수 없음).
- 옮겨 가기: 예전 파일의 `@updated` 줄은 읽을 때 버리므로(모르는 항목으로 보관하지 않음) 다음 저장 때 빠진다. 두 사람이 같은 파일을 각각 저장해 둘 다 이 줄을 지워도
  Git은 같은 변경으로 보고 충돌 없이 합친다. `@created`는 만들 때 한 번만 써서 충돌하지 않으므로 그대로 둔다.

## 저장 위치와 루트

- 기본 루트 "My Queries": 작업 공간 폴더의 `QueryLibrary`(`DBPWorkspace.getAbsolutePath()/QueryLibrary`, 예: `-data D:\ws`이면 `D:\ws\QueryLibrary`).
  사용자 홈이나 C: 드라이브 경로에 묶이지 않고 작업 공간과 함께 옮겨진다. 처음 읽을 때 없으면 만든다.
- **여러 루트:** "Library Roots…" 대화상자에서 루트(이름, 폴더, 읽기 전용)를 더하고 뺀다. 개인 루트 + 공유 루트(네트워크 폴더, Git 작업 사본)를 함께 쓰는 구조다.
  목록은 이 번들 설정 저장소의 `queryLibrary.roots`(한 줄에 `이름<TAB>경로<TAB>읽기 전용`)에 저장한다. 루트를 빼도 파일은 지우지 않는다. 목록이 비면 개인 루트를 다시 넣는다.
- Tools > Options에 넣지 않은 이유: 같은 시간에 다른 작업이 `OptionsDialog`를 바꾸고 있어(편집기 글꼴·색) 겹침을 피했다. 루트 목록은 표 하나라 도구 창의 ⚙에서 바로 여는 편이 쓰기도 쉽다.
- **바깥 변경:** 트리는 디스크를 그대로 보여 주므로, 동료가 공유 폴더의 파일을 바꾸거나 `git pull` 하면 저절로 다시 읽어 반영한다(④ "자동 새로 고침", Refresh도 된다).
  Run·Edit는 트리를 읽은 시점이 아니라 그때의 파일을 다시 읽어 쓴다. 열려 있는 편집 창도 다시 읽을 때마다 확인한다(④ "열린 창과 저장 충돌").
- 읽기는 작업 스레드(`AbstractJob`)에서 모든 루트를 읽고 EDT에서 트리를 다시 만든다(펼친 폴더·선택은 경로로 되살림). 네트워크 폴더가 느려도 화면이 멈추지 않는다.
- 같은 쿼리가 옮겨졌을 때: 읽을 때 ID → 파일 색인을 만들어, 편집 중인 쿼리의 파일이 트리에서 옮겨졌어도 저장할 때 ID로 새 위치를 찾는다.

## 실행 흐름

1. **Run**(Enter): 파일을 다시 읽고(`QueryLibraryPanel.readCurrent`) 대상을 정한다(`QueryLibraryEditor.resolveTarget`).
   - `connection`: 모든 프로젝트에서 `DBPDataSourceRegistry.getDataSource(id)`, 없으면 `findDataSourceByName(name)`.
   - `current`: Object Explorer에서 고른 노드의 연결, 없으면 활성 쿼리 창의 연결.
   - 찾지 못했거나 `ask`면 연결 고르기 대화상자(이유 문구와 함께). **Run on…**은 늘 이 대화상자다.
2. 이 쿼리의 쿼리 창이 같은 연결로 열려 있고 실행 중이 아니면 그 창을, 아니면 새 쿼리 창을 연다. 탭 제목은 쿼리 파일 이름과 연결(`02. 대기.sql - QL SQL Server`)이고 아이콘은 `library-query`다.
   다른 서버에서 같은 쿼리를 나란히 보려고 연결마다 창을 따로 둔다.
3. 그 창에서 실행한다(`SqlEditorPanel.execute`). 실행 경로는 일반 쿼리 창과 같다: 준비 Job이 필요하면 연결하고(`SwingConnectionService`),
   `SQLScriptBatches.extractQueries`로 문장/`GO` 배치를 나누고, `SQLQueryExecuteJob`을 예약한다. 결과 표는 Results에 쌓이고 행 수·PRINT·오류는 Messages에 간다. EDT는 기다리지 않는다.
4. 실행 직전(EDT) 확장의 `prepareExecution`이 매개변수를 묻는다(아래). 취소하면 실행하지 않는다.
5. 준비 Job에서 `SqlParameterBinding.prepare`가 나눈 문장을 바꾸고(기본 DB/스키마 적용, `:name` → `?`), 엔진이 문장을 만들 때 `bindParameters`로 값을 바인딩한다.

**Edit**(더블클릭)는 같은 창을 연다(실행하지 않음). 편집기 위에 머리말이 붙는다(2026-11-07부터 쿼리 창 도구 막대는 메인 창 둘째 줄에 있다):

```
[▶ Execute] [■] | [연결 ▾] [DB/스키마 ▾]
Name [02. 대기          ]  Description [대기 이벤트 상위 10개                ]                   [저장 아이콘]
Target [QL SQL Server ▾]   [master        ▾] [Schema        ▾] [Parameters (1)…] [Thresholds…]
SELECT ...
```

- 편집기는 쿼리 창 그대로라 구문 색, 자동완성, 찾기·바꾸기, F5 실행(선택만 실행 포함)이 된다. 머리말을 바꾸면 탭에 `*`가 붙는다.
- **머리말 배치**(`QueryLibraryEditor.createHeader`, `GridBagLayout` 두 줄): 이름 칸·설명 칸·대상 콤보·Database·Schema 칸은 가중치가 있어 남는 너비를 나눠 갖고
  좁아지면 줄어든다(우선 크기와 최소 크기 70). Name·Target 글자와 Parameters…·Thresholds… 단추는 줄지 않는다: 최소 크기를 고정하지 않아 지금 글자의 크기
  ("Parameters (1)…", "매개변수(1)…")가 최소 크기다. 2026-11-08 전에는 만들 때 최소 크기를 고정했는데, 단추의 글자는 그 뒤에(`loadFields`) 들어가
  빈 단추의 크기(FlatLaf 최소 너비 72)가 최소 크기가 되었다. `GridBagLayout`은 줄이 우선 크기만큼 들어가지 않으면 모든 칸을 최소 크기로 두므로, 문서 영역이 좁은 창
  (1200 px 창에 Chromite Agent·Query Library 열을 연 경우 등)에서 단추가 "Para…", "Thres…"로 잘렸다. 칸의 최소 크기 합보다 좁으면 칸이 더 줄고 단추 글자는 그대로다.
  저장 아이콘 단추는 첫 줄 오른쪽 끝, Thresholds… 단추 위 칸에 오른쪽으로 붙는다(아래 "머리말 저장 단추").
- Target 콤보: "Ask when run", "Current connection", 연결 목록. 이 PC에 없는 연결이 저장된 쿼리는 "<이름> (not found)" 항목으로 보여 주고 그대로 저장한다(동료의 설정을 지우지 않음).
- 저장(Ctrl+S, File > Save, 닫을 때·끝낼 때 Save)은 `QueryLibrary.save`: 파일을 ID로 다시 찾고, 머리말과 SQL을 쓰고, 이름이 바뀌었으면 파일 이름도 바꾼다.
  읽기 전용 루트의 쿼리는 저장하지 않고 Duplicate를 안내한다. 다른 사람이 먼저 저장했으면 덮어쓰기 전에 묻는다(④).
  File > Save As는 라이브러리 쿼리를 바꾸지 않고 SQL만 평범한 `.sql`로 내보낸다(④ "Save As .sql").
- SQL에서 빠진 매개변수의 정의는 저장할 때 버린다.

### 머리말 저장 단추 (2026-11-08)

사용자: "쿼리라이브러리 편집 화면에서 저장버튼이 없다". 저장은 Ctrl+S, 주 도구 막대 Save, File > Save, 닫을 때 묻기로만 할 수 있었다.

- 머리말 첫 줄 오른쪽 끝에 저장 단추. 아이콘 팩의 `save` 아이콘만 보이는 단추이고(사용자 결정 2026-11-08: "아이콘으로만 표시"), 모양은 도구 막대의 아이콘 단추와 같다
  (`UiIcons.createActionButton`). 도구 설명은 "Save (Ctrl+S)"(한국어 "저장 (Ctrl+S)"), 화면 읽기 프로그램용 이름은 "Save"("저장")다.
- 누르면 메인 창의 Save 동작(`MainFrame.saveAction`, Ctrl+S)을 그대로 부른다(`QueryLibraryPanel.Host.getSaveAction`). 그 동작은 활성 쿼리 창을 저장하므로
  먼저 이 창을 앞에 둔다. 저장 경로가 하나라 읽기 전용 루트 안내, 이름 검사, 다른 사람이 먼저 저장했을 때의 질문이 단축키와 똑같다.
- 창이 고침 상태(탭 제목의 `*`)일 때만 켜진다. 쿼리 창이 고침 상태가 바뀔 때마다 `SqlEditorPanel.DIRTY_PROPERTY` 속성 변경을 알리고(글 입력, 머리말 변경,
  저장, 다시 읽기) 단추가 그것을 따른다. 저장하거나 Reload로 다시 읽으면 꺼진다.

### 머리말 DB/스키마 목록 (2026-11-08)

사용자: "쿼리라이브러리 대상에서 데이터베이스나 스키마는 자동으로 불러오지 못하는건가?". 두 칸은 자리 표시 글만 있는 글 칸이었다.

- 두 칸 모두 직접 입력할 수 있는 콤보다(`QueryLibraryDefaultsPicker`). 공유 쿼리는 다른 서버, 다른 DB 이름에서도 쓰므로 목록에 없는 값(저장된 값 포함)도
  그대로 보이고 저장된다. 자리 표시 글("Database", "Schema")은 그대로다.
- 목록을 읽는 연결: 대상이 정해진 연결이면 그 연결, "Ask when run"·"Current connection"이면 이 쿼리 창의 연결. 이 PC에 없는 대상 연결("(not found)")이나
  연결이 없는 창은 목록이 비고, 도구 설명 둘째 줄이 목록을 어디서 읽는지(또는 목록이 없는 이유)를 말한다.
- 목록은 펼칠 때 작업 스레드(`AbstractJob`)에서 읽는다. 연결되어 있지 않으면 그때 평소 연결 경로(`DBUtils.initDataSource` → `SwingConnectionService`:
  비밀번호 묻기, 오류 대화상자)로 연결한다. 머리말을 여는 것만으로는 연결하지 않는다. 읽은 뒤 열려 있는 목록은 새 항목 수에 맞게 다시 펼친다.
- 읽는 모델은 SQL Editor 도구 막대의 DB/스키마 콤보와 같다(데이터 소스의 `DBSObjectContainer`와 첫째 자식 형식, 같은 순서: 카탈로그 먼저).
  도구 막대의 `ContextDefaultObjectsReader`는 창 세션이 바꿀 수 있는 한 단계만 주므로 쓰지 않고 직접 읽는다.
  - 첫째 자식이 `DBSCatalog`: Database 목록은 카탈로그들. Schema 목록은 Database 칸의 카탈로그(대소문자 무시, 비었으면 연결의 기본 카탈로그)의 `DBSSchema` 자식.
    Database 칸에 이 연결에 없는 이름이 있으면 Schema 목록은 비어 있다.
  - 첫째 자식이 `DBSSchema`: Schema 목록만.
- 연결이 쓰지 않는 칸은 끈다(도구 막대 콤보와 같은 규칙): 카탈로그가 없으면 Database(Oracle 등), 카탈로그 아래 스키마가 없으면 Schema(MySQL·MariaDB 등).
  도구 설명 둘째 줄이 이유를 말한다. 다음 경우는 끄지 않는다.
  - 칸에 값이 있으면 지울 수 있게 켜 둔다.
  - "Ask when run"·"Current connection"은 실행할 때마다 다른 종류의 DB일 수 있어 끄지 않는다.
  - 무엇을 쓰는지는 연결되어 있어야 안다. 정해진 대상이 연결되어 있으면 머리말을 열 때(연결하지 않고) 읽고, 아니면 목록을 처음 펼칠 때 안다. 그 전에는 두 칸이 켜져 있다.

## 매개변수

### 찾기

- **실행할 때(권위):** SWT 편집기와 같은 모델 파서. `SQLScriptBatches.extractQueries` → `SQLScriptParser.extractScriptQueries(..., parseParameters = true)` →
  `SQLScriptParser.parseParametersAndVariables`([SQLScriptParser.java:914](../../plugins/com.seizia.chromite.model.sql/src/com/seizia/chromite/model/sql/parser/SQLScriptParser.java)) →
  `DefaultSQLQueryParameterParser.parseParametersAndVariables`([DefaultSQLQueryParameterParser.java:60-133](../../plugins/com.seizia.chromite.model.sql/src/com/seizia/chromite/model/sql/parser/DefaultSQLQueryParameterParser.java)).
  규칙 기반 스캐너의 `T_PARAMETER` 토큰이 `:name`(접두어는 연결 설정 `sql.parameter.prefix`, 기본 `:`)이고, `${name}`·`{{name}}`·`$P{name}` 변수는
  `SQLQueryParameter.getVariablePattern()`([SQLQueryParameter.java:32-33](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/model/sql/SQLQueryParameter.java))으로 찾는다.
  문자열·주석 안, PostgreSQL `::type`, DDL 문장, `$$` 블록 안은 파서가 건너뛴다. GO 배치는 배치 전체 범위를 다시 파싱한다(`SQLScriptBatches.addBatch`).
- **연결 없이(편집기의 Parameters…, 실행 대화상자 목록):** `QueryParameterBinder.detect`가 같은 규칙을 문자 단위로 따른다(작은따옴표·큰따옴표·백틱·`[]` 안, `--`·`/* */` 주석, `$tag$` 블록, `::`를 건너뜀).
  실행할 때 파서가 찾은 이름에 값이 없으면 실행하지 않고 "No value for parameter :x" 오류를 Messages에 쓴다.
- 익명 `?`(연결 설정에서 켠 경우)는 이름이 없어 정의를 붙일 수 없으므로 받지 않는다(오류). 연결 설정에서 매개변수를 끈 경우(`sql.parameter.enabled = false`) 파서가 찾지 않으므로 `:name`이 그대로 서버로 간다.

### 정의 (Parameters…)

| 열 | 뜻 |
|---|---|
| Parameter | `:name`, 또는 `${name} (SQL text)` |
| Label | 대화상자에 보일 이름(비우면 이름) |
| Type | Text, Number, Date(`yyyy-mm-dd`), Date and time(`yyyy-mm-dd hh:mm[:ss]`), Boolean, Choice list, Lookup query |
| Default | 기본값(처음 실행할 때 채움) |
| Required | 비우면 실행하지 않음 |
| Choices / lookup | Choice list는 `a|b|c`, Lookup query는 첫 열을 값 목록으로 쓰는 SQL(대상 연결에서 실행) |

### 실행할 때 (대화상자)

- 매개변수마다 한 줄: 표시 이름(필수면 `*`), 형식에 맞는 입력(글자·숫자·날짜는 입력란과 형식 힌트, Boolean·Choice는 콤보, Lookup은 편집할 수 있는 콤보), `${name}`은 "SQL text" 표시.
- 처음 값: 이 PC의 마지막 입력값(`QueryLibraryState`, 쿼리 ID별) → 없으면 정의의 기본값.
- Lookup 목록은 대상 연결에서 작업 스레드로 읽는다(필요하면 연결, 최대 1000개). 읽는 동안에도 입력할 수 있다.
- Run을 누르면 형식·필수를 검사하고(`QueryLibraryParameter.validate`), 틀리면 대화상자 안에 빨간 글자로 알리고 그 입력란으로 간다.
- 입력값은 쿼리 파일이 아니라 이 PC의 설정(`queryLibrary.lastValues.<id>`)에 저장한다. 공유 쿼리에 개인 값이 섞이지 않게 하기 위해서다.
  SWT 편집기의 `SQLQueryParameterRegistry`([SQLQueryParameterBindDialog.java:89-92, :327-332](../../plugins/com.seizia.chromite.ui.editors.sql/src/com/seizia/chromite/ui/editors/sql/dialogs/SQLQueryParameterBindDialog.java))는
  이름 하나에 값 하나를 모든 쿼리가 함께 쓰므로 쿼리별 기억에 맞지 않아 쓰지 않았다.

### 매개변수 바인딩

- **SWT 편집기의 방식:** `SQLEditor`가 `SQLEditorParametersProvider`([SQLEditor.java:2494](../../plugins/com.seizia.chromite.ui.editors.sql/src/com/seizia/chromite/ui/editors/sql/SQLEditor.java))로
  `SQLQueryParameterBindDialog`를 띄우고, 엔진이 `SQLScriptContext.fillQueryParameters`([SQLScriptContext.java:299-353](../../plugins/com.seizia.chromite.model.sql/src/com/seizia/chromite/model/sql/SQLScriptContext.java))
  → `SQLUtils.fillQueryParameters`([SQLUtils.java:1446-1461](../../plugins/com.seizia.chromite.model/src/com/seizia/chromite/model/sql/SQLUtils.java))로 **값을 SQL 글자로 치환**한다
  (사용자가 `'text'`처럼 따옴표까지 입력). 네이티브 바인딩은 Firebird `EXECUTE BLOCK`(`FireBirdStatementProducer`)뿐이다.
- **Query Library의 방식:** 남이 만든 쿼리에 값만 넣는 용도라 글자 치환은 SQL 주입과 따옴표 실수를 부른다. 그래서
  1. 준비 Job에서 `QueryParameterBinder.prepare`가 파서가 찾은 `:name` 자리를 `?`로 바꾸고, 형식에 맞는 Java 값(`QueryLibraryParameter.toJdbcValue`: `String`, `Integer`/`Long`/`BigDecimal`,
     `java.sql.Date`, `Timestamp`, `Boolean`, 빈 값 = SQL NULL)을 쿼리의 데이터(`SQLQuery.setData`)로 붙인다. 같은 이름이 여러 번 나오면 자리마다 같은 값이다.
  2. 엔진(`SQLQueryExecuteJob.executeStatement`)이 `handler.hasBoundParameters`가 참인 쿼리는 `DBCStatementType.QUERY`(JDBC `PreparedStatement`)로 만들고
     `handler.bindParameters` → `PreparedStatement.setObject/setNull`로 바인딩한다. 바꾼 쿼리는 매개변수 목록이 없으므로 엔진의 글자 치환(`fillQueryParameters`)은 아무것도 하지 않는다.
  - 정수는 `Integer`(범위를 넘으면 `Long`)로 바인딩한다. PostgreSQL의 `make_interval(mins => ?)`처럼 정수만 받는 함수에 `BigDecimal`(numeric)은 맞지 않기 때문이다.
  - 결과를 다시 읽을 때(결과 뷰어의 필터·서버 정렬·다음 페이지, 열 필터 값 목록, Export Data…, 편집 저장 뒤 행 다시 읽기)도 같은 값을 바인딩한다
    (2026-11-08, 아래 "매개변수 대화상자와 바인딩 하나로"). 2026-11-06부터 2026-11-08까지는 값이 그 실행의 핸들러에만 있어 다시 읽으면 `?`가 값 없이 나갔으므로
    (PostgreSQL "syntax error at end of input") 이 기능들을 막았다.
  - SQL Server `GO` 배치는 배치 하나가 prepared statement 하나다(`DECLARE`한 변수와 `?`를 함께 쓸 수 있음). JDBC가 아닌 연결은 바인딩할 수 없어 오류로 끝난다.
  - Query History(쿼리 창 기록)에는 엔진이 보낸 `?` 문장이 아니라 `:name`이 남은 문장(`${name}`은 값을 넣은 글자)과 바인딩한 값·형식을 남긴다(2026-11-08).
    기록에서 Execute하면 같은 값을 다시 바인딩하고, Open한 창은 실행할 때 기록된 값을 채운 대화상자로 묻는다(아래).
    2026-11-06에는 값을 SQL 리터럴로 넣은 글자를 남겼는데, 리터럴은 서버마다 뜻이 달라 다시 실행하면 처음과 다를 수 있었다
    (Oracle의 날짜 문자열은 세션의 `NLS_DATE_FORMAT`에 따라 바뀌고, SQL Server에는 불리언 리터럴이 없어 오류).
- **`${name}`·`{{name}}` 변수(표시: "SQL text"):** SWT에서도 텍스트 치환 변수이고 식별자·SQL 조각 자리(테이블 이름, `ORDER BY` 열 등)라 바인딩할 수 없다.
  값을 **그대로 SQL에 끼운다**. 대화상자와 정의 표에 "SQL text"로 표시하고, 쓰는 사람이 이 점을 알도록 도구 설명을 단다. 신뢰하지 않는 값에는 `:name`을 쓴다.

### 매개변수 대화상자와 바인딩 하나로 (2026-11-08)

후속 작업 #9(대화상자·바인딩 합치기), #22(바인딩한 결과 다시 읽기), #19(기록에 원래 글자와 값). 매개변수 입력은 쿼리 창의 세 경로에서 같은 대화상자와 같은 바인딩 클래스를 쓴다.

| 경로 | 언제 묻나 | 대화상자 | 값이 들어가는 방법 |
|---|---|---|---|
| Query Library 쿼리 창 | 실행 전(EDT), 실행할 글자 전체 | `QueryParameterDialog.open`: 정의의 형식·필수·선택 목록·조회 쿼리, 이 PC의 마지막 값 | `:name` 바인딩, `${name}` SQL 글자 |
| 기록에서 연 바인딩 실행 | 실행 전(EDT). History의 Execute는 묻지 않고 기록된 값 | 같은 `open`: 기록된 형식, 기록된 값(입력하면 그 창의 다음 실행에 씀) | `:name` 바인딩(`QueryParameterBinder.Recorded`) |
| 평범한 쿼리 창 | 스크립트가 실행되는 동안 엔진이 값 없는 매개변수를 만날 때(문장마다) | `QueryParameterDialog.openForScript`: 위에 그 문장, Execute / Ignore / Cancel, 매개변수 기록(`parameter-bindings.xml`) | SQL 글자(엔진의 `fillQueryParameters`) |

- **대화상자:** 예전 쿼리 창 대화상자(`SqlParameterDialog`, 이름·값 표)를 지우고 `QueryParameterDialog`에 스크립트 모드를 더했다. 줄마다 표식(`:name`, `${name}`, `?`)을
  도구 설명으로 보이고, SQL 글자로 들어가는 줄은 "SQL text" 힌트를 단다. 스크립트 모드의 문장 미리 보기, Ignore(이 스크립트 끝까지 매개변수 무시), 아래 안내
  ("값은 SQL 글자로 들어가니 문자열은 따옴표로"), 제목 "Bind Parameters", 단추 "Execute", 값 기억(`SQLQueryParameterRegistry`)은 예전 그대로다.
  바뀐 점: 표의 칸 대신 입력란이라 Enter가 바로 Execute이고(예전 표는 Enter가 다음 줄로 감), 열면 첫 입력 칸에 초점이 간다(라이브러리 쿼리도 같음).
  값의 앞뒤 공백은 지운다(라이브러리 대화상자와 같음, SQL 글자의 뜻은 바뀌지 않는다).
- **평범한 쿼리 창을 바인딩으로 바꾸지 않은 이유:** SWT·DBeaver 사용자는 `:name` 값에 `'abc'`처럼 따옴표까지, 또는 `(1,2,3)`·열 이름 같은 SQL 조각을 넣는다.
  바인딩으로 바꾸면 이런 스크립트가 깨진다. 또 스크립트 변수(`@set x = …`)가 실행 중에 값을 주므로 실행 전에 한 번에 물을 수 없다. 그래서 이 경로는 엔진의
  매개변수 제공자(`QueryParameterBinder.ScriptProvider`, 예전 `SqlParameterDialog.Provider`)로 남기고 대화상자만 합쳤다.
- **바인딩 클래스:** `QueryParameterBinder` 하나가 바인딩(`prepare`/`bind`), 다시 읽기, 기록, 기록에서 연 실행, 스크립트 제공자를 맡는다.
  Chromite Agent의 Query Library 도구(`LibraryTools.bind`, model.ai.tools)는 UI 번들에 기댈 수 없어 같은 원칙의 자기 바인딩을 따로 갖는다(이번에 합치지 않음).
- **다시 읽기(#22):** `SqlParameterBinding.createDataContainer`가 결과의 데이터 컨테이너를 만든다. 바인딩한 쿼리는 `?` 글자 대신 `:name`이 남은 글자(`${name}`은
  값을 넣은 것)를 갖고, 바인딩 방법(`SQLQueryDataContainer.ParameterBinder`, 구현은 바인딩 자신)을 받는다. 다시 읽을 때 컨테이너는 필터·정렬을 넣은
  최종 글자에서 매개변수를 다시 찾고(원래 있던 동작), 스크립트 문맥의 글자 치환 대신 바인딩 방법으로 `?`로 바꿔 prepared statement에 이름으로 바인딩한다.
  `?` 글자를 그대로 두고 순서대로 바인딩하는 방법은 고르지 않았다: 헤더 정렬이 쿼리의 ORDER BY를 바꾸거나 열 필터 값 목록이 ORDER BY를 지우면 그 안의 `?`가 빠져
  순서가 어긋난다. 이름으로 다시 찾으면 빠진 표식은 그냥 없다. 필터 조건 값은 방언이 글자로 넣으므로(`appendConditionString(..., inline = true)`) 새 표식이 생기지 않는다.
  결과 표의 Export Data…, 필터, 서버 정렬, 다음 페이지, 열 필터 값 목록, 편집 저장 뒤 행 다시 읽기가 이 컨테이너를 쓴다. 값은 쿼리 창이 실행한 그 값이다
  (같은 창에서 다른 값으로 다시 실행하면 새 결과 표가 새 값을 갖는다).
- **기록(#19):** 위 "매개변수 바인딩"의 Query History. 기록 파일(`chromite-query-history.jsonl`)의 항목에 `parameters`(이름, 입력한 값, 바인딩 형식)가 붙는다.
  형식은 숫자·날짜·날짜와 시각·불리언이고 나머지(선택 목록, 조회 쿼리)는 텍스트다(목록은 기록하지 않음). 이 항목이 없는 예전 기록은 예전처럼 글자만이다.
  History 창의 미리 보기는 문장 아래에 `-- :name = 값` 주석 줄로 값을 보인다. 기록에서 연 창에서 새 `:x`를 쓰면 그것도 텍스트로 바인딩한다(그 창은 바인딩 창이다).

## 예약 실행 (③)

사용자 결정(2026-11-06): **Chromite가 실행 중일 때만 앱 안에서 예약 실행한다.**

| 방식 | 판단 |
|---|---|
| 앱 안 스케줄러 (Chromite 실행 중에만) | **채택, 구현**. 연결·자격 증명·드라이버·알림을 그대로 쓰고, 설치·권한이 필요 없다 |
| OS 스케줄러(작업 스케줄러/cron) + 헤드리스 실행기(CLI) | **기각 — 사용자 결정 2026-11-06**. (참고로 남기는 단점: 저장된 비밀번호를 헤드리스 프로세스가 읽어야 하고, OS별 등록·해제와 결과를 앱에 되돌리는 경로가 따로 필요) |

### 정의 (쿼리 파일, 공유됨)

트리의 쿼리 오른쪽 클릭 > **Schedule…**:

| 항목 | 내용 |
|---|---|
| Run this query on a schedule | 예약 사용 여부(파일의 `enabled`) |
| When | Every N minutes/hours, Daily at hh:mm, Weekdays at hh:mm(월–금). cron 문법 없음 |
| Connections | 실행할 서버(여러 개). 이 PC의 연결 목록 + 다른 PC에서 정한 대상 중 여기 없는 것("(not found)") |
| Parameters | SQL의 매개변수마다 예약 실행용 값(비우면 정의의 기본값) |
| Alert when | 없음 / Row count `>` `>=` `=` X / First value(첫 행 첫 열) `>` `>=` `<` `<=` `=` `!=` X. 숫자면 숫자로, 아니면 글자로 비교 |
| Active on this computer | 이 PC에서 돌릴지(로컬 설정, 아래) |
| Remove Schedule | 예약 블록을 파일에서 지움 |

배치: 양식은 GridBagLayout이다. 화면 배율에서 창 크기가 반올림되어 선호 크기보다 1px만 작아도(측정: 창 459×549, 선호 459×550)
GridBagLayout은 모든 칸을 최소 크기로 놓는데, 스크롤 창의 최소 높이는 한 줄이라 Connections·Parameters 목록이 한 줄로 줄고 아래에 빈 띠가 남았다.
두 목록에 최소 높이(몇 줄)와 세로 가중치를 주어 줄지 않게 하고, 창을 키우면 두 목록이 늘어난다.

파일 형식(`-- @schedule` 블록, 쿼리 머리말의 다른 항목과 같은 규칙):

```sql
-- @schedule
--   every: 1m                      (또는 every: 2h / daily: 08:30 / weekdays: 08:30)
--   target: postgres-jdbc-chr-ql|QL PostgreSQL
--   target: microsoft-chr-ql|QL SQL Server
--   param.min_minutes: 10
--   condition: rows > 0           (rows|first, >= <= != > < =)
--   enabled: true
```

- 대상은 `데이터 소스 ID|이름`이다. 실행할 때 ID → 이름 순으로 찾는다(공유한 쿼리가 동료 PC에서도 맞는 연결을 찾음).
- 쿼리 창의 저장(Ctrl+S)은 파일의 예약 블록을 그대로 둔다(예약은 Schedule…에서만 바뀜). 열린 편집 창이 오래된 예약을 덮어쓰지 않게 하기 위해서다.

### 이 PC에서 돌릴지 (로컬 설정)

- 파일의 `enabled`는 "예약할 수 있음"이고, 실제로 이 PC에서 돌지는 이 번들 설정의 `queryLibrary.schedule.active.<쿼리 ID>`다. 기본값은 꺼짐이다.
  그래서 공유 루트(네트워크 폴더, Git)로 받은 예약 쿼리가 동료 PC마다 저절로 돌지 않는다. Schedule…에서 저장하면 "Active on this computer"(기본 켬)가 이 PC에 저장된다.
- 읽기 전용 루트의 쿼리는 정의를 바꿀 수 없고 이 PC의 활성 여부만 바꾼다(Schedule…의 OK, 또는 Scheduled Queries의 "Active here").

### 스케줄러 (`QueryScheduler`)

- 데몬 스레드 하나의 타이머(`ScheduledExecutorService`, 15초 간격)가 다음 실행 시각이 지난 예약을 고르고, 실행은 쿼리마다 `AbstractJob` 하나로 한다(EDT 아님).
- 같은 쿼리의 앞 실행이 아직 돌면 이번 실행은 건너뛰고 로그와 기록(`SKIPPED`)에 남긴다(겹쳐 돌지 않음).
- 예약 목록은 라이브러리를 읽을 때마다(시작, Refresh, 저장) 바뀐다. 다음 실행 시각은 지금 이후의 첫 시각이고, 간격 예약은 기록의 마지막 실행 시각에 맞춰 이어 간다.
  **꺼져 있던 동안 놓친 실행은 몰아서 하지 않는다.** 일시 정지한 예약은 다음 시각을 계속 미래로 옮겨, 다시 시작해도 바로 실행되지 않는다.
- 메인 창을 닫으면(`MainFrame.dispose` → `QueryLibraryPanel.dispose`) 타이머를 멈추고 돌고 있는 실행을 취소한다. 다음 시작 때 라이브러리를 읽으면 다시 돈다.
- 실행: 쿼리 창 없이 같은 엔진(`SQLQueryExecuteJob`, `SQLScriptBatches`로 GO 배치)과 같은 바인딩(`QueryParameterBinder`)을 쓴다. 대상마다 차례로 실행한다.
  - 실행마다 **격리된 실행 문맥**(`DBSInstance.openIsolatedContext`, 연결 하나)을 열고 끝나면 닫는다. 처음에는 쿼리 창과 같은 기본 문맥을 썼는데,
    80초 걸리는 예약 쿼리 뒤에 다른 예약 쿼리가 줄을 서서 다음 분에 건너뛰어졌다(2026-11-06 확인). 기본 문맥을 같이 쓰면 쿼리 창도 막히고,
    쿼리의 기본 DB/스키마 설정이 쿼리 창의 것을 바꾸므로 분리했다.
  - 대상 연결이 끊겨 있으면 **저장된 자격 증명이 있을 때만** 연결한다(`isCredentialsSaved`). 백그라운드에서 비밀번호 대화상자를 띄우지 않기 위해서다. 없으면 `SKIPPED`("password is not saved").
  - 확인이 필요한 문장(운영 연결 유형의 변경 문장, `confirmQueryExecution`)은 묻지 않고 실행하지 않는다(오류로 기록). 결과 집합이 너무 많을 때도 묻지 않고 멈춘다.
  - 첫 결과 집합의 행 수와 열 이름 + 앞 20행(글자)만 받는다. 행 수는 연결의 최대 행 수 설정까지다.
- 조건이 맞으면(`ALERT`) 알림 토스트(`DBPPlatformUI.showNotification`, 오류 모양, 누르면 History…)를 띄우고, 트리 노드에 빨간 점 배지를 단다
  (`queryLibrary.alert.<id>`, History…를 열면 지움). 실행 오류는 경고 토스트로 알린다. 메일 등 외부 발송은 없다.

### 기록 (`QueryLibraryHistory`)

- 이 PC의 것이라 쿼리 파일이 아니라 작업 공간 메타데이터 폴더 `.metadata/chromite-query-library/history/<쿼리 ID>.tsv`에 쓴다.
  한 실행 = 한 줄(시각, 서버, 걸린 시간 ms, 행 수, 상태 `OK|ALERT|ERROR|SKIPPED`, 메시지, 스냅숏). 칸 안의 탭·줄바꿈·역슬래시는 `\t`, `\n`, `\\`로 쓰고,
  스냅숏은 칸을 U+001F, 행을 U+001E로 잇는다.
- 보존: Tools > Options > Query Library의 "Keep history runs"(기본 100), "Keep history days"(기본 30). 덧붙일 때 넘는 것을 지운다.
- **History…**(트리 오른쪽 클릭, Scheduled Queries의 단추, 알림 클릭): 위는 실행 목록(최근 것이 위, 상태 색), 아래는 고른 실행의 앞 행. 비모달이고 새 실행이 기록되면 다시 읽는다.

### 개요 (Scheduled Queries…)

도구 막대의 시계 아이콘 또는 오른쪽 클릭 메뉴. 예약된 쿼리마다 Query, Schedule, Connections, Active here(체크 = 다시 시작, 해제 = 일시 정지, 로컬 설정),
Next run(남은 시간), Last run, Last status. Run Now(바로 한 번), History…. 비모달, 5초마다 남은 시간을 다시 그린다.
트리에서는 이 PC에서 켠 예약 쿼리의 보조 글자에 일정(`every minute` 등)이 붙는다.

## 공유 (④)

사용자 결정(2026-11-07): "쿼리 라이브러리 공유는 당연히 진행해야지". 처음 설계(쿼리 하나 = 파일 하나, 정해진 항목 순서, ID 기반 로컬 상태, 연결은 ID와 이름으로 찾기,
루트 단위 읽기 전용) 위에 아래를 만들었다. 공유 쿼리를 고치려면 지금처럼 Duplicate로 개인 루트에 복사하거나, 쓰기 권한이 있는 루트에서 고친다.

### 공유 폴더와 자동 새로 고침 (`QueryLibraryWatcher`)

- 네트워크 폴더(`\\server\share\queries`)나 아무 폴더를 Library Roots…에서 루트로 더한다. 관리자만 쓰기 권한을 갖고 나머지는 읽기 전용 루트로 쓰는 것을 권한다.
- 바깥 변경을 두 가지로 알아챈다.
  - 파일 시스템 감시(`WatchService`, 루트 아래 모든 폴더를 등록, `.`으로 시작하는 폴더 제외). 로컬 폴더에서는 저장 직후(0.7초 모아서) 반영된다.
  - 주기 확인: Tools > Options > Query Library의 "Check folders every"(`queryLibrary.refreshSeconds`, 기본 **30초**, 0 = 감시 이벤트만, 최대 3600).
    SMB 같은 네트워크 폴더는 감시 이벤트가 오지 않거나 빠지는 일이 있어 주기 확인이 메운다. 간격은 지금 기다리는 확인이 끝난 뒤부터 적용된다.
- 느린 공유 폴더를 반복해서 다 읽지 않는다: 확인은 파일 내용을 읽지 않고 폴더 목록과 `.sql` 파일의 크기·수정 시각만 모은 지문을 지난 읽기의 지문과 비교하고,
  다를 때만 트리를 다시 읽는다. 감시 이벤트도 같은 비교를 거치므로 이 창에서 저장해 이미 다시 읽은 변경은 한 번 더 읽지 않는다.
  지문은 트리를 읽기 직전에 만든다(읽는 동안 바뀐 파일은 다음 확인에서 읽음). 없어진 루트(뽑힌 공유)는 "없음"으로 기억해 돌아오면 다시 읽는다.
- 저절로 다시 읽을 때는 펼친 폴더와 선택을 경로로 되살리고 스크롤 위치도 그대로 둔다(선택한 줄로 스크롤하지 않음). Refresh나 이 창의 변경은 예전처럼 선택한 줄을 보인다.
- 30초를 고른 이유: 공유 폴더 하나에 쿼리 수백 개여도 확인 한 번은 디렉터리 목록 몇 번이고, 동료의 변경이 늦어도 반 분 안에 보인다. 로컬·감시가 되는 폴더는 간격과 상관없이 바로 반영된다.

### 열린 창과 저장 충돌 (`QueryLibraryEditor`)

- 쿼리 창은 연 때(또는 마지막 저장 때)의 쿼리를 기억한다. 비교는 창이 다루는 항목(이름, 설명, 대상, DB/스키마, 매개변수 정의, SQL)을 파일 형식으로 쓴 글자로 한다.
  `@created`, 예약 블록, 모르는 머리말 줄은 비교에서 뺀다: Schedule… 저장처럼 창이 다루지 않는 항목만 바뀐 경우는 다른 사람의 편집이 아니기 때문이다.
  질문에 보이는 "마지막 수정" 시각은 파일의 수정 시각이다(2026-11-08까지는 파일의 `@updated`).
- **저장할 때:** 파일의 편집 항목이 기억한 것과 다르면(다른 사람이 먼저 저장) 묻는다: **Overwrite**(내 것으로 덮어씀) / **Reload**(그 사람의 버전을 창에 보이고 내 변경은 버림) / **Cancel**.
  파일이 Chromite 밖에서 지워졌거나 옮겨졌으면 다시 저장할지 묻는다.
- **라이브러리를 다시 읽을 때마다**(자동, Refresh, 저장) 열린 창을 확인한다(읽은 결과를 쓰므로 EDT에서 파일을 읽지 않음).
  - 고치지 않은 창: 새 내용으로 바꾸고 머리말 위에 알림 줄("This query was changed on disk and has been reloaded (hh:mm:ss).")을 보인다.
  - 고친 창: 내용은 그대로 두고 경고 알림 줄과 **Reload** 단추를 보인다. 저장하면 위의 질문이 뜬다.
  - 파일이 라이브러리에서 사라졌으면(삭제, 옮김, 폴더를 쓸 수 없음) 경고 알림 줄. Dismiss로 닫으면 같은 변경에 대해서는 다시 보이지 않는다.

### Git 루트 (`QueryLibraryGit`)

- 루트 폴더부터 위로 `.git`이 있으면 Git 작업 사본이다(루트가 저장소의 하위 폴더여도 됨). 오른쪽 클릭 메뉴에 **Pull**과 **Commit & Push…**가 붙는다.
- 시스템 `git`을 쓴다. 대상 플랫폼에 JGit이 없고(2026-11-07 `org.eclipse.jgit` 검색 결과 없음) 새 의존성을 넣지 않기 위해서다.
  Git이 없으면 두 명령을 끄고 도구 설명에 이유("Git was not found. Install Git and make sure git is on the PATH.")를 보인다. 있는지는 처음 한 번 작업 스레드에서 `git --version`으로 본다.
- **자격 증명을 저장하지 않는다.** 사용자의 Git 설정(이름·메일, 자격 증명 도우미, SSH 키, 프록시)을 그대로 쓴다. `GIT_TERMINAL_PROMPT=0`이라 Git이 보이지 않는 터미널에서
  비밀번호를 기다리며 멈추지 않는다(자격 증명 도우미의 창은 뜬다). 원격 명령은 5분, 로컬 명령은 30초가 지나면 끝낸다.
- 루트 이름 뒤 보조 글자: `main ↑1 ↓2 · 1 conflicts`(브랜치, 올리지 않은 커밋, 받지 않은 커밋, 충돌 수). 뒤처짐은 마지막 fetch(Pull) 기준이다(자동 fetch는 하지 않음: 네트워크·자격 증명).
  상태는 트리를 읽을 때 `git --no-optional-locks status --porcelain=v2 --branch -z --untracked-files=all -- .`로 읽는다(기계용 형식, 색인 파일을 다시 쓰지 않음).
- **Pull:** `git pull --no-rebase --no-edit`. 겹치지 않는 변경은 Git이 합친다. 같은 줄을 고친 충돌은 **Chromite가 합치지 않는다**: Git이 충돌 표시를 남긴 채 멈추고,
  Chromite는 충돌 파일을 트리에 노란 점과 "conflict"로 표시하고 파일 목록과 Git 출력을 보인다. Git의 사람용 출력은 언어 설정에 따라 바뀌므로 해석하지 않고,
  성공은 종료 코드, 충돌은 다시 읽은 상태로 정한다.
- **Commit & Push…:** 바뀐 파일 목록과 커밋 메시지(기본 "Update queries")를 묻고 `git add -A -- .` → `git commit -F - -- .`(루트 아래만, 메시지는 표준 입력 UTF-8) → `git push`.
  병합 중이면 Git이 일부 커밋을 받지 않으므로 색인 전체를 커밋해 병합을 끝낸다. 올라갈 브랜치가 없으면 `git push -u origin HEAD`.
  충돌 표시(`<<<<<<< `)가 남은 파일이 있으면 올리지 않고 알린다. push가 거절되면 Git 출력과 함께 "먼저 Pull 하라"고 안내한다. 읽기 전용 루트에서는 끄고 Pull만 된다.
- 충돌 해결: 충돌 파일을 쿼리 창에서 열면 충돌 표시가 SQL 부분에 보인다(머리말이 충돌 표시 줄에서 끝나므로). 표시 줄을 지우고 저장한 뒤 Commit & Push 하면 해결된 것으로 올라간다.
  머리말 항목(이름, 매개변수 등)이 충돌했으면 그 뒤의 머리말 줄이 SQL 주석으로 보이므로 텍스트 편집기나 Git 도구로 고치기를 권한다.
- 2026-11-07에는 저장할 때마다 `@updated`가 바뀌어, 두 사람이 같은 쿼리를 동시에 고치면 SQL의 다른 줄을 고쳤어도 그 줄이 늘 충돌했다.
  2026-11-08부터 이 줄을 파일에 쓰지 않는다("저장 형식"의 "고친 시각을 파일에 두지 않음"). 이제 다른 줄을 고친 동시 편집은 Git이 합친다.

### 내보내기·가져오기 (`QueryLibraryArchive`)

- **Export…**(폴더·루트): 그 폴더와 하위 폴더의 쿼리 파일(`.sql`, `.`으로 시작하는 것 제외)을 zip 하나(`.chromite-queries`)로 묶는다. 항목은 내보낸 폴더 기준 상대 경로이고
  파일 바이트를 그대로 담는다(문자 집합·BOM·줄바꿈·모르는 머리말 유지). 내보낸 폴더 이름은 넣지 않는다: 가져오는 사람이 넣을 폴더를 고른다. 임시 파일에 쓴 뒤 바꾸므로 실패해도 예전 파일이 남는다.
- **Import…**(쓸 수 있는 폴더): 먼저 묶음 전체를 메모리로 읽고 검사한다.
  - 항목 수 10,000개, 파일 하나 10 MB, 풀었을 때 전체 100 MB까지. 크기는 머리글 값이 아니라 실제로 읽은 바이트로 센다(압축 폭탄).
  - 경로: 절대 경로, 드라이브 문자(`:`), `..`이 있으면 거부하고, 대상 폴더 밖으로 나가는 경로를 쓰기 직전에 다시 막는다(zip-slip). `.sql`이 아닌 항목과 `.`으로 시작하는 항목은 건너뛴다.
  - 검사에 걸리면 아무것도 쓰지 않는다.
- **ID 충돌:** 같은 `@id`의 쿼리가 라이브러리(모든 루트)에 있으면 쿼리마다 묻는다: **Overwrite**(그 파일 자리에 가져온 내용을 씀) / **Import as Copy**(새 ID, 이름에 "copy") /
  **Skip** / Cancel(아무것도 가져오지 않음). "Do the same for the remaining N queries with the same ID"를 켜면 나머지에 같은 답을 쓴다.
  기존 파일이 읽기 전용 루트에 있으면 Overwrite를 보이지 않는다. 묶음 안에서 같은 ID가 다시 나오면 뒤의 것은 사본으로 가져온다(한 라이브러리에 같은 ID가 둘이면 이 PC의 상태가 섞임).
  ID가 없는 파일(머리말 없는 `.sql`)과 충돌하지 않는 쿼리는 상대 경로대로 쓰고, 같은 이름이 있으면 ` (2)`를 붙인다. 끝나면 추가·덮어씀·사본·건너뜀 수를 알린다.

### Save As .sql

- 트리의 **Save As .sql…**와 라이브러리 쿼리 창의 **File > Save As**는 SQL만(머리말 없이) 평범한 `.sql` 파일로 내보낸다. 문자 집합·줄바꿈은 라이브러리 파일의 것을 따른다.
  창은 계속 라이브러리 쿼리다. 라이브러리 루트 안으로는 내보내지 않는다(머리말 없는 다른 쿼리로 보이기 때문).

### 비밀 정보

- 쿼리 파일에는 비밀번호·토큰을 쓰지 않는다. 연결은 ID와 이름으로만 가리키고 자격 증명은 각자의 Chromite 연결 설정(보안 저장소)에 있다.
  매개변수 마지막 입력값(`queryLibrary.lastValues.<id>`)과 예약 활성 여부(`queryLibrary.schedule.active.<id>`)는 지금처럼 이 PC의 설정에만 둔다. Git 자격 증명도 저장하지 않는다(위).
- Library Roots… 대화상자에 경고 아이콘과 함께 이 내용을 보인다(루트를 더하는 곳).
- Parameters…에서 이름이나 표시 이름이 비밀처럼 보이는(`pass`, `password`, `pwd`, `secret`, `token`, `api_key`, `credential`, `private_key`, `비밀번호`, `암호`, `토큰`) 매개변수에
  기본값을 넣으면 OK를 누를 때 "기본값은 라이브러리를 함께 쓰는 모든 사람이 읽는 쿼리 파일에 저장된다"고 경고하고, 저장을 누를 때만 저장한다(저장 / 취소, 기본 단추는 취소). `:name`·`${name}` 모두 해당한다.

## upstream 대비 변경점

- Chromite 고유 기능(새 클래스). DBeaver의 모델 계층(파서, 실행 엔진, 데이터 소스 레지스트리, 실행 문맥 기본값)을 그대로 쓴다.
- Chromite 실행 엔진(`SQLQueryExecuteHandler`/`SQLQueryExecuteJob`)에 네이티브 바인딩 지점 두 개를 더했다. 기본 구현은 바인딩 없음이라 SWT 편집기(`SQLQueryJob`) 동작은 같다.

## 남긴 것

- Chromite Agent의 Query Library 도구는 자기 바인딩(`LibraryTools.bind`)을 쓴다(모델 번들이라 `QueryParameterBinder`를 쓸 수 없음). 합치려면 바인딩을 모델 번들로 옮겨야 한다.
- 데이터 전송 작업으로 저장(Save as task)한 바인딩 결과의 내보내기는 값을 저장하지 않는다(작업은 쿼리 글자만 저장함, `DatabaseTransferProducer`의 "query" 선택자). Swing에는 아직 작업 저장이 없어 드러나지 않는다.
- ④ Git: 자동 fetch(뒤처짐을 Pull 전에 보이기), 브랜치 바꾸기, 충돌을 나란히 보여 주는 해결 화면은 없다. 머리말 항목의 충돌은 텍스트 편집기·Git 도구로 고친다.
- ④ 주기 확인만으로 바뀐 것을 알아채는 경우(감시 이벤트가 오지 않는 네트워크 폴더)는 따로 확인하지 못했다. 로컬 폴더에서는 감시 이벤트가 먼저 와서 같은 비교 경로로 반영되었다.
- 트리 안에서 바로 이름 바꾸기(지금은 이름 입력 대화상자).
- 예약 실행: 한 쿼리의 여러 서버를 차례로 실행한다(병렬 아님). 예약 실행마다 연결을 새로 열고 닫는다(자주 도는 예약이 많으면 연결 풀이 필요할 수 있음).
- 범위 밖에서 발견한 문제: 기존 `NotificationToast`가 메시지가 있는 새 알림을 창 높이만큼 크게 그렸다. `layoutToasts`가 토스트를
  배치하기 전에 `getPreferredSize()`를 불러 줄바꿈 `JTextArea`가 폭 없이 높이를 계산했다. 2026-11-06에 토스트를 먼저 배치한 뒤 재도록 고쳤다
  ([swing-frontend.md](swing-frontend.md) 변경 이력).

## 확인 (2026-11-06)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swt,product-chromite-swing` → BUILD SUCCESS.
빌드한 Swing 제품을 개인 작업 공간(`-data D:\Tools\_scratch\query-library\ws`)으로 실행하고, 로컬 Docker DB(각 서버에 `chr_query_library` DB)로 확인했다.

| 확인 | 결과 |
|---|---|
| View > Query Library(Ctrl+Alt+T) | Object Explorer 오른쪽 열에 도구 창, "My Queries" 루트(작업 공간의 `QueryLibrary`)를 처음 읽을 때 만듦 |
| 트리 | "01. 상태 모니터링" 폴더 아래 "01. 세션 수 / 02. 대기 / 03. 디스크 사용량 / 04. 오래 실행 중인 작업" 자연 정렬, 보조 글자로 고정 대상 연결 |
| New Query(도구 막대) | 이름 입력 → `01. 세션 수.sql` 생성(Object Explorer에서 고른 연결이 고정 대상), 편집 창 열림, SQL 입력 후 Ctrl+S → 머리말과 SQL이 파일에 저장됨(UTF-8, LF) |
| 실행 PostgreSQL | 편집 창 F5 → 비밀번호 입력 후 결과 3행 |
| 실행 SQL Server | 트리에서 "02. 대기" Enter → 매개변수 대화상자(상위 개수 3, 최소 대기 0) → `TOP (?)`와 `> ?`가 바인딩되어 결과 표 2개(3행, 1행), Messages에 한글 PRINT, `GO` 두 배치 |
| 실행 MariaDB | 머리말 없는 "03. 디스크 사용량"을 Run on…으로 QL MariaDB에서 실행, 11행 |
| 매개변수 검사·바인딩 | "04. 오래 실행 중인 작업"(choice `state`, number `min_minutes`): `ABC` 입력 시 대화상자 안 오류, 10 입력 시 `make_interval(mins => ?)`에 정수로 바인딩되어 13행 |
| 바깥 변경 | 탐색기에서 파일 2개를 폴더에 복사 → Refresh로 트리에 나타남 |
| 검색 | "디스크" → 해당 쿼리와 상위 폴더만 |
| 마지막 입력값 | 끝낸 뒤 설정 파일에 `queryLibrary.lastValues.<id>`(쿼리 ID별) 저장 확인 |

| ③ 1분 예약 (PostgreSQL) | "04. 오래 실행 중인 작업"(`param.state: failed`, `param.min_minutes: 10`, `condition: rows > 0`): 매분 실행, 13행 → `ALERT`, 알림 토스트, 폴더·쿼리에 빨간 점 |
| ③ 1분 예약 (SQL Server) | "05. 사용자 세션 수"(`condition: first >= 1`): 매분 실행, 첫 값 9 → `ALERT` |
| ③ 로컬 활성 | 파일에 예약이 있어도 이 PC 설정(`queryLibrary.schedule.active.<id>`)이 없으면 돌지 않음, 켠 뒤에만 실행 |
| ③ 끝내기·다시 시작 | 13:18:02 실행 뒤 13:18:4x에 끝냄 → 13:21:41 다시 시작 → 13:22:16에 이어서 실행(13:19–13:21 놓친 실행은 몰아서 하지 않음) |
| ③ 겹침 방지 | "06. 느린 점검"(`pg_sleep(80)`, 1분): 13:27:14 시작 → 13:28:14 `SKIPPED`("previous run is still running", 로그에도 남음) → 13:28:3x 끝(81초, `OK`) |
| ③ 격리된 연결 | 같은 PostgreSQL에서 "06"(80초)이 도는 동안 "04"가 13:37:35, 13:38:35, 13:39:35에 그대로 실행됨(기본 문맥을 같이 쓰던 빌드에서는 13:28:29에 "04"가 건너뛰어짐) |
| ③ 끝낼 때 취소 | 실행 중에 앱을 끝내면 그 실행은 `SKIPPED`("Canceled")로 기록되고 앱이 바로 끝남 |
| ③ 기록 | `.metadata/chromite-query-library/history/<id>.tsv`에 시각·서버·ms·행 수·상태·메시지·앞 행 |
| 여러 루트 | 개인 루트 + 읽기 전용 "Team (shared)" 루트(보조 글자 "read-only") |
| 다크 테마 | 같은 화면을 다크 테마로 확인 |

화면으로 확인하지 못한 것(13:06부터 다른 프로세스의 "Windows 보안" 자격 증명 창이 전경을 잡고 있어 클릭·키 입력을 보낼 수 없었음):
Schedule…·History…·Scheduled Queries… 대화상자, Save to Query Library… 대화상자, Rename·Delete·끌어다 놓기, Parameters… 정의 표, 다크 테마의 편집 창·매개변수 대화상자.
예약 실행 자체(실행, 조건, 알림, 기록, 겹침 방지, 다시 시작)는 파일과 로컬 설정을 미리 만들어 두고 앱을 실행해 기록 파일과 창 캡처로 확인했다.

스크린숏: `D:\Tools\_scratch\query-library\shots\`. 회귀 테스트는 실행하지 않았다(요청 없음).
영향받을 수 있는 기능: SWT·Swing의 쿼리 실행(실행 엔진의 바인딩 분기, 기본은 기존 경로), Swing 쿼리 창(확장 지점, 저장), 도구 창 배치(새 열, 처음에는 숨김).

## 확인 (2026-11-07, ④ 공유)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → BUILD SUCCESS. 빌드한 제품 하나를 데이터 폴더·작업 공간만 다르게 두 개 실행했다
(A: 영어 `-nl en_US`, B: 한국어). 화면 조작은 각 프로세스에 붙인 테스트용 Java 에이전트가 Swing API로 했다(OS 입력 없음). 증거: `D:\Tools\_scratch\query-library-sharing\evidence\`.

| 확인 | 결과 |
|---|---|
| 공유 폴더 자동 반영 | A가 공유 루트에 "Active sessions"를 만듦(21:34:25) → B의 트리에 Refresh 없이 21:34:28에 보임 |
| 고치지 않은 열린 창 | B가 저장 → A의 창이 B의 SQL로 바뀌고 알림 줄 "changed on disk and has been reloaded" |
| 고친 창 + 저장 충돌 | A가 먼저 저장 → B(고친 창)에 경고 알림 줄과 다시 읽기 단추, B 저장 → "다른 사람이 바꾼 쿼리" 질문 → 덮어쓰기: 파일이 B의 내용 |
| 저장 충돌 Reload | B가 저장한 뒤 A(고친 창) 저장 → 질문 → Reload: A의 창이 B의 SQL, 알림 줄 닫힘 |
| Git Commit & Push / Pull | 빈 bare 저장소와 두 clone. A가 쿼리를 만들고 Commit & Push(올라갈 브랜치 설정) → B Pull → 트리에 나타남. 원격 기록 `8136f75 User A Update queries` |
| Git 충돌 | A·B가 같은 줄을 고쳐 각각 커밋. B push 거절(Git 출력 + Pull 안내) → B Pull → 충돌 안내, 트리 `main ↑1 ↓1 · 충돌 1개`, 쿼리에 "충돌". 파일에 충돌 표시가 남음(합치지 않음) |
| 충돌 해결 | B가 쿼리 창에서 표시 줄을 지우고 저장 → Commit & Push가 병합 커밋으로 올림 → A Pull: A의 창이 병합 결과로 바뀜 |
| Git 없음 | PATH에서 git을 뺀 B: Pull·Commit & Push가 꺼지고 도구 설명 "Git을 찾을 수 없습니다…" |
| 내보내기·가져오기 | A가 Shared(쿼리 2개) 내보냄(항목 바이트가 원본과 같음). B가 My Queries로 세 번 가져옴: 사본으로+나머지에도(새 ID, "- 복사본"), 덮어쓰기+건너뛰기, 건너뛰기+나머지에도 |
| 위험한 묶음 | `../../evil.sql` → "Unsafe path", 항목 10,001개 → 개수 제한, 11 MB 항목 → 크기 제한. 모두 아무것도 쓰지 않음 |
| Save As .sql | 트리·File > Save As 모두 머리말 없는 SQL만, 루트 안 경로는 거부 |
| 비밀 정보 | Library Roots… 안내 문구, `api_token` 기본값에 경고(No면 대화상자에 남음), Options의 "Check folders every" |

회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: Query Library 트리 다시 읽기(Git 상태·지문 추가), 라이브러리 쿼리 창 저장(충돌 확인), File > Save As(라이브러리 창), Tools > Options.

## 확인 (2026-11-08, 머리말 단추)

빌드한 Swing 제품, 라이브러리 쿼리 하나(매개변수 1개, 기준값 1개)의 편집 창, Object Explorer·Query Library·Chromite Agent 열을 연 상태. 문서 영역 너비는 도킹 분할을 API로 옮겨 정했다
(`JSplitPane.setDividerLocation`, 마우스 입력 없음). 단추 너비는 화면 단위(px, UI 배율 전).

| 창 | 문서 영역 | 고치기 전 (영어) | 고친 뒤 영어 | 고친 뒤 한국어 |
|---|---|---|---|---|
| 1200 px | 847 / 753 | 단추 118·117(우선 크기, 최소 72) | 118·117 | 99·85 |
| 1200 px, 좁은 문서 영역 | 614 / 522 | **72·72 "Para…", "Thres…"** | 118·117 | 99·85 |
| 1024 px | 592 / 500 | — | 118·117 | 99·85 |
| 1024 px, 450 | 450 | — | 118·117, 입력 칸이 줄어듦 | — |

화면: `D:\Tools\_scratch\chromite-bugs-locks-agent-stream\run\shots\q-*`.

## 확인 (2026-11-08, 매개변수 대화상자와 바인딩 하나로, 고친 시각)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → BUILD SUCCESS. 빌드한 제품 하나를 데이터 폴더(`CHROMITE_DATA`)와
작업 공간을 scratch에 두고 영어(`-nl en_US`)로 실행했다. 화면 조작은 이 프로세스에 붙인 테스트용 Java 에이전트가 Swing API와 앱의 메서드로 했다(OS 입력 없음).
DB는 공유 테스트 컨테이너에 만든 `chr_query_library_params`(PostgreSQL, SQL Server, `orders` 600행)이고 끝난 뒤 지웠다.
증거: `D:\Tools\_scratch\chromite-query-library-params\evidence\`(스크립트 로그, 화면).

| 확인 | 결과 |
|---|---|
| 라이브러리 쿼리 대화상자 | "Parameters - Orders by amount": `min_amount`(number), `since`(date), `skip`("SQL text", 도구 설명 `${skip}`), 첫 칸에 초점, Run이 기본 단추 |
| 바인딩 실행 (PostgreSQL) | 300 / 2026-03-01 / `'cust3'` → 200행(더 있음). 직접 센 값 344행과 같음 |
| 다음 페이지 (#22) | 344행. 예전에는 "parameter values bound for this run only"로 막혔음. `requeryBlocker` 없음, Export Data 켜짐(`exportQuery` 있음) |
| 서버 정렬 (#22) | ORDER BY 없는 같은 쿼리, 200/344행에서 id 내림차순 → 첫 행 600, 오름차순 → 첫 행 200(서버에서 다시 읽음) |
| 필터 (#22) | customer = cust1 → 57행(직접 센 값과 같음) |
| 열 필터 값 목록 (#22) | `SQLQueryColumnValues`: cust0·1·2·4·5·6, cust3 없음(바인딩한 조건을 따름) |
| SQL Server (#22) | `@database` 지정, `min_amount` 800 → 67행, 필터 customer = cust2(ORDER BY 쿼리를 감싸는 경로) → 10행, 열 값 목록 7개 |
| 기록 (#19) | 문장 `… amount >= :min_amount AND created >= :since AND customer <> 'cust3' …`, `parameters` = min_amount 300 number, since 2026-03-01 date. 미리 보기에 `-- :min_amount = 300` 줄 |
| 기록 Execute (#19) | 묻지 않고 같은 값으로 바인딩해 200행(더 있음) |
| 기록 Open (#19) | 창에 `:name` 글자, Execute → "Parameters - SQLQuery2.sql"에 300·2026-03-01이 채워짐 → 1000으로 실행 → 0행, 새 기록에 1000 |
| 평범한 쿼리 창 (#9) | `:c`, `:a` → "Bind Parameters": 문장 미리 보기, 줄마다 "SQL text"와 표식 도구 설명, 아래 안내, Execute / Ignore / Cancel. `'cust2'`, 100 → `count(*)` 76, 기록은 값을 넣은 글자(매개변수 없음). 다시 실행하면 지난 값이 채워짐, Cancel → 대화상자 닫히고 실행 안 함 |
| 저장 (`@updated`) | 예전 파일(`@updated` 있음)을 창에서 저장 → 파일에서 `@updated` 줄이 빠지고 다른 줄은 그대로 |
| 저장 충돌 질문 | 바깥에서 파일을 바꾼 뒤 고친 창 저장 → "last update 2026-11-08 03:29:37"(파일 수정 시각), Cancel이 기본 |

확인하지 못한 것: Export Data… 마법사를 끝까지 실행하지 않았다(출력 폴더 기본값이 사용자 폴더라 C: 쓰기를 피함). 내보내기는 같은 데이터 컨테이너의 `readData`를
쓰므로 위의 다시 읽기와 같은 바인딩 경로다. 두 Git 사본에서 동시에 고친 파일의 병합은 실행하지 않았다(작업 사본 밖 git 실행이 막혀 있음). 다른 줄을 고친 두 변경은
3방향 병합에서 겹치는 줄이 없으면 합쳐지고, 두 쪽이 같은 `@updated` 줄을 지운 것도 같은 변경이라 합쳐진다.
회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 쿼리 창 매개변수 입력(평범한 창, 라이브러리, 기록), 결과 다시 읽기(필터, 정렬, 다음 페이지,
열 필터 값 목록, Export Data, 편집 저장 뒤 행 다시 읽기), Query History, 라이브러리 저장·충돌 질문, 가져오기·복제.

## 확인 (2026-11-08, 머리말 저장 단추와 DB/스키마 목록)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → BUILD SUCCESS. 빌드한 제품을 데이터 폴더(`CHROMITE_DATA`)와 작업 공간을
scratch에 두고 한국어(OS 언어), 화면 배율 150%로 실행했다. 화면 조작은 이 프로세스에 붙인 테스트용 Java 에이전트가 Swing API와 앱의 메서드로 했다(OS 입력 없음).
DB는 공유 테스트 컨테이너(PostgreSQL 17, SQL Server 2022, MariaDB 11, Oracle Free 23)이고 아무것도 만들거나 바꾸지 않았다.
증거: `D:\Tools\_scratch\chromite-library-editor-2\run\`(스크립트 로그, `shots\`).

| 확인 | 결과 |
|---|---|
| 저장 단추 모양 | 첫 줄 오른쪽 끝, `save` 아이콘만(글자 없음), 도구 막대 아이콘 단추 모양, 도구 설명 "저장 (Ctrl+S)", 접근성 이름 "저장" |
| 켜짐·꺼짐 | 연 직후 꺼짐 → SQL 고침·목록에서 스키마 고름 → 켜짐(탭 `*`) → 누르면 파일에 저장되고 꺼짐 |
| 이름 검사 | 이름을 비우고 누름 → "쿼리 이름을 입력하십시오." (Ctrl+S와 같은 메시지) |
| 저장 충돌 | 바깥에서 파일을 바꾼 뒤 누름 → "다른 사람이 바꾼 쿼리"(덮어쓰기 / 다시 읽기 / 취소, 취소가 기본) → 취소: 파일 그대로, 단추 켜진 채 → 다시 누르고 덮어쓰기: 파일이 창의 내용 |
| 연결하지 않음 | PostgreSQL·SQL Server·MariaDB 대상 쿼리를 열고 기다림 → 연결 안 됨 |
| PostgreSQL | Database 목록을 펼침 → 연결되고 `postgres`, Schema 목록 `information_schema, pg_catalog, public` |
| SQL Server | Database 51개(`master`, `model`, `msdb`, `tempdb`, 테스트 DB), `master`의 Schema `dbo, INFORMATION_SCHEMA, sys` |
| MariaDB | Database 41개, Schema 칸 꺼짐("대상 연결에는 전환할 스키마가 없습니다") |
| Oracle | Schema 52개, Database 칸 꺼짐("대상 연결에는 전환할 데이터베이스(카탈로그)가 없습니다") |
| 목록에 없는 값 | Database에 `db_not_here` → 그대로 보이고 저장됨, 그 DB의 Schema 목록은 비어 있음. "Ask when run" 쿼리의 `db_on_another_server`도 그대로 |
| 실행 때 묻기 | 연결 없는 창: 목록 비어 있음, 도구 설명 "목록: 이 쿼리 창의 연결에서 읽습니다", 두 칸 켜짐 |
| 없는 연결 | "Colleague server (not found)" 대상: 목록 없음, 도구 설명 "목록 없음: 대상 연결이 이 컴퓨터에 없습니다", 저장된 Schema `sales` 그대로 |
| 대상 바꾸기 | MariaDB 창의 대상을 연결된 PostgreSQL로 → 목록을 펼치지 않고 두 칸 켜짐(연결 없이 읽음), 다시 MariaDB → Schema 꺼짐, "Ask when run" → 두 칸 켜짐, 앞 연결의 목록은 지워짐 |

확인하지 못한 것: 펼친 목록의 화면(앱 창이 앞에 있지 않아 열린 목록이 바로 닫힘. 목록 내용은 콤보 모델로 확인), 비밀번호를 묻는 연결(테스트 연결은 비밀번호가 저장되어 있음),
테마·밀도를 바꾼 뒤의 입력 감지(편집기가 바뀌면 다시 붙이도록 함). 회귀 테스트는 실행하지 않았다(요청 없음).
영향받을 수 있는 기능: 쿼리 창의 고침 표시와 제목(`SqlEditorPanel.setDirty`·`setContent`·`saved`가 속성 변경을 알림), File > Save(같은 동작을 머리말 단추가 부름),
라이브러리 쿼리 저장·다시 읽기의 DB/스키마 값.

## 변경 이력

- 2026-11-06 최초 작성: ① 라이브러리 트리·실행, ② 매개변수 매핑 구현, ③·④ 설계.
- 2026-11-06 ③ 예약 실행 구현(사용자 결정: 앱 실행 중에만, OS 스케줄러 기각): 예약 정의(파일), 이 PC 활성 여부(로컬), 스케줄러, 기록·보존, 조건 알림·배지, Scheduled Queries 개요.
- 2026-11-06 다른 Swing 기능과 합침: 도구 창을 오른쪽 열의 Properties 위로 옮김, 실행 기록 창 이름을 `QueryRunHistoryDialog`로 바꿈(쿼리 창 기록과 겹침),
  `history` 아이콘은 SQL 편집기 확장의 것을 함께 씀, `new-folder`의 Full color 그림을 `new-group`과 같은 폴더 모양으로 맞춤(생성기의 같은 폴더 식),
  Options의 기록 보존 칸을 쿼리 창 기록 칸과 따로 둠. 바인딩한 쿼리의 결과는 다시 읽지 않음(Export Data·결과 뷰어 필터가 값 없는 `?`로 실패하던 것).
- 2026-11-06 바인딩한 실행이 Query History에 `… = ?`로 남던 문제: 값을 SQL 리터럴로 넣은 원래 문장을 기록.
- 2026-11-06 Schedule… 대화상자의 Connections·Parameters 목록이 한 줄로 줄던 문제: 목록에 최소 높이와 세로 가중치.
- 2026-11-07 Query Library와 Properties가 한쪽을 숨긴 사이 다른 쪽을 보이면 따로 열을 만들던 문제: 서로를 기본 이웃으로 둠([swing-frontend.md](swing-frontend.md) "Query Library 자리").
- 2026-11-07 쿼리 창 도구 막대가 메인 창 둘째 줄(SQL Editor 도구 막대)로 옮겨져 머리말 위치를 "편집기 위"로 고침. [swing-frontend.md](swing-frontend.md) "SQL Editor 도구 막대".
- 2026-11-07 ④ 공유 구현: 자동 새로 고침(`QueryLibraryWatcher`, 기본 30초 + 파일 시스템 감시), Git Pull·Commit & Push와 충돌 표시(`QueryLibraryGit`, 시스템 git),
  `.chromite-queries` 내보내기·가져오기(`QueryLibraryArchive`), 저장 충돌 확인과 열린 창 다시 읽기, Save As .sql, 비밀 정보 안내·경고. "공유 설계"를 구현한 설계로 바꿈.
- 2026-11-08 비밀 기본값 경고의 단추를 예/아니요에서 저장/취소(취소가 기본)로 바꿈, Query Library 삭제 확인은 삭제/취소, 파일 덮어쓰기 확인은 바꾸기/취소.
  [swing-frontend.md](swing-frontend.md) "실행 확인의 기본 단추".
- 2026-11-08 좁은 문서 영역에서 머리말 단추가 "Para…", "Thres…"로 잘리던 문제: 글자가 들어가기 전에 단추의 최소 크기를 고정하지 않음("실행 흐름"의 "머리말 배치", "확인 (2026-11-08 …)").
- 2026-11-08 매개변수 대화상자와 바인딩 하나로(후속 #9, #22, #19): 쿼리 창 대화상자 `SqlParameterDialog`를 지우고 `QueryParameterDialog` 스크립트 모드로 합침,
  바인딩한 결과도 같은 값으로 다시 읽음(필터·서버 정렬·다음 페이지·열 값 목록·Export Data, `SQLQueryDataContainer.ParameterBinder`), 기록에는 리터럴 대신
  `:name` 글자와 값을 남기고 Open·Execute가 다시 바인딩. "매개변수 바인딩", "매개변수 대화상자와 바인딩 하나로", "확인 (2026-11-08, …)".
- 2026-11-08 `@updated`를 파일에 쓰지 않음(저녁 후속 #6): 동시에 고친 쿼리가 Git에서 그 줄로 늘 충돌하던 문제. 저장 충돌 질문의 시각은 파일의 수정 시각.
  "고친 시각을 파일에 두지 않음", "Git 루트", "남긴 것".
- 2026-11-08 머리말 오른쪽 끝에 저장 아이콘 단추(Ctrl+S와 같은 동작, 고쳤을 때만 켜짐), Database·Schema 칸을 대상 연결의 목록이 있는 콤보로 바꿈(목록을 펼칠 때만 연결,
  연결이 쓰지 않는 칸은 끔). "머리말 저장 단추", "머리말 DB/스키마 목록".
- 2026-11-08 기본 배치(앱 변경 #12): Query Library가 처음부터 보이고 오른쪽 열 위, 아래는 Chromite Agent, Properties는 숨김. "열기"에 표시(`chromite/ui-layout`).
