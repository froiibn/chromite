/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2017-2018 Andrew Khitrin (ahitrin@gmail.com)
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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
package com.seizia.chromite.ext.postgresql.debug.internal.impl;

import com.seizia.chromite.debug.DBGController;
import com.seizia.chromite.debug.DBGControllerFactory;
import com.seizia.chromite.debug.DBGException;
import com.seizia.chromite.ext.postgresql.debug.core.PostgreSqlDebugCore;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedure;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.code.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 연결 대신 함수로 컨트롤러를 만듦 (docs/chromite/procedure-debugger.md)
 */
public class PostgreDebugControllerFactory implements DBGControllerFactory {

    @NotNull
    @Override
    public DBGController createController(@NotNull DBSObject routine, @NotNull Map<String, Object> configuration) throws DBGException {
        if (!(routine instanceof PostgreProcedure procedure)) {
            throw new DBGException("Not a PostgreSQL function: " + routine.getName());
        }
        Map<String, Object> fullConfiguration = new HashMap<>(configuration);
        PostgreSqlDebugCore.saveFunction(procedure, fullConfiguration);
        return new PostgreDebugController(procedure.getDataSource().getContainer(), fullConfiguration, procedure.getObjectId());
    }

}
