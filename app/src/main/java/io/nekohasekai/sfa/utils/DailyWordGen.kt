package io.nekohasekai.sfa.utils

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/**
 * Kotlin port of `p2p-network/daily_word_gen.py`; behaviour must match exactly.
 *
 * After changing `WORD_LIST` in Python, regenerate this file:
 * run `python _emit_daily_word_gen.kt.py` with working directory `p2p-network/`.
 */
object DailyWordGen {

    private val WORD_LIST = arrayOf(
        "able", "about", "above", "accept", "across", "act", "actually", "add",
        "admit", "afraid", "after", "again", "against", "age", "ago", "agree",
        "ahead", "air", "all", "allow", "almost", "alone", "along", "already",
        "also", "always", "among", "amount", "and", "anger", "animal", "answer",
        "any", "appear", "apple", "area", "arm", "army", "around", "arrive",
        "art", "ask", "at", "attack", "aunt", "avoid", "away", "baby",
        "back", "bad", "bag", "ball", "band", "bank", "bar", "base",
        "basic", "bath", "be", "bear", "beat", "beauty", "become", "bed",
        "before", "begin", "behind", "believe", "bell", "belong", "below", "best",
        "better", "between", "big", "bird", "bit", "black", "block", "blood",
        "blow", "blue", "board", "boat", "body", "bone", "book", "born",
        "both", "bottom", "box", "boy", "brain", "bread", "break", "bridge",
        "bright", "bring", "broad", "brother", "brown", "build", "burn", "bus",
        "busy", "but", "buy", "call", "calm", "came", "camp", "can",
        "cap", "capital", "captain", "car", "card", "care", "carry", "case",
        "catch", "cause", "center", "century", "certain", "chair", "chance", "change",
        "charge", "check", "child", "choice", "church", "circle", "city", "claim",
        "class", "clean", "clear", "climb", "close", "cloud", "club", "coast",
        "coat", "cold", "collect", "college", "color", "come", "common", "company",
        "compare", "complete", "concern", "condition", "connect", "consider", "contain", "continue",
        "control", "cook", "cool", "copy", "corner", "cost", "could", "count",
        "country", "couple", "course", "court", "cover", "create", "cross", "crowd",
        "cry", "cup", "current", "cut", "dance", "danger", "dark", "data",
        "date", "daughter", "day", "dead", "deal", "dear", "death", "decide",
        "deep", "degree", "demand", "depend", "describe", "design", "detail", "develop",
        "die", "difference", "difficult", "dinner", "direct", "discover", "discuss", "do",
        "doctor", "dog", "dollar", "door", "double", "down", "draw", "dream",
        "dress", "drink", "drive", "drop", "dry", "during", "each", "ear",
        "early", "earth", "east", "easy", "eat", "edge", "effect", "effort",
        "eight", "either", "else", "empty", "end", "enemy", "energy", "enjoy",
        "enough", "enter", "equal", "escape", "even", "evening", "event", "ever",
        "every", "exact", "example", "except", "excite", "excuse", "exercise", "expect",
        "experience", "explain", "express", "eye", "face", "fact", "fail", "fair",
        "fall", "family", "famous", "far", "farm", "fast", "fat", "father",
        "favor", "fear", "feed", "feel", "few", "field", "fight", "fill",
        "final", "find", "fine", "finger", "finish", "fire", "first", "fish",
        "fit", "five", "fix", "flat", "floor", "flower", "fly", "follow",
        "food", "foot", "for", "force", "foreign", "forest", "forget", "form",
        "forward", "four", "free", "fresh", "friend", "from", "front", "fruit",
        "full", "fun", "future", "game", "garden", "gate", "gather", "general",
        "gentle", "get", "gift", "girl", "give", "glad", "glass", "go",
        "god", "gold", "good", "government", "grand", "grass", "gray", "great",
        "green", "ground", "group", "grow", "guard", "guess", "gun", "hair",
        "half", "hall", "hand", "hang", "happen", "happy", "hard", "hat",
        "have", "he", "head", "health", "hear", "heart", "heat", "heavy",
        "help", "her", "here", "high", "hill", "him", "his", "hit",
        "hold", "hole", "home", "hope", "horse", "hospital", "hot", "hotel",
        "hour", "house", "how", "huge", "human", "hundred", "hunt", "hurry",
        "hurt", "husband", "ice", "idea", "if", "image", "imagine", "important",
        "in", "include", "increase", "indeed", "indicate", "industry", "inform", "inside",
        "instead", "interest", "into", "iron", "island", "issue", "it", "item",
        "job", "join", "joy", "judge", "jump", "just", "justice", "keep",
        "key", "kid", "kill", "kind", "king", "kitchen", "knee", "knife",
        "knock", "know", "lady", "lake", "land", "language", "large", "last",
        "late", "laugh", "law", "lay", "lead", "leaf", "learn", "least",
        "leave", "left", "leg", "less", "lesson", "let", "letter", "level",
        "library", "lie", "life", "lift", "light", "like", "likely", "limit",
        "line", "lip", "list", "listen", "little", "live", "long", "look",
        "lord", "lose", "lot", "love", "low", "lunch", "machine", "main",
        "major", "make", "man", "manage", "many", "map", "mark", "market",
        "marry", "master", "matter", "may", "maybe", "mean", "measure", "meet",
        "member", "memory", "mention", "message", "method", "middle", "might", "mile",
        "milk", "mind", "mine", "minute", "miss", "mistake", "model", "modern",
        "moment", "money", "month", "moon", "more", "morning", "most", "mother",
        "mountain", "mouth", "move", "much", "music", "must", "name", "nation",
        "nature", "near", "necessary", "neck", "need", "neighbor", "neither", "never",
        "new", "news", "next", "nice", "night", "nine", "no", "noble",
        "noise", "none", "nor", "north", "nose", "not", "note", "nothing",
        "notice", "now", "number", "object", "observe", "occur", "of", "off",
        "offer", "office", "often", "oil", "old", "on", "once", "one",
        "only", "open", "opinion", "opposite", "or", "order", "other", "our",
        "out", "outside", "over", "own", "page", "pain", "pair", "paper",
        "parent", "part", "particular", "party", "pass", "past", "path", "pay",
        "peace", "people", "perfect", "perhaps", "period", "permit", "person", "pick",
        "picture", "piece", "place", "plan", "plant", "play", "please", "point",
        "police", "poor", "popular", "position", "possible", "power", "practice", "prepare",
        "present", "president", "press", "pretty", "prevent", "price", "prince", "private",
        "prize", "probably", "problem", "produce", "product", "program", "promise", "protect",
        "prove", "provide", "public", "pull", "purpose", "push", "put", "quarter",
        "queen", "question", "quick", "quiet", "quite", "race", "rain", "raise",
        "range", "rather", "reach", "read", "ready", "real", "reason", "receive",
        "record", "red", "reduce", "region", "relate", "remain", "remember", "remove",
        "repeat", "report", "require", "rest", "result", "return", "rich", "ride",
        "right", "ring", "rise", "risk", "river", "road", "rock", "roll",
        "room", "round", "row", "rule", "run", "safe", "sail", "salt",
        "same", "sand", "save", "say", "school", "science", "sea", "search",
        "seat", "second", "secret", "see", "seem", "self", "sell", "send",
        "sense", "sentence", "serve", "service", "set", "settle", "seven", "several",
        "shake", "shall", "shape", "share", "sharp", "she", "shine", "ship",
        "shoe", "shoot", "shop", "short", "should", "shoulder", "shout", "show",
        "shut", "sick", "side", "sight", "sign", "silence", "silver", "similar",
        "simple", "since", "sing", "sir", "sister", "sit", "six", "size",
        "skill", "skin", "sky", "sleep", "slip", "slow", "small", "smile",
        "smoke", "snow", "so", "soft", "soil", "soldier", "some", "son",
        "song", "soon", "sorry", "sort", "soul", "sound", "south", "space",
        "speak", "special", "speed", "spend", "spirit", "spot", "spread", "spring",
        "square", "stage", "stand", "star", "start", "state", "station", "stay",
        "step", "stick", "still", "stock", "stone", "stop", "store", "story",
        "strange", "street", "strong", "student", "study", "such", "sudden", "suffer",
        "suggest", "summer", "sun", "support", "suppose", "sure", "surprise", "sweet",
        "swim", "system", "table", "take", "talk", "tall", "taste", "teach",
        "team", "tell", "ten", "than", "thank", "that", "the", "then",
        "there", "these", "they", "thick", "thin", "thing", "think", "third",
        "this", "those", "though", "thought", "thousand", "three", "through", "throw",
        "tie", "till", "time", "tiny", "to", "today", "together", "tomorrow",
        "tonight", "too", "top", "total", "touch", "toward", "town", "trade",
        "train", "travel", "tree", "trouble", "true", "trust", "truth", "try",
        "turn", "twelve", "twenty", "two", "type", "uncle", "under", "understand",
        "union", "unit", "until", "up", "upon", "us", "use", "usual",
        "valley", "value", "various", "very", "village", "visit", "voice", "wait",
        "walk", "wall", "want", "war", "warm", "wash", "watch", "water",
        "way", "we", "weak", "wear", "weather", "week", "weight", "welcome",
        "well", "west", "what", "when", "where", "whether", "which", "while",
        "white", "who", "whole", "why", "wide", "wife", "wild", "will",
        "win", "wind", "window", "winter", "wish", "with", "without", "woman",
        "wonder", "wood", "word", "work", "world", "worry", "worth", "would",
        "write", "wrong", "yard", "year", "yes", "yet", "you", "young",
        "your", "youth", "zero", "zone"

    )

