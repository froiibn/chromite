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
import org.jkiss.code.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * The two requests of the updater: the list, and a release file. Both go to the one update site only.
 *
 * 설계: Turbrix 데스크톱의 규칙을 따른다.
 * <ul>
 *     <li>보내는 것은 요청 자체뿐이다: GET, 고정된 User-Agent {@value #USER_AGENT}(버전·OS를 넣지 않는다), 쿼리·본문·쿠키 없음.
 *     {@link HttpClient}는 쿠키 처리기를 정하지 않으면 쿠키를 보내지 않는다. 프록시는 기본 {@code ProxySelector}를 따른다.</li>
 *     <li>되돌려 보내기(3xx)는 따라가지 않는다. 200만 받는다.</li>
 *     <li>목록: 연결부터 본문 끝까지 {@link #FEED_TIMEOUT} 안에, {@link UpdateFeed#MAX_FEED_BYTES}까지. 넘으면 읽지 않는다.
 *     UTF-8이 아니면 버린다. 실패는 null(아무에게도 알리지 않는다, 오프라인·폐쇄망).</li>
 *     <li>파일: {@code <file>.part}로 받고 목록의 크기를 넘으면 그 자리에서 끊는다. {@link #STALL_TIMEOUT} 동안 한 바이트도
 *     오지 않으면 끊는다. 다 받으면 크기가 같을 때만 이름을 바꾼다.</li>
 * </ul>
 * 본문 읽기에 시간 제한이 없는 {@link HttpClient} 대신, 감시 타이머가 시간을 넘긴 스트림을 닫는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public class UpdateHttp {

    /**
     * Fixed: the site learns nothing of the version or the system (the download site's privacy note says so).
     */
    public static final String USER_AGENT = "Chromite"; //$NON-NLS-1$
    public static final Duration FEED_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration STALL_TIMEOUT = Duration.ofSeconds(60);
    private static final String PART = ".part"; //$NON-NLS-1$

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Chromite update watchdog"); //$NON-NLS-1$
        t.setDaemon(true);
        return t;
    });

    private final HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(FEED_TIMEOUT)
        .version(HttpClient.Version.HTTP_1_1)
        .build();

    /**
     * The list's text, or null on any failure (offline, time limit, not 200, too large, not UTF-8).
     */
    @Nullable
    public String fetchFeed(@NotNull URI origin) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(UpdateFeed.originText(origin) + UpdateFeed.FEED_PATH))
            .GET()
            .header("User-Agent", USER_AGENT) //$NON-NLS-1$
            .header("Accept", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
            .timeout(FEED_TIMEOUT)
            .build();
        long deadline = System.nanoTime() + FEED_TIMEOUT.toNanos();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    return null;
                }
                ScheduledFuture<?> cut = WATCHDOG.schedule(() -> closeQuietly(body),
                    Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                try {
                    byte[] bytes = readAtMost(body, UpdateFeed.MAX_FEED_BYTES + 1);
                    if (bytes.length > UpdateFeed.MAX_FEED_BYTES || System.nanoTime() > deadline) {
                        return null;
                    }
                    return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                } finally {
                    cut.cancel(false);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Receives {@code entry} into {@code <folder>/<file>}, never more than the list's size.
     *
     * @param progress  the bytes received so far
     * @param cancelled polled between reads; true stops the download
     * @return the received file
     */
    @NotNull
    public Path download(
        @NotNull UpdateEntry entry,
        @NotNull Path folder,
        @NotNull LongConsumer progress,
        @NotNull BooleanSupplier cancelled
    ) throws IOException, InterruptedException {
        Files.createDirectories(folder);
        Path file = folder.resolve(entry.file());
        Path part = folder.resolve(entry.file() + PART);
        HttpRequest request = HttpRequest.newBuilder(entry.url())
            .GET()
            .header("User-Agent", USER_AGENT) //$NON-NLS-1$
            .timeout(FEED_TIMEOUT)
            .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        AtomicLong lastRead = new AtomicLong(System.nanoTime());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode()); //$NON-NLS-1$
            }
            OptionalLong length = response.headers().firstValueAsLong("Content-Length"); //$NON-NLS-1$
            if (length.isPresent() && length.getAsLong() != entry.size()) {
                throw new IOException("Content-Length differs from the list"); //$NON-NLS-1$
            }
            ScheduledFuture<?> stall = WATCHDOG.scheduleWithFixedDelay(() -> {
                if (System.nanoTime() - lastRead.get() > STALL_TIMEOUT.toNanos() || cancelled.getAsBoolean()) {
                    closeQuietly(body);
                }
            }, 1, 1, TimeUnit.SECONDS);
            long bytes = 0;
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[1 << 16];
                for (int n; (n = body.read(buffer)) > 0; ) {
                    lastRead.set(System.nanoTime());
                    bytes += n;
                    if (bytes > entry.size()) {
                        throw new IOException("Larger than the list says"); //$NON-NLS-1$
                    }
                    out.write(buffer, 0, n);
                    progress.accept(bytes);
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Cancelled"); //$NON-NLS-1$
                    }
                }
            } finally {
                stall.cancel(false);
            }
            if (bytes != entry.size()) {
                throw new IOException("Smaller than the list says"); //$NON-NLS-1$
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e instanceof IOException io ? io : new IOException(e);
        }
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }

    @NotNull
    private static byte[] readAtMost(@NotNull InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; out.size() < limit && (n = in.read(buffer, 0, Math.min(buffer.length, limit - out.size()))) > 0; ) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void closeQuietly(@NotNull InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // (the reader sees the end)
        }
    }
}
