package dev.nexus.bedwars.db;

/** Immutable history row written once per finished game. */
public record MatchRecord(String serverId, String arena, String mode, String winnerTeam,
                          long durationSeconds, long startedAtEpochMs, long endedAtEpochMs) {}
