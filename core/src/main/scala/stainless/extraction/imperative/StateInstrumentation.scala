/* Copyright 2009-2020 EPFL, Lausanne */

package stainless
package extraction
package imperative

trait StateInstrumentation
  extends oo.CachingPhase
     with SimpleFunctions
     with IdentitySorts
     with oo.IdentityClasses
     with oo.IdentityTypeDefs
     with SimplyCachedFunctions
     with SimplyCachedSorts
     with oo.SimplyCachedClasses { self =>

  val s: Trees
  val t: s.type
  import s._

  override protected def getContext(symbols: Symbols) = new TransformerContext(symbols)

  // A tiny effect analyzer
  // TODO: Replace this with explicit, modularly-checked annotations
  trait EffectInferenceContext {
    val symbols: Symbols

    type EffectLevel = Option[Boolean] // None=NoEffect, Some(false)=reads, Some(true)=writes
    type EffectSummaries = Map[Identifier, Boolean] // EffectSummaries#get : EffectLevel
    def effectMax(el1: EffectLevel, el2: EffectLevel): EffectLevel = (el1, el2) match {
      case (Some(_), None) => el1
      case (None, Some(_)) => el2
      case (Some(w1), Some(_)) => if (w1) el1 else el2
      case _ => None
    }

    private[imperative] val effectSummaries: EffectSummaries = {
      def updateStep(summaries: EffectSummaries, fd: FunDef, iteration: Int): EffectSummaries =
        computeSummary(summaries, fd) match {
          case None => summaries
          case Some(writes) => summaries + (fd.id -> writes)
        }

      // The default call graph omits isolated functions, but we want all of them.
      val sccs = (symbols.callGraph ++ symbols.functions.keySet).stronglyConnectedComponents

      val empty: EffectSummaries = Map.empty
      val res = sccs.topSort.reverse.foldLeft(empty) { case (summaries, scc) =>
        // Fast path for the simple case of a single, non-recursive function
        if (scc.size == 1 && !symbols.isRecursive(scc.head)) {
          updateStep(summaries, symbols.getFunction(scc.head), 1)
        } else {
          val fds = scc.map(symbols.getFunction(_))
          var iteration = 0
          inox.utils.fixpoint[EffectSummaries] { summaries =>
            iteration += 1
            fds.foldLeft(summaries)(updateStep(_, _, iteration))
          } (summaries)
        }
      }
      // println(s"\n=== EFFECT SUMMARIES ===\n${res}\n")
      res
    }

    def computeSummary(summaries: EffectSummaries, fd: FunDef): Option[Boolean] = {
      var effectLevel: EffectLevel = symbols
        .callees(fd)
        .toSeq
        .map(fd => summaries.get(fd.id))
        .foldLeft(None: EffectLevel)(effectMax)

      exprOps.preTraversal {
        case _: MutableMapApply =>
          effectLevel = effectMax(effectLevel, Some(false))
        case _: MutableMapUpdate =>
          effectLevel = effectMax(effectLevel, Some(true))
        case _: ArraySelect | _: FieldAssignment | _: FiniteArray
            | _: LargeArray | _: ArrayUpdate | _: ArrayUpdated | _: MutableMapWithDefault
            | _: MutableMapUpdated | _: MutableMapDuplicate =>
          // FIXME: All of these should have been eliminated by now?
          ???
        case _ =>
      }(fd.fullBody)

      effectLevel
    }
  }

  protected class TransformerContext(val symbols: Symbols) extends EffectInferenceContext { tctx =>
    import dsl._

    // FIXME: Deduplicate across phases?
    // FIXME: Degrade gracefully when Ref is not present (assuming that no refTransform is needed)?
    lazy val RefType: Type = T(symbols.lookup.get[ADTSort]("stainless.lang.Ref").get.id)()
    lazy val HeapType: MapType = MapType(RefType, AnyType())
    lazy val TheHeap = NoTree(MutableMapType(HeapType.from, HeapType.to))

    private def freshStateParam(): ValDef = "s0" :: HeapType

    def adjustSig(fd: FunDef): FunDef = effectSummaries.get(fd.id) match {
      case None =>
        fd
      case Some(writes) =>
        fd.copy(
          params = freshStateParam() +: fd.params,
          returnType = if (writes) T(fd.returnType, HeapType) else fd.returnType
        )
    }

    private[this] val adjustedSymbols: s.Symbols =
      symbols.withFunctions(symbols.functions.values.map(adjustSig).toSeq)

    // Represent state in a functional way
    object instrumenter extends StateInstrumenter {
      val trees: self.s.type = self.s

      // We use the adjusted symbols, so we can still invoke getType during instrumentation
      implicit val symbols: trees.Symbols = adjustedSymbols

      val stateTpe = HeapType
      def dummyState: Env = FiniteMap(Seq.empty, UnitLiteral(), HeapType.from, HeapType.to)

      override def instrument(e: Expr)(implicit pc: PurityCheck): MExpr = e match {
        case MutableMapApply(`TheHeap`, key) =>
          bind(instrument(key)) { vkey =>
            { s0 =>
              Uninstrum(MapApply(s0, vkey).copiedFrom(e), s0)
            }
          }

        case MutableMapUpdate(`TheHeap`, key, value) =>
          bind(instrument(key), instrument(value)) { (vkey, vvalue) =>
            { s0 =>
              Instrum(E(
                UnitLiteral().copiedFrom(e),
                MapUpdated(s0, vkey, vvalue).copiedFrom(e)
              ))
            }
          }

        case FunctionInvocation(id, targs, args) =>
          bindMany(args.map(instrument)) { vargs =>
            effectSummaries.get(id) match {
              case None =>
                pure(FunctionInvocation(id, targs, vargs).copiedFrom(e))
              case Some(false) =>
                { s0 =>
                  Uninstrum(FunctionInvocation(id, targs, s0 +: vargs).copiedFrom(e), s0)
                }
              case Some(true) =>
                { s0 =>
                  Instrum(FunctionInvocation(id, targs, s0 +: vargs).copiedFrom(e))
                }
            }
          }

        case Lambda(params, body) =>
          val stateParam = freshStateParam().copiedFrom(e)
          val newBody = ensureInstrum(instrument(body)(pc)(stateParam.toVariable))
          val lam = Lambda(stateParam +: params, newBody).copiedFrom(e)
          pure(lam)

        case Application(callee, args) =>
          bind(instrument(callee)) { vcallee =>
            bindMany(args.map(instrument)) { vargs =>
              { s0 =>
                Instrum(Application(vcallee, s0 +: vargs).copiedFrom(e))
              }
            }
          }

        case Block(exprs, expr) =>
          bindMany(exprs.map(instrument)) { _ =>
            instrument(expr)
          }

        case Old(_) =>
          // Ignored here, but replaced separately later
          pure(e)

        case _ =>
          super.instrument(e)
      }

      override def instrument(pat: Pattern, s0: Expr): Pattern = pat match {
        case pat @ UnapplyPattern(_, recs, id, _, subPats) =>
          val newSubPats = subPats.map(p => instrument(p, s0))
          effectSummaries.get(id) match {
            case None =>
              pat.copy(subPatterns = newSubPats)
            case Some(false) =>
              pat.copy(recs = Seq(s0) ++ recs, subPatterns = newSubPats).copiedFrom(pat)
            case Some(true) =>
              ??? // FIXME: Unapply should never write to heap
          }
        case PatternExtractor(subPats, recons) =>
          recons(subPats.map(p => instrument(p, s0)))
      }
    }
  }

  override protected def extractFunction(tctx: TransformerContext, fd: FunDef): FunDef = {
    import tctx._
    import symbols._
    import instrumenter._
    import exprOps._
    import dsl._

    // FIXME: Assumes for now that there is no local mutable state in pure functions
    effectSummaries.get(fd.id) match {
      case None =>
        fd

      case Some(writes) =>
        val adjustedFd = adjustSig(fd)
        val initialState = adjustedFd.params.head.toVariable

        val (specs, bodyOpt) = deconstructSpecs(adjustedFd.fullBody)
        val newBodyOpt: Option[Expr] = bodyOpt.map { body =>
          val newBody = instrument(body)(NoPurityCheck)(initialState)
          if (writes) ensureInstrum(newBody) else newBody.asInstanceOf[Uninstrum].ve
        }

        val newSpecs = specs.map {
          case Precondition(expr) =>
            Precondition(instrumentPure(expr, initialState))

          case Measure(expr) =>
            Measure(instrumentPure(expr, initialState))

          case Postcondition(lam @ Lambda(Seq(resVd), post)) =>
            val resVd1 = if (writes) resVd.copy(tpe = T(resVd.tpe, HeapType)) else resVd
            val post1 = postMap {
              case v: Variable if writes && v.id == resVd.id =>
                Some(resVd1.toVariable._1.copiedFrom(v))
              case Old(e) =>
                Some(instrumentPure(e, initialState))
              case _ =>
                None
            }(post)

            val finalState = if (writes) resVd1.toVariable._2 else initialState
            val post2 = instrumentPure(post1, finalState)

            Postcondition(Lambda(Seq(resVd1), post2).copiedFrom(lam))
        }
        val newBody = reconstructSpecs(newSpecs, newBodyOpt, adjustedFd.returnType)

        adjustedFd.copy(fullBody = newBody)
    }
  }
}

object StateInstrumentation {
  def apply(trees: Trees)(implicit ctx: inox.Context): ExtractionPipeline {
    val s: trees.type
    val t: trees.type
  } = new StateInstrumentation {
    override val s: trees.type = trees
    override val t: trees.type = trees
    override val context = ctx
  }
}