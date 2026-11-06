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
package com.seizia.chromite.ext.mssql.model.agent;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.model.DBPObjectWithOrdinalPosition;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.struct.DBSObject;
import org.eclipse.osgi.util.NLS;

/**
 * One SQL Server Agent error log file: the current log (archive 0) or an archive (1, 2, …).
 *
 * 설계: SSMS의 "오류 로그" 폴더처럼 {@code xp_enumerrorlogs 2}가 알려 주는 파일마다 노드 하나를 두고, 내용은 열 때
 * {@code xp_readerrorlog <archive>, 2}로 읽는다. Linux의 SQL Server는 에이전트 로그 목록을 돌려주지 않아(2026-11-07 확인, 2022 CU) 그때는
 * 현재 로그 하나만 둔다. 이름 순서가 아니라 보관 번호 순서로 보이도록 {@link DBPObjectWithOrdinalPosition}이다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
public class SQLServerAgentErrorLog implements DBSObject, DBPObjectWithOrdinalPosition {

    private final SQLServerAgent agent;
    private final int archiveNumber;
    @Nullable
    private final String date;
    private final long size;

    SQLServerAgentErrorLog(@NotNull SQLServerAgent agent, int archiveNumber, @Nullable String date, long size) {
        this.agent = agent;
        this.archiveNumber = archiveNumber;
        this.date = date;
        this.size = size;
    }

    @NotNull
    public SQLServerAgent getAgent() {
        return agent;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        if (archiveNumber == 0) {
            return date == null ? SQLServerAgentMessages.error_log_current_no_date : NLS.bind(SQLServerAgentMessages.error_log_current, date);
        }
        return NLS.bind(SQLServerAgentMessages.error_log_archive, archiveNumber, date == null ? "" : date); //$NON-NLS-1$
    }

    /**
     * 0 for the current log.
     */
    @Property(viewable = true, order = 2)
    public int getArchiveNumber() {
        return archiveNumber;
    }

    @Property(viewable = true, order = 3)
    public long getSize() {
        return size;
    }

    @Override
    public int getOrdinalPosition() {
        return archiveNumber;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public SQLServerAgent getParentObject() {
        return agent;
    }

    @NotNull
    @Override
    public SQLServerDataSource getDataSource() {
        return agent.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Override
    public String toString() {
        return getName();
    }
}
