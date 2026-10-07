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

These come from the original Java code.

1. **Splits aren't atomic.** A split writes the left node, the right node and the parent separately, with nothing like a write-ahead log to make them atomic. A crash partway through can leave the tree inconsistent.

   A related problem has been fixed. `nextPageId` is written to the header only when the root changes or on `close()`, so after a crash the header can hold an old value, and reopening used to hand out page numbers that live nodes still occupied. `readFileHeader` now takes the larger of the header value and the number of pages in the file (`pagesInFile`). The file size is never out of date, because every new page is written right after it's allocated.
2. **Duplicate keys aren't handled.** Inserting a key that already exists adds a second copy instead of updating the first. Search returns whichever copy it reaches first.
3. **There is no delete.** Deletion is the hardest B-tree operation, because underfull nodes have to borrow from or merge with their siblings.
4. **Most of each page is empty.** A full node with `T = 2` uses at most 85 bytes of 4096. In a real B-tree, `T` is chosen so a node fills its page. Here that is about `T = 85`, or 169 keys per node, which would make the tree roughly 3 levels deep for a million keys instead of about 20.
5. **No page cache.** Every visit to a node is a real `read` call, and every node on the insert path is rewritten even when it didn't change (line 159).
6. **Partial reads and writes, and threads.** `channel.read`/`write` are allowed to transfer fewer bytes than asked for, and the code doesn't loop to finish them. Using `position(...)` and then `read` is also unsafe if several threads share the channel. Using `channel.read(buf, offset)` would fix the threading problem.

The most useful next fix would probably be #4 (choose `T` to fill a page).

## 8. Q&A

### What does `(channel.size() + PAGE_SIZE - 1) / PAGE_SIZE` mean?

It calculates how many pages the file holds, counting a partly written last page as a whole page. In math terms it is **ceiling division**: `⌈size / PAGE_SIZE⌉`. It is used by `pagesInFile` (line 211).

- `channel.size()` is the file's length in bytes, as a `Long`.
- Dividing one integer by another truncates the result, so plain `size / 4096` always rounds **down**.
- Adding `PAGE_SIZE - 1` (4095) before dividing makes it round **up** instead.

| File size (bytes) | `size / 4096` (down) | `(size + 4095) / 4096` (up) |
|---|---|---|
| 0 | 0 | 0 |
| 4096 (exactly 1 page) | 1 | 1 |
| 4097 (1 page + 1 byte) | 1 | **2** |
| 36,864 (exactly 9 pages) | 9 | 9 |
| 40,000 (9 full pages + a partial one) | 9 | **10** |

When the size is an exact multiple of 4096, adding 4095 isn't enough to reach the next multiple, so the result is unchanged. If there is even one extra byte, the sum crosses into the next multiple and the count goes up by one.

**Why round up:** normally the file is always a whole number of pages, so both forms give the same answer. A crash in the middle of writing a new page could leave a partial page at the end of the file, though. Rounding down would treat that page's number as free, so it would be handed out again. Rounding up treats it as used and skips it, which wastes at most one page and never overwrites anything.

**Why the count equals the next free page:** pages are numbered from 0, with page 0 as the header, and the file holds pages `0` to `N-1`. So a file of `N` pages has `N` as its first unused page number. A 9-page file holds pages 0–8, and the next new node goes on page 9.

### What is `rootPageId`?

`rootPageId` is the page number of the tree's root node, where every search and insert starts. Because page `N` starts at byte `N * 4096`, it also tells the code where the root sits in the file.

- **Declared** at line 13 as `private var rootPageId: Long = -1L`. `-1` means the tree is empty. There's no page -1, so the value can't be confused with a real page.
- **Stored** in the header on page 0, at bytes 8–15 (line 44). That's how the root can be found again after the file is closed and reopened.
- **Read** back from the header when an existing file is opened (line 37).
- **Used** whenever an operation starts. `search` (line 72) returns `None` if it's `-1`, otherwise starts searching at that page. `insert` (line 89) creates the first node if it's `-1`, otherwise starts the recursive insert at that page.
- **Changed** only in two cases, and each time the header is rewritten immediately:
  1. **The first insert** (line 95): the new leaf becomes the root.
  2. **A root split** (line 107): a new node is created above the old root, and the new node becomes the root.

