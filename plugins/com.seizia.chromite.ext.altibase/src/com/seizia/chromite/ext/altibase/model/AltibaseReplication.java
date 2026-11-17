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
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.model.*;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;
import com.seizia.chromite.model.meta.Association;
import com.seizia.chromite.model.meta.IPropertyValueValidator;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectLazy;
import com.seizia.chromite.model.struct.DBSObjectState;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A replication of a server of the Altibase family: a row of the connection's replication catalog
 * ({@link AltibaseReplicationCatalog}; on Altibase SYSTEM_.SYS_REPLICATIONS_).
 *
 * 설계: 상태(시작 여부)는 목록과 같은 카탈로그 행에서 같은 문장({@code AltibaseDataSource.ReplicationCache})으로 읽는다. 문장은 모두 복제
 * 카탈로그가 주고, 이 객체는 자기를 읽은 카탈로그를 갖는다: 항목·송신·수신을 같은 카탈로그에서 읽고, 카탈로그에 없는 열에만 기대는 속성을
 * 숨기고({@link HasConnectionType}), 카탈로그가 이름을 주면 속성 보기에 보이고({@link #getCatalogName()}), 확인하지 않은 제어 문장을
 * 막는다({@link #supportsControlStatements()}).
 *
 * 변경 이력:
 * - 2026-11-17 {@link #refreshObjectState}: 프로시저 클래스에서 복사된 SYS_PROCEDURES_ 질의(자리표시자 둘 중 하나만 채움, 프로시저 상태를
 *   시작 여부로 읽음)를 복제 목록의 조회 문장으로 바꿈(앱 변경 #139, docs/chromite/altibase.md "상태 다시 읽기")
 * - 2026-11-17 문장을 복제 카탈로그에서 받음, 카탈로그 이름 속성, 연결 유형 속성은 카탈로그에 그 열이 있을 때만, 통계(송신 지연 바이트)는
 *   지연 단위가 있을 때만(앱 변경 #139 작업 4·5, docs/chromite/altibase.md "복제 카탈로그")
 * - 2026-11-17 카탈로그의 상태 코드가 이 계열의 것이 아니면({@link AltibaseReplicationCatalog#hasFamilyStateCodes()}) 시작 여부 대신
 *   서버의 IS_STARTED 값을 보이고 노드를 "활성"으로 그리지 않음(앱 변경 #139 수정 3, docs/chromite/shardsphere.md "GEMINI")
 */
public class AltibaseReplication extends AltibaseGlobalObject 
    implements DBSObjectLazy<AltibaseDataSource>, DBPRefreshableObject, DBPScriptObject, DBPStatefulObject, DBPObjectStatistics {

    private final SenderCache senderCache = new SenderCache();
    private final ReceiverCache receiverCache = new ReceiverCache();

    // The catalog this replication was read from
    @NotNull
    private final AltibaseReplicationCatalog catalog;

    private String ddl;

    private String name;
    private String remoteAddr;
    private String remoteConnType;

    private boolean isStarted;
    // IS_STARTED as the server stores it, for a catalog whose codes are not the family's
    private String startedValue;
    private String conflictResolution;
    private String mode;
    private String role;
    private int options;
    private String recoverable;
    private int parallelApplierCount;

    private long xsn;

    private Timestamp giveUpTime;
    private long giveUpXsn;
    private Timestamp remoteFaultDetectTime;

    protected AltibaseReplication(GenericStructContainer owner, JDBCResultSet resultSet) {
        super((AltibaseDataSource) owner.getDataSource(), true);
        catalog = ((AltibaseDataSource) owner.getDataSource()).getReplicationCatalog();

        name = JDBCUtils.safeGetString(resultSet, "REPLICATION_NAME");

        isStarted = readStarted(resultSet);
        startedValue = JDBCUtils.safeGetString(resultSet, "IS_STARTED");
        conflictResolution = JDBCUtils.safeGetString(resultSet, "CONFLICT_RESOLUTION");
        mode = JDBCUtils.safeGetString(resultSet, "REPL_MODE");
        role = JDBCUtils.safeGetString(resultSet, "ROLE");
        options = JDBCUtils.safeGetInt(resultSet, "OPTIONS");
        recoverable = JDBCUtils.safeGetString(resultSet, "RECOVERABLE");
        parallelApplierCount = JDBCUtils.safeGetInt(resultSet, "PARALLEL_APPLIER_COUNT");

        xsn = JDBCUtils.safeGetLong(resultSet, "XSN");

        giveUpTime = JDBCUtils.safeGetTimestamp(resultSet, "GIVE_UP_TIME");
        giveUpXsn = JDBCUtils.safeGetLong(resultSet, "GIVE_UP_XSN");
        remoteFaultDetectTime = JDBCUtils.safeGetTimestamp(resultSet, "REMOTE_FAULT_DETECT_TIME");

        remoteAddr = JDBCUtils.safeGetString(resultSet, "REMOTE_ADDR");
        // Not read where the catalog has no such column (the row has none)
        if (catalog.hasHostConnectionType()) {
            remoteConnType = JDBCUtils.safeGetString(resultSet, "REMOTE_CONN_TYPE");
        }
    }

    /**
     * The replication catalog this replication was read from; its items, sender and receiver are read from it too.
     */
    @NotNull
    public AltibaseReplicationCatalog getReplicationCatalog() {
        return catalog;
    }

    /**
     * Whether the start, stop and synchronize statements may be sent for this replication
     * ({@link AltibaseReplicationCatalog#supportsControlStatements()}).
     */
    public boolean supportsControlStatements() {
        return catalog.supportsControlStatements();
    }

    // SYS_REPLICATIONS_.IS_STARTED: 1 started, 0 stopped
    private static boolean readStarted(@NotNull JDBCResultSet resultSet) {
        return JDBCUtils.safeGetBoolean(resultSet, "IS_STARTED", "1");
    }

    @NotNull
    @Association
    public Collection<AltibaseReplicationSender> getReplicationSenders(@NotNull DBRProgressMonitor monitor) throws DBException {
        return senderCache.getAllObjects(monitor, this);
    }

    @NotNull
    @Association
    public List<AltibaseReplicationReceiver> getReplicationReceivers(@NotNull DBRProgressMonitor monitor) throws DBException {
        return receiverCache.getAllObjects(monitor, this);
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Property(viewable = true, order = 2)
    public String getRemoteAddr() {
        return remoteAddr;
    }

    @Property(viewable = true, order = 3, visibleIf = HasConnectionType.class)
    public String getRemoteConnType() {
        return remoteConnType;
    }

    /**
     * The name the server gives its replication when its catalog is not the family's own; shown only then.
     */
    @Nullable
    @Property(viewable = true, order = 9, visibleIf = HasCatalogName.class)
    public String getCatalogName() {
        return catalog.getDisplayName();
    }

    @Property(viewable = true, order = 10, visibleIf = FamilyStateCodes.class)
    public boolean getIsStarted() {
        return isStarted;
    }

    /**
     * IS_STARTED as the server stores it, where the catalog's codes are not the family's (shown instead of the flag).
     */
    @Nullable
    @Property(viewable = true, order = 10, visibleIf = OtherStateCodes.class)
    public String getStartedValue() {
        return startedValue;
    }

    @Property(viewable = true, order = 11)
    public String getConflictResolution() {
        return conflictResolution;
    }

    @Property(viewable = true, order = 12)
    public String getMode() {
        return mode;
    }

    @Property(viewable = true, order = 13)
    public String getRole() {
        return role;
    }

    @Property(viewable = true, order = 14)
    public int getOptions() {
        return options;
    }

    @Property(viewable = true, order = 15)
    public String getRecoverable() {
        return recoverable;
    }

    @Property(viewable = true, order = 16)
    public int getParallelApplierCount() {
        return parallelApplierCount;
    }

    @Property(viewable = true, order = 20)
    public long getXsn() {
        return xsn;
    }

    @Property(viewable = true, order = 30)
    public Timestamp getGiveUpTime() {
        return giveUpTime;
    }

    @Property(viewable = true, order = 31)
    public long getGiveUpXsn() {
        return giveUpXsn;
    }

    @Property(viewable = true, order = 32)
    public Timestamp getRemoteFaultDetectTime() {
        return remoteFaultDetectTime;
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        ddl = null;

        return this.getDataSource().getReplicationCache().refreshObject(monitor, getDataSource(), this);
    }

    @Nullable
    @Override
    public Object getLazyReference(Object propertyId) {
        return null;
    }

    /**
     * Returns a replication's children: replication item
     */
    public List<AltibaseReplicationItem> getReplicationItems(DBRProgressMonitor monitor) throws DBException {
        return this.getDataSource().getReplicationCache().getChildren(monitor, this.getDataSource(), this);
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        if (CommonUtils.isEmpty(ddl)) {
            ddl = ((AltibaseMetaModel) getDataSource().getMetaModel()).getReplicationDDL(monitor, this, options) + ";";
        }

        return ddl;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        // A started flag whose codes are not known does not mark the node as active
        return isStarted && catalog.hasFamilyStateCodes() ? DBSObjectState.ACTIVE : DBSObjectState.NORMAL;
    }

    /**
     * Reads the started flag again from this replication's own catalog row, with the statement the replication list uses
     * ({@link AltibaseDataSource.ReplicationCache#prepareLookupStatement}), so the catalog is named in one place. Without
     * the row (dropped meanwhile) the state stays as read; a node refresh removes the node.
     */
    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) throws DBCException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, 
                "Refresh state of replication '" + this.getName() + "'")) {
            try (JDBCStatement dbStat = getDataSource().getReplicationCache().prepareLookupStatement(
                    session, getDataSource(), this, null)) {
                dbStat.executeStatement();

                try (JDBCResultSet dbResult = dbStat.getResultSet()) {
                    if (dbResult != null && dbResult.next()) {
                        isStarted = readStarted(dbResult);
                        startedValue = JDBCUtils.safeGetString(dbResult, "IS_STARTED");
                    }
                }
            } catch (SQLException e) {
                throw new DBCException(e, session.getExecutionContext());
            }
        }
    }

    static class SenderCache extends JDBCObjectCache<AltibaseReplication, AltibaseReplicationSender> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, 
                @NotNull AltibaseReplication owner) throws SQLException {
            final JDBCPreparedStatement dbStat = session.prepareStatement(owner.getReplicationCatalog().getSenderQuery());
            dbStat.setString(1, owner.getName());
            return dbStat;
        }

        @Override
        protected AltibaseReplicationSender fetchObject(@NotNull JDBCSession session, @NotNull AltibaseReplication owner, 
                @NotNull JDBCResultSet resultSet) throws SQLException, DBException {
            return new AltibaseReplicationSender(owner, resultSet);
        }
    }

    static class ReceiverCache extends JDBCObjectCache<AltibaseReplication, AltibaseReplicationReceiver> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(@NotNull JDBCSession session, 
                @NotNull AltibaseReplication owner) throws SQLException {
            final JDBCPreparedStatement dbStat = session.prepareStatement(owner.getReplicationCatalog().getReceiverQuery());
            dbStat.setString(1, owner.getName());
            return dbStat;
        }

        @Override
        protected AltibaseReplicationReceiver fetchObject(@NotNull JDBCSession session, @NotNull AltibaseReplication owner, 
                @NotNull JDBCResultSet resultSet) throws SQLException, DBException {
            return new AltibaseReplicationReceiver(owner, resultSet);
        }
    }

    ///////////////////////////////////
    // Statistics

    /**
     * The statistics size is the sender gap in bytes, which needs the catalog's gap unit: without one there is no size
     * rather than a wrong one.
     */
    @Override
    public boolean hasStatistics() {
        return catalog.getGapUnitProperty() != null;
    }

    @Override
    public long getStatObjectSize() {
        long gap = 0;
        if (senderCache.getCacheSize() > 0) {
            gap = senderCache.getCachedObjects().get(0).getGapSizeInByte();
        }
        return gap;
    }

    /**
     * Shows the connection type only where the catalog's host table has that column.
     */
    public static class HasConnectionType implements IPropertyValueValidator<AltibaseReplication, Object> {
        @Override
        public boolean isValidValue(@NotNull AltibaseReplication object, @Nullable Object value) {
            return object.catalog.hasHostConnectionType();
        }
    }

    /**
     * Shows the catalog's name only where the catalog has one (not on the family's own replication).
     */
    public static class HasCatalogName implements IPropertyValueValidator<AltibaseReplication, Object> {
        @Override
        public boolean isValidValue(@NotNull AltibaseReplication object, @Nullable Object value) {
            return object.catalog.getDisplayName() != null;
        }
    }

    /**
     * Shows the started flag where the catalog's state codes are the family's.
     */
    public static class FamilyStateCodes implements IPropertyValueValidator<AltibaseReplication, Object> {
        @Override
        public boolean isValidValue(@NotNull AltibaseReplication object, @Nullable Object value) {
            return object.catalog.hasFamilyStateCodes();
        }
    }

    /**
     * Shows the stored started value where the catalog's state codes are not established.
     */
    public static class OtherStateCodes implements IPropertyValueValidator<AltibaseReplication, Object> {
        @Override
        public boolean isValidValue(@NotNull AltibaseReplication object, @Nullable Object value) {
            return !object.catalog.hasFamilyStateCodes();
        }
    }

}
