package com.nihilshell.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The QR reader against codes made by qrencode (as the desktop makes them,
 * with -l M), drawn here as squares and as the desktop's dots, turned, small,
 * noisy and damaged; and against the QR of Preferences, Phone, as the shell
 * drew it on a screen.
 */
class QrDecoderTest {

    @Test
    fun theDesktopsCode() {
        assertEquals(URI, decode(render(NIHIL_URI, scale = 5f)))
    }

    @Test
    fun drawnInDotsAsTheDesktopDrawsIt() {
        assertEquals(URI, decode(render(NIHIL_URI, scale = 5f, dots = true)))
        assertEquals(URI, decode(render(NIHIL_URI, scale = 7f, dots = true)))
    }

    @Test
    fun turnedAnyWay() {
        for (degrees in intArrayOf(23, 45, 90, 135, 180, 271, 340)) {
            assertEquals("$degrees°", URI, decode(render(NIHIL_URI, scale = 6f, dots = true, degrees = degrees.toDouble())))
        }
    }

    @Test
    fun smallNoisyAndDim() {
        assertEquals(URI, decode(render(NIHIL_URI, scale = 3f)))
        assertEquals(URI, decode(render(NIHIL_URI, scale = 5f, dots = true, noise = 30, light = 170, dark = 80)))
    }

    @Test
    fun damagedAndCorrected() {
        val rows = NIHIL_URI.map { it.copyOf() }.toTypedArray()
        val random = java.util.Random(7)
        var flipped = 0
        while (flipped < 14) {
            val y = 9 + random.nextInt(rows.size - 18)
            val x = 9 + random.nextInt(rows.size - 18)
            if (x == 6 || y == 6) continue
            rows[y][x] = !rows[y][x]
            flipped++
        }
        assertEquals(URI, decode(render(rows, scale = 5f)))
    }

    @Test
    fun everyKindOfBlock() {
        assertEquals("NIHIL", decode(render(V1_L, scale = 6f)))
        assertEquals("ex nihilo", decode(render(V2_H, scale = 6f)))
        assertEquals(TEXTS.getValue("V5_Q"), decode(render(V5_Q, scale = 5f)))
        assertEquals(TEXTS.getValue("V7_M"), decode(render(V7_M, scale = 5f, degrees = 10.0)))
        assertEquals(TEXTS.getValue("V10_L"), decode(render(V10_L, scale = 4f)))
        assertEquals(TEXTS.getValue("V14_Q"), decode(render(V14_Q, scale = 4f, dots = true)))
    }

    @Test
    fun noCodeNoText() {
        val flat = ByteArray(200 * 200) { 200.toByte() }
        assertNull(QrDecoder.decode(flat, 200, 200))
        val random = java.util.Random(3)
        val noise = ByteArray(300 * 300) { random.nextInt(256).toByte() }
        assertNull(QrDecoder.decode(noise, 300, 300))
    }

    @Test
    fun formatAndVersionWords() {
        assertEquals(0x5412, QrDecoder.FORMATS[0])
        assertEquals(0x5125, QrDecoder.FORMATS[1])
        assertEquals(0x07C94, QrDecoder.versionWord(7))
        assertEquals(0x0F928, QrDecoder.versionWord(15))
    }

    @Test
    fun blocksFillEachVersion() {
        for (version in 1..QrDecoder.MAX_VERSION) for (level in 0..3) {
            val spec = QrDecoder.BLOCKS[version][level]
            var total = 0
            var g = 1
            while (g < spec.size) {
                total += spec[g] * (spec[g + 1] + spec[0])
                g += 2
            }
            assertEquals("version $version level $level", QrDecoder.codewords(version), total)
        }
    }

    @Test
    fun theShellsQrOnAScreen() {
        // A greyscale PGM, gzipped: Android's unit tests compile without javax.imageio.
        val stream = javaClass.getResourceAsStream("/qr/nihil-preferences.pgm.gz")
        assertTrue("the screenshot is in the test resources", stream != null)
        val bytes = java.util.zip.GZIPInputStream(stream).use { it.readBytes() }
        var start = 0
        var lines = 0
        while (lines < 3) if (bytes[start++] == '\n'.code.toByte()) lines++
        val (w, h) = String(bytes, 0, start, Charsets.US_ASCII).lines()[1].split(' ').map { it.toInt() }
        val lum = bytes.copyOfRange(start, start + w * h)
        assertEquals(
            "nihil-deck://pair?v=1&name=thinkpad&hosts=192.0.2.2&port=64445&code=NM3TL9BD&pin=KbMN8DhTefv-656rVKbNm3JdZgujKwRZ1Zsf3FIFIWY",
            QrDecoder.decode(lum, w, h),
        )
    }

