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
package com.seizia.chromite.runtime.net;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPAuthInfo;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.impl.net.SocksConstants;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.DBWHandlerType;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.secret.DBSSecretController;
import com.seizia.chromite.runtime.DBServiceConnections;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.encode.EncryptionException;
import com.seizia.chromite.runtime.encode.SecuredPasswordEncrypter;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import java.net.Authenticator;
import java.net.PasswordAuthentication;

/**
 * Global authenticator
 */
public class GlobalProxyAuthenticator extends Authenticator {
    private static final Log log = Log.getLog(GlobalProxyAuthenticator.class);

    public GlobalProxyAuthenticator() {
    }

    @Nullable
    @Override
    protected PasswordAuthentication getPasswordAuthentication() {
        {
            DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();

            // 1. Check for drivers download proxy
            final String proxyHost = store.getString(ModelPreferences.UI_PROXY_HOST);
            if (!CommonUtils.isEmpty(proxyHost) && proxyHost.equalsIgnoreCase(getRequestingHost()) &&
                store.getInt(ModelPreferences.UI_PROXY_PORT) == getRequestingPort()) {
                DBPAuthInfo credentials = null;
                try {
                    credentials = readCredentials();
                } catch (DBException e) {
                    log.error("Error reading proxy credentials", e);
                }
                if (credentials == null) {
                    credentials = readCredentialsInUI("Auth proxy '" + proxyHost + "'", null, null);
                }
                if (credentials != null) {
                    if (credentials.isSavePassword()) {
                        try {
                            saveCredentials(credentials.getUserName(), credentials.getUserPassword());
                        } catch (DBException e) {
                            log.error("Error saving proxy credentials", e);
                        }
                    }
                    return new PasswordAuthentication(credentials.getUserName(), credentials.getUserPassword().toCharArray());
                }
            }
        }

        {
            // 2. Check for connections' proxies
            String requestingProtocol = getRequestingProtocol();
            if (SocksConstants.PROTOCOL_SOCKS5.equals(requestingProtocol) || SocksConstants.PROTOCOL_SOCKS4.equals(requestingProtocol)) {
                DBPDataSourceContainer activeContext = DBExecUtils.findConnectionContext(getRequestingHost(), getRequestingPort(), getRequestingScheme());
                if (activeContext != null) {
                    for (DBWHandlerConfiguration networkHandler : activeContext.getActualConnectionConfiguration().getHandlers()) {
                        if (networkHandler.isEnabled() && networkHandler.getType() == DBWHandlerType.PROXY) {
                            String userName = networkHandler.getUserName();
                            String userPassword = networkHandler.getPassword();
                            if (CommonUtils.isEmpty(userName) || CommonUtils.isEmpty(userPassword)) {
                                DBPAuthInfo authInfo = readCredentialsInUI(getRequestingPrompt(), userName, userPassword);
                                if (authInfo != null) {
                                    userName = authInfo.getUserName();
                                    userPassword = authInfo.getUserPassword();
                                    if (authInfo.isSavePassword()) {
                                        // Save DS config
                                        networkHandler.setUserName(userName);
                                        networkHandler.setPassword(userPassword);
                                        networkHandler.setSavePassword(true);
                                        activeContext.getRegistry().flushConfig();
                                    }
                                }
                            }
                            if (!CommonUtils.isEmpty(userName) && !CommonUtils.isEmpty(userPassword)) {
                                return new PasswordAuthentication(userName, userPassword.toCharArray());
                            }
                        }
                    }
                }
            }
        }

        DBServiceConnections serviceConnections = DBWorkbench.getService(DBServiceConnections.class);
        if (serviceConnections != null) {
            PasswordAuthentication pc = serviceConnections.getGlobalProxyConfiguration(
                getRequestingProtocol(),
                getRequestingHost(),
                getRequestingPort());
            if (pc != null) {
                return pc;
            }
        }
        return null;
    }

    private DBPAuthInfo readCredentialsInUI(String prompt, String userName, String userPassword) {
        return DBWorkbench.getPlatformUI().promptUserCredentials(prompt, null, userName, userPassword, false, true);
    }

    @Nullable
    public static DBPAuthInfo readCredentials() throws DBException {
        // Modern storage
        DBSSecretController secrets = DBSSecretController.getGlobalSecretController();
        String userName = secrets.getPrivateSecretValue(ModelPreferences.UI_PROXY_USER);
        String password = secrets.getPrivateSecretValue(ModelPreferences.UI_PROXY_PASSWORD);

        if (CommonUtils.isNotEmpty(userName)) {
            return new DBPAuthInfo(userName, CommonUtils.notEmpty(password), true);
        }

        // Backward compatibility
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        userName = store.getString(ModelPreferences.UI_PROXY_USER);
        password = store.getString(ModelPreferences.UI_PROXY_PASSWORD);

        if (CommonUtils.isNotEmpty(userName)) {
            if (CommonUtils.isNotEmpty(password)) {
                try {
                    password = new SecuredPasswordEncrypter().decrypt(password);
                } catch (EncryptionException e) {
                    throw new DBException("Can't decrypt legacy password", e);
                }
            }

            return new DBPAuthInfo(userName, CommonUtils.notEmpty(password), true);
        }

        return null;
    }

    public static void saveCredentials(@NotNull String username, @NotNull String password) throws DBException {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        store.setToDefault(ModelPreferences.UI_PROXY_USER);
        store.setToDefault(ModelPreferences.UI_PROXY_PASSWORD);
        PrefUtils.savePreferenceStore(store);

        DBSSecretController secrets = DBSSecretController.getGlobalSecretController();
        secrets.setPrivateSecretValue(
            ModelPreferences.UI_PROXY_USER,
            CommonUtils.isNotEmpty(username) ? username : null);
        secrets.setPrivateSecretValue(
            ModelPreferences.UI_PROXY_PASSWORD,
            CommonUtils.isNotEmpty(username) && CommonUtils.isNotEmpty(password) ? password : null);
        secrets.flushChanges();
    }

}
