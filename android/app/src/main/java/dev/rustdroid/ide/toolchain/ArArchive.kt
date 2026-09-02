package dev.rustdroid.ide.toolchain

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Minimal `ar` archive reader for static libraries (libunwind.a et al.).
 * Supports GNU ("/") and BSD ("__.SYMDEF") symbol tables — enough to verify
 * that an archive defines the symbols we need, without shipping binutils.
 *
 * Pure JVM — unit-tested with synthetic archives.
 */
object ArArchive {

    private const val MAGIC = "!<arch>\n"
    private const val HEADER_SIZE = 60

    data class ArInfo(val members: Int, val symbols: List<String>)

    fun read(input: InputStream): ArInfo {
        val magic = ByteArray(MAGIC.length)
        readFully(input, magic)
        if (String(magic, Charsets.US_ASCII) != MAGIC) {
            throw IOException("not an ar archive (bad magic)")
        }
        var members = 0
        val symbols = mutableListOf<String>()
        while (true) {
            val header = ByteArray(HEADER_SIZE)
            try {
                readFully(input, header)
            } catch (e: EOFException) {
                break
            }
            val name = String(header, 0, 16, Charsets.US_ASCII).trimEnd('\u0000', ' ')
            val sizeStr = String(header, 48, 10, Charsets.US_ASCII).trim()
            val size = sizeStr.toLongOrNull() ?: throw IOException("bad ar header size: '$sizeStr'")
            val terminator = String(header, 58, 2, Charsets.US_ASCII)
            if (terminator != "`\n") throw IOException("bad ar header terminator: '$terminator'")

            when {
                name == "/" || name.startsWith("__.SYMDEF") -> readSymbols(input, size, name, symbols)
                name == "//" || name.isEmpty() -> skip(input, size)
                else -> {
                    skip(input, size)
                    members++
                }
            }
            // ar members are padded to even length
            if (size % 2 == 1L) {
                if (input.read() == -1) break
            }
        }
        return ArInfo(members, symbols)
    }

    private fun readSymbols(input: InputStream, size: Long, name: String, out: MutableList<String>) {
        if (size <= 0 || size > 64L * 1024 * 1024) return
        val data = ByteArray(size.toInt())
        readFully(input, data)
        // Both GNU and BSD flavors: u32 count, then count*(u32 offset),
        // then null-terminated names. Symbol count sanity-clamped.
        val count = be32(data, 0).coerceAtMost(200_000)
        var p = 4 + 4 * count
        var read = 0
        while (p < data.size && read < count) {
            val end = findZero(data, p)
            if (end < 0) break
            if (end > p) out.add(String(data, p, end - p, Charsets.US_ASCII))
            p = end + 1
            read++
        }
    }

    private fun findZero(b: ByteArray, from: Int): Int {
        for (i in from until b.size) if (b[i] == 0.toByte()) return i
        return -1
    }

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("unexpected EOF at $off of ${buf.size}")
            off += n
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            val n = input.skip(remaining).toInt()
            if (n <= 0) {
                if (input.read() == -1) throw EOFException("EOF skipping member")
                remaining -= 1
            } else remaining -= n
        }
    }
}
