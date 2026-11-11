# Swing 작업 스케줄러 (Oracle DBMS_SCHEDULER, PostgreSQL pg_cron, MySQL/MariaDB 이벤트)

## 목적

DB 관리 기능 공통 축 1번(작업 스케줄러)을 SQL Server 에이전트 다음으로 Oracle, PostgreSQL, MySQL/MariaDB에 넣는다
(SQL Server 에이전트는 [swing-sqlserver-agent.md](swing-sqlserver-agent.md)). 구조는 SQL Server 에이전트를 따른다: 각 ext 드라이버의
모델 패키지(탐색기 노드가 데이터 원본 트리에 붙음), Swing 창(실행 전 스크립트 미리 보기), 알림이 뜨는 빠른 명령, 기록·실행 중 작업 문서 탭,
권한·중지 상태 분류.

| DB | 탐색기 위치 | 할 수 있는 일 |
|---|---|---|
| Oracle | 스키마 > 스케줄러(DBeaver 폴더) > 작업 · 프로그램 · **일정** · **작업 실행 기록** · **실행 중인 작업** | 새 작업, 속성(고치기), 사용 / 사용 안 함, 지금 실행(기다리기 선택), 작업 중지(강제 중지는 실행 중인 작업 탭), 삭제, 기록, 실행 중인 작업. 스케줄러 폴더에 스케줄러 상태 |
| PostgreSQL | 데이터베이스 > **pg_cron** > 작업 · 작업 실행 기록 | pg_cron이 설치된 데이터베이스에서 새 작업(cron.schedule), 속성(cron.alter_job), 사용 / 사용 안 함, 삭제(cron.unschedule), 기록. 설치되지 않았으면 노드가 "설치되지 않음"이고 명령이 꺼진다 |
| MySQL / MariaDB | 데이터베이스 > 이벤트(DBeaver 폴더) | 새 이벤트, 속성(ALTER EVENT), 사용 / 사용 안 함, 삭제, 이벤트 스케줄러 상태(켜는 명령을 보여 주기만 함). 이벤트 폴더에 event_scheduler 상태 |

굵은 글씨는 이번에 더한 트리 항목이다.

## 화면

| 노드 / 창 | 명령·구성 |
|---|---|
| Oracle 스케줄러 폴더 | 새 작업, 기록 보기(스키마 전체), 실행 중인 작업 보기. 보조 글자 = 스케줄러 상태(실행 중 / 중지됨(job_queue_processes = 0) / 사용 안 함(SCHEDULER_DISABLED)), 폴더 아이콘 상태 점 |
| Oracle 작업 폴더 / 작업 | 새 작업, 지금 실행, 작업 중지, 사용, 사용 안 함, 기록 보기, 실행 중인 작업 보기, 삭제, 속성(두 번 클릭). 작업 보조 글자 = 사용 안 함 / 실행 중 |
| Oracle 작업 실행 기록 / 실행 중인 작업(잎) | 두 번 클릭으로 문서 탭 |
| 새 작업 / 작업 속성 (Oracle) | 일반(이름, 스키마, 작업 유형 PL/SQL 블록·저장 프로시저, 주석, 사용, 완료되면 작업 삭제, 동작), 일정(시작 날짜, 반복 간격 + 만들기…, 종료 날짜, 다음 실행 날짜) |
| 지금 실행 | "작업이 끝날 때까지 기다림" 선택(기다리면 이 도구의 연결에서 실행하고 작업의 오류를 바로 보임). 스케줄러가 작업을 실행하지 않으면 기다리기만 된다 |
| pg_cron 노드 | 새 작업, 기록 보기. 보조 글자 = "pg_cron 1.6" / 설치되지 않음 / 권한 없음 (+ ", 스케줄러 중지됨"), 아이콘 상태 점 |
| pg_cron 작업 | 새 작업, 사용, 사용 안 함, 기록 보기, 삭제, 속성(두 번 클릭). 보조 글자 = cron 일정(사용 안 함이면 앞에 표시) |
| 새 pg_cron 작업 / pg_cron 작업 속성 | 이름, 일정 + 만들기…, 데이터베이스, 사용, 명령 |
| MySQL 이벤트 폴더 | 새 이벤트, 이벤트 스케줄러 상태. 보조 글자 = 이벤트 스케줄러 ON / OFF / DISABLED, 폴더 아이콘 상태 점 |
| MySQL 이벤트 | 새 이벤트, 사용, 사용 안 함, 이벤트 스케줄러 상태, 삭제, 속성(두 번 클릭). 보조 글자 = 사용 안 함 |
| 새 이벤트 / 이벤트 속성 | 일반(OFF 경고와 켜는 명령, 이름, 데이터베이스, 상태(사용 / 사용 안 함 / 복제본에서 사용 안 함), 주석, 완료된 뒤에도 유지, 마지막 실행, "실행 기록 없음" 안내, 본문), 일정(되풀이: 간격·단위·시작·종료 / 한 번: 시각, 시간대 안내) |
| 이벤트 스케줄러 상태 | 상태 설명, 실행 기록이 없다는 안내, OFF면 `SET GLOBAL event_scheduler = ON;`과 쿼리 창에서 열기 / 명령 복사 / 닫기 |
| 일정 도우미 | 빈도(N분마다, N시간마다, 매일, 매주(요일), 매월(날짜)), 간격, 시각, 결과 식. Oracle 달력 식과 cron 식을 만든다 |
| 문서 탭 | 작업 실행 기록 - 스키마(.작업) / 데이터베이스(.작업), 실행 중인 작업 - 스키마(자동 새로 고침, 작업 중지·강제 중지) |

