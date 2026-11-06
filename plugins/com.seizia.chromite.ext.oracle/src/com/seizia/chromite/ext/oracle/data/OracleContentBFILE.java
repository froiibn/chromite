/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.oracle.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.data.DBDContentStorage;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.storage.BytesContentStorage;
import com.seizia.chromite.model.data.storage.TemporaryContentStorage;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.data.JDBCContentLOB;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.ContentUtils;
import com.seizia.chromite.utils.MimeTypes;
import org.jkiss.utils.BeanUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * BFILE content
 */
public class OracleContentBFILE extends JDBCContentLOB {

    private static final Log log = Log.getLog(OracleContentBFILE.class);

    private Object bfile;
    private String name;
    private boolean opened;

    public OracleContentBFILE(DBCExecutionContext executionContext, Object bfile) {
        super(executionContext);
        this.bfile = bfile;
        if (this.bfile != null) {
            try {
                name = (String) BeanUtils.invokeObjectMethod(
                    bfile,
                    "getName");
            } catch (Throwable e) {
                log.error(e);
            }
        }
    }

    @Override
    public long getLOBLength() throws DBCException {
        if (bfile != null) {
            boolean openLocally = !opened;
            try {
                if (openLocally) {
                    openFile();
                }
                final Object length = BeanUtils.invokeObjectMethod(
                    bfile,
                    "length");
                if (length instanceof Number) {
                    return ((Number) length).longValue();
                }
            } catch (Throwable e) {
                throw new DBCException("Error when reading BFILE length", e, executionContext);
            } finally {
                if (openLocally) {
                    closeFile();
                }
            }
        }
        return 0;
    }

    private void openFile() throws DBCException {
        if (opened) {
            return;
        }
        try {
            BeanUtils.invokeObjectMethod(bfile, "openFile");
            opened = true;
        } catch (Throwable e) {
            throw new DBCException(e, executionContext);
        }
    }

    private void closeFile() throws DBCException {
        if (!opened) {
            return;
        }
        try {
            BeanUtils.invokeObjectMethod(bfile, "closeFile");
            opened = false;
        } catch (Throwable e) {
            throw new DBCException(e, executionContext);
        }
    }

    private InputStream getInputStream() throws DBCException {
        try {
            return (InputStream) BeanUtils.invokeObjectMethod(
                bfile,
                "getBinaryStream");
        } catch (Throwable e) {
            throw new DBCException("Error when reading BFILE length", e, executionContext);
        }
    }

    @NotNull
    @Override
    public String getContentType()
    {
        return MimeTypes.OCTET_STREAM;
    }

    @Override
    public DBDContentStorage getContents(@NotNull DBRProgressMonitor monitor)
        throws DBCException
    {
        if (storage == null && bfile != null) {
            try {
                openFile();
                long contentLength = getContentLength();
                DBPPlatform platform = DBWorkbench.getPlatform();
                if (contentLength < platform.getPreferenceStore().getInt(ModelPreferences.MEMORY_CONTENT_MAX_SIZE)) {
                    try {
                        try (InputStream bs = getInputStream()) {
                            storage = BytesContentStorage.createFromStream(
                                bs,
                                contentLength,
                                getDefaultEncoding());
                        }
                    } catch (IOException e) {
                        throw new DBCException("IO error while reading content", e);
                    }
                } else {
                    // Create new local storage
                    Path tempFile;
                    try {
                        tempFile = ContentUtils.createTempContentFile(monitor, platform, "blob" + bfile.hashCode());
                    } catch (IOException e) {
                        throw new DBCException("Can't create temporary file", e);
                    }
                    try (OutputStream os = Files.newOutputStream(tempFile)) {
                        try (InputStream bs = getInputStream()) {
                            ContentUtils.copyStreams(bs, contentLength, os, monitor);
                        }
                    } catch (IOException e) {
                        ContentUtils.deleteTempFile(tempFile);
                        throw new DBCException("IO error while copying stream", e);
                    } catch (Throwable e) {
                        ContentUtils.deleteTempFile(tempFile);
                        throw new DBCException(e, executionContext);
                    }
                    this.storage = new TemporaryContentStorage(platform, tempFile, getDefaultEncoding(), true);
                }
                // Free blob - we don't need it anymore
                releaseBlob();
            }
            finally {
                closeFile();
            }
        }
        return storage;
    }

    @Override
    public void release()
    {
        releaseBlob();
        super.release();
    }

    private void releaseBlob() {
        if (bfile != null) {
            bfile = null;
        }
    }

    @Override
    public void bindParameter(JDBCSession session, JDBCPreparedStatement preparedStatement, DBSTypedObject columnType, int paramIndex)
        throws DBCException
    {
        throw new DBCException("BFILE update not supported");
    }

    @Nullable
    @Override
    public Object getRawValue() {
        return bfile;
    }

    @Override
    public boolean isNull()
    {
        return bfile == null && storage == null;
    }

    @Override
    protected JDBCContentLOB createNewContent()
    {
        return new OracleContentBFILE(executionContext, null);
    }

    @Nullable
    @Override
    public String getDisplayString(@NotNull DBDDisplayFormat format)
    {
        return bfile == null && storage == null ? null : "[BFILE:" + name + "]";
    }

}
