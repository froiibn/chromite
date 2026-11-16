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
package com.seizia.chromite.registry;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The container of a connection transfer file: a plain one-line header, then the payload (encrypted or not).
 *
 * 설계:
 * <ul>
 *     <li>JDK만 쓴다: PBKDF2-HMAC-SHA256으로 암호에서 256비트 키를 만들고 AES-256-GCM으로 페이로드 전체를 암호화한다.
 *     연결 이름과 호스트도 민감할 수 있어 비밀번호만이 아니라 페이로드 전체를 암호화한다.</li>
 *     <li>머리말은 평문 JSON 한 줄(형식 이름, 버전, 암호 방식, KDF와 반복 횟수, salt, IV)이다. 나중 버전이 옛 파일을 읽을 수 있게
 *     복호화에 필요한 값은 모두 머리말에 둔다. 머리말 바이트 전체를 GCM의 AAD로 넣어, 머리말을 바꿔도(반복 횟수 등) 인증이 실패한다.</li>
 *     <li>반복 횟수 {@value #PBKDF2_ITERATIONS}: OWASP Password Storage Cheat Sheet(2023)의 PBKDF2-HMAC-SHA256 권장값이다.
 *     파일 하나를 열 때 한 번만 계산하므로(1초 안팎) 사람이 기다릴 만하고, 파일을 가져간 사람의 암호 추측은 그만큼 느려진다.
 *     읽을 때는 {@value #MIN_ITERATIONS}..{@value #MAX_ITERATIONS}만 받는다(약한 파일과 일부러 느리게 만든 파일을 막음).</li>
 *     <li>salt 16바이트, IV 12바이트(GCM 권장 길이), 인증 태그 128비트. 파일마다 새로 만든다(SecureRandom).</li>
 *     <li>비밀번호를 넣지 않은 내보내기는 암호 없이 같은 머리말 형식에 {@code "encryption":"none"}으로 쓴다.</li>
 *     <li>암호({@code char[]})와 키·평문 바이트 배열은 쓰고 나면 지운다. 호출한 쪽의 암호 배열은 호출한 쪽이 지운다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/connection-transfer.md)
 */
public final class ConnectionTransferFile {

    /**
     * File name extension, without the dot.
     */
    public static final String FILE_EXTENSION = "chromite-connections"; //$NON-NLS-1$
    /**
     * Minimum passphrase length accepted for export.
     */
    public static final int MIN_PASSPHRASE_LENGTH = 12;

    static final String FORMAT_NAME = "chromite-connections"; //$NON-NLS-1$
    static final int FORMAT_VERSION = 1;
    static final String ENCRYPTION_AES_GCM = "AES-256-GCM"; //$NON-NLS-1$
    static final String ENCRYPTION_NONE = "none"; //$NON-NLS-1$
    static final String KDF_PBKDF2 = "PBKDF2WithHmacSHA256"; //$NON-NLS-1$
    static final int PBKDF2_ITERATIONS = 600_000;
    static final int MIN_ITERATIONS = 100_000;
    static final int MAX_ITERATIONS = 10_000_000;

    private static final String ATTR_FORMAT = "format"; //$NON-NLS-1$
    private static final String ATTR_VERSION = "version"; //$NON-NLS-1$
    private static final String ATTR_ENCRYPTION = "encryption"; //$NON-NLS-1$
    private static final String ATTR_KDF = "kdf"; //$NON-NLS-1$
    private static final String ATTR_ITERATIONS = "iterations"; //$NON-NLS-1$
    private static final String ATTR_SALT = "salt"; //$NON-NLS-1$
    private static final String ATTR_IV = "iv"; //$NON-NLS-1$
    private static final String ATTR_TAG_BITS = "tagBits"; //$NON-NLS-1$

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"; //$NON-NLS-1$
    private static final String KEY_ALGORITHM = "AES"; //$NON-NLS-1$
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_HEADER_BYTES = 4096;
    private static final byte HEADER_END = '\n';

    private ConnectionTransferFile() {
    }

    /**
     * Writes the container: encrypted with the passphrase, or plain when the passphrase is null.
     */
    @NotNull
    static byte[] write(@NotNull byte[] payload, @Nullable char[] passphrase) throws ConnectionTransferException {
        JsonObject header = new JsonObject();
        header.addProperty(ATTR_FORMAT, FORMAT_NAME);
        header.addProperty(ATTR_VERSION, FORMAT_VERSION);
        byte[] body;
        byte[] headerBytes;
        if (passphrase == null) {
            header.addProperty(ATTR_ENCRYPTION, ENCRYPTION_NONE);
            headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
            body = payload;
        } else {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(salt);
            random.nextBytes(iv);
            header.addProperty(ATTR_ENCRYPTION, ENCRYPTION_AES_GCM);
            header.addProperty(ATTR_KDF, KDF_PBKDF2);
            header.addProperty(ATTR_ITERATIONS, PBKDF2_ITERATIONS);
            header.addProperty(ATTR_SALT, Base64.getEncoder().encodeToString(salt));
            header.addProperty(ATTR_IV, Base64.getEncoder().encodeToString(iv));
            header.addProperty(ATTR_TAG_BITS, TAG_BITS);
            headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
            try {
                Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, passphrase, salt, PBKDF2_ITERATIONS, iv, TAG_BITS);
                cipher.updateAAD(headerBytes);
                body = cipher.doFinal(payload);
            } catch (GeneralSecurityException e) {
                throw new ConnectionTransferException(ConnectionTransferException.Reason.UNSUPPORTED_FORMAT,
                    "Cannot encrypt the connection file", e);
            }
        }
        byte[] result = new byte[headerBytes.length + 1 + body.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        result[headerBytes.length] = HEADER_END;
        System.arraycopy(body, 0, result, headerBytes.length + 1, body.length);
        return result;
    }

    /**
     * True if reading the file needs a passphrase.
     */
    static boolean isEncrypted(@NotNull byte[] file) throws ConnectionTransferException {
        return !ENCRYPTION_NONE.equals(getString(readHeader(file).header, ATTR_ENCRYPTION));
    }

    /**
     * Reads the payload. Nothing is returned unless GCM authentication succeeds (wrong passphrase or a changed byte
     * anywhere in the file fails).
     */
    @NotNull
    static byte[] read(@NotNull byte[] file, @Nullable char[] passphrase) throws ConnectionTransferException {
        Header parsed = readHeader(file);
        JsonObject header = parsed.header;
        byte[] body = Arrays.copyOfRange(file, parsed.bodyOffset, file.length);
        String encryption = getString(header, ATTR_ENCRYPTION);
        if (ENCRYPTION_NONE.equals(encryption)) {
            return body;
        }
        if (!ENCRYPTION_AES_GCM.equals(encryption) || !KDF_PBKDF2.equals(getString(header, ATTR_KDF))) {
            throw new ConnectionTransferException(ConnectionTransferException.Reason.UNSUPPORTED_FORMAT,
                "Unsupported encryption '" + encryption + "'");
        }
        if (passphrase == null) {
            throw new ConnectionTransferException(ConnectionTransferException.Reason.WRONG_PASSPHRASE_OR_CHANGED,
                "The connection file is encrypted");
        }
        int iterations = getInt(header, ATTR_ITERATIONS);
        int tagBits = getInt(header, ATTR_TAG_BITS);
        byte[] salt = getBase64(header, ATTR_SALT);
        byte[] iv = getBase64(header, ATTR_IV);
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS || tagBits != TAG_BITS
            || salt.length < SALT_BYTES || iv.length != IV_BYTES) {
            throw new ConnectionTransferException(ConnectionTransferException.Reason.UNSUPPORTED_FORMAT,
                "Unsupported encryption parameters");
        }
        try {
            Cipher cipher = createCipher(Cipher.DECRYPT_MODE, passphrase, salt, iterations, iv, tagBits);
            cipher.updateAAD(file, 0, parsed.headerLength);
            return cipher.doFinal(body);
        } catch (AEADBadTagException e) {
            throw new ConnectionTransferException(ConnectionTransferException.Reason.WRONG_PASSPHRASE_OR_CHANGED,
                "Wrong passphrase, or the connection file was changed", e);
        } catch (GeneralSecurityException e) {
            throw new ConnectionTransferException(ConnectionTransferException.Reason.UNSUPPORTED_FORMAT,
                "Cannot decrypt the connection file", e);
        }
    }

    @NotNull
    private static Cipher createCipher(
        int mode,
        @NotNull char[] passphrase,
        @NotNull byte[] salt,
        int iterations,
        @NotNull byte[] iv,
        int tagBits
    ) throws GeneralSecurityException {
        PBEKeySpec keySpec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        byte[] keyBytes = null;
        try {
            // PBKDF2 in the JDK encodes the passphrase as UTF-8, so non-ASCII passphrases give the same key everywhere
            keyBytes = SecretKeyFactory.getInstance(KDF_PBKDF2).generateSecret(keySpec).getEncoded();
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(mode, new SecretKeySpec(keyBytes, KEY_ALGORITHM), new GCMParameterSpec(tagBits, iv));
            return cipher;
        } finally {
            keySpec.clearPassword();
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }
    }

    private record Header(@NotNull JsonObject header, int headerLength, int bodyOffset) {
    }

    @NotNull
    private static Header readHeader(@NotNull byte[] file) throws ConnectionTransferException {
        int end = -1;
        for (int i = 0; i < Math.min(file.length, MAX_HEADER_BYTES); i++) {
            if (file[i] == HEADER_END) {
                end = i;
                break;
            }
        }
        if (end <= 0) {
            throw notTransferFile(null);
        }
        JsonObject header;
        try {
            JsonElement element = JsonParser.parseString(new String(file, 0, end, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                throw notTransferFile(null);
            }
            header = element.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw notTransferFile(e);
        }
        if (!FORMAT_NAME.equals(getString(header, ATTR_FORMAT))) {
            throw notTransferFile(null);
        }
        int version = getInt(header, ATTR_VERSION);
        if (version < 1 || version > FORMAT_VERSION) {
            throw new ConnectionTransferException(ConnectionTransferException.Reason.UNSUPPORTED_FORMAT,
                "Unsupported connection file version " + version);
        }
        return new Header(header, end, end + 1);
    }

    @NotNull
    private static ConnectionTransferException notTransferFile(@Nullable Throwable cause) {
        return new ConnectionTransferException(ConnectionTransferException.Reason.NOT_A_TRANSFER_FILE,
            "Not a Chromite connection file", cause);
    }

    @Nullable
    private static String getString(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static int getInt(@NotNull JsonObject object, @NotNull String name) throws ConnectionTransferException {
        JsonElement element = object.get(name);
        try {
            if (element != null && element.isJsonPrimitive()) {
                return element.getAsInt();
            }
        } catch (NumberFormatException e) {
            throw notTransferFile(e);
        }
        throw notTransferFile(null);
    }

    @NotNull
    private static byte[] getBase64(@NotNull JsonObject object, @NotNull String name) throws ConnectionTransferException {
        String value = getString(object, name);
        if (value == null) {
            throw notTransferFile(null);
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw notTransferFile(e);
        }
    }
}
