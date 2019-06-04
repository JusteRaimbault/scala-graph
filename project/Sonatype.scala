import sbt._
import Keys._

/* Adapted version of Paul's suggestion on org.improving */
trait Sonatype {
  def projectUrl= "https://github.com/openmole/spatialdata"
  def licenseDistribution = "repo"
  def licenseName = "Affero GPLv3"
  def licenseUrl= "http://www.gnu.org/licenses/"
  def developerId= "justeraimbault"
  def developerName= "Juste Raimbault"
  //def developerUrl: String
  def scmUrl        = "https://github.com/JusteRaimbault/scala-graph.git"
  def scmConnection = "scm:git:git@github.com:JusteRaimbault/scala-graph.git"

  protected def isSnapshot(s: String) = s.trim endsWith "SNAPSHOT"
  protected val nexus                 = "https://oss.sonatype.org/"
  protected val ossSnapshots          = "Sonatype OSS Snapshots" at nexus + "content/repositories/snapshots/"
  protected val ossStaging            = "Sonatype OSS Staging" at nexus + "service/local/staging/deploy/maven2/"

  protected def generatePomExtra(scalaVersion: String): xml.NodeSeq =
    <url>{ projectUrl }
    </url>
    <licenses><license>
        <name>{ licenseName }</name>
        <url>{ licenseUrl }</url>
        <distribution>{ licenseDistribution }</distribution>
    </license></licenses>
    <scm>
      <url>{ scmUrl }</url>
      <connection>{ scmConnection }</connection>
    </scm>
    <developers><developer>
      <id>{ developerId }</id>
      <name>{ developerName }</name>
    </developer></developers>

  def settings: Seq[Setting[_]] = Seq(
    publishMavenStyle := true,
    publishTo := version((v: String) => Some(if (isSnapshot(v)) ossSnapshots else ossStaging)).value,
    publishArtifact in Test := false,
    pomIncludeRepository := (_ => false),
    pomExtra := (scalaVersion)(generatePomExtra).value
  )
}
