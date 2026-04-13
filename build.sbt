ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.3.7"

lazy val root = (project in file("."))
  .aggregate(simCore, simRuntimeAkka, simAlgorithms, simCli)
  .settings(
    name := "AlgoGraph"
  )

lazy val simCore = (project in file("sim-core"))
  .settings(
    name := "sim-core"
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
    name := "sim-algorithms"
  )

lazy val simCli = (project in file("sim-cli"))
  .dependsOn(simCore, simRuntimeAkka, simAlgorithms)
  .settings(
    name := "sim-cli"
  )