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

import com.formdev.flatlaf.FlatClientProperties;
import com.seizia.chromite.model.update.UpdateService;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Help &gt; About Chromite 대화상자.
 *
 * 설계: 제품명·부제·저작권은 제품 확장({@code plugin.xml}의 {@code org.eclipse.core.runtime.products} 속성
 * {@code appName}, {@code subTitle}, {@code copyright})에서 읽어 한 곳에서만 관리한다. 버전은 SWT 버전의 About과 같이
 * {@link GeneralUtils#getPlainVersion()}(제품 번들 버전)이다. 링크는 제품 사이트(내려받기, 변경 내역)가 먼저, 소스 저장소가 그 아래이고
 * 둘 다 같은 도우미({@link #createLinkLabel})로 만들어 모양과 동작(링크 색, 손 모양 커서, 도움말, 누르면 기본 브라우저)이 같다.
 * 제품 사이트 주소는 {@link UpdateService#ORIGIN_TEXT}를 쓴다: 사이트와 업데이트는 한 주소로 정해져 있고(docs/chromite/distribution.md
 * "결정 사항"), 업데이트 진행 창의 [받는 곳 열기]도 이미 이 값을 내려받기 페이지로 쓴다. 둘째 사본을 두면 주소가 바뀔 때 한쪽만 바뀐다.
 * DBeaver 이름은 출처 표기(저작권 줄)에만 나온다(CLAUDE.md 브랜딩 규칙).
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-15 제품 사이트 링크(https://chromite.seizia.com)를 저장소 링크 위에 더함. 두 링크를 한 도우미로 만듦 (앱 변경 #120)
 */
final class AboutDialog extends JDialog {

    private static final String REPOSITORY = "https://github.com/froiibn/chromite"; //$NON-NLS-1$

    private AboutDialog(@NotNull Frame owner) {
        super(owner, NLS.bind(CoreMessages.main_about, GeneralUtils.getProductName()), true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        IProduct product = Platform.getProduct();
        String subTitle = product == null ? null : product.getProperty("subTitle"); //$NON-NLS-1$
        String copyright = product == null ? null : product.getProperty("copyright"); //$NON-NLS-1$

        JLabel nameLabel = new JLabel(GeneralUtils.getProductName());
        nameLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h1"); //$NON-NLS-1$
        JLabel subTitleLabel = new JLabel(CommonUtils.notEmpty(subTitle));
        subTitleLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "h3"); //$NON-NLS-1$
        JLabel versionLabel = new JLabel(NLS.bind(CoreMessages.about_version, GeneralUtils.getPlainVersion()));
        JLabel copyrightLabel = new JLabel(CommonUtils.notEmpty(copyright));
        copyrightLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.add(nameLabel);
        textPanel.add(subTitleLabel);
        textPanel.add(Box.createVerticalStrut(12));
        textPanel.add(versionLabel);
        textPanel.add(Box.createVerticalStrut(12));
        textPanel.add(copyrightLabel);
        textPanel.add(Box.createVerticalStrut(4));
        textPanel.add(createLinkLabel(UpdateService.ORIGIN_TEXT));
        textPanel.add(createLinkLabel(REPOSITORY));

        JLabel logoLabel = new JLabel(ChromiteIcons.getLogoIcon(64));
        logoLabel.setVerticalAlignment(SwingConstants.TOP);

        JButton okButton = new JButton(CoreMessages.common_ok);
        okButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.add(okButton);

        JPanel content = new JPanel(new BorderLayout(20, 16));
        content.setBorder(BorderFactory.createEmptyBorder(20, 20, 16, 20));
        content.add(logoLabel, BorderLayout.WEST);
        content.add(textPanel, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    @NotNull
    private static JLabel createLinkLabel(@NotNull String url) {
        JLabel label = new JLabel(url);
        label.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.linkColor"); //$NON-NLS-1$
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.setToolTipText(CoreMessages.about_open_in_browser);
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                DBWorkbench.getPlatformUI().executeShellProgram(url);
            }
        });
        return label;
    }

    static void open(@NotNull Frame owner) {
        new AboutDialog(owner).setVisible(true);
    }
}
