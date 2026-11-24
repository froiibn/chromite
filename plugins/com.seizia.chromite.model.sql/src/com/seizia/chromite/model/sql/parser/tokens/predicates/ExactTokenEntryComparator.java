/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.sql.parser.tokens.predicates;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.sql.parser.TokenEntry;

import java.util.Comparator;

/**
 * Strong comparator implementing foll comparison of data carried by two token entries.
 * Establishes strong ordering on the continuity of possible token entries.
 *
 * 설계: an inverted entry ("not EXISTS") and the plain entry with the same type and text ("EXISTS") are different keys of the
 * predicate tries (TokenPredicateSet). The inversion is compared last, so the order by type stays the first part of the order, as
 * TokenEntryMatchingComparator needs.
 *
 * 변경 이력:
 * - 2026-11-25 Chromite: compare the inversion too. The two entries were one trie node, so a condition ending in EXISTS was filed
 *   under the Oracle IF rule's "not EXISTS" node: it never matched at EXISTS and matched after any other word instead
 *   (docs/chromite/swing-query-commands.md "Oracle 스크립트 나누기 2").
 */
class ExactTokenEntryComparator extends TokenEntryComparatorBase implements Comparator<TokenEntry> {
    public static final ExactTokenEntryComparator INSTANCE = new ExactTokenEntryComparator();

    private ExactTokenEntryComparator() {

    }

    @Override
    public int compare(@NotNull TokenEntry first, @NotNull TokenEntry second) {
        // keep in sync with TokenEntryMatchingComparator implementation: look at the partially comparable part of the data at first!
        int result = compareByTokenTypes(first, second);
        if (result != 0) {
            return result;
        }
        result = compareByStrings(first, second);
        if (result != 0) {
            return result;
        }
        return Boolean.compare(first.isInverted(), second.isInverted());
    }
}
