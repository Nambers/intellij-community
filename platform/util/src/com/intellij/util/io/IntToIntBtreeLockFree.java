// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.io;

import com.intellij.util.BitUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.SystemProperties;
import com.intellij.util.io.pagecache.Page;
import com.intellij.util.io.pagecache.PageUnsafe;
import com.intellij.util.io.pagecache.impl.PageContentLockingStrategy.SharedLockLockingStrategy;
import com.intellij.util.io.stats.BTreeStatistics;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Implementation is not lock-free, it just uses {@link FilePageCacheLockFree} for page management.
 * Actually, the implementation expects to be guarded by a single lock
 */
public final class IntToIntBtreeLockFree extends AbstractIntToIntBtree {
  private static final boolean CACHE_ROOT_NODE_BUFFER = SystemProperties.getBooleanProperty("idea.btree.cache.root.node.buffer", true);

  private static final int HAS_ZERO_KEY_MASK = 0xFF000000;

  private static final int UNDEFINED_ADDRESS = -1;
  private static final int DEFAULT_PAGE_SIZE = 1024 * 1024;

  private static final boolean DO_SANITY_CHECK = false;
  private static final boolean DO_DUMP = false;

  private final int pageSize;
  private final short maxInteriorNodes;
  private final short maxLeafNodes;
  private final short maxLeafNodesInHash;

  private final BtreeRootNode root;
  private int height;
  private int maxStepsSearchedInHash;
  private int totalHashStepsSearched;
  private int hashSearchRequests;
  private int pagesCount;
  private int hashedPagesCount;
  private int count;
  private int movedMembersCount;
  private boolean hasZeroKey;
  private int zeroKeyValue;

  private final PagedFileStorageWithRWLockedPageContent storage;
  private final int metaDataLeafPageLength;
  private final int hashPageCapacity;


  public IntToIntBtreeLockFree(int pageSize,
                               @NotNull Path file,
                               @NotNull StorageLockContext storageLockContext,
                               boolean initial, SharedLockLockingStrategy lockingStrategy)
    throws IOException {
    this.pageSize = pageSize;

    if (initial) {
      Files.deleteIfExists(file);
    }

    storage = PagedFileStorageWithRWLockedPageContent.createWithDefaults(
      file,
      storageLockContext,
      SystemProperties.getIntProperty("idea.IntToIntBtree.page.size", DEFAULT_PAGE_SIZE),
      IOUtil.useNativeByteOrderForByteBuffers(),
      lockingStrategy
    );
    //storage.setRoundFactor(pageSize);
    root = new BtreeRootNode();

    if (initial) {
      root.setAddress(UNDEFINED_ADDRESS);
    }

    int i = (this.pageSize - BtreeIndexNodeView.RESERVED_META_PAGE_LEN) / BtreeIndexNodeView.INTERIOR_SIZE - 1;
    assert i < Short.MAX_VALUE && i % 2 == 0;
    maxInteriorNodes = (short)i;
    maxLeafNodes = (short)i;

    ++i;
    while (!isPrime(i)) {
      i -= 2;
    }

    hashPageCapacity = i;
    int metaPageLen = BtreeIndexNodeView.RESERVED_META_PAGE_LEN;
    i = (int)(hashPageCapacity * 0.9);
    if ((i & 1) == 1) {
      ++i;
    }

    metaDataLeafPageLength = metaPageLen;

    assert i > 0 && i % 2 == 0;
    maxLeafNodesInHash = (short)i;
  }

  private void doAllocateRoot() throws IOException {
    nextPage(); // allocate root
    root.setAddress(0);
    root.getNodeView().setIndexLeaf(true);
  }

  @Override
  public void persistVars(@NotNull BtreeDataStorage storage,
                          boolean toDisk) throws IOException {
    int i = storage.persistInt(0, height | (hasZeroKey ? HAS_ZERO_KEY_MASK : 0), toDisk);
    hasZeroKey = (i & HAS_ZERO_KEY_MASK) != 0;
    height = i & ~HAS_ZERO_KEY_MASK;

    pagesCount = storage.persistInt(4, pagesCount, toDisk);
    movedMembersCount = storage.persistInt(8, movedMembersCount, toDisk);
    maxStepsSearchedInHash = storage.persistInt(12, maxStepsSearchedInHash, toDisk);
    count = storage.persistInt(16, count, toDisk);
    hashSearchRequests = storage.persistInt(20, hashSearchRequests, toDisk);
    totalHashStepsSearched = storage.persistInt(24, totalHashStepsSearched, toDisk);
    hashedPagesCount = storage.persistInt(28, hashedPagesCount, toDisk);
    root.setAddress(storage.persistInt(32, root.address, toDisk));
    zeroKeyValue = storage.persistInt(36, zeroKeyValue, toDisk);
  }

