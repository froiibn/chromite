# Swing SQL Server 에이전트 관리

## 목적

SSMS처럼 SQL Server 연결 아래에서 SQL Server 에이전트(작업, 작업 활동 모니터, 경고, 운영자, 프록시, 오류 로그)를 보고 고친다.
DBeaver CE에는 이 기능이 없었다. 모델 코드는 Swing에 의존하지 않는다.

- 개체 탐색기의 서버 노드 아래(보안 폴더 다음)에 **SQL Server 에이전트** 노드. 이름 뒤 보조 글자와 아이콘 점으로 서비스 상태
  (실행 중 / 중지됨 / 사용할 수 없음 / 알 수 없음)를 보인다. 아래 폴더: 작업, 작업 활동 모니터, 경고, 운영자, 프록시, 오류 로그.
- **작업:** 목록(사용 여부는 "사용 안 함" 보조 글자, 마지막 실행 결과·시각, 다음 실행은 속성 창), 오른쪽 클릭 메뉴
  (새 작업, 단계에서 작업 시작, 작업 중지, 사용, 사용 안 함, 기록 보기, 스크립팅 > CREATE 대상, 삭제, 속성).
  작업 속성 / 새 작업 창(일반, 단계, 일정, 알림 페이지)과 작업 기록 탭(실행마다 단계·결과·기간·메시지).
- **작업 활동 모니터:** 모든 작업의 상태·마지막 실행·다음 실행·현재 단계 표, 새로 고침(자동 간격), 시작·중지·사용·사용 안 함·기록·속성.
- **운영자:** 목록, 새로 만들기·편집·삭제(이름, 사용, 전자 메일 이름).
- **경고:** 목록, 사용·사용 안 함, 새로 만들기·편집·삭제(SQL Server 이벤트 경고: 심각도 또는 오류 번호, 데이터베이스, 응답은 작업 실행·운영자 전자 메일).
- **오류 로그:** 에이전트 로그 보기(`xp_readerrorlog <보관 번호>, 2`), 보관 파일 고르기, 검색.
- **프록시:** 목록과 속성(읽기 전용).

바꾸는 창(작업·경고·운영자 속성, 삭제)은 보안 편집기처럼 확인을 누르면 실행할 스크립트를 먼저 보여 주고(실행 / 쿼리 창으로 열기 / 복사 / 취소),
"스크립트" 단추는 실행 없이 같은 스크립트를 새 쿼리 창이나 클립보드에 낸다.

## 화면

| 노드 / 창 | 명령·구성 |
|---|---|
| SQL Server 에이전트 노드 | 새 작업, 새 경고, 새 운영자, 기록 보기(모든 작업), 작업 활동 보기, 에이전트 로그 보기. 보조 글자 = 서비스 상태 |
| 작업 폴더 | 새 작업, 기록 보기, 작업 활동 보기, 필터·새로 고침 |
| 작업 | 새 작업, 단계에서 작업 시작, 작업 중지, 사용, 사용 안 함, 기록 보기, 스크립팅 > CREATE 대상, DDL 보기, 삭제, 속성(두 번 클릭) |
| 작업 활동 모니터(잎) | 작업 활동 보기(두 번 클릭) |
| 경고 폴더 / 경고 | 새 경고 / 사용, 사용 안 함, 삭제, 속성(두 번 클릭) |
| 운영자 폴더 / 운영자 | 새 운영자 / 삭제, 속성(두 번 클릭) |
| 오류 로그 폴더 / 로그 | 에이전트 로그 보기(두 번 클릭) |
| 작업 속성 / 새 작업 | 일반(이름, 소유자, 범주, 설명, 사용), 단계(목록, 새로 만들기·삽입·편집·삭제·위로·아래로, 시작 단계), 일정(목록, 새로 만들기·편집·제거), 알림(전자 메일, Windows 애플리케이션 이벤트 로그, 자동 삭제 + 시기) |
| 새 작업 단계 / 작업 단계 속성 | 일반(단계 이름, 유형 T-SQL·CmdExec·PowerShell, 데이터베이스, 명령), 고급(성공·실패한 경우 동작, 재시도 횟수·간격) |
| 새 작업 일정 / 작업 일정 속성 | 이름, 사용, 일정 유형(되풀이, 한 번, 에이전트 시작 시, CPU 유휴 시), 빈도(매일·매주(요일)·매월(날짜 / 첫째…마지막 요일)), 일별 빈도(한 번 / N시간·분·초마다, 시작·종료 시간), 기간, 요약 |
| 경고 속성 / 새 경고 | 일반(이름, 사용, 유형, 데이터베이스, 오류 번호 또는 심각도), 응답(작업 실행, 운영자에게 전자 메일), 옵션(전자 메일에 오류 텍스트 포함, 응답 간 지연) |
| 운영자 속성 / 새 운영자 | 이름, 사용, 전자 메일 이름 |
| 문서 탭 | 작업 활동 모니터 - 서버, 작업 기록 - 작업(또는 서버 전체), SQL Server 에이전트 로그 - 서버 |

