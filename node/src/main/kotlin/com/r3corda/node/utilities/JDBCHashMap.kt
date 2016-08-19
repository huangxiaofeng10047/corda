package com.r3corda.node.utilities

import com.r3corda.core.serialization.deserialize
import com.r3corda.core.serialization.serialize
import com.r3corda.core.utilities.loggerFor
import com.r3corda.core.utilities.trace
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Blob
import java.util.*
import kotlin.system.measureTimeMillis

/**
 * The classes in this file provide a convenient way to quickly implement persistence for map- and set-like
 * collections of data.  These might not be sufficient for the eventual implementations of persistence dependent on
 * access patterns and performance requirements.
 */

/**
 * A convenient JDBC table backed hash map with iteration order based on insertion order.
 * See [AbstractJDBCHashMap] for further implementation details.
 *
 * In this subclass, keys and values are represented by Blobs of Kryo serialized forms of the key and value objects.
 * If you can extend [AbstractJDBCHashMap] and implement less Kryo dependent key and/or value mappings then that is
 * likely preferrable.
 */
class JDBCHashMap<K : Any, V : Any>(tableName: String, loadOnInit: Boolean = false) : AbstractJDBCHashMap<K, V, JDBCHashMap.BlobMapTable>(BlobMapTable(tableName), loadOnInit) {

    class BlobMapTable(tableName: String) : JDBCHashedTable(tableName) {
        val key = blob("key")
        val value = blob("value")
    }

    override fun keyFromRow(it: ResultRow): K = deserializeFromBlob(it[table.key])
    override fun valueFromRow(it: ResultRow): V = deserializeFromBlob(it[table.value])

    override fun addKeyToInsert(it: InsertStatement, entry: Map.Entry<K, V>, finalizables: MutableList<() -> Unit>) {
        it[table.key] = serializeToBlob(entry.key, finalizables)
    }

    override fun addValueToInsert(it: InsertStatement, entry: Map.Entry<K, V>, finalizables: MutableList<() -> Unit>) {
        it[table.value] = serializeToBlob(entry.value, finalizables)
    }

}

fun <T: Any> serializeToBlob(value: T, finalizables: MutableList<() -> Unit>): Blob {
    val blob = TransactionManager.currentOrNull()!!.connection.createBlob()
    finalizables += { blob.free() }
    blob.setBytes(1, value.serialize().bits)
    return blob
}

fun <T : Any> deserializeFromBlob(blob: Blob): T {
    try {
        val bytes = blob.getBytes(0, blob.length().toInt())
        return bytes.deserialize()
    } finally {
        blob.free()
    }
}

/**
 * A convenient JDBC table backed hash set with iteration order based on insertion order.
 * See [AbstractJDBCHashSet] and [AbstractJDBCHashMap] for further implementation details.
 *
 * In this subclass, elements are represented by Blobs of Kryo serialized forms of the element objects.
 * If you can extend [AbstractJDBCHashSet] and implement less Kryo dependent element mappings then that is
 * likely preferrable.
 */
class JDBCHashSet<K : Any>(tableName: String, loadOnInit: Boolean = false) : AbstractJDBCHashSet<K, JDBCHashSet.BlobSetTable>(BlobSetTable(tableName), loadOnInit) {

    class BlobSetTable(tableName: String) : JDBCHashedTable(tableName) {
        val key = blob("key")
    }

    override fun elementFromRow(it: ResultRow): K = deserializeFromBlob(it[table.key])

    override fun addElementToInsert(it: InsertStatement, entry: K, finalizables: MutableList<() -> Unit>) {
        it[table.key] = serializeToBlob(entry, finalizables)
    }
}

/**
 * Base class for JDBC backed hash set that delegates to a JDBC backed hash map where the values are all
 * [Unit] and not actually persisted.  Iteration order is order of insertion.  Iterators can remove().
 *
 * See [AbstractJDBCHashMap] for implementation details.
 */
