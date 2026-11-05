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
package com.seizia.chromite.ui.swing;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * File > Recent Files 목록(최근 것부터 최대 10개).
 *
 * 설계: 창 배치·테마와 같은 이 번들의 설정 저장소에 경로를 줄바꿈으로 이어 저장한다(파일 경로에는 줄바꿈이 없다).
 * 열거나 저장할 때마다 맨 앞으로 옮기고 바로 저장해, 앱이 비정상 종료되어도 목록이 남게 한다.
 *
 * 변경 이력:
 * - 2026-11-06 E단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — E단계")
 */
final class RecentFiles {

    static final int MAX_FILES = 10;

    private static final String PREF_RECENT_FILES = "sqlEditor.recentFiles"; //$NON-NLS-1$

    private RecentFiles() {
    }

    @NotNull
    static List<Path> get() {
        List<Path> files = new ArrayList<>();
        for (String line : CommonUtils.notEmpty(getPreferences().getString(PREF_RECENT_FILES)).split("\n")) { //$NON-NLS-1$
            if (!line.isBlank() && files.size() < MAX_FILES) {
                try {
                    files.add(Path.of(line.trim()));
                } catch (IllegalArgumentException e) {
                    // Saved by another platform; skip
                }
            }
        }
        return files;
    }

    /**
     * Moves the file to the top of the list (opened or saved).
     */
    static void add(@NotNull Path file) {
        List<Path> files = get();
        Path normalized = file.toAbsolutePath().normalize();
        files.remove(normalized);
        files.addFirst(normalized);
        save(files);
    }

    /**
     * Removes a file that no longer exists.
     */
    static void remove(@NotNull Path file) {
        List<Path> files = get();
        if (files.remove(file.toAbsolutePath().normalize())) {
            save(files);
        }
    }

    private static void save(@NotNull List<Path> files) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < files.size() && i < MAX_FILES; i++) {
            if (!value.isEmpty()) {
                value.append('\n');
            }
            value.append(files.get(i));
        }
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_RECENT_FILES, value.toString());
        PrefUtils.savePreferenceStore(preferences);
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }
}
