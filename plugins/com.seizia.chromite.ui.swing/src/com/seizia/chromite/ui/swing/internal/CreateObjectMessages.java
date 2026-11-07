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
 * Object Explorer "New …" 명령(새 개체 만들기)의 화면 문구: 메뉴 항목, 데이터베이스·스키마·테이블스페이스·확장·이름 대화상자.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 이 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code CreateObjectMessages.properties}), 한국어는 {@code CreateObjectMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code create_}다.
 * 종류 이름({@code {0}}, "Sequence")은 드라이버 번들의 탐색기 이름이라 이 묶음에 없다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-create-objects.md)
 * - 2026-11-08 이름 대화상자의 암호 오류 {@code create_error_password}, {@code create_error_password_mismatch}
 *   (swing-create-objects.md "새 사용자의 암호")
 */
public class CreateObjectMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.CreateObjectMessages"; //$NON-NLS-1$

    public static String create_new_object;
    public static String create_new_object_tip;
    public static String create_new_template_tip;
    public static String create_new_designer_tip;
    public static String create_title;
    public static String create_progress_read;
    public static String create_label_name;
    public static String create_label_owner;
    public static String create_label_template;
    public static String create_label_encoding;
    public static String create_label_lc_collate;
    public static String create_label_lc_ctype;
    public static String create_label_tablespace;
    public static String create_label_collation;
    public static String create_label_charset;
    public static String create_label_recovery;
    public static String create_label_location;
    public static String create_label_options;
    public static String create_label_schema;
    public static String create_label_database;
    public static String create_label_extension;
    public static String create_default;
    public static String create_hint_defaults;
    public static String create_options_tip;
    public static String create_column_name;
    public static String create_column_version;
    public static String create_column_description;
    public static String create_error_name;
    public static String create_error_password;
    public static String create_error_password_mismatch;
    public static String create_error_owner;
    public static String create_error_location;
    public static String create_error_extension;
    public static String create_error_schema;
    public static String create_error_collation;
    public static String create_error_read;
    public static String create_error_create;

    static {
        NLS.initializeMessages(BUNDLE_NAME, CreateObjectMessages.class);
    }

    private CreateObjectMessages() {
    }
}
