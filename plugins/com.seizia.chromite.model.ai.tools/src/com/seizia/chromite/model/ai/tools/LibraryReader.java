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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.impl.preferences.BundlePreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Read-only access to the Query Library files of the Swing frontend (docs/chromite/swing-query-library.md "저장 형식"),
 * plus the {@code @threshold} header entries of the agent (docs/chromite/ai-agent.md "Query Library 기준값").
 *
 * 설계: 라이브러리 저장소({@code QueryLibrary}, {@code QueryLibraryFile})는 Swing 번들 안에 있어 모델 번들이 쓸 수 없다. 같은 설정
 * ({@code com.seizia.chromite.ui.swing} 설정의 {@code queryLibrary.roots}: 줄마다 "이름\t경로\t읽기 전용", 비면 {@code <작업 공간>/QueryLibrary})과
 * 같은 머리말 형식을 읽기만 한다. 쓰지 않으므로 Swing 쪽 파일과 충돌하지 않는다. Swing 파서는 모르는 머리말 줄을 보존하므로
 * {@code @threshold}를 더해도 Swing에서 저장할 때 지워지지 않는다. 저장소가 모델 번들로 옮겨지면 이 클래스를 지운다(ai-agent.md 위험 목록).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
final class LibraryReader {

    private static final Log log = Log.getLog(LibraryReader.class);

    private static final String SWING_BUNDLE = "com.seizia.chromite.ui.swing"; //$NON-NLS-1$
    private static final String PREF_ROOTS = "queryLibrary.roots"; //$NON-NLS-1$
    private static final String DEFAULT_ROOT_FOLDER = "QueryLibrary"; //$NON-NLS-1$
    private static final String KEY_PREFIX = "-- @"; //$NON-NLS-1$
    private static final String SUB_PREFIX = "--   "; //$NON-NLS-1$
    private static final int MAX_FILES = 5000;

    record Root(@NotNull String name, @NotNull Path path, boolean readOnly) {
    }

    record Parameter(@NotNull String name, @Nullable String label, @Nullable String type, @Nullable String defaultValue,
                     boolean required, @NotNull List<String> choices) {
    }

    record Threshold(@NotNull String column, @Nullable String warn, @Nullable String critical, @NotNull String scope,
                     @Nullable String label) {
    }

    record Item(
        @NotNull String id,
        @NotNull String name,
        @Nullable String description,
        @NotNull String root,
        @NotNull String folder,
        @NotNull Path file,
        @Nullable String target,
        @Nullable String targetId,
        @Nullable String targetName,
        @Nullable String database,
        @Nullable String schema,
        @NotNull List<Parameter> parameters,
        @NotNull List<Threshold> thresholds,
        @NotNull String sql
    ) {
    }

    private LibraryReader() {
    }

    @NotNull
    static List<Root> roots() {
        List<Root> roots = new ArrayList<>();
        String value = null;
        try {
            value = new BundlePreferenceStore(SWING_BUNDLE).getString(PREF_ROOTS);
        } catch (Throwable e) {
            log.debug("Can't read the Query Library roots", e);
        }
        if (value != null && !value.isBlank()) {
            for (String line : value.split("\n")) {
                String[] parts = line.split("\t");
                if (parts.length >= 2 && !parts[1].isBlank()) {
                    try {
                        roots.add(new Root(parts[0], Path.of(parts[1]), parts.length > 2 && Boolean.parseBoolean(parts[2])));
                    } catch (IllegalArgumentException e) {
                        log.debug("Bad Query Library root: " + line);
                    }
                }
            }
        }
        if (roots.isEmpty()) {
            Path workspace = DBWorkbench.getPlatform().getWorkspace().getAbsolutePath();
            roots.add(new Root("Library", workspace.resolve(DEFAULT_ROOT_FOLDER), false));
        }
        return roots;
    }

