import java.net.*;
import java.io.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.concurrent.*;

/**
 * ============================================================
 *  BasketballScoreServer.java
 *  Basketball Scoring System — TCP Server  (Milestone 2)
 * ============================================================
 *
 *  Listens on PORT 5000 for exactly 3 scorer clients.
 *  • Scoring commands (2 / 3) enter a 3-second conflict-resolution
 *    window; the highest submitted value is applied.
 *  • All other commands (F, T, Q, P, R, A, B) are applied
 *    immediately from the first scorer who sends them.
 *  • After every state change the full STATE|... string is
 *    broadcast to all connected clients.
 *  • An AWT window shows the live scoreboard on the server machine.
 *
 *  Compile:  javac *.java
 *  Run:      java BasketballScoreServer
 * ============================================================
 */
public class BasketballScoreServer extends Frame {

    // ── Constants ─────────────────────────────────────────────
    static final int  PORT           = 5000;
    static final int  NUM_SCORERS    = 3;
    static final long SCORE_WINDOW_MS = 3000L;   // conflict-resolution window

    // ── Game state ────────────────────────────────────────────
    GameState gameState = new GameState();

    // ── Scorer connections ────────────────────────────────────
    private final ScorerHandler[] scorers          = new ScorerHandler[NUM_SCORERS];
    private final boolean[]       scorerConnected  = new boolean[NUM_SCORERS];

    // ── Conflict-resolution state ─────────────────────────────
    private final Object           scoreLock     = new Object();
    private final int[]            pendingScores = new int[NUM_SCORERS]; // 0 = not yet submitted
    private boolean                windowOpen    = false;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?>     resolveTask;

    // ── AWT components ────────────────────────────────────────
    private Label lblScoreA, lblScoreB;
    private Label lblFoulsA, lblFoulsB;
    private Label lblTimeoutsA, lblTimeoutsB;
    private Label lblQuarter, lblActiveTeam, lblStatus;
    private TextArea logArea;

    // ═══════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ═══════════════════════════════════════════════════════════
    public static void main(String[] args) {
        BasketballScoreServer server = new BasketballScoreServer();
        server.startServer();
    }

    public BasketballScoreServer() {
        buildAWT();
    }

