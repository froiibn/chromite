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

## 호환성: 새로 시작

기존 Chromite 사용자가 없으므로 옛 데이터를 가져오는 코드는 넣지 않았다(사용자 결정 5, 6).

| 항목 | 이전 | 이후 |
|---|---|---|
| 데이터 폴더 | `%APPDATA%\DBeaverData` (설치된 DBeaver와 같은 폴더) | `%APPDATA%\ChromiteData` |
| 프로젝트 메타데이터 폴더 | `.dbeaver` | `.chromite` |
| 로그 | `.metadata/dbeaver-debug.log` | `.metadata/chromite-debug.log` |
| 연결 필터 `type`(`data-sources.json`) | `org.jkiss.dbeaver.model.…` 클래스 이름 | `com.seizia.chromite.model.…` |
| 보안 저장소 루트, macOS 키체인 서비스 | `dbeaver`, `com.dbeaver.SecureStorage` | `chromite`, `com.seizia.chromite.SecureStorage` |
| Java 환경 설정 노드(`DeploymentId`) | `org/jkiss/dbeaver` | `com/seizia/chromite` |
| Windows 정책 레지스트리 | `Software\DBeaver Corp\DBeaver\policy` (DBeaver 관리자 정책이 Chromite에도 적용됨) | `Software\Chromite\policy` |

저장된 비밀번호와 필터는 다시 만들어야 한다. 개발용 smoke 작업 공간도 새로 만든다.

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
- 예전 DBeaver 작업 공간 가져오기 코드(`ChromiteSettingsImporter`, `WORKSPACE_DIR_4`, `.chromite-data-sources*`)는 이름만 바뀌어
  이제 Chromite 옛 형식을 찾는다. 새로 시작하므로 실제로는 쓰이지 않는다. 지울지는 따로 정한다.
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
