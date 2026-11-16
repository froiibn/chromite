/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.task;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPNamedObject;
import com.seizia.chromite.model.DBPObjectWithDescription;
import com.seizia.chromite.model.app.DBPProject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.Map;

/**
 * Task configuration
 *
 * 변경 이력(Chromite):
 * - 2026-11-12 {@link #getSchedule}/{@link #setSchedule}: 앱 안 예약을 작업과 함께 저장(앱 변경 #104, docs/chromite/task-scheduling.md "저장")
 */
public interface DBTTask extends DBPNamedObject, DBPObjectWithDescription {
    @NotNull
    String getId();

    @NotNull
    DBPProject getProject();

    @NotNull
    Date getCreateTime();

    @NotNull
    Date getUpdateTime();

    @NotNull
    DBTTaskType getType();

    @Nullable
    DBTTaskFolder getTaskFolder();

    @NotNull
    Map<String, Object> getProperties();

    void setProperties(@NotNull Map<String, Object> properties);

    /**
     * Chromite: the in-app schedule of the task as keys and values ({@code every}/{@code daily}/{@code weekdays}, {@code enabled},
     * {@code since}); empty when the task has none. Saved with the task, apart from the handler settings ({@link #getProperties}),
     * which the task editors replace as a whole. The front-end scheduler reads it (docs/chromite/task-scheduling.md).
     */
    @NotNull
    Map<String, String> getSchedule();

    void setSchedule(@NotNull Map<String, String> schedule);

    boolean isTemporary();

    @Nullable
    DBTTaskRun getLastRun();

    @NotNull
    DBTTaskRun[] getAllRuns();

    @Nullable
    Path getRunLog(@NotNull DBTTaskRun run);

    @NotNull
    InputStream getRunLogInputStream(@NotNull DBTTaskRun run) throws DBException, IOException;

    void removeRun(DBTTaskRun taskRun);

    void cleanRunStatistics();

    /**
     * Refreshes run statistics. This is a <b>thread blocking operation</b>.
     */
    void refreshRunStatistics();

    /**
     * A duration that represent the total execution time allocated for a task to finish. When expired, the task is terminated.
     * <p>
     * The duration must be ignored if it's not {@link Duration#isPositive()}.
     *
     * @return total execution time allocated for a task to finish
     */
    @NotNull
    Duration getMaxExecutionTime();
}
