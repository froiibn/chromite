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
package com.seizia.chromite.model.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The record of one update, {@code <updates>/update-run.json}: from [Restart now] (or the close) in the old version to
 * the first start of the new version, and what the first start after an update is.
 *
 * 설계: Turbrix #747 {@code electron/updateRun.cjs}를 옮겼다(docs/chromite/auto-update.md "업데이트 진행 창").
 * 세 프로그램이 차례로 쓴다.
 * <ol>
 *     <li>옛 앱: 버전, [지금 다시 시작]을 누른 때(또는 그냥 닫은 때), 받는 데 걸린 시간, 다시 확인을 마친 때, 창의 자리·언어·테마,
 *     로그 폴더, 되돌리기용 이전 버전 설치 파일. Windows에서 앱의 사본이 설치를 맡으면 설치 파일과 옛 앱이 확인한 SHA-256,
 *     설치 폴더, 런처, 옛 앱의 PID, 설치 범위(모든 사용자면 관리자 권한으로 설치)도 쓴다.</li>
 *     <li>앱의 사본({@code <updates>/window-app/}, {@link UpdateHandOver}): 창이 떴다는 것, 설치 프로그램을 시작·끝낸 때와 종료 코드.</li>
 *     <li>새 버전의 첫 시작: 창을 이어받았다는 것({@code takenAt}, 사본이 보고 끝남). 끝나면 기록을 지운다.</li>
 * </ol>
 * 쓰기는 다른 이름에 쓴 뒤 바꿔 넣는다(읽는 쪽이 반쯤 쓴 파일을 보지 않게). 사본도 이 클래스를 쓰므로 OSGi·{@code Log} 없이 돈다
 * (gson과 JDK만). 사본의 기록은 도우미와 같은 {@value UpdateApplier#LOG_FILE}에 남긴다({@link #log}).
 * <p>
 * 업데이트 뒤의 첫 시작({@link #firstStart}): 기록이 이번 버전으로 가는 [지금 다시 시작]이고 1시간 안이면 그 기록의 시간으로,
 * 그냥 닫을 때 적용한 업데이트면 7일 안의 첫 시작에 이번 시작부터 센다. 기록이 없고 도우미의 결과({@value UpdateApplier#RESULT_FILE},
 * 이 기능이 없던 0.1.3의 도우미가 남김)가 이번 버전의 성공이고 10분 안이면 "Chromite 시작" 단계부터 보인다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 업데이트 진행 창 (docs/chromite/auto-update.md "업데이트 진행 창")
 * - 2026-11-09 기록에 설치 범위({@code allUsers}) (docs/chromite/auto-update.md "관리자 권한으로 설치")
 */
public final class UpdateRun {

    public static final String RUN_FILE = "update-run.json"; //$NON-NLS-1$
    /**
     * The folder of the copy of the app that shows the window while the installer runs, under the updates folder.
     */
    public static final String COPY_FOLDER = "window-app"; //$NON-NLS-1$
    /**
     * A record older than this is not an update in progress any more.
     */
    static final long FRESH_MS = 60 * 60 * 1000L;
    /**
     * An update known only from the helper's result (a version without this record applied it): its first start comes
     * within this time, or it was not a [Restart now] (the window would count a wrong time).
     */
    static final long FRESH_OUTCOME_MS = 10 * 60 * 1000L;
    /**
     * An update applied at a plain close: its window shows at the first start within this time.
     */
    static final long QUIET_MS = 7 * 24 * 60 * 60 * 1000L;

    private static final String CONFIG_INI = "configuration/config.ini"; //$NON-NLS-1$
    private static final String BUNDLES_INFO = "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"; //$NON-NLS-1$
    private static final String PRODUCT_KEY = "eclipse.product"; //$NON-NLS-1$
    private static final int WRITE_TRIES = 10;

    private static final Gson GSON = new GsonBuilder().create();

    /**
     * The fields of the record (JSON names are the field names). Absent values are null.
     */
    public static final class Record {
        public int v = 1;
        public String from;
        public String to;
        /** [Restart now] was pressed (ms since the epoch). */
        public Long restartAt;
        /** The update is applied at a plain close: no window until the new version's first start. */
        public Long quitAt;
        public Long downloadMs;
        public Long checkEndAt;
        public String language;
        public Boolean dark;
        public Integer x;
        public Integer y;
        /** The folder [Open the log folder] opens (the workspace's metadata). */
        public String logFolder;
        public String downloadPage;
        /** The installer of the version updated from, for [Go back to the previous version] (Windows). */
        public Installer previous;
        // The copy of the app (Windows): what it runs
        public Long pid;
        public Installer installer;
        public String installDir;
        public String launcher;
        /** Installed for all users: the installer runs elevated (UAC). */
        public Boolean allUsers;
        public Boolean dryRun;
        /** Test mode: the exit code the dry run reports. */
        public Integer testCode;
        // Written by the copy
        public Long helperShownAt;
        public Long installStartAt;
        public Long installEndAt;
        public Integer installCode;
        // Written by the new version
        public Long takenAt;
    }

    /**
     * An installer and the SHA-256 the app checked.
     */
    public static final class Installer {
        public String version;
        public String file;
        public String sha256;

        public Installer() {
        }

        public Installer(@NotNull String version, @NotNull Path file, @NotNull String sha256) {
            this.version = version;
            this.file = file.toString();
            this.sha256 = sha256;
        }
    }

    public enum Source {
        /** [Restart now] of a version with this record. */
        RUN,
        /** Applied at a plain close: counted from this start. */
        QUIET,
        /** Known from the helper's result alone (a version before this record applied it). */
        OUTCOME
    }

    /**
     * The first start after an update, and what is known of the update.
     *
     * @param startAt     what the elapsed time counts from (null: this start)
     * @param record      the record (null for {@link Source#OUTCOME})
     */
    public record FirstStart(
        @NotNull Source source,
        @Nullable String from,
        @NotNull String to,
        @Nullable Long startAt,
        @Nullable Long downloadMs,
        @Nullable Long checkEndAt,
        @Nullable Long installEndAt,
        @Nullable Record record
    ) {
    }

    /**
     * The times of the steps (ms); null for a step whose start or end is not known.
     */
    public record Times(@Nullable Long download, @Nullable Long check, @Nullable Long install, @Nullable Long start,
                        @Nullable Long open) {
    }

    private final Path folder;

    public UpdateRun(@NotNull Path folder) {
        this.folder = folder;
    }

    @NotNull
    public Path getFolder() {
        return folder;
    }

    @NotNull
    public Path getFile() {
        return folder.resolve(RUN_FILE);
    }

    @NotNull
    public Path getCopyFolder() {
        return folder.resolve(COPY_FOLDER);
    }

    @Nullable
    public Record read() {
        Path file = getFile();
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            Record r = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Record.class);
            return r != null && r.to != null && UpdateVersion.isValid(r.to) ? r : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Writes the record: to another name first, then moved over the file (a reader never sees half a file). A move
     * that meets a reader holding the file open is tried again.
     */
    public void write(@NotNull Record record) throws IOException {
        Files.createDirectories(folder);
        Path part = folder.resolve(RUN_FILE + "." + ProcessHandle.current().pid() + ".part"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.writeString(part, GSON.toJson(record), StandardCharsets.UTF_8);
        IOException last = null;
        for (int i = 0; i < WRITE_TRIES; i++) {
            try {
                try {
                    Files.move(part, getFile(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(part, getFile(), StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Files.deleteIfExists(part);
        throw last != null ? last : new IOException("not written"); //$NON-NLS-1$
    }

    /**
     * Reads the record, changes it and writes it back. Returns the record as written, or null when there is none (or it
     * could not be written: the caller goes on from what it knows).
     */
    @Nullable
    public Record patch(@NotNull Consumer<Record> change) {
        Record r = read();
        if (r == null) {
            return null;
        }
        change.accept(r);
        try {
            write(r);
            return r;
        } catch (IOException e) {
            return null;
        }
    }

    public void remove() {
        try {
            Files.deleteIfExists(getFile());
        } catch (IOException ignored) {
            // (not fresh at the next start)
        }
    }

    /**
     * Removes the copy of an update before (it has ended by now; a copy that still runs keeps its files and the next
     * start tries again).
     */
    public void removeCopy() {
        UpdateFiles.deleteTree(getCopyFolder());
    }

    /**
     * One line in the helpers' log ({@value UpdateApplier#LOG_FILE}), as the Windows helper writes it.
     */
    public void log(@NotNull String message) {
        String line = Instant.now().truncatedTo(ChronoUnit.SECONDS) + " " + message + System.lineSeparator(); //$NON-NLS-1$
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(UpdateApplier.LOG_FILE), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // (no log)
        }
    }

    /**
     * Whether this start of {@code version} is the first start after an update, and what is known of it. Read before
     * anything removes the record or the helper's result.
     *
     * @param outcome the helper's result as it is now ({@link UpdateApplier#peekOutcome})
     * @param previousVersion the version of the installer kept for a rollback, the version updated from when the
     *                        record does not say it
     */
    @Nullable
    public static FirstStart firstStart(
        @Nullable Record run,
        @Nullable UpdateApplier.Outcome outcome,
        @Nullable String previousVersion,
        @NotNull String version,
        long now
    ) {
        if (run != null && version.equals(run.to) && run.from != null && run.restartAt != null
            && now >= run.restartAt && now - run.restartAt < FRESH_MS) {
            return new FirstStart(Source.RUN, run.from, run.to, run.restartAt, run.downloadMs, run.checkEndAt, run.installEndAt, run);
        }
        // Applied at a plain close: counted from this start
        if (run != null && version.equals(run.to) && run.from != null && run.restartAt == null && run.quitAt != null
            && now >= run.quitAt && now - run.quitAt < QUIET_MS) {
            return new FirstStart(Source.QUIET, run.from, run.to, null, null, null, null, run);
        }
        if (run == null && outcome != null && outcome.code() == 0 && version.equals(outcome.version())
            && now >= outcome.at() && now - outcome.at() < FRESH_OUTCOME_MS) {
            String from = UpdateVersion.isNewer(version, previousVersion) ? previousVersion : null;
            return new FirstStart(Source.OUTCOME, from, version, null, null, null, null, null);
        }
        return null;
    }

    /**
     * The steps' times of a first start: what the record knows, the start of this process ({@code appStartAt}), and the
     * moments Chromite was started (workspace and bundles) and its window shown.
     */
    @NotNull
    public static Times timesOf(@NotNull FirstStart start, long appStartAt, @Nullable Long startEndAt, @Nullable Long openEndAt) {
        Long installEnd = start.installEndAt() != null ? start.installEndAt() : Long.valueOf(appStartAt);
        Long check = start.source() == Source.RUN ? span(start.startAt(), start.checkEndAt()) : null;
        Long open = startEndAt != null ? span(Math.max(startEndAt, appStartAt), openEndAt) : null;
        return new Times(start.downloadMs(), check, span(start.checkEndAt(), installEnd), span(appStartAt, startEndAt), open);
    }

    @Nullable
    static Long span(@Nullable Long from, @Nullable Long to) {
        return from != null && to != null && to >= from ? to - from : null;
    }

    /**
     * The product version installed in {@code installDir} now: the version of the product's defining bundle in the
     * installation's bundle list ({@code configuration/config.ini} names the product, {@code bundles.info} lists the
     * bundles). Null when it cannot be read (removed, half replaced): a failed installation then does not name the
     * version in use.
     */
    @Nullable
    public static String installedVersion(@NotNull Path installDir) {
        try {
            Properties config = new Properties();
            try (InputStream in = Files.newInputStream(installDir.resolve(CONFIG_INI))) {
                config.load(in);
            }
            String product = config.getProperty(PRODUCT_KEY);
            int dot = product == null ? -1 : product.lastIndexOf('.');
            if (dot <= 0) {
                return null;
            }
            // The product id is <defining bundle>.<id>
            String bundle = product.substring(0, dot) + ","; //$NON-NLS-1$
            try (Stream<String> lines = Files.lines(installDir.resolve(BUNDLES_INFO), StandardCharsets.UTF_8)) {
                return lines.filter(l -> l.startsWith(bundle))
                    .map(l -> l.substring(bundle.length()).split(",", 2)[0]) //$NON-NLS-1$
                    .map(UpdateRun::plainVersion)
                    .filter(v -> v != null)
                    .findFirst().orElse(null);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code 0.1.3.202611080230} as {@code 0.1.3}.
     */
    @Nullable
    static String plainVersion(@NotNull String bundleVersion) {
        String[] parts = bundleVersion.trim().split("\\.", 4); //$NON-NLS-1$
        if (parts.length < 3) {
            return null;
        }
        String v = parts[0] + "." + parts[1] + "." + parts[2]; //$NON-NLS-1$ //$NON-NLS-2$
        return UpdateVersion.isValid(v) ? v : null;
    }
}
