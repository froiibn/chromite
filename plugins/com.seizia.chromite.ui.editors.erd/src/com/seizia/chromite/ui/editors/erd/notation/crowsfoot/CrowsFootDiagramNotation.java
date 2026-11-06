/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ui.editors.erd.notation.crowsfoot;

import org.eclipse.draw2d.ConnectionEndpointLocator;
import org.eclipse.draw2d.PolylineConnection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.erd.ERDAssociation;
import com.seizia.chromite.model.erd.ERDEntity;
import com.seizia.chromite.model.erd.ERDEntityAttribute;
import com.seizia.chromite.model.erd.ERDUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSTableIndex;
import com.seizia.chromite.ui.editors.erd.notations.ERDAssociationType;
import com.seizia.chromite.ui.editors.erd.notations.ERDNotation;
import com.seizia.chromite.ui.editors.erd.notations.ERDNotationBase;
import org.jkiss.utils.CommonUtils;

import java.util.Collection;
import java.util.List;

public class CrowsFootDiagramNotation extends ERDNotationBase implements ERDNotation {

    private static final Log log = Log.getLog(CrowsFootDiagramNotation.class);

    @Override
    public void applyNotationForArrows(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PolylineConnection conn,
        @NotNull ERDAssociation association,
        @NotNull Color bckColor,
        @NotNull Color frgColor
    ) {
        DBSEntityConstraintType constraintType = association.getObject().getConstraintType();
        if (constraintType == DBSEntityConstraintType.PRIMARY_KEY) {
            // source 0..1
            createSourceDecorator(conn, bckColor, frgColor, ERDAssociationType.ZERO_OR_ONE, LABEL_0_TO_1);
        } else if (constraintType.isAssociation() &&
            association.getSourceEntity() instanceof ERDEntity src &&
            association.getTargetEntity() instanceof ERDEntity trg) {
            // source - 1..n
            try {
                DBSEntity entity = src.getObject();
                Collection<? extends DBSTableIndex> indexes = ((DBSTable) entity).getIndexes(monitor);
                if (!CommonUtils.isEmpty(indexes)) {
                    // get index for require source attributes
                    List<ERDEntityAttribute> erdSourceAttributes = association.getSourceAttributes();
                    List<DBSEntityAttribute> attributes = erdSourceAttributes.stream()
                        .map(ERDEntityAttribute::getObject)
                        .toList();
                    if (DBUtils.isUniqueIndexForAttributes(monitor, attributes, entity)) {
                        createSourceDecorator(conn, bckColor, frgColor, ERDAssociationType.ONE_ONLY, LABEL_1);
                    } else {
                        createSourceDecorator(conn, bckColor, frgColor, ERDAssociationType.ZERO_OR_MANY, LABEL_0_TO_N);
                    }
                } else {
                    createSourceDecorator(conn, bckColor, frgColor, ERDAssociationType.ZERO_OR_MANY, LABEL_0_TO_N);
                }
                if (ERDUtils.isOptionalAssociation(association)) {
                    // target - 0..1
                    createTargetDecorator(conn, bckColor, frgColor, ERDAssociationType.ZERO_OR_ONE, LABEL_0_TO_1);
                } else {
                    // target - 1
                    createTargetDecorator(conn, bckColor, frgColor, ERDAssociationType.ONE_ONLY, LABEL_1);
                }
            } catch (DBException e) {
                log.error(e.getMessage(), e);
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
                /* Clean up whatever needs to be handled before interrupting */
                Thread.currentThread().interrupt();
            }
        }
        conn.setLineWidth(1);
        conn.setLineStyle(SWT.LINE_CUSTOM);
    }

    private void createSourceDecorator(PolylineConnection conn, Color bckColor, Color frgColor, ERDAssociationType type, String label) {
        CrowsFootPolylineDecoration sourceDecor;
        sourceDecor = new CrowsFootPolylineDecoration(type);
        sourceDecor.setFill(true);
        sourceDecor.setBackgroundColor(bckColor);
        conn.setSourceDecoration(sourceDecor);
        ConnectionEndpointLocator srcEndpointLocator = new ConnectionEndpointLocator(conn, false);
        srcEndpointLocator.setVDistance(LBL_V_DISTANCE);
        srcEndpointLocator.setUDistance(LBL_U_DISTANCE);
        conn.add(getLabel(label, frgColor), srcEndpointLocator);
    }

    private void createTargetDecorator(PolylineConnection conn, Color bckColor, Color frgColor, ERDAssociationType type, String label) {
        final CrowsFootPolylineDecoration targetDecor = new CrowsFootPolylineDecoration(type);
        targetDecor.setFill(true);
        targetDecor.setBackgroundColor(bckColor);
        ConnectionEndpointLocator trgEndpointLocator = new ConnectionEndpointLocator(conn, true);
        trgEndpointLocator.setVDistance(LBL_V_DISTANCE);
        trgEndpointLocator.setUDistance(LBL_U_DISTANCE);
        conn.add(getLabel(label, frgColor), trgEndpointLocator);
        conn.setTargetDecoration(targetDecor);
    }

    @Override
    public void applyNotationForEntities(
        @NotNull PolylineConnection conn,
        @NotNull ERDAssociation association,
        @NotNull Color bckColor,
        @NotNull Color frgColor
    ) {
        // nothing
    }

    @Override
    public double getIndentation() {
        return 30;
    }

}
