package net.gs64.gci;

import java.util.List;

/**
 * What a {@link GciSignalListener} received since the last poll.
 *
 * @param changedObjects watched objects that another session changed and committed (each once)
 * @param messages signals from other sessions, in the order they arrived
 */
public record GciSignals(List<Oop> changedObjects, List<GciSessionSignal> messages) {
    public GciSignals {
        changedObjects = List.copyOf(changedObjects);
        messages = List.copyOf(messages);
    }

    public boolean isEmpty() {
        return changedObjects.isEmpty() && messages.isEmpty();
    }
}
