name := "fiximulator"
organization := "org.fiximulator"
version := "1.0-SNAPSHOT"

resolvers ++= Seq(
  Resolver.mavenLocal,
  "MarketCetera" at "http://repo.marketcetera.org/maven"
)

libraryDependencies ++= Seq(
  "quickfixj" % "quickfixj-core"      % "1.3.1",
  "quickfixj" % "quickfixj-msg-fix40" % "1.3.1",
  "quickfixj" % "quickfixj-msg-fix41" % "1.3.1",
  "quickfixj" % "quickfixj-msg-fix42" % "1.3.1",
  "quickfixj" % "quickfixj-msg-fix43" % "1.3.1",
  "quickfixj" % "quickfixj-msg-fix44" % "1.3.1",
  "org.apache.mina" % "mina-core"       % "1.1.7",
  "org.apache.mina" % "mina-filter-ssl" % "1.1.7",
  "org.jdesktop" % "beansbinding" % "1.2.1",
  "com.cloudhopper.proxool" % "proxool"       % "0.9.1",
  "com.cloudhopper.proxool" % "proxool-cglib" % "0.9.1",
  "org.slf4j" % "slf4j-api"    % "1.6.3",
  "org.slf4j" % "slf4j-simple" % "1.6.3"
//backport-util-concurrent-2.1.jar
//sleepycat-je_2.1.30.jar
)

// Inform sbt-eclipse to not add Scala nature
EclipseKeys.projectFlavor := EclipseProjectFlavor.Java

// Remove scala dependency/version for pure Java libraries, and publish maven style
autoScalaLibrary := false
crossPaths := false
publishMavenStyle := true

parallelExecution in Test := false
testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-q")

// With this enabled, compiled jars are easier to debug in other projects
// variable names are visible.
javacOptions in compile ++= Seq("-g:lines,vars,source", "-deprecation")

//native packaging config
enablePlugins(JavaAppPackaging)
mainClass in Compile := Some("edu.harvard.fas.zfeledy.fiximulator.ui.FIXimulatorFrame")

// Disable javadoc
//javacOptions in doc += "-Xdoclint:none"
sources in (Compile,doc) := Seq.empty
publishArtifact in (Compile, packageDoc) := false