- **에이전트가 멈춰 있으면** 시작·중지 메뉴와 단추가 꺼지고 도구 설명이 "SQL Server 에이전트가 실행 중이 아닙니다."를 알린다. 정의를 바꾸는 명령은 쓸 수 있다(msdb만 바꿈).
- **에이전트가 없는 에디션(Express, Azure SQL Edge)**은 노드가 "사용할 수 없음"으로 남고 바꾸는 명령이 모두 꺼진다(도구 설명으로 이유). 보기 명령(기록, 로그, 활동)은 남는다.
- **에이전트가 아예 없는 서버(Azure SQL Database, Synapse, Babelfish)**에는 노드가 없다.
- **권한:** msdb 권한·에이전트 역할이 모자라 서버가 229(EXECUTE 거부), 916(msdb 접근 불가), 15003(sysadmin만) 같은 오류를 내면
  "SQL Server 에이전트를 사용할 권한이 없습니다. … SQLAgentUserRole, SQLAgentReaderRole, SQLAgentOperatorRole …" 문구를 먼저 보이고
  서버 메시지는 그 아래 줄에 둔다(스택 추적은 오류 창의 "자세히"에만). 22022·15281(에이전트 멈춤)은 "실행 중이 아닙니다" 문구다.

## 구조

| 위치 | 클래스 | 역할 |
|---|---|---|
| `com.seizia.chromite.ext.mssql` (`model.agent` 패키지, 새로 내보냄) | `SQLServerAgent` | 에이전트 노드: 서비스 상태, 아래 폴더의 목록(캐시), 새로 고침 |
| | `SQLServerAgentJob`, `SQLServerAgentAlert`, `SQLServerAgentOperator`, `SQLServerAgentProxy`, `SQLServerAgentErrorLog`, `SQLServerAgentActivityMonitor` | 탐색기 항목(`@Property` 값은 속성 창·작업 활동 모니터 표에 보임). 작업은 `DBPScriptObject`(생성 스크립트) |
| | `SQLServerAgentJobDefinition`, `SQLServerAgentJobStep`, `SQLServerAgentSchedule` | 작업 정의(편집 상태)와 만들기·바꾸기·지우기 문 |
| | `SQLServerAgentAlertDefinition`, `SQLServerAgentOperatorDefinition` | 경고·운영자 편집 상태와 문 |
| | `SQLServerAgentManager` | 목록·기록·로그·창의 선택 값 읽기, 문 실행(격리된 연결, 한 트랜잭션) |
| | `SQLServerAgentSql`, `SQLServerAgentException`, `SQLServerAgentMessages`(+ `_ko`) | `EXEC msdb.dbo.…` 문 만들기·결과 집합 읽기, 권한·에이전트 멈춤 오류 분류, 모델 문구 |
| | `SQLServerDataSource#getAgent` | 탐색기 `agent` 항목(서버당 하나), 데이터 원본 새로 고침 때 다시 만듦 |
| | `plugin.xml`, `OSGI-INF/l10n/bundle(_ko).properties` | 트리(`agent` 항목 아래 폴더 다섯 개 + 잎 하나), 폴더·속성 이름 |
| `com.seizia.chromite.ui.swing` | `AgentCommands` | 탐색기 명령(메뉴 항목, 켜기·끄기, 도구 설명), Delete·속성·두 번 클릭, 보조 글자·아이콘, 시작·중지·사용 실행과 노드 다시 읽기 |
| | `AgentObjectDialog` | 작업·경고·운영자 창의 공통 부분: 확인 → 스크립트 미리 보기 → 실행 → 노드 다시 읽기, 스크립트 단추 |
| | `AgentJobDialog`, `AgentJobStepDialog`, `AgentScheduleDialog`, `AgentAlertDialog`, `AgentOperatorDialog` | 속성 / 새로 만들기 창 |
| | `AgentJobActivityPanel`, `AgentJobHistoryPanel`, `AgentErrorLogPanel` | 문서 탭(작업 활동 모니터, 작업 기록, 에이전트 로그) |
| | `internal/AgentMessages`(+ `_ko`) | 화면 문구 |
| 고친 파일 | `ObjectExplorerPanel` | 메뉴 줄(`agentCommands.addTo`), Delete·속성 갈래, 두 번 클릭, 보조 글자, `QueryWindows#openToolDocument` |
| | `MainFrame` | `openToolDocument`(열쇠로 찾아 여는 도구 문서 탭) |
| | `NodeIcons` | 에이전트 노드 아이콘(서비스 상태 점) |
| | `DatabaseTaskDialog` | 실행 전 확인 단계 `confirmRun`(기본은 그대로 실행) |

