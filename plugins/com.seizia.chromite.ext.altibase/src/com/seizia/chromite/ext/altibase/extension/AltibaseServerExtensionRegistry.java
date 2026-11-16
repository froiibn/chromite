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
package com.seizia.chromite.ext.altibase.extension;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.impl.AbstractDescriptor;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;

import java.util.ArrayList;
import java.util.List;

/**
 * The Altibase server extensions installed in this product (extension point {@value #EXTENSION_ID}).
 *
 * 설계: 확장 지점은 앱이 시작한 뒤 바뀌지 않으므로 처음 물을 때 한 번 읽는다. 확장 객체는 상태가 없으므로 기여마다 하나를 만들어 모든 연결이
 * 함께 쓴다. 만들 수 없는 확장(클래스 오류)은 한 번 기록하고 빼서 연결을 막지 않는다. 확장이 없으면 빈 목록이고 데이터 원본은 감지 세션을
 * 열지 않는다.
 *
 * 변경 이력:
 * - 2026-11-17 최초 작성(앱 변경 #139, docs/chromite/shardsphere.md)
 */
public final class AltibaseServerExtensionRegistry {

    public static final String EXTENSION_ID = "com.seizia.chromite.altibase.serverExtension"; //$NON-NLS-1$

    private static final Log log = Log.getLog(AltibaseServerExtensionRegistry.class);

    private static final String TAG_SERVER_EXTENSION = "serverExtension"; //$NON-NLS-1$
    private static final String ATTR_CLASS = "class"; //$NON-NLS-1$

    private static AltibaseServerExtensionRegistry instance;

    @NotNull
    private final List<AltibaseServerExtension> extensions;

    @NotNull
    public static synchronized AltibaseServerExtensionRegistry getInstance() {
        if (instance == null) {
            instance = new AltibaseServerExtensionRegistry();
        }
        return instance;
    }

    private AltibaseServerExtensionRegistry() {
        List<AltibaseServerExtension> list = new ArrayList<>();
        for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(EXTENSION_ID)) {
            if (!TAG_SERVER_EXTENSION.equals(element.getName())) {
                continue;
            }
            try {
                AltibaseServerExtensionDescriptor descriptor = new AltibaseServerExtensionDescriptor(element);
                list.add(descriptor.createExtension());
            } catch (DBException | RuntimeException | LinkageError e) {
                log.error("Can't create the Altibase server extension of " + element.getContributor().getName(), e);
            }
        }
        extensions = List.copyOf(list);
    }

    /**
     * The installed extensions, in the extension registry's order; empty when no bundle contributes one.
     */
    @NotNull
    public List<AltibaseServerExtension> getExtensions() {
        return extensions;
    }

    /**
     * Reads one {@code serverExtension} element.
     */
    private static final class AltibaseServerExtensionDescriptor extends AbstractDescriptor {

        private final ObjectType type;

        AltibaseServerExtensionDescriptor(@NotNull IConfigurationElement config) {
            super(config);
            type = new ObjectType(config, ATTR_CLASS);
        }

        @NotNull
        AltibaseServerExtension createExtension() throws DBException {
            return type.createInstance(AltibaseServerExtension.class);
        }
    }

}
