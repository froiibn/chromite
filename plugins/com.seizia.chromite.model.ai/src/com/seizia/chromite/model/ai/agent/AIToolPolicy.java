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
package com.seizia.chromite.model.ai.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.connection.DBPConnectionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-connection and per-tool permission policy of agent tools.
 *
 * 설계:
 * <ul>
 *     <li>설정은 작업 공간 {@code .metadata/chromite-ai/tool-policy.json} 하나다. 키는 도구 ID 또는 분류({@code @READ}, {@code @WRITE},
 *     {@code @DANGEROUS}), 값은 {@link AIToolPermission}. 연결 설정 파일(data-sources.json)에 넣지 않은 이유: 연결 설정은 동료와 공유하거나
 *     내보내는 일이 많은데, "이 연결에서 쓰기 자동 실행"은 이 PC 사용자의 결정이라 함께 퍼지면 안 된다.</li>
 *     <li>판정 순서는 docs/chromite/ai-agent.md "설정과 판정 순서"와 같다. 운영 연결의 쓰기 막기는 기본값이고, 그 연결에 직접 정한 설정만 이것을 넘는다.
 *     DANGEROUS를 자동 실행으로 만드는 것은 연결별 도구 설정만 할 수 있다(전역·분류 설정 하나로 모든 연결의 삭제가 자동이 되는 것을 막기 위해).</li>
 *     <li>읽기 전용 연결 막기는 {@link AIToolExecutor}가 정책보다 먼저 한다. 설정으로 바꿀 수 없어야 하기 때문이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class AIToolPolicy {

    private static final Log log = Log.getLog(AIToolPolicy.class);

    public static final String FILE_NAME = "tool-policy.json"; //$NON-NLS-1$
    public static final String CLASS_KEY_PREFIX = "@"; //$NON-NLS-1$
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static AIToolPolicy instance;

    /**
     * Stored settings: tool ID or {@code @CLASS} → permission name.
     */
    public static final class Settings {
        private Map<String, String> defaults = new LinkedHashMap<>();
        private Map<String, Map<String, String>> connections = new LinkedHashMap<>();

        @NotNull
        public Map<String, String> getDefaults() {
            if (defaults == null) {
                defaults = new LinkedHashMap<>();
            }
            return defaults;
        }

        @NotNull
        public Map<String, Map<String, String>> getConnections() {
            if (connections == null) {
                connections = new LinkedHashMap<>();
            }
            return connections;
        }

        @NotNull
        Settings copy() {
            Settings copy = new Settings();
            copy.getDefaults().putAll(getDefaults());
            getConnections().forEach((id, map) -> copy.getConnections().put(id, new LinkedHashMap<>(map)));
            return copy;
        }
    }

    private Settings settings;

    private AIToolPolicy() {
    }

    @NotNull
    public static synchronized AIToolPolicy getInstance() {
        if (instance == null) {
            instance = new AIToolPolicy();
        }
        return instance;
    }

    @NotNull
    public static Path getFile() {
        return AIAgentServices.getAgentFolder().resolve(FILE_NAME);
    }

    /**
     * A copy of the current settings (for a settings page).
     */
    @NotNull
    public synchronized Settings getSettings() {
        return load().copy();
    }

    /**
     * Sets or clears one rule and saves the file.
     *
     * @param connectionId data source ID, or null for the global defaults
     * @param key          tool ID, or {@code @READ}/{@code @WRITE}/{@code @DANGEROUS}
     * @param permission   new permission, or null to inherit
     */
    public synchronized void setPermission(
        @Nullable String connectionId,
        @NotNull String key,
        @Nullable AIToolPermission permission
    ) throws DBException {
        Settings current = load();
        Map<String, String> map;
        if (connectionId == null) {
            map = current.getDefaults();
        } else {
            map = current.getConnections().computeIfAbsent(connectionId, id -> new LinkedHashMap<>());
        }
        if (permission == null) {
            map.remove(key);
        } else {
            map.put(key, permission.name());
        }
        if (connectionId != null && map.isEmpty()) {
            current.getConnections().remove(connectionId);
        }
        save(current);
    }

    /**
     * Re-reads the file on the next call (after an outside edit).
     */
    public synchronized void reload() {
        settings = null;
    }

    /**
     * Whether the user marked the connection as production: connection type "Production" or a custom type that confirms SQL
     * execution. The connection color is not used: it only overrides the type color in the UI.
     */
    public static boolean isProduction(@NotNull DBPDataSourceContainer container) {
        DBPConnectionType type = container.getConnectionConfiguration().getConnectionType();
        return DBPConnectionType.PROD.getId().equals(type.getId()) || type.isConfirmExecute();
    }

    /**
     * The permission for one call. Read-only connections are checked by the executor before this.
     *
     * @param container connection of the call, or null for connection-less tools
     * @param access    effective class of this call (static class raised by the tool's classifier)
     */
    @NotNull
    public synchronized AIToolDecision resolve(
        @Nullable DBPDataSourceContainer container,
        @NotNull AIFunctionDescriptor tool,
        @NotNull AIToolAccess access
    ) {
        Settings current = load();
        String toolId = tool.getId();
        String classKey = CLASS_KEY_PREFIX + access.name();
        if (container != null) {
            Map<String, String> connectionRules = current.getConnections().get(container.getId());
            if (connectionRules != null) {
                AIToolPermission byTool = parse(connectionRules.get(toolId));
                if (byTool != null) {
                    return new AIToolDecision(byTool, "connection rule for " + toolId);
                }
                AIToolPermission byClass = limitDangerous(access, parse(connectionRules.get(classKey)));
                if (byClass != null) {
                    return new AIToolDecision(byClass, "connection rule for " + classKey);
                }
            }
            if (access.isWrite() && isProduction(container)) {
                return new AIToolDecision(AIToolPermission.BLOCKED,
                    "production connection: write tools are blocked unless allowed for this connection");
            }
        }
        AIToolPermission globalTool = limitDangerous(access, parse(current.getDefaults().get(toolId)));
        if (globalTool != null) {
            return new AIToolDecision(globalTool, "global rule for " + toolId);
        }
        AIToolPermission globalClass = limitDangerous(access, parse(current.getDefaults().get(classKey)));
        if (globalClass != null) {
            return new AIToolDecision(globalClass, "global rule for " + classKey);
        }
        return new AIToolDecision(
            access == AIToolAccess.READ ? AIToolPermission.AUTO : AIToolPermission.CONFIRM,
            "default for " + access.name() + " tools"
        );
    }

    // DANGEROUS tools run automatically only by a per-connection per-tool rule
    @Nullable
    private static AIToolPermission limitDangerous(@NotNull AIToolAccess access, @Nullable AIToolPermission permission) {
        if (access == AIToolAccess.DANGEROUS && permission == AIToolPermission.AUTO) {
            return AIToolPermission.CONFIRM;
        }
        return permission;
    }

    @Nullable
    private static AIToolPermission parse(@Nullable String value) {
        if (value == null) {
            return null;
        }
        try {
            return AIToolPermission.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.debug("Unknown tool permission '" + value + "'");
            return null;
        }
    }

    @NotNull
    private Settings load() {
        if (settings == null) {
            Path file = getFile();
            Settings loaded = null;
            if (Files.exists(file)) {
                try {
                    loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
                } catch (IOException | JsonParseException e) {
                    // A broken file must not silently allow everything: fall back to the built-in defaults
                    log.warn("Can't read the AI tool policy " + file + ", using defaults", e);
                }
            }
            settings = loaded == null ? new Settings() : loaded;
        }
        return settings;
    }

    private void save(@NotNull Settings newSettings) throws DBException {
        Path file = getFile();
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(FILE_NAME + ".tmp"); //$NON-NLS-1$
            Files.writeString(temp, GSON.toJson(newSettings), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            settings = newSettings;
        } catch (IOException e) {
            throw new DBException("Can't save the AI tool policy to " + file, e);
        }
    }
}
