# 오프라인 드라이버 설치 묶음

## 목적

고객사 폐쇄망 PC에는 인터넷이 없어, Chromite에 내장되지 않은 드라이버(H2, Derby, DuckDB, ClickHouse 등)를 처음 쓸 때 내려받을 수 없다.
지금까지는 jar 파일을 구해 드라이버 설정의 Add File로 하나씩 지정해야 했다(Maven 전이 의존성까지 손으로 찾아야 함).

사용자 결정(2026-11-07, 선택지 A):

- 인터넷이 되는 PC의 드라이버 관리자에서 필요한 드라이버를 하나 또는 여러 개 골라 **설치 묶음 파일 하나**를 만든다.
- 폐쇄망 PC의 드라이버 관리자에서 그 파일을 설치하면, 그 드라이버들이 인터넷 없이 연결된다.
- "모든 드라이버"도 고를 수 있지만 기본이 아니다(약 1.7 GB, [drivers.md](drivers.md)).
- 내장 드라이버(SQL Server, Oracle, PostgreSQL, MariaDB, SQLite)는 이 과정이 필요 없다. MySQL은 2026-11-07부터 내장하지 않으므로 폐쇄망에서는 이 묶음으로 옮긴다([drivers.md](drivers.md)). 내장 드라이버는 내려받을 파일이 없어 목록에 나오지 않는다.
- 기존의 jar·폴더 직접 추가(Add File/Add Folder)는 그대로 둔다. 공개 저장소에 없는 벤더 전용 드라이버는 계속 그 방법을 쓴다.

## 사용 흐름

| PC | 위치 | 동작 |
|---|---|---|
| 인터넷 PC | 도구 > 드라이버 관리자 > **오프라인 묶음 만들기** | 드라이버 체크 목록(검색, 모두 선택/선택 해제, "이 PC의 파일" 다운로드됨/안 됨). 관리자에서 고른 드라이버가 처음부터 체크돼 있다. **만들기** → 저장할 파일(기본 이름 `chromite-drivers-YYYYMMDD.chromite-drivers`) → 진행 대화상자(취소 가능) → 결과(드라이버 수, 파일 수, 크기, 넣지 못한 선택 파일) |
| 폐쇄망 PC | 도구 > 드라이버 관리자 > **오프라인 묶음 설치** | 파일 고르기 → 진행 대화상자에서 전체 검사 → 미리 보기(드라이버·버전·상태, 파일 수·크기·이미 있는 수, 내용이 달라 바뀔 파일 목록, 다른 플랫폼에서 만든 묶음 안내) → **설치** → 결과(설치한 드라이버 수, 복사·이미 있음·바뀜 수) |

미리 보기의 드라이버 상태:

| 상태 | 뜻 |
|---|---|
| 설치 예정 (Will be installed) | 새 파일이 있거나 같은 파일이 있어도 아직 이 작업 공간의 드라이버에 등록되지 않음 |
| 이미 설치됨 (Already installed) | 같은 파일(크기·SHA-256)이 있고 같은 파일로 등록돼 있음. 모두 이 상태면 설치 단추가 꺼진다 |
| 설치 예정. 빠진 라이브러리: … (Will be installed, but missing: …) | 이 Chromite의 드라이버 정의에는 있는데 묶음에 없는 필수 라이브러리가 있음(다른 버전 Chromite나 다른 플랫폼에서 만든 묶음) |
| 이 Chromite에 없음: 건너뜀 (Not in this Chromite: skipped) | 공급자·드라이버 ID가 이 Chromite에 없음(사용자 정의 드라이버, 더 새 Chromite가 만든 묶음) 또는 묶음의 라이브러리가 이 드라이버 정의에 하나도 없음 |

## 설치 위치와 이유

