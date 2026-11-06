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

import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.access.DBAAuthModel;
import org.jkiss.dbeaver.model.connection.DBPAuthModelDescriptor;
import org.jkiss.dbeaver.model.connection.DBPConnectionConfiguration;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNative;
import org.jkiss.dbeaver.model.impl.auth.AuthModelDatabaseNativeCredentials;
import org.jkiss.dbeaver.model.preferences.DBPPropertyDescriptor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.properties.PropertyCollector;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Connect 대화상자의 Authentication 콤보에 넣을 인증 방식과, 인증 방식마다 보일 입력 칸(Login, Password).
 *
 * 설계: 인증 방식 목록은 모델 계층의 {@code DBPDataSourceProviderRegistry#getApplicableAuthModels}(드라이버에 맞는 것, 데스크톱에서는
 * 서버 앱 전용 제외, 다른 방식이 대체한 것 제외)에서 시작한다. SWT는 인증 방식마다 SWT 설정 화면({@code propertyConfigurator}의
 * {@code uiClass})을 쓰는데 Swing 제품에는 그 번들이 없다. 그래서 범용 칸(Login, Password)만으로 설정이 끝나는 방식만 고를 수 있게 한다.
 * 판단은 모델 코드만으로 한다.
 * <ul>
 *     <li>구현이 {@link AuthModelDatabaseNative}다: 사용자·비밀번호를 연결 설정의 {@code userName}·{@code userPassword}에 두고,
 *     {@code isUserNameApplicable}/{@code isUserPasswordApplicable}로 칸이 필요한지 알린다
 *     (SWT 범용 설정 화면 {@code DatabaseNativeAuthModelConfigurator}가 이 두 값만 보고 칸을 만든다).</li>
 *     <li>자격 증명 객체에서 보이는 속성({@code createCredentialsSource}, 숨김 속성 제외)이 {@code userName}·{@code userPassword}뿐이다
 *     (예: 셸 명령 방식은 명령·작업 폴더가 있어 뺀다).</li>
 *     <li>보일 칸이 하나 이상이거나, 자격 증명이 필요 없는 방식이다({@code isCredentialsPromptRequired() == false}, 예: Windows 인증, OS 인증).
 *     자격 증명이 필요한데 두 속성을 모두 숨긴 방식(예: ClickHouse SSO·OIDC)은 브라우저 로그인이나 별도 설정이 필요하므로 뺀다.</li>
 *     <li>외부 인증 공급자가 필요 없다({@code getRequiredAuthProviderId}).</li>
 * </ul>
 * 연결이 이미 쓰는 방식은 위 조건과 관계없이 목록에 넣는다(편집할 때 다른 화면에서 고른 방식을 지우지 않기 위해서다).
 * 순서는 SWT {@code AuthModelSelector}와 같다(기본 방식 먼저, 그다음 이름순).
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성: SSMS식 Connect 대화상자 (docs/chromite/swing-frontend.md "Connect 대화상자 (SSMS식)")
 * - 2026-11-06 화면 문구를 ConnectionMessages로 옮김(OS 언어를 따름)
 */
final class ConnectionAuthModels {

    private static final Log log = Log.getLog(ConnectionAuthModels.class);

    /**
     * Display names of the base credentials' properties: these are replaced by the SSMS labels.
     */
    private static final Set<String> BASE_LABELS = collectBaseLabels();

    /**
     * Login and password fields of an auth model; {@code null} label: the field is not shown.
     *
     * @param managed the model keeps its credentials in the connection's user name and password
     *                ({@link AuthModelDatabaseNative}); otherwise the form leaves them as they are
     */
    record Fields(@Nullable String userLabel, @Nullable String passwordLabel, boolean managed) {
        static final Fields NONE = new Fields(null, null, false);

        boolean isEmpty() {
            return userLabel == null && passwordLabel == null;
        }
    }

    private ConnectionAuthModels() {
    }

    /**
     * The auth model the connection uses, as the SWT connection page detects it (ConnectionPageWithAuth.loadSettings):
     * the configured model (or the driver's legacy settings), replaced by a newer model when one replaces it.
     */
    @NotNull
    static DBPAuthModelDescriptor detect(@NotNull DBPDriver driver, @NotNull DBPConnectionConfiguration configuration) {
        DBPAuthModelDescriptor model = driver.getDataSourceProvider().detectConnectionAuthModel(driver, configuration);
        DBPAuthModelDescriptor replacement = model.getReplacedBy(driver);
        return replacement != null ? replacement : model;
    }

