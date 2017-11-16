package bloop
package tasks

import java.util.Optional

import bloop.util.{Progress, TopologicalSort}

import xsbti.Logger
import xsbti.compile.PreviousResult

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

object CompilationTask {

  def apply(project: Project,
            projects: Map[String, Project],
            compilerCache: CompilerCache,
            logger: Logger)(implicit ec: ExecutionContext): Map[String, Project] = {
    val toCompile = TopologicalSort.reachable(project, projects)

    val progress = new Progress(toCompile.size)
    val tasks =
      toCompile.map {
        case (name, proj) =>
          name -> new Task(
            (projects: Map[String, Project]) => doCompile(proj, projects, compilerCache, logger),
            () => progress.update())
      }

    tasks.foreach {
      case (name, task) =>
        val dependencies = projects(name).dependencies
        dependencies.foreach(dep => task.dependsOn(tasks(dep)))
    }

    Await.result(tasks(project.name).run(), Duration.Inf) match {
      case Task.Success(result) =>
        projects ++ result
      case Task.Failure(partial, reasons) =>
        reasons.foreach { throwable =>
          logger.trace(() => throwable)
        }
        // TODO: Log reasons for failure
        projects ++ partial
    }
  }

  private def doCompile(project: Project,
                        projects: Map[String, Project],
                        compilerCache: CompilerCache,
                        logger: Logger): Map[String, Project] = {
    val inputs = toCompileInputs(project, compilerCache, logger)
    val result = Compiler.compile(inputs)
    val previousResult =
      PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
    projects ++ Map(project.name -> project.copy(previousResult = previousResult))
  }

  def toCompileInputs(project: Project, cache: CompilerCache, logger: Logger): CompileInputs = {
    val instance   = project.scalaInstance
    val sourceDirs = project.sourceDirectories
    val classpath  = project.classpath
    val classesDir = project.classesDir
    val target     = project.tmp
    val previous   = project.previousResult
    CompileInputs(instance, cache, sourceDirs, classpath, classesDir, target, previous, logger)
  }
}