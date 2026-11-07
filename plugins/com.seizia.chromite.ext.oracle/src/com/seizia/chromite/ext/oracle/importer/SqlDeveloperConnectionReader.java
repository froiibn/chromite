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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.dict.OracleConnectionType;
import com.seizia.chromite.ext.oracle.oci.OCIUtils;
import com.seizia.chromite.registry.ForeignConnection;
import com.seizia.chromite.registry.ForeignConnectionReader;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.XMLException;
import org.jkiss.utils.xml.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads Oracle SQL Developer connections: the connection store ({@code connections.xml}) or its JSON form
 * ({@code connections.json}, also what "Export Connections" writes).
 *
 * 설계:
 * <ul>
 *     <li>SWT {@code ConfigImportWizardPageSqlDeveloper}(삭제된 {@code ext.oracle.ui}, {@code 263b8ddb45})의 읽기 규칙을 모델로
 *     옮겼다. XML은 {@code Reference/RefAddresses/StringRefAddr(addrType, Contents)}, JSON은 {@code connections[].name}과
 *     {@code info}의 같은 이름 값을 읽는다. 두 형식을 같은 값 지도로 바꿔 한 규칙으로 연결을 만든다.</li>
 *     <li>SWT는 SQL Developer 설치 폴더에서 파일을 찾았지만, 여기서는 사용자가 고른 파일을 읽는다(다른 PC에서 내보낸 파일도 받기 위해).
 *     형식은 파일 첫 글자로 정한다(여는 중괄호이면 JSON, 아니면 XML).</li>
 *     <li>비밀번호는 읽지 않는다. SQL Developer는 비밀번호를 자기 키로 암호화해 저장하고, SWT 가져오기도 읽지 않았다(브리프: 원본 도구의
 *     암호화를 풀지 않음). 가져온 연결은 연결할 때 비밀번호를 묻는다.</li>
 *     <li>SWT와 달라진 것(근본 원인 수정):
 *     <ul>
 *         <li>OS 인증({@code OS_AUTHENTICATION})을 SWT는 사용자 이름에 속성 키를 넣었다. Oracle 데이터 소스가 읽는
 *         공급자 속성({@link OracleConstants#OS_AUTH_PROP})으로 넣는다.</li>
 *         <li>역할은 SYSDBA·SYSOPER만 {@link OracleConstants#PROP_AUTH_LOGON_AS}로 넣는다. 인증 모델과 Swing 연결 대화상자가 이 값을
 *         읽는다. 다른 값(기본 역할)은 넣지 않는다: 옛 키에 "Normal"을 넣으면 사용자 이름 뒤에 " AS Normal"이 붙는다.</li>
 *         <li>연결 방식은 Chromite가 아는 값(BASIC, TNS, CUSTOM)으로 바꾼다. 호스트 없이 URL만 있으면 CUSTOM(URL) 연결이다. URL이
 *         Oracle URL이 아니면 SQL Developer의 다른 DB 연결로 보고 드라이버를 URL로 고르게 둔다.</li>
 *         <li>TNS 경로는 {@code TNS_ADMIN}이 없을 때만 기본 Oracle 홈으로 넣는다(Oracle 공급자가 같은 순서로 찾는다).
 *         SWT는 Oracle 홈이 없으면 예외로 멈췄다.</li>
 *     </ul></li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 */
public final class SqlDeveloperConnectionReader implements ForeignConnectionReader {

    public static final String CONNECTIONS_XML = "connections.xml"; //$NON-NLS-1$
    public static final String CONNECTIONS_JSON = "connections.json"; //$NON-NLS-1$
    /**
     * Driver of the Oracle connections ("provider:driver").
     */
    public static final String ORACLE_DRIVER = "oracle:oracle_thin"; //$NON-NLS-1$

    private static final String ORACLE_URL_PREFIX = "jdbc:oracle:"; //$NON-NLS-1$
    private static final String UTF8_BOM = "﻿"; //$NON-NLS-1$

    // Keys of the XML address list and of the JSON "info" object
    private static final String KEY_HOST = "hostname"; //$NON-NLS-1$
    private static final String KEY_PORT = "port"; //$NON-NLS-1$
    private static final String KEY_SID = "sid"; //$NON-NLS-1$
    private static final String KEY_SERVICE_NAME = "serviceName"; //$NON-NLS-1$
    private static final String KEY_USER = "user"; //$NON-NLS-1$
    private static final String KEY_ROLE = "role"; //$NON-NLS-1$
    private static final String KEY_OS_AUTH = "OS_AUTHENTICATION"; //$NON-NLS-1$
    private static final String KEY_CUSTOM_URL = "customUrl"; //$NON-NLS-1$
    private static final String KEY_CONNECTION_TYPE = "OracleConnectionType"; //$NON-NLS-1$

    @NotNull
    @Override
    public List<ForeignConnection> read(@NotNull Path file) throws DBException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DBException("Cannot read " + file, e);
        }
        // JSON (always UTF-8) starts with '{'; anything else goes to the XML parser, which reads the encoding declaration
        for (byte b : bytes) {
            if (b == '{') {
                String text = new String(bytes, StandardCharsets.UTF_8);
                return readJson(text.startsWith(UTF8_BOM) ? text.substring(UTF8_BOM.length()) : text);
            }
            if (!Character.isWhitespace(b) && (b & 0x80) == 0) {
                break;
            }
        }
        return readXml(file);
    }

    @NotNull
    private static List<ForeignConnection> readJson(@NotNull String text) throws DBException {
        JsonArray connections;
        try {
            JsonElement root = JsonParser.parseString(text);
            JsonElement list = root.isJsonObject() ? root.getAsJsonObject().get("connections") : null; //$NON-NLS-1$
            if (list == null || !list.isJsonArray()) {
                throw new DBException("The file has no SQL Developer connection list");
            }
            connections = list.getAsJsonArray();
        } catch (JsonParseException | IllegalStateException e) {
            throw new DBException("The SQL Developer connection file cannot be parsed", e);
        }
        List<ForeignConnection> result = new ArrayList<>();
        for (JsonElement element : connections) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject connection = element.getAsJsonObject();
            String name = getString(connection, "name"); //$NON-NLS-1$
            JsonElement info = connection.get("info"); //$NON-NLS-1$
            if (CommonUtils.isEmpty(name) || info == null || !info.isJsonObject()) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> value : info.getAsJsonObject().entrySet()) {
                if (value.getValue().isJsonPrimitive()) {
                    values.put(value.getKey(), value.getValue().getAsString());
                }
            }
            addConnection(result, name, values);
        }
        return result;
    }

    @NotNull
    private static List<ForeignConnection> readXml(@NotNull Path file) throws DBException {
        Document document;
        try {
            document = XMLUtils.parseDocument(file);
        } catch (XMLException e) {
            throw new DBException("The SQL Developer connection file cannot be parsed", e);
        }
        List<ForeignConnection> result = new ArrayList<>();
        for (Element reference : XMLUtils.getChildElementList(document.getDocumentElement(), "Reference")) { //$NON-NLS-1$
            String name = reference.getAttribute("name"); //$NON-NLS-1$
            if (CommonUtils.isEmpty(name)) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            Element addresses = XMLUtils.getChildElement(reference, "RefAddresses"); //$NON-NLS-1$
            if (addresses != null) {
                for (Element address : XMLUtils.getChildElementList(addresses, "StringRefAddr")) { //$NON-NLS-1$
                    String type = address.getAttribute("addrType"); //$NON-NLS-1$
                    String content = XMLUtils.getChildElementBody(address, "Contents"); //$NON-NLS-1$
                    if (!CommonUtils.isEmpty(type) && !CommonUtils.isEmpty(content)) {
                        values.put(type, content);
                    }
                }
            }
            addConnection(result, name, values);
        }
        return result;
    }

    private static void addConnection(@NotNull List<ForeignConnection> result, @NotNull String name, @NotNull Map<String, String> values) {
        String host = CommonUtils.nullIfEmpty(values.get(KEY_HOST));
        String url = CommonUtils.nullIfEmpty(values.get(KEY_CUSTOM_URL));
        if (host == null && url == null) {
            // Nothing to connect to (SWT skipped these too)
            return;
        }
        ForeignConnection connection = new ForeignConnection(name);
        connection.setUser(CommonUtils.nullIfEmpty(values.get(KEY_USER)));
        String connectionType = CommonUtils.notEmpty(values.get(KEY_CONNECTION_TYPE)).toUpperCase(Locale.ENGLISH);

        if (OracleConstants.ConnectionType.TNS.equals(connectionType) && url != null) {
            // The "URL" of a TNS connection is the network alias; the Oracle provider resolves it from tnsnames.ora
            connection.setDriverId(ORACLE_DRIVER);
            connection.setDatabase(url);
            connection.setProviderProperty(OracleConstants.PROP_CONNECTION_TYPE, OracleConstants.ConnectionType.TNS);
            if (System.getenv(OracleConstants.VAR_TNS_ADMIN) == null) {
                File oraHome = OCIUtils.getDefaultOraHomePath();
                if (oraHome != null) {
                    connection.setProviderProperty(OracleConstants.PROP_TNS_PATH, oraHome.getPath());
                }
            }
        } else if (host == null) {
            connection.setUrl(url);
            if (!url.regionMatches(true, 0, ORACLE_URL_PREFIX, 0, ORACLE_URL_PREFIX.length())) {
                // Another database added to SQL Developer through its JDBC URL: the driver is chosen by the URL
                result.add(connection);
                return;
            }
            connection.setDriverId(ORACLE_DRIVER);
            connection.setProviderProperty(OracleConstants.PROP_CONNECTION_TYPE, OracleConstants.ConnectionType.CUSTOM);
        } else {
            connection.setDriverId(ORACLE_DRIVER);
            connection.setHost(host);
            connection.setPort(CommonUtils.nullIfEmpty(values.get(KEY_PORT)));
            String sid = CommonUtils.nullIfEmpty(values.get(KEY_SID));
            String serviceName = CommonUtils.nullIfEmpty(values.get(KEY_SERVICE_NAME));
            connection.setDatabase(sid != null ? sid : serviceName);
            if (sid != null) {
                connection.setProviderProperty(OracleConstants.PROP_SID_SERVICE, OracleConnectionType.SID.name());
            } else if (serviceName != null) {
                connection.setProviderProperty(OracleConstants.PROP_SID_SERVICE, OracleConnectionType.SERVICE.name());
            }
        }
        if (CommonUtils.toBoolean(values.get(KEY_OS_AUTH))) {
            connection.setProviderProperty(OracleConstants.OS_AUTH_PROP, Boolean.TRUE.toString());
        }
        String role = OracleImportUtils.getLogonRole(values.get(KEY_ROLE));
        if (role != null) {
            connection.setProviderProperty(OracleConstants.PROP_AUTH_LOGON_AS, role);
        }
        result.add(connection);
    }

    @Nullable
    private static String getString(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
