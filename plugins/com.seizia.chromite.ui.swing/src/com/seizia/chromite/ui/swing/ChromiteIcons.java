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
import org.jkiss.dbeaver.Log;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chromite 창 아이콘.
 *
 * 설계: Swing 창에 아이콘을 지정하지 않으면 Java 런타임의 기본 아이콘이 제목 표시줄과 작업 표시줄에 나온다.
 * JBR에서는 JetBrains 아이콘이라 제품이 JetBrains 제품처럼 보인다. 그래서 모든 최상위 창에 Chromite 아이콘을 지정한다.
 * OS가 화면 배율에 맞는 크기를 고를 수 있게 여러 크기(16~256)를 함께 준다.
 * 이미지는 {@code product/community/icons-sources}의 Chromite 아이콘이다(16, 32, 48, 64, 128, 256은 그대로 복사,
 * 20, 24, 40은 512px 원본을 축소해 만들었다).
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 */
final class ChromiteIcons {

    private static final Log log = Log.getLog(ChromiteIcons.class);

    private static final int[] WINDOW_ICON_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private static List<Image> windowIcons;

    private ChromiteIcons() {
    }

    /**
     * Window icons in all available sizes, for {@link Window#setIconImages}.
     */
    @NotNull
    static synchronized List<Image> getWindowIcons() {
        if (windowIcons == null) {
            List<Image> icons = new ArrayList<>();
            for (int size : WINDOW_ICON_SIZES) {
                String path = "/icons/chromite" + size + ".png";
                try (InputStream stream = ChromiteIcons.class.getResourceAsStream(path)) {
                    if (stream == null) {
                        log.warn("Window icon not found: " + path);
                        continue;
                    }
                    icons.add(ImageIO.read(stream));
                } catch (IOException e) {
                    log.warn("Can't read window icon " + path, e);
                }
            }
            windowIcons = Collections.unmodifiableList(icons);
        }
        return windowIcons;
    }
}
