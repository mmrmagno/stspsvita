"""Runs the real launcher against a fake Vita that locks the app folder while app0: is mounted."""
import hashlib
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "launcher/src"
HEADERS = ("psp2/appmgr.h", "psp2/ctrl.h", "psp2/io/fcntl.h", "psp2/io/stat.h",
           "psp2/kernel/processmgr.h", "psp2/power.h", "vita2d.h")

FAKE = r"""
#ifndef FAKE_H
#define FAKE_H
#include <stdint.h>
#include <sys/types.h>
typedef int SceUID; typedef unsigned SceSize;
typedef struct { long long st_size; } SceIoStat;
typedef struct { unsigned buttons; } SceCtrlData;
typedef struct vita2d_pgf vita2d_pgf;
enum { SCE_O_RDONLY = 1, SCE_O_WRONLY = 2, SCE_O_CREAT = 4, SCE_O_TRUNC = 8 };
enum { SCE_CTRL_SELECT = 1, SCE_CTRL_CROSS = 2, SCE_CTRL_CIRCLE = 4 };
#define RGBA8(r, g, b, a) ((r) | (g) << 8 | (b) << 16 | (unsigned) (a) << 24)
int sceAppMgrUmount(const char *); int sceAppMgrLoadExec(const char *, char *const *, const void *);
SceUID sceIoOpen(const char *, int, int); int sceIoRead(SceUID, void *, SceSize);
int sceIoWrite(SceUID, const void *, SceSize); int sceIoClose(SceUID);
int sceIoRemove(const char *); int sceIoRename(const char *, const char *);
int sceIoGetstat(const char *, SceIoStat *);
int sceCtrlReadBufferPositive(int, SceCtrlData *, int);
int sceKernelExitProcess(int); int scePowerSetArmClockFrequency(int);
int vita2d_init(void); void vita2d_set_clear_color(unsigned); vita2d_pgf *vita2d_load_default_pgf(void);
void vita2d_start_drawing(void); void vita2d_clear_screen(void); void vita2d_end_drawing(void);
void vita2d_swap_buffers(void); void vita2d_wait_rendering_done(void);
int vita2d_pgf_draw_text(vita2d_pgf *, int, int, unsigned, float, const char *);
#endif
"""

IMPL = r"""
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include "vita2d.h"
#define EACCES_VITA ((int) 0x8001000D)
#define ENOENT_VITA ((int) 0x80010002)
static int mounted = 1, polls;
static int map(const char *p, char *out) {
    const char *c = strchr(p, ':');
    if (!c) return 0;
    if (strncmp(p, "app0", 4) == 0) {
        if (!mounted) return 0;
        sprintf(out, "ux0/app/STSV00001/%s", c + 1 + (c[1] == '/'));
    } else {
        sprintf(out, "%.*s/%s", (int) (c - p), p, c + 1 + (c[1] == '/'));
    }
    return 1;
}
static int locked(const char *p) { return mounted && strstr(p, "app/STSV00001") != NULL; }
int sceAppMgrUmount(const char *p) { if (!strcmp(p, "app0:")) mounted = 0; return 0; }
int sceAppMgrLoadExec(const char *p, char *const *a, const void *o) {
    (void) a; (void) o;
    printf("EXEC %s\n", p);
    exit(0);
}
SceUID sceIoOpen(const char *p, int f, int m) {
    char b[512]; (void) m;
    if (!map(p, b)) return ENOENT_VITA;
    if ((f & SCE_O_WRONLY) && locked(b)) return EACCES_VITA;
    int fd = open(b, (f & SCE_O_WRONLY ? O_WRONLY : O_RDONLY) | (f & SCE_O_CREAT ? O_CREAT : 0)
                  | (f & SCE_O_TRUNC ? O_TRUNC : 0), 0666);
    return fd < 0 ? ENOENT_VITA : fd;
}
int sceIoRead(SceUID f, void *b, SceSize n) { return (int) read(f, b, n); }
int sceIoWrite(SceUID f, const void *b, SceSize n) { return (int) write(f, b, n); }
int sceIoClose(SceUID f) { return close(f); }
int sceIoRemove(const char *p) {
    char b[512];
    return !map(p, b) ? ENOENT_VITA : locked(b) ? EACCES_VITA : remove(b);
}
int sceIoRename(const char *p, const char *q) {
    char a[512], b[512];
    return !map(p, a) || !map(q, b) ? ENOENT_VITA : locked(b) ? EACCES_VITA : rename(a, b);
}
int sceIoGetstat(const char *p, SceIoStat *s) {
    char b[512]; struct stat st;
    if (!map(p, b) || stat(b, &st)) return ENOENT_VITA;
    s->st_size = st.st_size;
    return 0;
}
int sceCtrlReadBufferPositive(int p, SceCtrlData *d, int n) {
    (void) p; (void) n;
    d->buttons = polls++ < 2 ? 0 : SCE_CTRL_CIRCLE;
    if (polls > 8) exit(9);
    return 1;
}
int sceKernelExitProcess(int r) { printf("EXIT\n"); exit(r); }
int scePowerSetArmClockFrequency(int f) { (void) f; return 0; }
int vita2d_init(void) { return 1; }
void vita2d_set_clear_color(unsigned c) { (void) c; }
vita2d_pgf *vita2d_load_default_pgf(void) { return NULL; }
void vita2d_start_drawing(void) {} void vita2d_clear_screen(void) {} void vita2d_end_drawing(void) {}
void vita2d_swap_buffers(void) {} void vita2d_wait_rendering_done(void) {}
int vita2d_pgf_draw_text(vita2d_pgf *f, int x, int y, unsigned c, float s, const char *t) {
    (void) f; (void) x; (void) y; (void) c; (void) s;
    printf("TEXT %s\n", t);
    return 0;
}
"""