## 설계 결정과 이유

- **모델은 `ext.mssql`에 둔다(`model.admin`이 아니라).** 탐색기 트리는 데이터 원본의 `plugin.xml` 트리에서 만들어지고 항목 목록은
  부모 객체(`SQLServerDataSource`)의 게터를 리플렉션으로 부른다(`DBNDatabaseNode.extractPropertyValue`). 다른 번들이 SQL Server 트리에
  노드를 더하는 방법(`treeInjection`)은 같은 공급자의 하위 공급자용이고, `model.admin`은 이미 `ext.mssql`에 의존하므로 반대 방향 의존은
  순환이다. 작업이 `DBPScriptObject`로 생성 스크립트를 내려면 작업 정의와 문 만들기도 같은 번들에 있어야 해서, 에이전트 모델 전체를
  `ext.mssql`의 새 패키지에 두었다. DBeaver 원본 파일은 `SQLServerDataSource`(게터 하나, 새로 고침 한 줄), `plugin.xml`, 묶음 문구만 고쳤다.
- **Swing 전용 가짜 노드 대신 탐색기 모델 노드.** 모델 노드라서 지연 읽기, F5, 필터, 속성 창, 찾기, "스크립팅 > CREATE 대상"·"DDL 보기"
  (`ObjectScripts`가 `DBPScriptObject`를 씀)가 따로 코드 없이 된다. 탐색기 파일은 메뉴 줄과 갈래만 더했다.
- **msdb 도움말 프로시저로 읽는다.** `sp_help_job`, `sp_help_alert`, `sp_help_operator`, `sp_help_proxy`, `sp_help_jobstep`, `sp_help_jobschedule`,
  `sp_help_jobhistory`, `sp_help_notification`. msdb 에이전트 역할에는 `sysjobs` 같은 테이블의 SELECT 권한이 없고(2026-11-07 `msdb.sys.database_permissions` 확인:
  SQLAgentUserRole은 `sysjobs_view`·프로시저 EXECUTE만) 프로시저가 역할에 맞게 걸러 주기 때문이다. `sp_help_job`은 에이전트가 멈춰 있어도 된다(확인).
- **서비스 상태.** `sys.dm_server_services`의 에이전트 행(VIEW SERVER STATE 권한)으로 판단한다. Linux에서도 이 뷰에 "SQL Server Agent (MSSQLSERVER)" 행이 있다(확인).
  그 권한이 없으면 "Agent XPs" 구성 값이 꺼져 있을 때만 "중지됨"(에이전트가 시작할 때 켬, 에이전트 로그에서 확인)이고 켜져 있으면 "알 수 없음"이다.
  에이전트를 멈춘 뒤에도 Agent XPs가 켜진 채 남았기 때문이다(2026-11-07 확인). "알 수 없음"이면 시작·중지를 막지 않고 서버 응답(22022 → "실행 중이 아닙니다")에 맡긴다. `SERVERPROPERTY('EngineEdition')` 4(Express)·9(Edge)는 "사용할 수 없음",
  5·6·11(Azure SQL Database, Synapse)은 노드를 만들지 않는다.
