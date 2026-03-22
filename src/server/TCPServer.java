package server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Servidor TCP.
 * - Acepta múltiples clientes.
 * - Aplica la RestartPolicy cuando se cae o es detenido externamente.
 *
 * CORRECCIONES:
 *  - Bug 1: onStopped solo se invoca al final del ciclo completo (no en cada
 *           cierre temporal durante AUTO_RESTART), evitando que la UI se
 *           desincronice mostrando OFFLINE mientras el servidor se reinicia.
 *  - Bug 1b: onStarted se invoca en cada (re)inicio para que la UI refleje
 *            correctamente el estado ONLINE tras cada reinicio automático.
 */
public class TCPServer {

    private final int              port;
    private final RestartPolicy    policy;
    private final Consumer<String> log;

    private ServerSocket     serverSocket;
    private volatile boolean running = false;
    private volatile boolean stopped = false;  // detenido a propósito por el usuario

    private final List<Socket>    clients = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService pool    = Executors.newCachedThreadPool();

    private Runnable onStarted;
    private Runnable onStopped;

    public TCPServer(int port, RestartPolicy policy, Consumer<String> log) {
        this.port   = port;
        this.policy = policy;
        this.log    = log;
    }

    public void setOnStarted(Runnable r) { this.onStarted = r; }
    public void setOnStopped(Runnable r) { this.onStopped = r; }

    /** Inicia el servidor en un hilo independiente. */
    public void start() {
        stopped = false;
        policy.reset();
        new Thread(this::lifecycle, "Server-Main").start();
    }

    /** Detiene el servidor manualmente (sin reinicio). */
    public void stop() {
        stopped = true;
        running = false;
        closeSocket();
    }

    /**
     * Simula una caída inesperada del servidor.
     * NO marca stopped=true, por lo que la RestartPolicy decide si reiniciar.
     * - NO_RESTART   → el servidor se queda apagado.
     * - AUTO_RESTART → el servidor se reinicia automáticamente tras el delay.
     */
    public void crash() {
        running = false;
        closeSocket();   // cierre brusco, sin tocar 'stopped'
    }

    // ── Ciclo de vida con política de reinicio ────────────────
    //
    // FIX Bug 1: onStopped se mueve FUERA de listen() y se llama
    // únicamente aquí, al terminar el ciclo completo. Así la UI no
    // parpadea a OFFLINE en cada reinicio automático.
    private void lifecycle() {
        do {
            listen();                          // bloquea hasta que el servidor se cae

            if (stopped) break;               // parada manual → no reiniciar

            if (policy.shouldRestart()) {
                policy.countRestart();
                int d = policy.getDelaySeconds();
                log.accept("⟳ Reiniciando en " + d + "s  (intento "
                        + policy.getRestartCount() + "/" + policy.getMaxRestarts() + ")...");
                try { Thread.sleep(policy.getDelayMs()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            } else {
                log.accept("✖ Política NO_RESTART: el servidor no se reiniciará.");
                break;
            }
        } while (!stopped);

        // ← onStopped se llama UNA SOLA VEZ al final del ciclo completo
        if (onStopped != null) onStopped.run();
    }

    // ── Escucha activa (un "ciclo" de vida del socket) ────────
    //
    // FIX Bug 1b: onStarted se invoca aquí (dentro de listen) para que
    // se dispare en CADA arranque/reinicio, no solo el primero.
    // Ya NO se llama a onStopped desde el finally; lo gestiona lifecycle().
    private void listen() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            log.accept("✔ Servidor escuchando en puerto " + port);

            // Notificar UI que está ONLINE (también en reinicios automáticos)
            if (onStarted != null) onStarted.run();

            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    clients.add(client);
                    log.accept("+ Cliente conectado: "
                            + client.getInetAddress().getHostAddress()
                            + ":" + client.getPort()
                            + "  (total: " + clients.size() + ")");
                    pool.submit(() -> handleClient(client));
                } catch (SocketException e) {
                    if (running) log.accept("! Error aceptando: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!stopped) log.accept("! Error servidor: " + e.getMessage());
        } finally {
            running = false;
            disconnectAll();
            log.accept("◼ Servidor detenido.");
            // ← onStopped ya NO se llama aquí
        }
    }

    private void handleClient(Socket socket) {
        try (BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter    out = new PrintWriter(socket.getOutputStream(), true)) {
            String line;
            while ((line = in.readLine()) != null) {
                log.accept("← [" + socket.getPort() + "] " + line);
                out.println("ACK: " + line);
            }
        } catch (IOException ignored) {
        } finally {
            clients.remove(socket);
            log.accept("- Cliente desconectado [" + socket.getPort()
                    + "]  (total: " + clients.size() + ")");
        }
    }

    private void disconnectAll() {
        synchronized (clients) {
            for (Socket s : clients) try { s.close(); } catch (IOException ignored) {}
            clients.clear();
        }
    }

    private void closeSocket() {
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    public boolean isRunning()   { return running; }
    public int     clientCount() { return clients.size(); }
}