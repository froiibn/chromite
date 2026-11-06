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

import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;

import javax.swing.*;
import java.awt.event.ActionEvent;

/**
 * 메인 창의 도구 창 하나(Object Explorer, Registered Servers, Properties, Query Library, Results): 제목, View 메뉴 토글,
 * 숨기기 단추, 보이기·숨기기.
 *
 * 설계:
 * <ul>
 *     <li>도구 창이 어디에 어떻게 놓이는지는 {@link Host}가 맡는다. 다른 코드(View 메뉴, 도구 막대, 명령)는 이 클래스의
 *     {@link #getToggleAction()}, {@link #setShown}, {@link #isShown()}, {@link #createHideButton()}만 쓰므로 배치 방식이 바뀌어도 고치지 않는다.</li>
 *     <li>Object Explorer, Registered Servers, Properties, Query Library는 도킹 라이브러리 위의 {@link ToolWindowDocking}이 호스트다
 *     (끌어서 옮기기, 탭으로 합치기, 떠 있는 창, 자동 숨기기, 배치 저장).</li>
 *     <li>Results는 SSMS처럼 쿼리 창(문서 카드) 안에 있으므로 도킹하지 않고 문서 영역의 분할 창 아래쪽을 쓴다({@link SplitHost}).</li>
 *     <li>토글의 선택 상태는 호스트가 실제 상태에서 다시 읽는다({@link #shownChanged()}). 떠 있는 창을 닫거나 끌어서 옮기는 것처럼
 *     이 클래스를 거치지 않은 변경도 View 메뉴에 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 디자인 적용: 머리말 높이 40, 아이콘 숨기기 단추, 쓰지 않게 된 머리말 도구 인자 삭제 (docs/chromite/design-spec.md "적용 현황")
 * - 2026-11-06 Islands: 도구 창을 둥근 카드({@link IslandPanel})로 감쌈 (docs/chromite/design-spec.md "Islands 적용")
 * - 2026-11-06 P1: 기본 크기로 되돌리기(Window &gt; Reset Window Layout) (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 Registered Servers: 한 열에 두 도구 창 쌓기 (docs/chromite/swing-registered-servers.md)
 * - 2026-11-06 Query Library: 처음에는 숨긴 도구 창(Query Library) (docs/chromite/swing-query-library.md)
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-06 도킹: 배치를 {@link Host}로 나눔. 분할 창 열 쌓기({@code stackBelow}), 처음 숨김({@code setDefaultShown}), 머리말을 지우고
 *   도킹 도구 창은 {@link ToolWindowDocking}으로 옮김, 분할 창은 Results만 씀({@link SplitHost}) (docs/chromite/swing-frontend.md "도구 창 도킹")
 */
final class ToolWindow {

    /**
     * Where the tool window lives: the docking layout or the document area split pane.
     */
    interface Host {
        boolean isShown();

        /**
         * Shows or hides the tool window; showing a shown one brings it to front.
         */
        void setShown(boolean shown);
    }

    private final String id;
    private final String title;
    private final Action toggleAction;
    @Nullable
    private Host host;

    /**
     * @param id          persistent id (layout file, preference key prefix)
     * @param title       header title and View menu label
     * @param accelerator shortcut of the View menu toggle
     */
    ToolWindow(@NotNull String id, @NotNull String title, @Nullable KeyStroke accelerator) {
        this.id = id;
        this.title = title;
        this.toggleAction = new AbstractAction(title) {
            @Override
            public void actionPerformed(ActionEvent e) {
                setShown(Boolean.TRUE.equals(getValue(SELECTED_KEY)));
            }
        };
        toggleAction.putValue(Action.SELECTED_KEY, Boolean.FALSE);
        toggleAction.putValue(Action.ACCELERATOR_KEY, accelerator);
    }

    @NotNull
    String getId() {
        return id;
    }

    @NotNull
    String getTitle() {
        return title;
    }

    /**
     * Called once by the host that places this tool window.
     */
    void setHost(@NotNull Host host) {
        this.host = host;
        shownChanged();
    }

    /**
     * Check box action for the View menu; also used by the hide button.
     */
    @NotNull
    Action getToggleAction() {
        return toggleAction;
    }

    /**
     * The "hide" icon button (—) for the tool window header or for content with its own header.
     */
    @NotNull
    JButton createHideButton() {
        Action hideAction = new AbstractAction(NLS.bind(CoreMessages.toolwindow_hide, title)) {
            @Override
            public void actionPerformed(ActionEvent e) {
                setShown(false);
            }
        };
        return UiIcons.createActionButton(hideAction, "hide"); //$NON-NLS-1$
    }

    boolean isShown() {
        return host != null && host.isShown();
    }

    /**
     * Shows or hides the tool window. Showing a shown tool window brings it to front (its tab, if it shares a tab group).
     */
    void setShown(boolean shown) {
        if (host != null) {
            host.setShown(shown);
        }
        shownChanged();
    }

