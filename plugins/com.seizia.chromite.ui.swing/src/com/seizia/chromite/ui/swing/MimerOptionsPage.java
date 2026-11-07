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
import com.seizia.chromite.ext.mimer.MimerConstants;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools &gt; Options &gt; Mimer SQL: the global Mimer settings of SWT's Mimer SQL page ({@code PrefPageMimer}).
 *
 * 설계:
 * <ul>
 *     <li>CASCADE 삭제 확인: {@code MimerCascadeDropUtil}이 개체 종류마다 {@code mimer.confirm.drop_cascade.<종류>}를 플랫폼 저장소에서 읽는다. 켜져
 *     있으면 그 종류의 CASCADE 삭제를 다시 묻지 않는다(확인 창의 "다시 묻지 않음"이 켬). 여기서는 종류마다 보고 끌 수 있다. SQL 실행 확인을 하는 연결
 *     유형의 연결은 이 값과 상관없이 늘 묻는다. 종류 목록은 모델의 {@link MimerConstants#DROP_CASCADE_CONFIRM_TYPES}다.</li>
 *     <li>공간 형식: {@code MimerGisUtils}가 결과를 가져올 때 플랫폼 저장소에서 읽는다(연결별 값 없음, 모델 설계). 모델은 이 키의 기본값을 읽기·쓰기
 *     전마다 등록한다(등록되지 않은 키는 끔을 저장하지 못함). 그 메서드가 모델 패키지 안에만 있어 이 페이지도 같은 기본값을 등록한다
 *     (SWT {@code PrefPageMimer}와 같은 방식, {@link #registerGisDefaults}).</li>
 *     <li>모두 전역 설정이라 연결 대화상자에는 넣지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class MimerOptionsPage extends SettingsPage {

    private final Map<String, JCheckBox> cascadeBoxes = new LinkedHashMap<>();
    private final JCheckBox locationBox = new JCheckBox(SettingsMessages.mimer_gis_location);
    private final JCheckBox coordinateBox = new JCheckBox(SettingsMessages.mimer_gis_coordinate);
    private final JCheckBox latLongBox = new JCheckBox(SettingsMessages.mimer_gis_latlong);
    private final JCheckBox gridTextBox = new JCheckBox(SettingsMessages.mimer_gis_grid_text);

    MimerOptionsPage() {
        JPanel cascadePanel = new JPanel(new GridLayout(0, 3, 12, 2));
        MimerConstants.DROP_CASCADE_CONFIRM_TYPES.forEach((typeKey, label) -> {
            JCheckBox box = new JCheckBox(label);
            cascadeBoxes.put(MimerConstants.PREF_DROP_CASCADE_CONFIRM_PREFIX + typeKey, box);
            cascadePanel.add(box);
        });
        JLabel cascadeHint = new JLabel(SettingsMessages.mimer_cascade_hint);
        cascadeHint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.mimer_section_cascade);
        row = OptionsDialog.addRow(form, row, null, cascadePanel, null);
        row = OptionsDialog.addRow(form, row, null, cascadeHint, null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.mimer_section_gis);
        row = OptionsDialog.addRow(form, row, null, locationBox, null);
        row = OptionsDialog.addRow(form, row, null, coordinateBox, null);
        row = OptionsDialog.addRow(form, row, null, latLongBox, null);
        OptionsDialog.addRow(form, row, null, gridTextBox, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        load(DBWorkbench.getPlatform().getPreferenceStore());
    }

    /**
     * The model's defaults of the spatial settings ({@code MimerGisUtils.registerGisDefaults}): only GIS_COORDINATE is not
     * shown as a geometry by default.
     */
    private static void registerGisDefaults(@NotNull DBPPreferenceStore store) {
        store.setDefault(MimerConstants.PREF_GIS_LOCATION_AUTO_MAP, true);
        store.setDefault(MimerConstants.PREF_GIS_COORDINATE_AUTO_MAP, false);
        store.setDefault(MimerConstants.PREF_GIS_LATLONG_AUTO_DECIMAL, true);
        store.setDefault(MimerConstants.PREF_GIS_GRID_POINT_TEXT, true);
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.mimer_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        List<String> keys = new ArrayList<>(cascadeBoxes.keySet());
        keys.addAll(List.of(MimerConstants.PREF_GIS_LOCATION_AUTO_MAP, MimerConstants.PREF_GIS_COORDINATE_AUTO_MAP,
            MimerConstants.PREF_GIS_LATLONG_AUTO_DECIMAL, MimerConstants.PREF_GIS_GRID_POINT_TEXT));
        return keys;
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        registerGisDefaults(store);
        cascadeBoxes.forEach((key, box) -> box.setSelected(store.getBoolean(key)));
        locationBox.setSelected(store.getBoolean(MimerConstants.PREF_GIS_LOCATION_AUTO_MAP));
        coordinateBox.setSelected(store.getBoolean(MimerConstants.PREF_GIS_COORDINATE_AUTO_MAP));
        latLongBox.setSelected(store.getBoolean(MimerConstants.PREF_GIS_LATLONG_AUTO_DECIMAL));
        gridTextBox.setSelected(store.getBoolean(MimerConstants.PREF_GIS_GRID_POINT_TEXT));
    }

    @Override
    void save(@NotNull Writer writer) {
        registerGisDefaults(writer.getStore());
        cascadeBoxes.forEach((key, box) -> writer.setBoolean(key, box.isSelected()));
        writer.setBoolean(MimerConstants.PREF_GIS_LOCATION_AUTO_MAP, locationBox.isSelected());
        writer.setBoolean(MimerConstants.PREF_GIS_COORDINATE_AUTO_MAP, coordinateBox.isSelected());
        writer.setBoolean(MimerConstants.PREF_GIS_LATLONG_AUTO_DECIMAL, latLongBox.isSelected());
        writer.setBoolean(MimerConstants.PREF_GIS_GRID_POINT_TEXT, gridTextBox.isSelected());
    }
}
