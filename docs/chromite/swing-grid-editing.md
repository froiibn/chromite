# Swing 결과 그리드 편집 (저장하고 커밋, 형식별 편집기, 행·셀 명령, 붙여넣기, LOB, JSON·XML, 고급 복사)

## 목적

SWT 결과 뷰어의 편집 기능 중 Swing 결과 그리드(G단계, [swing-frontend.md](swing-frontend.md) "구현 현황 — G단계")에 없던 것을 옮긴다.
SWT 대조 4순위 B3 작업이고 대상 항목은 다음과 같다([swt-parity/B-editors.md](swt-parity/B-editors.md), [swt-parity/D-db-specific.md](swt-parity/D-db-specific.md)).

| 항목 | 내용 |
|---|---|
| B-R20 | 저장하고 커밋, 변경 스크립트 보기(연쇄 삭제는 남김) |
| B-R21 | 행 복제, 위·아래 행 값 복사, 기본값으로, 셀 되돌리기, 실행 취소·다시 실행 |
| B-R16 | 고급 복사(설정 대화상자), JSON·XML·HTML·SQL INSERT 복사, 여러 칸 붙여넣기, 특수 붙여넣기 |
| B-R14 | 날짜·시각 선택기, 불리언 편집기, LOB 파일에서 불러오기·파일로 저장 |
| B-O6, B-O7 | JSON·XML 값 편집기(정리·검사) |
| D-P28, D-M18 | ENUM 열 콤보 편집(PostgreSQL ENUM 형식, MySQL/MariaDB `ENUM`) |

사용자 규칙 "트랜잭션 안전"(묻지 않고 커밋·롤백하지 않음)과 "변경을 실행하는 확인은 Cancel이 기본 단추"를 따른다.

## 구조

모두 `com.seizia.chromite.ui.swing` 번들이다. 새 번들·새 의존은 없다(PostgreSQL·MySQL 모델 번들 의존은 원래 있었다).

| 클래스 | 새/변경 | 역할 |
|---|---|---|
| `ResultSetGrid` | 변경 | 저장하고 커밋 단추·메뉴, 변경 미리 보기, 행·셀 명령과 단축키, 붙여넣기, 불리언·ENUM 편집기(`ChoiceCellEditor`), 글자 편집기의 날짜 선택 단추 |
| `ResultSetEditModel` | 변경 | LOB 열 편집(읽지 않은 값), ENUM 값, 실행 취소 기록, 행 복제, 위·아래 값 복사, 기본값, 셀 되돌리기 |
| `ResultSetEditPersister` | 변경 | 저장하고 커밋(같은 Job에서 커밋), 커밋 오류를 따로 넘김 |
| `ResultSetGridReceiver` | 변경 | 편집할 수 있는 결과의 ENUM 값을 Job 스레드에서 읽어 넘김, LOB 값 객체로 받은 열을 알림 |
| `ResultSetViewerPanel` | 변경 | LOB 칸 F2·두 번 누르기 → 값 패널, 파일에서 불러오기·파일로 저장(메뉴, 값 패널 단추), 레코드 보기는 LOB를 칸에서 고치지 않음 |
| `ResultSetValuePanel` | 변경 | 글자 LOB 편집, JSON·XML 정리(Format)와 검사, 파일 단추 자리 |
| `ResultSetCellContent` | 변경 | 칸 값 전체를 파일로 쓰기, 글자 내용 판단 |
| `ResultSetCopyFormats` | 변경 | JSON·XML·HTML·SQL INSERT 형식, 고급 복사 대화상자, 결과 표 복사의 글자·HTML 표와 전송 처리기(2026-11-28, "엑셀로 붙여넣는 복사") |
| `ResultSetDateTimePicker` | 새 | 날짜·시각 선택 대화상자 |
| `ResultSetEnumValues` | 새 | ENUM 열의 값(PostgreSQL·MySQL 모델에서 읽음) |
| `ResultSetPaste` | 새 | 클립보드 글자 나누기, 특수 붙여넣기 설정 |
| `ResultSetLobFiles` | 새 | LOB 파일 대화상자(마지막 폴더 기억), 파일 읽기·쓰기 |
| `internal/GridEditMessages` (+ `.properties`, `_ko.properties`) | 새 | 이 기능의 화면 문구(영어·한국어) |
| `internal/EditorMessages*.properties` | 변경 | 수동 커밋 안내 문구가 새 명령을 가리킴 |

## 설계 결정과 이유

### 저장하고 커밋 (B-R20)

SWT: `ResultSetHandlerMain` `CMD_APPLY_AND_COMMIT_CHANGES`(저장 뒤 `ConnectionCommands.CMD_COMMIT` 실행).

- **수동 커밋 연결의 Save는 커밋하지 않는다(바뀌지 않음).** 저장 문장은 열린 트랜잭션에 들어가고 Messages가 커밋 방법(저장하고 커밋, 쿼리 > 커밋)을 알린다.
  자동 커밋 연결의 Save는 G단계처럼 한 트랜잭션으로 저장하고 커밋한다(사용자가 고른 연결 방식이 자동 커밋이므로).
