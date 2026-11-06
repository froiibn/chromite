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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.model.admin.security.Securable;
import com.seizia.chromite.model.admin.security.SecurityDialect;
import com.seizia.chromite.model.admin.security.SecurityPrincipal;
import com.seizia.chromite.ui.swing.internal.SecurityMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;

/**
 * Screen texts for the values of the security model (object types, option ids, principal kinds).
 *
 * 설계: 모델 번들({@code com.seizia.chromite.model.admin.security})은 화면 문구 없이 식별자(옵션 id, 종류 enum)만 넘긴다.
 * 옵션 id는 SQL 키워드({@code SUPERUSER}, {@code CHECK_POLICY})라 모르는 id는 그대로 보여 준다(새 서버 옵션이 생겨도 화면이 깨지지 않는다).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 */
final class SecurityLabels {

    private SecurityLabels() {
    }

    @NotNull
    static String getKindLabel(@NotNull Securable.Kind kind) {
        return switch (kind) {
            case DATABASE -> SecurityMessages.security_kind_database;
            case SCHEMA -> SecurityMessages.security_kind_schema;
            case TABLE -> SecurityMessages.security_kind_table;
            case VIEW -> SecurityMessages.security_kind_view;
            case SEQUENCE -> SecurityMessages.security_kind_sequence;
            case FUNCTION -> SecurityMessages.security_kind_function;
            case PROCEDURE -> SecurityMessages.security_kind_procedure;
        };
    }

    @NotNull
    static String getOptionLabel(@NotNull String option) {
        return switch (option) {
            case "SUPERUSER" -> SecurityMessages.security_option_superuser; //$NON-NLS-1$
            case "CREATEDB" -> SecurityMessages.security_option_createdb; //$NON-NLS-1$
            case "CREATEROLE" -> SecurityMessages.security_option_createrole; //$NON-NLS-1$
            case "INHERIT" -> SecurityMessages.security_option_inherit; //$NON-NLS-1$
            case "REPLICATION" -> SecurityMessages.security_option_replication; //$NON-NLS-1$
            case "BYPASSRLS" -> SecurityMessages.security_option_bypassrls; //$NON-NLS-1$
            case "CHECK_POLICY" -> SecurityMessages.security_option_check_policy; //$NON-NLS-1$
            case "CHECK_EXPIRATION" -> SecurityMessages.security_option_check_expiration; //$NON-NLS-1$
            default -> option;
        };
    }

    @NotNull
    static String getEnabledLabel(@NotNull SecurityDialect.EnabledStyle style) {
        return switch (style) {
            case ENABLED -> SecurityMessages.security_enabled_login;
            case CAN_LOGIN -> SecurityMessages.security_enabled_can_login;
            case NOT_LOCKED -> SecurityMessages.security_account_locked;
        };
    }

    @NotNull
    static String getMembershipTitle(@NotNull SecurityDialect.MembershipStyle style) {
        return switch (style) {
            case SERVER_ROLES -> SecurityMessages.security_page_server_roles;
            case MEMBER_OF -> SecurityMessages.security_page_member_of;
            case ROLES -> SecurityMessages.security_page_roles;
        };
    }

    @NotNull
    static String getNameLabel(@NotNull SecurityPrincipal.Kind kind) {
        return switch (kind) {
            case LOGIN -> SecurityMessages.security_label_login_name;
            case USER -> SecurityMessages.security_label_user_name;
            case ROLE -> SecurityMessages.security_label_role_name;
        };
    }

    @NotNull
    static String getTitle(@NotNull SecurityPrincipal principal) {
        if (principal.isNew()) {
            return switch (principal.getKind()) {
                case LOGIN -> SecurityMessages.security_title_new_login;
                case USER -> SecurityMessages.security_title_new_user;
                case ROLE -> SecurityMessages.security_title_new_role;
            };
        }
        String pattern = switch (principal.getKind()) {
            case LOGIN -> SecurityMessages.security_title_login;
            case USER -> SecurityMessages.security_title_user;
            case ROLE -> SecurityMessages.security_title_role;
        };
        return NLS.bind(pattern, principal.getName());
    }
}