    // --- Drawing codes ----------------------------------------------------------

    private class Picture(val lum: ByteArray, val width: Int, val height: Int)

    private fun decode(p: Picture): String? = QrDecoder.decode(p.lum, p.width, p.height)

    private fun rows(vararg hex: String): Array<BooleanArray> {
        val n = hex.size
        return Array(n) { y ->
            val bits = hex[y].map { Character.digit(it, 16) }.flatMap { d -> (3 downTo 0).map { (d shr it) and 1 == 1 } }
            BooleanArray(n) { bits[it] }
        }
    }

    /** The finders and the alignment patterns, which the desktop draws whole. */
    private fun whole(rows: Int, y: Int, x: Int): Boolean {
        if ((x < 7 && y < 7) || (x >= rows - 7 && y < 7) || (x < 7 && y >= rows - 7)) return true
        val version = (rows - 17) / 4
        val centres = ALIGNMENTS[version]
        for (cy in centres) for (cx in centres) {
            val corner = (cx == 6 && cy == 6) || (cx == 6 && cy == centres.last()) || (cx == centres.last() && cy == 6)
            if (!corner && kotlin.math.abs(x - cx) <= 2 && kotlin.math.abs(y - cy) <= 2) return true
        }
        return false
    }

    private fun render(
        modules: Array<BooleanArray>,
        scale: Float,
        dots: Boolean = false,
        degrees: Double = 0.0,
        noise: Int = 0,
        light: Int = 242,
        dark: Int = 16,
    ): Picture {
        val n = modules.size
        val side = ((n + 8) * scale * 1.45f).toInt() + 16
        val lum = ByteArray(side * side)
        val angle = Math.toRadians(degrees)
        val c = cos(angle)
        val s = sin(angle)
        val random = java.util.Random(11)
        val half = side / 2.0
        for (y in 0 until side) for (x in 0 until side) {
            val dx = x + 0.5 - half
            val dy = y + 0.5 - half
            val mx = (c * dx + s * dy) / scale + n / 2.0
            val my = (-s * dx + c * dy) / scale + n / 2.0
            val col = floor(mx).toInt()
            val row = floor(my).toInt()
            var on = false
            if (row in 0 until n && col in 0 until n && modules[row][col]) {
                on = if (!dots || whole(n, row, col)) {
                    true
                } else {
                    val fx = mx - col - 0.5
                    val fy = my - row - 0.5
                    fx * fx + fy * fy <= 0.43 * 0.43
                }
            }
            var v = if (on) dark else light
            if (noise > 0) v += random.nextInt(2 * noise + 1) - noise
            lum[y * side + x] = v.coerceIn(0, 255).toByte()
        }
        return Picture(lum, side, side)
    }

