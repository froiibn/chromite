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

## DBeaver 플러그인 한국어 보충

사용자 결정(2026-11-06, 안 "가"): 한국어 Windows에서도 개체 탐색기 폴더(Databases, Schemas, Tables…), 개체 속성 이름(Object ID, Owner,
Statistics…), 실행 계획·잠금·세션 열, 데이터 전송 속성, 인증 방식·연결 유형 이름이 영어로 보였다. Swing 화면이 쓰는 DBeaver **모델**
번들의 `_ko` 파일이 없거나 비어 있었기 때문이다. 그래서 그 번들의 한국어 번역을 채웠다. SWT UI 플러그인(`*.ui`, `ui.*`)과 `deps/`는 건드리지 않았다.

### 대상과 수

| 플러그인 | 파일 | 추가한 키 | 고친 기존 값 |
|---|---|---|---|
| `org.jkiss.dbeaver.model` | `OSGI-INF/l10n/bundle_ko`, `ModelResources_ko`, 새 `DataMessages_ko` | 113 + 157 + 12 | 15 + 2 (키 1개 삭제) |
| `org.jkiss.dbeaver.registry` | `OSGI-INF/l10n/bundle_ko`, `RegistryMessages_ko` (+ `plugin.xml`, `bundle.properties`) | 25 + 25 | 3 + 2 |
| `org.jkiss.dbeaver.data.transfer` | `OSGI-INF/l10n/bundle_ko`, 새 `DTMessages_ko` | 89 + 141 | 67 |
| `org.jkiss.dbeaver.model.sql` / `model.jdbc` | `bundle_ko`, `ModelSQLResources_ko` / `bundle_ko` | 28 + 19 / 8 | 6 / 15 |
| `org.jkiss.dbeaver.ext.generic` / `ext.sqlite` | `bundle_ko`, 새 `GenericMessages_ko` / 새 `bundle_ko`, `SQLiteMessages_ko` | 3 + 1 / 8 + 1 | 46 / — |
| `org.jkiss.dbeaver.ext.postgresql` | 새 `bundle_ko`, 새 `PostgreSQLMessages_ko` | 650 + 12 | — |
| `org.jkiss.dbeaver.ext.mssql` | `bundle_ko`(Chromite 실행 계획 키 15개는 그대로), 새 `SQLServerMessages_ko` | 199 + 20 | — |
| `org.jkiss.dbeaver.ext.oracle` | 새 `bundle_ko`, 새 `OracleMessages_ko` | 858 + 25 | — |
| `org.jkiss.dbeaver.ext.mysql` (MariaDB 포함) | 새 `bundle_ko`, 새 `MySQLMessages_ko` | 348 + 3 | — |

모두 2,745개 키를 더하고 기존 한국어 값 156개를 고쳤다. `# TODO ko`는 없다.

- 파일 형식은 기존 DBeaver `_ko` 파일과 같다(UTF-8, BOM 없음). Equinox는 `OSGI-INF/l10n/bundle_ko`(ManifestLocalization)와 NLS
  `*Messages_ko`를 모두 UTF-8 그대로 읽는다(화면 확인). 키 순서는 영어 파일을 따르고, 새 파일은 영어 파일의 주석과 빈 줄도 따랐다.
- 영어 파일은 바꾸지 않았다. 예외는 아래 `registry`의 인증 방식 이름 하나다.
- 기술 토큰과 제품 이름(SQL 키워드, `ID`, `PID`, `TID`, `OID`, `SSL`, `#`, URL, 드라이버·제조사 이름, CSV/HTML 같은 형식 이름)은 번역하지 않았다.
  번역이 영어와 같은 키는 넣지 않았다(영어로 대체됨).
- 자리 표시자 `{0}`와 작은따옴표는 영어 원문과 같은 규칙(그 키를 `NLS.bind`/`MessageFormat`으로 쓰는지)에 맞췄다.

### 기존 값 정렬

모델 번들에 이미 있던 한국어 값 중 용어집과 다른 154개를 고쳤다(목록: `D:\Tools\_scratch\dbeaver-ko-model\aligned.txt`, 형식 `파일: 키: '전' -> '후'`).
주된 유형:

