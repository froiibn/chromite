# 모델 후속 수정 (2026-11-07)

2026-11-06 통합 중 모은 후속 과제(followups-2026-11-06) 가운데 모델 번들의 결함 다섯 가지를 고친 기록이다(#47, #48, #50, #60, #70).
형식 매핑·Copy Tables의 기본 키(#39, #51, #52, #53)는 [swing-data-transfer.md](swing-data-transfer.md) "남은 매핑과 기본 키 (2026-11-07 밤)",
작은 수의 표시(#53)는 [swing-result-viewer.md](swing-result-viewer.md) "아주 작은 수의 표시"에 있다.
2026-11-08의 작은 모델·안전·라이선스 수정 여섯 가지는 아래 "작은 모델·안전·라이선스 수정 (2026-11-08)"에 있다.
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

## 작은 모델·안전·라이선스 수정 (2026-11-08)

후속 과제 followups-2026-11-07-evening의 #17, #4, #5, #3, #12와 followups-2026-11-06의 #49다(`chromite/small-fixes-2`). 고치기 전에 모두 코드로 다시 확인했다.
줄 번호는 고치기 전 기준이다.

### 변경을 하는 모델 확인의 기본 단추 (#17)

- 증상: 모델이 묻는 확인 네 가지가 예를 기본 단추로 두어, 습관처럼 친 Enter로 테이블을 만들거나 파일을 옮기거나 작업을 시작했다.
- 원인: 호출자가 경고 표시 없이 `DBPPlatformUI.confirmAction(title, message)`를 불렀다. Swing은 `isWarning = true`일 때만 아니요를 기본으로 한다
  (`SwingPlatformUI.confirmAction`, [swing-frontend.md](swing-frontend.md) "실행 확인의 기본 단추").
- 고친 것: 네 호출에 `isWarning = true`를 넘긴다. 단추 이름(예/아니요)과 문구는 그대로다.

| 확인 | 위치 | 하는 일 |
|---|---|---|
| Oracle PLAN_TABLE missing | `OracleDataSource.getPlanTableName`(`:805`) | 실행 계획 테이블 `CREATE` |
| Generate DDL automatically | `DatabaseTransferUtils.generateTargetTableDDL`(`:227`) | 구조 DDL이 실패한 뒤 자동 생성 SQL로 대상 만들기·바꾸기 |
| Copy resource(s) / Move resource(s) | `DBNPathBase.dropNodes`(`:279`) | 파일 복사·이동 |
| Confirmation required to launch … | `DBTaskUtils.confirmTask`(`:211`) | "데이터베이스가 손상될 수 있다"는 작업(복원 등) 실행 |

- 같은 원인의 다른 경우: Swing 밖에서 `confirmAction`을 경고 없이 부르는 곳은 이제 세 곳이다(`plugins/` 검색). Vertica 비밀번호 바꾸기 권유(`VerticaDataSource`, 바꾸는 대화상자를 열 뿐),
  새 비밀번호를 연결 설정에 저장(`DBAuthUtils`), AI 메타데이터 전송 동의(`SQLCommandAI`)로, 데이터베이스나 파일을 바꾸지 않아 그대로 둔다
  ([swing-frontend.md](swing-frontend.md) "바꾸지 않은 것과 이유"). 나머지 모델 호출(SSH 호스트 키, Mimer 파일 이름 바꾸기, 확인 필요 연결 유형의 쿼리 등)은 이미 `isWarning`을 넘긴다.
  `DBNPathBase.dropNodes`는 `plugins/` 안에서 부르는 곳이 없다(모델 경로만 고침).

### Oracle DBMS_OUTPUT 끄기·켜기를 열린 세션에도 (#4)

- 증상: Options > Oracle에서 DBMS_OUTPUT을 끄면 쿼리 창은 읽기를 멈추지만 서버는 그 세션에서 계속 모았다(100만 바이트가 차면 `PUT_LINE`이 `ORU-10027`).
  켜면 다시 연결할 때까지 아무것도 나오지 않았다. 도구 설명이 "다시 연결한 뒤부터"라고 안내했다.
- 원인: 서버의 `DBMS_OUTPUT.ENABLE`/`DISABLE`은 세션마다 있는데, `OracleDataSource.initializeContextState`(`:339`)가 세션을 열 때만 설정에 맞췄다.
- 고친 것: 실행 문맥(`OracleExecutionContext`)이 마지막으로 보낸 상태를 기억하고, 새 기본 메서드 `DBCServerOutputReader.syncServerOutput`을 Oracle 읽기 도구가 구현한다
  (설정과 다르면 그 세션에 `ENABLE`/`DISABLE`). 쿼리 창은 문장을 실행하기 전에(`SQLQueryListener.onStartQuery`, 엔진 Job 스레드) 이것을 부른다.
  Options의 OK에서 바로 모든 세션에 보내지 않은 이유와 트랜잭션 영향(없음)은 [swing-server-output.md](swing-server-output.md) "설계 결정과 이유"에 있다.
  도구 설명을 "바꾸면 열린 쿼리 창에도 다음 문장부터 적용"으로 고쳤다(영어·한국어).
- 같은 원인의 다른 경우: YashanDB는 `OracleDataSource`를 상속해 함께 고쳐진다. Altibase(콜백 등록)·CUBRID(`dbms_output.enable`)도 세션을 열 때만 설정을 반영하지만,
  Swing에 그 설정을 바꾸는 화면이 없어 실행 중에 바뀌지 않는다. 화면이 생기면 같은 메서드를 구현한다.

### 슬래시를 쓴 Windows 경로 (#5)

- 증상: 내보내기 폴더 `D:/x`(예: `@export`의 `outputFolder`, 마법사에 직접 입력)가 "File system schema 'D' not recognized"로 실패했다. `D:\x`는 됐다.
- 원인: `DBFFileSystemManager.URI_SCHEME_PREFIX`(`:42`, `[a-z\d]+:/.+`)가 한 글자 `D`를 URI 스킴으로 보아 `getPathFromURI`로 보냈고, `D` 파일 시스템 제공자가 없어 실패했다.
  같은 모델의 `IOUtils.isLocalFile`은 `:/`가 둘째 글자에 있으면(드라이브 문자) 로컬 경로로 본다.
- 고친 것: 스킴을 두 글자 이상으로 바꿨다(`[a-z\d]{2,}:/.+`). 실제 스킴(`file`, `s3`, `gs`, `azure` 등)은 모두 두 글자 이상이다.
- 같은 원인의 다른 경우: 진행 모니터를 받는 `DBFUtils.resolvePathFromString`(프로젝트가 있으면 `getPathFromString`)을 부르는 14곳이 함께 고쳐진다
  (데이터 전송 출력 폴더·LOB 폴더·입력 파일·완료 뒤 폴더 열기, SQL 스크립트 작업, MySQL·PostgreSQL·Oracle 백업·복원·스크립트 작업).
  `DBFUtils.resolvePathFromString(DBRRunnableContext, …)`(스트림 가져오기 상태 복원)와 `DBFUtils.getPathFromURI`는 이미 `isLocalFile`로 걸러 영향이 없었다.

### 연결 유형 이름의 언어 (#3)

- 증상: 연결 유형을 저장하면 기본 유형(Development, Test, Production)의 이름과 설명이 그때의 화면 언어로 `connection-types.xml`에 저장됐다.
  영어 화면에서 저장한 뒤 한국어 화면에서 "Development"로 보였다(반대도 같음).
- 원인: `DataSourceProviderRegistry.saveConnectionTypes`(`:784`, `:787`)가 `getName()`·`getDescription()`(번역된 문구)을 그대로 쓰고, `ConnectionTypeParser`(`:958`, `:961`)가 읽은 값을 그대로 썼다.
- 고친 것: `DBPConnectionType.toStoredText`/`fromStoredText`. 기본 유형의 문구가 모델이 가진 어느 언어의 기본 문구와 같으면 영어 기본 문구로 저장하고,
  읽을 때 어느 언어의 기본 문구든 지금 화면 언어 문구로 바꾼다. 사용자가 입력한 이름은 그대로 저장하고 그대로 읽는다.
  번역은 모델 번들(과 언어 조각)의 `ModelResources*.properties`를 한 번 읽어 비교한다(`Bundle.findEntries`, UTF-8). 그래서 `ModelMessages.BUNDLE_NAME`을 public으로 바꿨다.
  - 이름 속성을 빼지 않고 영어로 쓰는 이유: 이 변경 전 빌드로 되돌려도 이름이 비지 않는다(영어로 보임).
  - 한계: 사용자가 기본 문구와 똑같은 이름(예: 한국어 "개발")을 입력하면 기본 이름으로 취급되어 다른 언어 화면에서는 번역된다.
- 같은 원인의 다른 경우: `data-sources.json`의 `connection-types` 사본에도 번역된 이름이 들어가지만, 읽을 때 이미 있는 유형(기본 유형은 늘 있음)은 건너뛰어(`DataSourceSerializerModern`) 화면에 쓰이지 않는다. 바꾸지 않았다.

### 드라이버 라이선스 표기 (#12)

- MySQL 5 (Legacy)는 `lgpl-2.0.txt`, StarRocks는 GNU 사이트의 GPLv2(URL)였다. 새 파일 `gpl-2.0-mysql-foss-exception.txt`와 `gpl-2.0-universal-foss-exception.txt`로 바꿨다.
  근거와 파일 내용은 [driver-licenses.md](driver-licenses.md) "plugin.xml의 라이선스 표기와 실제가 다른 항목".

### LICENSE.md의 JFreeChart (#49)

- `LICENSE.md`의 제3자 목록이 "JFreeChart - Apache 2.0"이었다. JFreeChart는 LGPL 2.1이고, 지금 제품에 없다(빌드 설정·`MANIFEST.MF`·feature에 없음, Swing 대시보드는 Java2D 차트).
  줄을 지웠다. 다시 넣으면 LGPL 2.1로 적는다([swing-dashboard.md](swing-dashboard.md) "차트 라이브러리").

### 확인 (2026-11-08)

`mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing` BUILD SUCCESS, `tools/rename/gate.py` ALL GATES PASSED.
빌드 결과물을 시험 작업 공간 사본과 별도 데이터 폴더(`CHROMITE_DATA`)로 한 번 실행하고, 같은 프로세스 안에 붙인 시험 드라이버로 화면을 조작했다(OS 입력 없음).
작업 공간·데이터 폴더는 확인 뒤 지웠다.

| 항목 | 확인 | 결과 |
|---|---|---|
| #4 | Oracle 쿼리 창(자동 커밋)에서 `PUT_LINE('probe')` 뒤 `GET_LINE`의 상태를 오류 문구로 보이는 블록을 실행. 켬 → Options에서 끔 → 같은 창에서 실행 → 다시 켬 → 실행(다시 연결하지 않음) | 켬 `server_status=0`, 끈 뒤 `server_status=1`(서버에서 꺼짐), 다시 켠 뒤 `server_status=0`이고 `visible line`이 Messages에 나옴 |
| #17 | Options > Oracle의 실행 계획 테이블을 없는 이름(`CHR_SF2_NO_PLAN`)으로 두고 예상 실행 계획 | "Oracle PLAN_TABLE missing" 단추 예(Y)/아니오(N), 기본·포커스 아니오. Enter로 닫힘, `all_tables`에 테이블 없음 |
| #5 | Oracle 쿼리 창에서 `@export {"type":"csv","consumer":{"outputFolder":"D:/Tools/_scratch/…/export",…}}` + `SELECT … FROM dual`, 마법사 마침 | "성공: 개체 1개 중 1개를 전송했습니다", 파일 `fwd_slash.csv`(2행) 생성 |
| #3 | 옛 형식 파일(영어 기본 이름 + 사용자 이름 "My QA")을 한국어 화면에서 열기 → 유형을 바꿔 저장 | 목록 개발 / My QA / 운영(설명도 한국어). 저장된 파일은 `Development`, `My QA`, `Production`과 영어 설명 |
| #3 | 한국어로 저장된 옛 파일(`개발`, `일반 개발 데이터베이스`)과 사용자 이름 `Производство`(러시아어 번역 아님)를 영어 화면(`-nl en`)에서 열기 | Development / My QA / Производство, 설명 Regular development database 등 |
| #12 | 빌드 결과물의 설치 폴더 `licenses/external/` | `gpl-2.0-mysql-foss-exception.txt`(19,108 bytes)가 있고 `gpl-2.0-universal-foss-exception.txt`(22,864 bytes) 앞 안내에 StarRocks 문단이 있음. 드라이버 설정의 License 탭은 열어 보지 않았다 |
| #49 | `LICENSE.md` 텍스트만 바뀜 | |

- 회귀 테스트는 실행하지 않았다(요청 없음). 영향받을 수 있는 기능: 모델 확인 네 가지(Enter가 이제 아니요), Oracle·YashanDB 쿼리 창의 문장 실행(문장 전에 상태 확인, 바뀐 경우만 SQL 한 번),
  `getPathFromString`을 쓰는 모든 파일 경로(한 글자 스킴은 이제 로컬 경로), 연결 유형 저장·읽기, MySQL 5 (Legacy)·StarRocks 드라이버의 License 탭.

## 후속 수정 3 (2026-11-08)

후속 과제 followups-2026-11-07-evening의 #18, #19, #20, #23, #24, #27(2·3부), #28, #30, #31, #33이다(`chromite/followups-3`). 고치기 전에 모두 코드로 다시 확인했다.
줄 번호는 고치기 전 기준이다. 다른 문서에 적은 항목: #18·#20 [driver-licenses.md](driver-licenses.md), #19 [swing-server-output.md](swing-server-output.md),
#23 [swing-grid-column-filters.md](swing-grid-column-filters.md), #27 [ai-agent.md](ai-agent.md), #28 [swing-sql-terminal.md](swing-sql-terminal.md).

### Oracle 계획 노드의 부모 번호 (#24)

- 증상: `OraclePlanNode.getParentId`가 늘 0이었다. 계획을 저장 형식(JSON, `OracleQueryPlanner.serialize`)으로 쓰면 모든 노드의 `parent_id`가 0이었다.
- 원인: 필드 `parentId`(`:90`)에 값을 넣는 곳이 없었다. 두 생성자 모두 `PARENT_ID`로 부모 노드만 찾았다(`:233-240`, `:281-287`).
- 같은 원인의 다른 경우: 저장한 계획을 읽을 때(`OracleQueryPlanner.deserialize`) 노드 생성자가 `parent_id`(늘 0)로 부모를 찾아 스스로 그 자식으로 붙었고,
  읽는 쪽(`ExecutionPlanDeserializer.loadNode`)도 저장한 트리대로 자식으로 붙였다. 그래서 뿌리 밑에 모든 노드가 한 번 더 들어갔다. Altibase(`AltibasePlanNode`의
  JSON 생성자 `:87-104`)도 같은 구조라 자식이 두 번 들어갔다(Altibase는 `parent_id`를 올바르게 썼으므로 같은 부모에 두 번). 다른 DB(MySQL, PostgreSQL,
  Exasol, OceanBase, Ocient)의 노드는 읽는 쪽이 넘긴 부모만 쓰고 스스로 붙지 않는다.
- 고친 것: 결과 집합 생성자가 `PARENT_ID`를 `parentId`에 담는다. 저장한 계획의 노드(Oracle·Altibase)는 읽는 쪽이 넘긴 부모를 쓰고 스스로 붙지 않으며 `parent_id`는 값으로만
  담는다. 저장한 트리는 보이는 계획(적응 계획의 쓰지 않는 행을 뺀 것, [swing-sql-editor-plus.md](swing-sql-editor-plus.md) "Oracle 적응 계획")이라 그대로 읽으면 같은 모양이다. 쓰지 않게 된 `IntKeyMap` 노드 목록을 지웠다.
- 지금 Swing에는 계획을 저장하거나 불러오는 화면이 없다(`serialize`·`deserialize`를 부르는 곳이 `ui.swing`에 없음). 그래서 앱 안의 시험 코드로 모델 API를 불렀다:
  Oracle 23ai에서 `t1 JOIN v1 … ORDER BY` 예상 계획(노드 5개) → `serialize` → `deserialize`. 앞뒤 트리가 같다(id 0 뿌리, 1 SORT의 부모 0, 2 HASH JOIN의 부모 1,
  3·4 TABLE ACCESS의 부모 2, 자식 수 1·1·2·0·0, 겹친 자식 없음). `getParentId`는 0·0·1·2·2. Altibase는 코드 경로만 확인했다.

### Oracle 형식 컴파일의 개체 종류, SQL Server 루틴 매개변수의 길이와 정밀도 (#31)

- Oracle: `OracleDataType.getCompileActions`(`:292`)가 `ALTER TYPE … COMPILE` 동작의 개체 종류를 `OracleObjectType.VIEW`로 적었다. `TYPE`으로 고쳤다.
  지금 Swing의 Compile 명령은 오류를 이 값이 아니라 `OracleCompileErrors.getErrorTypes`(형식은 TYPE·TYPE BODY)로 읽어 화면은 달라지지 않는다.
  같은 클래스를 쓰는 다른 `getCompileActions`(뷰, 구체화된 뷰, 패키지, 프로시저, 트리거, Java 클래스)는 모두 자기 종류다.
- SQL Server 길이: `SQLServerProcedureParameter`(`:76`)가 `sys.parameters.max_length`(바이트)를 그대로 길이로 썼다. `nvarchar(50)`이 `nvarchar(100)`,
  `nchar(10)`이 `nchar(20)`으로 보였고, 실행 대화상자가 만드는 OUTPUT 변수 선언(`DECLARE @label nchar(20)`, `ProcedureCallScript.typeOf`)도 그 길이였다.
  테이블 열(`SQLServerTableColumn.loadInfo`)이 쓰는 `SQLServerUtils.getDisplayMaxLength`(nchar·nvarchar는 2로 나눔)를 매개변수에도 쓴다. `-1`(MAX)은 그대로다.
- SQL Server 정밀도: `decimal(5,2)`가 `decimal(38,38)`로 보이던 곳은 매개변수 자신이 아니라 데이터 형식(`getParameterType()`, 형식의 기본 정밀도)의 이름을
  쓰던 곳이다. 실행·디버거 대화상자의 인수 표는 B5에서 이미 고쳤고(`RoutineArgumentTable`), 남은 곳은 편집기의 루틴 설명(마우스 올림, `SqlObjectNavigation`)이었다.
  인수 표와 같이 매개변수 자신(`DBSTypedObject`)의 형식을 쓴다. 디버거의 루틴 식별 키(`DebugCommands`, 화면에 보이지 않는 오버로드 구분)는 그대로 두었다.
- 확인(SQL Server 2022, `chr_followups_3.dbo.fu3_proc(@title nvarchar(50), @amount decimal(5,2), @code varchar(20), @label nchar(10) OUTPUT)`):
  `sys.parameters`는 100·5,2·20·20. 한국어 화면의 Execute Procedure 인수 표가 `nvarchar(50)`, `decimal(5,2)`, `varchar(20)`, `nchar(10)`, "쿼리 창에서 열기"의
  스크립트가 `DECLARE @label nchar(10) = NULL;`. 마우스 올림 설명과 Oracle 형식 컴파일은 코드 경로만 확인했다.

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
- 2026-11-08 "작은 모델·안전·라이선스 수정": 변경을 하는 모델 확인 네 곳의 기본 단추(#17), Oracle DBMS_OUTPUT을 열린 세션에도 적용(#4), 슬래시 Windows 경로(#5),
  연결 유형 이름의 언어(#3), MySQL 5 (Legacy)·StarRocks 라이선스 표기(#12), `LICENSE.md`의 JFreeChart 줄(#49).
- 2026-11-08 "후속 수정 3": Oracle 계획 노드의 부모 번호와 저장한 계획 읽기(#24, Altibase 같은 원인).
- 2026-11-08 "후속 수정 3": Oracle 형식 컴파일의 개체 종류, SQL Server 루틴 매개변수의 길이와 루틴 설명의 정밀도(#31).
