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
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import java.awt.*;

/**
 * SQL Editor 도구 막대 맨 앞의 쿼리 창 서버 표시(앱 변경 #69). 예전의 연결 콤보를 대신한다.
 *
 * 설계:
 * <ul>
 *     <li>서버 이름은 고를 수 없는 표시다(사용자 결정 2026-11-12: "제거 해서 연결된 세션은 그 서버의 세션으로 두는게 맞다고 생각된다").
 *     연결 콤보는 클릭이나 방향키 하나로 같은 글을 다른 서버에서 돌게 했다. 사고를 막는 것이 제품의 목적이라 이름 쪽은 고를 수 없는 표시다:
 *     드라이버 아이콘과 연결 이름, 이름을 감싸는 연결 색 알약({@link ConnectionPill}: Object Explorer·Registered Servers와 같은 색과 모양),
 *     도구 설명에 전체 이름과 서버 주소(문서 탭의 도구 설명과 같은 글). 라벨이라 포커스를 받지 않고 키보드·마우스로 바뀌지 않는다.
 *     콤보 테두리와 화살표가 없어 꺼진 콤보처럼 보이지 않고 도구 막대의 글자처럼 보인다.</li>
 *     <li>연결 변경 단추(앱 변경 #73, 사용자 결정 2026-11-12: "ssms도 보니까 연결 버튼과 연결변경 버튼이 있다"): 서버가 있는 창의 표시 오른쪽 끝에
 *     아이콘 단추 하나를 둔다. 창의 서버는 이 명시적인 명령(목록에서 고르고 "변경")으로만 바뀐다({@link SqlEditorPanel#changeConnection}).
 *     단추는 메인 창의 Query &gt; Change Connection… 동작 하나를 쓰고 도구 막대가 활성 창의 표시에 끼운다({@link #setChangeButton}):
 *     켜짐(실행·계획 중 꺼짐)과 도구 설명이 메뉴와 같다. 자리는 고정이라 이름 길이에 따라 움직이지 않고, 좁으면 이름이 먼저 말줄임표로 준다.</li>
 *     <li>서버가 없는 창(서버를 고르지 않고 연 파일, 연결이 지워진 복원 창 등)은 같은 자리에 "서버 선택" 단추를 둔다.
 *     누르면 창이 서버를 한 번 고르고({@link SqlEditorPanel#chooseServer}), 고른 뒤에는 표시로 바뀐다. 이 단추가 그 창의 "연결"이라
 *     연결 변경 단추는 두지 않는다.</li>
 *     <li>폭은 예전 연결 콤보와 같다({@link #WIDTH}, 좁은 창에서는 DB/스키마 콤보와 함께 {@link SqlEditorPanel#PICKER_MIN_WIDTH}까지 준다).
 *     긴 이름은 라벨의 말줄임표로 줄고, 알약은 보이는 글자만 감싼다. 연결 변경 단추는 폭 안에 있다.</li>
 *     <li>쿼리 창마다 하나이고, 메인 창이 활성 창의 것을 도구 막대에 끼운다(DB/스키마 콤보와 같은 방식, {@link SqlEditorToolBar}).
 *     쿼리 창이 없을 때 끼우는 빈 표시는 고를 동작이 없는 같은 폭의 빈 칸이다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: 연결 콤보를 고를 수 없는 서버 표시로 바꿈(앱 변경 #69) (docs/chromite/swing-frontend.md "쿼리 창은 한 서버의 창")
 * - 2026-11-12 라벨이 알약이 글자 줄 밖으로 넘치는 만큼 위아래 여백을 둠: 글자 높이뿐이라 운영 알약의 아래 테두리가 잘렸다(앱 변경 #71 a)
 * - 2026-11-12 서버가 있는 창의 표시 오른쪽 끝에 연결 변경 단추({@link #setChangeButton}), 도구 설명의 "바뀌지 않습니다"를 바꾸는 방법으로
 *   (앱 변경 #73) (docs/chromite/swing-frontend.md "쿼리 창의 연결 변경")
 */
final class QueryWindowServerIndicator extends JPanel {

    /**
     * Width of the indicator, the one of the connection picker it replaces (user decision 2026-11-08: wider than the
     * database/schema picker).
     */
    static final int WIDTH = 220;
    // Space before the icon and between the icon and the name (unscaled)
    private static final int PADDING_LEFT = 6;
    private static final int ICON_TEXT_GAP = 4;

    private final ServerLabel label = new ServerLabel();
    @Nullable
    private final JButton chooseButton;
    // The tool bar's Change Connection button while this window is the active one, else null
    @Nullable
    private JButton changeButton;
    @Nullable
    private DBPDataSourceContainer container;

    /**
     * @param chooseServer what the "Select Server" button of a window without a server does, or null for the empty
     *                     indicator shown while no query window is active
     */
    QueryWindowServerIndicator(@Nullable Runnable chooseServer) {
        super(new BorderLayout());
        setOpaque(false);
        // Not an input: the Tab key goes from the document to the database/schema picker
        setFocusable(false);
        if (chooseServer != null) {
            chooseButton = new JButton(EditorMessages.editor_sql_select_server, UiIcons.get("connection")); //$NON-NLS-1$
            chooseButton.setToolTipText(EditorMessages.editor_sql_select_server_tip);
            chooseButton.putClientProperty(FlatClientProperties.STYLE, "arc: 6"); //$NON-NLS-1$
            chooseButton.setFocusable(false);
            chooseButton.addActionListener(e -> chooseServer.run());
        } else {
            chooseButton = null;
        }
        setServer(null);
        // Narrow main window: gives way like the database/schema picker (the minimum width comes from getMinimumSize)
        OverflowToolBar.setShrinkable(this, SqlEditorPanel.PICKER_MIN_WIDTH);
    }