바꾸는 창(작업·이벤트 속성)과 삭제는 SQL Server 에이전트처럼 확인을 누르면 실행할 스크립트를 먼저 보여 주고(실행 / 쿼리 창으로 열기 / 복사 / 취소),
"스크립트" 단추는 실행 없이 같은 스크립트를 새 쿼리 창이나 클립보드에 낸다. 지금 실행·중지·사용·사용 안 함은 바로 실행하고 알림으로 결과를 알린다.

## 구조

| 위치 | 클래스 | 역할 |
|---|---|---|
| `com.seizia.chromite.ext.oracle` (`model.scheduler`, 새로 내보냄) | `OracleSchemaScheduler` | 스키마마다 하나. 일정 목록(캐시), 작업 실행 기록·실행 중인 작업 잎 |
| | `OracleSchedulerSchedule`, `OracleSchedulerMonitor` | 탐색기 항목(일정, 잎 두 개) |
| | `OracleSchedulerRunDetail`, `OracleSchedulerRunningJob` | 문서 탭 표의 행(`@Property` 열) |
| | `OracleSchedulerJobDefinition` | 작업 편집 상태와 `CREATE_JOB` / `SET_ATTRIBUTE` 블록 |
| | `OracleSchedulerManager` | 뷰 고르기(DBA_ / ALL_), 권한·스케줄러 상태, 목록·기록 읽기, 달력 식 계산, 문 만들기, 실행(격리된 연결) |
| | `OracleSchedulerException`, `OracleSchedulerMessages`(+ `_ko`) | 권한·스케줄러 중지 오류 분류, 모델 문구 |
| | `OracleSchema#getScheduler` | DBeaver 원본에 게터 하나와 새로 고침 한 줄 |
| `com.seizia.chromite.ext.postgresql` (`model.cron`, 새로 내보냄) | `PostgreCronScheduler` | pg_cron 노드: 설치 여부·권한·버전별 기능·런처 상태, 작업 목록(캐시), 기록 잎 |
| | `PostgreCronJob`, `PostgreCronHistory`, `PostgreCronRunDetail` | 작업, 기록 잎, 기록 행 |
| | `PostgreCronJobDefinition`, `PostgreCronManager` | 작업 편집 상태와 `cron.schedule` / `cron.alter_job` 문, 읽기·실행(격리된 연결, 한 트랜잭션) |
| | `PostgreCronException`, `PostgreCronMessages`(+ `_ko`) | SQLSTATE로 권한·미설치 분류, 모델 문구 |
| | `PostgreDatabase#getCronScheduler` | DBeaver 원본에 게터 하나와 새로 고침 한 줄 |
| `com.seizia.chromite.ext.mysql` (`model.scheduler`, 새로 내보냄) | `MySQLEventScheduler` | event_scheduler 상태, EVENT 권한, 사용·삭제 문, 실행(격리된 연결) |
| | `MySQLEventDefinition` | 이벤트 편집 상태와 CREATE EVENT / ALTER EVENT |
| | `MySQLEventSchedulerException`, `MySQLEventSchedulerMessages`(+ `_ko`) | 권한 오류 분류, 상태 문구 |
| 세 ext의 `plugin.xml`, `OSGI-INF/l10n/bundle(_ko).properties`, `MANIFEST.MF` | | Oracle 스케줄러 폴더 항목 세 개, PostgreSQL 데이터베이스 아래 pg_cron 항목, 속성 이름, 패키지 내보내기 |
| `com.seizia.chromite.ui.swing` | `SchedulerCommands` | 탐색기 명령(세 DB), 켜기·끄기와 도구 설명, Delete·속성·두 번 클릭, 보조 글자·아이콘, 권한·상태 캐시, DB별 실행 방식(`Backend`) |
| | `SchedulerScriptDialog` | 세 DB 창의 공통 부분: 권한 확인 → 스크립트 미리 보기 → 실행 → 노드 다시 읽기 |
| | `OracleJobDialog`, `PgCronJobDialog`, `MySQLEventDialog` | 새로 만들기 / 속성 창 |
| | `ScheduleBuilderDialog` | 일정 도우미(Oracle 달력 식, cron 식) |
| | `SchedulerTablePanel` | 문서 탭: 표 + 고른 행의 상세 글 + 새로 고침(·자동) + 행 명령 |
| | `internal/SchedulerMessages`(+ `_ko`) | 화면 문구 |
| 고친 파일 | `ObjectExplorerPanel` | 메뉴 줄, Properties·Delete 갈래, 두 번 클릭, 보조 글자(SQL Server 에이전트 갈래 옆에 한 줄씩) |
| | `NodeIcons` | 스케줄러 노드 아이콘 갈래 한 줄 |

