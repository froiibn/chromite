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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 값 패널: 결과에서 고른 칸 하나의 값을 표 옆에서 크게 보여 주고, 편집할 수 있는 칸은 고친다(SWT {@code ValueViewerPanel}과 {@code ContentPanelEditor}).
 *
 * 설계:
 * <ul>
 *     <li>보기 방식: Auto(내용으로 고름), Text(줄 바꿈 선택), JSON(들여쓰기), XML(들여쓰기), Image(이진 이미지 미리 보기), Hex(16진수 덤프).
 *     Auto는 LOB의 내용 형식(MIME, {@code DBDContent.getContentType})과 글자 모양으로 고른다: 이진 값은 이미지로 읽히면 Image, 아니면 Hex,
 *     글자는 JSON·XML로 읽히면 그 보기, 아니면 Text. SWT 패널도 내용 형식으로 편집기를 고른다({@code ContentPanelEditor}).</li>
 *     <li>LOB 읽기({@link ResultSetCellContent#load}), JSON·XML 정리, 이미지 디코딩, 16진수 덤프는 모두 백그라운드 Job에서 한다(큰 값에서 EDT가 멈추지 않게).
 *     칸을 빨리 옮기면 마지막 요청의 결과만 보인다(세대 번호).</li>
 *     <li>JSON은 Gson(모델 번들이 다시 내보내는 {@code com.google.gson})으로 다시 쓴다. 숫자는 원래 글자 그대로 남고(Gson의 지연 숫자), HTML 이스케이프는 끈다.
 *     XML은 JDK 파서로 읽어 들여쓴다. 외부 엔터티와 DTD는 막는다(XXE). 여러 뿌리 요소로 된 조각(SQL Server xml 열)은 감싸서 읽고 자식마다 쓴다.
 *     정리하지 못하면 원래 글자를 보이고 이유를 아래 줄에 쓴다.</li>
 *     <li>글자 보기는 RSyntaxTextArea로 하고(JSON·XML 구문 색), SQL 편집기와 같은 테마 파일·글꼴을 쓴다.</li>
 *     <li>편집: 결과 표가 편집할 수 있는 칸이고 글자 값이면 패널에서 고친 뒤 Apply(Ctrl+Enter)로 표에 넣는다. 값은 표의 셀 편집기처럼
 *     열의 값 처리기가 만든다({@link ResultSetGrid#setCellText}). 고치는 중에는 같은 칸이 다시 보여도 글자를 덮어쓰지 않는다.
 *     LOB 열은 표에서도 읽기 전용이라 패널에서도 고치지 않는다.</li>
 *     <li>읽는 양: LOB는 {@link ResultSetCellContent#MAX_LOAD_SIZE}까지, 16진수 덤프는 {@link #MAX_HEX_BYTES}까지 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-result-viewer.md "값 패널")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 */
final class ResultSetValuePanel extends JPanel {

    private static final Log log = Log.getLog(ResultSetValuePanel.class);

    static final int MAX_HEX_BYTES = 256 * 1024;
    private static final String CARD_TEXT = "text"; //$NON-NLS-1$
    private static final String CARD_IMAGE = "image"; //$NON-NLS-1$
    private static final String CARD_MESSAGE = "message"; //$NON-NLS-1$
    private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    /**
     * The cell to show.
     *
     * @param key      identifies the cell (grid, row, column): the same cell shown again keeps an edit in progress
     * @param binding  column of the cell
     * @param display  display string of the grid, or {@link ResultSetGrid#NULL_VALUE}
     * @param value    original value, {@link ResultSetCellContent} for a LOB, or null
     * @param editable whether the grid lets the cell be changed
     */
    record Cell(
        @NotNull String key,
        @NotNull DBDAttributeBinding binding,
        @Nullable Object display,
        @Nullable Object value,
        boolean editable
    ) {
    }

    /**
     * Sets a cell from the panel's text.
     */
    interface Editor {
        /**
         * @return null if the value was set, otherwise why not
         */
        @Nullable
        String apply(@NotNull String text);
    }

    enum Mode {
        AUTO(EditorMessages.editor_value_mode_auto),
        TEXT(EditorMessages.editor_value_mode_text),
        JSON("JSON"),
        XML("XML"),
        IMAGE(EditorMessages.editor_value_mode_image),
        HEX(EditorMessages.editor_value_mode_hex);

        private final String label;

        Mode(@NotNull String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum Kind {
        TEXT,
        IMAGE,
        MESSAGE
    }

    /**
     * What the job made of a cell.
     */
    private record Rendered(
        @NotNull Kind kind,
        @NotNull String text,
        @NotNull String syntax,
        @Nullable BufferedImage image,
        @NotNull String info,
        boolean editable,
        @Nullable String warning,
        boolean wrappable
    ) {
        Rendered(@NotNull Kind kind, @NotNull String text, @NotNull String syntax, @Nullable BufferedImage image, @NotNull String info,
                 boolean editable, @Nullable String warning) {
            this(kind, text, syntax, image, info, editable, warning, true);
        }
    }

    private final JLabel titleLabel = new JLabel(" ");
    private final JComboBox<Mode> modeCombo = new JComboBox<>(Mode.values());
    private final JCheckBox wrapCheck = new JCheckBox(EditorMessages.editor_value_wrap);
    private final JPanel cards = new JPanel(new CardLayout());
    private final ValueTextArea textArea = new ValueTextArea();
    private final ImageView imageView = new ImageView();
    private final JLabel messageLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel infoLabel = new JLabel(" ");
    private final JButton applyButton;
    private final JButton revertButton;
    @Nullable
    private Editor editor;
    @Nullable
    private Cell cell;
    @Nullable
    private Rendered rendered;
    private long generation;
    private boolean dirty;
    private boolean settingText;

    ResultSetValuePanel() {
        super(new BorderLayout());
        setMinimumSize(new Dimension(120, 60));

        JPanel header = new JPanel(new BorderLayout(4, 0));
        header.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 4));
        titleLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold"); //$NON-NLS-1$
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.setOpaque(false);
        modeCombo.setToolTipText(EditorMessages.editor_value_mode_tip);
        modeCombo.addActionListener(e -> rerender());
        wrapCheck.setSelected(true);
        wrapCheck.setFocusable(false);
        wrapCheck.addActionListener(e -> textArea.setLineWrap(wrapCheck.isSelected() && (rendered == null || rendered.wrappable())));
        tools.add(modeCombo);
        tools.add(wrapCheck);
        header.add(titleLabel, BorderLayout.CENTER);
        header.add(tools, BorderLayout.EAST);

        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(false);
        textArea.setCodeFoldingEnabled(true);
        textArea.setHighlightCurrentLine(false);
        textArea.setBracketMatchingEnabled(false);
        textArea.setAntiAliasingEnabled(true);
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                textChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                textChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
        RTextScrollPane textScroll = new RTextScrollPane(textArea, false);
        textScroll.setBorder(BorderFactory.createEmptyBorder());
        JScrollPane imageScroll = new JScrollPane(imageView);
        imageScroll.setBorder(BorderFactory.createEmptyBorder());
        messageLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        cards.add(textScroll, CARD_TEXT);
        cards.add(imageScroll, CARD_IMAGE);
        cards.add(messageLabel, CARD_MESSAGE);

        Action applyAction = ResultSetViewerPanel.createAction(EditorMessages.editor_value_apply,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), this::applyText);
        applyAction.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_value_apply_tip);
        Action revertAction = ResultSetViewerPanel.createAction(EditorMessages.editor_value_revert, null, this::rerender);
        revertAction.putValue(Action.SHORT_DESCRIPTION, EditorMessages.editor_value_revert_tip);
        textArea.getInputMap().put((KeyStroke) applyAction.getValue(Action.ACCELERATOR_KEY), "chromite.applyValue"); //$NON-NLS-1$
        textArea.getActionMap().put("chromite.applyValue", applyAction); //$NON-NLS-1$
        applyButton = new JButton(applyAction);
        revertButton = new JButton(revertAction);
        for (JButton button : new JButton[]{applyButton, revertButton}) {
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            button.setFocusable(false);
        }
        JPanel footer = new JPanel(new BorderLayout(4, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 4));
        infoLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel editButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        editButtons.setOpaque(false);
        editButtons.add(applyButton);
        editButtons.add(revertButton);
        footer.add(infoLabel, BorderLayout.CENTER);
        footer.add(editButtons, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(cards, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        showMessage(EditorMessages.editor_value_select_cell);
        updateEditButtons();
    }

    void setEditor(@Nullable Editor editor) {
        this.editor = editor;
    }

    /**
     * Shows a cell (null: nothing selected). The same cell with an edit in progress keeps the edited text.
     */
    void showCell(@Nullable Cell newCell) {
        if (dirty && cell != null && newCell != null && cell.key().equals(newCell.key())) {
            cell = newCell;
            return;
        }
        cell = newCell;
        dirty = false;
        rerender();
    }

    void dispose() {
        generation++;
    }

    //////////////////////////////////////////////////////////
    // Rendering

    private void rerender() {
        long current = ++generation;
        dirty = false;
        Cell shown = cell;
        if (shown == null) {
            titleLabel.setText(" ");
            showMessage(EditorMessages.editor_value_select_cell);
            infoLabel.setText(" ");
            rendered = null;
            updateEditButtons();
            return;
        }
        titleLabel.setText(shown.binding().getName() + "  " + CommonUtils.notEmpty(shown.binding().getTypeName()).toLowerCase(Locale.ROOT));
        Mode mode = (Mode) modeCombo.getSelectedItem();
        Mode requested = mode == null ? Mode.AUTO : mode;
        infoLabel.setText(EditorMessages.editor_common_reading);
        new AbstractJob("Show cell value") {
            {
                setUser(false);
                setSystem(true);
            }

            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Rendered result;
                try {
                    result = render(shown, requested, monitor);
                } catch (Throwable e) {
                    log.debug("Can't show the cell value", e);
                    result = new Rendered(Kind.MESSAGE, NLS.bind(EditorMessages.editor_value_cant_read, CommonUtils.getAllExceptionMessages(e)),
                        SyntaxConstants.SYNTAX_STYLE_NONE, null, "", false, null);
                }
                Rendered done = result;
                SwingUtilities.invokeLater(() -> {
                    if (current == generation) {
                        show(done);
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void show(@NotNull Rendered result) {
        rendered = result;
        switch (result.kind()) {
            case TEXT -> {
                settingText = true;
                try {
                    textArea.setSyntaxEditingStyle(result.syntax());
                    // A hex dump keeps its columns
                    textArea.setLineWrap(wrapCheck.isSelected() && result.wrappable());
                    textArea.setText(result.text());
                    textArea.setCaretPosition(0);
                    textArea.discardAllEdits();
                    textArea.setEditable(result.editable());
                } finally {
                    settingText = false;
                }
                ((CardLayout) cards.getLayout()).show(cards, CARD_TEXT);
            }
            case IMAGE -> {
                imageView.setImage(result.image());
                ((CardLayout) cards.getLayout()).show(cards, CARD_IMAGE);
            }
            case MESSAGE -> showMessage(result.text());
        }
        setInfo(result.info(), result.warning(), false);
        dirty = false;
        updateEditButtons();
    }

    private void showMessage(@NotNull String text) {
        messageLabel.setText("<html><div style='text-align:center'>" + escapeHtml(text) + "</div></html>"); //$NON-NLS-1$ //$NON-NLS-2$
        ((CardLayout) cards.getLayout()).show(cards, CARD_MESSAGE);
    }

    private void setInfo(@NotNull String info, @Nullable String warning, boolean error) {
        String text = warning == null ? info : (info.isEmpty() ? warning : info + " · " + warning);
        infoLabel.setText(text.isEmpty() ? " " : text);
        infoLabel.setToolTipText(text.isEmpty() ? null : text);
        infoLabel.putClientProperty(FlatClientProperties.STYLE, error || warning != null
            ? "foreground: $Chromite.status.error" //$NON-NLS-1$
            : "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
    }

    /**
     * Turns a cell into what the panel shows (job thread).
     */
    @NotNull
    private static Rendered render(@NotNull Cell cell, @NotNull Mode mode, @NotNull DBRProgressMonitor monitor) throws Exception {
        DBDAttributeBinding binding = cell.binding();
        Object value = cell.value();
        boolean isNull = cell.display() == ResultSetGrid.NULL_VALUE && value == null;
        if (isNull) {
            if (cell.editable() && (mode == Mode.AUTO || mode == Mode.TEXT)) {
                return new Rendered(Kind.TEXT, "", SyntaxConstants.SYNTAX_STYLE_NONE, null, "NULL", true, null);
            }
            return new Rendered(Kind.MESSAGE, "NULL", SyntaxConstants.SYNTAX_STYLE_NONE, null, "", false, null);
        }
        byte[] bytes = null;
        String text = null;
        String contentType = "";
        long length = -1;
        boolean truncated = false;
        if (value instanceof ResultSetCellContent content) {
            ResultSetCellContent.Loaded loaded = content.load(monitor);
            bytes = loaded.bytes();
            text = loaded.text();
            length = loaded.length();
            truncated = loaded.truncated();
            contentType = content.getContentType();
        } else if (value instanceof byte[] array) {
            bytes = array;
            length = array.length;
        } else if (value instanceof String string) {
            text = string;
        } else if (value != null) {
            text = binding.getValueHandler().getValueDisplayString(binding, value, DBDDisplayFormat.EDIT);
        } else {
            // A value known by its display string only (arrays, structures, read errors)
            text = cell.display() == null ? "" : cell.display().toString();
        }
        boolean textValue = bytes == null;
        boolean editable = cell.editable() && textValue && !(value instanceof ResultSetCellContent);
        String size = bytes != null
            ? NLS.bind(EditorMessages.editor_value_bytes, String.format("%,d", length >= 0 ? length : bytes.length)) //$NON-NLS-1$
            : NLS.bind(EditorMessages.editor_value_chars, String.format("%,d", length >= 0 ? length : text.length())); //$NON-NLS-1$
        String info = size + (contentType.isEmpty() ? "" : " · " + contentType);
        String warning = truncated ? NLS.bind(EditorMessages.editor_value_showing_first, String.format("%,d", ResultSetCellContent.MAX_LOAD_SIZE)) : null; //$NON-NLS-1$

        Mode effective = mode;
        if (mode == Mode.AUTO) {
            effective = bytes != null ? (decodeImage(bytes) != null ? Mode.IMAGE : Mode.HEX) : detectTextMode(text, contentType);
        }
        switch (effective) {
            case IMAGE -> {
                BufferedImage image = bytes == null ? null : decodeImage(bytes);
                if (image == null) {
                    return new Rendered(Kind.MESSAGE, EditorMessages.editor_value_not_image,
                        SyntaxConstants.SYNTAX_STYLE_NONE, null, info, false, warning);
                }
                return new Rendered(Kind.IMAGE, "", SyntaxConstants.SYNTAX_STYLE_NONE, image,
                    info + " · " + image.getWidth() + "×" + image.getHeight(), false, warning);
            }
            case HEX -> {
                byte[] data = bytes != null ? bytes : text.getBytes(StandardCharsets.UTF_8);
                String hexWarning = data.length > MAX_HEX_BYTES
                    ? NLS.bind(EditorMessages.editor_value_showing_first_bytes, String.format("%,d", MAX_HEX_BYTES)) : warning; //$NON-NLS-1$
                return new Rendered(Kind.TEXT, hexDump(data, MAX_HEX_BYTES), SyntaxConstants.SYNTAX_STYLE_NONE, null, info, false, hexWarning, false);
            }
            case JSON -> {
                String source = text != null ? text : new String(bytes, StandardCharsets.UTF_8);
                try {
                    return new Rendered(Kind.TEXT, formatJson(source), SyntaxConstants.SYNTAX_STYLE_JSON, null, info, editable, warning);
                } catch (Exception e) {
                    return new Rendered(Kind.TEXT, source, SyntaxConstants.SYNTAX_STYLE_JSON, null, info, editable,
                        NLS.bind(EditorMessages.editor_value_invalid_json, firstLine(e.getMessage())));
                }
            }
            case XML -> {
                String source = text != null ? text : new String(bytes, StandardCharsets.UTF_8);
                try {
                    return new Rendered(Kind.TEXT, formatXml(source), SyntaxConstants.SYNTAX_STYLE_XML, null, info, editable, warning);
                } catch (Exception e) {
                    return new Rendered(Kind.TEXT, source, SyntaxConstants.SYNTAX_STYLE_XML, null, info, editable,
                        NLS.bind(EditorMessages.editor_value_invalid_xml, firstLine(e.getMessage())));
                }
            }
            default -> {
                String source = text != null ? text : new String(bytes, StandardCharsets.UTF_8);
                return new Rendered(Kind.TEXT, source, SyntaxConstants.SYNTAX_STYLE_NONE, null, info, editable, warning);
            }
        }
    }

    @NotNull
    private static Mode detectTextMode(@NotNull String text, @NotNull String contentType) {
        String trimmed = text.strip();
        if (contentType.endsWith("json") || (trimmed.startsWith("{") || trimmed.startsWith("[")) && isJson(trimmed)) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Mode.JSON;
        }
        if (contentType.endsWith("xml") || trimmed.startsWith("<") && trimmed.endsWith(">")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Mode.XML;
        }
        return Mode.TEXT;
    }

    private static boolean isJson(@NotNull String text) {
        try {
            parseJson(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @NotNull
    private static JsonElement parseJson(@NotNull String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement element = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException(EditorMessages.editor_value_text_after_json);
            }
            return element;
        }
    }

    @NotNull
    static String formatJson(@NotNull String text) throws IOException {
        return PRETTY_JSON.toJson(parseJson(text));
    }

    /**
     * Indents XML. A fragment with several root elements (or text around them) is read inside a wrapper element and
     * written child by child.
     */
    @NotNull
    static String formatXml(@NotNull String text) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // No DTDs, external entities or XInclude (XXE)
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(null);
        String trimmed = text.strip();
        boolean declaration = trimmed.startsWith("<?xml"); //$NON-NLS-1$
        Document document;
        boolean wrapped = false;
        try {
            document = builder.parse(new InputSource(new StringReader(trimmed)));
        } catch (Exception e) {
            if (declaration) {
                throw e;
            }
            document = builder.parse(new InputSource(new StringReader("<chromite-fragment>" + trimmed + "</chromite-fragment>"))); //$NON-NLS-1$ //$NON-NLS-2$
            wrapped = true;
        }
        removeWhitespaceText(document.getDocumentElement());
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); //$NON-NLS-1$
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, ""); //$NON-NLS-1$
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes"); //$NON-NLS-1$
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, declaration ? "no" : "yes"); //$NON-NLS-1$ //$NON-NLS-2$
        StringWriter out = new StringWriter();
        if (wrapped) {
            NodeList children = document.getDocumentElement().getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE) {
                    out.write(child.getTextContent().strip());
                    out.write('\n');
                } else {
                    transformer.transform(new DOMSource(child), new StreamResult(out));
                }
            }
        } else {
            transformer.transform(new DOMSource(document), new StreamResult(out));
        }
        return out.toString().strip();
    }

    private static void removeWhitespaceText(@NotNull Node node) {
        NodeList children = node.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE && child.getTextContent().isBlank()) {
                node.removeChild(child);
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                removeWhitespaceText(child);
            }
        }
    }

    @Nullable
    private static BufferedImage decodeImage(@NotNull byte[] bytes) {
        if (bytes.length < 4) {
            return null;
        }
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Offset, 16 bytes in hex and their printable ASCII characters per line.
     */
    @NotNull
    static String hexDump(@NotNull byte[] bytes, int maxBytes) {
        int length = Math.min(bytes.length, maxBytes);
        StringBuilder out = new StringBuilder(length / 16 * 78 + 80);
        for (int offset = 0; offset < length; offset += 16) {
            out.append(String.format("%08X  ", offset)); //$NON-NLS-1$
            for (int i = 0; i < 16; i++) {
                if (offset + i < length) {
                    out.append(String.format("%02X ", bytes[offset + i] & 0xFF)); //$NON-NLS-1$
                } else {
                    out.append("   "); //$NON-NLS-1$
                }
                if (i == 7) {
                    out.append(' ');
                }
            }
            out.append(' ');
            for (int i = 0; i < 16 && offset + i < length; i++) {
                int b = bytes[offset + i] & 0xFF;
                out.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * The first line of a parser message (Gson adds a troubleshooting link on the next line).
     */
    @NotNull
    private static String firstLine(@Nullable String message) {
        String text = CommonUtils.notEmpty(message).strip();
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end).strip();
    }

    @NotNull
    private static String escapeHtml(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    //////////////////////////////////////////////////////////
    // Editing

    private void textChanged() {
        if (settingText || rendered == null || !rendered.editable()) {
            return;
        }
        if (!dirty) {
            dirty = true;
            updateEditButtons();
        }
    }

    private void updateEditButtons() {
        boolean editable = rendered != null && rendered.editable() && rendered.kind() == Kind.TEXT;
        applyButton.setVisible(editable);
        revertButton.setVisible(editable);
        applyButton.setEnabled(dirty);
        revertButton.setEnabled(dirty);
    }

    private void applyText() {
        if (!dirty || editor == null || rendered == null || !rendered.editable()) {
            return;
        }
        String error = editor.apply(textArea.getText());
        if (error != null) {
            setInfo("", NLS.bind(EditorMessages.editor_value_not_applied, error), true); //$NON-NLS-1$
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            return;
        }
        dirty = false;
        updateEditButtons();
        // The grid announces the change; the viewer shows the cell again with its new value
    }

    /**
     * The text area, themed like the SQL editor (the theme is applied again after a look and feel switch).
     */
    private static final class ValueTextArea extends RSyntaxTextArea {
        ValueTextArea() {
            applyTheme();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (getParent() != null) {
                SwingUtilities.invokeLater(this::applyTheme);
            }
        }

        void applyTheme() {
            String resource = ChromiteTheme.getCurrent() == ChromiteTheme.DARK ? "SqlEditorDark.xml" : "SqlEditorLight.xml"; //$NON-NLS-1$ //$NON-NLS-2$
            try (InputStream in = SqlEditorPanel.class.getResourceAsStream(resource)) {
                if (in != null) {
                    Theme.load(in).apply(this);
                }
            } catch (IOException e) {
                log.debug("Can't load the value panel theme " + resource, e);
            }
            Font codeFont = UIManager.getFont("monospaced.font"); //$NON-NLS-1$
            if (codeFont != null) {
                setFont(codeFont);
            }
            setHighlightCurrentLine(false);
        }
    }

    /**
     * An image scaled down to fit (never up), centered.
     */
    private static final class ImageView extends JComponent {
        @Nullable
        private BufferedImage image;

        void setImage(@Nullable BufferedImage image) {
            this.image = image;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(80, 80);
        }

        @Override
        protected void paintComponent(Graphics g) {
            BufferedImage shown = image;
            if (shown == null) {
                return;
            }
            Rectangle area = SwingUtilities.calculateInnerArea(this, null);
            int margin = 8;
            double scale = Math.min(1.0, Math.min((area.width - 2.0 * margin) / shown.getWidth(), (area.height - 2.0 * margin) / shown.getHeight()));
            if (scale <= 0) {
                return;
            }
            int width = (int) Math.round(shown.getWidth() * scale);
            int height = (int) Math.round(shown.getHeight() * scale);
            int x = area.x + (area.width - width) / 2;
            int y = area.y + (area.height - height) / 2;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(shown, x, y, width, height, null);
            } finally {
                g2.dispose();
            }
        }
    }
}
