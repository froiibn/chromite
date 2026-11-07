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
 * Texts of the shell command approval dialog ({@code SwingShellCommandsService}).
 *
 * 설계: 다른 작업과 같은 파일을 고치지 않도록 이 클래스에 따로 둔다
 * (영어 {@code ShellCommandMessages.properties}, 한국어 {@code ShellCommandMessages_ko.properties}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/shell-command-approval.md)
 * - 2026-11-08 항상 허용 안내에 저장된 작업 범위를 더함 (docs/chromite/shell-command-approval.md "저장된 네이티브 도구 작업")
 */
public class ShellCommandMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ShellCommandMessages"; //$NON-NLS-1$

    public static String shell_cmd_title;
    public static String shell_cmd_message;
    public static String shell_cmd_working_directory;
    public static String shell_cmd_command;
    public static String shell_cmd_always_note;
    public static String shell_cmd_cancel_note;
    public static String shell_cmd_run_once;
    public static String shell_cmd_always;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ShellCommandMessages.class);
    }

    private ShellCommandMessages() {
    }
}
