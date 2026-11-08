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
package com.seizia.chromite.model.exec;

import org.jkiss.code.NotNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What the database itself reports about a session's transaction ({@link DBCTransactionProbe}): whether there is one
 * and what it holds until it ends.
 *
 * 설계(docs/chromite/idle-transactions.md "트랜잭션 상태"): 사용자 원칙 "실제로 쥐고 있을 때만 하라"(2026-11-08). 문장 종류로
 * 짐작하지 않고 DB에 물은 답을 담는다. 서버에 트랜잭션이 있어도 쥔 것이 없을 수 있고(SQL Server 암시적 트랜잭션의 일반 SELECT,
 * PostgreSQL {@code SELECT 1}), 무엇을 쥐었는지 읽을 권한이 없을 수도 있어서(SQL Server DMV) 종류를 나눈다. 명시적 BEGIN을 문장으로
 * 보았는지, 데이터를 바꾼 문장이 있었는지는 서버가 모르는 경우가 있어 쿼리 관리자({@code QMUtils.getTransactionState})가 합친다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/idle-transactions.md "트랜잭션 상태")
 */
public record DBCServerTransaction(@NotNull Kind kind, @NotNull Set<Hold> holds) {

    public enum Kind {
        /** No transaction on the server. */
        NONE,
        /** A transaction that holds something until it ends ({@link #holds()} says what). */
        HOLDING,
        /** A transaction that holds nothing: ending it changes nothing (unless the user began it explicitly). */
        EMPTY,
        /** A transaction whose holdings cannot be read (no permission on the server's views). */
        UNVERIFIED,
        /** The question failed (connection error, timeout). */
        FAILED
    }

    public enum Hold {
        /** Uncommitted data changes (log records, an assigned transaction id). */
        CHANGES,
        /** Locks held to the end of the transaction (row, table, metadata or predicate locks). */
        LOCKS,
        /** A snapshot held to the end of the transaction (SNAPSHOT, REPEATABLE READ, SERIALIZABLE). */
        SNAPSHOT,
        /** Begun explicitly (BEGIN TRAN, BEGIN, START TRANSACTION, SET TRANSACTION). */
        EXPLICIT,
        /** Aborted by an error: only a rollback ends it. */
        ABORTED
    }

    public static final DBCServerTransaction NONE = new DBCServerTransaction(Kind.NONE, Set.of());
    public static final DBCServerTransaction EMPTY = new DBCServerTransaction(Kind.EMPTY, Set.of());
    public static final DBCServerTransaction UNVERIFIED = new DBCServerTransaction(Kind.UNVERIFIED, Set.of());
    public static final DBCServerTransaction FAILED = new DBCServerTransaction(Kind.FAILED, Set.of());

    public DBCServerTransaction {
        holds = holds.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(holds));
    }

    /**
     * A transaction that holds the given things; {@link #EMPTY} when there are none.
     */
    @NotNull
    public static DBCServerTransaction holding(@NotNull Set<Hold> holds) {
        return holds.isEmpty() ? EMPTY : new DBCServerTransaction(Kind.HOLDING, holds);
    }
}
