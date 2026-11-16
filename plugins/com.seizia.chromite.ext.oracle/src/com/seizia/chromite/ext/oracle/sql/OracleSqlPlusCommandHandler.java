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
package com.seizia.chromite.ext.oracle.sql;

import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.oracle.internal.OracleMessages;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleExecutionContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.output.DBCServerOutputReader;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLControlCommand;
import com.seizia.chromite.model.sql.SQLControlCommandHandler;
import com.seizia.chromite.model.sql.SQLControlResult;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLScriptContext;

import java.util.Locale;

/**
 * Runs a SQL*Plus command line of an Oracle script on the client ({@link OracleSqlPlusRule} found it), instead of sending
 * it to the server, which rejects it (ORA-00922, ORA-00900).
 *
 * 설계:
 * <ul>
 *     <li>{@code SET SERVEROUTPUT}: 이미 있는 서버 출력 경로({@link OracleDataSource.OracleOutputReader})로 이 쿼리 창이 쓰는 세션만 켜고 끈다.
 *     연결 설정("DBMS_OUTPUT 읽기")은 바꾸지 않는다. 세션 값이 설정보다 앞서므로 문장 전 맞추기({@code syncServerOutput})가 되돌리지 않고,
 *     문장 뒤 읽기도 그 세션 값으로 판단한다. SQL*Plus처럼 {@code SIZE}를 받고, {@code FORMAT}은 표시 방식이라 무시한다.</li>
 *     <li>{@code SET DEFINE OFF}({@code SET SCAN OFF}): SQL*Plus의 {@code &} 치환을 끄는 명령이다. Chromite에서 같은 일은 매개변수
 *     처리이므로, 매개변수 대화상자의 "무시"와 같이 이 실행의 나머지 문장에서 매개변수를 묻지 않는다({@code SQLScriptContext.setIgnoreParameters}).
 *     {@code ON}은 다시 묻는다. 다른 치환 문자({@code SET DEFINE ^})는 무시하고 알린다.</li>
 *     <li>나머지 {@code SET}(ECHO, FEEDBACK, TIMING, LINESIZE, PAGESIZE, VERIFY, HEADING …)과 {@code SPOOL}은 SQL*Plus 화면·파일 설정이라
 *     Chromite에는 해당하는 것이 없다. 무시하고 무시했다고 Messages에 쓴다.</li>
 *     <li>{@code PROMPT}는 글자를 Messages에 쓴다(upstream {@code @echo}와 같은 출력).</li>
 *     <li>{@code EXEC call}은 SQL*Plus가 하는 그대로 {@code BEGIN call; END;}로 바꿔 실행한다({@code SQLControlResult.transform}, upstream
 *     {@code @include}가 쓰는 길). 바꾼 문장은 평범한 문장처럼 실행되어 DBMS_OUTPUT·오류·트랜잭션 처리가 같다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: Oracle 쿼리 창의 SQL*Plus 명령 (앱 변경 #46, docs/chromite/oracle-sqlplus-commands.md)
 */
public class OracleSqlPlusCommandHandler implements SQLControlCommandHandler {

    @NotNull
    @Override
    public SQLControlResult handleCommand(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLControlCommand command,
        @NotNull SQLScriptContext scriptContext
    ) throws DBException {
        String line = command.getText();
        OracleSqlPlus.Command sqlPlus = OracleSqlPlus.parse(line);
        if (sqlPlus == null) {
            throw new DBException("Not a SQL*Plus command: " + line);
        }
        switch (sqlPlus.kind()) {
            case SERVER_OUTPUT -> setServerOutput(monitor, scriptContext, sqlPlus.argument());
            case DEFINE -> setDefine(scriptContext, line, sqlPlus.argument());
            case DISPLAY_SETTING, SPOOL -> print(scriptContext, NLS.bind(OracleMessages.sqlplus_ignored, line));
            case PROMPT -> print(scriptContext, sqlPlus.argument());
            case EXECUTE -> {
                // SQL*Plus runs EXECUTE as an anonymous block
                String block = "BEGIN " + sqlPlus.argument() + "; END;"; //$NON-NLS-1$ //$NON-NLS-2$
                return SQLControlResult.transform(new SQLQuery(command.getDataSource(), block, command.getOffset(), command.getLength()));
            }
        }
        return SQLControlResult.success();
    }

