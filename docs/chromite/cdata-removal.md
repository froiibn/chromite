# CData 연동 제거

## 목적

DBeaver Community에는 CData JDBC 드라이버(Salesforce, Box 같은 SaaS용 상용 드라이버)를 내려받고 라이선스를
활성화해 연결하는 CData 연동이 들어 있다. Chromite는 이 연동을 완전히 제거한다.

## 결정 사항 (2026-11-06 사용자 결정)

CData 연동을 Chromite에서 모두 제거한다. 이유는 다음과 같다.

- 라이선스: 활성화 화면의 CData 사용권 동의 문구(`CDataUIResources.properties`의 `activation_eula_consent`)가
  CData 제품을 "authorized DBeaver software"와 함께만 쓸 수 있다고 정한다. Chromite에서 CData OEM 드라이버를 쓸 권리가 없을 수 있다.
- dbeaver.com 의존: 라이선스 활성화를 `https://dbeaver.com/lmp/externalLicenseActivation...`
  (`CDataLicenseActivationReporter.ACTIVATION_HOST`)로 보고하고, 드라이버 설정을
  `https://dbeaver.com/info/cdata-driver-settings.json`(`CDataDriverSettings.URL`)에서 받았다.
  Chromite는 DBeaver Corp 서버에 의존하지 않는다.
- 이 밖에 드라이버 카탈로그(`https://www.cdata.com/order/dbeavercatalog.rsb`), 아이콘, 문서, Maven 저장소
  (`https://maven.cdata.com/p/jdbc/`)도 DBeaver 전용 CData 서비스를 썼다.

## 제거한 항목

### 플러그인과 피처

| 경로 | 내용 |
|---|---|
| `plugins/org.jkiss.dbeaver.ext.cdata` | 모델: 데이터 소스 공급자 `cdata`, SQL 방언 `cdata`, generic 메타 모델 `cdata`(`cdata.jdbc.*`), 인증 모델 `cdata_native_url_builder`, 드라이버 분류 `cdata`(`commercial="true"`), Maven 저장소 `cdata-jdbc`, 제품 기능 `commercial-drivers`, 드라이버 카탈로그·라이선스 확인·활성화 코드, 묶음 `cdata-drivers.json`·`cdata-driver-settings.json`, 아이콘 `cdata*.svg` |
| `plugins/org.jkiss.dbeaver.ext.cdata.ui` | UI: 라이선스 서비스 구현, 인증 모델 설정 화면, 연결 페이지, 라이선스 활성화 대화상자, 환경설정 페이지 `org.jkiss.dbeaver.ext.cdata.preferences`, 키워드, 활동(activity) `dbeaver.activity.commercialDrivers`, 메시지(en, pl) |
| `features/org.jkiss.dbeaver.ext.cdata.feature` | 위 두 플러그인을 묶은 피처 |
| `test/org.jkiss.dbeaver.ext.cdata.test` | CData 단위 테스트 플러그인 |

### 다른 파일에서 지운 참조

- `plugins/pom.xml`: 모듈 `org.jkiss.dbeaver.ext.cdata`, `org.jkiss.dbeaver.ext.cdata.ui`
- `features/pom.xml`: 모듈 `org.jkiss.dbeaver.ext.cdata.feature`
- `features/org.jkiss.dbeaver.standalone.feature/feature.xml`: `org.jkiss.dbeaver.ext.cdata.feature` 포함
- `test/pom.xml`: 모듈 `org.jkiss.dbeaver.ext.cdata.test`
- `features/org.jkiss.dbeaver.test.feature/feature.xml`: 플러그인 `org.jkiss.dbeaver.ext.cdata.test`
- `plugins/org.jkiss.dbeaver.ui.editors.connection/.../UIConnectionMessages_pl.properties`: 키 `dialog_connection_show_cdata`.
  `UIConnectionMessages`에 같은 이름의 필드가 없어 이미 쓰이지 않던 키다.

### 지우지 않은 "cdata" 검색 결과

대소문자를 무시하고 `cdata`를 검색하면 나오지만 CData 연동과 관계없는 곳이다.

- `plugin.xml`(ext.generic, ext.h2, ext.mssql, ext.oracle, ext.postgresql, model.jdbc 등): `<![CDATA[`(XML CDATA 구역)이나
  `GenericDataSourceProvider`, `JDBCDataSource`, `JDBCDatabase...`, `publicDatabaseLinks` 같은 식별자 안의 부분 문자열이다.
- `DiagramLoader.java`(DTD의 `CDATA` 속성 유형), `GeometryViewerRegistry.java`(XML CDATA 주석),
  `DataGripDataSourceConfigXmlServiceImpl.java`(`Node.CDATA_SECTION_NODE`), `ui.ai/web/*.js`(외부 JS 라이브러리).
