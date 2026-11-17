/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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

import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.altibase.internal.AltibaseMessages;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.IPropertyValueValidator;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.Map;

/**
 * A job of the scheduler ({@code SYSTEM_.SYS_JOBS_}).
 *
 * 설계: 열은 서버마다 다르다. Altibase 7.3에는 없고 ShardSphere 5.1.9에는 있는 소유자({@code JOB_USER_ID}), 종류({@code JOB_TYPE}),
 * 실패 횟수({@code FAIL_COUNT})는 작업 목록 질의가 연결의 카탈로그를 보고 읽을 때만 읽고 보인다({@link AltibaseDataSource.JobCache},
 * {@link ExtendedColumns}). 값의 뜻은 확인한 것만 이름으로 보인다:
 * <ul>
 *     <li>주기 단위({@code INTERVAL_TYPE}): YY 년, MM 월, DD 일, HH 시간, MI 분. SQL 매뉴얼의 CREATE JOB 단위(YEAR, MONTH, DAY, HOUR,
 *     MINUTE)로 Altibase 7.3에 작업을 만들어 읽은 코드다(2026-11-17, SECOND는 문법 오류). 모르는 코드는 수와 코드 그대로.</li>
 *     <li>{@code STATE}는 코드 그대로 보인다. 전에는 {@code STATE = '1'}을 "Activated"로 보였는데, 켜진 작업도 꺼진 작업도 0이었고
 *     (Altibase 7.3에서 읽음: 스케줄러가 돌지 않아 실행 중인 작업은 보지 못함), 시스피어 예제의 켜진 채 실행된 작업도 0이다. 켜짐은
 *     {@code IS_ENABLE}(T/F)이 말한다.</li>
 *     <li>종류: {@code P}는 프로시저(시스피어 예제의 {@code DBMS_SCHEDULER} 작업 종류 STORED_PROCEDURE가 P로 저장됨). 다른 코드는 그대로.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-17 Chromite: 소유자·종류·실패 횟수(있는 서버에서만), 읽을 수 있는 주기, "Activated"를 상태 코드로 바꿈
 *   (앱 변경 #139 수정 5, docs/chromite/altibase.md "작업")
 */
public class AltibaseJob extends AltibaseGlobalObject implements DBPScriptObject, DBPRefreshableObject {

    // SYS_JOBS_.INTERVAL_TYPE
    private static final String INTERVAL_YEAR = "YY"; //$NON-NLS-1$
    private static final String INTERVAL_MONTH = "MM"; //$NON-NLS-1$
    private static final String INTERVAL_DAY = "DD"; //$NON-NLS-1$
    private static final String INTERVAL_HOUR = "HH"; //$NON-NLS-1$
    private static final String INTERVAL_MINUTE = "MI"; //$NON-NLS-1$
    // SYS_JOBS_.JOB_TYPE
    private static final String JOB_TYPE_PROCEDURE = "P"; //$NON-NLS-1$

    private String ddl;
    
    private int jobId;
    private String jobName;
    private String execQuery;
    private Timestamp startTime;
    private Timestamp endTime;
    private int interval;
    private String intervalType;
    private Integer stateCode;
    private Timestamp lastExecTime;
    private int execCount;
    private String errorCode;
    private boolean isEnable;
    private String comment;
    // Read only where the connection's SYS_JOBS_ has these columns
    private final boolean extendedColumns;
    private String jobOwner;
    private String jobType;
    private Integer failCount;

