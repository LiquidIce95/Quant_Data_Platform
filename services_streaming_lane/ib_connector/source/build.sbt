// build.sbt

ThisBuild / scalaVersion := "2.13.16"
resolvers += "Confluent" at "https://packages.confluent.io/maven/"

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
			"com.softwaremill.sttp.client4" %% "core" % "4.0.0-RC1",
			"com.lihaoyi" %% "cask" % "0.10.2",
			
			"org.apache.commons" % "commons-math3" % "3.6.1",
			//--- confluent and avro
			"org.apache.avro"            %  "avro"                              % "1.11.3",
			"io.confluent"               %  "kafka-avro-serializer"             % "7.6.1",
			"io.confluent"               %  "kafka-schema-registry-client"      % "7.6.1",


			// --- Config files (HOCON) ---
			"com.typesafe" % "config" % "1.4.3",

			// --- Kubernetes (Fabric8) for runtime sharding via Kubernetes API ---
			"io.fabric8" % "kubernetes-client"            % "7.4.0",
			"io.fabric8" % "kubernetes-httpclient-okhttp" % "7.4.0",

			// --- Tests ---
			"org.scalatest" %% "scalatest" % "3.2.19" % Test,
			"org.mockito"  %% "mockito-scala-scalatest" % "1.17.37" % Test,
			// Fabric8 mock server to unit-test k8s interactions without a cluster
			"io.fabric8" % "kubernetes-server-mock" % "7.4.0" % Test
		)
	)
Compile / run / fork := true
Runtime / fork := true

val tlsFlags = Seq(
  "-Djavax.net.ssl.trustStore=/trust/merged.jks",
  "-Djavax.net.ssl.trustStorePassword=changeit",
  "-Djavax.net.ssl.trustStoreType=JKS",
  "-Djdk.internal.httpclient.disableHostnameVerification=true"
)

Compile / run / javaOptions ++= tlsFlags
Runtime / javaOptions            ++= tlsFlags   // <- this is what Bloop/DAP “Run | Debug” uses
Test    / javaOptions            ++= tlsFlags