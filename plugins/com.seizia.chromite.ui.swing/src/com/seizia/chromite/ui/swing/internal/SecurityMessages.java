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
 * 보안 편집기(로그인·사용자·역할 속성, 새 로그인/사용자/역할, 개체 사용 권한)의 화면 문구.
 *
 * 설계: 다른 Swing 영역과 같이 {@link NLS} 묶음 하나에 둔다(영어 {@code SecurityMessages.properties}, 한국어
 * {@code SecurityMessages_ko.properties}, OS 언어를 따름). 여러 기능을 동시에 고치는 작업이 같은 묶음 파일에서 부딪히지 않도록
 * 이 기능만의 묶음으로 나눴다. 키 접두사는 {@code security_}다. 용어는 한국어 SSMS의 보안 화면을 따른다
 * (사용 권한, 허용, 권한 부여, 거부, 보안 개체, 사용자 매핑). {0}이 있는 값은 {@link NLS#bind}로만 쓴다.
 * 권한 이름(SELECT, EXECUTE 등)과 역할 옵션의 SQL 키워드는 SQL 토큰이라 옮기지 않는다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 */
public class SecurityMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.SecurityMessages"; //$NON-NLS-1$

    public static String security_properties;
    public static String security_properties_tip;
    public static String security_permissions;
    public static String security_permissions_tip;
    public static String security_new_login;
    public static String security_new_user;
    public static String security_new_role;

    public static String security_title_login;
    public static String security_title_user;
    public static String security_title_role;
    public static String security_title_new_login;
    public static String security_title_new_user;
    public static String security_title_new_role;
    public static String security_title_object_permissions;
    public static String security_object_header;

    public static String security_page_general;
    public static String security_page_server_roles;
    public static String security_page_member_of;
    public static String security_page_roles;
    public static String security_page_user_mapping;
    public static String security_page_permissions;

    public static String security_label_login_name;
    public static String security_label_user_name;
    public static String security_label_role_name;
    public static String security_label_host;
    public static String security_host_hint;
    public static String security_label_password;
    public static String security_label_confirm_password;
    public static String security_password_unchanged_hint;
    public static String security_label_default_database;
    public static String security_label_expiry;
    public static String security_expiry_hint;
    public static String security_label_database;
    public static String security_enabled_login;
    public static String security_enabled_can_login;
    public static String security_account_locked;

    public static String security_option_superuser;
    public static String security_option_createdb;
    public static String security_option_createrole;
    public static String security_option_inherit;
    public static String security_option_replication;
    public static String security_option_bypassrls;
    public static String security_option_check_policy;
    public static String security_option_check_expiration;

    public static String security_column_member;
    public static String security_column_role;
    public static String security_column_map;
    public static String security_column_database;
    public static String security_column_user;
    public static String security_column_default_schema;
    public static String security_column_name;
    public static String security_column_type;
    public static String security_column_permission;
    public static String security_column_grant;
    public static String security_column_with_grant;
    public static String security_column_deny;

    public static String security_mapping_users;
    public static String security_mapping_roles;
    public static String security_mapping_roles_none;
    public static String security_securables;
    public static String security_users_and_roles;
    public static String security_permissions_for;
    public static String security_permissions_none;
    public static String security_add_objects;
    public static String security_add_grantees;
    public static String security_remove;
    public static String security_button_script;
    public static String security_script_title;
    public static String security_no_mapped_database;

    public static String security_kind_database;
    public static String security_kind_schema;
    public static String security_kind_table;
    public static String security_kind_view;
    public static String security_kind_sequence;
    public static String security_kind_function;
    public static String security_kind_procedure;

    public static String security_pick_objects_title;
    public static String security_pick_grantees_title;
    public static String security_pick_filter;

    public static String security_progress_load;
    public static String security_progress_apply;
    public static String security_error_load;
    public static String security_error_apply;
    public static String security_error_apply_generic;
    public static String security_error_metadata_restricted;
    public static String security_no_changes;
    public static String security_error_name_required;
    public static String security_error_password_mismatch;
    public static String security_error_password_required;
    public static String security_error_expiry;
    public static String security_error_user_required;
    public static String security_drop_title;
    public static String security_drop_title_object;
    public static String security_drop_question;

    static {
        NLS.initializeMessages(BUNDLE_NAME, SecurityMessages.class);
    }

    private SecurityMessages() {
    }
}
