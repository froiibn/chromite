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
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPQualifiedObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.erd.*;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.*;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.virtual.DBVObject;
import com.seizia.chromite.model.virtual.DBVUtils;
import org.jkiss.utils.CommonUtils;

import java.util.*;

/**
 * ER 다이어그램의 내용(테이블, 열, 관계)을 DB 메타데이터에서 읽은 것.
 *
 * 설계:
 * <ul>
 *     <li>읽기는 SWT의 "View Diagram"(ERDEditorEmbedded.loadFromDatabase, ui.editors.erd)과 같은 모델 API를 쓴다:
 *     {@link ERDUtils#collectDatabaseTables}(스키마면 모든 테이블·뷰, 테이블이면 그 테이블과 외래 키로 이어진 테이블),
 *     {@link ERDDiagram#fillEntities}(열과 관계, {@link ERDContentProviderDefault}). 뷰는 SWT 기본값처럼 넣고 파티션은 뺀다.
 *     모델 번들 {@code com.seizia.chromite.model.erd}는 SWT·GEF에 의존하지 않는다.</li>
 *     <li>그리는 데 필요한 것(제목, 열 이름·형식·NOT NULL·키 표시, 관계 끝의 카디널리티, 관계 설명)은 여기서 모두 문자열·값으로 복사해 둔다.
 *     화면(EDT)은 그릴 때 DB 메타데이터를 읽지 않는다(지연 로딩이 EDT를 막지 않게).</li>
 *     <li>관계 끝 표시는 SWT 까마귀발 표기(CrowsFootDiagramNotation)와 같은 규칙이다: 외래 키 쪽은 외래 키 열이 고유 인덱스면 1, 아니면 0..n,
 *     기본 키 쪽은 외래 키 열이 NULL을 허용하면 0..1, 아니면 1.</li>
 *     <li>저장한 배치({@link ErdDiagramState})는 SWT와 같은 곳, 곧 루트 객체(스키마 또는 테이블)의 가상 모델 속성
 *     {@value ErdDiagramState#PROP_DIAGRAM_STATE}에서 읽는다. 그래서 두 화면이 같은 배치를 쓴다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-erd.md)
 */
final class ErdDiagramModel {

    private static final Log log = Log.getLog(ErdDiagramModel.class);

    /**
     * Relationship end (crow's foot notation).
     */
    enum End {
        ONE,
        ZERO_OR_ONE,
        ZERO_OR_MANY
    }

    record Column(
        @NotNull String name,
        @NotNull String type,
        boolean required,
        boolean primaryKey,
        boolean foreignKey
    ) {
    }

    /**
     * A table of the diagram. The position and size are diagram coordinates (zoom 1) and change on the EDT only.
     */
    static final class Entity {
        final DBSEntity object;
        final String title;
        final String fullName;
        final boolean view;
        final boolean primary;
        final List<Column> columns;
        /**
         * Key of the table in the saved layout: data source id, parent names (nearest first, as in the .erd format) and name.
         */
        final String dataSourceId;
        final List<String> path;
        @Nullable
        final String qualifiedName;

        double x;
        double y;
        int width;
        int height;
        boolean placed;

        Entity(@NotNull DBSEntity object, @NotNull String title, @NotNull String fullName, boolean view, boolean primary,
               @NotNull List<Column> columns, @NotNull String dataSourceId, @NotNull List<String> path, @Nullable String qualifiedName) {
            this.object = object;
            this.title = title;
            this.fullName = fullName;
            this.view = view;
            this.primary = primary;
            this.columns = columns;
            this.dataSourceId = dataSourceId;
            this.path = path;
            this.qualifiedName = qualifiedName;
        }

        @NotNull
        String getLayoutKey() {
            return layoutKey(dataSourceId, path, object.getName());
        }

        double centerX() {
            return x + width / 2.0;
        }

        double centerY() {
            return y + height / 2.0;
        }
    }

