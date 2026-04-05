package balancer;

import java.io.*;
import java.net.*;

public class BalancerClient {

    private static final String HOST       = "localhost";
    private static final int    PORT       = LoadBalancer.REGISTRY_PORT;
    private static final int    TIMEOUT_MS = 1500;

    public static void register(int serverPort) {
        send("REGISTER " + serverPort);
    }

    public static void registerStandby(int standbyPort) {
        send("REGISTER_STANDBY " + standbyPort);
    }

    /** Compatibilidad — ignora primaryPort. */
    public static void registerStandby(int standbyPort, int primaryPort) {
        registerStandby(standbyPort);
    }

    public static void unregister(int serverPort) {
        send("UNREGISTER " + serverPort);
    }

    public static void reportClientCount(int serverPort, int count) {
        send("CLIENT_COUNT " + serverPort + " " + count);
    }

    public static int requestNextPort() {
        return requestInt("NEXT_PORT");
    }

    public static int requestNextServer() {
        return requestInt("NEXT_SERVER");
    }

    public static int requestRedirectTarget(int currentPort) {
        return requestInt("REDIRECT_TARGET " + currentPort);
    }

    /**
     * Consulta el estado actual de un puerto en el balanceador.
     * @return "ONLINE", "OFFLINE", "STANDBY", "ACTIVE", o "UNKNOWN"
     */
    public static String requestStatus(int serverPort) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_MS);
            PrintWriter    out = new PrintWriter(s.getOutputStream(), true);
            BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out.println("STATUS " + serverPort);
            String resp = in.readLine();
            return (resp != null) ? resp.trim() : "UNKNOWN";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private static int requestInt(String message) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_MS);
            PrintWriter    out = new PrintWriter(s.getOutputStream(), true);
            BufferedReader in  = new BufferedReader(new InputStreamReader(s.getInputStream()));
            out.println(message);
            String resp = in.readLine();
            return (resp != null) ? Integer.parseInt(resp.trim()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void send(String message) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), TIMEOUT_MS);
            PrintWriter out = new PrintWriter(s.getOutputStream(), true);
            out.println(message);
        } catch (Exception ignored) {}
    }
}