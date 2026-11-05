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
| [branding.md](branding.md) | 제품명, 아이콘, 스플래시, About 화면, 라이선스 고지 등 Chromite 브랜딩 |
