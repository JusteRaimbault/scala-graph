object Version {
  val compiler_2_11 = "2.11.12"
  val compiler_2_12 = "2.12.7"

  //private val isSnapshot = true
  private val isSnapshot = false
  private def snapshot   = if (isSnapshot) "-SNAPSHOT" else ""
  private val major      = "1.12"
  val highest            = s"$major.5$snapshot"
  //val core               = s"$major.5$snapshot"
  // patched core for release
  val core = "1.12.5.1"
  val constrained        = s"$major.7$snapshot"
  val dot                = s"$major.1$snapshot"
  val json               = s"$major.1$snapshot"
  val misc               = s"$major.1$snapshot"
}
