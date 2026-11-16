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

package com.seizia.chromite.ext.gbase8s;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.gbase8s.model.GBase8sDataSource;
import com.seizia.chromite.ext.gbase8s.model.meta.GBase8sMetaModel;
import com.seizia.chromite.ext.generic.GenericDataSourceProvider;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

/**
 * @author Chao Tian
 */
public class GBase8sDataSourceProvider extends GenericDataSourceProvider<GBase8sDataSource> {
    public GBase8sDataSourceProvider() {
        super(GBase8sDataSource.class);
    }

    @NotNull
    @Override
    public GBase8sDataSource openDataSource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer container
    ) throws DBException {
        return new GBase8sDataSource(monitor, container, new GBase8sMetaModel());
    }
}
