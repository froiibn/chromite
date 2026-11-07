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

import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.ui.swing.internal.ObjectExplorerMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * Object Explorer settings: what a double click does, how many objects a long folder shows at a time, and Link with Editor.
 *
 * 설계: SWT 탐색기 설정({@code NavigatorPreferences})의 키 이름을 그대로 쓴다({@code navigator.object.doubleClick},
 * {@code navigator.connection.doubleClick}, {@code navigator.long.list.fetch.size}, {@code navigator.sync.editor.datasource}).
 * SWT 값은 지운 {@code ui.navigator} 번들의 설정 저장소에 있었으므로 옮겨 올 값은 없고, 이 앱의 Swing 번들 저장소
 * ({@link ChromiteSwingActivator#getPreferences})에 둔다. 기본값은 지금까지의 Swing 동작이다: 두 번 클릭은 펼치기/접기(SWT 객체 기본은
 * 편집기 열기였지만 Swing 트리는 처음부터 SSMS처럼 펼쳤다), 긴 목록은 SWT 기본 5000개씩(최소 100), 편집기와 연결은 꺼짐(SWT와 같음).
 * 값은 열거형 이름으로 저장하고 모르는 값은 기본값으로 읽는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-explorer-productivity.md)
 */
final class ExplorerPreferences {

    static final String PREF_OBJECT_DOUBLE_CLICK = "navigator.object.doubleClick"; //$NON-NLS-1$
    static final String PREF_CONNECTION_DOUBLE_CLICK = "navigator.connection.doubleClick"; //$NON-NLS-1$
    static final String PREF_LONG_LIST_FETCH_SIZE = "navigator.long.list.fetch.size"; //$NON-NLS-1$
    static final String PREF_LINK_WITH_EDITOR = "navigator.sync.editor.datasource"; //$NON-NLS-1$

    /**
     * SWT {@code NavigatorPreferences.NAVIGATOR_LONG_LIST_FETCH_SIZE} default and its minimum.
     */
    static final int DEFAULT_PAGE_SIZE = 5000;
    static final int MIN_PAGE_SIZE = 100;
    static final int MAX_PAGE_SIZE = 100000;

    /**
     * Double click on a database object (not a folder, connection, database or schema: those always expand).
     */
    enum ObjectDoubleClick {
        EXPAND,
        /**
         * Design for tables, Modify for views and routines, else Object Details.
         */
        OPEN_EDITOR,
        /**
         * Select Top N Rows for tables and views, else expand.
         */
        OPEN_DATA;

        @NotNull
        String getLabel() {
            return switch (this) {
                case EXPAND -> ObjectExplorerMessages.oe_dblclick_expand;
                case OPEN_EDITOR -> ObjectExplorerMessages.oe_dblclick_open_editor;
                case OPEN_DATA -> NLS.bind(ObjectExplorerMessages.oe_dblclick_open_data, ObjectExplorerPanel.SELECT_TOP_ROWS);
            };
        }
    }

    /**
     * Double click on a connection node.
     */
    enum ConnectionDoubleClick {
        EXPAND,
        NEW_QUERY,
        EDIT_CONNECTION;

        @NotNull
        String getLabel() {
            return switch (this) {
                case EXPAND -> ObjectExplorerMessages.oe_dblclick_expand_connect;
                case NEW_QUERY -> ObjectExplorerMessages.oe_dblclick_new_query;
                case EDIT_CONNECTION -> ObjectExplorerMessages.oe_dblclick_edit_connection;
            };
        }
    }

    private static boolean defaultsSet;

    private ExplorerPreferences() {
    }

    @NotNull
    static ObjectDoubleClick getObjectDoubleClick() {
        return parse(ObjectDoubleClick.class, getPreferences().getString(PREF_OBJECT_DOUBLE_CLICK), ObjectDoubleClick.EXPAND);
    }

    @NotNull
    static ConnectionDoubleClick getConnectionDoubleClick() {
        return parse(ConnectionDoubleClick.class, getPreferences().getString(PREF_CONNECTION_DOUBLE_CLICK), ConnectionDoubleClick.EXPAND);
    }

    /**
     * Objects a folder shows before its "Load more…" row, at least {@link #MIN_PAGE_SIZE} as in SWT.
     */
    static int getPageSize() {
        return Math.clamp(getPreferences().getInt(PREF_LONG_LIST_FETCH_SIZE), MIN_PAGE_SIZE, MAX_PAGE_SIZE);
    }

    static boolean isLinkWithEditor() {
        return getPreferences().getBoolean(PREF_LINK_WITH_EDITOR);
    }

    static void setLinkWithEditor(boolean link) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_LINK_WITH_EDITOR, link);
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * Saves the Options values (one save for all three).
     */
    static void save(@NotNull ObjectDoubleClick objectDoubleClick, @NotNull ConnectionDoubleClick connectionDoubleClick, int pageSize) {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_OBJECT_DOUBLE_CLICK, objectDoubleClick.name());
        preferences.setValue(PREF_CONNECTION_DOUBLE_CLICK, connectionDoubleClick.name());
        preferences.setValue(PREF_LONG_LIST_FETCH_SIZE, Math.clamp(pageSize, MIN_PAGE_SIZE, MAX_PAGE_SIZE));
        PrefUtils.savePreferenceStore(preferences);
    }

    @NotNull
    private static <E extends Enum<E>> E parse(@NotNull Class<E> type, @Nullable String value, @NotNull E defaultValue) {
        if (value != null) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(value)) {
                    return constant;
                }
            }
        }
        return defaultValue;
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        if (!defaultsSet) {
            preferences.setDefault(PREF_OBJECT_DOUBLE_CLICK, ObjectDoubleClick.EXPAND.name());
            preferences.setDefault(PREF_CONNECTION_DOUBLE_CLICK, ConnectionDoubleClick.EXPAND.name());
            preferences.setDefault(PREF_LONG_LIST_FETCH_SIZE, DEFAULT_PAGE_SIZE);
            preferences.setDefault(PREF_LINK_WITH_EDITOR, false);
            defaultsSet = true;
        }
        return preferences;
    }
}
