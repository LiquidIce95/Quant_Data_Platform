ThisBuild / scalaVersion := "2.13.16"

lazy val root = (project in file(".")).settings(
  name := "ib-connector",
  unmanagedBase := baseDirectory.value / "lib",      // picks up lib/TwsApi.jar
  libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.6"
)
