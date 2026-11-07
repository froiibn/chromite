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
| A 플랫폼·설정 | 35 | 15 | 21 | 0 | 2 | 73 |
| B 편집기 | 75 | 30 | 7 | 1 | 4 | 117 |
| C 탐색기·도구 | 27 | 22 | 14 | 0 | 1 | 64 (+해당 없음 4) |
| D DB별 화면 | 93 | 22 | 8 | 0 | 1 | 124 |
| **합계** | **230** | **89** | **50** | **1** | **8** | **378** |

2026-11-08 다시 셈(devel #165, `chromite/audit-recount`): 네 영역 보고서의 모든 행을 코드와 다시 대조하고 지금 상태 칸으로 셌다("수정함"은 있음으로 셈).
바로 전 표는 있음 122, 일부 103, 없음 121, 조용히 끊김 24, 의도적 제외 8이었다(병합마다 자기 행만 고쳐 바뀐 행이 빠져 있었다).
조사 당시(#95)는 있음 89, 일부 105, 없음 148, 조용히 끊김 29, 의도적 제외 7이었다.

같은 문제가 두 영역에 함께 잡힌 것이 있다(예: 연결 끊기 때 트랜잭션 확인 = A-S3·B, 유휴 트랜잭션 = A-S1·B-S24). 그래서 합계는 항목 수의 상한이다.

## 남은 빈자리 (우선순위)

2026-11-08 다시 정리(devel #165): 처음 제안한 1~3순위(데이터·보안 안전, 연결이 안 되거나 출력이 사라짐, 매일 쓰는 작업의 빈자리)는 모두 고쳤다.
고친 내용은 영역 보고서의 상태 칸과 아래 변경 이력에 있다. 여기에는 지금 남은 것만 사용자에게 주는 가치 순으로 둔다.

### 조용히 끊김 (1건)

- **B-E12** `openEntityEditor`가 디버그 로그만 남긴다. SWT에서 저장한 데이터 전송 작업의 "끝나면 테이블 열기"가 아무 일도 하지 않는다.
  Swing 마법사는 이 옵션을 끄고 시작한다. 데이터 위험은 없다(낮음).

### 상위 13

| # | 빈자리 | 행 | 상태(영향) | 남은 것 |
|---|---|---|---|---|
| 1 | 실행 중 자동/수동 커밋 전환 명령 | B-S18 | 없음(높음) | 도구 막대·메뉴 토글은 사용자 결정 대기. 상태 줄에서 바꾸지 않는 것은 이미 결정됨(B-S18a) |
| 2 | 개체 속성 편집 | B-E1, B-E2, C-15 | 일부(중간) | 속성 창은 읽기 전용. 디자이너가 없는 DB(DB2·Generic의 인덱스·외래 키)와 시퀀스 등 테이블 밖 개체 |
| 3 | 실행 중 격리 수준 바꾸기 | B-S19, A-C5 | 없음(중간) | 연결의 기본 격리 수준은 있음(B-C11) |
| 4 | 값 편집기 나머지 | B-R14 | 일부(낮음) | 2026-11-08 참조 값 고르기·배열·구조체·hstore·SET·16진수·외부 편집기·UUID 만들기를 넣음([swing-value-editors.md](swing-value-editors.md)). 커서 보기, 일반 JDBC 구조체(Oracle 객체 형식) 편집만 남음 |
| 5 | 데이터 전송을 작업으로 저장, 작업 보기 나머지 | C-39, C-43, C-41 | 없음·일부(중간) | 작업 폴더·복사·변수, SQL 스크립트 작업 |
| 6 | 테이블 복사·내보내기 고급 옵션 | C-36, C-37 | 일부(중간·낮음) | 매핑 규칙, 열 변환기, 중복 키 처리, 매핑 저장, 외래 키 옮기기, 열 고르기 |
| 7 | 네이티브 백업 고급 | D-P18, D-M13, D-P19, C-46 | 일부·없음(중간) | 스키마·테이블 고르기, pg_dumpall, 도구 계정, 추가 인수 |
| 8 | 다른 도구에서 연결 가져오기 나머지 | A-X4 | 일부(중간) | DbVisualizer, DataGrip, pgAdmin, Navicat, SQuirreL, SQL Workbench/J |
| 9 | 설정·스크립트·프로젝트 내보내기/가져오기 | A-C14 | 일부(중간) | 지금은 연결만 옮김 |
| 10 | 연결별 보안 제한 편집, 연결 잠금 | B-C13 | 일부(중간) | 연결 유형 단위 제한은 있음 |
| 11 | 저장한 필터·필터 기록, 가상(계산) 열 | B-R10, B-R22 | 일부(중간) | WHERE 입력 줄은 사용자 요청으로 열 머리글 필터로 바꿈 |
| 12 | Query Manager 보기 | A-C6, B-S26 | 일부(중간) | 모든 실행 기록, 내부·메타데이터 쿼리, 필터 |
| 13 | 권한 세부와 Altibase 복제 | D-P10, D-M5, D-A7 | 일부·없음(중간) | PostgreSQL 열 권한·기본 권한, MySQL 자원 제한, Altibase 복제 시작·중지 |

### 그다음

- 결과: 연쇄 삭제(B-R20), 오류 때 묻기 대화상자(B-S14), 메타데이터·그룹 패널(B-R5, R8), 값에 따른 색(B-R23), 열 고정(B-R24).
- 편집기: 자동완성 세부(B-S34), 아웃라인의 문장 안 구조(B-S28), 의미 분석 표시(B-S36), 코드 편집·방언 설정(B-S45), 결과 탭 이름 바꾸기·분리(B-S3).
- 관리: 다른 DB 세션 관리자·잠금 그래프(B-O1, O2), Exasol 잠금, DB2·Exasol 도구(C-44), Oracle 개체 권한 편집(B-O3), Oracle 패키지·열 권한(D-O16 비고), 유지 관리 도구 옵션 화면(D-P23·O21 비고), PostgreSQL 설정 페이지(D-P7).
- 탐색기: 객체를 쿼리 창에 끌어 놓기(C-13), 자원 탐색기 나머지(새 파일, 파일·폴더 링크, 파일 시스템 탐색기, C-22), 연결된 것만·종류 거르기(C-5), 선택 객체로 필터 추가(C-4), 프로젝트(C-21), 북마크(C-20).
- 플랫폼: SWT 전역 설정 넘기기(A-P1), 알림 소리(A-U6), 확인의 "다시 묻지 않음"(A-C27), 커밋 알림 설정(A §6 Transactions), 작업 공간 전환(A-S3).
- AI: 편집기 안 AI 번역(C-57), `@ai` 확인 규칙 설정(C-58), 이미지 첨부(C-56).
- 나머지 낮음 항목은 각 영역 보고서 표에 있다.

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
- 2026-11-08 4순위 루틴 묶음: B-S42, D-O11·O12·O13·O14·O20·A5·A6 → 있음([swing-routines.md](swing-routines.md)). 결과 요약 수는 devel 병합 뒤 다시 센다.
- 2026-11-08 4순위 차트·공간 데이터: B-R9·C-64 없음 → 일부(결과 공간 보기), 결과 표 차트 보기는 CE에 없던 기능이라 수 변화 없음
  ([swing-result-charts-spatial.md](swing-result-charts-spatial.md)). 결과 요약 B·C·합계 갱신.
- 2026-11-08 설정 화면 나머지 1차: 연결별 설정, Data Formats·Errors and Timeouts·Metadata·Client Identification([swing-options.md](swing-options.md)).
  A-platform §6의 해당 줄과 §7 집계 문단 갱신.
- 2026-11-08 4순위 결과 편집([swing-grid-editing.md](swing-grid-editing.md)): B-R14·R16·R20·R21·O6·O7, D-P28·M18 상태 갱신. 결과 요약은 메인 세션이 다시 셈.
- 2026-11-08 C-49 없음 → 일부: Schema Compare(두 스키마의 구조 비교와 마이그레이션 스크립트, [schema-compare.md](schema-compare.md)). 결과 요약 C·합계 갱신.
- 2026-11-08 devel 병합 때 영역 C 집계를 행 상태에서 다시 셈: C-29(데이터 검색, 2026-11-08 옮김)가 행은 있음인데 집계에는 없음으로 남아 있었다. 결과 요약 C·합계 갱신.
- 2026-11-08 후속 #55·#62·#69(`chromite/drivers-oracle-batch`): B-C18·C19 일부 → 있음(결과 요약 B·합계 갱신), A-platform §6 Classpath 없음 → 있음·Maven 비고,
  D-O9·O10 비고(Oracle Cancel Query, RAC). [swing-driver-manager.md](swing-driver-manager.md), [drivers.md](drivers.md) "로컬 파일 우선", [swing-admin-tools.md](swing-admin-tools.md) "Oracle RAC와 쿼리 취소". devel 병합 때 결과 요약 B를 영역 보고서의 지금 행 상태로 다시 세고("수정함"은 있음) 합계를 다시 냄.
- 2026-11-08 4순위 SQL 터미널: B-S43 없음 → 있음(Query > SQL Terminal, [swing-sql-terminal.md](swing-sql-terminal.md)), A-platform §6 SQL Terminal 설정 줄 갱신. 결과 요약은 메인 세션이 다시 셈.
- 2026-11-08 4순위 스크립트 탐색기: C-22·A-C13 없음 → 일부(Scripts 도구 창, 스크립트와 연결 묶기, [swing-scripts-explorer.md](swing-scripts-explorer.md)), B-S38 근거 갱신(일부 그대로). 결과 요약은 메인 세션이 다시 셈.
- 2026-11-08 설정 화면 나머지 2차(`chromite/settings-rest-2`): 남은 키 47개 화면, 효과 없는 키 6개 재판정([swing-options.md](swing-options.md) "남은 키 (2026-11-08 오후)").
  A-platform §6의 해당 줄과 S12 근거 갱신(집계 수는 고치지 않음).
- 2026-11-08 4순위 쿼리 편집기 편의(B6): B-S31·S35·S37 없음 → 있음, B-S28 없음 → 일부, B-S44 일부 → 있음, B-S3·S33·S34 비고 갱신
  ([swing-sql-editor-ergonomics.md](swing-sql-editor-ergonomics.md)). 결과 요약 수는 병합 때 다시 셈.
- 2026-11-08 4순위 연결 칸(`chromite/p4-connection-fields`, [swing-conn-advanced.md](swing-conn-advanced.md)): 공급자 속성 선언, ClickHouse·Databend·TiDB Lake SSL, BigQuery·Snowflake·Spanner·HANA·Firebird·Mimer 칸, 연결 설명.
  D-P3·S4·S5·O7과 번들 줄 9개 갱신, B-C2·C4·C8 비고 갱신. 결과 요약 수는 고치지 않음(공통 규칙: 메인 세션이 다시 셈).
- 2026-11-08 4순위 B2 연결 수명 주기([connection-lifecycle.md](connection-lifecycle.md)): A-S8·S10·S11(조용히 끊김) 수정, A-C4·A-C7·A-C8·A-C15·A-U3 → 있음, A-S2 → 일부,
  B-S16 → 있음, B-C15 → 일부, D-K4·K5 수정. 닫기 경로 전수 점검(절전 닫기·깨어남 다시 초기화의 무확인 롤백을 고침).
- 2026-11-08 4순위 연결 도구(`chromite/p4-connection-tools`, [swing-conn-advanced.md](swing-conn-advanced.md), [platform-startup.md](platform-startup.md)): A-S9·S12·S13 수정함, A §6 Network Profiles·General 비고, B-C6·C9·C12·C21, C-51·52 행 갱신. 결과 요약 수는 고치지 않음(메인 세션이 다시 셈).
- 2026-11-08 집계 다시 셈(`chromite/audit-recount`): 네 영역 보고서의 모든 행을 devel #165 코드와 대조해 낡은 상태를 고치고(A 12줄(집계 대상 3줄), B 8줄, C 3줄, D 7줄), 결과 요약을 행 상태로 다시 셈. 우선순위를 남은 빈자리만 사용자 가치 순으로 다시 씀(고친 1~3순위 표는 지움, 기록은 영역 보고서와 이 변경 이력).
- 2026-11-08 5순위 스크립트 자동 저장(`chromite/script-autosave`, [swing-query-window-recovery.md](swing-query-window-recovery.md)): B-S38 일부 → 있음, A-S17 없음 → 있음,
  C-22 근거 갱신(연결별 최근 스크립트, 일부 그대로). #166 집계에 두 행의 변화만 더함(A 있음 +1·없음 −1, B 있음 +1·일부 −1, 합계 같이).
  남은 빈자리 상위 목록의 2번(B-S38·A-S17·C-22)을 빼고 번호를 당김, C-22 나머지는 "그다음 > 탐색기"로.
- 2026-11-08 5순위 결과에서 SQL 만들기(`chromite/sql-from-results`, [swing-result-generate-sql.md](swing-result-generate-sql.md)): B-S41 일부 → 있음.
  집계에 한 행의 변화만 더함(B 있음 +1·일부 −1, 합계 같이). 남은 빈자리 상위 목록의 4번(B-S41)을 빼고 번호를 당김.
- 2026-11-08 5순위 값 편집기 나머지(`chromite/value-editors-rest`, [swing-value-editors.md](swing-value-editors.md)): B-R14 근거 갱신(일부 그대로), D-P28 일부 → 있음,
  D-M18 비고, A-platform §6 Dictionaries 줄 일부(§6 줄 단위 집계만, A 영역 수는 그대로). 집계에 한 행의 변화만 더함(D 있음 +1·일부 −1, 합계 같이).
  상위 목록 4번의 남은 것을 고침(커서 보기·Oracle 객체 형식).