    private companion object {
        val ALIGNMENTS = arrayOf(
            intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26), intArrayOf(6, 30),
            intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42), intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
            intArrayOf(6, 30, 54), intArrayOf(6, 32, 58), intArrayOf(6, 34, 62), intArrayOf(6, 26, 46, 66), intArrayOf(6, 26, 48, 70),
        )
        const val URI = "nihil-deck://pair?v=1&name=thinkpad&hosts=192.168.1.20,100.64.0.7&port=64445&code=7JTX39F4&pin=8-eMVPli0fpzjeUBe-b_bmRtjDMwo1bNYXaVTyPSbVc"
        val TEXTS = mapOf(
            "V1_L" to "NIHIL",
            "V2_H" to "ex nihilo",
            "V5_Q" to "NIHIL 5Q, ex nihilo, nihil fit",
            "V7_M" to "Version seven, with its version info",
            "V10_L" to "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            "V14_Q" to "Fourteen: four rows of alignment patterns"
        )
    }

    private val NIHIL_URI = rows(
        "fe5549069cbf8",
        "823662560fa08",
        "ba8ce1cef1ae8",
        "bad3d491192e8",
        "badf67e7042e8",
        "82f3d639be208",
        "feaaaaaaaabf8",
        "00e4823fd5800",
        "be10ebf04abe0",
        "f828b7cac1690",
        "462ff6a5eeaf8",
        "e48f584ec2d90",
        "1a2ae7a658668",
        "15471a4718740",
        "7794ae6a6f178",
        "2cae9b1680180",
        "0e488d897ce70",
        "50fe045f8d360",
        "0b53b4a802418",
        "b9e5307cc3490",
        "d3e01fa06be70",
        "6439627648200",
        "bf8fa7e8bafc8",
        "8895b22eb2898",
        "8acbaeb249ab8",
        "08f7d63a418d0",
        "afca0be833fb8",
        "614d370de0d90",
        "cee416b61fef0",
        "8cc7270708b20",
        "4b0c5647efe18",
        "d1d6472319b00",
        "f76c0d155b680",
        "e088b7821c378",
        "3f804cd220e58",
        "000046bbb6100",
        "27d89d462f3e8",
        "fd3ea7b700850",
        "46136351ba328",
        "7188957dc69c8",
        "e3bf8bf00bfb8",
        "00f44232588a0",
        "fe7be6b42fab8",
        "82f0363fb2888",
        "baeb87e07dfe0",
        "baa23a06d4ec8",
        "baae89cf30560",
        "823159957e708",
        "febfc0491aab8",
    )

    private val V1_L = rows(
        "fe2bf8",
        "82aa08",
        "bab2e8",
        "ba0ae8",
        "bafae8",
        "82e208",
        "feabf8",
        "008000",
        "d33bb0",
        "b8f500",
        "563380",
        "48af88",
        "169768",
        "00c6a0",
        "fe9ad8",
        "820278",
        "ba6e18",
        "bac738",
        "ba1728",
        "82d948",
        "fec560",
    )

    private val V2_H = rows(
        "fef3bf8",
        "82a3208",
        "badaae8",
        "ba0f2e8",
        "ba7dae8",
        "82d8208",
        "feaabf8",
        "0094800",
        "3af7738",
        "1139a40",
        "5211dc8",
        "08f9188",
        "4a8e4e0",
        "ad2fb30",
        "aaa0278",
        "98ecf98",
        "a63eff8",
        "00e48e0",
        "fe39a98",
        "8271898",
        "bac5fe0",
        "bab82e8",
        "ba81748",
        "8249508",
        "fe168f8",
    )

    private val V5_Q = rows(
        "fe573813f8",
        "8202d19208",
        "ba810ed2e8",
        "ba29ba32e8",
        "ba88ca22e8",
        "828e158a08",
        "feaaaaabf8",
        "002cccc800",
        "4abdbd5da0",
        "8894d3e208",
        "bb11e770b0",
        "30aa8519f0",
        "4bca086fc8",
        "246c5ac210",
        "960ae730b0",
        "9dc16d9f90",
        "371ba12ad8",
        "cd6932d748",
        "dbd9f995e0",
        "b1a21d2150",
        "ca0ac46278",
        "b85b92c668",
        "3740659cc8",
        "5d31f32998",
        "f3830c7290",
        "f42edecfe8",
        "3351690950",
        "1c4bf28218",
        "e32b4a3f80",
        "00b0d5c8d0",
        "fe1c683ad8",
        "827b2248f0",
        "baf0d2bfd8",
        "ba7bf80cc8",
        "ba544e6050",
        "82cf44c578",
        "fe7831c3e8",
    )

    private val V7_M = rows(
        "fe94270bcbf8",
        "826a33f81208",
        "ba61990b12e8",
        "baa14f5e1ae8",
        "bad8eff4bae8",
        "82d8d8a38208",
        "feaaaaaaabf8",
        "00c7f8946800",
        "8b9dcfe67fc8",
        "c4b2b9a34b30",
        "de02f331c350",
        "2123067b7788",
        "ba9a6cd9c988",
        "40b49e8c96d8",
        "9bfec4843878",
        "dcbd198c8ea8",
        "c3163123b420",
        "084de5f77928",
        "e627b55bf180",
        "75f8627c7150",
        "2f924fde5ff8",
        "78ab388b08f0",
        "3a864a81ba80",
        "c8e408b33888",
        "ff802fb1dfc0",
        "ec17e2a4cb08",
        "36a147ec1110",
        "d143130c1c80",
        "8ef9d9263888",
        "487274f37128",
        "07565159ec00",
        "f8bf1cf8f3d0",
        "37c1e65cc178",
        "2c8f590e8c58",
        "0be0ce0736f8",
        "787b430b01a8",
        "9b2e9fa1afa0",
        "00b158f4e8b0",
        "fec6eadc6af0",
        "827b98c42898",
        "bae98ff63fc0",
        "ba2511b34e50",
        "ba475b71cc60",
        "82515b7b7900",
        "fe91ad59d388",
    )

    private val V10_L = rows(
        "fe5dddeeeeeb3f8",
        "823f773bbbbd208",
        "baf2225111132e8",
        "ba09016444412e8",
        "ba754d7eeeed2e8",
        "825be7a3bbba208",
        "feaaaaaaaaaabf8",
        "00ced4e32525800",
        "efacf73f0f0f620",
        "44952271a5a4170",
        "7262caa52f2ec30",
        "bc59f98ceeefe98",
        "4333535fbbbb3d0",
        "ecf040751110168",
        "b2c288a64444c38",
        "1021dd86eeefa10",
        "8783774bbbba8c0",
        "186022611110a68",
        "72ec88ac44442b8",
        "40bfdd8eeeeee90",
        "faedefdbbbbb3c0",
        "0ca52b511110148",
        "ba4381e44444c78",
        "a9e14d4e5253ed0",
        "7375777af0f13e0",
        "78a022705a5a160",
        "3f9eacbef2f2fa8",
        "98919fe2eeef880",
        "cabd352bbbbaac8",
        "78d0042311108e8",
        "3ffa88fe4445fb8",
        "509dddbaeeeeb90",
        "061d7735bbba940",
        "08c022471111468",
        "aa4a88cc44456b8",
        "9ddfddbceeef390",
        "dabefea9bbba2c0",
        "913ab2cd11110b0",
        "971418464444210",
        "18a8d4ab25248b0",
        "cf5b77510f0e928",
        "819a2205a5a5470",
        "27f2ca8f2f2f6b0",
        "cda1f99aeeeeb98",
        "cb635331bbba950",
        "d58a40451111468",
        "a6f288ce44456b8",
        "f99dddbaeeeeb90",
        "02a3773fbbbafc0",
        "00fe226311118e8",
        "fed688aa4444ab8",
        "82f3dde2eeef890",
        "babfefffbbbbfc0",
        "ba7f2b2d11116d0",
        "bad1819e44453e8",
        "82a14d545253950",
        "febb7766f0f1478",
    )

    private val V14_Q = rows(
        "fe0e71576d694da2bf8",
        "829709ec06f963ba208",
        "ba31f9fca356c5a42e8",
        "babc8e80ef912ddf2e8",
        "ba9ae7fe660f9b05ae8",
        "824dd189a3789d8a208",
        "feaaaaaaaaaaaaaabf8",
        "00ee718c7468d383000",
        "5eb796f95f6fd9fb6d0",
        "50d92382587a9897ea8",
        "efc05c393b8e20c8ec0",
        "dd0aab295213a6d5bb0",
        "4b5fdfd45ebd6e89140",
        "31edb0da64f9da53018",
        "ba7897c5910d445e568",
        "ddfae21cd4230a27b78",
        "16395c9d6837bbcde38",
        "543b73a7ca632f85bd8",
        "a6acd6799d25f8ec7c0",
        "90fc7054e2b0cb63640",
        "4fad96e8caec4a1b590",
        "48e92a92442b9177938",
        "f78e0cb1a7b62fe8898",
        "d908bb34466a2cb1fa8",
        "6fdddeffc8aff6cefb8",
        "08eda08f7ca8c015888",
        "babec6a98d7ada1dab0",
        "d89e2a88442880648a0",
        "2ff92cf96c3fa7adf80",
        "4cdf62b4d271b584ab8",
        "b7ac0ed71f276e84420",
        "b8bcf0e1e0aac917780",
        "4a4e17735afeca4c0b0",
        "4c4a339e40381121c28",
        "d28e1d8c3bb4a7ba900",
        "fd09e38cd67024a7a30",
        "66db3e2848b6eef8020",
        "2d6db9b964aa582bb08",
        "9b3846938d6fca66ab0",
        "f59c60a8e4394012ea0",
        "2bfa4c7eec24c7d8100",
        "b4d8fcfad271558aeb8",
        "8fab8bc55f26cef1a20",
        "d8b8f6e6812b692db80",
        "dfcc9df97bffca61fb0",
        "b8cb9189813891098a8",
        "4a87a6aedb3aa75ea80",
        "7886798d7668e5918b0",
        "3fceb7fbc8af8f6efa0",
        "f0fe66b6e5a1b934608",
        "aba9da074d746b733b0",
        "98145563a4b3a188f20",
        "bbf207b0cc2626d3680",
        "9141b9c513fab59a3b8",
        "0b635515febd6ffef20",
        "1160e348c441cc25e00",
        "168c9ecf5c194dfc3b0",
        "b592b4e1c055b610ec8",
        "c28f457cfee14443ee0",
        "bd063e455bf0c61ab50",
        "f317d3abf1b66f77760",
        "fc3ea4e8dd21d9ac680",
        "d6489a3123ec23653b8",
        "18c47761e8dbf408eb8",
        "8a3b43fabadfd1d3f88",
        "0080798d7a18d39b8d0",
        "fe2ad3ade26acc77ad8",
        "82b0e488a528bbac8e0",
        "ba859eff6befb3e5fe8",
        "ba9a373981ad6889aa8",
        "ba7e41bcc5229ed3398",
        "82d438a52a8758dbf38",
        "fe19d053b65c9897b28",
    )
}