    /**
     * A foreign key: from the referencing table (source, foreign key side) to the referenced one (target, key side).
     */
    record Link(
        @NotNull Entity source,
        @NotNull Entity target,
        @NotNull String name,
        @Nullable String qualifiedName,
        @NotNull String typeId,
        @NotNull List<String> sourceColumns,
        @NotNull List<String> targetColumns,
        @NotNull End sourceEnd,
        @NotNull End targetEnd
    ) {
    }

    final DBSObject root;
    final List<Entity> entities;
    final List<Link> links;
    final List<String> errors;
    /**
     * The saved layout (serialized .erd XML) of the root object, or null.
     */
    @Nullable
    final String savedState;

    private ErdDiagramModel(@NotNull DBSObject root, @NotNull List<Entity> entities, @NotNull List<Link> links,
                            @NotNull List<String> errors, @Nullable String savedState) {
        this.root = root;
        this.entities = entities;
        this.links = links;
        this.errors = errors;
        this.savedState = savedState;
    }

    /**
     * Whether "View Diagram" applies to an object: a table or view, or a container of tables (schema, or the catalog of
     * databases without schemas).
     */
    static boolean canShow(@Nullable DBSObject object) {
        if (object instanceof DBSEntity) {
            return true;
        }
        if (!(object instanceof DBSObjectContainer container) || object instanceof DBPDataSource) {
            return false;
        }
        try {
            // Only reads the class of the children (no metadata query)
            Class<? extends DBSObject> childType = container.getPrimaryChildType(new VoidProgressMonitor());
            return childType != null && DBSEntity.class.isAssignableFrom(childType);
        } catch (DBException e) {
            log.debug("Can't detect child type of " + object.getName(), e);
            return false;
        }
    }

    /**
     * Reads the diagram of a schema (its tables) or of a table (with the tables related by foreign keys).
     * Reads database metadata: call in a job.
     */
    @NotNull
    static ErdDiagramModel load(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject root) throws DBException {
        ERDDiagram diagram = new ERDDiagram(root, root.getName(), new ERDContentProviderDefault());
        Collection<DBSEntity> tables = ERDUtils.collectDatabaseTables(monitor, root, diagram, true, false);
        diagram.fillEntities(monitor, tables, root);

        Map<ERDEntity, Entity> entityMap = new IdentityHashMap<>();
        List<Entity> entities = new ArrayList<>();
        for (ERDEntity erdEntity : diagram.getEntities()) {
            if (monitor.isCanceled()) {
                break;
            }
            Entity entity = makeEntity(erdEntity);
            entityMap.put(erdEntity, entity);
            entities.add(entity);
        }
        // Stable order: the layout and the saved file do not depend on the order the database returned
        entities.sort(Comparator.comparing((Entity e) -> e.title, String.CASE_INSENSITIVE_ORDER).thenComparing(e -> e.title));

        List<Link> links = new ArrayList<>();
        for (ERDEntity erdEntity : diagram.getEntities()) {
            for (ERDAssociation association : erdEntity.getAssociations()) {
                if (monitor.isCanceled()) {
                    break;
                }
                Entity source = entityMap.get(association.getSourceEntity());
                Entity target = entityMap.get(association.getTargetEntity());
                if (source == null || target == null) {
                    continue;
                }
                links.add(makeLink(monitor, association, source, target));
            }
        }
        links.sort(Comparator.comparing((Link l) -> l.source().title).thenComparing(Link::name));

        return new ErdDiagramModel(root, entities, links, new ArrayList<>(diagram.getErrorMessages()), ErdDiagramState.read(root));
    }