    /**
     * The models the user can choose for the driver (see the class comment), plus the current one.
     */
    @NotNull
    static List<DBPAuthModelDescriptor> getChoices(@NotNull DBPDriver driver, @NotNull DBPAuthModelDescriptor current) {
        List<DBPAuthModelDescriptor> models = new ArrayList<>();
        for (DBPAuthModelDescriptor model : DBWorkbench.getPlatform().getDataSourceProviderRegistry().getApplicableAuthModels(driver)) {
            if (model == current || isGeneric(model)) {
                models.add(model);
            }
        }
        if (!models.contains(current)) {
            models.add(current);
        }
        models.sort(Comparator
            .comparing((DBPAuthModelDescriptor model) -> !model.isDefaultModel())
            .thenComparing(DBPAuthModelDescriptor::getName));
        if (log.isDebugEnabled()) {
            log.debug("Auth models for " + driver.getFullId() + ": " + models);
        }
        return models;
    }

    /**
     * The login and password fields of a model; a model that is not database native shows no fields
     * (its settings are kept as they are).
     */
    @NotNull
    static Fields getFields(@NotNull DBPAuthModelDescriptor model) {
        DBAAuthModel<?> instance;
        try {
            instance = model.getInstance();
        } catch (IllegalStateException e) {
            log.debug("Auth model " + model.getId() + " is not available", e);
            return Fields.NONE;
        }
        if (!(instance instanceof AuthModelDatabaseNative<?> nativeModel)) {
            return Fields.NONE;
        }
        DBPPropertyDescriptor[] visible = model.createCredentialsSource(null, null).getProperties();
        String userLabel = nativeModel.isUserNameApplicable()
            ? getLabel(visible, AuthModelDatabaseNativeCredentials.PROP_USER_NAME, ConnectionMessages.settings_login) : null;
        String passwordLabel = nativeModel.isUserPasswordApplicable()
            ? getLabel(visible, AuthModelDatabaseNativeCredentials.PROP_USER_PASSWORD, ConnectionMessages.settings_password) : null;
        return new Fields(userLabel, passwordLabel, true);
    }

    private static boolean isGeneric(@NotNull DBPAuthModelDescriptor model) {
        if (!CommonUtils.isEmpty(model.getRequiredAuthProviderId())) {
            return false;
        }
        DBAAuthModel<?> instance;
        DBPPropertyDescriptor[] visible;
        try {
            instance = model.getInstance();
            visible = model.createCredentialsSource(null, null).getProperties();
        } catch (RuntimeException e) {
            log.debug("Auth model " + model.getId() + " is not available", e);
            return false;
        }
        if (!(instance instanceof AuthModelDatabaseNative<?>)) {
            return false;
        }
        Set<String> generic = Set.of(AuthModelDatabaseNativeCredentials.PROP_USER_NAME, AuthModelDatabaseNativeCredentials.PROP_USER_PASSWORD);
        for (DBPPropertyDescriptor property : visible) {
            if (!generic.contains(property.getId())) {
                return false;
            }
        }
        return !getFields(model).isEmpty() || !instance.isCredentialsPromptRequired();
    }

    /**
     * The credentials' own name for a field when it renames it (e.g. "Token"), else the SSMS label.
     * {@code null} when the credentials hide the property.
     */
    @Nullable
    private static String getLabel(@NotNull DBPPropertyDescriptor[] visible, @NotNull String id, @NotNull String defaultLabel) {
        for (DBPPropertyDescriptor property : visible) {
            if (property.getId().equals(id)) {
                String name = property.getDisplayName();
                return BASE_LABELS.contains(name) || CommonUtils.isEmpty(name) ? defaultLabel : name;
            }
        }
        return null;
    }

    @NotNull
    private static Set<String> collectBaseLabels() {
        PropertyCollector collector = new PropertyCollector(new AuthModelDatabaseNativeCredentials(), false);
        collector.collectProperties();
        Set<String> labels = new HashSet<>();
        for (DBPPropertyDescriptor property : collector.getProperties()) {
            labels.add(property.getDisplayName());
        }
        return labels;
    }
}
