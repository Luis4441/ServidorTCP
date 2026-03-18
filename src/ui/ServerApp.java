package ui;

import server.*;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Interfaz dedicada únicamente al Servidor TCP.
 * Ejecutar esta clase de forma independiente a ClientApp.
 */
public class ServerApp extends JFrame {

    // ── Colores ──────────────────────────────────────────────
    static final Color C_WHITE  = Color.WHITE;
    static final Color C_BG     = new Color(245, 247, 250);
    static final Color C_BORDER = new Color(200, 205, 215);
    static final Color C_BLUE   = new Color(52, 120, 200);
    static final Color C_GREEN  = new Color(34, 160, 90);
    static final Color C_RED    = new Color(210, 50, 50);
    static final Color C_GRAY   = new Color(120, 125, 135);
    static final Color C_TEXT   = new Color(25, 30, 45);

    // ── Componentes ──────────────────────────────────────────
    private TCPServer   server;
    private Timer       clientCountTimer;

    private JLabel      statusPill;
    private JLabel      clientsLbl;
    private JSpinner    portSpinner;
    private JSpinner    delaySpinner;
    private JSpinner    maxRestartsSpinner;
    private JComboBox<String> policyBox;
    private JTextArea   logArea;
    private JButton     startBtn;
    private JButton     stopBtn;

    public ServerApp() {
        super("Servidor TCP");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(560, 620);
        setMinimumSize(new Dimension(480, 500));
        setLocationRelativeTo(null);
        setResizable(true);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(C_BG);
        root.add(buildHeader(),  BorderLayout.NORTH);
        root.add(buildCenter(), BorderLayout.CENTER);
        root.add(buildButtons(), BorderLayout.SOUTH);
        setContentPane(root);
        setVisible(true);
    }

    // ── Cabecera ─────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_BLUE);
        p.setBorder(new EmptyBorder(16, 20, 16, 20));

        JLabel title = new JLabel("Servidor TCP");
        title.setFont(font(20, true));
        title.setForeground(Color.WHITE);

        // Pill de estado + contador de clientes
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

    // ── Centro: configuración + log ──────────────────────────
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

        // Deshabilitar campos de auto-reinicio por defecto
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
        startBtn = actionBtn("▶  Iniciar servidor", C_GREEN);
        stopBtn  = actionBtn("■  Detener servidor", C_RED);
        JButton clearBtn = actionBtn("⌫  Limpiar log", C_GRAY);

        stopBtn.setEnabled(false);
        startBtn.addActionListener(e -> startServer());
        stopBtn .addActionListener(e -> stopServer());
        clearBtn.addActionListener(e -> logArea.setText(""));

        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
        p.setBackground(C_WHITE);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        p.add(startBtn);
        p.add(stopBtn);
        p.add(clearBtn);
        return p;
    }

    // ── Lógica ───────────────────────────────────────────────
    private void startServer() {
        boolean auto = policyBox.getSelectedIndex() == 1;
        RestartPolicy.Type type = auto
                ? RestartPolicy.Type.AUTO_RESTART
                : RestartPolicy.Type.NO_RESTART;
        RestartPolicy policy = new RestartPolicy(type,
                (Integer) delaySpinner.getValue(),
                (Integer) maxRestartsSpinner.getValue());
        int port = (Integer) portSpinner.getValue();

        server = new TCPServer(port, policy, msg -> log(msg));

        server.setOnStarted(() -> SwingUtilities.invokeLater(() -> {
            setPill(statusPill, "ONLINE", C_GREEN);
            startBtn.setEnabled(false);
            stopBtn.setEnabled(true);
            portSpinner.setEnabled(false);
            policyBox.setEnabled(false);
        }));

        server.setOnStopped(() -> SwingUtilities.invokeLater(() -> {
            setPill(statusPill, "OFFLINE", C_RED);
            startBtn.setEnabled(true);
            stopBtn.setEnabled(false);
            portSpinner.setEnabled(true);
            policyBox.setEnabled(true);
            clientsLbl.setText("0 clientes conectados");
            if (clientCountTimer != null) {
                clientCountTimer.stop();
                clientCountTimer = null;
            }
        }));

        // Timer de refresco de clientes conectados
        if (clientCountTimer != null) clientCountTimer.stop();
        clientCountTimer = new Timer(800, e -> {
            if (server != null && server.isRunning())
                clientsLbl.setText(server.clientCount() + " clientes conectados");
        });
        clientCountTimer.start();

        server.start();
        log("Iniciando en puerto " + port + "  |  "
                + (auto ? "Reinicio automático cada " + (Integer) delaySpinner.getValue() + "s"
                : "Sin reinicio"));
    }

    private void stopServer() {
        log("Deteniendo servidor (manual)...");
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

    private void addRow(JPanel p, GridBagConstraints g, int row, String label, JComponent field) {
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
        b.setPreferredSize(new Dimension(180, 38));
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