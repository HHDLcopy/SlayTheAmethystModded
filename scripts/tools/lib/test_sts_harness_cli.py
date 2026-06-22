from __future__ import annotations

import unittest

from scripts.tools.lib.sts_harness_cli import create_parser


class DecompilCliTest(unittest.TestCase):
    def test_decompil_command_in_choices(self):
        parser = create_parser()
        command_actions = [action for action in parser._actions if action.dest == "command"]
        self.assertEqual(len(command_actions), 1)
        self.assertIn("decompil", command_actions[0].choices)

    def test_decompil_targets_single(self):
        parser = create_parser()
        args = parser.parse_args([
            "-Command", "decompil",
            "-Target", "com.megacrit.cardcrawl.cards.AbstractCard",
        ])
        self.assertEqual(args.command, "decompil")
        self.assertEqual(args.decompil_targets, ["com.megacrit.cardcrawl.cards.AbstractCard"])

    def test_decompil_targets_multiple(self):
        parser = create_parser()
        args = parser.parse_args([
            "-Command", "decompil",
            "-Target", "com.megacrit.cardcrawl.cards.AbstractCard",
            "-Target", "com.megacrit.cardcrawl.cards.AbstractCard#applyPowers",
        ])
        self.assertEqual(len(args.decompil_targets), 2)
        self.assertIn("com.megacrit.cardcrawl.cards.AbstractCard", args.decompil_targets)
        self.assertIn("com.megacrit.cardcrawl.cards.AbstractCard#applyPowers", args.decompil_targets)

    def test_decompil_accepts_empty_targets_at_cli_level(self):
        parser = create_parser()
        args = parser.parse_args(["-Command", "decompil"])
        self.assertEqual(args.command, "decompil")
        self.assertEqual(args.decompil_targets, [])


if __name__ == "__main__":
    unittest.main()
