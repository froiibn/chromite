# Swing 옵션(Tools > Options)과 SWT 설정 페이지

## 목적

SWT 제품은 설정 페이지 62개(Window > Preferences, 연결별 속성 페이지)로 동작을 바꿨다. Swing 제품에는 그중 일부만 화면이 있었다
([swt-parity-audit.md](swt-parity-audit.md) 3순위 "설정 화면", [A-platform.md](swt-parity/A-platform.md) §6).
이 문서는 SWT가 바꾸게 해 준 설정 중 **Swing 제품에서 아직 효과가 있는 것**을 찾아 Swing에 자리를 주고, 효과가 없는 키는 화면을 만들지 않는다는 원칙과
키마다의 결과를 적는다.

## 판단 방법

1. 기준 SWT 소스(`263b8ddb45`, 삭제 직전)의 모든 설정 페이지 클래스(`PrefPage*`, `*PreferencePage*`, 67개)에서 참조하는 상수를 값으로 풀어 키 목록을 만들었다.
   상수를 쓰지 않는 페이지(Query Manager, Data Transfer 등)와 다른 클래스에 위임한 키는 감사 표의 키를 더했다.
2. 키마다 지금 작업 트리(`plugins/`)에서 그 키나 상수를 읽는 곳을 찾았다(정의와 기본값 설정은 뺌). 읽는 곳이 SWT 번들뿐이었으면 지금은 0개다.
3. 읽는 곳이 있어도 그 경로가 Swing에서 돌지 않으면(예: 절전 감지, 스마트 커밋) "효과 없음"으로 보고 이유를 적었다.
4. 효과 있는 키 중 1–3순위(트랜잭션, 연결 유형, SQL 실행)와 결과 형식·Oracle을 이번에 화면에 넣었다. 나머지는 "다음 작업"이다.

도구(재현용, 저장소 밖): `D:\Tools\_scratch\chromite-p3-settings-pages\keyscan.py`(키 추출과 읽는 곳 검색), `gentable.py`(아래 부록 표 생성).

## 결과 요약

| 상태 | 키 수 | 뜻 |
|---|---|---|
| 추가함 | 22 | 이번에 Swing 화면을 만듦 |
| 이미 있음 | 7 | 전에 만든 Swing 화면이 같은 키를 씀 |
| 연결별 공급자 속성 | 7 | 연결 대화상자 Provider Properties 탭에서 이미 바꿀 수 있음 |
| 규칙상 두지 않음 | 2 | 스크립트 커밋 방식: 트랜잭션 안전 규칙에 어긋남 |
| 다음 작업 | 71 | Swing에서 읽지만 화면 없음(아래 "다음 작업") |
| 효과 없음 | 197 | Swing에서 읽는 곳이 없거나 경로가 돌지 않음. 화면을 만들지 않음 |

키가 아닌 설정(연결 유형, 연결별 격리 수준, 템플릿, AI 등)은 아래 페이지별 표에 적었다.

## 이번에 만든 화면

### Options > Query Execution (`SqlExecutionOptionsPage`)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| 문 구분 기호 | `script.sql.delimiter` | `SQLSyntaxManager.init` |
| 데이터베이스 자체 구분 기호 무시 | `script.sql.ignoreNativeDelimiter` | `SQLSyntaxManager.init`(GO 같은 배치 구분 기호도 꺼짐) |
| 빈 줄로 문 끝내기(자동 / 항상 / 사용 안 함) | `script.sql.delimiter.blank` | `SQLSyntaxManager.init` → `SQLScriptParser`. 실행(F5)은 스크립트 모드라 "자동"일 때만 빈 줄에서 나눈다. "항상"은 커서 위치의 문(자동완성)에만 적용된다(업스트림 규칙, `SQLScriptParser` 136행) |
| 보내기 전에 문 끝의 구분 기호 제거 | `script.sql.query.remove.trailing.delimiter` | `SQLScriptParser` |
| 이름 있는 매개 변수, 접두사 | `sql.parameter.enabled`, `sql.parameter.prefix` | `SQLSyntaxManager.init` |
| 이름 없는 매개 변수(?), 기호 | `sql.parameter.anonymous.enabled`, `sql.parameter.mark` | `SQLSyntaxManager.init` |
| 코드 블록 안의 매개 변수 | `sql.parameter.ddl.enabled` | `DefaultSQLQueryParameterParser` |
| 변수 | `sql.variables.enabled` | `SQLSyntaxManager.init` |
| 명령 접두사 | `sql.command.prefix` | `SQLSyntaxManager.init` |
| 오류가 나면: 스크립트 중지 / 그 문을 건너뛰고 계속 | `script.error.handling` | `SqlEditorPanel` → `SQLQueryExecuteJob.setErrorHandling` |

- 쿼리 창은 실행할 때마다 파서 문맥을 새로 만든다(`SQLScriptBatches.createParserContext`). 그래서 다시 연결하지 않아도 다음 실행부터 적용된다.
- 값은 플랫폼 설정 저장소에 쓴다. 연결의 설정 저장소(`DataSourcePreferenceStore`)가 이것을 부모로 가지므로 연결별 값을 두지 않은 모든 연결에 적용된다.
  연결별로 다른 값을 주는 화면은 아직 없다(다음 작업).
- 빈 칸은 기본값으로 되돌린다. 빈 접두사·기호는 모든 문을 매개 변수나 명령으로 보이게 만들기 때문이다.

#### 오류 처리와 트랜잭션 (고친 버그)

SWT의 오류 처리는 "멈추고 롤백"(기본) / "멈추고 커밋" / "무시"였다. 실행 엔진 기본값도 `STOP_ROLLBACK`이다.
Swing 쿼리 창은 이 값을 넘기지 않아 엔진 기본값을 썼고, 그래서 **수동 커밋 세션에서 스크립트가 오류로 멈추면 그 세션의 열린 트랜잭션을 묻지 않고 롤백했다**
(`SQLQueryExecuteJob` 스크립트 끝: `lastError != null`이고 `STOP_ROLLBACK`이면 `txnManager.rollback`). 오류 앞의 문장뿐 아니라 그 전에 실행해 둔 변경까지 사라진다.
트랜잭션 안전 규칙(조용히 커밋·롤백하지 않음)에 어긋난다.

