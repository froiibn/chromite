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
package com.seizia.chromite.ext.altibase.internal;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.jkiss.code.NotNull;
import org.osgi.framework.BundleContext;

/**
 * Altibase bundle activator.
 *
 * 설계: plugin.xml의 {@link AltibasePreferencesInitializer}는 이 번들의 기본값 노드를 처음 읽을 때만 실행된다(Eclipse 환경설정 규칙).
 * Oracle·CUBRID 번들은 활성화될 때 자기 노드를 읽어 기본값(서버 출력 켬 등)이 들어가지만, Altibase 번들에는 그런 곳이 없어
 * 초기화가 한 번도 실행되지 않았다. 그래서 {@code altibase.dbms.output}이 기본값 켬이 아니라 꺼짐으로 읽혀 PRINT 출력이 나오지 않았다.
 * 활성화될 때(데이터 원본을 만들기 전) 노드를 읽어 초기화를 실행한다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: Altibase 환경설정 기본값이 적용되지 않던 것 (docs/chromite/swing-server-output.md)
 */
public class AltibaseActivator extends Plugin {

    @Override
    public void start(@NotNull BundleContext context) throws Exception {
        super.start(context);
        DefaultScope.INSTANCE.getNode(getBundle().getSymbolicName());
    }
}
