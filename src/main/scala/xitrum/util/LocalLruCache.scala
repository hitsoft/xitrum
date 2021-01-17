package xitrum.util

import java.util
import java.util.Collections
import java.util.Map.Entry

/**
 * Non-threadsafe, non-distributed LRU cache.
 *
 * http://stackoverflow.com/questions/221525/how-would-you-implement-an-lru-cache-in-java-6
 */
private class NonThreadsafeLocalLruCache[K, V](capacity: Int) extends util.LinkedHashMap[K, V](capacity + 1, 1.0f, true) {
  protected override def removeEldestEntry(eldest: Entry[K, V]): Boolean = size > capacity
}

/**
 * Threadsafe, non-distributed LRU cache.
 *
 * http://stackoverflow.com/questions/221525/how-would-you-implement-an-lru-cache-in-java-6
 *
 * Xitrum uses this for storing etags for static files. Each web server in a
 * cluster has its own cache of (file path, mtime) -> etag.
 */
object LocalLruCache {
  def apply[K, V](capacity: Int): util.Map[K, V] = Collections.synchronizedMap(new NonThreadsafeLocalLruCache[K, V](capacity))
}
