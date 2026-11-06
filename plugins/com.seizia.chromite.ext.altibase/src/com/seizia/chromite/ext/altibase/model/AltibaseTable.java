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
package com.seizia.chromite.ext.altibase.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.altibase.AltibaseConstants;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericTable;
import com.seizia.chromite.ext.generic.model.GenericTableColumn;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBPObjectStatistics;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDPseudoAttribute;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;
import com.seizia.chromite.model.meta.Association;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSTableStorageKind;
import com.seizia.chromite.model.struct.rdb.DBSTableWithStorageKind;
import org.jkiss.utils.ByteNumberFormat;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

/**
 * Altibase table.
 *
 * 설계: 테이블 목록 질의({@link AltibaseMetaModel#prepareTableLoadStatement})가 테이블스페이스 이름, 파티션 여부,
 * 테이블스페이스 종류를 함께 읽어 오므로 생성자에서 받아 둔다. 그래서 탐색기가 테이블마다 저장 방식(메모리/디스크/
 * 휘발성)을 그려도 추가 질의가 없고, {@link #getTablespace}도 목록에서 온 테이블은 질의하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 저장 방식({@link DBSTableWithStorageKind}) 추가, 테이블스페이스와 파티션 여부를 목록 질의에서 받음
 *   (docs/chromite/altibase.md)
 */
public class AltibaseTable extends GenericTable implements DBPNamedObject2, DBPObjectStatistics, DBSTableWithStorageKind {

    private static final Log log = Log.getLog(AltibaseTable.class);
    
    // Extra columns of the table list query (AltibaseMetaModel.prepareTableLoadStatement)
    static final String COLUMN_TBS_NAME = "TBS_NAME";
    static final String COLUMN_IS_PARTITIONED = "IS_PARTITIONED";
    static final String COLUMN_TBS_TYPE = "TBS_TYPE";
    static final String COLUMN_PARTITION_TBS_TYPE_MIN = "PARTITION_TBS_TYPE_MIN";
    static final String COLUMN_PARTITION_TBS_TYPE_MAX = "PARTITION_TBS_TYPE_MAX";

    private transient volatile Long[] tableSize;
    private static final int SIZE_IDX_MEM = 0;
    private static final int SIZE_IDX_DISK = 1;
    
    private String tablespace;
    private boolean partitioned;
    @Nullable
    private DBSTableStorageKind storageKind;
    
    private final TablePrivCache tablePrivCache = new TablePrivCache();
    
    public AltibaseTable(GenericStructContainer container, String tableName, String tableType, JDBCResultSet dbResult) {
        super(container, tableName, tableType, dbResult);
        if (dbResult != null) {
            tablespace = JDBCUtils.safeGetString(dbResult, COLUMN_TBS_NAME);
            partitioned = JDBCUtils.safeGetBoolean(dbResult, COLUMN_IS_PARTITIONED, AltibaseConstants.RESULT_T_VALUE);
            storageKind = readStorageKind(dbResult, partitioned);
        }
    }

    /**
     * A partitioned table keeps its rows in the tablespaces of its partitions, not in the tablespace of the table, and
     * each partition may use another one (hybrid partitioned table). The list query gives the lowest and the highest
     * V$TABLESPACES.TYPE of the partitions: the type codes are grouped by kind (memory 0-2, disk 3-7, volatile 8),
     * so both have the same kind only when all partitions have it.
     */
    @Nullable
    private static DBSTableStorageKind readStorageKind(@NotNull JDBCResultSet dbResult, boolean partitioned) {
        if (!partitioned) {
            return getStorageKind(JDBCUtils.safeGetInteger(dbResult, COLUMN_TBS_TYPE));
        }
        DBSTableStorageKind lowest = getStorageKind(JDBCUtils.safeGetInteger(dbResult, COLUMN_PARTITION_TBS_TYPE_MIN));
        DBSTableStorageKind highest = getStorageKind(JDBCUtils.safeGetInteger(dbResult, COLUMN_PARTITION_TBS_TYPE_MAX));
        if (lowest == null || highest == null) {
            return null;
        }
        return lowest == highest ? lowest : DBSTableStorageKind.HYBRID;
    }

