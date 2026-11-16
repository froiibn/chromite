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
package com.seizia.chromite.model.data.storage;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.data.DBDContentStorage;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;

/**
 * Stream content storage
 */
public class StreamContentStorage implements DBDContentStorage {

    private final InputStream stream;

    public StreamContentStorage(InputStream stream)
    {
        this.stream = stream;
    }

    @NotNull
    @Override
    public InputStream getContentStream()
        throws IOException
    {
        return stream;
    }

    @NotNull
    @Override
    public Reader getContentReader()
        throws IOException
    {
        return new InputStreamReader(stream, getCharset());
    }

    @Override
    public long getContentLength()
    {
        return -1;
    }

    @NotNull
    @Override
    public String getCharset()
    {
        return GeneralUtils.DEFAULT_ENCODING;
    }

    @NotNull
    @Override
    public DBDContentStorage cloneStorage(@NotNull DBRProgressMonitor monitor)
        throws IOException
    {
        return new StreamContentStorage(stream);
    }

    @Override
    public void release()
    {
        IOUtils.close(stream);
    }

}
