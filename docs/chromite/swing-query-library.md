# Query Library (Swing)

## 목적

DBA가 자주 쓰는 상태 확인 쿼리(세션 수, 대기, 디스크 사용량 등)를 매번 `.sql` 파일에서 불러오지 않고, Chromite 안의 트리에서 관리하고 바로 실행한다.
저장 프로시저처럼 서버에 두는 것이 아니라 Chromite 쪽에 두는 평범한 SQL이다. Chromite 고유 기능이다(DBeaver에는 대응 기능이 없다).

사용자 로드맵과 상태:

| 단계 | 내용 | 상태 |
|---|---|---|
| ① | 라이브러리 트리 + 실행 | 구현 (2026-11-06) |
| ② | 매개변수 매핑 | 구현 (2026-11-06) |
| ③ | 예약 실행 | 설계(아래 "예약 실행 설계"), 구현 여부는 그 절의 상태 표를 본다 |
| ④ | 다른 사람과 공유 | 설계만 (아래 "공유 설계"), **구현하지 않음**. 저장 형식과 루트 구조는 공유를 전제로 만들었다 |

## 구조

| 클래스 (`com.seizia.chromite.ui.swing`) | 역할 |
|---|---|
| `QueryLibraryPanel` | 왼쪽 도구 창 "Query Library": 트리, 도구 막대, 검색란, 오른쪽 클릭 메뉴, 끌어다 놓기, Query > Save to Query Library… |
| `QueryLibrary` | 저장소: 루트 목록, 폴더 트리 읽기, 쿼리 파일 만들기·저장·이름 바꾸기·옮기기·복제·삭제, 자연 정렬, ID → 파일 색인 |
| `QueryLibraryItem` | 쿼리 하나(파일 하나)의 값: 이름, 설명, SQL, 기본 대상, 기본 DB/스키마, 시각, 매개변수 정의, 모르는 머리말 줄 |
| `QueryLibraryParameter` | 매개변수 정의(표시 이름, 형식, 기본값, 필수, 선택 목록, 조회 쿼리)와 형식 변환·검사 |
| `QueryLibraryFile` | 파일 형식 읽기·쓰기(머리말 주석 + SQL) |
| `QueryLibraryState` | 이 PC에만 두는 쿼리별 상태(매개변수 마지막 입력값) |
| `QueryLibraryEditor` | 쿼리 창 확장: 이름·설명·대상·DB/스키마·매개변수 머리말, 라이브러리로 저장, 실행 전 매개변수 묻기, 실행 대상 찾기 |
| `QueryParameterBinder` | 매개변수 바인딩(`:name` → `?` + JDBC 바인딩, `${name}` → SQL 글자), 연결 없이 매개변수 찾기 |
| `QueryParameterDialog` | 실행할 때 값을 묻는 대화상자 |
| `QueryParameterDefinitionsDialog` | 편집기의 "Parameters…": 매개변수 정의 표 |
| `QueryLibraryRootsDialog` | "Library Roots…": 루트 목록 |
| `SqlEditorExtension`, `SqlParameterBinding` | 쿼리 창(`SqlEditorPanel`)에 붙이는 확장 지점과 실행별 바인딩 계약 |

공유 파일에 넣은 연결 지점(다른 작업과 겹치는 곳을 줄이기 위해 작게 둠):