    private const val SUFFIX_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"

    private const val CLOUDFLARE_NET = "cloudflare.net"

    private fun seedHex(vararg parts: Any): String {
        val joined = parts.joinToString("|") { it.toString() }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(joined.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { b ->
            val u = b.toInt() and 0xff
            u.toString(16).padStart(2, '0')
        }
    }

    private fun wordWithSuffix(seed: String, i: Int): String {
        val chunk = seed.substring(8 + i * 8, 16 + i * 8)
        val idx = (chunk.toULong(16) % WORD_LIST.size.toULong()).toInt()
        val suffixChunk = seed.substring(32 + i * 4, 36 + i * 4)
        val suffixIdx = suffixChunk.toInt(16) % SUFFIX_CHARS.length
        return WORD_LIST[idx] + SUFFIX_CHARS[suffixIdx]
    }

    private fun labelMultiWords(seed: String): String {
        val wordCount = 2 + (seed.substring(0, 8).toULong(16) % 2uL).toInt()
        val words = Array(wordCount) { wi -> wordWithSuffix(seed, wi) }
        val gapIdx =
            (seed.substring(56, 60).toULong(16) % (wordCount - 1).toULong()).toInt()
        val sepChar =
            SUFFIX_CHARS[(seed.substring(60, 64).toULong(16) % SUFFIX_CHARS.length.toULong()).toInt()]
        return buildString {
            append(words[0])
            for (i in 1 until words.size) {
                if (i - 1 == gapIdx) append(sepChar)
                append(words[i])
            }
        }
    }

    private fun labelSingleWord(seed: String): String = wordWithSuffix(seed, 0)

    /**
     * @param timestamp Unix seconds; if null, uses current time.
     * @param domainType If null or blank, returns only the multi-word label (no `.com` / FQDN).
     *   Otherwise same as Python `domain_type` — pass `"com"` explicitly for `a.b.com`.
     */
    @JvmStatic
    @JvmOverloads
    fun getDailyString(timestamp: Long? = null, domainType: String? = null): String {
        val ts = timestamp ?: (System.currentTimeMillis() / 1000L)
        val dayTs = ts / 86400L
        val trimmed = domainType?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            val seed = seedHex(dayTs, "label-only", "plain")
            return labelMultiWords(seed)
        }
        val dt = trimmed.lowercase(Locale.ROOT)
        return if (dt == CLOUDFLARE_NET) {
            val seed = seedHex(dayTs, dt, "single")
            "${labelMultiWords(seed)}.$CLOUDFLARE_NET"
        } else {
            val firstSeed = seedHex(dayTs, trimmed, "first")
            val secondSeed = seedHex(dayTs, trimmed, "second")
            val first = labelMultiWords(firstSeed)
            val second = labelSingleWord(secondSeed)
            "$first.$second.$trimmed"
        }
    }
}
