package net.gs64.gci.mapping;

import java.util.ArrayList;
import java.util.List;

import net.gs64.gci.Oop;

/**
 * The server models associations with database-oriented collections (RC collections,
 * dictionaries, queues); the Java side only has lists.
 */
final class ServerModel {
    private ServerModel() {
    }

    @GsClass("GsSrvTestThing")
    static final class Thing {
        @GsInstVar("name") String name;

        Thing() {
        }

        Thing(String name) {
            this.name = name;
        }
    }

    /** Every association as a plain list. */
    @GsClass("GsSrvTestHolder")
    static final class Holder {
        @GsInstVar("items") List<Thing> items = new ArrayList<>();
        @GsInstVar("byName") @GsDictionaryKey("name") List<Thing> byName = new ArrayList<>();
        @GsInstVar("queue") List<Thing> queue = new ArrayList<>();
    }

    /** The same class, but the dictionary without a key selector: loadable, not changeable. */
    @GsClass("GsSrvTestHolder")
    static final class HolderWithoutKey {
        @GsInstVar("byName") List<Thing> byName = new ArrayList<>();
    }

    /** A class without initialize: new objects get collections chosen by the Java side. */
    @GsClass("GsSrvTestPlain")
    static final class Plain {
        @GsInstVar("items") List<Thing> items = new ArrayList<>();
        @GsInstVar("byName") @GsDictionaryKey("name") List<Thing> byName = new ArrayList<>();
    }

    // Like generated domain classes: the mapping sits on protected fields, the OOP in a base
    // class, associations are an own list type.

    static class OwnList<T> extends ArrayList<T> {
        private static final long serialVersionUID = 1L;
    }

    abstract static class ModelBase {
        @GsOop protected Oop oop;

        Oop oop() {
            return oop;
        }
    }

    @GsClass("GsSrvTestThing")
    static class ModelThing extends ModelBase {
        @GsInstVar("name") protected String name;

        ModelThing() {
        }

        ModelThing(String name) {
            this.name = name;
        }
    }

    @GsClass("GsSrvTestHolder")
    static class ModelHolder extends ModelBase {
        @GsInstVar("items") protected OwnList<ModelThing> items = new OwnList<>();
        @GsInstVar("byName") @GsDictionaryKey("name") protected OwnList<ModelThing> byName = new OwnList<>();
    }

    // Elements typed by an interface (or an unmapped base class): each object becomes the mapped
    // class that matches its GemStone class. A sealed hierarchy: the subclasses are found without
    // registering them.

    sealed interface Shape permits ShapeBase {
        String name();

        void name(String value);
    }

    abstract static sealed class ShapeBase extends ModelBase implements Shape permits Circle, Square {
        @GsInstVar("name") protected String name;

        @Override
        public String name() {
            return name;
        }

        @Override
        public void name(String value) {
            name = value;
        }
    }

    @GsClass("GsSrvTestCircle")
    static final class Circle extends ShapeBase {
        Circle() {
        }

        Circle(String name) {
            this.name = name;
        }
    }

    @GsClass("GsSrvTestSquare")
    static sealed class Square extends ShapeBase permits BigSquare {
        Square() {
        }

        Square(String name) {
            this.name = name;
        }
    }

    /** A GemStone subclass of GsSrvTestSquare with its own Java class. */
    @GsClass("GsSrvTestBigSquare")
    static final class BigSquare extends Square {
        BigSquare() {
        }

        BigSquare(String name) {
            super(name);
        }
    }

    @GsClass("GsSrvTestDrawing")
    static final class Drawing extends ModelBase {
        @GsInstVar("shapes") protected OwnList<Shape> shapes = new OwnList<>();
        @GsInstVar("main") protected Shape main;
        @GsInstVar("first") protected ShapeBase first;
    }
}
