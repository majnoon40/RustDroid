package dev.rustdroid.ide

import dev.rustdroid.ide.toolchain.ArArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ArArchiveTest {

    /** Builds a GNU-format ar archive: symbol table + one member. */
    private fun gnuAr(symbolNames: List<String>, memberName: String = "hello.o", memberData: ByteArray = byteArrayOf(1, 2, 3)): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray(Charsets.US_ASCII))

        // symbol table member "/"
        val names = symbolNames.joinToString("") { "$it\u0000" }.toByteArray(Charsets.US_ASCII)
        val offsets = ByteArray(symbolNames.size * 4) // zeros fine for parsing names
        val count = intToBe32(symbolNames.size)
        val symData = count + offsets + names
        out.write(memberHeader("/", symData.size.toLong()))
        out.write(symData)
        if (symData.size % 2 == 1) out.write(0x0A)

        // member
        out.write(memberHeader(memberName, memberData.size.toLong()))
        out.write(memberData)
        if (memberData.size % 2 == 1) out.write(0x0A)
        return out.toByteArray()
    }

    private fun memberHeader(name: String, size: Long): ByteArray {
        val n = name.padEnd(16, ' ')
        val date = "0".padStart(12, ' ')
        val uid = "0".padStart(6, ' ')
        val gid = "0".padStart(6, ' ')
        val mode = "644".padStart(8, ' ')
        val sz = size.toString().padStart(10, ' ')
        return "$n$date$uid$gid$mode$sz`\n".toByteArray(Charsets.US_ASCII)
    }

    private fun intToBe32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    @Test
    fun `reads gnu symbol table`() {
        val ar = gnuAr(listOf("_Unwind_Resume", "_Unwind_Backtrace"))
        val info = ArArchive.read(ByteArrayInputStream(ar))
        assertEquals(1, info.members)
        assertTrue(info.symbols.contains("_Unwind_Resume"))
        assertTrue(info.symbols.contains("_Unwind_Backtrace"))
    }

    @Test
    fun `detects unwind symbols presence`() {
        val ar = gnuAr(listOf("foo", "_Unwind_GetIP"))
        val info = ArArchive.read(ByteArrayInputStream(ar))
        assertTrue(info.symbols.any { it.startsWith("_Unwind") })
    }

    @Test
    fun `rejects non-archive data`() {
        try {
            ArArchive.read(ByteArrayInputStream("not an archive".toByteArray()))
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("not an ar"))
        }
    }

    @Test
    fun `empty symbol table is tolerated`() {
        val ar = gnuAr(emptyList())
        val info = ArArchive.read(ByteArrayInputStream(ar))
        assertEquals(1, info.members)
        assertTrue(info.symbols.isEmpty())
    }
}
