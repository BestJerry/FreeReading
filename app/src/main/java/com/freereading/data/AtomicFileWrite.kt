package com.freereading.data

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 同步并确认原子文件已提交；AtomicFile 的部分同步／重命名失败只写日志，不会抛异常。
 * 通过长度和临时文件检查向调用方报告提交异常，避免将未确认的写入视为成功。
 */
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
