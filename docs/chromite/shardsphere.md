# ShardSphere 기능 플러그인

## 목적

ShardSphere(CISPHERE Inc.의 Altibase 계열 DBMS)에만 있는 기능(GEMINI 복제, 샤딩)을 Chromite에 넣을 자리를 만든다. 이번 변경(앱 변경 #139 작업 2·3)은
뼈대다: Altibase 플러그인의 중립적인 확장 지점, 그 첫 사용자인 새 번들 `com.seizia.chromite.ext.shardsphere`와 그 feature, 서버 카탈로그로 하는 감지,
감지 결과를 보이는 읽기 전용 속성 하나. 기능(GEMINI 트리, 샤딩 탐색기, `NODE[...]` 편집기 지원)은 다음 작업이다.

## 결정 (사용자)

- 2026-11-16 "통합 제품에 독립 플러그인으로" 넣는 방향(분리판은 거래에 필요할 때만).
- 2026-11-17 "권장안대로": 구조 (나) — 기존 `altibase` 공급자에 기능만 얹는 새 번들, 자기 feature. 드라이버 `altibase:shardsphere`, jar, 데이터 원본 유형,
  아이콘은 지금 자리(`ext.altibase`, 내장 드라이버 feature)에 그대로 둔다. 저장된 연결이 바뀌지 않는다(옮기거나 이름을 바꾸지 않는다).
  ShardSphere는 **서버 카탈로그로** 알아본다(드라이버나 제품 이름으로 가르지 않는다). 두 서버에 다 있는 기능은 나중에 공용 Altibase 코드로 가고,
  이 플러그인은 ShardSphere에만 있는 것을 갖는다. 조회 전용 서버에서 `NODE[...]` 문장을 실행하지 않는다.

## 구조

| 위치 | 내용 |
|---|---|
| `plugins/com.seizia.chromite.ext.altibase` (중립, ShardSphere를 모름) | 확장 지점 `com.seizia.chromite.altibase.serverExtension`과 스키마, `extension/AltibaseServerExtension`·`AltibaseServerExtensionState`·`AltibaseServerExtensionRegistry`, `AltibaseDataSource`의 감지·어댑터·트리 자리, System Info 안 트리 항목 `serverExtension`. 자세한 것은 [altibase.md](altibase.md) "서버 확장" |
| `plugins/com.seizia.chromite.ext.shardsphere` (모델만, UI 번들에 의존하지 않음) | `plugin.xml`: 확장 하나(`<serverExtension id="shardsphere">`). `ShardSphereServerExtension`: 감지. `model/ShardSphereServer`: 연결마다의 결과이자 System Info 아래 노드, 읽기 전용 속성 세 개, 방언 기여. `ShardSphereStatementPrefix`: 문장 접두어 인식. `OSGI-INF/l10n/bundle*.properties`: 속성 이름(영어, 한국어) |
| `features/com.seizia.chromite.shardsphere.feature` | 이 번들 하나를 담는 feature. 제품에서 뺄 단위 |
| `features/com.seizia.chromite.swing.feature/feature.xml` | `<includes id="com.seizia.chromite.shardsphere.feature">` |
| `plugins/pom.xml`(desktop 프로필), `features/pom.xml`(desktop 프로필) | 모듈 |

- **의존:** `com.seizia.chromite.model`, `model.jdbc`, `ext.generic`, `ext.altibase`. 이 번들에 의존하는 번들은 없다(검색). `model.admin`은 아직 쓰지 않아
  의존하지 않는다. 모듈은 desktop 프로필에 두었다: 다음 작업(관리 보고서, 복제 탭)이 `model.admin`을 쓰면 그 번들도 desktop 프로필에만 있기 때문이다.
- **시험 플러그인:** 만들지 않았다. 빌드가 요구하지 않고(시험 플러그인이 없는 번들이 많다, 예: `ext.gaussdb`), 회귀 테스트는 요청이 있을 때만 쓴다(CLAUDE.md 7번).
  시험을 쓰게 되면 `test/com.seizia.chromite.ext.shardsphere.test`와 `features/com.seizia.chromite.test.feature`의 항목을 함께 만든다.
- **NOTICE:** 바꾸지 않았다(다른 출처의 것을 넣지 않음).

## 확장 지점 (Altibase 서버 확장)

| 타입 | 메서드 | 왜 있나 |
|---|---|---|
| `AltibaseServerExtension` (기여 클래스, 상태 없음, 모든 연결이 공유) | `detect(JDBCSession, AltibaseDataSource)` → 상태 또는 null | 확장이 맞는 서버인지는 서버 카탈로그만 안다. 연결마다 한 번, 연결이 쓸 수 있게 된 뒤 |
| `AltibaseServerExtensionState` (연결마다) | `getAdapter(Class)` | 다음 작업(GEMINI 복제 탭, 관리 보고서)이 데이터 원본의 어댑터로 들어온다. 계열 공통 구현보다 먼저 묻는다 |
| | `getTreeObjects()` | Object Explorer에 확장의 객체를 내는 중립 자리. 이번에는 감지 결과 노드 하나 |

- **방언 기여(2026-11-17 작업 6a):** `getSyntaxKeywords()`, `getFunctions()`, `getStatementPrefixLength(text)`. 실행 전 확인 보강과 함께 넣었다(아래 "문장 접두어").
- **나중으로 둔 것(쓰는 곳이 아직 없음):** 복제 카탈로그 전략(S2)은 GEMINI 트리와 함께, 관리 보고서 지점(S4)·객체 명령(S6/S7)은 그 기능과 함께 정한다. 트리 자리도 지금은 System Info 한 곳뿐이다.

## 감지

- **세 가지를 따로:** GEMINI 카탈로그(`SYSTEM_.SYS_GEMINIS_`가 `SYSTEM_.SYS_TABLES_`에 있음), 샤딩 패키지(`SYS`의 `DBMS_SHARD`가 `SYSTEM_.SYS_PACKAGES_`에 있음),
  샤딩 메타 노드(`SYSTEM_.SYS_USERS_`에 `SYS_SHARD`가 있고 `V$PROPERTY`의 `SHARD_META_ENABLE` = 1). 셋 중 하나라도 있으면 확장이 맞는 서버다.
- **문장 하나:** 네 개의 스칼라 부분 질의를 `FROM DUAL` 한 SELECT로 읽는다(연결마다 왕복 한 번). 없는 표를 준비해 보는 방식(오류로 판단)은 Altibase에서 오류와
  기록을 남기므로 쓰지 않았다. 읽지 못하면(오류) "없음"이고 디버그 기록에 오류 코드만 남긴다.
- **세 서버에서 읽은 값(2026-11-17, 조회 전용, 앱 밖 JDBC로 먼저 확인한 뒤 앱 안에서 같은 값):**

| 서버 | GEMINI 카탈로그 | `DBMS_SHARD` (명세·본문 행) | `SYS_SHARD` 사용자 | `SHARD_META_ENABLE` | 결과 |
|---|---|---|---|---|---|
| ShardSphere 5.1.9 (일반 서버) | 있음 | 2 (유형 6, 7) | 없음 | 0 | GEMINI 예, 패키지 예, 메타 노드 아니오 |
| ShardSphere 5.1.9-beta9 (샤딩 메타 노드) | 있음 | 2 | 있음 | 1 | 예, 예, 예 |
| Altibase 7.3.0.0.7 (시험 서버) | 없음 | 0 | 없음 | 속성 없음 | 확장 없음(노드 없음) |

- **비용:** 감지 문장의 실행 시간(쿼리 관리자 기록): ShardSphere 10–13 ms, Altibase 6–61 ms(SSH 터널 너머, 왕복 하나). 연결 시간(로그인 대화상자 확인 뒤부터
  연결 끝까지, 세 번의 중앙값, 같은 날 같은 PC): 기능 없는 제품 → 기능 있는 제품 순으로 Altibase 1574 → 1603 ms, ShardSphere 205 → 211 ms, 메타 노드 201 → 220 ms.
  차이는 측정 흔들림 안팎이다(같은 제품에서 한 번의 연결이 1171 ms였던 적도 있다).
- **새로 고침:** Altibase 데이터 원본의 새로 고침은 전부터 `initialize`를 두 번 부른다(`GenericDataSource.refreshObject`와 `AltibaseDataSource.refreshObject`가
  각각 부름). 그래서 새로 고침마다 감지 문장도 두 번 실행된다(쿼리 관리자에서 확인). 전부터 있던 구조라 이 변경에서 고치지 않았다(LOB 임계값 질의도 두 번이다).

## 보이는 결과

새 화면 없이 기존 Object Explorer와 속성 보기에 보인다: 연결 ▸ 시스템 정보 아래 `ShardSphere` 노드(유형 "서버 확장"), 그 속성 이름·GEMINI 카탈로그·
샤딩 패키지·샤딩 메타 노드. 값은 앱의 다른 불리언 속성처럼 `true`/`false`로 보인다. Altibase 서버에는 노드가 없다(시스템 정보의 자식이 전과 같은 두 개).

- 속성 이름은 `@Property`의 규칙대로 이 번들의 `OSGI-INF/l10n/bundle.properties`·`bundle_ko.properties`에 있다(`meta.<클래스>.<속성>.name`).
  Java 코드의 화면 문구가 없어 `*Messages` 클래스는 만들지 않았다. 노드 이름 "ShardSphere"는 제품 이름이라 번역하지 않는다.
- 데이터 원본 연결의 속성(연결 노드의 속성 보기)은 레지스트리의 `DataSourceDescriptor`가 그리는 것이라 Altibase 플러그인이 바꿀 수 없다. 그래서 Altibase 데이터
  원본이 이미 서버 정보를 보이는 곳인 "시스템 정보" 폴더에 확장 지점의 트리 자리를 두었다.

## 기능을 뺀 제품 (판매판·다른 배포판)

- **빼는 줄:** `features/com.seizia.chromite.swing.feature/feature.xml`의 `<includes id="com.seizia.chromite.shardsphere.feature" version="0.0.0"/>`(와 그 위 주석)
  한 줄. 그 제품을 빌드하는 쪽이 이 번들을 아예 빌드하지 않으려면 `plugins/pom.xml`과 `features/pom.xml`의 `<module>` 두 줄(과 주석)도 뺀다.
  드라이버·jar·아이콘은 내장 드라이버 feature와 `ext.altibase`에 그대로 있으므로 ShardSphere 연결은 그대로 열린다.
- **확인(2026-11-17):** 포함 줄을 로컬에서만 주석 처리하고 빌드(커밋하지 않음): 제품에 `com.seizia.chromite.ext.shardsphere` 번들과 feature가 없다.
  앱이 시작되고 ShardSphere 두 서버와 Altibase에 연결됐다. 감지 문장은 한 번도 실행되지 않았다(쿼리 관리자 41개 문장 중 0개). 세 연결 모두 시스템 정보의 자식은
  Properties·Module Memory Usage 둘(전과 같음), 저장된 연결은 `"provider": "altibase"`, `"driver": "shardsphere"`/`"Altibase"` 그대로다.
  Altibase의 복제(이중화 객체) 폴더 0개·오류 없음, 테이블스페이스 5개, 테이블 DDL(817자), 작업 모니터의 프로세스(세션 3개)·잠금(대기 없음)이 기능 있는 제품과 같다.
  앱 기록에 확장 관련 줄이 없다(기능 있는 제품에서도 없다).

## 연결마다의 답 (같은 앱에 세 연결)

같은 앱에 SS(일반 서버), SHARD(메타 노드), ALTI(Altibase 7.3)를 함께 열고 데이터 원본 인스턴스와 그 확장 객체의 identity를 읽었다.

| 순서 | ALTI | SS | SHARD |
|---|---|---|---|
| ALTI → SS → SHARD 연결 | 객체 0 | 객체 1(부모 = 자기 데이터 원본): GEMINI true, 패키지 true, 메타 노드 false | 객체 1: true, true, true |
| 세 연결 새로 고침 | 0, 같은 데이터 원본 | 새 객체, 같은 값 | 새 객체, 같은 값 |
| 다시 연결(ALTI, SHARD, SS) | 새 데이터 원본, 0 | 새 데이터 원본·객체, 같은 값 | 새 데이터 원본·객체, 같은 값 |
| 모두 끊고 SHARD → ALTI → SS 연결 | 0 | false 메타 노드 | true 메타 노드 |

연결마다의 상태는 데이터 원본 인스턴스에만 있고, 메타 모델·어댑터 팩토리·공급자 트리 정의·정적 낱말 목록·방언 메타데이터에는 연결마다의 것이 없다(코드).

## 문장 접두어 (2026-11-17, 앱 변경 #139 작업 6a)

### 구멍

샤딩 서버는 문장 앞에 실행할 노드를 고르는 접두어를 받는다: `NODE[meta]`, `NODE[data]`, `NODE[data('n1','n2')]`, `NODE[all]`, `SHARD`. 전에는
`SQLQuery.parseQuery`가 접두어에서 실패해 종류가 UNKNOWN이고 `isModifying()`과 위험 판단이 모두 거짓이라, `NODE[all] DELETE FROM t`, `NODE[data] UPDATE t SET …`,
`NODE[all] DROP TABLE t`, `NODE[all] TRUNCATE TABLE t`(INSERT도)가 실행 전 확인 없이 서버로 갔다(접두어 없는 같은 문장은 멈춤). 한 문장이 클러스터의 모든 노드를 바꿀 수 있다.

### 고친 방법

- **자리(S5, 모델, 중립):** `SQLDialect.getStatementPrefixLength(text)`(`SQLDialect.java:540`, 기본 0). 길이만 돌려준다: 본문은 늘 `text.substring(length)`이고
  (접두어는 문장 앞에만 있음), 길이에 앞의 공백·주석도 넣는다. 서버로 보내는 글은 바뀌지 않는다. 제품 이름은 모델에 없다.
- **누가 아는가:** 이 번들의 `ShardSphereStatementPrefix`(인식)와 `ShardSphereServer`(연결의 확장 상태)가 Altibase 서버 확장의 방언 기여로 낸다
  (`AltibaseServerExtensionState.getStatementPrefixLength`·`getSyntaxKeywords`·`getFunctions` → `AltibaseDataSource.java:165` →
  `AltibaseSQLDialect.applyServerExtensions`(`:499`)·`getStatementPrefixLength`(`:524`)). **샤딩 패키지(`DBMS_SHARD`)를 감지한 연결에서만**
  (`ShardSphereServer.java:138-151`), 그 데이터 원본의 방언 객체에만 들어간다. Altibase 연결과 기능을 뺀 제품에서는 0이다(전과 같음).
- **문법 낱말:** `NODE`, `META`, `UNSHARD`, `CLONE`, `SOLO`, `AUTOHASH`, `SUBSHARD`, `HASH`(키워드, 이름의 따옴표 이유는 아님), 함수 `SHARD_NODE_NAME`, `SHARD_KEY`.
  메타 노드의 `V$RESERVED_WORDS`는 이 가운데 하나도 보고하지 않는다(2026-11-17 조회: 후보 중 예약어는 `ALL`, `KEY`, `OTHERS`, `SHARD`). `SHARD`는 서버 예약어라 전처럼
  예약어다. `DATA`, `LIST`, `RANGE`는 흔한 이름이라 넣지 않았다(`data`는 접두어 대괄호 안에서만 키워드로 칠함).

### 접두어 규칙 (`ShardSphereStatementPrefix.getLength`)

서버가 받는 형태(조사 결과 "Accepted syntax")와 같다. 대소문자 무시.

1. 앞의 공백, `--` 주석, `/* */` 주석을 건너뛴다.
2. 따로 선 낱말 `NODE`(뒤가 이름 글자가 아님), 공백, `[`, 공백·줄바꿈, `meta` | `all` | `data` | `data(이름, …)` | `data()`, 공백, `]`. 이름은 작은따옴표(`''` 이스케이프),
   큰따옴표, 맨 이름(글자·숫자·`_$#`), 쉼표로 나눔. `]` 뒤에는 무엇이 와도 된다(공백, 주석, 바로 문장).
3. 또는 따로 선 낱말 `SHARD` 뒤에 공백이나 주석, 그리고 문장이 있음.
4. 접두어가 둘 이어지면(`NODE[data] SHARD …`, 서버는 거절) 이어서 뗀다.

| 경우 | 결과 |
|---|---|
| `NODE[all]` + DML/DDL | 접두어(서버가 정함; `NODE[all] SELECT`는 서버가 거절하지만 분류는 SELECT) |
| `NODE[]`, `NODE meta`, `NODE[foo]`, `NODE[meta()]`, `NODE[all('n')]`, `NODE[1]`, `NODE[data`(닫히지 않음) | 접두어 아님 → 전처럼 UNKNOWN(서버도 거절) |
| `SELECT node FROM t`, `CREATE TABLE node …`, `UPDATE node …`, `SELECT node[1] FROM t` | 문장 맨 앞이 아니거나 형태가 달라 접두어 아님(종류 그대로) |
| `CREATE SHARD …`, `ALTER SHARD …`, `DROP SHARD …`, `ALTER TABLE … SHARD BY …`, `ALTER TABLE … UNSHARD`, `DROP PROCEDURE p UNSHARD` | `SHARD`가 맨 앞이 아님 → 문장의 일부. `DROP SHARD`·`DROP PROCEDURE … UNSHARD`는 DROP 확인을 받음 |
| `SELECT … FROM NODE[data](…)`, `SELECT … FROM SHARD (…)`, `(NODE[data] SELECT …)`, `SHARD(SELECT …)`, `SHARDX …` | 접두어 아님(전과 같음) |
| `-- NODE[all]` 다음 줄 `DELETE …`, 문자열 안의 `NODE[all]` | 주석·문자열은 접두어가 아님(본문 `DELETE`로 분류, 전과 같음) |

**안전 쪽:** 접두어는 문장을 담지 않으므로 떼어도 위험한 본문이 숨지 않는다. 떼지 못하면 전과 같은 UNKNOWN이다. 판단이 갈리는 곳(접두어 둘)은 떼는 쪽을 골랐다.

### 접두어를 보게 바꾼 판단

| 판단 | 위치 | 전 → 후 (ShardSphere 샤딩 연결) |
|---|---|---|
| 종류·변경 여부·위험(WHERE 없는 UPDATE/DELETE, TRUNCATE, DROP) | `SQLQuery.java:191`(본문 파싱), `:359`, `:852-916` | UNKNOWN·모두 거짓 → 접두어 없는 같은 문장과 같음 |
| 실행 전 확인(쿼리 창, `@include` 파일) | `SqlExecutionSafety.java:156-194`(위 판단을 씀), `SqlEditorPanel.java:1962,2614` | 대화상자 없음 → 대화상자(문장은 접두어까지 그대로) |
| LSM의 DROP 판단, DDL 뒤 Object Explorer 새로 고침 | `SQLObjectOperationRecognizer.java:95,115,154` | DROP USER 등도 확인 |
| 트랜잭션을 바꾸는 문장(쿼리 관리자의 트랜잭션, 열린 트랜잭션 표시, 스마트 커밋) | `AbstractSQLDialect.java:745`(`QMMStatementExecuteInfo.java:78`, `DBExecUtils.java:541`이 씀) | 접두어 DML이 "바꾸지 않음" → "바꿈" |
| 운영 연결의 실행 확인(접두어 SELECT를 묻던 것) | `SQLSemanticProcessor.java:192`(`SQLQueryExecuteJob.java:569`) | 접두어 SELECT 물음 → 묻지 않음, 접두어 DML 물음(같음) |
| 문장 나누기의 블록 규칙(`NODE[meta] CREATE PROCEDURE … AS v INTEGER; BEGIN … END;`) | `SQLScriptParser.java:189-199,322` | 선언의 `;`에서 3개로 나뉨 → 1개(접두어 없는 것과 같음), TYPESET도 |
| Agent 분류기 | `AgentSqlClassifier.java:249,280,308,337` | [ai-agent.md](ai-agent.md) "문장 접두어" |
| 다시 읽기(행 수, 필터, 페이지), 다시 실행해 내보내기 | `ResultSetSource.java:101`, `SQLQueryDataContainer.java:262`, `DataExportWizard.java:532` | 전처럼 막음(종류는 이제 SELECT지만 다시 쓰기가 접두어를 잃음; 6b) |
| 결과 편집(단일 원본 테이블), 자동 행 제한(LIMIT), 끊긴 뒤 자동 재실행 | `SQLQuery.getStatement()`가 없음(`:359`) → `DBExecUtils`, `DBUtils.java:1631`, `SQLQueryRecovery.java:99` | 전과 같음(편집 안 됨, LIMIT 안 붙임, 재실행 안 함) |
| 커밋·롤백 문장 뒤 세션 커밋·롤백 | `SQLQueryExecuteJob.java:443-446` | 접두어 COMMIT/ROLLBACK은 UNKNOWN 그대로(세션 전체를 끝내지 않음) |
| 강조 | `SqlTokenClassifier.java:219,263` | 이름 + 대괄호 이름 → 키워드·연산자·문자열 |

### 읽기 전용 연결

쿼리 창에는 읽기 전용 연결에서 변경 문을 미리 거절하는 검사가 없다(전부터, [swing-sql-exec-safety.md](swing-sql-exec-safety.md) "남은 것"): 드라이버의
`setReadOnly(true)`가 서버에서 막는 것에 맡긴다. 그래서 읽기 전용 연결에서도 접두어 DML에 위 확인 대화상자가 뜬다(접두어 없는 DML과 같음). 서버가 접두어 DML을
읽기 전용 연결에서 막는지는 미확인이다(조사: `setReadOnly`는 접두어 SELECT를 막지 않음). Chromite가 스스로 판단하는 읽기 전용 검사는 Agent 정책뿐이고, 그것은
위 분류기로 접두어 쓰기를 전처럼 막는다. 쿼리 창의 읽기 전용 거절은 새 기능(새 문구)이라 이 변경에 넣지 않았다(남은 일).

### 아직 남은 것

- 행 수·필터·페이지(접두어를 괄호 안 하위 질의로 감싸 유지, 6b), 접두어 뒤의 자동 완성(6b), 실행 계획(접두어 문장의 계획 요청은 확인하지 않음: 메타 노드에서
  `NODE` 문장을 실행하지 않는다는 결정. 이 변경은 계획 경로를 바꾸지 않았다).
- 확인 대화상자의 SQL 보기는 연결 없는 표준 낱말로 칠해 `NODE[all]`이 보통 글자로 보인다(전부터 그 보기의 동작).
- `TRUNCATE`는 접두어와 관계없이 종류가 UNKNOWN이고 `isModifying()`이 거짓이다(확인은 `isTruncate`로 뜸, 전부터; 범위 밖).
- 한 낱말 문장 앞에 공백이 있으면 `SQLUtils.getFirstKeyword`가 키워드를 못 읽는다(전부터, 모든 방언). 접두어 뒤에서는 공백을 지워 피했다.

### 확인 (2026-11-17, 전: 이 브랜치의 기준 `189a23a9b2` 빌드, 후: 이 브랜치 빌드, 사설 작업 공간, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

- **분류 하네스(서버 없음):** 방언 6개(PostgreSQL, SQL Server, Oracle, MySQL, Altibase, ShardSphere = Altibase + 이 기여) × 303문장(접두어 12형태 × 본문 22,
  접두어가 아닌 경우 29) + 나누기 10개. 결정 14개(종류, 파싱, `isModifying`, 위험 3개, 편집기 DROP, 확인, 트랜잭션, 운영 연결 확인, Agent 접근·읽기·이유, 문장 수).
  **다른 DB 5개: 전후 차이 0.** ShardSphere: 접두어 문장 243개 중 221개가 쌍둥이와 같고, 22개는 일부러 다름(접두어 COMMIT/ROLLBACK = UNKNOWN). 하네스와 결과:
  `D:\Tools\_scratch\shardsphere-prefix-safety\harness\`(저장소 밖).
- **샤딩 메타 노드(벤더 서버, SELECT만):** 연결 두 개(읽기 전용, 보통), 값은 환경 변수 참조, 암호 저장 끔. 접두어 SELECT(`NODE[meta]`, `NODE[data]`,
  `NODE[data('<노드>')]`, `SHARD … FROM <샤드 테이블>`, `FROM NODE[data](…)`)는 전처럼 행이 보이고(노드별 4행 등), 상태 줄·쿼리 관리자 그대로, F5 다시 실행.
  다시 읽기는 전처럼 "단일 SELECT 쿼리가 아님". 확인 대화상자: 없는 테이블 `CHR_S6_NO_SUCH_TABLE`에 접두어 DELETE·UPDATE·DROP·TRUNCATE·`SHARD DELETE`를
  두 연결에서 F5 → 대화상자(취소가 기본 단추) → 닫음 → "쿼리가 취소되었습니다.", 쿼리 관리자에 그 문장 없음. 벤더 서버로 간 실행은 모두 SELECT(쿼리 관리자 감사 51개, SELECT 아닌 것 0; 첫 세션의 연결 때 카탈로그 읽기는 감사 밖).
- **연결마다:** Altibase와 샤딩 연결을 함께 열고 두 순서, 새로 고침(같은 방언 객체, 같은 답), 끊고 다시 연결(새 데이터 원본·방언, 같은 답)에서 Altibase는 늘 접두어 0,
  낱말 없음.
- **Altibase 7.3(시험 서버):** `NODE` 열이 있는 `CHR_S6_T`를 만들고 SELECT·UPDATE(WHERE)·DELETE(WHERE)는 실행, WHERE 없는 UPDATE·DELETE는 확인(취소),
  `NODE[data] DELETE FROM CHR_S6_T`는 접두어가 아니라 확인 없이 서버로 가고 서버가 200705(구문 오류)로 거절(전과 같음). 표는 지우고 카탈로그에서 0건 확인.
- 회귀 테스트는 작성·실행하지 않았다. 영향받을 수 있는 기능: 모든 DB의 실행 전 확인, 트랜잭션 표시·스마트 커밋, 문장 나누기, Agent 분류, 쿼리 창 강조.

### 미확인 (벤더 문의)

- 미확인: `NODE[...]`/`SHARD` 뒤에 COMMIT·ROLLBACK·EXEC·블록(PSM)을 받는지, 받으면 뜻.
- 미확인: 읽기 전용 연결(`setReadOnly(true)`)에서 접두어 DML을 서버가 막는지.
- 미확인: 접두어 문장의 실행 계획(`EXPLAIN PLAN`·`ALTER SESSION SET EXPLAIN PLAN`)이 어느 노드의 계획을 주는지.
- 미확인: `NODE` 다음과 `[` 사이 줄바꿈, 이름에 공백이 든 따옴표 이름을 받는지(인식은 받음).

## 아직 없는 것

GEMINI 트리와 복제 탭(작업 4·5), 접두어 문장의 행 수·필터·페이지와 자동 완성(작업 6b), 계열 공통 공간 탭(작업 7), 관리 보고서(작업 8),
샤딩 탐색기(작업 9), 쓰기 기능(작업 11), 샤딩 JDBC 드라이버(작업 12). 계획: `D:\Tools\_briefs\shardsphere-plugin-plan.md`(저장소 밖).

## 미확인 (벤더 문의)

- 미확인: `DBMS_SHARD` 패키지·`SYS_SHARD` 사용자가 ShardSphere에만 있는지. Altibase의 샤딩 기능이 있는 판(시험 서버 7.3에는 없음)에도 있다면 그 서버에서
  "ShardSphere" 노드가 GEMINI 아니오로 보인다.
- 미확인: 메타 노드의 판정(`SYS_SHARD` 사용자 + `SHARD_META_ENABLE` = 1)이 데이터 노드와 메타 노드를 늘 가르는지. 데이터 노드에도 `SYS_SHARD`가 만들어지는지.
- 미확인: `SYS_GEMINIS_`가 GEMINI를 쓰지 않는 ShardSphere 설치에도 늘 있는지(두 서버 모두 있음, 둘 다 GEMINI 비어 있음).

## 확인 (2026-11-17, 이 브랜치 빌드, 사설 작업 공간·설정·데이터 폴더, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

- 연결은 `SS`, `SHARD`, `ALTI`, 값은 환경 변수 참조(`${...}`)로만, "암호 저장" 끔, ShardSphere 두 연결은 읽기 전용 표시. 벤더 서버에서는 연결, 카탈로그 읽기,
  SELECT만 했다(감지 문장, 트리의 시스템 정보 폴더).
- 캡처: `D:\Tools\_scratch\shardsphere-plugin-skeleton\sheets\` — `after-1-ss-property-view.png`(SS: 메타 노드 false), `after-2-shard-property-view.png`(SHARD: true),
  `after-3-alti-system-info.png`(ALTI: 노드 없음), `before-*`(기능 없는 제품의 같은 화면: 세 연결 모두 노드 없음). "전" 캡처는 origin/command 빌드가 아니라
  이 브랜치에서 feature 포함 줄만 뺀 빌드다(확장 지점은 있고 확장이 없음).
- 처음 빌드에서 연결이 실패했다: 상위 클래스 생성자가 연결을 열며 `getAdapter`를 부르는데 그때는 이 클래스의 필드 초기화 전이라 확장 목록이 null이었다.
  목록을 null 허용으로 두고 "감지 전"으로 읽게 고쳤다(확장 실패가 연결을 막지 않는다는 규칙을 이 경로에서도 지킴).
- 회귀 테스트는 작성·실행하지 않았다. 영향받을 수 있는 기능: Altibase·ShardSphere 연결, 새로 고침, Altibase 데이터 원본의 모든 어댑터(서버 출력, 실행 계획,
  세션 관리자), System Info 폴더.

## upstream 대비 변경점

- upstream에는 없다. 새 번들·feature, Altibase 플러그인의 확장 지점([altibase.md](altibase.md) "서버 확장").

## 변경 이력

- 2026-11-17 최초 작성: Altibase 서버 확장 지점과 ShardSphere 번들·feature 뼈대, 카탈로그로 감지(GEMINI·샤딩 패키지·메타 노드), System Info 아래 읽기 전용 노드
  (앱 변경 #139 작업 2·3, `chromite/shardsphere-plugin-skeleton`).
- 2026-11-17 문장 접두어 `NODE[...]`/`SHARD`: 모델의 중립 자리 `SQLDialect.getStatementPrefixLength`, 샤딩 패키지가 있는 연결의 방언 기여(접두어, 낱말, 함수),
  종류에 기대는 판단을 본문으로(실행 전 확인, 트랜잭션, 운영 연결 확인, 문장 나누기, Agent), 강조. 접두어 DML이 확인 없이 나가던 구멍을 막음
  (앱 변경 #139 작업 6a, `chromite/shardsphere-prefix-safety`), 위 "문장 접두어".
