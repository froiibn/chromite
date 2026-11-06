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
package com.seizia.chromite.ui.app.standalone;


import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.cli.ApplicationCommandLine;
import com.seizia.chromite.model.cli.CLIContextImpl;
import com.seizia.chromite.model.cli.CLIRunMeta;
import com.seizia.chromite.model.cli.registry.CLICommandDescriptor;
import com.seizia.chromite.ui.app.standalone.cli.ChromiteMixin;
import com.seizia.chromite.ui.app.standalone.rpc.IInstanceController;
import picocli.CommandLine;

import java.util.List;

/**
 * Command line processing.
 * Note:
 * there are two modes of command line processing:
 * 1. On DBeaver start. It tries to find already running DBeaver instance (thru REST API) and make it execute passed commands
 *    If DBeaver will execute at least one command using remote invocation then application won't start.
 *    Otherwise it will start normally (and then will try to process commands in UI)
 * 2. After DBeaver UI start. It will execute commands directly
 */
public class ChromiteCommandLine extends ApplicationCommandLine<IInstanceController> {
    private static final Log log = Log.getLog(ChromiteCommandLine.class);

    private static ChromiteCommandLine INSTANCE = null;

    private ChromiteCommandLine() {
        super();
    }

    @Override
    protected ChromiteTopLevelCommand createTopLevelCommand(
        @Nullable IInstanceController applicationInstanceController,
        @NotNull CLIContextImpl context,
        @NotNull CLIRunMeta runMeta
    ) {
        return new ChromiteTopLevelCommand(applicationInstanceController, context, runMeta);
    }

    public synchronized static ChromiteCommandLine getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ChromiteCommandLine();
        }
        return INSTANCE;
    }

    @Override
    protected void preprocessCommandLineParameter(
        @NotNull CLICommandDescriptor descriptor,
        boolean uiActivated
    ) {
        super.preprocessCommandLineParameter(descriptor, uiActivated);
        if (!uiActivated && descriptor.isExclusiveMode()) {
            if (ChromiteApplication.instance != null) {
                ChromiteApplication.instance.setExclusiveMode(true);
            }
        }
    }

    @NotNull
    @Override
    protected CommandLine initCommandLine(
        @Nullable IInstanceController applicationInstanceController,
        @NotNull CLIContextImpl context,
        @NotNull CLIRunMeta runMeta,
        @NotNull List<CLICommandDescriptor> commandsToExecute
    ) {
        CommandLine cmd = super.initCommandLine(applicationInstanceController, context, runMeta, commandsToExecute);
        cmd.addMixin("chromite", new ChromiteMixin());
        return cmd;
    }
}
