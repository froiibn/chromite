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

package com.seizia.chromite.ext.oceanbase.model.auth;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.impl.auth.AuthModelDatabaseNative;
import com.seizia.chromite.model.impl.auth.AuthModelDatabaseNativeCredentials;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.Properties;

public class OceanbaseAuthModelDatabaseNative extends AuthModelDatabaseNative<AuthModelDatabaseNativeCredentials> {

    public static final String ID = "oceanbase_native";

    @Override
    public Object initAuthentication(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSource dataSource,
            @NotNull AuthModelDatabaseNativeCredentials credentials, @NotNull DBPConnectionConfiguration configuration,
            @NotNull Properties connProperties) throws DBException {
        String userName = configuration.getUserName();
        if (!CommonUtils.isEmpty(userName) && !userName.contains("@")) {
            userName += "@" + configuration.getServerName();
        }

        credentials.setUserName(userName);
        return super.initAuthentication(monitor, dataSource, credentials, configuration, connProperties);
    }

}
