/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2017-2018 Andrew Khitrin (ahitrin@gmail.com)
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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

package com.seizia.chromite.debug;

import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * A running debug session. Stepping methods start the step and return; {@link DBGEvent#SUSPEND} tells when the target
 * stopped again. The other methods talk to the server and must not be called while a step runs (they wait for it).
 *
 * 설계: 디버그 세션을 끝내는 일을 둘로 나눴다. {@link #abort}는 대상 실행만 멈추고(Stop) 연결은 둔다. 그 뒤 대상 연결에 커밋하지 않은
 * 트랜잭션이 있으면({@link #getTransactionState}) UI가 사용자에게 커밋/롤백/취소를 물어 {@link #endTransaction}으로 끝내고, 그다음에야
 * {@link #closeSession}이 연결을 닫는다. 사용자 규칙 "트랜잭션 안전"(2026-11-07): 연결을 닫아 열린 트랜잭션을 조용히 롤백하지 않는다.
 * SWT 버전에는 이 구분이 없어 대상 연결을 실행이 끝나자마자 닫았다.
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 구현 없던 resume·suspend 삭제, abort·readMessages·getTargetResult·
 *   getTransactionState·endTransaction 추가 (docs/chromite/procedure-debugger.md)
 */
public interface DBGSession {

    /**
     * State of the transaction of the session that ran the debugged code (local mode).
     */
    enum TransactionState {
        /**
         * No open transaction (auto-commit, nothing run yet, or not the debugger's session: attach mode).
         */
        NONE,
        /**
         * A transaction that can be committed or rolled back.
         */
        OPEN,
        /**
         * A failed transaction (the routine failed or was stopped): it can only be rolled back.
         */
        FAILED
    }

    DBGSessionInfo getSessionInfo();

    Object getSessionId();

    List<? extends DBGBreakpointDescriptor> getBreakpoints();

    void addBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor descriptor) throws DBGException;

    void removeBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor descriptor) throws DBGException;

    boolean canStepInto();

    boolean canStepOver();

    boolean canStepReturn();

    void execContinue() throws DBGException;

    void execStepInto() throws DBGException;

    void execStepOver() throws DBGException;

    void execStepReturn() throws DBGException;

    List<? extends DBGVariable<?>> getVariables(DBGStackFrame stack) throws DBGException;

    void setVariableVal(DBGVariable<?> variable, Object value) throws DBGException;

    List<? extends DBGStackFrame> getStack() throws DBGException;

    String getSource(DBGStackFrame stack) throws DBGException;

    /**
     * Server messages (notices) of the debugged code received since the last call.
     */
    @NotNull
    List<String> readMessages();

    /**
     * What the debugged call returned or the error it ended with, once it has ended (local mode); null before or in attach mode.
     */
    @Nullable
    DBGTargetResult getTargetResult();

    /**
     * Stops the debugged code (Stop): aborts it while it is suspended, cancels it while it runs. The connections stay open.
     * In attach mode a running call of the other session is left to run without the debugger.
     */
    void abort(@NotNull DBRProgressMonitor monitor) throws DBGException;

    /**
     * The transaction of the session that ran the debugged code, asked from the server. Call once the code has ended.
     */
    @NotNull
    TransactionState getTransactionState(@NotNull DBRProgressMonitor monitor) throws DBGException;

    /**
     * Commits or rolls back the transaction of the session that ran the debugged code, as the user chose.
     */
    void endTransaction(@NotNull DBRProgressMonitor monitor, boolean commit) throws DBGException;

    /**
     * Closes the connections of the session. Ask about an open transaction first ({@link #getTransactionState}):
     * closing a connection rolls it back.
     */
    void closeSession(DBRProgressMonitor monitor) throws DBGException;

}