The root page doesn't change in any other case. Most inserts and splits happen lower in the tree and leave the root on its existing page.

In the demo, it changed three times:

| After inserting | `rootPageId` | Why |
|---|---|---|
| 10 | 1 | First insert creates the root leaf on page 1 |
| 40 | 3 | Root leaf split; new root on page 3 |
| 100 | 8 | Root split again; new root on page 8 |

The root's page number isn't always 1, and it isn't necessarily the last page either. It moves wherever the newest root was allocated, which is why the header has to record it rather than the code assuming a fixed location.

### What are keys and values?

Each entry in the tree is a key–value pair, and both parts are `Long`s:

- **Key:** what you look things up by. The tree keeps keys sorted, and that order decides where each entry goes.
- **Value:** the data attached to that key. The tree stores it and returns it but never looks at it.

```scala
tree.insert(key = 42, value = 99000)
tree.search(42)   // Some(99000)
```

**How they're stored:** each node has two parallel arrays, `keys` and `values` (lines 183–184). `keys(i)` and `values(i)` form one pair, so whenever a key is shifted or moved during an insert or split, its value moves with it:

```
keys:    [ 10   | 20    | 30    ]
values:  [10000 | 20000 | 30000 ]
```

**What keys do:** they are the only thing the algorithm compares. Search walks down the tree by comparing the target with keys (line 78), inserts use keys to find the sorted position (line 115), and in internal nodes the keys separate the children. Everything under `children(i)` is smaller than `keys(i)`.

**What values are for:** the demo maps each key `k` to `k * 1000`, which is a placeholder. The comment on line 225 describes the intended use: the value is a **pointer to the real data**, stored elsewhere, such as a byte offset into a separate data file or a row ID.

```
index file (B-tree)              data file
key: user_id 42  ──value──►  byte 99000: { id: 42, name: "Ana", email: ... }
```

This is how database indexes work. The B-tree stays small and quick to search because it holds only keys and pointers, and one lookup tells you where to read the full record.

Two details specific to this implementation:
- **Values live in internal nodes too**, not just in leaves. A search can stop as soon as it finds the key at any level. In a B+ tree, values are kept only in the leaves.
- **Keys aren't unique.** Inserting the same key twice stores two entries instead of replacing the first value (limitation #2).

### Walkthrough: the first insert into an empty tree (lines 89–96)

```scala
if rootPageId == -1 then
  val root = Node(allocatePageId(), isLeaf = true)
  root.keys(0) = key
  root.values(0) = value
  root.numKeys = 1
  writeNode(root)
  rootPageId = root.pageId
  flushFileHeader()
```

When the tree has no nodes yet, there's nothing to search down through, so the first key becomes a new one-key root node. In the demo this runs once, for key 10 with value 10000.

1. **`if rootPageId == -1 then`**: the tree is empty, either a brand-new file or one that was never given a key. Every later insert takes the `else` branch, which walks down from the existing root.
2. **`Node(allocatePageId(), isLeaf = true)`**: reserves a page and builds a node for it in memory. In a new file the page is 1, because page 0 is the header. `isLeaf = true` because the tree's only node has no children; a node is a leaf whenever nothing hangs below it, even if it's also the root. Nothing has been written to disk yet.
3. **`root.keys(0) = key` and `root.values(0) = value`**: put the pair into slot 0. No shifting or sorting is needed because the node is empty.
4. **`root.numKeys = 1`**: marks how many slots are in use. This is required because `writeTo` serializes only the first `numKeys` entries (lines 191–192). If `numKeys` stayed 0, the page would be written with no keys.
5. **`writeNode(root)`**: serializes the node and writes it at `pageId * 4096`, which is byte 4096 for page 1:
   ```
   byte 0     : 1        (isLeaf)
   bytes 1-4  : 1        (numKeys)
   bytes 5-12 : 10       (key)
   bytes 13-20: 10000    (value)
   rest       : zeros    (leaf, so no child pointers)
   ```
