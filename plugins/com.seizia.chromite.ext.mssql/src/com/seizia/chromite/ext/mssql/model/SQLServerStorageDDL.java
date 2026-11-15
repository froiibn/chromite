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
package com.seizia.chromite.ext.mssql.model;

import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Filegroup and database file statements of SQL Server: add, rename, remove a filegroup, make it the default, read-only or
 * read-write; add a data or log file, change its size, growth and maximum size, rename and remove it.
 *
 * 설계: 문장 모양은 시험 서버(SQL Server 2022 CU27, Linux)에서 직접 실행해 확인한 것만 쓴다(docs/chromite/mssql-storage.md "확인한 문법").
 * 객체 관리자({@code SQLServerFilegroupManager}, {@code SQLServerDatabaseFileManager})와 Object Explorer 유지 관리 명령
 * ({@code ObjectMaintenanceActions})이 같은 함수를 써서 문장이 한 곳에서만 만들어진다. 화면은 값만 넘기고 SQL을 만들지 않는다.
 * <ul>
 *     <li>모든 문장은 {@code ALTER DATABASE [db] …}이고 사용자 트랜잭션 안에서는 실행되지 않는다(서버 오류 226). 실행하는 쪽이
 *     자동 커밋으로 실행한다.</li>
 *     <li>크기는 정수 MB로만 쓴다. 서버가 소수 MB를 구문 오류로 거절한다({@code SIZE = 4.5MB}: 오류 102).</li>
 *     <li>논리 파일 이름은 {@code NAME = N'…'} 문자열로, 파일 그룹 이름과 {@code REMOVE FILE}의 논리 이름은 방언의 식별자 따옴표로,
 *     실제 파일 경로는 {@code N'…'} 문자열로 쓴다(따옴표·한글이 든 이름도 확인함).</li>
 *     <li>자동 증가를 끄는 것은 {@code FILEGROWTH = 0}이다. 최대 크기를 비우면 {@code MAXSIZE = UNLIMITED}다(로그 파일은 서버가
 *     2 TB로 기록한다).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성: 파일·파일 그룹 관리(앱 변경 #128, docs/chromite/mssql-storage.md)
 */
public final class SQLServerStorageDDL {

    /**
     * Autogrowth of a file: {@code value} MB, or {@code value} percent.
     */
    public record Growth(long value, boolean percent) {
    }

    /**
     * Settings of a new file, or the change of an existing one (null parts are not written).
     *
     * @param fileName  the physical file (new files only)
     * @param sizeMb    the size in MB
     * @param growth    the growth; {@link #GROWTH_OFF} turns autogrowth off
     * @param maxSizeMb the maximum size in MB; {@link #UNLIMITED} for no limit
     */
    public record FileSpec(@Nullable String fileName, @Nullable Long sizeMb, @Nullable Growth growth, @Nullable Long maxSizeMb) {
        public boolean isEmpty() {
            return fileName == null && sizeMb == null && growth == null && maxSizeMb == null;
        }
    }

    /**
     * No autogrowth ({@code FILEGROWTH = 0}).
     */
    public static final Growth GROWTH_OFF = new Growth(0, false);

    /**
     * No maximum size ({@code MAXSIZE = UNLIMITED}).
     */
    public static final long UNLIMITED = -1;

    private SQLServerStorageDDL() {
    }

    /**
     * {@code ALTER DATABASE d ADD FILEGROUP fg}.
     */
    @NotNull
    public static String addFilegroup(@NotNull SQLServerFilegroup filegroup) {
        return alterDatabase(filegroup.getDatabase()) + " ADD FILEGROUP " + DBUtils.getQuotedIdentifier(filegroup);
    }

    /**
     * {@code ALTER DATABASE d REMOVE FILEGROUP fg}. The server refuses a filegroup that has files (5042).
     */
    @NotNull
    public static String removeFilegroup(@NotNull SQLServerFilegroup filegroup) {
        return alterDatabase(filegroup.getDatabase()) + " REMOVE FILEGROUP " + DBUtils.getQuotedIdentifier(filegroup);
    }

    /**
     * {@code ALTER DATABASE d MODIFY FILEGROUP fg NAME = new}.
     */
    @NotNull
    public static String renameFilegroup(@NotNull SQLServerFilegroup filegroup, @NotNull String oldName, @NotNull String newName) {
        SQLServerDataSource dataSource = filegroup.getDataSource();
        return alterDatabase(filegroup.getDatabase()) + " MODIFY FILEGROUP " + DBUtils.getQuotedIdentifier(dataSource, oldName)
            + " NAME = " + DBUtils.getQuotedIdentifier(dataSource, newName);
    }

    /**
     * {@code ALTER DATABASE d MODIFY FILEGROUP fg DEFAULT}.
     */
    @NotNull
    public static String setDefault(@NotNull SQLServerFilegroup filegroup) {
        return modifyFilegroup(filegroup) + " DEFAULT";
    }

    /**
     * {@code ALTER DATABASE d MODIFY FILEGROUP fg READ_ONLY | READ_WRITE}. Read-only needs the database for itself (5070
     * while another session uses it).
     */
    @NotNull
    public static String setReadOnly(@NotNull SQLServerFilegroup filegroup, boolean readOnly) {
        return modifyFilegroup(filegroup) + (readOnly ? " READ_ONLY" : " READ_WRITE");
    }

    /**
     * {@code ALTER DATABASE d ADD FILE (…) TO FILEGROUP fg} for a file of a filegroup, {@code ALTER DATABASE d ADD LOG FILE (…)}
     * for a log file.
     */
    @NotNull
    public static String addFile(@NotNull SQLServerDatabaseFile file, @NotNull FileSpec spec) {
        if (spec.fileName() == null || spec.fileName().isBlank()) {
            throw new IllegalArgumentException("No file name"); //$NON-NLS-1$
        }
        StringBuilder sql = new StringBuilder(alterDatabase(file.getDatabase()));
        sql.append(file.isLog() ? " ADD LOG FILE (" : " ADD FILE (");
        sql.append("NAME = ").append(literal(file, file.getName()));
        sql.append(", FILENAME = ").append(literal(file, spec.fileName()));
        appendSpec(sql, spec);
        sql.append(')');
        if (!file.isLog()) {
            sql.append(" TO FILEGROUP ").append(DBUtils.getQuotedIdentifier(file.getFilegroup()));
        }
        return sql.toString();
    }

    /**
     * {@code ALTER DATABASE d MODIFY FILE (NAME = N'f', SIZE = …, FILEGROWTH = …, MAXSIZE = …)} with the changed parts (one
     * statement takes all three). Empty if nothing changes.
     */
    @NotNull
    public static List<String> modifyFile(@NotNull SQLServerDatabaseFile file, @NotNull FileSpec change) {
        List<String> statements = new ArrayList<>();
        if (change.sizeMb() == null && change.growth() == null && change.maxSizeMb() == null) {
            return statements;
        }
        StringBuilder sql = new StringBuilder(alterDatabase(file.getDatabase()));
        sql.append(" MODIFY FILE (NAME = ").append(literal(file, file.getName()));
        appendSpec(sql, change);
        sql.append(')');
        statements.add(sql.toString());
        return statements;
    }

    /**
     * {@code ALTER DATABASE d MODIFY FILE (NAME = N'old', NEWNAME = N'new')}: the logical name; the physical file keeps its
     * name.
     */
    @NotNull
    public static String renameFile(@NotNull SQLServerDatabaseFile file, @NotNull String oldName, @NotNull String newName) {
        return alterDatabase(file.getDatabase()) + " MODIFY FILE (NAME = " + literal(file, oldName) + ", NEWNAME = " + literal(file, newName) + ")";
    }

    /**
     * {@code ALTER DATABASE d REMOVE FILE f}. The server refuses a file that holds data (5042) and the primary data and log
     * files (5020); it deletes the physical file of a removed one.
     */
    @NotNull
    public static String removeFile(@NotNull SQLServerDatabaseFile file) {
        return alterDatabase(file.getDatabase()) + " REMOVE FILE " + DBUtils.getQuotedIdentifier(file);
    }

    @NotNull
    private static String alterDatabase(@NotNull SQLServerDatabase database) {
        return "ALTER DATABASE " + DBUtils.getQuotedIdentifier(database);
    }

    @NotNull
    private static String modifyFilegroup(@NotNull SQLServerFilegroup filegroup) {
        return alterDatabase(filegroup.getDatabase()) + " MODIFY FILEGROUP " + DBUtils.getQuotedIdentifier(filegroup);
    }

    private static void appendSpec(@NotNull StringBuilder sql, @NotNull FileSpec spec) {
        if (spec.sizeMb() != null) {
            sql.append(", SIZE = ").append(spec.sizeMb()).append("MB");
        }
        if (spec.growth() != null) {
            sql.append(", FILEGROWTH = ").append(spec.growth().value());
            if (spec.growth().value() != 0) {
                sql.append(spec.growth().percent() ? "%" : "MB");
            }
        }
        if (spec.maxSizeMb() != null) {
            sql.append(", MAXSIZE = ").append(spec.maxSizeMb() == UNLIMITED ? "UNLIMITED" : spec.maxSizeMb() + "MB");
        }
    }

    /**
     * The dialect's string literal, which for SQL Server is Unicode ({@code N'…'}): logical names and paths may hold any character.
     */
    @NotNull
    private static String literal(@NotNull SQLServerDatabaseFile file, @NotNull String value) {
        return SQLUtils.quoteString(file.getDataSource(), value);
    }
}
