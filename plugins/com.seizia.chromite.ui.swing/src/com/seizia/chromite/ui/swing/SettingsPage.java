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
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;

/**
 * A settings page of model preference keys that can be edited for all connections (Tools &gt; Options) or for one
 * connection (connection dialog, Options &gt; Settings).
 *
 * 설계: SWT의 {@code TargetPrefPage}처럼 한 페이지 클래스를 두 곳에서 쓴다. 페이지는 어느 저장소에 쓰는지 모르고
 * {@link #load}로 읽고 {@link #save}로 {@link Writer}에 쓴다.
 * <ul>
 *     <li>전역(Options): 쓰는 곳은 플랫폼 설정 저장소다. 바뀐 값만 쓰고, 바뀐 것이 있으면 저장한다({@link #apply}).</li>
 *     <li>연결별: 쓰는 곳은 연결의 설정 저장소({@code DataSourcePreferenceStore})다. 부모가 플랫폼 저장소라 연결이 자기 값을 갖지 않은 키는
 *     전역 값을 따른다. 연결별 값을 켜면 페이지의 모든 키를 연결의 값으로 쓴다(전역 값과 같더라도: 나중에 전역 값을 바꿔도 이 연결은 그대로).
 *     끄면 {@link #clear}로 연결의 값을 지운다. 켜고 끄는 틀은 {@link ConnectionSettingsOverrides}에 있다.</li>
 *     <li>모델은 키를 연결의 설정 저장소에서 읽는다(키마다 읽는 곳은 docs/chromite/swing-options.md). 그래서 연결 대화상자에 넣는 페이지는
 *     연결별 값이 실제로 효과가 있는 키만 가진다. 모델이 플랫폼 저장소에서만 읽는 키의 페이지(Connections, Drivers, Logs 등)도 같은 틀을 쓰고
 *     Options에만 넣는다.</li>
 *     <li>전역 값이 플랫폼 저장소가 아닌 번들 저장소에 있는 키(데이터 전송 매핑은 {@code data.transfer} 번들)는 {@link #getGlobalStore}를
 *     바꾼다. 연결은 그 키를 자기 값으로 가질 때만 쓰고, 없으면 모델이 번들 저장소를 읽는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 연결별 설정(SWT 연결 속성 페이지) (docs/chromite/swing-options.md "연결별 설정")
 * - 2026-11-08 전역 저장소를 페이지가 정함({@link #getGlobalStore}), {@link Writer#setLong}: 남은 설정 키 페이지
 *   (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
abstract class SettingsPage extends JPanel {

    protected SettingsPage() {
        super(new BorderLayout());
    }

    /**
     * The page title (the Options tab and the connection's settings list).
     */
    @NotNull
    abstract String getTitle();

    /**
     * Every key the page writes. A connection overrides the page when its store has a value of its own for any of them.
     */
    @NotNull
    abstract Collection<String> getKeys();

    /**
     * Shows the values of a store (its own values, or the parent's ones for the keys it does not set).
     */
    abstract void load(@NotNull DBPPreferenceStore store);

    /**
     * Writes the shown values.
     */
    abstract void save(@NotNull Writer writer);

    /**
     * Whether the model reads the page's keys at each operation, so that a change on a connected connection needs no
     * reconnect. Pages whose keys are read when a session opens (or are cached per connection) return {@code false}:
     * the connection dialog then asks to reconnect, and the reconnect asks about open transactions first.
     */
    boolean isAppliedWithoutReconnect() {
        return true;
    }

    /**
     * Updates the enabled state of the controls that depend on other controls (called after the page is enabled).
     */
    void updateEnablement() {
    }

    /**
     * Removes the store's own values of the page's keys (the connection follows the global settings again).
     */
    void clear(@NotNull DBPPreferenceStore store) {
        for (String key : getKeys()) {
            if (store.contains(key)) {
                store.setToDefault(key);
            }
        }
    }

    /**
     * The store of the global values: the platform settings, unless the model reads the page's keys from another store.
     */
    @NotNull
    DBPPreferenceStore getGlobalStore() {
        return DBWorkbench.getPlatform().getPreferenceStore();
    }

    /**
     * Saves the shown values to the global settings (Options dialog OK).
     */
    void apply() {
        DBPPreferenceStore store = getGlobalStore();
        Writer writer = new Writer(store, false);
        save(writer);
        if (writer.isChanged()) {
            PrefUtils.savePreferenceStore(store);
        }
    }

    /**
     * Writes values to a store: only changed values into the global settings, every value into a connection that
     * overrides the page (its own values then stay when the global settings change later).
     */
    static final class Writer {
        private final DBPPreferenceStore store;
        private final boolean override;
        private boolean changed;

        Writer(@NotNull DBPPreferenceStore store, boolean override) {
            this.store = store;
            this.override = override;
        }

        @NotNull
        DBPPreferenceStore getStore() {
            return store;
        }

        boolean isOverride() {
            return override;
        }

        boolean isChanged() {
            return changed;
        }

        void markChanged() {
            changed = true;
        }

        void setBoolean(@NotNull String key, boolean value) {
            if (override || value != store.getBoolean(key)) {
                store.setValue(key, value);
                changed = true;
            }
        }

        void setInt(@NotNull String key, int value) {
            if (override || value != store.getInt(key)) {
                store.setValue(key, value);
                changed = true;
            }
        }

        void setLong(@NotNull String key, long value) {
            if (override || value != store.getLong(key)) {
                store.setValue(key, value);
                changed = true;
            }
        }

        void setString(@NotNull String key, @NotNull String value) {
            if (override || !value.equals(store.getString(key))) {
                store.setValue(key, value);
                changed = true;
            }
        }

        /**
         * A text value where an empty field means the default (an empty prefix or mark would make every statement look like a
         * parameter or a command). A connection stores the default itself, so that it does not take the global value.
         */
        void setStringOrDefault(@NotNull String key, @NotNull String value) {
            if (!CommonUtils.isEmpty(value)) {
                setString(key, value);
            } else if (override) {
                setString(key, CommonUtils.notEmpty(DBWorkbench.getPlatform().getPreferenceStore().getDefaultString(key)));
            } else if (!store.isDefault(key)) {
                store.setToDefault(key);
                changed = true;
            }
        }
    }
}
