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
 * Texts of the Scripts tool window ({@code ScriptsPanel}): the project's script files and folders.
 *
 * 설계: 다른 작업과 같은 파일을 고치지 않도록 이 클래스에 따로 둔다
 * (영어 {@code ScriptsMessages.properties}, 한국어 {@code ScriptsMessages_ko.properties}).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-scripts-explorer.md)
 */
public class ScriptsMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ScriptsMessages"; //$NON-NLS-1$

    public static String scripts_title;
    public static String scripts_no_project;
    public static String scripts_tree_not_created;
    public static String scripts_tree_link_missing;
    public static String scripts_tip_link;
    public static String scripts_tip_link_missing;
    public static String scripts_open;
    public static String scripts_open_tip;
    public static String scripts_new_folder;
    public static String scripts_new_folder_tip;
    public static String scripts_new_folder_prompt;
    public static String scripts_rename;
    public static String scripts_rename_tip;
    public static String scripts_rename_script_prompt;
    public static String scripts_rename_folder_prompt;
    public static String scripts_delete;
    public static String scripts_delete_tip;
    public static String scripts_confirm_delete_script;
    public static String scripts_confirm_delete_folder;
    public static String scripts_delete_open;
    public static String scripts_link;
    public static String scripts_link_tip;
    public static String scripts_link_title;
    public static String scripts_link_reason;
    public static String scripts_unlink;
    public static String scripts_unlink_tip;
    public static String scripts_refresh;
    public static String scripts_refresh_tip;
    public static String scripts_error_create_folder;
    public static String scripts_error_rename;
    public static String scripts_error_delete;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ScriptsMessages.class);
    }

    private ScriptsMessages() {
    }
}
