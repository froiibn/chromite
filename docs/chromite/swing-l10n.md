# Swing 프런트엔드 화면 언어(한국어화)

## 목적

사용자 결정(2026-11-06): Swing 화면은 **운영체제 언어를 따른다**. 기본(영어)은 지금 문구 그대로 두고, 한국어 운영체제에서는
화면 전체를 한국어로 보여 준다. 언어를 고르는 설정은 따로 두지 않는다(요청 없음). DBeaver 모델의 속성 이름처럼 플랫폼이 이미
번역한 문구는 플랫폼 번역을 그대로 쓴다.

## 구조

### NLS 메시지 클래스

DBeaver의 NLS 관례(`plugins/org.jkiss.dbeaver.ui.editors.sql/.../internal/SQLEditorMessages*`)를 따른다.
패키지 `com.seizia.chromite.ui.swing.internal`(export하지 않음)에 기능 영역마다 클래스 하나를 둔다.
영역을 나눈 이유는 네 사람이 동시에 옮기면서 같은 파일을 고치지 않게 하려는 것(병합 충돌 방지)이다.

| 클래스 | 키 | 영역 |
|---|---|---|
| `CoreMessages` | 275 | 메인 창·메뉴·도구 막대, 개체 탐색기, 등록된 서버, 속성, 옵션, 정보, 찾기/바꾸기, 플랫폼 대화상자(오류·진행·입력), 테마, 아이콘 스타일, 알림, DDL 보기, 스크립팅 |
| `EditorMessages` | 299 | SQL 편집기, 편집 > 고급, 템플릿, 매개 변수 바인딩, 실행 계획, 쿼리 기록, 쿼리 상태 줄, 결과 표·값 패널·레코드·필터·집계·다른 형식으로 복사·CSV/Excel 내보내기 |
| `ConnectionMessages` | 285 | 서버에 연결 대화상자(모든 탭: SSH·SSL·프록시·드라이버 속성·시작 쿼리), 드라이버 선택·다운로드, 연결 명령, 작업 모니터(프로세스·잠금), 개체 찾기 |
| `ToolsMessages` | 450 | 데이터 내보내기/가져오기 마법사, 테이블 디자이너, 쿼리 라이브러리(매개 변수·일정·예약된 쿼리·실행 기록) |

- 각 클래스는 `NLS`를 상속하고 `public static String 키;` 필드와 `NLS.initializeMessages(BUNDLE_NAME, …)`를 가진다.
- `MANIFEST.MF`의 `Require-Bundle`에 `org.eclipse.osgi`를 한 줄 넣었다(`org.eclipse.osgi.util.NLS`). 다른 번들의 재내보내기로도
  보이지만, 직접 쓰는 번들은 직접 요구하는 편이 분명하다.
- 키는 `영역_구성요소_항목` 소문자와 밑줄(`main_menu_file`, `editor_grid_copy`, `designer_action_delete` …).

### 파일 형식

- `XxxMessages.properties`(영어 기본)와 `XxxMessages_ko.properties`(한국어). 한국어 파일은 UTF-8, CRLF(DBeaver `_ko` 파일과 같음).
  영어 파일은 UTF-8(`…` 같은 문자가 그대로 있음)이고 `ToolsMessages.properties`만 `\uXXXX` 이스케이프를 쓴 ASCII다. 둘 다 같은 값으로
  읽히므로 통일하지 않았다(화면 확인).
- **작은따옴표 규칙**: `NLS.bind`는 작은따옴표를 MessageFormat처럼 인용 부호로 해석한다. 그래서 `{0}` 같은 자리 표시자가 있어
  `NLS.bind`로 채우는 문구는 `''`로 쓰고, 그대로 쓰는 문구는 `'` 하나로 쓴다(`Can''t open {0}` / `Can't move the item`).
- 문장 조각을 이어 붙이지 않는다. 단수/복수는 `_one`/`_many`(또는 `…`/`…s`) 문장 두 개로 나누고 한국어는 같은 문장을 쓴다.
  영어 결과 문장은 옮기기 전과 글자까지 같다.
- 숫자 천 단위 구분은 코드의 `String.format("%,d")`가 만들고 문구에는 `{0}`로만 넣는다.

