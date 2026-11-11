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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeListener;

/**
 * The look of the settings forms (Tools &gt; Options pages, the connection dialog's Settings pages, the forms that use the same rows):
 * a bold section title above a rounded card, one setting per row in the card, the name on the left with a grey description under
 * it, the control at the right end, thin lines between the rows.
 *
 * 설계:
 * <ul>
 *     <li>페이지 코드는 그대로 두고 {@link OptionsDialog#addSection}, {@link OptionsDialog#addRow}가 이 클래스로 그린다. 모든 페이지(약 40개)가
 *     한 번에 같은 모양이 된다. 페이지는 지금처럼 GridBag 양식에 행 번호로 더한다. 절은 양식의 한 행에 제목과 카드를 두고, 뒤의 행은 그 카드
 *     안에 쌓는다(행 번호는 그대로 하나씩 늘어 페이지가 직접 넣는 표·미리 보기는 카드 아래에 온다). 페이지가 카드 뒤에 다른 것을 넣은 다음의
 *     행은 제목 없는 이어지는 카드에 들어간다(순서가 바뀌지 않음).</li>
 *     <li>카드 테두리: 저장 표준(Turbrix STYLE_GUIDE 5.12 7항 "칸마다 테두리와 제목")과 참고 화면의 굵은 절 제목·가는 줄을 함께 따른다.
 *     색은 테마 토큰(Chromite.dividerColor, Component.borderColor)을 그릴 때 읽어 테마를 바꿔도 맞는다.</li>
 *     <li>체크 상자 행: 글자를 왼쪽 이름으로 옮기고 상자는 오른쪽 끝에 둔다. 이름을 눌러도 바뀐다. 켜고 끄는 토글은 스위치 모양
 *     ({@link SwitchIcon})이고, 값의 일부인 체크 상자({@link #VALUE_CHECK_BOX}, 저장 단추로 저장)는 체크 상자 모양 그대로다.
 *     이름은 상자의 사용 가능 여부·글자·도움말을 따른다(페이지가 상자를 끄면 이름도 흐려짐).</li>
 *     <li>이름 없는 행(안내 글, 여러 상자를 담은 패널): 행 전체 폭에 왼쪽부터 둔다.</li>
 *     <li>컨트롤이 넓어 이름 옆에 들어가지 않으면 이름 아래로 내린다({@link RowLayout}).</li>
 *     <li>글꼴과 글자색은 바꾸지 않는다(테마의 secondaryForeground, semibold 글꼴만 씀).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 옵션 화면 다시 디자인(앱 변경 #59, docs/chromite/swing-options.md "옵션 화면 모양과 저장 (#59)")
 */
final class OptionsForm {

    /**
     * Client property of a check box that is part of a value (saved with the page's Save button, as a table row's fields), not a toggle
     * that applies at once: it keeps the check box look.
     */
    static final String VALUE_CHECK_BOX = "chromite.options.valueCheckBox"; //$NON-NLS-1$

    // The card rows are added to (client property of the form)
    private static final String CURRENT_CARD = "chromite.options.card"; //$NON-NLS-1$
    // Above a section title (not the first), under it
    private static final int SECTION_GAP = 24;
    private static final int TITLE_GAP = 8;
    // Inside a card: side padding, row padding (each side), gap between the name and the control
    private static final int CARD_PADDING = 14;
    private static final int ROW_PADDING = 9;
    private static final int CONTROL_GAP = 16;
    private static final int CARD_ARC = 10;
    private static final String SECONDARY = "foreground: $Chromite.secondaryForeground"; //$NON-NLS-1$

    private OptionsForm() {
    }

