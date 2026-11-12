package com.freereading

import android.app.Application
import com.freereading.data.BookRepository

class ReadingApplication : Application() {
    val repository: BookRepository by lazy { BookRepository(this) }
}
