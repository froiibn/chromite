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
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDReferenceNavigation;
import com.seizia.chromite.model.data.DBDReferenceUtils;
import com.seizia.chromite.model.data.DBDResultSetModel;
import com.seizia.chromite.model.data.DBDRowIdentifier;
import com.seizia.chromite.model.data.DBDValueRow;
import com.seizia.chromite.model.data.ResultSetValuePath;
import com.seizia.chromite.model.data.hints.DBDValueHintContext;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.impl.data.ResultSetHintContext;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAssociation;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityAttributeRef;
import com.seizia.chromite.model.struct.DBSEntityConstraint;
import com.seizia.chromite.model.struct.DBSEntityReferrer;
import com.seizia.chromite.model.struct.DBStructUtils;
import com.seizia.chromite.model.virtual.DBVEntity;
import com.seizia.chromite.model.virtual.DBVUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 결과의 외래 키 관계(참조하는 테이블과 참조되는 테이블)와 그 행으로 가는 필터. 결과 뷰어의 외래 키 따라가기, 참조 패널이 쓴다.
 *
 * 설계:
 * <ul>
 *     <li>관계는 SWT처럼 모델에서 읽는다. 앞으로(이 결과의 열이 다른 테이블을 가리킴)는 {@link DBVUtils#getAllAssociations}(실제 외래 키와 가상 외래 키),
 *     뒤로(다른 테이블이 이 결과의 키를 가리킴)는 {@link DBStructUtils#readReferences}({@link DBVUtils#getAllReferences}: 실제 참조와
 *     다른 테이블의 가상 외래 키)다. 관계의 열이 모두 결과에 있어야 따라갈 수 있다(SWT {@code ResultSetReferenceMenu}, {@code ReferencesResultsContainer}와 같은 조건).</li>
 *     <li>대상 행의 필터는 모델의 {@link DBDReferenceUtils}가 만든다(한 행이면 {@code =}, 여러 행이면 {@code IN}). 이 클래스는 행 값을 주는
 *     {@link DBDResultSetModel}만 만든다: 편집할 수 있는 결과는 편집 모델, 읽기 전용 결과는 {@link RowsModel}.</li>
 *     <li>칸 하나의 외래 키({@link #getCellAssociations})는 열 바인딩이 이미 아는 것({@link DBDAttributeBinding#getReferrers}, 결과를 받을 때
 *     {@code lateBinding}이 읽은 실제·가상 외래 키)이라 DB를 읽지 않는다. 나머지는 모두 Job 스레드에서 부른다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 외래 키 따라가기, 참조 패널 (docs/chromite/swing-grid-navigation.md)
 */
final class ResultSetReferences {

    private static final Log log = Log.getLog(ResultSetReferences.class);

    /**
     * A relation of the result's table to another table.
     *
     * @param association the foreign key (real or virtual)
     * @param referencing true: the other table references this result (its rows point here); false: this result
     *                    references the other table
     * @param target      the other table, whose rows the navigation reads
     * @param label       "table (key)" for menus
     */
    record Link(
        @NotNull DBSEntityAssociation association,
        boolean referencing,
        @NotNull DBSEntity target,
        @NotNull String label
    ) {
    }

    private ResultSetReferences() {
    }

    /**
     * The foreign keys of a cell's column that bound it to a table (no database access).
     */
    @NotNull
    static List<DBSEntityAssociation> getCellAssociations(@NotNull DBDAttributeBinding binding) {
        List<DBSEntityAssociation> result = new ArrayList<>();
        for (DBSEntityReferrer referrer : CommonUtils.safeList(binding.getReferrers())) {
            if (referrer instanceof DBSEntityAssociation association) {
                result.add(association);
            }
        }
        return result;
    }

    /**
     * The tables this result references and the tables that reference it, through keys whose columns are all in the
     * result (job thread).
     */
    @NotNull
    static List<Link> readLinks(@NotNull DBRProgressMonitor monitor, @NotNull DBDAttributeBinding[] bindings) throws DBException {
        Set<DBSEntity> entities = new LinkedHashSet<>();
        for (DBDAttributeBinding binding : bindings) {
            DBSEntityAttribute attribute = binding.getEntityAttribute();
            if (attribute != null && !(attribute.getParentObject() instanceof DBVEntity)) {
                entities.add(attribute.getParentObject());
            }
        }
        List<Link> associations = new ArrayList<>();
        List<Link> references = new ArrayList<>();
        for (DBSEntity entity : entities) {
            if (monitor.isCanceled()) {
                break;
            }
            for (DBSEntityAssociation association : DBVUtils.getAllAssociations(monitor, entity)) {
                if (association instanceof DBSEntityReferrer referrer && association.getReferencedConstraint() != null
                    && allBound(monitor, referrer, bindings)) {
                    DBSEntity target = association.getReferencedConstraint().getParentObject();
                    if (target != null) {
                        associations.add(new Link(association, false, target, describe(target, association)));
                    }
                }
            }
            for (DBSEntityAssociation reference : DBStructUtils.readReferences(monitor, entity, attribute -> DBUtils.findBinding(bindings, attribute))) {
                DBSEntity source = reference.getParentObject();
                if (source != null) {
                    references.add(new Link(reference, true, source, describe(source, reference)));
                }
            }
        }
        associations.sort(Comparator.comparing(Link::label, String.CASE_INSENSITIVE_ORDER));
        references.sort(Comparator.comparing(Link::label, String.CASE_INSENSITIVE_ORDER));
        List<Link> links = new ArrayList<>(associations);
        links.addAll(references);
        return links;
    }

    /**
     * The table and the filter of the rows a link leads to from the given rows (job thread).
     */
    @NotNull
    static DBDReferenceNavigation resolve(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntityAssociation association,
        boolean referencing,
        @NotNull DBDResultSetModel model,
        @NotNull List<? extends DBDValueRow> rows
    ) throws DBException {
        return referencing
            ? DBDReferenceUtils.resolveReferenceNavigation(monitor, model, association, rows)
            : DBDReferenceUtils.resolveAssociationNavigation(monitor, model, association, rows);
    }

    /**
     * "table (key)", the table's name qualified as the UI shows it.
     */
    @NotNull
    static String describe(@NotNull DBSEntity table, @NotNull DBSEntityConstraint key) {
        return DBUtils.getObjectFullName(table, DBPEvaluationContext.UI) + " (" + key.getName() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether the result has a column for every column of the key.
     */
    private static boolean allBound(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntityReferrer referrer,
        @NotNull DBDAttributeBinding[] bindings
    ) {
        try {
            List<? extends DBSEntityAttributeRef> references = referrer.getAttributeReferences(monitor);
            if (CommonUtils.isEmpty(references)) {
                return false;
            }
            for (DBSEntityAttributeRef reference : references) {
                if (reference.getAttribute() == null || DBUtils.findBinding(bindings, reference.getAttribute()) == null) {
                    return false;
                }
            }
            return true;
        } catch (DBException e) {
            log.debug("Can't read the columns of " + referrer.getName(), e);
            return false;
        }
    }

    /**
     * The rows of a read-only result for the model's navigation filters: its column bindings and the values the
     * grid kept (the cell values of the result viewer).
     */
    static final class RowsModel implements DBDResultSetModel {
        private final DBSDataContainer dataContainer;
        private final DBDAttributeBinding[] bindings;
        private final List<DBDValueRow> rows;
        private final DBDValueHintContext hintContext;

        RowsModel(@NotNull DBSDataContainer dataContainer, @NotNull DBDAttributeBinding[] bindings, @NotNull List<Object[]> values) {
            this.dataContainer = dataContainer;
            this.bindings = bindings;
            this.rows = new ArrayList<>(values.size());
            for (int i = 0; i < values.size(); i++) {
                int number = i;
                Object[] rowValues = values.get(i);
                rows.add(new DBDValueRow() {
                    @Override
                    public int getRowNumber() {
                        return number;
                    }

                    @Override
                    public Object[] getValues() {
                        return rowValues;
                    }
                });
            }
            this.hintContext = new ResultSetHintContext(() -> dataContainer, this::getSingleSource);
        }

        @NotNull
        @Override
        public DBDAttributeBinding[] getAttributes() {
            return bindings;
        }

        @NotNull
        @Override
        public List<DBDAttributeBinding> getVisibleAttributes() {
            return List.of(bindings);
        }

        @NotNull
        @Override
        public List<DBDValueRow> getAllRows() {
            return rows;
        }

        @Nullable
        @Override
        public DBDRowIdentifier getDefaultRowIdentifier() {
            for (DBDAttributeBinding binding : bindings) {
                if (binding.getRowIdentifier() != null) {
                    return binding.getRowIdentifier();
                }
            }
            return null;
        }

        @Nullable
        @Override
        public Object getCellValue(
            @NotNull DBDAttributeBinding attribute,
            @NotNull DBDValueRow row,
            @Nullable int[] rowIndexes,
            @Nullable ResultSetValuePath valuePath,
            boolean retrieveDeepestCollectionElement
        ) {
            return DBUtils.getAttributeValue(attribute, bindings, row.getValues(), rowIndexes, retrieveDeepestCollectionElement);
        }

        @Nullable
        @Override
        public Object getCellValue(@NotNull DBDAttributeBinding attribute, @NotNull DBDValueRow row) {
            return getCellValue(attribute, row, null, null, false);
        }

        @NotNull
        @Override
        public DBDValueHintContext getHintContext() {
            return hintContext;
        }

        @Nullable
        @Override
        public String getReadOnlyStatus(@Nullable DBPDataSourceContainer dataSourceContainer) {
            return null;
        }

        @Nullable
        @Override
        public DBSEntity getSingleSource() {
            return DBExecUtils.detectSingleSourceTable(bindings);
        }
    }
}
