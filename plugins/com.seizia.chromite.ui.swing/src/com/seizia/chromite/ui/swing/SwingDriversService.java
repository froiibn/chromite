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

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.connection.DBPDriver;
import org.jkiss.dbeaver.model.connection.DBPDriverDependencies;
import org.jkiss.dbeaver.model.connection.DBPDriverLibrary;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.registry.driver.DriverDescriptor;
import org.jkiss.dbeaver.registry.driver.DriverUtils;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.UIServiceDrivers;

import javax.swing.*;
import java.awt.KeyboardFocusManager;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 드라이버 파일이 없을 때 내려받을지 묻고, 진행 대화상자에서 내려받는다({@link UIServiceDrivers}).
 *
 * 설계: 드라이버를 처음 쓸 때(연결, 연결 시험, 드라이버 속성 읽기) {@code DriverLoaderDescriptor.downloadDriverLibraries}가
 * 이 서비스를 부른다. SWT 제품은 {@code org.jkiss.dbeaver.core}의 {@code UIServiceDriversImpl}({@code DriverDownloadDialog})이
 * 이 일을 하는데 Swing 제품에는 그 번들이 없어서, 서비스가 없으면 모델이 묻지 않고 바로 내려받았다.
 * 그래서 같은 서비스를 {@code plugin.xml}의 {@code org.jkiss.dbeaver.service} 확장으로 등록한다.
 * <ul>
 *     <li>확인: 없는 라이브러리 이름을 보여 주고 Download / Cancel을 묻는다. Cancel이면 {@code false}를 돌려주고,
 *     모델은 드라이버 클래스를 찾지 못했다는 오류를 낸다(SWT에서 다운로드를 취소했을 때와 같음).</li>
 *     <li>내려받기는 모델의 {@link DriverUtils#downloadDriverFiles}(SWT 대화상자도 결국 같은 라이브러리 다운로드)를
 *     모달 진행 대화상자({@link ProgressDialog}, Cancel 가능)에서 실행한다. 호출 스레드(대개 연결 Job)는 끝날 때까지 기다린다.</li>
 *     <li>오프라인 모드({@code ui.drivers.offline}, docs/chromite/drivers.md)에서는 모델이 이 서비스를 부르지 않고
 *     로컬 파일만 찾은 뒤, 없으면 이유를 설명하는 오류를 낸다. 그 오류는 호출한 화면이 그대로 보여 준다.</li>
 * </ul>
 * SWT 대화상자에 있던 라이브러리 버전 선택, 의존성 트리 보기는 없다.
 *
 * 변경 이력:
 * - 2026-11-06 D단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 */
public class SwingDriversService implements UIServiceDrivers {

    private static final Log log = Log.getLog(SwingDriversService.class);

    @Override
    public boolean downloadDriverFiles(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDriver driver,
        @NotNull DBPDriverDependencies dependencies,
        boolean isShowExpanded
    ) {
        if (!(driver instanceof DriverDescriptor driverDescriptor)) {
            return false;
        }
        AtomicBoolean result = new AtomicBoolean();
        Runnable task = () -> {
            if (!confirmDownload(driverDescriptor)) {
                return;
            }
            try {
                Boolean downloaded = ProgressDialog.runTask(
                    "Download driver files",
                    downloadMonitor -> DriverUtils.downloadDriverFiles(downloadMonitor, driverDescriptor, dependencies)
                        && !downloadMonitor.isCanceled());
                result.set(Boolean.TRUE.equals(downloaded));
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError("Download driver files",
                    "Can't download the files of driver '" + driverDescriptor.getFullName() + "'", e);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (InvocationTargetException e) {
                log.error("Error downloading driver files", e.getTargetException());
                return false;
            }
        }
        return result.get();
    }

    private static boolean confirmDownload(@NotNull DriverDescriptor driver) {
        StringBuilder message = new StringBuilder()
            .append("Driver files for '").append(driver.getFullName()).append("' are missing.\n");
        List<String> missing = getMissingLibraries(driver);
        if (!missing.isEmpty()) {
            message.append('\n');
            for (String library : missing) {
                message.append("  ").append(library).append('\n');
            }
        }
        message.append("\nDownload them now?");
        Object[] options = {"Download", "Cancel"};
        // Parent: the focused window, so that the question appears over the dialog that started the download
        int choice = JOptionPane.showOptionDialog(
            KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),
            message.toString(),
            "Download driver files",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]);
        return choice == 0;
    }

    /**
     * Libraries the driver needs on this platform whose local file is not there (the same libraries the model
     * resolves and downloads; their transitive dependencies are only known after resolution).
     */
    @NotNull
    private static List<String> getMissingLibraries(@NotNull DriverDescriptor driver) {
        List<String> missing = new ArrayList<>();
        for (DBPDriverLibrary library : driver.getDriverLibraries()) {
            if (library.isDisabled() || library.isOptional() || !library.matchesCurrentPlatform()
                || library.getType() == DBPDriverLibrary.FileType.license) {
                continue;
            }
            Path localFile = library.getLocalFile();
            if (localFile == null || !Files.exists(localFile)) {
                missing.add(library.getDisplayName());
            }
        }
        return missing;
    }
}
