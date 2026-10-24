import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class BlackjackClient {

    // ============================== NETWORKING ==============================

    private volatile Socket socket;   // set by the reader thread, read by the Swing thread
    private volatile Writer out;
    private JFrame frame;
    private CardLayout screens;
    private JPanel root;
    private ConnectPanel connectPanel;
    private TablePanel table;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new BlackjackClient().showWindow());
    }

    // Connects and then reads lines from the server, all in a background thread,
    // so the window never freezes. Swing may only be touched from its own thread,
    // so every received line is handed over with SwingUtilities.invokeLater.
    void connect(String ip, int port, String name) {
        Thread reader = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 5000);
                socket = s;
                out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                // BufferedReader keeps its own buffer and splits the TCP stream on "\n"
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                SwingUtilities.invokeLater(() -> showTable(name));
                send("NAME " + name);
                String line;
                while ((line = in.readLine()) != null) {
                    String received = line;
                    SwingUtilities.invokeLater(() -> table.handle(received));
                }
                SwingUtilities.invokeLater(() -> table.connectionClosed());
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    if (socket == null) connectPanel.showError("Cannot connect: " + e.getMessage());
                    else table.connectionClosed();
                });
            }
        });
        reader.setDaemon(true);
        reader.start();
    }

    // Every message is one line ending in "\n"
    synchronized void send(String line) {
        if (out == null) return;
        try {
            out.write(line + "\n");
            out.flush();
        } catch (IOException e) {
            // the reader thread will notice the closed connection
        }
    }

    // ============================== WINDOW ==============================

    void showWindow() {
        frame = new JFrame("Blackjack");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        screens = new CardLayout();
        root = new JPanel(screens);
        connectPanel = new ConnectPanel(this);
        table = new TablePanel(this::send);
        root.add(connectPanel, "connect");
        root.add(table, "table");
        frame.setContentPane(root);

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = (int) Math.min(TablePanel.VW, screen.width * 0.9);
        int h = w * TablePanel.VH / TablePanel.VW;
        if (h > screen.height * 0.85) {
            h = (int) (screen.height * 0.85);
            w = h * TablePanel.VW / TablePanel.VH;
        }
        frame.setSize(w, h + 30);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    void showTable(String name) {
        table.me = name;
        screens.show(root, "table");
    }

    // ============================== CONNECT SCREEN ==============================

    static class ConnectPanel extends JPanel {
        final JTextField ip = new JTextField("127.0.0.1", 14);
        final JTextField port = new JTextField("5050", 14);
        final JTextField name = new JTextField(14);
        final JButton connect = new JButton("JOIN TABLE");
        final JLabel error = new JLabel(" ");

        ConnectPanel(BlackjackClient app) {
            setLayout(new GridBagLayout());
            setBackground(new Color(14, 32, 48));
            JPanel box = new JPanel(new GridBagLayout());
            box.setBackground(new Color(22, 110, 62));
            box.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(92, 58, 30), 8),
                    BorderFactory.createEmptyBorder(24, 36, 24, 36)));

            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(6, 6, 6, 6);
            c.gridx = 0;
            c.gridy = 0;
            c.gridwidth = 2;
            JLabel title = new JLabel("BLACKJACK");
            title.setFont(new Font("SansSerif", Font.BOLD, 40));
            title.setForeground(Color.WHITE);
            box.add(title, c);

            c.gridy++;
            JLabel sub = new JLabel("first to 20,000 wins");
            sub.setFont(new Font("SansSerif", Font.BOLD, 14));
            sub.setForeground(new Color(255, 255, 255, 170));
            box.add(sub, c);

            c.gridwidth = 1;
            String[] labels = {"Server IP", "Port", "Your name"};
            JTextField[] fields = {ip, port, name};
            for (int i = 0; i < 3; i++) {
                c.gridy++;
                c.gridx = 0;
                c.anchor = GridBagConstraints.EAST;
                JLabel l = new JLabel(labels[i]);
                l.setFont(new Font("SansSerif", Font.BOLD, 15));
                l.setForeground(Color.WHITE);
                box.add(l, c);
                c.gridx = 1;
                c.anchor = GridBagConstraints.WEST;
                fields[i].setFont(new Font("SansSerif", Font.PLAIN, 16));
                fields[i].addActionListener(e -> connect.doClick());
                box.add(fields[i], c);
            }

            c.gridy++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.CENTER;
            connect.setFont(new Font("SansSerif", Font.BOLD, 18));
            connect.setBackground(new Color(240, 190, 50));
            connect.setFocusPainted(false);
            box.add(connect, c);

            c.gridy++;
            error.setForeground(new Color(255, 180, 180));
            error.setFont(new Font("SansSerif", Font.BOLD, 13));
            box.add(error, c);

            add(box);

            connect.addActionListener(e -> {
                String n = name.getText().trim().replace(" ", "_");
                if (n.isEmpty()) {
                    showError("Enter a name");
                    return;
                }
                int p;
                try {
                    p = Integer.parseInt(port.getText().trim());
                } catch (NumberFormatException ex) {
                    showError("Port must be a number");
                    return;
                }
                connect.setEnabled(false);
                error.setText("Connecting...");
                app.connect(ip.getText().trim(), p, n);
            });
        }

        void showError(String text) {
            error.setText(text);
            connect.setEnabled(true);
        }
    }

    // ============================== TABLE SCREEN ==============================

    static class Seat {
        String name;
        int bankroll, bet;
        List<String> cards = new ArrayList<>();
        String value;
        boolean spectator;
    }

    static class Btn {
        Rectangle r;
        boolean enabled;
        Runnable action;

        Btn(Rectangle r, boolean enabled, Runnable action) {
            this.r = r;
            this.enabled = enabled;
            this.action = action;
        }
    }

    static class TablePanel extends JPanel {
        static final int VW = 1180, VH = 760;    // everything is drawn in this virtual size, then scaled
        static final int CX = VW / 2;
        static final int[] CHIP_VALUES = {100, 500, 1000, 5000};
        static final Color[] CHIP_COLORS = {new Color(30, 90, 200), new Color(120, 40, 160), new Color(230, 120, 20), new Color(30, 30, 30)};

        final Consumer<String> sender;
        String me = "";
        int bankroll, lastBet, pendingBet, minBet = 100;
        List<String> myCards = new ArrayList<>();
        String myValue = "";
        List<String> dealerCards = new ArrayList<>();
        String dealerValue = "";
        String phase = "WAITING", current = "-";
        long turnEnd;
        double turnTotal = 20;
        List<Seat> seats = new ArrayList<>();
        LinkedList<String> log = new LinkedList<>();
        String banner;
        Color bannerColor;
        long bannerEnd;
        String overlay;
        boolean spectating;
        boolean actionSent;   // blocks double clicks until the server answers with the next TABLE

        // card animations, key = hand id + "#" + card index
        static final int DEAL_MS = 380, DEAL_GAP = 260, FLIP_MS = 320;   // flight time, time between cards, flip time
        static final int DECK_X = 800, DECK_Y = 74;
        final java.util.Map<String, Long> dealStart = new java.util.HashMap<>();
        final java.util.Map<String, Long> flipStart = new java.util.HashMap<>();
        final java.util.Map<String, List<String>> shownSeatCards = new java.util.HashMap<>();
        final LinkedList<String[]> incoming = new LinkedList<>();   // {line, arrival time in ms}
        long nextFree, busyUntil;

        final List<Btn> buttons = new ArrayList<>();
        Point mouse = new Point(-1, -1);
        double scale = 1, offX, offY;

        TablePanel(Consumer<String> sender) {
            this.sender = sender;
            setBackground(new Color(10, 22, 34));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {   // mouseClicked drops clicks if the mouse moves slightly
                    handleClick(toVirtual(e.getPoint()));
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    mouse = toVirtual(e.getPoint());
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            new javax.swing.Timer(30, e -> {   // smooth countdown rings and card animations
                pump();
                repaint();
            }).start();
        }

        Point toVirtual(Point p) {
            return new Point((int) ((p.x - offX) / scale), (int) ((p.y - offY) / scale));
        }

        void handleClick(Point p) {
            for (int i = buttons.size() - 1; i >= 0; i--) {
                Btn b = buttons.get(i);
                if (b.enabled && b.r.contains(p)) {
                    b.action.run();
                    repaint();
                    return;
                }
            }
        }

        // ---------- protocol: one server line in, state updated ----------

        void handle(String line) {
            incoming.addLast(new String[]{line, String.valueOf(System.currentTimeMillis())});
            pump();
        }

        // Lines wait in the queue while cards are still flying, so the game unfolds in order
        // (the result is shown only after the dealer's last card lands).
        // SEAT lines belong to the TABLE snapshot right before them, so they never wait.
        void pump() {
            while (!incoming.isEmpty()) {
                String line = incoming.getFirst()[0];
                if (!line.startsWith("SEAT") && System.currentTimeMillis() < busyUntil) return;
                String[] item = incoming.removeFirst();
                process(item[0], Long.parseLong(item[1]));
            }
        }

        void process(String line, long arrived) {
            String[] p = line.split(" ");
            String rest = line.contains(" ") ? line.substring(line.indexOf(' ') + 1) : "";
            switch (p[0]) {
                case "WELCOME" -> {
                    bankroll = Integer.parseInt(p[1]);
                    if (p.length > 2) me = p[2];   // the server may change the name (duplicates, symbols)
                }
                case "YOUR_BET" -> {
                    minBet = Integer.parseInt(p[1]);
                    pendingBet = 0;
                }
                case "BANKROLL" -> bankroll = Integer.parseInt(p[1]);
                case "HAND" -> {
                    List<String> c = cards(p[1]);
                    animate("me", myCards, c);
                    myCards = c;
                    myValue = p[2];
                }
                case "DEALER" -> {
                    List<String> c = cards(p[1]);
                    animate("dealer", dealerCards, c);
                    dealerCards = c;
                    dealerValue = p[2];
                }
                case "RESULT" -> showResult(p[1], Integer.parseInt(p[2]));
                case "TURN", "DONE" -> { }   // the TABLE line that follows tells us whose turn it is
                case "SPECTATING" -> spectating = true;
                case "GAME_OVER" -> {
                    banner = null;
                    if (p[1].equals("DEALER")) overlay = "THE DEALER WINS";
                    else overlay = (p[1].contains(",") ? "WINNERS: " : "WINNER: ") + p[1].replace(",", ", ");
                }
                case "MSG" -> addLog(rest);
                case "ERROR" -> {
                    addLog("! " + rest);
                    setBanner(rest, new Color(190, 40, 40), 1800);
                }
                case "TABLE" -> {
                    if (p[1].equals("BETTING") && !phase.equals("BETTING")) {   // a new round started
                        myCards = new ArrayList<>();
                        myValue = "";
                        dealerCards = new ArrayList<>();
                        dealerValue = "";
                        dealStart.clear();
                        flipStart.clear();
                        shownSeatCards.clear();
                    }
                    phase = p[1];
                    current = p[2];
                    actionSent = false;
                    turnEnd = arrived + (long) (Double.parseDouble(p[3]) * 1000);
                    turnTotal = Math.max(1, Double.parseDouble(p[4]));
                    seats.clear();
                }
                case "SEAT" -> {
                    Seat s = new Seat();
                    s.name = p[1];
                    s.bankroll = Integer.parseInt(p[2]);
                    s.bet = Integer.parseInt(p[3]);
                    s.cards = p[4].equals("-") ? new ArrayList<>() : cards(p[4]);
                    s.value = p[5];
                    s.spectator = p[6].equals("SPECTATOR");
                    seats.add(s);
                    if (s.name.equals(me)) {
                        bankroll = s.bankroll;
                        spectating = s.spectator;
                    } else {
                        animate("seat:" + s.name, shownSeatCards.getOrDefault(s.name, new ArrayList<>()), s.cards);
                        shownSeatCards.put(s.name, s.cards);
                    }
                }
                default -> addLog(line);
            }
            repaint();
        }

        void connectionClosed() {
            if (overlay == null) overlay = "CONNECTION LOST";
            repaint();
        }

        static List<String> cards(String list) {
            List<String> l = new ArrayList<>();
            for (String c : list.split(",")) l.add(c);
            return l;
        }

        // new cards get a start time, one after another; a card that changed (?? -> real) gets a flip
        void animate(String hand, List<String> before, List<String> after) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < after.size(); i++) {
                String key = hand + "#" + i;
                if (i >= before.size()) {
                    long start = Math.max(now, nextFree);
                    dealStart.put(key, start);
                    nextFree = start + DEAL_GAP;
                    busyUntil = Math.max(busyUntil, start + DEAL_MS);
                } else if (!before.get(i).equals(after.get(i))) {
                    long start = Math.max(now, nextFree);
                    flipStart.put(key, start);
                    nextFree = start + FLIP_MS;
                    busyUntil = Math.max(busyUntil, start + FLIP_MS);
                }
            }
        }

        // true when all cards of this hand have landed (then its value can be shown)
        boolean settled(String hand, int n) {
            long now = System.currentTimeMillis();
            for (int i = 0; i < n; i++) {
                Long st = dealStart.get(hand + "#" + i);
                if (st != null && now < st + DEAL_MS) return false;
                Long fs = flipStart.get(hand + "#" + i);
                if (fs != null && now < fs + FLIP_MS) return false;
            }
            return true;
        }

        void addLog(String text) {
            log.addLast(text);
            while (log.size() > 4) log.removeFirst();
        }

        void showResult(String result, int newBankroll) {
            int net = newBankroll - (bankroll + lastBet);   // bankroll still has the bet taken out
            bankroll = newBankroll;
            switch (result) {
                case "WIN" -> setBanner("YOU WIN  +" + money(net), new Color(30, 150, 70), 3500);
                case "BLACKJACK" -> setBanner("BLACKJACK!  +" + money(net), new Color(210, 150, 20), 3500);
                case "PUSH" -> setBanner("PUSH  " + money(0), new Color(90, 90, 110), 3500);
                default -> setBanner("YOU LOSE  -" + money(lastBet), new Color(170, 35, 45), 3500);
            }
        }

        void setBanner(String text, Color color, int ms) {
            banner = text;
            bannerColor = color;
            bannerEnd = System.currentTimeMillis() + ms;
        }

        static String money(int n) {
            return String.format(Locale.US, "$%,d", n);
        }

        double secondsLeft() {
            return Math.max(0, (turnEnd - System.currentTimeMillis()) / 1000.0);
        }

        Seat mySeat() {
            for (Seat s : seats) if (s.name.equals(me)) return s;
            return null;
        }

        // ---------- drawing ----------

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            scale = Math.min(getWidth() / (double) VW, getHeight() / (double) VH);
            offX = (getWidth() - VW * scale) / 2;
            offY = (getHeight() - VH * scale) / 2;
            paintBackground(g, getWidth(), getHeight());
            g.translate(offX, offY);
            g.scale(scale, scale);

            buttons.clear();
            paintTable(g);
            paintDealer(g);
            paintOthers(g);
            paintMe(g);
            paintControls(g);
            paintBalance(g);
            paintLog(g);
            paintBanner(g);
            paintOverlay(g);
            g.dispose();
        }

        void paintBackground(Graphics2D g, int w, int h) {
            g.setPaint(new GradientPaint(0, 0, new Color(18, 40, 60), 0, h, new Color(8, 18, 28)));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(255, 255, 255, 8));
            for (int y = 0; y < h; y += 48)
                for (int x = (y / 48 % 2) * 48; x < w; x += 96)
                    g.fillRect(x, y, 48, 48);
        }

        void paintTable(Graphics2D g) {
            Shape rim = new RoundRectangle2D.Double(80, 30, 1020, 560, 560, 560);
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(new RoundRectangle2D.Double(86, 42, 1020, 560, 560, 560));
            g.setPaint(new GradientPaint(0, 30, new Color(120, 76, 38), 0, 590, new Color(70, 42, 20)));
            g.fill(rim);
            Shape felt = new RoundRectangle2D.Double(100, 50, 980, 520, 520, 520);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(CX, 280), 560,
                    new float[]{0f, 1f}, new Color[]{new Color(44, 160, 92), new Color(16, 100, 54)}));
            g.fill(felt);
            g.setColor(new Color(255, 215, 120, 90));
            g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Double(124, 74, 932, 472, 472, 472));

            g.setColor(new Color(255, 255, 255, 60));
            g.setFont(new Font("SansSerif", Font.BOLD, 28));
            centerText(g, "BLACKJACK PAYS 3 TO 2", CX, 262);
            g.setFont(new Font("SansSerif", Font.BOLD, 13));
            centerText(g, "DEALER STANDS ON 17   ·   REACH 20,000 TO WIN", CX, 286);

            for (int i = 2; i >= 0; i--) drawCard(g, "??", 800 + i * 3, 74 - i * 3, 60, 86);   // the deck
        }

        void paintDealer(Graphics2D g) {
            g.setColor(new Color(255, 255, 255, 170));
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            centerText(g, "DEALER", CX, 74);
            if (dealerCards.isEmpty()) {
                drawEmptySlot(g, CX - 78, 86, 72, 102);
                drawEmptySlot(g, CX + 6, 86, 72, 102);
                return;
            }
            int end = drawHand(g, "dealer", dealerCards, CX, 86, 72, 102, 48);
            if (!dealerValue.equals("?") && settled("dealer", dealerCards.size())) drawBadge(g, dealerValue, end + 10, 125);
        }

        // draws the cards that were dealt so far, centered on cx; returns the x where the last card ends
        int drawHand(Graphics2D g, String hand, List<String> cards, int cx, int y, int w, int h, int step) {
            long now = System.currentTimeMillis();
            int visible = 0;
            for (int i = 0; i < cards.size(); i++) {
                Long st = dealStart.get(hand + "#" + i);
                if (st == null || st <= now) visible++;
            }
            if (visible == 0) return cx;
            int total = w + (visible - 1) * step;
            int x = cx - total / 2;
            for (int i = 0; i < visible; i++) {
                String key = hand + "#" + i;
                Long st = dealStart.get(key);
                Long fs = flipStart.get(key);
                double t = st == null ? 1 : (now - st) / (double) DEAL_MS;
                if (t < 1) drawFlying(g, cards.get(i), t, x + i * step, y, w, h);
                else if (fs != null && now < fs + FLIP_MS) drawFlipping(g, cards.get(i), (now - fs) / (double) FLIP_MS, x + i * step, y, w, h);
                else drawCard(g, cards.get(i), x + i * step, y, w, h);
            }
            return x + total;
        }

        // a card on its way from the deck to (tx, ty), turning face up halfway
        void drawFlying(Graphics2D g, String code, double t, int tx, int ty, int w, int h) {
            double e = 1 - Math.pow(1 - t, 3);   // ease-out: fast start, soft landing
            double cw = 60 + (w - 60) * e, ch = 86 + (h - 86) * e;
            double x = DECK_X + (tx - DECK_X) * e, y = DECK_Y + (ty - DECK_Y) * e;
            AffineTransform saved = g.getTransform();
            g.rotate((1 - e) * -0.5, x + cw / 2, y + ch / 2);
            g.translate(x + cw / 2, y + ch / 2);
            g.scale(Math.max(0.03, Math.abs(1 - 2 * e)), 1);
            g.translate(-(x + cw / 2), -(y + ch / 2));
            drawCard(g, e < 0.5 ? "??" : code, (int) x, (int) y, (int) cw, (int) ch);
            g.setTransform(saved);
        }

        // a card lying on the table turning over (the dealer's hidden card)
        void drawFlipping(Graphics2D g, String code, double f, int x, int y, int w, int h) {
            f = Math.max(0, f);
            AffineTransform saved = g.getTransform();
            g.translate(x + w / 2.0, y + h / 2.0);
            g.scale(Math.max(0.03, Math.abs(1 - 2 * f)), 1);
            g.translate(-(x + w / 2.0), -(y + h / 2.0));
            drawCard(g, f < 0.5 ? "??" : code, x, y, w, h);
            g.setTransform(saved);
        }

        void paintOthers(Graphics2D g) {
            int[][] pos = {{215, 330}, {965, 330}, {330, 190}, {850, 190}};
            int i = 0;
            for (Seat s : seats) {
                if (s.name.equals(me) || i >= pos.length) continue;
                int x = pos[i][0], y = pos[i][1];
                i++;
                if (!s.cards.isEmpty()) {
                    int end = drawHand(g, "seat:" + s.name, s.cards, x, y - 84, 50, 72, 26);
                    if (settled("seat:" + s.name, s.cards.size())) drawBadge(g, s.value, end + 6, y - 58);
                }
                drawPlate(g, s, x, y, 160, false);
                if (s.bet > 0) drawBetStack(g, x, y + 96, 20, s.bet);
            }
        }

        void paintMe(Graphics2D g) {
            Seat s = mySeat();
            if (!myCards.isEmpty()) {
                int end = drawHand(g, "me", myCards, CX, 330, 86, 122, 56);
                if (settled("me", myCards.size())) drawBadge(g, myValue, end + 10, 378);
            }
            if (s != null && s.bet > 0) drawBetStack(g, 400, 410, 26, s.bet);
            if (s != null) drawPlate(g, s, CX, 498, 210, true);
        }

        void drawPlate(Graphics2D g, Seat s, int cx, int y, int w, boolean isMe) {
            int h = 52;
            int x = cx - w / 2;
            boolean turn = s.name.equals(current) && !phase.equals("RESULTS");
            if (turn) {
                float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 200.0));
                g.setColor(new Color(255, 220, 60, (int) (120 + 100 * pulse)));
                g.setStroke(new BasicStroke(4f));
                g.drawRoundRect(x - 5, y - 5, w + 10, h + 10, 22, 22);
            }
            g.setColor(s.spectator ? new Color(60, 60, 70, 210) : new Color(250, 250, 245));
            g.fillRoundRect(x, y, w, h, 16, 16);
            g.setColor(s.spectator ? new Color(220, 220, 220) : new Color(20, 30, 40));
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            centerText(g, isMe ? s.name + " (you)" : s.name, cx, y + 21);
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            if (s.spectator) {
                g.setColor(new Color(200, 200, 210));
                centerText(g, (s.bankroll >= 20000 ? "WINNER · " : "SPECTATING · ") + money(s.bankroll), cx, y + 41);
            } else {
                g.setColor(new Color(20, 130, 70));
                centerText(g, money(s.bankroll), cx, y + 41);
            }
            if (turn && !isMe) {
                int rx = cx > CX ? x - 30 : x + w + 30;   // ring on the side facing the table center
                drawRing(g, rx, y + h / 2, 20, secondsLeft() / turnTotal, (int) Math.ceil(secondsLeft()) + "");
            }
        }

        void paintControls(Graphics2D g) {
            g.setColor(new Color(0, 0, 0, 150));
            g.fillRoundRect(40, 612, 1100, 132, 28, 28);

            boolean myTurn = current.equals(me);
            double left = secondsLeft();
            if (myTurn && phase.equals("BETTING")) {
                for (int i = 0; i < CHIP_VALUES.length; i++) {
                    int v = CHIP_VALUES[i];
                    int cx = 110 + i * 82, cy = 678;
                    boolean ok = pendingBet + v <= bankroll;
                    Rectangle r = new Rectangle(cx - 34, cy - 34, 68, 68);
                    boolean hover = ok && r.contains(mouse);
                    drawChip(g, cx, cy - (hover ? 4 : 0), 34, ok ? CHIP_COLORS[i] : new Color(90, 90, 90), shortMoney(v));
                    buttons.add(new Btn(r, ok, () -> pendingBet += v));
                }
                button(g, "ALL-IN", 450, 650, 110, 56, new Color(200, 50, 50), bankroll >= minBet, () -> pendingBet = bankroll - bankroll % 10);   // bets must be multiples of 10
                button(g, "CLEAR", 572, 650, 100, 56, new Color(110, 115, 130), pendingBet > 0, () -> pendingBet = 0);
                drawRing(g, 730, 678, 48, left / turnTotal, money(pendingBet));
                button(g, "PLACE BET", 795, 650, 160, 56, new Color(30, 160, 80), pendingBet >= minBet && !actionSent, () -> {
                    lastBet = pendingBet;
                    act("BET " + pendingBet);
                });
                if (bankroll >= 20000)
                    button(g, "CASH OUT", 967, 650, 150, 56, new Color(40, 110, 200), !actionSent, () -> act("SPECTATE"));
                else {
                    g.setColor(new Color(255, 255, 255, 140));
                    g.setFont(new Font("SansSerif", Font.BOLD, 13));
                    centerText(g, "min " + money(minBet), 1040, 672);
                    centerText(g, (int) Math.ceil(left) + "s left", 1040, 692);
                }
            } else if (myTurn && phase.equals("PLAYING")) {
                button(g, "HIT", 356, 646, 170, 64, new Color(30, 160, 80), !actionSent, () -> act("HIT"));
                drawRing(g, CX, 678, 44, left / turnTotal, (int) Math.ceil(left) + "s");
                button(g, "STAND", 654, 646, 170, 64, new Color(200, 50, 50), !actionSent, () -> act("STAND"));
            } else {
                String text;
                Seat s = mySeat();
                if (phase.equals("RESULTS")) text = "Next round in " + (int) Math.ceil(left) + "...";
                else if (phase.equals("BETTING") && !current.equals("-")) text = "Waiting for " + current + " to bet";
                else if (phase.equals("PLAYING") && !current.equals("-")) text = current + " is playing";
                else text = "Waiting for players...";
                if (spectating) text = "You are spectating  ·  " + text;
                else if (phase.equals("PLAYING") && s != null && s.bet == 0) text = "You sit out this round  ·  " + text;
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, 22));
                centerText(g, text, CX, 686);
                if (!current.equals("-") || phase.equals("RESULTS")) {
                    FontMetrics fm = g.getFontMetrics();
                    drawRing(g, CX + fm.stringWidth(text) / 2 + 40, 678, 22, left / turnTotal, null);
                }
            }
        }

        void act(String command) {
            actionSent = true;
            sender.accept(command);
        }

        void paintBalance(Graphics2D g) {
            g.setColor(new Color(0, 0, 0, 160));
            g.fillRoundRect(16, 14, 210, 70, 18, 18);
            g.setColor(new Color(255, 255, 255, 170));
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            g.drawString("CHIP BALANCE", 32, 36);
            g.setColor(new Color(110, 230, 140));
            g.setFont(new Font("SansSerif", Font.BOLD, 28));
            g.drawString(money(bankroll), 32, 70);
        }

        void paintLog(Graphics2D g) {
            if (log.isEmpty()) return;
            g.setColor(new Color(0, 0, 0, 140));
            g.fillRoundRect(870, 14, 296, 18 + log.size() * 18, 16, 16);
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            int y = 34;
            for (String l : log) {
                g.setColor(l.startsWith("!") ? new Color(255, 160, 160) : new Color(230, 230, 230));
                g.drawString(fit(g, l, 276), 882, y);
                y += 18;
            }
        }

        void paintBanner(Graphics2D g) {
            long now = System.currentTimeMillis();
            if (banner == null || now > bannerEnd) return;
            float alpha = (float) Math.min(1.0, (bannerEnd - now) / 400.0);
            Composite old = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g.setFont(new Font("SansSerif", Font.BOLD, 32));
            int w = Math.max(360, g.getFontMetrics().stringWidth(banner) + 60);
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRoundRect(CX - w / 2 + 4, 222, w, 76, 24, 24);
            g.setColor(bannerColor);
            g.fillRoundRect(CX - w / 2, 216, w, 76, 24, 24);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(3f));
            g.drawRoundRect(CX - w / 2, 216, w, 76, 24, 24);
            centerText(g, banner, CX, 266);
            g.setComposite(old);
        }

        void paintOverlay(Graphics2D g) {
            if (overlay == null) return;
            buttons.clear();   // nothing else is clickable now
            g.setColor(new Color(0, 0, 0, 225));
            g.fillRect(-1000, -1000, VW + 2000, VH + 2000);
            g.setColor(new Color(255, 215, 90));
            g.setFont(new Font("SansSerif", Font.BOLD, 54));
            centerText(g, "GAME OVER", CX, 300);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 34));
            centerText(g, overlay, CX, 360);
            button(g, "EXIT", CX - 80, 420, 160, 58, new Color(200, 50, 50), true, () -> System.exit(0));
        }

        // ---------- small drawing helpers ----------

        void button(Graphics2D g, String label, int x, int y, int w, int h, Color color, boolean enabled, Runnable action) {
            Rectangle r = new Rectangle(x, y, w, h);
            boolean hover = enabled && r.contains(mouse);
            Color c = enabled ? (hover ? color.brighter() : color) : new Color(80, 80, 90);
            g.setColor(c.darker().darker());
            g.fillRoundRect(x, y + 5, w, h, 18, 18);
            g.setColor(c);
            g.fillRoundRect(x, y + (hover ? 2 : 0), w, h, 18, 18);
            g.setColor(enabled ? Color.WHITE : new Color(170, 170, 175));
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            centerText(g, label, x + w / 2, y + h / 2 + 8 + (hover ? 2 : 0));
            buttons.add(new Btn(r, enabled, action));
        }

        void drawCard(Graphics2D g, String code, int x, int y, int w, int h) {
            int arc = w / 5;
            g.setColor(new Color(0, 0, 0, 70));
            g.fillRoundRect(x + 3, y + 4, w, h, arc, arc);
            if (code.equals("??")) {
                g.setColor(new Color(178, 34, 52));
                g.fillRoundRect(x, y, w, h, arc, arc);
                Shape oldClip = g.getClip();
                g.clip(new RoundRectangle2D.Double(x + 5, y + 5, w - 10, h - 10, arc, arc));
                g.setColor(new Color(255, 255, 255, 50));
                g.setStroke(new BasicStroke(2f));
                for (int i = -h; i < w; i += 8) g.drawLine(x + i, y, x + i + h, y + h);
                g.setClip(oldClip);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(2.5f));
                g.drawRoundRect(x + 4, y + 4, w - 8, h - 8, arc, arc);
                return;
            }
            String rank = code.substring(0, code.length() - 1);
            char suit = code.charAt(code.length() - 1);
            String sym = switch (suit) {
                case 'S' -> "♠";
                case 'H' -> "♥";
                case 'D' -> "♦";
                default -> "♣";
            };
            Color col = (suit == 'H' || suit == 'D') ? new Color(205, 30, 45) : new Color(25, 25, 35);
            g.setColor(Color.WHITE);
            g.fillRoundRect(x, y, w, h, arc, arc);
            g.setColor(new Color(0, 0, 0, 50));
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(x, y, w, h, arc, arc);

            AffineTransform saved = g.getTransform();
            for (int corner = 0; corner < 2; corner++) {
                if (corner == 1) g.rotate(Math.PI, x + w / 2.0, y + h / 2.0);
                g.setColor(col);
                g.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, h / 5)));
                g.drawString(rank, x + w * 0.08f, y + h * 0.21f);
                g.setFont(new Font("SansSerif", Font.PLAIN, Math.max(9, h / 6)));
                g.drawString(sym, x + w * 0.09f, y + h * 0.37f);
            }
            g.setTransform(saved);
            g.setFont(new Font("SansSerif", Font.PLAIN, (int) (h * 0.42)));
            centerText(g, sym, x + w / 2, (int) (y + h * 0.66));
        }

        // the bet as a stack of chips (biggest at the bottom, at most 5) with the amount under it
        void drawBetStack(Graphics2D g, int cx, int cy, int r, int amount) {
            List<Integer> stack = new ArrayList<>();
            int rest = amount;
            for (int i = CHIP_VALUES.length - 1; i >= 0; i--) {
                while (rest >= CHIP_VALUES[i] && stack.size() < 5) {
                    stack.add(i);
                    rest -= CHIP_VALUES[i];
                }
            }
            if (stack.isEmpty()) stack.add(0);
            for (int k = 0; k < stack.size(); k++) {
                int i = stack.get(k);
                boolean top = k == stack.size() - 1;
                drawChip(g, cx, cy - k * (r / 5 + 1), r, CHIP_COLORS[i], top ? shortMoney(CHIP_VALUES[i]) : "");
            }
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, r < 24 ? 13 : 15));
            centerText(g, money(amount), cx, cy + r + 20);
        }

        void drawEmptySlot(Graphics2D g, int x, int y, int w, int h) {
            g.setColor(new Color(255, 255, 255, 40));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6, 6}, 0));
            g.drawRoundRect(x, y, w, h, w / 5, w / 5);
        }

        void drawChip(Graphics2D g, int cx, int cy, int r, Color base, String label) {
            g.setColor(new Color(0, 0, 0, 90));
            g.fillOval(cx - r + 2, cy - r + 4, 2 * r, 2 * r);
            g.setColor(base);
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
            g.setColor(new Color(255, 255, 255, 230));
            g.setStroke(new BasicStroke(r * 0.24f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            double d = r * 1.76;
            for (int i = 0; i < 6; i++)
                g.draw(new Arc2D.Double(cx - d / 2, cy - d / 2, d, d, i * 60 + 10, 24, Arc2D.OPEN));
            g.setColor(base.darker());
            int ir = (int) (r * 0.62);
            g.fillOval(cx - ir, cy - ir, 2 * ir, 2 * ir);
            g.setColor(new Color(255, 255, 255, 200));
            g.setStroke(new BasicStroke(1.5f));
            g.drawOval(cx - ir, cy - ir, 2 * ir, 2 * ir);
            if (!label.isEmpty()) {
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, Math.max(10, (int) (r * (label.length() > 3 ? 0.42 : 0.55)))));
                centerText(g, label, cx, cy + (int) (r * 0.19));
            }
        }

        void drawRing(Graphics2D g, int cx, int cy, int r, double fraction, String label) {
            fraction = Math.max(0, Math.min(1, fraction));
            g.setColor(new Color(0, 0, 0, 170));
            g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
            Color c = fraction > 0.5 ? new Color(70, 220, 120) : fraction > 0.25 ? new Color(250, 200, 50) : new Color(240, 70, 60);
            g.setColor(c);
            g.setStroke(new BasicStroke(Math.max(3f, r / 6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int in = (int) Math.max(3, r / 8.0);
            g.draw(new Arc2D.Double(cx - r + in, cy - r + in, 2 * (r - in), 2 * (r - in), 90, 360 * fraction, Arc2D.OPEN));
            if (label != null) {
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, r / 2 - (label.length() > 5 ? 4 : 0))));
                centerText(g, label, cx, cy + r / 6);
            }
        }

        void drawBadge(Graphics2D g, String text, int x, int y) {
            if (text == null || text.isEmpty() || text.equals("-")) return;
            g.setFont(new Font("SansSerif", Font.BOLD, 16));
            int w = g.getFontMetrics().stringWidth(text) + 18;
            int v = text.matches("\\d+") ? Integer.parseInt(text) : 0;
            g.setColor(v > 21 ? new Color(200, 40, 40) : v == 21 ? new Color(220, 160, 20) : new Color(20, 20, 25, 220));
            g.fillRoundRect(x, y - 15, w, 28, 28, 28);
            g.setColor(Color.WHITE);
            g.drawString(text, x + 9, y + 5);
        }

        static void centerText(Graphics2D g, String text, int cx, int baseline) {
            g.drawString(text, cx - g.getFontMetrics().stringWidth(text) / 2, baseline);
        }

        static String shortMoney(int v) {
            return v >= 1000 ? (v / 1000) + "K" : String.valueOf(v);
        }

        static String fit(Graphics2D g, String text, int width) {
            FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(text) <= width) return text;
            while (text.length() > 1 && fm.stringWidth(text + "...") > width) text = text.substring(0, text.length() - 1);
            return text + "...";
        }
    }
}
