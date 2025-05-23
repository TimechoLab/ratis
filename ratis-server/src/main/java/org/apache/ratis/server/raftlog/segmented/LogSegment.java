/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.server.raftlog.segmented;

import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.server.RaftServerConfigKeys.Log.CorruptionPolicy;
import org.apache.ratis.server.metrics.SegmentedRaftLogMetrics;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.raftlog.LogEntryHeader;
import org.apache.ratis.server.raftlog.LogProtoUtils;
import org.apache.ratis.server.raftlog.RaftLogIOException;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.thirdparty.com.google.common.annotations.VisibleForTesting;
import org.apache.ratis.thirdparty.com.google.protobuf.CodedOutputStream;
import org.apache.ratis.util.FileUtils;
import org.apache.ratis.util.JavaUtils;
import org.apache.ratis.util.Preconditions;
import org.apache.ratis.util.ReferenceCountedObject;
import org.apache.ratis.util.SizeInBytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


/**
 * In-memory cache for a log segment file. All the updates will be first written
 * into LogSegment then into corresponding files in the same order.
 *
 * This class will be protected by the {@link SegmentedRaftLog}'s read-write lock.
 */
public final class LogSegment {

  static final Logger LOG = LoggerFactory.getLogger(LogSegment.class);

  enum Op {
    LOAD_SEGMENT_FILE,
    REMOVE_CACHE,
    CHECK_SEGMENT_FILE_FULL,
    WRITE_CACHE_WITH_STATE_MACHINE_CACHE,
    WRITE_CACHE_WITHOUT_STATE_MACHINE_CACHE
  }

  static long getEntrySize(LogEntryProto entry, Op op) {
    switch (op) {
      case CHECK_SEGMENT_FILE_FULL:
      case LOAD_SEGMENT_FILE:
      case WRITE_CACHE_WITH_STATE_MACHINE_CACHE:
        Preconditions.assertTrue(!LogProtoUtils.hasStateMachineData(entry),
            () -> "Unexpected LogEntryProto with StateMachine data: op=" + op + ", entry=" + entry);
        break;
      case WRITE_CACHE_WITHOUT_STATE_MACHINE_CACHE:
      case REMOVE_CACHE:
        break;
      default:
        throw new IllegalStateException("Unexpected op " + op + ", entry=" + entry);
    }
    final int serialized = entry.getSerializedSize();
    return serialized + CodedOutputStream.computeUInt32SizeNoTag(serialized) + 4L;
  }

  static class LogRecord {
    /** starting offset in the file */
    private final long offset;
    private final LogEntryHeader logEntryHeader;

    LogRecord(long offset, LogEntryProto entry) {
      this.offset = offset;
      this.logEntryHeader = LogEntryHeader.valueOf(entry);
    }

    LogEntryHeader getLogEntryHeader() {
      return logEntryHeader;
    }

    TermIndex getTermIndex() {
      return getLogEntryHeader().getTermIndex();
    }

    long getOffset() {
      return offset;
    }
  }

  private static class Records {
    private final ConcurrentNavigableMap<Long, LogRecord> map = new ConcurrentSkipListMap<>();

    int size() {
      return map.size();
    }

    LogRecord getFirst() {
      final Map.Entry<Long, LogRecord> first = map.firstEntry();
      return first != null? first.getValue() : null;
    }

    LogRecord getLast() {
      final Map.Entry<Long, LogRecord> last = map.lastEntry();
      return last != null? last.getValue() : null;
    }

    LogRecord get(long i) {
      return map.get(i);
    }

    long append(LogRecord record) {
      final long index = record.getTermIndex().getIndex();
      final LogRecord previous = map.put(index, record);
      Preconditions.assertNull(previous, "previous");
      return index;
    }

    LogRecord removeLast() {
      final Map.Entry<Long, LogRecord> last = map.pollLastEntry();
      return Objects.requireNonNull(last, "last == null").getValue();
    }

    void clear() {
      map.clear();
    }
  }

