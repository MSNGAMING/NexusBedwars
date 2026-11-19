package dev.nexus.bedwars.arena;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Lock-free state holder. {@link #transition} is a compare-and-set loop, so two threads racing
 * (e.g. the global countdown tick and a region thread detecting the last bed) can never both win
 * the same transition – exactly one caller gets {@code true} and runs the side-effects.
 */
public final class ArenaStateMachine {

    private final AtomicReference<ArenaState> state;
    private final List<BiConsumer<ArenaState, ArenaState>> listeners = new CopyOnWriteArrayList<>();

    public ArenaStateMachine(ArenaState initial) {
        this.state = new AtomicReference<>(initial);
    }

    public ArenaState state() {
        return state.get();
    }

    /** @return true if this call performed the transition. */
    public boolean transition(ArenaState target) {
        while (true) {
            ArenaState current = state.get();
            if (!current.canTransitionTo(target)) return false;
            if (state.compareAndSet(current, target)) {
                for (BiConsumer<ArenaState, ArenaState> l : listeners) l.accept(current, target);
                return true;
            }
        }
    }

    /** Transition only when the arena is currently in {@code expected}. */
    public boolean transition(ArenaState expected, ArenaState target) {
        if (!expected.canTransitionTo(target)) return false;
        if (state.compareAndSet(expected, target)) {
            for (BiConsumer<ArenaState, ArenaState> l : listeners) l.accept(expected, target);
            return true;
        }
        return false;
    }

    public void onChange(BiConsumer<ArenaState, ArenaState> listener) {
        listeners.add(listener);
    }
}
