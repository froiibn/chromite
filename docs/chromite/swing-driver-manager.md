# Swing 드라이버 관리자 (Driver Manager, 드라이버 편집)

## 목적

Swing 프런트엔드에서 JDBC 드라이버를 만들고, 복사하고, 고치고, 지울 수 있게 한다(SWT `DriverManagerDialog`/`DriverEditDialog`의 Swing 판).
가장 중요한 쓰임은 **폐쇄망**이다. 공개판에 내장되지 않은 드라이버([drivers.md](drivers.md))는 인터넷이 없으면 내려받을 수 없으므로,
사용자가 가진 jar 파일이나 폴더를 드라이버 라이브러리로 직접 지정해야 한다. 라이브러리마다 출처(내장·로컬 파일·다운로드됨·다운로드 안 됨·없음)를
보여 주고, 다운로드는 오프라인 모드(`ui.drivers.offline`)가 꺼져 있을 때만 사용자가 눌러서 한다.

## 화면

| 위치 | 항목 | 동작 |
|---|---|---|
| Tools 메뉴 | **Driver Manager…** (Find Objects 아래 구분선 다음, Options 위) | 드라이버 관리자 창 |
| Connect 대화상자 | Database type 콤보 오른쪽 **Edit Driver…** | 고른 드라이버의 편집 창. 바꾸면 입력한 값을 지키며 설정 화면을 다시 만든다(URL 템플릿·기본값이 칸 구성을 정하므로) |

### Driver Manager 창

- 왼쪽: 검색 칸(이름·설명·범주·ID, 대소문자·공백 무시), 범주별 트리(범주 폴더 + 범주 없는 드라이버, 이름순), 아래에 고른 드라이버의 설명.
  검색하는 동안에는 범주를 모두 펼치고 처음 맞는 드라이버를 고른다(Enter = Edit).
- 오른쪽 단추: **New…**, **Copy…**, **Edit…**(두 번 누르기·Enter도), **Delete**, **Restore Deleted…**, **Create Offline Bundle…**, **Install Offline Bundle…**,
  **Global Libraries…**(아래 "전역 라이브러리"),
  그 아래 범례(사용자 정의 = 전구 덧그림, 로드 실패 = 오류 덧그림). 오프라인 묶음 두 단추는 폐쇄망 PC로 드라이버 파일을 옮기는 기능이다([driver-offline-bundle.md](driver-offline-bundle.md)).
- 아래: **Offline mode (never download driver files)** 체크 상자, Close.

### 드라이버 편집 창 (New / Copy / Edit)

| 탭 | 내용 |
|---|---|
| Settings | Driver name, Driver type(공급자. 새 드라이버만 고를 수 있음), Class name, URL template, Default port / database / user, 옵션(Embedded, Propagate driver properties, No authentication, Allow empty password, Use legacy JDBC instantiation, Thread-safe driver), Description(ID, Provider ID, 설명, 웹 사이트) |
| Libraries | 라이브러리 트리(이름 [버전] + 상태, 내려받은 Maven 라이브러리는 실제 파일을 자식으로), Add File… / Add Folder… / Add Artifact… / Edit… / Details… / Remove / Download/Update, Driver class 콤보 + Find Class, 폐쇄망 안내 |
| Default Properties | 모든 연결의 기본 드라이버 속성(사용자 속성 표) |
| Advanced Parameters | 공급자의 드라이버 매개 변수와 기본값 |
| Local Client | 공급자가 네이티브 클라이언트 관리자일 때만(PostgreSQL, MySQL·MariaDB, Oracle 공급자). 아래 "Local Client" |
| License | 드라이버에 라이선스 글이 있을 때만 |

- 아래: **Reset to Defaults**(기본 제공 드라이버만), 이름이 비면 오류 문구와 함께 OK가 꺼진다, OK / Cancel.
- 공급자가 드라이버를 관리할 수 없으면(`isDriversManageable() == false`) 설정 칸은 읽기 전용이고 안내 문구가 보인다. 라이브러리는 바꿀 수 있다(SWT와 같음).
- 기본 제공 드라이버의 이름은 바꿀 수 없고, URL 템플릿이 쓰이지 않는 드라이버(`isSampleURLApplicable() == false`)의 URL 템플릿도 바꿀 수 없다(SWT와 같음).

