package dev.nexus.bedwars.db;

import java.util.UUID;

public record LeaderboardEntry(int rank, UUID uuid, String name, long value) {}
