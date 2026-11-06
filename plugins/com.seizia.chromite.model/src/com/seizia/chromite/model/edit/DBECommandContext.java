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

package com.seizia.chromite.model.edit;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPContextProvider;
import com.seizia.chromite.model.DBPObject;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.Collection;
import java.util.Map;

/**
 * Command context.
 * Provides facilities for object edit commands, undo/redo, save/revert
 */
public interface DBECommandContext extends DBPContextProvider {

    // Do not use transactions in conect save
    String OPTION_AVOID_TRANSACTIONS = "avoidTransactions";

    boolean isDirty();

    @Nullable
    DBECommand<?> getUndoCommand();

    @Nullable
    DBECommand<?> getRedoCommand();

    void saveChanges(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException;

    void resetChanges(boolean undoCommands);

    void undoCommand();

    void redoCommand();

    @NotNull
    Collection<? extends DBECommand<?>> getFinalCommands();

    @NotNull
    Collection<? extends DBECommand<?>> getUndoCommands();

    @NotNull
    Collection<DBPObject> getEditedObjects();

    void addCommand(@NotNull DBECommand<?> command, @Nullable DBECommandReflector reflector);

    void addCommand(@NotNull DBECommand<?> command, @Nullable DBECommandReflector reflector, boolean execute);

    void addCommand(@NotNull DBECommand<?> command, @Nullable DBECommandReflector reflector, boolean execute, @Nullable DBECommand<?> linkedCommand);

    //void addCommandBatch(List<DBECommand> commands, DBECommandReflector reflector, boolean execute);

    void removeCommand(@NotNull DBECommand<?> command);

    void updateCommand(@NotNull DBECommand<?> command, @Nullable DBECommandReflector commandReflector);

    void addCommandListener(@NotNull DBECommandListener listener);

    void removeCommandListener(@NotNull DBECommandListener listener);

    @NotNull
    Map<Object, Object> getUserParams();

}