package com.freereading.data

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** AtomicFile can log sync/rename failures without throwing; callers need an actual commit point. */
internal fun AtomicFile.finishWriteChecked(output: FileOutputStream) {
    val expectedLength = output.channel.size()
    output.fd.sync()
    finishWrite(output)
    val target = baseFile
    if (!target.isFile || target.length() != expectedLength ||
        File(target.path + ".new").exists() || File(target.path + ".bak").exists()) {
        throw IOException("Atomic file was not committed")
    }
}
