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
import com.seizia.chromite.ui.swing.internal.GridEditMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
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
 *     <li>글자 보기는 RSyntaxTextArea로 하고(JSON·XML 구문 색), SQL 편집기와 같은 테마 파일을 쓴다. 글꼴은 SQL 편집기 글꼴({@link EditorFont})이
 *     아니라 Messages·오류 상세와 같은 코드 글꼴 {@code monospaced.font}다(design-spec.md {@code font.code}): 값 패널은 결과 쪽 화면이라
 *     Options의 편집기 글꼴 설정을 따르지 않는다.</li>
 *     <li>편집: 결과 표가 편집할 수 있는 칸이고 글자 값이면 패널에서 고친 뒤 Apply(Ctrl+Enter)로 표에 넣는다. 값은 표의 셀 편집기처럼
 *     열의 값 처리기가 만든다({@link ResultSetGrid#setCellText}). 고치는 중에는 같은 칸이 다시 보여도 글자를 덮어쓰지 않는다.
 *     글자 LOB(JSON, XML, CLOB)도 여기서 고친다(표 칸에서는 고치지 않음). 잘라 읽은 LOB는 고치면 뒷부분을 잃으므로 고치지 않고 파일로 바꾼다.
 *     이진 LOB는 파일에서 불러오기·파일로 저장 단추(결과 뷰어의 동작, {@link #setFileActions})로 다룬다.</li>
 *     <li>JSON·XML 편집기(SWT {@code JSONPanelEditor}, {@code XMLPanelEditor}): Format 단추가 지금 글자를 다시 들여쓰고, 고치는 동안 잠시 뒤
 *     JSON·XML로 읽히는지 아래 줄에 보인다. JSON·XML 형식 열(형식 이름이나 LOB 내용 형식)은 읽히지 않는 글자를 Apply하지 않는다(DB가 거부할 값을
 *     저장 전에 막음). 다른 글자 열은 보기 방식이 JSON·XML이어도 그대로 넣는다.</li>
 *     <li>읽는 양: LOB는 {@link ResultSetCellContent#MAX_LOAD_SIZE}까지, 16진수 덤프는 {@link #MAX_HEX_BYTES}까지 보인다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-result-viewer.md "값 패널")
 * - 2026-11-06 화면 문구를 EditorMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 설계 설명의 글꼴을 코드와 맞춤: SQL 편집기 글꼴이 아니라 {@code monospaced.font}(docs/chromite/swing-result-viewer.md "값 패널")
 * - 2026-11-08 글자 LOB 편집, JSON·XML 정리(Format)와 검사, 파일 불러오기·저장 단추 (docs/chromite/swing-grid-editing.md "JSON·XML 편집기")
 * - 2026-11-08 NULL 배열·구조체(값이 없는 값 객체)를 NULL로 보임: 편집 모델이 배열·구조체 값을 남기게 되어 "NULL" 글자로 보이던 것
 *   (docs/chromite/swing-value-editors.md)
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
     * @param value    original value, {@link ResultSetCellContent} for a LOB, a value object set in the grid, or null
     * @param editable whether the grid lets the cell be changed as text (not a binary LOB)
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
    private final JButton formatButton;
    private final JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final Timer validateTimer;
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
        Action formatAction = ResultSetViewerPanel.createAction(GridEditMessages.gridedit_value_format,
            KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), this::formatText);
        formatAction.putValue(Action.SHORT_DESCRIPTION, GridEditMessages.gridedit_value_format_tip);
        textArea.getInputMap().put((KeyStroke) formatAction.getValue(Action.ACCELERATOR_KEY), "chromite.formatValue"); //$NON-NLS-1$
        textArea.getActionMap().put("chromite.formatValue", formatAction); //$NON-NLS-1$
        // JSON and XML are checked a moment after typing stops
        validateTimer = new Timer(400, e -> validateText());
        validateTimer.setRepeats(false);
        applyButton = new JButton(applyAction);
        revertButton = new JButton(revertAction);
        formatButton = new JButton(formatAction);
        for (JButton button : new JButton[]{formatButton, applyButton, revertButton}) {
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            button.setFocusable(false);
        }
        // The info line keeps the full width; the buttons (edit, Format, files) go on a line of their own when shown
        JPanel footer = new JPanel(new BorderLayout(4, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 4));
        infoLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        JPanel editButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        editButtons.setOpaque(false);
        fileButtons.setOpaque(false);
        editButtons.add(fileButtons);
        editButtons.add(formatButton);
        editButtons.add(applyButton);
        editButtons.add(revertButton);
        footer.add(infoLabel, BorderLayout.CENTER);
        footer.add(editButtons, BorderLayout.SOUTH);

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
     * Load from File and Save to File of LOB cells (the result viewer's actions, which it enables for the selected cell).
     */
    void setFileActions(@NotNull Action load, @NotNull Action save) {
        fileButtons.removeAll();
        // Icon buttons (the tool tip names them): with Format, Apply and Revert the line must fit a narrow panel
        for (Action action : new Action[]{load, save}) {
            JButton button = UiIcons.createActionButton(action, action == load ? "open" : "save"); //$NON-NLS-1$ //$NON-NLS-2$
            button.setVisible(action.isEnabled());
            // Shown only where they apply, so the footer of a plain cell stays as it was
            action.addPropertyChangeListener(e -> button.setVisible(action.isEnabled()));
            fileButtons.add(button);
        }
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
                    result = new Rendered(Kind.MESSAGE, NLS.bind(EditorMessages.editor_value_cant_read, ErrorText.describe(e)),
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
        if (value instanceof DBDContent content) {
            // A LOB value set in the grid (panel, file): read like a fetched one, without releasing it
            value = DBUtils.isNullValue(content) ? null : ResultSetCellContent.capture(content);
        }
        // A NULL array or structure is a value object that holds no value
        boolean isNull = cell.display() == ResultSetGrid.NULL_VALUE && (value == null || DBUtils.isNullValue(value));
        if (isNull) {
            // A NULL JSON or XML cell is typed with that editor
            Mode nullMode = mode == Mode.AUTO ? detectTypeMode(binding) : mode;
            if (cell.editable() && (nullMode == Mode.TEXT || nullMode == Mode.JSON || nullMode == Mode.XML)) {
                return new Rendered(Kind.TEXT, "", getSyntax(nullMode), null, "NULL", true, null);
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
        // A LOB read in part can't be edited here: the rest would be lost
        boolean editable = cell.editable() && textValue && !truncated;
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
                        NLS.bind(EditorMessages.editor_value_invalid_json, firstLine(ErrorText.describe(e))));
                }
            }
            case XML -> {
                String source = text != null ? text : new String(bytes, StandardCharsets.UTF_8);
                try {
                    return new Rendered(Kind.TEXT, formatXml(source), SyntaxConstants.SYNTAX_STYLE_XML, null, info, editable, warning);
                } catch (Exception e) {
                    return new Rendered(Kind.TEXT, source, SyntaxConstants.SYNTAX_STYLE_XML, null, info, editable,
                        NLS.bind(EditorMessages.editor_value_invalid_xml, firstLine(ErrorText.describe(e))));
                }
            }
            default -> {
                String source = text != null ? text : new String(bytes, StandardCharsets.UTF_8);
                return new Rendered(Kind.TEXT, source, SyntaxConstants.SYNTAX_STYLE_NONE, null, info, editable, warning);
            }
        }
    }

    /**
     * The editor of a JSON or XML column by its type name, else Text.
     */
    @NotNull
    private static Mode detectTypeMode(@NotNull DBDAttributeBinding binding) {
        String typeName = CommonUtils.notEmpty(binding.getTypeName()).toLowerCase(Locale.ROOT);
        return typeName.contains("json") ? Mode.JSON : typeName.contains("xml") ? Mode.XML : Mode.TEXT; //$NON-NLS-1$ //$NON-NLS-2$
    }

    @NotNull
    private static String getSyntax(@NotNull Mode mode) {
        return mode == Mode.JSON ? SyntaxConstants.SYNTAX_STYLE_JSON : mode == Mode.XML ? SyntaxConstants.SYNTAX_STYLE_XML : SyntaxConstants.SYNTAX_STYLE_NONE;
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
     * The first line of a parser error text (Gson adds a troubleshooting link on the next line).
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
        if (isStructured(rendered)) {
            validateTimer.restart();
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
        formatButton.setVisible(editable && isStructured(rendered));
        applyButton.setEnabled(dirty);
        revertButton.setEnabled(dirty);
    }

    /**
     * The text is shown with the JSON or XML editor.
     */
    private static boolean isStructured(@Nullable Rendered shown) {
        return shown != null && (SyntaxConstants.SYNTAX_STYLE_JSON.equals(shown.syntax()) || SyntaxConstants.SYNTAX_STYLE_XML.equals(shown.syntax()));
    }

    /**
     * Why the text is not valid JSON or XML for the editor shown, or null if it is.
     */
    @Nullable
    private static String validate(@NotNull String syntax, @NotNull String text) {
        try {
            if (SyntaxConstants.SYNTAX_STYLE_JSON.equals(syntax)) {
                parseJson(text);
                return null;
            }
            formatXml(text);
            return null;
        } catch (Exception e) {
            return NLS.bind(SyntaxConstants.SYNTAX_STYLE_JSON.equals(syntax) ? EditorMessages.editor_value_invalid_json : EditorMessages.editor_value_invalid_xml,
                firstLine(ErrorText.describe(e)));
        }
    }

    private void validateText() {
        if (rendered == null || !rendered.editable() || !isStructured(rendered)) {
            return;
        }
        String problem = validate(rendered.syntax(), textArea.getText());
        if (problem == null) {
            setInfo(SyntaxConstants.SYNTAX_STYLE_JSON.equals(rendered.syntax()) ? GridEditMessages.gridedit_value_valid_json
                : GridEditMessages.gridedit_value_valid_xml, null, false);
        } else {
            setInfo("", problem, true); //$NON-NLS-1$
        }
    }

    /**
     * Format: indents the JSON or XML being edited (the edit stays pending until Apply).
     */
    private void formatText() {
        if (rendered == null || !rendered.editable() || !isStructured(rendered)) {
            return;
        }
        String text = textArea.getText();
        String problem = validate(rendered.syntax(), text);
        if (problem != null) {
            setInfo("", problem, true); //$NON-NLS-1$
            UIManager.getLookAndFeel().provideErrorFeedback(textArea);
            return;
        }
        try {
            String formatted = SyntaxConstants.SYNTAX_STYLE_JSON.equals(rendered.syntax()) ? formatJson(text) : formatXml(text);
            if (!formatted.equals(text)) {
                int caret = Math.min(textArea.getCaretPosition(), formatted.length());
                textArea.setText(formatted);
                textArea.setCaretPosition(caret);
            }
            validateText();
        } catch (Exception e) {
            setInfo("", firstLine(ErrorText.describe(e)), true); //$NON-NLS-1$
        }
    }

    /**
     * A JSON or XML column (by its type name or its LOB content type): the database rejects text that is not.
     */
    private boolean isStructuredColumn() {
        if (cell == null) {
            return false;
        }
        String typeName = CommonUtils.notEmpty(cell.binding().getTypeName()).toLowerCase(Locale.ROOT);
        String contentType = cell.value() instanceof ResultSetCellContent content ? content.getContentType() : "";
        return typeName.contains("json") || typeName.contains("xml") || contentType.endsWith("json") || contentType.endsWith("xml"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private void applyText() {
        if (!dirty || editor == null || rendered == null || !rendered.editable()) {
            return;
        }
        if (isStructured(rendered) && isStructuredColumn()) {
            String problem = validate(rendered.syntax(), textArea.getText());
            if (problem != null) {
                setInfo("", NLS.bind(EditorMessages.editor_value_not_applied, problem), true); //$NON-NLS-1$
                UIManager.getLookAndFeel().provideErrorFeedback(textArea);
                return;
            }
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
