# 제품 방향

## 목적

Chromite가 무엇을 만들고, 누구를 위한 제품이며, 어떤 원칙으로 개발하는지 정리한다.
세부 설계는 각 문서([swing-frontend.md](swing-frontend.md), [drivers.md](drivers.md), [runtime.md](runtime.md))를 본다.

## 제품 정의

- **제품:** Chromite, Database Workbench
- **기반:** DBeaver Community (Apache License 2.0)를 2026-11-06에 포크
- **사용자:** 개발자와 DBA. 주 사용자인 개발팀은 고객사 폐쇄망에서 작업하는 경우가 많다.
- **차별화:**
  - SSMS식 화면 배치와 사용 흐름, DataGrip·SSMS 스타일의 디자인
  - 폐쇄망에서도 바로 쓸 수 있는 드라이버 내장
  - Java 런타임을 따로 설치할 필요가 없는 사용자 경험
  - AI 기능 강화 (세부 내용은 추후 논의)

## 결정 사항

| 주제 | 결정 | 날짜 |
|---|---|---|
| 공개 여부 | 오픈소스. 저장소 `froiibn/chromite`는 공개로 유지하고, Chromite 코드도 Apache 2.0으로 배포 | 2026-11-06 |
| upstream | DBeaver와 독립 개발. 정기 병합을 하지 않고, 필요한 커밋만 요청할 때 `git cherry-pick -x`로 가져옴 | 2026-11-06 |
| UI 기술 | SWT/Eclipse RCP UI를 Swing으로 새로 만들고, 모델 계층은 재사용 | 2026-11-06 |
| UX / 디자인 | 화면 배치는 SSMS를 따르고, 디자인은 DataGrip(다크)과 SSMS(라이트) 스타일 | 2026-11-06 |
| 드라이버 | 설치 파일에 미리 내장. 오프라인 우선 조회, 메모리 로딩은 연결할 때만 | 2026-11-06 |
| 배포판 | 공개판(주요 DB 드라이버만 내장)과 내부판(전체 드라이버 내장)으로 분리 | 2026-11-06 |
| 런타임 | JBR 25 LTS를 jlink로 줄여 내장. 사용자가 JRE를 따로 설치하지 않음 | 2026-11-06 |
| 플랫폼 | Windows x64, macOS Apple Silicon(arm64). Linux, Intel Mac, Windows ARM은 지원하지 않음 | 2026-11-06 |
| SWT 버전 | Swing MVP가 나올 때까지 유지한 뒤 삭제 | 2026-11-06 |

## 개발 원칙

작업 규칙은 [CLAUDE.md](../../CLAUDE.md)의 "작업 원칙"을 따른다.
최소 변경, 근거 기반 판단, 근본 원인 해결, 사용하지 않는 코드 완전 삭제, 주석 최신화, 설계 문서 갱신,
회귀 테스트(사용자가 요청할 때만)의 7가지다.

## 진행 단계 (초안)

1. **1단계 리브랜딩:** 제품명, 아이콘, 스플래시, 라이선스 고지 (완료, PR froiibn/chromite#1)
2. **빌드 환경:** JDK와 Maven 설치, GitHub Actions에서 Windows와 macOS arm64 빌드
3. **드라이버 내장:** 라이선스 목록 작성, 드라이버 묶음과 `maven-local` 구성, 오프라인 모드
4. **런타임 내장:** JBR 25 + jlink, AOT 캐시, 설치 파일
5. **Swing MVP:** [swing-frontend.md](swing-frontend.md)의 MVP 범위
6. **SWT 버전 삭제**
7. **AI 기능 강화 등 차별화 기능**

순서와 세부 일정은 확정되지 않았다.

## 미결정 사항

- AI 기능의 구체적인 범위
- 버전 체계 (DBeaver 버전 26.2.5를 이어 쓸지, Chromite 자체 버전을 쓸지)
- 업데이트 방식 (GitHub Releases 기반 버전 확인 등)
- Windows 코드 서명 인증서, Apple Developer 계정과 공증
- GitHub 포크 연결 해제 여부 (저장소 소유자가 직접 처리)
- 내부 이름(`org.jkiss.dbeaver.*` 패키지 등)을 바꿀지와 그 시점

## 변경 이력

- 2026-11-06 최초 작성. 제품 방향 논의 결과(공개 여부, upstream, UI 기술, 드라이버, 런타임, 플랫폼)를 정리.
