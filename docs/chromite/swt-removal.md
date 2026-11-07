# SWT 프런트엔드 삭제

## 목적

Chromite는 Swing 프런트엔드 하나만 배포한다. 사용자가 2026-11-07에 결정했다: 내부 이름 변경([internal-rename.md](internal-rename.md))이
병합된 직후, 기능이 모두 옮겨지기를 기다리지 않고 Eclipse RCP(SWT) 프런트엔드를 삭제한다.
SWT 제품을 함께 빌드·서명·배포하면 CI 시간과 릴리스 절차가 두 배가 되고, 두 UI에 같은 수정을 해야 하는지 매번 따져야 했다.

삭제 직전 커밋은 `263b8ddb45`(devel)다. 이 문서와 다른 문서에서 "SWT 버전", "SWT 구현"으로 가리키는 클래스와 링크는 이 커밋에서 볼 수 있다.

## 남길 번들을 정한 방법 (근거)

1. **출발점:** Swing 제품 [chromite-swing.product](../../product/swing/chromite-swing.product) → `com.seizia.chromite.swing.feature`와
   그 피처가 포함하는 피처(`runtime.feature`, `db.feature`, `drivers.public.feature`)의 플러그인.
2. **닫힘:** 모든 `META-INF/MANIFEST.MF`의 `Require-Bundle`(선택 의존 포함), `Import-Package`(저장소 안의 `Export-Package`로 해석),
   `Fragment-Host`를 따라 저장소 번들을 더했다.
3. **실행 중 확장 참조:** 남는 번들의 `plugin.xml`이 쓰는 확장 포인트를 모두 모아, 선언한 번들이 삭제 대상이거나 Eclipse UI 포인트인 것을 찾았다.
   Oracle의 `org.eclipse.ui.bindings`(삭제한 `ext.oracle.ui`의 명령에 단축키를 다는 것) 하나뿐이어서 지웠다.
   드라이버, 데이터 소스, 데이터 전송 처리기, SQL 방언 같은 모델 확장은 모두 남는 번들(`model*`, `registry`, `ext.*`, `data.*`)에 있다.
4. **테스트:** 남는 모델 번들을 시험하는 테스트 플러그인(`test/`)과 그 기반(`headless`, `osgi.test.runner`, `test.platform`)은 남겼다.

결과는 저장소 번들 186개(플러그인 160, 테스트 26) 중 **남김 107개, 삭제 79개**, 피처 18개 중 **남김 6개, 삭제 12개**다.
Swing 제품의 번들 수는 삭제 전과 같다(Windows x64 138개, macOS arm64 139개).

| 남김 | 번들 |
|---|---|
| Swing 제품 (76) | `ui.swing`, `model*`(admin, ai, cli, dashboard, erd, jdbc, lsm, nio, rcp, security, sql, sql.jdbc), `registry`, `data.transfer`, `data.office`, `data.gis`, `cmp.simple`, `tasks.native`, `net.ssh*`, `slf4j`, `drivers.public`, `ext.*` 모델 번들 52개 |
| 테스트 (28) | `test/` 25개, `headless`, `osgi.test.runner`, `test/…test.platform` |
| 제품에 없던 모델 번들 (4) | `model.datadam`, `model.event`, `model.lsp`, `model.sm`. 삭제 전에도 어떤 제품 피처에도 없었다. SWT와 관계없는 기존 상태라 이번 범위 밖으로 두었다(아래 "남은 일") |

| 삭제한 피처 | 내용 |
|---|---|
| `ce`, `standalone`, `app.base`, `app.local`, `rcp`, `ui`, `ui.extra`, `db.ui`, `debug`, `git`, `ext.office`, `ui.test` | SWT 제품 구성. `ext.office`의 UI 없는 번들(`data.office`, POI)은 Swing 피처가 직접 포함한다 |

| 남긴 피처 | 이유 |
|---|---|
| `swing`, `runtime`, `db`, `drivers.public` | Swing 제품 |
| `test`, `test.base` | 테스트 제품 `product/testing/ChromiteUnitTest.product`(IDEA 작업 공간 생성기, `@RunWithProduct`). `test.feature`에서 `launcher.test`만 뺐다 |

## 삭제한 것

### 번들 (79개)

