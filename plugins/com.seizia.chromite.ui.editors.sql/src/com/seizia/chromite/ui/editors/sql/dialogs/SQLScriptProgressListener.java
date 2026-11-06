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
package com.seizia.chromite.ui.editors.sql.dialogs;

import org.eclipse.core.runtime.jobs.Job;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.Collection;

/**
 * Script progress listener
 */
public interface SQLScriptProgressListener<T extends DBSObject> {

    void beginScriptProcessing(Job job, Collection<T> objects);

    void endScriptProcessing();

    void beginObjectProcessing(@NotNull T object, int objectNumber);

    void endObjectProcessing(@NotNull T object, Exception error);

    void processObjectResults(@NotNull T object, @Nullable DBCStatement statement, @Nullable DBCResultSet resultSet) throws DBCException;

}
