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

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;

/**
 * Keeps the font metrics of an {@link RSyntaxTextArea} right for the screen scale its window is on.
 *
 * 설계: RSyntaxTextArea(4.0.1)는 토큰 너비를 처음 보일 때(또는 글꼴·색 구성·안티앨리어싱 설정이 바뀔 때) 잰 글꼴 메트릭으로 재고,
 * 글자는 지금 그리는 Graphics로 그린다. 창이 배율이 다른 모니터로 옮겨지면(노트북 150 % ↔ 외부 모니터 100 %, 도킹, 원격 데스크톱 다시 연결)
 * 메트릭은 옛 배율 그대로라, 토큰 자리는 옛 배율의 너비로 정하고 글자는 새 배율의 너비로 그려져 글자가 다음 토큰 위에 겹친다
 * (Cascadia Mono 13: 영문 한 글자 100 % 8 px, 150 % 7.33 px; 앱 변경 #187, docs/chromite/design-spec.md "편집기 글꼴과 SQL 색").
 * AWT는 배율이 바뀌면 모든 구성 요소에 {@code graphicsConfiguration} 속성 변경을 알린다. 알림은 툴킷 스레드에서 올 수 있고, 창을 옮길 때
 * 붙기 전에도 오므로 EDT에서 나중에 확인한다. 이벤트 값 대신 저장된 메트릭의 배율과 지금 창의 배율을 비교한다: 같은 배율이면(같은 모니터의
 * 다른 구성, 창 이동) 아무것도 하지 않아 다시 그리지 않고, 처음 보이기 전이면 RSyntaxTextArea가 스스로 잰다.
 * 다시 재는 것은 같은 색 구성을 다시 넣기({@code setSyntaxScheme(getSyntaxScheme())})다: RSyntaxTextArea가 글꼴 크기 명령에 쓰는 방식이고,
 * 글꼴·색·캐럿·실행 취소 기록을 건드리지 않으며 같은 객체라 속성 변경 알림도 없다. {@code applyTheme}을 다시 부르지 않는 이유: 설정을 다시 읽어
 * 테마를 새로 만들고 편집기마다 다르다(쿼리 창·실행 계획·값 보기). 줄 번호(Gutter)는 그릴 때마다 Graphics의 메트릭을 써서 따로 할 일이 없다.
 *
 * 변경 이력:
 * - 2026-11-28 최초 작성: 창의 화면 배율이 바뀌면 토큰 너비를 다시 잼 (앱 변경 #187)
 */
final class TextAreaScale {

    private static final String GRAPHICS_CONFIGURATION_PROPERTY = "graphicsConfiguration"; //$NON-NLS-1$

    private TextAreaScale() {
    }

    /**
     * Measures the text area's font metrics again whenever its window moves to a screen of another scale.
     */
    static void follow(@NotNull RSyntaxTextArea textArea) {
        textArea.addPropertyChangeListener(GRAPHICS_CONFIGURATION_PROPERTY, e -> SwingUtilities.invokeLater(() -> remeasure(textArea)));
    }

    private static void remeasure(@NotNull RSyntaxTextArea textArea) {
        GraphicsConfiguration configuration = textArea.getGraphicsConfiguration();
        FontMetrics metrics = textArea.getFontMetricsForTokenType(Token.IDENTIFIER);
        if (!textArea.isDisplayable() || configuration == null || metrics == null) {
            return;
        }
        AffineTransform screen = configuration.getDefaultTransform();
        AffineTransform measured = metrics.getFontRenderContext().getTransform();
        if (screen.getScaleX() != measured.getScaleX() || screen.getScaleY() != measured.getScaleY()) {
            textArea.setSyntaxScheme(textArea.getSyntaxScheme());
            textArea.repaint();
        }
    }
}