- **SWT UI 번들:** `ui`, `ui.ai`, `ui.app.config`, `ui.app.devtools`, `ui.app.standalone`(SWT 제품 번들), `ui.charts`, `ui.config.migration`,
  `ui.config.sample`, `ui.dashboard`, `ui.datadam`, `ui.editors.*`(acl, base, connection, data, entity, erd, hex, image, json, locks,
  session, sql, sql.terminal, xml), `ui.forms`, `ui.navigator`, `ui.search`, `ui.swt.macos`, `ui.swt.windows`, `core`(SWT 데스크톱 플랫폼),
  `cmp.simple.ui`, `data.gis.view`, `data.office.ui`, `data.transfer.ui`, `debug.ui`, `net.ssh.ui`, `tasks.ui`, `tasks.ui.view`,
  `tasks.native.ui`, `tasks.sql.ui`, `team.git.ui`.
- **DB별 SWT UI 번들 (32):** `ext.{altibase,athena,bigquery,clickhouse,cloudflare.d1,cubrid,dameng,databend,db2,duckdb,exasol,firebird,gaussdb,
  generic,h2,hana,iotdb,kingbase,mimer,mssql,mysql,oceanbase,oracle,postgresql,snowflake,spanner,sqlite,tidb,tidblake,vertica,yashandb}.ui`,
  `ext.postgresql.debug.ui`.
- **SWT 제품에서만 쓰던 UI 없는 번들:**
  - `launcher`: SWT 제품의 사용자 정의 Equinox 런처. Swing 제품은 기본 `org.eclipse.equinox.launcher`를 쓴다.
  - `debug.core`, `ext.postgresql.debug.core`: 디버거 모델. `org.eclipse.debug.core`에 의존하고 쓰는 곳은 SWT 디버거 UI뿐이었다.
  - `ext.wmi`: WMI 데이터 소스. 번들 자체가 SWT UI(`com.seizia.chromite.ui`, 연결 화면)에 의존한다.
- **테스트:** `launcher.test`, `ui.app.standalone.test`.

### 제품, 빌드, 도구

| 대상 | 변경 |
|---|---|
| `product/community` | 삭제. 제품 아이콘(`icons/`, `icons-sources/`)은 `product/swing/`으로 옮겼다 |
| `product/pom.xml` | `product-chromite-swt` 프로파일 삭제 |
| `plugins/pom.xml`, `features/pom.xml`, `test/pom.xml` | 삭제한 모듈 줄 삭제. `desktop` 프로파일 주석 갱신 |
| `product/swing/chromite-swing.product` | 아이콘 경로 `icons/…`, SWT 제품을 가리키던 주석 갱신. 실행 파일 이름 `chromite-swing`은 유지(아래 결정) |
| `product/testing/ChromiteUnitTest.product` | 삭제한 번들을 가리키던 `<splash>` 삭제 |
| `.github/workflows/build.yml`, `release.yml` | SWT 제품 빌드·업로드·서명·패키징 단계 삭제, 프로파일을 `product-chromite-swing`만 사용([ci.md](ci.md), [signing.md](signing.md)) |
| `tools/build.cmd`, `tools/build.sh`, `osgi-app.properties` | SWT 프로파일·제품 대신 Swing |
| `tools/branding/gen_app_icons.py` | SWT 창 아이콘·About·스플래시·`UIIcon` 로고 생성 삭제, 제품 아이콘 출력 위치 `product/swing/icons*` |
| `tools/rename/gate.py` | `KNOWN_BROKEN`에서 삭제한 번들의 11개 항목 삭제(G5) |

### 함께 쓸모없어진 것

- **NLS 문구 120개:** 남는 번들의 Messages 클래스 필드 중 삭제한 번들에서만 쓰던 것을 필드와 모든 언어의 키까지 지웠다
  (`ModelMessages` 46: Query Manager 로그 보기 등, `DTMessages` 70: 데이터 전송 마법사 페이지·SQL 스크립트 작업, `MySQLMessages` 2,
  `DB2Messages` 1, `RegistryMessages` 1). 판별: 남는 Java 파일에서 `클래스.필드` 참조(정적 import 포함)가 없고, 삭제 전 트리의 삭제 번들에 참조가 있는 것.
  처음부터 어디서도 쓰지 않던 필드는 범위 밖이라 남겼다.
- **확장:** `ext.oracle/plugin.xml`의 `org.eclipse.ui.bindings`(삭제한 명령의 단축키).
- **스키마 메타데이터:** `model.sql/schema/*.exsd` 6개의 `plugin`/`targetNamespace`가 삭제한 `core`·`ui`를 가리켜 `com.seizia.chromite.model.sql`로 고쳤다.

