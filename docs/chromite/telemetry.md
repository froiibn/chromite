# 사용 통계(텔레메트리) 제거와 외부 전송 현황

## 목적

DBeaver Community에는 기능 사용 기록을 모아 DBeaver Corp 서버(`https://stats.dbeaver.com/send-statistics`)로 보내는
사용 통계 기능이 들어 있다. 첫 실행 때 나오는 제품 구성 마법사(Product Configuration)의 "Data collection" 페이지에서
"Share anonymous usage statistics"가 기본으로 선택되어 있어, 사용자가 바꾸지 않으면 통계가 전송된다.
Chromite는 사용 통계를 수집하지도 보내지도 않는다. 이 문서는 제거한 내용과, 제거 후에도 DBeaver 소유 서버로 나가는
요청을 정리한다.

## 결정 사항 (2026-11-06 사용자 결정)

사용 통계 기능을 완전히 제거한다. 선택 해제 옵션으로 남기지 않고 수집·전송 코드와 설정, 화면을 모두 지운다.
DBeaver 소유 서버로 나가는 다른 요청은 이번 작업에서 조사만 하고, 처리는 후속 결정에 맡긴다("외부 전송 현황" 참고).

## 제거 전 구조 (upstream)

| 구성 요소 | 역할 |
|---|---|
| `DBRFeatureRegistry.useFeature()` (model) | 기능 사용 이벤트를 받아 QM 처리기와 `DBRFeatureTracker`에 전달 |
| `DBRFeatureTracker` (model) | 추적기 인터페이스. `DBRFeatureRegistry`가 `GeneralUtils.adapt()`로 구현체를 찾음 |
| `StatisticsAdapterFactory` (ui.statistics) | `org.eclipse.core.runtime.adapters`로 `DBRFeatureRegistry` → `FeatureStatisticsCollector` 어댑터 등록 |
| `FeatureStatisticsCollector` | 5초마다 이벤트를 `<워크스페이스>/.metadata/.activity-logs/<시각>_<실행ID>.log`에 기록 |
| `StatisticsTransmitter` | 로그 파일을 `POST https://stats.dbeaver.com/send-statistics?session=<실행ID>&time=<시각>`로 보낸 뒤 삭제. 헤더: Locale, Country, Timezone, Application-Name, Application-Version, OS, `X-Referrer: <작업 공간 ID>-<MAC 주소 MD5>`, User-Agent |
| `DesktopPlatform.initialize()` | `startTracking()` 호출: 이전 실행의 로그를 3초 뒤 백그라운드로 전송 |
| `UIStatisticsActivator.stop()`, `ApplicationWorkbenchWindowAdvisor` | `endTracking()` 호출: 종료할 때 현재 실행의 로그 전송 |
| `ProductConfigDataCollectionPage` | 마법사 "Data collection" 페이지(`org.jkiss.dbeaver.ui.app.config` 확장, order 4000) |
| `WorkbenchInitializerDataShareConfirm`, `StatisticsCollectionConfirmDialog` | 제품 구성 마법사가 없을 때 띄우는 데이터 공유 확인 대화상자 |
| `PrefPageUsageStatistics` | 환경설정 Workbench > Usage Statistics 페이지(dbeaver.com 개인정보 링크 포함) |
| 설정 키 | `feature.tracking.enabled`(값이 없으면 마법사에서 "보냄"이 기본 선택), `feature.tracking.skipConfirmation` (ui.statistics 번들 설정 저장소) |

## 제거한 항목

### 플러그인

| 경로 | 내용 |
|---|---|
| `plugins/org.jkiss.dbeaver.ui.statistics` | 위 표의 ui.statistics 클래스 전부, 마법사 페이지와 메시지(`ProductConfigMessages` en/pl), `StatisticCollectionMessages`(en/pl/tw/zh), 번들 메시지 `OSGI-INF/l10n/bundle*.properties`(en/ar/fr/pl/pt_BR/ro/tw/uk/zh), `plugin.xml` 확장(어댑터, workbenchWindowInitializer, 환경설정 페이지 `org.jkiss.dbeaver.preferences.main.usageStatistics`, 키워드 `org.jkiss.dbeaver.ui.statistics.keyword.pref.usage`, 마법사 페이지 `dataCollection`) |

이 플러그인에는 아이콘과 테스트가 없었다. 설정 기본값을 넣는 초기화 코드도 없었다(두 키는 코드에서 직접 읽었다).

### 다른 파일에서 지운 참조와 연결 지점

- `plugins/pom.xml`: 모듈 `org.jkiss.dbeaver.ui.statistics`
- `features/org.jkiss.dbeaver.app.local.feature/feature.xml`: 플러그인 `org.jkiss.dbeaver.ui.statistics`
- `DBRFeatureTracker.java`(model): 구현체가 통계 수집기 하나뿐이어서 인터페이스를 삭제
- `DBRFeatureRegistry`(model): 추적기 필드와 어댑터 조회, `startTracking()`, `endTracking()` 삭제.
  `useFeature()`는 이제 로컬 QM 처리기(`QMUtils.getDefaultHandler().handleFeatureUsage()`)에만 전달한다.