| 파일 | 바꾼 것 |
|---|---|
| `MainFrame` | Query Library 도구 창(Object Explorer와 문서 사이 열, 처음에는 숨김, View > Query Library, Ctrl+Alt+T), Query > Save to Query Library…, `QueryLibraryPanel.Host` 구현(창 열기·찾기, 현재 연결), 확장이 붙은 쿼리 창의 저장을 확장에 넘김 |
| `SqlEditorPanel` | `setExtension`(머리말을 도구 막대 아래에), `markModified`, 탭 아이콘, 실행 전 `prepareExecution`, 준비 Job에서 `SqlParameterBinding.prepare`, 엔진 핸들러의 바인딩 메서드 |
| `ToolWindow` | `setDefaultShown(false)`: 처음에는 숨긴 도구 창(Reset Window Layout도 숨김으로 되돌림) |
| `SQLQueryExecuteHandler`, `SQLQueryExecuteJob` (model.sql, Chromite 실행 엔진) | `hasBoundParameters`/`bindParameters`: 핸들러가 값을 바인딩하는 쿼리는 스크립트 문장 대신 prepared statement로 만들고 바인딩. 기본값은 바인딩 없음이라 SWT 편집기 동작은 같다 |
| `tools/branding/gen_ui_icons.py` | 아이콘 `library`(Tabler books), `library-query`(문서 + 실행 배지), `new-folder`(폴더 + 더하기 배지), 두 팩·두 테마 |

## 화면

```
┌ Object Explorer ┐┌ Query Library ─────────── — ┐┌ 문서 탭 ...
│                 ││ [▶] | [+문서] [+폴더] | [⟳] [⚙] │
│                 ││ [🔍 Search queries        ] │
│                 ││ 📚 My Queries               │
│                 ││  └ 📁 01. 상태 모니터링      │
│                 ││     ├ ▶ 01. 세션 수   QL PostgreSQL
│                 ││     ├ ▶ 02. 대기      ask
│                 ││     └ ▶ 03. 디스크 사용량
│                 ││ 📚 Team (shared)  read-only │
```

- **열기:** View > Query Library(Ctrl+Alt+T). 도구 창 하나를 Object Explorer와 문서 영역 사이의 열로 둔다. 처음에는 숨김이다(1280px 기본 창에서 세 도구 창을 모두 펴면 문서가 너무 좁다).
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
-- @updated: 2026-11-06T10:20:00+09:00
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
| `@created`, `@updated` | ISO-8601 시각(초 단위, 지역 오프셋) |
| `@param <이름>` + 하위 줄 | 매개변수 정의(`label`, `type`, `default`, `required`, `choice`(여러 줄), `lookup`). 아무 정의가 없는 매개변수는 쓰지 않는다 |

규칙(`QueryLibraryFile`):

- 머리말은 파일 맨 앞에서 `-- @`로 시작하는 줄과 그 아래 `--   `(하이픈 둘 + 공백 셋)으로 시작하는 하위 줄이다. 다른 줄이 나오면 끝나고, 바로 뒤의 빈 줄 하나는 구분 줄이다.
  값 안의 줄바꿈과 역슬래시는 `\n`, `\\`로 쓴다(한 항목 = 한 줄이라 Git 비교가 읽기 쉽다).
- 항목 순서는 늘 같고 값이 없는 항목은 쓰지 않는다. 그래서 같은 내용을 다시 저장해도 `@updated` 말고는 줄이 바뀌지 않는다.
- 모르는 항목(새 버전의 항목, 예: 공유·예약 정보)은 원래 줄 그대로 머리말 끝에 다시 쓴다. 다른 버전이 쓴 정보를 지우지 않는다.
- 머리말 전체가 SQL 주석이라 파일을 그대로 다른 SQL 도구에서 열고 실행할 수 있다(`:name`은 그 도구의 매개변수 문법에 따름).
- 머리말이 없는 `.sql` 파일(다른 도구에서 만든 파일, 탐색기에서 끌어다 놓은 파일)도 쿼리로 보인다. 이름은 파일 이름이고, Chromite에서 저장하면 머리말과 ID가 붙는다.
- 문자 집합·BOM·줄바꿈은 기존 파일의 것을 유지한다(`SqlFileContent`). 새 파일은 UTF-8(BOM 없음), LF다. 운영체제에 따라 공유 파일의 줄바꿈이 바뀌지 않게 하기 위해서다.
- 파일 이름은 쿼리 이름에서 만든다: 파일 시스템이 거부하는 글자(`\ / : * ? " < > |`, 제어 문자)는 `_`, 끝의 점·공백은 뺌, 120자까지. 같은 이름이 있으면 ` (2)`를 붙인다.
  한글 이름이 그대로 파일 이름이 된다(예: `02. 오래 실행 중인 세션.sql`).
