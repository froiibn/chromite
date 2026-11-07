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
| B 편집기 | 25 | 42 | 39 | 8 | 3 | 117 |
| C 탐색기·도구 | 13 | 20 | 28 | 2 | 1 | 64 (+해당 없음 4) |
| D DB별 화면 | 42 | 31 | 45 | 5 | 1 | 124 |
| **합계** | **91** | **106** | **145** | **29** | **7** | **378** |

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
- 쿼리 창마다 별도 연결(지금은 한 연결의 창들이 트랜잭션을 공유).
- 탐색기에서 개체 삭제·이름 바꾸기, 이름 복사, 여러 개 선택, Generate SQL의 INSERT/UPDATE/DELETE 등.
- 설정 화면: 트랜잭션, 연결 유형, 편집기·결과 기본값(설정 페이지 62개 중 45개는 Swing에서 바꿀 화면이 없음).
- Oracle 사용자·역할·권한 관리, Oracle NLS 세션 설정, Altibase 잠금 탭, TRUNCATE·트리거 켜기/끄기·구체화된 뷰 새로 고침.
- ~~`@export` 명령이 무시됨(오류 없이 다음 쿼리 실행).~~ 2026-11-07 고침: 형식·옵션을 채운 Export Data 마법사로 내보냄([swing-data-transfer.md](swing-data-transfer.md) "@export 명령").

### 4순위

나머지 없음·일부 항목. 각 보고서 표에 있다. 예: 스키마 비교, 데이터 검색, 작업 저장·재실행, 차트, 프로젝트·스크립트 탐색기.

## 원래 SWT 제품에 없던 것 (해당 없음)

디버거, Git, Office("Excel에서 열기"), 개발 도구 번들은 빌드는 됐지만 SWT 제품 피처에 들어 있지 않았다([C 보고서](swt-parity/C-navigator-tools.md) 참고).
SWT 사용자가 잃은 기능은 아니다. 그래도 옮길지는 따로 정한다.
PostgreSQL 디버거는 2026-11-07 사용자 요청으로 Swing에 옮겼다(C-62, D-P29 → 있음, [procedure-debugger.md](procedure-debugger.md)).

## 앞으로

- 우선순위는 사용자가 정한다. 항목을 옮기면 해당 보고서 표의 상태와 이 문서의 표를 같은 커밋에서 고친다.
- 새 Swing 기능을 만들 때는 남은 모델 코드의 `DBWorkbench.getService(...)`와 `DBWorkbench.getPlatformUI()` 호출을 먼저 검색해, 연결 지점이 필요한지 확인한다.

## 변경 이력

- 2026-11-07 최초 작성. 영역 A~D 조사(읽기 전용, 코드 대조), 근본 원인, 우선순위 제안.
- 2026-11-07 2순위 A-S6(Windows 인증서 저장소), A-S14(macOS 끝내기·정보·환경설정) 고침([platform-startup.md](platform-startup.md)).
- 2026-11-07 1순위 남은 두 줄 고침: A-S2 유휴 연결 닫기, A-S7 모델 알림([idle-transactions.md](idle-transactions.md)). #97로 고친 A-S1·A-S3·B-S23·B-S24의 상태도 영역 보고서에 반영.
- 2026-11-07 A-C14 없음 → 일부: 연결 내보내기·가져오기(비밀번호는 암호로 암호화한 파일)([connection-transfer.md](connection-transfer.md)).
- 2026-11-07 C-62·D-P29 없음 → 있음: PostgreSQL 프로시저 디버거(모델 복원, Swing 디버거 탭)([procedure-debugger.md](procedure-debugger.md)).
  결과 요약의 C·D·합계 수를 고침.
- 2026-11-07 3순위 1차: 트랜잭션 줄(커밋/롤백 명령과 도구 단추, 트랜잭션 로그, 열린 트랜잭션 목록, [transactions.md](transactions.md))과 `@export` 줄([swing-data-transfer.md](swing-data-transfer.md) "@export 명령") 고침. 영역 A-C1·A-C2·A-C3·A-C4, B-S12·B-S17·B-S20·B-S21 상태 갱신.
