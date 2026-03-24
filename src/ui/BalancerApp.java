package ui;

import balancer.Loadbalancer;
import client.ReconnectionPolicy;
import client.TCPClientBalanced;
import server.RestartPolicy;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BalancerApp v2 — Mejoras visuales:
 *  1. Log separado: servidor (izq) y clientes (der)
 *  2. Tarjetas en grid 2 columnas, acumulan hacia abajo
 *  3. Tarjeta muestra a qué servidor está conectado (ej: "→ S02:8081")
 *  4. Tabla de servidores muestra qué clientes tiene (ej: "C03, C05")
 */
public class BalancerApp extends JFrame {

    // ── Colores ──────────────────────────────────────────────
    static final Color C_WHITE  = Color.WHITE;
    static final Color C_BORDER = new Color(200, 205, 215);
    static final Color C_TEAL   = new Color(20, 130, 130);
    static final Color C_GREEN  = new Color(34, 160, 90);
    static final Color C_RED    = new Color(210, 50, 50);
    static final Color C_ORANGE = new Color(220, 120, 20);
    static final Color C_GRAY   = new Color(120, 125, 135);
    static final Color C_PURPLE = new Color(120, 70, 190);
    static final Color C_TEXT   = new Color(25, 30, 45);
    static final Color C_BLUE   = new Color(52, 120, 200);

    // ── Estado ───────────────────────────────────────────────
    private Loadbalancer                      balancer;
    private final List<TCPClientBalanced>     clients     = new ArrayList<>();
    private final Map<String, JPanel>         cardMap     = new LinkedHashMap<>();
    // clientId → serverId que tiene asignado
    private final Map<String, String>         clientServer = new ConcurrentHashMap<>();
    private int   clientCounter = 0;
    private Timer statusTimer;

    // ── Config servidores ─────────────────────────────────────
    private JSpinner          numServersSpinner, basePortSpinner;
    private JSpinner          delaySpinner, maxRestartsSpinner;
    private JComboBox<String> policyBox;
    private DefaultTableModel serverTableModel;
    private JTable            serverTable;
    private JLabel            balancerStatusLbl;
    private JButton           startAllBtn, stopAllBtn, crashAllBtn;

    // ── Config clientes ───────────────────────────────────────
    private JSpinner numClientsSpinner, retriesSpinner, intervalSpinner, timeoutSpinner;
    private JPanel   cardsPanel;
    private JLabel   clientSummaryLbl;

    // ── Logs separados ────────────────────────────────────────
    private JTextArea serverLogArea;
    private JTextArea clientLogArea;

