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
package com.seizia.chromite.model.update;

import org.jkiss.code.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an operating system tool for the update checks and reads what it prints.
 *
 * 설계: 확인 도구(PowerShell, codesign, spctl, ditto)는 항상 절대 경로로 부르고, 파일 경로는 명령줄 글이 아니라
 * 인자나 환경 변수로 넘긴다. 출력은 별도 스레드가 끝까지 읽어(파이프가 차서 멈추지 않게) 정해진 크기까지만 모은다.
 * 시간 제한을 넘기면 프로세스를 끝내고 실패(-1)로 본다.
 * {@code PSModulePath}는 자식에게 넘기지 않는다: PowerShell 7에서 띄운 Chromite가 그 값을 물려주면 Windows PowerShell 5.1이
 * PowerShell 7용 {@code Microsoft.PowerShell.Security} 모듈을 불러오다 실패한다(이 PC에서 재현, "module could not be loaded").
 * 값이 없으면 5.1은 자기 기본 모듈 경로를 쓴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
final class UpdateProcess {

    private static final int MAX_OUTPUT = 16 * 1024 * 1024;

    /**
     * @param code the exit code, -1 when the tool did not run or did not end in time
     */
    record Result(int code, @NotNull String output) {
    }

    private UpdateProcess() {
    }

    /**
     * Runs {@code command}; its standard error is read together with its output.
     *
     * @param env variables added to this process's environment
     */
    @NotNull
    static Result run(@NotNull List<String> command, @NotNull Map<String, String> env, @NotNull Duration timeout)
        throws InterruptedException {
        ProcessBuilder builder = builder(command, env);
        builder.redirectErrorStream(true);
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Result(-1, ""); //$NON-NLS-1$
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (InputStream in = process.getInputStream()) {
                for (int n; (n = in.read(buffer)) > 0; ) {
                    synchronized (output) {
                        if (output.size() < MAX_OUTPUT) {
                            output.write(buffer, 0, Math.min(n, MAX_OUTPUT - output.size()));
                        }
                    }
                }
            } catch (IOException ignored) {
                // (the process ended or was ended)
            }
        }, "Chromite update check output"); //$NON-NLS-1$
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, ""); //$NON-NLS-1$
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        reader.join(TimeUnit.SECONDS.toMillis(5));
        synchronized (output) {
            return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
        }
    }

    /**
     * A process with this process's environment plus {@code env}, without {@code PSModulePath}.
     */
    @NotNull
    static ProcessBuilder builder(@NotNull List<String> command, @NotNull Map<String, String> env) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().keySet().removeIf("PSModulePath"::equalsIgnoreCase); //$NON-NLS-1$
        builder.environment().putAll(env);
        return builder;
    }

    /**
     * {@code -EncodedCommand} text of a PowerShell script: no quoting of the script on the command line.
     */
    @NotNull
    static String encodePowerShell(@NotNull String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    @NotNull
    static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }
}