    @NotNull
    static List<Item> list() throws DBException {
        List<Item> items = new ArrayList<>();
        for (Root root : roots()) {
            if (!Files.isDirectory(root.path())) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root.path())) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted()
                    .limit(MAX_FILES)::iterator) {
                    try {
                        items.add(read(root, file));
                    } catch (IOException e) {
                        log.debug("Can't read library file " + file, e);
                    }
                }
            } catch (IOException e) {
                throw new DBException("Can't read the Query Library folder " + root.path(), e);
            }
        }
        return items;
    }

    @NotNull
    static Item find(@NotNull String idOrPath) throws DBException {
        String key = idOrPath.trim();
        Item byName = null;
        for (Item item : list()) {
            if (item.id().equalsIgnoreCase(key) || item.file().toString().equalsIgnoreCase(key)) {
                return item;
            }
            String relative = (item.folder().isEmpty() ? "" : item.folder() + "/") + item.name();
            if (relative.equalsIgnoreCase(key) || item.name().equalsIgnoreCase(key)) {
                byName = item;
            }
        }
        if (byName != null) {
            return byName;
        }
        throw new com.seizia.chromite.model.ai.agent.AIToolArgumentException("Library query '" + key + "' not found; call listLibraryQueries");
    }

    @NotNull
    static Item read(@NotNull Root root, @NotNull Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\\R", -1);
        Map<String, String> values = new LinkedHashMap<>();
        List<Parameter> parameters = new ArrayList<>();
        List<Threshold> thresholds = new ArrayList<>();
        int index = 0;
        String section = null;
        String sectionName = null;
        Map<String, List<String>> sub = new LinkedHashMap<>();
        while (index < lines.length) {
            String line = lines[index];
            if (line.startsWith(KEY_PREFIX)) {
                flush(section, sectionName, sub, parameters, thresholds);
                sub = new LinkedHashMap<>();
                String entry = line.substring(KEY_PREFIX.length());
                int colon = entry.indexOf(':');
                int space = entry.indexOf(' ');
                if (colon > 0 && (space < 0 || colon < space)) {
                    values.put(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
                    section = null;
                    sectionName = null;
                } else {
                    section = space > 0 ? entry.substring(0, space).trim() : entry.trim();
                    sectionName = space > 0 ? entry.substring(space + 1).trim() : "";
                }
            } else if (line.startsWith(SUB_PREFIX) && section != null) {
                String entry = line.substring(SUB_PREFIX.length());
                int colon = entry.indexOf(':');
                if (colon > 0) {
                    sub.computeIfAbsent(entry.substring(0, colon).trim(), k -> new ArrayList<>()).add(entry.substring(colon + 1).trim());
                }
            } else {
                break;
            }
            index++;
        }
        flush(section, sectionName, sub, parameters, thresholds);
        if (index < lines.length && lines[index].isBlank() && index > 0) {
            index++;
        }
        StringBuilder sql = new StringBuilder();
        for (int i = index; i < lines.length; i++) {
            sql.append(lines[i]).append(i < lines.length - 1 ? "\n" : "");
        }
        String fileName = file.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - 4);
        Path parent = file.getParent();
        String folder = parent == null || parent.equals(root.path()) ? "" : root.path().relativize(parent).toString().replace('\\', '/');
        return new Item(
            values.getOrDefault("id", file.toString()),
            values.getOrDefault("name", baseName),
            values.get("description"),
            root.name(),
            folder,
            file,
            values.get("target"),
            values.get("target-id"),
            values.get("target-name"),
            values.get("database"),
            values.get("schema"),
            parameters,
            thresholds,
            sql.toString().strip()
        );
    }

    private static void flush(
        @Nullable String section,
        @Nullable String name,
        @NotNull Map<String, List<String>> sub,
        @NotNull List<Parameter> parameters,
        @NotNull List<Threshold> thresholds
    ) {
        if (section == null || name == null || name.isEmpty()) {
            return;
        }
        if (section.equals("param")) {
            parameters.add(new Parameter(name, first(sub, "label"), first(sub, "type"), first(sub, "default"),
                "true".equalsIgnoreCase(first(sub, "required")), sub.getOrDefault("choice", List.of())));
        } else if (section.equals("threshold")) {
            String scope = first(sub, "scope");
            thresholds.add(new Threshold(name, first(sub, "warn"), first(sub, "critical"), scope == null ? "any" : scope.toLowerCase(Locale.ROOT),
                first(sub, "label")));
        }
    }

    @Nullable
    private static String first(@NotNull Map<String, List<String>> sub, @NotNull String key) {
        List<String> values = sub.get(key);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }
}
