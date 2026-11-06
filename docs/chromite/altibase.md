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

## 변경 이력

- 2026-11-07 최초 작성: Object Explorer 테이블 저장 방식 표시(메모리 빨강, 디스크 파랑, 휘발성 호박색, 하이브리드), 목록 질의 변경.