- 이름이 `.`으로 시작하는 폴더·파일(`.git` 등)은 읽지 않는다.

## 저장 위치와 루트

- 기본 루트 "My Queries": 작업 공간 폴더의 `QueryLibrary`(`DBPWorkspace.getAbsolutePath()/QueryLibrary`, 예: `-data D:\ws`이면 `D:\ws\QueryLibrary`).
  사용자 홈이나 C: 드라이브 경로에 묶이지 않고 작업 공간과 함께 옮겨진다. 처음 읽을 때 없으면 만든다.
- **여러 루트:** "Library Roots…" 대화상자에서 루트(이름, 폴더, 읽기 전용)를 더하고 뺀다. 개인 루트 + 공유 루트(네트워크 폴더, Git 작업 사본)를 함께 쓰는 구조다.
  목록은 이 번들 설정 저장소의 `queryLibrary.roots`(한 줄에 `이름<TAB>경로<TAB>읽기 전용`)에 저장한다. 루트를 빼도 파일은 지우지 않는다. 목록이 비면 개인 루트를 다시 넣는다.
- Tools > Options에 넣지 않은 이유: 같은 시간에 다른 작업이 `OptionsDialog`를 바꾸고 있어(편집기 글꼴·색) 겹침을 피했다. 루트 목록은 표 하나라 도구 창의 ⚙에서 바로 여는 편이 쓰기도 쉽다.
- **바깥 변경:** 트리는 디스크를 그대로 보여 주므로, 동료가 공유 폴더의 파일을 바꾸거나 `git pull` 하면 Refresh로 반영된다. Run·Edit는 트리를 읽은 시점이 아니라 그때의 파일을 다시 읽어 쓴다.
  열려 있는 편집 창은 다시 읽지 않는다(저장하면 그 창의 내용으로 덮어쓴다, 남긴 것).
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

**Edit**(더블클릭)는 같은 창을 연다(실행하지 않음). 도구 막대 아래 머리말이 붙는다:

```
[▶ Execute] [■] | [연결 ▾] [DB/스키마 ▾]
Name [02. 대기          ]  Description [대기 이벤트 상위 10개                ]
Target [QL SQL Server ▾]   Database [master  ] [Schema ] [Parameters (1)…]
SELECT ...
```

- 편집기는 쿼리 창 그대로라 구문 색, 자동완성, 찾기·바꾸기, F5 실행(선택만 실행 포함)이 된다. 머리말을 바꾸면 탭에 `*`가 붙는다.
- Target 콤보: "Ask when run", "Current connection", 연결 목록. 이 PC에 없는 연결이 저장된 쿼리는 "<이름> (not found)" 항목으로 보여 주고 그대로 저장한다(동료의 설정을 지우지 않음).
- 저장(Ctrl+S, File > Save, 닫을 때·끝낼 때 Save)은 `QueryLibrary.save`: 파일을 ID로 다시 찾고, 머리말과 SQL을 쓰고, `@updated`를 바꾸고, 이름이 바뀌었으면 파일 이름도 바꾼다.
  Save As도 같은 저장이다(라이브러리 쿼리를 아무 파일로 내보내는 것은 남김). 읽기 전용 루트의 쿼리는 저장하지 않고 Duplicate를 안내한다.
- SQL에서 빠진 매개변수의 정의는 저장할 때 버린다.

## 매개변수

### 찾기

