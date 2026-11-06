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
package com.seizia.chromite.model.ai.tools.mcp;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.ai.agent.AIAgentServices;
import com.seizia.chromite.model.impl.preferences.BundlePreferenceStore;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Settings of the local MCP server: enabled flag, port, bearer token (docs/chromite/ai-agent.md "로컬 MCP 서버").
 *
 * 설계:
 * <ul>
 *     <li>켜기·포트는 이 번들의 설정 저장소({@code com.seizia.chromite.model.ai.tools})에 둔다. 기본은 꺼짐, 포트 {@value #DEFAULT_PORT}.</li>
 *     <li>토큰은 설정이 아니라 작업 공간 {@code .metadata/chromite-ai/mcp-token} 파일이다. 설정 파일은 내보내기·공유될 수 있지만 토큰은 이 PC의 비밀이기
 *     때문이다. 32바이트 무작위 값(base64url)이고, POSIX 파일 시스템이면 소유자만 읽게 한다. 설정 화면(2단계)이 보여 주고 다시 만들 수 있다.</li>
 *     <li>값을 바꾸면 {@link AIMcpServer#applySettings()}로 서버를 시작·중지·재시작한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성
 */
public final class AIMcpSettings {

    private static final Log log = Log.getLog(AIMcpSettings.class);

    public static final String BUNDLE_ID = "com.seizia.chromite.model.ai.tools"; //$NON-NLS-1$
    public static final String PREF_ENABLED = "chromite.ai.mcp.enabled"; //$NON-NLS-1$
    public static final String PREF_PORT = "chromite.ai.mcp.port"; //$NON-NLS-1$
    public static final int DEFAULT_PORT = 47821;
    private static final String TOKEN_FILE = "mcp-token"; //$NON-NLS-1$

    private static DBPPreferenceStore store;

    private AIMcpSettings() {
    }

    @NotNull
    private static synchronized DBPPreferenceStore getStore() {
        if (store == null) {
            store = new BundlePreferenceStore(BUNDLE_ID);
            store.setDefault(PREF_ENABLED, false);
            store.setDefault(PREF_PORT, DEFAULT_PORT);
        }
        return store;
    }

    public static boolean isEnabled() {
        return getStore().getBoolean(PREF_ENABLED);
    }

    public static void setEnabled(boolean enabled) {
        getStore().setValue(PREF_ENABLED, enabled);
        PrefUtils.savePreferenceStore(getStore());
        AIMcpServer.getInstance().applySettings();
    }

    public static int getPort() {
        int port = getStore().getInt(PREF_PORT);
        return port > 0 && port < 65536 ? port : DEFAULT_PORT;
    }

    public static void setPort(int port) {
        getStore().setValue(PREF_PORT, port);
        PrefUtils.savePreferenceStore(getStore());
        AIMcpServer.getInstance().applySettings();
    }

    @NotNull
    public static String getUrl() {
        return "http://127.0.0.1:" + getPort() + AIMcpServer.PATH;
    }

    /**
     * The bearer token, created on first use.
     */
    @NotNull
    public static synchronized String getToken() throws DBException {
        Path file = getTokenFile();
        try {
            if (Files.exists(file)) {
                String token = Files.readString(file, StandardCharsets.US_ASCII).trim();
                if (token.length() >= 32) {
                    return token;
                }
            }
            return writeNewToken(file);
        } catch (IOException e) {
            throw new DBException("Can't read or create the MCP token " + file, e);
        }
    }

    /**
     * Replaces the token; connected MCP clients must use the new one.
     */
    @NotNull
    public static synchronized String regenerateToken() throws DBException {
        try {
            return writeNewToken(getTokenFile());
        } catch (IOException e) {
            throw new DBException("Can't write the MCP token", e);
        }
    }

    @NotNull
    public static Path getTokenFile() {
        return AIAgentServices.getAgentFolder().resolve(TOKEN_FILE);
    }

    /**
     * {@code claude mcp add} command with the token placeholder (the token itself is not printed).
     */
    @NotNull
    public static String getClaudeCodeCommand() {
        return "claude mcp add --transport http chromite " + getUrl() + " --header \"Authorization: Bearer <CHROMITE_MCP_TOKEN>\"";
    }

    @NotNull
    private static String writeNewToken(@NotNull Path file) throws IOException {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Files.createDirectories(file.getParent());
        Files.writeString(file, token, StandardCharsets.US_ASCII);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (IOException | UnsupportedOperationException e) {
                log.debug("Can't restrict the MCP token file permissions", e);
            }
        }
        return token;
    }
}
