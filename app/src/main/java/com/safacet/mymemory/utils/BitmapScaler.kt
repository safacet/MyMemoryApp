package com.safacet.mymemory.utils

import android.graphics.Bitmap

object BitmapScaler {

    //Scale and maintain aspect ratio given a desired width
    fun scaleToFitWidth(b: Bitmap, width: Int): Bitmap {
        val factor = width / b.width.toFloat()
        return Bitmap.createScaledBitmap(b, width, (b.height * factor).toInt(),true)
    }

    //Scale and maintain aspect ratio given a desired height
    fun scaleToFitHeight(b: Bitmap, height: Int): Bitmap {
        val factor = height / b.height.toFloat()
        return Bitmap.createScaledBitmap(b, (b.width*factor).toInt(), height, true)
    }
}
