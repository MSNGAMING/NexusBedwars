package dev.nexus.bedwars.arena;

/**
 * Lifecycle of a running arena:
 *
 * <pre>
 *   DISABLED ──► WAITING ◄──► STARTING ──► RUNNING ──► ENDING ──► RESETTING ──► WAITING
 *                  ▲                          │                       ▲
 *                  └──────────────────────────┴───── (force stop) ─────┘
 * </pre>
 *
 * Transitions are validated by {@link ArenaStateMachine}; illegal ones are rejected atomically.
 */
public enum ArenaState {
    DISABLED,
    WAITING,
    STARTING,
    RUNNING,
    ENDING,
    RESETTING;

    public boolean joinable() {
        return this == WAITING || this == STARTING;
    }

    public boolean canTransitionTo(ArenaState target) {
        if (target == DISABLED) return true; // an admin may always shut an arena down
        return switch (this) {
            case DISABLED -> target == WAITING;
            case WAITING -> target == STARTING;
            case STARTING -> target == WAITING || target == RUNNING;
            case RUNNING -> target == ENDING || target == RESETTING;
            case ENDING -> target == RESETTING;
            case RESETTING -> target == WAITING;
        };
    }
}
