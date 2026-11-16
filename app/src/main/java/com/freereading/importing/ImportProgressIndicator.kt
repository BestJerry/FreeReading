package com.freereading.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.freereading.R
import com.freereading.data.BookImportProgress
import com.freereading.data.ImportStage

/** Reports the current import step; percentages deliberately do not imply overall completion. */
@Composable
internal fun ImportProgressIndicator(progress: BookImportProgress, modifier: Modifier = Modifier) {
    val fraction = progress.fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val stage = stringResource(when (progress.stage) {
        ImportStage.WAITING -> R.string.import_progress_waiting
        ImportStage.COPYING -> R.string.import_progress_copying
        ImportStage.VALIDATING -> R.string.import_progress_validating
        ImportStage.INDEXING -> R.string.import_progress_indexing
        ImportStage.SAVING -> R.string.import_progress_saving
        ImportStage.COMPLETED -> R.string.import_progress_completed
    })
    val percent = progress.percent?.let {
        stringResource(if (progress.stage == ImportStage.COMPLETED) R.string.import_progress_percent
            else R.string.import_progress_step_percent, it)
    }
    val stepNumber = when (progress.stage) {
        ImportStage.COPYING -> 1
        ImportStage.VALIDATING -> 2
        ImportStage.INDEXING -> 3
        ImportStage.SAVING -> 4
        else -> null
    }
    val step = stepNumber?.let { stringResource(R.string.import_progress_step, it) }
    val total = progress.total?.takeIf { it > 0 }
    val completed = progress.completed.coerceAtLeast(0).let { if (total == null) it else it.coerceAtMost(total) }
    val detail = when (progress.stage) {
        ImportStage.COPYING -> when {
            total != null -> stringResource(R.string.import_progress_copy_known, completed / MEBIBYTE, total / MEBIBYTE)
            completed > 0 -> stringResource(R.string.import_progress_copy_unknown, completed / MEBIBYTE)
            else -> null
        }
        ImportStage.INDEXING -> when {
            total != null -> stringResource(R.string.import_progress_index_known, completed, total)
            completed > 0 -> stringResource(R.string.import_progress_index_unknown, completed)
            else -> null
        }
        else -> null
    }
    val description = stringResource(R.string.import_progress_description)
    val accessibilityState = listOfNotNull(stage, step, percent, detail).joinToString("，")
    Column(modifier.fillMaxWidth().testTag("import_progress"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stage, modifier = Modifier.weight(1f).padding(end = 12.dp).testTag("import_progress_stage"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (percent != null) Text(percent, modifier = Modifier.testTag("import_progress_percent"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        val barModifier = Modifier.fillMaxWidth().height(4.dp).testTag("import_progress_bar").semantics {
            contentDescription = description
            stateDescription = accessibilityState
        }
        if (fraction == null) {
            LinearProgressIndicator(modifier = barModifier)
        } else {
            // Capture this immutable value so semantics can never observe a later zero/unknown total.
            LinearProgressIndicator(progress = { fraction }, modifier = barModifier)
        }
        if (step != null) {
            Text(if (detail == null) step else stringResource(R.string.import_progress_step_detail, step, detail),
                modifier = Modifier.testTag("import_progress_detail"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val MEBIBYTE = 1_048_576.0
