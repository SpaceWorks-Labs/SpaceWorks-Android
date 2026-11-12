"""Convert the Design-Kit WOFF2 fonts to native Android TTF resources at prepare time.

The kit ships WOFF2 only. WOFF2 is decoded losslessly with fontTools (plus brotli), either
in-process or through the `fonttools` command on PATH; outlines, names and metrics are not
edited. Without fontTools the build still succeeds: no font resources are generated and the
generated `BrandFonts` object selects the platform font (see docs/licenses/FONT-PROVENANCE.md).
"""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

# brand.json key -> Android resource name. Open-licensed (OFL) faces only: display headings use the
# sans variable font at 800 (light display at 300); a null/absent accent falls back to the sans role.
ROLES = {'font_sans': 'brand_sans', 'font_accent': 'brand_accent'}
SANS_WEIGHTS = (400, 500, 600, 700)
DISPLAY_WEIGHT = 800
DISPLAY_LIGHT_WEIGHT = 300
INSTALL_HINT = 'brew install fonttools  (or: python3 -m pip install --user fonttools brotli)'


class FontToolsMissing(RuntimeError):
    pass


def decompress(source, target):
    try:
        from fontTools.ttLib import woff2
        woff2.decompress(str(source), str(target))
        return
    except ImportError:
        pass
    tool = shutil.which('fonttools')
    if tool is None:
        raise FontToolsMissing('fontTools is unavailable; install it with: ' + INSTALL_HINT)
    result = subprocess.run([tool, 'ttLib.woff2', 'decompress', str(source), '-o', str(target)],
                            capture_output=True, text=True)
    if result.returncode != 0:
        raise ValueError(f'Cannot decode {source}: {result.stderr.strip()[-400:]}')


def native_font(source):
    data = Path(source).read_bytes()
    if data[:4] != b'wOF2':
        raise ValueError(f'{source} must be a WOFF2 font from the Design-Kit')
    with tempfile.TemporaryDirectory() as folder:
        target = Path(folder) / 'font.ttf'
        decompress(source, target)
        converted = target.read_bytes()
    if converted[:4] not in (b'\0\1\0\0', b'OTTO', b'true'):
        raise ValueError(f'{source} did not decode to an sfnt font')
    return data, converted


def sans_family_xml(path):
    """Variable Outfit at explicit weights, for SDKs that take a font resource (Stripe)."""
    family = ET.Element('font-family', {'xmlns:android': 'http://schemas.android.com/apk/res/android',
                                        'xmlns:app': 'http://schemas.android.com/apk/res-auto'})
    for weight in SANS_WEIGHTS:
        attributes = {}
        for namespace in ('android', 'app'):
            attributes.update({namespace + ':font': '@font/brand_sans', namespace + ':fontWeight': str(weight),
                               namespace + ':fontStyle': 'normal',
                               namespace + ':fontVariationSettings': f"'wght' {weight}"})
        ET.SubElement(family, 'font', attributes)
    ET.ElementTree(family).write(path, encoding='utf-8', xml_declaration=True)


def generate_brand_fonts(root, brand):
    """Return {role: manifest} for the converted roles, or {} when fontTools is unavailable."""
    root = Path(root)
    prepared = {}
    try:
        for key, name in ROLES.items():
            if brand.get(key):
                prepared[name] = (brand[key], *native_font(root / brand[key]))
    except FontToolsMissing as missing:
        print('warning: ' + str(missing) + '; using the platform font instead of the brand fonts')
        prepared = {}
    if prepared and 'brand_sans' not in prepared:
        raise ValueError('font_sans is required when any brand font is configured')
    output = root / 'app/build/generated/res/brand/font'
    if output.exists():
        shutil.rmtree(output)
    manifest = {}
    if prepared:
        output.mkdir(parents=True)
        for name, (source, original, converted) in prepared.items():
            (output / (name + '.ttf')).write_bytes(converted)
            manifest[name] = {'source': source, 'source_sha256': hashlib.sha256(original).hexdigest(),
                              'ttf_sha256': hashlib.sha256(converted).hexdigest()}
        sans_family_xml(output / 'brand_sans_family.xml')
    report = root / 'app/build/generated/brand-fonts.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    return manifest


def kotlin_source(manifest):
    """Compose families per kit role; every role degrades to sans, and sans to the platform font."""
    # FontVariation.Settings is still marked experimental in Compose text.
    lines = ['@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)', '', 'package org.spaceworks.app.theme', '',
             'import androidx.compose.ui.text.font.Font', 'import androidx.compose.ui.text.font.FontFamily',
             'import androidx.compose.ui.text.font.FontVariation', 'import androidx.compose.ui.text.font.FontWeight',
             'import org.spaceworks.app.R', '',
             '/** Generated by scripts/brand_fonts.py from branding/brand.json. */', 'object BrandFonts {']
    if not manifest:
        lines += ['    const val BUNDLED = false',
                  '    val sans: FontFamily = FontFamily.Default',
                  '    val display: FontFamily = FontFamily.Default',
                  '    val displayLight: FontFamily = FontFamily.Default',
                  '    val accent: FontFamily = FontFamily.Default',
                  '    val sdkFontResource: Int? = null', '}']
        return '\n'.join(lines) + '\n'
    def variable(resource, weight):
        # Variable fonts: pin the wght axis so each Compose weight renders its true instance.
        return (f'Font(R.font.{resource}, FontWeight.W{weight}, '
                f'variationSettings = FontVariation.Settings(FontVariation.weight({weight})))')
    sans = ', '.join(variable('brand_sans', w) for w in SANS_WEIGHTS)
    lines += ['    const val BUNDLED = true', f'    val sans: FontFamily = FontFamily({sans})']
    lines.append(f"    val display: FontFamily = FontFamily({variable('brand_sans', DISPLAY_WEIGHT)})")
    lines.append(f"    val displayLight: FontFamily = FontFamily({variable('brand_sans', DISPLAY_LIGHT_WEIGHT)})")
    lines.append('    val accent: FontFamily = ' + (f"FontFamily({variable('brand_accent', 500)}, {variable('brand_accent', 600)})"
                                                   if 'brand_accent' in manifest else 'sans'))
    lines += ['    val sdkFontResource: Int? = R.font.brand_sans_family', '}']
    return '\n'.join(lines) + '\n'
