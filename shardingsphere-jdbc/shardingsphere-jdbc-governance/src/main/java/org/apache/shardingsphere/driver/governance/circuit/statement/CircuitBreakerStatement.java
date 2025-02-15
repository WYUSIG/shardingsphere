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

package org.apache.shardingsphere.driver.governance.circuit.statement;

import lombok.Getter;
import org.apache.shardingsphere.driver.governance.circuit.connection.CircuitBreakerConnection;
import org.apache.shardingsphere.driver.jdbc.unsupported.AbstractUnsupportedOperationStatement;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLWarning;

/**
 * Circuit breaker statement.
 */
@Getter
public final class CircuitBreakerStatement extends AbstractUnsupportedOperationStatement {
    
    @Override
    public void close() {
    }
    
    @Override
    public int getMaxFieldSize() {
        return 0;
    }
    
    @Override
    public void setMaxFieldSize(final int max) {
    }
    
    @Override
    public int getMaxRows() {
        return 0;
    }
    
    @Override
    public void setMaxRows(final int max) {
    }
    
    @Override
    public void setEscapeProcessing(final boolean enable) {
    }
    
    @Override
    public int getQueryTimeout() {
        return 0;
    }
    
    @Override
    public void setQueryTimeout(final int seconds) {
    }
    
    @Override
    public void cancel() {
    }
    
    @Override
    public SQLWarning getWarnings() {
        return null;
    }
    
    @Override
    public void clearWarnings() {
    }
    
    @Override
    public ResultSet getResultSet() {
        return null;
    }
    
    @Override
    public int getUpdateCount() {
        return 0;
    }
    
    @Override
    public void setFetchSize(final int rows) {
    }
    
    @Override
    public int getFetchSize() {
        return 0;
    }

    @Override
    public int getFetchDirection() {
        return ResultSet.FETCH_FORWARD;
    }

    @Override
    public void setFetchDirection(final int direction) {
    }

    @Override
    public int getResultSetConcurrency() {
        return ResultSet.CONCUR_READ_ONLY;
    }
    
    @Override
    public int getResultSetType() {
        return ResultSet.TYPE_FORWARD_ONLY;
    }
    
    @Override
    public Connection getConnection() {
        return new CircuitBreakerConnection();
    }
    
    @Override
    public boolean getMoreResults() {
        return false;
    }
    
    @Override
    public boolean getMoreResults(final int current) {
        return false;
    }
    
    @Override
    public ResultSet getGeneratedKeys() {
        return null;
    }
    
    @Override
    public ResultSet executeQuery(final String sql) {
        return null;
    }
    
    @Override
    public int executeUpdate(final String sql) {
        return 0;
    }
    
    @Override
    public int executeUpdate(final String sql, final int autoGeneratedKeys) {
        return 0;
    }
    
    @Override
    public int executeUpdate(final String sql, final int[] columnIndexes) {
        return 0;
    }
    
    @Override
    public int executeUpdate(final String sql, final String[] columnNames) {
        return 0;
    }
    
    @Override
    public boolean execute(final String sql) {
        return false;
    }
    
    @Override
    public boolean execute(final String sql, final int autoGeneratedKeys) {
        return false;
    }
    
    @Override
    public boolean execute(final String sql, final int[] columnIndexes) {
        return false;
    }
    
    @Override
    public boolean execute(final String sql, final String[] columnNames) {
        return false;
    }
    
    @Override
    public int getResultSetHoldability() {
        return 0;
    }
    
    @Override
    public boolean isClosed() {
        return false;
    }
    
    @Override
    public void setPoolable(final boolean poolable) {
    }
    
    @Override
    public boolean isPoolable() {
        return false;
    }
}
