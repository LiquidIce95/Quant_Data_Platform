// build.sbt

ThisBuild / scalaVersion := "2.13.16"

Test / logBuffered := false
Test / parallelExecution := false
Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD", "-oF")
Test / fork := true


lazy val root = (project in file("."))
  .settings(
    name := "ib-connector",
    unmanagedBase := baseDirectory.value / "lib",
    libraryDependencies ++= Seq(
      // --- Core runtime deps ---
      "org.apache.kafka" % "kafka-clients" % "3.7.0",
      "org.slf4j"        % "slf4j-simple"  % "2.0.13",

      // --- Tests ---
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.mockito"  %% "mockito-scala-scalatest" % "1.17.37" % Test,

      // --- Kubernetes (Fabric8) for runtime sharding via Kubernetes API ---
      // High-level client (CRUD, informers, leader election helpers, etc.)
      "io.fabric8" % "kubernetes-client" % "7.4.0",
      // Transport (OkHttp-based HTTP client for Fabric8 7.x)
      "io.fabric8" % "kubernetes-httpclient-okhttp" % "7.4.0",

      // Optional: handy mock server for unit tests against the k8s API (no real cluster needed)
      "io.fabric8" % "kubernetes-server-mock" % "7.4.0" % Test
    )
  )
