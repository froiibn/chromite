# 상용판 홍보 제거

## 목적

DBeaver Community에는 DBeaver Corp의 상용판(PRO, Lite, Enterprise, Ultimate, Team Edition, CloudBeaver)을
홍보하거나 구매를 권하는 기능과 문구가 들어 있다. Chromite는 DBeaver의 상용판과 관계가 없는 독립 제품이므로
이런 기능과 문구를 모두 없앤다.

## 결정 사항 (2026-11-06 사용자 결정)

1. DBeaver 상용판 홍보는 앱에서 모두 완전히 제거한다.
2. DBeaver의 다른 제품이나 판을 가리키는 화면 문구에는 "DBeaver"라는 단어를 남기지 않는다.
   문구가 필요한 기능이면 "다른 판(edition)의 제품" 같은 일반적인 표현으로 바꾸고, 모든 로캘에서 같은 뜻으로 옮긴다.
   올바른 번역을 쓸 수 없는 로캘에는 "DBeaver"를 남기지 않고 영어 문구를 넣는다.

## 제거한 항목

### "Try DBeaver PRO" 명령 (Help 메뉴)

`plugins/com.seizia.chromite.ui.app.standalone`

- `plugin.xml`: 명령 `com.seizia.chromite.core.try.pro`, 핸들러, Help 메뉴 기여(`commercial` 구분선 포함),
  이 메뉴의 `visibleWhen`에만 쓰던 property tester(`com.seizia.chromite.ui.app.standalone.menu.configuration`,
  속성 `isCommunity`)를 지웠다.
