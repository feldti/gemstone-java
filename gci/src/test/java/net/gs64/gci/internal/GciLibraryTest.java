package net.gs64.gci.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GciLibraryTest {
    @TempDir
    Path root;

    private String touch(String dir, String file) throws IOException {
        var d = Files.createDirectories(root.resolve(dir));
        return Files.createFile(d.resolve(file)).toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {".so", ".dylib", ".dll"})
    void searchOrderIsExplicitThenGemstoneLibBinThenAppDirThenDefault(String ext) throws IOException {
        var lib = touch("gs/lib", "libgcirpc-3.7.5-64" + ext);
        var bin = touch("gs/bin", "libgcirpc-3.7.5-64" + ext);
        var app = touch("app", "libgcirpc-3.7.5-64" + ext);
        touch("gs/lib", "libgcirpc-3.7.5-64.other"); // wrong extension: ignored
        var env = Map.of(GciLibrary.LIBRARY_PATH_VARIABLE, "/opt/custom/libgcirpc.x",
                GciLibrary.GEMSTONE_VARIABLE, root.resolve("gs").toString());
        assertEquals(List.of("/opt/custom/libgcirpc.x", lib, bin, app, "libgcirpc"),
                GciLibrary.candidates(env::get, root.resolve("app").toString(), ext));
    }

    @Test
    void highestVersionWinsNumericallyNotLexically() throws IOException {
        var v9 = touch("gs/lib", "libgcirpc-3.7.9-64.so");
        var v10 = touch("gs/lib", "libgcirpc-3.7.10-64.so");
        var v5 = touch("gs/lib", "libgcirpc-3.7.5-64.so");
        var gs = root.resolve("gs").toString();
        assertEquals(List.of(v10, v9, v5, "libgcirpc"),
                GciLibrary.candidates(k -> k.equals(GciLibrary.GEMSTONE_VARIABLE) ? gs : null, null, ".so"));
    }

    @Test
    void fallsBackToOsSearchWithoutEnvironment() {
        assertEquals(List.of("libgcirpc"), GciLibrary.candidates(k -> null, null, ".so"));
    }

    /**
     * Every entry point must name a symbol the library really exports — catches typos and header
     * macros before the first call fails at runtime. Needs only the library, no stone.
     */
    @Test
    void allEntryPointsAreExported() {
        if (System.getenv("GEMSTONE") == null && System.getenv("GEMSTONE_GCI_LIBRARY") == null) return;
        var names = GciNative.entryPointNames(); // initializing GciNative resolves every entry point
        assertTrue(names.size() >= 60, "only " + names.size() + " entry points found");
        assertTrue(names.stream().allMatch(n -> GciLibrary.tryExport(n).isPresent()));
    }
}
