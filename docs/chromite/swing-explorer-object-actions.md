# Swing 개체 탐색기 개체 작업 (삭제, 이름 바꾸기, 이름 복사, 여러 개 선택, SQL 생성, 유지 관리 명령, 끌어서 이름 넣기)

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
  - Altibase 계열 테이블스페이스는 "내용도 삭제(INCLUDING CONTENTS)", "데이터 파일도 삭제(AND DATAFILES)"가 앞에 더 있다(모두 꺼짐). 파일과 CASCADE는 서버 문법상
    내용과 함께일 때만 되므로 내용을 고를 때만 켜진다(`DeleteOption.CONTENTS`·`DATA_FILES`, 기능 비트 `FEATURE_DELETE_CONTENTS`·`FEATURE_DELETE_DATA_FILES`).
  - Oracle 테이블스페이스도 같은 세 확인란이다(`DROP TABLESPACE t [INCLUDING CONTENTS [AND DATAFILES] [CASCADE CONSTRAINTS]]`, [oracle-storage.md](oracle-storage.md)).
- 이름 바꾸기: "{형식} 이름 바꾸기" 입력 창(지금 이름이 들어 있음). 연결 노드의 이름 바꾸기와 같은 모양이다.
  - PostgreSQL 사용자 테이블스페이스도 바뀐다(`ALTER TABLESPACE a RENAME TO b`). pg_default·pg_global은 꺼짐([swing-object-properties.md](swing-object-properties.md) "PostgreSQL 테이블스페이스").
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
- **취소와 권한 오류(2026-11-22, 앱 변경 #159).** 진행 창의 취소는 실행 중인 메타데이터 문장을 쿼리 창의 중지와 같은 길(작업 취소 → 문장 블록 취소)로
  멈춘다. 생성기는 취소된 모니터에서 멈추고 만든 데까지 돌려주므로, `ObjectScripts.runGenerator`는 취소된 생성의 결과를 버린다(전에는 취소 뒤에도
  열이 없는 `CREATE TABLE`이 탭에 열렸다). 서버가 권한 때문에 거절하면(데이터 원본의 오류 종류 `PERMISSION_DENIED`) DDL 보기·스크립팅·SQL 생성·
  선택하여 복사·수정의 오류 창 첫 줄이 "권한이 없어 개체 정의를 읽을 수 없습니다."가 되고 서버 오류가 뒤따른다. 메뉴는 흐리게 하지 않는다
  ([swing-routines.md](swing-routines.md) "DDL 권한과 취소").

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
| 온라인으로 전환 / 오프라인으로 전환 | Altibase 계열 메모리·디스크 데이터 사용자 테이블스페이스 | `ALTER TABLESPACE t ONLINE / OFFLINE`(지금 상태의 항목은 꺼짐, [altibase.md](altibase.md) "테이블스페이스 관리") |
| 자동 확장 설정… / 데이터 파일 설정… | Altibase 계열 메모리·휘발성 테이블스페이스 / 디스크 데이터·임시 파일(한 개 선택) | 설정 대화상자(지금 값) 뒤 `ALTER TABLESPACE … ALTER AUTOEXTEND …` / `… ALTER DATAFILE 'f' SIZE …`·`AUTOEXTEND …`. 바뀐 것이 없으면 "문장 없음" 알림 |
| 온라인으로 전환 / 오프라인으로 전환, 읽기 전용으로 전환 / 읽기 쓰기로 전환, 데이터 파일 설정… | Oracle 영구·언두 테이블스페이스(읽기 전용은 영구만), 데이터·임시 파일(한 개 선택). SYSTEM·SYSAUX·기본·활성 언두 제외 | `ALTER TABLESPACE t ONLINE / OFFLINE / READ ONLY / READ WRITE`(지금 상태의 항목은 꺼짐), `ALTER DATABASE DATAFILE / TEMPFILE 'f' RESIZE …`·`AUTOEXTEND …`([oracle-storage.md](oracle-storage.md)) |
| 기본 파일 그룹으로 설정 / 읽기 전용으로 전환 / 읽기 쓰기로 전환 | SQL Server 행 데이터 파일 그룹(읽기 전용은 PRIMARY 제외) | `ALTER DATABASE d MODIFY FILEGROUP fg DEFAULT / READ_ONLY / READ_WRITE`(지금 상태의 항목과 파일이 없는 파일 그룹의 항목은 꺼짐, [mssql-storage.md](mssql-storage.md)) |
| 파일 설정… | SQL Server 데이터·로그 파일(읽기 전용 제외, 한 개 선택) | 설정 대화상자(지금 값) 뒤 `ALTER DATABASE d MODIFY FILE (NAME = N'f', SIZE …, FILEGROWTH …, MAXSIZE …)`(바뀐 것만). 바뀐 것이 없으면 "문장 없음" 알림 |
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

## 끌어서 이름 넣기 (2026-11-13, 앱 변경 #112)

개체 탐색기에서 DB 개체를 끌어 편집할 수 있는 쿼리 창에 놓으면 놓은 자리에 이름이 들어간다(SSMS와 같은 기능). 새 명령·메뉴·설정·문구는 없다.

| 끈 노드 | 들어가는 글 |
|---|---|
| 테이블·뷰·프로시저·스키마·데이터베이스 등 DB 개체 | 정규화된 이름 복사와 같은 이름(`DBUtils.getObjectFullName(…, DML)`, 방언의 따옴표). 단 DB.스키마.개체 이름은 놓는 쿼리 창이 같은 연결이고 현재 DB가 개체의 DB이면 스키마.개체(아래 "놓는 창에 맞춘 이름") |
| 열 | 따옴표가 필요할 때만 붙은 열 이름(열은 정규화된 개체가 아니어서 같은 함수가 열 이름만 준다) |
| 여러 개 | 트리 순서로 ", "로 이은 한 줄(SQL의 목록에 바로 들어가도록. 복사 명령은 그대로 줄마다 하나) |
| 테이블·뷰의 Columns 폴더 | 그 열 이름들을 ", "로 이은 한 줄. 열을 이미 읽은 폴더(펼친 적이 있는 폴더)만 |
| 연결, Columns 말고 다른 폴더, 아직 읽지 않은 Columns 폴더, 그런 노드가 섞인 선택 | 끌리지 않는다(아무것도 들어가지 않음) |

- **구조.** 트리는 원래 있던 전송 처리기(`ExplorerObjectCommands.createTransferHandler`)를 그대로 쓰고 `setDragEnabled(true)`만 켰다.
  처리기는 `exportAsDrag` 안에서 만들어지는 전송 객체만 끌기용 이름(`getDragNames`)으로 만든다. Ctrl+C·Edit ▸ 복사(`exportToClipboard`)는
  지금처럼 이름 복사 글을 준다. 끌기 전송 객체(`DragNames`)는 보통 글(`StringSelection`)에 JVM 안에서만 쓰는 표시 형식(`DRAG_FLAVOR`)을 더한 것이다.
  처리기는 복사(`COPY`)만 내보내고 받지 않으므로 트리 자신에 놓아도 아무것도 바뀌지 않는다.
- **편집기.** SQL 편집 영역(`SqlEditorPanel.SqlTextArea`)은 RSyntaxTextArea의 글 전송 처리기(`RTATextTransferHandler`)를 상속한
  `ExplorerDropHandler`를 쓴다. 표시 형식이 있는 놓기만 `beginAtomicEdit`/`endAtomicEdit`로 감싸 실행 취소 한 단계로 만들고(앞뒤 입력과
  합쳐지지 않게, 붙여넣기와 같음) 편집기에 초점을 준다. 나머지 전송(편집기 안 글 끌기, 다른 프로그램의 글, 파일, 복사·붙여넣기)은 상속한 처리 그대로다.
  RSyntaxTextArea의 UI가 생성자와 `updateUI`에서 자기 처리기를 다시 넣으므로 두 곳에서 처리기를 다시 놓는다. 처리기는 RSyntaxTextArea처럼
  모든 SQL 편집 영역이 한 인스턴스를 같이 쓴다(편집기 안 끌기가 원본을 처리기에 기억하므로).
- **놓는 창에 맞춘 이름(2026-11-13 검토 후 결정).** SSMS처럼, 창이 이미 그 DB에 있으면 DB 이름은 지울 군더더기다. 규칙(엔진 중립):
  DB(카탈로그) 부분은 (1) 놓는 쿼리 창이 개체와 같은 연결이고 창의 현재 DB가 개체의 DB이며 (2) DB를 빼도 스키마 부분이 남을 때(DB > 스키마 > 개체:
  SQL Server, 카탈로그와 스키마가 있는 Generic 엔진)만 뺀다. 그 밖은 그대로: 다른 DB·다른 연결·연결 없는 창은 전체 이름, MySQL/MariaDB는 `db.table`
  (DB가 유일한 이름 공간), PostgreSQL·Oracle은 원래 `schema.table`, 열·Columns 폴더·여러 개는 같은 방식. 이름 복사와 트리 Ctrl+C는 놓을 창이 없으므로 바뀌지 않는다.
  - 구현: 놓을 창은 끌기를 시작할 때 모르므로, 끌기 데이터(`DragNames`)에 개체마다 전체 이름, DB를 뺀 이름, 그 DB 이름과 연결(`DragName`)을 싣는다.
    DB를 뺀 이름은 전체 이름이 `DBUtils.getFullQualifiedName(데이터 소스, DB, 스키마, 개체)`와 같을 때만 `getFullQualifiedName(데이터 소스, 스키마, 개체)`로
    만든다(같은 따옴표 함수, 글을 자르지 않음). 그래서 다른 꼴의 이름(MySQL `db.table`, SQL Server 프로시저 `dbo.p`, 스키마 자신, 열)은 전체 이름 그대로다.
    개체를 싣는 대신 이름을 실은 것은, 놓을 때 개체를 다시 읽거나 이름 함수를 편집기 쪽에 두지 않기 위해서다(변경이 더 작음).
  - 놓는 쪽: 쿼리 창 자신의 편집 영역이면 창의 연결(`getDataSourceContainer`)과 현재 DB(`getDefaultObjectNames`: DB 선택·상태 줄이 보이는 기본 개체,
    메모리에서 읽고 DB에 묻지 않음)로 이름을 고른다. `USE`로 세션 DB가 바뀌면 창이 따라 읽은 값(#220)을 그대로 쓴다. 대화상자 등 쿼리 창이 아닌
    SQL 편집 영역은 전체 이름을 받는다.
- **놓는 자리.** 끄는 동안 캐럿이 포인터를 따라가고(Swing 글 놓기의 기본 `DropMode.USE_SELECTION`), 놓으면 그 자리에 들어가며 캐럿은 넣은 글 뒤에 선다.
  선택된 글이 있으면 끌기가 들어오는 순간 선택이 풀리고 놓은 자리에 끼워 넣는다(선택을 바꾸지 않는다; Swing 글 구성 요소의 기본 동작).
  창의 변경 표시·상태 줄은 입력과 같은 문서 이벤트로 바뀐다.
- **받지 않는 곳.** 읽기 전용 편집 영역(DDL 보기, 비교 탭 등)은 상속한 처리의 `isEditable` 검사로 받지 않는다. 결과 표, Query Library 트리,
  개체 탐색기 트리는 글을 받지 않는다. 그 밖의 편집 가능한 Swing 글 칸(찾기 칸 등)은 원래처럼 글 놓기를 받는다.
- **Columns 폴더를 읽지 않은 경우.** 끌기는 이벤트 스레드에서 시작하고 전송 객체를 그때 만들어야 한다. 열을 읽으면 DB에 물을 수 있으므로
  메모리에 있는 자식만 쓴다(`needsInitialization`, `LocalCacheProgressMonitor`, `DataTransferObjects`와 같은 방법). 배경에서 읽고 나중에 넣는
  방법은 편집기 쪽에 지연 삽입(위치 추적, 창 닫힘, 실행 취소)이 필요해 이번 변경에 넣지 않았다.

### 확인 (2026-11-13)

빌드한 Swing 제품을 한 벌 복사해 빈 작업 공간·설정·데이터 폴더(`D:\Tools\_scratch\explorer-drag-name\`)로 띄우고, 프로세스에 붙인 시험 에이전트가
`java.awt.Robot`으로 실제 마우스 끌기(누르기, 조금씩 움직이기, 놓기)를 앱 창이 앞에 있을 때 그 창 안에서만 했다. 연결은 저장하지 않는 임시 연결,
시험 개체는 `chr_drag_name`(끝난 뒤 모두 지움). 스크린숏은 앱 창만 그린 것이다(`shots\`).

| 끈 것 | SQL Server 2022 (창 DB master) | PostgreSQL 17 | MariaDB 11 | Oracle 23ai |
|---|---|---|---|---|
| 테이블 | `chr_drag_name.dbo.chr_t1` | `chr_drag_name.chr_t1` | `chr_drag_name.chr_t1` | `CHR_DRAG_NAME.CHR_T1` |
| 따옴표가 필요한 테이블 | `chr_drag_name.dbo.[chr t2]`, `….[order]` | `chr_drag_name."chr t2"`, `."order"`, `."ChrMixed"` | `` chr_drag_name.`chr t2` ``, `` .`order` `` | `CHR_DRAG_NAME."chr t2"`, `."ORDER"`, `."ChrMixed"` |
| 뷰 | `chr_drag_name.dbo.chr_v` | `chr_drag_name.chr_v` | `chr_drag_name.chr_v` | `CHR_DRAG_NAME.CHR_V` |
| 프로시저 | `dbo.chr_p`(DB 없음, 모델의 정규화된 이름 그대로) | `chr_drag_name.chr_p` | `chr_drag_name.chr_p` | `CHR_DRAG_NAME.CHR_P` |
| 열 | `[my col]`, `[select]`, `MixedCase` | `"my col"`, `"select"`, `"MixedCol"` | `` `my col` ``, `` `select` ``, `MixedCol` | `"my col"`, `"SELECT"`, `"MixedCol"` |
| Columns 폴더(읽음) | `id, [my col], [select], MixedCase` | `id, "my col", "select", "MixedCol"` | ``id, `my col`, `select`, MixedCol`` | `ID, "my col", "SELECT", "MixedCol"` |
| 테이블 3개 | `chr_drag_name.dbo.[chr t2], chr_drag_name.dbo.chr_t1, chr_drag_name.dbo.[order]` | 같은 꼴 | 같은 꼴 | 같은 꼴 |
| 스키마 / 데이터베이스 | `chr_drag_name.chr_s` / `chr_drag_name` | `chr_drag_name` / `postgres` | `chr_drag_name` | `CHR_DRAG_NAME` |
| 다른 DB·스키마의 테이블 | `chr_drag_name_b.dbo.chr_tb` | `chr_drag_name_b.chr_tb` | `chr_drag_name_b.chr_tb` | `CHR_DRAG_NAME_B.CHR_TB` |

- 위 표는 첫 확인(창 DB가 개체의 DB가 아닌 경우)이다. 놓는 창에 맞춘 이름(검토 후 결정)은 다시 빌드해 같은 방법으로 확인했다:

| SQL Server에서 끈 것 | 창이 `chr_drag_name`(같은 연결) | 창이 `master` | `master` 창에서 `USE chr_drag_name` 뒤 | 다른 연결의 창(DB `chr_drag_name`) | 연결 없는 창 |
|---|---|---|---|---|---|
| 테이블 | `dbo.chr_t1` | `chr_drag_name.dbo.chr_t1` | `dbo.chr_t1` | `chr_drag_name.dbo.chr_t1` | `chr_drag_name.dbo.chr_t1` |
| 따옴표가 필요한 테이블 | `dbo.[chr t2]` | | | | |
| 뷰 | `dbo.chr_v` | | | | |
| 테이블 3개 | `dbo.[chr t2], dbo.chr_t1, dbo.[order]` | | | | |
| 다른 스키마의 테이블 | `chr_s.chr_t4` | | | | |
| 프로시저 | `dbo.chr_p`(원래 DB 없음) | | | | |
| Columns 폴더 | `id, [my col], [select], MixedCase` | | | | |
| 스키마 / 데이터베이스 | `chr_drag_name.chr_s` / `chr_drag_name`(스키마 부분이 남지 않으므로 그대로) | | | | |
| 다른 DB의 테이블 | `chr_drag_name_b.dbo.chr_tb` | | | | |

  다른 엔진은 그대로: PostgreSQL 창(postgres/public) `chr_drag_name.chr_t1`, MariaDB 창(`USE chr_drag_name` 뒤) `chr_drag_name.chr_t1`,
  Oracle 창(SYSTEM) `CHR_DRAG_NAME.CHR_T1`. 실행 취소는 여전히 한 단계(`dbo.chr_t1` → 빈 창 → 다시 실행). 이름 복사·트리 Ctrl+C는 전과 같다.
- 연결 노드, Tables 폴더, 읽지 않은 Columns 폴더, 테이블과 Views 폴더를 함께 고른 선택: 끌기가 시작되지 않고 글이 들어가지 않음.
- 놓는 자리(SQL Server): 빈 창, 줄 가운데(`SELECT * FROM | WHERE 1 = 1`), 글 끝, 200줄 글을 끝까지 내린 창의 보이는 줄, 실행 중인 창(`WAITFOR` 중에 놓으면
  들어가고 실행은 그대로 끝남), 선택(`xxx`) 가운데(선택이 풀리고 그 자리에 끼워 넣음). 끄는 동안 캐럿이 포인터 위치로 움직이고, 놓으면 편집기가 초점을 받고
  캐럿은 넣은 글 뒤, 창 탭에 변경 표시(`*`), 상태 줄 줄·칸이 바뀜. 실행 취소 한 번에 넣은 글만 빠지고 다시 실행으로 돌아옴. 놓은 뒤 친 글자는 따로 취소됨.
  완성 목록이 열린 채 끌면 목록은 닫히고 다시 열리지 않음.
- 받지 않음: DDL 보기 탭, 비교 탭, 결과 표, Query Library 트리, 개체 탐색기 트리(선택·펼침·노드 그대로), 다른 문서 탭의 머리글(탭이 바뀌지 않고 아무것도 안 들어감).
- 그대로인지(같은 프로세스에서 이 변경 전 설정 = 트리 끌기 끔·RSyntaxTextArea 처리기로 바꿔 비교): 트리 Ctrl+C(별도 Clipboard 객체로)는 전·후 모두
  `chr t2⏎chr_t1`, Columns 폴더는 `Columns`; 편집기 안에서 고른 글 끌기(옮기기, 선택 안에 놓기는 아무 일 없음)와 실행 취소가 전·후 같음.
- 연결을 새로 고친 뒤에도 같은 결과. 회귀 테스트는 실행하지 않았다(요청 없음).

## 행을 쓰는 생성기 (2026-11-22, 앱 변경 #157)

- **문제:** "SQL 생성" ▸ INSERT·UPDATE·DELETE·MERGE는 객체 형식(`DBSDataManipulator`)만 보고 나왔다(`com.seizia.chromite.model.sql/plugin.xml`의
  `objectType`, `SQLGeneratorDescriptor.appliesTo`). 그래서 서버가 행 넣기·바꾸기를 거절하는 Altibase 계열 큐에도 INSERT·UPDATE·MERGE가 나왔다
  (201248, 200753 / 201854).
- **고침:** 네 생성기의 `objectType`에 확장점이 이미 가진 조건(`if`, JEXL)을 붙여 모델의 데이터 기능을 묻는다. INSERT `data.insert`, UPDATE `data.update`,
  DELETE `data.delete`, MERGE는 `data.insert`와 `data.update` 둘 다(`DBSDataManipulator.FEATURE_DATA_*`). 메뉴(`ObjectScripts.getGenerators`)와 실행
  (`ObjectScripts.generate`)이 같은 `appliesTo`를 쓰므로 두 곳이 함께 바뀐다. 코드에는 제품 이름이 없다: 큐가 데이터 기능에서 넣기·바꾸기를 뺀다
  ([altibase.md](altibase.md) "큐에 맞지 않는 데이터 명령").
- **다른 객체:** 데이터 기능을 줄이는 모델은 지금 큐뿐이다(`JDBCTable.getSupportedFeatures`를 다시 정의한 곳은 `AltibaseQueue` 하나). 그래서 다른 모든
  테이블·뷰·구체화된 뷰의 메뉴는 전과 같다(아래 확인).
- **남긴 것:** PostgreSQL INSERT ON CONFLICT·UPDATE FROM·DELETE USING, Firebird UPDATE OR INSERT는 그 DB의 테이블 형식만 본다. 데이터 기능을 줄이는 그 DB의
  모델이 없어 지금은 결과가 같으므로 그 번들은 고치지 않았다(같은 `if`를 붙이면 된다).
- **확인:** 이 브랜치 빌드, 객체마다 `ObjectScripts.getGenerators`의 결과. 큐(Altibase 7.3, ShardSphere 5.1.9): 전 SELECT INSERT UPDATE DELETE MERGE DDL →
  후 SELECT DELETE DDL. 테이블(Altibase·ShardSphere·SQL Server·PostgreSQL·MariaDB·Oracle), 뷰(갱신 가능·집계), 구체화된 뷰(Altibase·PostgreSQL·Oracle)는
  전과 같음(PostgreSQL은 INSERT ON CONFLICT·UPDATE FROM·DELETE USING까지). 연결 새로 고침·다시 연결 뒤에도 같음.

## 행 수 제한 (2026-11-24, 앱 변경 #183)

### 문제와 원인

사용자 보고: "상위1000개 선택을 누르면 테이블 전체 쿼리가 나오는 현상". 탐색기 ▸ "상위 1000개 행 선택"(`ObjectExplorerPanel` `selectTopAction`)은
`ObjectScripts.generateSelect(…, 1000)`으로 SELECT를 만들어 쿼리 창에 열고 실행한다. 행 수 제한은 방언의 문법을 직접 쓰지 않고 연결의
`DBCQueryTransformProvider`에 `RESULT_SET_LIMIT` 변환기를 물어 붙였다(`ObjectScripts.applyRowLimit`). 변환기가 없으면 SELECT를 그대로 두었다.

- 번들 드라이버 가운데 **Oracle과 ShardSphere**(그리고 같은 메타 모델의 Altibase)는 이 변환기가 없다. Oracle은 upstream이 `QueryTransformerRowNum`을
  주석으로 막아 두었고(`OracleDataSource.createQueryTransformer`), Altibase 계열 메타 모델(`AltibaseMetaModel`)은 변환기를 주지 않는다.
  `JDBCDataSource.createQueryTransformer`의 기본값은 null이다.
- 그래서 이 연결들에서 열린 스크립트는 `SELECT … FROM t;`였다. 쿼리 창은 최대 행 수 1000(JDBC `setMaxRows`)으로 가져오기를 멈추므로 결과는 1000행이지만
  글은 "테이블 전체"였고, 그 글을 다른 곳에서 실행하면 전체를 읽는다.
- 같은 원인의 다른 길: ERD 다이어그램의 "상위 1000개 행 선택"(`ErdDiagramPanel.selectTop`), 데이터베이스 복사 페이지의 같은 명령
  (`DatabaseCopyPages`), 데이터 찾기의 "행 열기"(`generateFilteredSelect`, WHERE 뒤 제한). 모두 `applyRowLimit`를 지난다.
  "SQL 생성 ▸ SELECT"와 "스크립팅 ▸ SELECT 대상"은 원래 제한이 없다(행 수 0). Agent 도구는 이 생성기를 부르지 않는다.

### 자리: 스크립트만의 변환 종류 `SCRIPT_ROW_LIMIT`

`RESULT_SET_LIMIT`은 실행 엔진도 쓴다(`DBUtils.makeStatement`): JSQLParser가 읽은 단순 SELECT에, 연결 설정 "SQL로 가져오기 크기 제한"
(`resultset.maxrows.sql`, 기본 꺼짐)이 켜졌을 때 편집기의 최대 행 수를 SQL로 넣는다(SQL Server는 `isForceTransform`으로 일부 표에서 늘). 이 종류를 새로 답하면
그 설정을 켠 사용자의 쿼리가 이 DB들에서 바뀐다(설정은 기본값이 모든 연결에 같이 걸린다). Oracle은 upstream이 일부러 꺼 두었다.

- 고른 길(b): `DBCQueryTransformType.SCRIPT_ROW_LIMIT`을 더했다. 엔진은 이 종류를 묻지 않는다(`DBUtils`는 `RESULT_SET_LIMIT`·`FETCH_ALL_TABLE`만).
  `applyRowLimit`는 먼저 `SCRIPT_ROW_LIMIT`을, 없으면 `RESULT_SET_LIMIT`을 묻는다. 변환기를 이미 가진 데이터 원본은 `SCRIPT_ROW_LIMIT`에 답하지 않으므로
  전과 같다.
- 고르지 않은 길(a): Altibase 계열에 `RESULT_SET_LIMIT`을 주기(SQLite·Athena처럼 `QueryTransformerLimit(false)`). 설정이 꺼져 있으면 쿼리는 같지만 켠
  사용자의 쿼리에 `LIMIT`이 붙고, Oracle에는 같은 방법을 쓸 수 없어 두 DB가 다른 자리를 쓰게 된다.
- 답하는 곳: `AltibaseMetaModel`(Altibase와 ShardSphere 드라이버가 함께 씀) `QueryTransformerLimit(false)` → `LIMIT n`.
  `OracleDataSource` 12c 이상 `QueryTransformerFetchFirst`(새, `ext.oracle` `data`) → `FETCH FIRST n ROWS ONLY`, 11g 이하는 null(전과 같음).
  `YashanDBDataSource`(`OracleDataSource`를 물려받음)는 null을 돌려 전과 같다(FETCH FIRST를 YashanDB 서버에서 확인하지 못함).
- 문법은 서버에서 확인한 사실로 정했다(아래 "서버에서 확인한 문법"). 제한은 문장 끝(ORDER BY·WHERE 뒤)에 붙고 끝의 `;`는 변환기가 떼었다가
  `applyRowLimit`가 다시 붙인다.

### 서버에서 확인한 문법 (앱 밖 JDBC, 시험용 개체 `CHR_TOP_*`, 1,100행)

| 문장 | ShardSphere 5.1.9 일반 서버 | Altibase 7.3 | Oracle 26ai |
|---|---|---|---|
| `… LIMIT 1000` | 1000행 | 1000행 | 3047 |
| `… ORDER BY ID LIMIT 1000` / `… WHERE … LIMIT 1000` | 1000행 / 1000행 | – | – |
| `… LIMIT 1000 ORDER BY ID` | 200705 | – | – |
| `… LIMIT 0, 1000` / `LIMIT 1, 1000` | 200713 / 1000행(첫 행부터) | 200713 / – | – |
| `… LIMIT 1000 OFFSET 5` | 200705 | – | – |
| `… FETCH FIRST 1000 ROWS ONLY` | 200705 | 200705 | 1000행(WHERE·ORDER BY 뒤, 끝의 `;`도 됨) |
| `SELECT TOP 1000 …` | 200705 | – | – |
| `… WHERE ROWNUM <= 1000` | 1000행 | – | – |
| `… LIMIT 1000 FOR UPDATE` / `… FOR UPDATE LIMIT 1000` | 1000행 / 200705 | – | – |
| `…;⏎LIMIT 1000` | 200705 | – | – |

ShardSphere의 종류마다 `SELECT <열>⏎FROM 소유자.이름⏎LIMIT 1000`: 테이블·뷰·구체화된 뷰·시노님(소유자 붙임·없음)·분할 테이블 1000행, 분할 하나
(`PARTITION (p)`) 500행, 큐 1000행, 스트림 테이블 1000행, 연속 테이블(집계 있음·없음)·연속 스트림 테이블 오류 없음(DISABLE이라 0행).

### 변환기가 없는 데이터 원본 (2026-11-24, `RESULT_SET_LIMIT`도 `SCRIPT_ROW_LIMIT`도 없음)

"번들"은 제품이 드라이버 jar를 함께 싣는 것(`drivers.public/plugin.xml`, [drivers.md](drivers.md)), 아니면 사용자가 내려받거나 넣는 드라이버다.
이 표의 데이터 원본은 전과 같이 SELECT에 제한을 쓰지 않고 쿼리 창의 최대 행 수로만 자른다. 문법이 있어도 서버에서 확인하지 못한 것은 넣지 않았다
(서버가 거절하는 문장은 제한이 없는 문장보다 나쁘다).

| 데이터 원본 (클래스) | 행 수 문법(일반 지식, 서버에서 확인하지 않음) | 번들 |
|---|---|---|
| YashanDB (`YashanDBDataSource`, Oracle을 물려받되 null) | 있음(확인 못 함) | 아니오 |
| Db2 LUW(`DB2DataSource`), Db2 for i(`DB2IMetaModel`), Db2 z/OS(`DB2ZOSMetaModel`) | `FETCH FIRST n ROWS ONLY` | 아니오 |
| OceanBase Oracle 모드(`OceanbaseOracleDataSource`, 일반 메타 모델) | `ROWNUM`/`FETCH FIRST` | 아니오 |
| Apache Doris(`DorisMetaModel`), StarRocks(`StarRocksMetaModel`), Manticore(`ManticoreMetaModel`), GreptimeDB(`GreptimeMetaModel`) | `LIMIT` | 아니오 |
| Dameng(`DamengMetaModel`), GBase 8s(`GBase8sMetaModel`), Informix(`InformixMetaModel`) | `TOP`/`LIMIT`/`FIRST` | 아니오 |
| Denodo(`DenodoMetaModel`), Firebird(`FireBirdMetaModel`), H2·H2GIS(`H2DataSource`), HANA(`HANAMetaModel`), HSQLDB(`HSQLMetaModel`), Derby(`DerbyMetaModel`), Mimer(`MimerMetaModel`), Netezza(`NetezzaMetaModel`), Ocient(`OcientMetaModel`), Spanner(`SpannerMetaModel`), IoTDB·TimechoDB(`IoTDBMetaModel`) | 대부분 `LIMIT`·`FETCH FIRST`·`FIRST`(DB마다 다름) | 아니오 |
| 일반 JDBC(`GenericMetaModel`): OpenSearch, Elasticsearch, Solr, Drill, Dremio, Impala, Kyuubi, Presto, Kylin, Calcite Avatica, Arrow Flight SQL, Teiid, MaxDB, Caché·IRIS, Ingres, NuoDB, MonetDB, Virtuoso, OpenEdge, Pervasive, SQream, Db2(구), MySQL(구), CSV, DBF, MS Access, JDBCX, Gemfire XD, SnappyData, Ignite, OmniSci, CrateDB(구), OrientDB, Machbase, TDengine, DolphinDB, Jennifer, Salesforce Data 360, GizmoSQL, 사용자가 더한 드라이버 | 드라이버마다 다르거나 알 수 없음 | 아니오 |

번들 드라이버(SQL Server, Oracle, PostgreSQL, MariaDB, SQLite, ShardSphere) 가운데 변환기가 없던 것은 Oracle과 ShardSphere였고, 이 변경으로 둘 다 답한다.
Altibase의 옛 드라이버들(`Altibase7_1`, 이름 없는 `Altibase`)도 같은 메타 모델이라 `LIMIT`을 쓴다. 서버에서는 7.3만 확인했다.
`RESULT_SET_LIMIT`을 가진 데이터 원본(전과 같음): PostgreSQL 계열(Greenplum·Kingbase·GaussDB 등), MySQL·MariaDB·OceanBase MySQL·TiDB, SQL Server·jTDS·Sybase(TOP),
SQLite·LibSQL·Cloudflare D1, DuckDB, ClickHouse, Exasol, Athena, BigQuery, CUBRID, Databend, Databricks, Snowflake, Vertica, TiDBLake, Timeplus, Hive·Spark, Trino,
Teradata(TOP), Phoenix.

### 확인 (2026-11-24, 전 = `origin/command` 96c492fe63 빌드, 후 = 이 브랜치 빌드, 사설 작업 공간·설정·데이터 폴더, 앱 안 테스트 에이전트, 한국어·밝은 테마·150 %)

연결 여덟 개(ShardSphere 일반 서버 `SS`, 메타 노드 `SHARD` 읽기 전용, 터널의 Altibase 7.3·Oracle 1521·PostgreSQL·SQL Server·MariaDB·MySQL 8.4)에
시험용 개체 `CHR_TOP_*`(1,100행)를 두고 탐색기의 명령 자체(`selectTopAction`)를 불러 열린 스크립트, 결과, 쿼리 관리자의 문장을 보았다.
ERD·데이터베이스 복사가 쓰는 `generateSelect`와 데이터 찾기의 `generateFilteredSelect`(WHERE `ID >= 50`)는 같은 앱에서 직접 불러 실행했다
(스트림 목록 객체는 트리 노드가 아니라 이 길로만 닿는다).

| DB | 종류 | 전 | 후 | 오류 | 행 |
|---|---|---|---|---|---|
| ShardSphere | 테이블·뷰·구체화된 뷰·분할 테이블·큐 | `SELECT …⏎FROM 소유자.t;` | `…⏎LIMIT 1000;` | 없음 | 1000 |
| ShardSphere | 스트림 테이블(ERD·복사 길) | 제한 없음 | `SELECT "$INSERT_TIME", …⏎LIMIT 1000;` | 없음 | 1000 |
| ShardSphere | 연속 테이블 둘·연속 스트림 테이블(같은 길) | 제한 없음 | `…⏎LIMIT 1000;` | 없음 | 0(DISABLE) |
| ShardSphere | 데이터 찾기: 테이블, 스트림 테이블 | `…⏎WHERE ID >= 50;` | `…⏎WHERE ID >= 50⏎LIMIT 1000;` | 없음 | 1000 |
| ShardSphere | 시노님, "스트림" 노드의 객체 | 명령 꺼짐 | 같음 | – | – |
| ShardSphere 메타 노드 | 로그인 스키마의 첫 테이블(SELECT만) | 제한 없음 | `…⏎LIMIT 1000;` | 없음 | 100(전부) |
| Altibase 7.3 | 테이블·뷰·구체화된 뷰 | 제한 없음 | `…⏎LIMIT 1000;` | 없음 | 1000 |
| Oracle 26ai | 테이블·분할 테이블·뷰·구체화된 뷰, 데이터 찾기 | 제한 없음 | `…⏎FETCH FIRST 1000 ROWS ONLY;` | 없음 | 1000 |
| Oracle·SQL Server·Altibase | 시노님·동의어 | 명령 꺼짐 | 같음 | – | – |
| PostgreSQL 17 | 테이블·분할 테이블·뷰·구체화된 뷰 | `…⏎LIMIT 1000;` | 같음 | 없음 | 1000 |
| SQL Server 2022 | 테이블·분할 테이블·뷰 | `SELECT TOP 1000 …;` | 같음 | 없음 | 1000 |
| MariaDB 11.8·MySQL 8.4 | 테이블·분할 테이블·뷰 | `…⏎LIMIT 0, 1000;` | 같음 | 없음 | 1000 |

- 쿼리 창은 최대 행 수 1000에서 가져오기를 멈추므로 "행" 열은 전에도 1000이었다. 서버가 제한을 지키는지는 같은 글을 행 수 제한 없이 JDBC로 실행해 확인했다
  (위 문법 표의 종류별 줄: 1,100행 개체에서 1000행).
- **사용자 쿼리는 그대로**(쿼리 관리자, 전·후 같은 문장): `SS`·`ORA`·`ALTI`에서 보통 SELECT, 자기 제한이 있는 SELECT(`LIMIT 5`, `FETCH FIRST 5 ROWS ONLY`),
  `SELECT … FOR UPDATE`, `UPDATE`(같은 값), 스트림 테이블 SELECT를 "SQL로 가져오기 크기 제한" 꺼짐·켜짐 둘 다로 실행: 서버로 간 사용자 문장 글이 전과 후에 같고
  (`SELECT ID, V FROM CHR_TOP_T` 등, 제한 절이 붙지 않음) 결과 행도 같다(최대 행 수 200에서 멈춤, 5행, 1행).
- 캡처(저장소 밖, 계정은 `<owner>`, 호스트·다른 객체 이름은 가림): `D:\Tools\_scratch\select-top-row-limit\shots\before|after\`.
- 회귀 테스트는 작성·실행하지 않았다(요청 없음). 영향받을 수 있는 기능: Oracle·Altibase·ShardSphere의 "상위 1000개 행 선택"(탐색기·ERD·데이터베이스 복사),
  데이터 찾기의 "행 열기". 넣을 만한 시험: `ObjectScripts.generateSelect`·`generateFilteredSelect`가 데이터 원본별로 만드는 글(모델 시험 플러그인, 가짜 데이터 원본),
  `QueryTransformerFetchFirst`의 SELECT가 아닌 문장·이미 FETCH가 있는 문장.

### upstream 대비 변경점

- `DBCQueryTransformType.SCRIPT_ROW_LIMIT`(새 값), Altibase 계열 메타 모델과 Oracle 데이터 원본이 답함, `QueryTransformerFetchFirst`(새).
  upstream의 `RESULT_SET_LIMIT` 답과 엔진 동작은 그대로다.

## DDL 글의 객체 이름 줄 (2026-11-25)

### 보고와 재현

DDL 보기·스크립팅 ▸ CREATE 대상·선택하여 복사(DDL)·SQL 생성 ▸ DDL의 뷰 글은 맨 앞에 객체 이름 줄(`-- dbo.chr_v 소스`)이 있다
(`DBStructUtils.generateTableListDDL`이 `getObjectNameComment`로 붙임, "추가 DDL 정보" 옵션, 기본 켬). 네 명령이 모두 같은 생성기(`SQLGeneratorDDL`)다.
쿼리 창은 문장 앞 주석을 문장과 함께 보내므로, `CREATE` 앞 주석까지 정의로 두는 서버에서는 그 글을 붙여 실행할 때마다 정의에 이름 줄이 한 벌씩 들어가고,
다시 연 DDL 보기에 그만큼 더 보였다. `origin/command` 4c7a63fa21 빌드(사설 작업 공간·설정·데이터 폴더, 앱 안 시험 에이전트, 한국어 150 %)에서
뷰마다 DDL 보기 → 글 전체 복사 → 새 쿼리 창 → 붙임 → 표지 값 고침 → F5 → 연결 새로 고침을 세 번 했다(시험 개체 `chr_ddl_name_comment_once`).

| DB, 뷰 | DDL 보기의 이름 줄 수(처음 → 1·2·3회 실행 뒤) |
|---|---|
| SQL Server 2022 | 1 → 2 → 3 → 4 (세 번째 실행은 아래 `USE` 배치를 앞에 둔 글, 같은 결과) |
| SQL Server, 정의에 이미 `\n` 이름 줄 세 벌 | 4 → 5 → 6 → 7 |
| SQL Server, 정의에 이미 `\r\n` 이름 줄 두 벌 | 2(줄 끝이 맞아 앱 줄은 빠지지만 정의의 두 벌이 보임) |
| Altibase 7.3(`GET_DDL` 글) | 1 → 2 → 3 → 4 |
| PostgreSQL 17, Oracle 26ai(1521), MariaDB 11 | 1 → 1 → 1 → 1 (서버가 `CREATE` 앞 주석을 두지 않음) |

SQL Server 프로시저·함수·트리거의 DDL 글에는 이름 줄이 없다(`SQLGeneratorDDL.generateSQL`은 정의만 씀). 테이블의 이름 줄(`… 정의`) 앞 글은 모델이 만든 DDL이라 쌓이지 않는다.

### 근본 원인

`generateTableListDDL`은 "정의가 이미 이름 줄로 시작하면 붙이지 않음"을 `startsWith`로 비교했다. 이름 줄은 플랫폼 줄 끝(Windows `\r\n`)으로 끝나는데,
쿼리 창이 보내 서버가 둔 정의는 `\n`이라 비교가 맞지 않았다. 줄 끝이 맞아도 정의 안의 여러 벌은 그대로 보였다(위 `\r\n` 두 벌).

### 고친 것

`DBStructUtils.generateTableListDDL`이 뷰마다 이름 줄을 한 번 쓰고, 정의 맨 앞의 이름 줄 사본을 모두 뺀다(`withoutObjectNameComment`). 줄은 앞뒤 공백·`\r`을
떼고 비교하고, 그 사이의 빈 줄은 건너뛴다. 맨 앞이 아닌 곳의 같은 글과 사용자 주석(`-- my own note`)은 그대로 둔다. 정의에 이름 줄이 없으면 글은 전과 같다.

- **보내는 쪽에서 빼지 않은 이유:** 쿼리 창은 모든 방언에서 문장 앞 주석을 함께 보낸다(사용자 주석도 정의에 남아야 함). 앱이 쓰는 줄은 앱이 보일 때 알아본다.
- **PR 379(`SourceScripts.inObjectDatabase`):** SQL Server 글이 `USE <db>` / `GO` 배치로 시작해도 이름 줄은 다음 배치의 맨 앞이라 정의 맨 앞에 저장된다.
  위 재현의 세 번째 실행이 그 모양이다.
- **Altibase 계열의 `AltibaseMetaModel.withoutGeneratedHeader`는 그대로 둔다:** 카탈로그 원본 길(ShardSphere)에서 Modify·속성의 글(DDL 보기 생성기를 지나지 않음)도
  거르고, 전 빌드의 경고 줄·패키지 머리 줄이 이름 줄 뒤에 있는 원본에서 이름 줄을 지나야 그 줄들에 닿는다([altibase.md](altibase.md) "뷰 DDL의 경고 줄").
  DDL 보기 길에서는 둘 다 같은 줄을 빼지만 결과는 같다(이미 빠진 글에서 공용 코드는 아무것도 빼지 않음).
- **남은 것:** SQL Server·Altibase 7.3 뷰의 Modify 글은 서버 정의 그대로라 이미 저장된 이름 줄 사본을 보인다(Modify 글은 이름 줄을 더하지 않으므로 더 늘지 않음).
  앱 언어를 바꾸면(`소스` ↔ `Source`) 전 언어로 저장된 줄은 사용자 주석으로 보인다.

### 확인

2026-11-25, "전" = `origin/command` 4c7a63fa21 빌드, "후" = 이 브랜치 빌드, 같은 작업 공간과 시험 개체(전의 실행으로 더럽혀진 정의를 그대로 후에서 엶).
증거는 `D:\Tools\_scratch\ddl-name-comment-once\`(글 `s\texts\`, 캡처 `shots\before|after\`).

| DB, 뷰 | 전 | 후(이어서 세 번 더 실행) |
|---|---|---|
| SQL Server `chr_v`(전의 실행으로 정의에 네 벌) | 1 → 4 | 1 → 1 → 1 → 1, 세 번째는 `USE`/`GO` 배치 뒤의 글을 `master` 창에서 실행 |
| SQL Server `chr_v3`(정의에 일곱 벌) | 4 → 7 | 1 → 1 → 1 → 1 |
| SQL Server `chr_vcr`(`\r\n` 두 벌), `chr_vu`(이름 줄 + 사용자 주석) | 2, 2 | 1, 1(사용자 주석 `-- my own note`는 남음) |
| Altibase 7.3, PostgreSQL 17, Oracle 1521, MariaDB 11 | 1 → 4 / 1 / 1 / 1 | 모두 1 |

- 실행마다 표지 값이 바뀌어(`v;v4` → `v;v7`) 매번 서버가 받았다. 후의 실행 뒤 서버 정의(`sys.sql_modules`)의 이름 줄은 한 벌이다(`chr_v`·`chr_v3`·`chr_vu` 1,
  실행하지 않은 `chr_vcr`는 2, 프로시저와 `chr_v0`은 0).
- 실행한 적 없는 개체의 DDL 보기 글은 전과 후가 바이트까지 같다: SQL Server·PostgreSQL·Oracle 1521·MariaDB의 테이블 `chr_t`와 뷰 `chr_v0`, 8개 모두(SHA-256 일치).
- CREATE 대상 창도 이름 줄 한 줄. Modify 글은 서버 정의 그대로(`USE` 배치 뒤 이름 줄 한 벌)다. SQL Server 프로시저의 DDL 글에는 전후 모두 이름 줄이 없다.
- 확인하지 않은 것: ShardSphere(카탈로그 원본 길, 코드로 판단), MySQL 8.4(MariaDB와 같은 `SHOW CREATE VIEW` 길), 다른 언어로 저장된 이름 줄.

## 변경 이력

- 2026-11-07 최초 작성. 개체 삭제·이름 바꾸기(객체 관리자, 스크립트 미리 보기, 트랜잭션 질문), 이름·정규화된 이름 복사, 여러 개 선택, SQL 생성 메뉴.
  PostgreSQL·SQL Server·MariaDB에서 실행 확인, 삭제 확인 목록의 불필요한 스크롤바 수정.
- 2026-11-08 유지 관리 명령: 테이블 잘라내기, 트리거 사용/사용 안 함, 구체화된 뷰 새로 고침(모델 도구의 SQL, 확인·미리 보기·트랜잭션 질문). PostgreSQL·SQL Server·Oracle·MariaDB에서 확인.
- 2026-11-08 Altibase 테이블 잘라내기·구체화된 뷰 새로 고침(A5), 컴파일 명령(Oracle·Altibase, O11·A6). 확인은 [swing-routines.md](swing-routines.md).
- 2026-11-08 `runChange`를 속성 편집도 씀. 문장이 없는 변경은 빈 미리 보기 대신 알리고 되돌림([swing-object-properties.md](swing-object-properties.md)).
- 2026-11-13 끌어서 이름 넣기(앱 변경 #112): 트리 끌기(복사만), 쿼리 편집기가 놓은 자리에 한 실행 취소 단계로 넣음. SQL Server·PostgreSQL·MariaDB·Oracle에서 실제 마우스 끌기로 확인.
- 2026-11-13 놓는 창에 맞춘 이름(#112 검토): 같은 연결의 쿼리 창이 개체의 DB에 있으면 DB.스키마.개체에서 DB를 뺌(SQL Server `dbo.t`). 다른 꼴의 이름·다른 창은 그대로.
- 2026-11-15 Altibase 계열 테이블스페이스: 삭제 확인의 내용·데이터 파일 확인란, 유지 관리 명령 온라인·오프라인·자동 확장 설정·데이터 파일 설정(앱 변경 #126, [altibase.md](altibase.md) "테이블스페이스 관리").
- 2026-11-15 Oracle 테이블스페이스: 삭제 확인의 세 확인란, 이름 바꾸기, 유지 관리 명령 온라인·오프라인·읽기 전용·읽기 쓰기·데이터 파일 설정(앱 변경 #127, [oracle-storage.md](oracle-storage.md)). 데이터 파일 설정 툴팁의 문장을 두 DB에 맞게 `ALTER ... DATAFILE ...`로 바꿈.
- 2026-11-15 PostgreSQL 테이블스페이스 이름 바꾸기(앱 변경 #129, [swing-object-properties.md](swing-object-properties.md) "PostgreSQL 테이블스페이스").
- 2026-11-15 SQL Server 파일 그룹·파일: 유지 관리 명령 기본 파일 그룹으로 설정·읽기 전용·읽기 쓰기·파일 설정, 파일 그룹·파일의 삭제·이름 바꾸기(앱 변경 #128, [mssql-storage.md](mssql-storage.md)).
- 2026-11-16 메뉴 항목은 노드의 종류에 해당할 때만 보이고 지금 할 수 없으면 꺼짐: 이름 바꾸기·삭제의 조건을 종류(`appliesRename`·`appliesDelete`)와 지금 상태(`canRename`·`canDelete`)로 나눔, 이름 복사·SQL 생성은 해당할 때만 보임(앱 변경 #137, [context-menus.md](context-menus.md)).
- 2026-11-22 DDL 명령의 취소와 권한 오류: 취소한 생성은 결과를 열지 않음, 권한 오류의 첫 줄(앱 변경 #159, [swing-routines.md](swing-routines.md) "DDL 권한과 취소").
- 2026-11-22 행을 쓰는 생성기(INSERT·UPDATE·DELETE·MERGE)가 객체의 데이터 기능을 물음: 큐에 서버가 거절하는 문장이 나오지 않음(앱 변경 #157), 위 "행을 쓰는 생성기".
- 2026-11-24 "상위 1000개 행 선택"이 Oracle·Altibase·ShardSphere에서 제한 없는 SELECT를 열던 것을 고침: 스크립트만의 변환 종류 `SCRIPT_ROW_LIMIT`
  (`LIMIT n`, Oracle 12c 이상 `FETCH FIRST n ROWS ONLY`), 변환기가 없는 데이터 원본 표(앱 변경 #183), 위 "행 수 제한".
- 2026-11-25 뷰 DDL 글의 객체 이름 줄을 한 번만 보임(`DBStructUtils.withoutObjectNameComment`, `chromite/ddl-name-comment-once`), 위 "DDL 글의 객체 이름 줄".
  회귀 테스트는 실행하지 않았다. 영향받을 수 있는 기능: 모든 DB의 뷰 DDL 보기·CREATE 대상·선택하여 복사(DDL)·SQL 생성 ▸ DDL, 스키마 DDL(PostgreSQL·SQL Server).
