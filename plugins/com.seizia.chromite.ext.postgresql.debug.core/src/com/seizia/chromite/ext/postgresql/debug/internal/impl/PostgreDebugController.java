/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2017-2018 Andrew Khitrin (ahitrin@gmail.com)
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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
package com.seizia.chromite.ext.postgresql.debug.internal.impl;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.debug.DBGBaseController;
import com.seizia.chromite.debug.DBGBreakpointDescriptor;
import com.seizia.chromite.debug.DBGException;
import com.seizia.chromite.ext.postgresql.debug.core.PostgreSqlDebugCore;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedure;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

/**
 * PL/pgSQL debugger (pldbgapi extension) of one function.
 *
 * 설계: 줄 번호는 pldbgapi가 쓰는 대로 함수 본문({@code pg_proc.prosrc})의 줄이다. 본문은 보통 빈 줄({@code $function$} 바로 뒤
 * 줄바꿈)로 시작하므로 1번 줄이 빈 줄이다. 세션 전에 중단점을 놓을 수 있게 같은 본문을 여기서 읽는다({@link #readSource}).
 * pldbgapi는 PL/pgSQL(EDB는 SPL)만 디버깅하므로 다른 언어 함수는 시작 전에 막는다.
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): readSource·getSourceIdentifier, 원본·줄로 중단점 설명
 *   (docs/chromite/procedure-debugger.md)
 */
public class PostgreDebugController extends DBGBaseController {

    private static final Log log = Log.getLog(PostgreDebugController.class);

    /**
     * Languages pldbgapi can debug.
     */
    private static final Set<String> DEBUGGABLE_LANGUAGES = Set.of("plpgsql", "edbspl"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String SQL_SOURCE = "SELECT l.lanname, p.prosrc FROM pg_catalog.pg_proc p " //$NON-NLS-1$
        + "JOIN pg_catalog.pg_language l ON l.oid = p.prolang WHERE p.oid = ?"; //$NON-NLS-1$

    private final long functionOid;

    public PostgreDebugController(DBPDataSourceContainer dataSourceContainer, Map<String, Object> configuration, long functionOid) {
        super(dataSourceContainer, configuration);
        this.functionOid = functionOid;
    }

    @Override
    public PostgreDebugSession createSession(DBRProgressMonitor monitor, Map<String, Object> configuration)
            throws DBGException
    {
        PostgreDebugSession pgSession = null;
        try {
            log.debug("Creating debug session");
            pgSession = new PostgreDebugSession(monitor,this);
            log.debug("Attaching debug session");
            pgSession.attach(monitor, configuration);
            log.debug("Debug session created");
            return pgSession;
        } catch (DBException e) {
            if (pgSession != null) {
                try {
                    // Nothing ran in the debugger's sessions yet (or the call failed to start): no transaction to ask about
                    pgSession.closeSession(monitor);
                } catch (Exception e1) {
                    log.error(e1);
                }
            }
            if (e instanceof DBGException) {
                throw (DBGException)e;
            }
            log.debug(String.format("Error attaching debug session %s", e.getMessage()));
            throw new DBGException("Error attaching debug session", e);
        }
    }

    @NotNull
    @Override
    public String readSource(@NotNull DBRProgressMonitor monitor) throws DBGException {
        try {
            PostgreProcedure function = PostgreSqlDebugCore.resolveFunction(monitor, getDataSourceContainer(), getDebugConfiguration());
            try (JDBCSession session = DBUtils.openMetaSession(monitor, function, "Read function source for debugger")) { //$NON-NLS-1$
                try (JDBCPreparedStatement statement = session.prepareStatement(SQL_SOURCE)) {
                    statement.setLong(1, functionOid);
                    try (JDBCResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new DBGException("Function " + functionOid + " not found");
                        }
                        String language = resultSet.getString(1);
                        if (!DEBUGGABLE_LANGUAGES.contains(language)) {
                            throw new DBGException("Only PL/pgSQL functions and procedures can be debugged (language of "
                                + function.getName() + ": " + language + ")");
                        }
                        String source = resultSet.getString(2);
                        return source == null ? "" : source;
                    }
                }
            }
        } catch (DBGException e) {
            throw e;
        } catch (DBException | SQLException e) {
            throw new DBGException("Cannot read the function source", e);
        }
    }

    @NotNull
    @Override
    public Object getSourceIdentifier() {
        return functionOid;
    }

    @NotNull
    @Override
    public DBGBreakpointDescriptor describeBreakpoint(@NotNull Object sourceIdentifier, int lineNumber) {
        return new PostgreDebugBreakpointDescriptor(sourceIdentifier, lineNumber);
    }

}