- **위치:** 온라인 다운로드가 쓰는 드라이버 폴더 그대로다. `DriverDescriptor.getCustomDriversHome()` = `<데이터 폴더>\drivers`
  (Windows 기본 `%APPDATA%\ChromiteData\drivers`, `CHROMITE_DATA`로 바뀜, 설정 `ui.drivers.home`이 있으면 그 폴더, [workspace-location.md](workspace-location.md)).
  Maven 파일은 `maven/<저장소 ID>/<groupId>/<artifactId>-<version>[-classifier].jar`(`MavenRepository.getLocalCacheDir`,
  `MavenArtifactVersion.getCacheFile`), URL 파일은 `remote/<URL 경로>`(`DriverLibraryRemote.getLocalFilePath`), `repo:` 파일은 `<repo 경로>`다.
- **새 조회 경로 없음:** 파일을 같은 상대 경로에 놓고, 평소 다운로드가 끝났을 때와 같이 드라이버 로더에 "해석된 파일"로 등록한다
  (`DriverLoaderDescriptor`의 `resolvedFiles`, `drivers.xml`의 `<library>` 아래 `<file>`). 로더는 등록된 파일이 모두 있으면 Maven 해석도
  다운로드도 하지 않으므로(`getDriverDependencies`가 `null`) 드라이버 관리자에 "다운로드됨"으로 보이고 인터넷 없이 로드된다.
  `drivers.xml`은 경로를 `${drivers_home}`으로 바꿔 저장한다.
- **설치 폴더를 쓰지 않은 이유:** 설치 폴더의 `maven-local`도 로더가 먼저 찾는 곳이지만([drivers.md](drivers.md)), 관리자 설치면 쓸 수 없고
  자동 업데이트가 설치 폴더를 통째로 바꾼다([auto-update.md](auto-update.md)). 드라이버 폴더는 사용자별이라 항상 쓸 수 있고 업데이트가 건드리지 않는다.
- **Maven 해석을 다시 하지 않는 이유:** 많은 드라이버가 `RELEASE`(최신) 버전으로 정의돼 있어, 해석하려면 원격 `maven-metadata.xml`이 필요하다
  (오프라인 모드에서는 막힘). 그래서 인터넷 PC가 해석한 결과(라이브러리별 파일 목록)를 매니페스트에 담아 그대로 등록한다.
- **작업 공간별 등록:** 등록 정보(`drivers.xml`)는 작업 공간의 `.metadata/.config/`에 있다. 같은 PC에서 다른 작업 공간을 쓰면 같은 묶음을 한 번 더
  설치한다. 파일은 이미 있으므로 복사하지 않고 등록만 한다.

## 묶음 파일 형식 (버전 1)

- 확장자 `.chromite-drivers`, 내용은 평범한 ZIP이다. 항목은 아래 둘뿐이고 다른 항목이 있으면 설치하지 않는다.
  - `chromite-driver-bundle.json`: 매니페스트(UTF-8 JSON). 파일들을 쓴 뒤 마지막에 넣는다(쓰면서 계산한 SHA-256을 담으므로).
  - `files/<드라이버 폴더 기준 상대 경로>`: 파일. 예: `files/maven/maven-central/com.h2database/h2-2.4.240.jar`. 압축 수준은 가장 빠른 것(jar는 이미 압축돼 있음).
- 매니페스트:

| 키 | 내용 |
|---|---|
| `format` | `"chromite-driver-bundle"` |
| `formatVersion` | `1`. 다른 값이면 "지원하지 않는 형식 버전"으로 설치하지 않는다 |
| `chromiteVersion` | 만든 Chromite 버전(`GeneralUtils.getProductVersion()`) |
| `created` | 만든 시각(UTC, ISO-8601) |
| `os`, `arch` | 만든 PC의 플랫폼(`Platform.getOS()`, `getOSArch()`, 예: `win32`, `x86_64`) |
| `drivers[]` | `provider`(공급자 ID), `id`(드라이버 ID), `name`, `libraries[]` |
| `drivers[].libraries[]` | `id`(드라이버 정의의 라이브러리 ID, 설치할 때 이것으로 맞춘다), `path`(정의 경로, 예 `maven:/com.h2database:h2:RELEASE`), `type`, `files[]` |
| `drivers[].libraries[].files[]` | 해석된 파일. 첫 항목이 라이브러리 자신, 나머지는 Maven 의존성(로더의 `collectLibraryFiles`와 같은 순서). `id`, `version`, `type`, `path`(상대 경로), `coordinates`(Maven `group:artifact[:classifier]:version`), `url`(받은 주소), `licenses`(POM이나 부모 POM의 라이선스 이름과 URL) |
| `files[]` | 묶음의 모든 파일: `path`, `size`, `sha256`(소문자 16진수). 같은 파일은 한 번만 들어간다(예: Derby의 `derbyshared`는 세 라이브러리가 함께 씀) |

