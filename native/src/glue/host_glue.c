/* Host (Linux) glue for the desktop-GL debugging harness: an SDL2 window at
 * the Vita's 960x544, mouse mapped to the Vita front touch, keyboard mapped
 * to SceCtrl buttons. Same @Import surface the Vita build gets from
 * vitaGL/sceCtrl/sceTouch, so the Java side is identical. */

#include "stsvita_glue.h"

#include <SDL2/SDL.h>
#include <stdio.h>
#include <stdlib.h>

/* SceCtrl button bits (subset the backend maps) */
#define BTN_SELECT   0x000001
#define BTN_START    0x000008
#define BTN_UP       0x000010
#define BTN_RIGHT    0x000020
#define BTN_DOWN     0x000040
#define BTN_LEFT     0x000080
#define BTN_LTRIGGER 0x000100
#define BTN_RTRIGGER 0x000200
#define BTN_TRIANGLE 0x001000
#define BTN_CIRCLE   0x002000
#define BTN_CROSS    0x004000
#define BTN_SQUARE   0x008000

static SDL_Window *window;
static int mouse_x, mouse_y, mouse_down;
static int buttons;

static void ensure_window(void) {
    if (window) {
        return;
    }
    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
        fprintf(stderr, "[host_glue] SDL_Init: %s\n", SDL_GetError());
        exit(1);
    }
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 2);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 1);
    SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
    SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
    SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);
    window = SDL_CreateWindow("STSVITA harness",
            SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
            960, 544, SDL_WINDOW_OPENGL);
    if (!window || !SDL_GL_CreateContext(window)) {
        fprintf(stderr, "[host_glue] window/context: %s\n", SDL_GetError());
        exit(1);
    }
    SDL_GL_SetSwapInterval(1);
}

static void pump_events(void) {
    SDL_Event e;
    while (SDL_PollEvent(&e)) {
        switch (e.type) {
            case SDL_QUIT:
                exit(0);
            case SDL_MOUSEMOTION:
                mouse_x = e.motion.x;
                mouse_y = e.motion.y;
                break;
            case SDL_MOUSEBUTTONDOWN:
            case SDL_MOUSEBUTTONUP:
                if (e.button.button == SDL_BUTTON_LEFT) {
                    mouse_down = e.type == SDL_MOUSEBUTTONDOWN;
                    mouse_x = e.button.x;
                    mouse_y = e.button.y;
                }
                break;
            case SDL_KEYDOWN:
            case SDL_KEYUP: {
                int bit = 0;
                switch (e.key.keysym.sym) {
                    case SDLK_UP:        bit = BTN_UP; break;
                    case SDLK_DOWN:      bit = BTN_DOWN; break;
                    case SDLK_LEFT:      bit = BTN_LEFT; break;
                    case SDLK_RIGHT:     bit = BTN_RIGHT; break;
                    case SDLK_z: case SDLK_RETURN: bit = BTN_CROSS; break;
                    case SDLK_x: case SDLK_BACKSPACE: bit = BTN_CIRCLE; break;
                    case SDLK_a:         bit = BTN_SQUARE; break;
                    case SDLK_s:         bit = BTN_TRIANGLE; break;
                    case SDLK_q:         bit = BTN_LTRIGGER; break;
                    case SDLK_w:         bit = BTN_RTRIGGER; break;
                    case SDLK_TAB:       bit = BTN_SELECT; break;
                    case SDLK_SPACE:     bit = BTN_START; break;
                    case SDLK_ESCAPE:    exit(0);
                    default: break;
                }
                if (e.type == SDL_KEYDOWN) {
                    buttons |= bit;
                } else {
                    buttons &= ~bit;
                }
                break;
            }
            default:
                break;
        }
    }
}

#ifdef STSV_BENCH
#include <SDL2/SDL_opengl.h>

static int32_t bench_capture_id = -1;

void stsv_bench_capture(int32_t id) {
    bench_capture_id = id;
}

static void bench_capture(void) {
    static unsigned char pixels[960 * 544 * 3];
    char name[32];
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(0, 0, 960, 544, GL_RGB, GL_UNSIGNED_BYTE, pixels);
    snprintf(name, sizeof name, "cap_%04d.ppm", (int) bench_capture_id);
    FILE *f = fopen(name, "wb");
    if (f) {
        fprintf(f, "P6\n960 544\n255\n");
        for (int y = 543; y >= 0; y--) {
            fwrite(pixels + y * 960 * 3, 1, 960 * 3, f);
        }
        fclose(f);
    }
    bench_capture_id = -1;
}
#endif

void stsv_swap_buffers(void) {
    ensure_window();
#ifdef STSV_BENCH
    if (bench_capture_id >= 0) {
        bench_capture();
    }
#endif
    SDL_GL_SwapWindow(window);
    pump_events();
}

void stsv_set_vsync(int32_t on) {
    if (window) {
        SDL_GL_SetSwapInterval(on ? 1 : 0);
    }
}

int32_t stsv_is_host_harness(void) {
    return 1;
}

/* The Java side creates the GL context implicitly through first use; the
 * host needs it before ANY gl* call, which happens before the first swap. */
/* On the Vita, main.c calls stsv_audio_init() before the Java world boots; the
 * host harness has no such entry point, so kick it off at gfx init instead
 * (idempotent). */
void stsv_audio_init(void);

void stsv_gfx_init(void) {
    ensure_window();
    stsv_audio_init();
}

/* No on-screen keyboard on the host: open reports failure, so the Java side
 * falls back to accepting the default text (same behavior as before IME). */
int32_t stsv_ime_open(void *title_u16, void *initial_u16, int32_t max_len) {
    (void) title_u16; (void) initial_u16; (void) max_len;
    return -1;
}

int32_t stsv_ime_poll(void) {
    return 0;
}

int32_t stsv_ime_result(void *out, int32_t cap) {
    (void) cap;
    *(uint16_t *) out = 0;
    return 0;
}

int32_t stsv_ime_active(void) {
    return 0;
}

int32_t stsv_ctrl_buttons(void) {
    return buttons;
}

float stsv_ctrl_axis(int32_t axis) {
    (void) axis;
    return 0.0f;
}

int32_t stsv_touch_count(void) {
    pump_events();
    return mouse_down ? 1 : 0;
}

int32_t stsv_touch_x(int32_t finger) {
    (void) finger;
    return mouse_x;
}

int32_t stsv_touch_y(int32_t finger) {
    (void) finger;
    return mouse_y;
}

int32_t stsv_rear_tap(void) {
    return 0;
}
