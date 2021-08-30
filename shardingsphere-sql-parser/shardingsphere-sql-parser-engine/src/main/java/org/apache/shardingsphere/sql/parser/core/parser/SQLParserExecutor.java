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

package org.apache.shardingsphere.sql.parser.core.parser;

import lombok.RequiredArgsConstructor;
import org.antlr.v4.runtime.BailErrorStrategy;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.shardingsphere.sql.parser.api.parser.SQLParser;
import org.apache.shardingsphere.sql.parser.exception.SQLParsingException;

/**
 * SQL parser executor.
 */
@RequiredArgsConstructor
public final class SQLParserExecutor {
    
    private final String databaseType;
    
    /**
     * Parse SQL.
     * 
     * @param sql SQL to be parsed
     * @return parse tree
     */
    public ParseTree parse(final String sql) {
        //调用解析方法
        ParseASTNode result = twoPhaseParse(sql);
        if (result.getRootNode() instanceof ErrorNode) {
            throw new SQLParsingException("Unsupported SQL of `%s`", sql);
        }
        //返回第一个孩子节点
        return result.getRootNode();
    }
    
    private ParseASTNode twoPhaseParse(final String sql) {
        //根据数据库类型、sql进行词法解析，得到语法解析SQLParser
        SQLParser sqlParser = SQLParserFactory.newInstance(databaseType, sql);
        try {
            //先尝试使用SLL模式进行语法解析
            setPredictionMode((Parser) sqlParser, PredictionMode.SLL);
            return (ParseASTNode) sqlParser.parse();
        } catch (final ParseCancellationException ex) {
            //失败后，使用LL模式进行语法解析
            ((Parser) sqlParser).reset();
            setPredictionMode((Parser) sqlParser, PredictionMode.LL);
            try {
                return (ParseASTNode) sqlParser.parse();
            } catch (final ParseCancellationException e) {
                throw new SQLParsingException("You have an error in your SQL syntax");
            }
        }
    }
    
    private void setPredictionMode(final Parser sqlParser, final PredictionMode mode) {
        sqlParser.setErrorHandler(new BailErrorStrategy());
        sqlParser.getInterpreter().setPredictionMode(mode);
    }
}
