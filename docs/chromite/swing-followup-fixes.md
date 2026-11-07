# Swing 후속 수정 — 편집기·결과·도구 (2026-11-07)

2026-11-06 통합 중 모은 후속 과제(followups-2026-11-06의 #33, #40, #43, #44) 가운데 편집기·결과·도구 쪽 여섯 가지를
고친 기록이다. 항목마다 증상, 근본 원인(파일:줄, 고치기 전 기준), 같은 원인의 다른 경우, 고친 방법, SWT(DBeaver) 대비 변경점을 적는다.

## 자동완성 프로시저

- 증상: Swing SQL 편집기 자동완성이 저장 프로시저·함수를 제안하지 않음(#33).
- 원인: `SqlCompletionAnalysis.SwingCompletionContext.isSearchProcedures()`가 늘 `false`였다(`SqlCompletionAnalysis.java:426-428`).
  모델의 새 완성 엔진(`SQLQueryCompletionContext`)은 이 값이 켜져 있을 때만 테이블 문맥과 값 문맥(SELECT 목록, WHERE)에서 `DBSProcedure`·`DBSPackage`를
  찾고(`SQLQueryCompletionContext.java:466`, `:657`, `:1374`), 프로시저 목록도 이 값이 켜져 있을 때만 읽는다(`collectProcedures` `:1533`, `collectPackages` `:1511`).
- SWT: 같은 값을 설정 `SQLEditor.ContentAssistant.show.column.procedures`(`SQLPreferenceConstants.SHOW_COLUMN_PROCEDURES`)에서 읽고
  (`SQLEditorCompletionContext.java:100`), 기본값은 끔(`SQLEditorPreferencesInitializer.java:108`)이며 설정 화면의 확인란
  "Show stored procedures in column list"(`PrefPageSQLCompletion.java:228`)로 켠다. SWT도 기본으로는 제안하지 않지만 켤 수 있고, Swing에는 켤 곳이 없었다.
- 고친 것: Tools > Options > 일반 > Query Editor에 확인란 **Propose stored procedures and functions**(저장 프로시저와 함수도 제안)를 두고
  Swing 번들 설정 `sqlEditor.completion.showProcedures`(기본 끔)에 저장한다(`SqlCompletionPopup.isShowProcedures`·`setShowProcedures`).
  `SwingCompletionContext`가 완성 요청마다 이 값을 읽어 엔진에 넘긴다.
- 결정: 기본값은 SWT와 같이 끔. 켜면 SELECT 목록·WHERE의 제안에 프로시저·함수가 섞여 길어지므로 SWT 기본을 따랐다.
  연결별 값은 두지 않았다(SWT는 연결 설정으로 덮어쓸 수 있으나 Swing 자동완성의 다른 설정도 모두 전역이다).
- 새 엔진에는 `EXEC`/`CALL` 뒤의 프로시저 제안 문맥이 없다. 예전 엔진(`SQLCompletionAnalyzer.java:353-358`)에는 있지만 SWT 기본 설정도
  새 엔진만 쓰므로(`SqlCompletionAnalysis` 클래스 설명) Swing도 그대로 두었다.
  2026-11-07 새 엔진에 추가했다([swing-sql-completion.md](swing-sql-completion.md)). 이 설정은 값 자리에만 적용되고 호출문 뒤는 늘 제안한다.

## SQL Server 열 정렬 규칙

- 증상: 테이블 디자이너에서 SQL Server 열의 형식을 `varchar`에서 `int`로 바꾸면 변경 스크립트가
  `ALTER TABLE … ALTER COLUMN c int COLLATE Korean_Wansung_CI_AS NULL`이 되어 저장이 오류 447("Expression type int is invalid for COLLATE clause")로 실패함(#44).
- 원인: `SQLServerTableColumn`은 읽어 온 정렬 규칙(`collation_name`, `SQLServerTableColumn.java:151`)을 형식과 따로 들고 있고 형식을 바꿔도
  그대로 남는다(`setDataType` `:197`). 열 정의를 만드는 `SQLServerTableColumnManager.CollateModifier`(`:64-69`)는 형식을 보지 않고 정렬 규칙이 있으면
  늘 `COLLATE`를 붙였다. 열 추가(ADD)와 변경(ALTER COLUMN)이 같은 수정자를 쓰므로 둘 다 해당하고, SWT 속성 편집기에서 형식을 바꿔도 같다.
- 고친 것: `CollateModifier`가 열 형식이 정렬 규칙을 받을 수 있을 때만 `COLLATE`를 붙인다(`isCollatableType`). 판단은 데이터 전송이 이미 쓰는
  `SQLServerDatabase.isCollatableType`(char, nchar, varchar, nvarchar, text, ntext, sysname)이고, 별칭 형식은 기본 형식(`getSystemDataType`)으로 본다.
  형식을 모르면 예전처럼 붙인다. 정렬 규칙 값은 열에 남으므로 다시 문자형으로 돌리면 원래 정렬 규칙이 붙는다.
- 다른 방언: MySQL/MariaDB는 이미 `getDataKind() == STRING`일 때만 `CHARACTER SET`·`COLLATE`를 붙인다(`MySQLTableColumnManager.java:76-86`).
  PostgreSQL은 열 변경(`ALTER COLUMN … TYPE`, `PostgreTableColumnManager.addObjectModifyActions`)에 정렬 규칙을 넣지 않는다. 새 열을 만들 때만
  `PostgreCollateModifier`(`:116-125`)가 붙이는데 기본 정렬 규칙은 건너뛰므로, 기본이 아닌 정렬 규칙을 가진 열을 복사해 형식을 바꾼 경우에만 남는다
  (2026-11-07 고침, [model-fixes.md](model-fixes.md) "PostgreSQL 복사한 열의 정렬 규칙과 저장 방식"). Oracle·일반 드라이버의 열 관리자는 정렬 규칙을 쓰지 않는다.

## 날짜/시간 소수 초

- 증상: 결과 표가 `timestamp(6)`·`timestamptz`·`datetime2(7)`·`datetimeoffset` 값을 밀리초(소수 셋째 자리)까지만 보이고, 셀 편집기도 같은 글자로
  시작하므로 값을 고쳐 저장하면 마이크로·나노초가 사라짐(#43).
- 원인: Swing 고유의 문제가 아니라 DBeaver 데이터 서식 프로필의 기본 형식이다. 날짜/시간 값 처리기(`DateTimeCustomValueHandler.getValueDisplayString`
  `:91-101`)는 표시 형식(UI·EDIT)과 상관없이 프로필의 서식기로 글자를 만들고, 기본 패턴은 `yyyy-MM-dd HH:mm:ss.SSS`(`DBConstants.java:112-113`,
  `TimestampFormatSample`)다. `ExtendedDateFormat.format`은 `S`/`f` 자리 수만큼만 나노초를 쓰므로 세 자리에서 끝나고, 편집한 글자도 같은 서식기로 읽는다
  (`DateTimeCustomValueHandler.getValueFromObject` → `DateTimeDataFormatter.parseValue`). SWT 결과 표도 같은 처리기와 같은 기본 프로필을 쓰므로 똑같다.
  Swing 표(`ResultSetGridReceiver`, `ResultSetEditModel.getEditText`)는 SWT와 같은 API를 같은 형식으로 부르고, 고치지 않은 셀은 원래 값을 유지한다
  (`ResultSetGrid.TextCellEditor.stopCellEditing`).
- SWT의 해결책: 설정 화면 Data Formats의 **Disable date/time formatting**(`ModelPreferences.RESULT_NATIVE_DATETIME_FORMAT`, `PrefPageDataFormat.java:145`,
  기본 끔). 켜면 처리기가 드라이버의 글자(`ResultSet.getString`)를 값으로 쓰고(`JDBCDateTimeValueHandler.fetchValueObject`), 편집한 글자도 서식기를
  거치지 않고 그대로 바인딩한다(`DateTimeCustomValueHandler.getValueFromObject`, PostgreSQL은 형식을 붙여 `setObject`). Swing에는 이 설정을 바꿀 곳이 없었다.
- 고친 것:
  - Tools > Options > 일반 > Query Results에 확인란 **Show date/time values in the database format**(날짜/시간 값을 데이터베이스 형식으로 표시)를 두고
    플랫폼 설정 `resultset.format.datetime.native`(SWT 설정 화면과 같은 저장소·키)에 저장한다. 연결별 값이 없는 연결은 이 값을 따르고 다음 쿼리부터 적용된다.
  - 확인 중 찾은 같은 계열의 오류: SQL Server `datetimeoffset` 처리기(`SQLServerDateTimeOffsetHandler.getValueFromObject` `:92-100`)는 편집한 글자를
    서식기로만 읽고, 읽지 못하면 `null`을 돌려주어 **값이 NULL로 저장**되었다. 데이터베이스 형식 설정을 켜면 셀 글자(`… .1234567 +09:00`)가 늘 서식기와 맞지 않아
    모든 편집이 NULL이 되고, 꺼 둔 상태에서도 서식과 다른 글자(ISO `2026-11-06T12:30:00+09:00` 등)가 NULL이 되었다. 이제 데이터베이스 형식이면 글자를 그대로,
    서식기가 읽지 못한 글자는 서버에 그대로 넘긴다(서버가 읽지 못하면 오류가 난다). 빈 글자는 예전처럼 NULL이다. 다른 날짜/시간 처리기(PostgreSQL, MySQL,
    Oracle, 일반)는 `DateTimeCustomValueHandler`처럼 읽지 못한 글자를 그대로 두므로 해당하지 않는다.
- 결정: 기본값은 DBeaver와 같이 끔. 켜면 날짜/시간 값이 문자열이 되어 데이터 전송(Copy Tables 등)도 문자열로 바인딩한다. 대상이 문자열을 날짜로
  바꾸는 규칙(예: Oracle `NLS_TIMESTAMP_FORMAT`)에 맞지 않으면 복사가 실패할 수 있어 모든 사용자에게 켜지 않았다.
  열의 소수 초 자릿수(`timestamp(6)`, `datetime2(7)`)에 맞춰 서식을 바꾸는 방법은 모델의 서식기·처리기 여러 곳을 고쳐야 해서(java.time 경로의 `n` 패턴은
  소수 자릿수가 아니라 나노초 값이다) 하지 않았다("남긴 것").

## 남은 영어 문구

- 증상: 한국어 화면에서 진행 대화상자의 취소 중 표시가 "Canceling…", 탭 줄이 넘칠 때 나오는 단추의 도구 설명이 "Show Hidden Tabs"로 나옴(#44).
- 원인:
  - `ProgressDialog.cancel`이 글자를 코드에 적어 두었다(`ProgressDialog.java:156`, `//$NON-NLS-1$`). Swing 번들에서 화면 문구를 코드에 적은 곳은 여기뿐이었다
    (`setText("…")`, `setToolTipText("…")`, `new JLabel/JButton/JMenuItem("…")` 검색).
  - "Show Hidden Tabs"는 ModernDocking이 아니라 FlatLaf의 문구다. `modern-docking-api-1.4.9.jar`에는 없고, `flatlaf.jar`의
    `com/formdev/flatlaf/resources/Bundle.properties`에 `TabbedPane.moreTabsButtonToolTipText`로 있다. FlatLaf는 `FlatLaf.initResourceBundle`에서 이 묶음을
    UI 기본값에 넣는데 번역은 독일어·스페인어·프랑스어뿐이라 한국어에서는 영어가 남는다. 문서 탭(`MainFrame`의 `SCROLL_TAB_LAYOUT`), 도구 창 탭
    (ModernDocking `DockedTabbedPanel`의 `JTabbedPane`), 연결 설정 탭이 넘치면 이 단추가 나온다. 같은 묶음의 `SplitPaneDivider.*` 여덟 개는 한 번 눌러
    펼치는 분할 막대(`setOneTouchExpandable`)에만 쓰이는데 Swing 화면에는 그런 막대가 없어 옮기지 않았다.
- 고친 것: `CoreMessages.progress_canceling`(취소하는 중…)과 `CoreMessages.main_show_hidden_tabs`(숨겨진 탭 표시)를 더하고, `ChromiteTheme.install`이
  Look and Feel을 설치한 뒤 `UIManager.put("TabbedPane.moreTabsButtonToolTipText", …)`으로 넣는다. `UIManager.put`은 개발자 기본값이라 Look and Feel
  기본값보다 앞서고 테마를 바꿔도 남는다.

## 전송 대상 이름

- 증상: 데이터 전송(Copy Tables, Export Data… → Database)이 기존 테이블에 쓰다 실패하면 오류가
  `Error while finishing result set fetching into 'chr_fixes_editor.dbo.null'`처럼 대상 이름 대신 `null`을 보임(#44).
- 원인: 오류 문구(`DBDDataReceiver.java:78`)는 받는 쪽의 `toString()`을 쓰고, `DatabaseTransferConsumer.toString()`(`:1074`)은
  `DatabaseMappingContainer.getTargetFullName()`을 돌려준다. 이 메서드는 스키마·카탈로그 아래 대상의 이름을 `targetName` 필드에서 직접 읽었다
  (`DatabaseMappingContainer.java:402-409`). 필드는 새로 만들거나 이름을 바꾼 대상에만 채워지고, 기존 테이블을 고른 매핑(`existing`)은 대상 개체만 들고 있어
  비어 있다. 같은 클래스의 `getTargetName()`(`:181-222`)은 대상 개체·원본 이름으로 채우는데, 스키마·카탈로그가 없을 때만 그것을 썼다.
  같은 메서드를 쓰는 다른 곳은 매핑 화면의 대상 이름(`DatabaseTransferConsumer.getTargetName` `:888`)뿐이고, 대상 개체가 있으면 그 앞에서 개체 이름을 쓴다.
- 고친 것: `getTargetFullName()`이 두 경우 모두 `getTargetName()`을 쓴다.

## 네이티브 백업·복원 실패

빈 출력 파일 때문에 MySQL 백업을 다시 실행할 수 없던 것과, psql 일반 형식 복원이 SQL 오류에도 성공으로 보이던 것(#40)은
[swing-backup-restore.md](swing-backup-restore.md) "실패한 백업과 psql 오류"에 적었다.

## Oracle 프로그램 이름

- 증상: Oracle 세션 목록(`v$session.program`)에 Chromite가 "Chromite 26?2?5 ? Activity Monitor"로 보임(후속 과제 #68).
- 원인: 인코딩이 아니라 `OracleDataSource.getInternalConnectionProperties`(고치기 전 `OracleDataSource.java:462`)가 이름에서 영문자·숫자·공백이
  아닌 글자를 모두 `?`로 바꿨다(upstream 그대로, 주석 "Oracle don't like them"). 그래서 `.`과 `-`가 `?`가 되었다. 버전 26.2.5는 #70(버전 0.1.0)
  전에 본 값이다. 이름은 `DBUtils.getClientApplicationName` → `GeneralUtils.getProductTitle()`(애플리케이션 기여 번들 `com.seizia.chromite.ui.swing`의
  버전)이라 지금은 `Chromite 0.1.0 - <용도>`다.
- 드라이버가 받는 값(ojdbc11 23.2.0.0, 제품에 들어 있는 버전, Oracle Free 23ai에서 시험): `(`, `)`가 있거나 48자를 넘으면 연결이
  ORA-17190으로 실패한다. `.`, `-`, `_`, `/`, `:`, `<>`, `[]`, `=`, `,`, 따옴표, `#`, `\`는 그대로 저장된다. 한글은 AL32UTF8 DB에서 그대로
  저장되지만 열이 84바이트라 잘린다.
- 고친 것: 출력 가능한 ASCII(0x20-0x7E)는 두고 `(`, `)`와 ASCII 밖 글자만 `?`로 바꾼다(48자 자르기는 그대로). ASCII 밖 글자를 바꾸는 것은
  DB 문자 집합에 따라 결과가 달라지지 않게(유니코드가 아닌 DB에서는 어차피 `?`) 하기 위해서다. 연결 용도에 사용자 파일 이름(한글)이 들어갈 수 있다.
- 같은 이름을 쓰는 다른 드라이버: PostgreSQL `application_name`(pgjdbc `ApplicationName`)과 SQL Server `program_name`(`applicationName`, 64자로 자름)은
  이름을 바꾸지 않는다. 시험: 둘 다 ASCII 문장부호를 그대로 저장하고, SQL Server는 한글도 그대로, PostgreSQL 17은 ASCII 밖 글자를 서버가 `\xNN`으로
  바꾼다(서버 동작, 고치지 않음).
- 확인: 앱에서 FB Oracle·FB PG·FB MSSQL에 작업 모니터를 열고 서버를 따로 조회 → Oracle `Chromite 0.1.0 - Main`, `- Metadata`,
  `- Activity Monitor`, PostgreSQL `Chromite 0.1.0 - Main <chr_followups_batch_1>`, SQL Server 같은 이름.

## 확인 (2026-11-07)

Swing 제품(`-vm D:\Tools\jbrsdk-25\bin\server\jvm.dll`, 비공개 작업 공간)을 실행하고 같은 프로세스에 붙인 EDT 시험 에이전트로 조작했다(OS 입력 없음).
데이터베이스는 `chr_fixes_editor`(PostgreSQL 17, SQL Server 2022, MariaDB 11.8). 화면은 한국어(OS 기본)와 `-nl en`, 라이트·다크
(`D:\Tools\_scratch\fixes-editor\run\shots\`).
- 자동완성: 팝업은 편집기가 초점을 가져야 뜨는데(`SqlCompletionPopup.analysisDone`) 시험 에이전트는 창 초점을 줄 수 없어, 팝업이 쓰는 분석
  (`SqlCompletionAnalysis.compute`)을 앱 프로세스 안에서 같은 요청으로 불렀다. 설정을 끄면 SQL Server `SELECT ufn_`·PostgreSQL `SELECT fx_` 제안 0개,
  켜면 `ufn_total_amount(:@a)`(PROCEDURE)가 나옴. Options 확인란·저장·다시 열기 확인.
- SQL Server 디자이너: `c_code varchar(50) COLLATE Korean_Wansung_CI_AS` → `int`의 변경 스크립트가 `ALTER TABLE chr_fixes_editor.dbo.t_types ALTER COLUMN c_code int NULL;`,
  실행 성공(`sys.columns`에서 int). 회귀: `varchar` → `nvarchar`와 별칭 형식 `code_t`(varchar 기반)는 `COLLATE Korean_Wansung_CS_AS`를 그대로 붙임.
- 날짜/시간: 설정을 끄면 PostgreSQL `10:00:00.123`(밀리초), 켜면 `2026-11-06 10:00:00.123456`, `…+09`, `10:00:00.123456`. 켠 상태에서 편집·저장한 값이
  psql·sqlcmd·mariadb로 그대로 확인됨(PostgreSQL `timestamp(6)`·`timestamptz` 마이크로초, SQL Server `datetime2(7)`·`datetimeoffset(7)` 100ns, MariaDB `datetime(6)`).
  `datetimeoffset`은 고치기 전에는 NULL로 저장됨을 확인했고(위 원인), 고친 뒤에는 `2026-11-06 10:00:01.7654321 +09:00`으로 저장. 설정을 끈 상태에서
  ISO 글자 `2026-11-06T12:30:00.5+09:00`도 `12:30:00.5000000 +09:00`으로 저장.
- 문구: SQL Server 연결 테스트를 닿지 않는 주소(10.255.255.1)로 걸고 취소 → "취소하는 중…" / "Canceling…". 문서 탭 14개로 넘치게 한 뒤 넘침 단추의
  도구 설명이 "숨겨진 탭 표시" / "Show Hidden Tabs", 목록 열기 확인.
- 전송: PostgreSQL `copy_src` → SQL Server 기존 테이블 `copy_dst`(기본 키 충돌)로 복사, 중지 → `Error while finishing result set fetching into 'chr_fixes_editor.dbo.copy_dst'`,
  진행 표의 대상 열도 `chr_fixes_editor.dbo.copy_dst`.
- 백업·복원: [swing-backup-restore.md](swing-backup-restore.md) "실패한 백업과 psql 오류"의 확인.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능은 각 항목의 "고친 것"에 적은 곳이다.

## 작은 화면 수정 (2026-11-08)

후속 과제 followups-2026-11-06의 #25, #58, #65, #66, #74와 followups-2026-11-07-evening의 #7, #15(남은 부분)다. 고치기 전에 모두 다시 확인했고,
#74는 지금 기본 배치에서 생기지 않아 고치지 않았다. 줄 번호는 고치기 전 기준이다.

### 쿼리 상태 줄 왼쪽 문구 (#25, #66)

- 증상: 1280 창(Windows 150%, 논리 1280×800)의 기본 배치(Object Explorer·Properties 열림, 쿼리 창 745)에서 상태 문구가 잘림.
  한국어 "쿼리가 완료되었지만 오…"(필요 230, 받은 폭 180), 영어 "Query complet…"(203/141), 영어는 성공 문구 "Query executed successfully."도 잘림(207/134).
- 원인: `QueryStatusLine`이 상태 문구를 `BorderLayout.CENTER`, 오른쪽 칸들을 `FlowLayout` 패널로 `EAST`에 두었다(`QueryStatusLine.java:144-182`).
  `EAST`는 늘 선호 폭을 받으므로 폭이 모자라면 상태 문구만 줄어든다. 칸이 늘수록(커밋 방식 칸, 열린 트랜잭션 칸) 더 일찍 잘린다.
  상태 문구에는 도구 설명도 없어(`setState`는 글자만 바꿈) 잘린 문구를 읽을 곳이 없었다.
- 고친 것: 줄 전체를 배치하는 `QueryStatusLine.LineLayout`. 칸들은 오른쪽부터 선호 폭으로 놓고, 모자라면 **서버 칸이 먼저 양보**한다.
  서버 칸은 최소 64까지 줄임표로 줄고(도구 설명에 전체 위치·버전이 있음), 그래도 모자라면 빠진다(연결은 도구 막대 연결 상자와 탭 제목에 있다).
  그 뒤에야 상태 문구가 잘린다. 다른 칸(사용자, DB/스키마, 커밋 방식, 열린 트랜잭션, 시간, 행, 커서)은 값을 도구 설명에 갖고 있지 않아 줄이지 않는다.
- 결정: 최소 64는 줄임표 앞에 몇 글자(예: "127.0...")가 남는 폭이다. 처음에는 최소 폭까지 줄이기만 했는데 영어는 1280에서도 3~10px 모자라
  여전히 잘렸고, 최소를 48로 낮추면 "12..."만 보여 쓸모가 없어 "64까지 줄이고, 그래도 모자라면 빼기"로 했다. 한국어 오류 문구는 64로 줄인 서버 칸과 함께 보이고,
  영어 문구는 서버 칸이 빠진다.
- 같은 원인의 다른 경우: 실제 실행 계획 안내("The actual execution plan will be included when executing.", 약 330px)는 서버 칸을 빼도 1280에서 잘린다.
  더 좁은 창(1000, 쿼리 창 675)에서는 서버 칸을 뺀 뒤 상태 문구가 잘린다("Query completed with errors." 203/187). 이 경우는 그대로 둔다.

### 잠금 나무 폭 (#25)

- 증상: 작업 모니터 > 잠금의 차단 체인 나무가 129px로 시작해 "세션 38352 · 38345 대기"(끝 207), "Session 38352 · waits for 38345"(끝 253)가 잘림.
- 원인: 위쪽 분할(`LockManagerPanel.java:176-180`)의 처음 나눔 위치는 왼쪽 패널의 선호 폭이다. 첫 새로 고침 전에는 나무가 비어 있어 선호 폭이
  머리글 "차단 체인"만큼이고, 나무가 채워진 뒤에는 분할이 다시 나누지 않는다.
- 고친 것: 나무 패널의 선호 폭을 280(`TREE_WIDTH`)으로 둔다. 1단계 대기 세션 줄(영어, 다섯 자리 세션 번호)이 들어가고 두 단계 더 들여써도 남는다.
  사용자가 분할 막대를 옮기는 것은 그대로다.

### PropertiesPanel 주석 (#25)

- `PropertiesPanel.GridRenderer`의 주석 "a derived font would lose the Hangul fallback"(`:565`)이 틀렸다. FlatLaf 테마 글꼴은 합성 글꼴
  (`createdFont`인 `FontUIResource`)이라 `Font.deriveFont`가 같은 대체 글꼴을 유지한다. `semibold.font`를 쓰는 진짜 이유는 Java에 semibold 스타일이
  없어서다(`deriveFont(Font.BOLD)`는 semibold가 아니라 bold). 주석만 고쳤다.

### 테마 전환 뒤 탭 넘침 단추 도구 설명 (#58)

- 증상: 실행 중 테마를 바꾸면 탭 줄이 넘칠 때 나오는 단추의 도구 설명("숨겨진 탭 표시"/"Show Hidden Tabs")이 없어짐. 다시 시작하면 나옴.
- 원인: FlatLaf 3.8 `FlatTabbedPaneUI`의 단추(`FlatMoreTabsButton`)는 생성자에서 필드 `moreTabsButtonToolTipText`를 읽는다. 단추는
  `BasicTabbedPaneUI.installUI` → `installComponents` → `installHiddenTabsNavigation`에서 만들어지는데, 필드를 채우는 `installDefaults`는 그 뒤에 불린다
  (`javap -c`로 확인). 처음에는 탭 창이 만들어진 뒤 `setTabLayoutPolicy(SCROLL_TAB_LAYOUT)`가 같은 UI 객체를 다시 설치해(필드가 이미 채워짐) 단추를
  다시 만들므로 보인다. 테마 전환(`FlatLaf.updateUI`)은 탭 창마다 새 UI 객체를 설치할 뿐이라 단추가 도구 설명 없이 남는다. 번역(ChromiteTheme이 넣는
  UI 기본값)과는 상관없다.
- 고친 것: 새 클래스 `ChromiteTabbedPaneUI`(`FlatTabbedPaneUI` 상속)가 `createMoreTabsButton`에서 도구 설명이 비어 있으면 UI 기본값에서 채운다.
  `ChromiteLightLaf`·`ChromiteDarkLaf`가 `TabbedPaneUI`로 등록하고(`ChromiteScrollBarUI`와 같은 방식), 문서 탭의 `MainFrame.IslandTabbedPaneUI`도 이 클래스를 상속한다.
  그래서 문서 탭, 도구 창 탭, 연결 설정 탭, 테마 전환 뒤에 만들어지는 탭 창 모두에 적용된다.

### 연결 중 중지 (#65)

- 증상: 쿼리 창에서 실행해 연결하는 동안 중지(쿼리 실행 취소)를 누르면 "'Slow PG'에 연결하지 못했습니다. … 연결 시도 중 중단되었습니다." 오류 창이 뜸.
  드라이버가 중단을 무시하고 연결을 마치면 같은 자리에서 "Connection has been canceled" 오류가 된다.
- 원인: `ConnectJob.run`(`:110-113`)이 연결 중 던져진 모든 예외를 오류 상태(`makeExceptionStatus`)로 만들었다. 사용자가 취소한 경우의 예외는 두 가지다:
  `DataSourceDescriptor.connect`가 취소를 알아채고 던지는 `DBException("Connection has been canceled")`(`DataSourceDescriptor.java:1239-1243`)과,
  `ConnectJob.canceling`이 연결 스레드를 끊어 드라이버가 던지는 예외(pgjdbc "연결 시도 중 중단되었습니다"). `SwingConnectionService.notifyFinished`는
  취소(`IStatus.CANCEL`)가 아닌 실패를 오류 창으로 보이므로 오류 창이 떴다.
- 고친 것: `ConnectJob.run`이 예외를 받았을 때 모니터가 취소되었으면 `connectStatus`를 `Status.CANCEL_STATUS`로 두고 `connectError`를 남기지 않는다(debug 로그만).
  모델 쪽 한 곳이라 같은 원인을 가진 다른 경로도 함께 고쳐진다: 실행 계획, 작업 모니터, 개체 찾기, 쿼리 예약 등 `DBUtils.initDataSource`를 부르는 모든 곳,
  탐색기 노드 펼치기(취소 상태를 받음), 연결 대화상자의 Test Connection(`ConnectionTestJob`은 `connectError`가 없고 성공도 아니면 아무 창도 띄우지 않음).
  쿼리 창은 이미 `run.cancelRequested`이면 취소로 끝내므로(`SqlEditorPanel.prepareAndSchedule`) 상태 줄이 "쿼리가 취소되었습니다."가 된다.
- upstream 대비: DBeaver의 `ConnectJob`은 취소로 생긴 예외도 오류 상태로 만든다(이 파일은 DBeaver에서 왔다).

### 감사 기록 열 폭 (evening #7)

- 증상: 옵션 > Chromite Agent > 감사 기록의 "클라이언트" 열이 59px라 "Chromite Agent"가 "Chro…", 머리글 "클라이언트"가 "클라이…"로 잘림.
  같은 원인으로 영어 "Policy" 열의 "Run automatically"(113/101), 결과 열의 "Read-only connection"·"정책으로 막힘"도 잘린다.
- 원인: 열 폭이 문구와 상관없는 고정 숫자였다(`AiOptionsPage.java:898-901`, 클라이언트 60, 분류·정책·결과 80).
- 고친 것: 값이 고정 문구(클라이언트·분류·정책·결과)이거나 고정 형식(시각)인 열은 가장 긴 문구와 머리글의 글자 폭 + 28(셀 여백 8+8과 정렬 화살표)로 맞추고
  같은 값을 최소 폭으로도 둔다(`AuditTab.fitLabels`). 처음에는 최소 폭 없이 시각 열을 고정 숫자로 두었더니, 표를 대화상자 폭에 맞출 때 시각 열이
  132로 줄어 "2026-11-08 10:04:05"(133)가 잘려서, 시각 열도 형식 "0000-00-00 00:00:00"으로 맞췄다. 자유 글자 열(도구, 연결, ms)은 고정 숫자 그대로이고
  표 폭에 맞춰 줄어든다.

### 줄임표 (evening #15 남은 부분)

- Swing 번들 메시지 파일에서 줄 끝 줄임표는 "…"(U+2026)가 144개, "..."가 10개였고 "..."는 모두 `AiMessages`에 있었다.
  Query Library 편집기의 Parameters 단추는 "Parameters…", Thresholds 단추는 "Thresholds..."였다.
- 고친 것: `AiMessages`(영어·한국어)의 단추·메뉴 글자 세 개를 "…"로: `ai_thresholds_button`, `ai_thresholds_button_count`, `ai_settings`(Chromite Agent 창의 설정 단추).
- 결정: 같은 파일의 진행·안내 문구 7개("Thinking...", "Running {0}...", "Stopping...", "running...", "Testing...", "Loading...", 입력 칸 안내
  "Ask about your databases...")는 단추·메뉴 글자가 아니라 이번 범위에서 바꾸지 않았다. 다른 번들의 진행 문구(예: "Executing query…", "Connecting…")는 "…"를 쓴다.

### 결과 상태 줄과 결과 영역 높이 (#74, 고치지 않음)

- 다시 확인: 기본 배치에서 Results 카드 280 중 결과 영역(바깥 스크롤 창)은 224(머리글과 쿼리 상태 줄 27을 뺀 높이)이고, 결과 하나의 최소 높이
  `QueryResultsView.MIN_GRID_HEIGHT` 220보다 크다. 그래서 결과 표가 영역에 맞춰지고 뷰어 상태 줄(행 수, 필터 요약)이 보인다(한국어·영어, 라이트·다크에서 같음).
  쿼리 상태 줄이 Results 카드 아래로 옮겨지고 밀도 B 치수가 적용된 뒤로 생기지 않는 것으로 보인다. 여유는 4px라 머리글이나 쿼리 상태 줄이 커지면 다시 생길 수 있다.
  결과 집합이 둘 이상이면 표마다 220을 받아 바깥 스크롤이 생기는 것은 설계대로다.

### 확인 (2026-11-08)

빌드 `-Pproduct-chromite-swing` BUILD SUCCESS. 고치기 전 `origin/devel`(fa0d23fe0f)을 같은 방식으로 빌드해 "전" 캡처를 만들었다.
JBR 25(`-vm`), `CHROMITE_DATA`와 작업 공간은 비공개 폴더, 창 1280×800(논리, Windows 150%), 같은 프로세스에 붙인 EDT 시험 에이전트(OS 입력 없음).
데이터베이스는 공유 시험 컨테이너 PostgreSQL 17(55432), 연결할 수 없는 주소 10.255.255.1의 "Slow PG". 잠금은 `ui_polish_lock` 테이블에 세션 하나가
`ACCESS EXCLUSIVE`를 잡고 둘이 기다리게 만들었다(확인 뒤 삭제). 감사 기록은 작업 공간에 시험 기록 3줄을 넣었다.
캡처: `D:\Tools\_scratch\ui-polish-batch\run\shots\{before,after}-{ko,en}-{light,dark}-*.png`.

| 항목 | 전 | 후 |
|---|---|---|
| 상태 줄 오류 문구, 한국어 | 180/230, "쿼리가 완료되었지만 오…" | 230/230, 서버 칸 114 → 65 "127.0...." |
| 상태 줄 오류·성공 문구, 영어 | 141/203, 134/207 | 256/203, 248/207, 서버 칸 빠짐 |
| 잠금 나무 | 폭 129, 줄 끝 207(한국어)·253(영어) | 폭 282 |
| 탭 넘침 단추 도구 설명, 테마 전환 뒤 | null(라이트→다크, 다크→라이트) | "숨겨진 탭 표시"/"Show Hidden Tabs" |
| 연결 중 중지 | 오류 창 "'Slow PG'에 연결하지 못했습니다. … 연결 시도 중 중단되었습니다." | 창 없음, 상태 "쿼리가 취소되었습니다."/"Query was canceled." 약 3초 |
| 감사 기록 클라이언트 열 | 59(한국어)·81(영어) < 100 | 112, 시각 151, 정책(영어) 125, 잘림 없음 |
| Thresholds 단추 | "기준값..."/"Thresholds..." | "기준값…"/"Thresholds…" |
| 결과 영역(#74) | 224 ≥ 220, 상태 줄 보임 | 같음 |

- 확인하지 못한 것: 연결 대화상자 Test Connection의 취소(코드 경로만 확인), SQL Server·Oracle 연결의 중지, 100% 배율, macOS.
- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 쿼리 상태 줄의 모든 칸 배치(커밋 방식·열린 트랜잭션 칸 클릭 위치 포함), 모든 탭 창
  (Look and Feel의 탭 UI가 바뀜), 연결 실패 처리(취소가 아닌 실패는 그대로 오류), 감사 기록 표.

## 남긴 것
- (2026-11-07 고침) SQL Server `EXEC dbo.` 뒤 자동완성의 `NullPointerException`과 새 완성 엔진의 `EXEC`/`CALL` 뒤 프로시저 제안:
  [swing-sql-completion.md](swing-sql-completion.md).
- 열의 소수 초 자릿수에 맞춘 표시(`datetime2(7)`은 일곱 자리처럼). 데이터베이스 형식 설정을 켠 채 Oracle로 복사하면 문자열 바인딩이 `NLS_TIMESTAMP_FORMAT`에 달려 있다.
- (2026-11-08 고침) 테마를 실행 중에 바꾸면 이미 있는 탭 창의 넘침 단추 도구 설명이 사라진다. 위 "작은 화면 수정".
- 전송 오류의 앞부분 "Error while finishing result set fetching into"는 모델 예외 문구라 영어로 남는다.

## 변경 이력
- 2026-11-07 최초 작성: 자동완성 프로시저·함수 제안 설정(#33).
- 2026-11-07 SQL Server 열 정의가 문자형이 아닌 형식에 COLLATE를 붙이던 것(#44).
- 2026-11-07 날짜/시간 값을 데이터베이스 형식으로 보이는 설정(#43), SQL Server `datetimeoffset` 편집이 NULL로 저장되던 것.
- 2026-11-07 진행 대화상자의 "Canceling…"과 탭 넘침 단추의 "Show Hidden Tabs"를 화면 언어로(#44).
- 2026-11-07 데이터 전송 오류의 대상 이름이 `….null`로 나오던 것(#44).
- 2026-11-07 네이티브 백업·복원 실패 항목을 swing-backup-restore.md로 연결(#40).
- 2026-11-07 Oracle `v$session.program`에서 `.`·`-`까지 `?`로 바꾸던 것(#68), 위 "Oracle 프로그램 이름".
- 2026-11-07 "남긴 것"의 PostgreSQL 복사한 열 `COLLATE`(#60)를 고침: [model-fixes.md](model-fixes.md).
- 2026-11-07 "남긴 것"의 `EXEC dbo.` 예외와 `EXEC`/`CALL` 뒤 프로시저 제안(#56, #57)을 고침: [swing-sql-completion.md](swing-sql-completion.md).
- 2026-11-08 "작은 화면 수정": 쿼리 상태 줄 폭(#25, #66), 잠금 나무 폭과 PropertiesPanel 주석(#25), 테마 전환 뒤 탭 넘침 단추 도구 설명(#58),
  연결 중 중지의 오류 창(#65), 감사 기록 열 폭(evening #7), AI 단추 줄임표(evening #15). #74는 다시 확인해 생기지 않아 고치지 않음.
