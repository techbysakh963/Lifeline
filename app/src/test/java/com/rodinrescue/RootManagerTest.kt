package com.rodinrescue

import com.rodinrescue.root.RootShell
import org.junit.Assert.assertEquals
import org.junit.Test

class RootManagerTest {
    @Test fun namesTheRootManagerFromSuVersion() {
        assertEquals("Magisk", RootShell.managerFrom("28.1:MAGISKSU\n"))   // seen on a Galaxy S9 (LineageOS 20)
        assertEquals("KernelSU", RootShell.managerFrom("12056:KernelSU"))
        assertEquals("APatch", RootShell.managerFrom("10983:APatch"))
        assertEquals("your root manager", RootShell.managerFrom(""))
    }
}
