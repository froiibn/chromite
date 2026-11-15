/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.altibase.model;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPQualifiedObject;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;

import java.sql.ResultSet;

/**
 * A file of an Altibase tablespace: a disk data or temp file (V$DATAFILES) or a memory checkpoint image.
 *
 * 변경 이력:
 * - 2026-11-15 새(아직 추가하지 않은) 파일 객체와 행 다시 읽기({@link #loadRow}): Add Data File 뒤 서버의 이름(전체 경로)과 ID를 받음
 *   (앱 변경 #126, docs/chromite/altibase.md "테이블스페이스 관리"). 이름은 파일 경로라 따옴표로 감싸지 않는다({@link #getFullyQualifiedName})
 */
public abstract class AltibaseDataFile extends AltibaseObject<AltibaseTablespace> implements DBPQualifiedObject {

    protected int id;
    protected int spaceid;
    
    protected AltibaseDataFile(AltibaseTablespace parent, ResultSet dbResult) {
        super(parent, JDBCUtils.safeGetString(dbResult, "NAME"), true);
        this.id = JDBCUtils.safeGetInt(dbResult, "ID");
        this.spaceid = JDBCUtils.safeGetInt(dbResult, "SPACEID");
    }

    /**
     * A new file of the tablespace, not yet added.
     */
    protected AltibaseDataFile(@NotNull AltibaseTablespace parent) {
        super(parent, null, false);
        this.spaceid = parent.getId();
    }

    /**
     * The file path as the server reports it: a path, not an SQL identifier, so it is never quoted (the delete
     * confirmation and error messages showed it as ""/path"").
     */
    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return getName();
    }

    /**
     * Takes the name, ID and tablespace ID of a V$DATAFILES row.
     */
    protected void loadRow(@NotNull ResultSet dbResult) {
        this.name = JDBCUtils.safeGetString(dbResult, "NAME");
        this.id = JDBCUtils.safeGetInt(dbResult, "ID");
        this.spaceid = JDBCUtils.safeGetInt(dbResult, "SPACEID");
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return name;
    }
    
    @Property(viewable = true, order = 2)
    public int getId() {
        return id;
    }
    
    @Property(viewable = true, order = 3)
    public int getTbsId() {
        return spaceid;
    }
}