### 라이브러리 상태

| 상태 | 판단 (`DriverLibrariesPanel.getState`) |
|---|---|
| 내장 (Bundled) | 내려받지 않는 라이브러리 중 파일이 설치 폴더(`Platform.getInstallLocation()`, 공개판의 `drivers/`) 안에 있거나 `bundle:` 라이브러리 |
| 로컬 파일 (Local file) | 내려받지 않는 라이브러리 중 파일이 설치 폴더 밖에 있음(사용자가 추가한 jar·폴더, 사용자 드라이버 폴더) |
| 다운로드됨 (Downloaded) | Maven·URL 라이브러리 중 로더가 찾은 파일(`getLibraryFiles`)이 모두 있음 |
| 다운로드 안 됨 (Not downloaded) | Maven·URL 라이브러리 중 아직 받지 않았거나 파일이 사라짐. 오프라인 모드면 "(오프라인 모드)"를 붙인다 |
| 없음 (Missing) | 내려받지 않는 라이브러리인데 파일이 없음(오류색) |
| 사용 안 함 (Not used: a local file has the driver class) | 미리 정의된 다운로드 항목인데, 사용자가 추가한 로컬 jar·폴더에 드라이버 클래스가 있음. 받지도 클래스패스에 넣지도 않는다([drivers.md](drivers.md) "로컬 파일 우선"). 판단은 Settings 탭의 Class name 값으로 하고, Libraries 탭을 열 때마다 다시 그린다 |

SWT는 같은 판단을 글자색으로만 보여 준다(오류색 = 파일 없음, 링크색 = 아직 받지 않음, DriverEditDialog.java:456-470). 선택 라이브러리는 "선택"을 덧붙인다.
도구 설명은 로컬 경로다. 두 번 누르면 파일이 있는 폴더를 연다(SWT `DriverEditHelpers.showFileInExplorer`).

### Maven 아티팩트 (Add Artifact… / Edit…)

- **추가:** 두 탭. *Dependency Declaration*에 Maven `<dependency>` 요소(여럿, `<dependencies>` 블록도 됨)나 Gradle `group:artifact:version[:classifier]` 줄을
  붙여 넣으면 "찾은 아티팩트: N개"를 바로 보인다. *Manual*은 Group ID, Artifact ID, Classifier, Version(고정 버전, 비우면 대체 버전), Fallback version(RELEASE·LATEST·버전).
  아래 옵션 *전이 의존성 무시*, *선택 의존성도 받기*. 추가한 아티팩트는 사용자 정의 라이브러리로 목록 끝에 붙고 OK를 누르면 `drivers.xml`에 저장된다.
- **편집:** Maven 라이브러리를 고르고 Edit…. 사용자 정의 아티팩트는 Manual 탭과 옵션을 고칠 수 있고, 미리 정의된(plugin.xml) 아티팩트는 SWT처럼 읽기 전용이다
  ("제거하고 다른 아티팩트를 추가하십시오"). 고친 값은 원래 객체가 아니라 새 객체로 목록에서 바뀐다(드라이버 편집 창을 취소하면 원래대로).
- **만드는 방식:** 저장한 드라이버를 다시 읽을 때와 같은 생성자(경로 `maven:/group:artifact[:classifier]:fallback` + 선호 버전)로 만든다.
  이름·버전에는 글자·숫자·`.`·`_`·`-`만 받는다. classifier가 있는데 대체 버전이 비면 RELEASE를 쓴다(경로 `g:a:c`는 classifier를 대체 버전으로 읽는다).
- **XML 읽기:** `XMLUtils.newSecureDocumentBuilderFactory()`(DOCTYPE·외부 엔터티 막음). 뿌리 요소 없는 조각은 감싸서 읽는다. 하위 요소(예: `<exclusions>`의 groupId)는 보지 않는다.

### 라이브러리 정보 (Details…)

- 드라이버, 라이브러리, 경로, 버전, 로컬 파일과 탭 두 개(Dependencies, Description).
- **Dependencies:** *읽기*를 누르면 진행 대화상자에서 라이브러리 하나로 `DriverDependencies.resolveDependencies`를 돌려 트리를 보인다.
  노드는 이름 [버전]과 상태(파일 있음, 다운로드 안 됨, 중복). Maven 라이브러리는 이때 저장소의 POM을 읽고 jar는 받지 않는다.
  오프라인 모드에서는 로컬 저장소만 읽는다(단추 도구 설명). 일부만 풀리면 풀린 것과 오류 문구를 함께 보인다.
