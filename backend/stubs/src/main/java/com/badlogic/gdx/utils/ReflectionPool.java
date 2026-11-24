package com.badlogic.gdx.utils;

import com.badlogic.gdx.Net;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.math.Rectangle;
import java.util.HashMap;
import java.util.Map;

/**
 * Shadow of libGDX's ReflectionPool: the original instantiates pooled objects
 * via ClassReflection.getConstructor().newInstance(), which TeaVM's C backend
 * cannot do (no constructor reflection). We look the type up in a factory
 * registry instead. All classes libGDX pools in 1.9.5 (found by scanning the
 * jar for Pools.get/obtain and new ReflectionPool) are registered below; the
 * ones with non-public constructors (scene2d internals StS never pools) are
 * omitted and would surface as a clear error rather than a silent failure.
 */
public class ReflectionPool<T> extends Pool<T> {

    private interface Factory {
        Object create();
    }

    private static final Map<String, Factory> FACTORIES = new HashMap<>();

    static {
        FACTORIES.put("com.badlogic.gdx.graphics.g2d.GlyphLayout", GlyphLayout::new);
        FACTORIES.put("com.badlogic.gdx.graphics.g2d.GlyphLayout$GlyphRun",
                GlyphLayout.GlyphRun::new);
        FACTORIES.put("com.badlogic.gdx.math.Rectangle", Rectangle::new);
        FACTORIES.put("com.badlogic.gdx.graphics.Color", Color::new);
        FACTORIES.put("com.badlogic.gdx.Net$HttpRequest", Net.HttpRequest::new);
    }

    private final Factory factory;
    private final String typeName;

    public ReflectionPool(Class<T> type) {
        this(type, 16, Integer.MAX_VALUE);
    }

    public ReflectionPool(Class<T> type, int initialCapacity) {
        this(type, initialCapacity, Integer.MAX_VALUE);
    }

    public ReflectionPool(Class<T> type, int initialCapacity, int max) {
        super(initialCapacity, max);
        this.typeName = type.getName();
        this.factory = FACTORIES.get(typeName);
        if (this.factory == null) {
            throw new RuntimeException(
                    "STSVITA: no pool factory registered for " + typeName
                    + " (add it to ReflectionPool's registry)");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected T newObject() {
        return (T) factory.create();
    }
}
