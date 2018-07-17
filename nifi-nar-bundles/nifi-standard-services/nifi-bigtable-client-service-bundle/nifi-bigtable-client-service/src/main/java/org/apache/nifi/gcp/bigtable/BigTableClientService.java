/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.gcp.bigtable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.ParseFilter;
import org.apache.hadoop.hbase.security.visibility.Authorizations;
import org.apache.hadoop.hbase.security.visibility.CellVisibility;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnDisabled;
import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.hbase.DeleteRequest;
import org.apache.nifi.hbase.HBaseClientService;
import org.apache.nifi.hbase.put.PutColumn;
import org.apache.nifi.hbase.put.PutFlowFile;
import org.apache.nifi.hbase.scan.Column;
import org.apache.nifi.hbase.scan.ResultCell;
import org.apache.nifi.hbase.scan.ResultHandler;
import org.apache.nifi.reporting.InitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.cloud.bigtable.hbase.BigtableConfiguration;

@Tags({ "hbase", "client", "google", "cloud", "table", "big", "bigtable"})
@CapabilityDescription("Implementation of HBaseClientService for Google Cloud Big Table")
public class BigTableClientService extends AbstractControllerService implements HBaseClientService {

    private static final Logger logger = LoggerFactory.getLogger(BigTableClientService.class);

    private volatile Connection connection;
    private volatile String masterAddress;
    private List<PropertyDescriptor> properties;

    protected Connection getConnection() {
        return connection;
    }

    protected void setConnection(Connection connection) {
        this.connection = connection;
    }

    @Override
    protected void init(ControllerServiceInitializationContext config) throws InitializationException {

        List<PropertyDescriptor> props = new ArrayList<>();
        // TODO
        this.properties = Collections.unmodifiableList(props);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) throws InitializationException, IOException, InterruptedException {
        this.connection = BigtableConfiguration.connect("", ""); // TODO

        if (this.connection != null) {
            final Admin admin = this.connection.getAdmin();
            if (admin != null) {
                admin.listTableNames();
                masterAddress = admin.getClusterStatus().getMaster().getHostAndPort();
            }
        }
    }

