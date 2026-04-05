package ui;

import balancer.BalancerClient;
import server.*;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class ServerApp extends JFrame {

    static final Color C_WHITE   = Color.WHITE;
    static final Color C_BG      = new Color(245, 247, 250);
    static final Color C_BORDER  = new Color(200, 205, 215);
    static final Color C_BLUE    = new Color(52, 120, 200);
    static final Color C_GREEN   = new Color(34, 160, 90);
    static final Color C_RED     = new Color(210, 50, 50);
    static final Color C_ORANGE  = new Color(220, 120, 20);
    static final Color C_GRAY    = new Color(120, 125, 135);
    static final Color C_TEAL    = new Color(20, 150, 160);
    static final Color C_TEXT    = new Color(25, 30, 45);

    private TCPServer             server;
    private javax.swing.Timer     clientCountTimer;
    private javax.swing.Timer     statusMonitorTimer; // ← nuevo: vigila si hay que hacer failback
    private RestartPolicy         policy;
    private boolean               isStandbyMode = false;
    private int                   myPort        = -1;

    private JLabel            statusPill;
    private JLabel            modePill;
    private JLabel            clientsLbl;
    private JSpinner          portSpinner;
    private JSpinner          primaryPortSpinner;
    private JSpinner          delaySpinner;
    private JSpinner          maxRestartsSpinner;
    private JComboBox<String> policyBox;
    private JComboBox<String> modeBox;
    private JTextArea         logArea;
    private JButton           startBtn;
    private JButton           stopBtn;
    private JButton           crashBtn;

    public ServerApp() {
        this(-1, null, false, -1);
    }

    public ServerApp(int autoPort, RestartPolicy autoPolicy,
                     boolean standby, int primaryPort) {
        super("Servidor TCP");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (server != null && server.isRunning()) {
                    log("✕ Ventana cerrada — deteniendo servidor...");
                    server.stop();
                } else {
                    dispose();
                }
            }
        });
        setSize(580, 700);
        setMinimumSize(new Dimension(500, 580));
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

        if (autoPort > 0 && autoPolicy != null) {
            portSpinner.setValue(autoPort);
            policyBox.setSelectedIndex(1);
            delaySpinner.setValue(autoPolicy.getDelaySeconds());
            maxRestartsSpinner.setValue(autoPolicy.getMaxRestarts());
            if (standby) {
                modeBox.setSelectedIndex(1);
            }
            javax.swing.Timer t = new javax.swing.Timer(600,
                    e -> launchServer(autoPolicy, autoPort, standby, -1));
            t.setRepeats(false);
            t.start();
        } else {
            resolveAutoPort();
        }
    }

    private void resolveAutoPort() {
        new Thread(() -> {
            int port = BalancerClient.requestNextPort();
            SwingUtilities.invokeLater(() -> {
                if (port > 0) {
                    portSpinner.setValue(port);
                    log("⚖ Puerto asignado por balanceador: " + port);
                } else {
                    log("⚠ Balanceador no disponible — usando puerto por defecto ("
                            + portSpinner.getValue() + ")");
                }
            });
        }, "AutoPort-Resolver").start();
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[ShutdownHook] Proceso terminado externamente.");
            if (server != null && server.isRunning()) {
                if (myPort > 0) BalancerClient.unregister(myPort);
                server.stop();
            }
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
        modePill   = pill("PRIMARIO", C_BLUE.darker());
        clientsLbl = new JLabel("0 clientes conectados");
        clientsLbl.setFont(font(12, false));
        clientsLbl.setForeground(new Color(180, 210, 255));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(clientsLbl);
        right.add(modePill);
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
        primaryPortSpinner = spinner(8080, 1024, 65535);
        delaySpinner       = spinner(20, 1, 120);
        maxRestartsSpinner = spinner(3, 1, 20);
        policyBox          = combo("Sin reinicio", "Reinicio automático");
        modeBox            = combo("Primario", "Standby genérico");

        addRow(p, g, 0, "Puerto:",               portSpinner);
        addRow(p, g, 1, "Modo:",                 modeBox);
        addRow(p, g, 2, "Política de reinicio:",  policyBox);
        addRow(p, g, 3, "Delay reinicio (s):",   delaySpinner);
        addRow(p, g, 4, "Máx. reinicios:",       maxRestartsSpinner);

        // primaryPortSpinner ya no se usa — standby es genérico
        primaryPortSpinner.setVisible(false);
        primaryPortSpinner.setEnabled(false);

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
                        "<b>Standby genérico</b>: cubre cualquier primario que caiga.<br>" +
                        "<b>Simular Caída</b>: AUTO_RESTART → reabre · NO_RESTART → OFFLINE." +
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
    //  LÓGICA
    // ════════════════════════════════════════════════════════
    private void startServer() {
        boolean auto    = policyBox.getSelectedIndex() == 1;
        isStandbyMode   = modeBox.getSelectedIndex() == 1;
        int port        = (Integer) portSpinner.getValue();

        RestartPolicy.Type type = auto
                ? RestartPolicy.Type.AUTO_RESTART
                : RestartPolicy.Type.NO_RESTART;
        policy = new RestartPolicy(type,
                (Integer) delaySpinner.getValue(),
                (Integer) maxRestartsSpinner.getValue());

        String modeStr = isStandbyMode ? "Standby genérico" : "Primario";
        log("Iniciando en puerto " + port + "  |  " + modeStr + "  |  "
                + (auto ? "Reinicio automático · delay="
                + (Integer) delaySpinner.getValue() + "s" : "Sin reinicio"));

        launchServer(policy, port, isStandbyMode, -1);
    }

    private void launchServer(RestartPolicy pol, int port,
                              boolean standby, int ignoredPrimaryPort) {
        myPort        = port;
        isStandbyMode = standby;
        server        = new TCPServer(port, this::log);

        server.setOnStarted(() -> SwingUtilities.invokeLater(() -> {
            if (standby) {
                setPill(modePill, "STANDBY", C_TEAL);
                new Thread(() -> BalancerClient.registerStandby(port),
                        "Balancer-RegStandby").start();
                log("⏸ Registrado como standby genérico");
                startStatusMonitor(port); // ← inicia el monitor de estado
            } else {
                setPill(modePill, "PRIMARIO", C_BLUE.darker());
                new Thread(() -> BalancerClient.register(port),
                        "Balancer-Register").start();
            }

            setPill(statusPill, "ONLINE", C_GREEN);
            startBtn.setEnabled(false);
            stopBtn .setEnabled(true);
            crashBtn.setEnabled(true);
            portSpinner .setEnabled(false);
            policyBox   .setEnabled(false);
            modeBox     .setEnabled(false);
        }));

        server.setOnStopped(() -> SwingUtilities.invokeLater(() -> {
            stopTimers();
            new Thread(() -> BalancerClient.unregister(port), "Balancer-Unreg").start();

            if (pol.shouldRestart()) {
                pol.countRestart();
                int delay = pol.getDelaySeconds();
                log("⟳ Reinicio " + pol.getRestartCount() + "/" + pol.getMaxRestarts()
                        + " — ventana cerrará y reabrirá en " + delay + "s...");

                javax.swing.Timer closeTimer = new javax.swing.Timer(1500,
                        e -> SwingUtilities.invokeLater(this::dispose));
                closeTimer.setRepeats(false);
                closeTimer.start();

                javax.swing.Timer reopenTimer = new javax.swing.Timer(delay * 1000,
                        e -> SwingUtilities.invokeLater(
                                () -> new ServerApp(port, pol, standby, -1)));
                reopenTimer.setRepeats(false);
                reopenTimer.start();

            } else if (pol.getType() == RestartPolicy.Type.AUTO_RESTART) {
                log("✖ Sin más reinicios (" + pol.getRestartCount() + "/"
                        + pol.getMaxRestarts() + "). Cerrando ventana...");
                javax.swing.Timer closeTimer = new javax.swing.Timer(1500,
                        e -> SwingUtilities.invokeLater(this::dispose));
                closeTimer.setRepeats(false);
                closeTimer.start();
            } else {
                log("■ Servidor detenido.");
                goOffline();
            }
        }));

        // Timer de refresco de clientes
        if (clientCountTimer != null) clientCountTimer.stop();
        clientCountTimer = new javax.swing.Timer(800, e -> {
            if (server != null && server.isRunning()) {
                int count = server.clientCount();
                clientsLbl.setText(count + " clientes conectados");
                new Thread(() -> BalancerClient.reportClientCount(port, count),
                        "Balancer-Report").start();
            }
        });
        clientCountTimer.start();

        server.start();
    }

    /**
     * Monitor de estado para servidores standby.
     *
     * Consulta STATUS al balanceador cada segundo.
     * Mientras el balanceador diga ACTIVE → sigue sirviendo normalmente.
     * En cuanto diga STANDBY → el primario se recuperó: detener el TCPServer
     * para cortar todas las conexiones activas. Los clientes detectarán la
     * desconexión, consultarán REDIRECT_TARGET a su puerto original y el
     * balanceador les devolverá el primario. Failback transparente.
     */
    private void startStatusMonitor(int port) {
        if (statusMonitorTimer != null) statusMonitorTimer.stop();

        statusMonitorTimer = new javax.swing.Timer(1000, e -> {
            if (server == null || !server.isRunning()) return;

            new Thread(() -> {
                String status = BalancerClient.requestStatus(port);

                SwingUtilities.invokeLater(() -> {
                    if ("ACTIVE".equals(status)) {
                        // Seguimos cubriendo al primario caído — actualizar pill
                        setPill(modePill, "ACTIVO", C_ORANGE);
                        setPill(statusPill, "ONLINE", C_GREEN);

                    } else if ("STANDBY".equals(status)) {
                        // El primario se recuperó — dejar de servir para que
                        // los clientes migren de vuelta
                        log("↩ Primario recuperado — cediendo clientes y volviendo a standby...");
                        setPill(modePill, "STANDBY", C_TEAL);

                        // Detener el TCPServer corta las conexiones activas.
                        // server.stop() dispara onStopped → goOffline() porque
                        // pol.shouldRestart() = false en este contexto.
                        // Por eso marcamos que es una parada "voluntaria de failback"
                        // y relanzamos inmediatamente como standby en espera.
                        if (statusMonitorTimer != null) {
                            statusMonitorTimer.stop();
                            statusMonitorTimer = null;
                        }

                        // Detener el servidor (corta conexiones → clientes reconectan al primario)
                        new Thread(() -> {
                            server.stop();
                            // Pequeña pausa para que los clientes detecten la desconexión
                            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                            // Volver a arrancar como standby en espera (sin clientes)
                            SwingUtilities.invokeLater(() -> {
                                log("⏸ Standby listo para cubrir la próxima caída.");
                                launchServer(policy, port, true, -1);
                            });
                        }, "Failback-Restart").start();
                    }
                    // Si status es UNKNOWN (balanceador caído), no hacer nada
                });
            }, "StatusMonitor").start();
        });
        statusMonitorTimer.start();
    }

    private void stopTimers() {
        if (clientCountTimer  != null) { clientCountTimer.stop();  clientCountTimer  = null; }
        if (statusMonitorTimer != null) { statusMonitorTimer.stop(); statusMonitorTimer = null; }
    }

    private void goOffline() {
        setPill(statusPill, "OFFLINE", C_RED);
        startBtn.setEnabled(true);
        stopBtn .setEnabled(false);
        crashBtn.setEnabled(false);
        portSpinner.setEnabled(true);
        policyBox  .setEnabled(true);
        modeBox    .setEnabled(true);
        clientsLbl.setText("0 clientes conectados");
    }

    private void crashServer() {
        log("⚡ Fallo manual simulado (crash)...");
        if (server != null) server.stop();
    }

    private void stopServer() {
        log("■ Deteniendo servidor...");
        if (statusMonitorTimer != null) { statusMonitorTimer.stop(); statusMonitorTimer = null; }
        if (server != null) server.stop();
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
        l.setFont(font(11, true));
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

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(ServerApp::new);
    }
}