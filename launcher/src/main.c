#include <psp2/appmgr.h>
#include <psp2/ctrl.h>
#include <psp2/io/fcntl.h>
#include <psp2/io/stat.h>
#include <psp2/kernel/processmgr.h>
#include <psp2/power.h>
#include <stdio.h>
#include <string.h>
#include <vita2d.h>

#include "manifest.h"
#include "sha256.h"

#define DATA_DIR "ux0:data/stsvita/"
#define APP_DIR "ux0:app/" STSV_TITLEID "/"

#define BG RGBA8(0x12, 0x14, 0x1c, 0xff)
#define GOLD RGBA8(0xe8, 0xc1, 0x6a, 0xff)
#define TEXT RGBA8(0xe6, 0xe6, 0xe6, 0xff)
#define DIM RGBA8(0x9a, 0xa0, 0xac, 0xff)
#define BAD RGBA8(0xe0, 0x6c, 0x75, 0xff)
#define PANEL RGBA8(0x0c, 0x0e, 0x12, 0xe0)
#define LINE RGBA8(0xb8, 0x96, 0x4a, 0xff)

static vita2d_pgf *font;
static stsv_manifest manifest;
static char copy_buffer[256 * 1024];
static int app0_rc;

typedef struct {
    const char *title;
    char body[512];
} problem;

static vita2d_texture *background;

/* The game's own loading screen, from the user's build; the VPK ships no game art. */
static void load_background(void) {
    if (background) return;
    background = vita2d_load_PNG_file(DATA_DIR "loading.png");
    if (background && (vita2d_texture_get_width(background) != 960 ||
                       vita2d_texture_get_height(background) != 544)) {
        vita2d_free_texture(background);
        background = NULL;
    }
}

static void draw_background(void) {
    load_background();
    if (!background) return;
    vita2d_draw_texture_part(background, 0, 0, 0, 110, 960, 330);
    vita2d_draw_texture_part_scale(background, 0, 330, 0, 439, 960, 1, 1.0f, 214.0f);
    vita2d_draw_rectangle(0, 0, 960, 544, RGBA8(0, 0, 0, 0x4c));
}

static void draw(const char *title, unsigned title_color, const char *body, const char *hint) {
    static const char footer[] = "STSPSVITA launcher " STSV_VERSION ", made by mmrmagno";
    char line[128];
    int y = 350;
    vita2d_start_drawing();
    vita2d_clear_screen();
    draw_background();
    vita2d_draw_rectangle(100, 262, 760, 246, PANEL);
    vita2d_pgf_draw_text(font, 130, 300, title_color, 1.2f, title);
    vita2d_draw_line(130, 316, 830, 316, LINE);
    while (*body && y < 470) {
        size_t n = strcspn(body, "\n");
        size_t c = n < sizeof(line) - 1 ? n : sizeof(line) - 1;
        memcpy(line, body, c);
        line[c] = 0;
        vita2d_pgf_draw_text(font, 130, y, TEXT, 1.0f, line);
        y += 28;
        body += n + (body[n] == '\n');
    }
    vita2d_pgf_draw_text(font, 130, 494, DIM, 0.9f, hint);
    vita2d_pgf_draw_text(font, (960 - vita2d_pgf_text_width(font, 0.7f, footer)) / 2, 534, DIM, 0.7f, footer);
    vita2d_end_drawing();
    vita2d_swap_buffers();
}

static void wait_release(void) {
    SceCtrlData pad;
    do {
        sceCtrlReadBufferPositive(0, &pad, 1);
    } while (pad.buttons & (SCE_CTRL_CROSS | SCE_CTRL_CIRCLE | SCE_CTRL_SELECT));
}

static int wait_press(void) {
    SceCtrlData pad;
    wait_release();
    for (;;) {
        sceCtrlReadBufferPositive(0, &pad, 1);
        if (pad.buttons & SCE_CTRL_CROSS) return SCE_CTRL_CROSS;
        if (pad.buttons & SCE_CTRL_CIRCLE) return SCE_CTRL_CIRCLE;
    }
}