- **저장하고 커밋**은 수동 커밋 연결에서만 보인다(표 위 단추, 오른쪽 클릭 메뉴). 연결 방식은 쿼리 관리자 기록(`QMUtils.getTransactionState(...).isTransactionMode()`, 드라이버 호출 없음)으로 판단한다.
- 커밋은 트랜잭션 전체에 적용된다. 그래서 트랜잭션에 그 전 데이터 변경(같은 쿼리 창의 다른 문장, 이전 저장)이 있으면
  "열린 트랜잭션에 이 세션에서 먼저 한 변경 N개가 있습니다… 함께 커밋됩니다"를 먼저 묻는다. 기본 단추는 취소다. 그 전 변경이 없으면 묻지 않는다(명령 자체가 답).
- **같은 Job에서 커밋한다.** 저장 Job(`DataUpdaterJob`, 문맥의 `DataSourceJob`)이 끝나기 전에 Query > Commit 경로(`IdleTransactionMonitor.endTransaction`)를 부르면
  "문맥에서 Job이 실행 중"이라 아무것도 하지 않는다. 그래서 저장 문장이 모두 성공한 뒤 같은 Job에서 `DBCTransactionManager.commit`을 부른다.
  저장 문장이 실패하면 G단계처럼 저장점까지 롤백하고 커밋하지 않는다.
- **커밋이 실패하면** 저장 문장은 열린 트랜잭션에 남는다. 표는 그 행들을 저장된 것으로 반영하고(바뀐 내용으로 두면 다시 저장할 때 같은 INSERT가 두 번 실행됨),
  Messages에 "열린 트랜잭션에 저장했지만 커밋하지 못했습니다. 쿼리 > 커밋 또는 롤백으로 끝내세요"와 오류를 빨간색으로 쓴다.
- **변경 미리 보기(SWT "Generate Script")**: 설정·연결 유형과 관계없이 저장할 문장을 #98의 미리 보기 대화상자(`ScriptPreviewDialog.confirmSave`, 기본 단추 Cancel)로 보여 주고
  Save를 누르면 저장한다. #98의 저장 전 미리 보기는 그대로다(같은 경로).
- **연쇄 삭제는 옮기지 않았다.** SWT `ResultSetPersister.prepareDeleteCascade`(기준 커밋 `263b8ddb45`)는 깊은 연쇄 인자를 쓰지 않고, 참조하는 테이블의 외래 키 열을
  지울 행의 키 열과 이름으로만 맞춘다(`DBDAttributeValue.getAttributeValue`가 이름 비교). 이름이 다른 외래 키는 건너뛴다.
  다른 테이블의 행을 화면에 보이지 않게 지우는 기능이라, 규칙을 고치고 실제 DB로 확인하기 전에는 넣지 않는다("남긴 것").

### 행·셀 명령 (B-R21)

SWT 명령과 단축키(`ui.editors.data/plugin.xml`, 기준 커밋 `263b8ddb45`)를 따랐다.

| 명령 | 단축키 | 동작 |
|---|---|---|
| 행 복제 | Ctrl+Alt+Insert | 고른 행의 사본을 아래에 새 행으로 더함. 값은 값 처리기로 복사(`getValueFromObject(…, copy=true)`), 의사·자동 생성 열은 새 행 값. 읽지 않은 LOB는 비워 두고 몇 개인지 상태 줄에 알림 |
| 위 행 값 복사 / 아래 행 값 복사 | Ctrl+D / Ctrl+Alt+D (macOS ⌘D / ⇧⌘D) | SWT `copyRowValues`와 같은 규칙: 한 행이면 이웃 행, 이어진 여러 행이면 그 묶음의 첫(위)·마지막(아래) 행의 값. 고른 열만 |
| 기본값으로 설정 | Ctrl+Backspace | 값 처리기의 기본값(`DBDValueDefaultGenerator`: 날짜는 지금, 숫자 0, 불리언 false, 문자열 빈 값). DB의 DEFAULT가 아니다(SWT와 같음) |
| 셀 되돌리기 | (메뉴만) | DB에서 읽은 값으로. SWT의 Esc는 표에서 셀 편집 취소 키라 쓰지 않았다 |
| 실행 취소 / 다시 실행 | Ctrl+Z / Ctrl+Y (macOS ⌘Z / ⇧⌘Z) | 셀 값 변경만(SWT `ResultSetUndoRedoManager`와 같음). 한 동작(붙여넣기, NULL로, 위 행 값 복사…)은 한 번에. 기록 200단계(SWT 기본). 저장·취소하면 기록을 지움 |

- 행 추가·삭제는 실행 취소 대상이 아니다(SWT와 같음). 지운 새 행이나 삭제 표시된 행의 변경은 되돌릴 때 건너뛴다.

### 붙여넣기 (B-R16)

SWT: `ResultSetHandlerPasteSpecial`, `ResultSetPasteSettings`.

- Ctrl+V: 클립보드 글자를 고른 셀부터 넣는다. 줄은 행, 탭은 칸이다(스프레드시트·결과 표 복사 형식). 엑셀처럼 큰따옴표로 감싼 값은 탭·줄 바꿈을 품을 수 있고 `""`는 따옴표 하나다.
  값 하나를 여러 셀이 골라진 곳에 붙이면 고른 셀 모두에 넣는다. 일반 붙여넣기는 SWT 기본처럼 있는 행에만 넣는다.
