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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Regression tests of the Git sharing of the Query Library ({@link QueryLibraryGit}): the machine status format, the
 * working copy lookup, unresolved conflict markers, and that commands run without a shell.
 *
 * 설계: Git은 사용자의 시스템 {@code git}을 프로세스 인수 배열로 실행한다(셸 없음). 그래서 커밋 메시지와 파일 이름의 셸 특수 문자가 명령으로
 * 해석되지 않고 그대로 Git에 가야 한다. 이것은 실제 {@code git}으로 임시 저장소에서 확인하고, {@code git}이 없으면 건너뛴다. 원격이 없으므로
 * push는 실패하지만 커밋은 남는다. 사용자 전역 설정(서명, 훅)이 시험을 바꾸지 않게 저장소 설정으로 끈다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "Query Library")
 */
public class QueryLibraryGitTest {

    private static final String SHELL_TEXT = "Update \"x\"; echo pwned > pwned.txt & del /q * | $(whoami) `id` %PATH% 'q'";

    @TempDir
    Path temp;

    @Test
    public void porcelainStatusIsParsedWithoutTheHumanText() throws Exception {
        Path workTree = temp.toAbsolutePath();
        String output = String.join("\0",
            "# branch.oid 0123456789abcdef",
            "# branch.head main",
            "# branch.upstream origin/main",
            "# branch.ab +2 -3",
            "1 .M N... 100644 100644 100644 1111111 2222222 queries/a b.sql",
            "2 R. N... 100644 100644 100644 1111111 2222222 R100 queries/new.sql",
            "queries/old.sql",
            "u UU N... 100644 100644 100644 100644 1111111 2222222 3333333 queries/conflict.sql",
            "? queries/untracked.sql",
            "");

        QueryLibraryGit.Status status = parseStatus(output, workTree);

        Assertions.assertEquals("main", status.branch());
        Assertions.assertTrue(status.upstream());
        Assertions.assertEquals(2, status.ahead());
        Assertions.assertEquals(3, status.behind());
        Assertions.assertEquals(Set.of(workTree.resolve("queries/conflict.sql")), status.conflicts());
        Assertions.assertEquals(List.of(
            workTree.resolve("queries/a b.sql"),
            workTree.resolve("queries/new.sql"),
            workTree.resolve("queries/conflict.sql"),
            workTree.resolve("queries/untracked.sql")), status.changes());

        QueryLibraryGit.Status detached = parseStatus("# branch.head (detached)\0", workTree);
        Assertions.assertNull(detached.branch());
        Assertions.assertFalse(detached.upstream());
    }

    @Test
    public void workTreeIsFoundAboveTheRootAndUnresolvedConflictsByTheirMarkers() throws Exception {
        Path repository = Files.createDirectories(temp.resolve("repo"));
        Files.createDirectories(repository.resolve(".git"));
        Path root = Files.createDirectories(repository.resolve("team").resolve("queries"));
        Assertions.assertEquals(repository.toAbsolutePath().normalize(), QueryLibraryGit.findWorkTree(root));

        Path resolved = Files.writeString(root.resolve("resolved.sql"), "SELECT 1\n");
        Path unresolved = Files.writeString(root.resolve("unresolved.sql"), "<<<<<<< HEAD\nSELECT 1\n=======\nSELECT 2\n>>>>>>> origin/main\n");
        Assertions.assertEquals(List.of(unresolved), QueryLibraryGit.findUnresolved(Set.of(resolved, unresolved)));
    }

    @Test
    public void commitMessageAndFileNamesAreNeverInterpretedByAShell() throws Exception {
        Assumptions.assumeTrue(QueryLibraryGit.checkAvailable(), "git is not installed");
        Path root = Files.createDirectories(temp.resolve("library"));
        Path hooks = Files.createDirectories(temp.resolve("no-hooks"));
        assertOk(git(root, "init", "-q"));
        assertOk(git(root, "config", "user.name", "Chromite Test"));
        assertOk(git(root, "config", "user.email", "test@example.invalid"));
        assertOk(git(root, "config", "commit.gpgsign", "false"));
        assertOk(git(root, "config", "core.hooksPath", hooks.toString()));
        Files.writeString(root.resolve("a & b; $(whoami).sql"), "SELECT 1\n", StandardCharsets.UTF_8);

        QueryLibraryGit.Result result = QueryLibraryGit.commitAndPush(root, SHELL_TEXT, false);

        // No remote: the push fails, the commit is made
        Assertions.assertFalse(result.ok());
        QueryLibraryGit.Result message = git(root, "log", "-1", "--format=%B");
        assertOk(message);
        Assertions.assertEquals(SHELL_TEXT, message.output().strip());
        QueryLibraryGit.Result files = git(root, "ls-files");
        Assertions.assertEquals("a & b; $(whoami).sql", files.output().strip());
        Assertions.assertFalse(Files.exists(root.resolve("pwned.txt")));
    }

    private static void assertOk(@NotNull QueryLibraryGit.Result result) {
        Assertions.assertTrue(result.ok(), result.output());
    }

    @NotNull
    private static QueryLibraryGit.Result git(@NotNull Path dir, @NotNull String... args) throws Exception {
        Method run = QueryLibraryGit.class.getDeclaredMethod("run", Path.class, long.class, String.class, String[].class);
        run.setAccessible(true);
        return (QueryLibraryGit.Result) invoke(run, dir, 30L, null, args);
    }

    @NotNull
    private static QueryLibraryGit.Status parseStatus(@NotNull String output, @NotNull Path workTree) throws Exception {
        Method parse = QueryLibraryGit.class.getDeclaredMethod("parseStatus", String.class, Path.class);
        parse.setAccessible(true);
        return (QueryLibraryGit.Status) invoke(parse, output, workTree);
    }

    private static Object invoke(@NotNull Method method, Object... args) throws Exception {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }
}
