package dev.nexus.bedwars.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tiny debounced WatchService wrapper that powers hot-reload of config.yml / arenas.yml.
 * Writes performed by the plugin itself (GUI / command layer) call {@link #suppress}
 * first, so the three management layers never fight each other.
 */
public final class FileWatcher implements AutoCloseable {

    private final Path directory;
    private final Logger logger;
    private final Map<String, Runnable> handlers = new ConcurrentHashMap<>();
    private final Map<String, Long> suppressedUntil = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread thread;

    public FileWatcher(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
    }

    public void register(String fileName, Runnable onChange) {
        handlers.put(fileName, onChange);
    }

    public void suppress(String fileName, long millis) {
        suppressedUntil.put(fileName, System.currentTimeMillis() + millis);
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "NexusBedwars-FileWatcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        try (WatchService service = FileSystems.getDefault().newWatchService()) {
            directory.register(service, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
            while (running) {
                WatchKey key = service.take();
                Thread.sleep(250); // debounce editors that write in several chunks
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (!(event.context() instanceof Path changed)) continue;
                    String name = changed.getFileName().toString();
                    Runnable handler = handlers.get(name);
                    if (handler == null) continue;
                    if (System.currentTimeMillis() < suppressedUntil.getOrDefault(name, 0L)) continue;
                    try {
                        handler.run();
                    } catch (RuntimeException ex) {
                        logger.warning("Hot-reload of " + name + " failed: " + ex.getMessage());
                    }
                }
                if (!key.reset()) break;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            logger.warning("File watcher stopped: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) thread.interrupt();
    }
}
