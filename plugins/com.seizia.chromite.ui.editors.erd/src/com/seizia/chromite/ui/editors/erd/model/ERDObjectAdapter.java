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
package com.seizia.chromite.ui.editors.erd.model;

import org.eclipse.core.runtime.IAdapterFactory;
import org.eclipse.gef.EditPart;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.DBPObject;
import com.seizia.chromite.model.DBPQualifiedObject;
import com.seizia.chromite.model.erd.ERDObject;
import com.seizia.chromite.model.navigator.DBNDatabaseItem;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.navigator.DBNUtils;
import com.seizia.chromite.model.navigator.meta.DBXTreeFolder;
import com.seizia.chromite.model.navigator.meta.DBXTreeItem;
import com.seizia.chromite.model.navigator.meta.DBXTreeNode;
import com.seizia.chromite.model.preferences.DBPPropertySource;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.DBSStructContainer;
import com.seizia.chromite.ui.editors.erd.part.DiagramPart;

import java.util.Collection;
import java.util.List;

/**
 * ERD object adapter
 */
public class ERDObjectAdapter implements IAdapterFactory {

    public ERDObjectAdapter() {
    }

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        if (DBNNode.class == adapterType) {
            boolean unwrapParentNode = false;
            Object object = ((EditPart) adaptableObject).getModel();
            if (object instanceof EntityDiagram) {
                final EntityDiagram diagram = (EntityDiagram) object;
                if (isEntityContainerUnique(diagram)) {
                    if (!diagram.getEntities().isEmpty()) {
                        object = diagram.getEntities().get(0);
                    } else if (diagram.getRootObjectContainer() != null) {
                        object = diagram.getRootObjectContainer();
                    }
                }
                unwrapParentNode = true;
            }
            if (object instanceof ERDObject) {
                object = ((ERDObject<?>) object).getObject();
                if (object instanceof DBSObject && adaptableObject instanceof DiagramPart && ((DBSObject) object).getParentObject() instanceof DBSStructContainer) {
                    object = ((DBSObject) object).getParentObject();
                }
            }
            if (object instanceof DBSObject) {
                DBNDatabaseNode node = DBNUtils.getNodeByObject((DBSObject) object);
                if (node instanceof DBNDatabaseItem && node.getObject() instanceof DBSStructContainer) {
                    node = getItemsFolderNode(node);
                } else if (node != null && node.getParentNode() instanceof DBNDatabaseNode && unwrapParentNode) {
                    node = (DBNDatabaseNode) node.getParentNode();
                }

                if (node != null) {
                    return adapterType.cast(node);
                }
            }
        } else if (DBPObject.class.isAssignableFrom(adapterType)) {
            if (adaptableObject instanceof EditPart) {
                Object model = ((EditPart) adaptableObject).getModel();
                if (model != null && adapterType.isAssignableFrom(model.getClass())) {
                    return adapterType.cast(model);
                }
                if (model instanceof ERDObject) {
                    Object object = ((ERDObject<?>) model).getObject();
                    if (object != null && adapterType.isAssignableFrom(object.getClass())) {
                        return adapterType.cast(object);
                    }
                }
            }
        } else if (DBPPropertySource.class.isAssignableFrom(adapterType)) {
            if (adaptableObject instanceof EditPart) {
                Object model = ((EditPart) adaptableObject).getModel();
                if (model instanceof ERDObject) {
                    return ((ERDObject<?>) model).getAdapter(adapterType);
                }
            }
        }
        return null;
    }

    private static boolean isEntityContainerUnique(@NotNull EntityDiagram diagram) {
        if (diagram.getDataSources().size() == 1) {
            final DBPDataSourceContainer dataSourceContainer = diagram.getDataSources().iterator().next();
            final Collection<DBSObjectContainer> objectContainers = diagram.getObjectContainers(dataSourceContainer);
            return objectContainers != null && objectContainers.size() == 1;
        }
        return diagram.getRootObjectContainer() != null;
    }

    // That's tricky
    // Try to get Table folder. It is handy for Create New command
    private DBNDatabaseNode getItemsFolderNode(DBNDatabaseNode node) {
        for (DBXTreeNode childFolderMeta : node.getMeta().getChildren(node)) {
            if (childFolderMeta instanceof DBXTreeFolder) {
                List<DBXTreeNode> childItems = childFolderMeta.getChildren(node);
                if (!childItems.isEmpty()) {
                    DBXTreeNode itemMeta = childItems.get(0);
                    if (itemMeta instanceof DBXTreeItem) {
                        String itemPropName = ((DBXTreeItem) itemMeta).getPropertyName();
                        if (itemPropName.contains("table") || itemPropName.contains("collection")) {
                            try {
                                // Safe to use fake monitor because we read local folders
                                DBNDatabaseNode[] nodeChildren = node.getChildren(new VoidProgressMonitor());
                                if (nodeChildren != null) {
                                    for (DBNDatabaseNode navFolder : nodeChildren) {
                                        if (navFolder.getMeta() == childFolderMeta) {
                                            node = navFolder;
                                            break;
                                        }
                                    }
                                }
                            } catch (DBException ignored) {
                                // Shouldn't be here
                            }
                            break;
                        }
                    }
                }
            }
        }
        return node;
    }

    @Override
    public Class<?>[] getAdapterList() {
        return new Class<?>[] { ERDObject.class, DBPNamedObject.class, DBPQualifiedObject.class, DBSObject.class, DBNNode.class };
    }
}