    public AltibaseJob(GenericStructContainer owner, @NotNull ResultSet resultSet, boolean extendedColumns) {

        super((AltibaseDataSource) owner.getDataSource(), true);

        jobId = JDBCUtils.safeGetInt(resultSet, "JOB_ID");
        jobName = JDBCUtils.safeGetString(resultSet, "JOB_NAME");
        execQuery = JDBCUtils.safeGetString(resultSet, "EXEC_QUERY");
        startTime = JDBCUtils.safeGetTimestamp(resultSet, "START_TIME");
        endTime = JDBCUtils.safeGetTimestamp(resultSet, "END_TIME");
        interval = JDBCUtils.safeGetInt(resultSet, "INTERVAL");
        intervalType = JDBCUtils.safeGetStringTrimmed(resultSet, "INTERVAL_TYPE");
        stateCode = JDBCUtils.safeGetInteger(resultSet, "STATE");
        lastExecTime = JDBCUtils.safeGetTimestamp(resultSet, "LAST_EXEC_TIME");
        execCount = JDBCUtils.safeGetInt(resultSet, "EXEC_COUNT");
        errorCode = JDBCUtils.safeGetString(resultSet, "ERROR_CODE");
        isEnable = JDBCUtils.safeGetBoolean(resultSet, "IS_ENABLE", "T");
        comment = JDBCUtils.safeGetString(resultSet, "COMMENT");
        this.extendedColumns = extendedColumns;
        if (extendedColumns) {
            jobOwner = JDBCUtils.safeGetStringTrimmed(resultSet, AltibaseDataSource.JobCache.COLUMN_JOB_USER_NAME);
            jobType = JDBCUtils.safeGetStringTrimmed(resultSet, "JOB_TYPE");
            failCount = JDBCUtils.safeGetInteger(resultSet, "FAIL_COUNT");
        }
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return jobName;
    }

    @Property(viewable = true, order = 2)
    public int getJobId() {
        return jobId;
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getExecQuery() {
        return execQuery;
    }

    /**
     * The interval with its unit in words; an unknown unit code as the number and the code, nothing without a unit.
     */
    @Nullable
    @Property(viewable = true, order = 4)
    public String getinterval() {
        if (intervalType == null || intervalType.isEmpty()) {
            return null;
        }
        String pattern = switch (intervalType) {
            case INTERVAL_YEAR -> AltibaseMessages.job_interval_year;
            case INTERVAL_MONTH -> AltibaseMessages.job_interval_month;
            case INTERVAL_DAY -> AltibaseMessages.job_interval_day;
            case INTERVAL_HOUR -> AltibaseMessages.job_interval_hour;
            case INTERVAL_MINUTE -> AltibaseMessages.job_interval_minute;
            default -> null;
        };
        return pattern == null ? interval + " " + intervalType : NLS.bind(pattern, interval);
    }

    @Property(viewable = true, order = 5)
    public boolean getIsEnable() {
        return isEnable;
    }

    /**
     * SYS_JOBS_.STATE as the server stores it: its codes are not established (see the class comment).
     */
    @Nullable
    @Property(viewable = true, order = 9)
    public Integer getStateCode() {
        return stateCode;
    }

    @Nullable
    @Property(viewable = true, order = 13, visibleIf = ExtendedColumns.class)
    public String getJobOwner() {
        return jobOwner;
    }

    @Nullable
    @Property(viewable = true, order = 14, visibleIf = ExtendedColumns.class)
    public String getJobType() {
        return JOB_TYPE_PROCEDURE.equals(jobType) ? AltibaseMessages.job_type_procedure : jobType;
    }

    @Nullable
    @Property(viewable = true, order = 15, visibleIf = ExtendedColumns.class)
    public Integer getFailCount() {
        return failCount;
    }

    @NotNull
    @Property(viewable = true, order = 6)
    public Timestamp getLastExecTime() {
        return lastExecTime;
    }

    @NotNull
    @Property(viewable = true, order = 7)
    public String getErrorCode() {
        return errorCode;
    }

    @Property(viewable = true, order = 8)
    public int getExecCount() {
        return execCount;
    }

    @NotNull
    @Property(viewable = true, order = 10)
    public Timestamp getStartTime() {
        return startTime;
    }

    @NotNull
    @Property(viewable = true, order = 11)
    public Timestamp getEndTime() {
        return endTime;
    }

    @NotNull
    @Property(viewable = true, order = 12)
    public String getComment() {
        return comment;
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        if (CommonUtils.isEmpty(ddl)) {
            ddl = ((AltibaseMetaModel) getDataSource().getMetaModel()).getJobDDL(monitor, this, options) + ";";
        }
        
        return ddl;
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        AltibaseDataSource dataSouce = getDataSource();
        return dataSouce.getJobCache().refreshObject(monitor, dataSouce, this);
    }

    /**
     * Shows the owner, type and fail count only where the connection's SYS_JOBS_ has those columns.
     */
    public static class ExtendedColumns implements IPropertyValueValidator<AltibaseJob, Object> {
        @Override
        public boolean isValidValue(@NotNull AltibaseJob object, @Nullable Object value) {
            return object.extendedColumns;
        }
    }
}