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
package com.seizia.chromite.registry;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.csv.CSVParser;
import org.jkiss.utils.csv.CSVReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads connections from a CSV file with a header row.
 *
 * 설계:
 * <ul>
 *     <li>열은 머리글 이름으로 찾는다(대소문자, 앞뒤 공백 무시, 순서 자유): {@code name}(필수), {@code driver}(또는 {@code type}),
 *     {@code host}, {@code port}, {@code database}, {@code user}. 있으면 쓰는 열: {@code url}, {@code folder}(또는 {@code group}),
 *     {@code driver_class}, {@code password}. 모르는 열은 무시한다.</li>
 *     <li>{@code driver}는 DB 종류 이름이나 Chromite 드라이버 ID다. 드라이버 고르기는 {@link ForeignConnectionImport}가 한다.</li>
 *     <li>구분 기호는 머리글 줄로 정한다: 쉼표가 있으면 쉼표, 없고 세미콜론이나 탭이 있으면 그것. 표 계산 프로그램이 지역 설정에 따라
 *     세미콜론으로 저장하는 경우를 받기 위해서다.</li>
 *     <li>인코딩은 UTF-8(BOM 무시)이다. UTF-8로 읽을 수 없는 파일은 이 PC의 기본 인코딩으로 읽는다(한국어 Windows의 표 계산
 *     프로그램이 CP949로 저장한 파일).</li>
 *     <li>{@code password} 열은 SWT 사용자 정의 가져오기(삭제된 {@code ConfigImportWizardPageCustomConnections})처럼 평문 값을
 *     그대로 받는다. 사용자가 직접 만든 파일이라 다른 도구의 암호화가 없다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-transfer.md "다른 도구에서 가져오기")
 */
public final class CsvConnectionReader implements ForeignConnectionReader {

    public static final String COLUMN_NAME = "name"; //$NON-NLS-1$
    public static final String COLUMN_DRIVER = "driver"; //$NON-NLS-1$
    public static final String COLUMN_TYPE = "type"; //$NON-NLS-1$
    public static final String COLUMN_HOST = "host"; //$NON-NLS-1$
    public static final String COLUMN_PORT = "port"; //$NON-NLS-1$
    public static final String COLUMN_DATABASE = "database"; //$NON-NLS-1$
    public static final String COLUMN_USER = "user"; //$NON-NLS-1$
    public static final String COLUMN_PASSWORD = "password"; //$NON-NLS-1$
    public static final String COLUMN_URL = "url"; //$NON-NLS-1$
    public static final String COLUMN_FOLDER = "folder"; //$NON-NLS-1$
    public static final String COLUMN_GROUP = "group"; //$NON-NLS-1$
    public static final String COLUMN_DRIVER_CLASS = "driver_class"; //$NON-NLS-1$

    private static final char BOM = '﻿';

    @NotNull
    @Override
    public List<ForeignConnection> read(@NotNull Path file) throws DBException {
        String text = readText(file);
        if (!text.isEmpty() && text.charAt(0) == BOM) {
            text = text.substring(1);
        }
        int lineEnd = text.indexOf('\n');
        String headerLine = lineEnd < 0 ? text : text.substring(0, lineEnd);
        String separator = CSVParser.DEFAULT_SEPARATOR;
        if (!headerLine.contains(separator)) {
            if (headerLine.contains(";")) { //$NON-NLS-1$
                separator = ";"; //$NON-NLS-1$
            } else if (headerLine.contains("\t")) { //$NON-NLS-1$
                separator = "\t"; //$NON-NLS-1$
            }
        }

        List<ForeignConnection> result = new ArrayList<>();
        // No backslash escape (RFC 4180: a quote inside a quoted value is doubled), so Windows paths and passwords keep their backslashes
        try (CSVReader reader = new CSVReader(new StringReader(text), separator,
            CSVParser.DEFAULT_QUOTE_CHARACTER, CSVParser.NULL_CHARACTER)) {
            String[] header = reader.readNext();
            Map<String, Integer> columns = new HashMap<>();
            if (header != null) {
                for (int i = 0; i < header.length; i++) {
                    columns.putIfAbsent(CommonUtils.notEmpty(header[i]).trim().toLowerCase(Locale.ENGLISH), i);
                }
            }
            if (!columns.containsKey(COLUMN_NAME)) {
                throw new DBException("The CSV file has no '" + COLUMN_NAME + "' column in its first row");
            }
            for (String[] row = reader.readNext(); row != null; row = reader.readNext()) {
                String name = getValue(row, columns, COLUMN_NAME);
                if (name == null) {
                    // Empty lines and rows without a name
                    continue;
                }
                ForeignConnection connection = new ForeignConnection(name);
                String type = getValue(row, columns, COLUMN_DRIVER);
                connection.setType(type != null ? type : getValue(row, columns, COLUMN_TYPE));
                connection.setDriverClass(getValue(row, columns, COLUMN_DRIVER_CLASS));
                connection.setUrl(getValue(row, columns, COLUMN_URL));
                connection.setHost(getValue(row, columns, COLUMN_HOST));
                connection.setPort(getValue(row, columns, COLUMN_PORT));
                connection.setDatabase(getValue(row, columns, COLUMN_DATABASE));
                connection.setUser(getValue(row, columns, COLUMN_USER));
                connection.setPassword(getValue(row, columns, COLUMN_PASSWORD));
                String folder = getValue(row, columns, COLUMN_FOLDER);
                connection.setFolderPath(folder != null ? folder : getValue(row, columns, COLUMN_GROUP));
                result.add(connection);
            }
        } catch (IOException e) {
            throw new DBException("Cannot read the CSV file " + file, e);
        }
        return result;
    }

    @Nullable
    private static String getValue(@NotNull String[] row, @NotNull Map<String, Integer> columns, @NotNull String column) {
        Integer index = columns.get(column);
        if (index == null || index >= row.length) {
            return null;
        }
        return CommonUtils.nullIfEmpty(CommonUtils.notEmpty(row[index]).trim());
    }

    @NotNull
    private static String readText(@NotNull Path file) throws DBException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new DBException("Cannot read " + file, e);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            // Not UTF-8: a spreadsheet saved it in the local code page
            return new String(bytes, Charset.defaultCharset());
        }
    }
}
