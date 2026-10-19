# SPDX-License-Identifier: GPL-3.0-only
# Copyright (c) 2026 vossgraves
from pathlib import Path
import tempfile
import unittest
import wave

from scripts.android_qa import PACKAGE, session_state, write_fixture


class AndroidQaTest(unittest.TestCase):
    def test_media_state_cannot_be_taken_from_another_apps_playing_session(self):
        dump = (
            f"  package={PACKAGE}\n  state=PlaybackState {{state=2, position=2140}}\n"
            "  metadata: archivetune-qa.wav\n"
            "  package=other.player\n  state=PlaybackState {state=3, position=9000}\n"
        )
        self.assertEqual((2, 2140, "archivetune-qa.wav"), session_state(dump))
        self.assertIsNone(session_state(dump.replace(PACKAGE, "unrelated.player")))

    def test_synthetic_fixture_is_seekable_pcm_not_an_empty_placeholder(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tone.wav"
            write_fixture(path)
            with wave.open(str(path), "rb") as audio:
                self.assertEqual((1, 2, 22050, 30 * 22050), (
                    audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getnframes(),
                ))
                audio.setpos(22050)
                self.assertNotEqual(b"\0" * 200, audio.readframes(100))


if __name__ == "__main__":
    unittest.main()
