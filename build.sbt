ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.7"

// Akka secure repository — required to resolve Cinnamon artifacts
// Token is safe to check into source control per Lightbend's guidance.
ThisBuild / resolvers += "Akka Secure" at
  "https://repo.akka.io/dmXUXaJVn15EgArJIJOgWOC6kB8y_xieky4uAo4EGOct8VpW/secure"

lazy val root = (project in file("."))
  .aggregate(simCore, simRuntimeAkka, simAlgorithms, simCli)
  .settings(
    name := "AlgoGraph"
  )

lazy val simCore = (project in file("sim-core"))
  .settings(
    name := "sim-core",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
  )

lazy val simRuntimeAkka = (project in file("sim-runtime-akka"))
  .dependsOn(simCore, simAlgorithms)
  .settings(
    name := "sim-runtime-akka",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.8.8"
    )
  )

lazy val simAlgorithms = (project in file("sim-algorithms"))
  .dependsOn(simCore)
  .settings(
    name := "sim-algorithms",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    )
  )

lazy val simCli = (project in file("sim-cli"))
  .dependsOn(simCore, simRuntimeAkka, simAlgorithms)
  .enablePlugins(Cinnamon)
  .settings(
    name := "sim-cli",

    // Enable Cinnamon agent for sbt run and sbt test
    run  / cinnamon := true,
    test / cinnamon := true,

    cinnamonLogLevel := "INFO",

    libraryDependencies ++= Seq(
      "com.typesafe"      %  "config"                       % "1.4.2",
      Cinnamon.library.cinnamonAkka,
      Cinnamon.library.cinnamonCHMetrics,
      Cinnamon.library.cinnamonJvmMetricsProducer
    )
  )
