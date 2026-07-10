import unittest

from scripts.tools.harness._status import (
    find_crash_marker,
    find_harness_logcat_crash,
    find_single_room_result,
    last_non_blank_line,
    parse_boot_bridge_events,
)


class ParseBootBridgeEventsTest(unittest.TestCase):
    def test_empty_text(self):
        result = parse_boot_bridge_events("")
        self.assertEqual(result["eventCount"], 0)
        self.assertIsNone(result["latestEvent"])
        self.assertIsNone(result["terminalEvent"])

    def test_parses_events(self):
        text = "LOAD\t50\tLoading assets\nREADY\t100\tReady\n"
        result = parse_boot_bridge_events(text)
        self.assertEqual(result["eventCount"], 2)
        self.assertEqual(result["latestEvent"]["type"], "READY")
        self.assertEqual(result["terminalEvent"]["type"], "READY")

    def test_none_input(self):
        result = parse_boot_bridge_events(None)
        self.assertEqual(result["eventCount"], 0)

    def test_fail_terminal(self):
        text = "LOAD\t50\tLoading\nFAIL\t0\tTimeout\n"
        result = parse_boot_bridge_events(text)
        self.assertEqual(result["terminalEvent"]["type"], "FAIL")


class FindCrashMarkerTest(unittest.TestCase):
    def test_finds_game_crashed(self):
        self.assertEqual(find_crash_marker("Game crashed."), "Game crashed.")

    def test_finds_lwjgl_exception(self):
        self.assertEqual(
            find_crash_marker('Exception in thread "LWJGL Application" blah'),
            'Exception in thread "LWJGL Application"',
        )

    def test_returns_none_when_clean(self):
        self.assertIsNone(find_crash_marker("Everything is fine"))

    def test_handles_none(self):
        self.assertIsNone(find_crash_marker(None))


class FindSingleRoomResultTest(unittest.TestCase):
    def test_finds_result(self):
        text = "[amethyst-autoplay] single_room result outcome=WIN character=IRONCLAD turns=5"
        result = find_single_room_result(text)
        self.assertEqual(result["outcome"], "WIN")
        self.assertEqual(result["character"], "IRONCLAD")
        self.assertEqual(result["turns"], "5")

    def test_no_result_returns_none(self):
        self.assertIsNone(find_single_room_result("regular log line"))

    def test_empty_text_returns_none(self):
        self.assertIsNone(find_single_room_result(""))


class FindHarnessLogcatCrashTest(unittest.TestCase):
    def test_finds_fatal_exception(self):
        text = "FATAL EXCEPTION: main\nProcess: com.example.app"
        result = find_harness_logcat_crash(text, "com.example.app")
        self.assertIsNotNone(result)
        self.assertEqual(result["marker"], "FATAL EXCEPTION")

    def test_no_crash_returns_none(self):
        self.assertIsNone(find_harness_logcat_crash("all good", "com.example.app"))

    def test_empty_text_returns_none(self):
        self.assertIsNone(find_harness_logcat_crash("", "com.example.app"))


class LastNonBlankLineTest(unittest.TestCase):
    def test_returns_last_non_blank(self):
        self.assertEqual(last_non_blank_line("a\n\nb\n  c  "), "c")

    def test_all_blank_returns_none(self):
        self.assertIsNone(last_non_blank_line("  \n\n  "))

    def test_none_returns_none(self):
        self.assertIsNone(last_non_blank_line(None))
