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
| 문법 낱말 | 예약어가 아닌 문법 낱말(`ALTIBASE_SYNTAX_KEYWORDS`: `CACHE`, `NOCACHE`)은 서버 목록과 관계없이 키워드로 더한다. 연결한 서버가 예약어로 보고하지 않으면 이름의 따옴표 이유가 되지 않는다(`AltibaseSQLDialect.mustBeQuoted`, 아래 "예약어가 아닌 문법 낱말") |

### `RESERVED_TYPE`의 뜻 (측정)

- Altibase 7.3 시험 서버, 임시 스키마 `CHR_SS_KEYWORDS`(확인 뒤 지움)에서 258개 낱말마다 따옴표 없이 테이블 이름, 열 이름, 열 별칭으로 써 봄:
  - 1(160개): 열 이름·별칭으로 모두 됨. 0(98개): 모두 안 됨(구문 오류 200705). 테이블 이름은 두 값 모두 258개 전부 안 됨(통제: `PLAIN_NAME`, `"TYPE"`, `STREAM`은 됨).
  - 즉 Altibase 7.3에서 1은 "열 이름·별칭으로는 쓸 수 있는 예약어", 0은 "어디에도 따옴표 없이 쓸 수 없는 예약어"다. 객체 이름으로는 둘 다 따옴표가 필요하다.
- ShardSphere(읽기만): 별칭 `SELECT 1 AS <낱말> FROM DUAL`만 시험. 1(75개)은 모두 됨, 0(183개) 중 91개도 됨(그중 81개는 Altibase 7.3에서 1인 낱말,
  `CONTINUOUS`·`CONTROLFILE`·`SHARD`·`STREAM`과 `CONSTANT`·`EXCEPTION`·`NEW`·`OLD`·`SESSION`·`TOP`). ShardSphere의 값은 Altibase의 규칙과 다르고, 쓰기가 금지된 서버라
  열·테이블 이름으로는 확인하지 못했다.
- 방언은 서버가 보고한 낱말을 모두 똑같이 따옴표로 감싼다(`AbstractSQLDialect.mustBeQuoted`). `RESERVED_TYPE`으로 "예약되지 않은 키워드"를 나누지 않았다(브리프). 테이블 이름은 어차피 둘 다 따옴표가 필요하다. 따옴표 없이 두는 키워드는 서버가 보고하지 않는 문법 낱말뿐이다(아래).

### 전후 낱말 (같은 빌드에서 "전"은 같은 연결에 뷰 읽기를 실패시킨 새 방언 = 정적 목록)