abstract class AbstractJDBCHashSet<K : Any, T : JDBCHashedTable>(table: T, loadOnInit: Boolean = false) : MutableSet<K>, AbstractSet<K>() {
    protected val innerMap = object : AbstractJDBCHashMap<K, Unit, T>(table, loadOnInit) {
        override fun keyFromRow(it: ResultRow): K = this@AbstractJDBCHashSet.elementFromRow(it)

        // Return constant.
        override fun valueFromRow(it: ResultRow) = Unit

        override fun addKeyToInsert(it: InsertStatement, entry: Map.Entry<K, Unit>, finalizables: MutableList<() -> Unit>) =
                this@AbstractJDBCHashSet.addElementToInsert(it, entry.key, finalizables)

        // No op as not actually persisted.
        override fun addValueToInsert(it: InsertStatement, entry: Map.Entry<K, Unit>, finalizables: MutableList<() -> Unit>) {
        }

    }

    protected val table: T
        get() = innerMap.table

    override fun add(element: K): Boolean {
        if (innerMap.containsKey(element)) {
            return false
        } else {
            innerMap.put(element, Unit)
            return true
        }
    }

    override fun clear() {
        innerMap.clear()
    }

    override fun iterator(): MutableIterator<K> = innerMap.keys.iterator()

    override fun remove(element: K): Boolean = (innerMap.remove(element) != null)

    override val size: Int
        get() = innerMap.size

    override fun contains(element: K): Boolean = innerMap.containsKey(element)

    override fun isEmpty(): Boolean = innerMap.isEmpty()

    /**
     * Implementation should return the element object marshalled from the database table row.
     *
     * See example implementations in [JDBCHashSet].
     */
    protected abstract fun elementFromRow(it: ResultRow): K

    /**
     * Implementation should marshall the element to the insert statement.
     *
     * If some cleanup is required after the insert statement is executed, such as closing a Blob, then add a closure
     * to the finalizables to do so.
     *
     * See example implementations in [JDBCHashSet].
     */
    protected abstract fun addElementToInsert(it: InsertStatement, entry: K, finalizables: MutableList<() -> Unit>)
}

/**
 * A base class for a JDBC table backed hash map that iterates in insertion order by using
 * an ever increasing sequence number on entries.  Iterators supports remove() but entries are not really mutable and
 * do not support setValue() method from [MutableMap.MutableEntry].
 *
 * You should only use keys that have overridden [Object.hashCode] and that have a good hash code distribution.  Beware
 * changing the hashCode() implementation once objects have been persisted.  A process to re-hash the entries persisted
 * would be necessary if you do this.
 *
 * Subclasses must provide their own mapping to and from keys/values and the database table columns, but there are
 * inherited columns that all tables must provide to support iteration order and hashing.
 *
 * The map operates in one of two modes.
 * 1. loadOnInit=true where the entire table is materialised in the JVM and only writes need to perform database access.
 * 2. loadOnInit=false where all entries with the same key hash code are materialised in the JVM on demand when accessed
 * via any method other than via keys/values/entries properties, and thus the whole map is not materialised.
 *
 * All operations require a [databaseTransaction] to be started.
 *
 * The keys/values/entries collections are really designed just for iterating and other uses might turn out to be
 * costly in terms of performance.  Beware when loadOnInit=true, the iterator first sorts the entries which could be
 * costly too.
 *
 * This class is *not* thread safe.
 *
 * TODO: buckets grows forever.  Support some form of LRU cache option (e.g. use [LinkedHashMap.removeEldestEntry] feature).
 * TODO: consider caching size once calculated for the first time.
 * TODO: buckets just use a list and so are vulnerable to poor hash code implementations with collisions.
 * TODO: if iterators are used extensively when loadOnInit=true, consider maintaining a collection of keys in iteration order to avoid sorting each time.
 * TODO: revisit whether we need the loadOnInit==true functionality and remove if not.
 */
abstract class AbstractJDBCHashMap<K : Any, V : Any, T : JDBCHashedTable>(val table: T, val loadOnInit: Boolean = false) : MutableMap<K, V>, AbstractMap<K,V>() {

    companion object {
        protected val log = loggerFor<AbstractJDBCHashMap<*,*,*>>()
    }

    // Hash code -> entries mapping.  Lazy when loadOnInit=false.
    private val buckets = HashMap<Int, MutableList<NotReallyMutableEntry<K, V>>>()

