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

import com.seizia.chromite.debug.DBGController;
import com.seizia.chromite.debug.DBGControllerFactory;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSProcedureParameter;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.ui.swing.internal.DebugMessages;
import com.seizia.chromite.utils.GeneralUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * "Debug…" of a function or procedure: Object Explorer's context menu and the Query menu of a query window opened by
 * Modify (the source editor).
 *
 * 설계: 디버깅할 수 있는지는 루틴의 {@link DBGControllerFactory} 어댑터가 있는지로 정한다(드라이버의 디버그 번들이 등록,
 * PostgreSQL은 {@code ext.postgresql.debug.core}). 그래서 이 클래스와 디버거 창은 DB를 모른다. 열 때 루틴 원본과 입력 매개 변수를
 * 진행 대화상자에서 읽고(언어 확인 포함, 디버깅할 수 없으면 탭을 열지 않고 오류를 보임), 같은 루틴의 디버거 탭이 열려 있으면 그 탭을 보인다
 * ({@link ObjectExplorerPanel.QueryWindows#openToolDocument}, 열쇠 = 연결 + 원본 식별자).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 */
final class DebugCommands {

    private record OpenInfo(@NotNull Object sourceId, @NotNull String source, @NotNull List<DBSProcedureParameter> parameters) {
    }

    private DebugCommands() {
    }

    /**
     * True if a debugger is registered for the object (EDT, no database access).
     */
    static boolean canDebug(@Nullable DBSObject object) {
        return object instanceof DBSProcedure && object.isPersisted() && GeneralUtils.adapt(object, DBGControllerFactory.class) != null;
    }

    /**
     * The "Debug…" action; it debugs the routine the supplier gives (and is enabled by the caller with {@link #canDebug}).
     */
    @NotNull
    static Action createAction(@NotNull Component parent, @NotNull Supplier<DBSObject> routine, @NotNull ObjectExplorerPanel.QueryWindows windows) {
        Action action = new AbstractAction(DebugMessages.debug_menu) {
            @Override
            public void actionPerformed(ActionEvent e) {
                DBSObject object = routine.get();
                if (canDebug(object)) {
                    open(parent, (DBSProcedure) object, windows);
                }
            }
        };
        action.putValue(Action.SHORT_DESCRIPTION, DebugMessages.debug_menu_tip);
        return action;
    }

    /**
     * Opens (or shows) the debugger tab of the routine.
     */
    static void open(@NotNull Component parent, @NotNull DBSProcedure routine, @NotNull ObjectExplorerPanel.QueryWindows windows) {
        DBGControllerFactory factory = GeneralUtils.adapt(routine, DBGControllerFactory.class);
        if (factory == null) {
            return;
        }
        String routineName = DBUtils.getObjectFullName(routine, DBPEvaluationContext.UI);
        OpenInfo info;
        try {
            info = ProgressDialog.runTask(DebugMessages.debug_progress_open, monitor -> {
                DBGController controller = factory.createController(routine, Map.of());
                try {
                    String source = controller.readSource(monitor);
                    List<DBSProcedureParameter> inputs = new ArrayList<>();
                    for (DBSProcedureParameter parameter : CommonUtils.safeCollection(routine.getParameters(monitor))) {
                        if (parameter.getParameterKind().isInput()) {
                            inputs.add(parameter);
                        }
                    }
                    return new OpenInfo(controller.getSourceIdentifier(), source, inputs);
                } finally {
                    controller.dispose();
                }
            });
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            ErrorDialog.show(parent, NLS.bind(DebugMessages.debug_error_open, routineName), e.getMessage(), e);
            return;
        }
        String key = "debugger:" + routine.getDataSource().getContainer().getId() + ":" + info.sourceId(); //$NON-NLS-1$ //$NON-NLS-2$
        windows.openToolDocument(key, () -> new ProcedureDebuggerPanel(routine, factory, info.sourceId(), info.source(), info.parameters()));
    }
}
