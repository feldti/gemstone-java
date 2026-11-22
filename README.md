# Gs64 GCI for Java

**🚧 Work in progress: not ready for production use. 🚧**

Java access to GemStone/S 64 Bit (3.6.5 – 3.7.6) through GemBuilder for C, the classic GCI. It
uses the Foreign Function and Memory API of Java 22+ (built for Java 25), so it needs no JNI and
no C code, and Kotlin can use it directly. It is the Java counterpart of the C# library
`Gs64Net.Gci`.

```java
var net = new GciNetParameters("gs64stone", "!@localhost#netldi:gs64ldi!gemnetobject");
try (var session = GciSession.login(net, "DataCurator", "swordfish")) {
    long seven = session.evaluate("3 + 4", Long.class);
    var array = session.execute("{1. 'two'. 3.0}");
    var traversal = session.traverse(array, 2);          // one round trip for the graph
    System.out.println(traversal.toList(array));         // [1, two, 3.0]
    session.release(array);
    session.runInTransaction(s -> s.execute("UserGlobals at: #Answer put: 42"));
}
```

Run the JVM with `--enable-native-access=ALL-UNNAMED`. The GemStone client library is not part
of this package: set `GEMSTONE` to a GemStone installation (or `GEMSTONE_GCI_LIBRARY` to the
`libgcirpc` file), or put the library next to the application.

> [!WARNING]
> **Work in progress.** This project is under active development and not ready for production
> use. APIs may change without notice, there are no releases or Maven artifacts yet, and some
> parts are still missing.
>
> What exists is covered by integration tests against GemStone 3.6.5 and 3.7.6 on Linux x86-64
> with Java 25. GemStone 3.6.8, 3.7.2 and 3.7.5, Windows, Linux arm64 and macOS have not been
> tested yet.

## Status

Done: sessions (several per process), execute/perform, value conversion, structural object
access, traversals, path fetches, store traversals, transactions, asynchronous calls with
cancellation, and the object mapper (`GsObjectContext`: annotated Java classes, identity map,
change tracking, batched saves that patch collections in place), signals between sessions with
a listener, a persistent change feed with a replica that follows it, and repository
administration (space, garbage collection, instance counts, sessions, backups). An annotation processor
(`gs64-gci-processor`) generates the mapping metadata at compile time instead of reflection:

```xml
<!-- maven-compiler-plugin: JDK 23+ needs annotation processing switched on -->
<proc>full</proc>
<annotationProcessorPaths>
  <path><groupId>net.gs64</groupId><artifactId>gs64-gci-processor</artifactId><version>0.1.0-SNAPSHOT</version></path>
</annotationProcessorPaths>
```

```java
var ctx = new GsObjectContext(session, GsGeneratedMappings.DEFAULT); // generated next to your classes
```

```java
@GsClass("Customer")
class Customer {
    @GsInstVar("name") String name;
    @GsInstVar("orders") List<Order> orders = new ArrayList<>();
}

try (var ctx = new GsObjectContext(session)) {
    var customer = ctx.load("UserGlobals at: #TheCustomer", Customer.class);
    customer.name = "New name";
    ctx.saveChangesAndCommit();
}
```

## Building

```bash
mvn -B test        # stone tests need GS_TEST_STONE (and GS_TEST_GEM_SERVICE)
```

## License

MIT
