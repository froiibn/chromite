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
package com.seizia.chromite.model.sql;

import com.seizia.chromite.junit.ChromiteUnitTest;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.sql.semantics.SQLQueryModelRecognizer;
import com.seizia.chromite.model.sql.semantics.SQLQueryRecognitionContext;
import com.seizia.chromite.model.sql.semantics.SQLQuerySymbolOrigin;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryConnectionContext;
import com.seizia.chromite.model.sql.semantics.context.SQLQueryRowsSourceContext;
import com.seizia.chromite.model.sql.semantics.model.SQLQueryModel;
import com.seizia.chromite.model.sql.semantics.model.ddl.SQLQueryObjectDataModel;
import com.seizia.chromite.model.sql.semantics.model.dml.SQLQueryCallModel;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests of the routine name of call statements ({@code CALL x.}): the unfinished name that threw a
 * NullPointerException in object lookup, and the CALL origin the completion proposes routines from
 * (docs/chromite/swing-sql-completion.md).
 *
 * 설계: 끝나지 않은 이름({@code app.})의 빈 조각(null)이 객체 찾기까지 가서 방언의 따옴표 제거에서 NPE가 났다. 실제 연결 문맥 대신 null 조각을
 * 받으면 NPE를 던지는 연결 문맥 mock으로 이름을 해석해 null 조각이 객체 찾기에 가지 않는지, 이름의 원천이 호출문 거르기 방식(CALL)인지 본다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "자동완성")
 */
public class CallStatementNameResolutionTest extends ChromiteUnitTest {

    @Test
    public void unfinishedRoutineNameDoesNotReachObjectLookup() {
        for (String sql : List.of("CALL app.", "CALL app.proc(1)", "CALL proc()")) {
            List<List<?>> lookups = new ArrayList<>();
            SQLQueryObjectDataModel object = callTarget(sql);
            object.resolveRowSources(rowsContext(lookups), realMetadata());

            for (List<?> lookup : lookups) {
                Assertions.assertFalse(lookup.contains(null), sql + ": object lookup got an empty name part " + lookup);
            }
            SQLQuerySymbolOrigin.DbObjectRef origin = Assertions.assertInstanceOf(SQLQuerySymbolOrigin.DbObjectRef.class,
                object.getObjectNameOrigin(), sql);
            Assertions.assertEquals(SQLQuerySymbolOrigin.DbObjectFilterMode.CALL, origin.getFilterMode(), sql);
        }
    }

    @NotNull
    private static SQLQueryObjectDataModel callTarget(@NotNull String sql) {
        SQLDialect dialect = BasicSQLDialect.INSTANCE;
        SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dialect, DBWorkbench.getPlatform().getPreferenceStore());
        SQLQueryModel model = SQLQueryModelRecognizer.recognizeQuery(
            new SQLQueryRecognitionContext(new VoidProgressMonitor(), null, false, false, syntaxManager, dialect), sql);
        Assertions.assertNotNull(model, sql);
        SQLQueryCallModel call = Assertions.assertInstanceOf(SQLQueryCallModel.class, model.getQueryModel(), sql);
        Assertions.assertNotNull(call.getObject(), sql);
        return call.getObject();
    }

    /**
     * A connection that fails like the dialect's unquoting did when an object lookup gets a null name part.
     */
    @NotNull
    private static SQLQueryRowsSourceContext rowsContext(@NotNull List<List<?>> lookups) {
        SQLQueryConnectionContext connection = Mockito.mock(SQLQueryConnectionContext.class, invocation -> {
            String method = invocation.getMethod().getName();
            if (method.equals("findRealObjects") || method.equals("findRealObjectsImpl")) {
                for (Object argument : invocation.getArguments()) {
                    if (argument instanceof List<?> name) {
                        lookups.add(name);
                        if (name.contains(null)) {
                            throw new NullPointerException("null name part");
                        }
                    }
                }
                return List.of();
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        // Inline mock maker: the context's reset methods are final and must not run on the empty mock
        SQLQueryRowsSourceContext context = Mockito.mock(SQLQueryRowsSourceContext.class, Mockito.withSettings().mockMaker("mock-maker-inline"));
        Mockito.when(context.getConnectionInfo()).thenReturn(connection);
        return context;
    }

    @NotNull
    private static SQLQueryRecognitionContext realMetadata() {
        SQLQueryRecognitionContext statistics = Mockito.mock(SQLQueryRecognitionContext.class);
        Mockito.when(statistics.useRealMetadata()).thenReturn(true);
        Mockito.when(statistics.getMonitor()).thenReturn(new VoidProgressMonitor());
        return statistics;
    }
}
