"""Generate native semantic colors, motion, fonts and Android vector icons from brand.json inputs."""
from pathlib import Path
import json,xml.etree.ElementTree as ET,re
from brand_links import generate_brand_links
from brand_fonts import generate_brand_fonts, kotlin_source as fonts_source

PAIRS={'primary':'accent','onPrimary':'onAccent','primaryContainer':'accentSoft','onPrimaryContainer':'accentInk','secondary':'emphasis','onSecondary':'onEmphasis','secondaryContainer':'emphasisSoft','onSecondaryContainer':'ink','tertiary':'info','onTertiary':'onInfo','background':'bg','onBackground':'ink','surface':'surface','onSurface':'ink','surfaceVariant':'panel','onSurfaceVariant':'muted','outline':'outline','outlineVariant':'line','error':'danger','onError':'onDanger','errorContainer':'danger','onErrorContainer':'onDanger','inverseSurface':'ink','inverseOnSurface':'bg','inversePrimary':'accentInk','surfaceTint':'accent','tertiaryContainer':'infoSoft','onTertiaryContainer':'infoInk','surfaceDim':'surface','surfaceBright':'panel','surfaceContainerLowest':'bg','surfaceContainerLow':'surface','surfaceContainer':'panel','surfaceContainerHigh':'panel','surfaceContainerHighest':'hover','scrim':'scrim'}

def camel(name): return re.sub(r'-([a-z])',lambda m:m[1].upper(),name)
def color(value): return 'Color(0xFF'+value.lstrip('#').upper()+')'
def millis(value):
    match=re.fullmatch(r'(\d+)ms',str(value))
    if not match: raise ValueError(f'Motion duration {value!r} must be whole milliseconds')
    return int(match[1])
def bezier(value):
    match=re.fullmatch(r'cubic-bezier\(([^)]*)\)',str(value))
    if not match: raise ValueError(f'Motion easing {value!r} must be a cubic-bezier()')
    points=[float(x) for x in match[1].split(',')]
    if len(points)!=4: raise ValueError(f'Motion easing {value!r} needs four control points')
    return 'floatArrayOf('+', '.join(f'{x}f' for x in points)+')'

def theme_source(tokens,motion):
    """BrandTokens/BrandMotion/BrandCards Kotlin. Kit motion is authoritative; the app file names kit tokens."""
    lines=['package org.spaceworks.app.theme','', 'import androidx.compose.ui.graphics.Color','import androidx.compose.material3.*','', 'object BrandTokens {']
    for mode in ['light','dark']:
        lines+=['    object '+mode.title()+' {']+['        val '+camel(name)+' = '+color(value) for name,value in tokens[mode].items()]+['    }']
    lines+=['}']
    kit=tokens.get('motion',{})
    durations={name:millis(value) for name,value in kit.get('duration',{}).items()}
    easings=kit.get('easing',{})
    lines+=['object BrandMotion {']
    lines+=['    const val '+name.upper()+' = '+str(value) for name,value in durations.items()]
    for key,name in motion['duration_ms'].items():
        if name not in durations: raise ValueError(f'mobile-motion duration {key} names unknown kit duration {name!r}')
        lines+=['    const val '+key.upper()+' = '+name.upper()]
    lines+=['    val '+camel(name)+'Easing = '+bezier(value) for name,value in easings.items()]
    for key,name in motion['easing'].items():
        if name not in easings: raise ValueError(f'mobile-motion easing {key} names unknown kit easing {name!r}')
        lines+=['    val '+camel(key.replace('_','-'))+'Easing = '+camel(name)+'Easing']
    distance=kit.get('distance',{})
    if 'enter-y' in distance: lines+=['    const val ENTER_Y_DP = '+str(float(distance['enter-y'].removesuffix('px')))+'f']
    if 'pop-scale' in distance: lines+=['    const val POP_SCALE = '+str(float(distance['pop-scale']))+'f']
    lines+=['    const val BLOCKING_DELAY = '+str(motion['onboarding']['blocking_delay_ms']),
        '    const val REDUCED_OPACITY_DURATION = '+str(motion['reduce_motion']['opacity_duration_ms']),
        '    const val REDUCED_SPATIAL = '+str(motion['reduce_motion']['spatial_transitions']).lower(),
        '    fun duration(millis: Int, reduced: Boolean) = if (reduced) REDUCED_OPACITY_DURATION else millis','}']
    aurora=tokens.get('cards',{}).get('aurora')
    if aurora:
        lines+=['/** Design-Kit `cards.aurora`: membership card and event ticket look. */','object BrandCards {']
        for mode in ['light','dark']:
            lines+=['    object '+mode.title()+' {']+['        val '+camel(name)+' = '+color(value) for name,value in aurora[mode].items()]+['    }']
        lines+=['}']
    for mode in ['light','dark']:
        t='BrandTokens.'+mode.title()+'.'
        pairs=dict(PAIRS) if 'scrim' in tokens[mode] else {**PAIRS,'scrim':'ink'}
        lines+=['val '+mode.title()+'Scheme = '+mode+'ColorScheme('+', '.join(k+' = '+t+v for k,v in pairs.items())+')']
    return '\n'.join(lines)+'\n'

