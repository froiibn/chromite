# Altibase

Altibase 지원(`plugins/com.seizia.chromite.ext.altibase`) 중 Chromite에서 바꾼 부분을 적는다.

## 테이블 저장 방식 표시 (Object Explorer)

### 목적

Altibase 테이블은 메모리 테이블스페이스(행을 메모리에 두고 체크포인트로 디스크에 씀), 디스크 테이블스페이스,
휘발성 테이블스페이스(메모리에만 두고 서버를 다시 시작하면 없어짐) 중 한 곳에 있다. 같은 SQL이라도 저장 방식에
따라 성능과 운영 방법이 달라서, Altibase 전용 도구(참고: "Orange for Altibase")는 트리에서 메모리 테이블을 빨강,
디스크 테이블을 파랑으로 구분한다. 사용자 요청(2026-11-07)에 따라 Swing Object Explorer에서도 같은 구분을 한다.

### 화면

| 저장 방식 | 글자색 (Light / Dark) | 툴팁 (영어 / 한국어) | 속성 "Storage Type" |
|---|---|---|---|
| 메모리 | 빨강 `#B3261E` / `#FF9999` | Memory table / 메모리 테이블 | Memory / 메모리 |
| 디스크 | 파랑 `#1A4FB0` / `#94B8FF` | Disk table / 디스크 테이블 | Disk / 디스크 |
| 휘발성 | 호박색 `#8F4F00` / `#F0A858` | Volatile table / 휘발성 테이블 | Volatile / 휘발성 |
| 하이브리드(파티션마다 다름) | 바꾸지 않음 | Hybrid table / 하이브리드 테이블 | Hybrid / 하이브리드 |

- 테이블 주석이 있으면 툴팁은 `Disk table: <주석>`처럼 저장 방식 뒤에 주석을 붙인다(전에는 주석만 보였다).
- 색만으로는 구분할 수 없는 사용자를 위해 툴팁과 Properties 창(속성 "Storage Type", 한국어 "스토리지 유형")이 같은 값을 말로 보여 준다.
- 화면 문구에 다른 제품 이름은 쓰지 않는다.

### 구조

| 위치 | 내용 |
|---|---|
| `com.seizia.chromite.model` `struct/rdb/DBSTableStorageKind` | 저장 방식 enum(MEMORY, DISK, VOLATILE, HYBRID). `DBPNamedObject`라 속성 창이 지역화된 이름(`ModelMessages.model_struct_storage_*`)을 보여 준다 |
| `com.seizia.chromite.model` `struct/rdb/DBSTableWithStorageKind` | 저장 방식을 아는 테이블. 화면은 이 인터페이스만 본다 |
| `AltibaseMetaModel.prepareTableLoadStatement` | 테이블 목록을 카탈로그 질의로 읽고 테이블스페이스 종류를 같은 질의에서 받는다 |
| `AltibaseTable` | 생성자에서 테이블스페이스 이름, 파티션 여부, 저장 방식을 받아 둔다. `@Property getStorageKind()`(order 17) |
| `AltibaseTablespace.TbsType.getStorageKind()` | `V$TABLESPACES.TYPE` 코드 → 저장 방식 |
| `com.seizia.chromite.ui.swing` `TableStorageStyle` | 렌더러가 부르는 글자색·툴팁 처리. `ObjectExplorerPanel.ExplorerCellRenderer`에는 호출 한 줄만 있다 |
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | 색 토큰 `Chromite.Tree.storageMemoryForeground`, `storageDiskForeground`, `storageVolatileForeground` |
| `internal/TableStorageMessages(.properties, _ko)` | 툴팁 문구 `{0} table`, `{0} table: {1}` |
| `OSGI-INF/l10n/bundle(_ko).properties` (Altibase) | 속성 이름·설명 |

### 카탈로그 근거 (Altibase 7.3.0.0.7, 컨테이너 `chromite-test-altibase`에서 확인)

- `SYSTEM_.SYS_TABLES_`에 `TBS_ID`, `TBS_NAME`, `IS_PARTITIONED`, `TABLE_TYPE`이 있다(`DESC SYSTEM_.SYS_TABLES_`).
- `V$TABLESPACES`는 `ID`, `NAME`, `TYPE`, `STATE`를 준다. 기본 데이터베이스의 값:
  `SYS_TBS_MEM_DIC` 0, `SYS_TBS_MEM_DATA` 1, `SYS_TBS_DISK_DATA` 3, `SYS_TBS_DISK_UNDO` 7, `SYS_TBS_DISK_TEMP` 5,
  새로 만든 휘발성 테이블스페이스 `CHR_VOL_TBS` 8.
  코드 표(0–2 메모리, 3–7 디스크(3·4 데이터, 5·6 임시, 7 언두), 8 휘발성)는 기존 `AltibaseTablespace.TbsType`과 같다.
- 일반 사용자(`CHR_ALTI`)도 `V$TABLESPACES`를 읽을 수 있다(같은 질의를 그 사용자로 실행해 확인).
- 파티션드 테이블의 `SYS_TABLES_.TBS_ID`는 기본 테이블스페이스일 뿐이고 행은 파티션의 테이블스페이스에 있다.
  `SYSTEM_.SYS_TABLE_PARTITIONS_.TBS_ID`가 파티션별 값이며, 한 테이블에 메모리·디스크 파티션을 섞을 수 있다
  (`CHR_ALTI_PART`: 테이블 TBS 3, 파티션 TBS 1과 2).
- 확인한 목록 질의 결과: CHR_ALTI_MEM 1, CHR_ALTI_DISK 3, CHR_ALTI_VOL 8, CHR_ALTI_DEFAULT 1(사용자 기본 TBS),
  CHR_ALTI_PART 3(파티션 최소 1, 최대 3 → 하이브리드), CHR_ALTI_PART_DISK 3(파티션 3, 3 → 디스크), 큐 CHR_ALTI_Q 1, 뷰 0.