    // ════════════════════════════════════════════════════════
    public BalancerApp() {
        super("TCP Balanceador de Carga");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 820);
        setMinimumSize(new Dimension(1000, 700));
        setLocationRelativeTo(null);
        registerShutdownHook();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(C_WHITE);
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildMain(),   BorderLayout.CENTER);
        setContentPane(root);
        setVisible(true);
    }

    // ── Cabecera ─────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_TEAL);
        p.setBorder(new EmptyBorder(14, 20, 14, 20));

        JLabel title = new JLabel("TCP — Balanceador de Carga");
        title.setFont(font(20, true));
        title.setForeground(Color.WHITE);

        balancerStatusLbl = new JLabel("Sin servidores activos");
        balancerStatusLbl.setFont(font(12, false));
        balancerStatusLbl.setForeground(new Color(180, 230, 230));

        p.add(title,             BorderLayout.WEST);
        p.add(balancerStatusLbl, BorderLayout.EAST);
        return p;
    }

    // ── Layout principal: izq | der, cada mitad tiene panel + log ──────
    private JPanel buildMain() {
        // Panel izquierdo: config servidores + tabla + log servidor
        JPanel left = new JPanel(new BorderLayout(0, 0));
        left.setBackground(C_WHITE);
        left.setBorder(new EmptyBorder(12, 12, 12, 6));

        JPanel leftTop = new JPanel(new BorderLayout(0, 10));
        leftTop.setBackground(C_WHITE);
        leftTop.add(buildServerConfig(), BorderLayout.NORTH);
        leftTop.add(buildServerTable(),  BorderLayout.CENTER);
        leftTop.add(buildServerButtons(), BorderLayout.SOUTH);

        left.add(leftTop,           BorderLayout.CENTER);
        left.add(buildServerLog(),  BorderLayout.SOUTH);

        // Panel derecho: config clientes + tarjetas + log clientes
        JPanel right = new JPanel(new BorderLayout(0, 0));
        right.setBackground(C_WHITE);
        right.setBorder(new EmptyBorder(12, 6, 12, 12));

        JPanel rightTop = new JPanel(new BorderLayout(0, 10));
        rightTop.setBackground(C_WHITE);
        rightTop.add(buildClientConfig(),  BorderLayout.NORTH);
        rightTop.add(buildClientCards(),   BorderLayout.CENTER);
        rightTop.add(buildClientButtons(), BorderLayout.SOUTH);

        right.add(rightTop,          BorderLayout.CENTER);
        right.add(buildClientLog(),  BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(550);
        split.setDividerSize(5);
        split.setBorder(null);
        return wrapInPanel(split);
    }

    private JPanel wrapInPanel(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_WHITE);
        p.add(c);
        return p;
    }

    // ════════════════════════════════════════════════════════
    //  PANEL IZQUIERDO — Servidores
    // ════════════════════════════════════════════════════════
    private JPanel buildServerConfig() {
        JPanel p = sectionPanel("Configuración del pool de servidores");
        p.setLayout(new GridBagLayout());
        GridBagConstraints g = gbc();

        numServersSpinner  = spinner(3, 1, 10);
        basePortSpinner    = spinner(8080, 1024, 65530);
        delaySpinner       = spinner(5, 1, 60);
        maxRestartsSpinner = spinner(3, 1, 20);
        policyBox          = combo("Sin reinicio", "Reinicio automático");

        addRow2(p, g, 0, "Núm. servidores:", numServersSpinner, "Puerto base:", basePortSpinner);
        addRowFull(p, g, 1, "Política de reinicio:", policyBox);
        addRow2(p, g, 2, "Delay reinicio (s):", delaySpinner, "Máx. reinicios:", maxRestartsSpinner);

        delaySpinner.setEnabled(false);
        maxRestartsSpinner.setEnabled(false);
        policyBox.addActionListener(e -> {
            boolean auto = policyBox.getSelectedIndex() == 1;
            delaySpinner.setEnabled(auto);
            maxRestartsSpinner.setEnabled(auto);
        });
        return p;
    }

    private JPanel buildServerTable() {
        // Columnas: ID | Puerto | Estado | Clientes conectados | Acciones
        String[] cols = { "ID", "Puerto", "Estado", "Clientes", "Acciones" };
        serverTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return c == 4; }
        };
        serverTable = new JTable(serverTableModel);
        serverTable.setFont(font(12, false));
        serverTable.setRowHeight(34);
        serverTable.getTableHeader().setFont(font(12, true));
        serverTable.getTableHeader().setBackground(new Color(235, 250, 252));
        serverTable.setGridColor(C_BORDER);
        serverTable.setShowGrid(true);
        serverTable.setSelectionBackground(new Color(210, 245, 245));

        serverTable.getColumnModel().getColumn(4).setCellRenderer(new ActionCellRenderer());
        serverTable.getColumnModel().getColumn(4).setCellEditor(new ActionCellEditor(new JCheckBox()));

        serverTable.getColumnModel().getColumn(0).setPreferredWidth(45);
        serverTable.getColumnModel().getColumn(1).setPreferredWidth(60);
        serverTable.getColumnModel().getColumn(2).setPreferredWidth(70);
        serverTable.getColumnModel().getColumn(3).setPreferredWidth(130); // nombres de clientes
        serverTable.getColumnModel().getColumn(4).setPreferredWidth(150);

        JScrollPane scroll = new JScrollPane(serverTable);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER));

        JLabel lbl = new JLabel("Pool de servidores");
        lbl.setFont(font(12, true));
        lbl.setForeground(C_TEXT);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(C_WHITE);
        p.add(lbl,    BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildServerButtons() {
        startAllBtn = actionBtn("▶  Iniciar todos",      C_GREEN,  160);
        stopAllBtn  = actionBtn("■  Detener todos",      C_RED,    160);
        crashAllBtn = actionBtn("Simular Caída todos",  C_ORANGE, 160);

        stopAllBtn .setEnabled(false);
        crashAllBtn.setEnabled(false);

        startAllBtn.addActionListener(e -> startAllServers());
        stopAllBtn .addActionListener(e -> stopAllServers());
        crashAllBtn.addActionListener(e -> crashAllServers());

        JLabel hint = new JLabel(
                "<html><center><i><b>Simular Caída</b>: fallo brusco → la política decide." +
                        "  <b>Detener</b>: parada limpia → nunca reinicia.</i></center></html>");
        hint.setFont(font(10, false));
        hint.setForeground(C_GRAY);
        hint.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
        row.setBackground(C_WHITE);
        row.add(startAllBtn); row.add(stopAllBtn); row.add(crashAllBtn);

        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_WHITE);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        p.add(row,  BorderLayout.CENTER);
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    /** Log exclusivo del servidor (fondo verde claro) */
    private JPanel buildServerLog() {
        serverLogArea = new JTextArea(7, 0);
        serverLogArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        serverLogArea.setBackground(new Color(235, 250, 238));
        serverLogArea.setForeground(C_TEXT);
        serverLogArea.setEditable(false);
        serverLogArea.setLineWrap(true);
        serverLogArea.setWrapStyleWord(true);
        serverLogArea.setBorder(new EmptyBorder(6, 10, 6, 10));

        JScrollPane scroll = new JScrollPane(serverLogArea);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER));

        JLabel lbl = new JLabel("Registro de eventos — Servidores");
        lbl.setFont(font(12, true));
        lbl.setForeground(C_TEXT);

        JButton clearBtn = smallBtn("Limpiar", C_GRAY);
        clearBtn.addActionListener(e -> {
            serverLogArea.setText("");
            serverTableModel.setRowCount(0);
            if (statusTimer != null) { statusTimer.stop(); statusTimer = null; }
            if (balancer != null) { balancer.stopAll(); balancer = null; }
            startAllBtn.setEnabled(true);
            stopAllBtn .setEnabled(false);
            crashAllBtn.setEnabled(false);
            numServersSpinner.setEnabled(true);
            basePortSpinner  .setEnabled(true);
            policyBox        .setEnabled(true);
            balancerStatusLbl.setText("Sin servidores activos");
        });

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(C_WHITE);
        header.add(lbl, BorderLayout.WEST);
        header.add(clearBtn, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(C_WHITE);
        p.setBorder(new EmptyBorder(8, 0, 0, 0));
        p.add(header, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    // ════════════════════════════════════════════════════════
    //  PANEL DERECHO — Clientes
    // ════════════════════════════════════════════════════════
    private JPanel buildClientConfig() {
        JPanel p = sectionPanel("Configuración de clientes");
        p.setLayout(new GridBagLayout());
        GridBagConstraints g = gbc();

        numClientsSpinner = spinner(2, 1, 20);
        retriesSpinner    = spinner(5, 1, 30);
        intervalSpinner   = spinner(3, 1, 30);
        timeoutSpinner    = spinner(5, 1, 60);

        addRow2(p, g, 0, "Núm. clientes:", numClientsSpinner, "Máx. reintentos:", retriesSpinner);
        addRow2(p, g, 1, "Intervalo (s):", intervalSpinner,   "Timeout (s):",     timeoutSpinner);
        return p;
    }

    /**
     * Tarjetas en grid de 2 columnas.
     * Cada tarjeta muestra: ID + estado + servidor asignado.
     */
    private JPanel buildClientCards() {
        // GridLayout dinámico: usamos WrapLayout para 2 columnas
        cardsPanel = new JPanel(new GridLayout(0, 2, 8, 8));
        cardsPanel.setBackground(new Color(248, 246, 255));
        cardsPanel.setBorder(new EmptyBorder(6, 6, 6, 6));

        JScrollPane scroll = new JScrollPane(cardsPanel);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER));
        scroll.setPreferredSize(new Dimension(0, 200));
        scroll.getVerticalScrollBarPolicy();

        clientSummaryLbl = new JLabel("Sin clientes activos");
        clientSummaryLbl.setFont(font(11, false));
        clientSummaryLbl.setForeground(C_GRAY);

        JLabel lbl = new JLabel("Estado de clientes");
        lbl.setFont(font(12, true));
        lbl.setForeground(C_TEXT);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(C_WHITE);
        header.add(lbl,             BorderLayout.WEST);
        header.add(clientSummaryLbl, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBackground(C_WHITE);
        p.add(header, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildClientButtons() {
        JButton launchBtn = actionBtn("▶  Lanzar clientes", C_GREEN, 160);
        JButton stopBtn   = actionBtn("■  Detener todos",   C_RED,   160);

        launchBtn.addActionListener(e -> launchClients());
        stopBtn  .addActionListener(e -> stopAllClients());

        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
        p.setBackground(C_WHITE);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        p.add(launchBtn);
        p.add(stopBtn);
        return p;
    }

    /** Log exclusivo de clientes (fondo azul claro) */
    private JPanel buildClientLog() {
        clientLogArea = new JTextArea(7, 0);
        clientLogArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        clientLogArea.setBackground(new Color(240, 238, 252));
        clientLogArea.setForeground(C_TEXT);
        clientLogArea.setEditable(false);
        clientLogArea.setLineWrap(true);
        clientLogArea.setWrapStyleWord(true);
        clientLogArea.setBorder(new EmptyBorder(6, 10, 6, 10));

        JScrollPane scroll = new JScrollPane(clientLogArea);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER));

        JLabel lbl = new JLabel("Registro de eventos — Clientes");
        lbl.setFont(font(12, true));
        lbl.setForeground(C_TEXT);

        JButton clearBtn = smallBtn("Limpiar", C_GRAY);
        clearBtn.addActionListener(e -> {
            clientLogArea.setText("");
            for (TCPClientBalanced c : clients) c.disconnect();
            clients.clear();
            cardMap.clear();
            clientServer.clear();
            cardsPanel.removeAll();
            cardsPanel.revalidate();
            cardsPanel.repaint();
            clientSummaryLbl.setText("Sin clientes activos");
        });

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(C_WHITE);
        header.add(lbl,      BorderLayout.WEST);
        header.add(clearBtn, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(C_WHITE);
        p.setBorder(new EmptyBorder(8, 0, 0, 0));
        p.add(header, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    // ════════════════════════════════════════════════════════
    //  LÓGICA — Servidores
    // ════════════════════════════════════════════════════════
    private void startAllServers() {
        int n        = (Integer) numServersSpinner.getValue();
        int basePort = (Integer) basePortSpinner.getValue();
        boolean auto = policyBox.getSelectedIndex() == 1;
        int delay    = (Integer) delaySpinner.getValue();
        int maxR     = (Integer) maxRestartsSpinner.getValue();

        RestartPolicy.Type type = auto
                ? RestartPolicy.Type.AUTO_RESTART : RestartPolicy.Type.NO_RESTART;

        balancer = new Loadbalancer(this::logServer);
        serverTableModel.setRowCount(0);

        for (int i = 0; i < n; i++) {
            String id   = "S" + String.format("%02d", i + 1);
            int    port = basePort + i;
            balancer.addServer(id, port, new RestartPolicy(type, delay, maxR));
            serverTableModel.addRow(new Object[]{ id, port, "Iniciando...", "—", id });
        }

        balancer.setOnStatusChange((entry, status) ->
                SwingUtilities.invokeLater(() -> updateServerRow(entry.id, status)));

        if (statusTimer != null) statusTimer.stop();
        statusTimer = new Timer(800, e -> SwingUtilities.invokeLater(this::refreshTableClients));
        statusTimer.start();

        balancer.startAll();

        logServer("▶ Iniciando " + n + " servidor(es) desde puerto " + basePort
                + "  |  " + (auto ? "Reinicio automático cada " + delay + "s" : "Sin reinicio"));

        startAllBtn.setEnabled(false);
        stopAllBtn .setEnabled(true);
        crashAllBtn.setEnabled(true);
        numServersSpinner.setEnabled(false);
        basePortSpinner  .setEnabled(false);
        policyBox        .setEnabled(false);
        updateBalancerStatus();
    }

    private void stopAllServers() {
        if (balancer != null) balancer.stopAll();
        if (statusTimer != null) { statusTimer.stop(); statusTimer = null; }
        startAllBtn.setEnabled(true);
        stopAllBtn .setEnabled(false);
        crashAllBtn.setEnabled(false);
        numServersSpinner.setEnabled(true);
        basePortSpinner  .setEnabled(true);
        policyBox        .setEnabled(true);
        balancerStatusLbl.setText("Sin servidores activos");
    }

    private void crashAllServers() {
        if (balancer != null) {
            boolean auto = policyBox.getSelectedIndex() == 1;
            logServer("⚡ Fallo simulado en TODOS → "
                    + (auto ? "reiniciarán automáticamente." : "política SIN REINICIO."));
            balancer.crashAll();
        }
    }

    private void updateServerRow(String id, String status) {
        for (int r = 0; r < serverTableModel.getRowCount(); r++) {
            if (id.equals(serverTableModel.getValueAt(r, 0))) {
                serverTableModel.setValueAt(status, r, 2);
                break;
            }
        }
        updateBalancerStatus();
    }

    /**
     * Refresca la columna "Clientes" de cada servidor mostrando
     * los IDs de los clientes actualmente conectados a él.
     */
    private void refreshTableClients() {
        if (balancer == null) return;
        for (Loadbalancer.ServerEntry e : balancer.getEntries()) {
            // Buscar clientes cuyo connectedServer empieza con el ID del servidor
            List<String> connected = new ArrayList<>();
            for (TCPClientBalanced c : clients) {
                String cs = c.getConnectedServer();
                if (cs != null && cs.startsWith(e.id + ":")) {
                    connected.add(c.getId());
                }
            }
            String label = connected.isEmpty() ? "—" : String.join(", ", connected);
            for (int r = 0; r < serverTableModel.getRowCount(); r++) {
                if (e.id.equals(serverTableModel.getValueAt(r, 0))) {
                    serverTableModel.setValueAt(label, r, 3);
                    break;
                }
            }
        }
    }

    private void updateBalancerStatus() {
        if (balancer == null) { balancerStatusLbl.setText("Sin servidores activos"); return; }
        int total     = balancer.getEntries().size();
        int available = balancer.availableCount();
        balancerStatusLbl.setText(total + " servidores  ·  "
                + available + " disponibles  ·  " + (total - available) + " caídos");
    }

    // ════════════════════════════════════════════════════════
    //  LÓGICA — Clientes
    // ════════════════════════════════════════════════════════
    private void launchClients() {
        if (balancer == null || balancer.availableCount() == 0) {
            logClient("⚠ No hay servidores disponibles. Inicia los servidores primero.");
            return;
        }
        int n        = (Integer) numClientsSpinner.getValue();
        int retries  = (Integer) retriesSpinner.getValue();
        int interval = (Integer) intervalSpinner.getValue();
        int timeout  = (Integer) timeoutSpinner.getValue();

        logClient("Lanzando " + n + " cliente(s) con balanceo Round Robin"
                + "  [reintentos=" + retries + ", intervalo=" + interval
                + "s, timeout=" + timeout + "s]");

        for (int i = 0; i < n; i++) {
            clientCounter++;
            String id = "C" + String.format("%02d", clientCounter);
            ReconnectionPolicy pol = new ReconnectionPolicy(retries, interval, timeout);

            boolean autoPolicy = policyBox.getSelectedIndex() == 1;
            TCPClientBalanced c = new TCPClientBalanced(
                    id, "localhost", pol,
                    this::logClient,
                    () -> balancer.nextAvailable(),
                    autoPolicy   // allowRedirect: solo redirige si hay reinicio automático
            );

            JPanel card = buildCard(id);
            cardMap.put(id, card);

            c.setOnStateChange(st -> SwingUtilities.invokeLater(() -> {
                updateCard(card, st, clientServer.getOrDefault(id, ""));
                updateClientSummary();
            }));

            c.setOnServerChange((cid, srvLabel) -> {
                clientServer.put(cid, srvLabel);
                SwingUtilities.invokeLater(() -> {
                    JPanel cc = cardMap.get(cid);
                    if (cc != null) updateCard(cc, c.getState(), srvLabel);
                });
            });

            clients.add(c);
            cardsPanel.add(card);
            cardsPanel.revalidate();
            c.connect();
        }
        updateClientSummary();
    }

    private void stopAllClients() {
        logClient("■ Deteniendo todos los clientes...");
        for (TCPClientBalanced c : clients) c.disconnect();
        clients.clear();
        cardMap.clear();
        clientServer.clear();
        cardsPanel.removeAll();
        cardsPanel.revalidate();
        cardsPanel.repaint();
        clientSummaryLbl.setText("Sin clientes activos");
    }

    // ── Tarjeta de cliente ────────────────────────────────────
    /**
     * Tarjeta con 3 líneas:
     *  - ID grande (C01)
     *  - Estado (Conectado / Reintentando / …)
     *  - Servidor asignado (→ S02:8081) o vacío
     */
    private JPanel buildCard(String id) {
        JPanel card = new JPanel(new BorderLayout(2, 2));
        card.setBackground(C_WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER, 2),
                new EmptyBorder(6, 10, 6, 10)));

        JLabel idLbl = new JLabel(id, SwingConstants.CENTER);
        idLbl.setFont(font(15, true));
        idLbl.setForeground(C_PURPLE);

        JLabel stLbl = new JLabel("Iniciando", SwingConstants.CENTER);
        stLbl.setName("state");
        stLbl.setFont(font(10, false));
        stLbl.setForeground(C_ORANGE);

        JLabel srvLbl = new JLabel(" ", SwingConstants.CENTER);
        srvLbl.setName("server");
        srvLbl.setFont(font(9, false));
        srvLbl.setForeground(C_GRAY);

        JPanel bottom = new JPanel(new GridLayout(2, 1, 0, 1));
        bottom.setOpaque(false);
        bottom.add(stLbl);
        bottom.add(srvLbl);

        card.add(idLbl,   BorderLayout.CENTER);
        card.add(bottom,  BorderLayout.SOUTH);
        return card;
    }

    private void updateCard(JPanel card, TCPClientBalanced.State st, String serverLabel) {
        String text; Color textColor, border;
        switch (st) {
            case CONNECTED:  text = "Conectado";    textColor = C_GREEN;  border = C_GREEN;  break;
            case CONNECTING: text = "Conectando";   textColor = C_BLUE;   border = C_BLUE;   break;
            case RETRYING:   text = "Reintentando"; textColor = C_ORANGE; border = C_ORANGE; break;
            case FAILED:     text = "Fallido";      textColor = C_RED;    border = C_RED;    break;
            default:         text = "Idle";         textColor = C_GRAY;   border = C_BORDER; break;
        }
        findLabel(card, "state",  text, textColor);
        findLabel(card, "server",
                (serverLabel != null && !serverLabel.isEmpty()) ? "→ " + serverLabel : " ",
                C_GRAY);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 2),
                new EmptyBorder(6, 10, 6, 10)));
    }

    private void updateClientSummary() {
        long connected = clients.stream()
                .filter(c -> c.getState() == TCPClientBalanced.State.CONNECTED).count();
        long retrying  = clients.stream()
                .filter(c -> c.getState() == TCPClientBalanced.State.RETRYING).count();
        long failed    = clients.stream()
                .filter(c -> c.getState() == TCPClientBalanced.State.FAILED).count();
        long total     = clients.size();
        clientSummaryLbl.setText(total == 0 ? "Sin clientes activos"
                : total + " clientes  ·  " + connected + " conectados  ·  "
                + retrying + " reintentando  ·  " + failed + " fallidos");
    }

    // ════════════════════════════════════════════════════════
    //  RENDERER / EDITOR — Botones en tabla de servidores
    // ════════════════════════════════════════════════════════
    class ActionCellRenderer implements TableCellRenderer {
        private final JPanel  panel    = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 3));
        private final JButton cBtn     = smallBtn("⚡ Caída",   C_ORANGE);
        private final JButton sBtn     = smallBtn("■ Detener", C_RED);
        ActionCellRenderer() { panel.setBackground(C_WHITE); panel.add(cBtn); panel.add(sBtn); }
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean s, boolean f, int r, int c) { return panel; }
    }

    class ActionCellEditor extends DefaultCellEditor {
        private final JPanel  panel   = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 3));
        private final JButton cBtn    = smallBtn("⚡ Caída",   C_ORANGE);
        private final JButton sBtn    = smallBtn("■ Detener", C_RED);
        private String currentId = "";

        ActionCellEditor(JCheckBox cb) {
            super(cb);
            panel.setBackground(C_WHITE);
            cBtn.addActionListener(e -> {
                fireEditingStopped();
                if (balancer != null) {
                    boolean auto = policyBox.getSelectedIndex() == 1;
                    logServer("⚡ Fallo simulado en [" + currentId + "] → "
                            + (auto ? "reiniciará automáticamente." : "política SIN REINICIO."));
                    balancer.crashServer(currentId);
                }
            });
            sBtn.addActionListener(e -> {
                fireEditingStopped();
                if (balancer != null) balancer.stopServer(currentId);
            });
            panel.add(cBtn); panel.add(sBtn);
        }

        @Override public Component getTableCellEditorComponent(
                JTable t, Object v, boolean s, int r, int c) {
            currentId = (String) v; return panel;
        }
        @Override public Object  getCellEditorValue() { return currentId; }
        @Override public boolean stopCellEditing()    { return super.stopCellEditing(); }
    }

    // ── Shutdown Hook ─────────────────────────────────────────
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (balancer != null) balancer.stopAll();
            for (TCPClientBalanced c : clients) c.disconnect();
        }, "ShutdownHook-Balancer"));
    }

    // ── Logs separados ────────────────────────────────────────
    private void logServer(String msg) {
        SwingUtilities.invokeLater(() -> {
            String t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            serverLogArea.append("[" + t + "]  " + msg + "\n");
            serverLogArea.setCaretPosition(serverLogArea.getDocument().getLength());
        });
    }

    private void logClient(String msg) {
        SwingUtilities.invokeLater(() -> {
            String t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            clientLogArea.append("[" + t + "]  " + msg + "\n");
            clientLogArea.setCaretPosition(clientLogArea.getDocument().getLength());
        });
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

    private void addRowFull(JPanel p, GridBagConstraints g, int row,
                            String label, JComponent field) {
        g.gridx = 0; g.gridy = row; g.weightx = 0; g.gridwidth = 1; p.add(lbl(label), g);
        g.gridx = 1; g.weightx = 1; g.gridwidth = 3; p.add(field, g);
        g.gridwidth = 1;
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
        s.setPreferredSize(new Dimension(80, 28));
        return s;
    }

    private JComboBox<String> combo(String... items) {
        JComboBox<String> cb = new JComboBox<>(items);
        cb.setFont(font(12, false));
        cb.setBackground(C_WHITE);
        return cb;
    }

    private JButton actionBtn(String text, Color color, int width) {
        JButton b = new JButton(text);
        b.setFont(font(13, true));
        b.setForeground(Color.WHITE);
        b.setBackground(color);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(width, 36));
        b.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e)
            { if (b.isEnabled()) b.setBackground(color.darker()); }
            public void mouseExited(java.awt.event.MouseEvent e)
            { if (b.isEnabled()) b.setBackground(color); }
        });
        return b;
    }

    private JButton smallBtn(String text, Color color) {
        JButton b = new JButton(text);
        b.setFont(font(10, true));
        b.setForeground(Color.WHITE);
        b.setBackground(color);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(78, 24));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
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

    private Font font(int size, boolean bold) {
        return new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size);
    }

    // ── Main ─────────────────────────────────────────────────
    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(BalancerApp::new);
    }
}