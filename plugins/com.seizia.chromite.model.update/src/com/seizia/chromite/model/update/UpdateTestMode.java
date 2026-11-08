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

import java.net.URI;

/**
 * The updater's test mode for DEV BUILDS ONLY: a local update site, and the expected publisher and product name of a
 * locally available signed test file. Never in a release build.
 *
 * 설계: 개발 빌드(Maven 결과물)는 업데이트하지 않는다({@link UpdatePlace}). 업데이트 경로 전체를 이 PC에서 시험하려면
 * 개발 빌드를 "설치된 것처럼" 다루고 로컬 사이트를 읽어야 한다. 그 스위치가 이 클래스다(시스템 속성, {@code -vmargs -D...}).
 * <ul>
 *     <li>{@value #PROP_ORIGIN}: 사이트 주소 {@code http(s)://host[:port]}. 이 속성이 있어야 시험 모드다.</li>
 *     <li>{@value #PROP_PUBLISHER}, {@value #PROP_PRODUCT}: 기대하는 서명자 주체와 ProductName(없으면 실제 값).
 *     서명 상태 {@code Valid}와 타임스탬프 요구는 그대로다. 그래서 Windows가 신뢰하는 체인으로 서명된 파일만 통과한다.</li>
 *     <li>설치 파일은 실행하지 않는다(dry run). 도우미는 확인·기다리기·다시 시작만 한다.</li>
 *     <li>{@value #PROP_INSTALLER_CODE}: 앱의 사본({@link UpdateHandOver})이 dry run에서 설치 프로그램의 종료 코드로 알릴 값(없으면 0).
 *     설치 실패 화면을 실제 설치 없이 보려고 둔다.</li>
 * </ul>
 * 릴리스 빌드에서 켜지지 않게 하는 조건(모두 만족해야 켜진다, {@link UpdatePlace#detect}):
 * (1) 이 복사본이 설치 프로그램으로 설치된 것이 아니다(Windows: 제거 정보에 이 폴더가 없다),
 * (2) Windows: 실행 중인 런처가 우리 게시자({@link WindowsUpdateVerifier#PUBLISHER})로 서명되어 있지 않다
 * (릴리스 빌드의 런처는 CI가 서명한다, docs/chromite/signing.md), macOS: 실행 중인 앱에 Team ID가 없다(ad hoc).
 * 조건이 맞지 않으면 속성을 무시하고 로그에 남긴다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md "시험 모드")
 * - 2026-11-08 {@value #PROP_INSTALLER_CODE}: dry run이 알릴 설치 프로그램 종료 코드 (docs/chromite/auto-update.md "업데이트 진행 창")
 */
public record UpdateTestMode(@NotNull URI origin, @Nullable String publisher, @Nullable String productName,
                             @Nullable Integer installerCode) {

    public static final String PROP_ORIGIN = "chromite.update.test.origin"; //$NON-NLS-1$
    public static final String PROP_PUBLISHER = "chromite.update.test.publisher"; //$NON-NLS-1$
    public static final String PROP_PRODUCT = "chromite.update.test.product"; //$NON-NLS-1$
    public static final String PROP_INSTALLER_CODE = "chromite.update.test.installer.code"; //$NON-NLS-1$

    /**
     * The test mode the system properties ask for, or null; a malformed origin asks for none.
     */
    @Nullable
    public static UpdateTestMode fromSystemProperties() {
        String origin = System.getProperty(PROP_ORIGIN);
        if (origin == null || origin.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(origin.trim());
            boolean web = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme()); //$NON-NLS-1$ //$NON-NLS-2$
            String path = uri.getRawPath();
            if (!web || uri.getHost() == null || (path != null && !path.isEmpty() && !"/".equals(path)) //$NON-NLS-1$
                || uri.getRawQuery() != null || uri.getRawUserInfo() != null) {
                return null;
            }
            return new UpdateTestMode(URI.create(UpdateFeed.originText(uri)),
                System.getProperty(PROP_PUBLISHER), System.getProperty(PROP_PRODUCT), Integer.getInteger(PROP_INSTALLER_CODE));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
