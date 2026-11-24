#include "sha256.h"

#include <string.h>

static const uint32_t K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

#define ROR(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

static void compress(stsv_sha256 *s, const uint8_t *p) {
    uint32_t w[64], v[8];
    for (int i = 0; i < 16; i++) {
        w[i] = (uint32_t) p[i * 4] << 24 | (uint32_t) p[i * 4 + 1] << 16
             | (uint32_t) p[i * 4 + 2] << 8 | p[i * 4 + 3];
    }
    for (int i = 16; i < 64; i++) {
        uint32_t a = ROR(w[i - 15], 7) ^ ROR(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t b = ROR(w[i - 2], 17) ^ ROR(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + a + w[i - 7] + b;
    }
    memcpy(v, s->h, sizeof(v));
    for (int i = 0; i < 64; i++) {
        uint32_t e1 = ROR(v[4], 6) ^ ROR(v[4], 11) ^ ROR(v[4], 25);
        uint32_t ch = (v[4] & v[5]) ^ (~v[4] & v[6]);
        uint32_t t1 = v[7] + e1 + ch + K[i] + w[i];
        uint32_t e0 = ROR(v[0], 2) ^ ROR(v[0], 13) ^ ROR(v[0], 22);
        uint32_t t2 = e0 + ((v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]));
        memmove(v + 1, v, 7 * sizeof(v[0]));
        v[4] += t1;
        v[0] = t1 + t2;
    }
    for (int i = 0; i < 8; i++) s->h[i] += v[i];
}

void stsv_sha256_init(stsv_sha256 *s) {
    static const uint32_t H[8] = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    };
    memset(s, 0, sizeof(*s));
    memcpy(s->h, H, sizeof(H));
}

void stsv_sha256_update(stsv_sha256 *s, const void *data, size_t n) {
    const uint8_t *p = data;
    s->len += n;
    while (n > 0) {
        size_t take = 64 - s->used;
        if (take > n) take = n;
        memcpy(s->block + s->used, p, take);
        s->used += take;
        p += take;
        n -= take;
        if (s->used == 64) {
            compress(s, s->block);
            s->used = 0;
        }
    }
}

void stsv_sha256_hex(stsv_sha256 *s, char out[65]) {
    static const char HEX[] = "0123456789abcdef";
    uint64_t bits = s->len * 8;
    uint8_t len[8];
    stsv_sha256_update(s, "\x80", 1);
    while (s->used != 56) stsv_sha256_update(s, "\0", 1);
    for (int i = 0; i < 8; i++) len[i] = (uint8_t) (bits >> (56 - 8 * i));
    stsv_sha256_update(s, len, 8);
    for (int i = 0; i < 32; i++) {
        uint8_t b = (uint8_t) (s->h[i / 4] >> (24 - 8 * (i % 4)));
        out[i * 2] = HEX[b >> 4];
        out[i * 2 + 1] = HEX[b & 15];
    }
    out[64] = 0;
}
