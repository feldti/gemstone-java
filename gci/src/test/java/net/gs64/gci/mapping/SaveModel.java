package net.gs64.gci.mapping;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.gs64.gci.Oop;

/** Extra mappings for the save tests (on the classes of {@link MappingFixture}). */
final class SaveModel {
    private SaveModel() {
    }

    @GsClass("GsMapTestAccount")
    static final class AccountWithReadOnlyName {
        @GsInstVar("accName") @GsReadOnly String name;
        @GsInstVar("amount") BigDecimal balance;
    }

    @GsClass("GsMapTestAccount")
    static final class AccountWithOrderRefs {
        @GsInstVar("orderList") @GsCollection(GsCollectionKind.ORDERED) List<OrderWithAccountOop> orders = new ArrayList<>();
    }

    @GsClass("GsMapTestOrder")
    static final class OrderWithAccountOop {
        @GsInstVar("number") int number;
        @GsInstVar("account") Oop account;
        @GsInstVar("lines") Object lines;
    }

    /** Values the OOP itself holds (SmallInteger, SmallDouble, an Oop): their snapshots keep no box. */
    @GsClass("GsMapTestOrder")
    static final class OrderWithSpecialValues {
        @GsInstVar("number") long number;
        @GsInstVar("total") double total;
        @GsInstVar("account") Oop account;
    }

    @GsClass("GsMapTestAccount")
    static final class AccountWithDateTime {
        @GsInstVar("extra") LocalDateTime extra; // nil in a fresh account
    }

    @GsClass("GsMapTestNested")
    static final class NestedCollections {
        @GsInstVar("rows") List<List<Long>> rows;
        @GsInstVar("groups") @GsCollection(GsCollectionKind.DICTIONARY) Map<String, List<String>> groups;
        @GsInstVar("grid") Long[][] grid;
    }
}
