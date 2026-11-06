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
 * 설정 저장소는 {@link #start}가 아니라 앱이 작업 공간을 정하고 잠근 뒤에 만든다({@link #initPreferences}).
 * 이 번들은 앱 클래스를 읽을 때 앱보다 먼저 활성화되는데, {@code InstanceScope}를 처음 읽는 순간 Equinox가 정해지지 않은
 * 작업 공간 위치를 기본값 {@code <현재 폴더>/workspace}로 고정하기 때문이다(docs/chromite/workspace-location.md).
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 * - 2026-11-07 설정 저장소 생성을 {@link #initPreferences}로 옮김(작업 공간이 현재 폴더를 따라가던 문제)
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
    }

    /**
     * Creates the preference stores of this bundle and of the model ({@link ModelPreferences#setMainBundle}).
     * Called by the application once it has set and locked the workspace: the first read of the instance
     * preferences fixes the instance location, to Equinox's default {@code <user.dir>/workspace} if nothing set it.
     */
    void initPreferences() {
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
