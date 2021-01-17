package xitrum

import org.slf4s.{Logger, LoggerFactory}

/**
 * If you don't care about the class name where the log is made, without having
 * to extend this trait, you can call like this directly:
 * xitrum.Log.debug("msg"), xitrum.Log.info("msg") etc.
 */
trait Log {
  // Although the class name can be determined by sniffing around on the stack:
  // (Thread.currentThread.getStackTrace)(2).getClassName
  //
  // We use a trait for better speed, because getStackTrace is slow.

  /** Log name is inferred from name of the class extending this trait. */
  lazy val log: Logger = LoggerFactory.getLogger(getClass)
}
