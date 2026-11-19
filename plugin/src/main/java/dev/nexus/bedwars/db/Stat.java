package dev.nexus.bedwars.db;

import java.util.Locale;
import java.util.Optional;

/**
 * Every persisted player statistic.
 *
 * <p>{@code additive} stats are synchronised as deltas ({@code col = col + ?}), which makes
 * concurrent writers (several servers on one network) lossless. Non-additive stats
 * (streaks) are absolute values – a player is only ever in one game at a time.
 */
public enum Stat {
    WINS("wins", true),
    LOSSES("losses", true),
    KILLS("kills", true),
    DEATHS("deaths", true),
    FINAL_KILLS("final_kills", true),
    FINAL_DEATHS("final_deaths", true),
    BEDS_BROKEN("beds_broken", true),
    GAMES_PLAYED("games_played", true),
    WINSTREAK("winstreak", false),
    BEST_WINSTREAK("best_winstreak", false),
    ELO("elo", true);

    private final String column;
    private final boolean additive;

    Stat(String column, boolean additive) {
        this.column = column;
        this.additive = additive;
    }

    /** Column names come exclusively from this enum → never user input → no SQL injection surface. */
    public String column() { return column; }

    public boolean additive() { return additive; }

    public static Optional<Stat> parse(String input) {
        String n = input.toUpperCase(Locale.ROOT).replace('-', '_');
        for (Stat s : values()) {
            if (s.name().equals(n) || s.column.equalsIgnoreCase(n)) return Optional.of(s);
        }
        return Optional.empty();
    }
}
