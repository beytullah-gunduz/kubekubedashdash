package com.kubekubedashdash.ui.modals.viewmodel

/** Which tab the first step of a discovery modal shows. [stored] is the preference value. */
enum class DiscoveryMode(val stored: String) {
    BROWSE("browse"),
    BY_NAME("by_name"),
    ;

    companion object {
        fun fromStored(value: String?): DiscoveryMode = entries.firstOrNull { it.stored == value } ?: BROWSE
    }
}

/** What the paste field understood. [recognized] false = the text matched no known shape. */
data class PasteNotice(val text: String, val recognized: Boolean)

/** "Filled the location and cluster. Add the project." — either half may be absent. */
internal fun filledNotice(filled: List<String>, missing: List<String>): String = listOfNotNull(
    filled.takeIf { it.isNotEmpty() }?.let { "Filled the ${englishList(it)}." },
    missing.takeIf { it.isNotEmpty() }?.let { "Add the ${englishList(it)}." },
).joinToString(" ")

internal fun englishList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}
