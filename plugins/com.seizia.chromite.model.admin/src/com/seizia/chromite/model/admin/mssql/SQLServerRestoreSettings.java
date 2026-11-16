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

import java.math.BigDecimal;
import java.util.*;

/**
 * SQL Server 데이터베이스 복원 설정과 그 T-SQL({@code RESTORE DATABASE / RESTORE LOG}).
 *
 * 설계: SSMS Restore Database 대화상자의 "장치에서 복원" 흐름을 따른다. 백업 파일의 세트 목록({@code HEADERONLY})에서
 * 복원할 세트를 고르고, 첫 데이터베이스 세트의 파일 목록({@code FILELISTONLY})으로 파일 위치({@code WITH MOVE})를 정한다.
 * <ul>
 *     <li>기본 복원 계획({@link #selectDefaultPlan}): 마지막 전체 백업, 그 전체 백업을 기준으로 한 마지막 차등 백업,
 *     그 뒤로 끊기지 않는 로그 백업. LSN(전체 백업의 CheckpointLSN = 차등 백업의 DatabaseBackupLSN, 로그 체인은
 *     FirstLSN ≤ 앞 세트의 LastLSN)으로 이어지는 것만 고른다. 파일에 다른 데이터베이스의 세트가 섞여 있으면 마지막 전체 백업의
 *     데이터베이스만 본다.</li>
 *     <li>세트마다 문장 하나: 마지막 세트만 사용자가 고른 복구 상태(RECOVERY/NORECOVERY)이고 나머지는 NORECOVERY다.
 *     REPLACE와 MOVE는 처음 만드는 문장(첫 세트)에만 붙인다(뒤 세트는 이미 만든 파일에 이어 쓴다).</li>
 *     <li>파일 위치 기본값({@link #defaultTargetPath}): 대상 이름이 원래 데이터베이스와 같으면 원래 경로, 다르면 파일 이름의
 *     원래 데이터베이스 이름을 대상 이름으로 바꾼다(없으면 앞에 붙인다). SSMS가 새 이름으로 복원할 때 파일이 원본과 부딪치지
 *     않게 하는 것과 같은 목적이다. "모든 파일을 폴더로 옮기기"를 켜면 데이터·로그 폴더로 옮긴다.</li>
 *     <li>"기존 연결 닫기"는 SSMS와 같이 복원 앞에 {@code SET SINGLE_USER WITH ROLLBACK IMMEDIATE}, 뒤에 {@code SET MULTI_USER}다.
 *     대상 데이터베이스가 서버에 있을 때만 넣고, 복원 뒤 데이터베이스가 복원 중 상태로 남는(NORECOVERY) 경우에는
 *     뒤 문장을 넣지 않는다(복원 중인 데이터베이스는 ALTER할 수 없다).</li>
 *     <li>모든 일괄 처리는 {@code USE [master]}로 시작하는 연결에서 실행한다(복원 대상 데이터베이스를 쓰고 있으면 복원할 수 없다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 */
public class SQLServerRestoreSettings {

    private String sourcePath = ""; //$NON-NLS-1$
    private final List<SQLServerBackupSet> backupSets = new ArrayList<>();
    private final Set<Integer> selectedPositions = new TreeSet<>();
    private final List<SQLServerBackupFile> files = new ArrayList<>();
    private final Map<String, String> fileTargets = new LinkedHashMap<>();
    private String targetDatabase = ""; //$NON-NLS-1$
    private boolean targetExists;
    private boolean replace;
    private boolean recovery = true;
    private boolean closeConnections;
    private int stats = SQLServerBackupSettings.DEFAULT_STATS;

    /**
     * Backup file path on the server.
     */
    @NotNull
    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(@NotNull String sourcePath) {
        this.sourcePath = sourcePath;
    }

    /**
     * Backup sets of the source file, in file order.
     */
    @NotNull
    public List<SQLServerBackupSet> getBackupSets() {
        return backupSets;
    }

    public void setBackupSets(@NotNull List<SQLServerBackupSet> sets) {
        backupSets.clear();
        backupSets.addAll(sets);
        backupSets.sort(Comparator.comparingInt(SQLServerBackupSet::position));
        selectedPositions.clear();
    }

    public boolean isSelected(@NotNull SQLServerBackupSet set) {
        return selectedPositions.contains(set.position());
    }

    public void setSelected(@NotNull SQLServerBackupSet set, boolean selected) {
        if (selected) {
            selectedPositions.add(set.position());
        } else {
            selectedPositions.remove(set.position());
        }
    }

