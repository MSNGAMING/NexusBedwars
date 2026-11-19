package dev.nexus.bedwars.arena;

/** Standard Elo expectation formula, applied against the average rating of all opponents. */
public final class EloCalculator {

    private EloCalculator() {}

    public static long delta(long playerElo, double opponentAverage, boolean won, int kFactor) {
        double expected = 1.0 / (1.0 + Math.pow(10.0, (opponentAverage - playerElo) / 400.0));
        return Math.round(kFactor * ((won ? 1.0 : 0.0) - expected));
    }
}
