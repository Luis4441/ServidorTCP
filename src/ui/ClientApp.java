package ui;

import balancer.BalancerClient;
import client.*;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class ClientApp extends JFrame {

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
    static final Color C_TEAL   = new Color(20, 150, 160);

    private final List<TCPClient> clients = new ArrayList<>();
    private int clientCounter = 0;

    private JTextField        hostField;
    private JSpinner          portSpinner;
    private JSpinner          numSpinner;
    private JSpinner          retriesSpinner;
    private JSpinner          intervalSpinner;
    private JSpinner          timeoutSpinner;
    private JCheckBox         useBalancerCheck;
    private JPanel            cardsPanel;
    private JTextArea         logArea;
    private JLabel            summaryLbl;

    public ClientApp() {
        super("Clientes TCP");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(620, 720);
        setMinimumSize(new Dimension(520, 580));
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

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (TCPClient c : clients) c.disconnect();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }, "ShutdownHook-Client"));
    }

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

    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setBackground(C_WHITE);
        p.setBorder(new EmptyBorder(16, 20, 8, 20));
        p.add(buildConfig(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildCards(), buildLog());
        split.setDividerLocation(160);
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

        hostField        = textField("localhost");
        portSpinner      = spinner(8080, 1024, 65535);
        numSpinner       = spinner(2, 1, 20);
        retriesSpinner   = spinner(99, 1, 999);
        intervalSpinner  = spinner(3, 1, 30);
        timeoutSpinner   = spinner(5, 1, 60);
        useBalancerCheck = new JCheckBox("Usar balanceador (failover automático)");
        useBalancerCheck.setFont(font(12, false));
        useBalancerCheck.setForeground(C_TEXT);
        useBalancerCheck.setOpaque(false);
        useBalancerCheck.setSelected(true);
        useBalancerCheck.addActionListener(e -> {
            boolean manual = !useBalancerCheck.isSelected();
            hostField  .setEnabled(manual);
            portSpinner.setEnabled(manual);
        });
        hostField  .setEnabled(false);
        portSpinner.setEnabled(false);

        addRow2(p, g, 0, "Host:",           hostField,
                "Puerto:",          portSpinner);
        addRow2(p, g, 1, "Núm. clientes:",  numSpinner,
                "Máx. reintentos:", retriesSpinner);
        addRow2(p, g, 2, "Intervalo (s):",  intervalSpinner,
                "Timeout (s):",     timeoutSpinner);

        GridBagConstraints fullRow = gbc();
        fullRow.gridx = 0; fullRow.gridy = 3;
        fullRow.gridwidth = 4; fullRow.weightx = 1;
        p.add(useBalancerCheck, fullRow);

        return p;
    }

    private JPanel buildCards() {
        cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        cardsPanel.setBackground(new Color(248, 246, 255));

        JScrollPane scroll = new JScrollPane(cardsPanel);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER));
        scroll.getVerticalScrollBar().setUnitIncrement(10);

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
        p.add(launchBtn); p.add(stopBtn); p.add(clearBtn);
        return p;
    }

    // ════════════════════════════════════════════════════════
    //  LÓGICA — lanzamiento con portResolver dinámico (FIXED)
    // ════════════════════════════════════════════════════════
    private void launchClients() {
        int n        = (Integer) numSpinner.getValue();
        int retries  = (Integer) retriesSpinner.getValue();
        int interval = (Integer) intervalSpinner.getValue();
        int timeout  = (Integer) timeoutSpinner.getValue();
        boolean useBalancer = useBalancerCheck.isSelected();

        log("Lanzando " + n + " cliente(s)"
                + (useBalancer ? " vía balanceador (failover automático)"
                : " → " + hostField.getText().trim()
                + ":" + portSpinner.getValue()));

        new Thread(() -> {
            for (int i = 0; i < n; i++) {
                clientCounter++;
                final String id = "C" + String.format("%02d", clientCounter);

                final int    initialPort;
                final String host;

                if (useBalancer) {
                    int assigned = BalancerClient.requestNextServer();
                    if (assigned < 0) {
                        log("[" + id + "] ✖ Sin servidores disponibles — cancelado.");
                        clientCounter--;
                        continue;
                    }
                    host        = "localhost";
                    initialPort = assigned;
                    log("[" + id + "] ⚖ Asignado → :" + assigned + " (round-robin)");
                } else {
                    host        = hostField.getText().trim();
                    initialPort = (Integer) portSpinner.getValue();
                }

                ReconnectionPolicy pol = new ReconnectionPolicy(retries, interval, timeout);

                // ── portResolver dinámico (FIXED) ────────────────────────────
                // originalPort = puerto del primario asignado al cliente. NUNCA cambia.
                // Siempre consultamos REDIRECT_TARGET con este puerto, no con el último
                // puerto al que nos conectamos. Así, cuando el primario vuelve después
                // de un failover, el balanceador devuelve el primario y el cliente
                // regresa automáticamente (failback transparente).
                final int originalPort = initialPort;
                final int[] currentPort = { initialPort }; // solo para detectar cambios en UI

                java.util.function.IntSupplier portResolver = () -> {
                    if (!useBalancer) return originalPort;
                    int resolved = BalancerClient.requestRedirectTarget(originalPort);
                    // Notificar a la UI solo cuando el puerto destino cambia
                    if (resolved > 0 && resolved != currentPort[0]) {
                        currentPort[0] = resolved;
                    }
                    return resolved; // -1 si no hay servidor disponible
                };

                TCPClient c = new TCPClient(id, host, portResolver, pol, this::log);
                JPanel card = buildCard(id, initialPort);

                c.setOnStateChange(st -> SwingUtilities.invokeLater(() -> {
                    updateCard(card, st);
                    updateSummary();
                }));

                c.setOnPortChange(newPort -> SwingUtilities.invokeLater(() ->
                        updateCardPort(card, newPort)));

                final TCPClient finalC = c;
                SwingUtilities.invokeLater(() -> {
                    clients.add(finalC);
                    cardsPanel.add(card);
                    cardsPanel.revalidate();
                    updateSummary();
                });

                c.connect();
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
            }
        }, "ClientLauncher").start();
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

    private JPanel buildCard(String id, int port) {
        JPanel card = new JPanel(new BorderLayout(2, 2));
        card.setBackground(C_WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER, 2),
                new EmptyBorder(6, 12, 6, 12)
        ));
        card.setPreferredSize(new Dimension(118, 86));

        JLabel idLbl = new JLabel(id, SwingConstants.CENTER);
        idLbl.setFont(font(15, true));
        idLbl.setForeground(C_PURPLE);

        JLabel portLbl = new JLabel("→ :" + port, SwingConstants.CENTER);
        portLbl.setName("port");
        portLbl.setFont(font(10, false));
        portLbl.setForeground(C_TEAL);

        JLabel stLbl = new JLabel("Iniciando", SwingConstants.CENTER);
        stLbl.setName("state");
        stLbl.setFont(font(10, false));
        stLbl.setForeground(C_ORANGE);

        JPanel south = new JPanel(new GridLayout(2, 1, 0, 1));
        south.setOpaque(false);
        south.add(portLbl);
        south.add(stLbl);

        card.add(idLbl, BorderLayout.CENTER);
        card.add(south, BorderLayout.SOUTH);
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

    private void updateCardPort(JPanel card, int newPort) {
        for (java.awt.Component comp : card.getComponents()) {
            if (comp instanceof JPanel) {
                for (java.awt.Component inner : ((JPanel) comp).getComponents()) {
                    if (inner instanceof JLabel && "port".equals(inner.getName())) {
                        ((JLabel) inner).setText("→ :" + newPort);
                        ((JLabel) inner).setForeground(C_ORANGE);
                    }
                }
            }
        }
    }

    private void updateSummary() {
        long connected = clients.stream().filter(c -> c.getState() == TCPClient.State.CONNECTED).count();
        long retrying  = clients.stream().filter(c -> c.getState() == TCPClient.State.RETRYING).count();
        long failed    = clients.stream().filter(c -> c.getState() == TCPClient.State.FAILED).count();
        long total     = clients.size();
        summaryLbl.setText(total == 0 ? "Sin clientes activos"
                : total + " clientes  ·  " + connected + " conectados  ·  "
                + retrying + " reintentando  ·  " + failed + " fallidos");
    }

    private void findLabel(java.awt.Container c, String name, String text, Color color) {
        for (java.awt.Component comp : c.getComponents()) {
            if (comp instanceof JLabel && name.equals(comp.getName())) {
                ((JLabel) comp).setText(text);
                ((JLabel) comp).setForeground(color);
            } else if (comp instanceof java.awt.Container) {
                findLabel((java.awt.Container) comp, name, text, color);
            }
        }
    }

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
                new EmptyBorder(3, 6, 3, 6)));
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

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(ClientApp::new);
    }
}