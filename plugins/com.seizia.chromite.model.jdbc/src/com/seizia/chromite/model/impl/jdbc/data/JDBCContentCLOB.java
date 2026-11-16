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
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.data.DBDContentCached;
import com.seizia.chromite.model.data.DBDContentStorage;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.storage.ExternalContentStorage;
import com.seizia.chromite.model.data.storage.StringContentStorage;
import com.seizia.chromite.model.data.storage.TemporaryContentStorage;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.ContentUtils;
import com.seizia.chromite.utils.MimeTypes;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Clob;
import java.sql.SQLException;

/**
 * JDBCContentCLOB
 *
 * @author Serge Rider
 */
public class JDBCContentCLOB extends JDBCContentLOB implements DBDContent {

    private static final Log log = Log.getLog(JDBCContentCLOB.class);

    private Clob clob;
    private Reader tmpReader;

    public JDBCContentCLOB(DBCExecutionContext executionContext, Clob clob) {
        super(executionContext);
        this.clob = clob;
    }

    @Override
    public long getLOBLength() throws DBCException {
        if (clob == null) {
            return 0;
        }
        try {
            return clob.length();
        } catch (Throwable e) {
            throw new DBCException(e, executionContext);
        }
    }

    @NotNull
    @Override
    public String getContentType()
    {
        return MimeTypes.TEXT_PLAIN;
    }

    @Override
    public DBDContentStorage getContents(@NotNull DBRProgressMonitor monitor)
        throws DBCException
    {
        if (storage == null && clob != null) {
            try {
                long contentLength = getContentLength();
                DBPPlatform platform = DBWorkbench.getPlatform();
                if (contentLength < platform.getPreferenceStore().getInt(ModelPreferences.MEMORY_CONTENT_MAX_SIZE)) {
                    try {
                        String subString = clob.getSubString(1, (int) contentLength);
                        storage = new JDBCContentChars(executionContext, subString);
                    } catch (Exception e) {
                        log.debug("Can't get CLOB as substring", e);
                        try {
                            storage = StringContentStorage.createFromReader(clob.getCharacterStream(), contentLength);
                        } catch (IOException e1) {
                            throw new DBCException("IO error while reading content", e);
                        } catch (Throwable e1) {
                            throw new DBCException(e, executionContext);
                        }
                    }
                } else {
                    // Create new local storage
                    Path tempFile;
                    try {
                        tempFile = ContentUtils.createTempContentFile(monitor, platform, "clob" + clob.hashCode());
                    } catch (IOException e) {
                        throw new DBCException("Can't create temp file", e);
                    }
                    try (Writer os = Files.newBufferedWriter(tempFile, Charset.forName(getDefaultEncoding()))) {
                        ContentUtils.copyStreams(clob.getCharacterStream(), contentLength, os, monitor);
                    } catch (IOException e) {
                        ContentUtils.deleteTempFile(tempFile);
                        throw new DBCException("IO error while copying content", e);
                    } catch (Throwable e) {
                        ContentUtils.deleteTempFile(tempFile);
                        throw new DBCException(e, executionContext);
                    }
                    this.storage = new TemporaryContentStorage(platform, tempFile, getDefaultEncoding(), true);
                }
            } catch (DBCException e) {
                handleContentReadingException(e);
            }
            // Free lob - we don't need it anymore
            releaseClob();
        }
        return storage;
    }

    @Override
    public void release()
    {
        releaseTempStream();
        releaseClob();
        super.release();
    }

    private void releaseClob() {
        if (clob != null) {
            try {
                clob.free();
            } catch (Throwable e) {
                // Log as warning only if it is an exception.
                log.debug("Error freeing CLOB: " + e.getClass().getName() + ": " + e.getMessage());
            }
            clob = null;
        }
    }

    private void releaseTempStream() {
        if (tmpReader != null) {
            ContentUtils.close(tmpReader);
            tmpReader = null;
        }
    }

    @Override
    public void bindParameter(
        JDBCSession session, JDBCPreparedStatement preparedStatement,
        DBSTypedObject columnType, int paramIndex
    ) throws DBCException {
        try {
            if (storage != null) {
//                String stringValue = ContentUtils.getContentStringValue(session.getProgressMonitor(), this);
//                preparedStatement.setString(paramIndex, stringValue);
                // Try 3 jdbc methods to set character stream
                releaseTempStream();
                tmpReader = storage.getContentReader();
                try {
                    preparedStatement.setNCharacterStream(
                        paramIndex,
                        tmpReader
                    );
                } catch (Throwable e) {
                    if (e instanceof SQLException && !(JDBCUtils.isFeatureNotSupportedError(session.getDataSource(), e))) {
                        throw (SQLException) e;
                    } else {
                        long streamLength = ContentUtils.calculateContentLength(storage.getContentReader());
                        try {
                            preparedStatement.setCharacterStream(
                                paramIndex,
                                tmpReader,
                                streamLength
                            );
                        } catch (Throwable e1) {
                            if (e1 instanceof SQLException && !(JDBCUtils.isFeatureNotSupportedError(session.getDataSource(), e1))) {
                                throw (SQLException) e1;
                            } else {
                                preparedStatement.setCharacterStream(
                                    paramIndex,
                                    tmpReader,
                                    (int) streamLength
                                );
                            }
                        }
                    }
                }
            } else if (clob != null) {
                preparedStatement.setClob(paramIndex, clob);
            } else {
                preparedStatement.setNull(paramIndex, java.sql.Types.CLOB);
            }
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        } catch (Throwable e) {
            throw new DBCException("IO error while binding content", e);
        }
    }

    @Nullable
    @Override
    public Object getRawValue() {
        return clob;
    }

    @Override
    public boolean isNull()
    {
        return clob == null && storage == null;
    }

    @Override
    protected JDBCContentLOB createNewContent()
    {
        return new JDBCContentCLOB(executionContext, null);
    }

    @Nullable
    @Override
    public String getDisplayString(@NotNull DBDDisplayFormat format)
    {
        if (clob == null && storage == null) {
            return null;
        }
        if (storage != null) {
            if (storage instanceof DBDContentCached) {
                return CommonUtils.toString(((DBDContentCached) storage).getCachedValue());
            } else {
                if (storage instanceof ExternalContentStorage) {
                    return "[" + ((ExternalContentStorage) storage).getFile().getFileName() + "]";
                }
            }
        }
        return "[CLOB]";
    }
}
