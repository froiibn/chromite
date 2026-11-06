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

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.DBNProjectDatabases;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.AlphanumericComparator;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Object Explorer에 연 서버 목록(SSMS: Object Explorer에는 사용자가 연결한 서버만 있다).
 *
 * 설계:
 * <ul>
 *     <li>서버 자체는 DBeaver 연결 레지스트리({@link DBPDataSourceRegistry})에 그대로 있고, 여기에는 Object Explorer에 보일
 *     연결 ID만 둔다. 등록된 서버 전체와 그룹은 Registered Servers 창({@link RegisteredServersPanel})이 보여 준다.</li>
 *     <li>순서는 SSMS처럼 연 순서다(새로 연 서버가 맨 아래). 처음 채울 때만 이름의 자연 정렬 순서로 넣는다.</li>
 *     <li>저장은 이 번들의 설정 저장소({@link ChromiteSwingActivator#getPreferences()})의 {@link #PREF_SERVERS}에 줄바꿈으로 나눈
 *     연결 ID 목록이다. 연결 설정 파일({@code data-sources.json})에 Chromite 전용 속성을 넣지 않아 DBeaver 형식을 바꾸지 않는다.
 *     바꿀 때마다 바로 저장한다.</li>
 *     <li>업그레이드 뒤 첫 실행: 값이 없으면(설정 저장소의 기본값 {@link #ALL_SERVERS}) 그전처럼 모든 연결을 보여 주고 그 목록을 저장한다.
 *     빈 목록("")은 기본값과 달라 그대로 저장되므로({@code BundlePreferenceStore}는 기본값과 같은 값을 지운다) "아무것도 열지 않음"과
 *     "아직 정하지 않음"이 구분된다.</li>
 *     <li>읽기는 Object Explorer의 백그라운드 Job에서도 하므로 메서드를 동기화한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Registered Servers: 최초 작성 (docs/chromite/swing-registered-servers.md)
 */
final class ObjectExplorerServers {

    private static final String PREF_SERVERS = "objectExplorer.servers"; //$NON-NLS-1$
    /**
     * Default value: nothing saved yet (first run after the upgrade), show every connection.
     */
    private static final String ALL_SERVERS = "*"; //$NON-NLS-1$
    private static final String SEPARATOR = "\n"; //$NON-NLS-1$

    private final DBPDataSourceRegistry registry;
    private final Set<String> ids = new LinkedHashSet<>();

    ObjectExplorerServers(@NotNull DBPDataSourceRegistry registry) {
        this.registry = registry;
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        preferences.setDefault(PREF_SERVERS, ALL_SERVERS);
        String value = preferences.getString(PREF_SERVERS);
        if (ALL_SERVERS.equals(value)) {
            // First run after the upgrade: the servers that were visible before (all), so nothing disappears
            List<DBPDataSourceContainer> all = new ArrayList<>(registry.getDataSources());
            all.sort(Comparator.comparing(DBPDataSourceContainer::getName, AlphanumericComparator.getInstance()::compareIgnoreCase));
            for (DBPDataSourceContainer container : all) {
                ids.add(container.getId());
            }
            save();
        } else if (!CommonUtils.isEmpty(value)) {
            for (String id : value.split(SEPARATOR)) {
                if (!id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
    }

    synchronized boolean contains(@NotNull DBPDataSourceContainer container) {
        return ids.contains(container.getId());
    }

    /**
     * Adds a server at the end (SSMS); true if it was not there.
     */
    synchronized boolean add(@NotNull DBPDataSourceContainer container) {
        if (!ids.add(container.getId())) {
            return false;
        }
        save();
        return true;
    }

    /**
     * Removes a server; true if it was there.
     */
    synchronized boolean remove(@NotNull DBPDataSourceContainer container) {
        if (!ids.remove(container.getId())) {
            return false;
        }
        save();
        return true;
    }

    /**
     * The Object Explorer root: the navigator nodes of the opened servers in their order. Hidden connections are left out,
     * as the navigator does ({@code DBNProjectDatabases#getChildren}).
     */
    @NotNull
    synchronized DBNNode[] getNodes(@NotNull DBNProjectDatabases databases) {
        List<DBNNode> nodes = new ArrayList<>();
        for (String id : ids) {
            DBNDataSource node = databases.getDataSource(id);
            if (node != null && !node.getDataSourceContainer().isHidden()) {
                nodes.add(node);
            }
        }
        return nodes.toArray(new DBNNode[0]);
    }

    /**
     * Saves the list; ids of connections deleted from the project are dropped.
     */
    private void save() {
        ids.removeIf(id -> registry.getDataSource(id) == null);
        DBPPreferenceStore preferences = ChromiteSwingActivator.getInstance().getPreferences();
        preferences.setValue(PREF_SERVERS, String.join(SEPARATOR, ids));
        PrefUtils.savePreferenceStore(preferences);
    }
}