- **실행할 때(권위):** SWT 편집기와 같은 모델 파서. `SQLScriptBatches.extractQueries` → `SQLScriptParser.extractScriptQueries(..., parseParameters = true)` →
  `SQLScriptParser.parseParametersAndVariables`([SQLScriptParser.java:914](../../plugins/org.jkiss.dbeaver.model.sql/src/org/jkiss/dbeaver/model/sql/parser/SQLScriptParser.java)) →
  `DefaultSQLQueryParameterParser.parseParametersAndVariables`([DefaultSQLQueryParameterParser.java:60-133](../../plugins/org.jkiss.dbeaver.model.sql/src/org/jkiss/dbeaver/model/sql/parser/DefaultSQLQueryParameterParser.java)).
  규칙 기반 스캐너의 `T_PARAMETER` 토큰이 `:name`(접두어는 연결 설정 `sql.parameter.prefix`, 기본 `:`)이고, `${name}`·`{{name}}`·`$P{name}` 변수는
  `SQLQueryParameter.getVariablePattern()`([SQLQueryParameter.java:32-33](../../plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/model/sql/SQLQueryParameter.java))으로 찾는다.
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
  SWT 편집기의 `SQLQueryParameterRegistry`([SQLQueryParameterBindDialog.java:89-92, :327-332](../../plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/dialogs/SQLQueryParameterBindDialog.java))는
  이름 하나에 값 하나를 모든 쿼리가 함께 쓰므로 쿼리별 기억에 맞지 않아 쓰지 않았다.

### 매개변수 바인딩

- **SWT 편집기의 방식:** `SQLEditor`가 `SQLEditorParametersProvider`([SQLEditor.java:2494](../../plugins/org.jkiss.dbeaver.ui.editors.sql/src/org/jkiss/dbeaver/ui/editors/sql/SQLEditor.java))로
  `SQLQueryParameterBindDialog`를 띄우고, 엔진이 `SQLScriptContext.fillQueryParameters`([SQLScriptContext.java:299-353](../../plugins/org.jkiss.dbeaver.model.sql/src/org/jkiss/dbeaver/model/sql/SQLScriptContext.java))
  → `SQLUtils.fillQueryParameters`([SQLUtils.java:1446-1461](../../plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/model/sql/SQLUtils.java))로 **값을 SQL 글자로 치환**한다
  (사용자가 `'text'`처럼 따옴표까지 입력). 네이티브 바인딩은 Firebird `EXECUTE BLOCK`(`FireBirdStatementProducer`)뿐이다.
- **Query Library의 방식:** 남이 만든 쿼리에 값만 넣는 용도라 글자 치환은 SQL 주입과 따옴표 실수를 부른다. 그래서
  1. 준비 Job에서 `QueryParameterBinder.prepare`가 파서가 찾은 `:name` 자리를 `?`로 바꾸고, 형식에 맞는 Java 값(`QueryLibraryParameter.toJdbcValue`: `String`, `Integer`/`Long`/`BigDecimal`,
     `java.sql.Date`, `Timestamp`, `Boolean`, 빈 값 = SQL NULL)을 쿼리의 데이터(`SQLQuery.setData`)로 붙인다. 같은 이름이 여러 번 나오면 자리마다 같은 값이다.
  2. 엔진(`SQLQueryExecuteJob.executeStatement`)이 `handler.hasBoundParameters`가 참인 쿼리는 `DBCStatementType.QUERY`(JDBC `PreparedStatement`)로 만들고
     `handler.bindParameters` → `PreparedStatement.setObject/setNull`로 바인딩한다. 바꾼 쿼리는 매개변수 목록이 없으므로 엔진의 글자 치환(`fillQueryParameters`)은 아무것도 하지 않는다.
  - 정수는 `Integer`(범위를 넘으면 `Long`)로 바인딩한다. PostgreSQL의 `make_interval(mins => ?)`처럼 정수만 받는 함수에 `BigDecimal`(numeric)은 맞지 않기 때문이다.
  - SQL Server `GO` 배치는 배치 하나가 prepared statement 하나다(`DECLARE`한 변수와 `?`를 함께 쓸 수 있음). JDBC가 아닌 연결은 바인딩할 수 없어 오류로 끝난다.
