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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.data.DBDDataFormatter;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.registry.formatter.DataFormatterDescriptor;
import com.seizia.chromite.registry.formatter.DataFormatterProfile;
import com.seizia.chromite.registry.formatter.DataFormatterRegistry;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.properties.PropertySourceCustom;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tools &gt; Options &gt; Data Formats, and the same page for one connection: the data formatter profile (locale, and the
 * number, date and time patterns) that the value handlers use to show values (SWT {@code PrefPageDataFormat}).
 *
 * 설계:
 * <ul>
 *     <li>모델: 형식 프로필은 설정 키 {@code dataformat.profile.*}(로캘)와 {@code dataformat.type.<형식>.<속성>}이다
 *     ({@link DataFormatterProfile}). 전역 프로필은 플랫폼 설정 저장소, 연결의 프로필({@code DataSourceDescriptor.getDataFormatterProfile})은 연결의
 *     설정 저장소에 있고 부모가 플랫폼 저장소라 연결이 자기 값을 갖지 않은 키는 전역 값을 따른다. 값 처리기는 값을 표시할 때마다
 *     {@code createFormatter}로 프로필을 다시 읽으므로 다음에 값을 가져올 때부터 보인다(다시 연결하지 않음).</li>
 *     <li>화면: 로캘, 형식 종류(숫자, 날짜, 시간, 타임스탬프 …, 등록된 {@link DataFormatterDescriptor}), 그 형식의 속성 표({@link ConnectionPropertiesTable}),
 *     견본. 속성의 기본값은 로캘에 따라 달라서({@code DBDDataFormatterSample.getDefaultProperties}) 로캘을 바꾸면 기본값과 견본이 바뀐다.</li>
 *     <li>저장: 로캘 세 키를 직접 쓴다. {@code DataFormatterProfile.saveProfile}은 저장소를 저장(연결이면 연결 설정 파일 쓰기)까지 하므로, 편집 중인
 *     연결 사본에는 쓰지 않는다. 속성은 전역이면 기본값과 같은 값을 기본값으로 되돌리고, 연결별이면 모든 값을 연결의 값으로 쓴다
 *     ({@link SettingsPage.Writer}).</li>
 *     <li>두지 않은 것: SWT의 이름 있는 프로필(Manage profiles, {@code dataformat-profiles.xml})은 데이터 내보내기 작업 설정의
 *     {@code formatterProfile}만 읽는다. Swing 내보내기 화면은 프로필을 고르지 않으므로 화면을 만들지 않았다.
 *     날짜·숫자를 데이터베이스 형식으로 보이는 세 설정은 Query Results 페이지({@link ResultSetOptionsPage})에 있다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "Data Formats")
 */
final class DataFormatOptionsPage extends SettingsPage {

    private static final Log log = Log.getLog(DataFormatOptionsPage.class);

    private final List<DataFormatterDescriptor> formatters = DataFormatterRegistry.getInstance().getDataFormatters();
    // The edited properties of every formatter type; defaults follow the selected locale
    private final Map<String, PropertySourceCustom> sources = new HashMap<>();
    private final JComboBox<Locale> localeCombo = new JComboBox<>();
    private final JComboBox<DataFormatterDescriptor> typeCombo = new JComboBox<>();
    private final ConnectionPropertiesTable propertiesTable = new ConnectionPropertiesTable(false);
    private final JTextField sampleField = new JTextField(30);
    private boolean loading;

