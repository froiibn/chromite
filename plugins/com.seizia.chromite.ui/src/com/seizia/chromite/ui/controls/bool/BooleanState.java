/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.ui.controls.bool;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.internal.UIMessages;

public enum BooleanState {
    CHECKED(
        "checked",
        UIMessages.control_boolean_state_checked,
        UIIcon.CHECK_ON,
        new String[]{"☑", "[v]", "yes", String.valueOf(true)}
    ),
    UNCHECKED(
        "unchecked",
        UIMessages.control_boolean_state_unchecked,
        UIIcon.CHECK_OFF,
        new String[]{"☐", "[ ]", "no", String.valueOf(false)}
    ),
    NULL(
        "null",
        UIMessages.control_boolean_state_null,
        UIIcon.CHECK_QUEST,
        new String[]{"☒", "[?]", DBConstants.NULL_VALUE_LABEL}
    );

    private final String id;
    private final String label;
    private final DBIcon icon;
    private final String[] presets;

    BooleanState(@NotNull String id, @NotNull String label, @NotNull DBIcon icon, @NotNull String[] presets) {
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.presets = presets;
    }

    @NotNull
    public String getId() {
        return id;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    @NotNull
    public DBIcon getIcon() {
        return icon;
    }

    @NotNull
    public String[] getPresets() {
        return presets;
    }

    @NotNull
    public <T> T choose(@NotNull T checked, @NotNull T unchecked, @NotNull T none) {
        return this == CHECKED ? checked : this == UNCHECKED ? unchecked : none;
    }
}
