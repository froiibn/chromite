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
package com.seizia.chromite.ext.altibase.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.altibase.AltibaseConstants;
import com.seizia.chromite.ext.generic.model.GenericCatalog;
import com.seizia.chromite.ext.generic.model.GenericPackage;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.model.DBPStatefulObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectState;

import java.sql.SQLException;
import java.util.Map;

/**
 * Altibase package: specification and body in one DDL text.
 *
 * 설계: DDL 글은 머리와 본문을 차례로 둔 스크립트다. 쿼리 창에 붙여 실행하면 두 문장으로 나뉘어야 하므로 머리 글은 {@code ;}로 끝낸다.
 *
 * 변경 이력:
 * - 2026-11-24 Chromite: 머리 글이 {@code ;}로 끝나지 않으면 붙임({@link #terminated}), 머리·본문 이름 줄을 상수로. 쿼리 창이 머리를
 *   {@code END}까지만 보내 서버에 그렇게 저장된 패키지는 DDL 보기 글의 머리와 본문이 한 문장이 되어 두 번째 실행에서 구문 오류가 났다
 *   (앱 변경 182, docs/chromite/altibase.md "저장 루틴의 문장 나누기")
 */
public class AltibasePackage extends GenericPackage implements DBPStatefulObject {

    protected String schemaName;
    private String source;

    private boolean valid; // 0: Valid, 1: Invalid
    private boolean hasBody;

    public AltibasePackage(GenericStructContainer container, String packageName, JDBCResultSet dbResult) {
        super(container, packageName, true);
        
        schemaName = container.getName();
        valid = JDBCUtils.safeGetBoolean(dbResult, "STATUS", "0");
    }

    public void setBody(boolean hasBody) {
        this.hasBody = hasBody;
    }
    
    public void setStatus(boolean valid) {
        this.valid = this.valid && valid;
    }

    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        if (source == null) {
            source = AltibaseConstants.PACKAGE_SPEC_HEADER
                    + AltibaseConstants.NEW_LINE 
                    + terminated(((AltibaseMetaModel) getDataSource().getMetaModel())
                        .getPackageDDL(monitor, this, AltibaseConstants.PACKAGE_TYPE_SPEC))
                    + AltibaseConstants.NEW_LINE 
                    + AltibaseConstants.PACKAGE_BODY_HEADER
                    + AltibaseConstants.NEW_LINE;

            if (hasBody) {
                source += ((AltibaseMetaModel) getDataSource().getMetaModel())
                        .getPackageDDL(monitor, this, AltibaseConstants.PACKAGE_TYPE_BODY);
            } else {
                source += "-- No body definition";
            }
        }
        return source;
    }

    /**
     * The specification ending in ";", so that the body after it in this text is a statement of its own. The server keeps the
     * text it was sent as the source: one sent without the ";" after END (as the query window did before app change 182) is
     * stored that way.
     */
    @NotNull
    private static String terminated(@NotNull String specification) {
        String text = specification.stripTrailing();
        String lastLine = text.substring(text.lastIndexOf('\n') + 1).strip();
        if (text.isEmpty() || text.endsWith(";") || lastLine.startsWith("--")) {
            return specification;
        }
        return text + ";";
    }

    @Property(viewable = true, order = 5)
    public boolean isValid() {
        return valid;
    }
    
    @Override
    @Property(viewable = false, hidden = true, order = 3, labelProvider = GenericCatalog.CatalogNameTermProvider.class)
    public GenericCatalog getCatalog() {
        return super.getCatalog();
    }
    
    public void refreshState(JDBCSession session) throws DBCException {
        // Check package body status only because package spec. cannot be invalid.
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
                "SELECT status FROM system_.sys_users_ u, system_.sys_packages_ p"
                        + " WHERE p.package_type = 7 AND u.user_id = p.user_id AND u.user_name = ? AND package_name = ?")) {
            dbStat.setString(1, schemaName);
            dbStat.setString(2, getName());

            dbStat.executeStatement();

            try (JDBCResultSet dbResult = dbStat.getResultSet()) {
                if (dbResult != null && dbResult.next()) {
                    valid = JDBCUtils.safeGetBoolean(dbResult, 1, "0"); // 0 is Valid, 1 is invalid
                }
            }
        } catch (SQLException e) {
            throw new DBCException(e, session.getExecutionContext());
        }
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) throws DBCException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, 
                "Refresh state of package '" + this.getName() + "'")) {
            refreshState(session);
        }
    }
    
    @Nullable
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, this, 
                "Refresh state of package '" + this.getName() + "'")) {
            refreshState(session);
        }
        
        return this;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return valid ? DBSObjectState.NORMAL : DBSObjectState.INVALID;
    }
}
