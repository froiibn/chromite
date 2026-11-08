/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.postgresql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.ext.postgresql.PostgreConstants;
import com.seizia.chromite.ext.postgresql.internal.PostgreSQLMessages;
import com.seizia.chromite.ext.postgresql.model.data.PostgreBinaryFormatter;
import com.seizia.chromite.ext.postgresql.model.impls.PostgreServerPostgreSQL;
import com.seizia.chromite.ext.postgresql.sql.PostgreEscapeStringRule;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.data.DBDBinaryFormatter;
import com.seizia.chromite.model.exec.DBCLogicalOperator;
import com.seizia.chromite.model.exec.DBCServerTransaction;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCTransactionProbe;
import com.seizia.chromite.model.impl.jdbc.JDBCDataSource;
import com.seizia.chromite.model.impl.jdbc.JDBCSQLDialect;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.sql.SQLDialectFeatures;
import com.seizia.chromite.model.sql.*;
import com.seizia.chromite.model.sql.parser.rules.SQLDollarQuoteRule;
import com.seizia.chromite.model.struct.*;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import com.seizia.chromite.model.text.parser.TPRule;
import com.seizia.chromite.model.text.parser.TPRuleProvider;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.*;

/**
 * PostgreSQL dialect
 *
 * 설계: {@link #convertExternalDataType} maps foreign types that PostgreSQL knows under another name or that the
 * generic name search maps to the wrong kind (character LOBs became bytea, datetimeoffset lost its offset).
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: data transfer type mapping — uniqueidentifier, character LOBs, timestamps with and without
 *   time zone (Oracle DATE has a time part); text and bytea never get a length (text(10485760) failed).
 * - 2026-11-07 Chromite: MariaDB uuid (a character type there) becomes uuid instead of varchar(36).
 * - 2026-11-07 Chromite: a one-bit BIT of another database (SQL Server bit) becomes boolean instead of bit.
 * - 2026-11-08 Chromite: a numeric without precision (display size 131089, e.g. an expression column of a query result)
 *   gets no modifiers in a new column; numeric(131089) failed (docs/chromite/swing-result-generate-sql.md).
 * - 2026-11-08 Chromite: {@link #probeTransaction}: what the session's transaction holds, asked from the server
 *   (docs/chromite/idle-transactions.md "PostgreSQL").
 */
