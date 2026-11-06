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
package com.seizia.chromite.ext.mssql.model.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.data.DBDDataFormatter;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.DBDFormatSettings;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCDateTimeValueHandler;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCStringValueHandler;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSTypedObject;
import org.jkiss.utils.BeanUtils;

import java.sql.Timestamp;
import java.text.ParseException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;


/**
 * Value handler of SQL Server datetimeoffset.
 *
 * 설계: values are bound as ISO text with an offset. A ZonedDateTime keeps its offset; a java.sql.Timestamp (an
 * instant, read from timestamptz columns of other databases during data transfer) gets the JVM time zone offset: the
 * driver's setTimestamp stored its local wall time with offset +00:00, which moved the instant by the JVM offset.
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: bind Timestamp values with the JVM offset and ZonedDateTime values with their offset
 *   (Copy Tables timestamptz -> datetimeoffset).
 * - 2026-11-07 Chromite: edited text is no longer turned into NULL when the client formatter can't parse it: native
 *   date/time text is kept as it is, other unparsable text is passed to the server
 *   (docs/chromite/swing-followup-fixes.md "날짜/시간 소수 초").
 */
public class SQLServerDateTimeOffsetHandler extends JDBCDateTimeValueHandler {
    private static final Log log = Log.getLog(JDBCStringValueHandler.class);

    public SQLServerDateTimeOffsetHandler(DBDFormatSettings formatSettings) {
        super(formatSettings);
    }

    /**
     * {@link <a href="https://learn.microsoft.com/en-us/sql/connect/jdbc/reference/datetimeoffset-members?view=sql-server-ver16">...</a>}
     * custom SQL Server type
     */
    @Override
    public Object getValueFromObject(
        @NotNull DBCSession session,
        @NotNull DBSTypedObject type,
        @Nullable Object object,
        boolean copy,
        boolean validateValue
    ) throws DBCException {
        if (
            object != null
            && object.getClass().getName().equals("microsoft.sql.DateTimeOffset")
            && !formatSettings.isUseNativeDateTimeFormat()
        ) {
            try {
                Timestamp timestamp = (Timestamp) BeanUtils.invokeObjectMethod(object, "getTimestamp");
                int offset = (int) BeanUtils.invokeObjectMethod(object, "getMinutesOffset");
                int offsetSeconds = offset * 60;
                if (timestamp == null) {
                    log.debug("Extracted timestamp is null");
                    return null;
                }
                return timestamp.toInstant().atOffset(ZoneOffset.ofTotalSeconds(offsetSeconds));
            } catch (Throwable e) {
                log.debug("error extracting datetimeoffset timestamp", e);
            }
        }
        if (object instanceof String text) {
            if (text.isEmpty()) {
                return null;
            }
            if (session.isUseNativeDateTimeFormat()) {
                // The driver's text (all seven fractional digits) is bound as it is, as DateTimeCustomValueHandler does
                return text;
            }
            DBDDataFormatter formatter = getFormatter(type);
            try {
                return formatter.parseValue(text, OffsetDateTime.class);
            } catch (ParseException e) {
                // The server reads more formats than the client formatter; an unreadable value fails there instead
                // of being silently saved as NULL
                log.debug("Error parsing offset datetime value", e);
                return text;
            }
        }
        return super.getValueFromObject(session, type, object, copy, validateValue);
    }

    @NotNull
    @Override
    public String getValueDisplayString(@NotNull DBSTypedObject column, @Nullable Object value, @NotNull DBDDisplayFormat format) {
        if (format == DBDDisplayFormat.NATIVE && value instanceof OffsetDateTime offsetDateTime) {
            return SQLUtils.quoteString(column, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(offsetDateTime));
        }
        return super.getValueDisplayString(column, value, format);
    }

    @NotNull
    @Override
    protected String getFormatterId(DBSTypedObject column) {
        return DBDDataFormatter.TYPE_NAME_TIMESTAMP_TZ;
    }

    @NotNull
    @Override
    protected DBDDataFormatter getFormatter(DBSTypedObject column) {
        return super.getFormatter(column);
    }

    @Override
    public void bindValueObject(
        @NotNull DBCSession session,
        @NotNull DBCStatement statement,
        @NotNull DBSTypedObject type,
        int index,
        @Nullable Object value
    ) throws DBCException {
        if (value instanceof ZonedDateTime zonedDateTime) {
            value = zonedDateTime.toOffsetDateTime();
        } else if (value instanceof Timestamp timestamp) {
            value = timestamp.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (value instanceof OffsetDateTime) {
            String s = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(((OffsetDateTime) value));
            super.bindValueObject(session, statement, type, index, s);
            return;
        }
        super.bindValueObject(session, statement, type, index, value);
    }
}
