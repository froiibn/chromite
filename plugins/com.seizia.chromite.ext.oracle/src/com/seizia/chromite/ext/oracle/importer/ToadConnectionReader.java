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
package com.seizia.chromite.ext.oracle.importer;

import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.dict.OracleConnectionType;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.DBWUtils;
import com.seizia.chromite.model.net.ssh.SSHConstants;
import com.seizia.chromite.registry.ForeignConnection;
import com.seizia.chromite.registry.ForeignConnectionReader;
import com.seizia.chromite.registry.network.NetworkHandlerDescriptor;
import com.seizia.chromite.registry.network.NetworkHandlerRegistry;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.XMLException;
import org.jkiss.utils.xml.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the Oracle connections of a Toad connection export file (XML).
 *
 * 설계:
 * <ul>
 *     <li>SWT {@code ConfigImportWizardPageToadConnections}(삭제된 {@code ext.oracle.ui}, {@code 263b8ddb45})의 읽기 규칙을 모델로
 *     옮겼다: {@code ConnectionHierarchy/DbPlatform[name=Oracle]/Connections/Connection[type=Oracle]}의 자식 요소
 *     (Host, Port, Sid, User, Alias, ConnectionMode, GUID, ConnectionType의 ServiceName 속성, SSH 값).
 *     다른 DB 플랫폼의 연결은 SWT처럼 읽지 않는다(요소 이름을 확인할 근거가 없다).</li>
 *     <li>데이터베이스 비밀번호는 읽지 않는다(SWT도 읽지 않았다). SSH 비밀번호는 SWT처럼 파일에 평문
 *     {@code SSHPassword}로 있을 때만 옮긴다. 그 값이 있으면 SSH 처리기의 "비밀번호 저장"을 켠다.</li>
 *     <li>SWT와 달라진 것: 역할 "Default"는 넣지 않는다(옛 키에 "Normal"을 넣으면 사용자 이름 뒤에 " AS Normal"이 붙는다).
 *     SYSDBA·SYSOPER는 인증 모델이 읽는 {@link OracleConstants#PROP_AUTH_LOGON_AS}로 넣는다. SSH 구현은 정하지 않는다(SWT는
 *     "sshj"를 넣었다. 비우면 SSH 번들의 기본 구현을 쓴다). 키 인증이면 비밀번호를 저장하지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 */
public final class ToadConnectionReader implements ForeignConnectionReader {

    private static final String ORACLE = "Oracle"; //$NON-NLS-1$

    @NotNull
    @Override
    public List<ForeignConnection> read(@NotNull Path file) throws DBException {
        Document document;
        try {
            document = XMLUtils.parseDocument(file);
        } catch (XMLException e) {
            throw new DBException("The Toad connection file cannot be parsed", e);
        }
        List<ForeignConnection> result = new ArrayList<>();
        for (Element hierarchy : XMLUtils.getChildElementList(document.getDocumentElement(), "ConnectionHierarchy")) { //$NON-NLS-1$
            for (Element platform : XMLUtils.getChildElementList(hierarchy, "DbPlatform")) { //$NON-NLS-1$
                if (!ORACLE.equals(platform.getAttribute("name"))) { //$NON-NLS-1$
                    continue;
                }
                for (Element connections : XMLUtils.getChildElementList(platform, "Connections")) { //$NON-NLS-1$
                    for (Element connection : XMLUtils.getChildElementList(connections, "Connection")) { //$NON-NLS-1$
                        if (ORACLE.equals(connection.getAttribute("type"))) { //$NON-NLS-1$
                            result.add(readConnection(connection));
                        }
                    }
                }
            }
        }
        return result;
    }

    @NotNull
    private static ForeignConnection readConnection(@NotNull Element element) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean serviceName = false;
        for (Element child : XMLUtils.getChildElementList(element)) {
            String body = XMLUtils.getElementBody(child);
            if (CommonUtils.isEmpty(body)) {
                continue;
            }
            values.put(child.getTagName(), body.trim());
            if ("ConnectionType".equals(child.getTagName())) { //$NON-NLS-1$
                serviceName = "True".equals(child.getAttribute("ServiceName")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        String host = values.get("Host"); //$NON-NLS-1$
        String sid = values.get("Sid"); //$NON-NLS-1$
        String sshHost = values.get("SSHHost"); //$NON-NLS-1$
        if (CommonUtils.isEmpty(host)) {
            host = sshHost;
        }
        String name = firstNonEmpty(values.get("Alias"), sid, host, sshHost, values.get("GUID")); //$NON-NLS-1$ //$NON-NLS-2$

        ForeignConnection connection = new ForeignConnection(CommonUtils.notEmpty(name));
        connection.setDriverId(SqlDeveloperConnectionReader.ORACLE_DRIVER);
        connection.setHost(host);
        connection.setPort(values.get("Port")); //$NON-NLS-1$
        connection.setDatabase(sid);
        connection.setUser(values.get("User")); //$NON-NLS-1$
        connection.setProviderProperty(OracleConstants.PROP_SID_SERVICE,
            serviceName ? OracleConnectionType.SERVICE.name() : OracleConnectionType.SID.name());
        String role = OracleImportUtils.getLogonRole(values.get("ConnectionMode")); //$NON-NLS-1$
        if (role != null) {
            connection.setProviderProperty(OracleConstants.PROP_AUTH_LOGON_AS, role);
        }
        if (!CommonUtils.isEmpty(sshHost)) {
            DBWHandlerConfiguration ssh = createSshTunnel(values, sshHost);
            if (ssh != null) {
                connection.getHandlers().add(ssh);
            }
        }
        return connection;
    }

    @Nullable
    private static DBWHandlerConfiguration createSshTunnel(@NotNull Map<String, String> values, @NotNull String sshHost) {
        NetworkHandlerDescriptor descriptor = NetworkHandlerRegistry.getInstance().getDescriptor(DBWUtils.SSH_TUNNEL);
        if (descriptor == null) {
            return null;
        }
        DBWHandlerConfiguration ssh = new DBWHandlerConfiguration(descriptor, null);
        ssh.setEnabled(true);
        ssh.setUserName(values.get("SSHUser")); //$NON-NLS-1$
        ssh.setProperty(DBWHandlerConfiguration.PROP_HOST, sshHost);
        ssh.setProperty(DBWHandlerConfiguration.PROP_PORT, values.get("SSHPort")); //$NON-NLS-1$
        String privateKey = values.get("SSHPrivateKey"); //$NON-NLS-1$
        if (!CommonUtils.isEmpty(privateKey)) {
            ssh.setProperty(SSHConstants.PROP_AUTH_TYPE, SSHConstants.AuthType.PUBLIC_KEY.name());
            ssh.setProperty(SSHConstants.PROP_KEY_PATH, privateKey);
        } else {
            ssh.setProperty(SSHConstants.PROP_AUTH_TYPE, SSHConstants.AuthType.PASSWORD.name());
            String password = values.get("SSHPassword"); //$NON-NLS-1$
            if (!CommonUtils.isEmpty(password)) {
                ssh.setPassword(password);
                ssh.setSavePassword(true);
            }
        }
        return ssh;
    }

    @Nullable
    private static String firstNonEmpty(@Nullable String... values) {
        for (String value : values) {
            if (!CommonUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }
}
