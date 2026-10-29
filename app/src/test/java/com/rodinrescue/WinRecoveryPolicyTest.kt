package com.rodinrescue

import com.rodinrescue.security.Risk
import com.rodinrescue.security.WinRecoveryPolicy
import org.junit.Assert.*
import org.junit.Test

class WinRecoveryPolicyTest {
    private fun r(c: String) = WinRecoveryPolicy.classify(c).risk

    @Test fun readsRunWithoutAsking() {
        listOf("dir C:\\", "dir D:\\Windows", "D:", "bcdedit", "bcdedit /enum", "bcdedit /enum all", "bootrec /scanos", "reagentc /info",
            "chkdsk D:", "manage-bde -status", "manage-bde -status C:", "dism /image:D:\\ /get-packages", "dism /image:D:\\ /cleanup-image /checkhealth",
            "sfc /verifyonly /offbootdir=D:\\ /offwindir=D:\\Windows", "type D:\\Windows\\Logs\\CBS\\CBS.log | find \"error\"", "ver", "vol D:",
            "wmic logicaldisk get name,volumename", "mountvol", "set", "attrib D:\\boot.ini", "list volume", "list disk", "detail disk", "exit")
            .forEach { assertEquals(it, Risk.READ_ONLY, r(it)) }
    }

    @Test fun repairsAsk() {
        listOf("chkdsk D: /f", "chkdsk D: /r", "sfc /scannow /offbootdir=D:\\ /offwindir=D:\\Windows", "dism /image:D:\\ /cleanup-image /revertpendingactions",
            "dism /image:D:\\ /cleanup-image /restorehealth", "copy D:\\a D:\\b", "ren D:\\Windows\\x x.old", "diskpart", "notepad", "dir > D:\\x.txt",
            "echo %PATH%", "set x=1", "mountvol S: /s", "attrib -h -s D:\\x", "ipconfig /renew", "bootrec /scanos & notepad",
            "manage-bde -unlock D: -recoverypassword 111111-222222", "find / -delete", "type x | more ^& del y")
            .forEach { assertNotEquals(it, Risk.READ_ONLY, r(it)) }
    }

    @Test fun bootRepairAndDeletionsNeedTypedConfirm() {
        listOf("bootrec /fixmbr", "bootrec /fixboot", "bootrec /rebuildbcd", "bcdboot D:\\Windows", "bcdboot D:\\Windows /s S: /f UEFI",
            "bcdedit /deletevalue {default} safeboot", "bcdedit /set {default} recoveryenabled no", "del D:\\Windows\\Temp\\x.tmp",
            "rd /s /q D:\\Windows\\SoftwareDistribution", "reg delete HKLM\\x\\y /f", "dism /image:D:\\ /remove-package /packagename:x", "active")
            .forEach { assertEquals(it, Risk.DESTRUCTIVE, r(it)) }
    }

    @Test fun neverTyped() {
        listOf("format D: /q", "clean", "clean all", "delete partition override", "convert gpt", "create partition primary", "diskpart /s x.txt",
            "manage-bde -off D:", "bcdedit /set testsigning on", "net user admin NewPass1", "rd /s /q D:\\", "del /s /q D:\\Windows",
            "rd /s /q D:\\Users", "reg delete HKLM\\SYSTEM /f")
            .forEach { assertEquals(it, Risk.FORBIDDEN, r(it)) }
    }

    @Test fun theShellChoiceCanOnlyMakeThingsStricter() {
        val k = com.rodinrescue.ai.KeyPolicy
        assertEquals(Risk.READ_ONLY, k.typedRisk("dir D:\\Windows", true).risk)
        assertNotEquals(Risk.READ_ONLY, k.typedRisk("dir D:\\Windows", false).risk)
        assertEquals(Risk.READ_ONLY, k.typedRisk("journalctl -b -p err --no-pager", false).risk)
        assertNotEquals(Risk.READ_ONLY, k.typedRisk("journalctl -b -p err --no-pager", true).risk)
        // wrong shell claimed: Linux danger still caught, Windows danger still caught
        assertNotEquals(Risk.READ_ONLY, k.typedRisk("find / -delete", true).risk)
        assertEquals(Risk.DESTRUCTIVE, k.typedRisk("rm -f /etc/x", true).risk)
        assertEquals(Risk.FORBIDDEN, k.typedRisk("mkfs.ext4 /dev/sda1", true).risk)
        assertEquals(Risk.FORBIDDEN, k.typedRisk("format D: /q", false).risk)
        assertEquals(Risk.DESTRUCTIVE, k.typedRisk("bcdboot D:\\Windows", false).risk)
    }
}
