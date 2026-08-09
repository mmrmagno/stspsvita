/*
 * Minimal PNG -> DDS/DXT encoder for the asset pipeline.
 *
 * Uses the repo's existing stb_image/stb_dxt copies so the generated DDS files
 * match the runtime compressor's block format. This intentionally writes a
 * single top-level image, no mipmaps.
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define STB_IMAGE_IMPLEMENTATION
#define STB_IMAGE_STATIC
#include "../native/third_party/stb_image.h"

#define STB_DXT_IMPLEMENTATION
#define STB_DXT_STATIC
#include "../native/third_party/stb_dxt.h"

static void put_u32(unsigned char *p, uint32_t v) {
    p[0] = (unsigned char) (v & 0xffu);
    p[1] = (unsigned char) ((v >> 8) & 0xffu);
    p[2] = (unsigned char) ((v >> 16) & 0xffu);
    p[3] = (unsigned char) ((v >> 24) & 0xffu);
}

static uint32_t fourcc(const char *s) {
    return (uint32_t) s[0]
        | ((uint32_t) s[1] << 8)
        | ((uint32_t) s[2] << 16)
        | ((uint32_t) s[3] << 24);
}

static int write_header(FILE *f, int width, int height, int has_alpha, size_t data_size) {
    unsigned char h[128];
    memset(h, 0, sizeof(h));
    h[0] = 'D';
    h[1] = 'D';
    h[2] = 'S';
    h[3] = ' ';

    put_u32(h + 4, 124);             /* DDS_HEADER.dwSize */
    put_u32(h + 8, 0x00081007u);     /* CAPS | HEIGHT | WIDTH | PIXELFORMAT | LINEARSIZE */
    put_u32(h + 12, (uint32_t) height);
    put_u32(h + 16, (uint32_t) width);
    put_u32(h + 20, (uint32_t) data_size);

    put_u32(h + 76, 32);             /* DDS_PIXELFORMAT.dwSize */
    put_u32(h + 80, 0x00000004u);    /* DDPF_FOURCC */
    put_u32(h + 84, fourcc(has_alpha ? "DXT5" : "DXT1"));

    put_u32(h + 108, 0x00001000u);   /* DDSCAPS_TEXTURE */
    return fwrite(h, 1, sizeof(h), f) == sizeof(h) ? 0 : 1;
}

/* Alpha-weighted 2x2 box downsample: weighting color by alpha avoids the
 * dark halo a plain average pulls in from fully-transparent neighbours. */
static unsigned char *halve(const unsigned char *src, int w, int h) {
    int hw = w / 2, hh = h / 2;
    unsigned char *out = (unsigned char *) malloc((size_t) hw * hh * 4);
    if (out == NULL) {
        return NULL;
    }
    for (int y = 0; y < hh; y++) {
        for (int x = 0; x < hw; x++) {
            unsigned r = 0, g = 0, b = 0, a = 0;
            for (int dy = 0; dy < 2; dy++) {
                for (int dx = 0; dx < 2; dx++) {
                    const unsigned char *p =
                        src + (((size_t) (y * 2 + dy) * w) + (size_t) (x * 2 + dx)) * 4;
                    unsigned pa = p[3];
                    r += p[0] * pa;
                    g += p[1] * pa;
                    b += p[2] * pa;
                    a += pa;
                }
            }
            unsigned char *q = out + ((size_t) y * hw + x) * 4;
            q[0] = (unsigned char) (a ? (r + a / 2) / a : 0);
            q[1] = (unsigned char) (a ? (g + a / 2) / a : 0);
            q[2] = (unsigned char) (a ? (b + a / 2) / a : 0);
            q[3] = (unsigned char) ((a + 2) / 4);
        }
    }
    return out;
}

