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
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPWorkspaceDesktop;
import com.seizia.chromite.model.cli.CLIAbstractSubcommand;
import com.seizia.chromite.model.cli.CLIProcessResult;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "link-project", aliases = {"-link-project", "--link-project"},
    description = "Link an existing external folder into the workspace as a project (contents are not copied)")
public class LinkProjectHandler extends CLIAbstractSubcommand {
    private static final Log log = Log.getLog(LinkProjectHandler.class);

    @CommandLine.Parameters(index = "0", arity = "1", description = "Path to the project folder")
    private String path;

    @CommandLine.Option(names = {"--name", "-name"}, description = "Project name (optional)")
    private String name;

    @Override
    public void run() {
        try {
            Path folder = Path.of(path);
            if (!(DBWorkbench.getPlatform().getWorkspace() instanceof DBPWorkspaceDesktop workspace)) {
                throw new IllegalStateException("Project linking is not supported in this workspace");
            }
            DBPProject project = workspace.linkProject(folder, name, new VoidProgressMonitor());
            context().addResult("Project '" + project.getName() + "' linked from " + folder.toAbsolutePath());
        } catch (Exception e) {
            log.error("Error linking project", e);
            context().addResult("Error linking project: " + e.getMessage());
        }
        context().setPostAction(CLIProcessResult.PostAction.SHUTDOWN);
    }
}
