# 회귀 테스트

## 목적

2026-11-07~08에 들어간 변경(PR #95–#133)이 나중에 다시 깨지지 않도록 회귀 테스트를 둔다. 사용자가 2026-11-08에
"실사용 빼고 다 진행하라"고 요청해 CLAUDE.md 7번 원칙(요청할 때만 회귀 테스트)에 따라 작성했다.

- 모델 기능(UI 없이 판단하는 코드)은 `test/` 아래 테스트 플러그인의 JUnit 테스트로 둔다.
- 화면, 설치, 브랜딩, 사이트처럼 자동화하기 어려운 것은 이 문서의 "화면 확인 목록"에 단계와 기대 결과로 적는다.

## 실행

전체 빌드에서 Tycho가 테스트 플러그인마다 OSGi 시험 앱(헤드리스)을 띄워 실행한다. 한 플러그인만 따로 돌리면 의존 번들이 없어 실패하므로
늘 전체 빌드로 실행한다.

```powershell
$env:JAVA_HOME='D:\Tools\jbrsdk-25'; $env:Path="D:\Tools\jbrsdk-25\bin;D:\Tools\maven\bin;$env:Path"
# 시험 앱의 데이터 폴더(보안 저장소 잠금 파일, 드라이버 폴더)와 임시 폴더를 사용자 폴더 밖으로
$env:CHROMITE_DATA='D:\Tools\_scratch\<branch>\data'; $env:TMP='D:\Tools\_scratch\<branch>\tmp'; $env:TEMP=$env:TMP
mvn -B -fae verify -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing *> D:\Tools\_build-logs\<name>.log
```

- `-fae`(fail at end): 한 테스트 플러그인이 실패해도 나머지 플러그인의 테스트를 끝까지 돌려 결과를 모두 얻는다.
- `CHROMITE_DATA`를 주지 않으면 시험 앱이 `%APPDATA%\ChromiteData`(실제 사용자 폴더)에 잠금 파일과 드라이버 폴더를 만든다.
- 결과는 플러그인마다 `test/<플러그인>/target/surefire-reports/`에 있고 로그의 `Tests run:` 줄로도 보인다.
- 모든 새 테스트는 Docker 시험 DB 없이 돈다. Git 명령 시험(`QueryLibraryGitTest`)은 `git`이 없으면 건너뛰고, 심볼릭 링크 시험은
  링크를 만들 권한이 없으면(Windows 일반 사용자) 건너뛴다.

## 자동 테스트

### 새 테스트 플러그인

호스트 번들의 패키지 전용 메서드를 부를 수 있도록 테스트 플러그인은 호스트의 조각(fragment)이다. 호스트마다 하나를 둔다(AGENTS.md
"Each test plugin mirrors a production plugin").

| 플러그인 | 호스트 | 비고 |
|---|---|---|
| `com.seizia.chromite.model.test` | `com.seizia.chromite.model` | 트랜잭션 안전, 일괄 실행, 숫자 표시 |
| `com.seizia.chromite.registry.test` | `com.seizia.chromite.registry` | 연결 내보내기 파일, 가져오기 충돌, 저장한 비밀번호, 드라이버 묶음, 실행 제한 |
| `com.seizia.chromite.model.ai.tools.test` | `com.seizia.chromite.model.ai.tools` | Chromite Agent |
| `com.seizia.chromite.ui.swing.test` | `com.seizia.chromite.ui.swing` | Query Library, 쿼리 창 실행 안전(UI 없이 부르는 부분). 호스트처럼 Java 25 |

네 플러그인은 `test/pom.xml` 모듈과 `com.seizia.chromite.test.feature`에 더했다.

- `ui.swing.test`의 런타임에서는 헤드리스 시험 앱이 시작되지 않는다. Swing 번들이 자기 앱 정의(`chromite-swing`)를 가지고 있어 모델이 그
  정의를 먼저 고르고(로그 "Multiple applications defined. Use the first one (chromite-swing)"), 시험 런타임에는 그 클래스가 없기 때문이다.
  그래서 이 플러그인의 테스트는 `ChromiteUnitTest`를 쓰지 않고, 플랫폼이 필요한 값(설정 저장소)은 시험 스레드에서만 바꾼다.
- OSGi 시험 런타임의 Mockito 기본 mock 방식은 final 클래스·메서드를 바꾸지 못한다. final 클래스(`QueryLibrary`)나 final 메서드를 가진
  클래스(`SQLQueryRowsSourceContext`)는 `withSettings().mockMaker("mock-maker-inline")`로 만든다.

### 테스트 클래스

"앱"은 OSGi 시험 앱이 필요한 테스트(`ChromiteUnitTest`), "단독"은 앱 없이 도는 JUnit 테스트다.

| 영역 | 클래스 (플러그인) | 확인하는 것 | 앱 |
|---|---|---|---|
| 트랜잭션 안전 | `QMUtilsTransactionStateTest` (model.test) | 사용자 문장만 셈(META·UTIL 제외), 커밋 뒤 새 트랜잭션, 자동 커밋은 열린 트랜잭션 없음, 실행 중 판단, `getTransactionStatements` 순서, `getLastUseTime`(연결·모든 목적 문장·커밋) | 단독 |
| | `IdleTransactionMonitorTest` (model.test) | 기간과 켜기(연결 설정 → 연결 종류, 개발 30분·테스트 15분·운영 10분), 자동 롤백은 사용자가 켰을 때만, 알림 한 번·다시 쓰면 닫기, Keep open은 그 트랜잭션에만, 사라진 문맥의 알림 닫기, 실행 중·자동 커밋·UI 없음(헤드리스)이면 알리지 않음, 다른 트랜잭션은 커밋·롤백하지 않음 | 단독 |
| | `TransactionRecoveryGuardTest` (model.test) | 중단된 트랜잭션 롤백은 사용자 문장이 있을 때만 멈춤, 시도 전 스냅숏(실패한 문장만 연 트랜잭션은 잃을 것 없음), 다시 연결 보류(열린 트랜잭션, Chromite Agent 세션), 핑 알림은 연결마다 한 번, 격리 문맥만 다시 열기와 쿼리 관리자 롤백 기록, 실행 중인 세션은 끊긴 것으로 보지 않음 | 단독 |
| | `DataSourceMonitorJobIdleTest` (model.test) | 유휴 연결 닫기 시간(연결 → 연결 종류), 마지막 사용(메타데이터 읽기 포함), 열린 트랜잭션·실행 중·에이전트 세션이면 닫지 않음 | 단독 |
| SQL 실행 안전 | `SqlExecutionDangerDetectionTest` (model.sql.test) | DROP 15종(JSQLParser가 못 읽는 DATABASE, USER, TABLESPACE, PROCEDURE, TRIGGER, 여러 테이블 DROP은 LSM 파서), `isDropStatement`, `ALTER TABLE … DROP COLUMN`은 DROP 아님, TRUNCATE, WHERE 없는 UPDATE/DELETE | 앱 |
| | `SqlExecutionSafetyTest` (ui.swing.test) | 쿼리 창의 `isDrop` 그대로, SWT 확인 설정 값의 뜻(`always`·`never`는 묻지 않음), 찾은 문장의 줄 번호 | 단독 |
| 단축키 | `UiKeysTest` (ui.swing.test) | 앱의 Windows 단축키 57개를 macOS 명령 키로: Windows는 그대로 Ctrl, macOS 예외(⌥⌘F, ⇧⌘M, ⇧⌘Z, ⌥⌘/, ⌃`, ⇧⌘D), macOS 시스템 키를 피함, 서로 겹치지 않음, 글자(⇧⌘F / Ctrl+Shift+F), 마우스 수정자 ([shortcuts.md](shortcuts.md)) | 단독 |
| | `ExecuteScriptsPermissionTest` (registry.test) | "Restrict script execute": 연결 종류의 제한, 연결 자체 목록이 종류 대신 쓰임 | 앱 |
| 자격 증명 | `ProjectCredentialsStorageTest` (registry.test) | 합치기(같은 연결·노드는 저장소 값이 이김, 예전 파일에만 있는 것은 남김), 저장소를 쓸 수 없으면 예전 파일을 지우지 않고 읽기만 함, 저장 거부, 둘 다 없으면 오류 | 앱 |
| | `ConnectionTransferFileTest` (registry.test) | 평문·암호화 왕복, 틀린·빠진 암호, 바뀐 암호문 바이트, 바뀐 머리말(AAD), 반복 횟수·태그 길이 범위, 다른 형식·버전·암호 방식, 한글 암호 | 단독 |
| | `ConnectionTransferConflictTest` (registry.test) | 같은 ID·같은 폴더의 같은 이름은 충돌, 다른 폴더는 아님, 선택 없으면 건너뜀, 연결 중인 연결은 바꾸지 않음, 사본은 새 ID와 "이름 (2)" | 앱 |
| 드라이버 | `DriverOfflineBundleTest` (registry.test) | 경로 안전(`..`, 절대 경로, 드라이브 문자, 역슬래시, Windows 금지 문자, 끝의 점·공백, 길이), 드라이버 폴더 밖 거부, 링크·막는 폴더, 중앙 디렉터리의 링크 항목·중복 이름·항목 수 상한, 해시·크기 불일치, 목록에 없는 항목, 전체 크기 상한, 올바른 묶음의 검사 통과 | 단독 |
| 데이터 | `ExecuteBatchImplNullColumnsTest` (model.test) | NULL 열이 바뀌면 일괄 처리가 아니어도 문장을 새로 준비(데이터가 밀리던 결함), 일괄 처리는 비우고 새로 준비, 같은 NULL 열은 문장 하나 | 단독 |
| | `DatabaseTransferPrimaryKeyTest` (data.transfer.test) | Copy Tables가 만드는 기본 키: 선언된 기본 키만, 키 순서, 고유 키·테이블이 아닌 원본은 없음 | 단독 |
| | `PostgreDialectBitMappingTest` (ext.postgresql.test) | 다른 DB의 한 비트 BIT → boolean, 여러 비트와 PostgreSQL 원본은 그대로 | 앱 |
| | `OracleMaxStringSizeTest` (ext.oracle.test) | `MAX_STRING_SIZE` 읽기(EXTENDED → 32767, STANDARD·행 없음·권한 없음 → 4000), 한도에서 VARCHAR2/CLOB | 앱 |
| | `TinyNumberFormattingTest` (model.test) | 작은 값 지수 표기 기본값, -2.5E-300이 -0이 되지 않음, 편집 값·SQL의 원래 표기는 반올림될 때만 지수 | 단독 |
| 자동완성 | `CallStatementTargetTest` (test.platform) | CALL/EXEC 대상: 기본은 프로시저만, SQL Server는 스칼라 함수(FN, FS)도, 표 값 함수는 아님 | 단독 |
| | `CallStatementNameResolutionTest` (model.sql.test) | `CALL app.`의 빈 이름 조각이 객체 찾기에 가지 않음(NPE), 이름 원천이 CALL | 앱 |
| Chromite Agent | `AIAgentToolSelectionTest` (model.ai.tools.test) | 연결 없는 대화는 모두, 다른 DB 종류의 도구 제외, 읽기 전용 연결의 DB 쓰기·운영 연결의 쓰기 도구 차단, 파일 쓰기 도구는 읽기 전용 연결에서도 보냄 | 앱 |
| | `AIAgentChatHistoryTest` (model.ai.tools.test) | 예산 안이면 전부, 오류·빈 답 제외, 결과 없는 호출은 취소로, 오래된 결과 줄이기 → 오래된 차례 빼기 → 남은 차례의 오래된 결과 줄이기(최신 4개 유지), 호출·결과는 한 메시지, 기록은 프롬프트로 시작, 저장한 대화는 그대로 | 단독 |
| | `PlanToolsOracleHintTest` (model.ai.tools.test) | 힌트 위치(첫 키워드 뒤, 앞 주석 건너뜀, 주석 안의 키워드 무시, WITH의 첫 SELECT), 사용자 힌트와 합침, 다른 문 거부, 앞 주석 건너뛰기 규칙(중첩 없음, 닫히지 않은 주석), ReDoS 입력·10만 자 주석·10만 자 공백·긴 WITH가 스택 넘침 없이 10초 안에 끝남 | 단독 |
| | `AgentJsonTimestampTzTest` (model.ai.tools.test) | 시간대가 있는 시각(표준, Oracle -101·-102)은 드라이버 글자로 읽음 | 단독 |
| Query Library | `QueryLibraryArchiveTest` (ui.swing.test) | 항목 경로 검사(zip-slip, 숨김·다른 파일 건너뜀), 기존 ID 찾기, 실제로 읽은 크기의 상한, 항목 수 상한, 덮어쓰기·사본·건너뛰기·묶음 안 중복은 사본, 읽기 전용 루트 덮어쓰기 오류 | 단독 |
| | `QueryLibraryGitTest` (ui.swing.test) | `status --porcelain=v2 -z` 해석(경로의 공백, 이름 바꾸기, 충돌, 분리된 HEAD), 작업 사본 찾기, 충돌 표시가 남은 파일, 실제 `git`으로 셸 특수 문자가 든 커밋 메시지·파일 이름이 셸로 해석되지 않음 | 단독 |

## 화면 확인 목록

자동으로 확인하기 어려운 것은 아래 순서로 직접 확인한다. 실행할 앱 사본은 `CHROMITE_DATA=<시험 폴더>\data`와 `-data <작업 공간 사본>`으로
띄운다(실제 사용자 폴더에 시험 키와 비밀번호를 남기지 않기 위해서). 결과는 날짜와 함께 각 항목 끝에 적는다.

### 실행 확인의 기본 단추 (#130)

| 단계 | 기대 결과 |
|---|---|
| 테이블 디자이너에서 열을 바꾸고 Save → 스크립트 미리 보기에서 바로 Enter | 실행되지 않음(Cancel이 기본 단추이고 처음 포커스) |
| Object Explorer에서 테이블 Delete → 확인 창에서 Enter | 삭제되지 않음 |
| Activity Monitor에서 세션 Kill → Enter | 세션이 끝나지 않음 |
| SQL Server Agent 작업·작업 스케줄러 미리 보기에서 Enter | 실행되지 않음 |
| Copy Tables·Import의 Truncate/Re-create 확인, 파일 덮어쓰기 질문에서 Enter | 아무것도 바뀌지 않음 |
| 백업·복원 대화상자, 전송 마법사 마지막 단계에서 Enter | 시작되지 않음(기본 단추 없음) |
| 연결 가져오기, 드라이버 묶음 설치 미리 보기에서 Enter | 가져오거나 설치하지 않음 |
| 결과 표를 고치고 창을 닫을 때 저장 질문에서 Enter | 저장하지 않음 |

### 열린 트랜잭션 표시와 유휴 알림 (#97)

| 단계 | 기대 결과 |
|---|---|
| 수동 커밋 연결에서 `UPDATE`를 실행 | 상태 줄에 열린 트랜잭션(경과 시간, 문장 수)이 보이고 눌러서 커밋·롤백할 수 있음 |
| 연결 설정의 유휴 트랜잭션 기간을 1분으로 두고 기다림 | 막지 않는 알림(Commit / Roll back / Keep open), 저절로 아무것도 하지 않음 |
| Keep open | 한 기간 동안 다시 알리지 않음 |
| 연결에서 "자동 롤백"을 켜고 기다림 | 롤백 뒤 닫을 때까지 남는 알림. 커밋은 절대 하지 않음 |
| 긴 문장이 실행 중일 때 기간이 지남 | 알리지도 롤백하지도 않음 |

### 연결 끊기·다시 연결·삭제·종료 전 질문 (#97, #124)

| 단계 | 기대 결과 |
|---|---|
| 열린 트랜잭션이 있는 연결을 Disconnect / Reconnect / Delete, 연결 편집 뒤 다시 연결 | Commit / Roll back / Cancel 질문, Cancel이면 아무것도 끊지 않음 |
| 앱 종료 | 같은 질문, 세션마다 창 이름을 보여 줌 |
| 쿼리 창 닫기, 창의 연결·DB 바꾸기 | 그 창 세션의 열린 트랜잭션만 물음 |

### 트랜잭션 명령, 로그, 열린 트랜잭션 목록 (#116)

| 단계 | 기대 결과 |
|---|---|
| Query > Commit / Roll Back, 도구 막대, Ctrl+Alt+Shift+K / R | 열린 수동 커밋 트랜잭션이 있을 때만 켜지고 그 창의 트랜잭션만 끝냄 |
| Query > Transaction Log | 상태 줄의 문장 수와 같은 사용자 문장 목록(메타데이터 읽기 없음) |
| Tools > Open Transactions… | 모든 연결의 열린 트랜잭션, 고른 것만 커밋·롤백 |
| `@export` 다음 문장 실행 | Export Data 마법사가 명령의 형식·옵션으로 열림 |

### 쿼리 창마다 세션, 끊긴 세션 (#124, #125)

| 단계 | 기대 결과 |
|---|---|
| 같은 연결의 두 창에서 각각 `UPDATE` 뒤 한 창에서 Commit | 다른 창의 트랜잭션은 그대로 |
| 한 창의 세션을 서버에서 끊고(`pg_terminate_backend`) 그 창에서 실행 | 그 창 세션만 다시 열고 잃은 문장 수를 알림, 다른 창의 트랜잭션은 그대로 |
| 끊긴 세션의 창 닫기 | Discard / Cancel 질문(서버가 이미 롤백함) |

### 오류 복구와 열린 트랜잭션 (#120)

| 단계 | 기대 결과 |
|---|---|
| PostgreSQL 수동 커밋에서 `UPDATE` 뒤 오류 문장 실행(25P02) | 자동 롤백하지 않고 남는 알림에서 Roll back을 고르게 함 |
| 열린 트랜잭션이 있는 연결의 네트워크를 끊음 | 자동 다시 연결하지 않고 Reconnect를 고르게 함, 핑 알림은 한 번 |

### 유휴 연결 닫기 (#110)

| 단계 | 기대 결과 |
|---|---|
| "Close idle connection" 1분, 아무것도 하지 않음 | 연결이 끊기고 경고 알림 |
| 열린 트랜잭션이 있거나 문장이 실행 중 | 끊지 않음 |
| 다른 연결로 계속 작업 | 쓰지 않은 연결만 끊김(전역 활동이 아니라 연결별) |

### SQL 실행 안전 (#100, #120)

| 단계 | 기대 결과 |
|---|---|
| `DELETE FROM t`, `UPDATE t SET a = 1`, `TRUNCATE TABLE t`, `DROP DATABASE x`, `DROP USER 'a'@'%'`를 한 번에 F5 | 한 번만 묻고 줄 번호·종류·문장 목록을 보여 줌, Cancel이면 하나도 실행하지 않음 |
| SQL Server에서 `GO`로 나눈 배치 안의 `DROP TABLE` | 물음 |
| Options에서 두 확인을 끔 | 묻지 않음 |
| 연결 종류에 "Restrict script execute" | 실행 전에 거절 알림 |
| `@ai`가 만든 SQL | 실행 전에 읽기 전용으로 보여 줌 |

### 결과 표 저장 스크립트 (#98)

| 단계 | 기대 결과 |
|---|---|
| Test/Production 연결 또는 옵션을 켜고 결과 표를 고쳐 저장 | 저장 스크립트 미리 보기, Cancel이면 아무것도 실행하지 않고 편집은 남음 |

### 셸 명령 승인 (#99)

| 단계 | 기대 결과 |
|---|---|
| 연결 이벤트 명령이 있는 연결에 연결 | 명령·폴더·연결·이벤트를 보이는 질문(Run Once / Always Allow / Cancel, Cancel이 기본) |
| Always Allow 뒤 명령 글자를 바꿈 | 다시 물음 |

### 연결 내보내기·가져오기 (#108)

| 단계 | 기대 결과 |
|---|---|
| "Include passwords"로 내보내기(12자 미만 암호) | 거절 |
| 다른 PC 또는 다른 작업 공간에서 가져오기, 틀린 암호 | 아무것도 가져오지 않음 |
| 미리 보기의 충돌 연결 Skip / Replace / Copy | 고른 대로, 연결 중인 연결은 바꾸지 않고 결과에 적음 |

### 저장한 비밀번호와 AI 키 (#103, #111)

| 단계 | 기대 결과 |
|---|---|
| 예전 `credentials-config.json`이 있는 작업 공간으로 시작 | 비밀번호가 OS 보안 저장소로 옮겨지고 파일과 백업이 지워짐, 연결 시 묻지 않음 |
| 보안 저장소 파일을 다른 PC의 것으로 바꿔 시작 | 저장 거부 알림, 비밀번호를 덮어쓰지 않음 |
| AI 제공자 키 저장 | 설정 파일에 키가 평문으로 없음 |

### 드라이버 오프라인 묶음 (#109)

| 단계 | 기대 결과 |
|---|---|
| 인터넷 PC에서 Create Offline Bundle… | `.chromite-drivers` 파일 |
| 폐쇄망 PC(네트워크 끊음)에서 Install Offline Bundle… | 미리 보기(새·같음·다름), 설치 뒤 드라이버 관리자에 "다운로드됨", 연결 성공 |
| 묶음의 바이트 하나를 바꿈 | 미리 보기 전에 거절, 아무것도 설치하지 않음 |

### Query Library 공유 (#112)

| 단계 | 기대 결과 |
|---|---|
| 공유 폴더 루트의 파일을 다른 PC에서 바꿈 | 30초 안에 트리 갱신, 펼침·선택 유지 |
| Git 작업 사본 루트 | 분기·ahead/behind·충돌 수, Pull, Commit & Push, Git이 없으면 명령이 꺼지고 이유 표시 |
| 같은 줄을 고친 충돌 | Chromite가 합치지 않고 충돌 파일 표시, 충돌 표시가 남은 파일은 올리지 않음 |
| 폴더 내보내기 → 다른 폴더로 가져오기, 같은 ID | 덮어쓰기·사본·건너뛰기(모두 적용) 질문 |

### 프로시저 디버거 (#114, #121)

| 단계 | 기대 결과 |
|---|---|
| PostgreSQL PL/pgSQL 함수 Debug…, 중단점, F10/F11/F5, 변수 값 바꾸기 | 현재 줄 표시, 변수·호출 스택·메시지·결과 |
| 디버그 중 탭 닫기·앱 종료 | 디버그 세션의 트랜잭션을 물음 |
| Oracle 프로시저 Debug… | 디버그 컴파일 질문, 거절하면 열지 않음, 권한이 없으면 필요한 GRANT 문 안내 |
| Oracle Step Out, 패키지 프로시저 | 동작, 줄 번호가 패키지 본문 기준 |

### Chromite Agent (#115, #132)

| 단계 | 기대 결과 |
|---|---|
| PostgreSQL 연결 대화에서 SQL Server 전용 도구를 요구 | 모델이 그 도구는 이 연결에서 쓸 수 없다고 답함(보내지 않은 도구가 시스템 프롬프트에 있음) |
| 운영 연결에서 쓰기 도구 요청 | 막힘 |
| 긴 대화(도구 결과가 큼) | 요청이 실패하지 않고 오래된 결과가 줄어 보내짐, 화면의 대화는 그대로 |

### 개체 탐색기와 자동완성 (#119, #122)

| 단계 | 기대 결과 |
|---|---|
| 여러 객체 선택 Delete, Rename, Copy name, Generate SQL | 스크립트 미리 보기, 열린 트랜잭션이 있으면 질문 |
| `EXEC dbo.` / `CALL app.` 뒤 자동완성 | 오류 없이 부를 수 있는 루틴 제안(SQL Server는 스칼라 함수 포함), 이름만 넣음 |

### 관리 도구 (#128, #129)

| 단계 | 기대 결과 |
|---|---|
| 수동 커밋 PostgreSQL 연결의 Activity Monitor > Locks에서 다른 세션이 잠금을 기다림 | 대기 세션이 보임(모니터 연결은 자동 커밋) |
| Oracle 보안·NLS 설정, Altibase 잠금, 객체 유지 관리 명령 | 스크립트 미리 보기 뒤 실행 |

### 작은 화면 수정 (#131)

| 단계 | 기대 결과 |
|---|---|
| 쿼리 창을 좁힘 | 상태 줄의 서버 부분이 먼저 줄고 상태 글자는 잘리지 않음 |
| 테마 바꾼 뒤 탭 넘침 단추 | 도구 설명 유지 |
| 연결 중 Stop | 오류 대화상자 없음 |

### macOS와 Windows 시작 (#104)

| 단계 | 기대 결과 |
|---|---|
| macOS Cmd+Q, 열린 트랜잭션 있음 | 메인 창 닫기와 같은 질문, Cancel이면 앱이 남음(확인하지 못함: macOS 장비 필요) |
| macOS 앱 메뉴 About / Preferences | 기존 대화상자 |
| Windows에서 사내 CA 인증서를 쓰는 서버에 SSL 연결 | 별도 신뢰 저장소 없이 연결(`WINDOWS-ROOT`) |

### macOS 단축키와 화면 메뉴 막대 (앱 변경 #24, #27)

[shortcuts.md](shortcuts.md), [mac-ui.md](mac-ui.md). macOS 장비가 필요하다(`macos-smoke.yml`의 `menus`가 메뉴 막대 항목 이름만 본다).

| 단계 | 기대 결과 |
|---|---|
| 앱을 켜고 화면 맨 위 메뉴 막대를 봄 | Chromite(앱 메뉴), File, Edit, View, Query, Tools, Window. 창 안에는 메뉴 막대가 없음 |
| File, Tools 메뉴, Help 메뉴 | Exit·Options가 없고 Help 메뉴가 없음. 앱 메뉴에 Chromite 정보, 옵션(⌘,), Chromite 끝내기(⌘Q) |
| View > Theme으로 테마를 바꿈 | 메뉴가 화면 메뉴 막대에 그대로 있음 |
| File > Recent Files, Window 메뉴 | 열 때마다 항목이 채워짐(동적 메뉴), Query 메뉴의 체크 항목(Include Actual Plan)이 상태를 보임 |
| ⌘N, ⌘S, ⌘F, ⌥⌘F, ⇧⌘M, ⌘Enter, ⇧⌘Z | 새 쿼리, 저장, 찾기, 바꾸기, 실제 계획 토글, 현재 문장 실행, 다시 실행. 한 번씩만 동작(메뉴와 편집기가 두 번 하지 않음) |
| Control+Enter, Control+H | 아무 명령도 실행하지 않음(예전 키) |
| 연결한 쿼리 창 편집기에서 ⌥X, ⌥P, 그다음 ⌥O, ⌥E 뒤 e | ⌥X는 실행, ⌥P는 커서부터 실행하고 SQL에 "≈"·"π"가 들어가지 않음. ⌥O는 "ø", ⌥E 뒤 e는 "é"가 입력됨([shortcuts.md](shortcuts.md) "Option 단축키의 글자") |
| 편집기에서 ⌃Space | 자동 완성 목록(입력 소스 단축키를 바꾼 경우). 입력 소스가 바뀌면 [shortcuts.md](shortcuts.md)의 안내대로 |
| 결과 표에서 ⌘+클릭, 열 머리글 ⌘+클릭 | 행·열 선택에 더함. Control+클릭은 팝업 메뉴 |
| 도구 막대 단추에 마우스를 올림 | 도구 설명이 ⌘ 기호로 키를 보임(예: "Save all … (⇧⌘S)") |
| 새 버전이 있을 때 | 업데이트 단추가 상태 표시줄 오른쪽 끝에 보이고 누르면 메뉴·업데이트 창 |
| Windows에서 같은 단계 | 메뉴가 창 안(제목 표시줄), Exit·Options·About 있음, 단축키·도구 설명 Ctrl, 업데이트 단추는 제목 표시줄 오른쪽 |

### 설치와 브랜딩 (#107, #123, #127)

| 단계 | 기대 결과 |
|---|---|
| 설치 프로그램으로 설치, About | 버전 0.1.1, "DBeaver" 이름 없음 |
| 드라이버 목록 | SQLite 내장, MySQL Connector/J는 내려받기(라이선스 표시) |

### 다운로드 사이트 (#96, #101, #126, #133)

| 단계 | 기대 결과 |
|---|---|
| Windows·macOS 브라우저로 `/ko/`, `/en/` | 자기 플랫폼 카드가 먼저, 다른 것은 접힘 |
| 스크롤 | 장면마다 스크린숏 확대·기울기·문구 교체가 스크롤에 따라 바뀜 |
| 스크립트 끔·움직임 줄이기 | 모든 장면과 두 카드가 그대로 보임 |
| 히어로의 Download | `#download`로 이동 |

## 실행 결과

명령은 위 "실행"의 명령에 `-Dmaven.test.failure.ignore=true`를 더한 것이다. 모든 테스트 플러그인이 `com.seizia.chromite.test.platform`에 의존하므로,
이 옵션이 없으면 그 플러그인의 실패 하나로 나머지 테스트 플러그인이 모두 SKIPPED가 된다(첫 실행에서 확인).

### 2026-11-08 둘째 실행 (결함 수정 뒤, devel cac9ad967a 병합)

결과 `BUILD SUCCESS`, 실패 0, 로그 `D:\Tools\_build-logs\regression-tests-verify.log`.

| 테스트 플러그인 | Tests run | Failures | Errors | Skipped |
|---|---|---|---|---|
| com.seizia.chromite.model.test (새) | 24 | 0 | 0 | 0 |
| com.seizia.chromite.registry.test (새) | 21 | 0 | 0 | 1 |
| com.seizia.chromite.model.ai.tools.test (새) | 15 | 0 | 0 | 0 |
| com.seizia.chromite.ui.swing.test (새) | 11 | 0 | 0 | 0 |
| com.seizia.chromite.model.sql.test (새 5개 포함) | 188 | 0 | 0 | 0 |
| com.seizia.chromite.test.platform (새 2개 포함) | 962 | 0 | 0 | 9 |
| com.seizia.chromite.data.transfer.test (새 2개 포함) | 102 | 0 | 0 | 0 |
| com.seizia.chromite.ext.postgresql.test (새 2개 포함) | 168 | 0 | 0 | 0 |
| com.seizia.chromite.ext.oracle.test (새 3개 포함) | 68 | 0 | 0 | 0 |
| com.seizia.chromite.model.ai.test | 199 | 0 | 0 | 0 |
| 그 밖의 테스트 플러그인 19개 | 779 | 0 | 0 | 15 |
| 합계 | 2537 | 0 | 0 | 25 |

건너뜀 25개 중 이번에 생긴 것은 두 개다: `DriverOfflineBundleTest`와 `DriverLibraryPlatformTest`의 심볼릭 링크 시험(Windows 일반 사용자는 링크를 만들 수
없음, Linux CI에서는 돈다). 나머지는 원래 조건부로 건너뛰는 시험이다(lsm 12, net 3, test.platform 8).

첫 실행에서 찾은 실패와 고친 것:

| 실패 | 원인 | 고친 것 |
|---|---|---|
| `PlanToolsOracleHintTest.longLeadingCommentDoesNotOverflowTheStack` (새 시험) | 제품 결함. Oracle 실제 계획 도구의 첫 키워드 정규식에서 블록 주석 본문 `(?:[^*]|\*+[^*/])*`이 글자마다 그룹을 반복해 `java.util.regex`가 재귀했다. 약 2,000자 넘는 머리 주석에서 `StackOverflowError` | `PlanTools.skipLeadingComments`(반복문)로 앞 공백·주석을 건너뛰고 정규식은 그 자리의 키워드만 본다. 옛 정규식과 무작위 입력 30만 개를 비교해 결과가 같았다. 시험에 10만 자 주석·10만 자 공백·ReDoS 입력 3만 번과 건너뛰기 규칙을 더함 |
| `SQLScriptParserTest` 10개, `SQLScriptParserGenericsTest` 1개 (test.platform) | #118의 `OracleSQLDialect.readMaxVarchar2Length`가 `SQLException`만 잡았다. 시험의 mock 세션은 문장을 주지 않아 `NullPointerException`이 방언 설정 밖으로 나갔다 | 그 조회의 모든 실패를 4000으로 두고 디버그 로그만 남김(시험의 mock은 그대로). `OracleMaxStringSizeTest`에 문장·결과 없음, SQL이 아닌 예외를 더함 |
| `ConnectJobTest.canceledConnectionExceptionIsReportedAsError` | #131이 취소로 생긴 연결 실패를 의도적으로 취소 상태로 바꿨는데 시험이 옛 동작을 기대했다 | 시험을 새 동작으로 고치고 `canceledConnectionExceptionIsReportedAsCancel`로 이름을 바꿈 |
| `AISettingsManagerTest` 3개 (model.ai.test) | #103 뒤로 프로필을 만들 때 AI 비밀을 OS 보안 저장소에서 읽는데 헤드리스 시험 앱에는 그 저장소가 없다 | 시험에 메모리 보안 저장소를 줌(제품은 평문으로 대신 저장하지 않음) |
| `DriverLibraryPlatformTest.rejectsSymlinkedCacheDirectoriesAndFiles` | Windows 일반 사용자의 심볼릭 링크 권한(환경) | OS가 링크를 거부하면 건너뜀 |

### 2026-11-08 첫 실행 (devel 2fe1a0bb4e)

새 테스트 84개 중 82개 통과, 1개 실패(위 `PlanTools` 결함), 1개 건너뜀. 기존 테스트 16개 실패(위 표의 나머지). 로그
`D:\Tools\_build-logs\regression-tests-verify-3.log`.

Docker 시험 DB가 필요한 테스트는 없다.
## 변경 이력

- 2026-11-08 최초 작성: 새 테스트 플러그인 네 개(model, registry, model.ai.tools, ui.swing)와 기존 테스트 플러그인(model.sql, test.platform,
  data.transfer, ext.postgresql, ext.oracle)에 PR #95–#133의 모델 기능 회귀 테스트, 화면 확인 목록. 첫 실행 결과(새 테스트 84개 중 82개 통과,
  Oracle 계획 힌트 정규식의 StackOverflowError 결함 발견, 기존 테스트 실패 16개의 원인)
- 2026-11-08 찾은 실패를 고침: `PlanTools`의 앞 주석 건너뛰기(반복문), Oracle `MAX_STRING_SIZE` 조회의 모든 실패를 4000으로, `ConnectJobTest`를 #131 동작으로,
  `AISettingsManagerTest`에 메모리 보안 저장소, 심볼릭 링크 시험 건너뛰기. 둘째 실행 결과(실패 0)
- 2026-11-09 `UiKeysTest`와 "macOS 단축키와 화면 메뉴 막대" 확인 목록(앱 변경 #24, #27, `chromite/mac-ui`)
- 2026-11-15 "macOS 단축키와 화면 메뉴 막대"에 Option 단축키의 글자 줄(앱 변경 #122, `chromite/execute-alt-x`)