- `DesktopPlatform.initialize()`(core): `startTracking()` 호출 삭제
- `ApplicationWorkbenchWindowAdvisor`(ui.app.standalone): 마법사를 취소할 때의 `endTracking()` 호출 삭제
- `DBPApplication`(model): `isStatisticsCollectionRequired()`(통계 수집 강제 여부)와
  `getAdditionalApplicationProperties()`(통계 전송 헤더에만 쓰임) 삭제. `AbstractApplication`의 구현도 삭제
- `BaseWorkspaceImpl`(model): 통계 전송용 작업 공간 ID를 읽던 `readWorkspaceIdProperty()`와 캐시 필드 `globalWorkspaceId` 삭제.
  작업 공간 ID 자체(`readWorkspaceId()`, `workspace-id` 속성)는 다른 기능이 쓰므로 남겼다.
- `ProductConfigMessages.properties`, `ProductConfigMessages_pl.properties`(ui.app.config): 환영 페이지 문구 `welcome_body_text`에서
  "data collection"과 "zbieranie danych"를 뺐다. 이 키는 en, pl 두 언어에만 있다.

### 제품 구성 마법사의 페이지 (제거 후)

페이지는 `org.jkiss.dbeaver.ui.app.config` 확장의 `order` 순서로 쌓인다(`ProductConfigWizard.addPages()`).
"Data collection"(order 4000)이 빠져도 순서 값만 비고 페이지 이동은 그대로다.

1. Welcome (1000): 언어 선택과 안내 문구
2. Appearance (2000): 테마와 탐색기 보기. standalone 앱에서만 나온다.
3. Features (3000): 선택 기능 켜기/끄기
4. Final steps (5000): "Turn on Tip of the day", "Create sample database" 작업

## 설계 결정과 이유

- **옵션으로 끄지 않고 코드째 지운다.** 기본값만 바꾸면 설정 하나로 다시 전송될 수 있고, 남은 코드는 사용 중인 코드로
  오인되기 쉽다(CLAUDE.md 원칙 4).
- **`DBRFeature`와 `DBRFeatureRegistry`는 남긴다.** 기능 사용 기록은 쿼리 관리자(QM) 처리기에도 전달되고, 명령 기능 매핑
  (`org.jkiss.dbeaver.commandFeature` 확장)도 이 레지스트리를 쓴다. 외부로 보내는 부분(추적기)만 지웠다.
- **기존 사용자 데이터는 자동으로 지우지 않는다.** upstream DBeaver에서 쓰던 작업 공간을 가져온 경우
  `<워크스페이스>/.metadata/.activity-logs/` 폴더와 ui.statistics 번들 설정 파일이 남아 있을 수 있다. 이제 이 파일을 읽거나
  보내는 코드는 없다.

## 외부 전송 현황 (2026-11-06 조사, 후속 결정용)

사용 통계 외에 DBeaver 소유 서버(dbeaver.io, dbeaver.com, dbeaver.net, datadam.cloud)로 나가는 요청을 정리했다.
조사만 했고 이번 작업에서는 바꾸지 않았다.

### 공통 헤더

- `WebUtils.openURLConnection()`(`plugins/org.jkiss.dbeaver.model/src/org/jkiss/dbeaver/runtime/WebUtils.java` 150~157행)은
  모든 요청에 `User-Agent: <제품명> <버전>`(예: `Chromite 26.2.5`)을 넣고, 호출자가 referrer를 주면 `X-Referrer` 헤더로 보낸다.
- DataDam REST 클라이언트(`DDClient.java` 44~45행)는 `User-Agent: <제품명> (<OS>; <플랫폼>; <아키텍처>)`를 쓴다.

### 1. AI 모델 카탈로그 (제품에 포함, 사용자가 의식하지 못하는 요청)

- 위치: `plugins/org.jkiss.dbeaver.model.ai/src/org/jkiss/dbeaver/model/ai/engine/AIModelCatalog.java` 51~52행(주소), 247~251행(요청)
- 주소: `GET https://dbeaver.com/info/ai_models_api.json` (본문과 쿼리 없음, JDK `HttpClient` 기본 User-Agent)
- 시점: OpenAI(기본 엔드포인트나 ChatGPT 계정 인증)나 GitHub Copilot AI 엔진으로 AI 기능을 쓸 때
  (`BaseCompletionEngine` 49~51·80·91행, `AIEngineRequestFactory`, `OpenAIAccountAuthenticator` 264행).
  캐시(`ai-model-catalog-dbeaver.com.json`)가 7일 지났거나, 실패하고 1일 지났을 때만 받는다.
- 제어: 설정 키나 시스템 속성이 없다. 제품 기능 "ai"(`model.ai/plugin.xml`, 기본 켜짐)를 끄는 방법뿐이다.
- 판단: 사용자는 OpenAI나 GitHub를 골랐는데 dbeaver.com에도 요청이 간다. 사용자가 시작한 동작에 딸린 부수 요청이다.

### 2. 버전 업데이트 확인 (연결은 남아 있으나 지금은 동작하지 않음)