- SWT는 창을 열자마자 읽어 폐쇄망에서 창이 늦게 떴다. 그래서 단추로 읽는다. SWT의 고정 문구 License 탭과 버전 고르기(다운로드 대화상자 전용)는 옮기지 않았다.

### Local Client

- 이 드라이버의 네이티브 클라이언트 설치 목록. 백업·복원([swing-backup-restore.md](swing-backup-restore.md))이 pg_dump·mysqldump 등을 이 위치에서 실행한다.
- 목록 = 클라이언트 관리자가 이 컴퓨터에서 찾은 설치("이 컴퓨터에서 찾음", 지울 수 없음) + 사용자가 추가한 폴더("추가함"). 기본 설치에 "기본"을 붙이고 처음에 고른다.
- **Add Folder…** 로 폴더를 더하고 **Remove** 로 추가한 폴더를 뺀다(확인, 기본 단추 취소, 폴더는 지우지 않음). OK를 누르면 추가한 폴더가 드라이버의
  `nativeClientHomes`로 `drivers.xml`에 저장된다(백업 대화상자의 찾아보기…로 더한 폴더와 같은 목록).
- 고른 위치의 ID, 경로, 이름, 제품, 버전을 보인다. 제품·버전은 클라이언트 관리자가 도구를 실행해 읽을 수 있어 백그라운드에서 읽는다.
- 원격 저장소에서 받는 위치(`RemoteNativeClientLocation`)는 넣지 않는다(고르면 내려받음, 백업 대화상자와 같은 이유). 연결마다 어느 설치를 쓸지는 백업 대화상자에서 고른다.

### 전역 라이브러리 (Global Libraries…)

- 모든 드라이버의 클래스 로더 부모에 넣을 jar 목록(설정 `ui.drivers.global.libraries`, 경로를 URL 인코딩해 `|`로 이은 SWT와 같은 형식)과 읽기 전용 앱 클래스패스.
- Add Files… / Remove, 두 번 누르면 폴더를 연다. OK에 저장한다.
- 모델은 첫 드라이버를 불러올 때 이 목록으로 부모 클래스 로더를 한 번 만든다. 이미 불러온 드라이버가 있는데 목록이 바뀌었으면 "다시 시작하면 적용된다"고 알린다
  (SWT는 재시작을 권하지만 Swing 앱에는 재시작 명령이 없다). 앱 클래스패스는 OS의 경로 구분자로 나눈다(SWT는 `;`로 나눠 Linux·macOS에서 한 줄이었다).

## 구조

| 클래스 | 역할 |
|---|---|
| `DriverManagerDialog` (새) | 드라이버 트리·검색, New/Copy/Edit/Delete/Restore, 오프라인 모드 체크 상자 |
| `DriverEditorDialog` (새) | 드라이버 만들기·복사·편집 창(탭 5개), 저장 |
| `DriverLibrariesPanel` (새) | Libraries 탭: 라이브러리 트리·상태, 파일·폴더·Maven 아티팩트 추가, 아티팩트 편집, 정보, 제거, 다운로드, 클래스 찾기 |
| `MavenArtifactDialog` (새, 2026-11-08) | Maven 아티팩트 추가·편집(의존성 선언 붙여 넣기, 직접 입력, 의존성 옵션) |
| `DriverLibraryDetailsDialog` (새, 2026-11-08) | 라이브러리 정보와 의존성 트리 |
| `ClientHomesPanel` (새, 2026-11-08) | 드라이버 편집 창의 Local Client 탭 |
| `GlobalLibrariesDialog` (새, 2026-11-08) | 관리자 창의 전역 라이브러리 |
| `internal.DriverManagerMessages` (새) | 화면 문구 묶음(`.properties` 영어, `_ko.properties` 한국어). 키 접두사 `drivermgr_`, `driveredit_`, `driverlib_`, `drivermaven_`, `driverdeps_`, `driverclient_`, `driverglobal_` |
| `MainFrame` (연결 지점) | Tools 메뉴에 Driver Manager… 한 줄 |
| `ConnectionDialog` (연결 지점) | Database type 줄을 콤보 + Edit Driver… 패널로 바꾸고 `editDriver()` |

