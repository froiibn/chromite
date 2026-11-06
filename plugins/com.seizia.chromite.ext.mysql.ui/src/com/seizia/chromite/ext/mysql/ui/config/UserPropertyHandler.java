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
package com.seizia.chromite.ext.mysql.ui.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mysql.model.MySQLUser;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.prop.DBEPropertyHandler;
import com.seizia.chromite.model.edit.prop.DBEPropertyReflector;
import org.jkiss.utils.CommonUtils;

/**
* User property handler
*/
public enum UserPropertyHandler implements DBEPropertyHandler<MySQLUser>, DBEPropertyReflector<MySQLUser> {
    NAME,
    HOST,
    PASSWORD,
    PASSWORD_CONFIRM,
    MAX_QUERIES,
    MAX_UPDATES,
    MAX_CONNECTIONS,
    MAX_USER_CONNECTIONS;


    @NotNull
    @Override
    public String getId()
    {
        return name();
    }

    @NotNull
    @Override
    public MySQLCommandChangeUser createCompositeCommand(@NotNull MySQLUser object)
    {
        return new MySQLCommandChangeUser(object);
    }

    @Override
    public void reflectValueChange(@NotNull MySQLUser object, @Nullable Object oldValue, @Nullable Object newValue)
    {
        if (this == NAME || this == HOST) {
            if (this == NAME) {
                object.setUserName(CommonUtils.toString(newValue));
            } else {
                object.setHost(CommonUtils.toString(newValue));
            }
            DBUtils.fireObjectUpdate(object);
        }
    }
}