- 시작할 때: `plugins/org.jkiss.dbeaver.ui.app.standalone/src/org/jkiss/dbeaver/ui/app/standalone/update/WorkbenchInitializerUpdateCheck.java`
  43행이 창을 열 때마다 `DBeaverVersionChecker`를 예약한다. 수동: `CheckForUpdateAction` 44행.
- `DBeaverVersionChecker.java` 96행이 설정 `ui.auto.update.check`(기본 true, `DesktopPreferencesInitializer` 63~64행)를 보고,
  108~110행에서 제품 속성 `versionUpdateURL`이 없으면 네트워크 요청 없이 끝난다. Chromite는 이 속성을 지웠다(branding.md).
- 속성을 다시 넣으면 `VersionDescriptor.java` 72행이 `X-Referrer: <작업 공간 ID>`를 붙여 시작할 때마다 요청한다.

### 3. DataDam (빌드는 되지만 제품 피처에 포함되지 않음)

- `plugins/org.jkiss.dbeaver.model.datadam`과 `plugins/org.jkiss.dbeaver.ui.datadam`은 `plugins/pom.xml` 모듈이지만 어떤 제품 피처에도
  들어 있지 않다(`features/org.jkiss.dbeaver.test.feature`에 테스트 플러그인만 있음). 아래 내용은 이 번들을 설치했을 때만 해당한다.
- 주소(`DDEndpoints.java` 29~35행): `https://account.datadam.cloud`, `https://dbeaver-storage.datadam.cloud`, `wss://dam.datadam.cloud`.
  각각 `-Ddbeaver.datadam.*.url`로 바꿀 수 있다. AI 엔진 기본 주소 `https://ai.datadam.cloud/v1/`(`DDAIEngineProperties.java` 32행)은
  사용자가 그 엔진을 고를 때만 쓴다.
- 시작할 때(`DDDesktopInitializer` 41~59행): 전에 로그인한 적이 있으면(`datadam.desktop.sso.logged-in`, 기본 false) 토큰을 갱신하고
  계정을 확인한다(`POST https://account.datadam.cloud/graphql`). 로그인한 적이 없으면 요청하지 않는다.
- 로그인 상태면 시작할 때 WebSocket(`wss://dam.datadam.cloud`)을 열고 배포 ID, 실행 UUID, 작업 공간 ID,
  `clientDamId`(작업 공간 ID와 MAC 주소 MD5), 제품명과 버전을 보낸다(`DDRemoteDamDesktopSessionService.getInstance()` 84~92행).
  이 서비스는 제품 기능 "datadam" 토글을 직접 확인하지 않는다.
- `ui.datadam`의 `DDTrackingUIMessages`, `DDTrackingUIIcons`는 메시지와 아이콘일 뿐 추적 코드가 없다.

### 4. 드라이버·네이티브 클라이언트 파일 저장소 (사용자가 확인한 뒤)

- 설정 `ui.drivers.sources`의 기본값이 `https://dbeaver.io/files/jdbc/`다(`ModelPreferences.java` 411행).
- `repo:/` 경로를 쓰는 드라이버 파일(ext.generic 3개, ext.mssql 6개)과 MySQL, MariaDB, PostgreSQL 원격 네이티브 클라이언트를
  받을 때 쓴다. 드라이버 다운로드 대화상자에서 사용자가 확인해야 받는다. User-Agent만 보낸다.

### 5. p2 업데이트 사이트 (사용자가 열 때)

- `product/community/DBeaver.p2.inf` 2~7행이 `https://dbeaver.io/update/{office,git,debug}/latest/`를 저장소로 등록한다.
  사용자가 Install New Software 같은 p2 화면을 열 때만 접속한다. 자동 업데이트 스케줄러는 제품에 없다.

### 찾지 못한 것

- 오류·충돌 보고, 라이선스·활성화 확인, 뉴스·광고·원격 팁, 마켓플레이스 요청은 없다. 팁은 로컬 `tips.xml`이다.
- 도움말과 문서 링크(약 57곳, 예: `HelpUtils`의 `https://dbeaver.com/docs/dbeaver/`, `tips.xml`의 링크)는 사용자가 누를 때
  브라우저로 여는 것이라 전송으로 보지 않았다.

## upstream 대비 변경점

- upstream의 `org.jkiss.dbeaver.ui.statistics` 플러그인이 없다. 제품 구성 마법사에 "Data collection" 페이지가 없고,
  환경설정에 "Usage Statistics" 페이지가 없으며, 데이터 공유 확인 대화상자도 나오지 않는다.
- `DBRFeatureTracker` 인터페이스, `DBRFeatureRegistry.startTracking()`과 `endTracking()`,
  `DBPApplication.isStatisticsCollectionRequired()`와 `getAdditionalApplicationProperties()`,
  `BaseWorkspaceImpl.readWorkspaceIdProperty()`가 없다.

## 변경 이력

- 2026-11-06 사용 통계 기능 제거(ui.statistics 플러그인, 빌드·피처 참조, model·core·ui.app.standalone의 연결 지점, 환영 문구 en/pl).
  DBeaver 소유 서버로 나가는 다른 요청을 조사해 기록.
