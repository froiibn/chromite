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
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server 데이터베이스 백업 설정과 그 T-SQL({@code BACKUP DATABASE / BACKUP LOG}).
 *
 * 설계: DBeaver CE에는 SQL Server 백업 작업이 없어(ext.mssql의 tasks는 테이블 도구뿐) 모델 쪽에 새로 둔다. 문장 모양은
 * SSMS의 Back Up Database 대화상자가 만드는 스크립트를 따른다(같은 옵션 이름과 순서: DIFFERENTIAL, COPY_ONLY, NOFORMAT/FORMAT,
 * NOINIT/INIT, MEDIANAME, NAME, SKIP, NOREWIND, NOUNLOAD, COMPRESSION, CHECKSUM, STATS). 만료 날짜 옵션이 없으므로
 * SSMS 기본처럼 늘 SKIP(만료 검사 안 함)이다. "백업 확인"은 SSMS와 같이 방금 만든 세트를 msdb에서 찾아
 * {@code RESTORE VERIFYONLY}하는 별도 일괄 처리로 만든다.
 * 결과는 일괄 처리(GO로 나뉘는 단위) 목록이다. 실행은 일괄 처리마다 따로 하고({@link SQLServerBackupRestore#execute}),
 * 스크립트 창에는 GO로 이어 보여 준다({@link SQLServerBackupRestore#toScript}). 경로는 서버의 경로다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 */
public class SQLServerBackupSettings {

    public enum BackupType {
        FULL,
        DIFFERENTIAL,
        LOG
    }

    /**
     * What happens to the backup sets already in the file (SSMS Media Options "Overwrite media").
     */
    public enum MediaMode {
        /**
         * Append to the existing backup set (NOINIT).
         */
        APPEND,
        /**
         * Overwrite all existing backup sets (INIT).
         */
        OVERWRITE,
        /**
         * Back up to a new media set and erase all existing backup sets (FORMAT, INIT, MEDIANAME).
         */
        NEW_MEDIA_SET
    }

    public enum Compression {
        /**
         * Use the server setting ("backup compression default"): no option in the statement.
         */
        SERVER_DEFAULT,
        COMPRESS,
        NO_COMPRESSION
    }

    /**
     * Percent step of the progress messages (SSMS uses 10 in its scripts).
     */
    public static final int DEFAULT_STATS = 10;

    private final String databaseName;
    private BackupType backupType = BackupType.FULL;
    private boolean copyOnly;
    private String destination = ""; //$NON-NLS-1$
    private MediaMode mediaMode = MediaMode.APPEND;
    private String mediaName = ""; //$NON-NLS-1$
    private String mediaDescription = ""; //$NON-NLS-1$
    private String backupSetName = ""; //$NON-NLS-1$
    private String backupSetDescription = ""; //$NON-NLS-1$
    private Compression compression = Compression.SERVER_DEFAULT;
    private boolean checksum;
    private boolean continueAfterError;
    private boolean verify;
    private int stats = DEFAULT_STATS;

    public SQLServerBackupSettings(@NotNull String databaseName) {
        this.databaseName = databaseName;
    }

    @NotNull
    public String getDatabaseName() {
        return databaseName;
    }

    @NotNull
    public BackupType getBackupType() {
        return backupType;
    }

    public void setBackupType(@NotNull BackupType backupType) {
        this.backupType = backupType;
    }

    public boolean isCopyOnly() {
        return copyOnly;
    }

    public void setCopyOnly(boolean copyOnly) {
        this.copyOnly = copyOnly;
    }

    /**
     * Backup file path on the server.
     */
    @NotNull
    public String getDestination() {
        return destination;
    }

    public void setDestination(@NotNull String destination) {
        this.destination = destination;
    }

    @NotNull
    public MediaMode getMediaMode() {
        return mediaMode;
    }

    public void setMediaMode(@NotNull MediaMode mediaMode) {
        this.mediaMode = mediaMode;
    }

    @NotNull
    public String getMediaName() {
        return mediaName;
    }

    public void setMediaName(@NotNull String mediaName) {
        this.mediaName = mediaName;
    }

    @NotNull
    public String getMediaDescription() {
        return mediaDescription;
    }

    public void setMediaDescription(@NotNull String mediaDescription) {
        this.mediaDescription = mediaDescription;
    }

    @NotNull
    public String getBackupSetName() {
        return backupSetName;
    }

    public void setBackupSetName(@NotNull String backupSetName) {
        this.backupSetName = backupSetName;
    }

    @NotNull
    public String getBackupSetDescription() {
        return backupSetDescription;
    }

    public void setBackupSetDescription(@NotNull String backupSetDescription) {
        this.backupSetDescription = backupSetDescription;
    }

    @NotNull
    public Compression getCompression() {
        return compression;
    }

    public void setCompression(@NotNull Compression compression) {
        this.compression = compression;
    }

    public boolean isChecksum() {
        return checksum;
    }

    public void setChecksum(boolean checksum) {
        this.checksum = checksum;
    }

    public boolean isContinueAfterError() {
        return continueAfterError;
    }

    public void setContinueAfterError(boolean continueAfterError) {
        this.continueAfterError = continueAfterError;
    }

    public boolean isVerify() {
        return verify;
    }

    public void setVerify(boolean verify) {
        this.verify = verify;
    }

    public int getStats() {
        return stats;
    }

    public void setStats(int stats) {
        this.stats = stats;
    }

    /**
     * Why the settings can't be run (English, the UI shows its own text for the same checks), or null.
     */
    @Nullable
    public String validate() {
        if (CommonUtils.isEmptyTrimmed(destination)) {
            return "Backup destination is not specified"; //$NON-NLS-1$
        }
        if (CommonUtils.isEmptyTrimmed(backupSetName)) {
            return "Backup set name is not specified"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The batches that perform the backup: the BACKUP statement and, with {@link #isVerify()}, the verification.
     */
    @NotNull
    public List<String> buildBatches() {
        List<String> batches = new ArrayList<>();
        String database = SQLServerBackupRestore.quoteName(databaseName);
        StringBuilder sql = new StringBuilder();
        sql.append(backupType == BackupType.LOG ? "BACKUP LOG " : "BACKUP DATABASE ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(database)
            .append(" TO DISK = ").append(SQLServerBackupRestore.quoteString(destination.trim())) //$NON-NLS-1$
            .append(" WITH "); //$NON-NLS-1$
        List<String> options = new ArrayList<>();
        if (backupType == BackupType.DIFFERENTIAL) {
            options.add("DIFFERENTIAL"); //$NON-NLS-1$
        }
        if (copyOnly) {
            options.add("COPY_ONLY"); //$NON-NLS-1$
        }
        switch (mediaMode) {
            case APPEND -> {
                options.add("NOFORMAT"); //$NON-NLS-1$
                options.add("NOINIT"); //$NON-NLS-1$
            }
            case OVERWRITE -> {
                options.add("NOFORMAT"); //$NON-NLS-1$
                options.add("INIT"); //$NON-NLS-1$
            }
            case NEW_MEDIA_SET -> {
                options.add("FORMAT"); //$NON-NLS-1$
                options.add("INIT"); //$NON-NLS-1$
                if (!CommonUtils.isEmptyTrimmed(mediaName)) {
                    options.add("MEDIANAME = " + SQLServerBackupRestore.quoteString(mediaName.trim())); //$NON-NLS-1$
                }
                if (!CommonUtils.isEmptyTrimmed(mediaDescription)) {
                    options.add("MEDIADESCRIPTION = " + SQLServerBackupRestore.quoteString(mediaDescription.trim())); //$NON-NLS-1$
                }
            }
        }
        options.add("NAME = " + SQLServerBackupRestore.quoteString(backupSetName.trim())); //$NON-NLS-1$
        if (!CommonUtils.isEmptyTrimmed(backupSetDescription)) {
            options.add("DESCRIPTION = " + SQLServerBackupRestore.quoteString(backupSetDescription.trim())); //$NON-NLS-1$
        }
        options.add("SKIP"); //$NON-NLS-1$
        options.add("NOREWIND"); //$NON-NLS-1$
        options.add("NOUNLOAD"); //$NON-NLS-1$
        switch (compression) {
            case COMPRESS -> options.add("COMPRESSION"); //$NON-NLS-1$
            case NO_COMPRESSION -> options.add("NO_COMPRESSION"); //$NON-NLS-1$
            case SERVER_DEFAULT -> {
                // The server's "backup compression default" applies
            }
        }
        if (checksum) {
            options.add("CHECKSUM"); //$NON-NLS-1$
        }
        if (continueAfterError) {
            options.add("CONTINUE_AFTER_ERROR"); //$NON-NLS-1$
        }
        if (stats > 0) {
            options.add("STATS = " + stats); //$NON-NLS-1$
        }
        sql.append(String.join(", ", options)); //$NON-NLS-1$
        batches.add(sql.toString());

        if (verify) {
            // The position of the set just written: the latest backup set of the database in msdb (as SSMS scripts it)
            String name = SQLServerBackupRestore.quoteString(databaseName);
            String quotedForMessage = SQLServerBackupRestore.quoteString(
                "Verify failed. Backup information for database '" + databaseName + "' not found."); //$NON-NLS-1$ //$NON-NLS-2$
            batches.add(
                "DECLARE @backupSetId AS INT\n" + //$NON-NLS-1$
                "SELECT @backupSetId = position FROM msdb..backupset WHERE database_name = " + name + //$NON-NLS-1$
                " AND backup_set_id = (SELECT MAX(backup_set_id) FROM msdb..backupset WHERE database_name = " + name + ")\n" + //$NON-NLS-1$ //$NON-NLS-2$
                "IF @backupSetId IS NULL BEGIN RAISERROR(" + quotedForMessage + ", 16, 1) END\n" + //$NON-NLS-1$ //$NON-NLS-2$
                "RESTORE VERIFYONLY FROM DISK = " + SQLServerBackupRestore.quoteString(destination.trim()) + //$NON-NLS-1$
                " WITH FILE = @backupSetId, NOUNLOAD, NOREWIND"); //$NON-NLS-1$
        }
        return batches;
    }
}
