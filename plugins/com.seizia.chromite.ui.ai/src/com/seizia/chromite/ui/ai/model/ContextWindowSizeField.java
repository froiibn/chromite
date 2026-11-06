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
package com.seizia.chromite.ui.ai.model;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.validator.IntegerValidator;
import org.jkiss.utils.CommonUtils;

/**
 * Context window size input of the AI engine settings pages.
 *
 * 설계: 화면에 보이는 값이 저장되는 값이다. 제공자 모델 목록에서 온 기본값(setDefaultValue)도 저장한다.
 * 실행 중에 모델 이름으로 컨텍스트 크기를 다시 찾을 원본이 없으므로, 보이는 값을 저장하지 않으면 실제 요청에서는
 * AIConstants.DEFAULT_CONTEXT_WINDOW_SIZE가 쓰여 화면과 달라진다.
 * 사용자가 입력하거나 저장된 값을 불러온 뒤에는 목록 새로 고침이 값을 덮어쓰지 않는다(userDefined).
 *
 * 변경 이력:
 * - 2026-11-06 dbeaver.com AI 모델 카탈로그 제거: getValue()가 기본값일 때 null을 돌려주던 동작을 없앴다.
 *   이 동작은 카탈로그가 실행 중에 같은 값을 다시 채워 준다는 전제였다.
 */
public class ContextWindowSizeField {
    @NotNull
    private final Text text;

    private Integer value;
    private boolean userDefined;

    private ContextWindowSizeField(@NotNull Text text) {
        this.text = text;
        this.text.addModifyListener(e -> {
            value = CommonUtils.toInteger(text.getText(), null);
            userDefined = value != null;
        });
    }

    @NotNull
    public static Builder builder() {
        return new Builder();
    }

    public void setValue(@Nullable Integer value) {
        this.text.setText(value == null ? "" : value.toString());
        this.value = value;
        userDefined = value != null;
    }

    public void setDefaultValue(@Nullable Integer value) {
        if (!userDefined) {
            setValue(value);
            // Metadata may be replaced by a later refresh until the user edits the field.
            userDefined = false;
        }
    }

    @Nullable
    public Integer getValue() {
        return value;
    }

    public boolean isComplete() {
        return CommonUtils.toInt(text.getText(), 0) > 0;
    }

    public static class Builder {
        @NotNull
        private Composite parent;
        @NotNull
        private GridData gridData;

        public Builder withParent(@NotNull Composite parent) {
            this.parent = parent;
            return this;
        }

        public Builder withGridData(@NotNull GridData gridData) {
            this.gridData = gridData;
            return this;
        }

        public ContextWindowSizeField build() {
            Text text = UIUtils.createLabelText(
                parent,
                "Context window size",
                "",
                SWT.BORDER
            );
            text.addVerifyListener(new IntegerValidator(1, Integer.MAX_VALUE));
            text.setLayoutData(gridData);

            return new ContextWindowSizeField(text);
        }
    }
}
