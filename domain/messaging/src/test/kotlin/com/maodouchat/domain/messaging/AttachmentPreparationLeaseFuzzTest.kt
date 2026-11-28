package com.maodouchat.domain.messaging

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// ---------- oracle ----------

// UTF-16 码元 hex 编码：串里出现分隔符也不怕。
private fun encStr199a(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        val cp = ch.code
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            out.append("%04x".format(0xD800 + (v shr 10)))
            out.append("%04x".format(0xDC00 + (v and 0x3FF)))
        } else {
            out.append("%04x".format(cp))
        }
    }
    return out.toString()
}

private fun encOpt199a(s: String?): String = if (s == null) "NULL" else "S:" + encStr199a(s)

// op kind: 0 recordEncryptedPath, 1 recordPreparedSource, 2 handOff,
// 3 cleanupIfOwned, 4 isHandedOff。
private data class LeaseOp199a(
    val kind: Int,
    val arg: String = "",
)

private data class LeaseCase199a(
    val original: String,
    val ops: List<LeaseOp199a> = emptyList(),
)

private fun encodeLeaseResult199a(
    handedOff: Boolean,
    encPath: String?,
    prepUri: String?,
    effects: List<String>,
    cleans: List<Boolean>,
    queries: List<Boolean>,
): String = listOf(
    if (handedOff) "1" else "0",
    encOpt199a(encPath),
    encOpt199a(prepUri),
    effects.joinToString(","),
    cleans.joinToString(",") { if (it) "1" else "0" },
    queries.joinToString(",") { if (it) "1" else "0" },
).joinToString("|")

