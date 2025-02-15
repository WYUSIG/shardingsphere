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

package org.apache.shardingsphere.infra.metadata.schema.builder.loader;

import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.metadata.schema.builder.SchemaBuilderMaterials;
import org.apache.shardingsphere.infra.metadata.schema.model.ColumnMetaData;
import org.apache.shardingsphere.infra.metadata.schema.model.IndexMetaData;
import org.apache.shardingsphere.infra.metadata.schema.model.TableMetaData;
import org.apache.shardingsphere.infra.rule.identifier.type.DataSourceContainedRule;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public final class DefaultTableMetaDataLoaderTest {
    
    private static final String TEST_CATALOG = "catalog";
    
    private static final String TEST_TABLE = "table";
    
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private DataSource dataSource;
    
    @Mock
    private ResultSet primaryResultSet;
    
    @Mock
    private ResultSet tableExistResultSet;
    
    @Mock
    private ResultSet columnResultSet;
    
    @Mock
    private ResultSet caseSensitivesResultSet;
    
    @Mock
    private ResultSetMetaData resultSetMetaData;
    
    @Mock
    private ResultSet indexResultSet;
    
    @Before
    public void setUp() throws SQLException {
        when(dataSource.getConnection().getCatalog()).thenReturn(TEST_CATALOG);
        when(dataSource.getConnection().getMetaData().getTables(TEST_CATALOG, null, TEST_TABLE, null)).thenReturn(tableExistResultSet);
        when(tableExistResultSet.next()).thenReturn(true);
        when(dataSource.getConnection().getMetaData().getColumns(TEST_CATALOG, null, TEST_TABLE, "%")).thenReturn(columnResultSet);
        when(columnResultSet.next()).thenReturn(true, true, false);
        when(columnResultSet.getString("TABLE_NAME")).thenReturn(TEST_TABLE);
        when(columnResultSet.getString("COLUMN_NAME")).thenReturn("pk_col", "col");
        when(columnResultSet.getInt("DATA_TYPE")).thenReturn(Types.INTEGER, Types.VARCHAR);
        when(columnResultSet.getString("TYPE_NAME")).thenReturn("INT", "VARCHAR");
        when(dataSource.getConnection().getMetaData().getPrimaryKeys(TEST_CATALOG, null, TEST_TABLE)).thenReturn(primaryResultSet);
        when(primaryResultSet.next()).thenReturn(true, false);
        when(primaryResultSet.getString("COLUMN_NAME")).thenReturn("pk_col");
        when(dataSource.getConnection().createStatement().executeQuery(anyString())).thenReturn(caseSensitivesResultSet);
        when(caseSensitivesResultSet.findColumn("pk_col")).thenReturn(1);
        when(caseSensitivesResultSet.findColumn("col")).thenReturn(2);
        when(caseSensitivesResultSet.getMetaData()).thenReturn(resultSetMetaData);
        when(resultSetMetaData.isCaseSensitive(1)).thenReturn(true);
        when(dataSource.getConnection().getMetaData().getIndexInfo(TEST_CATALOG, null, TEST_TABLE, false, false)).thenReturn(indexResultSet);
        when(indexResultSet.next()).thenReturn(true, false);
        when(indexResultSet.getString("INDEX_NAME")).thenReturn("my_index");
    }
    
    @Test
    public void assertLoadWithExistedTable() throws SQLException {
        DatabaseType databaseType = mock(DatabaseType.class, RETURNS_DEEP_STUBS);
        when(databaseType.formatTableNamePattern(TEST_TABLE)).thenReturn(TEST_TABLE);
        Optional<TableMetaData> actual = DefaultTableMetaDataLoader.load(dataSource, TEST_TABLE, databaseType);
        assertTrue(actual.isPresent());
        Map<String, ColumnMetaData> columnMetaDataMap = actual.get().getColumns();
        assertThat(columnMetaDataMap.size(), is(2));
        assertColumnMetaData(columnMetaDataMap.get("pk_col"), "pk_col", Types.INTEGER, true, true);
        assertColumnMetaData(columnMetaDataMap.get("col"), "col", Types.VARCHAR, false, false);
        Map<String, IndexMetaData> indexMetaDataMap = actual.get().getIndexes();
        assertThat(indexMetaDataMap.size(), is(1));
        assertTrue(indexMetaDataMap.containsKey("my_index"));
    }
    
    private void assertColumnMetaData(final ColumnMetaData actual, final String name, final int dataType, final boolean primaryKey, final boolean caseSensitive) {
        assertThat(actual.getName(), is(name));
        assertThat(actual.getDataType(), is(dataType));
        assertThat(actual.isPrimaryKey(), is(primaryKey));
        assertThat(actual.isCaseSensitive(), is(caseSensitive));
    }
    
    @Test
    public void assertLoadWithNotExistedTable() throws SQLException {
        assertFalse(DefaultTableMetaDataLoader.load(dataSource, TEST_TABLE, mock(DatabaseType.class)).isPresent());
    }
    
    @Test
    public void assertLoadFromLogicDataSourceWithExistedTable() throws SQLException {
        DataSourceContainedRule rule = mock(DataSourceContainedRule.class, RETURNS_DEEP_STUBS);
        when(rule.getDataSourceMapper().containsKey("pr_ds")).thenReturn(true);
        when(rule.getDataSourceMapper().get("pr_ds")).thenReturn(Arrays.asList("write_ds", "read_ds_0", "read_ds_1"));
        SchemaBuilderMaterials materials = mock(SchemaBuilderMaterials.class, RETURNS_DEEP_STUBS);
        when(materials.getRules()).thenReturn(Collections.singletonList(rule));
        DatabaseType databaseType = mock(DatabaseType.class, RETURNS_DEEP_STUBS);
        when(databaseType.formatTableNamePattern(TEST_TABLE)).thenReturn(TEST_TABLE);
        when(materials.getDatabaseType()).thenReturn(databaseType);
        when(materials.getDataSourceMap().get("write_ds")).thenReturn(dataSource);
        Optional<TableMetaData> actual = DefaultTableMetaDataLoader.load(TEST_TABLE, Collections.singletonList("pr_ds"), materials);
        assertTrue(actual.isPresent());
        Map<String, ColumnMetaData> columnMetaDataMap = actual.get().getColumns();
        assertThat(columnMetaDataMap.size(), is(2));
        assertColumnMetaData(columnMetaDataMap.get("pk_col"), "pk_col", Types.INTEGER, true, true);
        assertColumnMetaData(columnMetaDataMap.get("col"), "col", Types.VARCHAR, false, false);
        Map<String, IndexMetaData> indexMetaDataMap = actual.get().getIndexes();
        assertThat(indexMetaDataMap.size(), is(1));
        assertTrue(indexMetaDataMap.containsKey("my_index"));
    }
    
    @Test
    public void assertLoadFromLogicDataSourceWithNotExistedTable() throws SQLException {
        assertFalse(DefaultTableMetaDataLoader.load(TEST_TABLE, Collections.singletonList("pr_ds"), mock(SchemaBuilderMaterials.class)).isPresent());
    }
}