    /**
     * @param global the global settings (Options, loaded here); otherwise one connection's settings, loaded by the caller
     */
    DataFormatOptionsPage(boolean global) {
        Locale[] locales = Arrays.stream(Locale.getAvailableLocales())
            .filter(locale -> !locale.getLanguage().isEmpty())
            .sorted(Comparator.comparing(Locale::getDisplayName, String.CASE_INSENSITIVE_ORDER))
            .toArray(Locale[]::new);
        localeCombo.setModel(new DefaultComboBoxModel<>(locales));
        localeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Locale locale) {
                    setText(locale.getDisplayName() + " (" + locale.toLanguageTag() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return this;
            }
        });
        localeCombo.setToolTipText(SettingsMessages.dataformat_locale_tip);
        localeCombo.addActionListener(e -> localeChanged());
        for (DataFormatterDescriptor formatter : formatters) {
            typeCombo.addItem(formatter);
        }
        typeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof DataFormatterDescriptor formatter) {
                    setText(formatter.getName());
                }
                return this;
            }
        });
        typeCombo.addActionListener(e -> showType());
        propertiesTable.addValueListener(this::updateSample);
        propertiesTable.setPreferredSize(new Dimension(520, 220));
        sampleField.setEditable(false);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.dataformat_section);
        row = OptionsDialog.addRow(form, row, SettingsMessages.dataformat_locale, localeCombo, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.dataformat_type, typeCombo, null);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 6, 0);
        form.add(propertiesTable, c);
        OptionsDialog.addRow(form, row, SettingsMessages.dataformat_sample, sampleField, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        if (global) {
            load(DBWorkbench.getPlatform().getPreferenceStore());
        }
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.dataformat_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        List<String> keys = new ArrayList<>(List.of(
            DataFormatterProfile.PROP_LANGUAGE, DataFormatterProfile.PROP_COUNTRY, DataFormatterProfile.PROP_VARIANT));
        for (DataFormatterDescriptor formatter : formatters) {
            for (DBPPropertyDescriptor property : formatter.getProperties()) {
                keys.add(getKey(formatter, property));
            }
        }
        return keys;
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        // A profile object only to read the store: the locale and the values that differ from the locale's defaults
        DataFormatterProfile profile = new DataFormatterProfile(getTitle(), store);
        Locale locale = profile.getLocale();
        sources.clear();
        for (DataFormatterDescriptor formatter : formatters) {
            PropertySourceCustom source = new PropertySourceCustom(formatter.getProperties(), profile.getFormatterProperties(store, formatter.getId()));
            source.setDefaultValues(formatter.getSample().getDefaultProperties(locale));
            sources.put(formatter.getId(), source);
        }
        loading = true;
        try {
            if (((DefaultComboBoxModel<Locale>) localeCombo.getModel()).getIndexOf(locale) < 0) {
                localeCombo.addItem(locale);
            }
            localeCombo.setSelectedItem(locale);
        } finally {
            loading = false;
        }
        showType();
    }

    @Override
    void save(@NotNull Writer writer) {
        propertiesTable.stopEditing();
        DBPPreferenceStore store = writer.getStore();
        Locale locale = getSelectedLocale();
        if (writer.isOverride() || !locale.equals(new DataFormatterProfile(getTitle(), store).getLocale())) {
            writer.setString(DataFormatterProfile.PROP_LANGUAGE, locale.getLanguage());
            writer.setString(DataFormatterProfile.PROP_COUNTRY, locale.getCountry());
            writer.setString(DataFormatterProfile.PROP_VARIANT, locale.getVariant());
        }
        for (DataFormatterDescriptor formatter : formatters) {
            PropertySourceCustom source = sources.get(formatter.getId());
            if (source == null) {
                continue;
            }
            Map<String, Object> defaults = formatter.getSample().getDefaultProperties(locale);
            Map<String, Object> values = source.getPropertiesWithDefaults();
            for (DBPPropertyDescriptor property : formatter.getProperties()) {
                String key = getKey(formatter, property);
                Object value = values.get(property.getId());
                boolean isDefault = value == null
                    || CommonUtils.toString(value).equals(CommonUtils.toString(defaults.get(property.getId())));
                if (value == null || (isDefault && !writer.isOverride())) {
                    // No value, or the locale's default in the global profile: the key goes back to its default
                    if (!store.isDefault(key)) {
                        store.setToDefault(key);
                        writer.markChanged();
                    }
                } else if (writer.isOverride() || !CommonUtils.toString(value).equals(store.getString(key))) {
                    PrefUtils.setPreferenceValue(store, key, value);
                    writer.markChanged();
                }
            }
        }
    }

    @NotNull
    private static String getKey(@NotNull DataFormatterDescriptor formatter, @NotNull DBPPropertyDescriptor property) {
        return DataFormatterProfile.DATAFORMAT_TYPE_PREFIX + formatter.getId() + "." + property.getId(); //$NON-NLS-1$
    }

    @NotNull
    private Locale getSelectedLocale() {
        return localeCombo.getSelectedItem() instanceof Locale locale ? locale : Locale.getDefault();
    }

    /**
     * The defaults of every type follow the locale.
     */
    private void localeChanged() {
        if (loading) {
            return;
        }
        propertiesTable.stopEditing();
        Locale locale = getSelectedLocale();
        for (DataFormatterDescriptor formatter : formatters) {
            PropertySourceCustom source = sources.get(formatter.getId());
            if (source != null) {
                source.setDefaultValues(formatter.getSample().getDefaultProperties(locale));
            }
        }
        showType();
    }

    private void showType() {
        DataFormatterDescriptor formatter = getSelectedFormatter();
        propertiesTable.setSource(formatter == null ? null : sources.get(formatter.getId()), null);
        updateSample();
    }

    @Nullable
    private DataFormatterDescriptor getSelectedFormatter() {
        return typeCombo.getSelectedItem() instanceof DataFormatterDescriptor formatter ? formatter : null;
    }

    /**
     * Formats the type's sample value with the shown (unsaved) properties; a pattern error is shown in the field.
     */
    private void updateSample() {
        DataFormatterDescriptor formatter = getSelectedFormatter();
        PropertySourceCustom source = formatter == null ? null : sources.get(formatter.getId());
        if (source == null) {
            sampleField.setText(""); //$NON-NLS-1$
            return;
        }
        try {
            DBDDataFormatter dataFormatter = formatter.createFormatter();
            dataFormatter.init(null, getSelectedLocale(), source.getPropertiesWithDefaults());
            sampleField.setText(CommonUtils.notEmpty(dataFormatter.formatValue(formatter.getSample().getSampleValue())));
        } catch (Exception e) {
            log.debug("Can't format the sample of " + formatter.getId(), e); //$NON-NLS-1$
            sampleField.setText(ErrorText.summary(e));
        }
    }
}
