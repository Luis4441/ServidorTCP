package client;

import java.io.*;
import java.net.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Cliente TCP con soporte de balanceo de carga.
 * Consulta al balanceador en cada intento (Round Robin).
 * Notifica qué servidor le fue asignado para mostrarlo en la UI.
 */
public class TCPClientBalanced {

    public enum State { IDLE, CONNECTING, CONNECTED, RETRYING, FAILED }

    private final String             id;
    private final String             host;
    private final ReconnectionPolicy policy;
    private final Consumer<String>   log;
    private final Supplier<int[]>    serverSupplier; // {puerto, índiceServidor}
    private final boolean            allowRedirect;  // false = Sin reinicio, no redirige tras caída

    private volatile State   state            = State.IDLE;
    private volatile boolean active           = false;
    private volatile boolean hasConnectedOnce = false; // true tras la primera conexión exitosa
    private volatile String  connectedServer  = ""; // "S01:8080" o ""
    private volatile int     lastPort         = -1;  // puerto del servidor al que estaba conectado
    private volatile String  lastSrvId        = "";  // id del servidor al que estaba conectado
    private Socket socket;

    private Consumer<State>           onStateChange;
    private BiConsumer<String,String> onServerChange; // (clientId, "S01:8080")

    /**
     * @param serverSupplier  Devuelve int[]{puerto, idxServidor} del próximo
     *                        servidor disponible. Devuelve null si no hay ninguno.
     * @param allowRedirect   true = con reinicio automático (puede redirigir a otro servidor
     *                        tras una caída). false = sin reinicio (si pierde conexión, falla).
     */
    public TCPClientBalanced(String id, String host,
                             ReconnectionPolicy policy,
                             Consumer<String> log,
                             Supplier<int[]> serverSupplier,
                             boolean allowRedirect) {
        this.id             = id;
        this.host           = host;
        this.policy         = policy;
        this.log            = log;
        this.serverSupplier = serverSupplier;
        this.allowRedirect  = allowRedirect;
    }

    public void setOnStateChange(Consumer<State> cb)            { this.onStateChange  = cb; }
    public void setOnServerChange(BiConsumer<String,String> cb) { this.onServerChange = cb; }

    public void connect() {
        if (active) return;
        active = true;
        policy.reset();
        new Thread(this::loop, "Client-" + id).start();
    }

    public void disconnect() {
        active = false;
        closeSocket();
        connectedServer = "";
        setState(State.IDLE);
        notifyServer("");
    }

    // ── Bucle principal ──────────────────────────────────────
    private void loop() {
        while (active) {
            setState(State.CONNECTING);

            int port;
            String srvId;

            if (hasConnectedOnce && allowRedirect) {
                // Con reinicio automático: vuelve a intentar con el mismo servidor,
                // no consulta el balanceador para no cambiar de servidor.
                port  = lastPort;
                srvId = lastSrvId;
                log.accept("[" + id + "] Reintentando reconectar a " + srvId
                        + " (" + host + ":" + port + ")  timeout=" + policy.getTimeoutSecs() + "s");
            } else if (hasConnectedOnce && !allowRedirect) {
                // Sin reinicio: el servidor caído no va a volver,
                // consulta el balanceador para ir a otro disponible.
                int[] info = serverSupplier.get();
                port  = (info != null) ? info[0] : -1;
                srvId = (info != null && info.length > 1)
                        ? "S" + String.format("%02d", info[1] + 1) : "";
                if (port == -1) {
                    log.accept("[" + id + "] ✖ No hay servidores disponibles.");
                } else {
                    log.accept("[" + id + "] Redirigiendo a " + srvId
                            + " (" + host + ":" + port + ")  timeout=" + policy.getTimeoutSecs() + "s");
                }
            } else {
                // Primera conexión: consulta el balanceador normalmente (Round Robin).
                int[] info = serverSupplier.get();
                port  = (info != null) ? info[0] : -1;
                srvId = (info != null && info.length > 1)
                        ? "S" + String.format("%02d", info[1] + 1) : "";
                if (port == -1) {
                    log.accept("[" + id + "] ✖ No hay servidores disponibles.");
                } else {
                    log.accept("[" + id + "] Intentando conectar a " + srvId
                            + " (" + host + ":" + port + ")  timeout=" + policy.getTimeoutSecs() + "s");
                }
            }

            boolean wasConnected = false;

            if (port != -1) {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(host, port), policy.getTimeoutMs());

                    wasConnected     = true;
                    hasConnectedOnce = true;
                    lastPort         = port;
                    lastSrvId        = srvId;
                    connectedServer  = srvId + ":" + port;
                    policy.reset();
                    setState(State.CONNECTED);
                    notifyServer(connectedServer);
                    log.accept("[" + id + "] ✔ Conectado a " + srvId + " (puerto " + port + ")");

                    startSender(srvId);
                    readLoop();

                } catch (SocketTimeoutException e) {
                    log.accept("[" + id + "] ✖ Timeout al conectar a " + srvId + " (>"
                            + policy.getTimeoutSecs() + "s).");
                } catch (ConnectException e) {
                    log.accept("[" + id + "] ✖ " + srvId + " no disponible en puerto " + port + ".");
                } catch (IOException e) {
                    if (active) log.accept("[" + id + "] ✖ Error: " + e.getMessage());
                } finally {
                    closeSocket();
                    if (wasConnected && active) {
                        log.accept("[" + id + "] Conexión perdida con " + srvId
                                + (allowRedirect ? ". Esperando que " + srvId + " vuelva..." : ". Buscando otro servidor..."));
                        connectedServer = "";
                        notifyServer("");
                    }
                }
            }

            if (!active) break;

            if (policy.canRetry()) {
                policy.countRetry();
                setState(State.RETRYING);
                log.accept("[" + id + "] Reintento " + policy.getRetryCount()
                        + "/" + policy.getMaxRetries()
                        + " en " + policy.getIntervalSecs() + "s...");
                sleep(policy.getIntervalMs());
            } else {
                setState(State.FAILED);
                connectedServer = "";
                notifyServer("");
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

    private void startSender(String srvId) {
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

    private void notifyServer(String label) {
        if (onServerChange != null) onServerChange.accept(id, label);
    }

    private void closeSocket() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public State  getState()           { return state; }
    public String getId()              { return id; }
    public String getConnectedServer() { return connectedServer; }
}