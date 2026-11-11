# Swing 개체 탐색기 개체 작업 (삭제, 이름 바꾸기, 이름 복사, 여러 개 선택, SQL 생성, 유지 관리 명령)

## 목적

SWT 전수 조사([swt-parity-audit.md](swt-parity-audit.md)) 3순위의 탐색기 줄을 옮긴다: "탐색기에서 개체 삭제·이름 바꾸기, 이름 복사,
여러 개 선택, Generate SQL의 INSERT/UPDATE/DELETE 등"(영역 C의 #10, #11, #12, #17). 모두 개체 탐색기의 오른쪽 클릭 메뉴와 키보드에 있다.

- **삭제**(Delete 키, 메뉴 "삭제"): 테이블·뷰·루틴·시퀀스·인덱스·스키마·데이터베이스 등 객체 관리자가 지울 수 있는 DB 객체. 여러 개를 한 번에.
- **이름 바꾸기**(F2, 메뉴 "이름 바꾸기"): 객체 관리자가 이름을 바꿀 수 있는 DB 객체.
- **이름 복사**(Ctrl+C, 메뉴 "이름 복사"), **정규화된 이름 복사**(Ctrl+Shift+C, 메뉴): 고른 객체마다 한 줄.
- **여러 개 선택**: 트리에서 Ctrl·Shift로 여러 노드를 고른다. 삭제, 이름 복사, SQL 생성은 선택 전체에 적용하고 나머지 명령은 꺼진다.
- **SQL 생성**(메뉴 "SQL 생성" ▸ SELECT, INSERT, UPDATE, DELETE, MERGE, SIMPLE JOIN, INNER JOIN, CALL, DDL, PostgreSQL INSERT ON CONFLICT·UPDATE FROM·DELETE USING 등):
  결과는 새 쿼리 창에 연다(실행하지 않음).

## 화면

- 오른쪽 클릭 메뉴: "스크립팅" 아래에 "SQL 생성" 하위 메뉴, "연결 편집" 아래에 "이름 복사", "정규화된 이름 복사", 그다음 기존 "이름 바꾸기", "삭제".
  다른 기능 항목(디버거 등)과 위치가 겹치지 않게 한 줄씩 넣었다.
- 삭제 확인(경고 아이콘, 기본 단추 "취소"):
  - 한 개: "{연결}에서 {형식} "{정규화된 이름}"을(를) 삭제하시겠습니까?"
  - 여러 개: "{연결}에서 다음 개체 N개를 삭제하시겠습니까?"와 이름·형식 표(10줄까지 보이고 넘으면 스크롤).
  - 객체 관리자가 지원할 때만 "종속 개체도 삭제(CASCADE)", "기존 연결 닫기"(데이터베이스 삭제) 확인란.
- 이름 바꾸기: "{형식} 이름 바꾸기" 입력 창(지금 이름이 들어 있음). 연결 노드의 이름 바꾸기와 같은 모양이다.
- 스크립트 미리 보기(`ScriptPreviewDialog`, 기본 단추 "취소"): 머리글 "다음 스크립트를 {연결}에서 실행합니다. 지금 실행하시겠습니까?",
  DDL을 바로 커밋하는 DB면 "이 데이터베이스는 DDL 문을 바로 커밋하므로 변경을 롤백할 수 없습니다." 한 줄이 더 붙는다.
  단추: 복사, 쿼리 창에서 열기(실행하지 않고 새 쿼리 창으로), 실행, 취소.
- 실행을 고르면 그 연결에 커밋하지 않은 트랜잭션이 있을 때 먼저 커밋 / 롤백 / 취소를 묻는다("트랜잭션").

## 구조

