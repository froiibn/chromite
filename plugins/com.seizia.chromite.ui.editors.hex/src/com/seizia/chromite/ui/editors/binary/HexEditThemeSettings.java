/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ui.editors.binary;

import org.eclipse.swt.graphics.Color;
import com.seizia.chromite.ui.ThemeColor;
import com.seizia.chromite.ui.ThemeListener;
import com.seizia.chromite.ui.controls.resultset.ThemeConstants;

/**
 * Theme settings
 */
public class HexEditThemeSettings extends ThemeListener {

    @ThemeColor("com.seizia.chromite.hex.editor.color.caret")
    public volatile Color colorCaretLine;
    @ThemeColor("com.seizia.chromite.hex.editor.color.text")
    public volatile Color colorText;

    @ThemeColor(ThemeConstants.COLOR_SQL_RESULT_HEADER_FOREGROUND)
    public volatile Color cellHeaderForeground;
    @ThemeColor(ThemeConstants.COLOR_SQL_RESULT_HEADER_BACKGROUND)
    public volatile Color cellHeaderBackground;

    public static final HexEditThemeSettings instance = new HexEditThemeSettings();
}
