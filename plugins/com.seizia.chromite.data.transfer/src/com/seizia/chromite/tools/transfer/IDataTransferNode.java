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
package com.seizia.chromite.tools.transfer;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;

import java.io.IOException;

/**
 * Abstract node
 */
public interface IDataTransferNode<SETTINGS extends IDataTransferSettings> {

    DBSObject getDatabaseObject();

    DBPDataSourceContainer getDataSourceContainer();

    String getObjectName();

    default String getObjectFullName(@NotNull DBRProgressMonitor monitor) throws IOException {
        return getObjectName();
    }

    DBPImage getObjectIcon();

    String getObjectContainerName();

    DBPImage getObjectContainerIcon();

    boolean isConfigurationComplete();

    @Nullable
    DBPProject getProject();

}
