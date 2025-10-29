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

			// --- HTTP(S) + WebSocket (OkHttp) ---
			// OkHttp provides both HTTP(S) client and a WebSocket client
			"com.squareup.okhttp3" % "okhttp"               % "4.12.0",
			"com.squareup.okhttp3" % "logging-interceptor"  % "4.12.0",
			"com.squareup.okhttp3" % "okhttp-tls"           % "4.12.0",

			// --- JSON (Jackson) ---
			"com.fasterxml.jackson.core"     % "jackson-databind"       % "2.17.2",
			"com.fasterxml.jackson.core"     % "jackson-core"           % "2.17.2",
			"com.fasterxml.jackson.module"  %% "jackson-module-scala"   % "2.17.2",

			// --- Config files (HOCON) ---
			"com.typesafe" % "config" % "1.4.3",

			// --- Kubernetes (Fabric8) for runtime sharding via Kubernetes API ---
			"io.fabric8" % "kubernetes-client"            % "7.4.0",
			"io.fabric8" % "kubernetes-httpclient-okhttp" % "7.4.0",

			// --- Tests ---
			"org.scalatest" %% "scalatest" % "3.2.19" % Test,
			"org.mockito"  %% "mockito-scala-scalatest" % "1.17.37" % Test,

			// OkHttp mock web server for HTTP/WebSocket tests
			"com.squareup.okhttp3" % "mockwebserver" % "4.12.0" % Test,

			// Fabric8 mock server to unit-test k8s interactions without a cluster
			"io.fabric8" % "kubernetes-server-mock" % "7.4.0" % Test
		)
	)