- 컬럼/컬럼명 → 열/열 이름, 테이블명·스키마명·카탈로그명·인덱스명 → … 이름
- 데이터 유형/타입 → 데이터 형식, 정밀도 → 전체 자릿수, 외부 키 → 외래 키, Unique 키 → 키(폴더)/고유
- 로우 → 행, 헤더 → 머리글, 구분자 → 구분 기호, 파일 확장자 → 파일 확장명, 인자/파라미터 → 매개 변수, 디폴트 → 기본값
- 해요체("추가하세요") → 합니다체("추가하십시오"), `_ko`에 영어로 남아 있던 값(Quote always, Precision, Auto Increment, Synonyms…) 번역
- 영어 파일에 없는 낡은 키 하나 삭제(`model` `bundle_ko`의 `…AbstractTableConstraint.type.name`, 영어 키는 `constraintType`)

다섯 영역을 합친 뒤 번들 사이에서 같은 영어가 다른 한국어로 번역된 곳을 스크립트로 찾아 맞췄다
(`D:\Tools\_scratch\dbeaver-ko\consistency.py`: 모든 `_ko` 파일과 영어 짝을 읽어 같은 영어 값끼리 묶고 한국어가 다른 묶음을 출력,
Swing `*Messages_ko`도 포함). 맞춘 것(30줄 수정, 3줄 추가):

| 영어 | 정한 한국어 | 고친 곳 |
|---|---|---|
| Row Count, Total row count, Rows(행 개수), Real/Stat row count | 행 수 / 실제 행 수 / 통계 행 수 | mssql 3, pg 2, oracle 5 ("행 개수" → "행 수"; 실행 계획 Rows와 model 용어) |
| Create time | 만든 시간 | mysql 2 ("생성 시간"; SSMS "만든 날짜"와 짝) |
| Min Value / Max Value | 최소값 / 최대값 | generic 2, mysql 2 ("최솟값/최댓값"; SSMS, 다수 번들) |
| Truncate table(s) | 테이블 잘라내기 | pg 2 ("테이블 비우기") |
| Cascade | 계단식 | pg 1 (TRUNCATE CASCADE "연쇄 적용"; FK 규칙과 같은 말) |
| Admin option | ADMIN 옵션 | pg 1 ("관리 옵션"; Oracle과 같게, SQL 키워드) |
| Entity | 엔터티 | pg 2 (실행 계획 "개체") |
| Primary (인덱스) | 기본 키 | model 1 ("기본"; pg와 같게) |
| Type이 데이터 형식인 곳 | 형식 | model 2, model.jdbc 2(JDBC 형식), pg 1(연산자 클래스 형식), oracle 2(Types 폴더) |
| Not Null (PostgreSQL 열·데이터 형식) | NOT NULL | pg 3 키 추가(영어로 남겨 두었던 것; 다른 번들과 같게) |

남은 차이는 뜻이 달라 일부러 둔 것이다: Source(개체 소스 "소스" / 원본 "원본"), Operation(실행 계획 "연산" / 작업 "작업"),
Program(세션 "애플리케이션" / Oracle 스케줄러 "프로그램"), Header(CSV "머리글" / Oracle 패키지 "헤더"), Extension(파일 "확장명" / PG "확장"),
Default(값 "기본값" / 여부 "기본"), Table type(종류 "테이블 유형" / SQL Server 사용자 정의 "테이블 형식"), Parameter Type(IN/OUT "유형" / 데이터 형식 "형식"),
Generated(PG 생성 열 "생성됨" / 자동 생성), Host(SSMS "호스트 이름"), Value(SQL Server 시퀀스 "현재 값"), Swing의 "…"·":"만 다른 메뉴/제목.
Comment(주석), Owner(소유자), Nullable(Null 허용), Created/Modify Date(만든 날짜/수정한 날짜), Grants(권한 부여), Partitioned(분할됨)는 이미 같았다.

### 영어 파일을 바꾼 곳: 인증 방식 이름

