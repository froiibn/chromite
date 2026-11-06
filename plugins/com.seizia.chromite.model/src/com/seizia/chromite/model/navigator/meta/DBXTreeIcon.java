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

package com.seizia.chromite.model.navigator.meta;

import org.apache.commons.jexl3.JexlExpression;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.impl.AbstractDescriptor;

/**
 * DBXTreeIcon
 */
public class DBXTreeIcon {
    private static final Log log = Log.getLog(DBXTreeIcon.class);

    private final String exprString;
    private final DBPImage icon;
    private JexlExpression expression;

    public DBXTreeIcon(String exprString, DBPImage icon)
    {
        this.exprString = exprString;
        this.icon = icon;
        try {
            this.expression = AbstractDescriptor.parseExpression(exprString);
        } catch (DBException ex) {
            log.warn("Can't parse icon expression: " + exprString, ex);
        }
    }

    public void dispose()
    {
    }

    public String getExprString()
    {
        return exprString;
    }

    public JexlExpression getExpression()
    {
        return expression;
    }

    public DBPImage getIcon()
    {
        return icon;
    }
}
