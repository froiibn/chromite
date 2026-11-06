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

import org.eclipse.core.runtime.Plugin;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.impl.preferences.BundlePreferenceStore;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import org.osgi.framework.BundleContext;

/**
 * Swing 프런트엔드 번들의 활성화 클래스.
 *
 * 설계: 모델 계층은 기본 설정 저장소와 상태 폴더를 "제품 번들"에서 얻는다
 * ({@link ModelPreferences#setMainBundle}, {@code BasePlatformImpl#getProductPlugin()}).
 * SWT 버전에서는 {@code com.seizia.chromite.core}가 이 역할을 하지만 Swing 제품에는 그 번들이 없으므로
 * 이 번들이 대신 맡는다. 헤드리스 앱({@code ChromiteTestActivator})과 같은 방식이다.
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 */
public class ChromiteSwingActivator extends Plugin {

    public static final String PLUGIN_ID = "com.seizia.chromite.ui.swing"; //$NON-NLS-1$

    private static ChromiteSwingActivator instance;

    private DBPPreferenceStore preferences;

    public static ChromiteSwingActivator getInstance() {
        return instance;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        ModelPreferences.setMainBundle(getBundle());
        preferences = new BundlePreferenceStore(getBundle());
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        instance = null;
        super.stop(context);
    }

    public DBPPreferenceStore getPreferences() {
        return preferences;
    }
}
