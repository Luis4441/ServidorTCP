package server;

/**
 * Política de reinicio del servidor.
 * NO_RESTART      → el servidor no se reinicia al caerse.
 * AUTO_RESTART    → el servidor se reinicia automáticamente tras un delay.
 */
public class RestartPolicy {

    public enum Type { NO_RESTART, AUTO_RESTART }

    private final Type type;
    private final int  delaySeconds;   // cuánto esperar antes de reiniciar
    private final int  maxRestarts;    // 0 = ilimitado
    private int        restartCount = 0;

    public RestartPolicy(Type type, int delaySeconds, int maxRestarts) {
        this.type          = type;
        this.delaySeconds  = delaySeconds;
        this.maxRestarts   = maxRestarts;
    }

    public boolean shouldRestart() {
        if (type == Type.NO_RESTART) return false;
        if (maxRestarts > 0 && restartCount >= maxRestarts) return false;
        return true;
    }

    public void countRestart()   { restartCount++; }
    public void reset()          { restartCount = 0; }
    public int  getDelayMs()     { return delaySeconds * 1000; }
    public int  getDelaySeconds(){ return delaySeconds; }
    public Type getType()        { return type; }
    public int  getRestartCount(){ return restartCount; }
    public int  getMaxRestarts() { return maxRestarts; }
}