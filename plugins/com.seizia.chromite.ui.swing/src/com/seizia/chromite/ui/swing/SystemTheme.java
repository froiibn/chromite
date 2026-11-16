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

import com.seizia.chromite.Log;
import com.seizia.chromite.utils.RuntimeUtils;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.W32Errors;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The light/dark setting of the operating system, and its changes while the app runs.
 *
 * 설계: 테마의 기본값 "시스템 설정 따르기"({@link ChromiteTheme.Choice#SYSTEM})가 읽는 값이다(앱 변경 #38).
 * FlatLaf와 JDK에는 운영체제의 밝은/어두운 설정을 알려 주는 API가 없어 운영체제 값을 직접 읽는다.
 * <ul>
 *     <li>Windows: 사용자 레지스트리 {@code HKCU\...\Themes\Personalize}의 {@code AppsUseLightTheme}(0이면 어둡게).
 *     설정 앱의 "앱 모드"가 이 값이다. 바뀜은 데몬 스레드가 {@code RegNotifyChangeKeyValue}로 기다린다(폴링 없음, 바뀌면 바로 따라감).</li>
 *     <li>macOS: 전역 기본값 {@code AppleInterfaceStyle}({@code Dark}이면 어둡게, 밝으면 값이 없다; "자동"일 때도 지금 모습을 담는다).
 *     {@code /usr/bin/defaults}로 읽는다. 바뀜 알림을 받으려면 AppKit 관찰자가 필요해, 대신 다른 앱에서 이 앱으로 돌아올 때
 *     (창 활성화, 직전 창이 이 앱의 것이 아님) 다시 읽는다. 시스템 설정에서 모습을 바꾸고 돌아오면 그때 따라간다.</li>
 *     <li>그 밖(Linux 등)과 읽기 실패: 밝게. 바뀜은 따라가지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: 테마 기본값을 운영체제 설정으로 (앱 변경 #38, docs/chromite/swing-options.md "테마")
 */
final class SystemTheme {

    private static final Log log = Log.getLog(SystemTheme.class);

    private static final String WINDOWS_PERSONALIZE_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"; //$NON-NLS-1$
    private static final String WINDOWS_APPS_USE_LIGHT_THEME = "AppsUseLightTheme"; //$NON-NLS-1$
    private static final String MAC_DEFAULTS = "/usr/bin/defaults"; //$NON-NLS-1$
    private static final String MAC_INTERFACE_STYLE = "AppleInterfaceStyle"; //$NON-NLS-1$
    private static final String MAC_DARK = "Dark"; //$NON-NLS-1$
    /** Longest wait for {@code defaults}; it answers in milliseconds */
    private static final int MAC_READ_TIMEOUT_SECONDS = 3;

    private static final AtomicBoolean watching = new AtomicBoolean();

    private SystemTheme() {
    }

    /**
     * Whether the operating system is set to dark; false where it can't be read. Any thread; on macOS it runs a process.
     */
    static boolean isDark() {
        try {
            if (RuntimeUtils.isWindows()) {
                return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, WINDOWS_PERSONALIZE_KEY, WINDOWS_APPS_USE_LIGHT_THEME)
                    && Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, WINDOWS_PERSONALIZE_KEY, WINDOWS_APPS_USE_LIGHT_THEME) == 0;
            }
            if (RuntimeUtils.isMacOS()) {
                return isMacDark();
            }
        } catch (Exception | LinkageError e) {
            log.debug("Can't read the system light/dark setting: " + e.getMessage()); //$NON-NLS-1$
        }
        return false;
    }

    private static boolean isMacDark() throws IOException, InterruptedException {
        Process process = new ProcessBuilder(MAC_DEFAULTS, "read", "-g", MAC_INTERFACE_STYLE) //$NON-NLS-1$ //$NON-NLS-2$
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        String value;
        try (InputStream out = process.getInputStream()) {
            value = new String(out.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        if (!process.waitFor(MAC_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy();
            return false;
        }
        // Exit code 1 and no output when the key does not exist (light)
        return process.exitValue() == 0 && MAC_DARK.equalsIgnoreCase(value);
    }

    /**
     * Calls the listener on the EDT with {@link #isDark()} when the setting may have changed (it may be unchanged).
     * Only the first call starts watching. On the EDT.
     */
    static void watch(@NotNull Consumer<Boolean> listener) {
        if (!watching.compareAndSet(false, true)) {
            return;
        }
        if (RuntimeUtils.isWindows()) {
            Thread thread = new Thread(() -> watchWindows(listener), "Chromite system theme watcher"); //$NON-NLS-1$
            thread.setDaemon(true);
            thread.start();
        } else if (RuntimeUtils.isMacOS()) {
            AtomicBoolean reading = new AtomicBoolean();
            Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                // Back from another application: no window of this one was active before
                if (event.getID() == WindowEvent.WINDOW_ACTIVATED && ((WindowEvent) event).getOppositeWindow() == null
                    && reading.compareAndSet(false, true)) {
                    Thread thread = new Thread(() -> {
                        try {
                            boolean dark = isDark();
                            SwingUtilities.invokeLater(() -> listener.accept(dark));
                        } finally {
                            reading.set(false);
                        }
                    }, "Chromite system theme check"); //$NON-NLS-1$
                    thread.setDaemon(true);
                    thread.start();
                }
            }, AWTEvent.WINDOW_EVENT_MASK);
        }
    }

    /**
     * Waits for changes of the Personalize key, for as long as the process runs (daemon thread).
     */
    private static void watchWindows(@NotNull Consumer<Boolean> listener) {
        try {
            WinReg.HKEYByReference key = new WinReg.HKEYByReference();
            int result = Advapi32.INSTANCE.RegOpenKeyEx(WinReg.HKEY_CURRENT_USER, WINDOWS_PERSONALIZE_KEY, 0, WinNT.KEY_NOTIFY, key);
            if (result != W32Errors.ERROR_SUCCESS) {
                log.debug("Can't watch the system light/dark setting: error " + result); //$NON-NLS-1$
                return;
            }
            try {
                while (true) {
                    // Synchronous: returns once a value of the key was set (on this thread, which lives as long as the process)
                    result = Advapi32.INSTANCE.RegNotifyChangeKeyValue(key.getValue(), false, WinNT.REG_NOTIFY_CHANGE_LAST_SET, null, false);
                    if (result != W32Errors.ERROR_SUCCESS) {
                        log.debug("Stopped watching the system light/dark setting: error " + result); //$NON-NLS-1$
                        return;
                    }
                    boolean dark = isDark();
                    SwingUtilities.invokeLater(() -> listener.accept(dark));
                }
            } finally {
                Advapi32.INSTANCE.RegCloseKey(key.getValue());
            }
        } catch (Exception | LinkageError e) {
            log.debug("Can't watch the system light/dark setting: " + e.getMessage()); //$NON-NLS-1$
        }
    }
}
