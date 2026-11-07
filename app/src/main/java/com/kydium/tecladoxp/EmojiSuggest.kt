package com.kydium.tecladoxp

import java.text.Normalizer

/** Emoji offered in the suggestion bar for some words (like Gboard: tapping it replaces the word). */
object EmojiSuggest {

    private val map: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        fun add(emoji: String, words: String) {
            for (w in words.split(' ')) if (w.isNotEmpty()) m[w] = emoji
        }
        // ---- español
        add("😂", "risa xd")
        add("❤️", "amor corazon amo tqm tkm tqiero")
        add("😘", "beso besos besito besitos muak")
        add("🤗", "abrazo abrazos")
        add("😊", "feliz contento contenta alegre")
        add("🎉", "felicidades felicitaciones fiesta celebrar")
        add("🎂", "cumpleanos cumple torta pastel")
        add("😢", "triste tristeza")
        add("😭", "llorar lloro llorando")
        add("😠", "enojado enojada molesto molesta furioso")
        add("😨", "miedo")
        add("😴", "sueno dormir durmiendo dormido dormida")
        add("😩", "cansado cansada")
        add("😋", "hambre rico rica delicioso deliciosa")
        add("🍕", "pizza")
        add("🍔", "hamburguesa")
        add("☕", "cafe")
        add("🍺", "cerveza chela chelas")
        add("🍷", "vino")
        add("🍗", "pollo")
        add("🍦", "helado")
        add("🔥", "fuego")
        add("👌", "ok okey oki")
        add("🙏", "gracias porfa porfis porfavor dios")
        add("👍", "bueno buenisimo dale")
        add("👏", "bravo aplausos excelente")
        add("🐶", "perro perrito")
        add("🐱", "gato gatito")
        add("☀️", "sol")
        add("🌧️", "lluvia")
        add("🥶", "frio")
        add("🥵", "calor")
        add("🏖️", "playa")
        add("🎵", "musica cancion")
        add("⚽", "futbol gol")
        add("💰", "dinero plata")
        add("🏠", "casa")
        add("🚗", "carro auto")
        add("💼", "trabajo chamba")
        add("📚", "estudiar")
        add("📖", "libro")
        add("📱", "celular telefono")
        add("📷", "foto fotos")
        add("🎁", "regalo regalos")
        add("🌸", "flor flores")
        add("🌹", "rosa rosas")
        add("⭐", "estrella")
        add("🌙", "luna")
        add("🌍", "mundo")
        add("✈️", "avion viaje")
        add("👋", "hola chau chao adios")
        add("👶", "bebe")
        add("👀", "ojos")
        add("😮", "wow guau")
        add("😅", "ups")
        add("😔", "perdon")
        add("🤪", "loco loca")
        add("💡", "idea")
        add("💪", "fuerza fuerte gimnasio gym")
        add("😎", "genial chevere bacan")
        add("✅", "listo")
        add("🤫", "secreto silencio")
        add("🤒", "enfermo enferma")
        add("💯", "verdad cien")
        add("🤔", "pensar pensando hmm")
        // ---- english
        add("😂", "lol lmao haha hahaha")
        add("❤️", "love heart")
        add("😘", "kiss kisses")
        add("🤗", "hug hugs")
        add("😊", "happy")
        add("🎉", "party congrats congratulations")
        add("🎂", "birthday cake")
        add("😢", "sad")
        add("😭", "cry crying")
        add("😠", "angry mad")
        add("😴", "sleep sleepy")
        add("😩", "tired")
        add("😋", "hungry yummy delicious")
        add("🍔", "burger")
        add("☕", "coffee")
        add("🍺", "beer")
        add("🍷", "wine")
        add("🔥", "fire lit")
        add("👌", "okay")
        add("🙏", "thanks thank thx please god")
        add("👍", "good great nice")
        add("🐶", "dog puppy")
        add("🐱", "cat kitty")
        add("☀️", "sun sunny")
        add("🌧️", "rain")
        add("🥶", "cold")
        add("🥵", "hot")
        add("🏖️", "beach")
        add("🎵", "music song")
        add("💰", "money")
        add("🏠", "home house")
        add("🚗", "car")
        add("💼", "work job")
        add("📱", "phone")
        add("🎁", "gift present")
        add("🌸", "flower flowers")
        add("🌹", "rose")
        add("⭐", "star")
        add("🌙", "moon")
        add("🌍", "world")
        add("✈️", "plane flight trip")
        add("👋", "hi hello hey bye")
        add("👶", "baby")
        add("👀", "eyes look")
        add("😅", "oops")
        add("😔", "sorry")
        add("😎", "cool")
        add("✅", "done")
        add("💪", "strong")
        add("🤔", "think thinking")
        m
    }

    private val laugh = Regex("^(ja|je|ji|js|ha|he|xd|jsj)+[aeijshdx]?$")

    private fun norm(word: String): String {
        val lw = word.lowercase().trim()
        val n = Normalizer.normalize(lw, Normalizer.Form.NFD)
        return n.filter { it.isLetter() }
    }

    /** Emoji for [word], or null. */
    fun forWord(word: String): String? {
        if (word.length < 2) return null
        val n = norm(word)
        if (n.length < 2) return null
        map[n]?.let { return it }
        if (n.length >= 4 && laugh.matches(n) && (n.startsWith("ja") || n.startsWith("js") || n.startsWith("je") || n.startsWith("ha"))) return "😂"
        return null
    }
}
