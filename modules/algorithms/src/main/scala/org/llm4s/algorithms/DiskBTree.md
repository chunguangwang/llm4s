# How `DiskBTree.scala` works

This is a classic B-tree stored in a file of fixed 4 KB pages. Every node is one page, and nodes point to each other by page number instead of by memory reference. Keys and values are both `Long`, and the value is meant to be something like a row offset. Line numbers below refer to `DiskBTree.scala` in this folder.

## 1. File layout

The file is a sequence of 4096-byte pages, and page `N` starts at byte `N * 4096` (lines 57 and 64):

```
page 0      page 1      page 2      ...      page N
[ header ][  node  ][  node  ]  ...  [  node  ]
```

**Page 0 is the header** (`flushFileHeader`, lines 40–48). It uses only the first 24 bytes:

| Offset | Size | Field | Purpose |
|---|---|---|---|
| 0 | 4 | `MAGIC_NUMBER` `0x42545245` ("BTRE") | Identifies the file as a B-tree file |
| 4 | 4 | `PAGE_SIZE` | Catches files written with a different page size |
| 8 | 8 | `rootPageId` | Where the tree starts (`-1` means empty) |
| 16 | 8 | `nextPageId` | The next unused page number |

Every other page holds one node (`writeTo`, lines 187–196):

```
byte 0        : isLeaf (1 or 0)
bytes 1..4    : numKeys (Int)
next          : numKeys × 8 bytes   keys
next          : numKeys × 8 bytes   values
next          : (numKeys+1) × 8     child page IDs (internal nodes only)
rest          : zero padding up to 4096
```

Leaves have no children, so their child section is skipped on both write and read (lines 193 and 206).

## 2. The node in memory (lines 180–185)

```scala
keys     = new Array[Long](2 * T)      // 4 slots
values   = new Array[Long](2 * T)      // 4 slots
children = Array.fill(2 * T + 1)(-1L)  // 5 slots
```

`T = 2` is the **minimum degree**, so a node can hold at most `2T - 1 = 3` keys. The arrays have one extra slot so a node can briefly hold 4 keys before it splits. An overfull node is never written to disk, because the split check on line 137 runs before `writeNode`.

The invariants are:
- Keys inside a node are sorted.
- An internal node with `n` keys has `n + 1` children.
- Everything under `children(i)` is smaller than `keys(i)`, and everything under `children(i+1)` is larger.
- Values are stored next to their keys in every node, internal nodes included. That is what makes this a B-tree rather than a B+ tree, where values live only in the leaves.

## 3. Search (lines 71–82)

```scala
while i < node.numKeys && key > node.keys(i) do i += 1
```

At each node, it scans forward to the first key that is greater than or equal to the target. Then one of three things happens:
1. **Exact match:** return `Some(values(i))`. Because values live in internal nodes too, a search can stop before reaching a leaf.
2. **Leaf with no match:** return `None`.
3. **Otherwise:** go down into `children(i)`.

`@tailrec` turns the recursion into a loop. Each level costs one page read, so a lookup costs about `height` disk reads.

## 4. Insert: bottom-up split (lines 88–160)

The idea is to go down to a leaf, insert the key there, and fix any overflow on the way back up.

**`insertRecursive`** returns `Option[SplitResult]`, which means "did I split, and if so, what do you need to absorb?"

- **At a leaf (lines 113–121):** shift larger keys right and drop the new key into its sorted position. This is one step of insertion sort.
- **At an internal node (lines 122–134):** find the right child and recurse into it. If the child reports a split, make room at position `i`, insert the promoted key, and put the new right sibling at `children(i + 1)`.
- **Split check (lines 137–157):** if the node now has 4 keys:
  ```
  before:  [ k0 | k1 | k2 | k3 ]     medianIdx = T-1 = 1
  after:   left  = [ k0 ]            (same page, numKeys truncated to 1)
           up    =   k1              (promoted to the parent)
           right = [ k2 | k3 ]       (new page)
  ```
  The left half stays on the original page; setting `numKeys = 1` simply hides the rest. The right half gets a newly allocated page. For internal nodes, the child pointers that sit to the right of the median move with the right half (line 150).

