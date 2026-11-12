name := "aws-emul-mcp"
scalaVersion := "3.10.1"

enablePlugins(ScalaNativePlugin)

// The Kyo flags are required by Kyo (https://github.com/getkyo/kyo#getting-started): a discarded effect is a bug.
scalacOptions ++= Seq(
  "-language:strictEquality",
  "-deprecation",
  "-Werror",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error",
)

val kyoVersion = "1.0.0-RC7"

libraryDependencies ++= Seq(
  "io.getkyo" %% "kyo-http" % kyoVersion,
  "io.getkyo" %% "kyo-system" % kyoVersion,
  "org.scalameta" %% "munit" % "1.3.6" % Test,
)

skillsJarsOutputDir := Some(file(".kiro/skills"))
libraryDependencies += "com.jamesward" % "skills" % "0.0.26" % Skills

// See project/NativeLinking.scala: OpenSSL, liburing when its headers are present, non-executable stack.
nativeConfig ~= (_.withLinkingOptions(_ ++ NativeLinking.linkingOptions))