  static LogSegment newOpenSegment(RaftStorage storage, long start, SizeInBytes maxOpSize,
      SegmentedRaftLogMetrics raftLogMetrics) {
    Preconditions.assertTrue(start >= 0);
    return new LogSegment(storage, true, start, start - 1, maxOpSize, raftLogMetrics);
  }

  @VisibleForTesting
  static LogSegment newCloseSegment(RaftStorage storage,
      long start, long end, SizeInBytes maxOpSize, SegmentedRaftLogMetrics raftLogMetrics) {
    Preconditions.assertTrue(start >= 0 && end >= start);
    return new LogSegment(storage, false, start, end, maxOpSize, raftLogMetrics);
  }

  static LogSegment newLogSegment(RaftStorage storage, LogSegmentStartEnd startEnd, SizeInBytes maxOpSize,
      SegmentedRaftLogMetrics metrics) {
    return startEnd.isOpen()? newOpenSegment(storage, startEnd.getStartIndex(), maxOpSize, metrics)
        : newCloseSegment(storage, startEnd.getStartIndex(), startEnd.getEndIndex(), maxOpSize, metrics);
  }

  public static int readSegmentFile(File file, LogSegmentStartEnd startEnd, SizeInBytes maxOpSize,
      CorruptionPolicy corruptionPolicy, SegmentedRaftLogMetrics raftLogMetrics,
      Consumer<ReferenceCountedObject<LogEntryProto>> entryConsumer)
      throws IOException {
    int count = 0;
    try(SegmentedRaftLogInputStream in = new SegmentedRaftLogInputStream(file, startEnd, maxOpSize, raftLogMetrics)) {
      for(LogEntryProto prev = null, next; (next = in.nextEntry()) != null; prev = next) {
        if (prev != null) {
          Preconditions.assertTrue(next.getIndex() == prev.getIndex() + 1,
              "gap between entry %s and entry %s", prev, next);
        }

        if (entryConsumer != null) {
          // TODO: use reference count to support zero buffer copying for readSegmentFile
          entryConsumer.accept(ReferenceCountedObject.wrap(next));
        }
        count++;
      }
    } catch (IOException ioe) {
      switch (corruptionPolicy) {
        case EXCEPTION: throw ioe;
        case WARN_AND_RETURN:
          LOG.warn("Failed to read segment file {} ({}): only {} entries read successfully",
              file, startEnd, count, ioe);
          break;
        default:
          throw new IllegalStateException("Unexpected enum value: " + corruptionPolicy
              + ", class=" + CorruptionPolicy.class);
      }
    }

    return count;
  }

  static LogSegment loadSegment(RaftStorage storage, File file, LogSegmentStartEnd startEnd, SizeInBytes maxOpSize,
      boolean keepEntryInCache, Consumer<LogEntryProto> logConsumer, SegmentedRaftLogMetrics raftLogMetrics)
      throws IOException {
    final LogSegment segment = newLogSegment(storage, startEnd, maxOpSize, raftLogMetrics);
    final CorruptionPolicy corruptionPolicy = CorruptionPolicy.get(storage, RaftStorage::getLogCorruptionPolicy);
    final boolean isOpen = startEnd.isOpen();
    final int entryCount = readSegmentFile(file, startEnd, maxOpSize, corruptionPolicy, raftLogMetrics, entry -> {
      segment.append(Op.LOAD_SEGMENT_FILE, entry, keepEntryInCache || isOpen, logConsumer);
    });
    LOG.info("Successfully read {} entries from segment file {}", entryCount, file);

    final long start = startEnd.getStartIndex();
    final long end = isOpen? segment.getEndIndex(): startEnd.getEndIndex();
    final int expectedEntryCount = Math.toIntExact(end - start + 1);
    final boolean corrupted = entryCount != expectedEntryCount;
    if (corrupted) {
      LOG.warn("Segment file is corrupted: expected to have {} entries but only {} entries read successfully",
          expectedEntryCount, entryCount);
    }

    if (entryCount == 0) {
      // The segment does not have any entries, delete the file.
      final Path deleted = FileUtils.deleteFile(file);
      LOG.info("Deleted RaftLog segment since entry count is zero: startEnd={}, path={}", startEnd, deleted);
      return null;
    } else if (file.length() > segment.getTotalFileSize()) {
      // The segment has extra padding, truncate it.
      FileUtils.truncateFile(file, segment.getTotalFileSize());
    }

    try {
      segment.assertSegment(start, entryCount, corrupted, end);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to read segment file " + file, e);
    }
    return segment;
  }

