package net.gs64.gci;

/**
 * {@code loginFlags} for {@code GciLoginEx} (values from {@code gci.ht}), combined with
 * {@code |}. {@code GCI_TS_CLIENT} (0x40) and {@code GCI_WINDOWS_CLIENT} (0x200) are set by the
 * GCI library itself and are deliberately not exposed.
 */
public final class GciLoginFlags {
    private GciLoginFlags() {
    }

    public static final int NONE = 0;
    /** GCI_LOGIN_PW_ENCRYPTED — password was encrypted with GciEncrypt. */
    public static final int PASSWORD_ENCRYPTED = 0x1;
    /** GCI_LOGIN_IS_SUBORDINATE — child of the current session (RPC only). */
    public static final int SUBORDINATE = 0x2;
    /** GCI_LOGIN_FULL_COMPRESSION_ENABLED. */
    public static final int FULL_COMPRESSION = 0x4;
    /** GCI_LOGIN_ERRS_USE_REF_SET — error OOPs go to the ReferencedSet instead of the PureExportSet. */
    public static final int ERRORS_USE_REFERENCED_SET = 0x8;
    /** GCI_LOGIN_QUIET — suppress the login banner the library prints to stdout (the default). */
    public static final int QUIET = 0x10;
    /** GCI_PASSWORDLESS_LOGIN — Kerberos / GSSAPI. */
    public static final int PASSWORDLESS_KERBEROS = 0x80;
    /** GCI_LOGIN_SOLO — no stone required. */
    public static final int SOLO = 0x100;
    /** GCI_ONETIME_PASSWORD. */
    public static final int ONE_TIME_PASSWORD = 0x400;
    /** GCI_JWT_PASSWORD — password is a JSON web token. */
    public static final int JWT_PASSWORD = 0x800;
    /** GCI_LOGIN_PW_FILE — password argument is a file name on the client. */
    public static final int PASSWORD_FROM_FILE = 0x1000;
    /** GCI_LOGIN_PW_ENVVAR — password argument names a client environment variable. */
    public static final int PASSWORD_FROM_ENVIRONMENT = 0x2000;
    /** GCI_LOGIN_DEBUG — write debug info to the gem log. */
    public static final int DEBUG = 0x4000;
}
