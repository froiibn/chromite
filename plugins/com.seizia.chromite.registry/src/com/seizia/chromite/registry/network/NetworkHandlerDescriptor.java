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
package com.seizia.chromite.registry.network;

import org.eclipse.core.runtime.IConfigurationElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.impl.AbstractContextDescriptor;
import com.seizia.chromite.model.impl.PropertyDescriptor;
import com.seizia.chromite.model.net.DBWHandlerDescriptor;
import com.seizia.chromite.model.net.DBWHandlerType;
import com.seizia.chromite.model.net.DBWNetworkHandler;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.registry.RegistryConstants;
import org.jkiss.utils.CommonUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * NetworkHandlerDescriptor
 */
public class NetworkHandlerDescriptor extends AbstractContextDescriptor implements DBWHandlerDescriptor {
    public static final String EXTENSION_ID = "com.seizia.chromite.networkHandler"; //$NON-NLS-1$
    private static final Log log = Log.getLog(NetworkHandlerDescriptor.class);

    private final String id;
    private final String label;
    private final String codeName;
    private final String prefix;
    private final String description;
    private final DBWHandlerType type;
    private final boolean secured;
    private final ObjectType handlerType;
    private final int order;
    private final List<String> replacesIDs;
    private NetworkHandlerDescriptor replacedBy;
    private final DBPPropertyDescriptor[] properties;
    private final boolean isDistributed; // see getter
    private final boolean isDesktop;

    NetworkHandlerDescriptor(
        IConfigurationElement config) {
        super(config);

        this.id = config.getAttribute(RegistryConstants.ATTR_ID);
        this.codeName = config.getAttribute("codeName") == null ? this.id : config.getAttribute("codeName");
        this.prefix = config.getAttribute("prefix") == null ? this.id : config.getAttribute("prefix");
        this.label = config.getAttribute(RegistryConstants.ATTR_LABEL);
        this.description = config.getAttribute(RegistryConstants.ATTR_DESCRIPTION);
        this.type = DBWHandlerType.valueOf(config.getAttribute(RegistryConstants.ATTR_TYPE).toUpperCase(Locale.ENGLISH));
        this.secured = CommonUtils.getBoolean(config.getAttribute(RegistryConstants.ATTR_SECURED), false);
        this.handlerType = new ObjectType(config.getAttribute(RegistryConstants.ATTR_HANDLER_CLASS));
        this.order = CommonUtils.toInt(config.getAttribute(RegistryConstants.ATTR_ORDER), 1);
        this.isDistributed = CommonUtils.getBoolean(config.getAttribute("distributed"), false);
        this.isDesktop = CommonUtils.getBoolean(config.getAttribute("desktop"), true);

        this.replacesIDs = Arrays.stream(config.getChildren("replace"))
            .map(re -> re.getAttribute("id"))
            .collect(Collectors.toList());

        this.properties = Arrays.stream(config.getChildren(PropertyDescriptor.TAG_PROPERTY_GROUP))
            .map(PropertyDescriptor::extractProperties)
            .flatMap(List<DBPPropertyDescriptor>::stream)
            .toArray(DBPPropertyDescriptor[]::new);
    }

    @Override
    @NotNull
    public String getId() {
        return id;
    }

    @Override
    @NotNull
    public String getCodeName() {
        return codeName;
    }

    @NotNull
    @Override
    public String getPrefix() {
        return prefix;
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @NotNull
    @Override
    public DBWHandlerType getType() {
        return type;
    }

    @Override
    public boolean isSecured() {
        return secured;
    }

    @NotNull
    @Override
    public DBPPropertyDescriptor[] getHandlerProperties() {
        return properties;
    }

    public int getOrder() {
        return order;
    }

    public boolean matches(DBPDriver driver) {
        try {
            return appliesTo(driver.getDataSourceProvider(), driver);
        } catch (Exception e) {
            log.debug(e);
            return false;
        }
    }

    @NotNull
    public ObjectType getHandlerType() {
        return handlerType;
    }

    @NotNull
    @Override
    public String getImplClassName() {
        return getHandlerType().getImplName();
    }

    @NotNull
    @Override
    public <T extends DBWNetworkHandler> T createHandler(@NotNull Class<T> impl) throws DBException {
        return handlerType.createInstance(impl);
    }

    public boolean replaces(NetworkHandlerDescriptor otherDesc) {
        return replacesIDs.contains(otherDesc.id);
    }

    @Override
    public String toString() {
        return id;
    }

    NetworkHandlerDescriptor getReplacedBy() {
        return replacedBy;
    }

    void setReplacedBy(NetworkHandlerDescriptor replacedBy) {
        this.replacedBy = replacedBy;
    }

    @Override
    public boolean isDistributed() {
        return isDistributed;
    }

    // Handler works in desktop application only
    public boolean isDesktopHandler() {
        return isDesktop;
    }
}