    /**
     * The selected sets in restore (file) order.
     */
    @NotNull
    public List<SQLServerBackupSet> getSelectedSets() {
        List<SQLServerBackupSet> result = new ArrayList<>();
        for (SQLServerBackupSet set : backupSets) {
            if (selectedPositions.contains(set.position())) {
                result.add(set);
            }
        }
        return result;
    }

    /**
     * The first selected set that restores the database (full or differential): its file list is the one to relocate.
     */
    @Nullable
    public SQLServerBackupSet getBaseSet() {
        for (SQLServerBackupSet set : getSelectedSets()) {
            if (!set.isLogBackup()) {
                return set;
            }
        }
        return null;
    }

    /**
     * Selects the latest full backup with the differential and log backups that continue it (see the class comment).
     */
    public void selectDefaultPlan() {
        selectedPositions.clear();
        SQLServerBackupSet full = null;
        for (SQLServerBackupSet set : backupSets) {
            if (set.backupType() == SQLServerBackupSet.TYPE_DATABASE) {
                full = set;
            }
        }
        if (full == null) {
            return;
        }
        selectedPositions.add(full.position());
        SQLServerBackupSet last = full;
        if (!full.copyOnly()) {
            // A copy-only full backup is not a differential base
            for (SQLServerBackupSet set : backupSets) {
                if (set.position() > full.position() && set.backupType() == SQLServerBackupSet.TYPE_DIFFERENTIAL
                    && full.databaseName().equalsIgnoreCase(set.databaseName())
                    && Objects.equals(full.checkpointLsn(), set.databaseBackupLsn())) {
                    last = set;
                }
            }
            if (last != full) {
                selectedPositions.add(last.position());
            }
        }
        BigDecimal chainEnd = last.lastLsn();
        if (chainEnd == null) {
            return;
        }
        for (SQLServerBackupSet set : backupSets) {
            if (set.position() <= last.position() || !set.isLogBackup()
                || !full.databaseName().equalsIgnoreCase(set.databaseName())
                || set.firstLsn() == null || set.lastLsn() == null) {
                continue;
            }
            if (set.lastLsn().compareTo(chainEnd) <= 0) {
                // Already covered by the base (an older log backup)
                continue;
            }
            if (set.firstLsn().compareTo(chainEnd) > 0) {
                // A gap in the log chain: later log backups can't be applied
                break;
            }
            selectedPositions.add(set.position());
            chainEnd = set.lastLsn();
        }
    }

    /**
     * Files of the base set.
     */
    @NotNull
    public List<SQLServerBackupFile> getFiles() {
        return files;
    }

    public void setFiles(@NotNull List<SQLServerBackupFile> newFiles) {
        files.clear();
        files.addAll(newFiles);
        fileTargets.clear();
    }

    /**
     * Where a file is restored to (WITH MOVE); the original path if not set.
     */
    @NotNull
    public String getTargetPath(@NotNull SQLServerBackupFile file) {
        String target = fileTargets.get(file.logicalName());
        return target != null ? target : file.physicalName();
    }

    public void setTargetPath(@NotNull SQLServerBackupFile file, @NotNull String path) {
        fileTargets.put(file.logicalName(), path);
    }

    /**
     * Sets the file targets to the defaults for the target database (see the class comment).
     *
     * @param dataFolder folder of the data files, or null to keep each file's folder
     * @param logFolder  folder of the log files, or null to keep each file's folder
     */
    public void resetTargetPaths(@Nullable String dataFolder, @Nullable String logFolder) {
        SQLServerBackupSet base = getBaseSet();
        String sourceDatabase = base == null ? targetDatabase : base.databaseName();
        fileTargets.clear();
        for (SQLServerBackupFile file : files) {
            fileTargets.put(file.logicalName(),
                defaultTargetPath(file, sourceDatabase, targetDatabase, file.isLogFile() ? logFolder : dataFolder));
        }
    }

