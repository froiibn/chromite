/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.junit;

import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.junit.osgi.annotation.RunWithApplication;
import com.seizia.chromite.junit.osgi.behaviors.IAsyncApplication;
import com.seizia.chromite.junit.osgi.extension.OSGITestExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockitoAnnotations;


@ExtendWith(OSGITestExtension.class)
@RunWithApplication(bundleName = "com.seizia.chromite.headless", registryName = "com.seizia.chromite.headless.application")
public abstract class ApplicationUnitTest implements IAsyncApplication {

    @BeforeEach
    public void setupMocks() {
        MockitoAnnotations.openMocks(this);
    }

    @Override
    public boolean verifyLaunched() {
        return DBWorkbench.isPlatformStarted();
    }

}
