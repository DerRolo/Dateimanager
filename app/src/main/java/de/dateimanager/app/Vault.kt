package de.dateimanager.app

import java.io.File

/** Tresor: Dateien werden auf demselben Speicher in den versteckten Ordner .DM_Tresor verschoben (nicht verschlüsselt). */
object Vault {
    private const val DIR = ".DM_Tresor"
    private const val INDEX = "index.tsv"

    private data class Entry(val id: String, val orig: String, val at: Long)

    private fun trashDir(root: Root) = File(root.dir, DIR)

    private fun fileOf(root: Root, e: Entry) = File(trashDir(root), e.id + "_" + File(e.orig).name)

    private fun readIndex(root: Root): MutableList<Entry> {
        val out = ArrayList<Entry>()
        val f = File(trashDir(root), INDEX)
        if (!f.exists()) return out
        for (line in f.readLines()) {
            val p = line.split('\t')
            val at = p.getOrNull(2)?.toLongOrNull()
            if (p.size >= 3 && at != null) out.add(Entry(p[0], p[1], at))
        }
        return out
    }

    private fun writeIndex(root: Root, list: List<Entry>) {
        val d = trashDir(root)
        d.mkdirs()
        val nm = File(d, ".nomedia")
        if (!nm.exists()) nm.createNewFile()
        File(d, INDEX).writeText(list.joinToString("\n") { it.id + "\t" + it.orig + "\t" + it.at })
    }

    fun hideIn(roots: List<Root>, files: List<File>): Int {
        var ok = 0
        for (root in roots) {
            val mine = files.filter { it.path.startsWith(root.dir.path + "/") }
            if (mine.isEmpty()) continue
            val idx = readIndex(root)
            val dir = trashDir(root)
            dir.mkdirs()
            for (f in mine) {
                val id = System.currentTimeMillis().toString() + "_" + (Math.random() * 100000).toInt()
                val target = File(dir, id + "_" + f.name)
                if (f.renameTo(target)) {
                    idx.add(Entry(id, f.path, System.currentTimeMillis()))
                    ok++
                }
            }
            writeIndex(root, idx)
        }
        return ok
    }

    fun list(roots: List<Root>): List<FileItem> {
        val out = ArrayList<FileItem>()
        for (r in roots) {
            for (e in readIndex(r)) {
                val f = fileOf(r, e)
                if (!f.exists()) continue
                val orig = File(e.orig)
                out.add(
                    FileItem(
                        f.path, orig.name, f.isDirectory,
                        if (f.isDirectory) 0L else f.length(), e.at, 0L, orig.parent
                    )
                )
            }
        }
        return out
    }

    private fun process(roots: List<Root>, only: Set<String>?, act: (Entry, File) -> Boolean): Int {
        var n = 0
        for (r in roots) {
            val idx = readIndex(r)
            if (idx.isEmpty()) continue
            val keep = ArrayList<Entry>()
            for (e in idx) {
                val tf = fileOf(r, e)
                if (only == null || tf.path in only) {
                    if (!tf.exists()) continue
                    if (act(e, tf)) n++ else keep.add(e)
                } else {
                    keep.add(e)
                }
            }
            writeIndex(r, keep)
        }
        return n
    }

    fun bringOut(roots: List<Root>, paths: Set<String>, restored: MutableList<String>): Int =
        process(roots, paths) { e, tf ->
            val orig = File(e.orig)
            val parent = orig.parentFile
            if (parent == null) {
                false
            } else {
                parent.mkdirs()
                val target = Fs.uniqueTarget(parent, orig.name, tf.isDirectory)
                if (tf.renameTo(target)) {
                    restored.addAll(Fs.collect(target).map { it.path })
                    true
                } else false
            }
        }

    fun deleteForever(roots: List<Root>, paths: Set<String>): Int =
        process(roots, paths) { _, tf -> tf.deleteRecursively() }
}