- 고침: 쿼리 창이 엔진에 오류 처리를 직접 넘긴다(`SqlExecutionOptionsPage.getEngineErrorHandling`). 멈춤은 `STOP_COMMIT`을 넘긴다. 쿼리 창의 커밋 방식은 늘
  `NO_COMMIT`이라 엔진은 멈춘 스크립트 끝에서 커밋도 롤백도 하지 않는다. 무시는 `IGNORE`다(실패한 문장을 건너뛰고, 끝에서 커밋·롤백하지 않음).
- 그래서 화면에는 "멈춤"과 "건너뛰고 계속" 둘만 둔다. 저장 값은 SWT와 같은 이름(`STOP_ROLLBACK`, `IGNORE`)이고, `STOP_*`은 모두 멈춤으로 읽는다.
- 열린 트랜잭션은 쿼리 상태 줄에 보이고([idle-transactions.md](idle-transactions.md)) 사용자가 커밋하거나 롤백한다.
- 같은 엔진을 쓰는 예약 쿼리(`QueryScheduler`)는 실행마다 격리된 문맥을 열고 닫으므로 바꾸지 않았다.
- 범위 밖에서 확인한 것(고치지 않음): PostgreSQL은 트랜잭션 안에서 오류가 나면 서버가 그 트랜잭션을 중단 상태로 둔다. 그 뒤 문장이
  "current transaction is aborted"로 실패하면 모델의 실행 복구(`DBExecUtils.tryExecuteRecover`, `execute.recover.enabled` 기본 켬)가
  `InvalidateJob.invalidateTransaction`으로 묻지 않고 롤백한 뒤 다시 실행한다. 서버에서 이미 커밋할 수 없는 트랜잭션이라 잃는 데이터는 없지만 알림 없이 끝난다.

### Options > General > Query Results (기존 절에 행 추가)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| JDBC 가져오기 크기 사용 | `resultset.fetch.size` | `DBExecUtils.setStatementFetchSize`(문장마다) |
| 이진 값(텍스트 / 16진수 / Base64) | `resultset.binary.representation` | `DBValueFormatting.getBinaryPresentation` |
| 이진 값 길이(결과 표에 보이는 바이트 수) | `resultset.binary.stringMaxLength` | `DBValueFormatting.formatBinaryString`(UI 형식) |
| 숫자를 데이터베이스 형식으로 표시 | `resultset.format.numeric.native` | `DataSourceDescriptor.isUseNativeNumericFormat` |
| 숫자에 과학적 표기법 사용 | `resultset.format.numeric.scientific` | `DataSourceDescriptor.isUseScientificNumericFormat` |

결과 표는 가져올 때 값을 표시 글자로 바꿔 두므로(`ResultSetGridReceiver`) 다음 실행부터 보인다.

### Options > Connection Types (`ConnectionTypesPage`)

SWT `PrefPageConnectionTypes`의 Swing판이다. 지금까지 연결 유형은 기본값(개발·테스트·운영)으로만 있었고 고를 수만 있었다.

| 항목 | `DBPConnectionType` | 읽는 곳 |
|---|---|---|
| 이름, 설명 | `name`, `description` | 연결 대화상자 연결 유형 콤보 |
| 색, 다크 테마 색, 색 없음 | `colorLight`, `colorDark` | `ConnectionColors`(개체 탐색기·등록된 서버 알약, 쿼리 상태 줄) |
| 기본으로 자동 커밋 | `autocommit` | `DataSourceDescriptor.isDefaultAutoCommit`(연결이 자체 값을 정하지 않았을 때) |
| SQL 실행 확인 | `confirmExecute` | `SQLQueryExecuteJob`(SELECT가 아닌 문), Chromite Agent 도구 정책 |
| 데이터 변경 확인 | `confirmDataChange` | `ResultSetGrid.isSavePreviewRequired` |
| 유휴 트랜잭션 알림, 시간(분) | `autoCloseTransactions`, `closeIdleTransactionPeriod` | `IdleTransactionMonitor`(연결이 자체 값을 정하지 않았을 때) |
| 유휴 연결 닫기, 시간(초) | `autoCloseConnections`, `closeIdleConnectionPeriod` | `DataSourceMonitorJob.getDisconnectTimeoutSeconds`, 새 연결의 기본값 |
| 제한(데이터 편집, 구조 편집, 스크립트 실행, 데이터 가져오기) | `modifyPermission` | `hasModifyPermission`(DBExecUtils, 데이터 전송, `SqlExecutionSafety`) |

- 새로 만들기·삭제: 미리 정의된 세 유형은 지울 수 없다. 지운 유형을 쓰던 연결은 새 연결의 기본 유형(없으면 개발)으로 바뀌고 저장된다.
  지우기 전에 그 유형을 쓰는 연결 수와 바뀔 유형을 보여 주고 묻는다.
- 저장: OK에서 SWT `performOk`와 같은 순서로 원본에 옮기고 `DataSourceProviderRegistry.saveConnectionTypes`로 저장한다. 바뀐 유형을 쓰는 연결은
  `updateDataSources`로 다시 저장하고(지운 유형의 연결, `data-sources.json`의 유형 사본) 갱신 이벤트를 보내 색이 바로 다시 그려진다.
