# Chromite 설계 문서

Chromite는 DBeaver Community를 포크해 Apache License 2.0에 따라 재구성한 데이터베이스 워크벤치다.
이 폴더에는 upstream(DBeaver)과 달라진 부분의 설계와 변경 이력을 기능 단위로 정리한다.
upstream의 전체 구조는 [AGENTS.md](../../AGENTS.md)와 [AGENTS-Architecture.md](../../AGENTS-Architecture.md)를 본다.

## 작성 규칙

- 기능이나 모듈 하나에 문서 하나를 둔다.
- 각 문서에는 목적, 구조, 설계 결정과 이유, upstream 대비 변경점, 변경 이력을 적는다.
- 코드를 바꾸면 같은 커밋에서 관련 문서도 갱신한다.
- 작업 규칙 전체는 [CLAUDE.md](../../CLAUDE.md)의 "작업 원칙"을 따른다.

## 문서 목록

| 문서 | 내용 |
|---|---|
| [product-direction.md](product-direction.md) | 제품 정의, 주요 결정 사항, 진행 단계, 미결정 사항 (먼저 읽을 문서) |
| [branding.md](branding.md) | 제품명, 아이콘, 스플래시, About 화면, 라이선스 고지 등 Chromite 브랜딩 |
| [commercial-upsell.md](commercial-upsell.md) | DBeaver 상용판 홍보 기능과 문구 제거, 다른 판을 가리키는 문구의 일반화 |
| [cdata-removal.md](cdata-removal.md) | CData 연동 제거 이유, 제거한 항목, 기존 CData 연결의 동작 |
| [telemetry.md](telemetry.md) | 사용 통계(텔레메트리) 제거, DBeaver 소유 서버로 나가는 요청 현황 |
| [ai.md](ai.md) | AI 제공자별 모델 목록과 모델 정보의 출처, dbeaver.com 모델 카탈로그 제거, 구독·API 키 연결 계획 |
| [ai-agent.md](ai-agent.md) | Chromite Agent(에이전트 기능의 공식 이름): DBA 도구 카탈로그, 연결·도구별 권한 정책과 감사 기록, 로컬 MCP 서버, Anthropic·OpenAI·Ollama 엔진, 시나리오 플레이북(1단계), Chromite Agent 채팅 창·확인 대화상자·설정·기준값 편집(2단계) |
| [swing-frontend.md](swing-frontend.md) | Swing 프런트엔드 구조, 재사용 분석, MVP 범위, 쿼리 실행(F5) 사양, 쿼리 실행 계층(SWT 없는 실행 API), A–G단계 구현 현황(D단계: 범용 연결 화면, 연결 명령, G단계: 결과 편집·저장) |
| [swing-registered-servers.md](swing-registered-servers.md) | Swing Registered Servers 창(서버 그룹·서버 트리, 연결 폴더 재사용), 연 서버만 보이는 Object Explorer |
| [swing-admin-tools.md](swing-admin-tools.md) | Swing 관리 도구: Activity Monitor(세션 목록·끝내기, 잠금 대기, 공간 사용량, 서버 로그, 유지 관리, 복제 상태), Find Objects(이름으로 객체 찾기), 서버 대시보드를 미룬 이유 |
| [swing-data-search.md](swing-data-search.md) | Swing 데이터 검색(Search Data…): 스키마나 고른 테이블의 행에서 값 찾기, 매개 변수 쿼리, 테이블당 행 수 제한·취소, 자기 자동 커밋 컨텍스트, 열별 결과와 쿼리 창에서 행 열기 |
| [swing-dashboard.md](swing-dashboard.md) | Swing 서버 대시보드(Activity Monitor 개요 탭): 연결별 실시간 차트(세션, 초당 트랜잭션·요청, CPU, I/O, 잠금·대기, 캐시 적중률), 갱신 로직의 모델 번들 이전, Java2D 차트를 고른 이유 |
| [swing-data-transfer.md](swing-data-transfer.md) | Swing 데이터 가져오기·내보내기 마법사(Export Data: 테이블·뷰·결과 표 → 모든 스트림 형식, Import Data: CSV → 기존 테이블, Copy Tables: 다른 연결·DB로 테이블 복사), 모델 실행 경로와 SWT 대비 변경점 |
| [swing-backup-restore.md](swing-backup-restore.md) | Swing 데이터베이스 백업·복원(Tasks ▸ Back Up… / Restore…): SQL Server T-SQL 대화상자(진행 메시지, 복원 계획, 파일 위치), PostgreSQL·MySQL 로컬 클라이언트 도구 |
| [swing-result-viewer.md](swing-result-viewer.md) | Swing 결과 뷰어: 값 패널(JSON·XML·이미지·16진수), 정렬·필터, 다음 페이지, 레코드 보기, 복사 형식, 선택 집계 |
| [swing-result-charts-spatial.md](swing-result-charts-spatial.md) | Swing 결과 차트·공간 보기: 선·막대·원형 차트(축·값 열·행 고르기, PNG 저장, Java2D), 기하 값 그리기(DBGeometry, SQL Server geometry/geography 바이트 해석, 밑그림 없음) |
| [swing-sql-exec-safety.md](swing-sql-exec-safety.md) | Swing 쿼리 실행 안전 확인: WHERE 없는 UPDATE/DELETE·DROP 확인(한 번에 목록, `GO` 배치 재분할), 연결의 스크립트 실행 제한, `@ai`가 만든 SQL을 보여 주는 실행 확인(`UIServiceSQL`) |
| [swing-server-output.md](swing-server-output.md) | Swing 쿼리 창의 서버 출력: Oracle·CUBRID `DBMS_OUTPUT`, Altibase `PRINT`를 문장마다 읽어(버퍼를 비움) Messages 탭에 씀, PRINT·NOTICE 중복 방지, Altibase 기본값 초기화 |
| [swing-sql-editor-plus.md](swing-sql-editor-plus.md) | Swing SQL 편집기 확장: 실행 계획(예상·실제, SQL Server 계획기, 그래픽·트리·원본 세 보기), 쿼리 기록, SQL 서식·편집 명령, 템플릿, 매개변수 대화상자 |
| [idle-transactions.md](idle-transactions.md) | 열린 트랜잭션 표시(쿼리 창 상태 줄, 커밋·롤백 메뉴)와 유휴 트랜잭션 처리(마지막 문장 기준 유휴, 알림·열어 두기, 연결에서 켰을 때만 자동 롤백), 업스트림 유휴 롤백 경로 교체, 연결 끊기 전 열린 트랜잭션 질문, 유휴 연결 닫기(연결별 마지막 사용 기준, 열린 트랜잭션이면 닫지 않음), 모델 알림 표시 |
| [transactions.md](transactions.md) | 트랜잭션 명령과 화면: Query 메뉴·SQL Editor 도구 막대·단축키(SWT와 같음)의 Commit / Roll Back(상태 줄과 같은 커밋 경로), 트랜잭션 로그(현재 트랜잭션의 문장), Tools > Open Transactions…(모든 연결의 열린 트랜잭션, 거기서 커밋·롤백), 쿼리 창마다 별도 연결(창마다 세션, 닫기·연결 바꾸기 전 트랜잭션 질문, 설정) |
| [swing-grid-column-filters.md](swing-grid-column-filters.md) | Swing 결과 그리드 열 필터: 머리글 필터·정렬 아이콘, 열 필터 팝업(서버 값 목록·조건, SQL 주입 방지), 필터 줄 삭제, SQL Server 열 필터 수정 || [swing-conn-advanced.md](swing-conn-advanced.md) | Swing Connect 대화상자의 고급 연결 설정: 인증 방식별 칸, SSH·프록시·SSL 탭, SQL Server·Oracle 주 칸, 연결 색, 연결 직후 SQL |
| [design-spec.md](design-spec.md) | Swing 화면 디자인 사양: 배치·동작은 SSMS, 모양은 DataGrip New UI. 요소별 비교, 디자인 토큰과 FlatLaf 키, 아이콘 요구 사항, 적용 현황(Islands, 아이콘 팩, 글꼴, 촘촘한 모드, 측정값) |
| [swing-table-designer.md](swing-table-designer.md) | Swing 테이블 디자이너(Design, New Table…): 열·키·외래 키·인덱스 편집, 변경 스크립트 미리 보기와 저장, 드라이버별 부분(어댑터) |
| [swing-explorer-object-actions.md](swing-explorer-object-actions.md) | Swing 개체 탐색기 개체 작업: DB 객체 삭제(여러 개, 연쇄 옵션)·이름 바꾸기(객체 관리자, 스크립트 미리 보기, 열린 트랜잭션 질문), 이름·정규화된 이름 복사, 여러 개 선택, SQL 생성 메뉴 |
| [swing-security.md](swing-security.md) | Swing 보안 편집기: 로그인·사용자·역할 속성(일반, 역할 멤버 자격, SQL Server 사용자 매핑, 사용 권한), 새로 만들기·삭제, 개체 사용 권한(허용·권한 부여·거부), 스크립트 미리 보기 후 실행 |
| [swing-query-library.md](swing-query-library.md) | Swing Query Library: 자주 쓰는 쿼리 트리(파일 하나 = 쿼리 하나, 여러 루트), 실행, 매개변수 매핑(안전한 바인딩), 예약 실행, 공유(공유 폴더 자동 새로 고침, Git, 내보내기·가져오기, 저장 충돌) |
| [swing-erd.md](swing-erd.md) | Swing ER 다이어그램(View Diagram): 스키마·테이블의 관계도, 자동 배치, 확대·이동, PNG·SVG·.erd 내보내기, SWT와 같은 곳에 배치 저장 |
| [procedure-debugger.md](procedure-debugger.md) | 프로시저 디버거: PostgreSQL pldbgapi(중단점, 단계 실행, 변수·호출 스택, attach 모드), Oracle DBMS_DEBUG(디버그 컴파일 질문, 프로시저 나가기, 권한 안내), 복원한 디버그 모델, 끝낼 때 트랜잭션 질문, SQL Server·MySQL·Altibase 가능성과 추정 |
| [sqlserver-execution-trace.md](sqlserver-execution-trace.md) | SQL Server 실행 추적(Trace Execution…): 프로시저를 인수와 함께 실행하고 확장 이벤트로 문장마다 줄·시간·행·읽기를 기록, 원본 옆 타임라인·줄별 표와 실행된 줄 표시, 권한 안내(GRANT), XE 세션 정리, 끝날 때 트랜잭션 질문 |
| [swing-source-editor.md](swing-source-editor.md) | Swing 개체 정의 편집: Modify·Script as ALTER(SWT 소스 탭 저장과 같은 객체 관리자 스크립트), New View/Procedure/Function 템플릿, 실행 뒤 노드 다시 읽기 |
| [swing-sqlserver-agent.md](swing-sqlserver-agent.md) | Swing SQL Server 에이전트 관리: 개체 탐색기의 에이전트 노드(서비스 상태), 작업(속성·단계·일정·알림, 시작·중지·사용·기록·스크립트), 작업 활동 모니터, 경고, 운영자, 오류 로그, 프록시(읽기), 권한·에이전트 멈춤 처리 |
| [swing-job-schedulers.md](swing-job-schedulers.md) | Swing 작업 스케줄러: Oracle DBMS_SCHEDULER(일정, 작업 실행 기록, 실행 중인 작업, 작업 창, 지금 실행·중지), PostgreSQL pg_cron(설치 여부 노드, 작업, 기록), MySQL/MariaDB 이벤트(창, event_scheduler 상태), 권한·스케줄러 중지 처리 |
| [swing-options.md](swing-options.md) | Swing 옵션과 SWT 설정 페이지: 키 306개의 Swing 효과 전수(키별 상태 표), Query Execution(구분 기호·매개 변수·오류 처리, 오류 뒤 무확인 롤백 고침)·Connection Types(연결 유형 편집, 열린 세션의 커밋 방식 적용)·Oracle 탭, 결과 형식, 격리 수준, 연결별 설정(연결 대화상자 Settings 탭), Data Formats·Errors and Timeouts·Metadata·Client Identification 탭, 남은 키 목록 |
| [swing-driver-manager.md](swing-driver-manager.md) | Swing 드라이버 관리자(Tools > Driver Manager…, Connect 대화상자의 Edit Driver…): 드라이버 만들기·복사·편집·삭제, 로컬 jar·폴더 라이브러리와 상태, 클래스 찾기, 오프라인 모드에서 다운로드 막기 |
| [swing-l10n.md](swing-l10n.md) | Swing 화면 언어: OS 언어를 따르는 영어/한국어 NLS 묶음, 영역 나눔, 용어집, 영어로 남긴 것, 한글 굵은 글꼴(합성 글꼴 대체 칸) |
| [swing-followup-fixes.md](swing-followup-fixes.md) | Swing 후속 수정(2026-11-07): 자동완성 프로시저 제안 설정, SQL Server 열 COLLATE, 날짜/시간 소수 초, 남은 영어 문구, 전송 대상 이름, 네이티브 백업 실패. 작은 화면 수정(2026-11-08): 쿼리 상태 줄 폭, 잠금 나무 폭, 테마 전환 뒤 탭 넘침 단추, 연결 중 중지, 감사 기록 열, 줄임표 |
| [swing-sql-completion.md](swing-sql-completion.md) | SQL 자동완성의 프로시저 제안: CALL/EXEC 뒤 부를 수 있는 루틴(방언 규칙, SQL Server 스칼라 함수 포함)·패키지·스키마, 이름만 넣기, `EXEC dbo.`의 NullPointerException(끝나지 않은 이름) |
| [model-fixes.md](model-fixes.md) | 모델 후속 수정(2026-11-07): SQL Server 매개변수 종류, MariaDB 패키지 캐시, PostgreSQL Block IO 단위, PostgreSQL 복사한 열의 COLLATE·STORAGE, MySQL/MariaDB 계획 비용. 작은 수정(2026-11-08): 변경을 하는 모델 확인의 기본 단추, Oracle DBMS_OUTPUT을 열린 세션에도 적용, 슬래시 Windows 경로, 연결 유형 이름의 언어, MySQL 5·StarRocks 라이선스 표기, LICENSE.md의 JFreeChart |
| [altibase.md](altibase.md) | Altibase: Object Explorer의 테이블 저장 방식 표시(메모리 빨강, 디스크 파랑, 휘발성 호박색, 툴팁·속성), 목록 질의에서 함께 읽는 카탈로그 근거 |
| [swing-auth-prompts.md](swing-auth-prompts.md) | 연결할 때의 질문: 기본 방식이 아닌 인증 방식(SQL Server Authentication, Oracle Username/password 등)의 자격 증명 질문, JSch SSH의 호스트 키·비밀번호·개인 키 암호·키보드 대화형(OTP) 질문(안전 쪽 실패) |
| [shell-command-approval.md](shell-command-approval.md) | 저장된 설정의 셸 명령(연결 이벤트, 셸 명령 암호, 전송 뒤 명령) 실행 전 승인: Swing 대화상자(이번만 실행·항상 허용·취소), 연결별·정확한 명령별 기억, 승인 서비스가 없으면 실행 거부 |
| [drivers.md](drivers.md) | JDBC 드라이버 내장(SQL Server, Oracle, PostgreSQL, MariaDB, SQLite만 내장하는 단일 배포판, MySQL 내장 취소), 오프라인 우선 조회와 오프라인 모드 |
| [driver-licenses.md](driver-licenses.md) | JDBC 드라이버별 라이선스·버전·크기 목록, 내장 가능 여부 분류, MySQL Connector/J 결정 기록 |
| [driver-offline-bundle.md](driver-offline-bundle.md) | 폐쇄망용 오프라인 드라이버 설치 묶음: 인터넷 PC에서 드라이버를 골라 파일 하나로 만들고 폐쇄망 PC에서 설치, 형식(`.chromite-drivers`, 매니페스트·SHA-256), 드라이버 폴더 설치와 로더 등록, 검사·zip-slip·상한, 라이선스 메모 |
| [runtime.md](runtime.md) | 내장 런타임(JBR 25 + jlink, `tools/jre/`): 모듈 목록과 근거, 크기, 시작 시간 측정, 갱신 절차, 지원 플랫폼 |
| [platform-startup.md](platform-startup.md) | 플랫폼 시작·종료: Windows 인증서 저장소(`WINDOWS-ROOT`)를 기본 신뢰 저장소로 쓰기, macOS 앱 메뉴의 끝내기(Cmd+Q)·정보·환경설정을 메인 창 경로로 연결 |
| [versioning.md](versioning.md) | 제품 버전 체계(`major.minor.dev`, 0.1.0부터): 규칙, 버전이 있는 곳과 유일한 원본, 번들 버전을 따로 두는 이유, `tools/release/bump-version.ps1`, 릴리스 태그 검사 |
| [build.md](build.md) | 빌드 의존 저장소(`deps/dbeaver-common`, `deps/datadam-api`) 구성, 빌드 명령, 빌드하는 제품, 갱신 방법 |
| [swt-removal.md](swt-removal.md) | SWT(Eclipse RCP) 프런트엔드 삭제: 남길 번들 판별, 삭제·이동한 것, Swing으로 옮길 때까지 없는 기능 목록 |
| [swt-parity-audit.md](swt-parity-audit.md) | SWT → Swing 미이관 기능 전수 조사: 누락 원인, 영역별 대조(플랫폼·편집기·탐색기·DB별, `swt-parity/`), "조용히 끊김" 분류, 우선순위 |
| [internal-rename.md](internal-rename.md) | 내부 이름 변경(DBeaver 패키지·번들 이름 → `com.seizia.chromite`): 이름 규칙, 유지하는 이름, 도구(`tools/rename/`), 새로 시작(데이터 폴더), 검증 게이트 |
| [ci.md](ci.md) | GitHub Actions CI: 플랫폼별 빌드와 결과물, CodeQL, `.github` 파일 정리, 린트를 뺀 이유 |
| [workspace-location.md](workspace-location.md) | 작업 공간 위치: `-data`가 없으면 `ChromiteData\workspace6`(현재 폴더와 무관), 원인(jdb 추적), 고치기 전 `workspace\` 한 번 복사와 이름 변경 이전과의 순서 |
| [distribution.md](distribution.md) | 배포: Windows 설치 프로그램(NSIS, 사용자별, 조용한 업그레이드), 업데이트 피드 `releases.json`, 배포 스크립트와 Docker 리허설, 다운로드 사이트, 되돌리기 |
| [signing.md](signing.md) | 릴리스 서명: Windows(Azure Artifact Signing)·macOS(Developer ID 서명·공증) 흐름, 서명 대상 파일, 제3자 정책, 사용자가 할 일 |
| [auto-update.md](auto-update.md) | 앱 안의 자동 업데이트: 업데이트 목록 확인(고정 User-Agent, 1 MB, 10초), 뒤에서 받기, Windows·macOS 파일 확인(서명·게시자·버전), 끝날 때 적용(도우미), 실패 처리, 옵션, 끝낼 때 트랜잭션 질문, 개발 빌드 시험 모드 |
| [connection-transfer.md](connection-transfer.md) | 연결 내보내기·가져오기: 비밀번호를 사용자 암호로 암호화한 파일(AES-256-GCM, PBKDF2 600,000번, 형식 버전 1), 내보내는 것, 충돌(건너뛰기·바꾸기·사본), 연결 중인 연결 보호, OS 보안 저장소 이전과의 관계 |
| [credential-storage.md](credential-storage.md) | 저장된 비밀번호의 OS 보안 저장소(Windows DPAPI, macOS 키체인) 이전: 연결 자격 증명과 작업 공간 비밀(전역 네트워크 프로필, 프록시, Maven), Chromite 전용 저장 파일과 범위, 시작할 때 이전(중간에 죽어도 안전), 저장 실패 표시, 옮기지 않은 것 |
