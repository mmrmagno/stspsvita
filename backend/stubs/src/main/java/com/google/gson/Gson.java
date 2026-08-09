package com.google.gson;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.google.gson.internal.ParamType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * gson facade over libGDX's JsonReader (which ships inside the game jar).
 * Real gson cannot run under TeaVM: its TypeAdapters registry drags in
 * java.sql/java.net/UUID/Currency and its binder needs generic reflection.
 *
 * The game's whole gson surface is: fromJson(String|Reader, Class),
 * fromJson(String, Type), toJson(Object), GsonBuilder.setPrettyPrinting().
 *
 * Field binding relies on TeaVM's supported reflection (getDeclaredFields /
 * newInstance) — target classes must be listed by the build-time
 * ReflectionSupplier (generated alongside the TypeToken manifest).
 * JSON numbers become Integer/Long/Float when bound into untyped Map/List
 * values, matching how the game consumes its prefs maps.
 */
public class Gson {

    /**
     * Instantiation registry: TeaVM's C backend has field reflection but no
     * constructor reflection (Class.newInstance codegen fails), so the
     * build-time-generated TypeManifest registers a `Class::new` factory for
     * every deserialization target instead.
     */
    public interface Factory {
        Object create();
    }

    private static final Map<String, Factory> FACTORIES = new HashMap<>();

    /** "declaringClass#fieldName" -> element class of a List/Map field. */
    private static final Map<String, Class<?>> FIELD_ELEMS = new HashMap<>();

    public static void stsvRegisterFactory(String className, Factory factory) {
        FACTORIES.put(className, factory);
    }

    public static void stsvRegisterFieldElem(String key, Class<?> elem) {
        FIELD_ELEMS.put(key, elem);
    }

    private final boolean pretty;

    public Gson() {
        this(false);
    }

    Gson(boolean pretty) {
        this.pretty = pretty;
    }

    /* ---------- deserialization ---------- */

    @SuppressWarnings("unchecked")
    public <T> T fromJson(String json, Class<T> classOfT) {
        if (json == null) {
            return null;
        }
        return (T) bind(parse(json), classOfT);
    }

    public <T> T fromJson(Reader reader, Class<T> classOfT) {
        return fromJson(readAll(reader), classOfT);
    }

    @SuppressWarnings("unchecked")
    public <T> T fromJson(String json, Type typeOfT) {
        if (json == null) {
            return null;
        }
        return (T) bindType(parse(json), typeOfT);
    }

    private static Object bindType(JsonValue v, Type type) {
        if (type instanceof Class) {
            return bind(v, (Class<?>) type);
        }
        if (type instanceof ParamType) {
            ParamType pt = (ParamType) type;
            if (Map.class.isAssignableFrom(pt.raw) && pt.args.length == 2) {
                return bindMap(v, pt.args[1]);
            }
            if (List.class.isAssignableFrom(pt.raw) && pt.args.length == 1) {
                return bindList(v, pt.args[0]);
            }
            return bind(v, pt.raw);
        }
        // Unresolved TypeToken (not in the manifest): JSON-native structures.
        // Erasure makes this valid for Map<String, primitive-ish> targets.
        return bindNative(v);
    }

    private static Object bindMap(JsonValue v, Type valueType) {
        if (v == null || v.isNull()) {
            return null;
        }
        Map<String, Object> map = new HashMap<>();
        for (JsonValue c = v.child; c != null; c = c.next) {
            map.put(c.name, bindType(c, valueType));
        }
        return map;
    }

    private static Object bindList(JsonValue v, Type elemType) {
        if (v == null || v.isNull()) {
            return null;
        }
        List<Object> list = new ArrayList<>();
        for (JsonValue c = v.child; c != null; c = c.next) {
            list.add(bindType(c, elemType));
        }
        return list;
    }

    private static JsonValue parse(String json) {
        try {
            return new JsonReader().parse(json);
        } catch (RuntimeException e) {
            throw new JsonSyntaxException("malformed JSON", e);
        }
    }