    @NotNull
    static String defaultTargetPath(
        @NotNull SQLServerBackupFile file,
        @NotNull String sourceDatabase,
        @NotNull String targetDatabase,
        @Nullable String folder
    ) {
        String original = file.physicalName();
        int separatorPos = Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\'));
        String originalFolder = separatorPos < 0 ? "" : original.substring(0, separatorPos + 1); //$NON-NLS-1$
        String fileName = original.substring(separatorPos + 1);
        if (!CommonUtils.isEmpty(targetDatabase) && !targetDatabase.equalsIgnoreCase(sourceDatabase)) {
            int namePos = CommonUtils.isEmpty(sourceDatabase) ? -1
                : fileName.toLowerCase(Locale.ROOT).indexOf(sourceDatabase.toLowerCase(Locale.ROOT));
            fileName = namePos >= 0
                ? fileName.substring(0, namePos) + targetDatabase + fileName.substring(namePos + sourceDatabase.length())
                : targetDatabase + "_" + fileName; //$NON-NLS-1$
        }
        if (CommonUtils.isEmptyTrimmed(folder)) {
            return originalFolder + fileName;
        }
        String trimmed = folder.trim();
        char separator = trimmed.indexOf('/') >= 0 || originalFolder.indexOf('/') >= 0 ? '/' : '\\';
        return trimmed.endsWith("/") || trimmed.endsWith("\\") ? trimmed + fileName : trimmed + separator + fileName; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    public String getTargetDatabase() {
        return targetDatabase;
    }

    public void setTargetDatabase(@NotNull String targetDatabase) {
        this.targetDatabase = targetDatabase;
    }

    /**
     * The target database exists on the server (closing its connections applies).
     */
    public boolean isTargetExists() {
        return targetExists;
    }

    public void setTargetExists(boolean targetExists) {
        this.targetExists = targetExists;
    }

    /**
     * Overwrite the existing database (WITH REPLACE).
     */
    public boolean isReplace() {
        return replace;
    }

    public void setReplace(boolean replace) {
        this.replace = replace;
    }

    /**
     * RESTORE WITH RECOVERY (database ready to use), or NORECOVERY (more backups can be restored).
     */
    public boolean isRecovery() {
        return recovery;
    }

    public void setRecovery(boolean recovery) {
        this.recovery = recovery;
    }

    public boolean isCloseConnections() {
        return closeConnections;
    }

    public void setCloseConnections(boolean closeConnections) {
        this.closeConnections = closeConnections;
    }

    public int getStats() {
        return stats;
    }

    public void setStats(int stats) {
        this.stats = stats;
    }

    /**
     * Why the settings can't be run (English, the UI checks the same with its own text), or null.
     */
    @Nullable
    public String validate() {
        if (CommonUtils.isEmptyTrimmed(sourcePath)) {
            return "Backup file is not specified"; //$NON-NLS-1$
        }
        if (CommonUtils.isEmptyTrimmed(targetDatabase)) {
            return "Target database is not specified"; //$NON-NLS-1$
        }
        if (getSelectedSets().isEmpty()) {
            return "No backup set is selected"; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The batches that perform the restore (see the class comment).
     */
    @NotNull
    public List<String> buildBatches() {
        List<String> batches = new ArrayList<>();
        String database = SQLServerBackupRestore.quoteName(targetDatabase.trim());
        String source = SQLServerBackupRestore.quoteString(sourcePath.trim());
        batches.add("USE [master]"); //$NON-NLS-1$
        boolean closing = closeConnections && targetExists;
        if (closing) {
            batches.add("ALTER DATABASE " + database + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        List<SQLServerBackupSet> sets = getSelectedSets();
        boolean first = true;
        for (int i = 0; i < sets.size(); i++) {
            SQLServerBackupSet set = sets.get(i);
            boolean lastSet = i == sets.size() - 1;
            StringBuilder sql = new StringBuilder();
            sql.append(set.isLogBackup() ? "RESTORE LOG " : "RESTORE DATABASE ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(database).append(" FROM DISK = ").append(source).append(" WITH "); //$NON-NLS-1$ //$NON-NLS-2$
            List<String> options = new ArrayList<>();
            options.add("FILE = " + set.position()); //$NON-NLS-1$
            if (first && !set.isLogBackup()) {
                for (SQLServerBackupFile file : files) {
                    String target = getTargetPath(file);
                    if (!target.equals(file.physicalName())) {
                        options.add("MOVE " + SQLServerBackupRestore.quoteString(file.logicalName()) + //$NON-NLS-1$
                            " TO " + SQLServerBackupRestore.quoteString(target)); //$NON-NLS-1$
                    }
                }
            }
            options.add(lastSet && recovery ? "RECOVERY" : "NORECOVERY"); //$NON-NLS-1$ //$NON-NLS-2$
            options.add("NOUNLOAD"); //$NON-NLS-1$
            if (first && replace) {
                options.add("REPLACE"); //$NON-NLS-1$
            }
            if (stats > 0) {
                options.add("STATS = " + stats); //$NON-NLS-1$
            }
            sql.append(String.join(", ", options)); //$NON-NLS-1$
            batches.add(sql.toString());
            first = false;
        }
        if (closing && recovery) {
            batches.add("ALTER DATABASE " + database + " SET MULTI_USER"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return batches;
    }
}