static long file_size(const char *path) {
    SceIoStat st;
    memset(&st, 0, sizeof(st));
    return sceIoGetstat(path, &st) < 0 ? -1 : (long) st.st_size;
}

static int read_text(const char *path, char *out, int cap) {
    SceUID fd = sceIoOpen(path, SCE_O_RDONLY, 0);
    if (fd < 0) return fd;
    int n = sceIoRead(fd, out, (SceSize) (cap - 1));
    sceIoClose(fd);
    if (n < 0) return n;
    out[n] = 0;
    return n;
}

static int matches_id(const char *path, const char *expected) {
    char text[80];
    if (read_text(path, text, sizeof(text)) < 64) return 0;
    text[64] = 0;
    return strcmp(text, expected) == 0;
}

static int check_shader_compiler(problem *p) {
    if (file_size("ur0:/data/libshacccg.suprx") > 0
        || file_size("ur0:data/external/libshacccg.suprx") > 0) {
        return 1;
    }
    p->title = "Shader compiler missing";
    snprintf(p->body, sizeof(p->body),
             "libshacccg.suprx was not found in ur0:data.\n"
             "Install it with PIB Configuration Tool or ShaRKF00D,\n"
             "then start this app again.");
    return 0;
}

static int check_manifest(problem *p) {
    char text[1024];
    int n = read_text(DATA_DIR "game.manifest", text, sizeof(text));
    if (n < 0) {
        p->title = "Game not installed";
        snprintf(p->body, sizeof(p->body),
                 "This app does not contain the game.\n"
                 "Build it on your PC from your own copy of the game and\n"
                 "copy the result to ux0:data/stsvita.\n"
                 "Steps: github.com/mmrmagno/stspsvita");
        return 0;
    }
    switch (stsv_manifest_parse(text, (size_t) n, &manifest)) {
    case STSV_MANIFEST_OK:
        return 1;
    case STSV_MANIFEST_TOO_NEW:
        p->title = "Launcher too old";
        snprintf(p->body, sizeof(p->body),
                 "The installed game needs a newer launcher.\n"
                 "Install the latest STSPSVITA.vpk.");
        return 0;
    default:
        p->title = "game.manifest is damaged";
        snprintf(p->body, sizeof(p->body), "Copy your build output to ux0:data/stsvita again.");
        return 0;
    }
}

static int check_assets(problem *p) {
    if (matches_id(DATA_DIR "assets.id", manifest.assets_id)
        && file_size(DATA_DIR "assets/build.properties") > 0) {
        return 1;
    }
    p->title = "Assets do not match";
    snprintf(p->body, sizeof(p->body),
             "The files in ux0:data/stsvita/assets belong to another build\n"
             "or the copy did not finish. Copy the whole build output again.");
    return 0;
}

static int installed(void) {
    return file_size(APP_DIR "game.bin") == manifest.game_size
        && matches_id(APP_DIR "game.id", manifest.game_sha256);
}