| 위치 | 역할 |
|---|---|
| `ExplorerObjectCommands`(새 파일) | 판단과 실행: 삭제·이름 바꾸기(명령 만들기, 스크립트, 실행), 이름 복사, 트리 전송 처리기, SQL 생성 메뉴 |
| `ObjectExplorerPanel` | 메뉴 항목·키 연결, 여러 개 선택(`DISCONTIGUOUS_TREE_SELECTION`), `getSelectedNavigatorNodes`, 오른쪽 클릭이 선택을 유지 |
| `ObjectScripts` | SQL 생성기 고르기(`getGenerators`)와 실행(`generate`) |
| `ScriptPreviewDialog.confirmChange` | 개체 변경 스크립트 확인(쿼리 창으로 열기 있음, 취소가 기본 단추, 여러 줄 머리글) |
| `ExitTransactionCheck.confirmObjectChange` | 실행 전 열린 트랜잭션 질문(끝낼 때·연결 끊을 때와 같은 찾기·질문·끝내기 코드) |
| `internal/NavigatorMessages`(+`_ko`) | 화면 문구 29개(`navigator_copy_*`, `navigator_generate_sql*`, `navigator_rename_*`, `navigator_delete_*`, `navigator_change_*`) |

모델 쪽: `DBEObjectMaker`(삭제, `getMakerOptions`의 `FEATURE_DELETE_CASCADE`·`FEATURE_CLOSE_EXISTING_CONNECTIONS`), `DBEObjectRenamer`,
`AbstractCommandContext`, `DBExecUtils.tryExecuteRecover`, SQL 생성기 확장점 `com.seizia.chromite.sqlGenerator`
(`model.sql/plugin.xml`, `ext.postgresql/plugin.xml`, `ext.firebird/plugin.xml`)와 `SQLGeneratorConfigurationRegistry`. 모델 코드는 고치지 않았다.

## SWT에서 옮긴 것 (근거, 커밋 `263b8ddb45`)

| SWT | Swing |
|---|---|
| `NavigatorHandlerObjectDelete.tryDeleteObjects`: 서로 다른 연결의 객체를 함께 지우지 않음 | 같은 판단(`getContainer`), 경고 후 중단 |
| `NavigatorNodesDeletionConfirmations`: 확인(여러 개면 이름·형식·설명 표), 기본 답 취소, 지원하는 옵션 확인란 | 확인 창(이름·형식 표), 기본 단추 취소, 같은 옵션 두 개 |
| `NavigatorObjectsDeleter`: 객체마다 `getCommandTarget` → `SimpleCommandContext(DBUtils.getDefaultContext(object, false), true)`, `objectMaker.deleteObject`, `ObjectSaver`가 `saveChanges(monitor, deleteOptions)` | 객체마다 같은 원자적 `AbstractCommandContext`, 같은 호출. 옵션은 그 객체의 관리자가 지원할 때만 넣는다(SWT `collectObjectMakerOptionsMap`) |
| `NavigatorObjectsDeleter.appendScript`: 최종 명령의 `getPersistActions` → `SQLUtils.generateScript`(비면 `generateComments`) | 같은 방법으로 미리 보기 스크립트 |
| `NavigatorHandlerObjectRename.renameDatabaseObject`: `DBEObjectRenamer.renameObject`(옵션 `OPTION_UI_SOURCE`), 스크립트 보기 후 `ObjectSaver` | 같은 호출, 스크립트는 언제나 보임 |
| `NavigatorHandlerCopyObject`: 객체 이름(`DBUtils.getObjectShortName`), 여러 개면 줄마다 | 같음(Ctrl+C, Edit ▸ 복사도) |
| `NavigatorHandlerCopySpecial`: 정규화된 이름 | `DBUtils.getObjectFullName(…, DML)`(아래 "설계 결정") |
| `SQLGeneratorContributor.getContributionItems`: `getApplicableGenerators`, 1000 단위로 구분선, 결과를 `SQLGeneratorDialog`로 보임 | 같은 생성기 목록과 구분선, 결과는 새 쿼리 창 |

## 설계 결정과 이유

- **원자적 명령 묶음.** `AbstractCommandContext(context, atomic=true)`는 저장이 끝난 뒤에야 반영기(reflector)를 돌린다
  (`AbstractCommandContext.java` `executeCommands`의 `if (atomic)`). 그래서 미리 보기에서 취소하면 객체 이름·캐시·탐색기 노드가 그대로다.
  테이블 디자이너는 편집 중 화면에 바로 보여야 해서 비원자적 묶음을 쓴다. 저장이 끝나면 모델이 `DBUtils.fireObjectRemove`(삭제),
  `fireObjectUpdate(RENAME)`(이름 바꾸기)를 보내고, 탐색기는 모델 이벤트(`navigatorNodeChanged`)로 노드를 빼거나 다시 그린다.