`org.jkiss.dbeaver.registry/plugin.xml`의 기본 인증 방식 두 개(`native`, `shell_command`)는 `label`/`description`이 문자열로
박혀 있어 번역할 수 없었다. `%auth.native.label`, `%auth.native.description`, `%auth.shell_command.label`,
`%auth.shell_command.description`으로 바꾸고 `OSGI-INF/l10n/bundle.properties`에 **같은 영어 문구**를 넣었다(영어 화면은 그대로).
서버에 연결 대화상자의 인증 목록이 한국어에서 "사용자 이름/암호"로 나온다(PostgreSQL·MariaDB처럼 기본 인증 방식을 쓰는 드라이버).

### 화면 확인 (2026-11-06, 통합 브랜치)

Swing 제품을 개인 작업 공간으로 실행해 SQL Server, PostgreSQL, MariaDB, Oracle 테스트 DB에 연결하고 확인했다.
서버에 연결(인증·연결 유형·자동 커밋), 개체 탐색기 폴더, 테이블·열 속성(행 수, 만든 날짜, NOT NULL, 데이터 형식 등), 데이터 내보내기 CSV 설정이
한국어로 나오고, `-nl en`에서는 영어 문구가 그대로다(Username/password, Development/Test/Production, Total row count, CSV 속성 이름).

### MySQL `nodeName` 키

`MySQLPlanNodeJSON`의 게터는 `getNodeName()`인데 영어 파일의 키는 `…MySQLPlanNodeJSON.nodeDisplayName.name`이라 조회되지 않는다
(`ObjectPropertyDescriptor`는 게터 이름으로 키를 만든다, `org.jkiss.dbeaver.model/.../runtime/properties/ObjectPropertyDescriptor.java:652-656`).
영어 파일은 고치지 않는 원칙이라 `bundle_ko`에만 `…MySQLPlanNodeJSON.nodeName.name = 이름`을 주석과 함께 넣었다. 한국어 실행 계획의 열은
"이름", 영어는 지금처럼 "Name"(애너테이션 기본값)이다. 영어 파일에는 게터가 없는 죽은 키가 더 있다(`MySQLProcedure.body`, `clientBody`,
`MySQLEvent.eventDefinition`, `nodeDisplayName`) — 동작에 영향이 없어 두었다.

### 영어로 남은 것과 이유

번들에 키가 없어 `.properties`만으로는 번역할 수 없는 것들이다. 고치려면 Java나 `plugin.xml`(영어 원본)을 바꿔야 해서 이번 범위에서 뺐다.

- **속성 범주**: 속성 창의 범주 머리글(Statistics, Properties, Flags, Settings, Constraints, Misc, Partitioning, Details, 연결 노드의
  Driver/Server 등)은 `@Property(category = "...")` 문자열이다. `ObjectPropertyDescriptor.getCategory()`가 번들을 찾지 않고 그대로 돌려준다
  (`org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/runtime/properties/ObjectPropertyDescriptor.java:363-366`).
- **`plugin.xml`에 박힌 라벨**: Oracle 폴더 `Java`, `Jobs`(`ext.oracle/plugin.xml`), SQL Server 폴더 설명(도구 설명), 인증 방식 이름
  (SQL Server `dataSourceAuth`의 "SQL Server Authentication", "Windows Authentication" 등, Oracle의 "Username/password", "OS Authentication" —
  `ext.oracle/plugin.xml:463,472`; 기본 인증 방식처럼 `%키`로 바꾸면 번역할 수 있다), Oracle 역할 값 Normal/SYSDBA/SYSOPER, 드라이버 속성(Misc, Performance, Show all databases, Server Time Zone, SSL 설정 등), 작업 범주 이름.
  SQL Server의 `description="tree.administer.node.description"`은 `%`가 빠져 키 이름이 그대로 보인다(upstream 버그).
- **키 없는 `@Property`**: PostgreSQL 제약 조건의 "Type"(`PostgreTableConstraintBase.getConstraintType()`을 다시 선언했는데 키가 없음),
  권한·연산자 클래스 이름 등 일부, Oracle 사용자의 `infoLabel`.
- **값(enum·Java 문자열)**: 제약 조건 유형 값(`FOREIGN KEY`), 연결 원본 "Local", 데이터 전송 옵션 값(top, as is, always, label, quotes, upper,
  default — `plugin.xml` `validValues`), 서버가 돌려주는 값(USER_TABLE, sleeping…). 라벨이 아니라 값이다.
