/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.runtime.jobs;

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPExclusiveResource;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.net.DBWNetworkHandler;
import com.seizia.chromite.model.struct.DBSInstance;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.code.NotNull;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

/**
 * Mocked connections and workbench services for the transaction safety tests of this package.
 *
 * 설계: 감시·복구 코드는 연결 객체에서 데이터 소스, 인스턴스, 실행 문맥을 따라가고 UI 서비스는 {@link DBWorkbench#findService}로 찾는다.
 * 실제 연결 없이 그 경로를 그대로 타도록 연결 객체는 mock으로 만들고, 서비스는 시험 스레드에서만 바뀌는 정적 mock으로 준다
 * (다른 정적 메서드는 실제 메서드를 부른다).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "트랜잭션 안전")
 */
final class TestConnections {

    private TestConnections() {
    }

    /**
     * A connected data source container whose one instance has the contexts.
     */
    @NotNull
    static DBPDataSourceContainer container(@NotNull DBPConnectionConfiguration configuration, @NotNull DBCExecutionContext... contexts) {
        DBPDataSourceContainer container = Mockito.mock(DBPDataSourceContainer.class);
        DBPDataSource dataSource = Mockito.mock(DBPDataSource.class);
        DBSInstance instance = Mockito.mock(DBSInstance.class);
        Mockito.when(container.getName()).thenReturn("Test connection");
        Mockito.when(container.getId()).thenReturn("test-connection");
        Mockito.when(container.getConnectionConfiguration()).thenReturn(configuration);
        Mockito.when(container.getDataSource()).thenReturn(dataSource);
        Mockito.when(container.isConnected()).thenReturn(true);
        Mockito.when(container.getActiveNetworkHandlers()).thenReturn(new DBWNetworkHandler[0]);
        Mockito.when(dataSource.getContainer()).thenReturn(container);
        Mockito.doReturn(List.of(instance)).when(dataSource).getAvailableInstances();
        Mockito.when(instance.getAllContexts()).thenReturn(contexts);
        Mockito.when(instance.getExclusiveLock()).thenReturn(Mockito.mock(DBPExclusiveResource.class));
        for (DBCExecutionContext context : contexts) {
            Mockito.when(context.getDataSource()).thenReturn(dataSource);
            Mockito.when(context.getOwnerInstance()).thenReturn(instance);
        }
        return container;
    }

    @NotNull
    static DBPConnectionConfiguration configuration(@NotNull DBPConnectionType type) {
        DBPConnectionConfiguration configuration = new DBPConnectionConfiguration();
        configuration.setConnectionType(new DBPConnectionType(type));
        return configuration;
    }

    /**
     * Replaces {@link DBWorkbench#findService} on this thread: the given services, null for any other type.
     */
    @NotNull
    static MockedStatic<DBWorkbench> services(@NotNull Map<Class<?>, Object> services) {
        return Mockito.mockStatic(DBWorkbench.class, Mockito.withSettings().mockMaker("mock-maker-inline").defaultAnswer(invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("findService") || method.equals("getService")) {
                return services.get((Class<?>) invocation.getArgument(0));
            }
            return invocation.callRealMethod();
        }));
    }
}