  private void assertSegment(long expectedStart, int expectedEntryCount, boolean corrupted, long expectedEnd) {
    Preconditions.assertSame(expectedStart, getStartIndex(), "Segment start index");
    Preconditions.assertSame(expectedEntryCount, records.size(), "Number of records");

    final long expectedLastIndex = expectedStart + expectedEntryCount - 1;
    Preconditions.assertSame(expectedLastIndex, getEndIndex(), "Segment end index");

    final LogRecord last = records.getLast();
    if (last != null) {
      Preconditions.assertSame(expectedLastIndex, last.getTermIndex().getIndex(), "Index at the last record");
      final LogRecord first = records.getFirst();
      Objects.requireNonNull(first, "first record");
      Preconditions.assertSame(expectedStart, first.getTermIndex().getIndex(), "Index at the first record");
    }
    if (!corrupted) {
      Preconditions.assertSame(expectedEnd, expectedLastIndex, "End/last Index");
    }
  }

  /**
   * The current log entry loader simply loads the whole segment into the memory.
   * In most of the cases this may be good enough considering the main use case
   * for load log entries is for leader appending to followers.
   *
   * In the future we can make the cache loader configurable if necessary.
   */
  class LogEntryLoader {
    private final SegmentedRaftLogMetrics raftLogMetrics;

    LogEntryLoader(SegmentedRaftLogMetrics raftLogMetrics) {
      this.raftLogMetrics = raftLogMetrics;
    }

    ReferenceCountedObject<LogEntryProto> load(TermIndex key) throws IOException {
      final File file = getFile();
      // note the loading should not exceed the endIndex: it is possible that
      // the on-disk log file should be truncated but has not been done yet.
      final AtomicReference<ReferenceCountedObject<LogEntryProto>> toReturn = new AtomicReference<>();
      final LogSegmentStartEnd startEnd = LogSegmentStartEnd.valueOf(startIndex, endIndex, isOpen);
      readSegmentFile(file, startEnd, maxOpSize, getLogCorruptionPolicy(), raftLogMetrics, entryRef -> {
        final LogEntryProto entry = entryRef.retain();
        try {
          final TermIndex ti = TermIndex.valueOf(entry);
          putEntryCache(ti, entryRef, Op.LOAD_SEGMENT_FILE);
          if (ti.equals(key)) {
            entryRef.retain();
            toReturn.set(entryRef);
          }
        } finally {
          entryRef.release();
        }
      });
      loadingTimes.incrementAndGet();
      final ReferenceCountedObject<LogEntryProto> proto = toReturn.get();
      if (proto == null) {
        throw new RaftLogIOException("Failed to load log entry " + key);
      }
      return proto;
    }
  }

  private static class Item {
    private final AtomicReference<ReferenceCountedObject<LogEntryProto>> ref;
    private final long serializedSize;

    Item(ReferenceCountedObject<LogEntryProto> obj, long serializedSize) {
      this.ref = new AtomicReference<>(obj);
      this.serializedSize = serializedSize;
    }

    ReferenceCountedObject<LogEntryProto> get() {
      return ref.get();
    }

    long release() {
      final ReferenceCountedObject<LogEntryProto> entry = ref.getAndSet(null);
      if (entry == null) {
        return 0;
      }
      entry.release();
      return serializedSize;
    }
  }

  class EntryCache {
    private Map<TermIndex, Item> map = new HashMap<>();
    private final AtomicLong size = new AtomicLong();

    @Override
    public String toString() {
      return JavaUtils.getClassSimpleName(getClass()) + "-" + LogSegment.this;
    }

    long size() {
      return size.get();
    }

