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
import com.seizia.chromite.debug.DBGPrivilegeException;
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
 * ({@link ObjectExplorerPanel.QueryWindows#openToolDocument}, 열쇠 = 연결 + 원본 식별자 + 루틴 이름·입력 형식: Oracle 패키지의 루틴들은
 * 원본(패키지 본문)이 같으므로 루틴까지 넣는다).
 * 디버그 정보로 다시 컴파일해야 하는 단위(Oracle)가 있으면 열기 전에 묻고, 동의하면 컴파일한 뒤 연다. 거절하면 열지 않는다: 디버그 정보가
 * 없으면 줄에서 멈추거나 변수를 볼 수 없다. 권한이 없으면 DBA가 실행할 GRANT 문을 사용자 언어로 보인다({@link #describeError}).
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/procedure-debugger.md)
 * - 2026-11-07 Oracle 디버거: 디버그 컴파일 질문, 권한 안내, 탭 열쇠에 루틴 이름·입력 형식
 * - 2026-11-08 디버그 컴파일 확인은 컴파일 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 */
final class DebugCommands {

    private record OpenInfo(
        @NotNull Object sourceId,
        @NotNull String source,
        @NotNull List<DBSProcedureParameter> parameters,
        @NotNull List<String> unitsToCompile
    ) {
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
                    return new OpenInfo(controller.getSourceIdentifier(), source, inputs, controller.getUnitsToCompile(monitor));
                } finally {
                    controller.dispose();
                }
            });
            if (!info.unitsToCompile().isEmpty()) {
                if (!SwingPlatformUI.confirmChange(parent,
                    NLS.bind(DebugMessages.debug_compile_question, String.join("\n", info.unitsToCompile())), //$NON-NLS-1$
                    DebugMessages.debug_compile_title, DebugMessages.debug_compile_button, JOptionPane.QUESTION_MESSAGE)) {
                    return;
                }
                ProgressDialog.runTask(DebugMessages.debug_progress_compile, monitor -> {
                    DBGController controller = factory.createController(routine, Map.of());
                    try {
                        controller.compileForDebugging(monitor);
                    } finally {
                        controller.dispose();
                    }
                    return null;
                });
            }
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            ErrorDialog.show(parent, NLS.bind(DebugMessages.debug_error_open, routineName), describeError(e), e);
            return;
        }
        StringBuilder key = new StringBuilder("debugger:").append(routine.getDataSource().getContainer().getId()) //$NON-NLS-1$
            .append(':').append(info.sourceId()).append(':').append(routineName);
        for (DBSProcedureParameter parameter : info.parameters()) {
            key.append(':').append(parameter.getParameterType() == null ? "" : parameter.getParameterType().getFullTypeName()); //$NON-NLS-1$
        }
        windows.openToolDocument(key.toString(), () -> new ProcedureDebuggerPanel(routine, factory, info.sourceId(), info.source(), info.parameters()));
    }

    /**
     * The message of a debugger error; a missing privilege becomes a notice with the statements a DBA must run.
     */
    @NotNull
    static String describeError(@NotNull DBException error) {
        if (error instanceof DBGPrivilegeException privilege) {
            return NLS.bind(DebugMessages.debug_error_privileges, privilege.getUserName(), String.join("\n", privilege.getStatements())); //$NON-NLS-1$
        }
        return error.getMessage();
    }
}
