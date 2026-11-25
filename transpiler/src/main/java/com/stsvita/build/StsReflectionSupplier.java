package com.stsvita.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.teavm.classlib.ReflectionContext;
import org.teavm.classlib.ReflectionSupplier;

/**
 * Feeds TeaVM the field-reflection metadata the gson facade needs at
 * runtime. Discovered by CTarget via
 * ServiceLoader.load(ReflectionSupplier.class, toolClassLoader); the class
 * list comes from stsvita-reflect.txt, generated into work/gen/resources by
 * pipeline/typemanifest.py (format: one class per line,
 * "binary.class.Name field1 field2 ...").
 */
public class StsReflectionSupplier implements ReflectionSupplier {

    private final Map<String, Set<String>> fieldsByClass = new HashMap<>();

    public StsReflectionSupplier() {
        try (InputStream in = StsReflectionSupplier.class
                .getResourceAsStream("/stsvita-reflect.txt")) {
            if (in == null) {
                System.err.println("StsReflectionSupplier: stsvita-reflect.txt missing "
                        + "- run pipeline/typemanifest.py (gradle does this automatically)");
                return;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    continue;
                }
                Set<String> fields = new HashSet<>();
                for (int i = 1; i < parts.length; i++) {
                    fields.add(parts[i]);
                }
                fieldsByClass.put(parts[0], fields);
            }
        } catch (IOException e) {
            throw new RuntimeException("failed to read stsvita-reflect.txt", e);
        }
    }

    @Override
    public Collection<String> getAccessibleFields(ReflectionContext context, String className) {
        return fieldsByClass.getOrDefault(className, Collections.emptySet());
    }
}