### 모델 쪽 변경

Swing 화면에 보이지만 모델 번들에 있는 Chromite 문구도 그 번들의 기존 지역화 방식으로 옮겼다.

- `org.jkiss.dbeaver.model.sql`: `SQLQueryExecuteHandler`의 기본 확인 대화상자 두 개(운영 연결의 실행 확인, 결과 집합이 너무 많을 때)를
  `ModelSQLMessages`(`ModelSQLResources.properties` + 새 `ModelSQLResources_ko.properties`)로. 키 4개 `model_sql_exec_*`.
- `org.jkiss.dbeaver.ext.mssql`: Chromite 실행 계획 노드 `SQLServerPlanNode`의 `@Property` 이름 15개를 DBeaver 속성 지역화 규칙
  (`OSGI-INF/l10n/bundle.properties`의 `meta.<클래스>.<속성>.name`)으로, 한국어는 새 `bundle_ko.properties`.
  `ObjectPropertyDescriptor`는 `name`이 기본값일 때만 번들에서 찾으므로 애너테이션의 `name`을 지웠다.
- `com.seizia.chromite.model.admin`: `ServerAdminMessages_ko.properties`(세션 필터·동작)와 `OSGI-INF/l10n/bundle_ko.properties`
  (`BlockingSessionLock`/`SessionLockItem` 열 이름). `Bundle-Name`/`Bundle-Vendor`는 DBeaver `bundle_ko`처럼 번역하지 않았다.

### 한글 굵은 글꼴

증상: 한국어 화면에서 도구 창 머리글처럼 semibold로 그리는 글자 중 한글만 영어보다 가늘게 보였다.

근본 원인(실측):
- UI 글꼴은 FlatLaf가 만든 Java **합성 글꼴**이다. 첫 칸이 Inter이고, Inter에 없는 글자(한글)는 합성 글꼴의 대체 칸
  (`dialog` 논리 글꼴의 구성 글꼴: Windows는 Arial, 맑은 고딕 …)에서 그린다. FlatLaf `FontUtils.getCompositeFont`는
  `StyleContext.getFont(family, style, size)`를 부르고, JDK는 이때 **글꼴 객체의 스타일(PLAIN/BOLD)과 같은 스타일의** `dialog`를 대체 칸으로 붙인다.
- Java에는 semibold 스타일이 없어서 테마의 `semibold.font = "Inter SemiBold"`는 PLAIN 스타일의 다른 글꼴 이름이다. 그래서 대체 칸이
  맑은 고딕 **보통**(malgun.ttf)이 된다. 라틴 글자는 Inter SemiBold(굵기 600), 한글은 보통(400)으로 그려져 한글이 가늘게 보였다.
- 거꾸로 BOLD 스타일로 만든 글꼴은 대체 칸도 맑은 고딕 Bold(malgunbd.ttf)다. 다만 첫 칸 글꼴에 그 스타일이 없으면 Java가 획을
  인위적으로 굵게 한다. 그래서 `"Inter SemiBold"`를 BOLD로 요청하면 라틴 글자가 지나치게 굵어진다(아래 표 C).
- 실행 런타임(JBR 25, [runtime.md](runtime.md))은 자체 Inter(Regular·Italic·SemiBold)를 갖고 있어 FlatLaf jar의 Inter Regular/Bold 등록이
  실패한다(같은 이름의 글꼴이 이미 있음). 그래서 Inter BOLD는 JBR의 Inter Regular를 인위적으로 굵게 한 글꼴이다.

측정(JBR 25, 13pt, 같은 문자열을 그려 잉크 양을 합산; `D:\Tools\_scratch\swing-l10n\fonttest`):

| 글꼴 | 라틴 "Object Explorer" | 한글 "개체 탐색기" | 대체 칸 |
|---|---|---|---|
| A 지금 semibold: 합성(Inter SemiBold, PLAIN) | 381 | 191 | 맑은 고딕 |
| B 보통: 합성(Inter, PLAIN) | 252 | 191 | 맑은 고딕 |
| C 합성(Inter SemiBold, BOLD) | 456 | 280 | 맑은 고딕 Bold |
| D 합성(Inter, BOLD) | 354 | 280 | 맑은 고딕 Bold |
| 맑은 고딕 Bold 단독 | 356 | 268 | — |

