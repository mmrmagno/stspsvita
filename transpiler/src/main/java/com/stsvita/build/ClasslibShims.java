package com.stsvita.build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.teavm.model.AccessLevel;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldHolder;
import org.teavm.model.Instruction;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.emit.ValueEmitter;
import org.teavm.model.instructions.BranchingCondition;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.FloatConstantInstruction;
import org.teavm.model.instructions.GetFieldInstruction;
import org.teavm.model.instructions.IntegerConstantInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;

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
 */
public class ClasslibShims implements ClassHolderTransformer {

    /** Game classes get java.util.Scanner retargeted to WordScanner (see
     * that class): same call surface, none of the JDK locale/NIO baggage. */
    private static final String SCANNER = "java.util.Scanner";
    private static final String WORD_SCANNER = "com.stsvita.compat.WordScanner";

    private static final String EFFECT = "com.megacrit.cardcrawl.vfx.AbstractGameEffect";
    private static final String DUNGEON = "com.megacrit.cardcrawl.dungeons.AbstractDungeon";
    private static final String SOUL = "com.megacrit.cardcrawl.cards.Soul";
    private static final String TRAIL = "com.megacrit.cardcrawl.vfx.CardTrailEffect";
    private static final String TRAIL_BUDGET = "com.stsvita.compat.TrailBudget";
    private static final Set<String> SUBSTEP_SKIP = new HashSet<>(Arrays.asList(
            "com.megacrit.cardcrawl.vfx.scene.InteractableTorchEffect",
            "com.megacrit.cardcrawl.vfx.scene.WobblyCircleEffect",
            "com.megacrit.cardcrawl.vfx.ShopSpeechBubble",
            TRAIL,
            "com.megacrit.cardcrawl.vfx.deprecated.AnimationTestEffect"));

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
                // at runtime uses them, so return empty arrays.
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
                if (substepped(cls, context)) {
                    substep(cls, context);
                }
                if (SOUL.equals(cls.getName())) {
                    budgetTrailPoints(cls);
                }
                if (TRAIL.equals(cls.getName())) {
                    weightTrailDots(cls, context);
                }
                if (DUNGEON.equals(cls.getName())) {
                    compactEffectLoops(cls);
                    collectAfterRoomTransition(cls, context);
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
            for (BasicBlock block : method.getProgram().getBasicBlocks()) {
                for (ConstructInstruction insn : instructionsOf(block, ConstructInstruction.class)) {
                    if (SCANNER.equals(insn.getType())) {
                        insn.setType(WORD_SCANNER);
                    }
                }
                for (InvokeInstruction insn : instructionsOf(block, InvokeInstruction.class)) {
                    if (SCANNER.equals(insn.getMethod().getClassName())) {
                        insn.setMethod(new MethodReference(
                                WORD_SCANNER, insn.getMethod().getDescriptor()));
                    }
                    retargetSplit(insn);
                }
            }
        }
    }

    private void budgetTrailPoints(ClassHolder cls) {
        MethodHolder move = cls.getMethod(new MethodDescriptor("updateMovement", ValueType.VOID));
        int ints = 0;
        int floats = 0;
        for (BasicBlock block : move.getProgram().getBasicBlocks()) {
            for (IntegerConstantInstruction insn : instructionsOf(block, IntegerConstantInstruction.class)) {
                if (insn.getConstant() == 20) {
                    replaceWithStatic(insn, insn.getReceiver(), "points", ValueType.INTEGER);
                    ints++;
                }
            }
            for (FloatConstantInstruction insn : instructionsOf(block, FloatConstantInstruction.class)) {
                if (insn.getConstant() == 19f) {
                    replaceWithStatic(insn, insn.getReceiver(), "span", ValueType.FLOAT);
                    floats++;
                }
            }
        }
        if (ints != 1 || floats != 1) {
            throw new IllegalStateException("Soul.updateMovement trail loop: " + ints + " / " + floats);
        }
    }

    private static void replaceWithStatic(Instruction old, Variable receiver, String name, ValueType type) {
        InvokeInstruction call = new InvokeInstruction();
        call.setType(InvocationType.SPECIAL);
        call.setMethod(new MethodReference(TRAIL_BUDGET, name, type));
        call.setReceiver(receiver);
        call.setLocation(old.getLocation());
        old.insertNext(call);
        old.delete();
    }

    private void weightTrailDots(ClassHolder cls, ClassHolderTransformerContext context) {
        FieldHolder field = new FieldHolder("stsvWeight");
        field.setType(ValueType.FLOAT);
        field.setLevel(AccessLevel.PRIVATE);
        cls.addField(field);
        ValueType colorType = ValueType.object("com.badlogic.gdx.graphics.Color");

        MethodHolder init = cls.getMethod(new MethodDescriptor("init",
                ValueType.FLOAT, ValueType.FLOAT, ValueType.VOID));
        ProgramEmitter pe = wrapBody(cls, init, "stsvBody$init", context);
        ValueEmitter self = pe.var(0, ValueType.object(cls.getName()));
        self.invokeSpecial("stsvBody$init", pe.var(1, ValueType.FLOAT), pe.var(2, ValueType.FLOAT));
        self.setField("stsvWeight", pe.invoke(TRAIL_BUDGET, "spawned", ValueType.FLOAT));
        pe.exit();

        MethodHolder update = cls.getMethod(new MethodDescriptor("update", ValueType.VOID));
        pe = wrapBody(cls, update, "stsvBody$update", context);
        self = pe.var(0, ValueType.object(cls.getName()));
        self.invokeSpecial("stsvBody$update");
        BasicBlock done = pe.prepareBlock();
        BasicBlock live = pe.prepareBlock();
        self.getField("isDone", ValueType.BOOLEAN)
                .fork(BranchingCondition.NOT_EQUAL).setThen(done).setElse(live);
        pe.enter(done);
        pe.invoke(TRAIL_BUDGET, "died", ValueType.VOID,
                self.getField("stsvWeight", ValueType.FLOAT));
        pe.exit();
        pe.enter(live);
        ValueEmitter color = self.getField("color", colorType);
        color.setField("a", pe.invoke(TRAIL_BUDGET, "alpha", ValueType.FLOAT,
                color.getField("a", ValueType.FLOAT), self.getField("stsvWeight", ValueType.FLOAT)));
        pe.exit();
    }

    private void collectAfterRoomTransition(ClassHolder cls, ClassHolderTransformerContext context) {
        MethodHolder transition = cls.getMethod(new MethodDescriptor("nextRoomTransition", ValueType.VOID));
        if (transition == null || transition.getProgram() == null) {
            throw new IllegalStateException("AbstractDungeon.nextRoomTransition() not found");
        }
        ProgramEmitter pe = wrapBody(cls, transition, "stsvBody$nextRoomTransition", context);
        pe.var(0, ValueType.object(cls.getName())).invokeSpecial("stsvBody$nextRoomTransition");
        pe.invoke("com.stsvita.compat.RoomGc", "collect", ValueType.VOID);
        pe.exit();
    }

    private void compactEffectLoops(ClassHolder cls) {
        MethodHolder update = cls.getMethod(new MethodDescriptor("update", ValueType.VOID));
        if (update == null || update.getProgram() == null) {
            return;
        }
        Program program = update.getProgram();
        Set<Variable> lists = new HashSet<>();
        for (BasicBlock block : program.getBasicBlocks()) {
            for (GetFieldInstruction insn : instructionsOf(block, GetFieldInstruction.class)) {
                String field = insn.getField().getFieldName();
                if (insn.getInstance() == null && DUNGEON.equals(insn.getField().getClassName())
                        && field.equals("topLevelEffects")) {
                    lists.add(insn.getReceiver());
                }
            }
        }
        int replaced = 0;
        for (BasicBlock block : program.getBasicBlocks()) {
            for (InvokeInstruction insn : instructionsOf(block, InvokeInstruction.class)) {
                MethodReference m = insn.getMethod();
                if (!"java.util.ArrayList".equals(m.getClassName()) || !"iterator".equals(m.getName())
                        || m.parameterCount() != 0 || !lists.contains(insn.getInstance())) {
                    continue;
                }
                insn.setArguments(insn.getInstance());
                insn.setInstance(null);
                insn.setType(InvocationType.SPECIAL);
                insn.setMethod(new MethodReference("com.stsvita.compat.CompactingIterator", "of",
                        ValueType.object("java.util.ArrayList"), ValueType.object("java.util.Iterator")));
                replaced++;
            }
        }
        if (replaced != 1) {
            throw new IllegalStateException("AbstractDungeon.update topLevelEffects loop: expected 1, got " + replaced);
        }
    }

    private static void retargetSplit(InvokeInstruction insn) {
        MethodReference m = insn.getMethod();
        if (!"java.lang.String".equals(m.getClassName()) || !"split".equals(m.getName())
                || insn.getInstance() == null) {
            return;
        }
        List<Variable> args = new ArrayList<>();
        args.add(insn.getInstance());
        args.addAll(insn.getArguments());
        ValueType[] sig = new ValueType[m.parameterCount() + 2];
        sig[0] = ValueType.object("java.lang.String");
        for (int i = 0; i < m.parameterCount(); i++) {
            sig[i + 1] = m.parameterType(i);
        }
        sig[sig.length - 1] = m.getReturnType();
        insn.setInstance(null);
        insn.setType(InvocationType.SPECIAL);
        insn.setArguments(args.toArray(new Variable[0]));
        insn.setMethod(new MethodReference("com.stsvita.compat.FastSplit", "split", sig));
    }

    private static boolean substepped(ClassHolder cls, ClassHolderTransformerContext context) {
        String name = cls.getName();
        if (SOUL.equals(name)) {
            return true;
        }
        if (SUBSTEP_SKIP.contains(name) || name.startsWith("com.megacrit.cardcrawl.vfx.campfire.")) {
            return false;
        }
        MethodHolder update = cls.getMethod(new MethodDescriptor("update", ValueType.VOID));
        return update != null && update.getProgram() != null
                && context.getHierarchy().isSuperType(EFFECT, name, false);
    }

    private void substep(ClassHolder cls, ClassHolderTransformerContext context) {
        MethodHolder update = cls.getMethod(new MethodDescriptor("update", ValueType.VOID));
        if (update == null || update.getProgram() == null) {
            throw new IllegalStateException("substep: no update() in " + cls.getName());
        }
        String bodyName = "stsvBody$" + cls.getName().replace('.', '_').replace('$', '_');
        ProgramEmitter pe = wrapBody(cls, update, bodyName, context);
        ValueEmitter self = pe.var(0, ValueType.object(cls.getName()));
        BasicBlock loop = pe.prepareBlock();
        BasicBlock once = pe.prepareBlock();
        BasicBlock out = pe.prepareBlock();
        pe.invoke("com.stsvita.compat.Substep", "begin", ValueType.BOOLEAN)
                .fork(BranchingCondition.NOT_EQUAL).setThen(loop).setElse(once);
        pe.enter(once);
        self.invokeSpecial(bodyName);
        pe.exit();
        pe.enter(loop);
        self.invokeSpecial(bodyName);
        pe.invoke("com.stsvita.compat.Substep", "again", ValueType.BOOLEAN,
                self.getField("isDone", ValueType.BOOLEAN))
                .fork(BranchingCondition.NOT_EQUAL).setThen(loop).setElse(out);
        pe.enter(out);
        pe.exit();
    }

    private static ProgramEmitter wrapBody(ClassHolder cls, MethodHolder method, String bodyName,
                                           ClassHolderTransformerContext context) {
        MethodHolder body = new MethodHolder(bodyName, method.getSignature());
        body.setLevel(AccessLevel.PRIVATE);
        body.setProgram(method.getProgram());
        cls.addMethod(body);
        return ProgramEmitter.create(method, context.getHierarchy());
    }

    private static <T> List<T> instructionsOf(BasicBlock block, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Instruction insn : block) {
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