    /**
     * A bold title and the card the next rows go into.
     */
    static int addSection(@NotNull JPanel form, int row, @NotNull String title) {
        JLabel label = new JLabel(title);
        label.putClientProperty(FlatClientProperties.STYLE, "font: +1 $semibold.font"); //$NON-NLS-1$
        label.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(2), UIScale.scale(TITLE_GAP), 0));
        addCard(form, row, label, row == 0 ? 0 : SECTION_GAP);
        return row + 1;
    }

    /**
     * A setting row in the current card (a new card when there is none or something else was added to the form after it).
     *
     * @param label the setting's name; {@code null} for a check box (its text is the name) or a full-width row
     * @param hint  grey description under the name
     */
    static int addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control, @Nullable String hint) {
        Card card = form.getClientProperty(CURRENT_CARD) instanceof Card current && isLast(form, current) ? current : null;
        if (card == null) {
            card = addCard(form, row, null, row == 0 ? 0 : TITLE_GAP);
        }
        card.add(createRow(label, control, hint));
        return row + 1;
    }

    private static boolean isLast(@NotNull JPanel form, @NotNull Card card) {
        int count = form.getComponentCount();
        return count > 0 && form.getComponent(count - 1) == card.getParent();
    }

    @NotNull
    private static Card addCard(@NotNull JPanel form, int row, @Nullable JLabel title, int gap) {
        Card card = new Card();
        JPanel section = new JPanel(new BorderLayout());
        section.setOpaque(false);
        if (title != null) {
            section.add(title, BorderLayout.NORTH);
        }
        section.add(card, BorderLayout.CENTER);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.weightx = 1;
        c.insets = new Insets(UIScale.scale(gap), 0, 0, 0);
        form.add(section, c);
        form.putClientProperty(CURRENT_CARD, card);
        return card;
    }

    @NotNull
    private static JComponent createRow(@Nullable String label, @NotNull JComponent control, @Nullable String hint) {
        JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        JLabel name = null;
        if (label != null) {
            name = new JLabel(label);
        } else if (control instanceof JCheckBox box) {
            name = createCheckBoxName(box);
        }
        if (name != null) {
            name.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(name);
        }
        if (hint != null) {
            JLabel hintLabel = new JLabel(hint);
            hintLabel.putClientProperty(FlatClientProperties.STYLE, SECONDARY);
            hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
            text.add(Box.createVerticalStrut(UIScale.scale(2)));
            text.add(hintLabel);
        }
        if (control instanceof JSpinner spinner && spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            editor.getTextField().setColumns(8);
        }
        boolean fullWidth = name == null;
        JPanel row = new JPanel(new RowLayout(fullWidth));
        row.setOpaque(false);
        row.add(text);
        row.add(control);
        // The row follows the control's visibility (pages hide controls that do not apply)
        row.setVisible(control.isVisible());
        control.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                row.setVisible(true);
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                row.setVisible(false);
            }
        });
        return row;
    }

    /**
     * The check box's text as the row name on the left; the box (a switch for a toggle) at the right end.
     */
    @NotNull
    private static JLabel createCheckBoxName(@NotNull JCheckBox box) {
        JLabel name = new JLabel();
        moveText(box, name);
        name.setToolTipText(box.getToolTipText());
        name.setEnabled(box.isEnabled());
        PropertyChangeListener mirror = e -> {
            switch (e.getPropertyName()) {
                case "enabled" -> name.setEnabled(box.isEnabled()); //$NON-NLS-1$
                case AbstractButton.TEXT_CHANGED_PROPERTY -> moveText(box, name);
                case JComponent.TOOL_TIP_TEXT_KEY -> name.setToolTipText(box.getToolTipText());
                default -> {
                }
            }
        };
        box.addPropertyChangeListener(mirror);
        name.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (box.isEnabled() && box.isShowing()) {
                    box.requestFocusInWindow();
                    box.doClick(0);
                }
            }
        });
        if (!Boolean.TRUE.equals(box.getClientProperty(VALUE_CHECK_BOX))) {
            SwitchIcon icon = new SwitchIcon();
            box.setIcon(icon);
            box.setSelectedIcon(icon);
            box.setPressedIcon(icon);
            box.setRolloverIcon(icon);
            box.setRolloverSelectedIcon(icon);
            box.setDisabledIcon(icon);
            box.setDisabledSelectedIcon(icon);
        }
        return name;
    }

    private static void moveText(@NotNull JCheckBox box, @NotNull JLabel name) {
        String text = box.getText();
        if (text != null && !text.isEmpty()) {
            name.setText(text);
            box.getAccessibleContext().setAccessibleName(text);
            box.setText(null);
        }
    }

    /**
     * Name (and description) on the left, the control at the right end, vertically centered; the control goes under the name when
     * both do not fit. A full-width row has its control on the left at up to the row's width, the description under it.
     */
    private static final class RowLayout implements LayoutManager {
        private final boolean fullWidth;

        RowLayout(boolean fullWidth) {
            this.fullWidth = fullWidth;
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Dimension text = parent.getComponent(0).getPreferredSize();
            Dimension control = parent.getComponent(1).getPreferredSize();
            int padding = UIScale.scale(ROW_PADDING) * 2;
            if (fullWidth) {
                return new Dimension(Math.max(text.width, control.width), control.height + text.height + padding);
            }
            return new Dimension(text.width + UIScale.scale(CONTROL_GAP) + control.width, Math.max(text.height, control.height) + padding);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Component text = parent.getComponent(0);
            Component control = parent.getComponent(1);
            Dimension textSize = text.getPreferredSize();
            Dimension controlSize = control.getPreferredSize();
            int width = parent.getWidth();
            int top = UIScale.scale(ROW_PADDING);
            if (fullWidth) {
                control.setBounds(0, top, Math.min(width, controlSize.width), controlSize.height);
                text.setBounds(0, top + controlSize.height, width, textSize.height);
                return;
            }
            int gap = UIScale.scale(CONTROL_GAP);
            if (textSize.width + gap + controlSize.width <= width) {
                int height = parent.getHeight() - top * 2;
                text.setBounds(0, top + (height - textSize.height) / 2, width - gap - controlSize.width, textSize.height);
                control.setBounds(width - controlSize.width, top + (height - controlSize.height) / 2, controlSize.width, controlSize.height);
            } else {
                // Stacked: the control under the name, at the right end as far as it fits
                int controlWidth = Math.min(width, controlSize.width);
                text.setBounds(0, top, width, textSize.height);
                control.setBounds(width - controlWidth, top + textSize.height + UIScale.scale(4), controlWidth, controlSize.height);
            }
        }
    }

    /**
     * The rows of a section: a rounded border, thin lines between the visible rows. It reports a stacked height when a row needs it.
     */
    private static final class Card extends JPanel {
        Card() {
            super(null);
            setOpaque(false);
            setBorder(new CardBorder());
        }

        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            int width = 0;
            int height = 0;
            int available = getWidth() > 0 ? getWidth() - insets.left - insets.right : Integer.MAX_VALUE;
            for (Component row : getComponents()) {
                if (row.isVisible()) {
                    Dimension size = row.getPreferredSize();
                    width = Math.max(width, size.width);
                    height += getRowHeight(row, available);
                }
            }
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension size = getPreferredSize();
            size.width = 0;
            return size;
        }

        /**
         * The height depends on the width (stacked rows): a new width lays the form out again once.
         */
        @Override
        public void setBounds(int x, int y, int width, int height) {
            int oldWidth = getWidth();
            super.setBounds(x, y, width, height);
            if (oldWidth != width) {
                SwingUtilities.invokeLater(this::revalidate);
            }
        }

        @Override
        public void doLayout() {
            Insets insets = getInsets();
            int width = getWidth() - insets.left - insets.right;
            int y = insets.top;
            for (Component row : getComponents()) {
                if (row.isVisible()) {
                    int height = getRowHeight(row, width);
                    row.setBounds(insets.left, y, width, height);
                    y += height;
                }
            }
        }

        /**
         * The row's height at a width: its preferred height, or the stacked height when the name and the control do not fit side by side.
         */
        private static int getRowHeight(@NotNull Component row, int width) {
            Dimension size = row.getPreferredSize();
            if (row instanceof Container container && container.getLayout() instanceof RowLayout layout && !layout.fullWidth
                && size.width > width) {
                Dimension text = container.getComponent(0).getPreferredSize();
                Dimension control = container.getComponent(1).getPreferredSize();
                return text.height + UIScale.scale(4) + control.height + UIScale.scale(ROW_PADDING) * 2;
            }
            return size.height;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(UIManager.getColor("Chromite.dividerColor")); //$NON-NLS-1$
            Insets insets = getInsets();
            boolean first = true;
            for (Component row : getComponents()) {
                if (!row.isVisible()) {
                    continue;
                }
                if (!first) {
                    g.fillRect(insets.left, row.getY(), getWidth() - insets.left - insets.right, Math.max(1, UIScale.scale(1)));
                }
                first = false;
            }
        }
    }

    /**
     * Padding with a divider line (Chromite.dividerColor, read when painted so that it follows the theme) on one side:
     * {@link SwingConstants#TOP} (the Options footer) or {@link SwingConstants#RIGHT} (the Options page list).
     */
    @NotNull
    static Border createDividerBorder(int side, @NotNull Insets padding) {
        return new AbstractBorder() {
            @Override
            public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
                g.setColor(UIManager.getColor("Chromite.dividerColor")); //$NON-NLS-1$
                int line = Math.max(1, UIScale.scale(1));
                if (side == SwingConstants.TOP) {
                    g.fillRect(x, y, width, line);
                } else {
                    g.fillRect(x + width - line, y, line, height);
                }
            }

            @Override
            public Insets getBorderInsets(Component c, Insets insets) {
                insets.set(padding.top, padding.left, padding.bottom, padding.right);
                return insets;
            }
        };
    }

    private static boolean hasVisibleRows(@NotNull Component card) {
        if (card instanceof Container container) {
            for (Component row : container.getComponents()) {
                if (row.isVisible()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Rounded line around a card (border.default), with the card padding inside; nothing for a card without rows (a section whose
     * content the page adds itself under the title: a table, a preview).
     */
    private static final class CardBorder extends AbstractBorder {
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (!hasVisibleRows(c)) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g2.setColor(UIManager.getColor("Component.borderColor")); //$NON-NLS-1$
                float arc = UIScale.scale((float) CARD_ARC);
                g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, width - 1, height - 1, arc, arc));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            if (!hasVisibleRows(c)) {
                insets.set(0, 0, 0, 0);
                return insets;
            }
            int padding = UIScale.scale(CARD_PADDING);
            insets.set(UIScale.scale(1), padding, UIScale.scale(1), padding);
            return insets;
        }
    }

    /**
     * An on/off switch drawn as the check box's icon: the track in the check box border color when off and in the accent (the primary button
     * background) when on, the knob on the side of the state, faded when disabled, a focus ring when the box has the focus.
     */
    static final class SwitchIcon implements Icon {
        private static final int WIDTH = 30;
        private static final int HEIGHT = 18;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            boolean selected = c instanceof AbstractButton button && button.isSelected();
            boolean enabled = c.isEnabled();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                if (!enabled) {
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
                }
                float width = getIconWidth();
                float height = getIconHeight();
                float focus = UIScale.scale(2f);
                float trackX = x + focus;
                float trackY = y + focus;
                float trackWidth = width - focus * 2;
                float trackHeight = height - focus * 2;
                if (c.hasFocus()) {
                    g2.setColor(UIManager.getColor("Component.focusColor")); //$NON-NLS-1$
                    g2.fill(new RoundRectangle2D.Float(x, y, width, height, height, height));
                    g2.setColor(c.getParent() != null ? c.getParent().getBackground() : c.getBackground());
                    float inner = UIScale.scale(1f);
                    g2.fill(new RoundRectangle2D.Float(x + inner, y + inner, width - inner * 2, height - inner * 2, height, height));
                }
                g2.setColor(UIManager.getColor(selected ? "Button.default.background" : "CheckBox.icon.borderColor")); //$NON-NLS-1$ //$NON-NLS-2$
                g2.fill(new RoundRectangle2D.Float(trackX, trackY, trackWidth, trackHeight, trackHeight, trackHeight));
                float inset = UIScale.scale(2f);
                float knob = trackHeight - inset * 2;
                float knobX = selected ? trackX + trackWidth - inset - knob : trackX + inset;
                g2.setColor(UIManager.getColor(selected ? "Button.default.foreground" : "CheckBox.icon.background")); //$NON-NLS-1$ //$NON-NLS-2$
                g2.fill(new RoundRectangle2D.Float(knobX, trackY + inset, knob, knob, knob, knob));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return UIScale.scale(WIDTH);
        }

        @Override
        public int getIconHeight() {
            return UIScale.scale(HEIGHT);
        }
    }
}