- **바꾸는 문은 문마다 독립된 `EXEC msdb.dbo.…`.** SSMS "Script Job as"의 한 배치(`@jobId` 변수와 `GOTO QuitWithRollback`)는 쿼리 창이 문 단위로 나눠 실행하면 변수가 사라진다.
  그래서 새 작업은 이름, 기존 작업은 `@job_id`로 가리키고, 미리 보기·쿼리 창·실행에 같은 글을 쓴다. 실행은 격리된 연결에서
  `BEGIN TRANSACTION … COMMIT`(실패하면 롤백)이라 반쯤 만들어진 작업이 남지 않는다. 사용자 세션의 트랜잭션은 건드리지 않는다(트랜잭션 안전 규칙).
- **작업 바꾸기.** 일반·알림은 바뀐 인자만 `sp_update_job`, 단계는 하나라도 바뀌면 모두 지우고(`sp_delete_jobstep @step_id = 0`, msdb 소스 확인) 다시 만든 뒤 시작 단계를 정한다.
  단계 번호가 위치라 끼워 넣기·옮기기·지우기를 하나씩 맞추면 "단계로 이동" 번호가 엇갈리기 쉽다. 작업 기록은 단계를 지워도 남는다. 창은 단계를 넣거나 옮기거나 지울 때
  다른 단계의 "단계로 이동" 번호와 시작 단계를 함께 고친다. 일정은 없어진 것 `sp_detach_schedule @delete_unused_schedule = 1`, 새것 `sp_add_jobschedule`, 바뀐 것 `sp_update_schedule`.
  새 작업은 `sp_add_jobserver @server_name = N'(local)'`까지 한다(대상이 없으면 실행되지 않음).
  마지막 단계의 "다음 단계로 이동"은 성공이면 "성공 보고와 함께 작업 종료", 실패면 "실패 보고와 함께 작업 종료"로 저장한다. 그대로 두면 에이전트가
  없는 단계로 가려다 작업을 실패로 끝낸다(확인 중 겪음).
- **경고 바꾸기.** `sp_update_alert`의 소스(msdb, 2022)대로 데이터베이스는 빈 문자열, 작업은 0 uniqueidentifier로 지운다. 심각도와 오류 번호는 둘 중 하나가 0이어야 해 함께 넘긴다.
- **시작·중지·사용·사용 안 함은 바로 실행.** SSMS와 같이 확인 없이 진행 대화상자에서 실행하고 알림으로 결과를 보인다. 시작은 단계가 여럿이면 시작할 단계를 묻는다(시작 단계가 기본).
- **실행 뒤 트리 갱신.** 에이전트 노드를 모델에서 다시 읽고(`refreshNode`) 이벤트를 받은 Object Explorer가 펼친 폴더를 그대로 둔 채 자식을 다시 그린다.
  Object Explorer의 F5 경로는 새로 고친 노드의 자식 트리 노드를 새로 만들어 작업 폴더가 접히므로 쓰지 않았다(보안 편집기가 겪은 것과 같은 동작).
- **작업 활동 모니터·작업 기록·로그는 문서 탭.** SSMS는 따로 창을 열지만 이 앱의 Activity Monitor가 문서 탭이라 맞췄다. 같은 서버(작업)의 탭이 열려 있으면 그 탭을 보인다
  (`MainFrame.openToolDocument`, 탭의 열쇠는 클라이언트 속성). 서버 연결은 Activity Monitor와 같은 `AdminConnection`(탭 하나에 격리된 연결 하나).
- **속성 창 틀.** SSMS 작업·경고·운영자 창은 데이터베이스 작업 창과 같은 모양(페이지 선택, 연결, 진행률, 스크립트 단추)이라 `DatabaseTaskDialog`를 썼다.
  실행 전 스크립트 미리 보기를 넣으려고 `confirmRun` 단계를 하나 더했다(백업·복원은 기본값 그대로).
