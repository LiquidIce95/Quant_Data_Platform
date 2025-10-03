ThisBuild / scalaVersion := "2.13.14"

lazy val root = (project in file("."))
  .settings(
    name := "ib-connector",
    unmanagedBase := baseDirectory.value / "lib",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      // optional but handy for mocking collaborators
      "org.mockito" %% "mockito-scala-scalatest" % "1.17.37" % Test
    )
  )
