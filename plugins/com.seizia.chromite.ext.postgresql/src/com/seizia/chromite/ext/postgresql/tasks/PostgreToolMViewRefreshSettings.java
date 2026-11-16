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
package com.seizia.chromite.ext.postgresql.tasks;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.postgresql.model.PostgreMaterializedView;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.sql.task.SQLToolExecuteSettings;

import java.util.Map;

public class PostgreToolMViewRefreshSettings extends SQLToolExecuteSettings<PostgreMaterializedView> {
    private boolean isWithData;

    @Property(viewable = true, editable = true, updatable = true)
    public boolean isWithData() {
        return isWithData;
    }

    public void setWithData(boolean withData) {
        isWithData = withData;
    }

    @Override
    public void loadConfiguration(@NotNull DBRRunnableContext runnableContext, @NotNull Map<String, Object> config, @NotNull DBPProject project) {
        super.loadConfiguration(runnableContext, config, project);
        isWithData = JSONUtils.getBoolean(config, "with_data");
    }

    @Override
    public void saveConfiguration(Map<String, Object> config) {
        super.saveConfiguration(config);
        config.put("with_data", isWithData);
    }
}
