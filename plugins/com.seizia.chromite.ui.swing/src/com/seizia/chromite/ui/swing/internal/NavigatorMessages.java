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
 * Object Explorer 트리 노드 글자 중 탐색기 모델이 주지 않는 것과 개체 작업({@code ExplorerObjectCommands})의 화면 문구.
 *
 * 설계: 기능별 묶음 규칙(docs/chromite/swing-l10n.md)에 따라 탐색기 후속 수정만의 {@link NLS} 묶음을 둔다. 영어가 기본
 * ({@code NavigatorMessages.properties}), 한국어는 {@code NavigatorMessages_ko.properties}이고 OS 언어를 따른다.
 * 키 접두사는 {@code navigator_}다. 그 밖의 Object Explorer 문구는 {@link CoreMessages}에 있다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 이름 없는 반환 값 매개 변수의 이름 (docs/chromite/swing-frontend.md "Object Explorer 탐색 불가 항목")
 * - 2026-11-07 개체 작업(삭제, 이름 바꾸기, 이름 복사, SQL 생성)의 문구 (docs/chromite/swing-explorer-object-actions.md)
 * - 2026-11-08 문장이 없는 변경의 알림 (docs/chromite/swing-object-properties.md)
 */
public class NavigatorMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.NavigatorMessages"; //$NON-NLS-1$

    public static String navigator_parameter_returns;

    // Object actions (ExplorerObjectCommands)
    public static String navigator_copy_name;
    public static String navigator_copy_name_tip;
    public static String navigator_copy_qualified_name;
    public static String navigator_copy_qualified_name_tip;
    public static String navigator_generate_sql;
    public static String navigator_generate_sql_tip;
    public static String navigator_generate_sql_error;
    public static String navigator_rename_title;
    public static String navigator_rename_prompt;
    public static String navigator_rename_error;
    public static String navigator_delete_title;
    public static String navigator_delete_question_one;
    public static String navigator_delete_question_many;
    public static String navigator_delete_column_name;
    public static String navigator_delete_column_type;
    public static String navigator_delete_cascade;
    public static String navigator_delete_cascade_tip;
    public static String navigator_delete_close_connections;
    public static String navigator_delete_close_connections_tip;
    public static String navigator_delete_button;
    public static String navigator_delete_error;
    public static String navigator_delete_different_connections;
    public static String navigator_change_progress;
    public static String navigator_change_execute_progress;
    public static String navigator_change_restricted;
    public static String navigator_change_preview_header;
    public static String navigator_change_preview_header_ddl_commit;
    public static String navigator_change_txn_message;
    public static String navigator_change_txn_error;
    public static String navigator_change_no_script;

    static {
        NLS.initializeMessages(BUNDLE_NAME, NavigatorMessages.class);
    }

    private NavigatorMessages() {
    }
}