## 설계 결정과 이유

- **모델은 각 ext 번들에 둔다.** SQL Server 에이전트와 같은 이유다: 탐색기 트리는 데이터 원본의 `plugin.xml`에서 만들어지고, `model.admin`은 ext 번들에
  의존하므로 반대 방향은 순환이다. 세 번들에 공통 기반 클래스를 두려면 core 번들을 고쳐야 해서, 실행기·예외는 DB마다 작게 따로 두었다.
- **DBeaver에 이미 있는 폴더는 넓힌다.** Oracle의 스키마 > 스케줄러(작업, 프로그램)와 MySQL의 이벤트 폴더가 이미 있어서 새 트리를 만들지 않았다.
  Oracle은 `plugin.xml`의 점 경로(`scheduler.schedules`)로 `OracleSchemaScheduler`의 목록을 읽어 `OracleSchema`에는 게터 하나만 더했다.
  pg_cron은 DBeaver에 없어 데이터베이스 아래 항목 하나를 새로 두었다(관리 폴더의 pgAgent "작업"과는 다른 것).
- **pg_cron 노드는 늘 보인다.** pg_cron은 `cron.database_name` 데이터베이스 한 곳에만 설치되고, 설치는 `shared_preload_libraries`와 서버 재시작이
  필요하다(확장을 설치하지 않는다). 노드는 모든 데이터베이스에 두고 보조 글자로 "설치되지 않음" / "권한 없음" / 버전을 알리며, 설치되지 않았으면
  아래 폴더를 숨기고(`visibleIf="object.installed"`) 명령을 도구 설명과 함께 끈다.