  private final class BtreeRootNode {
    int address;
    final BtreeIndexNodeView nodeView = new BtreeIndexNodeView(false);
    boolean initialized;

    void setAddress(int _address) {
      address = _address;
      initialized = false;
    }

    void syncWithStore() throws IOException {
      nodeView.setAddress(address);
      initialized = true;
    }

    BtreeIndexNodeView getNodeView() throws IOException {
      if (!initialized) syncWithStore();
      return nodeView;
    }
  }

  private static boolean isPrime(int val) {
    if (val % 2 == 0) return false;
    int maxDivisor = (int)Math.sqrt(val);
    for (int i = 3; i <= maxDivisor; i += 2) {
      if (val % i == 0) return false;
    }
    return true;
  }

  private int nextPage() throws IOException {
    int pageStart = (int)storage.length();
    storage.putInt(pageStart + pageSize - 4, 0);
    ++pagesCount;
    return pageStart;
  }

  private BtreeIndexNodeView myAccessNodeView;
  private int myLastGetKey;
  private int myOptimizedInserts;
  private boolean myCanUseLastKey;

  /**
   * Lookup given key in BTree, and return true if key is exist in the Tree (associated value is returned
   * in result[0]), false if there is no such key in the Tree (result is untouched then)
   *
   * @return true if key was found, false otherwise
   */
  @Override
  public boolean get(int key, int @NotNull [] result) throws IOException {
    assert result.length > 0 : "result.length must be >0";
    if (key == 0) {
      if (hasZeroKey) {
        result[0] = zeroKeyValue;
        return true;
      }
      return false;
    }

    if (root.address == UNDEFINED_ADDRESS) return false;

    Page root = initAccessNodeView();
    try {
      int index = myAccessNodeView.locate(key, /*split: */ false);

      if (index < 0) {
        myCanUseLastKey = true;
        myLastGetKey = key;
        return false;
      }
      else {
        myCanUseLastKey = false;
      }
      result[0] = myAccessNodeView.addressAt(index);
    }
    finally {
      myAccessNodeView.disposePage();
      if (root != null) {
        root.release();
      }
    }

    return true;
  }

  private Page initAccessNodeView() throws IOException {
    int rootAddress = root.address;

    PageUnsafe page;
    if (CACHE_ROOT_NODE_BUFFER) {
      BtreeIndexNodeView node = root.getNodeView();
      node.acquirePage();
      page = node.page;
    }
    else {
      page = null;
    }


    //sharedBuffer only here:
    if (myAccessNodeView == null) {
      myAccessNodeView = new BtreeIndexNodeView(rootAddress, /*cache: */true, page);
    }
    else {
      myAccessNodeView.initTraversal(rootAddress, page);
    }

    return page;
  }

  @Override
  public void put(int key, int value) throws IOException {
    if (key == 0) {
      hasZeroKey = true;
      zeroKeyValue = value;
      return;
    }

    boolean canUseLastKey = myCanUseLastKey;
    if (canUseLastKey) {
      myCanUseLastKey = false;
      if (key == myLastGetKey && !myAccessNodeView.myHasFullPagesAlongPath) {
        ++myOptimizedInserts;
        ++count;
        try {
          myAccessNodeView.insert(key, value);
        }
        finally {
          myAccessNodeView.disposePage();
        }
        return;
      }
    }
    doPut(key, value);
  }

  private void doPut(int key, int value) throws IOException {
    if (root.address == UNDEFINED_ADDRESS) doAllocateRoot();
    Page root = initAccessNodeView();
    try {
      int index = myAccessNodeView.locate(key, true);

      if (index < 0) {
        ++count;
        myAccessNodeView.insert(key, value);
      }
      else {
        myAccessNodeView.setAddressAt(index, value);
      }
    }
    finally {
      myAccessNodeView.disposePage();
      if (root != null) {
        root.release();
      }
    }
  }

