ThisBuild / scalaVersion := "2.13.14"
lazy val root = (project in file(".")).settings(
  name := "ib-connector",
  unmanagedBase := baseDirectory.value / "lib"
)
