package app.wortschatz.core

/** Numbers written out the way they are spoken: 21 is "einundzwanzig", 1984 as a year "neunzehnhundertvierundachtzig". */
object GermanNumbers {
    private val ones = listOf(
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun",
        "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
    )
    private val tens = listOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")

    fun words(n: Int): String {
        require(n in 0..999_999) { "out of range: $n" }
        if (n == 0) return "null"
        return compound(n)
    }

    /** Years from 1100 to 1999 are read in hundreds; others like ordinary numbers. */
    fun year(n: Int): String =
        if (n in 1100..1999) compound(n / 100) + "hundert" + (n % 100).let { if (it == 0) "" else compound(it) } else words(n)

    // Without "null"; "eins" becomes "ein" in front of hundert / tausend / und.
    private fun compound(n: Int): String = buildString {
        val thousands = n / 1000
        val rest = n % 1000
        if (thousands > 0) append(prefix(thousands)).append("tausend")
        val hundreds = rest / 100
        if (hundreds > 0) append(prefix(hundreds)).append("hundert")
        val small = rest % 100
        if (small > 0) append(below100(small))
    }

    private fun prefix(n: Int) = if (n == 1) "ein" else compound(n)

    private fun below100(n: Int): String = when {
        n < 20 -> ones[n]
        n % 10 == 0 -> tens[n / 10]
        else -> (if (n % 10 == 1) "ein" else ones[n % 10]) + "und" + tens[n / 10]
    }
}
