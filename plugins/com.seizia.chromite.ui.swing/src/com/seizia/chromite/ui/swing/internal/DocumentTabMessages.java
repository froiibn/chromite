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
 * 메인 창 문서 탭의 오른쪽 클릭 메뉴 문구({@code DocumentTabMenu}).
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 문서 탭 메뉴만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code DocumentTabMessages.properties}), 한국어는 {@code DocumentTabMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code tab_}다.
 * 한국어 메뉴 이름은 끝에 말줄임표를 붙이지 않는다(앱 변경 #9).
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 문서 탭 오른쪽 클릭 메뉴(앱 변경 #42) (docs/chromite/swing-frontend.md "문서 탭 메뉴")
 */
public class DocumentTabMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.DocumentTabMessages"; //$NON-NLS-1$

    public static String tab_save;
    public static String tab_rename;
    public static String tab_rename_title;
    public static String tab_rename_prompt;
    public static String tab_rename_error;
    public static String tab_close;
    public static String tab_close_all;
    public static String tab_close_others;
    public static String tab_reopen_closed;
    public static String tab_copy_path;
    public static String tab_open_folder;
    public static String tab_open_folder_error;
    public static String tab_pin;
    public static String tab_unpin;
    public static String tab_position;
    public static String tab_position_top;
    public static String tab_position_left;
    public static String tab_position_right;
    public static String tab_multiple_rows;
    public static String tab_library_rename_modified;

    static {
        NLS.initializeMessages(BUNDLE_NAME, DocumentTabMessages.class);
    }

    private DocumentTabMessages() {
    }
}
