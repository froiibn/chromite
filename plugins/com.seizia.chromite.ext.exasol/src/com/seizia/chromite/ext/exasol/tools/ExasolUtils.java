/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2016-2016 Karl Griesser (fullref@gmail.com)
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.exasol.tools;

import org.jkiss.code.NotNull;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.exasol.ExasolConstants;
import com.seizia.chromite.ext.exasol.model.*;
import com.seizia.chromite.ext.exasol.model.security.ExasolTableObjectType;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAttributeRef;
import org.jkiss.utils.CommonUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DB2 Utils
 *
 * 설계: 암호는 {@link #quotePassword}로만 문장에 넣는다(사용자 CREATE·ALTER, 암호 바꾸기). 식별자용
 * {@code DBUtils.getQuotedIdentifier}는 이미 따옴표로 싸인 글을 그대로 두고 이름 규칙에 맞는 글은 따옴표 없이 둔다.
 *
 * 변경 이력:
 * - 2026-11-08 {@link #quotePassword} 추가(ExasolUserManager의 것을 옮김), {@link #getConnectionDdl}의 사용자 이름 따옴표 처리
 *   (docs/chromite/password-handling.md)
 *
 * @author Karl Griesser
 */
public class ExasolUtils {
    /**
     * A comment that triggers <a href="https://docs.exasol.com/db/latest/database_concepts/snapshot_mode.htm">the Snapshot Mode</a>.
     * This mode is helpful for metadata queries as it allows avoiding transaction conflicts.
     * Prepend the comment to an SQL query if you want to enable the mode for the query.
     */
    public static final String SNAPSHOT_EXEC = "/*snapshot execution*/";

    // select columns of tables
    private static final String TABLE_QUERY_COLUMNS = "/*snapshot execution*/ SELECT * FROM EXA_ALL_COLUMNS WHERE COLUMN_SCHEMA='%s' AND COLUMN_TABLE='%s' ORDER BY COLUMN_ORDINAL_POSITION";

    private static final Log log = Log.getLog(ExasolUtils.class);

    // double single quotes for sql literals  
    public static String quoteString(String input) {
        return input.replaceAll("'", "''");
    }

    /**
     * A password as Exasol's {@code IDENTIFIED BY} and {@code REPLACE} take it: always in double quotes (a delimited
     * identifier) with inner double quotes doubled, so every character is kept as typed.
     */
    @NotNull
    public static String quotePassword(@NotNull String password) {
        return "\"" + password.replace("\"", "\"\"") + "\"";
    }

    public static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    public static String generateDDLforTable(DBRProgressMonitor monitor, ExasolDataSource dataSource,
                                             ExasolTable exasolTable) throws DBException {

        StringBuilder ddlOutput = new StringBuilder();
        ddlOutput.append("CREATE TABLE ").append(exasolTable.getFullyQualifiedName(DBPEvaluationContext.DDL)).append(" (");

        try (JDBCSession session = DBUtils.openMetaSession(monitor, exasolTable, "Get Table DDL")) {
            try (JDBCStatement dbStat = session.createStatement()) {


                JDBCResultSet rs = dbStat.executeQuery(String.format(TABLE_QUERY_COLUMNS, quoteString(exasolTable.getSchema().getName()), quoteString(exasolTable.getName())));

                // column infos
                List<String> columns = new ArrayList<>();

                // distribution key infos
                List<String> distKey = new ArrayList<>();

                while (rs.next()) {

                    StringBuilder columnString = new StringBuilder();

                    // double quotation mark for column as the name could be a
                    // reserved word
                    columnString.append("\n\t\t").append(DBUtils.getQuotedIdentifier(dataSource, CommonUtils.notEmpty(rs.getString("COLUMN_NAME")))).
                            append(" ").append(rs.getString("COLUMN_TYPE"));

                    // has default value?
                    String columnDefault = rs.getString("COLUMN_DEFAULT");
                    if (columnDefault != null)
                        columnString.append(" DEFAULT ").append(columnDefault);

                    // has identity
                    BigDecimal bigDecimal = rs.getBigDecimal("COLUMN_IDENTITY");
                    if (bigDecimal != null)
                        columnString.append(" IDENTITY ").append(bigDecimal.toString());

                    // has identity
                    if (!rs.getBoolean("COLUMN_IS_NULLABLE"))
                        columnString.append(" NOT NULL");

                    // comment
                    String columnComment = rs.getString("COLUMN_COMMENT");
                    if (columnComment != null)
                        // replace ' to double ' -> escape for SQL
                        columnString.append(" COMMENT IS '").append(columnComment.replaceAll("'", "''")).append("'");

                    // if distkey add column to distkey
                    if (rs.getBoolean("COLUMN_IS_DISTRIBUTION_KEY"))
                        distKey.add(rs.getString("COLUMN_NAME"));

                    columns.add(columnString.toString());
                }
                ddlOutput.append(CommonUtils.joinStrings(",", columns));

                // do we have a distkey?
                if (distKey.size() > 0) {
                    ddlOutput.append(",\n\t\t DISTRIBUTE BY ").append(CommonUtils.joinStrings(",", distKey));
                }

                ddlOutput.append("\n);\n");
            }
            
            //partitioning
            ddlOutput.append(getPartitionDdl(exasolTable, monitor));
            //ddlOutput.append(";\n"); //partition expression has ; already

            //primary key
            Collection<ExasolTableUniqueKey> pks = exasolTable.getConstraints(monitor);
            if (pks != null && pks.size() > 0) {

                //get only first as there is only 1 primary key
                ExasolTableUniqueKey pk;
                pk = pks.iterator().next();
                ddlOutput.append("\n").append(getPKDdl(pk, monitor)).append(";\n");
            }

            //foreign key
            Collection<ExasolTableForeignKey> fks = exasolTable.getAssociations(monitor);
            if (fks != null && fks.size() > 0) {

                //look keys
                for (ExasolTableForeignKey fk : fks) {
                    ddlOutput.append("\n").append(getFKDdl(fk, monitor)).append(";\n");
                }
            }

            return ddlOutput.toString();

        } catch (SQLException e) {
            throw new DBDatabaseException(e, dataSource);
        } finally {
            monitor.done();
        }

    }
    
    public static String getPartitionDdl(ExasolTable table, DBRProgressMonitor monitor) throws DBException {
    	
    	if (table.getPartitions(monitor).size() == 0)
    		return "";
    	Collection<String> cols = table.getPartitions(monitor).stream()
    			.sorted(Comparator.comparing(ExasolTablePartitionColumn::getOrdinalPosition))
    			.map(pc -> DBUtils.getQuotedIdentifier(pc))
    			.collect(Collectors.toCollection(ArrayList::new));
    	
    	String colList = String.join(",", cols);
    	
    	return String.format
    			(
    					"ALTER TABLE %s PARTITION BY %s;\n",
    					DBUtils.getObjectFullName(table, DBPEvaluationContext.DDL),
    					colList
    			);
    }

    public static String getFKDdl(ExasolTableForeignKey fk, DBRProgressMonitor monitor) throws DBException {
        ExasolTable exasolTable = fk.getTable();
        ArrayList<String> columns;
        columns = new ArrayList<>();
        ArrayList<String> refColumns = new ArrayList<>();
        for (DBSEntityAttributeRef c : fk.getAttributeReferences(monitor)) {
            columns.add(DBUtils.getQuotedIdentifier(c.getAttribute()));
        }

        for (DBSEntityAttributeRef c : fk.getReferencedConstraint().getAttributeReferences(monitor)) {
            refColumns.add(DBUtils.getQuotedIdentifier(c.getAttribute()));
        }
        String fk_enabled = " DISABLE";

        if (fk.getEnabled())
            fk_enabled = " ENABLE";

        return "ALTER TABLE " + DBUtils.getObjectFullName(exasolTable, DBPEvaluationContext.DDL) +
                " ADD CONSTRAINT " + DBUtils.getQuotedIdentifier(fk) +
                " FOREIGN KEY (" + CommonUtils.joinStrings(",", columns) +
                ") REFERENCES " + DBUtils.getObjectFullName(fk.getAssociatedEntity(), DBPEvaluationContext.DDL) + " (" +
                CommonUtils.joinStrings(",", refColumns) + ")" + fk_enabled;

    }

    public static String getPKDdl(ExasolTableUniqueKey pk, DBRProgressMonitor monitor) throws DBException {
        ExasolTable exasolTable = pk.getTable();
        ArrayList<String> columns = new ArrayList<>();
        for (DBSEntityAttributeRef c : pk.getAttributeReferences(monitor)) {
            columns.add("\"" + c.getAttribute().getName() + "\"");
        }
        return "ALTER TABLE " + DBUtils.getObjectFullName(exasolTable, DBPEvaluationContext.DDL) + " ADD CONSTRAINT " + DBUtils.getQuotedIdentifier(pk) + " PRIMARY KEY (" + CommonUtils.joinStrings(",", columns) + ") " + (pk.getEnabled() ? ExasolConstants.KEYWORD_ENABLE : ExasolConstants.KEYWORD_DISABLE);
    }

    public static String getConnectionDdl(ExasolConnection con, DBRProgressMonitor monitor) throws DBException {

        String userInfo = "";

        if (con.getUserName() != null) {
            userInfo = " USER '" + quoteString(con.getUserName()) + "' IDENTIFIED BY '<password>' ";
        }


        return "CREATE CONNECTION \"" + con.getName() + "\" to '" + con.getConnectionString() + "'" + userInfo + ";";
    }

    private ExasolUtils() {
        // Pure utility class, no instanciation allowed
    }


    public static ExasolTable findTableBySchemaNameAndName(
            DBRProgressMonitor monitor, ExasolDataSource dataSource,
            String exasolSchemaName, String exasolTableName) throws DBException {
        ExasolSchema exasolSchema = dataSource.getSchema(monitor, exasolSchemaName);
        if (exasolSchema == null) {
            return null;
        }
        return exasolSchema.getTable(monitor, exasolTableName);
    }


    public static String generateDDLforSchema(DBRProgressMonitor monitor,
                                              ExasolSchema exasolSchema) {
        return "CREATE SCHEMA " + exasolSchema.getName() + ";\n"
                + "ALTER SCHEMA " + exasolSchema.getName() + " CHANGE OWNER " + exasolSchema.getOwner() + ";\n";
    }

    public static ExasolTableObjectType getTableObjectType(String objectType) {
        try {
            return ExasolTableObjectType.valueOf(objectType);
        } catch (Exception e) {
            log.error("Unsupported object table type: " + objectType);
            return ExasolTableObjectType.TABLE;
        }
    }
}
