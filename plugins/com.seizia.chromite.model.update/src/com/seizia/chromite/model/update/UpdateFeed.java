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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The update list {@code <origin>/downloads/releases.json} (Turbrix {@code format} 1, docs/chromite/distribution.md):
 * reading it and choosing the file of a newer version.
 *
 * 설계: Turbrix 데스크톱의 {@code updateCheck.cjs} 규칙을 옮겼다.
 * <ul>
 *     <li>모르는 {@code format}이면 아무것도 고르지 않는다(목록 전체를 버린다). JSON은 엄격하게 읽는다(주석, 작은따옴표,
 *     뒤에 붙은 글자를 받지 않는다).</li>
 *     <li>항목 하나라도 형식이 틀리면 그 항목만 버린다. 파일 이름은 안전한 이름(경로 구분자, {@code ..} 없음)이어야 하고,
 *     계약의 이름(setup: {@code Chromite-<v>-<arch>-setup.exe}, zip: {@code Chromite-<v>-<arch>.zip},
 *     dmg: {@code Chromite-<v>-<arch>.dmg})과 정확히 같아야 한다. 버전은 {@link UpdateVersion}의 {@code x.y.z},
 *     SHA-256은 16진수 64자, 크기는 1 이상 {@link #MAX_FILE_BYTES} 이하 정수다.</li>
 *     <li>받을 주소는 목록의 값이 아니라 같은 출처의 {@code /downloads/<file>}이다. 릴리스 노트 주소도 같은 출처만 받는다.</li>
 *     <li>고르기: 이 설치의 {@code platform}·{@code arch}·{@code kind}가 같고 지금 버전보다 <b>엄격히 높은</b> 것 중 가장
 *     높은 것. 같거나 낮은 버전은 고르지 않는다(서명된 옛 파일로 되돌리게 하는 공격 차단).
 *     지금 버전의 파일({@link #current})은 되돌리기용 설치 파일을 받을 때만 쓴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 */
public final class UpdateFeed {

    public static final int FORMAT = 1;
    public static final String FEED_PATH = "/downloads/releases.json"; //$NON-NLS-1$
    public static final String DOWNLOADS_PATH = "/downloads/"; //$NON-NLS-1$
    /**
     * The list is small; a larger answer is not read.
     */
    public static final int MAX_FEED_BYTES = 1024 * 1024;
    /**
     * The most a file may be (the Windows product with its runtime is about 300 MB).
     */
    public static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_NOTES_URL = 512;

    private static final Pattern FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"); //$NON-NLS-1$
    private static final Pattern TOKEN = Pattern.compile("^[a-z0-9]{1,16}$"); //$NON-NLS-1$
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$"); //$NON-NLS-1$

    private UpdateFeed() {
    }

    /**
     * The file name the contract gives a release file, or null for a kind it does not name.
     */
    @Nullable
    public static String expectedFileName(@NotNull String version, @NotNull String arch, @NotNull String kind) {
        return switch (kind) {
            case UpdateTarget.KIND_SETUP -> "Chromite-" + version + "-" + arch + "-setup.exe"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case UpdateTarget.KIND_ZIP -> "Chromite-" + version + "-" + arch + ".zip"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case UpdateTarget.KIND_DMG -> "Chromite-" + version + "-" + arch + ".dmg"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> null;
        };
    }

    /**
     * The entries of a list that pass every check; empty for a list of another format or one that is not JSON.
     *
     * @param origin the site the list was read from ({@code scheme://host[:port]})
     */
    @NotNull
    public static List<UpdateEntry> parse(@NotNull String json, @NotNull URI origin) {
        List<UpdateEntry> entries = new ArrayList<>();
        JsonElement root;
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            root = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                return entries;
            }
        } catch (IOException | RuntimeException e) {
            return entries;
        }
        if (!(root instanceof JsonObject doc) || !(doc.get("files") instanceof JsonArray files)) { //$NON-NLS-1$
            return entries;
        }
        Long format = integerOf(doc.get("format")); //$NON-NLS-1$
        if (format == null || format != FORMAT) {
            return entries;
        }
        String site = originText(origin);
        for (JsonElement item : files) {
            UpdateEntry entry = entryOf(item, site);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * The file of the highest version strictly above {@code here}'s for its platform, arch and kind, or null.
     */
    @Nullable
    public static UpdateEntry pick(@NotNull List<UpdateEntry> entries, @NotNull UpdateTarget here) {
        UpdateEntry best = null;
        for (UpdateEntry e : entries) {
            if (!matches(e, here) || !UpdateVersion.isNewer(e.version(), here.version())) {
                continue;
            }
            if (best == null || UpdateVersion.isNewer(e.version(), best.version())) {
                best = e;
            }
        }
        return best;
    }

    /**
     * The file of the version installed now (the installer a rollback runs), or null.
     */
    @Nullable
    public static UpdateEntry current(@NotNull List<UpdateEntry> entries, @NotNull UpdateTarget here) {
        for (UpdateEntry e : entries) {
            Integer c = UpdateVersion.compare(e.version(), here.version());
            if (matches(e, here) && c != null && c == 0) {
                return e;
            }
        }
        return null;
    }

    @NotNull
    static String originText(@NotNull URI origin) {
        return origin.getScheme() + "://" + origin.getRawAuthority(); //$NON-NLS-1$
    }

    private static boolean matches(@NotNull UpdateEntry e, @NotNull UpdateTarget here) {
        return e.platform().equals(here.platform()) && e.arch().equals(here.arch()) && e.kind().equals(here.kind());
    }

    @Nullable
    private static UpdateEntry entryOf(@NotNull JsonElement item, @NotNull String site) {
        if (!(item instanceof JsonObject o)) {
            return null;
        }
        String file = stringOf(o.get("file")); //$NON-NLS-1$
        String version = stringOf(o.get("version")); //$NON-NLS-1$
        String arch = stringOf(o.get("arch")); //$NON-NLS-1$
        String kind = stringOf(o.get("kind")); //$NON-NLS-1$
        String platform = stringOf(o.get("platform")); //$NON-NLS-1$
        String sha256 = stringOf(o.get("sha256")); //$NON-NLS-1$
        Long size = integerOf(o.get("size")); //$NON-NLS-1$
        if (file == null || !FILE.matcher(file).matches() || !UpdateVersion.isValid(version)
            || !isToken(arch) || !isToken(kind) || !isToken(platform)
            || size == null || size <= 0 || size > MAX_FILE_BYTES || sha256 == null) {
            return null;
        }
        String expected = expectedFileName(version, arch, kind);
        if (expected != null && !expected.equals(file)) {
            return null;
        }
        sha256 = sha256.toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sha256).matches()) {
            return null;
        }
        String notes = stringOf(o.get("notes_url")); //$NON-NLS-1$
        if (notes != null && (notes.length() > MAX_NOTES_URL || !notes.startsWith(site + "/"))) { //$NON-NLS-1$
            notes = null;
        }
        Long generation = integerOf(o.get("data_generation")); //$NON-NLS-1$
        return new UpdateEntry(file, version, arch, kind, platform, size, sha256,
            URI.create(site + DOWNLOADS_PATH + file), notes,
            generation != null && generation >= 0 && generation <= Integer.MAX_VALUE ? generation.intValue() : null);
    }

    private static boolean isToken(@Nullable String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    @Nullable
    private static String stringOf(@Nullable JsonElement e) {
        return e instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
    }

    /**
     * A JSON number that is a whole number within {@code long}; null for anything else (1.5, "1", true).
     */
    @Nullable
    private static Long integerOf(@Nullable JsonElement e) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(p.getAsString());
            return value.stripTrailingZeros().scale() <= 0 ? value.longValueExact() : null;
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }
}
