/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.websocket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.websocket.event.WSClientEvent;
import com.seizia.chromite.model.websocket.event.WSEvent;
import com.seizia.chromite.model.websocket.gson.WSClientEventDeserializer;
import com.seizia.chromite.model.websocket.gson.WSClientSideExclusionStrategy;
import com.seizia.chromite.model.websocket.gson.WSEventDeserializer;

import java.net.URI;

public class WSUtils {
    public static final Gson gson = baseGsonBuilder()
        .create();

    public static final Gson clientGson = baseGsonBuilder()
        .setExclusionStrategies(new WSClientSideExclusionStrategy())
        .create();


    private static GsonBuilder baseGsonBuilder() {
        return new GsonBuilder()
            .registerTypeAdapter(WSEvent.class, new WSEventDeserializer())
            .registerTypeAdapter(WSClientEvent.class, new WSClientEventDeserializer())
            .enableComplexMapKeySerialization();
    }

    public static URI convertUriToWS(@NotNull URI wsServerUri) {
        if (wsServerUri.getScheme().startsWith("ws")) {
            return wsServerUri;
        }
        var wsScheme = wsServerUri.getScheme().equals("https") ? "wss" : "ws";
        var wsEndpointLink = wsScheme + wsServerUri.toString().replaceFirst("^(http[s]?)", "");
        return URI.create(wsEndpointLink);

    }
}
