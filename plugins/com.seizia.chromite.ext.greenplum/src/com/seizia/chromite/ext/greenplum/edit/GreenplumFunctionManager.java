/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2019 Dmitriy Dubson (ddubson@pivotal.io)
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
package com.seizia.chromite.ext.greenplum.edit;

import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.postgresql.edit.PostgreProcedureManager;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedure;
import com.seizia.chromite.ext.postgresql.model.PostgreSchema;
import com.seizia.chromite.model.struct.cache.DBSObjectCache;

public class GreenplumFunctionManager extends PostgreProcedureManager {
    @Nullable
    @Override
    public DBSObjectCache<PostgreSchema, PostgreProcedure> getObjectsCache(PostgreProcedure object)
    {
        return object.getContainer().getProceduresCache();
    }
}
