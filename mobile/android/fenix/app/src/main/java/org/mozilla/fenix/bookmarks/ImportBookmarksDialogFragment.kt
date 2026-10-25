/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.bookmarks

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.withStarted
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.bookmark.parser.BookmarksFileParser
import mozilla.components.concept.bookmarks.file.BookmarksFileImporter
import mozilla.components.feature.importer.BookmarkImporter
import mozilla.components.feature.importer.R as importerR
import mozilla.components.feature.importer.ImporterEvent
import mozilla.components.lib.bookmark.parser.jsoup.jsoupParser
import mozilla.components.lib.bookmarks.file.htmlImporter
import org.mozilla.fenix.R
import org.mozilla.fenix.bookmarks.importer.FenixBookmarkImporterError
import org.mozilla.fenix.bookmarks.importer.FenixImporterEvent
import org.mozilla.fenix.bookmarks.importer.toFenixError
import org.mozilla.fenix.dejavu.settings.workspaceRepository
import org.mozilla.fenix.dejavu.ui.DejavuDialog
import org.mozilla.fenix.dejavu.ui.DejavuDialogButton
import org.mozilla.fenix.dejavu.ui.LocalPopupTheme
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.theme.FirefoxTheme

internal class ImportBookmarksDialogFragment : DialogFragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, 0)
    }

    override fun onStart() {
        super.onStart()
        // The fragment's own window stays out of sight: the importer shows the file picker and then its own dialog.
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = content {
        FirefoxTheme {
            val repository = remember { workspaceRepository() }
            val workspaces by repository.state.collectAsState()
            CompositionLocalProvider(LocalPopupTheme provides workspaces.activeWorkspace?.theme) {
                ImportBookmarks()
            }
        }
    }

    @Composable
    private fun ImportBookmarks() {
        BookmarkImporter(
            importer =
                BookmarksFileImporter.htmlImporter(
                    context = requireContext(),
                    parentGuid = BookmarkRoot.Mobile.id,
                    parser =
                        BookmarksFileParser.jsoupParser(
                            rootFolderName =
                                requireContext().getString(R.string.bookmark_import_destination_default_name)
                        ),
                    inserter = requireComponents.core.bookmarksStorage,
                ),
            onEventReceived = { event ->
                parentFragmentManager.setFragmentResult(
                    REQUEST_KEY,
                    event.resultBundle(),
                )
                dismissWhenFinished(event, lifecycle) { dismiss() }
            },
            loadingDialog = { started, cancel -> ImportingDialog(onImportStarted = started, onCancel = cancel) },
        )
    }

    companion object {
        const val REQUEST_KEY = "import_bookmarks_request"
        const val KEY_RESULT = "result"
        const val KEY_ERROR_TYPE = "result_error_type"
        const val KEY_SUCCESS_IMPORT_COUNT = "result_success_import_count"
        internal const val RESULT_STARTED = "started"
        internal const val RESULT_SUCCESS = "success"
        internal const val RESULT_FAILURE = "failure"
        internal const val RESULT_CANCELLED = "cancelled"
        const val TAG = "import_dialog"

        fun decodeResult(bundle: Bundle): FenixImporterEvent? =
            when (bundle.getString(KEY_RESULT)) {
                RESULT_STARTED -> FenixImporterEvent.Started
                RESULT_SUCCESS -> FenixImporterEvent.Success(importCount = bundle.getInt(KEY_SUCCESS_IMPORT_COUNT, 0))

                RESULT_FAILURE -> {
                    val errorOrdinal = bundle.getInt(KEY_ERROR_TYPE, FenixBookmarkImporterError.UNKNOWN_ERROR.ordinal)
                    FenixImporterEvent.Failure(error = FenixBookmarkImporterError.entries[errorOrdinal])
                }

                RESULT_CANCELLED -> FenixImporterEvent.Canceled
                else -> null
            }
    }
}

/** The bookmarks being imported, in Dejavu's look; it starts the import once it shows. */
@Composable
private fun ImportingDialog(onImportStarted: () -> Unit, onCancel: () -> Unit) {
    LaunchedEffect(Unit) { onImportStarted() }
    DejavuDialog(
        title = stringResource(importerR.string.mozac_feature_importer_dialog_title),
        onDismissRequest = {},
        buttons = {
            DejavuDialogButton(
                text = stringResource(importerR.string.mozac_feature_importer_dialog_cancel),
                onClick = onCancel,
            )
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Text(text = stringResource(importerR.string.mozac_feature_importer_dialog_description))
        }
    }
}

@VisibleForTesting
internal fun dismissWhenFinished(event: ImporterEvent, lifecycle: Lifecycle, dismiss: () -> Unit) {
    if (event is ImporterEvent.Started) {
        return
    }

    lifecycle.coroutineScope.launch { lifecycle.withStarted(dismiss) }
}

private fun ImporterEvent.resultBundle(): Bundle {
    val status =
        when (this) {
            is ImporterEvent.Started -> ImportBookmarksDialogFragment.RESULT_STARTED
            is ImporterEvent.Success -> ImportBookmarksDialogFragment.RESULT_SUCCESS
            is ImporterEvent.Failure -> ImportBookmarksDialogFragment.RESULT_FAILURE
            is ImporterEvent.Canceled -> ImportBookmarksDialogFragment.RESULT_CANCELLED
        }

    val importCount = (this as? ImporterEvent.Success)?.importCount
    val error = (this as? ImporterEvent.Failure)?.error?.toFenixError()

    return Bundle().apply {
        putString(ImportBookmarksDialogFragment.KEY_RESULT, status)
        importCount?.let {
            putInt(ImportBookmarksDialogFragment.KEY_SUCCESS_IMPORT_COUNT, it)
        }
        error?.let {
            putInt(ImportBookmarksDialogFragment.KEY_ERROR_TYPE, error.ordinal)
        }
    }
}