    @OnDisabled
    public void shutdown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (final IOException ioe) {
                getLogger().warn("Failed to close connection to BigTable due to {}", new Object[]{ioe});
            }
        }
    }

    private List<Put> buildPuts(byte[] rowKey, List<PutColumn> columns) {
        List<Put> retVal = new ArrayList<>();

        try {
            Put put = null;

            for (final PutColumn column : columns) {
                if (put == null || (put.getCellVisibility() == null && column.getVisibility() != null) || ( put.getCellVisibility() != null
                        && !put.getCellVisibility().getExpression().equals(column.getVisibility())
                    )) {
                    put = new Put(rowKey);

                    if (column.getVisibility() != null) {
                        put.setCellVisibility(new CellVisibility(column.getVisibility()));
                    }
                    retVal.add(put);
                }

                if (column.getTimestamp() != null) {
                    put.addColumn(
                            column.getColumnFamily(),
                            column.getColumnQualifier(),
                            column.getTimestamp(),
                            column.getBuffer());
                } else {
                    put.addColumn(
                            column.getColumnFamily(),
                            column.getColumnQualifier(),
                            column.getBuffer());
                }
            }
        } catch (DeserializationException de) {
            getLogger().error("Error writing cell visibility statement.", de);
            throw new RuntimeException(de);
        }

        return retVal;
    }

    @Override
    public void put(final String tableName, final Collection<PutFlowFile> puts) throws IOException {
        try (final Table table = connection.getTable(TableName.valueOf(tableName))) {
            final Map<String, List<PutColumn>> sorted = new HashMap<>();
            final List<Put> newPuts = new ArrayList<>();

            for (final PutFlowFile putFlowFile : puts) {
                final String rowKeyString = new String(putFlowFile.getRow(), StandardCharsets.UTF_8);
                List<PutColumn> columns = sorted.get(rowKeyString);
                if (columns == null) {
                    columns = new ArrayList<>();
                    sorted.put(rowKeyString, columns);
                }

                columns.addAll(putFlowFile.getColumns());
            }

            for (final Map.Entry<String, List<PutColumn>> entry : sorted.entrySet()) {
                newPuts.addAll(buildPuts(entry.getKey().getBytes(StandardCharsets.UTF_8), entry.getValue()));
            }

            table.put(newPuts);
        }
    }

    @Override
    public void put(final String tableName, final byte[] rowId, final Collection<PutColumn> columns) throws IOException {
        try (final Table table = connection.getTable(TableName.valueOf(tableName))) {
            table.put(buildPuts(rowId, new ArrayList<PutColumn>(columns)));
        }
    }

    @Override
    public boolean checkAndPut(final String tableName, final byte[] rowId, final byte[] family, final byte[] qualifier, final byte[] value, final PutColumn column) throws IOException {
        try (final Table table = connection.getTable(TableName.valueOf(tableName))) {
            Put put = new Put(rowId);
            put.addColumn(
                column.getColumnFamily(),
                column.getColumnQualifier(),
                column.getBuffer());
            return table.checkAndPut(rowId, family, qualifier, value, put);
        }
    }

    @Override
    public void delete(final String tableName, final byte[] rowId) throws IOException {
        delete(tableName, rowId, null);
    }

    @Override
    public void delete(String tableName, byte[] rowId, String visibilityLabel) throws IOException {
        try (final Table table = connection.getTable(TableName.valueOf(tableName))) {
            Delete delete = new Delete(rowId);
            if (!StringUtils.isEmpty(visibilityLabel)) {
                delete.setCellVisibility(new CellVisibility(visibilityLabel));
            }
            table.delete(delete);
        }
    }

    @Override
    public void delete(String tableName, List<byte[]> rowIds) throws IOException {
        delete(tableName, rowIds);
    }

    @Override
    public void deleteCells(String tableName, List<DeleteRequest> deletes) throws IOException {
        List<Delete> deleteRequests = new ArrayList<>();
        for (int index = 0; index < deletes.size(); index++) {
            DeleteRequest req = deletes.get(index);
            Delete delete = new Delete(req.getRowId())
                .addColumn(req.getColumnFamily(), req.getColumnQualifier());
            if (!StringUtils.isEmpty(req.getVisibilityLabel())) {
                delete.setCellVisibility(new CellVisibility(req.getVisibilityLabel()));
            }
            deleteRequests.add(delete);
        }
        batchDelete(tableName, deleteRequests);
    }

    @Override
    public void delete(String tableName, List<byte[]> rowIds, String visibilityLabel) throws IOException {
        List<Delete> deletes = new ArrayList<>();
        for (int index = 0; index < rowIds.size(); index++) {
            Delete delete = new Delete(rowIds.get(index));
            if (!StringUtils.isBlank(visibilityLabel)) {
                delete.setCellVisibility(new CellVisibility(visibilityLabel));
            }
            deletes.add(delete);
        }
        batchDelete(tableName, deletes);
    }

    private void batchDelete(String tableName, List<Delete> deletes) throws IOException {
        try (final Table table = connection.getTable(TableName.valueOf(tableName))) {
            table.delete(deletes);
        }
    }

    @Override
    public void scan(final String tableName, final Collection<Column> columns, final String filterExpression, final long minTime, final ResultHandler handler)
            throws IOException {
        scan(tableName, columns, filterExpression, minTime, null, handler);
    }

    @Override
    public void scan(String tableName, Collection<Column> columns, String filterExpression, long minTime, List<String> visibilityLabels, ResultHandler handler) throws IOException {
        Filter filter = null;
        if (!StringUtils.isBlank(filterExpression)) {
            ParseFilter parseFilter = new ParseFilter();
            filter = parseFilter.parseFilterString(filterExpression);
        }

        try (final Table table = connection.getTable(TableName.valueOf(tableName));
             final ResultScanner scanner = getResults(table, columns, filter, minTime, visibilityLabels)) {

            for (final Result result : scanner) {
                final byte[] rowKey = result.getRow();
                final Cell[] cells = result.rawCells();

                if (cells == null) {
                    continue;
                }

                final ResultCell[] resultCells = new ResultCell[cells.length];
                for (int i=0; i < cells.length; i++) {
                    final Cell cell = cells[i];
                    final ResultCell resultCell = getResultCell(cell);
                    resultCells[i] = resultCell;
                }

                handler.handle(rowKey, resultCells);
            }
        }
    }

    @Override
    public void scan(final String tableName, final byte[] startRow, final byte[] endRow, final Collection<Column> columns, List<String> authorizations, final ResultHandler handler)
            throws IOException {

        try (final Table table = connection.getTable(TableName.valueOf(tableName));
             final ResultScanner scanner = getResults(table, startRow, endRow, columns, authorizations)) {

            for (final Result result : scanner) {
                final byte[] rowKey = result.getRow();
                final Cell[] cells = result.rawCells();

                if (cells == null) {
                    continue;
                }

                final ResultCell[] resultCells = new ResultCell[cells.length];
                for (int i=0; i < cells.length; i++) {
                    final Cell cell = cells[i];
                    final ResultCell resultCell = getResultCell(cell);
                    resultCells[i] = resultCell;
                }

                handler.handle(rowKey, resultCells);
            }
        }
    }

    @Override
    public void scan(final String tableName, final String startRow, final String endRow, String filterExpression,
            final Long timerangeMin, final Long timerangeMax, final Integer limitRows, final Boolean isReversed,
            final Collection<Column> columns, List<String> visibilityLabels, final ResultHandler handler) throws IOException {

        try (final Table table = connection.getTable(TableName.valueOf(tableName));
                final ResultScanner scanner = getResults(table, startRow, endRow, filterExpression, timerangeMin,
                        timerangeMax, limitRows, isReversed, columns, visibilityLabels)) {

            int cnt = 0;
            final int lim = limitRows != null ? limitRows : 0;
            for (final Result result : scanner) {

                if (lim > 0 && ++cnt > lim){
                    break;
                }

                final byte[] rowKey = result.getRow();
                final Cell[] cells = result.rawCells();

                if (cells == null) {
                    continue;
                }

                final ResultCell[] resultCells = new ResultCell[cells.length];
                for (int i = 0; i < cells.length; i++) {
                    final Cell cell = cells[i];
                    final ResultCell resultCell = getResultCell(cell);
                    resultCells[i] = resultCell;
                }

                handler.handle(rowKey, resultCells);
            }
        }
    }

    protected ResultScanner getResults(final Table table, final String startRow, final String endRow, final String filterExpression, final Long timerangeMin, final Long timerangeMax,
            final Integer limitRows, final Boolean isReversed, final Collection<Column> columns, List<String> authorizations)  throws IOException {
        final Scan scan = new Scan();
        if (!StringUtils.isBlank(startRow)){
            scan.setStartRow(startRow.getBytes(StandardCharsets.UTF_8));
        }
        if (!StringUtils.isBlank(endRow)){
            scan.setStopRow(endRow.getBytes(StandardCharsets.UTF_8));
        }

        if (authorizations != null && authorizations.size() > 0) {
            scan.setAuthorizations(new Authorizations(authorizations));
        }

        Filter filter = null;
        if (columns != null) {
            for (Column col : columns) {
                if (col.getQualifier() == null) {
                    scan.addFamily(col.getFamily());
                } else {
                    scan.addColumn(col.getFamily(), col.getQualifier());
                }
            }
        }
        if (!StringUtils.isBlank(filterExpression)) {
            ParseFilter parseFilter = new ParseFilter();
            filter = parseFilter.parseFilterString(filterExpression);
        }
        if (filter != null){
            scan.setFilter(filter);
        }

        if (timerangeMin != null && timerangeMax != null){
            scan.setTimeRange(timerangeMin, timerangeMax);
        }

        if (isReversed != null){
            scan.setReversed(isReversed);
        }

        return table.getScanner(scan);
    }

    protected ResultScanner getResults(final Table table, final byte[] startRow, final byte[] endRow,
            final Collection<Column> columns, List<String> authorizations) throws IOException {
        final Scan scan = new Scan();
        scan.setStartRow(startRow);
        scan.setStopRow(endRow);

        if (authorizations != null && authorizations.size() > 0) {
            scan.setAuthorizations(new Authorizations(authorizations));
        }

        if (columns != null && columns.size() > 0) {
            for (Column col : columns) {
                if (col.getQualifier() == null) {
                    scan.addFamily(col.getFamily());
                } else {
                    scan.addColumn(col.getFamily(), col.getQualifier());
                }
            }
        }

        return table.getScanner(scan);
    }

    protected ResultScanner getResults(final Table table, final Collection<Column> columns, final Filter filter,
            final long minTime, List<String> authorizations) throws IOException {
        final Scan scan = new Scan();
        scan.setTimeRange(minTime, Long.MAX_VALUE);

        if (authorizations != null && authorizations.size() > 0) {
            scan.setAuthorizations(new Authorizations(authorizations));
        }

        if (filter != null) {
            scan.setFilter(filter);
        }

        if (columns != null) {
            for (Column col : columns) {
                if (col.getQualifier() == null) {
                    scan.addFamily(col.getFamily());
                } else {
                    scan.addColumn(col.getFamily(), col.getQualifier());
                }
            }
        }

        return table.getScanner(scan);
    }

    private ResultCell getResultCell(Cell cell) {
        final ResultCell resultCell = new ResultCell();
        resultCell.setRowArray(cell.getRowArray());
        resultCell.setRowOffset(cell.getRowOffset());
        resultCell.setRowLength(cell.getRowLength());

        resultCell.setFamilyArray(cell.getFamilyArray());
        resultCell.setFamilyOffset(cell.getFamilyOffset());
        resultCell.setFamilyLength(cell.getFamilyLength());

        resultCell.setQualifierArray(cell.getQualifierArray());
        resultCell.setQualifierOffset(cell.getQualifierOffset());
        resultCell.setQualifierLength(cell.getQualifierLength());

        resultCell.setTimestamp(cell.getTimestamp());
        resultCell.setTypeByte(cell.getTypeByte());
        resultCell.setSequenceId(cell.getSequenceId());

        resultCell.setValueArray(cell.getValueArray());
        resultCell.setValueOffset(cell.getValueOffset());
        resultCell.setValueLength(cell.getValueLength());

        resultCell.setTagsArray(cell.getTagsArray());
        resultCell.setTagsOffset(cell.getTagsOffset());
        resultCell.setTagsLength(cell.getTagsLength());
        return resultCell;
    }

    @Override
    public byte[] toBytes(boolean b) {
        return Bytes.toBytes(b);
    }

    @Override
    public byte[] toBytes(float f) {
        return Bytes.toBytes(f);
    }

    @Override
    public byte[] toBytes(int i) {
        return Bytes.toBytes(i);
    }

    @Override
    public byte[] toBytes(long l) {
        return Bytes.toBytes(l);
    }

    @Override
    public byte[] toBytes(double d) {
        return Bytes.toBytes(d);
    }

    @Override
    public byte[] toBytes(String s) {
        return Bytes.toBytes(s);
    }

    @Override
    public byte[] toBytesBinary(String s) {
        return Bytes.toBytesBinary(s);
    }

    @Override
    public String toTransitUri(String tableName, String rowKey) {
        if (connection == null) {
            logger.warn("Connection has not been established, could not create a transit URI. Returning null.");
            return null;
        }
        final String transitUriMasterAddress = StringUtils.isEmpty(masterAddress) ? "unknown" : masterAddress;
        return "bigtable://" + transitUriMasterAddress + "/" + tableName + (StringUtils.isEmpty(rowKey) ? "" : "/" + rowKey);
    }
}