- **`${name}`·`{{name}}` 변수(표시: "SQL text"):** SWT에서도 텍스트 치환 변수이고 식별자·SQL 조각 자리(테이블 이름, `ORDER BY` 열 등)라 바인딩할 수 없다.
  값을 **그대로 SQL에 끼운다**. 대화상자와 정의 표에 "SQL text"로 표시하고, 쓰는 사람이 이 점을 알도록 도구 설명을 단다. 신뢰하지 않는 값에는 `:name`을 쓴다.

## 예약 실행 설계 (③)

사용자 결정(2026-11-06): **Chromite가 실행 중일 때만 앱 안에서 예약 실행한다.**

검토한 방식:

| 방식 | 판단 |
|---|---|
| 앱 안 스케줄러 (Chromite 실행 중에만) | **채택**. 연결·자격 증명·드라이버·알림을 그대로 쓰고, 설치·권한이 필요 없다 |
| OS 스케줄러(작업 스케줄러/cron) + 헤드리스 실행기(CLI) | **기각 — 사용자 결정 2026-11-06**. (참고로 남기는 단점: 저장된 비밀번호를 헤드리스 프로세스가 읽어야 하고, OS별 등록·해제와 결과를 앱에 되돌리는 경로가 따로 필요) |

설계:

- **예약 정의(쿼리 파일, 공유됨):** 머리말 `-- @schedule` 블록. 간격(`every: 5m`, `every: 1h`), 매일 시각(`daily: 08:30`), 평일 시각(`weekdays: 08:30`),
  대상 서버(여러 개: `target: <id>|<이름>`), 예약 실행용 매개변수 값(`param.<이름>: 값`), 조건(`condition: rows > 0` 등), 사용 여부(`enabled`).
  cron 문법 없이 위 세 가지 형태만 둔다(간단한 UI).
- **이 PC에서 켤지(로컬 설정):** 파일의 `enabled`는 "예약할 수 있음"이고, 실제로 이 PC에서 돌릴지는 로컬 설정(`queryLibrary.schedule.active.<id>`)이다.
  공유 쿼리가 동료 PC마다 저절로 돌지 않게 하기 위해서다. 공유 루트의 쿼리는 기본 꺼짐, 내가 만든 예약은 이 PC에서 켜짐.
- **스케줄러:** 백그라운드 Job(EDT 아님). 1분 간격 타이머가 다음 실행 시각이 지난 예약을 고른다. 쿼리별로 한 번에 하나만 돈다(앞 실행이 끝나지 않았으면 건너뛰고 기록).
  앱을 끝내면 깔끔하게 멈추고(진행 중인 실행 취소), 다음 시작 때 다시 이어서 돈다. 꺼져 있던 동안 놓친 실행은 몰아서 하지 않는다.
- **실행:** 쿼리 창 없이 같은 엔진(`SQLQueryExecuteJob`)과 같은 바인딩(`QueryParameterBinder`)을 쓴다. 결과 집합의 행 수와 앞 N행만 받는 수신기를 둔다.
- **결과 기록:** 쿼리별 기록(시각, 서버, 걸린 시간, 행 수, 상태/오류, 앞 N행 스냅숏). 작업 공간 메타데이터 폴더에 쿼리 ID별 파일로 둔다(공유 폴더에 쓰지 않음).
  보존 기간·개수는 설정에서 정한다. 트리의 "History…"로 본다.
- **조건과 알림:** "행 수 > 0", "행 수 >= X", "첫 열 값 > X" 같은 조건. 맞으면 기존 알림 토스트(`NotificationToast`)를 띄우고 트리 노드에 배지를 달아 확인할 때까지 둔다.
  메일 등 외부 발송은 하지 않는다.
- **개요:** "Scheduled" 목록(활성 예약, 다음 실행 시각, 마지막 상태, 일시 정지/다시 시작).
- **보안:** 예약 실행은 연결에 저장된 자격 증명만 쓴다. 비밀번호를 저장하지 않은 연결은 예약 실행 때 묻지 않고 "자격 증명 없음"으로 기록한다(백그라운드 대화상자 금지).

