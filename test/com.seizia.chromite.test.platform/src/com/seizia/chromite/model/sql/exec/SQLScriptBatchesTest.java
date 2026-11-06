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
package com.seizia.chromite.model.sql.exec;

import org.eclipse.jface.text.Document;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCDataSource;
import com.seizia.chromite.model.impl.jdbc.JDBCSQLDialect;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.model.sql.parser.SQLParserContext;
import com.seizia.chromite.model.sql.parser.SQLRuleManager;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.List;

/**
 * Batch grouping of {@link SQLScriptBatches} (F5 of the Swing SQL editor).
 *
 * 설계: SQL Server의 {@code GO} 배치 실행은 SQL Server 없이 실행해 볼 수 없어서, 실제 SQL Server 방언으로 나눈 결과를
 * 배치로 묶는 부분을 단위 테스트로 확인한다. 데이터 소스는 SQLScriptParserTest와 같은 방식으로 흉내 낸다.
 *
 * 변경 이력:
 * - 2026-11-06 C단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — C단계")
 */
public class SQLScriptBatchesTest extends ChromiteUnitTest {

    @Mock
    private JDBCDataSource dataSource;
    @Mock
    private DBPDataSourceContainer dataSourceContainer;
    @Mock
    private JDBCSession session;
    @Mock
    private JDBCDatabaseMetaData databaseMetaData;
    @Mock
    private DBPDriver driver;

    @BeforeEach
    public void init() {
        DBPConnectionConfiguration connectionConfiguration = new DBPConnectionConfiguration();
        DBPPreferenceStore preferenceStore = DBWorkbench.getPlatform().getPreferenceStore();
        Mockito.lenient().when(dataSource.getContainer()).thenReturn(dataSourceContainer);
        Mockito.lenient().when(dataSourceContainer.getConnectionConfiguration()).thenReturn(connectionConfiguration);
        Mockito.lenient().when(dataSourceContainer.getActualConnectionConfiguration()).thenReturn(connectionConfiguration);
        Mockito.lenient().when(dataSourceContainer.getPreferenceStore()).thenReturn(preferenceStore);
        Mockito.lenient().when(dataSourceContainer.getDriver()).thenReturn(driver);
        Mockito.lenient().when(driver.getSampleURL()).thenReturn("jdbc:sqlserver://localhost;user=MyUserName;password=*****;");
    }

    @Test
    public void mergesStatementsBetweenBatchDelimiters() throws DBException {
        String script = "DECLARE @x INT = 1;\nSELECT @x AS a;\nGO\nSELECT 2;\nSELECT 3\nGO 2\nSELECT 4";
        List<SQLScriptElement> elements = extract("sqlserver", script);

        Assertions.assertEquals(4, elements.size());
        // The dialect parses DECLARE as a block header, so the parser itself may already return this batch as one query
        // (with its trailing delimiter); either way the batch is the whole text before the first GO
        Assertions.assertTrue(elements.get(0).getText().startsWith("DECLARE @x INT = 1;\nSELECT @x AS a"), elements.get(0).getText());
        Assertions.assertEquals(0, elements.get(0).getOffset());
        // Two statements merged into one query: the original text from the first statement to the end of the last one
        Assertions.assertEquals("SELECT 2;\nSELECT 3", elements.get(1).getText().strip());
        Assertions.assertEquals(script.indexOf("SELECT 2"), elements.get(1).getOffset());
        // GO 2 repeats the batch
        Assertions.assertSame(elements.get(1), elements.get(2));
        Assertions.assertEquals("SELECT 4", elements.get(3).getText().trim());
        Assertions.assertTrue(elements.stream().allMatch(SQLQuery.class::isInstance));
    }

    @Test
    public void skipsEmptyBatches() throws DBException {
        String script = "GO\nSELECT 1\nGO\nGO\nSELECT 2;\nGO";
        List<SQLScriptElement> elements = extract("sqlserver", script);

        Assertions.assertEquals(List.of("SELECT 1", "SELECT 2"), elements.stream().map(e -> e.getText().trim()).toList());
    }

    @Test
    public void keepsStatementsOfDialectsWithoutBatchDelimiters() throws DBException {
        String script = "SELECT 1;\nSELECT 2;";
        List<SQLScriptElement> elements = extract("postgresql", script);

        Assertions.assertEquals(List.of("SELECT 1", "SELECT 2"), elements.stream().map(e -> e.getText().trim()).toList());
    }

    private List<SQLScriptElement> extract(String dialectName, String script) throws DBException {
        SQLDialect dialect = DBWorkbench.getPlatform().getSQLDialectRegistry().getDialect(dialectName).createInstance();
        ((JDBCSQLDialect) dialect).initDriverSettings(session, dataSource, databaseMetaData);
        Mockito.lenient().when(dataSource.getSQLDialect()).thenReturn(dialect);

        SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dialect, dataSourceContainer.getPreferenceStore());
        SQLRuleManager ruleManager = new SQLRuleManager(syntaxManager);
        ruleManager.loadRules(dataSource, false);
        SQLParserContext context = new SQLParserContext(dataSource, syntaxManager, ruleManager, new Document(script));
        return SQLScriptBatches.extractQueries(context, 0, script.length());
    }
}
