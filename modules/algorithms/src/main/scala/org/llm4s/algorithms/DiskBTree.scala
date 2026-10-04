package org.llm4s.algorithms

import java.io.{ Closeable, File, IOException, RandomAccessFile }
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import scala.util.Using

class DiskBTree(file: File) extends Closeable:
  import DiskBTree.*

  private val isNew                = !file.exists() || file.length() == 0
  private val channel: FileChannel = RandomAccessFile(file, "rw").getChannel
  private var rootPageId: Long     = -1L
  // Page 0 is reserved for file header metadata
  private var nextPageId: Long = 1L

  if isNew then initFileHeader() else readFileHeader()

  // ---------------------------------------------------------
  // Page 0: Metadata Header Management
  // ---------------------------------------------------------

  private def initFileHeader(): Unit =
    flushFileHeader()
    channel.force(true)

  private def readFileHeader(): Unit =
    val buf = ByteBuffer.allocate(PAGE_SIZE)
    channel.position(0)
    channel.read(buf)
    buf.rewind()

    val magic = buf.getInt()
    if magic != MAGIC_NUMBER then throw IOException("Corrupted B-Tree file: Invalid magic bytes.")
    val pageSize = buf.getInt()
    if pageSize != PAGE_SIZE then throw IOException(s"Mismatched page size: Expected $PAGE_SIZE but found $pageSize")
    rootPageId = buf.getLong()
    nextPageId = buf.getLong()

  private def flushFileHeader(): Unit =
    val buf = ByteBuffer.allocate(PAGE_SIZE)
    buf.putInt(MAGIC_NUMBER)
    buf.putInt(PAGE_SIZE)
    buf.putLong(rootPageId)
    buf.putLong(nextPageId)
    buf.rewind()
    channel.position(0)
    channel.write(buf)

  private def allocatePageId(): Long =
    val id = nextPageId
    nextPageId += 1
    id

  private def readNode(pageId: Long): Node =
    val buf = ByteBuffer.allocate(PAGE_SIZE)
    channel.position(pageId * PAGE_SIZE)
    channel.read(buf)
    Node.readFrom(pageId, buf)

  private def writeNode(node: Node): Unit =
    val buf = ByteBuffer.allocate(PAGE_SIZE)
    node.writeTo(buf)
    channel.position(node.pageId * PAGE_SIZE)
    channel.write(buf)

  // ---------------------------------------------------------
  // Search Operation
  // ---------------------------------------------------------

  def search(key: Long): Option[Long] =
    if rootPageId == -1 then None else searchFrom(rootPageId, key)

  @annotation.tailrec
  private def searchFrom(pageId: Long, key: Long): Option[Long] =
    val node = readNode(pageId)
    var i    = 0
    while i < node.numKeys && key > node.keys(i) do i += 1

    if i < node.numKeys && key == node.keys(i) then Some(node.values(i))
    else if node.isLeaf then None
    else searchFrom(node.children(i), key)

  // ---------------------------------------------------------
  // Insert Operation (Bottom-Up Overflow & Split)
  // ---------------------------------------------------------

  def insert(key: Long, value: Long): Unit =
    if rootPageId == -1 then
      val root = Node(allocatePageId(), isLeaf = true)
      root.keys(0) = key
      root.values(0) = value
      root.numKeys = 1
      writeNode(root)
      rootPageId = root.pageId
      flushFileHeader()
    else
      // If the root split, construct a new root node above it
      insertRecursive(rootPageId, key, value).foreach: split =>
        val newRoot = Node(allocatePageId(), isLeaf = false)
        newRoot.numKeys = 1
        newRoot.keys(0) = split.promotedKey
        newRoot.values(0) = split.promotedValue
        newRoot.children(0) = rootPageId
        newRoot.children(1) = split.rightChildPageId
        writeNode(newRoot)
        rootPageId = newRoot.pageId
        flushFileHeader()

  private def insertRecursive(pageId: Long, key: Long, value: Long): Option[SplitResult] =
    val node = readNode(pageId)

    if node.isLeaf then
      var i = node.numKeys - 1
      while i >= 0 && node.keys(i) > key do
        node.keys(i + 1) = node.keys(i)
        node.values(i + 1) = node.values(i)
        i -= 1
      node.keys(i + 1) = key
      node.values(i + 1) = value
      node.numKeys += 1
    else
      var i = 0
      while i < node.numKeys && key > node.keys(i) do i += 1
      insertRecursive(node.children(i), key, value).foreach: childSplit =>
        // Shift keys and children right to make room for promoted entry
        for j <- node.numKeys until i by -1 do
          node.keys(j) = node.keys(j - 1)
          node.values(j) = node.values(j - 1)
          node.children(j + 1) = node.children(j)
        node.keys(i) = childSplit.promotedKey
        node.values(i) = childSplit.promotedValue
        node.children(i + 1) = childSplit.rightChildPageId
        node.numKeys += 1

    // Split node if it exceeds max capacity (2*T - 1 keys)
    if node.numKeys > 2 * T - 1 then
      val medianIdx   = T - 1
      val promotedKey = node.keys(medianIdx)
      val promotedVal = node.values(medianIdx)

      val rightNode      = Node(allocatePageId(), node.isLeaf)
      val rightKeysCount = node.numKeys - (medianIdx + 1)
      rightNode.numKeys = rightKeysCount

      for j <- 0 until rightKeysCount do
        rightNode.keys(j) = node.keys(medianIdx + 1 + j)
        rightNode.values(j) = node.values(medianIdx + 1 + j)

      if !node.isLeaf then for j <- 0 to rightKeysCount do rightNode.children(j) = node.children(medianIdx + 1 + j)

      // Truncate original node to left partition
      node.numKeys = medianIdx

      writeNode(node)
      writeNode(rightNode)
      Some(SplitResult(promotedKey, promotedVal, rightNode.pageId))
    else
      writeNode(node)
      None

  override def close(): Unit =
    flushFileHeader()
    channel.force(true)
    channel.close()

