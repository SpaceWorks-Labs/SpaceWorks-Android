# Brand font provenance

Source: the Design-Kit submodule `design/` at `b2aa5bb` (SpaceWorks-Labs/SpaceWorks-Design `main`, 0.12.0),
`fonts/README.md` and `docs/ui-brand-spec.md` D.3 (decision 299).

| Role | Kit file | Android resource | Licence |
|---|---|---|---|
| Body, labels, controls, titles (400–700) | `Outfit-Variable.woff2` | `brand_sans` (+ `brand_sans_family` XML at 400/500/600/700 for SDKs) | SIL OFL 1.1, `OFL-Outfit.txt` |
| Display headings (800) and light display text (300) | `Outfit-Variable.woff2` | `brand_sans` at `wght` 800 / 300 | SIL OFL 1.1, `OFL-Outfit.txt` |
| One short accent phrase per screen (500–600) | `Unbounded-Variable.woff2` | `brand_accent` | SIL OFL 1.1, `OFL-Unbounded.txt` |

The kit ships WOFF2 only. `scripts/brand_fonts.py` decodes each file to a TTF during `prepareBrand`
with fontTools (WOFF2 decompression only; outlines, names, metrics and licence metadata are not edited)
and writes them to `app/build/generated/res/brand/font/`, with SHA-256 hashes of every input and output
in `app/build/generated/brand-fonts.json`. No converted font is committed.

fontTools needs brotli. Install either `brew install fonttools` (bundles brotli) or
`python3 -m pip install --user fonttools brotli`. Without it the build still succeeds: no font
resources are generated, `BrandFonts.BUNDLED` is false and every role uses the platform font (a
warning is printed). Release builds should always be made with fontTools present.

Variable fonts pin the `wght` axis per Compose weight (`FontVariation.weight`), so each weight renders its
true instance. Material's native sp sizes and line heights are kept; system font scaling and bold-text
adjustment keep working.

## Licences bundled in the APK

The full OFL texts for Outfit and Unbounded are copied byte-for-byte from `design/fonts/LICENSES/` into
`app/src/main/assets/licenses/` (and `docs/licenses/`); `scripts/check_native_packaging.py` requires both
exact texts in each APK.

## Open-licensed fonts only (owner decision 2026-11-13)

Only SIL OFL fonts are bundled: Outfit (body, interface and display text) and Unbounded (one short
accent phrase per screen). No Gilroy file, in any format, is bundled, converted or referenced by the
build; `scripts/tests/test_brand_fonts.py` fails if `branding/brand.json` names any other font.
