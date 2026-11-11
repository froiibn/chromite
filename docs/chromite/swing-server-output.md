# Swing 서버 출력 (DBMS_OUTPUT, PRINT)

## 목적

SWT 편집기는 문장이 끝날 때마다 연결의 서버 출력(Oracle `DBMS_OUTPUT`, Altibase `PRINT`, CUBRID `DBMS_OUTPUT`)을 읽어
Output 패널에 보였다. Swing 쿼리 창은 이 출력을 읽지 않았다. 그래서 출력이 보이지 않았고, 읽지 않은 출력이 쌓였다.

- Oracle: 모델이 모든 실행 문맥에서 `DBMS_OUTPUT.ENABLE(1000000)`을 켠다. 한 세션의 출력이 100만 바이트를 넘으면 사용자 PL/SQL이
  `ORU-10027: buffer overflow`로 실패할 수 있었다.
- Altibase: 드라이버의 메시지 콜백이 받은 글을 데이터 원본의 `StringBuilder`에 붙이고, 읽을 때만 비운다. 연결이 열려 있는 동안 계속 커졌다.
- CUBRID: Oracle과 같은 구조(기본 버퍼 1만 바이트).

SWT 대조 감사 항목 D-K1·K2·K3, D-O24, D-A4, B-S25, A-C10([swt-parity-audit.md](swt-parity-audit.md) "2순위")이다.

## 구조

| 위치 | 역할 |
|---|---|
| `ui.swing` `SqlEditorPanel.readServerOutput` | 문장마다(엔진의 `SQLQueryListener.onEndQuery`, 엔진 Job 스레드) 연결의 `DBCServerOutputReader`로 출력을 읽어 그 문장의 출력 목록에 넣는다 |
| `ui.swing` `SqlEditorPanel.reportQueryResult` | 출력 목록(출력으로 가른 경고 PRINT·NOTICE → 서버 출력)을 Server Output 탭에, 나머지(경고 → 행 수·`Commands completed` → 실행 시간, 또는 오류)를 Messages 탭에 쓴다(2026-11-12) |
| `ui.swing` `SqlEditorPanel.isServerOutput` | 문장의 `SQLWarning`이 서버가 찍은 출력인지 경고인지 가른다(2026-11-12, 아래 설계 결정) |
| `ui.swing` `QueryResultsView.appendServerOutput` | Server Output 탭에 쓰고, 탭이 앞에 없으면 탭에 점을 붙인다(2026-11-12) |
| `ui.swing` `SqlEditorPanel.syncServerOutput` | 문장을 실행하기 전에(엔진의 `SQLQueryListener.onStartQuery`, 엔진 Job 스레드) 읽기 도구의 `syncServerOutput`을 부른다(2026-11-08) |
| `model` `DBCServerOutputReader` | 연결별 읽기 도구(데이터 원본의 `getAdapter`). 2026-11-08에 기본 메서드 `syncServerOutput`(아무것도 하지 않음)을 더했다 |
| `ext.oracle` `OracleDataSource.OracleOutputReader` | `DBMS_OUTPUT.GET_LINE`을 상태가 0이 아닐 때까지 부른다. 읽으면 서버 버퍼에서 빠진다. `syncServerOutput`은 실행 문맥이 기억한 켜짐(`OracleExecutionContext`)이 설정과 다르면 `ENABLE`/`DISABLE`을 보낸다(2026-11-08) |
| `ext.altibase` `AltibaseDataSource.AltibaseOutputReader` | 콜백 버퍼를 한 번에 쓰고 비운다. 설정이 꺼져 있으면 콜백을 등록하지 않고, 버퍼가 비어 있으면 아무것도 쓰지 않게 고쳤다. `syncServerOutput`은 세션마다 기억한 콜백 등록이 설정과 다르면 콜백을 등록하거나 `null`로 지운다(2026-11-08) |
| `ext.cubrid` `CubridDataSource.CubridOutputReader` | Oracle과 같은 `get_line` 반복. `syncServerOutput`은 세션마다 기억한 버퍼 크기(꺼짐 0)가 설정과 다르면 `ENABLE(크기)`/`DISABLE`을 보낸다(2026-11-08) |
| `ext.altibase` `internal.AltibaseActivator`(새로 만듦, `MANIFEST.MF`의 `Bundle-Activator`) | 번들이 활성화될 때 기본값 노드를 읽어 `AltibasePreferencesInitializer`를 실행한다 |

## 설계 결정과 이유

