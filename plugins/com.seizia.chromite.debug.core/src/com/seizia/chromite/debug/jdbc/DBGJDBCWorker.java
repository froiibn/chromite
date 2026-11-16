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
package com.seizia.chromite.debug.jdbc;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;
import com.seizia.chromite.debug.DBGEvent;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Runs one blocking debugger command (step, continue, wait for a target) on the controller connection.
 *
 * 설계: 단계 실행 명령은 대상이 다시 멈출 때까지 돌아오지 않으므로 작업(Job)에서 실행하고, 시작·끝을 {@link DBGEvent}로 알린다.
 * 실패(대상이 끝나 서버 함수가 오류를 냄, 취소)는 SWT 버전처럼 상태로만 남기지 않고 세션에 알린다({@link DBGJDBCSession#workerFailed}).
 * 그러지 않으면 화면이 "실행 중"에서 멈춘다. 실행 중인 문장은 {@link #cancelStatement}로 다른 스레드에서 취소할 수 있게 들고 있다
 * (JDBC {@code Statement.cancel}은 다른 스레드에서 부를 수 있다).
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 실패를 세션에 알림, 실행 중인 문장 취소 (docs/chromite/procedure-debugger.md)
 */
public class DBGJDBCWorker extends AbstractJob {

    private static final Log log = Log.getLog(DBGJDBCWorker.class);

    private final DBGJDBCSession debugSession;
    private final String sql;
    private final DBGEvent before;
    private final DBGEvent after;
    private volatile Statement statement;

    public DBGJDBCWorker(DBGJDBCSession debugSession, String name, String sqlCommand, DBGEvent begin, DBGEvent end) {
        super(name);
        this.debugSession = debugSession;
        this.sql = sqlCommand;
        this.before = begin;
        this.after = end;
    }

    @NotNull
    @Override
    protected IStatus run(@NotNull DBRProgressMonitor monitor) {
        monitor.beginTask("Execute debug job", 1);
        try (JDBCSession session = debugSession.getControllerConnection().openSession(monitor, DBCExecutionPurpose.UTIL, "Run debug job")) {
            monitor.subTask(sql);
            try (Statement stmt = session.createStatement()) {
                statement = stmt;
                debugSession.fireEvent(before);
                stmt.execute(sql);
            } finally {
                statement = null;
            }
        } catch (SQLException e) {
            log.debug("Debugger command failed: " + sql + ": " + e.getMessage());
            debugSession.workerFailed(e);
            return Status.OK_STATUS;
        } finally {
            monitor.done();
        }
        debugSession.fireEvent(after);
        return Status.OK_STATUS;
    }

    /**
     * Cancels the command if it is running (another thread).
     */
    public void cancelStatement() {
        Statement running = statement;
        if (running != null) {
            try {
                running.cancel();
            } catch (SQLException e) {
                log.debug("Cannot cancel debugger command: " + e.getMessage());
            }
        }
    }

}