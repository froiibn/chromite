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
import com.seizia.chromite.model.ai.AIFunctionAlias;
import com.seizia.chromite.model.ai.AIFunctionDescriptor;
import com.seizia.chromite.model.ai.AIFunctionPurpose;
import com.seizia.chromite.model.connection.DBPConnectionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

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
 *     <li>그 자체는 DANGEROUS가 아닌 도구의 연결별 도구 규칙(AUTO)도 그 도구의 DANGEROUS 호출(인수로 올라간 분류: {@code runSql}의 DROP,
 *     {@code changeSchema}의 dropTable)은 자동 실행하지 않는다(2026-11-15). 확인 대화상자의 "이 연결에서 항상 허용"은 WRITE 호출에서만 보이므로,
 *     이것이 없으면 합친 DDL 도구에서 인덱스 만들기를 허용한 것이 테이블 지우기까지 자동으로 만들었다.</li>
 *     <li>도구를 합치거나 이름을 바꾸면(2026-11-15, {@code plugin.xml}의 {@code <alias>}) 처음 읽을 때 한 번 옛 ID의 규칙을 지금 ID로 옮긴다
 *     ({@link #migrate}). 옮긴 규칙은 그 범위(전역, 연결마다)에서 옛 도구들이 실제로 판정되던 권한 가운데 가장 엄격한 것이다. 옛 키는 지우지 않는다:
 *     26.0.8로 돌아가면 그 판이 옛 키를 전처럼 읽는다. 옮긴 표시({@code migrations})는 26.0.8이 모르는 필드라 무시한다. 다시 돌면(표시가 사라진 뒤)
 *     지금 규칙과 옛 규칙 가운데 엄격한 쪽을 쓰므로 느슨해지지 않는다(멱등).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 * - 2026-11-07 2단계 설정 화면: 저장 전 규칙으로 실제 권한을 계산하는 {@code resolve(Settings, ...)}, 편집한 사본을 한 번에 저장하는
 *   {@link #setSettings} (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-15 합치거나 이름을 바꾼 도구의 규칙 옮기기({@link #migrate}), DANGEROUS가 아닌 도구의 연결별 규칙은 그 도구의 DANGEROUS 호출을
 *   자동 실행하지 않음 (도구 정리, docs/chromite/ai-agent.md "정책 옮기기")
 */
public final class AIToolPolicy {

    private static final Log log = Log.getLog(AIToolPolicy.class);

    public static final String FILE_NAME = "tool-policy.json"; //$NON-NLS-1$
    public static final String CLASS_KEY_PREFIX = "@"; //$NON-NLS-1$
    /**
     * Marker of the rule migration of the 26.1.0 tool cleanup in {@link Settings#getMigrations()}.
     */
    public static final String MIGRATION_ALIASES_26_1 = "tool-aliases-26.1.0"; //$NON-NLS-1$
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static AIToolPolicy instance;

    /**
     * Stored settings: tool ID or {@code @CLASS} → permission name.
     */
    public static final class Settings {
        private Map<String, String> defaults = new LinkedHashMap<>();
        private Map<String, Map<String, String>> connections = new LinkedHashMap<>();
        // Rule migrations already done (MIGRATION_*); a field 26.0.8 does not know and ignores
        private List<String> migrations = new ArrayList<>();

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
        public List<String> getMigrations() {
            if (migrations == null) {
                migrations = new ArrayList<>();
            }
            return migrations;
        }

        @NotNull
        Settings copy() {
            Settings copy = new Settings();
            copy.getDefaults().putAll(getDefaults());
            getConnections().forEach((id, map) -> copy.getConnections().put(id, new LinkedHashMap<>(map)));
            copy.getMigrations().addAll(getMigrations());
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
     * Replaces all rules and saves the file (the settings page applies its edited copy on OK).
     */
    public synchronized void setSettings(@NotNull Settings newSettings) throws DBException {
        Settings copy = newSettings.copy();
        copy.getConnections().values().removeIf(Map::isEmpty);
        save(copy);
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
        return resolve(load(), container, tool, access);
    }

    /**
     * The permission for one call under the given settings: the settings page shows the effective permission of rules the
     * user has not saved yet with the same rules as the executor.
     */
    @NotNull
    public static AIToolDecision resolve(
        @NotNull Settings current,
        @Nullable DBPDataSourceContainer container,
        @NotNull AIFunctionDescriptor tool,
        @NotNull AIToolAccess access
    ) {
        return resolve(current, container == null ? null : container.getId(), container != null && isProduction(container),
            tool.getId(), tool.getAccess(), access);
    }

    /**
     * {@link #resolve(Settings, DBPDataSourceContainer, AIFunctionDescriptor, AIToolAccess)} by ids, for the rule migration
     * (a former tool id has no descriptor).
     *
     * @param connectionId the call's connection, or null for connection-less tools
     * @param production   whether that connection is a production connection
     * @param staticAccess the tool's own class ({@code plugin.xml}); a call's class may be higher
     */
    @NotNull
    static AIToolDecision resolve(
        @NotNull Settings current,
        @Nullable String connectionId,
        boolean production,
        @NotNull String toolId,
        @NotNull AIToolAccess staticAccess,
        @NotNull AIToolAccess access
    ) {
        String classKey = CLASS_KEY_PREFIX + access.name();
        if (connectionId != null) {
            Map<String, String> connectionRules = current.getConnections().get(connectionId);
            if (connectionRules != null) {
                AIToolPermission byTool = parse(connectionRules.get(toolId));
                if (byTool != null) {
                    if (staticAccess != AIToolAccess.DANGEROUS) {
                        // A rule for a tool that is not dangerous itself (allowed from a WRITE call's confirmation) never runs
                        // the tool's dangerous calls without asking: runSql DROP, changeSchema dropTable
                        byTool = limitDangerous(access, byTool);
                    }
                    return new AIToolDecision(byTool, "connection rule for " + toolId);
                }
                AIToolPermission byClass = limitDangerous(access, parse(connectionRules.get(classKey)));
                if (byClass != null) {
                    return new AIToolDecision(byClass, "connection rule for " + classKey);
                }
            }
            if (access.isWrite() && production) {
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
            if (loaded == null) {
                // Nothing to migrate: rules saved from now on are under the current tool ids
                loaded = new Settings();
                loaded.getMigrations().add(MIGRATION_ALIASES_26_1);
            }
            settings = loaded;
            if (migrate(settings, AIToolCatalog.getTools(AIFunctionPurpose.ALL), AIToolPolicy::isProductionId)) {
                try {
                    save(settings);
                } catch (DBException e) {
                    // The migrated rules apply in memory; the next start migrates the file again (the same result)
                    log.warn("Can't save the migrated AI tool policy", e);
                }
            }
        }
        return settings;
    }

    // A connection that is not found (deleted, or in a project that is not loaded) counts as production: the stricter guess
    private static boolean isProductionId(@NotNull String connectionId) {
        for (DBPDataSourceContainer container : AIToolConnections.getAll()) {
            if (container.getId().equals(connectionId)) {
                return isProduction(container);
            }
        }
        return true;
    }

    /**
     * One former tool id (or the tool itself) of a tool with aliases, and the class its calls had.
     */
    private record Member(@NotNull String id, @NotNull AIToolAccess access) {
    }

    /**
     * Moves the rules of former tool ids ({@code <alias>} of renamed and merged tools) to the current tool ids, once
     * (docs/chromite/ai-agent.md "정책 옮기기"). The settings never become more permissive:
     * <ul>
     *     <li>In each scope (the global rules, each connection's rules) the old rules are resolved for every member of a tool
     *     (its former ids with the class they had, and the tool itself unless it is new in this edition) as the old catalog
     *     resolved them, class rules, production blocking and defaults included.</li>
     *     <li>The tool gets a rule of its own in that scope when a member had a rule there, when it already has one (a
     *     second run), or when a member's calls would otherwise resolve to something less strict than before (a class
     *     raised by the merge, such as dropIndex becoming DANGEROUS under a stricter WRITE rule). The rule is the strictest
     *     of the members' old permissions (BLOCKED, then CONFIRM, then AUTO) and of its current rule.</li>
     *     <li>Old keys stay: the edition before reads them as before. A removed tool's rule is not moved.</li>
     * </ul>
     *
     * @return whether the settings changed (the marker included)
     */
    static boolean migrate(
        @NotNull Settings settings,
        @NotNull List<AIFunctionDescriptor> tools,
        @NotNull Predicate<String> production
    ) {
        if (settings.getMigrations().contains(MIGRATION_ALIASES_26_1)) {
            return false;
        }
        for (AIFunctionDescriptor tool : tools) {
            if (tool.getAliases().isEmpty()) {
                continue;
            }
            List<Member> members = new ArrayList<>();
            for (AIFunctionAlias alias : tool.getAliases()) {
                members.add(new Member(alias.id(), alias.access() != null ? alias.access() : tool.getAccess()));
            }
            if (tool.getAddedIn() == null) {
                // A tool that existed before: its own calls had their rules too
                members.add(new Member(tool.getId(), tool.getAccess()));
            }
            boolean raises;
            try {
                raises = tool.getInstance() instanceof AIToolAccessClassifier;
            } catch (RuntimeException e) {
                raises = true;
            }
            migrateScope(settings, null, false, tool, members, raises);
            for (String connectionId : new ArrayList<>(settings.getConnections().keySet())) {
                migrateScope(settings, connectionId, production.test(connectionId), tool, members, raises);
            }
        }
        settings.getMigrations().add(MIGRATION_ALIASES_26_1);
        return true;
    }

    private static void migrateScope(
        @NotNull Settings settings,
        @Nullable String connectionId,
        boolean production,
        @NotNull AIFunctionDescriptor tool,
        @NotNull List<Member> members,
        boolean raises
    ) {
        Map<String, String> rules = connectionId == null ? settings.getDefaults() : settings.getConnections().get(connectionId);
        AIToolPermission own = parse(rules.get(tool.getId()));
        boolean needed = own != null;
        AIToolPermission strictest = own;
        for (Member member : members) {
            needed |= !member.id().equals(tool.getId()) && rules.containsKey(member.id());
            AIToolPermission before = resolve(settings, connectionId, production, member.id(), member.access(), member.access()).permission();
            strictest = stricter(strictest, before);
            if (own == null) {
                // Without a rule of its own the tool resolves a member's calls by their class now, which may be higher
                AIToolAccess from = member.access().max(tool.getAccess());
                for (AIToolAccess access : AIToolAccess.values()) {
                    if (access.ordinal() < from.ordinal() || access.ordinal() > from.ordinal() && !raises) {
                        continue;
                    }
                    AIToolPermission after = resolve(settings, connectionId, production, tool.getId(), tool.getAccess(), access).permission();
                    needed |= after.ordinal() < before.ordinal();
                }
            }
        }
        if (needed && strictest != null) {
            rules.put(tool.getId(), strictest.name());
        }
    }

    @NotNull
    private static AIToolPermission stricter(@Nullable AIToolPermission a, @NotNull AIToolPermission b) {
        return a == null || b.ordinal() > a.ordinal() ? b : a;
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