@unittest.skipUnless(shutil.which("cc"), "needs a host C compiler")
class LauncherBoot(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build = tempfile.TemporaryDirectory()
        root = Path(cls.build.name)
        for name in HEADERS:
            path = root / "inc" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(FAKE)
        (root / "fake.c").write_text(IMPL)
        cls.exe = root / "launcher"
        subprocess.run(["cc", "-std=gnu11", "-w", "-fsanitize=address,undefined",
                        '-DSTSV_TITLEID="STSV00001"', '-DSTSV_VERSION="test"',
                        f"-I{root / 'inc'}", f"-I{SRC}", str(root / "fake.c"),
                        *(str(SRC / n) for n in ("main.c", "manifest.c", "sha256.c")),
                        "-o", str(cls.exe)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.build.cleanup()

    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.vita = Path(tmp.name)
        self.data = self.vita / "ux0/data/stsvita"
        self.app = self.vita / "ux0/app/STSV00001"
        for d in (self.data / "assets", self.app, self.vita / "ur0/data/external"):
            d.mkdir(parents=True)
        (self.vita / "ur0/data/external/libshacccg.suprx").write_bytes(b"x")
        (self.data / "assets/build.properties").write_text("x")
        (self.data / "assets.id").write_text("0" * 64 + "\n")
        self.put_game(b"game" * 200000)

    def put_game(self, game):
        self.game = game
        self.sha = hashlib.sha256(game).hexdigest()
        (self.data / "game.bin").write_bytes(game)
        (self.data / "game.manifest").write_text(
            f"format=1\nbuild_id=t\ngame_size={len(game)}\n"
            f"game_sha256={self.sha}\nassets_id={'0' * 64}\n")

    def boot(self):
        return subprocess.run([str(self.exe)], cwd=self.vita, capture_output=True,
                              text=True, timeout=20).stdout

    def assert_installed(self, out):
        self.assertIn("EXEC app0:game.bin", out)
        self.assertEqual((self.app / "game.bin").read_bytes(), self.game)
        self.assertEqual((self.app / "game.id").read_text(), self.sha)
        self.assertFalse((self.app / "game.bin.tmp").exists())

    def test_first_start_installs_into_the_locked_app_folder(self):
        out = self.boot()
        self.assertIn("Installing the game", out)
        self.assert_installed(out)

    def test_second_start_does_not_copy_again(self):
        self.boot()
        out = self.boot()
        self.assertNotIn("Installing the game", out)
        self.assert_installed(out)

    def test_update_replaces_the_game(self):
        self.boot()
        self.put_game(b"new!" * 200000)
        self.assert_installed(self.boot())

    def test_damaged_game_is_never_started(self):
        (self.data / "game.bin").write_bytes(b"X" + self.game[1:])
        out = self.boot()
        self.assertIn("game.bin is damaged", out)
        self.assertNotIn("EXEC", out)
        self.assertEqual(list(self.app.iterdir()), [])

    def test_missing_pieces_are_named(self):
        for path, title in ((self.data / "assets/build.properties", "Assets do not match"),
                            (self.data / "game.manifest", "Game not installed"),
                            (self.vita / "ur0/data/external/libshacccg.suprx",
                             "Shader compiler missing")):
            path.unlink()
            out = self.boot()
            self.assertIn(title, out)
            self.assertNotIn("EXEC", out)


if __name__ == "__main__":
    unittest.main()