| 서버 | 전 | 후(#115) | 빠진 키워드 | 새 키워드 |
|---|---|---|---|---|
| ShardSphere 5.1.9 | 717개(KEYWORD 502) | 715개(KEYWORD 500) | `CACHE`, `NOCACHE`, `DUMP_CALLSTACKS`, `LOGANCHOR`, `REPLICATION` | `CONTINUOUS`, `CONTROLFILE`, `STREAM` |
| Altibase 7.3.0.0.7 | 707개(KEYWORD 489) | 704개(KEYWORD 486) | `CACHE`, `NOCACHE`, `SHARD` | 없음 |

- 서버 낱말 중 정적 목록에 없던 나머지(`TYPE`, `LIMIT`, `LEVEL` 등)는 이미 표준·드라이버 키워드라 바뀌지 않았다. 함수 목록(213개, 212개)은 그대로다.
- `CACHE`·`NOCACHE`는 정적 목록에만 있고 두 서버 모두 예약어로 보고하지 않는다. #115 뒤에는 키워드 색이 아니었다(`CREATE SEQUENCE … CACHE 20`). 앱 변경 #118에서 되돌렸다(아래).
- `TIMESTAMPADD`: Altibase에서는 전후 모두 키워드(함수 목록에 없음), ShardSphere에서는 전후 모두 방언이 모르는 낱말이다.

### 예약어가 아닌 문법 낱말 (2026-11-15, 앱 변경 #118)

#115에서 받아들인 회귀(`CACHE`·`NOCACHE`가 키워드 색을 잃음)를 고쳤다. 근본 원인: 예약어 목록은 문법 낱말 목록이 아니다. `V$RESERVED_WORDS`는 예약어만 보고하므로,
예약되지 않은 문법 낱말은 어느 서버에서도 들어오지 않는다.

규칙: 정적 목록의 낱말 가운데 **Altibase 문법의 낱말이고 계열의 어느 서버도 예약하지 않는 것**은 예약어가 아닌 문법 낱말이다. 이 낱말은 서버와 관계없이 키워드(색)로 두되,
연결한 서버가 예약어로 보고하지 않는 한 이름의 따옴표 이유가 되지 않는다. 한 서버라도 예약하는 낱말은 연결한 서버의 목록을 따른다.

정적 목록과 두 서버 목록의 차이 6개를 낱말마다 이렇게 가렸다(시험 Altibase는 내 임시 스키마 `CHR_AB_DEFSCHEMA`에서 만들고 지움, ShardSphere는 SELECT만):

| 낱말 | Altibase 7.3 `V$RESERVED_WORDS` | ShardSphere | 7.3에서 따옴표 없는 테이블·열 이름 | 문법 | 판단 |
|---|---|---|---|---|---|
| `CACHE` | 없음 | 없음(별칭 됨) | 됨 / 됨 | `CREATE SEQUENCE … CACHE 20`, `ALTER SEQUENCE … CACHE 10` 됨(캐시 크기 10 확인) | 예약어가 아닌 문법 낱말 |
| `NOCACHE` | 없음 | 없음(별칭 됨) | 됨 / 됨 | `CREATE SEQUENCE … NOCACHE`, `ALTER SEQUENCE … NOCACHE` 됨(캐시 크기 0 확인) | 예약어가 아닌 문법 낱말 |
| `SHARD` | 없음 | 예약어 | 됨 / 됨 | 7.3에서 쓰는 문법을 확인하지 못함 | ShardSphere의 예약어, 서버를 따름 |
| `REPLICATION` | 예약어 | 없음 | 구문 오류 / 구문 오류 | | Altibase의 예약어, 서버를 따름 |
| `LOGANCHOR` | 예약어 | 없음 | 구문 오류 / 됨 | | Altibase의 예약어, 서버를 따름 |
| `DUMP_CALLSTACKS` | 예약어 | 없음 | 구문 오류 / 됨 | | Altibase의 예약어, 서버를 따름 |

- 정적 목록의 출처도 같은 구분을 보여 준다. 생성 기초 자료 `ALTIBASE_KEYWORDS`는 이름순으로 정렬된 258개(7.x 예약어 목록, 7.3 서버의 258개와 `SHARD`/`TIMESTAMPADD`만 다름)
  뒤에 `CACHE`, `NOCACHE`를 덧붙인 260개였다. 두 낱말을 기초 자료와 대체 목록(`ALTIBASE_ONLY_KEYWORDS`, 124개 → 122개)에서 떼어 `ALTIBASE_SYNTAX_KEYWORDS`로 옮겼다.
- 식별자 따옴표: 방언은 KEYWORD·TYPE·OTHER 종류의 낱말을 이름으로 쓸 때 따옴표로 감싼다(`AbstractSQLDialect.mustBeQuoted`). #115 전에는 `CACHE`도 그래서 열 이름 `CACHE`가
  `"CACHE"`가 되었고, #115 뒤에는 키워드가 아니라 따옴표가 없었다. 지금은 키워드이지만 `AltibaseSQLDialect.mustBeQuoted`가 예약되지 않은 문법 낱말은 다른 평범한 이름과 같은
  이유(항상 따옴표 설정, 대소문자 보존)로만 감싼다. 서버가 그 낱말을 예약어로 보고하면 예약어로 다룬다.

결과(같은 빌드, 앱 안에서 측정):

| 서버 | 정적 목록(#115 전) | #115 뒤 | 지금 | 정적 목록 대비 빠진 키워드 | 새 키워드 |
|---|---|---|---|---|---|
| ShardSphere 5.1.9 | 717개(KEYWORD 502) | 715개(KEYWORD 500) | 717개(KEYWORD 502) | `DUMP_CALLSTACKS`, `LOGANCHOR`, `REPLICATION`(Altibase의 예약어) | `CONTINUOUS`, `CONTROLFILE`, `STREAM` |
| Altibase 7.3.0.0.7 | 707개(KEYWORD 489) | 704개(KEYWORD 486) | 706개(KEYWORD 488) | `SHARD`(ShardSphere의 예약어) | 없음 |

- 고정 예문(13줄)의 토큰 분류: #115 빌드 대비 차이는 `CREATE SEQUENCE … CACHE 20 NOCACHE` 줄 하나(두 낱말이 이름 → 키워드), 두 서버 같음. 정적 목록 대비 차이는 위 표의 낱말뿐이다.
- 쿼리 창(두 서버)의 `CREATE SEQUENCE … CACHE 20`, `ALTER SEQUENCE … NOCACHE`, `SELECT CACHE, NOCACHE …`에서 `CACHE`·`NOCACHE`가 키워드 색. 연결 새로 고침 뒤에도 같다.
- 따옴표(`getQuotedIdentifier`): 두 서버 모두 `CACHE`·`NOCACHE` → 따옴표 없음, 대소문자 보존(`cache`, `Cache`) → 다른 이름처럼 따옴표. `REPLICATION`은 Altibase에서만,
  `SHARD`는 ShardSphere에서만 따옴표, `SELECT`는 둘 다.
- 대체 경로(문장을 실패시키는 세션으로 새 방언 초기화): 707개(KEYWORD 489)·717개(KEYWORD 502), `CACHE`·`NOCACHE`는 키워드이고 따옴표 없음.
- 키워드 자동 완성은 바뀌지 않는다(위 "자동 완성", 문법 기반 엔진).
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: Altibase 계열 연결의 구문 강조, 이름 따옴표(스크립트 생성, 데이터 편집 SQL).

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
| 결과 집합의 테이블 찾기(`DBUtils.getEntityFromMetaData`, 편집 가능 여부) | 한정하지 않은 `FROM T`는 테이블을 찾지 못함 | 기본 스키마의 `T`에 묶임. 만드는 UPDATE·DELETE는 `USER.T`로 쓴다 | 바뀜. 서버가 한정하지 않은 `T`를 찾는 스키마와 같다. 실행 확인(2026-11-15, 앱 변경 #121과 합친 빌드): 자기 테이블의 편집이 `USER.T`에 써짐. 다른 스키마의 테이블을 자기 테이블과 같은 이름의 별칭으로 읽으면(`OTHER.T2 U`) 이 변경만으로는 그리드가 `USER.U`에 묶였는데, #121(공통 조회가 이름이 주어지면 기본 스키마에서 찾지 않음)이 들어간 뒤에는 `OTHER.T2`에 묶이고 편집도 거기에 써짐. 조인·파생 테이블·WITH는 읽기 전용([swing-frontend.md](swing-frontend.md) "결과 그리드의 테이블 묶기") |
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
- 확인하지 못한 것: `CALL` 매개변수·AI 문맥은 코드로만 봤다(결과 집합 편집 바인딩은 #121에서 실행으로 확인, 위 표). Oracle의 PUBLIC 시노님 완성은 실행하지 않았다. 7.1 이하 서버는 없다.
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번).

### upstream 대비 변경점

- `plugin.xml` 두 드라이버의 `query-get-active-db`, `AltibaseConstants.QUERY_GET_DB_NAME`, `AltibaseDataSource.getDbName`이 그 상수를 씀(필드 `queryGetActiveDB` 지움).

## 테이블스페이스 관리 (2026-11-15, 앱 변경 #126)

### 목적

사용자 요청 "테이블스페이스 관리 기능도 포함되어있나?" → "추가 개발 항목에 포함한다" → "현재 어플리케이션의 UI UX흐름의 관점을 해치지 않게 하고
불필요한 주저리주저리 나열하는 멘트는 넣지 않는다". 전에는 Storage > Tablespaces가 조회만 됐다. Altibase 계열 연결(Altibase, ShardSphere: 같은 모델,
제품 이름 검사 없음)에서 테이블스페이스를 만들고 지우고, 온라인·오프라인으로 바꾸고, 데이터 파일과 자동 확장을 바꾼다. 모든 명령은 기존 흐름 안에 있다:
Object Explorer 메뉴 → (설정 대화상자) → 스크립트 미리 보기(취소가 기본) → 열린 트랜잭션 질문 → 실행 → 트리 갱신. 새 창 종류·마법사·설명 문단은 없다.

### 화면

| 위치 | 메뉴 항목 | 흐름 | 문장 |
|---|---|---|---|
| Storage > Tablespaces 폴더 | New Tablespace… / 새 테이블스페이스 | 이름·종류(메모리·디스크·임시·휘발성)·데이터 파일·크기(MB)·자동 확장·증가 크기·최대 크기 대화상자 → 미리 보기 | `CREATE [MEMORY | VOLATILE | TEMPORARY] TABLESPACE …` |
| 사용자 테이블스페이스 노드 | Delete(기존 명령) | 삭제 확인(확인란: 내용도 삭제, 데이터 파일도 삭제, 종속 개체도 삭제(CASCADE). 모두 꺼짐, 뒤의 둘은 내용을 고를 때만 켜짐) → 미리 보기 | `DROP TABLESPACE t [INCLUDING CONTENTS [AND DATAFILES] [CASCADE CONSTRAINTS]]` |
| 메모리·디스크 데이터 사용자 테이블스페이스 | Bring Online / Take Offline (온라인으로 전환 / 오프라인으로 전환) | 트리거 사용/사용 안 함과 같음: 지금 상태의 항목은 꺼짐, 미리 보기가 확인 | `ALTER TABLESPACE t ONLINE / OFFLINE` |
| 메모리·휘발성 사용자 테이블스페이스 | Autoextend Settings… / 자동 확장 설정 | 지금 값으로 채운 대화상자 → 미리 보기 | `ALTER TABLESPACE t ALTER AUTOEXTEND ON [NEXT n] [MAXSIZE n] / OFF` |
| 디스크 데이터·임시 테이블스페이스의 Data Files 폴더 | New Data File… / 새 데이터 파일 | 파일·크기·자동 확장 대화상자 → 미리 보기 | `ALTER TABLESPACE t ADD DATAFILE / TEMPFILE 'f' [SIZE n] [AUTOEXTEND …]` |
| 그 폴더의 파일 노드 | Data File Settings… / 데이터 파일 설정 | 지금 값으로 채운 대화상자(바뀐 것만) → 미리 보기 | `ALTER TABLESPACE t ALTER DATAFILE / TEMPFILE 'f' SIZE n`, `… AUTOEXTEND …`(두 문장) |
| 그 파일 노드 | Delete(기존 명령) | 삭제 확인 → 미리 보기 | `ALTER TABLESPACE t DROP DATAFILE / TEMPFILE 'f'` |

- 시스템 테이블스페이스(종류 코드 0, 1, 3, 5, 7: 사전, 시스템 데이터, 디스크 시스템 데이터, 시스템 임시, 언두)에는 새 명령이 보이지 않고 Delete는 꺼져 있다
  (Delete는 지울 수 없는 모든 개체에서 꺼진 항목으로 보이는 기존 메뉴다). 이름 목록이 아니라 `V$TABLESPACES.TYPE`으로 판단한다.
- 대화상자의 빈 칸은 문장에서 빠진다(서버 기본값). 메모리·휘발성의 크기는 서버가 요구해서 비울 수 없다. 설정 대화상자에서 아무것도 바꾸지 않으면 속성
  편집과 같은 "실행할 문장이 없다" 알림이다.
- DDL이 세션의 트랜잭션을 커밋하므로 미리 보기 머리글에 "DDL 문을 바로 커밋" 줄이 붙는다(JDBC `dataDefinitionCausesTransactionCommit` = true, 아래 확인).
- 이름 바꾸기는 없다(서버에 문장이 없음).

### 구조

| 위치 | 내용 |
|---|---|
| `ext.altibase` `model/AltibaseTablespaceDDL`(새) | 확인한 문법으로 문장을 만드는 한 곳: 만들기, 삭제, 온라인·오프라인, 자동 확장, 파일 추가·변경·삭제. 크기는 KB(1024 배수면 `M`, 아니면 `K`) |
| `ext.altibase` `edit/AltibaseTablespaceManager`(새) | 객체 관리자: 만들기(데이터 원본 컨테이너만), 삭제(사용자 종류만, 옵션 셋), CREATE 직후 새 행 읽기 |
| `ext.altibase` `edit/AltibaseDataFileManager`(새) | 객체 관리자: 파일 추가·삭제(사용자 디스크 데이터·임시만), 직후 파일·테이블스페이스 행 다시 읽기 |
| `ext.altibase` `AltibaseTablespace`, `AltibaseDataFile`, `AltibaseDataFile4Disk` | 새 객체 생성자, 종류별 지원 판단, 행 다시 읽기(`refreshObject`가 상태·파일 수도 다시 읽음), 자동 확장 읽기(`V$MEM_TABLESPACES`, `V$VOL_TABLESPACES`) |
| `ext.altibase` `plugin.xml`, `bundle_ko.properties` | 관리자 두 개 등록, 한국어 `tree.file.node.name`을 "데이터 파일"로(메뉴가 "새 파일"로 보이지 않게) |
| `model` `DBEObjectMaker`, `DBEObjectManager` | 기능 비트 `FEATURE_DELETE_CONTENTS`, `FEATURE_DELETE_DATA_FILES`와 옵션 키 |
| `model.admin` `ObjectMaintenanceActions` | 동작 `TABLESPACE_ONLINE`, `TABLESPACE_OFFLINE`, `TABLESPACE_AUTOEXTEND`, `DATA_FILE_SETTINGS`(복제 명령과 같은 모양, 격리 문맥에서 실행) |
| `ui.swing` `AltibaseTablespaceDialogs`(새) | 네 대화상자(새 테이블스페이스, 새 데이터 파일, 자동 확장 설정, 데이터 파일 설정). `DesignerDialogs.showDialog` 틀 |
| `ui.swing` `NewObjectDialogs`, `ObjectMaintenanceCommands`, `ExplorerObjectCommands` | 대화상자 연결, 메뉴 네 항목과 실행 뒤 테이블스페이스 노드 다시 읽기, 삭제 확인의 두 확인란과 의존 |
| `ui.swing` `CreateObjectMessages`, `ObjectMaintenanceMessages`, `NavigatorMessages`(+`_ko`) | 화면 문구(아래 "문구") |

### 확인한 문법 (Altibase 7.3.0.0.7 시험 서버, SYS와 임시 사용자 `CHR_TS_USER`, `CHR_TS_*` 테이블스페이스만, 끝에 모두 지움)

| 문장 | 서버의 답 |
|---|---|
| `CREATE TABLESPACE x` / `CREATE DISK DATA TABLESPACE x`(파일 없음) | 구문 오류 200705: 디스크는 `DATAFILE`이 꼭 필요 |
| `CREATE DISK DATA TABLESPACE x DATAFILE 'f.dbf' SIZE 32K` | 69924 "Not even one extent can be created (… Minimum File Size : 67 pages)": 최소 약 536 KB |
| `CREATE TABLESPACE x DATAFILE 'f.dbf' SIZE 4M` | 됨. 상대 경로는 `DEFAULT_DISK_DB_DIR`(`/opt/altibase/dbs/f.dbf`), 종류 4, 페이지 8 KB |
| `CREATE TABLESPACE x DATAFILE 'f.dbf'`(크기 없음) | 됨, 12800 페이지 = 100 MB(`USER_DATA_FILE_INIT_SIZE`) |
| 같은 이름으로 다시 | 201175 "Duplicate tablespace names" |
| `CREATE DATA TABLESPACE …` | 구문 오류 |
| `… AUTOEXTEND ON NEXT 1M MAXSIZE 8M`, `… AUTOEXTEND ON`(NEXT 128, MAX 262143 페이지), `… NEXT 512K`, `DATAFILE 'a' SIZE 2M AUTOEXTEND OFF, 'b' SIZE 1M` | 모두 됨 |
| `… MAXSIZE UNLIMITED` | 201748 "The total size of all disk tablespaces exceeds the maximum limit"(`DISK_MAX_DB_SIZE` 50 GB) |
| `CREATE MEMORY TABLESPACE x`(크기 없음) | 구문 오류 |
| `CREATE MEMORY TABLESPACE x SIZE 1M` | 69870 "must be a multiple of the expand chunk size (… = 4096K)" |
| `CREATE MEMORY [DATA] TABLESPACE x SIZE 4M [AUTOEXTEND ON NEXT 4M MAXSIZE 16M / OFF]` | 됨, 종류 2, 체크포인트 경로 `MEM_DB_DIR` |
| `CREATE VOLATILE [DATA] TABLESPACE x SIZE 4M AUTOEXTEND ON NEXT 4M MAXSIZE 16M` | 됨, 종류 8(크기 규칙은 메모리와 같음) |
| `CREATE [DISK] TEMPORARY TABLESPACE x TEMPFILE 'f.dbf' SIZE 2M [AUTOEXTEND …]` | 됨, 종류 6 |
| `ALTER TABLESPACE d ADD DATAFILE 'f' [SIZE 2M] [AUTOEXTEND ON NEXT 1M MAXSIZE 16M]` | 됨. 같은 파일 이름: 201194 "already in use by another tablespace" |
| `ALTER TABLESPACE d ALTER DATAFILE 'f' SIZE 6M` (상대 이름, 전체 경로 둘 다), `SIZE 1M`(줄이기), `SIZE 4608K` | 됨 |
| `… ALTER DATAFILE 'f' RESIZE 7M` / `… SIZE 8M AUTOEXTEND OFF` | 구문 오류: 크기와 자동 확장은 따로 |
| `… ALTER DATAFILE 'f' AUTOEXTEND OFF / ON / ON NEXT 2M MAXSIZE 32M / ON MAXSIZE 4M` | 됨. NEXT를 빼면 NEXT가 기본(1 MB)으로 돌아감 |
| `ALTER TABLESPACE d DROP DATAFILE 'f'` | 됨. **파일은 서버 디스크에 남음**. 첫 파일: 69686 "The data file is in use." |
| 남은 파일을 다시 `ADD DATAFILE` | 69669 "The data file already exists"; `… SIZE 1M REUSE`는 됨 |
| `ALTER TABLESPACE d OFFLINE / ONLINE` | 됨(STATE 1 / 2, 파일 STATE도). 다시: 69877 / 69876 "already in … mode". 오프라인 중 파일 추가: 69883 "Unable to use an offline tablespace" |
| `ALTER TABLESPACE m OFFLINE / ONLINE`(메모리) | 됨 |
| `ALTER TABLESPACE v/t OFFLINE`(휘발성, 임시) | 201191 "Unable to bring the specified tablespace online or offline." |
| `ALTER TABLESPACE m ALTER AUTOEXTEND ON [NEXT 4M] [MAXSIZE 32M] / OFF`(메모리·휘발성) | 됨. 메모리 `ON`만: NEXT 4 MB, MAX 140737488322560 바이트. `NEXT 3M`: 69857 "multiple of the expand chunk size" |
| `ALTER TABLESPACE m ADD DATAFILE …`(메모리) | 201297 "The tablespace to alter is not a disk tablespace." |
| `ALTER TABLESPACE t ADD / ALTER / DROP TEMPFILE …`(임시) | 됨. `DATAFILE`로 쓰면 201178 "Mismatched tablespace type" |
| `ALTER TABLESPACE x RENAME TO y`, `… RENAME y`, `RENAME TABLESPACE x TO y` | 모두 구문 오류: 이름 바꾸기 없음 |
| `DROP TABLESPACE d`(개체 있음) | 201181 "The tablespace has objects." |
| `DROP TABLESPACE d INCLUDING CONTENTS`(다른 테이블스페이스의 외래 키가 참조) | 201180 "A dependent foreign key constraint was found in another tablespace." |
| `DROP TABLESPACE d INCLUDING CONTENTS AND DATAFILES CASCADE CONSTRAINTS` | 됨(외래 키 제약도 지워짐). 순서를 바꾼 `… CASCADE CONSTRAINTS AND DATAFILES`, `AND DATAFILES`만, `CASCADE CONSTRAINTS`만: 구문 오류 |
| `DROP TABLESPACE x`(파일 옵션 없이, 디스크·임시·메모리) | 됨. **파일(메모리는 `x-0-0`, `x-1-0` 체크포인트 이미지)이 서버 디스크에 남음**. 메모리는 같은 이름으로 다시 만들 수 없음(69669, `REUSE`는 메모리 문법에 없음) |
| 이름 따옴표 `"CHR_TS_Q1"`, `"chr_ts_q2"` | 됨(소문자 이름 보존) |
| 권한 없는 사용자 | 201137 "The user must have CREATE_TABLESPACE / ALTER_TABLESPACE / DROP_TABLESPACE privilege(s) to execute this statement." 세 권한을 주면 만들기·파일 추가·오프라인·삭제가 됨. `V$TABLESPACES`, `V$DATAFILES`는 권한 없이 읽힘 |
| 트랜잭션 | 수동 커밋 세션의 INSERT 뒤 `CREATE TABLESPACE`·`ALTER … OFFLINE`·`DROP TABLESPACE`, 그리고 **실패한** `CREATE`(중복 이름)도 INSERT를 커밋함(롤백해도 행이 남음, 다른 세션에서 보임). JDBC `dataDefinitionCausesTransactionCommit` = true |

- `MEM_MAX_DB_SIZE` 2 GB, `VOLATILE_MAX_DB_SIZE` 4 GB(서버 속성, 읽기만 함). 4 MB 메모리 테이블스페이스 몇 개로는 한도에 닿지 않아 그 오류는 보지 못했다.

### 설계 결정

- **기존 흐름만.** 만들기는 폴더의 New &lt;종류&gt;…(대화상자 길), 삭제는 기존 Delete, 상태·설정 명령은 유지 관리 명령(트리거·복제와 같은 메뉴 자리, 같은 미리 보기·트랜잭션 질문·격리 실행)이다.
- **삭제 옵션 셋, 모두 꺼짐.** 서버 문법이 그대로 확인란이 된다. 파일과 CASCADE는 내용과 함께일 때만 문법이 되므로 내용을 고를 때만 켜진다. 파일을 남기면
  같은 이름을 다시 쓸 수 없다는 것(위 표)은 문구로 늘어놓지 않고 이 문서와 보고에 적었다.
- **문장은 모델 한 곳.** UI는 값만 넘긴다(`AltibaseTablespaceDDL`). 새 객체는 CREATE/ADD 직후 같은 세션에서 자기 행을 읽어 ID·종류·전체 경로를 받는다
  (읽지 않으면 새 노드의 ID가 0이라 Files가 사전 테이블스페이스의 것을 읽는다).
- **새로 고침.** 테이블스페이스 노드의 새로 고침이 캐시만 비우던 것을 자기 카탈로그 행도 다시 읽게 했다(상태·파일 수). Tablespaces 폴더의 F5는 연결 전체를
  다시 읽으므로 명령 뒤에는 그 테이블스페이스 노드만 다시 읽는다.
- **크기 단위 MB.** 칸 이름에 "(MB)". 소수도 받고(1 KB 단위), 서버가 거절하는 값(4 MB 배수 아님 등)은 서버 오류로 보인다.
- **시스템 테이블스페이스의 파일·자동 확장은 넣지 않음.** 공유 시험 서버에서 시스템 테이블스페이스를 바꿀 수 없어 확인하지 못했다.

### 확인 (2026-11-15, 이 브랜치 빌드, 사설 작업 공간, 앱 안 테스트 에이전트, 연결은 암호를 저장하지 않음)

| 명령 | 미리 보기의 문장 | 서버 결과(`V$TABLESPACES`·`V$DATAFILES`·`V$MEM/VOL_TABLESPACES`) | 트리 |
|---|---|---|---|
| New Tablespace… 디스크 | `CREATE TABLESPACE CHR_TS_APP_D1 DATAFILE 'chr_ts_app_d1_01.dbf' SIZE 4M AUTOEXTEND ON NEXT 1M MAXSIZE 16M` | 종류 4, 512/128/2048 페이지, 자동 확장 1 | 노드가 생기고 Data Files에 서버의 전체 경로 |
| 메모리 / 휘발성 / 임시 | `CREATE MEMORY TABLESPACE … SIZE 4M`, `CREATE VOLATILE TABLESPACE … SIZE 4M AUTOEXTEND ON NEXT 4M MAXSIZE 8M`, `CREATE TEMPORARY TABLESPACE … TEMPFILE '…' SIZE 2M` | 종류 2, 8, 6 | 노드 생김 |
| 대화상자에서 취소 / 미리 보기에서 취소 | — / 문장만 보임 | 바뀌지 않음 | 바뀌지 않음 |
| 빈 이름, 디스크 파일 없음, 메모리 크기 없음 | 대화상자 오류 줄 "Enter a name." / "Enter the data file name (without quotes)." / "Enter the sizes in MB." | — | — |
| Take Offline / Bring Online(디스크, 메모리) | `ALTER TABLESPACE … OFFLINE / ONLINE` | STATE 1 / 2 | 지금 상태의 항목이 꺼짐 |
| Autoextend Settings…(메모리 켜기, 휘발성 끄기, 바꾸지 않고 OK) | `… ALTER AUTOEXTEND ON NEXT 4M MAXSIZE 12M`, `… ALTER AUTOEXTEND OFF`, 없음 | 4194304/12582912, 0 | 바꾸지 않으면 "문장 없음" 알림 |
| New Data File…(오프라인 테이블스페이스) | `ALTER TABLESPACE … ADD DATAFILE 'chr_ts_app_d1_02.dbf' SIZE 2M` | 69883 "Unable to use an offline tablespace" 오류 창 | 바뀌지 않음 |
| New Data File…(온라인, 임시) | `… ADD DATAFILE '…' SIZE 2M AUTOEXTEND ON NEXT 1M MAXSIZE 8M`, `… ADD TEMPFILE '…' SIZE 1M` | 파일 추가 | 파일 노드(전체 경로) |
| Data File Settings…(크기+자동 확장, 자동 확장 끄기, 임시 파일 크기) | 두 문장 `… ALTER DATAFILE '…' SIZE 3M` + `… AUTOEXTEND ON NEXT 1M MAXSIZE 10M`, `… AUTOEXTEND OFF`, `… ALTER TEMPFILE '…' SIZE 2M` | 바뀜 | 다시 열면 새 값 |
| Delete 파일 | `ALTER TABLESPACE … DROP DATAFILE / TEMPFILE '…'` | 행 없어짐(파일은 디스크에 남음) | 노드 빠짐. 확인 문구의 파일 이름에 따옴표가 두 번 붙던 것을 고침(`AltibaseDataFile.getFullyQualifiedName`) |
| Delete 테이블스페이스(옵션 없음, 테이블 있음) | `DROP TABLESPACE CHR_TS_APP_D1` | 201181 "The tablespace has objects." 오류 창 | 바뀌지 않음 |
| Delete(내용+파일, 다른 테이블스페이스의 외래 키) | `… INCLUDING CONTENTS AND DATAFILES` | 201180 오류 | 바뀌지 않음 |
| Delete(내용+파일+CASCADE), 휘발성(내용+파일) | `… INCLUDING CONTENTS AND DATAFILES CASCADE CONSTRAINTS`, `… INCLUDING CONTENTS AND DATAFILES` | 지워짐, 테이블과 외래 키도 | 노드 빠짐 |
| 같은 이름으로 New Tablespace | — | 모델의 확인 "Object 'CHR_TS_APP_M1' already exists"(서버에 가기 전, 기존 공통 동작) | 바뀌지 않음 |
| 권한 없는 사용자(`CHR_TS_USER`) | `CREATE TABLESPACE …` | 201137 "The user must have CREATE_TABLESPACE privilege(s)…" 오류 창 | 바뀌지 않음 |
| 읽기 전용 연결 | — | New Tablespace, Take Offline, Delete 모두 "ALTI does not allow changing database objects…" | — |

- 열린 트랜잭션(수동 커밋 연결, 쿼리 창의 `INSERT`): Take Offline → "Uncommitted Transactions" 질문(취소가 기본). 취소 → 아무것도 하지 않음, 롤백 → INSERT 없어지고 오프라인,
  커밋 → INSERT가 남고 온라인(`CHR_TS_TX`의 행으로 확인).
- 미리 보기 머리글에 "This database commits each DDL statement at once; the change cannot be rolled back." 줄이 붙는다.
- 메뉴: 시스템 테이블스페이스 노드에는 새 항목이 없고 Delete가 꺼짐. 휘발성은 Autoextend Settings…만, 임시는 Delete만, 메모리는 온라인·오프라인·자동 확장.
- 화면: 영어·한국어, 라이트·다크, 100 %·150 %에서 폴더·노드 메뉴, 네 대화상자, 미리 보기, 삭제 확인, 트리를 캡처했다.
- ShardSphere 5.1.9(읽기만): `V$TABLESPACES` 다섯 행(MEM_DIC 0, MEM_DATA 1, DISK_DATA 3, DISK_UNDO 7, DISK_TEMP 5)의 종류 코드가 Altibase와 같다. 테이블 108개 중 86개 종류 0, 22개
  종류 1, SYS의 22개는 모두 MEM_DATA(1)이고 트리에서 모두 메모리 빨강(라이트 `#B3261E`, 다크 `#FF9999`, 툴팁 "Memory table"). 사용자 테이블스페이스가 없어 그 서버의 노드에는 새 항목이
  없고(Delete 꺼짐) Tablespaces 폴더에만 New Tablespace…가 보인다. 대화상자와 실행은 열지 않았다.
- 같은 빌드에서 PostgreSQL New Tablespace…(미리 보기 `CREATE TABLESPACE … OWNER postgres LOCATION '…'`, 취소), 트리거 사용 안 함/사용, 테이블 이름 바꾸기·삭제(확인란은 CASCADE 하나)가 전과 같다.
- 찾은 범위 밖 문제(고치지 않음): 수동 커밋 연결에서 객체 변경(`AbstractCommandContext.saveChanges`)이 실패하면 저장 경로가 롤백을 쿼리 관리자 기록 없이 하므로
  (`session.enableLogging(false)`) 다음 변경 때 "ALTI (Main) - open transaction with data changes" 질문이 한 번 나온다. 서버 세션의 `V$TRANSACTION.UPDATE_STATUS`는 0이었다.
- 회귀 테스트는 작성·실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: 모든 DB의 삭제 확인(옵션 순서), 유지 관리 메뉴, Altibase 테이블스페이스 노드 새로 고침.

### upstream 대비 변경점

- upstream은 Altibase 테이블스페이스를 조회만 한다. 객체 관리자 두 개, 문장 클래스, 모델의 새 객체·다시 읽기, 삭제 기능 비트 두 개를 더했다.

## 변경 이력

- 2026-11-07 최초 작성: Object Explorer 테이블 저장 방식 표시(메모리 빨강, 디스크 파랑, 휘발성 호박색, 하이브리드), 목록 질의 변경.
- 2026-11-07 한국어 표시: "Partitioned"(분할됨), "Materialized View(s)"(구체화된 뷰) 번역 추가(후속 과제 #61), 위 "한국어 표시".
- 2026-11-08 복제 상태(Activity Monitor 복제 탭)와 복제 시작·중지·동기화 명령(SWT 감사 D-A7), 위 "복제 시작·중지·동기화".
- 2026-11-14 Altibase 계열 드라이버 ShardSphere 추가와 서버 출력 콜백을 메서드 매개변수로 찾게 바꿈(앱 변경 #113), 위 "Altibase 계열 드라이버: ShardSphere".
- 2026-11-14 복제 카탈로그가 없는 서버의 복제 목록을 빈 목록으로, JDBC 3.0 드라이버의 함수 매개변수(앱 변경 #113 검토 뒤).
- 2026-11-15 엔진 키워드를 연결한 서버의 `V$RESERVED_WORDS`에서 읽음(읽지 못하면 정적 목록), `RESERVED_TYPE`의 뜻과 `DBMS_SHARD` 자동 완성 조사(앱 변경 #115), 위 "서버 예약어".
- 2026-11-15 예약어가 아닌 문법 낱말(`CACHE`, `NOCACHE`)을 서버와 관계없이 키워드로 두고 이름의 따옴표 이유에서는 뺌(앱 변경 #118), 위 "예약어가 아닌 문법 낱말".
- 2026-11-15 실행 문맥의 기본 스키마를 세션 사용자로 읽음(`query-get-active-db` = `USER_NAME()`, 읽기 전용), 데이터베이스 이름은 따로 읽음(앱 변경 #116), 위 "세션의 기본 스키마".
- 2026-11-15 테이블스페이스 관리(앱 변경 #126): 만들기·삭제(내용·데이터 파일·CASCADE)·온라인/오프라인·자동 확장·데이터 파일 추가·설정·삭제, 확인한 문법, 위 "테이블스페이스 관리".
