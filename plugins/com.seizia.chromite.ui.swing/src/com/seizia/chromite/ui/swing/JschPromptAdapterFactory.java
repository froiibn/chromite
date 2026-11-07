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

import org.eclipse.core.runtime.IAdapterFactory;
import com.seizia.chromite.model.net.ssh.JSCHSessionController;
import com.seizia.chromite.model.net.ssh.JSCHUserInfoPromptProvider;

/**
 * JSch 연결이 쓸 Swing 질문 창({@link JschUserInfoPrompter})을 모델에 넘긴다.
 *
 * 설계: {@code JSCHSessionController}는 {@code GeneralUtils.adapt(this, JSCHUserInfoPromptProvider.class)}로 UI 질문 공급자를 찾고,
 * 없으면 화면 없는 기본값을 쓴다. SWT는 {@code net.ssh.ui} 번들의 어댑터 팩터리({@code JSCHUIPromptProviderAdapterFactory})로
 * 공급자를 등록했고, Swing 제품에는 그 번들이 없으므로 같은 등록을 이 번들의 {@code plugin.xml}에 둔다.
 * 어댑터는 {@code JSCHSessionController}에만 걸리므로 기본 SSH 구현(SSHJ)에는 영향이 없다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: JSch 호스트 키 확인·OTP 입력 (docs/chromite/swing-auth-prompts.md)
 */
public class JschPromptAdapterFactory implements IAdapterFactory {

    private static final Class<?>[] ADAPTERS = {JSCHUserInfoPromptProvider.class};

    @Override
    public <T> T getAdapter(Object adaptableObject, Class<T> adapterType) {
        if (adaptableObject instanceof JSCHSessionController && adapterType == JSCHUserInfoPromptProvider.class) {
            JSCHUserInfoPromptProvider provider = (configuration, session) -> new JschUserInfoPrompter(configuration);
            return adapterType.cast(provider);
        }
        return null;
    }

    @Override
    public Class<?>[] getAdapterList() {
        return ADAPTERS;
    }
}