def native_vector(root,source,target,width,height):
    svg=ET.parse(source).getroot()
    view=svg.attrib.get('viewBox','0 0 512 512').split()
    vector=ET.Element('vector',{'xmlns:android':'http://schemas.android.com/apk/res/android','android:width':str(width)+'dp','android:height':str(height)+'dp','android:viewportWidth':view[2],'android:viewportHeight':view[3]})
    for node in svg.iter():
        tag=node.tag.split('}')[-1]
        if tag=='rect':
            x=float(node.get('x','0'));y=float(node.get('y','0'));ww=float(node.get('width'));hh=float(node.get('height'))
            r=min(float(node.get('rx','0')),ww/2,hh/2)
            d=f'M{x+r},{y}h{ww-2*r}a{r},{r} 0 0 1 {r},{r}v{hh-2*r}a{r},{r} 0 0 1 {-r},{r}h{-ww+2*r}a{r},{r} 0 0 1 {-r},{-r}v{-hh+2*r}a{r},{r} 0 0 1 {r},{-r}z' if r else f'M{x},{y}h{ww}v{hh}h-{ww}z'
        elif tag=='path':d=node.get('d')
        else:continue
        ET.SubElement(vector,'path',{'android:fillColor':node.get('fill','#142C2A'),'android:pathData':d,'android:fillType':'evenOdd' if node.get('fill-rule')=='evenodd' else 'nonZero'})
    res=root/'app/build/generated/res/brand/drawable';res.mkdir(parents=True,exist_ok=True)
    ET.ElementTree(vector).write(res/target,encoding='utf-8',xml_declaration=True)

def main():
    root=Path(__file__).resolve().parents[1]
    brand=json.loads((root/'branding/brand.json').read_text(encoding='utf-8'))
    generate_brand_links(root, brand)
    fonts=generate_brand_fonts(root, brand)
    tokens=json.loads((root/brand['theme_tokens']).read_text(encoding='utf-8'))
    motion=json.loads((root/'tokens/mobile-motion.json').read_text(encoding='utf-8'))
    source=theme_source(tokens,motion)
    out=root/'app/build/generated/source/brand/org/spaceworks/app/theme';out.mkdir(parents=True,exist_ok=True)
    (out/'BrandTokens.kt').write_text(source)
    (out/'BrandFonts.kt').write_text(fonts_source(fonts))
    native_vector(root,root/brand["app_icon"],"ic_brand.xml",108,108)
    native_vector(root,root/brand["logo_symbol"],"brand_symbol.xml",52,48)
    native_vector(root,root/brand["logo_symbol_reversed"],"brand_symbol_reversed.xml",52,48)
    native_vector(root,root/brand["logo_symbol_one_colour_ink"],"brand_symbol_one_colour_ink.xml",52,48)
    native_vector(root,root/brand["logo_stacked"],"brand_stacked.xml",180,64)
    native_vector(root,root/brand["logo_stacked_reversed"],"brand_stacked_reversed.xml",180,64)
    native_vector(root,root/brand["logo_stacked_one_colour_ink"],"brand_stacked_one_colour_ink.xml",180,64)

if __name__=='__main__':
    main()