- 라이선스: 드라이버 정의에 라이선스 파일(URL·`repo:`)이 있으면 평소 다운로드처럼 받아서 넣는다(예: H2·DuckDB의 `LICENSE`).
  모든 Maven 파일은 좌표와 POM의 라이선스 이름을 매니페스트에 적으므로, 넣지 않은 라이선스 원문도 찾아볼 수 있다.

## 만들기 (인터넷 PC)

[DriverOfflineBundleWriter](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverOfflineBundleWriter.java):

1. 드라이버마다 이 PC에서 내려받을 라이브러리(꺼지지 않음, `isDownloadable`, `matchesCurrentPlatform`)를 모은다.
2. `DriverDependencies.resolveDependencies`로 Maven 전이 의존성을 해석하고, 각 라이브러리의 `downloadLibraryFile`로 받는다.
   평소 다운로드와 같은 코드라 Maven·URL·`repo:` 모두 같고, 이미 받은 파일은 다시 받지 않는다. 다운로드 확인 대화상자는 띄우지 않는다.
3. 필수 파일을 받지 못하면 묶음을 만들지 않고 오류를 보인다. 선택(`optional`) 라이브러리와 라이선스 파일은 받지 못하면 빼고 결과에 적는다
   (평소 다운로드도 이 둘 없이 연결된다).
4. 받은 파일을 이 PC의 드라이버에도 등록한다(평소 다운로드와 같음). 그래서 만든 뒤 이 PC에서도 "다운로드됨"이 된다.
5. 같은 폴더의 `<이름>.part`에 쓰고 끝나면 제자리로 옮긴다. 실패·취소하면 `.part`를 지워 반쪽 묶음이 남지 않는다.
6. 드라이버 폴더 밖의 파일(설치 폴더의 `maven-local`에서 해석된 것)은 다른 PC에 놓을 곳이 없으므로 오류로 멈춘다. 공개판에는 `maven-local`이 없다.

## 설치 (폐쇄망 PC)

[DriverOfflineBundleInstaller](../../plugins/com.seizia.chromite.registry/src/com/seizia/chromite/registry/driver/DriverOfflineBundleInstaller.java):

1. **검사(아무것도 쓰지 않음):** ZIP 중앙 디렉터리 검사(아래 "보안") → 매니페스트 검사(형식·버전, 필수 키, 경로, `files[]`와 실제 항목이
   정확히 같은지, 라이브러리 파일이 모두 `files[]`에 있는지) → 모든 파일의 크기와 SHA-256 확인. 하나라도 틀리면
   "이 드라이버 묶음은 쓸 수 없습니다: … 아무것도 설치하지 않았습니다."로 끝나고 미리 보기를 열지 않는다.
2. **미리 보기:** 드라이버를 공급자·ID로 찾고(`DataSourceProviderDescriptor.getDriver`), 라이브러리를 ID로 맞춘다. 대상 파일마다
   없음(새 파일) / 같음(크기·SHA-256이 같음, 복사하지 않음) / 다름을 판단한다. 내용이 다른 파일은 경로를 목록으로 보이고, 설치를 누르면 바꾼다.
   조용히 덮어쓰지 않는다.
