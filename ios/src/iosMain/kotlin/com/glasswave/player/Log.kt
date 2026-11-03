// Glasswave · created by nonny dev. Original work; see AUTHORS.md.
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.glasswave.player

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/** A small log, also written to Documents/glasswave.log so test runs can read it back. */
object Log {
    private val lines = ArrayDeque<String>()
    private val start = NSDate().timeIntervalSince1970

    private val file: String? by lazy {
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?.let { "$it/glasswave.log" }
    }

    fun i(msg: String) = add("I", msg)
    fun e(msg: String) = add("E", msg)

    private fun add(level: String, msg: String) {
        val t = ((NSDate().timeIntervalSince1970 - start) * 10).toLong() / 10.0
        val line = "$t $level $msg"
        println("GW $line")
        lines.addLast(line)
        while (lines.size > 400) lines.removeFirst()
        file?.let { NSString.create(string = lines.joinToString("\n")).writeToFile(it, true, NSUTF8StringEncoding, null) }
    }
}
