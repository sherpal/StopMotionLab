package movieeditor

import com.raquo.laminar.api.L.*

import scala.concurrent.{ExecutionContext, Future}

/** One user action that can be reversed: `label` describes it for the tooltip (e.g. "la suppression de 2 images"),
  * and `perform` re-issues whatever command(s) undo it, resolving to whether that reversal actually went through.
  */
final case class UndoAction(label: String, perform: () => Future[Boolean])

/** A small best-effort "undo" stack for the movie editor.
  *
  * Every place that sends a mutating command pushes an [[UndoAction]] once that command succeeds, capturing
  * whatever it needs (the previous name, the removed image ids, ...) to reverse it. Popping the stack -- via the
  * undo button or Ctrl+Z -- re-issues that reversing command.
  *
  * This is deliberately not persisted anywhere: it lives only in this page's memory and starts empty every time the
  * movie is (re)opened, covering just what happened during this visit. It's best-effort, not a full history --
  * reversing a removal re-adds the images at the end rather than at their exact former position, and popping the
  * stack twice quickly always undoes one action at a time (a second click is ignored while the first is still in
  * flight, rather than queued).
  */
final class UndoStack(maxSize: Int = 2000)(using ExecutionContext) {
  private val stackVar   = Var(List.empty[UndoAction])
  private val undoingVar = Var(false)

  def signal: Signal[List[UndoAction]] = stackVar.signal

  def canUndoSignal: Signal[Boolean] = stackVar.signal.map(_.nonEmpty)

  def push(action: UndoAction): Unit =
    stackVar.update(current => (action :: current).take(maxSize))

  /** Marks that a movie-mutating command was just sent locally, so the passive "something changed" watcher (used to
    * catch changes with no dedicated undo call site, e.g. a photo added from the phone) can tell that the next
    * update(s) it sees are already accounted for by whoever sent that command, and skip them. A counter rather than
    * a flag, so that two locally-sent commands still in flight at once don't cause the first of their two resulting
    * updates to un-mark the second.
    */
  def markLocalChange(): Unit = pendingLocalChangesVar.update(_ + 1)
  private val pendingLocalChangesVar = Var(0)

  /** Get-and-decrement: true if a locally-sent command is expected to be the cause of the update being processed. */
  def consumeLocalChange(): Boolean = {
    val pending = pendingLocalChangesVar.now()
    if pending > 0 then pendingLocalChangesVar.set(pending - 1)
    pending > 0
  }

  /** Pops and reverses the last action, unless the stack is empty or an undo is already in flight. */
  def undo(): Unit =
    if !undoingVar.now() then
      stackVar.now() match {
        case action :: rest =>
          undoingVar.set(true)
          stackVar.set(rest)
          action.perform().onComplete(_ => undoingVar.set(false))
        case Nil => ()
      }

}