- 특수 붙여넣기(Ctrl+Shift+V): 행이 모자라면 새 행 추가(기본 켬), 따옴표를 글자로 취급, NULL로 넣을 글자. 설정은 이 실행 동안 기억한다.
- 값은 셀 편집기처럼 열의 값 처리기가 만든다. 읽기 전용 셀, 표 밖의 칸, 열이 거부한 값은 건너뛰고 상태 줄에 "값 N개를 붙여 넣고 M개는 건너뜀: 이유"를 쓴다.
  LOB 셀은 글자 LOB(JSON·XML·CLOB)일 때만 받는다. 붙여넣기 하나는 실행 취소 한 단계다.
- 붙여넣기는 표만 바꾸고 DB는 저장할 때 바뀐다. 그래서 설정 대화상자는 OK가 기본 단추다.

### 고급 복사와 복사 형식 (B-R16)

- Copy As 메뉴에 JSON(객체 배열, 숫자·불리언은 JSON 값), XML(`<rows><row><column name="…">`), HTML 표, SQL INSERT를 더했다.
  SWT는 데이터 전송 처리기를 다시 실행했지만 Swing은 표가 가진 값(숫자·불리언·날짜 원래 값과 표시 문자열)으로 바로 만든다: 쿼리를 다시 실행하지 않고, 저장하지 않은 변경도 복사된다.
  LOB 값은 표에 보이는 글자다(저장 미리 보기와 같은 한계). 2026-11-22부터 칸 글자가 값의 일부인 LOB는 DB 없이 얻을 수 있으면 전문이다
  ([swing-result-viewer.md](swing-result-viewer.md) "긴 LOB 칸" "복사·내보내기").
- SQL INSERT: 테이블은 결과의 단일 원본 테이블(`DBExecUtils.detectSingleSourceTable`), 없으면 `table`. 열 이름은 방언의 따옴표, 날짜·불리언은 `SQLUtils.convertValueToSQL`, 글자는 `SQLUtils.quoteString`.
- 고급 복사…(Copy As 메뉴 끝): 열 이름, 행 번호, 구분자(`\t`, `,`, `;`, `|` 또는 입력), 따옴표(필요할 때·항상·쓰지 않음), NULL 글자. 설정은 이 실행 동안 기억한다.
  SWT의 Ctrl+Shift+C는 Swing에서 이미 Copy with Headers라 고급 복사에는 단축키를 두지 않았다.

### 엑셀로 붙여넣는 복사 (2026-11-28, 앱 변경 #189)

사용자 보고: 쿼리 결과를 복사해 엑셀에 붙여넣으면 개행 문자 때문에 칸이 깨진다. 사용자 결정: 기본 복사에 고급 복사의 "필요할 때" 따옴표를 넣는다.

- 원인: Ctrl+C·편집 ▸ 복사·메뉴 Copy는 Swing 기본 표 전송 처리기(`BasicTableUI.TableTransferHandler`)였다. 글자(`text/plain`)는 값을 따옴표 없이 탭·줄로 이어
  줄바꿈·탭·`"`가 든 값이 여러 칸·여러 행으로 나뉘었고(앱 자신의 붙여넣기도 2행을 3줄로 읽음), HTML 표(`text/html`)는 값을 이스케이프하지 않아 엑셀(HTML을 먼저 읽음)에서
  줄바꿈이 공백이 되고 `x<b>&y`가 `x&y`가 되었다. 머리글 포함 복사도 같은 글자(따옴표 없음)였다.
- 지금: 결과 표의 전송 처리기는 `ResultSetCopyFormats.TableCopyHandler`다. 복사와 머리글 포함 복사는 `ResultSetCopyFormats.copySelection`이 두 형식을 만든다.
  - 글자: 고급 복사와 같은 코드(`appendDelimited`)에 탭, 따옴표 "필요할 때", NULL 글자 `NULL`(전과 같음)을 넘긴다. 탭·줄바꿈(`\n`, `\r`)·`"`가 든 값만 큰따옴표로 감싸고
    안의 `"`는 두 번 쓴다. 다른 값, 열 순서, 마지막 줄 끝에 줄바꿈 없음(칸 하나는 값만)은 전과 같다. 머리글 줄도 같은 규칙.
  - HTML 표: 모든 값을 이스케이프(`& < > "`)하고 줄바꿈(`\r\n`, `\n`, `\r`)은 `<br style="mso-data-placement:same-cell">`로 쓴다. 그냥 `<br>`은 엑셀이 다음 행으로
    넘기고, 날 줄바꿈은 공백이 된다(사실 확인 `D:\Tools\_scratch\v189\run\excel-html.log`). 머리글은 `<th>`. 전송 객체는 SQL 편집기의 HTML 복사와 같은
    `SqlTextTools.HtmlSelection`(HTML과 글자 두 형식).
  - 칸 글자가 값의 일부인 LOB는 전처럼 DB 없이 얻을 수 있는 전문(`getCopyData`)이다. 복사하는 동안 모델이 전문을 주던 `copyTexts`는 지웠다.
