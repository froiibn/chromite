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
package com.seizia.chromite.ext.altibase.model;

import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.sql.SQLUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Tablespace and data file statements of Altibase-family servers (Altibase, ShardSphere): create, drop, online/offline,
 * autoextend, add/resize/drop a data or temp file.
 *
 * 설계: 문장 모양은 시험 서버(Altibase 7.3.0.0.7)에서 직접 실행해 확인한 것만 쓴다(docs/chromite/altibase.md "테이블스페이스 관리"의
 * 확인한 문법). 객체 관리자({@code AltibaseTablespaceManager}, {@code AltibaseDataFileManager})와 Object Explorer 유지 관리 명령
 * ({@code ObjectMaintenanceActions})이 같은 함수를 써서 문장이 한 곳에서만 만들어진다. 화면은 값만 넘기고 SQL을 만들지 않는다.
 * <ul>
 *     <li>크기는 KB로 받고, 1024의 배수면 {@code M}, 아니면 {@code K}로 쓴다(서버가 둘 다 받음). 값이 없으면 그 절을 쓰지 않아 서버
 *     기본값을 따른다(디스크 파일 크기 100 MB, 자동 확장 끔, NEXT·MAXSIZE는 서버 기본).</li>
 *     <li>자동 확장을 켤 때 NEXT를 빼면 서버가 NEXT를 기본값으로 되돌린다(확인함). 그래서 설정을 바꾸는 문장은 NEXT·MAXSIZE를 늘
 *     함께 다시 쓴다({@link Autoextend}의 두 값). {@code MAXSIZE UNLIMITED}는 디스크 전체 한도 검사로 거절되므로 쓰지 않는다.</li>
 *     <li>파일 크기와 자동 확장은 한 문장에 함께 쓸 수 없다(서버 구문 오류). 두 문장으로 나눈다.</li>
 *     <li>임시 테이블스페이스의 파일은 {@code TEMPFILE}이다. {@code DATAFILE}로 쓰면 서버가 "Mismatched tablespace type"으로 거절한다.</li>
 *     <li>삭제 옵션은 서버 문법 {@code DROP TABLESPACE t [INCLUDING CONTENTS [AND DATAFILES] [CASCADE CONSTRAINTS]]}을 따른다.
 *     파일 삭제와 제약 조건 연쇄는 내용 삭제와 함께일 때만 쓸 수 있다.</li>
 *     <li>이름은 방언의 따옴표 규칙({@link DBUtils#getQuotedIdentifier}), 파일 이름은 문자열 따옴표({@link SQLUtils#quoteString})로 쓴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성: 테이블스페이스 관리(앱 변경 #126, docs/chromite/altibase.md "테이블스페이스 관리")
 */
public final class AltibaseTablespaceDDL {

    private static final long KB_PER_MB = 1024;

    /**
     * Autoextend settings; null sizes are left to the server's default.
     */
    public record Autoextend(boolean enabled, @Nullable Long nextKb, @Nullable Long maxKb) {
    }

    /**
     * The space of a new tablespace or data file, or the change of a data file.
     *
     * @param file       the data or temp file (disk and temporary tablespaces; null for memory and volatile)
     * @param sizeKb     the size; null leaves it out (the server's default, or unchanged)
     * @param autoextend the autoextend clause; null leaves it out (the server's default, or unchanged)
     */
    public record Space(@Nullable String file, @Nullable Long sizeKb, @Nullable Autoextend autoextend) {
    }

    private AltibaseTablespaceDDL() {
    }

    /**
     * {@code CREATE [MEMORY | VOLATILE | TEMPORARY] TABLESPACE …} of a new user tablespace of the type.
     */
    @NotNull
    public static String createTablespace(@NotNull AltibaseTablespace tablespace, @NotNull Space space) {
        AltibaseTablespace.TbsType type = tablespace.getTbsTypeEnum();
        StringBuilder sql = new StringBuilder("CREATE ");
        switch (type) {
            case MEMORY_USER_DATA -> sql.append("MEMORY ");
            case VOLATILE_USER_DATA -> sql.append("VOLATILE ");
            case DISK_USER_TEMP -> sql.append("TEMPORARY ");
            case DISK_USER_DATA -> {
                // CREATE TABLESPACE is a disk data tablespace
            }
            default -> throw new IllegalArgumentException("A " + type + " tablespace cannot be created"); //$NON-NLS-1$
        }
        sql.append("TABLESPACE ").append(DBUtils.getQuotedIdentifier(tablespace));
        if (tablespace.hasDiskFiles()) {
            sql.append(' ').append(fileKeyword(tablespace)).append(' ').append(quoteFile(tablespace, space.file()));
        }
        appendSize(sql, space.sizeKb());
        appendAutoextend(sql, space.autoextend());
        return sql.toString();
    }

    /**
     * {@code DROP TABLESPACE t [INCLUDING CONTENTS [AND DATAFILES] [CASCADE CONSTRAINTS]]}. Data files and cascade need
     * the contents; without them they are not written.
     */
    @NotNull
    public static String dropTablespace(@NotNull AltibaseTablespace tablespace, boolean contents, boolean dataFiles, boolean cascade) {
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
     * {@code ALTER TABLESPACE t ONLINE | OFFLINE}.
     */
    @NotNull
    public static String setOnline(@NotNull AltibaseTablespace tablespace, boolean online) {
        return "ALTER TABLESPACE " + DBUtils.getQuotedIdentifier(tablespace) + (online ? " ONLINE" : " OFFLINE");
    }

    /**
     * {@code ALTER TABLESPACE t ALTER AUTOEXTEND …} of a memory or volatile tablespace.
     */
    @NotNull
    public static String alterAutoextend(@NotNull AltibaseTablespace tablespace, @NotNull Autoextend autoextend) {
        StringBuilder sql = new StringBuilder("ALTER TABLESPACE ").append(DBUtils.getQuotedIdentifier(tablespace)).append(" ALTER");
        appendAutoextend(sql, autoextend);
        return sql.toString();
    }

    /**
     * {@code ALTER TABLESPACE t ADD DATAFILE | TEMPFILE 'f' [SIZE …] [AUTOEXTEND …]}.
     */
    @NotNull
    public static String addFile(@NotNull AltibaseTablespace tablespace, @NotNull Space space) {
        StringBuilder sql = new StringBuilder("ALTER TABLESPACE ").append(DBUtils.getQuotedIdentifier(tablespace))
            .append(" ADD ").append(fileKeyword(tablespace)).append(' ').append(quoteFile(tablespace, space.file()));
        appendSize(sql, space.sizeKb());
        appendAutoextend(sql, space.autoextend());
        return sql.toString();
    }

    /**
     * The statements of a data file change: {@code ALTER TABLESPACE t ALTER DATAFILE 'f' SIZE …} and
     * {@code … AUTOEXTEND …} (two statements: the server does not take both in one). Empty if nothing changes.
     */
    @NotNull
    public static List<String> alterFile(@NotNull AltibaseDataFile file, @NotNull Space change) {
        AltibaseTablespace tablespace = file.getParentObject();
        String prefix = "ALTER TABLESPACE " + DBUtils.getQuotedIdentifier(tablespace) + " ALTER " + fileKeyword(tablespace) + " "
            + quoteFile(tablespace, file.getName());
        List<String> statements = new ArrayList<>();
        if (change.sizeKb() != null) {
            StringBuilder sql = new StringBuilder(prefix);
            appendSize(sql, change.sizeKb());
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
     * {@code ALTER TABLESPACE t DROP DATAFILE | TEMPFILE 'f'}. The server keeps the file on its disk.
     */
    @NotNull
    public static String dropFile(@NotNull AltibaseDataFile file) {
        AltibaseTablespace tablespace = file.getParentObject();
        return "ALTER TABLESPACE " + DBUtils.getQuotedIdentifier(tablespace) + " DROP " + fileKeyword(tablespace) + " "
            + quoteFile(tablespace, file.getName());
    }

    @NotNull
    private static String fileKeyword(@NotNull AltibaseTablespace tablespace) {
        return tablespace.isTemporary() ? "TEMPFILE" : "DATAFILE";
    }

    @NotNull
    private static String quoteFile(@NotNull AltibaseTablespace tablespace, @Nullable String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("No data file name"); //$NON-NLS-1$
        }
        return SQLUtils.quoteString(tablespace.getDataSource(), file);
    }

    private static void appendSize(@NotNull StringBuilder sql, @Nullable Long sizeKb) {
        if (sizeKb != null) {
            sql.append(" SIZE ").append(formatSize(sizeKb));
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
