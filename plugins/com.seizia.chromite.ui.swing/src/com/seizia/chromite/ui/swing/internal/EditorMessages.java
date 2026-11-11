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
 * Texts of the SQL editor area: query window, execution plan, results grid and its value panel, record view,
 * Copy As, CSV/Excel export, query history, bind parameters and SQL templates.
 *
 * 설계: DBeaver의 {@code *Messages}(예: {@code SQLEditorMessages})와 같이 {@link NLS} 필드와 같은 이름의 properties 파일을 쓴다.
 * 영어가 기본({@code EditorMessages.properties}), 한국어는 {@code EditorMessages_ko.properties}이고 언어는 OS(플랫폼 NL)를 따른다.
 * 자리 표시자가 있는 문구는 {@code NLS.bind}로 채우므로 그 문구의 작은따옴표는 {@code ''}로 쓴다. 단수/복수는 문장을 따로 둔다
 * ({@code _one}/{@code _many}). 화면마다 클래스를 나누지 않고 편집기 영역 하나로 둔 것은 다른 영역(연결, 개체 탐색기 등)의
 * Messages 클래스와 나란히 두어 영역별로 따로 고칠 수 있게 하기 위해서다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 편집기 영역 화면 문구를 옮김(OS 언어를 따름)
 * - 2026-11-07 쿼리 창 도구 막대 삭제로 쓰지 않게 된 {@code editor_sql_cancel} 삭제. 실행·중지·계획 단추의 도구 설명은
 *   메인 창 SQL Editor 도구 막대가 씀 (docs/chromite/swing-frontend.md "SQL Editor 도구 막대")
 * - 2026-11-07 쿼리 상태 줄의 커밋 방식 칸 문구({@code editor_status_auto_commit} 등) (docs/chromite/swing-frontend.md "자동 커밋 상태")
 * - 2026-11-07 결과 필터 줄(ResultSetFilterBar)을 없애며 그 문구를 지움. 열 필터 문구는 {@link GridFilterMessages}
 * - 2026-11-07 결과 표 저장 전 스크립트 미리 보기 문구({@code editor_grid_preview_*}, {@code editor_grid_save_canceled})
 *   (docs/chromite/swing-frontend.md "저장 전 스크립트 미리 보기")
 * - 2026-11-07 쿼리 상태 줄의 열린 트랜잭션 칸 문구({@code editor_status_txn_*}) (docs/chromite/idle-transactions.md)
 * - 2026-11-07 쿼리 창 실행 제한, 위험한 문 확인 문구({@code editor_sql_execution_restricted*}, {@code editor_sql_danger_*})
 *   (docs/chromite/swing-sql-exec-safety.md)
 * - 2026-11-07 Commit / Roll Back 도구 설명, 트랜잭션 로그·열린 트랜잭션 목록 문구({@code editor_txn_log_*}, {@code editor_txn_list_*})
 *   (docs/chromite/transactions.md), {@code @export} 안내 줄({@code editor_sql_export_command}, docs/chromite/swing-data-transfer.md)
 * - 2026-11-07 상태 줄 커밋 방식 칸 도구 설명의 세션 줄({@code editor_status_session_*}) (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-07 위험한 문 확인에 TRUNCATE({@code editor_sql_danger_truncate}) (docs/chromite/swing-sql-exec-safety.md)
 * - 2026-11-08 결과 차트·공간 보기 문구({@code editor_viewer_chart*}, {@code editor_viewer_spatial*}, {@code editor_chart_*}, {@code editor_spatial_*})
 *   (docs/chromite/swing-result-charts-spatial.md)
 * - 2026-11-08 {@code editor_sql_bound_parameters} 삭제: 값을 바인딩한 결과도 다시 읽음. {@code editor_param_*}는 합친 매개변수 대화상자
 *   ({@code QueryParameterDialog}의 스크립트 모드)가 씀 (docs/chromite/swing-query-library.md "매개변수 대화상자와 바인딩 하나로")
 * - 2026-11-08 편집 편의 문구({@code editor_edit_*} 추가, {@code editor_text_*}, {@code editor_nav_*}, {@code editor_outline_none},
 *   결과 고정 {@code editor_results_*}) (docs/chromite/swing-sql-editor-ergonomics.md)
 * - 2026-11-08 실제 계획 토글을 Oracle·Altibase로: {@code editor_sql_actual_plan_unsupported}(그 밖의 연결), {@code editor_sql_cant_restore_actual_plan}.
 *   다시 실행하는 실제 계획을 지우며 {@code editor_sql_actual_plan_select_only}, {@code editor_sql_making_actual_plan} 삭제
 * - 2026-11-08 열린 트랜잭션 칸의 "확인할 수 없음"과 쥔 것 문구({@code editor_status_txn_unknown_*}, {@code editor_status_txn_tip_holds*},
 *   {@code editor_status_txn_hold_*}) (docs/chromite/idle-transactions.md "트랜잭션 상태")
 * - 2026-11-08 결과 집합마다 탭: {@code editor_results_tab}, {@code editor_results_pinned_tip}, {@code editor_results_already_pinned},
 *   {@code editor_results_messages_errors*} 추가, {@code editor_results_pinned}가 결과 탭 이름과 고정 시각을 받음, 결과 쌓기의 자리 표시
 *   {@code editor_results_none}과 고정 탭 줄의 {@code editor_results_current} 삭제 (docs/chromite/swing-frontend.md "결과 탭")
 * - 2026-11-09 조회만 한 트랜잭션 끝내기({@code editor_status_txn_end_read_only}, {@code editor_status_txn_tip_click_read_only},
 *   {@code editor_status_txn_read_only_ended*}), 칸이 조회 트랜잭션과 변경 있는 트랜잭션을 구별({@code editor_status_txn_read_only_locks},
 *   {@code editor_status_txn_read_only_snapshot}, {@code editor_status_txn_open_changes}) (docs/chromite/idle-transactions.md "조회만 한 트랜잭션 끝내기")
 * - 2026-11-12 결과 영역 두 단계 탭: 위 탭 이름 {@code editor_results_grid}, {@code editor_results_text}, {@code editor_results_server_output},
 *   Text 탭의 결과 제목 {@code editor_results_text_title}, 새 서버 출력 도구 설명 {@code editor_results_server_output_new_tip} 추가,
 *   {@code editor_results_tab}은 언어와 관계없이 "Result N" (docs/chromite/swing-frontend.md "결과 영역 두 단계 탭")
 * - 2026-11-12 Execution plan 탭 점의 도구 설명 {@code editor_results_plan_new_tip}, {@code editor_results_plan_none_tip} 추가
 *   (앱 변경 #58, docs/chromite/swing-sql-editor-plus.md "실제 계획 토글 점검")
 */
public class EditorMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.EditorMessages"; //$NON-NLS-1$

    // Common
    public static String editor_common_cancel;
    public static String editor_common_close;
    public static String editor_common_column;
    public static String editor_common_description;
    public static String editor_common_execute;
    public static String editor_common_name;
    public static String editor_common_not_connected;
    public static String editor_common_reading;
    public static String editor_common_save;
    public static String editor_common_value;
    public static String editor_common_clear_filter;

    // SQL editor (SqlEditorPanel)
    public static String editor_sql_rows_one;
    public static String editor_sql_rows_many;
    public static String editor_sql_actual_plan;
    public static String editor_sql_actual_plan_tip;
    public static String editor_sql_execute_tip;
    public static String editor_sql_cancel_tip;
    public static String editor_sql_estimated_plan;
    public static String editor_sql_estimated_plan_tip;
    public static String editor_sql_tip_not_saved;
    public static String editor_sql_tip_not_connected;
    public static String editor_sql_tip_connection;
    public static String editor_sql_actual_plan_on;
    public static String editor_sql_actual_plan_off;
    public static String editor_sql_connection_tip;
    public static String editor_sql_schema_tip;
    public static String editor_sql_connection_selected_tip;
    public static String editor_sql_schema_selected_tip;
    public static String editor_sql_saving_changes;
    public static String editor_sql_nothing_to_execute;
    public static String editor_sql_choose_connection;
    public static String editor_sql_execution_restricted_title;
    public static String editor_sql_execution_restricted;
    public static String editor_sql_danger_title;
    public static String editor_sql_danger_message_one;
    public static String editor_sql_danger_message_many;
    public static String editor_sql_danger_no_where;
    public static String editor_sql_danger_drop;
    public static String editor_sql_danger_truncate;
    public static String editor_sql_discard_and_execute;
    public static String editor_sql_discard_confirm_one;
    public static String editor_sql_discard_confirm_many;
    public static String editor_sql_waiting_actual_plan;
    public static String editor_sql_connecting;
    public static String editor_sql_canceling;
    public static String editor_sql_executing_statement;
    public static String editor_sql_executing_elapsed;
    public static String editor_sql_state_elapsed;
    public static String editor_sql_canceled_by_user;
    public static String editor_sql_completion_time;
    public static String editor_sql_completed_with_errors;
    public static String editor_sql_query_canceled;
    public static String editor_sql_executed_successfully;
    public static String editor_sql_plan_did_not_run;
    public static String editor_sql_plan_canceled;
    public static String editor_sql_plan_none_returned;
    public static String editor_sql_no_plans;
    public static String editor_sql_actual_plan_unsupported;
    public static String editor_sql_nothing_to_explain;
    public static String editor_sql_making_estimated_plan;
    public static String editor_sql_not_connected_to;
    public static String editor_sql_not_connected_to_short;
    public static String editor_sql_no_statements;
    public static String editor_sql_no_context;
    public static String editor_sql_cant_include_actual_plan;
    public static String editor_sql_cant_restore_actual_plan;
    public static String editor_sql_rows_affected_one;
    public static String editor_sql_rows_affected_many;
    public static String editor_sql_rows_affected_max;
    public static String editor_sql_commands_completed;
    public static String editor_sql_execution_time;
    public static String editor_sql_error_msg;
    public static String editor_sql_error_state;
    public static String editor_sql_error_generic;
    public static String editor_sql_error_line;
    public static String editor_sql_error_column;
    public static String editor_sql_change_schema_title;
    public static String editor_sql_change_schema_error;
    public static String editor_sql_select_connection;

    // Edit > Advanced (SqlEditCommands)
    public static String editor_edit_advanced;
    public static String editor_edit_format_sql;
    public static String editor_edit_upper_case;
    public static String editor_edit_lower_case;
    public static String editor_edit_comment;
    public static String editor_edit_uncomment;
    public static String editor_edit_indent;
    public static String editor_edit_outdent;
    public static String editor_edit_insert_template;
    public static String editor_edit_toggle_block_comment;
    public static String editor_edit_trim;
    public static String editor_edit_trim_spaces;
    public static String editor_edit_trim_leading;
    public static String editor_edit_trim_trailing;
    public static String editor_edit_morph_list;
    public static String editor_edit_copy_as_source;
    public static String editor_edit_search_web;
    public static String editor_edit_word_wrap;
    public static String editor_edit_folding;
    public static String editor_edit_fold_collapse;
    public static String editor_edit_fold_expand;
    public static String editor_edit_fold_collapse_all;
    public static String editor_edit_fold_expand_all;
    public static String editor_edit_fold_collapse_comments;

    // Text tools (SqlTextTools)
    public static String editor_text_unformatted_sql;
    public static String editor_text_morph_title;
    public static String editor_text_morph_no_selection;
    public static String editor_text_morph_source_delimiter;
    public static String editor_text_morph_target_delimiter;
    public static String editor_text_morph_quote;
    public static String editor_text_morph_wrap;
    public static String editor_text_morph_leading;
    public static String editor_text_morph_trailing;
    public static String editor_text_morph_hint;
    public static String editor_text_morph_apply;

    // Hover, Ctrl+click (SqlObjectNavigation) and Outline (SqlOutlinePanel)
    public static String editor_nav_not_found;
    public static String editor_nav_more;
    public static String editor_outline_none;

    // Options > Templates (SqlTemplatesPage)
    public static String editor_templates_enabled;
    public static String editor_templates_remove;
    public static String editor_templates_new;
    public static String editor_templates_restore;
    public static String editor_templates_restore_tip;
    public static String editor_templates_hint;
    public static String editor_templates_default_scount;
    public static String editor_templates_default_swhere;
    public static String editor_templates_default_scgb;
    public static String editor_templates_default_sob;
    public static String editor_templates_default_sel;
    public static String editor_templates_default_ins;
    public static String editor_templates_default_upd;
    public static String editor_templates_default_del;

    // Bind Parameters (QueryParameterDialog script mode)
    public static String editor_param_title;
    public static String editor_param_hint;
    public static String editor_param_ignore;
    public static String editor_param_ignore_tip;

    // Execution plan (ExecutionPlanView, SqlPlanExplainer)
    public static String editor_plan_empty;
    public static String editor_plan_header_actual_one;
    public static String editor_plan_header_actual_many;
    public static String editor_plan_header_estimated_one;
    public static String editor_plan_header_estimated_many;
    public static String editor_plan_header_failed;
    public static String editor_plan_query;
    public static String editor_plan_operation;
    public static String editor_plan_statement;
    public static String editor_plan_plan_query;
    public static String editor_plan_plan;
    public static String editor_plan_actual;
    public static String editor_plan_estimated;
    public static String editor_plan_error;
    public static String editor_plan_property;
    public static String editor_plan_canceled;

    // Query History (QueryHistoryDialog)
    public static String editor_history_title;
    public static String editor_history_all_connections;
    public static String editor_history_period_today;
    public static String editor_history_period_week;
    public static String editor_history_period_month;
    public static String editor_history_period_all;
    public static String editor_history_open;
    public static String editor_history_open_tip;
    public static String editor_history_execute_tip;
    public static String editor_history_search;
    public static String editor_history_clear;
    public static String editor_history_clear_title;
    public static String editor_history_clear_confirm;
    public static String editor_history_count_one;
    public static String editor_history_count_many;
    public static String editor_history_column_time;
    public static String editor_history_column_connection;
    public static String editor_history_column_database;
    public static String editor_history_column_duration;
    public static String editor_history_column_rows;
    public static String editor_history_column_status;
    public static String editor_history_column_statement;
    public static String editor_history_status_success;
    public static String editor_history_status_error;
    public static String editor_history_status_canceled;

    // Query status line (QueryStatusLine)
    public static String editor_status_ready;
    public static String editor_status_user;
    public static String editor_status_database;
    public static String editor_status_elapsed;
    public static String editor_status_rows;
    public static String editor_status_caret;
    public static String editor_status_caret_tip;
    public static String editor_status_auto_commit;
    public static String editor_status_auto_commit_tip;
    public static String editor_status_manual_commit;
    public static String editor_status_manual_commit_tip;
    public static String editor_status_commit_setting_tip;
    public static String editor_status_session_own;
    public static String editor_status_session_shared;
    public static String editor_status_commit_default_differs;
    public static String editor_status_txn_open_one;
    public static String editor_status_txn_open_many;
    public static String editor_status_txn_unknown_one;
    public static String editor_status_txn_unknown_many;
    public static String editor_status_txn_tip_unknown;
    public static String editor_status_txn_tip_holds;
    public static String editor_status_txn_tip_holds_statements;
    public static String editor_status_txn_hold_changes;
    public static String editor_status_txn_hold_locks;
    public static String editor_status_txn_hold_snapshot;
    public static String editor_status_txn_hold_explicit;
    public static String editor_status_txn_hold_aborted;
    public static String editor_status_txn_tip_started;
    public static String editor_status_txn_tip_statements;
    public static String editor_status_txn_tip_last;
    public static String editor_status_txn_tip_click;
    public static String editor_status_txn_commit;
    public static String editor_status_txn_rollback;
    public static String editor_status_txn_log;
    public static String editor_status_txn_end_read_only;
    public static String editor_status_txn_tip_click_read_only;
    public static String editor_status_txn_read_only_ended;
    public static String editor_status_txn_read_only_ended_tip;
    public static String editor_status_txn_read_only_locks;
    public static String editor_status_txn_read_only_snapshot;
    public static String editor_status_txn_open_changes;
    public static String editor_sql_commit_tip;
    public static String editor_sql_rollback_tip;
    public static String editor_txn_log_title;
    public static String editor_txn_log_not_connected;
    public static String editor_txn_log_session;
    public static String editor_txn_log_open;
    public static String editor_txn_log_none;
    public static String editor_txn_log_auto_commit;
    public static String editor_txn_log_col_time;
    public static String editor_txn_log_col_type;
    public static String editor_txn_log_col_sql;
    public static String editor_txn_log_col_duration;
    public static String editor_txn_log_col_rows;
    public static String editor_txn_log_col_result;
    public static String editor_txn_log_rows_updated;
    public static String editor_txn_log_rows_fetched;
    public static String editor_txn_log_running;
    public static String editor_txn_log_ok;
    public static String editor_txn_list_title;
    public static String editor_txn_list_description;
    public static String editor_txn_list_empty;
    public static String editor_txn_list_col_connection;
    public static String editor_txn_list_col_database;
    public static String editor_txn_list_col_session;
    public static String editor_txn_list_col_started;
    public static String editor_txn_list_col_statements;
    public static String editor_txn_list_col_idle;
    public static String editor_txn_list_statements;
    public static String editor_txn_list_running;
    public static String editor_sql_export_command;

    // Results (QueryResultsView)
    public static String editor_results_grid;
    public static String editor_results_text;
    public static String editor_results_text_title;
    public static String editor_results_server_output;
    public static String editor_results_server_output_new_tip;
    public static String editor_results_plan_new_tip;
    public static String editor_results_plan_none_tip;
    public static String editor_results_tab;
    public static String editor_results_pinned;
    public static String editor_results_pinned_tip;
    public static String editor_results_unpin_tip;
    public static String editor_results_unpin_discard;
    public static String editor_results_unpin_confirm_one;
    public static String editor_results_unpin_confirm_many;
    public static String editor_results_nothing_to_pin;
    public static String editor_results_already_pinned;
    public static String editor_results_messages_errors;
    public static String editor_results_messages_errors_tip;

    // Result grid (ResultSetGrid, ResultSetGridReceiver, ResultSetEditModel)
    public static String editor_grid_cell_read_only;
    public static String editor_grid_invalid_editor;
    public static String editor_grid_invalid_value;
    public static String editor_grid_invalid_value_status;
    public static String editor_grid_save_tip;
    public static String editor_grid_cancel_tip;
    public static String editor_grid_read_only;
    public static String editor_grid_saving;
    public static String editor_grid_editable_none;
    public static String editor_grid_editable_one;
    public static String editor_grid_editable_many;
    public static String editor_grid_insert_row;
    public static String editor_grid_cant_add_row;
    public static String editor_grid_results;
    public static String editor_grid_save_job;
    public static String editor_grid_saved;
    public static String editor_grid_zero_rows;
    public static String editor_grid_manual_commit;
    public static String editor_grid_save_time;
    public static String editor_grid_rollback_save;
    public static String editor_grid_rollback_open;
    public static String editor_grid_partly_applied;
    public static String editor_grid_nothing_saved;
    public static String editor_grid_cant_save;
    public static String editor_grid_save_failed;
    public static String editor_grid_save_canceled;
    public static String editor_grid_preview_job;
    public static String editor_grid_preview_title;
    public static String editor_grid_preview_header;
    public static String editor_grid_preview_comment;
    public static String editor_grid_copy;
    public static String editor_grid_copy_with_headers;
    public static String editor_grid_export_csv;
    public static String editor_grid_export_xlsx;
    public static String editor_grid_export_data;
    public static String editor_grid_export_data_tip;
    public static String editor_grid_export_data_blocked;
    public static String editor_grid_export_data_single;
    public static String editor_grid_edit_cell;
    public static String editor_grid_set_null;
    public static String editor_grid_delete_row;
    public static String editor_grid_save_changes;
    public static String editor_grid_cancel_changes;
    public static String editor_grid_columns_changed;
    public static String editor_grid_cell_error;
    public static String editor_grid_fetch_incomplete;
    public static String editor_grid_reason_no_connection;
    public static String editor_grid_reason_metadata;
    public static String editor_grid_reason_several_tables;
    public static String editor_grid_reason_no_table_columns;
    public static String editor_grid_reason_no_unique_key;
    public static String editor_grid_reason_key_unreadable;
    public static String editor_grid_reason_no_data_changes;
    public static String editor_grid_reason_no_editable_columns;
    public static String editor_grid_update_in_progress;

    // Result source (ResultSetSource)
    public static String editor_source_no_filters;
    public static String editor_source_not_single_select;
    public static String editor_source_whole_batch;

    // Result viewer (ResultSetViewerPanel)
    public static String editor_viewer_order_note;
    public static String editor_viewer_value_panel;
    public static String editor_viewer_value_panel_tip;
    public static String editor_viewer_record;
    public static String editor_viewer_record_tip;
    public static String editor_viewer_chart;
    public static String editor_viewer_chart_tip;
    public static String editor_viewer_spatial;
    public static String editor_viewer_spatial_tip;
    public static String editor_viewer_fetch_next;
    public static String editor_viewer_fetch_next_count;
    public static String editor_viewer_fetch_all;
    public static String editor_viewer_fetch_all_tip;
    public static String editor_viewer_sort_ascending;
    public static String editor_viewer_sort_descending;
    public static String editor_viewer_clear_sorting;
    public static String editor_viewer_fit_column;
    public static String editor_viewer_fit_all_columns;
    public static String editor_viewer_filter_by_value;
    public static String editor_viewer_filters_need;
    public static String editor_viewer_remove_filter_of;
    public static String editor_viewer_no_grid;
    public static String editor_viewer_no_cell;
    public static String editor_viewer_rows_one;
    public static String editor_viewer_rows_many;
    public static String editor_viewer_rows_more_one;
    public static String editor_viewer_rows_more_many;
    public static String editor_viewer_rows_filtered;
    public static String editor_viewer_more_rows_blocked;
    public static String editor_viewer_action_sort;
    public static String editor_viewer_action_filter;
    public static String editor_viewer_wait_saved;
    public static String editor_viewer_unsaved_title;
    public static String editor_viewer_unsaved_one;
    public static String editor_viewer_unsaved_many;
    public static String editor_viewer_save_to_fetch;
    public static String editor_viewer_fetch_all_title;
    public static String editor_viewer_fetch_all_confirm;
    public static String editor_viewer_fetch_more_title;
    public static String editor_viewer_fetch_more_pending;
    public static String editor_viewer_read_failed;
    public static String editor_viewer_cant_read_again;

    // Value panel (ResultSetValuePanel, ResultSetCellContent)
    public static String editor_value_mode_auto;
    public static String editor_value_mode_text;
    public static String editor_value_mode_image;
    public static String editor_value_mode_hex;
    public static String editor_value_wrap;
    public static String editor_value_mode_tip;
    public static String editor_value_apply;
    public static String editor_value_apply_tip;
    public static String editor_value_revert;
    public static String editor_value_revert_tip;
    public static String editor_value_select_cell;
    public static String editor_value_cant_read;
    public static String editor_value_bytes;
    public static String editor_value_chars;
    public static String editor_value_showing_first;
    public static String editor_value_showing_first_bytes;
    public static String editor_value_not_image;
    public static String editor_value_invalid_json;
    public static String editor_value_invalid_xml;
    public static String editor_value_text_after_json;
    public static String editor_value_not_applied;
    public static String editor_value_released;
    public static String editor_value_cant_read_content;

    // Record view (ResultSetRecordView)
    public static String editor_record_previous;
    public static String editor_record_next;
    public static String editor_record_previous_tip;
    public static String editor_record_next_tip;
    public static String editor_record_no_rows;
    public static String editor_record_position;
    public static String editor_record_type;

    // Chart view (ResultSetChartView, ResultSetChart)
    public static String editor_chart_line;
    public static String editor_chart_bar;
    public static String editor_chart_pie;
    public static String editor_chart_x_axis;
    public static String editor_chart_row_number;
    public static String editor_chart_values;
    public static String editor_chart_values_none;
    public static String editor_chart_values_tip;
    public static String editor_chart_values_limit;
    public static String editor_chart_rows;
    public static String editor_chart_rows_all;
    public static String editor_chart_rows_selected;
    public static String editor_chart_save_png;
    public static String editor_chart_save_png_title;
    public static String editor_chart_png_filter;
    public static String editor_chart_no_numeric;
    public static String editor_chart_choose_values;
    public static String editor_chart_no_rows;
    public static String editor_chart_no_values;
    public static String editor_chart_pie_no_positive;
    public static String editor_chart_empty_value;
    public static String editor_chart_other;
    public static String editor_chart_note_rows_one;
    public static String editor_chart_note_rows_many;
    public static String editor_chart_note_more_rows;
    public static String editor_chart_note_pie_first;
    public static String editor_chart_note_pie_left_out;

    // Spatial view (ResultSetSpatialView)
    public static String editor_spatial_column;
    public static String editor_spatial_fit;
    public static String editor_spatial_fit_tip;
    public static String editor_spatial_no_columns;
    public static String editor_spatial_no_values;
    public static String editor_spatial_note_one;
    public static String editor_spatial_note_many;
    public static String editor_spatial_note_srid;
    public static String editor_spatial_note_mixed_srid;
    public static String editor_spatial_note_empty;
    public static String editor_spatial_note_unreadable;
    public static String editor_spatial_note_no_basemap;
    public static String editor_spatial_tip;

    // Aggregates (ResultSetAggregates)
    public static String editor_aggregate_count;
    public static String editor_aggregate_numbers;
    public static String editor_aggregate_capped;

    // Copy As (ResultSetCopyFormats)
    public static String editor_copy_as;
    public static String editor_copy_tsv;
    public static String editor_copy_markdown;
    public static String editor_copy_sql_in;

    // Export to CSV/Excel (ResultSetExport)
    public static String editor_export_delimiter_comma;
    public static String editor_export_delimiter_semicolon;
    public static String editor_export_delimiter_tab;
    public static String editor_export_include_headers;
    public static String editor_export_bom;
    public static String editor_export_escape_formulas;
    public static String editor_export_csv_options;
    public static String editor_export_delimiter;
    public static String editor_export_csv_title;
    public static String editor_export_csv_filter;
    public static String editor_export_cant_export;
    public static String editor_export_note_one;
    public static String editor_export_note_many;
    public static String editor_export_note_truncated;
    public static String editor_export_replace_confirm;
    public static String editor_export_excel_options;
    public static String editor_export_xlsx_title;
    public static String editor_export_xlsx_filter;
    public static String editor_export_task;
    public static String editor_export_error_writing;

    static {
        NLS.initializeMessages(BUNDLE_NAME, EditorMessages.class);
    }

    private EditorMessages() {
    }
}
