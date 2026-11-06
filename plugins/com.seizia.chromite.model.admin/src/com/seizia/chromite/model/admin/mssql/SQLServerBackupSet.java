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
package com.seizia.chromite.model.admin.mssql;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 백업 파일 안의 백업 세트 하나({@code RESTORE HEADERONLY}의 한 행).
 *
 * 설계: 복원 계획(어느 세트를 어떤 순서로 복원할지)은 LSN으로 정해야 하므로 LSN 열을 그대로 둔다
 * ({@link SQLServerRestoreSettings#selectDefaultPlan}). 형식 번호는 {@code HEADERONLY}의 BackupType 값이다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 */
public record SQLServerBackupSet(
    int position,
    @Nullable String name,
    @Nullable String description,
    int backupType,
    @NotNull String databaseName,
    @Nullable String serverName,
    @Nullable String userName,
    @Nullable Date startDate,
    @Nullable Date finishDate,
    @Nullable BigDecimal firstLsn,
    @Nullable BigDecimal lastLsn,
    @Nullable BigDecimal checkpointLsn,
    @Nullable BigDecimal databaseBackupLsn,
    long backupSize,
    boolean copyOnly
) {
    /**
     * BackupType of a full database backup.
     */
    public static final int TYPE_DATABASE = 1;
    /**
     * BackupType of a transaction log backup.
     */
    public static final int TYPE_LOG = 2;
    /**
     * BackupType of a differential database backup.
     */
    public static final int TYPE_DIFFERENTIAL = 5;

    public boolean isLogBackup() {
        return backupType == TYPE_LOG;
    }
}
