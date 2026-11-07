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
 * Screen text of Chromite Agent: the tool window, the tool confirmation dialog, the Chromite Agent options and the Query
 * Library thresholds editor.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 AI 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본({@code AiMessages.properties}),
 * 한국어는 {@code AiMessages_ko.properties}이고 OS 언어를 따른다. 키 접두사는 {@code ai_}다. 제공자 이름(Anthropic Claude, OpenAI, Ollama)은
 * 고르는 대상의 이름이라 옮기지 않고 모델 번들({@code AIAgentProviders#getLabel})이 낸다. 도구 이름과 설명은 모델에게 주는 글이라 영어로 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/ai-agent.md "2단계")
 * - 2026-11-07 {@code ai_tool_transaction_*}, {@code ai_tool_state_after_transaction}: 도구 카드의 트랜잭션 질문 결과
 * - 2026-11-07 {@code ai_options_save_failed_secure_storage}: OS 보안 저장소에 키를 저장하지 못했을 때의 안내
 * - 2026-11-07 기능의 공식 이름을 Chromite Agent로 정함(사용자 결정). "AI 도우미"·"AI Assistant"·"도우미"로 기능을 가리키던 문구를
 *   영어·한국어 모두 "Chromite Agent"로 바꿈. 모델 제공자를 뜻하는 "AI 제공자"는 그대로 둠(docs/chromite/ai-agent.md "명칭")
 * - 2026-11-08 {@code ai_policy_send_all_tools}(+ {@code _tip}): 요청마다 도구를 모두 보내는 설정(docs/chromite/ai-agent.md "요청 크기")
 */
public class AiMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.AiMessages"; //$NON-NLS-1$

    public static String ai_title;
    public static String ai_toolbar_tip;
    public static String ai_new_chat;
    public static String ai_delete_chat;
    public static String ai_delete_chat_question;
    public static String ai_chat_untitled;
    public static String ai_chats_tip;
    public static String ai_playbooks;
    public static String ai_settings;
    public static String ai_provider_tip;
    public static String ai_model_tip;
    public static String ai_connection_tip;
    public static String ai_database_tip;
    public static String ai_no_connection;
    public static String ai_input_placeholder;
    public static String ai_input_tip;
    public static String ai_send;
    public static String ai_stop;
    public static String ai_setup_notice;
    public static String ai_busy;
    public static String ai_status_thinking;
    public static String ai_status_tool;
    public static String ai_status_stopping;
    public static String ai_status_stopped;
    public static String ai_empty_hint;
    public static String ai_playbook_tune;
    public static String ai_playbook_health;
    public static String ai_playbook_why_slow;
    public static String ai_playbook_system_check;
    public static String ai_playbook_blocking;
    public static String ai_playbook_routine;
    public static String ai_playbook_no_connection;
    public static String ai_playbook_no_sql;
    public static String ai_playbook_folder;
    public static String ai_playbook_object;
    public static String ai_ask_about_query;
    public static String ai_ask_prompt;
    public static String ai_code_block;
    public static String ai_code_copy;
    public static String ai_code_open;
    public static String ai_tool_running;
    public static String ai_tool_state;
    public static String ai_tool_arguments;
    public static String ai_tool_result;
    public static String ai_tool_result_cut;
    public static String ai_duration_ms;
    public static String ai_duration_s;
    public static String ai_summary_items;
    public static String ai_summary_truncated;
    public static String ai_summary_transaction_open;
    public static String ai_summary_update_count;
    public static String ai_tool_transaction_committed;
    public static String ai_tool_transaction_rolled_back;
    public static String ai_tool_state_after_transaction;
    public static String ai_outcome_success;
    public static String ai_outcome_error;
    public static String ai_outcome_denied;
    public static String ai_outcome_blocked;
    public static String ai_outcome_read_only;
    public static String ai_outcome_canceled;
    public static String ai_outcome_invalid;
    public static String ai_access_read;
    public static String ai_access_write;
    public static String ai_access_dangerous;
    public static String ai_badge_production;
    public static String ai_confirm_title;
    public static String ai_confirm_tx_title;
    public static String ai_confirm_headline;
    public static String ai_confirm_headline_mcp;
    public static String ai_confirm_headline_dangerous;
    public static String ai_confirm_headline_dangerous_mcp;
    public static String ai_confirm_tx_headline;
    public static String ai_confirm_tx_question;
    public static String ai_confirm_tx_no_commit;
    public static String ai_confirm_tool;
    public static String ai_confirm_connection;
    public static String ai_confirm_requested_by;
    public static String ai_confirm_client_chat;
    public static String ai_confirm_client_mcp;
    public static String ai_confirm_reason;
    public static String ai_confirm_preview;
    public static String ai_confirm_arguments;
    public static String ai_confirm_argument_name;
    public static String ai_confirm_argument_value;
    public static String ai_confirm_always_allow;
    public static String ai_confirm_always_allow_tip;
    public static String ai_confirm_dangerous_note;
    public static String ai_confirm_allow;
    public static String ai_confirm_deny;
    public static String ai_confirm_commit;
    public static String ai_confirm_rollback;
    public static String ai_confirm_cancel;
    public static String ai_confirm_rule_failed;
    public static String ai_reason_connection_rule;
    public static String ai_reason_global_rule;
    public static String ai_reason_default;
    public static String ai_reason_transaction;
    public static String ai_options_tab;
    public static String ai_options_title;
    public static String ai_options_providers;
    public static String ai_options_policy;
    public static String ai_options_mcp;
    public static String ai_options_audit;
    public static String ai_options_providers_note;
    public static String ai_options_api_key;
    public static String ai_options_key_saved;
    public static String ai_options_key_none;
    public static String ai_options_remove_key;
    public static String ai_options_base_url;
    public static String ai_options_base_url_openai;
    public static String ai_options_host;
    public static String ai_options_model;
    public static String ai_options_test;
    public static String ai_options_testing;
    public static String ai_options_test_ok;
    public static String ai_options_test_failed;
    public static String ai_options_save_failed;
    public static String ai_options_save_failed_secure_storage;
    public static String ai_policy_rule_production;
    public static String ai_policy_rule_read_only;
    public static String ai_policy_rule_dangerous;
    public static String ai_policy_scope;
    public static String ai_policy_all_connections;
    public static String ai_policy_connection_production;
    public static String ai_policy_class_rule;
    public static String ai_policy_scope_production;
    public static String ai_policy_scope_read_only;
    public static String ai_policy_filter;
    public static String ai_policy_send_all_tools;
    public static String ai_policy_send_all_tools_tip;
    public static String ai_policy_inherit;
    public static String ai_policy_auto;
    public static String ai_policy_confirm;
    public static String ai_policy_blocked;
    public static String ai_policy_effective_read_only;
    public static String ai_policy_column_tool;
    public static String ai_policy_column_group;
    public static String ai_policy_column_class;
    public static String ai_policy_column_rule;
    public static String ai_policy_column_effective;
    public static String ai_policy_save_failed;
    public static String ai_group_connections;
    public static String ai_group_metadata;
    public static String ai_group_query;
    public static String ai_group_data;
    public static String ai_group_ddl;
    public static String ai_group_transfer;
    public static String ai_group_health;
    public static String ai_group_performance;
    public static String ai_group_library;
    public static String ai_group_editor;
    public static String ai_mcp_enable;
    public static String ai_mcp_note;
    public static String ai_mcp_port;
    public static String ai_mcp_status;
    public static String ai_mcp_running;
    public static String ai_mcp_stopped;
    public static String ai_mcp_failed;
    public static String ai_mcp_url;
    public static String ai_mcp_token;
    public static String ai_mcp_show;
    public static String ai_mcp_copy;
    public static String ai_mcp_regenerate;
    public static String ai_mcp_regenerate_question;
    public static String ai_mcp_command;
    public static String ai_mcp_command_note;
    public static String ai_mcp_copy_command;
    public static String ai_audit_filter;
    public static String ai_audit_client;
    public static String ai_audit_outcome;
    public static String ai_audit_all;
    public static String ai_audit_client_chat;
    public static String ai_audit_client_mcp;
    public static String ai_audit_refresh;
    public static String ai_audit_loading;
    public static String ai_audit_count;
    public static String ai_audit_file;
    public static String ai_audit_column_time;
    public static String ai_audit_column_client;
    public static String ai_audit_column_tool;
    public static String ai_audit_column_class;
    public static String ai_audit_column_connection;
    public static String ai_audit_column_policy;
    public static String ai_audit_column_outcome;
    public static String ai_audit_column_ms;
    public static String ai_thresholds_button;
    public static String ai_thresholds_button_count;
    public static String ai_thresholds_tip;
    public static String ai_thresholds_title;
    public static String ai_thresholds_help;
    public static String ai_thresholds_column;
    public static String ai_thresholds_warn;
    public static String ai_thresholds_critical;
    public static String ai_thresholds_scope;
    public static String ai_thresholds_label;
    public static String ai_thresholds_add;
    public static String ai_thresholds_remove;
    public static String ai_thresholds_ok;
    public static String ai_thresholds_cancel;
    public static String ai_thresholds_bad_column;
    public static String ai_thresholds_no_condition;
    public static String ai_thresholds_bad_condition;

    static {
        NLS.initializeMessages(BUNDLE_NAME, AiMessages.class);
    }

    private AiMessages() {
    }
}
