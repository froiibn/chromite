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
package com.seizia.chromite.ext.yashandb.edit;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.oracle.edit.OracleMaterializedViewManager;
import com.seizia.chromite.ext.oracle.model.OracleMaterializedView;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.yashandb.model.YashanDBMaterializedView;

/**
 * YashanDBMaterializedViewManager
 */
public class YashanDBMaterializedViewManager extends OracleMaterializedViewManager {

    @NotNull
    @Override
    protected OracleMaterializedView createMaterializedView(@NotNull OracleSchema schema) {
        return new YashanDBMaterializedView(schema, "NEW_MVIEW"); //$NON-NLS-1$
    }
}