object DiskBTree:

  val PAGE_SIZE: Int            = 4096
  private val MAGIC_NUMBER: Int = 0x42545245 // "BTRE"
  // Minimum degree (Max keys = 2*T - 1 = 3)
  val T: Int = 2

  private case class SplitResult(promotedKey: Long, promotedValue: Long, rightChildPageId: Long)

  // ---------------------------------------------------------
  // Node Serialization & Deserialization
  // ---------------------------------------------------------

  private class Node(val pageId: Long, val isLeaf: Boolean):
    var numKeys: Int = 0
    // Capacities sized to 2*T to accommodate temporary overflow before split
    val keys: Array[Long]     = new Array[Long](2 * T)
    val values: Array[Long]   = new Array[Long](2 * T)
    val children: Array[Long] = Array.fill(2 * T + 1)(-1L)

    def writeTo(buf: ByteBuffer): Unit =
      buf.clear()
      buf.put((if isLeaf then 1 else 0).toByte)
      buf.putInt(numKeys)
      for i <- 0 until numKeys do buf.putLong(keys(i))
      for i <- 0 until numKeys do buf.putLong(values(i))
      if !isLeaf then for i <- 0 to numKeys do buf.putLong(children(i))
      // Pad remainder of disk block with zeros
      while buf.hasRemaining do buf.put(0.toByte)
      buf.rewind()

  private object Node:
    def readFrom(pageId: Long, buf: ByteBuffer): Node =
      buf.rewind()
      val isLeaf = buf.get() == 1
      val node   = Node(pageId, isLeaf)
      node.numKeys = buf.getInt()
      for i <- 0 until node.numKeys do node.keys(i) = buf.getLong()
      for i <- 0 until node.numKeys do node.values(i) = buf.getLong()
      if !isLeaf then for i <- 0 to node.numKeys do node.children(i) = buf.getLong()
      node

  // ---------------------------------------------------------
  // Demonstration & Verification
  // ---------------------------------------------------------

  @main def diskBTreeExample(): Unit =
    val dbFile = File("btree_demo.db")
    if dbFile.exists() then dbFile.delete()

    Using(DiskBTree(dbFile)): tree =>
      val keys = Seq(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)
      println(s"Inserting keys: ${keys.mkString(", ")}...")
      // Key maps to an arbitrary row offset / record identifier
      keys.foreach(k => tree.insert(k, k * 1000L))

      println("\nQuerying stored keys:")
      keys.foreach(k => println(s"Key $k -> Value: ${tree.search(k)}"))

      println("\nQuerying non-existent key 25:")
      println(s"Key 25 -> Value: ${tree.search(25)}")

      println(s"\nRoot Page ID: ${tree.rootPageId}")
      println(s"Allocated Pages: ${tree.nextPageId - 1}")
      println(s"File Size: ${dbFile.length()} bytes (${dbFile.length() / PAGE_SIZE} pages)")
    .failed.foreach(_.printStackTrace())
