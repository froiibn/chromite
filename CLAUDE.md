# CLAUDE.md

이 저장소는 **Chromite**다. DBeaver Community(https://github.com/dbeaver/dbeaver)를 포크해 Apache License 2.0에 따라
재구성한 데이터베이스 워크벤치다. 코드 구조, 빌드, 코딩 규칙은 upstream의 에이전트 문서를 그대로 따른다.

@AGENTS.md
@AGENTS-Architecture.md

새 데이터베이스 드라이버를 추가할 때는 [AGENTS-New-Database-Driver.md](AGENTS-New-Database-Driver.md)를 먼저 읽는다.

아래 내용은 Chromite에만 해당하며, 위 문서와 충돌하면 **아래 내용이 우선한다.**

## 응답 언어

- 사용자에게는 항상 한국어로 답한다. 코드, 명령어, 식별자, 커밋 메시지는 영어로 쓴다.

## 최소 변경 원칙

모든 작업은 요청을 해결하는 데 필요한 최소한의 변경으로 끝낸다. upstream 병합 충돌을 줄이고, 리뷰를 쉽게 하기 위해서다.

- 요청과 관계없는 리팩터링, 이름 변경, 정리, 포맷팅을 하지 않는다. 발견한 문제는 고치지 말고 보고만 한다.
- 고치는 줄 외에는 공백, 들여쓰기, import 순서, 줄바꿈 문자(CRLF/LF), 파일 인코딩을 바꾸지 않는다.
- 파일을 이동하거나 이름을 바꾸거나 삭제하지 않는다. 꼭 필요하면 먼저 사용자에게 묻는다.
- 요청하지 않은 기능, 옵션, 설정, 의존성을 추가하지 않는다.
- upstream 파일을 고치기보다 새 파일, 새 플러그인, 확장 포인트로 해결할 수 있으면 그쪽을 택한다.
- upstream 파일을 고쳐야 하면 바뀌는 범위를 메서드나 블록 단위로 좁힌다.
- 변경 범위가 예상보다 커지면(여러 플러그인에 걸치거나 파일 수가 많아지면) 진행하기 전에 범위와 이유를 사용자에게 알리고 확인을 받는다.
- 한 커밋, 한 PR에는 한 가지 목적의 변경만 담는다.

## 브랜딩

- 사용자에게 보이는 곳(제품명, 메뉴, 대화상자, About, 설치 파일, 스플래시, 아이콘)에는 "DBeaver" 이름이나 로고를 쓰지 않는다.
  제품명은 `Chromite`, 부제는 `Database Workbench`다. DBeaver는 DBeaver Corp의 상표다.
- 로고와 아이콘 원본은 `chromite_logo_pack/`에 있다. 새 아이콘은 이 폴더의 이미지로 만든다.
- 제품 정보는 `product/community/product-branding.properties`, `product/community/DBeaver.product`,
  `plugins/org.jkiss.dbeaver.ui.app.standalone/plugin.xml`과 `OSGI-INF/l10n/bundle*.properties`에 있다.
- 다음은 **바꾸지 않는다.** upstream 변경을 계속 병합할 수 있어야 하기 때문이다.
  - Java 패키지명, 번들 ID, 확장 포인트 ID (`org.jkiss.dbeaver.*`)
  - 설정 키, 드라이버 ID, 내부 파일 이름(`dbeaver16.png` 같은 아이콘 파일명 포함)
  - Maven 프로파일 이름(`product-dbeaver-ce` 등)
- 화면 문구에서 "DBeaver"를 바꿀 때는 일괄 치환하지 않는다. 사용자에게 실제로 보이는 문자열만 골라서 바꾼다.
- dbeaver.io의 버전 확인, 업데이트 사이트, 다운로드 링크를 새로 넣지 않는다. Chromite가 DBeaver 업데이트로 덮어써질 수 있다.

## 라이선스 (Apache 2.0)

- `LICENSE.md`와 `NOTICE`를 유지한다. 출처 표기가 바뀌면 `NOTICE`를 갱신한다.
- 기존 소스 파일의 DBeaver Corp 저작권 머리말은 지우거나 고치지 않는다.
- upstream Java 파일을 수정하면 저작권 줄 바로 아래에 다음 한 줄을 넣는다(이미 있으면 연도만 갱신).

  ```
   * Modifications Copyright (C) 2026 Chromite contributors
  ```

- Chromite에서 새로 만든 Java 파일은 `docs/license_header.txt` 대신 아래 머리말을 쓴다.

  ```java
  /*
   * Chromite - Database Workbench
   * Copyright (C) 2026 Chromite contributors
   *
   * Licensed under the Apache License, Version 2.0 (the "License");
   * you may not use this file except in compliance with the License.
   * You may obtain a copy of the License at
   *
   *     http://www.apache.org/licenses/LICENSE-2.0
   *
   * Unless required by applicable law or agreed to in writing, software
   * distributed under the License is distributed on an "AS IS" BASIS,
   * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   * See the License for the specific language governing permissions and
   * limitations under the License.
   */
  ```

## Git 작업 방식

AGENTS.md의 "Branches and Git Workflow" 중 DBeaver 조직 전용 규칙(`dbeaver/repo#번호` 형식, 이슈 연결 방식, 기여 정책)은 적용하지 않는다.

- 저장소: `froiibn/chromite`. 기본 브랜치는 `devel`이고 PR은 `devel`을 대상으로 한다.
- 이 저장소는 GitHub 포크라서 `gh` 명령에는 항상 `--repo froiibn/chromite`를 붙인다.
  붙이지 않으면 upstream(`dbeaver/dbeaver`)에 PR이나 이슈가 만들어질 수 있다.
- 브랜치 이름: `chromite/<짧은-설명>` (예: `chromite/branding-phase2`)
- 커밋 메시지: 영어, 첫 줄은 명령형 요약. 이슈가 있으면 `froiibn/chromite#번호`를 쓴다.
- `devel`에 직접 커밋하지 않는다. upstream 저장소에 push하거나 PR을 만들지 않는다.

## upstream 동기화

- upstream 변경은 `dbeaver/dbeaver`의 `devel` 브랜치에서 병합해 온다. 충돌이 나면 브랜딩 파일은 Chromite 쪽을 유지한다.
- upstream 파일 수정은 위의 "최소 변경 원칙"을 따른다.

## 빌드 환경

- 빌드에는 JDK 21과 Maven 3.9 이상이 필요하다. 전체 빌드는 시간이 오래 걸리므로 백그라운드에서 실행한다.

  ```bash
  mvn package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce
  ```

- 결과물은 `product/community/target/products/chromite-<버전>-<플랫폼>`이다.
- 빌드나 테스트를 실행하지 못했으면 확인하지 못했다고 분명히 보고한다.
