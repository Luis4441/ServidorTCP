package ui;

import balancer.LoadBalancer;
import balancer.LoadBalancer.ServerEntry;
import balancer.LoadBalancer.ServerStatus;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Interfaz del Balanceador de Carga TCP.
 *
 * Muestra en tiempo real:
 *   - Tarjetas por servidor con puerto, rol (PRIMARIO/STANDBY/ACTIVE), estado y clientes.
 *   - Flechas de failover activo en el log.
 *   - Totales globales en la cabecera.
 */
public class BalancerApp extends JFrame {

    static final Color C_WHITE  = Color.WHITE;
    static final Color C_BG     = new Color(245, 247, 250);
    static final Color C_BORDER = new Color(200, 205, 215);
    static final Color C_TEAL   = new Color(20, 150, 160);
    static final Color C_GREEN  = new Color(34, 160, 90);
    static final Color C_RED    = new Color(210, 50, 50);
    static final Color C_BLUE   = new Color(52, 120, 200);
    static final Color C_ORANGE = new Color(220, 120, 20);
    static final Color C_GRAY   = new Color(120, 125, 135);
    static final Color C_TEXT   = new Color(25, 30, 45);
    static final Color C_PURPLE = new Color(120, 70, 190);

    private LoadBalancer          balancer;
    private javax.swing.Timer     refreshTimer;

    private JLabel    summaryLbl;
    private JPanel    serverGrid;
    private JTextArea logArea;
    private JButton   startBtn;
    private JButton   stopBtn;
    private JLabel    statusPill;

    public BalancerApp() {
        super("Balanceador TCP");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(720, 720);
        setMinimumSize(new Dimension(580, 580));
        setLocationRelativeTo(null);
        setResizable(true);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(C_BG);
        root.add(buildHeader(),  BorderLayout.NORTH);
        root.add(buildCenter(),  BorderLayout.CENTER);
        root.add(buildButtons(), BorderLayout.SOUTH);
        setContentPane(root);
        setVisible(true);
    }

    // ── Cabecera ─────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(C_TEAL);
        p.setBorder(new EmptyBorder(16, 20, 16, 20));

        JLabel title = new JLabel("Balanceador TCP");
        title.setFont(font(20, true));
        title.setForeground(Color.WHITE);