    synchronized ReferenceCountedObject<LogEntryProto> get(TermIndex ti) {
      if (map == null) {
        return null;
      }
      final Item ref = map.get(ti);
      return ref == null? null: ref.get();
    }

    /** After close(), the cache CANNOT be used again. */
    synchronized void close() {
      if (map == null) {
        return;
      }
      evict();
      map = null;
      LOG.info("Successfully closed {}", this);
    }

    /** After evict(), the cache can be used again. */
    synchronized void evict() {
      if (map == null) {
        return;
      }
      for (Iterator<Map.Entry<TermIndex, Item>> i = map.entrySet().iterator(); i.hasNext(); i.remove()) {
        release(i.next().getValue());
      }
    }

    synchronized void put(TermIndex key, ReferenceCountedObject<LogEntryProto> valueRef, Op op) {
      if (map == null) {
        return;
      }
      valueRef.retain();
      final long serializedSize = getEntrySize(valueRef.get(), op);
      release(map.put(key,  new Item(valueRef, serializedSize)));
      size.getAndAdd(serializedSize);
    }

    private void release(Item ref) {
      if (ref == null) {
        return;
      }
      final long serializedSize = ref.release();
      size.getAndAdd(-serializedSize);
    }

    synchronized void remove(TermIndex key) {
      if (map == null) {
        return;
      }
      release(map.remove(key));
    }
  }

  File getFile() {
    return LogSegmentStartEnd.valueOf(startIndex, endIndex, isOpen).getFile(storage);
  }

  private volatile boolean isOpen;
  private long totalFileSize = SegmentedRaftLogFormat.getHeaderLength();
  /** Segment start index, inclusive. */
  private final long startIndex;
  /** Segment end index, inclusive. */
  private volatile long endIndex;
  private final RaftStorage storage;
  private final SizeInBytes maxOpSize;
  private final LogEntryLoader cacheLoader;
  /** later replace it with a metric */
  private final AtomicInteger loadingTimes = new AtomicInteger();

  /**
   * the list of records is more like the index of a segment
   */
  private final Records records = new Records();
  /**
   * the entryCache caches the content of log entries.
   */
  private final EntryCache entryCache = new EntryCache();

  private LogSegment(RaftStorage storage, boolean isOpen, long start, long end, SizeInBytes maxOpSize,
      SegmentedRaftLogMetrics raftLogMetrics) {
    this.storage = storage;
    this.isOpen = isOpen;
    this.startIndex = start;
    this.endIndex = end;
    this.maxOpSize = maxOpSize;
    this.cacheLoader = new LogEntryLoader(raftLogMetrics);
  }

  long getStartIndex() {
    return startIndex;
  }

  long getEndIndex() {
    return endIndex;
  }

  boolean isOpen() {
    return isOpen;
  }

  int numOfEntries() {
    return Math.toIntExact(endIndex - startIndex + 1);
  }

  CorruptionPolicy getLogCorruptionPolicy() {
    return CorruptionPolicy.get(storage, RaftStorage::getLogCorruptionPolicy);
  }

  void appendToOpenSegment(Op op, ReferenceCountedObject<LogEntryProto> entryRef) {
    Preconditions.assertTrue(isOpen(), "The log segment %s is not open for append", this);
    append(op, entryRef, true, null);
  }

  private void append(Op op, ReferenceCountedObject<LogEntryProto> entryRef,
      boolean keepEntryInCache, Consumer<LogEntryProto> logConsumer) {
    final LogEntryProto entry = entryRef.retain();
    try {
      final LogRecord record = new LogRecord(totalFileSize, entry);
      if (keepEntryInCache) {
        putEntryCache(record.getTermIndex(), entryRef, op);
      }
      appendLogRecord(op, record);
      totalFileSize += getEntrySize(entry, op);

      if (logConsumer != null) {
        logConsumer.accept(entry);
      }
    } finally {
      entryRef.release();
    }
  }

