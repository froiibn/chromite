/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.oracle.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.access.DBAUserPasswordManager;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.sql.SQLException;

/**
 * Changes the password of the connected Oracle user ({@code ALTER USER … IDENTIFIED BY … REPLACE …}).
 *
 * 설계: 새 암호와 옛 암호는 {@link OracleUtils#quotePassword}로 늘 큰따옴표로 감싼다. 식별자용
 * {@code DBUtils.getQuotedIdentifier}는 이미 따옴표로 싸인 글을 그대로 두어 {@code "abc"}를 친 사용자의 암호가 {@code abc}가 됐다.
 * 문장은 쿼리 관리자 기록에서 뺀다({@code enableLogging(false)}).
 *
 * 변경 이력:
 * - 2026-11-08 암호를 식별자가 아니라 암호로 따옴표 처리, 큰따옴표가 든 암호는 보내기 전에 거절 (docs/chromite/password-handling.md)
 */
public class OracleChangeUserPasswordManager implements DBAUserPasswordManager {

    private OracleDataSource dataSource;

    OracleChangeUserPasswordManager(OracleDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void changeUserPassword(@NotNull DBRProgressMonitor monitor, @NotNull String userName, @NotNull String newPassword, @NotNull String oldPassword) throws DBException {
        String quotedNewPassword = OracleUtils.quotePassword(CommonUtils.notEmpty(newPassword));
        String quotedOldPassword = OracleUtils.quotePassword(CommonUtils.notEmpty(oldPassword));
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Change user password")) {
            session.enableLogging(false);
            String transformedUserName = DBObjectNameCaseTransformer.transformName(dataSource, userName);
            JDBCUtils.executeSQL(session, "ALTER USER " + DBUtils.getQuotedIdentifier(dataSource,
                transformedUserName != null ? transformedUserName : userName) + " IDENTIFIED BY " +
                quotedNewPassword + " REPLACE " + quotedOldPassword);
        } catch (SQLException e) {
            throw new DBCException("Error changing user password", e);
        }
    }
}
