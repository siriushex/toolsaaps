import unittest
from install_route import patch_site, ANCHOR, INCLUDE


class RouteTests(unittest.TestCase):
    def test_only_adds_include_before_existing_ai_route(self):
        source = "server {\n    listen 443 ssl;\n    server_name diai.centv.ru;\n" + ANCHOR + "\n    }\n}\n"
        changed = patch_site(source)
        self.assertEqual(source, changed.replace(INCLUDE, "", 1))
        with self.assertRaises(ValueError):
            patch_site(changed)

    def test_unknown_or_ambiguous_host_is_rejected(self):
        for source in ("", ANCHOR, ANCHOR + ANCHOR, "server { listen 80; }", "/app-updates/"):
            with self.assertRaises(ValueError):
                patch_site(source)


if __name__ == "__main__":
    unittest.main()
