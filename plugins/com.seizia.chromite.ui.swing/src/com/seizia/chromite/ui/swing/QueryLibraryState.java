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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Query Library 쿼리마다 이 PC에만 두는 상태: 매개변수의 마지막 입력값, 예약 실행을 이 PC에서 돌릴지, 확인하지 않은 알림.
 *
 * 설계: 쿼리 파일은 공유될 수 있으므로(다른 사람의 PC, Git) 개인 입력값은 파일에 쓰지 않고 이 번들의 설정 저장소에 쿼리 ID로 저장한다
 * ({@link QueryLibraryItem#getStateKey}). ID는 파일을 옮겨도 그대로라 입력값도 따라간다. 모델의 {@code SQLQueryParameterRegistry}
 * (SWT 편집기의 매개변수 기록)는 이름 하나에 값 하나를 모든 쿼리가 함께 쓰므로, 쿼리마다 다른 값을 기억하려고 쓰지 않았다.
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "매개변수")
 * - 2026-11-06 Query Library ③: 예약 활성 여부와 알림 표시 (docs/chromite/swing-query-library.md "예약 실행")
 */
final class QueryLibraryState {

    private static final String PREF_LAST_VALUES = "queryLibrary.lastValues."; //$NON-NLS-1$
    private static final String PREF_SCHEDULE_ACTIVE = "queryLibrary.schedule.active."; //$NON-NLS-1$
    private static final String PREF_ALERT = "queryLibrary.alert."; //$NON-NLS-1$

    private QueryLibraryState() {
    }

    /**
     * Last values by parameter name (empty if the query never ran with parameters on this computer).
     */
    @NotNull
    static Map<String, String> getLastValues(@NotNull QueryLibraryItem item) {
        Map<String, String> result = new LinkedHashMap<>();
        String value = getPreferences().getString(PREF_LAST_VALUES + item.getStateKey());
        if (CommonUtils.isEmpty(value)) {
            return result;
        }
        for (String line : value.split("\n")) { //$NON-NLS-1$
            int separator = line.indexOf('=');
            if (separator > 0) {
                result.put(QueryLibraryFile.unescape(line.substring(0, separator)), QueryLibraryFile.unescape(line.substring(separator + 1)));
            }
        }
        return result;
    }

    static void setLastValues(@NotNull QueryLibraryItem item, @NotNull Map<String, String> values) {
        StringBuilder value = new StringBuilder();
        // Parameter names are SQL identifiers (no '='), so the first '=' separates name and value
        values.forEach((name, parameterValue) -> value.append(QueryLibraryFile.escape(name))
            .append('=').append(QueryLibraryFile.escape(CommonUtils.notEmpty(parameterValue))).append('\n'));
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_LAST_VALUES + item.getStateKey(), value.toString());
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * Whether the query's schedule runs on this computer. Off unless turned on here (a shared query does not start
     * running on every computer that reads it).
     */
    static boolean isScheduleActive(@NotNull QueryLibraryItem item) {
        return getPreferences().getBoolean(PREF_SCHEDULE_ACTIVE + item.getStateKey());
    }

    static void setScheduleActive(@NotNull QueryLibraryItem item, boolean active) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_SCHEDULE_ACTIVE + item.getStateKey(), active);
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * Whether a scheduled run met its condition and the user has not looked at it yet (tree badge).
     */
    static boolean hasAlert(@NotNull QueryLibraryItem item) {
        return getPreferences().getBoolean(PREF_ALERT + item.getStateKey());
    }

    static void setAlert(@NotNull QueryLibraryItem item, boolean alert) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_ALERT + item.getStateKey(), alert);
        PrefUtils.savePreferenceStore(preferences);
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }
}
