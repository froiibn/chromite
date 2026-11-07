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

import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.GridEditMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.data.DBDContentStorage;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.utils.ContentUtils;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

/**
 * LOB 칸 값을 파일에서 불러오고 파일로 저장한다(SWT {@code ContentValueManager.loadFromFile}/{@code saveToFile}).
 *
 * 설계:
 * <ul>
 *     <li>파일 대화상자는 사용자가 마지막으로 고른 폴더에서 연다. 그 폴더는 Swing 설정 저장소({@link #PREF_FOLDER})에 남겨 다음 실행에도 쓴다.
 *     처음에는 JDK가 정하는 사용자의 기본 폴더(Windows: 문서)다. C: 같은 고정 경로를 쓰지 않는다.</li>
 *     <li>글자 LOB(JSON, XML, CLOB)는 UTF-8로 읽고 쓴다(SWT와 같음). 읽을 때 UTF-8 BOM은 뗀다. 이진 LOB는 바이트 그대로다.</li>
 *     <li>불러온 파일은 메모리로 읽어 열의 값 처리기가 값을 만든다({@link ResultSetGrid#setCellObject}). SWT는 파일을 저장할 때까지 열어 두는 저장소를
 *     썼지만, 그러면 저장 전에 파일이 바뀌거나 지워질 때 저장 값이 달라진다. 크기 제한은 두지 않았다(JDBC 바이트 값도 메모리에 올라감).</li>
 *     <li>저장은 칸 값 전체를 쓴다. 값 패널이 잘라 읽은(1,000,000자) LOB도 DB 값에서 끝까지 읽는다({@link ResultSetCellContent#writeTo}).
 *     있는 파일을 덮어쓰기 전에 묻고 기본 단추는 Cancel이다.</li>
 *     <li>읽고 쓰기는 백그라운드 Job에서 하고 EDT는 진행 대화상자로 기다린다({@link ProgressDialog#runTask}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: LOB 파일 불러오기·저장 (docs/chromite/swing-grid-editing.md "LOB 파일")
 */
final class ResultSetLobFiles {

    /**
     * The folder of the last LOB file chosen (Swing preference store).
     */
    static final String PREF_FOLDER = "resultset.lob.lastFolder"; //$NON-NLS-1$
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ResultSetLobFiles() {
    }

    /**
     * Asks for the file to load into a cell.
     */
    @Nullable
    static Path chooseOpen(@NotNull Component parent) {
        JFileChooser chooser = createChooser(GridEditMessages.gridedit_lob_load_title);
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path file = chooser.getSelectedFile().toPath();
        rememberFolder(file);
        return file;
    }

    /**
     * Asks for the file to save a cell's value to; confirms replacing an existing file (Cancel is the default).
     */
    @Nullable
    static Path chooseSave(@NotNull Component parent, @NotNull String defaultName) {
        JFileChooser chooser = createChooser(GridEditMessages.gridedit_lob_save_title);
        chooser.setSelectedFile(new java.io.File(chooser.getCurrentDirectory(), defaultName));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        Path file = chooser.getSelectedFile().toPath();
        if (Files.exists(file) && !SwingPlatformUI.confirmChange(parent, NLS.bind(EditorMessages.editor_export_replace_confirm, file.getFileName()),
            GridEditMessages.gridedit_lob_save_title, CoreMessages.common_replace, JOptionPane.WARNING_MESSAGE)) {
            return null;
        }
        rememberFolder(file);
        return file;
    }

    /**
     * A file name for a cell: the column name with an extension for its kind of content.
     */
    @NotNull
    static String suggestFileName(@NotNull String columnName, @Nullable String typeName, boolean text) {
        String type = CommonUtils.notEmpty(typeName).toLowerCase(Locale.ROOT);
        String extension = type.contains("json") ? "json" : type.contains("xml") ? "xml" : text ? "txt" : "bin"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        String name = columnName.replaceAll("[\\\\/:*?\"<>|]", "_"); //$NON-NLS-1$ //$NON-NLS-2$
        return (name.isBlank() ? "value" : name) + "." + extension; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private static JFileChooser createChooser(@NotNull String title) {
        String folder = getPreferences().getString(PREF_FOLDER);
        java.io.File start = null;
        if (!CommonUtils.isEmpty(folder) && Files.isDirectory(Path.of(folder))) {
            start = new java.io.File(folder);
        }
        // Without a remembered folder the JDK's default: the user's documents folder
        JFileChooser chooser = new JFileChooser(start);
        chooser.setDialogTitle(title);
        return chooser;
    }

    private static void rememberFolder(@NotNull Path file) {
        Path folder = file.toAbsolutePath().getParent();
        if (folder != null) {
            DBPPreferenceStore preferences = getPreferences();
            preferences.setValue(PREF_FOLDER, folder.toString());
            PrefUtils.savePreferenceStore(preferences);
        }
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * Reads a file to load into a cell: text (UTF-8, without a byte order mark) or bytes.
     */
    @NotNull
    static Object read(@NotNull DBRProgressMonitor monitor, @NotNull Path file, boolean text) throws DBException {
        monitor.subTask(file.toString());
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DBException(NLS.bind(GridEditMessages.gridedit_lob_cant_load, file), e);
        }
        if (!text) {
            return bytes;
        }
        if (bytes.length >= UTF8_BOM.length && Arrays.equals(bytes, 0, UTF8_BOM.length, UTF8_BOM, 0, UTF8_BOM.length)) {
            return new String(bytes, UTF8_BOM.length, bytes.length - UTF8_BOM.length, StandardCharsets.UTF_8);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Whether a value object holds text (by its content type, as SWT {@code ContentUtils.isTextContent}, or JSON/XML).
     */
    static boolean isText(@NotNull DBDContent content) {
        String contentType = CommonUtils.notEmpty(content.getContentType()).toLowerCase(Locale.ROOT);
        return ContentUtils.isTextContent(content) || contentType.endsWith("json") || contentType.endsWith("xml"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Writes a value object's whole content to a file (background job only: it may read from the database).
     */
    static void writeContent(@NotNull DBRProgressMonitor monitor, @NotNull DBDContent content, @NotNull Path file, boolean text)
        throws DBException {
        DBDContentStorage storage = content.getContents(monitor);
        try {
            if (storage == null) {
                Files.write(file, new byte[0]);
            } else if (text) {
                try (Reader in = storage.getContentReader(); Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    in.transferTo(out);
                }
            } else {
                try (InputStream in = storage.getContentStream(); OutputStream out = Files.newOutputStream(file)) {
                    in.transferTo(out);
                }
            }
        } catch (IOException e) {
            throw new DBException(NLS.bind(GridEditMessages.gridedit_lob_cant_save, file), e);
        }
    }

    /**
     * Writes text or bytes held in memory to a file.
     */
    static void writeLoaded(@Nullable String text, @Nullable byte[] bytes, @NotNull Path file) throws DBException {
        try {
            if (text != null) {
                Files.writeString(file, text, StandardCharsets.UTF_8);
            } else {
                Files.write(file, bytes != null ? bytes : new byte[0]);
            }
        } catch (IOException e) {
            throw new DBException(NLS.bind(GridEditMessages.gridedit_lob_cant_save, file), e);
        }
    }
}
