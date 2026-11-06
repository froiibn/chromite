/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

package com.seizia.chromite.ext.gbase8s.model;

import com.seizia.chromite.ext.generic.model.GenericCatalog;
import com.seizia.chromite.ext.generic.model.GenericDataSource;
import com.seizia.chromite.model.DBPSystemObject;

/**
 * @author Chao Tian
 */
public class GBase8sCatalog extends GenericCatalog implements DBPSystemObject {

    public GBase8sCatalog(GenericDataSource dataSource, String catalogName) {
        super(dataSource, catalogName);
    }

    @Override
    public boolean isSystem() {
        return switch (getName().toLowerCase()) {
        case "sys", "sysadmin", "sysmaster", "sysuser", "sysutils" -> true;
        default -> false;
        };
    }

}
