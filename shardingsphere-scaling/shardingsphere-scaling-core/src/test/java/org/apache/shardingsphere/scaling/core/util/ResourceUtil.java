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

package org.apache.shardingsphere.scaling.core.util;

import com.google.gson.Gson;
import lombok.SneakyThrows;
import org.apache.commons.io.IOUtils;
import org.apache.shardingsphere.scaling.core.config.JobConfiguration;
import org.apache.shardingsphere.scaling.core.config.RuleConfiguration;
import org.apache.shardingsphere.scaling.core.config.datasource.ShardingSphereJDBCDataSourceConfiguration;
import org.apache.shardingsphere.scaling.core.config.datasource.StandardJDBCDataSourceConfiguration;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Resource util.
 */
public final class ResourceUtil {
    
    /**
     * Mock job configuration.
     *
     * @return job configuration
     */
    public static JobConfiguration mockJobConfig() {
        return mockStandardJdbcTargetJobConfig();
    }
    
    /**
     * Mock ShardingSphere-JDBC as target job configuration.
     *
     * @return ShardingSphere-JDBC target job configuration
     */
    public static JobConfiguration mockShardingSphereJdbcTargetJobConfig() {
        JobConfiguration result = new JobConfiguration();
        RuleConfiguration ruleConfig = new RuleConfiguration();
        ruleConfig.setSource(new ShardingSphereJDBCDataSourceConfiguration(readFileToString("/config_sharding_sphere_jdbc_source.yaml")).wrap());
        ruleConfig.setTarget(new ShardingSphereJDBCDataSourceConfiguration(readFileToString("/config_sharding_sphere_jdbc_target.yaml")).wrap());
        result.setRuleConfig(ruleConfig);
        return result;
    }
    
    /**
     * Mock standard JDBC as target job configuration.
     *
     * @return standard JDBC as target job configuration
     */
    public static JobConfiguration mockStandardJdbcTargetJobConfig() {
        JobConfiguration result = new JobConfiguration();
        RuleConfiguration ruleConfig = new RuleConfiguration();
        ruleConfig.setSource(new ShardingSphereJDBCDataSourceConfiguration(readFileToString("/config_sharding_sphere_jdbc_source.yaml")).wrap());
        ruleConfig.setTarget(new StandardJDBCDataSourceConfiguration(readFileToString("/config_standard_jdbc_target.yaml")).wrap());
        result.setRuleConfig(ruleConfig);
        return result;
    }
    
    @SneakyThrows(IOException.class)
    private static String readFileToString(final String fileName) {
        try (InputStream in = ResourceUtil.class.getResourceAsStream(fileName)) {
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void test() {
        JobConfiguration jobConfiguration = new JobConfiguration();

        RuleConfiguration ruleConfiguration = new RuleConfiguration();
        ruleConfiguration.setSource(new ShardingSphereJDBCDataSourceConfiguration(readFileToString("/config_sharding_jdbc_source.yaml")).wrap());
        ruleConfiguration.setTarget(new StandardJDBCDataSourceConfiguration(readFileToString("/config_standard_jdbc_target.yaml")).wrap());

        jobConfiguration.setRuleConfig(ruleConfiguration);

        String s = new Gson().toJson(jobConfiguration);
        System.out.println(s);
        s = "{\n" +
                "\"ruleConfig\":{\n" +
                "\"source\":{\n" +
                "\"type\":\"JDBC\",\n" +
                "\"parameter\":\"jdbcUrl: jdbc:mysql://127.0.0.1:3306/demo_scaling_ds_0?serverTimezone\\u003dUTC\\u0026useSSL\\u003dfalse\\r\\nusername: root\\r\\npassword:\"\n" +
                "},\n" +
                "\"target\":{\n" +
                "\"type\":\"JDBC\",\n" +
                "\"parameter\":\"jdbcUrl: jdbc:mysql://127.0.0.1:3306/demo_scaling_ds_1?serverTimezone\\u003dUTC\\u0026useSSL\\u003dfalse\\r\\nusername: root\\r\\npassword:\\r\\n\"\n" +
                "}\n" +
                "},\n" +
                "\"handleConfig\":{\n" +
                "\"concurrency\":3,\n" +
                "\"retryTimes\":3,\n" +
                "\"shardingItem\":0,\n" +
                "\"shardingSize\":10000000,\n" +
                "\"running\":true\n" +
                "}\n" +
                "}";
    }
}
