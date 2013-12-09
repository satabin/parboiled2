/*
 * Copyright (C) 2009-2013 Mathias Doenitz, Alexander Myltsev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.parboiled2

import scala.reflect.macros.Context
import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder
import shapeless._
import scala.util.control.NoStackTrace

abstract class Parser extends RuleDSL {
  import Parser._

  def input: ParserInput

  def rule[I <: HList, O <: HList](r: Rule[I, O]): Rule[I, O] = macro ruleImpl[I, O]

  /**
   * Pretty prints the given `ParseError` instance in the context of the `ParserInput` of this parser.
   */
  def formatError(error: ParseError): String = {
    val ParseError(Position(index, line, col), ruleStacks) = error
    val problem =
      if (index < input.length) s"Invalid input '${input charAt index}'"
      else "Unexpected end of input"

    problem + ", "
    s"expected ${ruleStacks.map(x ⇒ RuleStack(x.frames.reverse)) mkString ("\n", "\n\n", "\n")} " +
      s"(line $line, column $col): \n" +
      s"${input.getLine(line)}\n" + (" " * (col - 1)) + '^'
  }

  ////////////////////// INTERNAL /////////////////////////

  // the index of the current input char
  private[this] var cursor: Int = _

  // the highest cursor value we have seen in the current rule run
  private[this] var errorIndex: Int = _

  // the number of times we have already seen a character mismatch at the error index
  private[this] var mismatchesAtErrorIndex: Int = _

  // the index of the RuleStack we are currently constructing
  // for the ParserError to be returned in the very first parser run,
  // as long as we do not yet know whether we have to construct a ParserError object this value is -1
  private[this] var currentErrorRuleStackIx: Int = _

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  val __valueStack = new ValueStack

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __run[L <: HList](rule: ⇒ RuleN[L]): Result[L] = {
    def runRule(errorRuleStackIx: Int = -1): Boolean = {
      cursor = -1
      __valueStack.clear()
      mismatchesAtErrorIndex = 0
      currentErrorRuleStackIx = errorRuleStackIx
      rule.matched
    }
    @tailrec def buildParseError(errorRuleIx: Int = 0,
                                 stacksBuilder: VectorBuilder[RuleStack] = new VectorBuilder): ParseError = {
      val ruleFrames: Seq[RuleFrame] =
        try {
          runRule(errorRuleIx)
          Nil // we managed to complete the run w/o exception, i.e. we have collected all frames
        } catch {
          case e: Parser.CollectingRuleStackException ⇒ e.ruleFrames
        }
      if (ruleFrames.isEmpty) ParseError(errorPosition(), stacksBuilder.result())
      else buildParseError(errorRuleIx + 1, stacksBuilder += RuleStack(ruleFrames))
    }
    errorIndex = 0
    if (runRule())
      Right(__valueStack.toHList[L]())
    else
      Left(buildParseError())
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __nextChar(): Char = {
    val nextCursor = cursor + 1
    if (nextCursor < input.length) {
      cursor = nextCursor
      if (currentErrorRuleStackIx == -1 && nextCursor > errorIndex)
        errorIndex = nextCursor // if we are in the first "regular" parser run, we need to advance the errorIndex here
      input.charAt(nextCursor)
    } else EOI
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __markCursorAndValueStack: Mark = new Mark((cursor.toLong << 32) + __valueStack.top)

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __resetCursorAndValueStack(mark: Mark): Unit = {
    cursor = (mark.value >>> 32).toInt
    __valueStack.top = (mark.value & 0x00000000FFFFFFFF).toInt
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __markCursor: Int = cursor

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __resetCursor(mark: Int): Unit = cursor = mark

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __sliceInput(start: Int): String = input.sliceString(start + 1, cursor + 1)

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  def __registerCharMismatch(): Unit =
    if (currentErrorRuleStackIx != -1 && cursor == errorIndex) {
      if (mismatchesAtErrorIndex < currentErrorRuleStackIx) mismatchesAtErrorIndex += 1
      else throw new Parser.CollectingRuleStackException
    }

  @tailrec
  private def errorPosition(ix: Int = math.min(errorIndex, input.length - 1), line: Int = 1, col: Int = -1): Position =
    if (ix < 0) Position(errorIndex, line, if (col == -1) errorIndex + 1 else col)
    else if (input.charAt(ix) != '\n') errorPosition(ix - 1, line, col)
    else errorPosition(ix - 1, line + 1, if (col == -1) errorIndex - ix else col)
}

object Parser {
  class Mark private[Parser] (val value: Long) extends AnyVal

  // TODO: please everyone all the time
  type Result[L <: HList] = Either[ParseError, L]

  type RunnableRuleContext[L <: HList] = Context { type PrefixType = Rule.Runnable[L] }

  def runImpl[L <: HList: ctx.WeakTypeTag](ctx: RunnableRuleContext[L])(): ctx.Expr[Result[L]] = {
    import ctx.universe._
    ctx.prefix.tree match {
      case q"parboiled2.this.Rule.Runnable[$l]($parser.$rule)" ⇒ ctx.Expr[Result[L]](q"$parser.__run[$l]($parser.$rule)")
      case x ⇒ ctx.abort(x.pos, "Illegal `run` call: " + show(x))
    }
  }

  type ParserContext = Context { type PrefixType = Parser }

  def ruleImpl[I <: HList: ctx.WeakTypeTag, O <: HList: ctx.WeakTypeTag](ctx: ParserContext)(r: ctx.Expr[Rule[I, O]]): ctx.Expr[Rule[I, O]] = {
    val opTreeCtx = new OpTreeContext[ctx.type] { val c: ctx.type = ctx }
    val opTree = opTreeCtx.OpTree(r.tree)
    val ruleName = ctx.enclosingMethod.asInstanceOf[ctx.universe.DefDef].name.toString
    ctx.universe.reify {
      opTree.render(ruleName).splice.asInstanceOf[Rule[I, O]]
    }
  }

  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  class CollectingRuleStackException extends RuntimeException with NoStackTrace {
    private[this] val frameBuilder = new VectorBuilder[RuleFrame]
    def save(frame: RuleFrame): Nothing = {
      frameBuilder += frame
      throw this
    }
    def ruleFrames: Seq[RuleFrame] = frameBuilder.result()
  }
}