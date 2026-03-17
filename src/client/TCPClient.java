package client;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;

/**
 * Cliente TCP.
 * Estados: IDLE → CONNECTING → CONNECTED → RETRYING → FAILED
 * Aplica ReconnectionPolicy al perder la conexión o no poder conectar.
 *

 */
public class TCPClient {

    public enum State { IDLE, CONNECTING, CONNECTED, RETRYING, FAILED }

    private final String             id;
    private final String             host;
    private final int                port;
    private final ReconnectionPolicy policy;
    private final Consumer<String>   log;

    private volatile State   state  = State.IDLE;
    private volatile boolean active = false;
    private Socket socket;
    private Consumer<State> onStateChange;

    public TCPClient(String id, String host, int port,
                     ReconnectionPolicy policy, Consumer<String> log) {
        this.id     = id;
        this.host   = host;
        this.port   = port;
        this.policy = policy;
        this.log    = log;
    }

    public void setOnStateChange(Consumer<State> cb) { this.onStateChange = cb; }

    public void connect() {
        if (active) return;
        active = true;
        policy.reset();
        new Thread(this::loop, "Client-" + id).start();
    }

    public void disconnect() {
        active = false;
        closeSocket();
        setState(State.IDLE);
    }

    // ── Bucle de conexión con política ───────────────────────
    private void loop() {
        while (active) {
            setState(State.CONNECTING);
            log.accept("[" + id + "] Intentando conectar a " + host + ":" + port
                    + "  (timeout=" + policy.getTimeoutSecs() + "s)");

            // wasConnected: indica si llegamos a establecer conexión en este intento.
            // Se usa en el finally para distinguir "pérdida de conexión" de
            // "fallo al conectar", sin depender del valor de 'state'.
            boolean wasConnected = false;

            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), policy.getTimeoutMs());

                wasConnected = true;
                policy.reset();               // resetear contador al conectar con éxito
                setState(State.CONNECTED);
                log.accept("[" + id + "] ✔ Conectado al servidor.");

                startSender();
                readLoop();                   // bloquea hasta que se corta la conexión

            } catch (SocketTimeoutException e) {
                log.accept("[" + id + "] ✖ Timeout al conectar (>"
                        + policy.getTimeoutSecs() + "s).");
            } catch (ConnectException e) {
                log.accept("[" + id + "] ✖ Servidor no disponible.");
            } catch (IOException e) {
                if (active) log.accept("[" + id + "] ✖ Error: " + e.getMessage());
            } finally {
                closeSocket();

                // FIX Bug 2: usamos wasConnected en lugar de comprobar el estado
                // actual, que podría haber cambiado antes de llegar aquí.
                if (wasConnected && active) {
                    log.accept("[" + id + "] Conexión perdida.");
                }
                // No forzamos ningún estado aquí; el bucle asignará
                // RETRYING o FAILED de forma limpia en la siguiente iteración.
            }

            if (!active) break;

            if (policy.canRetry()) {
                policy.countRetry();
                setState(State.RETRYING);     // ← siempre se alcanza ahora
                log.accept("[" + id + "] Reintento " + policy.getRetryCount()
                        + "/" + policy.getMaxRetries()
                        + " en " + policy.getIntervalSecs() + "s...");
                sleep(policy.getIntervalMs());
            } else {
                setState(State.FAILED);
                log.accept("[" + id + "] ✖ Sin más reintentos. Cliente detenido.");
                active = false;
            }
        }
    }

    private void readLoop() throws IOException {
        try (BufferedReader in =
                     new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while (active && (line = in.readLine()) != null) {
                log.accept("[" + id + "] ← " + line);
            }
        }
    }

    private void startSender() {
        Thread t = new Thread(() -> {
            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                int n = 0;
                while (active && state == State.CONNECTED && !socket.isClosed()) {
                    out.println("Hola desde " + id + " [msg #" + (++n) + "]");
                    sleep(3000);
                }
            } catch (IOException ignored) {}
        }, "Sender-" + id);
        t.setDaemon(true);
        t.start();
    }

    private void setState(State s) {
        state = s;
        if (onStateChange != null) onStateChange.accept(s);
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public State  getState() { return state; }
    public String getId()    { return id; }
}