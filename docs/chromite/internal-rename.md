# 내부 이름 변경 (`org.jkiss.dbeaver` → `com.seizia.chromite`)

## 목적

Chromite 안의 DBeaver 내부 이름(Java 패키지, OSGi 번들·피처·제품 ID, 확장 포인트 ID, Maven 좌표, 실행 식별자, 파일 이름)을
Chromite 이름으로 한 번에 바꾼다. 2026-11-06 독립 개발로 정하면서 upstream 병합 충돌을 줄이려고 이름을 유지할 이유가 없어졌고,
같은 이름을 쓰면 설치된 DBeaver와 데이터 폴더(`DBeaverData`), Windows 정책 레지스트리, 보안 저장소를 함께 쓰는 문제가 있었다.

계획과 결정: 사용자 승인(2026-11-07, 계획서 9절의 권장안 전부). 쓰지 않는 제품은 먼저 지웠다([build.md](build.md) "빌드하는 제품", PR froiibn/chromite#58).

## 이름 규칙

`tools/rename/rules.py`가 유일한 정의다. 위에서부터 차례로 적용하고, 유지 목록에 걸린 부분은 바꾸지 않는다.

| # | 원래 | 새 이름 |
|---|---|---|
| R1 | `org.jkiss.dbeaver` (점, `/`, `\`, `-`, 정규식 이스케이프 형태 모두) | `com.seizia.chromite` |
| R1b | 오타 `org.jkisss.dbeaver` | `com.seizia.chromite` |
| R2 | `DBeaver<Name>` 타입·파일 | `Chromite<Name>`. 예외: `DBeaverIcons` → `SwtIcons`(Swing `ChromiteIcons`와 구분), `UIFonts.DBeaver` → `UIFonts.Chromite` |
| R3 | `com.dbeaver.ai.*`, `com.dbeaver.secretController`, `com.dbeaver.preferences.*`, `com.dbeaver.ssh.tunnels`, `com.dbeaver.SecureStorage` | `com.seizia.chromite.…` |
| R4 | 실행 식별자: 환경 변수·상수 `DBEAVER_*`, 시스템 속성 `dbeaver.*`, 폴더·파일 `.dbeaver`, `DBeaverData`, `dbeaver-debug.log`, `dbeaver-*.properties`, `${dbeaver_home}`, 드라이버 속성 `@dbeaver-*`, plugin.xml ID(`dbeaver.activity.*`, 툴바 `dbeaver-*` 등), 보안 저장소 루트 `dbeaver`, Windows 정책 레지스트리 `Software\DBeaver Corp\DBeaver\policy` | `CHROMITE_*`, `chromite.*`, `.chromite`, `ChromiteData`, `chromite-debug.log`, …, `Software\Chromite\policy` |
| R5 | 파일 이름 `dbeaver*.png/ico/icns/bmp/xpm`, `dbeaver.e4xmi`, `e4-*dbeaver*.css`, `DBeaver.product`, `DBeaver.p2.inf` | `chromite…`, `Chromite.product`, `Chromite.p2.inf` |
| R6 | Maven: 부모 `org.jkiss.dbeaver:dbeaver`, 집계 `com.dbeaver.build:ce.aggregate`, `${dbeaver-product}`, 프로파일 `product-dbeaver-ce` | `com.seizia.chromite:chromite`, `com.seizia.chromite.build:aggregate`, `${chromite-product}`, `product-chromite-swt` |
| R7 | 테스트 패키지 `org.jkiss.junit`, `org.jkiss.util` | `com.seizia.chromite.junit`, `com.seizia.chromite.test.util` |
| - | `Bundle-Vendor: DBeaver Corp`, feature `provider-name="DBeaver Corp"` | `Chromite contributors` |

### 바꾸지 않는 것

| 대상 | 이유 |
|---|---|
| `deps/` 전체, 그 안의 `org.jkiss.dbeaver.DBException` | `deps/`는 수정하지 않은 upstream 사본이다(NOTICE, [build.md](build.md)). 그래서 `org.jkiss.dbeaver` 이름은 이 클래스 하나만 남는다 |
| `org.jkiss.code`, `org.jkiss.utils`, `org.jkiss.api`, `com.dbeaver.common/datadam/jdbc/rest/messaging…` | `deps/`가 정의한 이름 |
| `org.jkiss.bundle.*`, `org.jkiss.wmi`, `org.jkiss.awt` | 외부에서 받은 바이너리 번들, JNI 라이브러리가 아는 이름 |
| `${dbeaver-version}`, `repo.p2.dbeaver-*` | `deps/dbeaver-common/root/pom.xml`의 속성 |
| http(s) 주소, 메일, `dbeaver.com/io/net` 호스트 | upstream 문서와 서비스를 가리킨다 |
| OpenAI 로그인 `originator: dbeaver`, Copilot OAuth 앱 `DBEAVER_OAUTH_APP`, Snowflake `DBeaver_<앱>`, TiDB `dbeaver_tidb_plugin`, DataDam 범위·클라이언트(`dbeaver-storage`, `dbeaver-desktop`), `jdbc:dbeaver:` | 외부 서비스에 등록된 값. 바꾸면 로그인·연결이 깨진다 |
| `dbeaver-ce` 애플리케이션 ID, `com.dbeaver.app.team`, `PRODUCT_PROPRIETARY_DBEAVER_DESKTOP_PREFIX` | DBeaver 판을 알아보는 값 |
| upstream 이슈·저장소 참조(`dbeaver/dbeaver#…`, `dbeaver/pro#…`), `dbeaver-community-license.txt` | 출처 표기 |
| DBeaver Corp 저작권 머리말, `NOTICE`, `LICENSE.md` | 라이선스(CLAUDE.md) |
| 화면 문구의 "DBeaver" | 브랜딩 규칙에 따라 골라서 바꾼다(이번 작업 범위 아님) |
| `osgi-app.properties`의 `dbeaver/…` 경로 | 외부 IDEA 작업 공간 생성기가 저장소 폴더 이름을 `dbeaver`로 가정한다([build.md](build.md)) |

## 구조

| 파일 | 역할 |
|---|---|
| `tools/rename/rules.py` | 규칙과 유지 목록(데이터). `transform()`, `transform_path()` |
| `tools/rename/rename.py` | `plan`(보고만), `move`(파일 이동), `rewrite`(내용 치환), `headers`(수정 표시 줄), `patch`(upstream 패치 변환) |
| `tools/rename/gate.py` | 정적 검사 G1–G8. CI(`build.yml`)가 매 빌드마다 실행 |
| `plugins/com.seizia.chromite.model.rcp/…/LegacyNameMigration.java` | 이름 변경 전 사용자 데이터를 시작 시 한 번 복사("사용자 데이터 이전"). 옛 이름을 쓰는 유일한 코드라 `EXCLUDE_FILES`에 있다 |

- **git blob 단위로 처리한다.** Windows 작업 트리는 `core.autocrlf`에 따라 줄바꿈이 바뀌어 있고 일부 파일은 저장소에 CRLF·LF가 섞여 있다.
  도구는 인덱스의 blob을 읽어 바이트로 바꾸고(latin-1 왕복, 모든 패턴이 ASCII) `--no-filters`로 다시 쓴다. 그래서 바꾼 이름 외의 바이트는 그대로다.
- **이동은 인덱스 항목을 같은 blob으로 옮긴다.** 그래서 이동 커밋의 모든 변경이 100% 이름 변경(R100)으로 기록된다.
- **결정적이고 멱등이다.** 같은 입력은 같은 결과를 내고, 바꾼 결과에 다시 돌려도 바뀌는 것이 없다. 경로가 겹치면(대소문자만 다른 경우 포함) 아무것도 쓰지 않고 멈춘다.
- 내용 치환 뒤 보정 두 가지
  - `Export-Package` 중복 제거: `org.jkiss.dbeaver.X`와 이미 있던 `com.seizia.chromite.X`가 같은 이름이 되면 뒤의 줄을 지운다.
  - `DBException` import 추가: 옛 루트 패키지 `org.jkiss.dbeaver`의 클래스와 `import org.jkiss.dbeaver.*`를 쓰던 클래스는
    같은 패키지라서 `DBException`을 import 없이 썼다. 이 클래스는 `deps/`에 남으므로 명시적 import를 넣는다.

## 커밋 구성

커밋 순서는 C2 → C3 → C4 → C1 → C5다.

| 커밋 | 내용 |
|---|---|
| C2 | 이동만(`rename.py move`). 모든 변경이 R100. 이 커밋만으로는 빌드되지 않는다 |
| C3 | 내용 치환(`rename.py rewrite`) |
| C4 | 이름이 바뀐 Java 파일에 `Modifications Copyright (C) 2026 Chromite contributors` 줄(`rename.py headers`). 트리가 "변경 전 devel을 도구로 변환한 결과"와 같다 |
| C1 | 도구(`tools/rename/`)와 이 문서, 문서 목록 한 줄 |
| C5 | 손 수정: CLAUDE.md, README, 문서, NOTICE, CI 게이트 작업, 루트 pom의 `chromite-product` 값, 접두어 의미로 쓰는 5곳 검토 결과 |

- 도구 커밋(C1)을 C4 뒤에 둔 이유: C2–C4가 도구만으로 만든 트리가 되게 해서, 진행 중이던 브랜치를 옮길 때
  C4를 병합 기준으로 쓸 수 있게 했다(아래 "진행 중이던 브랜치 옮기기"). C2–C4는 저장소 밖에 둔 같은 도구로 만들었다.

병합은 merge commit으로 한다(이동 커밋 C2를 따로 남겨 `git log --follow`와 blame이 이동을 따라가게).
CLAUDE.md의 클래스 Javadoc "변경 이력" 규칙은 이름만 바뀐 파일에는 적용하지 않고 이 문서에 기록한다(사용자 결정 11).

## 사용자 데이터 이전

처음에는 기존 Chromite 사용자가 없다고 보고 옛 데이터를 가져오는 코드를 넣지 않았다(사용자 결정 5, 6). 그런데 이름 변경 전 빌드로
만든 작업 공간(개발·검증용 작업 공간 포함)을 새 빌드로 열면 연결이 하나도 보이지 않았다. 새 빌드는 `<프로젝트>/.chromite`만 읽고
`.dbeaver`를 찾는 코드가 없었기 때문이다. 그래서 2026-11-07에 시작 시 한 번 복사하는 이전 코드를 넣었다
(`com.seizia.chromite.model.rcp.LegacyNameMigration`).

### 바뀐 이름과 이전 전 위험

규칙(`rules.py`)과 코드 검색, 이름 변경 전 빌드(태그 `pre-internal-rename`)로 만든 실제 작업 공간을 근거로 정리했다.
"이전 전"은 이 수정이 없을 때 이름 변경 전 작업 공간을 새 빌드로 열었을 때의 결과다.

| 항목 | 이전 | 이후 | 이전 전 | 이전 처리 |
|---|---|---|---|---|
| 프로젝트 메타데이터 폴더 | `<프로젝트>/.dbeaver/` | `<프로젝트>/.chromite/` | **연결·폴더·색·필터·저장 비밀번호·작업(tasks)·리소스 속성 전부 안 보임** | 폴더 전체 복사 |
| └ `data-sources*.json` | 필터 `type` = `org.jkiss.dbeaver.ext.….SQLServerTable` 등 클래스 이름, `provider-properties`의 `@dbeaver-…` 키(SQL Server 인증·모든 스키마 표시, Oracle 연결 방식·SID/서비스, MySQL 서버 시간대 등) | `com.seizia.chromite.…`, `@chromite-…` | 파일을 읽어도 필터 무시, 드라이버별 연결 설정이 기본값으로 | 복사하며 이름 치환 |
| └ `credentials-config*.json` | 고정 키 AES(`BaseProjectImpl.LOCAL_KEY_CACHE`, 이름 변경 전후 같음) | 같음 | 비밀번호 안 보임(파일을 못 찾음) | **바이트 그대로 복사**. 내용(ID별 user/password/보안 인증 속성)에 바뀐 이름이 없다(아래 검증) |
| └ `project-settings.json`, `project-metadata.json`, `tasks.json`, `*.bak` | 프로젝트 ID, 리소스 속성(스크립트↔연결), 작업 | 같은 이름 | 프로젝트 ID 새로 생성, 스크립트 연결·작업 사라짐 | 복사하며 이름 치환 |
| 프로젝트 설정 `<프로젝트>/.settings/org.jkiss.dbeaver.project.resources.prefs` | 리소스 폴더(스크립트 폴더 등) | `com.seizia.chromite.project.resources.prefs` | 기본 폴더로 돌아감 | 복사하며 이름 치환 |
| 설정 노드 `.metadata/.plugins/org.eclipse.core.runtime/.settings/org.jkiss.dbeaver.*.prefs` (번들 ID 이름) | 예: `org.jkiss.dbeaver.model.sql`(SQL 서식 등) | `com.seizia.chromite.*` | 그 번들의 설정이 기본값으로 | 설정 서비스로 키 단위 복사, 키·값 이름 치환 |
| 설정 키 안의 옛 이름(이름이 안 바뀐 노드) | Swing 번들 노드(주 설정)의 `org.jkiss.dbeaver.core.confirm.*`(확인 대화상자 "다시 묻지 않음") | `com.seizia.chromite.core.confirm.*` | 다시 물음 | 새 키를 옆에 추가(옛 키 유지) |
| Swing 설정(테마, 편집기 글꼴, 창 배치, 서버 기록, Query Library 루트 등) | `com.seizia.chromite.ui.swing.prefs` | 같음(번들 ID가 원래 Chromite 이름) | 잃지 않음 | 해당 없음 |
| Query Library | `<작업 공간>/QueryLibrary/*.sql` | 같음 | 잃지 않음 | 해당 없음 |
| 번들 상태 폴더 `.metadata/.plugins/<번들 ID>/`(대화상자 설정 `dialog_settings.xml` 등) | `org.jkiss.dbeaver.*` | `com.seizia.chromite.*` | 그 번들 상태를 잃음. Swing 제품은 만들지 않음(Swing 번들 폴더는 이름 그대로) | 있으면 복사하며 이름 치환 |
| 작업 공간 정보 `.metadata/dbeaver-workspace.properties` | 작업 공간 ID, 활성 프로젝트 | `chromite-workspace.properties` | 작업 공간 ID 새로 생성 | 복사 |
| 외부 파일↔연결 `.metadata/dbeaver-external-files.json`(.data) | | `chromite-external-files.json`(.data) | 작업 공간 밖 SQL 파일의 연결 연결이 풀림 | 복사 |
| 로그 `.metadata/dbeaver-debug.log` | | `chromite-debug.log` | 옛 로그는 그 자리에 남음 | 하지 않음(진단용) |
| `.project` 성질 | `org.jkiss.dbeaver.DBeaverNature` | `com.seizia.chromite.ChromiteNature` | 연결이 있으면 새 빌드가 새 성질을 덧붙임(`updateProjectNature`). 옛 성질은 남지만 기능에 영향 없음 | 하지 않음(두 빌드가 같은 파일을 씀) |
| `.metadata/.config/*`(drivers.xml, connection-types.xml, network-profiles 등) | 경로 같음. `drivers.xml`의 드라이버 매개변수 `@dbeaver-default-…` | `@chromite-default-…` | 파일은 읽음. 사용자가 바꾼 그 매개변수만 무시 | 하지 않음(두 빌드가 같은 파일을 씀). 이름 변경 전 Swing 제품에는 드라이버 편집 화면이 없어 이 파일을 만들지 않았다 |
| 전역 데이터 폴더 `%APPDATA%\DBeaverData` → `ChromiteData` | `drivers/`(내려받은 드라이버 캐시) | `ChromiteData\drivers` | 다시 내려받기를 물음(데이터 손실 아님) | 하지 않음: 설치된 DBeaver와 같이 쓰던 캐시이고, 제품에 들어 있는 드라이버는 내려받지 않는다 |
| 전역 작업 공간 `DBeaverData\workspace6` | SWT 제품의 기본 작업 공간 | `ChromiteData\workspace6` | Swing 제품은 원래 이 폴더를 쓰지 않음(`-data`나 실행 폴더의 `workspace`) | 하지 않음: 설치된 DBeaver의 작업 공간과 같은 폴더라 Chromite로 가져오면 안 된다 |
| Equinox 보안 저장소 루트 노드, macOS 키체인 모듈 ID | `dbeaver`, `org.jkiss.dbeaver.model.security.MacOSSecureStorageKeychain` | `chromite`, `com.seizia.chromite.…` | 이름 변경 전 Swing 제품은 쓰지 않음(AI 계정 토큰만 씀, 이름 변경 뒤 기능) | 하지 않음 |
| Java 환경 설정 노드 `DeploymentId` | `org/jkiss/dbeaver` | `com/seizia/chromite` | 설치 ID 새로 생성(DataDam 연결에만 쓰임). 설치된 DBeaver와 나눠 쓰지 않게 하려는 의도된 변화 | 하지 않음 |
| 환경 변수·시스템 속성 | `DBEAVER_DATA`, `DBEAVER_WORKSPACE`, `dbeaver.drivers.configuration-file` 등 | `CHROMITE_*`, `chromite.*` | 사용자가 설정해 둔 값이 무시됨 | 하지 않음(사용자 환경). 릴리스 노트에 적을 것 |
| Windows 정책 레지스트리 | `Software\DBeaver Corp\DBeaver\policy` | `Software\Chromite\policy` | DBeaver 관리자 정책이 더는 적용되지 않음(의도) | 하지 않음 |
| 경로 변수 | `${dbeaver_home}` | `${chromite_home}` | 그 변수를 쓴 경로가 풀리지 않음 | 복사한 파일에서 치환 |

### 설계

- **위치.** `DesktopApplicationImpl.beforeWorkspaceInitialization()`에서 부른다. 플랫폼이 작업 공간을 만들기 직전이고
  (`ChromiteSwingPlatform.initialize`), 앱이 작업 공간을 잠근 뒤라 다른 인스턴스와 동시에 복사하지 않는다. 프로젝트와 연결
  레지스트리, 이름이 바뀐 번들의 설정은 이 뒤에 처음 읽힌다. 모델 계층(`model.rcp`)에 두어 UI와 무관하고, 프로젝트 목록은 Eclipse
  작업 공간에서 얻어 작업 공간 밖에 연결한 프로젝트도 포함한다. 예전 가져오기 코드(`ChromiteSettingsImporter`)는 SWT 제품과 함께
  지워졌고(#68) 남은 곳이 없어 새 클래스로 만들었다.
- **복사만 한다.** 옛 파일·폴더·설정 노드는 고치거나 지우지 않는다. 이름 변경 전 빌드로 돌아가도 그대로 쓸 수 있다. 같은 이유로 두 빌드가
  같은 경로를 쓰는 파일(`.metadata/.config`, `.project`, Swing 설정)은 고치지 않고, 같은 노드 안에서 키 이름만 바뀐 설정은 새 키를 옆에
  더한다.
- **있는 것은 건너뛴다.** 새 위치에 파일·설정 키가 있으면 새 빌드에서 만든 값이므로 두고 로그에 남긴다. 파일은 임시 이름으로 쓴 뒤
  옮겨, 중간에 끊겨도 반쯤 쓴 파일이 "있는 파일"로 남지 않는다.
- **한 번만 한다.** 옮긴 뒤 표시 파일 `legacy-migration.properties`를 둔다(작업 공간은 `.metadata`, 프로젝트는 `.chromite`). 표시가 있으면
  다시 복사하지 않는다. "있으면 건너뛴다"만으로는 새 빌드에서 지운 연결 파일이나 기본값으로 되돌린 설정이 다음 시작 때 되살아난다.
  옛 데이터가 없으면 표시를 두지 않는다. 판단은 폴더·파일·표시의 존재로만 하고 제품·번들 버전은 보지 않는다(버전 체계가 26.2.5에서
  0.1.0으로 바뀌어도 영향 없음). 다시 복사하려면 표시 파일을 지운다.
- **이름 치환.** 복사한 텍스트 파일(.json, .xml, .properties, .prefs, .txt와 그 `.bak`)과 설정 키·값에 `rules.py`의 해당 규칙만
  적용한다: `org.jkiss.dbeaver…`(R1, 이름 마디의 `DBeaver<Name>`은 R2, `org.jkiss.dbeaver.DBException`은 유지), R3의
  `com.dbeaver.*` ID, `@dbeaver-`, `${dbeaver_home}`. `DBeaver`라는 단어 전체는 바꾸지 않는다(드라이버 경로의 `DBeaverData` 같은
  사용자 값이 깨진다). 비교는 ASCII라서 latin-1로 읽고 써 다른 바이트는 그대로 둔다.
- **프로젝트 설정 새로 고침.** 프로젝트 설정(`.settings/*.prefs`)은 Eclipse 리소스 트리를 거쳐 읽힌다. 디스크에만 복사하면 트리가
  모르는 파일이라 값이 비어 보인다(검증 중 확인). 그래서 복사한 뒤 열린 프로젝트의 `.settings` 폴더를 새로 고친다. 닫힌 프로젝트는
  열고 새로 고친 뒤에 보인다.
- **비밀번호.** `credentials-config*.json`은 고정 키로 암호화되어 있고 키는 이름 변경 전후 같다. 내용에 바뀐 이름이 없어 바이트 그대로
  복사한다. 복호화하지 않으므로 비밀번호가 메모리나 로그에 나오지 않는다.
- **옛 이름은 이 클래스에만 둔다.** `rules.py` `EXCLUDE_FILES`에 넣어 이름 변경 도구와 게이트(`gate.py`)가 이 파일을 건너뛴다.
- **로그.** 복사한 파일·설정 수, 건너뛴 항목, 실패를 `chromite-debug.log`에 남긴다(값은 남기지 않는다).

### 이전 검증 (2026-11-07)

이름 변경 전 빌드(태그 `pre-internal-rename` = `007d0e6433`, 별도 worktree에서 Swing 제품 빌드)로 작업 공간을 만들었다.
연결 4개(SQL Server, PostgreSQL, MariaDB, Oracle Free; 비밀번호 저장), 연결 폴더, 연결 색, SQL Server 테이블 필터(`cust%`),
SQL Server `provider-properties`(`@dbeaver-show-all-schemas@`), 다크 테마, 편집기 글꼴 17, 확인 설정 키, `org.jkiss.dbeaver.model.sql`
노드의 설정 2개(값 하나는 클래스 이름), 프로젝트 설정 1개, Query Library 항목 1개. 이 작업 공간을 복사해 새 빌드로 열었다.

| 확인 | 결과 |
|---|---|
| 연결과 설정 | 연결 4개, 폴더(Registered Servers의 "Migration Folder"), 색(빨간 `MIG MSSQL`), 필터 `type` = `com.seizia.chromite.ext.mssql.model.SQLServerTable`(dbo 테이블 3개 중 `customers`만 보임), `@chromite-show-all-schemas@`(`db_*` 스키마가 보임), 다크 테마·글꼴 17, `com.seizia.chromite.core.confirm.*`, `com.seizia.chromite.model.sql` 설정 2개(클래스 이름 치환), 프로젝트 설정, Query Library 항목 |
| 연결 | 4개 모두 펼치기(연결)에서 비밀번호 대화상자 없이 연결됨 |
| 비밀번호 파일 | `.chromite/credentials-config.json`이 `.dbeaver`의 것과 바이트까지 같다. 복호화해 보면 키 구조는 데이터 소스 ID → `#connection` → `user`/`password`뿐이고 옛 이름이 없다(값은 확인·기록하지 않음) |
| 옛 데이터 | `.dbeaver/*`, `.settings/org.jkiss.dbeaver*`, `.metadata/dbeaver-*`, `org.jkiss.dbeaver.*.prefs`의 SHA-256과 수정 시각이 실행 전후 같다 |
| 되돌아가기 | 이전한 작업 공간을 이름 변경 전 빌드로 다시 열어도 연결 4개·필터·설정이 그대로 보인다 |
| 두 번째 실행 | 로그에 "already migrated"만 남고 표시 파일이 그대로다. 첫 실행 뒤 지운 `.chromite/.data-sources.json.bak`, 지운 설정 키가 되살아나지 않는다 |
| 새 위치에 파일이 있을 때 | `.chromite/project-settings.json`을 미리 두면 그 파일을 두고("Kept existing …") 나머지 5개만 복사한다 |
| 오류 | `chromite-debug.log`, `.metadata/.log`에 오류 없음 |

### 옛 XML 연결 설정 읽기 삭제

DBeaver 6.1.3 이전 형식(`<프로젝트>/.dbeaver-data-sources*.xml`)을 읽던 코드는 기계적 치환으로 `.chromite-data-sources*.xml`을 찾게
됐다. 이 이름의 파일은 어떤 빌드도 만들지 않으므로 실행되지 않는 코드다. 참조를 모두 확인하고 함께 지웠다:
`DataSourceSerializerLegacy`, `DataSourceFileStorage`의 legacy 표시와 `convertToModern`, `DataSourceConfigurationManagerNIO`의 프로젝트
폴더 검색, `DataSourceRegistry`의 분기 두 곳, `DataSourceConfigurationProfileQuery`의 `.xml` 저장소 걸러내기와 그 테스트,
`DBPDataSourceRegistry.LEGACY_CONFIG_FILE_*`, `DesktopProjectImpl`의 LEGACY 형식 판정과 Eclipse 리소스 속성 이전
(`checkAndUpdateProjectStructure`), `BaseProjectImpl.ProjectFormat.LEGACY`, `DesktopWorkspaceImpl`의 리소스 핸들러 예외, 이 읽기만
쓰던 `RegistryConstants` 8개와 `DBVModel.getModelParser`/`DBVModelSerializerLegacy.ModelParser`.
범위 밖이라 남긴 것: 어디서도 쓰지 않는 `DBPDataSourceRegistry.LEGACY2_CONFIG_FILE_NAME`, 쓰는 곳이 없는 `DBVModel.serialize(XMLBuilder)`
(둘 다 이름 변경 전부터 쓰이지 않음).

## 접두어 의미로 쓰는 곳 (직접 검토)

이름 앞부분으로 "우리 번들"을 가려내는 코드다. 기계적 치환 결과를 하나씩 확인했다.

| 위치 | 치환 후 | 판단 |
|---|---|---|
| `launcher/Constants.java` `PRODUCT_CHROMITE_COMMUNITY_PREFIX` | `"com.seizia.chromite"` | 제품 ID `com.seizia.chromite.core.product`와 맞는다 |
| `WorkbenchPatcher.isContributedByDBeaver` | `com.seizia.chromite` 또는 `com.dbeaver` | SWT 제품의 모든 번들을 가린다 |
| `devtools/IconDialog` | 같은 조건 | 개발 도구, 위와 같다 |
| `ThemeColorOverridePreserver` `CHROMITE_COLOR_ID_PREFIX` | `"com.seizia.chromite."` | plugin.xml 색 ID도 같이 바뀌었다 |
| `DesktopProjectImpl` 리소스 속성 한정자 | `"com.seizia.chromite"` | 쓰는 쪽(`QualifiedName`)도 같은 값으로 바뀌었다. 새로 시작이라 옛 값은 읽지 않는다 |

## 검증 (2026-11-07)

| 항목 | 결과 |
|---|---|
| 도구 | 이동 10,873개(전부 R100), 내용 치환 8,688개 파일(R1 103,531 · R2 1,724 · R3 143 · R4 595 · R5 67 · R6 57 · R7 228 · Vendor 199), 수정 표시 줄 7,089개, 충돌 0 |
| G1–G5, G8 | 옛 이름 0건. G5는 이름 변경 전부터 깨져 있던 upstream 참조 13개만 남음(`gate.py` `KNOWN_BROKEN`, 같은 13개가 변경 전 트리에서도 나옴) |
| G6 | 변경 전 트리(`fe9f9b150f`)의 ID 15,385개(서로 다른 값 9,367개)에 규칙을 적용한 집합 = 현재 집합 |
| G7 | 비교 파일 10,910개, BOM 11 = 11, 줄바꿈 종류(CRLF 614 · LF 10,190 · 혼합 36 · 없음 70) 변경 0, 마지막 줄바꿈 변경 0. 줄 수 증가는 수정 표시 줄과 import 추가·문서 수정분 |
| 전체 빌드 | `-Pproduct-chromite-swt,product-chromite-swing -Dtycho.localArtifacts=ignore`: BUILD SUCCESS (4분 36초) |
| 제품 산출물 | 번들 목록이 변경 전 제품에 규칙을 적용한 것과 같다(SWT Windows 340, SWT macOS 338, Swing Windows 138, Swing macOS 139). `config.ini`, `bundles.info`에 옛 이름 없음(`com.dbeaver.jdbc.api`는 `deps/` 번들). `.eclipseproduct` `name=Chromite`, `id=com.seizia.chromite.product`. macOS `CFBundleIdentifier` `org.jkiss.dbeaver.core.product` → `com.seizia.chromite.core.product`(Swing은 원래 `com.seizia.chromite.swing.product`), 아이콘 `chromite.icns`. 실행 파일 아이콘 교체 오류(`IconExe`) 없음 |
| SWT 시작 | `chromitec -consoleLog -clean`, 새 데이터 폴더: `%APPDATA%\ChromiteData\workspace6`, `.metadata/chromite-debug.log`. 시작 로그는 변경 전 제품과 이름만 다르고, 번들 해석·확장·e4 클래스 오류 0건. 첫 실행 마법사, 주 창(AI Chat 포함), 새 연결 대화상자 확인 |
| Swing smoke | JBR 25(`-vm`), 새 작업 공간. DB 종류 52개·드라이버 180개(변경 전 제품과 ID까지 같음). SQL Server·PostgreSQL·MariaDB·Oracle 연결과 F5, 결과 그리드 편집·저장(DB에서 확인), ERD, 테이블 디자이너, 내보내기(CSV 파일 확인), 가져오기 마법사, 테이블 복사(SQL Server → PostgreSQL, DB에서 확인), SQL Server 백업(파일 확인)·복원 대화상자(백업 세트 읽기), Activity Monitor, 권한·로그인 속성 편집기, Query Library, Registered Servers, 다크 테마·아이콘 스타일, 한국어 화면, 다시 시작 후 저장된 비밀번호로 연결, 필터 `type` = `com.seizia.chromite.ext.mssql.model.SQLServerTable`, `.chromite/data-sources.json`, `chromite-debug.log`, `ChromiteData\drivers` |
| 브랜치 옮기기 | 아래 절차로 임시 브랜치를 옮긴 뒤 devel 병합: 충돌 0, 결과 차이는 브랜치가 바꾼 4개 파일(+23줄)뿐, 게이트 통과 |

### 남은 옛 이름과 이유

- `org.jkiss.dbeaver.DBException`(`deps/`), 위 "바꾸지 않는 것" 표의 외부 값.
- 이름 중간의 `DBeaver`: `addDBeaverDebugLogs`, `isContributedByDBeaver`, `readDBeaverServerInfo`, `uiOpenDBeaverSQLEditor`.
  R2는 이름 앞의 `DBeaver<이름>`만 바꾼다. 동작에는 영향이 없다.
- 대화상자 설정 구역 이름 `"DBeaver.<대화상자>"`(44곳), 문자열 `"DBeaver"`(세션 종류, Eclipse 플러그인 판 제품명 등). 저장 키·문구라 브랜딩 작업에서 고른다.
- `.ide/.idea`(IDEA 설정), `.agents/skills/dbeaver-code-review`, `osgi-app.properties`의 `dbeaver/` 경로: 외부 도구가 읽는 이름.
- 예전 DBeaver 작업 공간 가져오기 코드: `ChromiteSettingsImporter`와 `WORKSPACE_DIR_4`는 SWT 제품과 함께 지워졌고(#68),
  `.chromite-data-sources*` 읽기는 2026-11-07에 지웠다("사용자 데이터 이전"). 옛 이름은 `LegacyNameMigration`에만 남는다.
- import 순서: `com.seizia.chromite.*` import가 예전 `org.jkiss.dbeaver.*` 자리(`org.jkiss.code`와 `org.jkiss.utils` 사이)에 남아
  알파벳 순서가 아니다. 7,000여 파일을 더 고치지 않으려고 그대로 두었다(동작 영향 없음, 파일을 고칠 때 정리).
- Swing 제품은 `-data`가 없으면 작업 공간을 현재 폴더의 `workspace`에 만든다(이름 변경과 무관한 기존 동작, `ChromiteData`에는 드라이버만 둔다).

## 진행 중이던 브랜치 옮기기

이름 변경 전 devel(`fe9f9b150f`)에서 시작한 브랜치는 옛 이름을 쓴다. 그대로 devel을 병합하면 git이 이름 변경을 내용 비교로
추측해야 하고, 거의 모든 줄이 바뀐 작은 파일(MANIFEST.MF, plugin.xml, pom.xml)은 짝을 못 찾아 파일 전체가 충돌한다(실험으로 확인).
그래서 브랜치에도 같은 도구를 돌린 뒤, 그 결과를 "변환된 변경 전 트리" 커밋과의 병합으로 기록한다.
이 커밋(C4, "Mark renamed DBeaver Java files as modified by Chromite")의 트리는 `fe9f9b150f`를 도구로 변환한 결과와 바이트까지 같다.
그래서 이후 `git merge devel`의 병합 기준이 C4가 되고, 새 이름끼리 비교하므로 브랜치가 실제로 바꾼 곳만 남는다.

```bash
# 브랜치 작업 트리에서 (Git Bash). BASE는 이름 변경 직전 devel = 이름 변경 병합 커밋의 첫 번째 부모.
BASE=fe9f9b150f
git fetch origin
git merge $BASE                       # 브랜치가 BASE보다 오래됐으면 먼저(옛 이름끼리 병합)
CONVERTED=$(git log origin/devel --format=%H -1 --grep='^Mark renamed DBeaver Java files as modified by Chromite')
TOOL=$(mktemp -d); git archive origin/devel tools/rename | tar -x -C "$TOOL"   # 이름 변경된 devel의 도구(같은 규칙 버전)
python "$TOOL/tools/rename/rename.py" convert --base $BASE --converted $CONVERTED
git merge origin/devel                # 충돌은 브랜치와 C5(손 수정)가 같은 줄을 고친 곳만 생긴다
python tools/rename/gate.py           # 브랜치가 새로 넣은 옛 이름이 남았는지 확인
```

- `convert`는 `move`, `rewrite`, `headers --base $BASE`를 차례로 실행하고, 결과를 부모 두 개(브랜치 HEAD, `$CONVERTED`)인 커밋으로 남긴다.
  브랜치가 건드리지 않은 파일은 devel과 바이트까지 같아진다.
- 브랜치가 새로 만든 파일도 규칙대로 옮겨지고 바뀐다(예: `org/jkiss/dbeaver/...` 아래 새 클래스 → `com/seizia/chromite/...`).
  브랜치가 새로 만든 Java 파일에 DBeaver 머리말이 있으면 수정 표시 줄도 들어간다.
- 커밋이 적은 브랜치는 패치로 옮겨도 된다: `git diff $BASE...<브랜치> > b.patch`, `rename.py patch b.patch c.patch`,
  devel에서 새 브랜치를 만들어 `git apply --3way c.patch`. 커밋 이력은 하나로 합쳐진다.
- 2026-11-07 시험: `fe9f9b150f`에서 만든 임시 브랜치(새 클래스 `org/jkiss/dbeaver/model/probe/RenameProbe.java`, `MANIFEST.MF`
  Export-Package 추가, `plugin.xml` 주석 추가, 기존 `Log.java` 수정)를 위 절차로 옮겼다. 결과는 아래 "검증"에 적었다.

## upstream 커밋 가져오기

upstream 커밋은 옛 이름을 쓰므로 그대로 `git cherry-pick`하면 충돌한다. 패치로 만든 뒤 도구로 이름을 바꿔 적용한다.

```bash
git -C <dbeaver clone> format-patch -1 <커밋> --stdout > upstream.patch
python tools/rename/rename.py patch upstream.patch chromite.patch
git apply -3 chromite.patch
```

커밋 메시지에 원본 커밋 ID를 적는다(CLAUDE.md "upstream 정책"의 `-x`와 같은 목적).

## 변경 이력

- 2026-11-07 최초 작성(도구와 규칙).
- 2026-11-07 진행 중이던 브랜치 옮기기(`rename.py convert`)와 시험 결과, 검증 결과, 남은 옛 이름을 추가.
- 2026-11-07 "호환성: 새로 시작"을 "사용자 데이터 이전"으로 바꿈: 바뀐 사용자 데이터 이름 목록, 시작 시 한 번 복사하는
  `LegacyNameMigration`, 실행되지 않던 옛 XML 연결 설정 읽기 삭제, 검증 결과.