- 재사용: 속성 표는 `ConnectionPropertiesTable`, 진행 대화상자는 `ProgressDialog`, 다운로드 확인은 `SwingDriversService`, 아이콘은 `NodeIcons`,
  목록에서 뺄 드라이버는 Connect 대화상자와 같은 `DriverSelectionPanel.getConnectableDrivers()`다.
- 새 번들 의존은 없다. 모델은 `com.seizia.chromite.registry`(이미 `Require-Bundle`)의 `DriverDescriptor`, `DataSourceProviderDescriptor`,
  `DataSourceProviderRegistry`, `DriverLibraryAbstract`·`DriverLibraryBundle`, `DriverClassFindJob`, `DriverUtils`를 쓴다. SWT·JFace 번들은 쓰지 않는다.
- 저장: `DataSourceProviderRegistry.saveDrivers()` → `DriverDescriptorSerializerLegacy`가 설정 컨트롤러
  (`DBWorkbench.getPlatform().getConfigurationController()`)의 `drivers.xml`, 곧 `<작업 공간>/.metadata/.config/drivers.xml`에 쓴다(확인한 위치).
  사용자 정의 드라이버는 통째로(`custom="true"`, 라이브러리 경로 포함), 기본 제공 드라이버는 바뀐 값·라이브러리·숨김(`disabled="true"`)을 쓴다.
  다시 시작하면 같은 직렬화기가 읽는다.

## SWT에서 옮긴 것 (근거)

| 동작 | SWT 위치 (ui.editors.connection) | Swing |
|---|---|---|
| 관리자 목록 | `DriverManagerDialog.createDialogArea`(94-130행): `refreshProvidedDrivers`, `DriverSelectViewer`(트리) ← `DriverTreeViewer.collectDrivers`(180-215행): `getEnabledDrivers`를 `getCategory()`로 묶고 범주·드라이버를 이름순, `CommercialDriverFilter`(DriverSelectViewer.java:486-490) | 같은 묶음·정렬. 목록은 `getConnectableDrivers()`(상용 자리표시·임시·폐기 자리표시·내장 ODBC 브리지 제외). 검색은 SWT `DriverSelectViewer`의 이름·설명·범주 검색에 ID를 더함 |
| 단추 켜기 | `updateButtons`(296-311행) | 같은 조건. New의 공급자는 `createDriver`(313-325행)처럼 고른 드라이버의 공급자 → `generic`(DEFAULT_DS_PROVIDER) → 관리 가능한 첫 공급자 |
| Copy | `copyDriver`(327-335행) → `DriverEditDialog(shell, provider, driver)`(135-155행): `createDriver(원본)`, 이름 + " Copy", `setModified`, 드라이버·라이브러리 `setCustom(true)` | 같음. " Copy"는 화면 언어를 따른다(`driveredit_copy_name`, 한국어 "복사본") |
| Edit | `editDriver`(337-355행): 공급자의 현재 드라이버(`getDriver(id)`)를 열고 다르면 `applyFrom` | 같음 |
| Delete | `deleteDriver`(357-382행): 쓰는 연결이 있으면 막음(`DriverUtils.getUsedBy`), 확인, `removeDriver`, `saveDrivers` | 같음. 기본 제공 드라이버는 숨기기만 한다는 것을 확인 문구에 적음 |
| Restore Deleted | `undeleteDrivers`(384-431행): 꺼진 드라이버 체크 목록 → `setDisabled(false)` + `saveDrivers` | 여러 개 고르는 목록(JList) |
| 편집 탭 | `DriverEditDialog.createMainTab`(231-431행), `createLibrariesTab`(433-658행), `createConnectionPropertiesTab`(762-785행), `createParametersTab`(736-760행), `createClientHomesTab`, `createLicenseTab`(806-823행) | 같음(Local Client는 2026-11-08) |
| Maven 아티팩트 | `EditMavenArtifactDialog`(선언 탭: SAX로 `<dependency>`, Gradle 정규식 / 직접 입력 탭 / 의존성 옵션) | `MavenArtifactDialog`. 아래 "SWT와 다른 점" |
| 라이브러리 정보 | `DriverLibraryDetailsDialog` + `DriverDependenciesTree.loadLibDependencies` | `DriverLibraryDetailsDialog`(읽기 단추) |
| Local Client | `ClientHomesPanel.loadHomes`(드라이버 위치 + `findLocalClientLocations`), `addClientHome`, `removeClientHome`, `getLocalLocations` | `ClientHomesPanel`(원격 위치 제외) |
| 전역 라이브러리 | 환경설정 `PrefPageDriversClasspath`(전역 목록 + 시스템 클래스패스, OK에 저장, 재시작 권함) | `GlobalLibrariesDialog`(관리자 창 단추) |
| 파일·폴더 추가 | `addLibraryFiles`(711-734행), `addLibraryFolder`(695-709행): `DriverLibraryAbstract.createFromPath`, 파일 형식 `FileType.getFileTypeByFileName` | 같음. 같은 경로는 다시 넣지 않음 |
| 제거 | 삭제 단추(576-598행): 확인 → `resetDriverInstance` → 목록에서 뺌 | 같음(파일은 지우지 않는다고 확인 문구에 적음) |
| Download/Update | 603-613행: `setDriverLibraries(libraries)` → `getDefaultDriverLoader().updateFiles(true)` | 같음. 오프라인 모드면 단추를 끄고(도구 설명) 눌려도 알림만 |
| Find Class | 524-546행: `new DriverDescriptor(provider, "test", driver)` + `saveDriverSettings(test)` → `DriverClassFindJob(test, java.sql.Driver, true)` | 같음. `ProgressDialog`에서 돌리고, 찾은 클래스를 콤보에 넣어 펼침(고르면 Class name). 없으면 알림 |
| 단추 켜기(라이브러리) | `changeLibContent`(846-873행), `changeLibSelection`(875-887행) | 같은 조건(Find Class는 관리 가능 + 있는 파일, Download는 내려받는 라이브러리) |
| Reset to Defaults | `createButtonsForButtonBar`(825-833행), `loadSettings(true)`(897-923행), `resetLibraries`(940-943행) | 같음(사용자 정의 드라이버는 끔, OK를 눌러야 적용) |
| OK | `okPressed`(945-993행), `saveDriverSettings`(995-1023행) | 아래 "SWT와 다른 점" |