- **트랜잭션 안전**: 유형의 자동 커밋 기본값을 바꾸거나 유형을 지우면 연결의 실제 커밋 방식이 바뀔 수 있다. 저장 전에 연결된 연결마다 실제 값을 기억하고,
  바뀐 연결마다 `AutoCommitApplier`(연결 대화상자의 Auto-commit과 같은 경로, #80)로 열린 세션에 적용한다. 자동 커밋으로 바뀌는 세션에 커밋하지 않은 변경이
  있으면 커밋하고 적용 / 롤백하고 적용 / 취소를 묻는다. 취소하면 열린 세션은 그대로이고 다음 연결부터 적용된다. 질문은 한 연결씩 차례로 한다.
- 알려진 동작(업스트림과 같음, 고치지 않음): 저장하면 미리 정의된 유형의 이름도 그때의 화면 언어 글자로 저장된다(`saveConnectionTypes`의 `name`).
  그 뒤 다른 언어로 실행하면 저장할 때의 이름(예: 한국어 화면에서 "Development")이 보인다. 실행 확인 중 영어 화면에서 저장한 뒤 한국어로 실행해 확인했다.
- 두지 않은 것:
  - 스마트 커밋(`smartCommit`, `smartCommitRecover`): Swing 쿼리 창은 엔진 처리기의 `isSmartAutoCommit`을 켜지 않고, `DBExecUtils.recoverSmartCommit`을 부르는 곳이 없다.
    효과가 없어 화면에 두지 않았다. 저장된 값은 그대로 유지한다.
  - 유휴 트랜잭션 자동 롤백의 유형별 기본값: 자동 롤백은 사용자가 연결마다 켜는 것이 사용자 결정이다([idle-transactions.md](idle-transactions.md)). 유형에 두려면
    모델에 새 필드가 필요해 이번에는 하지 않았다.

### Options > Oracle (`OracleOptionsPage`)

| 항목 | 키(`OracleConstants`) | 읽는 곳 |
|---|---|---|
| 실행 계획 테이블(비우면 PLAN_TABLE, TOAD_PLAN_TABLE) | `oracle.explain.table` | `OracleDataSource.getPlanTableName`(연결마다 처음 한 번 찾고 기억 → 다시 연결한 뒤 적용) |
| 메시지 탭에 DBMS_OUTPUT 표시 | `oracle.dbms.output` | `OracleOutputReader.isServerOutputEnabled`(쿼리 창은 문장마다 이 값을 보고 읽음. 서버의 `DBMS_OUTPUT.ENABLE`·`DISABLE`은 세션을 열 때 함 → 끄면 지금 세션은 다시 연결할 때까지 서버 버퍼에 쌓임) |
| 스크립트의 JDBC 이스케이프 처리 끄기 | `oracle.disable.script.escape` | `OracleSQLDialect.isDisableScriptEscapeProcessing` |
| 테이블의 ROWID 열 표시 | `oracle.support.rowid` | `OracleTable.hasRowIdPseudoAttribute` |
| 모든 동의어 읽기 | `oracle.read.all.synonyms` | `OracleSchema` 동의어 조회 |

- 기본값(앞의 넷 켬)은 Oracle 번들의 설정 초기화가 넣고, 초기화는 번들이 시작될 때 돈다. Oracle 연결을 아직 쓰지 않았으면 번들이 시작되지 않아 꺼짐으로 읽히므로,
  탭이 값을 읽기 전에 번들의 클래스를 불러 시작시킨다(지연 활성화).
- SWT Oracle 페이지의 동의어·시퀀스 메타데이터 검색, DATE를 DATE로 보이기는 공급자 속성이라 연결 대화상자 Provider Properties에서 이미 바꿀 수 있다.

### 연결 대화상자 > Connection Properties > 격리 수준

SWT `ConnectionPageInitialization`의 트랜잭션 격리 수준(`DBPConnectionBootstrap.defaultTransactionIsolation`, `JDBCExecutionContext.connect`가 세션을 열 때 적용).

- 목록: 연결되어 있으면 그 데이터베이스가 지원하는 수준(`getSupportedTransactionsIsolation`, "None" 제외), 연결되어 있지 않으면 JDBC 네 수준.
  SWT는 연결되어 있지 않으면 콤보를 껐다. Swing은 드라이버가 거부한 수준을 모델이 무시하므로(디버그 로그만) 연결 전에도 고를 수 있게 했다.
- 적용: 세션을 열 때만 적용된다. 연결된 연결에서 바꾸면 기존 "다시 연결할까요" 질문이 나오고, 다시 연결은 열린 트랜잭션을 먼저 묻는다
  (`ExitTransactionCheck.confirmDisconnect`, 트랜잭션 안전 규칙). 열린 세션에 바로 적용하지 않은 이유: JDBC는 트랜잭션 중 격리 수준 변경의 결과를 드라이버에 맡기고,
  SWT도 다시 연결로 적용했다.

## 페이지별 정리

| SWT 페이지 | 결과 |
|---|---|
| Transactions | 효과 있는 키 없음(아래). 자동 커밋 기본값·유휴 트랜잭션 알림은 연결 유형으로, 격리 수준은 연결 대화상자로. "끊을 때 트랜잭션 처리"는 설정이 없고 끊기·다시 연결은 늘 묻는다(#97) |
| Connection Types | **추가함**(Options > Connection Types). 스마트 커밋만 뺌 |
| SQL Processing | **추가함**(Options > Query Execution): 구분 기호 4, 매개 변수 7, 오류 처리. 커밋 방식 2키는 규칙상 두지 않음, 나머지 12키는 효과 없음 |
| Data Editor, Binary Editor, Data Formats | 일부 **추가함**(Query Results): 가져오기 크기, 이진 값 2, 숫자 형식 2. 형식 프로필(날짜·숫자 패턴)은 다음 작업 |
| Oracle | **추가함**(Options > Oracle) 5키, 공급자 속성 3키 |
| PostgreSQL | 4키는 공급자 속성(이미 있음), `show-database-statistics`는 다음 작업 |
| Errors and Timeouts, Client Identification, Metadata, Drivers, Classpath, Query Manager, Error Logs, Navigator(정렬), Notifications(긴 작업 알림), Data Transfer(매핑 3키), Dictionaries, Code Completion(4키), Altibase, CUBRID, Mimer | 다음 작업(키는 아래 목록) |
| Maven | 다음 작업: 저장소 목록은 키가 아니라 `maven-repositories.xml`(`MavenRegistry`)에 저장되고 드라이버 내려받기가 읽는다 |
| Network Profiles | 다음 작업: 전역·프로젝트 네트워크 프로필 편집(지금은 연결이 쓰는 프로필을 읽기 전용으로 보임) |
| Templates, AI | Swing 자체 저장으로 이미 있음(Options > Templates, Chromite Agent) |
| User Interface, Editors, Toolbar Customization, Accessibility, Confirmations, Miscellaneous, Expert Settings, GIS, Appearance, Grid, Plain text, ERD, SQL Editor, Code Editor, SQL Dialects, Scripts, macOS Mouse, Project Resources | 효과 없음: 키를 읽던 SWT 코드가 지워졌다. Swing에 같은 기능이 생기면 그때 그 기능의 키를 정한다 |
| DataDam, Projects | 의도적 제외에 준함(삭제 전에도 어떤 제품에도 없던 기능) |

## 다음 작업 (키 목록)

Swing 제품이 읽지만 화면이 없는 키다. 우선순위 순서로 적었다.

1. 연결별 값: 위 화면은 모두 전역 값이다. SWT의 연결별 속성 페이지(`DataSourcePreferenceStore`)에 해당하는 화면이 없다.
2. 데이터 형식 프로필: 날짜·시간·숫자 패턴(`DataFormatterProfile`, 키 `dataformat.*`), 프로필 고르기.
3. Errors and Timeouts: `connection.open.timeout`, `connection.close.timeout`, `connection.validation.timeout`, `execute.recover.enabled`,
   `execute.recover.retryCount`, `execute.cancel.checkTimeout`, `query.rollback-on-error`.
4. Metadata: `database.meta.casesensitive`, `database.meta.extra.ddl.info`, `database.meta.server.side.filters`, `database.props.expensive`,
   `database.meta.disableAdditionalRead`.
5. Data Editor·Binary Editor 나머지: `resultset.maxrows.sql`, `resultset.order.mode`, `resultset.order.policy`, `resultset.column.label.ignore`,
   `sql.query.filter.force.subselect`, `resultset.autofetch.next.segment`, `resultset.transform.complex.type`,
   `resultset.reference.value.description.column.patterns`, `content.memory.maxsize`, `content.hex.encoding`, `content.cache.clob`, `content.cache.blob`,
   `content.cache.maxsize`.
6. Client Identification: `database.meta.client.name.disable`, `database.meta.client.name.override`, `database.meta.client.name.value`,
   `database.connect.processEnvVars`.
7. Connections: `connection.sanitizeDriverProperties`, `connection.shellAuthModelEnabled`, `connections.useWinTrustStoreType`. General: `java.client.timezone`(적용 코드 S12와 함께).
8. Drivers·Classpath·Maven: `ui.drivers.home`, `ui.drivers.sources`, `ui.drivers.version.update`, `ui.proxy.host`, `ui.proxy.port`, `ui.proxy.user`(+비밀번호),
   `ui.drivers.global.libraries`, `maven-repositories.xml`.
9. Query Manager: `qm.storeLogs`, `qm.historyDays`, `qm.logDirectory`, `qm.objectTypes`, `qm.queryTypes`. Error Logs: `logs.files.output.maxSize`, `logs.files.output.maxCount`.
10. Navigator: `navigator.sort.case.insensitive`, `navigator.sort.case.insensitive.ignore.case`, `navigator.sort.forlers.first`, `navigator.show.folder.placeholders`.
11. Notifications: `agent.long.operation.notify`, `agent.long.operation.timeout`. Data Transfer: `nameCaseMapping`, `replaceMapping`, `maxTypeLengthMapping`.
12. Code Completion: `SQLEditor.ContentAssistant.activate.hippie`, `SQLEditor.ContentAssistant.proposals.short.name`,
    `SQLEditor.ContentAssistant.proposals.long.name`, `sql.proposals.insert.table.alias`.
13. DB별: Altibase `altibase.dbms.output`, `altibase.explain.plan.type`, `altibase.plan.prefix`; CUBRID `pref.dbms.output`, `pref.dbms.output.buffer.size`;
    Mimer `mimer.confirm.drop_cascade.*`, `mimer.gis.*` 4개; PostgreSQL `show-database-statistics`, `postgresql.dd.*`의 전역 기본값.
14. 효과 없는 키 중 Swing 기능이 생기면 다시 볼 것: `statement.timeout`(문장 시간 제한), 스마트 커밋, 절전 때 연결 닫기(`connection.closeOnSleep`, A-S8).

## 실행 확인 (2026-11-07)

이 브랜치의 빌드를 작업 공간 사본으로 실행하고, 앱 안에서 Swing API로 조작하는 시험 도구로 확인했다(OS 입력 없음, 창 단위 화면).
증거: `D:\Tools\_scratch\chromite-p3-settings-pages\run\agent\shots\`, 각 단계 기록은 같은 폴더의 `s*.txt.log`.

| 확인 | 결과 |
|---|---|
| 연결 유형 색: 개발 유형에 색을 정하고 OK | 등록된 서버·개체 탐색기의 개발 유형 연결 알약이 바로 그 색이 됨(`07-main-tree-dev-color.png`) |
| 유형의 자동 커밋 끄기(연결된 PostgreSQL) | 열린 세션이 바로 수동 커밋으로 바뀜(상태 줄 "Manual commit") |
| 수동 커밋에서 `INSERT`, 이어서 `INSERT; SELECT 없는 테이블; INSERT` 실행(MariaDB) | 스크립트가 오류에서 멈추고 세 번째 문장은 실행 안 됨. 같은 세션의 `count(*)` = 2, 상태 줄 "Open transaction". 고치기 전 코드(엔진 기본값 `STOP_ROLLBACK`)는 여기서 롤백한다(코드로 확인, 고치기 전 빌드는 실행하지 않음) |
| 유형의 자동 커밋 다시 켜기(열린 트랜잭션 두 연결) | 연결마다 차례로 "커밋하고 적용 / 롤백하고 적용 / 취소" 질문(`10-…`, `11-…png`). 롤백을 고르자 `count(*)` = 0, "Auto-commit" |
| 오류가 나면 = 건너뛰고 계속 | `SELECT 1; SELECT 없는 테이블; SELECT 3` → 결과 표 두 개(a, c)와 오류 메시지 |
| 빈 줄로 문 끝내기 = 자동 / 사용 안 함 | `SELECT 10 AS x` 빈 줄 `SELECT 20 AS y`(구분 기호 없음): 자동은 결과 두 개, 사용 안 함은 한 문장으로 보내 구문 오류 |
| 이진 값 = 16진수 | `CAST('abc' AS BINARY(3))`: 텍스트일 때 `abc`, 16진수로 바꾼 뒤 `616263` |
| 격리 수준 = Serializable(연결된 MariaDB) | 저장하자 다시 연결 질문, 다시 연결 뒤 `@@tx_isolation`이 `REPEATABLE-READ`에서 `SERIALIZABLE`로 |
| Oracle DBMS_OUTPUT 끄기 | 켜져 있을 때 메시지 탭에 `PUT_LINE` 글이 나오고, 끈 뒤 같은 블록은 글 없이 "Commands completed successfully." |

## upstream 대비 변경점

- 쿼리 창 오류 처리에서 `STOP_ROLLBACK`(엔진 기본값)을 쓰지 않는다. 멈춘 스크립트가 열린 트랜잭션을 롤백하지 않는다.
- 스크립트 커밋 방식 설정(끝에서 커밋, N줄마다 커밋, 문장마다 자동 커밋)을 두지 않는다. 쿼리 창은 늘 `NO_COMMIT`이다.
- 연결 유형 편집에 스마트 커밋이 없다. 연결 유형을 바꿔 연결의 커밋 방식이 바뀌면 열린 세션에 적용하고, 커밋하지 않은 변경이 있으면 묻는다(SWT는 다시 연결할 때 적용).
- 격리 수준을 연결 전에도 고를 수 있다(SWT는 연결되어 있을 때만).

## 변경 이력

- 2026-11-07 최초 작성: SWT 설정 페이지 키 전수와 Swing 효과 판정, Options > Query Execution·Connection Types·Oracle 탭, Query Results의 가져오기 크기·이진 값·숫자 형식,
  연결 대화상자의 격리 수준. 오류로 멈춘 스크립트가 수동 커밋 세션의 열린 트랜잭션을 묻지 않고 롤백하던 것을 고침.

## 부록: 키별 상태

`263b8ddb45`의 설정 페이지 키(페이지 클래스가 참조하는 상수와 감사 표의 키). 페이지 id, 링크, 변수 이름처럼 설정이 아닌 문자열은 뺐다.

| SWT 페이지 | 키 | Swing 상태 |
|---|---|---|
| General | `java.client.timezone` | 일부만 읽음(ClickHouse), 시간대 적용 코드 없음(S12) → 다음 작업 |
| General | `ui.auto.update.check` | 효과 없음(읽는 곳 없음) |
| User Interface | `ui.showZoomRestartPrompt` | 효과 없음(읽는 곳 없음) |
| User Interface | `ui.statusBar.show.breadcrumbs` | 효과 없음(읽는 곳 없음) |
| User Interface | `ui.statusBar.show.statusLine` | 효과 없음(읽는 곳 없음) |
| User Interface | `ui.use.redirect.auth` | 효과 없음(읽는 곳 없음) |
| Editors | `navigator.editor.full-name` | 효과 없음(읽는 곳 없음) |
| Editors | `navigator.editor.show.preview` | 효과 없음(읽는 곳 없음) |
| Editors | `navigator.refresh.editor.open` | 효과 없음(읽는 곳 없음) |
| Editors | `navigator.sync.editor.datasource` | 효과 없음(읽는 곳 없음) |
| Editors | `ui.editors.disconnect-on-editors-close` | 효과 없음(읽는 곳 없음) |
| Editors | `ui.editors.keep-editors-on-disconnect` | 효과 없음(읽는 곳 없음) |
| Editors | `ui.editors.reopen-after-restart` | 효과 없음(읽는 곳 없음) |
| Query Manager | `qm.historyDays` | 읽음, 화면 없음 → 다음 작업 |
| Query Manager | `qm.logDirectory` | 읽음, 화면 없음 → 다음 작업 |
| Query Manager | `qm.maxEntries` | 효과 없음(읽는 곳 없음) |
| Query Manager | `qm.objectTypes` | 읽음, 화면 없음 → 다음 작업 |
| Query Manager | `qm.queryTypes` | 읽음, 화면 없음 → 다음 작업 |
| Query Manager | `qm.storeLogs` | 읽음, 화면 없음 → 다음 작업 |
| Accessibility | `screen.reader.accessibility` | 효과 없음(읽는 곳 없음) |
| Accessibility | `screen.reader.force.focus` | 효과 없음(읽는 곳 없음) |
| Errors and Timeouts | `connection.close.timeout` | 읽음, 화면 없음 → 다음 작업 |
| Errors and Timeouts | `connection.open.timeout` | 읽음, 화면 없음 → 다음 작업 |
| Errors and Timeouts | `connection.validation.timeout` | 읽음, 화면 없음 → 다음 작업 |
| Errors and Timeouts | `execute.cancel.checkTimeout` | 읽음, 화면 없음 → 다음 작업 |
| Errors and Timeouts | `execute.recover.enabled` | 읽음, 화면 없음 → 다음 작업 |
| Errors and Timeouts | `execute.recover.retryCount` | 읽음, 화면 없음 → 다음 작업 |
| Errors and Timeouts | `query.rollback-on-error` | 읽음, 화면 없음 → 다음 작업 |
| Error Logs | `logs.debug.enabled` | 효과 없음(읽는 곳 없음) |
| Error Logs | `logs.debug.location` | 효과 없음(읽는 곳 없음) |
| Error Logs | `logs.files.output.maxCount` | 읽음, 화면 없음 → 다음 작업 |
| Error Logs | `logs.files.output.maxSize` | 읽음, 화면 없음 → 다음 작업 |
| Connections | `connection.closeOnSleep` | 효과 없음: 읽는 경로(절전 감지)가 Swing에 없음(A-S8) |
| Connections | `connection.sanitizeDriverProperties` | 읽음, 화면 없음 → 다음 작업 |
| Connections | `connection.shellAuthModelEnabled` | 읽음, 화면 없음 → 다음 작업 |
| Connections | `connections.useWinTrustStoreType` | 읽음(시작 때, S6), 화면 없음 → 다음 작업 |
| Connections | `navigator.settings.default.connectionPattern` | 효과 없음(읽는 곳 없음) |
| Client Identification | `database.connect.processEnvVars` | 읽음, 화면 없음 → 다음 작업 |
| Client Identification | `database.meta.client.name.disable` | 읽음, 화면 없음 → 다음 작업 |
| Client Identification | `database.meta.client.name.override` | 읽음, 화면 없음 → 다음 작업 |
| Client Identification | `database.meta.client.name.value` | 읽음, 화면 없음 → 다음 작업 |
| Transactions | `transaction.auto.close.enabled` | 효과 없음: #97에서 키를 지움. Options > Connection Types와 연결 대화상자의 유휴 트랜잭션 알림으로 바뀜 |
| Transactions | `transaction.auto.close.ttl` | 효과 없음: 위와 같음 |
| Transactions | `transaction.ddl.behavior` | 효과 없음(읽는 곳 없음) |
| Transactions | `transaction.show.notifications` | 효과 없음(읽는 곳 없음) |
| Transactions | `transaction.smart.commit` | 효과 없음: 쿼리 창이 스마트 커밋을 켜지 않음(`isSmartAutoCommit` 기본값), `recoverSmartCommit` 호출 없음 |
| Transactions | `transaction.smart.commit.recover` | 효과 없음: 위와 같음 |
| Metadata | `database.meta.casesensitive` | 읽음, 화면 없음 → 다음 작업 |
| Metadata | `database.meta.disableAdditionalRead` | 읽음(자동완성), 화면 없음 → 다음 작업 |
| Metadata | `database.meta.extra.ddl.info` | 읽음, 화면 없음 → 다음 작업 |
| Metadata | `database.meta.separate.connection` | 이미 있음: 연결 대화상자(내부에서 읽기만, 메타데이터 별도 연결) |
| Metadata | `database.meta.server.side.filters` | 읽음, 화면 없음 → 다음 작업 |
| Metadata | `database.props.expensive` | 읽음, 화면 없음 → 다음 작업 |
| Drivers | `driver.selector.showCommercialDrivers` | 효과 없음(읽는 곳 없음) |
| Drivers | `ui.drivers.home` | 읽음, 화면 없음 → 다음 작업 |
| Drivers | `ui.drivers.offline` | 이미 있음: 드라이버 관리자 |
| Drivers | `ui.drivers.sources` | 읽음, 화면 없음 → 다음 작업 |
| Drivers | `ui.drivers.version.update` | 읽음, 화면 없음 → 다음 작업 |
| Drivers | `ui.proxy.host` | 읽음, 화면 없음 → 다음 작업 |
| Drivers | `ui.proxy.port` | 읽음, 화면 없음 → 다음 작업 |
| Drivers | `ui.proxy.user` | 읽음, 화면 없음 → 다음 작업 |
| Classpath | `ui.drivers.global.libraries` | 읽음, 화면 없음 → 다음 작업 |
| Navigator | `navigator.color.nodes.all` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.connection.doubleClick` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.editor.showGrid` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.expand.on.connect` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.group.by.driver` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.long.list.fetch.size` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.object.defaultEditorPage` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.object.doubleClick` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.restore.filters` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.restore.state.depth` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.child.count` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.connection.host` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.folder.placeholders` | 읽음, 화면 없음 → 다음 작업 |
| Navigator | `navigator.show.node.actions` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.objects.description` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.objects.tips` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.statistics.info` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.tooltips` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.tooltips.file.contents` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.sort.case.insensitive` | 읽음, 화면 없음 → 다음 작업 |
| Navigator | `navigator.sort.case.insensitive.ignore.case` | 읽음, 화면 없음 → 다음 작업 |
| Navigator | `navigator.sort.forlers.first` | 읽음, 화면 없음 → 다음 작업 |
| Confirmations | `com.seizia.chromite.core.confirm.` | 효과 없음: Swing 확인 대화상자는 이 키를 읽지 않음(A-C27) |
| Notifications | `agent.long.operation.notify` | 읽음, 화면 없음 → 다음 작업 |
| Notifications | `agent.long.operation.timeout` | 읽음, 화면 없음 → 다음 작업 |
| Notifications | `notifications.closeDelay` | 효과 없음(읽는 곳 없음) |
| Notifications | `notifications.enabled` | 효과 없음(읽는 곳 없음) |
| Notifications | `notifications.soundEnabled` | 효과 없음(읽는 곳 없음) |
| Notifications | `notifications.soundVolume` | 효과 없음(읽는 곳 없음) |
| Miscellaneous | `ui.show.holiday.decorations` | 효과 없음(읽는 곳 없음) |
| Expert Settings | `connections.debugNetworkConnections` | 효과 없음(읽는 곳 없음) |
| Expert Settings | `connections.preferredIPAddresses` | 효과 없음: 적용 코드가 Swing에 없음(S13) |
| Expert Settings | `connections.preferredIPType` | 효과 없음: 적용 코드가 Swing에 없음(S13) |
| Data Transfer | `captureUnsavedTaskLogs` | 효과 없음(읽는 곳 없음) |
| Data Transfer | `fallbackOutputDirectory` | 효과 없음(읽는 곳 없음) |
| Data Transfer | `maxTypeLengthMapping` | 읽음, 화면 없음 → 다음 작업 |
| Data Transfer | `nameCaseMapping` | 읽음, 화면 없음 → 다음 작업 |
| Data Transfer | `reconnectToLastDatabase` | 효과 없음(읽는 곳 없음) |
| Data Transfer | `replaceMapping` | 읽음, 화면 없음 → 다음 작업 |
| GIS Viewer | `gis.view.defaultSRID` | 효과 없음(읽는 곳 없음) |
| GIS Viewer | `gis.view.maxObjectsRender` | 효과 없음(읽는 곳 없음) |
| GIS Viewer | `gis.view.minZoomLevel` | 효과 없음(읽는 곳 없음) |
| Altibase | `altibase.dbms.output` | 읽음, 화면 없음 → 다음 작업 |
| Altibase | `altibase.explain.plan.type` | 읽음, 화면 없음 → 다음 작업 |
| Altibase | `altibase.plan.prefix` | 읽음, 화면 없음 → 다음 작업 |
| CUBRID | `pref.dbms.output` | 읽음, 화면 없음 → 다음 작업 |
| CUBRID | `pref.dbms.output.buffer.size` | 읽음, 화면 없음 → 다음 작업 |
| Mimer SQL | `mimer.confirm.drop_cascade.` | 읽음, 화면 없음 → 다음 작업 |
| Mimer SQL | `mimer.gis.coordinate.auto_map` | 읽음, 화면 없음 → 다음 작업 |
| Mimer SQL | `mimer.gis.grid_point_text` | 읽음, 화면 없음 → 다음 작업 |
| Mimer SQL | `mimer.gis.latlong.auto_decimal` | 읽음, 화면 없음 → 다음 작업 |
| Mimer SQL | `mimer.gis.location.auto_map` | 읽음, 화면 없음 → 다음 작업 |
| Oracle | `oracle.dbms.output` | **추가함**: Options > Oracle |
| Oracle | `oracle.disable.script.escape` | **추가함**: Options > Oracle |
| Oracle | `oracle.explain.table` | **추가함**: Options > Oracle |
| Oracle | `oracle.meta-search-in-sequences` | 연결별 공급자 속성: 연결 대화상자 Provider Properties |
| Oracle | `oracle.meta-search-in-synonyms` | 연결별 공급자 속성: 연결 대화상자 Provider Properties |
| Oracle | `oracle.read.all.synonyms` | **추가함**: Options > Oracle |
| Oracle | `oracle.show-date-as-date` | 연결별 공급자 속성: 연결 대화상자 Provider Properties |
| Oracle | `oracle.support.rowid` | **추가함**: Options > Oracle |
| PostgreSQL | `postgresql.dd.plain.string` | 연결별 공급자 속성: 연결 대화상자 Provider Properties |
| PostgreSQL | `postgresql.dd.tag.string` | 연결별 공급자 속성: 연결 대화상자 Provider Properties |
| PostgreSQL | `read-keys-with-columns` | 연결별 공급자 속성: 연결 대화상자 Provider Properties |
| PostgreSQL | `replace-legacy-timezone` | 연결별 공급자 속성: 연결 대화상자 Provider Properties |
| PostgreSQL | `show-database-statistics` | 읽음, 화면 없음 → 다음 작업 |
| Data Editor | `keep.statement.open` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.autofetch.next.segment` | 효과 있음, 화면 없음(Swing이 SWT 기본값 켬으로 읽음) → 다음 작업 |
| Data Editor | `resultset.automatic.row.count` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.cancel.timeout` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.column.label.ignore` | 읽음, 화면 없음 → 다음 작업 |
| Data Editor | `resultset.confirm.beforeSave` | 이미 있음: Options > General > Query Results |
| Data Editor | `resultset.datetime.editor` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.disableIfKeyMissing` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.key.use_all_columns` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.new.row.after` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.refreshAfterUpdate` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.undoLevel` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.fetch.size` | **추가함**: Options > General > Query Results |
| Data Editor | `resultset.filter.use.navigator` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.image.browser` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.mark.cell.value.occurrences` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.maxrows` | 이미 있음: Options > General > Query Results |
| Data Editor | `resultset.maxrows.sql` | 읽음, 화면 없음 → 다음 작업 |
| Data Editor | `resultset.order.mode` | 읽음, 화면 없음 → 다음 작업 |
| Data Editor | `resultset.order.policy` | 읽음, 화면 없음 → 다음 작업 |
| Data Editor | `resultset.reread.on.scroll` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.show.errorDialog` | 효과 없음(읽는 곳 없음) |
| Data Editor | `sql.query.filter.force.subselect` | 읽음, 화면 없음 → 다음 작업 |
| Binary Editor | `content.cache.blob` | 읽음, 화면 없음 → 다음 작업 |
| Binary Editor | `content.cache.clob` | 읽음, 화면 없음 → 다음 작업 |
| Binary Editor | `content.cache.maxsize` | 읽음, 화면 없음 → 다음 작업 |
| Binary Editor | `content.hex.encoding` | 읽음, 화면 없음 → 다음 작업 |
| Binary Editor | `content.memory.maxsize` | 읽음, 화면 없음 → 다음 작업 |
| Binary Editor | `resultset.binary.editor` | 효과 없음(읽는 곳 없음) |
| Binary Editor | `resultset.binary.representation` | **추가함**: Options > General > Query Results |
| Binary Editor | `resultset.binary.stringMaxLength` | **추가함**: Options > General > Query Results |
| Binary Editor | `resultset.commit.oncontentapply` | 효과 없음(읽는 곳 없음) |
| Binary Editor | `resultset.commit.oneditapply` | 효과 없음(읽는 곳 없음) |
| Binary Editor | `resultset.edit.longaslob` | 효과 없음(읽는 곳 없음) |
| Binary Editor | `resultset.edit.maxtextsize` | 효과 없음(읽는 곳 없음) |
| Binary Editor | `resultset.string.use.content.editor` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.behavior.autoCompleteProposial` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.behavior.autoSwitchMode` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.behavior.showFiltersPanelInSingleTabMode` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.calc.columnWidthByValues` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.show.columnHeaderExtra` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.show.connectionName` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.show.filterPanel` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.show.rightJustifyDateTime` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.show.rightJustifyNumbers` | 효과 없음(읽는 곳 없음) |
| Appearance | `resultset.transform.complex.type` | 읽음, 화면 없음 → 다음 작업 |
| Grid | `resultset.behavior.doubleClick` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.behavior.inlineEnter` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.grid.value.format` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.hScroll.smooth` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.highlight.selectedRows` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.max.column.def.width` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.attFilters` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.attIcons` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.attrOrder` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.boolean.checkbox` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.boolean.toggleOnClick` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.cellIcons` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.colorizeDataTypes` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.column.position` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.oddRows` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.row.batch.size` | 효과 없음(읽는 곳 없음) |
| Grid | `resultset.show.whitespace.characters` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.delimiter.bottom` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.delimiter.leading` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.delimiter.top` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.delimiter.trailing` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.extra.spaces` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.line.number` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.max.column.size` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.show.nulls` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.tab.size` | 효과 없음(읽는 곳 없음) |
| Plain text | `resultset.text.value.format` | 효과 없음(읽는 곳 없음) |
| Data Formats | `resultset.format.datetime.native` | 이미 있음: Options > General > Query Results |
| Data Formats | `resultset.format.numeric.native` | **추가함**: Options > General > Query Results |
| Data Formats | `resultset.format.numeric.scientific` | **추가함**: Options > General > Query Results |
| Dictionaries | `dictionary.max.rows` | 효과 없음(읽는 곳 없음) |
| Dictionaries | `resultset.reference.value.description.column.patterns` | 읽음, 화면 없음 → 다음 작업 |
| ERD | `erd.diagram.change.borders.colors` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.diagram.change.header.colors` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.diagram.show.partitions` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.diagram.show.views` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.grid.enabled` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.grid.height` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.grid.snap` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.grid.width` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.load.lazy.descriptions` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.print.margin-bottom` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.print.margin-left` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.print.margin-right` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.print.margin-top` | 효과 없음(읽는 곳 없음) |
| ERD | `erd.print.page-mode` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `SQLEditor.autoSaveActiveSchema` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `SQLEditor.autoSaveOnChange` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `SQLEditor.autoSaveOnClose` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `SQLEditor.autoSaveOnExecute` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `SQLEditor.outputPanel.autoShow` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `SQLEditor.resultSet.closeOnError` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `SQLEditor.resultSet.orientation` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `SQLEditor.resultSet.queryTabLimit` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `SQLEditor.resultSet.replaceCurrentTab` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `database.editor.connect.on.activate` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `database.editor.connect.on.execute` | 효과 없음(읽는 곳 없음) |
| SQL Editor | `database.editor.separate.connection` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.Folding.enabled` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.closeBrackets` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.closeDoubleQuotes` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.closeSingleQuotes` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.format.extractFromSource` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.format.keywordCaseAuto` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.markOccurrences` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.markOccurrences.forSelection` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.problemMarkers.enabled` | 효과 없음(읽는 곳 없음) |
| Code Editor | `SQLEditor.smartWordIterator` | 효과 없음(읽는 곳 없음) |
| Code Completion | `SQLEditor.ContentAssistant.activate.hippie` | 읽음, 화면 없음 → 다음 작업 |
| Code Completion | `SQLEditor.ContentAssistant.auto.activation.delay` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.auto.activation.enable` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.auto.keystrokes.activation` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.hide.duplicates` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.insert.case` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.insert.single.proposal` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.insert.space.after.proposal` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.matching.fuzzy` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.proposals.long.name` | 읽음, 화면 없음 → 다음 작업 |
| Code Completion | `SQLEditor.ContentAssistant.proposals.short.name` | 읽음, 화면 없음 → 다음 작업 |
| Code Completion | `SQLEditor.ContentAssistant.proposals.sort.alphabetically` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.qualified.column.names` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.replace.word` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.show.column.procedures` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.show.helpTopics` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.show.values` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.use.global.search` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `sql.proposals.insert.table.alias` | 읽음, 화면 없음 → 다음 작업 |
| SQL Dialects | `SQLEditor.script.bind.embedded.read` | 효과 없음(읽는 곳 없음) |
| Scripts | `SQLEditor.script.bigFileLengthBoundary` | 효과 없음(읽는 곳 없음) |
| Scripts | `SQLEditor.script.bind.commentType` | 효과 없음(읽는 곳 없음) |
| Scripts | `SQLEditor.script.bind.embedded.read` | 효과 없음(읽는 곳 없음) |
| Scripts | `SQLEditor.script.bind.embedded.readConfirm` | 효과 없음(읽는 곳 없음) |
| Scripts | `SQLEditor.script.bind.embedded.write` | 효과 없음(읽는 곳 없음) |
| Scripts | `new.script.template` | 효과 없음(읽는 곳 없음) |
| Scripts | `new.script.template.enabled` | 효과 없음(읽는 곳 없음) |
| Scripts | `script.auto.connection.attach` | 효과 없음(읽는 곳 없음) |
| Scripts | `script.auto.connection.folders` | 효과 없음(읽는 곳 없음) |
| Scripts | `script.auto.folders` | 효과 없음(읽는 곳 없음) |
| Scripts | `script.delete.empty` | 효과 없음(읽는 곳 없음) |
| Scripts | `script.file.name.pattern` | 효과 없음(읽는 곳 없음) |
| Scripts | `script.title.pattern` | 효과 없음(읽는 곳 없음) |
| Formatting | `SQLEditor.format.activeQuery` | 효과 없음(읽는 곳 없음) |
| Formatting | `SQLEditor.format.boldKeywords` | 효과 없음(읽는 곳 없음) |
| Formatting | `sql.format.formatter` | 이미 있음: Options > Editor > Formatting |
| Formatting | `sql.format.keywordCase` | 이미 있음: Options > Editor > Formatting |
| SQL Processing | `SQLEditor.beepOnQueryEnd` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `SQLEditor.clearOutputBeforeExecute` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `SQLEditor.closeIncludedScriptAfterExecution` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `SQLEditor.maxEditorOnScriptExecute` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `SQLEditor.refreshDefaultsAfterExecute` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `SQLEditor.setSelectionToStatisticsTab` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `SQLEditor.showMetadataRefreshNotification` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `SQLEditor.showStatisticsForQueriesWithResults` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `script.bigScript.noFetch.confirm` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `script.commit.lines` | 두지 않음: 트랜잭션 안전 규칙(묻지 않고 커밋) |
| SQL Processing | `script.commit.type` | 두지 않음: 트랜잭션 안전 규칙(묻지 않고 커밋) |
| SQL Processing | `script.error.handling` | **추가함**: Options > Query Execution |
| SQL Processing | `script.fetch.resultset` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `script.sql.delimiter` | **추가함**: Options > Query Execution |
| SQL Processing | `script.sql.delimiter.blank` | **추가함**: Options > Query Execution |
| SQL Processing | `script.sql.ignoreNativeDelimiter` | **추가함**: Options > Query Execution |
| SQL Processing | `script.sql.query.remove.trailing.delimiter` | **추가함**: Options > Query Execution |
| SQL Processing | `sql.command.prefix` | **추가함**: Options > Query Execution |
| SQL Processing | `sql.parameter.anonymous.enabled` | **추가함**: Options > Query Execution |
| SQL Processing | `sql.parameter.ddl.enabled` | **추가함**: Options > Query Execution |
| SQL Processing | `sql.parameter.enabled` | **추가함**: Options > Query Execution |
| SQL Processing | `sql.parameter.mark` | **추가함**: Options > Query Execution |
| SQL Processing | `sql.parameter.prefix` | **추가함**: Options > Query Execution |
| SQL Processing | `sql.variables.enabled` | **추가함**: Options > Query Execution |
| SQL Processing | `statement.invalidate.before.execute` | 효과 없음(읽는 곳 없음) |
| SQL Processing | `statement.timeout` | 효과 없음(읽는 곳 없음) |
| macOS Mouse | `macos.tooltip.delay` | 효과 없음(읽는 곳 없음) |
| macOS Mouse | `macos.tooltip.delay.enabled` | 효과 없음(읽는 곳 없음) |