- 같은 원인의 다른 표: 결과 값을 보이는 레코드 보기, 집계 패널, 배열·구조체·hstore 대화상자의 항목 표, 참조 값 고르기 표도 Swing 기본 표 복사였다.
  같은 처리기(`TableCopyHandler`, 표의 `getValueAt`, Swing처럼 행·열을 고르지 않는 표는 모든 행·열, null 칸은 빈 글자)를 단다.
  결과 값이 아닌 표(쿼리 기록, 관리 표 `AdminGridTable` 등)는 바꾸지 않았다. Query Manager의 Ctrl+C는 원래 문장을 복사한다.
- 엑셀에서 값 종류별(HTML로 읽음, 전 → 후): 줄바꿈 LF·CRLF `a b` → 한 칸 `a`↵`b`, `x<b>&y` → `x&y`에서 `x<b>&y`로. 탭 `a b`(공백), 앞뒤 공백 잘림(`pad`),
  `say "hi"`, `"lead`, NULL·`NULL` 글자, 숫자(`1,234,567.891` → 1234567.891, `#,##0.00`), 큰 수(1.23456789012345E+19), 앞의 0(`007` → 7), 날짜(`yyyy-mm-dd`),
  시각(`mm:ss.0` 형식)은 전과 같다(엑셀의 HTML 읽기 그대로).
- Windows의 Java 클립보드는 넣는 순간 모든 형식을 만든다(`sun.awt.windows.WClipboard.setContentsNative`: 지연 렌더링을 쓰지 않고 형식마다
  `translateTransferable` → `publishClipboardData`, JBR 25 `src.zip`). 그래서 형식을 나중에 만들게 해도 줄지 않는다. 형식은 HTML Format, UNICODE TEXT, TEXT,
  UniformResourceLocator(JDK가 글자를 이 형식으로도 넣음)이고, 전에는 Swing 전송 객체의 JVM 지역 객체 형식(글자 한 벌 더)도 있었다.
- 측정(PostgreSQL 17, `generate_series`로 만든 16열: 줄바꿈 10%·CRLF 5%·탭 14%·`"` 20%·NULL 33%·숫자·날짜·시각·md5·불리언, 전체 선택, 앱 `-Xmx1024m`.
  같은 PC에서 다른 빌드가 돌아 시간은 흔들림이 크다. 힙 최고는 1ms마다 본 사용량이라 거두지 않은 쓰레기를 포함):

  | | 10만 행 전 | 10만 행 후 | 30만 행 전 | 30만 행 후 |
  |---|---|---|---|---|
  | 전송 처리기(첫 복사) | 201 ms | 373 ms | 929 ms | 1,664 ms |
  | 전송 처리기(되풀이, 전은 같은 모델의 Swing 처리기) | 132~245 ms | 279~319 ms | 521~566 ms | 973~1,104 ms |
  | 클립보드 형식 만들기 | 1,105 ms | 1,176 ms | 4,660 ms | 5,199 ms |
  | Ctrl+C 합계(EDT가 멈춘 시간) | 1,306 ms | 1,550 ms | 5,590 ms | 6,863 ms |
  | text/plain · text/html 글자 수 | 16.1M · 34.8M | 16.2M · 35.8M | 49.0M · 105.1M | 49.4M · 108.3M |
  | 클립보드 형식 바이트 | 117.5 MB (5개) | 103.0 MB (4개) | 356.9 MB (5개) | 312.7 MB (4개) |
  | 힙: 결과 / 복사본이 잡은 것 | 185 / +50 MB | 185 / +51 MB | 457 / +138 MB | 457 / +142 MB |
  | 힙 최고: 처리기 / 형식 만들기 | +159 / +629 MB | +179 / +586 MB | +356 / +424 MB | +459 / +362 MB |
  | 할당: 처리기 / 형식 만들기 | 173 / 617 MB | 183 / 580 MB | 629 / 1,901 MB | 665 / 1,757 MB |

  - 글자만 만들면(같은 코드, HTML 없음): 10만 행 처리기 151~162 ms(복사용 행 30~45 ms 포함) + 형식 3개 456~617 ms(65.4 MB), 30만 행 712 ms + 1,619 ms(199.0 MB).
    글자 만들기는 전의 처리기(글자와 HTML)보다 느리지 않고, 처리기에서 늘어난 시간은 HTML 표(이스케이프)다.
  - 지금도 무겁다: 30만 행 Ctrl+C는 전에도 5.6초 동안 화면이 멈추고 클립보드에 357 MB를 넣으며, 힙 사용량이 1,019 MB(최대 1,024 MB)까지 올랐다.
  - 엑셀(직접 띄운 인스턴스에서 파일로 연 대용 측정, `Workbooks.Open`·`OpenText`): HTML 10만 행 11~23초(전)·16~20초(후), 30만 행 42초(전)·48초(후).
    따옴표 글자 10만 행 6~7초, 30만 행 15~19초. 파일의 글자 읽기(`OpenText`)는 따옴표 안의 탭·`"`·앞뒤 공백은 지키지만 따옴표 안의 줄바꿈에서 행을 나눴다
    (10만 행이 115,000행). 그래서 따옴표 글자만으로 엑셀 칸이 지켜지는지는 확인하지 못했고(실제 붙여넣기는 시스템 클립보드를 쓰지 않아 시험하지 못함), HTML을 남겼다.
