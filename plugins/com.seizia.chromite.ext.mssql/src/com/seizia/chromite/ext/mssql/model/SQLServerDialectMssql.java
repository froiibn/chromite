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
package com.seizia.chromite.ext.mssql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.SQLServerConstants;
import com.seizia.chromite.ext.mssql.SQLServerUtils;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataTypeProvider;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCServerTransaction;
import com.seizia.chromite.model.exec.jdbc.JDBCTransactionProbe;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLDialectFeatures;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.model.struct.DBStructUtils;
import org.jkiss.utils.CommonUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * SQL Server dialect
 *
 * 설계: {@link #convertExternalDataType} maps types of other databases for data transfer into new tables.
 * Character data of other databases is Unicode (PostgreSQL, MySQL utf8mb4, Oracle AL32UTF8) while varchar, char and
 * text follow the code page of the column collation (SQL_Latin1_General_CP1_CI_AS by default), so Korean text turned
 * into '?'. Character types therefore become nvarchar/nchar, as the SQL Server Import and Export Wizard does.
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: data transfer type mapping — Unicode character types, uuid, JSON and LOBs, timestamps
 *   (datetime2/datetimeoffset instead of datetime), signed tinyint.
 * - 2026-11-08 Chromite: {@link #supportsMergeStatement} (MERGE since SQL Server 2008) for Generate SQL from result rows
 *   (docs/chromite/swing-result-generate-sql.md).
 * - 2026-11-08 Chromite: {@link #probeTransaction}: what the session's transaction holds, asked from the server
 *   (docs/chromite/idle-transactions.md "SQL Server").
 */
public class SQLServerDialectMssql extends SQLServerDialectBase implements JDBCTransactionProbe {

    // Longest nvarchar(n)/nchar(n); longer or unbounded character data needs nvarchar(max)
    private static final int MAX_NCHAR_LENGTH = 4000;
    private static final String MAX_LENGTH_MODIFIER = "(MAX)";

    // One batch, so that the transaction count is read before anything else: in manual commit (IMPLICIT_TRANSACTIONS ON)
    // a SELECT from a DMV starts a transaction. The DMVs need VIEW SERVER STATE (2022: VIEW SERVER PERFORMANCE STATE);
    // without it the batch does not touch them, because an error would roll back a transaction under XACT_ABORT ON.
    // Not counted: schema stability locks on metadata (a query compiled in the transaction keeps Sch-S on the database
    // principal that owns the schema until it ends); they only block changing that principal or schema itself.
    private static final String TRANSACTION_PROBE =
        "IF @@TRANCOUNT = 0 OR XACT_STATE() = -1" //$NON-NLS-1$
            + " OR (ISNULL(HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW SERVER STATE'), 0) = 0" //$NON-NLS-1$
            + " AND ISNULL(HAS_PERMS_BY_NAME(NULL, NULL, 'VIEW SERVER PERFORMANCE STATE'), 0) = 0)\n" //$NON-NLS-1$
            + " SELECT @@TRANCOUNT, XACT_STATE(), @@OPTIONS & 2, CAST(NULL AS BIGINT), CAST(NULL AS INT), CAST(NULL AS BIT)\n" //$NON-NLS-1$
            + "ELSE SELECT @@TRANCOUNT, XACT_STATE(), @@OPTIONS & 2," //$NON-NLS-1$
            + " (SELECT SUM(d.database_transaction_log_record_count) FROM sys.dm_tran_database_transactions d" //$NON-NLS-1$
            + " JOIN sys.dm_tran_current_transaction c ON d.transaction_id = c.transaction_id)," //$NON-NLS-1$
            + " (SELECT COUNT(*) FROM sys.dm_tran_locks WHERE request_session_id = @@SPID" //$NON-NLS-1$
            + " AND request_owner_type = 'TRANSACTION' AND resource_type <> 'DATABASE'" //$NON-NLS-1$
            + " AND NOT (resource_type = 'METADATA' AND request_mode = 'Sch-S'))," //$NON-NLS-1$
            + " (SELECT transaction_is_snapshot FROM sys.dm_tran_current_transaction)"; //$NON-NLS-1$

    public SQLServerDialectMssql() {
        super("SQLServer", "sqlserver");
    }

    /**
     * The transaction count, the implicit transactions option and, with the permission, the transaction's log records,
     * its locks (every session holds a shared DATABASE lock, which is not the transaction's) and whether it is a SNAPSHOT
     * transaction. Under IMPLICIT_TRANSACTIONS (manual commit) a plain READ COMMITTED SELECT leaves a transaction that
     * holds nothing; an explicit BEGIN TRAN makes the count 2 there, and any count in auto-commit mode is explicit.
     */
    @Nullable
    @Override
    public DBCServerTransaction probeTransaction(@NotNull DBPDataSource dataSource, @NotNull Connection connection) throws SQLException {
        DBPDriver driver = dataSource.getContainer().getDriver();
        if (!(dataSource instanceof SQLServerDataSource) || SQLServerUtils.isDriverBabelfish(driver) || SQLServerUtils.isDriverFabric(driver)) {
            // Sybase, Babelfish (a PostgreSQL server) and Fabric do not have these functions and views
            return null;
        }
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(TIMEOUT_SECONDS);
            try (ResultSet rs = statement.executeQuery(TRANSACTION_PROBE)) {
                if (!rs.next()) {
                    return DBCServerTransaction.FAILED;
                }
                int count = rs.getInt(1);
                if (count == 0) {
                    return DBCServerTransaction.NONE;
                }
                Set<DBCServerTransaction.Hold> holds = EnumSet.noneOf(DBCServerTransaction.Hold.class);
                if (rs.getInt(2) == -1) {
                    holds.add(DBCServerTransaction.Hold.ABORTED);
                }
                if (rs.getInt(3) == 0 || count > 1) {
                    holds.add(DBCServerTransaction.Hold.EXPLICIT);
                }
                long logRecords = rs.getLong(4);
                int locks = rs.getInt(5);
                if (rs.wasNull()) {
                    // No permission on the DMVs (or an uncommittable transaction)
                    return holds.isEmpty() ? DBCServerTransaction.UNVERIFIED : DBCServerTransaction.holding(holds);
                }
                if (logRecords > 0) {
                    holds.add(DBCServerTransaction.Hold.CHANGES);
                }
                if (locks > 0) {
                    holds.add(DBCServerTransaction.Hold.LOCKS);
                }
                if (rs.getBoolean(6)) {
                    holds.add(DBCServerTransaction.Hold.SNAPSHOT);
                }
                return DBCServerTransaction.holding(holds);
            }
        }
    }

    @NotNull
    @Override
    public SQLDialectFeatures getDialectFeatures() {
        return DialectFeatures.INSTANCE;
    }

    @Override
    public boolean supportsMergeStatement() {
        return true;
    }

    @Nullable
    @Override
    public String convertExternalDataType(
        @NotNull SQLDialect sourceDialect,
        @NotNull DBSTypedObject sourceTypedObject,
        @Nullable DBPDataTypeProvider targetTypeProvider
    ) {
        if (sourceDialect instanceof SQLServerDialectBase) {
            // Types of SQL Server (and Sybase) keep their own names and code pages
            return super.convertExternalDataType(sourceDialect, sourceTypedObject, targetTypeProvider);
        }
        String typeName = SQLUtils.stripColumnTypeModifiers(CommonUtils.notEmpty(sourceTypedObject.getTypeName())).toLowerCase(Locale.ENGLISH);
        int typeID = sourceTypedObject.getTypeID();
        switch (typeName) {
            case "uuid":
            case SQLServerConstants.TYPE_UNIQUEIDENTIFIER:
                return SQLServerConstants.TYPE_UNIQUEIDENTIFIER;
            case "json":
            case "jsonb":
            case "clob":
            case "nclob":
                // JSON text and character LOBs are CONTENT, which the generic search maps to varbinary
                return SQLServerConstants.TYPE_NVARCHAR + MAX_LENGTH_MODIFIER;
            case "blob":
                // Oracle reports the LOB locator length (4000) as the BLOB length
                return SQLServerConstants.TYPE_VARBINARY + MAX_LENGTH_MODIFIER;
        }
        if (typeID == Types.CLOB || typeID == Types.NCLOB) {
            return SQLServerConstants.TYPE_NVARCHAR + MAX_LENGTH_MODIFIER;
        }
        if (typeID == Types.BLOB) {
            return SQLServerConstants.TYPE_VARBINARY + MAX_LENGTH_MODIFIER;
        }
        if (DBStructUtils.isTimestamp(sourceTypedObject)) {
            // datetime has 1/300 s precision and starts in 1753; datetime2 holds every timestamp value
            return DBStructUtils.isTimestampWithTimeZone(sourceTypedObject) ?
                SQLServerConstants.TYPE_DATETIMEOFFSET : SQLServerConstants.TYPE_DATETIME2;
        }
        if (typeID == Types.TINYINT && !CommonUtils.notEmpty(sourceTypedObject.getFullTypeName()).toLowerCase(Locale.ENGLISH).contains("unsigned")) {
            // SQL Server tinyint is unsigned (0..255), a signed TINYINT of other databases goes down to -128
            return SQLServerConstants.TYPE_SMALLINT;
        }
        if (sourceTypedObject.getDataKind() == DBPDataKind.STRING) {
            boolean fixedLength = typeID == Types.CHAR || typeID == Types.NCHAR
                || typeName.equals(SQLServerConstants.TYPE_CHAR) || typeName.equals(SQLServerConstants.TYPE_NCHAR)
                || typeName.equals("bpchar") || typeName.equals("character");
            long maxLength = sourceTypedObject.getMaxLength();
            if (maxLength > 0 && maxLength <= MAX_NCHAR_LENGTH) {
                return (fixedLength ? SQLServerConstants.TYPE_NCHAR : SQLServerConstants.TYPE_NVARCHAR) + "(" + maxLength + ")";
            }
            return SQLServerConstants.TYPE_NVARCHAR + MAX_LENGTH_MODIFIER;
        }
        return super.convertExternalDataType(sourceDialect, sourceTypedObject, targetTypeProvider);
    }

    private static class DialectFeatures extends SQLServerDialectBase.DialectFeatures {
        private static final DialectFeatures INSTANCE = new DialectFeatures();

        @NotNull
        @Override
        public Set<String> getSignatureModifierKeywords() {
            return Set.of("OUT", "OUTPUT", "READONLY");
        }

        @NotNull
        @Override
        public ProceduralSyntaxFamily getProceduralSyntaxFamily() {
            return ProceduralSyntaxFamily.TSQL;
        }

        /** T-SQL variables declared inside a procedure remain visible across nested BEGIN/END blocks.
         *
         * @see <a href="https://learn.microsoft.com/en-us/sql/t-sql/language-elements/declare-local-variable-transact-sql">DECLARE</a>
         */
        @NotNull
        @Override
        public LocalVariableVisibility getLocalVariableVisibility(@NotNull LocalVariableKind kind) {
            return LocalVariableVisibility.PROGRAM;
        }
    }

}