    init {
        // TODO: Move this to schema version managment tool.
        createTablesIfNecessary()
        if (loadOnInit) {
            log.trace { "Loading all entries on init for ${table.tableName}" }
            val elapsedMillis = measureTimeMillis {
                // load from database.
                table.selectAll().map {
                    val entry = createEntry(it)
                    val bucket = getBucket(entry.key)
                    bucket.add(entry)
                }
            }
            log.trace { "Loaded ${size} entries on init for ${table.tableName} in ${elapsedMillis} millis." }
        }
    }

    private fun createTablesIfNecessary() {
        SchemaUtils.create(table)
    }

    override fun isEmpty(): Boolean {
        for (bucket in buckets.values) {
            if (!bucket.isEmpty()) {
                return false
            }
        }
        return size == 0
    }

    override fun remove(key: K): V? {
        val bucket = getBucket(key)
        var removed: V? = null
        buckets.computeIfPresent(key.hashCode()) { hashCode, value ->
            for (entry in value) {
                if (entry.key == key) {
                    removed = entry.value
                    bucket.remove(entry)
                    deleteRecord(entry)
                    break
                }
            }
            value
        }
        return removed
    }

    override fun containsKey(key: K): Boolean = (get(key) != null)

    // We haven't implemented setValue.  We could implement if necessary.
    private class NotReallyMutableEntry<K, V>(key: K, value: V, val seqNo: Int) : AbstractMap.SimpleImmutableEntry<K, V>(key, value), MutableMap.MutableEntry<K, V> {
        override fun setValue(newValue: V): V {
            throw UnsupportedOperationException("Not really mutable.  Implement if really required.")
        }
    }

    private inner class EntryIterator : MutableIterator<MutableMap.MutableEntry<K, V>> {
        private val iterator = if (loadOnInit) {
            buckets.values.flatten().sortedBy { it.seqNo }.iterator()
        } else {
            // This uses a Sequence to make the mapping lazy.
            table.selectAll().orderBy(table.seqNo).asSequence().map {
                val bucket = buckets[it[table.keyHash]]
                if (bucket != null) {
                    val seqNo = it[table.seqNo]
                    for (entry in bucket) {
                        if (entry.seqNo == seqNo) {
                            return@map entry
                        }
                    }
                }
                return@map createEntry(it)
            }.iterator()
        }
        private var current: MutableMap.MutableEntry<K, V>? = null

        override fun hasNext(): Boolean = iterator.hasNext()

        override fun next(): MutableMap.MutableEntry<K, V> {
            val extractedNext = iterator.next()
            current = extractedNext
            return extractedNext
        }

        override fun remove() {
            val savedCurrent = current
            if (savedCurrent == null) {
                throw IllegalStateException("Not called next() yet or already removed.")
            }
            current = null
            remove(savedCurrent.key)
        }
    }

    override val keys: MutableSet<K> get() {
        return object : AbstractSet<K>() {
            override val size: Int get() = this@AbstractJDBCHashMap.size
            override fun iterator(): MutableIterator<K> {
                return object : MutableIterator<K> {
                    private val entryIterator = EntryIterator()

                    override fun hasNext(): Boolean = entryIterator.hasNext()
                    override fun next(): K = entryIterator.next().key
                    override fun remove() {
                        entryIterator.remove()
                    }
                }
            }
        }
    }

