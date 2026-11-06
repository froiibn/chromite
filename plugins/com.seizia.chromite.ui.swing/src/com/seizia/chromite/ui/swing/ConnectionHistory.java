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
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Connect 대화상자의 기록: 드라이버별 최근 서버 이름(SSMS "Server name" 목록)과 최근에 쓴 데이터베이스 종류.
 *
 * 설계: {@link RecentFiles}와 같이 이 번들의 설정 저장소(작업 공간 {@code .metadata})에 줄바꿈으로 이어 저장한다.
 * 서버 이름 기록은 드라이버의 전체 ID({@code 공급자:드라이버})별로 따로 둔다. 같은 서버 이름이라도 드라이버마다 포트와 의미가
 * 다르기 때문이다. 연결 설정 파일이 아니라 UI 설정에 두는 이유는 SSMS처럼 지운 연결의 서버도 목록에 남기기 위해서다.
 * 연결하거나 저장할 때마다 맨 앞으로 옮기고 바로 저장한다(최대 {@value #MAX_ITEMS}개).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: SSMS식 Connect 대화상자 (docs/chromite/swing-frontend.md "Connect 대화상자 (SSMS식)")
 */
final class ConnectionHistory {

    static final int MAX_ITEMS = 10;

    private static final String PREF_SERVER_HISTORY = "connectDialog.serverHistory."; //$NON-NLS-1$
    private static final String PREF_RECENT_DRIVERS = "connectDialog.recentDrivers"; //$NON-NLS-1$

    private ConnectionHistory() {
    }

    /**
     * Server names used with the driver, the most recent first.
     */
    @NotNull
    static List<String> getServers(@NotNull DBPDriver driver) {
        return read(PREF_SERVER_HISTORY + driver.getFullId());
    }

    static void addServer(@NotNull DBPDriver driver, @NotNull String server) {
        add(PREF_SERVER_HISTORY + driver.getFullId(), server);
    }

    /**
     * Full IDs ({@link DBPDriver#getFullId()}) of the drivers connections were made with, the most recent first.
     */
    @NotNull
    static List<String> getRecentDrivers() {
        return read(PREF_RECENT_DRIVERS);
    }

    static void addRecentDriver(@NotNull DBPDriver driver) {
        add(PREF_RECENT_DRIVERS, driver.getFullId());
    }

    @NotNull
    private static List<String> read(@NotNull String key) {
        List<String> items = new ArrayList<>();
        for (String line : CommonUtils.notEmpty(getPreferences().getString(key)).split("\n")) { //$NON-NLS-1$
            String item = line.trim();
            if (!item.isEmpty() && !items.contains(item) && items.size() < MAX_ITEMS) {
                items.add(item);
            }
        }
        return items;
    }

    private static void add(@NotNull String key, @NotNull String value) {
        String item = value.trim();
        if (item.isEmpty() || item.contains("\n")) { //$NON-NLS-1$
            return;
        }
        List<String> items = read(key);
        items.remove(item);
        items.addFirst(item);
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(key, String.join("\n", items.subList(0, Math.min(items.size(), MAX_ITEMS)))); //$NON-NLS-1$
        PrefUtils.savePreferenceStore(preferences);
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }
}