    /**
     * Updates the View menu toggle from the host's real state (also after changes the host made by itself).
     */
    void shownChanged() {
        toggleAction.putValue(Action.SELECTED_KEY, isShown());
    }

    /**
     * Results: the trailing (bottom) side of the document area split pane, as an Islands card, with its size and
     * visibility in the preferences.
     * <ul>
     *     <li>Hiding keeps the card in the split pane and only makes it invisible, so theme changes ({@code FlatLaf.updateUI()})
     *     still reach it. {@link JSplitPane} gives no space to an invisible side but keeps the divider, so the divider size is 0 while hidden.</li>
     *     <li>The saved value is the card's height, not the divider location; the split pane gives extra space to the
     *     documents ({@code resizeWeight = 1}), so the results keep their height when the window grows.</li>
     * </ul>
     */
    static final class SplitHost implements Host {
        private static final String PREF_VISIBLE_SUFFIX = ".visible"; //$NON-NLS-1$
        private static final String PREF_SIZE_SUFFIX = ".size"; //$NON-NLS-1$
        private static final int MIN_SIZE = 50;

        private final ToolWindow toolWindow;
        private final JSplitPane splitPane;
        private final JComponent component;
        private final int defaultSize;
        private int size;

        /**
         * @param content     content with its own header (it can embed {@link ToolWindow#createHideButton()})
         * @param splitPane   split pane whose trailing side hosts the tool window
         * @param defaultSize height (or width) used until the user resizes the tool window
         */
        SplitHost(@NotNull ToolWindow toolWindow, @NotNull JComponent content, @NotNull JSplitPane splitPane, int defaultSize) {
            this.toolWindow = toolWindow;
            this.splitPane = splitPane;
            this.defaultSize = defaultSize;
            this.size = defaultSize;
            // Islands: the tool window is a rounded card on the main window canvas
            component = new IslandPanel(content);
            splitPane.setRightComponent(component);
            // Extra space goes to the document area, so the tool window keeps its size when the main window grows
            splitPane.setResizeWeight(1.0);
            toolWindow.setHost(this);
        }

        @Override
        public boolean isShown() {
            return component.isVisible();
        }

        @Override
        public void setShown(boolean shown) {
            if (shown == component.isVisible()) {
                return;
            }
            if (shown) {
                component.setVisible(true);
                updateDivider();
                applySize();
            } else {
                size = getCurrentSize();
                component.setVisible(false);
                updateDivider();
            }
            splitPane.revalidate();
        }

        /**
         * Window &gt; Reset Window Layout: shown, with the default size.
         */
        void reset() {
            size = defaultSize;
            toolWindow.setShown(true);
            applySize();
        }

        /**
         * Reads visibility and size from the preferences. Call before the main window is shown;
         * the size is applied by {@link #applySize()} once the split pane has its real size.
         */
        void load(@NotNull DBPPreferenceStore preferences) {
            String id = toolWindow.getId();
            // The store drops values equal to their default (BundlePreferenceStore.putBoolean/putInt), so the visibility
            // is only remembered if the declared default is the one the tool window has before the user changes it
            preferences.setDefault(id + PREF_VISIBLE_SUFFIX, true);
            preferences.setDefault(id + PREF_SIZE_SUFFIX, size);
            size = Math.max(MIN_SIZE, preferences.getInt(id + PREF_SIZE_SUFFIX));
            toolWindow.setShown(preferences.getBoolean(id + PREF_VISIBLE_SUFFIX));
        }

        void save(@NotNull DBPPreferenceStore preferences) {
            if (component.isVisible()) {
                size = getCurrentSize();
            }
            preferences.setValue(toolWindow.getId() + PREF_VISIBLE_SUFFIX, component.isVisible());
            preferences.setValue(toolWindow.getId() + PREF_SIZE_SUFFIX, size);
        }

        /**
         * Moves the divider so that the tool window has its remembered size, and lays out the split pane at once.
         */
        void applySize() {
            if (!component.isVisible()) {
                return;
            }
            int total = isHorizontal() ? splitPane.getWidth() : splitPane.getHeight();
            if (total <= 0) {
                return;
            }
            int location = total - splitPane.getDividerSize() - size;
            splitPane.setDividerLocation(Math.max(MIN_SIZE, Math.min(location, total - MIN_SIZE)));
            splitPane.validate();
        }

        /**
         * Divider size 0 next to the hidden card.
         */
        private void updateDivider() {
            splitPane.setDividerSize(component.isVisible() ? UIManager.getInt("SplitPane.dividerSize") : 0); //$NON-NLS-1$
        }

        private int getCurrentSize() {
            int current = isHorizontal() ? component.getWidth() : component.getHeight();
            return current >= MIN_SIZE ? current : size;
        }

        private boolean isHorizontal() {
            return splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
        }
    }
}
