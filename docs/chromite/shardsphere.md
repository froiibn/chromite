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
| `plugins/com.seizia.chromite.ext.shardsphere` (모델만, UI 번들에 의존하지 않음) | `plugin.xml`: 확장 하나(`<serverExtension id="shardsphere">`)와 샤딩 탐색기 트리 주입(`<datasourcePatch id="altibase">`). `ShardSphereServerExtension`: 감지. `model/ShardSphereServer`: 연결마다의 결과이자 System Info 아래 노드, 읽기 전용 속성, 방언 기여, 복제 카탈로그. `model/GeminiReplicationCatalog`: GEMINI 복제 카탈로그(아래 "GEMINI"). `ShardSphereStatementPrefix`: 문장 접두어 인식. `model/ShardingDictionary`·`ShardNode`·`ShardObject`·`ShardPlacement`·`ShardingProperty`·`ShardLink`, `internal/ShardSphereMessages`: 샤딩 탐색기(아래). `model/ShardTableProperties`: 테이블 속성의 TTL과 샤드(아래 "테이블 속성: TTL과 샤드"). `model/ShardTableTtl`: 기존 테이블의 TTL 설정·변경·해제(아래 "TTL 관리"). `OSGI-INF/l10n/bundle*.properties`: 속성·트리 이름(영어, 한국어) |
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
- **복제 카탈로그(2026-11-17 작업 4·5):** `getReplicationCatalog()`. GEMINI를 Replications 폴더와 복제 탭에 보인다(아래 "GEMINI").
- **기존 객체의 추가 속성(2026-11-17 수정 6, 계획의 S8):** `getObjectProperties(monitor, object)`. 테이블의 TTL과 샤드 객체(아래 "테이블 속성: TTL과 샤드").
- **테이블 TTL(2026-11-17 앱 변경 #152, S6/S7 가운데 좁은 능력):** `getAdapter(AltibaseTableTtl.class)`. 기존 테이블의 TTL 설정·변경·해제(아래 "TTL 관리").
- **나중으로 둔 것(쓰는 곳이 아직 없음):** 관리 보고서 지점(S4)·일반 객체 명령 틀(S6/S7)은 그 기능과 함께 정한다. 트리 자리도 지금은 System Info 한 곳뿐이다.

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
| 다시 읽기(행 수, 필터, 페이지), 다시 실행해 내보내기 | `ResultSetSource.java:101`, `SQLQueryDataContainer.java:262`, `DataExportWizard.java:532` | 전처럼 막음(종류는 이제 SELECT지만 다시 쓰기가 접두어를 잃음). 6b에서 방언의 하위 질의 형태로 다시 읽음(아래 "접두어 문장 다시 읽기") |
| 결과 편집(단일 원본 테이블), 자동 행 제한(LIMIT), 끊긴 뒤 자동 재실행 | `SQLQuery.getStatement()`가 없음(`:359`) → `DBExecUtils`, `DBUtils.java:1631`, `SQLQueryRecovery.java:99` | 전과 같음(편집 안 됨, LIMIT 안 붙임, 재실행 안 함) |
| 커밋·롤백 문장 뒤 세션 커밋·롤백 | `SQLQueryExecuteJob.java:443-446` | 접두어 COMMIT/ROLLBACK은 UNKNOWN 그대로(세션 전체를 끝내지 않음) |
| 강조 | `SqlTokenClassifier.java:219,263` | 이름 + 대괄호 이름 → 키워드·연산자·문자열 |

### 읽기 전용 연결

쿼리 창에는 읽기 전용 연결에서 변경 문을 미리 거절하는 검사가 없다(전부터, [swing-sql-exec-safety.md](swing-sql-exec-safety.md) "남은 것"): 드라이버의
`setReadOnly(true)`가 서버에서 막는 것에 맡긴다. 그래서 읽기 전용 연결에서도 접두어 DML에 위 확인 대화상자가 뜬다(접두어 없는 DML과 같음). 서버가 접두어 DML을
읽기 전용 연결에서 막는지는 미확인이다(조사: `setReadOnly`는 접두어 SELECT를 막지 않음). Chromite가 스스로 판단하는 읽기 전용 검사는 Agent 정책뿐이고, 그것은
위 분류기로 접두어 쓰기를 전처럼 막는다. 쿼리 창의 읽기 전용 거절은 새 기능(새 문구)이라 이 변경에 넣지 않았다(남은 일).

### 아직 남은 것

- 행 수·필터·페이지, 접두어 뒤의 자동 완성, 실행 계획: 6b에서 함(아래 "접두어 문장 다시 읽기").
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
- 접두어 문장의 실행 계획: 6b에서 확인(메타 노드의 계획만, 아래 "접두어 문장 다시 읽기" "실행 계획").
- 미확인: `NODE` 다음과 `[` 사이 줄바꿈, 이름에 공백이 든 따옴표 이름을 받는지(인식은 받음).

## 접두어 문장 다시 읽기 (2026-11-17, 앱 변경 #139 작업 6b)

### 문제

6a 뒤 접두어 SELECT는 종류가 SELECT로 정해지지만 다시 읽기를 일부러 막아 두었다: Count Rows, 열 필터, 서버 정렬, 다음 페이지, 다시 실행해 내보내기가 모두
"단일 SELECT 쿼리가 아님"이었다. Chromite의 감싸기는 `SELECT COUNT(*) FROM (NODE[data] SELECT …) dbvrcnt`, `SELECT * FROM (NODE[data] SELECT …) z_q WHERE …`라
서버가 거절(200705)한다. 막힌 경우 깔때기를 눌러도 아무 일이 없었고(도구 설명에만 이유), 접두어 뒤에서는 자동 완성이 없었다.

### 서버가 받는 형태 (메타 노드, JDBC, 2026-11-17)

`SELECT … FROM NODE[x](본문) 별칭 [WHERE … ORDER BY …]`, `SELECT … FROM SHARD (본문) 별칭`. 접두어 형태 7가지(`meta`, `data`, `data('n')`, `data('n', "n2")`,
`data()`, `NODE [ data ]`, 소문자) × 본문 9가지(그냥, WHERE, ORDER BY, ORDER BY+LIMIT, 앞 블록 주석, 끝 줄 주석, 괄호로 싼 SELECT, 하위 질의, GROUP BY)에서 행 수·필터·
정렬·필터+정렬·값 목록·필터 위의 값 목록(두 겹 파생 테이블)을 준비하고 실행했다: 모두 받음(내 시험 문장이 본문에 없는 열을 가리킨 4개만 "Column not found").
감싼 COUNT는 접두어 문장의 행 수와 같다(meta 161, data 576, WHERE 352, LIMIT 3이면 노드마다 3 → 12, `data('n')` 144, 두 노드 288). 샤드 테이블:
`NODE[data]`·`SHARD`·`data('n')`의 노드별 `COUNT(*)`도 같은 형태로 됨. 옛 감싸기 둘은 여전히 200705. `setMaxRows`는 감싼 문장에도 전체에 적용된다.
일반 ShardSphere 서버(메타 노드가 아님)에서는 `NODE[...]` 문장도 `FROM NODE[meta](…)`도 200705이고, `SHARD SELECT 1 FROM DUAL`은 실행된다.

### 자리와 각 다시 쓰기의 결정

- **자리(모델, 중립):** `SQLDialect.getPrefixedStatementSubquery(text)`(기본 null): 접두어 문장 전체를 대신할 파생 테이블 글(별칭 없음), 또는 null(감쌀 수 없음).
  `SQLQuery.hasSubqueryForm()`: 접두어가 없으면 true(전과 같음), 있으면 방언의 형태가 있을 때만 true.
- **ShardSphere의 형태(`ShardSphereStatementPrefix.getSubqueryForm`):** `NODE[x]` + `(\n본문\n)` 또는 `SHARD (\n본문\n)`. 접두어는 사용자가 쓴 글 그대로(앞 주석은 뺌),
  본문 끝의 `;`과 공백은 뺌, 본문의 ORDER BY·LIMIT은 안에 그대로(노드마다 적용, 사용자가 쓴 대로), 닫는 괄호 앞 줄바꿈은 끝 줄 주석을 끝냄.
  형태가 없는 것: 접두어 둘(서버 거절), `NODE[all]`(SELECT 앞에서 서버 거절), 본문이 SELECT가 아님. **샤딩 메타 노드에서만** 낸다(`ShardSphereServer`): 형태를
  확인한 서버가 메타 노드뿐이고, 일반 서버는 `NODE` 형태를 거절한다. 그 밖의 서버에서는 접두어 SELECT가 전처럼 막힌다.

| 다시 쓰기 | 위치 | 결정 |
|---|---|---|
| 행 수(Count Rows, 내보내기의 전체 행 수) | `SQLQueryTransformerCount.transformQuery`·`wrapPrefixedQuery`, `SQLQueryDataContainer.countData` | 형태 사용: `SELECT COUNT(*) FROM NODE[x](…) dbvrcnt`. 형태가 없으면 전처럼 거절 |
| 필터·서버 정렬(그리드, 필터 적용 뒤 다음 페이지, 내보내기, 실행 작업의 필터) | `StandardSQLDialectQueryGenerator.getQueryWithAppliedFilters`·`getPrefixedFilterQuery`(`SQLDialect.addFiltersToQuery`가 부름) | 형태 사용: `SELECT * FROM NODE[x](…) z_q WHERE … ORDER BY …`. 본문에 조건을 넣지 않고(접두어를 잃음), 본문의 ORDER BY를 밖으로 옮기지 않음 |
| 필터 팝업의 값 목록 | `SQLGroupingQueryGenerator.generateGroupingQuery` | 형태 사용: `SELECT 열, COUNT(*) … FROM NODE[x](…) src GROUP BY …`. 다른 열 필터가 있으면 필터 쿼리(접두어 없음)를 전처럼 괄호로 감쌈(서버가 받음) |
| 다음 페이지(필터 없음) | `SQLQueryDataContainer.readData` → `DBUtils.makeStatement` | 다시 쓰지 않음: 접두어 문장을 그대로 다시 실행하고 `setMaxRows`·건너뛰기(전부터의 방식, 문장이 파싱되지 않으므로 LIMIT을 붙이지 않음) |
| 다시 실행해 내보내기 | `DataExportWizard.appliesExportQuery` | 형태가 있으면 허용(필터가 있으면 위 필터 쿼리, 없으면 문장 그대로) |
| 다시 읽기 허용 | `ResultSetSource.getRequeryBlocker` | `hasSubqueryForm()`이 거짓이면 전과 같은 "단일 SELECT 쿼리가 아님" |
| 결과 편집 | `SQLQuery.getStatement()`·단일 원본 테이블 없음(6a) | **꺼진 채로 둠.** UPDATE·DELETE를 만들려면 접두어와 어느 노드인지의 결정이 필요하다(이 변경 밖) |
| 자동 행 제한(LIMIT), 끊긴 뒤 자동 재실행 | `DBUtils.java`, `SQLQueryRecovery` | 바꾸지 않음(6a와 같음) |

**정렬:** 서버 정렬(머리글 정렬, 행이 더 있을 때)은 바깥 `ORDER BY`라 모든 노드를 합쳐 정렬한다. 사용자가 쓴 ORDER BY(본문)는 노드마다 정렬이라 결과가 노드 순서
(노드1의 정렬된 행, 노드2의 …)로 나온다. 그래서 머리글로 정렬하면 노드가 섞인 전체 순서가 보이고, 정렬을 없애면 노드별 순서로 돌아간다. 모든 행을 이미 읽었으면
전처럼 그리드 안에서 정렬한다(설정 "스마트").

### 막힌 경우의 이유

- 깔때기를 누르거나 필터를 적용하거나(오른쪽 클릭 Filter by Value 등) Count Rows를 부르면, 다시 읽을 수 없는 결과에서는 전에는 아무 일도 없었다. 이제 상태 줄(결과
  아래 줄)에 기존 문구로 이유가 보인다: "필터를 쓸 수 없음: 단일 SELECT 쿼리가 아님", "행 수 세기 실패: 단일 SELECT 쿼리가 아님"
  (`ResultSetViewerPanel.showRequeryBlocked`). 새 문구·대화상자는 없다. 원인은 같은 검사(`canRequery`)이므로 프로시저 호출 결과(SQL Server `EXEC sp_who`)와 일반 서버의
  `SHARD SELECT`가 똑같이 보인다. Count Rows는 메뉴와 상태 줄에서 이미 숨겨지므로 보통은 깔때기 클릭이 사용자가 만나는 경우다.

### 자동 완성

- **접두어 안:** `NODE[` 뒤 `meta`, `data`(입력한 `NODE`가 대문자면 대문자). `all`은 제안하지 않는다(2026-11-17 고침): 시스피어 답변(2026-11-17) "`node[all]`에서
  DML은 동작하지 않음, DML은 `node[data]`로만", 메타 노드는 `NODE[all] SELECT`를 거절하고, 분산 관리 매뉴얼은 `NODE[all]`을 DDL 앞에서만 쓴다. 손으로 친
  `NODE[all]`은 그대로 접두어로 알아본다(실행 전 확인·트랜잭션 분류가 본문으로 판단, 아래 "접두어 규칙"). `data(` 안(쉼표 뒤 포함)은 메타 노드의 노드 이름을 작은따옴표로(`'NODE1'`). 이름은 연결할 때 한 번
  `SELECT NODE_NAME FROM SYS_SHARD.NODES_ ORDER BY NODE_ID`로 읽는다(이름만, 호스트·포트는 읽지 않음, 새로 고침에서 다시). 메타 노드가 아닌 서버는 이름이 없다.
  자리: `SQLDialect.getStatementPrefixProposals(text)`(기본 null) → `AltibaseSQLDialect` → `ShardSphereStatementPrefix.getProposals`, 화면은 `SqlCompletionAnalysis`.
  `[`·`(`를 입력할 때 자동으로 뜨지는 않는다(Ctrl+Space, 또는 글자를 입력할 때 자동 완성).
- **접두어 뒤 문장:** 커서가 접두어 뒤에 있으면 접두어를 공백으로 지운 글(위치는 그대로)로 분석한다(`SqlCompletionAnalysis.blankStatementPrefix`). 엔진은 줄의 첫
  낱말을 문장의 첫 키워드로 읽고 파서는 접두어를 모르기 때문이다. 그래서 테이블·열·키워드 제안이 접두어 없는 같은 문장과 같고, 접두어 바로 뒤에서는 문장 첫 키워드
  (`SEL`까지 쳤으면 SELECT)가 나온다. 의미 모델은 접두어와 뒤 공백을 뺀 본문으로 만들어 그 자리에 등록한다(`recognizeStatement`, 마우스 올림·Ctrl+클릭도 같음).

### 실행 계획

계획(Ctrl+L)은 SQL을 바꾸지 않는다: 드라이버의 계획 모드를 `ONLY`로 두고 같은 문장을 준비해 `getExplainPlan()`을 읽는다(`AltibaseExecutionPlan`). 접두어 SELECT에도
SELECT만 준비하므로 막지 않았다. 결과(메타 노드, JDBC와 앱 같음): `NODE[meta]`는 메타 노드 자체의 계획(PROJECT, SCAN), `NODE[data]`·`data('n')`·`SHARD`·감싼 형태는
메타 노드의 조정 계획만(`PROJECT … SHARD-COORDINATOR`), 데이터 노드의 계획은 오지 않는다. 계획 뒤 같은 창의 실행은 정상이다.

### 확인 (2026-11-17, 전: 기준 `4efec38dcb` 빌드, 후: 이 브랜치 빌드, 사설 작업 공간, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

- **다시 쓰기 하네스(서버 없음):** 방언 6개 × 문장(일반 SELECT 30개, Altibase·ShardSphere는 접두어 10형태 × 본문 11). **다른 방언(PostgreSQL, SQL Server, Oracle,
  MySQL, 접두어 없는 Altibase): 260행 전후 같음(차이 0).** ShardSphere: 접두어 SELECT 80개가 "다시 읽음", `NODE[all]`·접두어 둘 20개는 막힘, DML 10개는 막힘(전과 같음).
- **메타 노드(읽기 전용 연결, SELECT만):** `NODE[data]`(576행, 한 번에 200행): 행 수 576(수동 `SELECT COUNT(*) FROM NODE[data](…)`와 같음), 다음 200행, 필터 `TABLE_TYPE = 'T'`
  → 행 수 352, 정렬 ↓ → `… z_q ORDER BY z_q.TABLE_ID DESC`, 필터 팝업 값 3개, 내보내기 576행(머리글 + 576줄). `data('n')` 144·필터 41, `meta` 161·필터 102,
  ORDER BY+LIMIT 3 → 12, 샤드 테이블 노드별 개수(`NODE[data]`, `SHARD`) 행 수 4, 앞 주석과 끝 `;` → `NODE[data](…)`. 새로 고침 뒤에도 같음. 벤더 서버로 간 실행은
  모두 SELECT(쿼리 관리자 감사 SHARD 73 + 42, SS 12, SELECT 아닌 것 0).
- **막힌 경우:** 일반 서버의 `SHARD SELECT 1 AS X FROM DUAL`, SQL Server `EXEC sp_who`: 깔때기·필터 → "필터를 쓸 수 없음: 단일 SELECT 쿼리가 아님", Count Rows →
  "행 수 세기 실패: 단일 SELECT 쿼리가 아님".
- **Altibase 7.3:** 일반 SELECT의 행 수·필터·정렬 문장은 전과 같은 괄호 감싸기, 접두어 인식 0, `NODE[`에 제안 없음.
- 캡처: `D:\Tools\_scratch\shardsphere-prefix-requery\sheets\`(저장소 밖).
- 회귀 테스트는 작성·실행하지 않았다. 영향받을 수 있는 기능: 모든 DB의 결과 필터·정렬·행 수·값 목록(접두어가 없으면 코드 경로가 같음), 다시 읽을 수 없는 결과의 상태 줄,
  SQL 자동 완성(접두어가 없으면 같음).

### 미확인 (벤더 문의)

- 미확인: 일반 ShardSphere 서버(메타 노드가 아님)에서 `SHARD SELECT`의 뜻과 `FROM SHARD (…)` 형태를 받는지(받는지 시험하지 않고 막아 둠).
- 미확인: 데이터 노드에 직접 연결했을 때 접두어 문장과 감싼 형태.
- 미확인: 데이터 노드의 계획을 볼 방법(`SHARD-COORDINATOR` 아래가 비어 있음).

## 샤딩 탐색기 (2026-11-17, 앱 변경 #139 작업 9)

### 목적

샤딩 메타 노드에 연결하면 Object Explorer에서 샤딩 구성을 읽기만으로 본다: 데이터 노드, 샤드 객체(종류, 분산 방식, 키), 객체마다 분산(값·범위 → 노드, 복제,
독립, 기본 노드), 사전 버전, 샤딩 속성, 이 세션의 노드 연결. 다른 서버(일반 ShardSphere, Altibase)에는 아무것도 보이지 않고 질의도 가지 않는다(감지 문장뿐).
사용자 결정: 찾은 것은 모두 다룸, 기능은 ShardSphere 번들에, 화면은 짧게 기존 흐름 안에서(트리 노드와 속성 보기, 새 창·설명 문구 없음).

### 자리와 이유

```
시스템 정보
└ ShardSphere            (속성: 이름, GEMINI 카탈로그, 샤딩 패키지, 샤딩 메타 노드, 샤딩 사전 버전 ← 메타 노드에서만)
  ├ 데이터 노드          NODE1 … (속성: 이름, ID, 호스트, 포트, 대체 서버, 추가 옵션, 샤드 객체 수)
  ├ 샤드 객체            <샤드 테이블> … (속성: 이름, 소유자, 종류, 분산 방식, 샤드 키, 서브 분산 방식, 서브 샤드 키, 기본 노드, 샤드 ID, [노드별 행 수])
  │ └ 250, 500, …        분산 행 (속성: 이름, 종류, 값, 서브 값, 노드)
  ├ 샤딩 속성            SHARD_* (System Info의 Properties와 같은 열: Name, Dynamic, 값)
  └ 노드 연결            NODE1 … (속성: 노드, 노드 ID, 세션 ID, 노드 세션 ID, 연결 시각, 사용·준비·실행 횟수, 샤드 SCN, 연결 실패)
```

- **ShardSphere 노드 아래(골랐음).** Altibase 트리의 중립 자리(System Info 안 `<items path="serverExtension" property="extensionObjects">`, 뼈대 작업)가
  이미 있고, 감지 결과(메타 노드인지)가 그 노드의 속성이라 "이 서버의 샤딩"이 그 아래에 있는 것이 자연스럽다. 폴더 이름이 모두 "샤드/샤딩/노드"라 따로
  "샤딩" 폴더를 한 단계 더 두지 않았다(클릭 한 번 덜).
- **대안(고르지 않음): 연결 바로 아래 "샤딩" 폴더.** 스키마·저장소·보안 옆에 보여 찾기 쉽지만, 공급자 트리의 최상위 폴더가 읽을 목록은 데이터 원본
  (`AltibaseDataSource`)의 getter여야 한다(트리는 부모 객체의 실제 클래스를 리플렉션으로 읽음, `DBNDatabaseNode.java:1052`). 그러려면 `ext.altibase`에
  중립 자리를 하나 더 두어야 해서(확장마다 폴더를 고르는 자리) 이번에는 두지 않았다. 사용자가 원하면 그 자리를 더하고 폴더를 옮기면 된다.
- **주입:** 이 번들의 `plugin.xml`이 `<datasourcePatch id="altibase"><treeInjection path="altibase/serverExtension">`로 폴더 넷을 넣는다. 공급자 트리 정의에
  ShardSphere 이름이 `ext.altibase`에 들어가지 않고, 번들을 빼면 폴더도 없다. `ext.altibase`의 자리(seam)는 바꾸지 않았다.
- **메타 노드에서만:** 폴더마다 `visibleIf="object.isShardingMetaNode()"`. 일반 ShardSphere 서버의 ShardSphere 노드에는 자식이 없다(펼침 표시도 없음).
  같은 자리에 다른 확장의 객체가 오면 식이 실패해 숨는다. 읽기 상태(`ShardingDictionary`)도 메타 노드인 연결의 `ShardSphereServer`만 갖고, getter는 그
  밖에서 질의 없이 빈 목록이다(이중 안전). 사전 버전 속성은 `@Property(visibleIf = MetaNodeOnly.class)`로 메타 노드에서만 보인다.
- **폴더에 `type`이 없다:** 주입된 폴더의 `type` 클래스는 공급자 번들(`ext.altibase`)의 클래스 로더로 읽혀(`DBNNode.getFolderChildrenClass`) 이 번들의
  클래스를 찾지 못한다. 자식의 클래스는 getter의 반환 형식에서 읽힌다(`DBNDatabaseNode.getChildrenClass`).

### 모델과 질의 (모두 SELECT, 메타 노드의 메타데이터 세션)

| 객체 (`model/`) | 언제 읽나 | 질의 |
|---|---|---|
| `ShardSphereServer.getShardingVersion` | ShardSphere 노드의 속성 보기(메타 노드) | `SELECT MAJOR_VER, MINOR_VER, PATCH_VER FROM SYS_SHARD.VERSION_` |
| `ShardNode` | 데이터 노드 폴더를 열 때 | `NODES_` + 노드마다 놓인 샤드 객체 수(부분 질의: `DEFAULT_NODE_ID`이거나 `RANGES_`·`CLONES_`·`SOLOS_`에 그 노드가 있는 객체) |
| `ShardObject` | 샤드 객체 폴더를 열 때 | `OBJECTS_ LEFT OUTER JOIN NODES_`(기본 노드 이름), 소유자·이름 순 |
| `ShardPlacement` | 샤드 객체를 펼칠 때(객체마다 한 번) | `RANGES_`('V') ∪ `CLONES_`('C') ∪ `SOLOS_`('S'), 각각 `LEFT OUTER JOIN NODES_`, 샤드 ID 바인드. 기본 노드 행은 질의 없이 객체 행에서 더함 |
| `ShardingProperty` | 샤딩 속성 폴더를 열 때 | `SELECT * FROM V$PROPERTY WHERE NAME LIKE 'SHARD\_%' ESCAPE '\' ORDER BY NAME` |
| `ShardLink` | 노드 연결 폴더를 열 때 | `V$SHARD_CONNECTION_INFO`(주소 두 열은 읽지 않음), 노드 ID 순 |
| `ShardObject.getRowsPerNode` | 아래 "노드별 행 수" | `NODE[data] SELECT SHARD_NODE_NAME(), COUNT(*) FROM "소유자"."이름"` |

- 질의 문장은 이 번들 밖 JDBC로 먼저 실행해(같은 서버, 읽기 전용 연결 하나) 받는 것을 확인한 뒤 넣었다(`NULL` 리터럴이 든 `UNION ALL`, `LIKE … ESCAPE`, 부분 질의).
- **분산의 순서:** 해시는 상한이 글자(`VARCHAR`)로 저장되어 사전 순서가 1000, 250, 500, 750이다. 해시(`H`)면 Java에서 수로 정렬하고(서버의 `TO_NUMBER`는
  `$$N/A` 같은 값에서 오류가 날 수 있어 쓰지 않음), 수가 아닌 값이 있으면 그 쌍은 사전 순서를 둔다. 다른 방식(범위, 리스트)은 사전에 있는 순서 그대로다
  (범위 경계의 뜻을 모르므로 순서를 만들지 않음). 행 순서는 값 → 복제 → 독립 → 기본 노드.
- **이름:** 분산 값 행은 값(트리에서 순서가 보이게), 복제·독립·기본 노드 행은 노드 이름. 서브 값 `$$N/A`는 빈 값. 노드 행이 사전에 없으면 노드 번호.
- **샤딩 속성:** System Info ▸ Properties의 `AltibaseProperty`를 그대로 쓴다(같은 행, 같은 열 이름·번역). 부모만 ShardSphere 노드로 바꾼 하위 클래스
  (`ShardingProperty`)다: 부모가 데이터 원본이면 트리가 가상 노드 규칙(`DBNDatabaseNode.getPlainNodeName`)으로 이름 앞에 연결 이름을 붙였다(`SHARD.SHARD_…`).
- 각 부분은 처음 열 때 읽고(`JDBCObjectCache`) 새로 고침이 비운다. 객체 관리자가 없어 모든 노드가 읽기 전용이다.

### 코드 값

| 열 | 코드 | 보이는 이름 | 근거 |
|---|---|---|---|
| `OBJECT_TYPE` | `T` | 테이블 / Table | 시스피어 답변(2026-11-17), 서버에서 봄(2개) |
| | `P` | 프로시저 / Procedure | 시스피어 답변(2026-11-17) |
| `SPLIT_METHOD`, `SUB_SPLIT_METHOD` | `H` | 해시 / Hash | 시스피어 답변(2026-11-17), 서버에서 봄(`SPLIT_METHOD`만, 서브는 NULL) |
| | `R`, `L` | 범위 / Range, 리스트 / List | 시스피어 답변(2026-11-17) |
| | `C`, `S` | 복제 / Clone, 독립 / Solo | 시스피어 답변(2026-11-17) |
| 그 밖 | | 코드 그대로 | 뜻을 만들지 않음 |

이름은 `internal/ShardSphereMessages*.properties`(영어, 한국어). 복합 분산은 따로 코드가 없고 서브 분산 방식·서브 키가 채워진 객체다(미확인: 이 서버에 없음).

### 노드별 행 수

- 모든 데이터 노드에서 `COUNT(*)`를 하므로 트리를 열 때는 읽지 않는다. **앱에 이미 있는 설정 "비용이 큰 개체 속성 읽기(행 수, 크기)"**(연결의 메타데이터 설정,
  기본 꺼짐, `DataSourcePropertyFilter`)를 켠 연결에서만 샤드 테이블의 속성 보기에 "노드별 행 수"가 보이고 그때 읽힌다(`@Property(expensive = true)`,
  목록 열이 아님 → 샤드 객체 폴더의 목록·세부 정보에서는 읽지 않음). 값은 `<노드>: <행 수>, …`로 노드 순서. 한 번 읽으면 새로 고침까지 다시 세지 않는다.
- 프로시저(`P`)와 모르는 종류에는 문장을 보내지 않는다(빈 값). 메타 노드 자신의 복사본은 비어 있어(조사) 세지 않는다.
- 탐색기에서 데이터 노드로 가는 문장은 이것 하나다. `NODE[...]`는 SELECT 앞에서만 쓴다.

### 노드 연결

`V$SHARD_CONNECTION_INFO`는 질의한 세션의 연결만 보이고, 그 세션이 데이터 노드에 무엇을 보낸 뒤에야 행이 생긴다. 탐색기는 행을 채우려고 노드에 문장을
보내지 않는다. 그래서 보통은 비어 있고, 같은 연결의 메타데이터 세션이 노드별 행 수를 읽은 뒤에는 노드마다 한 행이 보인다(확인: 4행). 쿼리 창이 따로 연결을
쓰면 그 세션의 연결은 여기에 보이지 않는다. `CONNECT_TIME`은 정수 그대로 보인다(유닉스 시각으로 보이지만 미확인이라 바꾸지 않음).

### 실제 테이블로 가기, 일반 트리의 샤드 테이블

- 사전은 카탈로그와 이름(소유자, 객체 이름)으로만 이어진다. Swing 속성 보기에는 객체 참조 값을 눌러 그 노드로 가는 기능이 없다(`PropertiesPanel.formatValue`는
  이름만 그림). 그래서 소유자와 이름을 속성으로 보인다. 가는 기능을 넣으려면 UI 쪽(속성 보기의 링크 칸 → `DBNUtils.getNodeByObject` → `revealNode`)과
  모델의 참조 속성(`linkPossible`)이 함께 필요하다(이 변경 밖).
- 일반 트리의 테이블에 분산 방식과 샤드 키를 보이는 것은 2026-11-17에 더했다(아래 "테이블 속성: TTL과 샤드").

### 새로 고침

- 샤드 객체 노드: 그 객체의 분산만 다시 읽는다(`ShardObject.refreshObject`). ShardSphere 노드와 그 아래 폴더: 앱의 다른 폴더처럼 부모 객체로 넘어가
  ShardSphere 노드가 사전 전부(버전·노드·객체·펼친 객체의 분산·속성·연결)를 비우고 다시 읽는다. 잎 노드(데이터 노드, 분산 행, 속성 하나, 연결 하나)는 앱의
  다른 잎처럼 다시 그리기만 한다. 연결의 새로 고침은 감지부터 다시 한다(새 `ShardSphereServer`).

### 오른쪽 클릭 메뉴

[context-menus.md](context-menus.md) 규칙대로 해당하는 명령만 보인다(덤프, 2026-11-17, 한국어): 데이터 노드·샤드 객체·분산 행·샤딩 속성·ShardSphere 노드는
새 쿼리, 최근 스크립트, 개체 세부 정보 | 이름 복사, 정규화된 이름 복사, 선택하여 복사 | 새로 고침(7개, 모두 켜짐). 네 폴더는 새 쿼리, 최근 스크립트, 개체 세부 정보 |
이름 복사 | 새로 고침(5개). 이름 바꾸기·삭제·새 …·디자인·유지 관리 명령은 숨는다(객체 관리자가 없음, `appliesRename`/`appliesDelete`). 쓰는 명령(노드 관리, 분산,
UNSHARD, 재배치)은 넣지 않았다(쓰기 서버가 필요한 작업 11).

### 확인 (2026-11-17, 이 브랜치 빌드, 사설 작업 공간·설정·데이터 폴더, 앱 안 테스트 에이전트, 밝은 테마·150 %)

- **메타 노드(벤더 서버, 읽기 전용 연결):** 연결 SHARD·SS·ALTI를 함께 열고 System Info와 ShardSphere 노드를 펼쳐 네 폴더가 보일 때까지 샤딩 질의 0개(연결 때
  감지와 노드 이름 읽기뿐), ShardSphere 노드를 고르면 `VERSION_` 하나. 모든 단계를 펼쳤을 때 데이터 노드 4, 샤드 객체 2, 분산 행 8(객체마다 4, 250·500·750·1000
  순서), 샤딩 속성 9 — 사전과 같다(같은 날 JDBC로 읽은 `NODES_` 4행, `OBJECTS_` 2행, `RANGES_` 8행, `SHARD_` 속성 9개). 노드마다 샤드 객체 2. 버전 3.1.0.
  노드별 행 수: 설정 꺼짐이면 속성 없음·질의 없음, 켜면 객체마다 `NODE[data] SELECT SHARD_NODE_NAME(), COUNT(*) …` 하나(4행), 그 뒤 노드 연결 4행. 다시 연결하면
  노드 연결 0행(새 세션).
- **보낸 문장(쿼리 관리자 감사):** 벤더 서버로 간 실행은 모두 SELECT(메타 노드 연결 단계별 10 + 26 + 13 + 43 + 1 + 8, 일반 서버 8 + 18; SELECT 아닌 것 0).
  `NODE[...]`가 붙은 것은 노드별 행 수의 SELECT뿐. 쓰기·DDL·EXEC·ALTER는 없다.
- **새로 고침:** 위 "새로 고침"대로(쿼리 관리자로 확인): 폴더 → 사전 전부, 샤드 객체 → 그 분산 질의 하나, 잎 → 질의 없음. 연결 새로 고침과 끊고 다시 연결 뒤에도
  같은 수.
- **일반 ShardSphere 서버(SS)·Altibase 7.3(ALTI):** SS의 ShardSphere 노드는 자식 0, 속성 넷(사전 버전 없음), 샤딩 질의 0. ALTI는 System Info 자식 둘(전과 같음).
  세 연결을 함께 연 채로, 새로 고침 뒤, 끊고 다시 연결한 뒤 모두 같음.
- **기능을 뺀 제품**(포함 줄을 로컬에서만 주석 처리하고 빌드, 커밋하지 않음): 제품에 ShardSphere 번들 없음, 세 연결 모두 System Info 자식 둘, 감지 문장 없음,
  새로 고침 뒤에도 같음, 실행은 모두 SELECT.
- 캡처: `D:\Tools\_scratch\shardsphere-sharding-explorer\sheets\`(저장소 밖, 한국어·영어, 호스트·포트·소유자·키 열 칸은 가림).
- 회귀 테스트는 작성·실행하지 않았다. 영향받을 수 있는 기능: Altibase·ShardSphere 연결의 System Info 트리, ShardSphere 노드의 속성 보기.

### 미확인 (벤더 문의)

- 확인됨(시스피어 답변 2026-11-17): 객체 종류 `T`·`P`, 분산 방식 `H`·`R`·`L`·`C`·`S`의 뜻. 미확인: 복합(서브) 분산의 `SUB_SPLIT_METHOD`·`SUB_VALUE` 값(이 서버에 없음).
- 미확인: 범위·리스트 방식에서 `RANGES_.VALUE`의 뜻(상한인지, 값 하나인지)과 사전 행의 순서가 정의되어 있는지.
- 미확인: `V$SHARD_CONNECTION_INFO.CONNECT_TIME`의 단위(유닉스 초로 보임), `TOUCH_COUNT`의 뜻.
- 미확인: `DEFAULT_NODE_ID`가 `NODES_`에 없는 번호일 수 있는지(그때 번호로 보임).
- 미확인: 사용자(소유자)가 다른 같은 이름의 샤드 객체가 있을 수 있는지(트리는 이름만 보이고 소유자는 속성).

## GEMINI (2026-11-17, 앱 변경 #139 작업 4·5)

### 목적

ShardSphere의 복제(GEMINI)를 기존 Replications 폴더(한국어 "이중화 객체")와 활동 모니터의 복제 탭에서 읽는다. 전에는 Altibase 이름을 질의해 폴더는 늘 비고
(표 없음 오류를 숨김) 탭은 "읽을 수 없음 … SYS_REPL_HOSTS_"였다. 읽기만 한다: 시작·중지·동기화 같은 제어 문장은 GEMINI가 설정된 서버에서 확인하기 전까지 내지 않는다
(사용자 결정 2026-11-17 "권장안대로").

제어 문장은 2026-11-17 받은 GEMINI 매뉴얼에 있다: `ALTER GEMINI g START [RETRY] | QUICKSTART [RETRY] | STOP | RESET | SYNC [PARALLEL n] [TABLE …] | SYNC ONLY … |
FLUSH [ALL] [WAIT sec]`, 테이블·호스트 추가·삭제, `SET RECOVERY|GAPLESS|GROUPING …`(RESET·테이블·호스트 변경은 멈춘 뒤에만, SYS만). **그래도 숨긴 채 둔다:** GEMINI가
설정된 서버(쌍)에서 쓰기 시험을 하기 전에는 문장 모양(매뉴얼 두 권이 서로 다른 곳이 있음), 실행 계정, 결과 상태 코드를 확인할 수 없다. 켜려면 카탈로그 능력에 문장
낱말(`ALTER REPLICATION` 대신 `ALTER GEMINI`)이 필요하다(`AltibaseConstants`의 문장, 도구 둘, `ObjectMaintenanceActions`).

### 구조

- Altibase 플러그인의 중립 전략 `AltibaseReplicationCatalog`([altibase.md](altibase.md) "복제 카탈로그")에 이 번들이 `model/GeminiReplicationCatalog`를 준다.
  `ShardSphereServer.getReplicationCatalog()`는 GEMINI 카탈로그(`SYSTEM_.SYS_GEMINIS_`)를 감지한 연결에서만 그것을, 아니면 null(= Altibase 카탈로그)을 돌려준다.
  메타 노드인지와 상관없다(일반 서버도 GEMINI 카탈로그가 있음). 결과는 감지 결과처럼 데이터 원본 인스턴스에 있다.
- 이 번들은 `model.admin`에 기대지 않는다: 복제 탭의 Altibase 방언이 데이터 원본의 카탈로그를 읽는다.

### 대응표 (조회 전용 조사 2026-11-16의 열 목록과 이 변경의 준비 확인)

| Altibase | GEMINI | 모델이 읽는 이름으로 별칭 |
|---|---|---|
| `SYSTEM_.SYS_REPLICATIONS_` | `SYSTEM_.SYS_GEMINIS_` | `GEMINI_NAME` → `REPLICATION_NAME`, `GEMI_MODE` → `REPL_MODE`(같은 DECODE), `PEER_GEMINI_NAME` → `PEER_REPLICATION_NAME`. 나머지 열은 같은 이름 |
| `SYSTEM_.SYS_REPL_HOSTS_` | `SYSTEM_.SYS_GEMI_HOSTS_` | `HOST_IP || ':' || PORT_NO` → `REMOTE_ADDR`. **`CONN_TYPE` 없음** → 연결 유형 속성을 숨김 |
| `SYSTEM_.SYS_REPL_ITEMS_` | `SYSTEM_.SYS_GEMI_ITEMS_` | `GEMINI_NAME` → `REPLICATION_NAME`, `GEMINI_UNIT` → `REPLICATION_UNIT` |
| `V$REPSENDER` + `V$REPGAP` | `V$GEMSENDER` + `V$GEMGAP` | `GEM_NAME` → `REP_NAME`, `GEM_GAP` → `REP_GAP`(단위 없이) |
| `V$REPRECEIVER` | `V$GEMRECEIVER` | `GEM_NAME` → `REP_NAME` |
| `REPLICATION_PORT_NO` | `GEMINI_PORT_NO` | 0이면 "사용 안 함" |
| `REPLICATION_GAP_UNIT` | **없음** | 지연은 서버의 수 그대로(단위 미확인) |
| `SYS_REPLICATIONS_.REMOTE_LAST_DDL_XSN` | **없음** | 모델이 읽지 않으므로 SELECT 목록에서 뺐다 |

SELECT 목록은 모델이 읽는 열만 고른다(`SELECT *`·`s.*`를 쓰지 않음: 이름이 다른 열이 별칭 없이 오지 않게). 송신 목록은 Altibase 문장처럼 지연 뷰와 내부 조인이다.

### 보이는 것

| 곳 | GEMINI가 있는 서버 |
|---|---|
| Global metadata ▸ 이중화 객체 | GEMINI 객체, 그 아래 송신·수신·이중화 테이블(Altibase 복제와 같은 노드) |
| 복제 객체의 속성 보기 | 종류 = GEMINI(이 속성은 Altibase 복제에서 보이지 않음), 연결 유형 없음, 충돌 해결은 이름(None/Master/Slave), 시작됨·모드·역할·복구 가능은 서버의 값 그대로(아래 "코드 값") |
| 송신의 속성 보기 | "지연"(서버의 수, 단위 없음, 설명 "단위 미확인"). "Gap (Byte)"는 보이지 않음. 복제 객체의 통계 크기(지연 바이트)도 없음. 시작 옵션·상태·네트워크 오류는 서버의 값 그대로 |
| 활동 모니터 ▸ 복제 ▸ 요약 | "GEMINI 포트" = `GEMINI_PORT_NO`, 0이면 "0: 이 서버는 GEMINI 사용 안 함(GEMINI_PORT_NO)", 복제 수(시작 코드를 모르므로 "시작된 수"는 없음) |
| 활동 모니터 ▸ 복제 ▸ 복제·송신 | 시작됨·모드·역할·상태·시작 옵션·네트워크 오류 열은 서버의 값 그대로 |
| 활동 모니터 ▸ 복제 ▸ 송신 | 지연 열 "지연"(수, 툴팁 "…서버가 준 수 그대로(단위 미확인)") |
| 활동 모니터 ▸ 복제 ▸ 복제 | 표 설명(오른쪽 클릭으로 시작·중지·동기화)을 붙이지 않음 |

### 숨긴 것과 방법

- **시작·중지·동기화:** `GeminiReplicationCatalog.supportsControlStatements()` = false → `ObjectMaintenanceActions.supports`가 거짓이라 GEMINI 객체의 오른쪽 클릭 메뉴에
  세 항목이 없고, 스크립트 생성도 거부하고(`generateScript`가 `supports`를 다시 봄), 모델 도구 `AltibaseToolReplicationStart`·`Stop`은 예외를 던진다(저장된 작업 같은 다른 길).
- **새로 만들기·삭제·이름 바꾸기:** 복제에는 객체 관리자가 없어 원래 없다(Altibase도 같음). 바꾸지 않았다.
- **DDL:** `DBMS_METADATA.GET_DDL`의 객체 유형이 GEMINI에서 무엇인지 모르므로(`getDdlObjectType()` = null) 부르지 않는다. DDL 탭은 비어 있다(EXEC 문장을 보내지 않음).

### 코드 값 (2026-11-17 고침)

전에는 모든 코드를 Altibase와 같다고 보고 Altibase 이름(모드 Lazy/Eager, 역할 General/…, 송신 상태 등)을 썼다. GEMINI 매뉴얼(2026-11-17)로 확인한 것만 이름으로 보이고
나머지는 서버의 값 그대로 보이게 고쳤다(`GeminiReplicationCatalog`, 중립 능력 `AltibaseReplicationCatalog.hasFamilyStateCodes()` = false, [altibase.md](altibase.md) "복제 카탈로그").

| 열 | 문서로 확인한 뜻 | 보이는 것 |
|---|---|---|
| `CONFLICT_RESOLUTION` | 0 없음, 1 master, 2 slave | None / Master / Slave, 그 밖은 수 |
| `OPTIONS` | 1 = recovery(다른 비트는 문서에 없음) | 수 그대로(전과 같음) |
| `GEMI_MODE` | 생성 낱말 `ASYNC`(기본)·`SYNC`만 있고 코드는 없음 | 수 그대로. Altibase의 Lazy/Eager도, 매뉴얼의 ASYNC/SYNC도 쓰지 않음(코드 대응 미확인) |
| `ROLE` | 생성 절 `FOR ANALYSIS`·`FOR PROPAGABLE LOGGING`·`FOR PROPAGATION`만, 코드 없음 | 수 그대로 |
| `IS_STARTED`, `INVALID_RECOVERY` | 없음 | 수 그대로("시작됨" 속성은 서버의 값, 노드를 활성으로 그리지 않음) |
| `V$GEMSENDER.START_FLAG`, `STATUS`, `NET_ERROR_FLAG` | 없음 | 수 그대로 |

**실제 GEMINI 행을 본 적이 없다**(두 서버 모두 GEMINI 표·뷰가 비어 있고 `GEMINI_PORT_NO` = 0).

**지연 단위(미확인, 단위 없이 보임).** 매뉴얼의 단서가 서로 맞지 않는다: (1) 지연 뷰 설명은 송신이 읽는 로그 위치와 현재 로그 위치의 차이, (2) 다른 곳은 "SN 차이",
(3) `NOVA_MIN_GAP` 같은 속성은 바이트 단위. 어느 것이 `V$GEMGAP.GEM_GAP`의 단위인지 확정되지 않았다.

### 확인 (2026-11-17, 이 브랜치 빌드, 사설 작업 공간·설정·데이터 폴더, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

연결 `SS`(일반 서버 5.1.9), `SHARD`(메타 노드 5.1.9-beta9), `ALTI`(Altibase 7.3, 터널). 값은 환경 변수 참조로만, 암호 저장 끔, ShardSphere 두 연결은 읽기 전용 표시.
벤더 서버에서는 연결, 카탈로그 읽기, SELECT만 했다.

- **문장 준비:** GEMINI 카탈로그의 10개 문장(트리 목록·이름 조회·항목·송신·수신, 탭의 포트·호스트·복제·송신·수신)을 두 ShardSphere 서버에서 앱의 메타 세션으로 준비하고
  실행했다(매개변수는 없는 이름): 10/10 준비됨, 모두 0행, 포트 속성은 1행. Altibase 기본 카탈로그 11개 문장도 7.3에서 11/11.
- **손으로 만든 행(서버의 실제 GEMINI 행은 없음):** 모델 클래스와 탭 방언에 GEMINI SELECT 목록이 별칭한 열 이름으로 만든 행을 넣었다(앱 안, 실제 데이터 원본, 가짜 결과 집합·세션).
  GEMINI 행: 속성 보기에 종류 = GEMINI, 연결 유형 없음, 송신 "지연" = 42(단위 없음), "Gap (Byte)" 없음, 시작·중지·동기화 적용 안 됨, 시작 도구 거부. 같은 카탈로그에 Altibase
  모양 행(없는 두 열이 있음): 같은 속성(두 열은 읽지 않음), 지연 0(값 없음). Altibase 카탈로그에 Altibase 행: 연결 유형 보임, "Gap (Byte)" = 168K(42 × 4096),
  명령 적용, 도구가 문장을 만듦. Altibase 카탈로그에 GEMINI 모양 행(두 열 없음): 연결 유형 빈 값, 오류 없음.
  탭 방언: GEMINI 요약 "GEMINI 포트 | 0: 이 서버는 GEMINI 사용 안 함(GEMINI_PORT_NO)"(포트 21300이면 "21300"), 송신 지연 열 종류 COUNT·값 42, Altibase는 BYTES·172032.
- **앱(기능 있는 빌드):** 세 연결 모두 Global metadata ▸ 이중화 객체 = 0개, 오류 없음(처음, 연결 새로 고침 뒤, 다시 연결 뒤, 모두 끊고 ALTI → SHARD → SS 순서로
  다시 연결한 뒤). 연결마다 카탈로그: SS·SHARD는 GEMINI, ALTI는 Altibase(각 데이터 원본 인스턴스에서 읽음, 새로 고침·다시 연결 뒤에도 같음). 복제 탭: SS·SHARD
  요약 "GEMINI 포트 | 0: 이 서버는 GEMINI 사용 안 함(GEMINI_PORT_NO)", "복제 | 구성되지 않음", "읽을 수 없음" 없음, 송신·수신 표는 빈 표 문구. ALTI는 "복제 포트 |
  0: 이 서버는 복제가 꺼져 있음(REPLICATION_PORT_NO)", "복제 | 구성되지 않음"(전과 같음). 쿼리 관리자: ShardSphere 두 연결이 받은 문장은 모두 SELECT
  (SS 41개, SHARD 39개, 오류 0), 복제 문장은 GEMINI 이름뿐이고 Altibase 복제 이름은 하나도 가지 않았다. ALTI는 Altibase 이름 그대로.
- **기능 없는 빌드(feature 포함 줄만 로컬에서 뺌, 커밋하지 않음):** 제품에 이 번들이 없다. SS·SHARD의 카탈로그는 Altibase, 폴더 0개·오류 없음(새로 고침 뒤에도),
  복제 탭은 전과 같이 "읽을 수 없음: Table or view was not found … SYS_REPL_HOSTS_"와 "복제 포트 | 0: 이 서버는 복제가 꺼져 있음". ALTI는 기능 있는 빌드와 같은 글자.
- 캡처(한국어, 밝은 테마, 150 %): `D:\Tools\_scratch\shardsphere-gemini-read\sheets\` — `before-1-ss-replication-summary.png`, `before-1b-ss-replication-replications.png`
  (기능 없는 빌드 = 이 변경 전과 같은 문장), `after-1-ss-replication-summary.png`, `after-1b-ss-replication-senders.png`, `after-2-shard-replication-summary.png`,
  `before-3-alti-replication-summary.png`, `after-3-alti-replication-summary.png`. "전"은 origin 기준 빌드가 아니라 이 브랜치에서 feature만 뺀 빌드다(Altibase 카탈로그의
  문장이 기준과 글자 하나까지 같음을 따로 확인).
- 회귀 테스트는 작성·실행하지 않았다. 영향받을 수 있는 기능: Altibase·ShardSphere의 Replications 폴더와 복제 노드(송신·수신·항목·DDL·통계), 복제 시작·중지·동기화 메뉴,
  활동 모니터 복제 탭.

### 미확인 (벤더 문의)

- 확인됨(GEMINI 매뉴얼 2026-11-17): `CONFLICT_RESOLUTION` 0/1/2, `OPTIONS` 1 = recovery, 제어 문장 `ALTER GEMINI …`(SYS만).
- 미확인: `IS_STARTED`, `GEMI_MODE`, `ROLE`, `INVALID_RECOVERY`, `V$GEMSENDER.START_FLAG`·`STATUS`·`NET_ERROR_FLAG`의 코드.
- 미확인: `V$GEMGAP.GEM_GAP`의 단위(위 세 단서가 다름). 단위 속성이 없다. 다른 후보는 `V$GEMSENDER_LOG.GAP_SIZE`, `V$GEMRECEIVER_GAP.SENDER_GAP`(읽지 않음).
- 미확인: 제어 문장의 실제 동작(매뉴얼 두 권이 `CREATE GEMINI`의 OPTIONS 자리·`SET HOST`에서 다름), `DBMS_METADATA.GET_DDL`의 객체 유형(문서에 없음).
- 미확인: `CONN_TYPE`이 없는 이유(GEMINI는 연결 유형이 하나뿐인지).
- 실제 GEMINI 행은 한 번도 보지 못했다: GEMINI가 설정된 서버가 있어야 값 코드, 지연 단위, 송신·수신 행의 모양을 확인한다.

## 시스피어 답변과 새 매뉴얼 (2026-11-17, 앱 변경 #139 수정)

2026-11-17 10:37 시스피어가 문의에 답하고 SQL·스트림·GEMINI 매뉴얼과 작업 예제를 보냈다(사본은 저장소 밖, 저작권 고지가 있어 본문을 옮기지 않음). 이 문서에 반영한 답:

| 문의 | 답 | 반영 |
|---|---|---|
| `NODE[all]` | `node[all]`에서 DML은 동작하지 않고 DML은 `node[data]`로만. 전체를 보려면 `node[meta] select … union all node[data] select …` | `NODE[` 뒤 자동 완성에서 `all`을 뺌(위 "자동 완성"), 접두어 인식은 그대로 |
| 샤딩 코드 | 종류 T 테이블 / P 프로시저, 분산 방식 H 해시 / R 범위 / L 리스트 / C 복제 / S 독립 | 코드 표의 "미확인"을 지움(위 "코드 값") |
| TTL | 서버 속성 `EXPIRED_DATA_DELETION_ENABLE = 1`(설정 파일, 재기동)로 켬, `CREATE TABLE … USING TTL n 단위 ON COLUMN c`, ALTER로도 | 테이블 속성의 TTL(아래), 문법 낱말 `TTL` |
| GEMINI | 매뉴얼 | 코드 이름은 문서에 있는 것만, 제어 문장은 문서화됐으나 숨김 유지(위 "GEMINI") |
| JDBC | 반드시 `shardsphere.jar`(관리는 메타 노드에서) | 바꾼 것 없음(이미 그 드라이버) |
| 쓰기 가능한 서버 | "현재 서버에서 User 생성 등 작업이 가능" | 구현 작업은 벤더 서버에서 조회·준비만 했다. 그 뒤 사용자 허락(2026-11-17)으로 일반 서버에 시험용 개체 `CHR_COR_*`만 만들어 TTL 행과 큐 시퀀스를 확인하고 지웠다(아래 "테이블 속성: TTL과 샤드") |

매뉴얼로 알게 된 것 중 큐의 내부 시퀀스와 작업은 계열 공통이라 [altibase.md](altibase.md) "큐의 내부 시퀀스", "작업"에 있다.

## 테이블 속성: TTL과 샤드 (2026-11-17, 앱 변경 #139 수정 6)

### 보이는 것

일반 트리(스키마 ▸ 테이블)의 테이블을 고르면 속성 보기의 자기 속성 뒤에 묶음이 붙는다(기존 흐름, 새 화면 없음):

| 묶음 | 속성 | 언제 | 읽는 곳 |
|---|---|---|---|
| TTL | TTL 기준 컬럼, TTL(예: "7일") | 서버에 `SYSTEM_.SYS_TTL_`가 있고(감지) 그 테이블의 행이 있을 때 | `SYS_TTL_` ⋈ `SYS_TABLES_`·`SYS_USERS_`(소유자·이름) ⟕ `SYS_COLUMNS_`(`TABLE_ID`·`COLUMN_ID`) |
| 샤딩 | 분산 방식, 샤드 키(서브 분산이면 하위 분산 방식, 하위 샤드 키) | 샤딩 메타 노드이고 `SYS_SHARD.OBJECTS_`에 그 테이블(`OBJECT_TYPE = 'T'`, 소유자·이름)이 있을 때 | `OBJECTS_` |

- **TTL 단위 코드:** Y 년, Q 분기, O 월, W 주, D 일, H 시간, M 분, S 초(스트림 매뉴얼의 `SYS_TTL_` 설명). 모르는 코드는 수와 코드 그대로("9 X"). 기준 컬럼을
  찾지 못하면 컬럼 번호를 보인다. 분산 방식 이름은 샤딩 탐색기와 같다(`ShardObject.splitMethodLabel`).
- **실제 TTL 행(2026-11-17, 일반 서버 5.1.9, 시험용 표 `CHR_COR_*`를 만들어 읽고 지움, 앱 밖 JDBC):** 두 서버 모두 원래 `SYS_TTL_` 0행이고
  `EXPIRED_DATA_DELETION_ENABLE` = 0이다(만료 삭제는 돌지 않음).
  - `CREATE TABLE … USING TTL 7 DAY ON COLUMN c` → `SYS_TTL_`에 UNIT `D`, TIME 7. `5 MINUTE` → `M`, `2 MONTH` → `O`. `ALTER TABLE … USING TTL 3 HOUR ON COLUMN c`는
    있는 TTL을 바꾸고(`H`, 3) TTL이 없는 표에도 건다(`1 YEAR` → `Y`). 단위 낱말 `MIN`은 문법 오류(200705)이고 `MINUTE`가 맞다(메일의 `MIN`과 다름).
  - `SYS_TTL_.COLUMN_ID`는 `SYS_COLUMNS_.COLUMN_ID`와 같은 번호다: 이 번들의 문장(`ShardTableProperties.QUERY_TTL`)이 실제 행에서 기준 컬럼 이름과 수·단위를 돌려준다.
  - `DBMS_METADATA.GET_DDL`의 표 DDL에 `USING TTL 7 DAY ON COLUMN c`가 들어 있다(DDL 보기에 TTL이 보임). `SYS_TTL_FORMAT_`에는 행이 생기지 않았다.
  - 끝에 모두 지움: `SYS_TABLES_`에 `CHR_COR_%` 0행, `SYS_TTL_` 0행.
  - **앱 화면에서 실제 TTL 행을 본 것은 아니다:** 문장은 실제 행으로(앱 밖), 표시는 손으로 만든 행(이 번들의 행 읽기·형식, 가짜 결과 집합)으로 확인했다. 주(W)·분기(Q)·초(S)
    코드는 만들어 보지 않았다(매뉴얼의 코드).
- **샤드 테이블 확인(메타 노드, 실제 행):** 샤드 테이블 둘 모두 분산 방식 "해시"와 샤드 키가 보이고, 같은 메타 노드의 다른 테이블(`SYS_SHARD` 스키마의 표)에는 묶음이 없다.

### 자리와 비용

- 중립 자리는 `ext.altibase`의 `AltibaseServerExtensionState.getObjectProperties`와 모델의 `DBPObjectWithContributedProperties`, 속성 보기
  (`PropertiesPanel`)의 작은 변경이다([altibase.md](altibase.md) "기존 객체의 추가 속성"). 이 번들은 `ShardSphereServer.getObjectProperties`에서 테이블일 때만
  `ShardTableProperties.read`를 부른다.
- **연결마다:** 감지 SELECT에 부분 질의 하나(`SYS_TTL_`가 있는지)를 더했다(왕복은 그대로 하나).
- **고를 때마다:** 속성 보기가 테이블을 보일 때만, 일반 서버는 TTL SELECT 하나, 메타 노드는 TTL과 샤드 SELECT 둘(같은 메타데이터 세션). 다른 객체·목록 열·트리를
  읽을 때는 질의가 없다. Altibase 7.3에는 확장이 없어 질의가 하나도 늘지 않는다(쿼리 관리자로 확인: 테이블 선택 때 전과 같은 두 문장).
  속성 보기가 같은 노드를 다시 그리면(연결 새로 고침 뒤 등) 다시 읽는다(기존 속성의 크기 질의와 같음).

## TTL 관리 (2026-11-17, 앱 변경 #152)

### 목적

ShardSphere 연결에서 기존 테이블의 TTL을 Object Explorer에서 설정·변경·해제한다. 읽기(위 "테이블 속성: TTL과 샤드")는 전부터 있었다. 화면은 기존 흐름 안에서
짧게(사용자 규칙: 새 화면도 기존 흐름, 대화상자에 설명 문구 없음).

### 문장 (시스피어 답변 2026-11-17 15:28, 같은 날 일반 서버에서 확인)

| 할 일 | 문장 | 서버의 답 |
|---|---|---|
| 설정·변경 | `ALTER TABLE t USING TTL n 단위 ON COLUMN c` | TTL이 없는 표에 걸고, 있는 TTL의 수·단위·기준 컬럼을 바꾼다(`SYS_TTL_` 행 하나) |
| 해제 | `ALTER TABLE t USING NOTTL` | `SYS_TTL_` 행과 DDL의 TTL 절이 없어진다. TTL이 없는 표에서도 오류 없음 |

- **단위:** 시스피어가 이름을 든 다섯만 고르게 한다: 분 `MINUTE`(M), 시간 `HOUR`(H), 일 `DAY`(D), 월 `MONTH`(O), 년 `YEAR`(Y). 메일의 `MIN`은 서버의 구문 오류라
  `MINUTE`를 쓴다. 서버가 받는 `WEEK`·`QUARTER`, 삭제 주기보다 짧으면 거절되는 `SECOND`는 고르게 하지 않는다(다른 길로 걸린 TTL이면 지금 값으로만 보인다).
  TTL이 없는 표의 처음 단위는 일이다(시스피어의 예, 분으로 잘못 고르면 행이 너무 일찍 만료됨).
- **수:** `SYS_TTL_.TIME`은 INTEGER다. 1 ~ 2147483647을 받고(`2147483647 YEAR`도 받음) 2147483648은 201846, 0은 구문 오류다. 대화상자는 숫자만 받는 칸에 1 이상의 정수(int 범위)를 요구한다.
- **이름:** 방언 규칙대로 따옴표를 붙인다(`소유자.테이블`, 컬럼). `"CHR_TTL_q"`·`"d 1"` 같은 따옴표 이름도 서버가 받는다. 다만 서버의 `DBMS_METADATA.GET_DDL`은 기준 컬럼 이름을
  따옴표 없이 쓴다(`… ON COLUMN d 1`, 그대로는 다시 실행할 수 없는 DDL: 벤더 문의).
- **해당하는 표:** 메모리 테이블, 디스크 테이블(`TABLESPACE DISK_DATA`), 분할 테이블(디스크·메모리)에서 설정·해제가 된다(시험용 표로 확인). 그래서 일반 테이블
  모두에 명령을 낸다. 큐·뷰·구체화된 뷰는 다른 모델 클래스라 명령이 없다(큐는 이 작업의 쓰기 범위 밖이라 서버에서 시험하지 않음). 시스템 스키마(`SYSTEM_`)의 표에는
  내지 않는다(남의 객체라 서버에서 시험하지 않음, 안전 쪽).
- **트랜잭션:** DDL이라 바로 커밋된다(미리 보기 머리글의 "DDL 문을 바로 커밋" 줄, JDBC `dataDefinitionCausesTransactionCommit`).

### 화면

| 곳 | 무엇 |
|---|---|
| Object Explorer ▸ 테이블 오른쪽 클릭 | "테이블 잘라내기" 바로 아래 "TTL 설정"(영어 "TTL…"). 하나를 고를 때만, TTL 사전(`SYS_TTL_`)을 감지한 연결의 일반 테이블에만 보인다. Altibase, 다른 DB, 기능을 뺀 제품에는 항목이 없다(숨김, 끄지 않음) |
| 대화상자 "TTL 설정" | 확인란 "TTL 사용", 기준 컬럼(날짜·시간 컬럼만), 기간(숫자만), 단위(분·시간·일·개월·년). 지금 TTL로 채워짐. 확인/취소, 오류는 단추 줄 한 줄("1 이상의 정수를 입력하십시오.", "날짜 컬럼을 고르십시오.") |
| 그 뒤 | 다른 유지 관리 명령과 같다: 스크립트 미리 보기(취소가 기본 단추) → 열린 트랜잭션 질문 → 격리된 실행 문맥에서 실행 → 테이블 노드 다시 읽기(속성의 TTL 묶음과 DDL이 새 값) |

- **해제:** "TTL 사용"을 끄면 칸이 꺼지고 `USING NOTTL`이 된다(자동 확장 설정의 확인란과 같은 모양을 골랐다. 대안: "TTL 해제" 단추).
- **날짜 컬럼이 없는 표:** 명령은 보이고(메뉴는 메모리만 보며 컬럼 목록은 아직 읽지 않았을 수 있음), 대화상자의 "TTL 사용"과 칸이 모두 꺼져 있어 확인은 "실행할 문장 없음"
  알림이다(고른 것. 대안: 컬럼을 미리 읽어 명령을 숨김).
- **바뀐 것 없음:** 확인을 누르면 다른 설정 대화상자처럼 "실행할 문장 없음" 알림, 문장 없음. 취소(대화상자·미리 보기)는 아무것도 보내지 않는다.
- **오류:** 서버의 메시지가 기존 오류 창으로 보인다(문장, 서버 메시지, 코드, URL 없음). 그 뒤 명령은 다시 쓸 수 있다(대화상자는 실행 전에 닫히는 기존 흐름).
- 문구: 공용 낱말(메뉴, 제목, 칸 이름, 오류)은 `ui.swing`의 `ObjectMaintenanceMessages`(영어·한국어), 단위 이름은 이 번들의 `ShardSphereMessages`(`ttl_unit_name_*`).

### 자리(seam)

- `ext.altibase`의 중립 능력 `extension/AltibaseTableTtl`([altibase.md](altibase.md) "테이블 TTL 자리"). 이 번들의 `model/ShardTableTtl`이 구현하고
  `ShardSphereServer.getAdapter`가 `SYS_TTL_`를 감지한 연결에서만 준다. 문장·단위·해당 판단·지금 값 읽기는 모두 이 번들에 있다(모델). Swing은 이 번들에 기대지 않는다.
- `model.admin`의 `ObjectMaintenanceActions.Action.TABLE_TTL`(설정이 필요한 명령), `ui.swing`의 `ObjectMaintenanceCommands`(메뉴·흐름)와 `AltibaseTableTtlDialog`(대화상자).
- **샤딩 메타 노드:** 샤드 테이블(`SYS_SHARD.OBJECTS_`의 테이블)에는 명령을 내지 않는다. 샤드 테이블의 TTL은 클러스터 전체에 걸리므로 확인하기 전까지 숨긴다(안전 쪽).
  목록은 감지 때 한 번 읽는다(`SELECT USER_NAME, OBJECT_NAME FROM SYS_SHARD.OBJECTS_ WHERE OBJECT_TYPE = 'T'`, 메타 노드이고 TTL 사전이 있을 때만). 읽지 못하면 그 연결의 어느
  표에도 내지 않는다. 연결 중에 쿼리 창에서 표를 샤딩하면 연결을 새로 고칠 때까지 그 표에 명령이 보인다(남은 것). 메타 노드의 샤드가 아닌 표에는 보이지만 이 작업에서는 실행하지 않았다.

### 확인 (2026-11-17, 이 브랜치 빌드, 사설 작업 공간·설정·데이터 폴더, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

연결 `SS`(일반 서버, 자동 커밋), `SSTX`(같은 서버, 수동 커밋), `SHARD`(메타 노드, 읽기 전용 표시), `ALTI`(Altibase 7.3). 값은 환경 변수 참조, 암호 저장 끔.
시험용 표는 로그인 스키마의 `CHR_TTL_*`만 만들고 끝에 지웠다. `SYS_TTL_` 행은 앱 밖의 다른 연결로 SELECT해 확인했다.

| 단계 | 대화상자 | 문장(쿼리 관리자, UTIL) | `SYS_TTL_` | 속성 TTL 묶음 / DDL 보기 |
|---|---|---|---|---|
| TTL 없음 → 설정 | 확인란 꺼짐, 칸 꺼짐 | `ALTER TABLE <소유자>.CHR_TTL_T USING TTL 7 DAY ON COLUMN D1` | D1, D, 7 | D1, 7일 / `USING TTL 7 DAY ON COLUMN D1` |
| 수·단위 | 7·일 채워짐 | `… USING TTL 3 MONTH ON COLUMN D1` | D1, O, 3 | 3개월 |
| 기준 컬럼·단위 | | `… USING TTL 1 YEAR ON COLUMN D2` | D2, Y, 1 | D2, 1년 / DDL 같음 |
| 단위 | | `… USING TTL 12 HOUR ON COLUMN D2`, `… 30 MINUTE …` | H 12, M 30 | 12시간, 30분 |
| 해제 | 확인란 끔 | `ALTER TABLE <소유자>.CHR_TTL_T USING NOTTL` | 행 없음 | 묶음 없음 / DDL에 TTL 절 없음 |

- 바뀐 것 없이 확인 → "실행할 문장 없음" 알림, 대화상자 취소·미리 보기 취소 → 쿼리 관리자에 ALTER 없음, `SYS_TTL_` 그대로.
- 기간 칸: `12abc` 입력은 들어가지 않음, 빈 칸·`0` → "1 이상의 정수를 입력하십시오." 줄, 대화상자는 열린 채.
- 날짜 컬럼 없는 표(`CHR_TTL_N`): 확인란·칸 꺼짐, 컬럼 목록 빈 것, 확인 → "실행할 문장 없음".
- 앱 뒤에서 지운 표: 서버 메시지 "Table or view was not found"(200753) 오류 창, 그 뒤 다른 표에서 명령 정상.
- **트랜잭션(SSTX, 쿼리 창의 커밋하지 않은 INSERT):** TTL 실행에서 "커밋하지 않은 트랜잭션" 질문(커밋 / 롤백 / 취소, 취소가 기본). 취소 → ALTER 없음, 다른 세션에서 행 0, TTL
  없음. 롤백 → INSERT가 사라지고(다음 질문에 그 창이 없음, 다른 세션 행 수에 없음) TTL 2일. 커밋 → 다른 세션에서 행이 보이고 TTL 6개월. 자동 커밋 연결(SS)의 INSERT 뒤에는
  질문 없이 실행. ALTER는 늘 격리된 실행 문맥(UTIL)에서 실행되어 쿼리 창의 세션을 건드리지 않는다.
- **메타 노드(SHARD):** 샤드 테이블의 메뉴에 TTL 없음(17항목), 샤드가 아닌 표의 메뉴에 TTL 있음(18항목). 실행하지 않았다. SHARD로 간 실행은 모두 SELECT.
- **Altibase 7.3:** 표 메뉴에 TTL 없음, 능력 없음, 질의 늘지 않음. 메뉴 덤프 전후(기준 클래스로 되돌린 jar) 같음.
- **기능을 뺀 제품**(내 앱 사본에서 이 번들을 빼고 `bundles.info`에서 그 줄을 지움): SS 표의 메뉴에 TTL 없음.
- 실행 뒤 표 노드를 다시 읽을 때 쿼리 관리자에 TTL 읽기 SELECT가 한 번 "오류 0"으로 남는 때가 있다(속성 보기가 노드 갱신으로 앞 읽기를 취소하고 다시 읽음, 바로 뒤
  같은 SELECT는 성공). 속성 보기의 기존 동작이라 고치지 않았다.
- 캡처(저장소 밖): `D:\Tools\_scratch\shardsphere-ttl-manage\sheets\`.
- 회귀 테스트는 작성·실행하지 않았다. 영향받을 수 있는 기능: Object Explorer 유지 관리 메뉴(항목 순서), Altibase 계열 연결의 어댑터, ShardSphere 감지(메타 노드의 SELECT 하나).

### 남은 것 (이 작업 밖)

- 테이블 디자이너·CREATE TABLE의 TTL 절, TTL 컬럼의 인덱스 권장, 서버 속성 `EXPIRED_DATA_DELETION_ENABLE` 켜기(설정 파일·재기동), 연속 테이블의 TTL, 시스피어가 이름을 들지
  않은 단위(`WEEK`, `QUARTER`, `SECOND`).
- 샤드 테이블의 TTL(메타 노드에서 숨김, 확인 전), 연결 중 샤딩한 표의 메뉴(새로 고침까지).

### 미확인 (벤더 문의)

- 미확인: 샤드 테이블에 `ALTER TABLE … USING TTL`을 메타 노드에서 실행하면 데이터 노드 모두에 걸리는지, 노드마다 따로인지.
- 미확인: INTEGER·VARCHAR 컬럼을 TTL 컬럼으로 받는 뜻(시간 값으로 읽는지, 형식은 `SYS_TTL_FORMAT_`인지).
- 미확인: `DBMS_METADATA.GET_DDL`이 기준 컬럼 이름을 따옴표 없이 쓰는 것(`ON COLUMN d 1`)이 의도인지.
- 미확인: 큐·시스템 표에 TTL을 받는지.

## 낱말과 함수 (2026-11-17, 앱 변경 #139 수정 8)

두 ShardSphere 서버와 Altibase 7.3에서 `SELECT f(...) FROM DUAL`을 준비해 정했다(실행하지 않음, 2026-11-17):

| 함수 | Altibase 7.3 | ShardSphere 5.1.9 · 메타 노드 | 넣은 곳 |
|---|---|---|---|
| `DATEPART`, `INSTRB`, `LENGTH`, `LENGTHB`, `POSITION`, `SUBSTR`, `REGEXP_LIKE` | 준비됨 | 준비됨 | Altibase 방언의 함수 목록(계열 공통) |
| `FROM_UNIXTIME`, `PVTIME_TO_DATE`, `EXPORT_CONTINUOUS_OBJECT` | 함수 없음(201001) | 준비됨 | 이 번들의 방언 기여(감지된 모든 ShardSphere 연결) |
| `SHARD_ID()`(SQL 매뉴얼에 이름만), `PVDATE_TO_TIME` | 없음 | 없음(인자 0·1개, 글자 인자 모두 201001, 없는 함수와 같은 코드) | 넣지 않음 |

- `LENGTH`, `POSITION`은 엔진에서 이미 키워드 종류라(예약어 목록) 강조 색은 키워드다. 함수 목록에도 들어가 자동 완성에 함수로 나온다.
- **문법 낱말:** `TTL`만 더했다(서버에 `SYS_TTL_`가 있을 때, 예약어가 아니면 따옴표 이유가 아님). 이 문서가 다루는 문장(`… USING TTL n 단위 ON COLUMN c`) 때문이다.
  `GEMINI`는 서버가 이미 예약어로 알려 준다. GEMINI 제어 문장의 낱말(`QUICKSTART`, `RETRY`, `GAPLESS` …), 스트림·작업·큐 낱말은 Chromite가 그 문장을 아직
  다루지 않아 넣지 않았다.

## 서버에서 읽은 사실 (2026-11-17, 조회·준비만)

두 서버(일반 5.1.9, 메타 노드 5.1.9-beta9)에서 같은 값:

| 항목 | 값 |
|---|---|
| `SHARD_ID()` | 없음(준비 201001) |
| `V$PROPERTY` `EXPIRED_DATA_DELETION_ENABLE` | 0 |
| `EXPIRED_DATA_DELETION_INTERVAL` | 60 |
| `EXPIRED_DATA_DELETION_TYPE`(이름에 EXPIRED가 든 다른 속성) | 0 |
| `JOB_SCHEDULER_ENABLE`, `JOB_THREAD_COUNT` | `V$PROPERTY`에 없음(이름에 JOB이 든 속성 0개). Altibase 7.3에도 없다(JOB_MSGLOG_* 넷만) |
| `CONTINUOUS_QUERY_ENABLE` | 1 (`CONTINUOUS_QUERY_INTERVAL` 100000, `CONTINUOUS_QUERY_THREAD_COUNT` 0) |
| `SYS_TABLES_.TABLE_TYPE` | 일반: S 15, T 108, V 41 / 메타 노드: S 18, T 102, V 41 (Q·M·I 없음). `STREAM` 열은 모두 N |
| `SYS_CONTINUOUS_TABLES_` | 0행. 열: USER_ID, TABLE_ID, AGGR_TABLE_ID, VIEW_ID, EXEC_QUERY1~4, WINDOW_UNIT, WINDOW_TIME, WINDOW_STEP_UNIT, WINDOW_STEP_TIME, EXEC_GROUP, EXEC_ORDER, IS_ENABLE, OPTION_OFFSET, OPTION_SIZE |
| `SYS_STREAM_FUNCTIONS_` | 0행. 열: FUNC_NAME, FUNC_TYPE, ARG1~4, CREATED |
| `SYS_TTL_FORMAT_` | 0행. 열: USER_ID, TABLE_ID, COLUMN_ID, FORMAT |
| `SYS_TTL_` | 0행. 열: USER_ID, TABLE_ID, COLUMN_ID, UNIT, TIME |
| `SYS_AUDIT_OPTS_` | 0행. 열: USER_NAME, OBJECT_NAME, OBJECT_TYPE과 연산마다 하나(SELECT·INSERT·UPDATE·DELETE·MOVE·MERGE·ENQUEUE·DEQUEUE·LOCK_TABLE·EXECUTE·COMMIT·ROLLBACK·SAVEPOINT·CONNECT·DISCONNECT·ALTER_SESSION·ALTER_SYSTEM·DDL `_OP`). Altibase 7.3에도 같은 열·0행 |
| `V$STREAM_FUNCTION` | 0행(열: FUNC_NAME, FUNC_TYPE, ALLOC_SIZE, GROUP_COUNT, CREATE_TIME, TOUCH_TIME, TOUCH_COUNT) |
| `V$STREAM_TIMESTAMP` | 0행(열: TIMESTAMP) |
| `SYS_JOBS_` | 0행. 열에 Altibase 7.3에 없는 JOB_USER_ID, JOB_TYPE, FAIL_COUNT가 있다 |

스트림 탐색기·감사 보기는 행이 있는 서버(또는 쓰기 시험)가 있어야 화면을 확인할 수 있다: 두 서버 모두 스트림·연속·감사 사전이 비어 있다.

## 확인 (2026-11-17, 수정 1~8, 이 브랜치 빌드, 사설 작업 공간·설정·데이터 폴더, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

연결 `SS`(일반), `SHARD`(메타 노드), `ALTI`(Altibase 7.3, 터널). 값은 환경 변수 참조로만, 암호 저장 끔, ShardSphere 두 연결은 읽기 전용 표시. 벤더 서버에서는 연결,
카탈로그 읽기, 준비, SELECT만 했다. "전"은 이 브랜치 빌드에 기준(`a3f6a0df5d`) 소스의 바뀐 클래스·자원을 다시 넣은 jar 두 개(`ext.altibase`, `ext.shardsphere`)다.

- **자동 완성:** 전 `NODE[` → META, DATA, ALL / 후 META, DATA(소문자 `node[`는 meta, data). Altibase는 제안 없음(전과 같음). 손으로 친 `NODE[all] DELETE …`는
  여전히 DELETE로 분류되어 위험한 문 확인 대화상자가 뜬다(취소함, 실행하지 않음).
- **테이블 속성:** 위 "테이블 속성: TTL과 샤드". 전에는 샤드 테이블에 샤딩 묶음이 없었다.
- **GEMINI:** 손으로 만든 행: 속성 보기 "시작됨" = 1(서버 값), 송신 시작 옵션 0·상태 1·네트워크 오류 0(서버 값). 복제 탭 요약 "복제 1개"(시작 수 없음), 복제·송신 표의 코드
  열은 수. Altibase 카탈로그는 전과 같은 이름(Started true, Normal, Run, "복제 1개, 시작됨 1개"). GEMINI 목록 문장을 두 서버에서 실행(0행).
- **함수·낱말:** 연결의 방언에서 `TTL`=키워드(SS·SHARD), `PVTIME_TO_DATE`·`EXPORT_CONTINUOUS_OBJECT`·`FROM_UNIXTIME`=함수(SS·SHARD, ALTI 없음), 계열 일곱 개는 세 연결 모두 함수
  (`LENGTH`·`POSITION`은 키워드 종류), `SHARD_ID`·`PVDATE_TO_TIME` 없음.
- **세 연결 함께, 새로 고침 뒤, 모두 끊고 SHARD → ALTI → SS 순서로 다시 연결한 뒤:** 각 연결이 자기 것만 보인다(ALTI: 샤딩·TTL 묶음 없음·추가 질의 없음, 자동 완성 없음).
- **기능 없는 제품**(내 앱 사본에서 이 번들과 feature를 빼고 `bundles.info`에서 그 줄을 지움): SS·SHARD에 감지 문장 없음, 자동 완성 없음, 테이블 속성에 묶음 없음, 복제 카탈로그는
  Altibase. 작업 폴더는 계열 공통 코드라 이 번들 없이도 ShardSphere의 소유자·종류·실패 횟수를 읽는다([altibase.md](altibase.md) "작업").
- **보낸 문장(쿼리 관리자):** ShardSphere 두 연결이 받은 실행은 모두 SELECT(실행마다 감사: SS 10 + 10 + 7, SHARD 61 + 21 + 19, SELECT 아닌 것 0). 앱 밖 JDBC 확인도 SELECT와
  준비만(읽기 전용 연결).
- 캡처(저장소 밖): `D:\Tools\_scratch\shardsphere-corrections\sheets\`.
- 회귀 테스트는 작성·실행하지 않았다. 영향받을 수 있는 기능: 모든 DB의 속성 보기(기여 인터페이스를 구현한 객체만 바뀜), Altibase 계열의 작업·시퀀스 목록, 복제 노드·복제 탭,
  ShardSphere 자동 완성.

## 아직 없는 것

관리 보고서(작업 8),
쓰기 기능(작업 11, 샤딩의 노드 관리·분산·UNSHARD·재배치 포함, 테이블 TTL은 위 "TTL 관리"), 샤딩 JDBC 드라이버(작업 12). 계획: `D:\Tools\_briefs\shardsphere-plugin-plan.md`(저장소 밖).

## 미확인 (벤더 문의)

- 미확인: `DBMS_SHARD` 패키지·`SYS_SHARD` 사용자가 ShardSphere에만 있는지. Altibase의 샤딩 기능이 있는 판(시험 서버 7.3에는 없음)에도 있다면 그 서버에서
  "ShardSphere" 노드가 GEMINI 아니오로 보인다.
- 미확인: 메타 노드의 판정(`SYS_SHARD` 사용자 + `SHARD_META_ENABLE` = 1)이 데이터 노드와 메타 노드를 늘 가르는지. 데이터 노드에도 `SYS_SHARD`가 만들어지는지.
- 미확인: `SYS_GEMINIS_`가 GEMINI를 쓰지 않는 ShardSphere 설치에도 늘 있는지(두 서버 모두 있음, 둘 다 GEMINI 비어 있음).
- 확인됨(2026-11-17, 시험용 표): `SYS_TTL_.COLUMN_ID` = `SYS_COLUMNS_.COLUMN_ID`, 분은 `MINUTE`로 쓰고 `M`으로 저장, TTL은 `ALTER TABLE … USING TTL …`로 걸고 바꾼다.
  미확인: `SYS_TTL_FORMAT_`의 용도. 확인됨(시스피어 답변 2026-11-17 15:28): TTL을 없애는 문법 `ALTER TABLE t USING NOTTL`(위 "TTL 관리").
- 미확인(2026-11-17): 스케줄러 속성 `JOB_SCHEDULER_ENABLE`·`JOB_THREAD_COUNT`가 `V$PROPERTY`에 보이지 않는 이유(숨김 속성인지), 그래서 Chromite가 스케줄러가 켜져 있는지 알 길.
- 미확인(2026-11-17): `SYS_JOBS_.STATE` 코드(작업 예제의 켜진 작업은 0), `JOB_TYPE`의 P 외 코드.
- 확인됨(2026-11-17, 시험용 큐 `CHR_COR_Q`, `CREATE QUEUE q (40)`): 큐의 내부 시퀀스 `<큐>_NEXT_MSG_ID`의 `TABLE_TYPE`은 ShardSphere 5.1.9에서도 `W`이고 `V$SEQ`에 있다(Altibase 7.3과 같음). 앱 화면에서는 Altibase 7.3으로만 확인했다.
- 미확인(2026-11-17): `SHARD_ID()`가 SQL 매뉴얼에 이름만 있고 두 서버에 없는 이유.

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
- 2026-11-17 접두어 SELECT의 행 수·필터·정렬·값 목록·내보내기를 방언의 하위 질의 형태(`NODE[x](…)`, `SHARD (…)`, 메타 노드에서만)로, 막힌 다시 읽기는 상태 줄에 이유,
  접두어 안과 뒤의 자동 완성, 실행 계획 확인(앱 변경 #139 작업 6b, `chromite/shardsphere-prefix-requery`), 위 "접두어 문장 다시 읽기".
- 2026-11-17 샤딩 메타 노드의 읽기 전용 샤딩 탐색기: ShardSphere 노드 아래 데이터 노드·샤드 객체(분산)·샤딩 속성·노드 연결 폴더, 사전 버전 속성,
  노드별 행 수는 기존 "비용이 큰 개체 속성 읽기" 설정으로만, 모두 SELECT(앱 변경 #139 작업 9, `chromite/shardsphere-sharding-explorer`), 위 "샤딩 탐색기".
- 2026-11-17 GEMINI 읽기: GEMINI 카탈로그를 감지한 연결에 GEMINI 복제 카탈로그를 줌 — Replications 폴더와 활동 모니터 복제 탭이 GEMINI를 읽음, 속성 보기에 종류 GEMINI,
  요약에 GEMINI 포트, 지연은 단위 없이, 시작·중지·동기화와 DDL 호출은 내지 않음(앱 변경 #139 작업 4·5, `chromite/shardsphere-gemini-read`), 위 "GEMINI".
- 2026-11-17 계열 공통 공간 탭(작업 7)을 Altibase 공용 코드에 둠(이 번들 없이도 동작): [altibase.md](altibase.md) "공간 사용량", "아직 없는 것"에서 뺌.
- 2026-11-17 시스피어 답변과 새 매뉴얼의 수정(앱 변경 #139 수정, `chromite/shardsphere-corrections`): `NODE[` 뒤 `all` 제안 뺌, 샤딩 코드 확인됨, GEMINI 코드는 문서에 있는
  것만 이름으로·제어 문장은 문서화됐지만 숨김·지연 단서 셋, 테이블 속성의 TTL과 샤드(기여 자리 S8), 함수와 `TTL` 낱말, 두 서버의 사실 목록. 위 "시스피어 답변과 새 매뉴얼"부터
  "확인 (2026-11-17, 수정 1~8 …)"까지.
- 2026-11-17 기존 테이블의 TTL 설정·변경·해제(앱 변경 #152, `chromite/shardsphere-ttl-manage`): 표 오른쪽 클릭 "TTL 설정"과 대화상자, 문장은 이 번들(`ShardTableTtl`),
  중립 능력 `AltibaseTableTtl`, 메타 노드의 샤드 테이블에는 내지 않음(감지 때 샤드 테이블 목록 읽기). 위 "TTL 관리".
