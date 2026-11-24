#ifndef STSV_SHA256_H
#define STSV_SHA256_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    uint32_t h[8];
    uint64_t len;
    uint8_t block[64];
    size_t used;
} stsv_sha256;

void stsv_sha256_init(stsv_sha256 *s);
void stsv_sha256_update(stsv_sha256 *s, const void *data, size_t n);
void stsv_sha256_hex(stsv_sha256 *s, char out[65]);

#endif
