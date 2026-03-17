package client;

/**
 * Política de reconexión del cliente.
 * Controla cuántas veces reintenta, cada cuánto tiempo y el timeout de conexión.
 */
public class ReconnectionPolicy {

    private final int maxRetries;       // número máximo de reintentos
    private final int intervalSeconds;  // espera entre reintentos
    private final int timeoutSeconds;   // timeout por intento de conexión
    private int retryCount = 0;

    public ReconnectionPolicy(int maxRetries, int intervalSeconds, int timeoutSeconds) {
        this.maxRetries      = maxRetries;
        this.intervalSeconds = intervalSeconds;
        this.timeoutSeconds  = timeoutSeconds;
    }

    public boolean canRetry()         { return retryCount < maxRetries; }
    public void    countRetry()       { retryCount++; }
    public void    reset()            { retryCount = 0; }
    public int     getIntervalMs()    { return intervalSeconds * 1000; }
    public int     getTimeoutMs()     { return timeoutSeconds  * 1000; }
    public int     getRetryCount()    { return retryCount; }
    public int     getMaxRetries()    { return maxRetries; }
    public int     getIntervalSecs()  { return intervalSeconds; }
    public int     getTimeoutSecs()   { return timeoutSeconds; }
}