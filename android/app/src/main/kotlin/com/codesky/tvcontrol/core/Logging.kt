package com.codesky.tvcontrol.core

import android.util.Log

private const val TAG = "TvControl"

object Logging {
    fun d(message: String) = Log.d(TAG, message)
    fun i(message: String) = Log.i(TAG, message)
    fun w(message: String, t: Throwable? = null) = Log.w(TAG, message, t)
    fun e(message: String, t: Throwable? = null) = Log.e(TAG, message, t)
}
