package ui;

import server.*;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Interfaz del Servidor TCP.
 *
 * Maneja la RestartPolicy exactamente igual que ClientApp maneja
 * la ReconnectionPolicy de los clientes:
 *
 *   TCPClient loop:                     ServerApp onStopped:
 *   ─────────────────                   ────────────────────
 *   canRetry()?                         shouldRestart()?
 *     sí → espera interval → reconecta    sí → espera delay → cierra+reabre ventana
 *     no → FAILED                         no → OFFLINE definitivo
 *
 * COMPORTAMIENTO DE VENTANA:
 *   Detener            → stopped=true  → ventana queda en OFFLINE, no reinicia.
 *   Crash + NO_RESTART → stopped=false → ventana queda en OFFLINE, no reinicia.
 *   Crash + AUTO_RESTART:
 *     - policy.shouldRestart()? sí → log delay → cierra ventana → reabre con servidor
 *     - policy.shouldRestart()? no → OFFLINE definitivo (máx. reinicios alcanzado)
 */
public class ServerApp extends JFrame {

    // ── Colores ──────────────────────────────────────────────
    static final Color C_WHITE  = Color.WHITE;
    static final Color C_BG     = new Color(245, 247, 250);
    static final Color C_BORDER = new Color(200, 205, 215);
    static final Color C_BLUE   = new Color(52, 120, 200);
    static final Color C_GREEN  = new Color(34, 160, 90);
    static final Color C_RED    = new Color(210, 50, 50);
    static final Color C_ORANGE = new Color(220, 120, 20);
    static final Color C_GRAY   = new Color(120, 125, 135);
    static final Color C_TEXT   = new Color(25, 30, 45);

    // ── Componentes ──────────────────────────────────────────
    private TCPServer  server;
    private Timer      clientCountTimer;
    private RestartPolicy policy;   // política activa, consultada en onStopped

    private JLabel            statusPill;
    private JLabel            clientsLbl;
    private JSpinner          portSpinner;
    private JSpinner          delaySpinner;
    private JSpinner          maxRestartsSpinner;
    private JComboBox<String> policyBox;
    private JTextArea         logArea;
    private JButton           startBtn;
    private JButton           stopBtn;
    private JButton           crashBtn;

    // ── Constructor normal ───────────────────────────────────
    public ServerApp() {
        this(-1, null);
    }

