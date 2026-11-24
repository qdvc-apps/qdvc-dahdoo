package qdvc.dahdoo.android.app.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Short messages for the snackbar. Failed writes are never silent (spec A8): any
 * layer that fails to persist something posts here.
 */
class Messages {
    private val _flow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val flow: SharedFlow<String> = _flow.asSharedFlow()

    fun post(text: String) {
        _flow.tryEmit(text)
    }
}
