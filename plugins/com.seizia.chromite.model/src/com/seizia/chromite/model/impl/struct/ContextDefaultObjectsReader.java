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
package com.seizia.chromite.model.impl.struct;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNModel;
import com.seizia.chromite.model.navigator.DBNUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableWithProgress;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Reads the default catalog or schema of an execution context and the objects it can be changed to (the query window's
 * database picker, {@code SqlEditorPanel.showSchemas}, which finds the default in the list by identity).
 *
 * 설계: 카탈로그 목록을 먼저 읽고 기본 카탈로그를 찾는다. 실행 문맥의 {@code getDefaultCatalog}는 진행 모니터가 없어 목록 캐시에서만
 * 찾으므로(SQL Server, MySQL/MariaDB), 연결을 다시 읽어 목록이 비어 있을 때 먼저 물으면 null이거나 다시 읽기 전 목록의 객체가 된다.
 *
 * 변경 이력:
 * - 2026-11-13 Chromite: 기본 카탈로그를 묻기 전에 카탈로그 목록을 읽음. SQL Server에서 연결 새로 고침(Refresh, 연결 전체를 다시 읽는
 *   DDL: CREATE/DROP DATABASE, {@code CREATE TABLE dbo.t})이 목록을 비운 뒤 이 읽기가 기본값을 먼저 물어, 쿼리 창의 DB 선택이 목록의
 *   객체 대신 이름 글(#245 전에는 "&lt; N/A &gt;")로 남았다(앱 변경 #89, docs/chromite/swing-sql-editor-session-ui.md "연결을 다시 읽은 뒤의 DB 선택")
 */
public class ContextDefaultObjectsReader implements DBRRunnableWithProgress {
    private final DBPDataSource dataSource;
    private final DBCExecutionContext executionContext;
    private final List<DBNDatabaseNode> nodeList = new ArrayList<>();
    // Remote instance node
    private DBSObject defaultObject;
    private boolean enabled;
    private boolean readNodes = false;
    private String currentDatabaseInstanceName;
    private Collection<? extends DBSObject> objectList;

    public ContextDefaultObjectsReader(DBPDataSource dataSource, DBCExecutionContext executionContext) {
        this.dataSource = dataSource;
        this.executionContext = executionContext;
    }

    public DBPDataSource getDataSource() {
        return dataSource;
    }

    public DBCExecutionContext getExecutionContext() {
        return executionContext;
    }

    public String getDefaultCatalogName() {
        return currentDatabaseInstanceName;
    }

    public DBSObject getDefaultObject() {
        return defaultObject;
    }

    public Collection<? extends DBSObject> getObjectList() {
        return objectList;
    }

    public void setReadNodes(boolean readNodes) {
        this.readNodes = readNodes;
    }

    public List<DBNDatabaseNode> getNodeList() {
        return nodeList;
    }

    @Override
    public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
        DBSObjectContainer objectContainer = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
        if (objectContainer == null) {
            return;
        }

        DBCExecutionContextDefaults<?,?> contextDefaults = null;
        if (executionContext != null) {
            contextDefaults = executionContext.getContextDefaults();
        }
        if (contextDefaults == null) {
            return;
        }
        try {
            monitor.beginTask("Read default objects", 1);
            currentDatabaseInstanceName = null;

            Class<? extends DBSObject> childType = objectContainer.getPrimaryChildType(monitor);
            if (!DBSObjectContainer.class.isAssignableFrom(childType)) {
                enabled = false;
            } else {
                enabled = true;

                DBSObjectContainer defObject = null;
                if (DBSCatalog.class.isAssignableFrom(childType)) {
                    // The context finds its default catalog in the catalog list's cache only (it has no monitor): read the
                    // list first. A refresh of the connection (Refresh, DDL that refreshes it) empties the list, and a default
                    // read before the list was read again was null, or an object of the list before the refresh
                    objectContainer.getChildren(monitor);
                    defObject = contextDefaults.getDefaultCatalog();
                    if (defObject != null) {
                        Class<? extends DBSObject> catalogChildrenType = defObject.getPrimaryChildType(monitor);
                        if (DBSSchema.class.isAssignableFrom(catalogChildrenType)) {
                            currentDatabaseInstanceName = defObject.getName();
                            if (contextDefaults.supportsSchemaChange()) {
                                objectContainer = defObject;
                            } else if (!contextDefaults.supportsCatalogChange()) {
                                // Nothing can be changed
                                objectContainer = null;
                            }
                            DBSSchema defaultSchema = contextDefaults.getDefaultSchema();
                            if (defaultSchema != null) {
                                defObject = defaultSchema;
                            }
                        }
                    }
                } else if (DBSSchema.class.isAssignableFrom(childType)) {
                    defObject = contextDefaults.getDefaultSchema();
                }
                objectList = objectContainer == null ?
                    Collections.singletonList(defObject) :
                    objectContainer.getChildren(monitor);
                defaultObject = defObject;

                DBSObjectContainer navigatorSource = objectContainer != null ? objectContainer :
                    DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
                DBNModel navigatorModel = DBNUtils.getNavigatorModel(navigatorSource);
                if (readNodes && navigatorModel != null) {
                    // Cache navigator nodes
                    if (objectList != null) {
                        for (DBSObject child : objectList) {
                            if (DBUtils.getAdapter(DBSObjectContainer.class, child) != null) {
                                DBNDatabaseNode node = navigatorModel.getNodeByObject(monitor, child, false);
                                if (node != null) {
                                    nodeList.add(node);
                                }
                            }
                        }
                    }
                }
            }
        } catch (DBException e) {
            throw new InvocationTargetException(e);
        } finally {
            monitor.done();
        }
    }
}
