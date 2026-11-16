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
package com.seizia.chromite.model.net.ssh;

import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.net.ssh.config.SSHHostConfiguration;
import com.seizia.chromite.runtime.DBWorkbench;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

/**
 * Checks the SSH server key against a known_hosts file and asks about an unknown key.
 *
 * 설계: 키는 소켓 주소가 아니라 서버의 키 이름({@link SSHHostConfiguration#hostKeyHostname},
 * {@link SSHHostConfiguration#hostKeyPort})으로 찾는다. 점프 서버 뒤 서버는 로컬 포워드({@code 127.0.0.1:임의 포트})로 열리므로
 * 소켓 주소로는 서버를 가릴 수 없다. 처음 보는 키는 OpenSSH처럼 SHA-256 지문을 보이며 묻고(기본 단추 "아니요"),
 * 바뀐 키는 묻지 않고 거절한다.
 *
 * 변경 이력:
 * - 2026-11-08 localhost·127.0.0.1이면 확인하지 않고 받아들이던 것을 지움. 그 예외 때문에 127.0.0.1의 SSH 서버와
 *   점프 서버 뒤의 모든 서버(로컬 포워드로 열림)가 묻지도 않고, 키가 바뀌어도 연결됐다. 지문은 MD5 대신 OpenSSH 기본과 같은
 *   SHA-256으로 보인다(후속 29 a).
 */
public class KnownHostsVerifier extends OpenSSHKnownHosts {
    private final SSHHostConfiguration host;

    public KnownHostsVerifier(@NotNull File khFile, @NotNull SSHHostConfiguration host) throws IOException {
        super(khFile);
        this.host = host;
    }

    @Override
    public boolean verify(String hostname, int port, PublicKey key) {
        return super.verify(host.hostKeyHostname(), host.hostKeyPort(), key);
    }

    @Override
    public List<String> findExistingAlgorithms(String hostname, int port) {
        return super.findExistingAlgorithms(host.hostKeyHostname(), host.hostKeyPort());
    }

    @Override
    protected boolean hostKeyUnverifiableAction(String hostname, PublicKey key) {
        KeyType type = KeyType.fromKey(key);

        boolean isConfirmed = DBWorkbench.getPlatformUI().confirmAction(SSHJUIMessages.verify_connection_confirmation_title,
            NLS.bind(SSHJUIMessages.verify_connection_confirmation_message, new String[]{hostname, type.toString(), getFingerprint(key)}), true);

        if (!isConfirmed) {
            return false;
        } else {
            try {
                this.entries().add(new HostEntry(null, hostname, KeyType.fromKey(key), key));
                this.write();
                DBWorkbench.getPlatformUI().showWarningMessageBox(SSHJUIMessages.warning_title,
                    NLS.bind(SSHJUIMessages.known_host_added_warning_message, hostname, type));
                return true;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    protected boolean hostKeyChangedAction(String hostname, PublicKey key) {
        DBWorkbench.getPlatformUI().showWarningMessageBox(
            SSHJUIMessages.warning_title,
            NLS.bind(SSHJUIMessages.host_key_changed_warning_message, new String[]{
                KeyType.fromKey(key).toString(),
                getFingerprint(key),
                getFile().getAbsolutePath()
            })
        );
        return false;
    }

    /**
     * The key fingerprint in the form OpenSSH prints by default ({@code ssh-keygen -l}), so it can be compared with the server's.
     */
    @NotNull
    static String getFingerprint(@NotNull PublicKey key) {
        byte[] blob = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob); //$NON-NLS-1$
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest); //$NON-NLS-1$
        } catch (NoSuchAlgorithmException e) {
            // Every Java runtime has SHA-256
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void write(KnownHostEntry entry) throws IOException {
        this.khFile.getParentFile().mkdirs();
        super.write(entry);
        SSHUtils.forcePlatformReloadKnownHostsPreferences();
    }

    @Override
    public void write() throws IOException {
        this.khFile.getParentFile().mkdirs();
        super.write();
        SSHUtils.forcePlatformReloadKnownHostsPreferences();
    }
}
