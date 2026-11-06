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
package com.seizia.chromite.model.admin.dashboard;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.dashboard.registry.DashboardItemConfiguration;
import org.jkiss.utils.CommonUtils;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * 대시보드 차트 제목·설명과 계열 이름의 화면 문구.
 *
 * 설계: DBeaver의 차트 정의({@code ext.postgresql}, {@code ext.mysql}의 {@code plugin.xml})는 제목 대부분과 계열 이름(질의의 열 별칭,
 * {@code mapLabels})을 영어로 바로 적었다. upstream 파일을 고치지 않고 한국어로 보이도록 차트 ID와 계열 이름을 키로 한 번역표
 * ({@code DashboardLabels.properties}, {@code _ko})를 둔다. 키가 정해져 있지 않아(차트·계열마다 다름) NLS 필드 클래스 대신
 * {@link ResourceBundle}로 찾는다. 표에 없으면 정의의 문구(Chromite 정의는 이 번들의 {@code OSGI-INF/l10n/bundle*.properties}로 이미 번역됨)를 그대로 쓴다.
 * 서버가 돌려주는 값(MySQL 명령 이름 등)인 계열 이름은 번역하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-dashboard.md)
 */
public final class DashboardLabels {

    private static final Log log = Log.getLog(DashboardLabels.class);
    private static final String BUNDLE_NAME = "com.seizia.chromite.model.admin.dashboard.DashboardLabels"; //$NON-NLS-1$

    private static ResourceBundle bundle;

    private DashboardLabels() {
    }

    @NotNull
    public static String getTitle(@NotNull DashboardItemConfiguration item) {
        return find("title." + item.getId(), item.getName()); //$NON-NLS-1$
    }

    @NotNull
    public static String getDescription(@NotNull DashboardItemConfiguration item) {
        return find("description." + item.getId(), CommonUtils.notEmpty(item.getDescription())); //$NON-NLS-1$
    }

    @NotNull
    public static String getSeries(@NotNull String seriesName) {
        return find("series." + seriesName.replaceAll("[^A-Za-z0-9]", "_"), seriesName); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @NotNull
    private static String find(@NotNull String key, @NotNull String defaultText) {
        ResourceBundle labels = getBundle();
        if (labels != null && labels.containsKey(key)) {
            return labels.getString(key);
        }
        return defaultText;
    }

    private static synchronized ResourceBundle getBundle() {
        if (bundle == null) {
            try {
                // The locale the platform runs with (-nl or the operating system), like the NLS message classes
                bundle = ResourceBundle.getBundle(BUNDLE_NAME, Locale.getDefault(), DashboardLabels.class.getClassLoader());
            } catch (MissingResourceException e) {
                log.debug("Dashboard labels not found", e);
            }
        }
        return bundle;
    }
}
