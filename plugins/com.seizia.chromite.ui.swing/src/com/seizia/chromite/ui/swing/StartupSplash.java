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

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.seizia.chromite.Log;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The startup splash once Java runs: the launcher's splash image with the version and a status line, until the first
 * other window (the main window, or a startup dialog) opens.
 *
 * 설계: 시작 화면은 두 단계다(docs/chromite/branding.md "시작 화면").
 * <ol>
 *     <li>네이티브 런처가 JVM보다 먼저 {@code com.seizia.chromite.splash}의 {@code splash.png}를 띄운다(가장 빠르다).
 *     런처 창은 그림만 보여 주고 글자를 그릴 수 없다. Windows에서는 런처가 그 창을 첫 스레드에 만들고, 그 스레드는 곧 Java를
 *     돌리느라 창의 메시지를 처리하지 않는다. 플랫폼 초기화가 길면 Windows가 창을 "응답 없음"으로 바꿀 수 있다.</li>
 *     <li>Look and Feel을 설치한 직후 이 클래스가 같은 그림을 런처 창과 같은 자리에 띄우고 버전과 "Loading…"을 얹는다.
 *     자리를 정하는 규칙이 운영체제마다 다르다. Windows 런처는 주 화면 전체의 정가운데에 둔다({@code eclipseWin.c showSplash}:
 *     {@code SM_CXSCREEN}, 화면 배율만큼 키운 그림) — 같은 계산을 한다. macOS 런처는 {@code [NSWindow center]}로 두는데, AppKit은
 *     창을 가로 가운데, 세로는 가운데보다 약간 위에 둔다(정확한 계산은 문서에 없음). 그래서 macOS에서는 계산하지 않고 런처 창의
 *     실제 자리({@code -[NSWindow frame]}, {@value #PROP_LAUNCHER_SPLASH_HANDLE})를 읽어 그대로 쓴다. 읽지 못하면 가운데에 둔다.
 *     그다음 애플리케이션이 런처 창을 내린다({@code IApplicationContext.applicationRunning}). 이 창은 EDT가 그리므로
 *     초기화가 오래 걸려도 응답하고, {@code @2x} 그림이 있으면 HiDPI 화면에서 선명하다.</li>
 * </ol>
 * 런처가 그림을 띄우지 않았으면(시스템 속성 {@value #PROP_LAUNCHER_SPLASH}가 없음: {@code -nosplash}, 개발 실행) 이 창도 띄우지
 * 않는다. 버전을 그림에 넣지 않은 이유: 버전을 올릴 때마다 그림을 다시 만들어야 하고, 잊으면 틀린 버전이 보인다.
 * <ul>
 *     <li>창은 꾸밈 없는 {@link JFrame}({@link Window.Type#UTILITY}: 작업 표시줄 단추 없음)이다. 활성화될 수 있어야 하기 때문이다.
 *     Windows 런처 창은 보일 때 활성화된다. 그 창을 내릴 때 프로세스에 활성 창이 없으면 활성 창이 다른 앱으로 넘어가고,
 *     그 뒤에 여는 메인 창이 앞으로 나오지 못한다. 보이지 않는 소유자를 가진 {@code JWindow}는 활성화될 수 없다.</li>
 *     <li>다른 창이 열리면({@link WindowEvent#WINDOW_OPENED}, 모든 창을 보는 AWT 이벤트 리스너) 닫는다. 메인 창, 시작 오류,
 *     작업 공간 잠금, 모델 코드가 띄우는 대화상자가 모두 같은 경로를 타므로 경로마다 닫는 코드를 두지 않는다.
 *     애플리케이션은 시작이 끝나는 모든 경로에서 {@link #close}도 부른다: 보이는 창이 남으면 AWT 이벤트 스레드가 프로세스를 붙잡는다.</li>
 *     <li>그림의 아래 줄은 {@code tools/branding/gen_app_icons.py}가 비워 둔다. 글자 색은 그림과 같은 다크 테마 토큰이다
 *     (docs/chromite/design-spec.md). 문구는 About의 버전 문구와 공용 "Loading…" 문구를 쓴다(한국어·영어).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 시작 화면 (docs/chromite/branding.md "시작 화면", 감사 행 A-S15)
 * - 2026-11-12 글자 크기를 고정 12 대신 UI 글꼴 그대로(13): 고정값이 UI 글꼴 크기 변경을 따르지 않았음 (앱 변경 #67)
 * - 2026-11-12 macOS: 런처 창이 있던 자리에 그대로 띄움. 화면 정가운데에 두어 가운데보다 약간 위의 런처 창에서 아래로 움직여
 *   보였다(앱 변경 #52, docs/chromite/branding.md "시작 화면")
 */
final class StartupSplash {

    private static final Log log = Log.getLog(StartupSplash.class);

    /**
     * Set by the Equinox launcher ({@code org.eclipse.equinox.launcher.Main}) to the splash image it shows.
     */
    private static final String PROP_LAUNCHER_SPLASH = "org.eclipse.equinox.launcher.splash.location"; //$NON-NLS-1$
    /**
     * Set by the Equinox launcher with {@link #PROP_LAUNCHER_SPLASH}: the native splash window (on macOS its {@code NSWindow}).
     */
    private static final String PROP_LAUNCHER_SPLASH_HANDLE = "org.eclipse.equinox.launcher.splash.handle"; //$NON-NLS-1$
    private static final String HIDPI_SUFFIX = "@2x"; //$NON-NLS-1$
    /**
     * Root pane property of the macOS AWT window ({@code CPlatformWindow.WINDOW_HIDES_ON_DEACTIVATE}).
     */
    private static final String MAC_HIDES_ON_DEACTIVATE = "Window.hidesOnDeactivate"; //$NON-NLS-1$

    // The bottom text row, in the image's 1x pixels
    private static final int TEXT_INSET = 20;
    private static final int TEXT_BASELINE_INSET = 20;
    // Dark theme tokens, the theme of the image (docs/chromite/design-spec.md): status bar widget text and text.primary
    private static final Color VERSION_COLOR = new Color(0x9DA0A8);
    private static final Color STATUS_COLOR = new Color(0xDFE1E5);

    // EDT only
    private static JFrame window;
    private static AWTEventListener otherWindowListener;
    @Nullable
    private static volatile String placement;

    private StartupSplash() {
    }

    /**
     * Shows the splash if the launcher showed one; returns when it is on screen and painted, so the launcher's splash can
     * go without a gap. Call off the EDT.
     */
    static void open() throws InterruptedException {
        String location = System.getProperty(PROP_LAUNCHER_SPLASH);
        if (location == null || GraphicsEnvironment.isHeadless()) {
            return;
        }
        Image image = readImage(Path.of(location));
        if (image == null) {
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> show(image));
        } catch (InvocationTargetException e) {
            log.warn("Can't show the startup splash", e.getTargetException());
        }
    }

    /**
     * Where the splash was shown (its bounds, and whether they are the launcher's splash's), or null without a splash.
     * The splash opens before the debug log does, so the application logs this later.
     */
    @Nullable
    static String getPlacement() {
        return placement;
    }

    /**
     * Closes the splash if it is open. Any thread.
     */
    static void close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(StartupSplash::close);
            return;
        }
        if (otherWindowListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(otherWindowListener);
            otherWindowListener = null;
        }
        if (window != null) {
            window.dispose();
            window = null;
        }
    }

    /**
     * The launcher's image with its {@code @2x} sibling when there is one (generated next to it), so HiDPI screens draw
     * the large one.
     */
    @Nullable
    private static Image readImage(@NotNull Path file) {
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                log.warn("Unknown startup splash image format: " + file);
                return null;
            }
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            Path hiDpiFile = file.resolveSibling(dot < 0 ? name + HIDPI_SUFFIX : name.substring(0, dot) + HIDPI_SUFFIX + name.substring(dot));
            BufferedImage hiDpiImage = Files.isRegularFile(hiDpiFile) ? ImageIO.read(hiDpiFile.toFile()) : null;
            return hiDpiImage == null ? image : new BaseMultiResolutionImage(image, hiDpiImage);
        } catch (IOException e) {
            log.warn("Can't read the startup splash image " + file, e);
            return null;
        }
    }

    private static void show(@NotNull Image image) {
        JFrame frame = new JFrame();
        frame.setUndecorated(true);
        frame.setType(Window.Type.UTILITY);
        frame.setResizable(false);
        frame.setIconImages(ChromiteIcons.getWindowIcons());
        // macOS makes a utility window an NSPanel; it must stay visible while another application is in front
        frame.getRootPane().putClientProperty(MAC_HIDES_ON_DEACTIVATE, Boolean.FALSE);
        Content content = new Content(image);
        frame.setContentPane(content);
        frame.pack();
        Rectangle launcherBounds = RuntimeUtils.isMacOS() ? getMacLauncherSplashBounds() : null;
        if (launcherBounds != null) {
            // macOS: exactly over the launcher's splash, which AppKit placed somewhat above the middle ([NSWindow center])
            frame.setBounds(launcherBounds);
        } else {
            // The middle of the whole main screen, where the Windows launcher centres its splash (not the area without the taskbar)
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
            frame.setLocation(screen.x + (screen.width - frame.getWidth()) / 2, screen.y + (screen.height - frame.getHeight()) / 2);
        }
        // Logged by the application once the debug log is open (macos-smoke reads it)
        placement = frame.getBounds() + (launcherBounds != null ? " (the launcher's splash)" : ""); //$NON-NLS-1$ //$NON-NLS-2$
        frame.setVisible(true);
        // Painted before the launcher's splash goes, so nothing blank shows in between
        content.paintImmediately(0, 0, content.getWidth(), content.getHeight());
        window = frame;
        otherWindowListener = event -> {
            if (event.getID() == WindowEvent.WINDOW_OPENED && event.getSource() != window) {
                close();
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(otherWindowListener, AWTEvent.WINDOW_EVENT_MASK);
    }

    /**
     * The macOS launcher's splash window in AWT screen coordinates, or null. The launcher puts the address of its
     * {@code NSWindow} in {@value #PROP_LAUNCHER_SPLASH_HANDLE}; this reads {@code -[NSWindow frame]}. The window lives on
     * the AppKit thread, but nothing moves it while the launcher shows it, so the read needs no dispatch.
     */
    @Nullable
    private static Rectangle getMacLauncherSplashBounds() {
        String handle = System.getProperty(PROP_LAUNCHER_SPLASH_HANDLE);
        if (handle == null) {
            return null;
        }
        try {
            Pointer window = new Pointer(Long.parseLong(handle));
            NativeLibrary process = NativeLibrary.getProcess();
            Pointer frameSelector = (Pointer) process.getFunction("sel_registerName").invoke(Pointer.class, new Object[] {"frame"}); //$NON-NLS-1$ //$NON-NLS-2$
            // A 32-byte struct comes back in registers on arm64 (four doubles) and through memory on x86_64 (the _stret entry)
            String msgSend = "aarch64".equals(System.getProperty("os.arch")) ? "objc_msgSend" : "objc_msgSend_stret"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            NSRect frame = (NSRect) process.getFunction(msgSend).invoke(NSRect.class, new Object[] {window, frameSelector});
            if (frame.width <= 0 || frame.height <= 0) {
                return null;
            }
            // Cocoa's origin is the bottom left of the main screen (the one with the menu bar), AWT's its top left
            Rectangle main = null;
            for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                Rectangle bounds = device.getDefaultConfiguration().getBounds();
                if (bounds.x == 0 && bounds.y == 0) {
                    main = bounds;
                }
            }
            if (main == null) {
                return null;
            }
            return new Rectangle(
                (int) Math.round(frame.x),
                (int) Math.round(main.height - frame.y - frame.height),
                (int) Math.round(frame.width),
                (int) Math.round(frame.height));
        } catch (RuntimeException | LinkageError e) {
            log.warn("Can't read the launcher's splash window, the startup splash is centred instead", e);
            return null;
        }
    }

    /**
     * Cocoa's {@code NSRect} (CGFloat is a double on 64-bit macOS), returned by value.
     */
    public static final class NSRect extends Structure implements Structure.ByValue {
        public double x;
        public double y;
        public double width;
        public double height;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("x", "y", "width", "height"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
    }

    private static final class Content extends JComponent {
        private final Image image;
        private final String version = NLS.bind(CoreMessages.about_version, GeneralUtils.getPlainVersion());
        // The UI font (font.ui) as the theme sets it, so the text follows its size
        private final Font font = UIManager.getFont("Label.font"); //$NON-NLS-1$

        Content(@NotNull Image image) {
            this.image = image;
            // The 1x image's pixels are the window's size in screen units
            Dimension size = new Dimension(image.getWidth(null), image.getHeight(null));
            if (image instanceof BaseMultiResolutionImage multi) {
                Image base = multi.getResolutionVariants().getFirst();
                size = new Dimension(base.getWidth(null), base.getHeight(null));
            }
            setPreferredSize(size);
            setOpaque(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                // The 2x image scaled down to 1.5x and the like
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
                FlatUIUtils.setRenderingHints(g2);
                g2.setFont(font);
                int baseline = getHeight() - TEXT_BASELINE_INSET;
                g2.setColor(VERSION_COLOR);
                g2.drawString(version, TEXT_INSET, baseline);
                String status = CoreMessages.common_loading;
                g2.setColor(STATUS_COLOR);
                g2.drawString(status, getWidth() - TEXT_INSET - g2.getFontMetrics().stringWidth(status), baseline);
            } finally {
                g2.dispose();
            }
        }
    }
}