- **예외·작업 이름**: "Connect to 'X'"(`ConnectJob` 작업 이름), 드라이버 인스턴스 오류, `SQLServerLoginPasswordManager`가 덧붙이는 영어 상세 —
  AGENTS.md 규칙대로 예외 문구는 영어.
- **Swing 쪽**: 데이터베이스 종류 대화상자의 범주 필터가 등록부의 `driver.category.*.name` 대신 범주 ID(sql, analytic…)를 보여 준다
  (`DriverSelectionPanel`). 별도 작업으로 남긴다.
- 의도적으로 영어로 둔 값: `ID`, `URL`, `PID`, `TID`, `SSL`, `#` 같은 기술 토큰, 제품 이름이 들어간 드라이버 설명.

### SQL Server 폴더 이름: "저장 프로시저", "제약 조건"

사용자 결정(2026-11-06): SQL Server 개체 탐색기의 폴더 이름은 한국어 SSMS와 같게 한다. **이름만** 맞추고 트리 구조는 바꾸지 않는다
(SSMS의 Programmability 폴더 같은 묶음은 만들지 않고, DBeaver 모델의 폴더 구성을 그대로 쓴다).
`ext.mssql`의 `OSGI-INF/l10n/bundle_ko.properties`에서 고친 키는 다음과 같다.

| 키 | 이전 | 지금 |
|---|---|---|
| `tree.procedures.node.name`(폴더) | 프로시저 | 저장 프로시저 |
| `tree.procedure.node.name`(개체) | 프로시저 | 저장 프로시저 |
| `tree.procedure_columns.node.name` | 프로시저 매개 변수 | 저장 프로시저 매개 변수 |
| `tree.check_constraints.node.name`(테이블 아래 폴더) | CHECK 제약 조건 | 제약 조건 |

- 개체 하나를 가리키는 `tree.check_constraint.node.name`은 "CHECK 제약 조건"으로 둔다. 그 제약 조건의 종류를 나타내는 이름이기 때문이다.
- 용어집의 Procedure → 프로시저는 그대로다. 다른 데이터베이스(PostgreSQL, Oracle, MySQL, generic 등)의 번들은 "프로시저"를 쓴다.
- `ext.mssql`의 다른 `_ko` 문구(속성 이름·설명, `SQLServerMessages_ko.properties`)에는 저장 프로시저를 개체 종류로 부르는 "프로시저"가 없었다.
  폴더 설명 `description="Procedures"`는 `plugin.xml`에 영어로 박혀 있어(`%키` 아님) 번역 대상이 아니다.
- 주의: 이 폴더에는 모델이 함수도 함께 담는다(`SQLServerSchema.ProcedureCache`가 `type IN ('P','PC','X','TF','FN','IF')`를 읽음).
  구조는 바꾸지 않기로 했으므로 함수도 "저장 프로시저" 폴더 아래에 보인다.
