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

package org.apache.shardingsphere.infra.parser.sql;

import org.apache.shardingsphere.sql.parser.api.SQLParserEngine;
import org.apache.shardingsphere.sql.parser.api.SQLVisitorEngine;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;

import java.util.Properties;

/**
 * SQL statement parser executor.
 */
public final class SQLStatementParserExecutor {

    //解析引擎
    private final SQLParserEngine parserEngine;

    //视图引擎
    private final SQLVisitorEngine visitorEngine;
    
    public SQLStatementParserExecutor(final String databaseType) {
        //根据数据库类型初始化解析引擎
        parserEngine = new SQLParserEngine(databaseType);
        //根据数据引擎创建STATEMENT类信息的视图引擎
        visitorEngine = new SQLVisitorEngine(databaseType, "STATEMENT", new Properties());
    }
    
    /**
     * Parse to SQL statement.
     *
     * @param sql SQL to be parsed
     * @return SQL statement
     */
    public SQLStatement parse(final String sql) {
        //解析引擎先解析sql得到语法树ParseTree，再使用视图引擎解析出SQLStatement
        return visitorEngine.visit(parserEngine.parse(sql, false));
    }
}
