# AI 제공자와 모델 목록

## 목적

Chromite의 AI 기능(SQL 생성, AI 채팅)이 어느 서버에서 모델 목록과 모델 정보를 받는지 정리한다.
Chromite는 사용자가 연결한 AI 제공자 외의 서버로 요청을 보내지 않는다. upstream DBeaver는 모델 정보를
DBeaver가 운영하는 카탈로그(`https://dbeaver.com/info/ai_models_api.json`)에서 받았는데, Chromite는 이를 제거했다.

## 결정 사항 (2026-11-06 사용자 결정)

- dbeaver.com AI 모델 카탈로그를 제거한다. 모델 목록은 사용자가 연결한 제공자에서 받는다.
- 앞으로 Claude와 ChatGPT를 구독 계정 로그인과 API 키로 연결할 예정이다(아래 "향후 작업"). 이번 작업에는 포함하지 않는다.

## 구조

| 구성 요소 | 위치 | 역할 |
|---|---|---|
| AI 엔진 등록 | `plugins/com.seizia.chromite.model.ai/plugin.xml` | OpenAI(`OpenAIEngine`), GitHub Copilot(`CopilotCompletionEngine`), Ollama(`OllamaCompletionEngine`) |
| 설정 화면 | `plugins/com.seizia.chromite.ui.ai/plugin.xml` | 엔진별 `propertyConfigurator`(`OpenAiConfigurator`, `CopilotConfigurator`, `OllamaConfigurator`) |
| 엔진 공통 | `model.ai/.../engine/BaseCompletionEngine.java` | 컨텍스트 크기(설정값), 요청 temperature |
| 모델 정보 | `model.ai/.../engine/AIModel.java`, `AIModelFeature.java` | 이름, 컨텍스트 크기, 입출력 한도, 기능(CHAT, STREAMING, TOOL_CALL, REASONING, VISION 등) |
| 요청 조립 | `model.ai/.../impl/AIEngineRequestFactory.java` | 컨텍스트 크기를 모르면 `AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE`(16384)를 쓴다(240~248행) |
| 설정 화면 공통 | `ui.ai/.../preferences/AbstractAIEngineConfigurator.java`, `ui.ai/.../model/ModelSelectorField.java`, `ContextWindowSizeField.java` | 모델 콤보(직접 입력 가능), 컨텍스트 크기 입력 |

DataDam AI 엔진(`DDAIEngine`, `plugins/com.seizia.chromite.model.datadam`)도 OpenAI 엔진을 상속하지만 제품 피처에 포함되어 있지 않다
([telemetry.md](telemetry.md) "DataDam").

## 엔진별 모델 목록과 모델 정보

| 엔진 / 연결 방식 | 모델 목록 요청 | 응답에서 쓰는 값 | 없는 값 |
|---|---|---|---|
| OpenAI, API 키 | 설정한 base URL의 `GET models`(기본 `https://api.openai.com/v1/models`). `OpenAiClientBase.getModels()` 79~81행, `OpenAIEngine.getModels()` 76~86행 | `id`, `context_length`(OpenAI 호환 서버가 줄 때만). 기능은 공식 엔드포인트면 모델 ID로 추정(`OpenAIModels.detectModelFeatures`), 아니면 CHAT·STREAMING | 공식 OpenAI API는 `context_length`를 주지 않는다. 컨텍스트 크기는 사용자 설정, 없으면 16384 |
| OpenAI, ChatGPT 계정 로그인 | `GET https://chatgpt.com/backend-api/codex/models?client_version=...`. `OpenAIAccountAuthenticator.listModelDetails()` 252행 이후 | `slug`, `visibility`(`list`만 표시), `priority`(정렬), `context_window` | 기능은 모델 ID로 추정 |
| GitHub Copilot | Copilot 세션의 `<apiBaseUrl>/models`. `CopilotClientBase.loadModels()` 134~143행, `CopilotCompletionEngine.getModels()` 82~89행 | `id`, `capabilities.limits`(컨텍스트, 프롬프트, 출력 한도), `capabilities.supports`(스트리밍, 도구 호출, 비전, 추론), `policy`, `model_picker_enabled` | 한도가 없으면 사용자 설정, 없으면 16384 |
| Ollama | `<base URL>/api/tags`. `OllamaClient` 67행 | `name` | 컨텍스트 크기는 사용자 설정 |

ChatGPT 계정 방식은 목록 API가 있으므로 내장 정적 모델 목록을 두지 않았다. 세 방식 모두 목록 API가 있어 정적 목록이 필요 없다.

### 컨텍스트 크기

- 실행 중에는 설정에 저장된 값만 쓴다(`BaseCompletionEngine.getContextWindowSize()`). 값이 없으면 `AIEngineRequestFactory`가 16384를 쓴다.
- 설정 화면에서 모델을 고르면 제공자 목록이 준 크기를 보여 주고, 크기가 없으면 16384를 보여 준다
  (`AbstractAIEngineConfigurator.updateModelParameters()`). 보이는 값이 그대로 저장된다(`ContextWindowSizeField.getValue()`).
- 채팅 화면의 모델 메뉴로 바꿀 때도 같다(`OpenAIProperties.selectModel()`은 크기가 없으면 16384, `CopilotProperties.selectModel()`은 목록 값).
- 사용자는 언제든 컨텍스트 크기를 직접 입력할 수 있다.

### temperature

