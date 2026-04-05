package balancer;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/**
 * Balanceador de carga TCP con standby genérico (cubre cualquier primario).
 *
 * ── Protocolo ────────────────────────────────────────────────────────────
 *   REGISTER <puerto>          → primario se registra / vuelve (failback)
 *   REGISTER_STANDBY <puerto>  → standby genérico (sin primario fijo)
 *   UNREGISTER <puerto>        → servidor cae
 *   CLIENT_COUNT <puerto> <n>  → reporte de clientes
 *   NEXT_PORT                  → siguiente puerto libre
 *   NEXT_SERVER                → round-robin
 *   REDIRECT_TARGET <puerto>   → ¿a qué puerto conectarse ahora?
 *
 * ── Failover genérico ─────────────────────────────────────────────────────
 *   Cuando un primario cae → se busca cualquier standby libre (status=STANDBY).
 *   El standby pasa a ACTIVE y se registra redirects[primario] = standby.
 *   El standby guarda en coveringPort el primario que está cubriendo.
 *
 * ── Failback ──────────────────────────────────────────────────────────────
 *   Cuando el primario vuelve (REGISTER de un puerto que estaba OFFLINE):
 *   - Se elimina redirects[primario].
 *   - El standby que lo cubría vuelve a STANDBY y coveringPort = -1.
 *   - Queda libre para cubrir al siguiente primario que caiga.
 */
public class LoadBalancer {

    public static final int REGISTRY_PORT = 19999;

    public enum ServerStatus { ONLINE, OFFLINE, STANDBY, ACTIVE }

    public static class ServerEntry {
        public final int             port;
        public volatile ServerStatus status      = ServerStatus.ONLINE;
        public volatile int          clientCount = 0;
        public volatile int          coveringPort = -1; // primario que cubre ahora (solo si ACTIVE)

        public ServerEntry(int port) { this.port = port; }

        public boolean servesClients() {
            return status == ServerStatus.ONLINE || status == ServerStatus.ACTIVE;
        }

        public boolean isStandbyType() {
            return status == ServerStatus.STANDBY || status == ServerStatus.ACTIVE;
        }
    }

    private final ConcurrentMap<Integer, ServerEntry> servers   = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Integer>     redirects = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinIdx = new AtomicInteger(0);
    private final AtomicInteger nextPort      = new AtomicInteger(8080);
    private final Consumer<String> log;

    private ServerSocket     registrySocket;
    private volatile boolean running = false;
    private Runnable         onUpdate;

    public LoadBalancer(Consumer<String> log) { this.log = log; }

    public void setOnUpdate(Runnable r) { this.onUpdate = r; }

    public void start() {
        running = true;
        new Thread(this::listenRegistry, "Balancer-Registry").start();
        log.accept("⚖ Balanceador activo — registro en puerto " + REGISTRY_PORT);
    }

    public void stop() {
        running = false;
        try { if (registrySocket != null) registrySocket.close(); } catch (IOException ignored) {}
    }

