// Project name
name := "SentinelProcessing"
version := "0.1"

// Use the LTS (Long Term Support) version
// This is the version that AI models understand best.
scalaVersion := "3.3.4"

libraryDependencies ++= Seq(
  // Kafka Streams compiled NATIVELY for Scala 3
  // No need for .cross(CrossVersion.for3Use2_13)
  "org.apache.kafka" %% "kafka-streams-scala" % "3.7.0" cross(CrossVersion.for3Use2_13),
  "org.apache.kafka" % "kafka-clients" % "3.7.0",
  
  // JSON : Circe (Stable versions for Scala 3.3)
  "io.circe" %% "circe-core"    % "0.14.9",
  "io.circe" %% "circe-generic" % "0.14.9",
  "io.circe" %% "circe-parser"  % "0.14.9",

  // Logging
  "org.slf4j" % "slf4j-simple" % "2.0.16"
)

// Compilation options to help the AI with Scala 3 syntax
scalacOptions ++= Seq(
  "-rewrite",            // Helps with syntax migration if needed
  "-encoding", "utf8",
  "-deprecation",
  "-unchecked"
)

fork := true
