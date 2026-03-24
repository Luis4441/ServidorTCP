package balancer;

import server.RestartPolicy;
import server.TCPServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Balanceador de carga Round Robin.
 * Mantiene n TCPServer. Asigna clientes en turno rotativo.
 * Si un servidor cae, lo marca no disponible y redirige al siguiente activo.
 * Devuelve {puerto, índice} para que el cliente sepa a qué servidor fue asignado.
 */
public class Loadbalancer {

    public static class ServerEntry {
        public final String    id;
        public final TCPServer server;
        public final int       port;
        public final int       index;
        public volatile boolean available = false;

        public ServerEntry(String id, TCPServer server, int port, int index) {
            this.id     = id;
            this.server = server;
            this.port   = port;
            this.index  = index;
        }
    }

    private final List<ServerEntry>             entries = new ArrayList<>();
    private final AtomicInteger                 rrIndex = new AtomicInteger(0);
    private final Consumer<String>              log;
    private BiConsumer<ServerEntry, String>     onStatusChange;

    public Loadbalancer(Consumer<String> log) {
        this.log = log;
    }

    public void setOnStatusChange(BiConsumer<ServerEntry, String> cb) {
        this.onStatusChange = cb;
    }

    /** Añade un servidor al pool. */
    public void addServer(String id, int port, RestartPolicy policy) {
        int idx = entries.size();
        TCPServer server = new TCPServer(port, policy,
                msg -> log.accept("[" + id + "] " + msg));

        ServerEntry entry = new ServerEntry(id, server, port, idx);
        entries.add(entry);

        server.setOnStarted(() -> {
            entry.available = true;
            log.accept("✔ [" + id + "] ONLINE  (puerto " + port + ")");
            notify(entry, "ONLINE");
        });

        server.setOnStopped(() -> {
            entry.available = false;
            log.accept("◼ [" + id + "] OFFLINE  (puerto " + port + ")");
            notify(entry, "OFFLINE");
        });
    }

    public void startAll() {
        for (ServerEntry e : entries) e.server.start();
    }

    public void stopAll() {
        for (ServerEntry e : entries) e.server.stop();
    }

    public void crashAll() {
        for (ServerEntry e : entries) e.server.crash();
    }

    public void crashServer(String id) {
        entries.stream().filter(e -> e.id.equals(id)).findFirst()
                .ifPresent(e -> { log.accept("⚡ Fallo simulado en [" + id + "]..."); e.server.crash(); });
    }

    public void stopServer(String id) {
        entries.stream().filter(e -> e.id.equals(id)).findFirst()
                .ifPresent(e -> { log.accept("■ Detención de [" + id + "]..."); e.server.stop(); });
    }

    /**
     * Round Robin: devuelve {puerto, índice} del próximo servidor disponible.
     * Devuelve null si no hay ninguno.
     */
    public int[] nextAvailable() {
        int total = entries.size();
        if (total == 0) return null;

        int start = rrIndex.getAndUpdate(i -> (i + 1) % total);
        for (int i = 0; i < total; i++) {
            ServerEntry e = entries.get((start + i) % total);
            if (e.available) {
                log.accept("⇒ Round Robin → [" + e.id + "] puerto " + e.port);
                return new int[]{ e.port, e.index };
            }
        }
        return null;
    }

    public List<ServerEntry> getEntries()  { return entries; }
    public int availableCount() {
        return (int) entries.stream().filter(e -> e.available).count();
    }

    private void notify(ServerEntry entry, String status) {
        if (onStatusChange != null) onStatusChange.accept(entry, status);
    }
}