    @Nullable
    private static DBSTableStorageKind getStorageKind(@Nullable Integer tablespaceType) {
        return tablespaceType == null ? null : AltibaseTablespace.TbsType.getTbsTypeByIdx(tablespaceType).getStorageKind();
    }

    @Override
    protected boolean isTruncateSupported() {
        return true;
    }

    /*
     * In order to use a conversion function for geometry type.
     */
    @Override
    protected void appendSelectSource(
        DBRProgressMonitor monitor,
        StringBuilder query,
        String tableAlias,
        DBDPseudoAttribute rowIdAttribute
    ) {
        String tableAliasName;
        try {
            tableAliasName = (tableAlias != null) ? tableAlias + "." : "";

            List<? extends GenericTableColumn> attributes = getAttributes(monitor);
            boolean hasGeometryColumns = CommonUtils.safeCollection(attributes).stream()
                .anyMatch(e -> AltibaseConstants.TYPE_NAME_GEOMETRY.equalsIgnoreCase(e.getTypeName()));
            if (!hasGeometryColumns) {
                super.appendSelectSource(monitor, query, tableAlias, rowIdAttribute);
                return;
            }
            // Use this hack for geometry columns data reading
            int i = 0;
            for (GenericTableColumn col : CommonUtils.safeCollection(attributes)) {
                String columnName = DBUtils.getQuotedIdentifier(col);
                if (i++ > 0) {
                    query.append(",");
                }

                if (AltibaseConstants.TYPE_NAME_GEOMETRY.equalsIgnoreCase(col.getTypeName())) {
                    query.append("ASEWKT(").append(tableAliasName).append(columnName).append(", 32000) as ").append(columnName);
                } else {
                    query.append(tableAliasName).append(columnName).append(" as ").append(columnName);
                }
            }
        } catch (DBException e) {
            log.warn("Can't read table attributes.", e);
        }
    }

    @Property(viewable = false, hidden = true)
    public String getTableType() {
        return super.getTableType();
    }
    
    @Property(viewable = true, order = 20, editable = false, formatter = ByteNumberFormat.class, category = DBConstants.CAT_STATISTICS)
    public Long getTableSize(DBRProgressMonitor monitor) throws DBCException {
        if (!hasStatistics()) {
            loadSize(monitor);
        }

        return tableSize[SIZE_IDX_MEM] + tableSize[SIZE_IDX_DISK];
    }
    
    @Property(viewable = true, order = 22, editable = false, formatter = ByteNumberFormat.class)
    public Long getTableSizeInMemory(DBRProgressMonitor monitor) throws DBCException {
        if (!hasStatistics()) {
            loadSize(monitor);
        }

        return tableSize[SIZE_IDX_MEM];
    }
    
    @Property(viewable = true, order = 23, editable = false, formatter = ByteNumberFormat.class)
    public Long getTableSizeInDisk(DBRProgressMonitor monitor) throws DBCException {
        if (!hasStatistics()) {
            loadSize(monitor);
        }

        return tableSize[SIZE_IDX_DISK];
    }
    
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        tableSize = null;
        getTableSize(monitor);
        getTablespace(monitor);

        tablePrivCache.clearCache();
        
