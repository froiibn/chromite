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
package com.seizia.chromite.ui.app.standalone.internal;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPMessageType;
import com.seizia.chromite.runtime.ChromiteNotifications;
import com.seizia.chromite.ui.notifications.NotificationUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.hooks.bundle.EventHook;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class CoreApplicationActivator extends AbstractUIPlugin {

    private static final Log log = Log.getLog(CoreApplicationActivator.class);

    // The shared instance
    private static CoreApplicationActivator plugin;

    public CoreApplicationActivator() {
    }

    @Override
    public void start(@NotNull BundleContext context) throws Exception {
        super.start(context);

        trackBundleActivation(context);
        configureNotifications();

        plugin = this;
    }

    private static void configureNotifications() {
        // Set notifications handler
        ChromiteNotifications.setHandler(new ChromiteNotifications.NotificationHandler() {
            @Override
            public void sendNotification(
                @NotNull DBPDataSource dataSource,
                @NotNull String id,
                @NotNull String text,
                @Nullable DBPMessageType messageType,
                @Nullable Runnable feedback
            ) {
                NotificationUtils.sendNotification(dataSource, id, text, messageType, feedback);
            }

            @Override
            public void sendNotification(
                @NotNull String id,
                @NotNull String title,
                @NotNull String text,
                @Nullable DBPMessageType messageType,
                @Nullable Runnable feedback
            ) {
                NotificationUtils.sendNotification(id, title, text, messageType, feedback);
            }
        });
    }

    private static void trackBundleActivation(@NotNull BundleContext context) {
        // Add bundle load logger
        if (!Log.isQuietMode()) {
            Set<String> activatedBundles = new HashSet<>();
            context.registerService(EventHook.class, (event, contexts) -> {
                String message = null;
                Bundle bundle = event.getBundle();
                if (event.getType() == BundleEvent.STARTED) {
                    if (bundle.getState() == Bundle.ACTIVE) {
                        message = "Start [" + bundle.getSymbolicName() + "]";
                        /* + " " + bundle.getVersion() + " " + OsgiUtils.getBundleName(bundle)*/
                        activatedBundles.add(bundle.getSymbolicName());
                    }
                } else if (event.getType() == BundleEvent.STOPPING) {
                    activatedBundles.remove(bundle.getSymbolicName());
                    //message = "< Stop " + getBundleName(bundle) +
                    // " [" + bundle.getSymbolicName() + " " + bundle.getVersion() + "]";
                }
                if (message != null) {
                    log.debug(message);
                }
            }, null);
        }
    }

    @Override
    public void stop(@NotNull BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    @NotNull
    public static CoreApplicationActivator getDefault() {
        return Objects.requireNonNull(plugin, "Core UI plugin was not started");
    }

}