  @Override
  public void doClose() throws IOException {
    final BtreeIndexNodeView rootNode = root.nodeView;
    rootNode.disposePage();
    storage.close();
  }

  @Override
  public void doFlush() throws IOException {
    storage.force();
  }

  // Leaf index node
  // (value_address {<0 if address in duplicates segment}, hash key) {getChildrenCount()}
  // (|next_node {<0} , hash key|) {getChildrenCount()} , next_node {<0}
  // next_node[i] is pointer to all less than hash_key[i] except for the last
  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  private class BtreeIndexNodeView implements Closeable {
    static final int INTERIOR_SIZE = 8;
    static final int KEY_OFFSET = 4;

    private boolean isIndexLeaf;
    private boolean isHashedLeaf;
    private static final int LARGE_MOVE_THRESHOLD = 5;

    static final int RESERVED_META_PAGE_LEN = 8;
    static final int FLAGS_SHIFT = 24;
    static final int LENGTH_SHIFT = 8;
    static final int LENGTH_MASK = 0xFFFF;

    /** address of a node in a file (i.e. absolute) */
    private int address = -1;
    /** address of a node in a page (i.e. relative to file page) */
    private int addressInBuffer;

    private short myChildrenCount = -1;

    /**
     * page lifecycle:
     * (regular): page acquired in .acquirePage(), released in .releasePage(), re-acquired in .acquirePage(), null-ed in .disposePage()
     * (cached):  page acquired once in .acquirePage(), released (and null-ed) only in .disposePage() -- .releasePage() does nothing,
     * (shared):  somebody else controls page lifecycle, so .(acquire|release|dispose)Page() all do nothing
     * <p>
     * Shared pages used by root (only?).
     * !cached (i.e. regular) pages are used by rootNode only.
     * All other pages are 'cached'.
     */
    private PageUnsafe page;
    /** Somebody else is responsible for page lifecycle (acquire/release), this node shouldn't do anything about it */
    private boolean isSharedPage;

    /** Do not call page.release() on unlockPage(), but keep page in-use until .disposePage() */
    private final boolean keepPageAcquiredUntilDispose;


    private boolean myHasFullPagesAlongPath;


    private void setFlag(int mask, boolean flag) throws IOException {
      mask <<= FLAGS_SHIFT;
      acquirePage();
      try {
        int anInt = page.getInt(addressInBuffer);

        if (flag) {
          anInt |= mask;
        }
        else {
          anInt &= ~mask;
        }
        page.putInt(addressInBuffer, anInt);
      }
      finally {
        releasePage();
      }
    }

    BtreeIndexNodeView(boolean keepPageAcquiredUntilDispose) {
      this.keepPageAcquiredUntilDispose = keepPageAcquiredUntilDispose;
    }

    BtreeIndexNodeView(int address, boolean keepPageAcquiredUntilDispose, PageUnsafe sharedBuffer) throws IOException {
      this.keepPageAcquiredUntilDispose = keepPageAcquiredUntilDispose;
      initTraversal(address, sharedBuffer);
    }

    private short getChildrenCount() {
      return myChildrenCount;
    }

    private void setChildrenCount(short value) throws IOException {
      myChildrenCount = value;
      acquirePage();
      try {
        final ByteBuffer pageBuffer = page.rawPageBuffer();
        int myValue = pageBuffer.getInt(addressInBuffer);
        myValue &= ~LENGTH_MASK << LENGTH_SHIFT;
        myValue |= value << LENGTH_SHIFT;
        page.putInt(addressInBuffer, myValue);
      }
      finally {
        releasePage();
      }
    }

    private void setNextPage(int nextPage) throws IOException {
      putInt(4, nextPage);
    }

    private int getNextPage() throws IOException {
      acquirePage();
      try {
        return getInt(4);
      }
      finally {
        releasePage();
      }
    }

    private int getInt(int address) throws IOException {
      acquirePage();
      try {
        return page.getInt(addressInBuffer + address);
      }
      finally {
        releasePage();
      }
    }

    private void putInt(int offset, int value) throws IOException {
      acquirePage();
      try {
        page.putInt(addressInBuffer + offset, value);
      }
      finally {
        releasePage();
      }
    }

    private ByteBuffer getBytes(int address, int length) throws IOException {
      acquirePage();
      try {
        final ByteBuffer duplicate = page.duplicate();

        final int newPosition = address + addressInBuffer;
        duplicate.position(newPosition);
        duplicate.limit(newPosition + length);
        return duplicate;
      }
      finally {
        releasePage();
      }
    }

    private void putBytes(int address, ByteBuffer buffer) throws IOException {
      acquirePage();
      try {
        page.putFromBuffer(buffer, address + addressInBuffer);
      }
      finally {
        releasePage();
      }
    }

    private static final int HASH_FREE = 0;

    void setAddress(int _address) throws IOException {
      setAddress(_address, null);
    }

    void setAddress(int _address, PageUnsafe sharedBuffer) throws IOException {
      if (DO_SANITY_CHECK) assert _address % pageSize == 0;

      address = _address;
      addressInBuffer = storage.toOffsetInPage(address);

      disposePage();

      if (sharedBuffer != null) {
        page = sharedBuffer;
        isSharedPage = true;
      }
      syncWithStore();
    }

    private void syncWithStore() throws IOException {
      acquirePage();
      try {
        doInitFlags(page.getInt(addressInBuffer));
      }
      finally {
        releasePage();
      }
    }

    private void acquirePage() throws IOException {
      if (isSharedPage) {
        return;
      }
      if (page == null) {
        page = (PageUnsafe)storage.pageByOffset(address, /*forWrite: */ false);
      }
      else if (!keepPageAcquiredUntilDispose) {
        boolean acquiredSuccessfully = page.tryAcquire(this);
        if (!acquiredSuccessfully) {
          page = (PageUnsafe)storage.pageByOffset(address, /*forWrite: */ false);
        }
      }//else: if(keepPageAcquiredUntilDispose) -> page must be already acquired, if !null
    }

    private void releasePage() {
      if (isSharedPage) {
        return;
      }
      if (!keepPageAcquiredUntilDispose) {
        page.release();
      }
    }

    private void disposePage() {
      try {
        if (isSharedPage) {
          isSharedPage = false;
          return;
        }

        //RC: in theory, we should dispose only cached pages, because !cached are already release()-ed
        // in .releasePage(). But there are .disposePage() calls without previous releasePage(): i.e.
        // in .setAddress() -- hence we always release page if it is !null -- i.e. if there is page to
        // release:
        if (page != null && keepPageAcquiredUntilDispose) {
          page.release();
        }
      }
      finally {
        page = null;
      }
    }


    private int search(int value) throws IOException {
      if (isIndexLeaf() && isHashedLeaf()) {
        return hashIndex(value);
      }
      acquirePage();
      try {
        final ByteBuffer pageBuffer = page.rawPageBuffer();
        return ObjectUtils.binarySearch(0, getChildrenCount(), mid -> {
          int key = keyAtGivenLocksAndBufferAcquired(pageBuffer, mid);
          return Integer.compare(key, value);
        });
      }
      finally {
        releasePage();
      }
    }

    int addressAt(int i) throws IOException {
      if (DO_SANITY_CHECK) {
        short childrenCount = getChildrenCount();
        if (isHashedLeaf()) {
          assert i < hashPageCapacity;
        }
        else {
          boolean b = i < childrenCount || (!isIndexLeaf() && i == childrenCount);
          assert b;
        }
      }
      return getInt(indexToOffset(i));
    }

    private void setAddressAt(int i, int value) throws IOException {
      int offset = indexToOffset(i);
      if (DO_SANITY_CHECK) {
        short childrenCount = getChildrenCount();
        final int metaPageLen;

        if (isHashedLeaf()) {
          assert i < hashPageCapacity;
          metaPageLen = metaDataLeafPageLength;
        }
        else {
          boolean b = i < childrenCount || (!isIndexLeaf() && i == childrenCount);
          assert b;
          metaPageLen = RESERVED_META_PAGE_LEN;
        }
        assert offset + 4 <= pageSize;
        assert offset >= metaPageLen;
      }
      putInt(offset, value);
    }

    private int indexToOffset(int i) {
      return i * INTERIOR_SIZE + (isHashedLeaf() ? metaDataLeafPageLength : RESERVED_META_PAGE_LEN);
    }


    private int keyAt(int i) {
      try {
        if (DO_SANITY_CHECK) {
          if (isHashedLeaf()) {
            assert i < hashPageCapacity;
          }
          else {
            assert i < getChildrenCount();
          }
        }
        return getInt(indexToOffset(i) + KEY_OFFSET);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    private int keyAtGivenLocksAndBufferAcquired(ByteBuffer pageBuffer,
                                                 int slotIndex) {
      final int keyOffsetInNode = indexToOffset(slotIndex) + KEY_OFFSET;
      return getIntGivenLocksAndBufferAcquired(pageBuffer, keyOffsetInNode);
    }

    private int addressAtGivenLocksAndBufferAcquired(ByteBuffer pageBuffer,
                                                     int slotIndex) {
      final int offsetInNode = indexToOffset(slotIndex);
      return getIntGivenLocksAndBufferAcquired(pageBuffer, offsetInNode);
    }

    private int getIntGivenLocksAndBufferAcquired(ByteBuffer pageBuffer,
                                                  int offsetInNode) {
      return pageBuffer.getInt(addressInBuffer + offsetInNode);
    }


    private void setKeyAt(int i, int value) throws IOException {
      final int offset = indexToOffset(i) + KEY_OFFSET;
      if (DO_SANITY_CHECK) {
        final int metaPageLen;
        if (isHashedLeaf()) {
          assert i < hashPageCapacity;
          metaPageLen = metaDataLeafPageLength;
        }
        else {
          assert i < getChildrenCount();
          metaPageLen = RESERVED_META_PAGE_LEN;
        }
        assert offset + 4 <= pageSize;
        assert offset >= metaPageLen;
      }
      putInt(offset, value);
    }

    static final int INDEX_LEAF_MASK = 0x1;
    static final int HASHED_LEAF_MASK = 0x2;

    boolean isIndexLeaf() {
      return isIndexLeaf;
    }

    protected void doInitFlags(int flags) {
      myChildrenCount = (short)((flags >>> LENGTH_SHIFT) & LENGTH_MASK);
      flags = (flags >> FLAGS_SHIFT) & 0xFF;
      isHashedLeaf = BitUtil.isSet(flags, HASHED_LEAF_MASK);
      isIndexLeaf = BitUtil.isSet(flags, INDEX_LEAF_MASK);
    }

    void setIndexLeaf(boolean value) throws IOException {
      isIndexLeaf = value;
      setFlag(INDEX_LEAF_MASK, value);
    }

    private boolean isHashedLeaf() {
      return isHashedLeaf;
    }

    void setHashedLeaf() throws IOException {
      isHashedLeaf = true;
      setFlag(HASHED_LEAF_MASK, true);
    }

    short getMaxChildrenCount() {
      return isIndexLeaf() ? isHashedLeaf() ? maxLeafNodesInHash : maxLeafNodes : maxInteriorNodes;
    }

    boolean isFull() {
      short childrenCount = getChildrenCount();
      if (!isIndexLeaf()) {
        ++childrenCount;
      }
      return childrenCount == getMaxChildrenCount();
    }

    boolean processMappings(KeyValueProcessor processor) throws IOException {
      assert isIndexLeaf();
      acquirePage();
      try {
        final ByteBuffer pageBuffer = page.rawPageBuffer();

        if (isHashedLeaf()) {
          int offset = addressInBuffer + indexToOffset(0);
          for (int slotNo = 0; slotNo < hashPageCapacity; slotNo++) {
            int key = pageBuffer.getInt(offset + KEY_OFFSET);
            if (key != HASH_FREE) {
              int value = pageBuffer.getInt(offset);
              if (!processor.process(key, value)) {
                return false;
              }
            }
            offset += INTERIOR_SIZE;
          }
        }
        else {
          final int childrenCount = getChildrenCount();
          for (int childNo = 0; childNo < childrenCount; ++childNo) {
            final int key = keyAtGivenLocksAndBufferAcquired(pageBuffer, childNo);
            final int value = addressAtGivenLocksAndBufferAcquired(pageBuffer, childNo);
            if (!processor.process(key, value)) {
              return false;
            }
          }
        }
        return true;
      }
      finally {
        releasePage();
      }
    }

    public void initTraversal(int address, PageUnsafe sharedBuffer) throws IOException {
      myHasFullPagesAlongPath = false;
      setAddress(address, sharedBuffer);
    }

    @Override
    public void close() {
      disposePage();
    }

    private final class HashLeafData {
      final BtreeIndexNodeView nodeView;
      final int[] keys;
      final Int2IntMap values;

      HashLeafData(BtreeIndexNodeView _nodeView, int recordCount) throws IOException {
        nodeView = _nodeView;

        int offset = nodeView.addressInBuffer + nodeView.indexToOffset(0);

        keys = new int[recordCount];
        values = new Int2IntOpenHashMap(recordCount);
        final int[] keyNumber = {0};

        for (int i = 0; i < hashPageCapacity; ++i) {

          nodeView.acquirePage();
          try {
            int key = nodeView.page.getInt(offset + KEY_OFFSET);
            if (key != HASH_FREE) {
              int value = nodeView.page.getInt(offset);

              if (keyNumber[0] == keys.length) throw new CorruptedException(storage.getFile());
              keys[keyNumber[0]++] = key;
              values.put(key, value);
            }
          }
          finally {
            nodeView.releasePage();
          }

          offset += INTERIOR_SIZE;
        }

        Arrays.sort(keys);
      }

      private void clean() throws IOException {
        for (int i = 0; i < hashPageCapacity; ++i) {
          nodeView.setKeyAt(i, HASH_FREE);
        }
      }
    }

    private int splitNode(int parentAddress) throws IOException {
      final boolean indexLeaf = isIndexLeaf();

      if (DO_SANITY_CHECK) {
        assert isFull();
        dump("before split:" + indexLeaf);
      }

      final boolean hashedLeaf = isHashedLeaf();
      final short recordCount = getChildrenCount();
      BtreeIndexNodeView parent = null;
      HashLeafData hashLeafData;

      try {
        if (parentAddress != 0) {
          parent = new BtreeIndexNodeView(parentAddress, true, null);
        }

        short maxIndex = (short)(getMaxChildrenCount() / 2);

        try (BtreeIndexNodeView newIndexNode = new BtreeIndexNodeView(nextPage(), true, null)) {
          syncWithStore(); // next page can cause ByteBuffer to be invalidated!
          if (parent != null) parent.syncWithStore();
          root.syncWithStore();

          newIndexNode.setIndexLeaf(indexLeaf); // newIndexNode becomes dirty

          int nextPage = getNextPage();
          setNextPage(newIndexNode.address);
          newIndexNode.setNextPage(nextPage);

          int medianKey;

          if (indexLeaf && hashedLeaf) {
            hashLeafData = new HashLeafData(this, recordCount);
            final int[] keys = hashLeafData.keys;

            hashLeafData.clean();

            final Int2IntMap map = hashLeafData.values;

            final int avg = keys.length / 2;
            medianKey = keys[avg];
            --hashedPagesCount;
            setChildrenCount((short)0);  // this node becomes dirty
            newIndexNode.setChildrenCount((short)0);

            for (int i = 0; i < avg; ++i) {
              int key = keys[i];
              insert(key, map.get(key));
              key = keys[avg + i];
              newIndexNode.insert(key, map.get(key));
            }
          }
          else {
            short recordCountInNewNode = (short)(recordCount - maxIndex);
            newIndexNode.setChildrenCount(recordCountInNewNode); // newIndexNode node becomes dirty

            ByteBuffer buffer = getBytes(indexToOffset(maxIndex), recordCountInNewNode * INTERIOR_SIZE);
            newIndexNode.putBytes(newIndexNode.indexToOffset(0), buffer);
            if (indexLeaf) {
              medianKey = newIndexNode.keyAt(0);
            }
            else {
              newIndexNode.setAddressAt(recordCountInNewNode, addressAt(recordCount));
              --maxIndex;
              medianKey = keyAt(maxIndex);     // key count is odd (since children count is even) and middle key goes to parent
            }
            setChildrenCount(maxIndex); // "this" node becomes dirty
          }

          if (parent != null) {
            if (DO_SANITY_CHECK) {
              int medianKeyInParent = parent.search(medianKey);
              int ourKey = keyAt(0);
              int ourKeyInParent = parent.search(ourKey);
              parent.dump("About to insert " +
                          medianKey +
                          "," +
                          newIndexNode.address +
                          "," +
                          medianKeyInParent +
                          " our key " +
                          ourKey +
                          ", " +
                          ourKeyInParent);

              assert medianKeyInParent < 0;
              boolean b = !parent.isFull();
              assert b;
            }

            parent.insert(medianKey, -newIndexNode.address);

            if (DO_SANITY_CHECK) {
              parent.dump("After modifying parent");
              int search = parent.search(medianKey);
              assert search >= 0;
              boolean b = parent.addressAt(search + 1) == -newIndexNode.address;
              assert b;

              dump("old node after split:");
              newIndexNode.dump("new node after split:");
            }
          }
          else {
            if (DO_SANITY_CHECK) {
              root.getNodeView().dump("Splitting root:" + medianKey);
            }

            int newRootAddress = nextPage();
            newIndexNode.syncWithStore();
            syncWithStore();

            if (DO_SANITY_CHECK) {
              System.out.println("Pages:" + pagesCount + ", elements:" + count + ", average:" + (height + 1));
            }
            root.setAddress(newRootAddress);
            parentAddress = newRootAddress;

            BtreeIndexNodeView rootNodeView = root.getNodeView();
            rootNodeView.setChildrenCount((short)1); // root becomes dirty
            rootNodeView.setKeyAt(0, medianKey);
            rootNodeView.setAddressAt(0, -address);
            rootNodeView.setAddressAt(1, -newIndexNode.address);

            if (DO_SANITY_CHECK) {
              rootNodeView.dump("New root");
              dump("First child");
              newIndexNode.dump("Second child");
            }
          }
        }
      }
      finally {
        if (parent != null) {
          parent.close();
        }
      }

      return parentAddress;
    }

    void dump(String s) throws IOException {
      if (DO_DUMP) {
        immediateDump(s);
      }
    }

    private void immediateDump(String s) throws IOException {
      short maxIndex = getChildrenCount();
      System.out.println(s + " @" + address);
      for (int i = 0; i < maxIndex; ++i) {
        System.out.print(addressAt(i) + " " + keyAt(i) + " ");
      }

      if (!isIndexLeaf()) {
        System.out.println(addressAt(maxIndex));
      }
      else {
        System.out.println();
      }
    }

    private int locate(int valueHC, boolean split) throws IOException {
      int searched = 0;
      int parentAddress = 0;
      final int maxHeight = height + 1;

      while (true) {
        if (isFull()) {
          if (split) {
            parentAddress = splitNode(parentAddress);
            if (parentAddress != 0) setAddress(parentAddress);
            --searched;
          }
          else {
            myHasFullPagesAlongPath = true;
          }
        }

        int i = search(valueHC);

        ++searched;
        if (searched > maxHeight) throw new CorruptedException(storage.getFile());

        if (isIndexLeaf()) {
          height = Math.max(height, searched);
          return i;
        }

        int address = i < 0 ? addressAt(-i - 1) : addressAt(i + 1);
        parentAddress = this.address;
        setAddress(-address);
      }
    }

    private void insert(int valueHC, int newValueId) throws IOException {
      if (DO_SANITY_CHECK) {
        boolean b = !isFull();
        assert b;
      }
      short recordCount = getChildrenCount();
      if (DO_SANITY_CHECK) assert recordCount < getMaxChildrenCount();

      final boolean indexLeaf = isIndexLeaf();

      if (indexLeaf) {
        if (recordCount == 0) {
          setHashedLeaf();
          ++hashedPagesCount;
        }

        if (isHashedLeaf()) {
          int index = hashIndex(valueHC);

          if (index < 0) {
            index = -index - 1;
          }

          setKeyAt(index, valueHC);
          setAddressAt(index, newValueId);
          setChildrenCount((short)(recordCount + 1)); // "this" node becomes dirty
          return;
        }
      }

      int medianKeyInParent = search(valueHC);
      if (DO_SANITY_CHECK) assert medianKeyInParent < 0;
      int index = -medianKeyInParent - 1;
      setChildrenCount((short)(recordCount + 1)); // "this" node becomes dirty

      final int itemsToMove = recordCount - index;
      movedMembersCount += itemsToMove;

      if (indexLeaf) {
        if (itemsToMove > LARGE_MOVE_THRESHOLD) {
          ByteBuffer buffer = getBytes(indexToOffset(index), itemsToMove * INTERIOR_SIZE);
          putBytes(indexToOffset(index + 1), buffer);
        }
        else {
          for (int i = recordCount - 1; i >= index; --i) {
            setKeyAt(i + 1, keyAt(i));
            setAddressAt(i + 1, addressAt(i));
          }
        }
        setKeyAt(index, valueHC);
        setAddressAt(index, newValueId);
      }
      else {
        // <address> (<key><address>) {record_count - 1}
        //
        setAddressAt(recordCount + 1, addressAt(recordCount));
        if (itemsToMove > LARGE_MOVE_THRESHOLD) {
          int elementsAfterIndex = recordCount - index - 1;
          if (elementsAfterIndex > 0) {
            ByteBuffer buffer = getBytes(indexToOffset(index + 1), elementsAfterIndex * INTERIOR_SIZE);
            putBytes(indexToOffset(index + 2), buffer);
          }
        }
        else {
          for (int i = recordCount - 1; i > index; --i) {
            setKeyAt(i + 1, keyAt(i));
            setAddressAt(i + 1, addressAt(i));
          }
        }

        if (index < recordCount) setKeyAt(index + 1, keyAt(index));

        setKeyAt(index, valueHC);
        setAddressAt(index + 1, newValueId);
      }

      if (DO_SANITY_CHECK) {
        if (index > 0) assert keyAt(index - 1) < keyAt(index);
        if (index < recordCount) assert keyAt(index) < keyAt(index + 1);
      }
    }

    private static final boolean useDoubleHash = true;

    private int hashIndex(int value) throws IOException {
      acquirePage();
      try {
        final ByteBuffer pageBuffer = page.rawPageBuffer();

        final int length = hashPageCapacity;
        int hash = value & 0x7fffffff;
        int index = hash % length;
        int keyAtIndex = keyAtGivenLocksAndBufferAcquired(pageBuffer, index);

        hashSearchRequests++;

        int total = 0;
        if (useDoubleHash) {
          if (keyAtIndex != value && keyAtIndex != HASH_FREE) {
            // see Knuth, p. 529
            final int probe = 1 + (hash % (length - 2));

            do {
              index -= probe;
              if (index < 0) index += length;

              keyAtIndex = keyAtGivenLocksAndBufferAcquired(pageBuffer, index);
              ++total;
              if (total > length) {
                throw new CorruptedException(storage.getFile()); // violation of Euler's theorem
              }
            }
            while (keyAtIndex != value && keyAtIndex != HASH_FREE);
          }
        }
        else {
          while (keyAtIndex != value && keyAtIndex != HASH_FREE) {
            if (index == 0) index = length;
            --index;
            keyAtIndex = keyAtGivenLocksAndBufferAcquired(pageBuffer, index);
            ++total;

            if (total > length) throw new CorruptedException(storage.getFile()); // violation of Euler's theorem
          }
        }

        maxStepsSearchedInHash = Math.max(maxStepsSearchedInHash, total);
        totalHashStepsSearched += total;

        return keyAtIndex == HASH_FREE ? -index - 1 : index;
      }
      finally {
        releasePage();
      }
    }
  }

  @Override
  public boolean processMappings(@NotNull KeyValueProcessor processor) throws IOException {
    doFlush();

    if (hasZeroKey) {
      if (!processor.process(0, zeroKeyValue)) return false;
    }

    if (root.address == UNDEFINED_ADDRESS) return true;
    root.syncWithStore();

    return processLeafPages(root.getNodeView(), processor);
  }

  @Override
  public @NotNull BTreeStatistics getStatistics() throws IOException  {
    int leafPages = height == 3 ? pagesCount - (1 + root.getNodeView().getChildrenCount() + 1) : height == 2 ? pagesCount - 1 : 1;
    return new BTreeStatistics(
      pagesCount,
      count,
      height,
      movedMembersCount,
      leafPages,
      maxStepsSearchedInHash,
      hashSearchRequests,
      totalHashStepsSearched,
      pageSize,
      storage.length()
    );
  }

  private boolean processLeafPages(@NotNull BtreeIndexNodeView node,
                                   @NotNull KeyValueProcessor processor) throws IOException {
    if (node.isIndexLeaf()) {
      return node.processMappings(processor);
    }

    // Copy children addresses first to avoid node's ByteBuffer invalidation
    final int[] childrenAddresses = new int[node.getChildrenCount() + 1];

    for (int i = 0; i < childrenAddresses.length; ++i) {
      childrenAddresses[i] = -node.addressAt(i);
    }

    if (childrenAddresses.length > 0) {
      try (BtreeIndexNodeView child = new BtreeIndexNodeView(true)) {
        for (int childrenAddress : childrenAddresses) {
          child.setAddress(childrenAddress);
          if (!processLeafPages(child, processor)) return false;
        }
      }
    }
    return true;
  }
}
