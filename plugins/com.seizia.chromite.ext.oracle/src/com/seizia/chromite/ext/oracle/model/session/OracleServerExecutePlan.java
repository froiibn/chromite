/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2019 SergDzh (jurasik@bigmir.net)
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
package com.seizia.chromite.ext.oracle.model.session;

import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPObjectWithDescription;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;

import java.sql.ResultSet;

/**
 * Plan
 */
public class OracleServerExecutePlan implements DBPObjectWithDescription {


    private String plan;

    public OracleServerExecutePlan(ResultSet dbResult) {
        this.plan = JDBCUtils.safeGetString(dbResult, "PLAN_TABLE_OUTPUT");
    }

    @Property(viewable = true, order = 1)
    public String getPlan() {
        return plan;
    }

    @Nullable
    @Override
    public String getDescription() {
        return plan;
    }
}
