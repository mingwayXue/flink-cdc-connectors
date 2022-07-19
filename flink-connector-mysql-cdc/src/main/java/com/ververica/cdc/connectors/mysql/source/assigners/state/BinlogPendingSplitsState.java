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

package com.ververica.cdc.connectors.mysql.source.assigners.state;

import com.ververica.cdc.connectors.mysql.source.assigners.AssignerStatus;
import io.debezium.relational.TableId;

import java.util.List;
import java.util.Objects;

/** A {@link PendingSplitsState} for pending binlog splits. */
public class BinlogPendingSplitsState extends PendingSplitsState {

    private final boolean isBinlogSplitAssigned;

    private final List<TableId> capturedTables;

    private final AssignerStatus assignerStatus;

    public BinlogPendingSplitsState(
            boolean isBinlogSplitAssigned,
            List<TableId> capturedTables,
            AssignerStatus assignerStatus) {
        this.isBinlogSplitAssigned = isBinlogSplitAssigned;
        this.capturedTables = capturedTables;
        this.assignerStatus = assignerStatus;
    }

    public boolean isBinlogSplitAssigned() {
        return isBinlogSplitAssigned;
    }

    public List<TableId> getCapturedTables() {
        return capturedTables;
    }

    public AssignerStatus getAssignerStatus() {
        return assignerStatus;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BinlogPendingSplitsState that = (BinlogPendingSplitsState) o;
        return isBinlogSplitAssigned == that.isBinlogSplitAssigned
                && Objects.equals(capturedTables, that.capturedTables)
                && Objects.equals(assignerStatus, that.assignerStatus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isBinlogSplitAssigned, capturedTables, assignerStatus);
    }

    @Override
    public String toString() {
        return "BinlogPendingSplitsState{"
                + "isBinlogSplitAssigned="
                + isBinlogSplitAssigned
                + ",capturedTables="
                + capturedTables
                + ",assignerStatus="
                + assignerStatus
                + '}';
    }
}