## SWT와 다른 점 (결정)

- **같은 이름 검사 순서:** SWT는 폼 값을 드라이버에 쓴 뒤 같은 이름 검사를 해서, 검사에 걸려 취소하면 등록된 드라이버에 바뀐 값이 남았다.
  Swing은 검사한 뒤에 쓴다.
- **클래스 로더 버리기:** SWT는 같은 이름의 드라이버가 있을 때만 `resetDriverInstance()`를 불러서, 이름을 바꾼 편집에서는 이전 jar의 클래스 로더가 남았다.
  Swing은 OK마다 버린다(다음 연결이 새 라이브러리로 드라이버를 다시 로드).
- **오프라인 모드 체크 상자:** SWT 관리자는 환경설정 > 연결 > 드라이버 페이지로 가는 링크를 둔다. Swing 앱에는 그 페이지가 없어 같은 설정
  (`ModelPreferences.UI_DRIVERS_OFFLINE`, `com.seizia.chromite.core` 노드)을 관리자 창의 체크 상자로 둔다. 모델(`DriverUtils.isOfflineMode`)이 매번 읽으므로 바로 적용된다.
- **Propagate driver properties:** SWT는 읽기 전용 공급자에서도 이 체크 상자만 켜 두는데, Swing은 다른 옵션처럼 끈다.
- **상태 글자:** SWT의 색 구분 대신 상태 이름을 글로 보인다(위 표).
- **Connect 대화상자의 Edit Driver…:** SWT 연결 페이지의 "Driver Settings" 단추(`ConnectionPageAbstract.java:252-258` → `ConnectionPageSettings.openDriverEditor`, 976-979행)에 해당한다. 편집 뒤 Swing은 폼 값을 작업용 연결에 쓰고
  설정 화면을 다시 만든다(입력한 값 유지, 암호를 저장하지 않는 연결은 암호를 다시 입력).

- **Maven 아티팩트 편집은 새 객체로:** SWT는 목록에 든 드라이버의 실제 라이브러리 객체를 고쳐, 드라이버 편집 창을 취소해도 바뀐 참조가 남았다.
  Swing은 새 객체를 만들어 목록에서 바꾼다.