3. **설치:** 필요한 파일을 드라이버 폴더 안 임시 폴더(`.bundle-install-<UUID>`)로 풀면서 SHA-256을 다시 확인한다(미리 보기 뒤에 파일이
   바뀌었을 수 있음). 모두 맞을 때만 제자리로 옮기고, 임시 폴더는 항상 지운다. 그다음 드라이버에 등록하고 `drivers.xml`을 저장한다.
   이미 로드된 드라이버는 클래스 로더를 버려 다음 연결이 새 파일을 쓴다(다른 라이브러리의 등록은 유지).
4. **네트워크:** 쓰지 않는다. Maven 해석·메타데이터 요청이 없고 오프라인 모드와 관계없이 같다.

## 보안과 상한

- **zip-slip:** 항목 이름과 매니페스트 경로는 `/`로 나눈 상대 경로만 받는다. 빈 조각, `.`, `..`, 앞의 `/`, 드라이브 문자·`:`, `\`, 제어 문자,
  Windows 금지 문자(`<>"|?*`), `.`이나 공백으로 끝나는 조각(Windows가 지워서 다른 이름이 같은 파일이 됨)을 거부한다.
  풀 위치는 정규화한 뒤 드라이버 폴더 안인지 다시 확인한다.
- **링크:** `java.util.zip.ZipFile`은 항목의 Unix 모드를 알려 주지 않아 중앙 디렉터리를 직접 읽는다. 모드가 심볼릭 링크(`0120000`)인 항목이
  있으면 거부한다. 파일은 항상 일반 파일로만 쓰고, 대상이나 그 사이 폴더가 링크이거나 다른 종류의 항목이면 설치하지 않는다.
- **중복 이름:** 같은 이름의 항목이 둘 있으면 거부한다(ZIP 리더마다 다른 항목을 읽는 문제).
- **상한:** 항목 10,000개, 전체 3 GiB, 매니페스트 16 MiB, 중앙 디렉터리 32 MiB. 항목을 읽을 때 선언한 크기를 넘으면 멈춘다.
  ZIP64(4 GiB나 65,535개 초과)는 상한을 넘으므로 읽지 않는다. "모든 드라이버"(약 1.7 GB)는 상한 안이다.

## 플랫폼

- 라이브러리는 만든 PC의 OS·아키텍처에 맞는 것만 넣는다(해석기가 돌려주는 그대로). 미리 보기는 만든 플랫폼이 다르면 안내를 보인다.
- 대부분의 드라이버는 순수 Java jar라 Windows에서 만든 묶음이 macOS·Linux에서도 그대로 쓰인다. 상대 경로는 `/`로 적고 설치하는 PC의
  드라이버 폴더 아래에 놓으므로 경로 차이도 없다.
- **DuckDB:** Windows x86_64에서 받은 `duckdb_jdbc-2.0.0.1.jar` 하나에 `libduckdb_java.so_windows_amd64`, `_linux_amd64`, `_linux_arm64`,
  `_macos_arm64`가 들어 있다(2026-11-07 확인). 그래서 Windows x86_64에서 만든 묶음은 Apple Silicon Mac에서 쓸 수 있지만, 인텔 Mac(해당 네이티브 없음)과
  Windows ARM64(정의상 다른 jar `duckdb_jdbc:windows_arm64`)에서는 안 된다. Windows ARM64용은 그 플랫폼에서 묶음을 만든다.
- macOS에서 실제로 설치·연결해 보지는 않았다(macOS PC 없음).

## 라이선스 메모 (법률 자문 아님)

묶음에 든 파일은 사용자가 온라인 다운로드와 같은 공개 저장소(Maven Central, 벤더 URL)에서 직접 받은 것이다. Chromite는 드라이버 jar를
호스팅하거나 재배포하지 않고, 같은 조직 안에서 사용자가 받은 파일을 다른 PC로 옮기는 도구만 준다. 그래서 설치 파일에 내장할 수 없는
라이선스 B 분류(벤더 약관, [driver-licenses.md](driver-licenses.md))도 이 방법을 막지 않는다. 다만 각 드라이버의 약관(사용 범위, 사내 복사 조건)은
사용자가 지켜야 한다. 매니페스트의 `coordinates`·`url`·`licenses`와 함께 넣은 라이선스 파일로 확인할 수 있다.

