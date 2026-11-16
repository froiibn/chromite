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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Judges a library query result against its {@code @threshold} entries (docs/chromite/ai-agent.md "Query Library 기준값").
 *
 * 설계: 기준값은 {@code <연산자> <값>} 하나로 단순하게 둔다. 표현식 언어를 넣지 않은 이유: 파일을 동료와 공유하므로 사람이 읽고 고치기 쉬워야 하고,
 * 평가가 SQL이나 코드 실행이 되면 안 된다. 범위({@code scope})로 여러 행의 값을 하나로 모은다(any, all, max, min, sum, count).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
final class ThresholdEvaluator {

    private static final Pattern CONDITION = Pattern.compile("^\\s*(>=|<=|!=|<>|=|>|<|not-contains|contains)\\s*(.+?)\\s*$");

    enum Status {
        OK, WARN, CRITICAL, NO_DATA, NO_THRESHOLDS
    }

    private ThresholdEvaluator() {
    }

    @NotNull
    static JsonObject evaluate(@NotNull List<LibraryReader.Threshold> thresholds, @NotNull JsonArray rows) {
        JsonObject json = new JsonObject();
        if (thresholds.isEmpty()) {
            json.addProperty("status", Status.NO_THRESHOLDS.name());
            json.addProperty("note", "No thresholds are set for this query: report the values and flag only obvious problems");
            return json;
        }
        Status overall = Status.OK;
        JsonArray results = new JsonArray();
        for (LibraryReader.Threshold threshold : thresholds) {
            JsonObject result = new JsonObject();
            result.addProperty("column", threshold.column());
            if (threshold.label() != null) {
                result.addProperty("label", threshold.label());
            }
            List<JsonElement> values = new ArrayList<>();
            for (JsonElement r : rows) {
                if (r.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : r.getAsJsonObject().entrySet()) {
                        if (e.getKey().equalsIgnoreCase(threshold.column())) {
                            values.add(e.getValue());
                        }
                    }
                }
            }
            Status status;
            if (values.isEmpty() && !threshold.scope().equals("count")) {
                status = Status.NO_DATA;
            } else if (matches(threshold.critical(), threshold.scope(), values, rows.size(), result, "critical")) {
                status = Status.CRITICAL;
            } else if (matches(threshold.warn(), threshold.scope(), values, rows.size(), result, "warn")) {
                status = Status.WARN;
            } else {
                status = Status.OK;
            }
            result.addProperty("status", status.name());
            results.add(result);
            if (status.ordinal() > overall.ordinal() && status != Status.NO_DATA) {
                overall = status;
            }
        }
        json.addProperty("status", overall.name());
        json.add("thresholds", results);
        return json;
    }

    private static boolean matches(
        @Nullable String condition,
        @NotNull String scope,
        @NotNull List<JsonElement> values,
        int rowCount,
        @NotNull JsonObject result,
        @NotNull String level
    ) {
        if (condition == null) {
            return false;
        }
        Matcher m = CONDITION.matcher(condition);
        if (!m.matches()) {
            result.addProperty(level + "Error", "bad condition '" + condition + "'");
            return false;
        }
        String op = m.group(1);
        String expected = unquote(m.group(2));
        switch (scope) {
            case "max", "min", "sum", "count" -> {
                Double observed = aggregate(scope, values, rowCount);
                result.addProperty("observed", observed);
                return observed != null && compare(op, observed.toString(), expected);
            }
            case "all" -> {
                result.addProperty("observedRows", values.size());
                return !values.isEmpty() && values.stream().allMatch(v -> compare(op, text(v), expected));
            }
            default -> {
                JsonArray matching = new JsonArray();
                for (int i = 0; i < values.size(); i++) {
                    if (compare(op, text(values.get(i)), expected)) {
                        matching.add(i + 1);
                    }
                }
                if (!matching.isEmpty()) {
                    result.add(level + "Rows", matching);
                }
                if (values.size() == 1) {
                    result.add("observed", values.getFirst());
                }
                return !matching.isEmpty();
            }
        }
    }

    @Nullable
    private static Double aggregate(@NotNull String scope, @NotNull List<JsonElement> values, int rowCount) {
        if (scope.equals("count")) {
            return (double) rowCount;
        }
        Double result = null;
        for (JsonElement v : values) {
            Double d = number(text(v));
            if (d == null) {
                continue;
            }
            result = result == null ? d : switch (scope) {
                case "max" -> Math.max(result, d);
                case "min" -> Math.min(result, d);
                default -> result + d;
            };
        }
        return result;
    }

    private static boolean compare(@NotNull String op, @Nullable String observed, @NotNull String expected) {
        if (observed == null) {
            return false;
        }
        if (op.equals("contains")) {
            return observed.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
        }
        if (op.equals("not-contains")) {
            return !observed.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
        }
        Double a = number(observed);
        Double b = number(expected);
        int cmp;
        if (a != null && b != null) {
            cmp = Double.compare(a, b);
        } else {
            cmp = observed.compareToIgnoreCase(expected);
        }
        return switch (op) {
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case "!=", "<>" -> cmp != 0;
            default -> cmp == 0;
        };
    }

    @Nullable
    private static String text(@NotNull JsonElement value) {
        return value.isJsonNull() ? null : value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    @Nullable
    private static Double number(@Nullable String text) {
        if (text == null) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @NotNull
    private static String unquote(@NotNull String value) {
        if (value.length() >= 2 && (value.startsWith("'") && value.endsWith("'") || value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
