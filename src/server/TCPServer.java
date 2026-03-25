package server;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Servidor TCP — escucha una sola vez y notifica cuando cae.
 *
 * La lógica de reinicio (cuántas veces, cada cuánto) la maneja
 * ServerApp, igual que ClientApp maneja los reintentos de TCPClient.
 * TCPServer solo sabe arrancar, aceptar clientes, y parar.
 *
 * Métodos clave:
 *   start()  → arranca en hilo propio, llama onStarted al estar listo.
 *   stop()   → parada limpia (stopped=true), llama onStopped al terminar.
 *   crash()  → caída brusca (stopped=false), llama onStopped al terminar.
 *              ServerApp distingue stop vs crash para decidir si reabrir.
 */
public class TCPServer {

    private final int              port;
    private final Consumer<String> log;

    private ServerSocket     serverSocket;
    private volatile boolean running = false;
    private volatile boolean stopped = false;

    private final List<Socket>    clients = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService pool    = Executors.newCachedThreadPool();

    private Runnable onStarted;
    private Runnable onStopped;

    public TCPServer(int port, Consumer<String> log) {
        this.port = port;
        this.log  = log;
    }

    public void setOnStarted(Runnable r) { this.onStarted = r; }
    public void setOnStopped(Runnable r) { this.onStopped = r; }

    /** Arranca el servidor en un hilo independiente. */
    public void start() {
        stopped = false;
        new Thread(this::listen, "Server-Listen").start();
    }

    /** Parada limpia — stopped=true, ServerApp NO reabrirá la ventana. */
    public void stop() {
        stopped = true;
        running = false;
        closeSocket();
    }

    /**
     * Caída brusca — stopped=false.
     * ServerApp detecta wasManualStop=false en onStopped y decide
     * si reabrir la ventana según la RestartPolicy configurada en la UI.
     */
    public void crash() {
        running = false;
        closeSocket();
    }

    public boolean wasStopped() { return stopped; }  // ServerApp lo consulta en onStopped

    // ── Escucha ───────────────────────────────────────────────
    private void listen() {
        try {
            serverSocket = new ServerSocket(port);
            running = true;
            log.accept("✔ Servidor escuchando en puerto " + port);
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
            if (onStopped != null) onStopped.run();
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