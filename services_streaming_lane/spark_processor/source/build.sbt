// Needed for PathList / MergeStrategy keys below
import sbtassembly.AssemblyPlugin.autoImport._
import sbtassembly.PathList

ThisBuild / scalaVersion := "2.13.16"

lazy val root = (project in file("."))
  .settings(
    name := "spark-processor",

    // If you want to drop any loose jars, put them in ./lib
    unmanagedBase := baseDirectory.value / "lib",

    libraryDependencies ++= Seq(
      // Spark itself is provided by the runtime image
      "org.apache.spark" %% "spark-core" % "4.0.1" % Provided,
      "org.apache.spark" %% "spark-sql"  % "4.0.1" % Provided,

      // Keep Kafka source in the fat jar (compile scope)
      "org.apache.spark" %% "spark-sql-kafka-0-10" % "4.0.1",

      // Tests
      "org.slf4j"     %  "slf4j-simple" % "2.0.13" % Test,
      "org.scalatest" %% "scalatest"    % "3.2.19" % Test,
      "org.mockito"   %% "mockito-scala-scalatest" % "1.17.37" % Test,

      // Your extra dep (ok to keep)
      "org.questdb"   %  "questdb" % "9.1.0"
    ),

    // sbt-assembly settings
    assembly / test := {},
    assembly / assemblyMergeStrategy := {
      // Keep SPI registrations so Spark can discover "kafka"
      case PathList("META-INF", "services", _ @ _*) => MergeStrategy.concat

      // Drop manifest & signature clutter
      case PathList("META-INF", "MANIFEST.MF")                      => MergeStrategy.discard
      case PathList("META-INF", x) if x.toLowerCase.endsWith(".sf") => MergeStrategy.discard
      case PathList("META-INF", x) if x.toLowerCase.endsWith(".dsa")=> MergeStrategy.discard
      case PathList("META-INF", x) if x.toLowerCase.endsWith(".rsa")=> MergeStrategy.discard

      // Discard other META-INF noise
      case PathList("META-INF", _ @ _*) => MergeStrategy.discard

      // Default: first wins
      case _ => MergeStrategy.first
    },
    // Don’t bundle Scala stdlib or Spark; DO include your app deps
    assembly / assemblyOption := (assembly / assemblyOption).value
      .withIncludeScala(false)
      .withIncludeDependency(true)
  )
