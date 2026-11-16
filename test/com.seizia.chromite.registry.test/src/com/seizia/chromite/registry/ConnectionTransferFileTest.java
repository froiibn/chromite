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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Regression tests of the connection transfer file container ({@link ConnectionTransferFile}).
 *
 * 설계: 컨테이너는 플랫폼 없이 JDK 암호만 쓰므로 OSGi 앱 없이 바이트 배열로 확인한다. PBKDF2 반복이 600,000번이라 암호화 왕복은
 * 꼭 필요한 경우만 한다. 머리말을 바꾼 파일은 실제 파일처럼 머리말 JSON만 고치고 암호문은 그대로 둔다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "연결 내보내기 파일")
 */
public class ConnectionTransferFileTest {

    private static final byte[] PAYLOAD = "{\"configuration\":{\"connections\":{}},\"secrets\":true}".getBytes(StandardCharsets.UTF_8);

    @Test
    public void plainFileRoundTripsWithoutPassphrase() throws Exception {
        byte[] file = ConnectionTransferFile.write(PAYLOAD, null);
        Assertions.assertFalse(ConnectionTransferFile.isEncrypted(file));
        Assertions.assertArrayEquals(PAYLOAD, ConnectionTransferFile.read(file, null));
        Assertions.assertEquals(ConnectionTransferFile.ENCRYPTION_NONE, header(file).get("encryption").getAsString());
    }

    @Test
    public void encryptedFileRoundTripsAndRejectsWrongOrMissingPassphrase() throws Exception {
        char[] passphrase = "correct horse battery".toCharArray();
        byte[] file = ConnectionTransferFile.write(PAYLOAD, passphrase);

        Assertions.assertTrue(ConnectionTransferFile.isEncrypted(file));
        JsonObject header = header(file);
        Assertions.assertEquals(ConnectionTransferFile.ENCRYPTION_AES_GCM, header.get("encryption").getAsString());
        Assertions.assertEquals(ConnectionTransferFile.PBKDF2_ITERATIONS, header.get("iterations").getAsInt());
        // The payload must not appear in clear text after the header (the header names the format "chromite-connections")
        int bodyStart = headerEnd(file) + 1;
        Assertions.assertFalse(new String(file, bodyStart, file.length - bodyStart, StandardCharsets.UTF_8).contains("configuration"));

        Assertions.assertArrayEquals(PAYLOAD, ConnectionTransferFile.read(file, "correct horse battery".toCharArray()));
        assertReason(ConnectionTransferException.Reason.WRONG_PASSPHRASE_OR_CHANGED,
            () -> ConnectionTransferFile.read(file, "wrong horse battery".toCharArray()));
        assertReason(ConnectionTransferException.Reason.WRONG_PASSPHRASE_OR_CHANGED,
            () -> ConnectionTransferFile.read(file, null));
    }

    @Test
    public void nonAsciiPassphraseRoundTrips() throws Exception {
        byte[] file = ConnectionTransferFile.write(PAYLOAD, "비밀번호는 열두 글자 이상".toCharArray());
        Assertions.assertArrayEquals(PAYLOAD, ConnectionTransferFile.read(file, "비밀번호는 열두 글자 이상".toCharArray()));
    }

    @Test
    public void changedByteInCipherTextFailsAuthentication() throws Exception {
        char[] passphrase = "correct horse battery".toCharArray();
        byte[] file = ConnectionTransferFile.write(PAYLOAD, passphrase);
        byte[] tampered = Arrays.copyOf(file, file.length);
        tampered[tampered.length - 1] ^= 0x01;
        assertReason(ConnectionTransferException.Reason.WRONG_PASSPHRASE_OR_CHANGED,
            () -> ConnectionTransferFile.read(tampered, "correct horse battery".toCharArray()));
    }

    @Test
    public void changedHeaderFailsAuthenticationThroughAad() throws Exception {
        byte[] file = ConnectionTransferFile.write(PAYLOAD, "correct horse battery".toCharArray());
        // A field the decryption does not use: the key and IV stay the same, only the authenticated header differs
        byte[] tampered = withHeader(file, header -> header.addProperty("comment", "edited"));
        assertReason(ConnectionTransferException.Reason.WRONG_PASSPHRASE_OR_CHANGED,
            () -> ConnectionTransferFile.read(tampered, "correct horse battery".toCharArray()));
    }

    @Test
    public void iterationCountOutsideBoundsIsRejectedBeforeKeyDerivation() throws Exception {
        byte[] file = ConnectionTransferFile.write(PAYLOAD, "correct horse battery".toCharArray());
        for (int iterations : new int[] {ConnectionTransferFile.MIN_ITERATIONS - 1, ConnectionTransferFile.MAX_ITERATIONS + 1}) {
            byte[] changed = withHeader(file, header -> header.addProperty("iterations", iterations));
            assertReason(ConnectionTransferException.Reason.UNSUPPORTED_FORMAT,
                () -> ConnectionTransferFile.read(changed, "correct horse battery".toCharArray()));
        }
        byte[] shortTag = withHeader(file, header -> header.addProperty("tagBits", 96));
        assertReason(ConnectionTransferException.Reason.UNSUPPORTED_FORMAT,
            () -> ConnectionTransferFile.read(shortTag, "correct horse battery".toCharArray()));
    }

    @Test
    public void unknownFilesAndVersionsAreRejected() throws Exception {
        assertReason(ConnectionTransferException.Reason.NOT_A_TRANSFER_FILE,
            () -> ConnectionTransferFile.read("just some text".getBytes(StandardCharsets.UTF_8), null));
        assertReason(ConnectionTransferException.Reason.NOT_A_TRANSFER_FILE,
            () -> ConnectionTransferFile.read("{\"format\":\"other\",\"version\":1}\n{}".getBytes(StandardCharsets.UTF_8), null));
        byte[] plain = ConnectionTransferFile.write(PAYLOAD, null);
        byte[] newer = withHeader(plain, header -> header.addProperty("version", ConnectionTransferFile.FORMAT_VERSION + 1));
        assertReason(ConnectionTransferException.Reason.UNSUPPORTED_FORMAT, () -> ConnectionTransferFile.read(newer, null));
        byte[] otherCipher = withHeader(plain, header -> header.addProperty("encryption", "ROT13"));
        assertReason(ConnectionTransferException.Reason.UNSUPPORTED_FORMAT,
            () -> ConnectionTransferFile.read(otherCipher, "correct horse battery".toCharArray()));
    }

    private interface Reading {
        void read() throws Exception;
    }

    private static void assertReason(@NotNull ConnectionTransferException.Reason reason, @NotNull Reading reading) {
        ConnectionTransferException error = Assertions.assertThrows(ConnectionTransferException.class, reading::read);
        Assertions.assertEquals(reason, error.getReason(), error.getMessage());
    }

    private static int headerEnd(@NotNull byte[] file) {
        for (int i = 0; i < file.length; i++) {
            if (file[i] == '\n') {
                return i;
            }
        }
        throw new AssertionError("No header line");
    }

    @NotNull
    private static JsonObject header(@NotNull byte[] file) {
        return JsonParser.parseString(new String(file, 0, headerEnd(file), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @NotNull
    private static byte[] withHeader(@NotNull byte[] file, @NotNull Consumer<JsonObject> change) {
        int end = headerEnd(file);
        JsonObject header = header(file);
        change.accept(header);
        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[headerBytes.length + file.length - end];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(file, end, result, headerBytes.length, file.length - end);
        return result;
    }
}