public class PostgreDialect extends JDBCSQLDialect implements TPRuleProvider, SQLDataTypeConverter,
    SQLDialectDDLExtension, SQLDialectSchemaController, JDBCTransactionProbe {

    private static final String TYPE_NUMERIC = "numeric"; //$NON-NLS-1$
    private static final String TYPE_DECIMAL = "decimal"; //$NON-NLS-1$
    /**
     * Largest precision of a numeric column (the server's NUMERIC_MAX_PRECISION).
     */
    private static final int NUMERIC_MAX_PRECISION = 1000;

    // The session's granted locks besides its own virtual transaction id. Relations below FirstNormalObjectId (16384)
    // are system catalogs and views, pg_locks among them: their AccessShareLock blocks nobody's DDL and the probe itself
    // takes the one on pg_locks. A transaction id is assigned by the first write or row lock (FOR UPDATE/SHARE); with a
    // write-mode relation lock it means changes.
    private static final String TRANSACTION_PROBE =
        "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks l WHERE l.pid = pg_catalog.pg_backend_pid() AND l.locktype = 'transactionid')" //$NON-NLS-1$
            + " AND EXISTS (SELECT 1 FROM pg_catalog.pg_locks l WHERE l.pid = pg_catalog.pg_backend_pid() AND l.locktype = 'relation'" //$NON-NLS-1$
            + " AND l.mode NOT IN ('AccessShareLock', 'RowShareLock') AND l.relation >= 16384)," //$NON-NLS-1$
            + " (SELECT count(*) FROM pg_catalog.pg_locks l WHERE l.pid = pg_catalog.pg_backend_pid() AND l.granted" //$NON-NLS-1$
            + " AND l.locktype NOT IN ('virtualxid', 'transactionid') AND (l.relation IS NULL OR l.relation >= 16384))," //$NON-NLS-1$
            + " pg_catalog.current_setting('transaction_isolation')"; //$NON-NLS-1$
    private static final String PROBE_SAVEPOINT = "chromite_txn_probe"; //$NON-NLS-1$

    @NotNull
    @Override
    public SQLDialectFeatures getDialectFeatures() {
        return DialectFeatures.INSTANCE;
    }

    /**
     * pgjdbc's protocol state (the server's ReadyForQuery status) tells without a round trip whether there is a
     * transaction: in manual commit the driver sends BEGIN before any statement outside one, so a query would start one.
     * Inside a transaction the locks decide: a plain SELECT keeps AccessShareLock on its tables to the end, which blocks
     * ALTER, DROP and TRUNCATE of another session; {@code SELECT 1} holds nothing. REPEATABLE READ and SERIALIZABLE keep
     * their snapshot. The query runs in a savepoint: an error (a statement timeout) would otherwise abort the user's
     * transaction. Only PostgreSQL servers; the other server types behind this dialect fall back to statement classification.
     */
    @Nullable
    @Override
    public DBCServerTransaction probeTransaction(@NotNull DBPDataSource dataSource, @NotNull Connection connection) throws SQLException {
        if (!(dataSource instanceof PostgreDataSource pgDataSource) || !(pgDataSource.getServerType() instanceof PostgreServerPostgreSQL)) {
            return null;
        }
        String state;
        try {
            Object value = connection.getClass().getMethod("getTransactionState").invoke(connection); //$NON-NLS-1$
            state = value == null ? null : value.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not pgjdbc: without the protocol state a query could start a transaction
            return null;
        }
        if ("IDLE".equals(state)) { //$NON-NLS-1$
            return DBCServerTransaction.NONE;
        }
        if ("FAILED".equals(state)) { //$NON-NLS-1$
            return DBCServerTransaction.holding(EnumSet.of(DBCServerTransaction.Hold.ABORTED));
        }
        if (!"OPEN".equals(state)) { //$NON-NLS-1$
            return DBCServerTransaction.FAILED;
        }
        Set<DBCServerTransaction.Hold> holds = EnumSet.noneOf(DBCServerTransaction.Hold.class);
        if (connection.getAutoCommit()) {
            // The driver never begins one in auto-commit mode
            holds.add(DBCServerTransaction.Hold.EXPLICIT);
        }
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            statement.execute("SAVEPOINT " + PROBE_SAVEPOINT); //$NON-NLS-1$
            try (ResultSet rs = statement.executeQuery(TRANSACTION_PROBE)) {
                if (rs.next()) {
                    if (rs.getBoolean(1)) {
                        holds.add(DBCServerTransaction.Hold.CHANGES);
                    }
                    if (rs.getLong(2) > 0) {
                        holds.add(DBCServerTransaction.Hold.LOCKS);
                    }
                    String isolation = rs.getString(3);
                    if ("repeatable read".equals(isolation) || "serializable".equals(isolation)) { //$NON-NLS-1$ //$NON-NLS-2$
                        holds.add(DBCServerTransaction.Hold.SNAPSHOT);
                    }
                }
            } catch (SQLException e) {
                statement.execute("ROLLBACK TO SAVEPOINT " + PROBE_SAVEPOINT); //$NON-NLS-1$
                statement.execute("RELEASE SAVEPOINT " + PROBE_SAVEPOINT); //$NON-NLS-1$
                throw e;
            }
            statement.execute("RELEASE SAVEPOINT " + PROBE_SAVEPOINT); //$NON-NLS-1$
        }
        return DBCServerTransaction.holding(holds);
    }

    private static class DialectFeatures extends SQLDialectFeatures {
        static final DialectFeatures INSTANCE = new DialectFeatures();

        @NotNull
        @Override
        public TrailingSelectIntoSupport getTrailingSelectIntoSupport() {
            return TrailingSelectIntoSupport.PROCEDURAL;
        }

        @Override
        public boolean areRoutineTableResultColumnsBodyVisible() {
            return true;
        }

        @NotNull
        @Override
        public Set<String> getSignatureModifierKeywords() {
            Set<String> keywords = new HashSet<>(super.getSignatureModifierKeywords());
            keywords.add("VARIADIC");
            return Set.copyOf(keywords);
        }

        /** PL/pgSQL nested blocks can shadow declarations in an enclosing block.
         *
         * @see <a href="https://www.postgresql.org/docs/current/plpgsql-structure.html">PL/pgSQL structure</a>
         */
        @NotNull
        @Override
        public LocalVariableShadowing getLocalVariableShadowing(@NotNull LocalVariableKind kind) {
            return LocalVariableShadowing.ALLOW_OUTER;
        }

        @Override
        public EnumSet<ProjectionAliasVisibilityScope> getProjectionAliasVisibilityScope() {
            return EnumSet.of(
                ProjectionAliasVisibilityScope.GROUP_BY,
                ProjectionAliasVisibilityScope.ORDER_BY
            );
        }

        @NotNull
        @Override
        public GlobalVariableInfo[] getGlobalVariables() {
            return new GlobalVariableInfo[]{
                new GlobalVariableInfo("current_date", PostgreSQLMessages.global_variable_current_date_description, DBPDataKind.DATETIME),
                new GlobalVariableInfo("current_time", PostgreSQLMessages.global_variable_current_time_description, DBPDataKind.DATETIME),
                new GlobalVariableInfo("current_timestamp", PostgreSQLMessages.global_variable_current_timestamp_description, DBPDataKind.DATETIME),
                new GlobalVariableInfo("localtime", PostgreSQLMessages.global_variable_localtime_description, DBPDataKind.DATETIME),
                new GlobalVariableInfo("localtimestamp", PostgreSQLMessages.global_variable_localtimestamp_description, DBPDataKind.DATETIME),
                new GlobalVariableInfo("current_role", PostgreSQLMessages.global_variable_user_description, DBPDataKind.STRING),
                new GlobalVariableInfo("current_user", PostgreSQLMessages.global_variable_user_description, DBPDataKind.STRING),
                new GlobalVariableInfo("current_catalog ", PostgreSQLMessages.global_variable_current_catalog_description, DBPDataKind.STRING),
                new GlobalVariableInfo("current_schema", PostgreSQLMessages.global_variable_current_schema_description, DBPDataKind.STRING),
                new GlobalVariableInfo("session_user", PostgreSQLMessages.global_variable_session_user_description, DBPDataKind.STRING),
                new GlobalVariableInfo("system_user", PostgreSQLMessages.global_variable_system_user_description, DBPDataKind.STRING),
                new GlobalVariableInfo("user", PostgreSQLMessages.global_variable_user_description, DBPDataKind.STRING)
            };
        }
    }

    public static final String[] POSTGRE_NON_TRANSACTIONAL_KEYWORDS = ArrayUtils.concatArrays(
        BasicSQLDialect.NON_TRANSACTIONAL_KEYWORDS,
        new String[]{
            "SHOW", "SET"
        }
    );

    private static final String[][] PG_STRING_QUOTES = {
        {"'", "'"}
    };

    // In PgSQL there are no blocks. DO $$ ... $$ queries are processed as strings
    public static final String[][] BLOCK_BOUND_KEYWORDS = {
//        {SQLConstants.BLOCK_BEGIN, SQLConstants.BLOCK_END},
//        {"LOOP", "END LOOP"}
    };

    private static final String[] EXEC_KEYWORDS = {
        "CALL"
    };

    public static final String AUTO_INCREMENT_KEYWORD = "AUTO_INCREMENT";

    //region KeyWords

    public static String[] POSTGRE_EXTRA_KEYWORDS = new String[]{
        "ABSENT",
        "ACCORDING",
        "ADA",
        "ADMIN",
//            "ARRAY_AGG",
//            "ARRAY_MAX_CARDINALITY",
        "BASE64",
        "BEGIN_FRAME",
        "BEGIN_PARTITION",
        "BERNOULLI",
        "BIT_LENGTH",
        "BLOCKED",
        "BOM",
        //"BREADTH",
//            "CATALOG_NAME",
//            "CHARACTER_SET_CATALOG",
//            "CHARACTER_SET_NAME",
//            "CHARACTER_SET_SCHEMA",
//            "CLASS_ORIGIN",
        //"COBOL",
//            "COLLATION_CATALOG",
//            "COLLATION_NAME",
//            "COLLATION_SCHEMA",
//            "COLUMN_NAME",
//            "COMMAND_FUNCTION",
//            "COMMAND_FUNCTION_CODE",
//            "CONDITION_NUMBER",
//            "CONNECTION_NAME",
//            "CONSTRAINT_CATALOG",
//            "CONSTRAINT_NAME",
//            "CONSTRAINT_SCHEMA",
        "CONTROL",
//            "CURRENT_ROW",
//            "DATALINK",
//            "DATETIME_INTERVAL_CODE",
//            "DATETIME_INTERVAL_PRECISION",
        //"DB",
        "DLNEWCOPY",
        "DLPREVIOUSCOPY",
        "DLURLCOMPLETE",
        "DLURLCOMPLETEONLY",
        "DLURLCOMPLETEWRITE",
        "DLURLPATH",
        "DLURLPATHONLY",
        "DLURLPATHWRITE",
        "DLURLSCHEME",
        "DLURLSERVER",
        "DLVALUE",
        "DYNAMIC_FUNCTION",
        "DYNAMIC_FUNCTION_CODE",
        "EMPTY",
        "END_FRAME",
        "END_PARTITION",
        "ENFORCED",
        "EXIT",
        "EXPRESSION",
        //"FILE",
        "FIRST_VALUE",
        //"FLAG",
        //"FORTRAN",
        "FRAME_ROW",
        "FS",
        "GROUPS",
        //"HEX",
        //"ID",
        "IGNORE",
        "IMMEDIATELY",
        "INCLUDE",
        "INDENT",
        "INTEGRITY",
        "KEY_MEMBER",
        "LAG",
        "LAST_VALUE",
        "LEAD",
        "LIBRARY",
        "LIKE_REGEX",
        //"LINK",
//            "MAX_CARDINALITY",
//            "MESSAGE_LENGTH",
//            "MESSAGE_OCTET_LENGTH",
//            "MESSAGE_TEXT",
        //"MODULE",
        //"NAME",
        //"NAMES",
        "NAMESPACE",
        //"NFC",
        //"NFD",
        //"NFKC",
        //"NFKD",
        "NIL",
        "NTH_VALUE",
        "NTILE",
        "NULLABLE",
        "OCCURRENCES_REGEX",
//            "PARAMETER_MODE",
//            "PARAMETER_NAME",
//            "PARAMETER_ORDINAL_POSITION",
//            "PARAMETER_SPECIFIC_CATALOG",
//            "PARAMETER_SPECIFIC_NAME",
//            "PARAMETER_SPECIFIC_SCHEMA",
        //"PASCAL",
        "PASSTHROUGH",
        "PERCENT",
        "PERIOD",
        "PERMISSION",
        //"PLI",
        //"PORTION",
        "POSITION_REGEX",
        "PRECEDES",
        "PROCEDURES",
        //"PUBLIC",
        "RECOVERY",
        "REQUIRING",
        "RESPECT",
        "RESTORE",
        "RULE",
//            "RETURNED_CARDINALITY",
//            "RETURNED_LENGTH",
//            "RETURNED_OCTET_LENGTH",
//            "RETURNED_SQLSTATE",
//            "ROUTINES",
//            "ROUTINE_CATALOG",
//            "ROUTINE_NAME",
//            "ROUTINE_SCHEMA",
        //"ROW_COUNT",
        //"SCHEMA_NAME",
        //"SCOPE_CATALOG",
        //"SCOPE_NAME",
        //"SCOPE_SCHEMA",
        //"SELECTIVE",
        //"SERVER_NAME",
        "SIMPLE",
        //"SPECIFIC_NAME",
        "SQLCODE",
        "SQLERROR",
        //"STATE",
        //"SUBCLASS_ORIGIN",
        //"SUBSTRING_REGEX",
        "SUCCEEDS",
        //"SYSTEM_TIME",
        //"TABLE_NAME",
        "TOKEN",
        //"TOP_LEVEL_COUNT",
        //"TRANSACTIONS_COMMITTED",
        //"TRANSACTIONS_ROLLED_BACK",
        //"TRANSACTION_ACTIVE",
        //"TRANSLATE_REGEX",
        //"TRIGGER_CATALOG",
        //"TRIGGER_NAME",
        //"TRIGGER_SCHEMA",
        //"TRIM_ARRAY",
        "UNLINK",
        "UNTYPED",
        //"URI",
        //"USER_DEFINED_TYPE_CATALOG",
        //"USER_DEFINED_TYPE_CODE",
        //"USER_DEFINED_TYPE_NAME",
        //"USER_DEFINED_TYPE_SCHEMA",        
        //"VALUE",
        //"VALUE_OF",
        "VERSIONING",
        "XMLAGG",
        "XMLBINARY",
        "XMLCAST",
        "XMLCOMMENT",
        "XMLDECLARATION",
        "XMLDOCUMENT",
        "XMLITERATE",
        "XMLQUERY",
        "XMLSCHEMA",
        "XMLTEXT",
        "XMLVALIDATE",
        "SQLERRM",
        "WHILE"
    };
    
    public static String[] POSTGRE_EXTRA_TYPES = new String[]{
        "UUID",
    };

    public static String[] POSTGRE_ONE_CHAR_KEYWORDS = new String[]{
        "C",
        "G",
        "K",
        "M",
        "T",
        "P"
    };
    //endregion

    //region FUNCTIONS KW

    public static String[] POSTGRE_FUNCTIONS_AGGREGATE = new String[]{
        "array_agg",
        "bit_and",
        "bit_or",
        "bool_and",
        "bool_or",
        "every",
        "json_agg",
        "jsonb_agg",
        "json_object_agg",
        "jsonb_object_agg",
        "mode",
        "string_agg",
        "xmlagg",
        "corr",
        "covar_pop",
        "covar_samp",
        "stddev",
        "stddev_pop",
        "stddev_samp",
        "variance",
        "var_pop",
        "var_samp"
    };

    public static String[] POSTGRE_FUNCTIONS_WINDOW = new String[]{
        "row_number",
        "rank",
        "dense_rank",
        "percent_rank",
        "cume_dist",
        "ntile",
        "lag",
        "lead",
        "first_value",
        "last_value",
        "nth_value"
    };


    public static String[] POSTGRE_FUNCTIONS_MATH = new String[]{
        "abs",
        "acos",
        "acosd",
        "asin",
        "asind",
        "atan",
        "atan2",
        "atan2d",
        "atand",
        "cbrt",
        "ceil",
        "ceiling",
        "cos",
        "cosd",
        "cosh",
        "cot",
        "cotd",
        "div",
        "exp",
        "floor",
        "gcd",
        "lcm",
        "ln",
        "log",
        "log10",
        "mod",
        "pi",
        "power",
        "random",
        "round",
        "scale",
        "setseed",
        "sin",
        "sind",
        "sinh",
        "sqrt",
        "tan",
        "tand",
        "trunc",
        "width_bucket"
    };
    public static String[] POSTGRE_FUNCTIONS_STRING = new String[]{
        "bit_length",
        "btrim",
        "chr",
        "concat_ws",
        "convert",
        "convert_from",
        "convert_to",
        "decode",
        "encode",
        "initcap",
        "left",
        "length",
        "lpad",
        "md5",
        "overlay",
        "parse_ident",
        "pg_client_encoding",
        "pg_backend_pid",
        "pg_database_size",
        "pg_sleep",
        "pg_terminate_backend",
        "position",
        "quote_ident",
        "quote_literal",
        "quote_nullable",
        "regexp_count",
        "regexp_instr",
        "regexp_like",
        "regexp_match",
        "regexp_matches",
        "regexp_replace",
        "regexp_split_to_array",
        "regexp_substr",
        "regexp_split_to_table",
        "replace",
        "reverse",
        "right",
        "rpad",
        "split_part",
        "strpos",
        "substring",
        "to_ascii",
        "to_hex",
        "translate",
        "treat",
        "unaccent"
    };

    public static String[] POSTGRE_FUNCTIONS_DATETIME = new String[]{
        "age",
        "clock_timestamp",
        "date_part",
        "date_trunc",
        "isfinite",
        "justify_days",
        "justify_hours",
        "justify_interval",
        "localtime",
        "localtimestamp",
        "make_date",
        "make_interval",
        "make_time",
        "make_timestamp",
        "make_timestamptz",
        "statement_timestamp",
        "timeofday",
        "to_timestamp",
        "transaction_timestamp"
    };

    public static String[] POSTGRE_FUNCTIONS_GEOMETRY = new String[]{
        "area",
        "center",
        "diagonal",
        "diameter",
        "height",
        "isclosed",
        "isopen",
        "npoints",
        "pclose",
        "popen",
        "radius",
        "slope",
        "width",
        "box",
        "bound_box",
        "circle",
        "line",
        "lseg",
        "path",
        "point",
        "polygon"
    };

    public static String[] POSTGRE_FUNCTIONS_NETWROK = new String[]{
        "abbrev",
        "broadcast",
        "family",
        "host",
        "hostmask",
        "masklen",
        "netmask",
        "network",
        "set_masklen",
        "text",
        "inet_same_family",
        "inet_merge",
        "macaddr8_set7bit"
    };

    public static String[] POSTGRE_FUNCTIONS_LO = new String[]{
        "lo_from_bytea",
        "lo_put",
        "lo_get",
        "lo_creat",
        "lo_create",
        "lo_unlink",
        "lo_import",
        "lo_export",
        "loread",
        "lowrite",
        "grouping",
        "cast"
    };

    public static String[] POSTGRE_FUNCTIONS_ADMIN = new String[]{
        "current_setting",
        "set_config",
        "brin_summarize_new_values",
        "brin_summarize_range",
        "brin_desummarize_range",
        "gin_clean_pending_list",
        "pg_cancel_backend",
        "pg_log_backend_memory_contexts",
        "pg_reload_conf",
        "pg_rotate_logfile",
        "pg_create_restore_point",
        "pg_current_wal_flush_lsn",
        "pg_current_wal_insert_lsn",
        "pg_current_wal_lsn",
        "pg_backup_start",
        "pg_backup_stop",
        "pg_switch_wal",
        "pg_walfile_name",
        "pg_walfile_name_offset",
        "pg_split_walfile_name",
        "pg_wal_lsn_diff",
        "pg_is_in_recovery",
        "pg_last_wal_receive_lsn",
        "pg_last_wal_replay_lsn",
        "pg_last_xact_replay_timestamp",
        "pg_get_wal_resource_managers",
        "pg_is_wal_replay_paused",
        "pg_get_wal_replay_pause_state",
        "pg_promote",
        "pg_wal_replay_pause",
        "pg_wal_replay_resume",
        "pg_export_snapshot",
        "pg_log_standby_snapshot",
        "pg_create_physical_replication_slot",
        "pg_drop_replication_slot",
        "pg_create_logical_replication_slot",
        "pg_copy_physical_replication_slot",
        "pg_copy_logical_replication_slot",
        "pg_logical_slot_get_changes",
        "pg_logical_slot_peek_changes",
        "pg_logical_slot_get_binary_changes",
        "pg_logical_slot_peek_binary_changes",
        "pg_replication_slot_advance",
        "pg_replication_origin_create",
        "pg_replication_origin_drop",
        "pg_replication_origin_oid",
        "pg_replication_origin_session_setup",
        "pg_replication_origin_session_reset",
        "pg_replication_origin_session_is_setup",
        "pg_replication_origin_session_progress",
        "pg_replication_origin_xact_setup",
        "pg_replication_origin_xact_reset",
        "pg_replication_origin_advance",
        "pg_replication_origin_progress",
        "pg_logical_emit_message",
        "pg_column_size",
        "pg_column_compression",
        "pg_indexes_size",
        "pg_relation_size",
        "pg_size_bytes",
        "pg_size_pretty",
        "pg_table_size",
        "pg_tablespace_size",
        "pg_total_relation_size",
        "pg_relation_filenode",
        "pg_relation_filepath",
        "pg_filenode_relation",
        "pg_collation_actual_version",
        "pg_database_collation_actual_version",
        "pg_import_system_collations",
        "pg_partition_tree",
        "pg_partition_ancestors",
        "pg_partition_root",
        "pg_ls_dir",
        "pg_ls_logdir",
        "pg_ls_waldir",
        "pg_ls_logicalmapdir",
        "pg_ls_logicalsnapdir",
        "pg_ls_replslotdir",
        "pg_ls_archive_statusdir",
        "pg_ls_tmpdir",
        "pg_read_file",
        "pg_read_binary_file",
        "pg_stat_file",
        "pg_advisory_lock",
        "pg_advisory_lock_shared",
        "pg_advisory_unlock",
        "pg_advisory_unlock_all",
        "pg_advisory_unlock_shared",
        "pg_advisory_xact_lock",
        "pg_advisory_xact_lock_shared",
        "pg_try_advisory_lock",
        "pg_try_advisory_lock_shared",
        "pg_try_advisory_xact_lock",
        "pg_try_advisory_xact_lock_shared"
    };

    public static String[] POSTGRE_FUNCTIONS_RANGE = new String[]{
        "isempty",
        "lower_inc",
        "upper_inc",
        "lower_inf",
        "upper_inf",
        "range_merge"
    };

    public static String[] POSTGRE_FUNCTIONS_TEXT_SEARCH = new String[]{
        "array_to_tsvector",
        "get_current_ts_config",
        "numnode",
        "plainto_tsquery",
        "phraseto_tsquery",
        "websearch_to_tsquery",
        "querytree",
        "setweight",
        "strip",
        "to_tsquery",
        "to_tsvector",
        "json_to_tsvector",
        "jsonb_to_tsvector",
        "ts_delete",
        "ts_filter",
        "ts_headline",
        "ts_rank",
        "ts_rank_cd",
        "ts_rewrite",
        "tsquery_phrase",
        "tsvector_to_array",
        "tsvector_update_trigger",
        "tsvector_update_trigger_column"
    };

    public static String[] POSTGRE_FUNCTIONS_BUILTIN = new String[] {
        "count"
    };


    public static String[] POSTGRE_FUNCTIONS_XML = new String[]{
        "xmlcomment",
        "xmlconcat",
        "xmlelement",
        "xmlforest",
        "xmlpi",
        "xmlroot",
        "xmlexists",
        "xml_is_well_formed",
        "xml_is_well_formed_document",
        "xml_is_well_formed_content",
        "xpath",
        "xpath_exists",
        "xmltable",
        "xmlnamespaces",
        "table_to_xml",
        "table_to_xmlschema",
        "table_to_xml_and_xmlschema",
        "query_to_xml",
        "query_to_xmlschema",
        "query_to_xml_and_xmlschema",
        "cursor_to_xml",
        "cursor_to_xmlschema",
        "schema_to_xml",
        "schema_to_xmlschema",
        "schema_to_xml_and_xmlschema",
        "database_to_xml",
        "database_to_xmlschema",
        "database_to_xml_and_xmlschema",
        "xmlattributes"
    };

    public static String[] POSTGRE_FUNCTIONS_JSON = new String[]{
        "to_json",
        "to_jsonb",
        "array_to_json",
        "row_to_json",
        "json_build_array",
        "jsonb_build_array",
        "json_build_object",
        "jsonb_build_object",
        "json_object",
        "jsonb_object",
        "json_array_length",
        "jsonb_array_length",
        "json_each",
        "jsonb_each",
        "json_each_text",
        "jsonb_each_text",
        "json_extract_path",
        "jsonb_extract_path",
        "json_object_keys",
        "jsonb_object_keys",
        "json_populate_record",
        "jsonb_populate_record",
        "json_populate_recordset",
        "jsonb_populate_recordset",
        "json_array_elements",
        "jsonb_array_elements",
        "json_array_elements_text",
        "jsonb_array_elements_text",
        "json_typeof",
        "jsonb_typeof",
        "json_to_record",
        "jsonb_to_record",
        "json_to_recordset",
        "jsonb_to_recordset",
        "json_strip_nulls",
        "jsonb_strip_nulls",
        "jsonb_set",
        "jsonb_insert",
        "jsonb_pretty"
    };

    public static String[] POSTGRE_FUNCTIONS_ARRAY = new String[]{
        "array_append",
        "array_cat",
        "array_ndims",
        "array_dims",
        "array_fill",
        "array_length",
        "array_lower",
        "array_position",
        "array_positions",
        "array_prepend",
        "array_remove",
        "array_replace",
        "array_to_string",
        "array_upper",
        "cardinality",
        "string_to_array",
        "unnest"
    };

    public static String[] POSTGRE_FUNCTIONS_INFO = new String[]{
        "current_database",
        "current_query",
        "current_schema",
        "current_schemas",
        "inet_client_addr",
        "inet_client_port",
        "inet_server_addr",
        "inet_server_port",
        "row_security_active",
        "format_type",
        "to_regclass",
        "to_regproc",
        "to_regprocedure",
        "to_regoper",
        "to_regoperator",
        "to_regtype",
        "to_regnamespace",
        "to_regrole",
        "col_description",
        "obj_description",
        "shobj_description",
        "txid_current",
        "txid_current_if_assigned",
        "txid_current_snapshot",
        "txid_snapshot_xip",
        "txid_snapshot_xmax",
        "txid_snapshot_xmin",
        "txid_visible_in_snapshot",
        "txid_status"
    };

    public static String[] POSTGRE_FUNCTIONS_COMPRASION = new String[]{
        "num_nonnulls",
        "num_nulls"
    };

    public static String[] POSTGRE_FUNCTIONS_FORMATTING = new String[]{
        "format",
        "to_char",
        "to_date",
        "to_number",
        "to_timestamp"
    };

    public static String[] POSTGRE_FUNCTIONS_ENUM = new String[]{
        "enum_first",
        "enum_last",
        "enum_range"
    };

    public static String[] POSTGRE_FUNCTIONS_SEQUENCE = new String[]{
        "currval",
        "lastval",
        "nextval",
        "setval"
    };

    public static String[] POSTGRE_FUNCTIONS_BINARY_STRING = new String[]{
        "bit_count",
        "get_bit",
        "get_byte",
        "set_bit",
        "set_byte",
        "substr"
    };

    public static String[] POSTGRE_FUNCTIONS_CONDITIONAL = new String[]{
        "coalesce",
        "nullif",
        "greatest",
        "least"
    };

    public static String[] POSTGRE_FUNCTIONS_TRIGGER = new String[]{
        "suppress_redundant_updates_trigger",
        "tsvector_update_trigger",
        "tsvector_update_trigger_column"
    };

    public static String[] POSTGRE_FUNCTIONS_SRF = new String[]{
        "generate_series",
        "generate_subscripts"
    };

    //endregion

    private PostgreServerExtension serverExtension;

    public PostgreDialect() {
        super("PostgreSQL", "postgresql");
    }

    public void addExtraKeywords(String... keywords) {
        super.addSQLKeywords(Arrays.asList(keywords));
    }

    public void addExtraFunctions(String... functions) {
        super.addFunctions(Arrays.asList(functions));
    }
    
    public void initDriverSettings(JDBCSession session, JDBCDataSource dataSource, JDBCDatabaseMetaData metaData) {
        super.initDriverSettings(session, dataSource, metaData);

        addExtraKeywords(
            "SHOW",
            "TYPE",
            "USER",
            "COMMENT",
            "LATERAL",
            "MATERIALIZED",
            "ILIKE",
            "ELSIF",
            "ELSEIF",
            "ANALYSE",
            "ANALYZE",
            "CONCURRENTLY",
            "FREEZE",
            "MAINTAIN", // PostgreSQL 16+ table privilege (GRANT MAINTAIN ON TABLE ...)
            "LANGUAGE",
            "MODULE",
            "OFFSET",
            //"PUBLIC",
            "RETURNING",
            "VARIADIC",
            "PERFORM",
            "FOREACH",
            "LOOP",
            "PERFORM",
            "RAISE",
            "NOTICE",
            "CONFLICT",
            "EXTENSION",

            // "DEBUG", "INFO", "NOTICE", "WARNING", // levels
            // "MESSAGE", "DETAIL", "HINT", "ERRCODE", //options

            "DATATYPE",
            "TABLESPACE",
            "REFRESH"
        );

        addExtraKeywords(POSTGRE_EXTRA_KEYWORDS);
        // Not sure about one char keywords. May confuse users
        //addExtraKeywords(POSTGRE_ONE_CHAR_KEYWORDS);

        addExtraFunctions(PostgreConstants.POSTGIS_FUNCTIONS);

        addExtraFunctions(POSTGRE_FUNCTIONS_ADMIN);
        addExtraFunctions(POSTGRE_FUNCTIONS_AGGREGATE);
        addExtraFunctions(POSTGRE_FUNCTIONS_ARRAY);
        addExtraFunctions(POSTGRE_FUNCTIONS_BINARY_STRING);
        addExtraFunctions(POSTGRE_FUNCTIONS_COMPRASION);
        addExtraFunctions(POSTGRE_FUNCTIONS_CONDITIONAL);
        addExtraFunctions(POSTGRE_FUNCTIONS_DATETIME);
        addExtraFunctions(POSTGRE_FUNCTIONS_ENUM);
        addExtraFunctions(POSTGRE_FUNCTIONS_FORMATTING);
        addExtraFunctions(POSTGRE_FUNCTIONS_GEOMETRY);
        addExtraFunctions(POSTGRE_FUNCTIONS_INFO);
        addExtraFunctions(POSTGRE_FUNCTIONS_JSON);
        addExtraFunctions(POSTGRE_FUNCTIONS_LO);
        addExtraFunctions(POSTGRE_FUNCTIONS_MATH);
        addExtraFunctions(POSTGRE_FUNCTIONS_NETWROK);
        addExtraFunctions(POSTGRE_FUNCTIONS_RANGE);
        addExtraFunctions(POSTGRE_FUNCTIONS_SEQUENCE);
        addExtraFunctions(POSTGRE_FUNCTIONS_SRF);
        addExtraFunctions(POSTGRE_FUNCTIONS_STRING);
        addExtraFunctions(POSTGRE_FUNCTIONS_TEXT_SEARCH);
        addExtraFunctions(POSTGRE_FUNCTIONS_TRIGGER);
        addExtraFunctions(POSTGRE_FUNCTIONS_WINDOW);
        addExtraFunctions(POSTGRE_FUNCTIONS_XML);
        addExtraFunctions(POSTGRE_FUNCTIONS_BUILTIN);

        removeSQLKeyword("LENGTH");
        removeSQLKeyword("JSON");
        removeSQLKeyword("TEXT");
        removeSQLKeyword("FORMAT");
        removeSQLKeyword("WORK");

        if (dataSource instanceof PostgreDataSource) {
            serverExtension = ((PostgreDataSource) dataSource).getServerType();
            serverExtension.configureDialect(this);
        }

        // #12723 Redshift driver returns wrong infor about unquoted case
        setUnquotedIdentCase(DBPIdentifierCase.LOWER);
    }

    @NotNull
    @Override
    protected DBPIdentifierCase getDefaultIdentifiersCase() {
        return DBPIdentifierCase.LOWER;
    }

    @Override
    public void addKeywords(Collection<String> set, DBPKeywordType type) {
        super.addKeywords(set, type);
    }

    @NotNull
    @Override
    public String[] getExecuteKeywords() {
        return EXEC_KEYWORDS;
    }

    @Override
    public char getStringEscapeCharacter() {
        if (serverExtension != null && serverExtension.supportsBackslashStringEscape()) {
            return '\\';
        }
        return super.getStringEscapeCharacter();
    }

    @Override
    public int getCatalogUsage() {
        return SQLDialect.USAGE_DML;
    }

    @Override
    public int getSchemaUsage() {
        return SQLDialect.USAGE_ALL;
    }

    @NotNull
    @Override
    public String[] getParametersPrefixes() {
        return new String[]{"$"};
    }

    @NotNull
    @Override
    public MultiValueInsertMode getDefaultMultiValueInsertMode() {
        return MultiValueInsertMode.GROUP_ROWS;
    }

    @Override
    public String[][] getBlockBoundStrings() {
        return BLOCK_BOUND_KEYWORDS;
    }

    @Override
    public boolean validIdentifierStart(char c) {
        return super.validIdentifierStart(c) || c == '_';
    }

    @Override
    public String getCastedAttributeName(@NotNull DBSAttributeBase attribute, String attributeName) {
        // This method actually works for special data types like JSON and XML.
        // Because column names in the condition in a table without key must be also cast, as data in getTypeCast method.
        if (attribute instanceof DBSObject sAttr && !DBUtils.isPseudoAttribute(attribute)) {
            if (!CommonUtils.equalObjects(attributeName, attribute.getName())) {
                // Must use explicit attribute name
                attributeName = DBUtils.getQuotedIdentifier(sAttr.getDataSource(), attributeName);
            } else {
                attributeName = DBUtils.getObjectFullName(sAttr.getDataSource(), attribute, DBPEvaluationContext.DML);
            }
        }
        return getCastedString(attribute, attributeName, true, true);
    }

    @NotNull
    @Override
    public String getTypeCastClause(
        @NotNull DBSTypedObject attribute,
        @NotNull String expression,
        boolean isInCondition,
        boolean exprIsAttrRef
    ) {
        // Some data for some types of columns data types must be cast. It can be simple casting only with data type name like "::pg_class" or casting with fully qualified names for user defined types like "::schemaName.testType".
        // Or very special clauses with JSON and XML columns, when we have to cast both column data and column name to text.
        return getCastedString(attribute, expression, isInCondition, false);
    }

    private String getCastedString(@NotNull DBSTypedObject attribute, String string, boolean isInCondition, boolean castColumnName) {
        if (attribute instanceof DBSTypedObjectEx toEx) {
            DBSDataType dataType = toEx.getDataType();
            if (dataType instanceof PostgreDataType pdt) {
                String typeCasting = pdt.getConditionTypeCasting(isInCondition, castColumnName);
                if (CommonUtils.isNotEmpty(typeCasting)) {
                    return string + typeCasting;
                }
            }
        }
        return string;
    }

    @NotNull
    @Override
    public String escapeScriptValue(DBSTypedObject attribute, @NotNull Object value, @NotNull String strValue) {
        boolean isPgObject = serverExtension != null && serverExtension.isPGObject(value);
        if (isPgObject
            || PostgreConstants.TYPE_BIT.equals(attribute.getTypeName())
            || PostgreConstants.TYPE_INTERVAL.equals(attribute.getTypeName())
            || attribute.getTypeID() == Types.OTHER
            || attribute.getTypeID() == Types.ARRAY
            || attribute.getTypeID() == Types.STRUCT) {
            // TODO: we need to add value handlers for all PG data types.
            // For now we use workaround: represent objects as strings
            return '\'' + escapeString(strValue) + '\'';
        }
        if (CommonUtils.isNaN(value) || CommonUtils.isInfinite(value)) {
            // These special values should be quoted
            // https://www.postgresql.org/docs/current/datatype-numeric.html#DATATYPE-NUMERIC-DECIMAL
            return '\'' + String.valueOf(value) + '\'';
        }
        return super.escapeScriptValue(attribute, value, strValue);
    }

    @NotNull
    @Override
    public String[][] getStringQuoteStrings() {
        return PG_STRING_QUOTES;
    }

    @Override
    public boolean supportsAliasInSelect() {
        return true;
    }

    @Override
    public boolean supportsAliasInConditions() {
        return false;
    }

    @Override
    public boolean supportsTableDropCascade() {
        return true;
    }

    @Override
    public boolean supportsColumnAutoIncrement() {
        return false;
    }

    @Override
    public boolean supportsCommentQuery() {
        return true;
    }

    @Override
    public boolean supportsNestedComments() {
        return true;
    }

    @Nullable
    @Override
    public SQLExpressionFormatter getCaseInsensitiveExpressionFormatter(@NotNull DBCLogicalOperator operator) {
        if (operator == DBCLogicalOperator.LIKE) {
            return (left, right) -> left + " ILIKE " + right;
        }
        return super.getCaseInsensitiveExpressionFormatter(operator);
    }

    @NotNull
    @Override
    public DBDBinaryFormatter getNativeBinaryFormatter() {
        return PostgreBinaryFormatter.INSTANCE;
    }

    @Override
    protected void loadDataTypesFromDatabase(JDBCDataSource dataSource) {
        super.loadDataTypesFromDatabase(dataSource);
        addDataTypes(PostgreConstants.DATA_TYPE_ALIASES.keySet());
        addDataTypes(Arrays.asList(POSTGRE_EXTRA_TYPES));
    }

    @NotNull
    @Override
    public String[] getNonTransactionKeywords() {
        return POSTGRE_NON_TRANSACTIONAL_KEYWORDS;
    }

    @Override
    protected boolean isStoredProcedureCallIncludesOutParameters() {
        return false;
    }

    @NotNull
    @Override
    public TPRule[] extendRules(@Nullable DBPDataSourceContainer dataSource, @NotNull RulePosition position) {
        if (position == RulePosition.INITIAL || position == RulePosition.PARTITION) {
            boolean ddTagDefault = DBWorkbench.getPlatform().getPreferenceStore().getBoolean(PostgreConstants.PROP_DD_TAG_STRING);
            boolean ddTagIsString = dataSource == null
                ? ddTagDefault
                : CommonUtils.getBoolean(dataSource.getActualConnectionConfiguration().getProviderProperty(PostgreConstants.PROP_DD_TAG_STRING), ddTagDefault);

            boolean ddPlainDefault = DBWorkbench.getPlatform().getPreferenceStore().getBoolean(PostgreConstants.PROP_DD_PLAIN_STRING);
            boolean ddPlainIsString = dataSource == null
                ? ddPlainDefault
                : CommonUtils.getBoolean(dataSource.getActualConnectionConfiguration().getProviderProperty(PostgreConstants.PROP_DD_PLAIN_STRING), ddPlainDefault);

            return new TPRule[] {
                new SQLDollarQuoteRule(position == RulePosition.PARTITION, true, ddTagIsString, ddPlainIsString),
                new PostgreEscapeStringRule()
            };
        }
        return new TPRule[0];
    }

    @Override
    public boolean supportsInsertAllDefaultValuesStatement() {
        return true;
    }

    @Override
    public String getColumnTypeModifiers(@NotNull DBPDataSource dataSource, @NotNull DBSTypedObject column, @NotNull String typeName, @NotNull DBPDataKind dataKind) {
        String lowerTypeName = typeName.toLowerCase(Locale.ENGLISH);
        if (lowerTypeName.equals(PostgreConstants.TYPE_TEXT) || lowerTypeName.equals(PostgreConstants.TYPE_BYTEA)) {
            // These types have no length. The generic code adds the source length (SQL Server ntext, Oracle BLOB),
            // which made CREATE TABLE fail with text(10485760) or bytea(4000).
            return null;
        }
        if (lowerTypeName.equals(TYPE_NUMERIC) || lowerTypeName.equals(TYPE_DECIMAL)) {
            // A numeric without precision (a column or an expression such as price * 2 in a query result) is reported
            // with precision 0 and display size 131089, which the generic code takes as the precision: numeric(131089)
            // failed as a new column ("must be between 1 and 1000")
            long precision = CommonUtils.toInt(column.getPrecision());
            if (precision == 0) {
                precision = column.getMaxLength();
            }
            if (precision > NUMERIC_MAX_PRECISION) {
                return null;
            }
        }
        return super.getColumnTypeModifiers(dataSource, column, typeName, dataKind);
    }

    @Override
    public String convertExternalDataType(@NotNull SQLDialect sourceDialect, @NotNull DBSTypedObject sourceTypedObject, @Nullable DBPDataTypeProvider targetTypeProvider) {
        String typeName = sourceTypedObject.getTypeName();
        if (typeName == null) {
            return null;
        }
        String externalTypeName = typeName.toLowerCase(Locale.ENGLISH);
        String localDataType = null, dataTypeModifies = null;

        switch (externalTypeName) {
            case "xml":
            case "xmltype":
            case "sys.xmltype":
                localDataType = "xml";
                break;
            case "varchar2":
            case "nchar":
            case "nvarchar":
                localDataType = "varchar";
                if (sourceTypedObject.getMaxLength() > 0 &&
                    sourceTypedObject.getMaxLength() != Integer.MAX_VALUE &&
                    sourceTypedObject.getMaxLength() != Long.MAX_VALUE)
                {
                    dataTypeModifies = String.valueOf(sourceTypedObject.getMaxLength());
                }
                break;
            case "json":
            case "jsonb":
                localDataType = "jsonb";
                break;
            case "geometry":
            case "sdo_geometry":
            case "mdsys.sdo_geometry":
                localDataType = "geometry";
                break;
            case "number":
                localDataType = "numeric";
                if (sourceTypedObject.getPrecision() != null) {
                    dataTypeModifies = sourceTypedObject.getPrecision().toString();
                    if (sourceTypedObject.getScale() != null) {
                        dataTypeModifies += "," + sourceTypedObject.getScale();
                    }
                }
                break;
            case "uniqueidentifier":
            case "uuid":
                // MariaDB uuid is a character type, so the name search skipped PostgreSQL uuid (another data kind)
                // and created varchar(36)
                localDataType = PostgreConstants.TYPE_UUID;
                break;
            case "clob":
            case "nclob":
                // Character LOBs are CONTENT, which the generic search maps to bytea
                localDataType = PostgreConstants.TYPE_TEXT;
                break;
        }
        if (localDataType == null && sourceTypedObject.getTypeID() == Types.BIT && CommonUtils.toInt(sourceTypedObject.getPrecision()) <= 1
            && !(sourceDialect instanceof PostgreDialect)) {
            // A one-bit BIT (SQL Server bit, MySQL BIT(1)) is a true/false value; the name search found PostgreSQL bit,
            // which is a bit string
            localDataType = PostgreConstants.TYPE_BOOLEAN;
        }
        if (localDataType == null && DBStructUtils.isTimestamp(sourceTypedObject)) {
            // Oracle DATE (has a time part) and SQL Server datetimeoffset are not known by name
            localDataType = DBStructUtils.isTimestampWithTimeZone(sourceTypedObject) ?
                PostgreConstants.TYPE_TIMESTAMPTZ : PostgreConstants.TYPE_TIMESTAMP;
        }
        if (localDataType == null) {
            return null;
        }
        if (targetTypeProvider == null) {
            return localDataType;
        } else {
            DBSDataType dataType = targetTypeProvider.getLocalDataType(localDataType);
            if (dataType == null) {
                return null;
            }
            String targetTypeName = DBUtils.getObjectFullName(dataType, DBPEvaluationContext.DDL);
            if (dataTypeModifies != null) {
                targetTypeName += "(" + dataTypeModifies + ")";
            }
            return targetTypeName;
        }
    }

    @Nullable
    @Override
    public String getAutoIncrementKeyword() {
        return AUTO_INCREMENT_KEYWORD;
    }

    @Override
    public boolean supportsCreateIfExists() {
        return true;
    }

    @NotNull
    @Override
    public String getTimestampDataType() {
        return PostgreConstants.TYPE_TIMESTAMP;
    }

    @NotNull
    @Override
    public String getBigIntegerType() {
        return PostgreConstants.TYPE_BIGINT;
    }

    @NotNull
    @Override
    public String getClobDataType() {
        return PostgreConstants.TYPE_TEXT;
    }

    @NotNull
    @Override
    public String getBlobDataType() {
        return PostgreConstants.TYPE_BYTEA;
    }

    @NotNull
    @Override
    public String getUuidDataType() {
        return PostgreConstants.TYPE_UUID;
    }

    @NotNull
    @Override
    public String getBooleanDataType() {
        return PostgreConstants.TYPE_BOOLEAN;
    }

    @NotNull
    @Override
    public String getAlterColumnOperation() {
        return PostgreConstants.OPERATION_ALTER;
    }

    @Override
    public boolean supportsNoActionIndex() {
        return true;
    }

    @Override
    public boolean supportsAlterColumnSet() {
        return true;
    }

    @Override
    public boolean supportsAlterHasColumn() {
        return true;
    }

    @NotNull
    @Override
    public String getSchemaExistQuery(@NotNull String schemaName) {
        return "SELECT 1 FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = " + getQuotedString(schemaName);
    }

    @NotNull
    @Override
    public String getCreateSchemaQuery(@NotNull String schemaName) {
        return "CREATE SCHEMA " + schemaName;
    }

    @Override
    protected String getStoredProcedureCallInitialClause(DBSProcedure proc) {
        String[] executeKeywords = getExecuteKeywords();
        String qualifiedName = proc.getFullyQualifiedName(DBPEvaluationContext.DML);
        if (proc.getProcedureType() == DBSProcedureType.FUNCTION || ArrayUtils.isEmpty(executeKeywords)) {
            return SQLConstants.KEYWORD_SELECT + " * FROM " + qualifiedName;
        } else {
            return executeKeywords[0] + " " + qualifiedName;
        }
    }

    @Override
    public boolean isEscapeBackslash() {
        return serverExtension != null && serverExtension.supportsBackslashStringEscape();
    }

    @Override
    public void generateStoredProcedureCall(
            StringBuilder sql,
            DBSProcedure proc,
            Collection<? extends DBSProcedureParameter> parameters,
            boolean castParams
    ) {
        DBPPreferenceStore prefStore;
        DBPDataSource dataSource = proc.getDataSource();
        if (dataSource != null) {
            prefStore = dataSource.getContainer().getPreferenceStore();
        } else {
            prefStore = DBWorkbench.getPlatform().getPreferenceStore();
        }
        String namedParameterPrefix = prefStore.getString(ModelPreferences.SQL_NAMED_PARAMETERS_PREFIX);
        boolean useBrackets = useBracketsForExec(proc);
        if (useBrackets) {
            sql.append("{ ");
        }
        sql.append(getStoredProcedureCallInitialClause(proc)).append("(");

        List<DBSProcedureParameter> inParameters = new ArrayList<>();
        if (parameters != null) {
            inParameters.addAll(parameters);
        }
        if (!inParameters.isEmpty()) {
            boolean isProcedure = DBSProcedureType.PROCEDURE.equals(proc.getProcedureType());
            processParameters(sql, castParams, inParameters, namedParameterPrefix, isProcedure);
        }

        sql.append(")");
        String callEndClause = getProcedureCallEndClause(proc);
        if (!CommonUtils.isEmpty(callEndClause)) {
            sql.append(" ").append(callEndClause);
        }
        if (!useBrackets) {
            sql.append(";");
        } else {
            sql.append(" }");
        }
        sql.append("\n\n");
    }

    private void processParameters(StringBuilder sql, boolean castParams, List<DBSProcedureParameter> inParameters,
                                   String namedParameterPrefix, boolean isProcedure) {
        StringJoiner parametersJoiner = new StringJoiner(", ");

        for (DBSProcedureParameter parameter : inParameters) {
            String typeName = parameter.getParameterType().getFullTypeName();
            switch (parameter.getParameterKind()) {
                case INOUT:
                case IN:
                    if (castParams) {
                        parametersJoiner.add("cast(" + namedParameterPrefix + CommonUtils.escapeIdentifier(parameter.getName())
                                + " as " + typeName + ")");
                    } else {
                        parametersJoiner.add(namedParameterPrefix + CommonUtils.escapeIdentifier(parameter.getName()));
                    }
                    break;
                case OUT:
                    // PostgreSQL requires OUT parameters to be passed as NULL in procedure calls
                    if (isProcedure) {
                        parametersJoiner.add(SQLConstants.NULL_VALUE);
                    }
                    break;
                case RETURN:
                    continue;
                default:
                    if (isStoredProcedureCallIncludesOutParameters()) {
                        if (castParams) {
                            parametersJoiner.add("cast(? as " + typeName + ")");
                        } else {
                            parametersJoiner.add("?");
                        }
                    }
                    break;
            }
        }

        sql.append(parametersJoiner);
    }
}
