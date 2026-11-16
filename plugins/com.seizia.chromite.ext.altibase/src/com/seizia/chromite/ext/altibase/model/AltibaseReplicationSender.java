/*
 * DBeaver - Universal Database Manager
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

package com.seizia.chromite.ext.altibase.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.IPropertyValueValidator;
import com.seizia.chromite.model.meta.Property;
import org.jkiss.utils.ByteNumberFormat;

/**
 * A thread to send replication target table's data to a peer database.
 *
 * 설계: 지연은 서버가 주는 수에 지연 단위 속성을 곱한 바이트다. 복제 카탈로그에 단위 속성이 없으면
 * ({@link AltibaseReplicationCatalog#getGapUnitProperty()} = null) 단위를 모르므로 바이트로 보이지 않고 서버의 수를 그대로
 * 단위 없이 보인다({@link #getGapWithoutUnit()}): 틀린 단위보다 단위 없는 수가 낫다. 두 속성 중 그 카탈로그에 맞는 하나만 보인다.
 *
 * 변경 이력:
 * - 2026-11-17 지연 단위가 없는 카탈로그의 지연을 단위 없이(앱 변경 #139 작업 4, docs/chromite/altibase.md "복제 카탈로그")
 */
public class AltibaseReplicationSender extends AltibaseReplicationModule {

    // The sender gap times the gap unit; only where the catalog has a gap unit
    private long gapSizeInByte;
    // The sender gap as the server reports it; only where the catalog has no gap unit
    private long gapWithoutUnit;
    private String startFlag;
    private boolean networkError;
    private long xsn;
    private long commitXsn;
    private String status;
    private long readLogCount;
    private long sentLogCount;

    public AltibaseReplicationSender(AltibaseReplication parent, JDBCResultSet resultSet) {
        super(parent);

        startFlag = getStartFlag(JDBCUtils.safeGetLong(resultSet, "START_FLAG"));
        networkError = JDBCUtils.safeGetBoolean(resultSet, "NET_ERROR_FLAG", "1");
        xsn = JDBCUtils.safeGetLong(resultSet, "XSN");
        commitXsn = JDBCUtils.safeGetLong(resultSet, "COMMIT_XSN");
        status = getStatus(JDBCUtils.safeGetLong(resultSet, "STATUS"));
        readLogCount = JDBCUtils.safeGetLong(resultSet, "READ_LOG_COUNT");
        sentLogCount = JDBCUtils.safeGetLong(resultSet, "SEND_LOG_COUNT");
        if (hasGapUnit(parent)) {
            gapSizeInByte = JDBCUtils.safeGetLong(resultSet, "GAP_SIZE_IN_BYTE");
        } else {
            gapWithoutUnit = JDBCUtils.safeGetLong(resultSet, "REP_GAP");
        }
    }

    private static boolean hasGapUnit(@NotNull AltibaseReplication replication) {
        return replication.getReplicationCatalog().getGapUnitProperty() != null;
    }

    @NotNull
    @Override
    @Property(viewable = false, order = 1, hidden = true)
    public String getName() {
        return "Sender";
    }

    @Property(viewable = true, order = 2, formatter = ByteNumberFormat.class, visibleIf = GapUnitKnown.class)
    public long getGap() {
        return gapSizeInByte;
    }

    /**
     * The gap in the server's own unit, where the catalog names no unit.
     */
    @Property(viewable = true, order = 2, visibleIf = GapUnitUnknown.class)
    public long getGapWithoutUnit() {
        return gapWithoutUnit;
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getStartFlag() {
        return startFlag;
    }

    @Property(viewable = true, order = 4)
    public boolean getNetworkError() {
        return networkError;
    }

    @Property(viewable = true, order = 5)
    public long getXsn() {
        return xsn;
    }

    @Property(viewable = true, order = 6)
    public long getCommitXsn() {
        return commitXsn;
    }

    @NotNull
    @Property(viewable = true, order = 7)
    public String getStatus() {
        return status;
    }

    @Property(viewable = true, order = 8)
    public long getReadLogCount() {
        return readLogCount;
    }

    @Property(viewable = true, order = 9)
    public long getSentLogCount() {
        return sentLogCount;
    }

    public long getGapSizeInByte() {
        return gapSizeInByte;
    }

    private String getStartFlag(long value) {
        switch ((int) value) {
            case 0: return "Normal";
            case 1: return "Quick";
            case 2: return "Sync";
            case 3: return "Sync Only";
            case 4: return "Sync Run";
            case 5: return "Sync End";
            case 6: return "Recovery";
            case 7: return "Offline";
            case 8: return "Parallel";
            default: return "Unknown";
        }
    }

    private String getStatus(long value) {
        switch ((int) value) {
            case 0: return "Stop";
            case 1: return "Run";
            case 2: return "Retry";
            case 3: return "Failback Normal";
            case 4: return "Failback Master";
            case 5: return "Failback Slave";
            case 6: return "Sync";
            case 7: return "Failback Eager";
            case 8: return "Failback Flush";
            case 9: return "Idle";
            default: return "Unknown";
        }
    }

    /**
     * Shows the gap in bytes where the catalog has a gap unit.
     */
    public static class GapUnitKnown implements IPropertyValueValidator<AltibaseReplicationSender, Object> {
        @Override
        public boolean isValidValue(@NotNull AltibaseReplicationSender object, @Nullable Object value) {
            return hasGapUnit(object.getParentObject());
        }
    }

    /**
     * Shows the gap without a unit where the catalog has no gap unit.
     */
    public static class GapUnitUnknown implements IPropertyValueValidator<AltibaseReplicationSender, Object> {
        @Override
        public boolean isValidValue(@NotNull AltibaseReplicationSender object, @Nullable Object value) {
            return !hasGapUnit(object.getParentObject());
        }
    }
}