**Root split (lines 99–108):** if the split travels all the way up and out of the root, `insert` creates a new root with one key and two children, then updates the header. This is the only way the tree gets taller, which keeps all leaves at the same depth.

The textbook alternative (CLRS) is **top-down**: it splits any full node on the way down, so a single pass is enough. The bottom-up approach used here is simpler to follow, but it relies on recursion to remember the path back up.

## 5. Tracing the demo

Each step below shows the page each node lives on (`pN`):

```
insert 10,20,30   p1[10,20,30]
insert 40         p1 overflows [10,20,30,40] → p1[10]  ↑20  p2[30,40]
                  new root p3[20]
insert 50,60      p2 overflows → p2[30] ↑40 p4[50,60]       root p3[20,40]
insert 70,80      p4 overflows → p4[50] ↑60 p5[70,80]       root p3[20,40,60]
insert 90,100     p5 overflows → p5[70] ↑80 p6[90,100]
                  root p3 overflows [20,40,60,80] → p3[20] ↑40 p7[60,80]
                  new root p8[40]
```

The final tree:

```
                    p8 [40]
                /            \
         p3 [20]              p7 [60, 80]
         /     \             /     |      \
    p1[10]   p2[30]     p4[50]  p5[70]  p6[90,100]
```

This matches the demo output: root page 8, 8 node pages allocated, and a file of 9 pages (36,864 bytes, counting the header).

## 6. File I/O details

- **Opening (lines 11–17):** `RandomAccessFile(file, "rw").getChannel` gives a `FileChannel` that can read and write at any offset. A new or empty file gets a fresh header; an existing file has its header read and checked.
- **Reads and writes (lines 55–65):** each one allocates a fresh `ByteBuffer`, moves the channel to `pageId * PAGE_SIZE`, and transfers the whole page. Calling `rewind()` after filling the buffer sets the position back to 0 while the limit stays at 4096, so the full page is written, padding included.
- **Durability:** `force(true)` makes the OS flush data to physical disk. It is only called after the header is first created and in `close()`. Every other write sits in the OS page cache until the OS flushes it.
- **Closing (lines 162–165):** writes the header, forces everything to disk, and closes the channel. `Using` in the demo makes sure this happens even if an exception is thrown.

## 7. Limitations to know about

These come from the original Java code; the Scala version keeps them as they were.

1. **A crash can corrupt the file.** `nextPageId` is written to the header only when the root changes or on `close()`. If the process dies after some splits, the header on disk has an old `nextPageId`. After reopening, new pages would overwrite live nodes. A split also writes the left node, the right node and the parent separately, with nothing like a write-ahead log to make them atomic.
2. **Duplicate keys aren't handled.** Inserting a key that already exists adds a second copy instead of updating the first. Search returns whichever copy it reaches first.
3. **There is no delete.** Deletion is the hardest B-tree operation, because underfull nodes have to borrow from or merge with their siblings.
4. **Most of each page is empty.** A full node with `T = 2` uses at most 85 bytes of 4096. In a real B-tree, `T` is chosen so a node fills its page. Here that is about `T = 85`, or 169 keys per node, which would make the tree roughly 3 levels deep for a million keys instead of about 20.
5. **No page cache.** Every visit to a node is a real `read` call, and every node on the insert path is rewritten even when it didn't change (line 159).
6. **Partial reads and writes, and threads.** `channel.read`/`write` are allowed to transfer fewer bytes than asked for, and the code doesn't loop to finish them. Using `position(...)` and then `read` is also unsafe if several threads share the channel. Using `channel.read(buf, offset)` would fix the threading problem.

The most useful fixes would probably be #1 (write the header after each allocation) and #4 (choose `T` to fill a page).
