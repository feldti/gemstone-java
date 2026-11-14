# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projekt

- **Zweck:** Java-Schnittstelle zu GemStone/S 64 Bit über GemBuilder for C (klassische GCI), Gegenstück zum C#-Wrapper `GemStone.Gci` (`/home/mf/claude-projects/csharp-gemstone`, Paket `Gs64Net.Gci`). Ziel: derselbe Funktionsumfang (Kern, Objekt-Mapping, Signale, Change-Feed/Replikat, Repository-Verwaltung, Annotation-Processor statt Source-Generator).
- **Technik:** Java 25, Foreign Function & Memory API (`java.lang.foreign`), kein JNI, kein C-Code. Aus Kotlin direkt nutzbar.
- **GemStone-Versionen:** 3.6.5 bis 3.7.6 (Zielversion der Anwendungen: 3.7.6).
- Die Erkenntnisse über GemStone (Bugs, Lecks, undokumentiertes Verhalten) stehen ausführlich in der `CLAUDE.md` des C#-Repos. Sie gelten hier genauso; hier steht nur, was Java-spezifisch ist oder wie es umgesetzt wurde.
- Keine Begriffe aus dem Anwendungsgebiet der Anwendungen in Code, Doku oder Commits; neutrale Beispiele. PUM darf genannt werden.

## Befehle

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
mvn -q -B test                                  # alle Tests (Stone-Tests nur mit GS_TEST_STONE)
mvn -q -B test -Dtest=GciValueIntegrationTest   # eine Testklasse
mvn -q -B package -DskipTests                   # gci/target/gs64-gci-<version>.jar

