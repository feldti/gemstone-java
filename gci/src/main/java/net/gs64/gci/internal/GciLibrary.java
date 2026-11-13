package net.gs64.gci.internal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds and loads the versioned GemBuilder RPC library once, e.g. {@code libgcirpc-3.7.6-64.so}
 * (Linux), {@code libgcirpc-3.7.6-64.dylib} (macOS) or {@code libgcirpc-3.7.6-64.dll} (Windows
 * client).
 *
 * <p>Search order:
 * <ol>
 *   <li>{@code GEMSTONE_GCI_LIBRARY} — full path to the library file;</li>
 *   <li>{@code $GEMSTONE/lib} and {@code $GEMSTONE/bin} (Windows client installs keep the DLLs in
 *       {@code bin});</li>
 *   <li>the application directory (system property {@code net.gs64.gci.appDir}, else the
 *       working directory) — per chapter 3 of the GCI manual an application may run without
 *       {@code GEMSTONE} if the GemStone libraries sit next to it;</li>
 *   <li>the OS loader search ({@code LD_LIBRARY_PATH} / {@code PATH}) for {@code libgcirpc}.</li>
 * </ol>
 * Within one directory the highest version wins. Loading by absolute path lets the OS find the
 * library's own dependencies (OpenSSL, Kerberos) in the same directory.
 */
public final class GciLibrary {
    public static final String LIBRARY_PATH_VARIABLE = "GEMSTONE_GCI_LIBRARY";
    public static final String GEMSTONE_VARIABLE = "GEMSTONE";
    public static final String BASE_NAME = "libgcirpc";
    private static final Pattern VERSION = Pattern.compile("^libgcirpc-(\\d+(?:\\.\\d+){1,3})");

    private static volatile SymbolLookup lookup;
    private static volatile String loadedFrom;

    private GciLibrary() {
    }

    /** Path (or name) the library was loaded from; {@code null} before the first GCI call. */
    public static String loadedFrom() {
        return loadedFrom;
    }

    /** Address of an export. */
    public static MemorySegment export(String name) {
        return tryExport(name).orElseThrow(() -> new UnsatisfiedLinkError(
                "'" + name + "' is not exported by the GemStone GCI library " + loadedFrom + "."));
    }

    public static Optional<MemorySegment> tryExport(String name) {
        return lookup().find(name);
    }

    private static SymbolLookup lookup() {
        var l = lookup;
        if (l != null) return l;
        synchronized (GciLibrary.class) {
            if (lookup == null) lookup = load();
            return lookup;
        }
    }

    @SuppressWarnings("restricted")
    private static SymbolLookup load() {
        var tried = new ArrayList<String>();
        for (var candidate : candidates(System::getenv, appDirectory(), currentExtension())) {
            try {
                var l = SymbolLookup.libraryLookup(candidate.contains("/") || candidate.contains("\\")
                        ? candidate : candidate + currentExtension(), Arena.global());
                loadedFrom = candidate;
                return l;
            } catch (IllegalArgumentException e) {
                tried.add(candidate);
            }
        }
        throw new UnsatisfiedLinkError(
                "GemStone GCI library (libgcirpc-<version>-64) not found. Set GEMSTONE to the GemStone installation, "
                        + "or GEMSTONE_GCI_LIBRARY to the library file. Tried: " + String.join(", ", tried));
    }

    private static String appDirectory() {
        var dir = System.getProperty("net.gs64.gci.appDir");
        return dir != null ? dir : System.getProperty("user.dir");
    }

    /** File extension of shared libraries on the current OS. */
    static String currentExtension() {
        var os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? ".dll" : os.contains("mac") ? ".dylib" : ".so";
    }

    /** Library paths/names to try, in order. Pure function of its inputs, for tests. */
    static List<String> candidates(Function<String, String> getEnv, String appDirectory, String extension) {
        var result = new ArrayList<String>();
        var explicit = getEnv.apply(LIBRARY_PATH_VARIABLE);
        if (explicit != null && !explicit.isBlank()) result.add(explicit);
        var gemstone = getEnv.apply(GEMSTONE_VARIABLE);
        if (gemstone != null && !gemstone.isBlank()) {
            result.addAll(versionedLibraries(Path.of(gemstone, "lib"), extension));
            result.addAll(versionedLibraries(Path.of(gemstone, "bin"), extension));
        }
        if (appDirectory != null && !appDirectory.isBlank())
            result.addAll(versionedLibraries(Path.of(appDirectory), extension));
        result.add(BASE_NAME);
        return result;
    }

    /** {@code libgcirpc-<version>-64<ext>} files in a directory, highest version first. */
    private static List<String> versionedLibraries(Path directory, String extension) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(BASE_NAME + "-") && n.endsWith(extension))
                    .sorted(Comparator.comparing(GciLibrary::version).thenComparing(Comparator.<String>naturalOrder()).reversed())
                    .map(n -> directory.resolve(n).toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Version of a file name as a comparable key (each part zero-padded). */
    private static String version(String fileName) {
        Matcher m = VERSION.matcher(fileName);
        if (!m.find()) return "";
        var sb = new StringBuilder();
        for (var part : m.group(1).split("\\.")) sb.append(String.format("%06d", Integer.parseInt(part)));
        return sb.toString();
    }
}
