package com.maodouchat.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Compose `LocalContext` is often a [ContextWrapper], not the Activity.
 * Casting `context as? MainActivity` therefore silently no-ops FLAG_SECURE
 * notify and ScreenCaptureCallback registration.
 */
fun Context.findActivity(): Activity? = ActivityLookup.unwrap(this)

object ActivityLookup {
    fun unwrap(context: Context?): Activity? {
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }
}
