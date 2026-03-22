package ui;

import client.*;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Interfaz dedicada únicamente a los Clientes TCP.
 * Ejecutar esta clase de forma independiente a ServerApp.
 */
public class ClientApp extends JFrame {

    // ── Colores ──────────────────────────────────────────────
    static final Color C_WHITE  = Color.WHITE;
    static final Color C_BG     = new Color(245, 247, 250);
    static final Color C_BORDER = new Color(200, 205, 215);
    static final Color C_PURPLE = new Color(120, 70, 190);
    static final Color C_GREEN  = new Color(34, 160, 90);
    static final Color C_RED    = new Color(210, 50, 50);
    static final Color C_BLUE   = new Color(52, 120, 200);
    static final Color C_ORANGE = new Color(220, 120, 20);
    static final Color C_GRAY   = new Color(120, 125, 135);
    static final Color C_TEXT   = new Color(25, 30, 45);

    // ── Estado ───────────────────────────────────────────────
    private final List<TCPClient> clients = new ArrayList<>();
    private int clientCounter = 0;

    // ── Componentes ──────────────────────────────────────────
    private JTextField        hostField;
    private JSpinner          portSpinner;
    private JSpinner          numSpinner;
    private JSpinner          retriesSpinner;
    private JSpinner          intervalSpinner;
    private JSpinner          timeoutSpinner;
    private JPanel            cardsPanel;
    private JTextArea         logArea;
    private JLabel            summaryLbl;

    public ClientApp() {
        super("Clientes TCP");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(600, 680);
        setMinimumSize(new Dimension(500, 560));
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
    }

