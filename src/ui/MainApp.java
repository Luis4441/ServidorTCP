package ui;

import client.*;
import server.*;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Ventana principal.
 * Izquierda  → control del SERVIDOR (puerto, política, start/stop, log)
 * Derecha     → control de CLIENTES  (host, puerto, política, lanzar, tarjetas, log)
 *
 * CORRECCIONES:
 *  - Bug 4: clientCountTimer es campo de clase; se detiene antes de crear
 *           uno nuevo en cada arranque del servidor, evitando el acúmulo
 *           de timers (memory/thread leak).
 *  - Bug 1 UI: onStarted actualiza la UI a ONLINE también en reinicios
 *              automáticos (el servidor llama onStarted en cada reinicio;
 *              aquí simplemente re-aplicamos el mismo callback).
 */
public class MainApp extends JFrame {

    // ── Colores base ─────────────────────────────────────────
    static final Color C_BG     = new Color(245, 247, 250);
    static final Color C_WHITE  = Color.WHITE;
    static final Color C_BORDER = new Color(200, 205, 215);
    static final Color C_BLUE   = new Color(52, 120, 200);
    static final Color C_PURPLE = new Color(120, 70, 190);
    static final Color C_GREEN  = new Color(34, 160, 90);
    static final Color C_RED    = new Color(210, 50, 50);
    static final Color C_ORANGE = new Color(220, 120, 20);
    static final Color C_GRAY   = new Color(120, 125, 135);
    static final Color C_TEXT   = new Color(25, 30, 45);

    // ── Estado del servidor ──────────────────────────────────
    private TCPServer   server;
    private JLabel      srvStatusLbl;
    private JLabel      srvClientsLbl;
    private JSpinner    srvPort, srvDelay, srvMaxRestarts;
    private JComboBox<String> srvPolicyBox;
    private JTextArea   srvLog;
    private JButton     srvStartBtn, srvStopBtn;

    // FIX Bug 4: referencia al timer para poder detenerlo antes de recrearlo
    private Timer clientCountTimer;

    // ── Estado de clientes ───────────────────────────────────
    private final List<TCPClient> clients    = new ArrayList<>();
    private JTextField  cliHost;
    private JSpinner    cliPort, cliNum, cliRetries, cliInterval, cliTimeout;
    private JPanel      cliCards;
    private JTextArea   cliLog;
    private int         cliCounter = 0;

    public MainApp() {
        super("TCP Monitor — Cliente / Servidor");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1180, 720);
        setMinimumSize(new Dimension(900, 580));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(C_BG);
        root.add(buildScenarioBar(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildServerSide(), buildClientSide());
        split.setDividerLocation(530);
        split.setDividerSize(4);
        split.setBorder(null);
        root.add(split, BorderLayout.CENTER);

        setContentPane(root);
        setVisible(true);
    }

