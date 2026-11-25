package com.google.gson.internal;

import java.lang.reflect.Type;

/**
 * Minimal parameterized-type value for the gson facade. We avoid
 * java.lang.reflect.ParameterizedType - its surface drags in more of
 * java.lang.reflect than TeaVM's C backend provides; the facade only needs
 * raw + args, and the build-time TypeManifest constructs these directly.
 */
public final class ParamType implements Type {

    public final Class<?> raw;
    public final Type[] args;

    private ParamType(Class<?> raw, Type[] args) {
        this.raw = raw;
        this.args = args;
    }

    public static ParamType of(Class<?> raw, Type... args) {
        return new ParamType(raw, args);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(raw.getName()).append('<');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(args[i]);
        }
        return sb.append('>').toString();
    }
}
