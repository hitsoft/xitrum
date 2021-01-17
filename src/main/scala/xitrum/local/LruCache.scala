package xitrum.local

import xitrum.{Cache, Config}
import xitrum.util.LocalLruCache

/**
 * Config in xitrum.conf:
 *
 * {{{
 * xitrum {
 *   cache {
 *     "xitrum.local.LruCache" {
 *       maxElems = 10000
 *     }
 *   }
 * }
 * }}}
 */
class LruCache extends Cache {
  private[this] val cache = {
    val className = getClass.getName
    val maxElems  = Config.xitrum.config.getInt("cache.\"" + className + "\".maxElems")

    // Use Int for expireAtSec, not Long, because we only need second precision,
    // not millisenod precision:
    //            key   expireAtSec value
    LocalLruCache[Any, (Int,        Any)](maxElems)
  }

  def start(): Unit = {}
  def stop(): Unit = {}

  def isDefinedAt(key: Any): Boolean = cache.containsKey(key)

  def get(key: Any): Option[Any] = get(key, removeStale = true)

  def put(key: Any, value: Any): Unit = {
    cache.put(key, (-1, value))
  }

  def putIfAbsent(key: Any, value: Any): Unit = {
    if (get(key, removeStale = false).isEmpty) cache.put(key, (-1, value))
  }

  def putSecond(key: Any, value: Any, seconds: Int): Unit = {
    val expireAtSec = (System.currentTimeMillis() / 1000L + seconds).toInt
    cache.put(key, (expireAtSec, value))
  }

  def putSecondIfAbsent(key: Any, value: Any, seconds: Int): Unit = {
    if (get(key, removeStale = false).isEmpty) putSecond(key, value, seconds)
  }

  def remove(key: Any): Unit = {
    cache.remove(key)
  }

  def clear(): Unit = {
    cache.clear()
  }

  //----------------------------------------------------------------------------

  private def get(key: Any, removeStale: Boolean): Option[Any] = {
    val tuple = cache.get(key)
    if (tuple == null) {
      None
    } else {
      val expireAtSec = tuple._1
      val value       = tuple._2
      if (expireAtSec < 0) {
        Some(value)
      } else {
        // Compare at millisec precision for a little more correctness, so that
        // when TTL is 1s and 1500ms has passed, the result will more likely be
        // None
        val nowMs = System.currentTimeMillis()
        if (expireAtSec.toLong * 1000L < nowMs) {
          if (removeStale) remove(key)
          None
        } else {
          Some(value)
        }
      }
    }
  }
}
