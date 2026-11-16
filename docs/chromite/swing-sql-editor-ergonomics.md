# 쿼리 편집기 편의 기능 (B6)

SWT 대응 점검 영역 B의 쿼리 편집기 편의 행(S3, S28, S31, S33, S34, S35, S37, S44)을 Swing 쿼리 창에 옮긴 기록이다.
행의 원래 상태는 [swt-parity/B-editors.md](swt-parity/B-editors.md), 편집기 전체 구조는 [swing-frontend.md](swing-frontend.md)와
[swing-sql-editor-plus.md](swing-sql-editor-plus.md), 자동완성은 [swing-sql-completion.md](swing-sql-completion.md)에 있다.

## 목적

- 긴 스크립트를 읽고 다니기 쉽게 한다: 코드 접기, 문장 목록(Outline), 같은 이름 강조.
- 이름이 무엇인지 편집기에서 바로 본다: 마우스 올림 설명, Ctrl+클릭으로 개체로 이동, 자동완성 제안 옆 설명 창.
- SWT 편집기의 글자 도구를 되살린다: 블록 주석 토글, 공백 다듬기, 구분 목록 바꾸기, 소스 코드로 복사, 웹에서 찾기, 자동 줄 바꿈.
- 결과를 다루기 쉽게 한다: 결과 고정(다음 실행이 지우지 않음), 결과를 편집기 옆에 놓기, 결과 최대화.

## 구조

모두 `com.seizia.chromite.ui.swing` 번들이다.

| 클래스 | 역할 |
|---|---|
| `SqlFoldParser` (새) | RSyntaxTextArea `FoldParser` 구현. 문장, `BEGIN…END`·`CASE…END` 블록, 여러 줄 괄호, 여러 줄 블록 주석을 접는다. Outline과 "소스 코드로 복사"가 쓰는 문장 목록(`findStatements`) |
| `SqlOutlinePanel` (새) | Outline 도구 창. 활성 쿼리 창(또는 DDL 탭)의 문장 목록, 고르면 편집기에서 그 문장을 선택 |
| `SqlObjectNavigation` (새) | 이름 위 마우스 올림 설명(`ToolTipSupplier`), Ctrl+클릭(`LinkGenerator`), 개체 설명 HTML(`describe`, 자동완성 설명 창도 씀) |
| `SqlTextTools` (새) | 블록 주석 토글, 공백 다듬기, 구분 목록 바꾸기(설정 대화상자), 소스 코드로 복사, 웹에서 찾기 |
| `SqlEditorPanel` | 편집 영역에 접기·같은 단어 표시를 켜고, 새 명령(`SqlTextArea.Command`)과 키, 이름 탐색 설치, `pinResults`, `Host.revealNode` |
| `SqlEditCommands` | Edit &gt; Advanced에 새 항목(블록 주석, 공백 다듬기, 구분 목록, 소스 코드로 복사, 웹에서 찾기, 자동 줄 바꿈, 접기) |
| `SqlCompletionPopup` | 제안 설명 창(목록 옆 두 번째 창) |
| `SqlCompletionAnalysis` | 문장의 의미 모델 만들기를 `recognizeStatement`로 떼어 자동완성과 이름 탐색이 같이 씀. `Proposal.getObject()` |
| `QueryResultsView` | 결과 고정: 고정 탭(2026-11-08부터 고른 결과 탭 하나), 닫기 확인, 지울 결과만의 변경 수(`getCurrentChangeCount`) |
| `ToolWindow.SplitHost` | Results를 문서 옆에 놓기(`setBeside`), 최대화(`setMaximized`) |
| `MainFrame` | Outline 도구 창, Query &gt; Pin Results, View &gt; Results Beside Editor·Maximize Results, Ctrl+클릭한 개체를 Object Explorer에 보이기 |
| `EditorMessages`, `CoreMessages` (+ `_ko`) | 새 문구(영어·한국어) |

### 메뉴와 키

