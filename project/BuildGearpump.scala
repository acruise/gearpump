/*
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import Dependencies._
import com.typesafe.sbt.SbtPgp.autoImport._
import sbt.Keys._
import sbt._
import sbtassembly.AssemblyPlugin.autoImport._
import xerial.sbt.Sonatype._

object BuildGearpump {

  val projectName = "gearpump"

  val commonSettings = sonatypeSettings ++
    Seq(
      resolvers ++= Seq(
        // https://repo1.maven.org/maven2 has been added by default
        "apache-repo" at "https://repository.apache.org/content/repositories",
        Resolver.sonatypeRepo("releases")
      ),
      scalaVersion := scalaVersionNumber,
      crossScalaVersions := crossScalaVersionNumbers,
      organization := "io.github.gearpump",
      useGpg := false,
      pgpSecretRing := file("./secring.asc"),
      pgpPublicRing := file("./pubring.asc"),
      updateOptions := updateOptions.value.withGigahorse(false),
      scalacOptions ++= Seq(
        "-deprecation", // Emit warning and location for usages of deprecated APIs
        "-encoding", "UTF-8", // Specify character encoding used by source files
        "-feature", // Emit warning and location for usages of features
                    // that should be imported explicitly
        "-language:existentials", // Enable existential types
        "-language:implicitConversions", // Enable implicit conversions
        "-Ywarn-unused-import" // Warn on unused imports
      ),
      publishMavenStyle := true,

      pgpPassphrase := Option(System.getenv().get("PASSPHRASE")).map(_.toArray),
      credentials += Credentials(
        "Sonatype Nexus Repository Manager",
        "oss.sonatype.org",
        System.getenv().get("SONATYPE_USERNAME"),
        System.getenv().get("SONATYPE_PASSWORD")),

      pomIncludeRepository := { _ => false },

      publishTo := {
        val nexus = "https://oss.sonatype.org/"
        if (isSnapshot.value) {
          Some("snapshots" at nexus + "content/repositories/snapshots")
        } else {
          Some("releases" at nexus + "service/local/staging/deploy/maven2")
        }
      },

      pomExtra := {
        // scalastyle:off line.size.limit
        <url>https://github.com/gearpump/gearpump</url>
        <licenses>
          <license>
            <name>Apache 2</name>
            <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
          </license>
        </licenses>
        <scm>
          <connection>scm:git:git@github.com:gearpump/gearpump.git</connection>
          <url>https://github.com/gearpump/gearpump.git</url>
        </scm>
        <developers>
          <developer>
            <id>gearpump</id>
            <name>Gearpump Team</name>
            <url>https://github.com/orgs/gearpump/teams</url>
          </developer>
        </developers>
        // scalastyle:on line.size.limit
      },

      publishArtifact in Test := true,

      pomPostProcess := {
        (node: xml.Node) => changeShadedDeps(
          Set(
            "org.scoverage",
            "org.scala-lang"
          ),
          List.empty[xml.Node],
          node)
      },

      cleanFiles += (baseDirectory.value / "examples" / "target")
    )

  val noPublish = Seq(
    publish := {},
    publishLocal := {},
    publishArtifact := false,
    publishArtifact in Test := false
  )

  lazy val myAssemblySettings = Seq(
    test in assembly := {},
    assemblyOption in assembly ~= {
      _.copy(includeScala = false)
    },
    assemblyJarName in assembly := {
      s"${name.value}_${scalaBinaryVersion.value}-${version.value}-assembly.jar"
    },
    assemblyShadeRules in assembly := Seq(
      ShadeRule.rename("com.romix.**" -> "io.gearpump.@0").inAll,
      ShadeRule.rename("com.esotericsoftware.**" ->
        "io.gearpump.@0").inAll,
      ShadeRule.rename("org.objenesis.**" -> "io.gearpump.@0").inAll,
      ShadeRule.rename("com.google.common.**" -> "io.gearpump.@0").inAll,
      ShadeRule.rename("com.google.thirdparty.**" -> "io.gearpump.@0").inAll,
      ShadeRule.rename("com.codahale.metrics.**" ->
        "io.gearpump.@0").inAll,
      ShadeRule.rename("com.gs.collections.**" ->
        "io.gearpump.gs.collections.@0").inAll
    ),
    target in assembly := baseDirectory.value / "target" / scalaBinaryVersion.value
  )

  def changeShadedDeps(toExclude: Set[String], toInclude: List[xml.Node],
      node: xml.Node): xml.Node = {
    node match {
      case elem: xml.Elem =>
        val child =
          if (elem.label == "dependencies") {
            elem.child.filterNot { dep =>
              dep.child.find(_.label == "groupId").exists(gid => toExclude.contains(gid.text))
            } ++ toInclude
          } else {
            elem.child.map(changeShadedDeps(toExclude, toInclude, _))
          }
        xml.Elem(elem.prefix, elem.label, elem.attributes, elem.scope, false, child: _*)
      case _ =>
        node
    }
  }

  def getShadedDepXML(groupId: String, artifactId: String,
      version: String, scope: String): scala.xml.Node = {
    <dependency>
      <groupId>{groupId}</groupId>
      <artifactId>{artifactId}</artifactId>
      <version>{version}</version>
      <scope>{scope}</scope>
    </dependency>
  }
}
