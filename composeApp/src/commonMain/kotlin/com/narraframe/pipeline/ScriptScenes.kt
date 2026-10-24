package com.narraframe.pipeline

/**
 * Splits a script into scenes at sentence boundaries. Each scene is narrated and
 * illustrated on its own, so the picture always matches what is being said.
 */
object ScriptScenes {

    /** Speaking rate used to size scenes before the narration exists. */
    const val WORDS_PER_SECOND = 2.5

    /**
     * Groups sentences into scenes of about [secondsPerScene] of speech. A sentence is
     * never cut; a paragraph break always starts a new scene once the current one has
     * at least half its target length.
     */
    fun split(script: String, secondsPerScene: Double): List<String> {
        val target = (secondsPerScene * WORDS_PER_SECOND).coerceAtLeast(6.0)
        val scenes = mutableListOf<String>()
        val current = StringBuilder()
        var words = 0

        fun flush() {
            if (current.isNotBlank()) scenes += current.toString().trim()
            current.clear(); words = 0
        }

        for (paragraph in script.split(Regex("\\n\\s*\\n"))) {
            if (words >= target / 2) flush()
            for (sentence in sentences(paragraph)) {
                val w = wordCount(sentence)
                // Close the scene when this sentence would overshoot more than it fills
                if (words > 0 && words + w > target && (words + w - target) > (target - words)) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
                words += w
            }
        }
        flush()
        return scenes.ifEmpty { listOf(script.trim()) }
    }

    fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun sentences(paragraph: String): List<String> =
        paragraph.replace(Regex("\\s+"), " ").trim()
            .split(Regex("(?<=[.!?…][\"'”’)]?)\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