- **일정 요약은 화면 언어로 만든다.** 서버의 `schedule_description`은 영어이고 저장 전 일정에는 없어서 칸 값으로 요약 문장을 만든다.
- **화면 문구.** 기능 전용 묶음 `AgentMessages`(영어 기본 + `_ko`), 모델 값(노드 이름, 상태, 결과)은 `SQLServerAgentMessages`. 한국어는 SQL Server 에이전트 한국어 화면의 용어
  (작업, 단계, 일정, 경고, 운영자, 프록시, 작업 활동 모니터, 범주, 소유자, 되풀이, 일별 빈도…). 다른 제품 이름은 화면 문구에 쓰지 않는다.

## SSMS 대비

| SSMS | Chromite | 비고 |
|---|---|---|
| SQL Server Agent 노드, 상태 아이콘 | 있음(보조 글자 + 상태 점) | 서비스 시작·중지·다시 시작은 없음(남긴 것) |
| Jobs: New Job, Start Job at Step, Stop Job, Enable, Disable, View History, Script Job as CREATE, Delete, Properties | 있음 | Script Job as는 CREATE To만(DROP·DROP and CREATE 없음) |
| Job Properties: General, Steps, Schedules, Alerts, Notifications, Targets | General, Steps, Schedules, Notifications | Alerts·Targets 페이지 없음 |
| Job Step: General, Advanced | 있음 | 출력 파일·로그 테이블·실행 사용자(Run as)는 기존 값을 그대로 둠 |
| Job Schedule: 모든 유형·빈도 | 있음 | |
| Job Activity Monitor: 표, 새로 고침, 시작·중지·사용·기록·속성, 필터 | 있음 | 열 필터 대신 검색 칸 |
| Log File Viewer(작업 기록) | 작업 기록 탭 | 실행 → 단계 → 메시지 세 칸 |
| Alerts: event / performance / WMI, Response, Options | SQL Server 이벤트 경고 | 성능 조건·WMI 경고는 이름·사용·응답만 편집 |
| Operators: General(e-mail, pager), Notifications, History | 이름, 사용, 전자 메일 이름 | 호출기·net send·알림 페이지 없음 |
| Proxies | 목록·속성(읽기 전용) | 새로 만들기·편집 없음 |
| Error Logs: Current, Archive #n, Configure | 있음(Linux는 현재 로그만, 서버가 목록을 주지 않음) | Configure 없음 |
| Agent Properties, Multi-Server Administration, Manage Job Categories, Manage Schedules | 없음 | 남긴 것 |

## upstream 대비 변경점

- 새 파일: `ext.mssql/src/.../model/agent/*`(Java 16개 + 문구 2개), Swing 번들 `Agent*.java` 10개, `internal/AgentMessages`(+ properties 2개).
- 고친 파일(DBeaver 원본): `SQLServerDataSource`(에이전트 게터·새로 고침), `ext.mssql/plugin.xml`(트리), `META-INF/MANIFEST.MF`(패키지 내보내기), `OSGI-INF/l10n/bundle(_ko).properties`.
- 고친 파일(Chromite): `ObjectExplorerPanel`, `MainFrame`, `NodeIcons`, `DatabaseTaskDialog`(위 표).

## 확인 (2026-11-07)

빌드한 Swing 제품(개인 작업 공간, EDT 테스트 에이전트로 조작)에서 테스트 컨테이너의 SQL Server 2022(Linux, Developer)로 하고, 결과를 `docker exec`의 `sqlcmd`로 msdb에서 확인했다.

- **에이전트 켜기:** 컨테이너의 에이전트가 꺼져 있었다(`dm_server_services` "Stopped", Agent XPs 0). `mssql-conf set sqlagent.enabled true` 후 컨테이너를 다시 시작했다(데이터는 컨테이너에 그대로).
- **작업 만들기:** 새 작업…(단계 2개: INSERT, `WAITFOR` 20초 + INSERT / 일정: 매주 월·수, 1시간마다) → 스크립트 → 실행. `sysjobs`·`sysjobsteps`·`sysschedules`(`freq_type` 8, `freq_interval` 10)·`sysjobservers` 확인.
- **실행과 기록:** 단계에서 작업 시작…(시작 단계 고르기) → 작업 활동 모니터에서 "실행 중 / 현재 단계 2 (step 2 wait)" → 끝난 뒤 "유휴 / 성공". 기록 탭에서 실행·단계·메시지 확인.
- **확인 중 발견해 고친 것:** 첫 실행이 "JobManager tried to run a non-existent step (3)"로 실패했다. 마지막 단계의 "다음 단계로 이동"이 원인이라 마지막 단계는 그 동작을 작업 종료(성공/실패)로 저장하게 고쳤다(`SQLServerAgentJobStep.asLastStep`). 작업 속성에서 단계 2의 동작을 바꾸는 편집(단계 모두 지우고 다시 만들기 스크립트)도 이때 확인했다.
  그 밖에: 오류 로그 탭이 열리지 않던 문제(필드 초기화 순서로 표 정렬기가 빈 목록을 읽음), 경고 오류 번호 칸의 천 단위 쉼표와 없는 번호(50001) 기본값, 다시 연결 뒤 작업 활동 모니터가 옛 에이전트 객체로 상태를 읽던 문제, Agent XPs만으로 "실행 중"이라 하던 판단(에이전트를 멈춰도 1로 남음).
