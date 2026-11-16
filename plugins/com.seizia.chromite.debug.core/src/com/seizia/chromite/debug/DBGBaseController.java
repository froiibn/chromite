/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2017-2018 Andrew Khitrin (ahitrin@gmail.com)
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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
package com.seizia.chromite.debug;

import org.eclipse.core.runtime.ListenerList;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller base: connects the data source if needed and keeps the event handlers.
 *
 * 설계: 세션 만들기({@link #createSession})는 {@link DBGSession}을 돌려준다. PostgreSQL 세션은 SQL 문자열 하나를 작업에서 실행하는
 * {@link com.seizia.chromite.debug.jdbc.DBGJDBCSession}이지만, Oracle({@code DBMS_DEBUG})은 단계 명령이 OUT 매개 변수로 멈춘 이유를
 * 돌려주고 시간 제한마다 다시 기다려야 해서 그 작업 틀을 쓰지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 createSession이 DBGJDBCSession 대신 DBGSession을 돌려줌(Oracle 디버거) (docs/chromite/procedure-debugger.md)
 */
public abstract class DBGBaseController implements DBGController {

    private static final Log log = Log.getLog(DBGBaseController.class);

    private final DBPDataSourceContainer dataSourceContainer;

    private final Map<String, Object> configuration;

    private ListenerList<DBGEventHandler> eventHandlers = new ListenerList<>();

    protected DBGBaseController(DBPDataSourceContainer dataSourceContainer, Map<String, Object> configuration) {
        this.dataSourceContainer = dataSourceContainer;
        this.configuration = new HashMap<>(configuration);
    }

    @Override
    public DBPDataSourceContainer getDataSourceContainer() {
        return dataSourceContainer;
    }

    @Override
    public Map<String, Object> getDebugConfiguration() {
        return new HashMap<>(configuration);
    }

    @Override
    public DBGSession openSession(DBRProgressMonitor monitor) throws DBGException {
        if (!dataSourceContainer.isConnected()) {
            try {
                dataSourceContainer.connect(monitor, true, true);
            } catch (DBException e) {
                throw new DBGException(e, dataSourceContainer.getDataSource());
            }
        }
        if (!dataSourceContainer.isConnected()) {
            throw new DBGException(ModelMessages.error_not_connected_to_database);
        }
        return createSession(monitor, configuration);
    }

    @Override
    public void dispose() {
        Object[] listeners = eventHandlers.getListeners();
        for (Object listener : listeners) {
            unregisterEventHandler((DBGEventHandler) listener);
        }
    }

    public abstract DBGSession createSession(DBRProgressMonitor monitor, Map<String, Object> configuration)
            throws DBGException;

    @Override
    public void registerEventHandler(DBGEventHandler eventHandler) {
        eventHandlers.add(eventHandler);
    }

    @Override
    public void unregisterEventHandler(DBGEventHandler eventHandler) {
        eventHandlers.remove(eventHandler);
    }

    public void fireEvent(DBGEvent event) {
        for (DBGEventHandler eventHandler : eventHandlers) {
            eventHandler.handleDebugEvent(event);
        }
    }

}