        return super.refreshObject(monitor);
    }
    
    ///////////////////////////////////
    // Tablespace
    @Property(viewable = true, order = 15, editable = false)
    public String getTablespace(DBRProgressMonitor monitor) throws DBException {
        
        if (tablespace != null) {
            return tablespace;
        }
        
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load tablespace")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT tbs_name, is_partitioned FROM system_.sys_tables_ t, system_.sys_users_ u "
                + "WHERE u.user_id = t.user_id AND u.user_name = ? AND t.table_name = ?")) {
                dbStat.setString(1, getSchema().getName());
                dbStat.setString(2, getName());

                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        tablespace = JDBCUtils.safeGetString(dbResult, 1);
                        partitioned = JDBCUtils.safeGetBoolean(dbResult, 2, AltibaseConstants.RESULT_T_VALUE);
                    }
                }
            }
        } catch (Exception e) {
            throw new DBCException("Error reading tablespace name", e);
        }
        
        return tablespace;
    }
    
    @Property(viewable = true, order = 16, editable = false)
    public boolean getPartitionedTable(DBRProgressMonitor monitor) throws DBException {
        if (tablespace == null) {
            getTablespace(monitor);
        }
        
        return partitioned;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 17, editable = false)
    public DBSTableStorageKind getStorageKind() {
        return storageKind;
    }
    
    ///////////////////////////////////
    // Statistics
    
    @Override
    public boolean hasStatistics() {
        return tableSize != null;
    }

    @Override
    public long getStatObjectSize() {
        return (!hasStatistics()) ? 0 : tableSize[SIZE_IDX_MEM] + tableSize[SIZE_IDX_DISK];
    }
    
    private void loadSize(DBRProgressMonitor monitor) throws DBCException {
        resetSize();
        
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load table status")) {
            try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT memory_size, disk_size FROM system_.sys_table_size_ WHERE USER_NAME = ? AND TABLE_NAME = ?")) {
                dbStat.setString(1, getSchema().getName());
                dbStat.setString(2, getName());

                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        fetchTableSize(dbResult);
                    }
                }
            }
        } catch (Exception e) {
            throw new DBCException("Error reading table statistics", e);
        } finally {
            if (!hasStatistics()) {
                resetSize();
            }
        }
    }
    
    protected void fetchTableSize(JDBCResultSet dbResult) throws SQLException {
        if (this.tableSize == null) {
            resetSize();
        }
        tableSize[SIZE_IDX_MEM] = (long) JDBCUtils.safeGetLong(dbResult, "MEMORY_SIZE");
        tableSize[SIZE_IDX_DISK] = (long) JDBCUtils.safeGetLong(dbResult, "DISK_SIZE");
    }

    protected void resetSize() {
        tableSize = new Long[2];
        for (int i = 0; i < 2; i++) {
            tableSize[i] = 0L; 
        }
    }
    
    /**
     * Returns object privileges on a table: SELECT, UPDATE, INSERT, DELETE
     */
    @Association
    public Collection<AltibasePrivTable> getTablePrivs(DBRProgressMonitor monitor) throws DBException {
        return tablePrivCache.getAllObjects(monitor, this);
    }
    
    static class TablePrivCache extends JDBCObjectCache<AltibaseTable, AltibasePrivTable> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, 
                @NotNull AltibaseTable tableBase) throws SQLException {
            final JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT"
                    + " grantor.user_name AS grantor_name"
                    + " , grantee.user_name AS grantee_name"
                    + " , DECODE(grantee.user_type, 'U', 'User', 'R', 'Role') as grantee_type"
                    + " ,p.priv_name AS priv_name"
                    + " ,g.with_grant_option AS with_grant_option"
                + " FROM"
                    + " system_.sys_users_ schema"
                    + " ,system_.sys_users_ grantor"
                    + " ,system_.sys_users_ grantee"
                    + " ,system_.sys_grant_object_ g"
                    + " ,system_.sys_privileges_ p"
                    + " ,system_.sys_tables_ t"
                + " WHERE"
                    + " schema.user_name = ? AND t.table_name = ?"
                    + " AND schema.user_id = t.user_id"
                    + " AND g.grantee_id = grantee.user_id"
                    + " AND g.grantor_id = grantor.user_id"
                    + " AND g.priv_id = p.priv_id"
                    + " AND p.priv_type = 1"
                    + " AND g.obj_id = t.table_id"
                + " ORDER BY priv_name, grantor_name, grantee_name");
            dbStat.setString(1, tableBase.getSchema().getName());
            dbStat.setString(2, tableBase.getName());
            return dbStat;
        }
        
        @Override
        protected AltibasePrivTable fetchObject(@NotNull JDBCSession session, @NotNull AltibaseTable tableBase, 
                @NotNull JDBCResultSet resultSet) throws SQLException, DBException {
            return new AltibasePrivTable(tableBase, resultSet);
        }
    }
}
