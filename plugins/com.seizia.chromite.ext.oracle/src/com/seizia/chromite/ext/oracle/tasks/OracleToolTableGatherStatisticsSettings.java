/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.oracle.tasks;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.meta.IPropertyValueValidator;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.sql.task.SQLToolExecuteSettings;
import com.seizia.chromite.model.struct.DBSObject;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

public class OracleToolTableGatherStatisticsSettings extends SQLToolExecuteSettings<DBSObject> {
    private int samplePercent;

    @Property(viewable = true, editable = true, updatable = true, valueValidator = OracleStatisticPercentLimiter.class)
    public int getSamplePercent() {
        return samplePercent;
    }

    public void setSamplePercent(int samplePercent) {
        this.samplePercent = samplePercent;
    }

    @Override
    public void loadConfiguration(@NotNull DBRRunnableContext runnableContext, @NotNull Map<String, Object> config, @NotNull DBPProject project) {
        super.loadConfiguration(runnableContext, config, project);
        samplePercent = JSONUtils.getInteger(config, "sample_percent");
    }

    @Override
    public void saveConfiguration(Map<String, Object> config) {
        super.saveConfiguration(config);
        config.put("sample_percent", samplePercent);
    }

    public static class OracleStatisticPercentLimiter implements IPropertyValueValidator<OracleToolTableGatherStatisticsSettings, Object> {

        @Override
        public boolean isValidValue(@NotNull OracleToolTableGatherStatisticsSettings object, @Nullable Object value) throws IllegalArgumentException {
            int valueInt = CommonUtils.toInt(value);
            return 0 <= valueInt && valueInt <= 100;
        }
    }
}
