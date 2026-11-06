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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Query Library 쿼리의 매개변수 정의 하나(이름, 표시 이름, 형식, 기본값, 필수, 선택 목록, 조회 쿼리).
 *
 * 설계: 매개변수 자체는 SQL에서 찾는다(실행할 때는 모델의 파서, {@link QueryParameterBinder}). 정의는 그 이름에 붙는 부가 정보이고
 * 쿼리 파일 머리말({@code -- @param})에 저장한다({@link QueryLibraryFile}). 정의가 없는 매개변수는 형식 {@link Type#TEXT}로 묻는다.
 * 값은 대화상자와 파일에서 모두 문자열이고, 바인딩할 때 {@link #toJdbcValue}로 형식에 맞는 Java 값으로 바꾼다.
 * 날짜는 ISO 형식({@code 2026-11-06}, {@code 2026-11-06 08:30[:00]})만 받는다(지역 형식에 따라 달라지지 않게).
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "매개변수")
 */
final class QueryLibraryParameter {

    /**
     * Value type; the id is the value of {@code type:} in the file.
     */
    enum Type {
        TEXT("text", "Text"), //$NON-NLS-1$
        NUMBER("number", "Number"), //$NON-NLS-1$
        DATE("date", "Date"), //$NON-NLS-1$
        DATETIME("datetime", "Date and time"), //$NON-NLS-1$
        BOOLEAN("boolean", "Boolean"), //$NON-NLS-1$
        CHOICE("choice", "Choice list"), //$NON-NLS-1$
        LOOKUP("lookup", "Lookup query"); //$NON-NLS-1$

        private final String id;
        private final String label;

        Type(@NotNull String id, @NotNull String label) {
            this.id = id;
            this.label = label;
        }

        @NotNull
        String getId() {
            return id;
        }

        @NotNull
        String getLabel() {
            return label;
        }

        @NotNull
        static Type fromId(@Nullable String id) {
            for (Type type : values()) {
                if (type.id.equalsIgnoreCase(CommonUtils.notEmpty(id).trim())) {
                    return type;
                }
            }
            return TEXT;
        }
    }

    private static final DateTimeFormatter DATE_TIME_FORMAT = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd[[ ]['T']HH:mm[:ss]]") //$NON-NLS-1$
        .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
        .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
        .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
        .toFormatter(Locale.ROOT);

    private String name;
    @Nullable
    private String label;
    private Type type = Type.TEXT;
    @Nullable
    private String defaultValue;
    private boolean required;
    private final List<String> choices = new ArrayList<>();
    @Nullable
    private String lookupSql;

    QueryLibraryParameter(@NotNull String name) {
        this.name = name;
    }

    @NotNull
    QueryLibraryParameter copy() {
        QueryLibraryParameter copy = new QueryLibraryParameter(name);
        copy.label = label;
        copy.type = type;
        copy.defaultValue = defaultValue;
        copy.required = required;
        copy.choices.addAll(choices);
        copy.lookupSql = lookupSql;
        return copy;
    }

    @NotNull
    String getName() {
        return name;
    }

    void setName(@NotNull String name) {
        this.name = name;
    }

    @Nullable
    String getLabel() {
        return label;
    }

    void setLabel(@Nullable String label) {
        this.label = CommonUtils.nullIfEmpty(label);
    }

    /**
     * The label, or the name if it has none.
     */
    @NotNull
    String getDisplayLabel() {
        return CommonUtils.isEmpty(label) ? name : label;
    }

    @NotNull
    Type getType() {
        return type;
    }

    void setType(@NotNull Type type) {
        this.type = type;
    }

    @Nullable
    String getDefaultValue() {
        return defaultValue;
    }

    void setDefaultValue(@Nullable String defaultValue) {
        this.defaultValue = CommonUtils.nullIfEmpty(defaultValue);
    }

    boolean isRequired() {
        return required;
    }

    void setRequired(boolean required) {
        this.required = required;
    }

    @NotNull
    List<String> getChoices() {
        return choices;
    }

    @Nullable
    String getLookupSql() {
        return lookupSql;
    }

    void setLookupSql(@Nullable String lookupSql) {
        this.lookupSql = CommonUtils.nullIfEmpty(lookupSql);
    }

    /**
     * Whether the definition says nothing besides the name (it is then not written to the file).
     */
    boolean isPlain() {
        return label == null && type == Type.TEXT && defaultValue == null && !required && choices.isEmpty() && lookupSql == null;
    }

    /**
     * Checks a value typed in the parameter dialog.
     *
     * @return an error message, or null if the value can be used
     */
    @Nullable
    String validate(@Nullable String value) {
        if (CommonUtils.isEmpty(value)) {
            return required ? getDisplayLabel() + " is required." : null;
        }
        try {
            toJdbcValue(value);
            return null;
        } catch (IllegalArgumentException e) {
            return getDisplayLabel() + ": " + e.getMessage();
        }
    }

    /**
     * The value to bind: {@link String}, {@link Integer}/{@link Long}/{@link BigDecimal}, {@link java.sql.Date},
     * {@link java.sql.Timestamp} or {@link Boolean}; null for an empty value (bound as SQL NULL).
     *
     * @throws IllegalArgumentException the value does not fit the type
     */
    @Nullable
    Object toJdbcValue(@Nullable String value) {
        if (CommonUtils.isEmpty(value)) {
            return null;
        }
        String text = value.trim();
        return switch (type) {
            case NUMBER -> {
                BigDecimal number;
                try {
                    number = new BigDecimal(text);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + value + "' is not a number");
                }
                // Integers bind as INTEGER/BIGINT so that integer-only server functions accept them
                if (number.scale() <= 0 || number.stripTrailingZeros().scale() <= 0) {
                    try {
                        long longValue = number.longValueExact();
                        yield longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE ? (Object) (int) longValue : (Object) longValue;
                    } catch (ArithmeticException e) {
                        yield number;
                    }
                }
                yield number;
            }
            case DATE -> {
                try {
                    yield java.sql.Date.valueOf(LocalDate.parse(text));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("'" + value + "' is not a date (yyyy-mm-dd)");
                }
            }
            case DATETIME -> {
                try {
                    yield java.sql.Timestamp.valueOf(LocalDateTime.parse(text, DATE_TIME_FORMAT));
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("'" + value + "' is not a date and time (yyyy-mm-dd hh:mm[:ss])");
                }
            }
            case BOOLEAN -> switch (text.toLowerCase(Locale.ROOT)) {
                case "true", "1", "yes", "y" -> Boolean.TRUE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                case "false", "0", "no", "n" -> Boolean.FALSE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                default -> throw new IllegalArgumentException("'" + value + "' is not true or false");
            };
            default -> value;
        };
    }
}
