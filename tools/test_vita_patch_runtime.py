import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vita_patch_runtime  # noqa: E402

STRING_C = """#include "String.h"
int32_t meth_jl_String_equals(void* teavm_this_, void* teavm_local_1) {
    int32_t teavm_local_2;
    teavm_local_2 = INT32_C(0);
    return teavm_local_2;
}
int32_t meth_jl_String_hashCode(void* teavm_this_) {
    return 0;
}
"""

ARRAYLIST_C = """void* meth_ju_ArrayList_remove(void* teavm_this_, int32_t teavm_local_1) {
    teavm_local_5 = (TEAVM_FIELD(teavm_this_, cls_ju_ArrayList, fld_size) - INT32_C(1));
    while (1) {
        if (!(teavm_local_3 < teavm_local_5)) break;
        teavm_local_1 = (teavm_local_3 + INT32_C(1));
        teavm_local_6 = TEAVM_WITH_CALL_SITE_ID(5111, teavm_checkBounds(teavm_local_1, teavm_local_2));
        teavm_local_7 = TEAVM_ARRAY_AT(teavm_local_2, void*, teavm_local_6);
        teavm_gc_writeBarrier(teavm_local_2);
        TEAVM_ARRAY_AT(teavm_local_2, void*, teavm_local_3) = teavm_local_7;
        teavm_local_3 = teavm_local_6;
    }
    teavm_local_7 = NULL;
    return teavm_local_4;
}
void meth_ju_ArrayList_other(void* teavm_this_) {
    while (1) {
        if (!(teavm_local_3 < teavm_local_5)) break;
    }
}
"""


class StringEqualsPatchTest(unittest.TestCase):
    def test_replaces_only_equals_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "String.c"
            p.write_text(STRING_C)
            self.assertTrue(vita_patch_runtime.patch_string_equals(p))
            text = p.read_text()
            self.assertFalse(vita_patch_runtime.patch_string_equals(p))
            self.assertEqual(text, p.read_text())
        self.assertIn("__builtin_memcmp", text)
        self.assertNotIn("teavm_local_2 = INT32_C(0);", text)
        self.assertIn("int32_t meth_jl_String_hashCode(void* teavm_this_) {\n    return 0;\n}", text)
        self.assertEqual(text.count("int32_t meth_jl_String_equals("), 1)

    def test_missing_anchor_fails(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "String.c"
            p.write_text("int x;\n")
            with self.assertRaises(SystemExit):
                vita_patch_runtime.patch_string_equals(p)


class ArrayListRemovePatchTest(unittest.TestCase):
    def test_replaces_shift_loop_once(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "ArrayList.c"
            p.write_text(ARRAYLIST_C)
            self.assertTrue(vita_patch_runtime.patch_arraylist_remove(p))
            text = p.read_text()
            self.assertFalse(vita_patch_runtime.patch_arraylist_remove(p))
        remove, other = text.split("void meth_ju_ArrayList_other")
        self.assertIn("__builtin_memmove", remove)
        self.assertNotIn("while (1)", remove)
        self.assertIn("teavm_local_7 = NULL;", remove)
        self.assertIn("while (1)", other)


if __name__ == "__main__":
    unittest.main()
