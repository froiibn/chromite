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
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import org.jkiss.code.NotNull;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.launch.Framework;

import java.util.concurrent.Callable;

/**
 * Runs the Swing application off the macOS main thread when the Eclipse launcher started the JVM on it.
 *
 * 설계: macOS에서 Eclipse 런처는 SWT를 위해 JVM을 프로세스의 첫(AppKit 메인) 스레드에서 시작하고
 * {@code JAVA_STARTED_ON_FIRST_THREAD_<pid>}를 둔다. AWT는 초기화와 창 작업을 이 스레드의 실행 루프에 맡기고 기다리는데,
 * Equinox가 이 스레드에서 부른 {@code start}가 Swing 작업을 기다리면 서로 기다려 창이 뜨지 않는다(v0.1.1, docs/chromite/platform-startup.md).
 * 그래서 JDK의 {@code java} 런처(libjli {@code MacOSXStartup}/{@code ParkEventLoop})와 같은 배치로 바꾼다:
 * <ul>
 *     <li>앱 본문은 새 스레드 {@value #THREAD_NAME}에서 돈다.</li>
 *     <li>첫 스레드는 CoreFoundation 실행 루프({@code CFRunLoopRunInMode}, 기본 모드)를 돌며 기다린다. AWT가 시작 블록을
 *     맡기면 그 안에서 앱의 AppKit 이벤트 루프를 돈다(돌아오지 않는다).</li>
 *     <li>{@code JAVA_STARTED_ON_FIRST_THREAD_<pid>}를 AWT가 읽기 전에 지운다. 남아 있으면 AWT가 SWT용 내장 모드로 시작해
 *     AppKit 이벤트 루프를 돌리지 않는다(창이 입력을 받지 못한다).</li>
 *     <li>런처가 JVM 전에 평범한 {@code NSApplication}을 만들어 두므로 AWT는 자기 {@code NSApplicationAWT}를 만들지 못하고
 *     그 앱을 돌린다. 이때 AWT는 앱 메뉴도 앱 위임자도 두지 않는다(JBR 25에서 {@code AWT_OVERRIDE_NSDELEGATE}를 두어도
 *     위임자가 없었다). 앱 메뉴와 끝내기 요청은 {@link MacOSAppMenu}가 맡는다.</li>
 *     <li>첫 스레드는 Equinox로 돌아가지 못하므로, 본문이 끝나면 일하던 스레드가 Equinox가 하던 마무리(OSGi 프레임워크
 *     정지)를 하고 결과 코드로 {@link System#exit}한다. JDK 런처도 같은 방식(둘째 스레드에서 {@code exit})으로 끝난다.
 *     {@code EclipseStarter.shutdown}은 쓰지 않는다: 실행 중인 앱(돌아오지 않는 첫 스레드)을 1분 기다린다.</li>
 * </ul>
 * 다른 방법을 쓰지 않은 이유: 런처의 {@code --launcher.secondThread}는 macOS 15에서 런처가 AppKit 단정으로 죽고,
 * 런처는 macOS에서 언제나 JVM을 같은 프로세스에 올린다(별도 {@code java} 프로세스로 띄우는 선택이 없다).
 * Windows와 다른 경우(첫 스레드가 아님)에는 아무것도 바꾸지 않는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: macOS 시작 교착 수정 (docs/chromite/platform-startup.md "macOS 메인 스레드")
 */
final class MacOSMainThread {

    private static final Log log = Log.getLog(MacOSMainThread.class);

    static final String THREAD_NAME = "Chromite main";

    /** Set by the Eclipse launcher (and {@code java -XstartOnFirstThread}); read by AWT when it starts. */
    private static final String FIRST_THREAD_VARIABLE = "JAVA_STARTED_ON_FIRST_THREAD_";
    /** CFRunLoopRunInMode result: the mode has no sources or timers yet (nothing to wait for). */
    private static final int RUN_LOOP_FINISHED = 1;
    private static final double RUN_LOOP_SECONDS = 60.0;
    private static final long IDLE_MILLIS = 20;
    /** Equinox's exit code for an application that failed with an exception (see the log). */
    private static final int EXIT_ERROR = 13;
    private static final long FRAMEWORK_STOP_MILLIS = 60_000;

