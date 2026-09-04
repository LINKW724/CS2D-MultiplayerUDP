package cs2d.client;

/** Mutually exclusive sources shown by the damage-number feedback system. */
public enum DamageNumberVisibility {
    OFF(0, "Off", "Damage numbers are hidden", false, false),
    OWN(1, "Mine", "Only your damage", true, false),
    TEAMMATES(2, "Team", "Only teammate damage", false, true),
    ALL(3, "All", "Your and teammate damage", true, true);

    private final int level;
    private final String tickLabel;
    private final String description;
    private final boolean showsOwnDamage;
    private final boolean showsTeammateDamage;

    DamageNumberVisibility(int level, String tickLabel, String description,
            boolean showsOwnDamage, boolean showsTeammateDamage) {
        this.level = level;
        this.tickLabel = tickLabel;
        this.description = description;
        this.showsOwnDamage = showsOwnDamage;
        this.showsTeammateDamage = showsTeammateDamage;
    }

    public int level() {
        return level;
    }

    public String tickLabel() {
        return tickLabel;
    }

    public String description() {
        return description;
    }

    public boolean showsOwnDamage() {
        return showsOwnDamage;
    }

    public boolean showsTeammateDamage() {
        return showsTeammateDamage;
    }

    public static DamageNumberVisibility fromLevel(double level) {
        int rounded = Math.max(OFF.level, Math.min(ALL.level, (int) Math.round(level)));
        return values()[rounded];
    }
}
