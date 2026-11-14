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

## 변경 이력

- 2026-11-07 최초 작성: Object Explorer 테이블 저장 방식 표시(메모리 빨강, 디스크 파랑, 휘발성 호박색, 하이브리드), 목록 질의 변경.
- 2026-11-07 한국어 표시: "Partitioned"(분할됨), "Materialized View(s)"(구체화된 뷰) 번역 추가(후속 과제 #61), 위 "한국어 표시".
- 2026-11-08 복제 상태(Activity Monitor 복제 탭)와 복제 시작·중지·동기화 명령(SWT 감사 D-A7), 위 "복제 시작·중지·동기화".
- 2026-11-14 Altibase 계열 드라이버 ShardSphere 추가와 서버 출력 콜백을 메서드 매개변수로 찾게 바꿈(앱 변경 #113), 위 "Altibase 계열 드라이버: ShardSphere".
- 2026-11-14 복제 카탈로그가 없는 서버의 복제 목록을 빈 목록으로, JDBC 3.0 드라이버의 함수 매개변수(앱 변경 #113 검토 뒤).
