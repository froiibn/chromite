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
 * Texts of the connection lifecycle: initialization SQL and SQLite extension approval, several connections' disconnect,
 * Invalidate/Reconnect, the connection lost and driver error dialogs, running jobs at exit, smart commit and the
 * transaction log's previous transactions.
 *
 * 설계: 다른 작업과 같은 파일을 고치지 않도록 이 클래스에 따로 둔다
 * (영어 {@code LifecycleMessages.properties}, 한국어 {@code LifecycleMessages_ko.properties}).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-lifecycle.md)
 */
public class LifecycleMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.LifecycleMessages"; //$NON-NLS-1$

    // Approval of code from connection settings (ExecutionApprovalDialog)
    public static String approval_connection;
    public static String approval_url;
    public static String approval_allow;
    public static String approval_allow_connect;
    public static String approval_cancel_note;
    public static String init_approval_title;
    public static String init_approval_message;
    public static String init_approval_script;
    public static String init_approval_remember_note;
    public static String sqlite_ext_title;
    public static String sqlite_ext_message;
    public static String sqlite_ext_libraries;
    public static String sqlite_ext_queries;
    public static String sqlite_ext_remember_note;

    // Disconnecting several connections, Invalidate/Reconnect (ConnectionCommands, ExitTransactionCheck)
    public static String disconnect_others;
    public static String disconnect_all;
    public static String disconnect_group;
    public static String disconnect_many_txn_message;
    public static String invalidate;
    public static String invalidate_tip;
    public static String reconnect_txn_message;
    public static String reconnect_txn_error;

    // Connection lost and driver errors (DatabaseErrorDialogs)
    public static String lost_title;
    public static String lost_message;
    public static String lost_reconnect_failed_message;
    public static String lost_reconnect;
    public static String lost_disconnect;
    public static String lost_retry;
    public static String driver_config_title;
    public static String driver_config_message;
    public static String driver_config_edit;
    public static String driver_license_title;
    public static String driver_license_message;

    // Running jobs at exit (RunningJobsCheck)
    public static String exit_jobs_title;
    public static String exit_jobs_message;
    public static String exit_jobs_more;
    public static String exit_jobs_tasks;
    public static String exit_jobs_wait;
    public static String exit_jobs_stop;
    public static String exit_jobs_waiting;
    public static String exit_jobs_stopping;

    // Smart commit (ConnectionTypesPage, SmartCommit)
    public static String smart_commit;
    public static String smart_commit_tip;
    public static String smart_commit_recover;
    public static String smart_commit_recover_tip;

    // Transaction log: previous transactions (TransactionLogDialog)
    public static String txn_log_show_previous;
    public static String txn_log_show_previous_tip;
    public static String txn_log_col_transaction;
    public static String txn_log_open;
    public static String txn_log_committed;
    public static String txn_log_rolled_back;

    static {
        NLS.initializeMessages(BUNDLE_NAME, LifecycleMessages.class);
    }

    private LifecycleMessages() {
    }
}
