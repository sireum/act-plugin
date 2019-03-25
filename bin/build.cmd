::#! 2> /dev/null                                             #
@ 2>/dev/null # 2>nul & echo off & goto BOF                   #
                                                              #
export ACT_HOME=$(cd -P $(dirname "$0")/.. && pwd -P)         #
                                                              #
export SIREUM_HOME=${ACT_HOME}/sireum                         #
${SIREUM_HOME}/bin/build.cmd                                  #
                                                              #
exec ${SIREUM_HOME}/bin/sireum slang run -s -n "$0" "$@"      #
:BOF
if not defined SIREUM_HOME (
  echo Please set SIREUM_HOME env var
  exit /B -1
)
%SIREUM_HOME%\bin\sireum.bat slang run -s -n "%0" %*
exit /B %errorlevel%
::!#
// #Sireum
import org.sireum._

val homeBin: Os.Path = Os.slashDir
val home = homeBin.up

val sireumHome = home / "sireum"
val sireumBin = sireumHome / "bin"
val sireum = sireumBin / (if (Os.isWin) "sireum.bat" else "sireum")
val mill = sireumBin / (if (Os.isWin) "mill.bat" else "mill")


val options: HashSMap[String, () => Unit] = HashSMap ++ ISZ(
  ("build", build _),
  ("regen-cli", regenCli _),
  ("tipe", tipe _),
  ("-h", usage _),
  ("--help", usage _))


def regenCli(): Unit = {
  val sireumPackagePath = home / "cli" / "jvm" / "src" / "main" / "scala" / "org" / "sireum"

  println("Generating CLI")

  Os.proc(ISZ(sireum.value, "tools", "cligen", "-p", "org.sireum", "-n", "ActCli", "-l", (home / "license.txt").value,
    (sireumPackagePath / "cli.sc").value)).at(sireumPackagePath).console.runCheck()

  println()
}

def tipe(): Unit = {
  println("Slang type checking ...")

  Os.proc(ISZ(sireum.value, "slang", "tipe", "--verbose", "-r", "-s", home.value)).at(home).console.runCheck()

  println()
}

def build(): Unit = {
  tipe()

  (sireumHome / "versions.properties").copyOverTo(home / "versions.properties")

  println("Building ...")

  Os.proc(ISZ(mill.string, "all", "cli.assembly", "act.jvm.tests", "cli.tests")).at(home).console.runCheck()

  val out = home / "out" / "cli" / "assembly" / "dest" / "out.jar"
  val dest = homeBin / s"act${if(Os.isWin) ".bat" else ""}"
  out.copyOverTo(dest)
  dest.chmod("+x")

  println(s"ACT available at: ${dest}")
}


def usage(): Unit = {
  println(st"""Act /build
              |Usage: (${(options.keys, " | ")})""".render)
}

if (Os.cliArgs.isEmpty) {
  usage()
} else {
  Os.cliArgs.foreach { arg =>
    options.get(arg) match {
      case Some(f) => f()
      case _ =>
        eprintln(s"Unrecognized command: $arg")
        Os.exit(-1)
    }
  }
}