## 옮긴 것

**클래스는 옮기지 않았다.** Swing 번들의 의존 닫힘(위 1–2단계)에 삭제 번들이 하나도 없다. 즉 Swing이 컴파일하거나 실행 중에 불러오는
클래스는 처음부터 모두 남는 번들에 있었다. 이전 Swing 작업에서 필요한 모델 코드는 이미 모델 번들로 옮겨 두었다
(예: `SQLQueryExecuteJob`, [swing-frontend.md](swing-frontend.md) "쿼리 실행 계층").

옮긴 것은 리소스뿐이다.

| 무엇 | 어디서 → 어디로 | 이유 |
|---|---|---|
| 아이콘 6개: `sync_connection.svg`, `file/export.svg`, `file/import.svg`, `misc/browser.svg`, `bookmark_folder.svg`, `misc/sql.svg` | `ui/icons/` → `model/icons/` | 남는 모델 번들의 `plugin.xml` 10곳(`ext.altibase` 탐색기 노드·도구, `ext.mysql`·`ext.postgresql` 내보내기/가져오기 도구, `model.dashboard`, `model.rcp` 북마크 자원, `model.sql` 기본 방언)이 `platform:/plugin/com.seizia.chromite.ui/icons/…`를 가리켰다. Swing 제품에는 `ui` 번들이 없어 원래부터 아이콘을 못 찾고 대체 아이콘을 그렸다(`NodeIcons.resolve`). 아이콘을 모델 번들로 옮기고 참조를 고쳐 근본 원인(모델이 UI 번들 자원을 가리킴)을 없앴다 |
| 제품 아이콘(`chromite.ico`, `chromite.icns`, `chromite_back.bmp`, 아이콘 세트) | `product/community/` → `product/swing/` | Swing 제품이 `../community/icons/…`를 쓰고 있었다 |
| 설치 폴더의 `licenses/`(`docs/root`) | SWT `ce.feature` → `swing.feature`(`build.properties`의 `root`) | 드라이버 정의가 `licenses/external/*.txt`를 라이선스 파일로 가리킨다(`plugin.xml`의 `<file type="license">`). 전에는 SWT 제품에만 들어갔다(followup 54) |

## Swing으로 옮길 때까지 없는 기능

SWT 제품에만 있었고 Swing 번들(`plugins/com.seizia.chromite.ui.swing/src`)에 아직 없는 기능이다. 각 항목은 삭제한 번들과,
Swing 소스에서 해당 모델 API나 기능을 찾지 못한 검색으로 확인했다(2026-11-07, 근거 열).
모델 계층이 남아 있는 기능은 화면만 새로 만들면 된다.
이 표는 큰 화면 단위다. 항목 단위의 전수 대조와 "조용히 끊긴" 기능은 [swt-parity-audit.md](swt-parity-audit.md)에 있다.

