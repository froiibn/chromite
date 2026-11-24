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
package com.seizia.chromite.ext.mysql.model;

import org.jkiss.code.NotNull;
import com.seizia.chromite.DBDatabaseException;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.mysql.MySQLConstants;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPRefreshableObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.struct.AbstractTrigger;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSActionTiming;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSManipulationType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQLTrigger
 *
 * 설계: DDL 글({@link #getObjectDefinitionText})은 서버가 기억하는 원래 문장(SHOW CREATE TRIGGER의 SQL Original Statement)이고,
 * 트리거 이름에 DB 이름이 없으면 붙인다. 프로시저의 DDL 글이 이름에 DB를 붙이는 것({@code MySQLProcedure#normalizeCreateStatement})과 같다.
 * DB를 붙인 트리거의 표({@code ON 표})는 서버가 그 DB에서 찾으므로, 어느 DB의 쿼리 창에 붙여 실행해도 이 트리거의 DB에 만들어진다.
 *
 * 변경 이력:
 * - 2026-11-25 Chromite: DDL 글의 트리거 이름에 DB 이름을 붙임. DB를 고르지 않은 새 쿼리 창에 붙이면 1046(No database selected),
 *   다른 DB의 창이면 그 DB의 같은 이름 표에 만들어졌다(docs/chromite/swing-query-commands.md "저장 루틴 DDL 글을 그대로 실행 (MySQL·MariaDB, SQL Server)").
 */
public class MySQLTrigger extends AbstractTrigger implements MySQLSourceObject, DBPRefreshableObject
{
    // The trigger name after CREATE [OR REPLACE] [DEFINER = user] TRIGGER [IF NOT EXISTS], and the dot that follows a database name
    private static final Pattern TRIGGER_NAME = Pattern.compile(
        "^(.*?\\bTRIGGER\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?)(`(?:[^`]|``)+`|[\\w$]+)(\\s*\\.)?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private MySQLCatalog catalog;
    private MySQLTable table;
    private String body;
    private String charsetClient;
    private String sqlMode;

    public MySQLTrigger(
        MySQLCatalog catalog,
        MySQLTable table,
        ResultSet dbResult)
    {
        super(JDBCUtils.safeGetString(dbResult, "TRIGGER_NAME"), null, true);
        this.catalog = catalog;
        this.table = table;

        setManipulationType(DBSManipulationType.getByName(JDBCUtils.safeGetString(dbResult, "EVENT_MANIPULATION")));
        setActionTiming(DBSActionTiming.getByName(JDBCUtils.safeGetString(dbResult, "ACTION_TIMING")));
        this.charsetClient = JDBCUtils.safeGetString(dbResult, "CHARACTER_SET_CLIENT");
        this.sqlMode = JDBCUtils.safeGetString(dbResult, MySQLConstants.COL_TRIGGER_SQL_MODE);
    }

    public MySQLTrigger(
        MySQLCatalog catalog,
        MySQLTable table,
        String name)
    {
        super(name, null, false);
        this.catalog = catalog;
        this.table = table;

        setActionTiming(DBSActionTiming.AFTER);
        setManipulationType(DBSManipulationType.INSERT);
        this.body = "";
    }

    public MySQLTrigger(MySQLCatalog catalog, MySQLTable table, MySQLTrigger source) {
        super(source.name, source.getDescription(), false);
        this.catalog = catalog;
        this.table = table;
        this.body = source.body;
        this.charsetClient = source.charsetClient;
        this.sqlMode = source.sqlMode;
    }

    @Property(viewable = true, order = 2, listProvider = TriggerTimingListProvider.class)
    public DBSActionTiming getActionTiming()
    {
        return super.getActionTiming();
    }

    @Property(viewable = true, order = 3, listProvider = TriggerTypeListProvider.class)
    public DBSManipulationType getManipulationType()
    {
        return super.getManipulationType();
    }

    public String getBody()
    {
        return body;
    }

    public MySQLCatalog getCatalog() {
        return catalog;
    }

    @Override
    @Property(viewable = true, order = 4)
    public MySQLTable getTable()
    {
        return table;
    }

    @Property(order = 5)
    public String getCharsetClient()
    {
        return charsetClient;
    }

    @Property(order = 6)
    public String getSqlMode()
    {
        return sqlMode;
    }

    @Override
    public MySQLTable getParentObject()
    {
        return table;
    }

    @NotNull
    @Override
    public MySQLDataSource getDataSource()
    {
        return catalog.getDataSource();
    }

    @NotNull
    @Override
    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException
    {
        if (body == null) {
            try (JDBCSession session = DBUtils.openMetaSession(monitor, this, "Read trigger declaration")) {
                try (JDBCPreparedStatement dbStat = session.prepareStatement("SHOW CREATE TRIGGER " + getFullyQualifiedName(DBPEvaluationContext.DDL))) {
                    try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                        if (dbResult.next()) {
                            body = qualifyTriggerName(JDBCUtils.safeGetString(dbResult, "SQL Original Statement"));
                        } else {
                            body = "-- Trigger definition not found in catalog";
                        }
                    }
                }
            } catch (SQLException e) {
                body = "-- " + e.getMessage();
                throw new DBDatabaseException(e, getDataSource());
            }
        }
        return body;
    }

    /**
     * The statement with the trigger's database before the trigger name, unless the statement names a database already.
     */
    private String qualifyTriggerName(String statement) {
        Matcher matcher = statement == null ? null : TRIGGER_NAME.matcher(statement);
        if (matcher == null || !matcher.find() || matcher.group(3) != null) {
            return statement;
        }
        return statement.substring(0, matcher.start(2)) + DBUtils.getQuotedIdentifier(catalog) + "." + statement.substring(matcher.start(2));
    }

    @Override
    public void setObjectDefinitionText(String sourceText)
    {
        body = sourceText;
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(getDataSource(),
            catalog,
            this);
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getCatalog().triggerCache.refreshObject(monitor, getCatalog(), this);
    }
}