- 남은 선택(사용자 결정 대기): HTML을 빼면 Ctrl+C가 30만 행에서 6.9초 → 2.3초, 클립보드 313 MB → 199 MB가 되지만, 엑셀의 실제 붙여넣기에서 따옴표 안 줄바꿈이
  한 칸에 남는지 확인해야 하고, Word·메일에 표로 붙던 것이 글자가 된다.

### 형식별 편집기 (B-R14, D-P28, D-M18)

- **불리언:** 두 번 누르기·F2로 목록(true, false, NULL)을 연다. 글자는 값 처리기의 편집 형식이다. NOT NULL 열은 NULL이 없다. 클릭이나 Enter로 고르면 편집이 끝난다.
  SWT는 체크 상자·콤보(`BooleanInlineEditor`)였다. 그리드 셀 안의 체크 상자는 NULL을 나타낼 수 없어 목록으로 했다.
  모델 자료형이 `BOOLEAN`인 열(PostgreSQL `boolean`, Oracle 23ai `BOOLEAN`)이 대상이다. SQL Server `bit`과 MariaDB `BOOLEAN`(`TINYINT(1)`)은
  모델이 숫자 열로 다루므로(`SQLServerDataType`의 `bit` → `NUMERIC`) SWT처럼 0·1 글자로 고친다.
- **ENUM:** PostgreSQL ENUM 형식(`PostgreDataType.getEnumValues`, 형식 범주 E)과 MySQL/MariaDB `ENUM` 열(`MySQLTableColumn.getEnumValues`)은 허용 값 목록으로 고친다.
  SWT의 `PostgreEnumValueManager`, `MySQLEnumValueManager`와 같은 모델 값이다. PostgreSQL 값 읽기는 형식 캐시가 비면 질의하므로 결과를 받는 Job 스레드에서 읽어 편집 모델에 넘긴다.
  MySQL `SET`은 여러 값이라 콤보로 다루지 않는다(확인란 고르기는 [swing-value-editors.md](swing-value-editors.md)).
- **날짜·시각:** 글자 편집기 오른쪽의 달력 단추나 Alt+↓로 선택기를 연다. 월 달력(주의 첫 요일·요일 이름은 OS 지역), 시:분:초, 오늘/지금, OK, Cancel.
  `DATE`는 날짜만, `TIME`은 시각만, 그 밖은 둘 다다. 고른 값은 `java.sql.Date/Time/Timestamp`로 열의 값 처리기에 넘긴다(SWT 달력 방식과 같음).
  값 처리기가 `Date`를 다루지 않는 열(예: SQL Server `datetimeoffset` 처리기)과 원래 형식 보이기 설정의 글자 값은 단추가 없고 글자로 고친다. 시각을 바꾸지 않으면 소수 초는 남는다.
  선택기는 모달 대화상자다: 같은 창의 팝업이면 포커스가 셀 편집기를 떠날 때 편집이 끝난다(`terminateEditOnFocusLost`).

### LOB 편집과 파일 (B-R14, B-O6, B-O7)

- **LOB 열도 편집한다.** G단계는 LOB를 읽기 전용으로 두었다. 수신기가 LOB 값 객체를 표시 문자열로 바꾸고 놓아 주므로 행에 값이 없기 때문이다.
  이제 값 전체를 바꾸는 방식으로 편집한다. 대상 열(`ResultSetEditModel.isWholeValueColumn`):
  - 자료형 `CONTENT`(JSON, XML, CLOB, BLOB 등)와 `BINARY`(PostgreSQL bytea 등),
  - 값 처리기가 LOB 값을 만드는 열,
  - 드라이버가 LOB 값으로 준 문자열 열(MariaDB `LONGTEXT`, MariaDB의 `JSON`은 `LONGTEXT`다). 이 열은 테이블 메타데이터에 묶은 뒤의 처리기가 문자열 처리기라
    자료형만으로는 알 수 없어서, 수신기가 값 객체로 받은 열을 편집 모델에 알린다(`lobColumns`).
  편집 방식:
  - 행에 값이 없는 LOB 셀은 "읽지 않은 값"으로 두고(`ResultSetEditModel.hasValue`), 원래 값을 모르므로 "처음 값으로 돌아감" 판단을 하지 않는다.
    되돌리면 다시 읽지 않은 상태가 되고 값 패널은 받은 내용(`ResultSetCellContent`)을 보인다.
  - 저장 엔진은 바뀐 열의 지금 값과 키 열만 쓰므로 읽지 않은 값이 문장에 들어가지 않는다. 값은 열의 값 처리기가 만든 값 객체
    (PostgreSQL json은 `PostgreContentJSON`, 그 밖은 `JDBCContentChars`·`JDBCContentBytes`·`JDBCContentXML`)이고 바인딩도 그 처리기가 한다.
  - 저장한 LOB 값은 행이 계속 알고 있다(저장 뒤 다시 읽기가 LOB 값을 놓아 주어도). 그래야 값 패널이 처음 받은 내용이 아니라 저장한 값을 보인다.
- 그리드 셀과 레코드 보기에서는 LOB를 글자로 고치지 않는다(시작 글자가 비어 값을 지울 수 있음). F2·두 번 누르기·Edit Cell은 값 패널을 연다.
  2026-11-22부터 값 전체를 보이지 않는 LOB 칸은 읽기 전용 결과에서도 링크로 그려지고, 칸 글자를 누르거나 두 번 누르거나 F2를 누르면 값 패널을 연다
  ([swing-result-viewer.md](swing-result-viewer.md) "긴 LOB 칸"). 패널·파일로 바꾼 칸은 보통 칸으로 그린다.
