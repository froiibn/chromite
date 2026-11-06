/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ui.editors.erd.navigator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.app.DBPPlatformDesktop;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.app.DBPResourceHandler;
import com.seizia.chromite.model.erd.DiagramObjectCollector;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.DBRRunnableWithProgress;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.editors.erd.internal.ERDUIMessages;
import com.seizia.chromite.ui.editors.erd.model.DiagramCollectSettingsDefault;
import com.seizia.chromite.ui.editors.erd.model.ERDContentProviderDecorated;
import com.seizia.chromite.ui.editors.erd.model.ERDDecoratorDefault;
import com.seizia.chromite.ui.editors.erd.model.EntityDiagram;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class DiagramCreateWizard extends Wizard implements INewWizard {

    @Nullable
    private IFolder folder;
    private EntityDiagram diagram = new EntityDiagram(null, "", new ERDContentProviderDecorated(), new ERDDecoratorDefault());
    private DiagramCreateWizardPage pageContent;
	private String errorMessage;
    private IStructuredSelection entitySelection;
    @Nullable
    private DBPProject project;
    
    public DiagramCreateWizard() {
	}

	@Override
    public void init(IWorkbench workbench, IStructuredSelection selection) {
        setWindowTitle(ERDUIMessages.wizard_diagram_create_title);
        setNeedsProgressMonitor(true);
        IFolder diagramFolder = null;
        if (selection != null) {
            Object element = selection.getFirstElement();
            if (element != null) {
                diagramFolder = Platform.getAdapterManager().getAdapter(element, IFolder.class);
			}
        }
        if (diagramFolder == null) {
            DBPProject activeProject = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        	if (activeProject == null) {
				errorMessage = "Can't create diagram without active project";
			} else {
	        	try {
					diagramFolder = ERDResourceHandler.getDiagramsFolder(activeProject, true);
				} catch (CoreException e) {
					errorMessage = e.getMessage();
				}
			}

			// Check for entity selection
            if (selection != null && !selection.isEmpty()) {
        	    if (Platform.getAdapterManager().getAdapter(selection.getFirstElement(), DBSEntity.class) != null) {
        	        entitySelection = selection;
                }
            }
        }
        if (diagramFolder != null) {
            this.folder = diagramFolder;
            this.project = DBPPlatformDesktop.getInstance().getWorkspace().getProject(diagramFolder.getProject());
        }
    }

    @Override
    public void addPages() {
        super.addPages();
        pageContent = new DiagramCreateWizardPage(diagram, entitySelection, project);
        addPage(pageContent);
        if (getContainer() != null) {
            //WizardDialog call
            pageContent.setErrorMessage(errorMessage);
		}
    }
    
    @Override
    public void setContainer(IWizardContainer wizardContainer) {
    	super.setContainer(wizardContainer);
    	if (pageContent != null) {
    		//New Wizard call
            pageContent.setErrorMessage(errorMessage);
		}
    }
    
	@Override
	public boolean performFinish() {
        try {
            Collection<DBNNode> initialContent = pageContent.getInitialContent();
            List<DBSObject> rootObjects = new ArrayList<>();
            for (DBNNode node : initialContent) {
                if (node instanceof DBNDatabaseNode) {
                    rootObjects.add(((DBNDatabaseNode) node).getObject());
                }
            }
            DiagramCreator creator = new DiagramCreator(rootObjects);
            UIUtils.run(getContainer(), true, true, creator);

            DBPResourceHandler handler = DBPPlatformDesktop.getInstance().getWorkspace().getResourceHandler(creator.diagramFile);
            if (handler != null) {
                handler.openResource(creator.diagramFile);
            }

        }
        catch (InterruptedException ex) {
            return false;
        }
        catch (Throwable ex) {
            if (ex instanceof InvocationTargetException ite) {
                ex = ite.getTargetException();
            }
            DBWorkbench.getPlatformUI().showError(
                    "Create error",
                "Cannot create diagram",
                ex);
            return false;
        }
        return true;
	}

    private class DiagramCreator implements DBRRunnableWithProgress {
        Collection<DBSObject> roots;
        IFile diagramFile;

        private DiagramCreator(Collection<DBSObject> roots)
        {
            this.roots = roots;
        }

        @Override
        public void run(DBRProgressMonitor monitor) throws InvocationTargetException {
            try {
                Collection<DBSEntity> tables = DiagramObjectCollector.collectTables(
                    monitor,
                    roots,
                    new DiagramCollectSettingsDefault(),
                    true);
                diagram.fillEntities(monitor, tables, null);

                diagramFile = ERDResourceHandler.createDiagram(diagram, diagram.getName(), folder, monitor);
            } catch (Exception e) {
                throw new InvocationTargetException(e);
            }
        }
    }
}