# Test-Stones wie im C#-Repo (Gastmodus, NetLDI-Port):
#   test365 3.6.5 37497   test368 3.6.8 40925   test372 3.7.2 32847   test375 3.7.5 36281   test376 3.7.6 39857
export GEMSTONE=/datadisk/pas/work/products/GemStone64Bit3.7.6-x86_64.Linux
export GS_TEST_STONE=test376 GS_TEST_GEM_SERVICE='!@localhost#netldi:39857!gemnetobject'
mvn -q -B test
# Ergebnis: cat gci/target/surefire-reports/*.txt | grep 'Tests run'
```

Weitere Variablen: `GS_TEST_USER` (DataCurator), `GS_TEST_PASSWORD` (swordfish), `GS_TEST_HOST_USER`, `GS_TEST_HOST_PASSWORD`, `GEMSTONE_GCI_LIBRARY`. `es2025` ist eine echte Datenbank: nicht für Tests verwenden.

Surefire startet die JVM mit `--enable-native-access=ALL-UNNAMED` und `LC_NUMERIC=C` (siehe Parent-POM). Eine Anwendung braucht ebenfalls `--enable-native-access=ALL-UNNAMED` (oder `Enable-Native-Access: ALL-UNNAMED` im Manifest einer ausführbaren JAR), sonst warnt die JVM bei jedem eingeschränkten Aufruf.

## Aufbau

- Maven-Parent `pom.xml` (groupId `net.gs64`, Java-Release 25, `-Xlint:all -Werror`), Module `gci-processor` (artifactId `gs64-gci-processor`) und `gci` (artifactId `gs64-gci`, Package `net.gs64.gci`).
- `net.gs64.gci.internal.GciLibrary` — sucht `libgcirpc-<version>-64.{so,dylib,dll}` in `GEMSTONE_GCI_LIBRARY` → `$GEMSTONE/lib` → `$GEMSTONE/bin` → Anwendungsverzeichnis (`-Dnet.gs64.gci.appDir`, sonst Arbeitsverzeichnis) → OS-Suche; höchste Version gewinnt (`candidates` ist eine reine Funktion, getestet). Laden mit `SymbolLookup.libraryLookup(path, Arena.global())`.
- `net.gs64.gci.internal.GciNative` — ein `static final MethodHandle` pro GCI-Funktion (`Linker.nativeLinker().downcallHandle`), aufgelöst beim ersten Zugriff auf die Klasse, plus eine Wrapper-Methode mit `invokeExact`. Typen: `OopType`/`int64`/`size_t` → `JAVA_LONG`, `int`/`BoolType` → `JAVA_INT`, Zeiger → `ADDRESS` (`MemorySegment`), Strings als null-terminiertes UTF-8 aus einer `Arena.ofConfined()` pro Aufruf. Neue Funktionen genau so anlegen und die Signatur gegen `$GEMSTONE/include/gci.hf` prüfen (dieselben `int`/`int64`-Fallen wie in C#). Optionale Funktionen (nur `GciOopToFlt_`, fehlt in 3.6) über `optional(…)`, das `null` liefert. `entryPointNames()` + `GciLibraryTest.allEntryPointsAreExported` sichern ab, dass jeder Name exportiert ist.
- Native Strukturen werden nicht als Java-Klassen gespiegelt, sondern per Offset gelesen (Offsets im Kommentar an der Stelle): `GciErrSType` (2168 Byte, in `GciSession.lastError`), `GciObjRepHdrSType` (40 Byte, `TravBuffer.readReports`), `GciClampedTravArgsSType` (40 Byte, `clampedArgs`, `isRpc = 1`), `GciPosixDateAndTime` (16 Byte).
- `Oop` ist ein `record Oop(long value)`; die Tag-Bit-Makros sind wie in C# portiert (`>>>` für vorzeichenlose Verschiebung). Massendaten (Traversal-Reports) halten OOPs als `long[]`-Blöcke zu 64K, nicht als `Oop`-Objekte; `GciObjectReport.oops()`/`indexed()` sind Sichten, `oopAt`/`namedValue`/`indexedValues` liefern rohe Werte.
- `GciSession` (eine Datei, Java hat keine partial classes): Login/Lock/Fehler, Execute/Perform/Export-Set, Transaktionen, Werte, struktureller Zugriff, Traversal, Async.
  - Prozessweiter Lock `GciSession.SYNC` (`ReentrantLock`), Helfer `locked(Supplier)`/`locked(Runnable)`. Jeder native Aufruf hinter `activate()` (zählt `callCount`, schaltet per `GciSetSessionId` um, wirft bei laufendem Async-Aufruf `IllegalStateException`).
  - Nach Fehlern: `discardError` (fatal → Session weg; sonst `GciClearStack` + Fehler-OOPs freigeben).
  - Ausgeloggte Session → `IllegalStateException` (C#: `ObjectDisposedException`).
- **Werte** (`toValue`/`fromValue`): nil → `null`, Boolean, Integer → `Long` bzw. `BigInteger`, Float → `Double`, ScaledDecimal → `BigDecimal` (beliebige Skala, keine 28-Stellen-Grenze wie bei .NET `decimal`), Character → `CodePoint` (eigener Record, weil `char` nur die BMP abdeckt), Strings → `String`, ByteArray → `byte[]`, DateAndTime → `OffsetDateTime` (Offsets mit Sekunden bleiben erhalten, anders als in .NET), Date → `LocalDate`, Time → `LocalTime`. `fromValue` nimmt zusätzlich `Instant` (UTC), `ZonedDateTime`, `LocalDateTime` (Systemzeitzone), `Character`, `Integer`/`Short`/`Byte`. `evaluate(source, Class)` wandelt Zahlen mit Bereichsprüfung um (`ArithmeticException`), parst nie Strings.
- **Async:** `…Async` liefern `GciFuture<T>` (ein `CompletableFuture`). Der `GciNb*`-Start läuft auf dem aufrufenden Thread (ein synchroner Aufruf direkt danach scheitert also verlässlich), das Abfragen auf einem virtuellen Thread: unter dem Lock `GciNbEndPoll(…, 0)`, außerhalb `Thread.sleep` 1 → 32 ms. `cancel(true)` schickt einen Soft Break; das Future endet erst, wenn der Server aufgehört hat, mit `CancellationException` (Ursache: die `GciException`), die Session bleibt nutzbar. Commit/Abort sind nicht abbrechbar. Wartet ein Aufruf auf den Socket (`waitOnSocket`, für den Signal-Listener), fragt ein Plattform-Thread ab, weil `GciPollSocketForRead` einen virtuellen Thread an seinen Träger heften würde.
- `runInTransaction(Function)` mit Ergebnis, `doInTransaction(Consumer, n)` ohne: gleiche Namen wären bei impliziten Lambdas mehrdeutig.
- Interne Haken für andere Packages (Mapping): `net.gs64.gci.internal.SessionAccess` (von `GciSession` im statischen Initialisierer installiert): Lock, Transaktions-Listener (`addTransactionListener`, Commit/Abort über die API), `attachments()` (Zustand pro Session, z. B. die Clamp-Spezifikationen). Kein API.
- **Store-Traversal** öffentlich: `GciNewObject`, `getFreeOops(n)` (höchstens 100.000 je `GciGetFreeOops`, Rest bleibt unverändert → in Stücken, auf 0 geprüft; über 2.000 auf ganze 2.000er-Blöcke gerundet wegen des Windows-Client-Bugs, Rest im Pool der Session), `storeAndExecute(objekte, quelltext, self)` = `GciStoreTrav` + `GciExecuteStrFromContext`.
- Zählung `callCount`: eine Schleife über Teilstücke (`fetchVaryingOops`, `storeVaryingOops`, `appendOops`) zählt einmal, wie in C#.

## Objekt-Mapping (`net.gs64.gci.mapping`)

Port von `GsObjectContext` (C#: `Mapping/`), gleiche Algorithmen (Discover/Build mit Runden, Flatten auf dem Server, ein Umwandlungs-Batch, Clamp-Spezifikation pro Session, Snapshots, Batch-Speichern mit `BATCH_SOURCE` und denselben Op-Codes 1–9, Journal für Abort, `saveChanges(kandidaten)`). Java-spezifisch:

- **Annotiert werden Felder** (`@GsInstVar` an Feldern beliebiger Sichtbarkeit, nicht `final`; `@GsOop`, `@GsCollection`, `@GsDictionaryKey`, `@GsConverter`, `@GsReadOnly`, `@GsIgnore`). Zugriff über `MethodHandles.privateLookupIn(deklarierendeKlasse, lookup())` → `unreflectGetter/Setter`, `asType(Object…)`, `invokeExact`. In einem benannten Modul muss das Package für `net.gs64.gci` geöffnet sein. Kotlin-Properties: Annotationen mit `@Target(FIELD)` landen automatisch am Backing Field.
- **Typen** sind `java.lang.reflect.Type` (Type Erasure): `GsShape` pro Typ, `Types.typeArgument` löst Typvariablen über Ober­klassen auf (eigene Listentypen wie `class OwnList<T> extends ArrayList<T>`, Feldtyp `OwnList<Thing>`). Generische Wurzeln über `TypeRef<T>` (`new TypeRef<List<Account>>() {}`). Listen → `ArrayList`, Sets → `LinkedHashSet`, Maps → `LinkedHashMap` (Ladereihenfolge bleibt), Arrays `T[]` (keine primitiven Arrays außer `byte[]`, das ein Skalar ist).
- **Polymorphie:** Java kann nicht wie .NET alle Klassen einer Assembly durchsuchen. Unterklassen kommen aus `sealed`-Hierarchien (`getPermittedSubclasses`, rekursiv), `@GsSubtypes({…})` und `new ReflectionMappingProvider(Klassen…)`/`register`. Getestet mit einem `sealed interface` + `sealed`-Basisklasse + Unterklasse einer Unterklasse.
- **Enums:** Konstanten `IN_PROGRESS` ↔ Symbol `#inProgress` (`SaveRun.symbolName`; beim Laden exakt, sonst ohne Unterstriche/Groß-Klein, `LoadRun.enumConstant`). War das geladene Symbol großgeschrieben (`'Active'`), wird `#Frozen` geschrieben (`lowerFirst`). Als SmallInteger gespeicherte Enums: `ordinal()`.
- **Datum/Zeit:** `LocalDateTime` als Ortszeit (Systemzeitzone) wie `DateTime` in C#; `OffsetDateTime`, `ZonedDateTime`, `Instant` behalten den Zeitpunkt. Keine Sonderbehandlung für Min/Max (anders als `DateTime.MinValue` in C#).
- Konverter: `GsValueConverter.fromGemStone`; schreiben nur mit `canWrite()` + `toGemStone` (sonst `UnsupportedOperationException` beim Speichern eines geänderten Werts).
- Snapshots sind Klassen (keine Structs in Java): `PropertySnapshot` mit Markern für Werte im OOP, `BoxCache` (−128…1023). Speicher pro Objekt daher eher höher als in C#; nicht gemessen.
- Change-Feed: `setChangeFeed`, Op 6 im Batch, Klingeln nach dem Commit (`feedRing`).
- Tests (`gci/src/test/java/net/gs64/gci/mapping`): Port von `GsMappingIntegrationTests`, `GsSaveChangesIntegrationTests`, `GsServerCollectionsIntegrationTests`, `GsUncommittedSavesIntegrationTests`; Modellklassen als statische innere Klassen (`MappingModel`, `SaveModel`, `ServerModel`), weil `-Xlint:all` Hilfsklassen in fremden Dateien anmahnt (`auxiliaryclass`). Gegentest: `PropertySnapshot.matches` immer `true` → 37 von 56 Speichertests rot.

