ThisBuild / scalaVersion := "3.3.1"

lazy val root = (project in file("."))
  .settings(
    name := "WidgetDockPro",
    version := "0.1.0",
    Compile / run / fork := true
  )

