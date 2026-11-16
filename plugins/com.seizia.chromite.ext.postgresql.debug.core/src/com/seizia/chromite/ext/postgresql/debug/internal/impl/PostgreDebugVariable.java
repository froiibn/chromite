/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2017-2018 Andrew Khitrin (ahitrin@gmail.com)
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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
package com.seizia.chromite.ext.postgresql.debug.internal.impl;

import com.seizia.chromite.debug.DBGVariable;

/**
 * A row of {@code pldbg_get_variables}: varclass {@code A} is an argument, {@code L} a local variable.
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 서버가 이름 지은 형식(format_type)을 둠, getType() 대신
 *   getTypeName()·isArgument() (docs/chromite/procedure-debugger.md)
 */
public class PostgreDebugVariable implements DBGVariable<String> {

    private static final String VARCLASS_ARGUMENT = "A"; //$NON-NLS-1$

    private final String name;
    private final String varclass;
    private final int lineNumber;
    private final boolean unique;
    private final boolean constant;
    private final boolean notnull;
    private final int oid;
    private final String typeName;
    private final String val;

    @Override
    public String getVal() {
        return val;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getTypeName() {
        return typeName;
    }

    @Override
    public boolean isArgument() {
        return VARCLASS_ARGUMENT.equals(varclass);
    }

    public PostgreDebugVariable(String name, String varclass, int linenumber, boolean unique, boolean constant,
            boolean notnull, int oid, String typeName, String val) {
        super();
        this.name = name;
        this.varclass = varclass;
        this.lineNumber = linenumber;
        this.unique = unique;
        this.constant = constant;
        this.notnull = notnull;
        this.oid = oid;
        this.typeName = typeName;
        this.val = val;
    }

    public String getVarclass() {
        return varclass;
    }

    @Override
    public int getLineNumber() {
        return lineNumber;
    }

    public boolean isUnique() {
        return unique;
    }

    public boolean isConstant() {
        return constant;
    }

    public boolean isNotnull() {
        return notnull;
    }

    public int getOid() {
        return oid;
    }

    @SuppressWarnings("nls")
    @Override
    public String toString() {
        return "PostgreDebugVariable [name=" + name + ", val=" + val + ", varclass=" + varclass + ", lineNumber="
                + lineNumber + ", unique=" + unique + ", constant=" + constant + ", notnull=" + notnull + ", oid=" + oid
                + ", type=" + typeName + "]";
    }

}
