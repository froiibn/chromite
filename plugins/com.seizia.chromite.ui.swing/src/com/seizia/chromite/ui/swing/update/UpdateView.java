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
package com.seizia.chromite.ui.swing.update;

import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the update window shows, from what is known of an update now: the words, the marks of the five steps, the bar,
 * the buttons, the Esc button and whether the window may be closed.
 *
 * 설계: Turbrix #747 {@code electron/updateView.cjs}(사용자가 확정한 시안, 단계 목록·브랜드 막대·단계 5개)를 옮겼다
 * (docs/chromite/auto-update.md "업데이트 진행 창"). 순수 함수다. 옛 앱, 앱의 사본, 새 버전의 첫 시작이 같은 말을 하도록
 * 창({@link UpdateWindow})은 이것이 돌려준 것만 그린다.
 * <ul>
 *     <li>단계 다섯: 새 버전 받기 · 업데이트 파일 확인 · 설치 · Chromite 시작 · 창 열기. Turbrix의 "엔진 시작"은 Chromite에서 작업 공간과
 *     번들을 읽는 시작이고, "앱 열기"는 주 창이 뜨는 것이다.</li>
 *     <li>상태 일곱(받는 중, 받음, 설치 중, 시작 중, 완료, 설치 실패, 시작 실패). 끝난 단계만 걸린 시간을 보인다.</li>
 *     <li>막대: 받는 중은 받은 비율, 받음·완료는 100%, 설치 중·시작 중은 길이를 모르는 막대(설치 프로그램은 비율을 말하지 않는다),
 *     실패는 막대 없음(실패는 제목의 표시와 실패한 단계 하나로 말한다).</li>
 *     <li>진행 중(설치 중·시작 중·완료)에는 단추가 없고 창을 닫을 수 없다. 단추가 있는 상태에서 창을 닫으면 그 상태의 Esc 단추와 같다.</li>
 *     <li>설치 실패의 "지금 쓰는 버전은 {0}입니다."는 설치 폴더가 아직 옛 버전일 때만 붙인다(사실일 때만).</li>
 *     <li>모든 사용자 설치에서 관리자 확인(UAC)을 거부해 설치하지 못했으면 그 까닭과 [다시 시도] 안내를 보인다(단추는 같다).</li>
 * </ul>
 * 이 클래스와 같은 패키지의 창은 앱의 사본에서도 돌므로 FlatLaf·JDK·{@link UpdateMessages}·업데이트 모델만 쓴다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 업데이트 진행 창 (docs/chromite/auto-update.md "업데이트 진행 창")
 * - 2026-11-09 관리자 확인 거부의 설치 실패 문구({@link Input#elevationRefused}) (docs/chromite/auto-update.md "관리자 권한으로 설치")
 */
public final class UpdateView {

    public enum State {
        DOWNLOADING("cwwww"), //$NON-NLS-1$
        READY("ddwww"), //$NON-NLS-1$
        INSTALLING("ddcww"), //$NON-NLS-1$
        STARTING("dddcw"), //$NON-NLS-1$
        DONE("ddddd"), //$NON-NLS-1$
        FAILED_INSTALL("ddfww"), //$NON-NLS-1$
        FAILED_START("dddfw"); //$NON-NLS-1$

        private final String marks;

        State(@NotNull String marks) {
            this.marks = marks;
        }

        public boolean isFailed() {
            return this == FAILED_INSTALL || this == FAILED_START;
        }
    }

    public enum Mark {
        DONE,
        CURRENT,
        WAITING,
        FAILED
    }

    public enum Action {
        CLOSE,
        LATER,
        RESTART,
        NOTES,
        AGAIN,
        SITE,
        BACK,
        LOG;

        @NotNull
        public String label() {
            return switch (this) {
                case CLOSE -> UpdateMessages.update_window_close;
                case LATER -> UpdateMessages.update_window_later;
                case RESTART -> UpdateMessages.update_restart_now;
                case NOTES -> UpdateMessages.update_whats_new;
                case AGAIN -> UpdateMessages.update_try_again;
                case SITE -> UpdateMessages.update_open_download_page;
                case BACK -> UpdateMessages.update_window_back;
                case LOG -> UpdateMessages.update_window_log;
            };
        }
    }

    public enum Bar {
        NONE,
        VALUE,
        BUSY
    }

    /**
     * What is known of an update now. Times are milliseconds; null is not known.
     */
    public static final class Input {
        public State state = State.INSTALLING;
        @Nullable
        public String from;
        @Nullable
        public String to;
        /** download, check, install, start, open */
        public final Long[] times = new Long[STEP_COUNT];
        /** While installing: the file is checked again (the check is the current step). */
        public boolean checking;
        @Nullable
        public Long elapsedMs;
        public int percent;
        @Nullable
        public Long bytes;
        @Nullable
        public Long size;
        /** The version has notes ([What's new]). */
        public boolean notes;
        /** A failed installation left the version {@link #from} in place. */
        public boolean versionKept;
        /** The installation failed because the administrator prompt (UAC) was refused. */
        public boolean elevationRefused;
        /** Going back to the version before is possible. */
        public boolean back;
        /** Why the start failed. */
        @Nullable
        public String error;
    }

    public record Step(@NotNull String label, @NotNull Mark mark, @NotNull String spoken, @NotNull String time) {
    }

    /**
     * @param primary  the first button is the primary one
     * @param escape   the button Esc (and closing the window) stands for, null when the window is not closed
     * @param metaLeft null when the line under the bar is not shown
     */
    public record View(
        @NotNull State state,
        @NotNull String name,
        @NotNull String from,
        @NotNull String to,
        @NotNull String versionsSpoken,
        @NotNull String head,
        @NotNull String sub,
        @NotNull Bar bar,
        int barValue,
        @NotNull String barLabel,
        @Nullable String metaLeft,
        @NotNull String metaRight,
        @NotNull List<Step> steps,
        @Nullable String note,
        @Nullable Action link,
        @NotNull List<Action> buttons,
        boolean primary,
        @Nullable Action escape
    ) {
        /**
         * While the update goes on the window is not closed (Alt+F4 included).
         */
        public boolean closable() {
            return !buttons.isEmpty();
        }
    }

    static final int STEP_COUNT = 5;

    private UpdateView() {
    }

    @NotNull
    public static View of(@NotNull Input in) {
        State state = in.state;
        String marks = state == State.INSTALLING && in.checking ? "dcwww" : state.marks; //$NON-NLS-1$
        String[] labels = {
            UpdateMessages.update_window_step_download,
            UpdateMessages.update_window_step_check,
            UpdateMessages.update_window_step_install,
            UpdateMessages.update_window_step_start,
            UpdateMessages.update_window_step_open
        };
        List<Step> steps = new ArrayList<>();
        int at = -1;
        for (int i = 0; i < STEP_COUNT; i++) {
            Mark mark = markOf(marks.charAt(i));
            if (at < 0 && (mark == Mark.CURRENT || mark == Mark.FAILED)) {
                at = i;
            }
            // A step that is done says how long it took, when that is known
            String time = mark == Mark.DONE && in.times[i] != null ? clock(in.times[i]) : ""; //$NON-NLS-1$
            steps.add(new Step(labels[i], mark, spokenOf(mark), time));
        }
        Step current = steps.get(at >= 0 ? at : STEP_COUNT - 1);
        boolean failed = state.isFailed();

        Bar bar = Bar.NONE;
        int value = 0;
        String left = ""; //$NON-NLS-1$
        switch (state) {
            case DOWNLOADING -> {
                value = Math.max(0, Math.min(100, in.percent));
                left = in.bytes != null && in.size != null && in.size > 0
                    ? NLS.bind(UpdateMessages.update_window_received, new Object[]{value, megabytes(in.bytes), megabytes(in.size)})
                    : value + "%"; //$NON-NLS-1$
                bar = Bar.VALUE;
            }
            case READY -> {
                value = 100;
                left = in.size != null && in.size > 0 ? megabytes(in.size) : ""; //$NON-NLS-1$
                bar = Bar.VALUE;
            }
            // The installer tells no share of its work: a run of unknown length
            case INSTALLING, STARTING -> bar = Bar.BUSY;
            case DONE -> {
                value = 100;
                bar = Bar.VALUE;
            }
            default -> {
            }
        }
        String elapsed = state == State.READY || failed ? null : clock(in.elapsedMs);

        String sub = switch (state) {
            case DOWNLOADING -> UpdateMessages.update_window_sub_downloading;
            case READY -> UpdateMessages.update_window_sub_ready;
            case INSTALLING -> UpdateMessages.update_window_sub_installing;
            case STARTING -> UpdateMessages.update_window_sub_starting;
            case DONE -> UpdateMessages.update_window_sub_done;
            case FAILED_INSTALL -> {
                StringBuilder s = new StringBuilder(in.elevationRefused
                    ? UpdateMessages.update_window_elevation_refused : UpdateMessages.update_window_install_failed);
                // Only when it is true: the installer stopped before the files of the version in use were removed
                if (in.versionKept && in.from != null) {
                    s.append(' ').append(NLS.bind(UpdateMessages.update_window_version_kept, in.from));
                }
                yield s.append(' ').append(in.elevationRefused
                    ? UpdateMessages.update_window_elevation_try_again : UpdateMessages.update_window_try_or_download).toString();
            }
            case FAILED_START -> in.error != null && !in.error.isBlank()
                ? in.error.strip() + "\n" + UpdateMessages.update_window_sub_failed_start //$NON-NLS-1$
                : UpdateMessages.update_window_sub_failed_start;
        };
        String head = switch (state) {
            case DOWNLOADING -> UpdateMessages.update_window_head_downloading;
            case READY -> UpdateMessages.update_window_head_ready;
            case INSTALLING -> UpdateMessages.update_window_head_installing;
            case STARTING -> UpdateMessages.update_window_head_starting;
            case DONE -> UpdateMessages.update_window_head_done;
            case FAILED_INSTALL -> UpdateMessages.update_window_head_failed_install;
            case FAILED_START -> UpdateMessages.update_window_head_failed_start;
        };

        String note = null;
        Action link = null;
        List<Action> buttons = List.of();
        boolean primary = true;
        Action escape = null;
        switch (state) {
            case DOWNLOADING -> {
                note = UpdateMessages.update_window_note_downloading;
                buttons = List.of(Action.CLOSE);
                primary = false;
                escape = Action.CLOSE;
            }
            case READY -> {
                link = in.notes ? Action.NOTES : null;
                buttons = List.of(Action.RESTART, Action.LATER);
                escape = Action.LATER;
            }
            case FAILED_INSTALL -> {
                buttons = List.of(Action.AGAIN, Action.SITE, Action.CLOSE);
                escape = Action.CLOSE;
            }
            case FAILED_START -> {
                buttons = in.back ? List.of(Action.BACK, Action.LOG, Action.CLOSE) : List.of(Action.LOG, Action.CLOSE);
                escape = Action.CLOSE;
            }
            default -> note = UpdateMessages.update_window_note_running;
        }
        String from = in.from != null ? in.from : ""; //$NON-NLS-1$
        String to = in.to != null ? in.to : ""; //$NON-NLS-1$
        return new View(
            state,
            UpdateMessages.update_window_name,
            from,
            to,
            in.from != null && in.to != null ? NLS.bind(UpdateMessages.update_window_versions, in.from, in.to) : to,
            head,
            sub,
            bar,
            value,
            current.label(),
            failed ? null : left,
            elapsed != null ? NLS.bind(UpdateMessages.update_window_elapsed, elapsed) : "", //$NON-NLS-1$
            List.copyOf(steps),
            note,
            link,
            buttons,
            primary,
            escape);
    }

    /**
     * "m:ss" (an hour or more: "h:mm:ss"); null for what is not known.
     */
    @Nullable
    static String clock(@Nullable Long ms) {
        if (ms == null || ms < 0) {
            return null;
        }
        long all = ms / 1000;
        long h = all / 3600;
        long m = (all % 3600) / 60;
        long s = all % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Bytes as megabytes with one decimal ("61.7 MB").
     */
    @NotNull
    static String megabytes(long bytes) {
        return NLS.bind(UpdateMessages.update_window_megabytes, String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0))); //$NON-NLS-1$
    }

    @NotNull
    private static Mark markOf(char c) {
        return switch (c) {
            case 'd' -> Mark.DONE;
            case 'c' -> Mark.CURRENT;
            case 'f' -> Mark.FAILED;
            default -> Mark.WAITING;
        };
    }

    @NotNull
    private static String spokenOf(@NotNull Mark mark) {
        return switch (mark) {
            case DONE -> UpdateMessages.update_window_mark_done;
            case CURRENT -> UpdateMessages.update_window_mark_current;
            case WAITING -> UpdateMessages.update_window_mark_waiting;
            case FAILED -> UpdateMessages.update_window_mark_failed;
        };
    }
}