| 기능 | 삭제한 SWT 번들 | 남은 모델 | Swing 확인 근거 |
|---|---|---|---|
| PostgreSQL 디버거(프로시저 중단점·단계 실행) | `debug.ui`, `ext.postgresql.debug.ui`, (모델) `debug.core`, `ext.postgresql.debug.core` | 없음(함께 삭제, git 이력) | 2026-11-07 옮김: 모델의 DB 중립 부분과 PostgreSQL 구현을 `org.eclipse.debug.core` 없이 복원, Swing 디버거 탭([procedure-debugger.md](procedure-debugger.md)) |
| 작업(Task) 관리와 예약 실행: 저장한 작업 목록, 스케줄러, SQL 스크립트 작업 | `tasks.ui`, `tasks.ui.view`, `tasks.sql.ui`, `tasks.native.ui` | `model`의 `DBTTask*`, `tasks.native` | `DBTTask`는 데이터 전송 실행(`DataTransferWizard`)에만 쓰임. 작업 저장·예약 화면 없음 |
| SQL 터미널(편집기 아래 콘솔 형식 결과) | `ui.editors.sql.terminal` | - | 2026-11-08 옮김: Query > SQL Terminal, 쿼리 창마다 켜는 Terminal 탭([swing-sql-terminal.md](swing-sql-terminal.md)) |
| 차트, 결과의 GIS(공간 데이터) 보기 | `ui.charts`, `data.gis.view` | `data.gis` | `Chart`·`DBGeometry` 참조 0 |
| 사용자 정의 대시보드(대시보드 편집·여러 대시보드 보기) | `ui.dashboard` | `model.dashboard` | Swing에는 Activity Monitor의 고정 서버 대시보드만 있다([swing-dashboard.md](swing-dashboard.md), 2026-11-07 devel 병합으로 확인) |
| 프로젝트·자원 탐색기(스크립트 폴더, 북마크, 프로젝트 관리) | `ui.navigator`의 프로젝트 보기, `core` | `model`, `model.rcp` | Object Explorer는 연 서버만 보인다(`ObjectExplorerPanel` 루트 주석) |
| Git 연동 | `team.git.ui`(+ EGit/JGit) | - | JGit 참조 0 |
| 스키마 비교 | `cmp.simple.ui` | `cmp.simple` | `CMPCompare` 참조 0 |
| AI 자동완성(AI 채팅은 #92에서 옮김, [ai-agent.md](ai-agent.md)) | `ui.ai` | `model.ai` | Swing에 AI 자동완성 없음 |
| Query Manager(실행 로그 보기) | `ui.editors.sql`(`QueryLogViewer`) | `model`의 QM | QM 보기 없음. Swing에는 자체 쿼리 기록만 있다([swing-sql-editor-plus.md](swing-sql-editor-plus.md)) |
| 데이터 검색(여러 테이블의 값 찾기) | `ui.search` | `model` | `DataSearch` 참조 0. 이름으로 객체 찾기는 있음(`ObjectSearchPanel`) |
| 드라이버 관리의 나머지: Maven 아티팩트 추가·편집, 라이브러리 의존성 트리, 전역 클래스패스 | `ui.editors.connection` | `registry` | 관리자·편집 창·오프라인 모드는 Swing에 있다([swing-driver-manager.md](swing-driver-manager.md) "남긴 것", 2026-11-07 devel 병합으로 확인) |
| 다른 도구의 연결 설정 가져오기(DbVisualizer, Navicat, SQuirreL 등) | `ui.config.migration` | - | 참조 0 |
| 환경설정 화면 대부분(편집기·결과·연결 기본값 등) | `ui`, `ui.editors.*`, `core` | `model` 설정 키 | Swing에는 일부 옵션 대화상자만 있다 |
| DB별 전용 화면: 연결 페이지, 객체 편집기 탭, 도구(예: Oracle 컴파일, PostgreSQL 유지 보수, MySQL 세션) | `ext.*.ui` 32개 | `ext.*` | Swing은 범용 연결 화면과 일부 SQL Server·PostgreSQL·MySQL 도구만 있다([swing-frontend.md](swing-frontend.md) D단계, [swing-conn-advanced.md](swing-conn-advanced.md)) |
| WMI 데이터 소스 | `ext.wmi` | 없음 | Swing 제품에 원래 없었다 |
| HEX·이미지 전용 편집기(값을 편집하는 별도 편집기) | `ui.editors.hex`, `ui.editors.image` | - | Swing 값 패널은 보기만 한다([swing-result-viewer.md](swing-result-viewer.md)) |

## 설계 결정과 이유

- **실행 파일 이름 `chromite-swing`, macOS `ChromiteSwing.app`을 유지한다.** SWT 제품의 `chromite`와 나누려고 붙인 이름이지만,
  바꾸면 서명 대상 경로, 릴리스 파일 이름, macOS 번들 폴더, CI 아티팩트 이름이 함께 바뀐다. 이번 작업은 삭제만 하고 이름 변경은 따로 정한다.
- **`.eclipseproduct`, `readme.txt`(SWT `ce.feature`가 만들던 설치 폴더 파일)는 Swing에 옮기지 않았다.** Swing 제품에는 원래 없었고,
  `.eclipseproduct`는 패치 번호를 읽는 데만 쓰이며 없으면 0으로 본다(`GeneralUtils.readProductProperties`). `readme.txt`는 SWT 실행 옵션 설명이다.
- **`test.base.feature`의 `org.eclipse.pde.spies` 포함은 두었다.** upstream이 "DO NOT REMOVE THIS LINE"을 붙여 둔 줄이고, 테스트 제품에만 쓰인다.
- **`product/testing`(빌드 모듈이 아님)은 `<splash>` 한 줄만 고쳤다.** `pom.xml`과 `product-branding.properties`가 SWT 제품 ID를 가리키는 것은
  이번 삭제 전부터 `.product`의 uid와 맞지 않던 상태다([branding.md](branding.md) "남은 차이").
- **모델 API는 지우지 않았다.** 남는 번들에는 SWT UI만 쓰던 모델 클래스·메서드가 많다(위 기능 목록의 "남은 모델"). 이것들은 Swing으로 옮길 때의 바탕이라 남겼다.
  지운 것은 NLS 문구, 확장, 리소스처럼 삭제한 화면에만 묶인 것이다.
- **`deps/`는 건드리지 않았다.** `deps/datadam-api`는 `model.datadam`(제품에 없던 번들)만 쓰고, 삭제 전과 같다.

## upstream 대비 변경점

DBeaver CE의 Eclipse RCP 제품, SWT UI 번들 79개, SWT 전용 피처 12개, `product/community`가 없다. 제품은 Swing 하나다.

## 남은 일

- `model.datadam`, `model.event`, `model.lsp`, `model.sm`: 어떤 제품에도 없고 빌드만 된다(삭제 전부터). 지울지 따로 정한다.
- Swing 번들 Javadoc의 "SWT 버전은 `com.seizia.chromite.core`의 …" 같은 설계 참고 문구는 옮겨 온 원본을 가리키는 기록이라 두었다(삭제 직전 커밋에서 볼 수 있음).
- `DashboardConstants.DASHBOARDS_LEGACY_PLUGIN_ID2`(`"com.seizia.chromite.ui.dashboard"`)는 예전 대시보드 설정 폴더 이름을 읽는 값이라 두었다.
- 위 "Swing으로 옮길 때까지 없는 기능".

## 검증 (2026-11-07)

| 항목 | 결과 |
|---|---|
| 빌드 | `mvn -B clean package -f product/aggregate/pom.xml -T1C -Pproduct-chromite-swing`(JBR 25, Maven 3.9): BUILD SUCCESS. 테스트 플러그인도 컴파일됨 |
| 제품 | 번들 Windows x64 138개, macOS arm64 139개(삭제 전과 같음). 설치 폴더에 `licenses/`, `licenses/external/*.txt` 있음. `swt`·`jface`·`org.eclipse.ui`·`e4.ui` 번들 0개 |
| 게이트 | `python tools/rename/gate.py` 통과(G5 알려진 upstream 결함 2개만 남음) |
| Swing smoke | JBR 25(`-vm`), 새 작업 공간, EDT 테스트 에이전트. SQL Server·PostgreSQL 연결, 트리 펼치기, F5 실행(SQL Server 집계 쿼리, PostgreSQL Select Top 1000), ERD(두 DB), 테이블 디자이너, 내보내기(CSV 파일 확인), 가져오기 마법사, SQL Server 백업 실행(백업 파일 확인)·복원 대화상자, PostgreSQL 백업 대화상자, 한국어 화면(OS 기본) |
| 로그 | `chromite-debug.log`, `.metadata/.log`에 오류 없음 |
| 참조 검색 | 삭제한 번들·피처·제품 ID를 저장소 전체(`deps/` 제외)에서 찾음. 남은 것은 변경 이력·검증 기록, 위 "남은 일"의 기록성 참조, `rules.py`의 이름 변경 규칙(R6), `product/testing`의 기존 불일치, 테스트의 URL 문자열 예시(`RuntimeUtilsTest`) |

회귀 테스트(`mvn verify`)는 요청이 없어 실행하지 않았다.

## 변경 이력

- 2026-11-07 최초 작성. SWT 프런트엔드 삭제(번들 79개, 피처 12개, `product/community`, SWT 프로파일과 CI 단계), 남길 번들 판별 방법, 옮긴 리소스, 없어진 기능 목록.
- 2026-11-07 devel 병합(#65 드라이버 관리자, #66 내장 런타임): 런타임 단계·`BundleJre.java`를 Swing 제품만 대상으로, 드라이버 관리자·오프라인 모드를 없는 기능 목록에서 뺌.
- 2026-11-07 전수 조사 문서 [swt-parity-audit.md](swt-parity-audit.md) 연결, AI 채팅 줄 갱신(#92에서 옮김).
- 2026-11-07 PostgreSQL 디버거 줄 갱신: 모델 복원과 Swing 디버거([procedure-debugger.md](procedure-debugger.md)).
