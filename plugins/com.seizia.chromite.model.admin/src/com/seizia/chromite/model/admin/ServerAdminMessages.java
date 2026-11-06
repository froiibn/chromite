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
package com.seizia.chromite.model.admin;

import org.eclipse.osgi.util.NLS;

/**
 * 서버 관리 도구의 문구(세션 필터와 명령 이름).
 *
 * 설계: SWT에서는 DB별 UI 플러그인({@code MySQLUIMessages}, {@code OracleUIMessages})과 편집기 코드에 있던 문구다.
 * Swing 화면은 SWT UI 플러그인을 쓸 수 없어, DB별 세션 명령을 정의하는 {@link ServerAdminProfile}과 같은 번들에 둔다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-admin-tools.md)
 */
public class ServerAdminMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.model.admin.ServerAdminMessages"; //$NON-NLS-1$

    public static String filter_show_idle;
    public static String filter_show_idle_tip;
    public static String filter_only_connections;
    public static String filter_only_connections_tip;
    public static String filter_hide_sleeping;
    public static String filter_hide_sleeping_tip;
    public static String filter_show_performance;
    public static String filter_show_performance_tip;
    public static String filter_show_background;
    public static String filter_show_background_tip;
    public static String filter_show_inactive;
    public static String filter_show_inactive_tip;
    public static String action_terminate;
    public static String action_terminate_tip;
    public static String action_cancel_query;
    public static String action_cancel_query_tip;
    public static String action_kill_session;
    public static String action_kill_session_tip;
    public static String action_kill_query;
    public static String action_kill_query_tip;
    public static String action_disconnect_session;
    public static String action_disconnect_session_tip;
    public static String action_immediate;

    static {
        NLS.initializeMessages(BUNDLE_NAME, ServerAdminMessages.class);
    }

    private ServerAdminMessages() {
    }
}
