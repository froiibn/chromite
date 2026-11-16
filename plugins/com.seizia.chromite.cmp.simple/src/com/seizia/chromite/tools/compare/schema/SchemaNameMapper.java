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
package com.seizia.chromite.tools.compare.schema;

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names of the target for objects read from the source: definitions with the target's schema and database names,
 * transformed names between database types, and qualified names in the target container.
 *
 * 설계: 원본 정의(뷰·루틴·트리거·시퀀스·인덱스)는 원본 스키마 이름으로 객체를 가리킨다({@code app_v1.orders}). 다른 스키마로
 * 옮기는 비교(예: {@code app_v1} → {@code app_v2})에서 그대로 쓰면 원본 객체를 만들거나 가리키므로, 같은 종류의 DB일 때
 * 스키마·데이터베이스 이름 뒤에 구조 구분자({@code .})가 오는 곳만 대상 이름으로 바꾼다(따옴표 있는 이름은 같은 따옴표로).
 * 비교할 때도 같은 변환을 거쳐, 스키마 이름만 다른 정의는 같다고 본다. 문자열 안이나 스키마와 이름이 같은 별칭도 바뀔 수 있으므로
 * 스크립트는 검토용이다(문서 "알려진 한계").
 * 다른 종류의 DB로는 정의를 옮기지 않으므로 바꾸지 않고, 이름은 대상의 대소문자 규칙({@link DBObjectNameCaseTransformer})을 따른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/schema-compare.md)
 */
public final class SchemaNameMapper {

    private record Replacement(@NotNull Pattern pattern, @NotNull String replacement) {
    }

    private final DBSObjectContainer target;
    private final DBPDataSource targetDataSource;
    private final boolean sameDialect;
    private final List<Replacement> replacements = new ArrayList<>();

    SchemaNameMapper(@NotNull DBSObjectContainer source, @NotNull DBSObjectContainer target, boolean sameDialect) {
        this.target = target;
        this.targetDataSource = target.getDataSource();
        this.sameDialect = sameDialect;
        if (sameDialect) {
            addReplacements(getSelfOrParent(DBSSchema.class, source), getSelfOrParent(DBSSchema.class, target));
            addReplacements(getSelfOrParent(DBSCatalog.class, source), getSelfOrParent(DBSCatalog.class, target));
        }
    }

    private void addReplacements(@Nullable DBSObject sourceObject, @Nullable DBSObject targetObject) {
        if (sourceObject == null || targetObject == null || sourceObject.getName().equals(targetObject.getName())) {
            return;
        }
        String sourceName = sourceObject.getName();
        String targetName = targetObject.getName();
        SQLDialect dialect = targetDataSource.getSQLDialect();
        String separator = Pattern.quote(String.valueOf(dialect.getStructSeparator()));
        String[][] quotes = dialect.getIdentifierQuoteStrings();
        if (quotes != null) {
            for (String[] pair : quotes) {
                if (pair.length == 2 && !CommonUtils.isEmpty(pair[0]) && !CommonUtils.isEmpty(pair[1])) {
                    replacements.add(new Replacement(
                        Pattern.compile(Pattern.quote(pair[0] + sourceName + pair[1]) + "(?=\\s*" + separator + ")"), //$NON-NLS-1$ //$NON-NLS-2$
                        Matcher.quoteReplacement(pair[0] + targetName + pair[1])));
                }
            }
        }
        // Unquoted: a whole identifier (no identifier character or quote before it), case-insensitive as unquoted names are
        replacements.add(new Replacement(
            Pattern.compile("(?<![\\w$#@\"`\\[\\]])" + Pattern.quote(sourceName) + "(?=\\s*" + separator + ")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Matcher.quoteReplacement(DBUtils.getQuotedIdentifier(targetDataSource, targetName))));
    }

    @Nullable
    private static <T> T getSelfOrParent(@NotNull Class<T> type, @NotNull DBSObject object) {
        return type.isInstance(object) ? type.cast(object) : DBUtils.getParentOfType(type, object);
    }

    /**
     * A source definition with the target's schema and database names (unchanged between different database types).
     */
    @NotNull
    public String remap(@NotNull String sourceText) {
        String text = sourceText;
        for (Replacement replacement : replacements) {
            text = replacement.pattern().matcher(text).replaceAll(replacement.replacement());
        }
        return text;
    }

    /**
     * The name a new target object gets for a source name: the same name in the same database type, else the name in
     * the target's identifier case (as the data transfer names new tables).
     */
    @NotNull
    public String getTargetName(@NotNull String sourceName) {
        if (sameDialect) {
            return sourceName;
        }
        String transformed = DBObjectNameCaseTransformer.transformName(targetDataSource, sourceName);
        return CommonUtils.isEmpty(transformed) ? sourceName : transformed;
    }

    /**
     * The quoted, qualified name of an object named {@code targetName} in the target container.
     */
    @NotNull
    public String qualify(@NotNull String targetName) {
        String quoted = DBUtils.getQuotedIdentifier(targetDataSource, targetName);
        if (target instanceof DBPDataSource) {
            return quoted;
        }
        return DBUtils.getObjectFullName(target, DBPEvaluationContext.DDL) + targetDataSource.getSQLDialect().getStructSeparator() + quoted;
    }
}