## 공유 설계 (④, 구현하지 않음)

지금 구조에서 이미 되는 것: 루트를 공유 폴더나 Git 작업 사본으로 두면 여러 사람이 같은 파일을 쓴다(읽기 전용 루트, Refresh, ID 기반 상태, 연결 이름으로 찾기).
그 위에 만들 것:

- **공유 폴더 루트:** 네트워크 폴더(`\\server\share\queries`)를 루트로 추가. 관리자만 쓰기 권한을 갖고 나머지는 읽기 전용 루트로 쓰는 것을 권장.
  파일 시스템 감시(`WatchService`, 네트워크 폴더에서는 신뢰도가 낮아 주기 다시 읽기와 함께)로 자동 반영.
- **Git 루트:** 루트를 Git 작업 사본으로 두고 Pull / Commit & Push 명령을 단다(JGit 또는 시스템 `git`). 파일 하나 = 쿼리 하나, 정해진 항목 순서라 충돌이 줄 단위로 작다.
  충돌은 Chromite가 합치지 않고 충돌 파일을 표시해 사용자가 고친다.
- **내보내기/가져오기:** 폴더 하나를 zip(`.chromite-queries`)으로 묶어 보내고 가져온다. 같은 ID가 있으면 "덮어쓰기 / 사본으로 / 건너뛰기"를 묻는다.
- **읽기 전용과 편집 가능:** 루트 단위(지금 구현). 공유 쿼리를 고치려면 Duplicate로 개인 루트에 복사한다.
- **충돌 처리:** 저장할 때 파일의 `@updated`가 연 시점과 다르면(다른 사람이 먼저 저장) 덮어쓰기 전에 묻는다(지금은 묻지 않음, 남긴 것).
- **비밀 정보:** 쿼리 파일에는 비밀번호·토큰을 절대 쓰지 않는다. 연결은 ID와 이름으로만 가리키고 자격 증명은 각자의 Chromite 연결 설정(보안 저장소)에 있다.
  매개변수 마지막 입력값과 예약 활성 여부도 로컬 설정에만 둔다. `${name}` 기본값에 비밀을 넣지 말라고 문서화한다.

## upstream 대비 변경점

- Chromite 고유 기능(새 클래스). DBeaver의 모델 계층(파서, 실행 엔진, 데이터 소스 레지스트리, 실행 문맥 기본값)을 그대로 쓴다.
- Chromite 실행 엔진(`SQLQueryExecuteHandler`/`SQLQueryExecuteJob`)에 네이티브 바인딩 지점 두 개를 더했다. 기본 구현은 바인딩 없음이라 SWT 편집기(`SQLQueryJob`) 동작은 같다.

## 남긴 것

- 열려 있는 편집 창이 바깥에서 바뀐 파일을 알아채지 못한다(저장하면 덮어씀). 저장할 때 `@updated` 비교로 묻기(④ 충돌 처리).
- 라이브러리 쿼리를 일반 `.sql` 파일로 내보내는 Save As.
- 파일 시스템 감시에 의한 자동 새로 고침(지금은 Refresh).
- 트리 안에서 바로 이름 바꾸기(지금은 이름 입력 대화상자).

## 확인 (2026-11-06)

빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce,product-chromite-swing` → BUILD SUCCESS.
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

스크린숏: `D:\Tools\_scratch\query-library\shots\`. 회귀 테스트는 실행하지 않았다(요청 없음).
영향받을 수 있는 기능: SWT·Swing의 쿼리 실행(실행 엔진의 바인딩 분기, 기본은 기존 경로), Swing 쿼리 창(확장 지점, 저장), 도구 창 배치(새 열, 처음에는 숨김).

## 변경 이력

- 2026-11-06 최초 작성: ① 라이브러리 트리·실행, ② 매개변수 매핑 구현, ③·④ 설계.
