"""Theme generator: Design-Kit 0.12.0 tokens, scrim, motion and Aurora card values."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location('prepare', SCRIPTS / 'prepare.py')
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)
ROOT = SCRIPTS.parent
TOKENS = json.loads((ROOT / 'design/tokens/tokens.json').read_text(encoding='utf-8'))
MOTION = json.loads((ROOT / 'tokens/mobile-motion.json').read_text(encoding='utf-8'))


class BrandThemeTest(unittest.TestCase):
    def setUp(self):
        self.source = PREPARE.theme_source(TOKENS, MOTION)

    def test_solid_black_dark_palette_and_scrim_token(self):
        for line in ('val bg = Color(0xFF000000)', 'val surface = Color(0xFF0B0B0B)', 'val panel = Color(0xFF141414)',
                     'val scrim = Color(0xFF000000)', 'val scrim = Color(0xFF142C2A)', 'val bg = Color(0xFFFFF4E8)'):
            self.assertIn(line, self.source)
        self.assertIn('scrim = BrandTokens.Dark.scrim', self.source)
        self.assertIn('scrim = BrandTokens.Light.scrim', self.source)

    def test_kit_motion_drives_app_durations_and_easing(self):
        for line in ('const val FAST = 120', 'const val BASE = 180', 'const val SLOW = 240',
                     'const val OVERLAY_ENTER = BASE', 'const val OVERLAY_EXIT = FAST',
                     'val enterEasing = floatArrayOf(0.16f, 1.0f, 0.3f, 1.0f)', 'val overlayEnterEasing = enterEasing',
                     'const val ENTER_Y_DP = 8.0f', 'const val POP_SCALE = 0.98f'):
            self.assertIn(line, self.source)

    def test_unknown_kit_motion_name_is_rejected(self):
        broken = json.loads(json.dumps(MOTION))
        broken['duration_ms']['expand'] = 'glacial'
        with self.assertRaises(ValueError):
            PREPARE.theme_source(TOKENS, broken)

    def test_aurora_card_tokens_are_generated(self):
        self.assertIn('object BrandCards', self.source)
        self.assertIn('val ribbon = Color(0xFFDFFF8F)', self.source)
        self.assertIn('val background = Color(0xFF142C2A)', self.source)

    def test_tokens_without_scrim_fall_back_to_ink(self):
        legacy = {'light': {'ink': '#111111'}, 'dark': {'ink': '#EEEEEE'}, 'motion': TOKENS['motion']}
        source = PREPARE.theme_source(legacy, MOTION)
        self.assertIn('scrim = BrandTokens.Light.ink', source)
        self.assertNotIn('BrandCards', source)


if __name__ == '__main__':
    unittest.main()