        statusPill = pill("OFFLINE", C_RED);
        summaryLbl = new JLabel("Sin servidores registrados");
        summaryLbl.setFont(font(12, false));
        summaryLbl.setForeground(new Color(180, 235, 240));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        right.setOpaque(false);
        right.add(summaryLbl);
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

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                buildServersPanel(), buildLog());
        split.setDividerLocation(300);
        split.setDividerSize(4);
        split.setBorder(null);
        split.setBackground(C_WHITE);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildServersPanel() {
        serverGrid = new JPanel();
        serverGrid.setLayout(new WrapLayout(FlowLayout.LEFT, 10, 10));
        serverGrid.setBackground(new Color(240, 252, 253));

        JScrollPane scroll = new JScrollPane(serverGrid);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER));
        scroll.getVerticalScrollBar().setUnitIncrement(12);

        JLabel lbl = new JLabel("Servidores registrados");
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
        logArea.setBackground(new Color(235, 252, 253));
        logArea.setForeground(C_TEXT);
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createLineBorder(C_BORDER));

        JLabel lbl = new JLabel("Registro de eventos del balanceador");
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
        startBtn = actionBtn("▶  Iniciar balanceador", C_TEAL);
        stopBtn  = actionBtn("■  Detener",              C_RED);
        JButton clearBtn = actionBtn("⌫  Limpiar log",  C_GRAY);

        stopBtn.setEnabled(false);
        startBtn.addActionListener(e -> startBalancer());
        stopBtn .addActionListener(e -> stopBalancer());
        clearBtn.addActionListener(e -> logArea.setText(""));

        JLabel hint = new JLabel(
                "<html><center><i>" +
                        "Puerto de registro: <b>19999</b>. " +
                        "Abrir <b>ServerApp</b> como Primario o Standby para registrar servidores." +
                        "</i></center></html>");
        hint.setFont(font(11, false));
        hint.setForeground(C_GRAY);
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        hint.setBorder(new EmptyBorder(0, 0, 8, 0));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        row.setBackground(C_WHITE);
        row.add(startBtn); row.add(stopBtn); row.add(clearBtn);

        JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setBackground(C_WHITE);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        p.add(row,  BorderLayout.CENTER);
        p.add(hint, BorderLayout.SOUTH);
        return p;
    }

    // ── Lógica ───────────────────────────────────────────────
    private void startBalancer() {
        balancer = new LoadBalancer(this::log);
        balancer.setOnUpdate(() -> SwingUtilities.invokeLater(this::refreshGrid));
        balancer.start();

        setPill(statusPill, "ONLINE", C_GREEN);
        startBtn.setEnabled(false);
        stopBtn .setEnabled(true);

        refreshTimer = new javax.swing.Timer(800,
                e -> SwingUtilities.invokeLater(this::refreshGrid));
        refreshTimer.start();
    }

    private void stopBalancer() {
        if (balancer    != null) balancer.stop();
        if (refreshTimer != null) { refreshTimer.stop(); refreshTimer = null; }
        setPill(statusPill, "OFFLINE", C_RED);
        startBtn.setEnabled(true);
        stopBtn .setEnabled(false);
        summaryLbl.setText("Sin servidores registrados");
        serverGrid.removeAll();
        serverGrid.revalidate();
        serverGrid.repaint();
        log("■ Balanceador detenido.");
    }

    // ── Refresco de grilla ────────────────────────────────────
    private void refreshGrid() {
        if (balancer == null) return;

        List<ServerEntry> entries = new ArrayList<>(balancer.getServers());
        entries.sort(Comparator.comparingInt(e -> e.port));
        Map<Integer, Integer> redirects = balancer.getRedirects();

        serverGrid.removeAll();
        for (ServerEntry e : entries) {
            serverGrid.add(buildServerCard(e, redirects));
        }
        serverGrid.revalidate();
        serverGrid.repaint();

        int online  = balancer.onlineCount();
        int total   = entries.size();
        int clients = balancer.totalClients();
        summaryLbl.setText(total + " servidores  ·  " + online + " activos  ·  "
                + clients + " clientes totales");
    }

    // ── Tarjeta de servidor ───────────────────────────────────
    private JPanel buildServerCard(ServerEntry e, Map<Integer, Integer> redirects) {
        Color borderColor;
        Color roleColor;
        String roleText;
        String stateText;
        Color  stateColor;

        switch (e.status) {
            case ONLINE:
                borderColor = C_TEAL;   roleText = "PRIMARIO"; roleColor = C_BLUE;
                stateText = "ONLINE";   stateColor = C_GREEN;
                break;
            case ACTIVE:
                borderColor = C_ORANGE; roleText = "ACTIVO";   roleColor = C_ORANGE;
                stateText = "EN SERVICIO"; stateColor = C_ORANGE;
                break;
            case STANDBY:
                borderColor = C_GRAY;   roleText = "STANDBY";  roleColor = C_GRAY;
                stateText = "EN ESPERA"; stateColor = C_GRAY;
                break;
            default: // OFFLINE
                borderColor = C_RED;    roleText = "OFFLINE";  roleColor = C_RED;
                stateText = "OFFLINE";  stateColor = C_RED;
                break;
        }

        JPanel card = new JPanel(new BorderLayout(2, 4));
        card.setBackground(C_WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, 2),
                new EmptyBorder(8, 14, 8, 14)
        ));
        card.setPreferredSize(new Dimension(150, 110));

        // Puerto
        JLabel portLbl = new JLabel(":" + e.port, SwingConstants.CENTER);
        portLbl.setFont(font(18, true));
        portLbl.setForeground(borderColor);

        // Rol
        JLabel roleLbl = new JLabel(roleText, SwingConstants.CENTER);
        roleLbl.setFont(font(9, true));
        roleLbl.setForeground(roleColor);

        // Estado
        JLabel stLbl = new JLabel(stateText, SwingConstants.CENTER);
        stLbl.setFont(font(10, false));
        stLbl.setForeground(stateColor);

        // Clientes
        String cliStr;
        if (e.status == ServerStatus.STANDBY) {
            cliStr = "disponible";  // standby libre, no cubre a nadie
        } else if (e.status == ServerStatus.ACTIVE) {
            // Muestra a qué primario está cubriendo
            cliStr = e.coveringPort > 0
                    ? "← :" + e.coveringPort + "  |  " + e.clientCount + " cliente" + (e.clientCount == 1 ? "" : "s")
                    : e.clientCount + " cliente" + (e.clientCount == 1 ? "" : "s");
        } else if (e.status == ServerStatus.OFFLINE) {
            cliStr = "—";
        } else {
            cliStr = e.clientCount + " cliente" + (e.clientCount == 1 ? "" : "s");
        }

        JLabel cliLbl = new JLabel(cliStr, SwingConstants.CENTER);
        cliLbl.setFont(font(10, false));
        cliLbl.setForeground(C_TEXT);

        JPanel south = new JPanel(new GridLayout(3, 1, 0, 1));
        south.setOpaque(false);
        south.add(roleLbl);
        south.add(stLbl);
        south.add(cliLbl);

        card.add(portLbl, BorderLayout.CENTER);
        card.add(south,   BorderLayout.SOUTH);
        return card;
    }

    // ── Helpers UI ───────────────────────────────────────────
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

    private JButton actionBtn(String text, Color color) {
        JButton b = new JButton(text);
        b.setFont(font(13, true));
        b.setForeground(Color.WHITE);
        b.setBackground(color);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(200, 38));
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

    // ── WrapLayout ────────────────────────────────────────────
    static class WrapLayout extends FlowLayout {
        public WrapLayout(int align, int hgap, int vgap) { super(align, hgap, vgap); }

        @Override public Dimension preferredLayoutSize(Container t) { return layoutSize(t, true);  }
        @Override public Dimension minimumLayoutSize (Container t) {
            Dimension d = layoutSize(t, false);
            d.width -= (getHgap() + 1);
            return d;
        }

        private Dimension layoutSize(Container target, boolean preferred) {
            synchronized (target.getTreeLock()) {
                int tw = target.getSize().width;
                if (tw == 0) tw = Integer.MAX_VALUE;
                Insets ins = target.getInsets();
                int maxW = tw - (ins.left + ins.right + getHgap() * 2);
                Dimension dim = new Dimension(0, 0);
                int rw = 0, rh = 0;
                for (int i = 0; i < target.getComponentCount(); i++) {
                    Component m = target.getComponent(i);
                    if (!m.isVisible()) continue;
                    Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                    if (rw + d.width > maxW) { addRow(dim, rw, rh); rw = 0; rh = 0; }
                    if (rw != 0) rw += getHgap();
                    rw += d.width; rh = Math.max(rh, d.height);
                }
                addRow(dim, rw, rh);
                dim.width  += ins.left + ins.right + getHgap() * 2;
                dim.height += ins.top  + ins.bottom + getVgap() * 2;
                return dim;
            }
        }

        private void addRow(Dimension dim, int rw, int rh) {
            dim.width = Math.max(dim.width, rw);
            if (dim.height > 0) dim.height += getVgap();
            dim.height += rh;
        }
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