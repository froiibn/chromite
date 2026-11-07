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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.connection.DBPDriverLibrary;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.registry.driver.DriverLibraryMavenArtifact;
import com.seizia.chromite.registry.maven.MavenArtifactReference;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.DriverManagerMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.xml.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.xml.parsers.DocumentBuilder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 드라이버 라이브러리 탭의 Maven 아티팩트 추가·편집 대화상자(SWT {@code EditMavenArtifactDialog}의 Swing 판).
 *
 * 설계: SWT처럼 두 가지로 입력한다.
 * <ul>
 *     <li>Dependency Declaration(추가할 때만): Maven {@code <dependency>} 요소(여럿 가능) 또는 Gradle {@code group:artifact:version} 줄을
 *     붙여 넣으면 아티팩트 수를 바로 보인다. SWT는 {@code SAXReader}로 읽는데, 여기서는 {@code XMLUtils}의 안전한 DOM 파서로 읽는다(DOCTYPE·외부 엔터티를 막고,
 *     뿌리 요소가 없는 조각은 감싸서 읽음). Gradle 줄의 네 번째 값은 classifier다(SWT는 버림).</li>
 *     <li>Manual: Group ID, Artifact ID, Classifier, Version(고정 버전, 비우면 대체 버전), Fallback version(RELEASE/LATEST/버전).</li>
 * </ul>
 * 아티팩트는 저장된 드라이버를 다시 읽을 때와 똑같이 경로({@code maven:/group:artifact[:classifier]:fallback})와 선호 버전으로 만든다
 * ({@code DriverLibraryAbstract.createFromPath}가 쓰는 생성자). SWT는 참조의 버전 칸에 선호 버전을 넣어, 다시 시작한 뒤의 객체(버전 RELEASE)와 달랐다.
 * classifier가 있으면 대체 버전을 비울 수 없다(경로 "g:a:c"는 classifier를 대체 버전으로 읽는다). 그래서 비면 RELEASE를 쓴다.
 * 이름에는 글자·숫자·'.'·'_'·'-'만 받는다(경로의 ':'가 섞이면 다시 읽을 때 다른 아티팩트가 된다).
 * <p>
 * 편집은 원래 객체를 고치지 않고 새 객체를 돌려준다. 라이브러리 목록은 드라이버의 실제 객체를 담고 있어, 고친 뒤 드라이버 편집 창을
 * 취소해도 바뀐 값이 남았다(SWT의 문제). 미리 정의된(plugin.xml) 아티팩트는 SWT처럼 읽기 전용이다. 옵션 "전이 의존성 무시",
 * "선택 의존성도 받기"는 {@code drivers.xml}에 저장된다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-driver-manager.md "Maven 아티팩트")
 */
final class MavenArtifactDialog extends JDialog {

    private static final Log log = Log.getLog(MavenArtifactDialog.class);

    // SWT EditMavenArtifactDialog.REGEX_FOR_GRADLE with the classifier kept (4th value)
    private static final Pattern GRADLE_PATTERN = Pattern.compile("([\\w.-]+):([\\w.-]+):([\\w.-]+)(?::([\\w.-]+))?"); //$NON-NLS-1$
    private static final Pattern NAME_PATTERN = Pattern.compile("[\\w.-]+"); //$NON-NLS-1$

    private final DriverDescriptor driver;
    @Nullable
    private final DriverLibraryMavenArtifact original;
    private final boolean readOnly;
    private final List<DriverLibraryMavenArtifact> result = new ArrayList<>();

    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextArea declarationArea = new JTextArea(10, 56);
    private final JLabel declarationStatus = new JLabel(" ");
    private final JTextField groupField = new JTextField(28);
    private final JTextField artifactField = new JTextField(28);
    private final JTextField classifierField = new JTextField(16);
    private final JTextField versionField = new JTextField(16);
    private final JComboBox<String> fallbackCombo = new JComboBox<>(new String[]{
        MavenArtifactReference.VERSION_PATTERN_RELEASE, MavenArtifactReference.VERSION_PATTERN_LATEST});
    private final JCheckBox ignoreDependenciesCheck = new JCheckBox(DriverManagerMessages.drivermaven_ignore_dependencies);
    private final JCheckBox optionalDependenciesCheck = new JCheckBox(DriverManagerMessages.drivermaven_optional_dependencies);
    private final JLabel problemLabel = new JLabel(" ");
    private final JButton okButton = new JButton(CoreMessages.common_ok);
    @Nullable
    private JComponent declarationTab;
    private List<DriverLibraryMavenArtifact> parsed = List.of();