- **실패하는 작업:** `SELECT 1/0` 단계 → 기록에 "Divide by zero error encountered … (Error 8134). The step failed." 표시.
- **사용 안 함 / 사용:** 탐색기에 "사용 안 함" 보조 글자와 알림, msdb `enabled` 확인.
- **운영자·경고:** 새 운영자(전자 메일), 새 경고(심각도 017, 데이터베이스, 작업 실행, 운영자 전자 메일 알림, 지연 60초) → `sysoperators`·`sysalerts`·`sysnotifications` 확인. 경고·운영자 속성 창, 삭제(확인 → 스크립트 → 실행, 트리에서 사라짐).
  없는 오류 번호로 만들면 서버 오류(14262)와 실패한 문이 메시지 페이지에 보인다.
- **스크립팅 > CREATE 대상 > 새 쿼리 편집기 창:** 작업 생성 스크립트가 열림.
- **오류 로그:** 현재 에이전트 로그(Linux는 `xp_enumerrorlogs 2`가 목록을 주지 않아 "현재" 하나), 새 줄이 위.
- **에이전트 멈춤:** `sqlagent.enabled false` + 컨테이너 재시작 → 노드 "중지됨"(빨간 점), 작업 활동 모니터의 시작·중지 단추가 꺼지고 도구 설명 "SQL Server 에이전트가 실행 중이 아닙니다.".
- **권한:** msdb 역할이 없는 로그인 → 작업 폴더에서 권한 문구 + 서버 메시지(229 `sp_help_job`), 노드 상태 "알 수 없음"(VIEW SERVER STATE 없음, Agent XPs 1).
  SQLAgentUserRole만 있는 로그인 → 작업 목록은 읽히고 경고 폴더에서 권한 문구(229 `sp_help_alert`).
- **화면:** 한국어(OS 기본)·영어(`-nl en`), 라이트·다크에서 트리, 메뉴, 작업 활동 모니터, 기록, 로그, 작업·단계·일정·경고·운영자 창, 스크립트 미리 보기를 창 단위로 찍어 확인했다(글자 잘림 없음).
- 테스트 객체(`chr_agent_*` 작업·일정·경고·운영자, 테스트 로그인 2개, `chr_sqlserver_agent` 데이터베이스)는 끝에 지웠다. 에이전트는 켜 둔 채로 두었다.

## 남긴 것

- 에이전트 서비스 시작·중지·다시 시작(Windows 서비스 제어, Linux는 `mssql-conf`), 에이전트 속성 창.
- 작업 속성의 경고·대상 페이지, 다중 서버 관리, 범주 관리, 일정 관리(공유 일정 목록), "Script Job as DROP / DROP and CREATE".
- 성능 조건·WMI 경고 만들기, 경고 심각도 설명 문구(번호만 고름), 운영자의 호출기·근무 시간, 운영자 알림 페이지.
- 프록시 만들기·편집, 오류 로그 구성(Configure).
- 작업 단계의 출력 파일·로그 테이블·실행 사용자(Run as) 편집(기존 값은 유지).

## 변경 이력

- 2026-11-07 최초 작성: SQL Server 에이전트 노드, 작업(속성·단계·일정·알림, 시작·중지·사용·기록·스크립트·삭제), 작업 활동 모니터, 운영자, 경고, 오류 로그, 프록시(읽기).
