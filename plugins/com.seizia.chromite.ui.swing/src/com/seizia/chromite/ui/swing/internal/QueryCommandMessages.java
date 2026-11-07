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
 * Texts of the query window's statement commands (Query menu: current statement, from the cursor, row count, expression,
 * statement navigation, variables) and of {@code @include}.
 *
 * 설계: 같은 때 쿼리 창을 고치는 다른 작업과 같은 파일({@code EditorMessages})을 고치지 않도록 이 클래스에 따로 둔다
 * (영어 {@code QueryCommandMessages.properties}, 한국어 {@code QueryCommandMessages_ko.properties}).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-query-commands.md)
 */
public class QueryCommandMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ui.swing.internal.QueryCommandMessages"; //$NON-NLS-1$

    public static String query_execute_statement;
    public static String query_execute_statement_tip;
    public static String query_execute_from_cursor;
    public static String query_execute_from_cursor_tip;
    public static String query_row_count;
    public static String query_row_count_tip;
    public static String query_evaluate_expression;
    public static String query_evaluate_expression_tip;
    public static String query_previous_statement;
    public static String query_next_statement;
    public static String query_copy_statement;
    public static String query_copy_statement_done;
    public static String query_no_statement;
    public static String query_not_a_query;
    public static String query_variables;
    public static String query_variables_tip;
    public static String query_variables_title;
    public static String query_variables_hint;
    public static String query_variables_name;
    public static String query_variables_value;
    public static String query_variables_remove;
    public static String query_variables_remove_all;
    public static String query_include_location;
    public static String query_include_error_file;

    static {
        NLS.initializeMessages(BUNDLE_NAME, QueryCommandMessages.class);
    }

    private QueryCommandMessages() {
    }
}
