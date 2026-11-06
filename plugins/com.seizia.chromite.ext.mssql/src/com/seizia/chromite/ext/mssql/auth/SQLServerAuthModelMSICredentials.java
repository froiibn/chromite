/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp
 * Modifications Copyright (C) 2026 Chromite contributors
 *
 * All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of DBeaver Corp and its suppliers, if any.
 * The intellectual and technical concepts contained
 * herein are proprietary to DBeaver Corp and its suppliers
 * and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from DBeaver Corp.
 */
package com.seizia.chromite.ext.mssql.auth;

import com.seizia.chromite.model.impl.auth.AuthModelDatabaseNativeCredentials;
import com.seizia.chromite.model.meta.Property;

public class SQLServerAuthModelMSICredentials extends AuthModelDatabaseNativeCredentials {

    public SQLServerAuthModelMSICredentials() {
    }

    @Override
    @Property(hidden = true)
    public String getUserPassword() {
        return super.getUserPassword();
    }

}