- **Maven 참조의 버전:** SWT는 새 아티팩트의 참조 버전 칸에 선호 버전을 넣었지만, 다시 시작하면 경로에서 다시 만들어 버전이 RELEASE가 됐다. Swing은 처음부터 다시 읽을 때와 같은 생성자로 만든다.
- **Maven 옵션 저장(모델 수정):** `DriverDescriptorSerializerLegacy`가 `ignore-dependencies`·`load-optional-dependencies`·`forced-version`을 이미 있는 라이브러리에만
  읽어서, 사용자가 추가한 아티팩트는 다시 시작하면 옵션을 잃었다. 라이브러리를 찾거나 만든 뒤에 읽게 고쳤다(SWT 제품에도 있던 문제).
- **라이브러리 정보는 단추로 읽음, 전역 라이브러리는 관리자 창에:** 위 각 절.

## 남긴 것

- 라이브러리 내보내기(`DriverEditHelpers.exportDriverLibraries`).
- Maven 저장소 목록(SWT 환경설정 `PrefPageDriversMaven`, `maven-repositories.xml`): 설정 작업([swing-options.md](swing-options.md) "다음 작업" 8번)에 남김.
- 라이브러리 정보 창의 버전 고르기(SWT 다운로드 대화상자의 의존성 트리 편집).
- 새 드라이버의 범주 지정: SWT도 편집 창에 범주 칸이 없다(새 드라이버는 범주 없음).
- 분산 제품(`DBWorkbench.isDistributed()`)의 라이브러리 업로드: Swing 제품은 분산 제품이 아니다.

## 확인 (2026-11-07, Windows x86_64 Swing 제품, 개인 작업 공간)