    private MavenArtifactDialog(@NotNull Window owner, @NotNull DriverDescriptor driver, @Nullable DriverLibraryMavenArtifact original) {
        super(owner, original == null ? DriverManagerMessages.drivermaven_title_add : DriverManagerMessages.drivermaven_title_edit,
            ModalityType.APPLICATION_MODAL);
        this.driver = driver;
        this.original = original;
        this.readOnly = original != null && !original.isCustom();
        setIconImages(ChromiteIcons.getWindowIcons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        if (original == null) {
            declarationTab = createDeclarationTab();
            tabs.addTab(DriverManagerMessages.drivermaven_tab_declaration, declarationTab);
        }
        tabs.addTab(DriverManagerMessages.drivermaven_tab_manual, createManualTab());
        tabs.addChangeListener(e -> validateInput());

        ignoreDependenciesCheck.setToolTipText(DriverManagerMessages.drivermaven_ignore_dependencies_tip);
        optionalDependenciesCheck.setToolTipText(DriverManagerMessages.drivermaven_optional_dependencies_tip);
        if (original != null) {
            ignoreDependenciesCheck.setSelected(original.isIgnoreDependencies());
            optionalDependenciesCheck.setSelected(original.isLoadOptionalDependencies());
        }
        ignoreDependenciesCheck.setEnabled(!readOnly);
        optionalDependenciesCheck.setEnabled(!readOnly);
        JPanel options = new JPanel(new GridLayout(0, 1, 0, 4));
        options.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        options.add(ignoreDependenciesCheck);
        options.add(optionalDependenciesCheck);

        problemLabel.setForeground(ConnectionDialog.getThemeColor("Chromite.status.error", "Component.error.focusedBorderColor", Color.RED)); //$NON-NLS-1$ //$NON-NLS-2$
        okButton.addActionListener(e -> accept());
        JButton cancelButton = new JButton(readOnly ? DriverManagerMessages.drivermgr_button_close : CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        if (!readOnly) {
            buttons.add(okButton);
        }
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout(12, 0));
        south.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        south.add(problemLabel, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);

        JPanel center = new JPanel(new BorderLayout());
        center.add(tabs, BorderLayout.CENTER);
        center.add(options, BorderLayout.SOUTH);
        JPanel content = new JPanel(new BorderLayout());
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(center, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(readOnly ? cancelButton : okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        validateInput();
        pack();
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> (original == null ? declarationArea : groupField).requestFocusInWindow());
    }

    /**
     * Asks for new Maven artifacts of the driver. Blocks until closed.
     *
     * @return the new artifacts (custom libraries), empty if canceled
     */
    @NotNull
    static List<DriverLibraryMavenArtifact> openAdd(@NotNull Window owner, @NotNull DriverDescriptor driver) {
        MavenArtifactDialog dialog = new MavenArtifactDialog(owner, driver, null);
        dialog.setVisible(true);
        return dialog.result;
    }

    /**
     * Shows a Maven artifact; a custom one can be changed. Blocks until closed.
     *
     * @return the changed artifact (a new object that replaces the original), or null if canceled or read-only
     */
    @Nullable
    static DriverLibraryMavenArtifact openEdit(@NotNull Window owner, @NotNull DriverDescriptor driver, @NotNull DriverLibraryMavenArtifact library) {
        MavenArtifactDialog dialog = new MavenArtifactDialog(owner, driver, library);
        dialog.setVisible(true);
        return dialog.result.isEmpty() ? null : dialog.result.getFirst();
    }

    @NotNull
    private JComponent createDeclarationTab() {
        declarationArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, declarationArea.getFont().getSize()));
        declarationArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                validateInput();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateInput();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateInput();
            }
        });
        JLabel hint = new JLabel(DriverManagerMessages.drivermaven_declaration_hint);
        hint.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        panel.add(hint, BorderLayout.NORTH);
        panel.add(new JScrollPane(declarationArea), BorderLayout.CENTER);
        panel.add(declarationStatus, BorderLayout.SOUTH);
        return panel;
    }

    @NotNull
    private JComponent createManualTab() {
        fallbackCombo.setEditable(true);
        if (original != null) {
            MavenArtifactReference reference = original.getReference();
            if (reference != null) {
                groupField.setText(CommonUtils.notEmpty(reference.getGroupId()));
                artifactField.setText(CommonUtils.notEmpty(reference.getArtifactId()));
                classifierField.setText(CommonUtils.notEmpty(reference.getClassifier()));
                fallbackCombo.setSelectedItem(CommonUtils.toString(reference.getFallbackVersion(), MavenArtifactReference.VERSION_PATTERN_RELEASE));
            }
            versionField.setText(CommonUtils.notEmpty(original.getPreferredVersion()));
        }
        versionField.setToolTipText(DriverManagerMessages.drivermaven_version_tip);
        fallbackCombo.setToolTipText(DriverManagerMessages.drivermaven_fallback_tip);
        DocumentListener listener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                validateInput();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                validateInput();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                validateInput();
            }
        };
        for (JTextField field : new JTextField[]{groupField, artifactField, classifierField, versionField}) {
            field.getDocument().addDocumentListener(listener);
            field.setEditable(!readOnly);
        }
        fallbackCombo.setEnabled(!readOnly);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        addRow(form, row++, DriverManagerMessages.drivermaven_group, groupField);
        addRow(form, row++, DriverManagerMessages.drivermaven_artifact, artifactField);
        addRow(form, row++, DriverManagerMessages.drivermaven_classifier, classifierField);
        addRow(form, row++, DriverManagerMessages.drivermaven_version, versionField);
        addRow(form, row++, DriverManagerMessages.drivermaven_fallback, fallbackCombo);
        if (readOnly) {
            JLabel note = new JLabel(DriverManagerMessages.drivermaven_read_only);
            note.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.LINE_START;
            c.insets = new Insets(8, 0, 0, 0);
            form.add(note, c);
        }
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(form, BorderLayout.NORTH);
        return holder;
    }

    private static void addRow(@NotNull JPanel form, int row, @NotNull String label, @NotNull JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(4, 0, 4, 12);
        JLabel labelComponent = new JLabel(label);
        labelComponent.setLabelFor(field);
        form.add(labelComponent, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(4, 0, 4, 0);
        form.add(field, c);
    }

    private boolean isDeclarationTab() {
        return declarationTab != null && tabs.getSelectedComponent() == declarationTab;
    }

    /**
     * Parses the declaration or checks the manual fields; OK is enabled only for a usable input.
     */
    private void validateInput() {
        String problem = null;
        if (readOnly) {
            // Nothing to check: only Close
        } else if (isDeclarationTab()) {
            parsed = List.of();
            String text = declarationArea.getText().trim();
            if (text.isEmpty()) {
                declarationStatus.setText(DriverManagerMessages.drivermaven_declaration_empty);
                problem = ""; //$NON-NLS-1$
            } else {
                try {
                    parsed = parseDeclaration(text);
                    declarationStatus.setText(NLS.bind(DriverManagerMessages.drivermaven_declaration_found, parsed.size()));
                    if (parsed.isEmpty()) {
                        problem = DriverManagerMessages.drivermaven_declaration_none;
                    }
                } catch (IllegalArgumentException e) {
                    declarationStatus.setText(" "); //$NON-NLS-1$
                    problem = e.getMessage();
                }
            }
        } else {
            String group = groupField.getText().trim();
            String artifact = artifactField.getText().trim();
            String classifier = classifierField.getText().trim();
            String version = versionField.getText().trim();
            String fallback = getFallbackVersion();
            if (group.isEmpty() || artifact.isEmpty()) {
                problem = DriverManagerMessages.drivermaven_required;
            } else if (!isValidName(group) || !isValidName(artifact) || !isValidOptionalName(classifier)
                || !isValidOptionalName(version) || !isValidOptionalName(fallback)) {
                problem = DriverManagerMessages.drivermaven_invalid_name;
            }
        }
        okButton.setEnabled(problem == null);
        problemLabel.setText(CommonUtils.isEmpty(problem) ? " " : problem); //$NON-NLS-1$
    }

    private static boolean isValidName(@NotNull String value) {
        return NAME_PATTERN.matcher(value).matches();
    }

    private static boolean isValidOptionalName(@NotNull String value) {
        return value.isEmpty() || isValidName(value);
    }

    @NotNull
    private String getFallbackVersion() {
        Object selected = fallbackCombo.isEditable() ? fallbackCombo.getEditor().getItem() : fallbackCombo.getSelectedItem();
        return selected == null ? "" : selected.toString().trim(); //$NON-NLS-1$
    }

    /**
     * Maven {@code <dependency>} elements (an XML fragment) or Gradle {@code group:artifact:version[:classifier]} lines.
     *
     * @throws IllegalArgumentException with a message for the user when the text is neither
     */
    @NotNull
    private List<DriverLibraryMavenArtifact> parseDeclaration(@NotNull String text) {
        if (text.startsWith("<")) { //$NON-NLS-1$
            return parseMaven(text);
        }
        List<DriverLibraryMavenArtifact> artifacts = new ArrayList<>();
        Matcher matcher = GRADLE_PATTERN.matcher(text);
        while (matcher.find()) {
            artifacts.add(createArtifact(matcher.group(1), matcher.group(2), matcher.group(4), null, matcher.group(3)));
        }
        if (artifacts.isEmpty()) {
            throw new IllegalArgumentException(DriverManagerMessages.drivermaven_declaration_invalid);
        }
        return artifacts;
    }

    @NotNull
    private List<DriverLibraryMavenArtifact> parseMaven(@NotNull String text) {
        // A pasted fragment often has several <dependency> elements and no root, or a whole <dependencies> block
        String xml = "<chromite-root>" + text.replaceFirst("^<\\?xml[^>]*\\?>", "") + "</chromite-root>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        Document document;
        try {
            // No DOCTYPE, no external entities (XMLUtils.parseDocument would print parse errors to the console)
            DocumentBuilder builder = XMLUtils.newSecureDocumentBuilderFactory().newDocumentBuilder();
            // The default handler prints parse errors to the console; the message line says it instead
            builder.setErrorHandler(new DefaultHandler());
            document = builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            log.debug("Not a Maven dependency declaration", e);
            throw new IllegalArgumentException(DriverManagerMessages.drivermaven_declaration_invalid);
        }
        List<DriverLibraryMavenArtifact> artifacts = new ArrayList<>();
        NodeList dependencies = document.getElementsByTagName("dependency"); //$NON-NLS-1$
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            String group = getChildText(dependency, "groupId"); //$NON-NLS-1$
            String artifact = getChildText(dependency, "artifactId"); //$NON-NLS-1$
            if (group == null || artifact == null) {
                throw new IllegalArgumentException(DriverManagerMessages.drivermaven_required);
            }
            artifacts.add(createArtifact(group, artifact, getChildText(dependency, "classifier"), null, //$NON-NLS-1$
                getChildText(dependency, "version"))); //$NON-NLS-1$
        }
        return artifacts;
    }

    /**
     * The text of a direct child element (not of the exclusions' groupId etc.).
     */
    @Nullable
    private static String getChildText(@NotNull Element parent, @NotNull String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getTagName())) {
                String text = element.getTextContent().trim();
                return text.isEmpty() ? null : text;
            }
        }
        return null;
    }

    /**
     * An artifact made like a saved one is read back: from its path and preferred version.
     *
     * @throws IllegalArgumentException if a name has characters the path cannot keep
     */
    @NotNull
    private DriverLibraryMavenArtifact createArtifact(
        @NotNull String group,
        @NotNull String artifact,
        @Nullable String classifier,
        @Nullable String fallbackVersion,
        @Nullable String version
    ) {
        if (!isValidName(group) || !isValidName(artifact) || !isValidOptionalName(CommonUtils.notEmpty(classifier))
            || !isValidOptionalName(CommonUtils.notEmpty(fallbackVersion)) || !isValidOptionalName(CommonUtils.notEmpty(version))) {
            throw new IllegalArgumentException(DriverManagerMessages.drivermaven_invalid_name);
        }
        StringBuilder path = new StringBuilder(DriverLibraryMavenArtifact.PATH_PREFIX).append(group).append(':').append(artifact);
        if (!CommonUtils.isEmpty(classifier)) {
            path.append(':').append(classifier);
        }
        path.append(':').append(CommonUtils.isEmpty(fallbackVersion) ? MavenArtifactReference.VERSION_PATTERN_RELEASE : fallbackVersion);
        return new DriverLibraryMavenArtifact(driver, DBPDriverLibrary.FileType.jar, path.toString(), CommonUtils.nullIfEmpty(version));
    }

    private void accept() {
        validateInput();
        if (!okButton.isEnabled()) {
            return;
        }
        List<DriverLibraryMavenArtifact> artifacts;
        try {
            artifacts = isDeclarationTab() ? parsed : List.of(createArtifact(groupField.getText().trim(), artifactField.getText().trim(),
                classifierField.getText().trim(), getFallbackVersion(), versionField.getText().trim()));
        } catch (IllegalArgumentException e) {
            problemLabel.setText(e.getMessage());
            return;
        }
        for (DriverLibraryMavenArtifact artifact : artifacts) {
            artifact.setIgnoreDependencies(ignoreDependenciesCheck.isSelected());
            artifact.setLoadOptionalDependencies(optionalDependenciesCheck.isSelected());
        }
        result.addAll(artifacts);
        dispose();
    }
}