## 구조

| 위치 | 역할 |
|---|---|
| `com.seizia.chromite.registry.driver.DriverOfflineBundle` (새) | 형식 상수·상한, 매니페스트 클래스(Gson), 경로 검사, 중앙 디렉터리 검사, SHA-256, 로더 등록(`registerLibraryFiles`) |
| `com.seizia.chromite.registry.driver.DriverOfflineBundleWriter` (새) | 만들기: 해석, 다운로드, 파일 수집, ZIP 쓰기 |
| `com.seizia.chromite.registry.driver.DriverOfflineBundleInstaller` (새) | 검사·미리 보기(`read`), 설치(`install`) |
| `RegistryMessages` (`driver_bundle_*`) | 모델 오류·진행 문구(영어, 한국어) |
| `com.seizia.chromite.ui.swing.DriverBundleCreateDialog` (새) | 만들기 창(체크 목록) |
| `com.seizia.chromite.ui.swing.DriverBundleInstallDialog` (새) | 파일 고르기, 검사, 미리 보기, 설치 |
| `DriverManagerDialog` | 오른쪽 단추 두 개(오프라인 묶음 만들기, 오프라인 묶음 설치) |
| `DriverManagerMessages` (`drivermgr_button_bundle_*`, `driverbundle_*`) | 화면 문구(영어, 한국어) |

- 모델 클래스는 UI에 의존하지 않는다(AGENTS-Architecture.md). 진행은 `DBRProgressMonitor`, Swing은 `ProgressDialog`로 돌리고 취소는 모니터로 전한다.
- 새 번들·의존성·확장 포인트는 없다. Gson은 `com.seizia.chromite.model`이 다시 내보내는 것을 쓴다.
- 관리자 트리는 한 드라이버만 고르는 편집용(Edit·Copy·Delete)이라 그대로 두고, 만들기 창에 체크 목록을 따로 두었다.

## upstream 대비 변경점

upstream(DBeaver)에는 같은 기능이 없다. SWT의 "드라이버 라이브러리 내보내기"(`DriverEditHelpers.exportDriverLibraries`)는 한 드라이버의 파일을
폴더로 복사할 뿐 다른 PC에 등록하는 방법이 없고 Swing으로 옮기지 않았다([swing-driver-manager.md](swing-driver-manager.md) "남긴 것").
기존 모델 클래스는 고치지 않았다(문구 클래스 `RegistryMessages`에 키만 추가).

## 확인 결과 (2026-11-07, Windows x86_64 Swing 제품, 한국어 화면)

