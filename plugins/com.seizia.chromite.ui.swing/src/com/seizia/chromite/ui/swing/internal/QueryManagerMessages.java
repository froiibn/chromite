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
 * Query Manager 도구 창(쿼리 관리자가 기록한 모든 실행)의 화면 문구.
 *
 * 설계: 다른 기능과 병합 충돌을 피하려고 이 기능만의 묶음으로 둔다(docs/chromite/swing-l10n.md의 영역 나눔과 같은 방식).
 * 영어가 기본, 한국어는 {@code QueryManagerMessages_ko.properties}다. {0} 자리표시자가 있는 값은 {@link NLS#bind}로만 쓰므로
 * 작은따옴표를 두 번 쓴다(''). 표의 시각·종류·SQL·걸린 시간·행·결과 열과 행 수·결과 문구, 쿼리 종류 이름은 트랜잭션 로그와
 * Options &gt; Logs의 문구를 그대로 쓴다(같은 것을 같은 말로 부르려고).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-query-manager.md)
 * - 2026-11-12 보일 항목 메뉴의 Log Settings (B12)
 */
public class QueryManagerMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.QueryManagerMessages"; //$NON-NLS-1$

    public static String qm_title;
    public static String qm_search;
    public static String qm_all_connections;
    public static String qm_show;
    public static String qm_show_tip;
    public static String qm_log_settings;
    public static String qm_column_connection;
    public static String qm_column_session;
    public static String qm_type_transaction;
    public static String qm_type_savepoint;
    public static String qm_type_connection;
    public static String qm_commit;
    public static String qm_rollback;
    public static String qm_rollback_savepoint;
    public static String qm_connected;
    public static String qm_disconnected;
    public static String qm_auto_commit;
    public static String qm_open;
    public static String qm_open_tip;
    public static String qm_copy;
    public static String qm_copy_tip;
    public static String qm_show_transaction;
    public static String qm_show_transaction_tip;
    public static String qm_clear;
    public static String qm_clear_tip;
    public static String qm_transaction_filter;
    public static String qm_show_all;
    public static String qm_count;

    static {
        NLS.initializeMessages(BUNDLE_NAME, QueryManagerMessages.class);
    }

    private QueryManagerMessages() {
    }
}
