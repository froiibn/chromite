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

package com.seizia.chromite.registry.formatter;

import org.eclipse.core.runtime.IConfigurationElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.data.DBDDataFormatter;
import com.seizia.chromite.model.data.DBDDataFormatterSample;
import com.seizia.chromite.model.impl.AbstractDescriptor;
import com.seizia.chromite.model.impl.PropertyDescriptor;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;

/**
 * DataFormatterDescriptor
 */
public class DataFormatterDescriptor extends AbstractDescriptor
{
    private static final Log log = Log.getLog(DataFormatterDescriptor.class);

    public static final String EXTENSION_ID = "com.seizia.chromite.dataFormatter"; //$NON-NLS-1$

    private final String id;
    private final String name;
    private final String description;
    private final DBPPropertyDescriptor[] properties;
    private DBDDataFormatterSample sample;
    private final ObjectType formatterType;

    public DataFormatterDescriptor(@NotNull IConfigurationElement config) {
        super(config);

        this.id = config.getAttribute("id");
        this.formatterType = new ObjectType(config.getAttribute("class"));
        this.name = config.getAttribute("label");
        this.description = config.getAttribute("description");
        this.properties = PropertyDescriptor.extractPropertyGroups(config);

        try {
            Class<?> objectClass = getImplClass(config.getAttribute("sampleClass"));
            sample = (DBDDataFormatterSample) objectClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("Can't instantiate data formatter '" + getId() + "' sample");
        }
    }

    @NotNull
    public String getId()
    {
        return id;
    }

    @NotNull
    public String getName()
    {
        return name;
    }

    @Nullable
    public String getDescription()
    {
        return description;
    }

    @NotNull
    public DBDDataFormatterSample getSample()
    {
        return sample;
    }

    @NotNull
    public DBPPropertyDescriptor[] getProperties() {
        return properties;
    }

    @NotNull
    public DBDDataFormatter createFormatter() throws ReflectiveOperationException
    {
        Class<? extends DBDDataFormatter> clazz = formatterType.getObjectClass(DBDDataFormatter.class);
        if (clazz == null) {
            return null;
        }
        return clazz.getConstructor().newInstance();
    }

}
