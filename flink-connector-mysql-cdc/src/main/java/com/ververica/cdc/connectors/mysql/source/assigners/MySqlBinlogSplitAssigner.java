/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.mysql.source.assigners;

import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.Preconditions;

import com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils;
import com.ververica.cdc.connectors.mysql.source.assigners.state.BinlogPendingSplitsState;
import com.ververica.cdc.connectors.mysql.source.assigners.state.PendingSplitsState;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import com.ververica.cdc.connectors.mysql.source.offset.BinlogOffset;
import com.ververica.cdc.connectors.mysql.source.split.FinishedSnapshotSplitInfo;
import com.ververica.cdc.connectors.mysql.source.split.MySqlBinlogSplit;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSplit;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.relational.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ververica.cdc.connectors.mysql.source.assigners.AssignerStatus.isAssigningFinished;
import static com.ververica.cdc.connectors.mysql.source.assigners.AssignerStatus.isSuspended;

/** A {@link MySqlSplitAssigner} which only read binlog from current binlog position. */
public class MySqlBinlogSplitAssigner implements MySqlSplitAssigner {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlBinlogSplitAssigner.class);
    private static final String BINLOG_SPLIT_ID = "binlog-split";

    private final MySqlSourceConfig sourceConfig;

    private boolean isBinlogSplitAssigned;

    private final List<TableId> capturedTables;

    private AssignerStatus assignerStatus;

    public MySqlBinlogSplitAssigner(MySqlSourceConfig sourceConfig) {
        this(sourceConfig, false);
    }

    public MySqlBinlogSplitAssigner(
            MySqlSourceConfig sourceConfig, BinlogPendingSplitsState checkpoint) {
        this(
                sourceConfig,
                checkpoint.isBinlogSplitAssigned(),
                checkpoint.getCapturedTables(),
                checkpoint.getAssignerStatus());
    }

    private MySqlBinlogSplitAssigner(
            MySqlSourceConfig sourceConfig, boolean isBinlogSplitAssigned) {
        this.sourceConfig = sourceConfig;
        this.isBinlogSplitAssigned = isBinlogSplitAssigned;
        this.capturedTables = new ArrayList<>();
    }

    public MySqlBinlogSplitAssigner(MySqlSourceConfig sourceConfig, List<TableId> capturedTables) {
        this(sourceConfig, false, capturedTables, AssignerStatus.INITIAL_ASSIGNING_FINISHED);
    }

    private MySqlBinlogSplitAssigner(
            MySqlSourceConfig sourceConfig,
            boolean isBinlogSplitAssigned,
            List<TableId> capturedTables,
            AssignerStatus assignerStatus) {
        this.sourceConfig = sourceConfig;
        this.isBinlogSplitAssigned = isBinlogSplitAssigned;
        this.capturedTables = capturedTables;
        this.assignerStatus = assignerStatus;
    }

    @Override
    public void open() {
        captureNewlyAddedTables();
    }

    private void captureNewlyAddedTables() {
        if (sourceConfig.isScanNewlyAddedTableEnabled() && isBinlogSplitAssigned) {
            try (JdbcConnection jdbc = DebeziumUtils.openJdbcConnection(sourceConfig)) {
                List<TableId> newlyAddedTables =
                        DebeziumUtils.discoverCapturedTables(jdbc, sourceConfig);
                newlyAddedTables.removeAll(capturedTables);
                if (!newlyAddedTables.isEmpty()) {
                    LOG.info("Found newly added tables, start capture newly added tables process");
                    capturedTables.addAll(newlyAddedTables);
                    if (isAssigningFinished(assignerStatus)) {
                        // start the newly added tables process under binlog reading phase
                        LOG.info(
                                "Found newly added tables, start capture newly added tables process under binlog reading phase");
                        this.suspend();
                    }
                }
            } catch (Exception e) {
                throw new FlinkRuntimeException(
                        "Failed to discover capturedTables tables to capture", e);
            }
        }
    }

    @Override
    public Optional<MySqlSplit> getNext() {
        if (isBinlogSplitAssigned) {
            return Optional.empty();
        } else {
            isBinlogSplitAssigned = true;
            return Optional.of(createBinlogSplit());
        }
    }

    @Override
    public boolean waitingForFinishedSplits() {
        return false;
    }

    @Override
    public List<FinishedSnapshotSplitInfo> getFinishedSplitInfos() {
        return Collections.EMPTY_LIST;
    }

    @Override
    public void onFinishedSplits(Map<String, BinlogOffset> splitFinishedOffsets) {
        // do nothing
    }

    @Override
    public void addSplits(Collection<MySqlSplit> splits) {
        // we don't store the split, but will re-create binlog split later
        isBinlogSplitAssigned = false;
    }

    @Override
    public PendingSplitsState snapshotState(long checkpointId) {
        return new BinlogPendingSplitsState(isBinlogSplitAssigned, capturedTables, assignerStatus);
    }

    @Override
    public void notifyCheckpointComplete(long checkpointId) {
        // nothing to do
    }

    @Override
    public AssignerStatus getAssignerStatus() {
        return assignerStatus;
    }

    @Override
    public void suspend() {
        Preconditions.checkState(
                isAssigningFinished(assignerStatus), "Invalid assigner status {}", assignerStatus);
        assignerStatus = assignerStatus.suspend();
    }

    @Override
    public void wakeup() {
        Preconditions.checkState(
                isSuspended(assignerStatus), "Invalid assigner status {}", assignerStatus);
        assignerStatus = assignerStatus.wakeup();
    }

    @Override
    public void close() {}

    // ------------------------------------------------------------------------------------------

    private MySqlBinlogSplit createBinlogSplit() {
        try (JdbcConnection jdbc = DebeziumUtils.openJdbcConnection(sourceConfig)) {
            return new MySqlBinlogSplit(
                    BINLOG_SPLIT_ID,
                    DebeziumUtils.currentBinlogOffset(jdbc),
                    BinlogOffset.NO_STOPPING_OFFSET,
                    new ArrayList<>(),
                    new HashMap<>(),
                    0);
        } catch (Exception e) {
            throw new FlinkRuntimeException("Read the binlog offset error", e);
        }
    }

    /*private MySqlBinlogSplit createNewlyAddedTableBinlogSplit() {
        try (MySqlConnection jdbc =
                DebeziumUtils.createMySqlConnection(sourceConfig.getDbzConfiguration())) {
            // fetch table schemas
            MySqlSchema mySqlSchema = new MySqlSchema(sourceConfig, jdbc.isTableIdCaseSensitive());
            Map<TableId, TableChanges.TableChange> tableSchemas = new HashMap<>();
            for (TableId tableId : newlyAddedTables) {
                TableChanges.TableChange tableSchema = mySqlSchema.getTableSchema(jdbc, tableId);
                tableSchemas.put(tableId, tableSchema);
            }
            // newlyAddedTableBinlogSplit
            return new MySqlBinlogSplit(
                    BINLOG_SPLIT_ID,
                    DebeziumUtils.currentBinlogOffset(jdbc),
                    BinlogOffset.NO_STOPPING_OFFSET,
                    new ArrayList<>(),
                    tableSchemas,
                    0);
        } catch (Exception e) {
            throw new FlinkRuntimeException("Read the binlog offset error", e);
        }
    }*/

    public List<TableId> getCapturedTables() {
        return capturedTables;
    }
}
