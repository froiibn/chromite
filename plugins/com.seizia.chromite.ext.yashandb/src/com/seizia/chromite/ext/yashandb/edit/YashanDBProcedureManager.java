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
import com.seizia.chromite.ext.oracle.edit.OracleProcedureManager;
import com.seizia.chromite.ext.oracle.model.OracleProcedureStandalone;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.yashandb.model.YashanDBProcedureStandalone;
import com.seizia.chromite.ext.yashandb.model.YashanDBSchema;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;

/**
 * YashanDBProcedureManager
 */
public class YashanDBProcedureManager extends OracleProcedureManager {

    @NotNull
    @Override
    protected OracleProcedureStandalone createProcedure(@NotNull OracleSchema schema) {
        return new YashanDBProcedureStandalone(
            (YashanDBSchema) schema,
            "NEW_PROCEDURE",
            DBSProcedureType.PROCEDURE);
    }
}
