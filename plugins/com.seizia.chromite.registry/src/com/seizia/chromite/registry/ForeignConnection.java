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
package com.seizia.chromite.registry;

import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One connection read from another tool's file, before it is mapped to a Chromite driver.
 *
 * 설계:
 * <ul>
 *     <li>다른 도구의 파일 형식마다 읽는 클래스({@link ForeignConnectionReader})가 이 객체만 만든다. 드라이버 고르기, 연결 설정 만들기,
 *     미리 보기·충돌·가져오기는 형식과 관계없이 {@link ForeignConnectionImport}와 {@link ConnectionTransfer}가 한 번에 한다.
 *     SWT {@code ImportConnectionInfo}(삭제된 {@code ui.config.migration})와 같은 역할이지만 UI 번들이 아니라 모델에 둔다.</li>
 *     <li>드라이버는 이름이 아니라 단서로 준다: 원본 도구의 종류 이름({@link #getType()}), 드라이버 클래스, JDBC URL.
 *     원본이 정확히 어떤 드라이버인지 알면(예: Oracle 전용 도구) {@link #getDriverId()}로 바로 정한다.</li>
 *     <li>비밀번호는 원본 파일에 그 도구가 읽을 수 있는 평문으로 있을 때만 채운다. 원본 도구의 암호화를 풀지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 */
public final class ForeignConnection {

    @NotNull
    private final String name;
    @Nullable
    private String folderPath;
    @Nullable
    private String type;
    @Nullable
    private String driverId;
    @Nullable
    private String driverClass;
    @Nullable
    private String url;
    @Nullable
    private String host;
    @Nullable
    private String port;
    @Nullable
    private String database;
    @Nullable
    private String user;
    @Nullable
    private String password;
    @NotNull
    private final Map<String, String> providerProperties = new LinkedHashMap<>();
    @NotNull
    private final List<DBWHandlerConfiguration> handlers = new ArrayList<>();

    public ForeignConnection(@NotNull String name) {
        this.name = name;
    }

    @NotNull
    public String getName() {
        return name;
    }

    /**
     * Group path ("a/b") in the source tool, or null at the top level.
     */
    @Nullable
    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(@Nullable String folderPath) {
        this.folderPath = folderPath;
    }

    /**
     * Database type as the source names it ("PostgreSQL", "mssql", a driver ID, …), or null.
     */
    @Nullable
    public String getType() {
        return type;
    }

    public void setType(@Nullable String type) {
        this.type = type;
    }

    /**
     * Chromite driver ("provider:driver" or a driver ID) when the source is known to use exactly that driver.
     */
    @Nullable
    public String getDriverId() {
        return driverId;
    }

    public void setDriverId(@Nullable String driverId) {
        this.driverId = driverId;
    }

    @Nullable
    public String getDriverClass() {
        return driverClass;
    }

    public void setDriverClass(@Nullable String driverClass) {
        this.driverClass = driverClass;
    }

    @Nullable
    public String getUrl() {
        return url;
    }

    public void setUrl(@Nullable String url) {
        this.url = url;
    }

    @Nullable
    public String getHost() {
        return host;
    }

    public void setHost(@Nullable String host) {
        this.host = host;
    }

    @Nullable
    public String getPort() {
        return port;
    }

    public void setPort(@Nullable String port) {
        this.port = port;
    }

    @Nullable
    public String getDatabase() {
        return database;
    }

    public void setDatabase(@Nullable String database) {
        this.database = database;
    }

    @Nullable
    public String getUser() {
        return user;
    }

    public void setUser(@Nullable String user) {
        this.user = user;
    }

    /**
     * Saved password, only when the source file holds it as plain text.
     */
    @Nullable
    public String getPassword() {
        return password;
    }

    public void setPassword(@Nullable String password) {
        this.password = password;
    }

    @NotNull
    public Map<String, String> getProviderProperties() {
        return providerProperties;
    }

    public void setProviderProperty(@NotNull String name, @Nullable String value) {
        if (value == null) {
            providerProperties.remove(name);
        } else {
            providerProperties.put(name, value);
        }
    }

    /**
     * Network handlers (SSH tunnel, …) of the connection.
     */
    @NotNull
    public List<DBWHandlerConfiguration> getHandlers() {
        return handlers;
    }
}
