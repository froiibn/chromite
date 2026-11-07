# Swing 쿼리 창 자동 저장과 복원

## 목적

SWT에는 스크립트 자동 저장(편집기를 닫거나 끝낼 때 스크립트 파일에 저장)과 다시 시작할 때 열려 있던 편집기를 다시 여는 기능이 있었다
(대조 감사 B-S38, A-S17). Swing 쿼리 창은 끝낼 때 저장 질문만 있고, 다음 시작에는 빈 문서 영역이었다. 프로세스가 비정상 종료되면
저장하지 않은 글이 사라졌다.

Swing은 열린 쿼리 창을 작업 공간의 복원 파일에 저장하고, 다음 시작에서 한 번 물어 연결·기본 DB/스키마와 함께 다시 연다.
연결은 하지 않고, 아무것도 다시 실행하지 않는다.

## 동작

- **자동 저장**: 10초마다(`QueryWindowRecovery.SAVE_INTERVAL_MS`) 열린 쿼리 창의 상태를 만들어 지난번과 다를 때만 쓴다. 메인 창이 닫힐 때
  (File > Exit, 창 닫기, 업데이트의 다시 시작, 외부 종료 요청) 한 번 더 바로 쓴다. 프로세스를 강제로 끝내면 마지막 10초 주기의 상태가 남는다.
- **저장하는 것**: 창 순서와 활성 창, 창 이름(`SQLQueryN.sql` 또는 파일 이름), 파일 경로, 연결(프로젝트 이름 + 연결 ID), 기본 DB/스키마,
  고침 여부, 커서 위치. 글은 파일에 없는 것만 저장한다(고친 창, 한 번도 저장하지 않은 창). 고치지 않은 파일 창은 경로만 적는다.
- **저장하지 않는 창**: Query Library 쿼리 창(라이브러리에 저장된다), 고치지 않은 Modify 창(그 사이 개체 정의가 바뀌었으면 옛 정의를 실행할 위험).
  고친 Modify 창은 글을 잃지 않도록 평범한 쿼리 창으로 복원한다(Debug·실행 뒤 탐색기 갱신 연결은 없다). DDL 탭, 테이블 디자이너, 도구 문서도 넣지 않는다.
