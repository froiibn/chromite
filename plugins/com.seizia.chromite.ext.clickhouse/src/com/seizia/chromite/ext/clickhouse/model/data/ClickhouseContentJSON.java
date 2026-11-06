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
package com.seizia.chromite.ext.clickhouse.model.data;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.jdbc.data.JDBCContentChars;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.utils.MimeTypes;

/**
 * ClickHouse JSON content: exposes the JSON text (produced by the driver's {@code getString()} when
 * the session uses {@code output_format_binary_write_json_as_string=1}) through the JSON viewer via
 * the {@code text/json} content type. Edits are bound back as a plain string by the inherited
 * {@link JDBCContentChars} handling; ClickHouse applies them as an {@code ALTER TABLE ... UPDATE}.
 */
public class ClickhouseContentJSON extends JDBCContentChars {

    public ClickhouseContentJSON(DBCExecutionContext executionContext, String json) {
        super(executionContext, json);
    }

    private ClickhouseContentJSON(ClickhouseContentJSON copyFrom) {
        super(copyFrom);
    }

    @NotNull
    @Override
    public String getContentType() {
        return MimeTypes.TEXT_JSON;
    }

    @NotNull
    @Override
    public ClickhouseContentJSON cloneValue(@NotNull DBRProgressMonitor monitor) {
        return new ClickhouseContentJSON(this);
    }
}