`D:\Tools\m2
epository`에 `sqlite-jdbc` jar가 없어서, 지시대로 공개판에 내장된 PostgreSQL jar(`drivers/postgresql/postgresql-42.7.14.jar`)를
작업 폴더(`D:\Tools\_scratch\driver-manager\jars\`)에 복사해 "사용자 정의 PostgreSQL" 드라이버의 로컬 파일로 썼다. 대상 DB는 로컬 PostgreSQL 17의 `chr_driver_manager`.

- **만들기:** Tools > 드라이버 관리자… > 새로 만들기…(고른 드라이버의 공급자 `postgresql`) → 이름 "Custom PostgreSQL (local jar)", URL 템플릿, 기본 포트·DB·사용자 →
  라이브러리 탭 파일 추가…로 위 jar → 상태 "로컬 파일" → 클래스 찾기가 `org.postgresql.Driver` 하나를 찾아 클래스 이름에 넣음 → 확인.
  관리자 트리에 전구 덧그림과 함께 나타나고 `drivers.xml`에 `custom="true"` 드라이버와 jar 경로가 저장됐다.
- **연결:** 새 연결 > 다른 데이터베이스 유형…에서 이 드라이버 → 서버 이름에 드라이버 기본 포트(5432) 도움말, 데이터베이스·로그인에 드라이버 기본값 →
  연결 테스트 "연결되었습니다 / 서버: PostgreSQL 17.11 / 드라이버: PostgreSQL JDBC Driver 42.7.14" → 연결 → 개체 탐색기에서 `drivers_seen` 상위 1000개 행(2개 행).
  연결된 동안 그 jar 파일은 Chromite 프로세스가 열고 있어 이름을 바꿀 수 없었다(사용자 정의 드라이버의 클래스 로더가 이 파일을 씀).
- **편집·다시 시작:** 기본 데이터베이스를 `chr_driver_manager`로 바꿔 저장 → 끝내고 다시 시작 → 드라이버 관리자와 편집 창에 같은 값·라이브러리가 보이고 연결이 됐다.
- **복사·삭제·복원:** PostgreSQL 복사…(이름 "PostgreSQL 복사본", 내장 폴더 라이브러리 2개와 라이선스 파일이 사용자 정의로 복사) → 확인 → 삭제(사용자 정의는 지움).
  연결이 쓰는 사용자 정의 드라이버의 삭제는 "드라이버를 삭제할 수 없음"으로 막혔다. 기본 제공 Altibase 삭제 → `disabled="true"` 저장 → 삭제한 드라이버 복원… → 다시 보임.
  연결을 지운 뒤 사용자 정의 드라이버를 지우자 `drivers.xml`에서 빠졌다.
- **Connect 대화상자의 드라이버 편집…:** 새 연결(PostgreSQL)에 서버·DB·로그인·암호·연결 이름을 넣고 드라이버 편집… → 확인 → 다시 만든 설정 화면에
  입력한 값이 그대로 있고 연결 테스트가 성공했다(연결 이름은 처음 확인에서 비어서 `working.setName`을 더해 고침).
- **내장 상태:** 연결 대화상자 PostgreSQL의 드라이버 편집… → 라이브러리 `drivers/postgresql` "내장", `drivers/postgis` "내장, 선택", 다운로드/업데이트 꺼짐(받을 라이브러리 없음).
- **오프라인 모드:** 관리자의 오프라인 모드를 켜면 SQLite(Maven `org.xerial:sqlite-jdbc:RELEASE`)가 "다운로드 안 됨(오프라인 모드)", 다운로드/업데이트가 꺼지고
  도구 설명이 "오프라인 모드가 켜져 있어 드라이버 파일을 다운로드하지 않습니다."였다. 이 상태에서 SQLite 연결 테스트는 다운로드 확인 없이
  모델의 오프라인 오류("'SQLite' 드라이버 파일이 설치본에 포함되어 있지 않고 오프라인 모드가 켜져 있습니다…")로 끝났다.
  끄면 단추가 켜지고, 누르면 `SwingDriversService`의 "드라이버 파일 다운로드" 확인이 떴다(취소함, 실제로 내려받지 않음).
- 화면: 한국어(OS 기본)·영어(`-nl en`) × 라이트·다크. 회귀 테스트는 실행하지 않았다.

### 확인하며 본 기존 문제 (범위 밖, 고치지 않음)

- SWT 제품 설치 폴더에는 `licenses/external/*.txt`(드라이버 라이선스 글, 예: `licenses/external/pg.txt`)가 있지만 Swing 제품에는 없어 PostgreSQL·SQLite 등의 라이선스 라이브러리가 "없음"으로 보인다.
  SWT도 같은 판단(파일 없음 = 오류색)을 하므로 상태 표시는 맞고, 파일을 Swing 제품에 넣을지는 따로 정할 일이다.
  → 2026-11-07 SWT 삭제 때 Swing 피처가 `docs/root`(`licenses/`)를 넣게 했다([swt-removal.md](swt-removal.md)).

## 확인 (2026-11-08, Maven 아티팩트·라이브러리 정보·Local Client·전역 라이브러리·로컬 파일 우선)

Windows x86_64 Swing 제품을 사설 작업 공간과 사설 데이터 폴더(`CHROMITE_DATA`)로 띄워 같은 프로세스에 붙인 EDT 테스트 에이전트로 조작했다(화면 입력 없음).
한국어(OS 기본)·라이트로 한 번, 다시 시작해 `-nl en`·다크로 한 번. 화면은 `D:\Tools\_scratch\chromite-drivers-oracle-batch\run\shots\`.

- **로컬 파일 우선(Altibase, 후속 #62):** Altibase 편집 > 라이브러리는 Maven `com.altibase:altibase-jdbc:RELEASE [7.1.0.9.0]` "다운로드 안 됨", 다운로드/업데이트 켜짐.
  파일 추가…로 `Altibase.jar`를 더하자 Maven 항목이 "사용 안 함: 로컬 파일에 드라이버 클래스가 있음"이 되고 다운로드/업데이트가 꺼졌다(도구 설명 "다운로드할 라이브러리 파일이 없습니다").
  Maven 항목을 남긴 채 확인 → 새 연결(Altibase, 127.0.0.1:52300/mydb, CHR_ALTI) → 연결 테스트: 다운로드 질문 없이 "연결되었습니다 / 서버: Altibase 7.3.0.0.7 / 드라이버: Altibase JDBC driver 7.3.0.0.6".
  `drivers.xml`에 Maven 항목(`custom="false"`)과 로컬 jar(`custom="true"`)가 함께 저장됐다.
- **라이브러리 정보:** Maven 항목의 정보… → 드라이버·라이브러리·경로 `maven:/com.altibase:altibase-jdbc:RELEASE`·버전 7.1.0.9.0 → 읽기 → "이 라이브러리에는 의존성이 없습니다."(영어 "The library has no dependencies.").
- **Maven 아티팩트 추가(SQLite 드라이버):** 처음에는 확인이 꺼지고 "의존성 선언을 붙여 넣으십시오.". `<dependency>` 두 개(하나는 `<exclusions>` 포함) → "찾은 아티팩트: 2개",
  Gradle `implementation 'org.slf4j:slf4j-api:2.0.16'` → 1개, 닫히지 않은 XML → "Maven이나 Gradle 의존성 선언이 아닙니다."(확인 꺼짐).
  직접 입력에서 아티팩트 ID `slf4j:api` → 이름 오류 문구, `slf4j-api` 2.0.16 + 전이 의존성 무시 → 확인 → 목록에 `org.slf4j:slf4j-api:RELEASE [2.0.16]` "다운로드 안 됨".
  편집…에서 버전 2.0.17 → 목록 [2.0.17]. 저장된 `drivers.xml`: `path="maven:/org.slf4j:slf4j-api:RELEASE" custom="true" version="2.0.17" ignore-dependencies="true"`.
  **다시 시작한 뒤** 편집…에서 "전이 의존성 무시"가 켜진 채였다(`DriverDescriptorSerializerLegacy` 수정 확인).
- **미리 정의된 아티팩트:** Altibase Maven 항목의 편집… → 칸 모두 읽기 전용, 옵션 꺼짐, 안내 문구, 닫기 단추만.
- **Local Client(PostgreSQL):** 처음 목록은 비어 있었다(이 PC에 설치형 PostgreSQL 없음). 폴더 추가… `D:\Tools\pgsql-17` → 제품 "PostgreSQL", 버전 빈 값(그 아래 `pgsql\bin`에 psql이 있음),
  `D:\Tools\pgsql-17\pgsql` → 버전 "psql (PostgreSQL) 17.11". 제거 확인은 "클라이언트 폴더 … 제거하시겠습니까?"이고 기본 단추·포커스가 취소였다. 취소 → 남음, 제거 → 빠짐.
  확인 → `drivers.xml`에 `<clientHome id="D:\Tools\pgsql-17\pgsql" …/>`, 다시 시작한 뒤에도 목록에 있었다.
- **전역 라이브러리:** 관리자 창의 전역 라이브러리… → 빈 목록과 앱 클래스패스(런처 jar 한 줄). jar 하나를 더해 확인 → Altibase 드라이버를 이미 불러온 상태라
  "전역 라이브러리를 저장했습니다. 이미 불러온 드라이버에도 적용하려면 Chromite를 다시 시작하십시오." → 다시 열어 그 jar가 보임 → 제거하고 확인(시험 흔적을 남기지 않음).
- 회귀 테스트는 실행하지 않았다.

## 변경 이력

- 2026-11-07 최초 작성: Tools > Driver Manager…, 드라이버 편집 창(Settings, Libraries, Default Properties, Advanced Parameters, License),
  라이브러리 상태, 로컬 파일·폴더 추가, Find Class, Download/Update(오프라인 모드에서 막음), Restore Deleted, 오프라인 모드 체크 상자,
  Connect 대화상자의 Edit Driver….
- 2026-11-07 SWT 프런트엔드 삭제([swt-removal.md](swt-removal.md))로 Swing 제품에도 `licenses/external/*.txt`가 들어감을 기존 문제 항목에 적음.
- 2026-11-07 관리자 창에 Create Offline Bundle…·Install Offline Bundle… 단추 추가([driver-offline-bundle.md](driver-offline-bundle.md)).
- 2026-11-08 후속 #55·#62: Maven 아티팩트 추가·편집(`MavenArtifactDialog`), 라이브러리 정보와 의존성 트리(`DriverLibraryDetailsDialog`),
  Local Client 탭(`ClientHomesPanel`), 전역 라이브러리(`GlobalLibrariesDialog`), 라이브러리 상태 "사용 안 함"([drivers.md](drivers.md) "로컬 파일 우선").
  모델: 사용자 정의 Maven 아티팩트의 의존성 옵션이 다시 시작하면 사라지던 것을 고침(`DriverDescriptorSerializerLegacy`).
