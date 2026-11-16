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
package com.seizia.chromite.model.admin;

import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 관리 화면(공간 사용량, 서버 로그)이 시스템 뷰를 읽는 짧은 도우미.
 *
 * 설계: 두 기능의 DB별 방언이 같은 모양(질의 하나, 행마다 읽기, 값이 없으면 null)으로 시스템 뷰를 읽으므로 한 곳에 둔다.
 * 실행은 화면의 격리된 실행 컨텍스트 세션({@code AdminConnection})에서 하고, 오류는 호출한 쪽이 권한 안내나 {@code DBException}으로 바꾼다.
 * 읽기 전용이다: 이 클래스로 서버의 상태를 바꾸는 문장을 실행하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 공간 사용량·서버 로그 (docs/chromite/swing-admin-tools.md)
 */
public final class AdminSql {

    /**
     * Reads one row of a result.
     */
    @FunctionalInterface
    public interface RowReader {
        void read(@NotNull JDBCResultSet resultSet) throws SQLException;
    }

    private AdminSql() {
    }

    /**
     * Runs a query and reads every row.
     */
    public static void query(@NotNull JDBCSession session, @NotNull String sql, @NotNull RowReader reader, @NotNull Object... parameters)
        throws SQLException {
        try (JDBCPreparedStatement statement = session.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (JDBCResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    reader.read(resultSet);
                }
            }
        }
    }

    /**
     * The first column of every row.
     */
    @NotNull
    public static List<String> queryStrings(@NotNull JDBCSession session, @NotNull String sql, @NotNull Object... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        query(session, sql, resultSet -> values.add(resultSet.getString(1)), parameters);
        return values;
    }

    /**
     * The first column of the first row, or null.
     */
    @Nullable
    public static String queryString(@NotNull JDBCSession session, @NotNull String sql, @NotNull Object... parameters) throws SQLException {
        List<String> values = queryStrings(session, sql, parameters);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * A number column, or null when the value is SQL NULL.
     */
    @Nullable
    public static Long getLong(@NotNull JDBCResultSet resultSet, int column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    /**
     * Whether a query can be run (the login may read the views it names). Parsing and opening are enough: the query should
     * read no rows ({@code WHERE 1 = 0}). Only for databases where a failed statement does not abort the transaction
     * (not PostgreSQL).
     *
     * @return null if it can, otherwise the error of the server
     */
    @Nullable
    public static SQLException probe(@NotNull JDBCSession session, @NotNull String sql) {
        try (JDBCPreparedStatement statement = session.prepareStatement(sql);
             JDBCResultSet ignored = statement.executeQuery()) {
            return null;
        } catch (SQLException e) {
            return e;
        }
    }
}
