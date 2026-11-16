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

/**
 * The check a received update file did not pass.
 *
 * 설계: 화면 문구는 UI 번들이 이 값으로 고른다(모델에는 화면 문구를 두지 않는다). {@link #id()}는 상태 파일
 * ({@code update.json})에 남는 이름이라 바꾸지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public enum UpdateStep {
    /** The file's size is not the list's. */
    SIZE,
    /** The file's SHA-256 is not the list's. */
    SHA256,
    /** The signature could not be read (PowerShell blocked, codesign missing, time limit). */
    CHECKER,
    /** Windows: Authenticode is not {@code Valid}. macOS: {@code codesign --verify --deep --strict} failed. */
    SIGNATURE,
    /** Windows: the signer is not our publisher. macOS: another bundle id or Team ID than the running app. */
    PUBLISHER,
    /** Windows: the signature has no timestamp. */
    TIMESTAMP,
    /** Windows: the file's ProductName is not Chromite. */
    PRODUCT,
    /** The file's version is not the list's. */
    VERSION,
    /** macOS: the zip holds other names than the app bundle, or could not be extracted. */
    EXTRACT,
    /** macOS: Gatekeeper does not accept the app as notarized. */
    NOTARIZATION;

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