## Annotation-Processor (`gci-processor`, artifactId `gs64-gci-processor`)

Gegenstück zum C#-Source-Generator: `net.gs64.gci.processor.GsMappingProcessor` (keine Abhängigkeiten, liest die Annotationen über ihre Namen; `META-INF/services/javax.annotation.processing.Processor`). Erzeugt

- pro `@GsClass`-Klasse `<Außen>_<Klasse>_GsMapping` im Package der Klasse: `register(classes, factories, polymorphic)` und `metadata()` mit `GsTypeMetadata.mappedClass(…)`. Direkter Feldzugriff (`o -> ((Account) o).name`), wenn Feld, deklarierende Klasse und Feldtyp vom Package aus erreichbar sind; sonst ein `VarHandle` über `GsGeneratedSupport.field` (`privateLookupIn`). Konstruktor direkt oder über `GsGeneratedSupport.constructor`. Typen ohne Reflection über `GsTypes.parameterized/array` (gleich den JDK-Typen, `equals`/`hashCode` nach Vertrag; getestet gegen `Field.getGenericType`).
- eine Registry `GsGeneratedMappings` (im längsten gemeinsamen Package der gemappten Klassen, mit `-Ags64.mappings=voll.qualifizierter.Name` wählbar) mit `DEFAULT`. Sie kennt alle gemappten Klassen der Kompilierung: Unterklassen und Implementierungen von Interfaces/Basisklassen, die Felder erreichen, werden ohne `sealed`/`@GsSubtypes`/Registrierung gefunden. Eigene Collection-Klassen bekommen eine Fabrik ohne Reflection. Collections allgemein über `GsCollectionMetadata.forType` (auch vom Reflection-Provider benutzt).
- Fehler beim Kompilieren statt zur Laufzeit: `final`-Feld, `@GsOop` an einem Nicht-`Oop`, generische/lokale/private/nicht statische innere gemappte Klasse. Fehlender Konstruktor und unbrauchbarer Konverter kommen wie beim Reflection-Provider als Problem in die Metadaten (`GsMappingException` beim ersten Gebrauch).
- Erzeugter Code: `@SuppressWarnings({"unchecked", "rawtypes", "cast"})`, kompiliert mit `-Xlint:all -Werror`.
- **JDK 23+ führt Processoren vom Klassenpfad nicht mehr von selbst aus:** `-proc:full` (Maven: `<proc>full</proc>`) oder `annotationProcessorPaths`. In `gci/pom.xml` hängt der Processor als Test-Abhängigkeit, Test-Kompilierung mit `proc full` und `-Xlint:all,-processing` (JUnit-Annotationen beansprucht kein Processor); die Hauptkompilierung mit `proc none`.
- Tests: `Gs{Mapping,SaveChanges,ServerCollections}GeneratedIntegrationTest` (alle Mapping-Tests noch einmal mit `GsGeneratedMappings.DEFAULT`, 77) und `GsMappingProcessorTest` (kompiliert Quellen im Speicher mit `ToolProvider`: private/geschützte Mitglieder über Package-Grenzen, eigene Listentypen, Unterklassen ohne `sealed`, Fehlermeldungen, Name der Registry). Gegentest: ohne die Registrierung polymorpher Typen (anfangs schloss ein Filter `net.gs64.gci.*` aus, also auch die Testklassen) wurden die drei Interface-Tests der erzeugten Variante rot.

