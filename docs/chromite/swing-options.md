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
5. 2026-11-08: "다음 작업"의 1–4순위(연결별 값, 데이터 형식 프로필, Errors and Timeouts, Metadata)와 6순위(Client Identification)를 넣었다.
   키마다 지금 코드에서 읽는 곳을 다시 확인했다(아래 각 절의 표). 남은 키는 "다음 작업"에 그대로 적었다.
6. 2026-11-08 오후: "다음 작업"의 5순위와 7–13순위를 넣었다("남은 키 (2026-11-08 오후)"). 키마다 읽는 곳을 다시 찾아, Swing 경로에서
   돌지 않는 키 6개(폴더 자리 표시, PostgreSQL 데이터베이스 통계, 정렬 정책, 참조 값 설명 열, 단어 완성, 셸 명령 인증 허용)는 "효과 없음"으로 옮겼다.

도구(재현용, 저장소 밖): `D:\Tools\_scratch\chromite-p3-settings-pages\keyscan.py`(키 추출과 읽는 곳 검색), `gentable.py`(아래 부록 표 생성).

## 결과 요약

| 상태 | 키 수 | 뜻 |
|---|---|---|
| 추가함 | 85 | Swing 화면을 만듦(2026-11-07 23키, 2026-11-08 Errors and Timeouts 5·Metadata 5·Client Identification 4·전역 라이브러리 1(Driver Manager), 오후 47키) |
| 이미 있음 | 7 | 전에 만든 Swing 화면이 같은 키를 씀 |
| 연결별 공급자 속성 | 7 | 연결 대화상자 Provider Properties 탭에서 이미 바꿀 수 있음 |
| 규칙상 두지 않음 | 5 | 스크립트 커밋 방식 2키, 오류 때 롤백, 취소가 안 될 때 세션 단위 취소: 트랜잭션 안전 규칙에 어긋남. Altibase 실행 계획 방식: 계획 보기가 문장을 실행함 |
| 다음 작업 | 0 | 없음. 정할 일만 남음(아래 "다음 작업": 파일 원본 기본값, Altibase 실행 계획 방식 확인 절차, `postgresql.dd.*` 전역 기본값) |
| 효과 없음 | 202 | Swing에서 읽는 곳이 없거나 경로가 돌지 않음. 화면을 만들지 않음 |

키가 아닌 설정(연결 유형, 연결별 격리 수준, 템플릿, AI, 데이터 형식 프로필의 `dataformat.*` 키 등)은 아래 페이지별 표에 적었다.
2026-11-08부터 Query Execution, Query Results, Data Formats, Errors and Timeouts, Metadata, Client Identification, Oracle 페이지는 연결마다 다른 값을
둘 수 있다("연결별 설정"). 같은 날 오후에 Binary and LOB, Code Completion, Data Transfer, Altibase, CUBRID 페이지도 연결별 값을 갖게 했다.

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
| 쿼리 창 세션: 창마다(내장·단일 연결 DB 제외, 기본) / 항상 창마다 / 함께 씀 | `database.editor.separate.connection` | `QueryWindowContext.isSeparate`(2026-11-07, [transactions.md](transactions.md) "쿼리 창마다 별도 연결"). 연결 대화상자의 같은 키가 이긴다 |

- 쿼리 창은 실행할 때마다 파서 문맥을 새로 만든다(`SQLScriptBatches.createParserContext`). 그래서 다시 연결하지 않아도 다음 실행부터 적용된다.
- 값은 플랫폼 설정 저장소에 쓴다. 연결의 설정 저장소(`DataSourcePreferenceStore`)가 이것을 부모로 가지므로 연결별 값을 두지 않은 모든 연결에 적용된다.
  연결별로 다른 값은 연결 대화상자 Options > Settings > Query Execution에서 준다(2026-11-08, "연결별 설정"). 쿼리 창 세션은 연결 대화상자
  Connection Properties에 따로 있어 연결별 페이지에는 없다.
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

### Options > Query Results (`ResultSetOptionsPage`)

2026-11-07에는 General 탭의 Query Results 절에 행을 더했다. 2026-11-08에 연결 대화상자에서 같은 페이지를 쓰려고 절 전체(Maximum rows, 날짜/시간
원본 형식, 저장 전 스크립트 표시 포함)를 Query Results 탭으로 옮겼다. 아래는 2026-11-07에 더한 행이다.

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
| 조회만 한 트랜잭션은 문장이 끝나면 자동으로 끝내기(2026-11-09, 기본 꺼짐) | `autoEndReadOnlyTransactions`(Chromite가 더함) | `IdleTransactionMonitor.endReadOnlyAfterWork`([idle-transactions.md](idle-transactions.md) "조회만 한 트랜잭션 끝내기") |
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
  - 스마트 커밋(`smartCommit`, `smartCommitRecover`): 2026-11-08부터 쿼리 창이 쓰므로 두 칸을 더했다([connection-lifecycle.md](connection-lifecycle.md) "스마트 커밋").
  - 유휴 트랜잭션 자동 롤백의 유형별 기본값: 자동 롤백은 사용자가 연결마다 켜는 것이 사용자 결정이다([idle-transactions.md](idle-transactions.md)). 유형에 두려면
    모델에 새 필드가 필요해 이번에는 하지 않았다.

### Options > Oracle (`OracleOptionsPage`)

| 항목 | 키(`OracleConstants`) | 읽는 곳 |
|---|---|---|
| 실행 계획 테이블(비우면 PLAN_TABLE, TOAD_PLAN_TABLE) | `oracle.explain.table` | `OracleDataSource.getPlanTableName`(연결마다 처음 한 번 찾고 기억 → 다시 연결한 뒤 적용) |
| 메시지 탭에 DBMS_OUTPUT 표시 | `oracle.dbms.output` | `OracleOutputReader.isServerOutputEnabled`(쿼리 창은 문장마다 이 값을 보고 읽음. 서버의 `DBMS_OUTPUT.ENABLE`·`DISABLE`은 세션을 열 때와, 값이 바뀐 뒤 쿼리 창 세션의 다음 문장 전에 함(2026-11-08, [swing-server-output.md](swing-server-output.md))) |
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

## 연결별 설정 (2026-11-08)

SWT는 설정 페이지 중 일부(`TargetPrefPage`를 쓰는 페이지)를 연결의 속성 페이지로도 열어 "이 연결에만 다른 설정"을 줄 수 있었다. 값은 연결의 설정 저장소
(`DataSourcePreferenceStore`, 부모는 플랫폼 설정 저장소)에 들어가고 연결 설정 파일의 `custom-properties`로 저장된다. 모델은 아래 페이지의 키를 모두
**연결의 설정 저장소에서** 읽으므로(키마다 읽는 곳은 각 절의 표) 연결별 값이 실제로 효과가 있다.

### 구조

| 부분 | 클래스 | 하는 일 |
|---|---|---|
| 페이지 틀 | `SettingsPage` | 페이지의 키 목록(`getKeys`), 저장소에서 읽기(`load`), 쓰기(`save(Writer)`), 연결 값 지우기(`clear`), 다시 연결 필요 여부(`isAppliedWithoutReconnect`). Options와 연결 대화상자가 같은 클래스를 쓴다 |
| 쓰기 방식 | `SettingsPage.Writer` | 전역: 바뀐 값만 쓰고 바뀐 것이 있으면 저장. 연결별: 모든 값을 연결의 값으로 씀(전역과 같은 값도: 나중에 전역을 바꿔도 이 연결은 그대로). 빈 칸이 기본값인 글자(구분 기호, 매개 변수 접두사)는 연결이면 기본값 자체를 씀 |
| 연결 대화상자 탭 | `ConnectionSettingsOverrides` | Options > Settings 탭. 왼쪽 목록에 페이지, 페이지마다 "이 연결에 다른 설정 사용" 확인란. 끄면 Options의 값을 보이고 고칠 수 없음. 켠 페이지 목록에 확인 표시 |
| 페이지 | `SqlExecutionOptionsPage`, `ResultSetOptionsPage`, `ContentOptionsPage`, `DataFormatOptionsPage`, `ConnectionErrorsOptionsPage`, `MetadataOptionsPage`, `ClientIdentificationOptionsPage`, `CodeCompletionOptionsPage`, `DataTransferOptionsPage`, `OracleOptionsPage`·`AltibaseOptionsPage`·`CubridOptionsPage`(그 데이터베이스 연결만) | 아래 각 절 |
| 전역 저장소 | `SettingsPage.getGlobalStore` | 페이지의 전역 값이 있는 저장소. 기본은 플랫폼 저장소, Data Transfer는 데이터 전송 번들 저장소. Options의 OK와 연결 페이지를 끌 때 보이는 값이 이것을 쓴다 |
| 전역만 있는 페이지 | `ConnectionsOptionsPage`, `DriversOptionsPage`, `NavigatorOptionsPage`, `NotificationsOptionsPage`, `LogsOptionsPage`, `MimerOptionsPage` | 모델이 플랫폼 저장소에서만 읽는 키. 같은 틀(`SettingsPage`)을 쓰고 Options에만 넣음 |

- 처음 상태: 연결의 설정 저장소가 페이지의 키 중 하나라도 자기 값으로 가지면 켜짐(SWT `hasDataSourceSpecificOptions`와 같은 규칙).
- 저장: 켠 페이지는 모든 키를 연결의 값으로 쓰고, 끈 페이지는 연결의 값을 지운다(`setToDefault`). Test Connection의 임시 연결에도 같은 값이 들어간다.
- 다시 연결: 모델이 그 작업을 할 때마다 읽는 페이지(Query Execution, Query Results, Binary and LOB, Data Formats, Errors and Timeouts, Metadata,
  Code Completion, Data Transfer, Altibase, CUBRID)는 바꿔도 다시 연결을 묻지 않는다(`ConnectionSettingsPanel.getPreferencesAppliedWithoutReconnect`,
  쿼리 창 세션과 같은 처리). 세션을 열 때 읽거나 연결마다 기억하는 페이지(Client Identification, Oracle)는 기존 "다시 연결할까요" 질문을
  거치고, 다시 연결은 열린 트랜잭션을 먼저 묻는다(트랜잭션 안전 규칙).
