ThisBuild / scalaVersion := "2.13.16"
// build.sbt
Test / logBuffered := false
Test / parallelExecution := false
Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD", "-oF")

lazy val root = (project in file("."))
  .settings(
    name := "ib-connector",
    unmanagedBase := baseDirectory.value / "lib",
    libraryDependencies ++= Seq(
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
      "org.slf4j"        % "slf4j-simple"  % "2.0.13",  // <- add this
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.mockito" %% "mockito-scala-scalatest" % "1.17.37" % Test
    )
  )