- **값 패널:** 글자 LOB(JSON, XML, CLOB, 텍스트)는 패널에서 고치고 Apply(Ctrl+Enter)로 넣는다. 값 패널이 1,000,000자까지만 읽은 LOB는 고치면 뒷부분을 잃으므로 고치지 않는다(파일로 바꿈).
  이진 LOB는 패널에서 고치지 않는다.
- **JSON·XML 편집기(SWT `JSONPanelEditor`, `XMLPanelEditor`):** Format(Ctrl+Shift+F)이 지금 글자를 들여쓴다(보기와 같은 Gson·JDK 변환, XXE 차단 그대로).
  고치는 동안 0.4초 뒤 "올바른 JSON"·"올바른 XML" 또는 이유를 아래 줄에 보인다. JSON·XML 형식 열(형식 이름이나 LOB 내용 형식)은 읽히지 않는 글자를 Apply하지 않는다.
  NULL인 JSON·XML 셀은 처음부터 그 편집기로 연다. 다른 글자 열은 보기 방식이 JSON·XML이어도 검사 없이 넣는다.
- **파일에서 불러오기·파일로 저장(SWT `ContentValueManager.loadFromFile`/`saveToFile`):** 오른쪽 클릭 메뉴와 값 패널 아래 단추.
  - 대화상자는 사용자가 마지막으로 고른 폴더에서 열고 그 폴더를 Swing 설정 저장소(`resultset.lob.lastFolder`)에 남긴다. 처음에는 JDK의 사용자 기본 폴더(Windows: 문서)다. 고정 경로를 쓰지 않는다.
  - 글자 LOB는 UTF-8(읽을 때 BOM 제거), 이진 LOB는 바이트 그대로. 불러온 파일은 메모리로 읽어 값 처리기가 값을 만든다(SWT는 파일을 저장 때까지 열어 두는 저장소를 썼지만 그 사이 파일이 바뀌면 저장 값이 달라짐).
  - 저장은 값 전체다: 값 패널이 잘라 읽은 LOB도 DB 값에서 끝까지 읽는다. 있는 파일은 덮어쓰기 전에 묻고 기본 단추는 Cancel이다.
  - 저장은 읽기 전용 결과에서도 된다. 불러오기는 편집할 수 있는 LOB 셀에서만.

### 오른쪽 클릭 메뉴에 보이는 편집 명령 (2026-11-16, 앱 변경 #137)

앱의 오른쪽 클릭 메뉴 규칙([context-menus.md](context-menus.md) "결과 그리드")을 따른다. 사용자 결정: "결과 그리드도 고친다."

- 편집 명령(셀 편집 … 변경 취소)은 결과가 종류로 편집할 수 있을 때(`ResultSetEditModel.appliesEditing`: 테이블 하나, 완전한 고유 키, 편집할 수 있는 열,
  데이터를 바꿀 수 있는 DB) 보인다. 그 결과를 읽을 때 연결이 편집을 막았으면(닫힘, 읽기 전용 연결, 데이터 편집 권한 없음) 편집 모델이 없으므로 명령이
  꺼진 채 보이고 키는 묶지 않는다(전처럼 표의 기본 키와 창의 단축키로 간다). 연결을 읽기 쓰기로 바꾼 뒤에는 다시 실행해야 켜진다(편집할 수 있는지는 읽을 때 정함).
- 행 추가·복제는 테이블이 INSERT를, 행 삭제는 DELETE를 받을 때(`DBSDataManipulator` 기능)만 보인다. 전에는 꺼진 채 보였다.
- 파일에서 불러오기는 편집할 수 있는 LOB·이진 열, 파일로 저장은 LOB·이진 열, 16진수로 편집은 이진 열에서만 보인다. NULL 값·지운 행·저장 중이면 꺼진다.
- 저장하고 커밋은 자동 커밋 모드에서 숨는다(예외: 커밋 방식은 바뀔 수 있는 "지금"이지만 그 모드에서는 Save가 커밋하므로 쓸모가 없음).
- 단축키와 Save·Cancel 단추의 켜짐은 바뀌지 않았다(숨은 명령은 전에도 꺼져 있었음).

## upstream 대비 변경점

- upstream(SWT) 코드와 모델은 바꾸지 않았다. Swing 번들만 바뀌었다.
- SWT와 다른 동작:
  - 불리언은 체크 상자 대신 목록(NULL 포함).
  - 복사 형식은 데이터 전송 처리기 대신 표의 값으로 만듦.
  - 셀 되돌리기에 Esc를 쓰지 않음.
  - 저장하고 커밋은 그 전 변경이 트랜잭션에 있으면 먼저 묻고, 같은 Job에서 커밋.
  - 불러온 파일은 저장 때까지 열어 두지 않고 메모리로 읽음.
  - 연쇄 삭제 없음.

## 확인 (2026-11-08)