## Signale, Change-Feed, Replikat, Verwaltung

Port von `GciSession.Signals.cs`, `GciSignalListener.cs`, `GciChangeFeed.cs`, `Mapping/GsReplica.cs`, `GciRepositoryAdmin.cs`; der Smalltalk-Code (Listen-Block, Feed-Methoden, Sessions-Abfrage) ist unverändert übernommen, ebenso alle Erkenntnisse aus der C#-`CLAUDE.md` (Signalisieren nur während des Wartens, `_signalFromGemStoneSession`, Soft Break fangbar, 6008/6010 beim nächsten Aufruf, RC-Collections nicht im Notify-Set …). Java-spezifisch:

- `GciSignalListener`: Ereignisse über `onSignals(Consumer<GciSignals>)`/`onError(Consumer<Exception>)`, optional über `setExecutor` (z. B. `SwingUtilities::invokeLater`), sonst auf dem Loop-Thread. Warten blockierend: `await(Duration)` (unterbrechbar per `Thread.interrupt`: Soft Break, das Gelesene geht mit dem nächsten Aufruf). Hintergrund: `start()` (wartet, Plattform-Thread) oder `start(Duration)` (fragt ab). Zugang zur Session über einen fairen `ReentrantLock`; `watch`/`poll` von anderen Threads unterbrechen ein laufendes Warten per `GciFuture.cancel` (Soft Break, den der Block fängt). `stop()` aus dem eigenen Handler wartet nicht auf sich selbst.
- Das Warten nutzt `startNonblocking(…, waitOnSocket = true)`: ein Plattform-Thread hängt in `GciPollSocketForRead` (ein virtueller Thread würde seinen Träger blockieren). Ein Signal, das beim Start des Aufrufs als Fehler 6008/6010 auftaucht, wird festgehalten und der Aufruf wiederholt (`SignalCapture`, `callCapturingSignals`).
- `GciChangeFeed.object()` ist öffentlich (C#: intern), `GsObjectContext.setChangeFeed(feed)` statt Property.
- `GsReplica<T>(ctx, feed, consumer, listener, rootSource, Class<T>)`: `ReentrantReadWriteLock`, Timer als `ScheduledExecutorService`, Commit der Entnahme als `tryCommitAsync()` (das nächste Update wartet mit `join`). Setzt den `onSignals`-Handler des Listeners (ein Listener gehört einem Replikat).
- `GciRepositoryAdmin`: gleiche Methoden, `…Async` liefern `GciFuture`; Zeiten als `Instant`.
- Gegentests: Signalisieren dauerhaft an → `aSignalAndACommitArrivingTogether` 4× rot; kein Klingeln nach `saveChangesAndCommit` → `theReplicaFollowsAMappedWriter` rot. (Den Fehler-6010-Pfad abzuschalten bleibt grün: Bei abgeschaltetem Signalisieren kommen Signale nie als Fehler, sondern über die Queue.)

## Samples (`samples`, artifactId `gs64-samples`)

```bash
mvn -q -B -DskipTests package          # baut auch samples/target/lib (Laufzeit-Klassenpfad)
R="$JAVA_HOME/bin/java --enable-native-access=ALL-UNNAMED -Xmx8g -cp samples/target/classes:samples/target/lib/*"
GS_STONE=test376 GS_GEM_SERVICE='!@localhost#netldi:39857!gemnetobject' GS_PASSWORD=swordfish $R net.gs64.samples.GsRepl
$R net.gs64.samples.GsRepl -e "3 + 4"
$R net.gs64.samples.ReplicaBench setup        # gleiche Daten und Namen wie der C#-Benchmark (GemStoneGciBench_Root, Bench*)
$R net.gs64.samples.ReplicaBench 2            # zwei Runden laden, getrackt und ungetrackt
$R net.gs64.samples.ReplicaBench bigsave 15000
$R net.gs64.samples.ReplicaBench cleanup
```

- `GsRepl`: wie die C#-REPL (`:commit`, `:abort`, `:q`, `\` am Zeilenende). Ctrl+C = Soft Break über `sun.misc.Signal`, **per Reflection** aufgerufen: der direkte Aufruf erzeugt eine nicht unterdrückbare javac-Warnung („interne proprietäre API“), und das Modul baut mit `-Werror`. Zum Testen per `kill -INT`: ein aus einer nicht interaktiven Shell mit `&` gestarteter Prozess ignoriert SIGINT (`SigIgn` 0x2 in `/proc/<pid>/status`), dann greift kein Handler; mit `signal.SIG_DFL` neu starten.
- `ReplicaBench` nutzt die erzeugten Metadaten (Processor mit `proc full` im Modul; die Registry landet in `net.gs64.samples`).
- **Messung (3.7.6 lokal, 825.001 Objekte, 2026-11-13):** getrackt 5,5–6,4 s (C#: 5,2 s), ungetrackt 4,1–4,3 s (C#: 3,6 s), Heap 695 / 270 MB (C#: 549 / 242 MB), GC 0,3–0,4 s, 2.688 GCI-Aufrufe. `bigsave 15000` (615.501 neue Objekte): Anlegen 5,8 s (C#: 9,9 s), `saveChanges` ohne Änderungen 0,5 s (C#: 0,9 s), Commit 0,4 s.
- **Gefunden mit JFR** (`-XX:StartFlightRecording=…,settings=profile`, `jfr print --events jdk.ExecutionSample`): Vorher 9,8 / 7,0 s, fast alles in `HashMap$TreeNode`. OOPs von Heap-Objekten haben gleiche untere Bits, `Long.hashCode` und der Record-`hashCode` mischen nicht, große Tabellen entarteten zu Baum-Buckets. Jetzt `Oop.hashCode` mit Fibonacci-Mischung und Report-Tabellen mit `Oop`- statt `Long`-Schlüsseln (`GciTraversal.decode` nimmt `Map<Oop, GciObjectReport>`). Test: `OopTest.hashCodesOfHeapOopsSpread`.

## GraalVM native-image

GraalVM CE 25.5.5.0.3 (JDK 25.0.5) liegt unter `~/.local/graalvm/graalvm-community-25.5.5.0.3+1.1` (nur Serial-GC, kein PGO).

```bash
G=$(ls -d ~/.local/graalvm/graalvm-*)
mvn -q -B -DskipTests package
cd samples && $G/bin/native-image -cp "target/classes:$(ls target/lib/*.jar | tr '\n' ':')" net.gs64.samples.ReplicaBench -o target/replicabench
# Metadaten neu erfassen (nach neuen GCI-Funktionen): mit dem Agenten auf der GraalVM-JVM laufen lassen
$G/bin/java -agentlib:native-image-agent=config-merge-dir=/tmp/ni-config --enable-native-access=ALL-UNNAMED -cp … net.gs64.samples.ReplicaBench 1
```

- Die Bibliothek bringt ihre Metadaten mit: `gci/src/main/resources/META-INF/native-image/net.gs64/gs64-gci/reachability-metadata.json` (alle 42 Downcall-Signaturen von `GciNative`; vom Agenten erfasst, der Agent sieht sie alle, weil der statische Initialisierer alle Handles anlegt) und `native-image.properties` (`-H:+UnlockExperimentalVMOptions -H:+ForeignAPISupport --enable-native-access=ALL-UNNAMED`). native-image liest beides aus der JAR; die Anwendung braucht keine eigenen Optionen. **Eine neue Funktion in `GciNative` mit neuer Signatur muss dort nachgetragen werden**, sonst scheitert das native Image beim Anlegen des Handles.
- Mit `GsGeneratedMappings` braucht das Mapping keine Reflection (der Agent fand nur einen JDK-Eintrag für Datumsformate). Der Reflection-Provider bräuchte dagegen Metadaten für alle gemappten Klassen.
- `UTF-32LE` ist kein Standard-Zeichensatz und fehlt in nativen Images (`UnsupportedCharsetException` beim Laden von `GciTraversal`): wird jetzt von Hand dekodiert (`GciTraversal.utf32le`).
- Messung (3.7.6 lokal): `gsrepl -e` 0,07 s und 22 MB statt 0,23 s und 96 MB auf der JVM; Ctrl+C geht auch nativ. Großer Graph (825.001 Objekte) dagegen langsamer: getrackt 11,5–17,4 s (mit `-Xmn3g` 10 s), ungetrackt 6,7–8,9 s, `bigsave 15000` 15,2 s statt 5,8 s, fast alles GC-Zeit des Serial-GC; Heap kleiner (523 / 211 MB statt 695 / 270 MB). Für große Kontexte also die JVM; Oracle GraalVM (G1, PGO) nicht getestet.

## Stand

- Kern, Store-Traversal, Objekt-Mapping, Annotation-Processor, Signale, Change-Feed, Replikat und Repository-Verwaltung fertig: 352 Tests grün auf 3.6.5 (test365) und 3.7.6 (test376). test368/372/375 liefen beim letzten Lauf nicht (Fehler 4042 beim Login, `gslist` zeigt sie nicht).
- Noch offen: Tests auf 3.6.8/3.7.2/3.7.5 und unter Windows/arm64.