6. **`rootPageId = root.pageId`**: points the tree at its new root, in memory only.
7. **`flushFileHeader()`**: rewrites page 0 so the header on disk records `rootPageId = 1` and `nextPageId = 2`. Without this step, reopening the file would read `-1` and treat the tree as empty, even though page 1 holds data.

**Why the order matters:** the node is written before the header points to it. If the process crashes between the two writes, the header still says `-1`, so the tree reopens as empty and the orphaned page 1 is harmless. With the order reversed, a crash could leave the header pointing at a page that was never written. The root-split code (lines 99–108) follows the same rule: write the new root, then update the header.

After this branch runs, the file is two pages, 8192 bytes:

```
page 0: header [ BTRE | 4096 | root=1 | next=2 ]
page 1: leaf   [ 10 → 10000 ]
```

### What is the root's `pageId`?

`root.pageId` is the page number that the new root node was given when it was created. In a new file, that number is **1**.

`pageId` is a constructor field of `Node` (line 180): `private class Node(val pageId: Long, val isLeaf: Boolean)`. Whatever `allocatePageId()` returns is stored in the node and never changes. Every node knows its own page number, because `writeNode` needs it to work out where in the file to write (line 64): `channel.position(node.pageId * PAGE_SIZE)`.

`root.pageId` and `rootPageId` hold the same number here, but they belong to different things:

| | `root.pageId` | `rootPageId` |
|---|---|---|
| Belongs to | the node | the tree |
| Meaning | "I live on page 1" | "the tree starts at page 1" |
| Changes? | Never | Yes, on every root split |
| Saved where | Implied by the node's position in the file | Header, bytes 8–15 |

A node doesn't know whether it's the root. Being the root is a role, and the role can move. In the demo, page 1 starts as the root. After the split at key 40, page 1 is just a leaf holding `[10]` and the root is page 3. Page 1's `pageId` is still 1, while `rootPageId` has moved on.

**Is it always 1?** It is for a new file, because this branch runs only on an empty tree and page 1 is the first free page. One exception comes from the crash fix: if a crash happened after page 1 was written but before the header was updated, the header still says `-1`, but the file is already two pages long. On reopen, `pagesInFile` makes `nextPageId = 2`, so the first insert puts the root on page 2. The old page 1 becomes unused space that nothing points to, which is harmless.

### Does the header occupy 4096 bytes, stored before the root node?

Yes. The header takes up the whole of page 0, bytes 0–4095, and the root node goes right after it, starting at byte 4096. Only the first 24 bytes of the header page hold data; the other 4072 are zero padding.

```
byte 0                 byte 4096               byte 8192
| page 0: header       | page 1: root leaf      |
| 24 bytes used + pad  | 21 bytes used + pad    |
```

**Why a whole page for 24 bytes:** so every node page starts at a multiple of 4096. Then a node's location is simply `pageId * 4096`. If the header took only 24 bytes, every offset would be `24 + pageId * 4096`. Page boundaries would no longer line up with the operating system's and disk's 4 KB blocks, so writing one node could touch two blocks. Real databases do the same thing, and they often use the rest of the header page for more metadata, such as a list of freed pages.

**"Before" is also true in time, with one extra step.** For a new file, the writes happen in this order:

1. **The constructor writes the header** (`initFileHeader`, line 23) with `root = -1, next = 1`. The file is one page long.
2. **The first insert writes the root node** to page 1 (`writeNode`). The file is now two pages long.
3. **The header is rewritten** (`flushFileHeader`) with `root = 1, next = 2`.

So the header is written both before and after the root: first to create a valid empty tree, then to point at the new root. Step 3 overwrites page 0 in place, which works because the header is always at byte 0 and is always the same size.