- 빌드: `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` → `BUILD SUCCESS`, `python tools/rename/gate.py` → `ALL GATES PASSED`.
- 실행: 빌드한 Swing 제품을 `D:\Tools\_scratch\chromite-p4-grid-editing`에 복사해 `CHROMITE_DATA`와 작업 공간을 그 폴더에 두고 열었다.
  화면 조작은 실행 중인 앱 안의 시험용 Java 에이전트(제품에 들어가지 않음)가 Swing API로 했다(OS 입력·앞 창 없음). DB 값은 앱 밖의 다른 세션
  (`docker exec`의 psql·sqlcmd·mariadb·sqlplus)으로 확인했다. 시험 객체는 공용 Docker DB에 만들고(PostgreSQL 스키마 `p4ge`, SQL Server DB `chr_p4ge`,
  MariaDB DB `chr_p4ge`, Oracle `SYSTEM.CHR_P4GE_ITEMS`) 끝난 뒤 지웠다. 확인 중 고친 것은 패치한 같은 번들로 다시 확인한 뒤 마지막에 전체 빌드했다.
- **자동 커밋 연결:**
  - PostgreSQL: ENUM 콤보(`sad·ok·happy`), 불리언 목록(NULL 포함), DATE·TIMESTAMP 선택기 → 저장 → DB `born 2026-01-20`, `seen 2026-01-21 10:20:30.123456`(시각을 바꾸지 않아 소수 초 남음),
    `mood sad`, `flag NULL`. 값 패널 jsonb: 잘못된 JSON을 고치는 중 "올바른 JSON이 아님: …", Apply 거부 → 정리(Format) → Apply. xml: 잘못된 XML 표시 → 고쳐 Apply.
    파일에서 불러오기: BOM 붙은 UTF-8 JSON(한글) → jsonb, 이진 파일 → bytea. 파일로 저장: bytea 8바이트, jsonb 글자, 저장 뒤 bytea 17바이트가 원본과 같음.
    위 행 값 복사 → 실행 취소 → 다시 실행 → 셀 되돌리기, 기본값으로(오늘 날짜), 행 복제(LOB 1개 건너뜀 안내, 자동 증가 키는 DB가 4를 줌),
    붙여넣기(두 줄 → 두 행), 특수 붙여넣기(새 행 2개, 세 줄), 붙여넣기 거부 값("abc" → id) → "값 1개를 붙여 넣고 1개는 건너뜀: …".
    Copy As JSON·XML·HTML·SQL INSERT(`INSERT INTO p4ge.items (id, "name", flag, born) VALUES (1, 'alpha', true, '2026-01-20');`), 고급 복사(탭·머리글).
    변경 미리 보기(INSERT 3, UPDATE 3, 기본 단추 취소) → 저장 → DB 6행이 모두 기대 값.
  - SQL Server: 날짜 선택기(오늘, 22일), `datetime2` 소수 7자리 유지(`10:20:30.1234567`), xml 값 패널(뿌리 둘인 조각 정리·Apply), varbinary 파일 불러오기·저장, 행 복제,
    변경 미리 보기 → 저장 → DB 확인. `bit`은 숫자 열(0·1 글자).
  - MariaDB: ENUM 콤보, DATE·DATETIME(3) 선택기, BLOB 파일 불러오기·저장, 붙여넣기 → 실행 취소 → 다시 실행 → 저장 → DB 확인. `JSON`(LONGTEXT) 열은 처음에 편집되지 않아
    원인(묶은 뒤 처리기가 문자열 처리기)을 고쳤다(`lobColumns`), 아래 수동 커밋에서 확인.
  - Oracle: `BOOLEAN` 목록, DATE(시각 포함)·TIMESTAMP 선택기, CLOB JSON을 값 패널에서 정리·Apply, CLOB 글(한글), BLOB 파일 불러오기·저장(로케이터 LOB를 끝까지 읽어 씀) → 저장 → DB 확인
    (`seen …10:20:30.123456`).
- **수동 커밋 연결**(작업 공간 사본의 연결 4개에 `bootstrap.autocommit = false`):
  - 표 위에 "저장하고 커밋" 단추가 보임(자동 커밋 연결에서는 숨김).
  - PostgreSQL: Save → 다른 세션에서 이전 값(커밋 안 됨) → 쿼리 > 롤백 → 이전 값. 저장하고 커밋 → 다른 세션에서 새 값.
    같은 창에서 `UPDATE … WHERE id = 2`를 실행해 트랜잭션을 연 뒤 표를 고쳐 저장하고 커밋 → "열린 트랜잭션에 이 세션에서 먼저 한 변경 1개가 있습니다…"(기본 단추 취소)
    → 취소: 아무것도 저장·커밋되지 않고 변경이 표에 남음 → 다시 저장하고 커밋 → 확인: UPDATE와 표의 변경이 함께 커밋.
  - SQL Server: Save 뒤 다른 세션의 읽기가 잠금 대기로 끝남(`Lock request time out period exceeded`, 커밋 안 됨) → 다른 행을 고쳐 저장하고 커밋 →
    그 전 저장 1개를 묻고 함께 커밋 → 다른 세션에서 두 값.
  - MariaDB: JSON(LONGTEXT) 셀 두 번 누르기 → 값 패널, 잘못된 JSON 표시, Apply → Save → 다른 세션에서 이전 값 → 롤백 → 이전 값 → ENUM과 JSON을 고쳐 저장하고 커밋 → 새 값.
  - Oracle: Save → 다른 세션에서 이전 값 → 롤백 → 이전 값 → 이름과 CLOB을 고쳐 저장하고 커밋 → 새 값.
