package qdvc.dahdoo.android.app.model

/** The two bottom-bar destinations. Record is slot 1, the home surface. */
enum class Tab { RECORD, RECORDINGS }

/** Settings pages; [depth] drives the direction of the horizontal slide. */
enum class SettingsPage(val depth: Int) {
    ROOT(0),
    FOLDER(1),
    CHILDREN(1),
    CHILD_EDIT(2),
    APPEARANCE(1),
    RECORDING(1),
    ABOUT(1),
}

data class Nav(
    val tab: Tab = Tab.RECORD,
    /** Base name of the recording open in the detail view, if any. */
    val openRecording: String? = null,
    /** Null when Settings is closed. */
    val settingsPage: SettingsPage? = null,
    /** The child being edited on [SettingsPage.CHILD_EDIT]; null there means "new child". */
    val editingChildId: String? = null,
    /** Recordings list filter; null shows everyone. */
    val childFilter: String? = null,
)
