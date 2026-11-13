package net.gs64.gci.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

/**
 * Generates the mapping metadata of {@code @GsClass} types at compile time: for every mapped
 * class a helper {@code <Name>_GsMapping} in its package (direct field access where Java allows
 * it, VarHandles otherwise), and one provider {@code GsGeneratedMappings} that knows all mapped
 * classes of the compilation, so polymorphic loading finds subclasses without sealed hierarchies,
 * {@code @GsSubtypes} or registration. Use it with
 * {@code new GsObjectContext(session, GsGeneratedMappings.DEFAULT)}.
 *
 * <p>The provider goes into the longest common package of the mapped classes; the option
 * {@code -Ags64.mappings=fully.qualified.Name} sets its name. Mistakes that the reflection
 * provider reports at run time (a final mapped field, {@code @GsOop} on a field that is no Oop,
 * a mapped class that is generic or private) are compile errors here.
 */
public final class GsMappingProcessor extends AbstractProcessor {
    private static final String PKG = "net.gs64.gci.mapping";
    private static final String GS_CLASS = PKG + ".GsClass";
    private static final String OPTION = "gs64.mappings";

    private final Map<String, TypeElement> mapped = new LinkedHashMap<>();
    private boolean generated;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(GS_CLASS);
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of(OPTION);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        var gsClass = processingEnv.getElementUtils().getTypeElement(GS_CLASS);
        if (gsClass == null) return false;
        for (var e : round.getElementsAnnotatedWith(gsClass)) {
            if (e.getKind() != ElementKind.CLASS) {
                error(e, "@GsClass belongs on a class.");
                continue;
            }
            var t = (TypeElement) e;
            if (!t.getTypeParameters().isEmpty()) {
                error(t, "A mapped class must not be generic.");
                continue;
            }
            if (t.getNestingKind() == NestingKind.LOCAL || t.getNestingKind() == NestingKind.ANONYMOUS) {
                error(t, "A mapped class must not be local or anonymous.");
                continue;
            }
            if (!isNameable(t)) {
                error(t, "A mapped class must not be private (or nested in a private class): the generated code cannot name it.");
                continue;
            }
            if (t.getNestingKind() == NestingKind.MEMBER && !t.getModifiers().contains(Modifier.STATIC)) {
                error(t, "A nested mapped class must be static.");
                continue;
            }
            mapped.put(t.getQualifiedName().toString(), t);
        }
        if (!generated && !mapped.isEmpty() && !round.processingOver()) {
            generated = true;
            try {
                generate();
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Cannot write the mapping code: " + e.getMessage());
            }
        }
        return false; // other processors may look at @GsClass too
    }

    // =========================================================================================
    // Generation
    // =========================================================================================

    private void generate() throws IOException {
        var helpers = new ArrayList<String>();
        for (var t : mapped.values()) helpers.add(generateHelper(t));
        var registry = registryName();
        var dot = registry.lastIndexOf('.');
        var pkg = dot < 0 ? "" : registry.substring(0, dot);
        var simple = registry.substring(dot + 1);
        var sb = new StringBuilder();
        if (!pkg.isEmpty()) sb.append("package ").append(pkg).append(";\n\n");
        sb.append("""
                /** Mapping metadata of the {@code @GsClass} types of this compilation, generated by GsMappingProcessor. */
                @javax.annotation.processing.Generated("net.gs64.gci.processor.GsMappingProcessor")
                @SuppressWarnings({"unchecked", "rawtypes"})
                public final class %1$s implements net.gs64.gci.mapping.GsMappingProvider {
                    /** The metadata of all mapped classes; pass it to {@code new GsObjectContext(session, %1$s.DEFAULT)}. */
                    public static final %1$s DEFAULT = new %1$s();

                    private final java.util.Map<Class<?>, java.util.function.Supplier<net.gs64.gci.mapping.GsTypeMetadata>> classes = new java.util.HashMap<>();
                    private final java.util.Map<Class<?>, java.util.function.IntFunction<?>> factories = new java.util.HashMap<>();
                    private final java.util.Map<Class<?>, java.util.List<Class<?>>> polymorphic = new java.util.HashMap<>();
                    private final java.util.Map<Class<?>, net.gs64.gci.mapping.GsTypeMetadata> cache = new java.util.concurrent.ConcurrentHashMap<>();

                    private %1$s() {
                """.formatted(simple));
        for (var h : helpers) sb.append("        ").append(h).append(".register(classes, factories, polymorphic);\n");
        sb.append("""
                    }

                    @Override
                    public net.gs64.gci.mapping.GsTypeMetadata typeMetadata(java.lang.reflect.Type type) {
                        var collection = net.gs64.gci.mapping.GsCollectionMetadata.forType(type, factories::get);
                        if (collection != null) return collection;
                        var raw = net.gs64.gci.mapping.GsTypes.raw(type);
                        var meta = classes.get(raw);
                        if (meta != null) return cache.computeIfAbsent(raw, k -> meta.get());
                        var subtypes = polymorphic.get(raw);
                        return subtypes == null ? null : net.gs64.gci.mapping.GsTypeMetadata.polymorphic(type, subtypes);
                    }
                }
                """);
        try (Writer w = processingEnv.getFiler().createSourceFile(registry, mapped.values().toArray(Element[]::new)).openWriter()) {
            w.write(sb.toString());
        }
    }

    /** The provider's name: the option, else GsGeneratedMappings in the longest common package. */
    private String registryName() {
        var option = processingEnv.getOptions().get(OPTION);
        if (option != null && !option.isBlank()) return option.trim();
        String common = null;
        for (var t : mapped.values()) {
            var p = packageOf(t);
            if (common == null) common = p;
            else {
                while (!(p.equals(common) || p.startsWith(common + "."))) {
                    var dot = common.lastIndexOf('.');
                    common = dot < 0 ? "" : common.substring(0, dot);
                    if (common.isEmpty()) break;
                }
            }
        }
        return (common == null || common.isEmpty() ? "" : common + ".") + "GsGeneratedMappings";
    }

    /** One helper per mapped class, in its package. Answers the helper's qualified name. */
    private String generateHelper(TypeElement type) throws IOException {
        var pkg = packageOf(type);
        var simple = flatName(type) + "_GsMapping";
        var vh = new ArrayList<String>(); // VarHandle declarations
        var problems = new ArrayList<String>();
        var props = new ArrayList<String>();
        String setOop = "null";
        var reachable = new LinkedHashSet<TypeMirror>();

        for (var f : fieldsOf(type)) {
            if (annotation(f, "GsIgnore") != null) continue;
            var oopAnn = annotation(f, "GsOop");
            var iv = annotation(f, "GsInstVar");
            if (oopAnn == null && iv == null) continue;
            var decl = (TypeElement) f.getEnclosingElement();
            var where = decl.getSimpleName() + "." + f.getSimpleName();
            if (f.getModifiers().contains(Modifier.FINAL)) {
                error(f, where + ": a mapped field must not be final.");
                continue;
            }
            var direct = isAccessible(f, pkg) && isAccessible(decl, pkg) && isNameable(f.asType(), pkg);
            String get, set;
            if (direct) {
                var cast = castType(f.asType());
                get = "o -> ((" + canonical(decl) + ") o)." + f.getSimpleName();
                set = "(o, v) -> ((" + canonical(decl) + ") o)." + f.getSimpleName() + " = (" + cast + ") v";
            } else {
                var name = "F" + vh.size();
                vh.add("    private static final java.lang.invoke.VarHandle " + name + " = net.gs64.gci.mapping.GsGeneratedSupport.field("
                        + classExpr(decl, pkg, simple) + ", \"" + f.getSimpleName() + "\", " + rawClassExpr(f.asType(), pkg, simple) + ");");
                get = "o -> (Object) " + name + ".get(o)";
                set = "(o, v) -> " + name + ".set(o, v)";
            }
            if (oopAnn != null) {
                if (!f.asType().toString().equals("net.gs64.gci.Oop")) {
                    error(f, where + ": @GsOop requires type Oop.");
                    continue;
                }
                setOop = set.replace("(o, v)", "(o, oop)").replace(" v", " oop").replace(", v)", ", oop)");
                continue;
            }
            var kind = annotation(f, "GsCollection");
            var key = annotation(f, "GsDictionaryKey");
            var conv = annotation(f, "GsConverter");
            String converter = "null";
            if (conv != null) {
                var convType = (DeclaredType) ((TypeMirror) value(conv, "value").getValue());
                var convElement = (TypeElement) convType.asElement();
                if (isNameable(convElement) && hasAccessibleNoArgConstructor(convElement, pkg)) converter = "new " + canonical(convElement) + "()";
                else problems.add(where + ": converter " + convElement.getSimpleName() + " needs a public parameterless constructor.");
            }
            collectReachable(f.asType(), reachable);
            props.add("net.gs64.gci.mapping.GsGeneratedSupport.property(" + quote(decl.getSimpleName().toString()) + ", "
                    + quote(f.getSimpleName().toString()) + ", " + typeExpr(f.asType(), pkg, simple) + ", "
                    + quote((String) value(iv, "value").getValue()) + ", " + get + ", " + set + ", "
                    + (kind == null ? "null" : "net.gs64.gci.mapping.GsCollectionKind." + ((VariableElement) value(kind, "value").getValue()).getSimpleName())
                    + ", " + (annotation(f, "GsReadOnly") != null) + ", " + converter + ", "
                    + (key == null ? "null" : quote((String) value(key, "value").getValue())) + ")");
        }

        // Constructor
        String create;
        if (type.getModifiers().contains(Modifier.ABSTRACT)) create = "null";
        else if (!hasNoArgConstructor(type)) {
            create = "null";
            problems.add(type.getSimpleName() + ": needs a parameterless constructor.");
        } else if (hasAccessibleNoArgConstructor(type, pkg)) create = "() -> new " + canonical(type) + "()";
        else create = "(java.util.function.Supplier) net.gs64.gci.mapping.GsGeneratedSupport.constructor(" + classExpr(type, pkg, simple) + ")";

        var gs = annotation(type, "GsClass");
        var dictionary = value(gs, "dictionary");
        var sb = new StringBuilder();
        if (!pkg.isEmpty()) sb.append("package ").append(pkg).append(";\n\n");
        sb.append("/** Mapping metadata of {@code ").append(type.getQualifiedName()).append("}, generated by GsMappingProcessor. */\n");
        sb.append("@javax.annotation.processing.Generated(\"net.gs64.gci.processor.GsMappingProcessor\")\n");
        sb.append("@SuppressWarnings({\"unchecked\", \"rawtypes\", \"cast\"})\n");
        sb.append("public final class ").append(simple).append(" {\n");
        for (var v : vh) sb.append(v).append('\n');
        sb.append("\n    private ").append(simple).append("() {\n    }\n\n");
        sb.append("    /** Registers the class, the own collection types and the polymorphic types its fields reach. */\n");
        sb.append("    public static void register(java.util.Map<Class<?>, java.util.function.Supplier<net.gs64.gci.mapping.GsTypeMetadata>> classes,\n");
        sb.append("            java.util.Map<Class<?>, java.util.function.IntFunction<?>> factories, java.util.Map<Class<?>, java.util.List<Class<?>>> polymorphic) {\n");
        sb.append("        classes.put(").append(classExpr(type, pkg, simple)).append(", ").append(simple).append("::metadata);\n");
        for (var r : reachable) {
            var e = (TypeElement) ((DeclaredType) r).asElement();
            if (isOwnCollection(e) && isNameable(e) && hasAccessibleNoArgConstructor(e, pkg))
                sb.append("        factories.put(").append(canonical(e)).append(".class, n -> new ").append(canonical(e)).append("());\n");
            var subs = polymorphicSubtypes(e);
            if (subs != null)
                sb.append("        polymorphic.put(").append(classExpr(e, pkg, simple)).append(", java.util.List.of(")
                        .append(String.join(", ", subs.stream().map(s -> classExpr(s, pkg, simple)).toList())).append("));\n");
        }
        sb.append("    }\n\n");
        sb.append("    static net.gs64.gci.mapping.GsTypeMetadata metadata() {\n");
        sb.append("        return net.gs64.gci.mapping.GsTypeMetadata.mappedClass((Class) ").append(classExpr(type, pkg, simple)).append(", ")
                .append(quote((String) value(gs, "value").getValue())).append(", ")
                .append(dictionary == null ? "null" : quote((String) dictionary.getValue())).append(", ")
                .append(create).append(",\n            java.util.List.of(");
        for (var i = 0; i < props.size(); i++) sb.append(i == 0 ? "\n                " : ",\n                ").append(props.get(i));
        sb.append("),\n            java.util.List.of(").append(String.join(", ", subtypesOf(type).stream().map(s -> classExpr(s, pkg, simple)).toList()))
                .append("),\n            ").append(setOop).append(",\n            java.util.List.of(")
                .append(String.join(", ", problems.stream().map(GsMappingProcessor::quote).toList())).append("));\n    }\n}\n");
        var qualified = pkg.isEmpty() ? simple : pkg + "." + simple;
        try (Writer w = processingEnv.getFiler().createSourceFile(qualified, type).openWriter()) {
            w.write(sb.toString());
        }
        return qualified;
    }

    // =========================================================================================
    // Model helpers
    // =========================================================================================

    /** Instance fields including inherited ones, base class first; a field hidden by a subclass is skipped. */
    private List<VariableElement> fieldsOf(TypeElement type) {
        var chain = new ArrayList<TypeElement>();
        for (var t = type; t != null; t = superclass(t)) chain.add(0, t);
        var seen = new HashSet<String>();
        var byLevel = new ArrayList<List<VariableElement>>();
        for (var i = chain.size() - 1; i >= 0; i--) {
            var level = new ArrayList<VariableElement>();
            for (var f : ElementFilter.fieldsIn(chain.get(i).getEnclosedElements()))
                if (!f.getModifiers().contains(Modifier.STATIC) && seen.add(f.getSimpleName().toString())) level.add(f);
            byLevel.add(0, level);
        }
        var result = new ArrayList<VariableElement>();
        for (var level : byLevel) result.addAll(level);
        return result;
    }

    private TypeElement superclass(TypeElement t) {
        var s = t.getSuperclass();
        if (s.getKind() != TypeKind.DECLARED) return null;
        var e = (TypeElement) ((DeclaredType) s).asElement();
        return e.getQualifiedName().contentEquals("java.lang.Object") ? null : e;
    }

    private int depth(TypeElement t) {
        var d = 0;
        for (var s = superclass(t); s != null; s = superclass(s)) d++;
        return d;
    }

    /** Mapped classes that extend or implement {@code type}, most derived first. */
    private List<TypeElement> subtypesOf(TypeElement type) {
        var types = processingEnv.getTypeUtils();
        var target = types.erasure(type.asType());
        var result = new ArrayList<TypeElement>();
        for (var m : mapped.values())
            if (m != type && types.isSubtype(types.erasure(m.asType()), target)) result.add(m);
        result.sort(Comparator.comparingInt((TypeElement t) -> depth(t)).reversed());
        return result;
    }

    /** Subtypes for an interface or unmapped class reached by a field, or null if it is none (or has none). */
    private List<TypeElement> polymorphicSubtypes(TypeElement e) {
        if (mapped.containsKey(e.getQualifiedName().toString())) return null;
        var name = e.getQualifiedName().toString();
        if (name.startsWith("java.") || name.startsWith("javax.")) return null;
        if (!isNameable(e)) return null;
        var subs = subtypesOf(e);
        return subs.isEmpty() ? null : subs;
    }

    private boolean isOwnCollection(TypeElement e) {
        var name = e.getQualifiedName().toString();
        if (name.startsWith("java.") || e.getModifiers().contains(Modifier.ABSTRACT) || e.getKind() != ElementKind.CLASS) return false;
        var types = processingEnv.getTypeUtils();
        var elements = processingEnv.getElementUtils();
        var erased = types.erasure(e.asType());
        return types.isSubtype(erased, types.erasure(elements.getTypeElement("java.util.Collection").asType()))
                || types.isSubtype(erased, types.erasure(elements.getTypeElement("java.util.Map").asType()));
    }

    /** Declared types inside a field type (itself, type arguments, array components). */
    private void collectReachable(TypeMirror t, Set<TypeMirror> into) {
        switch (t.getKind()) {
            case DECLARED -> {
                var d = (DeclaredType) t;
                into.add(processingEnv.getTypeUtils().erasure(d));
                for (var a : d.getTypeArguments()) collectReachable(a, into);
            }
            case ARRAY -> collectReachable(((ArrayType) t).getComponentType(), into);
            case WILDCARD -> {
                var w = (WildcardType) t;
                if (w.getExtendsBound() != null) collectReachable(w.getExtendsBound(), into);
            }
            default -> {
            }
        }
    }

    private boolean hasNoArgConstructor(TypeElement t) {
        return ElementFilter.constructorsIn(t.getEnclosedElements()).stream().anyMatch(c -> c.getParameters().isEmpty());
    }

    private boolean hasAccessibleNoArgConstructor(TypeElement t, String pkg) {
        for (ExecutableElement c : ElementFilter.constructorsIn(t.getEnclosedElements()))
            if (c.getParameters().isEmpty()) return isAccessible(c, pkg) && isAccessible(t, pkg);
        return false;
    }

    /** Whether code in {@code pkg} (outside the class) may use the element. */
    private boolean isAccessible(Element e, String pkg) {
        var m = e.getModifiers();
        if (m.contains(Modifier.PRIVATE)) return false;
        if (!m.contains(Modifier.PUBLIC) && !packageOf(e).equals(pkg)) return false;
        var enclosing = e.getEnclosingElement();
        return !(enclosing instanceof TypeElement t) || isAccessible(t, pkg);
    }

    /** A class the generated code can name at all (no private class on the way out). */
    private boolean isNameable(TypeElement t) {
        for (Element e = t; e instanceof TypeElement; e = e.getEnclosingElement())
            if (e.getModifiers().contains(Modifier.PRIVATE)) return false;
        return true;
    }

    /** Whether a type can be written in a cast in {@code pkg}. */
    private boolean isNameable(TypeMirror t, String pkg) {
        return switch (t.getKind()) {
            case DECLARED -> {
                var d = (DeclaredType) t;
                if (!isAccessible(d.asElement(), pkg)) yield false;
                yield d.getTypeArguments().stream().allMatch(a -> a.getKind() == TypeKind.WILDCARD || isNameable(a, pkg));
            }
            case ARRAY -> isNameable(((ArrayType) t).getComponentType(), pkg);
            case TYPEVAR -> false;
            default -> true;
        };
    }

    private static String packageOf(Element e) {
        while (!(e instanceof PackageElement)) e = e.getEnclosingElement();
        return ((PackageElement) e).getQualifiedName().toString();
    }

    /** {@code Outer_Inner} for nested classes. */
    private static String flatName(TypeElement t) {
        var parts = new ArrayList<String>();
        for (Element e = t; e instanceof TypeElement; e = e.getEnclosingElement()) parts.add(0, e.getSimpleName().toString());
        return String.join("_", parts);
    }

    private static String canonical(TypeElement t) {
        return t.getQualifiedName().toString();
    }

    private String binaryName(TypeElement t) {
        return processingEnv.getElementUtils().getBinaryName(t).toString();
    }

    /** {@code X.class}, or a lookup by name for classes the helper cannot name. */
    private String classExpr(TypeElement t, String pkg, String helper) {
        if (isAccessible(t, pkg)) return canonical(t) + ".class";
        return "net.gs64.gci.mapping.GsGeneratedSupport.classNamed(" + quote(binaryName(t)) + ", " + helper + ".class)";
    }

    /** The erasure of a type as a Class expression (VarHandle field types). */
    private String rawClassExpr(TypeMirror t, String pkg, String helper) {
        var erased = processingEnv.getTypeUtils().erasure(t);
        return switch (erased.getKind()) {
            case DECLARED -> classExpr((TypeElement) ((DeclaredType) erased).asElement(), pkg, helper);
            case ARRAY -> isNameable(erased, pkg) ? erased + ".class"
                    : "net.gs64.gci.mapping.GsTypes.raw(" + typeExpr(t, pkg, helper) + ")";
            default -> erased + ".class";
        };
    }

    /** A java.lang.reflect.Type expression for a field type. */
    private String typeExpr(TypeMirror t, String pkg, String helper) {
        return switch (t.getKind()) {
            case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> t + ".class";
            case DECLARED -> {
                var d = (DeclaredType) t;
                var raw = classExpr((TypeElement) d.asElement(), pkg, helper);
                if (d.getTypeArguments().isEmpty()) yield raw;
                yield "net.gs64.gci.mapping.GsTypes.parameterized(" + raw + ", "
                        + String.join(", ", d.getTypeArguments().stream().map(a -> typeExpr(a, pkg, helper)).toList()) + ")";
            }
            case ARRAY -> {
                var c = ((ArrayType) t).getComponentType();
                if (c.getKind().isPrimitive() || (c.getKind() == TypeKind.DECLARED && ((DeclaredType) c).getTypeArguments().isEmpty()
                        && isAccessible(((DeclaredType) c).asElement(), pkg)) || (c.getKind() == TypeKind.ARRAY && isNameable(c, pkg)))
                    yield processingEnv.getTypeUtils().erasure(t) + ".class";
                yield "net.gs64.gci.mapping.GsTypes.array(" + typeExpr(c, pkg, helper) + ")";
            }
            case WILDCARD -> {
                var w = (WildcardType) t;
                yield w.getExtendsBound() == null ? "Object.class" : typeExpr(w.getExtendsBound(), pkg, helper);
            }
            default -> "Object.class";
        };
    }

    /** The type to cast a value to before assigning it to a field (primitives boxed, erased). */
    private String castType(TypeMirror t) {
        var types = processingEnv.getTypeUtils();
        if (t.getKind().isPrimitive()) return types.boxedClass((javax.lang.model.type.PrimitiveType) t).getQualifiedName().toString();
        return types.erasure(t).toString();
    }

    private AnnotationMirror annotation(Element e, String simpleName) {
        for (var a : e.getAnnotationMirrors())
            if (((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(PKG + "." + simpleName)) return a;
        return null;
    }

    private static AnnotationValue value(AnnotationMirror a, String name) {
        for (var e : a.getElementValues().entrySet())
            if (e.getKey().getSimpleName().contentEquals(name)) return e.getValue();
        return null;
    }

    private static String quote(String s) {
        var sb = new StringBuilder("\"");
        for (var c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private void error(Element e, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e);
    }
}