| 기능 | 위치 | 키 | SWT 키 |
|---|---|---|---|
| 블록 주석 토글 | Edit &gt; Advanced | Ctrl+Shift+/ (macOS ⌥⌘/) | 같음 |
| 공백 다듬기(양쪽·앞·뒤) | Edit &gt; Advanced &gt; Trim Whitespace | 없음 | 없음 |
| 구분 목록 바꾸기 | Edit &gt; Advanced | 없음 | 없음 |
| 소스 코드로 복사(Java, Python, C/C++, Delphi, 서식 없는 SQL, HTML) | Edit &gt; Advanced &gt; Copy as Source Code | 없음 | 없음(대화상자) |
| 선택 영역을 웹에서 검색 | Edit &gt; Advanced | 없음 | 없음 |
| 자동 줄 바꿈 | Edit &gt; Advanced | Ctrl+Alt+Shift+W | 같음 |
| 블록 접기·펴기, 모두 접기·펴기, 주석 접기 | Edit &gt; Advanced &gt; Folding | Ctrl+숫자패드 - / + / ÷ / × | 접기는 여백 단추 |
| Outline | View &gt; Outline | Ctrl+Alt+O | Window &gt; Outline |
| 결과 고정 | Query &gt; Pin Results | Ctrl+Shift+P | 같음(탭 고정) |
| 결과를 편집기 옆에 | View &gt; Results Beside Editor | 없음 | 없음(도구 단추) |
| 결과 최대화 | View &gt; Maximize Results | Ctrl+Shift+R(2026-11-12부터, 앱 변경 #66) | Ctrl+Shift+T |
| 개체 설명 | 이름 위에 마우스 | — | 같음 |
| 개체로 이동 | Ctrl+클릭 | — | 같음(F3도 있었음) |

## 설계 결정

### 코드 접기 (S31)

- RSyntaxTextArea 4.0.1에는 SQL 접기 파서가 없다(`FoldParserManager`의 기본 파서는 중괄호·XML·HTML·Python 등). 브리프는 라이브러리의
  접기 파서를 쓰라고 했지만 SQL용이 없어, 같은 라이브러리의 `FoldParser` 인터페이스를 구현해 SQL 문법 스타일에 등록했다.
  접기 관리자, 여백의 접기 단추, 접기 동작과 키는 라이브러리 것이다.
- 편집기가 칠해 둔 토큰으로 읽는다. 문자열·따옴표 이름·주석 안의 `BEGIN`이나 괄호는 토큰 종류로 건너뛴다.
- 방언 파서(`SQLScriptParser`)를 쓰지 않은 이유: 연결이 있어야 하고(파서 문맥이 데이터 소스를 요구), 접기는 글자를 칠 때마다 EDT에서
  다시 계산된다. 그래서 문장 경계는 실행의 문장 나누기와 다를 수 있다.
- 문장 끝은 블록 밖의 `;`, 줄에 혼자 있는 `GO`(반복 수 포함)·`/`, 블록·괄호 밖의 빈 줄이다. 빈 줄은 세미콜론 없이 빈 줄로 문장을
  나누는 SSMS식 스크립트 때문에 넣었다.
- `BEGIN TRAN/TRANSACTION/WORK/DISTRIBUTED/ISOLATION`과 `BEGIN;`은 트랜잭션 시작이라 블록을 열지 않는다. `END IF/LOOP/WHILE/REPEAT/FOR`는
  이 파서가 열지 않은 블록을 닫으므로 무시하고, `END CASE`는 `CASE`를 닫는다. 짝이 맞지 않는 `END`·`)`는 무시하고 문장 끝까지 닫히지 않은
  범위는 버린다(입력 중).
- SWT의 "접기 켜기·끄기" 설정은 옮기지 않았다. 접기는 늘 켜져 있고 여백 단추를 쓰지 않으면 아무 영향이 없다.
- 여백의 펼친 접기 단추는 라이브러리 기본값대로 마우스를 여백에 올렸을 때만 보이고, 접힌 범위의 단추는 늘 보인다.
- 파서는 텍스트 영역이 처음 만들어지기 전에 등록한다(`SqlTextArea`의 정적 초기화). 접기 관리자는 문법 스타일을 정할 때 파서를 찾으므로
  나중에 등록하면 그 편집기에는 접기가 생기지 않는다(실행 확인 중 발견).

### Outline (S28)

- SWT Outline 보기처럼 스크립트를 탐색하는 도구 창이다. 오른쪽 열(Properties 위)에 처음에는 숨겨 둔다. 2026-11-08부터는 열면 오른쪽 열 위쪽
  Query Library 자리의 탭이다(없으면 Properties의 탭, 둘 다 없으면 Chromite Agent 위, [swing-frontend.md](swing-frontend.md) "기본 배치: Query Library와 Chromite Agent").
- 문장 목록은 접기와 같은 `SqlFoldParser.findStatements`다. 연결 없는 창에서도 되고, 접기 범위와 목록이 같다.
- 문장마다 줄 번호와 주석을 뺀 첫 줄을 보인다. SWT는 문장 안의 구조(SELECT 목록, FROM 표)까지 나무로 보였는데 그것은 옮기지 않았다.
- 문서가 바뀌면 0.4초 뒤 다시 만들고, 커서가 있는 문장을 고른다. 도구 창이 숨어 있으면 만들지 않는다.

### 같은 이름 강조, 마우스 올림 설명, Ctrl+클릭 (S35)

- 같은 이름 강조는 RSyntaxTextArea의 `setMarkOccurrences`다. 색은 테마 XML의 `markOccurrencesHighlight`(밝은 `edebfc`, 어두운 `373b39`)다.
  라이브러리는 같은 종류(이름)이면서 글자가 똑같은 토큰을 표시한다. 대소문자가 다른 같은 SQL 이름은 표시하지 않는다(라이브러리의
  표시 추가 메서드가 패키지 비공개라 바꾸지 않았다).
- 이름이 가리키는 것은 SWT(`SQLHyperlinkDetector`, `SQLContextInformer`)처럼 모델의 의미 분석으로 찾는다. 이름이 있는 문장 하나를
  파서로 찾아 자동완성과 같은 인자로 의미 모델을 만들고(`SqlCompletionAnalysis.recognizeStatement`), 그 위치 기호의 정의를 따라간다.
  그래서 별칭(`e.salary`)과 열 이름도 실제 열·테이블로 풀린다. 의미 분석이 풀지 못하면(EXEC 뒤 프로시저, DDL) 이름으로 찾는다
  (`SQLSearchUtils.findObjectsByFQN`, 연결의 기본 카탈로그·스키마 기준, 루틴 포함).
- 의미 모델에서 위치로 기호를 찾으려면 문장의 기호를 문맥에 등록해야 한다(SWT `SQLBackgroundParsingJob`이 `registerToken`으로 함).
  `recognizeStatement`가 등록한다. 자동완성 문맥은 이 등록을 읽지 않으므로 자동완성 동작은 바뀌지 않는다.
- 이름 토큰: 일반 이름, 따옴표 이름(`"이름"`, 역따옴표), SQL Server `[이름]`(SQL 토큰 생성기가 전처리기 토큰으로 읽음), 함수 이름.
- 분석은 메타데이터를 읽을 수 있어 작업 스레드에서 한다. 도구 설명은 동기로 글자를 돌려줘야 하므로, 처음 올리면 분석을 시작하고
  결과가 오면 마우스가 그 이름 위에 그대로 있을 때 도구 설명 관리자에게 다시 묻게 한다. 결과는 문서가 바뀔 때까지 기억한다.
- 마우스 올림은 연결을 열지 않는다. Ctrl+클릭은 자동완성(Ctrl+Space)처럼 필요하면 연결한다.
- Ctrl+클릭: 별칭·CTE 같은 문장 안의 이름이면 그 정의를 편집기에서 고른다(SWT와 같음). 데이터베이스 개체면 Object Explorer에서 그 노드를
  펼쳐 고른다. Swing에는 개체 편집기가 없고(`SwingPlatformUI.openEntityEditor`는 아무것도 하지 않음) Properties 창이 Object Explorer 선택을
  따르므로 이것이 가장 가까운 동작이다. 찾지 못하면 상태 줄에 알린다.
- 설명 내용: 이름과 종류(탐색기 노드 종류 이름), 전체 이름, 열이면 형식·NOT NULL·기본값, 테이블·뷰면 열 목록(30개까지), 루틴이면 매개변수,
  주석(설명). 도구 설명은 RSyntaxTextArea의 포커스 가능한 도구 설명(HTML)이고 색은 테마의 도구 설명 색이다.

### 제안 설명 창 (S34)

- SWT 자동완성의 추가 정보 창처럼, 고른 제안의 설명을 목록 옆(오른쪽, 화면이 모자라면 왼쪽)의 두 번째 창에 보인다.
- 데이터베이스 개체 제안은 마우스 올림과 같은 설명이다(`SqlObjectNavigation.describe`). 열·매개변수를 읽을 수 있어 고른 뒤 0.15초 멈추면
  작업 스레드에서 만든다. 키워드 등은 엔진의 설명 글자이고, 설명이 없거나 이름과 같으면 창을 숨긴다.
- 목록 행의 도구 설명(같은 설명 글자)은 설명 창이 대신하므로 지웠다.

### 블록 주석 토글과 글자 도구 (S33, S37)

- SWT 처리기의 동작을 그대로 옮겼다(`ToggleMultiLineCommentHandler`, `Trim*SpacesHandler`, `MorphDelimitedListHandler`,
  `CopySourceCodeHandler`와 변환기, `OpenLinkInWindowHandler`, `ToggleWordWrapHandler`). 처리기는 SWT 번들과 함께 지워져 Swing 쪽에 다시 썼다.
- 블록 주석 기호는 연결 방언의 `getMultiLineComments()`다. 선택이 주석으로 싸여 있으면 벗기고, 아니면 싼다. 줄 전체를 고르면 주석 기호를
  각자 줄에 둔다. 선택이 없으면 커서 줄(들여쓰기 뒤)이다.
- 공백 다듬기: 여러 줄을 고르면 줄마다, 한 줄 안이면 선택 글자, 선택이 없으면 커서 앞 글자(SWT와 같음).
- 구분 목록 바꾸기: 원래 구분자(여러 글자 중 아무거나), 결과 구분자, 따옴표, 줄 바꿈 열, 앞뒤 글자를 묻는다(SWT 기본값 `\t\n,`, `,`, `"`, 80).
  대화상자 값은 앱을 끝낼 때까지 기억한다(SWT는 대화상자 설정 파일). 글자를 바꾸는 확인이라 기본 단추는 Cancel이다.
- 소스 코드로 복사: SWT의 형식 선택 대화상자 대신 형식별 메뉴 항목이다. 옵션은 SWT 확장의 기본값(서식 유지 끔, 줄 구분 공백,
  Delphi `#13#10`, StringBuilder 끔)이다. 대상은 선택(2자 이상), 없으면 커서가 있는 문장, 그것도 없으면 전체다(SWT와 같음).
  HTML은 구문 색을 넣은 `<pre>`이며 RSyntaxTextArea `HtmlUtil`로 만든다. 클립보드에는 HTML 형식과 같은 HTML 글자를 함께 넣는다.
- 웹에서 찾기: 선택한 글자만 SWT와 같은 검색 주소로 기본 브라우저에 연다. 선택이 없으면 아무것도 보내지 않는다.
- 자동 줄 바꿈은 쿼리 창마다이고 저장하지 않는다(SWT와 같음).
- 글자를 바꾸지 않는 명령(자동 줄 바꿈, 웹에서 찾기, 소스 코드로 복사, 접기)은 읽기 전용 편집기(DDL 탭)에서도 켠다.

### 결과 고정 (S3)

- 2026-11-08부터 결과는 결과 집합마다 탭이고, 2026-11-12부터는 위 탭 Grid 안의 아래쪽 자식 탭(Result 1, Result 2…)이다
  ([swing-frontend.md](swing-frontend.md) "결과 탭", "결과 영역 두 단계 탭"). 그래서 고정의 단위도 결과 탭 하나다.
- Query &gt; Pin Results는 Grid에서 고른 결과 탭(Messages 같은 다른 위 탭이 앞이어도 Grid 안에서 고른 탭)을 고정하고 Grid를 앞으로 가져온다. 탭은 그 자리에 남고
  핀 아이콘과 고정 시각(`Result 3 (23:15:58)`)이 붙는다. 다음 실행은 고정하지 않은 결과 탭만 지우고 새 결과(번호는 1부터)를 고정 탭 앞에 낸다.
  SWT의 "탭 고정 → 다음 실행은 새 탭"과 같은 결과다. 이미 고정한 탭이면 상태 줄에 알린다.
- 고정 탭은 닫을 수 있고, 저장하지 않은 변경이 있으면 닫기 전에 묻는다(기본 Cancel). (전에는 지금 결과 표 전부를 고정 탭 하나로 옮기고, 고정 탭이 있을 때만
  Results 안에 탭 줄이 생겼다.)
- 고정 결과도 필터·정렬·다음 페이지·저장이 그대로 된다(결과 표가 자기 문맥과 다시 읽을 쿼리를 가짐). 창을 닫을 때의 저장 질문에 들어가고,
  다시 실행 전의 "버리고 실행" 질문은 지울 결과(고정하지 않은 결과 탭)만 센다. 상태 줄 행 수는 고른 결과 탭의 행 수다.
- 실행 중에는 고정하지 않는다(실행이 아직 결과 탭을 더하는 중).

### 결과 배치 (S44)

- 숨기기(Ctrl+R)는 이미 있었다. 새로 Results를 편집기 오른쪽에 놓기와 최대화를 넣었다.
- 놓는 방향은 전역이고 저장한다(`results.beside`). 저장하는 크기는 아래일 때 높이, 옆일 때 폭이다. 사용자가 방향을 바꾸면 반씩 나눈다.
- 최대화는 문서 쪽에 최소 크기(탭 줄 정도)만 남기고, 다시 누르면 전의 크기로 돌린다. 최대화 상태는 저장하지 않는다(끝낼 때는 전의 크기를 저장).
  Window &gt; Reset Window Layout은 아래·기본 높이로 되돌린다.

## upstream 대비 변경점

- SWT의 Outline은 나무(문장 안 구조), Swing은 문장 목록이다.
- Ctrl+클릭한 데이터베이스 개체는 개체 편집기 대신 Object Explorer에서 고른다. SWT의 F3(개체 열기)은 SSMS의 Find Next라 넣지 않았다.
- 소스 코드로 복사는 미리 보기·옵션 대화상자 없이 기본 옵션의 형식별 메뉴다.
- 결과 고정은 탭 이름 바꾸기·분리를 하지 않는다. "새 탭에서 실행"(SWT `run.statementNew`) 명령은 없다.
- 같은 이름 강조는 대소문자가 같은 이름만 표시한다(위 "같은 이름 강조").

## 확인 (2026-11-08)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → `BUILD SUCCESS`. `python tools/rename/gate.py` → `ALL GATES PASSED`.
- 앱: 빌드 결과물 한 벌을 `CHROMITE_DATA`와 작업 공간을 scratch에 두고 `-nl en`, `-nl ko`로 띄웠다. 입력은 모두 같은 프로세스 안의
  Swing API 호출(EDT)이었고 화면 좌표·전경 창 입력은 쓰지 않았다. Docker 테스트 DB(PostgreSQL 17, SQL Server 2022)에 scratch 개체
  (`p4_ergo` 스키마, `p4_ergo_db` 데이터베이스: 테이블 2개, 한글 열 `이름`, 한글 주석, 함수·프로시저)를 만들고 끝난 뒤 지웠다.
- 접기: PostgreSQL 스크립트에서 블록 주석(2-3), 문장(4-16) 안의 `CASE`(7-10)·괄호(13-16), `DO $$` 문장(20-26) 안의 `BEGIN`(21-25, `END IF` 무시).
  SQL Server 스크립트에서 문장(2-6), `IF EXISTS`(10-19) 안의 `BEGIN`(11-19)·`BEGIN TRY`(13-15)·`BEGIN CATCH`(16-18), `BEGIN TRAN`은 블록 아님.
  모두 접기는 19줄·14줄을 숨기고 모두 펴기는 0줄. 빈 줄·`GO` 문장 경계와 Outline 목록(6개, 5개)이 같고, Outline에서 고르면 그 문장을 선택.
- 마우스 올림: PostgreSQL `employees`(종류 Table, 시스템 열을 뺀 열 4개, 한글 주석), 별칭 `e.salary`(Column, `numeric(12,2) DEFAULT 0`, 열 주석),
  `"이름"`, 함수 `p4_ergo.raise_salary`(매개변수). SQL Server `employees`, `[이름]`, 프로시저 `dbo.raise_salary`.
- Ctrl+클릭: 열·테이블·함수·프로시저는 Object Explorer에서 그 노드를 고름(`postgres.p4_ergo.employees.emp_id` 등). 별칭 `e`·`d`는 FROM의 정의를 선택.
  `avg`는 상태 줄에 "No database object found…"(한국어 "…찾지 못했습니다").
- 제안 설명 창: `p4_ergo.`·`dbo.` 뒤 제안에서 테이블 설명(열, 주석)이 목록 오른쪽 창에 보임.
- 결과 고정: 실행 → 고정 → 다시 실행에서 탭 `[Results] [Pinned 1 (…)]`(한국어 `[결과] [고정 1 (…)]`), 고정 결과는 그대로 남음.
  옆에 놓기(분할 400/800), 최대화(문서 쪽 50px), 되돌리기.
- 글자 도구: 블록 주석 넣기·빼기(선택, 커서 줄), 뒤·앞 공백 다듬기, 자동 줄 바꿈 켜고 끄기, 구분 목록(`('a', 'b', 'c', 'd')`, 12열 줄 바꿈),
  소스 코드 변환(Java, Python, C/C++, Delphi)이 SWT 변환기와 같은 결과. 구분 목록 대화상자(기본 단추 Cancel).
- 밝은·어두운 테마, 영어·한국어 메뉴(Edit &gt; Advanced, View, Query)를 캡처로 확인. 클립보드에 쓰는 "소스 코드로 복사"와 브라우저를 여는
  "웹에서 찾기"는 사용자 PC에 영향이 있어 변환 함수만 확인했다.
- 회귀 테스트는 실행하지 않았다(요청 없음).

## 남긴 것

- Outline의 문장 안 구조(SELECT 목록, FROM 표), 연결 방언 파서 기반 문장 경계.
- 결과 탭 이름 바꾸기·분리, "새 탭에서 실행".
- 소스 코드로 복사의 옵션 대화상자(서식 유지, 줄 구분, StringBuilder).
- 대소문자를 무시하는 같은 이름 강조.
- 자동완성의 변수·명령 제안, 세부 설정(S34의 나머지).

## 변경 이력

- 2026-11-08 최초 작성: S3, S28, S31, S33, S34, S35, S37, S44 (`chromite/p4-editor-ergonomics`)
- 2026-11-08 결과 고정(S3): 결과 집합마다 탭이 되어 고른 결과 탭 하나를 고정(핀 아이콘, 고정 시각), 새 결과는 고정 탭 앞(앱 변경 #7, `chromite/result-tabs`)
- 2026-11-08 Outline 기본 자리를 새 기본 배치(앱 변경 #12)에 맞춤: Properties 위 대신 Query Library 탭 → Properties 탭 → Chromite Agent 위(`chromite/ui-layout`)
- 2026-11-09 블록 주석 토글의 macOS 키 ⌥⌘/(⇧⌘/는 도움말 메뉴 검색, 앱 변경 #24, `chromite/mac-ui`, [shortcuts.md](shortcuts.md))
- 2026-11-12 결과 고정(S3): 결과 탭이 Grid 안의 자식 탭(Result 1…)이 되어 Grid에서 고른 탭을 고정(앱 변경 #54, `chromite/result-area-2`)
- 2026-11-12 결과 최대화 키를 Ctrl+Shift+T에서 Ctrl+Shift+R(⇧⌘R)로: Ctrl+Shift+T는 닫은 탭 다시 열기(앱 변경 #66, `chromite/small-fixes-r2`, [shortcuts.md](shortcuts.md))
