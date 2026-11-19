package dev.nexus.bedwars.db;

import dev.nexus.bedwars.config.ConfigService;
import redis.clients.jedis.*;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Redis Pub/Sub bridge for network-wide stat consistency.
 *
 * <p>After a profile is flushed to the shared SQL database the persisted changes are published as
 * {@code serverId;uuid;WINS=1,ELO=-12,WINSTREAK=0}. Every other server that has that player cached
 * (lobby servers showing stats, a second arena server …) applies the change to its in-memory profile,
 * so scoreboards/holograms update instantly without hitting the database.
 */
public final class RedisSync implements AutoCloseable {

    private final ConfigService cfg;
    private final Logger log;
    private final String serverId;
    private final String channel;
    private final BiConsumer<UUID, Map<Stat, Long>> remoteHandler;

    private JedisPool pool;
    private volatile boolean running;
    private Thread subscriberThread;
    private volatile Jedis subscriberConnection;
    private volatile JedisPubSub pubSub;

    public RedisSync(ConfigService cfg, Logger log, BiConsumer<UUID, Map<Stat, Long>> remoteHandler) {
        this.cfg = cfg;
        this.log = log;
        this.serverId = cfg.getString("server-id", "bw-1");
        this.channel = cfg.getString("redis.channel", "nexusbw:stats");
        this.remoteHandler = remoteHandler;
    }

    public void start() {
        String password = cfg.getString("redis.password", "");
        HostAndPort address = new HostAndPort(cfg.getString("redis.host", "127.0.0.1"), cfg.getInt("redis.port", 6379));
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .ssl(cfg.getBoolean("redis.ssl", false))
                .connectionTimeoutMillis(5000);
        if (!password.isBlank()) builder.password(password);

        pool = new JedisPool(new JedisPoolConfig(), address, builder.socketTimeoutMillis(3000).build());
        // subscriber needs a blocking socket → timeout 0
        DefaultJedisClientConfig subscriberConfig = DefaultJedisClientConfig.builder()
                .ssl(cfg.getBoolean("redis.ssl", false))
                .connectionTimeoutMillis(5000)
                .socketTimeoutMillis(0)
                .password(password.isBlank() ? null : password)
                .build();

        running = true;
        subscriberThread = new Thread(() -> subscribeLoop(address, subscriberConfig), "NexusBedwars-Redis-Sub");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        log.info("Redis stat sync enabled on channel '" + channel + "'");
    }

    private void subscribeLoop(HostAndPort address, JedisClientConfig config) {
        while (running) {
            try (Jedis jedis = new Jedis(address, config)) {
                subscriberConnection = jedis;
                pubSub = new JedisPubSub() {
                    @Override
                    public void onMessage(String ch, String message) {
                        handle(message);
                    }
                };
                jedis.subscribe(pubSub, channel); // blocks until unsubscribed / connection lost
            } catch (Exception e) {
                if (running) {
                    log.warning("Redis subscriber lost connection: " + e.getMessage() + " – retrying in 5s");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void handle(String message) {
        try {
            String[] parts = message.split(";", 3);
            if (parts.length < 3 || parts[0].equals(serverId)) return; // ignore own echo
            UUID uuid = UUID.fromString(parts[1]);
            Map<Stat, Long> changes = new EnumMap<>(Stat.class);
            for (String entry : parts[2].split(",")) {
                String[] kv = entry.split("=");
                Stat.parse(kv[0]).ifPresent(s -> changes.put(s, Long.parseLong(kv[1])));
            }
            if (!changes.isEmpty()) remoteHandler.accept(uuid, changes);
        } catch (RuntimeException e) {
            log.fine("Ignoring malformed Redis message: " + message);
        }
    }

    /** Fire-and-forget; must be called from an async thread. */
    public void publish(UUID uuid, Map<Stat, Long> changes) {
        if (pool == null || changes.isEmpty()) return;
        StringBuilder sb = new StringBuilder(serverId).append(';').append(uuid).append(';');
        boolean first = true;
        for (Map.Entry<Stat, Long> e : changes.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey().name()).append('=').append(e.getValue());
            first = false;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, sb.toString());
        } catch (Exception e) {
            log.warning("Redis publish failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            if (pubSub != null && pubSub.isSubscribed()) pubSub.unsubscribe();
        } catch (Exception ignored) {
            // connection already gone
        }
        if (subscriberThread != null) subscriberThread.interrupt();
        if (pool != null) pool.close();
    }
}