A의 한글이 보통 굵기(B)와 같은 것이 증상의 원인이다.

수정(`ChromiteTheme.install`): 화면 언어의 semibold 문구(도구 창 제목 "개체 탐색기")를 Inter SemiBold가 직접 그릴 수 없으면
테마를 설치하기 전에 FlatLaf 추가 기본값 `semibold.font = bold`(기본 글꼴의 BOLD, 표 D)를 넣는다. 한글은 맑은 고딕 Bold
(macOS: Apple SD Gothic Neo의 굵은 글꼴)로 그려지고, 라틴 글자는 SemiBold와 비슷한 굵기(354 대 381)가 된다.
영어 화면은 지금처럼 Inter SemiBold를 쓴다(달라지는 것 없음). 운영체제 글꼴은 번들에 넣지 않는다.

- 언어 코드(`ko`)로 나누지 않고 "글꼴이 화면 언어의 글자를 갖고 있는가"로 판단한다. 일본어·중국어처럼 Inter에 없는 글자를 쓰는 언어가
  생겨도 같은 규칙이 적용된다.
- 다른 방법을 쓰지 않은 이유: Inter SemiBold를 BOLD로 요청하면 라틴 글자가 인위적으로 더 굵어진다(C). 첫 칸은 SemiBold로 두고
  대체 칸만 Bold인 합성 글꼴은 JDK 내부 API(`sun.font.CompositeFont`)로만 만들 수 있어 실행 옵션(`--add-exports`)과 JDK 버전에
  묶이므로 쓰지 않았다.
- `deriveFont(Font.BOLD)`로 굵게 만든 글꼴(실행 계획 항목, 속성 표 등)은 원래 대체 칸도 Bold라 고치지 않았다.

## 용어집

브리핑 용어집과 영역별 추가분(`D:\Tools\_briefs\l10n-glossary-additions.md`)을 합친 것이다. 한국어 SSMS/Windows 용어를 우선한다.
라벨·단추·메뉴는 짧은 명사구, 메시지는 합니다체("연결하지 못했습니다.")로 쓴다. SQL, JDBC, SSH, SSL, TNS, SID, PK, FK, CSV, JSON,
XML, XLSX, BOM, host:port 같은 기술 토큰은 번역하지 않는다. "…"는 영어와 같이 둔다.

