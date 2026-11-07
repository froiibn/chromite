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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The macOS application menu (About, Options, Hide, Hide Others, Show All, Quit) and the quit request when AWT does not
 * own the application.
 *
 * 설계: Eclipse 런처는 JVM을 올리기 전에 평범한 {@code NSApplication}을 만든다(Apple event 처리기 설치). 그래서 AWT는 자기
 * {@code NSApplicationAWT}를 만들지 못하고 내장(embedded) 상태로 시작해 앱 메뉴를 만들지 않는다(빈 앱 메뉴, Cmd+Q 없음).
 * 앱 위임자도 두지 않아 끝내기 Apple event·Dock의 끝내기·Cmd+Q({@code terminate:})가 Java 끝내기 처리기를 거치지 않고 바로
 * 프로세스를 끝낸다. 그래서 앱에 위임자가 없으면 이 클래스가 런타임에 만든 Objective-C 객체를 위임자로 두고,
 * {@code applicationShouldTerminate:}에서 {@code quit}(창 닫기 경로: 저장·트랜잭션 질문)을 EDT에 맡긴 뒤 끝내기를 취소한다
 * ({@code NSTerminateCancel}). 앱은 사용자가 끝내기로 하면 자기 경로로 끝난다. 이는 Java 끝내기 처리기와 같은 동작이다.
 * 정보·옵션도 같은 객체가 받아 Swing 쪽 실행기를 EDT에서 부른다(AWT 위임자의 내부 선택자에 기대지 않는다). 메뉴는 AppKit 스레드에서만 다룰 수 있어 main dispatch queue로 보낸다. AWT는 창이 활성화될 때 메인 메뉴의
 * 0번(앱 메뉴)을 남기고 나머지를 지우므로({@code CMenuBar clearMenuBar}) 이 메뉴는 그대로 남는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: macOS 시작 교착 수정과 함께 (docs/chromite/platform-startup.md "macOS 앱 메뉴")
 */
final class MacOSAppMenu {

    private static final Log log = Log.getLog(MacOSAppMenu.class);

    private static final String TARGET_CLASS = "ChromiteAppMenuTarget";
    private static final String ABOUT_ACTION = "chromiteAbout:";
    private static final String OPTIONS_ACTION = "chromiteOptions:";
    private static final String SHOULD_TERMINATE = "applicationShouldTerminate:";
    /** NSApplicationTerminateReply NSTerminateCancel */
    private static final long TERMINATE_CANCEL = 0;
    /** NSEventModifierFlagCommand, NSEventModifierFlagOption */
    private static final long COMMAND = 1L << 20;
    private static final long OPTION = 1L << 19;

    /** Objective-C method "v@:@": {@code - (void)action:(id)sender} */
    public interface ActionCallback extends Callback {
        void invoke(Pointer self, Pointer selector, Pointer sender);
    }

    /** Objective-C method "Q@:@": {@code - (NSApplicationTerminateReply)applicationShouldTerminate:(NSApplication *)sender} */
    public interface TerminateCallback extends Callback {
        long invoke(Pointer self, Pointer selector, Pointer sender);
    }

    /** dispatch_function_t */
    public interface DispatchCallback extends Callback {
        void invoke(Pointer context);
    }

    /** AppKit keeps only native pointers to the callbacks: they must stay reachable for the life of the process */
    private static final List<Callback> CALLBACKS = new ArrayList<>();

    private static NativeLibrary process;
    private static Function msgSend;

    private MacOSAppMenu() {
    }

    /**
     * Sets the application menu and, when the application has no delegate, the quit request, on the AppKit thread
     * (asynchronously). {@code about}, {@code options} and {@code quit} run on the EDT.
     */
    static synchronized void install(
        @NotNull String productName,
        @NotNull Runnable about,
        @NotNull Runnable options,
        @NotNull Runnable quit
    ) {
        process = NativeLibrary.getProcess();
        msgSend = process.getFunction("objc_msgSend");
        DispatchCallback build = context -> {
            try {
                setUp(productName, about, options, quit);
            } catch (Throwable e) {
                log.error("Cannot set the macOS application menu", e);
            }
        };
        CALLBACKS.add(build);
        // dispatch_get_main_queue() is the address of _dispatch_main_q
        process.getFunction("dispatch_async_f").invokeVoid(
            new Object[] {process.getGlobalVariableAddress("_dispatch_main_q"), null, build});
    }