- **끝낼 때**: 저장 질문(Save / Don't Save / Cancel)은 그대로다. Save는 파일에 쓰고, Don't Save는 파일에 쓰지 않는다. 어느 쪽이든 창의 지금 상태가
  복원 파일에 남아 다음 시작에서 돌아온다(Don't Save한 창은 고친 창으로). 버리려면 다음 시작의 질문에서 Discard를 고른다.
- **복원 질문**: 메인 창이 열린 뒤 한 번 묻는다. "Restore N query windows from the last session? Nothing is run: a window connects when you
  run a query in it." / "지난 세션의 쿼리 창 N개를 복원할까요? 아무것도 실행하지 않습니다. 창은 쿼리를 실행할 때 연결합니다."
  단추는 **Restore**(기본, 아무것도 실행하지 않음)와 **Discard**다. 복원할 창이 없으면 묻지 않는다.
- **Restore**:
  - 창을 같은 순서로 열고 예전 활성 창을 보인다. 파일 창은 파일을 다시 읽고(고친 창은 저장한 글로 바꾸고 고침 표시), 파일이 없어진
    고치지 않은 창은 건너뛴다. 고친 창의 파일이 없어졌으면 그 경로로 열고, 저장하면 다시 만든다.
  - 연결은 프로젝트 이름과 연결 ID로 찾는다(연결 이름이 바뀌어도 찾는다). 지워진 연결이면 연결 없이 연다.
  - **연결하지 않는다.** 창의 연결 콤보에 연결이 정해지고, DB 콤보(꺼짐)와 상태 줄에 복원할 기본 DB/스키마(`DB.스키마`)가 보인다.
    사용자가 그 창에서 실행하면 연결되고, 창의 세션이 처음 열릴 때 예전 기본 DB/스키마를 적용한 뒤 실행한다. 이미 연결된 연결(다른 창이 연결함)의 창은
    그때 세션을 열고 적용한다.
  - **아무것도 다시 실행하지 않는다.** 결과, 메시지, 실행 계획, `@set` 변수, 열린 트랜잭션은 복원하지 않는다.
  - 새 쿼리 창 번호는 복원한 `SQLQueryN.sql` 다음부터다.
- **Discard**(또는 대화상자 닫기): 창을 열지 않는다. 복원 파일은 지우지 않고 `query-windows.discarded.json`으로 한 번 남긴다(잘못 누른 경우 손으로 되살릴 수 있게).
  다음 Discard가 덮어쓴다.

## 구조

| 위치 | 역할 |
|---|---|
| `ui.swing` `QueryWindowRecovery` (새로 만듦) | 상태 만들기(EDT), 주기 저장(Swing 타이머 + 쓰기 스레드 하나), 끝낼 때 저장, 복원 질문, 창 다시 열기, 연결 찾기 |
| `ui.swing` `MainFrame` | `QueryWindowRecovery.Host` 구현(Query Library 호스트와 같은 메서드), 창이 열린 뒤 `start`, `dispose`에서 `stop`, 복원한 `SQLQueryN` 번호 뒤로 새 창 번호 |
| `ui.swing` `SqlEditorPanel` | `DefaultObjectNames`(기본 DB/스키마 이름), `getDefaultObjectNames`, `setRestoredDefaults`, 세션 열기를 `openWindowContext` 하나로 모아 처음 열 때 복원값 적용, 연결 전 DB 콤보·상태 줄에 복원값 |
| `ui.swing` `internal.ScriptsMessages` | 복원 질문 문구(영어, 한국어) |
| 작업 공간 `.metadata/chromite-recovery/query-windows.json` | 복원 파일(JSON, UTF-8). `query-windows.discarded.json`은 마지막으로 버린 것 |

복원 파일 모양(`version` 1):

```json
{"version":1,"active":1,"windows":[
  {"name":"SQLQuery1.sql","project":"General","connection":"<연결 ID>","catalog":"chr_db","schema":"dbo","modified":true,"text":"SELECT ...","caret":22},
  {"name":"report.sql","file":"D:\\...\\Scripts\\report.sql","project":"General","connection":"<연결 ID>","catalog":"master","schema":"dbo","modified":false,"caret":0}
]}
```

## 설계 결정과 이유

- **스크립트 파일이 아니라 복원 파일에 저장.** SWT 자동 저장은 새 편집기마다 Scripts 폴더에 파일을 만들고 그 파일에 썼다. Swing 쿼리 창은 SSMS처럼
  저장 전에는 파일이 없는 `SQLQueryN.sql`이고, 끝낼 때 저장 질문이 있다. 파일에 몰래 쓰면 "Don't Save"의 뜻이 바뀌고 Scripts 폴더가 빈 스크립트로 찬다.
  복원 파일은 파일 저장과 따로라 질문의 뜻을 바꾸지 않으면서 저장하지 않은 글을 잃지 않는다.
- **한 파일, 임시 파일을 거쳐 바꿔 넣기**(`ATOMIC_MOVE`). 쓰는 중에 죽어도 이전 상태나 새 상태 중 하나가 남는다. 창마다 파일을 두면 목록과 글이 어긋날 수 있다.
- **주기 비교.** 10초마다 EDT에서 상태를 JSON으로 만들어 지난번과 같으면 쓰지 않는다. 문서 리스너나 이벤트를 창마다 달지 않아도 글, 연결 콤보,
  DB 콤보, 저장, 탭 열고 닫기, 커서까지 한 곳에서 잡는다. 쓰기는 작업 스레드 하나에서 하고, 끝낼 때의 마지막 쓰기는 EDT에서 바로 한다
  (먼저 대기하던 쓰기가 나중에 덮지 않도록 번호로 거른다).
- **질문은 창이 열린 뒤 한 번, Restore가 기본.** 복원은 아무것도 실행하지 않으므로 기본 단추여도 안전하다(공통 규칙: 실행하거나 바꾸는 확인만 취소가 기본).
  묻는 동안과 그 전에는 주기 저장을 시작하지 않아 지난 세션의 파일을 덮어쓰지 않는다.
- **연결하지 않고 복원, 첫 실행 때 기본 DB/스키마 적용.** 시작할 때 창마다 연결하면 서버가 꺼져 있거나 비밀번호를 묻는 연결이 시작을 막는다.
  기본 DB/스키마는 창의 세션이 처음 열릴 때(`SqlEditorPanel.openWindowContext`) 적용한다. 실행, 실행 계획, 기본 DB 읽기, 커밋 방식 읽기가 모두 이 메서드로
  세션을 열므로 어느 것이 먼저 열어도 첫 실행 전에 적용된다(적용 중이면 실행이 기다린다). 새 세션이라 트랜잭션이 없어, PostgreSQL처럼 DB를 바꾸면
  세션을 다시 여는 경우에도 물을 것이 없다(트랜잭션 안전 규칙).
- **주 문맥을 함께 쓰는 창에는 적용하지 않음.** "쿼리 창마다 별도 연결"이 꺼졌거나 "단일 연결" 연결이면 창들이 연결의 주 문맥을 함께 쓴다. 복원한 창이
  그 기본값을 바꾸면 같은 연결의 다른 창(복원한 다른 창 포함)의 기본값이 바뀐다. 이때는 적용하지 않고 함께 쓰는 기본값을 보인다(로그에 남김).
- **Discard는 한 번 남김.** 저장하지 않은 글이 들어 있으므로 잘못 누르면 되살릴 길을 하나 둔다. 화면에는 노출하지 않는다.
- **켜고 끄는 설정 없음.** 매번 묻고 Discard로 버릴 수 있어서 설정을 더하지 않았다(요청 범위 밖).

## upstream 대비 변경점

- SWT 자동 저장(`SQLPreferenceConstants` 자동 저장 키, 스크립트 파일에 저장)과 편집기 다시 열기(Eclipse 작업대 메멘토)를 옮기지 않고 Swing에 새로 썼다.
- SWT는 다시 열 때 묻지 않았다. Swing은 한 번 묻는다(Restore 기본).
- SWT 다시 열기는 편집기 입력(스크립트 파일)과 연결을 되살렸다. Swing은 저장하지 않은 창의 글, 기본 DB/스키마, 커서 위치도 되살린다.

## 확인

회귀 테스트는 만들지 않았다(요청 없음). 앱 사본에서 확인한 내용은 아래 "확인 기록"에 있다.

### 확인 기록 (2026-11-08, Windows, 이 브랜치 빌드, 작업 공간 사본, Docker 테스트 DB의 임시 DB `chr_script_autosave`, 프로세스 안 드라이버로 조작)

| 확인 | 결과 |
|---|---|
| 세션 1(영어): 스크립트 2개를 PostgreSQL, 1개를 SQL Server에 묶고 Object Explorer > Recent Scripts | PostgreSQL: `pg-new.sql`, `reports/pg-old.sql`(최근 고친 순), SQL Server: `report.sql`, MariaDB: 꺼진 "No scripts linked to this connection". 둘째 항목을 고르면 `pg-old.sql - PostgreSQL 테스트` 탭 |
| 세션 1: SQL Server 창(DB `chr_script_autosave`), MariaDB 창(`chr_script_autosave`), PostgreSQL 창 2개, `report.sql`을 열고 고침(커서 10), 12초 대기 | 복원 파일에 창 6개, 저장하지 않은 글, 기본 DB/스키마, 커서가 기록됨 |
| 프로세스 강제 종료(Stop-Process -Force) 뒤 다시 시작 | "Restore 6 query windows from the last session?…", Restore가 초점·기본 단추. 누르기 전 문서 탭 0개, 모든 연결 끊김 |
| Restore | 창 6개가 같은 순서, 활성 창 SQLQuery1. 고친 창에 `*`, 글·커서 그대로. **모든 연결이 끊긴 채**, DB 콤보는 꺼진 `chr_script_autosave.dbo` 등, 상태 줄 `chr_script_autosave.dbo` |
| 복원 뒤 New Query | `SQLQuery5.sql`(복원한 SQLQuery4 다음) |
| SQL Server 창 실행 `SELECT DB_NAME()` | 그때 연결되고 결과 `chr_script_autosave`(예전 DB). 같은 연결의 `report.sql` 창은 `master` |
| MariaDB 창 실행 `SELECT DATABASE()` | `chr_script_autosave` |
| PostgreSQL 창에서 스키마 `s2`로 바꾸고 File > Exit → 저장 질문에서 Don't Save | 앱이 끝나고 `report.sql` 파일은 그대로. 복원 파일에 창 7개(Don't Save한 글 포함, 스키마 `s2`) |
| 다시 시작(`-nl ko`) | "지난 세션의 쿼리 창 7개를 복원할까요? 아무것도 실행하지 않습니다. 창은 쿼리를 실행할 때 연결합니다." 단추 `복원`(기본)·`버리기` |
| 복원 뒤 PostgreSQL 창 실행 `SELECT current_database(), current_schema()` | `chr_script_autosave`, `s2`(예전 스키마가 창의 세션에 적용됨) |
| 한국어 Recent Scripts | 메뉴 `최근 스크립트`, 도구 설명 `이 연결에 묶은 스크립트, 최근에 고친 것부터`, 빈 목록 `이 연결에 묶은 스크립트가 없습니다` |
| 다시 시작 뒤 `버리기` | 창 0개. `query-windows.discarded.json`(이전 내용)이 남고 `query-windows.json`은 빈 목록 |
| 빈 목록으로 다시 시작 | 질문 없음 |

확인하지 못한 것: 주 문맥을 함께 쓰는 창(별도 연결 끔)에서 복원값을 적용하지 않는 경로, Modify 창·Query Library 창을 빼는 경로(코드로만 확인).

## 변경 이력

- 2026-11-08 최초 작성: 쿼리 창 자동 저장(10초 주기, 닫을 때)과 다시 시작할 때 복원(한 번 묻기, 연결하지 않고 열기, 첫 세션에 기본 DB/스키마 적용),
  SWT 감사 B-S38·A-S17 (`chromite/script-autosave`)
