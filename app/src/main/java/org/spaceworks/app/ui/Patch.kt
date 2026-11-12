package org.spaceworks.app.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.spaceworks.app.R
import org.spaceworks.app.platform.LocalReducedMotion
import org.spaceworks.app.platform.rememberMobileText
import org.spaceworks.app.theme.LocalBrandDark

/**
 * Patch, the Design-Kit mascot (design/mascot, compressed product-UI WebPs, unaltered). Dark surfaces use
 * the approved `dark/` sticker-border files. [animated] plays the 7 s loop only when system animations are
 * on (API 28+); otherwise the idle frame is shown. Patch is a layout's one bold moment: never pair it with
 * an Unbounded accent phrase on the same screen.
 */
@Composable fun Patch(size:Dp,modifier:Modifier=Modifier,animated:Boolean=false) {
    val copyText=rememberMobileText()
    val dark=LocalBrandDark.current
    val play=animated && !LocalReducedMotion.current
    Box(modifier.size(size).semantics {contentDescription=copyText(DesignStrings.DESIGN_PATCH_DESCRIPTION);role=Role.Image}) {
        if(play && Build.VERSION.SDK_INT>=Build.VERSION_CODES.P) AnimatedPatch(if(dark)R.raw.patch_dark_animated else R.raw.patch_light_animated,size)
        else Image(painterResource(if(dark)R.drawable.patch_dark else R.drawable.patch_light),null,
            // The light idle frame is the loop's first frame without its 272 px canvas; restore that inset.
            if(dark)Modifier.size(size) else Modifier.size(size).padding(start=size*14/272,top=size*24/272,end=size*12/272,bottom=size*9/272),
            contentScale=ContentScale.Fit)
    }
}

@RequiresApi(Build.VERSION_CODES.P) @Composable private fun AnimatedPatch(resource:Int,size:Dp) {
    val context=LocalContext.current
    val drawable=produceState<Drawable?>(null,resource) {
        value=withContext(Dispatchers.IO) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources,resource))
        }
    }.value
    AndroidView({ImageView(it).apply {importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;scaleType=ImageView.ScaleType.FIT_CENTER}},
        Modifier.size(size),onRelease={view -> (view.drawable as? AnimatedImageDrawable)?.stop()}) {view ->
        if(view.drawable!==drawable) {
            (view.drawable as? AnimatedImageDrawable)?.stop()
            view.setImageDrawable(drawable)
            (drawable as? AnimatedImageDrawable)?.start()
        }
    }
}
