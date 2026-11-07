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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

/**
 * A connection transfer file that cannot be read.
 *
 * 설계: 화면은 이유({@link Reason})에 따라 지역화된 문구를 보인다. 모델 번들에는 화면 문구를 두지 않으므로
 * 예외 문구(영어)와 이유를 따로 둔다. 잘못된 암호와 바뀐 파일은 AES-GCM에서 구별할 수 없어(둘 다 인증 태그 불일치) 한 이유다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/connection-transfer.md)
 */
public class ConnectionTransferException extends DBException {

    public enum Reason {
        /**
         * Not a Chromite connection file, or its plain header is damaged.
         */
        NOT_A_TRANSFER_FILE,
        /**
         * Written by a newer format version or with an unknown cipher or KDF.
         */
        UNSUPPORTED_FORMAT,
        /**
         * GCM authentication failed: wrong passphrase, or the file was changed.
         */
        WRONG_PASSPHRASE_OR_CHANGED,
        /**
         * Decrypted, but the content is not a valid connection list.
         */
        INVALID_CONTENT
    }

    @NotNull
    private final Reason reason;

    public ConnectionTransferException(@NotNull Reason reason, @NotNull String message) {
        this(reason, message, null);
    }

    public ConnectionTransferException(@NotNull Reason reason, @NotNull String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    @NotNull
    public Reason getReason() {
        return reason;
    }
}
