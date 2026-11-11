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
import com.seizia.chromite.ui.swing.internal.DockingMessages;
import io.github.andrewauclair.moderndocking.Dockable;
import io.github.andrewauclair.moderndocking.DockingRegion;
import io.github.andrewauclair.moderndocking.api.DockingAPI;
import io.github.andrewauclair.moderndocking.api.RootDockingPanelAPI;
import io.github.andrewauclair.moderndocking.api.WindowLayoutBuilderAPI;
import io.github.andrewauclair.moderndocking.exception.DockingLayoutException;
import io.github.andrewauclair.moderndocking.internal.DockedSimplePanel;
import io.github.andrewauclair.moderndocking.internal.DockedSplitPanel;
import io.github.andrewauclair.moderndocking.internal.DockedTabbedPanel;
import io.github.andrewauclair.moderndocking.internal.FloatingFrame;
import io.github.andrewauclair.moderndocking.layouts.ApplicationLayout;
import io.github.andrewauclair.moderndocking.layouts.DockingLayoutNode;
import io.github.andrewauclair.moderndocking.layouts.DockingLayoutRootNode;
import io.github.andrewauclair.moderndocking.layouts.DockingSimplePanelNode;
import io.github.andrewauclair.moderndocking.layouts.DockingSplitPanelNode;
import io.github.andrewauclair.moderndocking.layouts.DockingTabPanelNode;
import io.github.andrewauclair.moderndocking.layouts.WindowLayout;
import io.github.andrewauclair.moderndocking.settings.Settings;
import io.github.andrewauclair.moderndocking.ui.DockingHeaderUI;
import io.github.andrewauclair.moderndocking.ui.HeaderController;
import io.github.andrewauclair.moderndocking.ui.HeaderModel;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 메인 창의 도구 창 도킹(Object Explorer, Registered Servers, Properties, Query Library)과 그 배치 저장.
 *
 * 설계:
 * <ul>
 *     <li>도킹 라이브러리는 ModernDocking(MIT, docs/chromite/swing-frontend.md "도구 창 도킹"의 검토)의 핵심 API만 쓴다. 도구 창 머리말을
 *     끌어 왼쪽·오른쪽·아래 또는 다른 도구 창 안(탭)에 놓고, 떠 있는 창으로 띄우고, 가장자리로 자동 숨긴다. 라이브러리의
 *     전역 정적 인스턴스(single-app의 {@code Docking})는 쓰지 않고 이 클래스가 메인 창마다 {@link DockingAPI} 하나를 만든다.</li>
 *     <li>문서 영역(문서 탭 + 쿼리 창의 Results)은 움직이지 않는 도킹 대상 하나({@link DocumentsDockable})다. 머리말이 없어
 *     끌 수 없고, 떠 있는 창·자동 숨기기·다른 창 이동을 막는다. 도구 창을 문서 영역 한가운데(CENTER)에 놓으면 문서 탭에 섞이지 않도록
 *     마우스에서 가장 가까운 가장자리로 바꾼다({@link Docking#dock(Dockable, Dockable, DockingRegion, double)}).</li>
 *     <li>보이기·숨기기: 라이브러리의 닫기는 위치를 기억하지 않는다. 그래서 도구 창마다 마지막 위치({@link Place}: 이웃 도구 창이나
 *     창 가장자리, 방향, 비율)를 배치가 바뀔 때마다 배치 트리에서 다시 계산해 두고, 다시 보일 때 그 위치에 놓는다. 이웃이
 *     없으면 도구 창마다 정한 기본 위치를 차례로 쓴다. 한 열을 나누는 두 도구 창(Object Explorer와 Registered Servers, Query Library와
 *     Chromite Agent)은 기본 위치에 서로를 이웃으로 둔다. 마지막 위치가 문서 영역 옆 열(창 가장자리나 문서 영역의 왼쪽·오른쪽)이고 기본 이웃이
 *     지금 문서 영역의 같은 쪽에 있으면, 새 열을 만들지 않고 이웃의 열에 들어간다. 마지막 위치는 이웃이 숨어 있을 때 계산되어 이웃의 열을
 *     모르기 때문이다(한쪽을 숨긴 사이에 다른 쪽을 보였다가 다시 보여도 같은 열). 반대로 마지막 위치의 이웃이 아직 숨어 있으면(한 열을
 *     하나씩 숨긴 뒤 먼저 숨긴 것부터 보일 때) 그 이웃의 마지막 위치를 쓴다. 기본 위치로 가면 열 폭이 기본 비율로 돌아가기 때문이다.</li>
 *     <li>저장: 작업 공간 {@code .metadata}의 {@value #LAYOUT_FILE}(라이브러리의 XML 형식, 떠 있는 창 포함)와 이 번들 설정의
 *     {@code <id>.restorePlace}(숨긴 도구 창의 위치). 메인 창 위치·크기는 {@link MainFrame}이 따로 저장하므로 배치 파일의 메인 창 크기는
 *     쓰지 않는다. 파일이 없거나, 읽을 수 없거나, 모르는 도구 창·문서 영역 없음 같은 맞지 않는 배치면 기본 배치로 시작한다
 *     (모르는 이름을 라이브러리에 넘기면 클래스 이름으로 객체를 만들려 하므로 먼저 검사한다). 파일 이름의 숫자는 형식 버전이다.</li>
 *     <li>이전 분할 창 배치의 설정({@code <id>.visible}, {@code <id>.size})은 배치 파일이 없는 첫 실행에서 보이기 여부만 옮기고 지운다.
 *     크기와 Registered Servers가 위였던 순서는 옮기지 않고 기본 배치(Object Explorer 위, Registered Servers 아래)로 시작한다.</li>
 *     <li>Islands 모양: 라이브러리 패널은 투명하게 하고 테두리를 지운다({@link #decorate()}). 루트({@link Root})가 캔버스와 카드 바탕을
 *     칠하고, 자식을 그린 뒤 카드 모서리를 캔버스 색으로 덮는다({@link IslandPanel#paintCorners}). 카드 사이 틈은 라이브러리 분할 창의
 *     분할선({@code SplitPane.dividerSize} 6)이다. 끌어 놓을 자리 표시는 강조색({@code ModernDocking.overlayBackground})이다.</li>
 *     <li>최소 폭: 라이브러리의 분할 창(JSplitPane)은 카드를 최소 폭보다 좁히지 않는다. 내용의 최소 폭(긴 개체 이름 레이블, 도구 막대 전체,
 *     줄 바꿈 텍스트 영역의 현재 폭)을 따르면 카드가 데이터에 따라 넓어지거나 아예 줄지 않으므로, 카드의 최소 폭은 머리말({@link Header})이
 *     정하고 내용({@link ToolWindowDockable})은 최소 폭을 갖지 않는다. 내용은 좁게 배치되어 레이블은 말줄임표로, 도구 막대는 넘침 메뉴로 줄어든다.</li>
 *     <li>크기 바꾸기: 라이브러리의 분할 창은 중첩되어 있고(바깥 분할 = [왼쪽 열 + 문서 영역 | 오른쪽 열]) 크기가 바뀌면 양쪽이 반씩 나눠 가진다
 *     (resizeWeight 0.5). 그래서 오른쪽 경계를 끌면 안쪽 분할의 왼쪽 열도, 창 크기를 바꾸면 모든 열이 바뀌었다. 문서 영역을 가진 분할은 바뀐 크기를
 *     문서 영역 쪽이 모두 갖고, 문서 영역의 한쪽에 있는 분할은 문서 영역에 가까운 쪽이 갖는다({@link #updateResizeWeights()}). 경계를 끌면
 *     그 양옆만, 창 크기를 바꾸면 문서 영역만 바뀐다(DataGrip, SSMS와 같음). 문서 영역과 다른 방향(한 열의 위아래)과 떠 있는 창은 반씩 그대로다.
 *     라이브러리가 분할선 두 번 클릭에 resizeWeight 위치로 옮기는 동작은 열을 한쪽 끝으로 접게 되므로 뗀다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: ModernDocking 도입, 분할 창 배치 대체 (docs/chromite/swing-frontend.md "도구 창 도킹")
 * - 2026-11-07 밀도 B: 치수를 테마의 Chromite.size.* 키({@link UiSizes})에서 읽음 (docs/chromite/design-spec.md "치수 토큰")
 * - 2026-11-07 도구 창 최소 폭: 카드 최소 폭을 머리말(아이콘 단추 + 제목 첫 글자와 말줄임표)로 정함. Properties가 일정 폭 이하로
 *   줄지 않던 문제 (docs/chromite/swing-frontend.md "도구 창 최소 폭")
 * - 2026-11-07 한 열의 두 도구 창: 문서 영역 옆 열로 돌아가는 도구 창은 기본 이웃이 문서 영역의 같은 쪽에 있으면 이웃의 열로. Properties를
 *   숨긴 사이 Query Library를 보이면 둘이 따로 열을 만들던 문제 (docs/chromite/swing-frontend.md "Query Library 자리")
 * - 2026-11-08 주석만: 한 열을 나누는 예를 새 기본 배치(Query Library와 Chromite Agent, 앱 변경 #12)로
 *   (docs/chromite/swing-frontend.md "기본 배치: Query Library와 Chromite Agent")
 * - 2026-11-11 크기 바꾸기: 끈 경계의 양옆만, 창 크기는 문서 영역만 바뀌게 분할 창의 resizeWeight를 문서 영역 쪽으로. 오른쪽 열 경계를 끌면
 *   왼쪽 열도 바뀌던 문제. 한 열을 하나씩 숨긴 뒤 먼저 숨긴 것부터 보이면 숨어 있는 이웃의 마지막 위치로(열 폭이 기본 비율로 돌아가던 문제)
 *   (앱 변경 #45, docs/chromite/swing-frontend.md "도구 창 크기 바꾸기")
 */
final class ToolWindowDocking {

    private static final Log log = Log.getLog(ToolWindowDocking.class);

    private static final String DOCUMENTS_ID = "documents"; //$NON-NLS-1$
    /**
     * The number is the layout format version: a file of another version is not read (the default layout is used).
     */
    private static final String LAYOUT_FILE = "chromite-window-layout-1.xml"; //$NON-NLS-1$
    private static final String PREF_RESTORE_PLACE_SUFFIX = ".restorePlace"; //$NON-NLS-1$
    /**
     * Preferences of the split pane layout before docking: migrated once (visibility), then removed.
     */
    private static final String OLD_PREF_VISIBLE_SUFFIX = ".visible"; //$NON-NLS-1$
    private static final String OLD_PREF_SIZE_SUFFIX = ".size"; //$NON-NLS-1$
    private static final double MIN_PROPORTION = 0.05;
    private static final double MAX_PROPORTION = 0.95;
    /**
     * The library's resize weight: both sides of a split share a change of its size.
     */
    private static final double EVEN_RESIZE_WEIGHT = 0.5;
    private static final int PLACES_DELAY_MS = 500;
    /**
     * The text a clipped {@link JLabel} ends with (the label's UI draws it).
     */
    private static final String CLIPPED_TEXT_END = "..."; //$NON-NLS-1$

    private final JFrame frame;
    private final Docking docking;
    private final DocumentsDockable documents;
    private final Root root;
    private final Map<String, Dock> docks = new LinkedHashMap<>();
    private final PropertyChangeListener lookAndFeelListener;
    /**
     * Remembers the places shortly after a change: the library sets the split dividers of a new layout only once the
     * panels are laid out, so places read at once would have the dividers' old proportions.
     */
    private final Timer placesTimer;
    private List<WindowLayout> pendingFloatingLayouts = List.of();
    private boolean updateScheduled;
    private boolean disposed;

    /**
     * Where a hidden tool window goes back to: next to another dockable ({@code targetId}) or along the main window edge
     * ({@code targetId == null}). {@code size} is the tool window's share of the target, or of the main window's docking
     * area for an edge. It is a proportion, the unit of the library's layouts: a shown tool window keeps its width in
     * pixels when the main window is resized ({@link #updateResizeWeights()}), but one hidden meanwhile comes back with
     * its share of the new size.
     */
    record Place(@Nullable String targetId, @NotNull DockingRegion region, double size) {
        private static final String SEPARATOR = "|"; //$NON-NLS-1$

        @NotNull
        static Place edge(@NotNull DockingRegion region, double proportion) {
            return new Place(null, region, proportion);
        }

        @NotNull
        static Place next(@NotNull String targetId, @NotNull DockingRegion region, double proportion) {
            return new Place(targetId, region, proportion);
        }

        @NotNull
        String format() {
            return CommonUtils.notEmpty(targetId) + SEPARATOR + region.name() + SEPARATOR + size;
        }

        @Nullable
        static Place parse(@Nullable String value) {
            if (CommonUtils.isEmpty(value)) {
                return null;
            }
            String[] parts = value.split("\\|", -1); //$NON-NLS-1$
            if (parts.length != 3) {
                return null;
            }
            try {
                double size = Double.parseDouble(parts[2]);
                if (!(size > 0) || size > 1) {
                    return null;
                }
                return new Place(parts[0].isEmpty() ? null : parts[0], DockingRegion.valueOf(parts[1]), size);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    ToolWindowDocking(@NotNull JFrame frame, @NotNull JComponent documentArea) {
        this.frame = frame;
        // Global settings of the library, before its first instance: no mouse-over border (the Islands cards show the
        // tool windows) and no change of the AWT-wide drag threshold (tree and table drags keep theirs)
        Settings.setActiveHighlighterEnabled(false);
        Settings.useExistingDragThreshold(true);
        docking = new Docking(frame);
        root = new Root(docking, frame);
        documents = new DocumentsDockable(documentArea);
        docking.registerDockable(documents);
        docking.dock(documents, frame);
        placesTimer = new Timer(PLACES_DELAY_MS, e -> {
            if (!disposed) {
                updatePlaces();
            }
        });
        placesTimer.setRepeats(false);
        docking.addDockingListener(e -> {
            // At once, not only in the scheduled update: the change is laid out before that runs
            updateResizeWeights();
            scheduleUpdate();
        });
        lookAndFeelListener = e -> {
            // The library re-adds its panel borders on look and feel changes (invokeLater); decorate after it
            if ("lookAndFeel".equals(e.getPropertyName())) { //$NON-NLS-1$
                scheduleUpdate();
            }
        };
        UIManager.addPropertyChangeListener(lookAndFeelListener);
    }

    /**
     * The docking area for the main window content.
     */
    @NotNull
    JComponent getComponent() {
        return root;
    }

    /**
     * Makes a tool window dockable. Call for all tool windows before {@link #restoreLayout}, in the order of the
     * default layout.
     *
     * @param shownByDefault whether the default layout shows it
     * @param defaultPlaces  where it goes when shown without a remembered place: the first place whose target is shown
     *                       is used, so the list should end with a window edge. Two tool windows that share a column
     *                       name each other, so either one shown after the other joins its column (also when its
     *                       remembered place is beside the documents, see {@link #getNeighbourColumnPlace})
     */
    void addToolWindow(@NotNull ToolWindow toolWindow, @NotNull JComponent content, boolean shownByDefault, @NotNull Place... defaultPlaces) {
        Dock dock = new Dock(toolWindow, shownByDefault, List.of(defaultPlaces));
        dock.dockable = new ToolWindowDockable(dock, content);
        docks.put(toolWindow.getId(), dock);
        docking.registerDockable(dock.dockable);
        toolWindow.setHost(dock);
    }

    /**
     * Restores the saved layout of the main window (or the default one) and the remembered places of hidden tool
     * windows. Floating windows are opened later, by {@link #restoreFloatingWindows()}, once the main window is shown.
     */
    void restoreLayout(@NotNull DBPPreferenceStore preferences) {
        for (Dock dock : docks.values()) {
            dock.restorePlace = Place.parse(preferences.getString(dock.getId() + PREF_RESTORE_PLACE_SUFFIX));
        }
        ApplicationLayout layout = readLayoutFile();
        if (layout != null) {
            restoreMainWindow(layout.getMainFrameLayout());
            pendingFloatingLayouts = layout.getFloatingFrameLayouts();
        } else {
            restoreMainWindow(createDefaultLayout());
            migrateSplitPanePreferences(preferences);
        }
        scheduleUpdate();
    }

    /**
     * Opens the floating windows of the restored layout (after the main window, so they stay in front of it).
     */
    void restoreFloatingWindows() {
        for (WindowLayout layout : pendingFloatingLayouts) {
            FloatingFrame floatingFrame = new FloatingFrame(docking, layout.getLocation(), layout.getSize(), layout.getState());
            docking.getDockingState().restoreWindowLayout(floatingFrame, layout);
        }
        pendingFloatingLayouts = List.of();
        scheduleUpdate();
    }

    void saveLayout(@NotNull DBPPreferenceStore preferences) {
        updatePlaces();
        for (Dock dock : docks.values()) {
            String key = dock.getId() + PREF_RESTORE_PLACE_SUFFIX;
            if (dock.restorePlace != null) {
                preferences.setValue(key, dock.restorePlace.format());
            } else {
                preferences.setToDefault(key);
            }
        }
        try {
            docking.getLayoutPersistence().saveLayoutToFile(getLayoutFile().toFile(), docking.getDockingState().getApplicationLayout());
        } catch (DockingLayoutException | RuntimeException e) {
            log.warn("Can't save the window layout", e); //$NON-NLS-1$
        }
    }

    /**
     * Window &gt; Reset Window Layout: the default layout, floating windows closed, remembered places forgotten.
     */
    void resetLayout() {
        for (Dock dock : docks.values()) {
            dock.restorePlace = null;
        }
        pendingFloatingLayouts = List.of();
        restoreMainWindow(createDefaultLayout());
        scheduleUpdate();
    }

    /**
     * Closes the floating windows and releases the library's global listeners. Call after {@link #saveLayout}.
     */
    void dispose() {
        disposed = true;
        placesTimer.stop();
        UIManager.removePropertyChangeListener(lookAndFeelListener);
        docking.uninitialize();
    }

    // ------------------------------------------------------------------ layouts

    @NotNull
    private ApplicationLayout createDefaultLayout() {
        // The place of each tool window shown by default: the first default place whose target is placed before it
        Set<String> placed = new HashSet<>();
        placed.add(DOCUMENTS_ID);
        List<Dock> placedDocks = new ArrayList<>();
        List<Place> places = new ArrayList<>();
        for (Dock dock : docks.values()) {
            if (!dock.shownByDefault) {
                continue;
            }
            for (Place place : dock.defaultPlaces) {
                if (place.targetId() == null || placed.contains(place.targetId())) {
                    placed.add(dock.getId());
                    placedDocks.add(dock);
                    places.add(place);
                    break;
                }
            }
        }
        WindowLayoutBuilderAPI builder = new WindowLayoutBuilderAPI(docking, DOCUMENTS_ID) {
        };
        for (int i = 0; i < places.size(); i++) {
            Place place = places.get(i);
            if (place.targetId() == null) {
                // An edge docked later wraps this one, so its share is of what the later edges of the same axis leave
                double later = 0;
                for (int j = i + 1; j < places.size(); j++) {
                    if (places.get(j).targetId() == null && isHorizontal(places.get(j).region()) == isHorizontal(place.region())) {
                        later += places.get(j).size();
                    }
                }
                builder.dockToRoot(placedDocks.get(i).getId(), place.region(), clampProportion(place.size() / Math.max(MIN_PROPORTION, 1 - later)));
            } else {
                builder.dock(placedDocks.get(i).getId(), place.targetId(), place.region(), clampProportion(place.size()));
            }
        }
        return builder.buildApplicationLayout();
    }

    /**
     * Restores the main window part of a layout without its window bounds ({@link MainFrame} keeps its own normal
     * bounds and maximized state). Also closes floating windows that are open.
     */
    private void restoreMainWindow(@NotNull ApplicationLayout layout) {
        restoreMainWindow(layout.getMainFrameLayout());
    }

    private void restoreMainWindow(@NotNull WindowLayout source) {
        WindowLayout main = new WindowLayout(source.getRootNode());
        main.setWestAutoHideToolbarIDs(source.getWestAutoHideToolbarIDs());
        main.setEastAutoHideToolbarIDs(source.getEastAutoHideToolbarIDs());
        main.setSouthAutoHideToolbarIDs(source.getSouthAutoHideToolbarIDs());
        for (List<String> ids : List.of(source.getWestAutoHideToolbarIDs(), source.getEastAutoHideToolbarIDs(), source.getSouthAutoHideToolbarIDs())) {
            for (String id : ids) {
                main.setSlidePosition(id, source.slidePosition(id));
            }
        }
        docking.getDockingState().restoreApplicationLayout(new ApplicationLayout(main));
    }

    @NotNull
    private static Path getLayoutFile() {
        return GeneralUtils.getMetadataFolder().resolve(LAYOUT_FILE);
    }

    /**
     * The saved layout, or {@code null} if there is none or it can't be used.
     */
    @Nullable
    private ApplicationLayout readLayoutFile() {
        Path file = getLayoutFile();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            ApplicationLayout layout = docking.getLayoutPersistence().loadApplicationLayoutFromFile(file.toFile());
            String problem = validate(layout);
            if (problem == null) {
                return layout;
            }
            log.warn("Window layout " + file + " is not used (" + problem + "), starting from the default layout"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (DockingLayoutException | RuntimeException e) {
            log.warn("Can't read the window layout " + file + ", starting from the default layout", e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return null;
    }

    /**
     * Checks that a loaded layout only has known dockables, each once, and the documents in the main window. Unknown ids
     * must never reach the library: it would try to create them by class name.
     *
     * @return the problem, or {@code null} if the layout can be restored
     */
    @Nullable
    private String validate(@NotNull ApplicationLayout layout) {
        WindowLayout main = layout.getMainFrameLayout();
        if (main == null) {
            return "no main window"; //$NON-NLS-1$
        }
        List<String> ids = new ArrayList<>();
        List<WindowLayout> windows = new ArrayList<>();
        windows.add(main);
        windows.addAll(layout.getFloatingFrameLayouts());
        for (WindowLayout window : windows) {
            if (window.getMaximizedDockable() != null) {
                return "maximized dockable"; //$NON-NLS-1$
            }
            List<String> windowIds = new ArrayList<>();
            if (!collectIds(window.getRootNode(), windowIds) || windowIds.isEmpty()) {
                return "unsupported layout node"; //$NON-NLS-1$
            }
            if (window != main && windowIds.contains(DOCUMENTS_ID)) {
                return "documents in a floating window"; //$NON-NLS-1$
            }
            ids.addAll(windowIds);
            for (List<String> hidden : List.of(window.getWestAutoHideToolbarIDs(), window.getEastAutoHideToolbarIDs(), window.getSouthAutoHideToolbarIDs())) {
                for (String id : hidden) {
                    if (!docks.containsKey(id)) {
                        return "unknown auto-hidden dockable " + id; //$NON-NLS-1$
                    }
                    ids.add(id);
                }
            }
        }
        if (!ids.contains(DOCUMENTS_ID)) {
            return "no documents"; //$NON-NLS-1$
        }
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                return "dockable twice: " + id; //$NON-NLS-1$
            }
            if (!DOCUMENTS_ID.equals(id) && !docks.containsKey(id)) {
                return "unknown dockable " + id; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static boolean collectIds(@Nullable DockingLayoutNode node, @NotNull List<String> ids) {
        if (node instanceof DockingLayoutRootNode rootNode) {
            return collectIds(rootNode.getNode(), ids);
        } else if (node instanceof DockingSplitPanelNode split) {
            return collectIds(split.getLeft(), ids) && collectIds(split.getRight(), ids);
        } else if (node instanceof DockingTabPanelNode tabs) {
            for (DockingSimplePanelNode tab : tabs.getPersistentIDs()) {
                ids.add(tab.getPersistentID());
            }
            return true;
        } else if (node instanceof DockingSimplePanelNode simple) {
            ids.add(simple.getPersistentID());
            return true;
        }
        // Anchors and empty nodes are not used by Chromite
        return false;
    }

    /**
     * First run with docking: the visibility saved by the split pane layout ({@code <id>.visible}, stored only when it
     * differed from the default) is kept; the old keys are removed.
     */
    private void migrateSplitPanePreferences(@NotNull DBPPreferenceStore preferences) {
        for (Dock dock : docks.values()) {
            String visibleKey = dock.getId() + OLD_PREF_VISIBLE_SUFFIX;
            if (preferences.contains(visibleKey)) {
                dock.toolWindow.setShown(preferences.getBoolean(visibleKey));
            }
            preferences.setToDefault(visibleKey);
            preferences.setToDefault(dock.getId() + OLD_PREF_SIZE_SUFFIX);
        }
    }

    // ------------------------------------------------------------------ show / hide

    private void show(@NotNull Dock dock) {
        List<Place> places = new ArrayList<>();
        // The last place, then, while its neighbour is hidden too (a column hidden one tool window after the other), the
        // neighbour's last place: the column comes back where it was and with its width, not at the default share
        Set<String> seen = new HashSet<>();
        seen.add(dock.getId());
        for (Place lastPlace = dock.restorePlace; lastPlace != null; lastPlace = getHiddenTargetPlace(lastPlace, seen)) {
            Place neighbourPlace = getNeighbourColumnPlace(dock, lastPlace);
            if (neighbourPlace != null) {
                places.add(neighbourPlace);
            }
            places.add(lastPlace);
        }
        places.addAll(dock.defaultPlaces);
        for (Place place : places) {
            if (place.targetId() == null) {
                docking.dock(dock.dockable, frame, place.region(), clampProportion(place.size()));
                return;
            }
            Dockable target = getDockable(place.targetId());
            if (target != null && target != dock.dockable && isInMainWindow(target)) {
                docking.dock(dock.dockable, target, place.region(), clampProportion(place.size()));
                return;
            }
        }
        // Default places end with a window edge, so this is only reached for a tool window without default places
        docking.dock(dock.dockable, frame, DockingRegion.EAST, MIN_PROPORTION * 5);
    }

    private void hide(@NotNull Dock dock) {
        Place place = computeMainPlace(dock);
        if (place != null) {
            dock.restorePlace = place;
        }
        docking.undock(dock.dockable);
    }

    private void floatWindow(@NotNull Dock dock) {
        Place place = computeMainPlace(dock);
        if (place != null) {
            dock.restorePlace = place;
        }
        docking.newWindow(dock.dockable);
    }

    /**
     * Back from a floating window or from auto hide to the last place in the main window.
     */
    private void dockToMainWindow(@NotNull Dock dock) {
        docking.undock(dock.dockable);
        show(dock);
    }

    private void toggleAutoHide(@NotNull Dock dock) {
        if (docking.isHidden(dock.dockable)) {
            dockToMainWindow(dock);
        } else {
            Place place = computeMainPlace(dock);
            if (place != null) {
                dock.restorePlace = place;
            }
            docking.autoHideDockable(dock.dockable);
        }
    }

    /**
     * The last place of the tool window a place is next to, if that one is not in the main window either (each tool window
     * once, {@code seen}).
     */
    @Nullable
    private Place getHiddenTargetPlace(@NotNull Place place, @NotNull Set<String> seen) {
        Dock target = place.targetId() != null ? docks.get(place.targetId()) : null;
        if (target == null || !seen.add(target.getId()) || isInMainWindow(target.dockable)) {
            return null;
        }
        return target.restorePlace;
    }

    @Nullable
    private Dockable getDockable(@NotNull String id) {
        if (DOCUMENTS_ID.equals(id)) {
            return documents;
        }
        Dock dock = docks.get(id);
        return dock != null ? dock.dockable : null;
    }

    /**
     * The default place next to a neighbour tool window, when the tool window comes back to a column beside the
     * documents (its last place is a window edge, or the documents, on the left or right) and that neighbour is now on
     * the same side of the documents. The last place was taken while the neighbour was hidden, so it doesn't know the
     * neighbour's column: without this, the tool window would open a column of its own next to it.
     */
    @Nullable
    private Place getNeighbourColumnPlace(@NotNull Dock dock, @NotNull Place lastPlace) {
        boolean beside = lastPlace.targetId() == null || DOCUMENTS_ID.equals(lastPlace.targetId());
        if (!beside || !isHorizontal(lastPlace.region())) {
            return null;
        }
        for (Place place : dock.defaultPlaces) {
            if (place.targetId() != null && !DOCUMENTS_ID.equals(place.targetId())
                && getSideOfDocuments(place.targetId()) == lastPlace.region()) {
                return place;
            }
        }
        return null;
    }

    /**
     * The side of the documents (WEST or EAST) that a tool window docked in the main window is on, or {@code null}
     * (above or below the documents, or not in the main window). From the layout tree: the split that first has both.
     */
    @Nullable
    private DockingRegion getSideOfDocuments(@NotNull String id) {
        Dockable dockable = getDockable(id);
        if (dockable == null || !isInMainWindow(dockable)) {
            return null;
        }
        DockingLayoutNode rootNode = docking.getDockingState().getWindowLayout(frame).getRootNode();
        DockingLayoutNode documentsNode = rootNode.findNode(DOCUMENTS_ID);
        DockingLayoutNode node = rootNode.findNode(id);
        if (documentsNode == null || node == null) {
            return null;
        }
        for (; node.getParent() != null; node = node.getParent()) {
            if (node.getParent() instanceof DockingSplitPanelNode split && isAncestor(split, documentsNode)) {
                if (split.getOrientation() != JSplitPane.HORIZONTAL_SPLIT) {
                    return null;
                }
                return split.getLeft() == node ? DockingRegion.WEST : DockingRegion.EAST;
            }
        }
        return null;
    }

    private static boolean isAncestor(@NotNull DockingLayoutNode ancestor, @NotNull DockingLayoutNode node) {
        for (DockingLayoutNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Docked in the main window layout (not floating, not auto-hidden).
     */
    private boolean isInMainWindow(@NotNull Dockable dockable) {
        return docking.isDocked(dockable) && !docking.isHidden(dockable)
            && SwingUtilities.getWindowAncestor((Component) dockable) == frame;
    }

    /**
     * The current place of a tool window in the main window layout, or {@code null} if it isn't there.
     */
    @Nullable
    private Place computeMainPlace(@NotNull Dock dock) {
        // Before the main window is shown the split dividers are not laid out, so their proportions are not real
        // (e.g. a tool window hidden while the old preferences are migrated keeps its default place instead)
        if (!root.isShowing() || !isInMainWindow(dock.dockable)) {
            return null;
        }
        return computePlace(docking.getDockingState().getWindowLayout(frame).getRootNode(), dock.getId());
    }

    /**
     * Where a dockable is relative to its neighbour in a layout: the other tab of its tab group, or the other side of
     * its split (the nearest dockable of that side, or the window edge for the window's top split).
     */
    @Nullable
    static Place computePlace(@NotNull DockingLayoutNode rootNode, @NotNull String id) {
        DockingLayoutNode node = rootNode.findNode(id);
        if (node == null) {
            return null;
        }
        if (node.getParent() instanceof DockingTabPanelNode tabs) {
            for (DockingSimplePanelNode tab : tabs.getPersistentIDs()) {
                if (!id.equals(tab.getPersistentID())) {
                    return Place.next(tab.getPersistentID(), DockingRegion.CENTER, 0.5);
                }
            }
            node = tabs;
        }
        if (!(node.getParent() instanceof DockingSplitPanelNode split)) {
            return null;
        }
        boolean first = split.getLeft() == node;
        boolean horizontal = split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
        DockingRegion region = horizontal ? (first ? DockingRegion.WEST : DockingRegion.EAST) : (first ? DockingRegion.NORTH : DockingRegion.SOUTH);
        double proportion = clampProportion(first ? split.getDividerProportion() : 1 - split.getDividerProportion());
        DockingLayoutNode sibling = first ? split.getRight() : split.getLeft();
        boolean leaf = sibling instanceof DockingSimplePanelNode || sibling instanceof DockingTabPanelNode;
        if (!leaf && !(split.getParent() instanceof DockingSplitPanelNode)) {
            // The window's top split: along the window edge, like the default layout
            return new Place(null, region, proportion);
        }
        String target = getAdjacentLeaf(sibling, first);
        return target != null ? Place.next(target, region, proportion) : null;
    }

    /**
     * The dockable of a layout subtree that is next to the other side of the split (its first leaf when the other side
     * is before the subtree).
     */
    @Nullable
    private static String getAdjacentLeaf(@Nullable DockingLayoutNode node, boolean fromStart) {
        if (node instanceof DockingSimplePanelNode simple) {
            return simple.getPersistentID();
        } else if (node instanceof DockingTabPanelNode tabs) {
            return tabs.getSelectedTabID();
        } else if (node instanceof DockingSplitPanelNode split) {
            return getAdjacentLeaf(fromStart ? split.getLeft() : split.getRight(), fromStart);
        }
        return null;
    }

    private static double clampProportion(double proportion) {
        return Math.max(MIN_PROPORTION, Math.min(MAX_PROPORTION, proportion));
    }

    private static boolean isHorizontal(@NotNull DockingRegion region) {
        return region == DockingRegion.WEST || region == DockingRegion.EAST;
    }

    // ------------------------------------------------------------------ updates after docking changes

    /**
     * Coalesces the docking events of one change (a drop fires several) into one update after the change.
     */
    private void scheduleUpdate() {
        if (updateScheduled || disposed) {
            return;
        }
        updateScheduled = true;
        SwingUtilities.invokeLater(() -> {
            updateScheduled = false;
            if (!disposed) {
                decorate();
                updateResizeWeights();
                placesTimer.restart();
                for (Dock dock : docks.values()) {
                    dock.toolWindow.shownChanged();
                }
                updateFloatingTitles();
                root.repaint();
            }
        });
    }

    /**
     * Remembers the place of every tool window that is in the main window layout, so a later hide, float, auto hide
     * or close (also from a floating window's close button) can bring it back there.
     */
    private void updatePlaces() {
        if (!root.isShowing()) {
            // Not laid out yet: the divider proportions would not be real (see computeMainPlace)
            return;
        }
        DockingLayoutNode rootNode = null;
        for (Dock dock : docks.values()) {
            if (isInMainWindow(dock.dockable)) {
                if (rootNode == null) {
                    rootNode = docking.getDockingState().getWindowLayout(frame).getRootNode();
                }
                Place place = computePlace(rootNode, dock.getId());
                if (place != null) {
                    dock.restorePlace = place;
                }
            }
        }
    }

    /**
     * Islands look for the library's panels: transparent (the root paints the canvas and the cards), no borders (the
     * library's 1px frame and active highlight), tool window tab strips like the results tabs.
     */
    private void decorate() {
        Color canvas = UIManager.getColor("Chromite.island.canvas"); //$NON-NLS-1$
        for (Map.Entry<Window, RootDockingPanelAPI> entry : docking.getRootPanels().entrySet()) {
            RootDockingPanelAPI rootPanel = entry.getValue();
            if (rootPanel != root && canvas != null) {
                // Floating windows: canvas around their cards
                rootPanel.setBackground(canvas);
            }
            decorate(rootPanel);
        }
    }

    private static void decorate(@NotNull Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof Dockable || child instanceof DockingHeaderUI) {
                continue;
            }
            if (child instanceof JComponent component) {
                component.setOpaque(false);
                if (child instanceof DockedSimplePanel || child instanceof DockedTabbedPanel) {
                    component.setBorder(null);
                }
                if (child instanceof JTabbedPane tabs) {
                    tabs.putClientProperty(FlatClientProperties.STYLE, UiSizes.TOOL_TABS_STYLE);
                }
            }
            if (child instanceof Container nested) {
                decorate(nested);
            }
        }
    }

    /**
     * Makes a change of a split's size go to the side toward the documents (see {@link #getResizeWeight}), so a dragged
     * border changes only its two neighbours and a resized main window only the documents. The library gives every
     * split half of a change: dragging the right column's border resized the left column too (the outer split holds
     * the left column and the documents on one side), and resizing the window changed every column.
     */
    private void updateResizeWeights() {
        for (RootDockingPanelAPI rootPanel : docking.getRootPanels().values()) {
            updateResizeWeights(rootPanel);
        }
    }

    private void updateResizeWeights(@NotNull Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof Dockable) {
                continue;
            }
            if (child instanceof DockedSplitPanel splitPanel) {
                JSplitPane splitPane = splitPanel.getSplitPane();
                splitPane.setResizeWeight(getResizeWeight(splitPane));
                // The library's double click moves the divider to the resize weight, which would now fold a side away
                if (splitPane.getUI() instanceof BasicSplitPaneUI splitPaneUI && splitPaneUI.getDivider() != null) {
                    splitPaneUI.getDivider().removeMouseListener(splitPanel);
                }
            }
            if (child instanceof Container nested) {
                updateResizeWeights(nested);
            }
        }
    }

    /**
     * 1 (the left or top side gets all of a change) or 0 (the right or bottom side) for the side toward the documents:
     * the side that has them, or for a split on one side of them along the same axis (two columns on the left), the side
     * nearer to them. Even for splits across that axis (two tool windows above each other in a column) and in floating
     * windows.
     */
    private double getResizeWeight(@NotNull JSplitPane splitPane) {
        for (Component component = splitPane; component != null; component = component.getParent()) {
            // The innermost split that has the documents: the split itself, or the one this split is on a side of
            if (component instanceof JSplitPane split && SwingUtilities.isDescendingFrom(documents, split)) {
                if (split.getOrientation() != splitPane.getOrientation()) {
                    return EVEN_RESIZE_WEIGHT;
                }
                // Documents first: they are on this split's first side, or this split is after them (right of or below
                // them) and its first side is the nearer one. Documents second: the same for the second side
                return SwingUtilities.isDescendingFrom(documents, split.getLeftComponent()) ? 1 : 0;
            }
        }
        return EVEN_RESIZE_WEIGHT;
    }

    /**
     * A floating window's title: its tool windows (shown in the task bar).
     */
    private void updateFloatingTitles() {
        for (Window window : docking.getRootPanels().keySet()) {
            if (window == frame || !(window instanceof JFrame floatingFrame)) {
                continue;
            }
            StringJoiner title = new StringJoiner(", "); //$NON-NLS-1$
            for (Dock dock : docks.values()) {
                if (docking.isDocked(dock.dockable) && SwingUtilities.getWindowAncestor(dock.dockable) == window) {
                    title.add(dock.toolWindow.getTitle());
                }
            }
            floatingFrame.setTitle(title.toString());
        }
    }

    // ------------------------------------------------------------------ classes

    /**
     * The docking instance of the main window. Docking into the center of the documents (or of the main window) would
     * put a tool window into the documents' tab group; it goes to the nearest edge instead.
     */
    private final class Docking extends DockingAPI {
        Docking(@NotNull Window mainWindow) {
            super(mainWindow);
        }

        @Override
        public void dock(Dockable source, Dockable target, DockingRegion region, double dividerProportion) {
            if (target == documents && region == DockingRegion.CENTER) {
                region = getNearestEdge(documents);
            }
            super.dock(source, target, region, dividerProportion);
        }

        @Override
        public void dock(Dockable dockable, Window window, DockingRegion region, double dividerProportion) {
            if (window == frame && dockable != documents && region == DockingRegion.CENTER) {
                region = getNearestEdge(root);
            }
            super.dock(dockable, window, region, dividerProportion);
        }

        /**
         * The edge of a component nearest to the mouse (a drop happens at the mouse).
         */
        @NotNull
        private DockingRegion getNearestEdge(@NotNull Component component) {
            PointerInfo pointer = MouseInfo.getPointerInfo();
            if (pointer == null || !component.isShowing()) {
                return DockingRegion.WEST;
            }
            Point point = new Point(pointer.getLocation());
            SwingUtilities.convertPointFromScreen(point, component);
            int west = point.x;
            int east = component.getWidth() - point.x;
            int north = point.y;
            int south = component.getHeight() - point.y;
            int min = Math.min(Math.min(west, east), Math.min(north, south));
            if (min == west) {
                return DockingRegion.WEST;
            } else if (min == east) {
                return DockingRegion.EAST;
            } else if (min == north) {
                return DockingRegion.NORTH;
            }
            return DockingRegion.SOUTH;
        }
    }

    /**
     * Docking root of the main window: the Islands canvas with the tool window cards. It paints the canvas and the
     * card backgrounds, then (after the children) covers the card corners outside the rounded shape. It is the painting
     * origin, so a repaint of any child (a tree row, a header button) repaints the corners over it too.
     */
    private final class Root extends RootDockingPanelAPI {
        Root(@NotNull DockingAPI docking, @NotNull JFrame frame) {
            super(docking, frame);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color canvas = UIManager.getColor("Chromite.island.canvas"); //$NON-NLS-1$
            if (canvas != null) {
                setBackground(canvas);
            }
            // Islands: the canvas shows as a gap (Chromite.island.gap) left and right of the outer cards
            int gap = UIScale.scale(Math.max(0, UIManager.getInt("Chromite.island.gap"))); //$NON-NLS-1$
            setBorder(BorderFactory.createEmptyBorder(0, gap, 0, gap));
        }

        @Override
        protected boolean isPaintingOrigin() {
            return true;
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            Color cardBackground = UIManager.getColor("Chromite.island.background"); //$NON-NLS-1$
            if (cardBackground == null) {
                return;
            }
            int arc = IslandPanel.getArc();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(cardBackground);
                for (Rectangle card : getCards()) {
                    g2.fill(new RoundRectangle2D.Float(card.x, card.y, card.width, card.height, arc, arc));
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        public void paint(Graphics g) {
            super.paint(g);
            for (Rectangle card : getCards()) {
                IslandPanel.paintCorners(g, card);
            }
        }

        /**
         * Bounds of the tool window cards (dockables and tab groups) in this panel; the documents are not a card (the
         * document tabs and the results are cards of their own, {@link IslandPanel}).
         */
        @NotNull
        private List<Rectangle> getCards() {
            List<Rectangle> cards = new ArrayList<>();
            collectCards(this, cards);
            return cards;
        }

        private void collectCards(@NotNull Container container, @NotNull List<Rectangle> cards) {
            for (Component child : container.getComponents()) {
                if (!child.isVisible() || child instanceof Dockable) {
                    continue;
                }
                if (child instanceof DockedSimplePanel || child instanceof DockedTabbedPanel) {
                    if (!SwingUtilities.isDescendingFrom(documents, child)) {
                        Rectangle bounds = SwingUtilities.convertRectangle(container, child.getBounds(), this);
                        Insets insets = ((JComponent) child).getInsets();
                        cards.add(new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                            bounds.width - insets.left - insets.right, bounds.height - insets.top - insets.bottom));
                    }
                } else if (child instanceof Container nested) {
                    collectCards(nested, cards);
                }
            }
        }
    }

    /**
     * A tool window in the docking layout.
     */
    private final class Dock implements ToolWindow.Host {
        private final ToolWindow toolWindow;
        private final boolean shownByDefault;
        private final List<Place> defaultPlaces;
        private ToolWindowDockable dockable;
        @Nullable
        private Place restorePlace;

        Dock(@NotNull ToolWindow toolWindow, boolean shownByDefault, @NotNull List<Place> defaultPlaces) {
            this.toolWindow = toolWindow;
            this.shownByDefault = shownByDefault;
            this.defaultPlaces = defaultPlaces;
        }

        @NotNull
        String getId() {
            return toolWindow.getId();
        }

        @Override
        public boolean isShown() {
            return dockable != null && docking.isDocked(dockable);
        }

        @Override
        public void setShown(boolean shown) {
            if (shown == isShown()) {
                if (shown) {
                    docking.bringToFront(dockable);
                }
                return;
            }
            if (shown) {
                show(this);
            } else {
                hide(this);
            }
        }
    }

    /**
     * The dockable of a tool window: its content as an Islands card body, with {@link Header} as the library header
     * (the drag handle).
     */
    private final class ToolWindowDockable extends JPanel implements Dockable {
        private final Dock dock;

        ToolWindowDockable(@NotNull Dock dock, @NotNull JComponent content) {
            super(new BorderLayout());
            this.dock = dock;
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
        public String getPersistentID() {
            return dock.getId();
        }

        @Override
        public String getTabText() {
            return dock.toolWindow.getTitle();
        }

        @Override
        public boolean isAutoHideAllowed() {
            return true;
        }

        @Override
        public boolean isWrappableInScrollpane() {
            // The contents scroll by themselves
            return false;
        }

        /**
         * No minimum width of its own: the {@link Header} sets the card's minimum width. The library's split panes
         * never make a card narrower than its minimum, and the content's minimum width is the full width of its labels
         * (a long object name in Properties), tool bars, or a wrapping text area's current width (so a card could only
         * grow). The content is laid out narrower instead: labels end with an ellipsis, tool bars overflow.
         */
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, super.getMinimumSize().height);
        }

        @Override
        public DockingHeaderUI createHeaderUI(HeaderController headerController, HeaderModel headerModel) {
            return new Header(dock);
        }
    }

    /**
     * Tool window header (size.header.toolWindow + a 1px border.subtle line below, semibold title on the left,
     * [⋮ options] [— hide] on the right; sizes from {@link UiSizes}). The library uses it as the drag handle: the title label has no mouse
     * listeners, so a press on it reaches the header's drag recognizer. The border is recreated on theme changes,
     * because its color comes from the look and feel.
     */
    private final class Header extends JPanel implements DockingHeaderUI {
        private final Dock dock;
        private final JLabel titleLabel;
        private final JButton optionsButton;
        private final JPanel tools;

        Header(@NotNull Dock dock) {
            super(new BorderLayout(UIScale.scale(8), 0));
            this.dock = dock;
            titleLabel = new JLabel(dock.toolWindow.getTitle());
            titleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
            Action optionsAction = new AbstractAction(DockingMessages.docking_options) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    displaySettingsMenu(optionsButton);
                }
            };
            optionsButton = UiIcons.createActionButton(optionsAction, "more"); //$NON-NLS-1$
            // Icon buttons are centered vertically in the header (GridBagLayout centers its row)
            tools = new JPanel(new GridBagLayout());
            tools.setOpaque(false);
            tools.add(optionsButton);
            tools.add(dock.toolWindow.createHideButton());
            add(titleLabel, BorderLayout.CENTER);
            add(tools, BorderLayout.EAST);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            Color background = UIManager.getColor("Chromite.island.background"); //$NON-NLS-1$
            if (background != null) {
                setBackground(background);
            }
            Color separatorColor = UIManager.getColor("Separator.foreground"); //$NON-NLS-1$
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, separatorColor != null ? separatorColor : Color.GRAY),
                UiSizes.createPadding(UiSizes.TOOL_WINDOW_HEADER_PADDING)));
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, UiSizes.get(UiSizes.TOOL_WINDOW_HEADER_HEIGHT) + 1);
        }

        /**
         * The minimum width of the tool window card ({@link ToolWindowDockable} has none): the icon buttons at full
         * size and the title's first character with the ellipsis, so a narrow card still shows which tool window it is
         * and keeps its options and hide buttons (the title is in the center of the layout, so it gets narrower first).
         * Not the title's full width: a long title (English, larger fonts) would keep the card wide.
         * <p>
         * Same height as preferred: a short card lays its header out at the minimum size (the library's GridBagLayout),
         * which would make it lower than in other cards.
         */
        @Override
        public Dimension getMinimumSize() {
            Insets insets = getInsets();
            int gap = ((BorderLayout) getLayout()).getHgap();
            int width = insets.left + getMinimumTitleWidth() + gap + tools.getPreferredSize().width + insets.right;
            return new Dimension(width, getPreferredSize().height);
        }

        private int getMinimumTitleWidth() {
            String title = titleLabel.getText();
            if (CommonUtils.isEmpty(title)) {
                return 0;
            }
            Insets insets = titleLabel.getInsets();
            String firstCharacter = title.substring(0, title.offsetByCodePoints(0, 1));
            int clippedWidth = titleLabel.getFontMetrics(titleLabel.getFont()).stringWidth(firstCharacter + CLIPPED_TEXT_END)
                + insets.left + insets.right;
            return Math.min(clippedWidth, titleLabel.getPreferredSize().width);
        }

        @Override
        public void update() {
            titleLabel.setText(dock.toolWindow.getTitle());
        }

        /**
         * Options menu: Float, Dock (back from a floating window or auto hide), Auto Hide, Hide.
         */
        @Override
        public void displaySettingsMenu(JButton settings) {
            boolean docked = docking.isDocked(dock.dockable);
            boolean autoHidden = docked && docking.isHidden(dock.dockable);
            boolean inMainWindow = isInMainWindow(dock.dockable);
            JMenuItem floatItem = new JMenuItem(DockingMessages.docking_float);
            floatItem.setEnabled(inMainWindow);
            floatItem.addActionListener(e -> floatWindow(dock));
            JMenuItem dockItem = new JMenuItem(DockingMessages.docking_dock);
            dockItem.setEnabled(docked && !inMainWindow);
            dockItem.addActionListener(e -> dockToMainWindow(dock));
            JCheckBoxMenuItem autoHideItem = new JCheckBoxMenuItem(DockingMessages.docking_auto_hide, autoHidden);
            autoHideItem.setEnabled(inMainWindow || autoHidden);
            autoHideItem.addActionListener(e -> toggleAutoHide(dock));
            JMenuItem hideItem = new JMenuItem(DockingMessages.docking_hide);
            hideItem.addActionListener(e -> dock.toolWindow.setShown(false));
            JPopupMenu menu = new JPopupMenu();
            menu.add(floatItem);
            menu.add(dockItem);
            menu.add(autoHideItem);
            menu.addSeparator();
            menu.add(hideItem);
            menu.show(settings, 0, settings.getHeight());
        }

        @Override
        public void setBackgroundOverride(Color color) {
            // Only used by the library's active highlighter, which is off
        }

        @Override
        public void setForegroundOverride(Color color) {
            // Only used by the library's active highlighter, which is off
        }
    }

    /**
     * The document area (document tabs and the query window results): fixed in the main window, without a header, so it
     * can't be dragged, floated or auto-hidden. It is closable for the library only: a non-closable dockable that gets
     * undocked (as every dockable is while a layout is restored) is docked again at once by the library.
     */
    private static final class DocumentsDockable extends JPanel implements Dockable {
        DocumentsDockable(@NotNull JComponent documentArea) {
            super(new BorderLayout());
            setOpaque(false);
            add(documentArea, BorderLayout.CENTER);
        }

        @Override
        public String getPersistentID() {
            return DOCUMENTS_ID;
        }

        @Override
        public int getType() {
            // Not the type of the tool windows: the library's display() never docks a tool window into it
            return 1;
        }

        @Override
        public String getTabText() {
            return ""; //$NON-NLS-1$
        }

        @Override
        public boolean isFloatingAllowed() {
            return false;
        }

        @Override
        public boolean isLimitedToWindow() {
            return true;
        }

        @Override
        public boolean isWrappableInScrollpane() {
            return false;
        }

        @Override
        public DockingHeaderUI createHeaderUI(HeaderController headerController, HeaderModel headerModel) {
            return new EmptyHeader();
        }
    }

    /**
     * Zero-size header of the document area: nothing to show and nothing to drag.
     */
    private static final class EmptyHeader extends JComponent implements DockingHeaderUI {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(0, 0);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public void update() {
        }

        @Override
        public void displaySettingsMenu(JButton settings) {
        }

        @Override
        public void setBackgroundOverride(Color color) {
        }

        @Override
        public void setForegroundOverride(Color color) {
        }
    }
}
