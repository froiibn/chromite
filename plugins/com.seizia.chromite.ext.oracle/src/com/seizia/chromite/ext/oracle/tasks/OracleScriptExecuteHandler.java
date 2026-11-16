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
package com.seizia.chromite.ext.oracle.tasks;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.oracle.model.OracleConstants;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.dict.OracleConnectionType;
import com.seizia.chromite.ext.oracle.oci.OCIUtils;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.fs.DBFUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableContext;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.task.DBTTask;
import com.seizia.chromite.registry.task.TaskPreferenceStore;
import com.seizia.chromite.tasks.nativetool.AbstractNativeToolHandler;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a script file with SQL*Plus for the oracleScriptExecute task.
 *
 * 변경 이력:
 * - 2026-11-08 Chromite: 암호를 명령줄에 넣지 않는다. 전에는 {@code user/"password"@url}을 SQL*Plus 인수로 넘겨서 이 컴퓨터의
 *   다른 사용자가 프로세스 목록에서 암호를 볼 수 있었고, 실행 로그의 명령줄 가리기({@code NativeToolUtils.isSecureString}, 암호로 끝나는
 *   인수만 가림)도 이 인수를 가리지 못했다. 이제 {@code sqlplus /nolog}로 시작하고 로그온 명령({@link #getLogonScript})을 표준 입력으로
 *   스크립트 앞에 보낸다. 로그온이 실패하면 스크립트를 실행하지 않고 실패로 끝나도록 {@code WHENEVER SQLERROR EXIT FAILURE}로 감싸고,
 *   스키마를 골랐으면 그 스키마를 현재 스키마로 정한다 (docs/chromite/swing-database-tasks.md "네이티브 클라이언트로 스크립트 실행")
 */
public class OracleScriptExecuteHandler extends AbstractNativeToolHandler<OracleScriptExecuteSettings, DBSObject, OracleDataSource> {

    @Override
    public Collection<OracleDataSource> getRunInfo(OracleScriptExecuteSettings settings) {
        return Collections.singletonList((OracleDataSource) settings.getDataSourceContainer().getDataSource());
    }

    @Override
    protected OracleScriptExecuteSettings createTaskSettings(DBRRunnableContext context, DBTTask task) throws DBException {
        OracleScriptExecuteSettings settings = new OracleScriptExecuteSettings(task.getProject());
        settings.loadSettings(context, new TaskPreferenceStore(task));

        return settings;
    }

    @Override
    protected boolean needsModelRefresh() {
        return false;
    }

    @Override
    public void fillProcessParameters(OracleScriptExecuteSettings settings, OracleDataSource arg, List<String> cmd) throws IOException {
        String sqlPlusExec = RuntimeUtils.getNativeBinaryName("sqlplus"); //$NON-NLS-1$
        File sqlPlusBinary = new File(settings.getClientHome().getPath(), "bin/" + sqlPlusExec); //$NON-NLS-1$
        if (!sqlPlusBinary.exists()) {
            sqlPlusBinary = new File(settings.getClientHome().getPath(), sqlPlusExec);
        }
        if (!sqlPlusBinary.exists()) {
            throw new IOException("SQL*Plus binary not found in '" + settings.getClientHome().getPath().getAbsolutePath());
        }
        String dumpPath = sqlPlusBinary.getAbsolutePath();
        cmd.add(dumpPath);
    }

    @Override
    protected List<String> getCommandLine(OracleScriptExecuteSettings settings, OracleDataSource arg) throws IOException {
        List<String> cmd = new ArrayList<>();
        fillProcessParameters(settings, arg, cmd);
        // The logon goes through the standard input (startProcessHandler): arguments are visible in the process list
        cmd.add("/nolog"); //$NON-NLS-1$
        return cmd;
    }

    /**
     * SQL*Plus commands sent through the standard input before the script: log on (a failed logon exits with a failure
     * code instead of running the script without a session), then the default error handling again, and the chosen schema
     * as the current schema.
     *
     * @param maskPassword replace the password with asterisks (to show the commands)
     */
    public String getLogonScript(OracleScriptExecuteSettings settings, boolean maskPassword) throws IOException {
        DBPConnectionConfiguration conInfo = settings.getDataSourceContainer().getActualConnectionConfiguration();
        String url;
        String walletLocation = conInfo.getAuthProperty("oracle.wallet.dir");
        boolean hasWallet = walletLocation != null && !walletLocation.isEmpty();

        if (OracleConstants.ConnectionType.TNS.equals(
            conInfo.getProviderProperty(OracleConstants.PROP_CONNECTION_TYPE)
        )) {
            String name = conInfo.getServerName() != null
                ? conInfo.getServerName()
                : conInfo.getDatabaseName();
            url = name;
            String description = OCIUtils.readTnsNames(Paths.get(conInfo.getProviderProperty(OracleConstants.PROP_TNS_PATH)).toFile(), false).get(name);
            if (hasWallet) {
                url = patchWalletDirectory(description, walletLocation);
            }
        } else {
            boolean isSID = OracleConnectionType.SID.name()
                .equals(conInfo.getProviderProperty(OracleConstants.PROP_SID_SERVICE));

            String protocol = hasWallet ? "TCPS" : "TCP";
            String port = conInfo.getHostPort();

            if (isSID || hasWallet) {
                // Use full descriptor when SID or wallet is involved
                String serviceName = extractServiceName(conInfo.getUrl());
                String connectData;
                if (serviceName != null && hasWallet) {
                    connectData = "(SERVICE_NAME=" + serviceName + ")";
                } else {
                    connectData = "(SID=" + conInfo.getDatabaseName() + ")";
                }

                url =
                    "(DESCRIPTION=" +
                        "(ADDRESS=(PROTOCOL=" + protocol + ")" +
                        "(HOST=" + conInfo.getHostName() + ")" +
                        "(PORT=" + port + "))" +
                        "(CONNECT_DATA=" + connectData + ")" +
                        (hasWallet
                            ? "(SECURITY=(MY_WALLET_DIRECTORY=" + walletLocation + ")(ssl_server_dn_match=yes))"
                            : "") +
                        ")";
            } else {
                // Existing Easy Connect format
                url =
                    "//" + conInfo.getHostName() +
                        (port != null ? ":" + port : "") +
                        "/" + conInfo.getDatabaseName();
            }
        }
        final String role = conInfo.getProviderProperty(OracleConstants.PROP_INTERNAL_LOGON);
        if (role != null) {
            url += (" AS " + role);
        }
        String password = maskPassword ? "******" : conInfo.getUserPassword(); //$NON-NLS-1$
        StringBuilder script = new StringBuilder();
        script.append("WHENEVER SQLERROR EXIT FAILURE\n"); //$NON-NLS-1$
        script.append("CONNECT ").append(conInfo.getUserName()).append("/\"").append(CommonUtils.notEmpty(password)).append("\"@").append(url).append('\n'); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        script.append("WHENEVER SQLERROR CONTINUE NONE\n"); //$NON-NLS-1$
        if (!settings.getDatabaseObjects().isEmpty() && settings.getDatabaseObjects().get(0) instanceof OracleSchema schema) {
            script.append("ALTER SESSION SET CURRENT_SCHEMA = ").append(DBUtils.getQuotedIdentifier(schema)).append(";\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return script.toString();
    }

    private static String extractServiceName(String descriptor) {
        Pattern pattern = Pattern.compile(
            "\\(SERVICE_NAME\\s*=\\s*([^)]+)\\)",
            Pattern.CASE_INSENSITIVE);

        Matcher matcher = pattern.matcher(descriptor);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }

    @Override
    protected boolean isLogInputStream() {
        return true;
    }

    @Override
    protected void startProcessHandler(
        DBRProgressMonitor monitor,
        DBTTask task,
        OracleScriptExecuteSettings settings,
        OracleDataSource arg,
        ProcessBuilder processBuilder,
        Process process,
        Log log
    ) throws IOException, DBException {
        Path inputFile = DBFUtils.resolvePathFromString(monitor, task.getProject(), settings.getInputFile());
        if (!Files.exists(inputFile)) {
            throw new IOException("File '" + inputFile + "' doesn't exist");
        }
        super.startProcessHandler(monitor, task, settings, arg, processBuilder, process, log);
        // The logon commands first, then the script through the same stream (the job closes it at the end)
        OutputStream input = process.getOutputStream();
        input.write(getLogonScript(settings, false).getBytes(GeneralUtils.getDefaultConsoleEncoding()));
        input.flush();
        new BinaryFileTransformerJob(monitor, task, inputFile, input, log).start();
    }

    public static String patchWalletDirectory(String descriptor, String walletDir) {
        String walletEntry = "(MY_WALLET_DIRECTORY=" + walletDir + ")";

        int securityPos = descriptor.toUpperCase().indexOf("(SECURITY=");
        if (securityPos >= 0) {
            int securityEnd = findMatchingParen(descriptor, securityPos);

            String securityBlock =
                descriptor.substring(securityPos, securityEnd + 1);

            int existingWallet =
                securityBlock.toUpperCase().indexOf("(MY_WALLET_DIRECTORY=");

            if (existingWallet >= 0) {
                int walletEnd =
                    findMatchingParen(securityBlock, existingWallet);

                securityBlock =
                    securityBlock.substring(0, existingWallet)
                        + walletEntry
                        + securityBlock.substring(walletEnd + 1);
            } else {
                securityBlock =
                    securityBlock.substring(0, securityBlock.length() - 1)
                        + walletEntry
                        + ")";
            }

            return descriptor.substring(0, securityPos)
                + securityBlock
                + descriptor.substring(securityEnd + 1);
        }

        int descriptionPos = descriptor.toUpperCase().indexOf("(DESCRIPTION=");
        if (descriptionPos < 0) {
            throw new IllegalArgumentException(
                "Descriptor does not contain DESCRIPTION block");
        }

        int descriptionEnd = findMatchingParen(descriptor, descriptionPos);

        return descriptor.substring(0, descriptionEnd)
            + "(SECURITY=" + walletEntry + ")"
            + descriptor.substring(descriptionEnd);
    }

    private static int findMatchingParen(String text, int openPos) {
        int depth = 0;

        for (int i = openPos; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        throw new IllegalArgumentException(
            "Unbalanced parentheses in descriptor");
    }

    @Override
    public boolean executeProcess(
        DBRProgressMonitor monitor,
        DBTTask task,
        OracleScriptExecuteSettings settings,
        OracleDataSource oracleDataSource,
        Log log
    ) throws IOException, InterruptedException {
        return super.executeProcess(monitor, task, settings, oracleDataSource, log);
    }
}
