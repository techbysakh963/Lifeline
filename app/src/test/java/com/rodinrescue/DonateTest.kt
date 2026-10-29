package com.rodinrescue

import com.rodinrescue.ui.Donate
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** A wrong character in a wallet address sends money to someone else: keep every copy identical. */
class DonateTest {
    @Test fun addressIsWellFormedAndEverywhereTheSame() {
        assertTrue(Donate.ADDRESS, Regex("^0x[0-9a-f]{40}$").matches(Donate.ADDRESS))
        // Unit tests run with the module dir (app/) as working directory.
        for (path in listOf("../README.md", "../scripts/dev/make_donate_qr.py")) {
            val text = File(path).readText()
            val found = Regex("0x[0-9a-fA-F]{40}").findAll(text).map { it.value.lowercase() }.toSet()
            assertEquals(path, setOf(Donate.ADDRESS), found)
        }
        assertTrue(File("src/main/res/drawable-nodpi/donate_usdt_qr.png").length() > 0)
    }
}
