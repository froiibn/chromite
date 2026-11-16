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

import org.jkiss.code.NotNull;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericFunctionResultType;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameterKind;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;

import java.sql.SQLException;
import java.util.Map;

/**
 * A procedure or function of a package.
 *
 * 설계: 패키지 안의 서브프로그램은 자기 상태 행이 없다(SYS_PROCEDURES_에 없고, SYS_PACKAGES_는 패키지 단위). 목록이 늘 유효로 만들므로
 * 상태 다시 읽기도 카탈로그를 읽지 않는다.
 *
 * 변경 이력:
 * - 2026-11-17 {@link #refreshState}: 상위 클래스가 독립 프로시저의 SYS_PROCEDURES_를 서브프로그램 이름으로 읽어, 같은 스키마에 같은 이름의
 *   무효한 독립 프로시저가 있으면 새로 고침 뒤 서브프로그램이 무효로 보였다(앱 변경 #139, docs/chromite/altibase.md "상태 다시 읽기")
 */
public class AltibaseProcedurePackaged extends AltibaseProcedureBase {

    private String pkgSchema;
    private String pkgName;
    
    public AltibaseProcedurePackaged(
            GenericStructContainer container,
            String pkgSchema,
            String pkgName,
            String procedureName, 
            boolean valid,
            DBSProcedureType procedureType, 
            GenericFunctionResultType functionResultType) {
        super(container, procedureName, true, procedureType, functionResultType);
        this.pkgSchema = pkgSchema;
        this.pkgName = pkgName;
    }

    /**
     * Keeps the state the list gave (valid): the catalog has no status of a package's subprogram, and SYS_PROCEDURES_ holds
     * only standalone procedures, functions and typesets.
     */
    @Override
    protected void refreshState(JDBCSession session) {
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        return "-- Unable to get package dependent object source";
    }

    /**
     * Add procedure columns
     */
    public void loadProcedureColumns(DBRProgressMonitor monitor) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Load procedure columns")) {
            JDBCPreparedStatement dbStat = ((AltibaseMetaModel) getDataSource().getMetaModel())
                    .prepareProcedurePackagedColumnLoadStatement(session, pkgSchema, pkgName, this.getName());
            dbStat.setFetchSize(DBConstants.METADATA_FETCH_SIZE);
            dbStat.executeStatement();
            JDBCResultSet dbResult = dbStat.getResultSet();
            try {
                while (dbResult.next()) {
                    final boolean isFunction  = (JDBCUtils.safeGetInt(dbResult, "SUB_TYPE") == 1);
                    final String columnName   = JDBCUtils.safeGetString(dbResult, "PARA_NAME");
                    int position              = JDBCUtils.safeGetInt(dbResult, "PARA_ORDER");
                    final int precision       = JDBCUtils.safeGetInt(dbResult, "PRECISION");
                    final int columnSize      = precision;
                    final int scale           = JDBCUtils.safeGetInt(dbResult, "SCALE");
                    final int columnTypeNum   = JDBCUtils.safeGetInt(dbResult, "INOUT_TYPE"); // 0: IN, 1: OUT, 2: IN OUT
                    final int valueType       = JDBCUtils.safeGetInt(dbResult, "DATA_TYPE");;
                    final String typeName     = JDBCUtils.safeGetString(dbResult, "TYPE_NAME");
                    final String defaultValue = JDBCUtils.safeGetString(dbResult, "DEFAULT_VAL");
                    final boolean notNull     = (defaultValue == null);
                    final String remarks      = "";

                    DBSProcedureParameterKind parameterType;

                    switch (columnTypeNum) {
                        case AltibaseProcedureParameter.PARAM_IN:
                            parameterType = DBSProcedureParameterKind.IN;
                            break;
                        case AltibaseProcedureParameter.PARAM_INOUT:
                            parameterType = DBSProcedureParameterKind.INOUT;
                            break;
                        case AltibaseProcedureParameter.PARAM_OUT:
                            parameterType = DBSProcedureParameterKind.OUT;
                            break;
                        default:
                            parameterType = DBSProcedureParameterKind.UNKNOWN;
                            break;
                    }

                    // procedure with no argument case
                    if (!isFunction && columnName == null && position == 0) {
                        return; 
                    }

                    // function return type
                    if (isFunction && columnName == null && position == 1) {
                        continue; 
                    }

                    if (isFunction) {
                        --position;
                    }

                    AltibaseProcedureParameter column = new AltibaseProcedureParameter(
                            this,
                            columnName,
                            typeName,
                            valueType,
                            position,
                            columnSize,
                            scale, precision, notNull,
                            remarks,
                            parameterType);

                    this.addColumn(column);
                }
            } finally {
                dbResult.close();
            }
        } catch (SQLException e) {
            throw new DBDatabaseException(e, getDataSource());
        }
    }
}
