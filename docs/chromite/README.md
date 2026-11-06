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
| [swing-frontend.md](swing-frontend.md) | Swing 프런트엔드 구조, 재사용 분석, MVP 범위, 쿼리 실행(F5) 사양, 쿼리 실행 계층(SWT 없는 실행 API), A–G단계 구현 현황(D단계: 범용 연결 화면, 연결 명령, G단계: 결과 편집·저장) |
| [swing-registered-servers.md](swing-registered-servers.md) | Swing Registered Servers 창(서버 그룹·서버 트리, 연결 폴더 재사용), 연 서버만 보이는 Object Explorer |
| [swing-admin-tools.md](swing-admin-tools.md) | Swing 관리 도구: Activity Monitor(세션 목록·끝내기, 잠금 대기), Find Objects(이름으로 객체 찾기), 서버 대시보드를 미룬 이유 |
| [swing-data-transfer.md](swing-data-transfer.md) | Swing 데이터 가져오기·내보내기 마법사(Export Data: 테이블·뷰·결과 표 → 모든 스트림 형식, Import Data: CSV → 기존 테이블), 모델 실행 경로와 SWT 대비 변경점 |
| [swing-backup-restore.md](swing-backup-restore.md) | Swing 데이터베이스 백업·복원(Tasks ▸ Back Up… / Restore…): SQL Server T-SQL 대화상자(진행 메시지, 복원 계획, 파일 위치), PostgreSQL·MySQL 로컬 클라이언트 도구 |
| [swing-result-viewer.md](swing-result-viewer.md) | Swing 결과 뷰어: 값 패널(JSON·XML·이미지·16진수), 정렬·필터, 다음 페이지, 레코드 보기, 복사 형식, 선택 집계 |
| [swing-sql-editor-plus.md](swing-sql-editor-plus.md) | Swing SQL 편집기 확장: 실행 계획(예상·실제, SQL Server 계획기), 쿼리 기록, SQL 서식·편집 명령, 템플릿, 매개변수 대화상자 |
| [swing-conn-advanced.md](swing-conn-advanced.md) | Swing Connect 대화상자의 고급 연결 설정: 인증 방식별 칸, SSH·프록시·SSL 탭, SQL Server·Oracle 주 칸, 연결 색, 연결 직후 SQL |
| [design-spec.md](design-spec.md) | Swing 화면 디자인 사양: 배치·동작은 SSMS, 모양은 DataGrip New UI. 요소별 비교, 디자인 토큰과 FlatLaf 키, 아이콘 요구 사항, 적용 현황(Islands, 아이콘 팩, 글꼴, 측정값) |
| [swing-table-designer.md](swing-table-designer.md) | Swing 테이블 디자이너(Design, New Table…): 열·키·외래 키·인덱스 편집, 변경 스크립트 미리 보기와 저장, 드라이버별 부분(어댑터) |
| [swing-query-library.md](swing-query-library.md) | Swing Query Library: 자주 쓰는 쿼리 트리(파일 하나 = 쿼리 하나, 여러 루트), 실행, 매개변수 매핑(안전한 바인딩), 예약 실행·공유 설계 |
| [swing-erd.md](swing-erd.md) | Swing ER 다이어그램(View Diagram): 스키마·테이블의 관계도, 자동 배치, 확대·이동, PNG·SVG·.erd 내보내기, SWT와 같은 곳에 배치 저장 |
| [swing-source-editor.md](swing-source-editor.md) | Swing 개체 정의 편집: Modify·Script as ALTER(SWT 소스 탭 저장과 같은 객체 관리자 스크립트), New View/Procedure/Function 템플릿, 실행 뒤 노드 다시 읽기 |
| [swing-l10n.md](swing-l10n.md) | Swing 화면 언어: OS 언어를 따르는 영어/한국어 NLS 묶음, 영역 나눔, 용어집, 영어로 남긴 것, 한글 굵은 글꼴(합성 글꼴 대체 칸) |
| [drivers.md](drivers.md) | JDBC 드라이버 내장(공개판 구현), 공개판·내부판 구성, 오프라인 우선 조회와 오프라인 모드 |
| [driver-licenses.md](driver-licenses.md) | JDBC 드라이버별 라이선스·버전·크기 목록, 공개판 내장 가능 여부 분류 |
| [runtime.md](runtime.md) | 내장 런타임(JBR 25 + jlink), 지원 플랫폼, 서명 |
| [build.md](build.md) | 빌드 의존 저장소(`deps/dbeaver-common`, `deps/datadam-api`) 구성, 빌드 명령, 갱신 방법 |
| [ci.md](ci.md) | GitHub Actions CI: 플랫폼별 빌드와 결과물, CodeQL, `.github` 파일 정리, 린트를 뺀 이유 |
| [signing.md](signing.md) | 릴리스 서명: Windows(Azure Artifact Signing)·macOS(Developer ID 서명·공증) 흐름, 서명 대상 파일, 제3자 정책, 사용자가 할 일 |
