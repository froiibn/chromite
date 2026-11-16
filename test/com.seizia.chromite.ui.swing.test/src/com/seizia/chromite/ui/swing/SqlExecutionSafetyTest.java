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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.registry.confirmation.ConfirmationConstants;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Regression tests of the query window's safety check before execution ({@link SqlExecutionSafety}): which statements
 * count as DROP, and how the confirmation settings shared with the SWT editor are read.
 *
 * 설계: DROP 판단은 쿼리 창이 쓰는 private 메서드({@code isDrop})를 그대로 부른다(JSQLParser 다음에 LSM 파서). 확인 설정의 값은 SWT 확인
 * 대화상자의 뜻을 따른다: {@code always}·{@code never}는 묻지 않음, 그 밖의 값은 물음. 이 번들은 자기 앱 정의를 가지고 있어 시험 런타임에서
 * 헤드리스 시험 앱이 시작되지 않으므로, 설정 저장소는 메모리 저장소로 주고 플랫폼은 시험 스레드에서만 바꾼다. 대화상자를 띄우는
 * 경로(묻기, 실행 제한 알림)는 화면 확인 목록(docs/chromite/regression-tests.md)에서 본다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "SQL 실행 안전")
 */
public class SqlExecutionSafetyTest {

    @Test
    public void dropsThatJsqlParserCannotReadAreFoundByTheLsmParser() throws Exception {
        DBPDataSource dataSource = Mockito.mock(DBPDataSource.class);
        Mockito.when(dataSource.getSQLDialect()).thenReturn(BasicSQLDialect.INSTANCE);
        SQLSyntaxManager syntaxManager = new SQLSyntaxManager(preferences(new HashMap<>()));
        syntaxManager.init(BasicSQLDialect.INSTANCE, preferences(new HashMap<>()));
        Method isDrop = SqlExecutionSafety.class.getDeclaredMethod("isDrop", DBPDataSource.class, SQLSyntaxManager.class, SQLQuery.class);
        isDrop.setAccessible(true);

        for (String sql : List.of("DROP DATABASE shop", "DROP USER 'app'@'%'", "DROP TABLESPACE ts", "DROP PROCEDURE p", "DROP TRIGGER trg",
            "DROP TABLE IF EXISTS a, b", "DROP TABLE a")) {
            Assertions.assertTrue((boolean) isDrop.invoke(null, dataSource, syntaxManager, new SQLQuery(null, sql)), sql);
        }
        for (String sql : List.of("ALTER TABLE a DROP COLUMN b", "SELECT 'DROP TABLE a'", "DELETE FROM a WHERE id = 1")) {
            Assertions.assertFalse((boolean) isDrop.invoke(null, dataSource, syntaxManager, new SQLQuery(null, sql)), sql);
        }
    }

    @Test
    public void confirmationSettingsReadLikeTheSwtToggle() {
        Map<String, String> values = new HashMap<>();
        DBPPreferenceStore store = preferences(values);
        DBPPlatform platform = Mockito.mock(DBPPlatform.class);
        Mockito.when(platform.getPreferenceStore()).thenReturn(store);
        try (MockedStatic<DBWorkbench> workbench = Mockito.mockStatic(DBWorkbench.class, Mockito.withSettings().mockMaker("mock-maker-inline"))) {
            workbench.when(DBWorkbench::getPlatform).thenReturn(platform);
            for (String asking : new String[] {"", "prompt", "anything else"}) {
                values.put(ConfirmationConstants.CONFIRM_DANGER_SQL_KEY, asking);
                Assertions.assertTrue(SqlExecutionSafety.isConfirmDangerousSql(), asking);
            }
            for (String quiet : new String[] {"always", "never"}) {
                values.put(ConfirmationConstants.CONFIRM_DANGER_SQL_KEY, quiet);
                Assertions.assertFalse(SqlExecutionSafety.isConfirmDangerousSql(), quiet);
            }
            SqlExecutionSafety.setConfirmDropSql(false);
            Assertions.assertEquals("always", values.get(ConfirmationConstants.CONFIRM_DROP_SQL_KEY));
            Assertions.assertFalse(SqlExecutionSafety.isConfirmDropSql());
            SqlExecutionSafety.setConfirmDropSql(true);
            Assertions.assertEquals("prompt", values.get(ConfirmationConstants.CONFIRM_DROP_SQL_KEY));
            Assertions.assertTrue(SqlExecutionSafety.isConfirmDropSql());
        }
    }

    @Test
    public void findingsNameTheLineOfTheStatement() throws Exception {
        Method lineOf = SqlExecutionSafety.class.getDeclaredMethod("lineOf", String.class, int.class);
        lineOf.setAccessible(true);
        String text = "SELECT 1;\n\nDROP TABLE a;\nDELETE FROM b;";
        Assertions.assertEquals(1, lineOf.invoke(null, text, 0));
        Assertions.assertEquals(3, lineOf.invoke(null, text, text.indexOf("DROP")));
        Assertions.assertEquals(4, lineOf.invoke(null, text, text.indexOf("DELETE")));
        Assertions.assertEquals(4, lineOf.invoke(null, text, text.length() + 10));
        Assertions.assertEquals(1, lineOf.invoke(null, text, -5));
    }

    /**
     * A preference store in memory: strings as set, booleans parsed, nothing saved.
     */
    @NotNull
    private static DBPPreferenceStore preferences(@NotNull Map<String, String> values) {
        DBPPreferenceStore store = Mockito.mock(DBPPreferenceStore.class);
        Mockito.when(store.getString(Mockito.anyString())).thenAnswer(invocation -> values.getOrDefault((String) invocation.getArgument(0), ""));
        Mockito.when(store.getBoolean(Mockito.anyString())).thenAnswer(invocation -> Boolean.parseBoolean(values.get((String) invocation.getArgument(0))));
        Mockito.doAnswer(invocation -> values.put(invocation.getArgument(0), invocation.getArgument(1)))
            .when(store).setValue(Mockito.anyString(), Mockito.anyString());
        return store;
    }
}
