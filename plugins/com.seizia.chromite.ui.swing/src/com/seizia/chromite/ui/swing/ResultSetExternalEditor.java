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

import com.seizia.chromite.ui.swing.internal.ValueEditMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 셀 값을 OS의 기본 프로그램으로 열고, 그 프로그램에서 저장하면 셀에 다시 넣는 외부 편집기. SWT {@code ContentValueManager}의
 * "Open in external editor"는 임시 파일로 열기만 하고 다시 읽지 않았다. Chromite는 저장한 내용을 셀에 넣는다(값을 밖에서 고치는 흐름).
 *
 * 설계:
 * <ul>
 *     <li>값은 플랫폼 임시 폴더({@code DBPPlatform.getTempFolder}, 작업 공간 아래, 이름 {@value #TEMP_FOLDER})의 파일로 쓴다. 확장자는
 *     파일 불러오기·저장과 같은 규칙({@link ResultSetLobFiles#suggestFileName}: JSON, XML, 글자 txt, 이진 bin)에 더해, 이진 값은 첫 바이트로
 *     PNG·JPEG·GIF·PDF·ZIP·BMP를 알아본다(그 형식의 기본 프로그램이 열리게).</li>
 *     <li>열기는 {@link Desktop#open}이다. 프로그램이 뜨는 동안 기다리지 않도록 따로 스레드에서 부른다.</li>
 *     <li>저장 감지는 1초마다 파일의 수정 시각과 크기를 보는 방식이다(WatchService는 폴더 단위이고 편집기마다 저장 방식이 달라 같은 결과를
 *     얻으려면 어차피 다시 확인해야 한다). 바뀐 뒤 한 번 더 같은 상태면(쓰기가 끝남) 파일을 읽어 셀에 넣는다. 셀에 넣는 일은 파일 불러오기와
 *     같은 길이라({@link Target#apply}, 열의 값 처리기) 저장·저장하고 커밋 규칙이 그대로다. 셀 값을 바꿀 뿐 DB는 저장할 때 바뀐다.</li>
 *     <li>결과 뷰어가 표를 새로 만들거나(다시 실행, 정렬·필터·다음 페이지. 저장 뒤 행 다시 읽기는 같은 표라 계속됨) 행이 없어지거나 결과 창을 닫으면
 *     ({@link Target#isAlive}, {@link #stopAll}) 지켜보기를 멈추고 임시 파일을 지운다. 읽기 전용 셀은 열기만 하고 지켜보지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 외부 편집기 (docs/chromite/swing-value-editors.md)
 */
final class ResultSetExternalEditor {

    private static final Log log = Log.getLog(ResultSetExternalEditor.class);

    /**
     * Folder of the files, under the platform's temporary folder.
     */
    static final String TEMP_FOLDER = "value-editor"; //$NON-NLS-1$
    private static final int POLL_MS = 1000;

    /**
     * Where a saved file goes.
     */
    interface Target {
        /**
         * Puts the file's content (text or bytes) in the cell.
         *
         * @return null if it was set, otherwise why not
         */
        @Nullable
        String apply(@NotNull Object input);

        /**
         * Whether the cell is still there to take the file (the same result, the row not removed).
         */
        boolean isAlive();

        /**
         * Shows a short notice next to the grid.
         */
        void showStatus(@NotNull String text);
    }

    /**
     * Writes the value to the file (background job: it may read a LOB from the database).
     */
    @FunctionalInterface
    interface ContentWriter {
        void write(@NotNull DBRProgressMonitor monitor, @NotNull Path file) throws DBException;
    }

    /**
     * A file being watched: its last known state and the state seen at the previous poll.
     */
    private static final class Watch {
        final Path file;
        final boolean text;
        final Target target;
        @Nullable
        Stamp applied;
        @Nullable
        Stamp pending;

        Watch(@NotNull Path file, boolean text, @NotNull Target target) {
            this.file = file;
            this.text = text;
            this.target = target;
            this.applied = Stamp.of(file);
        }
    }

    private record Stamp(@NotNull FileTime modified, long size) {
        @Nullable
        static Stamp of(@NotNull Path file) {
            try {
                return new Stamp(Files.getLastModifiedTime(file), Files.size(file));
            } catch (IOException e) {
                return null;
            }
        }
    }

    private final List<Watch> watches = new ArrayList<>();
    private final Timer timer = new Timer(POLL_MS, e -> poll());

    /**
     * Writes a cell's value to a file, opens it in the default application and, for an editable cell, watches it.
     *
     * @param text   the value is text (written and read as UTF-8) rather than bytes
     * @param target where saved changes go, or null for a read-only cell (opened only)
     */
    void open(
        @NotNull Component parent,
        @NotNull String columnName,
        @Nullable String typeName,
        boolean text,
        @NotNull ContentWriter writer,
        @Nullable Target target
    ) {
        Path file;
        try {
            file = ProgressDialog.runTask(ValueEditMessages.valueedit_external_title, monitor -> {
                Path written;
                try {
                    Path folder = DBWorkbench.getPlatform().getTempFolder(monitor, TEMP_FOLDER);
                    String name = ResultSetLobFiles.suggestFileName(columnName, typeName, text);
                    int dot = name.lastIndexOf('.');
                    written = Files.createTempFile(folder, name.substring(0, dot) + "-", name.substring(dot)); //$NON-NLS-1$
                } catch (IOException e) {
                    throw new DBException("Can't create the file of the value", e); //$NON-NLS-1$
                }
                writer.write(monitor, written);
                return text ? written : renameByContent(written);
            });
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ValueEditMessages.valueedit_external_title, ValueEditMessages.valueedit_external_cant_open, e);
            return;
        }
        if (target != null) {
            watch(file, text, target);
            target.showStatus(NLS.bind(ValueEditMessages.valueedit_external_watching, file.getFileName()));
        } else {
            file.toFile().deleteOnExit();
        }
        launch(parent, file);
        if (target == null) {
            JOptionPane.showMessageDialog(parent, NLS.bind(ValueEditMessages.valueedit_external_view_only, file.getFileName()),
                ValueEditMessages.valueedit_external_title, JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Starts watching a written file: each save is put in the cell.
     */
    void watch(@NotNull Path file, boolean text, @NotNull Target target) {
        file.toFile().deleteOnExit();
        watches.add(new Watch(file, text, target));
        timer.start();
    }

    /**
     * Stops watching every file and deletes them (the result viewer is closed).
     */
    void stopAll() {
        timer.stop();
        for (Watch watch : watches) {
            delete(watch.file);
        }
        watches.clear();
    }

    private static void launch(@NotNull Component parent, @NotNull Path file) {
        Thread thread = new Thread(() -> {
            try {
                Desktop.getDesktop().open(file.toFile());
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> DBWorkbench.getPlatformUI().showError(
                    ValueEditMessages.valueedit_external_title, ValueEditMessages.valueedit_external_cant_open, e));
            }
        }, "Open value in external editor"); //$NON-NLS-1$
        thread.setDaemon(true);
        thread.start();
    }

    private void poll() {
        for (Watch watch : new ArrayList<>(watches)) {
            if (!watch.target.isAlive() || !Files.exists(watch.file)) {
                stop(watch);
                watch.target.showStatus(NLS.bind(ValueEditMessages.valueedit_external_stopped, watch.file.getFileName()));
                continue;
            }
            Stamp now = Stamp.of(watch.file);
            if (now == null || now.equals(watch.applied)) {
                watch.pending = null;
                continue;
            }
            if (!now.equals(watch.pending)) {
                // Changed since the last poll: the application may still be writing
                watch.pending = now;
                continue;
            }
            watch.applied = now;
            watch.pending = null;
            apply(watch);
        }
        if (watches.isEmpty()) {
            timer.stop();
        }
    }

    private static void apply(@NotNull Watch watch) {
        String error;
        try {
            error = watch.target.apply(ResultSetLobFiles.read(new VoidProgressMonitor(), watch.file, watch.text));
        } catch (DBException e) {
            error = ErrorText.describeLine(e);
        }
        String name = watch.file.getFileName().toString();
        watch.target.showStatus(error == null
            ? NLS.bind(ValueEditMessages.valueedit_external_applied, name)
            : NLS.bind(ValueEditMessages.valueedit_external_cant_apply, name, error));
    }

    private void stop(@NotNull Watch watch) {
        watches.remove(watch);
        delete(watch.file);
    }

    private static void delete(@NotNull Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Still open in the application: removed when Chromite exits (deleteOnExit)
            log.debug("Can't delete " + file, e); //$NON-NLS-1$
        }
    }

    /**
     * Gives a binary file the extension of its format, found from its first bytes, so the default application of that
     * format opens it.
     */
    @NotNull
    private static Path renameByContent(@NotNull Path file) {
        byte[] head = new byte[8];
        int count;
        try (InputStream in = Files.newInputStream(file)) {
            count = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            return file;
        }
        String extension = detectExtension(head, count);
        if (extension == null) {
            return file;
        }
        String name = file.getFileName().toString();
        Path renamed = file.resolveSibling(name.substring(0, name.lastIndexOf('.') + 1) + extension);
        try {
            return Files.move(file, renamed);
        } catch (IOException e) {
            return file;
        }
    }

    @Nullable
    static String detectExtension(@NotNull byte[] head, int count) {
        if (startsWith(head, count, 0x89, 'P', 'N', 'G')) {
            return "png"; //$NON-NLS-1$
        }
        if (startsWith(head, count, 0xFF, 0xD8, 0xFF)) {
            return "jpg"; //$NON-NLS-1$
        }
        if (startsWith(head, count, 'G', 'I', 'F', '8')) {
            return "gif"; //$NON-NLS-1$
        }
        if (startsWith(head, count, '%', 'P', 'D', 'F')) {
            return "pdf"; //$NON-NLS-1$
        }
        if (startsWith(head, count, 'P', 'K', 3, 4)) {
            return "zip"; //$NON-NLS-1$
        }
        if (startsWith(head, count, 'B', 'M')) {
            return "bmp"; //$NON-NLS-1$
        }
        return null;
    }

    private static boolean startsWith(@NotNull byte[] head, int count, int... prefix) {
        if (count < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((head[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

}
