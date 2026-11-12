package org.spaceworks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.spaceworks.app.theme.BrandCards
import org.spaceworks.app.theme.LocalBrandDark

/**
 * Design-Kit `cards.aurora` ID/badge look for the membership card and event ticket: header band,
 * label/text inks, a Citron ribbon and an Orange tail. Layout and content are the caller's; QR codes
 * sit on a white quiet zone so scanners keep working on the dark card.
 */
@Composable fun AuroraCard(label:String,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    val dark=LocalBrandDark.current
    val background=if(dark)BrandCards.Dark.background else BrandCards.Light.background
    val text=if(dark)BrandCards.Dark.text else BrandCards.Light.text
    val labelInk=if(dark)BrandCards.Dark.label else BrandCards.Light.label
    val header=if(dark)BrandCards.Dark.header else BrandCards.Light.header
    val ribbon=if(dark)BrandCards.Dark.ribbon else BrandCards.Light.ribbon
    val tail=if(dark)BrandCards.Dark.tail else BrandCards.Light.tail
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=background,contentColor=text) {
        Column {
            Box(Modifier.fillMaxWidth().height(12.dp).background(header))
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(label,style=MaterialTheme.typography.labelLarge,color=labelInk)
                CompositionLocalProvider(LocalContentColor provides text) {content()}
            }
            Row(Modifier.fillMaxWidth().height(8.dp)) {
                Box(Modifier.weight(3f).fillMaxHeight().background(ribbon))
                Box(Modifier.weight(1f).fillMaxHeight().background(tail))
            }
        }
    }
}

/** White quiet zone around a QR code inside an [AuroraCard]. */
@Composable fun AuroraQuietZone(content:@Composable BoxScope.()->Unit) {
    Box(Modifier.fillMaxWidth().background(Color.White,RoundedCornerShape(10.dp)).padding(12.dp),content=content)
}