static int copy_game(problem *p) {
    stsv_sha256 sha;
    char hex[65];
    long total = 0;
    int n, rc = 0;

    p->title = "Could not install the game";
    if (file_size(DATA_DIR "game.bin") != manifest.game_size) {
        p->title = "game.bin missing or incomplete";
        snprintf(p->body, sizeof(p->body), "Copy your build output to ux0:data/stsvita again.");
        return 0;
    }
    sceIoRemove(APP_DIR "game.id");
    SceUID in = sceIoOpen(DATA_DIR "game.bin", SCE_O_RDONLY, 0);
    if (in < 0) {
        snprintf(p->body, sizeof(p->body), "Reading game.bin failed (0x%08X).", in);
        return 0;
    }
    SceUID out = sceIoOpen(APP_DIR "game.bin.tmp", SCE_O_WRONLY | SCE_O_CREAT | SCE_O_TRUNC, 0777);
    if (out < 0) {
        sceIoClose(in);
        snprintf(p->body, sizeof(p->body),
                 "Writing to the app folder failed (0x%08X).\n"
                 "app0 unmount returned 0x%08X. Restart the Vita and try again.", out, app0_rc);
        return 0;
    }
    stsv_sha256_init(&sha);
    while ((n = sceIoRead(in, copy_buffer, sizeof(copy_buffer))) > 0) {
        stsv_sha256_update(&sha, copy_buffer, (size_t) n);
        int w = sceIoWrite(out, copy_buffer, (SceSize) n);
        if (w != n) {
            rc = w < 0 ? w : -1;
            break;
        }
        total += n;
    }
    if (n < 0) rc = n;
    sceIoClose(in);
    sceIoClose(out);
    if (rc < 0) {
        sceIoRemove(APP_DIR "game.bin.tmp");
        snprintf(p->body, sizeof(p->body),
                 "Copy failed after %ld bytes (0x%08X).\nCheck free space on ux0.", total, rc);
        return 0;
    }
    stsv_sha256_hex(&sha, hex);
    if (strcmp(hex, manifest.game_sha256) != 0) {
        sceIoRemove(APP_DIR "game.bin.tmp");
        p->title = "game.bin is damaged";
        snprintf(p->body, sizeof(p->body),
                 "Its checksum does not match game.manifest.\nCopy your build output again.");
        return 0;
    }
    sceIoRemove(APP_DIR "game.bin");
    rc = sceIoRename(APP_DIR "game.bin.tmp", APP_DIR "game.bin");
    if (rc < 0) {
        snprintf(p->body, sizeof(p->body), "Rename failed (0x%08X).", rc);
        return 0;
    }
    SceUID id = sceIoOpen(APP_DIR "game.id", SCE_O_WRONLY | SCE_O_CREAT | SCE_O_TRUNC, 0666);
    if (id < 0 || sceIoWrite(id, manifest.game_sha256, 64) != 64) {
        if (id >= 0) sceIoClose(id);
        snprintf(p->body, sizeof(p->body), "Could not record the installed version.");
        return 0;
    }
    sceIoClose(id);
    return 1;
}

static int prepare(problem *p) {
    if (!check_shader_compiler(p) || !check_manifest(p) || !check_assets(p)) return 0;
    if (installed()) return 1;
    draw("Installing the game", GOLD, "This happens once after each update.", "Please wait");
    return copy_game(p);
}

static void status_screen(void) {
    problem p;
    char body[512];
    memset(&manifest, 0, sizeof(manifest));
    int shader = check_shader_compiler(&p);
    int game = check_manifest(&p);
    int assets = game && check_assets(&p);
    snprintf(body, sizeof(body),
             "Shader compiler: %s\nGame files: %s\nAssets: %s\nBuild: %s\nInstalled copy: %s",
             shader ? "found" : "missing", game ? "found" : "missing",
             assets ? "match" : "missing or different",
             game ? manifest.build_id : "none",
             game && installed() ? "current" : "will be refreshed");
    draw("Status", GOLD, body, "X start    O exit");
}

int main(void) {
    SceCtrlData pad;
    problem p;

    /* The app folder is read-only while app0: is mounted. */
    app0_rc = sceAppMgrUmount("app0:");
    scePowerSetArmClockFrequency(444);
    vita2d_init();
    vita2d_set_clear_color(BG);
    font = vita2d_load_default_pgf();

    sceCtrlReadBufferPositive(0, &pad, 1);
    if (pad.buttons & SCE_CTRL_SELECT) {
        status_screen();
        if (wait_press() == SCE_CTRL_CIRCLE) sceKernelExitProcess(0);
    }

    for (;;) {
        if (prepare(&p)) {
            draw("Starting", GOLD, "", "");
            vita2d_wait_rendering_done();
            int rc = sceAppMgrLoadExec("app0:game.bin", NULL, NULL);
            p.title = "Could not start the game";
            snprintf(p.body, sizeof(p.body), "sceAppMgrLoadExec returned 0x%08X.", rc);
        }
        draw(p.title, BAD, p.body, "X try again    O exit");
        if (wait_press() == SCE_CTRL_CIRCLE) sceKernelExitProcess(0);
    }
}
