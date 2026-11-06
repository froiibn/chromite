/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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

package com.seizia.chromite.model.admin.sessions;

import com.seizia.chromite.model.DBPImage;

/**
 * Server session additional details provider
 */
public abstract class AbstractServerSessionDetails implements DBAServerSessionDetails {

    private String detailsTitle;
    private String detailsTooltip;
    private DBPImage detailsIcon;

    public AbstractServerSessionDetails(String detailsTitle, String detailsTooltip, DBPImage detailsIcon) {
        this.detailsTitle = detailsTitle;
        this.detailsTooltip = detailsTooltip;
        this.detailsIcon = detailsIcon;
    }

    @Override
    public String getDetailsTitle() {
        return detailsTitle;
    }

    @Override
    public String getDetailsTooltip() {
        return detailsTooltip;
    }

    @Override
    public DBPImage getDetailsIcon() {
        return detailsIcon;
    }
}