- **미리 보기는 언제나.** SWT는 "View Script"를 눌러야 스크립트를 보였다. DROP은 되돌릴 수 없고, 브리프가 실행 전 스크립트 미리 보기를 요구한다.
  그래서 연결 유형의 "데이터 변경 확인"을 따로 볼 필요가 없다(늘 확인한다). 미리 보기에서 "쿼리 창에서 열기"를 고르면 실행하지 않고 쿼리 창에 넣는다.
- **제한.** 읽기 전용 연결(`isConnectionReadOnly`)이나 메타데이터 변경 제한(`PERMISSION_EDIT_METADATA`)·읽기 전용 메타데이터(`DBUtils.isReadOnly`)면
  확인 창을 띄우지 않고 알린다. 테이블 디자이너의 저장(`TableDesignSession.save`)과 같은 권한이다. 메뉴는 끄지 않는다(왜 안 되는지 알려 주려고).
- **트랜잭션.** 모델 저장 경로는 명령마다 커밋한다(`AbstractCommandContext.executeCommands`의 `txnManager.commit`, 수동 커밋이면 그 전에
  자동 커밋을 끄고 끝나면 되돌림). 쿼리 창은 연결의 같은 기본 문맥을 쓰므로(감사 3순위 "쿼리 창마다 별도 연결") 수동 커밋 쿼리 창의
  열린 트랜잭션이 DDL과 함께 조용히 커밋된다. 다른 문맥의 열린 트랜잭션은 DROP이 기다릴 잠금을 잡고 있을 수 있다. 그래서 실행 직전에 그 연결의
  모든 문맥(AI 도구 격리 문맥 포함)의 열린 트랜잭션을 찾아 커밋 / 롤백 / 취소를 묻는다(`ExitTransactionCheck`의 같은 코드, 사용자 규칙 "트랜잭션 안전").
  취소하면 아무것도 실행하지 않고 명령을 버린다.
- **DDL 바로 커밋 안내.** JDBC `DatabaseMetaData.dataDefinitionCausesTransactionCommit()`을 미리 보기 작업에서 읽는다(MySQL·MariaDB·Oracle은 true,
  PostgreSQL·SQL Server는 false). 모델의 `DBCTransactionManager.getDDLTransactionBehavior()`는 "DDL이 다른 세션에 언제 보이는가"를 답하고,
  지원 플래그가 둘 다 true인 드라이버를 `TRANSACTIONAL`로 정하므로 이 질문에 쓰지 않았다. DB 이름으로 분기하지 않는다.
- **여러 개 선택.** `getSelectedNavigatorNode()`가 둘 이상 선택에서 null을 돌려준다. 한 노드용 명령(새 쿼리, 디자인, 스크립팅 등)과 속성 창은
  기존 판단 그대로 꺼진다. 삭제·이름 복사·SQL 생성만 `getSelectedNavigatorNodes()`(트리 순서)를 쓴다. 오른쪽 클릭이 이미 고른 행 위면 선택을 바꾸지 않는다.
  F5·Enter는 첫 번째 고른 노드에 적용된다(이전과 같은 `getSelectionPath`).
- **이름 복사.** 이름은 `DBSObject.getName()`(SWT Copy와 같음, 따옴표 없음). 정규화된 이름은 SQL에 붙여 넣을 수 있게 `DBPEvaluationContext.DML`로
  만든다(방언의 따옴표, PostgreSQL `public."My Table"`, SQL Server `db.dbo.table`). 연결·폴더 노드는 이름 복사에서 트리 글자를 복사하고,
  정규화된 이름 복사는 DB 객체만 고른 때만 켜진다. Ctrl+C와 Edit ▸ 복사는 트리의 전송 처리기(`createTransferHandler`)를 지나므로 Swing 기본(노드 글자,
  열은 이름만) 대신 같은 이름이 복사된다.
- **SQL 생성 결과는 새 쿼리 창.** SWT는 결과를 보기 대화상자(복사·옵션)로 보였다. Swing에서는 기존 "스크립팅 ▸ 새 쿼리 편집기 창"과 같은 흐름이
  자연스럽고, 쿼리 창에서 복사·실행·저장이 모두 된다. 생성기 옵션(정규화된 이름, 간단한 SQL 등) 화면은 없다(생성기 기본값, "남긴 것").
