/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.ext.mssql.model.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An operator as the operator dialog edits it (name, enabled, e-mail name), and the msdb procedure calls that create,
 * change or drop it.
 *
 * 설계: SSMS 새 운영자 창의 일반 페이지 중 전자 메일만 다룬다(호출기·net send는 SQL Server에서 더 이상 쓰지 않는 기능이다).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public final class SQLServerAgentOperatorDefinition {

    /** null for a new operator */
    @Nullable
    private final String originalName;
    private String name;
    private boolean enabled;
    @Nullable
    private String emailAddress;

    private SQLServerAgentOperatorDefinition(@Nullable String originalName, @NotNull String name, boolean enabled, @Nullable String emailAddress) {
        this.originalName = originalName;
        this.name = name;
        this.enabled = enabled;
        this.emailAddress = emailAddress;
    }

    @NotNull
    public static SQLServerAgentOperatorDefinition newOperator() {
        return new SQLServerAgentOperatorDefinition(null, "", true, null); //$NON-NLS-1$
    }

    @NotNull
    public static SQLServerAgentOperatorDefinition of(@NotNull SQLServerAgentOperator operator) {
        return new SQLServerAgentOperatorDefinition(operator.getName(), operator.getName(), operator.isEnabled(), operator.getEmailAddress());
    }

    @NotNull
    public SQLServerAgentOperatorDefinition copy() {
        return new SQLServerAgentOperatorDefinition(originalName, name, enabled, emailAddress);
    }

    /**
     * Statements that create the operator, or change it from the original definition (empty if nothing changed).
     */
    @NotNull
    public List<String> generateStatements(@Nullable SQLServerAgentOperatorDefinition original) {
        List<String> statements = new ArrayList<>();
        if (original == null || originalName == null) {
            SQLServerAgentSql.Call call = new SQLServerAgentSql.Call("sp_add_operator") //$NON-NLS-1$
                .arg("name", name) //$NON-NLS-1$
                .arg("enabled", enabled); //$NON-NLS-1$
            if (!CommonUtils.isEmpty(emailAddress)) {
                call.arg("email_address", emailAddress); //$NON-NLS-1$
            }
            statements.add(call.toSql());
            return statements;
        }
        SQLServerAgentSql.Call update = new SQLServerAgentSql.Call("sp_update_operator").arg("name", originalName); //$NON-NLS-1$ //$NON-NLS-2$
        if (!name.equals(original.name)) {
            update.arg("new_name", name); //$NON-NLS-1$
        }
        if (enabled != original.enabled) {
            update.arg("enabled", enabled); //$NON-NLS-1$
        }
        if (!Objects.equals(CommonUtils.notEmpty(emailAddress), CommonUtils.notEmpty(original.emailAddress))) {
            // An empty string clears the address
            update.arg("email_address", CommonUtils.notEmpty(emailAddress)); //$NON-NLS-1$
        }
        if (update.hasArguments(1)) {
            statements.add(update.toSql());
        }
        return statements;
    }

    @NotNull
    public static String generateDropStatement(@NotNull String operatorName) {
        return new SQLServerAgentSql.Call("sp_delete_operator").arg("name", operatorName).toSql(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public boolean isNew() {
        return originalName == null;
    }

    @NotNull
    public String getName() {
        return name;
    }

    public void setName(@NotNull String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Nullable
    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(@Nullable String emailAddress) {
        this.emailAddress = emailAddress;
    }
}
