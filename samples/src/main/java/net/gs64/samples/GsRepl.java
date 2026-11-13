package net.gs64.samples;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import net.gs64.gci.GciException;
import net.gs64.gci.GciNetParameters;
import net.gs64.gci.GciSession;
import net.gs64.gci.GciTransactionConflictException;

/**
 * gsrepl — a minimal interactive Smalltalk console on top of the Java GCI wrapper.
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp 'samples/target/classes:samples/target/lib/*' net.gs64.samples.GsRepl
 *      [--stone NAME] [--gem-service NRS] [--user U] [--password P] [--host-user U] [--host-password P] [--eval "expr"]
 * </pre>
 *
 * Every option can also come from an environment variable (GS_STONE, GS_GEM_SERVICE, GS_USER,
 * GS_PASSWORD, GS_HOST_USER, GS_HOST_PASSWORD). Without a password you are prompted. GEMSTONE (or
 * GEMSTONE_GCI_LIBRARY) must point to the GemStone installation.
 */
public final class GsRepl {
    private GsRepl() {
    }

    private static volatile boolean executing;

    public static void main(String[] args) throws Exception {
        var stone = env("GS_STONE", "gs64stone");
        var gemService = env("GS_GEM_SERVICE", "gemnetobject");
        var user = env("GS_USER", "DataCurator");
        var password = System.getenv("GS_PASSWORD");
        var hostUser = env("GS_HOST_USER", "");
        var hostPassword = env("GS_HOST_PASSWORD", "");
        String eval = null;
        for (var i = 0; i < args.length; i++) {
            var value = i + 1 < args.length ? args[i + 1] : null;
            switch (args[i]) {
                case "--stone" -> stone = value;
                case "--gem-service" -> gemService = value;
                case "--user" -> user = value;
                case "--password" -> password = value;
                case "--host-user" -> hostUser = value;
                case "--host-password" -> hostPassword = value;
                case "-e", "--eval" -> eval = value;
                default -> {
                    System.err.println("Unknown option " + args[i] + ". Options: --stone --gem-service --user --password --host-user --host-password --eval");
                    System.exit(2);
                }
            }
            i++;
        }
        if (password == null) {
            var console = System.console();
            password = console != null ? new String(console.readPassword("Password for %s: ", user)) : "";
        }

        GciSession session;
        try {
            System.err.println("Login " + user + "@" + stone + " via " + gemService + " …");
            session = GciSession.login(new GciNetParameters(stone, hostUser, hostPassword, gemService), user, password);
        } catch (GciException | UnsatisfiedLinkError | UnsupportedOperationException e) {
            System.err.println("Login failed: " + e.getMessage());
            if (e instanceof UnsatisfiedLinkError)
                System.err.println("Set GEMSTONE to the GemStone installation, or GEMSTONE_GCI_LIBRARY to the libgcirpc library.");
            System.exit(1);
            return;
        }

        if (eval != null) {
            boolean ok;
            try (session) {
                ok = evaluate(session, eval);
            }
            System.exit(ok ? 0 : 1);
        }
        try (session) {
            // Ctrl+C while code runs: soft break; at the prompt it quits as usual.
            onInterrupt(() -> {
                if (executing) session.softBreak();
                else System.exit(130);
            });
            System.out.println("Connected. Enter Smalltalk, end a line with '\\' to continue it. :help for commands.");
            var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                var source = readStatement(in);
                if (source == null) break;
                if (source.isEmpty()) continue;
                switch (source) {
                    case ":q", ":quit", ":exit" -> {
                        return;
                    }
                    case ":help" -> System.out.println("""
                              <Smalltalk>   execute and print the result's printString
                              :commit       commit the transaction (conflicts are reported)
                              :abort        discard uncommitted changes
                              :q            log out and quit
                              Ctrl+C        while code runs: soft break (interrupt)""");
                    case ":commit" -> command(() -> {
                        session.commit();
                        System.out.println("Committed.");
                    });
                    case ":abort" -> command(() -> {
                        session.abort();
                        System.out.println("Aborted.");
                    });
                    default -> {
                        executing = true;
                        try {
                            if (!evaluate(session, source) && !session.isLoggedIn()) {
                                System.err.println("Session lost — exiting.");
                                System.exit(1);
                            }
                        } finally {
                            executing = false;
                        }
                    }
                }
            }
        }
    }

    /**
     * Ctrl+C handler through sun.misc.Signal (module jdk.unsupported), looked up reflectively so
     * that the build has no warning for it; without it Ctrl+C just ends the program.
     */
    private static void onInterrupt(Runnable action) {
        try {
            var signal = Class.forName("sun.misc.Signal");
            var handler = Class.forName("sun.misc.SignalHandler");
            var proxy = java.lang.reflect.Proxy.newProxyInstance(handler.getClassLoader(), new Class<?>[] {handler}, (p, m, a) -> {
                if (m.getName().equals("handle")) action.run();
                return null;
            });
            signal.getMethod("handle", signal, handler).invoke(null, signal.getConstructor(String.class).newInstance("INT"), proxy);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("(Ctrl+C cannot interrupt running code here: " + e + ")");
        }
    }

    /** Executes source, prints "=> printString" or the error. Returns false on error. */
    private static boolean evaluate(GciSession session, String source) {
        try {
            var result = session.execute(source);
            try {
                System.out.println("=> " + session.printString(result));
            } finally {
                session.release(result);
            }
            return true;
        } catch (GciException e) {
            var message = e.getMessage();
            var colon = message.indexOf(": ");
            System.err.println("Error " + e.number() + (e.isFatal() ? " (fatal)" : "") + ": " + (colon < 0 ? message : message.substring(colon + 2)));
            if (!e.reason().isEmpty()) System.err.println("  reason: " + e.reason());
            return false;
        }
    }

    private static void command(Runnable command) {
        try {
            command.run();
        } catch (GciTransactionConflictException e) {
            System.err.println("Commit failed: transaction conflict. Use :abort, then redo the work.");
            if (e.conflictReport() != null) System.err.println("  " + e.conflictReport());
        } catch (GciException e) {
            System.err.println("Error " + e.number() + ": " + e.getMessage());
        }
    }

    /** Reads one statement; lines ending in '\' are joined with the next line. */
    private static String readStatement(BufferedReader in) throws java.io.IOException {
        var parts = new ArrayList<String>();
        System.out.print("gs> ");
        System.out.flush();
        while (true) {
            var line = in.readLine();
            if (line == null) return parts.isEmpty() ? null : String.join("\n", parts);
            if (line.endsWith("\\")) {
                parts.add(line.substring(0, line.length() - 1));
                System.out.print("..> ");
                System.out.flush();
                continue;
            }
            parts.add(line);
            return String.join("\n", parts).strip();
        }
    }

    private static String env(String name, String fallback) {
        var v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
