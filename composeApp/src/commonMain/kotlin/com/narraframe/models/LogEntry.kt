package com.narraframe.models

data class LogEntry(
    val id:          String         = "${System.currentTimeMillis()}_${Math.random()}",
    val epochMs:     Long           = System.currentTimeMillis(),
    val level:       Level,
    val step:        GenerationStep? = null,
    val message:     String
) {
    enum class Level { DEBUG, INFO, WARNING, ERROR, SUCCESS }

    val formattedTime: String get() {
        val calendar = java.util.Calendar.getInstance().apply {
            timeInMillis = epochMs
        }
        return buildString {
            append(calendar.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0'))
            append(':')
            append(calendar.get(java.util.Calendar.MINUTE).toString().padStart(2, '0'))
            append(':')
            append(calendar.get(java.util.Calendar.SECOND).toString().padStart(2, '0'))
        }
    }
}
