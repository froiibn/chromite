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
import javax.swing.border.Border;
import java.awt.*;

/**
 * Chromite 컴포넌트의 치수 토큰(밀도): 테마 properties의 {@code Chromite.size.*} 키를 읽는다.
 *
 * 설계:
 * <ul>
 *     <li>화면 밀도를 정하는 값을 한곳(테마 properties의 "Sizes" 절)에 모은다. FlatLaf 키(트리·그리드 행, 메뉴 항목, 도구 막대 단추 여백,
 *     컨트롤 여백)는 원래 거기에 있었고, 컴포넌트 코드에 흩어져 있던 값(도구 창 머리말·도구 막대·탭·상태 줄 높이, 트리 아이콘 간격 등)을
 *     {@code Chromite.size.*} 키로 옮겼다. 그래서 나중의 촘촘한 모드(design-spec "촘촘한 모드")는 이 키들의 값만 바꾸면 된다
 *     ({@code FlatLaf#setExtraDefaults}로 덮어쓰기).</li>
 *     <li>컴포넌트는 만들 때 값을 읽는다(크기 계산이 매번 부르는 곳은 그때마다 읽는다). 두 테마의 값이 같으므로 테마를 바꿔도 다시 읽을 필요가 없다.</li>
 *     <li>FlatLaf 스타일 문자열은 {@code $키} 참조로 같은 키를 읽는다({@link #TOOL_TABS_STYLE}). 스타일은 {@code updateUI} 때마다 다시 적용된다.</li>
 *     <li>값은 배율 1의 논리 픽셀이고 {@link UIScale}로 배율을 적용한다(FlatLaf 키와 같음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: 하드코딩한 치수를 테마 키로 모으고 기본 밀도를 B("조금 촘촘")로 바꿈 (docs/chromite/design-spec.md "치수 토큰")
 */
final class UiSizes {

    /** size.header.toolWindow: tool window header height (a 1px line below comes on top). */
    static final String TOOL_WINDOW_HEADER_HEIGHT = "Chromite.size.toolWindowHeader.height"; //$NON-NLS-1$
    /** Tool window header padding (title on the left, buttons on the right). */
    static final String TOOL_WINDOW_HEADER_PADDING = "Chromite.size.toolWindowHeader.padding"; //$NON-NLS-1$
    /** size.toolbar.toolWindow: tool bars at the top of tool windows and tool documents. */
    static final String TOOL_WINDOW_TOOL_BAR_PADDING = "Chromite.size.toolWindowToolBar.padding"; //$NON-NLS-1$
    /** size.toolbar.main: the main tool bar. */
    static final String MAIN_TOOL_BAR_PADDING = "Chromite.size.mainToolBar.padding"; //$NON-NLS-1$
    /** size.toolbar.editor: the query window and table designer tool bars (28 controls). */
    static final String EDITOR_TOOL_BAR_PADDING = "Chromite.size.editorToolBar.padding"; //$NON-NLS-1$
    /** size.tab.editor: document tab row height. */
    static final String DOCUMENT_TAB_HEIGHT = "Chromite.size.documentTab.height"; //$NON-NLS-1$
    /** Distance of the selected document tab's pill from the top and bottom of the tab row. */
    static final String DOCUMENT_TAB_PILL_INSET = "Chromite.size.documentTab.pillInset"; //$NON-NLS-1$
    /** size.tab.tool: tool tab row height (results, tool window tabs, detail tabs). */
    static final String TOOL_TAB_HEIGHT = "Chromite.size.toolTab.height"; //$NON-NLS-1$
    /** size.statusBar: the main window's status bar. */
    static final String STATUS_BAR_HEIGHT = "Chromite.size.statusBar.height"; //$NON-NLS-1$
    /** size.statusLine.query: the query window's status line (a 1px line above comes on top). */
    static final String QUERY_STATUS_LINE_HEIGHT = "Chromite.size.queryStatusLine.height"; //$NON-NLS-1$
    /** size.row.properties: Properties grid rows. */
    static final String PROPERTY_ROW_HEIGHT = "Chromite.size.propertyRow.height"; //$NON-NLS-1$
    /** Tree rows: gap between the node icon and its text. */
    static final String TREE_ICON_TEXT_GAP = "Chromite.size.tree.iconTextGap"; //$NON-NLS-1$
    /** Tree rows: gap before the secondary text (and around a connection pill). */
    static final String TREE_SECONDARY_GAP = "Chromite.size.tree.secondaryGap"; //$NON-NLS-1$
    /** Result grid filter bar padding. */
    static final String FILTER_BAR_PADDING = "Chromite.size.filterBar.padding"; //$NON-NLS-1$
    /** Result viewer status bar (row count, fetch links) padding. */
    static final String VIEWER_STATUS_PADDING = "Chromite.size.viewerStatus.padding"; //$NON-NLS-1$

    /**
     * FlatLaf style of tool tabs (results, tool window tabs, detail tabs): size.tab.tool with a 3px underline.
     */
    static final String TOOL_TABS_STYLE = "tabHeight: $" + TOOL_TAB_HEIGHT + "; tabSelectionHeight: 3"; //$NON-NLS-1$ //$NON-NLS-2$
    /**
     * FlatLaf style of the document tabs: size.tab.editor, no underline (the selected tab is a pill).
     */
    static final String DOCUMENT_TABS_STYLE = "tabHeight: $" + DOCUMENT_TAB_HEIGHT + "; tabSelectionHeight: 0"; //$NON-NLS-1$ //$NON-NLS-2$

    private UiSizes() {
    }

    /**
     * The size token, scaled.
     */
    static int get(@NotNull String key) {
        return UIScale.scale(UIManager.getInt(key));
    }

    /**
     * An empty border of the padding token, scaled.
     */
    @NotNull
    static Border createPadding(@NotNull String key) {
        Insets insets = getInsets(key);
        return BorderFactory.createEmptyBorder(insets.top, insets.left, insets.bottom, insets.right);
    }

    /**
     * The padding token, scaled.
     */
    @NotNull
    static Insets getInsets(@NotNull String key) {
        Insets insets = UIManager.getInsets(key);
        return insets == null ? new Insets(0, 0, 0, 0) : UIScale.scale(insets);
    }
}
