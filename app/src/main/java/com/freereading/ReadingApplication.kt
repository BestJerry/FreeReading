package com.freereading

import android.app.Application
import com.freereading.data.BookRepository

/** 应用级依赖入口，让书架、阅读页与后台听书服务共用同一个本地书库实例。 */
class ReadingApplication : Application() {
    // 首次访问时创建，避免应用启动阶段提前初始化尚未使用的读书依赖。
    val repository: BookRepository by lazy { BookRepository(this) }
}