- **pg_cron 버전 차이는 카탈로그에서 읽는다.** 작업 이름(1.3), `cron.alter_job`·`cron.job_run_details`·`active` 열·`cron.schedule_in_database`(1.4)가 실제로
  있는지 `pg_proc`·`pg_attribute`로 확인해 칸과 명령을 켜고 끈다. 사용 안 함으로 새 작업을 만들 때는 `cron.alter_job(job_id := cron.schedule(...), active := false)`
  한 문으로 만든다(새 작업 번호를 따로 읽지 않으려고). 같은 이름으로 `cron.schedule`을 부르면 pg_cron이 그 작업을 덮어써서 창이 이름이 겹치는지 먼저 막는다.
- **권한 먼저.** Oracle은 `SESSION_PRIVS`(역할 포함)의 CREATE JOB·CREATE ANY JOB·MANAGE SCHEDULER, MySQL은 `information_schema`의 EVENT 권한(전역·데이터베이스,
  이름 패턴 포함), pg_cron은 cron 스키마 USAGE(슈퍼유저는 모두)를 읽는다. 모자라면 메뉴를 끄고 도구 설명에 필요한 권한을 적는다. 창에서 확인을 눌러도
  같은 문구로 막는다. 읽지 못했거나(권한 뷰를 못 읽음) MySQL 역할처럼 확실히 알 수 없으면 막지 않고 서버의 오류를 분류해 보인다
  (Oracle ORA-01031·27486·27399, PostgreSQL 42501, MySQL 1044·1142·1227).
- **Oracle·MySQL의 권한·상태는 Swing 쪽에 잠시 담아 둔다.** 그 폴더는 DBeaver 폴더라 상태를 들 모델 객체가 없다. 처음 그릴 때 Job으로 읽고(EDT에서 DB를
  부르지 않음) 60초 동안 쓰며, 바꾼 뒤에는 다시 읽는다. 읽은 뒤에는 탐색기 행을 다시 재어(보조 글자가 늘면 행 너비를 다시 계산해야 보인다) 메뉴를 다시 맞춘다.
- **뷰는 권한에 맞춰 고른다.** `DBA_` 뷰를 읽을 수 있으면 그것을, 아니면 `ALL_` 뷰를 쓴다. 스키마 노드 아래 기능이라 늘 `OWNER = 스키마`로 거르므로 `USER_`
  뷰는 `ALL_` 뷰와 결과가 같아 따로 쓰지 않는다. 작업 목록은 DBeaver 코드(연결 설정 "항상 DBA 뷰 사용")를 그대로 둔다.
- **Oracle 라이선스 가드.** AWR·ASH(`DBA_HIST_*`, `V$ACTIVE_SESSION_HISTORY`)·SQL Monitor 뷰는 읽지 않는다. 스케줄러 뷰, `SESSION_PRIVS`,
  `ALL_SCHEDULER_GLOBAL_ATTRIBUTE`, `V$PARAMETER`(job_queue_processes)만 읽는다.
- **트랜잭션 안전.** 바꾸는 문은 모두 격리된 실행 문맥(새 연결)에서 실행하고 닫는다. DBMS_SCHEDULER 호출과 CREATE/ALTER/DROP EVENT는 스스로 커밋해서,
  사용자 편집기 세션에서 실행하면 그 세션의 진행 중인 트랜잭션까지 커밋된다. pg_cron 문은 그 연결에서 자동 커밋을 끄고 한 트랜잭션으로 실행한다(실패하면 모두 롤백).
  Oracle·MySQL은 한 트랜잭션으로 되돌릴 수 없어 실패한 문을 알리고 앞의 문은 적용된 채 둔다(Oracle 속성 바꾸기는 한 PL/SQL 블록이라 대개 문이 하나다).
  읽기는 탐색기와 같은 메타 세션에서 한다(SELECT만).
