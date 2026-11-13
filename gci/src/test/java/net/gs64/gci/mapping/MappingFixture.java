package net.gs64.gci.mapping;

import net.gs64.gci.GciSession;
import net.gs64.gci.StoneEnv;

/** Defines the GemStone classes and data of the mapping tests in its own session; nothing is committed. */
final class MappingFixture implements AutoCloseable {
    final GciSession session;

    MappingFixture() {
        session = StoneEnv.login();
        session.execute("""
            | def addr1 addr2 a1 a2 a3 o1 o2 h big |
            def := [:sup :name :ivs | sup subclass: name instVarNames: ivs classVars: #() classInstVars: #()
                       poolDictionaries: #() inDictionary: UserGlobals].
            def value: Object value: 'GsMapTestAddress' value: #('street' 'city').
            def value: Object value: 'GsMapTestAccount' value: #('accName' 'amount' 'postalAddress' 'orderList' 'tags' 'state' 'opened' 'extra' 'unmapped').
            def value: (UserGlobals at: #GsMapTestAccount) value: 'GsMapTestSpecialAccount' value: #('bonus').
            def value: Object value: 'GsMapTestOrder' value: #('number' 'total' 'account' 'lines').
            def value: Object value: 'GsMapTestHolder' value: #('items' 'idSet' 'bag' 'symbols' 'code' 'big' 'when').
            def value: Object value: 'GsMapTestNested' value: #('rows' 'groups' 'grid').

            addr1 := (UserGlobals at: #GsMapTestAddress) new.
            addr1 instVarNamed: 'street' put: 'Hauptstraße 1'; instVarNamed: 'city' put: 'Köln'.
            addr2 := (UserGlobals at: #GsMapTestAddress) new.
            addr2 instVarNamed: 'street' put: 'Elm St'; instVarNamed: 'city' put: 'Portland'.

            a1 := (UserGlobals at: #GsMapTestAccount) new.
            a1 instVarNamed: 'accName' put: 'Alice'; instVarNamed: 'amount' put: 1234.56s2;
               instVarNamed: 'postalAddress' put: addr1; instVarNamed: 'orderList' put: OrderedCollection new;
               instVarNamed: 'tags' put: (Set withAll: #('vip' 'eu')); instVarNamed: 'state' put: #active;
               instVarNamed: 'opened' put: (Date newDay: 15 monthNumber: 3 year: 2021); instVarNamed: 'extra' put: 42;
               instVarNamed: 'unmapped' put: (Array new: 1000).
            a2 := (UserGlobals at: #GsMapTestAccount) new.
            a2 instVarNamed: 'accName' put: 'Bob'; instVarNamed: 'amount' put: 0s2;
               instVarNamed: 'postalAddress' put: addr1; instVarNamed: 'orderList' put: OrderedCollection new;
               instVarNamed: 'tags' put: Set new; instVarNamed: 'state' put: #frozen; instVarNamed: 'extra' put: 'text'.
            a3 := (UserGlobals at: #GsMapTestSpecialAccount) new.
            a3 instVarNamed: 'accName' put: 'Carol'; instVarNamed: 'amount' put: 99999999999999999999.99s2;
               instVarNamed: 'postalAddress' put: addr2; instVarNamed: 'orderList' put: OrderedCollection new;
               instVarNamed: 'tags' put: (Set with: 'gold'); instVarNamed: 'state' put: #closed;
               instVarNamed: 'bonus' put: 500.

            o1 := (UserGlobals at: #GsMapTestOrder) new.
            o1 instVarNamed: 'number' put: 1; instVarNamed: 'total' put: 19.5; instVarNamed: 'account' put: a1;
               instVarNamed: 'lines' put: (KeyValueDictionary new at: 'apple' put: 3; at: 'pear' put: 5; yourself).
            o2 := (UserGlobals at: #GsMapTestOrder) new.
            o2 instVarNamed: 'number' put: 2; instVarNamed: 'total' put: 7.25; instVarNamed: 'account' put: a1.
            (a1 instVarNamed: 'orderList') add: o1; add: o2.

            big := 2 raisedTo: 100.
            h := (UserGlobals at: #GsMapTestHolder) new.
            h instVarNamed: 'items' put: (Array with: 1 with: 'two' with: 3.5 with: nil);
              instVarNamed: 'idSet' put: (IdentitySet withAll: (1 to: 2000));
              instVarNamed: 'bag' put: (Bag new add: 'x'; add: 'x'; add: 'y'; yourself);
              instVarNamed: 'symbols' put: (SymbolDictionary new at: #home put: addr1; at: #work put: addr2; yourself);
              instVarNamed: 'code' put: #abc; instVarNamed: 'big' put: big;
              instVarNamed: 'when' put: (DateAndTime year: 2024 month: 2 day: 29 hour: 12 minute: 30 second: 0 offset: Duration zero).

            UserGlobals at: #GsMapTestAccounts put: (Array with: a1 with: a2 with: a3).
            UserGlobals at: #GsMapTestHolderObj put: h.
            UserGlobals at: #GsMapTestOrderNoLines put: o2.
            UserGlobals at: #GsMapTestMany put: ((1 to: 3000) collect: [:i | | a |
                a := (UserGlobals at: #GsMapTestAccount) new.
                a instVarNamed: 'accName' put: 'acc', i printString; instVarNamed: 'amount' put: (i asScaledDecimal: 2);
                  instVarNamed: 'orderList' put: OrderedCollection new; instVarNamed: 'tags' put: Set new;
                  instVarNamed: 'state' put: #active; instVarNamed: 'postalAddress' put: addr2.
                a]).
            true
            """);
    }

    @Override
    public void close() {
        session.abort(); // never committed: classes and data disappear
        session.close();
    }
}
