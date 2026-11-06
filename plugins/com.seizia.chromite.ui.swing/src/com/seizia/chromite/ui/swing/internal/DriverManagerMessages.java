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
 * 드라이버 관리자(Tools &gt; Driver Manager…, 드라이버 편집, 라이브러리 탭, Connect 대화상자의 Edit Driver…) 화면 문구.
 *
 * 설계: 다른 기능과 같은 파일을 고치지 않도록(병합 충돌) 이 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code DriverManagerMessages.properties}), 한국어는 {@code DriverManagerMessages_ko.properties}이고, 플랫폼이 OS 언어로 고른다.
 * 키 접두사는 관리자 {@code drivermgr_}, 편집 {@code driveredit_}, 라이브러리 {@code driverlib_}다.
 * {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-driver-manager.md)
 */
public class DriverManagerMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.DriverManagerMessages"; //$NON-NLS-1$

    public static String drivermgr_menu;
    public static String drivermgr_menu_tip;
    public static String drivermgr_title;
    public static String drivermgr_search;
    public static String drivermgr_button_new;
    public static String drivermgr_button_copy;
    public static String drivermgr_button_edit;
    public static String drivermgr_button_delete;
    public static String drivermgr_button_restore;
    public static String drivermgr_button_close;
    public static String drivermgr_legend_custom;
    public static String drivermgr_legend_unavailable;
    public static String drivermgr_category_description;
    public static String drivermgr_offline;
    public static String drivermgr_offline_tip;
    public static String drivermgr_delete_title;
    public static String drivermgr_delete_custom;
    public static String drivermgr_delete_builtin;
    public static String drivermgr_delete_used_title;
    public static String drivermgr_delete_used;
    public static String drivermgr_restore_title;
    public static String drivermgr_restore_hint;
    public static String drivermgr_restore_none;
    public static String drivermgr_save_error;

    public static String driveredit_title_new;
    public static String driveredit_title_edit;
    public static String driveredit_tab_settings;
    public static String driveredit_tab_libraries;
    public static String driveredit_tab_libraries_tip;
    public static String driveredit_tab_connection_properties;
    public static String driveredit_tab_connection_properties_tip;
    public static String driveredit_tab_parameters;
    public static String driveredit_tab_parameters_tip;
    public static String driveredit_tab_license;
    public static String driveredit_name;
    public static String driveredit_type;
    public static String driveredit_class;
    public static String driveredit_class_tip;
    public static String driveredit_url;
    public static String driveredit_url_tip;
    public static String driveredit_port;
    public static String driveredit_database;
    public static String driveredit_user;
    public static String driveredit_embedded;
    public static String driveredit_embedded_tip;
    public static String driveredit_propagate;
    public static String driveredit_propagate_tip;
    public static String driveredit_anonymous;
    public static String driveredit_anonymous_tip;
    public static String driveredit_empty_password;
    public static String driveredit_empty_password_tip;
    public static String driveredit_legacy;
    public static String driveredit_legacy_tip;
    public static String driveredit_thread_safe;
    public static String driveredit_thread_safe_tip;
    public static String driveredit_section_description;
    public static String driveredit_id;
    public static String driveredit_id_tip;
    public static String driveredit_provider_id;
    public static String driveredit_provider_id_tip;
    public static String driveredit_description;
    public static String driveredit_website;
    public static String driveredit_read_only;
    public static String driveredit_reset;
    public static String driveredit_reset_tip;
    public static String driveredit_name_required;
    public static String driveredit_exists_title;
    public static String driveredit_exists;
    public static String driveredit_copy_name;
    public static String driveredit_button_edit_driver;
    public static String driveredit_button_edit_driver_tip;

    public static String driverlib_add_file;
    public static String driverlib_add_file_tip;
    public static String driverlib_add_folder;
    public static String driverlib_add_folder_tip;
    public static String driverlib_remove;
    public static String driverlib_download;
    public static String driverlib_download_tip;
    public static String driverlib_download_offline;
    public static String driverlib_download_none;
    public static String driverlib_driver_class;
    public static String driverlib_find_class;
    public static String driverlib_find_class_tip;
    public static String driverlib_find_class_task;
    public static String driverlib_find_class_none;
    public static String driverlib_find_class_error;
    public static String driverlib_open_files_title;
    public static String driverlib_open_folder_title;
    public static String driverlib_filter_jars;
    public static String driverlib_filter_native;
    public static String driverlib_remove_title;
    public static String driverlib_remove_confirm;
    public static String driverlib_state_bundled;
    public static String driverlib_state_local;
    public static String driverlib_state_downloaded;
    public static String driverlib_state_not_downloaded;
    public static String driverlib_state_not_downloaded_offline;
    public static String driverlib_state_missing;
    public static String driverlib_optional;
    public static String driverlib_hint;

    static {
        NLS.initializeMessages(BUNDLE_NAME, DriverManagerMessages.class);
    }

    private DriverManagerMessages() {
    }
}
