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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.PrefUtils;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Query Library 자동 새로 고침: 루트 폴더의 파일이 바뀌면(동료가 공유 폴더에 저장, {@code git pull}, 탐색기에서 복사) 트리를 다시 읽게 알린다.
 *
 * 설계:
 * <ul>
 *     <li>두 가지를 함께 쓴다. 파일 시스템 감시({@link WatchService}, 로컬 폴더에서 바로 반영)와 주기 확인(설정 {@link #PREF_INTERVAL},
 *     기본 {@value #DEFAULT_INTERVAL_SECONDS}초). 네트워크 폴더(SMB)에서는 감시 이벤트가 오지 않거나 빠지는 일이 있어 주기 확인이 빠진 것을 메운다.</li>
 *     <li>확인은 파일 내용을 읽지 않고 폴더 목록과 파일의 크기·수정 시각만 모은 지문({@link #fingerprint})을 지난 읽기 때의 지문과 비교한다.
 *     같으면 아무것도 하지 않는다. 느린 공유 폴더를 반복해서 다 읽지 않기 위해서다. 감시 이벤트도 같은 비교를 거치므로 이 창에서 저장해 이미 다시 읽은
 *     변경은 한 번 더 읽지 않는다. 이벤트는 0.7초 모아서 한 번 확인한다(저장 하나가 이벤트 여러 개를 낸다).</li>
 *     <li>지문은 트리를 읽기 직전에 만든다({@link #setBaseline}). 읽는 동안 바뀐 파일은 다음 확인에서 다시 읽는다.</li>
 *     <li>{@code .}으로 시작하는 폴더·파일({@code .git})은 트리처럼 보지 않는다. {@code git status}가 {@code .git}을 고쳐도 다시 읽지 않는다.</li>
 *     <li>감시 스레드와 주기 타이머는 데몬 스레드이고 메인 창을 닫으면 멈춘다({@link #stop}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 Query Library ④ 공유: 최초 작성 (docs/chromite/swing-query-library.md "공유")
 */
final class QueryLibraryWatcher {

    private static final Log log = Log.getLog(QueryLibraryWatcher.class);

    static final String PREF_INTERVAL = "queryLibrary.refreshSeconds"; //$NON-NLS-1$
    static final int DEFAULT_INTERVAL_SECONDS = 30;
    static final int MAX_INTERVAL_SECONDS = 3600;
    private static final long DEBOUNCE_MILLIS = 700;
    private static final int IDLE_SECONDS = 5;
    private static final String MISSING = "missing"; //$NON-NLS-1$
    private static final String FOLDER = "folder"; //$NON-NLS-1$

    static {
        ChromiteSwingActivator.getInstance().getPreferences().setDefault(PREF_INTERVAL, DEFAULT_INTERVAL_SECONDS);
    }

    private final Runnable onChange;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Query Library refresh"); //$NON-NLS-1$
        thread.setDaemon(true);
        return thread;
    });
    @Nullable
    private WatchService watchService;
    private final Map<Path, WatchKey> watched = new HashMap<>();
    private volatile List<QueryLibrary.Root> roots = List.of();
    @Nullable
    private volatile Map<Path, String> baseline;
    @Nullable
    private ScheduledFuture<?> pendingCheck;
    private volatile boolean stopped;

    /**
     * @param onChange called on a background thread when the roots changed on disk
     */
    QueryLibraryWatcher(@NotNull Runnable onChange) {
        this.onChange = onChange;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Thread thread = new Thread(this::watch, "Query Library watcher"); //$NON-NLS-1$
            thread.setDaemon(true);
            thread.start();
        } catch (IOException e) {
            log.warn("File system watch is not available; the Query Library is checked every " + getInterval() + " s", e);
        }
        schedulePoll();
    }

    /**
     * Seconds between checks of the roots; 0 checks only on file system events.
     */
    static int getInterval() {
        int value = ChromiteSwingActivator.getInstance().getPreferences().getInt(PREF_INTERVAL);
        return Math.max(0, Math.min(MAX_INTERVAL_SECONDS, value));
    }

    static void setInterval(int seconds) {
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        preferences.setValue(PREF_INTERVAL, Math.max(0, Math.min(MAX_INTERVAL_SECONDS, seconds)));
        PrefUtils.savePreferenceStore(preferences);
    }

    /**
     * Schedules the next periodic check. The interval is read each time, so a change in Options applies after the
     * current wait; with 0 only the setting is looked at again (every {@value #IDLE_SECONDS} s).
     */
    private synchronized void schedulePoll() {
        if (stopped) {
            return;
        }
        int interval = getInterval();
        timer.schedule(() -> {
            if (getInterval() > 0) {
                check();
            }
            schedulePoll();
        }, interval > 0 ? interval : IDLE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Called by a read of the library (job thread) before it reads the files: the state the tree will show, and the
     * folders to watch.
     */
    void setBaseline(@NotNull List<QueryLibrary.Root> roots, @NotNull Map<Path, String> fingerprint) {
        this.roots = List.copyOf(roots);
        this.baseline = fingerprint;
        registerFolders(fingerprint);
    }

    synchronized void stop() {
        stopped = true;
        timer.shutdownNow();
        WatchService service = watchService;
        if (service != null) {
            try {
                service.close();
            } catch (IOException e) {
                log.debug(e);
            }
        }
    }

    /**
     * Folder list and file sizes and times of all roots (no file content). A missing root is marked so that it is
     * read again once it is back (an unplugged share).
     */
    @NotNull
    static Map<Path, String> fingerprint(@NotNull List<QueryLibrary.Root> roots) {
        Map<Path, String> result = new HashMap<>();
        for (QueryLibrary.Root root : roots) {
            Path path = root.path().toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                collect(path, result);
            } else {
                result.put(path, MISSING);
            }
        }
        return result;
    }

    private static void collect(@NotNull Path folder, @NotNull Map<Path, String> result) {
        result.put(folder, FOLDER);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path child : stream) {
                String name = String.valueOf(child.getFileName());
                if (name.startsWith(".")) { //$NON-NLS-1$
                    continue;
                }
                BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class);
                if (attributes.isDirectory()) {
                    collect(child, result);
                } else if (name.toLowerCase(Locale.ROOT).endsWith(QueryLibraryFile.EXTENSION)) {
                    result.put(child, attributes.size() + ":" + attributes.lastModifiedTime().toMillis()); //$NON-NLS-1$
                }
            }
        } catch (IOException e) {
            // A folder that can't be read now reads differently from one that can: it is read again when it comes back
            result.put(folder, MISSING);
        }
    }

    /**
     * Compares the roots with the state of the last read and reports a change (timer thread).
     */
    private void check() {
        if (stopped) {
            return;
        }
        Map<Path, String> last = baseline;
        if (last == null) {
            // The first read has not finished yet
            return;
        }
        try {
            Map<Path, String> now = fingerprint(roots);
            if (!now.equals(last) && baseline == last) {
                // Not again for the same change while the read it starts is running
                baseline = now;
                onChange.run();
            }
        } catch (RuntimeException e) {
            log.debug("Query Library check failed", e);
        }
    }

    //////////////////////////////////////////////////////////
    // File system events

    private synchronized void registerFolders(@NotNull Map<Path, String> fingerprint) {
        WatchService service = watchService;
        if (service == null || stopped) {
            return;
        }
        Set<Path> folders = new HashSet<>();
        fingerprint.forEach((path, value) -> {
            if (FOLDER.equals(value)) {
                folders.add(path);
            }
        });
        watched.entrySet().removeIf(entry -> {
            if (!folders.contains(entry.getKey())) {
                entry.getValue().cancel();
                return true;
            }
            return false;
        });
        for (Path folder : folders) {
            if (!watched.containsKey(folder)) {
                try {
                    watched.put(folder, folder.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY));
                } catch (IOException | RuntimeException e) {
                    // Some network file systems can't be watched; the timer still checks them
                    log.debug("Can't watch " + folder + ": " + e.getMessage());
                }
            }
        }
    }

    private void watch() {
        WatchService service = watchService;
        if (service == null) {
            return;
        }
        try {
            while (!stopped) {
                WatchKey key = service.take();
                // The events themselves don't matter: any change (or an overflow) leads to one check
                key.pollEvents();
                key.reset();
                scheduleCheck();
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // Stopped
        }
    }

    private synchronized void scheduleCheck() {
        if (stopped) {
            return;
        }
        if (pendingCheck != null) {
            pendingCheck.cancel(false);
        }
        pendingCheck = timer.schedule(this::check, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
    }
}
