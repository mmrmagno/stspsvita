/* Process startup: memory + vitaGL init before the Java world boots. */

#include <vitaGL.h>
#include <psp2/kernel/processmgr.h>
#include <psp2/power.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stdio.h>

/* Holds the 112MB Java heap (BuildVita.java) plus 128MB for native malloc:
 * image decode, FreeType and vitaGL's per-draw vertex buffers. What is left of
 * the app budget becomes vitaGL's RAM texture pool, so change both together. */
int _newlib_heap_size_user = 240 * 1024 * 1024;

/* TeaVM's generated main, renamed via -Dmain=stsv_java_main (CMake). */
int stsv_java_main(int argc, char **argv);

/* Enables SceCtrl analog + SceTouch front-panel sampling (input_glue.c). */
void stsv_input_init(void);

/* Replaces vitaGL's spinner with the generated title loading frame. */
void stsv_show_loading_screen(void);

/* Opens the OpenAL device/context for VitaAudio (audio_glue.c). */
void stsv_audio_init(void);

/* Buffered so every println is not a memory card write. Static, so logging
 * never allocates from the heap the draw buffers come from. */
static char stsv_stdout_buffer[16 * 1024];

int main(int argc, char **argv) {
    scePowerSetArmClockFrequency(444);
    scePowerSetBusClockFrequency(222);
    scePowerSetGpuClockFrequency(222);
    scePowerSetGpuXbarClockFrequency(166);

    /* Game data (saves, preferences, info.displayconfig) lives on ux0:.
     * The game reads/writes some of it via cwd-relative paths, so move
     * cwd there; assets are always absolute (app0:/assets/). */
    mkdir("ux0:data", 0777);
    mkdir("ux0:data/stsvita", 0777);
    chdir("ux0:data/stsvita");

    /* The Vita has no console, so stdout and stderr go to one file. Both are
     * opened in append mode because Vita newlib has no dup2. */
    {
        remove("ux0:data/stsvita/stsvita.prev.log");
        rename("ux0:data/stsvita/stsvita.log", "ux0:data/stsvita/stsvita.prev.log");
        FILE *f = fopen("ux0:data/stsvita/stsvita.log", "w");
        if (f) fclose(f);
    }
    freopen("ux0:data/stsvita/stsvita.log", "a", stdout);
    freopen("ux0:data/stsvita/stsvita.log", "a", stderr);
    setvbuf(stdout, stsv_stdout_buffer, _IOFBF, sizeof(stsv_stdout_buffer));
    setvbuf(stderr, NULL, _IONBF, 0);

    /* Takes all CDRAM and PHYCONT, and makes a RAM pool from what the newlib
     * heap left, keeping 16MB free for GXM and stacks. */
    GLboolean ok = vglInitExtended(8 * 1024 * 1024, 960, 544,
                                   16 * 1024 * 1024, SCE_GXM_MULTISAMPLE_NONE);
    fprintf(stderr,
        "[boot] vglInitExtended -> %d | pools: VRAM=%dMB RAM=%dMB PHYCONT=%dMB\n",
        (int) ok,
        (int) (vglMemTotal(VGL_MEM_VRAM) >> 20),
        (int) (vglMemTotal(VGL_MEM_RAM) >> 20),
        (int) (vglMemTotal(VGL_MEM_SLOW) >> 20));

    stsv_show_loading_screen();

    stsv_input_init();
    stsv_audio_init();

    int rc = stsv_java_main(argc, argv);

    fflush(NULL);

    sceKernelExitProcess(rc);
    return rc;
}
