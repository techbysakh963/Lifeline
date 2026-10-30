package com.rodinrescue

import com.rodinrescue.ui.toolLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chat shows a tool call as one readable line instead of its name and raw JSON. */
class ToolLineTest {
    @Test fun usesTheAisPurposeWhenGiven() {
        assertEquals("Check UEFI boot entries", toolLine("run_readonly_command", """{"command":"efibootmgr -v","purpose":"check UEFI boot entries"}"""))
        assertEquals("Read the \"boot\" log", toolLine("run_readonly_command", """{"purpose":"read the \"boot\" log","command":"journalctl -b"}"""))
    }

    @Test fun fallsBackToWordsForTheTool() {
        assertEquals("Checking disk space", toolLine("check_disk_space", "{}"))
        assertEquals("Looking around", toolLine("run_readonly_command", """{"command":"ls","purpose":""}"""))
        assertEquals("Press keys", toolLine("press_keys", """{"keys":["enter"]}"""))
    }
}
