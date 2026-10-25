package com.muimex11.official

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Native Toast wrapper replacing the unmaintained Toasty dependency.
 * Same call-site shape as before: UiToast.success(ctx, msg, Toast.LENGTH_SHORT).show()
 * is replaced by UiToast.success(ctx, msg) — duration defaults to SHORT.
 * Icons are intentionally dropped to avoid custom view toasts (banned on API 30+).
 */
object UiToast {
    fun success(ctx: Context, msg: String, duration: Int = Toast.LENGTH_SHORT) =
        make(ctx, msg, duration)

    fun error(ctx: Context, msg: String, duration: Int = Toast.LENGTH_SHORT) =
        make(ctx, msg, duration)

    fun warning(ctx: Context, msg: String, duration: Int = Toast.LENGTH_SHORT) =
        make(ctx, msg, duration)

    fun info(ctx: Context, msg: String, duration: Int = Toast.LENGTH_SHORT) =
        make(ctx, msg, duration)

    private fun make(ctx: Context, msg: String, duration: Int): Toast {
        // Toast must be created/shown on main thread.
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(ctx.applicationContext, msg, duration)
        } else {
            var t: Toast? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                t = Toast.makeText(ctx.applicationContext, msg, duration)
                latch.countDown()
            }
            try {
                latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
            t ?: Toast.makeText(ctx.applicationContext, msg, duration)
        }
    }
}
