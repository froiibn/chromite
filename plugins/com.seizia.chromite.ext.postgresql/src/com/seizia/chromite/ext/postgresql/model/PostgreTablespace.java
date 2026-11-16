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
package com.seizia.chromite.ext.postgresql.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.postgresql.PostgreUtils;
import com.seizia.chromite.model.DBPNamedObject2;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.IPropertyValueListProvider;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * PostgreTablespace
 *
 * 설계: 이름 바꾸기는 탐색기의 이름 바꾸기(F2)가 쓰도록 {@link DBPNamedObject2}다(관리자의 반영기가 이름과 캐시를 바꾼다).
 * 소유자와 옵션은 데이터베이스 소유자(Owner)처럼 속성 창에서 고치는 속성이다(editable·updatable, 소유자는 역할 목록).
 * 옵션 칸은 "이름=값"을 쉼표로 이은 글이라 무엇이 바뀌었는지 알려면 서버에 있는 옵션이 필요하다. 속성 명령은 새 값만 들고 있으므로
 * 서버의 옵션을 {@link #getSavedOptions}로 따로 두고, 관리자가 그와 비교해 바뀐 옵션만 SET, 빠진 옵션만 RESET한다.
 * 시스템 테이블스페이스(pg_default, pg_global)는 이름이 아니라 카탈로그로 가린다: initdb가 만든 개체의 OID는
 * FirstNormalObjectId(16384)보다 작다({@link #isSystemTablespace}).
 *
 * 변경 이력:
 * - 2026-11-15 이름 바꾸기, 소유자·옵션 고치기(앱 변경 #129, docs/chromite/swing-object-properties.md "PostgreSQL 테이블스페이스")
 */
public class PostgreTablespace extends PostgreInformation implements PostgreScriptObject, DBPNamedObject2 {

    private static final Log log = Log.getLog(PostgreTablespace.class);

    /**
     * PostgreSQL's FirstNormalObjectId: objects below it were made by initdb (pg_default 1663, pg_global 1664).
     */
    private static final long FIRST_NORMAL_OBJECT_ID = 16384;

    private long oid;
    private String name;
    private String loc;
    private long ownerId;
    private String options;
    /**
     * The options as the server has them (read from pg_tablespace.spcoptions, then kept after each saved change).
     */
    private Map<String, String> savedOptions = Map.of();

    public PostgreTablespace(PostgreDatabase database, ResultSet dbResult)
        throws SQLException {
        super(database);
        this.loadInfo(dbResult);
    }

    public PostgreTablespace(PostgreDatabase database) {
        super(database);
        this.oid = 0;
        this.name = "newtablespace";
        this.ownerId = 0;
        this.options = "";
        this.loc = "";
    }

    private void loadInfo(ResultSet dbResult) {
        this.oid = JDBCUtils.safeGetLong(dbResult, "oid");
        this.name = JDBCUtils.safeGetString(dbResult, "spcname");
        this.ownerId = JDBCUtils.safeGetLong(dbResult, "spcowner");
        String[] opts = PostgreUtils.safeGetStringArray(dbResult, "spcoptions");
        this.options = opts == null ? "" : String.join(",", opts);
        try {
            this.savedOptions = parseOptions(this.options);
        } catch (DBException e) {
            log.debug("Cannot read the options of tablespace " + name, e);
        }
        if (getDataSource().getServerType().supportsTablespaceLocation()) {
            this.loc = JDBCUtils.safeGetString(dbResult, "loc");
        }
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }

    @Override
    public long getObjectId() {
        return oid;
    }

    @Property(viewable = true, editable = true, updatable = true, order = 2, listProvider = RoleListProvider.class)
    public PostgreRole getOwner(DBRProgressMonitor monitor) throws DBException {
        return getDatabase().getRoleById(monitor, ownerId);
    }

    public void setOwner(@Nullable PostgreRole owner) {
        if (owner != null) {
            this.ownerId = owner.getObjectId();
        }
    }

    @Property(viewable = true, order = 3)
    public String getLoc() {
        return loc;
    }

    @Property(viewable = true, editable = true, updatable = true, order = 4)
    public String getOptions() {
        return options;
    }

    public void setOptions(String options) {
        this.options = options;
    }

    /**
     * The options as the server has them: what a change of {@link #getOptions} is compared with.
     */
    @NotNull
    public Map<String, String> getSavedOptions() {
        return savedOptions;
    }

    /**
     * Called when a statement that changes the options has been executed.
     */
    public void setSavedOptions(@NotNull Map<String, String> savedOptions) {
        this.savedOptions = Map.copyOf(savedOptions);
    }

    /**
     * True for the tablespaces initdb made (pg_default, pg_global): the server keeps them, so they get no rename, owner or
     * option change. A new tablespace (OID 0) is not one.
     */
    public boolean isSystemTablespace() {
        return oid > 0 && oid < FIRST_NORMAL_OBJECT_ID;
    }

    /**
     * The options text (name=value pairs separated by commas, as pg_tablespace.spcoptions is shown) as option names in
     * lower case (the server folds unquoted names) to values, in the order given.
     *
     * @throws DBException a pair without a name or a value
     */
    @NotNull
    public static Map<String, String> parseOptions(@Nullable String text) throws DBException {
        Map<String, String> result = new LinkedHashMap<>();
        if (CommonUtils.isEmptyTrimmed(text)) {
            return result;
        }
        for (String part : text.split(",")) {
            if (part.isBlank()) {
                continue;
            }
            int divider = part.indexOf('=');
            String optionName = divider < 0 ? "" : part.substring(0, divider).trim();
            String value = divider < 0 ? "" : part.substring(divider + 1).trim();
            if (optionName.isEmpty() || value.isEmpty()) {
                throw new DBException("Tablespace option is not name=value: " + part.trim());
            }
            result.put(optionName.toLowerCase(Locale.ROOT), value);
        }
        return result;
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        StringBuilder sb = new StringBuilder("CREATE TABLESPACE ");
        sb.append(DBUtils.getQuotedIdentifier(this)).append(" OWNER ").append(DBUtils.getQuotedIdentifier(getOwner(monitor)));
        if (CommonUtils.isNotEmpty(getLoc())) {
            sb.append(" LOCATION '").append(getLoc()).append("'");
        }
        if (CommonUtils.isNotEmpty(getOptions())) {
            sb.append("\nWITH (").append(getOptions()).append(")");
        }
        return sb.toString();
    }

    @Override
    public void setObjectDefinitionText(String sourceText) throws DBException {

    }

    @Override
    public void setName(@NotNull String name) {
        this.name = name;
    }

    public void setLoc(String loc) {
        this.loc = loc;
    }

    public void setOwnerId(long ownerId) {
        this.ownerId = ownerId;
    }

    /**
     * The roles a tablespace's owner may be changed to (as {@link PostgreDatabase.RoleListProvider} for a database).
     */
    public static class RoleListProvider implements IPropertyValueListProvider<PostgreTablespace> {
        @Override
        public boolean allowCustomValue() {
            return false;
        }

        @Nullable
        @Override
        public Object[] getPossibleValues(PostgreTablespace object) {
            try {
                Collection<? extends PostgreRole> roles = object.getDatabase().getAuthIds(new VoidProgressMonitor());
                return roles.toArray(new Object[0]);
            } catch (DBException e) {
                log.error(e);
                return new Object[0];
            }
        }
    }

}

