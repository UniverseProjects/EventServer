package com.universeprojects.eventserver;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {
    public static final String CONFIG_HAZELCAST_GROUP_NAME = "hazelcast.group.name";
    public static final String CONFIG_HAZELCAST_GROUP_PASSWORD = "hazelcast.group.password";
    public static final String CONFIG_HAZELCAST_MANAGEMENT_URL = "hazelcast.management.url";
    public static final String CONFIG_CLUSTER_HOST = "cluster.host";
    public static final String RANCHER_FIND_IP_URL = "http://rancher-metadata/2015-07-25/self/container/primary_ip";
    public static final String CONFIG_QUERY_RANCHER_IP = "cluster.rancher.query";

    private final Logger log = Logger.getGlobal();

    public static void main(String[] args) {
        new Main().run(args);
    }

    @SuppressWarnings("UnusedParameters")
    public void run(String[] args) {
        createVertxOptions((options) ->
                Vertx.clusteredVertx(options, (res) -> {
                    if (res.succeeded()) {
                        Vertx vertx = res.result();
                        deployVerticle(vertx);
                    } else {
                        log.log(Level.SEVERE, "Error starting clustered Vertx", res.cause());
                    }
                }));
    }

    private com.hazelcast.config.Config createHazelcastConfig() {
        com.hazelcast.config.Config hazelcastConfig = new com.hazelcast.config.Config();
        String hazelcastGroupName = Config.getString(CONFIG_HAZELCAST_GROUP_NAME, null);
        if (hazelcastGroupName != null) {
            hazelcastConfig.getGroupConfig().setName(hazelcastGroupName);
        }
        String hazelcastGroupPassword = Config.getString(CONFIG_HAZELCAST_GROUP_PASSWORD, null);
        if (hazelcastGroupPassword != null) {
            hazelcastConfig.getGroupConfig().setPassword(hazelcastGroupPassword);
        }
        String managementUrl = Config.getString(CONFIG_HAZELCAST_MANAGEMENT_URL, null);
        if (managementUrl != null) {
            hazelcastConfig.getManagementCenterConfig().setEnabled(true);
            hazelcastConfig.getManagementCenterConfig().setUrl(managementUrl);
        }
        return hazelcastConfig;
    }

    private void deployVerticle(Vertx vertx) {
        vertx.deployVerticle(new EventServerVerticle());
    }

    private void createVertxOptions(Handler<VertxOptions> optionsHandler) {
        VertxOptions options = new VertxOptions();
        options.setClustered(true);
        com.hazelcast.config.Config hazelCastConfig = createHazelcastConfig();
        HazelcastClusterManager clusterManager = new HazelcastClusterManager(hazelCastConfig);
        options.setClusterManager(clusterManager);
        String clusterHost = Config.getString(CONFIG_CLUSTER_HOST, null);
        if (clusterHost != null) {
            options.setClusterHost(clusterHost);
        } else {
            boolean queryFromRancher = Config.getBoolean(CONFIG_QUERY_RANCHER_IP, false);
            if (queryFromRancher) {
                final Vertx helperVertx = Vertx.vertx();
                final HttpClient httpClient = helperVertx.createHttpClient();
                final HttpClientRequest request = httpClient.get(RANCHER_FIND_IP_URL);
                request.exceptionHandler((exception) -> {
                    log.log(Level.SEVERE, "Cannot query for container IP", exception);
                    helperVertx.close((ignored) -> optionsHandler.handle(options));
                });
                request.handler((response) ->
                        response.bodyHandler((buffer) -> {
                            options.setClusterHost(buffer.toString());
                            helperVertx.close((ignored) -> optionsHandler.handle(options));
                        }));
                request.end();
                return;
            }
        }
        optionsHandler.handle(options);
    }

}