    override val values: MutableCollection<V> get() {
        return object : AbstractCollection<V>() {
            override val size: Int get() = this@AbstractJDBCHashMap.size
            override fun iterator(): MutableIterator<V> {
                return object : MutableIterator<V> {
                    private val entryIterator = EntryIterator()

                    override fun hasNext(): Boolean = entryIterator.hasNext()
                    override fun next(): V = entryIterator.next().value
                    override fun remove() {
                        entryIterator.remove()
                    }
                }
            }
        }
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() {
        return object : AbstractSet<MutableMap.MutableEntry<K, V>>() {
            override val size: Int get() = this@AbstractJDBCHashMap.size
            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                    private val entryIterator = EntryIterator()

                    override fun hasNext(): Boolean = entryIterator.hasNext()
                    override fun next(): MutableMap.MutableEntry<K, V> = entryIterator.next()
                    override fun remove() {
                        entryIterator.remove()
                    }
                }
            }
        }
    }

    override fun put(key: K, value: V): V? {
        var oldValue: V? = null
        getBucket(key)
        buckets.compute(key.hashCode()) { hashCode, list ->
            val newList = list ?: newBucket()
            val iterator = newList.listIterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == key) {
                    oldValue = entry.value
                    iterator.remove()
                    deleteRecord(entry)
                    break
                }
            }
            val seqNo = addRecord(key, value)
            val newEntry = NotReallyMutableEntry<K, V>(key, value, seqNo)
            newList.add(newEntry)
            newList
        }
        return oldValue
    }

    override fun containsValue(value: V): Boolean {
        for (storedValue in values) {
            if (storedValue == value) {
                return true
            }
        }
        return false
    }

    override val size: Int get() {
        return if (loadOnInit) {
            buckets.values.map { it.size }.sum()
        } else {
            table.slice(table.seqNo).selectAll().count()
        }
    }

    override fun clear() {
        if (!loadOnInit || !isEmpty()) {
            table.deleteAll()
            buckets.clear()
        }
    }

    override fun get(key: K): V? {
        for (entry in getBucket(key)) {
            if (entry.key == key) {
                return entry.value
            }
        }
        return null
    }

    private fun getBucket(key: Any): MutableList<NotReallyMutableEntry<K, V>> {
        return buckets.computeIfAbsent(key.hashCode()) { hashCode ->
            if (!loadOnInit) {
                loadBucket(key.hashCode())
            } else {
                newBucket()
            }
        }
    }

    private fun newBucket(): MutableList<NotReallyMutableEntry<K, V>> = mutableListOf()

    private fun loadBucket(hashCode: Int): MutableList<NotReallyMutableEntry<K, V>> {
        return table.select { table.keyHash.eq(hashCode) }.map {
            createEntry(it)
        }.toMutableList<NotReallyMutableEntry<K, V>>()
    }

    /**
     * Implementation should return the key object marshalled from the database table row.
     *
     * See example implementations in [JDBCHashMap].
     */
    protected abstract fun keyFromRow(it: ResultRow): K

    /**
     * Implementation should return the value object marshalled from the database table row.
     *
     * See example implementations in [JDBCHashMap].
     */
    protected abstract fun valueFromRow(it: ResultRow): V

    /**
     * Implementation should marshall the key to the insert statement.
     *
     * If some cleanup is required after the insert statement is executed, such as closing a Blob, then add a closure
     * to the finalizables to do so.
     *
     * See example implementations in [JDBCHashMap].
     */
    protected abstract fun addKeyToInsert(it: InsertStatement, entry: Map.Entry<K, V>, finalizables: MutableList<() -> Unit>)

    /**
     * Implementation should marshall the value to the insert statement.
     *
     * If some cleanup is required after the insert statement is executed, such as closing a Blob, then add a closure
     * to the finalizables to do so.
     *
     * See example implementations in [JDBCHashMap].
     */
    protected abstract fun addValueToInsert(it: InsertStatement, entry: Map.Entry<K, V>, finalizables: MutableList<() -> Unit>)

    private fun createEntry(it: ResultRow) = NotReallyMutableEntry<K, V>(keyFromRow(it), valueFromRow(it), it[table.seqNo])

    private fun deleteRecord(entry: NotReallyMutableEntry<K, V>) {
        table.deleteWhere {
            table.seqNo eq entry.seqNo
        }
    }

    private fun addRecord(key: K, value: V): Int {
        val finalizables = mutableListOf<() -> Unit>()
        try {
            return table.insert {
                it[keyHash] = key.hashCode()
                val entry = SimpleEntry<K, V>(key, value)
                addKeyToInsert(it, entry, finalizables)
                addValueToInsert(it, entry, finalizables)
            } get table.seqNo
        } finally {
            finalizables.forEach { it() }
        }
    }
}

open class JDBCHashedTable(tableName: String) : Table(tableName) {
    val keyHash = integer("key_hash").index()
    val seqNo = integer("seq_no").autoIncrement().index().primaryKey()
}