    /**
     * Constructor de reinicio automático.
     * Usado cuando ServerApp se reabre a sí misma tras un crash.
     * @param autoPort   puerto a usar (si > 0 arranca automáticamente)
     * @param autoPolicy política a restaurar en la UI
     */
    public ServerApp(int autoPort, RestartPolicy autoPolicy) {
        super("Servidor TCP");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);  // interceptar la X
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                // X de la ventana → parar servidor (dispara onStopped → política decide)
                if (server != null && server.isRunning()) {
                    log("✕ Ventana cerrada por el usuario — deteniendo servidor...");
                    server.stop();
                    // dispose() lo hará onStopped tras el delay, no aquí
                } else {
                    dispose(); // si no hay servidor activo, cerrar directamente
                }
            }
        });
        setSize(560, 680);
        setMinimumSize(new Dimension(480, 560));
        setLocationRelativeTo(null);
        setResizable(true);

        registerShutdownHook();

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(C_BG);
        root.add(buildHeader(),  BorderLayout.NORTH);
        root.add(buildCenter(),  BorderLayout.CENTER);
        root.add(buildButtons(), BorderLayout.SOUTH);
        setContentPane(root);
        setVisible(true);

        // Si viene de un reinicio automático, restaurar config y arrancar
        if (autoPort > 0 && autoPolicy != null) {
            portSpinner.setValue(autoPort);
            policyBox.setSelectedIndex(1);
            delaySpinner.setValue(autoPolicy.getDelaySeconds());
            maxRestartsSpinner.setValue(autoPolicy.getMaxRestarts());
            // Usar la política recibida TAL CUAL (con su contador ya incrementado)
            // NO llamar startServer() porque crearía una política nueva reseteando el contador
            Timer t = new Timer(600, e -> launchServer(autoPolicy, autoPort));
            t.setRepeats(false);
            t.start();
        }
    }

    // ════════════════════════════════════════════════════════
    //  SHUTDOWN HOOK
    // ════════════════════════════════════════════════════════
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            if (logArea != null) {
                logArea.append("\n[" + time + "]  ⚠ PROCESO TERMINADO EXTERNAMENTE\n");
                logArea.append("[" + time + "]  ⚠ (Administrador de Tareas / Ctrl+C / kill)\n");
                logArea.append("[" + time + "]  → Ejecutando limpieza...\n");
            }
            System.out.println("[ShutdownHook] Proceso terminado externamente.");
            if (server != null && server.isRunning()) {
                server.stop();
                if (logArea != null)
                    logArea.append("[" + time + "]  ✔ Puerto liberado. Proceso cerrado.\n");
            }
            System.out.println("[ShutdownHook] Limpieza completada.");
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }, "ShutdownHook-Server"));
    }

    // ── Cabecera ─────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_BLUE);
        p.setBorder(new EmptyBorder(16, 20, 16, 20));

        JLabel title = new JLabel("Servidor TCP");
        title.setFont(font(20, true));
        title.setForeground(Color.WHITE);

        statusPill = pill("OFFLINE", C_RED);
        clientsLbl = new JLabel("0 clientes conectados");
        clientsLbl.setFont(font(12, false));
        clientsLbl.setForeground(new Color(180, 210, 255));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(clientsLbl);
        right.add(statusPill);

        p.add(title, BorderLayout.WEST);
        p.add(right, BorderLayout.EAST);
        return p;
    }

    // ── Centro ───────────────────────────────────────────────
    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setBackground(C_WHITE);
        p.setBorder(new EmptyBorder(16, 20, 8, 20));
        p.add(buildConfig(), BorderLayout.NORTH);
        p.add(buildLog(),    BorderLayout.CENTER);
        return p;
    }

    private JPanel buildConfig() {
        JPanel p = sectionPanel("Configuración");
        p.setLayout(new GridBagLayout());
        GridBagConstraints g = gbc();

        portSpinner        = spinner(8080, 1024, 65535);
        delaySpinner       = spinner(5, 1, 60);
        maxRestartsSpinner = spinner(3, 1, 20);
        policyBox          = combo("Sin reinicio", "Reinicio automático");

        addRow(p, g, 0, "Puerto:",              portSpinner);
        addRow(p, g, 1, "Política de reinicio:", policyBox);
        addRow(p, g, 2, "Delay reinicio (s):",  delaySpinner);
        addRow(p, g, 3, "Máx. reinicios:",      maxRestartsSpinner);

        delaySpinner.setEnabled(false);
        maxRestartsSpinner.setEnabled(false);
        policyBox.addActionListener(e -> {
            boolean auto = policyBox.getSelectedIndex() == 1;
            delaySpinner.setEnabled(auto);
            maxRestartsSpinner.setEnabled(auto);
        });
        return p;
    }

    private JPanel buildLog() {
        logArea = new JTextArea();
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setBackground(new Color(235, 250, 238));
        logArea.setForeground(C_TEXT);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER));

        JLabel lbl = new JLabel("Registro de eventos");
        lbl.setFont(font(12, true));
        lbl.setForeground(C_TEXT);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(C_WHITE);
        p.add(lbl,    BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    // ── Botones ──────────────────────────────────────────────
    private JPanel buildButtons() {
        startBtn = actionBtn("▶  Iniciar",        C_GREEN);
        stopBtn  = actionBtn("■  Detener",         C_RED);
        crashBtn = actionBtn("⚡  Simular Caída",  C_ORANGE);
        JButton clearBtn = actionBtn("⌫  Limpiar", C_GRAY);

        stopBtn .setEnabled(false);
        crashBtn.setEnabled(false);

        startBtn.addActionListener(e -> startServer());
        stopBtn .addActionListener(e -> stopServer());
        crashBtn.addActionListener(e -> crashServer());
        clearBtn.addActionListener(e -> logArea.setText(""));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6));
        row1.setBackground(C_WHITE);
        row1.add(startBtn); row1.add(stopBtn);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 6));
        row2.setBackground(C_WHITE);
        row2.add(crashBtn); row2.add(clearBtn);

        JLabel hint = new JLabel(
                "<html><center><i>" +
                        "<b>Simular Caída</b>: crash → AUTO_RESTART: ventana cierra y reabre · NO_RESTART: OFFLINE.<br>" +
                        "<b>Detener</b>: parada limpia → siempre OFFLINE definitivo." +
                        "</i></center></html>");
        hint.setFont(font(11, false));
        hint.setForeground(C_GRAY);
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        hint.setBorder(new EmptyBorder(0, 0, 8, 0));

        JPanel rows = new JPanel(new GridLayout(2, 1));
        rows.setBackground(C_WHITE);
        rows.add(row1); rows.add(row2);

        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(C_WHITE);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        p.add(rows, BorderLayout.CENTER);
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    // ════════════════════════════════════════════════════════
    //  LÓGICA — espejo exacto del loop de TCPClient
    // ════════════════════════════════════════════════════════
    private void startServer() {
        boolean auto = policyBox.getSelectedIndex() == 1;
        RestartPolicy.Type type = auto
                ? RestartPolicy.Type.AUTO_RESTART
                : RestartPolicy.Type.NO_RESTART;

        // Crear política fresca (reset de contador)
        policy = new RestartPolicy(type,
                (Integer) delaySpinner.getValue(),
                (Integer) maxRestartsSpinner.getValue());

        launchServer(policy, (Integer) portSpinner.getValue());

        log("Iniciando en puerto " + (Integer) portSpinner.getValue() + "  |  "
                + (auto ? "Reinicio automático · delay=" + (Integer) delaySpinner.getValue()
                + "s · máx=" + (Integer) maxRestartsSpinner.getValue()
                : "Sin reinicio"));
    }

    /**
     * Crea y arranca un TCPServer con los callbacks de la UI.
     * Se llama tanto en el arranque inicial como en cada reapertura de ventana.
     */
    private void launchServer(RestartPolicy pol, int port) {
        server = new TCPServer(port, this::log);

        server.setOnStarted(() -> SwingUtilities.invokeLater(() -> {
            setPill(statusPill, "ONLINE", C_GREEN);
            startBtn.setEnabled(false);
            stopBtn .setEnabled(true);
            crashBtn.setEnabled(true);
            portSpinner.setEnabled(false);
            policyBox  .setEnabled(false);
        }));

        // onStopped: la política decide siempre, sin importar si fue stop() o crash()
        server.setOnStopped(() -> SwingUtilities.invokeLater(() -> {
            if (clientCountTimer != null) { clientCountTimer.stop(); clientCountTimer = null; }

            if (pol.shouldRestart()) {
                // ── AUTO_RESTART y puede reiniciar → cerrar ventana y reabrir ──
                // (igual que TCPClient en RETRYING, independiente de si fue stop/crash/X)
                pol.countRestart();
                int delay = pol.getDelaySeconds();
                log("⟳ Reinicio " + pol.getRestartCount() + "/" + pol.getMaxRestarts()
                        + " — ventana cerrará y reabrirá en " + delay + "s...");

                Timer closeTimer = new Timer(1500, e ->
                        SwingUtilities.invokeLater(this::dispose)
                );
                closeTimer.setRepeats(false);
                closeTimer.start();

                Timer reopenTimer = new Timer(delay * 1000, e ->
                        SwingUtilities.invokeLater(() -> new ServerApp(port, pol))
                );
                reopenTimer.setRepeats(false);
                reopenTimer.start();

            } else if (pol.getType() == RestartPolicy.Type.AUTO_RESTART) {
                // ── AUTO_RESTART pero agotó los reinicios → cerrar ventana definitivamente ──
                log("✖ Sin más reinicios (" + pol.getRestartCount() + "/" + pol.getMaxRestarts()
                        + "). Servidor caído definitivamente. Cerrando ventana...");
                Timer closeTimer = new Timer(1500, e ->
                        SwingUtilities.invokeLater(this::dispose)
                );
                closeTimer.setRepeats(false);
                closeTimer.start();

            } else {
                // ── NO_RESTART: OFFLINE definitivo siempre ──
                log("■ Servidor detenido.");
                goOffline();
            }
        }));

        // Timer de refresco del contador de clientes
        if (clientCountTimer != null) clientCountTimer.stop();
        clientCountTimer = new Timer(800, e -> {
            if (server != null && server.isRunning())
                clientsLbl.setText(server.clientCount() + " clientes conectados");
        });
        clientCountTimer.start();

        server.start();
    }

    /** Pone la UI en estado OFFLINE definitivo. */
    private void goOffline() {
        setPill(statusPill, "OFFLINE", C_RED);
        startBtn.setEnabled(true);
        stopBtn .setEnabled(false);
        crashBtn.setEnabled(false);
        portSpinner.setEnabled(true);
        policyBox  .setEnabled(true);
        clientsLbl.setText("0 clientes conectados");
    }

    /** FALLO MANUAL — crash brusco. */
    private void crashServer() {
        log("⚡ Fallo manual simulado (crash)...");
        if (server != null) server.stop();  // onStopped → política decide
    }

    /** DETENCIÓN — la política decide si reiniciar o no. */
    private void stopServer() {
        log("■ Deteniendo servidor...");
        if (server != null) server.stop();  // onStopped → política decide
    }

    // ── Helpers UI ───────────────────────────────────────────
    private JPanel sectionPanel(String title) {
        JPanel p = new JPanel();
        p.setBackground(new Color(248, 249, 253));
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(C_BORDER), title);
        tb.setTitleFont(font(12, true));
        tb.setTitleColor(C_GRAY);
        p.setBorder(BorderFactory.createCompoundBorder(tb, new EmptyBorder(6, 8, 6, 8)));
        return p;
    }

    private void addRow(JPanel p, GridBagConstraints g, int row,
                        String label, JComponent field) {
        g.gridx = 0; g.gridy = row; g.weightx = 0;
        JLabel l = new JLabel(label);
        l.setFont(font(12, false));
        l.setForeground(C_TEXT);
        p.add(l, g);
        g.gridx = 1; g.weightx = 1;
        p.add(field, g);
    }

    private GridBagConstraints gbc() {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 8, 5, 8);
        g.anchor = GridBagConstraints.WEST;
        g.fill   = GridBagConstraints.HORIZONTAL;
        return g;
    }

    private JSpinner spinner(int val, int min, int max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(val, min, max, 1));
        s.setFont(font(12, false));
        s.setPreferredSize(new Dimension(90, 28));
        return s;
    }

    private JComboBox<String> combo(String... items) {
        JComboBox<String> cb = new JComboBox<>(items);
        cb.setFont(font(12, false));
        cb.setBackground(C_WHITE);
        return cb;
    }

    private JButton actionBtn(String text, Color color) {
        JButton b = new JButton(text);
        b.setFont(font(13, true));
        b.setForeground(Color.WHITE);
        b.setBackground(color);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(175, 36));
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) { if (b.isEnabled()) b.setBackground(color.darker()); }
            public void mouseExited (java.awt.event.MouseEvent e) { if (b.isEnabled()) b.setBackground(color); }
        });
        return b;
    }

    private JLabel pill(String text, Color bg) {
        JLabel l = new JLabel("  " + text + "  ");
        l.setFont(font(12, true));
        l.setOpaque(true);
        l.setBackground(bg);
        l.setForeground(Color.WHITE);
        l.setBorder(new EmptyBorder(3, 8, 3, 8));
        return l;
    }

    private void setPill(JLabel l, String text, Color bg) {
        l.setText("  " + text + "  ");
        l.setBackground(bg);
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.append("[" + time + "]  " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private Font font(int size, boolean bold) {
        return new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size);
    }

    // ── Main ─────────────────────────────────────────────────
    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(ServerApp::new);
    }
}