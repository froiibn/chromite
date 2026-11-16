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
package com.seizia.chromite.model.sql;

import com.seizia.chromite.junit.ChromiteUnitTest;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.sql.semantics.SQLObjectOperationRecognizer;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Regression tests of the statements the query window asks about before it runs them (docs/chromite/swing-sql-exec-safety.md):
 * DROP of any object kind, TRUNCATE, and UPDATE/DELETE without WHERE.
 *
 * 설계: 쿼리 창({@code SqlExecutionSafety})은 JSQLParser 판단({@link SQLQuery#isDropDangerous()})이 아니라고 하거나 읽지 못하면 LSM 파서
 * ({@link SQLObjectOperationRecognizer#isDropStatement})로 다시 본다. JSQLParser가 읽지 못하는 DROP(DATABASE, USER, TABLESPACE, PROCEDURE,
 * TRIGGER 등)을 놓친 것이 근본 원인이었으므로, 쿼리 창과 같은 조합({@link #isDrop})으로 DROP 종류 전체를 확인하고, DROP이 아닌 문
 * ({@code ALTER TABLE ... DROP COLUMN} 포함)은 묻지 않는지 본다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "SQL 실행 안전")
 */
public class SqlExecutionDangerDetectionTest extends ChromiteUnitTest {

    private final SQLDialect dialect = BasicSQLDialect.INSTANCE;
    private SQLSyntaxManager syntaxManager;

    @BeforeEach
    public void createSyntaxManager() {
        syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dialect, DBWorkbench.getPlatform().getPreferenceStore());
    }

    @Test
    public void everyDropKindIsFound() {
        for (String sql : List.of(
            "DROP TABLE orders",
            "DROP TABLE IF EXISTS orders, order_lines",
            "DROP VIEW v_orders",
            "DROP INDEX ix_orders",
            "DROP SCHEMA sales",
            "DROP DATABASE shop",
            "DROP CATALOG shop",
            "DROP PROCEDURE p_close_month",
            "DROP FUNCTION f_total",
            "DROP TRIGGER trg_orders",
            "DROP SEQUENCE seq_orders",
            "DROP USER 'app'@'localhost'",
            "DROP TABLESPACE ts_data",
            "DROP ROLE reporting",
            "DROP MATERIALIZED VIEW mv_sales"
        )) {
            Assertions.assertTrue(isDrop(sql), sql);
        }
    }

    @Test
    public void otherStatementsAreNotDrops() {
        for (String sql : List.of(
            "SELECT * FROM orders",
            "DELETE FROM orders WHERE id = 1",
            "CREATE TABLE orders (id INT)",
            "ALTER TABLE orders DROP COLUMN note",
            "UPDATE orders SET status = 'dropped' WHERE id = 1"
        )) {
            Assertions.assertFalse(isDrop(sql), sql);
        }
    }

    @Test
    public void lsmRecognizerReadsTheDropKindDespiteAnUnreadableName() {
        for (String sql : List.of("DROP DATABASE shop", "DROP USER 'app'@'%'", "DROP TABLESPACE ts_data", "DROP TRIGGER trg ON orders")) {
            Assertions.assertTrue(SQLObjectOperationRecognizer.isDropStatement(dialect, syntaxManager, sql), sql);
        }
        for (String sql : List.of("SELECT 1", "ALTER TABLE orders DROP COLUMN note", "CREATE DATABASE shop", "TRUNCATE TABLE orders")) {
            Assertions.assertFalse(SQLObjectOperationRecognizer.isDropStatement(dialect, syntaxManager, sql), sql);
        }
    }

    @Test
    public void truncateAndUnfilteredChangesAreDangerous() {
        Assertions.assertTrue(new SQLQuery(null, "TRUNCATE TABLE orders").isTruncate());
        Assertions.assertFalse(new SQLQuery(null, "DELETE FROM orders").isTruncate());
        Assertions.assertTrue(new SQLQuery(null, "DELETE FROM orders").isDeleteUpdateDangerous());
        Assertions.assertTrue(new SQLQuery(null, "UPDATE orders SET status = 'x'").isDeleteUpdateDangerous());
        Assertions.assertFalse(new SQLQuery(null, "UPDATE orders SET status = 'x' WHERE id = 1").isDeleteUpdateDangerous());
        Assertions.assertFalse(new SQLQuery(null, "SELECT * FROM orders").isTruncate());
    }

    /**
     * The decision of {@code SqlExecutionSafety.isDrop}: JSQLParser first, the LSM parser for what it cannot read.
     */
    private boolean isDrop(@NotNull String sql) {
        SQLQuery query = new SQLQuery(null, sql);
        return query.isDropDangerous()
            || query.getParseError() != null && SQLObjectOperationRecognizer.isDropStatement(dialect, syntaxManager, query.getText());
    }
}
