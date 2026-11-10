package com.heartsong;

/** Hysteresis with user-configurable percentages of maximum health. */
public final class HealthGate {
    private int startPercent = 20;
    private int stopPercent = 40;
    private boolean active;

    public void setThresholds(int start, int stop) {
        this.startPercent = Math.clamp(start, 1, 98);
        this.stopPercent = Math.clamp(stop, this.startPercent + 1, 99);
    }

    public int startPercent() { return startPercent; }
    public int stopPercent() { return stopPercent; }

    public boolean update(float health, float maximum, boolean validPlayer) {
        if (!validPlayer || !Float.isFinite(health) || !Float.isFinite(maximum)
                || maximum <= 0 || health <= 0) {
            active = false;
        } else {
            double percent = (double) health * 100.0d / (double) maximum;
            if (percent < startPercent) {
                active = true;
            } else if (percent > stopPercent) {
                active = false;
            }
        }
        return active;
    }

    public boolean active() { return active; }
    public void reset() { active = false; }
}
