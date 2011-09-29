/*
 * Copyright 2011 Proofpoint, Inc.
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
package com.proofpoint.discovery.elb;

import com.google.common.collect.ImmutableMap;
import com.proofpoint.configuration.testing.ConfigAssertions;
import com.proofpoint.units.Duration;
import org.testng.annotations.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

public class TestServerConfig
{
    @Test
    public void testDefaults()
    {
        ConfigAssertions.assertRecordedDefaults(ConfigAssertions.recordDefaults(ServerConfig.class)
                .setUpdateInterval(new Duration(30, TimeUnit.SECONDS))
                .setAwsAccessKey(null)
                .setAwsSecretKey(null)
        );
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("elb.update-interval", "2s")
                .put("elb.aws-access-key", "my-access-key")
                .put("elb.aws-secret-key", "my-secret-key")
                .build();

        ServerConfig expected = new ServerConfig()
                .setUpdateInterval(new Duration(2, TimeUnit.SECONDS))
                .setAwsAccessKey("my-access-key")
                .setAwsSecretKey("my-secret-key");

        ConfigAssertions.assertFullMapping(properties, expected);
    }
}
