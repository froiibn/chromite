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
package com.seizia.chromite.ui.swing.update;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The update window: a small frameless window of its own, apart from the main window, that draws an
 * {@link UpdateView.View}.
 *
 * 설계: Turbrix #747 {@code electron/updateWindow.cjs}와 쪽({@code src/update/update.css}, 스타일 가이드 5.15절)을 Swing으로 옮겼다
 * (docs/chromite/auto-update.md "업데이트 진행 창").
 * <ul>
 *     <li>폭 440(화면 배율·글자 배율은 {@link UIScale}), 높이는 내용에 맞춤, 테두리 없음({@code setUndecorated}), 크기 조절 없음.
 *     가장자리는 창이 1px 선으로 그린다. 위 줄(상징·이름·버전)을 끌면 창이 움직인다.</li>
 *     <li>색과 글꼴은 설치된 Look and Feel(Chromite 라이트·다크)의 값만 쓴다. 그릴 때마다 {@link UIManager}에서 읽으므로 실행 중에
 *     테마가 바뀌어도(첫 시작에서 저장된 테마를 설치할 때) 따라간다.</li>
 *     <li>진행 중에는 창을 닫을 수 없다(Alt+F4 포함). 단추가 있으면 창 닫기와 Esc는 그 상태의 Esc 단추({@link UpdateView.View#escape})다.
 *     상태가 바뀌면 첫 단추가 포커스를 받고, 주 단추가 Enter 단추다. 같은 상태의 새 값(걸린 시간)은 포커스를 옮기지 않는다.</li>
 *     <li>움직임은 둘(길이를 모르는 막대, 지금 단계의 도는 표시)이다.</li>
 *     <li>작업 표시줄에 "Chromite 업데이트"와 Chromite 아이콘으로 보인다(주 창이 없을 때 홀로 뜨는 창이다).</li>
 * </ul>
 * 앱의 사본에서도 돌므로 {@code com.seizia.chromite.Log}, 설정 저장소, {@code ChromiteIcons}를 쓰지 않는다(그 클래스들은 OSGi와
 * 모델 번들이 있어야 뜬다). 아이콘은 같은 번들의 {@code icons/chromite<크기>.png}를 직접 읽는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 업데이트 진행 창 (docs/chromite/auto-update.md "업데이트 진행 창")
 */
public final class UpdateWindow {

    private static final int WINDOW_WIDTH = 440;
    private static final int[] ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};
    private static final String ICON_PATH = "/icons/chromite%d.png"; //$NON-NLS-1$
    private static final String ESCAPE_KEY = "chromite.update.escape"; //$NON-NLS-1$
    private static final int SPIN_MS = 40;
    /**
     * Room left at the end of a line of the sentence (the text area's caret).
     */
    private static final int WRAP_SLACK = 4;

    // Look and feel keys (Chromite light and dark themes)
    private static final String C_BORDER = "Component.borderColor"; //$NON-NLS-1$
    private static final String C_SURFACE = "Panel.background"; //$NON-NLS-1$
    private static final String C_FOOT = "Chromite.PropertyGrid.categoryBackground"; //$NON-NLS-1$
    private static final String C_SECONDARY = "Chromite.secondaryForeground"; //$NON-NLS-1$
    private static final String C_MUTED = "Label.disabledForeground"; //$NON-NLS-1$
    private static final String C_ACCENT = "ProgressBar.foreground"; //$NON-NLS-1$
    private static final String C_SUCCESS = "Chromite.status.success"; //$NON-NLS-1$
    private static final String C_DANGER = "Chromite.status.error"; //$NON-NLS-1$
    private static final String STYLE_SECONDARY = "foreground: $Chromite.secondaryForeground"; //$NON-NLS-1$

    private final JFrame frame;
    private final Consumer<UpdateView.Action> onAction;
    @NotNull
    private UpdateView.View view;
    @Nullable
    private UpdateView.State shownState;

    private final JLabel nameLabel = new JLabel();
    private final JLabel fromLabel = new JLabel();
    private final JLabel arrowLabel = new JLabel("→"); //$NON-NLS-1$
    private final JLabel toLabel = new JLabel();
    private final JLabel headIcon = new JLabel();
    private final JLabel headLabel = new JLabel();
    /**
     * Its lines are made by {@link #wrap}. Never wider than the body, neither preferred nor minimum: a child wider than
     * the body makes the grid bag layout squeeze every row. The text area adds the caret's width to its longest line
     * ({@code BasicTextUI}), hence {@link #WRAP_SLACK}.
     */
    private final JTextArea subText = new JTextArea() {
        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(Math.min(d.width, innerWidth()), d.height);
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension d = super.getMinimumSize();
            return new Dimension(Math.min(d.width, innerWidth()), d.height);
        }
    };
    private final JProgressBar bar = new JProgressBar(0, 100);
    private final JLabel metaLeft = new JLabel();
    private final JLabel metaRight = new JLabel();
    private final JPanel metaPanel = new JPanel(new BorderLayout());
    private final StepsPanel stepsPanel = new StepsPanel();
    private final JLabel noteLabel = new JLabel();
    private final JButton linkButton = new JButton();
    /**
     * The buttons in rows, right-aligned: a row that would be wider than the window goes on below (English
     * "Go back to the previous version" beside two more buttons does not fit in 440 px).
     */
    private final JPanel buttonsPanel = new JPanel();
    private final List<JButton> buttons = new ArrayList<>();
    private final Timer spinner;

    /**
     * Makes the window (not shown yet). Call on the EDT.
     *
     * @param onAction a pressed button: only one the window shows now, on the EDT
     */
    public UpdateWindow(@NotNull UpdateView.View view, @NotNull Consumer<UpdateView.Action> onAction) {
        this.view = view;
        this.onAction = onAction;
        frame = new JFrame(view.name());
        frame.setUndecorated(true);
        frame.setResizable(false);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setIconImages(loadIcons());
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // While the update goes on the window stays; a state with buttons is closed as its Esc button
                escape();
            }
        });
        frame.setContentPane(buildContent());
        JRootPane root = frame.getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ESCAPE_KEY);
        root.getActionMap().put(ESCAPE_KEY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                escape();
            }
        });
        spinner = new Timer(SPIN_MS, e -> stepsPanel.repaint());
        apply(view);
    }

    /**
     * Shows the window at {@code position} (the top left corner, kept on a screen), or centered on {@code over}.
     */
    public void open(@Nullable Point position, @Nullable Component over) {
        frame.pack();
        if (position != null) {
            frame.setLocation(onScreen(position, frame.getSize()));
        } else {
            frame.setLocationRelativeTo(over);
        }
        frame.setVisible(true);
        focusFirstButton();
    }

    /**
     * Shows another state, or new values of the same one.
     */
    public void push(@NotNull UpdateView.View next) {
        view = next;
        apply(next);
        if (frame.isDisplayable()) {
            Point at = frame.getLocation();
            frame.pack();
            frame.setLocation(at);
        }
    }

    @NotNull
    public UpdateView.View getView() {
        return view;
    }

    @NotNull
    public JFrame getFrame() {
        return frame;
    }

    public boolean isOpen() {
        return frame.isDisplayable();
    }

    @NotNull
    public Point getLocation() {
        return frame.getLocation();
    }

    /**
     * Keeps the window over other windows (the main window opening under the "done" state).
     */
    public void setOnTop(boolean onTop) {
        frame.setAlwaysOnTop(onTop);
    }

    public void toFront() {
        if (frame.getExtendedState() == Frame.ICONIFIED) {
            frame.setExtendedState(Frame.NORMAL);
        }
        frame.toFront();
        focusFirstButton();
    }

    public void close() {
        spinner.stop();
        frame.dispose();
    }

    private void escape() {
        UpdateView.Action escape = view.escape();
        if (view.closable() && escape != null) {
            onAction.accept(escape);
        }
    }

    // ---- building

    @NotNull
    private JPanel buildContent() {
        JPanel content = new EdgePanel();
        content.setLayout(new BorderLayout());

        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(20), UIScale.scale(24), UIScale.scale(22), UIScale.scale(24)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;

        // The top line: the symbol, the name, the versions; dragging it moves the window
        JPanel top = new JPanel(new BorderLayout(UIScale.scale(8), 0));
        top.setOpaque(false);
        nameLabel.setIcon(new ImageIcon(symbol()));
        nameLabel.setIconTextGap(UIScale.scale(8));
        nameLabel.putClientProperty(FlatClientProperties.STYLE, STYLE_SECONDARY + "; font: $semibold.font"); //$NON-NLS-1$
        fromLabel.putClientProperty(FlatClientProperties.STYLE, STYLE_SECONDARY);
        arrowLabel.putClientProperty(FlatClientProperties.STYLE, STYLE_SECONDARY);
        toLabel.putClientProperty(FlatClientProperties.STYLE, "font: $semibold.font"); //$NON-NLS-1$
        JPanel versions = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIScale.scale(5), 0));
        versions.setOpaque(false);
        versions.add(fromLabel);
        versions.add(arrowLabel);
        versions.add(toLabel);
        top.add(nameLabel, BorderLayout.WEST);
        top.add(versions, BorderLayout.EAST);
        MouseAdapter drag = new MouseAdapter() {
            private Point grab;

            @Override
            public void mousePressed(MouseEvent e) {
                grab = e.getPoint();
                SwingUtilities.convertPointToScreen(grab, e.getComponent());
                Point at = frame.getLocation();
                grab.translate(-at.x, -at.y);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (grab != null) {
                    Point p = e.getLocationOnScreen();
                    frame.setLocation(p.x - grab.x, p.y - grab.y);
                }
            }
        };
        for (JComponent part : new JComponent[]{top, nameLabel, versions, fromLabel, arrowLabel, toLabel}) {
            part.addMouseListener(drag);
            part.addMouseMotionListener(drag);
        }
        c.gridy = 0;
        body.add(top, c);

        // What happens now: the heading (with a mark when done or failed) and one sentence
        JPanel head = new JPanel(new BorderLayout(UIScale.scale(8), 0));
        head.setOpaque(false);
        headLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h2"); //$NON-NLS-1$
        headIcon.setVerticalAlignment(SwingConstants.TOP);
        head.add(headIcon, BorderLayout.WEST);
        head.add(headLabel, BorderLayout.CENTER);
        c.gridy = 1;
        c.insets = new Insets(UIScale.scale(22), 0, 0, 0);
        body.add(head, c);

        subText.setEditable(false);
        subText.setFocusable(false);
        subText.setOpaque(false);
        subText.setBorder(BorderFactory.createEmptyBorder());
        subText.setMargin(new Insets(0, 0, 0, 0));
        subText.putClientProperty(FlatClientProperties.STYLE, STYLE_SECONDARY);
        c.gridy = 2;
        c.insets = new Insets(UIScale.scale(6), 0, 0, 0);
        body.add(subText, c);

        bar.putClientProperty(FlatClientProperties.STYLE, "arc: 6"); //$NON-NLS-1$
        bar.setPreferredSize(new Dimension(UIScale.scale(100), UIScale.scale(6)));
        c.gridy = 3;
        c.insets = new Insets(UIScale.scale(20), 0, 0, 0);
        body.add(bar, c);

        metaPanel.setOpaque(false);
        metaLeft.putClientProperty(FlatClientProperties.STYLE, STYLE_SECONDARY);
        metaRight.putClientProperty(FlatClientProperties.STYLE, STYLE_SECONDARY);
        metaPanel.add(metaLeft, BorderLayout.WEST);
        metaPanel.add(metaRight, BorderLayout.EAST);
        c.gridy = 4;
        c.insets = new Insets(UIScale.scale(8), 0, 0, 0);
        body.add(metaPanel, c);

        c.gridy = 5;
        c.insets = new Insets(UIScale.scale(20), 0, 0, 0);
        body.add(stepsPanel, c);

        // The bottom line: a quiet note or a link on the left, the buttons on the right
        JPanel foot = new FootPanel();
        foot.setLayout(new BorderLayout(UIScale.scale(8), 0));
        foot.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(24), UIScale.scale(12), UIScale.scale(24)));
        noteLabel.putClientProperty(FlatClientProperties.STYLE, STYLE_SECONDARY);
        linkButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        linkButton.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.linkColor; margin: 0,0,0,0"); //$NON-NLS-1$
        linkButton.addActionListener(e -> {
            if (view.link() != null) {
                onAction.accept(view.link());
            }
        });
        buttonsPanel.setOpaque(false);
        buttonsPanel.setLayout(new BoxLayout(buttonsPanel, BoxLayout.Y_AXIS));
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(noteLabel, BorderLayout.CENTER);
        left.add(linkButton, BorderLayout.WEST);
        foot.add(left, BorderLayout.CENTER);
        foot.add(buttonsPanel, BorderLayout.EAST);

        content.add(body, BorderLayout.CENTER);
        content.add(foot, BorderLayout.SOUTH);
        return content;
    }

    private void apply(@NotNull UpdateView.View v) {
        frame.setTitle(v.name());
        frame.getAccessibleContext().setAccessibleName(v.name());
        nameLabel.setText(v.name());
        fromLabel.setText(v.from());
        fromLabel.setVisible(!v.from().isEmpty());
        arrowLabel.setVisible(!v.from().isEmpty());
        toLabel.setText(v.to());
        // The arrow is decoration: a screen reader reads "from {0} to {1}"
        toLabel.getAccessibleContext().setAccessibleName(v.versionsSpoken());

        headLabel.setText(v.head());
        UpdateView.Mark headMark = v.state() == UpdateView.State.DONE ? UpdateView.Mark.DONE
            : v.state().isFailed() ? UpdateView.Mark.FAILED : null;
        headIcon.setIcon(headMark != null ? new MarkIcon(headMark, 22) : null);
        headIcon.setVisible(headMark != null);
        // The text area takes the text area font of the theme (a theme change resets it): the label font, as the rest
        Font font = UIManager.getFont("Label.font"); //$NON-NLS-1$
        subText.setFont(font);
        subText.setText(wrap(v.sub(), subText.getFontMetrics(font), innerWidth() - UIScale.scale(WRAP_SLACK)));

        bar.setVisible(v.bar() != UpdateView.Bar.NONE);
        bar.setIndeterminate(v.bar() == UpdateView.Bar.BUSY);
        bar.setValue(v.barValue());
        bar.getAccessibleContext().setAccessibleName(v.barLabel());
        metaPanel.setVisible(v.metaLeft() != null);
        metaLeft.setText(v.metaLeft() != null && !v.metaLeft().isEmpty() ? v.metaLeft() : " "); //$NON-NLS-1$
        metaRight.setText(v.metaRight());

        stepsPanel.show(v.steps());
        boolean spinning = v.steps().stream().anyMatch(s -> s.mark() == UpdateView.Mark.CURRENT);
        if (spinning && !spinner.isRunning()) {
            spinner.start();
        } else if (!spinning) {
            spinner.stop();
        }

        noteLabel.setText(v.note() != null ? v.note() : ""); //$NON-NLS-1$
        noteLabel.setVisible(v.note() != null);
        linkButton.setText(v.link() != null ? v.link().label() : ""); //$NON-NLS-1$
        linkButton.setVisible(v.link() != null);

        if (v.state() != shownState) {
            // A new state: its buttons, and the focus on the first one
            shownState = v.state();
            buttonsPanel.removeAll();
            buttons.clear();
            int gap = UIScale.scale(8);
            JPanel row = null;
            int rowWidth = 0;
            for (UpdateView.Action action : v.buttons()) {
                JButton b = new JButton(action.label());
                b.addActionListener(e -> {
                    if (view.buttons().contains(action)) {
                        onAction.accept(action);
                    }
                });
                int width = b.getPreferredSize().width;
                if (row == null || rowWidth + gap + width > innerWidth()) {
                    if (row != null) {
                        buttonsPanel.add(Box.createVerticalStrut(gap));
                    }
                    row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                    row.setOpaque(false);
                    row.setAlignmentX(Component.RIGHT_ALIGNMENT);
                    buttonsPanel.add(row);
                    rowWidth = 0;
                } else {
                    row.add(Box.createHorizontalStrut(gap));
                    rowWidth += gap;
                }
                row.add(b);
                rowWidth += width;
                buttons.add(b);
            }
            frame.getRootPane().setDefaultButton(v.primary() && !buttons.isEmpty() ? buttons.get(0) : null);
            buttonsPanel.revalidate();
            if (frame.isVisible()) {
                focusFirstButton();
            }
        }
        frame.getContentPane().revalidate();
        frame.getContentPane().repaint();
    }

    private void focusFirstButton() {
        if (!buttons.isEmpty()) {
            buttons.get(0).requestFocusInWindow();
        }
    }

    /**
     * Breaks the text into lines at spaces. The text area's own word wrap breaks Korean words between syllables
     * ("다/시"); Korean text is laid out with breaks between words, as the approved design shows it. Only a word longer
     * than a line (a path in a start error) is broken between characters.
     */
    @NotNull
    static String wrap(@NotNull String text, @NotNull FontMetrics metrics, int width) {
        StringBuilder out = new StringBuilder();
        for (String paragraph : text.split("\n", -1)) { //$NON-NLS-1$
            if (!out.isEmpty()) {
                out.append('\n');
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) { //$NON-NLS-1$
                if (!line.isEmpty() && metrics.stringWidth(line + " " + word) > width) { //$NON-NLS-1$
                    out.append(line).append('\n');
                    line.setLength(0);
                }
                if (!line.isEmpty()) {
                    line.append(' ');
                }
                for (int i = 0; i < word.length(); i++) {
                    if (!line.isEmpty() && metrics.stringWidth(line.toString() + word.charAt(i)) > width) {
                        out.append(line).append('\n');
                        line.setLength(0);
                    }
                    line.append(word.charAt(i));
                }
            }
            out.append(line);
        }
        return out.toString();
    }

    private static int innerWidth() {
        return UIScale.scale(WINDOW_WIDTH) - 2 - UIScale.scale(48);
    }

    @NotNull
    private static Point onScreen(@NotNull Point p, @NotNull Dimension size) {
        for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = device.getDefaultConfiguration().getBounds();
            if (screen.contains(p)) {
                int x = Math.max(screen.x, Math.min(p.x, screen.x + screen.width - size.width));
                int y = Math.max(screen.y, Math.min(p.y, screen.y + screen.height - size.height));
                return new Point(x, y);
            }
        }
        Rectangle main = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration().getBounds();
        return new Point(main.x + (main.width - size.width) / 2, main.y + (main.height - size.height) / 2);
    }

    @NotNull
    private static List<Image> loadIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : ICON_SIZES) {
            Image image = readIcon(size);
            if (image != null) {
                icons.add(image);
            }
        }
        return icons;
    }

    /**
     * The 16 px symbol of the top line, with its larger sizes for scaled screens.
     */
    @NotNull
    private static Image symbol() {
        List<Image> images = new ArrayList<>();
        for (int size : new int[]{16, 20, 24, 32}) {
            Image image = readIcon(size);
            if (image != null) {
                images.add(image);
            }
        }
        return images.isEmpty() ? new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            : new BaseMultiResolutionImage(images.toArray(new Image[0]));
    }

    @Nullable
    private static Image readIcon(int size) {
        try (InputStream in = UpdateWindow.class.getResourceAsStream(String.format(ICON_PATH, size))) {
            return in != null ? ImageIO.read(in) : null;
        } catch (IOException e) {
            return null;
        }
    }

    @NotNull
    private static Color color(@NotNull String key, @NotNull Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    // ---- parts

    /**
     * The window's surface and its 1 px edge (a frameless window has no frame of the operating system).
     */
    private static final class EdgePanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(color(C_SURFACE, Color.WHITE));
            g.fillRect(0, 0, getWidth(), getHeight());
        }

        @Override
        protected void paintBorder(Graphics g) {
            g.setColor(color(C_BORDER, Color.GRAY));
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        }

        @Override
        public Insets getInsets() {
            return new Insets(1, 1, 1, 1);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(UIScale.scale(WINDOW_WIDTH), d.height);
        }
    }

    /**
     * The bottom line: the second surface, a line above it, at least 57 px high.
     */
    private static final class FootPanel extends JPanel {
        FootPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(color(C_FOOT, Color.LIGHT_GRAY));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(color(C_BORDER, Color.GRAY));
            g.drawLine(0, 0, getWidth(), 0);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            return new Dimension(d.width, Math.max(d.height, UIScale.scale(57)));
        }
    }

    /**
     * The five steps: a mark, the name and the time of each, the marks joined by a line (a sequence).
     */
    private final class StepsPanel extends JPanel {
        private final JLabel[] marks = new JLabel[UpdateView.STEP_COUNT];
        private final JLabel[] names = new JLabel[UpdateView.STEP_COUNT];
        private final JLabel[] times = new JLabel[UpdateView.STEP_COUNT];
        private List<UpdateView.Step> steps = List.of();

        StepsPanel() {
            super(new GridBagLayout());
            setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = 0;
            for (int i = 0; i < UpdateView.STEP_COUNT; i++) {
                marks[i] = new JLabel();
                names[i] = new JLabel();
                times[i] = new JLabel();
                times[i].putClientProperty(FlatClientProperties.STYLE, STYLE_SECONDARY);
                int rowHeight = UIScale.scale(26);
                c.gridy = i;
                c.gridx = 0;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                c.anchor = GridBagConstraints.CENTER;
                c.ipady = rowHeight - UIScale.scale(16);
                add(marks[i], c);
                c.gridx = 1;
                c.weightx = 1;
                c.ipady = 0;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.insets = new Insets(0, UIScale.scale(10), 0, UIScale.scale(10));
                add(names[i], c);
                c.gridx = 2;
                c.weightx = 0;
                c.fill = GridBagConstraints.NONE;
                c.insets = new Insets(0, 0, 0, 0);
                c.anchor = GridBagConstraints.EAST;
                add(times[i], c);
            }
        }

        void show(@NotNull List<UpdateView.Step> next) {
            steps = next;
            for (int i = 0; i < next.size() && i < UpdateView.STEP_COUNT; i++) {
                UpdateView.Step step = next.get(i);
                marks[i].setIcon(new MarkIcon(step.mark(), 16));
                names[i].setText(step.label());
                // The state is said in words too, not by color only
                names[i].getAccessibleContext().setAccessibleName(step.label() + " (" + step.spoken() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                String style = switch (step.mark()) {
                    case DONE -> STYLE_SECONDARY;
                    case CURRENT -> "foreground: $Label.foreground; font: $semibold.font"; //$NON-NLS-1$
                    case WAITING -> "foreground: $Label.disabledForeground"; //$NON-NLS-1$
                    case FAILED -> "foreground: $Chromite.status.error; font: $semibold.font"; //$NON-NLS-1$
                };
                names[i].putClientProperty(FlatClientProperties.STYLE, style);
                times[i].setText(step.time());
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setStroke(new BasicStroke(1f));
                for (int i = 1; i < steps.size() && i < UpdateView.STEP_COUNT; i++) {
                    Rectangle a = marks[i - 1].getBounds();
                    Rectangle b = marks[i].getBounds();
                    int x = a.x + a.width / 2;
                    int half = UIScale.scale(8);
                    int y1 = a.y + a.height / 2 + half + UIScale.scale(2);
                    int y2 = b.y + b.height / 2 - half - UIScale.scale(2);
                    if (y2 > y1) {
                        g2.setColor(steps.get(i - 1).mark() == UpdateView.Mark.DONE ? color(C_SUCCESS, Color.GREEN) : color(C_BORDER, Color.GRAY));
                        g2.drawLine(x, y1, x, y2);
                    }
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * The mark of a step (and of the heading): a check in a circle, a turning arc, an empty circle, a cross in a circle.
     */
    private static final class MarkIcon implements Icon {
        private final UpdateView.Mark mark;
        private final int size;

        MarkIcon(@NotNull UpdateView.Mark mark, int size) {
            this.mark = mark;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                double s = UIScale.scale((float) size);
                double w = s / 8;
                g2.translate(x, y);
                g2.setStroke(new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                double in = w / 2 + s / 16;
                Ellipse2D circle = new Ellipse2D.Double(in, in, s - 2 * in, s - 2 * in);
                switch (mark) {
                    case DONE -> {
                        g2.setColor(color(C_SUCCESS, Color.GREEN));
                        g2.draw(circle);
                        Path2D check = new Path2D.Double();
                        check.moveTo(s * 0.32, s * 0.52);
                        check.lineTo(s * 0.45, s * 0.65);
                        check.lineTo(s * 0.69, s * 0.38);
                        g2.draw(check);
                    }
                    case CURRENT -> {
                        g2.setColor(color(C_ACCENT, Color.BLUE));
                        double turn = (System.nanoTime() / 1_000_000L % 900) * 360.0 / 900;
                        g2.draw(new Arc2D.Double(in, in, s - 2 * in, s - 2 * in, -turn, 270, Arc2D.OPEN));
                    }
                    case WAITING -> {
                        g2.setColor(color(C_MUTED, Color.GRAY));
                        g2.draw(circle);
                    }
                    case FAILED -> {
                        g2.setColor(color(C_DANGER, Color.RED));
                        g2.draw(circle);
                        Path2D cross = new Path2D.Double();
                        cross.moveTo(s * 0.37, s * 0.37);
                        cross.lineTo(s * 0.63, s * 0.63);
                        cross.moveTo(s * 0.63, s * 0.37);
                        cross.lineTo(s * 0.37, s * 0.63);
                        g2.draw(cross);
                    }
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return UIScale.scale(size);
        }

        @Override
        public int getIconHeight() {
            return UIScale.scale(size);
        }
    }
}
