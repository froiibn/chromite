# CLAUDE.md

이 저장소는 **Chromite**다. DBeaver Community(https://github.com/dbeaver/dbeaver)를 포크해 Apache License 2.0에 따라
재구성한 데이터베이스 워크벤치다. 코드 구조, 빌드, 코딩 규칙은 upstream의 에이전트 문서를 그대로 따른다.

@AGENTS.md
@AGENTS-Architecture.md

새 데이터베이스 드라이버를 추가할 때는 [AGENTS-New-Database-Driver.md](AGENTS-New-Database-Driver.md)를 먼저 읽는다.

아래 내용은 Chromite에만 해당하며, 위 문서와 충돌하면 **아래 내용이 우선한다.**

## 응답 언어

- 사용자에게는 항상 한국어로 답한다. 코드, 명령어, 식별자, 커밋 메시지는 영어로 쓴다.

## 작업 원칙

모든 작업에 아래 7가지 원칙을 적용한다.

### 1. 최소 변경

요청을 해결하는 데 필요한 최소한의 변경으로 끝낸다. 의도하지 않은 동작 변경을 막고, 리뷰를 쉽게 하기 위해서다.

- 요청 범위 안의 코드만 고친다. 요청과 관계없는 리팩터링, 이름 변경, 정리, 포맷팅을 하지 않는다.
  범위 밖에서 발견한 문제는 고치지 말고 보고만 한다.
- 고치는 줄 외에는 공백, 들여쓰기, import 순서, 줄바꿈 문자(CRLF/LF), 파일 인코딩을 바꾸지 않는다.
- 요청하지 않은 기능, 옵션, 설정, 의존성을 추가하지 않는다.
- 요청 범위 밖의 파일을 이동하거나 이름을 바꾸거나 삭제해야 하면 먼저 사용자에게 묻는다.
- 바뀌는 범위를 메서드나 블록 단위로 좁힌다.
- 변경 범위가 예상보다 커지면(여러 플러그인에 걸치거나 파일 수가 많아지면) 진행하기 전에 범위와 이유를 사용자에게 알리고 확인을 받는다.
- 한 커밋, 한 PR에는 한 가지 목적의 변경만 담는다.

### 2. 근거 기반 판단

추측하지 않는다. 모든 판단은 실제 코드, 실제 데이터, 실제 실행 결과를 근거로 한다.

- 함수, 변수, 클래스의 동작은 이름이나 주석이 아니라 실제 구현을 읽고 판단한다.
  주석과 코드가 다르면 코드가 사실이다. 이때 주석이 틀렸다는 것을 보고하고, 수정 범위 안이면 주석을 고친다.
- 호출 관계, 사용 여부, 영향 범위는 검색(Grep 등)으로 실제 호출 지점을 확인한 뒤 판단한다.
- 데이터베이스의 테이블, 컬럼, 타입, 제약조건, 데이터는 이름으로 추론하지 않는다.
  실제 스키마(DDL, 메타데이터 조회, `information_schema`, 드라이버 메타데이터)나 실제 데이터를 조회해서 확인한다.
- 근거를 확인할 수 없으면 추측으로 진행하지 말고, 무엇을 확인하지 못했는지 밝히고 사용자에게 묻는다.
- 보고할 때는 판단의 근거(파일 경로와 줄 번호, 조회 결과, 실행 결과)를 함께 제시한다.

### 3. 근본 원인 해결 (예시 기반 처리 금지)

사용자가 특정 입력값이나 특정 상황을 예로 들어 문제를 제시해도, 그 예시만 고치지 않는다.

- 예시가 왜 실패하는지 근본 원인을 찾는다.
- 같은 원인으로 실패하는 다른 입력과 경로를 찾아 함께 확인한다.
- 특정 값을 분기 처리하는 땜질(`if (value == "예시")`)을 하지 않고, 원인이 되는 구조를 고친다.
- 보고할 때 근본 원인, 같은 원인의 영향을 받는 다른 경우, 수정 방법을 함께 설명한다.
- 근본 수정이 최소 변경 범위를 크게 넘으면 1번 원칙에 따라 범위를 먼저 확인받는다.

### 4. 사용하지 않는 코드는 완전히 삭제

기능을 바꾸거나 없앨 때 이전 코드를 남겨 두지 않는다. 남은 코드는 나중에 현재 사용 중인 코드로 오인되기 쉽다.
이전 코드는 git 이력에서 찾을 수 있다.

- 주석 처리, `if (false)`, 사용하지 않는 플래그, 호출되지 않는 메서드나 클래스로 남기지 않고 삭제한다.
- 함께 쓸모없어진 import, 상수, 메시지 키(`*Messages`, `plugin.properties`), `plugin.xml` 확장, 리소스, 테스트도 같이 삭제한다.
- 삭제하기 전에 검색으로 다른 곳에서 쓰지 않는지 확인한다(2번 원칙). 확장 포인트, 리플렉션, `plugin.xml`, 설정 파일에서 이름으로 참조하는 경우도 확인한다.
- 이 원칙은 요청한 변경으로 쓸모없어진 코드에 적용한다. 범위 밖의 기존 죽은 코드는 1번 원칙에 따라 보고만 한다.

### 5. 주석 최신화

주석은 항상 코드와 일치해야 한다.

- 코드를 고치면 그 코드와 관련된 주석(Javadoc, 블록 주석, 줄 주석)도 함께 고친다. 틀린 주석을 남기지 않는다.
- 주석에는 코드가 하는 일을 반복하지 말고, **왜** 이런 구조를 선택했는지를 쓴다.
- 파일을 새로 만들거나 고치면 클래스 Javadoc에 설계 이유와 변경 이력을 남긴다(이미 있으면 갱신한다).

  ```java
  /**
   * 설계: 왜 이런 구조를 선택했는지
   *
   * 변경 이력:
   * - 2026-11-06 무엇을 왜 바꿨는지
   */
  ```


### 6. 설계 문서 작성과 갱신

모든 개발 작업 뒤에는 설계 문서를 마크다운으로 작성하거나 갱신한다.

- 위치: `docs/chromite/`. 목차는 `docs/chromite/README.md`에 둔다.
- 기능이나 모듈 단위로 한 파일을 둔다(예: `docs/chromite/branding.md`).
- 내용: 목적, 구조(관련 플러그인, 클래스, 확장 포인트, 설정 파일), 설계 결정과 이유, upstream 대비 변경점, 변경 이력.
- 코드와 문서가 달라지지 않도록 같은 커밋에서 함께 갱신한다.

### 7. 회귀 테스트 (사용자가 요청할 때만)

이 프로젝트는 데스크톱 앱이라 전체 빌드와 테스트에 시간이 오래 걸린다.
그래서 회귀 테스트는 **사용자가 명시적으로 요청할 때만** 작성하고 실행한다.

- 요청이 없으면 작업이 끝난 뒤 회귀 테스트 케이스를 추가하거나 회귀 테스트를 실행하지 않는다.
  AGENTS.md의 "Creating unit tests"와 "Running tests"보다 이 규칙이 우선한다.
- 요청이 없을 때는 작업 보고에 "회귀 테스트는 실행하지 않았다"고 한 줄 밝힌다.
  변경이 다른 기능에 영향을 줄 수 있으면 영향받을 수 있는 기능을 함께 적는다.
- 사용자가 요청하면 다음과 같이 한다.
  - 모델 기능: `test/` 아래 테스트 플러그인에 JUnit 테스트로 작성한다(AGENTS.md "Creating unit tests" 참고).
  - 자동화하기 어려운 UI, 설치, 브랜딩 확인: `docs/chromite/regression-tests.md`에 확인 절차와 기대 결과를 체크리스트로 적는다.
  - 자동 테스트 실행: `mvn verify -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce` (백그라운드에서 실행)
  - 결과 보고: 실행한 테스트, 통과/실패 수, 실패 내용을 그대로 적는다.
    환경 문제(JDK 21이나 Maven 없음 등)로 실행하지 못했으면 실행하지 못했다고 밝히고, 사용자가 실행할 명령을 알려 준다.

## 브랜딩

- 사용자에게 보이는 곳(제품명, 메뉴, 대화상자, About, 설치 파일, 스플래시, 아이콘)에는 "DBeaver" 이름이나 로고를 쓰지 않는다.
  제품명은 `Chromite`, 부제는 `Database Workbench`다. DBeaver는 DBeaver Corp의 상표다.
- 로고와 아이콘 원본은 `chromite_logo_pack/`에 있다. 새 아이콘은 이 폴더의 이미지로 만든다.
- 제품 정보는 `product/community/product-branding.properties`, `product/community/DBeaver.product`,
  `plugins/org.jkiss.dbeaver.ui.app.standalone/plugin.xml`과 `OSGI-INF/l10n/bundle*.properties`에 있다.
- 다음 내부 이름은 별도로 계획한 작업에서만 바꾼다. 다른 작업 중에 일부만 바꾸지 않는다.
  사용자 설정, 저장된 연결 정보, 플러그인 참조가 이 이름에 묶여 있어 일부만 바꾸면 기능이 깨진다.
  - Java 패키지명, 번들 ID, 확장 포인트 ID (`org.jkiss.dbeaver.*`)
  - 설정 키, 드라이버 ID, 내부 파일 이름(`dbeaver16.png` 같은 아이콘 파일명 포함)
  - Maven 프로파일 이름(`product-dbeaver-ce` 등)
- 화면 문구에서 "DBeaver"를 바꿀 때는 일괄 치환하지 않는다. 사용자에게 실제로 보이는 문자열만 골라서 바꾼다.
- dbeaver.io의 버전 확인, 업데이트 사이트, 다운로드 링크를 새로 넣지 않는다. Chromite가 DBeaver 업데이트로 덮어써질 수 있다.

## 라이선스 (Apache 2.0)

- `LICENSE.md`와 `NOTICE`를 유지한다. 출처 표기가 바뀌면 `NOTICE`를 갱신한다.
- 기존 소스 파일의 DBeaver Corp 저작권 머리말은 지우거나 고치지 않는다.
- DBeaver에서 온 Java 파일을 수정하면 저작권 줄 바로 아래에 다음 한 줄을 넣는다(이미 있으면 연도만 갱신).

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

## upstream 정책 (독립 개발)

Chromite는 2026-11-06 포크 시점부터 DBeaver와 독립적으로 개발한다. upstream(`dbeaver/dbeaver`)을 정기적으로 병합하지 않는다.

- `git merge upstream/...`이나 `git pull upstream ...`으로 upstream 전체를 가져오지 않는다.
- 필요한 변경(보안 패치, 드라이버 수정 등)은 사용자가 요청할 때만 해당 커밋을 `git cherry-pick`으로 가져온다.
  - 가져오기 전에 그 커밋이 바꾸는 파일과 Chromite에서 바뀐 부분이 겹치는지 확인하고 사용자에게 알린다.
  - 커밋 메시지에 원본 커밋을 남긴다(`git cherry-pick -x`).
  - 가져온 내용은 관련 설계 문서의 변경 이력에 적는다.
- 보안 패치와 JDBC 드라이버 업데이트는 자동으로 들어오지 않는다. 관련 작업을 할 때 오래된 의존성이 보이면 사용자에게 알린다.

## 빌드 환경

- 빌드에는 JDK 21과 Maven 3.9 이상이 필요하다. 전체 빌드는 시간이 오래 걸리므로 백그라운드에서 실행한다.

  ```bash
  mvn package -f product/aggregate/pom.xml -T1C -Pproduct-dbeaver-ce
  ```

- 결과물은 `product/community/target/products/chromite-<버전>-<플랫폼>`이다.
- 빌드나 테스트를 실행하지 못했으면 확인하지 못했다고 분명히 보고한다.
