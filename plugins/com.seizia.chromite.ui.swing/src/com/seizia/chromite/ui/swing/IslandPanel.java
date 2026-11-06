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
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * DataGrip Islands 카드: 도구 창이나 편집기를 둥근 모서리 카드(island)로 보여 주는 패널(design-spec "Islands 적용").
 *
 * 설계:
 * <ul>
 *     <li>카드 바탕은 {@code Chromite.island.background}, 둘레는 메인 창 캔버스 {@code Chromite.island.canvas}, 모서리 지름은
 *     {@code Chromite.island.arc}(IntelliJ {@code Island.arc} 20)이다. 값은 테마 properties에서 읽어 테마를 바꾸면 따라간다.</li>
 *     <li>자식(트리, 편집기, 표 등)은 대개 불투명한 사각형이라 둥근 모양으로 잘라 그릴 수 없다(모양 클립은 앤티에일리어싱이 없어 모서리가 계단이 된다).
 *     그래서 자식을 다 그린 뒤 네 모서리의 바깥 부분을 캔버스 색으로 앤티에일리어싱해 덮는다. 자식 색이 무엇이든 모서리가 매끈하다.</li>
 *     <li>자식 하나만 다시 그려도 모서리를 다시 덮도록 이 패널을 그리기 시작점으로 둔다({@link #isPaintingOrigin()}). 다시 그리는 영역은
 *     그대로 자식의 영역이라 비용은 거의 같다.</li>
 *     <li>카드 사이 틈(6, {@code Island.borderWidth})은 메인 창의 분할선과 바깥 여백이 만든다({@link MainFrame}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Islands: 최초 작성 (docs/chromite/design-spec.md "Islands 적용")
 */
final class IslandPanel extends JPanel {

    private static final int DEFAULT_ARC = 20;

    IslandPanel(@NotNull JComponent content) {
        super(new BorderLayout());
        add(content, BorderLayout.CENTER);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        Color background = UIManager.getColor("Chromite.island.background"); //$NON-NLS-1$
        if (background != null) {
            setBackground(background);
        }
    }

    @Override
    protected boolean isPaintingOrigin() {
        return true;
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        Color canvas = UIManager.getColor("Chromite.island.canvas"); //$NON-NLS-1$
        if (canvas == null) {
            return;
        }
        int arc = UIScale.scale(UIManager.getInt("Chromite.island.arc") > 0 ? UIManager.getInt("Chromite.island.arc") : DEFAULT_ARC); //$NON-NLS-1$ //$NON-NLS-2$
        Area corners = new Area(new Rectangle2D.Float(0, 0, getWidth(), getHeight()));
        corners.subtract(new Area(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), arc, arc)));
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(canvas);
            g2.fill(corners);
        } finally {
            g2.dispose();
        }
    }
}
