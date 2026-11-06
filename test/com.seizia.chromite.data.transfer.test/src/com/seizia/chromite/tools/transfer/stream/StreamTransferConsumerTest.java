/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.tools.transfer.stream;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourceInfo;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCResultSetMetaData;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.tools.transfer.IDataTransferConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

class StreamTransferConsumerTest {

    @Test
    void retryAfterFailedTransferInitializesNewExporterAndHeader() throws Exception {
        StreamConsumerSettings settings = new StreamConsumerSettings();
        settings.setOutputClipboard(true);
        StreamTransferConsumer consumer = new StreamTransferConsumer();
        DBSDataContainer source = Mockito.mock(DBSDataContainer.class);
        Mockito.when(source.getName()).thenReturn("source");
        DBCSession session = Mockito.mock(DBCSession.class);
        DBPDataSource dataSource = Mockito.mock(DBPDataSource.class);
        Mockito.when(dataSource.getContainer()).thenReturn(Mockito.mock(DBPDataSourceContainer.class));
        Mockito.when(dataSource.getInfo()).thenReturn(Mockito.mock(DBPDataSourceInfo.class));
        Mockito.when(session.getDataSource()).thenReturn(dataSource);
        Mockito.when(session.getProgressMonitor()).thenReturn(new VoidProgressMonitor());
        DBCResultSet resultSet = Mockito.mock(DBCResultSet.class);
        DBCResultSetMetaData metaData = Mockito.mock(DBCResultSetMetaData.class);
        Mockito.when(resultSet.getMeta()).thenReturn(metaData);
        Mockito.when(metaData.getAttributes()).thenReturn(List.of());

        IStreamDataExporter firstExporter = Mockito.mock(IStreamDataExporter.class);
        IDataTransferConsumer.TransferParameters parameters = new IDataTransferConsumer.TransferParameters(false, false);
        consumer.initTransfer(source, settings, parameters, firstExporter, Map.of(), null);
        consumer.fetchStart(session, resultSet, 0, 0);
        consumer.finishTransfer(new VoidProgressMonitor(), new DBException("Invalid query"), null, false);

        IStreamDataExporter retryExporter = Mockito.mock(IStreamDataExporter.class);
        consumer.initTransfer(source, settings, parameters, retryExporter, Map.of(), null);
        consumer.fetchStart(session, resultSet, 0, 0);

        Mockito.verify(firstExporter).dispose();
        Mockito.verify(retryExporter).init(Mockito.any());
        Mockito.verify(retryExporter).exportHeader(session);
    }
}
