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
| [swing-frontend.md](swing-frontend.md) | Swing 프런트엔드 구조, 재사용 분석, MVP 범위, 쿼리 실행(F5) 사양 |
| [drivers.md](drivers.md) | JDBC 드라이버 내장, 공개판·내부판 구성, 오프라인 우선 조회 |
| [runtime.md](runtime.md) | 내장 런타임(JBR 25 + jlink), 지원 플랫폼, 서명 |
