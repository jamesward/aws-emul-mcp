import java.io.File

/** Link options for the C shims kyo-http and kyo-net compile into the binary.
  *
  * kyo-http's TLS shim needs OpenSSL (`-lssl -lcrypto`, also used by `Crypto`). kyo-net's io_uring shim compiles to real code
  * only when `<liburing.h>` is on the include path (buildpack-scala-native installs liburing-dev on Heroku); then liburing must
  * be linked, statically when `liburing.a` is available so the slug needs no extra library. `-z noexecstack` because Scala
  * Native's hand-written assembly lacks a `.note.GNU-stack` section.
  */
object NativeLinking {
  private def onSearchPath(envVars: Seq[String], fallback: Seq[String], fileName: String): Boolean =
    (envVars.flatMap(sys.env.get).flatMap(_.split(File.pathSeparatorChar).toSeq) ++ fallback)
      .filter(_.nonEmpty)
      .exists(dir => new File(dir, fileName).isFile)

  private val includeDirs = Seq("/usr/include", "/usr/local/include", "/usr/include/x86_64-linux-gnu", "/usr/include/aarch64-linux-gnu")
  private val libDirs     = Seq("/usr/lib", "/usr/local/lib", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu")

  private def header(name: String): Boolean = onSearchPath(Seq("C_INCLUDE_PATH", "CPATH"), includeDirs, name)
  private def lib(name: String): Boolean    = onSearchPath(Seq("LIBRARY_PATH", "LD_LIBRARY_PATH"), libDirs, name)

  val uringOptions: Seq[String] =
    if (!header("liburing.h")) Nil
    else if (lib("liburing.a")) Seq("-l:liburing.a")
    else Seq("-luring")

  val linkingOptions: Seq[String] = Seq("-lssl", "-lcrypto") ++ uringOptions ++ Seq("-Wl,-z,noexecstack")
}
