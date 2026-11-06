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

import com.seizia.chromite.ui.swing.internal.ToolbarMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 가로 도구 막대: 폭이 모자라면 줄일 수 있는 컴포넌트(콤보)를 먼저 줄이고, 그래도 넘치는 뒤쪽 단추는 오른쪽 끝의 넘침 단추(»)
 * 메뉴로 옮긴다.
 *
 * 설계:
 * <ul>
 *     <li>JToolBar의 기본 배치(BoxLayout)는 넘치는 컴포넌트를 오른쪽에서 잘라 버려, 좁은 창(1280px·150%)이나 도구 창을 넓힌 쿼리 창에서
 *     뒤쪽 단추를 누를 수 없었다. 그래서 배치 관리자({@link OverflowLayout})를 바꾼다. 컴포넌트를 숨기거나 보이는 대신 들어가지 않는
 *     컴포넌트에 크기 0을 준다. 배치 중에 보임 상태를 바꾸면 다시 배치가 일어나 반복될 수 있기 때문이다.</li>
 *     <li>줄일 수 있는 컴포넌트는 {@link #setShrinkable}로 정한 것(쿼리 창의 연결·DB 콤보)뿐이다. 구분선 같은 다른 컴포넌트도 최소 폭이 기본 폭보다
 *     작지만 줄이면 모양이 깨진다. 모자란 폭을 기본 폭과 최소 폭의 차이에 비례해 나눠 줄인다(BoxLayout과 같은 방식).</li>
 *     <li>넘침 메뉴는 열 때마다 숨은 단추로 만든다. 항목은 단추의 아이콘과 동작 이름이고, 누르면 단추를 누른 것과 같다({@link AbstractButton#doClick()}).
 *     켜고 끄는 단추는 선택 표시가 있는 항목이다. 콤보처럼 단추가 아닌 컴포넌트는 메뉴에 넣지 않는다.</li>
 *     <li>넘친 자리 바로 앞의 구분선은 그리지 않는다(끝에 구분선만 남지 않게).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: 주 도구 막대와 쿼리 창 도구 막대 확장 (docs/chromite/swing-frontend.md "도구 막대")
 */
final class OverflowToolBar extends JToolBar {

    private static final String SHRINKABLE_KEY = "chromite.toolBar.shrinkable"; //$NON-NLS-1$

    private final JButton overflowButton;

    OverflowToolBar() {
        setFloatable(false);
        overflowButton = UiIcons.createActionButton(new AbstractAction(ToolbarMessages.toolbar_overflow_tip) {
            @Override
            public void actionPerformed(ActionEvent e) {
                showOverflowMenu();
            }
        }, "overflow"); //$NON-NLS-1$
        add(overflowButton);
        setLayout(new OverflowLayout());
    }

    /**
     * Gives a component a minimum width below its preferred width, so the tool bar may narrow it before buttons overflow.
     */
    static void setShrinkable(@NotNull JComponent component, int minimumWidth) {
        component.setMinimumSize(new Dimension(minimumWidth, component.getPreferredSize().height));
        component.putClientProperty(SHRINKABLE_KEY, Boolean.TRUE);
    }

    private void showOverflowMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (Component component : getComponents()) {
            if (component == overflowButton || !isOverflowed(component) || !(component instanceof AbstractButton button)) {
                continue;
            }
            // The action's name (also for icon-only buttons), else the button's own text (Actual Plan has no action)
            Action action = button.getAction();
            String text = action != null && action.getValue(Action.NAME) != null ? String.valueOf(action.getValue(Action.NAME))
                : button.getText() != null && !button.getText().isEmpty() ? button.getText() : button.getToolTipText();
            JMenuItem item = button instanceof JToggleButton ? new JCheckBoxMenuItem(text, button.isSelected()) : new JMenuItem(text);
            item.setIcon(button.getIcon());
            item.setToolTipText(button.getToolTipText());
            item.setEnabled(button.isEnabled());
            item.addActionListener(e -> button.doClick());
            menu.add(item);
        }
        if (menu.getComponentCount() > 0) {
            menu.show(overflowButton, 0, overflowButton.getHeight());
        }
    }

    private static boolean isOverflowed(@NotNull Component component) {
        return component.isVisible() && component.getWidth() == 0;
    }

    /**
     * Left-to-right layout at preferred sizes, components centered vertically (separators take the full height).
     * See the class comment for shrinking and overflow.
     */
    private final class OverflowLayout implements LayoutManager {
        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int width = 0;
            int height = 0;
            for (Component component : getLaidOutComponents()) {
                Dimension size = component.getPreferredSize();
                width += size.width;
                height = Math.max(height, size.height);
            }
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            // Anything wider overflows into the menu
            Insets insets = parent.getInsets();
            Dimension size = overflowButton.getPreferredSize();
            return new Dimension(size.width + insets.left + insets.right, preferredLayoutSize(parent).height);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int available = parent.getWidth() - insets.left - insets.right;
            int height = parent.getHeight() - insets.top - insets.bottom;
            List<Component> components = getLaidOutComponents();
            int[] widths = new int[components.size()];
            int total = 0;
            int shrinkable = 0;
            for (int i = 0; i < widths.length; i++) {
                Component component = components.get(i);
                widths[i] = component.getPreferredSize().width;
                total += widths[i];
                shrinkable += getRoom(component, widths[i]);
            }
            if (total > available && shrinkable > 0) {
                int excess = Math.min(total - available, shrinkable);
                int shrunk = 0;
                for (int i = 0; i < widths.length && shrunk < excess; i++) {
                    int room = getRoom(components.get(i), widths[i]);
                    int cut = Math.min(room, (int) Math.ceil((double) excess * room / shrinkable));
                    cut = Math.min(cut, excess - shrunk);
                    widths[i] -= cut;
                    shrunk += cut;
                }
                total -= shrunk;
            }
            boolean overflow = total > available;
            int limit = overflow ? available - overflowButton.getPreferredSize().width : available;
            int x = insets.left;
            int fitting = 0;
            for (int i = 0; i < widths.length && x + widths[i] - insets.left <= limit; i++) {
                x += widths[i];
                fitting++;
            }
            if (overflow) {
                // No separator right before the overflow button
                while (fitting > 0 && components.get(fitting - 1) instanceof JToolBar.Separator) {
                    fitting--;
                }
            }
            x = insets.left;
            for (int i = 0; i < widths.length; i++) {
                Component component = components.get(i);
                if (i >= fitting) {
                    component.setBounds(0, 0, 0, 0);
                    continue;
                }
                place(component, x, widths[i], insets.top, height);
                x += widths[i];
            }
            if (overflow) {
                int width = overflowButton.getPreferredSize().width;
                place(overflowButton, parent.getWidth() - insets.right - width, width, insets.top, height);
            } else {
                overflowButton.setBounds(0, 0, 0, 0);
            }
        }

        /**
         * How much narrower than its preferred width a component may get: only components made shrinkable.
         */
        private int getRoom(@NotNull Component component, int width) {
            return component instanceof JComponent c && Boolean.TRUE.equals(c.getClientProperty(SHRINKABLE_KEY))
                ? Math.max(0, width - component.getMinimumSize().width) : 0;
        }

        private void place(@NotNull Component component, int x, int width, int top, int height) {
            if (component instanceof JToolBar.Separator) {
                component.setBounds(x, top, width, height);
                return;
            }
            int componentHeight = Math.min(height, component.getPreferredSize().height);
            component.setBounds(x, top + (height - componentHeight) / 2, width, componentHeight);
        }

        @NotNull
        private List<Component> getLaidOutComponents() {
            List<Component> components = new ArrayList<>();
            for (Component component : getComponents()) {
                if (component != overflowButton && component.isVisible()) {
                    components.add(component);
                }
            }
            return components;
        }
    }
}
