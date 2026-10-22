package com.nfchider.location

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled [DexSQL](https://github.com/LSPosed/dexsql-release) CLI against an
 * installed app's APK (or any DEX/APK file).
 *
 * DexSQL is a glibc-linked Linux/arm64 binary, so it cannot run directly on Android's
 * bionic libc. We ship the GNU dynamic loader (`ld-linux-aarch64.so.1`) and the required
 * glibc shared libraries alongside it, and invoke the binary through the loader with an
 * explicit `--library-path`.
 *
 * For repeated queries against the same APK use [prepareCache] once and then [queryCache];
 * this parses the input only once and is dramatically faster on large apps.
 */
object DexSqlRunner {

    private const val ASSET_DIR = "dexsql"
    private const val CACHE_DIR = "dexsql-cache"
    private val ASSET_FILES = listOf(
        "dexsql",
        "ld-linux-aarch64.so.1",
        "libc.so.6",
        "libpthread.so.0",
        "libdl.so.2"
    )

    /**
     * Copies the bundled DexSQL binary and its glibc dependencies into the app's
     * files directory and makes the executable bits executable. Safe to call repeatedly;
     * existing files are left untouched.
     */
    fun ensureInstalled(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR)
        if (!dir.exists()) dir.mkdirs()
        val assets = context.assets
        for (name in ASSET_FILES) {
            val out = File(dir, name)
            if (!out.exists()) {
                assets.open("$ASSET_DIR/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
            // Only the loader and the dexsql binary need the execute bit.
            if (name == "dexsql" || name == "ld-linux-aarch64.so.1") {
                out.setExecutable(true, false)
            }
        }
        return dir
    }

    /**
     * Runs `dexsql query --input <apkPath> --sql <sql>` and returns the combined
     * stdout/stderr as a string. Use [limit] to cap the number of returned rows.
     *
     * @param trustedInput when true, passes `--trusted-input` to skip source
     *   revalidation. Only enable for inputs from a controlled build pipeline.
     * @param threads worker thread count; 0 means auto (available CPUs).
     */
    fun query(
        context: Context,
        apkPath: String,
        sql: String,
        limit: Int = 500,
        timeoutSec: Long = 60,
        trustedInput: Boolean = false,
        threads: Int = 0
    ): String = run(
        context,
        buildList {
            add("query")
            add("--input"); add(apkPath)
            add("--sql"); add(sql)
            add("--limit"); add(limit.toString())
            if (trustedInput) add("--trusted-input")
            if (threads > 0) { add("--threads"); add(threads.toString()) }
        },
        timeoutSec
    )

    /**
     * Runs `dexsql tables --input <apkPath>` and returns the built-in table schema.
     */
    fun tables(context: Context, apkPath: String, timeoutSec: Long = 30): String =
        run(context, listOf("tables", "--input", apkPath), timeoutSec)

    /**
     * Runs `dexsql decode --input <apkPath> --method <methodSig>` and returns the
     * disassembled bytecode of a single method.
     *
     * Example method signature: `Lcom/example/Foo;->bar(I)V`
     */
    fun decode(
        context: Context,
        apkPath: String,
        methodSignature: String,
        timeoutSec: Long = 30
    ): String = run(
        context,
        listOf("decode", "--input", apkPath, "--method", methodSignature),
        timeoutSec
    )

    // ---------------------------------------------------------------------
    // Cache support: parse once, query many times
    // ---------------------------------------------------------------------

    /**
     * Runs `dexsql prepare-cache --input <apkPath> --output <cacheDir>` to build a
     * reusable `.dxc2` cache container. Returns the path to `cache.dxc2`, which can
     * be passed to [queryCache].
     *
     * The cache directory is named after a hash of the APK path so different apps
     * keep separate caches. Existing caches are reused unless the source APK's
     * last-modified time changed.
     */
    fun prepareCache(
        context: Context,
        apkPath: String,
        timeoutSec: Long = 120,
        trustedInput: Boolean = false
    ): String {
        val cacheDir = cacheDirFor(context, apkPath)
        val cacheFile = File(cacheDir, "cache.dxc2")
        val src = File(apkPath)

        // Reuse a cache that matches the source's size + mtime.
        if (cacheFile.exists()) {
            val stamp = File(cacheDir, "source.stamp")
            if (stamp.exists()) {
                val expected = "${src.length()}:${src.lastModified()}"
                if (stamp.readText().trim() == expected) {
                    return cacheFile.absolutePath
                }
            }
        }

        cacheDir.mkdirs()
        val args = buildList {
            add("prepare-cache")
            add("--input"); add(apkPath)
            add("--output"); add(cacheDir.absolutePath)
            if (trustedInput) add("--trusted-input")
        }
        val out = run(context, args, timeoutSec)
        if (!cacheFile.exists()) {
            return "[dexsql prepare-cache failed]\n$out"
        }
        File(cacheDir, "source.stamp").writeText("${src.length()}:${src.lastModified()}")
        return cacheFile.absolutePath
    }

    /**
     * Runs `dexsql query-cache --cache-file <cacheFile> --sql <sql>` against a cache
     * previously created by [prepareCache]. Much faster than [query] for repeated
     * queries on the same APK.
     */
    fun queryCache(
        context: Context,
        cacheFile: String,
        sql: String,
        limit: Int = 500,
        timeoutSec: Long = 30,
        threads: Int = 0
    ): String = run(
        context,
        buildList {
            add("query-cache")
            add("--cache-file"); add(cacheFile)
            add("--sql"); add(sql)
            add("--limit"); add(limit.toString())
            if (threads > 0) { add("--threads"); add(threads.toString()) }
        },
        timeoutSec
    )

    /** Convenience: prepare cache (if needed) then run a cached query in one call. */
    fun queryWithCache(
        context: Context,
        apkPath: String,
        sql: String,
        limit: Int = 500,
        timeoutSec: Long = 60
    ): String {
        val cacheFile = prepareCache(context, apkPath, timeoutSec = timeoutSec)
        if (cacheFile.startsWith("[")) return cacheFile // prepare-cache failed
        return queryCache(context, cacheFile, sql, limit, timeoutSec)
    }

    // ---------------------------------------------------------------------
    // Execution internals
    // ---------------------------------------------------------------------

    private fun cacheDirFor(context: Context, apkPath: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(apkPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return File(context.cacheDir, "$CACHE_DIR/$hash")
    }

    private fun run(context: Context, args: List<String>, timeoutSec: Long): String {
        val dir = ensureInstalled(context)
        val loader = File(dir, "ld-linux-aarch64.so.1").absolutePath
        val dexsql = File(dir, "dexsql").absolutePath

        val cmd = ArrayList<String>().apply {
            add(loader)
            add("--library-path")
            add(dir.absolutePath)
            add(dexsql)
            addAll(args)
        }

        return try {
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                output + "\n[dexsql timed out after ${timeoutSec}s]"
            } else {
                output
            }
        } catch (t: Throwable) {
            "[dexsql exec failed] ${t.javaClass.simpleName}: ${t.message}"
        }
    }
}
