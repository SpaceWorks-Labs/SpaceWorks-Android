import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('brand_fonts', ROOT/'scripts/brand_fonts.py')
FONTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FONTS)
BRAND = json.loads((ROOT/'branding/brand.json').read_text())
ANDROID = '{http://schemas.android.com/apk/res/android}'


def kit_brand():
    return {key: str((ROOT/value).resolve()) for key, value in BRAND.items() if key.startswith('font_') and value}


def fonttools_available():
    try:
        FONTS.native_font(ROOT/BRAND['font_sans'])
        return True
    except FONTS.FontToolsMissing:
        return False


class BrandFontsTest(unittest.TestCase):
    def test_brand_uses_only_open_licensed_faces(self):
        fonts = {key: value for key, value in BRAND.items() if key.startswith('font_')}
        self.assertEqual(fonts, {
            'font_sans': 'design/fonts/Outfit-Variable.woff2',
            'font_accent': 'design/fonts/Unbounded-Variable.woff2'})
        self.assertEqual(set(FONTS.ROLES), set(fonts))
        self.assertNotIn('gilroy', json.dumps(BRAND).lower())

    @unittest.skipUnless(fonttools_available(), 'fontTools is not installed: ' + FONTS.INSTALL_HINT)
    def test_kit_woff2_decode_to_deterministic_native_resources(self):
        with tempfile.TemporaryDirectory() as directory:
            first = FONTS.generate_brand_fonts(directory, kit_brand())
            second = FONTS.generate_brand_fonts(directory, kit_brand())
            self.assertEqual(first, second)
            self.assertEqual(set(first), set(FONTS.ROLES.values()))
            fonts = Path(directory)/'app/build/generated/res/brand/font'
            for name in FONTS.ROLES.values():
                self.assertIn((fonts/(name + '.ttf')).read_bytes()[:4], (b'\0\1\0\0', b'OTTO'))
            family = ET.parse(fonts/'brand_sans_family.xml').getroot()
            self.assertEqual([node.get(ANDROID + 'fontWeight') for node in family], ['400', '500', '600', '700'])
            self.assertEqual(family[3].get(ANDROID + 'fontVariationSettings'), "'wght' 700")
            source = FONTS.kotlin_source(first)
            self.assertIn('const val BUNDLED = true', source)
            self.assertIn('FontVariation.weight(700)', source)
            self.assertIn('val display: FontFamily = FontFamily(Font(R.font.brand_sans, FontWeight.W800', source)
            self.assertIn('val displayLight: FontFamily = FontFamily(Font(R.font.brand_sans, FontWeight.W300', source)
            self.assertNotIn('brand_display', source)

    def test_without_fonttools_the_platform_font_is_used_and_stale_fonts_are_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            stale = Path(directory)/'app/build/generated/res/brand/font/brand_sans.ttf'
            stale.parent.mkdir(parents=True)
            stale.write_bytes(b'old')
            with mock.patch.object(FONTS, 'native_font', side_effect=FONTS.FontToolsMissing('missing')):
                manifest = FONTS.generate_brand_fonts(directory, kit_brand())
            self.assertEqual(manifest, {})
            self.assertFalse(stale.exists())
            source = FONTS.kotlin_source(manifest)
            self.assertIn('const val BUNDLED = false', source)
            self.assertIn('val sdkFontResource: Int? = null', source)
            self.assertNotIn('R.font', source)

    def test_non_woff2_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            bad = Path(directory)/'broken.woff2'
            bad.write_bytes(b'not a font')
            with self.assertRaises(ValueError):
                FONTS.native_font(bad)


if __name__ == '__main__':
    unittest.main()