    private static void setServerOutput(
        @NotNull DBRProgressMonitor monitor,
        @NotNull SQLScriptContext scriptContext,
        @NotNull String argument
    ) throws DBException {
        String[] words = argument.isEmpty() ? new String[0] : argument.split("\\s+");
        if (words.length == 0 || !(words[0].equalsIgnoreCase("ON") || words[0].equalsIgnoreCase("OFF"))) {
            throw new DBException("SET SERVEROUTPUT needs ON or OFF [SIZE {n|UNLIMITED}] [FORMAT {WRAPPED|WORD_WRAPPED|TRUNCATED}]");
        }
        boolean enable = words[0].equalsIgnoreCase("ON");
        Integer size = null;
        boolean format = false;
        for (int i = 1; i < words.length; i++) {
            String word = words[i];
            if (OracleSqlPlus.isAbbreviation(word, "SIZE", 3) && i + 1 < words.length) { //$NON-NLS-1$
                size = parseSize(words[++i]);
            } else if (OracleSqlPlus.isAbbreviation(word, "FORMAT", 3) && i + 1 < words.length) { //$NON-NLS-1$
                // WRAPPED / WORD_WRAPPED / TRUNCATED: how SQL*Plus wraps the lines on screen
                format = true;
                i++;
            } else {
                throw new DBException("Unknown SET SERVEROUTPUT option: " + word);
            }
        }
        DBCExecutionContext context = scriptContext.getExecutionContext();
        DBCServerOutputReader reader = context == null ? null : DBUtils.getAdapter(DBCServerOutputReader.class, context.getDataSource());
        if (!(reader instanceof OracleDataSource.OracleOutputReader oracleReader) || !(context instanceof OracleExecutionContext oracleContext)) {
            throw new DBException("SET SERVEROUTPUT works only in an Oracle connection");
        }
        oracleReader.setSessionServerOutput(monitor, oracleContext, enable, size);
        String message = enable
            ? NLS.bind(OracleMessages.sqlplus_server_output_on, size == null ? OracleMessages.sqlplus_server_output_unlimited : size)
            : OracleMessages.sqlplus_server_output_off;
        print(scriptContext, message);
        if (format) {
            print(scriptContext, OracleMessages.sqlplus_server_output_format_ignored);
        }
    }

    /**
     * The buffer size of {@code SIZE n|UNLIMITED} (null: UNLIMITED).
     */
    @Nullable
    private static Integer parseSize(@NotNull String value) throws DBException {
        if (OracleSqlPlus.isAbbreviation(value, "UNLIMITED", 3)) { //$NON-NLS-1$
            return null;
        }
        try {
            int size = Integer.parseInt(value);
            if (size > 0) {
                return size;
            }
        } catch (NumberFormatException e) {
            // Reported below
        }
        throw new DBException("Invalid SET SERVEROUTPUT SIZE: " + value);
    }

    private static void setDefine(@NotNull SQLScriptContext scriptContext, @NotNull String line, @NotNull String argument) {
        String value = argument.toUpperCase(Locale.ENGLISH);
        if (value.equals("OFF")) { //$NON-NLS-1$
            scriptContext.setIgnoreParameters(true);
            print(scriptContext, NLS.bind(OracleMessages.sqlplus_define_off, line));
        } else if (value.equals("ON")) { //$NON-NLS-1$
            scriptContext.setIgnoreParameters(false);
            print(scriptContext, NLS.bind(OracleMessages.sqlplus_define_on, line));
        } else {
            // Another substitution character: Chromite's parameter prefixes are set in the preferences
            print(scriptContext, NLS.bind(OracleMessages.sqlplus_ignored, line));
        }
    }

    private static void print(@NotNull SQLScriptContext scriptContext, @NotNull String text) {
        scriptContext.getOutputWriter().println(null, text);
    }
}
