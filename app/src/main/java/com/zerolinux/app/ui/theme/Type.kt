package com.zerolinux.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zerolinux.app.R

val ZlMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

val ZlMonoSmall = TextStyle(
    fontFamily = ZlMono,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.2.sp
)

val ZlMonoBody = TextStyle(
    fontFamily = ZlMono,
    fontSize = 13.sp,
    lineHeight = 19.sp
)

val ZlTypography = Typography()
