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
package com.seizia.chromite.ui.swing.internal;

import org.eclipse.osgi.util.NLS;

/**
 * Texts of the SQL terminal of the query window ({@code SqlTerminalView}, Query &gt; SQL Terminal).
 *
 * 설계: 다른 작업과 같은 파일을 고치지 않도록 이 클래스에 따로 둔다
 * (영어 {@code TerminalMessages.properties}, 한국어 {@code TerminalMessages_ko.properties}).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-sql-terminal.md)
 */
public class TerminalMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.TerminalMessages"; //$NON-NLS-1$

    public static String terminal_tab;
    public static String terminal_action;
    public static String terminal_action_tip;
    public static String terminal_copy;
    public static String terminal_select_all;
    public static String terminal_clear;

    static {
        NLS.initializeMessages(BUNDLE_NAME, TerminalMessages.class);
    }

    private TerminalMessages() {
    }
}