### 설계 결정

- **같은 질의에서 읽기.** 전에는 테이블 목록을 드라이버의 `DatabaseMetaData.getTables`로 읽었고, 테이블스페이스는
  속성을 열 때 테이블마다 따로 질의했다(`getTablespace`). 트리가 노드마다 질의하지 않도록 `prepareTableLoadStatement`를
  카탈로그 질의로 바꾸고 `TBS_NAME`, `IS_PARTITIONED`, `V$TABLESPACES.TYPE`을 함께 읽는다. 열 이름과 테이블 종류
  (`TABLE`, `SYSTEM TABLE`, `VIEW`, `SYSTEM VIEW`, `QUEUE`, `MATERIALIZED VIEW`), 주석(`SYS_COMMENTS_`)은
  Altibase JDBC 드라이버 getTables 질의와 같게 했다. 드라이버가 같이 주는 시노님·시퀀스는 `createTableImpl`이 버리므로 읽지 않는다.
  필터가 마스크 하나면 `LIKE`, 객체 하나를 다시 읽을 때는 `=`로 이름을 좁힌다(기존 GenericMetaModel 동작과 같음).
  테이블스페이스 이름과 파티션 여부도 이 결과에서 받아 두므로 목록에서 온 테이블은 `getTablespace` 질의도 하지 않는다.
- **파티션드 테이블.** 파티션 테이블스페이스 종류의 최솟값과 최댓값을 상관 하위 질의로 읽는다. 종류 코드가 저장 방식별로
  붙어 있어(메모리 0–2 < 디스크 3–7 < 휘발성 8) 두 값의 저장 방식이 같으면 모든 파티션이 그 방식이고, 다르면 하이브리드다.
  하이브리드는 한 색으로 말할 수 없으므로 글자색을 바꾸지 않고 툴팁·속성으로만 알린다.
- **휘발성 색은 호박색.** 휘발성 테이블은 메모리에 있지만 재시작하면 데이터가 없어진다. 메모리 빨강과 같은 색이면 이
  차이를 놓치고, 파랑(디스크)은 저장 위치가 틀린다. 따뜻한 계열(메모리 쪽)이면서 빨강과 구분되고 "주의"를 뜻하는
  호박색을 골랐다. 보라 같은 다른 색은 의미를 떠올리기 어렵다고 보았다.
- **선택한 줄.** 색을 선택 바탕에서도 유지한다. 그래서 토큰 값을 트리 바탕, 활성 선택 바탕, 비활성 선택 바탕 모두에서
  명암비 4.5:1 이상으로 골랐다(Light: 빨강 6.54/4.88/5.44, 파랑 7.53/5.62/6.26, 호박 6.39/4.77/5.32;
  Dark: 빨강 8.52/4.80/5.99, 파랑 8.76/4.93/6.16, 호박 8.66/4.88/6.10 — 바탕 `#FFFFFF`/`#D0DFFE`/`#E9EAEE`,
  `#191A1C`/`#2A4371`/`#33353B`).
- **화면은 DBMS를 모른다.** 렌더러에 Altibase 분기를 넣지 않고 모델의 `DBSTableWithStorageKind`만 본다. SQL Server
  메모리 최적화 테이블, MySQL MEMORY 엔진 같은 경우도 그 인터페이스를 구현하면 같은 표시를 얻는다(이번 작업 범위 밖).
- **큐는 대상이 아니다.** `AltibaseQueue`는 `AltibaseTable`이 아니라 `GenericTable`을 상속하고, 큐는 항상 메모리 테이블이라
  색을 붙이지 않았다.

### upstream 대비 변경점

- upstream(DBeaver)은 Altibase 테이블 목록을 `DatabaseMetaData.getTables`로 읽고 저장 방식을 표시하지 않는다.
- 공통 모델에 `DBSTableStorageKind`, `DBSTableWithStorageKind`와 메시지 4개를 추가했다.

### 위험

- 목록 질의를 드라이버 대신 직접 쓰므로, 나중에 Altibase가 카탈로그 열이나 테이블 종류를 바꾸면 이 질의를 고쳐야 한다.
  7.3에서만 확인했다(7.1 이하 서버는 확인하지 못함).
- 파티션 하위 질의는 파티션드 테이블에서만 실행되지만, 파티션이 아주 많은 스키마에서는 목록 읽기가 조금 느려질 수 있다.

### 시험 환경

- 컨테이너 `chromite-test-altibase`(이미지 `tbx-altibase:7.3`), 연결 정보는 저장소 밖 `D:\Tools\_briefs\test-dbs.md`.
- 드라이버 jar(`Altibase.jar`)는 Altibase A+ Edition 사용권(비상업 용도, 재배포 조건 있음)이라 저장소와 제품에 넣지 않았다.
  Driver Manager에서 로컬 라이브러리로 추가해 썼다([driver-licenses.md](driver-licenses.md)의 분류 B와 같음).

## 한국어 표시

Altibase 번들의 `OSGI-INF/l10n/bundle_ko.properties`는 DBeaver에서 온 일부 키(영어 392개 중 114개)만 있다. 한국어 화면에서 영어로
보이던 것 가운데 다음을 더했다(용어는 Oracle 번들과 용어집과 같음).

| 키 | 영어 | 한국어 |
|---|---|---|
| `meta.….AltibaseTable.partitionedTable.name` | Partitioned | 분할됨 |
| `tree.materialized_views.node.name`, `tree.materialized_view.node.name` | Materialized View(s) | 구체화된 뷰 |

