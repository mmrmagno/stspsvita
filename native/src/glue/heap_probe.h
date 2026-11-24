#ifndef STSV_HEAP_PROBE_H
#define STSV_HEAP_PROBE_H

#include <stddef.h>
#include <stdlib.h>

static inline int stsv_heap_can_alloc(size_t bytes) {
    /* Without volatile GCC drops the malloc/free pair and assumes success. */
    void *volatile probe = malloc(bytes);
    if (probe == NULL) return 0;
    free(probe);
    return 1;
}

#endif
