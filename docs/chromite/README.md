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
| [swing-frontend.md](swing-frontend.md) | Swing 프런트엔드 구조, 재사용 분석, MVP 범위, 쿼리 실행(F5) 사양, 쿼리 실행 계층(SWT 없는 실행 API) |
| [drivers.md](drivers.md) | JDBC 드라이버 내장, 공개판·내부판 구성, 오프라인 우선 조회 |
| [driver-licenses.md](driver-licenses.md) | JDBC 드라이버별 라이선스·버전·크기 목록, 공개판 내장 가능 여부 분류 |
| [runtime.md](runtime.md) | 내장 런타임(JBR 25 + jlink), 지원 플랫폼, 서명 |
| [build.md](build.md) | 빌드 의존 저장소(`deps/dbeaver-common`, `deps/datadam-api`) 구성, 빌드 명령, 갱신 방법 |
| [ci.md](ci.md) | GitHub Actions CI: 플랫폼별 빌드와 결과물, CodeQL, `.github` 파일 정리, 린트를 뺀 이유 |
