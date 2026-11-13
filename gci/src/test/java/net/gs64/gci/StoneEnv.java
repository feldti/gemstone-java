package net.gs64.gci;

/**
 * Connection settings for tests against a real stone. Set GS_TEST_STONE (plus optionally
 * GS_TEST_USER, GS_TEST_PASSWORD, GS_TEST_HOST_USER, GS_TEST_HOST_PASSWORD, GS_TEST_GEM_SERVICE)
 * and GEMSTONE (or GEMSTONE_GCI_LIBRARY) to enable.
 */
public final class StoneEnv {
    private StoneEnv() {
    }

    private static String env(String name, String fallback) {
        var v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    public static GciNetParameters net() {
        return new GciNetParameters(env("GS_TEST_STONE", ""), env("GS_TEST_HOST_USER", ""),
                env("GS_TEST_HOST_PASSWORD", ""), env("GS_TEST_GEM_SERVICE", "gemnetobject"));
    }

    public static String user() {
        return env("GS_TEST_USER", "DataCurator");
    }

    public static String password() {
        return env("GS_TEST_PASSWORD", "swordfish");
    }

    public static GciSession login() {
        return GciSession.login(net(), user(), password());
    }
}
