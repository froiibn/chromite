# SWT → Swing 미이관 기능 전수 조사

## 목적

SWT 프런트엔드를 지울 때(#68) 기능 전체 목록과 맞춰 보지 않았다. 그 사이에 사라지거나 동작이 멈춘 기능을 빠짐없이 찾아,
옮길 순서를 정하기 위한 조사다. 사용자 요청(2026-11-07): "전수 조사해서 누락된 기능에 대해 미이관된 항목을 확인하는게 필요하겠다".

## 왜 누락됐나 (근본 원인)

1. **SWT를 먼저 지웠다.** 사용자 결정에 따라 "기능이 모두 옮겨지기를 기다리지 않고" 지웠다([swt-removal.md](swt-removal.md) "목적").
   남길 번들은 의존 관계로만 골랐다. 그 문서의 "없는 기능" 표는 큰 화면 단위 17줄뿐이다.
2. **Swing은 화면 목록을 기준으로 만들었다.** [swing-frontend.md](swing-frontend.md) "MVP 범위"가 기준이었다. 보이는 화면은 하나씩 옮겼다.
3. **화면 없이 UI 계층에 기대던 동작이 소리 없이 멈췄다.** 모델 코드는 남았지만 SWT 앱이 제공하던 연결 지점이 함께 없어졌다.
   - 연결 지점: `UIService*` 구현 등록, `DBPPlatformUI` 메서드, `DBPApplication` 재정의, 시작 때 초기화, 모델 알림 처리기.
   - 남은 코드는 대부분 `if (service != null)` 형태라 오류도 경고도 없이 건너뛴다. 그 결과 아무 일도 안 하거나, 더 나쁘게는 확인 질문을 건너뛴다.
   - 예: 유휴 트랜잭션 자동 정리. 활동 시각과 확인 창이 없어서 한 번도 동작하지 않았다.

이 조사에서 이런 경우를 **조용히 끊김**으로 따로 분류했다. 화면만 봐서는 드러나지 않아 가장 위험하다.

## 방법

- **기준점:** SWT는 삭제 직전 커밋 `263b8ddb45`, Swing은 devel `8e8aaaef60`.
- **영역:** 넷으로 나눠 코드와 `plugin.xml`, 문서를 직접 대조했다.
- **분류:** 항목마다 다섯 가지 중 하나로 나누고 근거(파일:줄, 검색 결과)와 사용자 영향(높음·중간·낮음)을 붙였다.
  - 있음
  - 일부
  - 없음
  - 의도적 제외: 문서에 결정 기록이 있는 것
  - 조용히 끊김
- **한계:** 빌드와 실행 없이 코드만 읽고 판단했다. 실행으로 확인이 필요한 항목은 각 보고서에 적어 두었다.

| 영역 | 보고서 | 대상 |
|---|---|---|
| A | [swt-parity/A-platform.md](swt-parity/A-platform.md) | `core`, `ui`, 앱 번들. 서비스 등록, 시작 초기화, 설정 페이지 62개(설정 키 단위) |
| B | [swt-parity/B-editors.md](swt-parity/B-editors.md) | SQL 편집기, 결과 뷰어, 개체 편집기, 연결 편집기, 세션·잠금, ERD, JSON/XML/HEX/이미지 |
| C | [swt-parity/C-navigator-tools.md](swt-parity/C-navigator-tools.md) | 탐색기, 검색, 대시보드, 차트, 데이터 전송, 작업, 스키마 비교, SSH, AI, 디버거, Git |
| D | [swt-parity/D-db-specific.md](swt-parity/D-db-specific.md) | `ext.*.ui` 32개. PostgreSQL, SQL Server, MySQL/MariaDB, Oracle, Altibase는 자세히 |

## 결과 요약

| 영역 | 있음 | 일부 | 없음 | 조용히 끊김 | 의도적 제외 | 계 |
|---|---|---|---|---|---|---|
| A 플랫폼·설정 | 11 | 13 | 33 | 14 | 2 | 73 |
| B 편집기 | 38 | 41 | 31 | 3 | 4 | 117 |
| C 탐색기·도구 | 17 | 20 | 24 | 2 | 1 | 64 (+해당 없음 4) |
| D DB별 화면 | 49 | 31 | 38 | 5 | 1 | 124 |
| **합계** | **115** | **105** | **126** | **24** | **8** | **378** |

같은 문제가 두 영역에 함께 잡힌 것이 있다(예: 연결 끊기 때 트랜잭션 확인 = A-S3·B, 유휴 트랜잭션 = A-S1·B-S24). 그래서 합계는 항목 수의 상한이다.

## 우선순위 (제안)

### 1순위: 데이터·보안 안전 (조용히 끊김 중 위험한 것)

| 항목 | 영역 | 상태 |
|---|---|---|
| 연결 끊기·다시 연결·삭제 때 열린 트랜잭션을 묻지 않고 롤백 | A-S3, B-S23 | 수정함(#97, [idle-transactions.md](idle-transactions.md)) |
| 유휴 트랜잭션 처리, 유휴 연결 끊기 설정이 동작하지 않음 | A-S1·S2, B-S24 | 유휴 트랜잭션은 수정함(#97). 유휴 연결 닫기도 수정함(`chromite/idle-close-notifications`, [idle-transactions.md](idle-transactions.md) "유휴 연결 닫기"): 연결별 마지막 사용 기준, 열린 트랜잭션이 있으면 닫지 않음. PostgreSQL·SQL Server에서 실행 확인 |
| 셸 명령(연결 이벤트, 셸 명령 인증, 내보내기 후 명령)이 승인 없이 실행됨 | A-S4 | 보안. 수정함(#99, [shell-command-approval.md](shell-command-approval.md)) |
| 테스트·운영 유형의 "데이터 변경 확인"이 결과 표 저장에서 무시됨(스크립트 미리 보기 없음) | B-R19 | 수정함(#98, [swing-frontend.md](swing-frontend.md) "저장 전 스크립트 미리 보기") |
| 조건 없는 DELETE/UPDATE, DROP 실행 전 확인 없음 | B-S7 | 고침(`chromite/sql-exec-safety`, [swing-sql-exec-safety.md](swing-sql-exec-safety.md)) |
| `@ai`가 만든 SQL을 실행 전 확인 창에 SQL 없이 묻음 | C-58 | 고침(같은 브랜치). AI 확인 규칙 설정 화면은 없음 |
| 연결의 "스크립트 실행 금지" 제한이 지켜지지 않음 | B-S8 | 고침(같은 브랜치) |
| 모델 알림(유휴 롤백, 유휴 끊김, 절전 뒤 재검증)이 화면에 안 나옴 | A-S7 | 수정함. 유휴 트랜잭션 알림은 자체 알림 창(#97), 나머지 모델 알림(유휴 연결 닫힘, 다시 연결 성공·실패)은 `SwingModelNotifications`로 메인 창 알림([idle-transactions.md](idle-transactions.md) "모델 알림"). 절전 뒤 재검증은 업스트림부터 알림이 없음 |

### 2순위: 연결이 안 되거나 출력이 사라짐

| 항목 | 영역 | 메모 |
|---|---|---|
| 비밀번호를 저장하지 않은 SQL Server·Oracle 연결이 묻지 않고 취소됨 | A-S5, B | 실행으로 확인한 뒤 고침(#106, [swing-auth-prompts.md](swing-auth-prompts.md)) |
| Windows 인증서 저장소를 쓰지 않음(사내 CA 서버 SSL 실패 가능) | A-S6 | 고침(#104, [platform-startup.md](platform-startup.md)). Windows에서 실행 확인 |
| Oracle DBMS_OUTPUT, Altibase 서버 출력이 안 보이고 버퍼만 쌓임 | D | 고침(#105, [swing-server-output.md](swing-server-output.md)): 쿼리 창이 문장마다 읽어 Messages 탭에 씀 |
| macOS Cmd+Q가 저장·트랜잭션 확인을 건너뛸 수 있음 | A-S14 | 고침(#104). macOS 실행 확인 안 함 |
| JSch SSH: 새 호스트 키 거부, OTP 서버 로그인 불가 | C | 고침(#106, [swing-auth-prompts.md](swing-auth-prompts.md)). 기본 SSHJ는 바꾸지 않음 |

### 3순위: 매일 쓰는 작업의 빈자리

- ~~트랜잭션: 커밋/롤백 명령과 도구 단추, 트랜잭션 로그, 열린 트랜잭션 목록. 상태 줄 표시는 유휴 트랜잭션 작업에 포함.~~
  2026-11-07 고침([transactions.md](transactions.md)). 남은 것: 로그의 지난 트랜잭션 보이기, 커밋 뒤 알림 설정.
- ~~쿼리 창마다 별도 연결(지금은 한 연결의 창들이 트랜잭션을 공유).~~
  2026-11-07 고침([transactions.md](transactions.md) "쿼리 창마다 별도 연결"): 창마다 격리된 세션(SWT와 같은 키·기본값), 창 닫기·연결 바꾸기 전 트랜잭션 질문,
  Options > Query Execution과 연결 대화상자의 설정, 상태 줄 도구 설명의 세션 수.
- 탐색기에서 개체 삭제·이름 바꾸기, 이름 복사, 여러 개 선택, Generate SQL의 INSERT/UPDATE/DELETE 등. 고침(`chromite/p3-navigator-actions`,
  [swing-explorer-object-actions.md](swing-explorer-object-actions.md)): C-10·C-11·C-17 있음, C-12 일부(Copy Special·붙여넣기 복제는 남음).
  PostgreSQL·SQL Server에서 실행 확인.
- 설정 화면: 트랜잭션, 연결 유형, 편집기·결과 기본값(설정 페이지 62개 중 45개는 Swing에서 바꿀 화면이 없음).
  2026-11-07 일부 고침([swing-options.md](swing-options.md)): 키 306개를 Swing에서 읽는지 전수 확인(효과 없음 197개는 화면을 만들지 않음).
  Options에 Query Execution(구분 기호·매개 변수·오류 처리)·Connection Types(연결 유형 편집)·Oracle 탭과 결과 형식 행, 연결 대화상자에 격리 수준을 더함.
  오류로 멈춘 스크립트가 수동 커밋 세션의 열린 트랜잭션을 묻지 않고 롤백하던 것을 고침. 남은 것: 읽지만 화면 없는 키 71개(연결별 값, 형식 프로필, 시간 제한, 메타데이터 등).
  2026-11-08 더 고침(`chromite/settings-rest`): 연결별 설정(연결 대화상자 Options > Settings), Data Formats·Errors and Timeouts·Metadata·Client Identification.
  남은 것: 읽지만 화면 없는 키 55개(결과 편집기 나머지, 연결 일반, 드라이버·프록시·Maven, QM·로그, 탐색기 정렬, 알림, 데이터 전송 매핑, 자동완성, DB별).
  2026-11-08 전역 라이브러리(`ui.drivers.global.libraries`)는 Driver Manager에 만들어 54개(`chromite/drivers-oracle-batch`).
  2026-11-08 오후 고침(`chromite/settings-rest-2`): 남은 54개 중 47개를 Options(Connections, Drivers, Logs, Object Explorer, Notifications, Data Transfer,
  Code Completion, Binary and LOB, Altibase, CUBRID, Mimer SQL, Query Results 읽기와 정렬)에 넣음. 효과 없음 6개, 규칙상 두지 않음 1개(Altibase 실행 계획 방식).
  남은 것 없음.
- ~~Oracle 사용자·역할·권한 관리, Oracle NLS 세션 설정, Altibase 잠금 탭, TRUNCATE·트리거 켜기/끄기·구체화된 뷰 새로 고침.~~
  2026-11-08 고침(`chromite/p3-oracle-altibase-admin`): [swing-security.md](swing-security.md) "Oracle", [swing-conn-advanced.md](swing-conn-advanced.md) "Oracle 세션 설정",
  [swing-admin-tools.md](swing-admin-tools.md) "Altibase 잠금", [swing-explorer-object-actions.md](swing-explorer-object-actions.md) "유지 관리 명령".
  PostgreSQL·SQL Server·Oracle·MariaDB·Altibase에서 실행 확인. 남은 것: Altibase TRUNCATE·MView 새로 고침(A5), 도구 옵션 화면, Oracle 패키지·열 권한.
- ~~`@export` 명령이 무시됨(오류 없이 다음 쿼리 실행).~~ 2026-11-07 고침: 형식·옵션을 채운 Export Data 마법사로 내보냄([swing-data-transfer.md](swing-data-transfer.md) "@export 명령").

### 4순위

나머지 없음·일부 항목. 각 보고서 표에 있다. 예: 스키마 비교, 작업 저장·재실행, 차트, 프로젝트·스크립트 탐색기.
데이터 검색은 2026-11-08 옮겼다([swing-data-search.md](swing-data-search.md), C-29).

## 원래 SWT 제품에 없던 것 (해당 없음)

디버거, Git, Office("Excel에서 열기"), 개발 도구 번들은 빌드는 됐지만 SWT 제품 피처에 들어 있지 않았다([C 보고서](swt-parity/C-navigator-tools.md) 참고).
SWT 사용자가 잃은 기능은 아니다. 그래도 옮길지는 따로 정한다.
PostgreSQL 디버거는 2026-11-07 사용자 요청으로 Swing에 옮겼다(C-62, D-P29 → 있음, [procedure-debugger.md](procedure-debugger.md)).
같은 날 SWT에 없던 Oracle PL/SQL 디버거(`DBMS_DEBUG`)를 더했다(C-62 비고).

## 앞으로

- 우선순위는 사용자가 정한다. 항목을 옮기면 해당 보고서 표의 상태와 이 문서의 표를 같은 커밋에서 고친다.
- 새 Swing 기능을 만들 때는 남은 모델 코드의 `DBWorkbench.getService(...)`와 `DBWorkbench.getPlatformUI()` 호출을 먼저 검색해, 연결 지점이 필요한지 확인한다.

## 변경 이력

- 2026-11-07 최초 작성. 영역 A~D 조사(읽기 전용, 코드 대조), 근본 원인, 우선순위 제안.
- 2026-11-07 2순위 A-S6(Windows 인증서 저장소), A-S14(macOS 끝내기·정보·환경설정) 고침([platform-startup.md](platform-startup.md)).
- 2026-11-07 1순위 남은 두 줄 고침: A-S2 유휴 연결 닫기, A-S7 모델 알림([idle-transactions.md](idle-transactions.md)). #97로 고친 A-S1·A-S3·B-S23·B-S24의 상태도 영역 보고서에 반영.
- 2026-11-07 A-C14 없음 → 일부: 연결 내보내기·가져오기(비밀번호는 암호로 암호화한 파일)([connection-transfer.md](connection-transfer.md)).
- 2026-11-07 C-62·D-P29 없음 → 있음: PostgreSQL 프로시저 디버거(모델 복원, Swing 디버거 탭)([procedure-debugger.md](procedure-debugger.md)).
- 2026-11-07 3순위 탐색기 줄: C-10·C-11·C-17 일부 → 있음, C-12 없음 → 일부(개체 삭제·이름 바꾸기, 이름 복사, 여러 개 선택, SQL 생성)
  ([swing-explorer-object-actions.md](swing-explorer-object-actions.md)). 결과 요약의 C 줄과 합계를 고침.
  결과 요약의 C·D·합계 수를 고침.
- 2026-11-07 Oracle 프로시저 디버거 추가(SWT에 없던 기능, 수 변화 없음, C-62 비고)([procedure-debugger.md](procedure-debugger.md)).
- 2026-11-07 3순위 1차: 트랜잭션 줄(커밋/롤백 명령과 도구 단추, 트랜잭션 로그, 열린 트랜잭션 목록, [transactions.md](transactions.md))과 `@export` 줄([swing-data-transfer.md](swing-data-transfer.md) "@export 명령") 고침. 영역 A-C1·A-C2·A-C3·A-C4, B-S12·B-S17·B-S20·B-S21 상태 갱신.
- 2026-11-07 3순위 설정 화면 일부: SWT 설정 키의 Swing 효과 전수, Query Execution·Connection Types·Oracle 탭, 결과 형식, 격리 수준, 오류 뒤 무확인 롤백 고침
  ([swing-options.md](swing-options.md)). A-platform §6, B-S14·B-S15 상태 갱신.
- 2026-11-08 3순위 Oracle·Altibase 관리 줄 고침: D-P23·S14·M17·O5·O16·O21·A2 → 있음(결과 요약 D·합계 갱신), C #44·B O2 비고 갱신.
- 2026-11-07 3순위 쿼리 창마다 별도 연결 고침([transactions.md](transactions.md) "쿼리 창마다 별도 연결"). B-S22, A-platform §6 SQL Editor 줄 상태 갱신.
- 2026-11-08 4순위 차트·공간 데이터: B-R9·C-64 없음 → 일부(결과 공간 보기), 결과 표 차트 보기는 CE에 없던 기능이라 수 변화 없음
  ([swing-result-charts-spatial.md](swing-result-charts-spatial.md)). 결과 요약 B·C·합계 갱신.
- 2026-11-08 설정 화면 나머지 1차: 연결별 설정, Data Formats·Errors and Timeouts·Metadata·Client Identification([swing-options.md](swing-options.md)).
  A-platform §6의 해당 줄과 §7 집계 문단 갱신.
- 2026-11-08 C-49 없음 → 일부: Schema Compare(두 스키마의 구조 비교와 마이그레이션 스크립트, [schema-compare.md](schema-compare.md)). 결과 요약 C·합계 갱신.
- 2026-11-08 devel 병합 때 영역 C 집계를 행 상태에서 다시 셈: C-29(데이터 검색, 2026-11-08 옮김)가 행은 있음인데 집계에는 없음으로 남아 있었다. 결과 요약 C·합계 갱신.
- 2026-11-08 후속 #55·#62·#69(`chromite/drivers-oracle-batch`): B-C18·C19 일부 → 있음(결과 요약 B·합계 갱신), A-platform §6 Classpath 없음 → 있음·Maven 비고,
  D-O9·O10 비고(Oracle Cancel Query, RAC). [swing-driver-manager.md](swing-driver-manager.md), [drivers.md](drivers.md) "로컬 파일 우선", [swing-admin-tools.md](swing-admin-tools.md) "Oracle RAC와 쿼리 취소". devel 병합 때 결과 요약 B를 영역 보고서의 지금 행 상태로 다시 세고("수정함"은 있음) 합계를 다시 냄.
- 2026-11-08 4순위 SQL 터미널: B-S43 없음 → 있음(Query > SQL Terminal, [swing-sql-terminal.md](swing-sql-terminal.md)), A-platform §6 SQL Terminal 설정 줄 갱신. 결과 요약은 메인 세션이 다시 셈.
- 2026-11-08 4순위 스크립트 탐색기: C-22·A-C13 없음 → 일부(Scripts 도구 창, 스크립트와 연결 묶기, [swing-scripts-explorer.md](swing-scripts-explorer.md)), B-S38 근거 갱신(일부 그대로). 결과 요약은 메인 세션이 다시 셈.
- 2026-11-08 설정 화면 나머지 2차(`chromite/settings-rest-2`): 남은 키 47개 화면, 효과 없는 키 6개 재판정([swing-options.md](swing-options.md) "남은 키 (2026-11-08 오후)").
  A-platform §6의 해당 줄과 S12 근거 갱신(집계 수는 고치지 않음).
