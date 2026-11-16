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

import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Oracle tablespace and data file statements: create, drop, rename, online/offline, read only/read write, add, resize,
 * autoextend and drop a data or temp file.
 *
 * 설계: 문장 모양은 시험 서버(Oracle AI Database 26ai Free 23.26, FREEPDB1과 CDB 루트)에서 직접 실행해 확인한 것만 쓴다
 * (docs/chromite/oracle-storage.md "확인한 문법"). 객체 관리자({@code OracleTablespaceManager}, {@code OracleDataFileManager})와 Object
 * Explorer 유지 관리 명령({@code ObjectMaintenanceActions})이 같은 함수를 써서 문장이 한 곳에서만 만들어진다. 화면은 값만 넘긴다.
 * <ul>
 *     <li>크기는 KB로 받고, 1024의 배수면 {@code M}, 아니면 {@code K}로 쓴다(서버가 둘 다 받음). 새 파일의 크기는 꼭 써야 한다:
 *     {@code DB_CREATE_FILE_DEST}가 없는 서버는 크기 없는 새 파일을 ORA-01119/ORA-17610으로, 이름 없는 {@code DATAFILE SIZE n}을
 *     ORA-02236으로 거절했다.</li>
 *     <li>{@code BIGFILE}/{@code SMALLFILE}은 늘 적는다. 서버 기본값({@code DATABASE_PROPERTIES.DEFAULT_TBS_TYPE})이 버전마다 달라
 *     (23ai는 BIGFILE) 미리 보기만 보고도 무엇이 만들어지는지 알 수 있게 하기 위해서다.</li>
 *     <li>자동 확장의 NEXT·MAXSIZE는 값이 있을 때만 쓴다. 쓰지 않으면 서버가 지금 값을 그대로 둔다(확인함: {@code AUTOEXTEND ON MAXSIZE 20M}
 *     뒤에도 NEXT가 그대로).</li>
 *     <li>파일 크기와 자동 확장은 한 문장에 쓸 수 없다({@code RESIZE 3M AUTOEXTEND ON}은 ORA-03049). 두 문장으로 나눈다.</li>
 *     <li>임시 테이블스페이스의 파일은 {@code TEMPFILE}이다. 영구에 TEMPFILE(ORA-01268), 임시에 DATAFILE(ORA-03217)은 거절된다.</li>
 *     <li>삭제 옵션은 {@code DROP TABLESPACE t [INCLUDING CONTENTS [AND DATAFILES] [CASCADE CONSTRAINTS]]} 순서다.
 *     {@code … CASCADE CONSTRAINTS AND DATAFILES}, 내용 없는 {@code AND DATAFILES}·{@code CASCADE CONSTRAINTS}는 ORA-02173.</li>
 *     <li>이름은 방언의 따옴표 규칙({@link DBUtils#getQuotedIdentifier}), 파일 이름은 문자열 따옴표({@link SQLUtils#quoteString})로 쓴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성: 테이블스페이스·데이터 파일 관리(앱 변경 #127, docs/chromite/oracle-storage.md)
 */
public final class OracleTablespaceDDL {

    private static final long KB_PER_MB = 1024;

    /**
     * Autoextend settings; null sizes are left out (the server's default, or the current value).
     */
    public record Autoextend(boolean enabled, @Nullable Long nextKb, @Nullable Long maxKb) {
    }

    /**
     * The space of a new data or temp file, or the change of one.
     *
     * @param file       the file name (new files only)
     * @param sizeKb     the size; null leaves it out (a change that keeps the size)
     * @param autoextend the autoextend clause; null leaves it out (the server's default, or unchanged)
     */
    public record Space(@Nullable String file, @Nullable Long sizeKb, @Nullable Autoextend autoextend) {
    }

    /**
     * The settings of a new tablespace.
     *
     * @param bigFile null when the server has no bigfile tablespaces (the keyword is left out)
     */
    public record NewTablespace(@NotNull OracleTablespace.Contents contents, @Nullable Boolean bigFile, @NotNull Space space) {
    }

    private OracleTablespaceDDL() {
    }

    /**
     * {@code CREATE [BIGFILE | SMALLFILE] [TEMPORARY | UNDO] TABLESPACE t DATAFILE | TEMPFILE 'f' SIZE n [AUTOEXTEND …]}.
     */
    @NotNull
    public static String createTablespace(@NotNull OracleTablespace tablespace, @NotNull NewTablespace settings) {
        StringBuilder sql = new StringBuilder("CREATE ");
        if (settings.bigFile() != null) {
            sql.append(settings.bigFile() ? "BIGFILE " : "SMALLFILE ");
        }
        switch (settings.contents()) {
            case TEMPORARY -> sql.append("TEMPORARY ");
            case UNDO -> sql.append("UNDO ");
            case PERMANENT -> {
                // CREATE TABLESPACE is a permanent tablespace
            }
        }
        sql.append("TABLESPACE ").append(DBUtils.getQuotedIdentifier(tablespace)).append(' ')
            .append(fileKeyword(settings.contents())).append(' ').append(quoteFile(tablespace, settings.space().file()));
        appendSize(sql, " SIZE ", settings.space().sizeKb());
        appendAutoextend(sql, settings.space().autoextend());
        return sql.toString();
    }

    /**
     * {@code DROP TABLESPACE t [INCLUDING CONTENTS [AND DATAFILES] [CASCADE CONSTRAINTS]]}. Data files and cascade need
     * the contents; without them they are not written.
     */
    @NotNull
    public static String dropTablespace(@NotNull OracleTablespace tablespace, boolean contents, boolean dataFiles, boolean cascade) {
        StringBuilder sql = new StringBuilder("DROP TABLESPACE ").append(DBUtils.getQuotedIdentifier(tablespace));
        if (contents) {
            sql.append(" INCLUDING CONTENTS");
            if (dataFiles) {
                sql.append(" AND DATAFILES");
            }
            if (cascade) {
                sql.append(" CASCADE CONSTRAINTS");
            }
        }
        return sql.toString();
    }

    /**
     * {@code ALTER TABLESPACE old RENAME TO new}.
     */
    @NotNull
    public static String renameTablespace(@NotNull OracleTablespace tablespace, @NotNull String oldName, @NotNull String newName) {
        return "ALTER TABLESPACE " + DBUtils.getQuotedIdentifier(tablespace.getDataSource(), oldName) + " RENAME TO "
            + DBUtils.getQuotedIdentifier(tablespace.getDataSource(), newName);
    }

    /**
     * {@code ALTER TABLESPACE t ONLINE | OFFLINE}.
     */
    @NotNull
    public static String setOnline(@NotNull OracleTablespace tablespace, boolean online) {
        return "ALTER TABLESPACE " + DBUtils.getQuotedIdentifier(tablespace) + (online ? " ONLINE" : " OFFLINE");
    }

    /**
     * {@code ALTER TABLESPACE t READ ONLY | READ WRITE}.
     */
    @NotNull
    public static String setReadOnly(@NotNull OracleTablespace tablespace, boolean readOnly) {
        return "ALTER TABLESPACE " + DBUtils.getQuotedIdentifier(tablespace) + (readOnly ? " READ ONLY" : " READ WRITE");
    }

    /**
     * {@code ALTER TABLESPACE t ADD DATAFILE | TEMPFILE 'f' SIZE n [AUTOEXTEND …]}.
     */
    @NotNull
    public static String addFile(@NotNull OracleTablespace tablespace, @NotNull Space space) {
        StringBuilder sql = new StringBuilder("ALTER TABLESPACE ").append(DBUtils.getQuotedIdentifier(tablespace))
            .append(" ADD ").append(fileKeyword(tablespace.getContents())).append(' ').append(quoteFile(tablespace, space.file()));
        appendSize(sql, " SIZE ", space.sizeKb());
        appendAutoextend(sql, space.autoextend());
        return sql.toString();
    }

    /**
     * The statements of a file change: {@code ALTER DATABASE DATAFILE | TEMPFILE 'f' RESIZE n} and
     * {@code … AUTOEXTEND …} (two statements: the server does not take both in one). Empty if nothing changes.
     */
    @NotNull
    public static List<String> alterFile(@NotNull OracleDataFile file, @NotNull Space change) {
        OracleTablespace tablespace = file.getTablespace();
        String prefix = "ALTER DATABASE " + fileKeyword(tablespace.getContents()) + " " + quoteFile(tablespace, file.getName());
        List<String> statements = new ArrayList<>();
        if (change.sizeKb() != null) {
            StringBuilder sql = new StringBuilder(prefix);
            appendSize(sql, " RESIZE ", change.sizeKb());
            statements.add(sql.toString());
        }
        if (change.autoextend() != null) {
            StringBuilder sql = new StringBuilder(prefix);
            appendAutoextend(sql, change.autoextend());
            statements.add(sql.toString());
        }
        return statements;
    }

    /**
     * {@code ALTER TABLESPACE t DROP DATAFILE | TEMPFILE 'f'}. The server deletes the file from its disk.
     */
    @NotNull
    public static String dropFile(@NotNull OracleDataFile file) {
        OracleTablespace tablespace = file.getTablespace();
        return "ALTER TABLESPACE " + DBUtils.getQuotedIdentifier(tablespace) + " DROP " + fileKeyword(tablespace.getContents()) + " "
            + quoteFile(tablespace, file.getName());
    }

    @NotNull
    private static String fileKeyword(@Nullable OracleTablespace.Contents contents) {
        return contents == OracleTablespace.Contents.TEMPORARY ? "TEMPFILE" : "DATAFILE";
    }

    @NotNull
    private static String quoteFile(@NotNull OracleTablespace tablespace, @Nullable String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("No data file name"); //$NON-NLS-1$
        }
        return SQLUtils.quoteString(tablespace.getDataSource(), file);
    }

    private static void appendSize(@NotNull StringBuilder sql, @NotNull String keyword, @Nullable Long sizeKb) {
        if (sizeKb != null) {
            sql.append(keyword).append(formatSize(sizeKb));
        }
    }

    private static void appendAutoextend(@NotNull StringBuilder sql, @Nullable Autoextend autoextend) {
        if (autoextend == null) {
            return;
        }
        if (!autoextend.enabled()) {
            sql.append(" AUTOEXTEND OFF");
            return;
        }
        sql.append(" AUTOEXTEND ON");
        if (autoextend.nextKb() != null) {
            sql.append(" NEXT ").append(formatSize(autoextend.nextKb()));
        }
        if (autoextend.maxKb() != null) {
            sql.append(" MAXSIZE ").append(formatSize(autoextend.maxKb()));
        }
    }

    @NotNull
    private static String formatSize(long kb) {
        return kb % KB_PER_MB == 0 ? kb / KB_PER_MB + "M" : kb + "K";
    }
}