| English | 한국어 |
|---|---|
| File / Edit / View / Query / Tools / Window / Help | 파일 / 편집 / 보기 / 쿼리 / 도구 / 창 / 도움말 |
| Object Explorer / Registered Servers / Properties | 개체 탐색기 / 등록된 서버 / 속성 |
| Server Group / New Server Group / New Server Registration | 서버 그룹 / 새 서버 그룹 / 새 서버 등록 |
| New Query / Execute / Cancel (query) / Cancel Executing Query | 새 쿼리 / 실행 / 실행 취소 / 쿼리 실행 취소 |
| Cancel / OK / Close / Don't Save (buttons) | 취소 / 확인 / 닫기 / 저장 안 함 |
| Results / Messages | 결과 / 메시지 |
| Execution plan / Estimated / Actual | 실행 계획 / 예상 / 실제 |
| Display Estimated Execution Plan / Include Actual Execution Plan | 예상 실행 계획 표시 / 실제 실행 계획 포함 |
| Query History / Query Library | 쿼리 기록 / 쿼리 라이브러리 |
| Connect / Disconnect / Reconnect / Connect to Server | 연결 / 연결 끊기 / 다시 연결 / 서버에 연결 |
| Connection / Connection type / Development / Test / Production | 연결 / 연결 유형 / 개발 / 테스트 / 운영 |
| Database / Schema / Table / View / Column / Index / Key / Foreign Key / Constraint / Primary key | 데이터베이스 / 스키마 / 테이블 / 뷰 / 열 / 인덱스 / 키 / 외래 키 / 제약 조건 / 기본 키 |
| Procedure / Function | 프로시저 / 함수 |
| Script as / CREATE To / SELECT To / Script as CREATE | 스크립팅 / CREATE 대상 / SELECT 대상 / CREATE로 스크립팅 |
| Select Top 1000 Rows / View DDL | 상위 1000개 행 선택 / DDL 보기 |
| Design / New Table… / Table Designer | 디자인 / 새 테이블… / 테이블 디자이너 |
| Generate Script / Change script / Pending changes / Save Changes | 스크립트 생성 / 변경 스크립트 / 보류 중인 변경 내용 / 변경 내용 저장 |
| Save / Save As… / Revert / Open File… / Recent Files / Exit | 저장 / 다른 이름으로 저장… / 되돌리기 / 파일 열기… / 최근 파일 / 끝내기 |
| Refresh / Filter / Collapse All / Expand All | 새로 고침 / 필터 / 모두 축소 / 모두 확장 |
| Rename / Delete / Move To / Remove / Duplicate | 이름 바꾸기 / 삭제 / 이동 / 제거 / 복제 |
| Undo / Redo / Cut / Copy / Paste / Select All | 실행 취소 / 다시 실행 / 잘라내기 / 복사 / 붙여넣기 / 모두 선택 |
| Find… / Replace… / Match case / Use regular expressions | 찾기… / 바꾸기… / 대/소문자 구분 / 정규식 사용 |
| Options… / General / Editor / Editor Colors / Reset to Defaults | 옵션… / 일반 / 편집기 / 편집기 색 / 기본값으로 다시 설정 |
| Theme / Light / Dark / Icon Style / Duo-tone / Full color | 테마 / 라이트 / 다크 / 아이콘 스타일 / 두 가지 색 / 풀 컬러 |
| Activity Monitor / Processes / Locks / Kill Session | 작업 모니터 / 프로세스 / 잠금 / 세션 종료 |
| Find Objects… / Tasks / Import Data… / Export Data… | 개체 찾기… / 작업 / 데이터 가져오기… / 데이터 내보내기… |
| Value Panel / Record / Fetch next / Fetch all | 값 패널 / 레코드 / 다음 행 가져오기 / 모두 가져오기 |
| Copy with Headers / Copy As | 머리글과 함께 복사 / 다른 형식으로 복사 |
| Parameters / Bind Parameters | 매개 변수 / 매개 변수 바인딩 |
| Schedule / Scheduled Queries / History… / Run Now | 일정 / 예약된 쿼리 / 기록… / 지금 실행 |
| Template(s) / Format SQL / Complete / Auto-Complete While Typing | 템플릿 / SQL 서식 지정 / 자동 완성 / 입력할 때 자동 완성 |
| Test Connection / Driver / Driver Properties / Authentication / Bootstrap queries | 연결 테스트 / 드라이버 / 드라이버 속성 / 인증 / 시작 쿼리 |
| User name / Password / Save (Remember) password | 사용자 이름 / 암호 / 암호 저장 |
| Server name / Login / Browse… | 서버 이름 / 로그인 / 찾아보기… |
| Window / Reset Window Layout / Welcome / Ready / Workspace | 창 / 창 레이아웃 다시 설정 / 시작 / 준비 / 작업 영역 |
| < Back / Next > / Finish / Source / Destination | < 이전 / 다음 > / 마침 / 원본 / 대상 |
| Statement / Grid | 문 / 표 |
| Not connected | 연결 안 됨 |
| {0} rows | {0}개 행 |

## 영어로 남긴 것과 이유

- 로그 메시지, 작업(Job)·세션 이름(`AbstractJob("Prepare query")`, `openSession(…, "Explain query")`, `connection.run("Read server sessions")`,
  격리 컨텍스트 이름 "Activity Monitor"(서버에 보이는 애플리케이션 이름) 등): Swing 화면에 나오지 않는다.
- 화면에 나오지 않는 예외와 프로그램 오류성 예외(AGENTS.md: 예외 메시지는 영어): `ProgressDialog` "Internal error",
  `SwingPlatformUI` "The result of the job is null…", `EditorColors` 테마 리소스 오류, `ObjectScripts` "SQL generation canceled",
  `AdminConnection` "The window is closed", `LockManagerPanel` "No lock manager", `DataImportWizard` "Data import is not available",
  `TableDesignSession`의 객체 관리자 오류들, `QueryLibrary` "The query has no file", `QueryParameterBinder`의 JDBC 전용 오류.
  사용자에게 대화상자·상태 줄로 보이는 예외는 옮겼다.
