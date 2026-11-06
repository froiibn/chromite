/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ui.actions;

/**
 * Connection commands
 */
public interface ConnectionCommands {

    String CMD_CONNECT = "com.seizia.chromite.core.connect";
    String CMD_DISCONNECT = "com.seizia.chromite.core.disconnect";
    String CMD_DISCONNECT_ALL = "com.seizia.chromite.core.disconnectAll";
    String CMD_DISCONNECT_OTHER = "com.seizia.chromite.core.disconnectOther";
    String CMD_INVALIDATE = "com.seizia.chromite.core.invalidate";
    String CMD_READONLY = "com.seizia.chromite.core.connection.readonly";
    String CMD_COMMIT = "com.seizia.chromite.core.commit";
    String CMD_ROLLBACK = "com.seizia.chromite.core.rollback";
    String CMD_TOGGLE_AUTOCOMMIT = "com.seizia.chromite.core.txn.autocommit"; //$NON-NLS-1$
    String CMD_SELECT_CONNECTION = "com.seizia.chromite.ui.tools.select.connection";
    String CMD_SELECT_SCHEMA = "com.seizia.chromite.ui.tools.select.schema";

}
