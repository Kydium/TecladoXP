package com.kydium.tecladoxp

class KbTheme(
    val id: String,
    val name: String,
    val unlockLevel: Int,
    val bgTop: Int,
    val bgBottom: Int,
    val key: Int,
    val keySpecial: Int,
    val keyPressed: Int,
    val text: Int,
    val textMuted: Int,
    val accent: Int,
    val accent2: Int,
    val rainbow: Boolean = false
)

private fun c(v: Long) = v.toInt()

object Themes {
    val all = listOf(
        KbTheme("noche", "Noche", 1,
            c(0xFF17181D), c(0xFF101115), c(0xFF2B2D35), c(0xFF1F2026), c(0xFF4A4D59),
            c(0xFFF2F3F5), c(0xFF8D91A0), c(0xFF8B5CF6), c(0xFF22D3EE)),
        KbTheme("claro", "Claro", 1,
            c(0xFFE6E8EE), c(0xFFD9DCE4), c(0xFFFFFFFF), c(0xFFC4C8D2), c(0xFFAFB4C0),
            c(0xFF1C1E24), c(0xFF6B7080), c(0xFF6D28D9), c(0xFF0EA5E9)),
        KbTheme("oceano", "Océano", 5,
            c(0xFF0B2545), c(0xFF061A33), c(0xFF13406E), c(0xFF0E3157), c(0xFF2A6CB0),
            c(0xFFE8F4FF), c(0xFF8FB8DE), c(0xFF38BDF8), c(0xFF34D399)),
        KbTheme("bosque", "Bosque", 10,
            c(0xFF14261B), c(0xFF0C1A11), c(0xFF22402D), c(0xFF1A3122), c(0xFF3D6B4D),
            c(0xFFEAF7EE), c(0xFF94BFA2), c(0xFF4ADE80), c(0xFFFACC15)),
        KbTheme("neon", "Neón", 15,
            c(0xFF0A0A0F), c(0xFF050508), c(0xFF16121F), c(0xFF100D17), c(0xFF3B2266),
            c(0xFFFF4FD8), c(0xFF8A6FB0), c(0xFFFF2BD6), c(0xFF00F0FF)),
        KbTheme("atardecer", "Atardecer", 20,
            c(0xFF3B1C4A), c(0xFF6B2737), c(0xFF5A2D57), c(0xFF472346), c(0xFF8C4566),
            c(0xFFFFEFE3), c(0xFFE0A9A0), c(0xFFFB923C), c(0xFFF472B6)),
        KbTheme("oro", "Oro", 30,
            c(0xFF121008), c(0xFF0A0904), c(0xFF221E10), c(0xFF19160B), c(0xFF4A3F17),
            c(0xFFFFE08A), c(0xFFB59B55), c(0xFFFACC15), c(0xFFFFF3B0)),
        KbTheme("matrix", "Matrix", 40,
            c(0xFF000000), c(0xFF010A03), c(0xFF06180B), c(0xFF041006), c(0xFF0F4A1E),
            c(0xFF39FF6A), c(0xFF1E9C40), c(0xFF22FF55), c(0xFFB6FFC8)),
        KbTheme("arcoiris", "Arcoíris", 50,
            c(0xFF14141C), c(0xFF0C0C12), c(0xFF23232E), c(0xFF1A1A23), c(0xFF44445A),
            c(0xFFFFFFFF), c(0xFF9A9AB0), c(0xFFFF5C8A), c(0xFF5CE1FF), rainbow = true),
        KbTheme("galaxia", "Galaxia", 75,
            c(0xFF1A0B3D), c(0xFF050214), c(0xFF2A1A5E), c(0xFF1E1247), c(0xFF5B3FB5),
            c(0xFFF3EDFF), c(0xFFA99BD6), c(0xFFC084FC), c(0xFF60A5FA), rainbow = true)
    )

    fun byId(id: String?): KbTheme = all.firstOrNull { it.id == id } ?: all[0]

    fun unlockedAt(level: Int): List<KbTheme> = all.filter { it.unlockLevel == level }
}
