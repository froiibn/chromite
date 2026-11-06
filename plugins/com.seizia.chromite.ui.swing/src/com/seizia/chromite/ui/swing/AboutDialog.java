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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.GeneralUtils;
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
 * {@link GeneralUtils#getPlainVersion()}(제품 번들 버전)이다. 웹사이트 링크는 기본 브라우저로 연다.
 * DBeaver 이름은 출처 표기(저작권 줄)에만 나온다(CLAUDE.md 브랜딩 규칙).
 *
 * 변경 이력:
 * - 2026-11-06 B단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 */
final class AboutDialog extends JDialog {

    private static final String WEBSITE = "https://github.com/froiibn/chromite"; //$NON-NLS-1$

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

        JLabel websiteLabel = new JLabel(WEBSITE);
        websiteLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.linkColor"); //$NON-NLS-1$
        websiteLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        websiteLabel.setToolTipText(CoreMessages.about_open_in_browser);
        websiteLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                DBWorkbench.getPlatformUI().executeShellProgram(WEBSITE);
            }
        });

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.add(nameLabel);
        textPanel.add(subTitleLabel);
        textPanel.add(Box.createVerticalStrut(12));
        textPanel.add(versionLabel);
        textPanel.add(Box.createVerticalStrut(12));
        textPanel.add(copyrightLabel);
        textPanel.add(Box.createVerticalStrut(4));
        textPanel.add(websiteLabel);

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

    static void open(@NotNull Frame owner) {
        new AboutDialog(owner).setVisible(true);
    }
}
