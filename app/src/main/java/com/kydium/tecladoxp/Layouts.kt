package com.kydium.tecladoxp

object Codes {
    const val SHIFT = -1
    const val SYMBOLS = -2
    const val LETTERS = -3
    const val SYMBOLS2 = -4
    const val DELETE = -5
    const val ENTER = -10
    const val SPACE = 32
}

class Key(
    val label: String,
    val code: Int,
    val width: Float = 1f,
    val alts: List<String> = emptyList(),
    val hint: String = ""
) {
    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    val isSpecial: Boolean get() = code < 0 || code == Codes.SPACE
    val isLetter: Boolean get() = code > 0 && Character.isLetter(code)
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

enum class LayoutType { LETTERS, SYMBOLS, SYMBOLS2 }

class KbLayout(val type: LayoutType, val rows: List<List<Key>>) {
    val allKeys: List<Key> = rows.flatten()
}

object Layouts {
    private fun alts(s: String) = s.split(" ").filter { it.isNotEmpty() }

    private fun ch(c: String, alts: String = "", hint: String = "", w: Float = 1f) =
        Key(c, c.codePointAt(0), w, alts(alts), hint)

    private const val EMOJIS = "😂 ❤️ 👍 🙏 😭 🔥 😍 🤣 😅 🥺"
    private const val PUNCT = "? ! ¿ ¡ , ; : ' \" - @ …"

    private fun bottomRow(switchLabel: String, switchCode: Int, left: Key, right: Key) = listOf(
        Key(switchLabel, switchCode, 1.5f),
        left,
        Key("", Codes.SPACE, 5f),
        right,
        Key("↵", Codes.ENTER, 1.5f)
    )

    fun letters() = KbLayout(
        LayoutType.LETTERS, listOf(
            listOf(
                ch("q", "1", "1"), ch("w", "2", "2"), ch("e", "3 é è ê ë €", "3"),
                ch("r", "4", "4"), ch("t", "5", "5"), ch("y", "6 ý ÿ ¥", "6"),
                ch("u", "7 ú ü ù û", "7"), ch("i", "8 í ï ì î", "8"),
                ch("o", "9 ó ö ò ô õ º", "9"), ch("p", "0 ¶", "0")
            ),
            listOf(
                ch("a", "á à â ä ã å æ ª"), ch("s", "ß $ §"), ch("d"), ch("f"), ch("g"),
                ch("h"), ch("j"), ch("k"), ch("l"), ch("ñ", "n")
            ),
            listOf(Key("⇧", Codes.SHIFT, 1.5f)) +
                listOf(ch("z"), ch("x"), ch("c", "ç ©"), ch("v"), ch("b"), ch("n", "ñ"), ch("m")) +
                listOf(Key("⌫", Codes.DELETE, 1.5f)),
            bottomRow("?123", Codes.SYMBOLS, ch(",", EMOJIS), ch(".", PUNCT))
        )
    )

    fun symbols() = KbLayout(
        LayoutType.SYMBOLS, listOf(
            listOf(
                ch("1", "¹ ½ ⅓ ¼"), ch("2", "² ⅔"), ch("3", "³ ¾"), ch("4", "⁴"), ch("5"),
                ch("6"), ch("7"), ch("8"), ch("9"), ch("0", "∅ ⁿ")
            ),
            listOf(
                ch("@"), ch("#"), ch("$", "€ £ ¥ ¢"), ch("_"), ch("&"), ch("-", "– — ·"),
                ch("+", "±"), ch("("), ch(")"), ch("/")
            ),
            listOf(Key("=\\<", Codes.SYMBOLS2, 1.5f)) +
                listOf(ch("*"), ch("\"", "“ ” « »"), ch("'", "‘ ’"), ch(":"), ch(";"), ch("!", "¡"), ch("?", "¿")) +
                listOf(Key("⌫", Codes.DELETE, 1.5f)),
            bottomRow("ABC", Codes.LETTERS, ch(",", EMOJIS), ch(".", PUNCT))
        )
    )

    fun symbols2() = KbLayout(
        LayoutType.SYMBOLS2, listOf(
            listOf(
                ch("~"), ch("`"), ch("|"), ch("•"), ch("√"), ch("π"), ch("÷"), ch("×"),
                ch("¶"), ch("∆")
            ),
            listOf(
                ch("£"), ch("¢"), ch("€"), ch("¥"), ch("^"), ch("°"), ch("="), ch("{"),
                ch("}"), ch("\\")
            ),
            listOf(Key("?123", Codes.SYMBOLS, 1.5f)) +
                listOf(ch("%", "‰"), ch("©"), ch("®"), ch("™"), ch("✓"), ch("["), ch("]")) +
                listOf(Key("⌫", Codes.DELETE, 1.5f)),
            bottomRow("ABC", Codes.LETTERS, ch("<", "≤ «"), ch(">", "≥ »"))
        )
    )
}
