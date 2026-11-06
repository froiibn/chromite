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
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceInfo;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import java.awt.*;

/**
 * 쿼리 창 상태 줄(SSMS 쿼리 창 맨 아래 줄): 상태 아이콘과 문구 │ 서버 │ 사용자 │ DB/스키마 │ 경과 시간 │ 행 수 │ 커서 위치(Ln, Col).
 *
 * 설계:
 * <ul>
 *     <li>내용은 SSMS 상태 줄(상태, 서버(버전), 사용자, DB, 시간, 행)이고 모양은 DataGrip 상태 표시줄 값(design-spec "9. 쿼리 상태 줄"):
 *     높이 28, 위 1px border.subtle, 글자 text.secondary, 칸 사이 1px 구분선. 바탕은 Islands 카드 바탕이다(카드 안에 있으므로 사양의 bg.chrome 대신). 상태 아이콘 16은 아이콘 팩의 메시지 아이콘이고,
 *     실행 전에는 연결 아이콘(연결되어 있으면 초록 상태 점)이라 연결 상태를 보인다.</li>
 *     <li>서버는 호스트[:포트], 호스트가 없는 파일 DB는 파일 이름이다. 전체 경로·URL과 DB 제품 버전은 도구 설명에 둔다(SSMS의 "서버 (버전)"을 줄이지 않고 보이면 칸이 길어진다).
 *     빈 칸(사용자가 없는 SQLite, 기본 DB/스키마를 모르는 연결)은 숨긴다.</li>
 *     <li>커서 위치는 SSMS 창 아래 상태 표시줄의 Ln/Col이다(한 줄로 합쳐 쿼리 창 상태 줄 끝에 둔다).</li>
 *     <li>바탕은 연결 색({@link ConnectionColors}: 연결에 정한 색, 없으면 연결 종류의 색)이다. 색이 없는 연결(기본 Development)은 카드 바탕 그대로다.
 *     연결 설정이 바뀌면 편집기가 {@link #setConnection}을 다시 불러 색도 바뀐다.
 *     테마 글자 색이 띠와 4.5:1이 안 되면(다크 테마의 밝은 연결 지정 색 등) 줄의 모든 글자를 대비 색({@link ConnectionColors#getTextColor})으로 그린다.
 *     라벨에 색을 설정하지 않고 그릴 때 읽는 색을 바꾼다({@link BandLabel#getForeground}). 테마를 바꿀 때 부모의 {@code updateUI}가 자식보다 먼저 불려
 *     설정한 색이 자식의 테마 색으로 덮이기 때문이다. 같은 이유로 칸의 보조 글자 색도 FlatLaf 스타일({@code foreground: $Chromite.secondaryForeground}) 대신
 *     여기서 읽는다: FlatLaf 스타일은 리플렉션으로 {@code getForeground}를 부르는데 이 번들 밖에서 접근할 수 없는 클래스의 메서드라 스타일 적용이 실패한다.</li>
 *     <li>줄은 편집기마다 하나이고 메인 창이 Results 카드 아래쪽(쿼리 창 맨 아래, Results가 숨겨져 있으면 문서 카드 아래쪽)에 활성 편집기의 줄을 끼운다(결과와 같은 방식, {@link MainFrame}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 P1: 최초 작성. C단계 편집기 상태 줄(상태, 시간, 행)에 서버·사용자·DB와 상태 아이콘을 더하고 결과 아래로 옮김
 *   (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 커서 위치(Ln, Col) 칸 (docs/chromite/swing-sql-editor-plus.md)
 * - 2026-11-06 고급 연결 설정: 바탕을 연결 색으로 칠함 (docs/chromite/swing-conn-advanced.md)
 * - 2026-11-06 띠 위 글자가 읽히지 않으면 대비 색 (docs/chromite/swing-registered-servers.md "연결 색 알약")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 */
final class QueryStatusLine extends JPanel {

    private static final int HEIGHT = 28;

    /**
     * Query state of the window; it picks the icon.
     */
    enum State {
        READY, EXECUTING, SUCCESS, ERROR, CANCELED
    }

