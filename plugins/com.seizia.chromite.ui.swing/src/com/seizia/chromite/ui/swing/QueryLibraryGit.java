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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Query Library 루트가 Git 작업 사본일 때의 Git 명령: 상태(브랜치, 앞섬/뒤처짐, 충돌 파일), Pull, Commit &amp; Push.
 *
 * 설계:
 * <ul>
 *     <li>시스템 {@code git} 명령을 실행한다. 대상 플랫폼에 JGit이 없고(2026-11-07 {@code org.eclipse.jgit} 검색 결과 없음) 새 의존성을 넣지 않기 위해서다.
 *     사용자의 Git 설정(이름·메일, 자격 증명 도우미, SSH 키, 프록시)을 그대로 쓰므로 Chromite는 자격 증명을 저장하지 않는다.
 *     {@code GIT_TERMINAL_PROMPT=0}이라 Git이 터미널에서 비밀번호를 묻느라 멈추지 않는다(자격 증명 도우미의 창은 그대로 뜬다).
 *     원격 명령은 제한 시간(5분)이 지나면 끝낸다.</li>
 *     <li>Git이 없으면({@link #isAvailable}) 명령을 끄고 이유를 도구 설명으로 보인다. 확인은 처음 한 번 작업 스레드에서 한다.</li>
 *     <li>작업 사본인지는 루트 폴더부터 위로 {@code .git}을 찾아 정한다(프로세스를 띄우지 않음). 루트가 저장소의 하위 폴더여도 된다.
 *     상태는 {@code git status --porcelain=v2 --branch -z}(사람용 문구가 아니라 기계용 형식, 경로는 저장소 최상위 기준)를 읽는다.
 *     {@code --no-optional-locks}로 상태를 읽는 동안 색인 파일을 다시 쓰지 않는다.</li>
 *     <li>Pull은 {@code git pull --no-rebase --no-edit}이다. 겹치지 않는 변경은 Git이 합치고, 같은 줄을 고친 충돌은 Git이 충돌 표시를 남긴 채 멈춘다.
 *     Chromite는 충돌을 합치지 않는다: 충돌 파일을 트리에 표시하고 알린다. 사용자가 파일을 고친 뒤 Commit &amp; Push하면 그 파일을 해결된 것으로 올린다.
 *     충돌 표시({@code <<<<<<< })가 남은 파일은 올리지 않는다.</li>
 *     <li>Commit &amp; Push는 루트 폴더 아래만 올린다({@code git add -A -- .}, {@code git commit -- .}). 병합 중에는 Git이 일부 커밋을 받지 않으므로
 *     색인 전체를 커밋한다. 메시지는 표준 입력(UTF-8)으로 넘긴다. 올라갈 브랜치가 없으면 {@code origin}에 같은 이름으로 올린다.</li>
 *     <li>Git의 사람용 출력은 언어 설정에 따라 바뀌므로 해석하지 않는다. 성공 여부는 종료 코드, 충돌은 다시 읽은 상태로 정하고, 출력은 사용자에게 그대로 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 Query Library ④ 공유: 최초 작성 (docs/chromite/swing-query-library.md "공유")
 */
final class QueryLibraryGit {

    private static final Log log = Log.getLog(QueryLibraryGit.class);

    private static final String GIT = "git"; //$NON-NLS-1$
    private static final long LOCAL_TIMEOUT_SECONDS = 30;
    private static final long REMOTE_TIMEOUT_SECONDS = 300;
    private static final String CONFLICT_MARKER = "<<<<<<< "; //$NON-NLS-1$
    private static final String DEFAULT_REMOTE = "origin"; //$NON-NLS-1$

    // null until checked, then whether "git --version" ran
    @Nullable
    private static volatile Boolean available;

    /**
     * State of a working copy.
     *
     * @param branch      current branch, or null when detached
     * @param upstream    whether the branch has an upstream (ahead and behind are known)
     * @param ahead       local commits not pushed
     * @param behind      remote commits not pulled, as of the last fetch (Pull)
     * @param conflicts   files with merge conflicts
     * @param changes     changed, new and deleted files under the root
     */
    record Status(
        @Nullable String branch,
        boolean upstream,
        int ahead,
        int behind,
        @NotNull Set<Path> conflicts,
        @NotNull List<Path> changes
    ) {
    }

    /**
     * Result of a git command: exit code 0 is success; the output is stdout and stderr together.
     */
    record Result(int exitCode, @NotNull String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    private QueryLibraryGit() {
    }

    /**
     * Whether {@code git} can be run. The first call runs {@code git --version} (job thread); later calls are cached.
     */
    static boolean checkAvailable() {
        Boolean result = available;
        if (result == null) {
            try {
                result = run(Path.of(".").toAbsolutePath(), LOCAL_TIMEOUT_SECONDS, null, "--version").ok(); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (IOException e) {
                log.debug("Git is not available: " + e.getMessage());
                result = false;
            }
            available = result;
        }
        return result;
    }

    /**
     * The cached answer of {@link #checkAvailable} for the EDT: false while unknown.
     */
    static boolean isAvailable() {
        return Boolean.TRUE.equals(available);
    }

    /**
     * The top folder of the Git working copy that contains a folder, or null.
     */
    @Nullable
    static Path findWorkTree(@NotNull Path folder) {
        for (Path dir = folder.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            // A folder for a repository, a file for a linked worktree or a submodule
            if (Files.exists(dir.resolve(".git"))) { //$NON-NLS-1$
                return dir;
            }
        }
        return null;
    }

    /**
     * Reads the state of the working copy, limited to the files under the root.
     */
    @NotNull
    static Status status(@NotNull Path root, @NotNull Path workTree) throws IOException {
        Result result = run(root, LOCAL_TIMEOUT_SECONDS, null,
            "--no-optional-locks", "status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all", "--", "."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        if (!result.ok()) {
            throw new IOException(result.output().strip());
        }
        return parseStatus(result.output(), workTree);
    }

    @NotNull
    private static Status parseStatus(@NotNull String output, @NotNull Path workTree) {
        String branch = null;
        boolean upstream = false;
        int ahead = 0;
        int behind = 0;
        Set<Path> conflicts = new LinkedHashSet<>();
        List<Path> changes = new ArrayList<>();
        String[] entries = output.split("\0"); //$NON-NLS-1$
        for (int i = 0; i < entries.length; i++) {
            String entry = entries[i];
            if (entry.startsWith("# branch.head ")) { //$NON-NLS-1$
                String head = entry.substring("# branch.head ".length()); //$NON-NLS-1$
                branch = head.equals("(detached)") ? null : head; //$NON-NLS-1$
            } else if (entry.startsWith("# branch.upstream ")) { //$NON-NLS-1$
                upstream = true;
            } else if (entry.startsWith("# branch.ab ")) { //$NON-NLS-1$
                // "# branch.ab +1 -2"
                String[] parts = entry.substring("# branch.ab ".length()).split(" "); //$NON-NLS-1$ //$NON-NLS-2$
                if (parts.length == 2) {
                    ahead = parseCount(parts[0]);
                    behind = parseCount(parts[1]);
                }
            } else if (entry.startsWith("1 ")) { //$NON-NLS-1$
                addPath(changes, workTree, entry, 9);
            } else if (entry.startsWith("2 ")) { //$NON-NLS-1$
                addPath(changes, workTree, entry, 10);
                // The original path of a rename follows as its own entry
                i++;
            } else if (entry.startsWith("u ")) { //$NON-NLS-1$
                Path path = addPath(changes, workTree, entry, 11);
                if (path != null) {
                    conflicts.add(path);
                }
            } else if (entry.startsWith("? ")) { //$NON-NLS-1$
                changes.add(workTree.resolve(entry.substring(2)).normalize());
            }
        }
        return new Status(branch, upstream, ahead, behind, Collections.unmodifiableSet(conflicts), List.copyOf(changes));
    }

    @Nullable
    private static Path addPath(@NotNull List<Path> changes, @NotNull Path workTree, @NotNull String entry, int fields) {
        String[] parts = entry.split(" ", fields); //$NON-NLS-1$
        if (parts.length < fields) {
            return null;
        }
        Path path = workTree.resolve(parts[fields - 1]).normalize();
        changes.add(path);
        return path;
    }

    private static int parseCount(@NotNull String value) {
        try {
            return Math.abs(Integer.parseInt(value.replace("+", ""))); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * {@code git pull --no-rebase --no-edit} in the root. Conflicts stop the merge; read them with {@link #status}.
     */
    @NotNull
    static Result pull(@NotNull Path root) throws IOException {
        return run(root, REMOTE_TIMEOUT_SECONDS, null, "pull", "--no-rebase", "--no-edit"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Files of a conflict that still contain conflict markers (the user has not finished editing them).
     */
    @NotNull
    static List<Path> findUnresolved(@NotNull Set<Path> conflicts) {
        List<Path> unresolved = new ArrayList<>();
        for (Path file : conflicts) {
            try {
                if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).lines().anyMatch(l -> l.startsWith(CONFLICT_MARKER))) {
                    unresolved.add(file);
                }
            } catch (IOException e) {
                // Not UTF-8 or unreadable: let git decide
                log.debug("Can't read " + file, e);
            }
        }
        return unresolved;
    }

    /**
     * Stages everything under the root, commits it with the message (if there is something to commit, or a merge to
     * conclude) and pushes. Each step's output is appended; the first failure stops.
     */
    @NotNull
    static Result commitAndPush(@NotNull Path root, @NotNull String message, boolean upstream) throws IOException {
        StringBuilder output = new StringBuilder();
        Result add = append(output, run(root, LOCAL_TIMEOUT_SECONDS, null, "add", "-A", "--", ".")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (!add.ok()) {
            return new Result(add.exitCode(), output.toString());
        }
        boolean merging = run(root, LOCAL_TIMEOUT_SECONDS, null, "rev-parse", "-q", "--verify", "MERGE_HEAD").ok(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // Exit code 1: staged changes under the root
        boolean staged = run(root, LOCAL_TIMEOUT_SECONDS, null, "diff", "--cached", "--quiet", "--", ".").exitCode() == 1; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        if (merging || staged) {
            // A merge can only be concluded as a whole; otherwise only the root's files are committed
            Result commit = append(output, merging
                ? run(root, LOCAL_TIMEOUT_SECONDS, message, "commit", "-F", "-") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : run(root, LOCAL_TIMEOUT_SECONDS, message, "commit", "-F", "-", "--", ".")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            if (!commit.ok()) {
                return new Result(commit.exitCode(), output.toString());
            }
        }
        Result push = append(output, upstream
            ? run(root, REMOTE_TIMEOUT_SECONDS, null, "push") //$NON-NLS-1$
            : run(root, REMOTE_TIMEOUT_SECONDS, null, "push", "-u", DEFAULT_REMOTE, "HEAD")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return new Result(push.exitCode(), output.toString());
    }

    @NotNull
    private static Result append(@NotNull StringBuilder output, @NotNull Result result) {
        if (!result.output().isBlank()) {
            output.append(result.output().strip()).append('\n');
        }
        return result;
    }

    /**
     * Runs git in a folder and waits for it.
     *
     * @param input text for the standard input (UTF-8), or null
     * @throws IOException if git can't be started
     */
    @NotNull
    private static Result run(@NotNull Path dir, long timeoutSeconds, @Nullable String input, @NotNull String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(GIT);
        // File names as they are (not octal escapes) in the human output
        command.add("-c"); //$NON-NLS-1$
        command.add("core.quotepath=off"); //$NON-NLS-1$
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir.toFile());
        builder.redirectErrorStream(true);
        // Never wait for a password typed into a terminal nobody sees; credential helpers still work
        builder.environment().put("GIT_TERMINAL_PROMPT", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        Process process = builder.start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                stream.transferTo(bytes);
            } catch (IOException e) {
                log.debug("Git output: " + e.getMessage());
            }
        }, "Query Library git output"); //$NON-NLS-1$
        reader.setDaemon(true);
        reader.start();
        try (OutputStream stdin = process.getOutputStream()) {
            if (input != null) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(1000);
                return new Result(-1, bytes.toString(StandardCharsets.UTF_8) + "\ngit " + String.join(" ", args) //$NON-NLS-1$ //$NON-NLS-2$
                    + ": timed out after " + timeoutSeconds + " s"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            reader.join(5000);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
        return new Result(process.exitValue(), bytes.toString(StandardCharsets.UTF_8));
    }
}
