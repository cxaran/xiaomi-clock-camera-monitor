package org.x04g.home.config;

/** Public errors never contain credentials, endpoints, imported text or raw provider messages. */
public final class ConfigException extends Exception {
    private static final long serialVersionUID = 1L;
    public enum Code { INVALID_INPUT, LIMIT, NOT_FOUND, CONFLICT, STORAGE, CORRUPT,
        SECRET_UNAVAILABLE, IDENTITY_UNCONFIRMED, CANCELLED, DISCOVERY_FAILED }
    public final Code code;
    public ConfigException(Code code) { super(code.name()); this.code = code; }
}