    // ═══════════════════════════════════════════════════════════
    //  AWT SCOREBOARD UI
    // ═══════════════════════════════════════════════════════════
    private void buildAWT() {
        setTitle("🏀  Basketball Score Server  —  Port " + PORT);
        setSize(720, 560);
        setBackground(new Color(18, 22, 34));
        setLayout(new BorderLayout(10, 10));

        // ── top status bar ──────────────────────────────────
        lblStatus = makeLabel("⏳ Waiting for scorers…", 13, new Color(200, 200, 200));
        lblStatus.setBackground(new Color(26, 30, 46));

        // ── scoreboard grid ─────────────────────────────────
        Panel board = new Panel(new GridLayout(5, 3, 16, 10));
        board.setBackground(new Color(26, 30, 46));

        // row 0 — headers
        board.add(makeLabel("",         12, Color.GRAY));
        board.add(makeLabel("TEAM  A",  14, new Color(80, 180, 255)));
        board.add(makeLabel("TEAM  B",  14, new Color(255, 140, 80)));

        // row 1 — scores
        board.add(makeLabel("SCORE", 12, Color.LIGHT_GRAY));
        lblScoreA = makeLabel("0", 36, Color.WHITE);
        lblScoreB = makeLabel("0", 36, Color.WHITE);
        board.add(lblScoreA);
        board.add(lblScoreB);

        // row 2 — fouls
        board.add(makeLabel("FOULS", 12, Color.LIGHT_GRAY));
        lblFoulsA = makeLabel("0", 20, new Color(255, 100, 100));
        lblFoulsB = makeLabel("0", 20, new Color(255, 100, 100));
        board.add(lblFoulsA);
        board.add(lblFoulsB);

        // row 3 — timeouts
        board.add(makeLabel("TIMEOUTS", 12, Color.LIGHT_GRAY));
        lblTimeoutsA = makeLabel("3", 20, new Color(80, 220, 120));
        lblTimeoutsB = makeLabel("3", 20, new Color(80, 220, 120));
        board.add(lblTimeoutsA);
        board.add(lblTimeoutsB);

        // row 4 — quarter + active team
        lblQuarter    = makeLabel("1",        18, new Color(255, 220, 60));
        lblActiveTeam = makeLabel("Team A ▶", 13, new Color(255, 220, 60));
        board.add(makeLabel("QTR / ACTIVE", 12, Color.LIGHT_GRAY));
        board.add(lblQuarter);
        board.add(lblActiveTeam);

        // ── top panel bundles status + board ─────────────────
        Panel topPanel = new Panel(new BorderLayout(0, 8));
        topPanel.setBackground(new Color(18, 22, 34));
        topPanel.add(lblStatus, BorderLayout.NORTH);
        topPanel.add(board,     BorderLayout.CENTER);

        // ── command-reference strip ───────────────────────────
        Label cmdRef = makeLabel(
            "Commands → 2:+2pts  3:+3pts  F:foul  T:timeout  Q:next-quarter  P:pause  R:reset  A/B:switch-team",
            9, new Color(100, 110, 140));
        cmdRef.setBackground(new Color(18, 22, 34));

        // ── log area ─────────────────────────────────────────
        logArea = new TextArea("", 12, 80, TextArea.SCROLLBARS_VERTICAL_ONLY);
        logArea.setEditable(false);
        logArea.setBackground(new Color(10, 12, 18));
        logArea.setForeground(new Color(80, 220, 120));
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));

        add(topPanel, BorderLayout.NORTH);
        add(logArea,  BorderLayout.CENTER);
        add(cmdRef,   BorderLayout.SOUTH);

        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                scheduler.shutdownNow();
                System.exit(0);
            }
        });
        setVisible(true);
    }

    /** Convenience factory for styled AWT labels. */
    private Label makeLabel(String text, int size, Color color) {
        Label l = new Label(text, Label.CENTER);
        l.setFont(new Font("Monospaced", Font.BOLD, size));
        l.setForeground(color);
        l.setBackground(new Color(26, 30, 46));
        return l;
    }

    /** Repaint all AWT widgets from current GameState (called on EDT). */
    private void refreshUI() {
        lblScoreA.setText(String.valueOf(gameState.score[0]));
        lblScoreB.setText(String.valueOf(gameState.score[1]));
        lblFoulsA.setText(String.valueOf(gameState.fouls[0]));
        lblFoulsB.setText(String.valueOf(gameState.fouls[1]));
        lblTimeoutsA.setText(String.valueOf(gameState.timeouts[0]));
        lblTimeoutsB.setText(String.valueOf(gameState.timeouts[1]));
        lblQuarter.setText(String.valueOf(gameState.quarter));
        lblActiveTeam.setText("Team " + (gameState.activeTeam == 0 ? "A" : "B") + " ▶");

        if (gameState.gameOver) {
            lblStatus.setText("🏁  GAME OVER  —  Final: Team A " +
                gameState.score[0] + "  vs  Team B " + gameState.score[1]);
        } else if (gameState.paused) {
            lblStatus.setText("⏸  PAUSED  —  Quarter " + gameState.quarter);
        } else {
            lblStatus.setText("▶  LIVE  —  Q" + gameState.quarter +
                "  |  Active: Team " + (gameState.activeTeam == 0 ? "A" : "B"));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  SERVER STARTUP — always listening, allows reconnection
    // ═══════════════════════════════════════════════════════════
    public void startServer() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                log("━━━ Server started on port " + PORT + " ━━━");
                log("Waiting for " + NUM_SCORERS + " scorer clients...");

                // Keep accepting forever so disconnected scorers can reconnect
                while (true) {
                    Socket socket = serverSocket.accept();

                    // Find the first open (disconnected) slot
                    int idx = findOpenSlot();
                    if (idx == -1) {
                        log("⚠️  Extra connection rejected - all " + NUM_SCORERS + " slots full.");
                        try {
                            PrintWriter pw = new PrintWriter(socket.getOutputStream(), true);
                            pw.println("ERROR|Server full - all scorer slots are occupied.");
                            socket.close();
                        } catch (IOException ignored) {}
                        continue;
                    }

                    try {
                        ScorerHandler handler = new ScorerHandler(socket, idx, this);
                        scorers[idx]         = handler;
                        scorerConnected[idx] = true;
                        new Thread(handler, "Scorer-" + (idx + 1)).start();

                        log("✅ Scorer " + (idx + 1) + " connected from "
                            + socket.getInetAddress().getHostAddress());

                        // Send current game state immediately so reconnecting
                        // scorer is caught up without waiting for the next event
                        handler.getWriter().println(gameState.toProtocolString());

                        int online = countConnected();
                        if (online < NUM_SCORERS) {
                            lblStatus.setText("Scorers online: " + online + "/" + NUM_SCORERS);
                        } else {
                            log("━━━ All " + NUM_SCORERS + " scorers connected! ━━━");
                            broadcast(gameState.toProtocolString());
                            refreshUI();
                        }

                    } catch (IOException e) {
                        log("⚠️  Failed to set up scorer " + (idx + 1) + ": " + e.getMessage());
                        scorerConnected[idx] = false;
                    }
                }

            } catch (IOException e) {
                log("❌ Server error: " + e.getMessage());
            }
        }, "AcceptThread").start();
    }

    /** Returns the index of the first disconnected slot, or -1 if all are full. */
    private synchronized int findOpenSlot() {
        for (int i = 0; i < NUM_SCORERS; i++) {
            if (!scorerConnected[i]) return i;
        }
        return -1;
    }

    /** Returns how many scorer slots are currently connected. */
    private synchronized int countConnected() {
        int n = 0;
        for (boolean b : scorerConnected) if (b) n++;
        return n;
    }

    // ═══════════════════════════════════════════════════════════
    //  COMMAND DISPATCHER
    // ═══════════════════════════════════════════════════════════
    public synchronized void handleCommand(String cmd, int scorerIndex) {

        if (gameState.gameOver && !cmd.equals("R")) {
            log("ℹ️  Game over — only R (reset) is accepted.");
            return;
        }

        log("[Scorer " + (scorerIndex + 1) + "] ← \"" + cmd + "\"");

        switch (cmd) {
            case "2": case "3":
                handleScoreInput(Integer.parseInt(cmd), scorerIndex);
                break;
            case "F":  recordFoul();    break;
            case "T":  useTimeout();    break;
            case "Q":  nextQuarter();   break;
            case "P":  togglePause();   break;
            case "R":  resetGame();     break;
            case "A":
                gameState.activeTeam = 0;
                log("🔁 Active team → Team A");
                broadcastAndRefresh();
                break;
            case "B":
                gameState.activeTeam = 1;
                log("🔁 Active team → Team B");
                broadcastAndRefresh();
                break;
            default:
                log("⚠️  Unknown command: \"" + cmd + "\"");
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CONFLICT RESOLUTION — scoring window
    // ═══════════════════════════════════════════════════════════
    /**
     * Opens (or updates) the 3-second scoring window.
     * Once all three scorers have submitted, or the timer fires,
     * resolveScore() applies the highest value.
     */
    private void handleScoreInput(int value, int scorerIndex) {
        synchronized (scoreLock) {
            if (!windowOpen) {
                Arrays.fill(pendingScores, 0);
                windowOpen = true;
                log("⏱  Score window opened (" + SCORE_WINDOW_MS / 1000 + "s)…");
                resolveTask = scheduler.schedule(
                    this::resolveScore, SCORE_WINDOW_MS, TimeUnit.MILLISECONDS);
            }
            pendingScores[scorerIndex] = value;
            log("   Scorer " + (scorerIndex + 1) + " submitted: " + value);

            // Early resolution if every scorer has already submitted
            boolean allIn = true;
            for (int s : pendingScores) {
                if (s == 0) { allIn = false; break; }
            }
            if (allIn) {
                resolveTask.cancel(false);
                resolveScore();
            }
        }
    }

    /**
     * Majority-rules resolution.
     * Count votes for each score value (2 or 3).
     * Whichever value has the most votes wins.
     * Tie (1-1-1 all different, or 1-1 split) → higher value wins as tiebreaker.
     */
    private void resolveScore() {
        synchronized (scoreLock) {
            if (!windowOpen) return;
            windowOpen = false;

            // Build submission log string
            StringBuilder submissions = new StringBuilder();
            for (int i = 0; i < NUM_SCORERS; i++) {
                submissions.append("S").append(i + 1).append("=")
                           .append(pendingScores[i] == 0 ? "-" : pendingScores[i]);
                if (i < NUM_SCORERS - 1) submissions.append(" ");
            }

            // Count votes per value
            int votes2 = 0, votes3 = 0;
            for (int s : pendingScores) {
                if (s == 2) votes2++;
                else if (s == 3) votes3++;
            }

            int result;
            String reason;
            if (votes2 == 0 && votes3 == 0) {
                // Nobody submitted anything
                log("ℹ️  Score window closed with no submissions.");
                return;
            } else if (votes2 > votes3) {
                result = 2;
                reason = votes2 + " scorer(s) voted 2 — majority wins";
            } else if (votes3 > votes2) {
                result = 3;
                reason = votes3 + " scorer(s) voted 3 — majority wins";
            } else {
                // Tie (e.g. 1 vote each) → higher value wins as tiebreaker
                result = 3;
                reason = "tie (" + votes2 + " vs " + votes3 + ") → 3 wins as tiebreaker";
            }

            if (result > 0) {
                gameState.score[gameState.activeTeam] += result;
                log("✅ Resolved [" + submissions + "] → +" + result +
                    " pts  (" + reason + ")" +
                    "  |  Team " + (gameState.activeTeam == 0 ? "A" : "B") +
                    " total: " + gameState.score[gameState.activeTeam]);
                broadcastAndRefresh();
            } else {
                log("ℹ️  Score window closed with no submissions.");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  GAME CONTROL ACTIONS
    // ═══════════════════════════════════════════════════════════
    private void recordFoul() {
        int t = gameState.activeTeam;
        gameState.fouls[t]++;
        log("🟥 Foul on Team " + (t == 0 ? "A" : "B") +
            "  (total: " + gameState.fouls[t] + ")");
        broadcastAndRefresh();
    }

    private void useTimeout() {
        int t = gameState.activeTeam;
        if (gameState.timeouts[t] > 0) {
            gameState.timeouts[t]--;
            log("⏱  Timeout — Team " + (t == 0 ? "A" : "B") +
                "  remaining: " + gameState.timeouts[t]);
        } else {
            log("⚠️  No timeouts left for Team " + (t == 0 ? "A" : "B"));
        }
        broadcastAndRefresh();
    }

    private void nextQuarter() {
        if (gameState.quarter < 4) {
            gameState.quarter++;
            gameState.paused = true;
            // Reset timeouts each half (quarters 1→2 and 3→4 keep; 2→3 is halftime)
            if (gameState.quarter == 3) {
                gameState.timeouts[0] = 3;
                gameState.timeouts[1] = 3;
                log("🏀 HALFTIME — Timeouts reset to 3 each.");
            }
            log("📣 Quarter → " + gameState.quarter + "  (game paused)");
        } else {
            gameState.gameOver = true;
            log("🏁 GAME OVER!  Final → Team A: " + gameState.score[0] +
                "  Team B: " + gameState.score[1]);
        }
        broadcastAndRefresh();
    }

    private void togglePause() {
        gameState.paused = !gameState.paused;
        log(gameState.paused ? "⏸  Game PAUSED" : "▶  Game RESUMED");
        broadcastAndRefresh();
    }

    private void resetGame() {
        synchronized (scoreLock) {
            if (resolveTask != null) resolveTask.cancel(false);
            windowOpen = false;
            Arrays.fill(pendingScores, 0);
        }
        gameState = new GameState();
        log("🔄 Game RESET — all state cleared.");
        broadcastAndRefresh();
    }

    // ═══════════════════════════════════════════════════════════
    //  BROADCAST helpers
    // ═══════════════════════════════════════════════════════════
    /** Send a raw message string to every connected scorer. */
    public void broadcast(String message) {
        for (int i = 0; i < NUM_SCORERS; i++) {
            if (scorerConnected[i] && scorers[i] != null) {
                scorers[i].getWriter().println(message);
            }
        }
    }

    /** Broadcast updated STATE and repaint the AWT scoreboard. */
    private void broadcastAndRefresh() {
        broadcast(gameState.toProtocolString());
        refreshUI();
    }

    // ═══════════════════════════════════════════════════════════
    //  CONNECTION management
    // ═══════════════════════════════════════════════════════════
    public void scorerDisconnected(int index) {
        scorerConnected[index] = false;
        scorers[index] = null;
        int online = countConnected();
        log("❌ Scorer " + (index + 1) + " disconnected. (" + online + "/" + NUM_SCORERS + " remaining)");
        EventQueue.invokeLater(() ->
            lblStatus.setText("⚠️ Scorer " + (index + 1) +
                " disconnected — waiting for reconnect... (" + online + "/" + NUM_SCORERS + ")")
        );
    }

    // ═══════════════════════════════════════════════════════════
    //  LOG helper (thread-safe append to AWT TextArea)
    // ═══════════════════════════════════════════════════════════
    public synchronized void log(String msg) {
        System.out.println(msg);
        EventQueue.invokeLater(() -> logArea.append(msg + "\n"));
    }
}