/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.model.data.hints.standard;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDResultSetModel;
import com.seizia.chromite.model.data.DBDRowIdentifier;
import com.seizia.chromite.model.data.hints.DBDAttributeHintProvider;
import com.seizia.chromite.model.data.hints.DBDValueHint;
import com.seizia.chromite.model.data.hints.ValueHintText;
import com.seizia.chromite.model.data.messages.DataMessages;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAssociation;
import com.seizia.chromite.model.struct.DBSEntityReferrer;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Attribute keys hint provider
 */
public class AttributeKeysHintProvider implements DBDAttributeHintProvider {

    @Nullable
    @Override
    public DBDValueHint[] getAttributeHints(
        @NotNull DBDResultSetModel model,
        @NotNull DBDAttributeBinding attribute,
        @NotNull EnumSet<DBDValueHint.HintType> types,
        int options
    ) {
        List<DBDValueHint> hints = new ArrayList<>();

        DBDRowIdentifier rowIdentifier = attribute.getRowIdentifier();

        if (rowIdentifier != null &&
            !rowIdentifier.isIncomplete() &&
            rowIdentifier != model.getDefaultRowIdentifier()
        ) {
            hints.add(
                new ValueHintText(
                    DataMessages.hint_attr_keys_unique_key + ": " +
                        rowIdentifier.getEntity().getName() +
                        "(" +
                        rowIdentifier.getAttributes().stream().map(DBDAttributeBinding::getName)
                            .collect(Collectors.joining(",")) +
                        ")",
                    "Unique key which will be used to edit this column's value",
                    null));
        }

        if (rowIdentifier != null && rowIdentifier.hasAttribute(attribute)) {
            hints.add(
                new ValueHintText(
                    DataMessages.hint_attr_keys_part_of + ": " +
                        DBUtils.getObjectFullName(rowIdentifier.getUniqueKey(), DBPEvaluationContext.UI),
                    null,
                    DBIcon.OVER_KEY));
        }
        if (!CommonUtils.isEmpty(attribute.getReferrers())) {
            hints.add(
                new ValueHintText(
                    DataMessages.hint_attr_keys_refers_to + ": " + getRefTableNames(attribute.getReferrers()),
                    null,
                    DBIcon.OVER_REFERENCE));
        }
        return hints.toArray(new DBDValueHint[0]);
    }

    private String getRefTableNames(List<DBSEntityReferrer> referrers) {
        return referrers.stream()
            .map(r -> {
                if (r instanceof DBSEntityAssociation assoc) {
                    DBSEntity entity = assoc.getAssociatedEntity();
                    if (entity != null) {
                        return DBUtils.getObjectFullName(entity, DBPEvaluationContext.UI)
                        /* + "(" +
                           r.getAttributeReferences(null).stream()
                           .map(ar -> ar.getAttribute().getName())+ ")"*/;
                    }
                }
                return null;
            })
            .filter(Objects::nonNull)
            .collect(Collectors.joining(","));
    }
}
