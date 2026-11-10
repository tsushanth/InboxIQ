package com.inboxiq.app.classify

/** What a registered India SMS header says about the kind of sender. Heuristic ones are weak. */
enum class HeaderCategory { BANK, CARD, PAYMENTS, ECOMMERCE, DELIVERY, UTILITY, TELECOM, TRAVEL, GOVERNMENT }

/** The message class a header carries, from the suffix the access provider appends. */
enum class DltSuffix(val letter: Char, val meaning: String) {
    TRANSACTIONAL('T', "transactional"),
    SERVICE('S', "service"),
    PROMOTIONAL('P', "promotional"),
    GOVERNMENT('G', "government"),
}

/** A parsed sender such as `VM-HDFCBK-S`: operator and circle prefix, header, optional suffix. */
data class DltHeader(
    val prefix: String,
    val operator: String,
    val circle: String,
    val header: String,
    val suffix: DltSuffix?,
    val brand: IndiaSenders.Brand?,
) {
    /** Plain-language description used in "Why?" sheets. Only states what the header itself shows. */
    val description: String
        get() {
            val kind = suffix?.let { ", registered as ${it.meaning}" }.orEmpty()
            val who = brand?.let { " (${it.name})" }.orEmpty()
            return "a sender ID registered in India: $header$who, from ${operator}'s $circle network$kind"
        }
}

/**
 * India's SMS headers (TRAI): `XY-ABCDEF`, X = originating operator, Y = licence service area, ABCDEF = the six-character
 * header; since May 2025 the operator appends `-P`, `-S`, `-T` or `-G`. The tables live in the resource
 * `classify/india_senders.tsv` (one place to maintain, every entry cites a source). Pure Kotlin.
 */
object IndiaSenders {
    data class Brand(val header: String, val category: HeaderCategory, val name: String, val source: String)

    private class Table(
        val operators: Map<Char, String>,
        val circles: Map<Char, String>,
        val brands: Map<String, Brand>,
        val endings: List<Pair<String, HeaderCategory>>,
    )

    private val table: Table by lazy { load() }

    /** True when the resource was found; a unit test asserts it so a packaging mistake cannot go unnoticed. */
    val loaded: Boolean get() = table.operators.isNotEmpty() && table.brands.isNotEmpty()

    val brandCount: Int get() = table.brands.size

    private val shape = Regex("^([A-Z])([A-Z])-([A-Z0-9]{3,11})(?:-([PSTG]))?$")

    /** null when [address] is not shaped like an India header (prefix letters must be a real operator and circle code). */
    fun parse(address: String): DltHeader? {
        val m = shape.matchEntire(address.trim().uppercase()) ?: return null
        val t = table
        val op = t.operators[m.groupValues[1][0]] ?: return null
        val circle = t.circles[m.groupValues[2][0]] ?: return null
        val header = m.groupValues[3]
        val suffix = m.groupValues[4].firstOrNull()?.let { l -> DltSuffix.entries.first { it.letter == l } }
        val brand = t.brands[header]
            ?: t.endings.firstOrNull { header.length > it.first.length && header.endsWith(it.first) }
                ?.let { Brand(header, it.second, header, "heuristic") }
        return DltHeader(m.groupValues[1] + m.groupValues[2], op, circle, header, suffix, brand)
    }

    private fun load(): Table {
        val ops = HashMap<Char, String>(); val circles = HashMap<Char, String>()
        val brands = HashMap<String, Brand>(); val endings = ArrayList<Pair<String, HeaderCategory>>()
        val stream = IndiaSenders::class.java.classLoader?.getResourceAsStream("classify/india_senders.tsv")
        stream?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val f = line.split('\t')
                when (f[0]) {
                    "OAP" -> if (f.size >= 3) ops[f[1][0]] = f[2]
                    "LSA" -> if (f.size >= 3) circles[f[1][0]] = f[2]
                    "HDR" -> if (f.size >= 5) brands[f[1]] = Brand(f[1], HeaderCategory.valueOf(f[2]), f[3], f[4])
                    "ENDS" -> if (f.size >= 4) endings += f[1] to HeaderCategory.valueOf(f[2])
                }
            }
        }
        return Table(ops, circles, brands, endings)
    }
}