- SSMS에도 데이터베이스마다 스키마 폴더(보안 > 스키마)가 있다. Chromite는 DBeaver 모델대로 데이터베이스 > 스키마 > 개체 순서로 보여 준다.

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
| Data Type / Data Types (폴더·속성) | 데이터 형식 |
| Type: 개체의 종류 / 열·인수의 데이터 형식 | 유형 / 형식 |
| Column Name / Table Name / Schema Name / Catalog Name / Index Name | 열 이름 / 테이블 이름 / 스키마 이름 / 카탈로그 이름 / 인덱스 이름 |
| Precision / Scale | 전체 자릿수 / 소수 자릿수 |
| Nullable / Not Null | Null 허용 / NOT NULL |
| Default (기본값) / Auto Increment / Auto Generated | 기본값 / 자동 증가 / 자동 생성 |
| Unique / Unique Key / Check constraint / Keys (폴더) | 고유 / 고유 키 / CHECK 제약 조건 / 키 |
| Owner / Comment / Description / Definition / Source (개체 원본 텍스트) | 소유자 / 주석 / 설명 / 정의 / 소스 |
| Created, Create Date / Modify Date, Altered / Create time | 만든 날짜 / 수정한 날짜 / 만든 시간 |
| Row Count / Rows (실행 계획) / Row Count Estimate | 행 수 / 행 수 / 예상 행 수 |
| Collation / Charset | 데이터 정렬 / 문자 집합 |
| Increment / Min Value / Max Value | 증분 / 최소값 / 최대값 |
| Ordinal Position / Ascending / Cardinality | 서수 위치 / 오름차순 / 카디널리티 |
| Synonym / Sequence / Package / Trigger / Materialized View / Tablespace | 동의어 / 시퀀스 / 패키지 / 트리거 / 구체화된 뷰 / 테이블스페이스 |
| Role / Privilege / Grants / Grantor / Grantee / Grantable / Admin option | 역할 / 권한 / 권한 부여 / 권한 부여자 / 피부여자 / 부여 가능 / ADMIN 옵션 |
| Partition / Subpartition / Partitioned | 파티션 / 하위 파티션 / 분할됨 |
| Attribute (형식의 멤버) / Method / Entity / Expression | 특성 / 메서드 / 엔터티 / 식 |
| Extension / Foreign Table / Foreign server / Foreign data wrapper | 확장 / 외부 테이블 / 외부 서버 / 외부 데이터 래퍼 |
| Object ID / Session ID / Host name / Program (세션) | 개체 ID / 세션 ID / 호스트 이름 / 애플리케이션 |
| On Delete / On Update / Cascade / Restrict / No Action / Set NULL / Set Default | 삭제 시 / 업데이트 시 / 계단식 / 제한 / 작업 안 함 / NULL 설정 / 기본값 설정 |
| Read uncommitted / Read committed / Repeatable read / Serializable | 커밋되지 않은 읽기 / 커밋된 읽기 / 반복 읽기 / 직렬화 가능 |
| Username/password / SQL Server Authentication / Windows Authentication | 사용자 이름/암호 / SQL Server 인증 / Windows 인증 |
| Security / Logins / Extended Properties / Data source | 보안 / 로그인 / 확장 속성 / 데이터 원본 |
| Header (CSV) / Delimiter / Row delimiter / Quote character / File extension | 머리글 / 구분 기호 / 행 구분 기호 / 따옴표 문자 / 파일 확장명 |
| Truncate table / Definer / Body / Deterministic / Engine | 테이블 잘라내기 / 정의자 / 본문 / 결정적 / 엔진 |
| Recycle Bin / Scheduler / Job / Timezone | 휴지통 / 스케줄러 / 작업 / 표준 시간대 |

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
- DBeaver 플러그인 한국어 보충: 위 표의 `_ko` 파일(새로 만들거나 키 추가·값 정렬), `org.jkiss.dbeaver.registry`의 `plugin.xml`(인증 방식 두 개의
  라벨·설명을 `%키`로)과 `OSGI-INF/l10n/bundle.properties`(같은 영어 문구 4개 추가). Java 파일은 바꾸지 않았다.

## 변경 이력

- 2026-11-06 최초 작성: Swing 화면 문구를 영역별 NLS 클래스 네 개로 옮기고 한국어 번역 추가(OS 언어를 따름), 영역 간 용어 통일
  ("그리드" → "표", "Hex" → "16진수", "Not connected" → "연결 안 됨"), 한국어 화면의 semibold 한글을 굵은 대체 글꼴로 그림.
- 2026-11-06 DBeaver 플러그인 한국어 보충(사용자 결정 안 "가"): model·registry·data.transfer·model.sql·model.jdbc·generic·sqlite·PostgreSQL·
  SQL Server·Oracle·MySQL 모델 번들의 `_ko` 키 2,745개 추가, 기존 값 156개를 용어집에 맞춤, 번들 사이 용어 통일(행 수, 최소값/최대값, 형식/유형 등),
  기본 인증 방식 이름을 `plugin.xml` `%키`로. 용어집에 모델 용어 추가. SQL Server "저장 프로시저"는 미해결 질문으로 남김.
- 2026-11-06 SQL Server 폴더 이름을 한국어 SSMS에 맞춤(사용자 결정, SQL Server만, 이름만 바꾸고 구조는 그대로): "프로시저" → "저장 프로시저"
  (폴더·개체·매개 변수), 테이블 아래 "CHECK 제약 조건" 폴더 → "제약 조건". 미해결 질문을 닫음.