- **언어·테마:** 한국어(OS 언어)와 영어(`-nl en`), 라이트·다크에서 오른쪽 클릭 메뉴(단축키 표시), 저장하고 커밋 단추, ENUM 목록, 날짜 선택기(주의 첫 요일·달 이름이 언어를 따름),
  JSON 편집기(검사 줄, Format), 특수 붙여넣기·고급 복사·변경 미리 보기 대화상자를 화면으로 확인했다. 값 패널 아래 단추가 좁은 패널에서 잘려 보여 정보 줄과 단추 줄을 나누고
  파일 단추를 아이콘으로 바꿨다. 대화상자 제목의 말줄임표를 뺐다.
- 확인하지 못한 것: macOS, Altibase 등 다른 DB, 1,000,000자보다 큰 LOB의 편집 거부(코드 경로만), 연결이 끊긴 상태의 저장하고 커밋 실패 경로(커밋 오류 알림).
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 결과 그리드 편집·저장(G단계), 저장 전 미리 보기(#98), 값 패널, 레코드 보기, Copy As,
  정렬·다음 페이지 뒤 그리드 다시 만들기(편집 모델을 다시 만듦).

### 확인하면서 고친 것

- PostgreSQL bytea(`BINARY` 자료형)가 LOB로 잡히지 않아 파일에서 불러오기가 꺼져 있었다 → `BINARY`도 값 전체 편집 열.
- 편집할 수 없는 LOB 열(읽기 전용 열)에서 값 패널이 받은 내용 대신 빈 값을 보이던 것 → "읽지 않은 값"을 편집 여부와 관계없이 수신기가 놓아 준 열 모두로 판단.
- 다시 만든 그리드(정렬·다음 페이지)에 읽을 수 있는 이진 값(byte[])이 빠지던 것 → LOB 값 객체만 패널 내용으로 옮김.
- MariaDB JSON(LONGTEXT)이 편집되지 않던 것 → 위 `lobColumns`.
- 값 패널 아래 단추가 좁은 패널에서 잘리던 것, 대화상자 제목의 말줄임표.

## 남긴 것

- 연쇄 삭제(Delete cascade, Deep cascade), 커서 보기. 참조 값 고르기, 배열·구조체 편집기, 외부 편집기, UUID 만들기, MySQL `SET` 다중 선택,
  hstore·bit string 편집기, 16진수 편집은 2026-11-08 [swing-value-editors.md](swing-value-editors.md)에서 넣었다.
- 수동 커밋 연결에서 Save 뒤 쿼리 > 롤백을 하면 DB는 되돌아가지만 표는 저장한 값을 그대로 보인다(G단계부터 같음, 다시 실행하면 DB 값). 롤백 명령이 결과 표에 알리는 길이 필요하다.
- 불리언·날짜 값 처리기는 글자를 너그럽게 읽는다(예: 붙여넣은 `not-bool` → false, `2026-13-45` → 다음 해 날짜). 셀 편집기와 같은 모델 동작이라 바꾸지 않았다.

## 변경 이력

- 2026-11-08 최초 작성(`chromite/p4-grid-editing`): 저장하고 커밋, 변경 미리 보기, 행·셀 명령, 실행 취소, 붙여넣기·특수 붙여넣기, 고급 복사·복사 형식,
  불리언·ENUM·날짜 편집기, LOB 편집·파일, JSON·XML 편집기.
- 2026-11-08 "남긴 것"의 값 편집기는 [swing-value-editors.md](swing-value-editors.md)에서 넣었다고 표시, SET 설명 고침(`chromite/value-editors-rest`).
- 2026-11-09 macOS 키: 아래 행 값 복사 ⇧⌘D(⌥⌘D는 Dock), 다시 실행 ⇧⌘Z(앱 변경 #24, `chromite/mac-ui`, [shortcuts.md](shortcuts.md)).
- 2026-11-16 오른쪽 클릭 메뉴에 해당하는 편집 명령만 보임, 읽기 전용 연결에서 읽은 결과는 편집 명령이 꺼진 채 보임(앱 변경 #137, `chromite/result-grid-menu`,
  위 "오른쪽 클릭 메뉴에 보이는 편집 명령").
- 2026-11-22 값 전체를 보이지 않는 LOB 칸은 링크, 누르기·두 번 누르기·F2로 값 패널(읽기 전용 결과 포함, 앱 변경 #160, `chromite/grid-lob-full-text`,
  위 "LOB 편집과 파일").
- 2026-11-22 고급 복사·복사 형식이 이미 읽은 LOB의 전문을 씀(앱 변경 #160 후속, 위 "고급 복사와 복사 형식").
- 2026-11-28 결과 표의 복사·머리글 포함 복사가 고급 복사의 "필요할 때" 따옴표 글자와 이스케이프한 HTML 표를 넣음, 결과 값을 보이는 다른 표(레코드 보기, 집계,
  값 대화상자 항목, 참조 값 고르기)도 같게, 10만·30만 행 측정(앱 변경 #189, `chromite/grid-copy-excel`, 위 "엑셀로 붙여넣는 복사").
