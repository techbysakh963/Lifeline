package com.rodinrescue

import com.rodinrescue.usb.RescueSystem
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RescueSystemTest {
    private val pub = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC7 rodin-rescue"

    @Test fun theKeyDriveHoldsBothKeysInTheFormatTheImageReads() {
        val host = RescueSystem.newHostKey()
        val d = RescueSystem.keyDrive(pub, host.privatePem)
        assertEquals(RescueSystem.KEY_DRIVE_SIZE, d.size); assertEquals(0, d.size % 512)
        // What rescue-image/.../lifeline-key does: drop the zeros, read the lines.
        val lines = String(d.filter { it != 0.toByte() }.toByteArray()).lines()
        assertEquals(RescueSystem.MAGIC, lines[0]); assertTrue("end" in lines)
        fun field(k: String) = String(Base64.getDecoder().decode(lines.first { it.startsWith("$k ") }.substringAfter(' ')))
        assertEquals("$pub\n", field("authorized_key"))
        assertTrue(field("host_key").startsWith("-----BEGIN PRIVATE KEY-----\n") && field("host_key").trimEnd().endsWith("-----END PRIVATE KEY-----"))
        assertEquals("EC", host.public.algorithm)
    }

    @Test fun aClosedAppCanPickTheSessionUpFromTheKeyDrive() {
        val host = RescueSystem.newHostKey()
        val d = RescueSystem.keyDrive(pub, host.privatePem, host.public)
        assertEquals(host.public, RescueSystem.hostPublicOf(d))
        assertNull("a drive without it (or not ours) gives nothing", RescueSystem.hostPublicOf(RescueSystem.keyDrive(pub, host.privatePem)))
        assertNull(RescueSystem.hostPublicOf(ByteArray(64)))
    }

    @Test fun eachSessionGetsItsOwnHostKeyAndOnlyOneAuthorizedLine() {
        assertNotEquals(RescueSystem.newHostKey().privatePem, RescueSystem.newHostKey().privatePem)
        assertThrows(IllegalArgumentException::class.java) { RescueSystem.keyDrive("$pub\nssh-rsa AAAA evil", "x") }
        assertThrows(IllegalArgumentException::class.java) { RescueSystem.keyDrive(" ", "x") }
    }
}
