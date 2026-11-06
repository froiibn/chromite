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

import com.seizia.chromite.ui.swing.internal.CoreMessages;
import org.fife.ui.rtextarea.RTextArea;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeListener;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 주 메뉴 Edit(SSMS 순서): Undo, Redo | Cut, Copy, Paste | Select All | Find…, Replace…, Find Next, Find Previous.
 *
 * 설계:
 * <ul>
 *     <li>Undo부터 Select All까지는 키보드 초점이 있는 컴포넌트(마지막 영구 초점)에 적용한다. 메뉴를 마우스로 열어도 Swing 메뉴는 초점을 가져가지 않으므로
 *     편집기·결과 표·입력 칸 어느 것이든 그대로 대상이다. 글자 편집기는 {@link JTextComponent}의 동작, 실행 취소는 RSyntaxTextArea({@link RTextArea})의 기록,
 *     표·트리의 복사는 그 컴포넌트의 전송 처리기({@link TransferHandler#getCopyAction()})를 쓴다.</li>
 *     <li>단축키는 메뉴 단축키(창 전체)라, 초점 컴포넌트가 같은 키를 직접 처리하면(편집기의 Ctrl+Z, 표의 Ctrl+C) 그쪽이 먼저다. 메뉴는 그 키를 보여 주고,
 *     처리하지 않는 컴포넌트에서만 동작한다.</li>
 *     <li>켜고 끄기: 초점이 바뀔 때 컴포넌트 종류로 정하고(단축키가 맞게 동작하도록), 메뉴를 열 때 선택·실행 취소 기록까지 보고 다시 정한다.</li>
 *     <li>Find/Replace는 활성 문서 탭의 편집기({@link FindReplaceDialog})다. 편집기 안에서는 같은 키를 편집기가 직접 받는다({@code SqlEditorPanel.SqlTextArea}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 P1: 최초 작성 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 */
final class EditMenu {

    private enum Command {
        UNDO, REDO, CUT, COPY, PASTE, SELECT_ALL
    }

    private final Supplier<RTextArea> activeTextArea;
    private final JMenu menu = new JMenu(CoreMessages.edit_menu);
    private final Action undoAction;
    private final Action redoAction;
    private final Action cutAction;
    private final Action copyAction;
    private final Action pasteAction;
    private final Action selectAllAction;
    private final Action findAction;
    private final Action replaceAction;
    private final Action findNextAction;
    private final Action findPreviousAction;
    private final PropertyChangeListener focusListener = e -> updateActions(false);

    /**
     * @param activeTextArea text area of the active document tab (query window or DDL tab), or null
     */
    EditMenu(@NotNull Supplier<RTextArea> activeTextArea) {
        this.activeTextArea = activeTextArea;
        undoAction = createCommand(CoreMessages.edit_undo, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK, Command.UNDO);
        redoAction = createCommand(CoreMessages.edit_redo, KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK, Command.REDO);
        cutAction = createCommand(CoreMessages.edit_cut, KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK, Command.CUT);
        copyAction = createCommand(CoreMessages.edit_copy, KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK, Command.COPY);
        pasteAction = createCommand(CoreMessages.edit_paste, KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK, Command.PASTE);
        selectAllAction = createCommand(CoreMessages.edit_select_all, KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK, Command.SELECT_ALL);
        findAction = createAction(CoreMessages.edit_find, KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK, () -> withTextArea(textArea -> FindReplaceDialog.open(textArea, false)));
        replaceAction = createAction(CoreMessages.edit_replace, KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK, () -> withTextArea(textArea -> FindReplaceDialog.open(textArea, true)));
        findNextAction = createAction(CoreMessages.find_find_next, KeyEvent.VK_F3, 0, () -> withTextArea(textArea -> FindReplaceDialog.findAgain(textArea, true)));
        findPreviousAction = createAction(CoreMessages.find_find_previous, KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK,
            () -> withTextArea(textArea -> FindReplaceDialog.findAgain(textArea, false)));

        menu.setMnemonic(KeyEvent.VK_E);
        menu.add(undoAction);
        menu.add(redoAction);
        menu.addSeparator();
        menu.add(cutAction);
        menu.add(copyAction);
        menu.add(pasteAction);
        menu.addSeparator();
        menu.add(selectAllAction);
        menu.addSeparator();
        menu.add(findAction);
        menu.add(replaceAction);
        menu.add(findNextAction);
        menu.add(findPreviousAction);
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {
                updateActions(true);
            }

            @Override
            public void menuDeselected(MenuEvent e) {
                // Shortcuts must not stay disabled by the selection or undo state seen when the menu was open
                updateActions(false);
            }

            @Override
            public void menuCanceled(MenuEvent e) {
            }
        });
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("permanentFocusOwner", focusListener); //$NON-NLS-1$
        updateActions(false);
    }

    @NotNull
    JMenu getMenu() {
        return menu;
    }

    /**
     * The active document changed: Find and Replace need a text area.
     */
    void activeDocumentChanged() {
        updateActions(false);
    }

    void dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removePropertyChangeListener("permanentFocusOwner", focusListener); //$NON-NLS-1$
    }

    @Nullable
    private static Component getFocusOwner() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
    }

    /**
     * @param exact also look at the selection and the undo history (menu open), not only at the kind of component
     */
    private void updateActions(boolean exact) {
        Component owner = getFocusOwner();
        for (Command command : Command.values()) {
            getAction(command).setEnabled(isApplicable(command, owner, exact));
        }
        RTextArea textArea = activeTextArea.get();
        findAction.setEnabled(textArea != null);
        replaceAction.setEnabled(textArea != null && textArea.isEditable());
        findNextAction.setEnabled(textArea != null);
        findPreviousAction.setEnabled(textArea != null);
    }

    @NotNull
    private Action getAction(@NotNull Command command) {
        return switch (command) {
            case UNDO -> undoAction;
            case REDO -> redoAction;
            case CUT -> cutAction;
            case COPY -> copyAction;
            case PASTE -> pasteAction;
            case SELECT_ALL -> selectAllAction;
        };
    }

    private static boolean isApplicable(@NotNull Command command, @Nullable Component owner, boolean exact) {
        if (owner instanceof JTextComponent text) {
            boolean hasSelection = !exact || text.getSelectionStart() != text.getSelectionEnd();
            return switch (command) {
                case UNDO -> owner instanceof RTextArea textArea && text.isEditable() && (!exact || textArea.canUndo());
                case REDO -> owner instanceof RTextArea textArea && text.isEditable() && (!exact || textArea.canRedo());
                case CUT -> text.isEditable() && hasSelection;
                case COPY -> hasSelection;
                case PASTE -> text.isEditable();
                case SELECT_ALL -> true;
            };
        }
        if (owner instanceof JTable table) {
            return switch (command) {
                case COPY -> !exact || table.getSelectedRowCount() > 0;
                case SELECT_ALL -> true;
                default -> false;
            };
        }
        return command == Command.COPY && owner instanceof JComponent component && component.getTransferHandler() != null;
    }

    private static void run(@NotNull Command command) {
        Component owner = getFocusOwner();
        if (!isApplicable(command, owner, true)) {
            UIManager.getLookAndFeel().provideErrorFeedback(owner);
            return;
        }
        if (owner instanceof JTextComponent text) {
            switch (command) {
                case UNDO -> ((RTextArea) owner).undoLastAction();
                case REDO -> ((RTextArea) owner).redoLastAction();
                case CUT -> text.cut();
                case COPY -> text.copy();
                case PASTE -> text.paste();
                case SELECT_ALL -> text.selectAll();
            }
        } else if (owner instanceof JTable table && command == Command.SELECT_ALL) {
            table.selectAll();
        } else if (command == Command.COPY && owner != null) {
            TransferHandler.getCopyAction().actionPerformed(new ActionEvent(owner, ActionEvent.ACTION_PERFORMED, "copy")); //$NON-NLS-1$
        }
    }

    private void withTextArea(@NotNull Consumer<RTextArea> task) {
        RTextArea textArea = activeTextArea.get();
        if (textArea != null) {
            task.accept(textArea);
        }
    }

    @NotNull
    private static Action createCommand(@NotNull String name, int keyCode, int modifiers, @NotNull Command command) {
        return createAction(name, keyCode, modifiers, () -> run(command));
    }

    @NotNull
    private static Action createAction(@NotNull String name, int keyCode, int modifiers, @NotNull Runnable task) {
        Action action = new AbstractAction(name) {
            @Override
            public void actionPerformed(ActionEvent e) {
                task.run();
            }
        };
        action.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(keyCode, modifiers));
        return action;
    }
}
