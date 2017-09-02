package com.universeprojects.eventserver;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {
    public static final String CONFIG_HAZELCAST_GROUP_NAME = "hazelcast.group.name";
    public static final String CONFIG_HAZELCAST_GROUP_PASSWORD = "hazelcast.group.password";
    public static final String CONFIG_HAZELCAST_MANAGEMENT_URL = "hazelcast.management.url";
    private final Logger log = Logger.getGlobal();
    public static void main(String[] args) {
        new Main().run(args);
    }

    @SuppressWarnings("UnusedParameters")
    public void run(String[] args) {
        VertxOptions options = createVertxOptions();
        com.hazelcast.config.Config hazelCastConfig = createHazelcastConfig();
        HazelcastClusterManager clusterManager = new HazelcastClusterManager(hazelCastConfig);
        options.setClusterManager(clusterManager);
        Vertx.clusteredVertx(options, (res) -> {
            if (res.succeeded()) {
                Vertx vertx = res.result();
                deployVerticle(vertx);
            } else {
                log.log(Level.SEVERE, "Error starting clustered Vertx", res.cause());
            }
        });
    }

    private com.hazelcast.config.Config createHazelcastConfig() {
        com.hazelcast.config.Config hazelcastConfig = new com.hazelcast.config.Config();
        String hazelcastGroupName = Config.getString(CONFIG_HAZELCAST_GROUP_NAME, null);
        if(hazelcastGroupName != null) {
            hazelcastConfig.getGroupConfig().setName(hazelcastGroupName);
        }
        String hazelcastGroupPassword = Config.getString(CONFIG_HAZELCAST_GROUP_PASSWORD, null);
        if(hazelcastGroupPassword != null) {
            hazelcastConfig.getGroupConfig().setPassword(hazelcastGroupPassword);
        }
        String managementUrl = Config.getString(CONFIG_HAZELCAST_MANAGEMENT_URL, null);
        if(managementUrl != null) {
            hazelcastConfig.getManagementCenterConfig().setEnabled(true);
            hazelcastConfig.getManagementCenterConfig().setUrl(managementUrl);
        }
        return hazelcastConfig;
    }

    private void deployVerticle(Vertx vertx) {
        vertx.deployVerticle(new EventServerVerticle());
    }

    private VertxOptions createVertxOptions() {
        VertxOptions options = new VertxOptions();
        options.setClustered(true);
        return options;
    }

}
