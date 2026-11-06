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
package com.seizia.chromite.registry;

import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceFolder;
import com.seizia.chromite.model.net.DBWNetworkProfile;

import java.util.LinkedHashSet;
import java.util.Set;

public class DataSourceParseResults {
    public final Set<DBPDataSourceContainer> updatedDataSources = new LinkedHashSet<>();
    public final Set<DBPDataSourceContainer> addedDataSources = new LinkedHashSet<>();
    public final Set<DBPDataSourceContainer> removedDataSources = new LinkedHashSet<>();
    public final Set<DBPDataSourceFolder> addedFolders = new LinkedHashSet<>();
    public final Set<DBPDataSourceFolder> updatedFolders = new LinkedHashSet<>();
    public final Set<DBPDataSourceFolder> removedFolders = new LinkedHashSet<>();
    public final Set<DBWNetworkProfile> updatedProfiles = new LinkedHashSet<>();
    public final Set<DBWNetworkProfile> removedProfiles = new LinkedHashSet<>();
}