- Options의 OK(2026-11-08, evening #33): 페이지마다 저장하다 한 페이지가 실패해도 나머지 페이지는 저장하고, 실패한 페이지를 "페이지: 오류" 줄로 한 번에 알린다
  (`OptionsDialog.applyStep`, 문구 `options_save_failed`). 전에는 예외가 OK 처리를 끊어 그 뒤 페이지가 말없이 저장되지 않았다.
- 트랜잭션: 이 페이지들의 키는 세션의 커밋 방식을 바꾸거나 세션을 닫지 않는다. 그런 키(오류 때 롤백, 세션 단위 취소)는 두지 않았다(아래 Errors and Timeouts).
- 알려진 동작(모델 직렬화, 고치지 않음): 연결 설정 파일에는 기본값과 다른 값만 저장된다(`DataSourceSerializerModern`, `getDefaultString`과 비교).
  전역 값을 바꾸지 않은 키에 연결이 기본값과 같은 값을 주면 저장되지 않아, 다시 열면 그 페이지의 확인란이 꺼져 보일 수 있다. 실제로 쓰는 값은 같다.
- Mimer 연결은 모델이 연결할 때 이진 값 표시 형식을 연결의 값으로 넣는다(`MimerDataSource`). 그래서 Mimer 연결은 Query Results 페이지가 켜진 채로 보인다.

### Options > Data Formats (`DataFormatOptionsPage`)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| 로캘 | `dataformat.profile.language`, `.country`, `.variant` | `DataFormatterProfile.loadProfile`(값을 표시할 때마다 `createFormatter`가 다시 읽음) |
| 형식 종류별 속성(숫자: 묶음 기호·소수 자릿수·반올림 등, 날짜·시간·타임스탬프: 패턴·시간대) | `dataformat.type.<형식>.<속성>` | `DataFormatterProfile.getFormatterProperties` → 값 처리기(`JDBCNumberValueHandler`, `TemporalAccessorValueHandler`, `DateTimeCustomValueHandler` 등) |

- 형식 종류와 속성은 모델 확장점(`com.seizia.chromite.dataFormatter`)에 등록된 것을 그대로 보인다. 속성 표는 연결 속성 표(`ConnectionPropertiesTable`)를 쓰고,
  값을 바꾸면 견본이 바로 바뀐다. 패턴이 틀리면 견본 칸에 오류가 보인다.
- 속성의 기본값은 로캘마다 다르다(`DBDDataFormatterSample.getDefaultProperties`). 전역에 저장할 때는 기본값과 같은 값을 기본값으로 되돌린다.
- 로캘은 세 키를 직접 쓴다. 모델의 `saveProfile`은 저장소를 저장까지 하므로(연결이면 연결 설정 파일을 씀) 편집 중인 연결 사본에 쓰지 않으려고
  `DataFormatterProfile`의 로캘 키 상수를 공개로 바꿨다(모델 변경은 이것뿐).
- 두지 않은 것: 이름 있는 프로필(SWT Manage profiles, `dataformat-profiles.xml`)은 데이터 내보내기 작업 설정의 `formatterProfile`만 읽는다
  (`StreamConsumerSettings`). Swing 내보내기 화면은 프로필을 고르지 않아 화면을 만들지 않았다. 날짜·숫자 원본 형식과 과학적 표기 세 키는 Query Results에 있다.

### Options > Errors and Timeouts (`ConnectionErrorsOptionsPage`)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| 연결 열기(ms, 0 = 드라이버 소켓 시간 제한까지) | `connection.open.timeout` | `JDBCDataSource.openConnection`, `DBUtils`(지연 인스턴스 연결) |
| 연결 닫기(ms) | `connection.close.timeout` | `JDBCDataSource.closeConnection` |
| 연결 확인(ms) | `connection.validation.timeout` | `JDBCUtils.isConnectionAlive`, `SSHTunnelImpl` |
| 연결이 끊기면 자동으로 다시 연결, 재시도 횟수 | `execute.recover.enabled`, `execute.recover.retryCount` | `DBExecUtils.tryExecuteRecover`. Chromite의 `TransactionRecoveryGuard`가 복구가 커밋하지 않은 변경을 버리게 되면 복구하지 않고 오류로 멈춘다([transactions.md](transactions.md) "오류 복구와 열린 트랜잭션") |

- 규칙상 두지 않음:
  - `query.rollback-on-error`(SWT "Rollback on error"): 문장이 실패하면 수동 커밋 세션의 트랜잭션을 묻지 않고 롤백했다(upstream `JDBCStatementImpl.handleExecuteError`).
  - `execute.cancel.checkTimeout`(SWT "Close connection after timeout"): 문장 취소가 듣지 않으면 시간 뒤 바깥 블록(세션)을 취소했다(upstream `AbstractJob` 취소 확인,
    `JDBCConnectionImpl.cancelBlock` → `JDBCDataSource.cancelCurrentExecution`: 일반 JDBC는 실행 스레드 인터럽트, PostgreSQL `cancelQuery`, Oracle `cancel`).
    인터럽트로 드라이버의 연결이 끊기면 열린 트랜잭션을 묻지 않고 잃는다.
- 두 키를 끄는 방법(2026-11-08, `chromite/safety-defaults`):
  - **모델이 읽지 않는다.** 두 키를 읽던 코드를 지웠다. 롤백 분기(`JDBCStatementImpl.handleExecuteError`)와 바깥 블록 취소(`AbstractJob.runBlockCanceler`의
    취소 확인 작업)가 없어, 취소는 늘 가장 안쪽 블록(문장)만 한다. 키 상수와 기본값(`ModelPreferences`)도 지웠다.
  - 시작할 때 값을 기본값으로 되돌리는 것만으로는 그 뒤의 설정 가져오기(#178), 연결 파일 가져오기, 손으로 고친 설정 파일이 다시 켤 수 있다. 읽는 코드를
    지우면 어느 경로로 들어온 값도 효과가 없어 이쪽을 골랐다. 두 기능 모두 Swing 화면이 없어 쓸 수 없었으므로 사용자에게 보이는 동작은 바뀌지 않는다
    (SWT에서 켜 둔 설정을 가져온 경우만 꺼진다).
  - **남은 값 정리**(`RemovedPreferences`, registry 번들): 효과는 없지만 설정 내보내기가 옮기거나 켜져 있다고 오해하지 않게 지운다. 지운 키마다 정보 로그를
    한 줄 남긴다(`Reset removed preference '<키>' (was '<값>')…`).
    - 전역: 플랫폼 설정 저장소(연결 설정 저장소의 부모, 두 키를 읽던 곳)에서 `BasePlatformImpl.initialize`가 지우고 저장한다.
    - 연결: `DataSourceSerializerModern`이 `data-sources*.json`의 `custom-properties`를 읽을 때 뺀다. 연결 파일 가져오기와 설정 가져오기의 연결도 같은 파서를
      거친다. 파일에서는 레지스트리가 그 연결 파일을 다음에 저장할 때 빠진다. 시작할 때 연결 파일을 다시 쓰지 않는 것은 Git에 둔 프로젝트 파일이
      사용자가 바꾸지 않았는데 바뀌지 않게 하려는 것이다. 그래서 저장되기 전까지는 파일을 읽을 때마다(시작할 때 두 번 읽음) 로그가 다시 남는다.
  - 확인(2026-11-08, 제품 빌드를 복사해 자기 작업 공간·데이터 폴더로 실행, 프로세스 안의 검사 코드, OS 입력 없음): 전역 설정 파일과 PostgreSQL·SQL Server
    연결 두 개의 `custom-properties`에 두 키를 넣고 시작 → 키마다 로그 한 줄, 전역 설정 파일에서 빠짐, 두 연결의 저장소에 남지 않음(같이 넣은 다른 키
    `resultset.maxrows`는 남음). 이어서 두 키를 실행 중에 연결 저장소에 다시 켜고(저장하지 않음) 수동 커밋 세션에서 INSERT 뒤 없는 표 SELECT로 오류를
    냄 → PostgreSQL 서버 상태 `idle in transaction (aborted)`(롤백하지 않음, 다음 문장은 25P02), SQL Server `@@TRANCOUNT` 1·같은 세션에서 행 1개·
    `sys.dm_exec_sessions.open_transaction_count` 1. 세션 단위 취소는 코드를 지운 것으로 확인했고 실행해 보지는 않았다.

### Options > Metadata (`MetadataOptionsPage`)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| DDL 문에 대소문자를 구분하는 이름 사용 | `database.meta.casesensitive` | `DBObjectNameCaseTransformer` |
| DDL에 추가 정보 넣기 | `database.meta.extra.ddl.info` | `DBStructUtils`, `PostgreUtils`·`PostgreTableManagerBase`·`PostgreViewBase`, `SQLServerBaseTableManager` |
| 서버 쪽 개체 필터 | `database.meta.server.side.filters` | `MySQLCatalog`(지원하는 데이터베이스만). 개체 탐색기를 새로 고치면 적용 |
| 비용이 큰 개체 속성 읽기(행 수, 크기) | `database.props.expensive` | `DataSourcePropertyFilter`(개체 목록 `ObjectListTable`, 속성) |
| 쿼리 창과 결과 표에서 테이블 정보를 읽지 않음 | `database.meta.disableAdditionalRead` | `DataSourceDescriptor.isExtraMetadataReadEnabled`(`DBExecUtils.bindAttributes`, `JDBCTable`, 자동완성), `SqlCompletionAnalysis` |

- SWT 같은 페이지의 "메타데이터용 별도 연결"(`database.meta.separate.connection`)은 연결 대화상자에 이미 있어 두지 않았다.

### Options > Client Identification (`ClientIdentificationOptionsPage`)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| 클라이언트 애플리케이션 이름을 보내지 않음 | `database.meta.client.name.disable` | PostgreSQL, MySQL, SQL Server(두 공급자), Oracle, DB2(LUW·i·z/OS), HANA, Athena, 범용 JDBC 데이터 원본(연결할 때) |
| 사용자 지정 이름 사용, 이름(변수 `${context.name}`, `${context.id}`, 연결 변수) | `database.meta.client.name.override`, `database.meta.client.name.value` | `DBUtils.getClientApplicationName` |
| 연결 매개 변수의 변수 풀기 | `database.connect.processEnvVars` | `DataSourceDescriptor`(연결할 때 연결 정보의 `${...}`), `SQLiteExtensionSettings` |

- 세션을 열 때 읽으므로 연결된 연결의 값을 바꾸면 다시 연결을 묻는다.

## 남은 키 (2026-11-08 오후)

"다음 작업"의 5순위와 7–13순위를 만들었다. 키마다 지금 코드에서 읽는 곳을 다시 확인했고, 그 사이에 효과가 없어진 키나 Swing 경로에서 돌지 않는 키는
화면을 만들지 않았다(각 표 아래 "두지 않은 것").

### Options > Query Results: 읽기와 정렬 절 (5순위, `ResultSetOptionsPage`)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| 행 수 제한을 SQL 문에 넣기(LIMIT, TOP) | `resultset.maxrows.sql` | `DBUtils`(실행할 때 쿼리 변환기 `RESULT_SET_LIMIT`) |
| 결과 표를 끝까지 내리면 행 더 읽기 | `resultset.autofetch.next.segment` | `ResultSetViewerPanel.isAutoFetchNextSegment` |
| 열 정렬: 모든 행을 읽지 않았으면 서버 / 항상 표 안 / 항상 서버 | `resultset.order.mode` | `ResultSetViewerPanel.isServerSideOrdering`(`OrderingStrategy`) |
| 필터할 때 쿼리를 감싸기 | `sql.query.filter.force.subselect` | `StandardSQLDialectQueryGenerator`, `SQLSemanticProcessor` |
| 레이블 대신 열 이름 표시 | `resultset.column.label.ignore` | `DBDAttributeBindingMeta.getLabel` → 결과 표 머리글(`ResultSetGridReceiver`), 열 도구 설명, 필터 요약 |
| 중첩 값(배열, 구조체)을 열로 펼쳐 보기 | `resultset.transform.complex.type` | `ComplexTypeAttributeTransformer`, `ArrayAttributeTransformer`, `MapAttributeTransformer`, BigQuery |

- 모두 연결의 설정 저장소에서 읽으므로 연결별 값이 있고, 다음 실행·정렬부터 적용된다(다시 연결 없음).
- **고친 버그**: 결과 표 머리글이 열의 레이블이 아니라 이름(`getColumnName`)을 썼다. MySQL·MariaDB 드라이버는 이름으로 원래 열 이름을 주므로
  `SELECT User AS my_user`의 머리글이 `User`였다(실행 확인, MariaDB 11). SWT 표처럼 레이블(없으면 이름)을 쓰게 고쳤다(`ResultSetGridReceiver`).
  그래서 "레이블 대신 열 이름 표시"가 머리글에도 효과가 있다. PostgreSQL 드라이버는 이름으로도 별칭을 주어 두 값이 같다.
- **고친 버그**: 끝까지 내리면 더 읽기는 연결 자신의 값만 보았다(`store.contains`가 연결 저장소 자신의 값만 봄). 그래서 Options에서 끄더라도 동작했다.
  기본값(켬)을 전역 저장소에 등록하고 연결 저장소로 읽게 바꿨다(`isAutoFetchNextSegment`).
- 열 정렬 위치와 #142(헤더 정렬이 쿼리의 ORDER BY를 바꿈): #142는 서버 정렬을 어떻게 만드는지(쿼리의 ORDER BY를 바꾸거나, 행을 고르는
  ORDER BY면 감싸서 정렬)만 바꾸고, 서버에서 정렬할지 표 안에서 정렬할지는 그대로 이 키로 정한다(`isServerSideOrdering`). 그래서 이 설정은
  #142와 함께 의미가 있다. 표 안 정렬은 읽은 행의 순서만 바꾸고 쿼리를 다시 보내지 않는다. 문구는 #142 전후 모두 맞게 "서버 정렬은 데이터베이스가
  정렬해서 쿼리를 다시 읽는다"로 썼다.
- 두지 않은 것:
  - `resultset.order.policy`(정렬 없을 때 기본 키로 정렬): `JDBCTable.readData`가 "항상 서버"이고 필터가 있으며 정렬이 없을 때만 읽는다.
    Swing 결과 표는 늘 쿼리 창 쿼리의 데이터 컨테이너로 읽고(`ResultSetGridReceiver`), 테이블 자체를 읽는 곳(내보내기·복사 생산자)은 필터를 넘기지 않는다.
    효과가 없다. #142의 "헤더 정렬이 ORDER BY를 바꿈"과도 관계가 없다.
  - `resultset.reference.value.description.column.patterns`: 사전(참조 값 고르기, `JDBCTable`의 사전 읽기)만 읽는다. Swing에 그 화면이 없다.

### Options > Binary and LOB (5순위, `ContentOptionsPage`)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| 텍스트 LOB(CLOB)·이진 LOB(BLOB)을 행과 함께 읽기, 크기 | `content.cache.clob`, `content.cache.blob`, `content.cache.maxsize` | `JDBCContentValueHandler`(가져올 때, 연결 저장소) |
| 메모리에 두는 크기(전역만) | `content.memory.maxsize` | `JDBCContentCLOB`, `JDBCContentBLOB`, `OracleContentBFILE`(플랫폼 저장소) |
| 이진 값을 글자로 바꿀 때 인코딩 | `content.hex.encoding` | `DBValueFormatting.getDefaultBinaryFileEncoding`(연결 저장소) → `JDBCContentBytes`, `JDBCContentChars` |

- `content.memory.maxsize`는 플랫폼 저장소에서만 읽어 연결 페이지에는 그 행이 없다(`getKeys`에도 전역일 때만).
- `ContentUtils`의 같은 키 읽기는 주석 처리된 코드라 효과가 없다.

### Options > Connections (7순위, `ConnectionsOptionsPage`, 전역만)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| 위험한 드라이버 매개 변수 거부 | `connection.sanitizeDriverProperties` | `JDBCDataSource.validateConnectionProperties`(연결할 때). 검사기를 주는 데이터 원본은 지금 MySQL·MariaDB뿐(`MySQLConnectionValidator`) |
| SSL에 Windows 인증서 저장소 사용(Windows만) | `connections.useWinTrustStoreType` | `ChromiteSwingApplication.useWindowsTrustStore`(시작할 때, S6). 다시 시작한 뒤 적용 |
| 클라이언트 시간대 | `java.client.timezone` | `ClickhouseDataSource`(연결할 때) |

- 시간대와 IP 스택(A-S12/A-S13)의 시작 처리는 B9 2부(`chromite/p4-connection-tools`)가 한다: 앱이 시작할 때 `TimezoneRegistry.overrideTimezone`으로
  JVM 기본 시간대를 바꾼다([platform-startup.md](platform-startup.md)). 이 페이지는 키만 쓴다. 실행 중에 JVM 시간대를 바꾸지 않는다(SWT는 OK에서 바꿨다):
  열린 결과와 기록의 시간이 섞이기 때문이다. 바꾼 시간대는 다음 시작부터 적용된다.
- 두지 않은 것: "셸 명령" 인증 방식 허용(`connection.shellAuthModelEnabled`). 모델은 이 키로 인증 방식 목록에 셸 명령을 넣지만
  (`enabledWhen`의 `hasPreference` → `getApplicableAuthModels`), Swing 연결 대화상자는 Login·Password 칸만으로 설정이 끝나는 방식만 보인다
  (`ConnectionAuthModels.getChoices`, 셸 명령은 명령·작업 폴더 칸이 필요해 뺌). 실행 확인에서 켠 뒤 PostgreSQL 연결 대화상자의 인증 목록이
  "Username/password / PostgreSQL PgPass" 그대로였다. 효과가 없어 화면에서 뺐다. 셸 명령 인증 입력 화면(B9 "이벤트 셸 명령 편집" 등)과 함께 둔다.

### Options > Drivers (8순위, `DriversOptionsPage`, 전역만)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| 드라이버 폴더(비우면 기본 폴더) | `ui.drivers.home` | `DriverDescriptor.getCustomDriversHome` |
| 파일 원본(한 줄에 하나, `\|`로 저장) | `ui.drivers.sources` | `DriverDescriptor.getDriversSources`·`getDriversPrimarySource` → `DriverLibraryRepository`, `NativeClientDistributionDescriptor` |
| 제품을 업데이트하면 제품에 든 라이브러리 버전 사용 | `ui.drivers.version.update` | `DriverDescriptorSerializerLegacy`(드라이버 설정을 읽을 때) |
| 프록시 호스트, 포트 | `ui.proxy.host`, `ui.proxy.port` | `WebUtils.openConnection`(드라이버 내려받기), `GlobalProxyAuthenticator` |
| 프록시 사용자 이름, 비밀번호 | `ui.proxy.user`, `ui.proxy.password` | 보안 저장소(`GlobalProxyAuthenticator.readCredentials`·`saveCredentials`). 설정 파일에 두지 않는다 |
| Maven 저장소(사용 여부, 추가·편집·제거) | `maven-repositories.xml` | `MavenRegistry`(드라이버 내려받기). 저장소 인증 정보는 보안 저장소 |

- 전역 라이브러리(`ui.drivers.global.libraries`)와 드라이버의 Maven 아티팩트 편집은 드라이버 관리자에 있다(#148, Driver Manager > Global Libraries…,
  드라이버 편집 창). 겹치지 않게 Drivers 페이지에는 "드라이버 관리자" 단추만 두었다.
- 기본 제공 Maven 저장소(plugin.xml)는 사용 여부만 바꾸고, 추가한 저장소만 편집·제거한다. 저장소 순서 바꾸기는 두지 않았다(추가한 저장소는 맨 뒤).
- **고친 버그(모델)**: 그룹(scope)이 있는 저장소를 저장하면 `MavenRegistry.saveConfiguration`이 scope 자식 요소 뒤에 `snapshot` 속성을 쓰려다
  "Attribute outside of element"로 실패했다(upstream 그대로, SWT에서도 같음). 속성을 자식 요소보다 먼저 쓰게 고쳤다. 실행 확인 중 OK가 이 예외로 멈춰 찾았다.
- dbeaver.io의 버전 확인이나 저장소 주소를 새로 넣지 않았다. 다만 파일 원본의 모델 기본값이 이미 `https://dbeaver.io/files/jdbc/`이다
  (`ModelPreferences` 423행, upstream). 이 페이지는 그 값을 보일 뿐 바꾸지 않았다. 2026-11-08 사용자 결정: 그대로 둔다.
  이 주소는 `repo:/…` 경로의 파일(Caché·jConnect 드라이버, jTDS SSO dll, DBF 라이브러리, Teradata 라이선스, Windows용 PostgreSQL·MySQL·MariaDB 로컬 클라이언트)에만 쓰이고,
  주요 드라이버는 Maven Central에서 받는다. Chromite 서버로 옮기려면 파일별 재배포 라이선스부터 확인해야 한다.

### Options > Logs (9순위, `LogsOptionsPage`, 전역만)

| 항목 | 키 | 읽는 곳 |
|---|---|---|
| 실행한 SQL을 로그 파일에 기록, 보관 일수, 폴더 | `qm.storeLogs`, `qm.historyDays`, `qm.logDirectory` | `QMLogFileWriter`(Swing 플랫폼이 켬, `qm.` 키가 바뀌면 바로 다시 만듦. 보관 일수가 지난 파일은 그때 지움) |
| 기록할 것(연결, 트랜잭션, 쿼리), 쿼리 종류 | `qm.objectTypes`, `qm.queryTypes` | `DefaultEventFilter` → `QMUtils.createDefaultCriteria` |
| 디버그 로그 파일 최대 크기(MB), 보관할 파일 수 | `logs.files.output.maxSize`, `logs.files.output.maxCount` | `LogOutputStream`(바로 적용) |

- 디버그 로그 두 키는 모델 번들 저장소 객체(`ModelPreferences.getPreferences`)에 쓴다. 플랫폼 저장소와 같은 설정 노드지만 변경 알림은 저장소 객체마다
  따로라서, 그 객체로 써야 실행 중인 로그가 바로 새 값을 쓴다.
- 쿼리 창의 쿼리 기록(General > Query History)과는 다른 기능이다. 이 파일은 메타데이터 읽기 같은 내부 쿼리까지 담는 글자 로그다.
- 2026-11-08: 기록할 것·쿼리 종류(`qm.objectTypes`, `qm.queryTypes`)는 View > Query Manager가 보일 항목이기도 하다(SWT와 같은 키, 그 창의 보일 항목 메뉴가
  같은 키를 바꿈). 그래서 로그 파일을 꺼도 이 두 줄은 켜 두고 "보기 > 쿼리 관리자에 보일 항목도 같음" 안내를 붙였다([swing-query-manager.md](swing-query-manager.md)).
- 범위 밖에서 확인한 것(고치지 않음): `QMLogFileWriter.initLogFile`은 켜진 상태에서 다시 만들 때 앞의 `FileWriter`를 닫지 않는다. `qm.` 키가 하나
  바뀔 때마다 불리므로 OK 한 번에 여러 키를 바꾸면 같은 파일의 쓰기 핸들이 여러 개 남는다(upstream 그대로).

### Options > Object Explorer, Notifications (10–11순위, 전역만)

| 페이지 | 항목 | 키 | 읽는 곳 |
|---|---|---|---|
| Object Explorer(`NavigatorOptionsPage`) | 이름순 정렬, 대소문자 무시, 폴더 먼저 | `navigator.sort.case.insensitive`, `navigator.sort.case.insensitive.ignore.case`, `navigator.sort.forlers.first` | `DBNUtils.filterNavigableChildren`(개체 탐색기가 자식을 읽을 때, `ObjectExplorerPanel.readChildren`) |
| Notifications(`NotificationsOptionsPage`) | 오래 걸린 작업이 끝나면 알림, 기준(초) | `agent.long.operation.notify`, `agent.long.operation.timeout` | `SwingSystemAgent`(네이티브 도구 작업이 끝날 때) |

- 정렬은 이미 펼친 노드를 새로 고치거나 다시 펼쳐야 바뀐다.
- 두지 않은 것: `navigator.show.folder.placeholders`는 프로젝트 리소스 노드(`DBNProjectDesktop`)만 읽는다. Swing 개체 탐색기는 루트에 서버만 보이고
  리소스 노드를 보이지 않아 효과가 없다(4개 중 정렬 3개만).

### Options > Data Transfer, Code Completion (11–12순위, 연결별 값 있음)

| 페이지 | 항목 | 키 | 읽는 곳 |
|---|---|---|---|
| Data Transfer(`DataTransferOptionsPage`) | 새 테이블·열 이름의 대소문자, 공백 처리, 최대 형식 길이 | `nameCaseMapping`, `replaceMapping`, `maxTypeLengthMapping` | `DatabaseTransferUtils.getTransformedName`(데이터베이스 복사 매핑 `DatabaseCopyMappings`), `DatabaseMappingAttribute`, `StreamDataImporterColumnInfo` |
| Code Completion(`CodeCompletionOptionsPage`) | 테이블 별칭(넣지 않음 / `my_table mt` / `my_table AS mt`), 스키마 없이 이름, 항상 전체 이름 | `sql.proposals.insert.table.alias`, `SQLEditor.ContentAssistant.proposals.short.name`, `…proposals.long.name` | `SQLQueryCompletionTextProvider`(쿼리 창 자동 완성 엔진), `SqlCompletionAnalysis`의 완성 문맥 |

- 데이터 전송 키는 **대상** 연결의 설정 저장소가 자기 값을 가지면 그 값을, 아니면 데이터 전송 번들의 저장소(`DTActivator`)를 읽는다. 그래서 이
  페이지의 전역 값은 플랫폼 저장소가 아니라 그 번들 저장소에 쓴다(`SettingsPage.getGlobalStore`를 이 페이지가 바꿈). 연결 페이지는 연결이 자기 값을
  갖지 않은 키를 번들 저장소에서 읽어 보인다.
- 짧은 이름과 전체 이름은 엔진이 짧은 이름을 먼저 보므로 하나를 켜면 다른 하나를 끈다.
- 두지 않은 것: `SQLEditor.ContentAssistant.activate.hippie`(단어 완성)는 예전 엔진(`SQLCompletionAnalyzer`)만 읽는다. Swing 쿼리 창은 새 엔진
  (`SQLQueryCompletionAnalyzer`)을 써서 효과가 없다.

### Options > Altibase, CUBRID, Mimer SQL (13순위)

| 페이지 | 항목 | 키 | 읽는 곳 |
|---|---|---|---|
| Altibase(`AltibaseOptionsPage`, Altibase 연결의 연결별 값) | 메시지 탭에 PRINT 출력, 실행 계획 단계 들여쓰기 | `altibase.dbms.output`, `altibase.plan.prefix` | `AltibaseDataSource`의 출력 읽기, `AltibasePlanNode.getPlanString` |
| CUBRID(`CubridOptionsPage`, CUBRID 연결의 연결별 값) | 메시지 탭에 DBMS_OUTPUT, 버퍼 크기 | `pref.dbms.output`, `pref.dbms.output.buffer.size` | `CubridDataSource`(세션을 열 때 서버 켜기와 버퍼, 문장마다 켜짐 여부, 문장 전에 열린 세션을 설정에 맞춤) |
| Mimer SQL(`MimerOptionsPage`, 전역만) | CASCADE 삭제를 다시 묻지 않을 개체 종류, 공간 형식 4개 | `mimer.confirm.drop_cascade.<종류>`, `mimer.gis.location.auto_map`, `mimer.gis.coordinate.auto_map`, `mimer.gis.latlong.auto_decimal`, `mimer.gis.grid_point_text` | `MimerCascadeDropUtil`, `MimerGisUtils`(플랫폼 저장소) |

- 기본값은 각 번들의 설정 초기화가 넣는다. Oracle 페이지처럼 값을 읽기 전에 번들의 클래스를 불러 시작시킨다. Swing 번들이 세 번들을 새로
  `Require-Bundle`로 가진다(제품의 `com.seizia.chromite.db.feature`에 이미 들어 있다).
- Altibase·CUBRID 서버 출력은 세션을 열 때 서버 쪽(Altibase 콜백, CUBRID `ENABLE`)을 맞추고, 그 뒤 바꾸면 쿼리 창이 다음 문장 전에 열린 세션을 맞춘다
  (`syncServerOutput`, 2026-11-08 evening #19, [swing-server-output.md](swing-server-output.md)). 그래서 둘 다 다시 연결을 묻지 않는다
  (전에는 CUBRID가 `isAppliedWithoutReconnect` false로 다시 연결을 물었다).
- Mimer 공간 형식 키는 모델이 읽기·쓰기 전마다 기본값을 등록해야 끔이 저장된다(`MimerConstants` 주석). 등록 메서드가 모델 패키지 안에만 있어 페이지도
  같은 기본값을 등록한다(SWT `PrefPageMimer`와 같은 방식).
- 두지 않은 것:
  - `altibase.explain.plan.type`: "EXPLAIN PLAN = ON"은 계획을 얻으려고 문장을 실제로 실행한다(`AltibaseExecutionPlan`, SELECT가 아니면 `execute`).
    계획 보기가 데이터를 바꿀 수 있어 확인 절차 없이 고를 수 있게 두지 않았다. 기본값(ONLY, 실행 안 함)으로 동작한다.
  - PostgreSQL `show-database-statistics`: 연결의 공급자 속성으로 읽지만 `collectObjectStatistics`를 부르는 곳이 Swing에(모델에도) 없고, 공급자
    속성으로 선언되지도 않았다. 효과가 없다.

## 목록, 찾기, 페이지 열기 (2026-11-08, app round 1 #8·#13)

사용자 요청: "옵션 화면의 크기를 조금 더 키운다. 옵션의 좌측 항목의 영역이 너무 좁다", "옵션의 우측 페이지의 좌측에 여백이 없다",
"옵션의 좌측 패널 상단에 고정된 검색 영역을 추가한다", Chromite Agent 창의 설정 단추는 "에이전트 설정 탭으로 이동하게 하라".

### 구조 (`OptionsDialog`)

- 왼쪽: 찾기 칸(고정) + "일치 없음" 글 + 페이지 목록(`JList`, 스크롤). 오른쪽: 페이지 자리(`CardLayout`)를 `JLayer`로 감싼 것.
- 전에는 `JTabbedPane(LEFT, SCROLL_TAB_LAYOUT)`이었다. 탭은 숨길 수 없어 찾기로 걸러 보일 수 없으므로 목록 + 카드로 바꿨다.
  페이지 클래스와 저장(`apply`)은 그대로다.
- 페이지마다 식별자가 있다: `general`, `editor`, `editor-colors`, `connection-types`, `templates`, `updates`, `agent`(`OptionsDialog.PAGE_AGENT`),
  설정 키 페이지(`SettingsPage`)는 클래스 이름(`SqlExecutionOptionsPage` 등). 카드 이름도 이 식별자다.
- `OptionsDialog.open(owner, pageId)`: 그 페이지를 고른 채 연다. 화면 이름(언어마다 다름)이 아니라 식별자로 고르므로 목록 구조가 바뀌어도 동작한다.
  없는 식별자면 로그에 남기고 첫 페이지를 연다. `open(owner)`(Tools > Options, macOS 설정 메뉴)는 전처럼 첫 페이지.

### 크기와 여백

> 2026-11-12(#59) 바뀜: 목록 최소 폭 236, 창은 고정 크기 1080×760 + 페이지 스크롤, 페이지 여백 위 8·좌우 28·아래 20. 아래는 그 전의 기록이다
> (아래 "옵션 화면 모양과 저장 (#59)").

- 목록 폭: 최소 220(UIScale), 가장 긴 이름 + 스크롤 막대가 더 넓으면 그 폭. 목록 높이는 8줄로 잡아 대화상자 높이는 페이지가 정한다.
  이름은 목록 기본대로 왼쪽 정렬이고 잘리지 않는다(영어 가장 긴 이름 121, 한국어 97, 목록 안쪽 폭 218).
- 창 크기: `pack()` 크기를 최소 크기로 두고, 여는 크기는 그 1.18배(화면 작업 영역의 90%를 넘지 않음, pack 크기보다 작지 않음).
  실측(150%, 4K 화면 = 논리 2560×1440): 영어 pack 1265×762 → 1493×899, 한국어 1159×762 → 1368×899.
- 왼쪽 여백: 페이지 자리(`pageHolder`)에 왼쪽 20(디자인 토큰의 그룹 간격)을 한 번 준다. `wrapPage`는 탭이 위에 있는 다른 대화상자 23곳도 쓰므로
  바꾸지 않았다(위 12만 띄움). 오른쪽 여백은 대화상자 안쪽 여백 12(디자인 토큰 Dialog insets)로 전과 같다. 실측: 페이지 왼쪽 x = 252(목록 220 + 여백 12 + 20),
  오른쪽 간격 12.
- 넓어진 공간: 설정 키 페이지와 General·Editor 페이지는 `addRow` 격자가 마지막 열(설명)에 남는 폭을 주어 입력란이 늘어나지 않는다.
  표가 있는 페이지(Connection Types, Templates, Drivers의 Maven 저장소, Data Formats)는 표가 넓어진다(의도). Chromite Agent > Providers만
  입력란이 오른쪽 끝까지 늘어나서, 양식 묶음을 자기 폭으로 두었다(`AiOptionsPage.createProvidersTab`).

### 찾기

- 찾기 칸: 자리 표시 "설정 찾기"/"Search settings"(`CoreMessages.options_search_placeholder`), 돋보기 아이콘, 지우기 단추.
- 찾는 글: 페이지 이름 + 페이지 안의 라벨, 단추·체크 상자 글, 제목 테두리 글, 하위 탭 이름. 숨긴 하위 탭과 카드의 내용도 찾는다(누르면 보이므로).
  다른 숨긴 구성 요소와 셀 그리기용 구성 요소(`CellRendererPane`, 마지막에 그린 칸의 글을 가짐)는 뺀다. 콤보 상자 항목, 표 내용, 입력란 값은 찾지 않는다.
- 비교: 소문자로 바꾸고 공백을 모두 뺀 글에 찾는 글이 들어 있는지("API키"가 "API 키:"와 일치). HTML 라벨은 태그와 흔한 엔터티를 뺀다.
- 결과: 일치하는 페이지만 목록에 남는다. 고른 페이지가 남으면 그대로, 아니면 첫 일치 페이지. 일치가 없으면 "일치하는 설정이 없습니다"/"No matching settings"
  (`options_search_no_match`)를 찾기 칸 아래에 보이고 마지막 페이지를 그대로 둔다.
- 강조: 보이는 페이지에서 일치한 글을 `JLayer`(`MatchHighlighter`)가 위에 반투명으로 칠한다(`Component.focusedBorderColor`, 채움 알파 56 + 테두리).
  페이지 구성 요소는 바꾸지 않는다. 라벨·체크 상자는 선호 폭(늘어난 체크 상자 전체가 아니라 글), 제목 테두리는 제목 줄, 하위 탭은 탭 자리를 칠한다.
  안에 일치가 있는 하위 탭도 칠해서 숨은 일치가 어느 탭에 있는지 보인다.
- 키: Ctrl+F는 찾기 칸으로(글 전체 선택). 찾기 칸의 Esc는 글이 있으면 지우고(모든 페이지), 비어 있으면 대화상자의 Esc(닫기)로 넘어간다.
  대화상자를 열 때 포커스는 찾기 칸이 아니라 목록에 둔다(첫 페이지 그대로).

### 특정 페이지로 여는 곳

- Chromite Agent 창의 도구 막대 설정 단추와 공급자 미설정 안내의 설정 단추: `AiAssistantPanel.Host.openOptions(OptionsDialog.PAGE_AGENT)`.
  새 대화상자의 Agent 페이지는 첫 탭 Providers를 보인다([ai-agent.md](ai-agent.md) "설정 열기").
- 설정 화면을 글로만 가리키던 곳(2026-11-12, B12): 링크나 메뉴 항목이 그 페이지를 바로 연다. 공통 처리는 `OptionsLinks`
  (`createLink`: 결과 표의 링크 단추와 같은 모양의 "Open Settings" / "설정 열기", `open`: 대화상자의 주인 사슬에서 프레임을 찾고 없으면 메인 창).
  여는 방법은 `OptionsDialog.open(owner, pageId)` 하나이고 페이지 식별자만 쓴다(General은 `OptionsLinks.PAGE_GENERAL` = `"general"`,
  설정 페이지는 클래스 이름).

  | 곳 | 전의 안내 | 지금 | 여는 페이지 |
  |---|---|---|---|
  | Agent 확인 대화상자(`AiConfirmationDialog`) | "항상 허용" 도움말 "Options > Chromite Agent > Policy", 위험 도구 안내 | 확인란·안내 줄 오른쪽 끝에 링크 | Chromite Agent(`PAGE_AGENT`). 하위 탭은 고르지 못해 첫 탭(Providers)이 보인다 — Policy 탭을 고르려면 `OptionsDialog`에 하위 탭 인자가 필요하다(Options 재구성 브랜치 뒤) |
  | 쿼리 기록 대화상자(`QueryHistoryDialog`) | 개수 글 끝 "(Tools > Options)" | 글에서 지우고 개수 글 옆에 링크. Options를 닫으면 보관 일수를 다시 읽는다 | General(Query History 묶음) |
  | Object Explorer "더 읽기" 줄(`ObjectExplorerPanel`) | 도움말 "Options > General > Object Explorer"(도움말은 그대로) | 그 줄의 오른쪽 클릭 메뉴 맨 위에 Page Size Settings... / 한 번에 표시할 개수 설정(다른 줄에서는 숨김) | General(Object Explorer 묶음) |
  | Query Manager 보일 항목(`QueryManagerPanel`) | 도움말 "Tools > Options > Logs"(도움말은 그대로) | 보일 항목 메뉴 끝에 Log Settings... / 로그 설정 | Logs(`LogsOptionsPage`) |
  | 연결 대화상자 Settings(`ConnectionSettingsOverrides`) | "Otherwise the settings of Tools > Options apply." | 안내 옆에 링크 | 그 탭과 같은 설정 페이지(클래스 이름). 연결별 설정을 끈 탭이 보이는 Options 값은 연결 대화상자를 다시 열 때 다시 읽는다 |

  - 확인(2026-11-12, 테스트 에이전트, 150%, 쿼리 기록은 100%도): 쿼리 기록 링크 → General, Query Manager 메뉴 → `LogsOptionsPage`, 연결 설정 탭의 링크 9개 중
    첫·끝 → `SqlExecutionOptionsPage`·`DataTransferOptionsPage`(창 밖 패널이라 주인은 메인 창), Agent 확인 대화상자 WRITE·DANGEROUS → `agent`.
    Object Explorer "더 읽기" 줄의 메뉴 항목은 긴 폴더가 있는 DB가 없어 화면으로 보지 않았다. 캡처 `D:\Tools\_scratch\chromite-small-fixes-r2\shots\b12-*.png`.
  - 상태 줄 커밋 방식 칸의 "Click to open the Auto-commit setting of the connection"(`QueryStatusLine` 287행)은 Options가 아니라 연결 대화상자를 연다.
  - 업데이트 알림은 Options를 열지 않는다.

### 확인 (2026-11-08)

Attach API 테스트 에이전트(EDT에서 Swing 객체를 직접 다룸, OS 입력 없음), 150%, 영어·한국어. 기록과 화면: `D:\Tools\_scratch\chromite-options-dialog-2\`.

- 첫 페이지 General, 포커스 목록, 목록 25개, 크기·여백 위 실측.
- "timeout"/"시간 제한" → Errors and Timeouts 하나, 절 제목과 설명 글 2곳 강조. "api key"/"API키" → Chromite Agent, Providers 탭·안내·API 키 라벨 2개.
  "token"/"토큰" → Chromite Agent, MCP Server 탭 강조, MCP 탭으로 바꾸면 라벨·안내 강조가 보임. "zzzz-nothing" → 목록 0개, 일치 없음 글.
- Esc(글 있음) → 글 지움, 목록 25개. Esc(빈 칸) → 대화상자 닫힘. Ctrl+F(목록에서) → 포커스 찾기 칸.
- Agent 창 도구 막대 설정 단추, 미설정 안내 단추(`doClick`), `open(owner, "agent")` → 모두 Chromite Agent 페이지, Providers 탭.

## 테마 (2026-11-11, 앱 변경 #38)

사용자 요청(기업 환경 2026-11-09): "테마는 컴퓨터 os 세팅값을 기본값으로 한다".

### 구조

- 선택지: View > Theme와 Options > General > Theme에 **시스템 설정 따르기**(Follow System) / 라이트 / 다크. 저장값은 설정 `ui.theme`
  (`SYSTEM` / `LIGHT` / `DARK`, 이 번들의 작업 공간 설정). 저장값이 없으면 `SYSTEM`. 전에 라이트·다크를 고른 사용자의 `LIGHT`·`DARK`는 그대로 그 테마다.
- `ChromiteTheme`은 설치하는 테마(LIGHT·DARK, 편집기 색도 테마별)이고, `ChromiteTheme.Choice`가 사용자의 선택이다. `Choice.resolve()`가 설치할 테마를 고르고,
  `Choice.apply()`가 저장한 뒤 다르면 설치한다. 메뉴와 Options는 지금 테마가 아니라 저장된 선택을 보인다. Editor Colors의 테마 목록은 그대로 라이트·다크(색은 테마별).
- 운영체제 설정 읽기(`SystemTheme`): Windows는 `HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize`의 `AppsUseLightTheme`(0 = 어둡게,
  설정 앱의 "앱 모드"), macOS는 `defaults read -g AppleInterfaceStyle`(`Dark`). 그 밖·읽기 실패는 라이트. FlatLaf·JDK에는 이 API가 없다.
  JNA 플랫폼 번들(`com.sun.jna.platform`, 이미 제품에 있음)을 Swing 번들의 Require-Bundle에 더했다.
- 시작: 작업 공간을 잠그기 전(설정을 못 읽을 때)에 운영체제 설정의 테마를 설치한다(전에는 라이트). 기본값 사용자는 메인 창 앞에서 다시 설치하지 않고,
  시작 오류·작업 공간 잠금 대화상자도 운영체제 모습이다. 메인 창을 열 때 저장된 선택의 테마가 다르면 그것으로 바꾼다.
- **실행 중 따라가기:** Windows는 데몬 스레드가 `RegNotifyChangeKeyValue`로 Personalize 키가 바뀌기를 기다리고(폴링 없음), 바뀌면 다시 읽어
  선택이 `SYSTEM`이고 테마가 다르면 바로 바꾼다. macOS는 다른 앱에서 이 앱으로 돌아올 때(창 활성화, 직전 활성 창이 이 앱 것이 아님) 다시 읽는다.
  Linux는 따라가지 않는다(라이트).
- 시작 화면: 런처가 JVM보다 먼저 띄우는 브랜드 그림(어두운 그림)이고 Swing 시작 화면은 같은 그림에 글자만 얹으므로 테마를 쓰지 않는다(바꾸지 않음).
- 업데이트 진행 창: 끝낼 때 지금 설치된 테마(선택을 풀어낸 결과)를 기록(`record.dark`)하고, 도우미 창과 업데이트 뒤 첫 시작 창이 그 테마를 쓴다.
  첫 시작 창은 이제 기록이 라이트일 때도 라이트로 바꾼다(시작 때 운영체제 테마가 먼저 설치되므로). 기록이 없으면 운영체제 테마 그대로.

### 확인 (2026-11-11)

Attach API 테스트 에이전트(EDT에서 Swing 객체를 직접 다룸, OS 입력 없음), 빈 작업 공간, 이 PC는 `AppsUseLightTheme = 0`(어둡게).
기록: `D:\Tools\_scratch\chromite-theme-look\run\`.

- 저장값 없음 → 선택 `SYSTEM`, 테마 DARK. View > Theme: "[시스템 설정 따르기 *] [라이트] [다크]". 감시 스레드 "Chromite system theme watcher"가
  `RegNotifyChangeKeyValue`에서 기다림.
- 라이트 고름 → `ui.theme=LIGHT` 저장, 라이트 설치, 메뉴 "라이트 *". 다시 시작(OS는 어둡게) → 라이트 그대로. 시스템 설정 따르기 → DARK.
- 감시 방식: 같은 JNA 호출을 내 임시 키(`HKCU\Software\ChromiteThemeLookProbe`, 끝나고 지움)에 걸고 값을 쓰면 0 ms 안에 돌아옴(사용자의 Personalize 키는 건드리지 않음).
- **확인하지 못함:** 실제로 Windows 앱 모드를 바꿨을 때 실행 중인 앱이 바뀌는 것(사용자 PC 설정을 바꾸지 않음), macOS 전체.

## 옵션 화면 모양과 저장 (#59)

사용자 요청(2026-11-11, 참고 화면 받음): "옵션화면의 스타일을 첨부한 것과 같이 세련된 스타일로 변경한다". 저장 방식(사용자 결정, Turbrix와 같은 방식):
"토글은 바로 반영되게 하고, 나머지는 설정값을 변경할 경우 저장 버튼을 활성화해서 저장할 대상이 있는지 사용자가 알 수 있게 하는 방법을 쓴다".
참고 화면은 다른 앱의 설정 화면이다. 배치 방식만 따르고 그 앱의 이름·아이콘·문구·색은 쓰지 않았다(Chromite 테마 토큰과 아이콘 팩).

### 모양

- **왼쪽 목록:** 맨 위 찾기 칸(고정), 작은 회색 묶음 제목 아래 아이콘 + 이름 항목, 넉넉한 줄(위아래 7), 선택은 FlatLaf의 둥근 선택(`List.selectionArc`).
  묶음 제목은 고를 수 없다(키보드·마우스로 고르면 움직인 방향의 다음 페이지). 목록 오른쪽에 가는 구분선(`Chromite.dividerColor`). 최소 폭 236.
- **묶음(정한 기본값):** 일반(General, Object Explorer, Notifications, Logs) / 편집기(Editor, Editor Colors, Code Completion, Query Execution, Templates) /
  결과(Query Results, Binary and LOB, Data Formats, Data Transfer) / 연결(Connections, Connection Types, Errors and Timeouts, Metadata,
  Client Identification, Drivers) / 데이터베이스(Oracle, Altibase, CUBRID, Mimer SQL) / Chromite(Chromite Agent, Updates). 업데이트 한 항목만의 묶음을
  두지 않고 Agent와 함께 "Chromite" 묶음에 두었다. 페이지 식별자는 그대로라 `OptionsDialog.open(owner, pageId)`는 바뀌지 않는다.
- **항목 아이콘:** 아이콘 팩의 것을 쓴다(General `settings`, Object Explorer `object-explorer`, Editor `file-sql`, Query Results `results`,
  Connections `connection`, Drivers `connect`, 데이터베이스 페이지 `database`, Agent `ai-assistant`, Updates `refresh` 등). 생성기
  (`tools/branding/gen_ui_icons.py`)에 `palette`(Editor Colors)와 테마 단추 셋(`theme-system`, `theme-light`, `theme-dark`)을 더했다(두 팩, 밝게·어둡게).
- **오른쪽 페이지(`OptionsForm`):** 공통 행 도우미 `OptionsDialog.addSection`·`addRow`가 `OptionsForm`으로 그려 모든 페이지(약 40개, 연결 대화상자
  Settings의 같은 페이지 포함)가 한 번에 바뀐다. 페이지 코드는 그대로다.
  - 절: 굵은 제목(`font: +1 $semibold.font`) 아래 둥근 카드(테두리 `Component.borderColor`, 모서리 10). 저장 표준의 "칸마다 테두리와 제목"과
    참고 화면의 굵은 절 제목·가는 줄을 함께 따랐다.
  - 행: 왼쪽 이름과 그 아래 회색 설명(`Chromite.secondaryForeground`, 전에는 컨트롤 오른쪽에 있던 도움말), 오른쪽 끝에 컨트롤, 행 사이 가는 선
    (`Chromite.dividerColor`), 위아래 9. 이름과 컨트롤이 한 줄에 들어가지 않으면 컨트롤을 이름 아래로 내린다(폭에 따라 높이가 바뀜).
  - 체크 상자 행: 상자 글을 왼쪽 이름으로 옮기고 상자는 오른쪽 끝에 둔다(이름을 눌러도 바뀜, 사용 가능 여부·도움말을 이름이 따름).
    켜고 끄는 토글은 스위치 모양(`OptionsForm.SwitchIcon`, 켜짐 = 주 단추 바탕색(강조색), 꺼짐 = 체크 상자 테두리 색)이다. 값의 일부인 체크 상자
    (`OptionsForm.VALUE_CHECK_BOX`: 연결 유형의 칸, Maven 저장소 대화상자의 Snapshot)는 체크 상자 모양 그대로다.
  - 이름 없는 행(안내 글, 여러 상자를 담은 패널)은 행 전체 폭에 왼쪽부터. 행이 없는 절(표·미리 보기를 페이지가 직접 넣는 절)은 제목만 보인다.
  - 넓은 여백: 페이지 자리 위 8(페이지의 12와 합쳐 20), 좌우 28, 아래 20. 페이지는 세로로 스크롤되는 칸 안에 있고 칸의 폭을 따른다.
  - 표가 큰 페이지(Templates, Data Formats, Connection Types, Agent 하위 탭, Editor Colors)는 폭을 쓰는 지금 구조를 그대로 두고 여백·절 제목만 바뀐다.
- **테마:** General > 환경 > 테마는 아이콘 단추 셋(시스템·밝게·어둡게)의 둥근 묶음이다(`Chromite.island.canvas` 바탕, 도구 막대 토글 단추).
  도움말과 접근성 이름은 `ChromiteTheme.Choice`의 이름이다. 값이라 [저장]에서 적용한다.
- **창:** 여는 크기 1080×760(UIScale, 화면 작업 영역의 90% 이내), 최소 760×520. 전에는 pack 크기 × 1.18이었는데, 행이 넉넉해져 가장 긴 페이지에
  맞추면 창이 화면보다 커지므로 고정 크기 + 페이지 스크롤로 바꿨다.
- 글꼴과 글자색은 바꾸지 않았다(이웃 작업: 글자 선명도·대비). 새 토큰도 없다.

### 저장 (Turbrix 저장 표준, `OptionsSaving`)

Turbrix의 #722 "저장 표준"(STYLE_GUIDE 5.12, `apps/desktop/src/shell/settingsPage.ts`, `SettingsFooter.tsx`, `Options.tsx`)을 그대로 따랐다(읽기만 함).

| 종류 | 예 | 반영 |
|---|---|---|
| 토글 | 설정 하나를 켜고 끄는 체크 상자(스위치) | 누르면 바로 저장. 바뀐 수에 들지 않음 |
| 값 | 숫자·글 칸, 선택 상자, 테마 단추 묶음, 표 편집(연결 유형, 템플릿, Maven 저장소, 권한 정책, 데이터 형식), API 키·[키 지우기] | [저장]으로만. 바뀐 수에 듦 |
| 동작 | Test, 지금 확인, 토큰 다시 만들기·복사, 찾아보기, 드라이버 관리자 열기 | 값을 저장하지 않음. 화면의 저장하지 않은 값을 씀(Test) |

- **아래 줄(`OptionsSaving.Footer`):** 페이지 아래에 고정(스크롤해도 보임, 위에 가는 구분선). [저장](주 단추 모양, `Button.default.*` 색을 스타일로 줌 —
  루트 창의 기본 단추가 아니라 글 칸의 Enter로 저장되지 않음) [되돌리기], 그 옆에 "저장하지 않은 변경 N개"/"N unsaved changes"(1개는 단수).
  바뀐 값이 없으면 두 단추가 꺼지고 글이 없다. 값이 없는 페이지(Updates: 토글과 동작만)는 줄을 숨긴다.
- **칸(`OptionsSaving.Part`):** 이름, 바뀐 수, 저장, 되돌리기. [저장]은 바뀐 칸을 차례로 저장하고, 실패한 칸은 값이 화면에 남고 이름을
  "저장하지 못함: {이름}. 값은 그대로 있습니다. 그 칸에서 확인하세요."로 단추 줄 위에 한 줄로 보인다(이유는 도움말). 다른 칸은 저장된다. 성공 알림은 없다.
  - General: 환경(테마·아이콘 스타일), Object Explorer, Query Editor(완성 지연), Query History, Query Library. Editor: 글꼴, 서식. Editor Colors: 한 칸
    (테마·항목별 다른 색 설정의 수). Templates, Connection Types: 한 칸(바뀐·더한·지운 항목 수). Chromite Agent: 제공자마다, 권한 정책, MCP 포트.
  - 모델 키 페이지(`SettingsPage` 20개): `OptionsSaving.SettingsPagePart`가 페이지의 `save(Writer)`를 저장소 위의 기록용 층(`StagingStore`)에 돌려
    바뀔 키를 얻는다. 그 수 + 저장소 밖 값(`SettingsPage.getOtherChangeCount`: Drivers의 프록시 인증 정보·저장소 목록, Logs의 디버그 로그 한도)이 바뀐 수다.
    되돌리기는 `load(getGlobalStore())` + `revertOther()`. 페이지 클래스는 거의 고치지 않는다.
  - 토글 저장: 토글을 누르면 직전에 센 바뀐 키와 지금 바뀐 키를 비교해 그 사이에 바뀐 키만(같이 바뀐 상자 포함, 예: Code Completion의 이름 형식 둘)
    바로 쓰고 저장한다. 값 칸의 저장하지 않은 변경은 그대로 남는다. 저장은 키의 타입대로(`setValue(boolean)` 등) 해서 듣는 쪽이 받는 값의 타입이 같다.
  - 다시 세기: 페이지의 값 컨트롤(글 칸, 숫자, 선택 상자, 단추, 표 모델)에 듣는 것을 달고, 이 창의 마우스 놓기·키 놓기 뒤에도 EDT 다음 차례에 한 번
    센다(표 모델이 알리지 않는 편집도 셈). 표 셀을 편집하는 동안은 세지 않는다(데이터 형식 페이지의 저장이 편집을 끝내므로).
- **떠날 때·닫을 때:** 바뀐 값이 있는 페이지에서 다른 페이지를 고르거나 창을 닫으면(×, Esc) "저장하지 않은 변경"/"{페이지}에 저장하지 않은 변경이
  있습니다. 저장할까요?"를 [저장](기본) [버리기] [취소]로 묻는다. Esc = 취소. 저장이 실패하면 그 페이지에 머문다. 묻는 동안 목록 선택은 지금 페이지로
  돌아가 있다(목록 이벤트 안이 아니라 그 뒤에 묻는다). 바뀐 값이 있는 페이지는 찾기가 걸러도 목록에 남고 앞에 보인다.
- **확인/취소 없앰(원칙 4):** OK·Cancel 단추와 전체 저장(`OptionsDialog.apply`, `applyStep`), 페이지마다의 `apply()`(Updates, Agent,
  Templates·Connection Types는 `save`/`revert`로), 저장 실패 대화상자와 그 문구(`options_save_failed*`, `ai_options_save_failed`, `ai_policy_save_failed`,
  `ai_options_title`, `editor_templates_cant_save`, `editor_templates_title`, `drivers_proxy_save_error`, `drivers_maven_save_error`)를 지웠다.
  저장 실패는 아래 줄로 알린다. 보안 저장소 실패의 설명(`ai_options_save_failed_secure_storage`)은 예외 글로 남겨 아래 줄 도움말에 보인다.
- 연결·트랜잭션 안전에 영향을 주는 설정(자동 롤백, 조회 트랜잭션 끝내기, 유휴 연결 끊기)은 Connection Types 표의 칸이라 값이다([저장]으로 저장하고
  열린 세션의 커밋 방식은 전처럼 `AutoCommitApplier`가 묻는다). 즉시 적용 토글이 아니므로 켤 때 안내 줄은 따로 두지 않았다.

### 이웃 작업

- 글자 선명도·대비(#43/#55, 테마 색·글꼴)는 손대지 않았다. 그 작업이 General에 더한 "UI 글꼴" 행(아래 "UI 글꼴")은 이 행 모양에 들어가고
  환경 칸의 값으로 센다([저장]으로 저장, 즉시 적용 토글 아님).

### 고친 버그: Notifications의 끄기

- 원인: 두 키(`agent.long.operation.notify`, `.timeout`)는 등록된 기본값이 없고 페이지는 "설정 없음 = 켬, 30초"로 읽는데
  (`SwingSystemAgent.isLongOperationNotify`), 저장은 `getBoolean`/`getLong`(설정 없음 = false, 0)과 비교했다. 그래서 열기만 해도 기본값을 다시 썼고
  (새 저장 표준에서는 "바뀐 값 2개"로 보였음), 끄기는 `setValue(false)`가 등록되지 않은 기본값(false)과 같아 저장소가 키를 지워 다시 켬으로 읽혔다
  (`BundlePreferenceStore.putBoolean`). 새 작업 공간에서 끄기가 저장되지 않던 오래된 버그다.
- 고침: 읽는 방식과 비교하고, 끔은 글자 "false"로 쓴다(`NotificationsOptionsPage.save`). 같은 방식(등록 기본값 없이 `contains`로 읽는 키)의 다른
  페이지는 없다(모든 페이지를 열어 바뀐 수 0 확인).

### 확인 (2026-11-12)

Attach API 테스트 에이전트(EDT에서 Swing 객체를 직접 다룸, OS 입력 없음), 빈 작업 공간. 기록과 화면: `D:\Tools\_scratch\chromite-options-restyle\run\`,
`shots\`.

- 25개 페이지를 하나씩 열어 바뀐 수 0(Notifications 고친 뒤), Updates는 아래 줄 없음. 창 1080×760, 최소 760×520. 목록에 묶음 제목 6개.
- 한국어 150% 어둡게: General(UI 글꼴 행 포함, 아래 설명 글), Query Execution, Connection Types, Chromite Agent, Data Formats, Editor, Editor Colors,
  Query Results, 찾기("시간 제한") 화면. 영어 100% 밝게: General, Query Execution, Connections, Templates, Connection Types, Agent, 찾기("timeout").
- 값: 완성 지연 300 → "저장하지 않은 변경 1개", [저장] 켜짐. 테마 단추 어둡게 → 2개. 다른 페이지 고름 → 질문(저장·버리기·취소), 취소 → 그대로,
  [되돌리기] → 0개. 문 구분 기호 ";;" → 1개, 저장소는 ";" 그대로, 페이지를 떠나며 [저장] → ";;" 저장되고 Editor로 넘어감. UI 글꼴 → 1개(값).
- 토글: 변수(`sql.variables.enabled`)를 누르면 바로 false/true로 저장되고 바뀐 수 0. 문 구분 기호를 바꾼 채 토글을 누르면 토글만 저장되고
  구분 기호는 저장되지 않은 채 1개로 남음. DROP 확인 토글은 바로 바뀜. Notifications 끄기 → `false` 저장, 켜기 → `true`.
- 닫기: 값 바꾼 채 닫기 → 질문, [버리기] → 닫힘, 다시 열면 0개. 값 없이 닫기 → 바로 닫힘. 찾기 중 바뀐 값이 있는 페이지(Query Execution)는
  일치하지 않아도 목록에 남고 선택된 채 보임.
- **확인하지 못함:** 저장 실패 줄(일부러 실패시킬 방법이 없음: 코드 경로만), macOS, 실제 마우스·키 입력(모두 Swing 객체로 다룸).
## UI 글꼴 (2026-11-12, 앱 변경 #43 → #67)

**지금(앱 변경 #67): Options에 UI 글꼴 행이 없다.** 사용자 결정(2026-11-12) "vscode와 같이 변경한다": UI 글꼴은 운영체제 글꼴 하나다
(Windows Segoe UI + 한글 맑은 고딕, macOS 시스템 글꼴, 크기 13). 구조와 측정은 [design-spec.md](design-spec.md) "적용 현황 — 운영체제 UI 글꼴 13".

- 지운 것: General > Environment의 **UI 글꼴** 행(자동·Pretendard·시스템 글꼴과 안내 글), `UiFont`(선택과 저장, 번들 Pretendard 등록),
  `ChromiteSwingApplication.openMainWindow`의 글꼴 비교, 문구 키 `options_ui_font`·`options_ui_font_hint`·`ui_font_auto`·`ui_font_system`(영어·한국어).
- 저장된 설정 `ui.font`(`AUTO` / `PRETENDARD` / `SYSTEM`)는 읽는 곳이 없어 무시된다. 옮기지 않는다(메인 세션 결정).

이전(#43, 2026-11-12 오전): 회사 PC(클라우드 VDI, 100% 배율)에서 Chromite 글자만 흐리다는 보고로 UI 글꼴 선택을 두었다. 자동(기본값)은 기본 화면 배율이
150% 미만이면 시스템 글꼴(FlatLaf의 `win.messagebox.font`: 한국어 Windows는 맑은 고딕 12), 그 이상이면 번들 Pretendard 12였다. 원인 측정은
design-spec "적용 현황 — 글자 선명도와 대비".

## 잘리던 한 줄 안내 (2026-11-12, 앱 변경 #71 b)

- **결함:** Chromite Agent > MCP 서버의 설명(영어 모든 배율, 한국어 100%에서 2px 모자람)과 템플릿 페이지 아래 안내(모든 구성)가 말줄임표로 잘렸다.
  #228로 대화상자가 작아지고 #236으로 글자가 13이 된 뒤다.
- **원인:** 두 글이 한 줄 `JLabel`이었고 배치(GridBag의 열, BorderLayout 아래)가 준 폭이 글보다 좁았다.
- **고침:** `WrappingNote`(읽기 전용 `JTextArea`, 라벨 글꼴): 자리가 있으면 한 줄, 없으면 줄을 바꾼다. 선호 폭은 한 줄 폭이고 높이는
  배치가 준 지금 폭에서 접은 줄 수다. 폭이 바뀌면 한 번 다시 배치한다. 글은 줄이지 않았다.
- **줄 바꾸는 자리(2026-11-12, 검토 후속):** 공백에서만 바꾼다(모든 언어, 한국어의 keep-all). 줄 하나보다 넓은 낱말 하나만 글자 사이에서 끊는다(잘리거나 밖으로
  그려지지 않게). 처음 판은 `JTextArea`의 줄 바꿈을 썼는데, Swing은 유니코드 줄 나눔 규칙대로 한글 음절 사이 어디서나 끊어 "…127.0.0.1에서만 받습니 / 다."
  (MCP 설명, 한국어 100%), "…를 설정 / 하세요."(Agent 설정 안내, 한국어 150%)가 되었다. 이제 `WrappingNote.wrap`이 줄을 나누고 직접 그린다(숨은 문자를 넣지 않아
  글꼴과 상관없음). 영어는 전과 같다(공백에서만 바뀌던 그대로). 고친 뒤 "…127.0.0.1에서만 / 받습니다.", "…모델)를 / 설정하세요.". 확대 비교 `D:\Tools\_scratch\visual-recheck-fixes\sheets\h-korean-breaks.png`.
- **검사:** 테스트 에이전트가 옵션의 모든 페이지와 Chromite Agent 탭(제공자, 권한 정책, MCP 서버, 감사 기록)에서 보이는 `JLabel`·단추의 글을
  `SwingUtilities.layoutCompoundLabel`로 다시 배치해 말줄임표가 생기는지, `WrappingNote`의 선호 높이가 실제 높이보다 큰지 셌다.
  고친 뒤 한국어·영어 × 100%·150% × 밝은 새로 시작·어두운 전환 뒤 모두 **0**. 고치기 전 잘린 것은 위 둘뿐이었다(점검 에이전트 기록).
  `OptionsForm` 행의 회색 설명은 한 줄 라벨 그대로다(지금 잘리는 것 없음. 행이 좁으면 컨트롤을 아래로 내림).
- 확대 비교 `D:\Tools\_scratch\visual-recheck-fixes\sheets\b-options-hints.png`.

### 메시지 대화상자도 같은 줄 바꿈 (2026-11-12, 앱 변경 #82, `chromite/small-fixes-r3`)

- **결함(#242 검토):** SSH 호스트 키 질문에서 "…입니 / 다.", "넣 / 으십시오."처럼 한국어 낱말 가운데서 줄이 바뀌었다. 같은 질문을 재현하면
  "연결할까 / 요?", "바뀌 / 었을", "않았 / 습니다"였다.
- **근본 원인:** 메시지·확인·경고·오류 대화상자의 글은 `SwingPlatformUI.createMessageComponent`가 만드는 `JTextArea`(줄 바꿈 + 낱말 단위)다.
  Swing의 줄 바꿈 보기(`WrappedPlainView`)는 줄 나눔 위치를 `BreakIterator`로 찾아 한글 음절 사이 어디서나 끊는다. `WrappingNote`가 고친 것과 같은 원인이다.
- **고침(구현 하나):** `WrappingNote`의 줄 끝 규칙을 `WrappingNote.breakOffset`으로 떼어 `wrap`과 새 `WrappingNote.MessageArea`가 함께 쓴다.
  `MessageArea`는 평범한 읽기 전용 `JTextArea`(포커스·선택·복사 그대로)이고, 테마의 글 상자 UI(`FlatTextAreaUI`)에 줄 바꿈 보기만 바꿔 넣는다
  (`WrappedPlainView.calculateBreakPosition`이 `breakOffset`을 부름). 양방향·복합 글(문서의 `i18n` 속성)은 Swing 보기 그대로 둔다.
  줄 하나보다 넓은 낱말(파일 경로, SQL, URL)만 글자 사이에서 끊으므로 잘리지 않는다.
  이 보기는 글 상자가 낱말 단위로 줄을 바꿀 때(`lineWrap`과 `wrapStyleWord`)만 쓴다(#82 검토 후속). 줄을 바꾸지 않으면 Swing의 `PlainView`,
  글자 단위로 바꾸면 Swing의 `WrappedPlainView` 그대로다. 두 속성이 바뀌면 기본 UI가 보기를 다시 만들므로(`BasicTextAreaUI.propertyChange` →
  `modelChanged`) 선택도 따라간다. 확인: 새 `MessageArea` → `PlainView`, `lineWrap`만 켬 → `WrappedPlainView`, 둘 다 켬 → `SpaceWrappedView`,
  `lineWrap` 끔 → `PlainView`, `createMessageComponent`의 글 → `SpaceWrappedView`. 복사는 글 상자의 전송 처리기로 별도 클립보드 객체에 해서 확인했다
  (`D:\Tools\_scratch\small-fixes-r3\sheets\82-followup-view-check.log.txt`).
- **바꾼 곳:** `SwingPlatformUI.createMessageComponent`(메시지·확인·경고·오류 대화상자, JSch·SSHJ 질문, SQL 확인, 오류 대화상자 `ErrorDialog`,
  실행 제한·변경 제한 안내 등 이 메서드를 쓰는 모든 곳), `MaintenanceDialog`의 실행 확인 글, `SchedulerCommands`의 이벤트 상태 대화상자 글,
  `DriverBundleCreateDialog.createHint`(드라이버 묶음 만들기·설치 대화상자의 안내).
- **보고 그대로 둔 곳(이유):** `AiConfirmationDialog`는 줄을 바꾸지 않는 한 줄 라벨이다(이 결함 없음). HTML 라벨로 접는 안내(`ConnectionExportDialog`,
  `ConnectionImportDialog`, `ConnectionTransferCommands`, `ConnectionShellCommandsPage`, `SwingShellCommandsService`, `ResultSetExport`, `SettingsExportDialog`,
  `SettingsImportDialog`)는 같은 원인으로 한국어를 끊을 수 있지만 대화상자의 메시지 영역이 아니라 대화상자마다 배치를 바꿔야 해서 이번 범위 밖이다.
  대화상자 밖의 글(`NotificationToast`, `AccessNoticeBar`, `DiskUsagePanel`, Chromite Agent 패널의 오류·안내, `AiOptionsPage` 경고), 로그·메시지 창
  (`DatabaseTaskDialog`, `MaintenanceDialog`의 메시지, `ExecutionTracePanel`, `ProcedureDebuggerPanel`, `ServerLogPanel`, `AgentJobHistoryPanel`), 마법사 요약,
  편집 칸(입력, 식, 설명 편집), 라이선스 원문, `DataTransferWizard`의 오류 칸도 그대로다. 도구 설명은 Swing 기본(HTML)이며 바꾸지 않았다.
- **확인(이 브랜치 빌드, 한국어 150%·영어 100%):** 호스트 키 확인 질문 "… 계속 / 연결할까요?", 경고 "… 방금 / 바뀌었을 수 있습니다", "연결하지 /
  않았습니다", 긴 경로는 줄보다 넓어서만 글자 사이에서 끊김, 오류 대화상자의 긴 jar 경로도 잘리지 않음. 글 선택·복사 그대로(선택한 글이 클립보드에 들어감).
  비교 `D:\Tools\_scratch\small-fixes-r3\sheets\82-message-wrapping-ko150.png`. 회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 위 "바꾼 곳"의
  모든 대화상자 글의 줄 바꿈과 높이, `WrappingNote`를 쓰는 안내(규칙은 같고 구현만 나눔).

## 페이지별 정리

| SWT 페이지 | 결과 |
|---|---|
| Transactions | 효과 있는 키 없음(아래). 자동 커밋 기본값·유휴 트랜잭션 알림은 연결 유형으로, 격리 수준은 연결 대화상자로. "끊을 때 트랜잭션 처리"는 설정이 없고 끊기·다시 연결은 늘 묻는다(#97) |
| Connection Types | **추가함**(Options > Connection Types). 스마트 커밋만 뺌 |
| SQL Processing | **추가함**(Options > Query Execution, 연결별 값 2026-11-08): 구분 기호 4, 매개 변수 7, 오류 처리. 커밋 방식 2키는 규칙상 두지 않음, 나머지 12키는 효과 없음 |
| Data Editor, Binary Editor | **추가함**(Options > Query Results, 연결별 값 2026-11-08): 가져오기 크기, 이진 값 2, 읽기와 정렬 6(오후). LOB 5키는 Options > Binary and LOB. 정렬 정책은 효과 없음 |
| Data Formats | **추가함**: 숫자 형식 2(Query Results), 형식 프로필(로캘, 형식별 패턴: Options > Data Formats, 2026-11-08). 연결별 값 있음. 이름 있는 프로필은 두지 않음 |
| Oracle | **추가함**(Options > Oracle, Oracle 연결의 연결별 값 2026-11-08) 5키, 공급자 속성 3키 |
| PostgreSQL | 4키는 공급자 속성(이미 있음), `show-database-statistics`는 효과 없음(2026-11-08 확인: 통계 수집을 부르는 곳 없음) |
| Errors and Timeouts | **추가함**(2026-11-08, 연결별 값 있음) 5키. 오류 때 롤백·세션 단위 취소 2키는 규칙상 두지 않음 |
| Metadata | **추가함**(2026-11-08, 연결별 값 있음) 5키. 별도 연결은 이미 있음 |
| Client Identification | **추가함**(2026-11-08, 연결별 값 있음) 4키 |
| General, Connections | **추가함**(2026-11-08 오후, Options > Connections): 시간대, 드라이버 매개 변수 거부, Windows 인증서 저장소. 셸 명령 인증 허용은 효과 없음 |
| Drivers, Maven | **추가함**(2026-11-08 오후, Options > Drivers): 드라이버 폴더·파일 원본·버전 갱신, 프록시(사용자·비밀번호는 보안 저장소), Maven 저장소 목록 |
| Classpath | **추가함**(2026-11-08): Driver Manager > Global Libraries…(`ui.drivers.global.libraries`). Options > Drivers에서 드라이버 관리자를 연다 |
| Query Manager, Error Logs | **추가함**(2026-11-08 오후, Options > Logs): SQL 로그 파일 5키, 디버그 로그 크기·개수 |
| Navigator(정렬), Notifications(긴 작업 알림) | **추가함**(2026-11-08 오후, Options > Object Explorer·Notifications): 정렬 3키, 알림 2키. 폴더 자리 표시는 효과 없음 |
| Data Transfer(매핑 3키), Code Completion(3키) | **추가함**(2026-11-08 오후, 연결별 값 있음). 단어 완성(hippie)은 효과 없음 |
| Dictionaries | 효과 없음(2026-11-08 확인: 사전 화면이 Swing에 없음) |
| Altibase, CUBRID, Mimer | **추가함**(2026-11-08 오후, Options > Altibase·CUBRID·Mimer SQL). Altibase 실행 계획 방식은 두지 않음(계획 보기가 문장을 실행) |
| Network Profiles | 다음 작업: 전역·프로젝트 네트워크 프로필 편집(지금은 연결이 쓰는 프로필을 읽기 전용으로 보임) |
| Templates, AI | Swing 자체 저장으로 이미 있음(Options > Templates, Chromite Agent) |
| SQL Editor | `database.editor.separate.connection`만 **추가함**(Options > Query Execution의 쿼리 창 세션, 연결 대화상자 Connection Properties의 연결별 값, 2026-11-07). 나머지 키는 효과 없음 |
| User Interface, Editors, Toolbar Customization, Accessibility, Confirmations, Miscellaneous, Expert Settings, GIS, Appearance, Grid, Plain text, ERD, Code Editor, SQL Dialects, Scripts, macOS Mouse, Project Resources | 효과 없음: 키를 읽던 SWT 코드가 지워졌다. Swing에 같은 기능이 생기면 그때 그 기능의 키를 정한다 |
| DataDam, Projects | 의도적 제외에 준함(삭제 전에도 어떤 제품에도 없던 기능) |

## 다음 작업 (키 목록)

Swing 제품이 읽지만 화면이 없는 키다. 우선순위 순서로 적었다. 1–4순위와 6순위는 2026-11-08에, 5순위와 7–13순위는 같은 날 오후에 만들었다
(번호는 그대로 둠, "남은 키 (2026-11-08 오후)").

1. ~~연결별 값~~: 2026-11-08 연결 대화상자 Options > Settings("연결별 설정").
2. ~~데이터 형식 프로필~~: 2026-11-08 Options > Data Formats. 이름 있는 프로필 고르기는 Swing에서 읽는 곳이 내보내기 작업 설정뿐이라 두지 않음.
3. ~~Errors and Timeouts~~: 2026-11-08. `query.rollback-on-error`, `execute.cancel.checkTimeout`은 규칙상 두지 않음.
4. ~~Metadata~~: 2026-11-08.
5. ~~Data Editor·Binary Editor 나머지~~: 2026-11-08 오후 Options > Query Results의 읽기와 정렬 절, Options > Binary and LOB. `resultset.order.policy`와
   `resultset.reference.value.description.column.patterns`는 효과 없음으로 옮김.
6. ~~Client Identification~~: 2026-11-08.
7. ~~Connections, General~~: 2026-11-08 오후 Options > Connections. 시간대의 앱 전체 적용(S12)과 IP 스택(S13)은 B9 2부.
8. ~~Drivers·Classpath·Maven~~: 2026-11-08 오후 Options > Drivers. `ui.drivers.global.libraries`(전역 라이브러리)는 2026-11-08 Driver Manager >
   Global Libraries…([swing-driver-manager.md](swing-driver-manager.md) "전역 라이브러리"). 파일 원본의 모델 기본값(`https://dbeaver.io/files/jdbc/`)은
   2026-11-08 사용자 결정으로 그대로 둔다(Maven이 아닌 드라이버 파일과 네이티브 클라이언트 내려받기가 쓴다).
9. ~~Query Manager, Error Logs~~: 2026-11-08 오후 Options > Logs.
10. ~~Navigator~~: 2026-11-08 오후 Options > Object Explorer. `navigator.show.folder.placeholders`는 효과 없음.
11. ~~Notifications, Data Transfer~~: 2026-11-08 오후 Options > Notifications·Data Transfer.
12. ~~Code Completion~~: 2026-11-08 오후 Options > Code Completion. `SQLEditor.ContentAssistant.activate.hippie`는 효과 없음.
13. ~~DB별~~: 2026-11-08 오후 Options > Altibase·CUBRID·Mimer SQL. 남은 것: `altibase.explain.plan.type`은 "ON"이 계획 보기에서 문장을 실행하므로
    확인 절차를 만들 때까지 두지 않는다. PostgreSQL `show-database-statistics`는 효과 없음. `postgresql.dd.*`의 전역 기본값(`PostgreDialect`가 연결의
    공급자 속성이 없을 때 플랫폼 저장소를 읽음)은 **결정함**(2026-11-08 사용자 결정 5): Options에 전역 기본값을 두지 않는다. 연결 대화상자의 연결별
    Provider Properties는 그대로 둔다.
14. 효과 없는 키 중 Swing 기능이 생기면 다시 볼 것: `statement.timeout`(문장 시간 제한).
15. 2026-11-08 효과가 생긴 키([connection-lifecycle.md](connection-lifecycle.md)): `transaction.smart.commit`, `transaction.smart.commit.recover`(연결별 값만 읽음.
    기본값은 Options > Connection Types의 유형 칸), `connection.closeOnSleep`(macOS 절전 알림). 화면은 없다.

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

## 실행 확인 (2026-11-08)

이 브랜치의 빌드를 작업 공간·데이터 폴더 사본(`CHROMITE_DATA`)으로 실행하고, 앱 안에서 Swing API로 조작하는 시험 도구로 확인했다(OS 입력 없음).
증거: `D:\Tools\_scratch\chromite-settings-rest\run\shots\`, 단계 기록은 같은 폴더의 `s*.txt.log`. PostgreSQL 17(공유 컨테이너, `postgres` DB에서 SELECT만).

| 확인 | 결과 |
|---|---|
| Options 왼쪽 목록: Query Results, Data Formats, Errors and Timeouts, Metadata, Client Identification 페이지 | 열림(`01`–`07-…png`) |
| 전역 Data Formats: Timestamp 패턴을 `yyyy/MM/dd HH:mm`로 | 견본이 바로 `2026/11/08 03:18`. OK 뒤 `TIMESTAMP '2026-11-08 12:34:56.789'`가 결과 표에 `2026/11/08 12:34`(`08`, `10-…png`) |
| 연결 Settings > Query Results: 끔 상태 | 확인란 꺼짐, 페이지 칸은 모두 고칠 수 없음(`20-…png`) |
| 연결별 Maximum rows 3, 연결별 Number "Use grouping" 끔, Save | 다시 연결 질문 없음. 같은 쿼리 창에서 다시 실행하면 10행 중 3행, 숫자 `1234567.891`(전역은 `1,234,567.891`), 타임스탬프는 전역 패턴 그대로(`24-…png`). `data-sources.json`의 `custom-properties`에 `resultset.maxrows`, `dataformat.type.number.useGrouping` 등 |
| 연결별 Query Results 끄기 + Client Identification 켜고 이름 `chromite-w2-check`, Save | "Connection changed … reconnect?" 질문(`31-…png`), Yes 뒤 `current_setting('application_name')` = `chromite-w2-check`, 행 수는 다시 10(전역 200). `custom-properties`에서 `resultset.maxrows`가 지워짐 |

## 실행 확인 (2026-11-08 오후)

이 브랜치의 빌드를 작업 공간·데이터 폴더 사본(`CHROMITE_DATA`)으로 실행하고, 앱 안에서 Swing API로 조작하는 시험 도구로 확인했다(OS 입력 없음).
증거: `D:\Tools\_scratch\chromite-settings-rest-2\run\shots\`, 단계 기록은 같은 폴더의 `s*.txt.log`, `k1.txt.log`. PostgreSQL 17, MariaDB 11(공유 컨테이너,
MariaDB에는 확인용 `chr_settings_rest_2` 데이터베이스를 만들고 끝에 지움).

| 확인 | 결과 |
|---|---|
| Options 왼쪽 목록 | 새 페이지 11개가 보이고 목록이 넘치면 스크롤됨(`01-options-list.png`, 한국어 `40`–`51-ko-…png`) |
| 페이지마다 값을 바꾸고 OK, 다시 시작 | Query Results(레이블 대신 열 이름), Binary and LOB(BLOB 함께 읽기), Logs(기록 켬, 7일), Object Explorer(이름순), Notifications(60초), Code Completion(`AS` 별칭), Data Transfer(대문자), Altibase(들여쓰기 끔), CUBRID(20000), Mimer(Table 다시 묻지 않음, GIS_COORDINATE 켬), Maven 저장소 추가가 다시 시작한 뒤 그대로(`s2.txt.log`). 설정 파일에는 바뀐 키만, 데이터 전송 키는 그 번들의 설정 파일에 |
| Maven 저장소 추가(그룹 `com.example`) 후 OK | 고치기 전: OK가 `IllegalStateException: Attribute outside of element`(`MavenRegistry.saveConfiguration`)로 멈추고 대화상자가 닫히지 않음. 고친 뒤: `maven-repositories.xml`에 저장되고 다시 시작해도 목록에 있음 |
| 프록시 호스트·포트·사용자·비밀번호(시험 값) | 설정 파일에는 `ui.proxy.host`·`ui.proxy.port`만, 사용자·비밀번호는 데이터 폴더의 보안 저장소에(평문 없음). 다시 시작한 뒤 칸에 다시 보임. 지우면 보안 저장소에서도 지워짐 |
| SQL 로그 켬 | 작업 공간 `.metadata/chromite_sql_20261108.log`에 직접 실행한 쿼리만 기록(메타데이터 읽기는 기본 종류에서 빠짐) |
| 개체 탐색기 이름순(MariaDB 확인용 테이블, 열을 `zeta, alpha, mid` 순서로 만듦) | 켜면 `alpha, mid, zeta`, 끄고 Columns를 새로 고치면 `zeta, alpha, mid` |
| 결과 표 머리글(MariaDB `SELECT User AS my_user, Host AS my_host …`) | 고치기 전: 레이블 설정과 상관없이 `User, Host`. 고친 뒤: 기본은 `my_user, my_host`, "레이블 대신 열 이름 표시"를 켜면 `User, Host`(`52`, `53-ko-…png`) |
| 연결 대화상자 Options > Settings(MariaDB) | Binary and LOB, Code Completion, Data Transfer 페이지가 있고, 끈 Data Transfer 페이지는 전역 값(번들 저장소의 대문자)을 보임. 켜고 소문자로 저장하면 `data-sources.json` `custom-properties`에 `nameCaseMapping` 등 세 키 |
| "셸 명령" 인증 허용을 켜고 새 PostgreSQL 연결 대화상자 | 인증 목록이 그대로 "Username/password / PostgreSQL PgPass": 효과가 없어 화면에서 뺌 |

확인하지 못한 것: 클라이언트 시간대(ClickHouse 컨테이너 없음), Altibase·CUBRID·Mimer 동작(값 저장만 확인), Data Transfer 매핑의 실제 이름 변환, 디버그 로그 크기 즉시 적용,
Windows 인증서 저장소(다시 시작 뒤 적용, 키 저장만 확인), 끝까지 내리면 더 읽기·열 정렬 위치·필터할 때 감싸기·중첩 값·LOB 읽기의 동작.

## upstream 대비 변경점

- 쿼리 창 오류 처리에서 `STOP_ROLLBACK`(엔진 기본값)을 쓰지 않는다. 멈춘 스크립트가 열린 트랜잭션을 롤백하지 않는다.
- 스크립트 커밋 방식 설정(끝에서 커밋, N줄마다 커밋, 문장마다 자동 커밋)을 두지 않는다. 쿼리 창은 늘 `NO_COMMIT`이다.
- 연결 유형을 바꿔 연결의 커밋 방식이 바뀌면 열린 세션에 적용하고, 커밋하지 않은 변경이 있으면 묻는다(SWT는 다시 연결할 때 적용).
- 격리 수준을 연결 전에도 고를 수 있다(SWT는 연결되어 있을 때만).
- 연결별 설정은 연결 대화상자의 한 탭(Settings)에 모았다(SWT는 연결의 속성 페이지 트리). 연결별 값을 켜면 페이지의 모든 키를 연결의 값으로 쓴다
  (SWT 데이터 형식 페이지는 로캘 기본값과 다른 값만 써서, 전역에서 바꾼 패턴을 연결에서 기본값으로 되돌릴 수 없었다).
- Errors and Timeouts에 "오류 때 롤백", "취소가 안 되면 시간 뒤 연결 닫기"가 없다(트랜잭션 안전 규칙). 2026-11-08부터 모델도 두 키
  (`query.rollback-on-error`, `execute.cancel.checkTimeout`)를 읽지 않고, 남은 값은 시작할 때 지운다(모델 변경: `JDBCStatementImpl`, `AbstractJob`,
  `ModelPreferences`, `BasePlatformImpl`, `DataSourceSerializerModern`).
- 데이터 형식의 이름 있는 프로필 관리가 없다.
- Options의 페이지 목록이 왼쪽에 있다(SSMS Options처럼). Query Results는 General 탭의 절에서 탭으로 옮겼다. 2026-11-08부터 목록 위의 찾기 칸이
  페이지 이름과 설정 글로 페이지를 거른다.
- 모델 변경: `DataFormatterProfile`의 로캘 키 상수 세 개를 공개로 바꿨다(값 변경 없음).
- 모델 변경(2026-11-08 오후): `MavenRegistry.saveConfiguration`의 속성 순서(그룹이 있는 저장소 저장 실패 고침).
- 2026-11-08 오후: 클라이언트 시간대를 바꿔도 실행 중에 JVM 시간대를 바꾸지 않는다(SWT는 OK에서 바꿈). Altibase 실행 계획 방식을 고를 수 없다.
  Maven 저장소 순서를 바꿀 수 없다. 전역 라이브러리는 Options가 아니라 드라이버 관리자에서 고친다. 끝까지 내리면 더 읽기는 연결의 값이 없으면 전역 값을
  따른다(전에는 전역 값을 무시함).

## 변경 이력

- 2026-11-07 최초 작성: SWT 설정 페이지 키 전수와 Swing 효과 판정, Options > Query Execution·Connection Types·Oracle 탭, Query Results의 가져오기 크기·이진 값·숫자 형식,
  연결 대화상자의 격리 수준. 오류로 멈춘 스크립트가 수동 커밋 세션의 열린 트랜잭션을 묻지 않고 롤백하던 것을 고침.
- 2026-11-07 쿼리 창 세션(`database.editor.separate.connection`): Options > Query Execution과 연결 대화상자. 요약 수(추가함 23, 효과 없음 196)와 부록 갱신
  ([transactions.md](transactions.md) "쿼리 창마다 별도 연결").
- 2026-11-08 "다음 작업" 1–4·6순위: 연결별 설정(연결 대화상자 Options > Settings, `SettingsPage`·`ConnectionSettingsOverrides`), Options > Data Formats·
  Errors and Timeouts·Metadata·Client Identification, Query Results를 탭으로 옮김, Options 페이지 목록을 왼쪽으로. 요약 수(추가함 37, 규칙상 두지 않음 4,
  다음 작업 55)와 부록 갱신.
- 2026-11-08 Oracle DBMS_OUTPUT: 값을 바꾸면 열린 쿼리 창 세션에도 다음 문장부터 적용(도구 설명 갱신), 기본 연결 유형 이름을 언어와 상관없이 저장
  ([model-fixes.md](model-fixes.md) "작은 모델·안전·라이선스 수정").
- 2026-11-08 오후 "다음 작업" 5·7–13순위(`chromite/settings-rest-2`): Query Results 읽기와 정렬 절, Binary and LOB, Connections, Drivers(프록시, Maven 저장소),
  Logs, Object Explorer, Notifications, Data Transfer, Code Completion, Altibase, CUBRID, Mimer SQL. `SettingsPage.getGlobalStore`. 끝까지 내리면 더 읽기의
  전역 값이 무시되던 것과 그룹이 있는 Maven 저장소를 저장하지 못하던 것(`MavenRegistry`), 결과 표 머리글이 별칭 대신 원래 열 이름을 보이던 것
  (`ResultSetGridReceiver`)을 고침. 다시 확인해 효과 없는 키 6개를 옮김. 요약 수(추가함 85, 규칙상 두지 않음 5, 다음 작업 0, 효과 없음 202, devel 병합 뒤 다시 셈)와 부록 갱신.
- 2026-11-08 `ui.drivers.global.libraries`: Driver Manager > Global Libraries…로 만듦(후속 #55, [swing-driver-manager.md](swing-driver-manager.md) "전역 라이브러리"). 요약 수(추가함 38, 다음 작업 54)와 8순위·부록 갱신.
- 2026-11-08 스마트 커밋이 동작하게 되어 Connection Types에 두 칸, `transaction.smart.commit`·`.recover`·`connection.closeOnSleep`이 효과 있음으로 바뀜
  ([connection-lifecycle.md](connection-lifecycle.md)). 요약 수는 고치지 않음(메인 세션이 다시 셈).
- 2026-11-08 Altibase·CUBRID 서버 출력 설정이 열린 세션에도 다음 문장부터 적용되어 CUBRID 페이지가 다시 연결을 묻지 않음(evening #19,
  [swing-server-output.md](swing-server-output.md)).
- 2026-11-08 Options의 OK가 한 페이지의 실패로 멈추지 않고 모든 페이지를 저장한 뒤 실패를 알림, Logs 페이지 키가 바뀔 때 SQL 로그 파일 핸들을 닫음
  (evening #33, [model-fixes.md](model-fixes.md) "후속 수정 3").
- 2026-11-08 부록의 `dictionary.max.rows`: 참조 값 고르기가 읽게 되어 "효과 없음"에서 고침([swing-value-editors.md](swing-value-editors.md)).
- 2026-11-08 파일 원본 기본값(dbeaver.io)을 그대로 두기로 한 사용자 결정을 적음.
- 2026-11-08 `query.rollback-on-error`, `execute.cancel.checkTimeout`을 모델이 읽지 않게 하고 남은 값을 시작할 때 지움(`chromite/safety-defaults`,
  "Errors and Timeouts"의 "두 키를 끄는 방법"). SWT 설정을 가져오면 두 키가 동작하던 남은 위험이 없어짐. `postgresql.dd.*` 전역 기본값을 두지 않기로 한
  사용자 결정 5를 "다음 작업" 13순위에 적음.
- 2026-11-08 왼쪽 목록을 `JTabbedPane`에서 `JList` + `CardLayout`으로 바꾸고 넓힘(최소 220), 고정 찾기 칸(페이지 이름 + 설정 글, 일치 강조),
  창을 pack 크기의 1.18배로(화면 90% 이내), 페이지 왼쪽 여백 20, Agent Providers 양식 폭, 페이지 식별자로 열기와 Agent 창 설정 단추
  (app round 1 #8·#13, 위 "목록, 찾기, 페이지 열기").
- 2026-11-09 Connection Types에 "조회만 한 트랜잭션은 문장이 끝나면 자동으로 끝내기"(앱 변경 #31, 기본 꺼짐, 사용자 결정으로 연결 유형마다)
  ([idle-transactions.md](idle-transactions.md) "조회만 한 트랜잭션 끝내기"). 요약 수는 고치지 않음(메인 세션이 다시 셈).
- 2026-11-11 테마 선택에 시스템 설정 따르기(기본값), 운영체제 설정이 바뀌면 따라감(앱 변경 #38, 위 "테마").
- 2026-11-12 옵션 화면 다시 디자인(묶음·아이콘 목록, 카드 행, 스위치, 테마 단추 셋, 넓은 여백)과 확인/취소 대신 Turbrix 저장 표준
  (토글은 바로, 값은 [저장], 떠날 때 묻기). Notifications 페이지가 새 작업 공간에서 끄기를 저장하지 못하던 것을 고침(앱 변경 #59, 위
  "옵션 화면 모양과 저장 (#59)").
- 2026-11-12 General에 UI 글꼴(자동·Pretendard·시스템 글꼴, 기본값 자동)(앱 변경 #43, 위 "UI 글꼴").
- 2026-11-12 UI 글꼴 행과 그 설정을 지움: UI 글꼴은 운영체제 글꼴 하나, 크기 13(앱 변경 #67, 위 "UI 글꼴").
- 2026-11-12 설정을 글로만 가리키던 다섯 화면(여섯 곳)에 그 페이지를 여는 링크·메뉴 항목(`OptionsLinks`, B12, `chromite/small-fixes-r2`, 위 "특정 페이지로 여는 곳").
  `OptionsDialog`는 고치지 않음(재구성 브랜치와 병합 충돌을 피하려고).

## 부록: 키별 상태

`263b8ddb45`의 설정 페이지 키(페이지 클래스가 참조하는 상수와 감사 표의 키). 페이지 id, 링크, 변수 이름처럼 설정이 아닌 문자열은 뺐다.

| SWT 페이지 | 키 | Swing 상태 |
|---|---|---|
| General | `java.client.timezone` | **추가함**: Options > Connections(2026-11-08). 시작할 때 적용(S12, [platform-startup.md](platform-startup.md)) |
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
| Query Manager | `qm.historyDays` | **추가함**: Options > Logs(2026-11-08) |
| Query Manager | `qm.logDirectory` | **추가함**: Options > Logs(2026-11-08) |
| Query Manager | `qm.maxEntries` | 효과 없음(읽는 곳 없음) |
| Query Manager | `qm.objectTypes` | **추가함**: Options > Logs(2026-11-08) |
| Query Manager | `qm.queryTypes` | **추가함**: Options > Logs(2026-11-08) |
| Query Manager | `qm.storeLogs` | **추가함**: Options > Logs(2026-11-08) |
| Accessibility | `screen.reader.accessibility` | 효과 없음(읽는 곳 없음) |
| Accessibility | `screen.reader.force.focus` | 효과 없음(읽는 곳 없음) |
| Errors and Timeouts | `connection.close.timeout` | **추가함**: Options > Errors and Timeouts(2026-11-08) |
| Errors and Timeouts | `connection.open.timeout` | **추가함**: Options > Errors and Timeouts(2026-11-08) |
| Errors and Timeouts | `connection.validation.timeout` | **추가함**: Options > Errors and Timeouts(2026-11-08) |
| Errors and Timeouts | `execute.cancel.checkTimeout` | 두지 않음: 트랜잭션 안전 규칙(세션 단위 취소로 연결이 끊길 수 있음, 2026-11-08). 2026-11-08부터 모델이 읽지 않음 |
| Errors and Timeouts | `execute.recover.enabled` | **추가함**: Options > Errors and Timeouts(2026-11-08) |
| Errors and Timeouts | `execute.recover.retryCount` | **추가함**: Options > Errors and Timeouts(2026-11-08) |
| Errors and Timeouts | `query.rollback-on-error` | 두지 않음: 트랜잭션 안전 규칙(묻지 않고 롤백, 2026-11-08). 2026-11-08부터 모델이 읽지 않음 |
| Error Logs | `logs.debug.enabled` | 효과 없음(읽는 곳 없음) |
| Error Logs | `logs.debug.location` | 효과 없음(읽는 곳 없음) |
| Error Logs | `logs.files.output.maxCount` | **추가함**: Options > Logs(2026-11-08) |
| Error Logs | `logs.files.output.maxSize` | **추가함**: Options > Logs(2026-11-08) |
| Connections | `connection.closeOnSleep` | 다음 작업: 2026-11-08부터 읽음(macOS 절전 알림, [connection-lifecycle.md](connection-lifecycle.md) "절전"), 화면 없음 |
| Connections | `connection.sanitizeDriverProperties` | **추가함**: Options > Connections(2026-11-08) |
| Connections | `connection.shellAuthModelEnabled` | 효과 없음: Swing 연결 대화상자가 셸 명령 인증을 고를 수 없게 거름(`ConnectionAuthModels`, 2026-11-08 확인) |
| Connections | `connections.useWinTrustStoreType` | **추가함**: Options > Connections(2026-11-08, Windows만, 다시 시작 뒤 적용) |
| Connections | `navigator.settings.default.connectionPattern` | 효과 없음(읽는 곳 없음) |
| Client Identification | `database.connect.processEnvVars` | **추가함**: Options > Client Identification(2026-11-08) |
| Client Identification | `database.meta.client.name.disable` | **추가함**: Options > Client Identification(2026-11-08) |
| Client Identification | `database.meta.client.name.override` | **추가함**: Options > Client Identification(2026-11-08) |
| Client Identification | `database.meta.client.name.value` | **추가함**: Options > Client Identification(2026-11-08) |
| Transactions | `transaction.auto.close.enabled` | 효과 없음: #97에서 키를 지움. Options > Connection Types와 연결 대화상자의 유휴 트랜잭션 알림으로 바뀜 |
| Transactions | `transaction.auto.close.ttl` | 효과 없음: 위와 같음 |
| Transactions | `transaction.ddl.behavior` | 효과 없음(읽는 곳 없음) |
| Transactions | `transaction.show.notifications` | 효과 없음(읽는 곳 없음) |
| Transactions | `transaction.smart.commit` | 다음 작업: 2026-11-08부터 쿼리 창이 연결별 값을 읽음(`SmartCommit`), 연결별 화면 없음. 유형 기본값은 Connection Types |
| Transactions | `transaction.smart.commit.recover` | 다음 작업: 위와 같음 |
| Metadata | `database.meta.casesensitive` | **추가함**: Options > Metadata(2026-11-08) |
| Metadata | `database.meta.disableAdditionalRead` | **추가함**: Options > Metadata(2026-11-08) |
| Metadata | `database.meta.extra.ddl.info` | **추가함**: Options > Metadata(2026-11-08) |
| Metadata | `database.meta.separate.connection` | 이미 있음: 연결 대화상자(내부에서 읽기만, 메타데이터 별도 연결) |
| Metadata | `database.meta.server.side.filters` | **추가함**: Options > Metadata(2026-11-08) |
| Metadata | `database.props.expensive` | **추가함**: Options > Metadata(2026-11-08) |
| Drivers | `driver.selector.showCommercialDrivers` | 효과 없음(읽는 곳 없음) |
| Drivers | `ui.drivers.home` | **추가함**: Options > Drivers(2026-11-08) |
| Drivers | `ui.drivers.offline` | 이미 있음: 드라이버 관리자 |
| Drivers | `ui.drivers.sources` | **추가함**: Options > Drivers(2026-11-08) |
| Drivers | `ui.drivers.version.update` | **추가함**: Options > Drivers(2026-11-08) |
| Drivers | `ui.proxy.host` | **추가함**: Options > Drivers(2026-11-08) |
| Drivers | `ui.proxy.port` | **추가함**: Options > Drivers(2026-11-08) |
| Drivers | `ui.proxy.user` | **추가함**: Options > Drivers(2026-11-08, 비밀번호와 함께 보안 저장소) |
| Classpath | `ui.drivers.global.libraries` | **추가함**(2026-11-08 Driver Manager > Global Libraries…). 조사 당시: 읽음, 화면 없음 → 다음 작업 |
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
| Navigator | `navigator.show.folder.placeholders` | 효과 없음: 프로젝트 리소스 노드만 읽음(`DBNProjectDesktop`), Swing 개체 탐색기는 리소스 노드를 보이지 않음(2026-11-08 확인) |
| Navigator | `navigator.show.node.actions` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.objects.description` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.objects.tips` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.statistics.info` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.tooltips` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.show.tooltips.file.contents` | 효과 없음(읽는 곳 없음) |
| Navigator | `navigator.sort.case.insensitive` | **추가함**: Options > Object Explorer(2026-11-08) |
| Navigator | `navigator.sort.case.insensitive.ignore.case` | **추가함**: Options > Object Explorer(2026-11-08) |
| Navigator | `navigator.sort.forlers.first` | **추가함**: Options > Object Explorer(2026-11-08) |
| Confirmations | `com.seizia.chromite.core.confirm.` | 효과 없음: Swing 확인 대화상자는 이 키를 읽지 않음(A-C27) |
| Notifications | `agent.long.operation.notify` | **추가함**: Options > Notifications(2026-11-08) |
| Notifications | `agent.long.operation.timeout` | **추가함**: Options > Notifications(2026-11-08) |
| Notifications | `notifications.closeDelay` | 효과 없음(읽는 곳 없음) |
| Notifications | `notifications.enabled` | 효과 없음(읽는 곳 없음) |
| Notifications | `notifications.soundEnabled` | 효과 없음(읽는 곳 없음) |
| Notifications | `notifications.soundVolume` | 효과 없음(읽는 곳 없음) |
| Miscellaneous | `ui.show.holiday.decorations` | 효과 없음(읽는 곳 없음) |
| Expert Settings | `connections.debugNetworkConnections` | 2026-11-08: 시작할 때 적용(`javax.net.debug=all`, S13). 설정 칸 없음 |
| Expert Settings | `connections.preferredIPAddresses` | 2026-11-08: 시작할 때 적용(S13). 설정 칸 없음 |
| Expert Settings | `connections.preferredIPType` | 2026-11-08: 시작할 때 적용(S13). 설정 칸 없음 |
| Data Transfer | `captureUnsavedTaskLogs` | 효과 없음(읽는 곳 없음) |
| Data Transfer | `fallbackOutputDirectory` | 효과 없음(읽는 곳 없음) |
| Data Transfer | `maxTypeLengthMapping` | **추가함**: Options > Data Transfer(2026-11-08, 연결별 값) |
| Data Transfer | `nameCaseMapping` | **추가함**: Options > Data Transfer(2026-11-08, 연결별 값) |
| Data Transfer | `reconnectToLastDatabase` | 효과 없음(읽는 곳 없음) |
| Data Transfer | `replaceMapping` | **추가함**: Options > Data Transfer(2026-11-08, 연결별 값) |
| GIS Viewer | `gis.view.defaultSRID` | 효과 없음(읽는 곳 없음) |
| GIS Viewer | `gis.view.maxObjectsRender` | 효과 없음(읽는 곳 없음) |
| GIS Viewer | `gis.view.minZoomLevel` | 효과 없음(읽는 곳 없음) |
| Altibase | `altibase.dbms.output` | **추가함**: Options > Altibase(2026-11-08, Altibase 연결의 연결별 값) |
| Altibase | `altibase.explain.plan.type` | 두지 않음: "EXPLAIN PLAN = ON"은 계획 보기가 문장을 실행함(2026-11-08) |
| Altibase | `altibase.plan.prefix` | **추가함**: Options > Altibase(2026-11-08, Altibase 연결의 연결별 값) |
| CUBRID | `pref.dbms.output` | **추가함**: Options > CUBRID(2026-11-08, CUBRID 연결의 연결별 값) |
| CUBRID | `pref.dbms.output.buffer.size` | **추가함**: Options > CUBRID(2026-11-08, CUBRID 연결의 연결별 값) |
| Mimer SQL | `mimer.confirm.drop_cascade.` | **추가함**: Options > Mimer SQL(2026-11-08, 종류마다) |
| Mimer SQL | `mimer.gis.coordinate.auto_map` | **추가함**: Options > Mimer SQL(2026-11-08) |
| Mimer SQL | `mimer.gis.grid_point_text` | **추가함**: Options > Mimer SQL(2026-11-08) |
| Mimer SQL | `mimer.gis.latlong.auto_decimal` | **추가함**: Options > Mimer SQL(2026-11-08) |
| Mimer SQL | `mimer.gis.location.auto_map` | **추가함**: Options > Mimer SQL(2026-11-08) |
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
| PostgreSQL | `show-database-statistics` | 효과 없음: `collectObjectStatistics`를 부르는 곳 없음, 공급자 속성으로 선언도 안 됨(2026-11-08 확인) |
| Data Editor | `keep.statement.open` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.autofetch.next.segment` | **추가함**: Options > Query Results(2026-11-08, 전역 값이 무시되던 것 고침) |
| Data Editor | `resultset.automatic.row.count` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.cancel.timeout` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.column.label.ignore` | **추가함**: Options > Query Results(2026-11-08) |
| Data Editor | `resultset.confirm.beforeSave` | 이미 있음: Options > Query Results(2026-11-08 General 탭에서 옮김) |
| Data Editor | `resultset.datetime.editor` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.disableIfKeyMissing` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.key.use_all_columns` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.new.row.after` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.refreshAfterUpdate` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.edit.undoLevel` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.fetch.size` | **추가함**: Options > Query Results(2026-11-08 General 탭에서 옮김) |
| Data Editor | `resultset.filter.use.navigator` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.image.browser` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.mark.cell.value.occurrences` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.maxrows` | 이미 있음: Options > Query Results(2026-11-08 General 탭에서 옮김) |
| Data Editor | `resultset.maxrows.sql` | **추가함**: Options > Query Results(2026-11-08) |
| Data Editor | `resultset.order.mode` | **추가함**: Options > Query Results(2026-11-08) |
| Data Editor | `resultset.order.policy` | 효과 없음: `JDBCTable.readData`(테이블 자체 읽기)만 읽음, Swing 결과 표는 그 경로를 쓰지 않음(2026-11-08 확인) |
| Data Editor | `resultset.reread.on.scroll` | 효과 없음(읽는 곳 없음) |
| Data Editor | `resultset.show.errorDialog` | 효과 없음(읽는 곳 없음) |
| Data Editor | `sql.query.filter.force.subselect` | **추가함**: Options > Query Results(2026-11-08) |
| Binary Editor | `content.cache.blob` | **추가함**: Options > Binary and LOB(2026-11-08) |
| Binary Editor | `content.cache.clob` | **추가함**: Options > Binary and LOB(2026-11-08) |
| Binary Editor | `content.cache.maxsize` | **추가함**: Options > Binary and LOB(2026-11-08) |
| Binary Editor | `content.hex.encoding` | **추가함**: Options > Binary and LOB(2026-11-08) |
| Binary Editor | `content.memory.maxsize` | **추가함**: Options > Binary and LOB(2026-11-08, 전역만) |
| Binary Editor | `resultset.binary.editor` | 효과 없음(읽는 곳 없음) |
| Binary Editor | `resultset.binary.representation` | **추가함**: Options > Query Results(2026-11-08 General 탭에서 옮김) |
| Binary Editor | `resultset.binary.stringMaxLength` | **추가함**: Options > Query Results(2026-11-08 General 탭에서 옮김) |
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
| Appearance | `resultset.transform.complex.type` | **추가함**: Options > Query Results(2026-11-08) |
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
| Data Formats | `resultset.format.datetime.native` | 이미 있음: Options > Query Results(2026-11-08 General 탭에서 옮김) |
| Data Formats | `resultset.format.numeric.native` | **추가함**: Options > Query Results(2026-11-08 General 탭에서 옮김) |
| Data Formats | `resultset.format.numeric.scientific` | **추가함**: Options > Query Results(2026-11-08 General 탭에서 옮김) |
| Dictionaries | `dictionary.max.rows` | 2026-11-08부터 참조 값 고르기가 한 번에 읽는 수로 읽음(설정 화면은 아직 없음, [swing-value-editors.md](swing-value-editors.md)) |
| Dictionaries | `resultset.reference.value.description.column.patterns` | 효과 없음: 사전(참조 값 고르기)만 읽음, Swing에 그 화면 없음(2026-11-08 확인) |
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
| SQL Editor | `database.editor.separate.connection` | **추가함**: Options > Query Execution, 연결 대화상자(2026-11-07) |
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
| Code Completion | `SQLEditor.ContentAssistant.activate.hippie` | 효과 없음: 예전 자동 완성 엔진(`SQLCompletionAnalyzer`)만 읽음(2026-11-08 확인) |
| Code Completion | `SQLEditor.ContentAssistant.auto.activation.delay` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.auto.activation.enable` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.auto.keystrokes.activation` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.hide.duplicates` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.insert.case` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.insert.single.proposal` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.insert.space.after.proposal` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.matching.fuzzy` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.proposals.long.name` | **추가함**: Options > Code Completion(2026-11-08, 연결별 값) |
| Code Completion | `SQLEditor.ContentAssistant.proposals.short.name` | **추가함**: Options > Code Completion(2026-11-08, 연결별 값) |
| Code Completion | `SQLEditor.ContentAssistant.proposals.sort.alphabetically` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.qualified.column.names` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.replace.word` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.show.column.procedures` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.show.helpTopics` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.show.values` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `SQLEditor.ContentAssistant.use.global.search` | 효과 없음(SWT 키). 자동완성 일부는 Swing 자체 키(Options > General) |
| Code Completion | `sql.proposals.insert.table.alias` | **추가함**: Options > Code Completion(2026-11-08, 연결별 값) |
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
- 2026-11-12 MCP 서버 설명과 템플릿 안내를 줄 바꾸는 글(`WrappingNote`)로(앱 변경 #71 b, `chromite/visual-recheck-fixes`), 위 "잘리던 한 줄 안내". 회귀 테스트는 실행하지 않았다.
- 2026-11-12 `WrappingNote`는 공백에서만 줄을 바꿈(한국어 낱말 가운데서 끊던 것, #71 검토 후속), 위 "잘리던 한 줄 안내"의 "줄 바꾸는 자리". 회귀 테스트는 실행하지 않았다.
- 2026-11-12 메시지 대화상자의 글도 같은 줄 바꿈(`WrappingNote.breakOffset`, `MessageArea`, 앱 변경 #82, `chromite/small-fixes-r3`), 위 "메시지 대화상자도 같은 줄 바꿈". 회귀 테스트는 실행하지 않았다.
- 2026-11-12 #82 검토 후속: `MessageArea`는 낱말 단위로 줄을 바꿀 때만 줄 바꿈 보기를 씀(`lineWrap`이 꺼져 있으면 Swing 보기 그대로), 위 "메시지 대화상자도 같은 줄 바꿈". 회귀 테스트는 실행하지 않았다.
