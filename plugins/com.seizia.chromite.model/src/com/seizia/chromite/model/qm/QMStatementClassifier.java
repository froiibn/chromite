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
package com.seizia.chromite.model.qm;

import org.jkiss.code.Nullable;

import java.util.regex.Pattern;

/**
 * Classifies user statements by their text, for what the database does not tell: an explicit begin of a transaction
 * (which leaves nothing to see on the server until something is held), and the statements that keep locks when the
 * database cannot be asked.
 *
 * 설계(docs/chromite/idle-transactions.md "문장 분류"): DB에 물을 수 있으면 잠금·변경은 DB의 답으로 정한다(문장 글자의 힌트를
 * 해석하지 않는다). 이 분류는 두 경우에만 쓴다. (1) 명시적 BEGIN: 수동 커밋 PostgreSQL의 빈 {@code BEGIN}처럼 서버에 보이는 것이
 * 없어도 사용자가 연 트랜잭션은 열림이다. (2) DB에 물을 수 없을 때(질의가 없는 DB, 권한 없음, 질의 실패) 잠그는 읽기를 찾는다.
 * PL/SQL·T-SQL 블록의 {@code BEGIN ... END}, {@code BEGIN TRY}는 트랜잭션 시작이 아니다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/idle-transactions.md "문장 분류")
 */
final class QMStatementClassifier {

    private static final Pattern LEADING_COMMENTS = Pattern.compile("^(\\s+|--[^\\n]*(\\n|$)|/\\*.*?\\*/)+", Pattern.DOTALL);
    // BEGIN alone (PostgreSQL, MySQL, SQLite), BEGIN TRAN[SACTION]/WORK and the PostgreSQL and SQLite options, START TRANSACTION
    private static final Pattern BEGIN = Pattern.compile(
        "^(BEGIN\\s*;?\\s*$|BEGIN\\s+(TRAN|TRANSACTION|WORK|ISOLATION|READ|NOT|DEFERRABLE|DEFERRED|IMMEDIATE|EXCLUSIVE|DISTRIBUTED)\\b"
            + "|START\\s+TRANSACTION\\b)",
        Pattern.CASE_INSENSITIVE);
    // COMMIT, ROLLBACK (not to a savepoint), END (PostgreSQL)
    private static final Pattern END = Pattern.compile(
        "^(COMMIT\\b|END\\s*(TRAN|TRANSACTION|WORK)?\\s*;?\\s*$|ROLLBACK\\b(?!\\s+(WORK\\s+)?TO\\b))",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCKING_READ = Pattern.compile(
        "\\bFOR\\s+(UPDATE|SHARE|NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE)\\b"
            + "|\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b"
            + "|^LOCK\\s"
            // SQL Server table hints: WITH (UPDLOCK, ROWLOCK), or the old form t (HOLDLOCK)
            + "|\\(\\s*[^()]*\\b(UPDLOCK|HOLDLOCK|XLOCK|TABLOCKX|TABLOCK|PAGLOCK|REPEATABLEREAD|SERIALIZABLE)\\b[^()]*\\)",
        Pattern.CASE_INSENSITIVE);

    // Statements that change the session's settings, not data (the query manager counts every non-query as "may have changed")
    private static final Pattern SESSION_SETTING = Pattern.compile("^(SET|USE|SAVEPOINT|RELEASE|ALTER\\s+SESSION)\\b", Pattern.CASE_INSENSITIVE);

    private QMStatementClassifier() {
    }

    /**
     * A statement that changes the session, not data: SET, USE, ALTER SESSION, savepoints and the transaction control
     * statements.
     */
    static boolean isSessionStatement(@Nullable String text) {
        String statement = strip(text);
        return SESSION_SETTING.matcher(statement).find() || getTransactionControl(statement) != null;
    }

    /**
     * @return true for a statement that begins a transaction explicitly, false for one that ends it, null for others
     */
    @Nullable
    static Boolean getTransactionControl(@Nullable String text) {
        String statement = strip(text);
        if (statement.isEmpty()) {
            return null;
        }
        if (BEGIN.matcher(statement).find()) {
            return Boolean.TRUE;
        }
        if (END.matcher(statement).find()) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * A statement that keeps locks to the end of the transaction: FOR UPDATE/SHARE, LOCK IN SHARE MODE, LOCK [TABLE],
     * SQL Server locking table hints.
     */
    static boolean isLockingRead(@Nullable String text) {
        String statement = strip(text);
        return !statement.isEmpty() && LOCKING_READ.matcher(statement).find();
    }

    private static String strip(@Nullable String text) {
        return text == null ? "" : LEADING_COMMENTS.matcher(text).replaceFirst("");
    }
}