int main(int argc, char **argv) {
    int force = 0, scale = 1;
    if (argc < 3) {
        fprintf(stderr, "usage: ddsenc <src.png> <dst.dds> [-f] [-2|-4]\n");
        return 1;
    }
    for (int i = 3; i < argc; i++) {
        if (strcmp(argv[i], "-f") == 0) {
            force = 1;
        } else if (strcmp(argv[i], "-2") == 0) {
            scale = 2;
        } else if (strcmp(argv[i], "-4") == 0) {
            scale = 4;
        } else {
            fprintf(stderr, "ddsenc: unknown flag %s\n", argv[i]);
            return 1;
        }
    }

    int width = 0, height = 0, comp = 0;
    if (!stbi_info(argv[1], &width, &height, &comp)) {
        fprintf(stderr, "ddsenc: not an image: %s\n", argv[1]);
        return 3;
    }
    if ((width & 3) != 0 || (height & 3) != 0) {
        return 3;
    }
    /* Small/flat images stay PNG for UI crispness — except forced pages
     * (padded POT atlas pages, which must be compressed for VRAM). */
    if (!force && (width < 256 || height < 256)) {
        return 3;
    }
    /* Downsampled dimensions must remain DXT-blockable (/4). The caller
     * retries a less aggressive scale on skip. */
    if ((scale == 2 && ((width & 7) != 0 || (height & 7) != 0))
            || (scale == 4 && ((width & 15) != 0 || (height & 15) != 0))) {
        return 3;
    }

    unsigned char *rgba = stbi_load(argv[1], &width, &height, &comp, 4);
    if (rgba == NULL) {
        fprintf(stderr, "ddsenc: decode failed for %s: %s\n", argv[1], stbi_failure_reason());
        return 1;
    }

    size_t pixels = (size_t) width * (size_t) height;
    int has_alpha = 0;
    for (size_t i = 0; i < pixels; i++) {
        if (rgba[i * 4 + 3] != 0xff) {
            has_alpha = 1;
            break;
        }
    }

    /* Reduced-data convention: the header keeps the ORIGINAL (logical) dims — the
     * game's layout/UV math reads them via texture.getWidth() — while the
     * block data is encoded at a smaller size. stsv_tex_image_compressed infers
     * the actual upload dims from the data size. */
    int enc_w = width, enc_h = height;
    for (int current_scale = 1; current_scale < scale; current_scale *= 2) {
        unsigned char *halved = halve(rgba, enc_w, enc_h);
        stbi_image_free(rgba);
        if (halved == NULL) {
            fprintf(stderr, "ddsenc: out of memory\n");
            return 1;
        }
        rgba = halved;
        enc_w /= 2;
        enc_h /= 2;
    }

    int block_bytes = has_alpha ? 16 : 8;
    int bw = enc_w / 4;
    int bh = enc_h / 4;
    size_t out_size = (size_t) bw * (size_t) bh * (size_t) block_bytes;
    unsigned char *out = (unsigned char *) malloc(out_size);
    if (out == NULL) {
        stbi_image_free(rgba);
        fprintf(stderr, "ddsenc: out of memory\n");
        return 1;
    }

    unsigned char *dst = out;
    unsigned char block[64];
    for (int by = 0; by < bh; by++) {
        for (int bx = 0; bx < bw; bx++) {
            for (int row = 0; row < 4; row++) {
                const unsigned char *src =
                    rgba + (((size_t) (by * 4 + row) * (size_t) enc_w) + (size_t) bx * 4u) * 4u;
                memcpy(block + row * 16, src, 16);
            }
            stb_compress_dxt_block(dst, block, has_alpha, STB_DXT_NORMAL);
            dst += block_bytes;
        }
    }

    FILE *f = fopen(argv[2], "wb");
    if (f == NULL) {
        free(out);
        stbi_image_free(rgba);
        perror("ddsenc: fopen");
        return 1;
    }
    int failed = write_header(f, width, height, has_alpha, out_size)
        || fwrite(out, 1, out_size, f) != out_size
        || fclose(f) != 0;

    free(out);
    stbi_image_free(rgba);
    return failed ? 1 : 0;
}