    private static volatile boolean active;

    private MacOSMainThread() {
    }

    /**
     * True on macOS when the calling thread is the process's first thread, the one AppKit runs on.
     */
    static boolean isAppKitMainThread() {
        if (!RuntimeUtils.isMacOS()) {
            return false;
        }
        return libc().getFunction("pthread_main_np").invokeInt(new Object[0]) == 1;
    }

    /**
     * True when the application runs through {@link #runApplication} (AWT does not own the NSApplication then).
     */
    static boolean isActive() {
        return active;
    }

    /**
     * Runs {@code application} on a new thread and serves the AppKit run loop on this, the first thread, until
     * the process ends. When the application returns, the OSGi framework is stopped and the process exits with
     * its result (an {@link Integer} application result is the exit code, as Equinox's {@code eclipse.exitcode}).
     *
     * @return never
     */
    static Object runApplication(@NotNull Callable<Object> application) {
        // Before AWT starts: AWT reads (and removes) this variable once, when its toolkit loads
        libc().getFunction("unsetenv").invokeInt(new Object[] {FIRST_THREAD_VARIABLE + ProcessHandle.current().pid()});
        active = true;
        Thread thread = new Thread(() -> exit(run(application)), THREAD_NAME);
        thread.setContextClassLoader(Thread.currentThread().getContextClassLoader());
        thread.start();
        serveRunLoop();
        throw new IllegalStateException("The AppKit run loop returned");
    }

    private static int run(@NotNull Callable<Object> application) {
        try {
            return application.call() instanceof Integer code ? code : 0;
        } catch (Throwable e) {
            log.error("Application error", e);
            return EXIT_ERROR;
        }
    }

    /**
     * What Equinox does on the first thread after the application returned (EclipseStarter: the framework stops,
     * bundles save their state), then the exit with the application's code.
     */
    private static void exit(int code) {
        try {
            Bundle bundle = FrameworkUtil.getBundle(MacOSMainThread.class);
            BundleContext context = bundle == null ? null : bundle.getBundleContext();
            Bundle system = context == null ? null : context.getBundle(Constants.SYSTEM_BUNDLE_ID);
            if (system instanceof Framework framework) {
                framework.stop();
                framework.waitForStop(FRAMEWORK_STOP_MILLIS);
            }
        } catch (Throwable e) {
            // The bundles of this class may be gone already: no logging through them
            e.printStackTrace(System.err);
        } finally {
            System.exit(code);
        }
    }

    /**
     * The first thread waits in the CoreFoundation run loop, as the JDK launcher's {@code ParkEventLoop}: AWT queues
     * its start there (default run loop mode) and then runs the AppKit event loop inside it, which does not return.
     */
    private static void serveRunLoop() {
        NativeLibrary coreFoundation = NativeLibrary.getInstance("CoreFoundation");
        Pointer defaultMode = coreFoundation.getGlobalVariableAddress("kCFRunLoopDefaultMode").getPointer(0);
        Function runInMode = coreFoundation.getFunction("CFRunLoopRunInMode");
        while (true) {
            int result = runInMode.invokeInt(new Object[] {defaultMode, RUN_LOOP_SECONDS, (byte) 0});
            if (result == RUN_LOOP_FINISHED) {
                // Nothing is queued yet: CFRunLoopRunInMode returns at once without a source to wait for
                try {
                    Thread.sleep(IDLE_MILLIS);
                } catch (InterruptedException e) {
                    // Keep serving: this thread ends only with the process
                }
            }
        }
    }

    @NotNull
    private static NativeLibrary libc() {
        return NativeLibrary.getInstance(Platform.C_LIBRARY_NAME);
    }
}
