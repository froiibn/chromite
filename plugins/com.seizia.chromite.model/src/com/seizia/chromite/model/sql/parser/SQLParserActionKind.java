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
package com.seizia.chromite.model.sql.parser;

/**
 * What the script parser does when a dialect's token predicate matches (SQLScriptParser).
 *
 * 설계: {@link #NESTED_BLOCK_HEADER} is its own kind, not a second {@link #BLOCK_HEADER}: the AS / IS that BLOCK_HEADER matches
 * also follows a cursor or type declaration inside a routine's declarations ("CURSOR c IS …"), so only the word that starts a
 * subprogram declaration can tell a nested subprogram from those.
 *
 * 변경 이력:
 * - 2026-11-25 Chromite: {@link #NESTED_BLOCK_HEADER} for a subprogram declared in a routine's declarations: its BEGIN closed the
 *   routine's header and its END ended the statement (Oracle, docs/chromite/swing-query-commands.md "Oracle 스크립트 나누기 2").
 */
public enum SQLParserActionKind {
    SKIP_SUFFIX_TERM,
    BEGIN_BLOCK,
    CAPTURE_COMMAND,
    BLOCK_HEADER,
    END_BLOCK,
    /**
     * The word that starts a subprogram declared in the declarations of a routine whose header a {@link #BLOCK_HEADER} opened.
     * It opens a header of its own inside that one: the subprogram's AS / IS ({@link #BLOCK_HEADER}) turns it into a declaration
     * section that its BEGIN or a call specification ({@link #END_BLOCK}) closes, and a delimiter before any AS / IS ends it
     * (a forward declaration).
     */
    NESTED_BLOCK_HEADER
}