    // ════════════════════════════════════════════════════════
    //  BARRA DE ESCENARIOS
    // ════════════════════════════════════════════════════════
    private JPanel buildScenarioBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 8));
        bar.setBackground(new Color(230, 235, 245));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER));

        JLabel lbl = new JLabel("Escenarios:");
        lbl.setFont(font(12, true));
        lbl.setForeground(C_GRAY);
        bar.add(lbl);

        bar.add(scenBtn("1 · Solo clientes (sin servidor)",
                "<html><b>Escenario 1</b><br><br>" +
                        "• NO inicies el servidor.<br>" +
                        "• Configura reintentos/timeout en el panel derecho.<br>" +
                        "• Pulsa <b>Lanzar clientes</b>.<br>" +
                        "• Los clientes reintentarán y llegarán a estado <b>Fallido</b>.</html>",
                new Color(190, 140, 20)));

        bar.add(scenBtn("2 · Servidor cae, sin reinicio",
                "<html><b>Escenario 2</b><br><br>" +
                        "• Política servidor → <b>Sin reinicio</b>.<br>" +
                        "• Inicia servidor → lanza clientes → detén servidor.<br>" +
                        "• Los clientes reintentarán pero el servidor no vuelve.<br>" +
                        "• Resultado: clientes en estado <b>Fallido</b>.</html>",
                C_RED));

        bar.add(scenBtn("3 · Servidor cae, reinicio automático",
                "<html><b>Escenario 3</b><br><br>" +
                        "• Política servidor → <b>Reinicio automático</b>.<br>" +
                        "• Inicia servidor → lanza clientes → detén servidor.<br>" +
                        "• El servidor se reinicia solo tras el delay.<br>" +
                        "• Los clientes reconectan automáticamente.<br>" +
                        "• Resultado: clientes vuelven a <b>Conectado</b>.</html>",
                C_GREEN));

        return bar;
    }

    private JButton scenBtn(String text, String html, Color color) {
        JButton b = new JButton(text);
        b.setFont(font(12, false));
        b.setForeground(color.darker());
        b.setBackground(mix(color, C_WHITE, 0.15f));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color, 1),
                new EmptyBorder(4, 10, 4, 10)
        ));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e ->
                JOptionPane.showMessageDialog(this, html, "Escenario: " + text,
                        JOptionPane.INFORMATION_MESSAGE));
        return b;
    }

    // ════════════════════════════════════════════════════════
    //  PANEL SERVIDOR (izquierda)
    // ════════════════════════════════════════════════════════
    private JPanel buildServerSide() {
        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(C_WHITE);
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, C_BORDER));

        p.add(colorHeader("Servidor TCP", C_BLUE, () -> {
            srvStatusLbl  = statusPill("OFFLINE", C_RED);
            srvClientsLbl = new JLabel("0 clientes");
            srvClientsLbl.setFont(font(12, false));
            srvClientsLbl.setForeground(new Color(180, 210, 255));
            JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            r.setOpaque(false);
            r.add(srvClientsLbl);
            r.add(srvStatusLbl);
            return r;
        }), BorderLayout.NORTH);

        JPanel cfg = buildSection("Configuración");
        cfg.setLayout(new GridBagLayout());
        GridBagConstraints g = gbc();

        addRow(cfg, g, 0, "Puerto:", srvPort = spin(8080, 1024, 65535));
        addRow(cfg, g, 1, "Política de reinicio:",
                srvPolicyBox = combo("Sin reinicio", "Reinicio automático"));
        addRow(cfg, g, 2, "Delay reinicio (s):", srvDelay = spin(5, 1, 60));
        addRow(cfg, g, 3, "Máx. reinicios:",     srvMaxRestarts = spin(3, 1, 20));

        srvPolicyBox.addActionListener(e -> {
            boolean auto = srvPolicyBox.getSelectedIndex() == 1;
            srvDelay.setEnabled(auto);
            srvMaxRestarts.setEnabled(auto);
        });
        srvDelay.setEnabled(false);
        srvMaxRestarts.setEnabled(false);

        srvLog = makeLog(new Color(235, 250, 238));

        srvStartBtn = btn("▶  Iniciar servidor", C_GREEN);
        srvStopBtn  = btn("■  Detener servidor", C_RED);
        srvStopBtn.setEnabled(false);
        srvStartBtn.addActionListener(e -> startServer());
        srvStopBtn .addActionListener(e -> stopServer());

        JButton clrBtn = btn("⌫  Limpiar", C_GRAY);
        clrBtn.addActionListener(e -> srvLog.setText(""));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        btns.setBackground(C_WHITE);
        btns.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        btns.add(srvStartBtn); btns.add(srvStopBtn); btns.add(clrBtn);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBackground(C_WHITE);
        center.setBorder(new EmptyBorder(10, 14, 6, 14));
        center.add(cfg, BorderLayout.NORTH);
        center.add(new JScrollPane(srvLog) {{
            setBorder(BorderFactory.createLineBorder(C_BORDER));
        }}, BorderLayout.CENTER);

        p.add(center, BorderLayout.CENTER);
        p.add(btns,   BorderLayout.SOUTH);
        return p;
    }

    private void startServer() {
        boolean auto = srvPolicyBox.getSelectedIndex() == 1;
        RestartPolicy.Type pt = auto
                ? RestartPolicy.Type.AUTO_RESTART
                : RestartPolicy.Type.NO_RESTART;
        RestartPolicy policy = new RestartPolicy(pt,
                (Integer) srvDelay.getValue(), (Integer) srvMaxRestarts.getValue());
        int port = (Integer) srvPort.getValue();

        server = new TCPServer(port, policy, msg -> appendLog(srvLog, msg));

        // ── onStarted: se dispara en CADA inicio/reinicio automático ──────
        // FIX Bug 1 UI: el mismo Runnable sirve para el primer arranque y
        // para cada reinicio automático, gracias a que TCPServer.listen()
        // lo invoca al abrir el ServerSocket.
        server.setOnStarted(() -> SwingUtilities.invokeLater(() -> {
            pill(srvStatusLbl, "ONLINE", C_GREEN);
            // Durante reinicio automático los botones ya están en el estado
            // correcto (Start deshabilitado, Stop habilitado), pero si esto
            // ocurre tras un reinicio, los dejamos igual para no confundir.
            srvStartBtn.setEnabled(false);
            srvStopBtn.setEnabled(true);
            srvPort.setEnabled(false);
            srvPolicyBox.setEnabled(false);
        }));

        // ── onStopped: ahora solo se llama AL FINAL del ciclo completo ────
        // FIX Bug 1 UI: ya no se dispara en cada reinicio temporal, solo
        // cuando el servidor se detiene definitivamente.
        server.setOnStopped(() -> SwingUtilities.invokeLater(() -> {
            pill(srvStatusLbl, "OFFLINE", C_RED);
            srvStartBtn.setEnabled(true);
            srvStopBtn.setEnabled(false);
            srvPort.setEnabled(true);
            srvPolicyBox.setEnabled(true);
            srvClientsLbl.setText("0 clientes");

            // FIX Bug 4: detener el timer cuando el servidor se apaga definitivamente
            if (clientCountTimer != null) {
                clientCountTimer.stop();
                clientCountTimer = null;
            }
        }));

        // FIX Bug 4: detener timer anterior (si existía) antes de crear uno nuevo
        if (clientCountTimer != null) clientCountTimer.stop();
        clientCountTimer = new Timer(800, e -> {
            if (server != null && server.isRunning())
                srvClientsLbl.setText(server.clientCount() + " clientes");
        });
        clientCountTimer.start();

        server.start();
        appendLog(srvLog, "Iniciando en puerto " + port + "  |  " +
                (auto ? "Reinicio automático cada " + (Integer) srvDelay.getValue() + "s"
                        : "Sin reinicio"));
    }

    private void stopServer() {
        appendLog(srvLog, "Deteniendo servidor (manual)...");
        if (server != null) server.stop();
    }

    // ════════════════════════════════════════════════════════
    //  PANEL CLIENTES (derecha)
    // ════════════════════════════════════════════════════════
    private JPanel buildClientSide() {
        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(C_WHITE);

        p.add(colorHeader("Clientes TCP", C_PURPLE, () -> null), BorderLayout.NORTH);

        JPanel cfg = buildSection("Configuración de reconexión");
        cfg.setLayout(new GridBagLayout());
        GridBagConstraints g = gbc();

        addRow2(cfg, g, 0, "Host:",           cliHost     = field("localhost"),
                "Puerto:",          cliPort     = spin(8080, 1024, 65535));
        addRow2(cfg, g, 1, "Num. clientes:",   cliNum      = spin(2, 1, 10),
                "Máx. reintentos:", cliRetries  = spin(5, 1, 30));
        addRow2(cfg, g, 2, "Intervalo (s):",   cliInterval = spin(3, 1, 30),
                "Timeout (s):",     cliTimeout  = spin(5, 1, 60));

        cliCards = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        cliCards.setBackground(new Color(248, 246, 255));
        JScrollPane cardsScroll = new JScrollPane(cliCards);
        cardsScroll.setBorder(BorderFactory.createLineBorder(C_BORDER));
        cardsScroll.setPreferredSize(new Dimension(0, 110));
        cardsScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);

        JLabel cardsTitle = new JLabel("Clientes activos");
        cardsTitle.setFont(font(12, true));
        cardsTitle.setForeground(C_TEXT);

        JPanel cardsWrap = new JPanel(new BorderLayout(0, 4));
        cardsWrap.setBackground(C_WHITE);
        cardsWrap.add(cardsTitle,  BorderLayout.NORTH);
        cardsWrap.add(cardsScroll, BorderLayout.CENTER);

        cliLog = makeLog(new Color(240, 238, 252));

        JButton launchBtn = btn("▶  Lanzar clientes", C_GREEN);
        JButton stopBtn   = btn("■  Detener todos",   C_RED);
        JButton clrBtn    = btn("⌫  Limpiar",         C_GRAY);
        launchBtn.addActionListener(e -> launchClients());
        stopBtn  .addActionListener(e -> stopAllClients());
        clrBtn   .addActionListener(e -> cliLog.setText(""));

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        btns.setBackground(C_WHITE);
        btns.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        btns.add(launchBtn); btns.add(stopBtn); btns.add(clrBtn);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBackground(C_WHITE);
        center.setBorder(new EmptyBorder(10, 14, 6, 14));
        center.add(cfg,       BorderLayout.NORTH);
        center.add(cardsWrap, BorderLayout.CENTER);

        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                center, wrapLog(cliLog));
        sp.setDividerLocation(320);
        sp.setDividerSize(4);
        sp.setBorder(null);

        p.add(sp,   BorderLayout.CENTER);
        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    private void launchClients() {
        int n        = (Integer) cliNum.getValue();
        int retries  = (Integer) cliRetries.getValue();
        int interval = (Integer) cliInterval.getValue();
        int timeout  = (Integer) cliTimeout.getValue();
        int port     = (Integer) cliPort.getValue();
        String host  = cliHost.getText().trim();

        appendLog(cliLog, "Lanzando " + n + " cliente(s) → " + host + ":" + port
                + "  [reintentos=" + retries
                + ", intervalo=" + interval + "s"
                + ", timeout=" + timeout + "s]");

        for (int i = 0; i < n; i++) {
            cliCounter++;
            String id = "C" + (cliCounter < 10 ? "0" + cliCounter : "" + cliCounter);
            ReconnectionPolicy pol = new ReconnectionPolicy(retries, interval, timeout);
            TCPClient c = new TCPClient(id, host, port, pol, msg -> appendLog(cliLog, msg));
            JPanel card = buildCard(id);
            c.setOnStateChange(st -> SwingUtilities.invokeLater(() -> updateCard(card, st)));
            clients.add(c);
            cliCards.add(card);
            cliCards.revalidate();
            c.connect();
        }
    }

    private void stopAllClients() {
        appendLog(cliLog, "Deteniendo todos los clientes...");
        for (TCPClient c : clients) c.disconnect();
        clients.clear();
        cliCards.removeAll();
        cliCards.revalidate();
        cliCards.repaint();
    }

    // ── Tarjeta de cliente ───────────────────────────────────
    private JPanel buildCard(String id) {
        JPanel card = new JPanel(new BorderLayout(2, 2));
        card.setBackground(C_WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER, 2),
                new EmptyBorder(6, 12, 6, 12)
        ));
        card.setPreferredSize(new Dimension(100, 66));

        JLabel idLbl = new JLabel(id, SwingConstants.CENTER);
        idLbl.setFont(font(15, true));
        idLbl.setForeground(C_PURPLE);

        JLabel stLbl = new JLabel("Iniciando", SwingConstants.CENTER);
        stLbl.setName("state");
        stLbl.setFont(font(10, false));
        stLbl.setForeground(C_ORANGE);

        card.add(idLbl, BorderLayout.CENTER);
        card.add(stLbl, BorderLayout.SOUTH);
        return card;
    }

    private void updateCard(JPanel card, TCPClient.State st) {
        String text; Color textColor, border;
        switch (st) {
            case CONNECTED:  text = "Conectado";    textColor = C_GREEN;  border = C_GREEN;  break;
            case CONNECTING: text = "Conectando";   textColor = C_BLUE;   border = C_BLUE;   break;
            case RETRYING:   text = "Reintentando"; textColor = C_ORANGE; border = C_ORANGE; break;
            case FAILED:     text = "Fallido";      textColor = C_RED;    border = C_RED;    break;
            default:         text = "Idle";         textColor = C_GRAY;   border = C_BORDER; break;
        }
        findLabel(card, "state", text, textColor);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 2),
                new EmptyBorder(6, 12, 6, 12)
        ));
    }

    private void findLabel(Container c, String name, String text, Color color) {
        for (Component comp : c.getComponents()) {
            if (comp instanceof JLabel && name.equals(comp.getName())) {
                ((JLabel) comp).setText(text);
                ((JLabel) comp).setForeground(color);
            } else if (comp instanceof Container) {
                findLabel((Container) comp, name, text, color);
            }
        }
    }

    // ════════════════════════════════════════════════════════
    //  HELPERS GENERALES DE UI
    // ════════════════════════════════════════════════════════
    private JPanel colorHeader(String title, Color bg,
                               java.util.function.Supplier<JComponent> right) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(bg);
        p.setBorder(new EmptyBorder(12, 16, 12, 16));
        JLabel t = new JLabel(title);
        t.setFont(font(16, true));
        t.setForeground(Color.WHITE);
        p.add(t, BorderLayout.WEST);
        JComponent r = right.get();
        if (r != null) p.add(r, BorderLayout.EAST);
        return p;
    }

    private JPanel buildSection(String title) {
        JPanel p = new JPanel();
        p.setBackground(new Color(248, 249, 253));
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(), title);
        tb.setTitleFont(font(12, true));
        tb.setTitleColor(C_GRAY);
        p.setBorder(BorderFactory.createCompoundBorder(tb, new EmptyBorder(4, 4, 4, 4)));
        return p;
    }

    private void addRow(JPanel p, GridBagConstraints g, int row,
                        String lbl, JComponent field) {
        g.gridx = 0; g.gridy = row; g.weightx = 0; p.add(label(lbl), g);
        g.gridx = 1; g.weightx = 1; p.add(field, g);
    }

    private void addRow2(JPanel p, GridBagConstraints g, int row,
                         String l1, JComponent f1, String l2, JComponent f2) {
        g.gridx = 0; g.gridy = row; g.weightx = 0; p.add(label(l1), g);
        g.gridx = 1; g.weightx = 1; p.add(f1, g);
        g.gridx = 2; g.weightx = 0; p.add(label(l2), g);
        g.gridx = 3; g.weightx = 1; p.add(f2, g);
    }

    private GridBagConstraints gbc() {
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(4, 6, 4, 6);
        g.anchor = GridBagConstraints.WEST;
        g.fill   = GridBagConstraints.HORIZONTAL;
        return g;
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(12, false));
        l.setForeground(C_TEXT);
        return l;
    }

    private JSpinner spin(int val, int min, int max) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(val, min, max, 1));
        s.setFont(font(12, false));
        s.setPreferredSize(new Dimension(85, 28));
        return s;
    }

    private JComboBox<String> combo(String... items) {
        JComboBox<String> cb = new JComboBox<>(items);
        cb.setFont(font(12, false));
        cb.setBackground(C_WHITE);
        return cb;
    }

    private JTextField field(String txt) {
        JTextField f = new JTextField(txt, 10);
        f.setFont(font(12, false));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER),
                new EmptyBorder(3, 6, 3, 6)
        ));
        return f;
    }

    private JButton btn(String text, Color color) {
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
            public void mouseEntered(java.awt.event.MouseEvent e) {
                if (b.isEnabled()) b.setBackground(color.darker());
            }
            public void mouseExited(java.awt.event.MouseEvent e) {
                if (b.isEnabled()) b.setBackground(color);
            }
        });
        return b;
    }

    private JLabel statusPill(String text, Color bg) {
        JLabel l = new JLabel("  " + text + "  ");
        l.setFont(font(12, true));
        l.setOpaque(true);
        l.setBackground(bg);
        l.setForeground(Color.WHITE);
        l.setBorder(new EmptyBorder(3, 8, 3, 8));
        return l;
    }

    private void pill(JLabel l, String text, Color bg) {
        l.setText("  " + text + "  ");
        l.setBackground(bg);
    }

    private JTextArea makeLog(Color bg) {
        JTextArea t = new JTextArea();
        t.setFont(new Font("Monospaced", Font.PLAIN, 11));
        t.setBackground(bg);
        t.setForeground(C_TEXT);
        t.setEditable(false);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setBorder(new EmptyBorder(6, 8, 6, 8));
        return t;
    }

    private JPanel wrapLog(JTextArea log) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(C_WHITE);
        p.setBorder(new EmptyBorder(0, 14, 6, 14));
        JLabel t = new JLabel("Registro");
        t.setFont(font(12, true));
        t.setForeground(C_TEXT);
        JScrollPane sc = new JScrollPane(log);
        sc.setBorder(BorderFactory.createLineBorder(C_BORDER));
        p.add(t,  BorderLayout.NORTH);
        p.add(sc, BorderLayout.CENTER);
        return p;
    }

    private void appendLog(JTextArea area, String msg) {
        SwingUtilities.invokeLater(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            area.append("[" + time + "]  " + msg + "\n");
            area.setCaretPosition(area.getDocument().getLength());
        });
    }

    private Font font(int size, boolean bold) {
        return new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size);
    }

    private Color mix(Color c1, Color c2, float f) {
        return new Color(
                (int)(c1.getRed()   * (1 - f) + c2.getRed()   * f),
                (int)(c1.getGreen() * (1 - f) + c2.getGreen() * f),
                (int)(c1.getBlue()  * (1 - f) + c2.getBlue()  * f)
        );
    }

    // ════════════════════════════════════════════════════════
    //  MAIN
    // ════════════════════════════════════════════════════════
    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(MainApp::new);
    }
}