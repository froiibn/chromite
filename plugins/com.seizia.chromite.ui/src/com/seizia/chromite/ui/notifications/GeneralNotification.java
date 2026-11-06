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
package com.seizia.chromite.ui.notifications;

import org.eclipse.swt.graphics.Image;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.DBPMessageType;
import com.seizia.chromite.ui.SwtIcons;

import java.util.Date;

public class GeneralNotification extends AbstractUiNotification {

    private final String label;
    private final String description;
    private final DBPMessageType messageType;
    private final Runnable feedback;
    private final Date date;

    public GeneralNotification(
        @NotNull String id,
        @NotNull String title,
        @NotNull String description,
        @Nullable DBPMessageType messageType,
        @Nullable Runnable feedback)
    {
        super("com.seizia.chromite.notifications.event." + id);
        this.label = title;
        this.description = description;
        this.messageType = messageType;
        this.feedback = feedback;
        this.date = new Date();
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Date getDate() {
        return date;
    }


    @Override
    public <T> T getAdapter(@NotNull Class<T> adapter) {
        return null;
    }

    @Override
    public Image getNotificationImage() {
        return null;
    }

    @Override
    public Image getNotificationKindImage() {
        if (messageType == null) {
            return null;
        }
        switch (messageType) {
            case ERROR:
                return SwtIcons.getImage(DBIcon.STATUS_ERROR);
            case WARNING:
                return SwtIcons.getImage(DBIcon.STATUS_WARNING);
            default:
                return SwtIcons.getImage(DBIcon.STATUS_INFO);
        }
    }

    @Override
    public void open() {
        if (feedback != null) {
            feedback.run();
        }
    }

}