이 브랜치의 빌드를 두 벌 복사하고, 작업 공간은 `D:\Chromite-Test\workspace`의 복사본, 데이터 폴더(`CHROMITE_DATA`)는 빈 폴더로 실행했다.
화면 조작은 앱 프로세스 안에서 그 앱의 창에만 했다(화면 좌표·전면 창 입력 없음). 증거(창 캡처, 명령·결과 기록 `evidence.txt`, `drivers.xml`, 디버그 로그,
매니페스트)는 `D:\Tools\_scratch\driver-offline-bundle\evidence\`에 있다.

1. **사본 1(온라인):** 만들기 창에서 H2 Embedded, Derby Embedded, DuckDB를 체크하고 만들었다. 결과 "드라이버 3개, 파일 7개, 82MiB".
   Derby의 `derby`·`derbytools`가 Maven 전이 의존성으로 가진 `derbyshared-10.17.1.0.jar`는 파일 목록에 한 번만 들어갔다. 만든 뒤 이 PC에서 세 드라이버가 "다운로드됨"이 됐다.
   두 번째 묶음(H2 Embedded (legacy), 2개 파일, 2.1MiB)도 만들었다. `repo:` 라이브러리는 쓰이지 않았다(dbeaver.io 요청 없음).
2. **사본 2(오프라인 모드 켬):** 설치 전 세 드라이버는 "다운로드 안 됨", H2의 라이브러리 탭은 "다운로드 안 됨(오프라인 모드)". 미리 보기 "파일 7개, 82MiB: 새 파일 7개" →
   설치 "드라이버 3개를 설치했습니다. 파일 복사 7개" → 라이브러리 탭 "다운로드됨". 새 로컬 DB 파일로 연결을 만들어 연결 테스트와 쿼리를 했다.
   H2 `SELECT H2VERSION()` → 2.4.240, Derby `VALUES (SYSCS_UTIL.SYSCS_GET_DATABASE_NAME(), 1 + 1)` → DB 경로와 2,
   DuckDB `SELECT version()` → v2.0.0. 다운로드 대화상자는 한 번도 열리지 않았고 디버그 로그에 다운로드·원격 요청이 없었다.
3. **변조:** jar 1바이트를 바꾼 묶음(매니페스트 그대로), ZIP 원본의 압축 데이터 1바이트를 바꾼 묶음은 "파일 '…h2-1.4.199.jar'의 SHA-256 체크섬이 맞지 않습니다",
   `files/../../evil.jar`(매니페스트에도 적음)·`/tmp/evil.jar`·심볼릭 링크 항목은 "안전하지 않은 항목", 깨진 매니페스트는 "매니페스트가 없거나 형식이 잘못되었습니다"로
   미리 보기 없이 끝났다. 매번 드라이버 폴더의 파일 목록·크기·SHA-256이 그대로였다. 같은 H2 legacy 묶음의 원본은 설치됐다(복사 1, 이미 있음 1: 공유 LICENSE.txt).
4. **두 번 설치:** 두 번째 미리 보기는 세 드라이버 모두 "이미 설치됨", "새 파일 0개, 이미 있음 7개"이고 설치 단추가 꺼졌다. 파일 7개, `drivers.xml`의 `<file>` 9개(라이브러리별 등록)로 늘지 않았다.
5. **다른 파일:** 설치된 H2 `LICENSE.txt`를 고치자 미리 보기에 "내용이 다름 1개"와 그 경로가 보였고, 설치 뒤 SHA-256이 묶음의 값(`827b84bb…`)으로 돌아왔다.
6. **이 Chromite에 없는 드라이버:** 드라이버 ID만 바꾼 묶음은 "이 Chromite에 없음: 건너뜀"으로 보이고 설치 단추가 꺼졌다.

빌드 중 확인에서 고친 것: 만들기 창의 행 정렬기를 행을 채우기 전에 만들어 체크할 때 `IndexOutOfBoundsException`이 났다(정렬기가 만든 시점의 행 수 0을 기억함). 행을 채운 뒤 만들도록 고쳤다.
회귀 테스트는 작성·실행하지 않았다(요청 없음).

## 한계

- 기본 로더의 라이브러리만 넣는다. 인증 모델이 더하는 라이브러리(`DBPDriverLibraryProvider`, 예: 일부 클라우드 IAM 인증)는 넣지 않는다.
- 설치 중 파일 옮기기 단계에서 실패하면(예: 다른 프로그램이 대상 파일을 잠금) 이미 옮긴 파일은 남는다. 모두 검증을 마친 파일이고 등록은 하지 않는다.
- 다른 버전의 Chromite가 만든 묶음은 라이브러리 ID가 같으면 그대로 등록한다. 버전이 달라도 묶음의 버전을 쓴다.
- 네이티브 클라이언트(`pg_dump` 등)는 드라이버가 아니라 넣지 않는다.

## 변경 이력

- 2026-11-07 최초 작성. 드라이버 관리자의 오프라인 묶음 만들기·설치, 형식 버전 1, 드라이버 폴더 설치와 로더 등록, 검사·zip-slip·상한.
- 2026-11-07 MySQL 내장 취소에 맞춰 내장 드라이버 목록에서 MySQL을 뺌([drivers.md](drivers.md)).
