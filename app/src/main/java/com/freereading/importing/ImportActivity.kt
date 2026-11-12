package com.freereading.importing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.freereading.R
import com.freereading.reader.ReaderActivity
import com.freereading.ui.Forest
import com.freereading.ui.FreeReadingTheme
import kotlinx.coroutines.launch

class ImportActivity : ComponentActivity() {
    private val model: ExternalImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (model.state.value == ExternalImportState.Ready) model.accept(intent)
        setContent {
            FreeReadingTheme {
                val state by model.state.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Forest)
                        Spacer(Modifier.height(24.dp))
                        val failure = state as? ExternalImportState.Failed
                        if (failure == null) {
                            Text(stringResource(R.string.external_import_title), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.external_import_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(28.dp))
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Spacer(Modifier.height(20.dp))
                            TextButton(onClick = ::finish) { Text(stringResource(R.string.cancel)) }
                        } else {
                            Text(stringResource(R.string.operation_failed), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(12.dp))
                            Text(failure.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(24.dp))
                            if (failure.canRetry) Button(onClick = model::retry) { Text(stringResource(R.string.retry)) }
                            TextButton(onClick = ::finish) { Text(stringResource(R.string.external_import_back)) }
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { state ->
                    if (state is ExternalImportState.Imported) {
                        startActivity(ReaderActivity.intent(this@ImportActivity, state.bookId))
                        model.markOpened(state.bookId)
                        finish()
                    } else if (state == ExternalImportState.Finished) {
                        finish()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.accept(intent)
    }
}
