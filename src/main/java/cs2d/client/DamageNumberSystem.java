package cs2d.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/** Bounded, Canvas-based lifecycle and renderer for floating damage numbers. */
public final class DamageNumberSystem {
    static final int MAX_ACTIVE = 64;
    static final double LIFETIME_SECONDS = 0.72;
    static final long MERGE_WINDOW_MILLIS = 40L;
    static final Color NORMAL_COLOR = Color.WHITE;
    static final Color HEADSHOT_COLOR = Color.rgb(245, 74, 74);

    private static final double[] LANE_OFFSETS = { -4.0, 4.0, -8.0, 8.0, 0.0 };
    private static final Color OUTLINE_COLOR = Color.rgb(26, 32, 44, 0.92);
    private static final Font DAMAGE_FONT = Font.font("Orbitron", FontWeight.BOLD, 15);
    private static final double SCREEN_MARGIN = 32.0;

    private final Deque<ActiveDamageNumber> activeNumbers = new ArrayDeque<>();
    private long laneCursor;

    public void add(DamageNumberEvent event) {
        if (tryMerge(event)) {
            return;
        }
        while (activeNumbers.size() >= MAX_ACTIVE) {
            activeNumbers.removeFirst();
        }
        double laneOffset = LANE_OFFSETS[(int) Math.floorMod(laneCursor++, LANE_OFFSETS.length)];
        activeNumbers.addLast(new ActiveDamageNumber(event, laneOffset));
    }

    public void advance(double deltaSeconds) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0.0) {
            return;
        }
        for (ActiveDamageNumber number : activeNumbers) {
            number.ageSeconds += deltaSeconds;
        }
        activeNumbers.removeIf(number -> number.ageSeconds >= LIFETIME_SECONDS);
    }

    public void draw(GraphicsContext gc, WorldToScreen projector, double viewportWidth, double viewportHeight) {
        if (gc == null || projector == null || activeNumbers.isEmpty()) {
            return;
        }

        gc.save();
        gc.setFont(DAMAGE_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.setLineWidth(3.0);
        gc.setStroke(OUTLINE_COLOR);
        for (ActiveDamageNumber number : activeNumbers) {
            Point2D anchor = projector.project(number.worldX, number.worldY);
            double progress = clamp01(number.ageSeconds / LIFETIME_SECONDS);
            double easedRise = 1.0 - Math.pow(1.0 - progress, 3.0);
            double x = anchor.getX() + number.laneOffset;
            double y = anchor.getY() - 16.0 - 36.0 * easedRise;
            if (x < -SCREEN_MARGIN || x > viewportWidth + SCREEN_MARGIN
                    || y < -SCREEN_MARGIN || y > viewportHeight + SCREEN_MARGIN) {
                continue;
            }

            double alpha = progress <= 0.42 ? 1.0 : clamp01((1.0 - progress) / 0.58);
            gc.setGlobalAlpha(alpha);
            gc.strokeText(number.text, x, y);
            gc.setFill(colorFor(number.headshot));
            gc.fillText(number.text, x, y);
        }
        gc.restore();
    }

    public void clear() {
        activeNumbers.clear();
        laneCursor = 0L;
    }

    public void clearTeammateDamage() {
        activeNumbers.removeIf(number -> number.teammateDamage);
    }

    int size() {
        return activeNumbers.size();
    }

    List<Snapshot> snapshots() {
        List<Snapshot> snapshots = new ArrayList<>(activeNumbers.size());
        activeNumbers.forEach(number -> snapshots.add(new Snapshot(
                number.damage, number.headshot, number.teammateDamage, number.worldX, number.worldY,
                number.ageSeconds, number.laneOffset)));
        return snapshots;
    }

    static Color colorFor(boolean headshot) {
        return headshot ? HEADSHOT_COLOR : NORMAL_COLOR;
    }

    private boolean tryMerge(DamageNumberEvent event) {
        Iterator<ActiveDamageNumber> iterator = activeNumbers.descendingIterator();
        while (iterator.hasNext()) {
            ActiveDamageNumber number = iterator.next();
            if (number.ageSeconds > MERGE_WINDOW_MILLIS / 1000.0) {
                break;
            }
            if (number.headshot == event.headshot()
                    && number.attackerId.equals(event.attackerId())
                    && number.targetId.equals(event.targetId())
                    && Math.abs(number.serverTimestamp - event.serverTimestamp()) <= MERGE_WINDOW_MILLIS) {
                number.damage += event.damage();
                number.text = Integer.toString(number.damage);
                number.worldX = event.worldX();
                number.worldY = event.worldY();
                number.serverTimestamp = Math.max(number.serverTimestamp, event.serverTimestamp());
                number.ageSeconds = 0.0;
                return true;
            }
        }
        return false;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @FunctionalInterface
    public interface WorldToScreen {
        Point2D project(double worldX, double worldY);
    }

    record Snapshot(int damage, boolean headshot, boolean teammateDamage, double worldX, double worldY,
                    double ageSeconds, double laneOffset) {
    }

    private static final class ActiveDamageNumber {
        private final String attackerId;
        private final String targetId;
        private final boolean headshot;
        private final boolean teammateDamage;
        private final double laneOffset;
        private int damage;
        private String text;
        private double worldX;
        private double worldY;
        private long serverTimestamp;
        private double ageSeconds;

        private ActiveDamageNumber(DamageNumberEvent event, double laneOffset) {
            this.attackerId = event.attackerId();
            this.targetId = event.targetId();
            this.damage = event.damage();
            this.text = Integer.toString(event.damage());
            this.headshot = event.headshot();
            this.teammateDamage = event.teammateDamage();
            this.worldX = event.worldX();
            this.worldY = event.worldY();
            this.serverTimestamp = event.serverTimestamp();
            this.laneOffset = laneOffset;
        }
    }
}