    /**
     * Puts the tool bar's Change Connection button at the right end (the window became the active one), or takes it out
     * (null: another window became active). It shows only while the window has a server.
     */
    void setChangeButton(@Nullable JButton button) {
        if (changeButton != button) {
            changeButton = button;
            setServer(container);
        }
    }

    /**
     * Shows the window's server with the Change Connection button after it, or the "Select Server" button while it has
     * none.
     */
    void setServer(@Nullable DBPDataSourceContainer container) {
        this.container = container;
        removeAll();
        if (container != null) {
            label.setServer(container);
            // The name gives way (ellipsis) before the button, which keeps its place at the right end
            add(label, BorderLayout.CENTER);
            if (changeButton != null) {
                add(changeButton, BorderLayout.EAST);
            }
        } else if (chooseButton != null) {
            add(chooseButton, BorderLayout.WEST);
        }
        revalidate();
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Called by the super constructor before the fields are set
        if (label == null) {
            return;
        }
        // The state that does not show is out of the tree and misses the theme switch
        if (chooseButton != null && container != null) {
            SwingUtilities.updateComponentTreeUI(chooseButton);
        } else if (container == null) {
            SwingUtilities.updateComponentTreeUI(label);
        }
        if (container != null) {
            // The pill's colour and the text colour follow the theme
            label.setServer(container);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        // A fixed width in the pickers' units (SqlEditorPanel.sizePicker), so that the tool bar does not move between
        // windows; the height of what shows (the name and the Change Connection button beside it)
        int height = getComponentCount() > 0 ? 0 : label.getPreferredSize().height;
        for (Component component : getComponents()) {
            height = Math.max(height, component.getPreferredSize().height);
        }
        return new Dimension(WIDTH, height);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(SqlEditorPanel.PICKER_MIN_WIDTH, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    /**
     * Driver icon and connection name, the name in the connection's colour pill when it has a colour. The name ends in
     * an ellipsis when it does not fit, and the pill wraps the text that shows.
     */
    private static final class ServerLabel extends JLabel {
        @Nullable
        private ConnectionPill pill;

        ServerLabel() {
            setFocusable(false);
            setBorder(new PillSpace());
        }

        void setServer(@NotNull DBPDataSourceContainer container) {
            setText(container.getName());
            setIcon(NodeIcons.getIcon(container.getDriver().getIcon()));
            pill = ConnectionPill.of(container);
            Color foreground = UIManager.getColor("Label.foreground"); //$NON-NLS-1$
            setForeground(pill != null && foreground != null ? pill.getTextColor(foreground) : foreground);
            // The pill's padding: before the name in the icon-text gap, after it in the border (PillSpace), so the pill
            // stays inside
            setIconTextGap(UIScale.scale(ICON_TEXT_GAP) + (pill != null ? ConnectionPill.getPaddingX() : 0));
            setToolTipText("<html>" + CommonUtils.escapeHtml(NLS.bind(EditorMessages.editor_sql_tip_connection, container.getName(), //$NON-NLS-1$
                QueryStatusLine.describeServerDetails(container))) + "<br>" //$NON-NLS-1$
                + CommonUtils.escapeHtml(EditorMessages.editor_sql_server_change_tip) + "</html>"); //$NON-NLS-1$
        }

        @Override
        protected void paintComponent(Graphics g) {
            int baseline = getBaseline(getWidth(), getHeight());
            if (pill != null && baseline >= 0) {
                // Where the label puts the text, shortened with an ellipsis when it does not fit
                Insets insets = getInsets();
                Rectangle view = new Rectangle(insets.left, insets.top, getWidth() - insets.left - insets.right,
                    getHeight() - insets.top - insets.bottom);
                Rectangle iconBounds = new Rectangle();
                Rectangle textBounds = new Rectangle();
                SwingUtilities.layoutCompoundLabel(this, getFontMetrics(getFont()), getText(), getIcon(), getVerticalAlignment(),
                    getHorizontalAlignment(), getVerticalTextPosition(), getHorizontalTextPosition(), view, iconBounds, textBounds,
                    getIconTextGap());
                pill.paint(g, this, textBounds.x, textBounds.width, baseline);
            }
            super.paintComponent(g);
        }

        /**
         * Space before the icon, the pill's padding after the name, and above and below the text line as much as the pill
         * reaches past it ({@link ConnectionPill#getOverhang}): the label is otherwise only as high as its text and the
         * pill's bottom line is cut. The same space on both sides keeps the name in the middle of the tool bar. Read from the
         * current font whenever the label is measured, so a theme or density change keeps the pill whole.
         */
        private final class PillSpace extends AbstractBorder {
            @Override
            public Insets getBorderInsets(Component c, Insets insets) {
                Font font = getFont();
                Insets overhang = pill != null && font != null ? ConnectionPill.getOverhang(ServerLabel.this, font) : null;
                int vertical = overhang != null ? Math.max(overhang.top, overhang.bottom) : 0;
                insets.set(vertical, UIScale.scale(PADDING_LEFT), vertical, ConnectionPill.getPaddingX());
                return insets;
            }
        }
    }
}
