package com.beyondlevi.nexus.news

/**
 * Turns an article body into HUD pages.
 *
 * Two hard constraints shape the numbers here. The glasses list renderer clips a
 * prose row at three wrapped lines, so a row longer than [MAX_ROW_CHARS] loses
 * its tail; and a surface heavier than ~3 KiB is not delivered at all when the
 * SPP data plane is down, so a whole article can never be one surface. Small
 * pages satisfy both and keep every page inside the viewport, which is what
 * makes the reader navigable with one axis.
 */
object ArticlePager {

    const val MAX_ROW_CHARS = 110
    const val ROWS_PER_PAGE = 4

    fun paginate(paragraphs: List<String>): List<List<String>> {
        val rows = paragraphs.flatMap { wrap(it) }
        if (rows.isEmpty()) return emptyList()
        return rows.chunked(ROWS_PER_PAGE)
    }

    /** Splits on word boundaries; a single word longer than the cap is cut hard. */
    fun wrap(paragraph: String, maxChars: Int = MAX_ROW_CHARS): List<String> {
        val text = paragraph.trim()
        if (text.isEmpty()) return emptyList()
        if (text.length <= maxChars) return listOf(text)

        val rows = mutableListOf<String>()
        val current = StringBuilder()
        for (word in text.split(' ')) {
            if (word.isEmpty()) continue
            if (word.length > maxChars) {
                if (current.isNotEmpty()) {
                    rows += current.toString()
                    current.setLength(0)
                }
                var offset = 0
                while (offset < word.length) {
                    val end = minOf(offset + maxChars, word.length)
                    rows += word.substring(offset, end)
                    offset = end
                }
                continue
            }
            val projected = if (current.isEmpty()) word.length else current.length + 1 + word.length
            if (projected > maxChars) {
                rows += current.toString()
                current.setLength(0)
                current.append(word)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
        }
        if (current.isNotEmpty()) rows += current.toString()
        return rows
    }
}
