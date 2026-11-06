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
package com.seizia.chromite.ext.mysql;

import org.eclipse.osgi.util.NLS;

/**
 * 변경 이력:
 * - 2026-11-07 SWT 프런트엔드 삭제로 쓰는 곳이 없어진 문구 삭제(docs/chromite/swt-removal.md)
 */
public class MySQLMessages extends NLS {
    private static final String BUNDLE_NAME = "com.seizia.chromite.ext.mysql.MySQLMessages";

    public static String exception_direct_database_rename;

    static {
        NLS.initializeMessages(BUNDLE_NAME, MySQLMessages.class);
    }

    private MySQLMessages() {
    }

}
