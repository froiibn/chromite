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
 * Texts of the job scheduler screens (Oracle Scheduler, pg_cron, MySQL / MariaDB events): menus, dialogs, document tabs.
 *
 * 설계: 기능마다 자기 묶음을 두는 Swing 화면 문구 규칙(docs/chromite/swing-frontend.md)을 따른다. 영어가 기본이고 한국어는 _ko다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
public class SchedulerMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.SchedulerMessages"; //$NON-NLS-1$

    public static String scheduler_new_job;
    public static String scheduler_new_event;
    public static String scheduler_run_now;
    public static String scheduler_run_now_title;
    public static String scheduler_run_prompt;
    public static String scheduler_run_wait;
    public static String scheduler_run_scheduler_stopped;
    public static String scheduler_run_started;
    public static String scheduler_run_finished;
    public static String scheduler_stop_job;
    public static String scheduler_stop_job_tip;
    public static String scheduler_stop_job_force;
    public static String scheduler_stop_job_force_tip;
    public static String scheduler_stopped;
    public static String scheduler_enable;
    public static String scheduler_disable;
    public static String scheduler_enabled;
    public static String scheduler_disabled;
    public static String scheduler_view_history;
    public static String scheduler_view_running;
    public static String scheduler_event_status;
    public static String scheduler_refresh;
    public static String scheduler_search_placeholder;
    public static String scheduler_reading;
    public static String scheduler_rows_status;
    public static String scheduler_rows_empty;
    public static String scheduler_script_title;
    public static String scheduler_success;
    public static String scheduler_failed_statement;
    public static String scheduler_no_changes;
    public static String scheduler_delete_title;
    public static String scheduler_delete_title_object;
    public static String scheduler_delete_question;
    public static String scheduler_error_apply;
    public static String scheduler_error_read;
    public static String scheduler_error_metadata_restricted;
    public static String scheduler_error_no_name;
    public static String scheduler_disabled_suffix;
    public static String scheduler_running_suffix;
    public static String scheduler_disabled_schedule;
    public static String scheduler_cron_launcher_stopped;
    public static String scheduler_name;
    public static String scheduler_comments;
    public static String scheduler_enabled_check;
    public static String scheduler_start_date;
    public static String scheduler_end_date;
    public static String scheduler_page_general;
    public static String scheduler_page_schedule;
    public static String scheduler_build;
    public static String scheduler_build_tip;
    public static String scheduler_close;
    public static String scheduler_history_title;
    public static String scheduler_history_tooltip;
    public static String scheduler_history_note_oracle;
    public static String scheduler_history_note_cron;
    public static String scheduler_running_title;
    public static String scheduler_running_tooltip;
    public static String scheduler_detail_errors;
    public static String scheduler_detail_info;
    public static String scheduler_detail_output;
    public static String scheduler_detail_message;
    public static String scheduler_detail_command;
    public static String scheduler_tip_oracle_create;
    public static String scheduler_tip_oracle_alter;
    public static String scheduler_tip_oracle_force;
    public static String scheduler_tip_mysql_event;
    public static String scheduler_tip_cron_not_installed;
    public static String scheduler_tip_cron_no_access;
    public static String scheduler_tip_cron_unknown;
    public static String scheduler_tip_cron_no_history;
    public static String scheduler_tip_cron_no_alter;
    public static String scheduler_event_status_on;
    public static String scheduler_event_status_off;
    public static String scheduler_event_status_disabled;
    public static String scheduler_event_status_unknown;
    public static String scheduler_event_no_history;
    public static String scheduler_event_command_label;
    public static String scheduler_event_open_command;
    public static String scheduler_event_copy_command;
    public static String builder_title;
    public static String builder_frequency;
    public static String builder_every_minutes;
    public static String builder_every_hours;
    public static String builder_daily;
    public static String builder_weekly;
    public static String builder_monthly;
    public static String builder_interval;
    public static String builder_time;
    public static String builder_days;
    public static String builder_day_of_month;
    public static String builder_result;
    public static String oracle_job_new_title;
    public static String oracle_job_title;
    public static String oracle_schema;
    public static String oracle_job_type;
    public static String oracle_type_plsql;
    public static String oracle_type_procedure;
    public static String oracle_type_note;
    public static String oracle_program_note;
    public static String oracle_action;
    public static String oracle_procedure;
    public static String oracle_procedure_note;
    public static String oracle_auto_drop;
    public static String oracle_repeat_interval;
    public static String oracle_repeat_placeholder;
    public static String oracle_date_placeholder;
    public static String oracle_schedule_help;
    public static String oracle_schedule_note;
    public static String oracle_next_runs;
    public static String oracle_next_runs_tip;
    public static String oracle_next_runs_once;
    public static String oracle_next_runs_none;
    public static String oracle_error_no_action;
    public static String cron_job_new_title;
    public static String cron_job_title;
    public static String cron_name_placeholder;
    public static String cron_schedule;
    public static String cron_schedule_placeholder;
    public static String cron_schedule_help;
    public static String cron_database;
    public static String cron_command;
    public static String cron_note_no_alter;
    public static String cron_note_launcher_stopped;
    public static String cron_error_name_exists;
    public static String cron_error_name_needed;
    public static String cron_error_no_schedule;
    public static String cron_error_no_command;
    public static String mysql_event_new_title;
    public static String mysql_event_title;
    public static String mysql_database;
    public static String mysql_status;
    public static String mysql_status_enabled;
    public static String mysql_status_disabled;
    public static String mysql_status_replica_disabled;
    public static String mysql_preserve;
    public static String mysql_last_executed;
    public static String mysql_never_executed;
    public static String mysql_no_history;
    public static String mysql_body;
    public static String mysql_recurring;
    public static String mysql_once;
    public static String mysql_every;
    public static String mysql_starts;
    public static String mysql_ends;
    public static String mysql_at;
    public static String mysql_date_placeholder;
    public static String mysql_time_zone;
    public static String mysql_time_zone_new;
    public static String mysql_warning_off;
    public static String mysql_warning_disabled;
    public static String mysql_command_tip;
    public static String mysql_error_no_interval;
    public static String mysql_error_no_body;
    public static String scheduler_properties_tip;

    static {
        NLS.initializeMessages(BUNDLE_NAME, SchedulerMessages.class);
    }

    private SchedulerMessages() {
    }
}
