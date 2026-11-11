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

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import org.jkiss.code.NotNull;
import com.seizia.chromite.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A rotating donut spinner (16, the size of the pack icons) for a running query: the state cell of the status bar and the
 * query window's tab.
 *
 * 설계:
 * <ul>
 *     <li>그림 파일이 아니라 Java2D로 그린다(옅은 고리 위에 테마 강조 색 호 하나). 벡터라 150%에서도 선명하고 테마를 바꾸면 다음 그림부터 새 색이다.</li>
 *     <li>아이콘은 자기를 그린 부품과 자리를 기억해 두고 타이머가 그 자리만 다시 그리게 한다. 탭 줄처럼 큰 부품 전체를 다시 그리지 않기 위해서다.
 *     각도는 시각에서 정하므로 여러 부품에 그려도 같이 돈다. {@link #stop()}하면 타이머가 멈추고 기억한 부품을 놓는다.</li>
 *     <li>운영체제의 움직임 줄이기(Windows "애니메이션 효과" 끔, macOS "동작 줄이기")를 켰으면 돌지 않는 같은 모양을 그린다
 *     ({@link #isReducedMotion()}, 시작할 때마다 읽는다). 그 밖의 OS는 설정을 읽지 않고 돈다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: 쿼리 실행 중 표시(앱 변경 #44, docs/chromite/swing-sql-editor-session-ui.md "실행 중 표시")
 */
final class SpinnerIcon implements Icon {

    private static final Log log = Log.getLog(SpinnerIcon.class);

    private static final int SIZE = UiIcons.SIZE;
    // One turn per second, about 20 frames: smooth enough for a 16 px ring, cheap for the clip-sized repaints
    private static final int TURN_MILLIS = 1000;
    private static final int FRAME_MILLIS = 50;
    private static final float STROKE = 2.2f;
    private static final double ARC_DEGREES = 100;

    // Windows SystemParametersInfo: whether "Animation effects" is on
    private static final int SPI_GETCLIENTAREAANIMATION = 0x1042;

    // The components that painted the icon and where; weak so that a closed window is not kept
    private final Map<Component, Rectangle> painted = new WeakHashMap<>();
    private final Timer timer = new Timer(FRAME_MILLIS, e -> repaintPainted());
    private boolean running;
    private boolean animated;

    /**
     * Starts turning (EDT). Reads the operating system's reduced motion setting.
     */
    void start() {
        if (running) {
            return;
        }
        running = true;
        animated = !isReducedMotion();
        if (animated) {
            timer.start();
        }
    }

    /**
     * Stops turning and forgets the components (EDT).
     */
    void stop() {
        running = false;
        timer.stop();
        painted.clear();
    }

    private void repaintPainted() {
        for (Map.Entry<Component, Rectangle> entry : painted.entrySet()) {
            Component component = entry.getKey();
            if (component.isShowing()) {
                Rectangle bounds = entry.getValue();
                component.repaint(bounds.x, bounds.y, bounds.width, bounds.height);
            }
        }
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        if (running && animated && c != null) {
            painted.put(c, new Rectangle(x, y, SIZE, SIZE));
        }
        Color accent = UIManager.getColor("Component.accentColor"); //$NON-NLS-1$
        if (accent == null) {
            accent = UIManager.getColor("Component.focusColor"); //$NON-NLS-1$
        }
        if (accent == null) {
            accent = Color.BLUE;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            double inset = 1.5 + STROKE / 2;
            double diameter = SIZE - 2 * inset;
            g2.setStroke(new BasicStroke(STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // The ring: the accent color, faint, so that it reads on light and dark themes and on connection colors
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 70));
            g2.draw(new Ellipse2D.Double(x + inset, y + inset, diameter, diameter));
            // The arc turns clockwise (Java2D angles go counterclockwise)
            double start = animated && running ? -360.0 * (System.currentTimeMillis() % TURN_MILLIS) / TURN_MILLIS : 90;
            g2.setColor(accent);
            g2.draw(new Arc2D.Double(x + inset, y + inset, diameter, diameter, start, ARC_DEGREES, Arc2D.OPEN));
        } finally {
            g2.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }

    /**
     * Whether the operating system asks applications to reduce motion: Windows with "Animation effects" off, macOS with
     * "Reduce motion" on. False elsewhere and when the setting cannot be read.
     */
    static boolean isReducedMotion() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            if (os.startsWith("windows")) { //$NON-NLS-1$
                Function function = NativeLibrary.getInstance("user32").getFunction("SystemParametersInfoW"); //$NON-NLS-1$ //$NON-NLS-2$
                Memory enabled = new Memory(4);
                enabled.setInt(0, 1);
                boolean ok = function.invokeInt(new Object[]{SPI_GETCLIENTAREAANIMATION, 0, enabled, 0}) != 0;
                return ok && enabled.getInt(0) == 0;
            }
            if (os.startsWith("mac")) { //$NON-NLS-1$
                NativeLibrary objc = NativeLibrary.getInstance("objc"); //$NON-NLS-1$
                Function getClass = objc.getFunction("objc_getClass"); //$NON-NLS-1$
                Function selector = objc.getFunction("sel_registerName"); //$NON-NLS-1$
                Function msgSend = objc.getFunction("objc_msgSend"); //$NON-NLS-1$
                Pointer workspace = msgSend.invokePointer(new Object[]{
                    getClass.invokePointer(new Object[]{"NSWorkspace"}), //$NON-NLS-1$
                    selector.invokePointer(new Object[]{"sharedWorkspace"})}); //$NON-NLS-1$
                if (workspace == null) {
                    return false;
                }
                // BOOL is one byte: the rest of the register is not defined
                int reduce = msgSend.invokeInt(new Object[]{workspace,
                    selector.invokePointer(new Object[]{"accessibilityDisplayShouldReduceMotion"})}); //$NON-NLS-1$
                return (reduce & 0xFF) != 0;
            }
        } catch (Throwable e) {
            log.debug("Can't read the reduced motion setting", e);
        }
        return false;
    }
}
