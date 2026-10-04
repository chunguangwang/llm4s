package org.llm4s.algorithms

import java.io.File
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DiskBTreeSpec extends AnyFlatSpec with Matchers:

  private def tempFile(): File =
    val f = File.createTempFile("btree", ".db")
    f.deleteOnExit()
    f

  "DiskBTree" should "find inserted keys after close and reopen" in {
    val file = tempFile()
    val tree = DiskBTree(file)
    (1L to 50L).foreach(k => tree.insert(k, k * 10))
    tree.close()

    val reopened = DiskBTree(file)
    (1L to 50L).foreach(k => reopened.search(k) shouldBe Some(k * 10))
    reopened.search(51L) shouldBe None
    reopened.close()
  }

  it should "not overwrite live pages when reopened after a crash" in {
    val file    = tempFile()
    val crashed = DiskBTree(file)
    // Inserting 120 splits a leaf without changing the root, so the header's page counter falls behind
    val firstKeys = 10L to 120L by 10L
    firstKeys.foreach(k => crashed.insert(k, k * 1000))

    // Simulate a crash by reopening without calling close() on the first instance
    val reopened  = DiskBTree(file)
    val laterKeys = Seq(5L, 6L, 7L)
    laterKeys.foreach(k => reopened.insert(k, k * 1000))

    (firstKeys ++ laterKeys).foreach(k => reopened.search(k) shouldBe Some(k * 1000))
    reopened.close()
  }