    private void listenRegistry() {
        try {
            registrySocket = new ServerSocket(REGISTRY_PORT);
            while (running) {
                try {
                    Socket conn = registrySocket.accept();
                    new Thread(() -> handleCommand(conn), "Registry-Cmd").start();
                } catch (SocketException e) {
                    if (running) log.accept("! Registry error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) log.accept("! No se pudo iniciar registro: " + e.getMessage());
        }
    }

    private void handleCommand(Socket conn) {
        try (BufferedReader in  = new BufferedReader(new InputStreamReader(conn.getInputStream()));
             PrintWriter    out = new PrintWriter(conn.getOutputStream(), true)) {

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                String[] parts = line.split(" ");

                switch (parts[0]) {

                    case "REGISTER": {
                        if (parts.length < 2) break;
                        int port = Integer.parseInt(parts[1]);
                        ServerEntry entry = servers.get(port);

                        if (entry == null) {
                            // Primera vez: es un primario nuevo
                            entry = new ServerEntry(port);
                            entry.status = ServerStatus.ONLINE;
                            servers.put(port, entry);
                            nextPort.updateAndGet(cur -> Math.max(cur, port + 1));
                            log.accept("+ Primario registrado en puerto " + port);
                        } else if (entry.isStandbyType()) {
                            // Era standby y ahora intenta registrarse como primario — ignorar
                            log.accept("⚠ Puerto " + port + " es standby, ignorando REGISTER");
                            out.println("OK");
                            break;
                        } else {
                            // Primario que vuelve después de caída
                            boolean wasFailed = entry.status == ServerStatus.OFFLINE;
                            entry.status = ServerStatus.ONLINE;
                            entry.clientCount = 0;
                            if (wasFailed) {
                                log.accept("↑ Primario :" + port + " recuperado — failback");
                                failback(entry);
                            }
                        }
                        notifyUpdate();
                        out.println("OK");
                        break;
                    }

                    case "REGISTER_STANDBY": {
                        // FIXED: ya no necesita puerto del primario — es genérico
                        if (parts.length < 2) break;
                        int standbyPort = Integer.parseInt(parts[1]);

                        ServerEntry standby = servers.get(standbyPort);
                        if (standby == null) {
                            standby = new ServerEntry(standbyPort);
                            servers.put(standbyPort, standby);
                        }
                        standby.status      = ServerStatus.STANDBY;
                        standby.coveringPort = -1;
                        nextPort.updateAndGet(cur -> Math.max(cur, standbyPort + 1));

                        log.accept("⏸ Standby genérico registrado en puerto :" + standbyPort);
                        notifyUpdate();
                        out.println("OK");
                        break;
                    }

                    case "UNREGISTER": {
                        if (parts.length < 2) break;
                        int port = Integer.parseInt(parts[1]);
                        ServerEntry entry = servers.get(port);
                        if (entry != null) {
                            boolean wasServing = entry.servesClients();
                            boolean isPrimary  = !entry.isStandbyType();
                            entry.status = ServerStatus.OFFLINE;
                            entry.clientCount = 0;

                            if (wasServing && isPrimary) {
                                log.accept("↓ Primario :" + port + " offline — buscando standby...");
                                failover(entry);
                            } else if (!isPrimary) {
                                // El standby cayó: limpiar la redirección que cubría
                                if (entry.coveringPort > 0) {
                                    redirects.remove(entry.coveringPort);
                                    log.accept("↓ Standby :" + port
                                            + " offline — redirección de :" + entry.coveringPort + " eliminada");
                                    entry.coveringPort = -1;
                                } else {
                                    log.accept("↓ Standby :" + port + " offline");
                                }
                            }
                        }
                        notifyUpdate();
                        out.println("OK");
                        break;
                    }
                    case "STATUS": {
                        if (parts.length < 2) { out.println("UNKNOWN"); break; }
                        int port = Integer.parseInt(parts[1]);
                        ServerEntry e = servers.get(port);
                        if (e == null) { out.println("UNKNOWN"); break; }
                        out.println(e.status.name()); // "ONLINE", "OFFLINE", "STANDBY", "ACTIVE"
                        break;
                    }

                    case "CLIENT_COUNT": {
                        if (parts.length < 3) break;
                        int port  = Integer.parseInt(parts[1]);
                        int count = Integer.parseInt(parts[2]);
                        ServerEntry e = servers.get(port);
                        if (e != null) { e.clientCount = count; notifyUpdate(); }
                        out.println("OK");
                        break;
                    }

                    case "NEXT_PORT":
                        out.println(nextPort.getAndIncrement());
                        break;

                    case "NEXT_SERVER":
                        out.println(nextServer());
                        break;

                    case "REDIRECT_TARGET": {
                        if (parts.length < 2) { out.println(-1); break; }
                        int asked = Integer.parseInt(parts[1]);
                        out.println(resolveTarget(asked));
                        break;
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    // ── Failover: primario cae → buscar cualquier standby libre ──────────
    private synchronized void failover(ServerEntry primary) {
        // Buscar un standby libre (status=STANDBY, no está cubriendo a nadie)
        ServerEntry standby = servers.values().stream()
                .filter(e -> e.status == ServerStatus.STANDBY && e.coveringPort < 0)
                .findFirst()
                .orElse(null);

        if (standby == null) {
            log.accept("⚠ No hay standby disponible para cubrir :" + primary.port);
            return;
        }

        standby.status      = ServerStatus.ACTIVE;
        standby.coveringPort = primary.port;
        redirects.put(primary.port, standby.port);

        log.accept("⚡ FAILOVER :" + primary.port
                + " → standby :" + standby.port + " ACTIVADO");
        notifyUpdate();
    }

    // ── Failback: primario vuelve → liberar standby ───────────────────────
    private synchronized void failback(ServerEntry primary) {
        // Buscar el standby que estaba cubriendo a este primario
        ServerEntry standby = servers.values().stream()
                .filter(e -> e.isStandbyType() && e.coveringPort == primary.port)
                .findFirst()
                .orElse(null);

        redirects.remove(primary.port);

        if (standby != null) {
            standby.status      = ServerStatus.STANDBY;
            standby.coveringPort = -1;
            standby.clientCount  = 0;
            log.accept("↩ FAILBACK :" + primary.port + " retoma el servicio  |  "
                    + "standby :" + standby.port + " → libre (STANDBY)");
        }
        notifyUpdate();
    }

    // ── Resolución de puerto destino ─────────────────────────────────────
    public int resolveTarget(int askedPort) {
        Integer redirect = redirects.get(askedPort);
        if (redirect != null) {
            ServerEntry t = servers.get(redirect);
            if (t != null && t.servesClients()) return t.port;
        }
        ServerEntry entry = servers.get(askedPort);
        if (entry != null && entry.servesClients()) return askedPort;
        return -1;
    }

    // ── Round-robin (solo primarios ONLINE, o ACTIVE si no hay ninguno) ──
    public int nextServer() {
        List<ServerEntry> pool = new ArrayList<>();
        for (ServerEntry e : servers.values())
            if (e.status == ServerStatus.ONLINE) pool.add(e);

        if (pool.isEmpty())
            for (ServerEntry e : servers.values())
                if (e.status == ServerStatus.ACTIVE) pool.add(e);

        if (pool.isEmpty()) return -1;
        pool.sort(Comparator.comparingInt(e -> e.port));
        return pool.get(Math.abs(roundRobinIdx.getAndIncrement() % pool.size())).port;
    }

    public Collection<ServerEntry> getServers()  { return servers.values(); }
    public Map<Integer, Integer>   getRedirects() { return Collections.unmodifiableMap(redirects); }

    public int onlineCount() {
        return (int) servers.values().stream().filter(ServerEntry::servesClients).count();
    }

    public int totalClients() {
        return servers.values().stream()
                .filter(ServerEntry::servesClients)
                .mapToInt(e -> e.clientCount).sum();
    }

    public boolean isRunning() { return running; }

    private void notifyUpdate() { if (onUpdate != null) onUpdate.run(); }
}