    private static void setUp(
        @NotNull String productName,
        @NotNull Runnable about,
        @NotNull Runnable options,
        @NotNull Runnable quit
    ) {
        Pointer target = send(send(targetClass(about, options, quit), "alloc"), "init");
        Pointer application = send(objcClass("NSApplication"), "sharedApplication");
        Pointer delegate = send(application, "delegate");
        log.debug("macOS application " + className(application) + ", delegate " + (delegate == null ? "none" : className(delegate)));
        if (delegate == null) {
            // NSApplication keeps a weak reference: the target is never released
            send(application, "setDelegate:", target);
        }

        Pointer appMenu = send(send(objcClass("NSMenu"), "alloc"), "initWithTitle:", nsString(productName));
        addItem(appMenu, NLS.bind(CoreMessages.main_about, productName), ABOUT_ACTION, "", 0, target);
        addSeparator(appMenu);
        addItem(appMenu, CoreMessages.main_options, OPTIONS_ACTION, ",", COMMAND, target);
        addSeparator(appMenu);
        // No target: the actions go up the responder chain to NSApp
        addItem(appMenu, NLS.bind(CoreMessages.mac_menu_hide, productName), "hide:", "h", COMMAND, null);
        addItem(appMenu, CoreMessages.mac_menu_hide_others, "hideOtherApplications:", "h", COMMAND | OPTION, null);
        addItem(appMenu, CoreMessages.mac_menu_show_all, "unhideAllApplications:", "", 0, null);
        addSeparator(appMenu);
        // terminate: asks the application delegate (applicationShouldTerminate:)
        addItem(appMenu, NLS.bind(CoreMessages.mac_menu_quit, productName), "terminate:", "q", COMMAND, null);

        Pointer appItem = send(send(objcClass("NSMenuItem"), "alloc"), "init");
        send(appItem, "setSubmenu:", appMenu);
        Pointer mainMenu = send(send(objcClass("NSMenu"), "alloc"), "initWithTitle:", nsString(""));
        send(mainMenu, "addItem:", appItem);
        send(application, "setMainMenu:", mainMenu);
    }

    /**
     * An NSObject subclass whose two actions run {@code about} and {@code options} on the EDT and whose
     * {@code applicationShouldTerminate:} runs {@code quit} on the EDT and cancels the termination.
     */
    @NotNull
    private static Pointer targetClass(@NotNull Runnable about, @NotNull Runnable options, @NotNull Runnable quit) {
        Pointer existing = objcClass(TARGET_CLASS);
        if (existing != null) {
            return existing;
        }
        Pointer cls = process.getFunction("objc_allocateClassPair")
            .invokePointer(new Object[] {objcClass("NSObject"), TARGET_CLASS, 0L});
        addMethod(cls, ABOUT_ACTION, (self, selector, sender) -> SwingUtilities.invokeLater(about));
        addMethod(cls, OPTIONS_ACTION, (self, selector, sender) -> SwingUtilities.invokeLater(options));
        TerminateCallback shouldTerminate = (self, selector, sender) -> {
            SwingUtilities.invokeLater(quit);
            return TERMINATE_CANCEL;
        };
        CALLBACKS.add(shouldTerminate);
        process.getFunction("class_addMethod").invokeInt(new Object[] {cls, selector(SHOULD_TERMINATE), shouldTerminate, "Q@:@"});
        process.getFunction("objc_registerClassPair").invokeVoid(new Object[] {cls});
        return cls;
    }

    private static void addMethod(@NotNull Pointer cls, @NotNull String selector, @NotNull ActionCallback action) {
        CALLBACKS.add(action);
        process.getFunction("class_addMethod").invokeInt(new Object[] {cls, selector(selector), action, "v@:@"});
    }

    private static void addItem(
        @NotNull Pointer menu,
        @NotNull String title,
        @NotNull String action,
        @NotNull String keyEquivalent,
        long modifiers,
        @Nullable Pointer target
    ) {
        Pointer item = send(send(objcClass("NSMenuItem"), "alloc"), "initWithTitle:action:keyEquivalent:",
            nsString(title), selector(action), nsString(keyEquivalent));
        if (!keyEquivalent.isEmpty()) {
            send(item, "setKeyEquivalentModifierMask:", modifiers);
        }
        if (target != null) {
            send(item, "setTarget:", target);
        }
        send(menu, "addItem:", item);
    }

    private static void addSeparator(@NotNull Pointer menu) {
        send(menu, "addItem:", send(objcClass("NSMenuItem"), "separatorItem"));
    }

    @NotNull
    private static String className(@NotNull Pointer object) {
        return process.getFunction("object_getClassName").invokeString(new Object[] {object}, false);
    }

    @Nullable
    private static Pointer objcClass(@NotNull String name) {
        return process.getFunction("objc_getClass").invokePointer(new Object[] {name});
    }

    @NotNull
    private static Pointer selector(@NotNull String name) {
        return process.getFunction("sel_registerName").invokePointer(new Object[] {name});
    }

    /** NSString from UTF-8 (the titles are not ASCII in every language) */
    @NotNull
    private static Pointer nsString(@NotNull String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Memory memory = new Memory(bytes.length + 1L);
        memory.write(0, bytes, 0, bytes.length);
        memory.setByte(bytes.length, (byte) 0);
        return send(objcClass("NSString"), "stringWithUTF8String:", memory);
    }

    private static Pointer send(@NotNull Pointer receiver, @NotNull String selector, @NotNull Object... args) {
        Object[] all = new Object[args.length + 2];
        all[0] = receiver;
        all[1] = selector(selector);
        System.arraycopy(args, 0, all, 2, args.length);
        return msgSend.invokePointer(all);
    }
}
