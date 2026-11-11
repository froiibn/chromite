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

import com.formdev.flatlaf.ui.FlatScrollPaneBorder;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;

/**
 * The border of scroll panes: FlatLaf's scroll pane border without its focus look around content areas (result grids,
 * trees, lists, property tables, read-only text); multi-line input fields (editable text) keep it.
 *
 * 설계: FlatLaf는 스크롤 창 안의 표·트리·목록·글 영역이 포커스를 가지면 스크롤 창 테두리를 포커스 색
 * ({@code Component.focusedBorderColor})으로 바꾸고 안쪽 포커스 선({@code Component.innerFocusWidth})을 덧그린다
 * ({@code FlatBorder.isFocused}가 스크롤 창이면 그 안 보기의 포커스를 본다). 내용 영역을 누를 때마다 둘레에 파란 테두리가
 * 생겨 사용자가 없애 달라고 했다(앱 변경 #37, "내용 영역만 제거"). 포커스가 어디 있는지는 선택 바탕색(활성·비활성 색)이 계속
 * 보여 준다. 입력란·콤보·단추는 다른 테두리(FlatTextBorder, FlatRoundBorder)를 쓰므로 그 포커스 표시는 그대로다.
 * 스크롤 창 안의 편집할 수 있는 글 영역(Agent 질문 칸, 대화상자의 여러 줄 입력란)은 입력란이라 포커스 표시를 남긴다(사용자 결정
 * "입력란 유지"). SQL 편집기·Messages·값 보기는 원래 빈 테두리라 포커스 테두리가 없었다.
 * 테마 properties의 {@code ScrollPane.border}와 {@code Table.scrollPaneBorder}(표를 스크롤 창에 넣을 때 Swing이 붙이는 테두리)가
 * 이 클래스를 가리킨다: 스크롤 창마다 고치지 않고 한 곳에서 모든 내용 영역에 적용한다. 크기·모서리·비활성 색은 FlatLaf와 같다.
 *
 * 변경 이력:
 * - 2026-11-11 최초 작성: 내용 영역의 포커스 테두리 제거 (앱 변경 #37, docs/chromite/design-spec.md "포커스 표시")
 */
public class ContentScrollPaneBorder extends FlatScrollPaneBorder {

    @Override
    protected boolean isFocused(Component c) {
        if (c instanceof JScrollPane scrollPane) {
            // A scroll pane is focused when its view is. A content area shows its focus by its selection color instead;
            // a multi-line input field keeps the focus look of the single-line ones
            Component view = scrollPane.getViewport() != null ? scrollPane.getViewport().getView() : null;
            return view instanceof JTextComponent text && text.isEditable() && super.isFocused(c);
        }
        return super.isFocused(c);
    }
}
