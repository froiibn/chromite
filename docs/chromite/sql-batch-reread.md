# 배치 결과 다시 읽기

## 목적

여러 문장을 한 번에 서버로 보낸 실행(SQL Server `GO` 배치, 선택한 여러 문장을 Ctrl+Enter로 한 번에 실행)의 결과를 다시 읽을 때
배치 전체(UPDATE, INSERT, DELETE, DDL, EXEC)를 다시 실행하지 않게 한다. 묻지 않고 데이터 변경을 되풀이하면 안전 규칙에 어긋난다
(후속 #40, 브랜치 `chromite/batch-reread-safety`). [sql-batch-errors.md](sql-batch-errors.md)(#187)를 고치다 찾은 문제다.

- 결과를 다시 읽으면 그 결과를 만든 SELECT 하나만 실행한다. 필터·정렬 감싸기(#142)도 그 문장에만 붙는다.
- 그 문장을 정할 수 없는 결과는 다시 읽지 않는다. 필터, 서버 정렬, 다음 행, 모두 가져오기, 행 수 세기, 열 필터 값 목록, Export Data가 꺼지고
  도구 설명에 이유("this result can't be read again without running the whole batch again" / "배치 전체를 다시 실행하지 않고는 이 결과를 다시
  읽을 수 없음")가 보인다.

## 문제

고치기 전 빌드(`origin/devel` c2bf527d54)에서 확인했다. 표 `t`(3행, `n` 정수), `big`(300행)을 만들고 결과를 다시 읽을 때마다 `SUM(n)`을 읽었다.
UPDATE가 다시 실행되면 3씩 오른다.

| DB | 실행 | 다시 읽기 | 고치기 전 |
|---|---|---|---|
| SQL Server | F5 `UPDATE t …; SELECT * FROM t;` | 정렬, 필터, 값 목록, 다시 읽기, 행 수, 저장 | 막힘("not a single SELECT query"), 값 그대로. 결과는 편집할 수 없음 |
| SQL Server | F5 `SELECT * FROM big; UPDATE t …;` | 다음 행 | **UPDATE가 다시 실행됨**(9 → 12) |
| SQL Server | F5 `SELECT * FROM t; UPDATE t …;` | 필터, 다시 읽기(필터 있음), 저장 뒤 새로 고침 | 값 그대로(필터를 넣으면서 JSQLParser가 첫 문장만 다시 써서 우연히 막힘). 값 목록·행 수는 구문 오류 |
| PostgreSQL | 두 문장 선택 후 Ctrl+Enter `SELECT * FROM big; UPDATE t …` | 다음 행 | **UPDATE가 다시 실행됨**(3 → 6) |
| PostgreSQL | 같은 글자를 F5 | 다음 행 | 값 그대로(문장마다 따로 실행) |
| MariaDB | 두 문장 선택 후 Ctrl+Enter | — | 기본 설정(`allowMultiQueries` 꺼짐)에서는 구문 오류라 결과 없음. 켜면 PostgreSQL과 같은 경로 |

같은 원인으로 배치의 둘째 결과 표(`SELECT …; UPDATE …; SELECT …`의 둘째 SELECT)를 다시 읽으면 배치 전체를 다시 실행하고 **첫째 결과를** 읽어
보였다. Export Data와 `@export`는 문장 수를 세어(`DataExportWizard.canExportQuery`) 이미 막혀 있었다.

## 근본 원인

결과의 데이터 컨테이너(`SQLQueryDataContainer`)가 결과를 만든 문장이 아니라 실행한 쿼리, 곧 **배치 글자 전체**를 가졌다. 엔진은 배치를
`SQLQuery` 하나로 보내고([sql-batch-errors.md](sql-batch-errors.md) "근본 원인", `SQLScriptBatches`), 쿼리 창(`SqlEditorPanel`)은 그 쿼리로
결과마다 컨테이너를 만들었다. 다시 읽기는 모두 이 컨테이너의 `readData`/`countData`로 쿼리를 다시 실행한다.

"다시 읽어도 되는가" 검사(`ResultSetSource.getRequeryBlocker`: `SQLQuery.getType() == SELECT && !isModifying()`)는 배치를 막지 못했다.
`SQLQuery`는 JSQLParser의 `Statement()`로 글자를 읽는데, 이 함수는 첫 문장 뒤의 `;`에서 멈추고 나머지를 버린다(JSQLParser 5.3으로 확인:
`SELECT * FROM t; UPDATE t SET n=1` → `PlainSelect`, `UPDATE …; SELECT …` → `Update`). 그래서 SELECT로 시작하는 배치는 SELECT 하나로 보였다.
필터나 정렬이 있으면 방언이 쿼리를 JSQLParser로 다시 써서(`injectFiltersToQuery`) 첫 문장만 남아 우연히 막혔지만, 조건이 없는 다시 읽기
(다음 행, 모두 가져오기, 조건 없는 다시 읽기)는 배치 글자를 그대로 실행했다. 저장 뒤 새로 고침(`ResultSetEditPersister.refreshRows`)은 이 검사도
하지 않았다.

## 구조

| 위치 | 하는 일 |
|---|---|
| `model.sql` `data/SQLBatchResultStatement` (새 파일) | 쿼리와 그 쿼리 안의 결과 집합 번호로 결과를 만든 문장을 찾는다. 하나의 문장이면 쿼리 그대로, 찾지 못하면 null |
| `model.sql` `data/SQLQueryDataContainer` | `restrictToResultSet(번호)`: 찾은 문장으로 쿼리를 바꾸거나, 못 찾으면 `isWholeBatchOnly()`. 그때 `readData`·`countData`는 예외로 거절 |
| `model.sql` `data/SQLQueryColumnValues` | 열 필터 값 목록: `isWholeBatchOnly()` 결과는 읽지 않음(쿼리 글자를 감싸 새 컨테이너로 읽으므로 따로 막음) |
| `ui.swing` `SqlEditorPanel.QueryHandler.createGridReceiver` | 쿼리마다 결과 표 수를 세어(`Execution.gridResultSets`, 쿼리가 다시 시작하면 처음부터: `GO 2`) 컨테이너를 그 결과 집합으로 좁힘. Export Data 쿼리를 첫 결과만이 아니라 모든 결과에 넘김 |
| `ui.swing` `ResultSetSource.getRequeryBlocker` | `isWholeBatchOnly()`면 새 문구 `editor_source_whole_batch`. 이 이유가 머리글 필터 도구 설명, 행 수 도구 설명, Export Data 도구 설명, 저장한 필터 메뉴에 그대로 보임 |
| `ui.swing` `DataExportWizard.canExportQuery` | `isWholeBatchOnly()`면 끔(문장 수 검사는 `@export`의 다음 쿼리를 위해 그대로) |
| `ui.swing` `ResultSetEditPersister.refreshRows` | 저장 뒤 새로 고침은 `getRequeryBlocker`가 null인 결과만. 아니면 입력한 값을 그대로 둠 |
| `ui.swing` `internal/EditorMessages` | `editor_source_whole_batch` (영어, 한국어) |

### 결과를 만든 문장 찾기

1. 쿼리가 파서가 뽑은 문장 하나(`SQLQuery.representsOneStatement()`)이거나, 방언의 문장 분리기로 나눠도 하나면 쿼리 그대로다(첫 결과 집합만.
   한 문장의 둘째 결과 집합은 찾지 못함).
2. 여러 문장이면 실행 확인(`SqlExecutionSafety`)과 같은 방법으로 나눈다(`SQLScriptBatches.createParserContext` + `SQLScriptParser.extractScriptQueries`).
3. 문장을 차례로 보며 결과 집합 수를 센다.

   | 문장 | 결과 집합 |
   |---|---|
   | 바꾸지 않는 SELECT(UNION, VALUES 포함) | 1 |
   | `SELECT … INTO` | 0 |
   | OUTPUT·RETURNING이 없는 INSERT·UPDATE·DELETE·MERGE | 0 |
   | DDL, COMMIT, ROLLBACK | 0 |
   | 그 밖: EXEC·CALL, DO·BEGIN 블록, SET, DECLARE, `FOR UPDATE`·데이터를 바꾸는 CTE, OUTPUT·RETURNING이 있는 DML, 읽지 못한 문장 | 모름 |

4. 결과 집합 번호에 닿은 SELECT가 그 결과의 문장이다. 그 앞에 "모름"이 있으면 찾지 않는다(null).
5. 찾은 문장은 자기 글자와 편집기 위치(배치 위치 + 문장 위치)를 가진 새 `SQLQuery`이고, 이후 검사(`getRequeryBlocker`, `canExportQuery`)는
   이 문장으로 한다.

결과 집합 번호는 쿼리 창이 센다. 엔진이 넘기는 `resultSetNumber`는 스크립트 전체에서 이어지는 번호라 쓸 수 없다. 실제 계획(Include Actual
Execution Plan)의 계획 결과 집합은 결과 표를 만들지 않으므로(`ActualPlanReceiver`) 세지 않는다.

## 설계 결정과 이유

- **결과 집합 순서를 문장 종류로 맞춘다.** 엔진은 결과마다 어느 문장의 것인지 알려 주지 않고, 수신기를 만들 때 알 수 있는 것은 그 쿼리의
  몇째 결과 집합인지뿐이다. 행 수(update count) 순서와 맞춰 보는 방법은 `SET NOCOUNT ON`이나 드라이버에 따라 행 수가 오지 않아 쓸 수 없다.
- **모르는 문장은 보수적으로 막는다.** SET은 뒤 문장의 결과를 바꿀 수 있고(`SET SHOWPLAN_XML`, `SET STATISTICS XML`은 계획 결과 집합을 더함),
  EXEC·CALL·블록은 결과 집합 수를 알 수 없다. DECLARE 뒤 SELECT는 대개 그 변수를 써서 혼자 실행할 수 없다. 그래서 흔한 `SET NOCOUNT ON; SELECT …`도
  막힌다. SELECT만 따로 실행하면(Ctrl+Enter, 또는 `GO`로 나눔) 다시 읽을 수 있다.
- **잘못 맞춰도 데이터는 바뀌지 않는다.** 찾은 문장도 "바꾸지 않는 SELECT 하나" 검사를 거친다. 드문 경우(UPDATE 트리거가 결과 집합을 돌려줌,
  결과 집합 없이 실패하고 배치가 계속된 SELECT)에 다른 SELECT를 읽을 수는 있지만 데이터를 바꾸는 문장은 실행되지 않는다.
- **거절은 모델이 한다.** 화면은 이미 `canRequery()`로 다시 읽기를 막지만, 저장 뒤 새로 고침처럼 검사를 거치지 않던 경로가 있었다.
  컨테이너가 `readData`·`countData`에서 거절하면 새로 생긴 경로도 배치를 다시 실행할 수 없다.
- **Export Data는 모든 결과에서 쓸 수 있다.** 전에는 쿼리의 첫 결과 집합만 Export Data 쿼리를 가졌다(첫 결과만 `readData`로 다시 얻을 수 있었기 때문).
  이제 결과마다 자기 문장을 가지므로 배치의 둘째 SELECT 결과도 내보낼 수 있다. 문장을 정하지 못한 결과는 꺼지고 이유가 보인다.
- **저장 뒤 새로 고침도 같은 조건이다.** 다시 읽을 수 없는 결과(배치, 프로시저 호출)를 편집해 저장하면 저장은 하되 다시 읽지 않고 입력한 값을 둔다.
- **트랜잭션은 건드리지 않는다.** 다시 읽기는 SELECT 하나를 실행할 뿐 커밋·롤백하지 않는다.

## 확인

Docker SQL Server 2022, PostgreSQL 17, MariaDB 11에서 각자 만든 `chr_batch_reread` 데이터베이스로 확인했다(영어·한국어 화면).
화면 조작은 앱 안에서 Swing API로 했다(화면 입력 없음). 매 단계 뒤 결과의 실행 문맥에서 `SUM(n)`을 읽었다.

| DB | 실행 | 다시 읽기 | 고친 뒤 |
|---|---|---|---|
| SQL Server | F5 `UPDATE t …; SELECT * FROM t;` | 정렬, 필터, 값 목록, 다시 읽기, 편집·저장 뒤 새로 고침 | 컨테이너 `SELECT * FROM dbo.t`, Export Data 켜짐, 값 그대로(3). 필터로 다시 읽은 결과는 편집 가능 |
| SQL Server | F5 `SELECT * FROM big; UPDATE t …;` | 행 수, 다음 행, 조건 없는 다시 읽기, 서버 정렬, 모두 가져오기 | 컨테이너 `SELECT * FROM dbo.big`, 행 수 300, 값 그대로(6) |
| SQL Server | F5 `SELECT … WHERE id = 1; UPDATE …; SELECT * FROM big;` | 첫 표 다시 읽기, 둘째 표 다음 행 | 첫 표는 id 1 행, 둘째 표는 `big`의 300행, 값 그대로(9) |
| SQL Server | F5 `EXEC dbo.bump; SELECT * FROM big;` | 모든 다시 읽기 | 두 표 모두 막힘, 이유 표시(머리글·행 수·Export Data 도구 설명), 값 목록 거절, 값 그대로(12) |
| SQL Server | F5 `SET NOCOUNT ON; SELECT * FROM big;` | — | 막힘(설계 결정) |
| SQL Server | `UPDATE …` `GO` `SELECT * FROM big` | 다음 행 | 전과 같음(쿼리가 따로라 다시 읽기 가능), 값 그대로 |
| SQL Server | `@export {…}` 다음에 `SELECT …; UPDATE …` | — | 전과 같음: "@export exports the result of the next statement, which must be one SELECT…"로 거절 |
| PostgreSQL | 두 문장 선택 Ctrl+Enter `SELECT * FROM big; UPDATE t …` | 행 수, 모두 가져오기, 다시 읽기, 다음 행, 정렬, 필터, 값 목록 | 컨테이너 `SELECT * FROM big`, 값 그대로 |
| PostgreSQL | 세 문장 선택 `UPDATE …; SELECT * FROM t; SELECT * FROM big` | 첫 표 다시 읽기·필터, 둘째 표 다음 행 | 각자 자기 SELECT, 값 그대로 |
| PostgreSQL | 두 문장 선택 `DO $$ … UPDATE … $$; SELECT * FROM big` | 다음 행 | 막힘(블록은 모르는 문장), 값 그대로 |
| MariaDB | F5 `SELECT * FROM big; UPDATE t …` | 다음 행 | 전과 같음, 값 그대로 |
| MariaDB(`allowMultiQueries=true`) | 두 문장 선택 Ctrl+Enter | 행 수, 다음 행, 다시 읽기, 필터 | 컨테이너 `SELECT * FROM big`, 값 그대로 |

Export Data는 메뉴 상태와 도구 설명, 컨테이너의 쿼리로 확인했고 마법사로 파일을 쓰지는 않았다(마법사는 같은 컨테이너의 `readData`로 읽는다).

## 한계

- 연결이 끊겨 엔진이 다시 연결한 뒤 같은 쿼리를 다시 실행하면(복구) 쿼리 창의 결과 수 세기가 이어져, 배치의 결과가 다른 SELECT에 맞춰지거나
  막힐 수 있다. 실행되는 것은 SELECT뿐이다.
- 결과 집합 수를 모르는 문장(SET, DECLARE, EXEC 등) 뒤의 결과는 다시 읽을 수 없다. SELECT를 따로 실행하면 된다.
- 배치에서 실행한 결과 표는 처음에는 편집할 수 없을 수 있다(SQL Server 결과의 열을 테이블에 묶을 때 엔진의 실행 원본이 배치 쿼리라서). 필터나 정렬로
  다시 읽으면 결과 문장으로 묶여 편집할 수 있다. 전과 같은 동작이고 이번 범위 밖이다.

## upstream 대비 변경점

- `SQLQueryDataContainer`: `restrictToResultSet`, `isWholeBatchOnly`, 다시 읽기 거절. SWT는 쿼리를 다시 실행하기 전에 데이터를 바꾸는 쿼리인지 물었지만
  (`verifyQuerySafety`) Swing은 묻지 않고 결과를 만든 SELECT만 실행한다.

## 변경 이력

- 2026-11-08 최초 작성: 배치 결과를 다시 읽으면 그 결과를 만든 SELECT만 실행, 문장을 정하지 못한 결과는 다시 읽기·내보내기를 끄고 이유를 보임,
  저장 뒤 새로 고침도 같은 조건 (후속 #40)
