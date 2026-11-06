/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Tempus from androidx.media3 1.9.2 SimpleCache: see the class comment. The changes
 * are marked "Tempus:".
 */
package androidx.media3.datasource.cache;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.os.ConditionVariable;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media3.common.C;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.database.DatabaseIOException;
import androidx.media3.database.DatabaseProvider;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Tempus: {@link SimpleCache} that starts without listing the cache folders.
 *
 * <p>SimpleCache rebuilds its span index at every start by listing every file in its ten
 * sub-folders, although with a {@link DatabaseProvider} the name, length and touch time of every
 * file are already in the {@link CacheFileMetadataIndex}; the listing only says which sub-folder
 * each file is in. On a removable card that goes through the MediaProvider FUSE daemon that costs
 * about 1.3 ms a file: with some 25,000 downloaded pieces, seconds of I/O wait and ~10 s of
 * MediaProvider CPU on every app start.
 *
 * <p>Here new files go into a sub-folder worked out from their name ({@link #subdirFor}), and the
 * sub-folders of files placed at random before that are recorded once, after one last full scan,
 * in {@code layoutFile} on internal storage. From then on the cache starts from the metadata index
 * alone. A file removed outside the cache is still noticed: {@code getSpan} compares each span's
 * file length before it is read, as SimpleCache does.
 */
@UnstableApi
public final class IndexedSimpleCache implements Cache {

  private static final String TAG = "IndexedSimpleCache";

  /**
   * Cache files are distributed between a number of subdirectories. This helps to avoid poor
   * performance in cases where the performance of the underlying file system (e.g. FAT32) scales
   * badly with the number of files per directory. See
   * https://github.com/google/ExoPlayer/issues/4253.
   */
  private static final int SUBDIRECTORY_COUNT = 10;

  private static final String UID_FILE_SUFFIX = ".uid";

  private static final HashSet<File> lockedCacheDirs = new HashSet<>();

  private final File cacheDir;
  private final CacheEvictor evictor;
  private final CachedContentIndex contentIndex;
  @Nullable private final CacheFileMetadataIndex fileIndex;
  // Tempus: sub-folders of files placed at random, "name<TAB>subdir" lines after a "uid=" line.
  private final File layoutFile;
  private final HashMap<String, ArrayList<Listener>> listeners;
  private final boolean touchCacheSpans;

  private long uid;
  private long totalSpace;
  private boolean released;
  @Nullable private CacheException initializationException;

  /**
   * Returns whether {@code cacheFolder} is locked by a {@link SimpleCache} instance. To unlock the
   * folder the {@link SimpleCache} instance should be released.
   */
  public static synchronized boolean isCacheFolderLocked(File cacheFolder) {
    return lockedCacheDirs.contains(cacheFolder.getAbsoluteFile());
  }

  /**
   * Deletes all content belonging to a cache instance.
   *
   * <p>This method may be slow and shouldn't normally be called on the main thread.
   *
   * @param cacheDir The cache directory.
   * @param databaseProvider The database in which index data is stored, or {@code null} if the
   *     cache used a legacy index.
   */
  @WorkerThread
  public static void delete(File cacheDir, @Nullable DatabaseProvider databaseProvider) {
    if (!cacheDir.exists()) {
      return;
    }

    File[] files = cacheDir.listFiles();
    if (files == null) {
      cacheDir.delete();
      return;
    }

    if (databaseProvider != null) {
      // Make a best effort to read the cache UID and delete associated index data before deleting
      // cache directory itself.
      long uid = loadUid(files);
      if (uid != UID_UNSET) {
        try {
          CacheFileMetadataIndex.delete(databaseProvider, uid);
        } catch (DatabaseIOException e) {
          Log.w(TAG, "Failed to delete file metadata: " + uid);
        }
        try {
          CachedContentIndex.delete(databaseProvider, uid);
        } catch (DatabaseIOException e) {
          Log.w(TAG, "Failed to delete file metadata: " + uid);
        }
      }
    }

    Util.recursiveDelete(cacheDir);
  }

  /**
   * Constructs the cache.
   *
   * @param cacheDir A dedicated cache directory.
   * @param evictor The evictor to be used.
   * @param databaseProvider Provides the database in which the cache index is stored.
   * @param layoutFile Tempus: where the sub-folders of files placed before this class are kept,
   *     on fast (internal) storage.
   */
  public IndexedSimpleCache(
      File cacheDir, CacheEvictor evictor, DatabaseProvider databaseProvider, File layoutFile) {
    CachedContentIndex contentIndex =
        new CachedContentIndex(
            databaseProvider,
            cacheDir,
            /* legacyIndexSecretKey= */ null,
            /* legacyIndexEncrypt= */ false,
            /* preferLegacyIndex= */ false);
    CacheFileMetadataIndex fileIndex = new CacheFileMetadataIndex(databaseProvider);
    if (!lockFolder(cacheDir)) {
      throw new IllegalStateException("Another IndexedSimpleCache instance uses the folder: " + cacheDir);
    }

    this.cacheDir = cacheDir;
    this.evictor = evictor;
    this.contentIndex = contentIndex;
    this.fileIndex = fileIndex;
    this.layoutFile = layoutFile;
    listeners = new HashMap<>();
    touchCacheSpans = evictor.requiresCacheSpanTouches();
    uid = UID_UNSET;

    // Start cache initialization.
    final ConditionVariable conditionVariable = new ConditionVariable();
    new Thread("IndexedCacheInit") {
      @Override
      public void run() {
        synchronized (IndexedSimpleCache.this) {
          conditionVariable.open();
          initialize();
          IndexedSimpleCache.this.evictor.onCacheInitialized();
        }
      }
    }.start();
    conditionVariable.block();
  }

  /**
   * Checks whether the cache was initialized successfully.
   *
   * @throws CacheException If an error occurred during initialization.
   */
  public synchronized void checkInitialization() throws CacheException {
    if (initializationException != null) {
      throw initializationException;
    }
  }

  @Override
  public synchronized long getUid() {
    return uid;
  }

  @Override
  public synchronized void release() {
    if (released) {
      return;
    }
    listeners.clear();
    removeStaleSpans();
    try {
      contentIndex.store();
    } catch (IOException e) {
      Log.e(TAG, "Storing index file failed", e);
    } finally {
      unlockFolder(cacheDir);
      released = true;
    }
  }

  @Override
  public synchronized NavigableSet<CacheSpan> addListener(String key, Listener listener) {
    checkState(!released);
    checkNotNull(key);
    checkNotNull(listener);
    ArrayList<Listener> listenersForKey = listeners.get(key);
    if (listenersForKey == null) {
      listenersForKey = new ArrayList<>();
      listeners.put(key, listenersForKey);
    }
    listenersForKey.add(listener);
    return getCachedSpans(key);
  }

  @Override
  public synchronized void removeListener(String key, Listener listener) {
    if (released) {
      return;
    }
    ArrayList<Listener> listenersForKey = listeners.get(key);
    if (listenersForKey != null) {
      listenersForKey.remove(listener);
      if (listenersForKey.isEmpty()) {
        listeners.remove(key);
      }
    }
  }

  @Override
  public synchronized NavigableSet<CacheSpan> getCachedSpans(String key) {
    checkState(!released);
    CachedContent cachedContent = contentIndex.get(key);
    return cachedContent == null || cachedContent.isEmpty()
        ? new TreeSet<>()
        : new TreeSet<CacheSpan>(cachedContent.getSpans());
  }

  @Override
  public synchronized Set<String> getKeys() {
    checkState(!released);
    return new HashSet<>(contentIndex.getKeys());
  }

  @Override
  public synchronized long getCacheSpace() {
    checkState(!released);
    return totalSpace;
  }

  @Override
  public synchronized CacheSpan startReadWrite(String key, long position, long length)
      throws InterruptedException, CacheException {
    checkState(!released);
    checkInitialization();

    while (true) {
      CacheSpan span = startReadWriteNonBlocking(key, position, length);
      if (span != null) {
        return span;
      } else {
        // Lock not available. We'll be woken up when a span is added, or when a locked span is
        // released. We'll be able to make progress when either:
        // 1. A span is added for the requested key that covers the requested position, in which
        //    case a read can be started.
        // 2. The lock for the requested key is released, in which case a write can be started.
        wait();
      }
    }
  }

  @Override
  @Nullable
  public synchronized CacheSpan startReadWriteNonBlocking(String key, long position, long length)
      throws CacheException {
    checkState(!released);
    checkInitialization();

    SimpleCacheSpan span = getSpan(key, position, length);

    if (span.isCached) {
      // Read case.
      return touchSpan(key, span);
    }

    CachedContent cachedContent = contentIndex.getOrAdd(key);
    if (cachedContent.lockRange(position, span.length)) {
      // Write case.
      return span;
    }

    // Lock not available.
    return null;
  }

  @Override
  public synchronized File startFile(String key, long position, long length) throws CacheException {
    checkState(!released);
    checkInitialization();

    CachedContent cachedContent = contentIndex.get(key);
    checkNotNull(cachedContent);
    checkState(cachedContent.isFullyLocked(position, length));
    if (!cacheDir.exists()) {
      // The cache directory has been deleted from underneath us. Recreate it, and remove in-memory
      // spans corresponding to cache files that no longer exist.
      createCacheDirectories(cacheDir);
      removeStaleSpans();
    }
    evictor.onStartFile(this, key, position, length);
    // Tempus: the sub-folder follows from the file's name (spread as evenly as at random), so the
    // file can be found again at start-up without listing the folders.
    File cacheSubDir = new File(cacheDir, subdirFor(cachedContent.id, position));
    if (!cacheSubDir.exists()) {
      createCacheDirectories(cacheSubDir);
    }
    long lastTouchTimestamp = System.currentTimeMillis();
    return SimpleCacheSpan.getCacheFile(
        cacheSubDir, cachedContent.id, position, lastTouchTimestamp);
  }

  @Override
  public synchronized void commitFile(File file, long length) throws CacheException {
    checkState(!released);
    if (!file.exists()) {
      return;
    }
    if (length == 0) {
      file.delete();
      return;
    }

    SimpleCacheSpan span =
        checkNotNull(SimpleCacheSpan.createCacheEntry(file, length, contentIndex));
    CachedContent cachedContent = checkNotNull(contentIndex.get(span.key));
    checkState(cachedContent.isFullyLocked(span.position, span.length));

    // Check if the span conflicts with the set content length
    long contentLength = ContentMetadata.getContentLength(cachedContent.getMetadata());
    if (contentLength != C.LENGTH_UNSET) {
      checkState((span.position + span.length) <= contentLength);
    }

    if (fileIndex != null) {
      String fileName = file.getName();
      try {
        fileIndex.set(fileName, span.length, span.lastTouchTimestamp);
      } catch (IOException e) {
        throw new CacheException(e);
      }
    }
    addSpan(span);
    try {
      contentIndex.store();
    } catch (IOException e) {
      throw new CacheException(e);
    }
    notifyAll();
  }

  @Override
  public synchronized void releaseHoleSpan(CacheSpan holeSpan) {
    checkState(!released);
    CachedContent cachedContent = checkNotNull(contentIndex.get(holeSpan.key));
    cachedContent.unlockRange(holeSpan.position);
    contentIndex.maybeRemove(cachedContent.key);
    notifyAll();
  }

  @Override
  public synchronized void removeResource(String key) {
    checkState(!released);
    for (CacheSpan span : getCachedSpans(key)) {
      removeSpanInternal(span);
    }
  }

  @Override
  public synchronized void removeSpan(CacheSpan span) {
    checkState(!released);
    removeSpanInternal(span);
  }

  @Override
  public synchronized boolean isCached(String key, long position, long length) {
    checkState(!released);
    @Nullable CachedContent cachedContent = contentIndex.get(key);
    return cachedContent != null && cachedContent.getCachedBytesLength(position, length) >= length;
  }

  @Override
  public synchronized long getCachedLength(String key, long position, long length) {
    checkState(!released);
    if (length == C.LENGTH_UNSET) {
      length = Long.MAX_VALUE;
    }
    @Nullable CachedContent cachedContent = contentIndex.get(key);
    return cachedContent != null ? cachedContent.getCachedBytesLength(position, length) : -length;
  }

  @Override
  public synchronized long getCachedBytes(String key, long position, long length) {
    long endPosition = length == C.LENGTH_UNSET ? Long.MAX_VALUE : position + length;
    if (endPosition < 0) {
      // The calculation rolled over (length is probably Long.MAX_VALUE).
      endPosition = Long.MAX_VALUE;
    }
    long currentPosition = position;
    long cachedBytes = 0;
    while (currentPosition < endPosition) {
      long maxRemainingLength = endPosition - currentPosition;
      long blockLength = getCachedLength(key, currentPosition, maxRemainingLength);
      if (blockLength > 0) {
        cachedBytes += blockLength;
      } else {
        // There's a hole of length -blockLength.
        blockLength = -blockLength;
      }
      currentPosition += blockLength;
    }
    return cachedBytes;
  }

  @Override
  public synchronized void applyContentMetadataMutations(
      String key, ContentMetadataMutations mutations) throws CacheException {
    checkState(!released);
    checkInitialization();

    contentIndex.applyContentMetadataMutations(key, mutations);
    try {
      contentIndex.store();
    } catch (IOException e) {
      throw new CacheException(e);
    }
  }

  @Override
  public synchronized ContentMetadata getContentMetadata(String key) {
    checkState(!released);
    return contentIndex.getContentMetadata(key);
  }

  /** Ensures that the cache's in-memory representation has been initialized. */
  private void initialize() {
    if (!cacheDir.exists()) {
      try {
        createCacheDirectories(cacheDir);
      } catch (CacheException e) {
        initializationException = e;
        return;
      }
    }

    @Nullable File[] files = cacheDir.listFiles();
    if (files == null) {
      String message = "Failed to list cache directory files: " + cacheDir;
      Log.e(TAG, message);
      initializationException = new CacheException(message);
      return;
    }

    uid = loadUid(files);
    if (uid == UID_UNSET) {
      try {
        uid = createUid(cacheDir);
      } catch (IOException e) {
        String message = "Failed to create cache UID: " + cacheDir;
        Log.e(TAG, message, e);
        initializationException = new CacheException(message, e);
        return;
      }
    }

    try {
      contentIndex.initialize(uid);
      if (fileIndex != null) {
        fileIndex.initialize(uid);
        Map<String, CacheFileMetadata> fileMetadata = fileIndex.getAll();
        // Tempus: no listing when the sub-folder of every placed-at-random file is on record.
        @Nullable Map<String, String> layout = readLayout(uid);
        if (layout != null) {
          loadFromIndex(fileMetadata, layout);
        } else {
          Map<String, String> scattered = new HashMap<>();
          unindexedFound = 0;
          loadDirectory(cacheDir, /* isRoot= */ true, files, fileMetadata, scattered);
          fileIndex.removeAll(fileMetadata.keySet());
          Log.i(TAG, "Scanned " + cacheDir + ": " + totalSpans() + " spans, " + unindexedFound
              + " of them had no metadata row and got one");
          writeLayout(uid, scattered);
        }
      } else {
        loadDirectory(cacheDir, /* isRoot= */ true, files, /* fileMetadata= */ null, null);
      }
    } catch (IOException e) {
      String message = "Failed to initialize cache indices: " + cacheDir;
      Log.e(TAG, message, e);
      initializationException = new CacheException(message, e);
      return;
    }

    contentIndex.removeEmpty();
    try {
      contentIndex.store();
    } catch (IOException e) {
      Log.e(TAG, "Storing index file failed", e);
    }
  }

  /**
   * Loads a cache directory. If the root directory is passed, also loads any subdirectories.
   *
   * @param directory The directory.
   * @param isRoot Whether the directory is the root directory.
   * @param files The files belonging to the directory.
   * @param fileMetadata A mutable map containing cache file metadata, keyed by file name. The map
   *     is modified by removing entries for all loaded files. When the method call returns, the map
   *     will contain only metadata that was unused. May be null if no file metadata is available.
   */
  // ---- Tempus: start-up from the metadata index --------------------------------------------

  // v2: the scan also gives every file it finds a metadata row (a v1 record may lack some).
  private static final String LAYOUT_HEADER = "v2 uid=";
  private static final String SPAN_SUFFIX = ".v3.exo";
  private int unindexedFound;

  private int totalSpans() {
    int n = 0;
    for (CachedContent content : contentIndex.getAll()) n += content.getSpans().size();
    return n;
  }

  /** The sub-folder for a file of content {@code id} starting at {@code position}. */
  /* package */ static String subdirFor(int id, long position) {
    long mix = id * 0x9E3779B97F4A7C15L + position * 0xC2B2AE3D27D4EB4FL;
    return Integer.toString((int) ((mix >>> 33) % SUBDIRECTORY_COUNT));
  }

  /** {@link #subdirFor(int, long)} of a cache file name, or "" for a name that isn't one. */
  /* package */ static String subdirFor(String fileName) {
    String[] parts = fileName.split("\\.");
    if (parts.length != 5 || !fileName.endsWith(SPAN_SUFFIX)) return "";
    try {
      return subdirFor(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
    } catch (NumberFormatException e) {
      return "";
    }
  }

  /**
   * Builds the spans from the metadata index alone: each file is in the sub-folder the layout
   * file names for it, or else in {@link #subdirFor}. Nothing on the cache's storage is read.
   */
  private void loadFromIndex(Map<String, CacheFileMetadata> fileMetadata, Map<String, String> layout)
      throws DatabaseIOException {
    Set<String> unusable = new HashSet<>();
    for (Map.Entry<String, CacheFileMetadata> entry : fileMetadata.entrySet()) {
      String name = entry.getKey();
      CacheFileMetadata metadata = entry.getValue();
      @Nullable String subdir = layout.get(name);
      if (subdir == null) subdir = subdirFor(name);
      File file = subdir.isEmpty() ? new File(cacheDir, name) : new File(new File(cacheDir, subdir), name);
      @Nullable
      SimpleCacheSpan span =
          name.endsWith(SPAN_SUFFIX)
              ? SimpleCacheSpan.createCacheEntry(
                  file, metadata.length, metadata.lastTouchTimestamp, contentIndex)
              : null;
      if (span != null) {
        addSpan(span);
      } else {
        unusable.add(name);
      }
    }
    if (fileIndex != null) fileIndex.removeAll(unusable);
    Log.i(TAG, "Loaded " + (fileMetadata.size() - unusable.size()) + " spans from the index without listing " + cacheDir);
  }

  /** The recorded sub-folders, or null to list the folders (no record yet, or another cache). */
  @Nullable
  private Map<String, String> readLayout(long uid) {
    if (!layoutFile.exists()) return null;
    Map<String, String> layout = new HashMap<>();
    try (java.io.BufferedReader in =
        new java.io.BufferedReader(new java.io.FileReader(layoutFile), 1 << 16)) {
      String header = in.readLine();
      if (header == null || !header.equals(LAYOUT_HEADER + Long.toHexString(uid))) return null;
      String line;
      while ((line = in.readLine()) != null) {
        int tab = line.indexOf('\t');
        if (tab > 0) layout.put(line.substring(0, tab), line.substring(tab + 1));
      }
    } catch (IOException e) {
      Log.w(TAG, "Reading " + layoutFile + " failed; listing the cache instead", e);
      return null;
    }
    return layout;
  }

  /** Records where the files placed at random are, written whole or not at all. */
  private void writeLayout(long uid, Map<String, String> scattered) {
    android.util.AtomicFile atomic = new android.util.AtomicFile(layoutFile);
    java.io.FileOutputStream out = null;
    try {
      File parent = layoutFile.getParentFile();
      if (parent != null) parent.mkdirs();
      out = atomic.startWrite();
      java.io.Writer writer =
          new java.io.BufferedWriter(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8), 1 << 16);
      writer.write(LAYOUT_HEADER + Long.toHexString(uid) + "\n");
      for (Map.Entry<String, String> entry : scattered.entrySet()) {
        writer.write(entry.getKey() + "\t" + entry.getValue() + "\n");
      }
      writer.flush();
      atomic.finishWrite(out);
      Log.i(TAG, "Recorded the sub-folders of " + scattered.size() + " files in " + layoutFile);
    } catch (IOException e) {
      if (out != null) atomic.failWrite(out);
      Log.w(TAG, "Writing " + layoutFile + " failed", e);
    }
  }

  // ---- end Tempus ----------------------------------------------------------------------------

  private void loadDirectory(
      File directory,
      boolean isRoot,
      @Nullable File[] files,
      @Nullable Map<String, CacheFileMetadata> fileMetadata,
      @Nullable Map<String, String> scattered) {
    if (files == null || files.length == 0) {
      // Either (a) directory isn't really a directory (b) it's empty, or (c) listing files failed.
      if (!isRoot) {
        // For (a) and (b) deletion is the desired result. For (c) it will be a no-op if the
        // directory is non-empty, so there's no harm in trying.
        directory.delete();
      }
      return;
    }
    for (File file : files) {
      String fileName = file.getName();
      if (isRoot && fileName.indexOf('.') == -1) {
        loadDirectory(file, /* isRoot= */ false, file.listFiles(), fileMetadata, scattered);
      } else {
        if (isRoot
            && (CachedContentIndex.isIndexFile(fileName) || fileName.endsWith(UID_FILE_SUFFIX))) {
          // Skip expected UID and index files in the root directory.
          continue;
        }
        long length = C.LENGTH_UNSET;
        long lastTouchTimestamp = C.TIME_UNSET;
        @Nullable
        CacheFileMetadata metadata = fileMetadata != null ? fileMetadata.remove(fileName) : null;
        if (metadata != null) {
          length = metadata.length;
          lastTouchTimestamp = metadata.lastTouchTimestamp;
        }
        @Nullable
        SimpleCacheSpan span =
            SimpleCacheSpan.createCacheEntry(file, length, lastTouchTimestamp, contentIndex);
        if (span != null) {
          addSpan(span);
          // Tempus: a file the metadata index doesn't know (SimpleCache measures those on disk at
          // every start and never records them) gets a row, or a start from the index would miss
          // it and that part of the song would be fetched from the server again.
          if (scattered != null && fileMetadata != null && metadata == null && fileIndex != null) {
            try {
              fileIndex.set(span.file.getName(), span.length, span.lastTouchTimestamp);
              unindexedFound++;
            } catch (IOException e) {
              Log.w(TAG, "Failed to index " + span.file, e);
            }
          }
          // Tempus: remember where it is, unless it is where subdirFor puts it.
          String subdir = isRoot ? "" : directory.getName();
          if (scattered != null && !subdir.equals(subdirFor(span.file.getName()))) {
            scattered.put(span.file.getName(), subdir);
          }
        } else {
          file.delete();
        }
      }
    }
  }

  /**
   * Touches a cache span, returning the updated result. If the evictor does not require cache spans
   * to be touched, then this method does nothing and the span is returned without modification.
   *
   * @param key The key of the span being touched.
   * @param span The span being touched.
   * @return The updated span.
   */
  private SimpleCacheSpan touchSpan(String key, SimpleCacheSpan span) {
    if (!touchCacheSpans) {
      return span;
    }
    String fileName = checkNotNull(span.file).getName();
    long length = span.length;
    long lastTouchTimestamp = System.currentTimeMillis();
    boolean updateFile = false;
    if (fileIndex != null) {
      try {
        fileIndex.set(fileName, length, lastTouchTimestamp);
      } catch (IOException e) {
        Log.w(TAG, "Failed to update index with new touch timestamp.");
      }
    } else {
      // Updating the file itself to incorporate the new last touch timestamp is much slower than
      // updating the file index. Hence we only update the file if we don't have a file index.
      updateFile = true;
    }
    SimpleCacheSpan newSpan =
        checkNotNull(contentIndex.get(key))
            .setLastTouchTimestamp(span, lastTouchTimestamp, updateFile);
    notifySpanTouched(span, newSpan);
    return newSpan;
  }

  /**
   * Returns the cache span corresponding to the provided key and range. See {@link
   * Cache#startReadWrite(String, long, long)} for detailed descriptions of the returned spans.
   *
   * @param key The key of the span being requested.
   * @param position The position of the span being requested.
   * @param length The length of the span, or {@link C#LENGTH_UNSET} if unbounded.
   * @return The corresponding cache {@link SimpleCacheSpan}.
   */
  private SimpleCacheSpan getSpan(String key, long position, long length) {
    @Nullable CachedContent cachedContent = contentIndex.get(key);
    if (cachedContent == null) {
      return SimpleCacheSpan.createHole(key, position, length);
    }
    while (true) {
      SimpleCacheSpan span = cachedContent.getSpan(position, length);
      if (span.isCached && checkNotNull(span.file).length() != span.length) {
        // The file has been modified or deleted underneath us. It's likely that other files will
        // have been modified too, so scan the whole in-memory representation.
        removeStaleSpans();
        continue;
      }
      return span;
    }
  }

  /**
   * Adds a cached span to the in-memory representation.
   *
   * @param span The span to be added.
   */
  private void addSpan(SimpleCacheSpan span) {
    contentIndex.getOrAdd(span.key).addSpan(span);
    totalSpace += span.length;
    notifySpanAdded(span);
  }

  private void removeSpanInternal(CacheSpan span) {
    @Nullable CachedContent cachedContent = contentIndex.get(span.key);
    if (cachedContent == null || !cachedContent.removeSpan(span)) {
      return;
    }
    totalSpace -= span.length;
    if (fileIndex != null) {
      String fileName = checkNotNull(span.file).getName();
      try {
        fileIndex.remove(fileName);
      } catch (IOException e) {
        // This will leave a stale entry in the file index. It will be removed next time the cache
        // is initialized.
        Log.w(TAG, "Failed to remove file index entry for: " + fileName);
      }
    }
    contentIndex.maybeRemove(cachedContent.key);
    notifySpanRemoved(span);
  }

  /**
   * Scans all of the cached spans in the in-memory representation, removing any for which the
   * underlying file lengths no longer match.
   */
  private void removeStaleSpans() {
    ArrayList<CacheSpan> spansToBeRemoved = new ArrayList<>();
    for (CachedContent cachedContent : contentIndex.getAll()) {
      for (CacheSpan span : cachedContent.getSpans()) {
        if (checkNotNull(span.file).length() != span.length) {
          spansToBeRemoved.add(span);
        }
      }
    }
    for (int i = 0; i < spansToBeRemoved.size(); i++) {
      removeSpanInternal(spansToBeRemoved.get(i));
    }
  }

  private void notifySpanRemoved(CacheSpan span) {
    @Nullable ArrayList<Listener> keyListeners = listeners.get(span.key);
    if (keyListeners != null) {
      for (int i = keyListeners.size() - 1; i >= 0; i--) {
        keyListeners.get(i).onSpanRemoved(this, span);
      }
    }
    evictor.onSpanRemoved(this, span);
  }

  private void notifySpanAdded(SimpleCacheSpan span) {
    @Nullable ArrayList<Listener> keyListeners = listeners.get(span.key);
    if (keyListeners != null) {
      for (int i = keyListeners.size() - 1; i >= 0; i--) {
        keyListeners.get(i).onSpanAdded(this, span);
      }
    }
    evictor.onSpanAdded(this, span);
  }

  private void notifySpanTouched(SimpleCacheSpan oldSpan, CacheSpan newSpan) {
    @Nullable ArrayList<Listener> keyListeners = listeners.get(oldSpan.key);
    if (keyListeners != null) {
      for (int i = keyListeners.size() - 1; i >= 0; i--) {
        keyListeners.get(i).onSpanTouched(this, oldSpan, newSpan);
      }
    }
    evictor.onSpanTouched(this, oldSpan, newSpan);
  }

  /**
   * Loads the cache UID from the files belonging to the root directory.
   *
   * @param files The files belonging to the root directory.
   * @return The loaded UID, or {@link #UID_UNSET} if a UID has not yet been created.
   */
  private static long loadUid(File[] files) {
    for (File file : files) {
      String fileName = file.getName();
      if (fileName.endsWith(UID_FILE_SUFFIX)) {
        try {
          return parseUid(fileName);
        } catch (NumberFormatException e) {
          // This should never happen, but if it does delete the malformed UID file and continue.
          Log.e(TAG, "Malformed UID file: " + file);
          file.delete();
        }
      }
    }
    return UID_UNSET;
  }

  @SuppressWarnings("TrulyRandom")
  private static long createUid(File directory) throws IOException {
    // Generate a non-negative UID.
    long uid = new SecureRandom().nextLong();
    uid = uid == Long.MIN_VALUE ? 0 : Math.abs(uid);
    // Persist it as a file.
    String hexUid = Long.toString(uid, /* radix= */ 16);
    File hexUidFile = new File(directory, hexUid + UID_FILE_SUFFIX);
    if (!hexUidFile.createNewFile()) {
      // False means that the file already exists, so this should never happen.
      throw new IOException("Failed to create UID file: " + hexUidFile);
    }
    return uid;
  }

  private static long parseUid(String fileName) {
    return Long.parseLong(fileName.substring(0, fileName.indexOf('.')), /* radix= */ 16);
  }

  private static void createCacheDirectories(File cacheDir) throws CacheException {
    // If mkdirs() returns false, double check that the directory doesn't exist before throwing.
    if (!cacheDir.mkdirs() && !cacheDir.isDirectory()) {
      String message = "Failed to create cache directory: " + cacheDir;
      Log.e(TAG, message);
      throw new CacheException(message);
    }
  }

  private static synchronized boolean lockFolder(File cacheDir) {
    return lockedCacheDirs.add(cacheDir.getAbsoluteFile());
  }

  private static synchronized void unlockFolder(File cacheDir) {
    lockedCacheDirs.remove(cacheDir.getAbsoluteFile());
  }
}
