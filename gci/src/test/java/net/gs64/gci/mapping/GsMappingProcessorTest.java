package net.gs64.gci.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.gs64.gci.Oop;

/**
 * The annotation processor on small sources compiled in memory (no stone): what it generates
 * compiles without warnings, reaches private and protected members, and turns mapping mistakes
 * into compile errors.
 */
class GsMappingProcessorTest {
    @TempDir
    Path dir;

    private record Result(boolean ok, String output, Path classes) {
    }

    private Result compile(Map<String, String> sources, String... options) throws IOException {
        var src = Files.createDirectories(dir.resolve("src"));
        var out = Files.createDirectories(dir.resolve("out"));
        var files = new ArrayList<String>();
        for (var e : sources.entrySet()) {
            var file = src.resolve(e.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            files.add(file.toString());
        }
        var args = new ArrayList<>(List.of("-d", out.toString(), "-cp", System.getProperty("java.class.path"), "-proc:full",
                "-processor", "net.gs64.gci.processor.GsMappingProcessor", "-Xlint:all,-processing", "-Werror"));
        args.addAll(List.of(options));
        args.addAll(files);
        var output = new StringWriter();
        var rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, redirect(output, args));
        return new Result(rc == 0, output.toString(), out);
    }

    /** javac writes diagnostics to stderr; collect them through -Xstdout into a file. */
    private String[] redirect(StringWriter output, List<String> args) throws IOException {
        var log = dir.resolve("javac.log");
        var all = new ArrayList<String>();
        all.add("-Xstdout");
        all.add(log.toString());
        all.addAll(args);
        logFile = log;
        return all.toArray(String[]::new);
    }

    private Path logFile;

    private String log() throws IOException {
        return logFile != null && Files.exists(logFile) ? Files.readString(logFile) : "";
    }

    @Test
    void reachesPrivateAndProtectedMembersAcrossPackages() throws Exception {
        var r = compile(Map.of(
                "demo.base.Base", """
                        package demo.base;
                        public abstract class Base {
                            @net.gs64.gci.mapping.GsOop protected net.gs64.gci.Oop oop;
                            @net.gs64.gci.mapping.GsInstVar("created") protected long created;
                            public net.gs64.gci.Oop oop() { return oop; }
                        }
                        """,
                "demo.model.Item", """
                        package demo.model;
                        import net.gs64.gci.mapping.*;
                        import java.util.*;
                        @GsClass("DemoItem")
                        public class Item extends demo.base.Base {
                            @GsInstVar("name") private String name;          // like a Kotlin backing field
                            @GsInstVar("tags") List<Map<String, Item>> tags = new ArrayList<>();
                            @GsInstVar("own") Items own = new Items();
                            private Item() { }
                            public String name() { return name; }
                        }
                        """,
                "demo.model.Items", """
                        package demo.model;
                        public class Items extends java.util.ArrayList<Item> {
                            private static final long serialVersionUID = 1L;
                        }
                        """));
        assertTrue(r.ok(), log());
        try (var loader = new URLClassLoader(new java.net.URL[] {r.classes().toUri().toURL()}, getClass().getClassLoader())) {
            // the only mapped class is in demo.model: the provider goes there
            var provider = (GsMappingProvider) loader.loadClass("demo.model.GsGeneratedMappings").getField("DEFAULT").get(null);
            var item = loader.loadClass("demo.model.Item");
            var meta = provider.typeMetadata(item);
            assertNotNull(meta);
            assertEquals("DemoItem", meta.className);
            assertEquals(List.of("created", "name", "tags", "own"), meta.properties.stream().map(p -> p.instVar).toList());
            var instance = meta.create.get(); // private constructor
            var byName = new java.util.HashMap<String, GsPropertyMetadata>();
            for (var p : meta.properties) byName.put(p.name, p);
            byName.get("name").set.accept(instance, "x"); // private field: VarHandle
            assertEquals("x", byName.get("name").get.apply(instance));
            byName.get("created").set.accept(instance, 7L); // protected field of a base class in another package
            assertEquals(7L, byName.get("created").get.apply(instance));
            meta.setOop.accept(instance, new Oop(4711));
            assertEquals(new Oop(4711), item.getMethod("oop").invoke(instance));
            assertEquals("java.util.List<java.util.Map<java.lang.String, demo.model.Item>>", byName.get("tags").type.getTypeName());
            assertEquals(item.getDeclaredField("tags").getGenericType(), byName.get("tags").type); // equal to the JDK's own type
            var own = provider.typeMetadata(loader.loadClass("demo.model.Items"));
            assertNotNull(own); // an own list type, created without reflection
            assertEquals("demo.model.Items", own.newCollection.apply(0).getClass().getName());
        }
    }

    @Test
    void findsSubclassesWithoutSealedOrRegistration() throws Exception {
        var r = compile(Map.of(
                "demo.Shape", "package demo; public interface Shape { }",
                "demo.Circle", "package demo; @net.gs64.gci.mapping.GsClass(\"C\") public class Circle implements Shape { }",
                "demo.Square", "package demo; @net.gs64.gci.mapping.GsClass(\"S\") public class Square implements Shape { }",
                "demo.Big", "package demo; @net.gs64.gci.mapping.GsClass(\"B\") public class Big extends Square { }",
                "demo.Drawing", """
                        package demo;
                        @net.gs64.gci.mapping.GsClass("D")
                        public class Drawing { @net.gs64.gci.mapping.GsInstVar("shapes") java.util.List<Shape> shapes; }
                        """));
        assertTrue(r.ok(), log());
        try (var loader = new URLClassLoader(new java.net.URL[] {r.classes().toUri().toURL()}, getClass().getClassLoader())) {
            var provider = (GsMappingProvider) loader.loadClass("demo.GsGeneratedMappings").getField("DEFAULT").get(null);
            var shape = provider.typeMetadata(loader.loadClass("demo.Shape"));
            assertEquals(List.of("demo.Big", "demo.Circle", "demo.Square"), shape.subtypes.stream().map(Class::getName).sorted().toList());
            assertEquals("demo.Big", shape.subtypes.get(0).getName()); // most derived first
            assertEquals(List.of("demo.Big"), provider.typeMetadata(loader.loadClass("demo.Square")).subtypes.stream().map(Class::getName).toList());
        }
    }

    @Test
    void mappingMistakesAreCompileErrors() throws Exception {
        var r = compile(Map.of("demo.Bad", """
                package demo;
                import net.gs64.gci.mapping.*;
                @GsClass("Bad")
                public class Bad {
                    @GsInstVar("a") final String a = "";
                    @GsOop String oop;
                    @GsClass("Hidden") private static class Hidden { }
                }
                """));
        assertFalse(r.ok());
        var log = log();
        assertTrue(log.contains("Bad.a: a mapped field must not be final."), log);
        assertTrue(log.contains("Bad.oop: @GsOop requires type Oop."), log);
        assertTrue(log.contains("must not be private"), log);
    }

    @Test
    void theProviderNameCanBeChosen() throws Exception {
        var r = compile(Map.of("demo.A", "package demo; @net.gs64.gci.mapping.GsClass(\"A\") public class A { }"),
                "-Ags64.mappings=other.Mappings");
        assertTrue(r.ok(), log());
        assertTrue(Files.exists(r.classes().resolve("other/Mappings.class")));
    }
}
