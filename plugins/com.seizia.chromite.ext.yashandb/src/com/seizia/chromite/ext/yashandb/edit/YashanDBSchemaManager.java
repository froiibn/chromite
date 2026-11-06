/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.yashandb.edit;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.oracle.edit.OracleSchemaManager;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.yashandb.model.YashanDBDataSource;
import com.seizia.chromite.ext.yashandb.model.YashanDBSchema;

/**
 * YashanDBSchemaManager
 */
public class YashanDBSchemaManager extends OracleSchemaManager {

    @NotNull
    @Override
    protected OracleSchema createSchema(@NotNull OracleDataSource dataSource) {
        return new YashanDBSchema(
            (YashanDBDataSource) dataSource,
            -1,
            "NEW_SCHEMA");
    }
}