    // ════════════════════════════════════════════════════════
    //  SHUTDOWN HOOK — Cierre por proceso externo
    // ════════════════════════════════════════════════════════
    /**
     * Se ejecuta cuando el proceso es terminado externamente:
     * Administrador de Tareas, Ctrl+C, kill, cierre del SO.
     * Desconecta todos los clientes limpiamente antes de morir.
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            if (logArea != null) {
                logArea.append("\n[" + time + "]  ⚠ PROCESO TERMINADO EXTERNAMENTE\n");
                logArea.append("[" + time + "]  ⚠ (Administrador de Tareas / Ctrl+C / kill)\n");
                logArea.append("[" + time + "]  → Desconectando " + clients.size() + " cliente(s)...\n");
            }
            System.out.println("[" + time + "] [ShutdownHook] Proceso de clientes terminado externamente.");

            for (TCPClient c : clients) c.disconnect();

            if (logArea != null)
                logArea.append("[" + time + "]  ✔ Clientes desconectados. Proceso cerrado.\n");
            System.out.println("[ShutdownHook] Clientes desconectados. Proceso cerrado.");
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }, "ShutdownHook-Client"));
    }

    // ── Cabecera ─────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_PURPLE);
        p.setBorder(new EmptyBorder(16, 20, 16, 20));

        JLabel title = new JLabel("Clientes TCP");
        title.setFont(font(20, true));
        title.setForeground(Color.WHITE);

        summaryLbl = new JLabel("Sin clientes activos");
        summaryLbl.setFont(font(12, false));
        summaryLbl.setForeground(new Color(210, 190, 255));

        p.add(title,      BorderLayout.WEST);
        p.add(summaryLbl, BorderLayout.EAST);
        return p;
    }

    // ── Centro ───────────────────────────────────────────────
    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setBackground(C_WHITE);
        p.setBorder(new EmptyBorder(16, 20, 8, 20));
        p.add(buildConfig(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildCards(), buildLog());
        split.setDividerLocation(140);
        split.setDividerSize(4);
        split.setBorder(null);
        split.setBackground(C_WHITE);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildConfig() {
        JPanel p = sectionPanel("Conexión y política de reconexión");
        p.setLayout(new GridBagLayout());
        GridBagConstraints g = gbc();

        hostField       = textField("localhost");
        portSpinner     = spinner(8080, 1024, 65535);
        numSpinner      = spinner(2, 1, 10);
        retriesSpinner  = spinner(5, 1, 30);
        intervalSpinner = spinner(3, 1, 30);
        timeoutSpinner  = spinner(5, 1, 60);

        addRow2(p, g, 0, "Host:",            hostField,
                "Puerto:",           portSpinner);
        addRow2(p, g, 1, "Núm. clientes:",   numSpinner,
                "Máx. reintentos:", retriesSpinner);
        addRow2(p, g, 2, "Intervalo (s):",   intervalSpinner,
                "Timeout (s):",     timeoutSpinner);
        return p;
    }

    private JPanel buildCards() {
        cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        cardsPanel.setBackground(new Color(248, 246, 255));

        JScrollPane scroll = new JScrollPane(cardsPanel);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER));
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);

        JLabel lbl = new JLabel("Estado de clientes");
        lbl.setFont(font(12, true));
        lbl.setForeground(C_TEXT);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(C_WHITE);
        p.add(lbl,    BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildLog() {
        logArea = new JTextArea();
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setBackground(new Color(240, 238, 252));
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
        JButton launchBtn = actionBtn("▶  Lanzar clientes", C_GREEN);
        JButton stopBtn   = actionBtn("■  Detener todos",   C_RED);
        JButton clearBtn  = actionBtn("⌫  Limpiar log",     C_GRAY);

        launchBtn.addActionListener(e -> launchClients());
        stopBtn  .addActionListener(e -> stopAllClients());
        clearBtn .addActionListener(e -> logArea.setText(""));

        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
        p.setBackground(C_WHITE);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        p.add(launchBtn);
        p.add(stopBtn);
        p.add(clearBtn);
        return p;
    }

    // ── Lógica ───────────────────────────────────────────────
    private void launchClients() {
        int n        = (Integer) numSpinner.getValue();
        int retries  = (Integer) retriesSpinner.getValue();
        int interval = (Integer) intervalSpinner.getValue();
        int timeout  = (Integer) timeoutSpinner.getValue();
        int port     = (Integer) portSpinner.getValue();
        String host  = hostField.getText().trim();

        log("Lanzando " + n + " cliente(s) → " + host + ":" + port
                + "  [reintentos=" + retries
                + ", intervalo=" + interval + "s"
                + ", timeout=" + timeout + "s]");

        for (int i = 0; i < n; i++) {
            clientCounter++;
            String id = "C" + String.format("%02d", clientCounter);
            ReconnectionPolicy pol = new ReconnectionPolicy(retries, interval, timeout);
            TCPClient c = new TCPClient(id, host, port, pol, this::log);
            JPanel card = buildCard(id);
            c.setOnStateChange(st -> SwingUtilities.invokeLater(() -> {
                updateCard(card, st);
                updateSummary();
            }));
            clients.add(c);
            cardsPanel.add(card);
            cardsPanel.revalidate();
            c.connect();
        }
        updateSummary();
    }

    private void stopAllClients() {
        log("Deteniendo todos los clientes...");
        for (TCPClient c : clients) c.disconnect();
        clients.clear();
        cardsPanel.removeAll();
        cardsPanel.revalidate();
        cardsPanel.repaint();
        summaryLbl.setText("Sin clientes activos");
    }

    // ── Tarjeta de cliente ───────────────────────────────────
    private JPanel buildCard(String id) {
        JPanel card = new JPanel(new BorderLayout(2, 4));
        card.setBackground(C_WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER, 2),
                new EmptyBorder(8, 14, 8, 14)
        ));
        card.setPreferredSize(new Dimension(110, 72));

        JLabel idLbl = new JLabel(id, SwingConstants.CENTER);
        idLbl.setFont(font(16, true));
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
                new EmptyBorder(8, 14, 8, 14)
        ));
    }

    /** Actualiza el resumen en la cabecera con conteos por estado. */
    private void updateSummary() {
        long connected  = clients.stream().filter(c -> c.getState() == TCPClient.State.CONNECTED).count();
        long retrying   = clients.stream().filter(c -> c.getState() == TCPClient.State.RETRYING).count();
        long failed     = clients.stream().filter(c -> c.getState() == TCPClient.State.FAILED).count();
        long total      = clients.size();

        if (total == 0) {
            summaryLbl.setText("Sin clientes activos");
        } else {
            summaryLbl.setText(
                    total + " clientes  ·  "
                            + connected + " conectados  ·  "
                            + retrying  + " reintentando  ·  "
                            + failed    + " fallidos"
            );
        }
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

    private void addRow2(JPanel p, GridBagConstraints g, int row,
                         String l1, JComponent f1, String l2, JComponent f2) {
        g.gridx = 0; g.gridy = row; g.weightx = 0; p.add(lbl(l1), g);
        g.gridx = 1; g.weightx = 1; p.add(f1, g);
        g.gridx = 2; g.weightx = 0; p.add(lbl(l2), g);
        g.gridx = 3; g.weightx = 1; p.add(f2, g);
    }

    private JLabel lbl(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(12, false));
        l.setForeground(C_TEXT);
        return l;
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

    private JTextField textField(String text) {
        JTextField f = new JTextField(text, 10);
        f.setFont(font(12, false));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER),
                new EmptyBorder(3, 6, 3, 6)
        ));
        return f;
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
        SwingUtilities.invokeLater(ClientApp::new);
    }
}