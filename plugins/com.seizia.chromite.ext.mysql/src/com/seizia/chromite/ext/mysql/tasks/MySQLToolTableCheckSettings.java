/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.mysql.tasks;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mysql.model.MySQLTableBase;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.meta.IPropertyValueListProvider;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.sql.task.SQLToolExecuteSettings;

import java.util.Map;

/**
 * Table check settings
 */
public class MySQLToolTableCheckSettings extends SQLToolExecuteSettings<MySQLTableBase> {
    private String option;

    @Property(viewable = true, editable = true, updatable = true, listProvider = CheckOptionListProvider.class)
    public String getOption() {
        return option;
    }

    public void setOption(String option) {
        this.option = option;
    }

    @Override
    public void loadConfiguration(@NotNull DBRRunnableContext runnableContext, @NotNull Map<String, Object> config, @NotNull DBPProject project) {
        super.loadConfiguration(runnableContext, config, project);
        option = JSONUtils.getString(config, "option");
    }

    @Override
    public void saveConfiguration(Map<String, Object> config) {
        super.saveConfiguration(config);
        config.put("option", option);
    }

    public static class CheckOptionListProvider implements IPropertyValueListProvider<MySQLToolTableCheckSettings> {

        @Override
        public boolean allowCustomValue() {
            return false;
        }

        @Nullable
        @Override
        public Object[] getPossibleValues(MySQLToolTableCheckSettings object) {
            return new String[] {
                "",
                "FOR UPGRADE",
                "QUICK",
                "FAST",
                "MEDIUM",
                "EXTENDED",
                "CHANGED"
            };
        }
    }

}