    @NotNull
    private static Entity makeEntity(@NotNull ERDEntity erdEntity) {
        DBSEntity table = erdEntity.getObject();
        List<Column> columns = new ArrayList<>();
        for (ERDEntityAttribute attribute : erdEntity.getAttributes()) {
            DBSEntityAttribute object = attribute.getObject();
            columns.add(new Column(
                attribute.getName(),
                CommonUtils.notEmpty(object.getFullTypeName()),
                object.isRequired(),
                attribute.isInPrimaryKey(),
                attribute.isInForeignKey()));
        }
        DBSObject parent = table.getParentObject();
        String title = parent instanceof DBSObjectContainer && !(parent instanceof DBPDataSource)
            ? parent.getName() + "." + table.getName() : table.getName(); //$NON-NLS-1$

        DBPDataSourceContainer dsContainer = table.getDataSource().getContainer();
        // Same parent path as the SWT diagram file (DiagramLoader.serializeDiagram): up to the data source's public container
        List<String> path = new ArrayList<>();
        for (DBSObject p = table.getParentObject(); p != null && DBUtils.getPublicObjectContainer(p) != dsContainer; p = p.getParentObject()) {
            path.add(p.getName());
        }
        String qualifiedName = table instanceof DBPQualifiedObject qualified ? qualified.getFullyQualifiedName(DBPEvaluationContext.UI) : null;
        return new Entity(
            table,
            title,
            DBUtils.getObjectFullName(table, DBPEvaluationContext.UI),
            DBUtils.isView(table),
            erdEntity.isPrimary(),
            columns,
            dsContainer.getId(),
            path,
            qualifiedName);
    }

    @NotNull
    private static Link makeLink(@NotNull DBRProgressMonitor monitor, @NotNull ERDAssociation association,
                                 @NotNull Entity source, @NotNull Entity target) {
        DBSEntityAssociation fk = association.getObject();
        List<String> sourceColumns = association.getSourceAttributes().stream().map(ERDEntityAttribute::getName).toList();
        List<String> targetColumns = association.getTargetAttributes().stream().map(ERDEntityAttribute::getName).toList();
        End sourceEnd = End.ZERO_OR_MANY;
        End targetEnd = End.ONE;
        DBSEntityConstraintType constraintType = fk.getConstraintType();
        if (constraintType == DBSEntityConstraintType.PRIMARY_KEY) {
            // CrowsFootDiagramNotation: a key-to-key association is 0..1 on the source side
            sourceEnd = End.ZERO_OR_ONE;
        } else {
            try {
                if (source.object instanceof DBSTable && !association.getSourceAttributes().isEmpty()) {
                    List<DBSEntityAttribute> attributes = association.getSourceAttributes().stream().map(ERDEntityAttribute::getObject).toList();
                    if (DBUtils.isUniqueIndexForAttributes(monitor, attributes, source.object)) {
                        sourceEnd = End.ONE;
                    }
                }
                if (DBUtils.isOptionalAssociation(monitor, fk)) {
                    targetEnd = End.ZERO_OR_ONE;
                }
            } catch (DBException e) {
                log.debug("Can't read the cardinality of " + fk.getName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String qualifiedName = fk instanceof DBPQualifiedObject qualified ? qualified.getFullyQualifiedName(DBPEvaluationContext.UI) : null;
        return new Link(source, target, fk.getName(), qualifiedName, constraintType.getId(), sourceColumns, targetColumns,
            sourceEnd, targetEnd);
    }

    @NotNull
    static String layoutKey(@NotNull String dataSourceId, @NotNull List<String> path, @NotNull String name) {
        StringBuilder key = new StringBuilder(dataSourceId);
        for (String part : path) {
            key.append('\n').append(part);
        }
        return key.append('\n').append(name).toString();
    }

    /**
     * The virtual model object that keeps the saved layout of a diagram root (as the SWT embedded diagram).
     */
    @Nullable
    static DBVObject getVirtualObject(@NotNull DBSObject root, boolean create) {
        return root instanceof DBSEntity entity ? DBVUtils.getVirtualEntity(entity, create) : DBVUtils.getVirtualObject(root, create);
    }
}
