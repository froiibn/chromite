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
package com.seizia.chromite.model.net.ssh.registry;

import org.eclipse.core.runtime.IConfigurationElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.impl.AbstractContextDescriptor;
import com.seizia.chromite.model.impl.AbstractDescriptor;
import com.seizia.chromite.model.net.ssh.SSHSessionController;
import com.seizia.chromite.registry.RegistryConstants;

/**
 * SSHImplementationDescriptor
 */
public class SSHSessionControllerDescriptor extends AbstractContextDescriptor {
    static final String EXTENSION_ID = "com.seizia.chromite.net.ssh"; //$NON-NLS-1$

    private final AbstractDescriptor.ObjectType implClass;
    private final String id;
    private final String label;
    private volatile SSHSessionController instance;

    SSHSessionControllerDescriptor(@NotNull IConfigurationElement config) {
        super(config);

        this.id = config.getAttribute(RegistryConstants.ATTR_ID);
        this.label = config.getAttribute(RegistryConstants.ATTR_LABEL);
        this.implClass = new AbstractDescriptor.ObjectType(config.getAttribute(RegistryConstants.ATTR_CLASS));
    }

    @NotNull
    public ObjectType getImplClass() {
        return implClass;
    }

    @NotNull
    public String getId() {
        return id;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    @NotNull
    public SSHSessionController getInstance() throws DBException {
        if (instance == null) {
            synchronized (this) {
                if (instance == null) {
                    instance = implClass.createInstance(SSHSessionController.class);
                }
            }
        }

        return instance;
    }

}
