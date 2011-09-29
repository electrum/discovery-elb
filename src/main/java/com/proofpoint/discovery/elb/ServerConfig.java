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

import com.proofpoint.configuration.Config;
import com.proofpoint.configuration.ConfigDescription;
import com.proofpoint.units.Duration;
import com.proofpoint.units.MinDuration;

import javax.validation.constraints.NotNull;
import java.util.concurrent.TimeUnit;

public class ServerConfig
{
    private Duration updateInterval = new Duration(30, TimeUnit.SECONDS);
    private String awsAccessKey;
    private String awsSecretKey;

    @Config("elb.update-interval")
    @ConfigDescription("time between updates")
    public ServerConfig setUpdateInterval(Duration updateInterval)
    {
        this.updateInterval = updateInterval;
        return this;
    }

    @NotNull
    @MinDuration("1s")
    public Duration getUpdateInterval()
    {
        return updateInterval;
    }

    @Config("elb.aws-access-key")
    @ConfigDescription("AWS access key")
    public ServerConfig setAwsAccessKey(String awsAccessKey)
    {
        this.awsAccessKey = awsAccessKey;
        return this;
    }

    @NotNull
    public String getAwsAccessKey()
    {
        return awsAccessKey;
    }

    @Config("elb.aws-secret-key")
    @ConfigDescription("AWS secret key")
    public ServerConfig setAwsSecretKey(String awsSecretKey)
    {
        this.awsSecretKey = awsSecretKey;
        return this;
    }

    @NotNull
    public String getAwsSecretKey()
    {
        return awsSecretKey;
    }
}
