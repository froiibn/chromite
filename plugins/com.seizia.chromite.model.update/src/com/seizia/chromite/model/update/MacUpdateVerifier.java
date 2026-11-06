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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The checks of a downloaded macOS update zip ({@code Chromite-<v>-arm64.zip}) before its app replaces this one.
 *
 * 설계: Turbrix 데스크톱 {@code macUpdate.cjs}의 확인을 옮겼다(docs/chromite/auto-update.md "macOS 확인").
 * <ol>
 *     <li>크기와 SHA-256이 목록과 같다.</li>
 *     <li>zip 안의 이름이 모두 실행 중인 앱 묶음의 이름({@code <Name>.app/})으로 시작하고 {@code ..}, 역슬래시, NUL이 없다
 *     ({@code zipinfo -1}로 풀기 전에 읽는다).</li>
 *     <li>{@code ditto -x -k}로 zip 옆의 {@code staging/}에 한 번 푼다.</li>
 *     <li>{@code codesign --verify --deep --strict}가 통과한다.</li>
 *     <li>번들 ID와 Team ID가 실행 중인 앱의 것({@code codesign -dv})과 같다. 실행 중인 앱에 Team ID가 없으면(서명 없는 빌드) 받지 않는다.</li>
 *     <li>Gatekeeper({@code spctl -a -t exec -vv})가 "Notarized Developer ID"로 받아들인다.</li>
 *     <li>앱의 {@code CFBundleShortVersionString}이 목록의 버전과 같다.</li>
 * </ol>
 * 다시 확인할 때(끝낼 때, 다음 시작)는 zip을 다시 풀지 않고 풀어 둔 앱을 4~7로 확인한다(봉인이 바뀐 것을 드러낸다).
 * 확인에 실패하면 {@code staging/}을 지운다.
 * 시험 모드({@code adhoc}, 개발 빌드만, {@link UpdateTestMode})는 Team ID가 둘 다 없는 ad hoc 봉인을 받고 Gatekeeper를 묻지 않는다.
 * Windows에서는 실행해 볼 수 없다. 출력 해석과 이름 검사는 순수 함수로 두었다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public final class MacUpdateVerifier implements UpdateVerifier {

    static final String CODESIGN = "/usr/bin/codesign"; //$NON-NLS-1$
    static final String SPCTL = "/usr/sbin/spctl"; //$NON-NLS-1$
    static final String DITTO = "/usr/bin/ditto"; //$NON-NLS-1$
    static final String ZIPINFO = "/usr/bin/zipinfo"; //$NON-NLS-1$
    static final String PLUTIL = "/usr/bin/plutil"; //$NON-NLS-1$
    static final String STAGING = "staging"; //$NON-NLS-1$
    private static final Duration CHECK_TIME = Duration.ofSeconds(120);
    private static final Map<String, String> TOOL_ENV = Map.of("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final Pattern TEAM = Pattern.compile("^[A-Z0-9]{10}$"); //$NON-NLS-1$
    private static final Pattern IDENTIFIER_LINE = Pattern.compile("^Identifier=(.+)$", Pattern.MULTILINE); //$NON-NLS-1$
    private static final Pattern TEAM_LINE = Pattern.compile("^TeamIdentifier=(.+)$", Pattern.MULTILINE); //$NON-NLS-1$

    /**
     * The bundle id and Team ID of a sealed app; {@code team} null for an ad hoc seal ("not set").
     */
    record Signing(@NotNull String identifier, @Nullable String team) {
    }

    private final Path runningApp;
    private final boolean adhoc;
    @Nullable
    private Signing own;

    /**
     * @param runningApp the app bundle this process runs from
     * @param adhoc      dev test mode only: accept ad hoc seals without Gatekeeper
     */
    public MacUpdateVerifier(@NotNull Path runningApp, boolean adhoc) {
        this.runningApp = runningApp;
        this.adhoc = adhoc;
    }

    @NotNull
    @Override
    public Verdict verify(@NotNull Path file, @NotNull UpdateEntry entry) throws InterruptedException {
        UpdateStep bytes = UpdateVerifier.checkBytes(file, entry);
        if (bytes != null) {
            return Verdict.fail(bytes);
        }
        String bundleName = runningApp.getFileName().toString();
        UpdateProcess.Result listed = UpdateProcess.run(List.of(ZIPINFO, "-1", file.toString()), TOOL_ENV, CHECK_TIME); //$NON-NLS-1$
        if (listed.code() != 0 || !namesInside(Arrays.asList(listed.output().split("\n")), bundleName)) { //$NON-NLS-1$
            return Verdict.fail(UpdateStep.EXTRACT);
        }
        Path staging = file.resolveSibling(STAGING);
        Path app = staging.resolve(bundleName);
        if (!Files.isDirectory(app, LinkOption.NOFOLLOW_LINKS)) {
            UpdateFiles.deleteTree(staging);
            try {
                Files.createDirectories(staging);
            } catch (IOException e) {
                return Verdict.fail(UpdateStep.EXTRACT);
            }
            UpdateProcess.Result out = UpdateProcess.run(List.of(DITTO, "-x", "-k", file.toString(), staging.toString()), //$NON-NLS-1$ //$NON-NLS-2$
                TOOL_ENV, CHECK_TIME);
            if (out.code() != 0) {
                UpdateFiles.deleteTree(staging);
                return Verdict.fail(UpdateStep.EXTRACT);
            }
        }
        UpdateStep step = verifyBundle(app, entry.version());
        if (step != null) {
            UpdateFiles.deleteTree(staging);
            return Verdict.fail(step);
        }
        return new Verdict(null, app);
    }

    /**
     * Checks 4-7 of an app bundle (a new one, or one kept for a rollback) against the version it must be.
     */
    @Nullable
    UpdateStep verifyBundle(@NotNull Path app, @NotNull String version) throws InterruptedException {
        if (!Files.isDirectory(app, LinkOption.NOFOLLOW_LINKS)) {
            return UpdateStep.EXTRACT;
        }
        UpdateProcess.Result sealed = UpdateProcess.run(
            List.of(CODESIGN, "--verify", "--deep", "--strict", app.toString()), TOOL_ENV, CHECK_TIME); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (sealed.code() != 0) {
            return UpdateStep.SIGNATURE;
        }
        Signing mine = ownSigning();
        Signing theirs = signingOfApp(app);
        if (mine == null || theirs == null) {
            return UpdateStep.CHECKER;
        }
        UpdateStep publisher = comparePublisher(mine, theirs, adhoc);
        if (publisher != null) {
            return publisher;
        }
        if (!adhoc) {
            UpdateProcess.Result gate = UpdateProcess.run(
                List.of(SPCTL, "-a", "-t", "exec", "-vv", app.toString()), TOOL_ENV, CHECK_TIME); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            if (gate.code() != 0 || !isNotarized(gate.output())) {
                return UpdateStep.NOTARIZATION;
            }
        }
        UpdateProcess.Result shown = UpdateProcess.run(List.of(PLUTIL, "-extract", "CFBundleShortVersionString", "raw", "-o", "-", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            app.resolve("Contents").resolve("Info.plist").toString()), TOOL_ENV, CHECK_TIME); //$NON-NLS-1$ //$NON-NLS-2$
        if (shown.code() != 0 || !version.equals(shown.output().trim())) {
            return UpdateStep.VERSION;
        }
        return null;
    }

    @Nullable
    private synchronized Signing ownSigning() throws InterruptedException {
        if (own == null) {
            own = signingOfApp(runningApp);
        }
        return own;
    }

    @Nullable
    static Signing signingOfApp(@NotNull Path app) throws InterruptedException {
        UpdateProcess.Result said = UpdateProcess.run(List.of(CODESIGN, "-dv", "--verbose=2", app.toString()), TOOL_ENV, CHECK_TIME); //$NON-NLS-1$ //$NON-NLS-2$
        return said.code() == 0 ? parseSigning(said.output()) : null;
    }

    /**
     * Whether every name of the zip lies inside {@code <bundleName>/}: no absolute path, no {@code ..}, no backslash, no NUL.
     */
    static boolean namesInside(@NotNull List<String> names, @NotNull String bundleName) {
        String top = bundleName + "/"; //$NON-NLS-1$
        boolean any = false;
        for (String name : names) {
            if (name.isEmpty()) {
                continue;
            }
            if (!name.startsWith(top) || name.contains("\\") || name.indexOf('\0') >= 0 //$NON-NLS-1$
                || Arrays.asList(name.split("/")).contains("..")) { //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * What {@code codesign -dv --verbose=2} prints (on its standard error): {@code Identifier=} and {@code TeamIdentifier=}.
     */
    @Nullable
    static Signing parseSigning(@NotNull String text) {
        Matcher identifier = IDENTIFIER_LINE.matcher(text);
        if (!identifier.find()) {
            return null;
        }
        Matcher team = TEAM_LINE.matcher(text);
        String teamId = team.find() ? team.group(1).trim() : null;
        return new Signing(identifier.group(1).trim(), teamId != null && TEAM.matcher(teamId).matches() ? teamId : null);
    }

    /**
     * The same bundle id, and the same Team ID: a real one on both sides, or (ad hoc test mode) none on both.
     */
    @Nullable
    static UpdateStep comparePublisher(@NotNull Signing mine, @NotNull Signing theirs, boolean adhoc) {
        if (!mine.identifier().equals(theirs.identifier())) {
            return UpdateStep.PUBLISHER;
        }
        if (adhoc) {
            return java.util.Objects.equals(mine.team(), theirs.team()) ? null : UpdateStep.PUBLISHER;
        }
        return mine.team() != null && mine.team().equals(theirs.team()) ? null : UpdateStep.PUBLISHER;
    }

    static boolean isNotarized(@NotNull String spctlOutput) {
        return spctlOutput.contains("accepted") && spctlOutput.contains("source=Notarized Developer ID"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
