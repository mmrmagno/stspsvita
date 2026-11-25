package com.stsvita.build;

import org.teavm.model.AccessLevel;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodHolder;
import org.teavm.model.ValueType;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.emit.ValueEmitter;

/**
 * Injects the handful of JDK methods StS reaches that TeaVM's classlib
 * lacks. Registered via TeaVMTool.getTransformers() in BuildVita; runs over
 * every class as it enters the model, so we match by name.
 *
 * Shims (all justified by how the game actually uses them):
 *  - Class.getResource(String) -> null. Only reached from libGDX
 *    FileHandle.exists()'s Classpath branch, which our backend never takes
 *    at runtime (handles are pre-resolved to Absolute).
 *  - Locale.getDefault(Locale.Category) -> Locale.getDefault().
 *
 * Candidates for upstreaming as real implementations.
 */
public class ClasslibShims implements ClassHolderTransformer {

    /** Game classes get java.util.Scanner retargeted to WordScanner (see
     * that class): same call surface, none of the JDK locale/NIO baggage. */
    private static final String SCANNER = "java.util.Scanner";
    private static final String WORD_SCANNER = "com.stsvita.compat.WordScanner";

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        switch (cls.getName()) {
            case "java.lang.Class":
                addReturningNull(cls, context, "getResource",
                        ValueType.object("java.lang.String"),
                        ValueType.object("java.net.URL"));
                // TeaVM 0.15's C backend has no method-reflection intrinsics
                // (ClassReflectionInfoIntrinsic: fieldCount/annotation only);
                // the classlib bodies of these two crash codegen if anything
                // links them (libGDX ClassReflection does). No caller we keep
                // at runtime uses them - make them honestly empty.
                replaceBodyWithEmptyArray(cls, context, "getDeclaredMethods",
                        ValueType.object("java.lang.reflect.Method"));
                replaceBodyWithEmptyArray(cls, context, "getDeclaredConstructors",
                        ValueType.object("java.lang.reflect.Constructor"));
                // Class.newInstance() can't be codegen'd (no constructor
                // intrinsic), but StS's map generator relies on it
                // (RoomTypeAssigner instantiates room types from a Class). Route
                // it to NewInstanceRegistry, which creates each known type via a
                // direct constructor, instead of throwing.
                redirectNewInstance(cls, context);
                break;
            case "java.util.Locale":
                addLocaleGetDefault(cls, context);
                break;
            case "java.nio.charset.Charset":
                // TeaVM's Charset omits the JDK's toString()->name() override,
                // so String.valueOf(StandardCharsets.UTF_8) yields the Object
                // default. StS passes that to FileHandle.readString(charset),
                // which then does forName(garbage) -> IllegalCharsetNameException.
                addToStringReturningName(cls, context);
                break;
            // Same C-backend gap as getDeclaredMethods above: constructor/
            // method *invocation* intrinsics don't exist either. Replace the
            // bodies with a loud throw instead of a codegen crash.
            case "java.lang.reflect.Constructor":
                replaceBodyWithThrow(cls, context, "newInstance");
                break;
            case "java.lang.reflect.Method":
                replaceBodyWithThrow(cls, context, "invoke");
                break;
            default:
                if (cls.getName().startsWith("com.megacrit.")) {
                    retargetScanner(cls);
                }
        }
        if (cls.getName().startsWith("com.codedisaster.steamworks.")) {
            // Steam is never present on Vita, but StS constructs SteamUtils /
            // SteamInputHelper etc. unconditionally. Make the JNI natives
            // benign no-ops (return defaults) so those constructors run;
            // SteamAPI.isSteamRunning() is false, so the game takes its
            // no-Steam paths afterwards. (Idle-Steam behavior.)
            denativize(cls, context, true);
        } else if (cls.getName().startsWith("com.badlogic.gdx.")
                || cls.getName().equals("java.lang.ClassLoader")) {
            // Remaining gdx JNI natives (ETC1, etc.) not replaced by a real
            // backend shadow: throw loudly so a genuine gap surfaces rather
            // than mis-rendering. The build log lists any that remain.
            denativize(cls, context, false);
        }
    }

    private void denativize(ClassHolder cls, ClassHolderTransformerContext context,
                            boolean benign) {
        for (MethodHolder method : cls.getMethods()) {
            if (!method.getModifiers().contains(ElementModifier.NATIVE)
                    || method.getAnnotations().get("org.teavm.interop.Import") != null) {
                continue;
            }
            method.getModifiers().remove(ElementModifier.NATIVE);
            ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
            if (benign) {
                if (method.getResultType() == ValueType.VOID) {
                    pe.exit();
                } else {
                    pe.defaultValue(method.getResultType()).returnValue();
                }
            } else {
                pe.construct("java.lang.UnsupportedOperationException",
                        pe.constant("JNI native not yet ported (M3): "
                                + cls.getName() + "." + method.getName()))
                  .raise();
                System.out.println("[denativized] " + cls.getName() + "." + method.getName());
            }
        }
    }

    private void retargetScanner(ClassHolder cls) {
        for (MethodHolder method : cls.getMethods()) {
            if (method.getProgram() == null) {
                continue;
            }
            for (org.teavm.model.BasicBlock block : method.getProgram().getBasicBlocks()) {
                for (org.teavm.model.instructions.ConstructInstruction insn
                        : instructionsOf(block, org.teavm.model.instructions.ConstructInstruction.class)) {
                    if (SCANNER.equals(insn.getType())) {
                        insn.setType(WORD_SCANNER);
                    }
                }
                for (org.teavm.model.instructions.InvokeInstruction insn
                        : instructionsOf(block, org.teavm.model.instructions.InvokeInstruction.class)) {
                    if (SCANNER.equals(insn.getMethod().getClassName())) {
                        insn.setMethod(new org.teavm.model.MethodReference(
                                WORD_SCANNER, insn.getMethod().getDescriptor()));
                    }
                }
            }
        }
    }

    private static <T> java.util.List<T> instructionsOf(org.teavm.model.BasicBlock block, Class<T> type) {
        java.util.List<T> result = new java.util.ArrayList<>();
        for (org.teavm.model.Instruction insn : block) {
            if (type.isInstance(insn)) {
                result.add(type.cast(insn));
            }
        }
        return result;
    }

    /** Rewrites {@code Class.newInstance()} to {@code NewInstanceRegistry.create(this)}. */
    private void redirectNewInstance(ClassHolder cls, ClassHolderTransformerContext context) {
        for (MethodHolder method : cls.getMethods()) {
            if (!method.getName().equals("newInstance")) {
                continue;
            }
            ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
            ValueEmitter self = pe.var(0, ValueType.object("java.lang.Class"));
            pe.invoke("com.stsvita.backend.NewInstanceRegistry", "create",
                    ValueType.object("java.lang.Object"), self).returnValue();
        }
    }

    private void replaceBodyWithEmptyArray(ClassHolder cls, ClassHolderTransformerContext context,
                                           String name, ValueType elementType) {
        for (MethodHolder method : cls.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
            pe.constructArray(elementType, pe.constant(0)).returnValue();
        }
    }

    private void replaceBodyWithThrow(ClassHolder cls, ClassHolderTransformerContext context,
                                      String name) {
        for (MethodHolder method : cls.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
            pe.construct("java.lang.UnsupportedOperationException",
                    pe.constant(cls.getName() + "." + name
                            + " is not supported by the TeaVM C backend"))
              .raise();
        }
    }

    private void addToStringReturningName(ClassHolder cls, ClassHolderTransformerContext context) {
        MethodHolder method = new MethodHolder("toString",
                ValueType.object("java.lang.String"));
        if (cls.getMethod(method.getDescriptor()) != null) {
            return;
        }
        method.setLevel(AccessLevel.PUBLIC);
        ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
        ValueEmitter self = pe.var(0, ValueType.object(cls.getName()));
        self.invokeVirtual("name", ValueType.object("java.lang.String")).returnValue();
        cls.addMethod(method);
    }

    private void addReturningNull(ClassHolder cls, ClassHolderTransformerContext context,
                                  String name, ValueType arg, ValueType ret) {
        MethodHolder method = arg != null
                ? new MethodHolder(name, arg, ret)
                : new MethodHolder(name, ret);
        if (cls.getMethod(method.getDescriptor()) != null) {
            return;
        }
        method.setLevel(AccessLevel.PUBLIC);
        ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
        pe.constantNull(ret).returnValue();
        cls.addMethod(method);
    }

    private void addLocaleGetDefault(ClassHolder cls, ClassHolderTransformerContext context) {
        MethodHolder method = new MethodHolder("getDefault",
                ValueType.object("java.util.Locale$Category"),
                ValueType.object("java.util.Locale"));
        if (cls.getMethod(method.getDescriptor()) != null) {
            return;
        }
        method.setLevel(AccessLevel.PUBLIC);
        method.getModifiers().add(ElementModifier.STATIC);
        ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
        pe.invoke("java.util.Locale", "getDefault",
                ValueType.object("java.util.Locale")).returnValue();
        cls.addMethod(method);
    }

}
