/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.ai.registry;

import org.eclipse.core.runtime.IConfigurationElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.DBRuntimeException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.ai.*;
import com.seizia.chromite.model.ai.agent.AIToolAccess;
import com.seizia.chromite.model.ai.agent.AIToolWrites;
import com.seizia.chromite.model.exec.DBCFeatureNotSupportedException;
import com.seizia.chromite.model.impl.AbstractDescriptor;
import com.seizia.chromite.registry.RegistryConstants;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * AI function declared in the {@value #EXTENSION_ID} extension point.
 *
 * 변경 이력:
 * - 2026-11-07 {@code access}, {@code writes} 속성 읽기(에이전트 도구 권한 정책, docs/chromite/ai-agent.md)
 * - 2026-11-12 {@code toolSet}, {@code shortDescription} 속성 읽기(채팅의 도구 묶음, docs/chromite/ai-agent.md "도구 묶음")
 */
public class AIFunctionInternalDescriptor extends AbstractDescriptor implements AIFunctionDescriptor {

    public static final String EXTENSION_ID = "com.seizia.chromite.ai.function";
    private static final Log log = Log.getLog(AIFunctionInternalDescriptor.class);

    private static final AIFunction VOID_STUB = (context, parameters) -> {
        throw new DBCFeatureNotSupportedException("Internal error. This function mustn't be called");
    };

    private final AIToolboxInternalDescriptor toolbox;
    private final ObjectType objectType;
    private final String id;
    private final String legacyId;
    private final String name;
    private final DBPImage icon;
    private final boolean system;
    private final boolean ui;
    private final boolean enabledByDefault;
    private final boolean omitConfirmation;
    private final AIFunctionAllowMode defaultAllowMode;
    private final AIFunctionPurpose purpose;
    private final AIFunctionType type;
    private final AIToolAccess access;
    private final AIToolWrites writes;
    private final String[] dependsOn;
    private final String aiDescription;
    private final String shortAiDescription;
    private final String toolSet;
    private final String userDescription;
    private final String categoryId;
    private final AIFunctionInternalParameter[] parameters;
    private final AIFunctionImplementationDescriptor implementation;
    private transient AIFunction instance;

    public AIFunctionInternalDescriptor(
        @NotNull AIToolboxInternalDescriptor toolbox,
        @NotNull IConfigurationElement config
    ) {
        this(toolbox, config, null);
    }

    AIFunctionInternalDescriptor(
        @NotNull AIToolboxInternalDescriptor toolbox,
        @NotNull IConfigurationElement config,
        @Nullable AIFunctionImplementationDescriptor implementation
    ) {
        super(config);
        this.toolbox = toolbox;
        this.objectType = new ObjectType(config, RegistryConstants.ATTR_CLASS);
        this.icon = iconToImage(config.getAttribute(RegistryConstants.ATTR_ICON));
        this.id = config.getAttribute(RegistryConstants.ATTR_ID);
        this.legacyId = config.getAttribute("legacyId");
        this.name = config.getAttribute(RegistryConstants.ATTR_NAME);
        this.ui = CommonUtils.toBoolean(config.getAttribute("ui"));
        this.system = CommonUtils.toBoolean(config.getAttribute("system"));
        this.enabledByDefault = CommonUtils.toBoolean(config.getAttribute("enabledByDefault"));
        this.defaultAllowMode = CommonUtils.valueOf(
            AIFunctionAllowMode.class,
            config.getAttribute("defaultAllowMode"),
            AIFunctionAllowMode.ALWAYS_ALLOW
        );
        this.omitConfirmation = CommonUtils.toBoolean(config.getAttribute("omitConfirmation"), false);
        this.purpose = CommonUtils.valueOf(AIFunctionPurpose.class, config.getAttribute("purpose"), AIFunctionPurpose.TOOL);
        this.categoryId = config.getAttribute("categoryId");
        this.aiDescription = config.getAttribute(RegistryConstants.ATTR_DESCRIPTION);
        this.shortAiDescription = config.getAttribute("shortDescription");
        this.toolSet = config.getAttribute("toolSet");
        this.userDescription = config.getAttribute("userDescription");
        this.dependsOn = CommonUtils.splitString(config.getAttribute("dependsOn"), ',').toArray(new String[0]);
        this.type = CommonUtils.valueOf(
            AIFunctionType.class,
            config.getAttribute("type"),
            AIFunctionType.INFORMATION
        );
        this.access = CommonUtils.valueOf(
            AIToolAccess.class,
            config.getAttribute("access"),
            type == AIFunctionType.ACTION ? AIToolAccess.WRITE : AIToolAccess.READ
        );
        this.writes = AIToolWrites.fromId(config.getAttribute("writes"));

        List<AIFunctionInternalParameter> params = new ArrayList<>();
        for (IConfigurationElement pe : config.getChildren("parameter")) {
            params.add(new AIFunctionInternalParameter(pe, implementation));
        }
        this.parameters = params.toArray(new AIFunctionInternalParameter[0]);
        this.implementation = implementation;
        if (implementation != null) {
            for (String parameterName : implementation.getTransformedParameters()) {
                if (getParameter(parameterName) == null) {
                    log.error("AI function implementation references an unknown parameter '" + parameterName +
                        "' in function '" + id + "'");
                }
            }
        }
    }

    @NotNull
    @Override
    public AIToolbox getToolbox() {
        return toolbox;
    }

    @NotNull
    public String getId() {
        return id;
    }

    @Nullable
    @Override
    public String getLegacyId() {
        return legacyId;
    }

    @NotNull
    public String getName() {
        return name;
    }

    @Nullable
    public DBPImage getIcon() {
        return icon;
    }

    @NotNull
    public AIFunctionType getType() {
        return type;
    }

    @NotNull
    public AIFunctionPurpose getPurpose() {
        return purpose;
    }

    @NotNull
    @Override
    public AIToolAccess getAccess() {
        return access;
    }

    @NotNull
    @Override
    public AIToolWrites getWrites() {
        return writes;
    }

    @Nullable
    public String getCategoryId() {
        return categoryId;
    }

    @Nullable
    public String getAiDescription() {
        return aiDescription;
    }

    @Nullable
    @Override
    public String getShortAiDescription() {
        return shortAiDescription;
    }

    @Nullable
    @Override
    public String getToolSet() {
        return toolSet;
    }

    @Nullable
    public String getUserDescription() {
        return userDescription;
    }

    @Override
    public boolean isUI() {
        return ui;
    }

    @Override
    public boolean isSystem() {
        return system;
    }

    @Override
    public boolean isEnabledByDefault() {
        return enabledByDefault;
    }

    @Override
    public boolean isOmitConfirmation() {
        return omitConfirmation;
    }

    @NotNull
    @Override
    public AIFunctionAllowMode getDefaultAllowMode() {
        return defaultAllowMode;
    }

    @NotNull
    public AIFunctionParameter[] getParameters() {
        return parameters;
    }

    @Nullable
    @Override
    public AIFunctionParameter getParameter(@NotNull String name) {
        for (AIFunctionParameter p : parameters) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    @NotNull
    public String[] getDependsOn() {
        return dependsOn;
    }


    @NotNull
    public AIFunction getInstance() {
        if (instance == null) {
            if (implementation != null && implementation.hasClass()) {
                try {
                    instance = implementation.createInstance();
                } catch (Exception e) {
                    throw new DBRuntimeException("Error creating AI function " + getId(), e);
                }
            } else if (CommonUtils.isEmpty(objectType.getImplName())) {
                instance = VOID_STUB;
            } else {
                try {
                    instance = objectType.createInstance(AIFunction.class);
                } catch (Exception e) {
                    throw new DBRuntimeException("Error creating AI function " + getId(), e);
                }
            }
        }
        return instance;
    }

    boolean hasImplementation() {
        return (implementation != null && (implementation.hasClass() ||
            implementation.isHeadless() && (type == AIFunctionType.ACTION || ui))) ||
            CommonUtils.isNotEmpty(objectType.getImplName());
    }

    public boolean isApplicable(@NotNull AIEngineDescriptor engine, @NotNull AIPromptGenerator prompt) {
        return true;
    }

    @Override
    public String toString() {
        return "AI function: " + getId();
    }

}
