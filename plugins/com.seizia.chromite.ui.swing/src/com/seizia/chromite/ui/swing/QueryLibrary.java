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

import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import java.awt.*;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Query Library 저장소: 라이브러리 루트(폴더) 목록, 폴더 트리 읽기, 쿼리 파일 만들기·저장·이름 바꾸기·옮기기·복제·삭제.
 *
 * 설계:
 * <ul>
 *     <li>쿼리 하나가 파일 하나({@link QueryLibraryFile})이고 폴더 구조가 곧 트리다. 별도 색인 파일을 두지 않는다. 그래서 Git 저장소나
 *     네트워크 공유 폴더를 그대로 루트로 쓸 수 있고, 다른 사람이 파일을 바꾸면 다시 읽기(Refresh)만으로 반영된다.
 *     이름이 {@code .}으로 시작하는 폴더·파일({@code .git} 등)은 읽지 않는다.</li>
 *     <li>루트는 여러 개다: 기본 루트 "My Queries"(작업 공간의 {@code QueryLibrary} 폴더)와 사용자가 더한 루트(공유 폴더, Git 작업 사본).
 *     루트 목록은 이 번들의 설정 저장소({@code queryLibrary.roots})에 저장한다. 읽기 전용 루트에서는 쓰기 명령을 막는다.</li>
 *     <li>읽기({@link #load})는 파일을 여러 개 읽으므로 작업 스레드에서 한다(네트워크 폴더가 느릴 수 있음). 파일 하나를 바꾸는 명령은 EDT에서 한다.</li>
 *     <li>읽을 때 ID → 파일 위치 색인을 만든다. 편집 중인 쿼리의 파일을 트리에서 옮겨도 저장할 때 ID로 새 위치를 찾는다({@link #resolveFile}).</li>
 *     <li>삭제는 운영체제가 지원하면 휴지통으로 보낸다(실수로 지운 쿼리를 되살릴 수 있게). 지원하지 않으면 지운다.</li>
 *     <li>정렬은 자연 정렬({@link #NATURAL_ORDER}: "2. …"가 "10. …"보다 앞)이고 폴더가 쿼리보다 앞이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "저장 위치와 루트")
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 */
final class QueryLibrary {

    private static final Log log = Log.getLog(QueryLibrary.class);

    private static final String PREF_ROOTS = "queryLibrary.roots"; //$NON-NLS-1$
    private static final String DEFAULT_ROOT_FOLDER = "QueryLibrary"; //$NON-NLS-1$
    static final String DEFAULT_ROOT_NAME = ToolsMessages.library_root_default;

    /**
     * Compares names with their numbers by value ("2. a" before "10. b"), the rest without case.
     */
    static final Comparator<String> NATURAL_ORDER = QueryLibrary::compareNatural;

    /**
     * A library root folder.
     *
     * @param readOnly no new, rename, move, delete or save in it (e.g. a shared folder that others maintain)
     */
    record Root(@NotNull String name, @NotNull Path path, boolean readOnly) {
    }

    /**
     * A folder read from disk, with its subfolders and queries in display order.
     */
    static final class Folder {
        final Root root;
        final Path path;
        final List<Folder> folders = new ArrayList<>();
        final List<QueryLibraryItem> queries = new ArrayList<>();
        // A root whose folder was not there when it was read (an unplugged share, a wrong path)
        boolean missing;

        Folder(@NotNull Root root, @NotNull Path path) {
            this.root = root;
            this.path = path;
        }

        @NotNull
        String getName() {
            return path.equals(root.path()) ? root.name() : String.valueOf(path.getFileName());
        }
    }

    private static QueryLibrary instance;

    private final List<Root> roots = new CopyOnWriteArrayList<>();
    // Query id -> file, from the last load of each root
    private final Map<String, Path> fileIndex = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private QueryLibrary() {
        loadRoots();
    }

    @NotNull
    static synchronized QueryLibrary getInstance() {
        if (instance == null) {
            instance = new QueryLibrary();
        }
        return instance;
    }

    //////////////////////////////////////////////////////////
    // Roots

    @NotNull
    List<Root> getRoots() {
        return List.copyOf(roots);
    }

    /**
     * The personal root: {@code QueryLibrary} in the workspace folder (moves with the workspace, not tied to a drive).
     */
    @NotNull
    static Root getDefaultRoot() {
        Path workspace = DBWorkbench.getPlatform().getWorkspace().getAbsolutePath();
        return new Root(DEFAULT_ROOT_NAME, workspace.resolve(DEFAULT_ROOT_FOLDER), false);
    }

    void setRoots(@NotNull List<Root> newRoots) {
        roots.clear();
        roots.addAll(newRoots);
        StringBuilder value = new StringBuilder();
        for (Root root : newRoots) {
            // One root per line: name, path, read-only (tabs can't be in names typed in a text field)
            value.append(root.name().replace('\t', ' ')).append('\t').append(root.path()).append('\t').append(root.readOnly()).append('\n');
        }
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        preferences.setValue(PREF_ROOTS, value.toString());
        PrefUtils.savePreferenceStore(preferences);
        fireChanged();
    }

    private void loadRoots() {
        String value = ChromiteSwingActivator.getInstance().getPreferences().getString(PREF_ROOTS);
        if (!CommonUtils.isEmpty(value)) {
            for (String line : value.split("\n")) { //$NON-NLS-1$
                String[] parts = line.split("\t"); //$NON-NLS-1$
                if (parts.length >= 2 && !parts[1].isBlank()) {
                    try {
                        roots.add(new Root(parts[0], Path.of(parts[1]), parts.length > 2 && Boolean.parseBoolean(parts[2])));
                    } catch (IllegalArgumentException e) {
                        log.warn("Bad Query Library root: " + line);
                    }
                }
            }
        }
        if (roots.isEmpty()) {
            roots.add(getDefaultRoot());
        }
    }

    /**
     * The root that contains a file or folder, or null.
     */
    @Nullable
    Root findRoot(@NotNull Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Root found = null;
        for (Root root : roots) {
            Path rootPath = root.path().toAbsolutePath().normalize();
            // The innermost root wins if roots are nested
            if (normalized.startsWith(rootPath) && (found == null || rootPath.startsWith(found.path().toAbsolutePath().normalize()))) {
                found = root;
            }
        }
        return found;
    }

    boolean isReadOnly(@NotNull Path path) {
        Root root = findRoot(path);
        return root == null || root.readOnly();
    }

    //////////////////////////////////////////////////////////
    // Change listeners (EDT)

    /**
     * Called after a change made through this class (the tree reloads).
     */
    void addListener(@NotNull Runnable listener) {
        listeners.add(listener);
    }

    void fireChanged() {
        listeners.forEach(Runnable::run);
    }

    //////////////////////////////////////////////////////////
    // Reading (job thread)

    /**
     * Reads a root's folders and queries. A missing personal root is created; a missing other root reads as empty.
     */
    @NotNull
    Folder load(@NotNull Root root) throws IOException {
        if (!Files.isDirectory(root.path()) && !root.readOnly() && root.path().equals(getDefaultRoot().path())) {
            Files.createDirectories(root.path());
        }
        Folder folder = new Folder(root, root.path());
        if (Files.isDirectory(root.path())) {
            loadFolder(folder);
        } else {
            folder.missing = true;
        }
        return folder;
    }

    private void loadFolder(@NotNull Folder folder) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder.path)) {
            for (Path child : stream) {
                String name = String.valueOf(child.getFileName());
                if (name.startsWith(".")) { //$NON-NLS-1$
                    continue;
                }
                if (Files.isDirectory(child)) {
                    Folder subfolder = new Folder(folder.root, child);
                    loadFolder(subfolder);
                    folder.folders.add(subfolder);
                } else if (name.toLowerCase(Locale.ROOT).endsWith(QueryLibraryFile.EXTENSION)) {
                    try {
                        QueryLibraryItem item = QueryLibraryFile.read(child);
                        if (item.getId() != null) {
                            fileIndex.put(item.getId(), child);
                        }
                        folder.queries.add(item);
                    } catch (IOException e) {
                        log.warn("Can't read query file " + child, e);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Can't read Query Library folder " + folder.path, e);
        }
        folder.folders.sort(Comparator.comparing(Folder::getName, NATURAL_ORDER));
        folder.queries.sort(Comparator.comparing(QueryLibraryItem::getName, NATURAL_ORDER));
    }

    /**
     * The current file of a query: its own file, or where the last load found its id (the file was moved).
     */
    @Nullable
    Path resolveFile(@NotNull QueryLibraryItem item) {
        Path file = item.getFile();
        if (file != null && Files.isRegularFile(file)) {
            return file;
        }
        Path indexed = item.getId() != null ? fileIndex.get(item.getId()) : null;
        return indexed != null && Files.isRegularFile(indexed) ? indexed : file;
    }

    //////////////////////////////////////////////////////////
    // Changes (EDT; each touches a few files)

    @NotNull
    Path createFolder(@NotNull Path parent, @NotNull String name) throws IOException {
        checkWritable(parent);
        Path folder = uniquePath(parent, QueryLibraryFile.toFileName(name), ""); //$NON-NLS-1$
        Files.createDirectories(folder);
        fireChanged();
        return folder;
    }

    /**
     * Writes a new query file into a folder, named after the query.
     */
    @NotNull
    QueryLibraryItem createQuery(@NotNull Path folder, @NotNull QueryLibraryItem item) throws IOException {
        checkWritable(folder);
        item.ensureId();
        Path file = uniquePath(folder, QueryLibraryFile.toFileName(item.getName()), QueryLibraryFile.EXTENSION);
        QueryLibraryFile.write(item, file);
        indexFile(item);
        fireChanged();
        return item;
    }

    /**
     * Saves a changed query to its file (found again by id if it was moved). The update time is set; the file name
     * follows the name of the query.
     */
    void save(@NotNull QueryLibraryItem item) throws IOException {
        Path file = resolveFile(item);
        if (file == null) {
            throw new IOException("The query has no file");
        }
        checkWritable(file);
        item.ensureId();
        if (item.getCreated() == null) {
            item.setCreated(OffsetDateTime.now().withNano(0));
        }
        item.setUpdated(OffsetDateTime.now().withNano(0));
        QueryLibraryFile.write(item, file);
        Path renamed = renameToName(file, item.getName());
        item.setFile(renamed);
        indexFile(item);
        fireChanged();
    }

    /**
     * Renames a query: its name in the file and the file name.
     */
    void renameQuery(@NotNull QueryLibraryItem item, @NotNull String newName) throws IOException {
        Path file = resolveFile(item);
        if (file == null) {
            throw new IOException("The query has no file");
        }
        checkWritable(file);
        // Read the file again: it may have been changed since the tree was loaded
        QueryLibraryItem current = QueryLibraryFile.read(file);
        current.setName(newName);
        current.ensureId();
        if (current.hasHeader() || !newName.equals(stem(file))) {
            QueryLibraryFile.write(current, file);
        }
        current.setFile(renameToName(file, newName));
        indexFile(current);
        fireChanged();
    }

    @NotNull
    Path renameFolder(@NotNull Path folder, @NotNull String newName) throws IOException {
        checkWritable(folder);
        String fileName = QueryLibraryFile.toFileName(newName);
        if (fileName.equals(String.valueOf(folder.getFileName()))) {
            return folder;
        }
        Path target = uniquePath(folder.getParent(), fileName, ""); //$NON-NLS-1$
        Files.move(folder, target);
        fireChanged();
        return target;
    }

    /**
     * Moves a query file or a folder into another folder (drag and drop). Ids stay the same, so the local state of
     * the queries follows them.
     */
    @NotNull
    Path move(@NotNull Path source, @NotNull Path targetFolder) throws IOException {
        checkWritable(source);
        checkWritable(targetFolder);
        if (targetFolder.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize())) {
            throw new IOException(ToolsMessages.library_error_move_into_itself);
        }
        if (source.getParent() != null && source.getParent().equals(targetFolder)) {
            return source;
        }
        String fileName = String.valueOf(source.getFileName());
        boolean query = Files.isRegularFile(source);
        String stem = query ? stem(source) : fileName;
        Path target = uniquePath(targetFolder, stem, query ? QueryLibraryFile.EXTENSION : ""); //$NON-NLS-1$
        Files.move(source, target);
        fireChanged();
        return target;
    }

    /**
     * Copies a query into the same folder (or another) with a new id and a "copy" name.
     */
    @NotNull
    QueryLibraryItem duplicate(@NotNull QueryLibraryItem item, @NotNull Path targetFolder) throws IOException {
        QueryLibraryItem copy = item.copy();
        copy.setId(null);
        copy.setFile(null);
        copy.setName(NLS.bind(ToolsMessages.library_copy_name, item.getName()));
        OffsetDateTime now = OffsetDateTime.now().withNano(0);
        copy.setCreated(now);
        copy.setUpdated(now);
        return createQuery(targetFolder, copy);
    }

    /**
     * Deletes a query file or a folder with everything in it: to the trash if the system has one.
     */
    void delete(@NotNull Path path) throws IOException {
        checkWritable(path);
        Root root = findRoot(path);
        if (root != null && root.path().toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize())) {
            throw new IOException(ToolsMessages.library_error_delete_root);
        }
        boolean trashed = false;
        try {
            trashed = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)
                && Desktop.getDesktop().moveToTrash(path.toFile());
        } catch (RuntimeException e) {
            log.debug("Can't move to trash: " + path, e);
        }
        if (!trashed) {
            deleteRecursively(path);
        }
        fireChanged();
    }

    private static void deleteRecursively(@NotNull Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            Files.deleteIfExists(path);
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @NotNull
            @Override
            public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @NotNull
            @Override
            public FileVisitResult postVisitDirectory(@NotNull Path dir, @Nullable IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void checkWritable(@NotNull Path path) throws IOException {
        Root root = findRoot(path);
        if (root == null) {
            throw new IOException(NLS.bind(ToolsMessages.library_error_not_in_root, path));
        }
        if (root.readOnly()) {
            throw new IOException(NLS.bind(ToolsMessages.library_error_read_only_root, root.name()));
        }
    }

    private void indexFile(@NotNull QueryLibraryItem item) {
        if (item.getId() != null && item.getFile() != null) {
            fileIndex.put(item.getId(), item.getFile());
        }
    }

    /**
     * Renames a query file to match the query name (no change if it matches already).
     */
    @NotNull
    private static Path renameToName(@NotNull Path file, @NotNull String name) throws IOException {
        String fileName = QueryLibraryFile.toFileName(name);
        if (fileName.equals(stem(file))) {
            return file;
        }
        Path target = uniquePath(file.getParent(), fileName, QueryLibraryFile.EXTENSION);
        Files.move(file, target);
        return target;
    }

    @NotNull
    private static String stem(@NotNull Path file) {
        String name = String.valueOf(file.getFileName());
        return name.toLowerCase(Locale.ROOT).endsWith(QueryLibraryFile.EXTENSION)
            ? name.substring(0, name.length() - QueryLibraryFile.EXTENSION.length()) : name;
    }

    /**
     * {@code folder/stem+extension}, or {@code stem (2)+extension} etc. if that exists.
     */
    @NotNull
    static Path uniquePath(@NotNull Path folder, @NotNull String stem, @NotNull String extension) {
        Path path = folder.resolve(stem + extension);
        for (int i = 2; Files.exists(path); i++) {
            path = folder.resolve(stem + " (" + i + ")" + extension); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return path;
    }

    /**
     * Natural order: runs of digits compare by value, the rest without case.
     */
    static int compareNatural(@NotNull String a, @NotNull String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String numberA = a.substring(startA, i).replaceFirst("^0+(?=.)", ""); //$NON-NLS-1$ //$NON-NLS-2$
                String numberB = b.substring(startB, j).replaceFirst("^0+(?=.)", ""); //$NON-NLS-1$ //$NON-NLS-2$
                int result = numberA.length() != numberB.length() ? numberA.length() - numberB.length() : numberA.compareTo(numberB);
                if (result != 0) {
                    return result;
                }
            } else {
                int result = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (result != 0) {
                    return result;
                }
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }
}
