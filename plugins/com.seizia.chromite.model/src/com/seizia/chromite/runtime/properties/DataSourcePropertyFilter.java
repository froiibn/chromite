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
package com.seizia.chromite.runtime.properties;

import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Datasource property filter
 */
public class DataSourcePropertyFilter implements IPropertyFilter {

    private final static Set<String> readExpPropertiesFor = new LinkedHashSet<>();

    private final boolean showExpensive;

    public DataSourcePropertyFilter()
    {
        this((DBPDataSourceContainer)null);
    }
    public DataSourcePropertyFilter(DBPDataSource dataSource)
    {
        this(dataSource == null ? null : dataSource.getContainer());
    }

    public DataSourcePropertyFilter(DBPDataSourceContainer container)
    {
        DBPPreferenceStore store = container != null ?
            container.getPreferenceStore() :
            DBWorkbench.getPlatform().getPreferenceStore();
        this.showExpensive = store.getBoolean(ModelPreferences.READ_EXPENSIVE_PROPERTIES);
    }

    @Override
    public boolean select(Object object, DBPPropertyDescriptor property)
    {
        if (property instanceof ObjectPropertyDescriptor) {
            ObjectPropertyDescriptor prop = (ObjectPropertyDescriptor) property;
            if (!prop.isExpensive() || showExpensive) {
                return true;
            }
            if (object instanceof DBSObject) {
                return isExpensivePropertiesReadEnabledFor((DBSObject) object);
            }
            return false;
        }
        return false;
    }

    public static boolean isExpensivePropertiesReadEnabledFor(DBSObject object) {
        synchronized (readExpPropertiesFor) {
            String objectFullId = DBUtils.getObjectFullId(object);
            return readExpPropertiesFor.contains(objectFullId);
        }
    }

    public static void readExpensivePropertiesFor(DBSObject object, boolean read) {
        synchronized (readExpPropertiesFor) {
            String objectFullId = DBUtils.getObjectFullId(object);
            if (read) {
                readExpPropertiesFor.add(objectFullId);
            } else {
                readExpPropertiesFor.remove(objectFullId);
            }
        }
    }
}
