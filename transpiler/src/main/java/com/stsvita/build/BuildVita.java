package com.stsvita.build;

import java.io.File;
import org.teavm.tooling.TeaVMProblemRenderer;
import org.teavm.tooling.TeaVMTargetType;
import org.teavm.tooling.TeaVMTool;
import org.teavm.tooling.TeaVMToolLog;
import org.teavm.vm.TeaVMOptimizationLevel;

/**
 * Drives TeaVM's C backend: user's desktop-1.0.jar (+ our backend and stubs,
 * already on the classpath) -> portable C11 in work/generated_c/.
 *
 * Classpath shadowing does the surgery: stub classes (steamworks4j, discord,
 * twitch, JNA) come first, then the Vita backend, then the user's jar, so the
 * desktop-only code inside the jar is never reachable and gets tree-shaken.
 *
 * Milestone M2. The TeaVM tooling API calls below are written against 0.15.0
 * and will be exercised/corrected when the transpile milestone starts.
 */
public final class BuildVita {

    public static void main(String[] argv) throws Exception {
        if (argv.length != 2 || argv[0].isEmpty()) {
            System.err.println("usage: BuildVita <path/to/desktop-1.0.jar> <out dir>");
            System.exit(2);
        }
        File gameJar = new File(argv[0]);
        File outDir = new File(argv[1]);
        if (!gameJar.isFile()) {
            System.err.println("game jar not found: " + gameJar);
            System.exit(2);
        }
        outDir.mkdirs();

        TeaVMTool tool = new TeaVMTool();
        tool.setTargetType(TeaVMTargetType.C);
        tool.setMainClass("com.stsvita.VitaLauncher");
        tool.setTargetDirectory(outDir);
        tool.setObfuscated(false);
        tool.setOptimizationLevel(TeaVMOptimizationLevel.ADVANCED);
        // TeaVM's C heap is malloc-committed up front (TEAVM_PSP
        // virtualAlloc=malloc), so the max is reserved immediately and steals
        // it from native malloc. The desktop -Xmx256m test didn't count
        // texture memory (desktop GL keeps it in the driver); on Vita the
        // decoded pixmaps + stb decode buffers + FreeType share the newlib
        // heap. The old 192MB floor was measured WITH 16MB RGBA pixmap
        // transients inflating the working set; the M4.dds pipeline removed
        // those (peak live pixmaps ~9MB on device now), so the Java floor
        // dropped and we can hand the freed budget to native FreeType, which
        // OOM'd (FT error 64) with only ~88MB native under the 192MB split.
        // 144MB Java -> ~136MB native within the 280MB newlib heap.
        // min == max: TEAVM_PSP's virtualAlloc is a plain malloc of maxHeap at
        // boot (newlib has no mmap), so a smaller min saves NOTHING natively —
        // it only lets the GC's shrink policy park the logical heap at 64MB.
        // The 2026-07-12 device log showed exactly that (avail 65536KB all
        // session, free ~0.5MB before each young pause, one pause every couple
        // frames in combat). Pinning the heap at 144MB triples the young-GC
        // allocation runway for free.
        // 112MB: the 2026-07-16/17 device logs show live Java of
        // ~16MB after full GC plus ~9.5MB peak pixmap transients, so 112MB
        // still leaves >4x peak-live runway. The 32MB given back shrinks
        // newlib in lockstep (272->240, main.c) so the NATIVE budget is
        // unchanged at 128MB (FreeType FT-64 history) and vitaGL's RAM texture
        // pool grows 45->77MB. That pool headroom is what fixes the mid-run
        // texture drops (shop rug/merchantHand skip-drops at pool maxfree
        // <1MB, spill budget 6110/6144KB consumed at boot) and re-admits the
        // five half-data City layers after an Act 1 run.
        tool.setMinHeapSize(112 * 1024 * 1024);
        tool.setMaxHeapSize(112 * 1024 * 1024);
        tool.getTransformers().add(ClasslibShims.class.getName());

        // The game jar rides in via the classloader below; stubs/backend are
        // already on the JVM classpath of this build process.
        java.net.URLClassLoader cl = new java.net.URLClassLoader(
                new java.net.URL[]{gameJar.toURI().toURL()},
                BuildVita.class.getClassLoader());
        tool.setClassLoader(cl);

        tool.generate();
        TeaVMProblemRenderer.describeProblems(
                tool.getDependencyInfo().getCallGraph(), tool.getProblemProvider(), log());
        if (!tool.getProblemProvider().getSevereProblems().isEmpty()) {
            System.err.println("transpile FAILED — unresolved classlib/linkage problems above");
            System.exit(1);
        }
        System.out.println("transpile OK -> " + outDir);
    }

    private static TeaVMToolLog log() {
        return new org.teavm.tooling.ConsoleTeaVMToolLog(true);
    }

    private BuildVita() {}
}
