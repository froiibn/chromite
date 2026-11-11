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

import com.formdev.flatlaf.util.UIScale;

import javax.swing.*;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.geom.Arc2D;

/**
 * 길이를 모르는 짧은 기다림의 진행 표시: 회전하는 도넛(옅은 고리 위에 강조색 호 하나).
 *
 * 설계: 사용자 결정(앱 변경 #60, 2026-11-11) "길이를 모르는 진행은 막대가 아니라 도넛 스피너". 길이를 아는 진행(받기 %, 전송 행 수)은 막대를 쓴다.
 * 그림은 테마 색(강조색 {@code Component.accentColor}, 고리는 {@code Component.borderColor})으로 그려 라이트·다크 테마를 따른다.
 * 타이머는 화면에 보일 때만 돈다(숨기거나 창을 닫으면 멈춤). 크기는 UI 배율을 따른다.
 * 상태 표시줄 스피너(#44, 다른 브랜치)와 구현이 겹치면 병합 때 하나로 합친다.
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: 연결 대화상자의 데이터베이스 목록 읽기 (docs/chromite/swing-conn-advanced.md "데이터베이스 목록에서 고르기")
 */
final class DonutSpinner extends JComponent {

    private static final int FRAME_MILLIS = 40;
    private static final int DEGREES_PER_FRAME = 12;
    private static final int ARC_DEGREES = 100;

    private final int size;
    private final Timer timer = new Timer(FRAME_MILLIS, e -> step());
    private int angle;

    /**
     * @param size the diameter before UI scaling
     */
    DonutSpinner(int size) {
        this.size = size;
        setOpaque(false);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                updateTimer();
            }
        });
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        updateTimer();
    }

    private void updateTimer() {
        if (isShowing()) {
            timer.start();
        } else {
            timer.stop();
        }
    }

    private void step() {
        angle = (angle + DEGREES_PER_FRAME) % 360;
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        int scaled = UIScale.scale(size);
        return new Dimension(scaled, scaled);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        int diameter = Math.min(getWidth(), getHeight());
        float stroke = Math.max(1.5f, diameter / 7f);
        double inset = stroke / 2.0;
        double x = (getWidth() - diameter) / 2.0 + inset;
        double y = (getHeight() - diameter) / 2.0 + inset;
        double d = diameter - stroke;
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(themeColor("Component.borderColor", Color.LIGHT_GRAY)); //$NON-NLS-1$
            g2.draw(new Arc2D.Double(x, y, d, d, 0, 360, Arc2D.OPEN));
            g2.setColor(themeColor("Component.accentColor", new Color(0x2675BF))); //$NON-NLS-1$
            // Clockwise: Arc2D angles run counterclockwise
            g2.draw(new Arc2D.Double(x, y, d, d, -angle, ARC_DEGREES, Arc2D.OPEN));
        } finally {
            g2.dispose();
        }
    }

    private static Color themeColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }
}
