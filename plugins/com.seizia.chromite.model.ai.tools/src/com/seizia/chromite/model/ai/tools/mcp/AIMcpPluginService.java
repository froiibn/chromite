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
package com.seizia.chromite.model.ai.tools.mcp;

import com.seizia.chromite.runtime.IPluginService;

/**
 * Starts the local MCP server with the application when it is enabled, and stops it on exit.
 *
 * 설계: 플랫폼이 시작 뒤에 {@code com.seizia.chromite.pluginService} 확장을 활성화한다({@code BasePlatformImpl.activatePluginServices},
 * Swing 앱은 {@code platform.postInitialize()}로 부름). 그래서 Swing 코드를 고치지 않고 모델 번들만으로 서버를 띄운다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "로컬 MCP 서버")
 */
public class AIMcpPluginService implements IPluginService {

    @Override
    public void activateService() {
        AIMcpServer.getInstance().applySettings();
    }

    @Override
    public void deactivateService() {
        AIMcpServer.getInstance().stop();
    }
}
