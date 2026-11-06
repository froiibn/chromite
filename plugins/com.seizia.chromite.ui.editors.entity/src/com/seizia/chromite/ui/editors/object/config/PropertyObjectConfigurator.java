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

package com.seizia.chromite.ui.editors.object.config;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.editors.object.struct.PropertyObjectEditPage;

import java.util.Map;

/**
 * Property object configurator
 */
public class PropertyObjectConfigurator implements DBEObjectConfigurator<DBSObject> {
    @NotNull
    @Override
    public DBSObject configureObject(
        @NotNull DBRProgressMonitor monitor,
        @Nullable DBECommandContext commandContext,
        @Nullable Object table,
        @NotNull DBSObject object,
        @NotNull Map<String, Object> options
    ) {
        return UITask.run(() -> {
            final PropertyObjectEditPage<?> page = new PropertyObjectEditPage<>(commandContext, object);
            if (!page.edit()) {
                return null;
            }
            return object;
        });
    }

}
