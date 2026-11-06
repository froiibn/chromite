/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ui.navigator;

/**
 * NavigatorCommands
 */
public class NavigatorCommands {

    public static final String GROUP_TOOLS = "tools";
    public static final String GROUP_TOOLS_END = "tools_end";
    public static final String GROUP_NAVIGATOR_ADDITIONS = "navigator_additions";
    public static final String GROUP_NAVIGATOR_ADDITIONS_END = "navigator_additions_end";

    public static final String CMD_OBJECT_OPEN = "com.seizia.chromite.core.object.open"; //$NON-NLS-1$
    public static final String CMD_OBJECT_CREATE = "com.seizia.chromite.core.object.create"; //$NON-NLS-1$
    public static final String CMD_OBJECT_DELETE = "com.seizia.chromite.core.object.delete"; //$NON-NLS-1$
    public static final String CMD_OBJECT_MOVE_TOP = "com.seizia.chromite.core.object.move.top"; //$NON-NLS-1$
    public static final String CMD_OBJECT_MOVE_BOTTOM = "com.seizia.chromite.core.object.move.bottom"; //$NON-NLS-1$
    public static final String CMD_OBJECT_MOVE_UP = "com.seizia.chromite.core.object.move.up"; //$NON-NLS-1$
    public static final String CMD_OBJECT_MOVE_DOWN = "com.seizia.chromite.core.object.move.down"; //$NON-NLS-1$
    public static final String CMD_OBJECT_SET_DEFAULT = "com.seizia.chromite.core.navigator.set.default";
    public static final String CMD_CREATE_LOCAL_FOLDER = "com.seizia.chromite.core.new.folder";
    public static final String CMD_CREATE_RESOURCE_FILE = "com.seizia.chromite.core.resource.create.file";
    public static final String CMD_CREATE_RESOURCE_FOLDER = "com.seizia.chromite.core.resource.create.folder";
    public static final String CMD_CREATE_FILE_LINK = "com.seizia.chromite.core.resource.link.file";
    public static final String CMD_CREATE_FOLDER_LINK = "com.seizia.chromite.core.resource.link.folder";
    public static final String CMD_CREATE_PROJECT = "com.seizia.chromite.core.project.create";

    public static final String CMD_FILTER_CONNECTED = "com.seizia.chromite.navigator.filter.connected";
    public static final String CMD_FILTER_OBJECT_TYPE = "com.seizia.chromite.navigator.filter.object.type";

    public static final String PARAM_OBJECT_TYPE = "com.seizia.chromite.core.object.type";
    public static final String PARAM_OBJECT_TYPE_NAME = "com.seizia.chromite.core.object.typeName";
    public static final String PARAM_OBJECT_TYPE_ICON = "com.seizia.chromite.core.object.typeIcon";
    public static final String PARAM_OBJECT_TYPE_FOLDER = "com.seizia.chromite.core.object.folder";

}