    private final JLabel stateLabel = new BandLabel(false);
    private final JLabel serverLabel = createSegment();
    private final JLabel userLabel = createSegment();
    private final JLabel databaseLabel = createSegment();
    private final JLabel elapsedLabel = createSegment();
    private final JLabel rowsLabel = createSegment();
    private final JLabel caretLabel = createSegment();
    private State state = State.READY;
    @Nullable
    private DBPDataSourceContainer container;
    /**
     * Text color of every label when the theme's text is unreadable on the connection color, else {@code null}.
     */
    @Nullable
    private Color bandForeground;

    QueryStatusLine() {
        super(new BorderLayout());
        stateLabel.setIconTextGap(UIScale.scale(6));
        stateLabel.setBorder(BorderFactory.createEmptyBorder(0, UIScale.scale(12), 0, UIScale.scale(12)));
        JPanel segments = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)) {
            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                return new Dimension(size.width, UIScale.scale(HEIGHT));
            }
        };
        segments.setOpaque(false);
        segments.add(serverLabel);
        segments.add(userLabel);
        segments.add(databaseLabel);
        segments.add(elapsedLabel);
        segments.add(rowsLabel);
        segments.add(caretLabel);
        add(stateLabel, BorderLayout.CENTER);
        add(segments, BorderLayout.EAST);
        setState(State.READY, EditorMessages.editor_status_ready);
        setElapsed(null);
        setRows(null);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(size.width, UIScale.scale(HEIGHT) + 1);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // bg.chrome with a border.subtle line above; recreated on theme changes because the colors come from the theme
        applyConnectionColor();
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, getLineColor()));
    }

    /**
     * The connection's color (custom or connection type) as the background, like the query window status bar
     * colored by a connection's custom color; the card background when the connection has none. Also picks the text
     * color of the labels on it ({@link #bandForeground}).
     */
    private void applyConnectionColor() {
        Color color = container == null ? null : ConnectionColors.getColor(container.getConnectionConfiguration());
        setBackground(color != null ? color : UIManager.getColor("Panel.background")); //$NON-NLS-1$
        Color foreground = UIManager.getColor("Label.foreground"); //$NON-NLS-1$
        Color text = color == null || foreground == null ? null : ConnectionColors.getTextColor(color, foreground);
        bandForeground = text == null || text.equals(foreground) ? null : text;
        repaint();
    }

    void setState(@NotNull State state, @NotNull String text) {
        this.state = state;
        stateLabel.setText(text);
        updateStateIcon();
    }

    /**
     * Connection of the window (server, user and the "ready" icon), or null when none is chosen.
     */
    void setConnection(@Nullable DBPDataSourceContainer container) {
        this.container = container;
        if (container == null) {
            setSegment(serverLabel, EditorMessages.editor_common_not_connected, null);
            setSegment(userLabel, null, null);
        } else {
            DBPConnectionConfiguration configuration = container.getConnectionConfiguration();
            setSegment(serverLabel, describeServer(container), describeServerDetails(container));
            setSegment(userLabel, CommonUtils.nullIfEmpty(configuration.getUserName()), EditorMessages.editor_status_user);
        }
        applyConnectionColor();
        updateStateIcon();
    }

    /**
     * Default database/schema of the connection, or null when unknown.
     */
    void setDatabase(@Nullable String name) {
        setSegment(databaseLabel, name, EditorMessages.editor_status_database);
    }

    void setElapsed(@Nullable String elapsed) {
        setSegment(elapsedLabel, elapsed, EditorMessages.editor_status_elapsed);
    }

    void setRows(@Nullable String rows) {
        setSegment(rowsLabel, rows, EditorMessages.editor_status_rows);
    }

    /**
     * Caret line and column (one-based), or negative values to hide them.
     */
    void setCaret(int line, int column) {
        setSegment(caretLabel, line < 0 ? null : NLS.bind(EditorMessages.editor_status_caret, line, column), EditorMessages.editor_status_caret_tip);
    }

    /**
     * Server of a connection for the status line and the tab tool tip: host[:port], or the file name of a file database.
     */
    @NotNull
    static String describeServer(@NotNull DBPDataSourceContainer container) {
        DBPConnectionConfiguration configuration = container.getConnectionConfiguration();
        String host = configuration.getHostName();
        if (!CommonUtils.isEmpty(host)) {
            return CommonUtils.isEmpty(configuration.getHostPort()) ? host : host + ":" + configuration.getHostPort(); //$NON-NLS-1$
        }
        String database = configuration.getDatabaseName();
        if (!CommonUtils.isEmpty(database)) {
            int separator = Math.max(database.lastIndexOf('/'), database.lastIndexOf('\\'));
            return database.substring(separator + 1);
        }
        return CommonUtils.notEmpty(configuration.getUrl());
    }

    /**
     * Full location (URL or file path) and, once connected, the database product and version (SSMS "server (version)").
     */
    @NotNull
    static String describeServerDetails(@NotNull DBPDataSourceContainer container) {
        DBPConnectionConfiguration configuration = container.getConnectionConfiguration();
        String location = !CommonUtils.isEmpty(configuration.getUrl()) ? configuration.getUrl()
            : !CommonUtils.isEmpty(configuration.getHostName()) ? describeServer(container)
            : CommonUtils.notEmpty(configuration.getDatabaseName());
        DBPDataSource dataSource = container.isConnected() ? container.getDataSource() : null;
        DBPDataSourceInfo info = dataSource != null ? dataSource.getInfo() : null;
        if (info == null) {
            return location + " (" + container.getDriver().getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return location + " (" + info.getDatabaseProductName() + " " + info.getDatabaseProductVersion() + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private void updateStateIcon() {
        stateLabel.setIcon(switch (state) {
            case READY -> container != null && container.isConnected()
                ? UiIcons.withStatusDot(UiIcons.get("connection"), UiIcons.Status.CONNECTED) //$NON-NLS-1$
                : UiIcons.get("connection"); //$NON-NLS-1$
            case EXECUTING -> UiIcons.get("run"); //$NON-NLS-1$
            case SUCCESS -> UiIcons.get("check"); //$NON-NLS-1$
            case ERROR -> UiIcons.get("error"); //$NON-NLS-1$
            case CANCELED -> UiIcons.get("warning"); //$NON-NLS-1$
        });
    }

    private static void setSegment(@NotNull JLabel segment, @Nullable String text, @Nullable String toolTip) {
        segment.setText(CommonUtils.notEmpty(text));
        segment.setToolTipText(CommonUtils.isEmpty(text) ? null : toolTip);
        segment.setVisible(!CommonUtils.isEmpty(text));
    }

    @NotNull
    private JLabel createSegment() {
        JLabel label = new BandLabel(true);
        label.setBorder(createSegmentBorder());
        return label;
    }

    /**
     * A 1px border.subtle line on the left of a segment (its color read when painting, so it follows theme changes),
     * space.12 on both sides of the text.
     */
    @NotNull
    private static Border createSegmentBorder() {
        int height = UIScale.scale(HEIGHT);
        int lineInset = UIScale.scale(7);
        return BorderFactory.createCompoundBorder(
            new AbstractBorder() {
                @Override
                public void paintBorder(Component c, Graphics g, int x, int y, int width, int borderHeight) {
                    g.setColor(getLineColor());
                    g.fillRect(x, y + lineInset, 1, Math.max(0, borderHeight - 2 * lineInset));
                }

                @Override
                public Insets getBorderInsets(Component c) {
                    return new Insets(0, 1, 0, 0);
                }
            },
            BorderFactory.createEmptyBorder((height - UIScale.scale(16)) / 2, UIScale.scale(12), (height - UIScale.scale(16)) / 2, UIScale.scale(12)));
    }

    /**
     * Label whose text color is {@link #bandForeground} while that is set, else the theme's text or text.secondary
     * color (read when painting, see the class comment).
     */
    private final class BandLabel extends JLabel {
        private final boolean secondary;

        BandLabel(boolean secondary) {
            this.secondary = secondary;
        }

        @Override
        public Color getForeground() {
            Color color = bandForeground;
            if (color == null && secondary) {
                color = UIManager.getColor("Chromite.secondaryForeground"); //$NON-NLS-1$
            }
            return color != null ? color : super.getForeground();
        }
    }

    @NotNull
    private static Color getLineColor() {
        Color color = UIManager.getColor("Chromite.dividerColor"); //$NON-NLS-1$
        return color != null ? color : Color.GRAY;
    }
}