  private void appendLogRecord(Op op, LogRecord record) {
    Objects.requireNonNull(record, "record == null");
    final LogRecord currentLast = records.getLast();

    final long index = record.getTermIndex().getIndex();
    if (currentLast == null) {
      Preconditions.assertTrue(index == startIndex,
              "%s: gap between start index %s and the entry to append %s", op, startIndex, index);
    } else {
      final long currentLastIndex = currentLast.getTermIndex().getIndex();
      Preconditions.assertTrue(index == currentLastIndex + 1,
              "%s: gap between last entry %s and the entry to append %s", op, currentLastIndex, index);
    }

    records.append(record);
    endIndex = index;
  }

  ReferenceCountedObject<LogEntryProto> getEntryFromCache(TermIndex ti) {
    return entryCache.get(ti);
  }

  /**
   * Acquire LogSegment's monitor so that there is no concurrent loading.
   */
  synchronized ReferenceCountedObject<LogEntryProto> loadCache(TermIndex ti) throws RaftLogIOException {
    final ReferenceCountedObject<LogEntryProto> entry = entryCache.get(ti);
    if (entry != null) {
      try {
        entry.retain();
        return entry;
      } catch (IllegalStateException ignored) {
        // The entry could be removed from the cache and released.
        // The exception can be safely ignored since it is the same as cache miss.
      }
    }
    try {
      return cacheLoader.load(ti);
    } catch (RaftLogIOException e) {
      throw e;
    } catch (Exception e) {
      throw new RaftLogIOException("Failed to loadCache for log entry " + ti, e);
    }
  }

  LogRecord getLogRecord(long index) {
    return records.get(index);
  }

  TermIndex getLastTermIndex() {
    final LogRecord last = records.getLast();
    return last == null ? null : last.getTermIndex();
  }

  long getTotalFileSize() {
    return totalFileSize;
  }

  long getTotalCacheSize() {
    return entryCache.size();
  }

  /**
   * Remove records from the given index (inclusive)
   */
  synchronized void truncate(long fromIndex) {
    Preconditions.assertTrue(fromIndex >= startIndex && fromIndex <= endIndex);
    for (long index = endIndex; index >= fromIndex; index--) {
      final LogRecord removed = records.removeLast();
      Preconditions.assertSame(index, removed.getTermIndex().getIndex(), "removedIndex");
      removeEntryCache(removed.getTermIndex());
      totalFileSize = removed.offset;
    }
    isOpen = false;
    this.endIndex = fromIndex - 1;
  }

  void close() {
    Preconditions.assertTrue(isOpen());
    isOpen = false;
  }

  @Override
  public String toString() {
    return isOpen() ? "log_" + "inprogress_" + startIndex :
        "log-" + startIndex + "_" + endIndex;
  }

  /** Comparator to find <code>index</code> in list of <code>LogSegment</code>s. */
  static final Comparator<Object> SEGMENT_TO_INDEX_COMPARATOR = (o1, o2) -> {
    if (o1 instanceof LogSegment && o2 instanceof Long) {
      return ((LogSegment) o1).compareTo((Long) o2);
    } else if (o1 instanceof Long && o2 instanceof LogSegment) {
      return Integer.compare(0, ((LogSegment) o2).compareTo((Long) o1));
    }
    throw new IllegalStateException("Unexpected objects to compare(" + o1 + "," + o2 + ")");
  };

  private int compareTo(Long l) {
    return (l >= getStartIndex() && l <= getEndIndex()) ? 0 :
        (this.getEndIndex() < l ? -1 : 1);
  }

  synchronized void clear() {
    records.clear();
    entryCache.close();
    endIndex = startIndex - 1;
  }

  int getLoadingTimes() {
    return loadingTimes.get();
  }

  void evictCache() {
    entryCache.evict();
  }

  void putEntryCache(TermIndex key, ReferenceCountedObject<LogEntryProto> valueRef, Op op) {
    entryCache.put(key, valueRef, op);
  }

  void removeEntryCache(TermIndex key) {
    entryCache.remove(key);
  }

  boolean hasCache() {
    return isOpen || entryCache.size() > 0; // open segment always has cache.
  }

  boolean containsIndex(long index) {
    return startIndex <= index && endIndex >= index;
  }

  boolean hasEntries() {
    return numOfEntries() > 0;
  }

}
