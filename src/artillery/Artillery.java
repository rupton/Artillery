package artillery;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.Random;

/**
 * Artillery - Classic two-player ballistic combat game
 * Player 1 (left) vs Player 2 (right)
 * Enter angle and power, account for wind, blow up your opponent.
 */
public class Artillery extends JFrame {

    // ── Constants ────────────────────────────────────────────────────────────
    static final int W = 900, H = 550;
    static final int TERRAIN_SEGS = 120;
    static final int CANNON_R = 12;
    static final int BASE_R = 18;
    static final double GRAVITY = 9.8;
    static final double DT = 0.05;          // simulation time step
    static final int ANIM_MS = 16;          // ~60 fps
    static final int EXPLOSION_FRAMES = 30;
    static final int CRATER_R = 28;
    static final Color SKY_TOP    = new Color(10, 14, 35);
    static final Color SKY_BOT    = new Color(30, 45, 90);
    static final Color TERRAIN_C  = new Color(55, 110, 55);
    static final Color TERRAIN_E  = new Color(35, 75, 35);
    static final Color P1_COLOR   = new Color(80, 200, 255);
    static final Color P2_COLOR   = new Color(255, 120, 80);
    static final Color SHELL_C    = new Color(255, 240, 100);
    static final Color HUD_BG     = new Color(0, 0, 0, 160);

    // ── Game State ────────────────────────────────────────────────────────────
    int[]    terrain   = new int[TERRAIN_SEGS + 1];  // y values of terrain top
    boolean[] craters  = new boolean[W];             // blown-out pixels
    int[] craterDepth  = new int[W];

    int p1x, p2x;          // cannon base x positions
    int p1Score, p2Score;
    int currentPlayer = 1; // 1 or 2
    double windSpeed;      // px/s, negative = leftward

    // Shell simulation
    boolean firing     = false;
    double  shellX, shellY;
    double  velX, velY;
    int     trailLen   = 0;
    double[]trailX     = new double[500];
    double[]trailY     = new double[500];

    // Explosion
    boolean exploding  = false;
    int     expFrame   = 0;
    double  expX, expY;
    int     expWinner  = 0; // who got hit: 1 or 2, 0 = miss

    // Input fields
    JTextField angleField, powerField;
    JButton    fireButton;
    JLabel     statusLabel, windLabel, scoreLabel;
    JPanel     canvas;
    Timer      animTimer;

    Random rng = new Random();

