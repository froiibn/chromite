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
 * {@link #readsOnly()}(2026-11-09): 끝내도 잃는 것이 없다는 것은 DB가 직접 확인한 때만 참이다. 잠금의 종류(읽기 잠금인지)와 변경 유무를
 * 권한 없이 확실히 읽을 수 있는 DB만 방언이 켜고, 그 밖의 답(SQL Server, Oracle, 권한 없는 MySQL)은 늘 거짓이다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/idle-transactions.md "트랜잭션 상태")
 * - 2026-11-09 {@link #readsOnly()}: 읽기 잠금·스냅숏만 쥔 트랜잭션을 한 번에 끝내기와 자동으로 끝내기(앱 변경 #31)
 *   (docs/chromite/idle-transactions.md "조회만 한 트랜잭션 끝내기")
 *
 * @param readsOnly the database confirmed that the transaction holds nothing but read locks and a snapshot (no data
 *                  change, no row lock, no lock of another kind): a rollback discards nothing. Only for {@link Kind#HOLDING}
 *                  with {@link Hold#LOCKS} and {@link Hold#SNAPSHOT} at most; false otherwise.
 */
public record DBCServerTransaction(@NotNull Kind kind, @NotNull Set<Hold> holds, boolean readsOnly) {

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

    // What a transaction that only reads may hold (before the constants below, which the constructor checks against it)
    private static final Set<Hold> READ_HOLDS = EnumSet.of(Hold.LOCKS, Hold.SNAPSHOT);

    public static final DBCServerTransaction NONE = new DBCServerTransaction(Kind.NONE, Set.of());
    public static final DBCServerTransaction EMPTY = new DBCServerTransaction(Kind.EMPTY, Set.of());
    public static final DBCServerTransaction UNVERIFIED = new DBCServerTransaction(Kind.UNVERIFIED, Set.of());
    public static final DBCServerTransaction FAILED = new DBCServerTransaction(Kind.FAILED, Set.of());

    public DBCServerTransaction {
        holds = holds.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(holds));
        readsOnly = readsOnly && kind == Kind.HOLDING && !holds.isEmpty() && READ_HOLDS.containsAll(holds);
    }

    /**
     * An answer that does not say whether the transaction only reads.
     */
    public DBCServerTransaction(@NotNull Kind kind, @NotNull Set<Hold> holds) {
        this(kind, holds, false);
    }

    /**
     * A transaction that holds the given things; {@link #EMPTY} when there are none.
     */
    @NotNull
    public static DBCServerTransaction holding(@NotNull Set<Hold> holds) {
        return holding(holds, false);
    }

    /**
     * A transaction that holds the given things; {@link #EMPTY} when there are none.
     *
     * @param readsOnly the database confirmed that the holds are read locks and a snapshot only ({@link #readsOnly()})
     */
    @NotNull
    public static DBCServerTransaction holding(@NotNull Set<Hold> holds, boolean readsOnly) {
        return holds.isEmpty() ? EMPTY : new DBCServerTransaction(Kind.HOLDING, holds, readsOnly);
    }
}
