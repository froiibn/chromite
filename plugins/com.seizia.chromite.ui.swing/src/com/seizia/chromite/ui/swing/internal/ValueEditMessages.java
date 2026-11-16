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
 * 결과 값 편집기(참조 값 고르기, SET, 배열·구조체·hstore, 16진수, 외부 편집기, UUID 만들기)의 화면 문구.
 *
 * 설계: 기능마다 묶음 하나를 두는 Swing 관례({@link GridEditMessages}와 같음, 다른 작업과 같은 파일을 고치지 않게)를 따른다. 영어가 기본
 * ({@code ValueEditMessages.properties}), 한국어는 {@code ValueEditMessages_ko.properties}이고 OS 언어로 고른다. 키 접두사는 {@code valueedit_}다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 결과 값 편집기 나머지 (docs/chromite/swing-value-editors.md)
 */
public class ValueEditMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.ValueEditMessages"; //$NON-NLS-1$

    // Grid commands (ResultSetGrid)
    public static String valueedit_edit_value;
    public static String valueedit_edit_value_tip;
    public static String valueedit_generate_uuid;
    public static String valueedit_uuid_skipped;
    public static String valueedit_no_dialog;

    // Value dialogs (ResultSetValueDialogs)
    public static String valueedit_set_title;
    public static String valueedit_set_hint;
    public static String valueedit_array_title;
    public static String valueedit_struct_title;
    public static String valueedit_hstore_title;
    public static String valueedit_col_index;
    public static String valueedit_col_value;
    public static String valueedit_col_attribute;
    public static String valueedit_col_type;
    public static String valueedit_col_key;
    public static String valueedit_add;
    public static String valueedit_remove;
    public static String valueedit_move_up;
    public static String valueedit_move_down;
    public static String valueedit_item_null;
    public static String valueedit_value_null;
    public static String valueedit_invalid_item;
    public static String valueedit_duplicate_key;
    public static String valueedit_empty_key;
    public static String valueedit_nested_read_only;
    public static String valueedit_cant_edit;
    public static String valueedit_no_element_type;
    public static String valueedit_hint_text;

    // Referenced value picker (ResultSetReferencePicker)
    public static String valueedit_ref_title;
    public static String valueedit_ref_source;
    public static String valueedit_ref_search;
    public static String valueedit_ref_col_value;
    public static String valueedit_ref_col_description;
    public static String valueedit_ref_more;
    public static String valueedit_ref_loading;
    public static String valueedit_ref_count;
    public static String valueedit_ref_count_more;
    public static String valueedit_ref_no_dictionary;
    public static String valueedit_ref_error;

    // Hex editor (ResultSetHexEditor)
    public static String valueedit_hex_menu;
    public static String valueedit_hex_title;
    public static String valueedit_hex_size;
    public static String valueedit_hex_invalid_char;
    public static String valueedit_hex_odd;
    public static String valueedit_hex_too_large;
    public static String valueedit_hex_format;
    public static String valueedit_hex_read_only;
    public static String valueedit_hex_read_title;

    // External editor (ResultSetExternalEditor)
    public static String valueedit_external_menu;
    public static String valueedit_external_title;
    public static String valueedit_external_watching;
    public static String valueedit_external_view_only;
    public static String valueedit_external_applied;
    public static String valueedit_external_cant_apply;
    public static String valueedit_external_stopped;
    public static String valueedit_external_cant_open;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ValueEditMessages.class);
    }

    private ValueEditMessages() {
    }
}
