#ifndef STSV_MANIFEST_H
#define STSV_MANIFEST_H

#include <stddef.h>

#define STSV_MANIFEST_FORMAT 1

typedef struct {
    int format;
    long game_size;
    char build_id[64];
    char game_sha256[65];
    char assets_id[65];
} stsv_manifest;

typedef enum {
    STSV_MANIFEST_OK = 0,
    STSV_MANIFEST_INVALID,
    STSV_MANIFEST_TOO_NEW,
} stsv_manifest_result;

stsv_manifest_result stsv_manifest_parse(const char *text, size_t len, stsv_manifest *out);

#endif
