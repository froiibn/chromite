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
package com.seizia.chromite.ext.mysql.tasks;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

class MySQLScriptExecuteSettingsTest extends ChromiteUnitTest {

    @ParameterizedTest
    @CsvSource({"false, true", "true, false", "false, false", "true, true"})
    void loadSettingsPreservesSelectedOperation(boolean isImport, boolean savedImport) throws DBException {
        DBPPreferenceStore preferenceStore = Mockito.mock(DBPPreferenceStore.class);
        Mockito.when(preferenceStore.getString("MySQL.script..import")).thenReturn(Boolean.toString(savedImport));
        Mockito.when(preferenceStore.getString("MySQL.script.logLevel")).thenReturn("Verbose");
        Mockito.when(preferenceStore.getString("inputFile")).thenReturn("script.sql");
        MySQLScriptExecuteSettings settings = new MySQLScriptExecuteSettings();
        settings.setImport(isImport);

        settings.loadSettings(Mockito.mock(DBRRunnableContext.class), preferenceStore);

        Assertions.assertEquals(isImport, settings.isImport());
        Assertions.assertEquals(MySQLScriptExecuteSettings.LogLevel.Verbose, settings.getLogLevel());
        Assertions.assertEquals("script.sql", settings.getInputFile());
    }
}
