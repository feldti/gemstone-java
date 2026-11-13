package net.gs64.gci.internal;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.gs64.gci.GciSession;

/**
 * Access to package-private parts of {@link GciSession} for other packages of this library (the
 * object mapper). Not an API: the session installs the implementation when its class is
 * initialized.
 */
public interface SessionAccess {
    /** Runs {@code body} under the process-wide GCI lock. */
    <T> T locked(Supplier<T> body);

    /** Listener for commits (true) and aborts (false) made through the session's API. */
    void addTransactionListener(GciSession session, Consumer<Boolean> listener);

    void removeTransactionListener(GciSession session, Consumer<Boolean> listener);

    /** Per-session state of other parts of the library; use under the lock. */
    Map<String, Object> attachments(GciSession session);

    static SessionAccess get() {
        if (Holder.instance == null) {
            try { // initializing GciSession installs the implementation
                Class.forName(GciSession.class.getName(), true, SessionAccess.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return Holder.instance;
    }

    /** Called once by GciSession. */
    static void install(SessionAccess access) {
        if (Holder.instance != null) throw new IllegalStateException("SessionAccess is already installed.");
        Holder.instance = access;
    }

    final class Holder {
        private Holder() {
        }

        static volatile SessionAccess instance;
    }
}
