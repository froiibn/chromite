# Altibase

Altibase 지원(`plugins/com.seizia.chromite.ext.altibase`) 중 Chromite에서 바꾼 부분을 적는다.

## 테이블 저장 방식 표시 (Object Explorer)

### 목적

Altibase 테이블은 메모리 테이블스페이스(행을 메모리에 두고 체크포인트로 디스크에 씀), 디스크 테이블스페이스,
휘발성 테이블스페이스(메모리에만 두고 서버를 다시 시작하면 없어짐) 중 한 곳에 있다. 같은 SQL이라도 저장 방식에
따라 성능과 운영 방법이 달라서, Altibase 전용 도구(참고: "Orange for Altibase")는 트리에서 메모리 테이블을 빨강,
디스크 테이블을 파랑으로 구분한다. 사용자 요청(2026-11-07)에 따라 Swing Object Explorer에서도 같은 구분을 한다.

2026-11-15 사용자 요청(앱 변경 #130, ShardSphere 연결을 보고): "글자색이 아닌 테이블 아이콘이 붉은 계열로 나타나야 한다. 일반 disk테이블은
일반 테이블처럼 나타나게 한다". 그래서 이름 글자색은 없애고, 메모리·휘발성 테이블의 **테이블 아이콘**을 붉은 계열로 그린다.
디스크 테이블은 보통 테이블과 똑같이 보인다.

### 화면

| 저장 방식 | 테이블 아이콘 선 색 (Light / Dark) | 이름 글자 | 툴팁 (영어 / 한국어) | 속성 "Storage Type" |
|---|---|---|---|---|
| 메모리 | 빨강 `#D11A1A` / `#FF5C5C` | 보통 | Memory table / 메모리 테이블 | Memory / 메모리 |
| 휘발성 | 주황빨강 `#C04A00` / `#FF8C42` | 보통 | Volatile table / 휘발성 테이블 | Volatile / 휘발성 |
| 디스크 | 보통 테이블 아이콘 | 보통 | 저장 방식 없음(주석만, 다른 테이블과 같음) | Disk / 디스크 |
| 하이브리드(파티션마다 다름) | 보통 테이블 아이콘 | 보통 | Hybrid table / 하이브리드 테이블 | Hybrid / 하이브리드 |

- 메모리·휘발성·하이브리드 테이블에 주석이 있으면 툴팁은 `Memory table: <주석>`처럼 저장 방식 뒤에 주석을 붙인다. 디스크 테이블의 툴팁은 다른 테이블처럼 주석만이다.
- 아이콘은 팩(Duo-tone, Full color)의 테이블 그림 그대로이고 선과 강조 채움(Full color의 머리 띠)만 위 색이다. 칸의 밝은 채움은 팩의 색이다.
- 색만으로는 구분할 수 없는 사용자를 위해 툴팁과 Properties 창(속성 "Storage Type", 한국어 "스토리지 유형")이 같은 값을 말로 보여 준다.
- 새 화면 문구는 없다. 화면 문구에 다른 제품 이름은 쓰지 않는다.

### 구조

| 위치 | 내용 |
|---|---|
| `com.seizia.chromite.model` `struct/rdb/DBSTableStorageKind` | 저장 방식 enum(MEMORY, DISK, VOLATILE, HYBRID). `DBPNamedObject`라 속성 창이 지역화된 이름(`ModelMessages.model_struct_storage_*`)을 보여 준다 |
| `com.seizia.chromite.model` `struct/rdb/DBSTableWithStorageKind` | 저장 방식을 아는 테이블. 화면은 이 인터페이스만 본다 |
| `AltibaseMetaModel.prepareTableLoadStatement` | 테이블 목록을 카탈로그 질의로 읽고 테이블스페이스 종류를 같은 질의에서 받는다 |
| `AltibaseTable` | 생성자에서 테이블스페이스 이름, 파티션 여부, 저장 방식을 받아 둔다. `@Property getStorageKind()`(order 17) |
| `AltibaseTablespace.TbsType.getStorageKind()` | `V$TABLESPACES.TYPE` 코드 → 저장 방식 |
| `com.seizia.chromite.ui.swing` `TableStorageStyle` | 렌더러가 부르는 아이콘·툴팁 처리. `ObjectExplorerPanel.ExplorerCellRenderer`에는 호출 한 줄만 있다(`NodeIcons.getNodeIcon`이 정한 아이콘을 메모리·휘발성일 때 바꿈). 색 아이콘 둘은 처음 쓸 때 한 번 만들어 둔다 |
| `com.seizia.chromite.ui.swing` `UiIcons.inLineColor` | 팩 아이콘의 선(`stroke`)과 `<rect>`가 아닌 도형의 채움을 테마 색으로 바꾼 SVG 문서. JSVG 읽기 전처리(`DomProcessor`)로 바꾸고 팩·테마·색마다 캐시한다. 아이콘은 마지막으로 그린 팩·테마·색의 문서를 들고 있어 같은 줄을 다시 그릴 때 읽기나 키 만들기가 없다 |
| `ChromiteLightLaf.properties`, `ChromiteDarkLaf.properties` | 색 토큰 `Chromite.Tree.storageMemoryIconColor`, `storageVolatileIconColor`. 비활성 트리는 `Label.disabledForeground`(text.disabled) |
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
  하이브리드는 한 색으로 말할 수 없으므로 아이콘을 바꾸지 않고 툴팁·속성으로만 알린다.
- **글자색 대신 아이콘(2026-11-15, 앱 변경 #130).** 사용자 요청이다. 이름은 다른 노드와 같은 글자(선택·기본 굵게 규칙 그대로)이고,
  저장 방식은 아이콘 색이 말한다. 디스크 테이블은 보통 테이블로 둔다(아이콘·글자·툴팁 모두). 대부분의 테이블이 디스크이므로
  예외인 메모리·휘발성만 눈에 띈다.
- **두 톤.** 메모리 빨강(색상 0°), 휘발성 주황빨강(색상 23°). 휘발성 테이블은 메모리에 있지만 재시작하면 데이터가 없어진다.
  같은 빨강이면 이 차이를 놓치므로 붉은 계열 안에서 16px로도 구분되게 색상을 23° 띄웠다(밝기는 비슷하고 색상으로 구분된다:
  두 색끼리 명암비 Light 1.09, Dark 1.31). 보통 테이블 아이콘은 회색 선(Duo-tone `#6C707E`/`#CED0D6`, 색상 227°)이라 두 톤 모두 색상으로 뚜렷이 다르다.
- **아이콘을 칠하는 방법.** 팩에는 테마 색으로 아이콘을 칠하는 `UiIcons.inColor`가 있지만 아이콘의 불투명한 픽셀을 모두 칠하는
  평평한 모양이라, 테이블 아이콘은 밝은 칸까지 칠해져 꽉 찬 빨간 사각형이 된다(칸과 격자가 없어짐). 그래서 팩이 빨간 아이콘을 그리는
  방식(생성기 `PAL`의 `rs` 선 + 밝은 채움)처럼 선만 칠한다: 읽을 때 SVG의 `stroke`와 `<rect>`가 아닌 도형의 `fill`을 토큰 색으로 바꾼다.
  생성기의 `table_shape`는 몸통을 `<rect>`(밝은 칸 채움)로, 머리 띠·선을 `<path>`로 그리므로 Duo-tone은 빨간 윤곽·격자 + 회색 칸,
  Full color는 빨간 머리 띠·윤곽·격자 + 밝은 칸이 된다. 새 이미지 파일은 더하지 않았다.
- **선택한 줄.** 팩 규칙대로 선택한 줄에서도 아이콘 색을 바꾸지 않는다. 그래서 토큰 값을 트리 바탕, 활성 선택 바탕, 비활성 선택 바탕
  모두에서 그래픽 대비 3:1 이상(WCAG 2.1 SC 1.4.11)으로 골랐다.

  | 토큰 | 테마 | 값 | 트리 바탕 | 활성 선택 | 비활성 선택 |
  |---|---|---|---|---|---|
  | `storageMemoryIconColor` | Light | `#D11A1A` | 5.42 | 4.04 | 4.51 |
  | `storageVolatileIconColor` | Light | `#C04A00` | 4.98 | 3.71 | 4.14 |
  | `storageMemoryIconColor` | Dark | `#FF5C5C` | 5.75 | 3.24 | 4.05 |
  | `storageVolatileIconColor` | Dark | `#FF8C42` | 7.53 | 4.24 | 5.30 |

  바탕: Light `#FFFFFF`/`#D0DFFE`/`#E9EAEE`, Dark `#191A1C`/`#2A4371`/`#33353B`. 처음 후보였던 Dark `#FF6B6B`(활성 선택 3.54)는 분홍에 가까워
  주황빨강과의 차이가 작아 `#FF5C5C`로 바꿨다. 이전 글자색 토큰 세 개(`Chromite.Tree.storage*Foreground`)는 지웠다(다른 곳에서 읽지 않음).
- **비활성 트리.** `inColor`와 같이 비활성 컴포넌트에서는 비활성 색 키 `Label.disabledForeground`(text.disabled, 회색)로 칠한다. 밝은 빨강이 비활성 트리에 남지 않는다.
  여기에 팩의 비활성 아이콘과 같은 투명도 0.4를 더했다: 첫 빌드의 캡처에서 회색을 불투명하게 칠한 아이콘이 옆의 흐린(0.4) 비활성 테이블 아이콘보다 진해 보였다.
- **캐시.** 색 아이콘 객체는 `TableStorageStyle`이 처음 쓸 때 한 번 만든다. 색을 바꾼 SVG 문서는 `UiIcons`가 팩·테마·색마다 한 번 읽어 캐시하고,
  아이콘은 마지막 문서를 들고 있어 같은 테마에서는 그릴 때 아무것도 만들지 않는다(그리는 일은 보통 팩 아이콘과 같은 JSVG 벡터 렌더링).
  색은 그릴 때 토큰에서 읽으므로 테마·아이콘 팩을 실행 중에 바꿔도 다시 그리기만으로 맞다.
- **화면은 DBMS를 모른다.** 렌더러에 Altibase 분기를 넣지 않고 모델의 `DBSTableWithStorageKind`만 본다. SQL Server
  메모리 최적화 테이블, MySQL MEMORY 엔진 같은 경우도 그 인터페이스를 구현하면 같은 표시를 얻는다(이번 작업 범위 밖).
- **큐는 대상이 아니다.** `AltibaseQueue`는 `AltibaseTable`이 아니라 `GenericTable`을 상속하고, 큐는 항상 메모리 테이블이라
  색을 붙이지 않았다.
- **Object Explorer만 보인다(2026-11-15 조사, 바꾸지 않음).** `TableStorageStyle`을 부르는 곳은 Object Explorer 렌더러 하나다.
  테이블 아이콘을 그리는 다른 곳은 모두 팩의 보통 `table` 아이콘(`NodeIcons`가 `TREE_TABLE` 등을 `table`로 대응)을 보인다:
  Find Objects 결과(`ObjectSearchPanel`), Object Details 제목·편집기 탭·자식 목록(`ObjectDetailsPanel`), 데이터 전송 객체 목록
  (`DataTransferObjects`), 내보내기 마법사 객체 표(`DataExportWizard`), 가져오기 대상 목록(`DataImportTargetPage`), 데이터베이스 복사
  (`DatabaseCopyPages`), SQL 자동 완성 목록(`SqlCompletionPopup`), 스키마 비교 트리(`SchemaComparePanel`, 비교 종류로 고름), ER 다이어그램
  엔터티 머리(`ErdRenderer`), 데이터 찾기 결과(`DataSearchPanel`, 늘 `table`). Registered Servers와 객체 고르기(Select Schema)는 테이블을
  보이지 않는다. 테이블스페이스 아래 Tables 폴더의 항목은 `AltibaseTablespaceObj4Table`(테이블이 아닌 테이블스페이스 내용 행)이라 탐색기에서도
  보통 `table` 아이콘이다. 다른 곳도 따를지는 사용자 결정을 기다린다.

### 확인 (2026-11-15, 앱 변경 #130)

- 테스트 Altibase 7.3: 이 작업용 테이블스페이스 `CHR_IC_MEM`(메모리 4M, 서버 최소), `CHR_IC_DISK`(디스크 4M), `CHR_IC_VOL`(휘발성 4M), 사용자 `CHR_IC_USER`와
  테이블 `CHR_IC_T_MEM`(주석 있음)·`CHR_IC_T_DISK`·`CHR_IC_T_VOL`·`CHR_IC_T_HYB`(파티션 P1 메모리, P2 디스크)·뷰 `CHR_IC_V`를 SQL로 만들었다
  (새 사용자는 `ALTER USER … ACCESS <테이블스페이스> ON` 없이는 그 테이블스페이스에 테이블을 만들 수 없었다). 끝에 사용자(CASCADE)와 테이블스페이스
  (`INCLUDING CONTENTS AND DATAFILES`)를 지우고 `V$TABLESPACES`, `V$DATAFILES`, `SYS_TABLES_`, `SYS_USERS_`에 남은 것이 없음을 확인했다.
- 앱(테스트 에이전트, 영어·한국어 × 100 %·150 % × 밝은·어두운, Duo-tone과 Full color): 메모리는 빨간 아이콘, 휘발성은 주황빨강 아이콘, 디스크·하이브리드는
  보통 아이콘, 이름 글자는 모두 보통 색이다. 선택(초점 있음·없음)에서도 아이콘 색이 같다. 툴팁: 디스크는 없음, 하이브리드 "Hybrid table",
  메모리 "Memory table: Order cache", 휘발성 "Volatile table"(한국어 "하이브리드 테이블", "메모리 테이블: Order cache", "휘발성 테이블").
  실행 중 테마·아이콘 팩 전환, 연결 F5, 연결 끊기·다시 연결 뒤에도 같다. 비활성 트리에서는 회색(투명도 0.4)으로 이웃 아이콘과 같은 흐림이다.
- ShardSphere 서버(읽기만, 연결 하나): SYS > Tables의 22개 테이블이 모두 메모리라 빨간 아이콘과 보통 글자로 보인다(밝은·어두운).
- PostgreSQL 테이블 노드는 보통 아이콘 그대로다.
- 회귀 테스트는 작성·실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: Object Explorer의 테이블 아이콘·툴팁, 아이콘 팩 문서 읽기(`UiIcons.getDocument`).

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
  2026-11-17부터 이 문장들은 연결의 복제 카탈로그가 준다(Altibase는 같은 문장, 아래 "복제 카탈로그"). 카탈로그가 제어 문장을 허락하지 않는 복제에는 세 명령이 없다.

### 설계 결정

- **원격 서버에 영향을 주므로 한 번 더 묻는다.** 다른 유지 관리 명령은 미리 보기가 확인이지만, 복제 명령은 TRUNCATE처럼 미리 보기 전에 무엇이 일어나는지 적은 확인을 둔다(브리프 요구).
- **상태 다시 읽기는 노드 새로 고침.** 노드 새로 고침(`refreshObject` → 복제 캐시 다시 읽기)으로 시작 여부와 송신·수신 목록을 맞춘다.
  모델 `AltibaseReplication.refreshObjectState`는 2026-11-17부터 같은 카탈로그 행을 읽지만(아래 "상태 다시 읽기") 송신·수신 목록은 읽지 않는다.
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
- 대화상자는 메인 창 가운데에 뜨고 폼 너비가 480 이상이다(2026-11-16 앱 변경 #132, 네 대화상자 모두 같은 너비). 크기·증가 크기·최대 크기 칸에는 숫자와
  마침표 하나만 들어간다(앱 변경 #133). 둘 다 [swing-create-objects.md](swing-create-objects.md) "대화상자 크기와 위치", "숫자 칸".

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
- 시험 서버 정리: 테이블스페이스 `CHR_TS_APP_D1`·`CHR_TS_APP_T1`·`CHR_TS_APP_M2`는 `INCLUDING CONTENTS AND DATAFILES CASCADE CONSTRAINTS`로, 테이블
  `SYS.CHR_TS_TREF`와 임시 사용자 `CHR_TS_USER`(CASCADE)는 SQL로 지웠다. SQL로 지울 수 없는 남은 파일 여섯 개는 컨테이너 안에서 이름을 하나씩 적어
  `docker exec … rm`으로 지웠다(와일드카드 없음): `/opt/altibase/dbs/CHR_TS_M2-0-0`, `/opt/altibase/dbs/CHR_TS_M2-1-0`, `/opt/altibase/dbs/CHR_TS_M4-0-0`,
  `/opt/altibase/dbs/CHR_TS_M4-1-0`(파일 옵션 없이 지운 메모리 테이블스페이스의 체크포인트 이미지, 문법 확인 중 생김), `/opt/altibase/dbs/chr_ts_app_d1_02.dbf`,
  `/opt/altibase/dbs/chr_ts_app_t1_02.dbf`(앱의 Delete로 `DROP DATAFILE`/`DROP TEMPFILE`한 파일). 그 뒤 `V$TABLESPACES`는 시스템 다섯 개,
  `V$DATAFILES`는 `system001.dbf`·`undo001.dbf`·`temp001.dbf`뿐이다. 앞으로는 남은 파일을 지우지 않고 보고한다(검토 뒤 지시).
- 새 파일의 행 찾기(검토 뒤, 임시 테이블스페이스 `CHR_TS_ID1`, 끝에 파일과 함께 지움): 파일 ID 0, 1, 2에서 2를 지우고 더하면 3, 1을 지우고 더하면 4, 3과 4를 지우고
  더하면 5였다. 이 서버에서는 ID가 다시 쓰이지 않고 늘기만 했지만 서버를 다시 시작한 뒤에도 그런지는 확인할 수 없다. 그래서 `AltibaseDataFile4Disk.readCreated`는
  가장 큰 ID가 아니라 입력한 이름으로 행을 찾는다(서버가 붙인 전체 경로가 입력한 이름과 같거나 경로 구분자 + 입력한 이름으로 끝나는 행).
- 검토 뒤 화면: 새 테이블스페이스·새 데이터 파일 대화상자의 "(기본값)" 안내 줄을 뺐다(설명 문구 없음). 데이터 파일 설정의 파일 경로 칸은 초점을 받지 않고 처음 초점이 크기 칸이다.
- 찾은 범위 밖 문제(고치지 않음): 수동 커밋 연결에서 객체 변경(`AbstractCommandContext.saveChanges`)이 실패하면 저장 경로가 롤백을 쿼리 관리자 기록 없이 하므로
  (`session.enableLogging(false)`) 다음 변경 때 "ALTI (Main) - open transaction with data changes" 질문이 한 번 나온다. 서버 세션의 `V$TRANSACTION.UPDATE_STATUS`는 0이었다.
- 회귀 테스트는 작성·실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: 모든 DB의 삭제 확인(옵션 순서), 유지 관리 메뉴, Altibase 테이블스페이스 노드 새로 고침.

### upstream 대비 변경점

- upstream은 Altibase 테이블스페이스를 조회만 한다. 객체 관리자 두 개, 문장 클래스, 모델의 새 객체·다시 읽기, 삭제 기능 비트 두 개를 더했다.

## ShardSphere에서 다른 문장 (2026-11-17, 앱 변경 #139 1단계)

### 목적

조회 전용 조사(실제 ShardSphere 5.1.9 서버에서 Chromite 문장 74개를 준비만 함)에서 64개는 그대로 되고 10개가 실패했다. 8개는 복제(ShardSphere 이름 GEMINI,
다음 단계)이고, 나머지 두 문장과 DBMS_METADATA 서브프로그램 두 개, 보호되지 않은 경로 하나를 여기서 고쳤다. 원칙: 두 서버가 모두 받는 한 문장을 쓰고, 제품 이름으로 가르지 않는다.
서버마다 다른 것은 서버 카탈로그에서 연결마다 읽어 정한다.

### 고친 것

| 기능 | 전 | ShardSphere 결과 | 후 | 두 서버 확인 |
|---|---|---|---|---|
| 활동 모니터 ▸ 잠금(대기) | 대기 시간의 UTC 현재 시각을 `UNIX_DATE`로 | `UNIX_DATE` 없음(200792, 42S22): 새로 고칠 때마다 오류 | `CONV_TIMEZONE(SYSDATE, DB_TIMEZONE(), '+00:00')` (`AltibaseBlockingLockManager`) | 아래 "UTC 현재 시각" |
| 디스크 데이터 테이블스페이스 크기 | 열 별칭 `'USED_SIZE'`(작은따옴표) | 구문 오류(200705, 42000): 사용·남은 크기 빈칸 | 따옴표 없는 `USED_SIZE` (`AltibaseTablespace`) | Altibase 7.3도 받고 값 같음(DISK_DATA 2G / 1M), ShardSphere 2G / 0 |
| 테이블 DDL의 COMMENT | `SELECT dbms_metadata.get_dependent_ddl(?, ?, ?) FROM DUAL`, 앞뒤로 `set_transform_param` | 두 서브프로그램이 패키지에 없음 | 있을 때만 부름(`AltibaseDataSource.hasDbmsMetadataSubprogram`), 없으면 같은 모양의 COMMENT 문을 `SYSTEM_.SYS_COMMENTS_`에서 만듦(`AltibaseMetaModel.getCommentDDLFromCatalog`) | 아래 "테이블 주석" |
| 이름으로 복제 찾기(`getChild` → `getReplication`) | 보호 없음 | 표 없음 오류(200753, 42S02)가 사용자에게 | 목록(`getReplications`)과 같은 판단(`isReplicationCatalogMissing`)으로 `null` | ShardSphere `null`, Altibase 7.3 `null`(복제 없음, 전과 같음) |

- **같은 원인의 다른 문장:** 플러그인 전체와 `model.admin`의 Altibase 문장에서 작은따옴표 열 별칭은 이 하나뿐이었다(소스 검색과 조사의 문장 목록).
  `UNIX_DATE`는 잠금 대기 질의에만 있었다(방언 함수 목록은 강조용이라 그대로).
- **통계 수집(`AltibaseDataSource.collectObjectStatistics`):** 테이블스페이스 하나가 실패하면 반복이 멈추는 구조지만, 이 메서드를 부르는 곳이 Swing·모델 어디에도 없다
  (검색 결과 구현만 있음). 목록의 크기는 테이블스페이스마다 따로 읽으므로(`getAvailableSize`/`getUsedSize`) 한 종류의 실패가 다른 행을 막지 않는다. 고치지 않았다.

### UTC 현재 시각

`V$STATEMENT.QUERY_START_TIME`은 두 서버 모두 Unix 초다(자기 문장의 값이 클라이언트 시계와 1초 안). Altibase 7.3에서 `SYSDATE`는 데이터베이스 시간대의 시각이고
세션 시간대와 관계없다(`ALTER SESSION SET TIME_ZONE = '-05:00'` 뒤에도 `SYSDATE` = `UNIX_DATE`, `CURRENT_DATE`만 5시간 뒤). 그래서
`CONV_TIMEZONE(SYSDATE, DB_TIMEZONE(), '+00:00')`이 UTC다.

| 서버 | `DB_TIMEZONE()` | 옛 식(`UNIX_DATE`) | 새 식 | 클라이언트 시계 |
|---|---|---|---|---|
| Altibase 7.3 (시험 컨테이너, `TIME_ZONE` = `OS_TZ`) | +00:00 | 1794846272 | 1794846272 | 1794846272 |
| 같은 세션, 세션 시간대 -05:00 | +00:00 | 1794846273 | 1794846273 | 1794846273 |
| ShardSphere 5.1.9 | +09:00 | 없음 | 1794846405 | 1794846405 |

시험 컨테이너의 데이터베이스 시간대가 UTC라 Altibase에서 시간대 변환 자체는 ShardSphere(+09:00)에서만 확인됐다. 데이터베이스 시간대를 바꾸는 것은
서버 설정 변경이라 하지 않았다.

### 테이블 주석

- **DBMS_METADATA 서브프로그램(`SYS_PACKAGE_PARAS_`):** Altibase 7.3은 GET_DDL, GET_DEPENDENT_DDL, GET_GRANTED_DDL, GET_STATS_DDL, GET_USER_DDL,
  SET_TRANSFORM_PARAM, SHOW_TRANSFORM_PARAMS. ShardSphere 5.1.9는 GET_DDL, GET_DDL_TABLE, VERSION과 질의 문장을 돌려주는 GET_*_QUERY 여덟 개
  (GET_COMMENT_QUERY 포함)로, GET_DEPENDENT_DDL·SET_TRANSFORM_PARAM이 없다.
- **연결마다 판단:** 메타 모델은 같은 드라이버의 모든 연결이 함께 쓰므로 목록을 데이터 원본(연결 하나)에 둔다. 처음 쓸 때 한 번 읽고, 연결 새로 고침에서 지운다.
  두 연결을 같은 앱에서 함께 열고 확인: ShardSphere GET_DEPENDENT_DDL·SET_TRANSFORM_PARAM = false, Altibase = true. 끊고 다시 연결한 뒤에도 같다.
- **Altibase에서도 주석이 빠졌다(같은 원인 계열, 이번에 찾음):** 전 코드의 `SELECT dbms_metadata.get_dependent_ddl(?, ?, ?) FROM DUAL`은 Altibase 7.3이
  거절한다(201275 "Invalid use of host variables", SELECT 목록의 함수 인자에 호스트 변수). 오류는 경고로만 남고 DDL에 COMMENT가 없었다(전 캡처).
  같은 파일의 get_ddl처럼 `exec ? := dbms_metadata.get_dependent_ddl(?, ?, ?)`로 부르게 바꿨다. 주석 없는 테이블은 전처럼 990013(0xF1B3D)이고 경고를 남기지 않는다.
- **카탈로그 대체:** `SYSTEM_.SYS_COMMENTS_`(USER_NAME, TABLE_NAME, COLUMN_NAME, COMMENTS)를 SELECT로 읽어 GET_DEPENDENT_DDL과 같은 모양으로 만든다:
  열 주석을 열 이름 순서로, 그다음 테이블 주석, 이름은 큰따옴표, 값의 작은따옴표는 두 번. Altibase 7.3에서 두 테이블(열 순서와 이름 순서가 다른 것 포함)로
  패키지 결과와 글자 하나까지 같음을 확인했다. ShardSphere의 GET_COMMENT_QUERY는 함수 호출이라 그 서버에서 부르지 않았다.
- **ShardSphere에서 보이지 못한 것:** 그 서버에는 주석이 있는 테이블이 없다(주석 36개가 모두 뷰의 테이블 주석). 만들 수 없으므로(조회 전용) 카탈로그 질의가
  그 서버에서 실행됨(0행)만 앱 안에서 확인했다. 또 그 서버에서 테이블 DDL 화면을 열면 `exec ? := dbms_metadata.get_ddl(...)`(EXEC)이 실행되므로 열지 않았다.
  ShardSphere의 GET_DDL이 받는 객체 종류와 그 결과에 주석이 들어 있는지는 확인하지 못했다.

### 확인 (2026-11-17, 전: origin/command `a0caa4a81f` 빌드, 후: 이 브랜치 빌드, 사설 작업 공간, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

- ShardSphere(조회 전용, 연결 읽기 전용 표시, 자동 커밋): 잠금 탭 전 "Column not found … UNIX_DATE" 오류 → 후 "잠금을 기다리는 세션이 없습니다"(0행, 다시 연결 뒤 같음).
  테이블스페이스 DISK_DATA 사용·남은 크기 전 빈칸 → 후 2G / 0, 다른 네 행은 전후 같음. `getChild("CHR_SS1_NO_SUCH_REPLICATION")` 전 예외 → 후 `null`.
- Altibase 7.3 시험 서버(`CHR_SS1_*` 테이블만, 끝에 지움): 내 두 세션의 실제 잠금 대기(배타 테이블 잠금 대 UPDATE) 한 행이 전후 같은 모양
  (대기 리소스 `SYS.CHR_SS1_LOCK`, 모드 IX_LOCK, 문, 대기 초가 시작부터 지난 시간과 같음). 테이블스페이스 다섯 행의 값 전후 같음.
  `CHR_SS1_CMT` 스크립트: 전 COMMENT 없음 → 후 COMMENT 세 줄.
- 회귀 테스트는 작성·실행하지 않았다.

## 상태 다시 읽기 (2026-11-17, 앱 변경 #139 작업 1)

### 목적

ShardSphere의 GEMINI 복제를 이 플러그인의 복제 클래스로 보이려면(ShardSphere 계획 S2) 복제 카탈로그를 읽는 곳이 한곳이어야 한다.
그 전에 `AltibaseReplication.refreshObjectState`의 복사 결함을 고쳤다.

### 근본 원인

- `AltibaseReplication.refreshObjectState`(전 `AltibaseReplication.java:219-228`)는 프로시저 클래스(`AltibaseProcedureBase.refreshState`)의 질의를
  복사한 것이었다: `SYS_PROCEDURES_`에서 사용자 이름 = ?, 프로시저 이름 = ?인 행의 STATUS를 시작 여부로 읽고, 자리표시자는 첫째만 복제 이름으로 채웠다.
  Altibase 7.3에서 실행하면 `Parameter not set at index: 2`(334383, 22P01)로 끝난다. 채웠더라도 복제와 관계없는 프로시저의 유효 상태(1 = 무효)를 시작 여부로 읽는다.
- **부르는 곳:** Swing 제품에는 복제에 이 메서드를 부르는 곳이 없다(검색). `ObjectMaintenanceActions.refreshObjectStates`는 컴파일 뒤에만 부르고 복제는
  컴파일 대상이 아니다. `AbstractCommandContext`의 호출은 주석 처리돼 있다. 복제 시작·중지·동기화 뒤에는 노드 새로 고침(`refreshObject` → 복제 캐시)이 상태를 맞춘다.
  그래서 사용자에게 보이던 증상은 없었다. 모델의 공개 동작이 틀려 있었고, GEMINI 작업이 이 클래스를 다시 쓰므로 먼저 고쳤다.

### 고친 것

- 복제: 상태를 목록과 같은 문장(`AltibaseDataSource.ReplicationCache.prepareLookupStatement`, 이름 조건)으로 자기 행에서 읽고 `IS_STARTED`를 목록과 같은 식
  (`readStarted`)으로 해석한다. 복제 카탈로그 이름은 캐시의 문장 한곳에만 있다. 행이 없으면(그사이 지워짐) 상태를 그대로 둔다. 노드 새로 고침이 노드를 지운다.
- 패키지의 서브프로그램(`AltibaseProcedurePackaged`, 같은 원인): 상위 클래스의 상태 다시 읽기가 독립 프로시저의 `SYS_PROCEDURES_`를 서브프로그램 이름으로 읽었다.
  서브프로그램은 그 표에 없으므로 같은 스키마에 같은 이름의 독립 프로시저가 있으면 그 상태를 가져온다. 시험 서버에서 무효한 독립 프로시저 `CHR_RS_P`와
  유효한 패키지 `CHR_RS_PKG`의 서브프로그램 `CHR_RS_P`로 재현: 전 코드는 패키지 서브프로그램 노드를 새로 고치면(F5) 상태가 NORMAL → INVALID.
  카탈로그에 서브프로그램의 상태가 없고 목록이 늘 유효로 만드므로 다시 읽기도 카탈로그를 읽지 않는다.

### 다른 상태 구현 (같은 결함인지)

| 객체 | 읽는 곳 | 판정 |
|---|---|---|
| 복제 | `AltibaseReplication.refreshObjectState` | 결함(다른 종류의 카탈로그, 채우지 않은 자리표시자, 다른 열) → 고침 |
| 패키지 서브프로그램 | 상속한 `AltibaseProcedureBase.refreshState` | 결함(다른 종류의 카탈로그) → 고침 |
| 독립 프로시저·함수·타입세트 | `AltibaseProcedureBase.refreshState`: `SYS_PROCEDURES_` STATUS, 자리표시자 둘 다 채움 | 정상. 목록(`loadPSMs`)과 같은 열·해석. `CHR_RS_P` = 1(무효) 확인 |
| 뷰 | `AltibaseUtils.getViewStatus`: `SYS_VIEWS_` + `SYS_TABLES_` | 정상. 바탕 테이블을 지운 `CHR_RS_V` = 1 확인 |
| 구체화된 뷰 | 같은 메서드: `SYS_VIEWS_` + `SYS_MATERIALIZED_VIEWS_` | 정상. `CHR_RS_MV` = 0 확인 |
| 패키지 | `AltibasePackage.refreshState`: `SYS_PACKAGES_` 본문 행(PACKAGE_TYPE 7) | 같은 결함 아님(자기 카탈로그, 둘 다 채움). 다른 빈틈은 아래 "고치지 않은 것" |
| 작업, 트리거, 라이브러리, 테이블스페이스, 데이터 파일, 복제 송신 | 목록에서만 읽음(`SYS_JOBS_`, `SYS_TRIGGERS_`, `SYS_LIBRARIES_`, `V$TABLESPACES`, `V$DATAFILES`, `V$REPSENDER`) | 상태 다시 읽기 구현 없음, 자기 카탈로그 |
| 큐, 시노님 | 상태 없음 | 해당 없음 |

### 고치지 않은 것

- 패키지: 주석 "package spec. cannot be invalid"는 틀렸다(바탕 테이블을 지운 명세만 있는 `CHR_RS_PKG2`의 명세 STATUS = 1). 본문이 있으면 명세와 함께 무효가 되므로
  (`CHR_RS_PKG3` 명세·본문 모두 1) 본문만 읽어도 맞지만, 본문 없는 패키지는 다시 읽어도 상태가 바뀌지 않는다. 다른 결함이라 이 변경에서 고치지 않았다.
- Swing 탐색기는 무효(INVALID) 상태를 아이콘에 보이지 않는다(무효인 독립 프로시저도 보통 아이콘, 캡처로 확인). 그래서 서브프로그램 결함도 화면에는 드러나지 않았다.
- `AltibaseLockManager`(DBeaver 잠금 관리자): 만드는 곳이 없다. 검색(소스, `plugin.xml`, 어댑터 `getAdapter`, 문자열 참조) 결과 문서와 `model.admin`의 주석,
  `MANIFEST.MF`의 패키지 내보내기, 속성 이름 `bundle*.properties`뿐이다. 이 변경 범위 밖이라 지우지 않았다.

### 확인 (2026-11-17, 전: origin/command `6184a0fae8` 빌드, 후: 이 브랜치 빌드, 사설 작업 공간, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

- 시험 서버는 `REPLICATION_PORT_NO = 0`이라 복제를 만들 수 없다(`CREATE REPLICATION CHR_RS_REP …` → 61023 "Replication is disabled"). 서버 속성은 바꾸지 않았다.
  그래서 복제는 이름이 `CHR_RS_REP`인 카탈로그 같은 행으로 모델 객체를 만들어(서버에 그 행은 없음) `refreshObjectState`를 불렀다.
  전: `Parameter not set at index: 2` 예외. 후: 예외 없음, 행이 없어 상태는 그대로(ACTIVE / NORMAL). 새 문장은 목록과 같아 서버에서 준비된다.
- 전역 메타데이터 ▸ 이중화 객체 폴더: 전후 모두 비어 있고 오류 없음(후: 폴더 새로 고침, 끊고 다시 연결한 뒤에도 같음). 복제 노드가 없어 노드 명령(시작·중지·동기화)은 실행하지 못했다.
- 패키지 서브프로그램 `CHR_RS_PKG.CHR_RS_P`: 전 F5 뒤 INVALID, 후 F5 뒤 NORMAL. 독립 프로시저 `CHR_RS_P`는 전후 INVALID.
- ShardSphere 서버에는 연결하지 않았다: 복제 목록 코드는 바뀌지 않았고, 복제 객체가 없는 서버에서는 `refreshObjectState`가 불릴 수 없다.
- 회귀 테스트는 작성·실행하지 않았다.

## 서버 확장 (2026-11-17, 앱 변경 #139)

### 목적

Altibase 계열의 일부 서버에만 있는 기능(ShardSphere의 GEMINI·샤딩)을 다른 번들이 이 플러그인을 고치지 않고 얹게 하는 자리다.
이 플러그인은 그 서버가 무엇인지 모른다(제품 이름 검사 없음). 첫 사용자는 ShardSphere 플러그인이다([shardsphere.md](shardsphere.md)).

### 구조

| 위치 | 하는 일 |
|---|---|
| `plugin.xml` 확장 지점 `com.seizia.chromite.altibase.serverExtension`, 스키마 `schema/com.seizia.chromite.altibase.serverExtension.exsd` | `<serverExtension id="…" class="…"/>`. 클래스는 상태 없는 구현 하나로 모든 연결이 함께 쓴다 |
| `extension/AltibaseServerExtension` | `detect(JDBCSession, AltibaseDataSource)`: 연결한 서버의 카탈로그를 읽고(SELECT만) 맞으면 그 연결의 상태를, 아니면 null을 돌려준다. 실패는 "없음" |
| `extension/AltibaseServerExtensionState` | 연결마다의 결과. `getAdapter(Class)`(데이터 원본의 어댑터를 먼저 답함), `getTreeObjects()`(아래 트리 자리의 객체), 방언 기여 `getSyntaxKeywords()`·`getFunctions()`·`getStatementPrefixLength(text)`(2026-11-17 작업 6a). 기본 구현은 "기여 없음" |
| `AltibaseSQLDialect.applyServerExtensions` / `getStatementPrefixLength` | 감지 뒤 데이터 원본이 자기 방언 객체에 넘긴다: 낱말은 키워드로(서버가 `V$RESERVED_WORDS`에 없으면 `mustBeQuoted`의 이유가 아님, `CACHE`와 같은 규칙), 함수는 함수로, 접두어 길이는 확장에 묻는다(확장이 없으면 0 = 전과 같음) |
| `AltibaseServerExtensionState.getPrefixedStatementSubquery` / `getStatementPrefixProposals` → `AltibaseSQLDialect`의 같은 이름(2026-11-17 작업 6b) | 접두어 문장을 하위 질의로 쓰는 형태(행 수·필터·정렬이 감쌀 때)와 입력 중인 접두어의 자동 완성 제안. 문장의 접두어를 아는 확장에 묻는다. 확장이 없으면 null = 전과 같음(형태 없음 → 다시 읽기 막힘, 제안 없음) |
| `AltibaseServerExtensionState.getReplicationCatalog()` → `AltibaseDataSource.getReplicationCatalog()`(2026-11-17 작업 4·5) | 복제를 다른 카탈로그에 두는 서버의 복제 카탈로그. 처음 답한 확장의 것, 없으면 Altibase 카탈로그(아래 "복제 카탈로그") |
| `extension/AltibaseServerExtensionRegistry` | 확장 지점을 처음 물을 때 한 번 읽고 확장 객체를 하나씩 만든다. 만들 수 없는 확장은 기록하고 뺀다 |
| `AltibaseDataSource.initialize` → `detectServerExtensions` | 연결이 쓸 수 있게 된 뒤(상위 `initialize`, LOB 임계값 다음) 확장마다 `detect`를 메타 세션 하나에서 부르고 결과를 인스턴스 필드 `serverExtensions`에 둔다. 확장이 없으면 세션도 열지 않는다 |
| `AltibaseDataSource.refreshObject` | 결과를 비우고 `initialize`가 다시 감지한다. 끊고 다시 연결하면 데이터 원본 인스턴스가 새로 만들어지므로 그때도 다시 감지한다 |
| `AltibaseDataSource.getAdapter` | 감지된 확장에 먼저 묻고, 답이 없으면 전과 같다 |
| `AltibaseDataSource.getExtensionObjects` + `plugin.xml` 트리 System Info 안 `<items path="serverExtension" property="extensionObjects">` | 트리 자리. 감지된 확장의 객체가 System Info 아래 노드가 되고 그 `@Property`가 속성 보기에 보인다. 확장이 없는 서버에서는 노드가 없다(빈 목록, 질의 없음) |

### 설계 결정

- **연결마다의 상태는 데이터 원본 인스턴스에만 둔다.** 메타 모델(`AltibaseMetaModel`)은 같은 드라이버의 모든 연결이 함께 쓰고, 어댑터 팩토리·공급자의 트리 정의·
  방언의 정적 낱말 목록도 공유된다(ShardSphere 계획의 위험 목록). 확장 객체도 공유되므로 상태를 갖지 않고 `detect`가 연결마다 새 상태 객체를 돌려준다.
- **감지는 연결할 때 한 번.** 쓸 때마다 묻지 않는 이유: 트리·어댑터가 자주 묻고, 서버가 바뀌는 일은 연결 안에서 없다. 새로 고침은 카탈로그를 다시 읽는 사용자의 요청이므로 다시 감지한다.
- **실패는 연결을 막지 않는다.** 확장의 오류(`RuntimeException`, `LinkageError`)와 세션 오류는 디버그 기록만 남기고 그 확장은 "없음"이다.
- **어댑터는 확장이 먼저.** 계열 공통 구현(세션 관리자, 실행 계획 등)을 어느 서버에서 바꿔야 할 때 이 클래스를 고치지 않으려고. 확장이 없으면 빈 목록을 한 번 도는 비용뿐이다.
- **트리 자리는 System Info 안 한 곳.** 이번 변경이 쓰는 곳만 두었다. 다음 작업의 GEMINI 트리는 Replications 폴더 아래이고(계획의 결정), 그때 필요한 자리를 정한다.
- **방언 기여는 데이터 원본의 방언 객체에만(2026-11-17, 작업 6a).** 방언 객체는 데이터 원본마다 하나(`AltibaseDataSource` 생성자의 `new AltibaseSQLDialect()`)라
  한 연결이 감지한 낱말·접두어가 같은 앱의 다른 Altibase 연결로 가지 않는다(정적 배열에 넣지 않음). `initialize`가 감지 뒤 `applyServerExtensions`를 부른다.
  새로 고침에서는 `initDriverSettings`(서버 예약어)가 다시 돈 뒤 감지와 기여가 다시 들어오고, 그 사이에도 접두어 인식은 앞 결과를 그대로 쓴다(꺼지지 않음).
  낱말은 한 번 더해지면 남는다(같은 서버에서는 늘 같은 결과). 확인: 두 연결을 함께 열고 양쪽 순서, 새로 고침, 다시 연결에서 방언 객체와 접두어 길이를 읽음
  ([shardsphere.md](shardsphere.md) "문장 접두어").
- **아직 두지 않은 것:** 관리 보고서(S4), 객체 명령(S6/S7)은 그 작업에서 정한다. 복제 카탈로그 전략(S2)은 2026-11-17에 두었다(아래 "복제 카탈로그").

### upstream 대비 변경점

- upstream에는 Altibase 서버 확장이 없다. 확장 지점, 세 타입, 데이터 원본의 감지·어댑터·트리 자리, System Info의 트리 항목 하나를 더했다.

## 복제 카탈로그 (2026-11-17, 앱 변경 #139 작업 4·5)

### 목적

같은 계열의 서버가 복제를 다른 이름의 카탈로그에 둔다(ShardSphere의 GEMINI, [shardsphere.md](shardsphere.md) "GEMINI"). 복제 문장이 Altibase 이름을
코드 여러 곳에 박아 두어, 그런 서버에서는 Replications 폴더가 늘 비고(표 없음 오류를 숨김) 활동 모니터의 복제 탭이 "읽을 수 없음 … SYS_REPL_HOSTS_"였다.
이 플러그인과 `model.admin`의 복제 문장을 연결마다 고르는 전략 하나로 모았다. 이 플러그인은 그 서버가 무엇인지 모른다.

### 구조

| 위치 | 하는 일 |
|---|---|
| `model/AltibaseReplicationCatalog` (인터페이스) | 카탈로그 이름(`getDisplayName`, Altibase는 null), 트리 문장(`getListQuery(byName)`, `getItemsQuery`, `getSenderQuery`, `getReceiverQuery`), 속성 이름(`getPortProperty`, `getGapUnitProperty` — 없으면 null), 열 유무(`hasHostConnectionType`), 능력(`supportsControlStatements`, `getDdlObjectType` — 모르면 null), 복제 탭 문장 4개(`getStatus*Query`, 열은 순서로 읽음) |
| `model/AltibaseDefaultReplicationCatalog` | Altibase 카탈로그. 각 클래스에 있던 문자열을 그대로 옮겼다(모양을 고치지 않음) |
| `AltibaseDataSource.getReplicationCatalog()` | 감지된 서버 확장 중 처음 답한 것(`AltibaseServerExtensionState.getReplicationCatalog()`), 없으면 기본. 질의 없음 |
| `AltibaseDataSource.ReplicationCache` | 목록·이름 조회는 데이터 원본의 카탈로그, 항목은 그 복제를 읽은 카탈로그 |
| `AltibaseReplication` | 자기를 읽은 카탈로그를 갖는다: 송신·수신 문장, `supportsControlStatements()`, 속성 `catalogName`(종류, 카탈로그 이름이 있을 때만 보임), `remoteConnType`(그 열이 있을 때만 보임·읽음), 통계 크기(지연 바이트)는 지연 단위가 있을 때만 |
| `AltibaseReplicationSender` | 지연 단위가 있으면 `GAP_SIZE_IN_BYTE`(바이트, "Gap (Byte)"), 없으면 `REP_GAP`을 단위 없이("Gap"/"지연"). 둘 중 하나만 보인다 |
| `AltibaseMetaModel.getReplicationDDL` | `DBMS_METADATA.GET_DDL`의 객체 유형을 카탈로그에서. null이면 부르지 않고 빈 DDL |
| `tasks/AltibaseToolReplicationStart`·`Stop` | 카탈로그가 제어 문장을 허락하지 않으면 문장을 만들지 않고 예외(저장된 작업 같은 다른 길 대비) |
| `model.admin` `ObjectMaintenanceActions.supports` | 시작·중지·동기화는 `replication.supportsControlStatements()`일 때만 → 오른쪽 클릭 메뉴에 항목이 없다(적용되지 않는 명령은 보이지 않는 규칙) |
| `model.admin` `AltibaseReplicationDialect` | 문장과 속성 이름을 데이터 원본의 카탈로그에서. 카탈로그 이름이 있으면 요약의 포트 행이 "{이름} 포트", 0이면 "0: 이 서버는 {이름} 사용 안 함({속성})". 지연 단위가 없으면 송신 표의 지연 열이 바이트가 아닌 수("지연", 단위 미확인 툴팁). 제어 문장이 없으면 복제 표의 시작·중지 안내를 붙이지 않는다 |

### 설계 결정

- **모델 클래스는 그대로, SELECT 목록이 별칭한다.** 복제·항목·송신·수신 클래스는 열 이름으로 읽으므로 카탈로그의 SELECT가 열을 Altibase 이름으로 별칭한다.
  카탈로그에 없는 열은 행에 없을 뿐이고 `JDBCUtils.safeGet*`가 "값 없음"(null/0)으로 읽는다. 그 열에만 기대는 속성(연결 유형, 바이트 지연)은 `@Property(visibleIf)`로
  그 카탈로그에서 숨기고, 없는 열은 읽지도 않는다(디버그 기록이 쌓이지 않게).
- **Altibase 문장은 글자 하나까지 같다.** 기본 카탈로그의 문장을 이 변경 전 소스의 문자열과 비교했다(기준 `8d78269ed4`의 소스에서 문자열 식을 그대로 뽑아 컴파일한
  것과 빌드한 jar의 결과, 12개 모두 같음: 트리 목록·이름 조회·항목·송신·수신, 탭의 포트·지연 단위·호스트·복제·송신·수신, DDL 객체 유형). 탭의 속성 문장은
  `"SELECT VALUE1 FROM V$PROPERTY WHERE NAME = '" + 속성 + "'"`로 만들어도 같은 글자다.
- **탭이 카탈로그를 얻는 길: 데이터 원본의 전략을 기존 Altibase 방언이 읽는다.** 계획의 S3(관리 쪽 어댑터 먼저)를 쓰지 않은 이유: 표·열의 뜻과 화면이 같아 다른 것은
  문장 이름뿐이고, 어댑터로 하면 확장 번들이 `model.admin`에 기대며 같은 방언을 거의 복사해야 한다. `model.admin`은 카탈로그 인터페이스만 알고 제품 이름을 모른다.
  S3는 표나 화면이 다른 서버가 생기면 그때 둔다.
- **제어 문장은 능력으로 막는다.** 확인하지 않은 카탈로그(GEMINI)에서 `ALTER REPLICATION`을 보내지 않으려고: 메뉴(`supports`), 스크립트 생성(`generateScript`가 `supports`를 다시 봄),
  모델 도구(예외) 세 곳. 복제에는 객체 관리자가 없어 새로 만들기·삭제·이름 바꾸기는 원래 없다(`plugin.xml` `objectManager`에 복제 없음, Swing은 관리자가 있을 때만 그 명령을 냄).
  DDL 탭의 `exec ? := dbms_metadata.get_ddl('REPLICATION', …)`도 확인하지 않은 카탈로그에서는 부르지 않는다.
- **카탈로그는 복제 객체가 갖는다.** 새로 고침 사이에 데이터 원본의 확장 상태가 바뀌어도(새 감지 객체) 이미 읽은 복제의 항목·송신·수신은 자기를 읽은 카탈로그에서 읽는다.
- **이름이 없는 카탈로그는 Altibase 문구 그대로.** 카탈로그 이름(`getDisplayName`)이 null이면 탭과 속성 보기의 문구가 전과 같다(종류 속성 숨김).

### 확인 (2026-11-17)

- Altibase 7.3(시험 서버): 기본 카탈로그의 11개 문장을 앱 안에서 준비하고 실행(모두 0행, 속성 2개는 1행), 폴더(0개, 오류 없음)와 탭(복제 포트 "0: 이 서버는 복제가
  꺼져 있음(REPLICATION_PORT_NO)", 구성되지 않음)이 전과 같은 글자. 쿼리 관리자의 문장이 전과 같다. 손으로 만든 행: 연결 유형 보임, 바이트 지연("168K" = 42 × 4096),
  시작·중지·동기화 적용됨, 시작 도구가 문장을 만듦. 자세한 것과 ShardSphere 쪽은 [shardsphere.md](shardsphere.md) "GEMINI".
- 회귀 테스트는 작성·실행하지 않았다.

### upstream 대비 변경점

- upstream의 복제 문장은 각 클래스에 있었다. 그 문자열을 바꾸지 않고 기본 카탈로그로 옮기고, 카탈로그 인터페이스·데이터 원본의 선택·능력 검사를 더했다.

## 공간 사용량 (2026-11-17, 앱 변경 #139 작업 7)

### 목적

활동 모니터의 공간 사용량 탭이 Altibase 계열 연결(Altibase, ShardSphere)에서 비어 있었다("Altibase에서는 공간 사용량을 볼 수 없습니다"):
`ServerSpaceDialect.forDataSource`에 이 계열의 분기가 없었다. 다른 DB와 같은 표 모양으로 테이블스페이스, 데이터 파일, 큰 테이블을 보인다.
한 구현이 두 서버에 같은 문장을 보낸다(제품 이름 검사 없음). ShardSphere 기능 번들이 없는 제품에서도 같다(같은 데이터 원본 클래스).
화면과 다른 DB의 내용은 [swing-admin-tools.md](swing-admin-tools.md) "공간 사용량".

### 구조

| 위치 | 하는 일 |
|---|---|
| `model.admin` `altibase/AltibaseSpaceDialect`(새) | 표 셋. 문장마다 실패하면 그 표만 "읽을 수 없음: {서버 메시지}" |
| `model.admin` `space/ServerSpaceDialect.forDataSource` | `AltibaseDataSource`면 위 방언 |
| `ext.altibase` `AltibaseTablespace.getSizeQuery`(정적)·`readSizes(session, type, id)`·`Sizes` | 종류별 크기 문장을 객체 필드(`qry4Size`)에서 정적 메서드로 옮기고 세션을 받게 함. 트리의 `loadSizes`와 탭이 같이 쓴다 |
| `model.admin` `SpaceMessages`(+`_ko`) | 새 문구 9개(아래) |

### 표와 읽는 곳

| 표 | 열 | 읽는 곳 |
|---|---|---|
| 테이블스페이스 | 테이블스페이스, 유형(`TbsType` 이름, 트리 속성과 같음), 상태(`State` 이름), 크기, 사용, 사용 가능, 사용 %, 최대 크기, 최대 대비 사용 %, 자동 확장 | 목록: `V$TABLESPACES`(크기 = TOTAL_PAGE_COUNT × 그 테이블스페이스의 PAGE_SIZE), 자동 확장: 디스크는 `V$DATAFILES.AUTOEXTEND`의 최댓값, 메모리 `V$MEM_TABLESPACES`, 휘발성 `V$VOL_TABLESPACES`의 AUTOEXTEND_MODE. 사용·최대 크기: 테이블스페이스마다 `AltibaseTablespace.readSizes` |
| 데이터 파일 | 파일 ID, 테이블스페이스, 종류(데이터 파일·임시 파일·체크포인트 경로), 경로, 크기, 자동 확장, 증가 단위, 최대 크기, 상태 | `V$DATAFILES` × `V$TABLESPACES`(페이지 수 × PAGE_SIZE, 최대 = MAXSIZE, 0이면 현재 크기: 트리의 디스크 문장과 같은 규칙), `V$MEM_TABLESPACE_CHECKPOINT_PATHS`(경로만) |
| 큰 테이블 | 소유자, 테이블, 테이블스페이스, 메모리, 디스크, 합계 | `SYSTEM_.SYS_TABLE_SIZE_`(범위: 소유자 하나 또는 전체, `ORDER BY … LIMIT ?`) |

- 언두와 임시 사용량은 그 테이블스페이스의 행이다: 언두 = (TX_EXT_CNT + USED_EXT_CNT + UNSTEALABLE_EXT_CNT) × `SYS_UNDO_TBS_EXTENT_SIZE`(`V$DISK_UNDO_USAGE`),
  임시 = `X$TEMPTABLE_STATS.NORMAL_AREA_SIZE`의 합(지금 쓰는 임시 테이블). 따로 표를 두지 않았다(다른 DB에 없는 표 종류라서).
- `SYS_TABLE_SIZE_`는 서버의 뷰다(두 서버의 정의가 같음, `SYS_VIEW_PARSE_`로 읽음): 사용자 ID가 1보다 큰 사용자의 테이블(TABLE_TYPE 'T'), 파티션은 테이블로 합침,
  메모리 = (MEM_PAGE_CNT + MEM_VAR_PAGE_CNT) × 32 KB(`X$TABLE_INFO`), 디스크 = 그 테이블 OID의 세그먼트 사용 크기(`X$SEGMENT.TOTAL_USED_SIZE`).
  행 수 통계는 이 계열에 없어 열이 없다.
- 데이터 파일마다의 사용량은 서버가 주지 않는다(세그먼트는 테이블스페이스 단위). 체크포인트 이미지 파일(`<이름>-0-0`, `-1-0`)의 크기도 SQL로 읽을 수 없어
  경로만 보인다(시험 컨테이너에서 파일 크기는 할당 페이지와 비슷했다: 메모리 데이터 385페이지 12.6 MB, 파일 10.9 MB).

### 트리와 같은 크기

트리(Storage > Tablespaces)의 Used Size = 탭의 사용, Available Size = 탭의 최대 크기 − 사용이다. 탭이 트리의 문장을 그대로 부르므로 둘이 어긋날 수 없다.
트리의 lazy 객체(`getTablespaces` → 객체마다 `loadSizes`)를 쓰지 않고 정적 문장을 탭의 세션에서 부르는 이유: 트리 캐시는 새로 고침 전까지 옛 목록·상태를
갖고, 객체마다 메타 세션을 따로 연다. 탭은 매번 `V$TABLESPACES`를 새로 읽고 테이블스페이스마다 크기 문장 하나를 보낸다(새로 고침 한 번에 목록·파일·경로·범위·큰 테이블 5문장과 크기 문장이 시험 서버 5개, 메타 노드 9개).
잰 시간: 터널 너머 Altibase 7.3에서 문장 하나 67–122 ms, 원격 ShardSphere 10–30 ms. 새로 고침 한 번이 1초 안팎이라 한 문장으로 합치지 않았다.

### 한도를 고친 것 (트리도 바뀜)

전에는 메모리 테이블스페이스의 한도가 모두 `MEM_MAX_DB_SIZE`(전체 메모리 DB 한도)였고, 휘발성은 MAX_SIZE가 0이면 `VOLATILE_MAX_DB_SIZE`였다. 자동 확장을 끈
테이블스페이스는 지금 크기 이상 커지지 않는데 트리의 Available Size가 GB 단위로 나왔다. 메타 노드의 사용자 테이블스페이스로 확인했다(자동 확장 꺼짐은 MAXSIZE 0):

| 테이블스페이스(메타 노드) | 전: 한도 | 후: 한도 |
|---|---|---|
| 메모리 사용자 둘(AUTOEXTEND_MODE 0, CURRENT_SIZE 64 MB) | 2 GB(`MEM_MAX_DB_SIZE`) | 64 MB |
| 휘발성 사용자 둘(AUTOEXTEND_MODE 0, CURRENT_SIZE 256 MB) | 4 GB(`VOLATILE_MAX_DB_SIZE`) | 256 MB |
| 메모리 시스템 둘(자동 확장, MAXSIZE 140737488322560) | 2 GB | 2 GB(LEAST) |

새 규칙: 자동 확장이 켜져 있으면 메모리는 `LEAST(MAXSIZE, MEM_MAX_DB_SIZE)`(`V$DATABASE`의 값은 문자열이라 `TO_NUMBER`), 휘발성은 전과 같은 DECODE, 꺼져 있으면 CURRENT_SIZE.
디스크·임시·언두 문장은 고치지 않았다.

### 두 서버의 같은 점과 다른 점

- 쓰는 뷰·열은 Altibase 7.3.0.0.7, ShardSphere 5.1.9, 메타 노드 5.1.9-beta9에서 모두 준비되고 실행됐다(탭의 문장 6개, 종류별 크기 문장 5개). SYS가 아닌 사용자(Altibase `TURBRIX`)도
  모두 준비했다. 그래서 서버마다 고르는 분기가 없다.
- 다른 점(이 탭은 쓰지 않음): `X$SEGMENT`에 ShardSphere는 `TOTAL_USED_PAGE_CNT` 열이 없다(42S22). 시스템 테이블스페이스 이름이 Altibase는 `SYS_TBS_*`,
  ShardSphere는 접두어 없이 `MEM_DIC`, `DISK_DATA` 같은 이름이다. `DISK_MAX_DB_SIZE`가 ShardSphere는 18446744073709551615(제한 없음)다.
- 메타 노드의 숫자는 메타 노드 자신의 저장소다(샤딩 사전 `SYS_SHARD`의 테이블, 메타 노드의 사용자 테이블스페이스). 데이터 노드의 공간은 각 데이터 노드에 연결해서 본다.
  메타 노드의 임시 테이블스페이스는 192 MB(91 %)를 쓰고 있었다(임시 테이블 3개).

### 확인 (2026-11-17, 전: 기준 `bac006fa38` 빌드, 후: 이 브랜치 빌드, 사설 작업 공간·설정·데이터 폴더, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

- 전: Altibase·ShardSphere 둘 다 탭에 "…에서는 공간 사용량을 볼 수 없습니다"만.
- Altibase 7.3: 탭의 다섯 행을 독립 질의(`V$TABLESPACES`, `V$MEMTBL_INFO`, `X$SEGMENT`, `V$DISK_UNDO_USAGE`, `X$TEMPTABLE_STATS`, `V$MEM_TABLESPACES`, `V$DATAFILES`)와
  트리 객체(`refreshObject` 뒤 `getUsedSize`/`getAvailableSize`)와 비교해 같음. 예: 디스크 데이터 크기 100 MB·사용 1 MB·최대 2,147,475,456, 언두 사용 134,217,728,
  메모리 데이터 사용 10,256,744. 범위 콤보(소유자) TURBRIX → 그 소유자의 4개.
- 시험 표 `CHR_SP_T`(SYS, 1,500행 × 1,000바이트, 자동 커밋 연결에서 만들고 지움): 메모리 테이블스페이스에 두면 큰 테이블에 메모리 2,129,920(65페이지 × 32 KB),
  메모리 데이터 사용 10,256,744 → 12,356,744, 크기 12 MB → 16 MB(자동 확장 한 번, 4 MB). 지우고 디스크 데이터 테이블스페이스에 다시 두면 디스크 2,097,152,
  디스크 데이터 사용 1 MB → 3 MB, 메모리 사용은 원래대로. 끝에 지움: `SYS_TABLES_`에 `CHR_SP_T` 0행, 디스크 사용 1 MB. 메모리 테이블스페이스의 늘어난 4 MB는
  줄지 않는다(서버 동작).
- ShardSphere 일반 서버와 메타 노드(조회 전용, 연결 읽기 전용 표시, 자동 커밋, 암호 저장 끔): 같은 세 표. 새로 고침 한 번에 보낸 문장(쿼리 관리자) 일반 서버 10개,
  메타 노드 14개, 모두 SELECT, 모두 성공. 데이터 파일 경로·사용자 이름·사용자 테이블스페이스 이름은 캡처에서 가렸다.
- ShardSphere 기능 번들(`com.seizia.chromite.ext.shardsphere`)을 뺀 제품: 두 연결의 탭이 같은 값(감지 문장만 없음).
- PostgreSQL·SQL Server: 탭의 표·열·값이 전과 같다(로그 사용량처럼 움직이는 값만 다름).
- 캡처: `D:\Tools\_scratch\altibase-space-tab\sheets\`(저장소 밖). 회귀 테스트는 작성·실행하지 않았다.

### 미확인

- 미확인: 자동 확장을 끈 디스크 데이터 파일의 MAXSIZE가 늘 0인지(시험 서버의 파일은 모두 자동 확장). 탭과 트리는 MAXSIZE가 0이 아니면 그 값을 한도로 본다.
- 미확인: 휘발성 테이블스페이스의 자동 확장이 켜졌을 때 MAX_SIZE 0의 뜻(전 코드처럼 `VOLATILE_MAX_DB_SIZE`로 읽음). 켜진 휘발성 테이블스페이스를 본 적이 없다.
- 미확인: 체크포인트 경로가 여럿인 메모리 테이블스페이스에서 이미지 파일이 경로마다 어떻게 나뉘는지(두 서버 모두 경로 하나).
- 미확인: ShardSphere에서 `SYS_TABLE_SIZE_`·`X$` 뷰를 일반 사용자가 읽을 수 있는지(벤더 서버는 관리 계정으로만 연결).

### upstream 대비 변경점

- upstream에는 공간 사용량 화면이 없다. 크기 문장을 정적 메서드로 옮기고(문자열은 메모리·휘발성 한도 말고 같음) 세션을 받는 읽기를 더했다.

## 개요 차트와 서버 정보 (2026-11-17, 앱 변경 #139 작업 8)

### 목적

활동 모니터의 개요 탭이 이 계열(Altibase, ShardSphere)에서 "이 데이터베이스 유형에는 정의된 차트가 없습니다"였고, 서버의 시작 단계·시각, 아카이브 로그 모드,
버전 세부, 백업 기록은 앱 어디에도 없었다. 두 서버에 다 있는 뷰로 둘 다 더한다. 한 구현이 세 서버(Altibase 7.3, ShardSphere 5.1.9, 메타 노드 5.1.9-beta9)에
같은 문장을 보낸다(제품 이름 검사 없음, ShardSphere 기능 번들이 없어도 같음).

### 구조

| 위치 | 하는 일 |
|---|---|
| `model.admin` `plugin.xml`(차트 정의 8개, `<datasource id="altibase"/>`) | 개요 차트. 차트·문장·뜻은 [swing-admin-tools.md](swing-admin-tools.md) "Altibase 계열 개요" |
| `model.admin` `dashboard/DashboardSampler` | 숫자 `STAT_TIMESTAMP`를 UTC 밀리초로 받음(`V$SYSSTAT`이 3초마다 모은 시각) |
| `ext.altibase` `plugin.xml` System Info 폴더 | "서버 정보" 폴더(속성·모듈 메모리 옆), 항목은 `AltibaseDataSource.getServerInfo` |
| `ext.altibase` `model/AltibaseServerInfoItem`(새) | 이름·값 항목, 뷰마다 문장 하나 |
| `ext.altibase` `internal/AltibaseMessages`(새, +`_ko`) | 항목 이름(객체의 값이라 `bundle*.properties`가 옮길 수 없음) |

### 서버 정보: 둘 곳

이 공급자는 System Info 폴더에 이미 서버의 사실을 이름·값 목록으로 둔다(Properties = `V$PROPERTY`, Module Memory Usage = `V$MEMSTAT`). 같은 모양의 폴더
"서버 정보"를 하나 더했다. 새 창이나 Swing 코드가 없고, 폴더를 열면 개체 세부 정보에 이름·값 표가 나온다. 개요 탭에 글자 줄로 두는 안(다른 DB의 개요에는
서버 사실이 없음)은 Swing 번들과 대시보드 화면을 고쳐야 해서 고르지 않았다.

| 항목 | 읽는 곳 | 비고 |
|---|---|---|
| 버전, 빌드, 빌드 시각, 저장 관리자 버전, 메타 버전, 프로토콜 버전 | `V$VERSION`의 PRODUCT_VERSION, PKG_BUILD_PLATFORM_INFO, PRODUCT_TIME, SM_VERSION, META_VERSION, PROTOCOL_VERSION | 두 서버 공통 열만 |
| 시작 단계, 시작 시각, 가동 시간 | `V$INSTANCE`의 STARTUP_PHASE, STARTUP_TIME_SEC, WORKING_TIME_SEC | 시작 시각은 1970 UTC부터의 초(시험 서버에서 컨테이너 시작 시각과 같고, 시작 + 가동 = 지금), 클라이언트 시간대로 보임 |
| 서버 상태, 아카이브 로그 모드 | `V$LOG`의 SERVER_STATUS, ARCHIVELOG_MODE | 서버 글자 그대로(`SERVER STARTED`, `NOARCHIVE`) |
| 아카이브 스레드, 현재 로그 파일, 다음에 아카이브할 로그 파일 | `V$ARCHIVE`의 ARCHIVE_THR_RUNNING(실행 중/멈춤), CURRENT_LOGFILE, NEXTLOGFILE_TO_ARCH(ARCHIVE_MODE가 0이 아닐 때만) | 현재 로그 파일은 `V$LFG.CUR_WRITE_LF_NO`와 세 서버 모두 같음(0, 1, 6). 첫 로그 파일 그룹 |
| 백업 기록, 마지막 백업 | `V$BACKUP_INFO`의 `COUNT(*)`, `MAX(END_BACKUP_TIME)`(행이 있을 때만) | 세 서버 모두 0행 |

아카이브 경로(`V$ARCHIVE.ARCHIVE_DEST`)는 넣지 않았다: 같은 값이 서버 속성 `ARCHIVE_DIR`로 Properties에 있다. 뷰 하나를 읽지 못하면 그 항목만 빠지고, 모두 실패하면
첫 오류를 보인다.

### 두 서버의 같은 점과 다른 점

- 같음: 차트 문장 8개와 서버 정보 문장 5개가 세 서버에서 모두 준비·실행됐다. `V$SYSSTAT` 이름과 3초 모음, `V$SESSION`·`V$LOCK_WAIT`·`V$BUFFPOOL_STAT`·`V$MEMSTAT`의
  열이 같다.
- 다름(읽지 않음): ShardSphere에만 `V$INSTANCE.PROCESS_ID`, `V$VERSION.PRODUCT_NAME`, `V$SESSION.IS_INTERNAL`·`PROGRAM_NAME` 등, 복제 프로토콜 열 이름(Altibase
  `REPL_PROTOCOL_VERSION`, ShardSphere `GEMI_PROTOCOL_VERSION`), `V$SYSSTAT`의 샤딩·연속 질의·만료 데이터 카운터(166행 대 132행, `SEQNUM`이 다름). Altibase에만
  `ddl sync timeout`. 메타 노드만 `TIMED_STATISTICS = 1`.
- ShardSphere 일반 서버에서 `session commit`은 개요가 연결해 있는 동안 늘지 않았다(`V$` 뷰만 읽어 트랜잭션이 없음, 다른 세션의 쓰기도 없었음).

### 확인 (2026-11-17, 이 브랜치 빌드, 사설 작업 공간·설정·데이터 폴더, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

- 개요 차트: [swing-admin-tools.md](swing-admin-tools.md) "Altibase 계열 개요"의 확인(부하 중 독립 질의와 같은 값, 한 번 읽기의 문장 수).
- 서버 정보: 세 서버에서 14항목(아카이브 모드가 아니라 "다음에 아카이브할 로그 파일", 백업이 없어 "마지막 백업"은 없음). 시험 Altibase 버전 7.3.0.0.7, 시작 단계
  SERVICE, 가동 5일, NOARCHIVE, 아카이브 스레드 멈춤, 현재 로그 파일 0, 백업 기록 0. 연결 새로 고침, 끊고 다시 연결한 뒤에도 같다.
- ShardSphere 기능 번들을 뺀 제품: 두 ShardSphere 연결의 서버 정보 14항목, System Info 아래 ShardSphere 노드만 없음.
- 시험 Altibase에 만든 `CHR_OV_T`는 끝에 지웠다(`SYS_TABLES_`에 0행, 지우기 전 열린 트랜잭션·잠금 대기 0). 서버 속성은 바꾸지 않았다.

### 미확인

- 미확인: 아카이브 모드 서버에서 `ARCHIVE_THR_RUNNING`, `NEXTLOGFILE_TO_ARCH`의 값(세 서버 모두 NOARCHIVE).
- 미확인: `V$BACKUP_INFO`의 `END_BACKUP_TIME` 글자 형식과 최댓값이 마지막 백업인지(세 서버 모두 0행).
- 미확인: `STARTUP_PHASE`의 SERVICE 밖 값(시작 중인 서버를 볼 수 없음).
- 미확인: `V$SYSTEM_EVENT`가 `TIMED_STATISTICS`와 어떤 관계인지, `V$TRANSACTION.STATUS` 코드, `lock row retry count` 등의 뜻(차트에 넣지 않음, 벤더 질문).

### upstream 대비 변경점

- upstream에는 Altibase 대시보드가 없다(SWT 대시보드도 정의 없음). System Info에 폴더 하나와 이 번들의 첫 NLS 문구 클래스를 더했다.

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
- 2026-11-15 테이블 저장 방식을 글자색 대신 테이블 아이콘 색으로(앱 변경 #130, 사용자 요청): 메모리 빨강, 휘발성 주황빨강, 디스크는 보통 테이블(툴팁 없음),
  하이브리드는 보통 아이콘 + 툴팁. 글자색 토큰 3개를 아이콘 토큰 2개로 바꿈. 위 "테이블 저장 방식 표시"의 화면·구조·설계 결정.
- 2026-11-16 테이블스페이스 대화상자가 창 가운데에 더 넓게 뜨고(앱 변경 #132), 숫자 칸이 숫자와 마침표 하나만 받음(앱 변경 #133,
  `chromite/storage-dialog-fixes`). 확인: 디스크 테이블스페이스 `CHR_SD_TBS` 만들기와 데이터 파일 크기 4.5 MB 변경의 문장이 전과 같은 모양
  (`… SIZE 4M AUTOEXTEND ON NEXT 1M MAXSIZE 8M`, `… ALTER DATAFILE '…' SIZE 4608K`), 끝에 지움.
- 2026-11-17 ShardSphere에서 실패하던 문장을 두 서버 공통으로(앱 변경 #139 1단계): 잠금 대기의 UTC 현재 시각, 디스크 테이블스페이스 크기의 별칭 따옴표,
  DBMS_METADATA 서브프로그램을 연결마다 확인하고 없으면 카탈로그에서 주석, GET_DEPENDENT_DDL을 exec로(Altibase에서도 주석이 빠졌음), 이름으로 복제 찾기 보호.
  위 "ShardSphere에서 다른 문장".
- 2026-11-17 복제의 상태 다시 읽기를 목록의 조회 문장으로(프로시저 질의 복사 결함), 패키지 서브프로그램이 같은 이름의 독립 프로시저 상태를 읽던 것을 고침
  (앱 변경 #139 작업 1), 위 "상태 다시 읽기".
- 2026-11-17 Altibase 서버 확장 지점(`com.seizia.chromite.altibase.serverExtension`): 연결마다 감지해 데이터 원본에 두고, 어댑터를 먼저 묻고, System Info 아래 트리 자리
  (앱 변경 #139 작업 2·3, 첫 사용자는 ShardSphere 플러그인), 위 "서버 확장".
- 2026-11-17 서버 확장의 방언 기여(문법 낱말, 함수, 문장 접두어 길이)를 연결의 방언 객체에(`AltibaseSQLDialect.applyServerExtensions`, 앱 변경 #139 작업 6a),
  위 "서버 확장".
- 2026-11-17 서버 확장의 방언 기여에 접두어 문장의 하위 질의 형태와 입력 중인 접두어의 제안을 더함(앱 변경 #139 작업 6b), 위 "서버 확장".
- 2026-11-17 복제 카탈로그 전략(S2): 이 플러그인과 활동 모니터 복제 탭의 복제 문장을 연결의 카탈로그에서 받음(Altibase 문장은 같음), 서버 확장이 다른 카탈로그를 줌,
  제어 문장 능력으로 시작·중지·동기화를 숨김, 단위 없는 지연(앱 변경 #139 작업 4·5, `chromite/shardsphere-gemini-read`), 위 "복제 카탈로그".
- 2026-11-17 활동 모니터 공간 사용량 탭에 Altibase 계열(앱 변경 #139 작업 7): 트리와 같은 크기 문장을 정적으로 옮겨 함께 씀, 자동 확장을 끈 메모리·휘발성
  테이블스페이스의 한도를 현재 크기로(메모리는 MAXSIZE와 MEM_MAX_DB_SIZE 중 작은 쪽), 위 "공간 사용량".
- 2026-11-17 활동 모니터 개요 차트 8개와 System Info > 서버 정보(버전, 시작, 아카이브 로그, 백업, 앱 변경 #139 작업 8), 위 "개요 차트와 서버 정보".
