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
 * 자동 업데이트(제목 표시줄 단추, 상태 표시줄 진행, 옵션 Updates 탭)와 끝낼 때의 트랜잭션 질문 화면 문구.
 *
 * 설계: 다른 기능과 같은 파일을 고치지 않도록(병합 충돌) 이 기능만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code UpdateMessages.properties}), 한국어는 {@code UpdateMessages_ko.properties}이고, 플랫폼이 OS 언어로 고른다.
 * 키 접두사는 {@code update_}(업데이트)와 {@code exit_txn_}(끝낼 때 트랜잭션)다. {0} 자리표시자가 있는 값은
 * {@link NLS#bind}로만 쓰므로 작은따옴표를 두 번 쓴다('').
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 * - 2026-11-07 {@code exit_txn_agent_item}: 끝낼 때 질문의 AI 도우미·MCP 도구 트랜잭션 줄
 * - 2026-11-07 {@code exit_txn_agent_item}: 기능 이름을 Chromite Agent로 바꿈(docs/chromite/ai-agent.md "명칭")
 * - 2026-11-08 {@code update_window_*}: 업데이트 진행 창(Turbrix #747의 문구, docs/chromite/auto-update.md "업데이트 진행 창").
 *   이 클래스는 앱의 사본(OSGi 없이 도는 진행 창)도 읽으므로 {@link NLS} 말고 다른 것을 참조하지 않는다.
 */
public class UpdateMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.UpdateMessages"; //$NON-NLS-1$

    public static String update_button_ready;
    public static String update_button_ready_tip;
    public static String update_restart_now;
    public static String update_whats_new;
    public static String update_button_available;
    public static String update_button_available_tip;
    public static String update_open_download_page;
    public static String update_button_failed;
    public static String update_failed_message;
    public static String update_try_again;
    public static String update_dismiss;
    public static String update_downloading;
    public static String update_step_size;
    public static String update_step_sha256;
    public static String update_step_checker;
    public static String update_step_signature;
    public static String update_step_publisher;
    public static String update_step_timestamp;
    public static String update_step_product;
    public static String update_step_version;
    public static String update_step_extract;
    public static String update_step_notarization;
    public static String update_applying_message;
    public static String update_install_failed_title;
    public static String update_install_failed_message;

    public static String update_window_name;
    public static String update_window_versions;
    public static String update_window_step_download;
    public static String update_window_step_check;
    public static String update_window_step_install;
    public static String update_window_step_start;
    public static String update_window_step_open;
    public static String update_window_mark_done;
    public static String update_window_mark_current;
    public static String update_window_mark_waiting;
    public static String update_window_mark_failed;
    public static String update_window_head_downloading;
    public static String update_window_head_ready;
    public static String update_window_head_installing;
    public static String update_window_head_starting;
    public static String update_window_head_done;
    public static String update_window_head_failed_install;
    public static String update_window_head_failed_start;
    public static String update_window_sub_downloading;
    public static String update_window_sub_ready;
    public static String update_window_sub_installing;
    public static String update_window_sub_starting;
    public static String update_window_sub_done;
    public static String update_window_sub_failed_start;
    public static String update_window_install_failed;
    public static String update_window_version_kept;
    public static String update_window_try_or_download;
    public static String update_window_note_running;
    public static String update_window_note_downloading;
    public static String update_window_received;
    public static String update_window_megabytes;
    public static String update_window_elapsed;
    public static String update_window_close;
    public static String update_window_later;
    public static String update_window_back;
    public static String update_window_log;

    public static String update_options_tab;
    public static String update_options_auto;
    public static String update_options_version;
    public static String update_options_check_now;
    public static String update_options_checking;
    public static String update_options_up_to_date;
    public static String update_options_unreachable;
    public static String update_options_downloading;
    public static String update_options_ready;
    public static String update_options_available;
    public static String update_options_failed;
    public static String update_options_off;
    public static String update_options_notify;
    public static String update_options_privacy;

    public static String exit_txn_title;
    public static String exit_txn_message;
    public static String exit_txn_commit;
    public static String exit_txn_rollback;
    public static String exit_txn_progress;
    public static String exit_txn_error;
    public static String exit_txn_agent_item;

    static {
        NLS.initializeMessages(BUNDLE_NAME, UpdateMessages.class);
    }

    private UpdateMessages() {
    }
}
