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
 * A settings file ({@link SettingsTransfer}) or a settings backup ({@link SettingsBackup}) that cannot be read.
 *
 * 설계: {@link ConnectionTransferException}과 같이, 모델 번들에는 화면 문구를 두지 않으므로 화면은 이유({@link Reason})에 따라
 * 지역화된 문구를 보이고 예외 문구(영어)는 로그와 오류 상세에 남는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/settings-transfer.md)
 */
public class SettingsTransferException extends DBException {

    public enum Reason {
        /**
         * Not a Chromite settings file (not a zip, or no manifest of the right format).
         */
        NOT_A_SETTINGS_FILE,
        /**
         * Written by a newer format version.
         */
        UNSUPPORTED_FORMAT,
        /**
         * The file breaks a limit or a rule: too many or too large entries, or an entry path outside its folder.
         */
        INVALID_CONTENT
    }

    @NotNull
    private final Reason reason;

    public SettingsTransferException(@NotNull Reason reason, @NotNull String message) {
        this(reason, message, null);
    }

    public SettingsTransferException(@NotNull Reason reason, @NotNull String message, @Nullable Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    @NotNull
    public Reason getReason() {
        return reason;
    }
}
