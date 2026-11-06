/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.ui.editors.sql;

/**
 * SQL Editor commands (some).
 */
public interface SQLEditorCommands
{
    String CMD_EXECUTE_STATEMENT = "com.seizia.chromite.ui.editors.sql.run.statement"; //$NON-NLS-1$
    String CMD_EXECUTE_STATEMENT_NEW = "com.seizia.chromite.ui.editors.sql.run.statementNew"; //$NON-NLS-1$
    String CMD_EXECUTE_SCRIPT = "com.seizia.chromite.ui.editors.sql.run.script"; //$NON-NLS-1$
    String CMD_EXECUTE_SCRIPT_NATIVE = "com.seizia.chromite.core.sql.script.run.scriptNative"; //$NON-NLS-1$
    String CMD_EXECUTE_SCRIPT_FROM_POSITION = "com.seizia.chromite.ui.editors.sql.run.scriptFromPosition"; //$NON-NLS-1$
    String CMD_EXECUTE_SCRIPT_NEW = "com.seizia.chromite.ui.editors.sql.run.scriptNew"; //$NON-NLS-1$

    String CMD_EXECUTE_ROW_COUNT = "com.seizia.chromite.ui.editors.sql.run.count"; //$NON-NLS-1$

    String CMD_EXECUTE_EXPRESSION = "com.seizia.chromite.ui.editors.sql.run.expression"; //$NON-NLS-1$
    String CMD_EXECUTE_ALL_ROWS = "com.seizia.chromite.ui.editors.sql.run.all.rows"; //$NON-NLS-1$
    String CMD_EXPLAIN_PLAN = "com.seizia.chromite.ui.editors.sql.run.explain"; //$NON-NLS-1$
    String CMD_LOAD_PLAN = "com.seizia.chromite.ui.editors.sql.load.plan"; //$NON-NLS-1$
    String CMD_MULTIPLE_RESULTS_PER_TAB = "com.seizia.chromite.ui.editors.sql.multipleResultsPerTab"; //$NON-NLS-1$
    String CMD_SQL_EDITOR_DEF_COMMAND = "com.seizia.chromite.core.sql.editor.defaultCommand";
    String CMD_SQL_EDITOR_OPEN = "com.seizia.chromite.core.sql.editor.open";
    String CMD_SQL_EDITOR_NEW = "com.seizia.chromite.core.sql.editor.create";
    String CMD_SQL_EDITOR_RECENT = "com.seizia.chromite.core.sql.editor.recent";
    String CMD_SQL_EDITOR_CONSOLE = "com.seizia.chromite.core.sql.editor.console";
    String CMD_SQL_QUERY_NEXT = "com.seizia.chromite.ui.editors.sql.query.next";
    String CMD_SQL_QUERY_PREV = "com.seizia.chromite.ui.editors.sql.query.prev";
    String CMD_SQL_GOTO_MATCHING_BRACKET = "com.seizia.chromite.ui.editors.sql.gotoMatchingBracket";
    String CMD_SQL_SELECT_TO_MATCHING_BRACKET = "com.seizia.chromite.ui.editors.sql.selectToMatchingBracket";
    String CMD_SQL_SWITCH_PANEL = "com.seizia.chromite.ui.editors.sql.switch.panel";
    String CMD_SQL_SHOW_OUTPUT = "com.seizia.chromite.ui.editors.sql.show.output";
    String CMD_SQL_SHOW_LOG = "com.seizia.chromite.ui.editors.sql.show.log";
    String CMD_SQL_SHOW_VARIABLES = "com.seizia.chromite.ui.editors.sql.show.variables";
    String CMD_SQL_SHOW_OUTLINE = "com.seizia.chromite.ui.editors.sql.show.outline";
    String CMD_SQL_EDITOR_MAXIMIZE_PANEL = "com.seizia.chromite.ui.editors.sql.maximize.result.panel";
    String CMD_SQL_EDITOR_CLOSE_TAB = "com.seizia.chromite.ui.editors.sql.close.tab";
    String CMD_SQL_EDITOR_TOGGLE_TAB_PINNED = "com.seizia.chromite.ui.editors.sql.toggle.pinned.tab";
    String CMD_SQL_ASSIST_TEMPLATES = "com.seizia.chromite.ui.editors.sql.assist.templates"; //$NON-NLS-1$
    String CMD_SYNC_CONNECTION = "com.seizia.chromite.ui.editors.sql.sync.connection";
    String CMD_SQL_RENAME = "com.seizia.chromite.ui.editors.sql.rename";
    String CMD_SQL_DELETE_THIS_SCRIPT = "com.seizia.chromite.ui.editors.sql.deleteThisScript";
    String CMD_OPEN_FILE = "com.seizia.chromite.ui.editors.sql.open.file"; //$NON-NLS-1$
    String CMD_SAVE_FILE = "com.seizia.chromite.ui.editors.sql.save.file"; //$NON-NLS-1$
    String CMD_DISABLE_SQL_SYNTAX_PARSER = "com.seizia.chromite.ui.editors.sql.disableSQLSyntaxParser"; //$NON-NLS-1$

    String CMD_NAVIGATE_OBJECT = "com.seizia.chromite.ui.editors.sql.navigate.object";
    String CMD_TOGGLE_LAYOUT = "com.seizia.chromite.ui.editors.sql.toggleLayout";
    String CMD_TOGGLE_SEPARATE_CONNECTION = "com.seizia.chromite.ui.editors.sql.connection.separate";

    String CMD_AI_SUGGESTION = "com.seizia.chromite.ai.sql.suggestion";
    String CMD_AI_CHAT_TOGGLE = "com.seizia.chromite.ai.chat.toggle"; //$NON-NLS-1$
}
