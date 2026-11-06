/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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

package com.seizia.chromite.ui.controls.resultset;

/**
 * Result set command constants
 */
public interface IResultSetCommands {

    String CMD_TOGGLE_PANELS = "com.seizia.chromite.core.resultset.grid.togglePreview";
    String CMD_ACTIVATE_PANELS = "com.seizia.chromite.core.resultset.grid.activatePreview";
    String CMD_TOGGLE_MAXIMIZE = "com.seizia.chromite.core.resultset.grid.togglePanelMaximize";
    String CMD_TOGGLE_LAYOUT = "com.seizia.chromite.core.resultset.grid.toggleLayout";
    String CMD_TOGGLE_MODE = "com.seizia.chromite.core.resultset.toggleMode";
    String CMD_FOCUS_FILTER = "com.seizia.chromite.core.resultset.focus.filter";
    String CMD_SWITCH_PRESENTATION = "com.seizia.chromite.core.resultset.switchPresentation";
    String CMD_ROW_FIRST = "com.seizia.chromite.core.resultset.row.first";
    String CMD_ROW_PREVIOUS = "com.seizia.chromite.core.resultset.row.previous";
    String CMD_ROW_NEXT = "com.seizia.chromite.core.resultset.row.next";
    String CMD_ROW_LAST = "com.seizia.chromite.core.resultset.row.last";
    String CMD_FETCH_PAGE = "com.seizia.chromite.core.resultset.fetch.page";
    String CMD_FETCH_ALL = "com.seizia.chromite.core.resultset.fetch.all";
    String CMD_COUNT = "com.seizia.chromite.core.resultset.count";
    String CMD_ROW_EDIT = "com.seizia.chromite.core.resultset.row.edit";
    String CMD_ROW_EDIT_INLINE = "com.seizia.chromite.core.resultset.row.edit.inline";
    String CMD_ROW_ADD = "com.seizia.chromite.core.resultset.row.add";
    String CMD_ROW_ADD_BEFORE = "com.seizia.chromite.core.resultset.row.add.before";
    String CMD_ROW_COPY = "com.seizia.chromite.core.resultset.row.copy";
    String CMD_ROW_COPY_BEFORE = "com.seizia.chromite.core.resultset.row.copy.before";
    String CMD_ROW_COPY_FROM_ABOVE = "com.seizia.chromite.core.resultset.row.copy.from.above";
    String CMD_ROW_COPY_FROM_BELOW = "com.seizia.chromite.core.resultset.row.copy.from.below";
    String CMD_ROW_DELETE = "com.seizia.chromite.core.resultset.row.delete";
    String CMD_CELL_SET_NULL = "com.seizia.chromite.core.resultset.cell.setNull";
    String CMD_CELL_SET_DEFAULT = "com.seizia.chromite.core.resultset.cell.setDefault";
    String CMD_CELL_RESET = "com.seizia.chromite.core.resultset.cell.reset";
    String CMD_APPLY_CHANGES = "com.seizia.chromite.core.resultset.applyChanges";
    String CMD_APPLY_AND_COMMIT_CHANGES = "com.seizia.chromite.core.resultset.applyAndCommitChanges";
    String CMD_REJECT_CHANGES = "com.seizia.chromite.core.resultset.rejectChanges";
    String CMD_GENERATE_SCRIPT = "com.seizia.chromite.core.resultset.generateScript";
    String CMD_TOGGLE_CONFIRM_SAVE = "com.seizia.chromite.core.resultset.toggleConfirmSave";
    String CMD_NAVIGATE_LINK = "com.seizia.chromite.core.resultset.navigateLink";
    String CMD_FILTER_MENU = "com.seizia.chromite.core.resultset.filterMenu";
    String CMD_FILTER_MENU_DISTINCT = "com.seizia.chromite.core.resultset.filterMenu.distinct";
    String CMD_FILTER_EDIT_SETTINGS = "com.seizia.chromite.core.resultset.filterSettings";
    String CMD_FILTER_SAVE_SETTING = "com.seizia.chromite.core.resultset.filterSave";
    String CMD_FILTER_RESET_SETTING = "com.seizia.chromite.core.resultset.filterReset";
    String CMD_FILTER_CLEAR_SETTING = "com.seizia.chromite.core.resultset.filterClear";
    String CMD_REFERENCES_MENU = "com.seizia.chromite.core.resultset.referencesMenu";
    String CMD_COPY_COLUMN_NAMES = "com.seizia.chromite.core.resultset.grid.copyColumnNames";
    String CMD_COPY_ROW_NAMES = "com.seizia.chromite.core.resultset.grid.copyRowNames";
    String CMD_EXPORT = "com.seizia.chromite.core.resultset.export";
    String CMD_ZOOM_IN = "com.seizia.chromite.core.resultset.zoomIn";
    String CMD_ZOOM_OUT = "com.seizia.chromite.core.resultset.zoomOut";
    String CMD_TOGGLE_ORDER = "com.seizia.chromite.core.resultset.toggleOrder";
    String CMD_SELECT_ROW_COLOR = "com.seizia.chromite.core.resultset.grid.selectRowColor";
    String CMD_GO_TO_COLUMN = "com.seizia.chromite.core.resultset.grid.gotoColumn";
    String CMD_GO_TO_ROW = "com.seizia.chromite.core.resultset.grid.gotoRow";
    String PARAM_EXPORT_WITH_PARAM = "exportWithParameter";
}
