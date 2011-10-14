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

import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancing.model.DeregisterInstancesFromLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.Instance;
import com.amazonaws.services.elasticloadbalancing.model.Listener;
import com.amazonaws.services.elasticloadbalancing.model.ListenerDescription;
import com.amazonaws.services.elasticloadbalancing.model.LoadBalancerDescription;
import com.amazonaws.services.elasticloadbalancing.model.RegisterInstancesWithLoadBalancerRequest;
import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.proofpoint.discovery.client.DiscoveryAnnouncementClient;
import com.proofpoint.discovery.client.DiscoveryLookupClient;
import com.proofpoint.discovery.client.ServiceDescriptor;
import com.proofpoint.discovery.client.ServiceDescriptors;
import com.proofpoint.log.Logger;
import com.proofpoint.node.NodeInfo;
import com.proofpoint.units.Duration;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.Iterables.transform;
import static com.google.common.collect.Sets.difference;
import static com.google.common.collect.Sets.newHashSet;

@Immutable
public class ElasticLoadBalancerUpdater
{
    private static final Logger log = Logger.get(ElasticLoadBalancerUpdater.class);

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactoryBuilder().setNameFormat("ElasticLoadBalancerUpdater-%s").build());

    private final AmazonElasticLoadBalancing elbClient;
    private final NodeInfo nodeInfo;
    private final DiscoveryLookupClient discoveryClient;
    private final Duration updateInterval;

    @Inject
    public ElasticLoadBalancerUpdater(AmazonElasticLoadBalancing elbClient, NodeInfo nodeInfo, DiscoveryLookupClient discoveryClient, ServerConfig config)
    {
        this.elbClient = checkNotNull(elbClient, "elbClient is null");
        this.nodeInfo = checkNotNull(nodeInfo, "nodeInfo is null");
        this.discoveryClient = checkNotNull(discoveryClient, "discoveryClient is null");
        checkNotNull(config, "config is null");
        updateInterval = config.getUpdateInterval();
    }

    @PostConstruct
    public void start()
    {
        Runnable updater = new Runnable()
        {
            @Override
            public void run()
            {
                try {
                    update();
                }
                catch (Exception e) {
                    log.error(e, "update failed");
                }
            }
        };
        executor.scheduleAtFixedRate(updater, 0, (long) updateInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void destroy()
            throws IOException
    {
        executor.shutdown();
    }

    public void update()
            throws Exception
    {
        for (LoadBalancerDescription loadBalancer : elbClient.describeLoadBalancers().getLoadBalancerDescriptions()) {
            // split ELB name into parts
            String elbName = loadBalancer.getLoadBalancerName();
            List<String> parts = copyOf(Splitter.on('-').split(elbName).iterator());
            if (parts.size() != 3 && parts.size() != 4) {
                log.debug("ignoring load balancer: %s", elbName);
                continue;
            }
            String environment = parts.get(0);
            String type = parts.get(1);
            String pool = parts.get(2);
            log.debug("found load balancer: %s", elbName);

            // check against environment
            if (!environment.equals(nodeInfo.getEnvironment())) {
                continue;
            }

            // look for services in discovery
            ServiceDescriptors services = discoveryClient.getServices(type, pool).get(1, TimeUnit.SECONDS);

            // map services to instances
            Set<String> instances = newHashSet();
            for (ServiceDescriptor descriptor : services.getServiceDescriptors()) {
                String instanceId = extractEc2InstanceId(descriptor.getLocation());
                if (instanceId == null) {
                    log.warn("invalid EC2 location: %s", descriptor.getLocation());
                    continue;
                }

                // verify load balancer listeners against the service announcement
                boolean valid = true;
                for (Listener listener : transform(loadBalancer.getListenerDescriptions(), GET_LISTENER)) {
                    if (!serviceExistsForListener(listener, descriptor.getProperties())) {
                        valid = false;
                        log.warn("load balancer %s listener %s does not match service %s", elbName, listener, descriptor);
                    }
                }
                if (valid) {
                    instances.add(instanceId);
                }
            }

            // get registered instances
            Set<String> registeredInstances = newHashSet(transform(loadBalancer.getInstances(), GET_INSTANCE_ID));

            // add new instances to load balancer
            Collection<String> addInstances = difference(instances, registeredInstances);
            if (!addInstances.isEmpty()) {
                registerInstances(elbName, addInstances);
            }

            // remove missing instances from load balancer
            Collection<String> removeInstances = difference(registeredInstances, instances);
            if (!removeInstances.isEmpty()) {
                deregisterInstances(elbName, removeInstances);
            }
        }
    }

    private static boolean serviceExistsForListener(Listener listener, Map<String, String> properties)
    {
        String protocol = listener.getInstanceProtocol().toLowerCase();
        if ((!protocol.equals("http")) && (!protocol.equals("https"))) {
            return false;
        }
        URI uri = extractUri(properties.get(protocol));
        return (uri != null) && (uri.getPort() == listener.getInstancePort());
    }

    private void registerInstances(String elbName, Collection<String> instanceIds)
    {
        List<Instance> instances = copyOf(transform(instanceIds, NEW_INSTANCE));
        log.debug("adding instances to load balancer: %s: %s", elbName, instanceIds);
        elbClient.registerInstancesWithLoadBalancer(new RegisterInstancesWithLoadBalancerRequest(elbName, instances));
        log.info("added instances to load balancer: %s: %s", elbName, instanceIds);
    }

    private void deregisterInstances(String elbName, Collection<String> instanceIds)
    {
        List<Instance> instances = copyOf(transform(instanceIds, NEW_INSTANCE));
        log.debug("removing instances from load balancer: %s: %s", elbName, instanceIds);
        elbClient.deregisterInstancesFromLoadBalancer(new DeregisterInstancesFromLoadBalancerRequest(elbName, instances));
        log.info("removed instances from load balancer: %s: %s", elbName, instanceIds);
    }

    private static String extractEc2InstanceId(String location)
    {
        try {
            return Ec2Location.valueOf(location).getInstanceId();
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static URI extractUri(String uri)
    {
        try {
            return new URI(uri);
        }
        catch (Exception e) {
            return null;
        }
    }

    private static Function<ListenerDescription, Listener> GET_LISTENER = new Function<ListenerDescription, Listener>()
    {
        @Override
        public Listener apply(ListenerDescription input)
        {
            return input.getListener();
        }
    };

    private static Function<Instance, String> GET_INSTANCE_ID = new Function<Instance, String>()
    {
        @Override
        public String apply(Instance input)
        {
            return input.getInstanceId();
        }
    };

    private static Function<String, Instance> NEW_INSTANCE = new Function<String, Instance>()
    {

        @Override
        public Instance apply(String input)
        {
            return new Instance(input);
        }
    };
}