- `test/org.jkiss.dbeaver.test.platform/.../DriverLibraryMavenArtifactTest.java`: Maven 경로 처리 테스트의 예시 문자열
  (`maven:/cdata:box-jdbc:...`)이다. CData 플러그인에 의존하지 않는다.
- `deps/datadam-api/README.md`: 수정하지 않는 의존 저장소 사본이다(CLAUDE.md "빌드 환경").

## 남긴 것과 이유

다음은 CData만 쓰던 공통 기반 코드다. model, registry, ui, ui.editors.connection, core 다섯 플러그인의 공개 API와
설정에 걸쳐 있어 이번 작업에서는 지우지 않고 후속 작업 후보로 남긴다. CData가 없어도 오류 없이 동작한다.

- 상용 드라이버 표시 기능: `DBPDriverWithLicense`, `DBPDriverLicense`(model), `DBNDataSource`의 라이선스 확인,
  드라이버 분류의 `commercial` 속성(`DriverCategoryDescriptor.isCommercial()`, `driverManager.exsd`),
  `UIPreferences.DRIVER_SELECTOR_SHOW_COMMERCIAL_DRIVERS`와 기본값(`DesktopPreferencesInitializer`),
  "Show commercial drivers" 확인란(`ConnectionPageDataSource`, `ConnectionPageConnector`, `PrefPageDrivers`,
  메시지 키 `dialog_connection_show_commercial_drivers`), `DataSourceTypeViewer`·`DriverSelectViewer`·`DriverTabbedViewer`·
  `NewConnectionDriverSelectorContributor`의 상용 드라이버 분기.
  확인란은 `DataSourceTypeViewer.isCommercialDriversEnabled()`가 제품 기능 `commercial-drivers`를 찾을 때만 보이는데,
  이 기능을 정의하던 CData 플러그인이 없어졌으므로 확인란이 나타나지 않는다. `DBPDriverWithLicense`를 구현하는 드라이버도 없다.
- 드라이버 공급자 기능: `DriverProvider` 인터페이스와 `dataSourceProvider` 확장 지점의 `driverProvider` 속성
  (`DataSourceProviderDescriptor`). 이 속성을 켠 공급자가 없으면 아무 일도 하지 않는다.
- `deps/datadam-api`의 `com.dbeaver.datadam.lm.api` 번들: CData UI만 `Require-Bundle`로 썼다.
  `deps/`는 사용자 확인 없이 고치지 않으므로 그대로 둔다. 이 번들은 어떤 피처에도 포함되지 않아 제품에는 들어가지 않는다.

## 기존 연결의 동작

CData 드라이버로 만든 연결은 `data-sources.json`에 공급자 `cdata`로 저장돼 있다. CData 플러그인이 없으면 다음과 같이 동작한다.

1. `DataSourceSerializerModern.parseOrCreateDriver()`(1057~1077행)가 공급자 `cdata`를 찾지 못하면
   `DataSourceProviderRegistry.makeFakeProvider()`로 임시 공급자를 만든다. 이 공급자는 `MissingDataSourceProvider`를 쓰고
   임시(`temporary`)로 표시된다(`DataSourceProviderDescriptor` 243~251행).
2. 드라이버도 찾지 못하므로 이름이 드라이버 ID이고 설명이 "Missing driver <ID>"인 임시 드라이버를 만든다(1067~1077행).
3. 연결은 탐색기에 그대로 남고 설정도 지워지지 않는다. 저장할 때 공급자 ID `cdata`를 그대로 기록한다(1134행).
4. 연결을 열면 `MissingDataSourceProvider.openDataSource()`(75~81행)가
   "The specified driver '...' could not be found." 오류를 낸다.

`<워크스페이스>/.metadata/cdata` 폴더(카탈로그 캐시, 아이콘, 활성화 정보)는 사용자 데이터이므로 자동으로 지우지 않는다.
이제 이 폴더를 읽는 코드는 없다.

## upstream 대비 변경점

- upstream의 `org.jkiss.dbeaver.ext.cdata*` 플러그인, 피처, 테스트 플러그인이 없다.
- Chromite 제품에는 CData 드라이버 분류, CData 환경설정 페이지, "Show commercial drivers" 확인란이 나타나지 않는다.

## 변경 이력

- 2026-11-06 CData 연동(플러그인 2개, 피처, 테스트 플러그인, 빌드·피처 참조, 쓰이지 않던 pl 메시지 키)을 제거.
  CData 사용권 조건과 dbeaver.com 의존이 이유다.
