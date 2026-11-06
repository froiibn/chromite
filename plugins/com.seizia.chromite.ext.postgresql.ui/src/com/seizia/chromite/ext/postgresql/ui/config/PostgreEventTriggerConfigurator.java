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

package com.seizia.chromite.ext.postgresql.ui.config;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.postgresql.PostgreMessages;
import com.seizia.chromite.ext.postgresql.model.PostgreEventTrigger;
import com.seizia.chromite.ext.postgresql.model.PostgreTriggerBase;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectConfigurator;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.ui.UITask;
import com.seizia.chromite.ui.UIUtils;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

/**
 * Postgre sequence configurator
 */
public class PostgreEventTriggerConfigurator implements DBEObjectConfigurator<PostgreEventTrigger> {

    @NotNull
    @Override
    public PostgreEventTrigger configureObject(@NotNull DBRProgressMonitor monitor, @Nullable DBECommandContext commandContext, @Nullable Object parent, @NotNull PostgreEventTrigger trigger, @NotNull Map<String, Object> options) {
        return new UITask<PostgreEventTrigger>() {
            @Override
            protected PostgreEventTrigger runTask() {
                TriggerEventEditPage editPage = new TriggerEventEditPage(trigger);
                if (!editPage.edit()) {
                    return null;
                }
                trigger.setName(editPage.getEntityName());
                trigger.setEventType(editPage.eventType);
                trigger.setFunction(editPage.selectedFunction);
                return trigger;
            }
        }.execute();
    }

    static class TriggerEventEditPage extends PostgreTriggerEditPage {

        private Combo eventCombo;
        PostgreEventTrigger.TriggerEventTypes eventType;

        TriggerEventEditPage(PostgreTriggerBase trigger) {
            super(trigger);
        }

        @Override
        public void addExtraCombo(Composite parent) {
            eventCombo = UIUtils.createLabelCombo(parent, PostgreMessages.dialog_trigger_label_combo_event_type, PostgreMessages.dialog_trigger_label_combo_event_type_tip, SWT.DROP_DOWN | SWT.READ_ONLY);
            for (PostgreEventTrigger.TriggerEventTypes type : PostgreEventTrigger.TriggerEventTypes.values()) {
                eventCombo.add(type.name());
            }
            PostgreEventTrigger.TriggerEventTypes defaultEvent = PostgreEventTrigger.TriggerEventTypes.values()[0];
            eventCombo.setText(defaultEvent.name());
            eventType = defaultEvent;
            eventCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e ->
                eventType = CommonUtils.valueOf(PostgreEventTrigger.TriggerEventTypes.class, eventCombo.getText())));
        }

        @Override
        public String getTitle() {
            return PostgreMessages.dialog_trigger_label_title;
        }
    }
}