- 저장한 temperature를 보낸다. 비워 두면 보내지 않는다(서버 기본값).
- 일부 모델(예: 추론 모델)은 temperature를 거부한다. 카탈로그가 없으므로 미리 알 수 없다. 대신 제공자가
  "Unsupported parameter ... temperature" 오류를 돌려주면 클라이언트가 temperature를 빼고 한 번 더 보내고, 그 모델을 기억한다
  (`OpenAIClientResponses` 107~110·146~149행, `CopilotClientResponses` 123~126·157~160행, `MODELS_WITHOUT_TEMPERATURE`).

### 이미지 입력

- Copilot은 목록의 `capabilities.supports.vision`으로 이미지 지원 여부를 안다. 지원하지 않는 모델에 이미지를 붙이면 요청 전에 막는다
  (`AIEngine.supportsImageInput()`, `AIEngineRequestFactory` 72행).
- OpenAI 목록에는 이 정보가 없어 이미지 입력을 허용으로 본다. 모델이 이미지를 지원하지 않으면 제공자가 오류를 돌려준다.

### 모델 이름 직접 입력

- 모델 콤보는 편집 가능한 콤보(`SWT.DROP_DOWN`, `ModelSelectorField`)라서 목록을 받지 못해도 모델 이름을 직접 입력할 수 있다.
- 목록에 없는 이름을 입력하면 컨텍스트 크기 칸이 비고, 사용자가 크기를 입력한다(OpenAI 설정 화면은 크기를 입력해야 완료된다).

## 설계 결정과 이유

- **제3자 카탈로그를 쓰지 않는다.** 사용자는 OpenAI나 GitHub를 골랐는데 dbeaver.com에도 요청이 가는 것은 사용자가 알 수 없는 부수 요청이다.
  Chromite는 DBeaver Corp 서버에 의존하지 않는다([telemetry.md](telemetry.md)).
- **새 원격 메타데이터 출처를 만들지 않는다.** 모델 정보는 제공자 목록 API가 주는 것만 쓰고, 없는 값은 보수적 기본값과 사용자 설정으로 채운다.
- **내장 정적 모델 목록을 두지 않는다.** 모든 연결 방식에 목록 API가 있고, 정적 목록은 금방 낡는다.
- **보이는 컨텍스트 크기를 저장한다.** upstream은 카탈로그가 실행 중에 같은 값을 다시 채운다는 전제로 기본값을 저장하지 않았다.
  카탈로그가 없으면 저장하지 않은 값은 실행 중에 다시 찾을 수 없어 화면과 실제 값이 달라지므로 저장하도록 바꿨다.

## upstream 대비 변경점

- 삭제한 클래스: `AIModelCatalog`(다운로드, 7일 캐시, 재시도), `AIModelCatalogEntry`(카탈로그 항목과 보강 로직), `CopilotModels`(카탈로그 조회).
- 삭제한 메서드: `BaseCompletionEngine`의 `getCatalogProviderId`, `getModelCatalog`, `getCachedModelCatalog`, `getCachedCatalogEntry`,
  `findCatalogEntry`, `mapModels`. `OpenAIEngine`의 `getCatalogProviderId`, `findCatalogEntry` 재정의.
  `OpenAIModels`의 `CATALOG_PROVIDER_ID`, `getModelByName`, `findCatalogEntry`, `isTemperatureEditable`, 카탈로그 항목을 받던 `fromApiModel`.
  `CopilotModel.toAIModel(AIModelCatalogEntry)`. `OpenAIAccountAuthenticator.listModelDetails(DBRProgressMonitor, OpenAIProperties)`.
  `OpenAIProperties`·`CopilotProperties`의 `getConfiguredContextWindowSize`, `getEffectiveContextWindowSize` 재정의,
  `CopilotProperties.getEffectiveTemperature`, `CopilotCompletionEngine.temperature()` 재정의.
- 삭제한 기능 값: `AIModelFeature.PDF_INPUT`, `STRUCTURED_OUTPUT`, `TEMPERATURE_UNSUPPORTED`(카탈로그만 채웠다).
- 바뀐 동작: 설정 화면의 컨텍스트 크기 기본값을 저장한다. 목록에 크기가 없는 모델은 16384를 기본값으로 보여 준다.
- 테스트: 카탈로그 전용 테스트 `OpenAIModelCatalogTest`를 삭제하고, 다른 AI 테스트에서 카탈로그 픽스처와 카탈로그 검증을 뺐다.

## 사용자가 알아챌 수 있는 변화

- 공식 OpenAI API(API 키)에서 모델을 고르면 컨텍스트 크기가 모델별 실제 값 대신 16384로 채워진다. 더 큰 값이 필요하면 직접 입력한다.
- temperature를 지원하지 않는 모델에서도 설정 화면의 temperature 칸이 비활성화되지 않는다. 요청은 위 재시도로 처리된다.
- 공식 OpenAI의 텍스트 전용 모델에 이미지를 붙이면, 요청 전에 막히지 않고 제공자 오류로 실패한다.

## 향후 작업

- Claude와 ChatGPT를 구독 계정 로그인과 API 키로 연결한다. 모델 목록은 연결한 제공자의 목록 API에서 받는다는 이 문서의 원칙을 따른다.
- 현재 ChatGPT 계정 방식은 OpenAI 엔진의 인증 방식 중 하나다(`OpenAIProperties.AUTHENTICATION_CHATGPT_ACCOUNT`).
  계정 모델 목록 요청에는 `originator: dbeaver` 헤더가 붙는다(`OpenAIAccountAuthenticator`). 새 연결 작업에서 함께 검토한다.

## 변경 이력

- 2026-11-06 문서 작성. dbeaver.com AI 모델 카탈로그를 제거하고 모델 목록과 메타데이터를 제공자에서만 받도록 바꿨다.
