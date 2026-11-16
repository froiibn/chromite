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
package com.seizia.chromite.model.update;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The checks of a received update file before anything of it runs ({@link WindowsUpdateVerifier},
 * {@link MacUpdateVerifier}).
 *
 * 설계: 크기와 SHA-256은 "목록과 같은 파일"만 보증한다(사이트가 뚫리면 목록과 파일이 함께 바뀐다). 진짜 닻은 서명이다
 * (Windows Authenticode의 게시자, macOS의 Team ID와 공증). 확인을 통과하지 못한 단계는 {@link Verdict#step}으로 알린다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public interface UpdateVerifier {

    /**
     * Checks {@code file} against its list entry. Nothing of the file runs.
     */
    @NotNull
    Verdict verify(@NotNull Path file, @NotNull UpdateEntry entry) throws InterruptedException;

    /**
     * @param step the check that failed, null when all passed
     * @param app  macOS: the app bundle extracted from the zip (what the helper moves into place)
     */
    record Verdict(@Nullable UpdateStep step, @Nullable Path app) {
        public static final Verdict OK = new Verdict(null, null);

        @NotNull
        public static Verdict fail(@NotNull UpdateStep step) {
            return new Verdict(step, null);
        }

        public boolean ok() {
            return step == null;
        }
    }

    /**
     * Size and SHA-256 of {@code file} against the list: the first checks of every platform.
     */
    @Nullable
    static UpdateStep checkBytes(@NotNull Path file, @NotNull UpdateEntry entry) {
        try {
            if (Files.size(file) != entry.size()) {
                return UpdateStep.SIZE;
            }
            return sha256Of(file).equals(entry.sha256()) ? null : UpdateStep.SHA256;
        } catch (IOException e) {
            return UpdateStep.SIZE;
        }
    }

    @NotNull
    static String sha256Of(@NotNull Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buffer)) > 0; ) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
