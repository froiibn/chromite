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
package com.seizia.chromite.model.impl.jdbc.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.data.DBDContentStorage;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.storage.StringContentStorage;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.utils.ContentUtils;
import com.seizia.chromite.utils.MimeTypes;

import java.io.IOException;
import java.io.Reader;
import java.sql.SQLException;
import java.sql.SQLXML;

/**
 * JDBCContentXML
 *
 * @author Serge Rider
 */
public class JDBCContentXML extends JDBCContentLOB {

    private static final Log log = Log.getLog(JDBCContentXML.class);

    protected SQLXML xml;

    public JDBCContentXML(DBCExecutionContext executionContext, SQLXML xml) {
        super(executionContext);
        this.xml = xml;
    }

    @Override
    public long getLOBLength() throws DBCException {
        return -1;
    }

    @NotNull
    @Override
    public String getContentType()
    {
        return MimeTypes.TEXT_XML;
    }

    @Override
    public DBDContentStorage getContents(@NotNull DBRProgressMonitor monitor)
        throws DBCException
    {
        if (storage == null && xml != null) {
            try {
                storage = StringContentStorage.createFromReader(xml.getCharacterStream());
            }
            catch (IOException e) {
                throw new DBCException("IO error while reading content", e);
            } catch (SQLException e) {
                throw new DBCException(e, executionContext);
            }
            // Free blob - we don't need it anymore
            releaseXML();
        }
        return storage;
    }

    @Override
    public void release()
    {
        releaseXML();
        super.release();
    }

    private void releaseXML() {
        if (xml != null) {
            try {
                xml.free();
            } catch (Exception e) {
                log.debug(e);
            }
            xml = null;
        }
    }

    @Override
    public void bindParameter(
        JDBCSession session,
        JDBCPreparedStatement preparedStatement,
        DBSTypedObject columnType,
        int paramIndex)
        throws DBCException
    {
        try {
            if (xml != null) {
                preparedStatement.setSQLXML(paramIndex, xml);
            } else if (storage != null) {
                try {
                    preparedStatement.setSQLXML(paramIndex, new JDBCSQLXMLImpl(storage));
                } catch (Throwable e) {
                    if (e instanceof SQLException && !JDBCUtils.isFeatureNotSupportedError(session.getDataSource(), e)) {
                        throw (SQLException) e;
                    }
                    try {
                        SQLXML sqlxml = preparedStatement.getConnection().createSQLXML();
                        sqlxml.setString(new JDBCSQLXMLImpl(storage).getString());
                        preparedStatement.setSQLXML(paramIndex, sqlxml);
                    } catch (Throwable e0) {
                        if (e0 instanceof SQLException && !JDBCUtils.isFeatureNotSupportedError(session.getDataSource(), e0)) {
                            throw (SQLException) e0;
                        }
                        // Try 3 jdbc methods to set character stream
                        Reader streamReader = storage.getContentReader();
                        try {
                            preparedStatement.setCharacterStream(
                                paramIndex,
                                streamReader);
                        } catch (Throwable e1) {
                            if (e1 instanceof SQLException && !JDBCUtils.isFeatureNotSupportedError(session.getDataSource(), e1)) {
                                throw (SQLException) e1;
                            }
                            long streamLength = ContentUtils.calculateContentLength(storage.getContentReader());
                            try {
                                preparedStatement.setCharacterStream(
                                    paramIndex,
                                    streamReader,
                                    streamLength);
                            } catch (Throwable e2) {
                                if (e2 instanceof SQLException && !JDBCUtils.isFeatureNotSupportedError(session.getDataSource(), e2)) {
                                    throw (SQLException) e2;
                                }
                                preparedStatement.setCharacterStream(
                                    paramIndex,
                                    streamReader,
                                    (int) streamLength);
                            }
                        }
                    }
                }
            } else {
                preparedStatement.setNull(paramIndex, java.sql.Types.SQLXML);
            }
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        } catch (IOException e) {
            throw new DBCException("IO error while reading content", e, session.getExecutionContext());
        }
    }

    @Nullable
    @Override
    public SQLXML getRawValue() {
        return xml;
    }

    @Override
    public boolean isNull()
    {
        return xml == null && storage == null;
    }

    @Override
    protected JDBCContentLOB createNewContent()
    {
        return new JDBCContentXML(executionContext, null);
    }

    @Nullable
    @Override
    public String getDisplayString(@NotNull DBDDisplayFormat format)
    {
        return xml == null && storage == null ? null : "[XML]";
    }

}
