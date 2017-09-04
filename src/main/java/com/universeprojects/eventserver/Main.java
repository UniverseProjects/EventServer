package com.universeprojects.eventserver;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.spi.cluster.ClusterManager;
import io.vertx.spi.cluster.hazelcast.HazelcastClusterManager;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class Main {
    public static final String CONFIG_HAZELCAST_GROUP_NAME = "hazelcast.group.name";
    public static final String CONFIG_HAZELCAST_GROUP_PASSWORD = "hazelcast.group.password";
    public static final String CONFIG_HAZELCAST_MANAGEMENT_URL = "hazelcast.management.url";
    public static final String CONFIG_CLUSTER_HOST = "cluster.host";

    private final Logger log = LoggerFactory.getLogger(getClass());

    public static void main(String[] args) {
        //No idea where this is set but it can causes an exception
        //because its format can be invalid
        System.clearProperty("io.netty.machineId");

        new Main().run(args);
    }

    @SuppressWarnings("UnusedParameters")
    public void run(String[] args) {
        VertxOptions options = createVertxOptions();
        Vertx.clusteredVertx(options, (res) -> {
            if (res.succeeded()) {
                Vertx vertx = res.result();
                deployVerticle(vertx);
            } else {
                log.error("Error starting clustered Vertx", res.cause());
            }
        });
    }

    private HazelcastClusterManager createHazelcastClusterManager() {
        HazelcastClusterManager clusterManager = new HazelcastClusterManager();
        com.hazelcast.config.Config hazelcastConfig = clusterManager.loadConfig();
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
        clusterManager.setConfig(hazelcastConfig);
        return clusterManager;
    }

    private void deployVerticle(Vertx vertx) {
        vertx.deployVerticle(new EventServerVerticle());
    }

    private VertxOptions createVertxOptions() {
        VertxOptions options = new VertxOptions();
        options.setClustered(true);
        ClusterManager clusterManager = createHazelcastClusterManager();
        options.setClusterManager(clusterManager);
        String clusterHost = Config.getString(CONFIG_CLUSTER_HOST, null);
        if (clusterHost == null) {
            clusterHost = pickHostAddress();
        }
        options.setClusterHost(clusterHost);
        return options;
    }

    private String pickHostAddress() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                Enumeration<InetAddress> e = ni.getInetAddresses();
                while (e.hasMoreElements()) {
                    InetAddress inetAddress = e.nextElement();
                    if (inetAddress instanceof Inet6Address) {
                        continue;
                    }
                    if (!inetAddress.isLoopbackAddress()) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
            return "localhost";
        } catch (SocketException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
