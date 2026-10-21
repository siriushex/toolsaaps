import unittest

from analyze_apk import verify_usage


class PackagingBoundaryTests(unittest.TestCase):
    def test_unused_icon_is_removed_but_used_icon_is_not_counted_as_removed(self):
        classes = {"Landroidx/compose/material/icons/filled/CheckKt;"}
        text = "androidx.compose.material.icons.filled.UnusedKt\nandroidx.compose.material.icons.filled.CheckKt:\n    int unusedCache\n"
        self.assertEqual((1, 0, 0), verify_usage(text, classes, set()))

    def test_transient_desugaring_artifacts_are_distinct_from_input_removal(self):
        classes = {"Landroidx/core/app/ActivityCompat;"}
        text = ("androidx.core.app.ActivityCompat$$InternalSyntheticLambda$1$abc$0\n"
                "androidx.core.app.ActivityCompat:\n"
                "    public static synthetic void $r8$lambda$abc(android.app.Activity)\n")
        self.assertEqual((0, 1, 1), verify_usage(text, classes, set()))

    def test_real_nonicon_class_or_member_removal_is_rejected(self):
        for text in ("io.aaps.copilot.data.Sample\n", "androidx.core.app.ActivityCompat\n",
                     "androidx.core.app.ActivityCompat:\n    public void realMethod()\n"):
            with self.assertRaises(ValueError):
                verify_usage(text, {"Landroidx/core/app/ActivityCompat;"}, set())

    def test_a_generated_looking_input_class_cannot_be_discarded(self):
        name = "androidx.core.app.ActivityCompat$$InternalSyntheticLambda$1$abc$0"
        with self.assertRaises(ValueError):
            verify_usage(name, set(), {"L" + name.replace(".", "/") + ";"})

    def test_bridge_exception_cannot_hide_a_missing_or_application_class(self):
        for name in ("androidx.core.app.ActivityCompat", "io.aaps.copilot.data.Sample"):
            text = name + ":\n    public static synthetic void $r8$lambda$abc()\n"
            with self.assertRaises(ValueError):
                verify_usage(text, set(), set())


if __name__ == "__main__":
    unittest.main()
