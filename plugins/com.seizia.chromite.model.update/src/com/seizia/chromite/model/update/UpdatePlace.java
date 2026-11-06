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

import com.seizia.chromite.Log;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Where this copy of Chromite runs from, and what the updater may do with it.
 *
 * 설계: 업데이트는 배포된 빌드만 한다. Maven 결과물에서 돌리는 개발 빌드는 업데이트를 확인하지도 않는다.
 * <ul>
 *     <li>{@link Mode#OFF}: 확인하지 않는다. 지원하지 않는 OS·CPU, 런처 없이 실행(IDE 등, {@code eclipse.launcher} 속성 없음),
 *     Maven 결과물({@code .../target/products/...} 아래)인 개발 빌드.</li>
 *     <li>{@link Mode#NOTIFY}: 새 버전이 있다고만 알리고 내려받는 곳을 연다(Turbrix의 포터블 빌드와 같음).
 *     Windows: 설치 프로그램으로 설치하지 않은 복사본(릴리스 zip을 푼 폴더 등)이나 쓸 수 없는 설치 폴더(관리자 설치).
 *     macOS: Gatekeeper가 옮겨 실행 중인 앱({@code /AppTranslocation/}), 쓸 수 없는 앱이나 폴더.</li>
 *     <li>{@link Mode#APPLY}: 받아서 확인하고, 끝날 때 바꿔 넣는다.</li>
 * </ul>
 * "설치 프로그램으로 설치됨"(Windows)은 앱 및 기능의 제거 정보로 판단한다: HKCU·HKLM(64·32비트 보기)의
 * {@code Software\Microsoft\Windows\CurrentVersion\Uninstall\*} 중 {@code InstallLocation}이 이 폴더이거나
 * {@code UninstallString}의 실행 파일이 이 폴더에 있는 항목이 있어야 한다. 제거 정보의 키 이름은 보지 않는다
 * (설치 프로그램이 정한다, docs/chromite/distribution.md). {@code UninstallString}은 앱 및 기능 항목에 반드시 있다.
 * 개발 빌드의 시험 모드는 {@link UpdateTestMode}가 정하는 조건에서만 켠다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public record UpdatePlace(
    @NotNull Mode mode,
    @Nullable UpdateTarget target,
    @Nullable Path home,
    @Nullable Path launcher,
    @NotNull String reason,
    @Nullable UpdateTestMode test
) {

    private static final Log log = Log.getLog(UpdatePlace.class);

    private static final String PROP_LAUNCHER = "eclipse.launcher"; //$NON-NLS-1$
    private static final String UNINSTALL_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall"; //$NON-NLS-1$
    private static final String TRANSLOCATED = "/AppTranslocation/"; //$NON-NLS-1$

    public enum Mode {
        OFF,
        NOTIFY,
        APPLY
    }

    /**
     * @param home Windows: the installation folder; macOS: the app bundle
     */
    @NotNull
    public static UpdatePlace off(@NotNull String reason) {
        return new UpdatePlace(Mode.OFF, null, null, null, reason, null);
    }

    /**
     * Looks at this process: the operating system, the launcher it was started with, and the installation.
     * Can take seconds in the test mode (a signature check); call it off the UI thread.
     */
    @NotNull
    public static UpdatePlace detect(@NotNull String version) throws InterruptedException {
        String os = System.getProperty("os.name", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String arch = System.getProperty("os.arch", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String launcherPath = System.getProperty(PROP_LAUNCHER);
        if (!UpdateVersion.isValid(version)) {
            return off("version " + version); //$NON-NLS-1$
        }
        if (launcherPath == null || launcherPath.isBlank()) {
            return off("not started by the launcher"); //$NON-NLS-1$
        }
        Path launcher;
        try {
            launcher = Path.of(launcherPath).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return off("launcher path"); //$NON-NLS-1$
        }
        UpdateTestMode test = UpdateTestMode.fromSystemProperties();
        if (os.startsWith("Windows")) { //$NON-NLS-1$
            if (!"amd64".equals(arch)) { //$NON-NLS-1$
                return off("arch " + arch); //$NON-NLS-1$
            }
            Path dir = launcher.getParent();
            UpdateTarget target = new UpdateTarget(UpdateTarget.PLATFORM_WINDOWS, UpdateTarget.ARCH_X64, UpdateTarget.KIND_SETUP, version);
            boolean installed = isRegisteredInstall(dir);
            if (test != null) {
                if (installed || WindowsUpdateVerifier.isSignedBy(launcher, WindowsUpdateVerifier.PUBLISHER)) {
                    log.warn("Update test mode ignored: this is not a dev build"); //$NON-NLS-1$
                } else {
                    return new UpdatePlace(Mode.APPLY, target, dir, launcher, "test mode", test); //$NON-NLS-1$
                }
            }
            if (isBuildOutput(dir)) {
                return off("dev build"); //$NON-NLS-1$
            }
            if (!installed) {
                return new UpdatePlace(Mode.NOTIFY, target, dir, launcher, "not installed by the setup", null); //$NON-NLS-1$
            }
            if (!Files.isWritable(dir)) {
                return new UpdatePlace(Mode.NOTIFY, target, dir, launcher, "installation folder not writable", null); //$NON-NLS-1$
            }
            return new UpdatePlace(Mode.APPLY, target, dir, launcher, "installed", null); //$NON-NLS-1$
        }
        if (os.startsWith("Mac")) { //$NON-NLS-1$
            if (!"aarch64".equals(arch)) { //$NON-NLS-1$
                return off("arch " + arch); //$NON-NLS-1$
            }
            // <Name>.app/Contents/MacOS/<launcher>
            Path bundle = launcher.getParent() == null || launcher.getParent().getParent() == null
                ? null : launcher.getParent().getParent().getParent();
            if (bundle == null || bundle.getFileName() == null || !bundle.getFileName().toString().endsWith(".app")) { //$NON-NLS-1$
                return off("not an app bundle"); //$NON-NLS-1$
            }
            UpdateTarget target = new UpdateTarget(UpdateTarget.PLATFORM_MACOS, UpdateTarget.ARCH_ARM64, UpdateTarget.KIND_ZIP, version);
            if (test != null) {
                MacUpdateVerifier.Signing own = MacUpdateVerifier.signingOfApp(bundle);
                if (own == null || own.team() != null) {
                    log.warn("Update test mode ignored: this is not an ad hoc dev build"); //$NON-NLS-1$
                } else {
                    return new UpdatePlace(Mode.APPLY, target, bundle, launcher, "test mode", test); //$NON-NLS-1$
                }
            }
            if (isBuildOutput(bundle)) {
                return off("dev build"); //$NON-NLS-1$
            }
            if (bundle.toString().contains(TRANSLOCATED)) {
                return new UpdatePlace(Mode.NOTIFY, target, bundle, launcher, "translocated", null); //$NON-NLS-1$
            }
            if (!Files.isWritable(bundle) || bundle.getParent() == null || !Files.isWritable(bundle.getParent())) {
                return new UpdatePlace(Mode.NOTIFY, target, bundle, launcher, "app not writable", null); //$NON-NLS-1$
            }
            return new UpdatePlace(Mode.APPLY, target, bundle, launcher, "installed", null); //$NON-NLS-1$
        }
        return off("os " + os); //$NON-NLS-1$
    }

    /**
     * Whether {@code path} lies in a Maven/Tycho product output ({@code .../target/products/...}).
     */
    static boolean isBuildOutput(@NotNull Path path) {
        String previous = null;
        for (Path name : path) {
            String n = name.toString().toLowerCase(Locale.ROOT);
            if ("target".equals(previous) && "products".equals(n)) { //$NON-NLS-1$ //$NON-NLS-2$
                return true;
            }
            previous = n;
        }
        return false;
    }

    /**
     * Whether an Apps &amp; Features entry names {@code dir} as its installation folder.
     */
    static boolean isRegisteredInstall(@NotNull Path dir) {
        for (WinReg.HKEY root : new WinReg.HKEY[]{WinReg.HKEY_CURRENT_USER, WinReg.HKEY_LOCAL_MACHINE}) {
            for (int view : new int[]{WinNT.KEY_WOW64_64KEY, WinNT.KEY_WOW64_32KEY}) {
                try {
                    if (!Advapi32Util.registryKeyExists(root, UNINSTALL_KEY, view)) {
                        continue;
                    }
                    for (String key : Advapi32Util.registryGetKeys(root, UNINSTALL_KEY, view)) {
                        Map<String, Object> values = Advapi32Util.registryGetValues(root, UNINSTALL_KEY + "\\" + key, view); //$NON-NLS-1$
                        if (sameFolder(folderOfLocation(values.get("InstallLocation")), dir) //$NON-NLS-1$
                            || sameFolder(folderOfCommand(values.get("UninstallString")), dir)) { //$NON-NLS-1$
                            return true;
                        }
                    }
                } catch (RuntimeException | UnsatisfiedLinkError e) {
                    log.debug("Cannot read the uninstall entries: " + e.getMessage()); //$NON-NLS-1$
                }
            }
        }
        return false;
    }

    @Nullable
    static String folderOfLocation(@Nullable Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        return unquote(s.trim());
    }

    /**
     * The folder of the program a command line starts, quoted ({@code "<folder>/Uninstall.exe" /S}) or not
     * ({@code <folder>/Uninstall.exe}); Windows separators in practice.
     */
    @Nullable
    static String folderOfCommand(@Nullable Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        String command = s.trim();
        String program;
        if (command.startsWith("\"")) { //$NON-NLS-1$
            int end = command.indexOf('"', 1);
            program = end > 0 ? command.substring(1, end) : command.substring(1);
        } else {
            int exe = command.toLowerCase(Locale.ROOT).indexOf(".exe"); //$NON-NLS-1$
            program = exe > 0 ? command.substring(0, exe + 4) : command;
        }
        try {
            Path parent = Path.of(program).getParent();
            return parent == null ? null : parent.toString();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    static boolean sameFolder(@Nullable String folder, @NotNull Path dir) {
        if (folder == null) {
            return false;
        }
        try {
            String a = Path.of(folder).toAbsolutePath().normalize().toString();
            String b = dir.toAbsolutePath().normalize().toString();
            return trimSeparators(a).equalsIgnoreCase(trimSeparators(b));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    @NotNull
    private static String unquote(@NotNull String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private static String trimSeparators(@NotNull String s) {
        String r = s;
        while (r.length() > 3 && (r.endsWith("\\") || r.endsWith("/"))) { //$NON-NLS-1$ //$NON-NLS-2$
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
