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
  (고치지 않음, "남긴 것"). Oracle·일반 드라이버의 열 관리자는 정렬 규칙을 쓰지 않는다.

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

## 남긴 것
- SQL Server에서 `EXEC dbo.` 뒤 자동완성이 모델 안에서 `NullPointerException`("identifier" is null)으로 실패한다. 프로시저 설정과 상관없다(고치기 전에도 같음).
- 새 완성 엔진의 `EXEC`/`CALL` 뒤 프로시저 제안(SWT도 기본 설정에서는 없음).
- PostgreSQL: 기본이 아닌 정렬 규칙을 가진 열을 복사해 만든 새 열의 형식을 정렬 규칙이 없는 형식으로 바꾸면 `COLLATE`가 남는다.
- 열의 소수 초 자릿수에 맞춘 표시(`datetime2(7)`은 일곱 자리처럼). 데이터베이스 형식 설정을 켠 채 Oracle로 복사하면 문자열 바인딩이 `NLS_TIMESTAMP_FORMAT`에 달려 있다.
- 테마를 실행 중에 바꾸면 이미 있는 탭 창의 넘침 단추 도구 설명이 사라진다. FlatLaf가 단추를 `installComponents`에서 만들 때는 아직 문구를 읽기 전이라
  번역과 상관없이 영어 문구도 같다. 다시 시작하면 나온다.
- 전송 오류의 앞부분 "Error while finishing result set fetching into"는 모델 예외 문구라 영어로 남는다.

## 변경 이력
- 2026-11-07 최초 작성: 자동완성 프로시저·함수 제안 설정(#33).
- 2026-11-07 SQL Server 열 정의가 문자형이 아닌 형식에 COLLATE를 붙이던 것(#44).
- 2026-11-07 날짜/시간 값을 데이터베이스 형식으로 보이는 설정(#43), SQL Server `datetimeoffset` 편집이 NULL로 저장되던 것.
- 2026-11-07 진행 대화상자의 "Canceling…"과 탭 넘침 단추의 "Show Hidden Tabs"를 화면 언어로(#44).
- 2026-11-07 데이터 전송 오류의 대상 이름이 `….null`로 나오던 것(#44).
- 2026-11-07 네이티브 백업·복원 실패 항목을 swing-backup-restore.md로 연결(#40).
- 2026-11-07 Oracle `v$session.program`에서 `.`·`-`까지 `?`로 바꾸던 것(#68), 위 "Oracle 프로그램 이름".
