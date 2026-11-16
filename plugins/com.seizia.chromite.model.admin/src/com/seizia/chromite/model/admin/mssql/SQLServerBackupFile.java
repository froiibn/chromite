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

/**
 * 백업 세트에 든 데이터베이스 파일 하나({@code RESTORE FILELISTONLY}의 한 행).
 *
 * 설계: 복원할 때 옮길 위치({@code WITH MOVE})는 이 값에 두지 않고 {@link SQLServerRestoreSettings}가 논리 이름별로 가진다.
 * 백업 파일에서 읽은 값과 사용자가 고친 값을 나눠, 대상 이름이 바뀔 때 원래 경로에서 다시 계산할 수 있게 하려는 것이다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 백업·복원 (docs/chromite/swing-backup-restore.md)
 */
public record SQLServerBackupFile(
    @NotNull String logicalName,
    @NotNull String physicalName,
    @NotNull String type,
    @Nullable String fileGroup,
    long size
) {
    /**
     * FILELISTONLY type of a log file (others are data: D rows, F full-text, S FILESTREAM).
     */
    public static final String TYPE_LOG = "L"; //$NON-NLS-1$

    public boolean isLogFile() {
        return TYPE_LOG.equals(type);
    }
}