- **지금 실행.** 기본은 `RUN_JOB(use_current_session => FALSE)`(스케줄러 작업 세션, 바로 돌아옴)이고, "끝날 때까지 기다림"을 고르면 `TRUE`로 이 도구의 격리된
  연결에서 실행해 작업의 오류를 바로 보인다. job_queue_processes가 0이면 스케줄러가 작업을 시작하지 않으므로 기다리기만 고를 수 있다.
- **Oracle 날짜는 TZR 형식 글자.** `YYYY-MM-DD HH24:MI:SS TZR`로 읽고 `TO_TIMESTAMP_TZ`로 넘겨 지역 시간대 이름(Asia/Seoul, 일광 절약 시간)을 잃지 않는다.
  속성 창은 바뀐 속성만 `SET_ATTRIBUTE`(비우면 `SET_ATTRIBUTE_NULL`)로 고친다. 프로그램이나 이름 있는 일정을 쓰는 작업은 그 칸을 읽기 전용으로 둔다.
  "다음 실행 날짜"는 `DBMS_SCHEDULER.EVALUATE_CALENDAR_STRING`(아무것도 바꾸지 않음)으로 서버가 계산한 날짜다.
- **MySQL 이벤트 시간대.** CREATE/ALTER EVENT는 날짜를 세션 시간대로 읽고 일정을 바꾸면 이벤트의 시간대도 세션 시간대가 된다. 기존 이벤트의 일정을 바꿀 때는
  먼저 `SET time_zone = '<이벤트 시간대>'`를 실행한다(격리된 연결이라 다른 세션에 영향 없음, 미리 보기에도 보인다).
- **event_scheduler는 바꾸지 않는다.** 서버 설정(SUPER/SYSTEM_VARIABLES_ADMIN, DISABLED면 설정 파일과 재시작)이라 상태 창과 이벤트 창에 명령을 보여 주고
  쿼리 창으로 열거나 복사만 한다. MySQL·MariaDB는 이벤트 실행 기록을 남기지 않아(마지막 실행 시각만) 기록 탭이 없고 그렇다고 적는다.
- **공통 Swing 부품은 세 DB 사이에서만 나눴다.** 스크립트 미리 보기 창(`SchedulerScriptDialog`), 기록·실행 중 작업 탭(`SchedulerTablePanel`), 일정 도우미
  (`ScheduleBuilderDialog`). SQL Server 에이전트의 `AgentObjectDialog`·`AgentJobHistoryPanel`은 그 모델(실행·단계 묶음)에 맞춘 것이고 PR #83이 막 들어가는
  중이라 고치지 않았다. 일정 도우미는 Oracle 달력 식과 cron 식이 같은 선택지(N분·N시간마다, 매일·매주·매월)를 쓰므로 하나로 만들었다. MySQL은 `EVERY n 단위`라 쓰지 않는다.
- **같은 모델을 물려받은 다른 DB에는 명령을 두지 않는다.** YashanDB(Oracle 모델), TiDB·OceanBase(MySQL 모델)는 스케줄러 동작이 달라 데이터 원본 클래스가 정확히
  Oracle·MySQL일 때만 명령을 보인다(트리 항목은 Oracle `plugin.xml`에만 더했다).
- **화면 문구.** 기능 묶음 `SchedulerMessages`(영어 기본 + `_ko`), 모델 값은 각 ext의 `*Messages`. 한국어 용어는 기존 번역(스케줄러, 작업, 프로그램, 일정, 이벤트,
  간격, 완료 시)과 SQL Server 에이전트 화면(사용, 사용 안 함, 기록 보기, 작업 중지)에 맞췄다. 서버 값(SUCCEEDED, FAILED, ENABLED…)은 그대로 보인다.

## DB별 지원