- 클래스 `actions.TryPROHandler`(https://dbeaver.com/download/ 를 여는 핸들러)와 `actions.MenuConfigurationPropertyTester`를 지웠다.
- `OSGI-INF/l10n/bundle*.properties`의 `command.com.seizia.chromite.ui.try.pro.name`/`.description`을 모든 로캘에서 지웠다.

### 오늘의 팁 (Tip of the Day)

`plugins/com.seizia.chromite.ui.app.standalone/src/com/seizia/chromite/ui/app/standalone/tipoftheday/tips.xml`

- `product` 속성이 붙은 팁 65개 중 64개를 지웠다. 모두 상용판(PRO, Lite, Enterprise, Ultimate, Team Edition,
  CloudBeaver)을 홍보하거나 상용판(`le`, `ee`, `ue`, `te`)에서만 보이던 팁이다.
- 남긴 1개는 `product="ce"`인 ER 다이어그램 표기법(Crow's Foot) 팁이다. 상용판 홍보가 아닌 실제 기능 안내다.
- 일반 팁 1개(필터 기록)에서 "If you use DBeaver PRO, the history will be saved even after restarting the app." 문장을 지웠다.
- `TipsXmlHandler`의 `product` 필터(제품 속성 `appEdition`=`ce`와 비교)는 남은 `product="ce"` 팁이 쓰므로 그대로 둔다.
  `tips.xsd`의 `productList` 패턴(`ce|le|ee|ue|te`)도 스키마 정의라서 고치지 않았다.

### 상용판 홍보용 드라이버 스텁

`plugins/com.seizia.chromite.ext.generic/plugin.xml`의 "Drivers Teasers" 블록에 있던 드라이버 8개를 지웠다.
모두 `<notAvailableDriver>`가 붙은 스텁이라 연결할 수 없고, 선택하면 상용판 구매를 권하는 안내만 보여 줬다.

| 드라이버 ID | 이름 | 비고 |
|---|---|---|
| `odbc` | ODBC - Legacy | `sun.jdbc.odbc.JdbcOdbcDriver`(Java 8에서 없어진 JDBC-ODBC 브리지). 연결 불가 스텁이고, 안내문이 PRO의 ODBC 드라이버를 권했다. |
| `mongo-ce` | MongoDB | |
| `cql-ce` | Cassandra | |
| `salesforce-ce` | Salesforce | |
| `couchbase-ce` | Couchbase | |
| `redis-ce` | Redis | |
| `timestream-ce` | Timestream | |
| `neo4j_jdbc` | Neo4j | |

- `odbc` 드라이버만 쓰던 데이터 소스 유형 `odbc_legacy`와 아이콘 `odbc_icon*.png`, 스텁만 쓰던 큰 아이콘
  (`mongodb_icon_big`, `cassandra_icon_big`, `salesforce_pro_icon`, `couchbase_pro_icon`, `redis_pro_icon`,
  `timestream_pro_icon`, `neo4j_icon_big`과 `@2x`)을 지웠다.
- 메시지 키 `deprecated.title`, `deprecated.description`, `driver.odbc.deprecation.reason`, `not.available.title`,
  `not.available.description`, `driver.{mongodb,cassandra,salesforce,couchbase,redis,timestream,neo4j}.reason`을
  모든 로캘에서 지웠다.
- 저장된 연결이 지운 드라이버를 가리키면 `DataSourceSerializerModern`이 "Missing driver" 임시 드라이버를 만들어 읽는다.
  기존 스텁도 연결할 수 없었으므로 잃는 기능은 없다.

남긴 것과 이유:

- 스텁 표시 기능 자체(`DBPDriverStub`, `DBPDriver.getDriverStub()`, `DriverDescriptor`의 `notAvailableDriver` 읽기,
  `RegistryConstants.ATTR_NOT_AVAILABLE_DRIVER`, 확장 지점 스키마의 `notAvailableDriver` 요소,
  `ConnectionPageDeprecation`과 연결 마법사·`ConnectJob`의 분기)는 이제 쓰는 곳이 없다.
  model, registry, ui.editors.connection 세 플러그인의 공개 API에 걸쳐 있어 이번 작업에서는 지우지 않고 후속 작업 후보로 남긴다.
- 드라이버 분류 `nosql`은 이제 이 분류를 쓰는 드라이버가 없다. `DataSourceTypeViewer`는 드라이버가 없는 분류 탭을
  만들지 않으므로 화면에는 나타나지 않는다. 분류 정의는 registry 공통 설정이라 그대로 둔다.

## 바꾼 문구

기능은 필요하지만 문구가 DBeaver의 다른 판을 가리키던 곳이다. 구매 권유는 빼고 "다른 판의 제품"으로 일반화했다.

| 위치 | 키 | 새 영어 문구 |
|---|---|---|
| `com.seizia.chromite.registry` `RegistryMessages*.properties` | `project_open_cannot_read_credentials_message` | You are about to open an encrypted project: ''{0}''\n\nWarning: if you proceed, your credentials will be permanently deleted. They were encrypted with a project password in another edition of the product and cannot be read in Chromite. |
| 같은 파일 | `project_open_cannot_read_configuration_message` | The ''{0}'' project was protected with a password in another edition of the product. It cannot be opened in Chromite. |
| `com.seizia.chromite.registry` `MissingDataSourceProvider.java` | Java 문자열 | This error may occur if the configuration was created in a different edition of the product. |
| `com.seizia.chromite.ui.editors.connection` `UIConnectionMessages*.properties` | `pref_page_drivers_repo_info` | The default file repository is a remote server and may be unreachable from closed networks. |

- 번역: `RegistryMessages`는 pl, pt_BR, tw, zh(이 키가 있는 로캘 전부), `pref_page_drivers_repo_info`는
  pl, pt_BR, ro, uk, zh(이 키가 있는 로캘 전부)를 같은 뜻으로 옮겼다.
- pt_BR의 `project_open_cannot_read_credentials_message`는 원래 경고 문장이 없는 짧은 번역이라 "DBeaver"가 없어서 그대로 뒀다.
- zh의 `project_open_cannot_read_credentials_message`는 줄바꿈이 `\\n`으로 이중 이스케이프돼 있다(기존 문제, 화면에 `\n`이 그대로 보일 수 있음).
  이번 작업 범위가 아니라 고치지 않았다.
- `pref_page_drivers_repo_info`의 원래 뜻은 "dbeaver.io 기본 저장소는 DBeaver로만 접근할 수 있다"였다. 기본 저장소 주소
  (`ModelPreferences.UI_DRIVERS_SOURCES` 기본값 `https://dbeaver.io/files/jdbc/`)는 바꾸지 않았고, 문구만 원격 서버라서
  닫힌 네트워크에서는 닿지 않을 수 있다는 중립적인 안내로 바꿨다.

## CData 드라이버 라이선스 문구

CData 사용권 동의 문구가 CData 제품을 "authorized DBeaver software"와 함께만 쓸 수 있다고 정하고, 라이선스 활성화와
드라이버 설정이 dbeaver.com을 쓰는 문제가 있었다. 2026-11-06 사용자 결정에 따라 CData 연동 전체를 제거했다.
자세한 내용은 [cdata-removal.md](cdata-removal.md)를 본다.

## 변경 이력

- 2026-11-06 Help 메뉴의 "Try DBeaver PRO" 명령과 그 명령에만 쓰던 핸들러, property tester, 메시지 키를 제거.
- 2026-11-06 오늘의 팁에서 상용판 홍보 팁 64개와 일반 팁 1개의 PRO 홍보 문장을 제거.
- 2026-11-06 Generic 플러그인의 상용판 홍보용 드라이버 스텁 8개와 그 데이터 소스 유형, 아이콘, 메시지 키를 제거.
- 2026-11-06 암호화 프로젝트 안내, 누락 드라이버 오류, 드라이버 저장소 안내 문구에서 DBeaver 상용판 언급을 일반화.
- 2026-11-06 CData 연동을 제거([cdata-removal.md](cdata-removal.md))하면서 CData 관련 메모를 정리.
