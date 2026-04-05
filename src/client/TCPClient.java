package client;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

public class TCPClient {

    public enum State { IDLE, CONNECTING, CONNECTED, RETRYING, FAILED }

    private final String           id;
    private final String           host;
    private final IntSupplier      portResolver;
    private final ReconnectionPolicy policy;
    private final Consumer<String> log;

    private volatile State   state  = State.IDLE;
    private volatile boolean active = false;
    private volatile int     lastConnectedPort = -1;
    private Socket           socket;
    private Consumer<State>  onStateChange;
    private Consumer<Integer> onPortChange;

    public TCPClient(String id, String host, int port,
                     ReconnectionPolicy policy, Consumer<String> log) {
        this(id, host, () -> port, policy, log);
    }

    public TCPClient(String id, String host, IntSupplier portResolver,
                     ReconnectionPolicy policy, Consumer<String> log) {
        this.id           = id;
        this.host         = host;
        this.portResolver = portResolver;
        this.policy       = policy;
        this.log          = log;
    }

    public void setOnStateChange(Consumer<State> cb)   { this.onStateChange = cb; }
    public void setOnPortChange(Consumer<Integer> cb)  { this.onPortChange = cb; }

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

    private void loop() {
        while (active) {
            int port = portResolver.getAsInt();

            if (port < 0) {
                log.accept("[" + id + "] ⏳ Sin servidor disponible, esperando "
                        + policy.getIntervalSecs() + "s...");
                setState(State.RETRYING);
                sleep(policy.getIntervalMs());
                continue;
            }

            // Notificar cambio de puerto a la UI si cambió respecto al último conocido
            if (port != lastConnectedPort && onPortChange != null) {
                onPortChange.accept(port);
            }

            setState(State.CONNECTING);
            log.accept("[" + id + "] Intentando conectar a " + host + ":" + port
                    + "  (timeout=" + policy.getTimeoutSecs() + "s)");

            boolean wasConnected = false;

            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), policy.getTimeoutMs());

                wasConnected = true;
                lastConnectedPort = port;
                policy.reset();
                setState(State.CONNECTED);
                log.accept("[" + id + "] ✔ Conectado al servidor :" + port);

                startSender(port);
                readLoop();

            } catch (SocketTimeoutException e) {
                log.accept("[" + id + "] ✖ Timeout al conectar :" + port
                        + " (>" + policy.getTimeoutSecs() + "s).");
            } catch (ConnectException e) {
                log.accept("[" + id + "] ✖ Servidor :" + port + " no disponible.");
            } catch (IOException e) {
                if (active) log.accept("[" + id + "] ✖ Error: " + e.getMessage());
            } finally {
                closeSocket();
                if (wasConnected && active) {
                    log.accept("[" + id + "] Conexión perdida con :" + port + ".");
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

    private void startSender(int port) {
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

    public State  getState()            { return state; }
    public String getId()               { return id; }
    public int    getLastConnectedPort(){ return lastConnectedPort; }
}