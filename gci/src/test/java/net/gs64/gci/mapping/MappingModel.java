package net.gs64.gci.mapping;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.gs64.gci.Oop;

/** Java classes as a code generator would emit them (instVar names differ on purpose). */
final class MappingModel {
    private MappingModel() {
    }

    enum AccountStatus { ACTIVE, CLOSED, FROZEN }

    @GsClass("GsMapTestAddress")
    static final class Address {
        @GsInstVar("street") String street;
        @GsInstVar("city") String city;
    }

    @GsClass("GsMapTestAccount")
    @GsSubtypes(SpecialAccount.class)
    static class Account {
        @GsOop Oop oop;
        @GsInstVar("accName") String name;
        @GsInstVar("amount") BigDecimal balance;
        @GsInstVar("postalAddress") Address address;
        @GsInstVar("orderList") @GsCollection(GsCollectionKind.ORDERED) List<Order> orders = new ArrayList<>();
        @GsInstVar("tags") @GsCollection(GsCollectionKind.SET) Set<String> tags = new LinkedHashSet<>();
        @GsInstVar("state") AccountStatus status;
        @GsInstVar("opened") LocalDate opened;
        @GsInstVar("extra") Object extra;
        String displayName; // not mapped
    }

    @GsClass("GsMapTestSpecialAccount")
    static final class SpecialAccount extends Account {
        @GsInstVar("bonus") long bonus;
    }

    @GsClass("GsMapTestOrder")
    static final class Order {
        @GsInstVar("number") int number;
        @GsInstVar("total") double total;
        @GsInstVar("account") Account account;
        @GsInstVar("lines") @GsCollection(GsCollectionKind.DICTIONARY) Map<String, Long> lines;
    }

    // ---- Deliberately wrong mappings ----

    @GsClass("GsMapTestAddress")
    static final class AddressWithTypo {
        @GsInstVar("stret") String street;
        @GsInstVar("zip") String zip;
    }

    @GsClass("GsMapTestNoSuchClass")
    static final class Phantom {
        @GsInstVar("x") int x;
    }

    @GsClass("GsMapTestOrder")
    static final class OrderWithWrongKind {
        @GsInstVar("lines") @GsCollection(GsCollectionKind.ARRAY) List<Long> lines;
    }

    @GsClass("GsMapTestOrder")
    static final class OrderWithAddressAsAccount {
        @GsInstVar("account") Address account;
    }

    @GsClass(value = "GsMapTestHolder", dictionary = "UserGlobals")
    static final class Holder {
        @GsInstVar("items") List<Object> items;
        @GsInstVar("idSet") Set<Long> idSet;
        @GsInstVar("bag") List<String> bag;
        @GsInstVar("symbols") Map<String, Address> bySymbol;
        @GsInstVar("code") @GsConverter(UpperCaseConverter.class) String code;
        @GsInstVar("big") BigInteger big;
        @GsInstVar("when") OffsetDateTime when;
    }

    public static final class UpperCaseConverter implements GsValueConverter {
        @Override
        public Object fromGemStone(Object value, Type targetType) {
            return value instanceof String s ? s.toUpperCase(Locale.ROOT) : null;
        }
    }
}