| 항목 | Oracle | PostgreSQL pg_cron | MySQL / MariaDB |
|---|---|---|---|
| 목록 | 작업·프로그램(DBeaver), 일정 | 작업 | 이벤트(DBeaver) |
| 새로 만들기 / 고치기 | PL/SQL 블록·저장 프로시저 작업 | 이름·일정·명령·데이터베이스·사용 | AT / EVERY, STARTS·ENDS, 상태, ON COMPLETION, 주석, 본문, 이름 바꾸기 |
| 사용 / 사용 안 함 | ENABLE / DISABLE | `cron.alter_job(active)` (1.4+) | ALTER EVENT ENABLE / DISABLE |
| 지금 실행 | RUN_JOB(비동기 / 기다림) | 없음(pg_cron에 기능 없음) | 없음 |
| 중지 | STOP_JOB(강제: MANAGE SCHEDULER) | 없음 | 없음 |
| 삭제 | DROP_JOB | `cron.unschedule` | DROP EVENT |
| 실행 기록 | `*_SCHEDULER_JOB_RUN_DETAILS`(오류·출력) | `cron.job_run_details`(1.4+) | 없음(LAST_EXECUTED만) |
| 실행 중 | `*_SCHEDULER_RUNNING_JOBS` 탭 | 없음 | 없음 |
| 스케줄러 상태 | job_queue_processes, SCHEDULER_DISABLED | "pg_cron launcher" 작업자 | event_scheduler ON/OFF/DISABLED |
| 권한 확인 | SESSION_PRIVS | cron 스키마 USAGE | EVENT 권한 |

## upstream 대비 변경점

- 새 파일: `ext.oracle/.../model/scheduler/*`(Java 9 + 문구 2), `ext.postgresql/.../model/cron/*`(Java 8 + 문구 2), `ext.mysql/.../model/scheduler/*`(Java 4 + 문구 2),
  Swing 번들 `Scheduler*.java` 3개·`ScheduleBuilderDialog`·`OracleJobDialog`·`PgCronJobDialog`·`MySQLEventDialog`, `internal/SchedulerMessages`(+ properties 2).
- 고친 파일(DBeaver 원본): `OracleSchema`(게터·새로 고침), `PostgreDatabase`(게터·새로 고침), Oracle·PostgreSQL `plugin.xml`(트리), 세 ext의 `MANIFEST.MF`(패키지 내보내기),
  Oracle·PostgreSQL `OSGI-INF/l10n/bundle(_ko).properties`.
- 고친 파일(Chromite): `ObjectExplorerPanel`, `NodeIcons`.

## 확인 (2026-11-07)

빌드한 Swing 제품(개인 작업 공간, EDT 테스트 에이전트로 조작)에서 테스트 컨테이너로 했고, 결과를 `docker exec`의 sqlplus·mariadb·psql로 확인했다.

- **Oracle Free 23ai** (`chromite-test-oracle`, 테스트 사용자 `CHR_SCHED`·`CHR_SCHED_RO`, 표 `CHR_SCHED_LOG`, 프로시저 `CHR_SCHED_PROC`·`CHR_SCHED_FAIL`):
  - 새 작업(저장 프로시저, 일정 도우미 → `FREQ=HOURLY;INTERVAL=1`, 다음 실행 날짜 5개) → 스크립트(`CREATE_JOB`) → 실행. `DBA_SCHEDULER_JOBS`에서 확인.
  - 지금 실행(비동기) → 작업 실행 기록 탭에 SUCCEEDED 행.
  - 실패하는 작업(`BEGIN chr_sched.chr_sched_fail; END;`): 지금 실행 + 기다림 → 오류 창에 ORA-20001, 비동기 실행 → 기록에 FAILED / 오류 번호 20001, 상세 칸에 ERRORS·ADDITIONAL_INFO.
  - 사용 안 함 → 보조 글자·알림·`STATE = DISABLED`, 사용 → 다시 SCHEDULED.
  - 속성: 주석과 반복 간격을 바꾸면 `SET_ATTRIBUTE` 두 줄의 블록. 시작 날짜는 `ASIA/SEOUL` 지역 이름으로 되돌아왔다(TZR).
  - 실행 중인 작업: `DBMS_SESSION.SLEEP(90)` 작업을 실행 → 탭에 행 → 작업 중지 → 행이 사라짐. 삭제(확인 → `DROP_JOB` 스크립트 → 실행).
  - 권한: CREATE SESSION만 있는 `CHR_SCHED_RO`로 연결 → 새 작업…이 꺼지고 도구 설명에 CREATE JOB 안내. 스케줄러 상태는 V$PARAMETER를 못 읽어 표시 없음.