    private static String readAll(Reader reader) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(reader)) {
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) >= 0) {
                sb.append(buf, 0, n);
            }
        } catch (IOException e) {
            throw new JsonSyntaxException("failed to read JSON", e);
        }
        return sb.toString();
    }

    private static Object bind(JsonValue v, Class<?> target) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (target == String.class) {
            return v.asString();
        }
        if (target == Integer.class || target == int.class) {
            return v.asInt();
        }
        if (target == Long.class || target == long.class) {
            return v.asLong();
        }
        if (target == Float.class || target == float.class) {
            return v.asFloat();
        }
        if (target == Double.class || target == double.class) {
            return v.asDouble();
        }
        if (target == Boolean.class || target == boolean.class) {
            return v.asBoolean();
        }
        if (Map.class.isAssignableFrom(target)) {
            return bindNative(v);
        }
        if (List.class.isAssignableFrom(target)) {
            return bindNative(v);
        }
        if (target.isArray()) {
            return bindArray(v, target.getComponentType());
        }
        return bindObject(v, target);
    }

    /**
     * Array binding without java.lang.reflect.Array: on TeaVM's C backend
     * Array.set relies on per-array-type reflection metadata that isn't
     * generated, so it silently leaves nulls. String[] and primitive arrays
     * (all that localization / saves use) are built directly; anything else
     * falls back to the reflective path with a warning.
     */
    private static Object bindArray(JsonValue v, Class<?> elem) {
        int size = v.size;
        if (elem == String.class) {
            String[] arr = new String[size];
            int i = 0;
            for (JsonValue c = v.child; c != null; c = c.next) {
                arr[i++] = c.isNull() ? null : c.asString();
            }
            return arr;
        }
        if (elem == int.class) {
            int[] arr = new int[size];
            int i = 0;
            for (JsonValue c = v.child; c != null; c = c.next) {
                arr[i++] = c.asInt();
            }
            return arr;
        }
        if (elem == boolean.class) {
            boolean[] arr = new boolean[size];
            int i = 0;
            for (JsonValue c = v.child; c != null; c = c.next) {
                arr[i++] = c.asBoolean();
            }
            return arr;
        }
        if (elem == float.class) {
            float[] arr = new float[size];
            int i = 0;
            for (JsonValue c = v.child; c != null; c = c.next) {
                arr[i++] = c.asFloat();
            }
            return arr;
        }
        Object arr = java.lang.reflect.Array.newInstance(elem, size);
        int i = 0;
        for (JsonValue c = v.child; c != null; c = c.next) {
            java.lang.reflect.Array.set(arr, i++, bind(c, elem));
        }
        return arr;
    }

    private static Object bindObject(JsonValue v, Class<?> target) {
        Factory factory = FACTORIES.get(target.getName());
        if (factory == null) {
            throw new JsonSyntaxException("no factory registered for "
                    + target.getName() + " (missing from type manifest?)");
        }
        Object obj = factory.create();
        for (JsonValue c = v.child; c != null; c = c.next) {
            Field f = findField(target, c.name);
            if (f == null) {
                continue;
            }
            try {
                f.setAccessible(true);
                Class<?> elem = FIELD_ELEMS.get(
                        f.getDeclaringClass().getName() + "#" + c.name);
                Object value;
                if (elem != null && Map.class.isAssignableFrom(f.getType())) {
                    value = bindMap(c, elem);
                } else if (elem != null && List.class.isAssignableFrom(f.getType())) {
                    value = bindList(c, elem);
                } else {
                    value = bind(c, f.getType());
                }
                f.set(obj, value);
            } catch (Exception e) {
                throw new JsonSyntaxException(
                        "cannot set " + target.getName() + "." + c.name, e);
            }
        }
        return obj;
    }

    private static Field findField(Class<?> cls, String name) {
        // Deliberately exception-free: the getDeclaredField/NoSuchFieldException
        // form ran a setjmp/longjmp round-trip (plus an exception allocation)
        // per missing field per superclass level — the hottest exception path
        // in the port, and the site of a rare on-device crash (a null Class
        // reached getSuperclass after the longjmp;).
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    return f;
                }
            }
        }
        return null;
    }

    /** JSON-native binding: maps, lists, strings, integral/float numbers. */
    private static Object bindNative(JsonValue v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isObject()) {
            Map<String, Object> map = new HashMap<>();
            for (JsonValue c = v.child; c != null; c = c.next) {
                map.put(c.name, bindNative(c));
            }
            return map;
        }
        if (v.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonValue c = v.child; c != null; c = c.next) {
                list.add(bindNative(c));
            }
            return list;
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isLong()) {
            long l = v.asLong();
            return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (Object) (int) l : (Object) l;
        }
        return v.asFloat();
    }

    /* ---------- serialization ---------- */

    public String toJson(Object src) {
        long t0 = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        write(sb, src, pretty ? 0 : -1);
        // Freeze diagnostic: reflective serialization of the
        // whole save allocates heavily; slow passes stall the frame loop.
        long ms = (System.nanoTime() - t0) / 1000000L;
        if (ms >= 30) {
            System.out.println("[save] toJson "
                    + (src == null ? "null" : src.getClass().getName())
                    + " " + sb.length() + " chars in " + ms + "ms");
        }
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o, int indent) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            quote(sb, (String) o);
        } else if (o instanceof Number || o instanceof Boolean) {
            sb.append(o);
        } else if (o instanceof Character) {
            quote(sb, o.toString());
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                if (indent >= 0) sb.append(' ');
                write(sb, e.getValue(), bump(indent));
            }
            newline(sb, indent);
            sb.append('}');
        } else if (o instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object e : (Iterable<?>) o) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent + 1);
                write(sb, e, bump(indent));
            }
            newline(sb, indent);
            sb.append(']');
        } else if (o.getClass().isArray()) {
            sb.append('[');
            int len = java.lang.reflect.Array.getLength(o);
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                newline(sb, indent + 1);
                write(sb, java.lang.reflect.Array.get(o, i), bump(indent));
            }
            newline(sb, indent);
            sb.append(']');
        } else if (o instanceof Enum) {
            quote(sb, ((Enum<?>) o).name());
        } else {
            writeObject(sb, o, indent);
        }
    }

    private static void writeObject(StringBuilder sb, Object o, int indent) {
        sb.append('{');
        boolean first = true;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                    continue;
                }
                Object val;
                try {
                    f.setAccessible(true);
                    val = f.get(o);
                } catch (Exception e) {
                    throw new JsonSyntaxException(
                            "cannot read " + c.getName() + "." + f.getName()
                            + " (missing from reflection manifest?)", e);
                }
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent + 1);
                quote(sb, f.getName());
                sb.append(':');
                if (indent >= 0) sb.append(' ');
                write(sb, val, bump(indent));
            }
        }
        newline(sb, indent);
        sb.append('}');
    }

    private static int bump(int indent) {
        return indent < 0 ? -1 : indent + 1;
    }

    private static void newline(StringBuilder sb, int indent) {
        if (indent < 0) {
            return;
        }
        sb.append('\n');
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        sb.append('"');
    }
}