- **Server Output 탭에 쓴다(2026-11-12 사용자 결정, 앱 변경 #54).** 결과 영역의 위 탭이 `Grid | Text | Messages | Server Output | 실행 계획`이 되면서
  서버가 낸 출력(DBMS_OUTPUT, PRINT, RAISE NOTICE 등)만 Server Output 탭에 둔다. 오류·행 수·경고·실행 시간은 Messages다. 둘은 성격이 다르다(출력은 사용자 코드가
  찍은 글, 메시지는 실행 결과). 출력이 없는 실행에서도 탭은 자리에 비어 있고(위치 고정), 탭이 앞에 없을 때 새 출력이 오면 탭에 점이 붙는다(고르면 지움).
  실행마다 비운다(Messages와 같음). [swing-frontend.md](swing-frontend.md) "결과 영역 두 단계 탭".
  - 전에는(2026-11-07) SSMS처럼 한 문장의 출력과 행 수, 오류를 한 곳에 차례대로 보이려고 Messages 탭에 썼다. 이제 출력과 메시지의 문장 사이 순서는 탭 사이에서
    끊긴다. 순서가 필요하면 SQL Terminal(문장, 출력, 메시지를 한 줄로 이어 씀)을 쓴다.
- **경고 중 무엇이 출력인가(2026-11-12).** JDBC는 PRINT·NOTICE와 진짜 경고를 모두 `SQLWarning`으로 준다. `SqlEditorPanel.isServerOutput`이 표준 SQLSTATE와 벤더 코드로 가른다.
  - SQLSTATE 클래스 `01`(SQL 표준의 경고 클래스: PostgreSQL `RAISE WARNING` 01000, MySQL 경고)은 경고 → Messages.
  - 그 밖에 벤더 코드 0(PostgreSQL `NOTICE`·`INFO`, SQLSTATE 00000; SQL Server `PRINT`) 또는 SQL Server 사용자 메시지(`RAISERROR`, 50000 이상)는 출력 → Server Output.
  - 나머지 벤더 코드(SQL Server의 정보 메시지 "Changed database context" 등, MySQL note, Oracle 경고)와 SQLSTATE가 없는 경고는 Messages.
  - PostgreSQL이 스스로 내는 NOTICE(`relation "t" already exists, skipping` 등)도 SQLSTATE 00000이라 Server Output이다. psql도 같은 NOTICE로 찍는다.
  - SQL Server 경로(PRINT의 코드 0·SQLSTATE `S0001`, RAISERROR 50000)는 mssql-jdbc의 상태 코드 규칙으로 정했고 실행해 보지 못했다(이 PC의 Docker가 꺼져 있음).
- **문장마다 엔진 Job 스레드에서 읽는다.** SWT는 200 ms마다 도는 별도 Job이 모아 둔 요청을 읽었다. Swing은 엔진이 문장 끝을 알릴 때 같은
  스레드에서 바로 읽는다. 그 문장의 메시지와 함께 한 번에 EDT로 넘기므로 순서가 섞이지 않고, EDT에서 서버를 부르지 않는다.
- **오류와 취소 뒤에도 읽는다.** 오류 전에 찍은 줄도 보여야 하고, 읽어야 버퍼가 비워진다. 출력은 오류 문구보다 앞에 나온다.
- **매번 읽어 비운다.** Oracle·CUBRID는 `GET_LINE`이 읽은 줄을 서버 버퍼에서 빼고, Altibase는 읽으면서 `StringBuilder`를 비운다.
  그래서 문장마다 읽으면 다음 실행에 이전 줄이 다시 나오지 않고, 버퍼가 차거나 커지지 않는다.
- **설정을 따른다.** 읽기 도구의 `isServerOutputEnabled()`(Oracle `oracle.dbms.output`, Altibase `altibase.dbms.output`, CUBRID `pref.dbms.output`,
  모두 기본 켬)가 거짓이면 읽지 않는다. Oracle을 켜는 방식(`DBMS_OUTPUT.ENABLE(1000000)`)은 바꾸지 않았다.
- **설정을 바꾸면 열린 세션에도 적용한다(2026-11-08, Oracle).** 서버의 켜짐은 세션마다 있는데, 전에는 세션을 열 때만 설정에 맞췄다. 끄면 읽기만 멈추고
  서버는 계속 쌓아 100만 바이트에서 `ORU-10027`이 날 수 있었고, 켜면 다시 연결할 때까지 아무것도 나오지 않았다. 이제 실행 문맥이 마지막으로 보낸 상태를
  기억하고, 쿼리 창이 문장을 실행하기 전에 설정과 다르면 그 세션에 `ENABLE`/`DISABLE`을 보낸다. 바꾼 다음 문장부터 맞는다.
  - Options의 OK에서 모든 세션에 바로 보내지 않는다. 다른 스레드가 쓰는 세션(긴 쿼리, 디버거가 기다리게 한 대상 세션)에서는 JDBC 호출이 그 일이 끝날
    때까지 막히고, 디버거 대상 세션은 PL/SQL 호출마다 디버거를 기다린다. 문장을 실행할 스레드가 실행 직전에 보내면 이런 겹침이 없다.
  - `UTIL` 세션으로 보내므로 쿼리 관리자는 사용자의 트랜잭션 문장으로 세지 않는다(`QMUtils.isUserStatement`). `DBMS_OUTPUT.ENABLE`/`DISABLE`은
    트랜잭션을 시작하거나 끝내지 않는다.
  - 맞추는 것은 쿼리 창이 문장을 실행하는 세션뿐이다. 쿼리 창이 쓰지 않는 실행 문맥(디버거의 격리 문맥 등)은 세션을 열 때의 상태 그대로다.
    YashanDB는 `OracleDataSource`를 상속해 같다.
  - Altibase·CUBRID도 같은 방식이다(2026-11-08, evening #19). Altibase는 세션마다 드라이버 콜백을 등록하거나 지운다. 드라이버의
    `AltibaseConnection.registerMessageCallback`은 콜백이 바뀌면 서버에 메시지 전송 여부를 알리고(`null`이면 끔, `javap`로 확인), 콜백이 없으면 메시지를 버린다.
    끌 때는 데이터 원본의 버퍼도 비운다(꺼진 동안 아무도 읽지 않음). CUBRID는 세션마다 켠 버퍼 크기를 기억하고, 켜짐이나 크기가 다르면 `dbms_output.enable(크기)`,
    끄면 `dbms_output.disable()`(읽지 않은 줄도 버림, CUBRID 11.4 설명서 "System Package")을 보낸다. 그래서 Options > CUBRID가 더 이상 다시 연결을 묻지 않는다.
- **PRINT·NOTICE를 두 번 쓰지 않는다.** PostgreSQL 읽기 도구(`PostgreServerOutputReader`)와 기본 읽기 도구(`DefaultServerOutputReader`)는
  실행 결과나 문장의 `SQLWarning`을 되풀이할 뿐이다. 실행 결과와 문장을 `null`로 넘기면 아무것도 쓰지 않는다. SQL Server는 읽기 도구가 없다.
  경고는 지금처럼 `reportQueryResult`가 한 번만 쓴다.
- **빈 줄.** `PUT_LINE(NULL)`·`PUT_LINE('')`은 빈 줄로 쓴다(SQL*Plus와 같음). Altibase 읽기 도구는 버퍼가 비어 있어도 빈 글을 한 번 썼기 때문에,
  문장마다 빈 줄이 생기지 않도록 비어 있으면 쓰지 않게 고쳤다.
- **Altibase 설정이 꺼져 있을 때.** 예전 코드는 `enable` 인자와 관계없이 콜백을 등록했다. 설정이 꺼져 있으면 편집기가 읽지 않으므로 버퍼가 계속 커진다.
  콜백이 없으면 드라이버가 메시지를 버리므로(`Altibase.jdbc.driver.cm.CmChannel`이 `mMessageCallback == null`이면 메시지를 건너뜀, `javap`로 확인)
  꺼져 있을 때는 등록하지 않는다.

- **Altibase 기본값이 적용되지 않던 것.** 실행해 보니 Altibase `PRINTLN` 출력이 나오지 않았다. `altibase.dbms.output`을 작업 공간 설정 파일에
  직접 `true`로 넣자 나왔다. 원인은 `plugin.xml`의 `AltibasePreferencesInitializer`가 실행되지 않은 것이다. Eclipse는 번들의 기본값 노드를 처음 읽을 때
  그 번들의 초기화를 실행한다. Oracle·CUBRID는 번들 활성화 코드가 자기 노드를 읽지만(`OracleActivator`, `CubridActivator`가 `BundlePreferenceStore`를 만듦),
  Altibase 번들에는 활성화 코드가 없었다. 초기화가 있는데 활성화 코드가 없는 번들은 Altibase 하나뿐이다(`plugins/` 검색). 그래서 설정이 기본값 켬이 아니라
  꺼짐으로 읽혔다. 같은 원인으로 Altibase의 다른 기본값(`PREF_EXPLAIN_PLAN_TYPE`, `PREF_PLAN_PREFIX`)도 적용되지 않았다. 활성화 코드를 더해 함께 고쳐진다.
  쓰지 않는 메서드를 남기지 않도록 활성화 코드는 노드만 읽는다.

## upstream 대비 변경점

- upstream(SWT)의 Output 패널(`SQLEditorOutputViewer`, 심각도 필터, 자동 표시 설정 `OUTPUT_PANEL_AUTO_SHOW`)은 옮기지 않았다. 출력은 결과 영역의 Server Output 탭에 있다(2026-11-12까지 Messages 탭).
- SWT가 실행 중인 문장의 출력을 비동기로 읽던 부분(`isAsyncOutputReadSupported`, PostgreSQL의 긴 문장 중 `NOTICE`)은 옮기지 않았다. PostgreSQL `NOTICE`는
  문장이 끝난 뒤 경고로 나온다(전과 같음).
- `AltibaseDataSource`: 설정이 꺼져 있으면 콜백을 등록하지 않고, 빈 버퍼는 쓰지 않는다.
- `ext.altibase`에 번들 활성화 코드(`AltibaseActivator`)를 더했다.

## 남은 일과 한계

- Oracle은 Tools > Options > Oracle에서 켜고 끈다(2026-11-07, [swing-options.md](swing-options.md)). 열린 세션에는 다음 문장부터 적용된다(위 설계 결정).
  Altibase·CUBRID는 Tools > Options > Altibase·CUBRID와 그 연결의 연결별 값으로 켜고 끈다(2026-11-08, [swing-options.md](swing-options.md)).
  열린 세션에도 다음 문장부터 적용된다(위 설계 결정). CUBRID 시험 서버가 없어 CUBRID 경로는 코드로만 확인했다. 이미 켠 세션에 `ENABLE`을 다른 크기로
  다시 부를 때 버퍼 크기가 바뀌는지는 설명서에 없다.
- 쿼리 창 밖에서 같은 실행 문맥으로 PL/SQL을 실행하면(개체 편집기의 저장 등) 그 출력은 다음 쿼리 창 실행 때 함께 나온다. Altibase 버퍼는 데이터 원본 하나에
  하나라서, 예약 쿼리(격리 문맥)의 `PRINT`도 다음 쿼리 창 실행 때 나온다.
- Altibase 콜백 버퍼(`StringBuilder`)는 동기화하지 않는다(upstream 그대로). 드라이버는 문장을 실행하는 스레드에서 콜백을 부르므로 읽기와 겹치는 일은
  드물다. 같은 데이터 원본에서 두 문맥이 동시에 실행되면 겹칠 수 있다.
- 실행 중인 긴 PL/SQL의 출력은 문장이 끝난 뒤 한 번에 나온다(Oracle `DBMS_OUTPUT` 자체가 끝난 뒤에 읽는 구조).

## 확인 (2026-11-07, Windows, Docker 테스트 DB, 앱 사본으로 실행)

| 확인 | 결과 |
|---|---|
| Oracle `BEGIN DBMS_OUTPUT.PUT_LINE('x'); END;` | Messages 탭에 `x` 한 줄. 두 번 실행해도 매번 `x` 한 줄(이전 줄이 다시 나오지 않음) |
| Oracle 3,000줄 반복(줄마다 약 210바이트, 한 번에 약 63만 바이트) + `PUT_LINE(NULL)` + `'last'` | `line 1`부터 `line 3000`, 빈 줄, `last`가 모두 나옴. 같은 세션에서 두 번 연달아 실행해도(합계 100만 바이트 초과) `ORU-10027` 없이 둘 다 성공 = 매번 비워짐 |
| Oracle 출력 뒤 `RAISE_APPLICATION_ERROR` | `before error`가 오류 문구 앞에 나옴 |
| SQL Server `PRINT 'hello print'; SELECT 1 AS n;` | `hello print` 한 번 |
| PostgreSQL `DO $$ BEGIN RAISE NOTICE 'hello notice'; END $$;` | `hello notice` 한 번 |
| Altibase `EXEC PRINTLN('hello altibase');` | 활성화 코드 전: 나오지 않음(기본값 미적용). 설정 파일에 `altibase.dbms.output=true`를 넣으면 나옴. 활성화 코드 뒤: 설정 없이 나오고, 두 번 실행해도 한 줄 |
| CUBRID | 테스트 서버가 없어 실행하지 않음. 코드 경로(`CubridOutputReader.readServerOutput`)만 확인 |

회귀 테스트는 작성·실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 쿼리 창 Messages 탭의 메시지 순서, Altibase 연결(번들 활성화 코드 추가).

## 확인 (2026-11-08, evening #19, `chromite/followups-3` 빌드)

Altibase 7.3 Docker, 한 쿼리 창에서 `EXEC PRINTLN('…')`, Tools > Options > Altibase의 PRINT 칸만 바꿈(다시 연결하지 않음). 프로세스 안 드라이버로 조작했다.

| 확인 | 한국어 | 영어 |
|---|---|---|
| 켬 → 실행 | 출력 나옴 | `fu3 en on` 나옴 |
| 끔 → 실행 | 출력 없음 | 출력 없음 |
| 다시 켬 → 실행 | `fu3 on again`만 나옴(끈 동안의 줄은 나오지 않음) | `fu3 en on again`만 나옴 |
| 끈 채로 연결 끊고 새 쿼리 창(세션을 꺼진 상태로 엶) → 실행 → 켬 → 실행 | 처음은 출력 없음, 켠 뒤 `fu3 turned on without reconnect` 나옴(고치기 전에는 다시 연결할 때까지 나오지 않던 경우) | - |

CUBRID: 시험 서버가 없어 실행하지 않았다. 코드 경로(`CubridOutputReader.syncServerOutput` → `applyDbmsOutput`)만 확인했다. 캡처:
`D:\Tools\_scratch\chromite-followups-3\shots\alti-19-ko.png`, `alti-19-en.png`. 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능:
Altibase·CUBRID 쿼리 창의 문장 실행(문장 전에 상태 확인, 바뀐 경우만 드라이버 호출 또는 SQL 한 번), CUBRID 연결 설정 저장(다시 연결 질문이 없어짐).

## 확인 (2026-11-12, 앱 변경 #54, `chromite/result-area-2` 빌드)

이 PC의 PostgreSQL 17.11(임시 클러스터, `D:\Tools\pgsql-17`), 앱 사본, 프로세스 안 시험 에이전트(OS 입력 없음).

| 확인 | 결과 |
|---|---|
| `DO $$ … RAISE NOTICE 'hello notice'; RAISE INFO 'info line'; RAISE WARNING 'careful'; END $$; SELECT 1` | Server Output `hello notice`, `info line`, 탭에 점(Grid가 앞). Messages는 `careful`, 행 수, 실행 시간 |
| Server Output 탭을 고름 | 점이 지워짐 |
| 출력 없는 실행(`SELECT 2`) | Server Output 비어 있음, 점 없음, 탭은 그 자리 |
| `CREATE TABLE IF NOT EXISTS` 두 번 | 서버 NOTICE `relation "t54" already exists, skipping`이 Server Output |
| NOTICE 뒤 `RAISE EXCEPTION` | Server Output `before error`, Messages에 오류(오류 점·개수) |
| SQL Terminal 켬 | 터미널에 문장, 출력 줄, 메시지 순서로 씀 |

Oracle `DBMS_OUTPUT`, SQL Server `PRINT`·`RAISERROR`, Altibase `PRINT`, CUBRID는 이 PC에서 Docker가 꺼져 있어 실행하지 못했다. 읽는 코드(`readServerOutput`)는 바뀌지 않았고
쓰는 곳만 Server Output 탭으로 바뀌었다. 회귀 테스트는 실행하지 않았다(요청 없음).

## 변경 이력

- 2026-11-07 최초 작성: 쿼리 창이 문장마다 서버 출력을 읽어 Messages 탭에 씀, Altibase 읽기 도구 정리, Altibase 번들 활성화 코드로 기본값 초기화
  (`chromite/server-output`)
- 2026-11-08 Oracle DBMS_OUTPUT 설정을 바꾸면 열린 세션에도 다음 문장부터 적용(`DBCServerOutputReader.syncServerOutput`, `chromite/small-fixes-2`,
  [model-fixes.md](model-fixes.md) "작은 모델·안전·라이선스 수정"). "남은 일과 한계"의 설정 화면 항목 갱신.
- 2026-11-08 Altibase·CUBRID도 설정을 바꾸면 열린 세션에 다음 문장부터 적용(evening #19, `chromite/followups-3`). Altibase 콜백 등록·해제,
  CUBRID `ENABLE`/`DISABLE`과 버퍼 크기. 확인은 아래 "확인 (2026-11-08)".
- 2026-11-12 서버 출력을 Messages 대신 결과 영역의 Server Output 탭에 씀, 경고 중 PRINT·NOTICE를 출력으로 가름(`isServerOutput`), 새 출력 점(앱 변경 #54, `chromite/result-area-2`). 확인은 "확인 (2026-11-12)".
