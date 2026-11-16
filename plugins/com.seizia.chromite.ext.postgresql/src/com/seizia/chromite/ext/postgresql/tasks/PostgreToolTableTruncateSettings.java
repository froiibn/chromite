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
package com.seizia.chromite.ext.postgresql.tasks;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.postgresql.model.PostgreTableBase;
import com.seizia.chromite.ext.postgresql.model.impls.PostgreServerExtensionBase;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.data.json.JSONUtils;
import com.seizia.chromite.model.meta.IPropertyValueValidator;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.sql.task.SQLToolExecuteSettings;
import org.jkiss.utils.CommonUtils;

import java.util.List;
import java.util.Map;

public class PostgreToolTableTruncateSettings extends SQLToolExecuteSettings<PostgreTableBase> {

    private boolean isRunning;
    private boolean isOnly;
    private boolean isRestarting;
    private boolean isCascading;

    @Property(viewable = true, editable = true, updatable = true)
    public boolean isRunning() {
        return isRunning;
    }

    public void setRunning(boolean running) {
        isRunning = running;
    }

    @Property(viewable = true, editable = true, updatable = true, visibleIf = PostgreSupportTruncateOptionOnlyValidator.class)
    public boolean isOnly() {
        return isOnly;
    }

    public void setOnly(boolean only) {
        isOnly = only;
    }

    @Property(viewable = true, editable = true, updatable = true, visibleIf = PostgreSupportTruncateOptionIdentityValidator.class)
    public boolean isRestarting() {
        return isRestarting;
    }

    public void setRestarting(boolean restarting) {
        isRestarting = restarting;
    }

    @Property(viewable = true, editable = true, updatable = true, visibleIf = PostgreSupportTruncateOptionCascadeValidator.class)
    public boolean isCascading() {
        return isCascading;
    }

    public void setCascading(boolean cascading) {
        isCascading = cascading;
    }

    @Override
    public void loadConfiguration(@NotNull DBRRunnableContext runnableContext, @NotNull Map<String, Object> config, @NotNull DBPProject project) {
        super.loadConfiguration(runnableContext, config, project);
        isRunning = JSONUtils.getBoolean(config, "run_in_separate_transaction");
        isOnly = JSONUtils.getBoolean(config, "only");
        isRestarting = JSONUtils.getBoolean(config, "restart_identity");
        isCascading = JSONUtils.getBoolean(config, "cascade");
    }

    @Override
    public void saveConfiguration(Map<String, Object> config) {
        super.saveConfiguration(config);
        config.put("run_in_separate_transaction", isRunning());
        config.put("only", isOnly);
        config.put("restart_identity", isRestarting);
        config.put("cascade", isCascading);
    }

    private static boolean isTruncateModeSupported(PostgreToolTableTruncateSettings settings, int mode) {
        List<PostgreTableBase> tablesList = settings.getObjectList();
        if (!CommonUtils.isEmpty(tablesList)) {
            PostgreTableBase tableBase = tablesList.get(0);
            return CommonUtils.isBitSet(tableBase.getDataSource().getServerType().getTruncateToolModes(), mode);
        }
        return false;
    }

    public static class PostgreSupportTruncateOptionOnlyValidator implements IPropertyValueValidator<PostgreToolTableTruncateSettings, Object> {
        @Override
        public boolean isValidValue(@NotNull PostgreToolTableTruncateSettings settings, @Nullable Object value) throws IllegalArgumentException {
            return isTruncateModeSupported(settings, PostgreServerExtensionBase.TRUNCATE_TOOL_MODE_SUPPORT_ONLY_ONE_TABLE);
        }
    }

    public static class PostgreSupportTruncateOptionIdentityValidator implements IPropertyValueValidator<PostgreToolTableTruncateSettings, Object> {
        @Override
        public boolean isValidValue(@NotNull PostgreToolTableTruncateSettings settings, @Nullable Object value) throws IllegalArgumentException {
            return isTruncateModeSupported(settings, PostgreServerExtensionBase.TRUNCATE_TOOL_MODE_SUPPORT_IDENTITIES);
        }
    }

    public static class PostgreSupportTruncateOptionCascadeValidator implements IPropertyValueValidator<PostgreToolTableTruncateSettings, Object> {
        @Override
        public boolean isValidValue(@NotNull PostgreToolTableTruncateSettings settings, @Nullable Object value) throws IllegalArgumentException {
            return isTruncateModeSupported(settings, PostgreServerExtensionBase.TRUNCATE_TOOL_MODE_SUPPORT_CASCADE);
        }
    }
}
