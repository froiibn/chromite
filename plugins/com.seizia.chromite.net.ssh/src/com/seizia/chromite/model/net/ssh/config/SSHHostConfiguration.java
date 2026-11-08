/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.model.net.ssh.config;

import org.jkiss.code.NotNull;
import org.jkiss.utils.SecurityUtils;

/**
 * An SSH server to connect to.
 *
 * 설계: {@code hostname}·{@code port}는 소켓을 여는 주소이고, {@code hostKeyHostname}·{@code hostKeyPort}는 known_hosts에서
 * 서버 키를 찾는 이름이다. 직접 연결에서는 둘이 같다. 점프 서버 뒤의 서버는 앞 서버가 연 로컬 포워드({@code 127.0.0.1:임의 포트})로
 * 열리므로, 키를 소켓 주소로 확인하면 모든 대상이 {@code [127.0.0.1]:임의 포트}가 되어 바뀐 키를 알아챌 수 없다.
 * 그래서 포워드로 여는 설정({@link #forwardedTo})은 원래 서버의 이름과 포트를 키 이름으로 그대로 가진다(OpenSSH ProxyJump와 같음).
 *
 * 변경 이력:
 * - 2026-11-08 키 이름(hostKeyHostname, hostKeyPort)과 forwardedTo 추가: 점프 서버 뒤 서버의 호스트 키를 실제 이름으로 확인(후속 29 a)
 */
public record SSHHostConfiguration(
    @NotNull String username,
    @NotNull String hostname,
    int port,
    @NotNull SSHAuthConfiguration auth,
    @NotNull String hostKeyHostname,
    int hostKeyPort
) {
    public SSHHostConfiguration(
        @NotNull String username,
        @NotNull String hostname,
        int port,
        @NotNull SSHAuthConfiguration auth
    ) {
        this(username, hostname, port, auth, hostname, port);
    }

    /**
     * The same server reached through a local port forward: the socket goes to the forward, the host key is checked
     * under this server's own name and port.
     */
    @NotNull
    public SSHHostConfiguration forwardedTo(@NotNull String localHost, int localPort) {
        return new SSHHostConfiguration(username, localHost, localPort, auth, hostKeyHostname, hostKeyPort);
    }

    @NotNull
    public String toDisplayString() {
        return username + "@" + hostname + ":" + port;
    }

    @Override
    public String toString() {
        return SecurityUtils.mask(username) + "@" + SecurityUtils.mask(hostname) + ":" + port;
    }
}
