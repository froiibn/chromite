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
package com.seizia.chromite.model.impl.net;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.exec.DBCInvalidatePhase;
import com.seizia.chromite.model.net.DBWForwarder;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.DBWNetworkHandler;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.net.GlobalProxySelector;
import com.seizia.chromite.utils.GeneralUtils;

import java.io.IOException;
import java.net.ProxySelector;

/**
 * SOCKS proxy
 */
public class SocksProxyImpl implements DBWNetworkHandler, DBWForwarder {
    private static final Log log = Log.getLog(SocksProxyImpl.class);

    private DBWHandlerConfiguration configuration;

    @NotNull
    @Override
    public DBPConnectionConfiguration initializeHandler(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBWHandlerConfiguration configuration,
        @NotNull DBPConnectionConfiguration connectionInfo
    ) throws DBException, IOException {
        this.configuration = configuration;

        setupProxyHandler();

        return connectionInfo;
    }

    @Override
    public void invalidateHandler(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull DBCInvalidatePhase phase
    ) throws DBException {
        // nothing to do
    }

    @Override
    public boolean matchesParameters(@NotNull String host, int port) {
        if (host.equals(configuration.getStringProperty(SocksConstants.PROP_HOST))) {
            int socksPort = configuration.getIntProperty(SocksConstants.PROP_PORT);
            return socksPort == port;
        }
        return false;
    }

    private static void setupProxyHandler() {
        if (ProxySelector.getDefault() instanceof GlobalProxySelector) {
            return;
        }

        // Init default network settings
        ProxySelector defProxySelector = GeneralUtils.adapt(DBWorkbench.getPlatform(), ProxySelector.class);
        if (defProxySelector == null) {
            defProxySelector = new GlobalProxySelector(ProxySelector.getDefault());
        }
        ProxySelector.setDefault(defProxySelector);
    }

}
