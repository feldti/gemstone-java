package net.gs64.gci;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Result of {@link GciSession#traverse} and friends: all object reports of a graph, fetched in
 * one (or few) round trips and decodable without the server.
 *
 * <p>Reported OOPs are not in the export set: a temporary (uncommitted) object that is reachable
 * only from this snapshot may be garbage-collected by the gem. The snapshot itself stays readable;
 * to keep working with the objects on the server, keep a root referenced (e.g. with
 * {@link GciTraversalOptions#keepResult()}) or commit them.
 */
public final class GciTraversal {

    private final List<Oop> roots;
    private final Oop result;
    private final List<GciObjectReport> reports;
    private Map<Oop, GciObjectReport> byOop;

    GciTraversal(List<Oop> roots, Oop result, List<GciObjectReport> reports) {
        this.roots = List.copyOf(roots);
        this.result = result;
        // An object larger than the buffer arrives as several partial reports with increasing
        // firstOffset (one per GciMoreTraversal buffer): merge them into one report. Without such
        // continuations the list is used as it is, and the lookup table is built on first use.
        if (reports.stream().noneMatch(r -> r.firstOffset() != 1)) {
            this.reports = reports;
            return;
        }
        var map = new HashMap<Oop, GciObjectReport>(reports.size() * 2);
        var position = new HashMap<Oop, Integer>();
        var ordered = new ArrayList<GciObjectReport>(reports.size());
        for (var r : reports) {
            var key = r.oop();
            var existing = map.get(key);
            if (existing != null) {
                var merged = existing.mergeWith(r);
                map.put(key, merged);
                ordered.set(position.get(key), merged);
            } else {
                map.put(key, r);
                position.put(key, ordered.size());
                ordered.add(r);
            }
        }
        byOop = map;
        this.reports = ordered;
    }

    private Map<Oop, GciObjectReport> byOop() {
        if (byOop == null) {
            var map = new HashMap<Oop, GciObjectReport>(reports.size() * 2);
            for (var r : reports) map.put(r.oop(), r);
            byOop = map;
        }
        return byOop;
    }

    /** Objects the traversal started from. */
    public List<Oop> roots() {
        return roots;
    }

    /** Execution result for {@link GciSession#executeTraverse} / {@link GciSession#performTraverse}, else null. */
    public Oop result() {
        return result;
    }

    /** All reports in traversal order. */
    public List<GciObjectReport> reports() {
        return reports;
    }

    public boolean contains(Oop oop) {
        return byOop().containsKey(oop);
    }

    /** The report of {@code oop}. */
    public GciObjectReport get(Oop oop) {
        var r = byOop().get(oop);
        if (r == null) throw new NoSuchElementException(oop + " is not part of this traversal.");
        return r;
    }

    /** The report of {@code oop}, or null. */
    public GciObjectReport find(Oop oop) {
        return byOop().get(oop);
    }

    /**
     * Decodes a value without a server round trip: specials (nil, Boolean, SmallInteger,
     * SmallDouble, Character) and reported objects of the classes String, Symbol, Unicode7/16/32,
     * DoubleByteString/Symbol, QuadByteSymbol, Utf8, ByteArray and Float. Anything else (and
     * partial reports) is returned as {@link Oop}.
     */
    public Object toValue(Oop oop) {
        return decode(oop, byOop());
    }

    /** The offline decoder behind {@link #toValue}, for reports collected from several traversals (keyed by OOP value). */
    public static Object decode(Oop oop, Map<Oop, GciObjectReport> reports) {
        if (oop.isNil()) return null;
        if (oop.isBoolean()) return oop.toBoolean();
        if (oop.isSmallInteger()) return oop.toSmallInteger();
        if (oop.isSmallDouble()) return oop.toSmallDouble();
        if (oop.isCharacter()) return new CodePoint(oop.toCharacter());
        var r = reports.get(oop);
        if (r == null || r.isPartial() || r.format() != GciObjectFormat.BYTE) return oop;
        var bytes = r.bytes();
        var c = r.objectClass();
        if (c.equals(KnownClasses.STRING) || c.equals(KnownClasses.SYMBOL) || c.equals(KnownClasses.UNICODE7))
            return new String(bytes, StandardCharsets.ISO_8859_1);
        if (c.equals(KnownClasses.UTF8)) return new String(bytes, StandardCharsets.UTF_8);
        if (c.equals(KnownClasses.UNICODE16) || c.equals(KnownClasses.DOUBLE_BYTE_STRING) || c.equals(KnownClasses.DOUBLE_BYTE_SYMBOL))
            return new String(bytes, StandardCharsets.UTF_16LE);
        if (c.equals(KnownClasses.UNICODE32) || c.equals(KnownClasses.QUAD_BYTE_SYMBOL)) return utf32le(bytes);
        if (c.equals(KnownClasses.BYTE_ARRAY)) return bytes.clone();
        if (c.equals(KnownClasses.FLOAT) && bytes.length == 8) return java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getDouble();
        return oop;
    }

    /**
     * UTF-32 in client byte order (little endian). Decoded by hand: the charset is not one of the
     * standard charsets, and a GraalVM native image leaves it out unless built with all charsets.
     */
    static String utf32le(byte[] bytes) {
        var sb = new StringBuilder(bytes.length / 4);
        for (var i = 0; i + 3 < bytes.length; i += 4)
            sb.appendCodePoint((bytes[i] & 0xFF) | (bytes[i + 1] & 0xFF) << 8 | (bytes[i + 2] & 0xFF) << 16 | (bytes[i + 3] & 0xFF) << 24);
        return sb.toString();
    }

    /** Whether {@link #decode} would answer a value rather than the {@link Oop} itself, without decoding it. */
    public static boolean canDecode(Oop oop, Map<Oop, GciObjectReport> reports) {
        if (oop.isNil() || oop.isBoolean() || oop.isSmallInteger() || oop.isSmallDouble() || oop.isCharacter()) return true;
        var r = reports.get(oop);
        if (r == null || r.isPartial() || r.format() != GciObjectFormat.BYTE) return false;
        var c = r.objectClass();
        return c.equals(KnownClasses.STRING) || c.equals(KnownClasses.SYMBOL) || c.equals(KnownClasses.UNICODE7)
                || c.equals(KnownClasses.UTF8) || c.equals(KnownClasses.UNICODE16) || c.equals(KnownClasses.DOUBLE_BYTE_STRING)
                || c.equals(KnownClasses.DOUBLE_BYTE_SYMBOL) || c.equals(KnownClasses.UNICODE32) || c.equals(KnownClasses.QUAD_BYTE_SYMBOL)
                || c.equals(KnownClasses.BYTE_ARRAY) || (c.equals(KnownClasses.FLOAT) && r.bytes().length == 8);
    }

    /** Named instance variable of a reported object, decoded with {@link #toValue}. */
    public Object named(Oop obj, int index) {
        return toValue(get(obj).named(index));
    }

    /** Indexed slots / members of a reported object, decoded with {@link #toValue}. */
    public List<Object> toList(Oop obj) {
        var result = new ArrayList<Object>();
        for (var o : get(obj).indexed()) result.add(toValue(o));
        return result;
    }
}
