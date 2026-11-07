# 모델 후속 수정 (2026-11-07)

2026-11-06 통합 중 모은 후속 과제(followups-2026-11-06) 가운데 모델 번들의 결함 다섯 가지를 고친 기록이다(#47, #48, #50, #60, #70).
형식 매핑·Copy Tables의 기본 키(#39, #51, #52, #53)는 [swing-data-transfer.md](swing-data-transfer.md) "남은 매핑과 기본 키 (2026-11-07 밤)",
작은 수의 표시(#53)는 [swing-result-viewer.md](swing-result-viewer.md) "아주 작은 수의 표시"에 있다.
항목마다 증상, 근본 원인(파일:줄, 고치기 전 기준), 같은 원인의 다른 경우, 고친 방법을 적는다. 모두 DBeaver에서 온 코드라 SWT(DBeaver)에도 같은 결함이 있다.

## SQL Server 매개변수 종류 (#47)

- 증상: SQL Server 프로시저의 일반 매개변수가 `IN/OUT`, 스칼라 함수의 반환 값이 `OUT`으로 보임(Object Explorer 매개변수 노드, 속성의 Kind).
- 원인: `SQLServerProcedureParameter` 생성자(`:61-67`)가 `is_output`이 아니면 `is_readonly`로 IN과 INOUT을 나눴다. `sys.parameters.is_readonly`는
  테이블 반환 매개변수(`READONLY`)에만 1이라 일반 입력 매개변수는 모두 INOUT이 되었다. 반환 값은 `parameter_id` 0이고 `is_output` = 1이라 OUT이 되었다.
  시험 DB의 `sys.parameters`: `p_kinds.@a`(입력) `is_output 0, is_readonly 0`, `p_kinds.@b`(OUTPUT) `is_output 1`, `f_kinds`의 반환 값 `parameter_id 0, is_output 1`.
- 고친 것: `parameter_id` 0은 RETURN, 커서 참조는 RESULTSET(전과 같음), `is_output`은 OUT(전과 같음), 나머지는 IN.
- 같은 원인의 다른 경우: 종류를 쓰는 곳은 자동완성의 입력 매개변수(`isInput`), 호출 문장의 출력 매개변수 등록(`JDBCCallableStatementImpl`, `isOutput`),
  Object Explorer의 이름 없는 반환 값 표시(`ObjectExplorerPanel.isReturnValue`, 이제 종류로도 맞음)다. 입력 매개변수는 전에도 `isInput`이었고 이제 출력으로
  등록되지 않는다. OUTPUT 매개변수는 T-SQL에서 값을 받기도 하지만(입출력) 전처럼 OUT으로 두었다(이 항목 범위 밖).

## MariaDB 패키지 캐시 (#48)

- 증상: MariaDB(`sql_mode=ORACLE`)에서 밖에서 만든 패키지가 데이터베이스나 Packages 폴더를 새로 고쳐도 보이지 않음.
- 원인: `MySQLCatalog.refreshObject`(`:538-553`)가 테이블·인덱스·프로시저·트리거·이벤트·시퀀스 캐시는 비우면서 `packageCache`(`:72`)는 비우지 않았다.
  `getPackages`(`:371-375`)는 캐시가 차 있으면 다시 읽지 않는다. 같은 클래스의 다른 캐시는 모두 비운다.
- 고친 것: `refreshObject`가 `packageCache`도 비운다.

## PostgreSQL Block IO 대시보드 단위 (#50)

- 증상: Activity Monitor 개요의 PostgreSQL "Block IO" 차트가 값을 바이트(`… KB/s`)로 보임.
- 원인: 정의(`ext.postgresql` `plugin.xml` `postgresql.blockIO`)가 `value="bytes"`인데 질의는 `pg_stat_database`의 `blks_read`·`blks_hit`(블록 수)를 더한다.
  `calc="delta"`라 초당 변화량이 되므로 실제 단위는 블록/초다.
- 고친 것: `value="integer"`. 차트가 정수로 보이고 단위 꼬리표가 `/s`(블록/초)가 된다. 바이트로 바꾸려면 `block_size`를 곱해야 하는데, 이름("Block IO")과
  설명("IO operations per second")이 블록 수를 말하므로 단위를 정의에 맞췄다. 다른 `value="bytes"` 차트(MySQL 트래픽·InnoDB, SQL Server 파일 I/O)는
  실제로 바이트를 센다. 사용자 정의 대시보드만 설정 파일에 저장되므로(`DashboardRegistry.saveConfigFile`) 기존 작업 공간에도 바로 적용된다.
  자세한 것은 [swing-dashboard.md](swing-dashboard.md) "차트 정의".

## PostgreSQL 복사한 열의 정렬 규칙과 저장 방식 (#60)

- 증상: 기본이 아닌 정렬 규칙(`COLLATE "C"`)을 가진 `text` 열을 복사해 새 열을 만들고 형식을 `int4`로 바꾸면
  `ALTER TABLE … ADD name int4 STORAGE EXTENDED COLLATE "C"`가 되어 실패함("collations are not supported by type integer",
  "column data type integer can only have storage PLAIN" — PostgreSQL 17에서 두 문장을 따로 실행해 확인).
- 원인: 열은 형식과 따로 정렬 규칙(`PostgreAttribute.collationId`)과 저장 방식(`storage`)을 들고 있고, 복사(`PostgreAttribute` 복사 생성자 `:113`, `:120`)와
  형식 변경(`setFullTypeName`)에서 그대로 남는다. 열 정의를 만드는 `PostgreTableColumnManager.PostgreCollateModifier`(`:117-126`)와
  `PostgreStorageModifier`(`:79-84`)는 새 형식이 그 값을 받을 수 있는지 보지 않았다. PostgreSQL에서 정렬 규칙은 `pg_type.typcollation`이 0이 아닌 형식만,
  PLAIN이 아닌 저장 방식은 `typstorage`가 `p`가 아닌 형식만 받는다(`int4`·`bool`·`uuid`는 `p`, `typcollation` 0).
- 고친 것: `PostgreCollateModifier`는 열 형식에 기본 정렬 규칙이 있을 때만(`PostgreDataType.getCollationId` ≠ null), `PostgreStorageModifier`와
  서버가 열 정의의 `STORAGE`를 모를 때 쓰는 `ALTER COLUMN … SET STORAGE`(`addObjectCreateActions`)는 형식의 저장 방식이 PLAIN이 아닐 때만 붙인다
  (`acceptsStorage`). 값은 열에 남으므로 다시 문자형으로 돌리면 원래 정렬 규칙이 붙는다(SQL Server의 같은 수정과 같은 방식,
  [swing-followup-fixes.md](swing-followup-fixes.md) "SQL Server 열 정렬 규칙").
- 같은 원인의 다른 경우: 기존 열의 형식 변경(`ALTER COLUMN … TYPE`, `addObjectModifyActions`)은 정렬 규칙·저장 방식을 쓰지 않아 해당하지 않는다
  (PostgreSQL이 새 형식의 기본값으로 바꾼다). 기존 열의 정렬 규칙만 바꾸는 변경은 스크립트를 만들지 않는다(upstream 그대로, 이 항목 범위 밖).

## MySQL/MariaDB 계획의 비용 (#70)

- 증상: 그래픽 계획에서 `nested_loop` 같은 중간 노드의 "하위 트리 비용"이 0, 뿌리의 비용 %가 100%라 노드 %의 합이 200%.
- 원인: `MySQLPlanNodeJSON.getNodeCost`(`:142-151`)는 자기 비용이 없는 노드의 비용을 자식 비용의 합으로 정하는데 `long`으로 더했다(`longValue()`).
  MariaDB 11의 `cost`는 1보다 작은 소수(0.0059, 0.0832)라 합이 0이 되었다. 계획 그림(`PlanDiagramBuilder.createGenericOp`)은 노드 자체 비용을
  "노드 비용 − 자식 비용"으로 계산하므로, 자식 비용이 0으로 줄어든 뿌리에 문장 비용 전체가 몰렸다.
- 고친 것: 자식 비용을 `double`로 더한다. 같은 계획(MariaDB 11.8, `t1 JOIN t2`)에서 고치기 전 `select` 100%·`nested_loop#1` 비용 0·`t1` 6.6%·`t2` 93.4%(합 200%),
  고친 뒤 `select` 0%·`nested_loop#1` 비용 0.0059·`t1` 6.6%·`t2` 93.4%(합 100%). 자기 비용이 없는 중간 노드의 %는 0이 맞다(비용은 테이블 노드에 있음).
- 같은 원인의 다른 경우: `OceanbasePlanNodeJSON.getNodeCost`가 같은 코드를 복사해 쓴다. OceanBase의 `COST`가 정수인지 확인하지 못했고 시험 DB도 없어 고치지 않았다(아래 "남긴 것").
  행 수(`getNodeRowCount`)는 정수라 `long` 합이 맞다.

## 확인 (2026-11-07)

빌드한 Swing 제품(`-vm D:\Tools\jbrsdk-25\bin\server\jvm.dll`, 시험 작업 공간 사본)에 같은 프로세스 안에서 도는 시험 코드를 붙여 모델을 불렀다(OS 입력 없음).
데이터베이스: PostgreSQL 17(`chr_mfb` 스키마), SQL Server 2022(`chr_mfb`), MariaDB 11.8(`chr_mfb`).

- #47: `p_kinds` `@a` IN, `@b` OUT, `f_kinds` 반환 값 RETURN, `@x` IN.
- #48: 앱이 패키지 목록(0개)을 읽은 뒤 밖에서 `mfb_pkg`를 만들고, 다시 읽기만 하면 0개(캐시), `refreshObject` 뒤 1개.
- #50: 등록된 정의 `postgresql.blockIO valueType=integer calc=delta`.
- #60: `coll_src.name`(`text COLLATE "C"`)의 복사본 DDL — 형식 그대로 `ADD name text COLLATE "C" NULL`, `int4`로 바꾸면 `ADD name int4 NULL`,
  `varchar(20)`으로 바꾸면 `ADD name varchar(20) COLLATE "C" NULL`(정렬 규칙 유지). 고치기 전 빌드에서 `int4`는 `ADD name int4 STORAGE EXTENDED NULL`이었다
  (정렬 규칙만 고친 중간 빌드에서 확인. 그 전 코드는 `COLLATE "C"`도 붙인다).
- #70: 위 "MySQL/MariaDB 계획의 비용"의 숫자(고치기 전 값은 같은 JSON에 고치기 전 계산을 적용).
- 회귀 테스트는 실행하지 않았다(CLAUDE.md 7번). 영향받을 수 있는 기능: SQL Server 매개변수 종류를 쓰는 자동완성·프로시저 호출, MariaDB 데이터베이스 새로 고침,
  PostgreSQL 대시보드, PostgreSQL 새 열·복사한 열의 DDL(테이블 디자이너), MySQL/MariaDB 계획 그림의 비용.

## 남긴 것

- OceanBase 계획(`OceanbasePlanNodeJSON.getNodeCost`)도 자식 비용을 `long`으로 더한다. `COST`가 정수면 문제가 없고, 확인할 시험 DB가 없다.
- SQL Server OUTPUT 매개변수는 입력도 받지만 OUT으로 둔다. 자동완성은 OUTPUT 매개변수를 입력으로 제안하지 않는다.
- PostgreSQL 기존 열의 정렬 규칙만 바꾸는 변경은 스크립트를 만들지 않는다(upstream 그대로).

## 변경 이력

- 2026-11-07 최초 작성: SQL Server 매개변수 종류(#47), MariaDB 패키지 캐시(#48), PostgreSQL Block IO 단위(#50), PostgreSQL 복사한 열의 COLLATE·STORAGE(#60),
  MySQL/MariaDB 계획 비용(#70).
