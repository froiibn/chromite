/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.model.sql;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLDialectFeatures;
import com.seizia.chromite.model.sql.semantics.SQLQueryModelRecognizer;
import com.seizia.chromite.model.sql.semantics.SQLQueryRecognitionContext;
import com.seizia.chromite.model.sql.semantics.SQLQuerySymbolEntry;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryExprType;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryLocalVariableInfo;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryLocalVariablesContext;
import com.seizia.chromite.model.sql.semantics.model.select.SQLQueryRowsLocalTableModel;
import com.seizia.chromite.model.stm.STMTreeNode;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

public class SQLQueryLocalVariablesContextTest extends ChromiteUnitTest {
    @Test
    void namespacesSeparateCapabilitiesAndPreserveInnerMasking() {
        SQLSyntaxManager syntax = new SQLSyntaxManager();
        syntax.init(BasicSQLDialect.INSTANCE, DBWorkbench.getPlatform().getPreferenceStore());
        var context = new SQLQueryRecognitionContext(
            new VoidProgressMonitor(), null, false, false, syntax, BasicSQLDialect.INSTANCE);
        var model = SQLQueryModelRecognizer.recognizeQuery(context, "SELECT 1");
        Assertions.assertNotNull(model);
        STMTreeNode node = model.getSyntaxNode();
        SQLQuerySymbolEntry name = new SQLQuerySymbolEntry(node, "slot", "slot", null);
        SQLQueryLocalVariableInfo scalar = new SQLQueryLocalVariableInfo(
            name, SQLDialectFeatures.LocalVariableKind.VALUE, SQLQueryExprType.UNKNOWN, null, false);
        SQLQueryLocalVariableInfo cursor = new SQLQueryLocalVariableInfo(
            name, SQLDialectFeatures.LocalVariableKind.CURSOR_VALUE, SQLQueryExprType.UNKNOWN, null, false);
        SQLQueryLocalVariablesContext outer = new SQLQueryLocalVariablesContext(BasicSQLDialect.INSTANCE).withLocalVariable(cursor);
        SQLQueryLocalVariablesContext inner = outer.withNestedBlock().withLocalVariable(scalar);
        Assertions.assertSame(scalar, inner.findLocalVariable(name, SQLDialectFeatures.LocalVariableCapability.VALUE));
        Assertions.assertNull(inner.findLocalVariable(name, SQLDialectFeatures.LocalVariableCapability.CURSOR));
        Assertions.assertSame(cursor, outer.findLocalVariable(name, SQLDialectFeatures.LocalVariableCapability.CURSOR));

        SQLDialect separate = new BasicSQLDialect() {
            @NotNull
            @Override
            public SQLDialectFeatures getDialectFeatures() {
                return new SQLDialectFeatures() {
                    @NotNull
                    @Override
                    public List<SQLDialectFeatures.LocalVariableNamespace> getLocalVariableNamespaces() {
                        return List.of(
                            new SQLDialectFeatures.LocalVariableNamespace(EnumSet.of(SQLDialectFeatures.LocalVariableCapability.VALUE)),
                            new SQLDialectFeatures.LocalVariableNamespace(EnumSet.of(SQLDialectFeatures.LocalVariableCapability.FIELDS)),
                            new SQLDialectFeatures.LocalVariableNamespace(EnumSet.of(SQLDialectFeatures.LocalVariableCapability.ROWS)),
                            new SQLDialectFeatures.LocalVariableNamespace(EnumSet.of(SQLDialectFeatures.LocalVariableCapability.CURSOR))
                        );
                    }
                };
            }
        };
        SQLQueryLocalVariableInfo table = new SQLQueryLocalVariableInfo(
            name, SQLDialectFeatures.LocalVariableKind.ROWSET, SQLQueryExprType.UNKNOWN,
            new SQLQueryRowsLocalTableModel(node, name, List.of()), false);
        SQLQueryLocalVariablesContext independent = new SQLQueryLocalVariablesContext(separate).withLocalVariable(scalar).withLocalVariable(table);
        Assertions.assertSame(scalar, independent.findLocalVariable(name, SQLDialectFeatures.LocalVariableCapability.VALUE));
        Assertions.assertSame(table, independent.findLocalVariable(name, SQLDialectFeatures.LocalVariableCapability.ROWS));
        Assertions.assertEquals(List.of(scalar), independent.getVisibleValueVariables());

        SQLDialect programDialect = new BasicSQLDialect() {
            @NotNull
            @Override
            public SQLDialectFeatures getDialectFeatures() {
                return new SQLDialectFeatures() {
                    @NotNull
                    @Override
                    public LocalVariableVisibility getLocalVariableVisibility(@NotNull LocalVariableKind kind) {
                        return LocalVariableVisibility.PROGRAM;
                    }
                };
            }
        };
        SQLQueryLocalVariablesContext program = new SQLQueryLocalVariablesContext(programDialect).resetForProgram();
        SQLQueryLocalVariablesContext branch = program.withNestedBlock().withLocalVariable(scalar);
        Assertions.assertNull(program.findLocalVariable(name, SQLDialectFeatures.LocalVariableCapability.VALUE));
        Assertions.assertSame(scalar, program.withProgramVariablesFrom(branch).findLocalVariable(name, SQLDialectFeatures.LocalVariableCapability.VALUE));
    }
}
