package com.example.data

object SafeLog {
    fun d(tag: String, msg: String) {
        try {
            android.util.Log.d(tag, msg)
        } catch (_: Throwable) {
            println("DEBUG: [$tag] $msg")
        }
    }

    fun i(tag: String, msg: String) {
        try {
            android.util.Log.i(tag, msg)
        } catch (_: Throwable) {
            println("INFO: [$tag] $msg")
        }
    }

    fun w(tag: String, msg: String) {
        try {
            android.util.Log.w(tag, msg)
        } catch (_: Throwable) {
            println("WARN: [$tag] $msg")
        }
    }

    fun e(tag: String, msg: String) {
        try {
            android.util.Log.e(tag, msg)
        } catch (_: Throwable) {
            println("ERROR: [$tag] $msg")
        }
    }
}