    // ── Entry Point ───────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Artillery().setVisible(true));
    }

    // ── Constructor ───────────────────────────────────────────────────────────
    Artillery() {
        super("☠ ARTILLERY ☠");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setResizable(false);

        buildUI();
        newGame();

        setSize(W, H + 160);
        setLocationRelativeTo(null);
    }

    // ── UI Construction ───────────────────────────────────────────────────────
    void buildUI() {
        setBackground(Color.BLACK);
        setLayout(new BorderLayout(0, 0));

        // ── Canvas ────
        canvas = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                render((Graphics2D) g);
            }
        };
        canvas.setPreferredSize(new Dimension(W, H));
        canvas.setBackground(Color.BLACK);
        add(canvas, BorderLayout.CENTER);

        // ── Control Panel ────
        JPanel ctrl = new JPanel();
        ctrl.setBackground(new Color(15, 15, 25));
        ctrl.setLayout(new FlowLayout(FlowLayout.CENTER, 14, 8));
        ctrl.setPreferredSize(new Dimension(W, 140));
        ctrl.setBorder(BorderFactory.createMatteBorder(2, 0, 0, 0, new Color(50, 80, 140)));

        Font mono = new Font("Monospaced", Font.BOLD, 13);
        Color fg  = new Color(200, 220, 255);

        scoreLabel  = styledLabel("P1: 0   P2: 0", mono, new Color(180, 200, 255));
        windLabel   = styledLabel("WIND: →  0.0", mono, new Color(150, 220, 150));
        statusLabel = styledLabel("PLAYER 1 — Enter angle & power", mono, P1_COLOR);

        JLabel aLbl = styledLabel("ANGLE:", mono, fg);
        JLabel pLbl = styledLabel("POWER:", mono, fg);

        angleField = styledField("45", mono);
        powerField = styledField("50", mono);

        fireButton = new JButton("FIRE!");
        fireButton.setFont(new Font("Monospaced", Font.BOLD, 14));
        fireButton.setBackground(new Color(180, 40, 40));
        fireButton.setForeground(Color.WHITE);
        fireButton.setFocusPainted(false);
        fireButton.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(255, 100, 100), 1),
            BorderFactory.createEmptyBorder(4, 18, 4, 18)));
        fireButton.addActionListener(e -> fire());

        JButton newBtn = new JButton("NEW GAME");
        newBtn.setFont(new Font("Monospaced", Font.BOLD, 12));
        newBtn.setBackground(new Color(30, 60, 100));
        newBtn.setForeground(new Color(180, 210, 255));
        newBtn.setFocusPainted(false);
        newBtn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(80, 130, 200), 1),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        newBtn.addActionListener(e -> newGame());

        ctrl.add(scoreLabel);
        ctrl.add(Box.createHorizontalStrut(20));
        ctrl.add(windLabel);
        ctrl.add(Box.createHorizontalStrut(20));
        ctrl.add(statusLabel);
        ctrl.add(Box.createHorizontalStrut(30));
        ctrl.add(aLbl); ctrl.add(angleField);
        ctrl.add(pLbl); ctrl.add(powerField);
        ctrl.add(fireButton);
        ctrl.add(newBtn);

        add(ctrl, BorderLayout.SOUTH);

        // Animation timer
        animTimer = new Timer(ANIM_MS, e -> tick());
    }

    JLabel styledLabel(String t, Font f, Color c) {
        JLabel l = new JLabel(t);
        l.setFont(f);
        l.setForeground(c);
        return l;
    }

    JTextField styledField(String def, Font f) {
        JTextField tf = new JTextField(def, 4);
        tf.setFont(f);
        tf.setBackground(new Color(20, 25, 45));
        tf.setForeground(Color.WHITE);
        tf.setCaretColor(Color.WHITE);
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 90, 160)),
            BorderFactory.createEmptyBorder(2, 5, 2, 5)));
        return tf;
    }

    // ── Game Init ─────────────────────────────────────────────────────────────
    void newGame() {
        animTimer.stop();
        firing = exploding = false;
        p1Score = p2Score = 0;
        generateLevel();
        updateHUD();
        canvas.repaint();
    }

    void generateLevel() {
        craters   = new boolean[W];
        craterDepth = new int[W];

        // Midpoint displacement terrain
        terrain[0] = H - 120;
        terrain[TERRAIN_SEGS] = H - 120;
        displace(0, TERRAIN_SEGS, 160);

        // Flatten platform zones for cannons
        int s1 = 3, s2 = TERRAIN_SEGS - 4;
        int flat1 = terrain[s1], flat2 = terrain[s2];
        for (int i = s1 - 2; i <= s1 + 2; i++) terrain[i] = flat1;
        for (int i = s2 - 2; i <= s2 + 2; i++) terrain[i] = flat2;

        p1x = (int)(s1 * (double)W / TERRAIN_SEGS);
        p2x = (int)(s2 * (double)W / TERRAIN_SEGS);

        windSpeed = (rng.nextDouble() * 60 - 30); // -30 to +30 px/s

        currentPlayer = 1;
        trailLen = 0;
    }

    void displace(int lo, int hi, double roughness) {
        if (hi - lo <= 1) return;
        int mid = (lo + hi) / 2;
        int avg = (terrain[lo] + terrain[hi]) / 2;
        int range = (int)(roughness * (hi - lo) / TERRAIN_SEGS);
        terrain[mid] = avg + rng.nextInt(range * 2 + 1) - range;
        terrain[mid] = Math.max(H / 4, Math.min(H - 40, terrain[mid]));
        displace(lo, mid, roughness);
        displace(mid, hi, roughness);
    }

    int terrainY(int px) {
        px = Math.max(0, Math.min(W, px));
        double seg = px * (double) TERRAIN_SEGS / W;
        int lo = (int) seg, hi = Math.min(lo + 1, TERRAIN_SEGS);
        double t = seg - lo;
        int base = (int)(terrain[lo] * (1 - t) + terrain[hi] * t);
        return base - craterDepth[Math.max(0, Math.min(W - 1, px))];
    }

    int cannonBaseY(int px) {
        return terrainY(px);
    }

    // ── Fire ──────────────────────────────────────────────────────────────────
    void fire() {
        if (firing || exploding) return;
        double angle, power;
        try {
            angle = Double.parseDouble(angleField.getText().trim());
            power = Double.parseDouble(powerField.getText().trim());
        } catch (NumberFormatException ex) {
            statusLabel.setText("Bad input — enter numbers!");
            return;
        }
        angle = Math.max(1, Math.min(179, angle));
        power = Math.max(1, Math.min(100, power));

        // Player 1 fires right (angle 0=right), Player 2 fires left (mirror)
        double rad;
        if (currentPlayer == 1) {
            rad = Math.toRadians(angle);        // 0°=right, 90°=up
        } else {
            rad = Math.toRadians(180 - angle);  // mirror for P2
        }

        int cx = (currentPlayer == 1) ? p1x : p2x;
        int cy = cannonBaseY(cx) - BASE_R;

        // Barrel tip offset
        double barrelLen = CANNON_R + 14;
        shellX = cx + Math.cos(rad) * barrelLen;
        shellY = cy - Math.sin(rad) * barrelLen;

        double speed = power * 4.0;
        velX = Math.cos(rad) * speed;
        velY = -Math.sin(rad) * speed;  // screen Y flipped

        trailLen = 0;
        firing = true;
        animTimer.start();
        fireButton.setEnabled(false);
    }

    // ── Animation Tick ────────────────────────────────────────────────────────
    void tick() {
        if (exploding) {
            expFrame++;
            if (expFrame >= EXPLOSION_FRAMES) {
                exploding = false;
                animTimer.stop();
                if (expWinner != 0) {
                    if (expWinner == 1) p2Score++;
                    else                p1Score++;
                    generateLevel();
                    updateHUD();
                } else {
                    switchPlayer();
                }
                fireButton.setEnabled(true);
            }
            canvas.repaint();
            return;
        }

        // Physics step
        velX += windSpeed * DT;
        velY += GRAVITY * DT * 10;  // scale gravity to feel right
        shellX += velX * DT;
        shellY += velY * DT;

        // Record trail
        if (trailLen < trailX.length) {
            trailX[trailLen] = shellX;
            trailY[trailLen] = shellY;
            trailLen++;
        }

        // Out of bounds
        if (shellX < -50 || shellX > W + 50 || shellY > H + 50) {
            firing = false;
            animTimer.stop();
            fireButton.setEnabled(true);
            statusLabel.setText("Miss! PLAYER " + currentPlayer + " — shot went out of bounds");
            switchPlayer();
            return;
        }

        // Hit terrain?
        if (shellY >= terrainY((int) shellX)) {
            startExplosion(shellX, shellY, 0);
            applyTerrain((int) shellX, (int) shellY);
            return;
        }

        // Hit a cannon base?
        int other = (currentPlayer == 1) ? 2 : 1;
        int targetX = (other == 1) ? p1x : p2x;
        int targetY = cannonBaseY(targetX);
        double dx = shellX - targetX, dy = shellY - targetY;
        if (Math.sqrt(dx * dx + dy * dy) < BASE_R * 2.5) {
            startExplosion(shellX, shellY, other);
            applyTerrain((int) shellX, (int) shellY);
            return;
        }

        canvas.repaint();
    }

    void startExplosion(double x, double y, int winner) {
        firing    = false;
        exploding = true;
        expFrame  = 0;
        expX = x; expY = y;
        expWinner = winner;
        if (winner != 0) {
            int scorer = (winner == 1) ? 2 : 1;
            statusLabel.setText("DIRECT HIT!  Player " + scorer + " scores!");
            statusLabel.setForeground(scorer == 1 ? P1_COLOR : P2_COLOR);
        } else {
            statusLabel.setText("BOOM — terrain hit. Switching sides.");
            statusLabel.setForeground(Color.LIGHT_GRAY);
        }
    }

    void applyTerrain(int cx, int cy) {
        for (int x = cx - CRATER_R; x <= cx + CRATER_R; x++) {
            if (x < 0 || x >= W) continue;
            double dx = x - cx;
            double depth = Math.sqrt(Math.max(0, CRATER_R * CRATER_R - dx * dx));
            craterDepth[x] = Math.max(craterDepth[x], (int) depth);
        }
    }

    void switchPlayer() {
        currentPlayer = (currentPlayer == 1) ? 2 : 1;
        trailLen = 0;
        updateHUD();
    }

    void updateHUD() {
        String dir  = windSpeed >= 0 ? "→" : "←";
        windLabel.setText(String.format("WIND: %s %4.1f", dir, Math.abs(windSpeed)));
        scoreLabel.setText(String.format("P1: %d   P2: %d", p1Score, p2Score));
        statusLabel.setText("PLAYER " + currentPlayer + " — Enter angle & power");
        statusLabel.setForeground(currentPlayer == 1 ? P1_COLOR : P2_COLOR);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────
    void render(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Sky gradient
        GradientPaint sky = new GradientPaint(0, 0, SKY_TOP, 0, H, SKY_BOT);
        g.setPaint(sky);
        g.fillRect(0, 0, W, H);

        // Stars
        g.setColor(new Color(255, 255, 255, 80));
        rng.setSeed(42);
        for (int i = 0; i < 120; i++) {
            int sx = rng.nextInt(W), sy = rng.nextInt(H / 2);
            g.fillOval(sx, sy, 1, 1);
        }
        rng.setSeed(System.currentTimeMillis()); // restore randomness

        // Terrain fill
        Polygon poly = new Polygon();
        poly.addPoint(0, H);
        for (int seg = 0; seg <= TERRAIN_SEGS; seg++) {
            int px = (int)(seg * (double) W / TERRAIN_SEGS);
            int py = terrainY(px);
            poly.addPoint(px, py);
        }
        poly.addPoint(W, H);
        g.setColor(TERRAIN_C);
        g.fillPolygon(poly);

        // Terrain edge
        g.setColor(TERRAIN_E);
        g.setStroke(new BasicStroke(2f));
        for (int seg = 0; seg < TERRAIN_SEGS; seg++) {
            int x1 = (int)(seg * (double) W / TERRAIN_SEGS);
            int x2 = (int)((seg + 1) * (double) W / TERRAIN_SEGS);
            g.drawLine(x1, terrainY(x1), x2, terrainY(x2));
        }

        // Cannons
        drawCannon(g, p1x, 1, firing && currentPlayer == 1 ? 0 : getAngle(1));
        drawCannon(g, p2x, 2, firing && currentPlayer == 2 ? 0 : getAngle(2));

        // Shell trail
        if (trailLen > 1) {
            g.setStroke(new BasicStroke(1.5f));
            for (int i = 1; i < trailLen; i++) {
                float alpha = (float) i / trailLen * 0.7f;
                g.setColor(new Color(1f, 0.95f, 0.4f, alpha));
                g.drawLine((int) trailX[i-1], (int) trailY[i-1],
                           (int) trailX[i],   (int) trailY[i]);
            }
        }

        // Shell
        if (firing) {
            g.setColor(SHELL_C);
            g.fillOval((int) shellX - 4, (int) shellY - 4, 8, 8);
            g.setColor(Color.WHITE);
            g.fillOval((int) shellX - 2, (int) shellY - 2, 4, 4);
        }

        // Explosion
        if (exploding) {
            float t = (float) expFrame / EXPLOSION_FRAMES;
            int maxR = 55;
            // Outer ring
            int r1 = (int)(maxR * t);
            float a1 = Math.max(0f, 1f - t * 1.3f);
            g.setColor(new Color(1f, 0.5f, 0.1f, a1));
            g.setStroke(new BasicStroke(3f));
            g.drawOval((int)expX - r1, (int)expY - r1, r1*2, r1*2);

            // Core
            int r2 = (int)(maxR * 0.5f * Math.max(0, 1f - t * 2f));
            if (r2 > 0) {
                g.setColor(new Color(1f, 1f, 0.6f, Math.min(1f, a1 * 2)));
                g.fillOval((int)expX - r2, (int)expY - r2, r2*2, r2*2);
            }

            // Sparks
            rng.setSeed(expFrame * 17L);
            int numSparks = 16;
            for (int i = 0; i < numSparks; i++) {
                double angle = rng.nextDouble() * Math.PI * 2;
                double dist  = rng.nextDouble() * maxR * t;
                int sx = (int)(expX + Math.cos(angle) * dist);
                int sy = (int)(expY + Math.sin(angle) * dist);
                float sa = (float)(rng.nextDouble() * a1);
                g.setColor(new Color(1f, rng.nextFloat() * 0.6f + 0.4f, 0.1f, sa));
                g.fillOval(sx - 2, sy - 2, 4, 4);
            }
        }

        // Aim guide for current player (dotted preview line)
        if (!firing && !exploding) {
            drawAimGuide(g);
        }
    }

    double getAngle(int player) {
        try {
            double a = Double.parseDouble(angleField.getText().trim());
            return Math.max(1, Math.min(179, a));
        } catch (Exception e) { return 45; }
    }

    void drawCannon(Graphics2D g, int cx, int player, double aimAngle) {
        int cy = cannonBaseY(cx);
        Color pc = (player == 1) ? P1_COLOR : P2_COLOR;

        // Base wheel/body
        g.setColor(pc.darker());
        g.fillOval(cx - BASE_R, cy - BASE_R / 2, BASE_R * 2, BASE_R);
        g.setColor(pc);
        g.setStroke(new BasicStroke(2));
        g.drawOval(cx - BASE_R, cy - BASE_R / 2, BASE_R * 2, BASE_R);

        // Barrel
        double rad = Math.toRadians(aimAngle);
        if (player == 2) rad = Math.toRadians(180 - aimAngle);
        double bx = Math.cos(rad) * (CANNON_R + 10);
        double by = -Math.sin(rad) * (CANNON_R + 10);
        g.setColor(pc);
        g.setStroke(new BasicStroke(6, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(cx, cy - 4, (int)(cx + bx), (int)(cy - 4 + by));

        // Label
        g.setColor(pc);
        g.setFont(new Font("Monospaced", Font.BOLD, 11));
        String lbl = "P" + player;
        int lx = (player == 1) ? cx - 28 : cx + 10;
        g.drawString(lbl, lx, cy - 20);

        // Health indicator (always full — one hit kills)
        g.setColor(new Color(50, 200, 50));
        g.fillRect(cx - BASE_R, cy - BASE_R / 2 - 8, BASE_R * 2, 4);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1));
        g.drawRect(cx - BASE_R, cy - BASE_R / 2 - 8, BASE_R * 2, 4);
    }

    void drawAimGuide(Graphics2D g) {
        int cx = (currentPlayer == 1) ? p1x : p2x;
        int cy = cannonBaseY(cx) - 4;

        double aimDeg = getAngle(currentPlayer);
        double rad = Math.toRadians(aimDeg);
        if (currentPlayer == 2) rad = Math.toRadians(180 - aimDeg);

        double power = 50;
        try { power = Double.parseDouble(powerField.getText().trim()); }
        catch (Exception ignored) {}
        power = Math.max(1, Math.min(100, power));

        double vx = Math.cos(rad) * power * 4.0;
        double vy = -Math.sin(rad) * power * 4.0;
        double px = cx, py = cy;

        Stroke dashed = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                0, new float[]{4, 6}, 0);
        g.setStroke(dashed);
        Color gc = (currentPlayer == 1) ? P1_COLOR : P2_COLOR;

        int steps = 0, maxSteps = 60;
        while (steps < maxSteps) {
            vx += windSpeed * DT;
            vy += GRAVITY * DT * 10;
            px += vx * DT;
            py += vy * DT;
            float alpha = 0.15f * (1f - (float) steps / maxSteps);
            g.setColor(new Color(gc.getRed() / 255f, gc.getGreen() / 255f, gc.getBlue() / 255f, alpha));
            g.fillOval((int) px - 2, (int) py - 2, 4, 4);
            steps++;
            if (px < 0 || px > W || py > H) break;
        }
    }
}