/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.resource;

import io.vertx.core.Future;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.Integer.parseInt;

class KafkaSorted {

    private static final Logger log = LogManager.getLogger(KafkaSorted.class.getName());

    private final AdminClient ac;
    private final Future<Collection<TopicDescription>> descriptions;

    KafkaSorted(AdminClient ac) {
        this.ac = ac;
        // 1. Get all topic names
        Future<Set<String>> topicNames = topicNames();
        // 2. Get topic descriptions
        descriptions = topicNames.compose(names -> {
            log.debug("Got {} topic names", names.size());
            log.trace("Topic names {}", names);
            return describeTopics(names);
        });
    }

    /**
     * Determine whether the given broker can be rolled without affecting
     * producers with acks=all publishing to topics with a {@code min.in.sync.replicas}.
     */
    Future<Boolean> canRoll(int broker) {
        log.debug("Determining whether broker {} can be rolled", broker);
        return canRollBroker(descriptions, broker);
    }

    private Future<Boolean> canRollBroker(Future<Collection<TopicDescription>> descriptions, int broker) {
        Future<List<TopicDescription>> topicsOnBroker = descriptions
                .compose(tds -> {

                    log.debug("Got {} topic descriptions", tds.size());
                    try {
                        return Future.succeededFuture(groupTopicsByBroker(tds).getOrDefault(broker, Collections.emptyList()));
                    } catch (Throwable t) {
                        log.debug("wtf", t);
                        return Future.failedFuture(t);
                    }
                }).recover(error -> {
                    log.warn(error);
                    return Future.failedFuture(error);
                });

        // 4. Get topic configs (for those on $broker)
        Future<Map<String, Config>> configs = topicsOnBroker
                .compose(td -> topicConfigs(td.stream().map(t -> t.name()).collect(Collectors.toList())));

        // 5. join
        return configs.map(x -> {
            log.debug("join");
            Collection<TopicDescription> tds = topicsOnBroker.result();
            Map<String, Config> nameToConfig = x;
            boolean b = tds.stream().noneMatch(
                td -> {
                    if (wouldAffectAvailability(broker, nameToConfig, td)) {
                        return true;
                    }
                    return false;
                });
            log.debug("{}", b);
            return b;
        }).recover(error -> {
            log.warn(error);
            return Future.failedFuture(error);
        });
    }

    private boolean wouldAffectAvailability(int broker, Map<String, Config> nameToConfig, TopicDescription td) {
        Config config = nameToConfig.get(td.name());
        ConfigEntry minIsrConfig = config.get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG);
        int minIsr;
        if (minIsrConfig != null && minIsrConfig.value() != null) {
            minIsr = parseInt(minIsrConfig.value());
        } else {
            minIsr = -1;
        }

        for (TopicPartitionInfo pi : td.partitions()) {
//            if (pi.leader() == null
//                || pi.leader().equals(Node.noNode())) {
//                if (contains(pi.replicas(), broker)) {
//                    log.debug("{}/{} has no leader and broker {} has a replica, so avoiding rolling",
//                            td.name(), pi.partition(), broker);
//                    return true;
//                }
//            } else {
            List<Node> isr = pi.isr();
            if (minIsr >= 0) {
                if (isr.size() < minIsr) {
                    if (contains(pi.replicas(), broker)) {
                        log.debug("{}/{} is below its min ISR of {} and broker {} has a replica, " +
                                        "so avoiding rolling",
                                td.name(), pi.partition(), minIsr, broker);
                        return true;
                    }
                } else if (isr.size() == minIsr) {
                    if (contains(isr, broker)) {
                        log.debug("rolling broker {} would put {}/{} below its min ISR of {}",
                                broker, td.name(), pi.partition(), minIsr);
                        return true;
                    }
                }
            }
//            }
        }
        return false;
    }

    private boolean contains(List<Node> isr, int broker) {
        return isr.stream().anyMatch(node -> node.id() == broker);
    }

    private Future<Map<String, Config>> topicConfigs(Collection<String> topicNames) {
        log.debug("Getting topic configs for {} topics", topicNames.size());
        List<ConfigResource> configs = topicNames.stream()
                .map((String topicName) -> new ConfigResource(ConfigResource.Type.TOPIC, topicName))
                .collect(Collectors.toList());
        Future<Map<String, Config>> f = Future.future();
        ac.describeConfigs(configs).all().whenComplete((topicNameToConfig, error) -> {
            if (error != null) {
                f.fail(error);
            } else {
                log.debug("Got topic configs for {} topics", topicNames.size());
                f.complete(topicNameToConfig.entrySet().stream()
                        .collect(Collectors.toMap(
                            entry -> entry.getKey().name(),
                            entry -> entry.getValue())));
            }
        });
        return f;
    }

    private Map<Integer, List<TopicDescription>> groupTopicsByBroker(Collection<TopicDescription> tds) {
        Map<Integer, List<TopicDescription>> byBroker = new HashMap<>();
        for (TopicDescription td : tds) {
            log.debug("{}", td);
            for (TopicPartitionInfo pd : td.partitions()) {
                for (Node broker : pd.replicas()) {
                    List<TopicDescription> topicPartitionInfos = byBroker.get(broker.id());
                    if (topicPartitionInfos == null) {
                        topicPartitionInfos = new ArrayList<>();
                        byBroker.put(broker.id(), topicPartitionInfos);
                    }
                    topicPartitionInfos.add(td);
                }
            }
        }
        log.debug("returning");
        return byBroker;
    }

    private Map<Node, List<TopicPartitionInfo>> groupReplicasByBroker(Collection<TopicDescription> tds) {
        Map<Node, List<TopicPartitionInfo>> byBroker = new HashMap<>();
        for (TopicDescription td : tds) {
            for (TopicPartitionInfo pd : td.partitions()) {
                for (Node broker : pd.replicas()) {
                    List<TopicPartitionInfo> topicPartitionInfos = byBroker.get(broker);
                    if (topicPartitionInfos == null) {
                        topicPartitionInfos = new ArrayList<>();
                        byBroker.put(broker, topicPartitionInfos);
                    }
                    topicPartitionInfos.add(pd);
                }
            }
        }
        return byBroker;
    }

    private Future<Collection<TopicDescription>> describeTopics(Set<String> names) {
        Future<Collection<TopicDescription>> descFuture = Future.future();
        ac.describeTopics(names).all()
                .whenComplete((tds, error) -> {
                    if (error != null) {
                        descFuture.fail(error);
                    } else {
                        log.debug("Got topic descriptions for {} topics", tds.size());
                        descFuture.complete(tds.values());
                    }
                });
        return descFuture;
    }

    private Future<Set<String>> topicNames() {
        Future<Set<String>> namesFuture = Future.future();
        ac.listTopics(new ListTopicsOptions().listInternal(true)).names()
                .whenComplete((names, error) -> {
                    if (error != null) {
                        namesFuture.fail(error);
                    } else {
                        log.debug("Got {} topic names", names.size());
                        namesFuture.complete(names);
                    }
                });
        return namesFuture;
    }
}