- **생성기 대상 거르기.** 생성기는 객체를 자기 형식으로 바꿔 쓰므로(SELECT 생성기는 테이블), 테이블과 프로시저를 함께 고르면 SWT는 실패할 수 있었다.
  각 생성기에 그 생성기가 받는 객체만 넘긴다(`ObjectScripts.generate`).
- **DDL 항목.** "SQL 생성 ▸ DDL"은 등록된 `SQLGeneratorDDL`이고 "스크립팅 ▸ CREATE 대상"·"DDL 보기"가 쓰는 생성기와 같다. 메뉴를 따로 만들지 않았다.

## upstream 대비 변경점

- 미리 보기가 늘 나온다(upstream은 선택).
- 실행 전 열린 트랜잭션 질문(upstream 탐색기 삭제·이름 바꾸기에는 없음).
- DDL 바로 커밋 안내 문구.
- 정규화된 이름 복사가 `DML` 문맥(upstream "Copy Advanced"는 `UI`).
- SQL 생성 결과를 대화상자 대신 쿼리 창에 연다. 섞인 선택에서 생성기마다 맞는 객체만 넘긴다.
- 모델 번들은 바꾸지 않았다.

## 확인 (2026-11-07)

Swing 제품(`-Pproduct-chromite-swing`)을 복사한 작업 공간과 개인 데이터 폴더로 한 번 띄우고, 프로세스에 붙인 EDT 시험 에이전트
(공개 Swing API만 씀, OS 입력·전경 창 없음)로 확인했다. 스크린숏은 창만 그린 것이다(`D:\Tools\_scratch\p3-navigator-actions\shots\`).
시험용 스키마·데이터베이스(PostgreSQL `p3nav`, SQL Server `p3nav`, MariaDB `p3nav_m`)는 확인 뒤 지웠다.

- PostgreSQL 17(55432, 한국어):
  - 메뉴: 테이블 하나에서 "SQL 생성" ▸ SELECT, INSERT, UPDATE, DELETE, MERGE, INSERT ON CONFLICT, UPDATE FROM, DELETE USING | DDL.
    세 개 선택에서 한 노드용 명령(새 쿼리, 디자인, 스크립팅, DDL 보기 등)과 이름 바꾸기는 꺼지고 삭제·이름 복사·SQL 생성만 켜짐(SIMPLE JOIN, INNER JOIN이 더해짐).
  - 이름 복사: `Mixed Case`, `p3_items`, `p3_orders`(줄마다). 정규화된 이름 복사: `p3nav."Mixed Case"`, `p3nav.p3_items`, `p3nav.p3_orders`.
    Ctrl+Shift+C는 `chromite.copyQualifiedName`, 트리의 Ctrl+C(`copy`, 전송 처리기)는 이름을 복사.
  - 이름 바꾸기(F2): 입력 창 → 미리 보기 `ALTER TABLE p3nav.p3_rename_me RENAME TO p3_renamed;`(기본 단추 취소) → 실행. DB와 트리 모두 `p3_renamed`.
  - 두 개 삭제(`p3_items`, `p3_orders`): 확인(목록, CASCADE 확인란) → 미리 보기 `DROP TABLE …` 두 줄 → 실행. `p3_items`는 지워지고,
    `p3_orders`는 뷰 `p3_view`가 기대어 PostgreSQL 오류 2BP01로 실패해 "p3nav.p3_orders을(를) 삭제하지 못했습니다." 오류 창(트리에는 남음).
    다시 `Mixed Case`·`p3_orders`를 CASCADE로 지우면 `DROP TABLE … CASCADE;` 두 줄이 실행되고 DB에서 뷰도 사라짐.
  - SQL 생성 9개 항목이 모두 새 쿼리 창에 열림(실행하지 않음, 예: `INSERT INTO p3nav.p3_renamed (id, note) VALUES(0, '');`, DDL은 `CREATE TABLE …`).
- SQL Server 2022(51433, 한국어, 연결을 수동 커밋으로 둔 복사 작업 공간):
  - 이름 바꾸기 미리 보기: `EXEC p3nav.sys.sp_rename N'p3nav.dbo.p3_rename_me', N'p3_renamed', 'OBJECT';` → 실행, DB 확인.
  - 쿼리 창에서 `INSERT INTO p3nav.dbo.p3_renamed …`를 실행해 트랜잭션을 연 채로(상태 줄 "열린 트랜잭션") `p3_items`·`p3_orders` 삭제 → 미리 보기에서 실행 →
    "커밋하지 않은 트랜잭션" 질문(SQL Server 테스트 (Main), 커밋 / 롤백 / 취소, 기본 취소) → 롤백 → 두 테이블이 지워지고 `p3_renamed`의 행 수 0(INSERT가 롤백됨).
    CASCADE 확인란은 나오지 않음(SQL Server 관리자가 지원하지 않음).
  - 정규화된 이름 복사: `p3nav.dbo.p3_renamed`, `p3nav.dbo.p3_view`. SQL 생성: 테이블 SELECT·INSERT·UPDATE·DELETE·MERGE·DDL, 프로시저 CALL
    (`EXEC @return_value = [dbo].[p3_proc]` …). 테이블과 프로시저를 함께 고른 SELECT는 테이블만, DDL은 둘 다 만듦.
- PostgreSQL(영어, `-nl en`): 메뉴 "Generate SQL", "Copy Name", "Copy Qualified Name", 확인 "Delete these 2 objects from PostgreSQL 테스트?",
  "Also delete dependent objects (cascade)", 미리 보기 "The following script runs on PostgreSQL 테스트. Execute it now?", 입력 창 "Rename Table".
- MariaDB 11(53306, 영어): 두 테이블 삭제 미리 보기 머리글에 "This database commits each DDL statement at once; the change cannot be rolled back."이 붙음
  (PostgreSQL·SQL Server에는 없음).
- 처음 빌드에서 삭제 확인의 목록이 두 줄인데도 세로 스크롤바가 보였다: 150% 배율에서 옵션 창이 목록을 원하는 높이보다 1픽셀 작게(47/48) 배치했다.
  목록이 모두 들어가면 세로 스크롤바를 두지 않게 고쳤고 다시 확인했다.
- 로그(`chromite-debug*.log`, `.log`): 위에서 일부러 만든 두 오류(뷰가 기대는 테이블 삭제, master에서 실행한 INSERT) 말고 없음.
- 회귀 테스트는 실행하지 않았다(요청 없음). Oracle은 확인하지 않았다.

## 남긴 것

- 열 삭제 때 종속 객체 목록 확인(SWT `confirmDependenciesDelete`, `DBEObjectWithDependencies`). 지금은 DB 오류로 알 수 있다.
- 열려 있는 테이블 디자이너의 객체를 지울 때 디자이너를 닫거나 그 편집으로 넘기는 것(SWT `deleteNewObject`·편집기 명령 대상).
- 인라인(트리 안) 이름 바꾸기. 지금은 입력 창이다(연결 이름 바꾸기와 같음).
- Copy Special(고급 복사 대화상자), 붙여넣기로 객체·연결 복제(C-12의 나머지).
- SQL 생성기 옵션 화면(정규화된 이름 끄기, 간단한 SQL, 자동 생성 열 빼기 등).
- 폴더 노드에서의 SQL 생성(SWT는 폴더의 부모 객체로 생성했다).
- CASCADE로 함께 지워진 다른 객체(예: 테이블에 기댄 뷰)는 새로 고칠 때까지 트리에 남는다. 모델은 지운 객체만 알린다(SWT도 같음).
- 여러 개 삭제 중 하나가 실패하면 앞의 객체는 이미 지워져 있고 나머지는 실행하지 않는다(명령마다 커밋). 오류 창이 실패한 객체 이름을 알린다.
- 회귀 테스트는 실행하지 않았다(요청 없음).

## 유지 관리 명령 (2026-11-08)

SWT 감사 3순위 "TRUNCATE·트리거 켜기/끄기·구체화된 뷰 새로 고침"(D-db-specific P23·S14·M17·O21, C #44). SWT는 작업(task) 도구와 도구 마법사로
실행했다(`pgToolTableTruncate`, `oracleToolTriggerEnable` 등, `SQLToolTaskWizard`). Swing은 Object Explorer 오른쪽 클릭 메뉴의 "유지 관리" 아래에 둔다.

| 메뉴 | 보이는 노드 | 문 (모델 도구 처리기 `generateObjectQueries`) |
|---|---|---|
| 테이블 잘라내기 | PostgreSQL 테이블(외부 테이블 제외), MySQL/MariaDB·Oracle·Altibase·SQL Server 테이블 | PG `TRUNCATE TABLE t CONTINUE IDENTITY RESTRICT`(`PostgreToolTableTruncate`), MySQL `TRUNCATE TABLE t`(`MySQLToolTableTruncate`), Oracle `TRUNCATE TABLE t`(`OracleToolTableTruncate`), Altibase `TRUNCATE TABLE t`(`AltibaseToolTableTruncate`), SQL Server `TRUNCATE TABLE db.schema.t`(모델 도구 없음, 직접) |
| 트리거 사용 / 트리거 사용 안 함 | PostgreSQL 테이블·이벤트 트리거, SQL Server 테이블 트리거, Oracle 테이블 트리거 | `ALTER TABLE t ENABLE/DISABLE TRIGGER tr`(PG, SQL Server), `ALTER TRIGGER s.tr ENABLE/DISABLE`(Oracle) |
| 구체화된 뷰 새로 고침 | PostgreSQL·Oracle·Altibase 구체화된 뷰 | `REFRESH MATERIALIZED VIEW v WITH DATA`(PG), `CALL DBMS_MVIEW.REFRESH('s.v','')`(Oracle, 뷰의 새로 고침 방식), `EXEC SYSTEM_.REFRESH_MATERIALIZED_VIEW('S', 'V')`(Altibase) |
| 컴파일 | Oracle 저장 코드·뷰·구체화된 뷰·트리거·사용자 형식, Altibase 뷰·프로시저·함수·패키지 | Oracle `getCompileActions`(`ALTER … COMPILE`, 패키지는 `COMPILE BODY`도), Altibase 도구의 `ALTER … COMPILE`. 결과와 오류 목록은 [swing-routines.md](swing-routines.md) "컴파일" |

- **흐름**: (TRUNCATE만) 경고 확인 → 스크립트 미리 보기(삭제와 같은 머리글, 취소가 기본 단추, 쿼리 창으로 열기) → 열린 트랜잭션 질문 → 실행.
- **TRUNCATE 확인**: #120의 규칙(쿼리 창의 TRUNCATE는 늘 확인)을 따른다. "{연결}에서 테이블 {이름}의 모든 행을 삭제하시겠습니까?"(여러 개면 목록),
  "삭제한 행은 되살릴 수 없습니다", DDL을 바로 커밋하는 DB(JDBC `dataDefinitionCausesTransactionCommit`: MySQL·MariaDB·Oracle)는
  "{DB}에서는 TRUNCATE가 암시적으로 커밋되므로 롤백할 수 없습니다"를 더한다. 단추는 잘라내기 / 취소(기본).
- **메뉴**: 선택한 노드가 모두 그 명령을 지원하고 한 연결에 있을 때만 보인다(여러 개 선택 가능). 트리거는 모델이 읽은 상태가 이미 그 상태면 그 항목을 끈다
  (PG `tgenabled = 'D'`, SQL Server `is_disabled`, Oracle `STATUS`). 실행 뒤 트리거 폴더를 다시 읽어 상태가 맞게 보인다.
- **실행 위치**: 보안 편집기처럼 대상 인스턴스의 격리된 실행 문맥에서 실행한다. 사용자 쿼리 창의 세션·트랜잭션을 건드리지 않는다. 연결이 수동 커밋이면 끝에서 커밋하고
  실패하면 되돌린다(실행 전 열린 트랜잭션은 질문으로 끝냈다). 모델 도구의 `executeTask`(작업 로그, 기본 실행 문맥, 자동 커밋 강제)는 쓰지 않는다.
- **도구 설정**: 처리기 기본값. 다만 PostgreSQL 구체화된 뷰 새로 고침의 기본값은 `WITH NO DATA`(뷰를 비움)라 `WITH DATA`로 둔다.
- **구조**: `ObjectMaintenanceCommands`(Swing, 새 파일: 메뉴·확인·미리 보기·실행·새로 고침), `internal/ObjectMaintenanceMessages`(+`_ko`, 18개),
  `model.admin`의 `objects/ObjectMaintenanceActions`(새 패키지, 내보냄: 지원 판단, 트리거 상태, 스크립트, 실행). `ObjectExplorerPanel`은 만들기·메뉴에 넣기·갱신 세 줄.
  SQL Server 트리거 도구를 쓰려고 `ext.mssql` MANIFEST가 `com.seizia.chromite.ext.mssql.tasks`를 내보낸다(한 줄).

### 확인 (2026-11-08)

같은 제품·작업 공간·EDT 시험 에이전트로 했다(스크린숏 `D:\Tools\_scratch\p3-oracle-altibase-admin\shots\`). 결과는 `docker exec`로 DB에서 읽었다.

| DB | 명령 | 미리 보기 | DB 결과 |
|---|---|---|---|
| PostgreSQL 17 (`chr_p3_oaa` 스키마) | 테이블 잘라내기 | `TRUNCATE TABLE chr_p3_oaa.chr_trunc CONTINUE IDENTITY RESTRICT;` | 5행 → 0행 |
| | 트리거 사용 안 함 / 사용 | `ALTER TABLE chr_p3_oaa.chr_trg_t DISABLE TRIGGER chr_trg;` / `ENABLE` | `tgenabled` D → O, 사용 안 함 뒤 메뉴는 "트리거 사용"만 켜짐 |
| | 구체화된 뷰 새로 고침 | `REFRESH MATERIALIZED VIEW chr_p3_oaa.chr_mv WITH DATA;` | 개수 5 → 2(새 행 수) |
| SQL Server 2022 | 트리거 사용 안 함 / 사용 | `ALTER TABLE chr_p3_oracle_altibase_admin.dbo.chr_trg_t DISABLE TRIGGER chr_trg;` / `ENABLE` | `is_disabled` 0 |
| | 테이블 잘라내기 | `TRUNCATE TABLE chr_p3_oracle_altibase_admin.dbo.chr_trunc;` | 3행 → 0행 |
| Oracle Free 23ai (`CHR_P3_OBJ`) | 구체화된 뷰 새로 고침 | `CALL DBMS_MVIEW.REFRESH('CHR_P3_OBJ.CHR_MV','');`(DDL 커밋 안내 머리글) | `LAST_REFRESH_DATE` 바뀜 |
| | 트리거 사용 안 함 / 사용 | `ALTER TRIGGER CHR_P3_OBJ.CHR_TRG DISABLE;` / `ENABLE` | `STATUS` DISABLED → ENABLED |
| | 테이블 잘라내기 | 확인에 암시적 커밋 문구, `TRUNCATE TABLE CHR_P3_OBJ.CHR_TRUNC;` | 5행 → 0행 |
| MariaDB 11 (`chr_p3_oaa`, 한국어 화면) | 테이블 잘라내기 | 확인 "MariaDB에서는 TRUNCATE가 암시적으로 커밋되므로 롤백할 수 없습니다", `TRUNCATE TABLE chr_p3_oaa.chr_trunc;` | 3행 → 0행 |

영어(`-nl en`)·한국어, 라이트·다크(Oracle 메뉴·미리 보기·잘라내기 확인)에서 화면을 보았다. 시험 객체는 끝난 뒤 모두 지웠다.

## 변경 이력

- 2026-11-07 최초 작성. 개체 삭제·이름 바꾸기(객체 관리자, 스크립트 미리 보기, 트랜잭션 질문), 이름·정규화된 이름 복사, 여러 개 선택, SQL 생성 메뉴.
  PostgreSQL·SQL Server·MariaDB에서 실행 확인, 삭제 확인 목록의 불필요한 스크롤바 수정.
- 2026-11-08 유지 관리 명령: 테이블 잘라내기, 트리거 사용/사용 안 함, 구체화된 뷰 새로 고침(모델 도구의 SQL, 확인·미리 보기·트랜잭션 질문). PostgreSQL·SQL Server·Oracle·MariaDB에서 확인.
- 2026-11-08 Altibase 테이블 잘라내기·구체화된 뷰 새로 고침(A5), 컴파일 명령(Oracle·Altibase, O11·A6). 확인은 [swing-routines.md](swing-routines.md).
- 2026-11-08 `runChange`를 속성 편집도 씀. 문장이 없는 변경은 빈 미리 보기 대신 알리고 되돌림([swing-object-properties.md](swing-object-properties.md)).
