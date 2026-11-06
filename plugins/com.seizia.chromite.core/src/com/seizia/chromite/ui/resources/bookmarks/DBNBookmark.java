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
package com.seizia.chromite.ui.resources.bookmarks;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.app.DBPResourceHandler;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNResource;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;

/**
 * DBNBookmark
 */
public class DBNBookmark extends DBNResource
{
    private final BookmarkStorage storage;

    DBNBookmark(DBNNode parentNode, IResource resource, DBPResourceHandler handler) throws DBException, CoreException
    {
        super(parentNode, resource, handler);
        storage = new BookmarkStorage((IFile)resource, true);
    }

    @Override
    protected void dispose(boolean reflect)
    {
        storage.dispose();
        super.dispose(reflect);
    }

    public BookmarkStorage getStorage() {
        return storage;
    }

    @NotNull
    @Override
    public String getNodeDisplayName()
    {
        return storage.getTitle();
    }

    @Nullable
    @Override
    public String getNodeDescription()
    {
        String dsInfo = "";
        Collection<DBPDataSourceContainer> dataSources = getAssociatedDataSources();
        if (!CommonUtils.isEmpty(dataSources)) {
            DBPDataSourceContainer dataSource = dataSources.iterator().next();
            dsInfo = " ('" + dataSource.getName() + "' - " + dataSource.getDriver().getName() + ")";
        }
        return storage.getDescription() + dsInfo;
    }

    @NotNull
    @Override
    public DBPImage getResourceNodeIcon() {
        return storage.getImage();
    }

    @NotNull
    @Override
    public String getNodeTargetName() {
        List<String> dsPath = storage.getDataSourcePath();
        return CommonUtils.isEmpty(dsPath) ? super.getNodeDisplayName() : dsPath.get(dsPath.size() - 1);
    }

    @Override
    public void rename(@NotNull DBRProgressMonitor monitor, @NotNull String newName) throws DBException
    {
        IFile file = (IFile) getResource();
        if (file != null) {
            try {
                storage.setTitle(newName);
                InputStream data = storage.serialize();
                file.setContents(data, true, false, RuntimeUtils.getNestedMonitor(monitor));
            } catch (Exception e) {
                throw new DBException("Can't rename bookmark", e);
            }
        }
    }

}
