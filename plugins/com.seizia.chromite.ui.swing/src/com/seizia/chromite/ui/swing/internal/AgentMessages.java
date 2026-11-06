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
 * SQL Server 에이전트 관리(작업, 작업 활동 모니터, 경고, 운영자, 오류 로그)의 화면 문구.
 *
 * 설계: 다른 Swing 영역과 같이 {@link NLS} 묶음 하나에 둔다(영어 {@code AgentMessages.properties}, 한국어
 * {@code AgentMessages_ko.properties}, OS 언어를 따름). 여러 기능을 동시에 고치는 작업이 같은 묶음 파일에서 부딪히지 않도록
 * 이 기능만의 묶음으로 나눴다. 키 접두사는 {@code agent_}다. 한국어 용어는 SQL Server 에이전트의 한국어 화면을 따른다
 * (작업, 단계, 일정, 경고, 운영자, 작업 활동 모니터). {0}이 있는 값은 {@link NLS#bind}로만 쓴다.
 * 탐색기 노드 이름과 모델 객체의 속성 값(상태, 결과)은 모델 번들의 {@code SQLServerAgentMessages}에 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class AgentMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.AgentMessages"; //$NON-NLS-1$

    public static String agent_new_job;
    public static String agent_new_alert;
    public static String agent_new_operator;
    public static String agent_start_job;
    public static String agent_start_job_title;
    public static String agent_start_job_tip;
    public static String agent_start_job_prompt;
    public static String agent_stop_job;
    public static String agent_stop_job_tip;
    public static String agent_enable;
    public static String agent_disable;
    public static String agent_view_history;
    public static String agent_view_activity;
    public static String agent_view_log;
    public static String agent_properties;
    public static String agent_properties_tip;
    public static String agent_refresh;
    public static String agent_disabled_suffix;
    public static String agent_tip_not_running;
    public static String agent_tip_not_supported;
    public static String agent_tip_no_steps;
    public static String agent_started;
    public static String agent_stopped;
    public static String agent_job_enabled;
    public static String agent_job_disabled;
    public static String agent_delete_title;
    public static String agent_delete_title_object;
    public static String agent_delete_question;
    public static String agent_script_title;
    public static String agent_progress_read;
    public static String agent_reading;
    public static String agent_connecting;
    public static String agent_search_placeholder;
    public static String agent_error_read;
    public static String agent_error_apply;
    public static String agent_error_metadata_restricted;
    public static String agent_error_no_name;
    public static String agent_failed_statement;
    public static String agent_no_changes;
    public static String agent_success;
    public static String agent_ok;
    public static String agent_cancel;
    public static String agent_name;
    public static String agent_column_name;
    public static String agent_enabled;
    public static String agent_page_general;
    public static String agent_page_advanced;
    public static String agent_page_steps;
    public static String agent_page_schedules;
    public static String agent_page_notifications;
    public static String agent_page_response;
    public static String agent_page_options;
    public static String agent_button_new;
    public static String agent_button_insert;
    public static String agent_button_edit;
    public static String agent_button_delete;
    public static String agent_button_remove;
    public static String agent_button_move_up;
    public static String agent_button_move_down;
    public static String agent_job_title;
    public static String agent_job_new_title;
    public static String agent_job_owner;
    public static String agent_job_category;
    public static String agent_job_description;
    public static String agent_job_error_no_owner;
    public static String agent_job_steps_list;
    public static String agent_job_start_step;
    public static String agent_job_column_step;
    public static String agent_job_column_type;
    public static String agent_job_column_on_success;
    public static String agent_job_column_on_failure;
    public static String agent_job_column_description;
    public static String agent_job_schedules_list;
    public static String agent_job_notify_header;
    public static String agent_job_notify_email;
    public static String agent_job_notify_operator;
    public static String agent_job_notify_when;
    public static String agent_job_notify_eventlog;
    public static String agent_job_notify_delete;
    public static String agent_job_level_success;
    public static String agent_job_level_failure;
    public static String agent_job_level_completion;
    public static String agent_job_no_operators;
    public static String agent_step_title;
    public static String agent_step_new_title;
    public static String agent_step_item;
    public static String agent_step_name;
    public static String agent_step_type;
    public static String agent_step_database;
    public static String agent_step_command;
    public static String agent_step_type_tsql;
    public static String agent_step_type_cmdexec;
    public static String agent_step_type_powershell;
    public static String agent_step_on_success;
    public static String agent_step_on_failure;
    public static String agent_step_retry_attempts;
    public static String agent_step_retry_interval;
    public static String agent_step_action_next;
    public static String agent_step_action_quit_success;
    public static String agent_step_action_quit_failure;
    public static String agent_step_action_goto;
    public static String agent_step_action_goto_short;
    public static String agent_step_error_no_name;
    public static String agent_step_error_no_command;
    public static String agent_schedule_title;
    public static String agent_schedule_new_title;
    public static String agent_schedule_type;
    public static String agent_schedule_type_recurring;
    public static String agent_schedule_type_once;
    public static String agent_schedule_type_agent_start;
    public static String agent_schedule_type_idle;
    public static String agent_schedule_section_once;
    public static String agent_schedule_section_frequency;
    public static String agent_schedule_section_daily;
    public static String agent_schedule_section_duration;
    public static String agent_schedule_section_summary;
    public static String agent_schedule_date;
    public static String agent_schedule_time;
    public static String agent_schedule_occurs;
    public static String agent_schedule_daily;
    public static String agent_schedule_weekly;
    public static String agent_schedule_monthly;
    public static String agent_schedule_recurs_every;
    public static String agent_schedule_unit_days;
    public static String agent_schedule_unit_weeks;
    public static String agent_schedule_unit_months;
    public static String agent_schedule_unit_hours;
    public static String agent_schedule_unit_minutes;
    public static String agent_schedule_unit_seconds;
    public static String agent_schedule_month_day;
    public static String agent_schedule_month_relative;
    public static String agent_schedule_first;
    public static String agent_schedule_second;
    public static String agent_schedule_third;
    public static String agent_schedule_fourth;
    public static String agent_schedule_last;
    public static String agent_schedule_occurs_once_at;
    public static String agent_schedule_occurs_every;
    public static String agent_schedule_starting_at;
    public static String agent_schedule_ending_at;
    public static String agent_schedule_start_date;
    public static String agent_schedule_end_date;
    public static String agent_schedule_no_end_date;
    public static String agent_schedule_error_no_day;
    public static String agent_schedule_error_end_date;
    public static String agent_schedule_desc_once;
    public static String agent_schedule_desc_agent_start;
    public static String agent_schedule_desc_idle;
    public static String agent_schedule_desc_daily;
    public static String agent_schedule_desc_weekly;
    public static String agent_schedule_desc_monthly;
    public static String agent_schedule_desc_monthly_relative;
    public static String agent_schedule_desc_at;
    public static String agent_schedule_desc_every;
    public static String agent_schedule_desc_from;
    public static String agent_schedule_desc_range;
    public static String agent_schedule_desc_disabled;
    public static String agent_day_monday;
    public static String agent_day_tuesday;
    public static String agent_day_wednesday;
    public static String agent_day_thursday;
    public static String agent_day_friday;
    public static String agent_day_saturday;
    public static String agent_day_sunday;
    public static String agent_day_day;
    public static String agent_day_weekday;
    public static String agent_day_weekend_day;
    public static String agent_operator_title;
    public static String agent_operator_new_title;
    public static String agent_operator_section_notification;
    public static String agent_operator_email;
    public static String agent_alert_title;
    public static String agent_alert_new_title;
    public static String agent_alert_type;
    public static String agent_alert_type_event;
    public static String agent_alert_type_other;
    public static String agent_alert_section_event;
    public static String agent_alert_database;
    public static String agent_alert_all_databases;
    public static String agent_alert_raised_by;
    public static String agent_alert_error_number;
    public static String agent_alert_severity;
    public static String agent_alert_error_no_number;
    public static String agent_alert_execute_job;
    public static String agent_alert_job;
    public static String agent_alert_notify_operators;
    public static String agent_alert_column_operator;
    public static String agent_alert_column_email;
    public static String agent_alert_no_operators;
    public static String agent_alert_section_include;
    public static String agent_alert_include_text;
    public static String agent_alert_section_delay;
    public static String agent_alert_delay_minutes;
    public static String agent_alert_delay_seconds;
    public static String agent_activity_title;
    public static String agent_activity_refresh_tip;
    public static String agent_activity_status;
    public static String agent_history_title;
    public static String agent_history_title_all;
    public static String agent_history_tooltip;
    public static String agent_history_status;
    public static String agent_history_empty;
    public static String agent_history_column_date;
    public static String agent_history_column_job;
    public static String agent_history_column_outcome;
    public static String agent_history_column_duration;
    public static String agent_history_column_message;
    public static String agent_history_column_step_id;
    public static String agent_history_column_step_name;
    public static String agent_log_title;
    public static String agent_log_file;
    public static String agent_log_column_type;
    public static String agent_log_level_error;
    public static String agent_log_level_warning;
    public static String agent_log_level_information;
    public static String agent_log_status;

    static {
        NLS.initializeMessages(BUNDLE_NAME, AgentMessages.class);
    }

    private AgentMessages() {
    }
}
