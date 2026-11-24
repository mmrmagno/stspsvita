#include "manifest.h"

#include <stdlib.h>
#include <string.h>

static int is_sha256(const char *s) {
    if (strlen(s) != 64) return 0;
    return strspn(s, "0123456789abcdef") == 64;
}

static int put(char *dst, size_t cap, const char *val, size_t n) {
    if (n == 0 || n >= cap) return 0;
    memcpy(dst, val, n);
    dst[n] = 0;
    return 1;
}

static int positive(const char *val, size_t n, long *out) {
    char num[16];
    if (!put(num, sizeof(num), val, n) || strspn(num, "0123456789") != n || n > 9) return 0;
    *out = strtol(num, NULL, 10);
    return *out > 0;
}

stsv_manifest_result stsv_manifest_parse(const char *text, size_t len, stsv_manifest *out) {
    const char *p = text, *end = text + len;
    memset(out, 0, sizeof(*out));
    while (p < end) {
        const char *nl = memchr(p, '\n', (size_t) (end - p));
        const char *stop = nl ? nl : end;
        const char *next = nl ? nl + 1 : end;
        if (stop > p && stop[-1] == '\r') stop--;
        const char *eq = memchr(p, '=', (size_t) (stop - p));
        if (eq) {
            size_t k = (size_t) (eq - p), n = (size_t) (stop - eq - 1);
            const char *v = eq + 1;
            long num;
#define KEY(s) (k == sizeof(s) - 1 && memcmp(p, s, k) == 0)
            if (KEY("format")) {
                if (!positive(v, n, &num)) return STSV_MANIFEST_INVALID;
                out->format = (int) num;
            } else if (KEY("game_size")) {
                if (!positive(v, n, &out->game_size)) return STSV_MANIFEST_INVALID;
            } else if (KEY("build_id")) {
                if (!put(out->build_id, sizeof(out->build_id), v, n)) return STSV_MANIFEST_INVALID;
            } else if (KEY("game_sha256")) {
                if (!put(out->game_sha256, sizeof(out->game_sha256), v, n)) return STSV_MANIFEST_INVALID;
            } else if (KEY("assets_id")) {
                if (!put(out->assets_id, sizeof(out->assets_id), v, n)) return STSV_MANIFEST_INVALID;
            }
#undef KEY
        }
        p = next;
    }
    if (out->format == 0) return STSV_MANIFEST_INVALID;
    if (out->format > STSV_MANIFEST_FORMAT) return STSV_MANIFEST_TOO_NEW;
    if (out->game_size == 0 || !out->build_id[0]
        || !is_sha256(out->game_sha256) || !is_sha256(out->assets_id)) {
        return STSV_MANIFEST_INVALID;
    }
    return STSV_MANIFEST_OK;
}
