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
package com.seizia.chromite.model.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The updater's own state file {@code <updates>/update.json}: the automatic check switch and the record of the last
 * verification failure.
 *
 * 설계: 작업 공간 설정이 아니라 업데이트 폴더에 둔다. 업데이트는 설치(PC 사용자) 단위라 작업 공간을 바꿔도 같아야 하고,
 * 실패 기록이 다시 시작해도 남아야 하루 한 번 재시도 규칙이 줄지 않는다(Turbrix {@code update.json}과 같음).
 * 읽지 못하면 기본값(켬, 실패 기록 없음)이다. 쓰기는 새 이름에 쓴 뒤 바꿔 넣는다(읽는 쪽이 반쪽 파일을 보지 않게).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
final class UpdatePrefs {

    static final String FILE_NAME = "update.json"; //$NON-NLS-1$

    /**
     * The version whose file did not pass a check, when, which check, and whether the user dismissed the notice.
     */
    record Failure(@NotNull String version, long at, @Nullable UpdateStep step, boolean dismissed) {
    }

    boolean enabled = true;
    @Nullable
    Failure failed;

    private final Path file;

    private UpdatePrefs(@NotNull Path file) {
        this.file = file;
    }

    @NotNull
    static UpdatePrefs read(@NotNull Path folder) {
        UpdatePrefs prefs = new UpdatePrefs(folder.resolve(FILE_NAME));
        try {
            JsonElement root = JsonParser.parseString(Files.readString(prefs.file, StandardCharsets.UTF_8));
            if (root instanceof JsonObject o) {
                prefs.enabled = !(o.get("enabled") instanceof JsonPrimitive p && p.isBoolean() && !p.getAsBoolean()); //$NON-NLS-1$
                if (o.get("failed") instanceof JsonObject f //$NON-NLS-1$
                    && f.get("version") instanceof JsonPrimitive v && v.isString() && UpdateVersion.isValid(v.getAsString()) //$NON-NLS-1$
                    && f.get("at") instanceof JsonPrimitive at && at.isNumber()) { //$NON-NLS-1$
                    UpdateStep step = null;
                    if (f.get("step") instanceof JsonPrimitive s && s.isString()) { //$NON-NLS-1$
                        for (UpdateStep candidate : UpdateStep.values()) {
                            if (candidate.id().equals(s.getAsString())) {
                                step = candidate;
                            }
                        }
                    }
                    prefs.failed = new Failure(v.getAsString(), at.getAsLong(), step,
                        f.get("dismissed") instanceof JsonPrimitive d && d.isBoolean() && d.getAsBoolean()); //$NON-NLS-1$
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // (defaults: on, no failure)
        }
        return prefs;
    }

    void write() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled); //$NON-NLS-1$
        if (failed != null) {
            JsonObject f = new JsonObject();
            f.addProperty("version", failed.version()); //$NON-NLS-1$
            f.addProperty("at", failed.at()); //$NON-NLS-1$
            if (failed.step() != null) {
                f.addProperty("step", failed.step().id()); //$NON-NLS-1$
            }
            if (failed.dismissed()) {
                f.addProperty("dismissed", true); //$NON-NLS-1$
            }
            o.add("failed", f); //$NON-NLS-1$
        }
        try {
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(FILE_NAME + "." + ProcessHandle.current().pid() + ".part"); //$NON-NLS-1$ //$NON-NLS-2$
            Files.writeString(part, o.toString(), StandardCharsets.UTF_8);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ignored) {
            // (the choice lasts for this start)
        }
    }
}
