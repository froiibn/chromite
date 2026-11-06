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
package com.seizia.chromite.model.ai.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the raw permission probe ({@code diag/probePermissions.sql}) into checks and the tools each one enables.
 *
 * 설계: 시나리오 2 "권한이 있는 것만 점검하고, 없는 것은 이유를 말한다"(docs/chromite/ai-agent.md)를 위해 모델이 처음에 한 번 불러
 * 어떤 도구가 안 될지 미리 알게 한다. 권한 이름과 도구의 짝은 각 진단 SQL의 {@code @requires}와 같다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
final class PermissionProbe {

    private record Check(@NotNull String name, @NotNull String description, @NotNull List<String> tools) {
    }

    private PermissionProbe() {
    }

    static void summarize(@NotNull JsonObject result, @NotNull DbFamily family) {
        Map<String, Boolean> facts = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : result.entrySet()) {
            JsonArray rows = DiagnosticPostProcessor.rows(e.getValue());
            if (rows == null) {
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("error")) {
                    // A probe statement that failed means "not available" for its check
                    facts.put(e.getKey(), false);
                }
                continue;
            }
            if (e.getKey().equals("grants")) {
                mysqlGrants(rows, facts);
            } else if (e.getKey().equals("privileges")) {
                for (JsonElement r : rows) {
                    String privilege = DiagnosticPostProcessor.str(r.getAsJsonObject(), "PRIVILEGE", "privilege");
                    if (privilege != null) {
                        facts.put("priv:" + privilege.toUpperCase(Locale.ROOT), true);
                    }
                }
            } else if (e.getKey().equals("roles")) {
                for (JsonElement r : rows) {
                    String role = DiagnosticPostProcessor.str(r.getAsJsonObject(), "ROLE", "role");
                    if (role != null) {
                        facts.put("role:" + role.toUpperCase(Locale.ROOT), true);
                    }
                }
            } else if (!rows.isEmpty()) {
                for (Map.Entry<String, JsonElement> column : rows.get(0).getAsJsonObject().entrySet()) {
                    Boolean value = toBoolean(column.getValue());
                    if (value != null) {
                        facts.put(column.getKey(), value);
                    }
                }
                if (e.getKey().equals("vdollar")) {
                    facts.put("vdollar", true);
                }
            }
        }
        List<Check> checks = checks(family, facts);
        JsonArray checkJson = new JsonArray();
        Map<String, String> unavailable = new TreeMap<>();
        for (Check check : checks) {
            boolean available = isAvailable(check.name(), family, facts);
            JsonObject c = new JsonObject();
            c.addProperty("check", check.name());
            c.addProperty("available", available);
            c.addProperty("description", check.description());
            JsonArray tools = new JsonArray();
            check.tools().forEach(tools::add);
            c.add("tools", tools);
            checkJson.add(c);
            if (!available) {
                for (String tool : check.tools()) {
                    unavailable.merge(tool, check.name(), (a, b) -> a + ", " + b);
                }
            }
        }
        JsonObject toolJson = new JsonObject();
        unavailable.forEach((tool, missing) -> toolJson.addProperty(tool, "limited or unavailable: missing " + missing));
        result.add("checks", checkJson);
        result.add("unavailableTools", toolJson);
    }

    @NotNull
    private static List<Check> checks(@NotNull DbFamily family, @NotNull Map<String, Boolean> facts) {
        List<Check> checks = new ArrayList<>();
        switch (family) {
            case SQLSERVER -> {
                checks.add(new Check("view_server_state", "VIEW SERVER STATE: server-wide DMVs (sessions, requests, waits, plan cache, XE)",
                    List.of("listSessions", "getBlockingChain", "listLocks", "listOpenTransactions", "getLongRunningQueries",
                        "getWaitStats", "getTopQueries", "getCachedPlans", "getRoutineStats", "getMissingIndexes", "getDeadlocks", "killSession")));
                checks.add(new Check("view_database_state", "VIEW DATABASE STATE: index usage, physical stats, statistics properties",
                    List.of("getIndexUsage", "getUnusedIndexes", "getIndexFragmentation", "getTableStatistics", "getSpaceUsage")));
                checks.add(new Check("showplan", "SHOWPLAN: estimated and actual plans",
                    List.of("explainQuery", "getActualPlan", "comparePlans")));
                checks.add(new Check("view_definition", "VIEW DEFINITION: routine source and dependencies of objects you do not own",
                    List.of("getRoutineSource", "getObjectDependencies", "getObjectDdl")));
                checks.add(new Check("alter_any_connection", "ALTER ANY CONNECTION: end other sessions", List.of("killSession")));
                checks.add(new Check("msdb_backupset", "SELECT on msdb.dbo.backupset", List.of("getBackupHistory")));
                checks.add(new Check("msdb_jobs", "SELECT on msdb job tables (SQLAgentReaderRole)", List.of("getJobHistory")));
                checks.add(new Check("error_log", "securityadmin or sysadmin: read the error log", List.of("getErrorLog")));
            }
            case POSTGRESQL -> {
                checks.add(new Check("pg_read_all_stats", "pg_monitor / pg_read_all_stats: query text and state of other users' sessions",
                    List.of("listSessions", "getBlockingChain", "listOpenTransactions", "getLongRunningQueries")));
                checks.add(new Check("ext_pg_stat_statements", "pg_stat_statements extension installed in this database",
                    List.of("getTopQueries", "getRoutineStats")));
                checks.add(new Check("pg_signal_backend", "pg_signal_backend or superuser: end other users' sessions", List.of("killSession")));
                checks.add(new Check("pg_read_server_files", "pg_read_server_files or superuser, and logging_collector on: read the server log",
                    List.of("getErrorLog")));
                checks.add(new Check("ext_pgstattuple", "pgstattuple extension: exact bloat/fragmentation", List.of("getIndexFragmentation")));
                checks.add(new Check("ext_hypopg", "hypopg extension: hypothetical indexes (phase 1b)", List.of("whatIfIndex")));
                checks.add(new Check("ext_pg_cron", "pg_cron extension: job history", List.of("getJobHistory")));
                checks.add(new Check("track_functions", "track_functions = pl or all: per-function statistics", List.of("getRoutineStats")));
            }
            case MYSQL, MARIADB -> {
                checks.add(new Check("PROCESS", "PROCESS: all sessions, InnoDB transactions and locks, InnoDB status",
                    List.of("listSessions", "getBlockingChain", "listLocks", "listOpenTransactions", "getLongRunningQueries", "getDeadlocks")));
                checks.add(new Check("performance_schema", "performance_schema enabled (MariaDB: off by default)",
                    List.of("getTopQueries", "getWaitStats", "getRoutineStats", "getIndexUsage", "getUnusedIndexes")));
                checks.add(new Check("CONNECTION_ADMIN", "SUPER or CONNECTION_ADMIN: end other users' sessions", List.of("killSession")));
            }
            case ORACLE -> {
                checks.add(new Check("vdollar", "SELECT on V$ views (SELECT_CATALOG_ROLE or SELECT ANY DICTIONARY)",
                    List.of("listSessions", "getBlockingChain", "listLocks", "listOpenTransactions", "getLongRunningQueries",
                        "getWaitStats", "getTopQueries", "getErrorLog", "getServerConfig")));
                checks.add(new Check("ALTER SYSTEM", "ALTER SYSTEM: end sessions", List.of("killSession")));
                checks.add(new Check("dba_views", "DBA_* views (DBA role): tablespaces, all users", List.of("getSpaceUsage", "getDatabaseSizes", "listPrincipals")));
            }
            default -> {
            }
        }
        return checks;
    }

    private static boolean isAvailable(@NotNull String check, @NotNull DbFamily family, @NotNull Map<String, Boolean> facts) {
        switch (family) {
            case SQLSERVER -> {
                boolean sysadmin = is(facts, "is_sysadmin");
                return switch (check) {
                    case "error_log" -> sysadmin || is(facts, "is_securityadmin");
                    default -> sysadmin || is(facts, check);
                };
            }
            case POSTGRESQL -> {
                boolean superuser = is(facts, "is_superuser");
                return switch (check) {
                    case "pg_read_all_stats" -> superuser || is(facts, "pg_read_all_stats") || is(facts, "pg_monitor");
                    case "pg_signal_backend" -> superuser || is(facts, "pg_signal_backend");
                    case "pg_read_server_files" -> (superuser || is(facts, "pg_read_server_files")) && is(facts, "logging_collector");
                    default -> is(facts, check);
                };
            }
            case MYSQL, MARIADB -> {
                boolean all = is(facts, "grant:ALL PRIVILEGES");
                return switch (check) {
                    case "CONNECTION_ADMIN" -> all || is(facts, "grant:SUPER") || is(facts, "grant:CONNECTION_ADMIN");
                    case "performance_schema" -> is(facts, "performance_schema");
                    default -> all || is(facts, "grant:" + check);
                };
            }
            case ORACLE -> {
                return switch (check) {
                    case "vdollar" -> is(facts, "vdollar");
                    case "ALTER SYSTEM" -> is(facts, "priv:ALTER SYSTEM");
                    case "dba_views" -> is(facts, "role:DBA") || is(facts, "priv:SELECT ANY DICTIONARY");
                    default -> false;
                };
            }
            default -> {
                return false;
            }
        }
    }

    // "GRANT PROCESS, SELECT ON *.* TO `u`@`%`" → grant:PROCESS, grant:SELECT
    private static void mysqlGrants(@NotNull JsonArray rows, @NotNull Map<String, Boolean> facts) {
        for (JsonElement r : rows) {
            for (Map.Entry<String, JsonElement> column : r.getAsJsonObject().entrySet()) {
                String grant = column.getValue().isJsonNull() ? "" : column.getValue().getAsString();
                if (!grant.toUpperCase(Locale.ROOT).startsWith("GRANT ") || !grant.contains(" ON *.*")) {
                    continue;
                }
                String privileges = grant.substring(6, grant.indexOf(" ON *.*"));
                for (String privilege : privileges.split(",")) {
                    facts.put("grant:" + privilege.trim().toUpperCase(Locale.ROOT), true);
                }
            }
        }
    }

    private static boolean is(@NotNull Map<String, Boolean> facts, @NotNull String name) {
        return Boolean.TRUE.equals(facts.get(name));
    }

    @Nullable
    private static Boolean toBoolean(@NotNull JsonElement value) {
        if (value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        if (value.getAsJsonPrimitive().isNumber()) {
            return value.getAsDouble() > 0;
        }
        String text = value.getAsString().trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "on", "true", "yes", "1", "t", "pl", "all" -> true;
            case "off", "false", "no", "0", "f", "none" -> false;
            default -> null;
        };
    }
}