private fun leaseOracle199a(c: LeaseCase199a): String {
    var handedOff = false
    var cleaned = false
    var encPath: String? = null
    var prepUri: String? = null
    val effects = mutableListOf<String>()
    val cleans = mutableListOf<Boolean>()
    val queries = mutableListOf<Boolean>()
    for (op in c.ops) {
        when (op.kind) {
            0 -> encPath = op.arg.takeIf { it.isNotBlank() }
            1 -> prepUri = op.arg.takeIf { it.isNotBlank() }
            2 -> handedOff = true
            3 -> {
                val ok = !handedOff && !cleaned
                if (ok) {
                    cleaned = true
                    encPath?.let { effects += "E:" + encStr199a(it) }
                    prepUri?.let { effects += "P:" + encStr199a(it) }
                    if (c.original.isNotBlank()) effects += "R:" + encStr199a(c.original)
                }
                cleans += ok
            }
            4 -> queries += handedOff
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return encodeLeaseResult199a(handedOff, encPath, prepUri, effects, cleans, queries)
}

private fun leaseProd199a(c: LeaseCase199a): String {
    val effects = mutableListOf<String>()
    val cleans = mutableListOf<Boolean>()
    val queries = mutableListOf<Boolean>()
    val lease = AttachmentPreparationLease(
        originalSourceUri = c.original,
        deleteEncryptedFile = { effects += "E:" + encStr199a(it) },
        deletePreparedSource = { effects += "P:" + encStr199a(it) },
        releasePersistablePermission = { effects += "R:" + encStr199a(it) },
    )
    // 生产不暴露已记录路径，路径栏两侧都按同一 takeIf 规则填；
    // 真正的差分在 effects/cleans/queries 里（路径只经由 cleanup 可见）。
    var encPath: String? = null
    var prepUri: String? = null
    for (op in c.ops) {
        when (op.kind) {
            0 -> {
                lease.recordEncryptedPath(op.arg)
                encPath = op.arg.takeIf { it.isNotBlank() }
            }
            1 -> {
                lease.recordPreparedSource(op.arg)
                prepUri = op.arg.takeIf { it.isNotBlank() }
            }
            2 -> lease.handOff()
            3 -> cleans += lease.cleanupIfOwned()
            4 -> queries += lease.isHandedOff()
            else -> throw IllegalArgumentException("unknown op ${op.kind}")
        }
    }
    return encodeLeaseResult199a(lease.isHandedOff(), encPath, prepUri, effects, cleans, queries)
}

private val LEASE_PATHS199a = listOf(
    "",
    " ",
    "   ",
    "\t",
    "\n",
    "  x  ",
    "/private/encrypted.bin",
    "file://prepared.jpg",
    "content://original",
    "a",
    "\u00a0",
    "\u2003",
    "x\ty",
    "enc",
    "prep",
)

private fun randomLeaseCase199a(r: Random): LeaseCase199a {
    val ops = mutableListOf<LeaseOp199a>()
    repeat(r.nextInt(0, 9)) {
        when (r.nextInt(5)) {
            0 -> ops += LeaseOp199a(0, LEASE_PATHS199a[r.nextInt(LEASE_PATHS199a.size)])
            1 -> ops += LeaseOp199a(1, LEASE_PATHS199a[r.nextInt(LEASE_PATHS199a.size)])
            2 -> ops += LeaseOp199a(2)
            3 -> ops += LeaseOp199a(3)
            else -> ops += LeaseOp199a(4)
        }
    }
    return LeaseCase199a(LEASE_PATHS199a[r.nextInt(LEASE_PATHS199a.size)], ops)
}

private fun boundaryLeaseCase199a(r: Random): LeaseCase199a {
    val seqs = listOf(
        listOf(LeaseOp199a(3)),
        listOf(LeaseOp199a(2), LeaseOp199a(3)),
        listOf(LeaseOp199a(0, "enc"), LeaseOp199a(1, "prep"), LeaseOp199a(3), LeaseOp199a(3)),
        listOf(LeaseOp199a(2), LeaseOp199a(0, "enc"), LeaseOp199a(1, "prep"), LeaseOp199a(3)),
        listOf(LeaseOp199a(0, "   "), LeaseOp199a(1, ""), LeaseOp199a(3)),
        listOf(LeaseOp199a(0, "\u00a0"), LeaseOp199a(1, "\u2003"), LeaseOp199a(3)),
        listOf(LeaseOp199a(3), LeaseOp199a(0, "enc"), LeaseOp199a(3)),
        listOf(LeaseOp199a(4), LeaseOp199a(2), LeaseOp199a(4), LeaseOp199a(3), LeaseOp199a(4)),
        listOf(LeaseOp199a(0, "e1"), LeaseOp199a(0, "e2"), LeaseOp199a(3)),
        listOf(LeaseOp199a(1, "p1"), LeaseOp199a(1, ""), LeaseOp199a(3)),
    )
    val originals = listOf("content://original", " ", "", "\u00a0")
    return LeaseCase199a(originals[r.nextInt(originals.size)], seqs[r.nextInt(seqs.size)])
}

// ---------- tests ----------

class AttachmentPreparationLeaseFuzzTest {

    @Test
    fun leaseDifferential199() {
        val r = Random(199101)
        repeat(300) {
            val c = randomLeaseCase199a(r)
            assertEquals(leaseOracle199a(c), leaseProd199a(c))
        }
    }

    @Test
    fun leaseBoundaryDifferential199() {
        val r = Random(199102)
        repeat(300) {
            val c = boundaryLeaseCase199a(r)
            assertEquals(leaseOracle199a(c), leaseProd199a(c))
        }
    }

    @Test
    fun leasePinned199() {
        val effects = mutableListOf<String>()
        val lease = AttachmentPreparationLease(
            originalSourceUri = "content://original",
            deleteEncryptedFile = { effects += "E:$it" },
            deletePreparedSource = { effects += "P:$it" },
            releasePersistablePermission = { effects += "R:$it" },
        )
        lease.recordPreparedSource("file://prepared.jpg")
        lease.recordEncryptedPath("/private/encrypted.bin")
        assertEquals(true, lease.cleanupIfOwned())
        assertEquals(false, lease.cleanupIfOwned())
        assertEquals(
            listOf(
                "E:/private/encrypted.bin",
                "P:file://prepared.jpg",
                "R:content://original",
            ),
            effects,
        )
        // 空白路径不记录，只放行权限；不换行空格不是空白。
        val blank = LeaseCase199a(
            "content://original",
            listOf(LeaseOp199a(0, "   "), LeaseOp199a(1, ""), LeaseOp199a(3)),
        )
        assertEquals(leaseOracle199a(blank), leaseProd199a(blank))
        val nbsp = LeaseCase199a(
            "content://original",
            listOf(LeaseOp199a(0, "\u00a0"), LeaseOp199a(3)),
        )
        assertEquals(leaseOracle199a(nbsp), leaseProd199a(nbsp))
        val handed = LeaseCase199a(
            "content://original",
            listOf(LeaseOp199a(0, "enc"), LeaseOp199a(2), LeaseOp199a(3), LeaseOp199a(4)),
        )
        assertEquals(leaseOracle199a(handed), leaseProd199a(handed))
    }
}
