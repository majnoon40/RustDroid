        runCheck("stubs", "bionic link stubs present (libc.so et al.)") {
            val sysroot = File(paths.kit, "sysroot")
            // Branch ORDER matters (review P3). The old code computed `sos`
            // via sysroot.listFiles() FIRST and only then tested
            // !sysroot.isDirectory: the verdict was still correct, but the
            // `when` read as though the size check could fire against a
            // missing directory, when in that case the list is always empty
            // and the directory branch has already returned. Testing
            // isDirectory first makes each branch's reachability obvious and
            // keeps the diagnostic naming the actual problem.
            if (!sysroot.isDirectory) return@runCheck "missing ${sysroot.path}"
            val sos = sysroot.listFiles()?.filter { it.name.endsWith(".so") } ?: emptyList()
            when {
                sos.size < 3 -> "only ${sos.size} stub libs (want >= 3 incl. libc.so)"
                File(sysroot, "libc.so").isFile.not() -> "libc.so stub missing — links impossible"
                else -> null
            }
        }