- **MariaDB 11.8** (`chromite-test-maria`, 데이터베이스 `chr_job_schedulers`, 표 `chr_sched_log`):
  - event_scheduler OFF: 이벤트 폴더 빨간 점·"이벤트 스케줄러 OFF", 상태 창(명령, 쿼리 창에서 열기 / 복사), 새 이벤트 창 위 경고.
  - 새 이벤트(`EVERY 5 SECOND`, INSERT) → 스크립트(CREATE EVENT) → 실행. OFF 동안 행 0개.
  - 테스트하는 사람이 `docker exec`로 `SET GLOBAL event_scheduler = ON` (Chromite는 실행하지 않음) → 12초 뒤 행 2개, LAST_EXECUTED 기록, 폴더 초록 점·"ON".
  - 사용 안 함 / 사용(알림, 보조 글자), 속성에서 간격 10초 → `SET time_zone = 'SYSTEM'` + `ALTER EVENT ... ON SCHEDULE EVERY 10 SECOND STARTS ...` 확인.
- **PostgreSQL 17** (`chromite-test-pg`): 이미지에 pg_cron이 없다(`pg_available_extensions`에 없음, `shared_preload_libraries` 비어 있음). 컨테이너를 바꾸지 않고
  "설치되지 않음" 경로를 확인했다: 노드 보조 글자 "설치되지 않음", 하위 폴더 없음, 새 작업…·기록 보기가 꺼지고 도구 설명이 이유를 알림.
  pg_cron 문은 별도 데이터베이스 `chr_sched_cron_stub`에 pg_cron 1.6과 같은 시그니처의 대역(`cron.job`, `cron.job_run_details`, `cron.schedule` 두 가지,
  `schedule_in_database`, `alter_job`, `unschedule`)을 만들어 Chromite가 만드는 문(이름 있는·없는 새 작업, 사용 안 함으로 새 작업, 다른 데이터베이스, 고치기, 사용,
  삭제)과 상태·기록 읽기 문이 그 시그니처에 맞게 실행되는지 psql로 확인했다(실제 pg_cron 동작은 확인하지 못했다).
- **화면:** 한국어(OS 기본)·영어(`-nl en`), 라이트·다크에서 트리, 메뉴, 창, 미리 보기, 문서 탭을 창 단위로 찍어 확인했다.

## 남긴 것

- Oracle: 일정·프로그램 만들기/고치기, 체인, 인수가 있는 작업(작업 인수 편집), 작업 클래스·창, EXECUTABLE·SQL_SCRIPT 등 다른 작업 유형 편집,
  이름 있는 일정을 쓰는 작업의 일정 바꾸기, 스케줄러 상태 바꾸기(job_queue_processes는 시스템 설정).
- pg_cron: 실제 pg_cron이 있는 서버에서의 확인, `cron.timezone` 표시, `cron.job_run_details` 정리, 다른 사용자 작업 표시(슈퍼유저만 보임).
- MySQL: 이벤트 실행 기록(서버에 없음), 이벤트 정의자(DEFINER) 바꾸기.
- 쿼리 창으로 연 pg_cron 스크립트는 연결의 기본 데이터베이스에서 열린다(pg_cron이 다른 데이터베이스에 있으면 쿼리 창에서 데이터베이스를 바꿔야 한다).
- SQL Server 에이전트 창과의 공통화(`AgentObjectDialog` → `SchedulerScriptDialog`)는 PR #83이 들어간 뒤 따로.

## 변경 이력

- 2026-11-07 최초 작성: Oracle 스케줄러(일정, 작업 실행 기록, 실행 중인 작업, 작업 창·명령), pg_cron 노드와 작업, MySQL/MariaDB 이벤트 창·명령·이벤트 스케줄러 상태.
