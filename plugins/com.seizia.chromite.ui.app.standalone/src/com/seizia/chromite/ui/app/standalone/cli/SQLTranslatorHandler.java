/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ui.app.standalone.cli;


import com.seizia.chromite.Log;
import com.seizia.chromite.model.cli.CLIAbstractSubcommand;
import com.seizia.chromite.model.impl.preferences.SimplePreferenceStore;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLModelPreferences;
import com.seizia.chromite.model.sql.translate.SQLQueryTranslator;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;
import picocli.CommandLine;

import java.io.FileReader;
import java.io.IOException;

@CommandLine.Command(name = "translateSQL", aliases = {"-translateSQL", "-translate-sql-script"},
    description = "Translate SQL script from one dialect to another")
public class SQLTranslatorHandler extends CLIAbstractSubcommand {
    private static final Log log = Log.getLog(SQLTranslatorHandler.class);

    @CommandLine.Parameters(index = "0", description = "Parameter in the form dialect=filePath", arity = "1")
    private String value;

    @Override
    public void run() {
        String[] args = value.split(",");
        if (args.length != 2) {
            throw new IllegalStateException("Input parameter format: dialect,<input-file-path>");
        }
        String dialect = args[0];
        String inputFile = args[1];
        if (CommonUtils.isEmpty(inputFile)) {
            throw new IllegalStateException("Input file not specified");
        }
        DBPPreferenceStore preferenceStore = new SimplePreferenceStore() {
            @Override
            public void save() throws IOException {

            }
        };
        preferenceStore.setValue(SQLModelPreferences.SQL_FORMAT_FORMATTER, "default");

        String script;
        try (FileReader fr = new FileReader(inputFile)) {
            script = IOUtils.readToString(fr);
        } catch (IOException e) {
            throw new RuntimeException("Error opening input file " + inputFile, e);
        }

        SQLDialect srcDialect = new BasicSQLDialect() {

        };
        SQLDialect targetDialect = new BasicSQLDialect() {

        };

        String result = null;
        try {
            result = SQLQueryTranslator.translateScript(srcDialect, targetDialect, preferenceStore, script);
        } catch (Exception e) {
            throw new RuntimeException("Error translating file " + inputFile, e);
        }

        System.out.println(result);
    }
}