남은 것:
- 속성 창의 범주 머리글 "Statistics"는 번역 파일로 고칠 수 없다. `@Property(category = DBConstants.CAT_STATISTICS)`의 글자를
  `ObjectPropertyDescriptor.getCategory`가 그대로 돌려주고 번역을 찾지 않는다. 모든 DB에 같은 문제다(후속 과제 #29).
- 나머지 빠진 키 278개(예: 폴더 `Global metadata`, 속성 `Database 이름`의 "Database"는 generic 번들)와 `plugin.xml`에 글자로 적은
  폴더 설명(`Procedures`, `Functions`, `Synonyms` 등)은 이번에 고치지 않았다(후속 과제 #24, #26).

## 복제 시작·중지·동기화 (2026-11-08)

SWT 감사 D-A7(`AltibaseToolReplicationStart/Stop`). 상태는 Activity Monitor의 복제 탭, 명령은 Object Explorer의 복제 노드(전역 메타데이터 ▸ Replications ▸ 복제)다.

### 화면

| 위치 | 내용 |
|---|---|
| Activity Monitor ▸ 복제(Replication) | 요약(복제 포트 `REPLICATION_PORT_NO`, 0이면 "이 서버는 복제가 꺼져 있음", 복제 수와 시작된 수), 복제(이름, 원격 서버, 시작됨, 모드, 역할, 테이블 수, XSN, 원격 장애 감지·포기 시각), 송신(상태, 시작 방식, 네트워크 오류, 상대 서버, 지연 바이트, XSN·커밋 XSN), 수신(상대 서버, 적용 XSN, 적용·실패한 변경 수). 읽기 전용 |
| 복제 노드 오른쪽 클릭 | 복제 시작 / 복제 중지 / 복제 동기화. 모델이 읽은 시작 여부(`IS_STARTED`)로 시작한 복제의 시작·동기화, 멈춘 복제의 중지를 끈다 |

명령 흐름: 무엇을 하는지 묻는 확인(시작 / 중지 / 동기화 단추와 취소, 취소가 기본) → 스크립트 미리 보기(취소 기본) → 열린 트랜잭션 질문 → 그 인스턴스의 격리 연결에서 실행 → 복제 노드를 다시 읽음(실패해도).

| 명령 | 문 | 근거 |
|---|---|---|
| 시작 | `ALTER REPLICATION r START` | 모델 도구 `AltibaseToolReplicationStart` |
| 중지 | `ALTER REPLICATION r STOP` | 모델 도구 `AltibaseToolReplicationStop` |
| 동기화 | `ALTER REPLICATION r SYNC` | 모델에 도구가 없어 같은 모양으로 씀. Altibase 복제 매뉴얼: 대상 테이블의 모든 행을 원격 서버로 보낸 뒤 복제를 시작(실행으로는 확인하지 못함) |

### 구조

- 모델: `model.admin` `altibase/AltibaseReplicationDialect`(상태, `ReplicationDialect.forDataSource`에 Altibase 분기), `objects/ObjectMaintenanceActions`(`START_REPLICATION`·`STOP_REPLICATION`·`SYNC_REPLICATION`),
  문구 `replication/ReplicationMessages`(`repl_alti_*`).
- 화면: `ObjectMaintenanceCommands`(메뉴 항목, 확인, 노드 다시 읽기), 문구 `ObjectMaintenanceMessages`(`objmaint_repl_*`).
- 읽는 표: `SYSTEM_.SYS_REPLICATIONS_`, `SYSTEM_.SYS_REPL_HOSTS_`, `V$REPSENDER`, `V$REPGAP`, `V$REPRECEIVER`, `V$PROPERTY`. 값의 뜻(REPL_MODE 0 Lazy/2 Eager, ROLE, 송신 STATUS·START_FLAG 코드,
  지연 = REP_GAP × REPLICATION_GAP_UNIT)은 모델의 복제 노드(`AltibaseDataSource` 복제 캐시, `AltibaseReplicationSender`)와 같다.

### 설계 결정

- **원격 서버에 영향을 주므로 한 번 더 묻는다.** 다른 유지 관리 명령은 미리 보기가 확인이지만, 복제 명령은 TRUNCATE처럼 미리 보기 전에 무엇이 일어나는지 적은 확인을 둔다(브리프 요구).
- **상태 다시 읽기는 노드 새로 고침.** 모델 `AltibaseReplication.refreshObjectState`는 복제가 아니라 `SYS_PROCEDURES_`를 읽어(범위 밖 모델 버그, 고치지 않음) 쓰지 않고,
  노드 새로 고침(`refreshObject` → 복제 캐시 다시 읽기)으로 시작 여부와 송신·수신 목록을 맞춘다.
- **이름은 모델 도구처럼 따옴표 없이.** 시작·중지 도구가 `ALTER REPLICATION %s START`로 쓰므로 동기화도 같게 했다(카탈로그에 저장된 이름 그대로).
- **실행 계정.** Altibase 복제 매뉴얼에 따르면 복제 명령은 SYS 사용자만 실행한다(이 서버에서는 복제가 꺼져 있어 확인하지 못함). 다른 계정이면 서버 오류가 실패한 문과 함께 보인다. 상태 표는 SYS가 아닌 사용자도 읽는다(7.3, `CHR_ALTI`로 확인).

### 확인 (2026-11-08)

- 시험 서버(`chromite-test-altibase`, 7.3)는 `REPLICATION_PORT_NO = 0`이라 복제를 만들 수 없다: `CREATE REPLICATION …`이 `ERR-61023 : Replication is disabled`.
  포트는 읽기 전용 속성이라 바꾸려면 공유 컨테이너를 다시 시작해야 해서 하지 않았다. `turbrix-validation-altibase`는 건드리지 않았다.
- 그래서 명령은 코드와 문으로 확인했다: isql에서 `ALTER REPLICATION CHR_G14_NOPE SYNC|START|STOP`이 문법 오류가 아니라 이름 오류(ERR-61126 / ERR-6111B "replication name was not found")로 끝난다.
  자동 커밋을 끈 세션에서도 같은 오류였다(자동 커밋 상태 검사 전에 이름을 찾음 — 수동 커밋 연결에서의 동작은 확인하지 못함).
- 앱(영어·한국어, SYS 연결)에서 Activity Monitor ▸ 복제 탭: 요약 "복제 포트 0: 이 서버는 복제가 꺼져 있음(REPLICATION_PORT_NO)", "복제: 구성되지 않음", 표 4개(`shots\09-alti-replication-summary.png`, `14-ko-alti-replications.png`).
  복제 노드가 없어 오른쪽 클릭 명령은 화면에서 실행하지 못했다(복제가 아닌 노드에서는 세 항목이 꺼짐을 확인).

## Altibase 계열 드라이버: ShardSphere (2026-11-14)

- 이 플러그인의 Altibase 공급자에 두 번째 드라이버 ShardSphere(`shardsphere.jdbc.driver.shardsphereDriver`)를 두고, 메타 모델 `alti`의 `driverClass`에 더했다.
  자기 데이터 원본 유형 `shardsphere`와 아이콘을 쓴다. 정의·내장·확인은 [drivers.md](drivers.md) "ShardSphere".
- 서버 출력 콜백: 콜백 인터페이스를 클래스 이름(`<패키지>.jdbc.driver.AltibaseMessageCallback`)으로 찾지 않고 연결 클래스의 `registerMessageCallback` 매개변수에서 얻는다.
  드라이버마다 패키지·클래스 이름이 달라도(Altibase.jar, Altibase7_x.jar, ShardSphere) 같은 코드가 동작한다.
- 복제 목록: 복제 카탈로그 표가 없는 서버(ShardSphere 시험 서버에는 `SYSTEM_.SYS_REPLICATIONS_`가 없음)는 복제가 없는 것으로 본다. `getReplications`가 그 질의의
  "표 없음"(42S02, `AltibaseConstants.EC_TABLE_NOT_FOUND` 0x31031)만 빈 목록으로 읽는다.
- 독립 함수 매개변수: ShardSphere 드라이버(JDBC 3.0)에 `getFunctionColumns`가 없어 `GenericProcedure`가 `getProcedureColumns`로 읽는다([drivers.md](drivers.md) "ShardSphere").

## 서버 예약어 (2026-11-15, 앱 변경 #115)

### 목적

사용자 요청 "shardsphere의 전용 예약어들이 있는데 확인가능한가?" → "맞춰라". 키워드 강조(와 식별자 따옴표)를 연결한 서버가 알려 주는 예약어에 맞춘다.
ShardSphere 문서는 없으므로 서버가 보고하는 것만 쓴다. 새 문법, 새 강조 분류, ShardSphere 낱말 목록은 넣지 않았다.

### 근본 원인

`AltibaseSQLDialect.initDriverSettings`가 Altibase 7.x용 정적 목록 `ALTIBASE_ONLY_KEYWORDS`(124개)를 모든 연결에 넣었다. 이 방언은 Altibase와
ShardSphere(앱 변경 #113)가 함께 쓰는데, 두 서버의 `V$RESERVED_WORDS`(각 258개)가 다르다. ShardSphere에만 `CONTINUOUS`·`CONTROLFILE`·`GEMINI`·`SHARD`·`STREAM`,
Altibase 7.3에만 `CHECK`·`DUMP_CALLSTACKS`·`LOGANCHOR`·`REPLICATION`·`TIMESTAMPADD`가 있다. 정적 목록 하나로는 이 계열의 모든 서버에 맞출 수 없다.

### 규칙

| 항목 | 내용 |
|---|---|
| 낱말의 출처 | 방언을 초기화할 때(연결, 연결 새로 고침) 메타 세션에서 `SELECT KEYWORD FROM V$RESERVED_WORDS` 한 번(`AltibaseSQLDialect.readServerKeywords`). 제품 이름 검사 없음 |
| 대체 | 질의가 실패하거나(오래된 서버, 권한 없음, 오류) 행이 없으면 정적 목록. 디버그 로그 한 줄, 연결은 실패하지 않음 |
| 우선순위 | 표준 SQL 키워드(`BasicSQLDialect` 생성자)와 드라이버 키워드·함수 목록은 그대로 먼저 들어간다. 서버 낱말은 정적 목록과 같은 방법(`addSQLKeywords`)으로 더한다: 함수 목록에도 있는 낱말(`ROWNUM`, `REPLACE`)은 키워드 종류가 되고 함수 목록에 남는다(엔진은 키워드 종류로 식별자 따옴표를 정한다, `AbstractSQLDialect.addKeywords` 주석). 서버 낱말은 아무것도 지우지 않는다. 그 뒤 `SYSDATE`(OTHER)와 `TRUNCATE`는 전과 같다 |
| 수명 | 방언 객체는 데이터 원본마다 새로 만든다(`AltibaseDataSource` 생성자 `new AltibaseSQLDialect()`). 다시 연결하면 새 데이터 원본·새 방언이다. 새로 고침은 같은 방언에 같은 낱말을 다시 넣는다(맵이라 늘지 않음) |
| `RESERVED_TYPE` | 쓰지 않는다(아래) |

### `RESERVED_TYPE`의 뜻 (측정)

- Altibase 7.3 시험 서버, 임시 스키마 `CHR_SS_KEYWORDS`(확인 뒤 지움)에서 258개 낱말마다 따옴표 없이 테이블 이름, 열 이름, 열 별칭으로 써 봄:
  - 1(160개): 열 이름·별칭으로 모두 됨. 0(98개): 모두 안 됨(구문 오류 200705). 테이블 이름은 두 값 모두 258개 전부 안 됨(통제: `PLAIN_NAME`, `"TYPE"`, `STREAM`은 됨).
  - 즉 Altibase 7.3에서 1은 "열 이름·별칭으로는 쓸 수 있는 예약어", 0은 "어디에도 따옴표 없이 쓸 수 없는 예약어"다. 객체 이름으로는 둘 다 따옴표가 필요하다.
- ShardSphere(읽기만): 별칭 `SELECT 1 AS <낱말> FROM DUAL`만 시험. 1(75개)은 모두 됨, 0(183개) 중 91개도 됨(그중 81개는 Altibase 7.3에서 1인 낱말,
  `CONTINUOUS`·`CONTROLFILE`·`SHARD`·`STREAM`과 `CONSTANT`·`EXCEPTION`·`NEW`·`OLD`·`SESSION`·`TOP`). ShardSphere의 값은 Altibase의 규칙과 다르고, 쓰기가 금지된 서버라
  열·테이블 이름으로는 확인하지 못했다.
- 방언은 키워드를 모두 똑같이 따옴표로 감싸고(`AbstractSQLDialect.mustBeQuoted`) "예약되지 않은 키워드" 종류가 없다. 새로 만들지 않았다(브리프). 테이블 이름은 어차피 둘 다 따옴표가 필요하다.

### 전후 낱말 (같은 빌드에서 "전"은 같은 연결에 뷰 읽기를 실패시킨 새 방언 = 정적 목록)

| 서버 | 전 | 후 | 빠진 키워드 | 새 키워드 |
|---|---|---|---|---|
| ShardSphere 5.1.9 | 717개(KEYWORD 502) | 715개(KEYWORD 500) | `CACHE`, `NOCACHE`, `DUMP_CALLSTACKS`, `LOGANCHOR`, `REPLICATION` | `CONTINUOUS`, `CONTROLFILE`, `STREAM` |
| Altibase 7.3.0.0.7 | 707개(KEYWORD 489) | 704개(KEYWORD 486) | `CACHE`, `NOCACHE`, `SHARD` | 없음 |

- 서버 낱말 중 정적 목록에 없던 나머지(`TYPE`, `LIMIT`, `LEVEL` 등)는 이미 표준·드라이버 키워드라 바뀌지 않았다. 함수 목록(213개, 212개)은 그대로다.
- `CACHE`·`NOCACHE`는 정적 목록에만 있고 두 서버 모두 예약어로 보고하지 않는다. 이제 키워드 색이 아니다(`CREATE SEQUENCE … CACHE 20`).
- `TIMESTAMPADD`: Altibase에서는 전후 모두 키워드(함수 목록에 없음), ShardSphere에서는 전후 모두 방언이 모르는 낱말이다.

### 자동 완성

- 키워드 자동 완성은 방언의 키워드 목록을 쓰지 않는다. Swing 자동 완성(`SQLQueryCompletionContext.prepareKeywordCompletions`)은 문법이 예측한 낱말만 낸다.
  그래서 `CONT`·`STRE`·`REPLI`에 `CONTINUOUS`·`STREAM`·`REPLICATION`은 전에도 후에도 나오지 않는다. 바꾸지 않았다(문법 기반 완성 엔진의 변경은 범위 밖).
- `DBMS_SHARD`: `EXEC SYS.DBMS_SHARD.` → 서브프로그램 55개(56개 이름 중 `VERSION`은 함수라 `EXEC` 뒤에 내지 않음, `SUB_TYPE` 1). 한정하지 않은 `EXEC DBMS_SHARD.`와
  `SELECT DBMS_SHARD.`는 0개다. Altibase 7.3의 `EXEC DBMS_OUTPUT.`도 0개, `EXEC SYS.DBMS_OUTPUT.`은 5개라 ShardSphere만의 문제가 아니다.
  원인: Altibase 연결의 실행 문맥에 기본 스키마가 없다(`getContextDefaults()`의 스키마·카탈로그 null, `supportsSchemaChange` false). 완성은 한정하지 않은 이름을 기본 스키마에서 찾으므로
  패키지(와 PUBLIC 시노님 `DBMS_SHARD`)를 찾지 못한다. 기본 스키마는 아래 "세션의 기본 스키마"(앱 변경 #116)에서 고쳤다.

### 확인 (2026-11-15, 이 브랜치 빌드, 사설 작업 공간, 앱 안 테스트 에이전트)

- 두 연결(실제 ShardSphere 서버는 읽기만, Altibase 7.3 시험 서버)을 동시에 열고 각 쿼리 창의 토큰 분류를 읽음: ShardSphere 창은 `CONTINUOUS`·`CONTROLFILE`·`STREAM`·`SHARD`·`GEMINI`가
  키워드, `REPLICATION`·`LOGANCHOR`·`DUMP_CALLSTACKS`는 이름. Altibase 창은 그 반대(`SHARD`·`GEMINI`는 이름). `CHECK`·`SELECT`는 둘 다 키워드. 창을 바꿔 다시 읽어도,
  연결 새로 고침 뒤에도 같다. 방언 객체는 연결마다 다르다.
- 고정 예문(13줄)의 토큰 분류 전후 차이는 위 표의 낱말뿐이다(ShardSphere 3줄, Altibase 2줄).
- 새로 고침 뒤와 끊고 다시 연결한 뒤 낱말 목록이 연결 직후와 같다(늘지도 비지도 않음).
- 대체 경로: 시험 서버에서는 권한 없는 사용자로도 뷰를 읽을 수 있어(임시 사용자로 확인) 서버로는 재현할 수 없었다. 문장을 실패시키는 세션으로 방언을 초기화해
  정적 목록이 들어가고 예외 없이 끝나며 디버그 로그에 한 줄이 남는 것을 확인했다.
- 더한 질의 시간: ShardSphere 약 15 ms(`SELECT 1 FROM DUAL`과 같음), Altibase 시험 서버 약 380–770 ms(SSH 터널 너머, `SELECT 1 FROM DUAL`도 380–780 ms라 왕복 한 번).

## 세션의 기본 스키마 (2026-11-15, 앱 변경 #116)

### 목적

앱 변경 #115 보고: 한정하지 않은 `EXEC DBMS_SHARD.`(ShardSphere)와 `EXEC DBMS_OUTPUT.`(Altibase)가 아무것도 제안하지 않고, 스키마를 붙이면(`EXEC SYS.DBMS_SHARD.`) 제안한다.
Altibase 계열 연결(Altibase, ShardSphere)의 실행 문맥이 세션의 기본 스키마를 알게 해 한정하지 않은 이름을 그 스키마에서 찾게 한다.

### 근본 원인

- 일반(generic) 실행 문맥은 드라이버 매개변수 `query-get-active-db`의 결과를 기본 개체 이름으로, `active-entity-type`(여기서는 `schema`)을 그 종류로 쓴다
  (`GenericExecutionContext.determineSelectedEntity`, `getDefaultObject`).
- 두 드라이버의 그 질의가 `SELECT db_name FROM V$DATABASE`였다. 결과는 데이터베이스 이름(`mydb`, `memdb`)이고 그런 스키마는 없으므로 `getSchema("mydb")`가 null,
  기본 스키마도 null이었다. 캐시된 값(`getCachedDefault`)에는 `mydb`가 남아 쿼리 창의 스키마 칸과 상태 줄이 데이터베이스 이름을 보였다(`SqlEditorPanel.getUnlistedDefaultName`).
- 같은 매개변수를 `AltibaseDataSource.getDbName`(세션·잠금 목록의 데이터베이스 이름)도 읽었다. 질의 하나가 두 뜻으로 쓰였다.

### 서버가 지원하는 것 (측정)

| 항목 | Altibase 7.3.0.0.7 (시험 서버) | ShardSphere 5.1.9 (실제 서버, 읽기만) |
|---|---|---|
| `SELECT USER_NAME() FROM DUAL` | 로그인 사용자, 대문자(`SYS`, 임시 사용자로 `CHR_AB_DEFSCHEMA`) | `SYS` |
| `V$SESSION.DB_USERNAME`(`ID = SESSION_ID()`) | 같음 | 같음 |
| JDBC `DatabaseMetaData.getUserName()` | 입력한 글자 그대로 | `sys`(입력한 소문자 그대로라 스키마 이름과 다름) |
| JDBC `Connection.getSchema()` | null | `AbstractMethodError`(JDBC 3.0 드라이버) |
| `ALTER SESSION SET CURRENT_SCHEMA = SYSTEM_` | ERR-266371 "A specified property does not exist or cannot be updated", 한정하지 않은 `SYS_TABLES_`는 여전히 "없음" | 시험하지 않음(쓰기 금지 서버) |
| JDBC `Connection.setSchema("SYSTEM_")` | 예외 없이 아무것도 하지 않음(`getSchema()` null, 이름 풀이 그대로) | 시험하지 않음 |
| 질의 한 번 | `USER_NAME()` 113–207 ms, `V$DATABASE` 115–562 ms, `SELECT 1 FROM DUAL` 103–340 ms(SSH 터널 왕복) | 각 10–17 ms |

결론: 세션의 스키마는 로그인 사용자이고, 세션 안에서 바꾸는 방법은 찾지 못했다(위 두 방법 모두 바뀌지 않음). 그래서 기본값은 읽기 전용이다.

### 규칙

| 항목 | 내용 |
|---|---|
| 읽는 질의 | 두 드라이버(`plugin.xml`)의 `query-get-active-db`를 `SELECT USER_NAME() FROM DUAL`로. 제품 이름 검사 없음 |
| 바꾸기 | 없음. `query-set-active-db`가 없고 API 경로도 아니라 `supportsSchemaChange`·`supportsCatalogChange`는 false(`GenericExecutionContext.java:228-268`). 쿼리 창 스키마 칸은 꺼져 있고 Object Explorer "기본으로 설정"도 꺼짐(`DefaultObjectCommands.java:76`) |
| JDBC 경로를 쓰지 않은 이유 | 매개변수를 비우면 `session.getSchema()`를 쓰는데, 그 경로는 `selectedEntityFromAPI`를 켜서 스키마 바꾸기가 켜지고(`GenericExecutionContext.java:106-108`, `:259-265`) 바꿀 때 `session.setSchema`를 부른다. Altibase는 그 호출을 무시하고 ShardSphere 드라이버에는 메서드가 없다. 값도 Altibase는 null이다 |
| 읽는 때 | 문맥을 만들 때 한 번(`initializeContextState` → `refreshDefaults`)과, 전과 같이 쿼리 창이 기본값을 다시 읽을 때. 같은 자리에서 전에는 `V$DATABASE`를 읽었으므로 문장 수는 같다. 실패하면 디버그 로그 한 줄 뒤 기본값 없음(전과 같은 동작, 연결은 실패하지 않음) |
| 데이터베이스 이름 | `AltibaseConstants.QUERY_GET_DB_NAME`(`SELECT db_name FROM V$DATABASE`)을 `getDbName`이 직접 쓴다 |
| 연결 확인 질의 | `query-ping`이 없으면 이 질의를 쓰므로(`GenericSQLDialect.java:115-118`) 연결 확인도 `USER_NAME()`이 된다. 두 서버에서 시간이 같다 |
| 사용자 드라이버 설정 | 드라이버 매개변수는 기본값과 다를 때만 저장된다(`DriverDescriptorSerializerLegacy.java:257`). 이 매개변수를 직접 고쳐 둔 사용자는 그 값이 남는다 |

### 기본값을 읽는 곳 (전 → 후, Altibase·ShardSphere 연결)

| 곳 | 전 | 후 | 실행하는 SQL |
|---|---|---|---|
| 자동 완성(`SQLSearchUtils.findObjectsByPath`, `SQLQueryCompletionContext.obtainDefaultContext`) | 한정하지 않은 이름을 데이터 원본 전체에서 찾음(대부분 못 찾음) | 기본 스키마에서 찾음 | 넣는 글자만 바뀜(아래 확인 표) |
| 쿼리 창 스키마 칸·상태 줄(`SqlEditorPanel.showSchemas`) | `mydb`(목록에 없는 캐시 값) | 기본 스키마 선택, 칸은 꺼짐 | 없음 |
| Object Explorer | 굵은 노드 없음 | 기본 스키마 노드가 굵게(`ObjectExplorerPanel.java:2497`) | 없음 |
| Copy Qualified Name, 끌어 놓기 이름(#112), Script Table As(CREATE·SELECT) | `SCHEMA.T` | 같음(측정). 끌어 놓기는 `database.schema.object` 이름만 줄이므로 해당 없음(`ExplorerObjectCommands.createDragName`) | 없음 |
| 결과 집합의 테이블 찾기(`DBUtils.getEntityFromMetaData`, 편집 가능 여부) | 한정하지 않은 `FROM T`는 테이블을 찾지 못함 | 기본 스키마의 `T`에 묶임. 만드는 UPDATE·DELETE는 `USER.T`로 쓴다 | 바뀜. 서버가 한정하지 않은 `T`를 찾는 스키마와 같다(코드로 확인, 실행하지 않음) |
| `CALL p(?)` 매개변수 메타데이터(`JDBCCallableStatementImpl.java:186-188`) | 없음 | 기본 스키마의 `p` | 문장 글자는 같음 |
| 객체 찾기·하이퍼링크(`GenericStructureAssistant.java:97-100`, `DBStructUtils.isSchemasSupported`) | 한정하지 않은 이름을 데이터 원본 전체에서 | 기본 스키마에서, 스키마 이름도 찾음 | 없음. 다른 스키마의 같은 이름은 이제 한정해야 이동한다 |
| DDL 뒤 메타데이터 새로 고침 대상(`SQLMetadataRefreshTargetResolver`) | 데이터 원본 전체 | 기본 스키마 | 없음 |
| 데이터 가져오기·복사의 기본 대상 스키마(`DataImportTargetPage`, `DatabaseCopyPages`) | 목록의 첫 스키마 | 기본 스키마(저장된 값이 우선) | 없음 |
| 결과에서 CREATE TABLE 만들기(`ResultSetGenerateSql`) | "스키마 없음"으로 실패 | 기본 스키마에 만드는 DDL을 편집기에 보여 줌(실행하지 않음) | 없음 |
| Chromite Agent(`ConnectionTools`, `AIDatabaseContext`, `AgentSql.refuseSharedSessionSwitch`) | 연결 스키마 없음, 공유 세션의 스키마는 `mydb`로 비교해 그 사용자 스키마를 지정한 호출을 거절 | 연결 스키마·프롬프트의 "Default schema"가 사용자 스키마, 그 스키마를 지정한 호출은 통과(다른 스키마는 여전히 거절). `SHARED_SESSION_DEFAULTS_NOTE` 문구는 그대로 | 거절되던 호출이 그 세션의 실제 스키마에서 실행됨 |
| 쿼리 기록·QM 로그의 스키마 | `mydb` 또는 없음 | 사용자 스키마 | 없음 |

- 실행되는 문장이 전과 다른 스키마에서 실행되게 하는 곳은 찾지 못했다. 이름을 줄이는 곳(완성의 짧은 이름, AI 프롬프트)은 기본 스키마 객체만 줄이고, 서버도 한정하지 않은 이름을
  그 스키마(세션 사용자)에서 찾는다. 기본 스키마를 바꾸려는 곳은 모두 `supportsSchemaChange`를 먼저 보므로 아무것도 하지 않는다(`DBExecUtils.setExecutionContextDefaults`).

### PUBLIC 시노님 (고치지 않음, 공통 완성 엔진)

- SYS로 연결하면 `DBMS_OUTPUT`·`DBMS_SHARD` 패키지가 기본 스키마 SYS에 있어 풀린다. 다른 사용자에게는 PUBLIC 시노님으로만 보여 `EXEC DBMS_OUTPUT.`는 전후 모두 0개다
  (`EXEC SYS.DBMS_OUTPUT.`은 5개).
- 원인은 두 곳이다. (1) 모델: Altibase 모델은 공개 범위(`DBSVisibilityScopeProvider`, Oracle은 `OracleSchema.getPublicScopes`)를 내놓지 않고, PUBLIC 시노님을 담는
  `AltibaseDataSource.publicSchema`는 `GenericSchema`라 `getChild`가 테이블만 찾는다. (2) 공통 엔진: 시노님을 찾아도 점 뒤 완성의 원천은 찾은 객체 그대로다
  (`SQLQuerySemanticUtils.performPartialResolution`, 별칭을 풀지 않고 `DbObjectFromDbObject`를 만듦). `SQLQueryCompletionContext`의 OBJECT·CALL 분기는 그 객체가
  `DBSObjectContainer`일 때만 제안하는데 시노님(`DBSAlias`)은 컨테이너가 아니다. 모든 엔진에 같은 제한이다(Oracle의 PUBLIC 시노님도 같은 경로, 실행으로는 확인하지 않음).
- (2)는 공통 완성 엔진을 바꾸는 일이라 지시대로 멈췄다. (1)만으로는 `EXEC` 경우가 그대로라 하지 않았다.

### 트랜잭션

- 더한 코드는 SELECT 하나를 바꾼 것뿐이고 커밋·롤백이 없다.
- 시험 서버, 수동 커밋 세션을 다른 세션에서 `V$TRANSACTION`으로 관찰: `USER_NAME()` 전후 `UPDATE_STATUS` 0, 로그 0(대조: 내 임시 테이블에 INSERT 뒤 `UPDATE_STATUS` 1, 롤백).
- 앱: 수동 커밋 연결(`CHR_AB_DEFSCHEMA`)의 모든 문맥이 연결·기본값 다시 읽기·새로 고침·다시 연결 뒤 `QMUtils.isTransactionActive` false, 서버 세션 `UPDATE_STATUS` 0.

### 확인 (2026-11-15, 두 브랜치를 합친 빌드, 사설 작업 공간, 앱 안 테스트 에이전트)

"전"은 같은 앱에서 문맥의 기본 개체 이름을 옛 질의 결과(`mydb`, `memdb`)로 되돌려 만든 상태다.

| 연결 · 입력 | 전 | 후 |
|---|---|---|
| ShardSphere(sys) `EXEC DBMS_SHARD.` | 0 | 55(`EXEC SYS.DBMS_SHARD.`와 같음) |
| Altibase SYS `EXEC DBMS_OUTPUT.` | 0 | 5(`NEW_LINE`, `PRINT_DISABLE`, `PRINT_ENABLE`, `PUT`, `PUT_LINE`) |
| Altibase 일반 사용자 `EXEC DBMS_OUTPUT.`(PUBLIC 시노님) | 0 | 0(위 "PUBLIC 시노님") |
| 일반 사용자 `SELECT * FROM CHR_OWN` (자기 테이블) | 0 | `CHR_OWN_T` |
| 일반 사용자 `SELECT CHR_OWN_T.` / `WHERE` 뒤 열 | 0 / 0 | `OWN_ID`, `OWN_NAME` / 같음 |
| 일반 사용자 `SELECT * FROM CHR_AB_SYS`(SYS의 테이블) | 0 | 0(다른 스키마는 여전히 접두가 필요), `SYS.CHR_AB_SYS`는 전후 1 |
| SYS `SELECT * FROM CHR_AB_SYS` / `SELECT * FROM CHR_OWN`(다른 스키마) | 0 / 0 | 1 / 0 |

- 기본 스키마: SYS 연결 `SYS`, 임시 사용자 연결 `CHR_AB_DEFSCHEMA`(SYS가 아님), ShardSphere `SYS`. 주·메타데이터 문맥, 쿼리 창마다의 문맥, 격리 문맥(새 쿼리 창과 같은
  `openIsolatedContext`) 모두 같다. 연결 새로 고침, 다시 연결, 끊고 다시 연결한 뒤 새 문맥도 같다. `supportsSchemaChange` false.
- 쿼리 창: 스키마 칸이 기본 스키마를 선택하고 꺼짐, 상태 줄에 사용자 스키마. Object Explorer는 기본 스키마 노드만 기본으로 표시(`DBNUtils.isDefaultElement`).
- 이름: `SYS.CHR_AB_SYS_T`(다른 스키마), `CHR_AB_DEFSCHEMA.CHR_OWN_T`(자기 스키마)의 DML·UI·DDL 이름, 끌어 놓기 이름, CREATE·SELECT 스크립트가 전후 같다.
- 연결 시간: 바꾼 질의는 같은 자리의 질의 하나를 대신한다. `USER_NAME()`과 `V$DATABASE`가 같은 시간(Altibase 180–208 ms 대 180–242 ms, ShardSphere 16–17 ms 대 16 ms)이라
  더한 시간은 측정 오차 안이다.
- 확인하지 못한 것: 결과 집합 편집 바인딩·`CALL` 매개변수·AI 문맥은 코드로만 봤다. Oracle의 PUBLIC 시노님 완성은 실행하지 않았다. 7.1 이하 서버는 없다.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번).

### upstream 대비 변경점

- `plugin.xml` 두 드라이버의 `query-get-active-db`, `AltibaseConstants.QUERY_GET_DB_NAME`, `AltibaseDataSource.getDbName`이 그 상수를 씀(필드 `queryGetActiveDB` 지움).

## 변경 이력

- 2026-11-07 최초 작성: Object Explorer 테이블 저장 방식 표시(메모리 빨강, 디스크 파랑, 휘발성 호박색, 하이브리드), 목록 질의 변경.
- 2026-11-07 한국어 표시: "Partitioned"(분할됨), "Materialized View(s)"(구체화된 뷰) 번역 추가(후속 과제 #61), 위 "한국어 표시".
- 2026-11-08 복제 상태(Activity Monitor 복제 탭)와 복제 시작·중지·동기화 명령(SWT 감사 D-A7), 위 "복제 시작·중지·동기화".
- 2026-11-14 Altibase 계열 드라이버 ShardSphere 추가와 서버 출력 콜백을 메서드 매개변수로 찾게 바꿈(앱 변경 #113), 위 "Altibase 계열 드라이버: ShardSphere".
- 2026-11-14 복제 카탈로그가 없는 서버의 복제 목록을 빈 목록으로, JDBC 3.0 드라이버의 함수 매개변수(앱 변경 #113 검토 뒤).
- 2026-11-15 엔진 키워드를 연결한 서버의 `V$RESERVED_WORDS`에서 읽음(읽지 못하면 정적 목록), `RESERVED_TYPE`의 뜻과 `DBMS_SHARD` 자동 완성 조사(앱 변경 #115), 위 "서버 예약어".
- 2026-11-15 실행 문맥의 기본 스키마를 세션 사용자로 읽음(`query-get-active-db` = `USER_NAME()`, 읽기 전용), 데이터베이스 이름은 따로 읽음(앱 변경 #116), 위 "세션의 기본 스키마".
