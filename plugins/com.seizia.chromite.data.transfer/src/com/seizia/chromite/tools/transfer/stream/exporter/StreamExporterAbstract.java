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
package com.seizia.chromite.tools.transfer.stream.exporter;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.tools.transfer.stream.IStreamDataExporter;
import com.seizia.chromite.tools.transfer.stream.IStreamDataExporterSite;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

/**
 * Abstract Exporter
 */
public abstract class StreamExporterAbstract implements IStreamDataExporter {

    private IStreamDataExporterSite site;
    private DBDDisplayFormat exportFormat;

    public IStreamDataExporterSite getSite()
    {
        return site;
    }

    protected PrintWriter getWriter() {
        return site.getWriter();
    }

    protected OutputStream getOutputStream() {
        return site.getOutputStream();
    }

    @Override
    public void init(IStreamDataExporterSite site) throws DBException
    {
        this.site = site;
    }

    @Override
    public void dispose() throws IOException
    {
        // do nothing
    }

    protected String getValueDisplayString(
        DBDAttributeBinding column,
        Object value)
    {
        final DBDValueHandler valueHandler = column.getValueHandler();
        return valueHandler.getValueDisplayString(column, value, getValueExportFormat(column));
    }

    protected DBDDisplayFormat getValueExportFormat(DBDAttributeBinding column) {
        if (this.exportFormat == null) {
            this.exportFormat = getSite().getExportFormat();
        }
        return this.exportFormat;
    }

}