- `ResultSetEditPersister`의 `DBCException` 문구: SWT `ResultSetPersister`에서 그대로 가져온 DBeaver 문구(DBeaver도 영어). 서버·드라이버 오류 메시지도 그대로.
- `SQLServerQueryPlanner` "The server returned no execution plan", `SQLServerExecutionPlan` "Can't parse the showplan XML": 드문 모델 예외, mssql 번들에 한국어 Messages가 없다.
- 데이터·파일 형식: 프로젝트 이름 `General`(폴더 이름), 새 연결 기본 이름 "New Connection"(저장되는 연결 이름), 쿼리 라이브러리 머리말 키,
  실행 기록 `.tsv`의 상태 값(`OK`/`ALERT`/`ERROR`/`SKIPPED`, 화면에서만 번역), 내보내기 기본 파일 이름 `Results.csv/xlsx`.
- 기술 토큰과 SQL: `JSON`, `XML`, `CSV`, `NULL`, `ms`, `WHERE`/`ORDER BY`, `TNS`, 기본 템플릿 `sf`의 설명 `select * from `, 단축키 이름(Ctrl+L, Enter …),
  DDL 탭 제목 `<이름> [DDL]`, 끌어 놓기 내부용 DataFlavor 이름.
- 제품 이름 "Chromite", 부제 "Database Workbench"(제품 속성, 브랜딩), 저작권 문구, 사이트 URL.
- 다른 곳에서 오는 문구: DBeaver 모델·드라이버의 이름(인증 방식 이름, 연결 유형 이름, 드라이버 속성 범주, PostgreSQL/SQL Server 세션·잠금 열,
  노드 유형, 개체 탐색기 폴더 이름)은 플랫폼에 번역이 있는 것만 한국어로 나온다. RSyntaxTextArea 기본 오른쪽 클릭 메뉴는 라이브러리의 `RTextArea_ko`로 이미 한국어다.
- 테마 메뉴 항목의 니모닉은 기존대로 라벨의 첫 글자라, 한국어 라벨에서는 동작하지 않는다(기존 동작을 바꾸지 않음).

## 확인 방법

- 한국어: 한국어 Windows에서 그냥 실행하면 한국어 화면이다(OSGi 로캘 = OS 로캘).
- 영어: `chromite-swing.exe -nl en`. 영어 화면은 옮기기 전과 글자까지 같아야 한다.
- 개인 작업 공간(`-data <폴더>`)으로 실행해 사용자 설정을 건드리지 않는다. 확인한 화면: 메인 창과 세 도구 창, 파일/편집/보기/쿼리/도구/창 메뉴,
  개체 탐색기 오른쪽 클릭 메뉴, 서버에 연결, SQL 편집기·결과·실행 계획·상태 줄, 옵션, 작업 모니터, 데이터 내보내기 마법사, 테이블 디자이너,
  쿼리 라이브러리(한국어 라이트·다크, 영어 라이트).

## upstream 대비 변경점

- Swing 프런트엔드 자체가 Chromite 코드라 upstream에 대응하는 파일이 없다.
- DBeaver 파일: `ModelSQLMessages.java`·`ModelSQLResources.properties`(키 4개 추가), `SQLQueryExecuteHandler.java`(Chromite가 만든 파일),
  `ext.mssql`의 `OSGI-INF/l10n/bundle.properties`(Chromite 실행 계획 노드 키 추가)와 새 `bundle_ko.properties`.

## 변경 이력

- 2026-11-06 최초 작성: Swing 화면 문구를 영역별 NLS 클래스 네 개로 옮기고 한국어 번역 추가(OS 언어를 따름), 영역 간 용어 통일
  ("그리드" → "표", "Hex" → "16진수", "Not connected" → "연결 안 됨"), 한국어 화면의 semibold 한글을 굵은 대체 글꼴로 그림.
