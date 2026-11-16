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
package com.seizia.chromite.debug.jdbc;

import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import com.seizia.chromite.debug.*;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCExecutionContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Debug session driven by SQL calls on a controller connection.
 *
 * 설계: 단계 실행 같은 막히는 명령은 {@link DBGJDBCWorker} 하나가 실행한다. 그동안 제어 연결은 바쁘므로 다른 호출(스택, 변수,
 * 중단점)은 대상이 멈춘 뒤에만 한다. 세션을 닫을 때는 실행 중인 명령을 JDBC 취소로 멈춘 뒤 연결을 닫는다
 * ({@link #closeSession}). 중단점 목록은 서버에 성공적으로 넣은 것만 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 작업 실패를 하위 클래스에 넘김({@link #workerFailed}),
 *   closeSession이 대상 멈추기(abort) 없이 명령 취소와 연결 닫기만 함, 쓰지 않는 composeAbortCommand·isDone 삭제
 *   (docs/chromite/procedure-debugger.md)
 */
public abstract class DBGJDBCSession implements DBGSession {

    private final DBGBaseController controller;

    protected volatile DBGJDBCWorker workerJob;

    private final List<DBGBreakpointDescriptor> breakpoints = new ArrayList<>(1);

    protected DBGJDBCSession(DBGBaseController controller) {
        this.controller = controller;
    }

    protected DBGBaseController getController() {
        return controller;
    }

    public abstract JDBCExecutionContext getControllerConnection();

    /**
     * Return true if debug session up and running on server
     * 
     * @return boolean
     */
    public abstract boolean isAttached();

    /**
     * Return true while a blocking debugger command runs (the target runs, or the session waits for a target).
     */
    public boolean isWaiting() {
        DBGJDBCWorker job = this.workerJob;
        return (job != null && !job.isFinished());
    }

    /**
     * Start thread for SQL command
     */
    protected void runAsync(String commandSQL, String name, DBGEvent begin, DBGEvent end) throws DBGException {
        DBGJDBCWorker job = new DBGJDBCWorker(this, name, commandSQL, begin, end);
        job.addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void done(IJobChangeEvent event) {
                if (workerJob == job) {
                    workerJob = null;
                }
            }
        });
        workerJob = job;
        job.schedule();
    }

    /**
     * A blocking command failed: the target ended or went away, or the command was canceled.
     */
    protected abstract void workerFailed(@NotNull SQLException error);

    /**
     * Cancels the running blocking command, if any (another thread may call it).
     */
    protected void cancelWorker() {
        DBGJDBCWorker job = workerJob;
        if (job != null) {
            job.cancelStatement();
        }
    }

    @Override
    public void closeSession(DBRProgressMonitor monitor) throws DBGException {
        cancelWorker();
        doClose(monitor);
    }

    /**
     * Closes the connections of the session.
     */
    protected abstract void doClose(@NotNull DBRProgressMonitor monitor) throws DBGException;

    @Override
    public List<? extends DBGBreakpointDescriptor> getBreakpoints() {
        return new ArrayList<>(breakpoints);
    }

    @Override
    public void addBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor descriptor) throws DBGException {
        try (JDBCSession session = getControllerConnection().openSession(monitor, DBCExecutionPurpose.UTIL, "Add breakpoint")) {
            try (Statement stmt = session.createStatement()) {
                String sqlQuery = composeAddBreakpointCommand(descriptor);
                stmt.execute(sqlQuery);
            } catch (SQLException e) {
                throw new DBGException("SQL error", e);
            }
            breakpoints.add(descriptor);
        }
    }

    protected abstract String composeAddBreakpointCommand(DBGBreakpointDescriptor descriptor);

    @Override
    public void removeBreakpoint(DBRProgressMonitor monitor, DBGBreakpointDescriptor bp) throws DBGException {
        try (JDBCSession session = getControllerConnection().openSession(monitor, DBCExecutionPurpose.UTIL, "Remove breakpoint")) {
            try (Statement stmt = session.createStatement()) {
                String sqlCommand = composeRemoveBreakpointCommand(bp);
                stmt.execute(sqlCommand);
            } catch (SQLException e) {
                throw new DBGException("SQL error", e);
            }
            breakpoints.remove(bp);
        }
    }

    protected abstract String composeRemoveBreakpointCommand(DBGBreakpointDescriptor descriptor);

    protected void fireEvent(DBGEvent event) {
        controller.fireEvent(event);
    }

}