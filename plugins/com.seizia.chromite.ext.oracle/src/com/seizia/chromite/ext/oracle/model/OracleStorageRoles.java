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
package com.seizia.chromite.ext.oracle.model;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.sql.SQLException;

/**
 * The tablespaces the database itself depends on, as the login can read them: the database default permanent and
 * temporary tablespaces ({@code DATABASE_PROPERTIES}), the active undo tablespace ({@code V$PARAMETER undo_tablespace}),
 * and the default tablespace type of a new tablespace.
 *
 * 설계: Object Explorer의 저장소 명령은 이 값으로 "명령을 보이지 않을 테이블스페이스"를 정한다({@link OracleTablespace#isFixedStorage}).
 * 메뉴는 DB를 읽지 않고 정해야 하므로 테이블스페이스 목록을 읽을 때 같은 세션에서 함께 읽는다({@code OracleDataSource.TablespaceCache}).
 * 값을 읽지 못하면(권한 없음: 시험 서버에서 CREATE SESSION만 가진 사용자는 {@code DATABASE_PROPERTIES}는 읽고 {@code V$PARAMETER}는
 * ORA-00942) null로 두고, null이면 그 종류의 모든 테이블스페이스에 파괴적인 명령을 보이지 않는다(추측하지 않음). 두 뷰 모두 옵션 팩이
 * 필요 없다(ORACLE LICENSE GUARD).
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #127, docs/chromite/oracle-storage.md)
 */
public record OracleStorageRoles(
    @Nullable String defaultPermanent,
    @Nullable String defaultTemporary,
    @Nullable String activeUndo,
    @Nullable Boolean defaultBigFile
) {

    private static final Log log = Log.getLog(OracleStorageRoles.class);

    /**
     * Nothing read: every tablespace counts as one the database depends on.
     */
    public static final OracleStorageRoles UNKNOWN = new OracleStorageRoles(null, null, null, null);

    /**
     * Reads the values the login can read in a metadata session of the connection (New Tablespace: the default type).
     */
    @NotNull
    public static OracleStorageRoles read(@NotNull DBRProgressMonitor monitor, @NotNull OracleDataSource dataSource) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, dataSource, "Read the default tablespaces")) {
            return read(session);
        }
    }

    /**
     * Reads the values the login can read; the others stay null.
     */
    @NotNull
    public static OracleStorageRoles read(@NotNull JDBCSession session) {
        String permanent = null;
        String temporary = null;
        Boolean bigFile = null;
        try (JDBCPreparedStatement dbStat = session.prepareStatement(
            "SELECT PROPERTY_NAME, PROPERTY_VALUE FROM DATABASE_PROPERTIES WHERE PROPERTY_NAME IN "
                + "('DEFAULT_PERMANENT_TABLESPACE', 'DEFAULT_TEMP_TABLESPACE', 'DEFAULT_TBS_TYPE')");
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            while (dbResult.next()) {
                String value = dbResult.getString(2);
                switch (dbResult.getString(1)) {
                    case "DEFAULT_PERMANENT_TABLESPACE" -> permanent = value;
                    case "DEFAULT_TEMP_TABLESPACE" -> temporary = value;
                    case "DEFAULT_TBS_TYPE" -> bigFile = "BIGFILE".equals(value);
                    default -> {
                        // Not asked for
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Cannot read the default tablespaces", e);
        }
        String undo = null;
        try (JDBCPreparedStatement dbStat = session.prepareStatement("SELECT VALUE FROM V$PARAMETER WHERE NAME = 'undo_tablespace'");
             JDBCResultSet dbResult = dbStat.executeQuery()) {
            if (dbResult.next()) {
                undo = dbResult.getString(1);
            }
        } catch (SQLException e) {
            log.debug("Cannot read the active undo tablespace", e);
        }
        return new OracleStorageRoles(permanent, temporary, undo, bigFile);
    }
}
